/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Explicit zero callbacks retain the original dense path as an independent enumeration oracle. */
public class ExactFiniteRowIndexTest {
	private static final Limits LIMITS = new Limits(1_000_000L, 10_000_000L);
	private static final double INF = Double.POSITIVE_INFINITY;

	@Test
	public void indexEnumeratesOnlyFiniteValuesAcrossWordAndStrideBoundaries() throws Exception {
		Class<?> dense = Class.forName(ExactCategoricalSolver.class.getName() + "$DenseFactor");
		var constructor = dense.getDeclaredConstructor(int[].class, int[].class,
			double[].class, double[].class, long[].class);
		constructor.setAccessible(true);
		Class<?> index = Class.forName(ExactCategoricalSolver.class.getName() + "$FiniteRowIndex");
		Method create = index.getDeclaredMethod("create", dense, int.class, int.class);
		Method offset = index.getDeclaredMethod("rowOffset", int.class);
		Method next = index.getDeclaredMethod("next", int.class, int.class);
		create.setAccessible(true);
		offset.setAccessible(true);
		next.setAccessible(true);
		int[] domains = {2, 129, 3};
		int[] strides = {387, 3, 1};
		double[] values = new double[774];
		for(int cell = 0; cell < values.length; cell++)
			values[cell] = cell % 11 == 0 || cell % 17 == 0 ? cell - 100d : INF;
		Object factor = constructor.newInstance(new int[] {0, 1, 2}, domains, values, null, null);
		for(int variable = 0; variable < domains.length; variable++) {
			Object rows = create.invoke(null, factor, variable, domains[variable]);
			Assert.assertNotNull(rows);
			for(int base = 0; base < values.length; base++) {
				if(base / strides[variable] % domains[variable] != 0)
					continue;
				List<Integer> expected = new ArrayList<>();
				for(int value = 0; value < domains[variable]; value++)
					if(values[base + value * strides[variable]] != INF)
						expected.add(value);
				List<Integer> actual = new ArrayList<>();
				int row = (int)offset.invoke(rows, base);
				for(int value = (int)next.invoke(rows, row, 0); value < domains[variable];
					value = (int)next.invoke(rows, row, value + 1))
					actual.add(value);
				Assert.assertEquals(expected, actual);
			}
		}
		Object allFinite = constructor.newInstance(new int[] {0, 1, 2}, domains,
			new double[values.length], null, null);
		Assert.assertNull(create.invoke(null, allFinite, 1, domains[1]));
	}

	@Test
	public void everyAxisAndReversedScopePreserveRawResultAndStatistics() throws Exception {
		List<Variable> variables = List.of(new Variable("axis-a", 3),
			new Variable("axis-b", 129), new Variable("axis-c", 2));
		for(int first = 0; first < variables.size(); first++) {
			for(boolean reverse : List.of(false, true)) {
				List<Variable> scope = new ArrayList<>(variables);
				if(reverse)
					Collections.reverse(scope);
				List<Factor> factors = List.of(Factor.lazy(scope, values -> {
					int checksum = 0;
					for(int value : values)
						checksum += value;
					return checksum % 7 == 0 ? -checksum * 0.125d : INF;
				}), Factor.lazy(List.of(variables.get(1)), values ->
					Math.scalb(values[0] - 65d, -45)));
				List<String> order = new ArrayList<>();
				order.add(variables.get(first).key());
				for(Variable variable : variables)
					if(!order.contains(variable.key()))
						order.add(variable.key());
				assertCompiledParity(variables, factors, order);
			}
		}
	}

	@Test
	public void randomSparseRowsMatchDenseOracleIncludingResidualTies() throws Exception {
		Random random = new Random(0xF1A17E05L);
		for(int trial = 0; trial < 160; trial++) {
			List<Variable> variables = new ArrayList<>();
			for(int index = 0; index < 1 + trial % 4; index++)
				variables.add(new Variable("random-" + index, 1 + random.nextInt(5)));
			List<Factor> factors = new ArrayList<>();
			for(int index = 0; index < 2 + trial % 5; index++) {
				List<Variable> scope = new ArrayList<>(variables);
				Collections.shuffle(scope, random);
				scope = new ArrayList<>(scope.subList(0, 1 + random.nextInt(scope.size())));
				int cells = scope.stream().mapToInt(Variable::domainSize).reduce(1, (a, b) -> a * b);
				double[] values = new double[cells];
				for(int cell = 0; cell < cells; cell++)
					values[cell] = cell != 0 && random.nextInt(4) != 0 ? INF
						: random.nextInt(7) - 3d + Math.scalb(random.nextInt(5), -53);
				factors.add(Factor.dense(scope, values));
			}
			factors.add(Factor.dense(List.of(), trial % 2 == 0 ? 1e16 : Double.MIN_VALUE));
			assertPublicParity(variables, factors);
			List<String> order = new ArrayList<>(variables.stream().map(Variable::key).toList());
			Collections.shuffle(order, random);
			assertCompiledParity(variables, factors, order);
		}
	}

