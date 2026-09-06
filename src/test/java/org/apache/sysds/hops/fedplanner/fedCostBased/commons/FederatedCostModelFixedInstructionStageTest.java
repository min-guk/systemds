/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Deterministic arithmetic contract for the fixed stage of one logical FED instruction. */
public class FederatedCostModelFixedInstructionStageTest {
	@Test
	public void fixedInstructionStageAddsIndependentLatencyAndControl() {
		Assert.assertEquals("Seven executions must each pay one millisecond of network latency"
			+ " plus one millisecond of coordinator control", 14.0,
			FederatedCostModel.computeFixedFederatedInstructionStageCost(7.0, 1.0, 1.0), 0.0);
		Assert.assertEquals(7.0,
			FederatedCostModel.computeFixedFederatedInstructionStageCost(7.0, 1.0, 0.0), 0.0);
		Assert.assertEquals(7.0,
			FederatedCostModel.computeFixedFederatedInstructionStageCost(7.0, 0.0, 1.0), 0.0);
		Assert.assertEquals("A branch probability is an execution weight, not a minimum-one count",
			1.0, FederatedCostModel.computeFixedFederatedInstructionStageCost(0.5, 1.0, 1.0), 0.0);
	}

	@Test
	public void aggregateBinaryLoutResultReusesTheFederatedInstructionRoundTrip() throws Exception {
		AggBinaryOp multiply = matrixMultiply();
		double outputBytes = 50_000D * 50D * 8D;
		double expectedPayload = inBandPayload(outputBytes, 5);
		double actual = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
			multiply, FType.ROW, outputBytes, 5, Double.POSITIVE_INFINITY);

		Assert.assertTrue("The fixture must exercise an in-band worker result payload", actual > 0.0);
		Assert.assertEquals("EXEC_INST, GET_VAR, and cleanup share the existing FED request/response;"
			+ " native LOUT owns payload only, not a second RTT", expectedPayload, actual, 0.0);
	}

	@Test
	public void rowResultFanInUsesTheLargestConcurrentWorkerResponse() throws Exception {
		double outputBytes = 50_000D * 50D * 8D;
		double oneWorker = inBandPayload(outputBytes, 1);
		double fiveWorkers = inBandPayload(outputBytes, 5);
		Assert.assertEquals("Five balanced ROW responses overlap on independent worker paths",
			oneWorker / 5.0, fiveWorkers, 1e-12);
	}

	@Test
	public void aggregateAddReturnsEveryWorkerPartialInsideTheInstructionRoundTrip() throws Exception {
		AggBinaryOp multiply = matrixMultiply();
		double outputBytesPerWorker = 50_000D * 50D * 8D;
		FederatedCostModel.MixedFedLocalCost stages = FederatedCostModel.computeMixedFedLocalCost(
			multiply, multiply.getInput(), Arrays.asList(FType.BROADCAST, FType.ROW),
			FType.ROW, 1.0, outputBytesPerWorker, 5);
		double expectedReplicatedPayload = inBandPayload(outputBytesPerWorker * 5, 5);

		Assert.assertEquals("aggAdd returns one full partial matrix per worker, but its payload"
			+ " shares the FED instruction request/response", expectedReplicatedPayload,
			stages.getPartialResultDownloadCost(), 0.0);
		Assert.assertTrue("The coordinator reduction remains a distinct local cost",
			stages.getCoordinatorLocalCost() > 0.0);
	}

	@Test
	public void separateFoutMaterializationOwnsOneMoreRequestStageThanInlineLout() throws Exception {
		double outputBytes = 50_000D * 50D * 8D;
		double instructionStage = FederatedCostModel.computeFixedFederatedInstructionStageCost(
			1.0, 100.0, 0.35);
		double inlineLout = instructionStage + inBandPayload(outputBytes, 5, 25.0, 210.0);
		double separateFout = instructionStage
			+ FederatedCostModel.computeReusableMaterializationDownloadCost(outputBytes, 5,
				25.0, 210.0, 14.7, 4D * 1024 * 1024, 0.100, 0.35);
		Assert.assertEquals("A later FOUT GET is a second request; inline LOUT is not",
			100.35, separateFout - inlineLout, 1e-9);
	}

	@Test
	public void recognizedMatrixMultiplyWithNoEffectiveResultSizeRetainsFallback() {
		AggBinaryOp multiply = Mockito.spy(matrixMultiply());
		Mockito.doReturn(0.0).when(multiply).getOutputMemEstimate();
		Mockito.doReturn(0.0).when(multiply).getOutputMemEstimate(Mockito.anyDouble());
		double fallback = 123.456;

		Assert.assertEquals("A recognized matrix multiply must not invent zero-byte transfer cost",
			fallback, FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				multiply, FType.ROW, 0.0, 5, fallback), 0.0);
	}

	@Test
	public void nonMatrixMultiplyAndMalformedHopRetainGenericFallback() {
		double fallback = 123.456;
		DataOp nonMultiply = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 50_000, 50, 2_500_000, 1_000);
		Assert.assertEquals(fallback,
			FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				nonMultiply, FType.ROW, 20_000_000, 5, fallback), 0.0);
		Assert.assertEquals(fallback,
			FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
				null, FType.ROW, 20_000_000, 5, fallback), 0.0);
	}

	private static AggBinaryOp matrixMultiply() {
		DataOp left = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 50_000, 2_100, 105_000_000, 1_000);
		DataOp right = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "V", 2_100, 50, 105_000, 1_000);
		return new AggBinaryOp("ba+*", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);
	}

	private static double inBandPayload(double bytes, int fanIn) throws Exception {
		return inBandPayload(bytes, fanIn, constant("MBS_NETWORK_BANDWIDTH_W2C"),
			constant("MBS_IN_BAND_RESULT_SERDES_BANDWIDTH_W2C"));
	}

	private static double inBandPayload(double bytes, int fanIn,
		double bandwidth, double serdesBandwidth) throws Exception {
		Method method = FederatedCostModel.class.getDeclaredMethod(
			"computeParallelInBandResultPayloadCost", double.class, int.class,
			double.class, double.class);
		method.setAccessible(true);
		return (double) method.invoke(null, bytes, fanIn, bandwidth, serdesBandwidth);
	}

	private static double constant(String name) throws Exception {
		Field field = FederatedCostModel.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getDouble(null);
	}
}
