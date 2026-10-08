/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CpRuleFamily;
import org.apache.sysds.hops.fedplanner.placement.CandidateRuleRelation;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyPlacementSelector;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class CpRuleFamilyPipelineTest {
	@Test(timeout = 30000)
	public void publicWeightedFamilyStaysSymbolicThroughBothSolversAndMatchesExplicitRows()
		throws Exception {
		Fixture familyFixture = fixture(false);
		PlacementAnalysis familyAnalysis = familyFixture.analysis();
		Assert.assertFalse(familyAnalysis.candidateRuleFacts().cpFamilies().isEmpty());
		Assert.assertTrue(familyAnalysis.candidateRuleFacts().cpFamilies().stream()
			.allMatch(family -> familyAnalysis.hop(family.parent()).orElseThrow() instanceof QuaternaryOp));
		Assert.assertEquals(0, materializedMembers(familyAnalysis));

		ExactPhysicalModel familyModel = ExactPhysicalModel.build(familyAnalysis);
		Assert.assertTrue(familyModel.domains().stream().flatMap(domain -> domain.alternatives().stream())
			.anyMatch(ExactPhysicalModel.Alternative::family));
		Assert.assertEquals(0, materializedMembers(familyAnalysis));
		var familySurface = ExactPhysicalCostModel.physicalCostSurface(familyAnalysis, familyModel);
		var familyLocal = LocalPhysicalOptimizer.optimize(familyModel, familySurface).physicalResult();
		var familyExact = ExactPhysicalOptimizer.optimize(
			familyModel, familySurface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(familyLocal.canonicalObjectiveBits(), familyExact.canonicalObjectiveBits());
		Assert.assertEquals(0, materializedMembers(familyAnalysis));

		Fixture explicitFixture = fixture(false);
		PlacementAnalysis explicitBase = explicitFixture.analysis();
		List<CandidateRuleFact> expanded = new ArrayList<>(explicitBase.candidateRuleFacts().orderedFacts());
		for(CpRuleFamily family : explicitBase.candidateRuleFacts().cpFamilies())
			expand(family, 0, new ArrayList<>(), expanded);
		expanded.sort(java.util.Comparator.comparing(fact -> fact.key().normalizedSignature()));
		PlacementAnalysis explicitAnalysis = CampaignBPlacementAnalysisFixtureBridge.withCandidateFacts(
			explicitBase, explicitFixture.program(), expanded);
		ExactPhysicalModel explicitModel = ExactPhysicalModel.build(explicitAnalysis);
		var explicitSurface = ExactPhysicalCostModel.physicalCostSurface(explicitAnalysis, explicitModel);
		var explicitLocal = LocalPhysicalOptimizer.optimize(explicitModel, explicitSurface).physicalResult();
		Assert.assertEquals(explicitLocal.canonicalObjectiveBits(), familyLocal.canonicalObjectiveBits());

		ExactPhysicalSelection selected = ExactPhysicalSelection.create(familyModel, familyLocal);
		Assert.assertNotNull(selected);
		Assert.assertTrue(materializedMembers(familyAnalysis) > 0);
		Assert.assertTrue(selected.candidateReceipts().stream().anyMatch(receipt ->
			familyAnalysis.candidateRuleFacts().cpFamiliesForParent(receipt.rule().parentOccurrence())
				.stream().anyMatch(family -> family.contains(receipt.rule().orderedInputs()))));
	}

	@Test(timeout = 30000)
	public void protectedWeightedInputDoesNotPublishCpFamily() throws Exception {
		assertNoFamilyEscapesPrivacyFailure();
	}

	@Test(timeout = 30000)
	public void inlinedWeightedFunctionFamilyReachesExactSelection() throws Exception {
		PlacementAnalysis analysis = fixture(false, true).analysis();
		Assert.assertFalse(analysis.candidateRuleFacts().cpFamilies().isEmpty());
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertNotNull(ExactPhysicalSelection.create(model,
			LocalPhysicalOptimizer.optimize(model, surface).physicalResult()));
	}

	@Test(timeout = 30000)
	public void rowWeightedFedRelationRestoresExactDirectSourceAuthority() throws Exception {
		PlacementAnalysis analysis = rowFixture().analysis();
		Assert.assertFalse(analysis.candidateRuleFacts().candidateRelations().isEmpty());
		Assert.assertEquals(0, materializedRelationMembers(analysis));
		CandidateRuleRelation relation = analysis.candidateRuleFacts().candidateRelations().stream()
			.filter(candidate -> analysis.hop(candidate.parent()).orElse(null) instanceof QuaternaryOp)
			.findFirst().orElseThrow();
		List<CandidateInputState> inputs = new ArrayList<>();
		inputs.add(CandidateInputState.present(org.apache.sysds.hops.fedplanner.FTypes.FType.ROW));
		while(inputs.size() < relation.regions().get(0).axes().size())
			inputs.add(CandidateInputState.absentLocal());
		var weightedFedSupports = relation.requireExact(inputs).allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState().execType()
				== org.apache.sysds.common.Types.ExecType.FED
				&& emission.emissionState().placementState().output()
					== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT)
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream()).toList();
		Assert.assertFalse(weightedFedSupports.isEmpty());
		Assert.assertTrue("the weighted FED relation must retain exact input-0 source authority",
			weightedFedSupports.stream()
				.anyMatch(clause -> clause.inputBindings().stream().anyMatch(binding ->
					binding.inputPosition() == 0
						&& binding.kind() == org.apache.sysds.hops.fedplanner.placement.PlacementIdentity
							.CandidateInputBindingKind.DIRECT)));
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		Assert.assertTrue(model.domains().stream().flatMap(domain -> domain.alternatives().stream())
			.anyMatch(ExactPhysicalModel.Alternative::relationFamily));
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var local = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		Assert.assertNotNull(local);
		var exact = ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(local.canonicalObjectiveBits(), exact.canonicalObjectiveBits());
		Assert.assertNotNull(ExactPhysicalSelection.create(model, local));
	}

	@Test(timeout = 30000)
	public void legacyFedAllAndPolicyGreedySelectExactWeightedFallbackRows() throws Exception {
		PlacementAnalysis compact = rowFixture().analysis();
		Assert.assertFalse(compact.candidateRuleFacts().candidateRelations().isEmpty());

		var compactPolicy = new PolicyGreedyPlacementSelector().select(compact);
		var compactFedAll = new FedAllPlacementAdapter().select(compact);
		Assert.assertTrue(materializedRelationMembers(compact) > 0);

		Fixture explicitFixture = rowFixture();
		List<CandidateRuleFact> expanded = new ArrayList<>(
			explicitFixture.analysis().candidateRuleFacts().orderedFacts());
		for(CandidateRuleRelation relation : explicitFixture.analysis().candidateRuleFacts().candidateRelations())
			for(CandidateRuleRelation.ConditionalRegion region : relation.regions())
				expandRelation(relation, region, 0, new ArrayList<>(), expanded);
		expanded.sort(java.util.Comparator.comparing(fact -> fact.key().normalizedSignature()));
		PlacementAnalysis explicit = CampaignBPlacementAnalysisFixtureBridge.withCandidateFacts(
			explicitFixture.analysis(), explicitFixture.program(), expanded);
		var explicitPolicy = new PolicyGreedyPlacementSelector().select(explicit);
		var explicitFedAll = new FedAllPlacementAdapter().select(explicit);

		Assert.assertEquals(assignmentSignature(explicitPolicy.assignment()),
			assignmentSignature(compactPolicy.assignment()));
		Assert.assertEquals(receiptSignatures(explicitPolicy.selectedCandidateSelections()),
			receiptSignatures(compactPolicy.selectedCandidateSelections()));
		Assert.assertEquals(assignmentSignature(explicitFedAll.assignment()),
			assignmentSignature(compactFedAll.assignment()));
		Assert.assertEquals(receiptSignatures(explicitFedAll.selectedCandidateSelections()),
			receiptSignatures(compactFedAll.selectedCandidateSelections()));
	}

	private static List<String> assignmentSignature(
		java.util.Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey,
			org.apache.sysds.hops.fedplanner.placement.PlacementState> assignment) {
		return assignment.entrySet().stream().map(entry -> entry.getKey().normalizedSignature()
			+ "=" + entry.getValue().normalizedSignature()).sorted().toList();
	}

	private static List<String> receiptSignatures(
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt> receipts) {
		return receipts.stream().map(
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt::normalizedSignature)
			.sorted().toList();
	}

	private static long materializedMembers(PlacementAnalysis analysis) {
		return analysis.candidateRuleFacts().cpFamilies().stream()
			.mapToLong(CpRuleFamily::materializedMemberCount).sum();
	}

	private static long materializedRelationMembers(PlacementAnalysis analysis) {
		return analysis.candidateRuleFacts().candidateRelations().stream()
			.mapToLong(CandidateRuleRelation::materializedMemberCount).sum();
	}

	private static void expand(CpRuleFamily family, int position,
		List<CandidateInputState> inputs, List<CandidateRuleFact> output) {
		if(position == family.axes().size()) {
			output.add(family.requireExact(inputs));
			return;
		}
		for(CandidateInputState input : family.axes().get(position)) {
			inputs.add(input);
			expand(family, position + 1, inputs, output);
			inputs.remove(inputs.size() - 1);
		}
	}

	private static void expandRelation(CandidateRuleRelation relation,
		CandidateRuleRelation.ConditionalRegion region, int position,
		List<CandidateInputState> inputs, List<CandidateRuleFact> output) {
		if(position == region.axes().size()) {
			output.add(relation.requireExact(inputs));
			return;
		}
		for(CandidateInputState input : region.axes().get(position)) {
			inputs.add(input);
			expandRelation(relation, region, position + 1, inputs, output);
			inputs.remove(inputs.size() - 1);
		}
	}

	private static Fixture fixture(boolean protectedInput) throws Exception {
		return fixture(protectedInput, false);
	}

	private static Fixture fixture(boolean protectedInput, boolean inFunction) throws Exception {
		String x = "X=federated(addresses=list(\"localhost:13001/X\"),"
			+ "ranges=list(list(0,0),list(8,4)));";
		String function = "weighted=function(matrix[double] X,matrix[double] U,"
			+ "matrix[double] V,matrix[double] W) return(double out){"
			+ "out=sum(W*(X-U%*%t(V))^2);}";
		String script = (inFunction ? function : "") + x
			+ "U=matrix(1,rows=8,cols=2);V=matrix(1,rows=4,cols=2);"
			+ "W=matrix(1,rows=8,cols=4);sl="
			+ (inFunction ? "weighted(X,U,V,W)" : "sum(W*(X-U%*%t(V))^2)") + ";print(sl);";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program,
			protectedInput ? Privacy.PRIVATE_AGGREGATE : Privacy.PUBLIC);
		return new Fixture(new NeutralPlacementGraphBuilder().buildAnalysis(program), program);
	}

	private static Fixture rowFixture() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:13001/X\",\"localhost:13002/X\"),"
			+ "ranges=list(list(0,0),list(4,4),list(4,0),list(8,4)));"
			+ "U=matrix(1,rows=8,cols=2);V=matrix(1,rows=4,cols=2);"
			+ "W=matrix(1,rows=8,cols=4);sl=sum(W*(X-U%*%t(V))^2);print(sl);";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		return new Fixture(new NeutralPlacementGraphBuilder().buildAnalysis(program), program);
	}

	private static void assertNoFamilyEscapesPrivacyFailure() throws Exception {
		try {
			PlacementAnalysis analysis = fixture(true).analysis();
			Assert.assertTrue(analysis.candidateRuleFacts().cpFamilies().isEmpty());
		}
		catch(DMLRuntimeException expected) {
			Assert.assertTrue(expected.getMessage().contains("privacy-safe physical placement"));
		}
	}

	private record Fixture(PlacementAnalysis analysis, DMLProgram program) { }
}
