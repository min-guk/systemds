/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.federated;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.parser.DMLProgram;

public class FederatedCostModelFallbackTest {
	private static final double UNKNOWN_DIM_SENTINEL_BYTES = 8d * 1024 * 1024 * 1024;

	@Test
	public void testEffectiveOutputMemEstimateFallback() {
		LiteralOp hop = new LiteralOp(1.0) {
			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 2 * 1024 * 1024;
			}
		};

		double outputMem = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		Assert.assertTrue("Fallback output mem estimate should be positive", outputMem > 0.0);
	}

	@Test
	public void testComputeOpCostWithFallbackUsesInjectedMemEstimates() {
		LiteralOp hop = new LiteralOp(1.0) {
			@Override
			public double getInputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getInputMemEstimate(double injectedDefault) {
				return 8 * 1024 * 1024;
			}

			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 4 * 1024 * 1024;
			}
		};

		double opCost = FederatedCostModel.computeOpCostWithFallback(hop);
		Assert.assertTrue("Fallback op cost should be positive when injected mem is available", opCost > 0.0);
	}

	@Test
	public void testEffectiveOutputMemEstimateUsesValueTypeSpecificDefaults() {
		assertOutputFallbackInjectedDefault(ValueType.BOOLEAN, OptimizerUtils.BOOLEAN_SIZE);
		assertOutputFallbackInjectedDefault(ValueType.INT32, OptimizerUtils.INT_SIZE);
		assertOutputFallbackInjectedDefault(ValueType.FP32, 4.0);
		assertOutputFallbackInjectedDefault(ValueType.FP64, OptimizerUtils.DOUBLE_SIZE);
		assertOutputFallbackInjectedDefault(ValueType.STRING, 100.0 * OptimizerUtils.CHAR_SIZE);
	}

	@Test
	public void testEffectiveInputMemEstimateUsesInputValueTypeDefaults() {
		double[] boolInjected = {-1.0};
		LiteralOp boolInput = new LiteralOp(1.0) {
			@Override
			public ValueType getValueType() {
				return ValueType.BOOLEAN;
			}

			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				boolInjected[0] = injectedDefault;
				return injectedDefault;
			}
		};

		double[] fp64Injected = {-1.0};
		LiteralOp fp64Input = new LiteralOp(1.0) {
			@Override
			public ValueType getValueType() {
				return ValueType.FP64;
			}

			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				fp64Injected[0] = injectedDefault;
				return injectedDefault;
			}
		};

		LiteralOp parent = new LiteralOp(1.0) {
			@Override
			public double getInputMemEstimate() {
				return 0.0;
			}
		};
		parent.addInput(boolInput);
		parent.addInput(fp64Input);

		double inputMem = FederatedCostModel.getEffectiveInputMemEstimate(parent);
		Assert.assertEquals(OptimizerUtils.BOOLEAN_SIZE, boolInjected[0], 0.0);
		Assert.assertEquals(OptimizerUtils.DOUBLE_SIZE, fp64Injected[0], 0.0);
		Assert.assertEquals(OptimizerUtils.BOOLEAN_SIZE + OptimizerUtils.DOUBLE_SIZE, inputMem, 0.0);
	}

	@Test
	public void testDpComputeHopCostUsesFallbackMemEstimates() {
		LiteralOp hop = new LiteralOp(1.0) {
			@Override
			public double getInputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getInputMemEstimate(double injectedDefault) {
				return 8 * 1024 * 1024;
			}

			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 4 * 1024 * 1024;
			}
		};

		FederatedPlannerDpMemoTable.HopCommon hopCommon = new FederatedPlannerDpMemoTable.HopCommon(
				hop, 1.0, 1.0, 1.0, 1, Collections.emptyList());
		double selfCost = FederatedPlannerDpCostEstimator.computeHopCost(hopCommon);

		Assert.assertTrue("DP self cost should be positive with fallback mem estimates", selfCost > 0.0);
		Assert.assertTrue("DP forwarding cost should be positive with fallback mem estimates",
				hopCommon.getForwardingCost() > 0.0);
	}

	@Test
	public void testEffectiveOutputMemEstimateClampsUnknownDimensionSentinel() {
		TestMatrixHop child = new TestMatrixHop("child", 1000, 1000, 32 * 1024 * 1024, 32 * 1024 * 1024);
		TestMatrixHop parent = new TestMatrixHop("parent", 2100, -1,
				UNKNOWN_DIM_SENTINEL_BYTES, UNKNOWN_DIM_SENTINEL_BYTES);
		parent.addInput(child);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(parent);

		Assert.assertTrue("Unknown-dimension output estimate should be clamped below the raw sentinel",
				effectiveOutputMem < UNKNOWN_DIM_SENTINEL_BYTES);
		Assert.assertTrue("Clamped output estimate should remain positive", effectiveOutputMem > 0.0);
		Assert.assertTrue("Clamped output estimate should stay within a small multiple of input size",
				effectiveOutputMem <= 32d * 1024 * 1024 * 4);
	}

	@Test
	public void testComputeOpCostUsesClampedUnknownDimensionMemEstimate() {
		TestMatrixHop child = new TestMatrixHop("child", 1000, 1000, 32 * 1024 * 1024, 32 * 1024 * 1024);
		TestMatrixHop parent = new TestMatrixHop("parent", 2100, -1,
				UNKNOWN_DIM_SENTINEL_BYTES, UNKNOWN_DIM_SENTINEL_BYTES);
		parent.addInput(child);

		double opCost = FederatedCostModel.computeOpCost(parent);
		double rawOutputOnlyCost = FederatedCostModel.computeMemoryAccessCost(UNKNOWN_DIM_SENTINEL_BYTES);

		Assert.assertTrue("Op cost should stay positive after clamping unknown-dimension memory", opCost > 0.0);
		Assert.assertTrue("Op cost should be much smaller than the raw 8GB-sentinel access cost",
				opCost < rawOutputOnlyCost);
		Assert.assertTrue("Op cost should remain in the sub-second range for the clamped test payload",
				opCost < 1000.0);
	}

	@Test
	public void testEffectiveUploadMemEstimateDoesNotReinflateSentinelClampedUnknownDimensionOutput() {
		TestMatrixHop child = new TestMatrixHop("child", 1000, 1000, 32 * 1024 * 1024, 32 * 1024 * 1024);
		TestMatrixHop parent = new TestMatrixHop("parent", 2100, -1,
				32 * 1024 * 1024, UNKNOWN_DIM_SENTINEL_BYTES);
		parent.addInput(child);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(parent);
		double uploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(parent);

		Assert.assertEquals("Sentinel-clamped unknown-dimension output should keep descendant-based output clamp",
				32d * 1024 * 1024, effectiveOutputMem, 0.0);
		Assert.assertEquals("Upload estimate should not re-inflate a sentinel-clamped unknown-dimension payload",
				effectiveOutputMem, uploadMem, 0.0);
	}

	@Test
	public void testEffectiveUploadMemEstimateKeepsOneAxisFloorForRawZeroUnknownDimensionOutput() {
		TestMatrixHop parent = new TestMatrixHop("parent", 2100, -1, 32 * 1024 * 1024, 0.0);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(parent);
		double uploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(parent);
		double expectedFloor = 2100d * 2100d * OptimizerUtils.DOUBLE_SIZE;

		Assert.assertTrue("Raw-zero unknown-dimension output should stay tiny before upload-floor correction",
				effectiveOutputMem < expectedFloor);
		Assert.assertEquals("Upload estimate should preserve the one-axis floor for raw-zero unknown-dimension outputs",
				expectedFloor, uploadMem, 0.0);
	}

	@Test
	public void testDmlFunctionOpCostExceedsGenericPlaceholderBaseline() throws Exception {
		TestDmlFunctionOp functionHop = createTestDmlFunctionOp();

		double inputMem = FederatedCostModel.getEffectiveInputMemEstimate(functionHop);
		double outputMem = FederatedCostModel.getEffectiveOutputMemEstimate(functionHop);
		double placeholderBaseline = computeGenericPlaceholderBaseline(functionHop, inputMem, outputMem);
		double opCost = FederatedCostModel.computeOpCost(functionHop);

		Assert.assertTrue("DML FunctionOp cost should exceed the generic placeholder baseline",
				opCost > placeholderBaseline);
	}

	@Test
	public void testSingleWorkerFedExecPenaltyDependsOnConfiguredControlOverhead() throws Exception {
		TestDmlFunctionOp functionHop = createTestDmlFunctionOp();
		functionHop.getInput().get(0).setForcedExecType(Types.ExecType.FED);
		functionHop.getInput().get(0).setFederatedOutput(FederatedOutput.FOUT);

		double penalty = FederatedCostModel.computeSingleWorkerFedExecPenalty(functionHop, 1.0, 1);
		double ctrlMs = getFederatedCostModelConstant("LOCAL_TO_FED_CTRL_OVERHEAD_MS");
		double thresholdMs = getFederatedCostModelConstant("SINGLE_WORKER_CTRL_PENALTY_THRESHOLD_MS");

		if (ctrlMs <= thresholdMs)
			Assert.assertEquals("Without material control-plane overhead, the single-worker penalty stays disabled",
				0.0, penalty, 0.0);
		else
			Assert.assertTrue("With material control-plane overhead, the single-worker DML FunctionOp penalty should activate even with fed inputs",
				penalty > 0.0);
	}

	@Test
	public void testDpComputeHopCostUsesDmlFunctionOpSharedFloor() throws Exception {
		TestDmlFunctionOp functionHop = createTestDmlFunctionOp();
		double inputMem = FederatedCostModel.getEffectiveInputMemEstimate(functionHop);
		double outputMem = FederatedCostModel.getEffectiveOutputMemEstimate(functionHop);
		double placeholderBaseline = computeGenericPlaceholderBaseline(functionHop, inputMem, outputMem);

		FederatedPlannerDpMemoTable.HopCommon hopCommon = new FederatedPlannerDpMemoTable.HopCommon(
				functionHop, 1.0, 1.0, 1.0, 1, Collections.emptyList());
		double selfCost = FederatedPlannerDpCostEstimator.computeHopCost(hopCommon);

		Assert.assertEquals("DP estimator should consume the shared FunctionOp op cost",
				FederatedCostModel.computeOpCostWithFallback(functionHop), selfCost, 1e-12);
		Assert.assertTrue("DP self cost should exceed the generic placeholder baseline",
				selfCost > placeholderBaseline);
		Assert.assertTrue("DP forwarding cost should remain positive for DML FunctionOp placeholders",
				hopCommon.getForwardingCost() > 0.0);
	}

	@Test
	public void testAggBinaryComputeCostUsesDedicatedThroughputCalibration() throws Exception {
		AggBinaryOp aggBinaryHop = createTestAggBinaryHop();
		double inputMem = FederatedCostModel.getEffectiveInputMemEstimate(aggBinaryHop);
		double outputMem = FederatedCostModel.getEffectiveOutputMemEstimate(aggBinaryHop);
		double opCost = FederatedCostModel.computeOpCost(aggBinaryHop);
		double computeCost = ComputeCost.getHOPComputeCost(aggBinaryHop);
		double aggbinaryFlops = getFederatedCostModelConstant("AGGBINARY_FLOPS_PER_SEC");
		double genericFlops = getFederatedCostModelConstant("FLOPS_PER_SEC");
		double toMs = getFederatedCostModelConstant("TO_MS");
		double inputAccessCost = FederatedCostModel.computeMemoryAccessCost(inputMem);
		double outputAccessCost = FederatedCostModel.computeMemoryAccessCost(outputMem);
		double expectedAggBinaryCost = Math.max((computeCost / aggbinaryFlops) * toMs, inputAccessCost)
			+ outputAccessCost;
		double genericBaseline = Math.max((computeCost / genericFlops) * toMs, inputAccessCost)
			+ outputAccessCost;

		Assert.assertEquals("AggBinary cost should use the dedicated throughput calibration",
			expectedAggBinaryCost, opCost, 1e-9);
		Assert.assertTrue("AggBinary dedicated throughput should reduce the shared-model op cost",
			opCost < genericBaseline);
	}

	@Test
	public void testLocalToFedForwardingPenaltyRequiresFederatedType() {
		Assert.assertEquals(0.0,
				FederatedCostModel.computeLocalToFedForwardingPenalty(null, 4), 0.0);
	}

	@Test
	public void testLocalToFedForwardingPenaltyScalesWithWorkerFanout() {
		double rowPenaltyOneWorker = FederatedCostModel.computeLocalToFedForwardingPenalty(FType.ROW, 1);
		double rowPenaltyFourWorkers = FederatedCostModel.computeLocalToFedForwardingPenalty(FType.ROW, 4);
		double broadcastPenaltyFourWorkers = FederatedCostModel.computeLocalToFedForwardingPenalty(FType.BROADCAST, 4);

		Assert.assertEquals(0.0, rowPenaltyOneWorker, 0.0);
		Assert.assertTrue("Forwarding penalty should increase with worker fan-out",
				rowPenaltyFourWorkers > rowPenaltyOneWorker);
		Assert.assertEquals("Forwarding penalty is latency-fanout based and independent of FType payload multiplier",
				rowPenaltyFourWorkers, broadcastPenaltyFourWorkers, 0.0);
	}

	private static void assertOutputFallbackInjectedDefault(ValueType valueType, double expectedDefault) {
		double[] injected = {-1.0};
		LiteralOp hop = new LiteralOp(1.0) {
			@Override
			public ValueType getValueType() {
				return valueType;
			}

			@Override
			public double getOutputMemEstimate() {
				return 0.0;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				injected[0] = injectedDefault;
				return injectedDefault;
			}
		};

		double outputMem = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		Assert.assertEquals(expectedDefault, injected[0], 0.0);
		Assert.assertEquals(expectedDefault, outputMem, 0.0);
	}

	private static TestDmlFunctionOp createTestDmlFunctionOp() {
		TestMatrixHop left = new TestMatrixHop("left", 4000, 1000, 32 * 1024 * 1024, 32 * 1024 * 1024);
		TestMatrixHop right = new TestMatrixHop("right", 4000, 1000, 32 * 1024 * 1024, 32 * 1024 * 1024);
		return new TestDmlFunctionOp("test_fun", List.of(left, right), 4000, 1000, 32 * 1024 * 1024);
	}

	private static AggBinaryOp createTestAggBinaryHop() {
		TestMatrixHop left = new TestMatrixHop("left", 50000, 2100, 16 * 1024 * 1024, 16 * 1024 * 1024);
		left.setNnz(left.getDim1() * left.getDim2());
		TestMatrixHop right = new TestMatrixHop("right", 2100, 50, 1024 * 1024, 1024 * 1024);
		right.setNnz(right.getDim1() * right.getDim2());
		AggBinaryOp aggBinaryOp = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64, OpOp2.MULT,
			AggOp.SUM, left, right);
		aggBinaryOp.setNnz(-1);
		return aggBinaryOp;
	}

	private static double computeGenericPlaceholderBaseline(FunctionOp hop, double inputMem, double outputMem)
			throws Exception {
		double flopsPerSec = getFederatedCostModelConstant("FLOPS_PER_SEC");
		double toMs = getFederatedCostModelConstant("TO_MS");
		double computeCost = ComputeCost.getHOPComputeCost(hop);
		double computeTime = (computeCost / flopsPerSec) * toMs;
		double inputAccessCost = FederatedCostModel.computeMemoryAccessCost(inputMem);
		double outputAccessCost = FederatedCostModel.computeMemoryAccessCost(outputMem);
		return Math.max(computeTime, inputAccessCost) + outputAccessCost;
	}

	private static double getFederatedCostModelConstant(String fieldName) throws Exception {
		Field field = FederatedCostModel.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.getDouble(null);
	}

	private static final class TestMatrixHop extends DataOp {
		private final double rawInputMemEstimate;
		private final double rawOutputMemEstimate;

		private TestMatrixHop(String name, long rows, long cols, double rawInputMemEstimate, double rawOutputMemEstimate) {
			super(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD, name, rows, cols, -1, 1024);
			this.rawInputMemEstimate = rawInputMemEstimate;
			this.rawOutputMemEstimate = rawOutputMemEstimate;
			setDim1(rows);
			setDim2(cols);
			setNnz(-1);
		}

		@Override
		public double getInputMemEstimate() {
			return rawInputMemEstimate;
		}

		@Override
		public double getOutputMemEstimate() {
			return rawOutputMemEstimate;
		}
	}

	private static final class TestDmlFunctionOp extends FunctionOp {
		private final double rawOutputMemEstimate;

		private TestDmlFunctionOp(String functionName, List<org.apache.sysds.hops.Hop> inputs,
				long rows, long cols, double rawOutputMemEstimate) {
			super(FunctionOp.FunctionType.DML, DMLProgram.DEFAULT_NAMESPACE, functionName,
					buildInputNames(inputs.size()), inputs, new String[] {"Y"}, true);
			this.rawOutputMemEstimate = rawOutputMemEstimate;
			setDataType(DataType.MATRIX);
			setValueType(ValueType.FP64);
			setDim1(rows);
			setDim2(cols);
			setNnz(-1);
		}

		@Override
		public double getOutputMemEstimate() {
			return rawOutputMemEstimate;
		}

		@Override
		public double getOutputMemEstimate(double injectedDefault) {
			return rawOutputMemEstimate > 0.0 ? rawOutputMemEstimate : super.getOutputMemEstimate(injectedDefault);
		}
	}

	private static String[] buildInputNames(int size) {
		String[] names = new String[size];
		for (int i = 0; i < size; i++)
			names[i] = "X" + i;
		return names;
	}
}
