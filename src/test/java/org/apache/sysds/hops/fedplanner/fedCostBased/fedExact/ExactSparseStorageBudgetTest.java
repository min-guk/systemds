/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactSparseStorageBudgetTest {
	@Test
	public void deferredDyadicProjectsRawSeparatorBeyondIntWithoutDenseAllocation()
		throws Exception {
		var z = new ExactCategoricalSolver.Variable("z", 2);
		var a = new ExactCategoricalSolver.Variable("a", 50_000);
		var b = new ExactCategoricalSolver.Variable("b", 50_000);
		double[] za = new double[100_000];
		double[] zb = new double[100_000];
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), za),
			ExactCategoricalSolver.Factor.dense(List.of(z, b), zb));
		var limits = new ExactCategoricalSolver.Limits(100_000, 200_010);

		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactCategoricalSolver.compilePreferred(List.of(z, a, b), factors, limits,
				List.of("z", "a", "b")));
		var compiled = ExactCategoricalSolver.compileDyadicPreferredForTest(
			List.of(z, a, b), factors, limits, List.of("z", "a", "b"));
		var solved = solveDeferredForTest(compiled, List.of(za, zb));

		Assert.assertEquals(0d, solved.objective(), 0d);
		Assert.assertEquals(List.of(0, 0, 0), solved.assignmentInVariableOrder());
		Assert.assertTrue(solved.statistics().maximumFactorCells() <= limits.maximumFactorCells());
		Assert.assertTrue(solved.statistics().materializedFactorCells()
			<= limits.maximumMaterializedCells());
	}

	@Test
	public void deferredDyadicRejectsNumericOnlyAuthority() {
		var x = new ExactCategoricalSolver.Variable("x", 2);
		double[] values = {0d, 1d};
		var compiled = ExactCategoricalSolver.compileDyadicPreferredForTest(List.of(x),
			List.of(ExactCategoricalSolver.Factor.dense(List.of(x), values)),
			new ExactCategoricalSolver.Limits(2, 4), List.of("x"));
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solveDyadic(compiled,
				ExactDyadicCosts.certifyTables(List.of(values))));
		Assert.assertEquals("EXACT_VE_DEFERRED_PHYSICAL_AUTHORITY_REQUIRED", failure.getMessage());
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(compiled));
	}

	@Test
	public void deferredProjectionMatchesStrictDyadicAssignmentAndRepresentative()
		throws Exception {
		var z = new ExactCategoricalSolver.Variable("z", 2);
		var a = new ExactCategoricalSolver.Variable("a", 3);
		var b = new ExactCategoricalSolver.Variable("b", 2);
		double[] za = {1d, 1d, 5d, 0d, 0d, 4d};
		double[] zb = {1d, 2d, 2d, 1d};
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), za),
			ExactCategoricalSolver.Factor.dense(List.of(z, b), zb));
		var variables = List.of(z, a, b);
		var limits = new ExactCategoricalSolver.Limits(20, 100);
		var order = List.of("z", "a", "b");
		var certificate = ExactDyadicCosts.certifyTables(List.of(za, zb));
		var strict = ExactCategoricalSolver.solveDyadic(
			ExactCategoricalSolver.compilePreferred(variables, factors, limits, order), certificate);
		var deferred = solveDeferredForTest(
			ExactCategoricalSolver.compileDyadicPreferredForTest(variables, factors, limits, order),
			List.of(za, zb));

		Assert.assertEquals(strict.objective(), deferred.objective(), 0d);
		Assert.assertEquals(strict.assignmentInVariableOrder(), deferred.assignmentInVariableOrder());
		Assert.assertEquals(0, deferred.assignmentInVariableOrder().get(1).intValue());
	}

	@Test
	public void sparseOutputStopsBeforePerFactorLimitGrowth() throws Exception {
		var fixture = selectiveCrossProduct(50, 200);
		IllegalArgumentException failure = invokeFailure(fixture.compiled, fixture.tables);
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=51|limit=50"));
	}

	@Test
	public void sparseOutputStopsBeforeCumulativeLimitGrowth() throws Exception {
		var fixture = selectiveCrossProduct(100, 70);
		IllegalArgumentException failure = invokeFailure(fixture.compiled, fixture.tables);
		Assert.assertTrue(failure.getMessage(), failure.getMessage().startsWith(
			"EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED|cells=71|limit=70"));
	}

	@Test
	public void sparseStorageDoesNotCrossOverToOversizedDenseMessage() throws Exception {
		var z = new ExactCategoricalSolver.Variable("z", 1);
		var a = new ExactCategoricalSolver.Variable("a", 200);
		var b = new ExactCategoricalSolver.Variable("b", 200);
		double[] za = selective(200, 10);
		double[] zb = selective(200, 20);
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), za),
			ExactCategoricalSolver.Factor.dense(List.of(z, b), zb));
		var limits = new ExactCategoricalSolver.Limits(200, 1_000);
		var compiled = ExactCategoricalSolver.compileDyadicPreferredForTest(
			List.of(z, a, b), factors, limits, List.of("z", "a", "b"));

		var solved = solveDeferredForTest(compiled, List.of(za, zb));
		Assert.assertEquals(200, solved.statistics().maximumFactorCells());
		Assert.assertTrue(solved.statistics().materializedFactorCells() <= 1_000);
	}

	private static Fixture selectiveCrossProduct(long maximumFactorCells,
		long maximumMaterializedCells) {
		var z = new ExactCategoricalSolver.Variable("z", 1);
		var a = new ExactCategoricalSolver.Variable("a", 20);
		var b = new ExactCategoricalSolver.Variable("b", 20);
		double[] za = selective(20, 9);
		double[] zb = selective(20, 9);
		var factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), za),
			ExactCategoricalSolver.Factor.dense(List.of(z, b), zb));
		var compiled = ExactCategoricalSolver.compileDyadicPreferredForTest(
			List.of(z, a, b), factors,
			new ExactCategoricalSolver.Limits(maximumFactorCells, maximumMaterializedCells),
			List.of("z", "a", "b"));
		return new Fixture(compiled, List.of(za, zb));
	}

	private static double[] selective(int domain, int finite) {
		double[] values = new double[domain];
		Arrays.fill(values, Double.POSITIVE_INFINITY);
		for(int value = 0; value < finite; value++)
			values[value] = value + 1d;
		return values;
	}

	private static IllegalArgumentException invokeFailure(
		ExactCategoricalSolver.CompiledProblem compiled, List<double[]> tables) throws Exception {
		InvocationTargetException failure = Assert.assertThrows(InvocationTargetException.class,
			() -> invokeDeferred(compiled, tables));
		Assert.assertTrue(failure.getCause().toString(),
			failure.getCause() instanceof IllegalArgumentException);
		return (IllegalArgumentException)failure.getCause();
	}

	private static ExactCategoricalSolver.Result solveDeferredForTest(
		ExactCategoricalSolver.CompiledProblem compiled, List<double[]> tables) throws Exception {
		return (ExactCategoricalSolver.Result)invokeDeferred(compiled, tables);
	}

	private static Object invokeDeferred(ExactCategoricalSolver.CompiledProblem compiled,
		List<double[]> tables) throws Exception {
		Field prepared = compiled.getClass().getDeclaredField("prepared");
		Field factors = compiled.getClass().getDeclaredField("factors");
		prepared.setAccessible(true);
		factors.setAccessible(true);
		Method solve = Arrays.stream(ExactCategoricalSolver.class.getDeclaredMethods())
			.filter(method -> method.getName().equals("solve") && method.getParameterCount() == 5)
			.findFirst().orElseThrow();
		solve.setAccessible(true);
		return solve.invoke(null, prepared.get(compiled), factors.get(compiled), null, null,
			ExactDyadicCosts.certifyTables(tables));
	}

	private record Fixture(ExactCategoricalSolver.CompiledProblem compiled,
		List<double[]> tables) { }
}
