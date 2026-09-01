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
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
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

		HeuristicPathwiseReentryFact frontier = analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.reentries().stream()).findFirst().orElseThrow();
		assertExactCommonFact(analysis, frontier);
		Assert.assertEquals("local value is operand 1, not a fabricated operand 0", 1,
			frontier.inputPosition());
		Assert.assertSame("frontier binds the common compiled input-edge consumer",
			analysis.requireExactCompiledInputEdge(frontier.localProducer(), frontier.consumer(),
				frontier.inputPosition()).consumer(), frontier.consumer());
		Assert.assertEquals("frontier consumer selects runtime-supported FED/FOUT",
			FederatedOutput.FOUT, result.assignment().get(frontier.consumer()).output());
		Assert.assertEquals(ExecType.FED, result.assignment().get(frontier.consumer()).execType());
		Assert.assertEquals("when both partition-aligned and broadcast uploads are legal, the heuristic"
			+ " must choose the non-replicating anchor-aligned frontier",
			frontier.relocationAction().durableAnchor().fType(),
			frontier.relocationAction().materializationFType());
		Node sibling = analysis.graph().node(frontier.siblingProducer()).orElseThrow();
		Assert.assertEquals("frontier is enabled by a selected compatible sibling FOUT",
			FederatedOutput.FOUT, result.assignment().get(sibling.key()).output());
		Assert.assertEquals(frontier.durableAnchor().fType(),
			result.assignment().get(sibling.key()).fType());

		Assert.assertEquals("dependent prefix remains local until its frontier", FederatedOutput.LOUT,
			result.assignment().get(frontier.localProducer()).output());
		Assert.assertEquals(ExecType.CP, result.assignment().get(frontier.localProducer()).execType());
		Assert.assertTrue("selector may use an ABSENT_LOCAL runtime row, but cannot invent obligations",
			Set.of(frontier.obligation()).containsAll(result.selectedObligations()));
		Assert.assertTrue("selector may elide the upload, but every selected upload remains analysis-owned",
			Set.of(frontier.relocationAction()).containsAll(result.selectedRelocations()));
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
		Set<?> analysisOwnedObligations = path.reentries().stream()
			.map(HeuristicPathwiseReentryFact::obligation).collect(java.util.stream.Collectors.toSet());
		Set<?> analysisOwnedRelocations = path.reentries().stream()
			.map(HeuristicPathwiseReentryFact::relocationAction).collect(java.util.stream.Collectors.toSet());
		Assert.assertTrue("selected obligations must be a subset of exact analysis-owned frontier facts",
			analysisOwnedObligations.containsAll(result.selectedObligations()));
		Assert.assertTrue("selected relocations must be a subset of exact analysis-owned frontier facts",
			analysisOwnedRelocations.containsAll(result.selectedRelocations()));
		for(HeuristicPathwiseReentryFact fact : path.reentries()) {
			var sibling = result.assignment().get(fact.siblingProducer());
			var consumer = result.assignment().get(fact.consumer());
			Assert.assertEquals(FederatedOutput.FOUT, sibling.output());
			Assert.assertEquals(fact.durableAnchor().fType(), sibling.fType());
			Assert.assertEquals(ExecType.FED, consumer.execType());
			Assert.assertEquals(FederatedOutput.FOUT, consumer.output());
			Assert.assertEquals(fact.durableAnchor().fType(), consumer.fType());
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
	public void complexOccurrencesPublishOnlyExactAnalysisOwnedFacts() throws Exception {
		PlacementAnalysis loop = new NeutralPlacementGraphBuilder().buildAnalysis(compile(loopScript()));
		Assert.assertTrue(loop.graph().nodes().stream()
			.anyMatch(node -> node.key().callSitePath().contains("/loop-body/")));
		List<HeuristicPathwiseReentryFact> loopReentries = loop.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.reentries().stream()).toList();
		boolean loopHasFoutCandidate = loop.graph().nodes().stream()
			.filter(node -> node.key().callSitePath().contains("/loop-body/"))
			.anyMatch(node -> node.legalAlternatives().stream().anyMatch(state ->
				state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT));
		if(!loopHasFoutCandidate)
			Assert.assertTrue("a loop body with no runtime-native FOUT candidate must not fabricate re-entry",
				loopReentries.isEmpty());
		loopReentries.forEach(fact -> assertExactCommonFact(loop, fact));

		PlacementAnalysis function = new NeutralPlacementGraphBuilder()
			.buildAnalysis(compile(functionScript(), false));
		Assert.assertTrue("the canonical named function identity must remain common-owned",
			function.namedFunctionStatementBlocks().containsKey("foo"));
		Assert.assertTrue("the exact function call must publish compiler-owned boundary nodes",
			function.graph().nodes().stream().anyMatch(node ->
				node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT));
		Assert.assertTrue("the exact function call must publish compiler-owned boundary nodes",
			function.graph().nodes().stream().anyMatch(node ->
				node.kind() == org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT));
		Assert.assertTrue("function-call placeholders must not own caller-side relocation obligations",
			function.graph().relocationActions().stream().flatMap(action -> action.obligations().stream())
				.noneMatch(obligation -> function.graph().node(obligation.consumer()).orElseThrow().kind()
					== org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_CALL));
		Assert.assertTrue("no common fact may infer a cross-function path from descendants",
			function.heuristicPolicyFacts().paths().stream().flatMap(path -> path.edges().stream())
				.noneMatch(edge -> !edge.producer().functionNamespace().equals(edge.consumer().functionNamespace())));
		List<HeuristicPathwiseReentryFact> functionReentries = function.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.reentries().stream()).toList();
		Assert.assertTrue("function-local re-entry must not fabricate a caller/callee edge",
			functionReentries.stream().allMatch(fact -> fact.localProducer().functionNamespace()
				.equals(fact.consumer().functionNamespace())
				&& fact.siblingProducer().functionNamespace().equals(fact.consumer().functionNamespace())));
		functionReentries.forEach(fact -> assertExactCommonFact(function, fact));

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
		List<HeuristicPathwiseReentryFact> recompileReentries = analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.reentries().stream()).toList();
		Assert.assertTrue("a recompile clone must never own a pathwise upload fact",
			recompileReentries.stream().noneMatch(fact -> List.of(fact.localProducer(), fact.consumer(),
				fact.siblingProducer()).stream().anyMatch(key -> analysis.graph().node(key).orElseThrow()
					.valueVersion().versionKind()
					== org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind.CLONE_RECOMPILE)));
		recompileReentries.forEach(fact -> assertExactCommonFact(analysis, fact));
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

	@Test
	public void lmNestedDemotionsStayLocalWithoutUnprovenRefed() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(compile(lmScript()));
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toSet());
		Assert.assertFalse("LM must exercise the typed Heuristic demotion policy", markers.isEmpty());
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey> localPrefix =
			analysis.heuristicPolicyFacts().paths().stream()
				.filter(path -> markers.contains(path.demotion().valueVersion()))
				.flatMap(path -> path.localPrefix().stream())
				.collect(java.util.stream.Collectors.toSet());
		var conflictingBaseActions = analysis.graph().relocationActions().stream()
			.filter(action -> !action.directSourcePlacements().isEmpty())
			.filter(action -> analysis.graph().nodes().stream()
				.anyMatch(node -> node.valueVersion().equals(action.key().sourceValueVersion())
					&& localPrefix.contains(node.key())))
			.toList();
		Assert.assertFalse("LM must contain a direct-source proof whose source becomes path-local",
			conflictingBaseActions.isEmpty());

		var result = new HeuristicPlacementAdapter().select(analysis, markers);
		Assert.assertEquals("LM has no proven exact re-entry frontier", "0",
			result.plannerFacts().get("frontierEdgeCount"));
		Assert.assertTrue("No local-prefix relocation may survive without an exact frontier",
			result.selectedRelocations().isEmpty());
		Assert.assertTrue("No local-prefix obligation may survive without an exact frontier",
			result.selectedObligations().isEmpty());
		for(var action : result.selectorGraph().relocationActions()) {
			Set<org.apache.sysds.hops.fedplanner.placement.PlacementState> legalSourceStates =
				result.selectorGraph().nodes().stream()
					.filter(node -> node.valueVersion().equals(action.key().sourceValueVersion()))
					.flatMap(node -> node.legalAlternatives().stream())
					.collect(java.util.stream.Collectors.toSet());
			Assert.assertTrue("direct-source metadata must be a subset of the filtered source universe",
				legalSourceStates.containsAll(action.directSourcePlacements()));
		}
		for(var baseAction : conflictingBaseActions) {
			Assert.assertTrue("an unproven local-prefix relocation must be removed from the selector graph",
				result.selectorGraph().relocationActions().stream()
					.noneMatch(action -> action.key().equals(baseAction.key())));
		}
		var nestedMarkers = analysis.heuristicPolicyFacts().paths().stream()
			.flatMap(path -> path.localPrefix().stream()
				.filter(key -> key != path.demotion().producer()))
			.filter(key -> analysis.heuristicPolicyFacts().demotions().stream()
				.anyMatch(marker -> marker.producer() == key))
			.collect(java.util.stream.Collectors.toSet());
		Assert.assertFalse("LM must expose at least one downstream nested demotion", nestedMarkers.isEmpty());
		for(var nested : nestedMarkers) {
			var state = result.assignment().get(nested);
			Assert.assertEquals("downstream nested demotion continues locally", ExecType.CP, state.execType());
			Assert.assertEquals(FederatedOutput.LOUT, state.output());
		}
	}

	@Test
	public void lmSingleWorkerFullLayoutRetainsAReachableHeuristicPolicy() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			compile(lmSingleWorkerScript()));
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey> markers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.valueVersion())
				.collect(java.util.stream.Collectors.toSet());

		Assert.assertFalse("single-worker LM must exercise FULL-layout demotions", markers.isEmpty());
		Set<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey> markerProducers =
			analysis.heuristicPolicyFacts().demotions().stream().map(fact -> fact.producer())
				.collect(java.util.stream.Collectors.toSet());
		Assert.assertTrue("a native FED/FOUT continuation must not terminate at another demotion marker",
			analysis.heuristicPolicyFacts().paths().stream()
				.flatMap(path -> path.nativeContinuations().stream())
				.noneMatch(fact -> markerProducers.contains(fact.consumer())));
		Assert.assertTrue("the nested demotion must remain in the earlier coordinator-local prefix",
			analysis.heuristicPolicyFacts().paths().stream().anyMatch(path -> path.localPrefix().stream()
				.anyMatch(key -> key != path.demotion().producer() && markerProducers.contains(key))));
		var result = new HeuristicPlacementAdapter().select(analysis, markers);

		Assert.assertEquals("the projected policy must have a candidate-reachable total assignment",
			result.selectorGraph().decisionNodes().size(), result.assignment().size());
		Assert.assertTrue("the selected policy must remain exact-candidate reachable",
			org.apache.sysds.hops.fedplanner.placement.CandidateSelections.canStillBeReachable(
				analysis, result.selectorGraph(), result.selectorGraph().relocationActions(),
				result.assignment()));

		var firstFeasible = new HeuristicPlacementAdapter(
			new org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector())
			.select(analysis, markers);
		Assert.assertEquals("single-pass Heuristic must retain the same complete legal domain",
			firstFeasible.selectorGraph().decisionNodes().size(), firstFeasible.assignment().size());
		Assert.assertTrue("single-pass Heuristic must not stop on a candidate-unreachable projection",
			org.apache.sysds.hops.fedplanner.placement.CandidateSelections.canStillBeReachable(
				analysis, firstFeasible.selectorGraph(), firstFeasible.selectorGraph().relocationActions(),
				firstFeasible.assignment()));
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

	private static String lmScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(500000,1050),list(500000,0),list(1000000,1050)));",
			"Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
				+ "ranges=list(list(0,0),list(500000,1),list(500000,0),list(1000000,1)));",
			"m=lm(X=X,y=Y,verbose=FALSE,tol=1e-9);", "print(sum(m));") + "\n";
	}

	private static String lmSingleWorkerScript() {
		return String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\"),"
				+ "ranges=list(list(0,0),list(1000000,1050)));",
			"Y=federated(addresses=list(\"localhost:1234/Y1\"),"
				+ "ranges=list(list(0,0),list(1000000,1)));",
			"m=lm(X=X,y=Y,verbose=FALSE,tol=1e-9);", "print(sum(m));") + "\n";
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
			"print(sum(y));") + "\n";
	}

	private static String functionScript() {
		return String.join("\n",
			"foo = function(matrix[double] X, matrix[double] A, matrix[double] v) return (matrix[double] y) {",
			"  z=X%*%v;", "  w=z+1;", "  y=A*w;", "}",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),ranges=list(list(0,0),list(2,1),list(2,0),list(4,1)));",
			"v=matrix(1,2,1);", "y=foo(X,A,v);", "print(sum(y));") + "\n";
	}

	private static DMLProgram compile(String script) throws Exception {
		return compile(script, true);
	}

	private static DMLProgram compile(String script, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return program;
	}
}
