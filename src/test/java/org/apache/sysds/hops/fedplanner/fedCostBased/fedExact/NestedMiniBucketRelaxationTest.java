/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import org.junit.Assert;
import org.junit.Test;

public class NestedMiniBucketRelaxationTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void explicitReplicaOptimumMatchesIntegerMiniBucketRelaxation() {
		Random random = new Random(814927L);
		for(int trial = 0; trial < 40; trial++) {
			List<ExactCategoricalSolver.Variable> variables = List.of(
				variable("a" + trial, 2), variable("b" + trial, 2),
				variable("c" + trial, 2), variable("aux" + trial, 2));
			List<ExactCategoricalSolver.Factor> factors = List.of(
				randomFactor(random, List.of(variables.get(0), variables.get(1))),
				randomFactor(random, List.of(variables.get(0), variables.get(2))),
				randomFactor(random, List.of(variables.get(1), variables.get(2), variables.get(3))),
				randomFactor(random, List.of(variables.get(3))));
			MiniBucketLowerBound.Result mbe = MiniBucketLowerBound.compute(
				variables, factors, 2, GENEROUS, () -> false);
			MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
				variables, factors, 2, GENEROUS, () -> false);
			double replicaOptimum = ExactCategoricalSolver.solve(
				replica.variables(), replica.factors(), GENEROUS).objective();
			double originalOptimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
			Assert.assertTrue("trial=" + trial, mbe.lowerBound() <= replicaOptimum);
			Assert.assertEquals("trial=" + trial, replicaOptimum, mbe.lowerBound(), 1e-10);
			Assert.assertTrue(replicaOptimum <= originalOptimum);
		}
	}

	@Test
	public void diagonalAssignmentPreservesObjectiveIncludingAuxiliaryReplicas() {
		var producer = variable("producer", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		var auxiliary = variable("activation-aux", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(auxiliary, producer, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(producer, left, auxiliary),
				0, 1, 2, 3, 4, 5, 6, 7),
			ExactCategoricalSolver.Factor.dense(List.of(producer, right, auxiliary),
				8, 7, 6, 5, 4, 3, 2, 1));
		MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
			variables, factors, 2, GENEROUS, () -> false);
		Assert.assertTrue(replica.replicas().get(0).size() > 1);
		Assert.assertTrue(replica.replicas().get(1).size() > 1);
		for(List<Integer> assignment : List.of(
			List.of(0, 0, 0, 0), List.of(0, 1, 1, 1), List.of(1, 0, 1, 0), List.of(1, 1, 0, 1))) {
			double original = ExactCategoricalSolver.evaluate(variables, factors, GENEROUS, assignment);
			double lifted = ExactCategoricalSolver.evaluate(replica.variables(), replica.factors(),
				GENEROUS, replica.diagonalAssignment(assignment));
			Assert.assertEquals(original, lifted, 0d);
		}
	}

	@Test
	public void persistentEqualitiesMonotonicallyCloseKnownGap() {
		var producer = variable("producer", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(producer, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			pairEquality(producer, left, 10d), pairEquality(producer, right, 10d),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(right), 4d, 0d));
		NestedMiniBucketRelaxation relaxation = NestedMiniBucketRelaxation.create(
			variables, factors, 2, GENEROUS, () -> false);
		Assert.assertEquals(0d, relaxation.lowerBound(), 0d);
		double previous = relaxation.lowerBound();
		while(!relaxation.fullyRestored()) {
			NestedMiniBucketRelaxation.Refinement refinement = relaxation.refine(4, () -> false);
			Assert.assertTrue(refinement.merged());
			Assert.assertTrue(refinement.lowerBound() >= previous);
			previous = refinement.lowerBound();
		}
		Assert.assertTrue(relaxation.lowerBound() <= 4d);
		Assert.assertEquals(4d, relaxation.solvedObjective(), 0d);
		Assert.assertEquals(ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective(),
			relaxation.solvedObjective(), 0d);
		Assert.assertTrue(relaxation.restoredEqualities() > 0);
	}

	@Test
	public void failedOrCancelledRefinementPreservesSuccessfulState() {
		var split = variable("split", 10);
		var left = variable("left", 2);
		var right = variable("right", 2);
		double[] zeros = new double[20];
		List<ExactCategoricalSolver.Variable> variables = List.of(split, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(split, left), zeros),
			ExactCategoricalSolver.Factor.dense(List.of(split, right), zeros));
		ExactCategoricalSolver.Limits equalityTooLarge =
			new ExactCategoricalSolver.Limits(50, 1_000);
		NestedMiniBucketRelaxation relaxation = NestedMiniBucketRelaxation.create(
			variables, factors, 1, equalityTooLarge, () -> false);
		double before = relaxation.lowerBound();
		List<Integer> assignment = relaxation.relaxedAssignment();
		NestedMiniBucketRelaxation.Refinement limited = relaxation.refine(3, () -> false);
		Assert.assertFalse(limited.merged());
		Assert.assertTrue(limited.resourceLimitedProbes() > 0);
		Assert.assertEquals(before, relaxation.lowerBound(), 0d);
		Assert.assertEquals(assignment, relaxation.relaxedAssignment());
		Assert.assertEquals(0, relaxation.restoredEqualities());
		Assert.assertThrows(CancellationException.class,
			() -> relaxation.refine(1, () -> true));
		Assert.assertEquals(before, relaxation.lowerBound(), 0d);
		Assert.assertEquals(0, relaxation.restoredEqualities());
	}

	@Test
	public void equalArgminDoesNotClaimUnprovedPositiveMergeGain() {
		var shared = variable("shared", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(shared, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, left), 0, 0, 0, 0),
			ExactCategoricalSolver.Factor.dense(List.of(shared, right), 0, 0, 0, 0));
		NestedMiniBucketRelaxation relaxation = NestedMiniBucketRelaxation.create(
			variables, factors, 1, GENEROUS, () -> false);
		Assert.assertFalse(relaxation.candidates().isEmpty());
		Assert.assertFalse(relaxation.candidates().get(0).assignmentDisagrees());
		NestedMiniBucketRelaxation.Refinement refinement = relaxation.refine(1, () -> false);
		Assert.assertTrue(refinement.merged());
		Assert.assertEquals(0d, refinement.lowerBound() - refinement.previousLowerBound(), 0d);
	}

	@Test
	public void conservativePublishedBoundsEncloseMultiscaleExhaustiveOracle() {
		var shared = variable("shared", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(shared, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, left),
				1.0e16, 1.0e16, 1.0e16 + 4d, 1.0e16 + 4d),
			ExactCategoricalSolver.Factor.dense(List.of(shared, right),
				Math.nextUp(1d), Math.nextUp(1d), 1d, 1d),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, Math.nextUp(0d)),
			ExactCategoricalSolver.Factor.dense(List.of(right), Math.nextUp(0d), 0d));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		NestedMiniBucketRelaxation relaxation = NestedMiniBucketRelaxation.create(
			variables, factors, 1, GENEROUS, () -> false);
		Assert.assertTrue(relaxation.lowerBound() <= optimum);
		while(!relaxation.fullyRestored()) {
			relaxation.refine(3, () -> false);
			Assert.assertTrue(relaxation.lowerBound() <= optimum);
		}
		Assert.assertEquals(optimum, relaxation.solvedObjective(), 0d);
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor pairEquality(
		ExactCategoricalSolver.Variable left, ExactCategoricalSolver.Variable right, double mismatch) {
		return ExactCategoricalSolver.Factor.dense(List.of(left, right), 0d, mismatch, mismatch, 0d);
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			values[cell] = random.nextInt(17);
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}
}
