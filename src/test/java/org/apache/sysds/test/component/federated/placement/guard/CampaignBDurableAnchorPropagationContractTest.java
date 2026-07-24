/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Focused contract for durable-anchor propagation semantics shared by first-pass and CFG closure. */
public class CampaignBDurableAnchorPropagationContractTest {
	@Test public void h10ScalarAndMatrixFromSumDoNotInheritDurableAnchor() throws Exception {
		PlacementAnalysis analysis = analysis(fed() + "s=sum(A);X=matrix(s,4,2);print(sum(X));");
		assertNoAnchor(onlySourceContains(analysis, "AggUnaryOp:ua(+RC):s"), "sum(A) scalar must not carry durable anchor");
		assertNoAnchor(onlySourceContains(analysis, "DataGenOp:dg(rand):X"), "matrix(sum(A),...) must not carry durable anchor");
	}

	@Test public void h08FullLocalMatrixOperandDoesNotInheritDurableAnchor() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-08-LATER-ANCHOR-NO-REFED");
		assertNoAnchor(onlySourceContains(fixture.analysis(), "BinaryOp:b(+):Y"), "A+Z with full local Z must not inherit A anchor");
	}

	@Test public void h09ScalarBroadcastPreservesDurableAnchor() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		assertOneAnchor(node(fixture.analysis(), fixture.roles().get("Y_INDEPENDENT").normalizedSignature()), "A+1 scalar broadcast should preserve A anchor");
	}

	@Test public void localVectorBroadcastPreservesWhenOracleKeepsRowDomain() throws Exception {
		PlacementAnalysis analysis = analysis(fed() + "v=matrix(1,1,2);Y=A+v;print(sum(Y));");
		assertOneAnchor(onlySourceContains(analysis, "BinaryOp:b(+):Y"), "A+row-vector broadcast should preserve ROW anchor");
	}

	@Test public void vectorTimesFederatedMatrixLocalOnlyDoesNotInheritDurableAnchor() throws Exception {
		PlacementAnalysis analysis = analysis(fed() + "v=matrix(1,1,4);Y=v%*%A;print(sum(Y));");
		assertNoAnchor(onlySourceContains(analysis, "AggBinaryOp:ba(+*)"), "vector x federated-MM local-only output must not inherit A anchor");
	}

	@Test public void h03RecurringTWriteTReadPreservesSameDurableAnchor() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-03-LOOP-RECOMPILE");
		var anchor = fixture.candidateProofs().values().iterator().next().anchor();
		List<String> owners = fixture.analysis().graph().nodes().stream()
			.filter(n -> n.anchors().stream().anyMatch(anchor::equals))
			.map(n -> n.valueVersion().lexicalVariable() + ':' + n.kind()).sorted().toList();
		Assert.assertTrue("H-03 A source owner present", owners.contains("A:OPERATION"));
		Assert.assertTrue("H-03 TWrite A owner present", owners.contains("A:TRANSIENT_WRITE"));
		Assert.assertTrue("H-03 TRead A owner present", owners.contains("A:TRANSIENT_READ"));
	}

	@Test public void branchMixedAnchoredAndLocalReachingDefinitionsTerminateAnchor() throws Exception {
		PlacementAnalysis analysis = analysis(fed() + "X=matrix(1,4,2);if(sum(A)>0){X=A+1;}else{X=matrix(2,4,2);}Y=X+1;print(sum(Y));");
		assertNoAnchor(onlySourceContains(analysis, "BinaryOp:b(+):Y"), "mixed anchored/local reaching definitions must terminate durable anchor");
	}

	private static PlacementAnalysis analysis(String script) throws Exception {
		return new NeutralPlacementGraphBuilder().buildAnalysis(compile(script));
	}

	private static Node onlySourceContains(PlacementAnalysis analysis, String sourceFragment) {
		List<Node> nodes = analysis.graph().nodes().stream()
			.filter(Node::emittedWork)
			.filter(n -> n.key().canonicalSourceOrigin().contains(sourceFragment)).toList();
		Assert.assertEquals("exactly one emitted source node for " + sourceFragment, 1, nodes.size());
		return nodes.get(0);
	}

	private static Node node(PlacementAnalysis analysis, String normalizedKey) {
		return analysis.graph().nodes().stream()
			.filter(n -> n.key().normalizedSignature().equals(normalizedKey)).findFirst().orElseThrow();
	}

	private static void assertNoAnchor(Node node, String message) {
		Assert.assertTrue(message + " | " + node.key(), node.anchors().isEmpty());
	}

	private static void assertOneAnchor(Node node, String message) {
		Assert.assertEquals(message + " | " + node.key(), 1, node.anchors().size());
	}

	private static String fed() {
		return "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
	}

	private static DMLProgram compile(String script) throws Exception {
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
