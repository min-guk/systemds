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
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
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
	public void testDpComputeHopCostUsesOutputMemEstimateForDownloadCost() {
		TestMatrixHop hop = new TestMatrixHop("unknownOutput", 2100, -1,
			32 * 1024 * 1024, 0.0);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		double effectiveUploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		FederatedPlannerDpMemoTable.HopCommon hopCommon = new FederatedPlannerDpMemoTable.HopCommon(
			hop, 1.0, 1.0, 1.0, 1, Collections.emptyList());
		FederatedPlannerDpCostEstimator.computeHopCost(hopCommon);

		Assert.assertTrue("Test precondition: upload estimate should exceed the output estimate for this unknown-dim hop",
			effectiveUploadMem > effectiveOutputMem);
		Assert.assertEquals("DP forwarding cost should follow the download payload, not the upload payload",
			FederatedCostModel.computeDownloadNetworkCost(effectiveOutputMem), hopCommon.getForwardingCost(), 1e-9);
	}

	@Test
	public void testEigenFunctionOutputMemEstimateUsesPerOutputSemantics() {
		DataOp input = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 2100, 2100, -1, 1024);
		DataOp eigenValues = new DataOp("eigen_values", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		eigenValues.setDim1(-1);
		eigenValues.setDim2(1);
		eigenValues.setNnz(-1);
		DataOp eigenVectors = new DataOp("eigen_vectors", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		eigenVectors.setDim1(-1);
		eigenVectors.setDim2(-1);
		eigenVectors.setNnz(-1);

		FunctionOp eigen = new FunctionOp(FunctionOp.FunctionType.MULTIRETURN_BUILTIN, DMLProgram.INTERNAL_NAMESPACE,
			"eigen", new String[] {"X"}, List.of(input), new String[] {"eigen_values", "eigen_vectors"},
			new java.util.ArrayList<>(List.of(eigenValues, eigenVectors)));

		double expectedValues = OptimizerUtils.estimateSizeExactSparsity(2100, 1, 1.0, DataType.MATRIX);
		double expectedVectors = OptimizerUtils.estimateSizeExactSparsity(2100, 2100, 1.0, DataType.MATRIX);

		Assert.assertEquals(expectedValues, eigen.getMultiReturnBuiltinOutputMemEstimate(eigenValues), 0.0);
		Assert.assertEquals(expectedVectors, eigen.getMultiReturnBuiltinOutputMemEstimate(eigenVectors), 0.0);
		Assert.assertEquals(expectedValues, FederatedCostModel.getEffectiveOutputMemEstimate(eigenValues), 0.0);
		Assert.assertEquals(expectedVectors, FederatedCostModel.getEffectiveOutputMemEstimate(eigenVectors), 0.0);
	}

	@Test
	public void testTransientReadUsesExplicitMultiReturnFunctionOutputSourceMemEstimate() {
		DataOp input = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 2100, 2100, -1, 1024);
		DataOp eigenValues = new DataOp("eigen_values", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		eigenValues.setDim1(-1);
		eigenValues.setDim2(1);
		eigenValues.setNnz(-1);
		DataOp eigenVectors = new DataOp("eigen_vectors", DataType.MATRIX, ValueType.FP64,
			input, OpOpData.FUNCTIONOUTPUT, null);
		eigenVectors.setDim1(-1);
		eigenVectors.setDim2(-1);
		eigenVectors.setNnz(-1);
		new FunctionOp(FunctionOp.FunctionType.MULTIRETURN_BUILTIN, DMLProgram.INTERNAL_NAMESPACE,
			"eigen", new String[] {"X"}, List.of(input), new String[] {"eigen_values", "eigen_vectors"},
			new java.util.ArrayList<>(List.of(eigenValues, eigenVectors)));

		DataOp transientRead = new DataOp("eigen_values", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "eigen_values", -1, 1, -1, 1024);
		transientRead.setDim1(-1);
		transientRead.setDim2(1);
		transientRead.setNnz(-1);

		double expectedValues = OptimizerUtils.estimateSizeExactSparsity(2100, 1, 1.0, DataType.MATRIX);
		Assert.assertEquals(expectedValues,
			FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(transientRead, eigenValues), 0.0);
		Assert.assertTrue(FederatedPlannerUtils.propagateMultiReturnFunctionOutputStatsToTransientRead(
			transientRead, eigenValues));
		Assert.assertEquals(2100, transientRead.getDim1());
		Assert.assertEquals(1, transientRead.getDim2());
		Assert.assertEquals(2100, transientRead.getNnz());
		Assert.assertEquals(FederatedCostModel.getEffectiveOutputMemEstimate(transientRead),
			FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(transientRead, eigenValues), 0.0);
	}

	@Test
	public void testTransientReadUnknownDimsUsesConcreteCompatibleSourceEnvelope() {
		DataOp sourceSmall = new DataOp("C_small", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTWRITE, "C_small", 50, 2100, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return 840000.0;
			}
		};
		sourceSmall.setDim1(50);
		sourceSmall.setDim2(2100);
		sourceSmall.setNnz(-1);

		DataOp sourceLarge = new DataOp("C_large", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTWRITE, "C_large", 50, 50000, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return 2.0E7;
			}
		};
		sourceLarge.setDim1(50);
		sourceLarge.setDim2(50000);
		sourceLarge.setNnz(-1);

		DataOp transientRead = new DataOp("Y_n", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "Y_n", 50, -1, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return UNKNOWN_DIM_SENTINEL_BYTES;
			}
		};
		transientRead.setDim1(50);
		transientRead.setDim2(-1);
		transientRead.setNnz(-1);
		transientRead.addInput(sourceSmall);
		transientRead.addInput(sourceLarge);

		Assert.assertEquals("Unknown-dimension transient reads should prefer the largest compatible concrete "
			+ "source instead of the generic sentinel fallback",
			2.0E7, FederatedCostModel.getEffectiveOutputMemEstimate(transientRead), 0.0);
		Assert.assertEquals(2.0E7, FederatedCostModel.getEffectiveUploadMemEstimate(transientRead), 0.0);
	}

	@Test
	public void testTransientReadUnknownDimsDoesNotInheritRawFederatedSourceEnvelope() {
		DataOp federatedSource = new DataOp("X_fed", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X_fed", 50000, 2100, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return 2.0E7;
			}
		};
		federatedSource.setDim1(50000);
		federatedSource.setDim2(2100);
		federatedSource.setNnz(-1);

		DataOp transientRead = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 50, -1, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return UNKNOWN_DIM_SENTINEL_BYTES;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 8.4E5;
			}
		};
		transientRead.setDim1(50);
		transientRead.setDim2(-1);
		transientRead.setNnz(-1);
		transientRead.addInput(federatedSource);

		Assert.assertEquals("Generic TRANSIENTREAD sizing should not inherit the full FEDERATED source envelope",
			8.4E5, FederatedCostModel.getEffectiveOutputMemEstimate(transientRead), 0.0);
		Assert.assertEquals("Upload sizing should follow the TRANSIENTREAD's own fallback envelope for direct FED input",
			8.4E5, FederatedCostModel.getEffectiveUploadMemEstimate(transientRead), 0.0);
	}

	@Test
	public void testElementwiseUnknownDimsStayBoundedByDirectFederatedTransientReadEnvelope() {
		DataOp federatedSource = new DataOp("X_fed", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "X_fed", 50000, 2100, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return 2.0E7;
			}
		};
		federatedSource.setDim1(50000);
		federatedSource.setDim2(2100);
		federatedSource.setNnz(-1);

		DataOp transientRead = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 50, -1, -1, 1024) {
			@Override
			public double getOutputMemEstimate() {
				return UNKNOWN_DIM_SENTINEL_BYTES;
			}

			@Override
			public double getOutputMemEstimate(double injectedDefault) {
				return 8.4E5;
			}
		};
		transientRead.setDim1(50);
		transientRead.setDim2(-1);
		transientRead.setNnz(-1);
		transientRead.addInput(federatedSource);

		LiteralOp scalar = new LiteralOp(1.0);
		BinaryOp elementwise = new BinaryOp("scaledX", DataType.MATRIX, ValueType.FP64, OpOp2.DIV,
			transientRead, scalar) {
			@Override
			public double getOutputMemEstimate() {
				return UNKNOWN_DIM_SENTINEL_BYTES;
			}
		};
		elementwise.setDim1(50);
		elementwise.setDim2(-1);
		elementwise.setNnz(-1);

		Assert.assertEquals("Unknown-dimension elementwise hops should stay bounded by the direct FED-input"
			+ " TRANSIENTREAD envelope instead of re-inflating to broad source/input sums",
			8.4E5, FederatedCostModel.getEffectiveOutputMemEstimate(elementwise), 0.0);
		Assert.assertEquals("Upload sizing should keep the same bounded elementwise envelope",
			8.4E5, FederatedCostModel.getEffectiveUploadMemEstimate(elementwise), 0.0);
	}

	@Test
	public void testDpScalarLiteralForwardingUploadCostIsZero() throws Exception {
		LiteralOp literal = new LiteralOp(2.0);
		TestMatrixHop parent = new TestMatrixHop("parent", 100, 10,
			8 * 1024 * 1024, 8 * 1024 * 1024);

		Method m = FederatedPlannerDpCostEstimator.class.getDeclaredMethod(
			"computeUploadCostWithFallback", Hop.class, Hop.class, FType.class, int.class);
		m.setAccessible(true);
		double uploadCost = (double) m.invoke(null, literal, parent, FType.ROW, 4);

		Assert.assertEquals("Scalar literals should use inline control-plane propagation, not matrix upload cost",
			0.0, uploadCost, 0.0);
	}

	@Test
	public void testDpMatrixForwardingUploadCostRemainsPositive() throws Exception {
		TestMatrixHop child = new TestMatrixHop("child", 100, 10,
			8 * 1024 * 1024, 8 * 1024 * 1024);
		TestMatrixHop parent = new TestMatrixHop("parent", 100, 10,
			8 * 1024 * 1024, 8 * 1024 * 1024);

		Method m = FederatedPlannerDpCostEstimator.class.getDeclaredMethod(
			"computeUploadCostWithFallback", Hop.class, Hop.class, FType.class, int.class);
		m.setAccessible(true);
		double uploadCost = (double) m.invoke(null, child, parent, FType.ROW, 4);

		Assert.assertTrue("Matrix boundary forwarding must still pay a positive upload cost", uploadCost > 0.0);
	}

	@Test
	public void testStableTransientReadFoutCumulativeShareDoesNotGoNegativeWhenForwardingIsExternal()
		throws Exception {
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlannerUtils.registerFedInitVar("fedInput", FType.FULL);
		try {
			DataOp transientRead = new DataOp("fedInput", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "fedInput", 100, 20, -1, 1024);
			FederatedPlannerDpMemoTable.HopCommon hopCommon = new FederatedPlannerDpMemoTable.HopCommon(
				transientRead, 1.0, 1.0, 1.0, 3, Collections.emptyList());
			Field forwardingField = FederatedPlannerDpMemoTable.HopCommon.class.getDeclaredField("forwardingCost");
			forwardingField.setAccessible(true);
			forwardingField.setDouble(hopCommon, 9.0);

			FederatedPlannerDpMemoTable.FedPlanVariants variants =
				new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan plan =
				new FederatedPlannerDpMemoTable.FedPlan(0.0, variants, Collections.emptyList());
			plan.setExecType(Types.ExecType.FED);
			plan.setFType(FType.FULL);
			variants.addFedPlan(plan);

			Method m = FederatedPlannerDpCostEstimator.class.getDeclaredMethod(
				"computeStableTransientReadFoutCumulativeShareForParent",
				FederatedPlannerDpMemoTable.FedPlan.class,
				FederatedPlannerDpMemoTable.class);
			m.setAccessible(true);
			double share = (double) m.invoke(null, plan, null);

			Assert.assertEquals("Stable FED-input transient reads whose cumulative cost excludes the forwarding edge"
				+ " must not produce a negative cumulative-share correction",
				0.0, share, 1e-9);
		}
		finally {
			FederatedPlannerUtils.clearFedInitVars();
		}
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
	public void testMappingPreservingFederatedTransposeSkipsCoordinationCost() {
		TestMatrixHop input = new TestMatrixHop("fedTransposeInput", 10, 4,
			32 * 1024 * 1024, 32 * 1024 * 1024);
		ReorgOp transpose = HopRewriteUtils.createTranspose(input);
		transpose.setDim1(4);
		transpose.setDim2(10);

		Assert.assertTrue("FULL federated transpose should be recognized as mapping-preserving",
			FederatedCostModel.isMappingPreservingFederatedTranspose(transpose, FType.FULL));
		Assert.assertTrue("BROADCAST federated transpose should be recognized as mapping-preserving",
			FederatedCostModel.isMappingPreservingFederatedTranspose(transpose, FType.BROADCAST));
		Assert.assertFalse("ROW-partitioned transpose should not use the mapping-preserving shortcut",
			FederatedCostModel.isMappingPreservingFederatedTranspose(transpose, FType.ROW));
		Assert.assertEquals("Mapping-preserving federated transpose should not pay a generic FED coordination term",
			0.0, FederatedCostModel.adjustFedCoordinationCost(transpose, FType.FULL, 123.0), 1e-9);
		Assert.assertEquals("Non-mapping-preserving reorgs must keep their FED coordination term",
			123.0, FederatedCostModel.adjustFedCoordinationCost(transpose, FType.ROW, 123.0), 1e-9);
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
	public void testEffectiveUploadMemEstimateUsesIndexingRangeBoundFromSizeExpression() {
		TestMatrixHop source = new TestMatrixHop("source", 2100, 2100,
			512d * 1024 * 1024, 512d * 1024 * 1024);
		UnaryOp nrow = HopRewriteUtils.createUnary(source, OpOp1.NROW);
		IndexingOp slice = createUnknownDimIndexingHop("components", source, nrow, new LiteralOp(10), false, false,
			UNKNOWN_DIM_SENTINEL_BYTES);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(slice);
		double uploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(slice);
		double expectedBound = OptimizerUtils.estimateSizeExactSparsity(2100, 10, 1.0, DataType.MATRIX);

		Assert.assertEquals("Unknown-dimension rightIndex output should clamp to the slice-size bound inferred from"
			+ " 1:nrow(source),1:k bounds",
			expectedBound, effectiveOutputMem, 0.0);
		Assert.assertEquals("CP/FOUT upload sizing should follow the inferred slice-size bound instead of the large"
			+ " unknown-dimension/source-input estimate",
			expectedBound, uploadMem, 0.0);
	}

	@Test
	public void testEffectiveUploadMemEstimateUsesLiteralIndexRangeBound() {
		TestMatrixHop source = new TestMatrixHop("source", 50000, 2100,
			512d * 1024 * 1024, 512d * 1024 * 1024);
		IndexingOp slice = createUnknownDimIndexingHop("sampleSlice", source, new LiteralOp(25000), new LiteralOp(50),
			false, false, UNKNOWN_DIM_SENTINEL_BYTES);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(slice);
		double uploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(slice);
		double expectedBound = OptimizerUtils.estimateSizeExactSparsity(25000, 50, 1.0, DataType.MATRIX);

		Assert.assertEquals("Literal rightIndex ranges should clamp unknown-dimension output estimates to the concrete"
			+ " slice payload", expectedBound, effectiveOutputMem, 0.0);
		Assert.assertEquals("Literal rightIndex ranges should also clamp CP/FOUT upload payload sizing",
			expectedBound, uploadMem, 0.0);
	}

	@Test
	public void testEffectiveUploadMemEstimateUsesIndexingRowBoundWhenRawUnknownOutputIsZero() {
		TestMatrixHop source = new TestMatrixHop("source", 2100, 2100,
			512d * 1024 * 1024, 512d * 1024 * 1024);
		UnaryOp nrow = HopRewriteUtils.createUnary(source, OpOp1.NROW);
		DataOp k = new DataOp("K", DataType.SCALAR, ValueType.INT64, OpOpData.TRANSIENTREAD, "K", 0, 0, -1, 1024);
		IndexingOp slice = createUnknownDimIndexingHop("componentsRowBoundOnly", source, nrow, k, false, false, 0.0);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(slice);
		double uploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(slice);
		double expectedRowBound = 2100d * 2100d * OptimizerUtils.DOUBLE_SIZE;

		Assert.assertEquals("Raw-zero unknown-dimension rightIndex output should still recover a one-axis slice bound"
			+ " from 1:nrow(source) even when the column upper bound stays symbolic",
			expectedRowBound, effectiveOutputMem, 0.0);
		Assert.assertEquals("Upload sizing should use the recovered one-axis indexing bound instead of falling back"
			+ " to the full source/input estimate", expectedRowBound, uploadMem, 0.0);
	}

	@Test
	public void testEffectiveUploadMemEstimateUsesIndexingRowBoundThroughUnknownDimAggBinary() {
		TestMatrixHop left = new TestMatrixHop("left", 2100, 2100,
			512d * 1024 * 1024, 512d * 1024 * 1024);
		TestMatrixHop right = new TestMatrixHop("right", 2100, -1,
			256d * 1024 * 1024, 0.0);
		AggBinaryOp components = new AggBinaryOp("componentsMM", DataType.MATRIX, ValueType.FP64, OpOp2.MULT,
			AggOp.SUM, left, right);
		components.setDim1(-1);
		components.setDim2(-1);
		components.setNnz(-1);
		UnaryOp nrow = HopRewriteUtils.createUnary(components, OpOp1.NROW);
		DataOp k = new DataOp("K", DataType.SCALAR, ValueType.INT64, OpOpData.TRANSIENTREAD, "K", 0, 0, -1, 1024);
		IndexingOp slice = createUnknownDimIndexingHop("componentsAggBinaryRowBoundOnly", components, nrow, k, false,
			false, 0.0);

		double effectiveOutputMem = FederatedCostModel.getEffectiveOutputMemEstimate(slice);
		double uploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(slice);
		double expectedRowBound = 2100d * 2100d * OptimizerUtils.DOUBLE_SIZE;

		Assert.assertEquals("Unknown-dimension IndexingOp output should recover the one-axis row bound even when the"
			+ " nrow() expression references an intermediate AggBinary with unresolved output dims",
			expectedRowBound, effectiveOutputMem, 0.0);
		Assert.assertEquals("Upload sizing should use the AggBinary-derived row bound instead of the large fallback"
			+ " input estimate", expectedRowBound, uploadMem, 0.0);
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
	public void testSingleWorkerFedExecPenaltySkipsConcreteFedInputSingleCall() throws Exception {
		TestDmlFunctionOp functionHop = createTestDmlFunctionOp();
		functionHop.getInput().get(0).setForcedExecType(Types.ExecType.FED);
		functionHop.getInput().get(0).setFederatedOutput(FederatedOutput.FOUT);

		double penalty = FederatedCostModel.computeSingleWorkerFedExecPenalty(functionHop, 1.0, 1);
		Assert.assertEquals("A one-shot DML FunctionOp with an immediate concrete federated matrix input should not"
			+ " receive any extra single-worker placeholder penalty",
			0.0, penalty, 0.0);
	}

	@Test
	public void testSingleWorkerFedExecPenaltyStaysBoundedForRepeatedConcreteFedInput() throws Exception {
		TestDmlFunctionOp functionHop = createTestDmlFunctionOp();
		functionHop.getInput().get(0).setForcedExecType(Types.ExecType.FED);
		functionHop.getInput().get(0).setFederatedOutput(FederatedOutput.FOUT);

		double penalty = FederatedCostModel.computeSingleWorkerFedExecPenalty(functionHop, 5.0, 1);
		double ctrlMs = getFederatedCostModelConstant("LOCAL_TO_FED_CTRL_OVERHEAD_MS");
		double thresholdMs = getFederatedCostModelConstant("SINGLE_WORKER_CTRL_PENALTY_THRESHOLD_MS");

		if (ctrlMs <= thresholdMs)
			Assert.assertEquals("Without material control-plane overhead, the repeated single-worker penalty stays disabled",
				0.0, penalty, 0.0);
		else
			Assert.assertTrue("Repeated single-worker DML FunctionOp calls may pay a bounded boundary penalty, but"
				+ " the cost must stay well below the old hard blocker regime",
				penalty > 0.0 && penalty < 1e6);
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
	public void testQuaternaryComputeCostUsesDedicatedWdivmmFlopEstimate() {
		TestMatrixHop x = new TestMatrixHop("X", 50000, 2100, 16 * 1024 * 1024, 16 * 1024 * 1024);
		TestMatrixHop u = new TestMatrixHop("U", 50000, 32, 1024 * 1024, 1024 * 1024);
		TestMatrixHop v = new TestMatrixHop("V", 2100, 32, 1024 * 1024, 1024 * 1024);
		QuaternaryOp wdivmm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, null, 1, false, false);
		wdivmm.setDim1(50000);
		wdivmm.setDim2(32);

		double computeCost = ComputeCost.getHOPComputeCost(wdivmm);
		double expected = 4d * Math.max(x.getDim1(), 1) * Math.max(x.getDim2(), 1);

		Assert.assertEquals("Quaternary WDIVMM compute cost should use the CPCostUtils flop model",
			expected, computeCost, 1e-9);
		Assert.assertTrue("Quaternary WDIVMM cost should exceed the generic output-size fallback",
			computeCost > (double) wdivmm.getDim1() * wdivmm.getDim2());
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

	@Test
	public void testDownloadNetworkCostScalesWithWorkerFanInForPartitionedLayouts() {
		double memSize = 32 * 1024 * 1024;
		double singleWorker = FederatedCostModel.computeDownloadNetworkCost(memSize, FType.ROW, 1);
		double fourWorkerRow = FederatedCostModel.computeDownloadNetworkCost(memSize, FType.ROW, 4);
		double fourWorkerFull = FederatedCostModel.computeDownloadNetworkCost(memSize, FType.FULL, 4);
		double fourWorkerBroadcast = FederatedCostModel.computeDownloadNetworkCost(memSize, FType.BROADCAST, 4);

		Assert.assertTrue("Partitioned downloads should include worker fan-in latency/control overhead",
			fourWorkerRow > singleWorker);
		Assert.assertEquals("Single-source FULL downloads should not pay multi-worker fan-in overhead",
			FederatedCostModel.computeDownloadNetworkCost(memSize), fourWorkerFull, 1e-9);
		Assert.assertEquals("Replicated BROADCAST downloads materialize one local copy and should not pay full fan-in",
			FederatedCostModel.computeDownloadNetworkCost(memSize), fourWorkerBroadcast, 1e-9);
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

	private static IndexingOp createUnknownDimIndexingHop(String name, Hop input, Hop rowUpper, Hop colUpper,
			boolean rowLEU, boolean colLEU, double rawOutputMemEstimate) {
		IndexingOp indexing = new IndexingOp(name, DataType.MATRIX, ValueType.FP64,
			input, new LiteralOp(1), rowUpper, new LiteralOp(1), colUpper, rowLEU, colLEU) {
			@Override
			public double getOutputMemEstimate() {
				return rawOutputMemEstimate;
			}
		};
		indexing.setDim1(-1);
		indexing.setDim2(-1);
		indexing.setNnz(-1);
		return indexing;
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
