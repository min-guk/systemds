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
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.FederatedPlannerConfiguration;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

public final class FederatedCostModel {
	public static final class MixedFedLocalCost {
		private static final MixedFedLocalCost NONE =
			new MixedFedLocalCost("none", 0.0, 0.0, 0.0, 0.0);

		private final String label;
		private final double inputPreparationCost;
		private final double partialResultDownloadCost;
		private final double coordinatorLocalCost;
		private final double federatedComputeFloor;

		private MixedFedLocalCost(String label, double inputPreparationCost,
				double partialResultDownloadCost, double coordinatorLocalCost,
				double federatedComputeFloor) {
			this.label = label;
			this.inputPreparationCost = sanitizeCost(inputPreparationCost);
			this.partialResultDownloadCost = sanitizeCost(partialResultDownloadCost);
			this.coordinatorLocalCost = sanitizeCost(coordinatorLocalCost);
			this.federatedComputeFloor = sanitizeCost(federatedComputeFloor);
		}

		public static MixedFedLocalCost none() {
			return NONE;
		}

		public String getLabel() {
			return label;
		}

		public double getInputPreparationCost() {
			return inputPreparationCost;
		}

		public double getPartialResultDownloadCost() {
			return partialResultDownloadCost;
		}

		public double getCoordinatorLocalCost() {
			return coordinatorLocalCost;
		}

		public double getFederatedComputeFloor() {
			return federatedComputeFloor;
		}

		public double getCoordinatorPhaseCost() {
			return partialResultDownloadCost + coordinatorLocalCost;
		}

		public boolean hasCoordinatorPhase() {
			return getCoordinatorPhaseCost() > 0.0;
		}

		public boolean hasInputPreparation() {
			return inputPreparationCost > 0.0;
		}

