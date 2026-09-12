/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Deterministic bounded-width min-sum mini-bucket elimination.
 *
 * <p>The i-bound includes the variable being eliminated. An input factor wider
 * than the requested bound is retained as an indivisible mini-bucket and its
 * descendants remain indivisible until their scope falls within the bound. This
 * native-width exception avoids dropping or altering caller factors; it does not
 * raise the bound used to combine unrelated factors.</p>
 *
 * <p>Conflict diagnostics compare one deterministic selected argmin per
 * mini-bucket in a reconstructed relaxed context. Equal-cost ties are resolved by
 * the lowest domain value, so a reported disagreement can reflect tie ambiguity;
 * conflicts guide region selection and are not a decomposition of the bound gap.</p>
 */
final class MiniBucketLowerBound {
	private MiniBucketLowerBound() { }

	record Result(double lowerBound, List<Conflict> conflicts, Statistics statistics) {
		Result { conflicts = List.copyOf(conflicts); }
	}

	record Conflict(ExactCategoricalSolver.Variable variable,
		List<ExactCategoricalSolver.Variable> scope, double disagreement) {
		Conflict { scope = List.copyOf(scope); }
	}

	record Statistics(int splitBuckets, long maximumFactorCells,
		long materializedCells, long evaluatedAssignments) { }

	enum EliminationOrder { INPUT, WEIGHTED_MIN_FILL }
	enum PartitionStrategy { FIRST_FIT, BEST_FIT }
	record PlanningPolicy(EliminationOrder eliminationOrder,
		PartitionStrategy partitionStrategy) {
		static final PlanningPolicy INPUT_FIRST_FIT =
			new PlanningPolicy(EliminationOrder.INPUT, PartitionStrategy.FIRST_FIT);

		PlanningPolicy {
			Objects.requireNonNull(eliminationOrder, "eliminationOrder");
			Objects.requireNonNull(partitionStrategy, "partitionStrategy");
		}
	}

	/**
	 * Explicit variable-replica encoding of the relaxation induced by one
	 * deterministic mini-bucket partition. Original factors retain their order and
	 * evaluator; only occurrences of a split variable are redirected to distinct
	 * replicas. Consequently, assigning every replica the corresponding original
	 * value preserves the original objective exactly.
	 */
	static final class ReplicaModel {
		private final List<ExactCategoricalSolver.Variable> originalVariables;
		private final List<ExactCategoricalSolver.Variable> variables;
		private final List<ExactCategoricalSolver.Factor> factors;
		private final List<List<ExactCategoricalSolver.Variable>> replicas;
		private final Statistics statistics;
		private final String partitionIdentity;
		private final List<Integer> eliminationOrder;

		private ReplicaModel(List<ExactCategoricalSolver.Variable> originalVariables,
			List<ExactCategoricalSolver.Variable> variables,
			List<ExactCategoricalSolver.Factor> factors,
			List<List<ExactCategoricalSolver.Variable>> replicas, Statistics statistics,
			String partitionIdentity, int[] eliminationOrder) {
			this.originalVariables = List.copyOf(originalVariables);
			this.variables = List.copyOf(variables);
			this.factors = List.copyOf(factors);
			this.replicas = replicas.stream().map(List::copyOf).toList();
			this.statistics = statistics;
			this.partitionIdentity = partitionIdentity;
			this.eliminationOrder = Arrays.stream(eliminationOrder).boxed().toList();
		}

		List<ExactCategoricalSolver.Variable> variables() { return variables; }
		List<ExactCategoricalSolver.Factor> factors() { return factors; }
		List<List<ExactCategoricalSolver.Variable>> replicas() { return replicas; }
		Statistics statistics() { return statistics; }
		String partitionIdentity() { return partitionIdentity; }
		List<Integer> eliminationOrder() { return eliminationOrder; }

		List<Integer> diagonalAssignment(List<Integer> originalAssignment) {
			Objects.requireNonNull(originalAssignment, "originalAssignment");
			if(originalAssignment.size() != originalVariables.size())
				throw new IllegalArgumentException("MINI_BUCKET_REPLICA_ASSIGNMENT_SIZE_MISMATCH");
			List<Integer> lifted = new ArrayList<>(variables.size());
			for(int original = 0; original < originalVariables.size(); original++) {
				int value = Objects.requireNonNull(originalAssignment.get(original), "assignment value");
				if(value < 0 || value >= originalVariables.get(original).domainSize())
					throw new IllegalArgumentException("MINI_BUCKET_REPLICA_ASSIGNMENT_VALUE_INVALID");
				for(int replica = 0; replica < replicas.get(original).size(); replica++)
					lifted.add(value);
			}
			return List.copyOf(lifted);
		}
	}

