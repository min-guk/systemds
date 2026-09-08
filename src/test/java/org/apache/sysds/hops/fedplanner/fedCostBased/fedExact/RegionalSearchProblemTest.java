/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

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
	public void reducedPreflightAdmitsCliqueRejectedByRawAnalysisAndSharesPreparedSolve() {
		List<Variable> variables = List.of(new Variable("a", 8), new Variable("b", 8),
			new Variable("c", 8), new Variable("d", 8));
		AtomicInteger evaluations = new AtomicInteger();
		List<Factor> factors = new java.util.ArrayList<>();
		for(int left = 0; left < variables.size(); left++)
			for(int right = left + 1; right < variables.size(); right++)
				factors.add(Factor.lazy(List.of(variables.get(left), variables.get(right)), values -> {
					evaluations.incrementAndGet();
					return 0d;
				}));
		Limits small = new Limits(100, 2_000);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.analyze(variables, factors, small));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);

		RegionalSearchProblem.RegionalWork work = problem.preflightWhole(
			problem.unconstrained(), small, 100, () -> false);
		int afterPreflight = evaluations.get();
		RegionalSearchProblem.Solution solution = problem.solveWhole(
			problem.unconstrained(), small, () -> false);

		Assert.assertTrue(work.admitted());
		Assert.assertFalse(work.hardResourceLimited());
		Assert.assertTrue(work.preparationNanos() > 0);
		Assert.assertTrue(work.maximumFactorCells() <= 100);
		Assert.assertEquals("solve should only perform the required final canonical evaluation",
			afterPreflight + factors.size(), evaluations.get());
		Assert.assertEquals(List.of(0, 0, 0, 0), solution.assignment());
		Assert.assertEquals(Double.doubleToRawLongBits(0d),
			Double.doubleToRawLongBits(solution.objective()));
	}

	@Test
	public void unrelatedPreflightInvalidatesPendingPreparedCondition() {
		Variable x = new Variable("x", 2);
		AtomicInteger generation = new AtomicInteger();
		AtomicInteger evaluations = new AtomicInteger();
		Factor changing = Factor.lazy(List.of(x), values -> {
			evaluations.incrementAndGet();
			return values[0] == generation.get() ? 0d : 1d;
		});
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x), List.of(changing));

		Assert.assertTrue(problem.preflightWhole(new int[] {-1}, LIMITS, 100, () -> false).admitted());
		generation.set(1);
		Assert.assertTrue(problem.preflightWhole(new int[] {1}, LIMITS, 100, () -> false).admitted());
		int beforeSolve = evaluations.get();
		RegionalSearchProblem.Solution refreshed = problem.solveWhole(
			new int[] {-1}, LIMITS, () -> false);

		Assert.assertTrue(evaluations.get() > beforeSolve);
		Assert.assertEquals(List.of(1), refreshed.assignment());
	}

	@Test
	public void preparedInfeasibilityAndFreeAuxiliaryReconstructionRemainExact()
		throws ReflectiveOperationException {
		Variable decision = new Variable("decision", 2), auxiliary = new Variable("aux", 2);
		RegionalSearchProblem infeasible = RegionalSearchProblem.generic(List.of(decision), List.of(
			Factor.dense(List.of(decision), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)));
		RegionalSearchProblem.RegionalWork empty = infeasible.preflightWhole(
			infeasible.unconstrained(), LIMITS, 100, () -> false);
		Assert.assertTrue(empty.admitted());
		Assert.assertFalse(infeasible.solveWhole(
			infeasible.unconstrained(), LIMITS, () -> false).feasible());

		RegionalSearchProblem encoded = encodedProblem(List.of(decision, auxiliary), List.of(
			Factor.dense(List.of(decision), 5d, 1d),
			Factor.dense(List.of(decision, auxiliary),
				Double.POSITIVE_INFINITY, 5d, 0d, Double.POSITIVE_INFINITY)), 1);
		RegionalSearchProblem.RegionalWork work = encoded.preflightWhole(
			encoded.unconstrained(), LIMITS, 100, () -> false);
		RegionalSearchProblem.Solution solved = encoded.solveWhole(
			encoded.unconstrained(), LIMITS, () -> false);
		Assert.assertTrue(work.admitted());
		Assert.assertTrue(solved.feasible());
		Assert.assertEquals(List.of(1), solved.assignment());
		Assert.assertEquals(Double.doubleToRawLongBits(1d),
			Double.doubleToRawLongBits(solved.objective()));
	}

	@Test
	public void regionalPreflightCountsFreeAuxiliaryWorkAndPreservesIncumbentOnSkip()
		throws ReflectiveOperationException {
		Variable decision = new Variable("decision", 2);
		Variable auxA = new Variable("aux-a", 3), auxB = new Variable("aux-b", 3),
			auxC = new Variable("aux-c", 3);
		double[] zeros = new double[27];
		RegionalSearchProblem problem = encodedProblem(List.of(decision, auxA, auxB, auxC), List.of(
			Factor.dense(List.of(decision), 0d, 1d),
			Factor.dense(List.of(auxA, auxB, auxC), zeros)), 1);

		RegionalSearchOptimizer.State limited = new RegionalSearchOptimizer.State(problem,
			List.of(1), searchOptions(1), ignored -> { });
		IllegalArgumentException skipped = Assert.assertThrows(IllegalArgumentException.class,
			() -> limited.region(problem.unconstrained(), Set.of(0), List.of(1)));
		Assert.assertTrue(skipped.getMessage().startsWith("REGIONAL_SEARCH_WORK_LIMIT_EXCEEDED"));
		Assert.assertEquals(List.of(1), limited.assignment);
		Assert.assertEquals(1d, limited.upper, 0d);
		Assert.assertEquals(0d, limited.lower, 0d);
		Assert.assertEquals(1L, limited.stats.get("regionWorkSkips"));
		Assert.assertTrue(limited.stats.get("regionPreflightAssignments") > 1L);
		Assert.assertEquals(0L, limited.stats.get("regionCalls"));

		RegionalSearchOptimizer.State admitted = new RegionalSearchOptimizer.State(problem,
			List.of(1), searchOptions(10_000), ignored -> { });
		RegionalSearchProblem.Solution solution = admitted.region(
			problem.unconstrained(), Set.of(0), List.of(1));
		Assert.assertTrue(solution.feasible());
		Assert.assertEquals(List.of(0), solution.assignment());
		Assert.assertEquals(0d, solution.objective(), 0d);
		Assert.assertEquals(0L, admitted.stats.get("regionWorkSkips"));
		Assert.assertEquals(1L, admitted.stats.get("regionCalls"));
	}

	@Test
	public void configurationRejectsMixedModesAndUnknownAlgorithms() {
		String key = CertifiedRegionalOptimizer.PROPERTY_PREFIX + "algorithm";
		String previous = System.getProperty(key);
		try {
			System.setProperty(key, "legacy");
			Assert.assertNull(RegionalSearchOptimizer.Options.configured(null));
			CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
				1, 1, 1, 1, 1, 1, 0d, 0d, true, true,
				CertifiedRegionalOptimizer.ExpansionPolicy.DISAGREEMENT, 1L, LIMITS);
			System.setProperty(key, "anytime-target");
			Assert.assertEquals(RegionalSearchOptimizer.Algorithm.ANYTIME_TARGET,
				RegionalSearchOptimizer.Options.configured(common).algorithm());
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

	@SuppressWarnings("unchecked")
	private static RegionalSearchProblem encodedProblem(List<Variable> variables,
		List<Factor> factors, int decisionCount) throws ReflectiveOperationException {
		Constructor<RegionalSearchProblem> constructor = RegionalSearchProblem.class
			.getDeclaredConstructor(List.class, List.class, int.class, ToDoubleFunction.class);
		constructor.setAccessible(true);
		ToDoubleFunction<List<Integer>> evaluator = assignment -> assignment.get(0);
		return constructor.newInstance(variables, factors, decisionCount, evaluator);
	}

	private static RegionalSearchOptimizer.Options searchOptions(long regionalWorkLimit) {
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			1, 1, 2, 1, 1, 10_000, 0d, 0d, true, true,
			CertifiedRegionalOptimizer.ExpansionPolicy.DISAGREEMENT, 19L, LIMITS);
		return new RegionalSearchOptimizer.Options(RegionalSearchOptimizer.Algorithm.THRESHOLD,
			common, 4, 1, 1, 8, 0L, regionalWorkLimit);
	}
}
