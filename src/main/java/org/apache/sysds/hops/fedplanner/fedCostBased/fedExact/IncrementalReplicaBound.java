/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
 * replica groups of one encoded variable and re-solves only the components
 * touched by that batch; all other exact results are retained. Published
 * component values and their sum are rounded downward, so every completed
 * state remains a lower bound on the original model.</p>
 */
final class IncrementalReplicaBound {
	private static final int MAXIMUM_PROBES = 2;

	private final MiniBucketLowerBound.ReplicaModel model;
	private final ExactCategoricalSolver.Limits limits;
	private final long maximumWork;
	private final IdentityHashMap<ExactCategoricalSolver.Variable, Integer> replicaPosition =
		new IdentityHashMap<>();
	private final IdentityHashMap<ExactCategoricalSolver.Factor, Integer> factorOrder =
		new IdentityHashMap<>();
	private final int[] originalByReplica;
	private final List<int[]> replicasByOriginal;
	private final int[] equalityParent;
	private final Set<VariableKey> resourceBlocked = new HashSet<>();
	private List<Component> components;
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

	record Work(long calls, long assignments, long materializedCells,
		long maximumFactorCells, long preparationNanos, long solveNanos,
		long probes, long reusedComponents, long resourceSkips) { }

	record Refinement(boolean changed, int originalVariable,
		List<Integer> affectedOriginalVariables, double lowerBound, double gain,
		long elapsedNanos) {
		Refinement { affectedOriginalVariables = List.copyOf(affectedOriginalVariables); }
	}

