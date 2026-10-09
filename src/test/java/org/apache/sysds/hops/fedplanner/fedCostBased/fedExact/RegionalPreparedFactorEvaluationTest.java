/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class RegionalPreparedFactorEvaluationTest {
	private static final Limits LIMITS = new Limits(100_000, 1_000_000);

	@Test
	public void repeatedPreparedEvaluationMatchesIndependentReferenceRawBits() {
		List<Variable> variables = List.of(
			new Variable("prepared-a", 4), new Variable("prepared-b", 3),
			new Variable("prepared-c", 2));
		List<Factor> factors = List.of(
			Factor.dense(List.of(), 1e16),
			Factor.dense(List.of(variables.get(2), variables.get(0)),
				0, 7, 3, 9, 2, 8, 4, 6),
			Factor.dense(List.of(variables.get(1)), 1, Math.nextUp(1d), 5),
			Factor.dense(List.of(), 1));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
		var prepared = problem.preparedFactors();
		Random random = new Random(0x51c0ffeeL);
		for(int iteration = 0; iteration < 400; iteration++) {
			List<Integer> assignment = List.of(random.nextInt(4), random.nextInt(3), random.nextInt(2));
			long expected = Double.doubleToRawLongBits(
				RegionalSearchProblem.evaluateFactors(variables, factors, assignment));
			Assert.assertEquals(expected,
				Double.doubleToRawLongBits(problem.evaluatePreparedFactors(assignment)));
			Assert.assertEquals(expected,
				Double.doubleToRawLongBits(prepared.evaluate(assignment)));
			Assert.assertSame("scope preparation belongs to the immutable problem",
				prepared, problem.preparedFactors());
		}
	}

	@Test
	public void preparedEvaluationPreservesInfinityAndValidationErrors() {
		Variable x = new Variable("prepared-errors-x", 2);
		Variable foreign = new Variable("prepared-errors-x", 2);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(List.of(x),
			List.of(Factor.dense(List.of(x), Double.POSITIVE_INFINITY, 7)));
		Assert.assertEquals(Double.POSITIVE_INFINITY,
			problem.evaluatePreparedFactors(List.of(0)), 0d);
		Assert.assertEquals(7d, problem.evaluatePreparedFactors(List.of(1)), 0d);
		assertMessage("REGIONAL_ASSIGNMENT_SIZE_INVALID",
			() -> problem.evaluatePreparedFactors(List.of()));
		assertMessage("REGIONAL_ASSIGNMENT_VALUE_INVALID",
			() -> problem.evaluatePreparedFactors(List.of(2)));
		assertMessage("REGIONAL_VARIABLE_DUPLICATE", () ->
			RegionalSearchProblem.PreparedFactorEvaluation.prepare(
				List.of(x, foreign), List.of()));
		assertMessage("REGIONAL_FACTOR_SCOPE_INVALID", () ->
			RegionalSearchProblem.PreparedFactorEvaluation.prepare(
				List.of(foreign), List.of(Factor.dense(List.of(x), 1, 2))));
	}

	@Test
	public void fixedSeedOptimizerRetainsExactAssignmentObjectiveAndCheckpoints() {
		List<Variable> variables = List.of(new Variable("prepared-opt-a", 3),
			new Variable("prepared-opt-b", 3), new Variable("prepared-opt-c", 2));
		List<Factor> factors = new ArrayList<>();
		factors.add(Factor.dense(List.of(variables.get(0), variables.get(1)),
			8, 6, 5, 7, 2, 4, 9, 3, 1));
		factors.add(Factor.dense(List.of(variables.get(1), variables.get(2)),
			4, 7, 3, 8, 2, 1));
		factors.add(Factor.dense(List.of(variables.get(0)), 3, 1, 2));
		var exact = ExactCategoricalSolver.solve(variables, factors, LIMITS);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
		List<IncrementalRegionalOptimizer.Checkpoint> observed = new ArrayList<>();
		var result = IncrementalRegionalOptimizer.optimize(problem, problem.reducedRoot(LIMITS),
			List.of(0, 0, 0), LIMITS,
			new IncrementalRegionalOptimizer.Options(0, 100_000, 1_000_000, 0, 16, false),
			observed::add);
		Assert.assertEquals(exact.assignmentInVariableOrder(), result.assignment());
		Assert.assertEquals(Double.doubleToRawLongBits(exact.objective()),
			Double.doubleToRawLongBits(result.upper()));
		Assert.assertEquals(result.checkpoints(), observed);
		double lower = 0d;
		double upper = Double.POSITIVE_INFINITY;
		for(var checkpoint : result.checkpoints()) {
			Assert.assertTrue(checkpoint.lower() >= lower);
			Assert.assertTrue(checkpoint.upper() <= upper);
			Assert.assertTrue(checkpoint.lower() <= exact.objective());
			Assert.assertTrue(checkpoint.upper() >= exact.objective());
			lower = checkpoint.lower();
			upper = checkpoint.upper();
		}
		Assert.assertEquals(Double.doubleToRawLongBits(
			RegionalSearchProblem.evaluateFactors(variables, factors, result.assignment())),
			Double.doubleToRawLongBits(result.upper()));
	}

	private static void assertMessage(String expected, Runnable action) {
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class, action::run);
		Assert.assertEquals(expected, failure.getMessage());
	}
}
