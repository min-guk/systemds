/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.MemoTable;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.ExpectedSparseAssignmentEstimates;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Assert;
import org.junit.Test;

/** General expected-cardinality contracts for row-arg-min assignment payloads. */
@net.jcip.annotations.NotThreadSafe
public class PlacementCostSemanticsSparseAssignmentTest {
	@Test
	public void expectedCardinalityFollowsTransientNormalizationAndTranspose() throws Exception {
		PlacementAnalysis analysis = analyze(String.join("\n",
			"X=rand(rows=6,cols=3,seed=7);",
			"m=rowMins(X);",
			"P=X<=m;",
			"N=P/rowSums(P);",
			"T=t(N);",
			"write(T,\"/tmp/g014-sparse-assignment-t\",format=\"binary\");"));
		ExpectedSparseAssignmentEstimates estimates =
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		double expected = MatrixBlock.estimateSizeOnDisk(6, 3, 6);
		Assert.assertEquals(expected, estimates.serializedEstimate(
			soleBinary(analysis, OpOp2.LESSEQUAL)), 0.0);
		Assert.assertEquals(expected, estimates.serializedEstimate(
			soleBinary(analysis, OpOp2.DIV)), 0.0);
		Assert.assertEquals(MatrixBlock.estimateSizeOnDisk(3, 6, 6),
			estimates.serializedEstimate(soleTranspose(analysis)), 0.0);
	}

	@Test
	public void ambiguousBranchDefinitionsFailClosed() throws Exception {
		PlacementAnalysis analysis = analyze(String.join("\n",
			"X=rand(rows=6,cols=3,seed=7);",
			"if(sum(X)>0) { m=rowMins(X); } else { m=rowSums(X); }",
			"P=X<=m;",
			"print(sum(P));"));
		Assert.assertEquals(0.0, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis)
			.serializedEstimate(soleBinary(analysis, OpOp2.LESSEQUAL)), 0.0);
	}

	@Test
	public void unrelatedRowAggregateFailsClosed() throws Exception {
		PlacementAnalysis analysis = analyze(String.join("\n",
			"X=rand(rows=6,cols=3,seed=7);",
			"m=rowSums(X);",
			"P=X<=m;",
			"print(sum(P));"));
		Assert.assertEquals(0.0, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis)
			.serializedEstimate(soleBinary(analysis, OpOp2.LESSEQUAL)), 0.0);
	}

	@Test
	public void loopCarriedAssignmentDefinitionFailsClosedWithoutRecursion() throws Exception {
		PlacementAnalysis analysis = analyze(String.join("\n",
			"X=rand(rows=6,cols=3,seed=7);",
			"P=X<=rowMins(X);",
			"for(i in 1:2) { P=P/rowSums(P); }",
			"print(sum(P));"));
		List<CompiledHopKey> divisions = binaryKeys(analysis, OpOp2.DIV);
		Assert.assertFalse("Expected one loop-carried normalization occurrence", divisions.isEmpty());
		ExpectedSparseAssignmentEstimates estimates =
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		for(CompiledHopKey division : divisions)
			Assert.assertEquals("A loop-carried reaching-definition cycle must not be guessed",
				0.0, estimates.serializedEstimate(division), 0.0);
	}

	@Test
	public void concreteNnzOverridesExpectedOneMinimumPerRow() throws Exception {
		PlacementAnalysis analysis = analyze(String.join("\n",
			"X=rand(rows=6,cols=3,seed=7);",
			"P=X<=rowMins(X);",
			"print(sum(P));"));
		CompiledHopKey comparison = soleBinary(analysis, OpOp2.LESSEQUAL);
		Hop hop = analysis.hop(comparison).orElseThrow();
		double onePerRow = MatrixBlock.estimateSizeOnDisk(6, 3, 6);
		hop.setNnz(18);
		hop.computeMemEstimate(new MemoTable());
		ExpectedSparseAssignmentEstimates estimates =
			PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis);
		Assert.assertEquals("Known NNZ, including a tied-minimum observation, is authoritative",
			0.0, estimates.serializedEstimate(comparison), 0.0);
		Assert.assertTrue("The HOP-local fallback must not replace known dense NNZ with one-per-row",
			FederatedCostModel.getEffectiveUploadMemEstimate(hop) > onePerRow);
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static CompiledHopKey soleBinary(PlacementAnalysis analysis, OpOp2 operation) {
		List<CompiledHopKey> matches = binaryKeys(analysis, operation);
		Assert.assertEquals("Expected one " + operation + " occurrence: " + matches,
			1, matches.size());
		return matches.get(0);
	}

	private static List<CompiledHopKey> binaryKeys(PlacementAnalysis analysis, OpOp2 operation) {
		return analysis.graph().nodes().stream().map(node -> node.key())
			.filter(key -> analysis.hop(key).map(hop -> hop instanceof BinaryOp binary
				&& binary.getOp() == operation).orElse(false)).toList();
	}

	private static CompiledHopKey soleTranspose(PlacementAnalysis analysis) {
		List<CompiledHopKey> matches = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(key -> analysis.hop(key).map(hop -> hop instanceof ReorgOp reorg
				&& reorg.getOp() == ReOrgOp.TRANS).orElse(false)).toList();
		Assert.assertEquals("Expected one transpose occurrence: " + matches, 1, matches.size());
		return matches.get(0);
	}
}
