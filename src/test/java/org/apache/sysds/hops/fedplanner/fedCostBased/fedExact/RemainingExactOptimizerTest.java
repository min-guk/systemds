/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
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

public class RemainingExactOptimizerTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 10_000_000);
	private static final String PREFIX = CertifiedRegionalOptimizer.PROPERTY_PREFIX;

	@Test
	public void regionDualInitialBoundIsSoundAndZeroBudgetFallsBackWithFullCoverage() {
		String oldKind = System.getProperty(PREFIX + "initialBound");
		String oldBudget = System.getProperty(PREFIX + "regionDualMillis");
		try {
			System.setProperty(PREFIX + "initialBound", "region-dual");
			for(String budget : List.of("0", "1000")) {
				System.setProperty(PREFIX + "regionDualMillis", budget);
				Fixture fixture = conflictingFork("dual-" + budget, 2, 10d, 4d, 2.5d);
				Result result = solve(fixture, 0d, 1_000_000);
				assertCertificateContainsOracle(fixture, result);
				Assert.assertEquals(fixture.optimum, result.upperBound(), 1e-10);
				Assert.assertTrue(result.checkpoints().stream().anyMatch(row ->
					row.phase().equals("INITIAL_BOUND") && row.details().contains("initialBound=region-dual")));
				Assert.assertTrue(result.checkpoints().stream().anyMatch(row ->
					row.phase().equals("FALLBACK_REPLICA_BOUND")));
			}
		}
		finally {
			if(oldKind == null) System.clearProperty(PREFIX + "initialBound");
			else System.setProperty(PREFIX + "initialBound", oldKind);
			if(oldBudget == null) System.clearProperty(PREFIX + "regionDualMillis");
			else System.setProperty(PREFIX + "regionDualMillis", oldBudget);
		}
	}

	@Test
	public void reducedRootRetainsSingletonVariablesAndExpandsOriginalRepresentatives() {
		Variable forced = variable("forced", 3);
		Variable free = variable("free", 2);
		List<Variable> variables = List.of(forced, free);
		List<Factor> factors = List.of(
			Factor.dense(List.of(forced), Double.POSITIVE_INFINITY, 2d,
				Double.POSITIVE_INFINITY),
			Factor.dense(List.of(free), 1d, 0d));

		ExactPhysicalReducedSolver.CompactModel reduced =
			ExactPhysicalReducedSolver.reducedModel(2, variables, factors, GENEROUS);

		Assert.assertEquals("remaining-exact must retain every encoded variable",
			variables.size(), reduced.variables().size());
		Assert.assertEquals(1, reduced.variables().get(0).domainSize());
		Assert.assertEquals(2, reduced.variables().get(1).domainSize());
		Assert.assertEquals(List.of(1, 1), reduced.expandAssignment(List.of(0, 1)));
	}

	@Test
	public void zeroToleranceRestoresAllCouplingsAndMatchesIndependentExactOracle() {
		Fixture fixture = conflictingFork("exact", 2, 10d, 4d, 2.5d);
		Result result = solve(fixture, 0d, 1_000_000);

		Assert.assertEquals(Algorithm.REMAINING_EXACT, result.algorithm());
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertTrue(result.targetReached());
		Assert.assertEquals(fixture.optimum, result.lowerBound(), Math.ulp(fixture.optimum) * 16);
		Assert.assertEquals(fixture.optimum, result.upperBound(), Math.ulp(fixture.optimum) * 16);
		Assert.assertEquals(fixture.optimum, ExactCategoricalSolver.evaluate(
			fixture.variables, fixture.factors, GENEROUS, result.assignment()),
			Math.ulp(fixture.optimum) * 16);
		Assert.assertEquals(0L, result.statistics().get("rootSingletonCompaction").longValue());
		Assert.assertEquals(fixture.variables.size(),
			result.statistics().get("rootRetainedVariables").longValue());
		Assert.assertEquals(1L, result.statistics().get("fullyRestored").longValue());
		Assert.assertEquals(1L, result.statistics().get("remainingClosureCompleted").longValue());
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(row -> row.phase().equals("INITIAL_BOUND")
				&& row.lowerBound() <= fixture.optimum));
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(row -> row.phase().equals("REMAINING_EXACT")
				&& row.details().contains("allReplicaEqualitiesRestored=true")));
	}

	@Test
	public void fivePercentTargetAtOrBelowThresholdSkipsExactClosure() {
		Fixture fixture = conflictingFork("target-below", 2, 10d, 4d, 500d);
		Result result = solve(fixture, 0.05d, 1_000_000);
		RegionalSearchOptimizer.Checkpoint initialBound = result.checkpoints().stream()
			.filter(row -> row.phase().equals("INITIAL_BOUND"))
			.findFirst().orElseThrow();

		Assert.assertEquals(StopReason.TARGET_REACHED, result.stopReason());
		Assert.assertTrue(result.targetReached());
		Assert.assertTrue(initialBound.relativeGap() <= 0.05d);
		assertCertificateContainsOracle(fixture, result);
		Assert.assertEquals(0L, result.statistics().get("remainingClosureAttempts").longValue());
		Assert.assertEquals(0L, result.statistics().get("remainingClosureCompleted").longValue());
		Assert.assertEquals(List.of("INITIAL", "INITIAL_BOUND", "STOP_TARGET_REACHED"),
			result.checkpoints().stream().map(RegionalSearchOptimizer.Checkpoint::phase).toList());
	}

	@Test
	public void fivePercentTargetAboveThresholdRunsSingleDirectClosure() {
		Fixture fixture = conflictingFork("target-above", 2, 10d, 4d, 400d);
		Result result = solve(fixture, 0.05d, 1_000_000);
		RegionalSearchOptimizer.Checkpoint initialBound = result.checkpoints().stream()
			.filter(row -> row.phase().equals("INITIAL_BOUND"))
			.findFirst().orElseThrow();

		Assert.assertTrue(initialBound.relativeGap() > 0.05d);
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertTrue(result.targetReached());
		Assert.assertEquals(1L, result.statistics().get("remainingClosureAttempts").longValue());
		Assert.assertEquals(1L, result.statistics().get("remainingClosureCompleted").longValue());
		Assert.assertEquals(List.of("INITIAL", "INITIAL_BOUND", "REMAINING_EXACT", "STOP_GLOBAL_EXACT"),
			result.checkpoints().stream().map(RegionalSearchOptimizer.Checkpoint::phase).toList());
		Assert.assertEquals(fixture.optimum, result.lowerBound(), Math.ulp(fixture.optimum) * 16);
		Assert.assertEquals(fixture.optimum, result.upperBound(), Math.ulp(fixture.optimum) * 16);
		Assert.assertEquals(fixture.optimum, ExactCategoricalSolver.evaluate(
			fixture.variables, fixture.factors, GENEROUS, result.assignment()),
			Math.ulp(fixture.optimum) * 16);
	}

	@Test
	public void remainingExactReusesReducedRootAcrossRuns() {
		Fixture fixture = conflictingFork("shared-root", 2, 10d, 4d, 0d);
		RegionalSearchProblem problem = RegionalSearchProblem.generic(fixture.variables, fixture.factors);
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(GENEROUS);

		Assert.assertTrue(problem.hasReducedRoot());
		Assert.assertEquals(1, problem.reducedRootRequests());
		Result result = solve(problem, fixture, 0d, 1_000_000);

		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertEquals(1L, result.statistics().get("rootReductionReused").longValue());
		Assert.assertEquals(2L, result.statistics().get("sharedRootRequests").longValue());
		Assert.assertEquals(2, problem.reducedRootRequests());
		Assert.assertSame(root, problem.reducedRoot(GENEROUS));
		Assert.assertEquals(3, problem.reducedRootRequests());
	}

	@Test
	public void remainingExactConfigurationDefaultsToFivePercentAndHonorsExplicitOverride() {
		String oldMode = System.getProperty(PREFIX + "mode");
		String oldAlgorithm = System.getProperty(PREFIX + "algorithm");
		String oldRelativeGap = System.getProperty(PREFIX + "relativeGap");
		try {
			System.setProperty(PREFIX + "mode", "anytime");
			System.setProperty(PREFIX + "algorithm", "remaining-exact");
			System.clearProperty(PREFIX + "relativeGap");
			Assert.assertEquals(0.05d,
				CertifiedRegionalOptimizer.Options.configured().relativeTolerance(), 0d);

			System.setProperty(PREFIX + "relativeGap", "0.025");
			Assert.assertEquals(0.025d,
				CertifiedRegionalOptimizer.Options.configured().relativeTolerance(), 0d);
		}
		finally {
			restore(PREFIX + "mode", oldMode);
			restore(PREFIX + "algorithm", oldAlgorithm);
			restore(PREFIX + "relativeGap", oldRelativeGap);
		}
	}

	@Test
	public void structuralPoliciesPreserveEveryCheckpointAndExactAssignment() {
		Fixture fixture = conflictingFork("policy-exact", 2, 10d, 4d, 2.5d);
		for(MiniBucketLowerBound.EliminationOrder order : MiniBucketLowerBound.EliminationOrder.values())
			for(MiniBucketLowerBound.PartitionStrategy grouping : MiniBucketLowerBound.PartitionStrategy.values()) {
				MiniBucketLowerBound.PlanningPolicy policy = new MiniBucketLowerBound.PlanningPolicy(order, grouping);
				CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
					2, 60_000L, 0d, 0d, GENEROUS);
				Result result = RegionalSearchOptimizer.optimize(fixture.variables, fixture.factors, fixture.seed,
					new Options(Algorithm.REMAINING_EXACT, common, 1_000_000L, 0L, 1000, policy));
				Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
				Assert.assertEquals(Double.doubleToRawLongBits(fixture.optimum),
					Double.doubleToRawLongBits(ExactCategoricalSolver.evaluate(
						fixture.variables, fixture.factors, GENEROUS, result.assignment())));
				double lower = 0d, upper = Double.POSITIVE_INFINITY;
				for(RegionalSearchOptimizer.Checkpoint point : result.checkpoints()) {
					Assert.assertTrue(point.lowerBound() >= lower && point.lowerBound() <= fixture.optimum);
					Assert.assertTrue(point.upperBound() <= upper && point.upperBound() >= fixture.optimum);
					lower = point.lowerBound();
					upper = point.upperBound();
				}
				String details = result.checkpoints().stream().filter(p -> p.phase().equals("INITIAL_BOUND"))
					.findFirst().orElseThrow().details();
				Assert.assertTrue(details.contains("lbOrder=" + order));
				Assert.assertTrue(details.contains("lbPartition=" + grouping));
			}
	}

	@Test
	public void structuralPolicyOptionsDefaultToLegacyAndParseExplicitPolicies() {
		String oldOrder = System.getProperty(PREFIX + "lbOrder");
		String oldGrouping = System.getProperty(PREFIX + "lbPartition");
		String oldAlgorithm = System.getProperty(PREFIX + "algorithm");
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			2, 60_000L, 0d, 0.05d, GENEROUS);
		try {
			System.setProperty(PREFIX + "algorithm", "remaining-exact");
			System.clearProperty(PREFIX + "lbOrder");
			System.clearProperty(PREFIX + "lbPartition");
			Assert.assertEquals(new Options(Algorithm.REMAINING_EXACT, common, 1000).lbPlanning(),
				Options.configured(common).lbPlanning());
			System.setProperty(PREFIX + "lbOrder", "weighted-min-fill");
			System.setProperty(PREFIX + "lbPartition", "best-fit");
			Assert.assertEquals(MiniBucketLowerBound.EliminationOrder.WEIGHTED_MIN_FILL,
				Options.configured(common).lbPlanning().eliminationOrder());
			Assert.assertEquals(MiniBucketLowerBound.PartitionStrategy.BEST_FIT,
				Options.configured(common).lbPlanning().partitionStrategy());
			System.setProperty(PREFIX + "lbOrder", "unknown");
			try {
				Options.configured(common);
				Assert.fail("Unknown policy accepted");
			}
			catch(IllegalArgumentException expected) { /* Explicit invalid configuration. */ }
		}
		finally {
			restore(PREFIX + "lbOrder", oldOrder);
			restore(PREFIX + "lbPartition", oldGrouping);
			restore(PREFIX + "algorithm", oldAlgorithm);
		}
	}

	@Test
	public void costShiftingCertifiesAnOptimalSeedWithoutExactClosure() {
		Fixture fixture = conflictingFork("cost-shift-target", 2, 10d, 4d, 0d);
		List<Integer> optimalSeed = List.of(0, 0, 0);
		Assert.assertEquals(fixture.optimum, CertifiedRegionalOptimizer.evaluate(
			fixture.variables, fixture.factors, optimalSeed), 0d);
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			2, 60_000L, 0d, 0.05d, GENEROUS);
		Options options = new Options(Algorithm.REMAINING_EXACT, common, 1_000_000L, 10_000L, 1000);
		Result result = RegionalSearchOptimizer.optimize(
			RegionalSearchProblem.generic(fixture.variables, fixture.factors), optimalSeed, options, ignored -> { });
		Assert.assertEquals(StopReason.TARGET_REACHED, result.stopReason());
		Assert.assertEquals(0L, result.statistics().get("remainingClosureAttempts").longValue());
		Assert.assertTrue(result.statistics().get("costShiftUpdates") > 0);
		Assert.assertTrue(result.checkpoints().stream().filter(x -> x.phase().equals("INITIAL_BOUND"))
			.findFirst().orElseThrow().relativeGap() > 0.05d);
		Assert.assertTrue(result.checkpoints().stream().filter(x -> x.phase().equals("COST_SHIFT"))
			.findFirst().orElseThrow().relativeGap() <= 0.05d);
		double lower = 0d;
		for(RegionalSearchOptimizer.Checkpoint point : result.checkpoints()) {
			Assert.assertTrue(point.lowerBound() >= lower);
			Assert.assertTrue(point.lowerBound() <= fixture.optimum);
			Assert.assertEquals(optimalSeed, point.assignment());
			Assert.assertEquals(fixture.optimum, point.upperBound(), 0d);
			lower = point.lowerBound();
		}
	}

	@Test
	public void costShiftingKeepsExactFallbackWhenSeedCannotMeetTarget() {
		Fixture fixture = conflictingFork("cost-shift-fallback", 2, 10d, 4d, 0d);
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			2, 60_000L, 0d, 0.05d, GENEROUS);
		Options options = new Options(Algorithm.REMAINING_EXACT, common, 1_000_000L, 10_000L, 4);
		Result result = RegionalSearchOptimizer.optimize(
			RegionalSearchProblem.generic(fixture.variables, fixture.factors), fixture.seed, options, ignored -> { });
		Assert.assertEquals(StopReason.GLOBAL_EXACT, result.stopReason());
		Assert.assertEquals(1L, result.statistics().get("costShiftCalls").longValue());
		Assert.assertEquals(1L, result.statistics().get("remainingClosureAttempts").longValue());
		Assert.assertTrue(result.checkpoints().stream().anyMatch(x -> x.phase().equals("COST_SHIFT")));
		Assert.assertEquals(fixture.optimum, result.upperBound(), 0d);
		assertCertificateContainsOracle(fixture, result);
	}

	@Test
	public void costShiftingOptionsAreDisabledByDefaultAndRejectInvalidLimits() {
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			2, 60_000L, 0d, 0.05d, GENEROUS);
		Assert.assertEquals(0L, new Options(Algorithm.REMAINING_EXACT, common, 1000).costShiftMillis());
		for(long budget : List.of(-1L, Long.MIN_VALUE)) {
			try {
				new Options(Algorithm.REMAINING_EXACT, common, 1000, budget, 2);
				Assert.fail("Negative tightening budget accepted");
			}
			catch(IllegalArgumentException expected) { /* Invalid option is explicit. */ }
		}
		try {
			new Options(Algorithm.REMAINING_EXACT, common, 1000, 1, 0);
			Assert.fail("Zero sweep limit accepted");
		}
		catch(IllegalArgumentException expected) { /* Invalid option is explicit. */ }
	}

	@Test
	public void closureResourceLimitReturnsHonestIncompleteCertificate() {
		Fixture fixture = conflictingFork("limited", 10, 10d, 4d, 0d);
		Result result = solve(fixture, 0d, 110);

		Assert.assertEquals(StopReason.RESOURCE_LIMIT, result.stopReason());
		Assert.assertFalse(result.targetReached());
		Assert.assertTrue(result.lowerBound() <= fixture.optimum);
		Assert.assertTrue(fixture.optimum <= result.upperBound());
		Assert.assertEquals(fixture.seed, result.assignment());
		Assert.assertEquals(1L, result.statistics().get("remainingClosureAttempts").longValue());
		Assert.assertEquals(0L, result.statistics().get("remainingClosureCompleted").longValue());
		Assert.assertEquals(0L, result.statistics().get("fullyRestored").longValue());
		Assert.assertTrue(result.statistics().get("resourceFailures") > 0L);
		Assert.assertTrue(result.checkpoints().stream()
			.noneMatch(row -> row.phase().equals("REMAINING_EXACT")));
	}

	private static Result solve(Fixture fixture, double relativeTolerance, long closureWork) {
		return solve(RegionalSearchProblem.generic(fixture.variables, fixture.factors), fixture,
			relativeTolerance, closureWork);
	}

	private static Result solve(RegionalSearchProblem problem, Fixture fixture,
		double relativeTolerance, long closureWork) {
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			2, 60_000L, 0d, relativeTolerance, GENEROUS);
		Options options = new Options(Algorithm.REMAINING_EXACT, common, closureWork);
		return RegionalSearchOptimizer.optimize(problem, fixture.seed, options, ignored -> { });
	}

	private static void assertCertificateContainsOracle(Fixture fixture, Result result) {
		Assert.assertTrue(result.lowerBound() <= fixture.optimum);
		Assert.assertTrue(fixture.optimum <= result.upperBound());
	}

	private static void restore(String key, String value) {
		if(value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

	private static Fixture conflictingFork(String prefix, int domain, double mismatch,
		double preference, double constant) {
		Variable shared = variable(prefix + "-shared", domain);
		Variable left = variable(prefix + "-left", domain);
		Variable right = variable(prefix + "-right", domain);
		double[] equality = new double[domain * domain];
		double[] preferFirst = new double[domain];
		double[] preferLast = new double[domain];
		for(int first = 0; first < domain; first++) {
			for(int second = 0; second < domain; second++)
				equality[first * domain + second] = first == second ? 0d : mismatch;
			preferFirst[first] = first == 0 ? 0d : preference;
			preferLast[first] = first == domain - 1 ? 0d : preference;
		}
		List<Variable> variables = List.of(shared, left, right);
		List<Factor> factors = List.of(
			Factor.dense(List.of(), constant),
			Factor.dense(List.of(shared, left), equality),
			Factor.dense(List.of(shared, right), equality),
			Factor.dense(List.of(left), preferFirst),
			Factor.dense(List.of(right), preferLast));
		List<Integer> seed = new ArrayList<>();
		seed.add(0);
		seed.add(domain - 1);
		seed.add(domain - 1);
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		return new Fixture(variables, factors, List.copyOf(seed), optimum);
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private record Fixture(List<Variable> variables, List<Factor> factors,
		List<Integer> seed, double optimum) { }
}
