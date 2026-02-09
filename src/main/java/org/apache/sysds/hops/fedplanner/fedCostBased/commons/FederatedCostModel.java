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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

public final class FederatedCostModel {
	private static final String ENV_MBS_MEMORY_BANDWIDTH = "SYSDS_FED_COST_MEM_BW";
	private static final String ENV_MBS_NETWORK_BANDWIDTH = "SYSDS_FED_COST_NET_BW";
	private static final String ENV_MBS_NETWORK_LATENCY = "SYSDS_FED_COST_NET_LATENCY";
	private static final String ENV_FLOPS_PER_SEC = "SYSDS_FED_COST_FLOPS";
	private static final double DEFAULT_MEM_ESTIMATE_PER_CELL = OptimizerUtils.DOUBLE_SIZE;
	private static final double DEFAULT_FP32_MEM_ESTIMATE_PER_CELL = 4.0;
	private static final double DEFAULT_STRING_MEM_ESTIMATE_PER_CELL = 100.0 * OptimizerUtils.CHAR_SIZE;

	// Default values are used as reasonable estimates since we only need to compare
	// relative costs between different federated plans.
	// Memory bandwidth for local computations (25 GB/s).
	private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
	// Network bandwidth for data transfers between federated sites (1 Gbps).
	private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
	// Network latency between federated sites (1 ms).
	private static final double DEFAULT_MBS_NETWORK_LATENCY = 0.001;
	// Compute throughput (FLOPs/s), consistent with CostEstimatorStaticRuntime defaults.
	private static final double DEFAULT_FLOPS_PER_SEC = 2d * 1024 * 1024 * 1024;
	// All costs are returned in milliseconds.
	private static final double TO_MS = 1000.0;
	private static final double MBS_MEMORY_BANDWIDTH = getConfiguredDouble(ENV_MBS_MEMORY_BANDWIDTH,
			DEFAULT_MBS_MEMORY_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH = getConfiguredDouble(ENV_MBS_NETWORK_BANDWIDTH,
			DEFAULT_MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_LATENCY = getConfiguredDouble(ENV_MBS_NETWORK_LATENCY,
			DEFAULT_MBS_NETWORK_LATENCY);
	private static final double FLOPS_PER_SEC = getConfiguredDouble(ENV_FLOPS_PER_SEC,
			DEFAULT_FLOPS_PER_SEC);

	private FederatedCostModel() {
		// utility class
	}

	public static double computeOpCost(Hop currentHop) {
		double computeCost = ComputeCost.getHOPComputeCost(currentHop);
		double computeTime = (computeCost / FLOPS_PER_SEC) * TO_MS;
		double inputAccessCost = computeMemoryAccessCost(currentHop.getInputMemEstimate());
		double outputAccessCost = computeMemoryAccessCost(currentHop.getOutputMemEstimate());

		// Total cost assumes:
		// 1) Computation and input access can overlap (take max)
		// 2) Output access must wait for both (add)
		return Math.max(computeTime, inputAccessCost) + outputAccessCost;
	}

	public static double computeOpCostWithFallback(Hop hop) {
		if (hop == null) {
			return 0.0;
		}

		double opCost = computeOpCost(hop);
		if (opCost > 0.0) {
			return opCost;
		}

		double inputMemEstimate = getEffectiveInputMemEstimate(hop);
		double outputMemEstimate = getEffectiveOutputMemEstimate(hop);
		if (inputMemEstimate <= 0.0 && outputMemEstimate <= 0.0) {
			return 0.0;
		}

		double inputAccessCost = computeMemoryAccessCost(inputMemEstimate);
		double outputAccessCost = computeMemoryAccessCost(outputMemEstimate);
		return inputAccessCost + outputAccessCost;
	}

	public static double computeMemoryAccessCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		return (memSize / (1024 * 1024) / MBS_MEMORY_BANDWIDTH) * TO_MS;
	}

	public static double getEffectiveInputMemEstimate(Hop hop) {
		if (hop == null) {
			return 0.0;
		}
		double inputMemEstimate = hop.getInputMemEstimate();
		if (inputMemEstimate > 0.0) {
			return inputMemEstimate;
		}

		double fallbackInputMemEstimate = 0.0;
		for (int i = 0; i < hop.getInput().size(); i++) {
			Hop inputHop = hop.getInput(i);
			double inputOutputMemEstimate = getEffectiveOutputMemEstimate(inputHop);
			if (inputOutputMemEstimate > 1024 * 1024) {
				boolean alreadyCounted = false;
				for (int j = 0; j < i; j++) {
					alreadyCounted |= (inputHop == hop.getInput(j));
				}
				inputOutputMemEstimate = alreadyCounted ? 0.0 : inputOutputMemEstimate;
			}
			fallbackInputMemEstimate += Math.max(0.0, inputOutputMemEstimate);
		}
		if (fallbackInputMemEstimate > 0.0) {
			return fallbackInputMemEstimate;
		}

		return Math.max(0.0, hop.getInputMemEstimate(getInjectedDefaultMemEstimatePerCell(hop)));
	}

	public static double getEffectiveOutputMemEstimate(Hop hop) {
		if (hop == null) {
			return 0.0;
		}
		double outputMemEstimate = hop.getOutputMemEstimate();
		if (outputMemEstimate > 0.0) {
			return outputMemEstimate;
		}
		return Math.max(0.0, hop.getOutputMemEstimate(getInjectedDefaultMemEstimatePerCell(hop)));
	}

	public static double computeNetworkCost(double memSize) {
		return (MBS_NETWORK_LATENCY + (memSize / (1024 * 1024) / MBS_NETWORK_BANDWIDTH)) * TO_MS;
	}

	public static double computeDownloadNetworkCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		return computeNetworkCost(memSize);
	}

	public static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		if (memSize <= 0)
			return 0.0;
		double multiplier = (fType != null && (fType == FType.FULL || fType == FType.BROADCAST))
				? Math.max(1, numWorkers)
				: 1.0;
		return computeNetworkCost(memSize * multiplier);
	}

	public static double computeRefedNetworkCost(double memSize, FType fType, int numWorkers) {
		return computeUploadNetworkCost(memSize, fType, numWorkers);
	}

	private static double getInjectedDefaultMemEstimatePerCell(Hop hop) {
		if (hop == null || hop.getValueType() == null) {
			return DEFAULT_MEM_ESTIMATE_PER_CELL;
		}

		ValueType valueType = hop.getValueType();
		switch (valueType) {
			case BOOLEAN:
				return OptimizerUtils.BOOLEAN_SIZE;
			case UINT4:
			case UINT8:
			case INT32:
			case HASH32:
				return OptimizerUtils.INT_SIZE;
			case INT64:
			case HASH64:
				return OptimizerUtils.DOUBLE_SIZE;
			case FP32:
				return DEFAULT_FP32_MEM_ESTIMATE_PER_CELL;
			case FP64:
				return OptimizerUtils.DOUBLE_SIZE;
			case CHARACTER:
				return OptimizerUtils.CHAR_SIZE;
			case STRING:
				return DEFAULT_STRING_MEM_ESTIMATE_PER_CELL;
			case UNKNOWN:
			default:
				return DEFAULT_MEM_ESTIMATE_PER_CELL;
		}
	}

	private static double getConfiguredDouble(String key, double fallback) {
		String value = System.getProperty(key);
		if (value == null || value.isEmpty())
			value = System.getenv(key);
		if (value == null || value.isEmpty())
			return fallback;
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}
}
 
