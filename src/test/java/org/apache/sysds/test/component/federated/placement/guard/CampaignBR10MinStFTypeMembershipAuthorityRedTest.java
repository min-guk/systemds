/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
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
		return program;
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream().map(HopOccurrenceProjection::key).toList();
	}

	private static String immutableSnapshot(PlacementAnalysis analysis) {
		analysis.assertProgramStructureUnchanged();
		return analysis.analysisFingerprint() + "\n" + analysis.graph().normalizedSignature() + "\n"
			+ analysis.occurrences().stream().map(HopOccurrenceProjection::normalizedSignature).toList() + "\n"
			+ analysis.candidateRuleFacts().orderedFacts();
	}
}
