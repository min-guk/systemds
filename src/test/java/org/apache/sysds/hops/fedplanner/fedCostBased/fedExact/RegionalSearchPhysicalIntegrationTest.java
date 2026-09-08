/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile/planning only: no federated workers or workload execution. */
public class RegionalSearchPhysicalIntegrationTest {
	@Test
	public void independentlyBuiltModelsHaveStableEncodedFactorOrder() throws Exception {
		String previous = null;
		for(int repetition = 0; repetition < 5; repetition++) {
			Fixture fixture = fixture();
			String fingerprint = RegionalSearchProblem.physical(fixture.model(), fixture.surface(), null).orderFingerprint();
			if(previous != null)
				Assert.assertEquals(previous, fingerprint);
			previous = fingerprint;
		}
	}

	@Test
	public void threeAlgorithmsPreserveAuxiliaryCanonicalAndGlobalEnvelope() throws Exception {
		Fixture fixture = fixture();
		Assert.assertTrue(fixture.surface().exactSolverVariables().size() > fixture.model().variables().size());
		LocalPhysicalOptimizer.Result seed = LocalPhysicalOptimizer.optimize(fixture.model(), fixture.surface(), null, ignored -> { });
		ExactPhysicalOptimizer.Result global = ExactPhysicalOptimizer.optimize(fixture.model(), fixture.surface(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		for(RegionalSearchOptimizer.Algorithm algorithm : RegionalSearchOptimizer.Algorithm.values()) {
			CertifiedRegionalOptimizer.Options common = common(60000);
			RegionalSearchOptimizer.Options options = new RegionalSearchOptimizer.Options(algorithm, common,
				256, 2, 2, 4096, Long.MAX_VALUE);
			LocalPhysicalOptimizer.Result result = LocalPhysicalOptimizer.optimize(fixture.model(), fixture.surface(), common,
				ignored -> { }, options, ignored -> { });
			Assert.assertNull(result.certificate());
			Assert.assertNotNull(result.search());
			Assert.assertEquals(seed.physicalResult().canonicalObjectiveBits(),
				Double.doubleToRawLongBits(result.search().seedUpperBound()));
			Assert.assertTrue(algorithm + " failed exact target", result.search().targetReached());
			Assert.assertEquals(global.canonicalObjectiveBits(), result.physicalResult().canonicalObjectiveBits());
			double lastLower = 0, lastUpper = Double.POSITIVE_INFINITY;
			for(RegionalSearchOptimizer.Checkpoint checkpoint : result.search().checkpoints()) {
				Assert.assertTrue(checkpoint.lowerBound() <= global.solverResult().objective());
				Assert.assertTrue(checkpoint.upperBound() >= global.solverResult().objective());
				Assert.assertTrue(checkpoint.lowerBound() >= lastLower);
				Assert.assertTrue(checkpoint.upperBound() <= lastUpper);
				Assert.assertTrue(Double.isFinite(CertifiedRegionalOptimizer.evaluate(fixture.model().variables(),
					fixture.model().hardFactors(), checkpoint.assignment())));
				Assert.assertEquals(fixture.surface().evaluateCanonical(checkpoint.assignment()),
					Double.doubleToRawLongBits(checkpoint.upperBound()));
				lastLower = checkpoint.lowerBound();
				lastUpper = checkpoint.upperBound();
			}
		}
	}

	@Test
	public void branchConditionLeavesAllAuxiliariesFreeAndRetainsGlobalModel() throws Exception {
		Fixture fixture = fixture();
		RegionalSearchProblem problem = RegionalSearchProblem.physical(fixture.model(), fixture.surface(), null);
		List<Integer> seed = LocalPhysicalOptimizer.optimize(fixture.model(), fixture.surface(), null, ignored -> { })
			.physicalResult().solverResult().assignmentInVariableOrder();
		int[] condition = problem.unconstrained();
		condition[0] = seed.get(0);
		RegionalSearchProblem.Conditional reduced = problem.condition(condition);
		for(int i = problem.decisionCount(); i < problem.variables().size(); i++) {
			ExactCategoricalSolver.Variable auxiliary = problem.variables().get(i);
			Assert.assertTrue(reduced.variables().stream().anyMatch(variable -> variable == auxiliary));
		}
		RegionalSearchProblem.Solution solved = problem.solveWhole(condition, ExactPhysicalOptimizer.PRODUCTION_LIMITS, () -> false);
		Assert.assertTrue(solved.feasible());
		Assert.assertTrue(problem.matches(condition, solved.assignment()));
		Assert.assertTrue(problem.bound(condition, 1, ExactPhysicalOptimizer.PRODUCTION_LIMITS, () -> false).lowerBound()
			<= solved.objective());
	}

	@Test
	public void zeroSchedulingBudgetReturnsVerifiedSeedForEveryAlgorithm() throws Exception {
		Fixture fixture = fixture();
		for(RegionalSearchOptimizer.Algorithm algorithm : RegionalSearchOptimizer.Algorithm.values()) {
			CertifiedRegionalOptimizer.Options common = common(0);
			RegionalSearchOptimizer.Result result = LocalPhysicalOptimizer.optimize(fixture.model(), fixture.surface(), common,
				ignored -> { }, new RegionalSearchOptimizer.Options(algorithm, common, 10, 2, 2, 32, 100), ignored -> { }).search();
			Assert.assertEquals(RegionalSearchOptimizer.StopReason.TIME_BUDGET, result.stopReason());
			Assert.assertEquals(result.seedUpperBound(), result.upperBound(), 0);
			Assert.assertEquals(0, result.lowerBound(), 0);
			Assert.assertFalse(result.targetReached());
		}
	}

	@Test
	public void configuredAlgorithmsEmitCompleteLegalPlanningReceipt() throws Exception {
		String prefix = CertifiedRegionalOptimizer.PROPERTY_PREFIX;
		Map<String,String> settings = new LinkedHashMap<>();
		settings.put("mode", "anytime"); settings.put("width", "1"); settings.put("maxWidth", "1");
		settings.put("timeMillis", "60000"); settings.put("regionGrowth", "256"); settings.put("maxRegion", "256");
		settings.put("relativeGap", "0"); settings.put("absoluteGap", "0"); settings.put("maxSteps", "64");
		settings.put("algorithm", "threshold"); settings.put("exactClosureAssignments", "100000000");
		Map<String,String> previous = new HashMap<>();
		settings.forEach((key, value) -> { previous.put(key, System.getProperty(prefix + key)); System.setProperty(prefix + key, value); });
		try {
			for(String algorithm : List.of("anytime-target", "threshold", "target-gap", "reuse")) {
				System.setProperty(prefix + "algorithm", algorithm);
				Fixture fixture = fixture();
				ExactPlacementInput receipt = new FederatedPlanLocalCost().rewriteProgram(fixture.program(), null, null, fixture.analysis());
				Assert.assertTrue(receipt.emissionReceipt().applied());
				Assert.assertEquals(PlacementEmissionTransaction.canonicalPlanHash(receipt.normalizedResult()), receipt.emissionReceipt().planHash());
				Assert.assertEquals(fixture.analysis().graph().decisionNodes().size(), receipt.exactSelectedStates().size());
				fixture.analysis().graph().decisionNodes().forEach(node -> Assert.assertTrue(node.legalAlternatives().stream()
					.anyMatch(state -> state == receipt.exactSelectedStates().get(node.key()))));
			}
		}
		finally {
			previous.forEach((key, value) -> {
				if(value == null) System.clearProperty(prefix + key); else System.setProperty(prefix + key, value);
			});
		}
	}

	private static CertifiedRegionalOptimizer.Options common(long budget) {
		return new CertifiedRegionalOptimizer.Options(1, 2, 8, 256, 256, budget, 0, 0, true, true,
			CertifiedRegionalOptimizer.ExpansionPolicy.DISAGREEMENT, 20260908, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
	}
	private static Fixture fixture() throws Exception {
		String script = "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "B=A+1;\nif(rand()>0.5) { print(sum(B)); } else { print(sum(B*2)); }\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program); translator.validateParseTree(program); translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		return new Fixture(program, analysis, model, ExactPhysicalCostModel.physicalCostSurface(analysis, model));
	}
	private record Fixture(DMLProgram program, PlacementAnalysis analysis, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface) { }
}
