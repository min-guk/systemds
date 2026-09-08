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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Persistent tightening of one explicit mini-bucket replica relaxation.
 *
 * <p>A successful step adds one equality between two replica components and
 * never removes an earlier equality. Candidate quality is measured by solving
 * the tightened relaxation, so a deterministic argmin disagreement is only a
 * priority hint; ties cannot be reported as positive bound gain unless the
 * trial solve proves it. Resource or cancellation failures leave the last
 * successfully solved state untouched.</p>
 */
final class NestedMiniBucketRelaxation {
	private final MiniBucketLowerBound.ReplicaModel model;
	private final ExactCategoricalSolver.Limits limits;
	private final List<ExactCategoricalSolver.Factor> equalityFactors = new ArrayList<>();
	private final IdentityHashMap<ExactCategoricalSolver.Variable, Integer> positions =
		new IdentityHashMap<>();
	private final int[] parent;
	private final List<int[]> replicaPositions;
	private final Set<MergeKey> resourceBlocked = new HashSet<>();
	private double lowerBound;
	private double solvedObjective;
	private List<Integer> assignment;
	private ExactCategoricalSolver.Statistics statistics;
	private long attemptedProbeCalls;
	private long successfulProbeCalls;
	private long resourceLimitedProbeCalls;
	private long probedEliminationAssignments;
	private long probedMaterializedCells;
	private long probedMaximumFactorCells;

	record Candidate(int originalVariable, int leftReplica, int rightReplica,
		boolean assignmentDisagrees) { }
	private record MergeKey(int originalVariable, int leftReplica, int rightReplica) { }
	record Work(long attemptedCalls, long successfulCalls, long resourceLimitedCalls,
		long eliminationAssignments, long materializedCells, long maximumFactorCells) { }
	record Refinement(boolean merged, Candidate candidate, double previousLowerBound,
		double lowerBound, long attemptedCalls, long successfulProbes, long resourceLimitedProbes,
		long probedEliminationAssignments, long probedMaterializedCells, long maximumFactorCells,
		long elapsedNanos, ExactCategoricalSolver.Statistics statistics) { }

	static NestedMiniBucketRelaxation create(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int width,
		ExactCategoricalSolver.Limits limits, BooleanSupplier cancelled) {
		Objects.requireNonNull(cancelled, "cancelled");
		MiniBucketLowerBound.ReplicaModel model = MiniBucketLowerBound.replicaModel(
			variables, factors, width, limits, cancelled);
		checkCancelled(cancelled);
		ExactCategoricalSolver.Result solved = ExactCategoricalSolver.solve(
			model.variables(), model.factors(), limits);
		checkCancelled(cancelled);
		return new NestedMiniBucketRelaxation(model, limits, solved);
	}

	private NestedMiniBucketRelaxation(MiniBucketLowerBound.ReplicaModel model,
		ExactCategoricalSolver.Limits limits, ExactCategoricalSolver.Result solved) {
		this.model = model;
		this.limits = limits;
		this.lowerBound = conservative(solved.objective());
		this.solvedObjective = solved.objective();
		this.assignment = solved.assignmentInVariableOrder();
		this.statistics = solved.statistics();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		parent = new int[model.variables().size()];
		for(int index = 0; index < parent.length; index++)
			parent[index] = index;
		replicaPositions = new ArrayList<>(model.replicas().size());
		for(List<ExactCategoricalSolver.Variable> group : model.replicas()) {
			int[] indexes = new int[group.size()];
			for(int replica = 0; replica < group.size(); replica++)
				indexes[replica] = positions.get(group.get(replica));
			replicaPositions.add(indexes);
		}
	}

