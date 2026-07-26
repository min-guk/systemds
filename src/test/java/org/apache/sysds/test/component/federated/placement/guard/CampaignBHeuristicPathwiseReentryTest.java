/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** RED/GREEN contract for exact path-local Heuristic re-entry over common analysis facts. */
public class CampaignBHeuristicPathwiseReentryTest {
	@Test
	public void localVectorPrefixReentersAtExactNonzeroConsumerEdge() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(script()));
		Assert.assertEquals("one qualifying aggregate-vector producer", 1,
			analysis.heuristicPolicyFacts().demotions().size());
		var marker = analysis.heuristicPolicyFacts().demotions().get(0);
		var result = new HeuristicPlacementAdapter().select(analysis, Set.of(marker.valueVersion()));

		Assert.assertSame("policy must retain the common immutable analysis", analysis, result.analysis());
		Assert.assertEquals("PATHWISE_REENTRY_POLICY_V2", result.plannerFacts().get("policy"));
		Assert.assertEquals("one pathwise-minimal eligible frontier", "1",
			result.plannerFacts().get("frontierEdgeCount"));
		Assert.assertTrue("producer is exact FED/LOUT", isFedLout(result.assignment().get(marker.producer())));

		List<ObligationKey> obligations = result.selectedObligations();
		Assert.assertEquals("one exact selected relocation obligation", 1, obligations.size());
		ObligationKey obligation = obligations.get(0);
		Assert.assertEquals("local value is operand 1, not a fabricated operand 0", 1,
			obligation.inputPosition());
		Assert.assertSame("obligation binds the common compiled input-edge consumer",
			analysis.requireExactCompiledInputEdge(owner(analysis, obligation.sourceValueVersion()),
				obligation.consumer(), obligation.inputPosition()).consumer(), obligation.consumer());
		Assert.assertEquals("frontier consumer selects runtime-supported FED/FOUT",
			FederatedOutput.FOUT, result.assignment().get(obligation.consumer()).output());
		Assert.assertSame("selected obligation uses the existing analysis relocation",
			obligation.relocationAction(), result.selectedRelocations().get(0));
		Node sibling = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == obligation.consumer()
				&& edge.inputPosition() != obligation.inputPosition())
			.map(edge -> analysis.graph().node(edge.producer()).orElseThrow())
			.filter(node -> node.anchors().contains(obligation.relocationAction().durableAnchor()))
			.findFirst().orElseThrow();
		Assert.assertEquals("frontier is enabled by a selected compatible sibling FOUT",
			FederatedOutput.FOUT, result.assignment().get(sibling.key()).output());
		Assert.assertEquals(obligation.relocationAction().durableAnchor().fType(),
			result.assignment().get(sibling.key()).fType());

		Node localSource = analysis.graph().nodes().stream()
			.filter(node -> node.valueVersion() == obligation.sourceValueVersion()).findFirst().orElseThrow();
		Assert.assertEquals("dependent prefix remains local until its frontier", FederatedOutput.LOUT,
			result.assignment().get(localSource.key()).output());
	}

	@Test
	public void branchDoesNotSynthesizeMissingCommonRelocationFacts() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(branchScript()));
		Assert.assertEquals(1, analysis.heuristicPolicyFacts().demotions().size());
		var marker = analysis.heuristicPolicyFacts().demotions().get(0);
		var result = new HeuristicPlacementAdapter().select(analysis, Set.of(marker.valueVersion()));
		Assert.assertEquals("the common analysis exposes no exact branch relocation obligation", "0",
			result.plannerFacts().get("frontierEdgeCount"));
		Assert.assertTrue("Heuristic must not synthesize a branch relocation",
			analysis.graph().relocationActions().stream().map(action -> action.key()).toList()
				.containsAll(result.selectedRelocations()));
	}

	@Test
	public void missingCompatibleSiblingLeavesThePrefixLocalWithoutSyntheticReentry() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(noSiblingScript()));
		Assert.assertEquals(1, analysis.heuristicPolicyFacts().demotions().size());
		var marker = analysis.heuristicPolicyFacts().demotions().get(0);
		var result = new HeuristicPlacementAdapter().select(analysis, Set.of(marker.valueVersion()));
		Assert.assertEquals("0", result.plannerFacts().get("frontierEdgeCount"));
		Assert.assertTrue(result.selectedRelocations().isEmpty());
		Assert.assertTrue(result.selectedObligations().isEmpty());
	}

	@Test
	public void noMarkerIsExactFedAllParity() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(script()));
		var heuristic = new HeuristicPlacementAdapter().select(analysis, Set.of());
		var fedAll = new FedAllPlacementAdapter().select(analysis);
		Assert.assertEquals(fedAll.assignment(), heuristic.assignment());
		Assert.assertEquals(fedAll.selectedRelocations(), heuristic.selectedRelocations());
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey owner(
		PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey value) {
		return analysis.graph().nodes().stream().filter(node -> node.valueVersion() == value)
			.map(Node::key).findFirst().orElseThrow();
	}

	private static boolean isFedLout(org.apache.sysds.hops.fedplanner.placement.PlacementState state) {
		return state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT;
	}

	private static String script() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "w=z+1;", "y=A*w;", "q=y+1;",
			"print(sum(q));") + "\n";
	}

	private static String branchScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "if(sum(v)>0){y=A*(z+1);}else{y=A*(z-1);}",
			"print(sum(y));") + "\n";
	}

	private static String noSiblingScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "w=z+1;", "print(sum(w));") + "\n";
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
