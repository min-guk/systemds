/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.UploadPriceTarget;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedInvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolution;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionRequest;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.InvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/** RED guard for exact MinST selectors that collapse FED/FOUT membership across retained FTypes. */
public class CampaignBR10MinStFTypeMembershipAuthorityRedTest {
	@Test
	public void b11ExactSelectorRetainsFTypeSpecificFedFoutRowAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-11"));
		String before = immutableSnapshot(analysis);
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact decision = b11FederatedSourceDecision(facts, analysis);
		List<PlacementState> fedFoutStates = identityDistinctFedFoutStates(decision);

		Assert.assertSame("BR10_B11_FACTS_RETAIN_ANALYSIS_IDENTITY", analysis, facts.analysis());
		Assert.assertEquals("BR10_B11_CURRENT_SOURCE_MUST_HAVE_SINGLE_FED_FOUT_STATE",
			1, fedFoutStates.size());
		PlacementState expected = fedFoutStates.get(0);
		Assert.assertEquals("BR10_B11_CURRENT_SOURCE_AUTHORITY_MUST_BE_ROW", FType.ROW,
			expected.fType());
		Assert.assertFalse("BR10_B11_CURRENT_SOURCE_AUTHORITY_MUST_NOT_BE_SHAPE_DEPENDENT",
			expected.shapeDependent());

		List<MembershipRepresentative> representatives = facts.membershipRepresentativesInCanonicalOrder()
			.stream()
			.filter(representative -> representative.decisionKey() == decision.key()
				&& representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT)
			.toList();
		Assert.assertEquals("BR10_B11_CURRENT_SOURCE_MUST_HAVE_SINGLE_FED_FOUT_AUTHORITY",
			1, representatives.size());
		Assert.assertSame("BR10_B11_REPRESENTATIVE_MUST_RETAIN_EXACT_ROW_STATE", expected,
			representatives.get(0).state());
		Assert.assertEquals("BR10_B11_REPRESENTATIVE_MUST_BE_DURABLE_SOURCE_AUTHORITY",
			MembershipAuthorityKind.DURABLE_ANCHOR, representatives.get(0).authorityKind());

