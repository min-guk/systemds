/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristicSinglePass;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.rewrite.ProgramRewriter;
import org.apache.sysds.hops.rewrite.RewriteFederatedPlannerPhysicalNormalization;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression for AggLocal's local continuation after a legal aggregate-vector release. */
public class HeuristicLocalContinuationTest {
	private static final String SCRIPT =
		"X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));\n"
		+ "Y=federated(addresses=list(\"localhost:1234/Y\"),ranges=list(list(0,0),list(4,1)));\n"
		+ "v=matrix(1,rows=3,cols=1);z=X%*%v;r=z*Y;s=sum(r);print(s);\n";
	private static final String MATRIX_SIBLING_SCRIPT =
		"X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));\n"
		+ "M=federated(addresses=list(\"localhost:1234/M\"),ranges=list(list(0,0),list(4,3)));\n"
		+ "v=matrix(1,rows=3,cols=1);z=X%*%v;r=z*M;print(sum(r));\n";
	private static final String REUSED_VECTOR_SCRIPT =
		"X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));\n"
		+ "Y=federated(addresses=list(\"localhost:1234/Y\"),ranges=list(list(0,0),list(4,1)));\n"
		+ "v=matrix(1,rows=3,cols=1);z=X%*%v;r=z*Y;t=z+Y;print(sum(r)+sum(t));\n";
	private static final String LM_CORE_SCRIPT = String.join("\n",
		"X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));",
		"y=federated(addresses=list(\"localhost:1234/y\"),ranges=list(list(0,0),list(4,1)));",
		"p=matrix(1,rows=3,cols=1);",
		"r=X%*%p-y;",
		"g=t(r)%*%X;",
		"print(sum(g));") + "\n";
	private static final String LM_FUNCTION_SCRIPT = String.join("\n",
		"X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));",
		"y=federated(addresses=list(\"localhost:1234/y\"),ranges=list(list(0,0),list(4,1)));",
		"m_evaluate=function(Matrix[Double] X,Matrix[Double] y,Matrix[Double] p,Boolean do_clip)",
		"  return(Matrix[Double] grad,Double loss) {",
		"  pred=X%*%p; err=pred-y;",
		"  if(do_clip) { z=(err<0)*0+(err>=0)*err; } else { z=err; }",
		"  grad=t(X)%*%z;",
		"  loss=sum(z*z);",
		"}",
		"p=matrix(0,rows=3,cols=1);",
		"for(t in 1:2) {",
		"  [grad,loss]=m_evaluate(X,y,p,t%%2==1);",
		"  p=p-grad/4;",
		"}",
		"print(loss);") + "\n";
	private static final String MIXED_BRANCH_SCRIPT = LM_FUNCTION_SCRIPT.replace(
		"if(do_clip) { z=(err<0)*0+(err>=0)*err; } else { z=err; }",
		"if(do_clip) { z=(err<0)*0+(err>=0)*err; } else { z=y; }");

