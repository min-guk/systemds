/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/** The opt-in integer kernel, not a change to legacy/local rounded-primary policy. */
public class ExactDyadicSolverTest {
	private static final ExactCategoricalSolver.Limits LIMITS =
		new ExactCategoricalSolver.Limits(100_000, 1_000_000);

	@Test
	public void retainedResidueCannotLoseGlobalCanonicalOptimum() {
		var x = new ExactCategoricalSolver.Variable("x", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 0x1p53, 0x1p53),
			ExactCategoricalSolver.Factor.dense(List.of(x), .75, 0),
			ExactCategoricalSolver.Factor.dense(List.of(), 1));
		var compiled = ExactCategoricalSolver.compile(List.of(x), factors, LIMITS);
		Assert.assertEquals("legacy behavior is deliberately unchanged", List.of(0),
			ExactCategoricalSolver.solve(compiled).assignmentInVariableOrder());
		var exact = ExactCategoricalSolver.solveDyadic(compiled, certificate(-2, 56, 3));
		Assert.assertEquals(List.of(1), exact.assignmentInVariableOrder());
		Assert.assertEquals(Double.doubleToRawLongBits(0x1p53),
			Double.doubleToRawLongBits(exact.objective()));
	}

	@Test
	public void structuralTieRefinesRoundedEqualObjectivesByExactTotal() {
		var x = new ExactCategoricalSolver.Variable("x", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 0x1p53, 0x1p53),
			ExactCategoricalSolver.Factor.dense(List.of(x), .25, 0));
		var compiled = ExactCategoricalSolver.compile(List.of(x), factors, LIMITS);
		Assert.assertEquals(List.of(0), ExactCategoricalSolver.solve(compiled).assignmentInVariableOrder());
		Assert.assertEquals(List.of(1), ExactCategoricalSolver.solveDyadic(compiled,
			certificate(-2, 56, 2)).assignmentInVariableOrder());
	}

	@Test
	public void conditionedExactOptimaAgreeWithIndependentExhaustiveSums() {
		Random random = new Random(783412);
		List<ExactCategoricalSolver.Variable> variables = List.of(
			new ExactCategoricalSolver.Variable("x", 2),
			new ExactCategoricalSolver.Variable("y", 2),
			new ExactCategoricalSolver.Variable("z", 2));
		for(int fixture = 0; fixture < 40; fixture++) {
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			for(int first = 0; first < variables.size(); first++) {
				double[] values = new double[4];
				for(int cell = 0; cell < values.length; cell++)
					values[cell] = random.nextInt(5) == 0 ? Double.POSITIVE_INFINITY
						: random.nextBoolean() ? 0x1p50 + random.nextInt(8)
						: Math.scalb(random.nextInt(1024), -20);
				factors.add(ExactCategoricalSolver.Factor.dense(
					List.of(variables.get(first), variables.get((first + 1) % 3)), values));
			}
			for(int fixed = -1; fixed < 2; fixed++) {
				List<ExactCategoricalSolver.Factor> conditioned = new ArrayList<>(factors);
				if(fixed >= 0)
					conditioned.add(ExactCategoricalSolver.Factor.dense(List.of(variables.get(1)),
						fixed == 0 ? 0 : Double.POSITIVE_INFINITY,
						fixed == 1 ? 0 : Double.POSITIVE_INFINITY));
				assertExhaustive(variables, conditioned, certificate(-20, 94, conditioned.size()),
					List.of("x", "y", "z"));
				assertExhaustive(variables, conditioned, certificate(-20, 94, conditioned.size()),
					List.of("z", "y", "x"));
			}
		}
	}

	@Test
	public void sparseAndDenseOutputStorageKeepBothExactDigits() {
		var x = new ExactCategoricalSolver.Variable("x", 4);
		var y = new ExactCategoricalSolver.Variable("y", 100);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y);
		for(int finiteOutputs : List.of(3, 100)) {
			double[] relation = new double[400];
			for(int first = 0; first < 4; first++)
				for(int second = 0; second < 100; second++)
					relation[first * 100 + second] = second >= finiteOutputs || first == 3
						? Double.POSITIVE_INFINITY : (3 - first) * .25 + second * .5;
			List<ExactCategoricalSolver.Factor> factors = List.of(
				ExactCategoricalSolver.Factor.dense(List.of(x), 0x1p53, 0x1p53, 0x1p53, 0x1p53),
				ExactCategoricalSolver.Factor.dense(variables, relation),
				ExactCategoricalSolver.Factor.dense(List.of(), 1));
			assertExhaustive(variables, factors, certificate(-2, 57, 3), List.of("x", "y"));
			assertExhaustive(variables, factors, certificate(-2, 57, 3), List.of("y", "x"));
		}
	}

	@Test
	public void exactLowWordsParticipateInIntermediateValueClasses() {
		var x = new ExactCategoricalSolver.Variable("x", 2);
		var y = new ExactCategoricalSolver.Variable("y", 2);
		var z = new ExactCategoricalSolver.Variable("z", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(x, y, z);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 0x1p53, 0x1p53),
			ExactCategoricalSolver.Factor.dense(List.of(x, y), .75, 0, 1, .25),
			ExactCategoricalSolver.Factor.dense(List.of(y, z), 0, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0),
			ExactCategoricalSolver.Factor.dense(List.of(z), 1, 1));
		assertExhaustive(variables, factors, certificate(-2, 57, 4), List.of("x", "y", "z"));
	}

	@Test
	public void actualTablesCannotExceedClaimedLatticeOrMaximum() {
		var x = new ExactCategoricalSolver.Variable("x", 2);
		var tooLarge = ExactCategoricalSolver.compile(List.of(x), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), 1, 8)), LIMITS);
		IllegalArgumentException maximum = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solveDyadic(tooLarge, certificate(0, 3, 1)));
		Assert.assertTrue(maximum.getMessage().contains("MAXIMUM_BOUND_MISMATCH"));
		var fractional = ExactCategoricalSolver.compile(List.of(x), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), .5, 1)), LIMITS);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solveDyadic(fractional, certificate(0, 2, 1)));
		for(double invalid : new double[] {-1, -0.0}) {
			Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactCategoricalSolver.solveDyadic(ExactCategoricalSolver.compile(List.of(x), List.of(
					ExactCategoricalSolver.Factor.dense(List.of(x), invalid, 0)), LIMITS),
					certificate(-2, 3, 1)));
		}
	}

	@Test
	public void subnormalCostsAndMissingSparseTuplesAreExact() {
		var x = new ExactCategoricalSolver.Variable("x", 3);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(x), Double.MIN_VALUE,
				Double.POSITIVE_INFINITY, 2 * Double.MIN_VALUE),
			ExactCategoricalSolver.Factor.dense(List.of(), Double.MIN_VALUE));
		assertExhaustive(List.of(x), factors, certificate(-1074, 3, 2), List.of("x"));
	}

	@Test
	public void unchangedStructuralPreflightPrecedesLazyEvaluation() {
		var x = new ExactCategoricalSolver.Variable("x", 2);
		AtomicInteger calls = new AtomicInteger();
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(x), values -> {
			calls.incrementAndGet();
			return 1;
		});
		Assert.assertThrows(IllegalArgumentException.class, () -> ExactCategoricalSolver.solveDyadic(
			ExactCategoricalSolver.compile(List.of(x), List.of(factor),
				new ExactCategoricalSolver.Limits(1, 1)), certificate(0, 2, 1)));
		Assert.assertEquals(0, calls.get());
	}

	private static ExactDyadicCosts.Certificate certificate(int q, int bits, int contributions) {
		// These tiny fixtures use the same elementary tables for both objectives.
		var certificate = ExactDyadicCosts.certify(q, bits, contributions, true);
		Assert.assertTrue(certificate.reason(), certificate.supported());
		return certificate;
	}

	private static void assertExhaustive(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, ExactDyadicCosts.Certificate certificate,
		List<String> order) {
		int cells = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		BigDecimal optimum = null;
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			Integer[] assignment = new Integer[variables.size()];
			for(int axis = variables.size() - 1; axis >= 0; axis--) {
				assignment[axis] = remaining % variables.get(axis).domainSize();
				remaining /= variables.get(axis).domainSize();
			}
			BigDecimal objective = exactObjective(variables, factors, List.of(assignment));
			if(objective != null && (optimum == null || objective.compareTo(optimum) < 0))
				optimum = objective;
		}
		var compiled = ExactCategoricalSolver.compilePreferred(variables, factors, LIMITS, order);
		if(optimum == null) {
			IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
				() -> ExactCategoricalSolver.solveDyadic(compiled, certificate));
			Assert.assertTrue(failure.getMessage().contains("NO_FEASIBLE_ASSIGNMENT"));
			return;
		}
		var result = ExactCategoricalSolver.solveDyadic(compiled, certificate);
		Assert.assertEquals(0, optimum.compareTo(exactObjective(variables, factors,
			result.assignmentInVariableOrder())));
		Assert.assertEquals(Double.doubleToRawLongBits(optimum.doubleValue()),
			Double.doubleToRawLongBits(result.objective()));
		Assert.assertEquals(result, ExactCategoricalSolver.solveDyadic(compiled, certificate));
	}

	private static BigDecimal exactObjective(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, List<Integer> assignment) {
		BigDecimal sum = BigDecimal.ZERO;
		for(var factor : factors) {
			int[] local = factor.scope().stream().mapToInt(variable ->
				assignment.get(variables.indexOf(variable))).toArray();
			double cost = factor.cost(local);
			if(cost == Double.POSITIVE_INFINITY)
				return null;
			sum = sum.add(new BigDecimal(cost));
		}
		return sum;
	}
}
