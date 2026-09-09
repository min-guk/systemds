/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.Options;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.StopReason;
import org.junit.Assert;
import org.junit.Test;

/** Shared evaluation and certificate regressions retained from the experimental suite. */
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
			Result result = solve(variables, factors, List.of(0, 0, 0, 0, 0), options(60_000, LIMITS));
			double lastLower = 0, lastUpper = Double.POSITIVE_INFINITY;
			for(RegionalSearchOptimizer.Checkpoint row : result.checkpoints()) {
				Assert.assertTrue(row.lowerBound() <= optimum && optimum <= row.upperBound());
				Assert.assertTrue(row.lowerBound() >= lastLower && row.upperBound() <= lastUpper);
				Assert.assertEquals(row.upperBound(), CertifiedRegionalOptimizer.evaluate(variables, factors, row.assignment()), 0);
				lastLower = row.lowerBound(); lastUpper = row.upperBound();
			}
			Assert.assertEquals(optimum, result.upperBound(), 0d);
			Assert.assertEquals(result.lowerBound(), result.upperBound(), 0d);
		}
	}

	@Test
	public void zeroTimeBudgetAndZeroCostAreWellDefined() {
		Variable x = new Variable("x", 2);
		List<Factor> factors = List.of(Factor.dense(List.of(x), 5, 0));
		Result timeout = solve(List.of(x), factors, List.of(0), options(0, LIMITS));
		Assert.assertEquals(StopReason.TIME_BUDGET, timeout.stopReason());
		Assert.assertEquals(List.of(0), timeout.assignment());
		Result zero = solve(List.of(x), factors, List.of(1), options(0, LIMITS));
		Assert.assertEquals(StopReason.GLOBAL_EXACT, zero.stopReason());
		Assert.assertEquals(0d, zero.relativeGap(), 0d);
	}

	@Test
	public void resourceStopRetainsFeasibleSeedWithoutInventingBound() {
		Variable x = new Variable("x", 2), y = new Variable("y", 2);
		Result result = solve(List.of(x, y), List.of(Factor.dense(List.of(x, y), 4, 9, 9, 1)),
			List.of(0, 0), options(60_000, new Limits(1, 100)));
		Assert.assertEquals(StopReason.RESOURCE_LIMIT, result.stopReason());
		Assert.assertEquals(List.of(0, 0), result.assignment());
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertEquals(4d, result.upperBound(), 0d);
		Assert.assertFalse(result.targetReached());
	}

	@Test
	public void constantOnlyModelRetainsExactObjective() {
		Result result = solve(List.of(), List.of(Factor.dense(List.of(), .1), Factor.dense(List.of(), .2)),
			List.of(), options(60_000, LIMITS));
		Assert.assertEquals(.1 + .2, result.upperBound(), 0d);
		Assert.assertEquals(result.upperBound(), result.lowerBound(), 0d);
	}

	@Test
	public void invalidAssignmentsAndInfeasibleSeedsAreErrors() {
		Variable x = new Variable("x", 2);
		Options options = options(60_000, LIMITS);
		Assert.assertThrows(IllegalArgumentException.class, () -> solve(List.of(x),
			List.of(Factor.dense(List.of(x), 8, 3)), List.of(2), options));
		Assert.assertThrows(IllegalArgumentException.class, () -> solve(List.of(x),
			List.of(Factor.dense(List.of(x), Double.POSITIVE_INFINITY, 0)), List.of(0), options));
		Assert.assertThrows(IllegalArgumentException.class, () -> solve(List.of(x),
			List.of(Factor.dense(List.of(x), Double.NaN, 0)), List.of(0), options));
	}

	private static Options options(long budget, Limits limits) {
		return new Options(2, budget, 0, 0, limits);
	}
	private static Result solve(List<Variable> variables, List<Factor> factors, List<Integer> seed, Options common) {
		return RegionalSearchOptimizer.optimize(variables, factors, seed,
			new RegionalSearchOptimizer.Options(RegionalSearchOptimizer.Algorithm.REMAINING_EXACT, common, Long.MAX_VALUE));
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
