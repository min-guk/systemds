/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class AdaptiveThresholdOptimizerTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 10_000_000);

	@Test
	public void thresholdSearchPublishesMonotoneCertifiedPathAndReplicaIdentity() {
		var producer = variable("producer", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(producer, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			pairEquality(producer, left, 10d), pairEquality(producer, right, 10d),
			ExactCategoricalSolver.Factor.dense(List.of(left), 0d, 4d),
			ExactCategoricalSolver.Factor.dense(List.of(right), 4d, 0d));
		RegionalSearchOptimizer.Result result = RegionalSearchOptimizer.optimize(
			variables, factors, List.of(0, 0, 0), options(GENEROUS));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		Assert.assertTrue(result.lowerBound() <= optimum);
		Assert.assertTrue(optimum <= result.upperBound());
		double lower = 0d;
		double upper = Double.POSITIVE_INFINITY;
		for(RegionalSearchOptimizer.Checkpoint checkpoint : result.checkpoints()) {
			Assert.assertTrue(checkpoint.lowerBound() >= lower);
			Assert.assertTrue(checkpoint.upperBound() <= upper);
			Assert.assertTrue(checkpoint.lowerBound() <= optimum);
			Assert.assertTrue(optimum <= checkpoint.upperBound());
			lower = checkpoint.lowerBound();
			upper = checkpoint.upperBound();
		}
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(checkpoint -> checkpoint.details().contains("partition=mb-replica-v1-")));
		Assert.assertTrue(result.statistics().get("replicaVariables") >= variables.size());
		Assert.assertTrue(result.statistics().get("boundActions") > 0);
		assertCoverageRule(result.checkpoints(), 1);
	}

	@Test
	public void replicaResourceFailureDoesNotDiscardMbeCertificate() {
		var split = variable("split", 10);
		var left = variable("left", 2);
		var right = variable("right", 2);
		double[] leftCost = new double[20];
		double[] rightCost = new double[20];
		for(int x = 0; x < 10; x++) {
			leftCost[2 * x] = x == 0 ? 0d : 20d;
			leftCost[2 * x + 1] = x == 0 ? 5d : 20d;
			rightCost[2 * x] = x == 1 ? 0d : 20d;
			rightCost[2 * x + 1] = x == 1 ? 5d : 20d;
		}
		List<ExactCategoricalSolver.Variable> variables = List.of(split, left, right);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(split, left), leftCost),
			ExactCategoricalSolver.Factor.dense(List.of(split, right), rightCost));
		ExactCategoricalSolver.Limits equalityTooLarge =
			new ExactCategoricalSolver.Limits(50, 1_000);
		RegionalSearchOptimizer.Result result = RegionalSearchOptimizer.optimize(
			variables, factors, List.of(0, 0, 0), options(equalityTooLarge));
		double optimum = ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
		Assert.assertTrue(result.statistics().get("resourceFailures") > 0);
		Assert.assertTrue(result.lowerBound() <= optimum);
		Assert.assertTrue(optimum <= result.upperBound());
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(checkpoint -> checkpoint.phase().contains("RESOURCE")));
	}

	private static RegionalSearchOptimizer.Options options(ExactCategoricalSolver.Limits limits) {
		CertifiedRegionalOptimizer.Options common = new CertifiedRegionalOptimizer.Options(
			1, 1, 12, 1, 3, 10_000, 0d, 0d, true, true,
			CertifiedRegionalOptimizer.ExpansionPolicy.DISAGREEMENT, 71L, limits);
		return new RegionalSearchOptimizer.Options(RegionalSearchOptimizer.Algorithm.THRESHOLD,
			common, 30, 2, 1, 100, 100_000);
	}

	private static void assertCoverageRule(List<RegionalSearchOptimizer.Checkpoint> checkpoints,
		int coveragePeriod) {
		int lowerActions = 0;
		for(RegionalSearchOptimizer.Checkpoint checkpoint : checkpoints) {
			if(checkpoint.phase().equals("THRESHOLD_LOWER"))
				lowerActions++;
			else if(checkpoint.phase().equals("THRESHOLD_PRIMAL")) {
				if(lowerActions >= coveragePeriod)
					Assert.assertTrue(checkpoint.details().contains("forced=true"));
				lowerActions = 0;
			}
		}
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor pairEquality(
		ExactCategoricalSolver.Variable left, ExactCategoricalSolver.Variable right, double mismatch) {
		return ExactCategoricalSolver.Factor.dense(List.of(left, right), 0d, mismatch, mismatch, 0d);
	}
}
