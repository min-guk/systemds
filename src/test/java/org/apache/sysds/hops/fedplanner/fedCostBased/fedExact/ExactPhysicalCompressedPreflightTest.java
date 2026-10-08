/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/** Cost preflight contract for compressed hard support and bounded monetary tables. */
public class ExactPhysicalCompressedPreflightTest {
	@Test
	public void conceptualHardProductDoesNotBlockBoundedOrdinaryFreeze() {
		var left = new ExactCategoricalSolver.Variable("left", 1_500);
		var middle = new ExactCategoricalSolver.Variable("middle", 1_500);
		var right = new ExactCategoricalSolver.Variable("right", 1_500);
		var choice = new ExactCategoricalSolver.Variable("choice", 3);
		AtomicInteger hardEvaluations = new AtomicInteger();
		AtomicInteger ordinaryEvaluations = new AtomicInteger();
		var compressedHard = ExactCategoricalSolver.Factor.lazy(
			List.of(left, middle, right), values -> {
				hardEvaluations.incrementAndGet();
				return values[0] == values[1] && values[1] == values[2]
					? 0d : Double.POSITIVE_INFINITY;
			});
		var ordinary = ExactCategoricalSolver.Factor.lazy(List.of(choice), values -> {
			ordinaryEvaluations.incrementAndGet();
			return values[0] * 0.25d;
		});

		List<ExactCategoricalSolver.Factor> frozen =
			ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(
				List.of(left, middle, right, choice), List.of(compressedHard),
				List.of(ordinary), List.of(ordinary),
				new ExactCategoricalSolver.Limits(10_000, 100_000));

		Assert.assertEquals("preflight must not expand symbolic hard support", 0, hardEvaluations.get());
		Assert.assertEquals(3, ordinaryEvaluations.get());
		Assert.assertEquals(1, frozen.size());
		for(int value = 0; value < 3; value++)
			Assert.assertEquals(Double.doubleToRawLongBits(value * 0.25d),
				Double.doubleToRawLongBits(frozen.get(0).cost(new int[] {value})));
	}

	@Test
	public void oversizedOrdinaryTableFailsBeforeAnyEvaluatorOrObserver() {
		var left = new ExactCategoricalSolver.Variable("ordinary-left", 50_000);
		var right = new ExactCategoricalSolver.Variable("ordinary-right", 50_000);
		AtomicInteger evaluations = new AtomicInteger();
		var ordinary = ExactCategoricalSolver.Factor.lazy(List.of(left, right), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(
				List.of(left, right), List.of(), List.of(ordinary), List.of(ordinary),
				new ExactCategoricalSolver.Limits(Long.MAX_VALUE, Long.MAX_VALUE)));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_FACTOR_CELL_OVERFLOW|factor="));
		Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("|arity=2|"));
		Assert.assertTrue(failure.getMessage(), failure.getMessage().contains("|representation=LAZY"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void combinedStoredHardAndOrdinaryBudgetFailsBeforeEvaluation() {
		var hardValue = new ExactCategoricalSolver.Variable("hard-value", 3);
		var pricedValue = new ExactCategoricalSolver.Variable("priced-value", 3);
		var hard = ExactCategoricalSolver.Factor.dense(
			List.of(hardValue), 0d, Double.POSITIVE_INFINITY, 0d);
		AtomicInteger evaluations = new AtomicInteger();
		var ordinary = ExactCategoricalSolver.Factor.lazy(List.of(pricedValue), values -> {
			evaluations.incrementAndGet();
			return values[0];
		});

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(
				List.of(hardValue, pricedValue), List.of(hard), List.of(ordinary), List.of(ordinary),
				new ExactCategoricalSolver.Limits(4, 5)));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_MATERIALIZED_LIMIT_EXCEEDED"));
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void malformedDenseCostFailsBeforeOrdinaryEvaluation() {
		var hardValue = new ExactCategoricalSolver.Variable("malformed-hard", 2);
		var pricedValue = new ExactCategoricalSolver.Variable("malformed-price", 2);
		var malformed = ExactCategoricalSolver.Factor.dense(
			List.of(hardValue), 0d, Double.NaN);
		AtomicInteger evaluations = new AtomicInteger();
		var ordinary = ExactCategoricalSolver.Factor.lazy(List.of(pricedValue), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactPhysicalCostModel.freezeOrdinaryFactorsAfterPreflight(
				List.of(hardValue, pricedValue), List.of(malformed),
				List.of(ordinary), List.of(ordinary),
				new ExactCategoricalSolver.Limits(10, 10)));
		Assert.assertTrue(failure.getMessage(),
			failure.getMessage().startsWith("EXACT_VE_FACTOR_COST_INVALID"));
		Assert.assertEquals(0, evaluations.get());
	}
}
