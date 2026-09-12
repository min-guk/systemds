/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.junit.Test;

public class RegionDualLowerBoundTest {
	private static final RegionDualLowerBound.Options GENEROUS =
		new RegionDualLowerBound.Options(false, 1_000_000L, 20_000_000L,
			1_000_000L, 20, 20_000_000L, Long.MAX_VALUE);

	@Test
	public void frustratedCycleConsistencyClosesLooseFactorMinima() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 0d, 10d, 0d, 10d),
			ExactCategoricalSolver.Factor.dense(List.of(b, c), 10d, 10d, 0d, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(c, a), 0d, 1d, 1d, 0d));
		List<Double> progress = new ArrayList<>();
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS,
			() -> false, bound -> false, progress::add);

		Assert.assertEquals(10d, bruteForce(variables, factors), 0d);
		Assert.assertEquals(10d, result.lowerBound(), 1e-10);
		Assert.assertTrue(result.statistics().updates() > 0);
		for(int index = 1; index < progress.size(); index++)
			Assert.assertTrue(progress.get(index) >= progress.get(index - 1));
	}

	@Test
	public void propagatesUnsupportedSeparatorTuplesWithoutDroppingFeasibleAssignments() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 0d, Double.POSITIVE_INFINITY),
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 10d, 20d, 0d, 0d));
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS, () -> false);
		Assert.assertEquals(10d, bruteForce(variables, factors), 0d);
		Assert.assertEquals(10d, result.lowerBound(), 1e-10);
		Assert.assertTrue(result.statistics().updates() > 0);
		Assert.assertEquals(0d, result.prepared().clusters().get(0).costs()[2], 0d);
	}

	@Test
	public void everyRemovedSeparatorIsImpliedByARetainedSupersetPath() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		var d = variable("d", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b, c), ramp(8, 0d)),
			ExactCategoricalSolver.Factor.dense(List.of(a, b, d), ramp(8, 1d)),
			ExactCategoricalSolver.Factor.dense(List.of(a, b), ramp(4, 2d)),
			ExactCategoricalSolver.Factor.dense(List.of(a), 0d, 1d));
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS, () -> false);
		var prepared = result.prepared();
		Assert.assertEquals(3, prepared.separators().size());
		for(int left = 0; left < prepared.clusters().size(); left++) {
			for(int right = left + 1; right < prepared.clusters().size(); right++) {
				int[] rightScope = prepared.clusters().get(right).scope();
				int[] scope = Arrays.stream(prepared.clusters().get(left).scope())
					.filter(v -> Arrays.binarySearch(rightScope, v) >= 0).toArray();
				boolean[] reached = new boolean[prepared.clusters().size()];
				reached[left] = true;
				for(int pass = 0; pass < reached.length; pass++) {
					for(var edge : prepared.separators()) {
						if(Arrays.stream(scope).allMatch(v -> Arrays.binarySearch(edge.scope(), v) >= 0)
							&& (reached[edge.leftCluster()] || reached[edge.rightCluster()])) {
							reached[edge.leftCluster()] = true;
							reached[edge.rightCluster()] = true;
						}
					}
				}
				Assert.assertTrue("Missing implied equality path", reached[right]);
			}
		}
		Assert.assertTrue(result.lowerBound() <= bruteForce(variables, factors));
	}

	@Test
	public void chainedHardSupportsProveInfeasibility() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		double inf = Double.POSITIVE_INFINITY;
		var variables = List.of(a, b, c);
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 0d, inf, inf, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(b, c), 0d, inf, inf, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(a), 0d, inf),
			ExactCategoricalSolver.Factor.dense(List.of(c), inf, 0d));
		Assert.assertEquals(inf, bruteForce(variables, factors), 0d);
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS, () -> false);
		Assert.assertEquals(inf, result.lowerBound(), 0d);
	}

	@Test
	public void cachedProjectionHandlesUnequalDomainsAndMultiVariableSeparator() {
		var a = variable("a", 2);
		var b = variable("b", 3);
		var privateLeft = variable("left", 2);
		var privateRight = variable("right", 3);
		var variables = List.of(a, b, privateLeft, privateRight);
		Random random = new Random(6138L);
		double[] first = new double[12];
		double[] second = new double[18];
		for(int cell = 0; cell < first.length; cell++)
			first[cell] = random.nextInt(20);
		for(int cell = 0; cell < second.length; cell++)
			second[cell] = random.nextInt(20);
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(b, privateLeft, a), first),
			ExactCategoricalSolver.Factor.dense(List.of(privateRight, a, b), second));
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS, () -> false);
		double optimum = bruteForce(variables, factors);
		Assert.assertTrue(result.lowerBound() <= optimum);
		Assert.assertEquals(optimum, result.lowerBound(), 1e-10);
	}

	@Test
	public void randomHardModelsStayBelowExhaustiveOracle() {
		Random random = new Random(985421L);
		for(int trial = 0; trial < 60; trial++) {
			var a = variable("a" + trial, 2);
			var b = variable("b" + trial, 2);
			var c = variable("c" + trial, 2);
			List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
			List<ExactCategoricalSolver.Factor> factors = List.of(
				randomFactor(random, List.of(b, a)), randomFactor(random, List.of(c, a)),
				randomFactor(random, List.of(b, c)),
				ExactCategoricalSolver.Factor.dense(List.of(), random.nextInt(5)));
			double optimum = bruteForce(variables, factors);
			for(boolean merge : List.of(false, true)) {
				var options = new RegionDualLowerBound.Options(merge, 64L, 10_000L,
					64L, 12, 1_000_000L, Long.MAX_VALUE);
				var result = RegionDualLowerBound.compute(variables, factors, options, () -> false);
				Assert.assertTrue("trial=" + trial + "|merge=" + merge + "|bound="
					+ result.lowerBound() + "|optimum=" + optimum,
					result.lowerBound() <= optimum);
			}
		}
	}

	@Test
	public void floatingBoundsStayBelowCompensatedCanonicalOracle() {
		var x = variable("x", 2);
		var y = variable("y", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y);
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int index = 0; index < 48; index++)
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(), 0.1d));
		for(int index = 0; index < 6; index++) {
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(x), 0d, 1e100));
			factors.add(ExactCategoricalSolver.Factor.dense(List.of(x), 1e100, 0d));
		}
		factors.add(ExactCategoricalSolver.Factor.dense(List.of(x, y),
			Double.MIN_VALUE, 1e-200, 1e-100, 0.3d));
		double optimum = bruteForce(variables, factors);
		List<Double> progress = new ArrayList<>();
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS,
			() -> false, bound -> false, progress::add);
		for(double bound : progress)
			Assert.assertTrue("bound=" + bound + "|oracle=" + optimum, bound <= optimum);
		Assert.assertTrue(result.lowerBound() <= optimum);
	}

	@Test
	public void preservesInfinityPermutedScopesAndInfeasibility() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		double[] callerValues = {7d, Double.POSITIVE_INFINITY, 3d, 11d};
		var result = RegionDualLowerBound.compute(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(b, a), callerValues),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1d, 0d)), GENEROUS,
			() -> false);
		Assert.assertTrue(result.lowerBound() <= 4d);
		Assert.assertFalse(Double.isNaN(result.lowerBound()));
		Assert.assertArrayEquals(new double[] {7d, Double.POSITIVE_INFINITY, 3d, 11d},
			callerValues, 0d);

		var infeasible = RegionDualLowerBound.compute(List.of(a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), GENEROUS, () -> false);
		Assert.assertEquals(Double.POSITIVE_INFINITY, infeasible.lowerBound(), 0d);
	}

	@Test
	public void ownsEveryFactorOnceAndUsesFullIntersections() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		var unit = variable("unit", 1);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b, unit), ramp(4, 0d)),
			ExactCategoricalSolver.Factor.dense(List.of(b, a, c), ramp(8, 2d)),
			ExactCategoricalSolver.Factor.dense(List.of(c), 7d, 7d),
			ExactCategoricalSolver.Factor.dense(List.of(), 3d));
		var result = RegionDualLowerBound.compute(List.of(a, b, c, unit), factors,
			GENEROUS, () -> false);
		var prepared = result.prepared();
		int[] ownership = new int[factors.size()];
		for(var cluster : prepared.clusters())
			for(int factor : cluster.originalFactors())
				ownership[factor]++;
		Assert.assertArrayEquals(new int[] {1, 1, 1, 1}, ownership);
		Assert.assertTrue(prepared.separators().stream()
			.anyMatch(edge -> Arrays.equals(new int[] {0, 1}, edge.scope())));
		Assert.assertTrue(prepared.clusters().stream()
			.noneMatch(cluster -> Arrays.stream(cluster.scope()).anyMatch(index -> index == 3)));
		Assert.assertTrue(prepared.clusters().stream()
			.anyMatch(cluster -> cluster.scope().length == 0
				&& cluster.originalFactors().length == 2));
	}

	@Test
	public void aggregationIsExplicitAndCanStrengthenWithoutLosingOwnership() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var c = variable("c", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b), ramp(4, 0d)),
			ExactCategoricalSolver.Factor.dense(List.of(b, c), ramp(4, 4d)));
		var options = new RegionDualLowerBound.Options(true, 8L, 1_000L, 8L,
			2, 100_000L, Long.MAX_VALUE);
		var result = RegionDualLowerBound.compute(List.of(a, b, c), factors, options,
			() -> false);
		Assert.assertTrue(result.prepared().mergedFactors());
		Assert.assertEquals(1, result.prepared().clusters().size());
		Assert.assertArrayEquals(new int[] {0, 1},
			result.prepared().clusters().get(0).originalFactors());
		Assert.assertEquals(bruteForce(List.of(a, b, c), factors), result.lowerBound(), 1e-10);
	}

	@Test
	public void eliminatesClusterPrivateVariablesOnlyFromIterativeTables() {
		var privateLeft = variable("private-left", 3);
		var shared = variable("shared", 2);
		var privateRight = variable("private-right", 4);
		List<ExactCategoricalSolver.Variable> variables =
			List.of(privateLeft, shared, privateRight);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(privateLeft, shared),
				0d, 100d, 50d, 20d, 80d, 40d),
			ExactCategoricalSolver.Factor.dense(List.of(shared, privateRight),
				100d, 100d, 100d, 100d, 0d, 0d, 0d, 0d));
		var result = RegionDualLowerBound.compute(variables, factors, GENEROUS, () -> false);

		Assert.assertArrayEquals(new int[] {0, 1},
			result.prepared().clusters().get(0).scope());
		Assert.assertArrayEquals(new int[] {1, 2},
			result.prepared().clusters().get(1).scope());
		Assert.assertEquals(14L, result.prepared().clusters().stream()
			.mapToLong(cluster -> cluster.costs().length).sum());
		Assert.assertEquals(4L, result.statistics().iterativeCells());
		Assert.assertEquals(2L, result.statistics().maximumIterativeClusterCells());
		Assert.assertEquals(bruteForce(variables, factors), result.lowerBound(), 1e-10);
	}

	@Test
	public void cancellationAndConstructionLimitsFailSafeToTrivialBound() {
		var a = variable("a", 10);
		var b = variable("b", 10);
		var factor = ExactCategoricalSolver.Factor.dense(List.of(a, b), new double[100]);
		var cancelled = RegionDualLowerBound.compute(List.of(a, b), List.of(factor),
			GENEROUS, () -> true);
		Assert.assertEquals(RegionDualLowerBound.Reason.CANCELLED, cancelled.reason());
		Assert.assertEquals(0d, cancelled.lowerBound(), 0d);
		Assert.assertNull(cancelled.prepared());

		var workOptions = new RegionDualLowerBound.Options(false, 100L, 1_000L, 100L,
			2, 10L, Long.MAX_VALUE);
		var work = RegionDualLowerBound.compute(List.of(a, b), List.of(factor),
			workOptions, () -> false);
		Assert.assertEquals(RegionDualLowerBound.Reason.WORK_LIMIT, work.reason());
		Assert.assertEquals(0d, work.lowerBound(), 0d);
		Assert.assertNull(work.prepared());

		var resourceOptions = new RegionDualLowerBound.Options(false, 99L, 1_000L, 100L,
			2, 1_000L, Long.MAX_VALUE);
		var resource = RegionDualLowerBound.compute(List.of(a, b), List.of(factor),
			resourceOptions, () -> false);
		Assert.assertEquals(RegionDualLowerBound.Reason.RESOURCE_LIMIT, resource.reason());
		Assert.assertEquals(0d, resource.lowerBound(), 0d);

		var allocationOptions = new RegionDualLowerBound.Options(false, 100L, 99L, 100L,
			2, 1_000L, Long.MAX_VALUE);
		var allocation = RegionDualLowerBound.compute(List.of(a, b), List.of(factor),
			allocationOptions, () -> false);
		Assert.assertEquals(RegionDualLowerBound.Reason.RESOURCE_LIMIT, allocation.reason());
		Assert.assertEquals(0d, allocation.lowerBound(), 0d);
		Assert.assertNull(allocation.prepared());
	}

	@Test
	public void cancellationDuringUpdateKeepsLastPublishedCompleteBound() {
		var x = variable("x", 30);
		var left = variable("left", 30);
		var right = variable("right", 30);
		double[] first = new double[900];
		double[] second = new double[900];
		for(int cell = 0; cell < 900; cell++) {
			first[cell] = cell / 30;
			second[cell] = 29 - cell / 30;
		}
		AtomicBoolean armed = new AtomicBoolean();
		int[] polls = {0};
		var result = RegionDualLowerBound.compute(List.of(x, left, right), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x, left), first),
			ExactCategoricalSolver.Factor.dense(List.of(x, right), second)), GENEROUS,
			() -> armed.get() && ++polls[0] > 10, bound -> false, bound -> armed.set(true));
		Assert.assertEquals(RegionDualLowerBound.Reason.CANCELLED, result.reason());
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertNotNull(result.prepared());
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static double[] ramp(int size, double offset) {
		double[] values = new double[size];
		for(int index = 0; index < size; index++)
			values[index] = offset + index;
		return values;
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		double[] values = new double[4];
		for(int cell = 0; cell < values.length; cell++)
			values[cell] = random.nextInt(7) == 0 ? Double.POSITIVE_INFINITY : random.nextInt(30);
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
		return CertifiedRegionalOptimizer.evaluate(variables, factors,
			Arrays.stream(assignment).boxed().toList());
	}
}