	@Test
	public void singletonEmptyBucketForbiddenRowsAndAuxiliaryMapsMatch() throws Exception {
		Variable a = new Variable("map-source", 5);
		Variable b = new Variable("map-observation", 3);
		Variable singleton = new Variable("singleton", 1);
		Variable isolated = new Variable("isolated", 3);
		List<Variable> variables = List.of(a, b, singleton, isolated);
		List<Factor> factors = List.of(
			Factor.lazy(List.of(a, b), v -> v[1] == v[0] % 3 ? 0d : INF),
			Factor.dense(List.of(b), INF, 0d, -1d),
			Factor.dense(List.of(singleton), Double.MIN_VALUE),
			Factor.dense(List.of(), -Double.MIN_VALUE));
		assertPublicParity(variables, factors);
		assertCompiledParity(variables, factors, List.of(a.key(), b.key(), singleton.key(), isolated.key()));
		assertPublicParity(List.of(isolated), List.of());
		assertPublicParity(List.of(a), List.of(Factor.dense(List.of(a), INF, INF, INF, INF, INF)));
	}

	@Test
	public void orderedOverflowIsNotHiddenByALaterForbiddenFactor() throws Exception {
		Variable a = new Variable("overflow", 2);
		Factor large = Factor.dense(List.of(a), Double.MAX_VALUE, 0d);
		Factor forbidden = Factor.dense(List.of(a), INF, 1d);
		assertPublicParity(List.of(a), List.of(large, large, forbidden));
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW", failure(
			() -> ExactCategoricalSolver.solve(List.of(a), List.of(large, large, forbidden), LIMITS)));
		assertPublicParity(List.of(a), List.of(forbidden, large, large));
		assertPublicParity(List.of(a), List.of(Factor.dense(List.of(a), INF, -0d)));
		assertPublicParity(List.of(a), List.of(
			Factor.dense(List.of(a), INF, 1e16), Factor.dense(List.of(a), 0d, 1d),
			Factor.dense(List.of(a), 0d, -1e16), Factor.dense(List.of(a), 0d, Double.MIN_VALUE)));
	}

	@Test
	public void callbackFailureAtAnInfiniteValueRemainsObservable() {
		Variable a = new Variable("callback", 2);
		AtomicInteger calls = new AtomicInteger();
		Assert.assertEquals("test-first-tie-failure", failure(() -> ExactCategoricalSolver.solve(
			List.of(a), List.of(Factor.dense(List.of(a), INF, 0d)), LIMITS, (variable, value) -> {
				calls.incrementAndGet();
				throw new IllegalArgumentException("test-first-tie-failure");
			})));
		Assert.assertEquals(1, calls.get());
	}

	@Test
	public void denseCapAndLazyValidationStillPrecedeSkipping() {
		Variable a = new Variable("cap-a", 3);
		Variable b = new Variable("cap-b", 3);
		AtomicInteger calls = new AtomicInteger();
		Factor sparse = Factor.lazy(List.of(a, b), values -> {
			calls.incrementAndGet();
			return values[0] == values[1] ? 0d : INF;
		});
		String defaultError = failure(() -> ExactCategoricalSolver.solve(
			List.of(a, b), List.of(sparse), new Limits(4, 100)));
		String denseError = failure(() -> ExactCategoricalSolver.solve(
			List.of(a, b), List.of(sparse), new Limits(4, 100), (v, x) -> 0L));
		Assert.assertEquals(denseError, defaultError);
		Assert.assertTrue(defaultError, defaultError.startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		Assert.assertEquals(0, calls.get());
		List<String> order = new ArrayList<>();
		String validation = failure(() -> ExactCategoricalSolver.solve(List.of(a), List.of(
			Factor.lazy(List.of(a), values -> { order.add("first-" + values[0]); return INF; }),
			Factor.lazy(List.of(a), values -> { order.add("second-" + values[0]); return Double.NaN; })), LIMITS));
		Assert.assertTrue(validation, validation.startsWith("EXACT_VE_FACTOR_COST_INVALID"));
		Assert.assertEquals(List.of("first-0", "first-1", "first-2", "second-0"), order);
	}

	private static void assertPublicParity(List<Variable> variables, List<Factor> factors) throws Exception {
		assertParity(() -> ExactCategoricalSolver.solve(variables, factors, LIMITS),
			() -> ExactCategoricalSolver.solve(variables, factors, LIMITS, (variable, value) -> 0L));
	}

	private static void assertCompiledParity(List<Variable> variables, List<Factor> factors,
		List<String> order) throws Exception {
		var compiled = ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS, order);
		Field field = compiled.getClass().getDeclaredField("prepared");
		field.setAccessible(true);
		Object prepared = field.get(compiled);
		Method solve = ExactCategoricalSolver.class.getDeclaredMethod("solve", prepared.getClass(),
			List.class, ExactCategoricalSolver.TieCostFunction.class);
		solve.setAccessible(true);
		assertParity(() -> ExactCategoricalSolver.solve(compiled), () -> {
			try {
				return (Result)solve.invoke(null, prepared, factors,
					(ExactCategoricalSolver.TieCostFunction)(variable, value) -> 0L);
			}
			catch(InvocationTargetException error) {
				throw (RuntimeException)error.getCause();
			}
		});
	}

	private static void assertParity(Solve indexed, Solve dense) throws Exception {
		Result expected;
		try {
			expected = dense.solve();
		}
		catch(IllegalArgumentException error) {
			Assert.assertEquals(error.getMessage(), failure(indexed));
			return;
		}
		Result actual = indexed.solve();
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(expected.statistics(), actual.statistics());
	}

	private static String failure(Solve solve) {
		try {
			solve.solve();
			throw new AssertionError("Expected failure");
		}
		catch(IllegalArgumentException error) {
			return error.getMessage();
		}
		catch(Exception error) {
			throw new AssertionError(error);
		}
	}

	@FunctionalInterface
	private interface Solve { Result solve() throws Exception; }
}
