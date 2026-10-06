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
import org.apache.sysds.common.Types.ParamBuiltinOp;
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
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.codegen.SpoofFusedOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.FederatedExecutionLayout;
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics.InputLayout;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

public final class FederatedCostModel {
	public static final class MixedFedLocalCost {
		private static final MixedFedLocalCost NONE =
			new MixedFedLocalCost("none", 0.0, 0.0, 0.0);

		private final String label;
		private final double inputPreparationCost;
		private final double partialResultDownloadCost;
		private final double coordinatorLocalCost;

		private MixedFedLocalCost(String label, double inputPreparationCost,
				double partialResultDownloadCost, double coordinatorLocalCost) {
			this.label = label;
			this.inputPreparationCost = sanitizeCost(inputPreparationCost);
			this.partialResultDownloadCost = sanitizeCost(partialResultDownloadCost);
			this.coordinatorLocalCost = sanitizeCost(coordinatorLocalCost);
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

		/** Compatibility accessor: kernel work is now priced by the shared execution primitive. */
		public double getFederatedComputeFloor() {
			return 0.0;
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
			return false;
		}
	}

	private static final String ENV_MBS_MEMORY_BANDWIDTH = "SYSDS_FED_COST_MEM_BW";
	private static final String ENV_MBS_NETWORK_BANDWIDTH_C2W = "SYSDS_FED_COST_NET_BW_C2W";
	private static final String ENV_MBS_NETWORK_BANDWIDTH_W2C = "SYSDS_FED_COST_NET_BW_W2C";
	// Additional per-byte overhead for federated PUT/GET (serialization + deserialization + RPC/Netty framing).
	//
	// One-way stage: latency + wire bytes/net_bw + codec bytes/serdes_bw. No control intercept.
	// Setting directional serdes_bw=0 disables this term.
	private static final String ENV_MBS_NETWORK_SERDES_BANDWIDTH_C2W = "SYSDS_FED_COST_NET_SERDES_BW_C2W";
	private static final String ENV_MBS_NETWORK_SERDES_BANDWIDTH_W2C = "SYSDS_FED_COST_NET_SERDES_BW_W2C";
	private static final String ENV_NETWORK_LATENCY_C2W = "SYSDS_FED_COST_NET_LATENCY_C2W";
	private static final String ENV_NETWORK_LATENCY_W2C = "SYSDS_FED_COST_NET_LATENCY_W2C";
	private static final String ENV_UPLOAD_ESTIMATE_CLAMP_RATIO = "SYSDS_FED_COST_UPLOAD_MEM_CLAMP_RATIO";
	private static final String ENV_UNKNOWN_DIM_TRANSFER_FALLBACK_MB = "SYSDS_FED_COST_UNKNOWN_DIM_TRANSFER_MB";
	private static final String ENV_FLOPS_PER_SEC = "SYSDS_FED_COST_FLOPS";
	private static final String ENV_AGGBINARY_FLOPS_PER_SEC = "SYSDS_FED_COST_AGGBINARY_FLOPS";
	private static final double DEFAULT_MEM_ESTIMATE_PER_CELL = OptimizerUtils.DOUBLE_SIZE;
	private static final double DEFAULT_FP32_MEM_ESTIMATE_PER_CELL = 4.0;
	private static final double DEFAULT_STRING_MEM_ESTIMATE_PER_CELL = 100.0 * OptimizerUtils.CHAR_SIZE;

	// Default values are used as reasonable estimates since we only need to compare
	// relative costs between different federated plans.
	// Configured default memory throughput in MiB/s (not decimal GB/s).
	private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
	// Configured default worker-link throughput in MiB/s (not a measured NIC speed).
	private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
	// Additional per-byte overhead term for federated transfers (disabled by default).
	private static final double DEFAULT_MBS_NETWORK_SERDES_BANDWIDTH = 0.0;
	// One-way seconds; preserves the previous default total of 1 ms per exchange.
	private static final double DEFAULT_ONE_WAY_LATENCY = 0.0005;
	// Clamp suspiciously large upload-size estimates when output dimensions are unknown.
	// This avoids over-penalizing CP->FOUT candidates for shape-dependent operators
	// (e.g., rightIndex/matmult chains before recompile resolves dimensions).
	private static final double DEFAULT_UPLOAD_ESTIMATE_CLAMP_RATIO = 4.0;
	private static final double DEFAULT_UNKNOWN_DIM_MEM_SENTINEL_BYTES = 8d * 1024 * 1024 * 1024;
	private static final double UNKNOWN_DIM_MEM_SENTINEL_EPSILON = 0.01;
	private static final int UNKNOWN_DIM_DESCENT_MAX_DEPTH = 6;
	// Fallback transfer payload used when output dimensions remain unknown and no
	// reliable descendant size estimate is available. This value directly impacts
	// DP/Exact decisions that trade off local materialization vs. federated plans
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
	// multi-worker planning and can bias Exact/DP toward pathological FED/FOUT chains.
	// Keep the calibration shared so both planners see the same correction.
	private static final double DEFAULT_AGGBINARY_FLOPS_PER_SEC = 32d * 1000 * 1000 * 1000;
	// All costs are returned in milliseconds.
	private static final double TO_MS = 1000.0;
	private static final double MBS_MEMORY_BANDWIDTH = FederatedPlannerConfiguration.captureDoublePropertyOrEnvironment(ENV_MBS_MEMORY_BANDWIDTH,
			DEFAULT_MBS_MEMORY_BANDWIDTH);
	private static final double MBS_NETWORK_BANDWIDTH_C2W = captureNetworkThroughput(
		ENV_MBS_NETWORK_BANDWIDTH_C2W, DEFAULT_MBS_NETWORK_BANDWIDTH, false);
	private static final double MBS_NETWORK_BANDWIDTH_W2C = captureNetworkThroughput(
		ENV_MBS_NETWORK_BANDWIDTH_W2C, DEFAULT_MBS_NETWORK_BANDWIDTH, false);
	// Capacities are fixed before optimization. A zero optional coordinator rate
	// means that resource is unspecified, not a measured infinite-capacity NIC.
	private static final double MBS_NETWORK_COORDINATOR_C2W = captureNetworkThroughput(
		"SYSDS_FED_COST_NET_BW_COORD_C2W", MBS_NETWORK_BANDWIDTH_C2W, true);
	private static final double MBS_NETWORK_COORDINATOR_W2C = captureNetworkThroughput(
		"SYSDS_FED_COST_NET_BW_COORD_W2C", 0.0, true);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH_C2W = captureNetworkThroughput(
		ENV_MBS_NETWORK_SERDES_BANDWIDTH_C2W, DEFAULT_MBS_NETWORK_SERDES_BANDWIDTH, true);
	private static final double MBS_NETWORK_SERDES_BANDWIDTH_W2C = captureNetworkThroughput(
		ENV_MBS_NETWORK_SERDES_BANDWIDTH_W2C, DEFAULT_MBS_NETWORK_SERDES_BANDWIDTH, true);
	private static final double NETWORK_LATENCY_C2W = captureOneWayLatency(ENV_NETWORK_LATENCY_C2W);
	private static final double NETWORK_LATENCY_W2C = captureOneWayLatency(ENV_NETWORK_LATENCY_W2C);
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

