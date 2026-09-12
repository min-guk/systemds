/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Incrementally restores equalities in an explicit mini-bucket replica model.
 *
 * <p>The replica factor graph is solved as independent factor-connected
 * components. One refinement atomically restores all currently disconnected
 * replica groups of a bounded connected batch of encoded variables and re-solves
 * only the components
 * touched by that batch; all other exact results are retained. Published
 * component values and their sum are rounded downward, so every completed
 * state remains a lower bound on the original model.</p>
 */
final class IncrementalReplicaBound {
	private static final int MAXIMUM_PROBES = 2;
	private static final int MAXIMUM_BATCH_VARIABLES = 32;

	private final MiniBucketLowerBound.ReplicaModel model;
	private final long partitionPlanningNanos;
	private final ExactCategoricalSolver.Limits limits;
	private final long maximumWork;
	private final boolean reusePreparation;
	private final ReplicaComponentPreparation componentPreparation = new ReplicaComponentPreparation();
	private final Map<String, Integer> replicaByKey = new HashMap<>();
	private final IdentityHashMap<ExactCategoricalSolver.Variable, Integer> replicaPosition =
		new IdentityHashMap<>();
	private final IdentityHashMap<ExactCategoricalSolver.Factor, Integer> factorOrder =
		new IdentityHashMap<>();
	private final int[] originalByReplica;
	private final List<int[]> replicasByOriginal;
	private final int[] equalityParent;
	private final Set<ExactCategoricalSolver.Factor> restoredEqualityFactors =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<VariableKey> resourceBlocked = new HashSet<>();
	private List<Component> components;
	private int[] componentByReplica;
	private double lowerBound;
	private int restoredEqualities;
	private int nextFactorOrder;

	private long exactCalls;
	private long assignments;
	private long materializedCells;
	private long maximumFactorCells;
	private long preparationNanos;
	private long solveNanos;
	private long probes;
	private long reusedComponents;
	private long resourceSkips;
	private long batchProbes;
	private long batchFallbacks;
	private int maximumBatchVariables;

	record Work(long calls, long assignments, long materializedCells,
		long maximumFactorCells, long preparationNanos, long solveNanos,
		long probes, long reusedComponents, long resourceSkips,
		long batchProbes, long batchFallbacks, int maximumBatchVariables,
		long preparedOrderHits, long preparedOrderMisses, long preparedOrderFallbacks) { }

	record Selection(int modalMinorityGroups, int representativeGroups,
		int touchedComponents, long cachedAssignments, long plannedAssignments,
		long measuredNanos, int batchVariables) {
		Selection(int modalMinorityGroups, int representativeGroups, int touchedComponents,
			long cachedAssignments, long plannedAssignments, long measuredNanos) {
			this(modalMinorityGroups, representativeGroups, touchedComponents,
				cachedAssignments, plannedAssignments, measuredNanos, 0);
		}
	}

	record Refinement(boolean changed, int originalVariable,
		List<Integer> affectedOriginalVariables, double lowerBound, double gain,
		long elapsedNanos, Selection selection, List<Integer> restoredOriginalVariables) {
		Refinement {
			affectedOriginalVariables = List.copyOf(affectedOriginalVariables);
			restoredOriginalVariables = List.copyOf(restoredOriginalVariables);
			Objects.requireNonNull(selection, "selection");
		}
		Refinement(boolean changed, int originalVariable, List<Integer> affectedOriginalVariables,
			double lowerBound, double gain, long elapsedNanos, Selection selection) {
			this(changed, originalVariable, affectedOriginalVariables, lowerBound, gain,
				elapsedNanos, selection, originalVariable < 0 ? List.of() : List.of(originalVariable));
		}
	}
	record Closure(int unresolvedVariables, int affectedComponents,
		int solvedComponents, int reusedComponents, int reusedOriginalVariables,
		long reusedAssignments, int largestOriginalVariables, long elapsedNanos) { }
	private record EqualityGroup(int originalVariable, List<Integer> representatives) {
		EqualityGroup { representatives = List.copyOf(representatives); }
	}

	private record VariableCandidate(int originalVariable, List<Integer> representatives,
		int modalMinorityGroups, List<Integer> touchedComponents, long scopedWorkProxy,
		List<EqualityGroup> groups) {
		VariableCandidate {
			representatives = List.copyOf(representatives);
			touchedComponents = List.copyOf(touchedComponents);
			groups = List.copyOf(groups);
		}
		VariableCandidate(int originalVariable, List<Integer> representatives,
			int modalMinorityGroups, List<Integer> touchedComponents, long scopedWorkProxy) {
			this(originalVariable, representatives, modalMinorityGroups, touchedComponents,
				scopedWorkProxy, List.of(new EqualityGroup(originalVariable, representatives)));
		}
	}
	private record VariableKey(List<Integer> originalVariables, List<List<Integer>> roots) {
		VariableKey {
			originalVariables = List.copyOf(originalVariables);
			roots = roots.stream().map(List::copyOf).toList();
		}
	}
	private record Trial(VariableCandidate candidate, List<ExactCategoricalSolver.Factor> equalities,
		Component component,
		List<Integer> touched, List<Integer> affectedOriginalVariables,
		double proposedLowerBound, double gain, long measuredNanos,
		long plannedAssignments) {
		Trial { equalities = List.copyOf(equalities); }
	}
	private record ContractedComponent(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int[] contractedVariableByOriginal) {
		ContractedComponent {
			variables = List.copyOf(variables);
			factors = List.copyOf(factors);
			contractedVariableByOriginal = contractedVariableByOriginal.clone();
		}
	}

