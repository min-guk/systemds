/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/** Preserve canonical callback and validation order while removing repeated cell division. */
public class ExactFactorFreezeOrderTest {
	@Test
	public void sharedInputFreezePreservesFactorTupleOrderAndRawBits() {
		var a = new ExactCategoricalSolver.Variable("a", 2);
		var b = new ExactCategoricalSolver.Variable("b", 3);
		var singleton = new ExactCategoricalSolver.Variable("singleton", 1);
		List<String> visits = new ArrayList<>();
		var factors = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(), values -> {
				visits.add("constant" + Arrays.toString(values));
				return Double.longBitsToDouble(1L);
			}),
			ExactCategoricalSolver.Factor.lazy(List.of(b, singleton, a), values -> {
				visits.add("reversed" + Arrays.toString(values));
				int cell = values[0] * 2 + values[2];
				return cell == 5 ? Double.POSITIVE_INFINITY : cell;
			}),
			ExactCategoricalSolver.Factor.dense(List.of(a), 0d, 7d));
		var frozen = ExactCategoricalSolver.freezeInputs(List.of(a, b, singleton), factors,
			new ExactCategoricalSolver.Limits(6, 9));
		Assert.assertEquals(1L, Double.doubleToRawLongBits(frozen.values(0)[0]));
		Assert.assertEquals("constant[]", visits.get(0));
		Assert.assertArrayEquals(new int[] {1, 2, 0}, frozen.scope(1));
		for(int cell = 0; cell < 6; cell++) {
			Assert.assertEquals("reversed" + Arrays.toString(new int[] {cell / 2, 0, cell % 2}),
				visits.get(cell + 1));
			Assert.assertEquals(Double.doubleToRawLongBits(cell == 5 ? Double.POSITIVE_INFINITY : cell),
				Double.doubleToRawLongBits(frozen.values(1)[cell]));
		}
		Assert.assertEquals(7, visits.size());
		Assert.assertArrayEquals(new double[] {0d, 7d}, frozen.values(2), 0d);
	}

	@Test
	public void sharedInputFreezeValidatesEveryFactorBeforeTheFirstCallback() {
		var a = new ExactCategoricalSolver.Variable("a", 2);
		var b = new ExactCategoricalSolver.Variable("b", 3);
		AtomicInteger calls = new AtomicInteger();
		var factors = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(a), values -> { calls.incrementAndGet(); return 0; }),
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> { calls.incrementAndGet(); return 0; }));
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.freezeInputs(
			List.of(a, b), factors, new ExactCategoricalSolver.Limits(5, 8)));
		Assert.assertEquals(0, calls.get());
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.freezeInputs(
			List.of(a, b), factors, new ExactCategoricalSolver.Limits(6, 7)));
		Assert.assertEquals(0, calls.get());
	}

	@Test
	public void callbacksVisitEveryTupleInOriginalMixedRadixOrder() {
		var a = new ExactCategoricalSolver.Variable("a", 3);
		var b = new ExactCategoricalSolver.Variable("singleton", 1);
		var c = new ExactCategoricalSolver.Variable("c", 5);
		List<String> visits = new ArrayList<>();
		var frozen = ExactCategoricalSolver.freezeValidatedFactor(ExactCategoricalSolver.Factor.lazy(
			List.of(a, b, c), values -> {
				visits.add(Arrays.toString(values));
				return values[0] * 5 + values[2];
			}));
		for(int cell = 0; cell < 15; cell++) {
			Assert.assertEquals(Arrays.toString(new int[] {cell / 5, 0, cell % 5}), visits.get(cell));
			Assert.assertEquals(cell, frozen.denseCostAt(cell), 0d);
		}
		Assert.assertEquals(15, visits.size());
	}

	@Test
	public void constantAndDenseFactorsKeepTheirOriginalContracts() {
		AtomicInteger calls = new AtomicInteger();
		var constant = ExactCategoricalSolver.freezeValidatedFactor(ExactCategoricalSolver.Factor.lazy(
			List.of(), values -> { Assert.assertEquals(0, values.length); calls.incrementAndGet(); return 7d; }));
		Assert.assertEquals(1, calls.get());
		Assert.assertEquals(7d, constant.denseCostAt(0), 0d);
		Assert.assertSame(constant, ExactCategoricalSolver.freezeValidatedFactor(constant));
	}

	@Test
	public void invalidCostFailsBeforeTheNextCallback() {
		AtomicInteger calls = new AtomicInteger();
		var variable = new ExactCategoricalSolver.Variable("invalid", 4);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactCategoricalSolver.freezeValidatedFactor(ExactCategoricalSolver.Factor.lazy(
				List.of(variable), values -> { calls.incrementAndGet(); return values[0] == 2 ? -0d : 1d; })));
		Assert.assertTrue(error.getMessage().startsWith("EXACT_VE_FACTOR_COST_INVALID"));
		Assert.assertEquals(3, calls.get());
	}
}
