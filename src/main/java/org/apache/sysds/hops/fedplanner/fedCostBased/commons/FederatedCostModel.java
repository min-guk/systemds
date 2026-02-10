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

import java.util.HashSet;
import java.util.Set;

import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

public final class FederatedCostModel {
	private static final String ENV_MBS_MEMORY_BANDWIDTH = "SYSDS_FED_COST_MEM_BW";
	private static final String ENV_MBS_NETWORK_BANDWIDTH = "SYSDS_FED_COST_NET_BW";
	private static final String ENV_MBS_NETWORK_BANDWIDTH_C2W = "SYSDS_FED_COST_NET_BW_C2W";
	private static final String ENV_MBS_NETWORK_BANDWIDTH_W2C = "SYSDS_FED_COST_NET_BW_W2C";
	private static final String ENV_MBS_NETWORK_LATENCY = "SYSDS_FED_COST_NET_LATENCY";
	private static final String ENV_LOCAL_TO_FED_CTRL_OVERHEAD_MS = "SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS";
	private static final String ENV_UPLOAD_ESTIMATE_CLAMP_RATIO = "SYSDS_FED_COST_UPLOAD_MEM_CLAMP_RATIO";
	private static final String ENV_UNKNOWN_DIM_TRANSFER_FALLBACK_MB = "SYSDS_FED_COST_UNKNOWN_DIM_TRANSFER_MB";
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
	private static final double DEFAULT_LOCAL_TO_FED_CTRL_OVERHEAD_MS = 0.0;
	// Clamp suspiciously large upload-size estimates when output dimensions are unknown.
	// This avoids over-penalizing CP->FOUT candidates for shape-dependent operators
	// (e.g., rightIndex/matmult chains before recompile resolves dimensions).
	private static final double DEFAULT_UPLOAD_ESTIMATE_CLAMP_RATIO = 4.0;
	private static final double DEFAULT_UNKNOWN_DIM_MEM_SENTINEL_BYTES = 8d * 1024 * 1024 * 1024;
	private static final double UNKNOWN_DIM_MEM_SENTINEL_EPSILON = 0.01;
	private static final int UNKNOWN_DIM_DESCENT_MAX_DEPTH = 6;
	private static final double DEFAULT_UNKNOWN_DIM_TRANSFER_FALLBACK_MB = 64.0;
	// Compute throughput (FLOPs/s), consistent with CostEstimatorStaticRuntime defaults.
	private static final double DEFAULT_FLOPS_PER_SEC = 2d * 1024 * 1024 * 1024;
	// All costs are returned in milliseconds.
	private static final double TO_MS = 1000.0;
	private static final double MBS_MEMORY_BANDWIDTH = getConfiguredDouble(ENV_MBS_MEMORY_BANDWIDTH,
			DEFAULT_MBS_MEMORY_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH = getConfiguredDouble(ENV_MBS_NETWORK_BANDWIDTH,
			DEFAULT_MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH_C2W = getConfiguredDouble(ENV_MBS_NETWORK_BANDWIDTH_C2W,
			MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH_W2C = getConfiguredDouble(ENV_MBS_NETWORK_BANDWIDTH_W2C,
			MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_LATENCY = getConfiguredDouble(ENV_MBS_NETWORK_LATENCY,
			DEFAULT_MBS_NETWORK_LATENCY);
	private static final double LOCAL_TO_FED_CTRL_OVERHEAD_MS = getConfiguredDouble(ENV_LOCAL_TO_FED_CTRL_OVERHEAD_MS,
			DEFAULT_LOCAL_TO_FED_CTRL_OVERHEAD_MS);
	private static final double UPLOAD_ESTIMATE_CLAMP_RATIO = getConfiguredDouble(ENV_UPLOAD_ESTIMATE_CLAMP_RATIO,
			DEFAULT_UPLOAD_ESTIMATE_CLAMP_RATIO);
	private static final double UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES =
		Math.max(1.0, getConfiguredDouble(ENV_UNKNOWN_DIM_TRANSFER_FALLBACK_MB,
			DEFAULT_UNKNOWN_DIM_TRANSFER_FALLBACK_MB) * 1024 * 1024);
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

	/**
	 * Returns an upload-size estimate for CP->FOUT/local->FED transfers.
	 *
	 * <p>When output dimensions are unresolved at planning time, raw output-memory
	 * estimates can be orders of magnitude larger than the true runtime payload.
	 * For these cases, clamp uploads to input-memory scale to avoid systematic
	 * over-penalization of CP->FOUT candidates.</p>
	 */
	public static double getEffectiveUploadMemEstimate(Hop hop) {
		if (hop == null)
			return 0.0;

		double outputMemEstimate = getEffectiveOutputMemEstimate(hop);
		double inputMemEstimate = getEffectiveInputMemEstimate(hop);
		if (outputMemEstimate <= 0.0)
			return Math.max(0.0, inputMemEstimate);
		if (inputMemEstimate <= 0.0)
			return outputMemEstimate;

		// Indexing-derived sizes often carry one unresolved axis before recompile.
		// Bound upload size with a square estimate on the known axis to avoid
		// pathological over-estimation (e.g., rightIndex into principal components).
		double indexingBound = getIndexingUploadBound(hop);
		if (indexingBound > 0.0)
			outputMemEstimate = Math.min(outputMemEstimate, indexingBound);

		if (hasUnknownOutputDims(hop) && isLikelyDefaultUnknownMemEstimate(outputMemEstimate)) {
			Set<Long> visited = new HashSet<>();
			visited.add(hop.getHopID());
			double descendantKnownMem = getKnownDescendantOutputMemEstimate(hop, UNKNOWN_DIM_DESCENT_MAX_DEPTH, visited);
			if (descendantKnownMem > 0.0) {
				outputMemEstimate = Math.min(outputMemEstimate, descendantKnownMem);
				if (isLikelyDefaultUnknownMemEstimate(inputMemEstimate)) {
					double fanInScale = Math.max(1, hop.getInput() == null ? 1 : hop.getInput().size());
					inputMemEstimate = Math.min(inputMemEstimate, descendantKnownMem * fanInScale);
				}
			}
		}
		if (hasUnknownOutputDims(hop)
			&& isLikelyDefaultUnknownMemEstimate(outputMemEstimate)
			&& isLikelyDefaultUnknownMemEstimate(inputMemEstimate)) {
			outputMemEstimate = Math.min(outputMemEstimate, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES);
			double fanInScale = Math.max(1, hop.getInput() == null ? 1 : hop.getInput().size());
			inputMemEstimate = Math.min(inputMemEstimate, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES * fanInScale);
		}

		double clampRatio = Math.max(1.0, UPLOAD_ESTIMATE_CLAMP_RATIO);
		if (hasUnknownOutputDims(hop) && outputMemEstimate > inputMemEstimate * clampRatio)
			return inputMemEstimate;
		return outputMemEstimate;
	}

	private static boolean isLikelyDefaultUnknownMemEstimate(double memEstimate) {
		if (memEstimate <= 0.0)
			return false;
		double lower = DEFAULT_UNKNOWN_DIM_MEM_SENTINEL_BYTES * (1.0 - UNKNOWN_DIM_MEM_SENTINEL_EPSILON);
		double upper = DEFAULT_UNKNOWN_DIM_MEM_SENTINEL_BYTES * (1.0 + UNKNOWN_DIM_MEM_SENTINEL_EPSILON);
		return memEstimate >= lower && memEstimate <= upper;
	}

	private static double getKnownDescendantOutputMemEstimate(Hop hop, int remainingDepth, Set<Long> visited) {
		if (hop == null || remainingDepth <= 0 || hop.getInput() == null || hop.getInput().isEmpty())
			return 0.0;
		double best = 0.0;
		for (Hop input : hop.getInput()) {
			if (input == null || !visited.add(input.getHopID()))
				continue;
			double inputMem = getEffectiveOutputMemEstimate(input);
			if (inputMem > 0.0 && !isLikelyDefaultUnknownMemEstimate(inputMem))
				best = Math.max(best, inputMem);
			best = Math.max(best,
				getKnownDescendantOutputMemEstimate(input, remainingDepth - 1, visited));
		}
		return best;
	}

	private static boolean hasUnknownOutputDims(Hop hop) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		return !hop.dimsKnown() || hop.getDim1() <= 0 || hop.getDim2() <= 0;
	}

	private static double getIndexingUploadBound(Hop hop) {
		if (!(hop instanceof IndexingOp) || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return 0.0;
		long rows = hop.getDim1();
		long cols = hop.getDim2();
		double perCell = getInjectedDefaultMemEstimatePerCell(hop);
		if (rows > 0 && cols <= 0)
			return rows * (double) rows * perCell;
		if (cols > 0 && rows <= 0)
			return cols * (double) cols * perCell;
		return 0.0;
	}

	public static double computeNetworkCost(double memSize) {
		return computeDirectionalNetworkCost(memSize, MBS_NETWORK_BANDWIDTH);
	}

	public static double computeDownloadNetworkCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		return computeDirectionalNetworkCost(memSize, MBS_NETWORK_BANDWIDTH_W2C);
	}

	public static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		if (memSize <= 0)
			return 0.0;
		double multiplier = (fType != null && (fType == FType.FULL || fType == FType.BROADCAST))
				? Math.max(1, numWorkers)
				: 1.0;
		return computeDirectionalNetworkCost(memSize * multiplier, MBS_NETWORK_BANDWIDTH_C2W);
	}

	private static double computeDirectionalNetworkCost(double memSize, double bandwidthMBps) {
		if (memSize <= 0)
			return 0.0;
		double effectiveBw = (bandwidthMBps > 0.0) ? bandwidthMBps : MBS_NETWORK_BANDWIDTH;
		return (MBS_NETWORK_LATENCY + (memSize / (1024 * 1024) / effectiveBw)) * TO_MS;
	}

	/**
	 * Additional latency-only penalty for local-to-federated forwarding
	 * (CP/LOUT -> FOUT -> FED).
	 *
	 * <p>The base upload model accounts for payload size and a single transfer latency.
	 * For forwarding into federated execution, data is typically sent to multiple
	 * workers. This penalty captures the extra fan-out control latency
	 * ((numWorkers - 1) * latency) without changing the shared bandwidth model.
	 */
	public static double computeLocalToFedForwardingPenalty(FType fType, int numWorkers) {
		if (fType == null)
			return 0.0;
		int fanout = Math.max(1, numWorkers);
		if (fanout <= 1)
			return 0.0;
		double latencyPenaltyMs = (fanout - 1) * MBS_NETWORK_LATENCY * TO_MS;
		double controlPenaltyMs = fanout * Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		return latencyPenaltyMs + controlPenaltyMs;
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
 