	@Test
	public void publicFederatedVectorIsCollectedForLocalVectorContinuation() throws Exception {
		PlacementAnalysis analysis = analyze(Privacy.PUBLIC);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		Node z = uniqueNode(analysis, "z", "ba(+*)");
		Node y = uniqueFederatedSource(analysis, "Y");
		Node r = uniqueNode(analysis, "r", "b(*)");
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		Node s = uniqueAggregateConsumer(analysis, r);

		Assert.assertEquals("The protected aggregate vector must be released locally",
			FederatedOutput.LOUT, result.assignment().get(z.key()).output());
		Assert.assertEquals("AggLocal must continue the vector-only expression at the coordinator",
			ExecType.CP, result.assignment().get(r.key()).execType());
		Assert.assertEquals(FederatedOutput.LOUT, result.assignment().get(r.key()).output());
		Assert.assertEquals("The terminal scalar reduction remains local",
			ExecType.CP, result.assignment().get(s.key()).execType());
		Assert.assertEquals("A public worker vector need not be uploaded merely to preserve FED execution",
			FederatedOutput.FOUT, result.assignment().get(y.key()).output());

		Assert.assertTrue("The public Y vector must have one certified local view for r",
			result.selectedLocalMaterializations().stream().anyMatch(action ->
				action.sourceValueVersion().equals(y.valueVersion())
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumerOccurrence() == r.key())));
		Assert.assertTrue("The already-local z vector must not be re-uploaded for r: "
			+ result.selectedRelocations(), result.selectedRelocations().stream().noneMatch(action ->
				action.sourceValueVersion().equals(z.valueVersion())
					&& action.compatibleConsumers().contains(r.key())));
		assertCanonicalCandidateLegality(analysis, result);
	}

	@Test
	public void protectedFederatedVectorDoesNotForceIllegalLocalContinuation() throws Exception {
		PlacementAnalysis analysis = analyze(Privacy.PRIVATE_AGGREGATE);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		Node y = uniqueFederatedSource(analysis, "Y");
		Node r = uniqueNode(analysis, "r", "b(*)");

		Assert.assertFalse("A raw PRIVATE_AGGREGATE Y vector cannot be collected for CP multiplication",
			result.assignment().get(r.key()).execType() == ExecType.CP
				&& result.assignment().get(r.key()).output() == FederatedOutput.LOUT);
		Assert.assertTrue("The protected Y vector must not acquire a local materialization",
			result.selectedLocalMaterializations().stream().noneMatch(action ->
				action.sourceValueVersion().equals(y.valueVersion())));
		assertCanonicalCandidateLegality(analysis, result);
	}

	@Test
	public void indexedProtectedVectorDomainMatchesCanonicalDomain() throws Exception {
		PlacementAnalysis analysis = analyze(Privacy.PRIVATE_AGGREGATE);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		var graph = result.selectorGraph();
		var index = CandidateSelections.partialReachabilityIndex(analysis, graph, graph.relocationActions());
		var indexed = index.materializationObjectiveVariantsForCompleteAssignment(result.assignment());
		var canonical = CandidateSelections.materializationObjectiveVariantsForCompleteAssignment(
			analysis, graph, graph.relocationActions(), result.assignment());
		Assert.assertEquals("Indexed and public domains must cover the same exact consumers",
			canonical.keySet(), indexed.keySet());
		for(var entry : canonical.entrySet())
			Assert.assertEquals("Indexed and public rows differ for "
				+ analysis.hop(entry.getKey()).map(hop -> hop.getOpString() + ":" + hop.getName()).orElse("boundary"),
				entry.getValue().stream().mapToInt(analysis::candidateReceiptRank).sorted().boxed().toList(),
				indexed.get(entry.getKey()).stream().mapToInt(analysis::candidateReceiptRank).sorted().boxed().toList());
		for(var rows : canonical.values())
			for(var receipt : rows)
				for(var binding : receipt.supportClause().inputBindings())
					if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION)
						Assert.assertTrue("A surviving row must retain its exact relocation in the projected universe",
							graph.relocationActions().stream().anyMatch(action ->
								action.key().equals(binding.relocationAction())));
	}

	@Test
	public void publicMatrixSiblingRemainsAFederatedReentryFrontier() throws Exception {
		PlacementAnalysis analysis = analyze(MATRIX_SIBLING_SCRIPT, "M", Privacy.PUBLIC);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		Node matrix = uniqueFederatedSource(analysis, "M");
		Node r = uniqueNode(analysis, "r", "b(*)");

		Assert.assertEquals("A non-vector matrix sibling ends the local vector prefix",
			ExecType.FED, result.assignment().get(r.key()).execType());
		Assert.assertTrue("AggLocal must not collect a large public matrix merely to extend the prefix",
			result.selectedLocalMaterializations().stream().noneMatch(action ->
				action.sourceValueVersion().equals(matrix.valueVersion())));
		assertCanonicalCandidateLegality(analysis, result);
	}

	@Test
	public void lmGradientUsesNativeFedLoutInsteadOfCollectingX() throws Exception {
		PlacementAnalysis analysis = analyzeRewritten(LM_CORE_SCRIPT, "y", Privacy.PUBLIC);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		Node matrix = uniqueFederatedSource(analysis, "X");
		List<Node> gradients = nodesAtLine(analysis, 5, "ba(+*)");

		Assert.assertEquals("fixture requires one gradient matrix multiplication", 1, gradients.size());
		Node gradient = gradients.get(0);
		Assert.assertTrue("AggLocal must record the exact mixed local/FED continuation",
			analysis.heuristicPolicyFacts().paths().stream().flatMap(path -> path.nativeContinuations().stream())
				.anyMatch(fact -> fact.consumer() == gradient.key()
					&& fact.consumerState().execType() == ExecType.FED
					&& fact.consumerState().output() == FederatedOutput.LOUT));
		Assert.assertEquals("The gradient must execute at workers while returning its vector locally",
			ExecType.FED, result.assignment().get(gradient.key()).execType());
		Assert.assertEquals(FederatedOutput.LOUT, result.assignment().get(gradient.key()).output());
		Assert.assertTrue("AggLocal must not collect X merely to continue the local vector prefix",
			result.selectedLocalMaterializations().stream().noneMatch(action ->
				action.sourceValueVersion().equals(matrix.valueVersion())));
		assertCanonicalCandidateLegality(analysis, result);
	}

	@Test
	public void twoLocalConsumersReuseOnePublicVectorMaterialization() throws Exception {
		PlacementAnalysis analysis = analyze(REUSED_VECTOR_SCRIPT, "Y", Privacy.PUBLIC);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		Node y = uniqueFederatedSource(analysis, "Y");
		Node z = uniqueNode(analysis, "z", "ba(+*)");
		Node r = uniqueNode(analysis, "r", "b(*)");
		Node t = uniqueNode(analysis, "t", "b(+)");

		Assert.assertEquals(ExecType.CP, result.assignment().get(r.key()).execType());
		Assert.assertEquals(ExecType.CP, result.assignment().get(t.key()).execType());
		var yCollections = result.selectedLocalMaterializations().stream()
			.filter(action -> action.sourceValueVersion().equals(y.valueVersion())).toList();
		Assert.assertEquals("Both consumers share one scope-compatible Y collection", 1, yCollections.size());
		Assert.assertEquals(Set.of(r.key(), t.key()), yCollections.get(0).obligations().stream()
			.map(obligation -> obligation.consumerOccurrence()).collect(Collectors.toSet()));
		Assert.assertTrue("Neither local consumer may trigger a z re-upload",
			result.selectedRelocations().stream().noneMatch(action ->
				action.sourceValueVersion().equals(z.valueVersion())
					&& (action.compatibleConsumers().contains(r.key())
						|| action.compatibleConsumers().contains(t.key()))));
		assertCanonicalCandidateLegality(analysis, result);
	}

	@Test
	public void functionLoopKeepsLocalVectorTransposeAndLossInCP() throws Exception {
		PlacementAnalysis analysis = analyzeRewritten(LM_FUNCTION_SCRIPT, "y", Privacy.PUBLIC);
		var markers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.valueVersion()).collect(Collectors.toSet());
		var result = new FederatedPlannerFedHeuristicSinglePass().select(analysis, markers);
		List<Node> gradientTranspose = nodesAtLine(analysis, 7, "r(r')");
		List<Node> lossPath = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> hop.getBeginLine() == 8 && node.emittedWork()).orElse(false)).toList();
		List<Node> zJoins = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NeutralPlacementGraph.NodeKind.BRANCH_JOIN
				&& "z".equals(node.valueVersion().lexicalVariable())).toList();

		Assert.assertFalse("fixture must expose the transpose introduced for the gradient: "
			+ analysis.graph().nodes().stream().map(node -> analysis.hop(node.key())
				.map(hop -> hop.getBeginLine() + ":" + hop.getOpString() + ":" + hop.getName())
				.orElse("none")).toList(), gradientTranspose.isEmpty());
		Assert.assertFalse("fixture must expose the loss expression", lossPath.isEmpty());
		Assert.assertEquals("fixture must classify the post-branch z read as one branch join", 1, zJoins.size());
		Set<CompiledHopKey> forwardedDefinitions =
			analysis.heuristicPolicyFacts().paths().stream().flatMap(path -> path.edges().stream())
				.filter(edge -> edge.kind() == PlacementAnalysis.HeuristicPathEdgeKind.CFG_TRANSIENT_FORWARD
					&& edge.consumer() == zJoins.get(0).key())
				.map(PlacementAnalysis.HeuristicPathEdgeFact::producer).collect(Collectors.toSet());
		Assert.assertEquals("both exact reaching z definitions must forward into the local phi",
			2, forwardedDefinitions.size());
		for(Node node : gradientTranspose) {
			Assert.assertEquals("AggLocal transposes an already-local z vector in CP",
				ExecType.CP, result.assignment().get(node.key()).execType());
			Assert.assertEquals(FederatedOutput.LOUT, result.assignment().get(node.key()).output());
		}
		for(Node node : lossPath) {
			Assert.assertEquals("AggLocal evaluates the local-vector loss path in CP: "
				+ analysis.hop(node.key()).orElseThrow().getOpString(),
				ExecType.CP, result.assignment().get(node.key()).execType());
			Assert.assertEquals(FederatedOutput.LOUT, result.assignment().get(node.key()).output());
		}
		assertCanonicalCandidateLegality(analysis, result);
	}

	@Test
	public void mixedFederatedBranchDoesNotAuthorizeLocalPhi() throws Exception {
		PlacementAnalysis analysis = analyzeRewritten(MIXED_BRANCH_SCRIPT, "y", Privacy.PUBLIC);
		List<Node> zJoins = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NeutralPlacementGraph.NodeKind.BRANCH_JOIN
				&& "z".equals(node.valueVersion().lexicalVariable())).toList();
		List<Node> zDefinitions = analysis.graph().nodes().stream()
			.filter(node -> node.kind() == NeutralPlacementGraph.NodeKind.TRANSIENT_WRITE
				&& "z".equals(node.valueVersion().lexicalVariable())).toList();
		long localDefinitions = zDefinitions.stream().filter(definition ->
			analysis.heuristicPolicyFacts().paths().stream()
				.anyMatch(path -> path.localPrefix().contains(definition.key()))).count();

		Assert.assertEquals("fixture must expose one branch-join value for z", 1, zJoins.size());
		Assert.assertEquals("fixture must expose both branch definitions", 2, zDefinitions.size());
		Assert.assertEquals("fixture must have exactly one proven-local reaching definition",
			1, localDefinitions);
		Assert.assertFalse("one local predecessor cannot authorize a mixed-placement phi as local",
			analysis.heuristicPolicyFacts().paths().stream()
				.anyMatch(path -> path.localPrefix().contains(zJoins.get(0).key())));
	}

	private static void assertCanonicalCandidateLegality(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter.Result result) {
		Assert.assertTrue(CandidateSelections.canStillBeReachable(analysis, result.selectorGraph(),
			result.selectorGraph().relocationActions(), result.assignment()));
		var canonical = CandidateSelections.selectMaterializationMaximal(analysis,
			result.selectorGraph(), result.selectorGraph().relocationActions(), result.assignment());
		Assert.assertEquals(canonical.candidates().stream().map(candidate -> candidate.normalizedSignature())
			.collect(Collectors.toCollection(java.util.TreeSet::new)),
			result.selectedCandidateSelections().stream().map(candidate -> candidate.normalizedSignature())
				.collect(Collectors.toCollection(java.util.TreeSet::new)));
	}

	private static PlacementAnalysis analyze(Privacy yPrivacy) throws Exception {
		return analyze(SCRIPT, "Y", yPrivacy);
	}

	private static PlacementAnalysis analyze(String script, String siblingName,
		Privacy siblingPrivacy) throws Exception {
		return analyze(script, siblingName, siblingPrivacy, false);
	}

	private static PlacementAnalysis analyzeRewritten(String script, String siblingName,
		Privacy siblingPrivacy) throws Exception {
		return analyze(script, siblingName, siblingPrivacy, true);
	}

	private static PlacementAnalysis analyze(String script, String siblingName,
		Privacy siblingPrivacy, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite) {
			translator.rewriteHopsDAG(program);
			new ProgramRewriter(new RewriteFederatedPlannerPhysicalNormalization())
				.rewriteProgramHopDAGs(program, false);
		}
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		for(DataOp source : federatedSources(program))
			if("X".equals(source.getName()))
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(source, Privacy.PRIVATE_AGGREGATE);
			else if(siblingName.equals(source.getName()))
				FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(source, siblingPrivacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static List<DataOp> federatedSources(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		program.getStatementBlocks().stream().filter(block -> block.getHops() != null)
			.forEach(block -> pending.addAll(block.getHops()));
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<DataOp> sources = new ArrayList<>();
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
				sources.add(data);
			pending.addAll(hop.getInput());
		}
		return sources;
	}

	private static Node uniqueFederatedSource(PlacementAnalysis analysis, String name) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED
				&& name.equals(data.getName())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one federated source " + name, 1, matches.size());
		return matches.get(0);
	}

	private static Node uniqueNode(PlacementAnalysis analysis, String name, String opcode) {
		List<Node> matches = analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> name.equals(hop.getName()) && opcode.equals(hop.getOpString())).orElse(false)).toList();
		Assert.assertEquals("fixture requires one " + name + '/' + opcode, 1, matches.size());
		return matches.get(0);
	}

	private static Node uniqueAggregateConsumer(PlacementAnalysis analysis, Node producer) {
		List<Node> matches = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == producer.key())
			.map(edge -> analysis.graph().node(edge.consumer()).orElseThrow())
			.filter(node -> analysis.hop(node.key()).orElseThrow() instanceof AggUnaryOp)
			.toList();
		Assert.assertEquals("fixture requires one scalar aggregate consumer", 1, matches.size());
		return matches.get(0);
	}

	private static List<Node> nodesAtLine(PlacementAnalysis analysis, int line, String opcode) {
		return analysis.graph().nodes().stream().filter(node -> analysis.hop(node.key())
			.map(hop -> hop.getBeginLine() == line && opcode.equals(hop.getOpString())).orElse(false)).toList();
	}

}