		public boolean hasFederatedComputeFloor() {
			return federatedComputeFloor > 0.0;
		}
	}

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
	private static final double MBS_MEMORY_BANDWIDTH = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_MEMORY_BANDWIDTH,
			DEFAULT_MBS_MEMORY_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_BANDWIDTH,
			DEFAULT_MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH_C2W = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_BANDWIDTH_C2W,
			MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH_W2C = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_BANDWIDTH_W2C,
			MBS_NETWORK_BANDWIDTH);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_SERDES_BANDWIDTH,
			DEFAULT_MBS_NETWORK_SERDES_BANDWIDTH);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH_C2W = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_SERDES_BANDWIDTH_C2W,
			MBS_NETWORK_SERDES_BANDWIDTH);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH_W2C = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_SERDES_BANDWIDTH_W2C,
			MBS_NETWORK_SERDES_BANDWIDTH);
	private static final double MBS_NETWORK_LATENCY = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_NETWORK_LATENCY,
			DEFAULT_MBS_NETWORK_LATENCY);
	private static final double LOCAL_TO_FED_CTRL_OVERHEAD_MS = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_LOCAL_TO_FED_CTRL_OVERHEAD_MS,
			DEFAULT_LOCAL_TO_FED_CTRL_OVERHEAD_MS);
	private static final double UPLOAD_ESTIMATE_CLAMP_RATIO = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_UPLOAD_ESTIMATE_CLAMP_RATIO,
			DEFAULT_UPLOAD_ESTIMATE_CLAMP_RATIO);
	private static final double UNKNOWN_DIM_TRANSFER_FALLBACK_BYTES =
		Math.max(1.0, FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_UNKNOWN_DIM_TRANSFER_FALLBACK_MB,
			DEFAULT_UNKNOWN_DIM_TRANSFER_FALLBACK_MB) * 1024 * 1024);
	private static final double FLOPS_PER_SEC = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_FLOPS_PER_SEC,
			DEFAULT_FLOPS_PER_SEC);
	private static final double AGGBINARY_FLOPS_PER_SEC = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_AGGBINARY_FLOPS_PER_SEC,
			Math.max(FLOPS_PER_SEC, DEFAULT_AGGBINARY_FLOPS_PER_SEC));

	private FederatedCostModel() {
		// utility class
	}

	private static double sanitizeCost(double cost) {
		return Double.isFinite(cost) && cost > 0.0 ? cost : 0.0;
	}

	/**
	 * Estimated per-operation coordination overhead for executing a federated instruction
	 * across multiple workers.
	 *
	 * <p>This helper is intentionally <b>control-plane only</b> (RPC framing / Netty bookkeeping).
	 * The configured value comes from the measured local-to-federated dispatch/control path for
	 * one logical federated instruction, so ordinary per-op coordination must not multiply it by
	 * worker fanout again. Boundary upload/download helpers separately model payload fan-in/fan-out
	 * and any extra worker-side transfer latency.</p>
	 *
	 * @param numWorkers number of federated workers participating in the operation
	 * @return estimated control-only coordination overhead in milliseconds
	 */
	public static double computeFedCoordinationCost(int numWorkers) {
		final double ctrl = Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		return ctrl;
	}

	/**
	 * Some federated executions are effectively metadata propagation at the planner/runtime
	 * boundary and should not pay a full per-op FED coordination term.
	 *
	 * <p>In particular, transpose on a FULL federated layout preserves the runtime mapping
	 * contract instead of initiating an ordinary worker RPC fanout. A BROADCAST output type
	 * alone is insufficient proof: the input can still be partitioned and require a real
	 * federated transpose instruction.</p>
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
		return logicalFType == FType.FULL;
	}

	public static boolean requiresFederatedWdivmmLocalAggregation(Hop hop, FType logicalFType) {
		if (!(hop instanceof QuaternaryOp))
			return false;
		QuaternaryOp quaternaryOp = (QuaternaryOp) hop;
		if (quaternaryOp.getOp() != OpOp4.WDIVMM)
			return false;

		int baseType = quaternaryOp.getBaseType();
		boolean isLeftWdivmm = baseType == 1 || baseType == 3;
		boolean isRightWdivmm = baseType == 2 || baseType == 4;
		return (isLeftWdivmm && logicalFType == FType.ROW)
			|| (isRightWdivmm && logicalFType == FType.COL);
	}

	/**
	 * Runtime-aware FED compute cost for the legal WDivMM local-aggregation path.
	 *
	 * <p>The generic DP model divides FED self cost by worker count because most
	 * partition-preserving FED instructions produce partitioned outputs and their
	 * compute/memory work scales with the selected input partition.  Left WDivMM over
	 * ROW X and right WDivMM over COL X are different: each worker returns a full
	 * partial result through {@code GET_VAR}, and the coordinator waits for and
	 * aggregates these full partials.  Applying the generic linear speedup to the
	 * whole HOP self cost therefore under-prices this runtime path, especially in
	 * looped ALS factor updates.  Keep the legal FED candidate open, but compare it
	 * with the unscaled self-cost floor used by the coordinator alternative.</p>
	 */
	public static double adjustFederatedComputeCostForWdivmmLocalAggregation(Hop hop,
			FType logicalFType, double baseSelfCost, double defaultFederatedComputeCost) {
		if (!requiresFederatedWdivmmLocalAggregation(hop, logicalFType))
			return defaultFederatedComputeCost;
		return Math.max(defaultFederatedComputeCost, baseSelfCost);
	}

	/**
	 * FED self-cost scaling predicate shared by DP and MinST.
	 *
	 * <p>The generic static model divides a hop's self compute cost by worker count
	 * for FED execution.  That is reasonable for arithmetic-heavy, partition-preserving
	 * worker computation.  It is not a valid speedup assumption for operations where
	 * runtime time is dominated by per-worker control, slicing/reindexing, representation
	 * changes, or redundant fully-broadcast inputs.  Keep the FED candidate open, but
	 * compare it against the unscaled self-cost floor for these runtime families.</p>
	 */
	public static boolean shouldUseUnscaledFederatedComputeCost(Hop hop, boolean broadcastOnlyFedCompute) {
		if (broadcastOnlyFedCompute)
			return true;
		if (hop instanceof BinaryOp)
			return true;
		if (isElementwiseTernaryOp(hop))
			return true;
		if (hop instanceof NaryOp && ((NaryOp) hop).getOp().isCellOp())
			return true;
		if (hop instanceof IndexingOp)
			return true;
		return hop instanceof ReorgOp && ((ReorgOp) hop).getOp() == ReOrgOp.TRANS;
	}

	private static boolean isElementwiseTernaryOp(Hop hop) {
		if (!(hop instanceof TernaryOp))
			return false;
		OpOp3 op = ((TernaryOp) hop).getOp();
		return op == OpOp3.PLUS_MULT
			|| op == OpOp3.MINUS_MULT
			|| op == OpOp3.IFELSE
			|| op == OpOp3.MAP;
	}

	public static double computeFederatedComputeCost(Hop hop, double baseSelfCost,
			int numWorkers, boolean broadcastOnlyFedCompute) {
		if (shouldUseUnscaledFederatedComputeCost(hop, broadcastOnlyFedCompute))
			return baseSelfCost;
		return baseSelfCost / Math.max(1, numWorkers);
	}

	/**
	 * Runtime-stage cost for native FED aggregate-unary outputs whose result keeps
	 * the input federation layout.
	 *
	 * <p>{@code AggregateUnaryFEDInstruction.processFederatedOutput} has two
	 * different runtime paths. Opposite-axis ROW/COL aggregates collect worker
	 * partials through {@code GET_VAR} and consolidate locally. Axis-preserving
	 * ROW/COL aggregates, and replicated FULL/BROADCAST inputs, keep the reduced
	 * matrix federated and only derive a new mapping. The planner must therefore
	 * not price these native FED/FOUT candidates as identical to a CP aggregate
	 * over a local full-input materialization.</p>
	 *
	 * <p>This helper does not close any candidate; it only gives DP and MinST a
	 * shared reduced-output cost for the runtime-supported native FED output path.
	 * Scalar aggregates are excluded because runtime cannot represent scalar
	 * outputs as federated variables.</p>
	 */
	public static double computeNativeFederatedAggregateUnaryCost(Hop hop,
			FType logicalFType, double defaultFederatedComputeCost) {
		if (!isNativeFederatedAggregateUnaryOutput(hop, logicalFType))
			return defaultFederatedComputeCost;

		AggUnaryOp aggregateUnary = (AggUnaryOp) hop;
		double outputMemEstimate = estimateAggregateUnaryResultMemEstimate(aggregateUnary,
			getEffectiveOutputMemEstimate(hop));
		double outputCells = estimateAggregateUnaryResultCellCount(aggregateUnary, outputMemEstimate);
		double outputComputeCost = outputCells > 0.0
				? (outputCells / getComputeFlopsPerSec(hop)) * TO_MS
				: 0.0;
		double outputAccessCost = computeMemoryAccessCost(outputMemEstimate);
		double reducedOutputCost = Math.max(outputComputeCost, outputAccessCost) + outputAccessCost;
		if (reducedOutputCost <= 0.0)
			return defaultFederatedComputeCost;
		return Math.min(defaultFederatedComputeCost, reducedOutputCost);
	}

	public static boolean isNativeFederatedAggregateUnaryOutput(Hop hop, FType logicalFType) {
		if (!(hop instanceof AggUnaryOp))
			return false;
		if (hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		if (logicalFType == FType.FULL || logicalFType == FType.BROADCAST)
			return true;
		AggUnaryOp aggregateUnary = (AggUnaryOp) hop;
		if (aggregateUnary.getDirection() == null)
			return false;
		return (logicalFType == FType.ROW && aggregateUnary.getDirection().isRow())
			|| (logicalFType == FType.COL && aggregateUnary.getDirection().isCol());
	}

	/**
	 * Runtime-stage result fan-in cost for native {@code AggregateUnaryFEDInstruction}
	 * plans that produce a local result ({@code FED/LOUT}).
	 *
	 * <p>The generic FED/LOUT boundary model describes an explicit materialization of
	 * a federated matrix at the coordinator and includes an extra worker fan-in
	 * control/latency term. Native aggregate-unary LOUT is different: the reduced
	 * result is returned as part of the federated aggregate instruction response.
	 * The per-instruction control path is already represented by
	 * {@link #computeFedCoordinationCost(int)}, so this helper charges only the
	 * reduced result payload needed by the aggregate semantics. This keeps all
	 * candidates open while avoiding a double-counted matrix-boundary download.</p>
	 */
	public static double computeNativeFederatedAggregateUnaryLoutResultCost(Hop hop,
			FType logicalFType, double outputMemEstimate, int numWorkers,
			double genericResultDownloadCost) {
		if (!(hop instanceof AggUnaryOp))
			return genericResultDownloadCost;
		AggUnaryOp aggregateUnary = (AggUnaryOp) hop;
		double resultMemEstimate = estimateAggregateUnaryResultMemEstimate(
			aggregateUnary, outputMemEstimate);
		if (resultMemEstimate <= 0.0)
			resultMemEstimate = getInjectedDefaultMemEstimatePerCell(hop);

		double reducedResultPayloadCost = computeAggregateUnaryPartialResultDownloadCost(
			aggregateUnary, logicalFType, resultMemEstimate, numWorkers);
		return reducedResultPayloadCost > 0.0
			? reducedResultPayloadCost
			: genericResultDownloadCost;
	}

	/**
	 * Runtime-stage result cost for native {@code AggregateBinaryFEDInstruction}
	 * plans that produce a local matrix result.
	 *
	 * <p>The worker compute, result GET, and cleanup requests form one logical FED
	 * instruction batch. The FED unary already owns the first fixed instruction
	 * control/round-trip term, so this boundary adds the result payload and only
	 * the extra fan-in fixed stages beyond the first worker. This mirrors the
	 * aggregate-unary result contract and avoids charging the first fixed
	 * latency/control stage twice.</p>
	 */
	public static double computeNativeFederatedAggBinaryLoutResultCost(Hop hop,
			FType logicalFType, double outputMemEstimate, int numWorkers,
			double genericResultDownloadCost) {
		if (!(hop instanceof AggBinaryOp) || !((AggBinaryOp) hop).isMatrixMultiply())
			return genericResultDownloadCost;
		double resultMemEstimate = outputMemEstimate > 0.0
			? outputMemEstimate : getEffectiveOutputMemEstimate(hop);
		if (resultMemEstimate <= 0.0)
			return genericResultDownloadCost;
		int fanIn = estimateDownloadFanIn(logicalFType, numWorkers);
		double resultCost = computeInBandWorkerResultDownloadCost(resultMemEstimate, fanIn, false);
		return resultCost > 0.0 ? resultCost : genericResultDownloadCost;
	}

	private static double estimateNativeAggregateUnaryPayloadFanIn(AggUnaryOp aggregate,
			FType logicalFType, int numWorkers) {
		if (aggregate == null || logicalFType == FType.FULL || logicalFType == FType.BROADCAST)
			return 1.0;
		Direction direction = aggregate.getDirection();
		if (direction == null)
			return Math.max(1, numWorkers);
		if ((logicalFType == FType.ROW && direction.isRow())
			|| (logicalFType == FType.COL && direction.isCol())) {
			return 1.0;
		}
		return Math.max(1, numWorkers);
	}

	/**
	 * Runtime-stage cost for native FED indexing/slicing.
	 *
	 * <p>{@code rightIndex} in native FED execution slices the worker-resident
	 * federated object.  It is not the same compute stage as a CP rightIndex over a
	 * fully materialized local input.  The generic unscaled FED floor remains useful
	 * to avoid giving slicing a blanket worker-count speedup, but the arithmetic/
	 * memory term itself should be bounded by the selected slice/output payload. The
	 * separate control-path model still charges fanout and loop multiplicity, so LAN
	 * can keep cheap native {@code fed_rightIndex} while WAN/high-control cases can
	 * still choose CP/LOUT by cost.</p>
	 *
	 * <p>This helper keeps all candidates open and is shared by DP and MinST. It is
	 * based only on operation semantics and static size estimates, not workload,
	 * worker-count, row-id, or hop-id rules.</p>
	 */
	public static double computeNativeFederatedIndexingCost(Hop hop,
			FType logicalFType, double defaultFederatedComputeCost) {
		if (!(hop instanceof IndexingOp))
			return defaultFederatedComputeCost;
		if (hop.getDataType() == null || !hop.getDataType().isMatrix())
			return defaultFederatedComputeCost;
		if (logicalFType == null)
			return defaultFederatedComputeCost;

		double sliceCost = computeIndexingSlicePayloadCost(hop);
		if (sliceCost <= 0.0)
			return defaultFederatedComputeCost;
		return Math.min(defaultFederatedComputeCost, sliceCost);
	}

	/**
	 * Runtime-stage cost for CP/local indexing/slicing.
	 *
	 * <p>Once a federated input is materialized locally, a CP {@code rightIndex}
	 * does not repeatedly scan the full source matrix for every slice.  Its local
	 * arithmetic/memory term is bounded by the selected slice/output payload; the
	 * one-time FOUT-to-local materialization remains modeled by the planner's
	 * boundary/result edges.  This mirrors the native FED indexing helper without
	 * closing any FED candidate, so DP and MinST compare the same staged operation
	 * semantics on both sides.</p>
	 */
	public static double computeLocalIndexingCostWithFallback(Hop hop, double defaultLocalCost) {
		if (!(hop instanceof IndexingOp))
			return defaultLocalCost;
		if (hop.getDataType() == null || !hop.getDataType().isMatrix())
			return defaultLocalCost;
		double sliceCost = computeIndexingSlicePayloadCost(hop);
		if (sliceCost <= 0.0)
			return defaultLocalCost;
		if (defaultLocalCost <= 0.0)
			return sliceCost;
		return Math.min(defaultLocalCost, sliceCost);
	}

	private static double computeIndexingSlicePayloadCost(Hop hop) {
		double sliceMemEstimate = getEffectiveOutputMemEstimate(hop);
		double indexingBound = getIndexingUploadBound(hop);
		if (indexingBound > 0.0)
			sliceMemEstimate = sliceMemEstimate > 0.0
				? Math.min(sliceMemEstimate, indexingBound)
				: indexingBound;
		if (sliceMemEstimate <= 0.0)
			return 0.0;

		double outputCells = estimateLogicalCellCount(hop, sliceMemEstimate);
		double outputComputeCost = outputCells > 0.0
				? (outputCells / getComputeFlopsPerSec(hop)) * TO_MS
				: 0.0;
		double outputAccessCost = computeMemoryAccessCost(sliceMemEstimate);
		return Math.max(outputComputeCost, outputAccessCost) + outputAccessCost;
	}

	/**
	 * Control-path latency floor for FED instructions whose runtime is dominated by
	 * worker fanout/scheduling instead of arithmetic-heavy partitioned compute.
	 *
	 * <p>The ordinary FED compute model can legitimately divide arithmetic-heavy,
	 * partition-preserving operators by worker count.  For slicing, transpose, cell
	 * operations, and fully-broadcast-only FED compute, that linear speedup is not
	 * enough: each logical FED instruction still has to be dispatched to the worker
	 * set even when the payload is tiny.  Runtime traces for these families show the
	 * missing term as repeated small {@code fed_rightIndex}, {@code fed_r'}, and
	 * cell-op instructions.  Keep the candidates open.  When an explicitly calibrated
	 * local-to-FED control cost is configured, the generic coordination term already
	 * accounts for one logical instruction dispatch; native FED indexing still needs
	 * the remaining worker fanout because a slice request is issued against each
	 * participating federated partition.</p>
	 */
	public static double computeControlDominatedFederatedInstructionCost(Hop hop,
			FType logicalFType, double execWeight, int numWorkers, boolean broadcastOnlyFedCompute) {
		if (hop == null || hop instanceof DataOp)
			return 0.0;
		if (!shouldUseUnscaledFederatedComputeCost(hop, broadcastOnlyFedCompute))
			return 0.0;
		if (isMappingPreservingFederatedTranspose(hop, logicalFType))
			return 0.0;
		double fanout = Math.max(1, numWorkers);
		double boundedWeight = Math.max(1.0, execWeight);
		if (LOCAL_TO_FED_CTRL_OVERHEAD_MS > 0.0)
			return hop instanceof IndexingOp
				? boundedWeight * Math.max(0, fanout - 1) * LOCAL_TO_FED_CTRL_OVERHEAD_MS
				: 0.0;
		return boundedWeight * fanout * MBS_NETWORK_LATENCY * TO_MS;
	}

	/**
	 * Shared runtime-stage model for FED instructions that do more than ordinary
	 * partition-preserving worker compute.
	 *
	 * <p>Some FED instructions execute a worker-side request and then perform a
	 * coordinator/local phase such as {@code GET_VAR} fan-in plus final aggregation
	 * or input {@code broadcastSliced}.  Those stages must be estimated explicitly
	 * instead of being folded into a generic {@code selfCost / workers} term.</p>
	 */
	public static MixedFedLocalCost computeMixedFedLocalCost(Hop hop, List<Hop> inputHops,
			List<FType> inputFTypes, FType logicalFType, double baseSelfCost,
			double outputMemEstimate, int numWorkers) {
		if (requiresFederatedAggUnaryLocalAggregation(hop)) {
			return computeAggregateUnaryLocalAggregationCost("agg-unary-local-aggregation",
				(AggUnaryOp) hop, logicalFType, outputMemEstimate, numWorkers, 0.0);
		}
		double wdivmmInputPreparationCost =
			computeWdivmmInputPreparationCost(hop, inputHops, inputFTypes, numWorkers);
		if (requiresFederatedWdivmmLocalAggregation(hop, logicalFType)) {
			return computePartialAggregationCost("wdivmm-local-aggregation",
				hop, outputMemEstimate, numWorkers, wdivmmInputPreparationCost, baseSelfCost, false);
		}
		if (wdivmmInputPreparationCost > 0.0) {
			double outputStageCost =
				computeWdivmmNativeOutputStageCost(hop, logicalFType, outputMemEstimate, numWorkers);
			return new MixedFedLocalCost("wdivmm-input-preparation",
				wdivmmInputPreparationCost + outputStageCost, 0.0, 0.0, baseSelfCost);
		}
		if (requiresFederatedAggBinaryRowLeftInputPreparation(hop, inputFTypes)) {
			double inputPreparationCost =
				computeAggBinaryRowLeftInputPreparationCost(hop, inputHops, inputFTypes, numWorkers);
			return new MixedFedLocalCost("aggbinary-rowleft-input-prep",
				inputPreparationCost, 0.0, 0.0, 0.0);
		}
		if (requiresFederatedAggBinaryAddAggregation(hop, inputFTypes)) {
			double inputPreparationCost =
				computeAggBinarySlicedInputBroadcastCost(hop, inputHops, inputFTypes, numWorkers);
			return computePartialAggregationCost("aggbinary-add-aggregation",
				hop, outputMemEstimate, numWorkers, inputPreparationCost, 0.0, true);
		}
		return MixedFedLocalCost.none();
	}

	/**
	 * Runtime-stage model for {@code AggregateUnaryFEDInstruction} when the selected
	 * planner state is {@code FED/LOUT}.
	 *
	 * <p>The runtime does not first materialize the full federated input at the
	 * coordinator. It sends the aggregate instruction to the workers, retrieves one
	 * reduced partial result per participating worker through {@code GET_VAR}, and
	 * performs the final aggregate/bind locally. Therefore the cost must be based on
	 * the aggregate result shape (scalar, row vector, or column vector), not on a
	 * stale matrix-boundary estimate inherited from the input hop.</p>
	 */
	public static boolean requiresFederatedAggUnaryLocalAggregation(Hop hop) {
		return hop instanceof AggUnaryOp;
	}

	public static double computeAggregateUnaryLocalAggregationCost(Hop hop, FType logicalFType,
			double outputMemEstimate, int numWorkers) {
		if (!(hop instanceof AggUnaryOp))
			return 0.0;
		return computeAggregateUnaryLocalAggregationCost("agg-unary-local-aggregation",
			(AggUnaryOp) hop, logicalFType, outputMemEstimate, numWorkers, 0.0)
			.getCoordinatorPhaseCost();
	}

	/**
	 * Runtime input-preparation model for {@code AggregateBinaryFEDInstruction}'s
	 * ROW-left branch.
	 *
	 * <p>When the left input is ROW/PART federated, runtime executes the matrix
	 * multiply on the left input's workers.  The right input is reused directly only
	 * when it is already represented as a compatible BROADCAST federated object;
	 * otherwise the runtime sends/refederates it to the left input's worker map
	 * before the {@code ba+*} call.  This applies to both FED/FOUT and FED/LOUT
	 * choices; FED/LOUT then separately pays the ordinary result download/bind
	 * cost.  Keep the FED candidate open and charge the runtime-defined preparation
	 * work instead of hard-closing the branch.</p>
	 */
	public static boolean requiresFederatedAggBinaryRowLeftInputPreparation(Hop hop,
			List<FType> inputFTypes) {
		if (!(hop instanceof AggBinaryOp))
			return false;
		AggBinaryOp aggBinaryOp = (AggBinaryOp) hop;
		if (!aggBinaryOp.isMatrixMultiply())
			return false;
		FType left = typeAt(inputFTypes, 0);
		if (!isStrictRowPartition(left))
			return false;
		FType right = typeAt(inputFTypes, 1);
		return right != FType.BROADCAST;
	}

	public static double computeAggBinaryRowLeftInputPreparationCost(Hop hop,
			List<Hop> inputHops, List<FType> inputFTypes, int numWorkers) {
		if (!requiresFederatedAggBinaryRowLeftInputPreparation(hop, inputFTypes))
			return 0.0;
		return computeFullBroadcastInputCost(inputHopAt(inputHops, 1), numWorkers);
	}

	private static MixedFedLocalCost computePartialAggregationCost(String label, Hop hop,
			double outputMemEstimate, int numWorkers, double inputPreparationCost,
			double federatedComputeFloor, boolean fixedResultControlOwnedByFedUnary) {
		double partialResultMem = outputMemEstimate > 0.0 ? outputMemEstimate : getEffectiveOutputMemEstimate(hop);
		if (partialResultMem <= 0.0)
			partialResultMem = getEffectiveUploadMemEstimate(hop);
		if (partialResultMem <= 0.0)
			return new MixedFedLocalCost(label, inputPreparationCost, 0.0, 0.0, federatedComputeFloor);

		int fanIn = Math.max(1, numWorkers);
		double partialDownloadCost = fixedResultControlOwnedByFedUnary
			? computeInBandWorkerResultDownloadCost(partialResultMem, fanIn, true)
			: computeReplicatedWorkerResultDownloadCost(partialResultMem, fanIn);
		double coordinatorAggregationCost = computeCoordinatorAggregationCost(hop, partialResultMem, fanIn);
		double cleanupControlCost = computeLocalAggregationCleanupControlCost(fanIn);
		return new MixedFedLocalCost(label, inputPreparationCost, partialDownloadCost,
			coordinatorAggregationCost + cleanupControlCost, federatedComputeFloor);
	}

	private static MixedFedLocalCost computeAggregateUnaryLocalAggregationCost(String label,
			AggUnaryOp aggregateUnary, FType logicalFType, double outputMemEstimate,
			int numWorkers, double inputPreparationCost) {
		double partialResultMem = estimateAggregateUnaryResultMemEstimate(
			aggregateUnary, outputMemEstimate);
		if (partialResultMem <= 0.0)
			partialResultMem = getEffectiveOutputMemEstimate(aggregateUnary);
		if (partialResultMem <= 0.0)
			partialResultMem = getInjectedDefaultMemEstimatePerCell(aggregateUnary);

		int workers = Math.max(1, numWorkers);
		double partialDownloadCost = computeAggregateUnaryPartialResultDownloadCost(
			aggregateUnary, logicalFType, partialResultMem, workers);
		double coordinatorAggregationCost = computeAggregateUnaryCoordinatorAggregationCost(
			aggregateUnary, partialResultMem, workers);
		double cleanupControlCost = computeLocalAggregationCleanupControlCost(workers);
		return new MixedFedLocalCost(label, inputPreparationCost, partialDownloadCost,
			coordinatorAggregationCost + cleanupControlCost, 0.0);
	}

	private static double computeAggregateUnaryPartialResultDownloadCost(AggUnaryOp aggregateUnary,
			FType logicalFType, double partialResultMem, int numWorkers) {
		if (partialResultMem <= 0.0)
			return 0.0;
		double payloadFanIn = estimateNativeAggregateUnaryPayloadFanIn(
			aggregateUnary, logicalFType, Math.max(1, numWorkers));
		return computeDownloadPayloadCost(partialResultMem * Math.max(1.0, payloadFanIn));
	}

	private static double computeAggregateUnaryCoordinatorAggregationCost(AggUnaryOp aggregateUnary,
			double partialResultMem, int fanIn) {
		int workers = Math.max(1, fanIn);
		if (workers <= 1)
			return computeMemoryAccessCost(partialResultMem);

		double outputCells = estimateAggregateUnaryResultCellCount(aggregateUnary, partialResultMem);
		double aggregateFlops = Math.max(0, workers - 1) * Math.max(0.0, outputCells);
		double aggregateComputeCost = (aggregateFlops / getComputeFlopsPerSec(aggregateUnary)) * TO_MS;
		double aggregateReadCost = computeMemoryAccessCost(partialResultMem * workers);
		double aggregateWriteCost = computeMemoryAccessCost(partialResultMem);
		return Math.max(aggregateComputeCost, aggregateReadCost) + aggregateWriteCost;
	}

	/**
	 * Cost of the legal WDivMM FED/LOUT runtime path that materializes full partial
	 * worker results at the coordinator.
	 *
	 * <p>{@code QuaternaryWDivMMFEDInstruction} executes left WDivMM over ROW X and
	 * right WDivMM over COL X by issuing a federated compute request, collecting a
	 * full partial result from every worker via {@code GET_VAR}, and aggregating
	 * those matrices locally. This is not an unplannable case and must not close the
	 * FED candidate. It is also not the same as an ordinary ROW/COL result download:
	 * the payload is one full partial result per worker, followed by coordinator
	 * aggregation work.</p>
	 */
	public static double computeWdivmmLocalAggregationCost(Hop hop, FType logicalFType,
			double outputMemEstimate, int numWorkers) {
		return computeMixedFedLocalCost(hop, null, null, logicalFType, 0.0,
			outputMemEstimate, numWorkers).getCoordinatorPhaseCost();
	}

	private static double computeWdivmmNativeOutputStageCost(Hop hop, FType logicalFType,
			double outputMemEstimate, int numWorkers) {
		if (!(hop instanceof QuaternaryOp))
			return 0.0;
		QuaternaryOp quaternaryOp = (QuaternaryOp) hop;
		if (quaternaryOp.getOp() != OpOp4.WDIVMM)
			return 0.0;
		if (requiresFederatedWdivmmLocalAggregation(hop, logicalFType))
			return 0.0;

		double resultMem = outputMemEstimate > 0.0 ? outputMemEstimate : getEffectiveOutputMemEstimate(hop);
		if (resultMem <= 0.0)
			resultMem = getEffectiveUploadMemEstimate(hop);
		if (resultMem <= 0.0)
			return 0.0;

		// Native QuaternaryWDivMMFEDInstruction produces a federated runtime object for
		// BASIC/LEFT/RIGHT cases that do not take the explicit local-aggregation
		// GET_VAR path.  When the DP plan later needs both a coordinator-local
		// consumer and a federated continuation, runtime pays a real result
		// materialization/refederation stage (observed as WDIVMM plus FED->FOUT
		// materialization work), not just the worker compute request.  Model that
		// stage from result size and federation topology instead of closing the legal
		// FED candidate or keying on workloads/rows/hop ids.
		FType resultType = logicalFType != null ? logicalFType : FType.FULL;
		return computeDownloadNetworkCost(resultMem, resultType, numWorkers)
			+ computeUploadNetworkCost(resultMem, resultType, numWorkers)
			+ computeLocalToFedForwardingPenalty(resultType, numWorkers);
	}

	/**
	 * Cost of {@code QuaternaryWDivMMFEDInstruction} input preparation before the
	 * federated WDivMM compute request.
	 *
	 * <p>This keeps legal FED candidates open. It only charges runtime-defined data
	 * movement required by the selected input states:</p>
	 * <ul>
	 *   <li>ROW-partitioned X: U is reused only when already ROW-aligned; otherwise
	 *       it is {@code broadcastSliced}. V is broadcast as a full matrix.</li>
	 *   <li>COL-partitioned X: U is broadcast as a full matrix. V is reused only
	 *       when already COL-aligned; otherwise it is {@code broadcastSliced}.</li>
	 *   <li>Matrix fourth inputs (MULT_MINUS_4 variants) are sliced to X's map when
	 *       not already FULL-aligned.</li>
	 * </ul>
	 */
	public static double computeWdivmmInputPreparationCost(Hop hop, List<Hop> inputHops,
			List<FType> inputFTypes, int numWorkers) {
		if (!(hop instanceof QuaternaryOp))
			return 0.0;
		QuaternaryOp quaternaryOp = (QuaternaryOp) hop;
		if (quaternaryOp.getOp() != OpOp4.WDIVMM)
			return 0.0;

		FType xType = typeAt(inputFTypes, 0);
		double cost = 0.0;
		if (isStrictRowPartition(xType)) {
			FType uType = typeAt(inputFTypes, 1);
			if (!isStrictRowPartition(uType))
				cost += computeSlicedBroadcastInputCost(inputHopAt(inputHops, 1), numWorkers);
			cost += computeFullBroadcastInputCost(inputHopAt(inputHops, 2), numWorkers);
		}
		else if (xType == FType.COL) {
			cost += computeFullBroadcastInputCost(inputHopAt(inputHops, 1), numWorkers);
			FType vType = typeAt(inputFTypes, 2);
			if (vType != FType.COL)
				cost += computeSlicedBroadcastInputCost(inputHopAt(inputHops, 2), numWorkers);
		}
		else {
			return 0.0;
		}

		Hop fourth = inputHopAt(inputHops, 3);
		if (fourth != null && fourth.getDataType() != null && fourth.getDataType().isMatrix()) {
			FType fourthType = typeAt(inputFTypes, 3);
			if (fourthType != FType.FULL)
				cost += computeSlicedBroadcastInputCost(fourth, numWorkers);
		}
		return cost;
	}

	public static boolean requiresFederatedAggBinaryAddAggregation(Hop hop, List<FType> inputFTypes) {
		if (!(hop instanceof AggBinaryOp))
			return false;
		AggBinaryOp aggBinaryOp = (AggBinaryOp) hop;
		if (!aggBinaryOp.isMatrixMultiply())
			return false;

		FType left = typeAt(inputFTypes, 0);
		FType right = typeAt(inputFTypes, 1);
		if (left == null && right == null)
			return false;

		// Runtime AggregateBinaryFEDInstruction has local-aggregation-by-add branches for:
		//  - FULL/BROADCAST/local/COL left x ROW right (sliced broadcast + GET_VAR + aggAdd), and
		//  - COL left x any compatible right input (GET_VAR + aggAdd, with sliced
		//    input preparation only when the right input is coordinator-local).
		// ROW-left matrix multiplication materializes local output by binding row partitions,
		// which is already represented by the ordinary ROW/COL download fan-in model.
		if (right == FType.ROW && left != FType.FULL && !isStrictRowPartition(left))
			return true;
		return left == FType.COL;
	}

	public static double computeAggBinaryAddAggregationCost(Hop hop, List<FType> inputFTypes,
			double outputMemEstimate, int numWorkers) {
		return computeMixedFedLocalCost(hop, null, inputFTypes, null, 0.0,
			outputMemEstimate, numWorkers).getCoordinatorPhaseCost();
	}

	/**
	 * Cost of AggregateBinaryFEDInstruction input preparation paths that cannot
	 * consume the child federation maps directly and therefore issue
	 * {@code FederationMap.broadcastSliced(...)} before the federated matrix
	 * multiply.
	 *
	 * <p>This keeps the FED candidate open. It only charges the runtime-defined
	 * payload movement needed to present the selected child state to the chosen
	 * federated execution branch:</p>
	 * <ul>
		 *   <li>{@code local left x ROW right}: slice/broadcast the left operand
		 *       according to the right ROW map.  FULL/BROADCAST logical left inputs
		 *       already carry a federated full/replicated state, so the model does not
		 *       add a coordinator local-to-federated upload term for them.  COL-left x
		 *       ROW-right is the aligned COL_T runtime branch once the planner has
		 *       admitted both inputs as compatible federated inputs, so it should not
		 *       pay this sliced-input preparation term.</li>
	 *   <li>{@code COL left x local right}: slice/broadcast the right operand
	 *       according to the left COL map. FULL/BROADCAST logical right inputs
	 *       already carry a remote full/replicated representation and must not be
	 *       charged as a repeated coordinator upload.</li>
	 * </ul>
	 *
		 * <p>ROW-left matrix multiplication can consume/broadcast the right side without
		 * this repartition penalty; COL-left x ROW-right is handled by the aligned
		 * runtime branch and is therefore not charged here.</p>
	 */
	public static double computeAggBinarySlicedInputBroadcastCost(Hop hop, List<Hop> inputHops,
			List<FType> inputFTypes, int numWorkers) {
		if (!(hop instanceof AggBinaryOp))
			return 0.0;
		AggBinaryOp aggBinaryOp = (AggBinaryOp) hop;
		if (!aggBinaryOp.isMatrixMultiply())
			return 0.0;
		if (aggBinaryOp.checkTransposeSelf() != null
				&& aggBinaryOp.checkTransposeSelf() != org.apache.sysds.lops.MMTSJ.MMTSJType.NONE)
			return 0.0;

		FType left = typeAt(inputFTypes, 0);
		FType right = typeAt(inputFTypes, 1);
		if (left == null && right == null)
			return 0.0;

		if (right == FType.ROW && !isStrictRowPartition(left)) {
			if (left == FType.COL)
				return 0.0;
			if (isFederatedFullOrBroadcast(left))
				return 0.0;
			return computeSlicedBroadcastInputCost(inputHopAt(inputHops, 0), numWorkers);
		}
		if (left == FType.COL) {
			if (isFederatedFullOrBroadcast(right))
				return 0.0;
			return computeSlicedBroadcastInputCost(inputHopAt(inputHops, 1), numWorkers);
		}
		return 0.0;
	}

	private static boolean isFederatedFullOrBroadcast(FType type) {
		return type == FType.FULL || type == FType.BROADCAST;
	}

	private static Hop inputHopAt(List<Hop> inputHops, int index) {
		if (inputHops == null || index < 0 || index >= inputHops.size())
			return null;
		return inputHops.get(index);
	}

	private static double computeSlicedBroadcastInputCost(Hop inputHop, int numWorkers) {
		double memEstimate = getEffectiveOutputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveUploadMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveInputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			return 0.0;

		// broadcastSliced over ROW/COL maps sends disjoint slices whose total payload
		// is approximately the input size. Add fan-out latency/control overhead without
		// multiplying the payload as a full BROADCAST would.
		return computeUploadNetworkCost(memEstimate, FType.ROW, numWorkers)
			+ computeLocalToFedForwardingPenalty(FType.ROW, numWorkers);
	}

	private static double computeFullBroadcastInputCost(Hop inputHop, int numWorkers) {
		double memEstimate = getEffectiveOutputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveUploadMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveInputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			return 0.0;
		return computeUploadNetworkCost(memEstimate, FType.BROADCAST, numWorkers)
			+ computeLocalToFedForwardingPenalty(FType.BROADCAST, numWorkers);
	}

	private static FType typeAt(List<FType> types, int index) {
		if (types == null || index < 0 || index >= types.size())
			return null;
		return types.get(index);
	}

	private static boolean isStrictRowPartition(FType type) {
		return type == FType.ROW || type == FType.PART;
	}

	private static double computeReplicatedWorkerResultDownloadCost(double memSizePerWorker, int fanIn) {
		int workers = Math.max(1, fanIn);
		double payloadMem = memSizePerWorker * workers;
		double baseCost = computeDirectionalNetworkCost(payloadMem,
			MBS_NETWORK_BANDWIDTH_W2C, MBS_NETWORK_SERDES_BANDWIDTH_W2C);
		if (workers <= 1)
			return baseCost;
		double latencyPenaltyMs = (workers - 1) * MBS_NETWORK_LATENCY * TO_MS;
		double controlPenaltyMs = (workers - 1) * Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		return baseCost + latencyPenaltyMs + controlPenaltyMs;
	}

	private static double computeInBandWorkerResultDownloadCost(double resultMem, int fanIn,
			boolean replicatedResultPerWorker) {
		if (resultMem <= 0.0)
			return 0.0;
		int workers = Math.max(1, fanIn);
		double payloadMem = replicatedResultPerWorker ? resultMem * workers : resultMem;
		double payloadCost = computeDownloadPayloadCost(payloadMem);
		if (workers <= 1)
			return payloadCost;
		double latencyPenaltyMs = (workers - 1) * MBS_NETWORK_LATENCY * TO_MS;
		double controlPenaltyMs = (workers - 1) * Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
		return payloadCost + latencyPenaltyMs + controlPenaltyMs;
	}

	private static double computeLocalAggregationCleanupControlCost(int fanIn) {
		int workers = Math.max(1, fanIn);
		if (workers <= 1)
			return 0.0;
		// Local-aggregation FED instructions do not end at GET_VAR. Runtime also
		// sends a cleanup request for the worker-side temporary output produced by the
		// federated compute request (for example QuaternaryWDivMMFEDInstruction and
		// AggregateBinaryFEDInstruction.aggregateLocally). The payload is negligible,
		// but the request is a separate worker fan-out stage after the partial-result
		// GET_VAR, so charge its control/latency cost explicitly instead of hiding it
		// in the generic FED compute term or closing the legal FED candidate.
		return computeLocalToFedForwardingPenalty(FType.BROADCAST, workers);
	}

	private static double computeCoordinatorAggregationCost(Hop hop, double partialResultMem, int fanIn) {
		int workers = Math.max(1, fanIn);
		if (workers <= 1)
			return computeMemoryAccessCost(partialResultMem);

		double outputCells = estimateLogicalCellCount(hop, partialResultMem);
		double aggregateFlops = Math.max(0, workers - 1) * Math.max(0.0, outputCells);
		double aggregateComputeCost = (aggregateFlops / getComputeFlopsPerSec(hop)) * TO_MS;
		double aggregateReadCost = computeMemoryAccessCost(partialResultMem * workers);
		double aggregateWriteCost = computeMemoryAccessCost(partialResultMem);
		return Math.max(aggregateComputeCost, aggregateReadCost) + aggregateWriteCost;
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
		if (currentHop instanceof QuaternaryOp
				&& ((QuaternaryOp) currentHop).getOp() == OpOp4.WDIVMM) {
			computeCost = Math.max(computeCost,
				estimateWdivmmRankAwareComputeFloor((QuaternaryOp) currentHop));
		}
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

	private static double estimateWdivmmRankAwareComputeFloor(QuaternaryOp hop) {
		if (hop == null || hop.getOp() != OpOp4.WDIVMM || hop.getInput() == null
				|| hop.getInput().isEmpty())
			return 0.0;

		Hop weights = hop.getInput().get(0);
		double weightCells = estimateLogicalCellCount(weights, getEffectiveOutputMemEstimate(weights));
		double rank = estimateWdivmmRank(hop);
		if (weightCells <= 0.0 || rank <= 1.0)
			return 0.0;

		// Runtime WDivMM kernels do not only touch each weighted input cell once.  For
		// BASIC/LEFT/RIGHT variants every active weight cell participates in a
		// rank-width U/V factor interaction (dot product plus output contribution).
		// The generic HOP compute model charges roughly a constant four flops per
		// weighted cell, which is suitable only for rank=1 and underestimates ALS
		// factor-update WDivMM by about the factor rank.  Keep this as a floor for the
		// FedPlanner cost model instead of closing legal FED candidates.
		return 4.0 * rank * weightCells;
	}

	private static double estimateWdivmmRank(QuaternaryOp hop) {
		if (hop == null || hop.getInput() == null)
			return 1.0;
		List<Hop> inputs = hop.getInput();
		if (inputs.size() > 1) {
			long uRank = inputs.get(1).getDim2();
			if (uRank > 0)
				return uRank;
		}
		if (inputs.size() > 2) {
			long vRank = inputs.get(2).getDim2();
			if (vRank > 0)
				return vRank;
		}
		long outputCols = hop.getDim2();
		return outputCols > 0 ? outputCols : 1.0;
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

	private static double estimateAggregateUnaryResultMemEstimate(AggUnaryOp aggregateUnary,
			double fallbackMemEstimate) {
		double resultCells = estimateAggregateUnaryResultCellCount(aggregateUnary, fallbackMemEstimate);
		if (resultCells > 0.0)
			return resultCells * getInjectedDefaultMemEstimatePerCell(aggregateUnary);
		if (fallbackMemEstimate > 0.0)
			return fallbackMemEstimate;
		return getEffectiveOutputMemEstimate(aggregateUnary);
	}

	private static double estimateAggregateUnaryResultCellCount(AggUnaryOp aggregateUnary,
			double fallbackMemEstimate) {
		if (aggregateUnary == null)
			return 0.0;
		if (aggregateUnary.getDataType() != null && aggregateUnary.getDataType().isScalar())
			return 1.0;

		Direction direction = aggregateUnary.getDirection();
		if (direction != null) {
			if (direction.isRowCol())
				return 1.0;
			if (direction.isRow()) {
				long rows = aggregateUnary.getDim1();
				if (rows <= 0 && aggregateUnary.getInput() != null && !aggregateUnary.getInput().isEmpty())
					rows = aggregateUnary.getInput().get(0).getDim1();
				if (rows > 0)
					return rows;
			}
			if (direction.isCol()) {
				long cols = aggregateUnary.getDim2();
				if (cols <= 0 && aggregateUnary.getInput() != null && !aggregateUnary.getInput().isEmpty())
					cols = aggregateUnary.getInput().get(0).getDim2();
				if (cols > 0)
					return cols;
			}
		}

		return estimateLogicalCellCount(aggregateUnary, fallbackMemEstimate);
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
		double semanticOutputMemEstimate = getSemanticSparseAssignmentMemEstimate(hop);
		if (semanticOutputMemEstimate > 0.0)
			return semanticOutputMemEstimate;
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

	/**
	 * Estimate sparse payloads for row-wise arg-min assignment matrices.
	 *
	 * <p>The kmeans-style idiom {@code P = D <= rowMins(D); P = P / rowSums(P)}
	 * creates an assignment matrix whose non-zero payload is driven by the number
	 * of chosen minima per row, not by {@code nrow(D) * ncol(D)}. The generic HOP
	 * stats conservatively keep {@code nnz=-1} for the comparison/division chain,
	 * which makes local-to-FED materialization of {@code P} or {@code t(P)} look
	 * dense and can incorrectly price the legal mixed FED/local aggregate-binary
	 * path out of the search space.  This is a semantic cost-state estimate only:
	 * it keeps all candidates open and does not key on workload, worker count, row
	 * id, or hop id.</p>
	 *
	 * <p>Exact tie counts are data-dependent and unavailable at static planning
	 * time.  For a row-min assignment indicator, the stable planning estimate is
	 * one selected cell per row, with the runtime still free to materialize extra
	 * tie cells when the data contains ties.</p>
	 */
	private static double getSemanticSparseAssignmentMemEstimate(Hop hop) {
		SparseAssignmentShape shape = getSemanticSparseAssignmentShape(hop);
		if (shape == null)
			return 0.0;
		double sparsity = Math.min(1.0, shape.nnz / (double) shape.rows / (double) shape.cols);
		return OptimizerUtils.estimateSizeExactSparsity(shape.rows, shape.cols, sparsity, hop.getDataType());
	}

	private static double getSemanticSparseAssignmentSerializedMemEstimate(Hop hop) {
		SparseAssignmentShape shape = getSemanticSparseAssignmentShape(hop);
		if (shape == null)
			return 0.0;
		return MatrixBlock.estimateSizeOnDisk(shape.rows, shape.cols, shape.nnz);
	}

	private static SparseAssignmentShape getSemanticSparseAssignmentShape(Hop hop) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return null;
		if (isTranspose(hop)) {
			SparseAssignmentShape inputShape = getSemanticSparseAssignmentShape(hop.getInput(0));
			return inputShape == null ? null : inputShape.transposeLike(hop);
		}
		if (!isNormalizedRowArgMinAssignment(hop) && !isRowArgMinIndicator(hop))
			return null;

		Hop source = getRowArgMinSourceMatrix(hop);
		if (source == null || source.getDim1() <= 0 || source.getDim2() <= 0)
			return null;
		long rows = hop.getDim1() > 0 ? hop.getDim1() : source.getDim1();
		long cols = hop.getDim2() > 0 ? hop.getDim2() : source.getDim2();
		if (rows <= 0 || cols <= 0)
			return null;
		long nnz = Math.min(rows * cols, Math.max(1, source.getDim1()));
		return new SparseAssignmentShape(rows, cols, nnz);
	}

	private static final class SparseAssignmentShape {
		private final long rows;
		private final long cols;
		private final long nnz;

		private SparseAssignmentShape(long rows, long cols, long nnz) {
			this.rows = rows;
			this.cols = cols;
			this.nnz = nnz;
		}

		private SparseAssignmentShape transposeLike(Hop hop) {
			long transposedRows = hop.getDim1() > 0 ? hop.getDim1() : cols;
			long transposedCols = hop.getDim2() > 0 ? hop.getDim2() : rows;
			return new SparseAssignmentShape(transposedRows, transposedCols, nnz);
		}
	}

	private static boolean isNormalizedRowArgMinAssignment(Hop hop) {
		if (!(hop instanceof BinaryOp) || ((BinaryOp) hop).getOp() != OpOp2.DIV)
			return false;
		Hop numerator = hop.getInput(0);
		Hop denominator = hop.getInput(1);
		return isRowArgMinIndicator(numerator) && isRowSumOf(denominator, numerator);
	}

	private static boolean isRowArgMinIndicator(Hop hop) {
		if (!(hop instanceof BinaryOp))
			return false;
		BinaryOp binaryOp = (BinaryOp) hop;
		if (binaryOp.getOp() == OpOp2.LESSEQUAL)
			return isRowMinOf(binaryOp.getInput(1), binaryOp.getInput(0));
		if (binaryOp.getOp() == OpOp2.GREATEREQUAL)
			return isRowMinOf(binaryOp.getInput(0), binaryOp.getInput(1));
		if (binaryOp.getOp() == OpOp2.EQUAL)
			return isRowMinOf(binaryOp.getInput(0), binaryOp.getInput(1))
				|| isRowMinOf(binaryOp.getInput(1), binaryOp.getInput(0));
		return false;
	}

	private static Hop getRowArgMinSourceMatrix(Hop hop) {
		if (hop == null)
			return null;
		if (isTranspose(hop))
			return getRowArgMinSourceMatrix(hop.getInput(0));
		if (isNormalizedRowArgMinAssignment(hop))
			return getRowArgMinSourceMatrix(hop.getInput(0));
		if (!(hop instanceof BinaryOp))
			return null;
		BinaryOp binaryOp = (BinaryOp) hop;
		if (binaryOp.getOp() == OpOp2.LESSEQUAL && isRowMinOf(binaryOp.getInput(1), binaryOp.getInput(0)))
			return binaryOp.getInput(0);
		if (binaryOp.getOp() == OpOp2.GREATEREQUAL && isRowMinOf(binaryOp.getInput(0), binaryOp.getInput(1)))
			return binaryOp.getInput(1);
		if (binaryOp.getOp() == OpOp2.EQUAL) {
			if (isRowMinOf(binaryOp.getInput(1), binaryOp.getInput(0)))
				return binaryOp.getInput(0);
			if (isRowMinOf(binaryOp.getInput(0), binaryOp.getInput(1)))
				return binaryOp.getInput(1);
		}
		return null;
	}

	private static boolean isRowMinOf(Hop aggregateHop, Hop sourceHop) {
		if (!(aggregateHop instanceof AggUnaryOp) || sourceHop == null)
			return false;
		AggUnaryOp aggregate = (AggUnaryOp) aggregateHop;
		return aggregate.getOp() == AggOp.MIN
			&& aggregate.getDirection() == Direction.Row
			&& aggregate.getInput(0) == sourceHop;
	}

	private static boolean isRowSumOf(Hop aggregateHop, Hop sourceHop) {
		if (!(aggregateHop instanceof AggUnaryOp) || sourceHop == null)
			return false;
		AggUnaryOp aggregate = (AggUnaryOp) aggregateHop;
		return aggregate.getOp() == AggOp.SUM
			&& aggregate.getDirection() == Direction.Row
			&& aggregate.getInput(0) == sourceHop;
	}

	private static boolean isTranspose(Hop hop) {
		return hop instanceof ReorgOp && ((ReorgOp) hop).getOp() == ReOrgOp.TRANS
			&& hop.getInput() != null && !hop.getInput().isEmpty();
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

		double serializedSparseAssignmentMem = getSemanticSparseAssignmentSerializedMemEstimate(hop);
		if (serializedSparseAssignmentMem > 0.0)
			return serializedSparseAssignmentMem;

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

	private static double computeDownloadPayloadCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		double effectiveBw = (MBS_NETWORK_BANDWIDTH_W2C > 0.0) ? MBS_NETWORK_BANDWIDTH_W2C : MBS_NETWORK_BANDWIDTH;
		double payloadMb = memSize / (1024 * 1024);
		double payloadSec = payloadMb / effectiveBw;
		double effectiveSerdesBw = (MBS_NETWORK_SERDES_BANDWIDTH_W2C > 0.0)
			? MBS_NETWORK_SERDES_BANDWIDTH_W2C
			: 0.0;
		if (effectiveSerdesBw > 0.0)
			payloadSec += payloadMb / effectiveSerdesBw;
		return payloadSec * TO_MS;
	}

	public static double computeDownloadNetworkCost(double memSize, FType fType, int numWorkers) {
		if (memSize <= 0)
			return 0.0;
		int fanIn = estimateDownloadFanIn(fType, numWorkers);
		double baseCost = computeDownloadNetworkCost(estimateParallelDownloadPayload(memSize, fanIn));
		if (baseCost <= 0.0)
			return baseCost;
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
	 * workers. The base directional upload already includes one latency/control
	 * stage, so this penalty captures only the remaining fan-out stages
	 * ((numWorkers - 1) * (latency + control)) without changing the shared
	 * bandwidth model.
	 */
	public static double computeLocalToFedForwardingPenalty(FType fType, int numWorkers) {
		if (fType == null)
			return 0.0;
		int fanout = Math.max(1, numWorkers);
		if (fanout <= 1)
			return 0.0;
		double latencyPenaltyMs = (fanout - 1) * MBS_NETWORK_LATENCY * TO_MS;
		double controlPenaltyMs = (fanout - 1) * Math.max(0.0, LOCAL_TO_FED_CTRL_OVERHEAD_MS);
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

	private static double estimateParallelDownloadPayload(double totalMemSize, int fanIn) {
		if (totalMemSize <= 0.0)
			return 0.0;
		if (fanIn <= 1)
			return totalMemSize;
		// ROW/COL/PART federated matrices are materialized by collecting disjoint
		// partitions from multiple workers. The logical matrix size is the sum of
		// those partitions, but the runtime issues the worker requests together; the
		// wall-clock payload term is therefore bounded by the largest worker partition
		// plus fan-in latency/control, not by serializing the full logical matrix
		// through one link. This keeps CP materialization legal and costed instead of
		// over-pricing it into an artificial FED/LOUT choice.
		return totalMemSize / fanIn;
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

}
 
