/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.memo;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpDnn;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.DnnOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.junit.Assert;
import org.junit.Test;

public class MemoCostModelTest {
	private static final int WORKERS = 3;

	@Test
	public void cpAndPartitionedFedReusePrimitiveCosts() {
		UnaryOp hop = unary(matrix("X", 8, 4));
		double base = FederatedCostModel.computeOpCostWithFallback(hop);
		RuleMemo.Rule cp = rule(ExecType.CP, List.of(MemoPartition.LOCAL), MemoPartition.LOCAL);
		RuleMemo.Rule row = rule(ExecType.FED, List.of(MemoPartition.ROW), MemoPartition.ROW);

		Assert.assertEquals(2 * base, MemoCostModel.nativeCost(hop, cp, WORKERS, 2), 1e-12);
		double expectedFed = base / WORKERS
			+ FederatedCostModel.computeFederatedInstructionNetworkCost(hop, 1);
		Assert.assertEquals(2 * expectedFed,
			MemoCostModel.nativeCost(hop, row, WORKERS, 2), 1e-12);
	}

	@Test
	public void replicatedFedComputeIsNotDividedByWorkers() {
		UnaryOp hop = unary(matrix("X", 8, 4));
		double base = FederatedCostModel.computeOpCostWithFallback(hop);
		RuleMemo.Rule rep = rule(ExecType.FED, List.of(MemoPartition.REP), MemoPartition.REP);
		double expected = base + FederatedCostModel.computeFederatedInstructionNetworkCost(hop, 1);

		Assert.assertEquals(expected, MemoCostModel.nativeCost(hop, rep, WORKERS, 1), 1e-12);
	}

	@Test
	public void localOperandBroadcastIsOwnedOnceByNativeInstruction() {
		Hop left = matrix("L", 8, 4);
		Hop right = matrix("R", 8, 4);
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, left, right);
		plus.setDim1(8);
		plus.setDim2(4);
		RuleMemo.Rule rule = rule(ExecType.FED,
			List.of(MemoPartition.ROW, MemoPartition.LOCAL), MemoPartition.ROW);
		double base = FederatedCostModel.computeOpCostWithFallback(plus);
		double bytes = FederatedCostModel.getEffectiveUploadMemEstimate(right);
		double expected = base / WORKERS
			+ FederatedCostModel.computeFederatedInstructionNetworkCost(plus, 1)
			+ FederatedCostModel.computeInBandUploadPayloadCost(bytes, FType.BROADCAST, WORKERS);

