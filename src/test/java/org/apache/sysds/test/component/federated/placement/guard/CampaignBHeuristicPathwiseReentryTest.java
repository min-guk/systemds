/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathwiseReentryFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPathEdgeKind;
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
	public void siblingBranchesConsumeOnlyTheirExactCommonRelocationFacts() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(branchScript()));
		Assert.assertEquals(1, analysis.heuristicPolicyFacts().demotions().size());
		var path = analysis.heuristicPolicyFacts().paths().get(0);
		Assert.assertEquals("both exact sibling branches publish a path-local frontier", 2,
			path.reentries().size());
		Assert.assertEquals("each sibling branch has its own exact transient-forward path edge", 2,
			path.edges().stream().filter(edge -> edge.kind() == HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD).count());
		Assert.assertEquals("branch-local consumers must remain distinct exact occurrences", 2,
			path.reentries().stream().map(HeuristicPathwiseReentryFact::consumer).distinct().count());
		for(HeuristicPathwiseReentryFact fact : path.reentries())
			assertExactCommonFact(analysis, fact);
		var result = new HeuristicPlacementAdapter().select(analysis, Set.of(path.demotion().valueVersion()));
		Assert.assertEquals("2", result.plannerFacts().get("frontierEdgeCount"));
		Assert.assertEquals(path.reentries().stream().map(HeuristicPathwiseReentryFact::obligation).sorted().toList(),
			result.selectedObligations());
		for(HeuristicPathwiseReentryFact fact : path.reentries()) {
			Assert.assertEquals(fact.siblingFoutState(), result.assignment().get(fact.siblingProducer()));
			Assert.assertEquals(fact.consumerFoutState(), result.assignment().get(fact.consumer()));
		}
	}

	@Test
	public void mergeWithUniqueReachingDefinitionPublishesOneExactReentry() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(mergeScript()));
		var reentryPaths = analysis.heuristicPolicyFacts().paths().stream()
			.filter(path -> !path.reentries().isEmpty()).toList();
		Assert.assertEquals("the unique reaching definition must bind one exact merge re-entry path", 1,
			reentryPaths.size());
		Assert.assertEquals(1, reentryPaths.get(0).edges().stream()
			.filter(edge -> edge.kind() == HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD).count());
		Assert.assertEquals(1, reentryPaths.get(0).reentries().size());
		assertExactCommonFact(analysis, reentryPaths.get(0).reentries().get(0));
		Assert.assertTrue(analysis.graph().nodes().stream()
			.anyMatch(node -> node.valueVersion().versionKind().name().equals("BRANCH_JOIN_PHI")));
	}

	@Test
	public void loopFunctionAndRecompileDoNotPublishUnsupportedPathwiseFacts() throws Exception {
		PlacementAnalysis loop = assertNoCommonReentry(loopScript());
		Assert.assertTrue(loop.graph().nodes().stream()
			.anyMatch(node -> node.key().callSitePath().contains("/loop-body/")));
		PlacementAnalysis function = assertNoCommonReentry(functionScript());
		Assert.assertTrue("the canonical named function identity must remain common-owned",
			function.namedFunctionStatementBlocks().containsKey("foo"));
		Assert.assertTrue("the exact function call must publish compiler-owned boundary nodes",
			function.graph().nodes().stream().anyMatch(node ->
				node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT));
		Assert.assertTrue("the exact function call must publish compiler-owned boundary nodes",
			function.graph().nodes().stream().anyMatch(node ->
				node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT));
		Assert.assertTrue("function-call candidate legality remains open; only unsupported path upload is withheld",
			function.graph().nodes().stream()
				.filter(node -> node.kind()
					== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_CALL)
				.flatMap(node -> node.legalAlternatives().stream())
				.anyMatch(state -> state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
		Assert.assertTrue("no common fact may infer a cross-function path from descendants",
			function.heuristicPolicyFacts().paths().stream().flatMap(path -> path.edges().stream())
				.noneMatch(edge -> !edge.producer().functionNamespace().equals(edge.consumer().functionNamespace())));
		DMLProgram recompile = compile(script());
		PlacementAnalysis before = new NeutralPlacementGraphBuilder().buildAnalysis(recompile);
		var marker = before.heuristicPolicyFacts().demotions().get(0);
		before.hop(marker.producer()).orElseThrow().setRequiresRecompile();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(recompile);
		Assert.assertTrue("recompile occurrence must remain explicit",
			analysis.graph().nodes().stream().anyMatch(node -> "recompile".equals(node.key().recompileContext())));
		Assert.assertTrue("recompile CP/FOUT must retain its explicit oracle reason",
			analysis.graph().nodes().stream().filter(node -> "recompile".equals(node.key().recompileContext()))
				.flatMap(node -> node.exclusions().stream()).anyMatch(exclusion ->
					exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT));
		Assert.assertTrue("no recompile occurrence may own a pathwise upload fact",
			analysis.heuristicPolicyFacts().paths().stream().flatMap(path -> path.reentries().stream())
				.noneMatch(fact -> "recompile".equals(fact.localProducer().recompileContext())
					|| "recompile".equals(fact.consumer().recompileContext())
					|| "recompile".equals(fact.siblingProducer().recompileContext())));
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

	private static void assertExactCommonFact(PlacementAnalysis analysis, HeuristicPathwiseReentryFact fact) {
		Assert.assertSame(analysis.requireExactCompiledInputEdge(fact.localProducer(), fact.consumer(),
			fact.inputPosition()).consumer(), fact.consumer());
		Assert.assertSame(analysis.requireExactCompiledInputEdge(fact.siblingProducer(), fact.consumer(),
			fact.siblingInputPosition()).consumer(), fact.consumer());
		Assert.assertEquals(analysis.graph().node(fact.localProducer()).orElseThrow().valueVersion(),
			fact.sourceValueVersion());
		Assert.assertEquals(analysis.graph().node(fact.siblingProducer()).orElseThrow().valueVersion(),
			fact.siblingValueVersion());
		Assert.assertTrue(analysis.graph().node(fact.siblingProducer()).orElseThrow().anchors()
			.contains(fact.durableAnchor()));
		Assert.assertEquals(fact.durableAnchor().fType(), fact.siblingFoutState().fType());
		Assert.assertEquals(FederatedOutput.FOUT, fact.siblingFoutState().output());
		Assert.assertEquals(FederatedOutput.FOUT, fact.consumerFoutState().output());
		Assert.assertEquals(fact.consumerFoutState().execType(),
			fact.runtimeCandidate().capability().nativeExec());
		Assert.assertEquals(fact.consumerFoutState().output(),
			fact.runtimeCandidate().capability().nativeOutput());
		Assert.assertEquals(fact.consumerFoutState().fType(),
			fact.runtimeCandidate().capability().nativeFoutFType());
		Assert.assertEquals(fact.relocationAction(), fact.obligation().relocationAction());
		Assert.assertEquals(fact.consumer(), fact.obligation().consumer());
		Assert.assertEquals(fact.inputPosition(), fact.obligation().inputPosition());
		Assert.assertEquals(1, fact.modeledDistinctRelocationCost());
		Assert.assertTrue(analysis.graph().relocationActions().stream()
			.anyMatch(action -> action.key() == fact.relocationAction()
				&& action.obligations().stream().anyMatch(obligation -> obligation == fact.obligation())));
	}

	private static PlacementAnalysis assertNoCommonReentry(String script) throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(script));
		Assert.assertTrue(analysis.heuristicPolicyFacts().paths().toString(),
			analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.reentries().stream()).findAny().isEmpty());
		return analysis;
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

	private static String mergeScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "if(sum(v)>0){s=1;}else{s=2;}",
			"w=z+s;", "y=A*w;", "q=y+1;", "print(sum(q));") + "\n";
	}

	private static String loopScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "i=1;", "while(i<2){w=z+1;y=A*w;i=i+1;}",
			"print(i);") + "\n";
	}

	private static String functionScript() {
		return String.join("\n",
			"foo = function(matrix[double] z, matrix[double] A) return (matrix[double] y) {",
			"  i=1;", "  while(i<2){w=z+1;y=A*w;i=i+1;}", "}",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v=matrix(1,2,1);", "z=X%*%v;", "y=foo(z,A);", "print(sum(y));") + "\n";
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