		MinStExactSelection selection = MinStExactSelector.select(facts);
		Assert.assertEquals("BR10_B11_SELECTOR_MUST_BE_UNIQUE", MinStExactSelection.UNIQUE,
			selection.tieCertificate());
		int index = facts.decisionFactsInScopeOrder().indexOf(decision);
		Assert.assertTrue("BR10_B11_DECISION_INDEX_MISSING", index >= 0);
		if(selection.selectedStatesInScopeOrder().get(index).execType() == ExecType.FED
			&& selection.selectedStatesInScopeOrder().get(index).output() == FederatedOutput.FOUT)
			Assert.assertSame("BR10_B11_SELECTOR_MUST_USE_ONLY_EXACT_ROW_FED_FOUT_STATE", expected,
				selection.selectedStatesInScopeOrder().get(index));
		Assert.assertEquals("BR10_B11_SELECTOR_MUST_NOT_MUTATE_ANALYSIS", before, immutableSnapshot(analysis));
	}

	@Ignore("Legacy binary-cut relocation derivation was replaced by exact physical candidate/action alternatives; covered by MinStExactProductionTractabilityCertificateTest")
	@Test
	public void logRegFunctionChoosesExactPresentInputCandidateAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileLogReg());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact decision = facts.decisionFactsInScopeOrder().stream()
			.filter(candidate -> analysis.hop(candidate.key())
				.filter(FunctionOp.class::isInstance)
				.map(FunctionOp.class::cast)
				.map(FunctionOp::getFunctionName)
				.filter("m_multiLogReg"::equals)
				.isPresent())
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_LOGREG_FUNCTION_DECISION_MISSING"));
		List<MembershipRepresentative> representatives = facts.membershipRepresentativesInCanonicalOrder()
			.stream()
			.filter(representative -> representative.decisionKey() == decision.key()
				&& representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT)
			.toList();
		Assert.assertEquals("BR10_LOGREG_FED_FOUT_REPRESENTATIVE_MUST_BE_UNIQUE",
			1, representatives.size());
		MembershipRepresentative representative = representatives.get(0);
		Assert.assertNotNull("BR10_LOGREG_REPRESENTATIVE_MUST_RETAIN_CANDIDATE_RULE",
			representative.candidateRuleFactOrNull());

		List<CompiledInputEdgeFact> inputEdges = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == decision.key()).toList();
		Assert.assertEquals("BR10_LOGREG_FUNCTION_MUST_RETAIN_TWO_COMPILED_MATRIX_INPUTS",
			2, inputEdges.size());
		for(CompiledInputEdgeFact edge : inputEdges)
			Assert.assertEquals("BR10_LOGREG_DIRECT_INPUT_AUTHORITY_MUST_NOT_BE_DISCARDED",
				CandidateInputState.present(FType.ROW),
				representative.orderedInputs().get(edge.inputPosition()));

		long competingFacts = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == decision.key())
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedEmissionFacts().stream()
				.anyMatch(emission -> emission.emissionState().placementState() == representative.state()))
			.count();
		Assert.assertTrue("BR10_LOGREG_FIXTURE_MUST_RETAIN_COMPETING_INPUT_DOMAINS",
			competingFacts > 1);
	}

	@Test
	public void kmeansCpFoutChoosesUniqueMaterializedInputAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		List<MembershipRepresentative> representatives = facts.membershipRepresentativesInCanonicalOrder()
			.stream()
			.filter(representative -> representative.execType() == ExecType.CP
				&& representative.output() == FederatedOutput.FOUT)
			.filter(representative -> representative.decisionKey().normalizedSignature()
				.contains("scripts/builtin/kmeans.dml:155:24:org.apache.sysds.hops.AggBinaryOp"))
			.toList();
		Assert.assertEquals("BR10_KMEANS_CP_FOUT_REPRESENTATIVE_MUST_BE_UNIQUE",
			1, representatives.size());
		MembershipRepresentative representative = representatives.get(0);
		Assert.assertEquals("BR10_KMEANS_EXACT_INPUT0_MUST_BE_COL",
			CandidateInputState.present(FType.COL), representative.orderedInputs().get(0));
		Assert.assertEquals("BR10_KMEANS_EXACT_INPUT1_MUST_BE_ROW",
			CandidateInputState.present(FType.ROW), representative.orderedInputs().get(1));
		Assert.assertEquals("BR10_KMEANS_CP_FOUT_MUST_RETAIN_ONE_EXACT_PRODUCER_AUTHORITY",
			1, representative.inputAuthorityFacts().size());
	}

	@Test
	public void kmeansHarnessDerivedFoutRetainsExactUploadAuthority() throws Exception {
		Map<String,String> oldCostProperties = installDockerLanCostProperties();
		try {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
				.buildDetachedAnalysis(compileHarnessKMeans());
			MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
			List<CompiledInputEdgeFact> matches = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.producer().normalizedSignature()
					.contains("scripts/builtin/kmeans.dml:210:29:org.apache.sysds.hops.BinaryOp"))
				.filter(edge -> edge.consumer().normalizedSignature()
					.contains("scripts/builtin/kmeans.dml:211:14:org.apache.sysds.hops.AggUnaryOp"))
				.toList();
			Assert.assertEquals("BR10_KMEANS_HARNESS_FAILURE_EDGE_MUST_BE_UNIQUE", 1, matches.size());
			CompiledInputEdgeFact input = matches.get(0);
			AuxiliaryGroupFact group = facts.auxiliaryGroupsInCanonicalOrder().stream()
				.filter(candidate -> candidate.direction() == Direction.UPLOAD
					&& candidate.producerKey() == input.producer())
				.filter(candidate -> candidate.endpointsInCanonicalOrder().stream().anyMatch(endpoint ->
					endpoint.consumerKey() == input.consumer()
						&& endpoint.inputPosition() == input.inputPosition()))
				.findFirst().orElseThrow(() -> new AssertionError(
					"BR10_KMEANS_HARNESS_UPLOAD_GROUP_MISSING"));
			Assert.assertEquals("BR10_KMEANS_DERIVED_FOUT_MUST_OWN_EXACT_REUSE_PRICE",
				UploadPriceTarget.PRODUCER_FED_FOUT, group.uploadPriceTarget());
			MinStExactSelection selection = MinStExactSelector.select(facts);
			Assert.assertEquals("BR10_KMEANS_DERIVED_FOUT_REUSE_MUST_NOT_EMIT_UPLOAD_RECEIPT", 0L,
				selection.obligationReceiptsInOrder().stream().filter(receipt ->
					receipt.direction() == Direction.UPLOAD
						&& receipt.producerKey() == input.producer()).count());
		}
		finally {
			restoreProperties(oldCostProperties);
		}
	}

	@Ignore("Legacy binary-cut KMeans membership assertions were replaced by exact physical candidate/action alternatives")
	@Test
	public void kmeansRefedAggregateBinaryRetainsLegacyFederatedMembership() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		CompiledHopKey key = analysis.graph().decisionNodes().stream()
			.map(node -> node.key())
			.filter(candidate -> candidate.normalizedSignature()
				.contains("scripts/builtin/kmeans.dml:70:30:org.apache.sysds.hops.AggBinaryOp"))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_REFED_AGGBINARY_DECISION_MISSING"));
		PlacementState fedLout = analysis.graph().node(key).orElseThrow().legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.LOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_REFED_AGGBINARY_RAW_FED_LOUT_MISSING"));

		Assert.assertTrue("BR10_KMEANS_LOCAL_INPUT_UPLOAD_MUST_HAVE_EXACT_RELOCATION_AUTHORITY",
			analysis.graph().relocationActions().stream()
				.filter(action -> action.key().targetPlacement().equals(fedLout))
				.flatMap(action -> action.obligations().stream())
				.anyMatch(obligation -> obligation.consumer() == key
					&& obligation.inputPosition() == 0));

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact decision = facts.decisionFactsInScopeOrder().stream()
			.filter(candidate -> candidate.key() == key).findFirst().orElseThrow();
		Assert.assertTrue("BR10_KMEANS_LEGACY_FED_LOUT_MUST_SURVIVE_EXACT_PRE_SOLVE",
			decision.legalStatesInCanonicalOrder().contains(fedLout));
	}

	@Test
	public void kmeansFunctionInputLocalMaterializationRetainsLegacyFullPayloadCost() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		PlacementAnalysis.LogicalFunctionInputFact logicalInput = analysis
			.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead().normalizedSignature()
				.contains("scripts/builtin/kmeans.dml:59:16:org.apache.sysds.hops.DataOp:TRead X"))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_FUNCTION_INPUT_TREAD_MISSING"));
		DecisionFact source = decision(facts, logicalInput.sourceArgument());
		DecisionFact formal = decision(facts, logicalInput.targetRead());
		DirectedEdgeFact edge = facts.directedEdgesInDerivationOrder().stream()
			.filter(candidate -> candidate.fromNodeId() == source.placementNodeId()
				&& candidate.toNodeId() == formal.placementNodeId())
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_FUNCTION_INPUT_DOWNLOAD_EDGE_MISSING"));
		double actual = edge.contributionsInDerivationOrder().stream()
			.filter(contribution -> "logical-function-fout-to-local-download"
				.equals(contribution.provenance()))
			.mapToDouble(contribution -> Double.longBitsToDouble(contribution.costBits()))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_FUNCTION_INPUT_DOWNLOAD_CONTRIBUTION_MISSING"));
		double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
			analysis.hop(logicalInput.targetRead()).orElseThrow(),
			analysis.hop(logicalInput.sourceArgument()).orElseThrow());
		double expected = FederatedCostModel.computeDownloadNetworkCost(bytes);
		Assert.assertEquals("BR10_KMEANS_LOCAL_TREAD_MUST_PAY_FULL_LEGACY_MATERIALIZATION",
			expected, actual, 0.0);
	}

	@Test
	public void kmeansDerivedWorkerPoolRetainsLegacyCpFoutCandidate() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		CompiledHopKey key = decisionKey(analysis,
			"scripts/builtin/kmeans.dml:213:32:org.apache.sysds.hops.BinaryOp");
		PlacementState cpFout = analysis.graph().node(key).orElseThrow().legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_DERIVED_WORKER_POOL_CP_FOUT_MISSING"));
		Assert.assertEquals("BR10_KMEANS_COMPILED_CP_FOUT_MUST_NOT_USE_RECOMPILE_ESCAPE",
			"compiled", key.recompileContext());
		Assert.assertTrue("BR10_KMEANS_CP_FOUT_MUST_HAVE_EXACT_CANDIDATE_AUTHORITY",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == key)
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().placementState() == cpFout));
	}

	@Ignore("Legacy binary-cut LM conjunctive FType derivation was replaced by exact physical domains and hard factors")
	@Test
	public void lmCgDerivedWorkerPoolClosesFedLoutToDerivedFedFout() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileLm());
		CompiledHopKey key = decisionKey(analysis,
			"scripts/builtin/lmCG.dml:129:24:org.apache.sysds.hops.AggBinaryOp:ba(+*):q");
		List<PlacementState> legal = analysis.graph().node(key).orElseThrow().legalAlternatives();
		PlacementState derived = legal.stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_LMCG_DERIVED_FED_FOUT_MISSING|legal=" + legal.stream()
					.map(PlacementState::normalizedSignature).toList()));
		Assert.assertTrue("BR10_LMCG_DERIVED_FED_FOUT_MUST_RETAIN_EXACT_CANDIDATE_AUTHORITY",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == key)
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().placementState() == derived
					&& emission.emissionState().derivedFedFout()
					&& emission.executionFType() != null));

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		Assert.assertTrue("BR10_LMCG_FIXTURE_MUST_EXERCISE_DOCKER_POLYNOMIAL_PATH",
			usesPolynomialSolver(facts));
		MinStExactSelection selection = MinStExactSelector.select(facts);
		Assert.assertTrue("BR10_LMCG_SELECTED_Q_MUST_BE_LEGAL",
			legal.contains(selectedState(facts, selection, decision(facts, key))));
	}

	@Ignore("Legacy binary-cut derived relocation fixture was replaced by physical alternative/action identity")
	@Test
	public void l2svmStateDependentInputMaterializationRemainsExactlyCostable() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileL2Svm());
		CompiledHopKey key = decisionKey(analysis,
			"scripts/builtin/l2svm.dml:91:19:org.apache.sysds.hops.AggBinaryOp:ba(+*):g_old");
		List<PlacementState> legal = analysis.graph().node(key).orElseThrow().legalAlternatives();
		PlacementState fedLout = legal.stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.LOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_L2SVM_NATIVE_FED_LOUT_MISSING|legal=" + legal.stream()
					.map(PlacementState::normalizedSignature).toList()));
		PlacementState fedFout = legal.stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_L2SVM_DERIVED_FED_FOUT_MISSING|legal=" + legal.stream()
					.map(PlacementState::normalizedSignature).toList()));

		Assert.assertTrue("BR10_L2SVM_FIXTURE_MUST_RETAIN_NATIVE_FULL_INPUT",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == key)
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.filter(fact -> fact.key().orderedInputs().size() > 1)
				.filter(fact -> fact.key().orderedInputs().get(1)
					.equals(CandidateInputState.present(FType.FULL)))
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().placementState() == fedLout));
		Assert.assertTrue("BR10_L2SVM_FIXTURE_MUST_RETAIN_DERIVED_LOCAL_INPUT_RELOCATION",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == key)
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.filter(fact -> fact.key().orderedInputs().size() > 1)
				.filter(fact -> fact.key().orderedInputs().get(1)
					.equals(CandidateInputState.absentLocal()))
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().placementState() == fedFout
					&& emission.emissionState().derivedFedFout()));
		CompiledInputEdgeFact localInput = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == key && edge.inputPosition() == 1)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_L2SVM_LOCAL_AGGBINARY_INPUT_MISSING"));
		var producer = analysis.graph().node(localInput.producer()).orElseThrow();
		var derivedRelocations = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion() == producer.valueVersion()
				&& action.key().targetPlacement().equals(fedFout))
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == key && obligation.inputPosition() == 1))
			.toList();
		Assert.assertEquals("BR10_L2SVM_DERIVED_INPUT_RELOCATION_MUST_BE_UNIQUE",
			1, derivedRelocations.size());
		Assert.assertEquals("BR10_L2SVM_DERIVED_OUTPUT_MUST_RETAIN_NATIVE_FULL_INPUT_LAYOUT",
			FType.FULL, derivedRelocations.get(0).key().materializationFType());

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		MinStExactSelection selection = MinStExactSelector.select(facts);
		Assert.assertTrue("BR10_L2SVM_SELECTED_G_OLD_MUST_BE_LEGAL",
			legal.contains(selectedState(facts, selection, decision(facts, key))));
	}

	@Ignore("Legacy binary-cut FULL-pool grouping was replaced by exact relocation action identity")
	@Test
	public void l2svmDifferentFullWorkersDoNotInventSharedPoolAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileL2Svm("localhost:1235/Y1"));
		CompiledHopKey key = decisionKey(analysis,
			"scripts/builtin/l2svm.dml:91:19:org.apache.sysds.hops.AggBinaryOp:ba(+*):g_old");
		PlacementState fedFout = analysis.graph().node(key).orElseThrow().legalAlternatives().stream()
			.filter(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_L2SVM_DIFFERENT_WORKER_FIXTURE_MUST_RETAIN_DERIVED_FOUT"));
		CompiledInputEdgeFact localInput = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == key && edge.inputPosition() == 1)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_L2SVM_DIFFERENT_WORKER_LOCAL_INPUT_MISSING"));
		var producer = analysis.graph().node(localInput.producer()).orElseThrow();
		Assert.assertFalse("BR10_L2SVM_DIFFERENT_WORKERS_MUST_NOT_SHARE_FULL_POOL_AUTHORITY",
			analysis.graph().relocationActions().stream()
				.filter(action -> action.key().sourceValueVersion() == producer.valueVersion()
					&& action.key().targetPlacement().equals(fedFout))
				.filter(action -> action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == key && obligation.inputPosition() == 1))
				.anyMatch(action -> action.key().materializationFType() == FType.FULL));
	}

	@Ignore("Legacy binary-cut forwarded-call membership was replaced by exact physical function-boundary authority")
	@Test
	public void kmeansForwardedFunctionInputRetainsLegacyDirectCallerChoice() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans(120));
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		PlacementAnalysis.LogicalTransientInputFact logical = analysis
			.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead().normalizedSignature()
				.contains("scripts/builtin/kmeans.dml:134:16:org.apache.sysds.hops.DataOp:TRead X"))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOGICAL_TRANSIENT_INPUT_MISSING"));
		CompiledHopKey formalRead = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.right() == logical.sourceWrite())
			.filter(constraint -> "function-input-binding".equals(constraint.evidence()))
			.map(constraint -> constraint.left()).findFirst().orElseThrow(() ->
				new AssertionError("BR10_KMEANS_FORMAL_BINDING_AUTHORITY_MISSING"));
		PlacementAnalysis.LogicalFunctionInputFact functionInput = analysis
			.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == formalRead).findFirst().orElseThrow(() ->
				new AssertionError("BR10_KMEANS_CALLER_ARGUMENT_AUTHORITY_MISSING"));
		DecisionFact source = decision(facts, functionInput.sourceArgument());
		DecisionFact read = decision(facts, logical.targetRead());
		assertContribution(facts, source.placementNodeId(), read.placementNodeId(),
			"logical-function-forwarded-fout-to-local-download");
		double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
			analysis.hop(read.key()).orElseThrow(), analysis.hop(source.key()).orElseThrow());
		double baseDownload = FederatedCostModel.computeDownloadNetworkCost(bytes);
		List<CompiledInputEdgeFact> readConsumers = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == read.key()).toList();
		Assert.assertEquals("BR10_KMEANS_FORWARD_READ_MUST_KEEP_TWO_LOOP_CONSUMERS",
			2, readConsumers.size());
		for(CompiledInputEdgeFact edge : readConsumers) {
			DecisionFact consumer = decision(facts, edge.consumer());
			Assert.assertEquals("BR10_KMEANS_FORWARD_LOCAL_CONSUMER_MUST_KEEP_LOOP_WEIGHT|consumer="
				+ edge.consumer().normalizedSignature(), 120.0 * baseDownload,
				contribution(facts, source.placementNodeId(), consumer.computeNodeId(),
					"logical-function-forwarded-local-consumer-download"), 0.0);
		}
		AuxiliaryGroupFact sharedDownload = facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == Direction.DOWNLOAD && group.producerKey() == read.key())
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_FORWARD_SHARED_LOCAL_MATERIALIZATION_MISSING"));
		Assert.assertEquals("BR10_KMEANS_FORWARD_LOCAL_MATERIALIZATION_MUST_BE_SHARED_ONCE",
			baseDownload, Double.longBitsToDouble(sharedDownload.priceBits()), 0.0);
		CompiledInputEdgeFact loopLocalInput = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.inputPosition() == 0)
			.filter(edge -> edge.consumer().normalizedSignature()
				.contains("scripts/builtin/kmeans.dml:155:24:org.apache.sysds.hops.AggBinaryOp"))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOOP_LOCAL_INPUT_MISSING"));
		AuxiliaryGroupFact loopUpload = facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == Direction.UPLOAD
				&& group.producerKey() == loopLocalInput.producer())
			.filter(group -> group.endpointsInCanonicalOrder().stream().anyMatch(endpoint ->
				endpoint.consumerKey() == loopLocalInput.consumer()
					&& endpoint.inputPosition() == loopLocalInput.inputPosition()))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOOP_LOCAL_UPLOAD_GROUP_MISSING"));
		Assert.assertEquals("BR10_KMEANS_LOOP_LOCAL_UPLOAD_MUST_USE_RELOCATION_MATERIALIZATION",
			FType.COL, loopUpload.conversionType());
		PlacementState loopConsumerTarget = representative(facts,
			decision(facts, loopLocalInput.consumer()), ExecType.FED, FederatedOutput.LOUT).state();
		Assert.assertTrue("BR10_KMEANS_LOOP_LOCAL_UPLOAD_MUST_RETAIN_ROW_TARGET_AUTHORITY",
			facts.transferAuthoritiesInCanonicalOrder().stream().anyMatch(authority ->
				authority.group() == loopUpload
					&& authority.authorityKind() == TransferAuthorityKind.RELOCATION_OBLIGATION
					&& authority.requiredPlacement().equals(loopConsumerTarget)));
		Assert.assertFalse("BR10_KMEANS_FORWARDED_FORMAL_MUST_NOT_BE_TIED_TO_COMPILER_BINDING",
			facts.directedEdgesInDerivationOrder().stream()
				.flatMap(edge -> edge.contributionsInDerivationOrder().stream())
				.anyMatch(contribution -> contribution.ownerKey() == logical.sourceWrite()
					&& contribution.peerKeyOrNull() == logical.targetRead()
					&& contribution.provenance().startsWith("logical-transient-")));

		MinStExactSelection selection = MinStExactSelector.select(facts);
		PlacementState readState = selectedState(facts, selection, read);
		Assert.assertEquals("BR10_KMEANS_LEGACY_LOOP_TREAD_MUST_STAY_FED",
			ExecType.FED, readState.execType());
		Assert.assertEquals("BR10_KMEANS_LEGACY_LOOP_TREAD_MUST_KEEP_FOUT",
			FederatedOutput.FOUT, readState.output());
		Assert.assertEquals("BR10_KMEANS_LEGACY_LOOP_TREAD_MUST_KEEP_ROW_LAYOUT",
			FType.ROW, readState.fType());
		for(CompiledInputEdgeFact edge : readConsumers) {
			PlacementState consumerState = selectedState(facts, selection,
				decision(facts, edge.consumer()));
			Assert.assertEquals("BR10_KMEANS_LEGACY_LOOP_CONSUMER_MUST_STAY_FED|consumer="
				+ edge.consumer().normalizedSignature(), ExecType.FED, consumerState.execType());
			Assert.assertEquals("BR10_KMEANS_LEGACY_LOOP_CONSUMER_MUST_KEEP_LOUT|consumer="
				+ edge.consumer().normalizedSignature(), FederatedOutput.LOUT, consumerState.output());
		}
	}

	@Ignore("Legacy binary-cut KMeans membership assertions were replaced by exact physical candidate/action alternatives")
	@Test
	public void kmeansProductionFunctionInputRetainsLegacyDirectCallerCostAndAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compileKMeansWithLiveCentroids(120));
		PlacementAnalysis.LogicalFunctionInputFact logicalInput = analysis
			.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead().normalizedSignature()
				.contains("scripts/builtin/kmeans.dml:59:16:org.apache.sysds.hops.DataOp:TRead X"))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_PRODUCTION_FUNCTION_INPUT_MISSING|available="
					+ analysis.logicalFunctionInputsInCanonicalOrder().stream()
						.map(fact -> fact.targetRead().normalizedSignature()).toList()));
		Assert.assertTrue("BR10_KMEANS_PRODUCTION_FORMAL_MUST_USE_DIRECT_CALLER_AUTHORITY",
			analysis.logicalTransientInputsInCanonicalOrder().stream()
				.noneMatch(fact -> fact.targetRead() == logicalInput.targetRead()));

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact source = decision(facts, logicalInput.sourceArgument());
		DecisionFact formal = decision(facts, logicalInput.targetRead());
		double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(
			analysis.hop(formal.key()).orElseThrow(), analysis.hop(source.key()).orElseThrow());
		double baseDownload = FederatedCostModel.computeDownloadNetworkCost(bytes);
		List<CompiledInputEdgeFact> consumers = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == formal.key()).toList();
		Assert.assertEquals("BR10_KMEANS_PRODUCTION_FORMAL_MUST_KEEP_DIRECT_CONSUMERS",
			2, consumers.size());
		for(CompiledInputEdgeFact edge : consumers) {
			DecisionFact consumer = decision(facts, edge.consumer());
			Assert.assertEquals("BR10_KMEANS_PRODUCTION_LOCAL_CONSUMER_MUST_KEEP_DIRECT_WEIGHT|consumer="
				+ edge.consumer().normalizedSignature(), baseDownload,
				contribution(facts, source.placementNodeId(), consumer.computeNodeId(),
					"logical-function-local-consumer-download"), 0.0);
		}

		MinStExactSelection selection = MinStExactSelector.select(facts);
		PlacementState formalState = selectedState(facts, selection, formal);
		Assert.assertEquals("BR10_KMEANS_PRODUCTION_FORMAL_MUST_STAY_FED",
			ExecType.FED, formalState.execType());
		Assert.assertEquals("BR10_KMEANS_PRODUCTION_FORMAL_MUST_KEEP_FOUT",
			FederatedOutput.FOUT, formalState.output());
		Assert.assertEquals("BR10_KMEANS_PRODUCTION_FORMAL_MUST_KEEP_CALLER_ROW_LAYOUT",
			FType.ROW, formalState.fType());
		for(CompiledInputEdgeFact edge : consumers) {
			PlacementState consumerState = selectedState(facts, selection,
				decision(facts, edge.consumer()));
			Assert.assertEquals("BR10_KMEANS_PRODUCTION_LOOP_CONSUMER_MUST_STAY_FED|consumer="
				+ edge.consumer().normalizedSignature(), ExecType.FED, consumerState.execType());
			Assert.assertEquals("BR10_KMEANS_PRODUCTION_LOOP_CONSUMER_MUST_KEEP_LOUT|consumer="
				+ edge.consumer().normalizedSignature(), FederatedOutput.LOUT, consumerState.output());
		}
	}

	@Test
	public void kmeansLoopWeightedNativeResultEdgesRetainLegacySemantics() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact decision = decision(facts, decisionKey(analysis,
			"scripts/builtin/kmeans.dml:134:22:org.apache.sysds.hops.AggBinaryOp"));
		Hop hop = analysis.hop(decision.key()).orElseThrow();
		MembershipRepresentative fedLout = representative(facts, decision,
			ExecType.FED, FederatedOutput.LOUT);
		FType executionFType = fedLout.state().fType();
		List<Hop> inputHops = new java.util.ArrayList<>(hop.getInput());
		List<FType> inputFTypes = fedLout.orderedInputs().stream()
			.map(input -> input.present() ? input.fType() : null).toList();
		double outputBytes = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		double uploadBytes = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		double genericDownload = FederatedCostModel.computeDownloadNetworkCost(uploadBytes);
		double nativeUnaryDownload = FederatedCostModel
			.computeNativeFederatedAggregateUnaryLoutResultCost(hop, executionFType,
				outputBytes, 2, genericDownload);
		double nativeDownload = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
			hop, executionFType, outputBytes, 2, nativeUnaryDownload);
		double unitLocalCost = FederatedCostModel.computeLocalIndexingCostWithFallback(hop,
			FederatedCostModel.computeOpCostWithFallback(hop));
		FederatedCostModel.MixedFedLocalCost mixed = FederatedCostModel.computeMixedFedLocalCost(
			hop, inputHops, inputFTypes, executionFType, unitLocalCost, outputBytes, 2);
		double expectedDownload = 2.0 * (mixed.hasCoordinatorPhase()
			? mixed.getCoordinatorPhaseCost() : nativeDownload);
		FType cpFoutType = representative(facts, decision,
			ExecType.CP, FederatedOutput.FOUT).state().fType();
		double expectedUpload = 2.0 * (FederatedCostModel.computeUploadNetworkCost(
			uploadBytes, cpFoutType, 2)
			+ FederatedCostModel.computeLocalToFedForwardingPenalty(cpFoutType, 2));
		Assert.assertEquals("BR10_KMEANS_NATIVE_RESULT_DOWNLOAD_MUST_KEEP_LOOP_WEIGHT",
			expectedDownload, contribution(facts, decision.computeNodeId(),
				decision.placementNodeId(), "native-fed-lout-download"), 0.0);
		Assert.assertEquals("BR10_KMEANS_CP_FOUT_UPLOAD_MUST_KEEP_LOOP_WEIGHT_AND_SINGLE_PARALLEL_DISPATCH",
			expectedUpload, contribution(facts, decision.placementNodeId(),
				decision.computeNodeId(), "native-cp-fout-upload"), 0.0);
	}

	@Test
	public void kmeansControlDominatedFedUnaryRetainsLegacyLatencyFloor() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact decision = decision(facts, decisionKey(analysis,
			"scripts/builtin/kmeans.dml:213:32:org.apache.sysds.hops.BinaryOp"));
		Hop hop = analysis.hop(decision.key()).orElseThrow();
		FType executionFType = representative(facts, decision,
			ExecType.FED, FederatedOutput.FOUT).candidateRuleFactOrNull()
			.allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState()
				== representative(facts, decision, ExecType.FED, FederatedOutput.FOUT).state())
			.findFirst().orElseThrow().executionFType();
		double floor = FederatedCostModel.computeControlDominatedFederatedInstructionCost(
			hop, executionFType, RewireConstants.DEFAULT_IF_ELSE_WEIGHT, 2, false);
		double fedUnary = contribution(facts, decision.computeNodeId(), facts.sinkNodeId(),
			"neutral-fed-unary");
		Assert.assertTrue("BR10_KMEANS_CONTROL_DOMINATED_FED_UNARY_MUST_INCLUDE_LATENCY_FLOOR"
			+ "|floor=" + floor + "|actual=" + fedUnary, fedUnary >= floor);
	}

	@Ignore("Legacy binary-cut KMeans unary relocation groups were replaced by exact physical candidate/action alternatives")
	@Test
	public void kmeansFedUnaryUsesExactBroadcastMaterializationForLocalMatrixInput() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		DecisionFact decision = decision(facts, decisionKey(analysis,
			"scripts/builtin/kmeans.dml:134:22:org.apache.sysds.hops.AggBinaryOp"));
		Hop hop = analysis.hop(decision.key()).orElseThrow();
		MembershipRepresentative fed = representative(facts, decision,
			ExecType.FED, FederatedOutput.FOUT);
		CompiledInputEdgeFact localInput = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == decision.key() && edge.inputPosition() == 1)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOCAL_AGGBINARY_INPUT_MISSING"));
		var producer = analysis.graph().node(localInput.producer()).orElseThrow();
		var action = analysis.graph().relocationActions().stream()
			.filter(candidate -> candidate.key().sourceValueVersion() == producer.valueVersion()
				&& candidate.key().targetPlacement().equals(fed.state()))
			.filter(candidate -> candidate.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == decision.key() && obligation.inputPosition() == 1))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOCAL_AGGBINARY_RELOCATION_MISSING"));
		FType materializationType = PlacementCostSemantics.exactMaterializationFType(
			analysis.shapeFact(localInput.producer()).orElseThrow(), action.key().durableAnchor());
		Assert.assertEquals("BR10_KMEANS_LOCAL_AGGBINARY_INPUT_MUST_UPLOAD_AS_BROADCAST",
			FType.BROADCAST, materializationType);

		FType executionFType = fed.candidateRuleFactOrNull().allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState() == fed.state())
			.findFirst().orElseThrow().executionFType();
		double cpUnary = contribution(facts, facts.sourceNodeId(), decision.computeNodeId(),
			"neutral-cp-unary");
		double unitLocal = FederatedCostModel.computeLocalIndexingCostWithFallback(hop,
			FederatedCostModel.computeOpCostWithFallback(hop));
		double executionWeight = cpUnary / unitLocal;
		List<FType> inputTypes = List.of(FType.ROW, FType.BROADCAST);
		double fedCompute = FederatedCostModel.computeFederatedComputeCost(hop, cpUnary, 2, false);
		fedCompute = FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
			hop, executionFType, fedCompute);
		fedCompute = FederatedCostModel.computeNativeFederatedIndexingCost(
			hop, executionFType, fedCompute);
		double coordination = FederatedCostModel.adjustFedCoordinationCost(hop, executionFType,
			executionWeight * FederatedCostModel.computeFedCoordinationCost(2));
		double instruction = FederatedCostModel.computeControlDominatedFederatedInstructionCost(
			hop, executionFType, executionWeight, 2, false);
		var mixed = FederatedCostModel.computeMixedFedLocalCost(hop,
			new ArrayList<>(hop.getInput()), inputTypes, executionFType, unitLocal,
			FederatedCostModel.getEffectiveOutputMemEstimate(hop), 2);
		double expected = fedCompute + coordination + instruction
			+ executionWeight * mixed.getInputPreparationCost()
			+ FederatedCostModel.computeSingleWorkerFedExecPenalty(hop, executionWeight, 2);
		Assert.assertEquals("BR10_KMEANS_FED_UNARY_MUST_NOT_CHARGE_LOCAL_INPUT_AS_COORDINATOR_PREP",
			expected, contribution(facts, decision.computeNodeId(), facts.sinkNodeId(),
				"neutral-fed-unary"), 0.0);
	}

	@Ignore("Legacy binary-cut KMeans upload groups were replaced by exact physical candidate/action alternatives")
	@Test
	public void kmeansUploadGroupUsesExactBroadcastConversionInsteadOfConsumerLayout() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileKMeans());
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		CompiledHopKey consumer = decisionKey(analysis,
			"scripts/builtin/kmeans.dml:134:22:org.apache.sysds.hops.AggBinaryOp");
		CompiledInputEdgeFact localInput = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer && edge.inputPosition() == 1)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOCAL_AGGBINARY_INPUT_MISSING"));
		AuxiliaryGroupFact upload = facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == Direction.UPLOAD
				&& group.producerKey() == localInput.producer())
			.filter(group -> group.endpointsInCanonicalOrder().stream().anyMatch(endpoint ->
				endpoint.consumerKey() == consumer && endpoint.inputPosition() == 1))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_LOCAL_AGGBINARY_UPLOAD_GROUP_MISSING"));
		Assert.assertEquals("BR10_KMEANS_UPLOAD_MUST_PRICE_ACTUAL_BROADCAST_MATERIALIZATION",
			FType.BROADCAST, upload.conversionType());
	}

	private static CompiledHopKey decisionKey(PlacementAnalysis analysis, String signature) {
		return analysis.graph().decisionNodes().stream().map(node -> node.key())
			.filter(key -> key.normalizedSignature().contains(signature)).findFirst()
			.orElseThrow(() -> new AssertionError("BR10_KMEANS_DECISION_MISSING|" + signature));
	}

	private static MembershipRepresentative representative(MinStExactCostFacts facts,
		DecisionFact decision, ExecType execType, FederatedOutput output) {
		return facts.membershipRepresentativesInCanonicalOrder().stream()
			.filter(candidate -> candidate.decisionKey() == decision.key()
				&& candidate.execType() == execType && candidate.output() == output)
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_REPRESENTATIVE_MISSING|" + execType + '/' + output));
	}

	private static void assertContribution(MinStExactCostFacts facts, long from, long to,
		String provenance) {
		contribution(facts, from, to, provenance);
	}

	private static double contribution(MinStExactCostFacts facts, long from, long to,
		String provenance) {
		return facts.directedEdgesInDerivationOrder().stream()
			.filter(edge -> edge.fromNodeId() == from && edge.toNodeId() == to)
			.flatMap(edge -> edge.contributionsInDerivationOrder().stream())
			.filter(candidate -> provenance.equals(candidate.provenance()))
			.mapToDouble(candidate -> Double.longBitsToDouble(candidate.costBits()))
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_KMEANS_EDGE_CONTRIBUTION_MISSING|" + from + "->" + to + '|' + provenance));
	}

	private static DecisionFact decision(MinStExactCostFacts facts, CompiledHopKey key) {
		return facts.decisionFactsInScopeOrder().stream()
			.filter(candidate -> candidate.key() == key).findFirst().orElseThrow(() ->
				new AssertionError("BR10_EXACT_DECISION_MISSING|key=" + key.normalizedSignature()));
	}

	private static PlacementState selectedState(MinStExactCostFacts facts,
		MinStExactSelection selection, DecisionFact decision) {
		int index = facts.decisionFactsInScopeOrder().indexOf(decision);
		if(index < 0)
			throw new AssertionError("BR10_EXACT_DECISION_INDEX_MISSING|key="
				+ decision.key().normalizedSignature());
		return selection.selectedStatesInScopeOrder().get(index);
	}

	private static DecisionFact b11FederatedSourceDecision(MinStExactCostFacts facts,
		PlacementAnalysis analysis) {
		return facts.decisionFactsInScopeOrder().stream()
			.filter(decision -> analysis.hop(decision.key())
				.map(hop -> "X".equals(hop.getName())).orElse(false))
			.filter(decision -> !identityDistinctFedFoutStates(decision).isEmpty())
			.findFirst().orElseThrow(() -> new AssertionError(
				"BR10_B11_SINGLE_ROW_FED_FOUT_DECISION_MISSING"));
	}

	private static List<PlacementState> identityDistinctFedFoutStates(DecisionFact decision) {
		return decision.legalStatesInCanonicalOrder().stream()
			.filter(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT)
			.toList();
	}

	private static PlacementState expectedRowFedFoutRepresentativeFromExactCandidateRule(
		PlacementAnalysis analysis, DecisionFact decision) {
		List<CandidateInputState> orderedInputs = retainedExactOrderedInputs(analysis, decision.key());
		CandidateRuleFact fact = analysis.candidateRuleFacts().requireExact(decision.key(), orderedInputs);
		Assert.assertSame("BR10_B11_RULE_FACT_MUST_BE_EXACT_PARENT_AUTHORITY",
			decision.key(), fact.key().parentOccurrence());
		Assert.assertEquals("BR10_B11_RULE_FACT_MUST_USE_RETAINED_ORDERED_INPUT_AUTHORITY",
			orderedInputs, fact.key().orderedInputs());
		Assert.assertEquals("BR10_B11_RULE_FACT_MUST_BE_AVAILABLE",
			CandidateEvaluationStatus.AVAILABLE, fact.status());

		CapturedResolution captured = PlacementCandidateRuleResolver.resolveCaptured(
			new CapturedResolutionRequest(analysis, analysis.analysisFingerprint(), decision.key(), orderedInputs,
				new CapturedInvocationEvidence(invocationEvidence(analysis, decision.key()), List.of(), List.of())));
		Assert.assertSame("BR10_B11_CAPTURED_RESOLUTION_MUST_RETAIN_EXACT_FACT", fact, captured.fact());
		Assert.assertEquals("BR10_B11_CAPTURED_AUTHORITY_MUST_PROJECT_ROW", FType.ROW,
			captured.logicalFType());

		PlacementState expectedValue = new PlacementState(ExecType.FED, FederatedOutput.FOUT,
			captured.logicalFType(), !fact.shapeProof().requiredFacts().isEmpty());
		List<PlacementState> retained = identityDistinctFedFoutStates(decision).stream()
			.filter(state -> state.equals(expectedValue)).toList();
		Assert.assertEquals("BR10_B11_RETAINED_ROW_STATE_MUST_MATCH_CAPTURED_AUTHORITY",
			1, retained.size());
		return retained.get(0);
	}

	private static List<CandidateInputState> retainedExactOrderedInputs(PlacementAnalysis analysis,
		CompiledHopKey consumer) {
		List<CompiledInputEdgeFact> inputEdges = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == consumer).toList();
		Assert.assertFalse("BR10_B11_EXACT_INPUT_EDGE_AUTHORITY_MISSING", inputEdges.isEmpty());
		List<CandidateRuleFact> matches = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == consumer)
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> retainedInputEdgesMatch(analysis, fact, inputEdges))
			.toList();
		Assert.assertEquals("BR10_B11_RETAINED_ORDERED_INPUT_AUTHORITY_MUST_BE_UNIQUE",
			1, matches.size());
		return matches.get(0).key().orderedInputs();
	}

	private static boolean retainedInputEdgesMatch(PlacementAnalysis analysis, CandidateRuleFact fact,
		List<CompiledInputEdgeFact> inputEdges) {
		List<CandidateInputState> inputs = fact.key().orderedInputs();
		for(CompiledInputEdgeFact edge : inputEdges) {
			if(edge.inputPosition() < 0 || edge.inputPosition() >= inputs.size())
				return false;
			List<FType> anchors = analysis.graph().node(edge.producer()).orElseThrow().anchors().stream()
				.map(anchor -> anchor.fType()).distinct().toList();
			Assert.assertEquals("BR10_B11_INPUT_ANCHOR_FTYPE_AUTHORITY_MUST_BE_UNIQUE",
				1, anchors.size());
			if(!inputs.get(edge.inputPosition()).equals(CandidateInputState.present(anchors.get(0))))
				return false;
		}
		for(int position = 0; position < inputs.size(); position++) {
			final int candidatePosition = position;
			boolean matrixInputPosition = inputEdges.stream()
				.anyMatch(edge -> edge.inputPosition() == candidatePosition);
			if(!matrixInputPosition && !inputs.get(position).equals(CandidateInputState.absentLocal()))
				return false;
		}
		return true;
	}

	private static InvocationEvidence invocationEvidence(PlacementAnalysis analysis, CompiledHopKey key) {
		PlacementAnalysis.NodeShapeFact shape = analysis.shapeFact(key)
			.orElseThrow(() -> new AssertionError("BR10_B11_SHAPE_AUTHORITY_MISSING"));
		return new InvocationEvidence(false, shape.dataType().isMatrix(), false,
			shape.dataType().isMatrix() && (shape.rows() == 1 || shape.cols() == 1),
			shape.rows(), shape.cols(), null, false, false, false, false, null, workerCount(analysis));
	}

	private static int workerCount(PlacementAnalysis analysis) {
		return (int)analysis.graph().nodes().stream().flatMap(node -> node.anchors().stream())
			.flatMap(anchor -> anchor.partitions().stream()).map(partition -> partition.workerId())
			.distinct().count();
	}

	private static DMLProgram compileLogReg() throws Exception {
		String script = String.join("\n",
			"N=50000;",
			"D=2100;",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
				+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));",
			"Y=(Y<0)+1;",
			"m=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,tol=1e-9,icpt=0,"
				+ "numclasses=2,numrows=N,numcols=D);",
			"write(m,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static DMLProgram compileKMeans() throws Exception {
		return compileKMeans(2);
	}

	private static DMLProgram compileHarnessKMeans() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));",
			"[C,Y]=kmeans(X=X,k=50,is_verbose=FALSE,runs=1,eps=1e-9,max_iter=60,"
				+ "avg_sample_size_per_centroid=50,seed=133815928);",
			"write(Y,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static DMLProgram compileLm() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\"),"
				+ "ranges=list(list(0,0),list(50000,2100)));",
			"Y=federated(addresses=list(\"localhost:1234/Y1\"),"
				+ "ranges=list(list(0,0),list(50000,1)));",
			"m=lm(X=X,y=Y,verbose=FALSE,tol=1e-9);",
			"write(m,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static DMLProgram compileL2Svm() throws Exception {
		return compileL2Svm("localhost:1234/Y1");
	}

	private static DMLProgram compileL2Svm(String yAddress) throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\"),"
				+ "ranges=list(list(0,0),list(50000,2100)));",
			"Y=federated(addresses=list(\"" + yAddress + "\"),"
				+ "ranges=list(list(0,0),list(50000,1)));",
			"m=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-22,maxIterations=30);",
			"write(m,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static DMLProgram compileKMeans(int maxIterations) throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));",
			"[C,Y]=kmeans(X=X,k=4,runs=1,max_iter=" + maxIterations + ",seed=93);") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static DMLProgram compileKMeansWithLiveCentroids(int maxIterations) throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));",
			"[C,Y]=kmeans(X=X,k=4,runs=1,max_iter=" + maxIterations + ",seed=93);",
			"write(C,\"out\",format=\"csv\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream().map(HopOccurrenceProjection::key).toList();
	}

	private static boolean usesPolynomialSolver(MinStExactCostFacts facts) throws Exception {
		var method = MinStExactSelector.class.getDeclaredMethod("usesPolynomialSolver",
			MinStExactCostFacts.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null, facts);
	}

	private static String immutableSnapshot(PlacementAnalysis analysis) {
		analysis.assertProgramStructureUnchanged();
		return analysis.analysisFingerprint() + "\n" + analysis.graph().normalizedSignature() + "\n"
			+ analysis.occurrences().stream().map(HopOccurrenceProjection::normalizedSignature).toList() + "\n"
			+ analysis.candidateRuleFacts().orderedFacts();
	}

	private static Map<String,String> installDockerLanCostProperties() {
		Map<String,String> values = Map.of(
			"SYSDS_FED_COST_MEM_BW", "25000",
			"SYSDS_FED_COST_NET_BW", "1250",
			"SYSDS_FED_COST_NET_BW_C2W", "1250",
			"SYSDS_FED_COST_NET_BW_W2C", "1250",
			"SYSDS_FED_COST_NET_SERDES_BW", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W", "210",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C", "14.7",
			"SYSDS_FED_COST_NET_LATENCY", "0.001",
			"SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS", "0",
			"SYSDS_FED_COST_FLOPS", "2147483648");
		Map<String,String> previous = new HashMap<>();
		values.forEach((key, value) -> {
			previous.put(key, System.getProperty(key));
			System.setProperty(key, value);
		});
		return previous;
	}

	private static void restoreProperties(Map<String,String> previous) {
		previous.forEach((key, value) -> {
			if(value == null)
				System.clearProperty(key);
			else
				System.setProperty(key, value);
		});
	}
}
