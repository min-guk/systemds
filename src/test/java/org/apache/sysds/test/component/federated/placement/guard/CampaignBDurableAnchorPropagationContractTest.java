/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
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
		Assert.assertTrue("H10 scalar/scalar-derived matrix must not expose relocation", analysis.graph().relocationActions().isEmpty());
	}

	@Test public void h08FullLocalMatrixOperandDoesNotInheritDurableAnchor() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-08-LATER-ANCHOR-NO-REFED");
		Node y = onlySourceContains(fixture.analysis(), "BinaryOp:b(+):Y");
		assertNoAnchor(y, "A+Z with full local Z must not inherit A anchor");
		assertOnePotentialRelocation(fixture.analysis(), y, "A+Z should expose exact potential upload of local Z to existing A anchor");
	}

	@Test public void h09ScalarBroadcastPreservesDurableAnchor() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		Node a = onlySourceContains(fixture.analysis(), "DataOp:Fed A:A");
		Node y = onlySourceContains(fixture.analysis(), "BinaryOp:b(+):Y");
		assertOneAnchor(a, "H09 A source should carry exact anchor");
		Assert.assertEquals("A+1 scalar broadcast should preserve exact A anchor", a.anchors(), y.anchors());
	}

	@Test public void localVectorBroadcastPreservesWhenOracleKeepsRowDomain() throws Exception {
		PlacementAnalysis analysis = analysis(fed() + "v=matrix(1,1,2);Y=A+v;print(sum(Y));");
		assertOneAnchor(onlySourceContains(analysis, "BinaryOp:b(+):Y"), "A+row-vector broadcast should preserve ROW anchor");
	}

	@Test public void vectorTimesFederatedMatrixLocalOnlyDoesNotInheritDurableAnchor() throws Exception {
		PlacementAnalysis analysis = analysis(fed() + "v=matrix(1,1,4);Y=v%*%A;print(sum(Y));");
		assertNoAnchor(onlySourceContains(analysis, "AggBinaryOp:ba(+*)"), "vector x federated-MM local-only output must not inherit A anchor");
		Assert.assertTrue("vector x federated-MM local-only output must not expose relocation", analysis.graph().relocationActions().isEmpty());
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

	private static void assertOnePotentialRelocation(PlacementAnalysis analysis, Node consumer, String message) {
		List<RelocationAction> actions = analysis.graph().relocationActions().stream()
			.filter(action -> action.key().compatibleConsumers().contains(consumer.key())).toList();
		Assert.assertEquals(message + " | action count", 1, actions.size());
		RelocationAction action = actions.get(0);
		Assert.assertEquals(message + " | one local matrix input obligation", 1, action.obligations().size());
		var obligation = action.obligations().get(0);
		Assert.assertEquals(message + " | exact consumer", consumer.key(), obligation.consumer());
		Assert.assertEquals(message + " | exact local matrix input", 1, obligation.inputPosition());
		Assert.assertEquals(message + " | obligation uses action target", action.key().targetPlacement(),
			obligation.requiredPlacement());
		Assert.assertEquals(message + " | obligation uses action key", action.key(), obligation.relocationAction());
		Assert.assertEquals(message + " | target matches anchor FType", action.key().durableAnchor().fType(),
			action.key().targetPlacement().fType());
		Assert.assertEquals(message + " | exact target", "FED/FOUT/ROW/SHAPE_DEPENDENT",
			action.key().targetPlacement().normalizedSignature());
		Assert.assertTrue(message + " | consumer has exact target candidate",
			consumer.legalAlternatives().contains(action.key().targetPlacement()));
		Assert.assertTrue(message + " | existing A FederationMap anchor",
			action.key().durableAnchor().placementId().startsWith("fed-init:A"));
		Assert.assertEquals(message + " | deterministic exact control-region scope",
			consumer.key().controlRegion().normalizedSignature(), action.key().statementBlockScope());
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
