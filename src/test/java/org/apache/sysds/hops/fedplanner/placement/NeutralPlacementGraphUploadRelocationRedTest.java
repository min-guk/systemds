/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for deriving one anchor-scoped CP-to-FOUT upload before exact selection. */
public class NeutralPlacementGraphUploadRelocationRedTest {
	@Test
	public void localMatrixSharedByFederatedConsumersRequiresOneCanonicalUploadAction() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compileFixture());
		NormalizedPlannerResult plan = new FedAllPlacementAdapter().select(analysis);
		Node local = uniqueNode(analysis, "S", "dg(rand)");
		Node anchor = federatedSource(analysis);
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
		Assert.assertEquals("P4_FED_CONSUMERS_SHARE_ONE_DURABLE_ANCHOR", 1,
			consumers.stream().flatMap(node -> node.anchors().stream()).distinct().count());
		Assert.assertEquals("P4_FED_CONSUMER_COUNT", 2, consumers.size());
		Assert.assertTrue("P4_FED_CONSUMERS_HAVE_LEGAL_SELECTED_FOUT",
			consumers.stream().allMatch(node -> node.legalAlternatives().contains(plan.selectedStates().get(node.key()))
				&& selected(plan, node, ExecType.FED, FederatedOutput.FOUT)));

		List<RelocationAction> uploads = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(local.valueVersion())).toList();
		Assert.assertEquals("P4_BUILDER_REQUIRES_ONE_SHARED_CP_TO_FOUT_ACTION", 1, uploads.size());
		RelocationAction upload = uploads.get(0);
		Assert.assertEquals("P4_UPLOAD_USES_FEDERATED_SIBLING_ANCHOR", anchor.anchors().get(0),
			upload.key().durableAnchor());
		Assert.assertEquals("P4_SHARED_UPLOAD_NAMES_BOTH_COMPATIBLE_CONSUMERS",
			consumers.stream().map(Node::key).sorted().toList(), upload.key().compatibleConsumers());
		Assert.assertEquals("P4_SHARED_UPLOAD_HAS_ONE_OBLIGATION_PER_EXACT_INPUT", 2,
			upload.obligations().size());
		List<String> expectedEndpoints = localEdges.stream().map(edge ->
			edge.consumer().normalizedSignature() + '@' + edge.inputPosition()).sorted().toList();
		List<String> actualEndpoints = upload.obligations().stream().map(obligation ->
			obligation.consumer().normalizedSignature() + '@' + obligation.inputPosition()).sorted().toList();
		Assert.assertEquals("P4_UPLOAD_OBLIGATIONS_MATCH_EXACT_COMPILED_INPUTS",
			expectedEndpoints, actualEndpoints);
		Assert.assertTrue("P4_UPLOAD_OBLIGATIONS_RETAIN_EXACT_ACTION_AUTHORITY",
			upload.obligations().stream().allMatch(obligation ->
				obligation.sourceValueVersion().equals(local.valueVersion())
					&& obligation.relocationAction().equals(upload.key())
					&& obligation.requiredPlacement().equals(upload.key().targetPlacement())));
		Assert.assertTrue("P4_UPLOAD_TARGET_IS_ANCHOR_TYPED_FED_FOUT",
			upload.key().targetPlacement().execType() == ExecType.FED
				&& upload.key().targetPlacement().output() == FederatedOutput.FOUT
				&& upload.key().targetPlacement().fType() == upload.key().durableAnchor().fType());
		Assert.assertTrue("P4_UPLOAD_TARGET_AND_OBLIGATIONS_ARE_CONSUMER_LEGAL",
			consumers.stream().allMatch(consumer -> consumer.legalAlternatives().contains(upload.key().targetPlacement()))
				&& upload.obligations().stream().allMatch(obligation -> analysis.graph().node(obligation.consumer())
					.orElseThrow().legalAlternatives().contains(obligation.requiredPlacement())));
		Assert.assertTrue("P4_UPLOAD_TARGET_PRESERVES_SHAPE_DEPENDENCE",
			upload.key().targetPlacement().shapeDependent()
				&& upload.obligations().stream().allMatch(obligation -> obligation.requiredPlacement().shapeDependent()));
		Assert.assertTrue("P4_ACTIVE_UPLOAD_MATCHES_SELECTED_CONSUMER_PLACEMENT",
			upload.obligations().stream().allMatch(obligation -> obligation.requiredPlacement()
				.equals(plan.selectedStates().get(obligation.consumer()))));
		Assert.assertTrue("P4_EXACT_FEDALL_SELECTS_REQUIRED_UPLOAD",
			plan.selectedRelocations().contains(upload.key()));
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
		List<Node> matches = analysis.graph().nodes().stream()
			.filter(node -> analysis.hop(node.key()).orElseThrow() instanceof DataOp data
				&& data.getOp() == OpOpData.FEDERATED)
			.toList();
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_FEDERATED_SOURCE", 1, matches.size());
		Assert.assertEquals("P4_FIXTURE_REQUIRES_ONE_DURABLE_ANCHOR", 1, matches.get(0).anchors().size());
		return matches.get(0);
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
}
