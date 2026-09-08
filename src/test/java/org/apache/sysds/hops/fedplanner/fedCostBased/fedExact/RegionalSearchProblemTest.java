/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class RegionalSearchProblemTest {
	private static final Limits LIMITS = new Limits(10000, 100000);

	@Test
	public void conditionRetainsConstantsAndSnapshotsBranchValues() {
		Variable x = new Variable("x", 2), y = new Variable("y", 2);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x, y), List.of(
			Factor.dense(List.of(), 7), Factor.dense(List.of(x), 2, 5), Factor.dense(List.of(y), 9, 3)));
		int[] fixed = {1, -1};
		RegionalSearchProblem.Conditional conditional = problem.condition(fixed);
		fixed[0] = 0;
		Assert.assertEquals(3, conditional.factors().size());
		Assert.assertEquals(1, conditional.originalFreeCount());
		Assert.assertEquals(1, conditional.fixed()[0]);
		Assert.assertEquals(15, ExactCategoricalSolver.solve(conditional.variables(), conditional.factors(), LIMITS).objective(), 0);
		Assert.assertEquals(15, problem.solveWhole(new int[] {1, -1}, LIMITS, () -> false).objective(), 0);
		Assert.assertTrue(problem.bound(new int[] {1, -1}, 1, LIMITS, () -> false).lowerBound() <= 15);
	}

	@Test
	public void regionalInfeasibilityDoesNotMeanConditionalGlobalInfeasibility() {
		Variable x = new Variable("x", 2), y = new Variable("y", 2);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x, y), List.of(
			Factor.dense(List.of(x, y), 10, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 5)));
		int[] branch = {1, -1};
		Assert.assertFalse(problem.solveRegion(branch, Set.of(0), List.of(0, 0), LIMITS, () -> false).feasible());
		RegionalSearchProblem.Solution full = problem.solveWhole(branch, LIMITS, () -> false);
		Assert.assertTrue(full.feasible());
		Assert.assertEquals(List.of(1, 1), full.assignment());
		Assert.assertEquals(5, full.objective(), 0);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> problem.solveRegion(branch, Set.of(1), List.of(0, 0), LIMITS, () -> false));
	}

	@Test
	public void numericalBoundaryCannotCertifyUsingRoundedThresholdProduct() {
		double lower = 100, upper = 101;
		Assert.assertTrue(RegionalSearchOptimizer.relative(lower, upper) > 0.01);
		Assert.assertEquals(Double.POSITIVE_INFINITY, RegionalSearchOptimizer.relative(0, 1), 0);
		Assert.assertEquals(0, RegionalSearchOptimizer.relative(0, 0), 0);
		Assert.assertTrue(RegionalSearchOptimizer.relative(lower, Math.nextDown(upper)) <= 0.01);
	}

	@Test
	public void workPreflightIncludesIntermediateTablesAndCancellationPrecedesSolve() {
		Variable x = new Variable("x", 2), y = new Variable("y", 2), z = new Variable("z", 2);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x, y, z), List.of(
			Factor.dense(List.of(x, y, z), 1, 2, 3, 4, 5, 6, 7, 8)));
		Assert.assertFalse(problem.canSolveWhole(problem.unconstrained(), new Limits(4, 100), 100));
		Assert.assertFalse(problem.canSolveWhole(problem.unconstrained(), LIMITS, 1));
		Assert.assertTrue(problem.canSolveWhole(problem.unconstrained(), LIMITS, 100));
		Assert.assertThrows(java.util.concurrent.CancellationException.class,
			() -> problem.solveWhole(problem.unconstrained(), LIMITS, () -> true));
	}

	@Test
	public void configurationRejectsMixedModesAndUnknownAlgorithms() {
		String key = CertifiedRegionalOptimizer.PROPERTY_PREFIX + "algorithm";
		String previous = System.getProperty(key);
		try {
			System.setProperty(key, "legacy");
			Assert.assertNull(RegionalSearchOptimizer.Options.configured(null));
			System.setProperty(key, "reuse");
			Assert.assertThrows(IllegalArgumentException.class, () -> RegionalSearchOptimizer.Options.configured(null));
			System.setProperty(key, "typo");
			Assert.assertThrows(IllegalArgumentException.class, () -> RegionalSearchOptimizer.Options.configured(null));
		}
		finally {
			if(previous == null)
				System.clearProperty(key);
			else
				System.setProperty(key, previous);
		}
	}
}
