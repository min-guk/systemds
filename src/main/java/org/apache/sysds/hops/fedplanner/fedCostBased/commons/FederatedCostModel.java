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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public final class FederatedCostModel {
	private static final String ENV_MBS_MEMORY_BANDWIDTH = "SYSDS_FED_COST_MEM_BW";
	private static final String ENV_MBS_NETWORK_BANDWIDTH = "SYSDS_FED_COST_NET_BW";
	private static final String ENV_MBS_NETWORK_BANDWIDTH_C2W = "SYSDS_FED_COST_NET_BW_C2W";
	private static final String ENV_MBS_NETWORK_BANDWIDTH_W2C = "SYSDS_FED_COST_NET_BW_W2C";
	// Additional per-byte overhead for federated PUT/GET (serialization + deserialization + RPC/Netty framing).
	//
	// Model: t(bytes) ~= latency + bytes/net_bw + bytes/serdes_bw (+ optional control overhead).
	// Setting serdes_bw=0 disables this term (legacy behaviour).
	private static final String ENV_MBS_NETWORK_SERDES_BANDWIDTH = "SYSDS_FED_COST_NET_SERDES_BW";
	private static final String ENV_MBS_NETWORK_SERDES_BANDWIDTH_C2W = "SYSDS_FED_COST_NET_SERDES_BW_C2W";
	private static final String ENV_MBS_NETWORK_SERDES_BANDWIDTH_W2C = "SYSDS_FED_COST_NET_SERDES_BW_W2C";
	private static final String ENV_MBS_NETWORK_LATENCY = "SYSDS_FED_COST_NET_LATENCY";
	private static final String ENV_LOCAL_TO_FED_CTRL_OVERHEAD_MS = "SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS";
	private static final String ENV_UPLOAD_ESTIMATE_CLAMP_RATIO = "SYSDS_FED_COST_UPLOAD_MEM_CLAMP_RATIO";
	private static final String ENV_UNKNOWN_DIM_TRANSFER_FALLBACK_MB = "SYSDS_FED_COST_UNKNOWN_DIM_TRANSFER_MB";
	private static final String ENV_FLOPS_PER_SEC = "SYSDS_FED_COST_FLOPS";
	private static final String ENV_AGGBINARY_FLOPS_PER_SEC = "SYSDS_FED_COST_AGGBINARY_FLOPS";
	private static final double DEFAULT_MEM_ESTIMATE_PER_CELL = OptimizerUtils.DOUBLE_SIZE;
	private static final double DEFAULT_FP32_MEM_ESTIMATE_PER_CELL = 4.0;
	private static final double DEFAULT_STRING_MEM_ESTIMATE_PER_CELL = 100.0 * OptimizerUtils.CHAR_SIZE;

	// Default values are used as reasonable estimates since we only need to compare
	// relative costs between different federated plans.
	// Memory bandwidth for local computations (25 GB/s).
	private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
	// Network bandwidth for data transfers between federated sites (1 Gbps).
	private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
	// Additional per-byte overhead term for federated transfers (disabled by default).
	private static final double DEFAULT_MBS_NETWORK_SERDES_BANDWIDTH = 0.0;
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
	// Fallback transfer payload used when output dimensions remain unknown and no
	// reliable descendant size estimate is available. This value directly impacts
	// DP/MinST decisions that trade off local materialization vs. federated plans
	// in early planning phases (before recompile resolves dimensions).
	//
	// In practice (notably in sliceline), under-estimation here can cause DP to
	// over-prefer CP/LOUT materialization and later pay large local->FED forwarding
	// costs. Use a conservative default; it can still be overridden via
	// SYSDS_FED_COST_UNKNOWN_DIM_TRANSFER_MB.
	private static final double DEFAULT_UNKNOWN_DIM_TRANSFER_FALLBACK_MB = 256.0;
	// Compute throughput (FLOPs/s), consistent with CostEstimatorStaticRuntime defaults.
	private static final double DEFAULT_FLOPS_PER_SEC = 2d * 1024 * 1024 * 1024;
	// AggBinaryOp (notably ba+* / matrix multiplication) executes on optimized BLAS kernels.
	// The generic 2 GiFLOPs/s fallback dramatically over-prices CP execution for these ops in
	// multi-worker planning and can bias MinST/DP toward pathological FED/FOUT chains.
	// Keep the calibration shared so both planners see the same correction.
	private static final double DEFAULT_AGGBINARY_FLOPS_PER_SEC = 32d * 1000 * 1000 * 1000;
	// DML FunctionOp placeholders summarize whole callees. A pure output-size shell cost
	// under-estimates the work because the placeholder at least has to account for one
	// logical pass over distinct inputs plus result production. Keep this floor small and
	// shared so both DP and MinST see the same correction without planner-specific hacks.
	private static final double MIN_DML_FUNCTION_OP_COMPUTE_FLOPS_PER_CELL = 1.0;
	// In the single-worker case, a federated function placeholder can still pay
	// additional call-boundary control cost when we keep the callee federated.
	//
	// Important: this must remain a bounded boundary term, not a hard blocker.
	// The function body is planned separately and can legitimately make FED cheaper
	// than CP even with one worker (e.g., iterative federated matrix kernels).
	private static final double SINGLE_WORKER_FED_EXEC_PENALTY_FACTOR = 1.0;
	private static final double SINGLE_WORKER_CTRL_PENALTY_THRESHOLD_MS = 10.0;
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
	private static final double MBS_NETWORK_SERDES_BANDWIDTH = getConfiguredDouble(ENV_MBS_NETWORK_SERDES_BANDWIDTH,
			DEFAULT_MBS_NETWORK_SERDES_BANDWIDTH);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH_C2W = getConfiguredDouble(ENV_MBS_NETWORK_SERDES_BANDWIDTH_C2W,
			MBS_NETWORK_SERDES_BANDWIDTH);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH_W2C = getConfiguredDouble(ENV_MBS_NETWORK_SERDES_BANDWIDTH_W2C,
			MBS_NETWORK_SERDES_BANDWIDTH);
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
	private static final double AGGBINARY_FLOPS_PER_SEC = getConfiguredDouble(ENV_AGGBINARY_FLOPS_PER_SEC,
			Math.max(FLOPS_PER_SEC, DEFAULT_AGGBINARY_FLOPS_PER_SEC));

	private FederatedCostModel() {
		// utility class
	}

	/**
	 * Estimated per-operation coordination overhead for executing a federated instruction
	 * across multiple workers.
	 *
	 * <p>This helper is intentionally <b>control-plane only</b> (RPC framing / Netty bookkeeping).
	 * Boundary upload/download costs already account for network latency and payload transfer,
	 * so adding a full latency term here would double-count and over-penalize FED execution
	 * in iterative workloads (e.g., lm/pca under WAN profiles).</p>
	 *
	 * @param numWorkers number of federated workers participating in the operation
	 * @return estimated control-only coordination overhead in milliseconds
	 */
	public static double computeFedCoordinationCost(int numWorkers) {
		final int fanout = Math.max(1, numWorkers);
		final double ctrl = Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		return fanout * ctrl;
	}

	/**
	 * Some federated executions are effectively metadata propagation at the planner/runtime
	 * boundary and should not pay a full per-op FED coordination term.
	 *
	 * <p>In particular, transpose on a FULL/BROADCAST federated layout preserves the
	 * runtime mapping contract instead of initiating an ordinary worker RPC fanout. Charging
	 * the generic per-op coordination cost there can lock DP/MinST into local transient
	 * materialization even though the runtime keeps the federated path cheap.</p>
	 */
	public static double adjustFedCoordinationCost(Hop hop, FType logicalFType, double coordinationCost) {
		if (coordinationCost <= 0.0)
			return coordinationCost;
		return isMappingPreservingFederatedTranspose(hop, logicalFType) ? 0.0 : coordinationCost;
	}

	public static boolean isMappingPreservingFederatedTranspose(Hop hop, FType logicalFType) {
		if (!(hop instanceof ReorgOp))
			return false;
		if (((ReorgOp) hop).getOp() != ReOrgOp.TRANS)
			return false;
		return logicalFType == FType.FULL || logicalFType == FType.BROADCAST;
	}

	/**
	 * Additional penalty for single-worker federated execution in degenerate cases.
	 *
	 * <p>This targets function-placeholder plans where FED execution over one worker
	 * offers no data-parallel speedup, but the planner can still prefer FED because
	 * the placeholder hop itself has near-zero compute cost. The penalty is applied
	 * only when control-plane overhead is materially non-zero and either the hop has
	 * no immediate concrete federated matrix input or it is executed repeatedly.</p>
	 */
	public static double computeSingleWorkerFedExecPenalty(Hop hop, double execWeight, int numWorkers) {
		if (hop == null || numWorkers > 1)
			return 0.0;
		if (!(hop instanceof FunctionOp))
			return 0.0;
		FunctionOp functionOp = (FunctionOp) hop;
		if (functionOp.getFunctionType() != FunctionOp.FunctionType.DML)
			return 0.0;
		final double ctrlMs = Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		if (ctrlMs <= SINGLE_WORKER_CTRL_PENALTY_THRESHOLD_MS)
			return 0.0;

		final boolean hasConcreteFedMatrixInput = hasConcreteFederatedMatrixInput(functionOp);
		final double boundedExecWeight = Math.max(1.0, execWeight);
		if (hasConcreteFedMatrixInput && boundedExecWeight <= 1.0)
			return 0.0;

		// Model only the additional call-boundary control cost that is not already captured by
		// ordinary per-hop FED coordination. When a concrete federated matrix input already anchors
		// the call boundary, a one-shot call should not receive any extra penalty. Repeated calls
		// and fully local boundaries still pay a bounded overhead proportional to the number of
		// distinct materialized inputs that must participate in the call.
		final int boundaryInputs = Math.max(1, countDistinctFunctionBoundaryInputs(functionOp));
		final double repetitionFactor = hasConcreteFedMatrixInput
			? Math.max(0.0, boundedExecWeight - 1.0)
			: boundedExecWeight;
		return repetitionFactor * boundaryInputs * ctrlMs * SINGLE_WORKER_FED_EXEC_PENALTY_FACTOR;
	}

	private static boolean hasConcreteFederatedMatrixInput(FunctionOp hop) {
		if (hop == null || hop.getInput() == null)
			return false;
		Set<Long> seen = new HashSet<>();
		for (Hop inputHop : hop.getInput()) {
			if (inputHop == null || !seen.add(inputHop.getHopID()))
				continue;
			if (inputHop.getDataType() == null || !inputHop.getDataType().isMatrix())
				continue;
			if (inputHop.getForcedExecType() == ExecType.FED || inputHop.getFederatedOutput() == FederatedOutput.FOUT)
				return true;
			if (inputHop instanceof DataOp && ((DataOp) inputHop).getOp() == OpOpData.FEDERATED)
				return true;
		}
		return false;
	}

	private static int countDistinctFunctionBoundaryInputs(FunctionOp hop) {
		if (hop == null || hop.getInput() == null)
			return 0;
		Set<Long> seen = new HashSet<>();
		for (Hop inputHop : hop.getInput()) {
			if (inputHop == null)
				continue;
			if (inputHop.getDataType() != null && inputHop.getDataType().isScalar())
				continue;
			seen.add(inputHop.getHopID());
		}
		return seen.size();
	}

	public static double computeOpCost(Hop currentHop) {
		double inputMemEstimate = getEffectiveInputMemEstimate(currentHop);
		double outputMemEstimate = getEffectiveOutputMemEstimate(currentHop);
		double computeCost = ComputeCost.getHOPComputeCost(currentHop);
		if (isDmlFunctionOp(currentHop)) {
			computeCost = Math.max(computeCost,
				estimateDmlFunctionOpComputeFloor((FunctionOp) currentHop, inputMemEstimate, outputMemEstimate));
		}
		double computeTime = (computeCost / getComputeFlopsPerSec(currentHop)) * TO_MS;
		double inputAccessCost = computeMemoryAccessCost(inputMemEstimate);
		double outputAccessCost = computeMemoryAccessCost(outputMemEstimate);

		// Total cost assumes:
		// 1) Computation and input access can overlap (take max)
		// 2) Output access must wait for both (add)
		return Math.max(computeTime, inputAccessCost) + outputAccessCost;
	}

	private static double getComputeFlopsPerSec(Hop hop) {
		if (hop instanceof AggBinaryOp && hop.getDataType() != null && hop.getDataType().isMatrix())
			return AGGBINARY_FLOPS_PER_SEC;
		return FLOPS_PER_SEC;
	}

	private static boolean isDmlFunctionOp(Hop hop) {
		return hop instanceof FunctionOp
			&& ((FunctionOp) hop).getFunctionType() == FunctionOp.FunctionType.DML;
	}

	private static double estimateDmlFunctionOpComputeFloor(FunctionOp hop,
		double inputMemEstimate, double outputMemEstimate) {
		if (hop == null || hop.getFunctionType() != FunctionOp.FunctionType.DML)
			return 0.0;

		double logicalCells = 0.0;
		Set<Long> seenInputHops = new HashSet<>();
		if (hop.getInput() != null) {
			for (Hop inputHop : hop.getInput()) {
				if (inputHop == null || !seenInputHops.add(inputHop.getHopID()))
					continue;
				logicalCells += estimateLogicalCellCount(inputHop, getEffectiveOutputMemEstimate(inputHop));
			}
		}
		logicalCells += estimateLogicalCellCount(hop, outputMemEstimate);

		if (logicalCells <= 0.0 && inputMemEstimate > 0.0) {
			double perCell = Math.max(1.0, getInjectedDefaultMemEstimatePerCell(hop));
			logicalCells = inputMemEstimate / perCell;
		}

		return Math.max(0.0, logicalCells) * MIN_DML_FUNCTION_OP_COMPUTE_FLOPS_PER_CELL;
	}

	private static double estimateLogicalCellCount(Hop hop, double memEstimate) {
		if (hop == null)
			return 0.0;
		if (hop.getDataType() != null && hop.getDataType().isScalar())
			return 1.0;

		long rows = hop.getDim1();
		long cols = hop.getDim2();
		if (rows > 0 && cols > 0)
			return Math.max(1.0, rows * (double) cols);

		double perCell = Math.max(1.0, getInjectedDefaultMemEstimatePerCell(hop));
		if (memEstimate > 0.0)
			return Math.max(1.0, memEstimate / perCell);

		return 0.0;
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
		boolean useRawInputMemEstimate = inputMemEstimate > 0.0
				&& !isLikelyDefaultUnknownMemEstimate(inputMemEstimate);
		if (useRawInputMemEstimate) {
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

		if (inputMemEstimate > 0.0 && hasUnknownOutputDims(hop) && UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES > 0.0) {
			double fanInScale = Math.max(1, hop.getInput() == null ? 1 : hop.getInput().size());
			return Math.min(inputMemEstimate, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES * fanInScale);
		}

		return Math.max(0.0, hop.getInputMemEstimate(getInjectedDefaultMemEstimatePerCell(hop)));
	}

	public static double getEffectiveOutputMemEstimate(Hop hop) {
		if (hop == null) {
			return 0.0;
		}
		double multiReturnFunctionOutputMemEstimate = getMultiReturnFunctionOutputMemEstimate(hop);
		if (multiReturnFunctionOutputMemEstimate > 0.0)
			return multiReturnFunctionOutputMemEstimate;
		double outputMemEstimate = hop.getOutputMemEstimate();
		double transientReadSourceMemEstimate = getConcreteTransientReadSourceMemEstimate(hop);
		double directFederatedTransientReadFallback = transientReadSourceMemEstimate > 0.0
			? -1.0
			: getDirectFederatedTransientReadFallbackMemEstimate(hop);
		double sourceClampRatio = Math.max(1.0, UPLOAD_ESTIMATE_CLAMP_RATIO);
		if (transientReadSourceMemEstimate > 0.0
			&& (outputMemEstimate <= 0.0
				|| hasUnknownOutputDims(hop)
				|| isLikelyDefaultUnknownMemEstimate(outputMemEstimate)
				|| outputMemEstimate > transientReadSourceMemEstimate * sourceClampRatio)) {
			outputMemEstimate = transientReadSourceMemEstimate;
		}
		else if (directFederatedTransientReadFallback > 0.0
			&& (outputMemEstimate <= 0.0
				|| hasUnknownOutputDims(hop)
				|| isLikelyDefaultUnknownMemEstimate(outputMemEstimate)
				|| outputMemEstimate > directFederatedTransientReadFallback * sourceClampRatio)) {
			outputMemEstimate = directFederatedTransientReadFallback;
		}
		double elementwiseInputMemUpperBound = getElementwiseInputMemUpperBound(hop);
		if (elementwiseInputMemUpperBound > 0.0 && hasUnknownOutputDims(hop)) {
			if (outputMemEstimate <= 0.0
				|| isLikelyDefaultUnknownMemEstimate(outputMemEstimate)
				|| outputMemEstimate > elementwiseInputMemUpperBound) {
				outputMemEstimate = elementwiseInputMemUpperBound;
			}
		}
		if (outputMemEstimate <= 0.0 && hasUnknownOutputDims(hop)) {
			double indexingBound = getIndexingUploadBound(hop);
			if (indexingBound > 0.0)
				return indexingBound;
		}
		if (outputMemEstimate <= 0.0)
			outputMemEstimate = Math.max(0.0, hop.getOutputMemEstimate(getInjectedDefaultMemEstimatePerCell(hop)));
		if (outputMemEstimate <= 0.0)
			return outputMemEstimate;
		if (!hasUnknownOutputDims(hop))
			return outputMemEstimate;

		double inputMemEstimate = hop.getInputMemEstimate();
		if (inputMemEstimate <= 0.0)
			inputMemEstimate = getEffectiveInputMemEstimate(hop);
		return clampUnknownDimOutputMemEstimate(hop, outputMemEstimate, inputMemEstimate);
	}

	public static double getEffectiveTransientReadSourceMemEstimate(Hop transientReadHop, Hop sourceHop) {
		double readerMemEstimate = getEffectiveOutputMemEstimate(transientReadHop);
		if (!(transientReadHop instanceof DataOp)
				|| ((DataOp) transientReadHop).getOp() != OpOpData.TRANSIENTREAD
				|| sourceHop == null
				|| !FederatedPlannerUtils.isMultiReturnFunctionOutputHop(sourceHop)) {
			return readerMemEstimate;
		}
		// Prefer the transient-read's own estimate once it is concrete/reliable. The explicit
		// function-output source is needed only while the reader still carries unresolved or
		// sentinel-sized stats; otherwise, always forcing the source estimate can leak broader
		// function-boundary costs into unrelated transient-write output decisions (observed on
		// the PCA overwritten-X chain 224 -> 225(TWrite X) -> 74(TRead X) -> 75).
		if (readerMemEstimate > 0.0
			&& !hasUnknownOutputDims(transientReadHop)
			&& !isLikelyDefaultUnknownMemEstimate(readerMemEstimate)) {
			return readerMemEstimate;
		}
		double sourceMemEstimate = getEffectiveOutputMemEstimate(sourceHop);
		if (sourceMemEstimate > 0.0)
			return sourceMemEstimate;
		return readerMemEstimate;
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

		double rawOutputMemEstimate = hop.getOutputMemEstimate();
		double outputMemEstimate = getEffectiveOutputMemEstimate(hop);
		double inputMemEstimate = getEffectiveInputMemEstimate(hop);
		if (outputMemEstimate <= 0.0)
			outputMemEstimate = 0.0;
		if (inputMemEstimate <= 0.0)
			inputMemEstimate = 0.0;

		// If output dimensions are unknown, Hop.getOutputMemEstimate(double) falls back to
		// max(dim,1) which implicitly treats unknown axes as 1. This can massively under-estimate
		// CP->FOUT/local->FED payloads (e.g., 3000x? becomes ~3000x1), making CP/FOUT candidates
		// look nearly free and leading to pathological broadcast-heavy plans in iterative workloads
		// (notably kmeans initialization).
		//
		// Apply a conservative lower bound for unknown-dimension uploads only when the raw
		// output estimate is genuinely missing and we therefore fall back to
		// Hop.getOutputMemEstimate(double). That fallback uses max(dim,1) for unknown axes and
		// can under-estimate one-known-axis payloads by orders of magnitude (e.g., 3000x? ->
		// 3000x1). Do not re-inflate estimates that already came from the generic unknown-size
		// sentinel path and were subsequently clamped by descendant/input bounds.
		if (hasUnknownOutputDims(hop) && rawOutputMemEstimate <= 0.0) {
			double perCell = getInjectedDefaultMemEstimatePerCell(hop);
			long r = hop.getDim1();
			long c = hop.getDim2();
			double squareBound = 0.0;
			if (r > 1 && c <= 0)
				squareBound = r * (double) r * perCell;
			else if (c > 1 && r <= 0)
				squareBound = c * (double) c * perCell;
			double floor = (squareBound > 0.0) ? squareBound : UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES;
			if (UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES > 0.0 && squareBound > 0.0)
				floor = Math.min(squareBound, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES);
			// Only lift tiny estimates; keep existing large estimates (including sentinel unknown).
			if (floor > 0.0 && outputMemEstimate > 0.0 && outputMemEstimate < floor) {
				outputMemEstimate = floor;
			}
			else if (floor > 0.0 && outputMemEstimate <= 0.0) {
				outputMemEstimate = floor;
			}
		}

		if (outputMemEstimate <= 0.0)
			return Math.max(0.0, inputMemEstimate);
		if (inputMemEstimate <= 0.0)
			return outputMemEstimate;

		// Indexing-derived sizes often carry one unresolved axis before recompile.
		// Bound upload size with a square estimate on the known axis to avoid
		// pathological over-estimation (e.g., rightIndex into principal components).
		double indexingBound = getIndexingUploadBound(hop);
		boolean recoveredConcreteIndexingBound = indexingBound > 0.0;
		if (recoveredConcreteIndexingBound)
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
		// Unknown-dimension hops may still carry very large non-sentinel estimates (e.g., -1 axes
		// propagated through matmult/indexing chains). These values are frequently pessimistic by
		// multiple orders of magnitude before recompile resolves concrete shapes, and can dominate
		// planner decisions in favor of CP fallbacks. Cap unknown-dimension transfer payloads to the
		// same configured fallback envelope used above.
		if (hasUnknownOutputDims(hop) && UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES > 0.0) {
			outputMemEstimate = Math.min(outputMemEstimate, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES);
			double fanInScale = Math.max(1, hop.getInput() == null ? 1 : hop.getInput().size());
			inputMemEstimate = Math.min(inputMemEstimate, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES * fanInScale);
		}

		double clampRatio = Math.max(1.0, UPLOAD_ESTIMATE_CLAMP_RATIO);
		if (hasUnknownOutputDims(hop) && outputMemEstimate > inputMemEstimate * clampRatio) {
			if (recoveredConcreteIndexingBound && inputMemEstimate < outputMemEstimate)
				return outputMemEstimate;
			return inputMemEstimate;
		}
		return outputMemEstimate;
	}

	private static boolean isLikelyDefaultUnknownMemEstimate(double memEstimate) {
		if (memEstimate <= 0.0)
			return false;
		double lower = DEFAULT_UNKNOWN_DIM_MEM_SENTINEL_BYTES * (1.0 - UNKNOWN_DIM_MEM_SENTINEL_EPSILON);
		double upper = DEFAULT_UNKNOWN_DIM_MEM_SENTINEL_BYTES * (1.0 + UNKNOWN_DIM_MEM_SENTINEL_EPSILON);
		return memEstimate >= lower && memEstimate <= upper;
	}

	private static double getConcreteTransientReadSourceMemEstimate(Hop hop) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.TRANSIENTREAD)
			return -1.0;
		if (hop.getInput() == null || hop.getInput().isEmpty())
			return -1.0;

		double best = -1.0;
		for (Hop sourceHop : hop.getInput()) {
			if (!(sourceHop instanceof DataOp))
				continue;
			OpOpData sourceOp = ((DataOp) sourceHop).getOp();
			if (sourceOp != OpOpData.TRANSIENTWRITE
				&& sourceOp != OpOpData.TRANSIENTREAD
				&& sourceOp != OpOpData.FEDERATED
				&& sourceOp != OpOpData.FUNCTIONOUTPUT) {
				continue;
			}
			// Do not automatically size a generic TRANSIENTREAD from the raw FEDERATED source
			// envelope. The federated source often represents the full original dataset while
			// the local TRANSIENTREAD node still carries a narrower planner-visible payload
			// envelope; inheriting the full source size here can over-penalize direct FED-input
			// transient boundaries and cascade into DP local fallback on overwritten-X / loop
			// carried chains (observed on current PCA/logreg traces). Function-output-specific
			// propagation is still handled explicitly via getEffectiveTransientReadSourceMemEstimate.
			if (sourceOp == OpOpData.FEDERATED)
				continue;
			if (!dimsCompatible(hop, sourceHop))
				continue;
			if (hasUnknownOutputDims(sourceHop) && sourceOp != OpOpData.FEDERATED)
				continue;
			double sourceMemEstimate = getEffectiveOutputMemEstimate(sourceHop);
			if (sourceMemEstimate <= 0.0 || isLikelyDefaultUnknownMemEstimate(sourceMemEstimate))
				continue;
			best = Math.max(best, sourceMemEstimate);
		}
		return best;
	}

	private static double getDirectFederatedTransientReadFallbackMemEstimate(Hop hop) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.TRANSIENTREAD)
			return -1.0;
		if (!hasUnknownOutputDims(hop) || hop.getInput() == null || hop.getInput().isEmpty())
			return -1.0;

		boolean hasDirectFederatedSource = false;
		for (Hop sourceHop : hop.getInput()) {
			if (!(sourceHop instanceof DataOp))
				continue;
			if (((DataOp) sourceHop).getOp() != OpOpData.FEDERATED)
				continue;
			hasDirectFederatedSource = true;
			break;
		}
		if (!hasDirectFederatedSource)
			return -1.0;

		double fallbackMemEstimate = Math.max(0.0, hop.getOutputMemEstimate(getInjectedDefaultMemEstimatePerCell(hop)));
		if (fallbackMemEstimate <= 0.0 || isLikelyDefaultUnknownMemEstimate(fallbackMemEstimate))
			return -1.0;
		return fallbackMemEstimate;
	}

	private static double getElementwiseInputMemUpperBound(Hop hop) {
		if (!isElementwiseSizePreservingHop(hop) || hop.getInput() == null || hop.getInput().isEmpty())
			return -1.0;

		double best = -1.0;
		for (Hop inputHop : hop.getInput()) {
			if (inputHop == null || inputHop.getDataType() == null || !inputHop.getDataType().isMatrix())
				continue;
			if (!dimsCompatible(hop, inputHop))
				continue;
			double inputMemEstimate = getEffectiveOutputMemEstimate(inputHop);
			if (inputMemEstimate <= 0.0 || isLikelyDefaultUnknownMemEstimate(inputMemEstimate))
				continue;
			best = Math.max(best, inputMemEstimate);
		}
		return best;
	}

	private static boolean isElementwiseSizePreservingHop(Hop hop) {
		if (hop instanceof UnaryOp)
			return true;
		if (!(hop instanceof BinaryOp))
			return false;
		OpOp2 op = ((BinaryOp) hop).getOp();
		return op != OpOp2.CBIND && op != OpOp2.RBIND;
	}

	private static boolean dimsCompatible(Hop left, Hop right) {
		if (left == null || right == null)
			return false;
		boolean d1Known = left.getDim1() > 0 && right.getDim1() > 0;
		boolean d2Known = left.getDim2() > 0 && right.getDim2() > 0;
		if (d1Known && left.getDim1() != right.getDim1())
			return false;
		if (d2Known && left.getDim2() != right.getDim2())
			return false;
		return true;
	}

	private static double clampUnknownDimOutputMemEstimate(Hop hop, double outputMemEstimate, double inputMemEstimate) {
		if (hop == null || outputMemEstimate <= 0.0 || !hasUnknownOutputDims(hop))
			return Math.max(0.0, outputMemEstimate);

		double clampedOutputMemEstimate = Math.max(0.0, outputMemEstimate);
		double indexingBound = getIndexingUploadBound(hop);
		if (indexingBound > 0.0)
			clampedOutputMemEstimate = Math.min(clampedOutputMemEstimate, indexingBound);

		if (isLikelyDefaultUnknownMemEstimate(clampedOutputMemEstimate)) {
			Set<Long> visited = new HashSet<>();
			visited.add(hop.getHopID());
			double descendantKnownMem = getKnownDescendantOutputMemEstimate(hop, UNKNOWN_DIM_DESCENT_MAX_DEPTH, visited);
			if (descendantKnownMem > 0.0)
				clampedOutputMemEstimate = Math.min(clampedOutputMemEstimate, descendantKnownMem);
		}

		if (UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES > 0.0)
			clampedOutputMemEstimate = Math.min(clampedOutputMemEstimate, UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES);

		double clampRatio = Math.max(1.0, UPLOAD_ESTIMATE_CLAMP_RATIO);
		if (inputMemEstimate > 0.0)
			clampedOutputMemEstimate = Math.min(clampedOutputMemEstimate, inputMemEstimate * clampRatio);

		return clampedOutputMemEstimate;
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
		IndexingOp indexingHop = (IndexingOp) hop;
		long rows = resolveIndexingAxisSize(indexingHop, true);
		long cols = resolveIndexingAxisSize(indexingHop, false);
		if (rows > 0 && cols > 0)
			return OptimizerUtils.estimateSizeExactSparsity(rows, cols, 1.0, hop.getDataType());
		double perCell = getInjectedDefaultMemEstimatePerCell(hop);
		if (rows > 0 && cols <= 0)
			return rows * (double) rows * perCell;
		if (cols > 0 && rows <= 0)
			return cols * (double) cols * perCell;
		return 0.0;
	}

	private static long resolveIndexingAxisSize(IndexingOp hop, boolean rowAxis) {
		long declaredSize = rowAxis ? hop.getDim1() : hop.getDim2();
		if (declaredSize > 0)
			return declaredSize;
		if (rowAxis ? hop.isRowLowerEqualsUpper() : hop.isColLowerEqualsUpper())
			return 1;

		Hop input = hop.getInput().get(0);
		Hop lower = hop.getInput().get(rowAxis ? 1 : 3);
		Hop upper = hop.getInput().get(rowAxis ? 2 : 4);
		long literalRangeSize = resolveLiteralIndexRangeSize(lower, upper);
		if (literalRangeSize > 0)
			return literalRangeSize;
		if (HopRewriteUtils.isLiteralOfValue(lower, 1)) {
			long sizeExprValue = resolveSizeExpressionValue(upper, input, rowAxis);
			if (sizeExprValue > 0)
				return sizeExprValue;
		}
		return 0;
	}

	private static long resolveLiteralIndexRangeSize(Hop lower, Hop upper) {
		if (!(lower instanceof org.apache.sysds.hops.LiteralOp) || !(upper instanceof org.apache.sysds.hops.LiteralOp))
			return 0;
		long lowerVal = HopRewriteUtils.getIntValueSafe(lower);
		long upperVal = HopRewriteUtils.getIntValueSafe(upper);
		if (lowerVal <= 0 || upperVal < lowerVal)
			return 0;
		return upperVal - lowerVal + 1;
	}

	private static long resolveSizeExpressionValue(Hop sizeExpr, Hop input, boolean rowAxis) {
		if (sizeExpr == null || input == null)
			return 0;
		if (HopRewriteUtils.isSizeExpressionOf(sizeExpr, input, rowAxis)) {
			long axisSize = resolveAxisSizeFromHop(input, rowAxis, 4);
			if (axisSize > 0)
				return axisSize;
		}
		if (HopRewriteUtils.isUnary(sizeExpr, rowAxis ? OpOp1.NROW : OpOp1.NCOL)) {
			Hop sizeInput = sizeExpr.getInput().get(0);
			long axisSize = resolveAxisSizeFromHop(sizeInput, rowAxis, 4);
			if (axisSize > 0)
				return axisSize;
			if (HopRewriteUtils.isColumnRightIndexing(input) && sizeInput == input.getInput().get(0)) {
				long originalAxisSize = resolveAxisSizeFromHop(input.getInput().get(0), rowAxis, 4);
				if (originalAxisSize > 0)
					return originalAxisSize;
			}
		}
		return 0;
	}

	private static long resolveAxisSizeFromHop(Hop hop, boolean rowAxis, int remainingDepth) {
		if (hop == null || remainingDepth <= 0)
			return 0;
		long directSize = rowAxis ? hop.getDim1() : hop.getDim2();
		if (directSize > 0)
			return directSize;
		if (hop instanceof ReorgOp && ((ReorgOp) hop).getOp() == ReOrgOp.TRANS && hop.getInput() != null
			&& !hop.getInput().isEmpty()) {
			return resolveAxisSizeFromHop(hop.getInput().get(0), !rowAxis, remainingDepth - 1);
		}
		if (hop instanceof AggBinaryOp && hop.getInput() != null && hop.getInput().size() >= 2) {
			Hop left = hop.getInput().get(0);
			Hop right = hop.getInput().get(1);
			return rowAxis ? resolveAxisSizeFromHop(left, true, remainingDepth - 1)
				: resolveAxisSizeFromHop(right, false, remainingDepth - 1);
		}
		return 0;
	}

	public static double computeNetworkCost(double memSize) {
		return computeDirectionalNetworkCost(memSize, MBS_NETWORK_BANDWIDTH, MBS_NETWORK_SERDES_BANDWIDTH);
	}

	public static double computeDownloadNetworkCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		return computeDirectionalNetworkCost(memSize, MBS_NETWORK_BANDWIDTH_W2C, MBS_NETWORK_SERDES_BANDWIDTH_W2C);
	}

	public static double computeDownloadNetworkCost(double memSize, FType fType, int numWorkers) {
		double baseCost = computeDownloadNetworkCost(memSize);
		if (baseCost <= 0.0)
			return baseCost;
		int fanIn = estimateDownloadFanIn(fType, numWorkers);
		if (fanIn <= 1)
			return baseCost;
		double latencyPenaltyMs = (fanIn - 1) * MBS_NETWORK_LATENCY * TO_MS;
		double controlPenaltyMs = (fanIn - 1) * Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		return baseCost + latencyPenaltyMs + controlPenaltyMs;
	}

	public static boolean requiresExplicitMatrixBoundaryTransfer(Hop hop) {
		return hop != null
			&& hop.getDataType() != null
			&& hop.getDataType().isMatrix();
	}

	public static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		if (memSize <= 0)
			return 0.0;
		double multiplier = (fType != null && (fType == FType.FULL || fType == FType.BROADCAST))
				? Math.max(1, numWorkers)
				: 1.0;
		return computeDirectionalNetworkCost(memSize * multiplier, MBS_NETWORK_BANDWIDTH_C2W, MBS_NETWORK_SERDES_BANDWIDTH_C2W);
	}

	private static double computeDirectionalNetworkCost(double memSize, double bandwidthMBps, double serdesBwMBps) {
		double ctrlMs = Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		if (memSize <= 0)
			return MBS_NETWORK_LATENCY * TO_MS + ctrlMs;
		double effectiveBw = (bandwidthMBps > 0.0) ? bandwidthMBps : MBS_NETWORK_BANDWIDTH;
		double payloadMb = memSize / (1024 * 1024);
		double payloadSec = payloadMb / effectiveBw;
		double effectiveSerdesBw = (serdesBwMBps > 0.0) ? serdesBwMBps : 0.0;
		if (effectiveSerdesBw > 0.0) {
			payloadSec += payloadMb / effectiveSerdesBw;
		}
		return (MBS_NETWORK_LATENCY + payloadSec) * TO_MS + ctrlMs;
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

	private static int estimateDownloadFanIn(FType fType, int numWorkers) {
		int workers = Math.max(1, numWorkers);
		if (workers <= 1)
			return 1;
		if (fType == FType.FULL || fType == FType.BROADCAST)
			return 1;
		return workers;
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

	private static double getMultiReturnFunctionOutputMemEstimate(Hop hop) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FUNCTIONOUTPUT)
			return -1.0;
		FunctionOp functionOp = resolveMultiReturnBuiltinParent(hop);
		return functionOp != null ? functionOp.getMultiReturnBuiltinOutputMemEstimate(hop) : -1.0;
	}

	private static FunctionOp resolveMultiReturnBuiltinParent(Hop hop) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FUNCTIONOUTPUT)
			return null;
		if (hop.getInput() == null || hop.getInput().isEmpty() || hop.getInput().get(0) == null)
			return null;
		for (Hop parent : hop.getInput().get(0).getParent()) {
			if (!(parent instanceof FunctionOp))
				continue;
			FunctionOp functionOp = (FunctionOp) parent;
			if (functionOp.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
					|| functionOp.getOutputs() == null)
				continue;
			for (Hop outputHop : functionOp.getOutputs()) {
				if (outputHop == hop)
					return functionOp;
			}
		}
		return null;
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
 
