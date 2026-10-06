/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.codegen.SpoofFusedOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.WorkerResponseSummary;
import org.junit.Assert;
import org.junit.Test;

public class NativeResultBatchOwnershipCostTest {
	private static final double BYTES = 8.0;
	private static final int WORKERS = 3;

	@Test
	public void containsResultSharesItsExecutionBatch() {
		LinkedHashMap<String, Hop> inputs = new LinkedHashMap<>();
		inputs.put("target", matrix("X"));
		inputs.put("pattern", new LiteralOp(3D));
		assertInBand(new ParameterizedBuiltinOp("contains", DataType.SCALAR,
			ValueType.BOOLEAN, ParamBuiltinOp.CONTAINS, inputs), Arrays.asList(FType.ROW, null));
	}

	@Test
	public void unweightedCentralMomentResultSharesItsUdfBatch() {
		assertInBand(new BinaryOp("cm", DataType.SCALAR, ValueType.FP64,
			OpOp2.MOMENT, matrix("X"), new LiteralOp(2L)), Arrays.asList(FType.ROW, null));
	}

	@Test
	public void mixedCovarianceResultSharesItsUdfBatch() {
		BinaryOp covariance = new BinaryOp("cov", DataType.SCALAR, ValueType.FP64,
			OpOp2.COV, matrix("X"), matrix("Y"));
		assertInBand(covariance, Arrays.asList(FType.ROW, null));
		assertInBand(covariance, Arrays.asList(null, FType.ROW));
	}

	@Test
	public void alignedCovarianceRetainsItsExistingAdditionalStageEstimate() {
		BinaryOp covariance = new BinaryOp("cov", DataType.SCALAR, ValueType.FP64,
			OpOp2.COV, matrix("X"), matrix("Y"));
		assertSeparateBatch(covariance, List.of(FType.ROW, FType.ROW));
	}

	@Test
	public void weightedScalarKernelsReturnInsideTheirExecutionBatch() {
		Hop x = matrix("X");
		Hop u = matrix("U");
		Hop v = matrix("V");
		assertInBand(new QuaternaryOp("wsloss", DataType.SCALAR, ValueType.FP64,
			OpOp4.WSLOSS, x, u, v, matrix("W"), false),
			Arrays.asList(FType.ROW, null, null, null));
		assertInBand(new QuaternaryOp("wcemm", DataType.SCALAR, ValueType.FP64,
			OpOp4.WCEMM, x, u, v), Arrays.asList(FType.ROW, null, null));
	}

	@Test
	public void spoofResultSharesItsExecuteMultipleSlicesBatch() {
		assertInBand(new SpoofFusedOp(), List.of(FType.ROW));
	}

	@Test
	public void ordinaryBinaryResultRetainsASeparateGetBatch() {
		assertSeparateBatch(new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, matrix("X"), new LiteralOp(1D)), Arrays.asList(FType.ROW, null));
	}

	private static void assertInBand(Hop hop, List<FType> inputFTypes) {
		double expected = FederatedCostModel.computeDownloadNetworkCost(BYTES, FType.ROW, WORKERS)
			- FederatedCostModel.computeRequestResponseLatency();
		Assert.assertEquals(expected, FederatedCostModel.computeNativeFederatedLoutResultCost(
			hop, inputFTypes, FType.ROW, BYTES, WORKERS), 1e-12);
		WorkerResponseSummary responses = new WorkerResponseSummary(BYTES, BYTES / WORKERS, WORKERS);
		double responseCost = FederatedCostModel.computeNativeFederatedLoutResultCost(
			hop, inputFTypes, responses);
		Assert.assertEquals("An in-band result owns payload but no additional request/response latency",
			expected, responseCost, 1e-12);
	}

	private static void assertSeparateBatch(Hop hop, List<FType> inputFTypes) {
		double expected = FederatedCostModel.computeDownloadNetworkCost(BYTES, FType.ROW, WORKERS);
		Assert.assertEquals(expected, FederatedCostModel.computeNativeFederatedLoutResultCost(
			hop, inputFTypes, FType.ROW, BYTES, WORKERS), 1e-12);
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 100, 10, 1_000, 1_000);
	}
}