		Assert.assertEquals(expected, MemoCostModel.nativeCost(plus, rule, WORKERS, 1), 1e-12);
	}

	@Test
	public void crossAxisBinaryUsesTheRuntimeRightPartitionedExecutionAnchor() {
		BinaryOp divide = new BinaryOp("divide", DataType.MATRIX, ValueType.FP64,
			OpOp2.DIV, matrix("col", 8, 2), matrix("row", 8, 2));
		divide.setDim1(8);
		divide.setDim2(2);
		RuleMemo.Rule rule = rule(ExecType.FED,
			List.of(MemoPartition.COL,MemoPartition.ROW),MemoPartition.ROW);

		double cost = MemoCostModel.nativeCost(divide,rule,WORKERS,1);
		Assert.assertTrue(Double.isFinite(cost));
		Assert.assertTrue(cost > 0);
	}

	@Test
	public void fedLocalResultGetIsIncludedExactlyOnce() {
		UnaryOp hop = unary(matrix("X", 8, 4));
		RuleMemo.Rule rule = rule(ExecType.FED, List.of(MemoPartition.ROW), MemoPartition.LOCAL);
		double base = FederatedCostModel.computeOpCostWithFallback(hop);
		double bytes = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		double expected = base / WORKERS
			+ FederatedCostModel.computeFederatedInstructionNetworkCost(hop, 1)
			+ FederatedCostModel.computeNativeFederatedLoutResultCost(
				hop, List.of(FType.ROW), FType.ROW, bytes, WORKERS);

		Assert.assertEquals(expected, MemoCostModel.nativeCost(hop, rule, WORKERS, 1), 1e-12);
	}

	@Test
	public void aggregateLocalResultReusesCoordinatorPhasePrimitive() {
		Hop input = matrix("X", 8, 4);
		AggUnaryOp aggregate = new AggUnaryOp("rowsums", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Row, input);
		aggregate.setDim1(8);
		aggregate.setDim2(1);
		RuleMemo.Rule rule = rule(ExecType.FED, List.of(MemoPartition.ROW), MemoPartition.LOCAL);
		double base = FederatedCostModel.computeOpCostWithFallback(aggregate);
		double outputBytes = FederatedCostModel.getEffectiveOutputMemEstimate(aggregate);
		FederatedCostModel.MixedFedLocalCost mixed = FederatedCostModel.computeMixedFedLocalCost(
			aggregate, aggregate.getInput(), List.of(FType.ROW), FType.ROW,
			base, outputBytes, WORKERS);
		double expected = base / WORKERS
			+ FederatedCostModel.computeFederatedInstructionNetworkCost(aggregate, 1)
			+ mixed.getInputPreparationCost() + mixed.getCoordinatorPhaseCost();

		Assert.assertTrue(mixed.hasCoordinatorPhase());
		Assert.assertEquals(expected, MemoCostModel.nativeCost(aggregate, rule, WORKERS, 1), 1e-12);
	}

	@Test
	public void federatedVarianceIncludesAuxiliaryRuntimeStagesOnce() {
		Hop input = matrix("X", 8, 4);
		AggUnaryOp variance = new AggUnaryOp("rowvars", DataType.MATRIX, ValueType.FP64,
			AggOp.VAR, Direction.Row, input);
		variance.setDim1(8);
		variance.setDim2(1);
		RuleMemo.Rule rule = rule(ExecType.FED, List.of(MemoPartition.ROW), MemoPartition.ROW);
		double base = FederatedCostModel.computeOpCostWithFallback(variance);
		double outputBytes = FederatedCostModel.getEffectiveOutputMemEstimate(variance);
		FederatedCostModel.MixedFedLocalCost mixed = FederatedCostModel.computeMixedFedLocalCost(
			variance, variance.getInput(), List.of(FType.ROW), FType.ROW,
			base, outputBytes, WORKERS);
		double ordinary = base / WORKERS
			+ FederatedCostModel.computeFederatedInstructionNetworkCost(variance, 1);

		Assert.assertTrue("variance has an auxiliary request/result stage", mixed.hasInputPreparation());
		Assert.assertEquals(ordinary + mixed.getInputPreparationCost(),
			MemoCostModel.nativeCost(variance, rule, WORKERS, 1), 1e-12);
	}

	@Test
	public void alignedMatrixMultiplyUsesItsProvenLeftExecutionAnchor() {
		AggBinaryOp mm = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, matrix("L", 8, 4), matrix("R", 4, 3));
		mm.setDim1(8);
		mm.setDim2(3);
		RuleMemo.Rule rule = rule(ExecType.FED,
			List.of(MemoPartition.COL, MemoPartition.ROW), MemoPartition.LOCAL);

		double cost = MemoCostModel.nativeCost(mm, rule, WORKERS, 1);
		Assert.assertTrue(Double.isFinite(cost));
		Assert.assertTrue(cost > 0);
	}

	@Test
	public void elementwiseTernaryUsesTheRuntimeFirstFederatedExecutionAnchor() {
		TernaryOp ternary = new TernaryOp("plusMult", DataType.MATRIX, ValueType.FP64,
			OpOp3.PLUS_MULT, matrix("row", 8, 1), matrix("local", 8, 1), matrix("col", 8, 1));
		ternary.setDim1(8);
		ternary.setDim2(1);
		RuleMemo.Rule rule = rule(ExecType.FED,
			List.of(MemoPartition.ROW, MemoPartition.LOCAL, MemoPartition.COL), MemoPartition.LOCAL);

		double cost = MemoCostModel.nativeCost(ternary, rule, WORKERS, 1);
		Assert.assertTrue(Double.isFinite(cost));
		Assert.assertTrue(cost > 0);
	}

	@Test
	public void crossAxisForcedLocalAppendPricesBothRuntimeCollections() {
		BinaryOp append = new BinaryOp("cbind", DataType.MATRIX, ValueType.FP64,
			OpOp2.CBIND, matrix("rows", 8, 1), matrix("cols", 8, 1));
		append.setDim1(8);
		append.setDim2(2);
		RuleMemo.Rule rule = rule(ExecType.FED,
			List.of(MemoPartition.ROW, MemoPartition.COL), MemoPartition.LOCAL);
		double base = FederatedCostModel.computeOpCostWithFallback(append);
		double expected = base
			+ FederatedCostModel.computeReusableMaterializationDownloadCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(append.getInput(0)), FType.ROW, WORKERS)
			+ FederatedCostModel.computeReusableMaterializationDownloadCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(append.getInput(1)), FType.COL, WORKERS);

		Assert.assertEquals(expected, MemoCostModel.nativeCost(append, rule, WORKERS, 1), 1e-12);
	}

	@Test
	public void explicitUploadAndCollectUseSharedMovementPrimitives() {
		Hop source = unary(matrix("X", 8, 4));
		double bytes = FederatedCostModel.getEffectiveUploadMemEstimate(source);
		double upload = FederatedCostModel.computeUploadNetworkCost(bytes, FType.ROW, WORKERS);
		double collect = FederatedCostModel.computeReusableMaterializationDownloadCost(
			bytes, FType.ROW, WORKERS);

		Assert.assertEquals(2 * upload, MemoCostModel.movementCost(source,
			MemoPartition.LOCAL, MemoPartition.ROW, WORKERS, 2), 1e-12);
		Assert.assertEquals(2 * collect, MemoCostModel.movementCost(source,
			MemoPartition.ROW, MemoPartition.LOCAL, WORKERS, 2), 1e-12);
		Assert.assertEquals(0, MemoCostModel.movementCost(source,
			MemoPartition.ROW, MemoPartition.ROW, WORKERS, 2), 0);
	}

	@Test
	public void replicatedCollectReflectsAllWorkerResponses() {
		Hop source = unary(matrix("X", 8, 4));
		double row = MemoCostModel.movementCost(source,
			MemoPartition.ROW, MemoPartition.LOCAL, WORKERS, 1);
		double rep = MemoCostModel.movementCost(source,
			MemoPartition.REP, MemoPartition.LOCAL, WORKERS, 1);

		Assert.assertTrue("REP collect receives a full response from every worker", rep > row);
	}

	@Test
	public void carriersHaveNoKernelCost() {
		DataOp source = matrix("X", 8, 4);
		RuleMemo.Rule carrier = rule(ExecType.CP, List.of(), MemoPartition.LOCAL);
		Assert.assertEquals(0, MemoCostModel.nativeCost(source, carrier, WORKERS, 7), 0);
	}

	@Test
	public void unresolvedInputsFailWithTypedDiagnostics() {
		Hop unknown = unary(matrix("X", -1, 4));
		MemoCostModel.CostException size = Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.movementCost(unknown, MemoPartition.LOCAL,
				MemoPartition.ROW, WORKERS, 1));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.UNKNOWN_SIZE, size.code());

		MemoCostModel.CostException frequency = Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.nativeCost(unary(matrix("Y", 8, 4)),
				rule(ExecType.CP, List.of(MemoPartition.LOCAL), MemoPartition.LOCAL),
				WORKERS, Double.NaN));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.INVALID_FREQUENCY, frequency.code());
	}

	@Test
	public void biasKernelsUseTheirExistingCpCostSemantics() {
		ArrayList<Hop> inputs = new ArrayList<>(List.of(matrix("X", 8, 4), matrix("b", 8, 4)));
		DnnOp dnn = new DnnOp("biasadd", DataType.MATRIX, ValueType.FP64, OpOpDnn.BIASADD, inputs);
		dnn.setDim1(8); dnn.setDim2(4);
		RuleMemo.Rule rule = rule(ExecType.CP,
			List.of(MemoPartition.LOCAL, MemoPartition.LOCAL), MemoPartition.LOCAL);
		double expected=FederatedCostModel.computeOpCostWithFallback(dnn);
		Assert.assertTrue(expected>0);
		Assert.assertEquals(expected,MemoCostModel.nativeCost(dnn,rule,WORKERS,1),1e-12);
	}

	@Test
	public void unknownExpensiveKernelIsRejectedInsteadOfInvented() {
		ArrayList<Hop> inputs = new ArrayList<>(List.of(matrix("X", 8, 4),matrix("F",1,1),
			new org.apache.sysds.hops.LiteralOp(1L),new org.apache.sysds.hops.LiteralOp(1L),
			new org.apache.sysds.hops.LiteralOp(0L),new org.apache.sysds.hops.LiteralOp(0L),
			new org.apache.sysds.hops.LiteralOp(8L),new org.apache.sysds.hops.LiteralOp(1L),
			new org.apache.sysds.hops.LiteralOp(2L),new org.apache.sysds.hops.LiteralOp(2L),
			new org.apache.sysds.hops.LiteralOp(1L),new org.apache.sysds.hops.LiteralOp(1L),
			new org.apache.sysds.hops.LiteralOp(1L),new org.apache.sysds.hops.LiteralOp(1L)));
		DnnOp dnn = new DnnOp("conv2d", DataType.MATRIX, ValueType.FP64, OpOpDnn.CONV2D, inputs);
		RuleMemo.Rule rule = rule(ExecType.CP,
			java.util.Collections.nCopies(inputs.size(),MemoPartition.LOCAL),MemoPartition.LOCAL);

		MemoCostModel.CostException error = Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.nativeCost(dnn, rule, WORKERS, 1));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.UNSUPPORTED_OPERATOR_FAMILY, error.code());
	}

	@Test
	public void completeRowMinimumPatternRetainsItsSparseSemanticEstimate() {
		DataOp source=matrix("X",6,3);
		source.setNnz(-1);
		AggUnaryOp rowMinimum=new AggUnaryOp("rowMins",DataType.MATRIX,ValueType.FP64,
			AggOp.MIN,Direction.Row,source);
		rowMinimum.setDim1(6);
		rowMinimum.setDim2(1);
		rowMinimum.setNnz(-1);
		BinaryOp indicator=new BinaryOp("indicator",DataType.MATRIX,ValueType.BOOLEAN,
			OpOp2.LESSEQUAL,source,rowMinimum);
		indicator.setDim1(6);
		indicator.setDim2(3);
		indicator.setNnz(-1);

		double expected=OptimizerUtils.estimateSizeExactSparsity(6,3,1.0/3.0,DataType.MATRIX);
		Assert.assertEquals(expected,
			FederatedCostModel.getSemanticSparseAssignmentMemEstimate(indicator),0.0);
	}

	@Test
	public void truncatedPatternLeafFallsBackToConcreteGenericEstimate() throws Exception {
		BinaryOp full=new BinaryOp("detached",DataType.MATRIX,ValueType.BOOLEAN,
			OpOp2.EQUAL,matrix("L",6,3),matrix("R",6,3));
		BinaryOp truncated=(BinaryOp)full.clone();
		truncated.setDim1(6);
		truncated.setDim2(3);
		truncated.setNnz(-1);

		Assert.assertTrue("the regression requires the bounded-view leaf shape",
			truncated.getInput().isEmpty());
		Assert.assertEquals(0.0,
			FederatedCostModel.getSemanticSparseAssignmentMemEstimate(truncated),0.0);
		Assert.assertTrue("an exact matrix shape must retain the generic memory estimate",
			FederatedCostModel.getEffectiveOutputMemEstimate(truncated)>0.0);

		truncated.setNnz(6);
		Assert.assertTrue("known NNZ must also remain a nonzero generic estimate",
			FederatedCostModel.getEffectiveOutputMemEstimate(truncated)>0.0);
	}

	@Test
	public void directFederatedReshardIsRejected() {
		MemoCostModel.CostException error = Assert.assertThrows(MemoCostModel.CostException.class,
			() -> MemoCostModel.movementCost(unary(matrix("X", 8, 4)), MemoPartition.ROW,
				MemoPartition.COL, WORKERS, 1));
		Assert.assertEquals(MemoCostModel.DiagnosticCode.ILLEGAL_MOVEMENT, error.code());
	}

	private static RuleMemo.Rule rule(ExecType exec, List<MemoPartition> inputs, MemoPartition output) {
		return new RuleMemo.Rule(exec, inputs, List.of(output), "test");
	}

	private static UnaryOp unary(Hop input) {
		UnaryOp exp = new UnaryOp("exp", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, input);
		exp.setDim1(input.getDim1());
		exp.setDim2(input.getDim2());
		return exp;
	}

	private static DataOp matrix(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, rows, cols,
			rows > 0 && cols > 0 ? rows * cols : -1, 1000);
	}
}
