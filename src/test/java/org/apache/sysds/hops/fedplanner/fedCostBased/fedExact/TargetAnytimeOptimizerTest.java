/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CertifiedRegionalOptimizer.ExpansionPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Algorithm;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Options;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.Result;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.RegionalSearchOptimizer.StopReason;
import org.junit.Assert;
import org.junit.Test;

public class TargetAnytimeOptimizerTest {
	private static final Limits LIMITS = new Limits(100_000L, 1_000_000L);

	@Test
	public void targetAnytimeProgressesPastLegacyRoundAndStepLimits() {
		Fixture fixture = frustratedClique();
		Result result = solve(fixture, common(1, 1, 1, 4, true), 1, 0L, 100_000L);
		Assert.assertEquals(Algorithm.ANYTIME_TARGET, result.algorithm());
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertEquals(10d, result.lowerBound(), 0d);
		Assert.assertEquals(10d, result.upperBound(), 0d);
		Assert.assertTrue(result.statistics().get("coverageAttempts") > 1L);
		Assert.assertTrue(result.statistics().get("steps") > 1L);
		Assert.assertEquals(1L, result.statistics().get("boundCalls").longValue());
		List<Integer> sizes = result.checkpoints().stream()
			.filter(row -> row.phase().equals("ANYTIME_TARGET_REGION")
				|| row.phase().equals("ANYTIME_TARGET_GLOBAL_EXACT"))
			.map(row -> Integer.parseInt(row.details().replaceFirst(".*region=([0-9]+).*", "$1"))).toList();
		Assert.assertEquals(List.of(1, 3, 4), sizes);
		assertCertificate(fixture, result);
	}

	@Test
	public void plateauSuspendsFurtherWidthsWithoutSuppressingPrimalCoverage() {
		Fixture fixture = frustratedClique();
		Result result = solve(fixture, common(1, 3, 1, 4, true), 1, 0L, 100_000L);
		Assert.assertTrue(result.statistics().get("boundWidthPasses") >= 1L);
		Assert.assertTrue(result.statistics().get("boundWidthSuspensions") >= 1L);
		Assert.assertTrue(result.statistics().get("coverageAttempts") >= 1L);
		Assert.assertTrue(result.statistics().get("regionCalls") >= 1L);
		Assert.assertTrue(TargetAnytimeOptimizer.meaningfulBoundGain(1d, 100d, 0d));
		Assert.assertFalse(TargetAnytimeOptimizer.meaningfulBoundGain(0d, 100d, 0d));
		Assert.assertFalse(TargetAnytimeOptimizer.meaningfulBoundGain(0.00001d, 100d, 0d));
		assertCertificate(fixture, result);
	}

	@Test
	public void deferredWidthIsRetriedAfterRegionalCoverageStops() {
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		Variable c = new Variable("c", 2);
		Fixture fixture = new Fixture(List.of(a, b, c),
			List.of(pair(a, b, false), pair(b, c, false), pair(a, c, true)), List.of(0, 0, 0), 10d);
		Result result = solve(fixture, common(1, 3, 1, 1, true), 1, 0L, 100_000L);
		Assert.assertTrue(result.targetReached());
		Assert.assertTrue(result.statistics().get("boundWidthResumptions") >= 1L);
		Assert.assertTrue(result.checkpoints().stream().anyMatch(row ->
			row.phase().equals("ANYTIME_TARGET_BOUND_RESUMED") && row.details().contains("nextWidth=3")));
		assertCertificate(fixture, result);
	}

