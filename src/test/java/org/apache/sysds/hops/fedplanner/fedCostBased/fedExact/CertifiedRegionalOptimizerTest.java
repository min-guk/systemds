/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.ExpansionPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.Options;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.StopReason;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class CertifiedRegionalOptimizerTest {
	private static final Limits LIMITS = new Limits(100_000L, 1_000_000L);

	@Test
	public void everyCheckpointBoundsIndependentExhaustiveOptimum() {
		Random random = new Random(5318008L);
		for(int trial = 0; trial < 40; trial++) {
			List<Variable> variables = new ArrayList<>();
			for(int index = 0; index < 5; index++)
				variables.add(new Variable("v" + index, 2));
			List<Factor> factors = new ArrayList<>();
			factors.add(Factor.dense(List.of(), 3d));
			for(int index = 0; index < 5; index++) {
				factors.add(Factor.dense(List.of(variables.get(index)), random.nextInt(11), random.nextInt(11)));
				for(int other = index + 1; other < 5; other++)
					if(random.nextBoolean())
						factors.add(Factor.dense(List.of(variables.get(index), variables.get(other)),
							random.nextInt(17), random.nextInt(17), random.nextInt(17),
							random.nextInt(5) == 0 ? Double.POSITIVE_INFINITY : random.nextInt(17)));
			}
			double optimum = oracle(variables, factors);
			for(ExpansionPolicy policy : ExpansionPolicy.values()) {
				Result result = solve(variables, factors, List.of(0, 0, 0, 0, 0),
					options(1, 3, 5, 1, 5, true, true, policy));
				assertInvariant(variables, factors, optimum, result);
				Assert.assertEquals(optimum, result.upperBound(), 0d);
				Assert.assertEquals(result.lowerBound(), result.upperBound(), 0d);
			}
		}
	}

	@Test
	public void cumulativeRegionsEscapeSharedProducerBarrierAndReachGlobal() {
		Variable producer = new Variable("producer", 2);
		Variable a = new Variable("consumer-a", 2);
		Variable b = new Variable("consumer-b", 2);
		List<Variable> variables = List.of(producer, a, b);
		List<Factor> factors = List.of(Factor.dense(List.of(producer, a), 4, 9, 9, 1),
			Factor.dense(List.of(producer, b), 4, 9, 9, 1));
		Result result = solve(variables, factors, List.of(0, 0, 0),
			options(1, 1, 3, 1, 3, false, true, ExpansionPolicy.STRUCTURAL));
		assertInvariant(variables, factors, 2d, result);
		List<CertifiedRegionalOptimizer.Checkpoint> regions = result.checkpoints().stream()
			.filter(row -> row.phase().equals("REGION") || row.phase().equals("GLOBAL")).toList();
		Assert.assertEquals(3, regions.size());
		Assert.assertEquals(8d, regions.get(0).upperBound(), 0d);
		Assert.assertEquals(8d, regions.get(1).upperBound(), 0d);
		Assert.assertEquals(2d, regions.get(2).upperBound(), 0d);
		Assert.assertEquals(List.of(1, 1, 1), result.assignment());
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
	}

	@Test
	public void certificationAndBoundRefinementDoNotChangeIncumbent() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		List<Variable> variables = List.of(x, y);
		List<Factor> factors = List.of(Factor.dense(List.of(x, y), 4, 9, 9, 1));
		for(boolean refine : List.of(false, true)) {
			Result result = solve(variables, factors, List.of(0, 0),
				options(1, 4, 4, 1, 2, refine, false, ExpansionPolicy.DISAGREEMENT));
			Assert.assertEquals(List.of(0, 0), result.assignment());
			Assert.assertEquals(4d, result.upperBound(), 0d);
			Assert.assertEquals(StopReason.CERTIFIED, result.stopReason());
			assertInvariant(variables, factors, 1d, result);
		}
	}

	@Test
	public void lowerBoundNeverConditionsOutsideRegionOnIncumbent() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		List<Variable> variables = List.of(x, y);
		List<Factor> factors = List.of(Factor.dense(List.of(x), 0, 10),
			Factor.dense(List.of(x, y), 0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0));
		Result result = solve(variables, factors, List.of(1, 1),
			options(1, 1, 1, 1, 1, false, false, ExpansionPolicy.STRUCTURAL));
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertEquals(10d, result.upperBound(), 0d);
		Assert.assertTrue(Double.isInfinite(result.relativeGap()));
		assertInvariant(variables, factors, 0d, result);
	}

	@Test
	public void completeCoupledHardConstraintIsSolvedWithItsCost() {
		Variable x = new Variable("decision", 2);
		Variable auxiliary = new Variable("derived", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(x, auxiliary),
			0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0),
			Factor.dense(List.of(auxiliary), 8, 3));
		Result result = CertifiedRegionalOptimizer.optimize(List.of(x, auxiliary), factors, List.of(0, 0),
			options(1, 1, 1, 2, 2, false, true, ExpansionPolicy.STRUCTURAL));
		Assert.assertEquals(List.of(1, 1), result.assignment());
		Assert.assertEquals(3d, result.lowerBound(), 0d);
		Assert.assertEquals(3d, result.upperBound(), 0d);
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
	}

	@Test
	public void resourceStopsKeepFeasibleIncumbentAndDoNotInventRawBound() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		List<Variable> variables = List.of(x, y);
		List<Factor> factors = List.of(Factor.dense(List.of(x, y), 4, 9, 9, 1));
		Options options = new Options(1, 2, 2, 1, 2, 60_000L, 0, 0, true, true,
			ExpansionPolicy.STRUCTURAL, 7L, new Limits(1, 100));
		Result result = solve(variables, factors, List.of(0, 0), options);
		Assert.assertEquals(StopReason.RESOURCE_LIMIT, result.stopReason());
		Assert.assertEquals(List.of(0, 0), result.assignment());
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertTrue(result.checkpoints().stream().anyMatch(row ->
			row.phase().equals("BOUND_LIMIT") && Double.isNaN(row.rawLowerBound())));
		assertInvariant(variables, factors, 1d, result);
	}

	@Test
	public void zeroTimeBudgetAndZeroCostAreWellDefined() {
		Variable x = new Variable("x", 2);
		List<Variable> variables = List.of(x);
		List<Factor> factors = List.of(Factor.dense(variables, 5, 0));
		Options immediate = new Options(1, 1, 1, 1, 1, 0L, 0, 0, false, true,
			ExpansionPolicy.STRUCTURAL, 7L, LIMITS);
		Result timeout = solve(variables, factors, List.of(0), immediate);
		Assert.assertEquals(StopReason.TIME_BUDGET, timeout.stopReason());
		Assert.assertEquals(List.of(0), timeout.assignment());
		Result zero = solve(variables, factors, List.of(1), immediate);
		Assert.assertEquals(StopReason.GAP, zero.stopReason());
		Assert.assertEquals(0d, zero.absoluteGap(), 0d);
		Assert.assertEquals(0d, zero.relativeGap(), 0d);
	}

	@Test
	public void positiveGapToleranceStopsWithConservativeCertificate() {
		Variable x = new Variable("x", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(x), 10, 9));
		Options options = new Options(1, 1, 1, 1, 1, 60_000L, 0, 0.12, false, true,
			ExpansionPolicy.STRUCTURAL, 7L, LIMITS);
		Result result = solve(List.of(x), factors, List.of(0), options);
		Assert.assertEquals(StopReason.GAP, result.stopReason());
		Assert.assertEquals(List.of(0), result.assignment());
		Assert.assertTrue(result.relativeGap() >= 1d / 9d);
		Assert.assertTrue(result.relativeGap() <= 0.12);
	}

	@Test
	public void noVariablesAndConstantCostRetainSoundCertificate() {
		List<Factor> factors = List.of(Factor.dense(List.of(), 0.1), Factor.dense(List.of(), 0.2));
		Result result = solve(List.of(), factors, List.of(),
			options(1, 1, 1, 1, 1, false, true, ExpansionPolicy.STRUCTURAL));
		Assert.assertEquals(List.of(), result.assignment());
		Assert.assertEquals(0.1 + 0.2, result.upperBound(), 0d);
		Assert.assertEquals(result.upperBound(), result.lowerBound(), 0d);
	}

	@Test
	public void invalidAssignmentAndOriginalModelInfeasibilityAreErrors() {
		Variable x = new Variable("x", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(x), 8, 3));
		Options options = options(1, 1, 1, 1, 1, false, true, ExpansionPolicy.STRUCTURAL);
		Assert.assertThrows(IllegalArgumentException.class, () -> solve(List.of(x), factors, List.of(2), options));
		Assert.assertThrows(IllegalArgumentException.class, () -> solve(List.of(x),
			List.of(Factor.dense(List.of(x), Double.POSITIVE_INFINITY, 0)), List.of(0), options));
		Assert.assertThrows(IllegalArgumentException.class, () -> solve(List.of(x),
			List.of(Factor.dense(List.of(x), Double.NaN, 0)), List.of(0), options));
	}

	@Test
	public void seededPoliciesRepeatAssignmentsAndBoundTrajectories() {
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		Variable c = new Variable("c", 2);
		List<Variable> variables = List.of(a, b, c);
		List<Factor> factors = List.of(Factor.dense(List.of(a, b), 4, 9, 9, 1),
			Factor.dense(List.of(b, c), 5, 8, 8, 1));
		Options options = options(1, 2, 3, 1, 3, true, true, ExpansionPolicy.RANDOM);
		Result left = solve(variables, factors, List.of(0, 0, 0), options);
		Result right = solve(variables, factors, List.of(0, 0, 0), options);
		Assert.assertEquals(left.assignment(), right.assignment());
		Assert.assertEquals(left.stopReason(), right.stopReason());
		Assert.assertEquals(left.checkpoints().stream().map(row -> List.of(row.lowerBound(), row.upperBound())).toList(),
			right.checkpoints().stream().map(row -> List.of(row.lowerBound(), row.upperBound())).toList());
	}

	private static Result solve(List<Variable> variables, List<Factor> factors, List<Integer> initial, Options options) {
		return CertifiedRegionalOptimizer.optimize(variables, factors, initial, options);
	}

	static Options options(int initialWidth, int maxWidth, int rounds, int growth, int maxRegion,
		boolean refine, boolean expand, ExpansionPolicy policy) {
		return new Options(initialWidth, maxWidth, rounds, growth, maxRegion, 60_000L, 0, 0,
			refine, expand, policy, 99L, LIMITS);
	}

	private static void assertInvariant(List<Variable> variables, List<Factor> factors, double optimum, Result result) {
		double previousLower = 0d;
		double previousUpper = Double.POSITIVE_INFINITY;
		for(CertifiedRegionalOptimizer.Checkpoint checkpoint : result.checkpoints()) {
			Assert.assertTrue("lower exceeds independent optimum: " + checkpoint, checkpoint.lowerBound() <= optimum);
			Assert.assertTrue("upper below independent optimum: " + checkpoint, checkpoint.upperBound() >= optimum);
			Assert.assertTrue(checkpoint.lowerBound() >= previousLower);
			Assert.assertTrue(checkpoint.upperBound() <= previousUpper);
			Assert.assertEquals(checkpoint.upperBound(),
				CertifiedRegionalOptimizer.evaluate(variables, factors, checkpoint.assignment()), 0d);
			previousLower = checkpoint.lowerBound();
			previousUpper = checkpoint.upperBound();
		}
	}

	/** Independent full enumeration with decimal-exact sums of binary64 factor entries. */
	private static double oracle(List<Variable> variables, List<Factor> factors) {
		BigDecimal best = null;
		for(int bits = 0; bits < (1 << variables.size()); bits++) {
			BigDecimal total = BigDecimal.ZERO;
			boolean feasible = true;
			for(Factor factor : factors) {
				int[] values = new int[factor.scope().size()];
				for(int local = 0; local < values.length; local++)
					values[local] = (bits >>> variables.indexOf(factor.scope().get(local))) & 1;
				double value = factor.cost(values);
				if(!Double.isFinite(value)) {
					feasible = false;
					break;
				}
				total = total.add(new BigDecimal(value));
			}
			if(feasible && (best == null || total.compareTo(best) < 0))
				best = total;
		}
		return best == null ? Double.POSITIVE_INFINITY : best.doubleValue();
	}
}
