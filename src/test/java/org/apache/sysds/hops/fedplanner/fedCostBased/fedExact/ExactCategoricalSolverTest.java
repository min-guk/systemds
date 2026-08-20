/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class ExactCategoricalSolverTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(10_000_000, 50_000_000);

	@Test
	public void randomModelsMatchBruteForce() {
		Random random = new Random(713947L);
		for(int trial = 0; trial < 100; trial++) {
			List<ExactCategoricalSolver.Variable> variables = List.of(
				variable("a", 2 + random.nextInt(2)), variable("b", 2 + random.nextInt(2)),
				variable("c", 2), variable("d", 2));
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(1))));
			factors.add(randomFactor(random, List.of(variables.get(1), variables.get(2), variables.get(3))));
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(3))));
			ExactCategoricalSolver.Result actual =
				ExactCategoricalSolver.solve(variables, factors, GENEROUS);
			BruteForce expected = bruteForce(variables, factors);
			Assert.assertEquals(expected.objective, actual.objective(), 0.0);
			Assert.assertEquals(expected.objective,
				evaluate(variables, factors, actual.assignmentInVariableOrder()), 0.0);
		}
	}

	@Test
	public void disconnectedFactorsAreSolvedTogether() {
		var a = variable("a", 2);
		var b = variable("b", 3);
		var result = ExactCategoricalSolver.solve(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 4, 1),
			ExactCategoricalSolver.Factor.dense(List.of(b), 9, 2, 3)), GENEROUS);
		Assert.assertEquals(3.0, result.objective(), 0.0);
		Assert.assertEquals(List.of(1, 1), result.assignmentInVariableOrder());
	}

	@Test
	public void highOrderSharedTransferFactorChargesOnce() {
		var producer = variable("producer", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		var shared = ExactCategoricalSolver.Factor.lazy(List.of(producer, left, right), values ->
			values[0] == 0 && (values[1] == 1 || values[2] == 1) ? 7.0 : 0.0);
		var rewardLeft = ExactCategoricalSolver.Factor.dense(List.of(left), 0.0, -5.0);
		var rewardRight = ExactCategoricalSolver.Factor.dense(List.of(right), 0.0, -5.0);
		var pinProducer = ExactCategoricalSolver.Factor.dense(List.of(producer), 0.0,
			Double.POSITIVE_INFINITY);
		var result = ExactCategoricalSolver.solve(List.of(producer, left, right),
			List.of(shared, rewardLeft, rewardRight, pinProducer), GENEROUS);
		Assert.assertEquals(-3.0, result.objective(), 0.0);
		Assert.assertEquals(List.of(0, 1, 1), result.assignmentInVariableOrder());
	}

	@Test
	public void infeasibleModelFailsClosed() {
		var a = variable("a", 2);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a),
					Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), GENEROUS));
		Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", error.getMessage());
	}

	@Test
	public void tiesAreDeterministicAcrossRuns() {
		var z = variable("z", 2);
		var a = variable("a", 2);
		List<Integer> first = ExactCategoricalSolver.solve(List.of(z, a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), 0, 0, 0, 0)), GENEROUS)
			.assignmentInVariableOrder();
		for(int repeat = 0; repeat < 20; repeat++)
			Assert.assertEquals(first, ExactCategoricalSolver.solve(List.of(z, a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(z, a), 0, 0, 0, 0)), GENEROUS)
				.assignmentInVariableOrder());
		Assert.assertEquals(List.of(0, 0), first);
	}

	@Test
	public void additiveTieCostResolvesAttributionInvariantPrimaryTie() {
		var producer = variable("producer", 2);
		var consumer = variable("consumer", 2);
		double transfer = 2.000030517578125;
		double compute = 0.000002167820930480957;
		var result = ExactCategoricalSolver.solve(List.of(producer, consumer), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(producer), transfer, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(consumer), 0d, transfer),
			ExactCategoricalSolver.Factor.dense(List.of(consumer), compute, compute),
			ExactCategoricalSolver.Factor.dense(List.of(producer, consumer),
				0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d)), GENEROUS,
			(variable, value) -> variable.equals(producer) && value == 1 ? 1L : 0L);
		Assert.assertEquals(List.of(0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(transfer + compute, result.objective(), 0d);
	}

	@Test
	public void additiveTieCostNeverOverridesDistinctPrimaryCost() {
		var value = variable("value", 2);
		var result = ExactCategoricalSolver.solve(List.of(value), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(value), 1d, Math.nextUp(1d))),
			GENEROUS, (variable, selected) -> selected == 0 ? 100L : 0L);
		Assert.assertEquals(List.of(0), result.assignmentInVariableOrder());
		Assert.assertEquals(1d, result.objective(), 0d);
	}

	@Test
	public void statisticsAndLimitsAreAvailableBeforeEvaluation() {
		var a = variable("a", 100);
		var b = variable("b", 100);
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			throw new AssertionError("must not evaluate beyond the declared limit");
		});
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.analyze(List.of(a, b), List.of(factor),
				new ExactCategoricalSolver.Limits(9_999, 1_000_000)));
		Assert.assertTrue(error.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));

		var stats = ExactCategoricalSolver.analyze(List.of(a, b), List.of(factor), GENEROUS);
		Assert.assertEquals(1, stats.inducedWidth());
		Assert.assertEquals(10_000, stats.maximumFactorCells());
		Assert.assertEquals(List.of("a", "b"), stats.eliminationOrder());
	}

	@Test
	public void domainWeightedOrderAvoidsMinFillMaterializationBlowup() {
		var a = variable("a", 2);
		var b = variable("b", 100);
		var c = variable("c", 2);
		var d = variable("d", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(b, c), values -> 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(b, d), values -> 0d));
		var stats = ExactCategoricalSolver.analyze(List.of(a, b, c, d), factors,
			new ExactCategoricalSolver.Limits(1_000, 700));
		Assert.assertEquals(List.of("b", "a", "c", "d"), stats.eliminationOrder());
		Assert.assertEquals(200L, stats.maximumFactorCells());
		Assert.assertEquals(615L, stats.materializedFactorCells());
	}

	@Test
	public void finiteObjectiveOverflowFailsClosed() {
		var a = variable("a", 1);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a), Double.MAX_VALUE),
				ExactCategoricalSolver.Factor.dense(List.of(a), Double.MAX_VALUE)), GENEROUS));
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW", error.getMessage());
	}

	@Test
	public void objectiveUsesCanonicalCompensatedSummation() {
		var a = variable("a", 1);
		var result = ExactCategoricalSolver.solve(List.of(a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0)), GENEROUS);
		Assert.assertEquals(1.0e16 + 2.0, result.objective(), 0.0);
		Assert.assertEquals(result.objective(), ExactCategoricalSolver.evaluate(
			List.of(a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a), 1.0e16),
				ExactCategoricalSolver.Factor.dense(List.of(a), 1.0),
				ExactCategoricalSolver.Factor.dense(List.of(a), 1.0)),
			GENEROUS, List.of(0)), 0.0);
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int i = 0; i < cells; i++)
			values[i] = random.nextInt(21) - 10;
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static BruteForce bruteForce(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		int[] values = new int[variables.size()];
		double[] best = {Double.POSITIVE_INFINITY};
		enumerate(variables, factors, 0, values, best);
		return new BruteForce(best[0]);
	}

	private static void enumerate(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int index, int[] values, double[] best) {
		if(index == variables.size()) {
			best[0] = Math.min(best[0], evaluate(variables, factors, ArraysAsList(values)));
			return;
		}
		for(int value = 0; value < variables.get(index).domainSize(); value++) {
			values[index] = value;
			enumerate(variables, factors, index + 1, values, best);
		}
	}

	private static double evaluate(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, List<Integer> assignment) {
		double total = 0;
		for(ExactCategoricalSolver.Factor factor : factors) {
			int cell = 0;
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				cell = cell * variable.domainSize() + assignment.get(variables.indexOf(variable));
			total += factor.denseCostAt(cell);
		}
		return total;
	}

	private static List<Integer> ArraysAsList(int[] values) {
		return java.util.Arrays.stream(values).boxed().toList();
	}

	private record BruteForce(double objective) { }
}
