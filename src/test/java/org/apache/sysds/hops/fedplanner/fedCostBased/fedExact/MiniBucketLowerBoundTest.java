/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class MiniBucketLowerBoundTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);
	private static final List<MiniBucketLowerBound.PlanningPolicy> POLICIES = List.of(
		new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.INPUT,
			MiniBucketLowerBound.PartitionStrategy.FIRST_FIT),
		new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.INPUT,
			MiniBucketLowerBound.PartitionStrategy.BEST_FIT),
		new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.WEIGHTED_MIN_FILL,
			MiniBucketLowerBound.PartitionStrategy.FIRST_FIT),
		new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.WEIGHTED_MIN_FILL,
			MiniBucketLowerBound.PartitionStrategy.BEST_FIT));

	@Test
	public void weightedMinFillImprovesAdversarialInputOrderAtSameWidth() {
		var x = variable("x-order", 2);
		var left = variable("left-order", 2);
		var right = variable("right-order", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			pairEquality(x, left, 10d), pairEquality(x, right, 10d),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(right), 4d, 0d));
		var input = MiniBucketLowerBound.compute(variables, factors, 2, GENEROUS,
			MiniBucketLowerBound.PlanningPolicy.INPUT_FIRST_FIT, () -> false);
		var weighted = MiniBucketLowerBound.compute(variables, factors, 2, GENEROUS,
			new MiniBucketLowerBound.PlanningPolicy(
				MiniBucketLowerBound.EliminationOrder.WEIGHTED_MIN_FILL,
				MiniBucketLowerBound.PartitionStrategy.FIRST_FIT), () -> false);
		Assert.assertEquals(0d, input.lowerBound(), 0d);
		Assert.assertEquals(4d, weighted.lowerBound(), Math.ulp(4d) * 8);
	}

	@Test
	public void bestFitImprovesAdversarialFactorOrderAtSameWidth() {
		var x = variable("x-partition", 2);
		var a = variable("a-partition", 2);
		var b = variable("b-partition", 2);
		var c = variable("c-partition", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			xPreference(List.of(x, a), false, 5d),
			xPreference(List.of(x, b), false, 5d),
			xPreference(List.of(x, a, c), true, 5d),
			xPreference(List.of(x, b, c), true, 5d));
		var firstFit = MiniBucketLowerBound.compute(variables, factors, 3, GENEROUS,
			MiniBucketLowerBound.PlanningPolicy.INPUT_FIRST_FIT, () -> false);
		var bestFit = MiniBucketLowerBound.compute(variables, factors, 3, GENEROUS,
			new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.INPUT,
				MiniBucketLowerBound.PartitionStrategy.BEST_FIT), () -> false);
		Assert.assertEquals(0d, firstFit.lowerBound(), 0d);
		Assert.assertEquals(10d, bestFit.lowerBound(), Math.ulp(10d) * 8);
	}

	@Test
	public void everyPolicyIsSoundForRandomHardModelsWithConstantsAndPermutedScopes() {
		Random random = new Random(619004L);
		for(int trial = 0; trial < 40; trial++) {
			var a = variable("pa" + trial, 2);
			var b = variable("pb" + trial, 2);
			var c = variable("pc" + trial, 2);
			List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
			List<ExactCategoricalSolver.Factor> factors = List.of(
				randomHardFactor(random, List.of(b, a)),
				randomHardFactor(random, List.of(c, a)),
				randomHardFactor(random, List.of(c, b)),
				ExactCategoricalSolver.Factor.dense(List.of(), random.nextInt(5)));
			double optimum = bruteForce(variables, factors);
			for(MiniBucketLowerBound.PlanningPolicy policy : POLICIES) {
				double lower = MiniBucketLowerBound.compute(variables, factors, 1,
					GENEROUS, policy, () -> false).lowerBound();
				Assert.assertTrue("trial=" + trial + "|policy=" + policy
					+ "|lower=" + lower + "|optimum=" + optimum, lower <= optimum);
			}
		}
	}

	@Test
	public void policiesPreserveDiagonalCostsOwnershipAndLegacyIdentity() {
		var a = variable("identity-a", 2);
		var b = variable("identity-b", 2);
		var c = variable("identity-c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(b, a), 1d, 7d, 4d, 2d),
			ExactCategoricalSolver.Factor.dense(List.of(a, c), 3d,
				Double.POSITIVE_INFINITY, 5d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(), 2d));
		var legacy = MiniBucketLowerBound.replicaModel(variables, factors, 1, GENEROUS,
			() -> false);
		var explicitLegacy = MiniBucketLowerBound.replicaModel(variables, factors, 1,
			GENEROUS, MiniBucketLowerBound.PlanningPolicy.INPUT_FIRST_FIT, () -> false);
		Assert.assertEquals(legacy.partitionIdentity(), explicitLegacy.partitionIdentity());
		Assert.assertEquals("mb-replica-v1-565d7b2dbba2acfc2959b3af043786912f6896cdfeb97b3344f56fbfe1721de2",
			legacy.partitionIdentity());
		Assert.assertEquals(List.of(0, 1, 2), legacy.eliminationOrder());

		List<Integer> assignment = List.of(1, 1, 0);
		double original = CertifiedRegionalOptimizer.evaluate(variables, factors, assignment);
		for(MiniBucketLowerBound.PlanningPolicy policy : POLICIES) {
			var replica = MiniBucketLowerBound.replicaModel(variables, factors, 1,
				GENEROUS, policy, () -> false);
			double diagonal = CertifiedRegionalOptimizer.evaluate(replica.variables(), replica.factors(),
				replica.diagonalAssignment(assignment));
			Assert.assertEquals(Double.doubleToRawLongBits(original),
				Double.doubleToRawLongBits(diagonal));
			var graph = MiniBucketLowerBound.joinGraphPlan(variables, factors, 1,
				GENEROUS, policy, () -> false);
			int[] ownership = new int[factors.size()];
			for(MiniBucketLowerBound.JoinCluster cluster : graph.clusters())
				for(int factor : cluster.originalFactors())
					ownership[factor]++;
			Assert.assertArrayEquals(new int[] {1, 1, 1}, ownership);
		}
		var alternative = MiniBucketLowerBound.replicaModel(variables, factors, 1,
			GENEROUS, POLICIES.get(3), () -> false);
		Assert.assertNotEquals(legacy.partitionIdentity(), alternative.partitionIdentity());
		Assert.assertEquals(List.of(1, 0, 2), alternative.eliminationOrder());
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> alternative.eliminationOrder().add(0));
	}

	@Test
	public void weightedScoringChecksCancellationInsideStructuralLoops() {
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		for(int index = 0; index < 12; index++)
			variables.add(variable("cancel-policy-" + index, 2));
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int left = 0; left < variables.size(); left++)
			for(int right = left + 1; right < variables.size(); right++)
				factors.add(ExactCategoricalSolver.Factor.dense(
					List.of(variables.get(left), variables.get(right)), 0d, 0d, 0d, 0d));
		AtomicInteger polls = new AtomicInteger();
		Assert.assertThrows(CancellationException.class, () -> MiniBucketLowerBound.compute(
			variables, factors, 2, GENEROUS,
			new MiniBucketLowerBound.PlanningPolicy(
				MiniBucketLowerBound.EliminationOrder.WEIGHTED_MIN_FILL,
				MiniBucketLowerBound.PartitionStrategy.BEST_FIT),
			() -> polls.incrementAndGet() > 100));
		Assert.assertTrue(polls.get() > 100);
	}

	@Test
	public void randomBoundsNeverExceedIndependentBruteForceOracle() {
		Random random = new Random(927441L);
		for(int trial = 0; trial < 150; trial++) {
			List<ExactCategoricalSolver.Variable> variables = List.of(
				variable("a" + trial, 2), variable("b" + trial, 2),
				variable("c" + trial, 2), variable("d" + trial, 2));
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(1))));
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(2))));
			factors.add(randomFactor(random, List.of(variables.get(1), variables.get(2), variables.get(3))));
			factors.add(randomFactor(random, List.of(variables.get(3))));
			double optimum = bruteForce(variables, factors);
			for(int iBound = 1; iBound <= variables.size(); iBound++) {
				double lower = MiniBucketLowerBound.compute(
					variables, factors, iBound, GENEROUS, () -> false).lowerBound();
				Assert.assertTrue("trial=" + trial + "|iBound=" + iBound
					+ "|lower=" + lower + "|optimum=" + optimum, lower <= optimum);
			}
		}
	}

	@Test
	public void widerBoundClosesKnownRelaxationGap() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			pairEquality(a, b, 10d), pairEquality(a, c, 10d),
			ExactCategoricalSolver.Factor.dense(List.of(b), 0d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(c), 4d, 0d));

		var loose = MiniBucketLowerBound.compute(variables, factors, 2, GENEROUS, () -> false);
		var exactWidth = MiniBucketLowerBound.compute(variables, factors, 3, GENEROUS, () -> false);
		Assert.assertEquals(0d, loose.lowerBound(), 0d);
		Assert.assertEquals(4d, exactWidth.lowerBound(), Math.ulp(4d) * 8);
		Assert.assertTrue(loose.lowerBound() < exactWidth.lowerBound());
		Assert.assertEquals(1, loose.statistics().splitBuckets());
	}

	@Test
	public void reportsDeterministicArgminDisagreementAndFreezesLists() {
		var producer = variable("producer", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(producer, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(producer, left), 0d, 0d, 5d, 5d),
			ExactCategoricalSolver.Factor.dense(List.of(producer, right), 5d, 5d, 0d, 0d));

		var result = MiniBucketLowerBound.compute(variables, factors, 2, GENEROUS, () -> false);
		Assert.assertEquals(1, result.conflicts().size());
		var conflict = result.conflicts().get(0);
		Assert.assertSame(producer, conflict.variable());
		Assert.assertEquals(variables, conflict.scope());
		Assert.assertEquals(1d, conflict.disagreement(), 0d);
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> result.conflicts().add(conflict));
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> conflict.scope().add(producer));
	}

	@Test
	public void handlesHardCostsConstantsIsolatedVariablesAndNativeWidthException() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var isolated = variable("isolated", 3);
		var feasible = MiniBucketLowerBound.compute(List.of(a, b, isolated), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(), 2d),
			ExactCategoricalSolver.Factor.dense(List.of(a, b),
				Double.POSITIVE_INFINITY, 3d, 4d, Double.POSITIVE_INFINITY)),
			1, GENEROUS, () -> false);
		Assert.assertEquals(5d, feasible.lowerBound(), Math.ulp(5d) * 4);
		Assert.assertEquals(4L, feasible.statistics().maximumFactorCells());

		var infeasible = MiniBucketLowerBound.compute(List.of(a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)),
			1, GENEROUS, () -> false);
		Assert.assertEquals(Double.POSITIVE_INFINITY, infeasible.lowerBound(), 0d);

		var empty = MiniBucketLowerBound.compute(List.of(isolated), List.of(),
			1, GENEROUS, () -> false);
		Assert.assertEquals(0d, empty.lowerBound(), 0d);
	}

	@Test
	public void downwardSummationDoesNotOverstateAdversarialFiniteSum() {
		var a = variable("a", 1);
		var result = MiniBucketLowerBound.compute(List.of(a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1d),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1d)),
			1, GENEROUS, () -> false);
		double mathematicalSum = 1.0e16 + 2d;
		Assert.assertTrue(result.lowerBound() <= mathematicalSum);
		Assert.assertTrue(result.lowerBound() >= 0d);
	}

	@Test
	public void rejectsInvalidCostsAndMalformedScopes() {
		var a = variable("a", 2);
		var equalButForeign = variable("a", 2);
		Assert.assertThrows(IllegalArgumentException.class, () -> MiniBucketLowerBound.compute(
			List.of(a), List.of(ExactCategoricalSolver.Factor.dense(List.of(equalButForeign), 0d, 0d)),
			1, GENEROUS, () -> false));
		Assert.assertThrows(IllegalArgumentException.class, () -> MiniBucketLowerBound.compute(
			List.of(a), List.of(ExactCategoricalSolver.Factor.dense(List.of(a, a), 0d, 0d, 0d, 0d)),
			2, GENEROUS, () -> false));
		Assert.assertThrows(IllegalArgumentException.class, () -> MiniBucketLowerBound.compute(
			List.of(a), List.of(ExactCategoricalSolver.Factor.dense(List.of(a), 0d)),
			1, GENEROUS, () -> false));
		for(double invalid : new double[] {-1d, -0d, Double.NEGATIVE_INFINITY, Double.NaN})
			Assert.assertThrows(IllegalArgumentException.class, () -> MiniBucketLowerBound.compute(
				List.of(a), List.of(ExactCategoricalSolver.Factor.dense(List.of(a), invalid, 0d)),
				1, GENEROUS, () -> false));
	}

	@Test
	public void resourceFailurePrecedesLazyEvaluation() {
		var a = variable("a", 10);
		var b = variable("b", 10);
		AtomicInteger evaluations = new AtomicInteger();
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});
		Assert.assertThrows(MiniBucketLowerBound.ResourceLimitException.class,
			() -> MiniBucketLowerBound.compute(List.of(a, b), List.of(factor), 2,
				new ExactCategoricalSolver.Limits(99, 1_000), () -> false));
		Assert.assertEquals(0, evaluations.get());
		Assert.assertThrows(MiniBucketLowerBound.ResourceLimitException.class,
			() -> MiniBucketLowerBound.compute(List.of(a, b), List.of(factor), 2,
				new ExactCategoricalSolver.Limits(100, 109), () -> false));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void malformedDenseInputPrecedesGeneratedMessageResourceFailure() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var malformed = ExactCategoricalSolver.Factor.dense(List.of(a), 0d);
		var pair = ExactCategoricalSolver.Factor.dense(List.of(a, b), 0d, 0d, 0d, 0d);
		RuntimeException error = Assert.assertThrows(RuntimeException.class,
			() -> MiniBucketLowerBound.compute(List.of(a, b), List.of(malformed, pair), 2,
				new ExactCategoricalSolver.Limits(4, 7), () -> false));
		Assert.assertFalse(error instanceof MiniBucketLowerBound.ResourceLimitException);
		Assert.assertEquals("EXACT_VE_DENSE_FACTOR_SIZE_MISMATCH", error.getMessage());
	}

	@Test
	public void partitionSplitsWhenMergedOutputWouldExceedFactorCellLimit() {
		var eliminated = variable("eliminated", 2);
		var left = variable("left", 10);
		var right = variable("right", 10);
		double[] zeros = new double[20];
		var result = MiniBucketLowerBound.compute(List.of(eliminated, left, right), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(eliminated, left), zeros),
			ExactCategoricalSolver.Factor.dense(List.of(eliminated, right), zeros)),
			3, new ExactCategoricalSolver.Limits(50, 100), () -> false);
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertEquals(1, result.statistics().splitBuckets());
		Assert.assertEquals(20L, result.statistics().maximumFactorCells());
	}

	@Test
	public void cancellationIsDistinctFromResourceFailureAndCheckedInsideCellLoops() {
		var a = variable("a", 20);
		AtomicInteger polls = new AtomicInteger();
		Assert.assertThrows(CancellationException.class, () -> MiniBucketLowerBound.compute(
			List.of(a), List.of(ExactCategoricalSolver.Factor.lazy(List.of(a), values -> 1d)),
			1, GENEROUS, () -> polls.incrementAndGet() > 8));
		Assert.assertTrue(polls.get() > 8);
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
			values[cell] = random.nextInt(21);
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static ExactCategoricalSolver.Factor randomHardFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		double[] values = new double[4];
		for(int cell = 0; cell < values.length; cell++)
			values[cell] = random.nextInt(7) == 0 ? Double.POSITIVE_INFINITY : random.nextInt(20);
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static ExactCategoricalSolver.Factor xPreference(
		List<ExactCategoricalSolver.Variable> scope, boolean preferOne, double penalty) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		int cellsPerX = cells / scope.get(0).domainSize();
		for(int cell = 0; cell < cells; cell++) {
			int x = cell / cellsPerX;
			values[cell] = x == (preferOne ? 1 : 0) ? 0d : penalty;
		}
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static double bruteForce(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		return enumerate(variables, factors, new int[variables.size()], 0);
	}

	private static double enumerate(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int[] assignment, int index) {
		if(index == variables.size()) {
			return CertifiedRegionalOptimizer.evaluate(variables, factors,
				Arrays.stream(assignment).boxed().toList());
		}
		double best = Double.POSITIVE_INFINITY;
		for(int value = 0; value < variables.get(index).domainSize(); value++) {
			assignment[index] = value;
			best = Math.min(best, enumerate(variables, factors, assignment, index + 1));
		}
		return best;
	}

	private static int identityIndex(List<ExactCategoricalSolver.Variable> variables,
		ExactCategoricalSolver.Variable target) {
		for(int index = 0; index < variables.size(); index++)
			if(variables.get(index) == target)
				return index;
		throw new AssertionError("foreign test variable");
	}
}
