/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.Assert;
import org.junit.Test;

public class IncrementalReplicaBoundTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void everyCheckpointEnclosesOriginalOptimumAndFullRestorationClosesGap() {
		Model model = conflictingFork("one", 2, 10d, 4d);
		double optimum = ExactCategoricalSolver.solve(model.variables, model.factors, GENEROUS).objective();
		MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
			model.variables, model.factors, 2, GENEROUS, () -> false);
		double relaxedOptimum = ExactCategoricalSolver.solve(
			replica.variables(), replica.factors(), GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 1_000_000, () -> false);
		Assert.assertEquals(2, bound.componentCount());
		Assert.assertTrue(bound.lowerBound() <= relaxedOptimum);
		Assert.assertEquals(relaxedOptimum, bound.lowerBound(), Math.ulp(1d) * 8);
		Assert.assertTrue(bound.lowerBound() <= optimum);
		while(!bound.fullyRestored()) {
			IncrementalReplicaBound.Refinement refinement = bound.refine(2, () -> false);
			Assert.assertTrue(refinement.changed());
			Assert.assertTrue(bound.lowerBound() <= optimum);
		}
		Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 8);
		Assert.assertEquals(optimum, ExactCategoricalSolver.evaluate(model.variables, model.factors,
			GENEROUS, bound.suggestedAssignment(false)), Math.ulp(optimum) * 8);
		Assert.assertEquals(1, bound.restoredEqualities());
		Assert.assertEquals(1, bound.componentCount());
		Assert.assertEquals(model.variables.size(), bound.suggestedAssignment(false).size());
	}

	@Test
	public void independentFactorComponentsReuseUntouchedExactSolutions() {
		Model first = conflictingFork("a", 2, 10d, 4d);
		Model second = conflictingFork("b", 2, 12d, 5d);
		List<ExactCategoricalSolver.Variable> variables = List.of(
			first.variables.get(0), first.variables.get(1), first.variables.get(2),
			second.variables.get(0), second.variables.get(1), second.variables.get(2));
		List<ExactCategoricalSolver.Factor> factors = List.of(
			first.factors.get(0), first.factors.get(1), first.factors.get(2), first.factors.get(3),
			second.factors.get(0), second.factors.get(1), second.factors.get(2), second.factors.get(3));
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 1_000_000, () -> false);
		Assert.assertEquals(4, bound.componentCount());
		long initialCalls = bound.workStats().calls();
		long initialAssignments = bound.workStats().assignments();
		IncrementalReplicaBound.Refinement refinement = bound.refine(1, () -> false);
		Assert.assertTrue(refinement.changed());
		Assert.assertEquals(0, refinement.originalVariable());
		Assert.assertEquals(initialCalls + 1, bound.workStats().calls());
		Assert.assertTrue(bound.workStats().assignments() > initialAssignments);
		Assert.assertEquals(2, bound.workStats().reusedComponents());
		Assert.assertEquals(3, bound.componentCount());
		Assert.assertFalse(bound.fullyRestored());
	}

	@Test
	public void constantsIsolatedAndAuxiliaryReplicasRemainInCertificateAndSuggestion() {
		Model fork = conflictingFork("kept", 2, 10d, 4d);
		ExactCategoricalSolver.Variable auxiliary = variable("activation-aux", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(
			fork.variables.get(0), fork.variables.get(1), fork.variables.get(2), auxiliary);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(), 2d),
			fork.factors.get(0), fork.factors.get(1), fork.factors.get(2), fork.factors.get(3));
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 1_000_000, () -> false);
		Assert.assertEquals(4, bound.componentCount());
		Assert.assertEquals(2d, bound.lowerBound(), Math.ulp(2d) * 8);
		Assert.assertEquals(variables.size(), bound.suggestedAssignment(false).size());
		Assert.assertEquals(variables.size(), bound.suggestedAssignment(true).size());
		Assert.assertEquals(Integer.valueOf(0), bound.suggestedAssignment(true).get(3));
	}

	@Test
	public void equalityWithinOneFactorComponentDoesNotRecomputeOtherComponents() {
		var shared = variable("shared", 2);
		var bridge = variable("bridge", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(shared, bridge, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, bridge, left), new double[8]),
			ExactCategoricalSolver.Factor.dense(List.of(shared, bridge, right), new double[8]),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(right), 4d, 0d));
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 3, GENEROUS, 1_000_000, () -> false);
		Assert.assertTrue(bound.replicaVariables() > variables.size());
		Assert.assertEquals(1, bound.componentCount());
		long calls = bound.workStats().calls();
		IncrementalReplicaBound.Refinement refinement = bound.refine(1, () -> false);
		Assert.assertTrue(refinement.changed());
		Assert.assertEquals(1, bound.componentCount());
		Assert.assertEquals(calls + 1, bound.workStats().calls());
	}

	@Test
	public void tiedAssignmentsStillChooseCheapestFeasibleProgress() {
		var shared = variable("tie-shared", 2);
		var left = variable("tie-left", 2);
		var right = variable("tie-right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(shared, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, left), 0d, 0d, 0d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(shared, right), 0d, 0d, 0d, 0d));
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 1, GENEROUS, 1_000_000, () -> false);
		IncrementalReplicaBound.Refinement refinement = bound.refine(1, () -> false);
		Assert.assertTrue(refinement.changed());
		Assert.assertEquals(0d, refinement.gain(), 0d);
		Assert.assertEquals(1, bound.restoredEqualities());
	}

	@Test
	public void oneRefinementAtomicallyRestoresAllReplicaGroupsOfOneVariable() {
		Model model = threeWayFork("batch", 2, 10d, 4d);
		double optimum = ExactCategoricalSolver.solve(model.variables, model.factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 1_000_000, () -> false);
		Assert.assertEquals(3, bound.componentCount());
		Assert.assertFalse(bound.fullyRestored());
		long calls = bound.workStats().calls();

		IncrementalReplicaBound.Refinement refinement = bound.refine(1, () -> false);

		Assert.assertTrue(refinement.changed());
		Assert.assertEquals(0, refinement.originalVariable());
		Assert.assertEquals(List.of(0, 1, 2, 3), refinement.affectedOriginalVariables());
		Assert.assertEquals(2, bound.restoredEqualities());
		Assert.assertEquals(1, bound.componentCount());
		Assert.assertEquals(calls + 1, bound.workStats().calls());
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertTrue(bound.lowerBound() <= optimum);
		Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 8);
		Assert.assertEquals(optimum, ExactCategoricalSolver.evaluate(model.variables, model.factors,
			GENEROUS, bound.suggestedAssignment(false)), Math.ulp(optimum) * 8);
	}

	@Test
	public void resourceFailureDoesNotPartiallyCommitVariableEqualityBatch() {
		Model model = threeWayFork("limited-batch", 10, 10d, 4d);
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 110, () -> false);
		double lower = bound.lowerBound();
		int components = bound.componentCount();
		List<Integer> assignment = bound.suggestedAssignment(false);

		IncrementalReplicaBound.Refinement refinement = bound.refine(1, () -> false);

		Assert.assertFalse(refinement.changed());
		Assert.assertEquals(1, bound.workStats().resourceSkips());
		Assert.assertEquals(0, bound.restoredEqualities());
		Assert.assertEquals(components, bound.componentCount());
		Assert.assertEquals(lower, bound.lowerBound(), 0d);
		Assert.assertEquals(assignment, bound.suggestedAssignment(false));
		long probes = bound.workStats().probes();
		Assert.assertFalse(bound.refine(1, () -> false).changed());
		Assert.assertEquals(probes, bound.workStats().probes());
	}

	@Test
	public void resourceAndCancellationFailuresAreCertificateAtomicAndBlocked() {
		Model model = conflictingFork("large", 10, 10d, 4d);
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 110, () -> false);
		double lower = bound.lowerBound();
		int components = bound.componentCount();
		List<Integer> assignment = bound.suggestedAssignment(false);
		IncrementalReplicaBound.Refinement limited = bound.refine(1, () -> false);
		Assert.assertFalse(limited.changed());
		Assert.assertEquals(1, bound.workStats().resourceSkips());
		Assert.assertEquals(lower, bound.lowerBound(), 0d);
		Assert.assertEquals(components, bound.componentCount());
		Assert.assertEquals(assignment, bound.suggestedAssignment(false));
		Assert.assertEquals(0, bound.restoredEqualities());
		long probes = bound.workStats().probes();
		Assert.assertFalse(bound.refine(1, () -> false).changed());
		Assert.assertEquals(probes, bound.workStats().probes());
		Assert.assertThrows(CancellationException.class, () -> bound.refine(1, () -> true));
		Assert.assertEquals(lower, bound.lowerBound(), 0d);
		Assert.assertEquals(0, bound.restoredEqualities());
	}

	@Test
	public void downwardComponentSumSurvivesMultiscaleCosts() {
		var shared = variable("numeric-shared", 2);
		var left = variable("numeric-left", 2);
		var right = variable("numeric-right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(shared, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, left),
				1.0e16, 1.0e16, 1.0e16 + 4d, 1.0e16 + 4d),
			ExactCategoricalSolver.Factor.dense(List.of(shared, right),
				Math.nextUp(1d), Math.nextUp(1d), 1d, 1d),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, Math.nextUp(0d)),
			ExactCategoricalSolver.Factor.dense(List.of(right), Math.nextUp(0d), 0d));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 1, GENEROUS, 1_000_000, () -> false);
		Assert.assertTrue(bound.lowerBound() <= optimum);
		while(!bound.fullyRestored()) {
			Assert.assertTrue(bound.refine(2, () -> false).changed());
			Assert.assertTrue(bound.lowerBound() <= optimum);
		}
		Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 8);
	}

	@Test
	public void rejectsInvalidLimitsAndProbeCountsWithoutMutation() {
		Model model = conflictingFork("invalid", 2, 10d, 4d);
		Assert.assertThrows(IllegalArgumentException.class, () -> IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 0, () -> false));
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 1_000_000, () -> false);
		double lower = bound.lowerBound();
		Assert.assertThrows(IllegalArgumentException.class, () -> bound.refine(0, () -> false));
		Assert.assertThrows(IllegalArgumentException.class, () -> bound.refine(3, () -> false));
		Assert.assertEquals(lower, bound.lowerBound(), 0d);
	}

	private static Model conflictingFork(String prefix, int domain, double mismatch,
		double preference) {
		var shared = variable(prefix + "-shared", domain);
		var left = variable(prefix + "-left", domain);
		var right = variable(prefix + "-right", domain);
		double[] equality = new double[domain * domain];
		for(int a = 0; a < domain; a++)
			for(int b = 0; b < domain; b++)
				equality[a * domain + b] = a == b ? 0d : mismatch;
		double[] preferFirst = new double[domain];
		double[] preferLast = new double[domain];
		for(int value = 0; value < domain; value++) {
			preferFirst[value] = value == 0 ? 0d : preference;
			preferLast[value] = value == domain - 1 ? 0d : preference;
		}
		return new Model(List.of(shared, left, right), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, left), equality),
			ExactCategoricalSolver.Factor.dense(List.of(shared, right), equality),
			ExactCategoricalSolver.Factor.dense(List.of(left), preferFirst),
			ExactCategoricalSolver.Factor.dense(List.of(right), preferLast)));
	}

	private static Model threeWayFork(String prefix, int domain, double mismatch,
		double preference) {
		var shared = variable(prefix + "-shared", domain);
		var first = variable(prefix + "-first", domain);
		var second = variable(prefix + "-second", domain);
		var third = variable(prefix + "-third", domain);
		double[] equality = new double[domain * domain];
		for(int a = 0; a < domain; a++)
			for(int b = 0; b < domain; b++)
				equality[a * domain + b] = a == b ? 0d : mismatch;
		double[] preferFirst = new double[domain];
		double[] preferLast = new double[domain];
		for(int value = 0; value < domain; value++) {
			preferFirst[value] = value == 0 ? 0d : preference;
			preferLast[value] = value == domain - 1 ? 0d : preference;
		}
		return new Model(List.of(shared, first, second, third), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, first), equality),
			ExactCategoricalSolver.Factor.dense(List.of(shared, second), equality),
			ExactCategoricalSolver.Factor.dense(List.of(shared, third), equality),
			ExactCategoricalSolver.Factor.dense(List.of(first), preferFirst),
			ExactCategoricalSolver.Factor.dense(List.of(second), preferLast),
			ExactCategoricalSolver.Factor.dense(List.of(third), preferLast)));
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private record Model(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) { }
}
