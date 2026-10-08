/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactFusedKernelOwnerProjectionTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void exhaustiveAndFixedSeedRandomizedRawBitsMatchExplicitReference() {
		Random random = new Random(731_993L);
		for(int repetition = 0; repetition < 64; repetition++) {
			int owners = 8 + random.nextInt(9);
			int weights = 8 + random.nextInt(9);
			int[] classes = new int[owners];
			for(int owner = 0; owner < owners; owner++)
				classes[owner] = owner < 3 ? owner : random.nextInt(3);
			double[][] table = new double[3][weights];
			for(int category = 0; category < table.length; category++)
				for(int weight = 0; weight < weights; weight++)
					table[category][weight] = randomValue(random, category, weight);
			var owner = new ExactCategoricalSolver.Variable("owner-" + repetition, owners);
			var weight = new ExactCategoricalSolver.Variable("weight-" + repetition, weights);
			var explicit = ExactCategoricalSolver.Factor.lazy(List.of(owner, weight),
				values -> table[classes[values[0]]][values[1]]);
			var projected = ExactPhysicalCostModel.projectFusedKernelOwnerIfBeneficial(
				"fused-random-" + repetition, explicit, classes);
			Assert.assertNotNull(projected);
			var frozen = new IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor>();
			var prices = projected.ordinaryFactors().get(0);
			frozen.put(prices, ExactCategoricalSolver.freezeValidatedFactor(prices));
			var published = projected.canonicalFactorAfterFreeze(explicit, frozen);
			for(int o = 0; o < owners; o++)
				for(int w = 0; w < weights; w++)
					Assert.assertEquals(Double.doubleToRawLongBits(explicit.cost(new int[] {o, w})),
						Double.doubleToRawLongBits(published.cost(new int[] {o, w})));
		}
	}

	@Test
	public void callbackCellsShrinkAndFrozenPublishedViewIgnoresLaterMutation() {
		var owner = new ExactCategoricalSolver.Variable("owner", 12);
		var weight = new ExactCategoricalSolver.Variable("weight", 10);
		int[] classes = {0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2};
		AtomicInteger generation = new AtomicInteger(1);
		AtomicInteger callbacks = new AtomicInteger();
		var explicit = ExactCategoricalSolver.Factor.lazy(List.of(owner, weight), values -> {
			callbacks.incrementAndGet();
			return generation.get() * (classes[values[0]] * 100d + values[1]);
		});
		var projected = ExactPhysicalCostModel.projectFusedKernelOwnerIfBeneficial(
			"fused-snapshot", explicit, classes);
		Assert.assertNotNull(projected);
		Assert.assertEquals(0, callbacks.get());
		var prices = projected.ordinaryFactors().get(0);
		var frozen = new IdentityHashMap<ExactCategoricalSolver.Factor,ExactCategoricalSolver.Factor>();
		frozen.put(prices, ExactCategoricalSolver.freezeValidatedFactor(prices));
		Assert.assertEquals(30, callbacks.get());
		var published = projected.canonicalFactorAfterFreeze(explicit, frozen);
		generation.set(17);
		for(int o = 0; o < 12; o++)
			for(int w = 0; w < 10; w++)
				Assert.assertEquals(Double.doubleToRawLongBits(classes[o] * 100d + w),
					Double.doubleToRawLongBits(published.cost(new int[] {o, w})));
		Assert.assertEquals("published view must not re-enter the live callback", 30, callbacks.get());
	}

	@Test
	public void smallDomainFallsBackWhenProjectionWouldNotReduceCells() {
		var owner = new ExactCategoricalSolver.Variable("owner", 3);
		var weight = new ExactCategoricalSolver.Variable("weight", 2);
		var explicit = ExactCategoricalSolver.Factor.lazy(List.of(owner, weight), values -> 0d);
		Assert.assertNull(ExactPhysicalCostModel.projectFusedKernelOwnerIfBeneficial(
			"no-gain", explicit, new int[] {0, 1, 2}));
	}

	@Test
	public void representativeInvalidAndOverflowValuesFailDuringTheSameSnapshotPhase() {
		var owner = new ExactCategoricalSolver.Variable("owner-invalid", 12);
		var weight = new ExactCategoricalSolver.Variable("weight-invalid", 10);
		int[] classes = {0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2};
		for(double invalid : new double[] {-0d, Double.NaN, Double.NEGATIVE_INFINITY}) {
			var explicit = ExactCategoricalSolver.Factor.lazy(List.of(owner, weight), values ->
				classes[values[0]] == 1 && values[1] == 4 ? invalid : Double.MAX_VALUE);
			var projected = ExactPhysicalCostModel.projectFusedKernelOwnerIfBeneficial(
				"fused-invalid-" + Double.doubleToRawLongBits(invalid), explicit, classes);
			Assert.assertThrows(IllegalArgumentException.class, () ->
				ExactCategoricalSolver.freezeValidatedFactor(explicit));
			Assert.assertThrows(IllegalArgumentException.class, () ->
				ExactCategoricalSolver.freezeValidatedFactor(projected.ordinaryFactors().get(0)));
		}
		var forbidden = ExactCategoricalSolver.Factor.lazy(List.of(owner, weight), values ->
			classes[values[0]] == 1 && values[1] == 4 ? Double.POSITIVE_INFINITY : Double.MAX_VALUE);
		var projected = ExactPhysicalCostModel.projectFusedKernelOwnerIfBeneficial(
			"fused-forbidden", forbidden, classes);
		Assert.assertNotNull(ExactCategoricalSolver.freezeValidatedFactor(forbidden));
		Assert.assertNotNull(ExactCategoricalSolver.freezeValidatedFactor(projected.ordinaryFactors().get(0)));
	}

	@Test
	public void conditionalOptimumAndOriginalTieSelectionArePreserved() {
		var owner = new ExactCategoricalSolver.Variable("owner", 12);
		var weight = new ExactCategoricalSolver.Variable("weight", 9);
		int[] classes = {0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2};
		var explicit = ExactCategoricalSolver.Factor.lazy(List.of(owner, weight), values ->
			classes[values[0]] == values[1] % 3 ? 0d : 7d);
		var projected = ExactPhysicalCostModel.projectFusedKernelOwnerIfBeneficial("fused-tie", explicit, classes);
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(List.of(owner, weight));
		variables.addAll(projected.auxiliaryVariables());
		ExactCategoricalSolver.TieCostFunction tie = (variable, value) ->
			variable == owner ? 100L * value : variable == weight ? value : 0L;
		var expected = ExactCategoricalSolver.solve(List.of(owner, weight), List.of(explicit), LIMITS, tie);
		var actual = ExactCategoricalSolver.solve(variables, projected.factors(), LIMITS, tie);
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder().subList(0, 2));
	}

	@Test
	public void productionSmallFixtureUsesTheNoGainFallbackWithoutPublishingAnAuxiliary() throws Exception {
		var fixture = ExactFusedFactorReuseCostTest.fixture(false, false, true);
		var model = ExactPhysicalModel.build(fixture.analysis());
		var projections = ExactPhysicalCostModel.fusedKernelOwnerProjectionsForTest(model);
		Assert.assertTrue(projections.isEmpty());
		var surface = ExactPhysicalCostModel.physicalCostSurface(fixture.analysis(), model);
		Assert.assertEquals(0, surface.exactSolverVariables().stream()
			.filter(variable -> variable.key().startsWith("exact-fused-kernel-owner|")).count());
	}

	private static double randomValue(Random random, int category, int weight) {
		return switch((category * 17 + weight) % 8) {
			case 0 -> 0d;
			case 1 -> 0d;
			case 2 -> Double.MIN_VALUE;
			case 3 -> Math.nextDown(Double.MAX_VALUE / 4d);
			default -> random.nextDouble() * 1e6;
		};
	}
}