	static final class ResourceLimitException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		ResourceLimitException(String message) { super(message); }
		ResourceLimitException(String message, Throwable cause) { super(message, cause); }
	}

	static Result compute(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int iBound,
		ExactCategoricalSolver.Limits limits, BooleanSupplier cancelled) {
		return compute(variables, factors, iBound, limits,
			PlanningPolicy.INPUT_FIRST_FIT, cancelled);
	}

	static Result compute(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int iBound,
		ExactCategoricalSolver.Limits limits, PlanningPolicy policy,
		BooleanSupplier cancelled) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(cancelled, "cancelled");
		if(iBound < 1)
			throw new IllegalArgumentException("MINI_BUCKET_I_BOUND_INVALID|iBound=" + iBound);
		checkCancelled(cancelled);

		Definition definition = validateStructure(variables, factors);
		validateExactFactorRepresentations(variables, factors, limits);
		Plan plan = plan(definition, iBound, limits, policy, cancelled);
		List<Table> tables = materializeInputs(definition, factors, plan, cancelled);
		List<StepResult> results = eliminate(definition, plan, tables, cancelled);

		double lowerBound = 0d;
		for(Table table : tables) {
			checkCancelled(cancelled);
			if(table.active)
				lowerBound = addDown(lowerBound, table.values[0]);
		}
		List<Conflict> conflicts = reconstructConflicts(definition, results, cancelled);
		Statistics statistics = new Statistics(plan.splitBuckets, plan.maximumFactorCells,
			plan.materializedCells, plan.evaluatedAssignments);
		return new Result(lowerBound, conflicts, statistics);
	}

	static ReplicaModel replicaModel(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int iBound,
		ExactCategoricalSolver.Limits limits, BooleanSupplier cancelled) {
		return replicaModel(variables, factors, iBound, limits,
			PlanningPolicy.INPUT_FIRST_FIT, cancelled);
	}

	static ReplicaModel replicaModel(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int iBound,
		ExactCategoricalSolver.Limits limits, PlanningPolicy policy,
		BooleanSupplier cancelled) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(cancelled, "cancelled");
		if(iBound < 1)
			throw new IllegalArgumentException("MINI_BUCKET_I_BOUND_INVALID|iBound=" + iBound);
		checkCancelled(cancelled);

		Definition definition = validateStructure(variables, factors);
		validateExactFactorRepresentations(variables, factors, limits);
		Plan plan = plan(definition, iBound, limits, policy, cancelled);
		return buildReplicaModel(definition, factors, plan, iBound, policy, cancelled);
	}

	/**
	 * Structural join graph induced by the deterministic mini-bucket plan. Each
	 * original factor is owned by exactly one cluster. Generated elimination
	 * messages contribute edges and separators only, so consumers cannot count a
	 * source factor for a second time.
	 */
	static JoinGraphPlan joinGraphPlan(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int iBound,
		ExactCategoricalSolver.Limits limits, BooleanSupplier cancelled) {
		return joinGraphPlan(variables, factors, iBound, limits,
			PlanningPolicy.INPUT_FIRST_FIT, cancelled);
	}

	static JoinGraphPlan joinGraphPlan(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int iBound,
		ExactCategoricalSolver.Limits limits, PlanningPolicy policy,
		BooleanSupplier cancelled) {
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(cancelled, "cancelled");
		if(iBound < 1)
			throw new IllegalArgumentException("MINI_BUCKET_I_BOUND_INVALID|iBound=" + iBound);
		checkCancelled(cancelled);

		Definition definition = validateStructure(variables, factors);
		validateExactFactorRepresentations(variables, factors, limits);
		Plan plan = plan(definition, iBound, limits, policy, cancelled);
		List<JoinCluster> clusters = new ArrayList<>();
		List<JoinEdge> edges = new ArrayList<>();
		Map<Integer, Integer> producerByOutput = new HashMap<>();
		boolean[] assigned = new boolean[factors.size()];
		for(PlannedStep step : plan.steps) {
			int previousSplitCluster = -1;
			for(PlannedMiniBucket mini : step.miniBuckets) {
				checkCancelled(cancelled);
				int cluster = clusters.size();
				int[] originals = Arrays.stream(mini.inputs)
					.filter(input -> input < factors.size()).toArray();
				for(int original : originals) {
					if(assigned[original])
						throw new IllegalStateException("MINI_BUCKET_JOIN_FACTOR_DUPLICATE|factor=" + original);
					assigned[original] = true;
				}
				clusters.add(new JoinCluster(mini.unionScope, originals));
				for(int input : mini.inputs) {
					if(input < factors.size())
						continue;
					Integer producer = producerByOutput.get(input);
					if(producer == null)
						throw new IllegalStateException("MINI_BUCKET_JOIN_PRODUCER_MISSING|factor=" + input);
					int[] separator = intersection(clusters.get(producer).scope, mini.unionScope);
					edges.add(new JoinEdge(producer, cluster, separator, true));
				}
				if(previousSplitCluster >= 0) {
					int[] separator = intersection(clusters.get(previousSplitCluster).scope,
						mini.unionScope);
					if(separator.length > 0)
						edges.add(new JoinEdge(previousSplitCluster, cluster, separator, false));
				}
				previousSplitCluster = cluster;
				producerByOutput.put(mini.outputId, cluster);
			}
		}
		for(int factor = 0; factor < assigned.length; factor++) {
			if(!assigned[factor]) {
				clusters.add(new JoinCluster(definition.scopes.get(factor), new int[] {factor}));
				assigned[factor] = true;
			}
		}
		Statistics statistics = new Statistics(plan.splitBuckets, plan.maximumFactorCells,
			plan.materializedCells, plan.evaluatedAssignments);
		return new JoinGraphPlan(definition.domains, definition.scopes, clusters, edges, statistics);
	}

	private static ReplicaModel buildReplicaModel(Definition definition,
		List<ExactCategoricalSolver.Factor> factors, Plan plan, int iBound,
		PlanningPolicy policy,
		BooleanSupplier cancelled) {
		int[][] occurrenceReplica = new int[factors.size()][];
		for(int factor = 0; factor < factors.size(); factor++) {
			occurrenceReplica[factor] = new int[factors.get(factor).scope().size()];
			Arrays.fill(occurrenceReplica[factor], -1);
		}
		int[] replicaCounts = new int[definition.variables.size()];
		for(PlannedStep step : plan.steps) {
			for(PlannedMiniBucket mini : step.miniBuckets) {
				checkCancelled(cancelled);
				int replica = replicaCounts[step.variable]++;
				for(int leaf : mini.leaves) {
					ExactCategoricalSolver.Factor source = factors.get(leaf);
					for(int position = 0; position < source.scope().size(); position++)
						if(identityIndex(definition.variables, source.scope().get(position)) == step.variable) {
							if(occurrenceReplica[leaf][position] >= 0)
								throw new IllegalStateException("MINI_BUCKET_REPLICA_LINEAGE_DUPLICATE"
									+ "|factor=" + leaf + "|position=" + position);
							occurrenceReplica[leaf][position] = replica;
						}
				}
			}
		}
		for(int variable = 0; variable < replicaCounts.length; variable++)
			if(replicaCounts[variable] == 0)
				replicaCounts[variable] = 1;

		List<ExactCategoricalSolver.Variable> liftedVariables = new ArrayList<>();
		List<List<ExactCategoricalSolver.Variable>> replicas = new ArrayList<>();
		for(int original = 0; original < definition.variables.size(); original++) {
			ExactCategoricalSolver.Variable source = definition.variables.get(original);
			List<ExactCategoricalSolver.Variable> group = new ArrayList<>();
			for(int replica = 0; replica < replicaCounts[original]; replica++) {
				ExactCategoricalSolver.Variable lifted = new ExactCategoricalSolver.Variable(
					"mb-replica-" + original + '-' + replica, source.domainSize());
				group.add(lifted);
				liftedVariables.add(lifted);
			}
			replicas.add(List.copyOf(group));
		}

		List<ExactCategoricalSolver.Factor> liftedFactors = new ArrayList<>(factors.size());
		for(int factor = 0; factor < factors.size(); factor++) {
			checkCancelled(cancelled);
			ExactCategoricalSolver.Factor source = factors.get(factor);
			List<ExactCategoricalSolver.Variable> scope = new ArrayList<>(source.scope().size());
			for(int position = 0; position < source.scope().size(); position++) {
				int original = identityIndex(definition.variables, source.scope().get(position));
				int replica = occurrenceReplica[factor][position];
				if(replica < 0)
					throw new IllegalStateException("MINI_BUCKET_REPLICA_OCCURRENCE_UNASSIGNED"
						+ "|factor=" + factor + "|position=" + position);
				scope.add(replicas.get(original).get(replica));
			}
			liftedFactors.add(ExactCategoricalSolver.Factor.lazy(scope, source::cost));
		}
		Statistics statistics = new Statistics(plan.splitBuckets, plan.maximumFactorCells,
			plan.materializedCells, plan.evaluatedAssignments);
		return new ReplicaModel(definition.variables, liftedVariables, liftedFactors,
			replicas, statistics,
			partitionIdentity(definition, occurrenceReplica, iBound, policy, plan.eliminationOrder),
			plan.eliminationOrder);
	}

	private static String partitionIdentity(Definition definition, int[][] occurrenceReplica,
		int iBound, PlanningPolicy policy, int[] eliminationOrder) {
		boolean legacy = policy.equals(PlanningPolicy.INPUT_FIRST_FIT);
		String version = legacy ? "mb-replica-v1" : "mb-replica-v2";
		StringBuilder serialized = new StringBuilder(version).append(";width=").append(iBound).append(';');
		if(!legacy)
			serialized.append("orderPolicy=").append(policy.eliminationOrder)
				.append(";partitionPolicy=").append(policy.partitionStrategy)
				.append(";realizedOrder=").append(Arrays.toString(eliminationOrder)).append(';');
		for(ExactCategoricalSolver.Variable variable : definition.variables)
			serialized.append(variable.key().length()).append(':').append(variable.key())
				.append(':').append(variable.domainSize()).append(';');
		for(int[] factor : occurrenceReplica)
			serialized.append(Arrays.toString(factor)).append(';');
		try {
			return version + '-' + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(serialized.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch(NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static Definition validateStructure(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		List<ExactCategoricalSolver.Variable> canonical = List.copyOf(variables);
		IdentityHashMap<ExactCategoricalSolver.Variable, Integer> identities = new IdentityHashMap<>();
		Map<String, ExactCategoricalSolver.Variable> keys = new HashMap<>();
		int[] domains = new int[canonical.size()];
		for(int index = 0; index < canonical.size(); index++) {
			ExactCategoricalSolver.Variable variable = Objects.requireNonNull(canonical.get(index), "variable");
			if(identities.put(variable, index) != null || keys.put(variable.key(), variable) != null)
				throw new IllegalArgumentException("MINI_BUCKET_VARIABLE_DUPLICATE|key=" + variable.key());
			domains[index] = variable.domainSize();
		}
		List<int[]> scopes = new ArrayList<>(factors.size());
		for(ExactCategoricalSolver.Factor factor : factors) {
			Objects.requireNonNull(factor, "factor");
			int[] scope = new int[factor.scope().size()];
			Set<Integer> unique = new HashSet<>();
			for(int position = 0; position < scope.length; position++) {
				Integer index = identities.get(factor.scope().get(position));
				if(index == null)
					throw new IllegalArgumentException("MINI_BUCKET_FACTOR_VARIABLE_FOREIGN");
				if(!unique.add(index))
					throw new IllegalArgumentException("MINI_BUCKET_FACTOR_VARIABLE_DUPLICATE");
				scope[position] = index;
			}
			Arrays.sort(scope);
			scopes.add(scope);
		}
		return new Definition(canonical, domains, List.copyOf(scopes));
	}

	private static void validateExactFactorRepresentations(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, ExactCategoricalSolver.Limits limits) {
		try {
			ExactCategoricalSolver.validateInputStructure(variables, factors, limits);
		}
		catch(IllegalArgumentException exception) {
			String message = exception.getMessage();
			if(message != null && (message.contains("LIMIT_EXCEEDED") || message.contains("CELL_OVERFLOW")))
				throw new ResourceLimitException("MINI_BUCKET_INPUT_RESOURCE_LIMIT|" + message, exception);
			throw exception;
		}
	}

	private static Plan plan(Definition definition, int iBound,
		ExactCategoricalSolver.Limits limits, PlanningPolicy policy,
		BooleanSupplier cancelled) {
		List<SymbolicFactor> active = new ArrayList<>();
		long materialized = 0L;
		long maximum = 0L;
		for(int factor = 0; factor < definition.scopes.size(); factor++) {
			checkCancelled(cancelled);
			int[] scope = definition.scopes.get(factor);
			long cells = cells(scope, definition.domains);
			checkFactorLimit(cells, limits);
			materialized = addCells(materialized, cells, limits);
			maximum = Math.max(maximum, cells);
			active.add(new SymbolicFactor(factor, scope, scope.length > iBound,
				new int[] {factor}));
		}

		List<PlannedStep> steps = new ArrayList<>();
		int nextFactor = definition.scopes.size();
		int splitBuckets = 0;
		long evaluated = materialized;
		int[] eliminationOrder = eliminationOrder(definition, policy.eliminationOrder, cancelled);
		for(int variable : eliminationOrder) {
			checkCancelled(cancelled);
			List<SymbolicFactor> bucket = new ArrayList<>();
			for(SymbolicFactor factor : active)
				if(contains(factor.scope, variable))
					bucket.add(factor);
			active.removeAll(bucket);
			if(bucket.isEmpty())
				continue;
			List<List<SymbolicFactor>> miniBuckets = partition(bucket, variable, iBound,
				definition.domains, limits.maximumFactorCells(), policy.partitionStrategy,
				cancelled);
			if(miniBuckets.size() > 1)
				splitBuckets++;
			List<PlannedMiniBucket> plannedMiniBuckets = new ArrayList<>();
			for(List<SymbolicFactor> miniBucket : miniBuckets) {
				int[] union = union(miniBucket);
				int[] outputScope = without(union, variable);
				long outputCells = cells(outputScope, definition.domains);
				checkFactorLimit(outputCells, limits);
				materialized = addCells(materialized, outputCells, limits);
				maximum = Math.max(maximum, outputCells);
				long assignments = multiply(outputCells, definition.domains[variable]);
				evaluated = addCount(evaluated, assignments,
					"MINI_BUCKET_EVALUATED_ASSIGNMENT_OVERFLOW");
				int[] inputs = miniBucket.stream().mapToInt(factor -> factor.id).toArray();
				boolean exempt = outputScope.length > iBound && miniBucket.size() == 1
					&& miniBucket.get(0).nativeWidthExempt;
				int[] leaves = miniBucket.stream().flatMapToInt(factor -> Arrays.stream(factor.leaves))
					.sorted().toArray();
				PlannedMiniBucket planned = new PlannedMiniBucket(inputs, union, outputScope,
					nextFactor, Math.toIntExact(outputCells), leaves);
				plannedMiniBuckets.add(planned);
				active.add(new SymbolicFactor(nextFactor++, outputScope, exempt, leaves));
			}
			steps.add(new PlannedStep(variable, List.copyOf(plannedMiniBuckets)));
		}
		return new Plan(List.copyOf(steps), nextFactor, splitBuckets, maximum, materialized,
			evaluated, eliminationOrder);
	}

	private static List<List<SymbolicFactor>> partition(List<SymbolicFactor> bucket,
		int eliminated, int iBound, int[] domains, long maximumFactorCells,
		PartitionStrategy strategy, BooleanSupplier cancelled) {
		List<List<SymbolicFactor>> result = new ArrayList<>();
		List<SymbolicFactor> ordered = strategy == PartitionStrategy.FIRST_FIT ? bucket
			: scopeAwareOrder(bucket, eliminated, domains, cancelled);
		for(SymbolicFactor factor : ordered) {
			checkCancelled(cancelled);
			boolean placed = false;
			if(!factor.nativeWidthExempt) {
				int destination = strategy == PartitionStrategy.FIRST_FIT
					? firstFit(result, factor, eliminated, iBound, domains, maximumFactorCells)
					: bestFit(result, factor, eliminated, iBound, domains,
						maximumFactorCells, cancelled);
				if(destination >= 0) {
					result.get(destination).add(factor);
					placed = true;
				}
			}
			if(!placed) {
				List<SymbolicFactor> miniBucket = new ArrayList<>();
				miniBucket.add(factor);
				result.add(miniBucket);
			}
		}
		return result;
	}

	private static int firstFit(List<List<SymbolicFactor>> miniBuckets,
		SymbolicFactor factor, int eliminated, int iBound, int[] domains,
		long maximumFactorCells) {
		for(int index = 0; index < miniBuckets.size(); index++) {
			List<SymbolicFactor> miniBucket = miniBuckets.get(index);
			if(!(miniBucket.size() == 1 && miniBucket.get(0).nativeWidthExempt)
				&& canMerge(miniBucket, factor, eliminated, iBound, domains, maximumFactorCells))
				return index;
		}
		return -1;
	}

	private static int bestFit(List<List<SymbolicFactor>> miniBuckets,
		SymbolicFactor factor, int eliminated, int iBound, int[] domains,
		long maximumFactorCells, BooleanSupplier cancelled) {
		int best = -1;
		int bestOverlap = -1;
		long bestExtraCells = Long.MAX_VALUE;
		long bestMergedCells = Long.MAX_VALUE;
		for(int index = 0; index < miniBuckets.size(); index++) {
			checkCancelled(cancelled);
			List<SymbolicFactor> miniBucket = miniBuckets.get(index);
			if(miniBucket.size() == 1 && miniBucket.get(0).nativeWidthExempt)
				continue;
			if(!canMerge(miniBucket, factor, eliminated, iBound, domains, maximumFactorCells))
				continue;
			int[] current = union(miniBucket);
			int[] merged = unionWith(current, factor.scope);
			int overlap = overlapWithout(current, factor.scope, eliminated);
			long currentCells = cells(without(current, eliminated), domains);
			long mergedCells = cells(without(merged, eliminated), domains);
			long extraCells = mergedCells - currentCells;
			if(overlap > bestOverlap || overlap == bestOverlap && extraCells < bestExtraCells
				|| overlap == bestOverlap && extraCells == bestExtraCells
					&& mergedCells < bestMergedCells) {
				best = index;
				bestOverlap = overlap;
				bestExtraCells = extraCells;
				bestMergedCells = mergedCells;
			}
		}
		return best;
	}

	private static List<SymbolicFactor> scopeAwareOrder(List<SymbolicFactor> bucket,
		int eliminated, int[] domains, BooleanSupplier cancelled) {
		List<SymbolicFactor> ordered = new ArrayList<>(bucket);
		ordered.sort((left, right) -> {
			checkCancelled(cancelled);
			int comparison = Boolean.compare(right.nativeWidthExempt, left.nativeWidthExempt);
			if(comparison != 0)
				return comparison;
			comparison = Integer.compare(right.scope.length, left.scope.length);
			if(comparison != 0)
				return comparison;
			comparison = Long.compare(cells(without(right.scope, eliminated), domains),
				cells(without(left.scope, eliminated), domains));
			if(comparison != 0)
				return comparison;
			comparison = compareScopes(left.scope, right.scope);
			return comparison != 0 ? comparison : Integer.compare(left.id, right.id);
		});
		return ordered;
	}

	private static int[] eliminationOrder(Definition definition, EliminationOrder policy,
		BooleanSupplier cancelled) {
		int variableCount = definition.variables.size();
		int[] order = new int[variableCount];
		if(policy == EliminationOrder.INPUT) {
			for(int variable = 0; variable < variableCount; variable++)
				order[variable] = variable;
			return order;
		}

		BitSet[] adjacent = new BitSet[variableCount];
		for(int variable = 0; variable < variableCount; variable++)
			adjacent[variable] = new BitSet(variableCount);
		for(int[] scope : definition.scopes) {
			checkCancelled(cancelled);
			for(int left = 0; left < scope.length; left++) {
				for(int right = left + 1; right < scope.length; right++) {
					adjacent[scope[left]].set(scope[right]);
					adjacent[scope[right]].set(scope[left]);
				}
			}
		}
		boolean[] eliminated = new boolean[variableCount];
		for(int position = 0; position < variableCount; position++) {
			int best = -1;
			long bestScore = Long.MAX_VALUE;
			for(int candidate = 0; candidate < variableCount; candidate++) {
				checkCancelled(cancelled);
				if(eliminated[candidate])
					continue;
				long score = weightedFillScore(candidate, adjacent, eliminated,
					definition.domains, bestScore, cancelled);
				if(best < 0 || score < bestScore) {
					best = candidate;
					bestScore = score;
					if(score == 0L)
						break;
				}
			}
			order[position] = best;
			BitSet neighbors = (BitSet)adjacent[best].clone();
			for(int eliminatedVariable = 0; eliminatedVariable < variableCount; eliminatedVariable++) {
				checkCancelled(cancelled);
				if(eliminated[eliminatedVariable])
					neighbors.clear(eliminatedVariable);
			}
			for(int left = neighbors.nextSetBit(0); left >= 0;
				left = neighbors.nextSetBit(left + 1)) {
				checkCancelled(cancelled);
				adjacent[left].or(neighbors);
				adjacent[left].clear(left);
				adjacent[left].clear(best);
			}
			adjacent[best].clear();
			eliminated[best] = true;
		}
		return order;
	}

	private static long weightedFillScore(int candidate, BitSet[] adjacent,
		boolean[] eliminated, int[] domains, long cutoff, BooleanSupplier cancelled) {
		long score = 0L;
		for(int left = adjacent[candidate].nextSetBit(0); left >= 0;
			left = adjacent[candidate].nextSetBit(left + 1)) {
			if(eliminated[left])
				continue;
			for(int right = adjacent[candidate].nextSetBit(left + 1); right >= 0;
				right = adjacent[candidate].nextSetBit(right + 1)) {
				checkCancelled(cancelled);
				if(eliminated[right] || adjacent[left].get(right))
					continue;
				long weight = (long)domains[left] * domains[right];
				score = score > Long.MAX_VALUE - weight ? Long.MAX_VALUE : score + weight;
				if(score >= cutoff)
					return score;
			}
		}
		return score;
	}

	private static boolean canMerge(List<SymbolicFactor> miniBucket,
		SymbolicFactor addition, int eliminated, int iBound, int[] domains,
		long maximumFactorCells) {
		Set<Integer> union = new HashSet<>();
		for(SymbolicFactor factor : miniBucket)
			for(int variable : factor.scope)
				union.add(variable);
		for(int variable : addition.scope)
			union.add(variable);
		if(union.size() > iBound)
			return false;
		long outputCells = 1L;
		long effectiveLimit = Math.min(maximumFactorCells, Integer.MAX_VALUE);
		for(int variable : union) {
			if(variable == eliminated)
				continue;
			if(outputCells > effectiveLimit / domains[variable])
				return false;
			outputCells *= domains[variable];
		}
		return true;
	}

	private static List<Table> materializeInputs(Definition definition,
		List<ExactCategoricalSolver.Factor> factors, Plan plan, BooleanSupplier cancelled) {
		List<Table> tables = new ArrayList<>(plan.factorCount);
		int[] global = new int[definition.variables.size()];
		for(int factorIndex = 0; factorIndex < factors.size(); factorIndex++) {
			checkCancelled(cancelled);
			ExactCategoricalSolver.Factor factor = factors.get(factorIndex);
			int[] canonicalScope = definition.scopes.get(factorIndex);
			int cells = Math.toIntExact(cells(canonicalScope, definition.domains));
			double[] values = new double[cells];
			int[] callerValues = new int[factor.scope().size()];
			int[] callerScopeIndices = new int[factor.scope().size()];
			for(int position = 0; position < callerScopeIndices.length; position++)
				callerScopeIndices[position] = identityIndex(
					definition.variables, factor.scope().get(position));
			for(int cell = 0; cell < cells; cell++) {
				checkCancelled(cancelled);
				decode(cell, canonicalScope, definition.domains, global);
				for(int position = 0; position < callerValues.length; position++)
					callerValues[position] = global[callerScopeIndices[position]];
				double value = factor.cost(callerValues);
				validateCost(value);
				values[cell] = value;
			}
			tables.add(new Table(canonicalScope, strides(canonicalScope, definition.domains), values));
		}
		return tables;
	}

	private static List<StepResult> eliminate(Definition definition, Plan plan,
		List<Table> tables, BooleanSupplier cancelled) {
		List<StepResult> results = new ArrayList<>(plan.steps.size());
		int[] global = new int[definition.variables.size()];
		for(PlannedStep step : plan.steps) {
			checkCancelled(cancelled);
			List<MiniBucketResult> miniResults = new ArrayList<>();
			for(PlannedMiniBucket mini : step.miniBuckets) {
				double[] output = new double[mini.outputCells];
				int[] choices = new int[mini.outputCells];
				for(int cell = 0; cell < output.length; cell++) {
					checkCancelled(cancelled);
					decode(cell, mini.outputScope, definition.domains, global);
					double best = Double.POSITIVE_INFINITY;
					int bestValue = 0;
					for(int value = 0; value < definition.domains[step.variable]; value++) {
						checkCancelled(cancelled);
						global[step.variable] = value;
						double candidate = 0d;
						for(int input : mini.inputs)
							candidate = addDown(candidate, tables.get(input).value(global));
						if(candidate < best) {
							best = candidate;
							bestValue = value;
						}
					}
					output[cell] = best;
					choices[cell] = bestValue;
				}
				for(int input : mini.inputs)
					tables.get(input).active = false;
				Table message = new Table(mini.outputScope,
					strides(mini.outputScope, definition.domains), output);
				while(tables.size() < mini.outputId)
					tables.add(null);
				tables.add(message);
				miniResults.add(new MiniBucketResult(mini.unionScope, mini.outputScope, choices));
			}
			results.add(new StepResult(step.variable, List.copyOf(miniResults)));
		}
		return results;
	}

	private static List<Conflict> reconstructConflicts(Definition definition,
		List<StepResult> results, BooleanSupplier cancelled) {
		int[] assignment = new int[definition.variables.size()];
		List<Conflict> reverse = new ArrayList<>();
		for(int stepIndex = results.size() - 1; stepIndex >= 0; stepIndex--) {
			checkCancelled(cancelled);
			StepResult step = results.get(stepIndex);
			List<Integer> choices = new ArrayList<>(step.miniBuckets.size());
			Set<Integer> scope = new HashSet<>();
			for(MiniBucketResult mini : step.miniBuckets) {
				int cell = encode(mini.outputScope, definition.domains, assignment);
				choices.add(mini.choices[cell]);
				for(int variable : mini.unionScope)
					scope.add(variable);
			}
			int selected = choices.get(0);
			assignment[step.variable] = selected;
			long disagreements = choices.stream().filter(choice -> choice != selected).count();
			if(disagreements > 0) {
				List<ExactCategoricalSolver.Variable> conflictScope = scope.stream().sorted()
					.map(definition.variables::get).toList();
				double score = (double)disagreements / (choices.size() - 1);
				reverse.add(new Conflict(definition.variables.get(step.variable), conflictScope, score));
			}
		}
		java.util.Collections.reverse(reverse);
		return List.copyOf(reverse);
	}

	private static double addDown(double left, double right) {
		if(left == Double.POSITIVE_INFINITY || right == Double.POSITIVE_INFINITY)
			return Double.POSITIVE_INFINITY;
		if(left == 0d)
			return right;
		if(right == 0d)
			return left;
		double sum = left + right;
		if(sum == Double.POSITIVE_INFINITY)
			return Double.MAX_VALUE;
		return Math.max(0d, Math.nextDown(sum));
	}

	private static void validateCost(double value) {
		if(Double.isNaN(value) || value < 0d || value == Double.NEGATIVE_INFINITY
			|| Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(-0d))
			throw new IllegalArgumentException("MINI_BUCKET_FACTOR_COST_INVALID|value=" + value);
	}

	private static int identityIndex(List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable target) {
		for(int index = 0; index < variables.size(); index++)
			if(variables.get(index) == target)
				return index;
		throw new IllegalArgumentException("MINI_BUCKET_FACTOR_VARIABLE_FOREIGN");
	}

	private static int[] union(List<SymbolicFactor> factors) {
		Set<Integer> union = new HashSet<>();
		for(SymbolicFactor factor : factors)
			for(int variable : factor.scope)
				union.add(variable);
		return union.stream().sorted().mapToInt(Integer::intValue).toArray();
	}

	private static int[] unionWith(int[] left, int[] right) {
		int[] merged = new int[left.length + right.length];
		int leftPosition = 0;
		int rightPosition = 0;
		int size = 0;
		while(leftPosition < left.length || rightPosition < right.length) {
			int value;
			if(rightPosition >= right.length
				|| leftPosition < left.length && left[leftPosition] < right[rightPosition])
				value = left[leftPosition++];
			else if(leftPosition >= left.length || right[rightPosition] < left[leftPosition])
				value = right[rightPosition++];
			else {
				value = left[leftPosition++];
				rightPosition++;
			}
			merged[size++] = value;
		}
		return Arrays.copyOf(merged, size);
	}

	private static int overlapWithout(int[] left, int[] right, int excluded) {
		int overlap = 0;
		int leftPosition = 0;
		int rightPosition = 0;
		while(leftPosition < left.length && rightPosition < right.length) {
			if(left[leftPosition] == right[rightPosition]) {
				if(left[leftPosition] != excluded)
					overlap++;
				leftPosition++;
				rightPosition++;
			}
			else if(left[leftPosition] < right[rightPosition])
				leftPosition++;
			else
				rightPosition++;
		}
		return overlap;
	}

	private static int compareScopes(int[] left, int[] right) {
		for(int index = 0; index < Math.min(left.length, right.length); index++) {
			int comparison = Integer.compare(left[index], right[index]);
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	}

	private static int[] without(int[] scope, int removed) {
		return Arrays.stream(scope).filter(variable -> variable != removed).toArray();
	}

	private static int[] intersection(int[] left, int[] right) {
		int[] temporary = new int[Math.min(left.length, right.length)];
		int leftPosition = 0;
		int rightPosition = 0;
		int size = 0;
		while(leftPosition < left.length && rightPosition < right.length) {
			if(left[leftPosition] == right[rightPosition]) {
				temporary[size++] = left[leftPosition];
				leftPosition++;
				rightPosition++;
			}
			else if(left[leftPosition] < right[rightPosition])
				leftPosition++;
			else
				rightPosition++;
		}
		return Arrays.copyOf(temporary, size);
	}

	private static boolean contains(int[] scope, int variable) {
		return Arrays.binarySearch(scope, variable) >= 0;
	}

	private static long cells(int[] scope, int[] domains) {
		long cells = 1L;
		for(int variable : scope) {
			if(cells > Long.MAX_VALUE / domains[variable])
				throw new ResourceLimitException("MINI_BUCKET_FACTOR_CELL_OVERFLOW");
			cells *= domains[variable];
		}
		return cells;
	}

	private static void checkFactorLimit(long cells, ExactCategoricalSolver.Limits limits) {
		if(cells > limits.maximumFactorCells() || cells > Integer.MAX_VALUE)
			throw new ResourceLimitException("MINI_BUCKET_FACTOR_LIMIT_EXCEEDED|cells=" + cells
				+ "|limit=" + Math.min(limits.maximumFactorCells(), Integer.MAX_VALUE));
	}

	private static long addCells(long total, long cells, ExactCategoricalSolver.Limits limits) {
		long result = addCount(total, cells, "MINI_BUCKET_MATERIALIZED_CELL_OVERFLOW");
		if(result > limits.maximumMaterializedCells())
			throw new ResourceLimitException("MINI_BUCKET_MATERIALIZED_LIMIT_EXCEEDED|cells="
				+ result + "|limit=" + limits.maximumMaterializedCells());
		return result;
	}

	private static long multiply(long left, long right) {
		if(left > Long.MAX_VALUE / right)
			throw new ResourceLimitException("MINI_BUCKET_EVALUATED_ASSIGNMENT_OVERFLOW");
		return left * right;
	}

	private static long addCount(long left, long right, String reason) {
		if(left > Long.MAX_VALUE - right)
			throw new ResourceLimitException(reason);
		return left + right;
	}

	private static int[] strides(int[] scope, int[] domains) {
		int[] strides = new int[scope.length];
		int stride = 1;
		for(int index = scope.length - 1; index >= 0; index--) {
			strides[index] = stride;
			stride = Math.multiplyExact(stride, domains[scope[index]]);
		}
		return strides;
	}

	private static void decode(int cell, int[] scope, int[] domains, int[] global) {
		for(int position = scope.length - 1; position >= 0; position--) {
			global[scope[position]] = cell % domains[scope[position]];
			cell /= domains[scope[position]];
		}
	}

	private static int encode(int[] scope, int[] domains, int[] assignment) {
		int cell = 0;
		for(int variable : scope)
			cell = cell * domains[variable] + assignment[variable];
		return cell;
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if(cancelled.getAsBoolean())
			throw new CancellationException("MINI_BUCKET_CANCELLED");
	}

	private record Definition(List<ExactCategoricalSolver.Variable> variables,
		int[] domains, List<int[]> scopes) { }
	private record SymbolicFactor(int id, int[] scope, boolean nativeWidthExempt,
		int[] leaves) { }
	private record PlannedMiniBucket(int[] inputs, int[] unionScope, int[] outputScope,
		int outputId, int outputCells, int[] leaves) { }
	private record PlannedStep(int variable, List<PlannedMiniBucket> miniBuckets) { }
	private record Plan(List<PlannedStep> steps, int factorCount, int splitBuckets,
		long maximumFactorCells, long materializedCells, long evaluatedAssignments,
		int[] eliminationOrder) {
		Plan { eliminationOrder = eliminationOrder.clone(); }
	}
	static record JoinCluster(int[] scope, int[] originalFactors) {
		JoinCluster {
			scope = scope.clone();
			originalFactors = originalFactors.clone();
		}
		@Override public int[] scope() { return scope.clone(); }
		@Override public int[] originalFactors() { return originalFactors.clone(); }
	}
	static record JoinEdge(int leftCluster, int rightCluster, int[] separator,
		boolean generatedMessage) {
		JoinEdge { separator = separator.clone(); }
		@Override public int[] separator() { return separator.clone(); }
	}
	static record JoinGraphPlan(int[] domains, List<int[]> factorScopes,
		List<JoinCluster> clusters, List<JoinEdge> edges, Statistics statistics) {
		JoinGraphPlan {
			domains = domains.clone();
			factorScopes = factorScopes.stream().map(int[]::clone).toList();
			clusters = List.copyOf(clusters);
			edges = List.copyOf(edges);
		}
		@Override public int[] domains() { return domains.clone(); }
		@Override public List<int[]> factorScopes() {
			return factorScopes.stream().map(int[]::clone).toList();
		}
	}
	private record MiniBucketResult(int[] unionScope, int[] outputScope, int[] choices) { }
	private record StepResult(int variable, List<MiniBucketResult> miniBuckets) { }

	private static final class Table {
		private final int[] scope;
		private final int[] strides;
		private final double[] values;
		private boolean active = true;

		private Table(int[] scope, int[] strides, double[] values) {
			this.scope = scope.clone();
			this.strides = strides;
			this.values = values;
		}

		private double value(int[] global) {
			int cell = 0;
			for(int position = 0; position < scope.length; position++)
				cell += global[scope[position]] * strides[position];
			return values[cell];
		}
	}
}