	private static final class Component {
		private final List<ExactCategoricalSolver.Variable> variables;
		private final List<ExactCategoricalSolver.Factor> factors;
		private final ExactCategoricalSolver.Result result;
		private final double lowerBound;

		private Component(List<ExactCategoricalSolver.Variable> variables,
			List<ExactCategoricalSolver.Factor> factors,
			ExactCategoricalSolver.Result result, double lowerBound) {
			this.variables = List.copyOf(variables);
			this.factors = List.copyOf(factors);
			this.result = result;
			this.lowerBound = lowerBound;
		}
	}

	static IncrementalReplicaBound create(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int width,
		ExactCategoricalSolver.Limits limits, long maximumWork,
		BooleanSupplier cancelled) {
		return create(variables, factors, width, limits, maximumWork, false, cancelled);
	}

	static IncrementalReplicaBound create(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int width,
		ExactCategoricalSolver.Limits limits, long maximumWork, boolean reusePreparation,
		BooleanSupplier cancelled) {
		return create(variables, factors, width, limits, maximumWork, reusePreparation,
			new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.INPUT,
				MiniBucketLowerBound.PartitionStrategy.FIRST_FIT), cancelled);
	}

	static IncrementalReplicaBound create(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int width,
		ExactCategoricalSolver.Limits limits, long maximumWork, boolean reusePreparation,
		MiniBucketLowerBound.PlanningPolicy policy, BooleanSupplier cancelled) {
		Objects.requireNonNull(cancelled, "cancelled");
		if(maximumWork <= 0)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_WORK_LIMIT_INVALID");
		checkCancelled(cancelled);
		long started = System.nanoTime();
		MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
			variables, factors, width, limits, policy, cancelled);
		return new IncrementalReplicaBound(replica, limits, maximumWork, reusePreparation,
			System.nanoTime() - started, cancelled);
	}

	private IncrementalReplicaBound(MiniBucketLowerBound.ReplicaModel model,
		ExactCategoricalSolver.Limits limits, long maximumWork, boolean reusePreparation,
		long partitionPlanningNanos, BooleanSupplier cancelled) {
		this.model = model;
		this.partitionPlanningNanos = partitionPlanningNanos;
		this.limits = Objects.requireNonNull(limits, "limits");
		this.maximumWork = maximumWork;
		this.reusePreparation = reusePreparation;
		for(int index = 0; index < model.variables().size(); index++) {
			replicaPosition.put(model.variables().get(index), index);
			replicaByKey.put(model.variables().get(index).key(), index);
		}
		for(ExactCategoricalSolver.Factor factor : model.factors())
			factorOrder.put(factor, nextFactorOrder++);
		originalByReplica = new int[model.variables().size()];
		replicasByOriginal = new ArrayList<>(model.replicas().size());
		for(int original = 0; original < model.replicas().size(); original++) {
			int[] positions = new int[model.replicas().get(original).size()];
			for(int replica = 0; replica < positions.length; replica++) {
				positions[replica] = position(model.replicas().get(original).get(replica));
				originalByReplica[positions[replica]] = original;
			}
			replicasByOriginal.add(positions);
		}
		equalityParent = new int[model.variables().size()];
		for(int index = 0; index < equalityParent.length; index++)
			equalityParent[index] = index;
		components = initialComponents(cancelled);
		componentByReplica = buildComponentIndex(components);
		lowerBound = sumComponents(components);
	}

	double lowerBound() { return lowerBound; }
	String partitionIdentity() { return model.partitionIdentity(); }
	List<Integer> eliminationOrder() { return model.eliminationOrder(); }
	long partitionPlanningNanos() { return partitionPlanningNanos; }
	int replicaVariables() { return model.variables().size(); }
	int componentCount() { return components.size(); }
	int restoredEqualities() { return restoredEqualities; }
	int maximumComponentVariables() {
		return components.stream().mapToInt(component -> component.variables.size()).max().orElse(0);
	}
	boolean fullyRestored() {
		for(int[] group : replicasByOriginal)
			for(int index = 1; index < group.length; index++)
				if(find(group[0]) != find(group[index]))
					return false;
		return true;
	}
	boolean hasRefinementCandidates() { return !candidates().isEmpty(); }
	Work workStats() {
		ReplicaComponentPreparation.Counters prepared = componentPreparation.counters();
		return new Work(exactCalls, assignments, materializedCells, maximumFactorCells,
			preparationNanos, solveNanos, probes, reusedComponents, resourceSkips,
			batchProbes, batchFallbacks, maximumBatchVariables,
			prepared.reuseHits(), prepared.reuseMisses(), prepared.reuseFallbacks());
	}

	List<Integer> suggestedAssignment(boolean majority) {
		int[] values = replicaAssignment();
		List<Integer> result = new ArrayList<>(replicasByOriginal.size());
		for(int[] group : replicasByOriginal) {
			if(!majority) {
				result.add(values[group[0]]);
				continue;
			}
			int domain = model.variables().get(group[0]).domainSize();
			int[] counts = new int[domain];
			for(int replica : group)
				counts[values[replica]]++;
			int best = 0;
			for(int value = 1; value < counts.length; value++)
				if(counts[value] > counts[best])
					best = value;
			result.add(best);
		}
		return List.copyOf(result);
	}

	/**
	 * Restores every equality still missing at entry. Missing equality groups that
	 * connect the same current components are solved together once. Each completed
	 * connected group is committed independently, so a later resource limit or
	 * cancellation preserves all earlier completed coverage.
	 */
	Closure closeRemaining(BooleanSupplier cancelled) {
		Objects.requireNonNull(cancelled, "cancelled");
		checkCancelled(cancelled);
		long started = System.nanoTime();
		List<EqualityGroup> missing = unresolvedEqualityGroups();
		int entryComponents = components.size();
		Set<Integer> affected = new HashSet<>();
		for(EqualityGroup group : missing)
			for(int representative : group.representatives)
				affected.add(componentByReplica[representative]);
		Set<Integer> untouched = new HashSet<>();
		for(int component = 0; component < entryComponents; component++)
			if(!affected.contains(component))
				untouched.add(component);
		Set<Integer> reusedOriginals = new HashSet<>();
		long reusedWork = 0L;
		for(int component : untouched) {
			for(ExactCategoricalSolver.Variable variable : components.get(component).variables)
				reusedOriginals.add(originalByReplica[position(variable)]);
			reusedWork = saturatedAdd(reusedWork,
				components.get(component).result.statistics().eliminationAssignments());
		}
		if(missing.isEmpty())
			return new Closure(0, 0, 0, untouched.size(), reusedOriginals.size(),
				reusedWork, 0, System.nanoTime() - started);

		List<List<EqualityGroup>> closureGroups = connectedClosureGroups(missing);
		int solved = 0;
		int largest = 0;
		for(List<EqualityGroup> groups : closureGroups) {
			checkCancelled(cancelled);
			VariableCandidate candidate = closureCandidate(groups);
			Trial completed = trial(candidate, cancelled);
			checkCancelled(cancelled);
			commit(completed);
			solved++;
			largest = Math.max(largest, completed.affectedOriginalVariables.size());
		}
		if(!fullyRestored())
			throw new IllegalStateException("INCREMENTAL_REPLICA_CLOSURE_INCOMPLETE");
		return new Closure(missing.size(), affected.size(), solved, untouched.size(),
			reusedOriginals.size(), reusedWork, largest, System.nanoTime() - started);
	}

	/** Evaluates the completed diagonal assignment over lifted source factors in order. */
	double exactObjective() {
		if(!fullyRestored())
			throw new IllegalStateException("INCREMENTAL_REPLICA_EXACT_NOT_RESTORED");
		int[] replicaValues = replicaAssignment();
		List<Integer> originalValues = new ArrayList<>(replicasByOriginal.size());
		for(int original = 0; original < replicasByOriginal.size(); original++) {
			int[] replicas = replicasByOriginal.get(original);
			int value = replicaValues[replicas[0]];
			for(int replica : replicas)
				if(replicaValues[replica] != value)
					throw new IllegalStateException(
						"INCREMENTAL_REPLICA_EXACT_ASSIGNMENT_INCONSISTENT|original=" + original);
			originalValues.add(value);
		}
		List<Integer> diagonal = model.diagonalAssignment(originalValues);
		return evaluateReplicaFactors(diagonal);
	}

	Refinement refine(int maximumCandidateProbes, BooleanSupplier cancelled) {
		return refine(maximumCandidateProbes, 1, cancelled);
	}

	Refinement refine(int maximumCandidateProbes, int batchLimit, BooleanSupplier cancelled) {
		if(maximumCandidateProbes < 1 || maximumCandidateProbes > MAXIMUM_PROBES)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_PROBE_LIMIT_INVALID");
		if(batchLimit < 1 || batchLimit > MAXIMUM_BATCH_VARIABLES)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_BATCH_LIMIT_INVALID");
		Objects.requireNonNull(cancelled, "cancelled");
		checkCancelled(cancelled);
		long started = System.nanoTime();
		List<VariableCandidate> candidates = candidates();
		Trial bestPositive = null;
		Trial cheapestZero = null;
		List<VariableKey> newlyBlocked = new ArrayList<>();
		Set<VariableKey> attempted = new HashSet<>();
		for(int index = 0; index < Math.min(maximumCandidateProbes, candidates.size()); index++) {
			checkCancelled(cancelled);
			VariableCandidate single = candidates.get(index);
			VariableCandidate candidate = connectedBatch(single, candidates, batchLimit);
			if(!attempted.add(key(candidate)))
				continue;
			Trial trial;
			try {
				trial = probe(candidate, cancelled);
			}
			catch(MiniBucketLowerBound.ResourceLimitException limited) {
				resourceSkips = saturatedAdd(resourceSkips, 1L);
				if(candidate.groups.size() == 1) {
					newlyBlocked.add(key(single));
					continue;
				}
				// A failed joint trial says nothing about the cost of its seed's
				// single-variable action. Try that once before blocking it.
				batchFallbacks = saturatedAdd(batchFallbacks, 1L);
				try { trial = probe(single, cancelled); }
				catch(MiniBucketLowerBound.ResourceLimitException singleLimited) {
					resourceSkips = saturatedAdd(resourceSkips, 1L);
					newlyBlocked.add(key(single));
					continue;
				}
			}
			checkCancelled(cancelled);
			if(trial.gain > 0d) {
				if(bestPositive == null || betterRate(trial, bestPositive))
					bestPositive = trial;
			}
			else if(cheapestZero == null || trial.measuredNanos < cheapestZero.measuredNanos
				|| trial.measuredNanos == cheapestZero.measuredNanos
					&& compare(trial.candidate, cheapestZero.candidate) < 0)
				cheapestZero = trial;
		}
		checkCancelled(cancelled);
		resourceBlocked.addAll(newlyBlocked);
		Trial selected = bestPositive != null ? bestPositive : cheapestZero;
		if(selected == null)
			return new Refinement(false, -1, List.of(), lowerBound, 0d,
				System.nanoTime() - started, new Selection(0, 0, 0, 0L, 0L, 0L));

		double previous = lowerBound;
		commit(selected);
		Selection selection = new Selection(selected.candidate.modalMinorityGroups,
			selected.candidate.groups.stream().mapToInt(group -> group.representatives.size()).sum(),
			selected.candidate.touchedComponents.size(), selected.candidate.scopedWorkProxy,
			selected.plannedAssignments, selected.measuredNanos, selected.candidate.groups.size());
		return new Refinement(true, selected.candidate.originalVariable,
			selected.affectedOriginalVariables, lowerBound, nonNegativeDifference(lowerBound, previous),
			System.nanoTime() - started, selection,
			selected.candidate.groups.stream().map(EqualityGroup::originalVariable).toList());
	}

	private Trial probe(VariableCandidate candidate, BooleanSupplier cancelled) {
		probes = saturatedAdd(probes, 1L);
		if(candidate.groups.size() > 1)
			batchProbes = saturatedAdd(batchProbes, 1L);
		maximumBatchVariables = Math.max(maximumBatchVariables, candidate.groups.size());
		return trial(candidate, cancelled);
	}

	/** Greedily grows within already touched components before opening new ones. */
	private VariableCandidate connectedBatch(VariableCandidate seed,
		List<VariableCandidate> candidates, int limit) {
		if(limit == 1)
			return seed;
		List<EqualityGroup> groups = new ArrayList<>(seed.groups);
		Set<Integer> originals = new HashSet<>();
		originals.add(seed.originalVariable);
		Set<Integer> touched = new HashSet<>(seed.touchedComponents);
		int minority = seed.modalMinorityGroups;
		while(groups.size() < limit) {
			VariableCandidate best = null;
			int bestNewComponents = Integer.MAX_VALUE;
			for(VariableCandidate candidate : candidates) {
				if(originals.contains(candidate.originalVariable))
					continue;
				int added = 0;
				for(int component : candidate.touchedComponents)
					if(!touched.contains(component))
						added++;
				if(added == candidate.touchedComponents.size())
					continue;
				if(added < bestNewComponents) {
					best = candidate;
					bestNewComponents = added;
				}
			}
			if(best == null)
				break;
			groups.addAll(best.groups);
			originals.add(best.originalVariable);
			touched.addAll(best.touchedComponents);
			minority += best.modalMinorityGroups;
		}
		List<Integer> ordered = touched.stream().sorted().toList();
		long work = 0L;
		for(int component : ordered)
			work = saturatedAdd(work, components.get(component).result.statistics().eliminationAssignments());
		return new VariableCandidate(seed.originalVariable, seed.representatives,
			minority, ordered, work, groups);
	}

	private List<EqualityGroup> unresolvedEqualityGroups() {
		List<EqualityGroup> result = new ArrayList<>();
		for(int original = 0; original < replicasByOriginal.size(); original++) {
			List<Integer> representatives = new ArrayList<>();
			Set<Integer> roots = new HashSet<>();
			for(int replica : replicasByOriginal.get(original)) {
				int root = find(replica);
				if(roots.add(root))
					representatives.add(replica);
			}
			if(representatives.size() > 1)
				result.add(new EqualityGroup(original, representatives));
		}
		return List.copyOf(result);
	}

	private List<List<EqualityGroup>> connectedClosureGroups(List<EqualityGroup> missing) {
		int[] parent = new int[components.size()];
		for(int component = 0; component < parent.length; component++)
			parent[component] = component;
		for(EqualityGroup group : missing) {
			int anchor = componentByReplica[group.representatives.get(0)];
			for(int index = 1; index < group.representatives.size(); index++)
				union(parent, anchor, componentByReplica[group.representatives.get(index)]);
		}
		Map<Integer,List<EqualityGroup>> grouped = new LinkedHashMap<>();
		for(EqualityGroup group : missing) {
			int component = componentByReplica[group.representatives.get(0)];
			grouped.computeIfAbsent(find(parent, component), ignored -> new ArrayList<>())
				.add(group);
		}
		return grouped.values().stream().map(List::copyOf).toList();
	}

	private VariableCandidate closureCandidate(List<EqualityGroup> groups) {
		Set<Integer> touched = new HashSet<>();
		for(EqualityGroup group : groups)
			for(int representative : group.representatives)
				touched.add(componentByReplica[representative]);
		List<Integer> ordered = touched.stream().sorted().toList();
		long work = 0L;
		for(int component : ordered)
			work = saturatedAdd(work,
				components.get(component).result.statistics().eliminationAssignments());
		EqualityGroup first = groups.get(0);
		return new VariableCandidate(first.originalVariable, first.representatives,
			0, ordered, work, groups);
	}

	private List<Component> initialComponents(BooleanSupplier cancelled) {
		int variableCount = model.variables().size();
		int[] parent = new int[variableCount];
		for(int index = 0; index < variableCount; index++)
			parent[index] = index;
		for(ExactCategoricalSolver.Factor factor : model.factors()) {
			if(factor.scope().isEmpty())
				continue;
			int first = position(factor.scope().get(0));
			for(int index = 1; index < factor.scope().size(); index++)
				union(parent, first, position(factor.scope().get(index)));
		}
		Map<Integer, List<ExactCategoricalSolver.Variable>> variables = new LinkedHashMap<>();
		for(int index = 0; index < variableCount; index++)
			variables.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>())
				.add(model.variables().get(index));
		Map<Integer, List<ExactCategoricalSolver.Factor>> factors = new LinkedHashMap<>();
		List<ExactCategoricalSolver.Factor> constants = new ArrayList<>();
		for(ExactCategoricalSolver.Factor factor : model.factors()) {
			if(factor.scope().isEmpty())
				constants.add(factor);
			else
				factors.computeIfAbsent(find(parent, position(factor.scope().get(0))),
					ignored -> new ArrayList<>()).add(factor);
		}
		List<Component> result = new ArrayList<>();
		for(Map.Entry<Integer, List<ExactCategoricalSolver.Variable>> entry : variables.entrySet())
			result.add(solveComponent(entry.getValue(), factors.getOrDefault(entry.getKey(), List.of()),
				0d, cancelled));
		if(!constants.isEmpty())
			result.add(solveComponent(List.of(), constants, 0d, cancelled));
		return List.copyOf(result);
	}

	private Trial trial(VariableCandidate candidate, BooleanSupplier cancelled) {
		List<Integer> touched = candidate.touchedComponents;
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int component : touched) {
			variables.addAll(components.get(component).variables);
			factors.addAll(components.get(component).factors);
		}
		variables.sort(Comparator.comparingInt(this::position));
		factors.sort(Comparator.comparingInt(
			factor -> factorOrder.getOrDefault(factor, Integer.MAX_VALUE)));
		List<ExactCategoricalSolver.Factor> equalities = new ArrayList<>();
		for(EqualityGroup group : candidate.groups) {
			ExactCategoricalSolver.Variable anchor = model.variables().get(group.representatives.get(0));
			for(int index = 1; index < group.representatives.size(); index++) {
				ExactCategoricalSolver.Variable replica = model.variables().get(group.representatives.get(index));
				ExactCategoricalSolver.Factor equality = ExactCategoricalSolver.Factor.lazy(
					List.of(anchor, replica),
					values -> values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);
				equalities.add(equality);
				factors.add(equality);
			}
		}
		double previousAffected = 0d;
		for(int component : touched)
			previousAffected = addDown(previousAffected, components.get(component).lowerBound);
		long beforePreparation = preparationNanos;
		long beforeSolve = solveNanos;
		int[] trialParent = equalityParent.clone();
		for(EqualityGroup group : candidate.groups)
			for(int index = 1; index < group.representatives.size(); index++)
				union(trialParent, group.representatives.get(0), group.representatives.get(index));
		Set<ExactCategoricalSolver.Factor> contractedEqualities =
			Collections.newSetFromMap(new IdentityHashMap<>());
		contractedEqualities.addAll(restoredEqualityFactors);
		contractedEqualities.addAll(equalities);
		Component solved = solveContractedComponent(variables, factors, contractedEqualities,
			trialParent, previousAffected, touched, cancelled);
		long measured = saturatedAdd(preparationNanos - beforePreparation, solveNanos - beforeSolve);
		double proposed = proposedLower(touched, solved);
		return new Trial(candidate, equalities, solved, touched, affectedOriginalVariables(variables),
			proposed, nonNegativeDifference(proposed, lowerBound), Math.max(1L, measured),
			solved.result.statistics().eliminationAssignments());
	}

	private Component solveContractedComponent(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		Set<ExactCategoricalSolver.Factor> equalityFactors, int[] contractionParent,
		double previousLower, List<Integer> touched, BooleanSupplier cancelled) {
		checkCancelled(cancelled);
		long prepared = System.nanoTime();
		ContractedComponent contracted = contract(
			variables, factors, equalityFactors, contractionParent);
		ExactCategoricalSolver.CompiledProblem compiled;
		try {
			if(reusePreparation)
				compiled = componentPreparation.prepare(contracted.variables, contracted.factors,
					limits, maximumWork, projectedOrder(contracted.variables, touched, contractionParent))
					.compiled();
			else
				compiled = ExactCategoricalSolver.compile(contracted.variables, contracted.factors, limits,
					"incremental-replica-contracted");
		}
		catch(MiniBucketLowerBound.ResourceLimitException failure) {
			preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - prepared);
			throw failure;
		}
		catch(IllegalArgumentException failure) {
			preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - prepared);
			if(RegionalSearchProblem.isResourceLimit(failure))
				throw new MiniBucketLowerBound.ResourceLimitException(
					"INCREMENTAL_REPLICA_COMPONENT_RESOURCE_LIMIT|" + failure.getMessage(), failure);
			throw failure;
		}
		preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - prepared);
		ExactCategoricalSolver.Statistics planned = ExactCategoricalSolver.statistics(compiled);
		if(planned.eliminationAssignments() > maximumWork)
			throw new MiniBucketLowerBound.ResourceLimitException(
				"INCREMENTAL_REPLICA_COMPONENT_WORK_LIMIT|work="
					+ planned.eliminationAssignments() + "|limit=" + maximumWork);
		checkCancelled(cancelled);
		long solving = System.nanoTime();
		exactCalls = saturatedAdd(exactCalls, 1L);
		ExactCategoricalSolver.Result contractedResult = ExactCategoricalSolver.solve(compiled);
		solveNanos = saturatedAdd(solveNanos, System.nanoTime() - solving);
		assignments = saturatedAdd(assignments, planned.eliminationAssignments());
		materializedCells = saturatedAdd(materializedCells, planned.materializedFactorCells());
		maximumFactorCells = Math.max(maximumFactorCells, planned.maximumFactorCells());
		checkCancelled(cancelled);
		List<Integer> expandedAssignment = new ArrayList<>(variables.size());
		for(int contractedVariable : contracted.contractedVariableByOriginal)
			expandedAssignment.add(
				contractedResult.assignmentInVariableOrder().get(contractedVariable));
		ExactCategoricalSolver.Result expandedResult = new ExactCategoricalSolver.Result(
			contractedResult.objective(), expandedAssignment, contractedResult.statistics());
		double conservative = conservative(contractedResult.objective());
		return new Component(variables, factors, expandedResult,
			Math.max(previousLower, conservative));
	}

	/** Only the symbolic elimination order is retained; current factor scopes are rebuilt. */
	private List<String> projectedOrder(List<ExactCategoricalSolver.Variable> variables,
		List<Integer> touched, int[] contractionParent) {
		Set<String> remaining = new LinkedHashSet<>();
		for(ExactCategoricalSolver.Variable variable : variables)
			remaining.add(variable.key());
		List<String> result = new ArrayList<>(variables.size());
		for(int index : touched)
			for(String key : components.get(index).result.statistics().eliminationOrder()) {
				Integer replica = replicaByKey.get(key);
				if(replica == null)
					throw new IllegalStateException("INCREMENTAL_REPLICA_PREPARED_VARIABLE_FOREIGN");
				String projected = model.variables().get(find(contractionParent, replica)).key();
				if(remaining.remove(projected))
					result.add(projected);
			}
		result.addAll(remaining);
		return List.copyOf(result);
	}

	private ContractedComponent contract(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors,
		Set<ExactCategoricalSolver.Factor> equalityFactors, int[] contractionParent) {
		Map<Integer, ExactCategoricalSolver.Variable> variableByRoot = new LinkedHashMap<>();
		for(ExactCategoricalSolver.Variable variable : variables) {
			int root = find(contractionParent, position(variable));
			variableByRoot.putIfAbsent(root, model.variables().get(root));
		}
		List<ExactCategoricalSolver.Variable> contractedVariables =
			variableByRoot.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(Map.Entry::getValue).toList();
		IdentityHashMap<ExactCategoricalSolver.Variable, Integer> contractedPosition =
			new IdentityHashMap<>();
		for(int index = 0; index < contractedVariables.size(); index++)
			contractedPosition.put(contractedVariables.get(index), index);
		int[] contractedVariableByOriginal = new int[variables.size()];
		for(int index = 0; index < variables.size(); index++) {
			int root = find(contractionParent, position(variables.get(index)));
			contractedVariableByOriginal[index] = contractedPosition.get(variableByRoot.get(root));
		}

		List<ExactCategoricalSolver.Factor> contractedFactors = new ArrayList<>();
		for(ExactCategoricalSolver.Factor factor : factors) {
			if(equalityFactors.contains(factor))
				continue;
			List<ExactCategoricalSolver.Variable> scope = new ArrayList<>();
			IdentityHashMap<ExactCategoricalSolver.Variable, Integer> scopePosition =
				new IdentityHashMap<>();
			int[] originalValueFromContracted = new int[factor.scope().size()];
			for(int index = 0; index < factor.scope().size(); index++) {
				int root = find(contractionParent, position(factor.scope().get(index)));
				ExactCategoricalSolver.Variable contractedVariable = variableByRoot.get(root);
				Integer local = scopePosition.get(contractedVariable);
				if(local == null) {
					local = scope.size();
					scopePosition.put(contractedVariable, local);
					scope.add(contractedVariable);
				}
				originalValueFromContracted[index] = local;
			}
			int[] originalValues = new int[originalValueFromContracted.length];
			contractedFactors.add(ExactCategoricalSolver.Factor.lazy(scope, values -> {
				for(int index = 0; index < originalValues.length; index++)
					originalValues[index] = values[originalValueFromContracted[index]];
				return factor.cost(originalValues);
			}));
		}
		return new ContractedComponent(
			contractedVariables, contractedFactors, contractedVariableByOriginal);
	}

	private Component solveComponent(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, double previousLower,
		BooleanSupplier cancelled) {
		checkCancelled(cancelled);
		ExactCategoricalSolver.CompiledProblem compiled;
		long prepared = System.nanoTime();
		try {
			compiled = ExactCategoricalSolver.compile(variables, factors, limits,
				"incremental-replica-component");
		}
		catch(IllegalArgumentException failure) {
			preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - prepared);
			if(RegionalSearchProblem.isResourceLimit(failure))
				throw new MiniBucketLowerBound.ResourceLimitException(
					"INCREMENTAL_REPLICA_COMPONENT_RESOURCE_LIMIT|" + failure.getMessage(), failure);
			throw failure;
		}
		preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - prepared);
		ExactCategoricalSolver.Statistics planned = ExactCategoricalSolver.statistics(compiled);
		if(planned.eliminationAssignments() > maximumWork)
			throw new MiniBucketLowerBound.ResourceLimitException(
				"INCREMENTAL_REPLICA_COMPONENT_WORK_LIMIT|work="
					+ planned.eliminationAssignments() + "|limit=" + maximumWork);
		checkCancelled(cancelled);
		long solving = System.nanoTime();
		exactCalls = saturatedAdd(exactCalls, 1L);
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(compiled);
		solveNanos = saturatedAdd(solveNanos, System.nanoTime() - solving);
		assignments = saturatedAdd(assignments, planned.eliminationAssignments());
		materializedCells = saturatedAdd(materializedCells, planned.materializedFactorCells());
		maximumFactorCells = Math.max(maximumFactorCells, planned.maximumFactorCells());
		checkCancelled(cancelled);
		double conservative = conservative(result.objective());
		return new Component(variables, factors, result,
			Math.max(previousLower, conservative));
	}

	private void commit(Trial selected) {
		List<Component> updated = new ArrayList<>(components.size() - selected.touched.size() + 1);
		int first = selected.touched.get(0);
		Set<Integer> touched = Set.copyOf(selected.touched);
		for(int index = 0; index < components.size(); index++) {
			if(index == first)
				updated.add(selected.component);
			else if(!touched.contains(index))
				updated.add(components.get(index));
		}
		List<Component> committed = List.copyOf(updated);
		int[] committedComponentByReplica = buildComponentIndex(committed);
		components = committed;
		componentByReplica = committedComponentByReplica;
		for(ExactCategoricalSolver.Factor equality : selected.equalities) {
			factorOrder.put(equality, nextFactorOrder++);
			restoredEqualityFactors.add(equality);
		}
		for(EqualityGroup group : selected.candidate.groups) {
			int anchor = group.representatives.get(0);
			for(int index = 1; index < group.representatives.size(); index++)
				union(equalityParent, anchor, group.representatives.get(index));
		}
		restoredEqualities += selected.equalities.size();
		reusedComponents = saturatedAdd(reusedComponents,
			Math.max(0, components.size() - 1));
		lowerBound = Math.max(lowerBound, sumComponents(components));
	}

	/**
	 * Builds a bounded structural shortlist. Modal minority groups estimate
	 * possible tightening independent of representative order, while cached
	 * component assignments estimate local cost. Their ratio is a heuristic, not
	 * an optimality claim. The final choice among probed candidates still uses
	 * measured global bound gain per preparation-plus-solve time.
	 */
	private List<VariableCandidate> candidates() {
		int[] assignment = replicaAssignment();
		List<VariableCandidate> result = new ArrayList<>();
		for(int original = 0; original < replicasByOriginal.size(); original++) {
			int[] group = replicasByOriginal.get(original);
			List<Integer> representatives = new ArrayList<>();
			Set<Integer> seenRoots = new HashSet<>();
			for(int replicaPosition : group) {
				int root = find(replicaPosition);
				if(seenRoots.add(root))
					representatives.add(replicaPosition);
			}
			if(representatives.size() < 2)
				continue;
			int[] frequencies = new int[model.variables().get(representatives.get(0)).domainSize()];
			for(int representative : representatives)
				frequencies[assignment[representative]]++;
			int modalFrequency = 0;
			for(int frequency : frequencies)
				modalFrequency = Math.max(modalFrequency, frequency);
			int modalMinority = representatives.size() - modalFrequency;
			Set<Integer> componentIndexes = new HashSet<>();
			for(int representative : representatives)
				componentIndexes.add(componentIndex(model.variables().get(representative)));
			List<Integer> touched = componentIndexes.stream().sorted().toList();
			long workProxy = 0L;
			for(int component : touched)
				workProxy = saturatedAdd(workProxy,
					components.get(component).result.statistics().eliminationAssignments());
			VariableCandidate candidate = new VariableCandidate(
				original, representatives, modalMinority, touched, workProxy);
			if(!resourceBlocked.contains(key(candidate)))
				result.add(candidate);
		}
		result.sort((left, right) -> {
			int compared = Double.compare(priority(right), priority(left));
			if(compared != 0)
				return compared;
			compared = Long.compare(denominator(left), denominator(right));
			if(compared != 0)
				return compared;
			return Integer.compare(left.originalVariable, right.originalVariable);
		});
		return List.copyOf(result);
	}

	private static double priority(VariableCandidate candidate) {
		return (double) candidate.modalMinorityGroups / denominator(candidate);
	}

	private static long denominator(VariableCandidate candidate) {
		return Math.max(1L, candidate.scopedWorkProxy);
	}

	private int[] replicaAssignment() {
		int[] result = new int[model.variables().size()];
		for(Component component : components)
			for(int local = 0; local < component.variables.size(); local++)
				result[position(component.variables.get(local))] =
					component.result.assignmentInVariableOrder().get(local);
		return result;
	}

	private List<Integer> affectedOriginalVariables(List<ExactCategoricalSolver.Variable> variables) {
		Set<Integer> originals = new HashSet<>();
		for(ExactCategoricalSolver.Variable variable : variables)
			originals.add(originalByReplica[position(variable)]);
		return originals.stream().sorted().toList();
	}

	private int componentIndex(ExactCategoricalSolver.Variable variable) {
		int index = componentByReplica[position(variable)];
		if(index < 0 || index >= components.size())
			throw new IllegalStateException("INCREMENTAL_REPLICA_COMPONENT_MISSING");
		return index;
	}

	private int[] buildComponentIndex(List<Component> indexedComponents) {
		int[] result = new int[model.variables().size()];
		java.util.Arrays.fill(result, -1);
		for(int component = 0; component < indexedComponents.size(); component++)
			for(ExactCategoricalSolver.Variable variable : indexedComponents.get(component).variables) {
				int replica = position(variable);
				if(result[replica] >= 0)
					throw new IllegalStateException("INCREMENTAL_REPLICA_COMPONENT_DUPLICATE");
				result[replica] = component;
			}
		for(int component : result)
			if(component < 0)
				throw new IllegalStateException("INCREMENTAL_REPLICA_COMPONENT_MISSING");
		return result;
	}

	private double proposedLower(List<Integer> touched, Component replacement) {
		double result = 0d;
		Set<Integer> skipped = Set.copyOf(touched);
		int first = touched.get(0);
		for(int index = 0; index < components.size(); index++) {
			if(index == first)
				result = addDown(result, replacement.lowerBound);
			else if(!skipped.contains(index))
				result = addDown(result, components.get(index).lowerBound);
		}
		return Math.max(lowerBound, result);
	}

	private static double sumComponents(List<Component> components) {
		double result = 0d;
		for(Component component : components)
			result = addDown(result, component.lowerBound);
		return result;
	}

	private int position(ExactCategoricalSolver.Variable variable) {
		Integer position = replicaPosition.get(variable);
		if(position == null)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_VARIABLE_FOREIGN");
		return position;
	}

	private int find(int position) { return find(equalityParent, position); }

	private VariableKey key(VariableCandidate candidate) {
		List<EqualityGroup> groups = candidate.groups.stream()
			.sorted(Comparator.comparingInt(EqualityGroup::originalVariable)).toList();
		return new VariableKey(groups.stream().map(EqualityGroup::originalVariable).toList(),
			groups.stream().map(group -> group.representatives.stream().map(this::find).sorted().toList())
				.toList());
	}

	private static boolean betterRate(Trial left, Trial right) {
		double leftRate = left.gain / left.measuredNanos;
		double rightRate = right.gain / right.measuredNanos;
		int compared = Double.compare(leftRate, rightRate);
		if(compared != 0)
			return compared > 0;
		compared = Double.compare(left.gain, right.gain);
		return compared != 0 ? compared > 0 : compare(left.candidate, right.candidate) < 0;
	}

	private static int compare(VariableCandidate left, VariableCandidate right) {
		int compared = Integer.compare(left.originalVariable, right.originalVariable);
		if(compared != 0)
			return compared;
		for(int index = 0; index < Math.min(left.representatives.size(), right.representatives.size()); index++) {
			compared = Integer.compare(left.representatives.get(index), right.representatives.get(index));
			if(compared != 0)
				return compared;
		}
		return Integer.compare(left.representatives.size(), right.representatives.size());
	}

	private static int find(int[] parent, int index) {
		while(parent[index] != index)
			index = parent[index];
		return index;
	}

	private static void union(int[] parent, int left, int right) {
		left = find(parent, left);
		right = find(parent, right);
		if(left != right) {
			if(left < right)
				parent[right] = left;
			else
				parent[left] = right;
		}
	}

	private static double conservative(double objective) {
		return objective == 0d || objective == Double.POSITIVE_INFINITY
			? objective : Math.max(0d, Math.nextDown(objective));
	}

	private static double addDown(double left, double right) {
		if(left == Double.POSITIVE_INFINITY || right == Double.POSITIVE_INFINITY)
			return Double.POSITIVE_INFINITY;
		if(left == 0d)
			return right;
		if(right == 0d)
			return left;
		double sum = left + right;
		return sum == Double.POSITIVE_INFINITY ? Double.MAX_VALUE
			: Math.max(0d, Math.nextDown(sum));
	}

	private static double nonNegativeDifference(double upper, double lower) {
		if(upper == lower)
			return 0d;
		if(upper == Double.POSITIVE_INFINITY)
			return Double.POSITIVE_INFINITY;
		return Math.max(0d, upper - lower);
	}

	private double evaluateReplicaFactors(List<Integer> assignment) {
		if(assignment.size() != model.variables().size())
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_EXACT_ASSIGNMENT_SIZE_INVALID");
		ExactCompensatedCostSum total = new ExactCompensatedCostSum();
		for(ExactCategoricalSolver.Factor factor : model.factors()) {
			int[] local = new int[factor.scope().size()];
			for(int index = 0; index < local.length; index++)
				local[index] = assignment.get(position(factor.scope().get(index)));
			double value = factor.cost(local);
			if(value == Double.POSITIVE_INFINITY)
				throw new IllegalStateException("INCREMENTAL_REPLICA_EXACT_ASSIGNMENT_INFEASIBLE");
			total.addBits(Double.doubleToRawLongBits(value),
				"INCREMENTAL_REPLICA_EXACT_FACTOR_COST_INVALID",
				"INCREMENTAL_REPLICA_EXACT_OBJECTIVE_INVALID");
		}
		return Double.longBitsToDouble(
			total.totalBits("INCREMENTAL_REPLICA_EXACT_OBJECTIVE_INVALID"));
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if(cancelled.getAsBoolean())
			throw new CancellationException("INCREMENTAL_REPLICA_CANCELLED");
	}
}