	private record VariableCandidate(int originalVariable, List<Integer> representatives,
		int disagreeingGroups, List<Integer> touchedComponents, long scopedWorkProxy) {
		VariableCandidate {
			representatives = List.copyOf(representatives);
			touchedComponents = List.copyOf(touchedComponents);
		}
	}
	private record VariableKey(int originalVariable, List<Integer> roots) {
		VariableKey { roots = List.copyOf(roots); }
	}
	private record Trial(VariableCandidate candidate, List<ExactCategoricalSolver.Factor> equalities,
		Component component,
		List<Integer> touched, List<Integer> affectedOriginalVariables,
		double proposedLowerBound, double gain, long measuredNanos) {
		Trial { equalities = List.copyOf(equalities); }
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
		Objects.requireNonNull(cancelled, "cancelled");
		if(maximumWork <= 0)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_WORK_LIMIT_INVALID");
		checkCancelled(cancelled);
		MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
			variables, factors, width, limits, cancelled);
		return new IncrementalReplicaBound(replica, limits, maximumWork, cancelled);
	}

	private IncrementalReplicaBound(MiniBucketLowerBound.ReplicaModel model,
		ExactCategoricalSolver.Limits limits, long maximumWork,
		BooleanSupplier cancelled) {
		this.model = model;
		this.limits = Objects.requireNonNull(limits, "limits");
		this.maximumWork = maximumWork;
		for(int index = 0; index < model.variables().size(); index++)
			replicaPosition.put(model.variables().get(index), index);
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
		lowerBound = sumComponents(components);
	}

	double lowerBound() { return lowerBound; }
	String partitionIdentity() { return model.partitionIdentity(); }
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
	Work workStats() {
		return new Work(exactCalls, assignments, materializedCells, maximumFactorCells,
			preparationNanos, solveNanos, probes, reusedComponents, resourceSkips);
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

	Refinement refine(int maximumCandidateProbes, BooleanSupplier cancelled) {
		if(maximumCandidateProbes < 1 || maximumCandidateProbes > MAXIMUM_PROBES)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_PROBE_LIMIT_INVALID");
		Objects.requireNonNull(cancelled, "cancelled");
		checkCancelled(cancelled);
		long started = System.nanoTime();
		List<VariableCandidate> candidates = candidates();
		Trial bestPositive = null;
		Trial cheapestZero = null;
		List<VariableKey> newlyBlocked = new ArrayList<>();
		for(int index = 0; index < Math.min(maximumCandidateProbes, candidates.size()); index++) {
			checkCancelled(cancelled);
			VariableCandidate candidate = candidates.get(index);
			probes = saturatedAdd(probes, 1L);
			Trial trial;
			try {
				trial = trial(candidate, cancelled);
			}
			catch(MiniBucketLowerBound.ResourceLimitException limited) {
				newlyBlocked.add(key(candidate));
				resourceSkips = saturatedAdd(resourceSkips, 1L);
				continue;
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
				System.nanoTime() - started);

		double previous = lowerBound;
		commit(selected);
		return new Refinement(true, selected.candidate.originalVariable,
			selected.affectedOriginalVariables, lowerBound, nonNegativeDifference(lowerBound, previous),
			System.nanoTime() - started);
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
		ExactCategoricalSolver.Variable anchor = model.variables().get(candidate.representatives.get(0));
		for(int index = 1; index < candidate.representatives.size(); index++) {
			ExactCategoricalSolver.Variable replica =
				model.variables().get(candidate.representatives.get(index));
			ExactCategoricalSolver.Factor equality = ExactCategoricalSolver.Factor.lazy(
				List.of(anchor, replica),
				values -> values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);
			equalities.add(equality);
			factors.add(equality);
		}
		double previousAffected = 0d;
		for(int component : touched)
			previousAffected = addDown(previousAffected, components.get(component).lowerBound);
		long beforePreparation = preparationNanos;
		long beforeSolve = solveNanos;
		Component solved = solveComponent(variables, factors, previousAffected, cancelled);
		long measured = saturatedAdd(preparationNanos - beforePreparation, solveNanos - beforeSolve);
		double proposed = proposedLower(touched, solved);
		return new Trial(candidate, equalities, solved, touched, affectedOriginalVariables(variables),
			proposed, nonNegativeDifference(proposed, lowerBound), Math.max(1L, measured));
	}

	private Component solveComponent(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, double previousLower,
		BooleanSupplier cancelled) {
		checkCancelled(cancelled);
		ExactCategoricalSolver.CompiledProblem compiled;
		long prepared = System.nanoTime();
		try {
			compiled = ExactCategoricalSolver.compile(variables, factors, limits);
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
		components = List.copyOf(updated);
		for(ExactCategoricalSolver.Factor equality : selected.equalities)
			factorOrder.put(equality, nextFactorOrder++);
		int anchor = selected.candidate.representatives.get(0);
		for(int index = 1; index < selected.candidate.representatives.size(); index++)
			union(equalityParent, anchor, selected.candidate.representatives.get(index));
		restoredEqualities += selected.equalities.size();
		reusedComponents = saturatedAdd(reusedComponents,
			Math.max(0, components.size() - 1));
		lowerBound = Math.max(lowerBound, sumComponents(components));
	}

	/**
	 * Builds a bounded structural shortlist. Disagreement estimates possible
	 * tightening and cached component work estimates its local cost; neither is
	 * an optimality claim. The final choice still uses measured global bound gain
	 * per preparation-plus-solve time.
	 */
	private List<VariableCandidate> candidates() {
		int[] assignment = replicaAssignment();
		List<VariableCandidate> result = new ArrayList<>();
		for(int original = 0; original < replicasByOriginal.size(); original++) {
			int[] group = replicasByOriginal.get(original);
			List<Integer> representatives = new ArrayList<>();
			for(int replicaPosition : group) {
				int root = find(replicaPosition);
				boolean known = false;
				for(int representative : representatives)
					if(find(representative) == root) {
						known = true;
						break;
					}
				if(!known)
					representatives.add(replicaPosition);
			}
			if(representatives.size() < 2)
				continue;
			int disagreeing = 0;
			for(int index = 1; index < representatives.size(); index++)
				if(assignment[representatives.get(0)] != assignment[representatives.get(index)])
					disagreeing++;
			Set<Integer> componentIndexes = new HashSet<>();
			for(int representative : representatives)
				componentIndexes.add(componentIndex(model.variables().get(representative)));
			List<Integer> touched = componentIndexes.stream().sorted().toList();
			long workProxy = 0L;
			for(int component : touched)
				workProxy = saturatedAdd(workProxy,
					components.get(component).result.statistics().eliminationAssignments());
			VariableCandidate candidate = new VariableCandidate(
				original, representatives, disagreeing, touched, workProxy);
			if(!resourceBlocked.contains(key(candidate)))
				result.add(candidate);
		}
		result.sort(Comparator.comparingInt(VariableCandidate::disagreeingGroups).reversed()
			.thenComparing(Comparator.comparingInt(
				(VariableCandidate candidate) -> candidate.representatives.size()).reversed())
			.thenComparingLong(VariableCandidate::scopedWorkProxy)
			.thenComparingInt(VariableCandidate::originalVariable));
		return List.copyOf(result);
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
		for(int index = 0; index < components.size(); index++)
			if(components.get(index).variables.contains(variable))
				return index;
		throw new IllegalStateException("INCREMENTAL_REPLICA_COMPONENT_MISSING");
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
		return new VariableKey(candidate.originalVariable,
			candidate.representatives.stream().map(this::find).sorted().toList());
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

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if(cancelled.getAsBoolean())
			throw new CancellationException("INCREMENTAL_REPLICA_CANCELLED");
	}
}
