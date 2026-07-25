/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipInputAuthorityFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** G007 guard for exact MinST membership authorities whose producer appears later in canonical scope. */
public class G007MinStForwardMembershipAuthorityRedTest {
	@Test
	public void logRegForwardProducerInputAuthorityIsMaterializedByExactIdentity() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compileLogRegWithForwardLabelRelabel());
		List<CompiledHopKey> scope = scope(analysis);
		ForwardEdge forward = requireForwardBinaryRelabelEdge(analysis, scope);

		Assert.assertTrue("G007_FIXTURE_MUST_LOCK_FORWARD_CANONICAL_CONSUMER_BEFORE_PRODUCER",
			forward.consumerDecisionIndex() < forward.producerDecisionIndex());
		Assert.assertTrue("G007_FIXTURE_MUST_LOCK_PRODUCER_AFTER_CONSUMER_BY_SCOPE_INDEX",
			scope.indexOf(forward.edge().consumer()) < scope.indexOf(forward.edge().producer()));

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);
		Map<CompiledHopKey,DecisionFact> decisions = facts.decisionFactsInScopeOrder().stream()
			.collect(Collectors.toMap(DecisionFact::key, Function.identity(), (a, b) -> a,
				java.util.IdentityHashMap::new));
		Assert.assertEquals("G007_DECISION_IDS_MUST_STAY_CANONICAL_CONSUMER_COMPUTE_ID",
			computeNodeId(scope.indexOf(forward.edge().consumer())),
			decisions.get(forward.edge().consumer()).computeNodeId());
		Assert.assertEquals("G007_DECISION_IDS_MUST_STAY_CANONICAL_PRODUCER_COMPUTE_ID",
			computeNodeId(scope.indexOf(forward.edge().producer())),
			decisions.get(forward.edge().producer()).computeNodeId());

		MembershipRepresentative consumer = requireFedFoutRepresentative(facts, forward.edge().consumer());
		Assert.assertEquals("G007_CONSUMER_MUST_RETAIN_CAPTURED_RULE_AUTHORITY",
			MembershipAuthorityKind.CAPTURED_RULE, consumer.authorityKind());
		Assert.assertTrue("G007_CONSUMER_RULE_MUST_REQUIRE_PRESENT_PRODUCER_INPUT",
			consumer.orderedInputs().get(forward.edge().inputPosition()).present());
		FType expectedProducerType = consumer.orderedInputs().get(forward.edge().inputPosition()).fType();
		Assert.assertEquals("G007_FORWARD_INPUT_FIXTURE_EXPECTS_FULL_AUTHORITY", FType.FULL, expectedProducerType);

		MembershipInputAuthorityFact authority = consumer.inputAuthorityFacts().stream()
			.filter(fact -> fact.inputEdge() == forward.edge()).findFirst()
			.orElseThrow(() -> new AssertionError("G007_FORWARD_INPUT_AUTHORITY_FACT_MISSING"));
		MembershipRepresentative canonicalProducer = requireFedFoutRepresentative(facts, forward.edge().producer());
		Assert.assertSame("G007_INPUT_AUTHORITY_MUST_BIND_EXACT_CANONICAL_EDGE", forward.edge(), authority.inputEdge());
		Assert.assertSame("G007_INPUT_AUTHORITY_MUST_BIND_EXACT_PRODUCER_REPRESENTATIVE",
			canonicalProducer, authority.producerRepresentative());
		Assert.assertSame("G007_INPUT_AUTHORITY_PRODUCER_KEY_MUST_RETAIN_IDENTITY",
			forward.edge().producer(), authority.producerRepresentative().decisionKey());
		Assert.assertEquals("G007_INPUT_AUTHORITY_FTYPE_MUST_MATCH_PRESENT_RULE_INPUT",
			expectedProducerType, authority.producerRepresentative().state().fType());
	}

	private static ForwardEdge requireForwardBinaryRelabelEdge(PlacementAnalysis analysis,
		List<CompiledHopKey> scope) {
		Map<CompiledHopKey,Integer> decisionIndex = new java.util.IdentityHashMap<>();
		int index = 0;
		for(CompiledHopKey key : scope)
			if(analysis.graph().node(key).orElseThrow().emittedWork())
				decisionIndex.put(key, index++);
		List<ForwardEdge> matches = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer().normalizedSignature().contains("BinaryOp:b(+):Y")
				&& edge.producer().normalizedSignature().contains("BinaryOp:b(<):compiler-temp"))
			.filter(edge -> decisionIndex.containsKey(edge.consumer()) && decisionIndex.containsKey(edge.producer()))
			.map(edge -> new ForwardEdge(edge, decisionIndex.get(edge.consumer()), decisionIndex.get(edge.producer())))
			.filter(edge -> edge.consumerDecisionIndex() < edge.producerDecisionIndex())
			.toList();
		Assert.assertEquals("G007_LOGREG_Y_RELABEL_FORWARD_EDGE_MUST_BE_UNIQUE", 1, matches.size());
		return matches.get(0);
	}

	private static MembershipRepresentative requireFedFoutRepresentative(MinStExactCostFacts facts,
		CompiledHopKey key) {
		List<MembershipRepresentative> representatives = facts.membershipRepresentativesInCanonicalOrder()
			.stream()
			.filter(representative -> representative.decisionKey() == key
				&& representative.execType() == ExecType.FED
				&& representative.output() == FederatedOutput.FOUT)
			.toList();
		Assert.assertEquals("G007_FED_FOUT_REPRESENTATIVE_MUST_BE_UNIQUE|" + key.normalizedSignature(),
			1, representatives.size());
		return representatives.get(0);
	}

	private static long computeNodeId(int scopeIndex) {
		return 2L * scopeIndex;
	}

	private static DMLProgram compileLogRegWithForwardLabelRelabel() throws Exception {
		String script = String.join("\n",
			"N=50000;",
			"D=2100;",
			"X=federated(addresses=list(\"localhost:1234/X\"),"
				+ "ranges=list(list(0,0),list(50000,2100)));",
			"Y=federated(addresses=list(\"localhost:1234/Y\"),"
				+ "ranges=list(list(0,0),list(50000,1)));",
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

	private record ForwardEdge(CompiledInputEdgeFact edge, int consumerDecisionIndex, int producerDecisionIndex) { }
}
