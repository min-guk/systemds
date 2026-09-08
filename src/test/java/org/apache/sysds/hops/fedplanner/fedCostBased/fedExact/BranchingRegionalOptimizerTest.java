/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

public class BranchingRegionalOptimizerTest {
	private static final Limits LIMITS = new Limits(100_000L, 1_000_000L);

	@Test
	public void bothSearchesMaintainCertificatesAndReachExactEndpoint() {
		Fixture fixture = frustratedTriangle();
		for(Algorithm algorithm : List.of(Algorithm.TARGET_GAP, Algorithm.REUSE)) {
			Result result = solve(fixture, algorithm, false, 2, 128, 64);
			Assert.assertTrue(result.stopReason() == StopReason.GLOBAL_EXACT
				|| result.stopReason() == StopReason.TARGET_REACHED);
			Assert.assertTrue(result.targetReached());
			Assert.assertEquals(10d, result.lowerBound(), 0d);
			Assert.assertEquals(10d, result.upperBound(), 0d);
			assertCertificate(fixture, 10d, result);
			Assert.assertTrue(result.statistics().get("cacheHits") > 0);
			Assert.assertTrue(result.statistics().get("probeBoundSuccess") >= result.statistics().get("cacheHits"));
			Assert.assertEquals(0L, result.statistics().get("probeBoundFallback").longValue());
			Assert.assertTrue(result.checkpoints().stream().filter(row -> row.phase().equals("BRANCH_COMMIT"))
				.allMatch(row -> row.details().contains("children=2")));
		}
	}

	@Test
	public void fallbackProbeBoundsCannotBeCountedAsCacheHits() {
		Assert.assertEquals(0L,
			BranchingRegionalOptimizer.reusableProbeBounds(List.of(false, false)));
		Assert.assertEquals(1L,
			BranchingRegionalOptimizer.reusableProbeBounds(List.of(false, true)));
	}

	@Test
	public void targetGapTestsTwoCandidatesWhileReuseTestsOne() {
		Fixture fixture = frustratedTriangle();
		Result target = solve(fixture, Algorithm.TARGET_GAP, false, 2, 128, 64);
		Result reuse = solve(fixture, Algorithm.REUSE, false, 2, 128, 64);
		String targetFirstProbe = target.checkpoints().stream().filter(row -> row.phase().equals("PROBE_COMPLETE"))
			.findFirst().orElseThrow().details();
		String reuseFirstProbe = reuse.checkpoints().stream().filter(row -> row.phase().equals("PROBE_COMPLETE"))
			.findFirst().orElseThrow().details();
		Assert.assertTrue(targetFirstProbe, targetFirstProbe.matches(".*candidates=[^ ]+,[^ ]+.*"));
		Assert.assertFalse(reuseFirstProbe, reuseFirstProbe.matches(".*candidates=[^ ]+,[^ ]+.*"));
		Assert.assertTrue(target.statistics().get("probeVariables") > reuse.statistics().get("probeVariables"));
		Assert.assertEquals(0L, reuse.statistics().get("widthStrengthenings").longValue());
	}

	@Test
	public void targetGapOptionalStrengtheningIsAbsentFromReuse() {
		Fixture fixture = frustratedTriangle();
		Result target = solve(fixture, Algorithm.TARGET_GAP, true, 2, 128, 64);
		Result reuse = solve(fixture, Algorithm.REUSE, true, 2, 128, 64);
		Assert.assertTrue(target.statistics().get("widthStrengthenings") > 0);
		Assert.assertEquals(0L, reuse.statistics().get("widthStrengthenings").longValue());
		assertCertificate(fixture, 10d, target);
		assertCertificate(fixture, 10d, reuse);
	}

	@Test
	public void allCandidatePartitionsCanStrengthenParentButDeferredNodesRemainInAggregate() {
		Assert.assertEquals(7d,
			BranchingRegionalOptimizer.combinePartitionLower(3d, List.of(5d, 7d, 6d)), 0d);
		Assert.assertEquals(Double.POSITIVE_INFINITY,
			BranchingRegionalOptimizer.combinePartitionLower(3d, List.of(Double.POSITIVE_INFINITY)), 0d);
		Assert.assertEquals(4d,
			BranchingRegionalOptimizer.aggregateFrontierLower(12d, List.of(9d, 4d, 10d)), 0d);
		Assert.assertEquals(12d,
			BranchingRegionalOptimizer.aggregateFrontierLower(12d, List.of()), 0d);
	}

