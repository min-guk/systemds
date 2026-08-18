/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.List;
import java.util.HashMap;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression: the selected MinST physical representative must survive projection verbatim. */
public class MinStExactProjectionAuthorityPreservationRedTest {
	@Test
	public void selectedCandidateRepresentativeAndEmissionSurviveNormalizationByIdentity() throws Exception {
		int capturedRepresentatives = 0;
		List<String> selectedAuthorities = new java.util.ArrayList<>();
		for(String fixture : List.of("B-01", "B-07", "B-09", "B-11", "B-16", "B-21", "B-22")) {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
			capturedRepresentatives += assertPreserved(fixture, analysis, selectedAuthorities);
		}
		capturedRepresentatives += assertPreserved("LARGE-FED-CANDIDATE",
			new NeutralPlacementGraphBuilder().buildAnalysis(compileLargeFederatedCandidate()), selectedAuthorities);
		Assert.assertTrue("fixtures must exercise selected captured-rule representatives: " + selectedAuthorities,
			capturedRepresentatives > 0);
	}

	private static int assertPreserved(String fixture, PlacementAnalysis analysis,
		List<String> selectedAuthorities) {
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		MinStExactPhysicalOptimizer.Result optimized = MinStExactPhysicalOptimizer.optimize(
			model, surface, MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
		MinStExactPhysicalSelection selected = MinStExactPhysicalSelection.create(model, optimized);
		MinStPlacementInput projected = MinStExactPhysicalPlacementProjector.project(selected);
		NormalizedPlannerResult normalized = projected.normalizedResult();
		Assert.assertNotNull(fixture, normalized);
		Assert.assertEquals(fixture, selected.selectedStates(), normalized.selectedStates());
		Assert.assertEquals(fixture, selected.candidateReceipts(),
			normalized.selectedCandidateSelections());
		Assert.assertEquals(fixture, selected.relocationChoices(),
			normalized.selectedRelocationChoices());

		int capturedRepresentatives = 0;
		for(var alternative : selected.alternativesInDecisionOrder()) {
			selectedAuthorities.add(fixture + ':' + alternative.authorityKind() + ':'
				+ alternative.state().execType() + '/' + alternative.state().output() + ':'
				+ analysis.hop(alternative.decision()).map(hop -> hop.getOpString()).orElse("?"));
			if(!alternative.captured())
				continue;
			capturedRepresentatives++;
			var receipts = normalized.selectedCandidateSelections().stream()
				.filter(receipt -> receipt.rule().parentOccurrence() == alternative.decision()).toList();
			Assert.assertEquals(fixture + " selected alternative requires one exact candidate receipt",
				1, receipts.size());
			Assert.assertSame(fixture + " candidate rule identity changed during projection",
				alternative.candidateRule().key(), receipts.get(0).rule());
			Assert.assertSame(fixture + " candidate emission identity changed during projection",
				alternative.candidateEmission(), receipts.get(0).emission());
			Assert.assertSame(fixture + " selected emission identity changed during projection",
				alternative.candidateEmission().emissionState(),
				normalized.selectedEmissionStates().get(alternative.decision()));
		}
		for(var choice : selected.relocationChoices()) {
			var projectedChoices = normalized.selectedRelocationChoices().stream()
				.filter(candidate -> candidate.demand().equals(choice.demand())).toList();
			Assert.assertEquals(fixture + " selected relocation demand must survive projection",
				1, projectedChoices.size());
			Assert.assertSame(fixture + " selected relocation action identity changed during projection",
				choice.action(), projectedChoices.get(0).action());
		}
		return capturedRepresentatives;
	}

	private static DMLProgram compileLargeFederatedCandidate() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n"
			+ "Y=X+1;\nwrite(Y,\"target/minst-authority-preservation\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
}
