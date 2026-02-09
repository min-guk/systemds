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

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;

public class FederatedCostModelFallbackTest {

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
}
