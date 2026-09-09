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
		Assert.assertFalse(bound.hasRefinementCandidates());
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
	public void componentIndexPreservesKnownSequenceAcrossCommitsAndCancellation() {
		Model first = conflictingFork("indexed-a", 2, 10d, 4d);
		Model second = conflictingFork("indexed-b", 2, 10d, 4d);
		Model third = conflictingFork("indexed-c", 2, 10d, 4d);
		List<ExactCategoricalSolver.Variable> variables = List.of(
			first.variables.get(0), first.variables.get(1), first.variables.get(2),
			second.variables.get(0), second.variables.get(1), second.variables.get(2),
			third.variables.get(0), third.variables.get(1), third.variables.get(2));
		List<ExactCategoricalSolver.Factor> factors = List.of(
			first.factors.get(0), first.factors.get(1), first.factors.get(2), first.factors.get(3),
			second.factors.get(0), second.factors.get(1), second.factors.get(2), second.factors.get(3),
			third.factors.get(0), third.factors.get(1), third.factors.get(2), third.factors.get(3));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 1_000_000, () -> false);
		Assert.assertEquals(6, bound.componentCount());

		IncrementalReplicaBound.Refinement firstRefinement = bound.refine(1, () -> false);
		Assert.assertEquals(0, firstRefinement.originalVariable());
		Assert.assertEquals(2, firstRefinement.selection().touchedComponents());
		Assert.assertEquals(5, bound.componentCount());
		Assert.assertTrue(bound.lowerBound() <= optimum);
		double afterFirst = bound.lowerBound();
		List<Integer> assignmentAfterFirst = bound.suggestedAssignment(false);

		Assert.assertThrows(CancellationException.class, () -> bound.refine(1, () -> true));
		Assert.assertEquals(5, bound.componentCount());
		Assert.assertEquals(afterFirst, bound.lowerBound(), 0d);
		Assert.assertEquals(assignmentAfterFirst, bound.suggestedAssignment(false));

		IncrementalReplicaBound.Refinement secondRefinement = bound.refine(1, () -> false);
		Assert.assertEquals(3, secondRefinement.originalVariable());
		Assert.assertEquals(2, secondRefinement.selection().touchedComponents());
		Assert.assertEquals(4, bound.componentCount());
		Assert.assertTrue(bound.lowerBound() <= optimum);

		IncrementalReplicaBound.Refinement thirdRefinement = bound.refine(1, () -> false);
		Assert.assertEquals(6, thirdRefinement.originalVariable());
		Assert.assertEquals(2, thirdRefinement.selection().touchedComponents());
		Assert.assertEquals(3, bound.componentCount());
		Assert.assertEquals(9, bound.workStats().reusedComponents());
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 8);
		Assert.assertEquals(optimum, ExactCategoricalSolver.evaluate(variables, factors,
			GENEROUS, bound.suggestedAssignment(false)), Math.ulp(optimum) * 8);
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
			ExactCategoricalSolver.Factor.dense(List.of(), 1.25d),
			ExactCategoricalSolver.Factor.dense(List.of(shared, bridge, left),
				0d, 7d, 2d, 6d, 5d, 1d, 4d, 3d),
			ExactCategoricalSolver.Factor.dense(List.of(shared, bridge, right),
				6d, 0d, 5d, 2d, 1d, 7d, 3d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(right), 4d, 0d));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
			variables, factors, 3, GENEROUS, () -> false);
		Assert.assertEquals(2, replica.replicas().get(0).size());
		List<ExactCategoricalSolver.Factor> uncontractedFactors = new java.util.ArrayList<>(replica.factors());
		uncontractedFactors.add(ExactCategoricalSolver.Factor.lazy(replica.replicas().get(0),
			values -> values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY));
		long uncontractedWork = ExactCategoricalSolver.analyze(
			replica.variables(), uncontractedFactors, GENEROUS).eliminationAssignments();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 3, GENEROUS, 1_000_000, () -> false);
		Assert.assertTrue(bound.replicaVariables() > variables.size());
		Assert.assertEquals(2, bound.componentCount());
		long calls = bound.workStats().calls();
		long assignments = bound.workStats().assignments();
		IncrementalReplicaBound.Refinement refinement = bound.refine(1, () -> false);
		Assert.assertTrue(refinement.changed());
		Assert.assertEquals(2, bound.componentCount());
		Assert.assertEquals(calls + 1, bound.workStats().calls());
		Assert.assertTrue(bound.workStats().assignments() - assignments < uncontractedWork);
		Assert.assertTrue(bound.lowerBound() <= optimum);
		Assert.assertTrue(bound.fullyRestored());
		Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 16);
		Assert.assertEquals(optimum, ExactCategoricalSolver.evaluate(variables, factors,
			GENEROUS, bound.suggestedAssignment(false)), Math.ulp(optimum) * 16);
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
		Assert.assertEquals(1, refinement.selection().modalMinorityGroups());
		Assert.assertEquals(3, refinement.selection().representativeGroups());
		Assert.assertEquals(3, refinement.selection().touchedComponents());
		Assert.assertTrue(refinement.selection().cachedAssignments() > 0L);
		Assert.assertTrue(refinement.selection().plannedAssignments() > 0L);
		Assert.assertTrue(refinement.selection().measuredNanos() > 0L);
	}

	@Test
	public void modalMinorityTelemetryIsInvariantToReplicaGroupOrder() {
		Model forward = threeWayFork("order", 2, 10d, 4d);
		List<ExactCategoricalSolver.Factor> reversedFactors = List.of(
			forward.factors.get(2), forward.factors.get(1), forward.factors.get(0),
			forward.factors.get(5), forward.factors.get(4), forward.factors.get(3));
		IncrementalReplicaBound forwardBound = IncrementalReplicaBound.create(
			forward.variables, forward.factors, 2, GENEROUS, 1_000_000, () -> false);
		IncrementalReplicaBound reversedBound = IncrementalReplicaBound.create(
			forward.variables, reversedFactors, 2, GENEROUS, 1_000_000, () -> false);

		IncrementalReplicaBound.Refinement forwardRefinement = forwardBound.refine(1, () -> false);
		IncrementalReplicaBound.Refinement reversedRefinement = reversedBound.refine(1, () -> false);

		Assert.assertTrue(forwardRefinement.changed());
		Assert.assertTrue(reversedRefinement.changed());
		Assert.assertEquals(1, forwardRefinement.selection().modalMinorityGroups());
		Assert.assertEquals(forwardRefinement.selection().modalMinorityGroups(),
			reversedRefinement.selection().modalMinorityGroups());
		Assert.assertEquals(forwardRefinement.selection().representativeGroups(),
			reversedRefinement.selection().representativeGroups());
		Assert.assertEquals(forwardRefinement.selection().touchedComponents(),
			reversedRefinement.selection().touchedComponents());
		Assert.assertEquals(forwardRefinement.selection().cachedAssignments(),
			reversedRefinement.selection().cachedAssignments());
		Assert.assertEquals(forwardBound.lowerBound(), reversedBound.lowerBound(),
			Math.ulp(forwardBound.lowerBound()) * 8);
	}

	@Test
	public void resourceBlockedFirstChoiceLeavesAnotherValidCandidate() {
		Model large = conflictingFork("first-limited", 10, 10d, 4d);
		Model tied = tiedFork("remaining", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(
			large.variables.get(0), large.variables.get(1), large.variables.get(2),
			tied.variables.get(0), tied.variables.get(1), tied.variables.get(2));
		List<ExactCategoricalSolver.Factor> factors = List.of(
			large.factors.get(0), large.factors.get(1), large.factors.get(2), large.factors.get(3),
			tied.factors.get(0), tied.factors.get(1));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 2, GENEROUS, 110, () -> false);

		IncrementalReplicaBound.Refinement limited = bound.refine(1, () -> false);

		Assert.assertFalse(limited.changed());
		Assert.assertEquals(new IncrementalReplicaBound.Selection(0, 0, 0, 0L, 0L, 0L),
			limited.selection());
		Assert.assertTrue(bound.hasRefinementCandidates());
		Assert.assertTrue(bound.lowerBound() <= optimum);

		IncrementalReplicaBound.Refinement remaining = bound.refine(1, () -> false);
		Assert.assertTrue(remaining.changed());
		Assert.assertEquals(3, remaining.originalVariable());
		Assert.assertEquals(0, remaining.selection().modalMinorityGroups());
		Assert.assertTrue(bound.lowerBound() <= optimum);
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
	public void cancellationAfterBatchSolveDoesNotCommitAnyEquality() {
		Model model = threeWayFork("cancelled-batch", 2, 10d, 4d);
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			model.variables, model.factors, 2, GENEROUS, 1_000_000, () -> false);
		double lower = bound.lowerBound();
		int components = bound.componentCount();
		List<Integer> assignment = bound.suggestedAssignment(false);
		int[] cancellationPolls = {0};

		Assert.assertThrows(CancellationException.class,
			() -> bound.refine(1, () -> ++cancellationPolls[0] >= 5));

		Assert.assertEquals(0, bound.restoredEqualities());
		Assert.assertEquals(components, bound.componentCount());
		Assert.assertEquals(lower, bound.lowerBound(), 0d);
		Assert.assertEquals(assignment, bound.suggestedAssignment(false));
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
	public void connectedTwoVariableBatchClosesPlateauThatEitherSingleCannotImprove() {
		Model model = twoVariablePlateau();
		double optimum = ExactCategoricalSolver.solve(model.variables, model.factors, GENEROUS).objective();
		Assert.assertEquals(3.5d, optimum, 0d);
		MiniBucketLowerBound.ReplicaModel replica = MiniBucketLowerBound.replicaModel(
			model.variables, model.factors, 1, GENEROUS, () -> false);
		for(int original : new int[] {0, 1}) {
			List<ExactCategoricalSolver.Factor> one = new java.util.ArrayList<>(replica.factors());
			List<ExactCategoricalSolver.Variable> group = replica.replicas().get(original);
			Assert.assertEquals(2, group.size());
			one.add(ExactCategoricalSolver.Factor.lazy(group,
				values -> values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY));
			Assert.assertEquals(2.5d, ExactCategoricalSolver.solve(replica.variables(), one, GENEROUS).objective(), 0d);
		}
		for(boolean reuse : new boolean[] {false, true}) {
			IncrementalReplicaBound bound = IncrementalReplicaBound.create(
				model.variables, model.factors, 1, GENEROUS, 1_000_000, reuse, () -> false);
			long calls = bound.workStats().calls();
			IncrementalReplicaBound.Refinement refined = bound.refine(1, 2, () -> false);
			Assert.assertTrue(refined.changed());
			Assert.assertEquals(2, refined.selection().batchVariables());
			Assert.assertEquals(java.util.Set.of(0, 1), java.util.Set.copyOf(refined.restoredOriginalVariables()));
			Assert.assertEquals(calls + 1, bound.workStats().calls());
			Assert.assertEquals(1, bound.workStats().batchProbes());
			Assert.assertEquals(2, bound.restoredEqualities());
			Assert.assertTrue(bound.fullyRestored());
			Assert.assertTrue(bound.lowerBound() <= optimum);
			Assert.assertEquals(optimum, bound.lowerBound(), Math.ulp(optimum) * 8);
			Assert.assertEquals(reuse ? 1 : 0, bound.workStats().preparedOrderHits());
		}
	}

	@Test
	public void failedConnectedBatchFallsBackToFeasibleSingleWithoutBlockingIt() {
		var x = variable("limited-x", 2);
		var y = variable("limited-y", 2);
		var a = variable("limited-a", 2);
		var b = variable("limited-b", 2);
		var c = variable("limited-c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y, a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, a), 0d, 0d, 0d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(x, b), 0d, 0d, 0d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(y, b), 0d, 0d, 0d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(y, c), 0d, 0d, 0d, 0d));
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(
			variables, factors, 1, GENEROUS, 14, () -> false);
		IncrementalReplicaBound.Refinement refined = bound.refine(1, 2, () -> false);
		Assert.assertTrue(refined.changed());
		Assert.assertEquals(1, refined.selection().batchVariables());
		Assert.assertEquals(1, bound.restoredEqualities());
		Assert.assertEquals(1, bound.workStats().batchFallbacks());
		Assert.assertEquals(1, bound.workStats().resourceSkips());
		Assert.assertFalse(bound.fullyRestored());
		Assert.assertEquals(0d, bound.lowerBound(), 0d);
	}

	@Test
	public void cancellationAfterJointSolvePreservesAllPublishedState() {
		Model model = twoVariablePlateau();
		for(boolean reuse : new boolean[] {false, true}) {
			IncrementalReplicaBound bound = IncrementalReplicaBound.create(
				model.variables, model.factors, 1, GENEROUS, 1_000_000, reuse, () -> false);
			long calls = bound.workStats().calls();
			double lower = bound.lowerBound();
			int count = bound.componentCount();
			List<Integer> assignment = bound.suggestedAssignment(false);
			Assert.assertThrows(CancellationException.class,
				() -> bound.refine(1, 2, () -> bound.workStats().calls() > calls));
			Assert.assertEquals(calls + 1, bound.workStats().calls());
			Assert.assertEquals(0, bound.restoredEqualities());
			Assert.assertEquals(lower, bound.lowerBound(), 0d);
			Assert.assertEquals(count, bound.componentCount());
			Assert.assertEquals(assignment, bound.suggestedAssignment(false));
			Assert.assertTrue(bound.refine(1, 2, () -> false).changed());
			Assert.assertTrue(bound.fullyRestored());
		}
	}

	@Test
	public void cachedOrderRetainsBoundsAndFeasibleProjectionAcrossSequentialRefinements() {
		Model model = twoVariablePlateau();
		IncrementalReplicaBound ordinary = IncrementalReplicaBound.create(
			model.variables, model.factors, 1, GENEROUS, 1_000_000, false, () -> false);
		IncrementalReplicaBound reused = IncrementalReplicaBound.create(
			model.variables, model.factors, 1, GENEROUS, 1_000_000, true, () -> false);
		while(!ordinary.fullyRestored()) {
			double previous = reused.lowerBound();
			ordinary.refine(1, () -> false);
			reused.refine(1, () -> false);
			Assert.assertEquals(ordinary.lowerBound(), reused.lowerBound(), 0d);
			Assert.assertTrue(reused.lowerBound() >= previous);
		}
		Assert.assertTrue(reused.fullyRestored());
		Assert.assertEquals(3.5d, ExactCategoricalSolver.evaluate(model.variables, model.factors,
			GENEROUS, reused.suggestedAssignment(false)), 0d);
		Assert.assertEquals(2, reused.workStats().preparedOrderHits());
	}

	private static Model twoVariablePlateau() {
		var x = variable("plateau-x", 2);
		var y = variable("plateau-y", 2);
		var a = variable("plateau-a", 2);
		var b = variable("plateau-b", 2);
		double[] equal = new double[8];
		double[] different = new double[8];
		for(int index = 0; index < 8; index++) {
			boolean same = index / 4 == (index / 2) % 2;
			equal[index] = same ? 0d : 1d;
			different[index] = same ? 1d : 0d;
		}
		return new Model(List.of(x, y, a, b), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(), 2.5d),
			ExactCategoricalSolver.Factor.dense(List.of(x, y, a), equal),
			ExactCategoricalSolver.Factor.dense(List.of(x, y, b), different)));
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
		Assert.assertThrows(IllegalArgumentException.class, () -> bound.refine(1, 0, () -> false));
		Assert.assertThrows(IllegalArgumentException.class, () -> bound.refine(1, 33, () -> false));
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

	private static Model tiedFork(String prefix, int domain) {
		var shared = variable(prefix + "-shared", domain);
		var left = variable(prefix + "-left", domain);
		var right = variable(prefix + "-right", domain);
		return new Model(List.of(shared, left, right), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(shared, left), new double[domain * domain]),
			ExactCategoricalSolver.Factor.dense(List.of(shared, right), new double[domain * domain])));
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private record Model(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) { }
}
