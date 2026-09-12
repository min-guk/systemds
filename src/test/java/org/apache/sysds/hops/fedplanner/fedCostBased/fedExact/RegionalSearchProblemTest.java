/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigDecimal;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class RegionalSearchProblemTest {
	private static final Limits LIMITS = new Limits(10000, 100000);

	@Test
	public void evaluationRetainsConstantsAndCompensatedCost() {
		Variable x = new Variable("x", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(), 1e16),
			Factor.dense(List.of(x), 1, 3), Factor.dense(List.of(), 1));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x), factors);
		double expected = new BigDecimal(1e16).add(BigDecimal.ONE).add(BigDecimal.ONE).doubleValue();
		Assert.assertEquals(Double.doubleToRawLongBits(expected),
			Double.doubleToRawLongBits(problem.evaluate(List.of(0))));
		Assert.assertEquals(expected, RegionalSearchProblem.evaluateFactors(List.of(x), factors, List.of(0)), 0);
	}

	@Test
	public void hardInfeasibilityIsNotAFeasiblePlan() {
		Variable x = new Variable("x", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(x), Double.POSITIVE_INFINITY, 7));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x), factors);
		Assert.assertEquals(Double.POSITIVE_INFINITY,
			RegionalSearchProblem.evaluateFactors(List.of(x), factors, List.of(0)), 0);
		Assert.assertThrows(IllegalArgumentException.class, () -> problem.evaluate(List.of(0)));
		Assert.assertEquals(7, problem.evaluate(List.of(1)), 0);
	}

	@Test
	public void modelAndAssignmentsRetainIdentityValidation() {
		Variable x = new Variable("x", 2), foreign = new Variable("x", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(x), 1, 2));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x), factors);
		Assert.assertThrows(IllegalArgumentException.class, () -> problem.evaluate(List.of()));
		Assert.assertThrows(IllegalArgumentException.class, () -> problem.evaluate(List.of(2)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> RegionalSearchProblem.generic(List.of(x, foreign), factors));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> RegionalSearchProblem.generic(List.of(foreign), factors));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> RegionalSearchProblem.evaluateFactors(List.of(foreign), factors, List.of(0)));
	}

	@Test
	public void invalidLazyCostsRemainErrors() {
		Variable x = new Variable("x", 2);
		for(double cost : new double[] {Double.NaN, -1, Double.NEGATIVE_INFINITY}) {
			List<Factor> factors = List.of(Factor.lazy(List.of(x), values -> cost));
			Assert.assertThrows(IllegalArgumentException.class,
				() -> RegionalSearchProblem.generic(List.of(x), factors).evaluate(List.of(0)));
		}
	}

	@Test
	public void sharedRootReusesTablesOnlyWithinTheSameLimits() {
		Variable x = new Variable("x", 2);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x),
			List.of(Factor.dense(List.of(x), 1, 2)));
		var root = problem.reducedRoot(LIMITS);
		Assert.assertSame(root, problem.reducedRoot(LIMITS));
		Assert.assertEquals(2, problem.reducedRootRequests());
		Assert.assertTrue(problem.reducedRootNanos() >= 0);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> problem.reducedRoot(new Limits(20000, 100000)));
	}

	@Test
	public void resourceClassificationDoesNotHideModelErrors() {
		Assert.assertTrue(RegionalSearchProblem.isResourceLimit(
			new IllegalArgumentException("EXACT_VE_FACTOR_LIMIT_EXCEEDED|cells=100")));
		Assert.assertFalse(RegionalSearchProblem.isResourceLimit(
			new IllegalArgumentException("REGIONAL_SEARCH_FACTOR_SCOPE_INVALID")));
		Assert.assertFalse(RegionalSearchProblem.isResourceLimit(new IllegalArgumentException()));
	}

	@Test
	public void roundedThresholdProductCannotProduceFalseCertification() {
		Assert.assertTrue(IncrementalRegionalOptimizer.relativeGap(100, 101) > 0.01);
		Assert.assertTrue(IncrementalRegionalOptimizer.relativeGap(100, Math.nextDown(101d)) <= 0.01);
		Assert.assertEquals(Double.POSITIVE_INFINITY, IncrementalRegionalOptimizer.relativeGap(0, 1), 0);
		Assert.assertEquals(0, IncrementalRegionalOptimizer.relativeGap(0, 0), 0);
	}
}
