/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Error-boundary regressions for optional certified cost-bound preparation. */
public class CertifiedCostPruningFailureTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(1_000_000L, 10_000_000L);

	@Test
	public void localOrdinaryOptionalBoundAllocationSkipsOnlyTheOptimization() {
		var x = variable("local-resource-x", 2);
		var leaves = ExactCategoricalSolver.boundaryLeaves(List.of(x), List.of(
			factor(x, 3d, 4d), factor(x, 7d, 9d)), LIMITS);
		var failure = allocationFailure("regional-cost-bounds", 3);
		try(var mocked = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			mocked.when(() -> PlannerResourceGuard.allocateDoubles(3, "regional-cost-bounds"))
				.thenThrow(failure);
			var legacy = merge(leaves, List.of(), ExactCategoricalSolver.CostPruningMode.LEGACY,
				new ExactCategoricalSolver.BoundaryMergeCounters());
			var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
			var suffix = merge(leaves, List.of(), ExactCategoricalSolver.CostPruningMode.SUFFIX, counters);

			assertMessageParity(legacy, suffix, List.of(x));
			Assert.assertEquals(10d, suffix.minimum(), 0d);
			assertSkippedOptimization(counters);
		}
	}

	@Test
	public void localSupportOptionalBoundAllocationSkipsOnlyTheOptimization() {
		var boundary = variable("local-resource-boundary", 12);
		var internal = variable("local-resource-internal", 8);
		List<ExactCategoricalSolver.Variable> variables = List.of(boundary, internal);
		double[] support = new double[96];
		double[] first = new double[96];
		double[] second = new double[96];
		for(int row = 0; row < 12; row++)
			for(int value = 0; value < 8; value++) {
				int cell = row * 8 + value;
				support[cell] = value < 2 ? 0d : Double.POSITIVE_INFINITY;
				first[cell] = value == 0 ? 3d : value == 1 ? 4d : 100d;
				second[cell] = value == 0 ? 7d : value == 1 ? 9d : 100d;
			}
		var leaves = ExactCategoricalSolver.boundaryLeaves(variables, List.of(
			ExactCategoricalSolver.Factor.dense(variables, support),
			ExactCategoricalSolver.Factor.dense(variables, first),
			ExactCategoricalSolver.Factor.dense(variables, second)), LIMITS);
		var failure = allocationFailure("regional-cost-bounds", 4);
		try(var mocked = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			mocked.when(() -> PlannerResourceGuard.allocateDoubles(4, "regional-cost-bounds"))
				.thenThrow(failure);
			var legacy = merge(leaves, List.of(boundary), ExactCategoricalSolver.CostPruningMode.LEGACY,
				new ExactCategoricalSolver.BoundaryMergeCounters());
			var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
			var suffix = merge(leaves, List.of(boundary), ExactCategoricalSolver.CostPruningMode.SUFFIX,
				counters);

			assertMessageParity(legacy, suffix, variables);
			Assert.assertTrue("support path must remain active after skipping the optional bound",
				counters.supportCellsExamined() > 0);
			assertSkippedOptimization(counters);
		}
	}

	@Test
	public void globalOrdinaryOptionalBoundAllocationSkipsOnlyTheOptimization() {
		var x = variable("global-resource-x", 2);
		var compiled = compile(List.of(x), List.of(factor(x, 3d, 4d), factor(x, 7d, 9d)));
		var failure = allocationFailure("exact-cost-bounds", 3);
		try(var mocked = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			mocked.when(() -> PlannerResourceGuard.allocateDoubles(3, "exact-cost-bounds"))
				.thenThrow(failure);
			GlobalRun legacy = solve(compiled, null, ExactCategoricalSolver.CostPruningMode.LEGACY);
			GlobalRun suffix = solve(compiled, null, ExactCategoricalSolver.CostPruningMode.SUFFIX);

			assertRunParity(legacy, suffix);
			Assert.assertEquals(10d, suffix.result().objective(), 0d);
			assertSkippedOptimization(suffix.counters());
		}
	}

	@Test
	public void globalDyadicSecondBoundAllocationSkipsOnlyTheOptimization() {
		var x = variable("global-dyadic-resource-x", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			factor(x, 3d, 4d), factor(x, 7d, 9d));
		var compiled = compile(List.of(x), factors);
		var certificate = ExactDyadicCosts.certifyTables(List.of(
			new double[] {3d, 4d}, new double[] {7d, 9d}));
		Assert.assertTrue(certificate.reason(), certificate.supported());
		var failure = allocationFailure("exact-cost-bound-words", 3);
		try(var mocked = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			mocked.when(() -> PlannerResourceGuard.allocateDoubles(3, "exact-cost-bound-words"))
				.thenThrow(failure);
			GlobalRun legacy = solve(compiled, certificate, ExactCategoricalSolver.CostPruningMode.LEGACY);
			GlobalRun suffix = solve(compiled, certificate, ExactCategoricalSolver.CostPruningMode.SUFFIX);

			assertRunParity(legacy, suffix);
			Assert.assertEquals(10d, suffix.result().objective(), 0d);
			assertSkippedOptimization(suffix.counters());
		}
	}

	@Test
	public void isolatedAndFactorlessBucketsKeepCounterEnabledSolveEquivalent() {
		var x = variable("isolated-x", 2);
		var y = variable("connected-y", 2);
		var isolated = ExactCategoricalSolver.compilePreferred(List.of(x, y),
			List.of(factor(y, 0d, 1d)), LIMITS, List.of(x.key(), y.key()));
		assertCounterSolveParity(isolated);

		var factorless = ExactCategoricalSolver.compilePreferred(List.of(x), List.of(), LIMITS,
			List.of(x.key()));
		assertCounterSolveParity(factorless);
	}

	@Test
	public void essentialAllocationFailureStillPropagates() {
		var x = variable("essential-resource-x", 2);
		var compiled = compile(List.of(x), List.of(factor(x, 3d, 4d), factor(x, 7d, 9d)));
		var failure = allocationFailure("exact-numeric", 1);
		try(var mocked = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			mocked.when(() -> PlannerResourceGuard.allocateDoubles(
				Mockito.anyInt(), Mockito.eq("exact-numeric"))).thenThrow(failure);
			Assert.assertSame(failure, Assert.assertThrows(
				PlannerResourceGuard.ResourceExhaustedException.class,
				() -> solve(compiled, null, ExactCategoricalSolver.CostPruningMode.SUFFIX)));
		}
	}

	@Test
	public void optionalBoundArithmeticFailureStillPropagates() {
		var x = variable("arithmetic-resource-x", 2);
		var compiled = compile(List.of(x), List.of(factor(x, 3d, 4d), factor(x, 7d, 9d)));
		ArithmeticException failure = new ArithmeticException("injected bound arithmetic failure");
		try(var mocked = Mockito.mockStatic(PlannerResourceGuard.class, Mockito.CALLS_REAL_METHODS)) {
			mocked.when(() -> PlannerResourceGuard.allocateDoubles(3, "exact-cost-bounds"))
				.thenThrow(failure);
			Assert.assertSame(failure, Assert.assertThrows(ArithmeticException.class,
				() -> solve(compiled, null, ExactCategoricalSolver.CostPruningMode.SUFFIX)));
		}
	}

	private static void assertCounterSolveParity(ExactCategoricalSolver.CompiledProblem compiled) {
		var expected = ExactCategoricalSolver.solve(compiled);
		GlobalRun actual = solve(compiled, null, ExactCategoricalSolver.CostPruningMode.SUFFIX);
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.result().objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(),
			actual.result().assignmentInVariableOrder());
	}

	private static void assertSkippedOptimization(
		ExactCategoricalSolver.BoundaryMergeCounters counters) {
		Assert.assertEquals(1L, counters.resourceSkippedCostBuckets());
		Assert.assertTrue("failed optional-bound preparation must still be timed",
			counters.boundPreparationNanos() > 0L);
	}

	private static PlannerResourceGuard.ResourceExhaustedException allocationFailure(
		String phase, int length) {
		return PlannerResourceGuard.allocationFailure(phase, (long)length * Double.BYTES, "double[]",
			new OutOfMemoryError("injected optional allocation failure"));
	}

	private static ExactCategoricalSolver.BoundaryMessage merge(
		List<ExactCategoricalSolver.BoundaryMessage> inputs,
		List<ExactCategoricalSolver.Variable> boundary, ExactCategoricalSolver.CostPruningMode mode,
		ExactCategoricalSolver.BoundaryMergeCounters counters) {
		return ExactCategoricalSolver.mergeBoundaryWithPruningForTest(
			inputs, boundary, LIMITS, mode, counters);
	}

	private static ExactCategoricalSolver.CompiledProblem compile(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		return ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS,
			variables.stream().map(ExactCategoricalSolver.Variable::key).toList());
	}

	private static GlobalRun solve(ExactCategoricalSolver.CompiledProblem compiled,
		ExactDyadicCosts.Certificate certificate, ExactCategoricalSolver.CostPruningMode mode) {
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
		List<ExactCategoricalSolver.EliminationSnapshot> steps = new ArrayList<>();
		var result = ExactCategoricalSolver.solveWithPruningForTest(
			compiled, null, certificate, mode, counters, steps::add);
		return new GlobalRun(result, counters, steps);
	}

	private static void assertRunParity(GlobalRun expected, GlobalRun actual) {
		Assert.assertEquals(Double.doubleToRawLongBits(expected.result().objective()),
			Double.doubleToRawLongBits(actual.result().objective()));
		Assert.assertEquals(expected.result().assignmentInVariableOrder(),
			actual.result().assignmentInVariableOrder());
		Assert.assertEquals(expected.steps().size(), actual.steps().size());
		for(int index = 0; index < expected.steps().size(); index++) {
			var left = expected.steps().get(index);
			var right = actual.steps().get(index);
			Assert.assertEquals(left.variable(), right.variable());
			Assert.assertArrayEquals(left.scope(), right.scope());
			Assert.assertArrayEquals(left.choices(), right.choices());
			assertRaw(left.high(), right.high());
			assertRaw(left.low(), right.low());
		}
	}

	private static void assertMessageParity(ExactCategoricalSolver.BoundaryMessage expected,
		ExactCategoricalSolver.BoundaryMessage actual,
		List<ExactCategoricalSolver.Variable> variables) {
		Assert.assertEquals(expected.scope(), actual.scope());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.minimum()),
			Double.doubleToRawLongBits(actual.minimum()));
		Assert.assertEquals(Double.doubleToRawLongBits(expected.lowerBound()),
			Double.doubleToRawLongBits(actual.lowerBound()));
		for(ExactCategoricalSolver.Variable variable : expected.scope()) {
			assertRaw(expected.minMarginals(variable), actual.minMarginals(variable));
			assertRaw(expected.lowerMinMarginals(variable), actual.lowerMinMarginals(variable));
		}
		int[] expectedAssignment = new int[variables.size()];
		int[] actualAssignment = new int[variables.size()];
		expected.decodeInto(expectedAssignment, variables);
		actual.decodeInto(actualAssignment, variables);
		Assert.assertArrayEquals(expectedAssignment, actualAssignment);
	}

	private static void assertRaw(double[] expected, double[] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for(int index = 0; index < expected.length; index++)
			Assert.assertEquals("cell=" + index, Double.doubleToRawLongBits(expected[index]),
				Double.doubleToRawLongBits(actual[index]));
	}

	private static ExactCategoricalSolver.Factor factor(
		ExactCategoricalSolver.Variable variable, double... values) {
		return ExactCategoricalSolver.Factor.dense(List.of(variable), values);
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private record GlobalRun(ExactCategoricalSolver.Result result,
		ExactCategoricalSolver.BoundaryMergeCounters counters,
		List<ExactCategoricalSolver.EliminationSnapshot> steps) { }
}
