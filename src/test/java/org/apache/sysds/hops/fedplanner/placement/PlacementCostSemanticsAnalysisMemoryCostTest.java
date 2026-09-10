/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.MemoTable;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for immutable common-analysis shapes in ordinary local HOP cost. */
public class PlacementCostSemanticsAnalysisMemoryCostTest {
	@Test
	public void exactAnalysisShapeReplacesOnlyUnknownHopMemory() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=50000,cols=4,seed=7); E=exp(X); print(sum(E));");
		CompiledHopKey key = soleUnary(analysis, OpOp1.EXP);
		Hop hop = analysis.hop(key).orElseThrow();
		Hop input = hop.getInput(0);
		double bytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, key);
		Assert.assertTrue(bytes > 1024 * 1024);

		input.setDim1(-1); input.setDim2(-1); input.setNnz(-1); input.computeMemEstimate(new MemoTable());
		hop.setDim1(-1); hop.setDim2(-1); hop.setNnz(-1); hop.computeMemEstimate(new MemoTable());
		double sentinelCost = FederatedCostModel.computeOpCostWithFallback(hop);
		double expected = FederatedCostModel.computeOpCostWithFallback(hop, 0.0, bytes, bytes);
		double actual = PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, key);

		Assert.assertEquals(expected, actual, 0.0);
		Assert.assertNotEquals("the exact 50000x4 shape must displace the unknown-memory fallback",
			sentinelCost, actual, 0.0);
	}

	@Test
	public void concreteSparseHopEstimateRemainsAuthoritative() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=50000,cols=40,sparsity=0.001,seed=7); E=abs(X); print(sum(E));");
		CompiledHopKey key = soleUnary(analysis, OpOp1.ABS);
		Hop hop = analysis.hop(key).orElseThrow();
		Hop input = hop.getInput(0);
		long nnz = 2000;
		double sparseBytes = OptimizerUtils.estimateSizeExactSparsity(
			50000, 40, nnz / 50000d / 40d, DataType.MATRIX);
		input.setDim1(-1); input.setDim2(-1); input.setNnz(nnz); input.computeMemEstimate(new MemoTable());
		hop.setDim1(-1); hop.setDim2(-1); hop.setNnz(nnz); hop.computeMemEstimate(new MemoTable());
		double expected = FederatedCostModel.computeOpCostWithFallback(hop, 0.0,
			sparseBytes, sparseBytes);
		Assert.assertEquals(expected, PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, key), 0.0);
	}

	@Test
	public void commonSparseAssignmentEstimateSurvivesUnknownHopShape() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=50000,cols=4,seed=7); P=X<=rowMins(X); print(sum(P));");
		CompiledHopKey key = soleBinary(analysis, OpOp2.LESSEQUAL);
		Hop hop = analysis.hop(key).orElseThrow();
		Hop source = hop.getInput(0);
		double sparseBytes = PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis)
			.memEstimate(key);
		Assert.assertTrue(sparseBytes > 0.0);
		double sourceBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis,
			analysis.compiledInputEdge(key, 0).orElseThrow().producer());
		double otherInputBytes = FederatedCostModel.getEffectiveOutputMemEstimate(hop.getInput(1));
		source.setDim1(-1); source.setDim2(-1); source.setNnz(-1);
		source.computeMemEstimate(new MemoTable());
		hop.setDim1(-1); hop.setDim2(-1); hop.setNnz(-1); hop.computeMemEstimate(new MemoTable());
		Assert.assertEquals(0.0,
			FederatedCostModel.getSemanticSparseAssignmentMemEstimate(hop), 0.0);
		double expected = FederatedCostModel.computeOpCostWithFallback(hop, 0.0,
			sourceBytes + otherInputBytes, sparseBytes);
		Assert.assertEquals(expected, PlacementCostSemantics.analysisAwareUnitLocalCost(
			analysis, PlacementCostSemantics.expectedSparseAssignmentEstimates(analysis), key), 0.0);
	}

	@Test
	public void unknownAnalysisShapeDoesNotInventMemoryBytes() throws Exception {
		Path input = Files.createTempFile("unknown-analysis-shape", ".csv");
		input.toFile().deleteOnExit();
		Files.writeString(input, "1,2\n");
		PlacementAnalysis analysis = analyze("X=read(\"" + input
			+ "\",format=\"csv\"); E=exp(X); print(sum(E));");
		CompiledHopKey key = soleUnary(analysis, OpOp1.EXP);
		Assert.assertTrue(Double.isNaN(PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, key)));
		Hop hop = analysis.hop(key).orElseThrow();
		Assert.assertEquals(FederatedCostModel.computeOpCostWithFallback(hop),
			PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, key), 0.0);
	}

	@Test
	public void repeatedLargeOccurrenceInputIsCountedOnce() throws Exception {
		PlacementAnalysis analysis = analyzeWithoutRewrite(
			"X=rand(rows=50000,cols=4,seed=7); Y=X+X; print(sum(Y));");
		CompiledHopKey key = soleBinary(analysis, OpOp2.PLUS);
		Hop hop = analysis.hop(key).orElseThrow();
		Hop input = hop.getInput(0);
		double inputBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis,
			analysis.compiledInputEdge(key, 0).orElseThrow().producer());
		double outputBytes = PlacementCostSemantics.analysisAwareDenseOutputBytes(analysis, key);
		Assert.assertTrue(inputBytes > 1024 * 1024);

		Assert.assertSame(input, hop.getInput(1));
		input.setDim1(-1); input.setDim2(-1); input.setNnz(-1); input.computeMemEstimate(new MemoTable());
		hop.setDim1(-1); hop.setDim2(-1); hop.setNnz(-1); hop.computeMemEstimate(new MemoTable());
		double expected = FederatedCostModel.computeOpCostWithFallback(hop, 0.0,
			inputBytes, outputBytes);
		Assert.assertEquals(expected,
			PlacementCostSemantics.analysisAwareUnitLocalCost(analysis, key), 0.0);
	}

	@Test
	public void explicitMemoryOverloadPreservesKnownKernelCost() throws Exception {
		PlacementAnalysis analysis = analyze("X=rand(rows=20,cols=10,seed=7); Z=rand(rows=10,cols=15,seed=8); Y=X%*%Z; print(sum(Y));");
		Hop hop = analysis.graph().nodes().stream().map(node -> analysis.hop(node.key()).orElse(null))
			.filter(candidate -> candidate instanceof AggBinaryOp)
			.findFirst().orElseThrow();
		double ordinary = FederatedCostModel.computeOpCostWithFallback(hop);
		Assert.assertEquals(ordinary, FederatedCostModel.computeOpCostWithFallback(hop, 0.0,
			FederatedCostModel.getEffectiveInputMemEstimate(hop),
			FederatedCostModel.getEffectiveOutputMemEstimate(hop)), 0.0);
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		return analyze(script, true);
	}

	private static PlacementAnalysis analyzeWithoutRewrite(String script) throws Exception {
		return analyze(script, false);
	}

	private static PlacementAnalysis analyze(String script, boolean rewrite) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		if(rewrite)
			translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static CompiledHopKey soleUnary(PlacementAnalysis analysis, OpOp1 op) {
		List<CompiledHopKey> keys = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(key -> analysis.hop(key).map(hop -> hop instanceof UnaryOp unary
				&& unary.getOp() == op).orElse(false)).toList();
		Assert.assertEquals(1, keys.size());
		return keys.get(0);
	}

	private static CompiledHopKey soleBinary(PlacementAnalysis analysis, OpOp2 op) {
		List<CompiledHopKey> keys = analysis.graph().nodes().stream().map(node -> node.key())
			.filter(key -> analysis.hop(key).map(hop -> hop instanceof BinaryOp binary
				&& binary.getOp() == op).orElse(false)).toList();
		Assert.assertEquals(1, keys.size());
		return keys.get(0);
	}
}