	double lowerBound() { return lowerBound; }
	double solvedObjective() { return solvedObjective; }
	String partitionIdentity() { return model.partitionIdentity(); }
	int replicaVariables() { return model.variables().size(); }
	int restoredEqualities() { return equalityFactors.size(); }
	boolean fullyRestored() {
		for(int[] group : replicaPositions)
			for(int index = 1; index < group.length; index++)
				if(find(group[0]) != find(group[index]))
					return false;
		return true;
	}
	List<Integer> relaxedAssignment() { return assignment; }
	ExactCategoricalSolver.Statistics statistics() { return statistics; }
	Work work() {
		return new Work(attemptedProbeCalls, successfulProbeCalls, resourceLimitedProbeCalls,
			probedEliminationAssignments, probedMaterializedCells, probedMaximumFactorCells);
	}
	List<Integer> rankedOriginalVariables(int decisionCount) {
		List<Integer> ranked = new ArrayList<>();
		for(Candidate candidate : candidates())
			if(candidate.originalVariable() < decisionCount && !ranked.contains(candidate.originalVariable()))
				ranked.add(candidate.originalVariable());
		return List.copyOf(ranked);
	}
	List<Integer> originalAssignment(int decisionCount) {
		if(!fullyRestored())
			throw new IllegalStateException("NESTED_MBE_NOT_FULLY_RESTORED");
		if(decisionCount < 0 || decisionCount > replicaPositions.size())
			throw new IllegalArgumentException("NESTED_MBE_DECISION_COUNT_INVALID");
		List<Integer> original = new ArrayList<>(decisionCount);
		for(int variable = 0; variable < decisionCount; variable++)
			original.add(assignment.get(replicaPositions.get(variable)[0]));
		return List.copyOf(original);
	}

	List<Candidate> candidates() {
		List<Candidate> result = new ArrayList<>();
		for(int original = 0; original < replicaPositions.size(); original++) {
			int[] group = replicaPositions.get(original);
			if(group.length < 2)
				continue;
			List<Integer> representatives = new ArrayList<>();
			for(int position : group) {
				int representative = find(position);
				if(!representatives.contains(representative))
					representatives.add(representative);
			}
			for(int index = 1; index < representatives.size(); index++) {
				int left = representatives.get(0);
				int right = representatives.get(index);
				int leftReplica = replicaIndex(group, left);
				int rightReplica = replicaIndex(group, right);
				if(!resourceBlocked.contains(new MergeKey(original, leftReplica, rightReplica)))
					result.add(new Candidate(original, leftReplica, rightReplica,
						!assignment.get(left).equals(assignment.get(right))));
			}
		}
		result.sort(Comparator.comparing(Candidate::assignmentDisagrees).reversed()
			.thenComparingInt(Candidate::originalVariable)
			.thenComparingInt(Candidate::leftReplica)
			.thenComparingInt(Candidate::rightReplica));
		return List.copyOf(result);
	}

