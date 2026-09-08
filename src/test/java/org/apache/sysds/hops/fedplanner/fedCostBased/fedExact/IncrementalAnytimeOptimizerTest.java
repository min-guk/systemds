/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Algorithm;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Options;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.StopReason;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalAnytimeOptimizerTest {
	private static final Limits LIMITS = new Limits(1_000_000, 5_000_000);

	@Test
	public void threeAndFivePercentStopWithUnrestoredCouplingsAndReusedComponents() {
		Fixture fixture = forks(3, false);
		for(double target : new double[] {0.05, 0.03}) {
			Result result = solve(fixture, options(target, 64, 60000, 1_000_000));
			Assert.assertTrue(result.toString(), result.targetReached());
			Assert.assertEquals(StopReason.TARGET_REACHED, result.stopReason());
			Assert.assertTrue(result.relativeGap() <= target);
			Assert.assertEquals(0L, result.statistics().get("fullyRestored").longValue());
			Assert.assertTrue(result.statistics().get("restoredEqualities") > 0L);
			Assert.assertTrue(result.statistics().get("reusedComponents") > 0L);
			Assert.assertEquals(0L, result.statistics().get("exactCalls").longValue());
			Assert.assertEquals(0L, result.statistics().get("wholePreflightCalls").longValue());
			assertCertificate(fixture, result);
		}
	}

	@Test
	public void hardInfeasibleReplicaProjectionNeverBecomesAnIncumbent() {
		Fixture fixture = forks(1, true);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(fixture.variables, fixture.factors);
		Options options = options(0.001, 64, 60000, 1_000_000);
		IncrementalAnytimeOptimizer.Initialization initialization = IncrementalAnytimeOptimizer.initialize(problem, options);
		Assert.assertFalse(initialization.hasProjectedSeed());
		Result result = IncrementalAnytimeOptimizer.optimize(problem, fixture.seed, options, ignored -> { }, initialization);
		Assert.assertTrue(result.targetReached());
		Assert.assertEquals(0L, result.statistics().get("projectionSeedAvailable").longValue());
		Assert.assertTrue(result.statistics().get("projectionCacheHits") > 0L);
		assertCertificate(fixture, result);
	}

	@Test
	public void compactionRetainsFixedDecisionCostsAndOriginalAssignment() {
		Fixture fixture = forks(3, false);
		Variable fixed = new Variable("fixed-original", 1);
		List<Variable> variables = new ArrayList<>(fixture.variables);
		List<Factor> factors = new ArrayList<>(fixture.factors);
		List<Integer> seed = new ArrayList<>(fixture.seed);
		variables.add(fixed); factors.add(Factor.dense(List.of(fixed), 7d)); seed.add(0);
		Fixture withFixed = new Fixture(variables, factors, seed);
		Result result = solve(withFixed, options(0.03, 64, 60000, 1_000_000));
		Assert.assertTrue(result.targetReached());
		Assert.assertEquals(variables.size(), result.assignment().size());
		Assert.assertTrue(result.statistics().get("compactVariables") < variables.size());
		assertCertificate(withFixed, result);
	}

	@Test
	public void exhaustedWorkAndStepBudgetsKeepValidMisses() {
		Fixture fixture = forks(3, false);
		Result limited = solve(fixture, options(0.03, 64, 60000, 1));
		Assert.assertEquals(StopReason.RESOURCE_LIMIT, limited.stopReason());
		Assert.assertFalse(limited.targetReached());
		Assert.assertEquals(0d, limited.lowerBound(), 0d);
		assertCertificate(fixture, limited);
		Result stepped = solve(fixture, options(0.001, 1, 60000, 1_000_000));
		Assert.assertEquals(StopReason.STEP_LIMIT, stepped.stopReason());
		Assert.assertFalse(stepped.targetReached());
		assertCertificate(fixture, stepped);
	}

	@Test
	public void expiredGenericSchedulingBudgetDoesNotStartComponentWork() {
		Fixture fixture = forks(3, false);
		Result result = solve(fixture, options(0.03, 64, 0, 1_000_000));
		Assert.assertEquals(StopReason.TIME_BUDGET, result.stopReason());
		Assert.assertEquals(0L, result.statistics().get("boundCalls").longValue());
		Assert.assertEquals(fixture.seed, result.assignment());
		assertCertificate(fixture, result);
	}

	@Test
	public void resourceLimitedProbeDoesNotAbandonOtherRefinements() {
		Variable shared = new Variable("large-shared", 10);
		Variable left = new Variable("large-left", 10);
		Variable right = new Variable("large-right", 10);
		Variable small = new Variable("small-shared", 2);
		Variable a = new Variable("small-left", 2);
		Variable b = new Variable("small-right", 2);
		double[] coupling = new double[100];
		double[] preferFirst = new double[10], preferLast = new double[10];
		for(int i = 0; i < 10; i++) {
			preferFirst[i] = i == 0 ? 0d : 4d;
			preferLast[i] = i == 9 ? 0d : 4d;
			for(int j = 0; j < 10; j++)
				coupling[i * 10 + j] = i == j ? 0d : 10d;
		}
		Fixture fixture = new Fixture(List.of(shared, left, right, small, a, b), List.of(
			Factor.dense(List.of(shared, left), coupling),
			Factor.dense(List.of(shared, right), coupling),
			Factor.dense(List.of(left), preferFirst),
			Factor.dense(List.of(right), preferLast),
			Factor.dense(List.of(small, a), 0d, 1d, 1d, 0d),
			Factor.dense(List.of(small, b), 0d, 1d, 1d, 0d)),
			List.of(0, 0, 0, 0, 0, 0));
		Options oneProbe = new Options(Algorithm.ANYTIME_INCREMENTAL,
			options(0.03, 8, 60000, 110).common(), 8, 1, 3, 64, 0, 110, 0);
		Result result = solve(fixture, oneProbe);
		Assert.assertEquals(StopReason.RESOURCE_LIMIT, result.stopReason());
		Assert.assertFalse(result.targetReached());
		Assert.assertTrue(result.statistics().get("componentResourceSkips") > 0L);
		Assert.assertTrue("another candidate must run after the first resource failure",
			result.statistics().get("restoredEqualities") > 0L);
		Assert.assertTrue(result.statistics().get("boundActions") > 1L);
		assertCertificate(fixture, result);
	}

	private static Options options(double target, int steps, long millis, long work) {
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			2, 2, 8, 2, 8, millis, 0, target, true, true,
			CertifiedRegionalOptimizer.ExpansionPolicy.DISAGREEMENT, 20260908, LIMITS);
		return new Options(Algorithm.ANYTIME_INCREMENTAL, common, steps, 2, 3, 64, 0, work, 0);
	}

	private static Result solve(Fixture fixture, Options options) {
		return RegionalSearchOptimizer.optimize(fixture.variables, fixture.factors, fixture.seed, options);
	}

	private static void assertCertificate(Fixture fixture, Result result) {
		double optimum = Double.POSITIVE_INFINITY;
		int states = 1;
		for(Variable variable : fixture.variables)
			states = Math.multiplyExact(states, variable.domainSize());
		for(int ordinal = 0; ordinal < states; ordinal++) {
			List<Integer> assignment = new ArrayList<>();
			int remaining = ordinal;
			for(Variable variable : fixture.variables) {
				assignment.add(remaining % variable.domainSize());
				remaining /= variable.domainSize();
			}
			optimum = Math.min(optimum, CertifiedRegionalOptimizer.evaluate(fixture.variables, fixture.factors, assignment));
		}
		double previousLower = 0d, previousUpper = Double.POSITIVE_INFINITY;
		for(RegionalSearchOptimizer.Checkpoint row : result.checkpoints()) {
			Assert.assertTrue(row.toString(), row.lowerBound() <= optimum && optimum <= row.upperBound());
			Assert.assertTrue(row.lowerBound() >= previousLower);
			Assert.assertTrue(row.upperBound() <= previousUpper);
			Assert.assertEquals(row.upperBound(), CertifiedRegionalOptimizer.evaluate(
				fixture.variables, fixture.factors, row.assignment()), 0d);
			previousLower = row.lowerBound(); previousUpper = row.upperBound();
		}
	}

	private static Fixture forks(int count, boolean hard) {
		List<Variable> variables = new ArrayList<>();
		List<Factor> factors = new ArrayList<>();
		List<Integer> seed = new ArrayList<>();
		factors.add(Factor.dense(List.of(), 200d));
		for(int index = 0; index < count; index++) {
			Variable shared = new Variable("fork" + index + "-shared", 2);
			Variable left = new Variable("fork" + index + "-left", 2);
			Variable right = new Variable("fork" + index + "-right", 2);
			variables.addAll(List.of(shared, left, right)); seed.addAll(List.of(0, 0, 0));
			double mismatch = hard ? Double.POSITIVE_INFINITY : 10d;
			factors.add(Factor.dense(List.of(shared, left), 0d, mismatch, mismatch, 0d));
			factors.add(Factor.dense(List.of(shared, right), 0d, mismatch, mismatch, 0d));
			factors.add(Factor.dense(List.of(left), 0d, 4d));
			factors.add(Factor.dense(List.of(right), 4d, 0d));
		}
		return new Fixture(variables, factors, seed);
	}

	private record Fixture(List<Variable> variables, List<Factor> factors, List<Integer> seed) { }
}
