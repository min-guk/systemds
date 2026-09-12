/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class CostShiftLowerBoundTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void joinGraphOwnsEveryOriginalOnceAndLinksNativeWidthSplitClusters() {
		var x = variable("x", 2);
		var a = variable("a", 3);
		var b = variable("b", 3);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, a), new double[6]),
			ExactCategoricalSolver.Factor.dense(List.of(b, x), new double[6]),
			ExactCategoricalSolver.Factor.dense(List.of(), 2d));
		var graph = MiniBucketLowerBound.joinGraphPlan(List.of(x, a, b), factors, 1,
			GENEROUS, () -> false);
		int[] ownership = new int[factors.size()];
		for(MiniBucketLowerBound.JoinCluster cluster : graph.clusters())
			for(int factor : cluster.originalFactors())
				ownership[factor]++;
		Assert.assertArrayEquals(new int[] {1, 1, 1}, ownership);
		Assert.assertTrue(graph.edges().stream().anyMatch(edge -> !edge.generatedMessage()
			&& Arrays.equals(new int[] {0}, edge.separator())));
	}

	@Test
	public void splitClusterShiftClosesOpposingPreferenceGapAndPublishesMonotonically() {
		var x = variable("x", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<Double> published = new ArrayList<>();
		var result = CostShiftLowerBound.compute(List.of(x, left, right), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, left), 0d, 0d, 100d, 100d),
			ExactCategoricalSolver.Factor.dense(List.of(x, right), 100d, 100d, 0d, 0d)),
			1, GENEROUS, Long.MAX_VALUE, 4, 1_000_000L, () -> false,
			bound -> false, published::add);

		Assert.assertEquals(100d, result.lowerBound(), 1e-10);
		Assert.assertTrue(result.statistics().updates() > 0);
		for(int index = 1; index < published.size(); index++)
			Assert.assertTrue(published.get(index) >= published.get(index - 1));
	}

	@Test
	public void randomHardModelsStayBelowExhaustiveOracle() {
		Random random = new Random(240991L);
		for(int trial = 0; trial < 50; trial++) {
			var a = variable("a" + trial, 2);
			var b = variable("b" + trial, 2);
			var c = variable("c" + trial, 2);
			List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
			List<ExactCategoricalSolver.Factor> factors = List.of(
				randomFactor(random, List.of(a, b)), randomFactor(random, List.of(a, c)),
				randomFactor(random, List.of(b, c)),
				ExactCategoricalSolver.Factor.dense(List.of(), random.nextInt(4)));
			double optimum = bruteForce(variables, factors);
			var result = CostShiftLowerBound.compute(variables, factors, 1, GENEROUS,
				Long.MAX_VALUE, 8, 2_000_000L, () -> false, bound -> false, bound -> { });
			Assert.assertTrue("trial=" + trial + "|bound=" + result.lowerBound()
				+ "|optimum=" + optimum, result.lowerBound() <= optimum);
		}
	}

	@Test
	public void preservesInfinityConstantsScopeOrderAndOriginalFactors() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		double[] callerValues = {7d, Double.POSITIVE_INFINITY, 3d, 11d};
		var permuted = ExactCategoricalSolver.Factor.dense(List.of(b, a), callerValues);
		var constant = ExactCategoricalSolver.Factor.dense(List.of(), 5d);
		var result = CostShiftLowerBound.compute(List.of(a, b), List.of(permuted, constant),
			1, GENEROUS, Long.MAX_VALUE, 5, 1_000_000L, () -> false,
			bound -> false, bound -> { });
		Assert.assertTrue(result.lowerBound() <= 8d);
		Assert.assertArrayEquals(new double[] {7d, Double.POSITIVE_INFINITY, 3d, 11d},
			callerValues, 0d);

		var infeasible = CostShiftLowerBound.compute(List.of(a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), 1, GENEROUS,
			Long.MAX_VALUE, 2, 100_000L, () -> false, bound -> false, bound -> { });
		Assert.assertEquals(Double.POSITIVE_INFINITY, infeasible.lowerBound(), 0d);

		var mixed = CostShiftLowerBound.compute(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b),
				Double.POSITIVE_INFINITY, 0d, 4d, Double.POSITIVE_INFINITY),
			ExactCategoricalSolver.Factor.dense(List.of(a), 3d, 0d)), 1, GENEROUS,
			Long.MAX_VALUE, 4, 1_000_000L, () -> false, bound -> false, bound -> { });
		Assert.assertFalse(Double.isNaN(mixed.lowerBound()));
		Assert.assertTrue(mixed.lowerBound() <= 3d);
	}

	@Test
	public void cancellationDuringPairLeavesLastCompleteBoundAndNoPartialCallback() {
		var x = variable("x", 40);
		var left = variable("left", 40);
		var right = variable("right", 40);
		double[] first = new double[1_600];
		double[] second = new double[1_600];
		for(int cell = 0; cell < first.length; cell++) {
			first[cell] = cell / 40;
			second[cell] = 39 - cell / 40;
		}
		AtomicInteger polls = new AtomicInteger();
		AtomicInteger callbacks = new AtomicInteger();
		AtomicBoolean armed = new AtomicBoolean();
		var result = CostShiftLowerBound.compute(List.of(x, left, right), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, left), first),
			ExactCategoricalSolver.Factor.dense(List.of(x, right), second)),
			1, GENEROUS, Long.MAX_VALUE, 10, 10_000_000L,
			() -> armed.get() && polls.incrementAndGet() > 100, bound -> false,
			bound -> { callbacks.incrementAndGet(); armed.set(true); });
		Assert.assertEquals(CostShiftLowerBound.Reason.CANCELLED, result.reason());
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertEquals(1, callbacks.get());
		Assert.assertTrue(result.statistics().cancelled());
	}

	@Test
	public void reportsFiniteResourceAndWorkStops() {
		var a = variable("a", 10);
		var b = variable("b", 10);
		var factor = ExactCategoricalSolver.Factor.dense(List.of(a, b), new double[100]);
		var resource = CostShiftLowerBound.compute(List.of(a, b), List.of(factor), 2,
			new ExactCategoricalSolver.Limits(100, 109), Long.MAX_VALUE, 2, 10_000L,
			() -> false, bound -> false, bound -> { });
		Assert.assertEquals(CostShiftLowerBound.Reason.RESOURCE_LIMIT, resource.reason());
		Assert.assertTrue(resource.statistics().resourceLimited());

		var work = CostShiftLowerBound.compute(List.of(a, b), List.of(factor), 2,
			GENEROUS, Long.MAX_VALUE, 2, 10L, () -> false, bound -> false, bound -> { });
		Assert.assertEquals(CostShiftLowerBound.Reason.WORK_LIMIT, work.reason());
		Assert.assertTrue(work.statistics().resourceLimited());
	}

	@Test
	public void negativeShiftsRemainSoundAcrossLargeAndSmallScales() {
		var x = variable("x", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, left), 1e-200, 1e-200, 1e100, 1e100),
			ExactCategoricalSolver.Factor.dense(List.of(x, right), 1e100, 1e100, 1e-200, 1e-200));
		double optimum = bruteForce(variables, factors);
		var result = CostShiftLowerBound.compute(variables, factors, 1, GENEROUS,
			Long.MAX_VALUE, 10, 1_000_000L, () -> false, bound -> false, bound -> { });
		Assert.assertTrue(result.lowerBound() <= optimum);
		Assert.assertTrue(result.lowerBound() > 0d);
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		double[] values = new double[4];
		for(int cell = 0; cell < values.length; cell++)
			values[cell] = random.nextInt(8) == 0 ? Double.POSITIVE_INFINITY : random.nextInt(30);
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static double bruteForce(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		return enumerate(variables, factors, new int[variables.size()], 0);
	}

	private static double enumerate(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int[] assignment, int index) {
		if(index < variables.size()) {
			double best = Double.POSITIVE_INFINITY;
			for(int value = 0; value < variables.get(index).domainSize(); value++) {
				assignment[index] = value;
				best = Math.min(best, enumerate(variables, factors, assignment, index + 1));
			}
			return best;
		}
		List<Integer> values = Arrays.stream(assignment).boxed().toList();
		return CertifiedRegionalOptimizer.evaluate(variables, factors, values);
	}

}
