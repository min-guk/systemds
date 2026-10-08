/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactConditionalSupportSolverTest {
	private static final ExactCategoricalSolver.Limits LARGE =
		new ExactCategoricalSolver.Limits(Integer.MAX_VALUE, Integer.MAX_VALUE);

	@Test
	public void incrementalRegionalOptimizerConsumesConditionalBoundaryMessages() {
		var selector = variable("regional-selector", 2);
		var left = variable("regional-left", 32);
		var right = variable("regional-right", 32);
		List<ExactCategoricalSolver.Variable> variables = List.of(selector, left, right);
		double[] leftCosts = new double[32];
		double[] rightCosts = new double[32];
		Arrays.fill(leftCosts, 10d);
		Arrays.fill(rightCosts, 10d);
		leftCosts[31] = 0d;
		rightCosts[30] = 0d;
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.conditionalSupport(variables, 0,
				new int[] {0, 1}, List.of(
					region(0, null, new int[] {3}, new int[] {5}),
					region(1, null, new int[] {1}, new int[] {1}),
					region(1, null, new int[] {31}, new int[] {30}))),
			ExactCategoricalSolver.Factor.dense(List.of(selector),
				Double.POSITIVE_INFINITY, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(left), leftCosts),
			ExactCategoricalSolver.Factor.dense(List.of(right), rightCosts));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
		AtomicInteger merges = new AtomicInteger();
		var result = IncrementalRegionalOptimizer.optimize(problem, problem.reducedRoot(LARGE),
			List.of(1, 1, 1), LARGE,
			new IncrementalRegionalOptimizer.Options(0d, 0L, 0L, 0L, 0, false),
			ignored -> { }, null, (messages, boundary, limits, maximumAssignments, counters) -> {
				merges.incrementAndGet();
				return ExactCategoricalSolver.mergeBoundary(messages, boundary, limits, counters);
			});
		Assert.assertTrue(merges.get() > 0);
		Assert.assertEquals(0d, result.upper(), 0d);
		Assert.assertEquals(0d,
			RegionalSearchProblem.evaluateFactors(variables, factors, result.assignment()), 0d);
	}

	@Test
	public void regionalBoundaryKeepsConditionalRowsAndJoinsOnlyActiveRectangle() {
		var selector = variable("boundary-selector", 2);
		var left = variable("boundary-left", 1_000);
		var right = variable("boundary-right", 1_000);
		List<ExactCategoricalSolver.Variable> variables = List.of(selector, left, right);
		var conditional = ExactCategoricalSolver.Factor.conditionalSupport(variables, 0,
			new int[] {0, 1}, List.of(
				region(0, null, new int[] {7}, new int[] {11}),
				region(1, null, new int[] {999}, new int[] {998})));
		List<ExactCategoricalSolver.BoundaryMessage> leaves =
			ExactCategoricalSolver.boundaryLeaves(variables, List.of(
				conditional, ExactCategoricalSolver.Factor.dense(List.of(selector),
					Double.POSITIVE_INFINITY, 0d)), LARGE);
		Assert.assertEquals(2_000_000L, leaves.get(0).cells());
		Assert.assertEquals(4L, leaves.get(0).retainedCells());
		Assert.assertArrayEquals(new double[] {0d, 0d},
			leaves.get(0).minMarginals(selector), 0d);
		var counters = new ExactCategoricalSolver.BoundaryMergeCounters();
		var root = ExactCategoricalSolver.mergeBoundary(leaves, List.of(), LARGE, counters);
		Assert.assertEquals(0d, root.minimum(), 0d);
		Assert.assertEquals(2L, counters.childEvaluations());
		Assert.assertTrue(counters.supportCellsExamined() <= 4L);
	}

	@Test
	public void constrainedSelectorWithoutRegionsIsForbiddenEvenForSelectorOnlyScope() {
		var selector = variable("empty-selector", 3);
		var conditional = ExactCategoricalSolver.Factor.conditionalSupport(
			List.of(selector), 0, new int[] {1, 2}, List.of(region(1, (int[])null)));
		var wildcard = ExactCategoricalSolver.solve(List.of(selector), List.of(
			conditional, ExactCategoricalSolver.Factor.dense(List.of(selector),
				0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), LARGE);
		Assert.assertEquals(List.of(0), wildcard.assignmentInVariableOrder());
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(selector), List.of(
				conditional, ExactCategoricalSolver.Factor.dense(List.of(selector),
					Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d)), LARGE));
		Assert.assertTrue(failure.getMessage().startsWith("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"));
	}

	@Test
	public void unionOfProductsAndWildcardSelectorsMatchExplicitDenseOracle() {
		var selector = variable("selector", 3);
		var left = variable("left", 4);
		var right = variable("right", 3);
		List<ExactCategoricalSolver.Variable> variables = List.of(selector, left, right);
		var conditional = ExactCategoricalSolver.Factor.conditionalSupport(variables, 0, List.of(
			region(1, null, new int[] {0, 1}, new int[] {1}),
			region(1, null, new int[] {1, 2}, new int[] {1, 2})));
		double[] explicit = new double[3 * 4 * 3];
		for(int selectorValue = 0; selectorValue < 3; selectorValue++)
			for(int leftValue = 0; leftValue < 4; leftValue++)
				for(int rightValue = 0; rightValue < 3; rightValue++) {
					boolean legal = selectorValue != 1
						|| (leftValue <= 1 && rightValue == 1)
						|| (leftValue >= 1 && leftValue <= 2 && rightValue >= 1);
					explicit[(selectorValue * 4 + leftValue) * 3 + rightValue] = legal
						? 0d : Double.POSITIVE_INFINITY;
				}
		List<ExactCategoricalSolver.Factor> costs = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(selector),
				Double.POSITIVE_INFINITY, 0d, Double.POSITIVE_INFINITY),
			ExactCategoricalSolver.Factor.dense(List.of(left), 5d, 3d, 0d, 9d),
			ExactCategoricalSolver.Factor.dense(List.of(right), 4d, 2d, 0d));
		var compact = ExactCategoricalSolver.solve(variables,
			List.of(conditional, costs.get(0), costs.get(1), costs.get(2)), LARGE);
		var dense = ExactCategoricalSolver.solve(variables,
			List.of(ExactCategoricalSolver.Factor.dense(variables, explicit),
				costs.get(0), costs.get(1), costs.get(2)), LARGE);

		Assert.assertEquals(List.of(1, 2, 2), compact.assignmentInVariableOrder());
		Assert.assertEquals(dense.assignmentInVariableOrder(), compact.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(dense.objective()),
			Double.doubleToRawLongBits(compact.objective()));
		ExactCategoricalSolver.TieCostFunction tie = (variable, value) ->
			(long)(variable.domainSize() - value);
		var compactTie = ExactCategoricalSolver.solve(variables,
			List.of(conditional, costs.get(0), costs.get(1), costs.get(2)), LARGE, tie);
		var denseTie = ExactCategoricalSolver.solve(variables,
			List.of(ExactCategoricalSolver.Factor.dense(variables, explicit),
				costs.get(0), costs.get(1), costs.get(2)), LARGE, tie);
		Assert.assertEquals(denseTie.assignmentInVariableOrder(), compactTie.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(denseTie.objective()),
			Double.doubleToRawLongBits(compactTie.objective()));
	}

	@Test
	public void millionCellWildcardRelationUsesStoredAxesAndActiveRectangle() {
		var selector = variable("large-selector", 2);
		var left = variable("large-left", 1_000);
		var right = variable("large-right", 1_000);
		var conditional = ExactCategoricalSolver.Factor.conditionalSupport(
			List.of(selector, left, right), 0, List.of(
				region(1, null, new int[] {999}, new int[] {998})));
		Assert.assertEquals(2L, conditional.conditionalStoredValues());
		List<ExactCategoricalSolver.Variable> variables = List.of(selector, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			conditional,
			ExactCategoricalSolver.Factor.dense(List.of(selector),
				Double.POSITIVE_INFINITY, 0d));
		var result = ExactCategoricalSolver.solve(variables, factors, LARGE);
		Assert.assertEquals(List.of(1, 999, 998), result.assignmentInVariableOrder());
		Assert.assertEquals(0L, Double.doubleToRawLongBits(result.objective()));
		var reduced = ExactPhysicalReducedSolver.solve(3, variables, factors, LARGE);
		Assert.assertEquals(result.assignmentInVariableOrder(), reduced.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(result.objective()),
			Double.doubleToRawLongBits(reduced.objective()));
	}

	@Test
	public void reducedSolveProjectsConditionedAxesWithoutChangingExactChoice() {
		var selector = variable("reduced-selector", 3);
		var left = variable("reduced-left", 5);
		var right = variable("reduced-right", 4);
		List<ExactCategoricalSolver.Variable> variables = List.of(selector, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.conditionalSupport(variables, 0, List.of(
				region(2, null, new int[] {1, 3, 4}, new int[] {0, 2, 3}))),
			ExactCategoricalSolver.Factor.dense(List.of(selector),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(left),
				Double.POSITIVE_INFINITY, 4d, Double.POSITIVE_INFINITY, 1d, 2d),
			ExactCategoricalSolver.Factor.dense(List.of(right),
				7d, Double.POSITIVE_INFINITY, 3d, 0d));
		var expected = ExactCategoricalSolver.solve(variables, factors, LARGE);
		var actual = ExactPhysicalReducedSolver.solve(3, variables, factors, LARGE);
		Assert.assertEquals(expected.assignmentInVariableOrder(), actual.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		var compacted = ExactPhysicalReducedSolver.solveCompacted(3, variables, factors, LARGE);
		Assert.assertEquals(expected.assignmentInVariableOrder(), compacted.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(expected.objective()),
			Double.doubleToRawLongBits(compacted.objective()));
	}

	private static ExactCategoricalSolver.ConditionalRegion region(int selector,
		int[]... allowed) {
		return new ExactCategoricalSolver.ConditionalRegion(selector,
			Arrays.stream(allowed).map(values -> values == null ? null : values.clone())
				.toArray(int[][]::new));
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}
}