	private static double captureOneWayLatency(String directionKey) {
		String value = FederatedPlannerConfiguration.captureNonEmptyPropertyOrEnvironment(directionKey);
		return value == null || value.isBlank() ? DEFAULT_ONE_WAY_LATENCY
			: parseLatencySeconds(directionKey, value);
	}

	private static double captureNetworkThroughput(String key, double defaultValue, boolean allowZero) {
		String value = FederatedPlannerConfiguration.captureNonEmptyPropertyOrEnvironment(key);
		if(value == null || value.isBlank())
			return defaultValue;
		try {
			double rate = Double.parseDouble(value);
			if(Double.isFinite(rate) && (rate > 0.0 || allowZero && rate == 0.0))
				return rate;
		}
		catch(NumberFormatException ignored) {
			// Explicit invalid configuration must not silently become a different profile.
		}
		throw new IllegalArgumentException("FED_COST_INVALID_NETWORK_THROUGHPUT: " + key + "=" + value);
	}

	private static double parseLatencySeconds(String key, String value) {
		try {
			double seconds = Double.parseDouble(value);
			if(Double.isFinite(seconds) && seconds >= 0.0)
				return seconds;
		}
		catch(NumberFormatException ignored) {
			// Invalid explicit latency is a configuration error, not an implicit zero/default.
		}
		throw new IllegalArgumentException("FED_COST_INVALID_LATENCY: " + key + "=" + value);
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
	 * <p>Each worker still computes only its X partition. The full partial-result
	 * {@code GET_VAR} and coordinator aggregation are separate stages modeled by
	 * {@link #computeMixedFedLocalCost(Hop, List, List, FType, double, double, int)}.
	 * Replacing the partitioned worker cost with the unscaled coordinator cost would
	 * count that worker work as serial and then charge the coordinator stage again.</p>
	 */
	public static double adjustFederatedComputeCostForWdivmmLocalAggregation(Hop hop,
			FType logicalFType, double baseSelfCost, double defaultFederatedComputeCost) {
		return defaultFederatedComputeCost;
	}

	public static double computeFederatedComputeCost(Hop hop, double baseSelfCost,
			int numWorkers, boolean broadcastOnlyFedCompute) {
		if (broadcastOnlyFedCompute)
			return baseSelfCost;
		return baseSelfCost / Math.max(1, numWorkers);
	}

	/**
	 * Returns whether every matrix input is coordinator-local or explicitly replicated.
	 * Such a FED instruction performs redundant full-input work on every worker and must
	 * not receive partition-based compute scaling. A non-replicated FType proves that at
	 * least one input supplies independent worker shards.
	 */
	public static boolean hasOnlyBroadcastMatrixInputs(List<Hop> inputHops, List<FType> inputFTypes) {
		if (inputHops == null || inputHops.isEmpty())
			return false;
		boolean hasMatrixInput = false;
		for (int position = 0; position < inputHops.size(); position++) {
			Hop input = inputHops.get(position);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			hasMatrixInput = true;
			FType inputType = inputFTypes != null && position < inputFTypes.size()
				? inputFTypes.get(position) : null;
			if (inputType != null && inputType != FType.BROADCAST)
				return false;
		}
		return hasMatrixInput;
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
	 * <p>This helper does not close any candidate; it only gives DP and Exact a
	 * shared reduced-output cost for the runtime-supported native FED output path.
	 * Scalar aggregates are excluded because runtime cannot represent scalar
	 * outputs as federated variables.</p>
	 */
	public static double computeNativeFederatedAggregateUnaryCost(Hop hop,
			FType logicalFType, double defaultFederatedComputeCost) {
		return defaultFederatedComputeCost;
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
	 * request and response latency. Native aggregate-unary LOUT is different: the reduced
	 * result is returned as part of the federated aggregate instruction response.
	 * The instruction's two directional stages are already represented by
	 * {@link #computeFederatedInstructionNetworkCost(Hop, double)}, so this helper charges only the
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
	 * request/response batch. The ordinary FED execution term therefore owns the
	 * request and response directional latencies; this {@code FED/LOUT} result term
	 * owns only the returned payload transfer. Coordinator binding remains part of
	 * the runtime semantics but has no separately quantified cost here. A later
	 * standalone FOUT materialization remains a separate request and retains its
	 * own stage.</p>
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
		int fanIn = logicalFType == FType.FULL || logicalFType == FType.BROADCAST
			? 1 : Math.max(1, numWorkers);
		double resultCost = computeInBandWorkerResultDownloadCost(resultMemEstimate, fanIn, false);
		return resultCost > 0.0 ? resultCost : genericResultDownloadCost;
	}

	/** Total payload in full-output equivalents, NOT the number of worker responses. */
	private static double estimateNativeAggregateUnaryPayloadMultiplier(AggUnaryOp aggregate,
			FType logicalFType, int numWorkers) {
		if (aggregate == null || logicalFType == FType.FULL)
			return 1.0;
		// processGetOutput sends compute + GET to the entire replicated map.
		// Each response holds a full result even though the coordinator adopts one.
		if (logicalFType == FType.BROADCAST)
			return Math.max(1, numWorkers);
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
	 * fully materialized local input. Worker overlap and slice quantities are projected
	 * by the prepared execution layout; this compatibility hook must not add a second
	 * post-hoc cap or floor. The intrinsic network stages still charge request and
	 * response latencies per execution, so latency-sensitive plans can choose
	 * CP/LOUT by cost without closing any FED candidate.</p>
	 *
	 * <p>This helper keeps all candidates open and is shared by DP and Exact. It is
	 * based only on operation semantics and static size estimates, not workload,
	 * worker-count, row-id, or hop-id rules.</p>
	 */
	public static double computeNativeFederatedIndexingCost(Hop hop,
			FType logicalFType, double defaultFederatedComputeCost) {
		return defaultFederatedComputeCost;
	}

	/**
	 * Runtime-stage cost for CP/local indexing/slicing.
	 *
	 * <p>Once a federated input is materialized locally, a CP {@code rightIndex}
	 * does not repeatedly scan the full source matrix for every slice.  Its local
	 * arithmetic/memory term is bounded by the selected slice/output payload; the
	 * one-time FOUT-to-local materialization remains modeled by the planner's
	 * boundary/result edges.  This mirrors the native FED indexing helper without
	 * closing any FED candidate, so DP and Exact compare the same staged operation
	 * semantics on both sides.</p>
	 */
	public static double computeLocalIndexingCostWithFallback(Hop hop, double defaultLocalCost) {
		return defaultLocalCost;
	}

	/**
	 * The latency contributions of one C2W request and one W2C response per remote
	 * instruction. In-band payload factors contribute bytes to these same stages;
	 * they do not create more latency. Parallel worker fanout does not multiply the
	 * stage count. Transient reads/writes create no remote instruction; a transpose
	 * does, even when its FULL layout is preserved. Compiler-elided kernels are
	 * excluded by the prepared execution cost, not by FType exceptions here.
	 */
	public static double computeFederatedInstructionNetworkCost(Hop hop, double execWeight) {
		if (hop == null || hop instanceof DataOp)
			return 0.0;
		return Math.max(0.0, execWeight) * computeRequestResponseLatency(
			NETWORK_LATENCY_C2W, NETWORK_LATENCY_W2C);
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
		return computeMixedFedLocalCost(hop, inputHops, null, inputFTypes, logicalFType,
			baseSelfCost, outputMemEstimate, numWorkers);
	}

	/**
	 * Occurrence-aware variant whose input byte estimates come from the immutable
	 * whole-program analysis when the compiled HOP still has unresolved dimensions.
	 * A non-positive or non-finite entry falls back to the ordinary HOP estimate.
	 */
	public static MixedFedLocalCost computeMixedFedLocalCost(Hop hop, List<Hop> inputHops,
			List<Double> inputMemEstimates, List<FType> inputFTypes, FType logicalFType,
			double baseSelfCost, double outputMemEstimate, int numWorkers) {
		return computeMixedFedLocalCost(hop, inputHops, inputMemEstimates, inputFTypes, logicalFType,
			baseSelfCost, outputMemEstimate, numWorkers, null);
	}

	public static MixedFedLocalCost computeMixedFedLocalCost(Hop hop, List<Hop> inputHops,
			List<Double> inputMemEstimates, List<FType> inputFTypes, FType logicalFType,
			double baseSelfCost, double outputMemEstimate, int numWorkers, FederatedExecutionLayout layout) {
		double auxiliaryCost = computeAuxiliaryStageCost(hop, inputHops, inputMemEstimates,
			inputFTypes, logicalFType, outputMemEstimate, numWorkers, layout);
		if (requiresFederatedAggUnaryLocalAggregation(hop)) {
			return computeAggregateUnaryLocalAggregationCost("agg-unary-local-aggregation",
				(AggUnaryOp) hop, logicalFType, outputMemEstimate, numWorkers, auxiliaryCost);
		}
		double wdivmmInputPreparationCost =
			computeWdivmmInputPreparationCost(hop, inputHops, inputMemEstimates,
				inputFTypes, numWorkers, layout);
		if (requiresFederatedWdivmmLocalAggregation(hop, logicalFType)) {
			return computePartialAggregationCost("wdivmm-local-aggregation",
				hop, outputMemEstimate, numWorkers, wdivmmInputPreparationCost);
		}
		if (wdivmmInputPreparationCost > 0.0) {
			return new MixedFedLocalCost("wdivmm-input-preparation",
				wdivmmInputPreparationCost, 0.0, 0.0);
		}
		if (requiresFederatedAggBinaryRowLeftInputPreparation(hop, inputFTypes)) {
			double inputPreparationCost =
				computeAggBinaryRowLeftInputPreparationCost(hop, inputHops,
					inputMemEstimates, inputFTypes, numWorkers);
			return new MixedFedLocalCost("aggbinary-rowleft-input-prep",
				inputPreparationCost, 0.0, 0.0);
		}
		if (requiresFederatedAggBinaryAddAggregation(hop, inputFTypes)) {
			double inputPreparationCost =
				computeAggBinarySlicedInputBroadcastCost(hop, inputHops,
					inputMemEstimates, inputFTypes, numWorkers);
			return computePartialAggregationCost("aggbinary-add-aggregation",
				hop, outputMemEstimate, numWorkers, inputPreparationCost);
		}
		return auxiliaryCost > 0.0
			? new MixedFedLocalCost("auxiliary-runtime-stages", auxiliaryCost, 0.0, 0.0)
			: MixedFedLocalCost.none();
	}

	/**
	 * Extra runtime batches, excluding the principal kernel and its result. Each
	 * execute fanout owns one RTT, irrespective of worker count. Payload and work
	 * use the same primitives as ordinary kernels, with generated intermediates'
	 * own shapes rather than the final HOP output size.
	 */
	private static double computeAuxiliaryStageCost(Hop hop, List<Hop> inputs,
			List<Double> inputBytes, List<FType> types, FType executionType, double outputBytes,
			int numWorkers, FederatedExecutionLayout layout) {
		int workers = executionType == FType.FULL ? 1 : Math.max(1, numWorkers);
		double latency = computeRequestResponseLatency();
		if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.RESHAPE)
			// Reshape initializes output metadata in a separate PUT batch (no MatrixBlock).
			return latency;
		if(hop instanceof AggUnaryOp aggregate && aggregate.getOp() == AggOp.VAR) {
			double partial = estimateAggregateUnaryResultMemEstimate(aggregate, outputBytes);
			double multiplier = estimateNativeAggregateUnaryPayloadMultiplier(aggregate, executionType, workers);
			double share = auxiliaryWorkerShare(types, 0, workers, layout);
			double bytes = auxiliaryInputBytes(inputs, inputBytes, 0);
			return latency + computeAggregateUnaryPartialResultDownloadCost(aggregate, executionType, partial, workers)
				+ computeExecutionCost(null, estimateLogicalCellCount(inputHopAt(inputs, 0), bytes) * share,
					bytes * share, partial * multiplier / workers);
		}
		if(isAlignedCovariance(hop, types)) {
			boolean weighted = hop instanceof TernaryOp;
			int stages = weighted ? 3 : 2;
			double cost = stages * (latency + computeReplicatedWorkerResultDownloadCost(8, workers));
			for(int i = 0; i < 2; i++) {
				double bytes = auxiliaryInputBytes(inputs, inputBytes, i);
				double share = auxiliaryWorkerShare(types, i, workers, layout);
				double cells = estimateLogicalCellCount(inputHopAt(inputs, i), bytes) * share;
				if(weighted) {
					double weights = auxiliaryInputBytes(inputs, inputBytes, 2) * share;
					double product = cells * 8;
					cost += computeExecutionCost(null, cells, bytes * share + weights, product)
						+ computeExecutionCost(null, 4 * cells, product, 8)
						+ computeExecutionCost(null, 4 * cells, weights, 8)
						+ computeExecutionCost(null, 1, 16, 8);
				}
				else
					cost += computeExecutionCost(null, cells, bytes * share, 8);
			}
			if(weighted) {
				double bytes = auxiliaryInputBytes(inputs, inputBytes, 2);
				double share = auxiliaryWorkerShare(types, 0, workers, layout);
				cost += computeExecutionCost(null, 4 * estimateLogicalCellCount(inputHopAt(inputs, 2), bytes) * share,
					bytes * share, 8);
			}
			// Two global means and the covariance merge: 13 operations per worker,
			// two mean divisions, and the final degrees-of-freedom adjustment/division.
			return cost + computeExecutionCost(null, 13.0 * workers + 4, 8.0 * workers * (weighted ? 7 : 5), 8);
		}
		if(hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE) {
			inputs = inputs != null ? inputs : hop.getInput();
			double cost = 0;
			for(int i = ternary.isSequenceRewriteApplicable(true) ? 1 : 0; i < 2; i++) {
				Hop input = inputHopAt(inputs, i);
				if(input == null || !input.getDataType().isMatrix())
					continue;
				double bytes = auxiliaryInputBytes(inputs, inputBytes, i);
				if(typeAt(types, i) == null) {
					// Local dimension discovery slices then scans every range, without RPC.
					cost += 2 * computeMemoryAccessCost(bytes)
						+ computeExecutionCost(null, estimateLogicalCellCount(input, bytes), bytes, 8.0 * workers)
						+ computeExecutionCost(null, workers, 8.0 * workers, 8);
					continue;
				}
				int responses = layout != null && i < layout.inputs().size() && layout.inputs().get(i).exactRanges()
					? layout.inputs().get(i).ranges().size() : typeAt(types, i) == FType.FULL ? 1 : workers;
				double share = auxiliaryWorkerShare(types, i, responses, layout);
				cost += latency + computeReplicatedWorkerResultDownloadCost(8, responses)
					+ computeExecutionCost(null, estimateLogicalCellCount(inputHopAt(inputs, i), bytes) * share,
						bytes * share, 8)
					+ computeExecutionCost(null, responses, 8.0 * responses, 8);
			}
			FederatedExecutionLayout actualLayout = layout != null ? layout
				: new FederatedExecutionLayout(executionType, workers, java.util.stream.IntStream.range(0, inputs.size())
					.mapToObj(position -> new InputLayout(typeAt(types, position), List.of(), false)).toList());
			for(int position = 0; position < Math.min(3, inputs.size()); position++) {
				if(!PlacementCostSemantics.ctableInputNeedsCollection(hop, actualLayout, position))
					continue;
				double bytes = auxiliaryInputBytes(inputs, inputBytes, position);
				InputLayout primary = actualLayout.inputs().get(actualLayout.inputs().get(0).fType() != null ? 0 : 1);
				int partitions = primary.exactRanges() ? primary.ranges().size() : workers;
				// GET/cache activation is owned by the materialization collector. Slicing
				// and PUT occur on every CTABLE call, including warm/local inputs. A
				// full, replicated or singleton map broadcasts without copying slices.
				cost += computeInBandUploadPayloadCost(bytes, primary.fType(), workers);
				if(primary.fType() != FType.FULL && primary.fType() != FType.BROADCAST && partitions > 1)
					cost += 2 * computeMemoryAccessCost(bytes);
				if(position < 2)
					// isFedOutput scans secondary slices for min/max even for forced LOUT.
					cost += 2 * computeMemoryAccessCost(bytes)
						+ computeExecutionCost(null, 2 * estimateLogicalCellCount(inputHopAt(inputs, position), bytes),
							2 * bytes, 16.0 * workers);
			}
			return cost;
		}
		if(hop instanceof UnaryOp unary && typeAt(types, 0) == FType.ROW
			&& (unary.getOp() == OpOp1.CUMSUM || unary.getOp() == OpOp1.CUMPROD
				|| unary.getOp() == OpOp1.CUMMIN || unary.getOp() == OpOp1.CUMMAX
				|| unary.getOp() == OpOp1.CUMSUMPROD))
			return computeRowCumulativeAuxiliaryCost(unary, inputs, inputBytes, types, workers, layout);
		return 0.0;
	}

	/**
	 * Logical input PUT ownership, separate from auxiliary RTT/compute costs.
	 * A positive preparation cost alone does not prove that an input was uploaded.
	 */
	public static boolean modelsNativeInputUpload(Hop hop, List<FType> types, int position) {
		if(hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE)
			// Specialized CTABLE preparation also accounts for the erased expand marker (zero PUT).
			return position >= 0 && position < 3;
		if(hop instanceof QuaternaryOp q && q.getOp() == OpOp4.WDIVMM)
			return (typeAt(types, 0) == FType.ROW || typeAt(types, 0) == FType.COL
				|| typeAt(types, 0) == FType.FULL)
				&& (PlacementCostSemantics.isWdivmmMatrixOperand(hop, position)
					|| position == 3 && PlacementCostSemantics.hasWdivmmEpsilon(hop));
		if(!(hop instanceof AggBinaryOp multiply) || !multiply.isMatrixMultiply())
			return false;
		if(requiresFederatedAggBinaryRowLeftInputPreparation(hop, types))
			return position == 1;
		if(multiply.checkTransposeSelf() != org.apache.sysds.lops.MMTSJ.MMTSJType.NONE)
			return false;
		return position == 0 && typeAt(types, 0) == null && typeAt(types, 1) == FType.ROW
			|| position == 1 && typeAt(types, 0) == FType.COL && typeAt(types, 1) == null;
	}

	private static double computeRowCumulativeAuxiliaryCost(UnaryOp hop, List<Hop> inputs,
			List<Double> inputBytes, List<FType> types, int workers, FederatedExecutionLayout layout) {
		Hop input = inputHopAt(inputs, 0);
		double bytes = auxiliaryInputBytes(inputs, inputBytes, 0);
		double cells = estimateLogicalCellCount(input, bytes);
		double share = auxiliaryWorkerShare(types, 0, workers, layout);
		boolean sumProduct = hop.getOp() == OpOp1.CUMSUMPROD;
		double rows = input.getDim1(), cols = input.getDim2();
		if(layout != null && !layout.inputs().isEmpty() && layout.inputs().get(0).exactRanges()) {
			if(rows <= 0)
				rows = layout.inputs().get(0).ranges().stream().mapToLong(range -> range.end().get(0)).max().orElse(0);
			if(cols <= 0)
				cols = layout.inputs().get(0).ranges().stream().mapToLong(range -> range.end().get(1)).max().orElse(0);
		}
		if(cols <= 0)
			cols = sumProduct ? 2 : 1;
		if(rows <= 0)
			rows = Math.max(1, cells / cols);
		cells = rows * cols;
		// The generated correction has only boundary entries for SUM; PROD/MIN/MAX
		// initialize the entire matrix to a nonzero identity, regardless of input sparsity.
		double correction = generatedMatrixBytes(rows, cols,
			hop.getOp() == OpOp1.CUMSUM ? Math.max(0, workers - 1) * cols : cells);
		double cost = (sumProduct ? 3 : 2) * computeRequestResponseLatency();
		if(sumProduct) {
			double firstResult = rows * 8;
			double products = cells * 8;
			cost += computeInBandWorkerResultDownloadCost(firstResult, workers, false)
				+ computeInBandWorkerResultDownloadCost(products, workers, false)
				+ computeExecutionCost(null, 2 * rows * share, bytes * share, firstResult * share)
				+ computeExecutionCost(null, cells * share, bytes * share, products * share);
			correction += generatedMatrixBytes(rows, cols, Math.max(0, workers - 1));
		}
		else {
			double partial = cols * 8;
			cost += computeReplicatedWorkerResultDownloadCost(partial, workers)
				+ computeExecutionCost(null, (hop.getOp() == OpOp1.CUMSUM ? 4 : 1) * cells * share,
					bytes * share, partial);
		}
		return cost + computeInBandUploadPayloadCost(correction, FType.ROW, workers)
			+ computeExecutionCost(null, cells * share, (bytes + correction) * share, cells * 8 * share)
			+ computeExecutionCost(null, workers * cols * (sumProduct ? 4 : 1),
				workers * cols * 8, workers * cols * 8)
			// Create correction matrices, then read/copy their slices for upload.
			+ 3 * computeMemoryAccessCost(correction);
	}

	private static double generatedMatrixBytes(double rows, double cols, double nonzeros) {
		return MatrixBlock.estimateSizeInMemory((long) rows, (long) cols,
			Math.min(1.0, nonzeros / Math.max(1.0, rows * cols)));
	}

	private static double auxiliaryInputBytes(List<Hop> inputs, List<Double> estimates, int index) {
		double bytes = inputMemEstimateAt(estimates, index);
		return Double.isFinite(bytes) && bytes > 0 ? bytes : getEffectiveOutputMemEstimate(inputHopAt(inputs, index));
	}

	private static double auxiliaryWorkerShare(List<FType> types, int index, int workers,
			FederatedExecutionLayout layout) {
		FType type = typeAt(types, index);
		if(type == FType.FULL || type == FType.BROADCAST)
			return 1;
		if(layout != null && index < layout.inputs().size() && layout.inputs().get(index).exactRanges()) {
			double total = 0, largest = 0;
			for(var range : layout.inputs().get(index).ranges()) {
				double cells = (range.end().get(0) - range.begin().get(0))
					* (double) (range.end().get(1) - range.begin().get(1));
				total += cells;
				largest = Math.max(largest, cells);
			}
			if(total > 0)
				return largest / total;
		}
		return 1.0 / workers;
	}

	private static boolean isAlignedCovariance(Hop hop, List<FType> types) {
		return (hop instanceof BinaryOp binary && binary.getOp() == OpOp2.COV
			|| hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.COV)
			&& typeAt(types, 0) != null && typeAt(types, 1) != null;
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
		return computeAggBinaryRowLeftInputPreparationCost(hop, inputHops, null,
			inputFTypes, numWorkers);
	}

	private static double computeAggBinaryRowLeftInputPreparationCost(Hop hop,
			List<Hop> inputHops, List<Double> inputMemEstimates,
			List<FType> inputFTypes, int numWorkers) {
		if (!requiresFederatedAggBinaryRowLeftInputPreparation(hop, inputFTypes))
			return 0.0;
		return computeFullBroadcastInputCost(inputHopAt(inputHops, 1),
			inputMemEstimateAt(inputMemEstimates, 1), numWorkers);
	}

	private static MixedFedLocalCost computePartialAggregationCost(String label, Hop hop,
			double outputMemEstimate, int numWorkers, double inputPreparationCost) {
		double partialResultMem = outputMemEstimate > 0.0 ? outputMemEstimate : getEffectiveOutputMemEstimate(hop);
		if (partialResultMem <= 0.0)
			partialResultMem = getEffectiveUploadMemEstimate(hop);
		if (partialResultMem <= 0.0)
			return new MixedFedLocalCost(label, inputPreparationCost, 0.0, 0.0);

		int fanIn = Math.max(1, numWorkers);
		double partialDownloadCost = computeReplicatedWorkerResultDownloadCost(partialResultMem, fanIn);
		double coordinatorAggregationCost = computeCoordinatorAggregationCost(hop, partialResultMem, fanIn);
		return new MixedFedLocalCost(label, inputPreparationCost, partialDownloadCost,
			coordinatorAggregationCost);
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
			aggregateUnary, logicalFType, partialResultMem, workers);
		return new MixedFedLocalCost(label, inputPreparationCost, partialDownloadCost,
			coordinatorAggregationCost);
	}

	private static double computeAggregateUnaryPartialResultDownloadCost(AggUnaryOp aggregateUnary,
			FType logicalFType, double partialResultMem, int numWorkers) {
		if (partialResultMem <= 0.0)
			return 0.0;
		double payloadMultiplier = estimateNativeAggregateUnaryPayloadMultiplier(
			aggregateUnary, logicalFType, Math.max(1, numWorkers));
		double totalPayloadMem = partialResultMem * Math.max(1.0, payloadMultiplier);
		return computeCalibratedGetResponsePayloadCost(totalPayloadMem,
			Math.max(1, numWorkers));
	}

	private static double computeAggregateUnaryCoordinatorAggregationCost(AggUnaryOp aggregateUnary,
			FType logicalFType, double partialResultMem, int fanIn) {
		int workers = Math.max(1, fanIn);
		// BROADCAST adopts an existing result: no payload scan/copy or arithmetic.
		// Request bookkeeping and deserialization are already priced elsewhere.
		// Scalar variance is different: processVar calls the mean/variance scalar
		// merge overload, which does not have the replicated select-first branch.
		boolean scalarVariance = aggregateUnary.getOp() == AggOp.VAR
			&& aggregateUnary.getDataType() != null && aggregateUnary.getDataType().isScalar();
		if (logicalFType == FType.BROADCAST && !scalarVariance)
			return 0.0;
		// Aligned-axis aggregates bind W disjoint responses. Their combined bytes
		// are ONE full output, not W full outputs; no elementwise reduction occurs.
		// Network fan-in remains W: payload-equivalent count is not response count.
		if ((logicalFType == FType.ROW && aggregateUnary.getDirection() == Direction.Row)
			|| (logicalFType == FType.COL && aggregateUnary.getDirection() == Direction.Col))
			return 2 * computeMemoryAccessCost(partialResultMem);
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
		return computeWdivmmInputPreparationCost(hop, inputHops, null, inputFTypes, numWorkers, null);
	}

	public static double computeWdivmmInputPreparationCost(Hop hop, List<Hop> inputHops,
			List<FType> inputFTypes, int numWorkers, FederatedExecutionLayout layout) {
		return computeWdivmmInputPreparationCost(hop, inputHops, null, inputFTypes, numWorkers, layout);
	}

	private static double computeWdivmmInputPreparationCost(Hop hop, List<Hop> inputHops,
			List<Double> inputMemEstimates, List<FType> inputFTypes, int numWorkers,
			FederatedExecutionLayout layout) {
		if(!(hop instanceof QuaternaryOp q) || q.getOp() != OpOp4.WDIVMM) return 0.0;
		FType weights = typeAt(inputFTypes, 0);
		if(layout != null && !layout.inputs().isEmpty()) weights = layout.inputs().get(0).fType();
		if(weights != FType.ROW && weights != FType.FULL && weights != FType.COL) return 0.0;
		double cost = 0.0;
		for(int position = 1; position < hop.getInput().size(); position++) {
			if(!PlacementCostSemantics.isWdivmmMatrixOperand(hop, position)
				|| !PlacementCostSemantics.wdivmmInputNeedsCollection(hop, layout, position)) continue;
			boolean sliced = position == 1 && (weights == FType.ROW || weights == FType.FULL)
				|| position == 2 && weights == FType.COL || position == 3;
			cost += sliced ? computeSlicedBroadcastInputCost(inputHopAt(inputHops, position),
				inputMemEstimateAt(inputMemEstimates, position), numWorkers)
				: computeFullBroadcastInputCost(inputHopAt(inputHops, position),
					inputMemEstimateAt(inputMemEstimates, position), numWorkers);
		}
		// Matrix-valued EPS is read at (0,0) on the coordinator; runtime sends one scalar.
		if(PlacementCostSemantics.hasWdivmmEpsilon(hop))
			cost += computeInBandUploadPayloadCost(8.0, FType.BROADCAST, numWorkers);
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
		return computeAggBinarySlicedInputBroadcastCost(hop, inputHops, null,
			inputFTypes, numWorkers);
	}

	private static double computeAggBinarySlicedInputBroadcastCost(Hop hop,
			List<Hop> inputHops, List<Double> inputMemEstimates,
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
			return computeSlicedBroadcastInputCost(inputHopAt(inputHops, 0),
				inputMemEstimateAt(inputMemEstimates, 0), numWorkers);
		}
		if (left == FType.COL) {
			if (isFederatedFullOrBroadcast(right))
				return 0.0;
			return computeSlicedBroadcastInputCost(inputHopAt(inputHops, 1),
				inputMemEstimateAt(inputMemEstimates, 1), numWorkers);
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

	private static double inputMemEstimateAt(List<Double> inputMemEstimates, int index) {
		if(inputMemEstimates == null || index < 0 || index >= inputMemEstimates.size())
			return Double.NaN;
		Double estimate = inputMemEstimates.get(index);
		return estimate == null ? Double.NaN : estimate;
	}

	private static double computeSlicedBroadcastInputCost(Hop inputHop, int numWorkers) {
		return computeSlicedBroadcastInputCost(inputHop, Double.NaN, numWorkers);
	}

	private static double computeSlicedBroadcastInputCost(Hop inputHop,
			double occurrenceMemEstimate, int numWorkers) {
		double memEstimate = Double.isFinite(occurrenceMemEstimate) && occurrenceMemEstimate > 0.0
			? occurrenceMemEstimate : getEffectiveOutputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveUploadMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveInputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			return 0.0;

		// broadcastSliced is part of the same FederationMap.execute request batch as
		// the instruction. FED execution owns the request and response latencies,
		// so this preparation term contributes payload/codec time only.
		return computeInBandUploadPayloadCost(memEstimate, FType.ROW, numWorkers);
	}

	private static double computeFullBroadcastInputCost(Hop inputHop, int numWorkers) {
		return computeFullBroadcastInputCost(inputHop, Double.NaN, numWorkers);
	}

	private static double computeFullBroadcastInputCost(Hop inputHop,
			double occurrenceMemEstimate, int numWorkers) {
		double memEstimate = Double.isFinite(occurrenceMemEstimate) && occurrenceMemEstimate > 0.0
			? occurrenceMemEstimate : getEffectiveOutputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveUploadMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			memEstimate = getEffectiveInputMemEstimate(inputHop);
		if (memEstimate <= 0.0)
			return 0.0;
		return computeInBandUploadPayloadCost(memEstimate, FType.BROADCAST, numWorkers);
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
		return computeCalibratedGetResponsePayloadCost(memSizePerWorker * workers, workers);
	}

	private static double computeInBandWorkerResultDownloadCost(double resultMem, int fanIn,
			boolean replicatedResultPerWorker) {
		if (resultMem <= 0.0)
			return 0.0;
		int workers = Math.max(1, fanIn);
		double payloadMem = replicatedResultPerWorker ? resultMem * workers : resultMem;
		return computeCalibratedGetResponsePayloadCost(payloadMem, workers);
	}

	/**
	 * Payload-only critical path for results returned inside one FED request batch.
	 *
	 * <p>{@code FederationMap.execute} submits all worker requests before any result
	 * is consumed, while aggregate response-processing work is charged over total bytes. Coordinator
	 * binding/aggregation is modeled separately by the caller. GET response payloads use
	 * one purpose-independent calibration; this helper adds no request batch.</p>
	 */
	private static double computeParallelInBandResultPayloadCost(double totalMemSize, int fanIn,
			double bandwidthMBps, double serdesBwMBps) {
		return computeGetResponsePayloadCost(totalMemSize, fanIn, bandwidthMBps,
			serdesBwMBps);
	}

	private static double computeCalibratedGetResponsePayloadCost(double totalMemSize, int fanIn) {
		return computeGetResponsePayloadCost(totalMemSize, fanIn,
			MBS_NETWORK_BANDWIDTH_W2C, MBS_NETWORK_SERDES_BANDWIDTH_W2C);
	}

	private static double computeCoordinatorAggregationCost(Hop hop, double partialResultMem, int fanIn) {
		int workers = Math.max(1, fanIn);
		if (workers <= 1)
			return computeMemoryAccessCost(partialResultMem);

		double outputCells = hop == null ? partialResultMem / OptimizerUtils.DOUBLE_SIZE
			: estimateLogicalCellCount(hop, partialResultMem);
		double aggregateFlops = Math.max(0, workers - 1) * Math.max(0.0, outputCells);
		double aggregateComputeCost = (aggregateFlops / getComputeFlopsPerSec(hop)) * TO_MS;
		double aggregateReadCost = computeMemoryAccessCost(partialResultMem * workers);
		double aggregateWriteCost = computeMemoryAccessCost(partialResultMem);
		return Math.max(aggregateComputeCost, aggregateReadCost) + aggregateWriteCost;
	}

	public static double computeOpCost(Hop currentHop) {
		return computeOpCost(currentHop, 0.0);
	}

	/** Compatibility overload using a minimum compute-work quantity, in FLOPs. */
	public static double computeOpCost(Hop currentHop, double minimumComputeFlops) {
		double inputMemEstimate = getEffectiveInputMemEstimate(currentHop);
		double outputMemEstimate = getEffectiveOutputMemEstimate(currentHop);
		return computeOpCost(currentHop, minimumComputeFlops,
			inputMemEstimate, outputMemEstimate);
	}

	private static double computeOpCost(Hop currentHop, double minimumComputeFlops,
			double inputMemEstimate, double outputMemEstimate) {
		return computeOpCost(currentHop, minimumComputeFlops, inputMemEstimate,
			outputMemEstimate, ComputeCost.getHOPComputeCost(currentHop));
	}

	private static double computeOpCost(Hop currentHop, double minimumComputeFlops,
			double inputMemEstimate, double outputMemEstimate, double computeCost) {
		double flops = Math.max(Double.isFinite(computeCost) ? computeCost : 0.0,
			Double.isFinite(minimumComputeFlops) ? minimumComputeFlops : 0.0);
		return computeExecutionCost(currentHop, flops, inputMemEstimate, outputMemEstimate);
	}

	/**
	 * One execution primitive for CP and worker kernels:
	 * {@code max(flops/rho, readBytes/mu) + writeBytes/mu}.
	 */
	public static double computeExecutionCost(Hop hop, double flops,
			double readBytes, double writeBytes) {
		double safeFlops = Double.isFinite(flops) && flops > 0.0 ? flops : 0.0;
		double safeRead = Double.isFinite(readBytes) && readBytes > 0.0 ? readBytes : 0.0;
		double safeWrite = Double.isFinite(writeBytes) && writeBytes > 0.0 ? writeBytes : 0.0;
		double computeTime = safeFlops / getComputeFlopsPerSec(hop) * TO_MS;
		double readTime = computeMemoryAccessCost(safeRead);
		return Math.max(computeTime, readTime) + computeMemoryAccessCost(safeWrite);
	}


	private static double getComputeFlopsPerSec(Hop hop) {
		if (hop instanceof AggBinaryOp && hop.getDataType() != null && hop.getDataType().isMatrix())
			return AGGBINARY_FLOPS_PER_SEC;
		return FLOPS_PER_SEC;
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
		return computeOpCostWithFallback(hop, 0.0);
	}

	/** See {@link #computeOpCost(Hop, double)}. */
	public static double computeOpCostWithFallback(Hop hop,
			double minimumComputeFlops) {
		return computeOpCostWithFallback(hop, minimumComputeFlops,
			Double.NaN, Double.NaN);
	}

	/**
	 * Occurrence-aware ordinary HOP cost. Positive finite byte estimates replace
	 * the memory-derived inputs to the existing formula; missing estimates retain
	 * all existing HOP fallbacks and compute-work estimates.
	 */
	public static double computeOpCostWithFallback(Hop hop,
			double minimumComputeFlops, double inputMemEstimate,
			double outputMemEstimate) {
		return computeOpCostWithFallback(hop, minimumComputeFlops, inputMemEstimate,
			outputMemEstimate, Double.NaN);
	}

	/** Same primitive and kernel corrections, with occurrence-exact ordinary FLOPs. */
	public static double computeOpCostWithFallback(Hop hop,
			double minimumComputeFlops, double inputMemEstimate,
			double outputMemEstimate, double computeFlops) {
		if (hop == null) {
			return 0.0;
		}

		double effectiveInputMemEstimate = positiveFinite(inputMemEstimate)
			? inputMemEstimate : getEffectiveInputMemEstimate(hop);
		double effectiveOutputMemEstimate = positiveFinite(outputMemEstimate)
			? outputMemEstimate : getEffectiveOutputMemEstimate(hop);
		double opCost = computeOpCost(hop, minimumComputeFlops,
			effectiveInputMemEstimate, effectiveOutputMemEstimate,
			Double.isFinite(computeFlops) && computeFlops >= 0.0
				? computeFlops : ComputeCost.getHOPComputeCost(hop));
		if (opCost > 0.0) {
			return opCost;
		}

		if (effectiveInputMemEstimate <= 0.0 && effectiveOutputMemEstimate <= 0.0) {
			return 0.0;
		}

		double inputAccessCost = computeMemoryAccessCost(effectiveInputMemEstimate);
		double outputAccessCost = computeMemoryAccessCost(effectiveOutputMemEstimate);
		return inputAccessCost + outputAccessCost;
	}

	private static boolean positiveFinite(double estimate) {
		return Double.isFinite(estimate) && estimate > 0.0;
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
	public static double getSemanticSparseAssignmentMemEstimate(Hop hop) {
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
		// Concrete statistics are authoritative. The semantic rule below is only an
		// expected-cardinality fallback for the common unknown-NNZ planning case.
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix()
				|| hop.getNnz() >= 0)
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
		long cells = rows > Long.MAX_VALUE / cols ? Long.MAX_VALUE : rows * cols;
		long nnz = Math.min(cells, Math.max(1, source.getDim1()));
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
			long cells = transposedRows > Long.MAX_VALUE / transposedCols
				? Long.MAX_VALUE : transposedRows * transposedCols;
			return new SparseAssignmentShape(transposedRows, transposedCols,
				Math.min(cells, nnz));
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
				|| !PlacementCostSemantics.isMultiReturnFunctionOutput(sourceHop)) {
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

	public static double computeRequestResponseLatency() {
		return computeRequestResponseLatency(NETWORK_LATENCY_C2W, NETWORK_LATENCY_W2C);
	}

	public static double computeDownloadNetworkCost(double memSize) {
		return computeGetResponseCost(memSize, 1,
			MBS_NETWORK_BANDWIDTH_W2C, MBS_NETWORK_SERDES_BANDWIDTH_W2C, 1,
			NETWORK_LATENCY_C2W, NETWORK_LATENCY_W2C);
	}

	public static double computeDownloadNetworkCost(double memSize, FType fType, int numWorkers) {
		return computeResultGetCost(memSize, fType, numWorkers, 1);
	}

	/** Runtime-specific result batch ownership; all payloads still use the same GET policy. */
	public static double computeNativeFederatedLoutResultCost(Hop hop, FType executionType,
			double bytes, int workers) {
		return computeNativeFederatedLoutResultCost(hop, null, executionType, bytes, workers);
	}

	public static double computeNativeFederatedLoutResultCost(Hop hop, List<FType> inputFTypes,
			FType executionType, double bytes, int workers) {
		if(isAlignedCovariance(hop, inputFTypes))
			return computeReplicatedWorkerResultDownloadCost(8, executionType == FType.FULL ? 1 : workers);
		boolean inBand = nativeResultIsInBand(hop);
		if(hop instanceof QuaternaryOp quaternary && quaternary.getOp() == OpOp4.WDIVMM)
			return computeWdivmmLoutResultCost(quaternary.getBaseType(), executionType, bytes, workers);
		return computeResultGetCost(bytes, executionType, workers, inBand ? 0 : 1);
	}

	private static boolean nativeResultIsInBand(Hop hop) {
		// Covariance's main result is in-band; auxiliary mean/weight batches are
		// charged explicitly by computeAuxiliaryStageCost.
		return hop instanceof TernaryOp
			|| hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS
			|| hop instanceof ParameterizedBuiltinOp parameterized
				&& parameterized.getOp() == ParamBuiltinOp.CONTAINS
			|| hop instanceof BinaryOp binary && binary.getOp() == OpOp2.MOMENT
			|| hop instanceof BinaryOp binary && binary.getOp() == OpOp2.COV
			|| hop instanceof QuaternaryOp quaternary
				&& (quaternary.getOp() == OpOp4.WSLOSS || quaternary.getOp() == OpOp4.WCEMM)
			|| hop instanceof SpoofFusedOp;
	}

	public static double computeNativeFederatedLoutResultCost(Hop hop,
			PlacementCostSemantics.WorkerResponseSummary responses) {
		return computeNativeFederatedLoutResultCost(hop, null, responses);
	}

	public static double computeNativeFederatedLoutResultCost(Hop hop, List<FType> inputFTypes,
			PlacementCostSemantics.WorkerResponseSummary responses) {
		if(isAlignedCovariance(hop, inputFTypes))
			return computeReplicatedWorkerResultDownloadCost(8, responses.responses());
		double payload = computeGetResponsePayloadCost(responses.totalBytes(), responses.largestBytes(),
			MBS_NETWORK_BANDWIDTH_W2C, MBS_NETWORK_SERDES_BANDWIDTH_W2C);
		return payload + (nativeResultIsInBand(hop) ? 0.0
			: computeRequestResponseLatency(NETWORK_LATENCY_C2W, NETWORK_LATENCY_W2C));
	}

	/** Explicit and dynamically fused WDivMM have the same partial/bind response contract. */
	public static double computeWdivmmLoutResultCost(int baseType, FType inputType,
			double outputBytes, int workers) {
		boolean partial = (baseType == 1 || baseType == 3) && inputType == FType.ROW
			|| (baseType == 2 || baseType == 4) && inputType == FType.COL;
		double payload = computeResultGetCost(outputBytes, partial ? FType.PART : inputType, workers, 0);
		return payload + (partial ? computeCoordinatorAggregationCost(null, outputBytes, workers) : 0.0);
	}

	private static double computeResultGetCost(double memSize, FType fType, int numWorkers,
			int additionalBatches) {
		// FULL is a non-replicated single-worker map; BROADCAST represents replicas.
		int fanIn = fType == FType.FULL ? 1 : Math.max(1, numWorkers);
		return computeGetResponseCost(estimateCollectResponseBytes(memSize, fType, fanIn), fanIn,
			MBS_NETWORK_BANDWIDTH_W2C, MBS_NETWORK_SERDES_BANDWIDTH_W2C, additionalBatches,
			NETWORK_LATENCY_C2W, NETWORK_LATENCY_W2C);
	}

	/**
	 * Cost of one planner-selected, reusable FOUT-to-local materialization.
	 *
	 * <p>The emitted {@code prefetch} calls {@code acquireReadAndRelease} once for the
	 * selected producer and rewires all compatible local consumers to that materialized
	 * value. Its runtime path is therefore one parallel {@code GET_VAR} batch, not a
	 * standalone serial collection per consumer. Worker links use the largest response, the shared coordinator NIC uses total
	 * response bytes, and aggregate processing uses total response bytes. The batch owns
	 * one request and one response stage, using the same payload policy as every other GET. Reuse affects activation count outside this helper,
	 * never the price of one response.</p>
	 */
	public static double computeReusableMaterializationDownloadCost(double memSize,
			FType fType, int numWorkers) {
		return computeDownloadNetworkCost(memSize, fType, numWorkers);
	}

	static double computeReusableMaterializationDownloadCost(double totalMemSize, int fanIn,
			double bandwidthMBps, double serdesBwMBps, double requestLatencySec, double responseLatencySec) {
		return computeGetResponseCost(totalMemSize, fanIn, bandwidthMBps,
			serdesBwMBps, 1, requestLatencySec, responseLatencySec);
	}

	/** Payload contribution to an already owned stage, not another directional exchange. */
	static double computeNetworkPayloadCost(double largestWireBytes, double totalWireBytes,
			double codecBytes, double workerBandwidthMiBps, double coordinatorBandwidthMiBps,
			double codecMiBps) {
		return computeOneWayNetworkCost(largestWireBytes, totalWireBytes, codecBytes,
			workerBandwidthMiBps, coordinatorBandwidthMiBps, codecMiBps, 0.0);
	}

	/** One-way latency + star-network bottleneck time + aggregate endpoint processing. */
	static double computeOneWayNetworkCost(double largestWireBytes, double totalWireBytes,
			double codecBytes, double workerBandwidthMiBps, double coordinatorBandwidthMiBps,
			double codecMiBps, double latencySec) {
		if(!Double.isFinite(latencySec) || latencySec < 0.0)
			throw new IllegalArgumentException("FED_COST_INVALID_ONE_WAY_LATENCY: " + latencySec);
		double largest = positiveFinite(largestWireBytes) ? largestWireBytes : 0.0;
		double total = positiveFinite(totalWireBytes) ? totalWireBytes : 0.0;
		double codec = positiveFinite(codecBytes) ? codecBytes : 0.0;
		if(largest > 0.0 && !positiveFinite(workerBandwidthMiBps))
			throw new IllegalArgumentException("FED_COST_WORKER_BANDWIDTH_REQUIRED");
		double workerTime = largest > 0.0 ? largest / (1024 * 1024) / workerBandwidthMiBps : 0.0;
		double coordinatorTime = positiveFinite(coordinatorBandwidthMiBps)
			? total / (1024 * 1024) / coordinatorBandwidthMiBps : 0.0;
		double codecTime = positiveFinite(codecMiBps) ? codec / (1024 * 1024) / codecMiBps : 0.0;
		return (latencySec + Math.max(workerTime, coordinatorTime) + codecTime) * TO_MS;
	}

	/** Two zero-payload stage contributions; payload factors add bytes to these same stages. */
	static double computeRequestResponseLatency(double requestLatencySec, double responseLatencySec) {
		return computeOneWayNetworkCost(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, requestLatencySec)
			+ computeOneWayNetworkCost(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, responseLatencySec);
	}

	/** Purpose-independent payload price for a balanced GET response set. */
	static double computeGetResponsePayloadCost(double totalMemSize, int fanIn,
			double bandwidthMBps, double aggregateProcessingMBps) {
		return computeGetResponsePayloadCost(totalMemSize,
			estimateParallelDownloadPayload(totalMemSize, Math.max(1, fanIn)),
			bandwidthMBps, aggregateProcessingMBps);
	}

	/**
	 * Wire critical path plus aggregate coordinator response-processing work.
	 * The configured processing rate is effective throughput over total bytes, not
	 * a per-thread codec rate. Neither event-loop count nor response size changes it.
	 */
	static double computeGetResponsePayloadCost(double totalMemSize, double largestResponseBytes,
			double bandwidthMBps, double aggregateProcessingMBps) {
		if(!Double.isFinite(totalMemSize) || totalMemSize <= 0.0)
			return 0.0;
		return computeNetworkPayloadCost(largestResponseBytes, totalMemSize, totalMemSize,
			bandwidthMBps, MBS_NETWORK_COORDINATOR_W2C, aggregateProcessingMBps);
	}

	/** GET payload plus the explicitly owned count of additional request/response batches. */
	static double computeGetResponseCost(double totalMemSize, int fanIn,
			double bandwidthMBps, double aggregateProcessingMBps, int additionalBatches,
			double requestLatencySec, double responseLatencySec) {
		double payload = computeGetResponsePayloadCost(totalMemSize, fanIn,
			bandwidthMBps, aggregateProcessingMBps);
		return payload + Math.max(0, additionalBatches)
			* computeRequestResponseLatency(requestLatencySec, responseLatencySec);
	}

	public static boolean requiresExplicitMatrixBoundaryTransfer(Hop hop) {
		return hop != null
			&& hop.getDataType() != null
			&& hop.getDataType().isMatrix();
	}

	public static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		return computeInBandUploadPayloadCost(memSize, fType, numWorkers) + computeRequestResponseLatency();
	}

	/** Payload contribution to the enclosing FED request, with no additional latency. */
	public static double computeInBandUploadPayloadCost(double memSize, FType fType, int numWorkers) {
		if(!positiveFinite(memSize))
			return 0.0;
		int workers = Math.max(1, numWorkers);
		double total = fType == FType.BROADCAST ? memSize * workers : memSize;
		double largest = fType == FType.ROW || fType == FType.COL ? memSize / workers : memSize;
		return computeNetworkPayloadCost(largest, total, total, MBS_NETWORK_BANDWIDTH_C2W,
			MBS_NETWORK_COORDINATOR_C2W, MBS_NETWORK_SERDES_BANDWIDTH_C2W);
	}

	/**
	 * Cost of changing the federated placement of an already federated value.
	 *
	 * <p>The runtime contract is FED/FOUT -&gt; LOUT -&gt; FED/FOUT: collect the
	 * source placement at the coordinator and then upload the materialized value
	 * using the planner-selected target layout. Treating this as upload-only makes
	 * cross-anchor relocation artificially cheap and can invert DP/Exact choices.</p>
	 */
	public static double computeRefedNetworkCost(double memSize, FType sourceFType,
		FType targetFType, int numWorkers) {
		return computeDownloadNetworkCost(memSize, sourceFType, numWorkers)
			+ computeUploadNetworkCost(memSize, targetFType, numWorkers);
	}

	private static double estimateCollectResponseBytes(double logicalBytes, FType fType, int workers) {
		// MatrixObject requests every map entry. BROADCAST replicas and PART
		// overlapping partials return one full-shaped block per worker; ROW/COL
		// shards collectively return one logical matrix. Coordinator processing uses
		// total response bytes even when wire critical size is one block.
		return fType == FType.BROADCAST || fType == FType.PART
			? logicalBytes * workers : logicalBytes;
	}

	private static double estimateParallelDownloadPayload(double totalMemSize, int fanIn) {
		if (totalMemSize <= 0.0)
			return 0.0;
		if (fanIn <= 1)
			return totalMemSize;
		// The caller supplies total response bytes, including replica/partial
		// multiplicity. Equal-size parallel responses are a cost approximation;
		// coordinator processing is priced separately over total response bytes.
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
