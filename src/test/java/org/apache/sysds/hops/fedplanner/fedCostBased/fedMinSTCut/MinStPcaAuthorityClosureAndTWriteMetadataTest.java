/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.BoundaryMode;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Production-shape PCA guards for authority closure and TWrite transfer pricing. */
public class MinStPcaAuthorityClosureAndTWriteMetadataTest {
	@Test
	public void pcaClosesUngroundedFedMembershipAndPricesTWriteAsMetadata() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildDetachedAnalysis(compilePca());
		List<CompiledHopKey> scope = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope);

		CompiledInputEdgeFact containsInput = uniqueEdge(analysis,
			"AggBinaryOp:ba(+*):XReduced", "ParameterizedBuiltinOp:CONTAINS:containsInf");
		DecisionFact containsDecision = decision(facts, containsInput.consumer());
		Assert.assertTrue("PCA_UNGROUNDED_CONTAINS_FED_MEMBERSHIP_MUST_BE_CLOSED",
			containsDecision.legalStatesInCanonicalOrder().stream()
				.noneMatch(state -> state.execType() == ExecType.FED));

		CompiledInputEdgeFact tWriteInput = uniqueEdge(analysis,
			"BinaryOp:b(/):X", "DataOp:TWrite X:X");
		AuxiliaryGroupFact group = uniqueGroup(facts, tWriteInput);
		Assert.assertEquals("PCA_TWRITE_UPLOAD_DIRECTION", Direction.UPLOAD, group.direction());
		Assert.assertEquals("PCA_TWRITE_MUST_NOT_SHARE_ANCHOR_TRANSFER_GROUP",
			BoundaryMode.TWRITE_METADATA, group.boundaryMode());
		Assert.assertTrue("PCA_TWRITE_PRICE_MUST_TARGET_PRODUCER_PLACEMENT",
			facts.directedEdgesInDerivationOrder().stream().anyMatch(edge ->
				edge.fromNodeId() == group.auxiliaryNodeId()
					&& edge.toNodeId() == group.producerPlacementNodeId()
					&& edge.contributionsInDerivationOrder().stream()
						.anyMatch(contribution -> contribution.kind() == ContributionKind.PRICE_UPLOAD_OR)));
		Assert.assertFalse("PCA_TWRITE_PRICE_MUST_NOT_BE_UNCONDITIONALLY_SUNK",
			facts.directedEdgesInDerivationOrder().stream().anyMatch(edge ->
				edge.fromNodeId() == group.auxiliaryNodeId()
					&& edge.toNodeId() == facts.sinkNodeId()
					&& edge.contributionsInDerivationOrder().stream()
						.anyMatch(contribution -> contribution.kind() == ContributionKind.PRICE_UPLOAD_OR)));

		MinStExactSelector.select(facts);
	}

	private static DMLProgram compilePca() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(500,100),list(500,0),list(1000,100)));",
			"[PC,V]=pca(X=X,K=5,scale=TRUE,center=TRUE);",
			"write(PC,\"out\",format=\"binary\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static CompiledInputEdgeFact uniqueEdge(PlacementAnalysis analysis,
		String producerSignature, String consumerSignature) {
		List<CompiledInputEdgeFact> matches = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer().normalizedSignature().contains(producerSignature)
				&& edge.consumer().normalizedSignature().contains(consumerSignature))
			.toList();
		Assert.assertEquals("PCA_EXPECTED_EDGE_MUST_BE_UNIQUE|producer=" + producerSignature
			+ "|consumer=" + consumerSignature, 1, matches.size());
		return matches.get(0);
	}

	private static DecisionFact decision(MinStExactCostFacts facts, CompiledHopKey key) {
		return facts.decisionFactsInScopeOrder().stream().filter(candidate -> candidate.key() == key)
			.findFirst().orElseThrow(() -> new AssertionError("PCA_DECISION_MISSING|" + key.normalizedSignature()));
	}

	private static AuxiliaryGroupFact uniqueGroup(MinStExactCostFacts facts,
		CompiledInputEdgeFact input) {
		List<AuxiliaryGroupFact> matches = facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == Direction.UPLOAD
				&& group.boundaryMode() == BoundaryMode.TWRITE_METADATA
				&& group.producerKey() == input.producer()
				&& group.endpointsInCanonicalOrder().stream().anyMatch(endpoint ->
					endpoint.consumerKey() == input.consumer()
						&& endpoint.inputPosition() == input.inputPosition()))
			.toList();
		Assert.assertEquals("PCA_TWRITE_GROUP_MUST_BE_UNIQUE", 1, matches.size());
		return matches.get(0);
	}

}