	@Test
	public void admittedWholePreflightClosesBeforeCoverage() {
		Fixture fixture = frustratedClique();
		Result result = solve(fixture, common(1, 1, 1, 4, true), 1, Long.MAX_VALUE, 100_000L);
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertEquals(1L, result.statistics().get("wholePreflightCalls").longValue());
		Assert.assertEquals(1L, result.statistics().get("exactCalls").longValue());
		Assert.assertEquals(1L, result.statistics().get("wholeClosureAttempts").longValue());
		Assert.assertEquals(1L, result.statistics().get("wholeClosureCompleted").longValue());
		Assert.assertEquals(0L, result.statistics().get("coverageAttempts").longValue());
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(row -> row.phase().equals("ANYTIME_TARGET_EXACT_ADMITTED")
				&& row.details().contains("assignments=")));
		assertCertificate(fixture, result);
	}

	@Test
	public void initialTargetReturnsWithoutBoundOrExactWork() {
		Variable x = new Variable("zero", 2);
		Fixture fixture = new Fixture(List.of(x), List.of(Factor.dense(List.of(x), 0d, 5d)), List.of(0), 0d);
		Result result = solve(fixture, common(1, 2, 1, 1, true), 1, Long.MAX_VALUE, 100_000L);
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertEquals(0L, result.statistics().get("boundCalls").longValue());
		Assert.assertEquals(0L, result.statistics().get("wholePreflightCalls").longValue());
		Assert.assertEquals(0L, result.statistics().get("regionCalls").longValue());
	}

	@Test
	public void regionCapReturnsHonestCertificateWithoutClaimingTarget() {
		Fixture fixture = frustratedClique();
		Result result = solve(fixture, common(1, 1, 1, 1, false), 1, 0L, 100_000L);
		Assert.assertEquals(StopReason.REGION_LIMIT, result.stopReason());
		Assert.assertFalse(result.targetReached());
		Assert.assertTrue(result.lowerBound() <= fixture.optimum());
		Assert.assertTrue(fixture.optimum() <= result.upperBound());
		Assert.assertEquals(1L, result.statistics().get("coverageVariables").longValue());
		assertCertificate(fixture, result);
	}

	@Test
	public void rejectedRegionDoesNotDisableLargerCoverageAttempt() {
		Fixture fixture = frustratedClique();
		Result result = solve(fixture, common(1, 1, 1, 4, false), 1, 0L, 1L);
		Assert.assertEquals(StopReason.RESOURCE_LIMIT, result.stopReason());
		Assert.assertTrue(result.statistics().get("regionWorkSkips") > 1L);
		Assert.assertTrue(result.statistics().get("coverageAttempts") > 1L);
		Assert.assertEquals(4L, result.statistics().get("coverageVariables").longValue());
		assertCertificate(fixture, result);
	}

	@Test
	public void hardResourceWholePreflightStillAllowsAffordableRegionalWork() {
		Fixture fixture = frustratedClique();
		Limits narrow = new Limits(4L, 1_000L);
		Result result = solve(fixture, common(1, 1, 1, 1, false, narrow), 1,
			Long.MAX_VALUE, 100_000L);
		Assert.assertEquals(1L, result.statistics().get("wholeHardResourceSkips").longValue());
		Assert.assertEquals(1L, result.statistics().get("regionCalls").longValue());
		Assert.assertEquals(StopReason.REGION_LIMIT, result.stopReason());
		assertCertificate(fixture, result);
	}

	@Test
	public void zeroBudgetStopsBeforeInitialBoundAndTargetPolicyWork() {
		Fixture fixture = frustratedClique();
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			1, 2, 1, 1, 4, 0L, 0d, 0d, true, true,
			ExpansionPolicy.DISAGREEMENT, 20260908L, LIMITS);
		Result result = solve(fixture, common, 1, Long.MAX_VALUE, 100_000L);
		Assert.assertEquals(StopReason.TIME_BUDGET, result.stopReason());
		Assert.assertEquals(0L, result.statistics().get("wholePreflightCalls").longValue());
		Assert.assertEquals(fixture.seed(), result.assignment());
		assertCertificate(fixture, result);
	}

	@Test
	public void legacyOptimizerStillHonorsItsSingleRoundLimit() {
		Fixture fixture = frustratedClique();
		CertifiedRegionalOptimizer.Result legacy = CertifiedRegionalOptimizer.optimize(
			fixture.variables(), fixture.factors(), fixture.seed(), common(1, 1, 1, 4, false));
		Assert.assertEquals(CertifiedRegionalOptimizer.StopReason.ITERATION_LIMIT, legacy.stopReason());
		Assert.assertEquals(1L, legacy.checkpoints().stream()
			.filter(row -> row.phase().equals("REGION")).count());
	}

	private static Result solve(Fixture fixture, CertifiedRegionalOptimizer.Options common,
		int maxSteps, long exactClosureAssignments, long regionWorkLimit) {
		Options options = new Options(Algorithm.ANYTIME_TARGET, common, maxSteps, 1, 1,
			16, exactClosureAssignments, regionWorkLimit);
		return RegionalSearchOptimizer.optimize(fixture.variables(), fixture.factors(), fixture.seed(), options);
	}

	private static CertifiedRegionalOptimizer.Options common(int initialWidth, int maximumWidth,
		int rounds, int maximumRegion, boolean refineBound) {
		return common(initialWidth, maximumWidth, rounds, maximumRegion, refineBound, LIMITS);
	}

	private static CertifiedRegionalOptimizer.Options common(int initialWidth, int maximumWidth,
		int rounds, int maximumRegion, boolean refineBound, Limits limits) {
		return new CertifiedRegionalOptimizer.Options(initialWidth, maximumWidth, rounds, 1, maximumRegion,
			60_000L, 0d, 0d, refineBound, true, ExpansionPolicy.DISAGREEMENT, 20260908L, limits);
	}

	private static void assertCertificate(Fixture fixture, Result result) {
		double lower = 0d;
		double upper = Double.POSITIVE_INFINITY;
		for(RegionalSearchOptimizer.Checkpoint checkpoint : result.checkpoints()) {
			Assert.assertTrue(checkpoint.toString(), checkpoint.lowerBound() <= fixture.optimum());
			Assert.assertTrue(checkpoint.toString(), fixture.optimum() <= checkpoint.upperBound());
			Assert.assertTrue(checkpoint.lowerBound() >= lower);
			Assert.assertTrue(checkpoint.upperBound() <= upper);
			Assert.assertEquals(checkpoint.upperBound(), CertifiedRegionalOptimizer.evaluate(
				fixture.variables(), fixture.factors(), checkpoint.assignment()), 0d);
			lower = checkpoint.lowerBound();
			upper = checkpoint.upperBound();
		}
	}

	private static Fixture frustratedClique() {
		List<Variable> variables = new ArrayList<>();
		for(int i = 0; i < 4; i++)
			variables.add(new Variable("x" + i, 2));
		List<Factor> factors = new ArrayList<>();
		for(int i = 0; i < variables.size(); i++)
			for(int j = i + 1; j < variables.size(); j++)
				factors.add(pair(variables.get(i), variables.get(j), i == 0 && j == 3));
		double optimum = ExactCategoricalSolver.solve(variables, factors, LIMITS).objective();
		return new Fixture(variables, factors, List.of(0, 0, 0, 0), optimum);
	}

	private static Factor pair(Variable left, Variable right, boolean unequal) {
		return unequal ? Factor.dense(List.of(left, right), 10d, 0d, 0d, 10d)
			: Factor.dense(List.of(left, right), 0d, 10d, 10d, 0d);
	}

	private record Fixture(List<Variable> variables, List<Factor> factors,
		List<Integer> seed, double optimum) { }
}
