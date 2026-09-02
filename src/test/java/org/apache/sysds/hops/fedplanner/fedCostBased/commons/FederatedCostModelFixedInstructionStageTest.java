/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

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
	public void aggregateBinaryLoutOwnsOneBlockingResultStage() {
		DataOp left = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 50_000, 2_100, 105_000_000, 1_000);
		DataOp right = new DataOp("v", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "v", 2_100, 1, 2_100, 1_000);
		AggBinaryOp multiply = new AggBinaryOp("ba+*", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, left, right);
		double outputBytes = 50_000D * 8D;
		double payloadOnly = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
			multiply, FType.ROW, outputBytes, 3, Double.POSITIVE_INFINITY, 0.0);
		double blocking = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
			multiply, FType.ROW, outputBytes, 3, Double.POSITIVE_INFINITY, 100.0);

		Assert.assertTrue("The fixture must exercise an in-band worker result payload", payloadOnly > 0.0);
		Assert.assertEquals("FED/LOUT waits for GET_VAR and binds the response at the coordinator;"
			+ " that blocking stage is paid once per logical execution, not once per worker",
			payloadOnly + 100.0, blocking, 0.0);
	}
}