	@Test
	public void aNodeBoundAboveItsVerifiedFeasiblePlanIsFatal() {
		IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
			() -> BranchingRegionalOptimizer.validateNodeFeasible(17L, 8d, 7d));
		Assert.assertTrue(failure.getMessage().startsWith("REGIONAL_SEARCH_NODE_BOUND_EXCEEDS_FEASIBLE"));
		BranchingRegionalOptimizer.validateNodeFeasible(17L, 7d, 7d);
	}

	@Test
	public void zeroCostSeedClosesWithoutSearchWork() {
		Variable x = new Variable("x", 2);
		Fixture fixture = new Fixture(List.of(x), List.of(Factor.dense(List.of(x), 0d, 5d)), List.of(0));
		Result result = solve(fixture, Algorithm.REUSE, false, 1, 8, 8);
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertEquals(0d, result.lowerBound(), 0d);
		Assert.assertEquals(0d, result.upperBound(), 0d);
		Assert.assertEquals(0L, result.statistics().get("boundCalls").longValue());
		Assert.assertEquals(0L, result.statistics().get("probes").longValue());
	}

	@Test
	public void frontierLimitStopsBeforeAnIncompleteReplacement() {
		Fixture fixture = frustratedTriangle();
		Result result = solve(fixture, Algorithm.REUSE, false, 1, 16, 1);
		Assert.assertEquals(StopReason.FRONTIER_LIMIT, result.stopReason());
		Assert.assertFalse(result.targetReached());
		Assert.assertEquals(0L, result.statistics().get("branchedNodes").longValue());
		Assert.assertEquals(1L, result.statistics().get("frontier").longValue());
		Assert.assertTrue(result.checkpoints().stream().noneMatch(row -> row.phase().equals("BRANCH_COMMIT")));
		assertCertificate(fixture, 10d, result);
	}

	@Test
	public void randomFiniteModelsEncloseIndependentExactOptimumAtEveryCheckpoint() {
		Random random = new Random(90210L);
		for(int trial = 0; trial < 12; trial++) {
			List<Variable> variables = new ArrayList<>();
			for(int i = 0; i < 4; i++)
				variables.add(new Variable("r" + trial + '-' + i, 2));
			List<Factor> factors = new ArrayList<>();
			for(int i = 0; i < variables.size(); i++) {
				factors.add(Factor.dense(List.of(variables.get(i)), random.nextInt(8), random.nextInt(8)));
				for(int j = i + 1; j < variables.size(); j++)
					if(random.nextBoolean())
						factors.add(Factor.dense(List.of(variables.get(i), variables.get(j)),
							random.nextInt(11), random.nextInt(11), random.nextInt(11), random.nextInt(11)));
			}
			Fixture fixture = new Fixture(variables, factors, List.of(0, 0, 0, 0));
			double optimum = ExactCategoricalSolver.solve(variables, factors, LIMITS).objective();
			for(Algorithm algorithm : List.of(Algorithm.TARGET_GAP, Algorithm.REUSE))
				assertCertificate(fixture, optimum, solve(fixture, algorithm, false, 2, 256, 256));
		}
	}

	@Test
	public void optionalWidthResourceFailureFallsBackToProbeAndRegionalProgress() {
		Variable a = new Variable("wide-a", 2);
		Variable b = new Variable("wide-b", 10);
		Variable c = new Variable("wide-c", 10);
		List<Variable> variables = List.of(a, b, c);
		List<Factor> factors = List.of(
			Factor.lazy(List.of(a, b), value -> value[0] == 1 && value[1] == 0 ? 0d : 1d),
			Factor.lazy(List.of(a, c), value -> value[0] == 1 && value[1] == 0 ? 0d : 1d));
		Fixture fixture = new Fixture(variables, factors, List.of(0, 0, 0));
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(2, 3, 8, 1, 2,
			60_000L, 0d, 0d, true, true, ExpansionPolicy.DISAGREEMENT, 19L, new Limits(100, 100));
		Options options = new Options(Algorithm.TARGET_GAP, common, 64, 2, 3, 256, 0L);
		Result result = RegionalSearchOptimizer.optimize(variables, factors, fixture.seed, options);
		Assert.assertTrue(result.statistics().get("resourceFailures") > 0);
		Assert.assertTrue(result.statistics().get("probes") > 0);
		Assert.assertTrue(result.checkpoints().stream().anyMatch(row ->
			row.phase().equals("NODE_STRENGTHEN_LIMIT") && row.details().contains("fallback=probe")));
		assertCertificate(fixture, 0d, result);
	}

	@Test
	public void selectedChildReusesItsParentsCumulativeRegion() {
		Variable a = new Variable("cycle-a", 2);
		Variable b = new Variable("cycle-b", 2);
		Variable c = new Variable("cycle-c", 2);
		Variable d = new Variable("cycle-d", 2);
		List<Variable> variables = List.of(a, b, c, d);
		List<Factor> factors = List.of(
			Factor.dense(List.of(a, b), 0, 10, 10, 0),
			Factor.dense(List.of(b, c), 0, 10, 10, 0),
			Factor.dense(List.of(c, d), 0, 10, 10, 0),
			Factor.dense(List.of(a, d), 10, 0, 0, 10));
		Fixture fixture = new Fixture(variables, factors, List.of(0, 0, 0, 0));
		Result result = solve(fixture, Algorithm.REUSE, false, 1, 128, 128);
		Assert.assertTrue(result.checkpoints().stream().filter(row -> row.phase().equals("CONDITIONAL_REGION"))
			.anyMatch(row -> row.details().contains("inheritedRegion=2")));
		Assert.assertTrue(result.statistics().get("inheritedRegionVariables") >= 2);
		assertCertificate(fixture, 10d, result);
	}

	private static Result solve(Fixture fixture, Algorithm algorithm, boolean refine,
		int probeCandidates, int maxSteps, int maximumFrontier) {
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(1, 2, 8, 1, 2,
			60_000L, 0d, 0d, refine, true, ExpansionPolicy.DISAGREEMENT, 19L, LIMITS);
		Options options = new Options(algorithm, common, maxSteps, probeCandidates, 3, maximumFrontier, 0L);
		return RegionalSearchOptimizer.optimize(fixture.variables, fixture.factors, fixture.seed, options);
	}

	private static void assertCertificate(Fixture fixture, double optimum, Result result) {
		double previousLower = 0d;
		double previousUpper = Double.POSITIVE_INFINITY;
		for(RegionalSearchOptimizer.Checkpoint checkpoint : result.checkpoints()) {
			Assert.assertTrue(checkpoint.toString(), checkpoint.lowerBound() <= optimum);
			Assert.assertTrue(checkpoint.toString(), checkpoint.upperBound() >= optimum);
			Assert.assertTrue(checkpoint.lowerBound() >= previousLower);
			Assert.assertTrue(checkpoint.upperBound() <= previousUpper);
			Assert.assertEquals(checkpoint.upperBound(), CertifiedRegionalOptimizer.evaluate(
				fixture.variables, fixture.factors, checkpoint.assignment()), 0d);
			previousLower = checkpoint.lowerBound();
			previousUpper = checkpoint.upperBound();
		}
	}

	private static Fixture frustratedTriangle() {
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		Variable c = new Variable("c", 2);
		List<Variable> variables = List.of(a, b, c);
		List<Factor> factors = List.of(
			Factor.dense(List.of(a, b), 0, 10, 10, 0),
			Factor.dense(List.of(b, c), 0, 10, 10, 0),
			Factor.dense(List.of(a, c), 10, 0, 0, 10));
		return new Fixture(variables, factors, List.of(0, 0, 0));
	}

	private record Fixture(List<Variable> variables, List<Factor> factors, List<Integer> seed) { }
}