	Refinement refine(int maximumCandidateProbes, BooleanSupplier cancelled) {
		if(maximumCandidateProbes < 1)
			throw new IllegalArgumentException("NESTED_MBE_PROBE_LIMIT_INVALID");
		Objects.requireNonNull(cancelled, "cancelled");
		checkCancelled(cancelled);
		long started = System.nanoTime();
		Candidate bestCandidate = null;
		ExactCategoricalSolver.Factor bestEquality = null;
		ExactCategoricalSolver.Result bestResult = null;
		int successful = 0;
		int limited = 0;
		int attempted = 0;
		long probedAssignments = 0L;
		long materializedCells = 0L;
		long maximumFactorCells = 0L;
		List<Candidate> candidates = candidates();
		for(int index = 0; index < Math.min(maximumCandidateProbes, candidates.size()); index++) {
			checkCancelled(cancelled);
			Candidate candidate = candidates.get(index);
			ExactCategoricalSolver.Factor equality = equality(candidate);
			List<ExactCategoricalSolver.Factor> trialFactors = factorsWith(equality);
			attempted++;
			attemptedProbeCalls++;
			try {
				ExactCategoricalSolver.Result trial = ExactCategoricalSolver.solve(
					model.variables(), trialFactors, limits);
				successful++;
				successfulProbeCalls++;
				probedAssignments = Math.addExact(probedAssignments,
					trial.statistics().eliminationAssignments());
				probedEliminationAssignments = Math.addExact(probedEliminationAssignments,
					trial.statistics().eliminationAssignments());
				materializedCells = Math.addExact(materializedCells,
					trial.statistics().materializedFactorCells());
				probedMaterializedCells = Math.addExact(probedMaterializedCells,
					trial.statistics().materializedFactorCells());
				maximumFactorCells = Math.max(maximumFactorCells,
					trial.statistics().maximumFactorCells());
				probedMaximumFactorCells = Math.max(probedMaximumFactorCells,
					trial.statistics().maximumFactorCells());
				checkCancelled(cancelled);
				if(bestResult == null || trial.objective() > bestResult.objective()
					|| trial.objective() == bestResult.objective()
						&& compare(candidate, bestCandidate) < 0) {
					bestCandidate = candidate;
					bestEquality = equality;
					bestResult = trial;
				}
			}
			catch(IllegalArgumentException failure) {
				if(!RegionalSearchProblem.isResourceLimit(failure))
					throw failure;
				resourceBlocked.add(key(candidate));
				limited++;
				resourceLimitedProbeCalls++;
			}
		}
		checkCancelled(cancelled);
		double previous = lowerBound;
		if(bestResult != null) {
			equalityFactors.add(bestEquality);
			union(position(bestCandidate, true), position(bestCandidate, false));
			assignment = bestResult.assignmentInVariableOrder();
			statistics = bestResult.statistics();
			solvedObjective = bestResult.objective();
			lowerBound = Math.max(lowerBound, conservative(bestResult.objective()));
		}
		return new Refinement(bestResult != null, bestCandidate, previous, lowerBound,
			attempted, successful, limited, probedAssignments, materializedCells, maximumFactorCells,
			System.nanoTime() - started, statistics);
	}

	private ExactCategoricalSolver.Factor equality(Candidate candidate) {
		ExactCategoricalSolver.Variable left = model.replicas().get(candidate.originalVariable())
			.get(candidate.leftReplica());
		ExactCategoricalSolver.Variable right = model.replicas().get(candidate.originalVariable())
			.get(candidate.rightReplica());
		return ExactCategoricalSolver.Factor.lazy(List.of(left, right), values ->
			values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);
	}

	private List<ExactCategoricalSolver.Factor> factorsWith(ExactCategoricalSolver.Factor extra) {
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>(
			model.factors().size() + equalityFactors.size() + 1);
		factors.addAll(model.factors());
		factors.addAll(equalityFactors);
		factors.add(extra);
		return factors;
	}

	private int position(Candidate candidate, boolean left) {
		int replica = left ? candidate.leftReplica() : candidate.rightReplica();
		return replicaPositions.get(candidate.originalVariable())[replica];
	}

	private int replicaIndex(int[] group, int representative) {
		for(int index = 0; index < group.length; index++)
			if(find(group[index]) == representative)
				return index;
		throw new IllegalStateException("NESTED_MBE_REPLICA_COMPONENT_MISSING");
	}

	private int find(int index) {
		while(parent[index] != index)
			index = parent[index];
		return index;
	}

	private void union(int left, int right) {
		left = find(left);
		right = find(right);
		if(left != right)
			parent[right] = left;
	}

	private static int compare(Candidate left, Candidate right) {
		if(right == null)
			return -1;
		int compared = Integer.compare(left.originalVariable(), right.originalVariable());
		if(compared == 0)
			compared = Integer.compare(left.leftReplica(), right.leftReplica());
		if(compared == 0)
			compared = Integer.compare(left.rightReplica(), right.rightReplica());
		return compared;
	}

	private static MergeKey key(Candidate candidate) {
		return new MergeKey(candidate.originalVariable(), candidate.leftReplica(), candidate.rightReplica());
	}

	private static double conservative(double objective) {
		return objective == 0d || objective == Double.POSITIVE_INFINITY
			? objective : Math.max(0d, Math.nextDown(objective));
	}

	private static void checkCancelled(BooleanSupplier cancelled) {
		if(cancelled.getAsBoolean())
			throw new CancellationException("NESTED_MBE_CANCELLED");
	}
}
