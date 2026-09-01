/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateFallbackMaterialization;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for exact upload ownership before normalized placement selection. */
public class NeutralPlacementGraphUploadRelocationRedTest {
	@Test
	public void sparseOracleDomainRetainsNativeAbsentWithoutInventingRefed() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		NormalizedPlannerResult fedAll = new FedAllPlacementAdapter().select(analysis);
		Node local = uniqueNode(analysis, "S", "dg(rand)");
		Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>(fedAll.selectedStates());
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants = CandidateSelections.feasibleVariants(
			analysis, analysis.graph().relocationActions(), assignment);
		List<CandidateSelectionReceipt> nativeRows = new ArrayList<>(fedAll.selectedCandidateSelections());
		for(var edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.producer() != local.key())
				continue;
			CandidateSelectionReceipt nativeRow = variants.get(edge.consumer()).stream()
				.filter(candidate -> !candidate.rule().orderedInputs().get(edge.inputPosition()).present())
				.filter(candidate -> candidate.fallbackMaterializations().stream().noneMatch(fallback ->
					fallback.inputPosition() == edge.inputPosition()))
				.findFirst().orElseThrow(() -> new AssertionError(
					"fixture requires a native ABSENT_LOCAL candidate at the selected placement"));
			nativeRows.removeIf(candidate -> candidate.rule().parentOccurrence() == edge.consumer());
			nativeRows.add(nativeRow);
		}
		nativeRows = nativeRows.stream().sorted().toList();
		var nativeChoices = RelocationSelections.selectCanonical(analysis,
			analysis.graph().relocationActions(), assignment, nativeRows, (demand, action) -> true);
		var nativeEmitted = RelocationSelections.emittedActions(analysis,
			analysis.graph().relocationActions(), assignment, nativeRows, nativeChoices);

		Assert.assertTrue("same final placement with native ABSENT_LOCAL must not invent CP-to-FOUT",
			nativeEmitted.stream().noneMatch(action -> action.sourceValueVersion().equals(local.valueVersion())));
		Assert.assertTrue("selected candidates must not synthesize PRESENT authority on ABSENT_LOCAL rows",
			fedAll.selectedCandidateSelections().stream()
				.allMatch(candidate -> candidate.fallbackMaterializations().isEmpty()));
		Assert.assertEquals("without an exact PRESENT oracle row, FedAll must retain the native row",
			fedAll.selectedCandidateSelections(), nativeRows);
	}

	@Test
	public void everyPublishedReceiptOrUploadIsBackedByAnExactPresentOracleCandidate() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		for(RelocationAction action : analysis.graph().relocationActions())
			for(var obligation : action.obligations())
				Assert.assertTrue("relocation must be backed by an AVAILABLE exact PRESENT candidate: "
					+ action.key().normalizedSignature() + " obligation=" + obligation.normalizedSignature(),
					analysis.candidateRuleFacts().orderedFacts().stream().anyMatch(candidate ->
						candidate.key().parentOccurrence() == obligation.consumer()
							&& candidate.status() == PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE
							&& obligation.inputPosition() < candidate.key().orderedInputs().size()
							&& candidate.key().orderedInputs().get(obligation.inputPosition()).present()
							&& candidate.key().orderedInputs().get(obligation.inputPosition()).fType()
								== action.key().materializationFType()
							&& candidate.allowedEmissionFacts().stream().anyMatch(emission ->
								emission.emissionState().placementState()
									.equals(action.key().targetPlacement()))));
	}

	@Test
	public void candidateReceiptRejectsLegacyAbsentLocalFallbackAuthority() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		CandidateSelectionReceipt nativeRow = CandidateSelections.feasibleVariants(analysis,
			analysis.graph().relocationActions(), new FedAllPlacementAdapter().select(analysis).selectedStates())
			.values().stream().flatMap(List::stream)
			.filter(candidate -> candidate.rule().orderedInputs().stream().anyMatch(input -> !input.present()))
			.findFirst().orElseThrow();
		try {
			new CandidateSelectionReceipt(nativeRow.rule(), nativeRow.emission(),
				List.of(new CandidateFallbackMaterialization(0,
					org.apache.sysds.hops.fedplanner.FTypes.FType.ROW)));
			Assert.fail("ABSENT_LOCAL candidate must not own post-materialization authority");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("exact PRESENT row"));
		}
	}

	@Test
	public void localMatrixNativeFedOperandDoesNotCreateSyntheticUploadAction() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Node local = uniqueNode(analysis, "S", "dg(rand)");
		federatedSource(analysis);
		List<PlacementAnalysis.CompiledInputEdgeFact> localEdges = analysis.compiledInputEdgesInCanonicalOrder()
			.stream().filter(edge -> edge.producer() == local.key()).toList();
		List<Node> consumers = localEdges.stream()
			.map(edge -> analysis.graph().node(edge.consumer()).orElseThrow()).toList();

		Assert.assertEquals("P4_BUILDER_PRESERVES_TWO_LOCAL_MATRIX_INPUT_EDGES", 2, localEdges.size());
		Assert.assertTrue("P4_LOCAL_MATRIX_IS_EXACT_SECOND_INPUT",
			localEdges.stream().allMatch(edge -> edge.inputPosition() == 1));
		Assert.assertTrue("P4_UPLOAD_SOURCE_IS_GENUINELY_LOCAL",
			selected(plan, local, ExecType.CP, FederatedOutput.LOUT));
		Assert.assertTrue("P4_UPLOAD_SOURCE_HAS_NO_DURABLE_ANCHOR", local.anchors().isEmpty());
		Assert.assertTrue("P4_FED_CONSUMERS_DO_NOT_DUPLICATE_DURABLE_ANCHOR",
			consumers.stream().allMatch(node -> node.anchors().isEmpty()));
		Assert.assertEquals("P4_FED_CONSUMER_COUNT", 2, consumers.size());
		Assert.assertTrue("P4_FED_CONSUMERS_HAVE_LEGAL_SELECTED_FOUT",
			consumers.stream().allMatch(node -> node.legalAlternatives().contains(plan.selectedStates().get(node.key()))
				&& selected(plan, node, ExecType.FED, FederatedOutput.FOUT)));

		List<RelocationAction> uploads = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(local.valueVersion())).toList();
		Assert.assertTrue("ABSENT_LOCAL runtime operand must not become a synthetic CP-to-FOUT action",
			uploads.isEmpty());
		List<RelocationAction> selectedUploads = uploads.stream()
			.filter(action -> plan.selectedRelocations().contains(action.key())).toList();
		Assert.assertTrue("FedAll must not select an action absent from exact candidate facts",
			selectedUploads.isEmpty());
	}

	@Test
	public void alreadyCompatibleFederatedSourceDoesNotActivateRelocation() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-11"));
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Node source = federatedSource(analysis);
		List<Node> consumers = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == source.key())
			.map(edge -> analysis.graph().node(edge.consumer()).orElseThrow()).toList();

		Assert.assertTrue("P4_NEGATIVE_SOURCE_IS_ALREADY_FOUT_COMPATIBLE",
			selected(plan, source, ExecType.FED, FederatedOutput.FOUT));
		Assert.assertFalse("P4_NEGATIVE_B11_HAS_FEDERATED_CONSUMER", consumers.isEmpty());
		Assert.assertTrue("P4_NEGATIVE_B11_OTHER_INPUT_IS_SCALAR",
			consumers.stream().map(node -> analysis.hop(node.key()).orElseThrow())
				.anyMatch(hop -> hop.getInput().stream().anyMatch(input -> input.getDataType().isScalar())));
		Assert.assertTrue("P4_COMPATIBLE_FOUT_SOURCE_DEACTIVATES_RELOCATION",
			plan.selectedRelocations().stream().noneMatch(action ->
				action.sourceValueVersion().equals(source.valueVersion())));
	}

	@Test
	public void twoPresentInputsOnSamePhysicalPoolUseTwoDirectReceiptsAndNoRefed() throws Exception {
		assertTwoPresentInputPoolCoherence(true);
	}

	@Test
	public void twoPresentInputsOnDistinctPhysicalPoolsChooseOneCommonAnchorAndOneRefed() throws Exception {
		assertTwoPresentInputPoolCoherence(false);
	}

	private static void assertTwoPresentInputPoolCoherence(boolean samePhysicalPool) throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compileTwoFederatedSourceFixture(samePhysicalPool));
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Node consumer = uniqueNode(analysis, "C", "b(+)");
		Node left = uniqueNode(analysis, "A", "Fed A");
		Node right = uniqueNode(analysis, "B", "Fed B");

		CandidateSelectionReceipt selected = plan.selectedCandidateSelections().stream()
			.filter(candidate -> candidate.rule().parentOccurrence() == consumer.key())
			.findFirst().orElseThrow();
		Assert.assertEquals("FedAll must select the exact two-input row", 2,
			selected.rule().orderedInputs().size());
		Assert.assertTrue("FedAll must federate every exactly available matrix input",
			selected.rule().orderedInputs().stream().allMatch(input -> input.present()
				&& input.fType() == org.apache.sysds.hops.fedplanner.FTypes.FType.ROW));

		var resolved = RelocationSelections.resolveAndValidate(analysis,
			analysis.graph().relocationActions(), plan.selectedStates(),
			plan.selectedCandidateSelections(), plan.selectedRelocationChoices()).stream()
			.filter(choice -> choice.obligation().consumer() == consumer.key()).toList();
		Assert.assertEquals("each PRESENT matrix input requires one graph-owned receipt", 2, resolved.size());
		Assert.assertEquals("both receipts must bind the consumer to one exact target anchor", 1,
			resolved.stream().map(choice -> choice.action().key().durableAnchor()).distinct().count());
		long emissions = resolved.stream().filter(RelocationSelections.ResolvedChoice::requiresEmission).count();
		Assert.assertEquals(samePhysicalPool
			? "same physical pool needs no REFED"
			: "distinct physical pools need exactly one REFED into the chosen common pool",
			samePhysicalPool ? 0L : 1L, emissions);
		if(samePhysicalPool)
			Assert.assertTrue("both existing FederationMaps are direct receipts",
				resolved.stream().allMatch(choice -> !choice.requiresEmission()));
		else
			Assert.assertEquals("one source remains direct and the other is explicitly rematerialized", 1,
				resolved.stream().filter(choice -> !choice.requiresEmission()).count());

		long emittedForInputs = plan.selectedRelocations().stream().filter(action ->
			(action.sourceValueVersion().equals(left.valueVersion())
				|| action.sourceValueVersion().equals(right.valueVersion()))
				&& action.compatibleConsumers().contains(consumer.key())).count();
		Assert.assertEquals("normalized emitted actions must match resolved physical receipts",
			samePhysicalPool ? 0L : 1L, emittedForInputs);
	}

	@Test
	public void functionCallPlaceholderDoesNotOwnArgumentRelocations() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFunctionFixture());
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		List<Node> sources = federatedSources(analysis);
		List<Node> calls = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NodeKind.FUNCTION_CALL).toList();

		Assert.assertEquals("P4_FUNCTION_FIXTURE_REQUIRES_TWO_FEDERATED_ARGUMENTS", 2, sources.size());
		Assert.assertEquals("P4_FUNCTION_FIXTURE_REQUIRES_ONE_CALL_PLACEHOLDER", 1, calls.size());
		Node call = calls.get(0);
		Assert.assertEquals("P4_FUNCTION_CALL_RETAINS_BOTH_EXACT_MATRIX_INPUT_EDGES", 2,
			analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == call.key())
				.filter(edge -> sources.stream().anyMatch(source -> source.key() == edge.producer())).count());
		Assert.assertTrue("P4_FUNCTION_FIXTURE_EXPOSES_MIXED_PRESENT_ABSENT_CANDIDATES",
			analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == call.key())
				.anyMatch(fact -> fact.key().orderedInputs().stream().anyMatch(input -> input.present())
					&& fact.key().orderedInputs().stream().anyMatch(input -> !input.present())));
		Assert.assertTrue("P4_FUNCTION_ARGUMENTS_RETAIN_EXACT_FED_FOUT_PLACEMENT",
			sources.stream().allMatch(source -> selected(plan, source, ExecType.FED, FederatedOutput.FOUT)));
		Assert.assertTrue("P4_FUNCTION_PLACEHOLDER_OWNS_NO_CALLER_SIDE_RELOCATION",
			analysis.graph().relocationActions().stream()
				.noneMatch(action -> action.obligations().stream()
					.anyMatch(obligation -> obligation.consumer() == call.key())));
		Assert.assertTrue("P4_NORMALIZED_PLAN_EMITS_NO_FUNCTION_PLACEHOLDER_RELOCATION",
			plan.selectedRelocations().stream()
				.noneMatch(action -> action.compatibleConsumers().contains(call.key())));
	}

	private static boolean selected(NormalizedPlannerResult plan, Node node, ExecType exec,
		FederatedOutput output) {
		PlacementState selected = plan.selectedStates().get(node.key());
		return selected != null && selected.execType() == exec && selected.output() == output;
	}

	private static Node uniqueNode(PlacementAnalysis analysis, String name, String opcode) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> name.equals(hop.getName()) && opcode.equals(hop.getOpString())).orElse(false)).toList();
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_" + name + '_' + opcode, 1, matches.size());
		return matches.get(0);
	}

	private static Node federatedSource(PlacementAnalysis analysis) {
		List<Node> matches = federatedSources(analysis);
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_FEDERATED_SOURCE", 1, matches.size());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_DURABLE_ANCHOR", 1, matches.get(0).anchors().size());
		return matches.get(0);
	}

	private static List<Node> federatedSources(PlacementAnalysis analysis) {
		return analysis.graph().nodes().stream()
			.filter(node -> analysis.hop(node.key()).orElseThrow() instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.toList();
	}

	private static DMLProgram compileFixture() throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));\n"
			+ "S=rand(rows=4,cols=2,seed=7);\n"
			+ "Y1=X+S;\nY2=X-S;\n"
			+ "write(Y1,\"/tmp/g005-p4-y1\",format=\"binary\");\n"
			+ "write(Y2,\"/tmp/g005-p4-y2\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static DMLProgram compileFunctionFixture() throws Exception {
		String script = "X=federated(addresses=list(\"worker1:8001/data/P2P2D_features.data\"),"
			+ "ranges=list(list(0,0),list(50000,2100)));\n"
			+ "Y=federated(addresses=list(\"worker1:8001/data/P2P2D_labels.data\"),"
			+ "ranges=list(list(0,0),list(50000,1)));\n"
			+ "M=lm(X=X,y=Y,verbose=FALSE,tol=1e-9);\n"
			+ "write(M,\"/tmp/g007-p4-lm\",format=\"csv\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static DMLProgram compileTwoFederatedSourceFixture(boolean samePhysicalPool) throws Exception {
		String leftAddresses = "list(\"localhost:1234/A1\",\"localhost:1235/A2\")";
		String rightAddresses = samePhysicalPool
			? "list(\"localhost:1234/B1\",\"localhost:1235/B2\")"
			: "list(\"localhost:2234/B1\",\"localhost:2235/B2\")";
		String ranges = "list(list(0,0),list(2,2),list(2,0),list(4,2))";
		String script = "A=federated(addresses=" + leftAddresses + ",ranges=" + ranges + ");\n"
			+ "B=federated(addresses=" + rightAddresses + ",ranges=" + ranges + ");\n"
			+ "C=A+B;\nwrite(C,\"/tmp/g014-two-source-C\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
