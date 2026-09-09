/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalReplicaClosureTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void closesDisconnectedGroupsOnceAndReusesUnaffectedComponents() {
		Model first = conflictingFork("first", 2, 10d, 4d);
		Model second = conflictingFork("second", 2, 12d, 5d);
		Variable singleton = variable("singleton", 1);
		List<Variable> variables = concatenate(first.variables, second.variables, List.of(singleton));
		List<Factor> factors = concatenate(first.factors, second.factors,
			List.of(Factor.dense(List.of(singleton), 0d), Factor.dense(List.of(), 2.5d)));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 1_000_000, () -> false);
		long calls = bound.workStats().calls();

		IncrementalReplicaBound.Closure closure = bound.closeRemaining(() -> false);

		Assert.assertEquals(2, closure.unresolvedVariables());
		Assert.assertEquals(4, closure.affectedComponents());
		Assert.assertEquals(2, closure.solvedComponents());
		Assert.assertEquals("the singleton and constant components are unaffected",
			2, closure.reusedComponents());
		Assert.assertEquals(1, closure.reusedOriginalVariables());
		Assert.assertEquals(1L, closure.reusedAssignments());
		Assert.assertEquals(3, closure.largestOriginalVariables());
		Assert.assertEquals(calls + 2, bound.workStats().calls());
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 16);
		Assert.assertEquals(optimum, bound.exactObjective(), Math.ulp(optimum) * 16);
		Assert.assertEquals(optimum, ExactCategoricalSolver.evaluate(variables, factors, GENEROUS,
			bound.suggestedAssignment(false)), Math.ulp(optimum) * 16);
		Assert.assertEquals(variables.size(), bound.suggestedAssignment(false).size());
		Assert.assertTrue(closure.elapsedNanos() >= 0L);
	}

	@Test
	public void restoresTiedZeroDisagreementGroupEvenWithoutBoundGain() {
		Model tied = tiedFork("tied");
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			tied.variables, tied.factors, 1, GENEROUS, 1_000_000, () -> false);
		double before = bound.lowerBound();

		IncrementalReplicaBound.Closure closure = bound.closeRemaining(() -> false);

		Assert.assertEquals(1, closure.unresolvedVariables());
		Assert.assertEquals(1, closure.solvedComponents());
		Assert.assertEquals(before, bound.lowerBound(), 0d);
		Assert.assertEquals(1, bound.restoredEqualities());
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertEquals(0d, bound.exactObjective(), 0d);
	}

	@Test
	public void closesOnlyTheGroupsLeftAfterAnEarlierRefinement() {
		Model first = conflictingFork("refined-first", 2, 10d, 4d);
		Model second = conflictingFork("refined-second", 2, 10d, 4d);
		List<Variable> variables = concatenate(first.variables, second.variables);
		List<Factor> factors = concatenate(first.factors, second.factors);
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 1_000_000, () -> false);
		Assert.assertTrue(bound.refine(1, () -> false).changed());
		Assert.assertEquals(1, bound.restoredEqualities());

		IncrementalReplicaBound.Closure closure = bound.closeRemaining(() -> false);

		Assert.assertEquals(1, closure.unresolvedVariables());
		Assert.assertEquals(2, closure.affectedComponents());
		Assert.assertEquals(1, closure.solvedComponents());
		Assert.assertEquals(1, closure.reusedComponents());
		Assert.assertEquals(3, closure.reusedOriginalVariables());
		Assert.assertEquals(3, closure.largestOriginalVariables());
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertEquals(optimum, bound.exactObjective(), Math.ulp(optimum) * 16);
	}

	@Test
	public void cancellationAfterOneDisconnectedCommitPreservesCoveredAndUncoveredState() {
		Model first = conflictingFork("cancel-first", 2, 10d, 4d);
		Model second = conflictingFork("cancel-second", 2, 10d, 4d);
		List<Variable> variables = concatenate(first.variables, second.variables);
		List<Factor> factors = concatenate(first.factors, second.factors);
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 1_000_000, () -> false);
		double initialLower = bound.lowerBound();
		long initialCalls = bound.workStats().calls();

		Assert.assertThrows(CancellationException.class,
			() -> bound.closeRemaining(() -> bound.workStats().calls() > initialCalls + 1));

		Assert.assertEquals("the first disconnected closure remains committed",
			1, bound.restoredEqualities());
		Assert.assertEquals(3, bound.componentCount());
		Assert.assertFalse(bound.fullyRestored());
		Assert.assertTrue(bound.lowerBound() >= initialLower);
		Assert.assertTrue(bound.lowerBound() <= optimum);
		Assert.assertEquals(variables.size(), bound.suggestedAssignment(false).size());
		Assert.assertThrows(IllegalStateException.class, bound::exactObjective);

		IncrementalReplicaBound.Closure resumed = bound.closeRemaining(() -> false);
		Assert.assertEquals(1, resumed.unresolvedVariables());
		Assert.assertEquals(1, resumed.solvedComponents());
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertEquals(optimum, bound.exactObjective(), Math.ulp(optimum) * 16);
		long completedCalls = bound.workStats().calls();

		IncrementalReplicaBound.Closure repeated = bound.closeRemaining(() -> false);
		Assert.assertEquals(0, repeated.unresolvedVariables());
		Assert.assertEquals(0, repeated.solvedComponents());
		Assert.assertEquals("completed closure must be idempotent",
			completedCalls, bound.workStats().calls());
	}

	@Test
	public void laterResourceFailureKeepsEarlierClosureWithoutClaimingExactness() {
		Model small = conflictingFork("small", 2, 10d, 4d);
		Model large = conflictingFork("large", 10, 10d, 4d);
		List<Variable> variables = concatenate(small.variables, large.variables);
		List<Factor> factors = concatenate(small.factors, large.factors);
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 110, () -> false);
		double initialLower = bound.lowerBound();

		Assert.assertThrows(MiniBucketLowerBound.ResourceLimitException.class,
			() -> bound.closeRemaining(() -> false));

		Assert.assertEquals(1, bound.restoredEqualities());
		Assert.assertFalse(bound.fullyRestored());
		Assert.assertTrue(bound.lowerBound() >= initialLower);
		Assert.assertTrue(bound.lowerBound() <= optimum);
		Assert.assertEquals(variables.size(), bound.suggestedAssignment(false).size());
		IllegalStateException failure = Assert.assertThrows(
			IllegalStateException.class, bound::exactObjective);
		Assert.assertEquals("INCREMENTAL_REPLICA_EXACT_NOT_RESTORED", failure.getMessage());
	}

	private static Model conflictingFork(String prefix, int domain, double mismatch,
		double preference) {
		Variable shared = variable(prefix + "-shared", domain);
		Variable left = variable(prefix + "-left", domain);
		Variable right = variable(prefix + "-right", domain);
		double[] equality = new double[domain * domain];
		double[] preferFirst = new double[domain];
		double[] preferLast = new double[domain];
		for(int first = 0; first < domain; first++) {
			for(int second = 0; second < domain; second++)
				equality[first * domain + second] = first == second ? 0d : mismatch;
			preferFirst[first] = first == 0 ? 0d : preference;
			preferLast[first] = first == domain - 1 ? 0d : preference;
		}
		return new Model(List.of(shared, left, right), List.of(
			Factor.dense(List.of(shared, left), equality),
			Factor.dense(List.of(shared, right), equality),
			Factor.dense(List.of(left), preferFirst),
			Factor.dense(List.of(right), preferLast)));
	}

	private static Model tiedFork(String prefix) {
		Variable shared = variable(prefix + "-shared", 2);
		Variable left = variable(prefix + "-left", 2);
		Variable right = variable(prefix + "-right", 2);
		return new Model(List.of(shared, left, right), List.of(
			Factor.dense(List.of(shared, left), new double[4]),
			Factor.dense(List.of(shared, right), new double[4]),
			Factor.dense(List.of(), 0d)));
	}

	@SafeVarargs
	private static <T> List<T> concatenate(List<T>... parts) {
		List<T> result = new ArrayList<>();
		for(List<T> part : parts)
			result.addAll(part);
		return List.copyOf(result);
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private record Model(List<Variable> variables, List<Factor> factors) { }
}
