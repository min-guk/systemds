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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedTypePropagator;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedWorkerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.*;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTGraph {
	// HARD_INF: prohibitively large cost used to block a placement/exec option
	// entirely
	private static final double HARD_INF = 1e12;
	// HARD_CONSTRAINT: represents impossible states (consistency violations or
	// illegal combos)
	private static final double HARD_CONSTRAINT = 1e15;
	// Single-worker FED execution rarely provides compute benefit but can add large
	// orchestration/materialization overhead at runtime. Keep FED reachable when it
	// is the only legal choice, but strongly prefer CP in this degenerate topology.
	private static final double SINGLE_WORKER_FED_EXEC_PENALTY = 1e6;
	private static final double SINGLE_WORKER_CTRL_PENALTY_THRESHOLD_MS = 10.0;
	private static final double REQUIRED_LOCAL_REPAIR_DEMOTE_CTRL_PENALTY_THRESHOLD_MS = 30.0;

	private static boolean hasImmediateFedMatrixInput(Hop hop) {
		if (hop == null)
			return false;
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty())
			return false;
		for (Hop in : inputs) {
			if (in == null || in.getDataType() == null || !in.getDataType().isMatrix())
				continue;
			if (in instanceof DataOp) {
				DataOp dataOp = (DataOp) in;
				Types.OpOpData op = dataOp.getOp();
				if (op == Types.OpOpData.FEDERATED)
					return true;
				if (op == Types.OpOpData.TRANSIENTREAD) {
					String name = dataOp.getName();
					if (name != null && FederatedPlannerUtils.isFedInitVar(name))
						return true;
				}
			}
			ExecType forcedExec = in.getForcedExecType();
			if (forcedExec == ExecType.FED && in.getFederatedOutput() == FederatedOutput.FOUT)
				return true;
		}
		return false;
	}

	private static double getConfiguredLocalToFedCtrlOverheadMs() {
		String raw = System.getenv("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS");
		if (raw == null || raw.isEmpty())
			raw = System.getProperty("SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS");
		if (raw == null || raw.isEmpty())
			return 0.0;
		try {
			return Double.parseDouble(raw.trim());
		}
		catch (NumberFormatException ex) {
			return 0.0;
		}
	}
	private static final long leafedSource = -1L;
	private static final long rootLocalSink = -2L;
	private static final long auxNodeBase = -3L;

	private final Map<Long, Vertex> memoTable = new HashMap<>();
	private final Graph<Long, DefaultWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(
			DefaultWeightedEdge.class);
	// Track TR/TW consistency edges to avoid duplicates.
	private final Set<Pair<Long, Long>> trConsistencyAdded = new HashSet<>();
	// Track TW/input placement consistency edges to avoid duplicates.
	private final Set<Pair<Long, Long>> twInputPlacementConsistencyAdded = new HashSet<>();
	// Track required local-input constraints (child must have local output if parent executes CP).
	private final Set<Pair<Long, Long>> requiredLocalInputAdded = new HashSet<>();
	// Track parent-child edges where local->federated forwarding was assumed via refed in rewire.
	private final Set<Pair<Long, Long>> parentChildUploadHintAdded = new HashSet<>();
	// Track upload-cost fallback warnings to avoid log spam (one per child hop).
	private final Set<Long> parentChildUploadCostFallbackLogged = new HashSet<>();
	private final List<LoopCarryEdge> loopCarryEdges = new ArrayList<>();
	private final Map<Long, List<EffectiveDemandRecord>> effectiveDemandIndex = new HashMap<>();
	private final Map<HyperEdgeKey, HyperEdgeGroup> parentChildHyperEdges = new HashMap<>();
	private final List<SelectedObligation> selectedObligations = new ArrayList<>();
	private int numOfWorkers = 0;
	private long nextAuxNodeId = auxNodeBase;

	{
		graph.addVertex(leafedSource);
		graph.addVertex(rootLocalSink);
	}

	public Map<Long, Vertex> getMemoTable() {
		return memoTable;
	}

	public int getNumOfWorkers() {
		return numOfWorkers;
	}

	public Graph<Long, DefaultWeightedEdge> getGraph() {
		return graph;
	}

	public void setNumOfWorkers(int numOfWorkers) {
		this.numOfWorkers = numOfWorkers;
	}

	public void addVertex(Vertex vertex) {
		long hopID = vertex.getHopID();
		memoTable.put(hopID, vertex);
		graph.addVertex(FederatedPlanMinSTPlanner.computeId(hopID));
		graph.addVertex(FederatedPlanMinSTPlanner.placementId(hopID));
	}

	public void forbidLOUTUnary(long pId) {
		addCap(leafedSource, pId, HARD_INF);
		addCap(pId, rootLocalSink, 0.0);
	}

	public void forbidFOUTUnary(long pId) {
		addCap(leafedSource, pId, 0.0);
		addCap(pId, rootLocalSink, HARD_INF);
	}

	public void forbidNoLocalUnary(long lId) {
		// C/P-only MinST graph no longer creates unconditional locality nodes.
		// Kept as a compatibility no-op for staged callers while L is removed.
	}

	public void addNoLocalImplicationEdges(long hopId) {
		// C/P-only MinST graph no longer encodes a third no-local decision bit.
		// Extra U/D obligations, if proven later, must be explicit conversion nodes.
	}

		public void setVertexCost(Vertex vertex) {
			Hop hop = vertex.getHopRef();
			long hopID = vertex.getHopID();
			long cId = FederatedPlanMinSTPlanner.computeId(hopID);
			ExecPlacementCaps caps = vertex.getCaps();
		boolean acL = caps.allowCP_LOUT;
		boolean acF = caps.allowCP_FOUT;
		boolean afL = caps.allowFED_LOUT;
		boolean afF = caps.allowFED_FOUT;

		double cpCost = vertex.getOpCostWithWeight();
		// DP/MinST parity: choosing CP-local for a concrete federated TRANSIENTREAD
		// must pay federated->local materialization (runtime acquire_read path).
		// Stable fed-init TReads can reuse one local buffer across loop iterations
		// and multiple CP parents, so charge the amortized shared materialization
		// cost instead of the full loop-weighted download at each consumer.
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			if (caps.allowCP_LOUT && caps.allowFED_FOUT
					&& hop.getDim1() > 0 && hop.getDim2() > 0) {
				cpCost += computeTransientReadLocalMaterializationCost(vertex);
			}
		}
		double fedOverhead = 0.0;
		if (!(vertex.getHopRef() instanceof DataOp)) {
			// Federated execution incurs a coordination overhead at runtime.
			//
			// IMPORTANT: Although a single logical FED instruction targets all workers,
			// requests are issued without waiting per worker; the critical path is closer to
			// "one latency" (slowest worker) plus per-worker control overhead, not
			// (latency * numWorkers). Over-penalizing latency with worker count can flip
			// iterative workloads (e.g., kmeans) into CP elementwise chains, which then
			// trigger large repeated CP->FED uploads each iteration under WAN.
			// DP parity: this overhead follows the hop's execution frequency (compute weight),
			// not the parent-child forwarding weight. DP uses HopCommon.computeWeight*multiplicity
			// (networkWeight equals computeWeight in DP rewire), which corresponds to Vertex.opWeight here.
			fedOverhead = vertex.getOpWeight()
					* FederatedCostModel.computeFedCoordinationCost(numOfWorkers);
		}
		fedOverhead = FederatedCostModel.adjustFedCoordinationCost(hop, vertex.getDataType(), fedOverhead);
			// DP parity: use the shared runtime-stage predicate for FED compute scaling.
			// Binary/Nary cell ops, slicing, transpose, and fully broadcast-only inputs are
			// not arithmetic-heavy partition-preserving compute in practice, so the static
			// model must not grant them a blanket linear worker speedup.
			boolean hasMatrixInputForFedCompute = false;
			boolean hasNonBroadcastMatrixInputForFedCompute = false;
			if (hop != null && hop.getInput() != null) {
				for (Hop in : hop.getInput()) {
					if (in == null || in.getDataType() == null || !in.getDataType().isMatrix())
						continue;
					hasMatrixInputForFedCompute = true;
					Vertex inVertex = memoTable.get(in.getHopID());
					FType inType = (inVertex != null) ? inVertex.getDataType() : null;
					if (inType != null && inType != FType.BROADCAST) {
						hasNonBroadcastMatrixInputForFedCompute = true;
						break;
					}
				}
			}
				boolean broadcastOnlyFedCompute = hasMatrixInputForFedCompute
						&& !hasNonBroadcastMatrixInputForFedCompute;
				double defaultFedComputeCost = FederatedCostModel.computeFederatedComputeCost(
						vertex.getHopRef(), cpCost, numOfWorkers, broadcastOnlyFedCompute);
				double fedComputeCost = FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
						vertex.getHopRef(), vertex.getDataType(), defaultFedComputeCost);
				fedComputeCost = FederatedCostModel.computeNativeFederatedIndexingCost(
						vertex.getHopRef(), vertex.getDataType(), fedComputeCost);
				double fedInstructionLatencyCost = FederatedCostModel.computeControlDominatedFederatedInstructionCost(
						vertex.getHopRef(), vertex.getDataType(), vertex.getOpWeight(),
						numOfWorkers, broadcastOnlyFedCompute);
			double fedInputPreparationCost = vertex.getFedInputPreparationCostWithWeight();
			double fedCost = fedComputeCost + fedOverhead
					+ fedInstructionLatencyCost
					+ fedInputPreparationCost
					+ FederatedCostModel.computeSingleWorkerFedExecPenalty(
							hop, vertex.getOpWeight(), numOfWorkers);

			// Capability legality must dominate ordinary hard-ish conversion penalties.
			// Some graph edges already include HARD_INF plus finite network cost; using the
			// stronger HARD_CONSTRAINT tier for impossible CP/FED execution sides prevents
			// max-flow ties from selecting placements that ExecPlacementCaps disallows.
			if (!acL && !acF)
				cpCost = HARD_CONSTRAINT;
			if (!afL && !afF)
				fedCost = HARD_CONSTRAINT;

		addCap(leafedSource, cId, cpCost);
		addCap(cId, rootLocalSink, fedCost);

			if (FederatedPlannerTrace.shouldTrace(vertex.getHopRef())) {
				FederatedPlannerTrace.log(vertex.getHopRef(), "MinST-VertexCost", String.format(
						"weights(op=%.6f, net=%.6f) unaryCP=%.6f unaryFED=%.6f fedCompute=%.6f fedInstructionLatency=%.6f fedInputPrep=%.6f caps=[CP_LOUT=%s,CP_FOUT=%s,FED_LOUT=%s,FED_FOUT=%s]",
						vertex.getOpWeight(), vertex.getNetworkWeight(), cpCost, fedCost,
						fedComputeCost, fedInstructionLatencyCost, fedInputPreparationCost,
						caps.allowCP_LOUT, caps.allowCP_FOUT, caps.allowFED_LOUT, caps.allowFED_FOUT));
			}
	}

	public void addExecPlacementResultEdge(Vertex vertex) {
		Hop hop = vertex.getHopRef();
		if (HopUtils.isPrintOrPWrite(hop))
			return;
		long hopId = vertex.getHopID();
		long cId = FederatedPlanMinSTPlanner.computeId(hopId);
		long pId = FederatedPlanMinSTPlanner.placementId(hopId);
		if (hop instanceof DataOp &&
				((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			// DP parity: a stable federated-input TRead (fed-init var) can materialize
			// once and share the local buffer across loop iterations / CP parents. The
			// MinST graph already shares this edge globally across all parents, so do not
			// multiply the FED->CP download by the hop execution frequency again.
			//
			// For ordinary TReads we still charge the loop-weighted download, because the
			// local materialization may legitimately happen per execution.
			double trDownloadCost = computeTransientReadDownloadEdgeCost(vertex);
			addCap(cId, pId, trDownloadCost);
			if (FederatedPlannerTrace.shouldTrace(hop)) {
				FederatedPlannerTrace.log(hop, "MinST-ResultEdge",
						String.format("TR edge c->p download=%.6f stableFedInput=%s",
								trDownloadCost, shouldReuseTransientReadDownload(vertex)));
			}
			return;
		}
		// Hop-local placement conversion follows the hop's own execution frequency.
		double uploadCost = vertex.getOpWeight() * vertex.getCpUploadCostWithoutWeight();
		double downloadCost = vertex.getOpWeight() * vertex.getDownloadCostWithoutWeight();
		if (vertex.isDerivedFedFout()) {
			// Derived FED/FOUT is not a native worker-side FOUT result. It is produced as
			// FED/LOUT first and then refed from the local materialization. Therefore any
			// FED execution of this vertex must pay the FED->local materialization cost,
			// and any FOUT placement must pay the local->federated upload cost. Encoding
			// the download only as C->P would charge FED/LOUT but miss FED/FOUT because
			// C and P are both on the source side for FED/FOUT.
			addCap(cId, rootLocalSink, downloadCost);
			addCap(pId, rootLocalSink, uploadCost);
			if (FederatedPlannerTrace.shouldTrace(hop)) {
				FederatedPlannerTrace.log(hop, "MinST-ResultEdge", String.format(
						"derivedFED_FOUT edge c->t materialize=%.6f, p->t upload=%.6f",
						downloadCost, uploadCost));
			}
			return;
		}
		// Download is paid by the intrinsic native FED/LOUT combination in the C/P graph.
		addCap(cId, pId, downloadCost);
		addCap(pId, cId, uploadCost);
		if (FederatedPlannerTrace.shouldTrace(hop)) {
			FederatedPlannerTrace.log(hop, "MinST-ResultEdge", String.format(
					"native edge p->c upload=%.6f, c->p download=%.6f",
						uploadCost, downloadCost));
		}
	}

	public void addParentChildNetEdge(Vertex childVertex, long childHopID,
			Vertex parentVertex, long parentHopID, boolean requiresFederatedInput) {
		long parentC = FederatedPlanMinSTPlanner.computeId(parentHopID);
		long childP = FederatedPlanMinSTPlanner.placementId(childHopID);
		Hop parentHopRef = (parentVertex != null) ? parentVertex.getHopRef() : null;
		Hop childHopRef = childVertex.getHopRef();
		boolean matrixInput = childHopRef != null
				&& childHopRef.getDataType() != null
				&& childHopRef.getDataType().isMatrix();

		double forwardingWeight = parentVertex.computeForwardingWeightOfChild(childVertex.getLoopContext());
		// Use child FType as a proxy conversion key since per-input conversion detail is not available here.
		FType uploadConversionType = childVertex.getCpFoutDataType();
		if (uploadConversionType == null) {
			uploadConversionType = childVertex.getDataType();
		}
		// Align parent-child forwarding-cost estimation with runtime CP->FOUT materialization policy.
		// If a global anchor key implies ROW/COL partitioning but this hop's axis length (or vector axis)
		// does not match, runtime will broadcast even if the logical FType is ROW/COL. Reflect that here
		// to avoid under-estimating LOUT->FED forwarding cost by missing the fan-out multiplier.
		uploadConversionType = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(childHopRef, uploadConversionType);
		// Use CP->FOUT upload cost here as well: a parent-child edge can represent
		// local (LOUT/CP) -> federated (FED) forwarding, and in such cases the CP->FOUT
		// upload FType (e.g., BROADCAST for vector axis mismatch) must be reflected.
		double uploadCost = childVertex.getCpUploadCostWithoutWeight();
		if (Double.isNaN(uploadCost) || uploadCost <= 0.0) {
			final double originalUploadCost = uploadCost;
				Hop childHop = childHopRef;
				double outputMemEstimate = FederatedCostModel.getEffectiveUploadMemEstimate(childHop);
			if (uploadConversionType != null && outputMemEstimate > 0.0) {
				uploadCost = FederatedCostModel.computeUploadNetworkCost(
						outputMemEstimate, uploadConversionType, numOfWorkers);
			}
			// If we still cannot estimate a meaningful cost, warn once per child hop.
			// This indicates that the plan's forwarding cost is under-estimated (e.g., unknown mem estimate).
			if ((Double.isNaN(uploadCost) || uploadCost <= 0.0)
					&& parentChildUploadCostFallbackLogged.add(childHopID)) {
				String childOp = (childHop != null) ? childHop.getOpString() : "null";
				Hop parentHop = (parentVertex != null) ? parentVertex.getHopRef() : null;
				String parentOp = (parentHop != null) ? parentHop.getOpString() : "null";
				FederatedPlannerLogger.logWarnMessage(
						"[MinST] Parent-child forwarding CP->FOUT upload cost missing/zero for child hop "
								+ childHopID + " (" + childOp + ") consumed by parent hop "
								+ parentHopID + " (" + parentOp + "). "
								+ "cpUploadCost=" + originalUploadCost
								+ ", outputMemEstimate=" + outputMemEstimate
								+ ", cpFoutType=" + uploadConversionType
								+ ", dataType=" + childVertex.getDataType()
								+ ", numWorkers=" + numOfWorkers
								+ "; forwarding cost may be under-estimated.");
			}
			// If we successfully recovered a cost, keep it debug-only to avoid noise.
			else if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0)
					&& parentChildUploadCostFallbackLogged.add(childHopID)) {
				String childOp = (childHop != null) ? childHop.getOpString() : "null";
				FederatedPlannerLogger.logInfoMessage(
						"[MinST] Recovered missing parent-child CP->FOUT upload cost for child hop "
								+ childHopID + " (" + childOp + "): " + originalUploadCost + " -> " + uploadCost);
			}
		}
		double forwardingPenalty = 0.0;
		if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0)) {
			forwardingPenalty = FederatedCostModel.computeLocalToFedForwardingPenalty(
					uploadConversionType, numOfWorkers);
			uploadCost += forwardingPenalty;
		}
		double uploadWeighted = forwardingWeight * uploadCost;

		// If a parent executes in CP, the child usually must have a local materialization
		// (either natively or via download).  A concrete-source TRANSIENTREAD is the
		// exception: the TRead is an alias/materialization boundary over a FED source,
		// so CP/LOUT on the alias must be costed by the TRead's own shared download
		// edge, not by forcing the underlying FEDERATED source itself to become local.
		boolean skipRequiredLocalInputEdge =
				shouldUseAliasMaterializationForLocalTransientRead(parentVertex, childVertex);
		if (!skipRequiredLocalInputEdge)
			addRequiredLocalInputEdge(parentHopID, childHopID);
		// Cost-model policy: matrix inputs can require local->federated boundary transfer
		// whenever the parent executes FED, regardless of OPTIONAL/REQUIRED classification.
		// Upload hints are still used by rewire/materialization policy, but they no longer
		// gate parent-child boundary cost edges in MinST.
		if (!matrixInput) {
			Hop traceHop = FederatedPlannerTrace.shouldTrace(parentVertex.getHopRef())
					? parentVertex.getHopRef() : childHopRef;
			if (FederatedPlannerTrace.shouldTrace(traceHop)) {
				FederatedPlannerTrace.log(traceHop, "MinST-BoundaryEdge", String.format(
						"parent=%d child=%d requiresFedInput=%s matrixInput=%s edge=SKIP",
						parentHopID, childHopID, requiresFederatedInput, matrixInput));
			}
			return;
		}
		recordEffectiveDemand(childHopID, EffectiveDemandSide.FED, parentHopID,
				"raw-parent-fed-boundary", forwardingWeight, uploadConversionType);
		if (childHopRef instanceof DataGenOp) {
			// DataGen CP->FOUT has no concrete runtime anchor in the local result.
			// Treat a FED consumer as requiring native FED/FOUT generation instead of
			// selecting a post-cut U obligation that would fail during registration.
			long childC = FederatedPlanMinSTPlanner.computeId(childHopID);
			addCap(parentC, childP, HARD_CONSTRAINT);
			addCap(parentC, childC, HARD_CONSTRAINT);
			if (FederatedPlannerTrace.shouldTrace(childHopRef)) {
				FederatedPlannerTrace.log(childHopRef, "MinST-CapabilityGate", String.format(
						"parent=%d child=%d reason=DataGen CP/LOUT U has no anchor; FED consumer requires native FED/FOUT or parent CP",
						parentHopID, childHopID));
			}
			return;
		}
		// State-conditioned U obligation: if one or more selected FED consumers need
		// a federated representation while the child primary placement stays LOUT,
		// the upload/refed conversion is charged once for the compatible child domain.
		// The auxiliary hyperedge encodes the OR over parent C states before the cut:
		// any FED parent forces the aux node to source; if child P remains sink (LOUT),
		// the group pays exactly one upload cost.
		addParentChildHyperEdge(parentC, childP, HyperEdgeDirection.UPLOAD,
				uploadConversionType, uploadWeighted);
		Hop traceHop = FederatedPlannerTrace.shouldTrace(parentVertex.getHopRef())
				? parentVertex.getHopRef() : childHopRef;
		if (FederatedPlannerTrace.shouldTrace(traceHop)) {
			FederatedPlannerTrace.log(traceHop, "MinST-BoundaryEdge", String.format(
					"parent=%d child=%d requiresFedInput=%s fwdWeight=%.6f baseUpload=%.6f penalty=%.6f weightedUpload=%.6f type=%s encoding=U-HYPEREDGE",
					parentHopID, childHopID, requiresFederatedInput, forwardingWeight,
					childVertex.getCpUploadCostWithoutWeight(), forwardingPenalty, uploadWeighted, uploadConversionType));
		}
	}

	private boolean shouldUseAliasMaterializationForLocalTransientRead(Vertex parentVertex, Vertex childVertex) {
		if (parentVertex == null || childVertex == null)
			return false;
		if (!parentVertex.isStableFederatedInputRead())
			return false;
		Hop parentHop = parentVertex.getHopRef();
		if (!(parentHop instanceof DataOp)
				|| ((DataOp) parentHop).getOp() != Types.OpOpData.TRANSIENTREAD)
			return false;
		Hop childHop = childVertex.getHopRef();
		if (!(childHop instanceof DataOp))
			return false;
		DataOp childDataOp = (DataOp) childHop;
		if (childDataOp.getOp() == Types.OpOpData.FEDERATED)
			return true;
		return childDataOp.getOp() == Types.OpOpData.TRANSIENTREAD
				&& childVertex.isStableFederatedInputRead();
	}

	public void addLoopCarryEdge(long endWriterHopId, long frontReaderHopId, double weight) {
		if (weight <= 0.0 || endWriterHopId < 0 || frontReaderHopId < 0)
			return;
		loopCarryEdges.add(new LoopCarryEdge(endWriterHopId, frontReaderHopId, weight));
	}

	public List<LoopCarryEdge> getLoopCarryEdges() {
		return Collections.unmodifiableList(loopCarryEdges);
	}

	public void addLoopCarryNetEdge(long frontReaderHopId, long endWriterHopId,
			double uploadWeighted, double downloadWeighted) {
		long readerC = FederatedPlanMinSTPlanner.computeId(frontReaderHopId);
		long writerP = FederatedPlanMinSTPlanner.placementId(endWriterHopId);
		recordEffectiveDemand(endWriterHopId, EffectiveDemandSide.FED, frontReaderHopId,
				"loop-carry-fed", uploadWeighted, null);
		recordEffectiveDemand(endWriterHopId, EffectiveDemandSide.LOCAL, frontReaderHopId,
				"loop-carry-local", downloadWeighted, null);
		addCap(readerC, writerP, uploadWeighted);
		addCap(writerP, readerC, downloadWeighted);
	}

		public void addTransReadWriteConsistencyEdges(Vertex tw, long twId, Vertex tr, long trId) {
			// TR/TW hard-constraint helper for enforcing:
			//   <TW-LOUT, TR-CP/LOUT>  and  <TW-FOUT, TR-FED/FOUT>
			// In our min-cut encoding this corresponds to placing TW.P, TR.C, and TR.P on the same side.
			if (!trConsistencyAdded.add(Pair.of(twId, trId)))
				return;
			long twP = FederatedPlanMinSTPlanner.placementId(twId);
			long trC = FederatedPlanMinSTPlanner.computeId(trId);
			long trP = FederatedPlanMinSTPlanner.placementId(trId);
			addXorEdge(twP, trC, HARD_CONSTRAINT);
			addXorEdge(twP, trP, HARD_CONSTRAINT);
			Hop traceHop = (tw != null && FederatedPlannerTrace.shouldTrace(tw.getHopRef()))
					? tw.getHopRef()
					: ((tr != null) ? tr.getHopRef() : null);
			if (FederatedPlannerTrace.shouldTrace(traceHop)) {
				FederatedPlannerTrace.log(traceHop, "MinST-TRTW-Consistency", String.format(
						"tw=%d tr=%d twP=%d trC=%d trP=%d", twId, trId, twP, trC, trP));
			}
		}

	public void addTransWriteInputPlacementConsistencyEdge(long twId, long inputHopId) {
		if (twId < 0 || inputHopId < 0)
			return;
		if (!twInputPlacementConsistencyAdded.add(Pair.of(twId, inputHopId)))
			return;
		long twP = FederatedPlanMinSTPlanner.placementId(twId);
		long inputP = FederatedPlanMinSTPlanner.placementId(inputHopId);
		addXorEdge(twP, inputP, HARD_CONSTRAINT);
	}

	public void addRequiredLocalInputEdge(long parentHopId, long childHopId) {
		if (parentHopId < 0 || childHopId < 0)
			return;
		if (!requiredLocalInputAdded.add(Pair.of(parentHopId, childHopId)))
			return;
		long parentC = FederatedPlanMinSTPlanner.computeId(parentHopId);
		long childP = FederatedPlanMinSTPlanner.placementId(childHopId);
		// Conservative C/P fallback: without an explicit D obligation, a CP parent
		// requires the child primary placement to be local. This converts the former
		// post-cut required-local repair into a graph constraint.
		recordEffectiveDemand(childHopId, EffectiveDemandSide.LOCAL, parentHopId,
				"raw-parent-local", 1.0, null);
		addCap(childP, parentC, HARD_CONSTRAINT);
	}

	public EffectiveDemandClass getEffectiveDemandClass(long hopId) {
		List<EffectiveDemandRecord> records = effectiveDemandIndex.get(hopId);
		if (records == null || records.isEmpty())
			return EffectiveDemandClass.NONE;
		boolean local = false;
		boolean fed = false;
		for (EffectiveDemandRecord record : records) {
			if (record.side == EffectiveDemandSide.LOCAL)
				local = true;
			else if (record.side == EffectiveDemandSide.FED)
				fed = true;
		}
		if (local && fed)
			return EffectiveDemandClass.MIXED_LOCAL_FED;
		if (local)
			return EffectiveDemandClass.LOCAL_ONLY;
		return EffectiveDemandClass.FED_ONLY;
	}

	public List<String> getEffectiveDemandSummary(long hopId) {
		List<EffectiveDemandRecord> records = effectiveDemandIndex.get(hopId);
		if (records == null || records.isEmpty())
			return Collections.emptyList();
		List<String> summary = new ArrayList<>();
		for (EffectiveDemandRecord record : records)
			summary.add(record.toString());
		return Collections.unmodifiableList(summary);
	}

	private void recordEffectiveDemand(long hopId, EffectiveDemandSide side, long consumerHopId,
			String kind, double weight, FType fType) {
		if (hopId < 0 || side == null)
			return;
		List<EffectiveDemandRecord> records =
				effectiveDemandIndex.computeIfAbsent(hopId, k -> new ArrayList<>());
		EffectiveDemandRecord record =
				new EffectiveDemandRecord(side, consumerHopId, kind, weight, fType);
		if (!records.contains(record))
			records.add(record);
	}

	public List<SelectedObligation> getSelectedObligations() {
		return Collections.unmodifiableList(selectedObligations);
	}

	private void computeSelectedObligations(Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		selectedObligations.clear();
		for (Map.Entry<Long, List<EffectiveDemandRecord>> entry : effectiveDemandIndex.entrySet()) {
			long childHopId = entry.getKey();
			Vertex childVertex = memoTable.get(childHopId);
			if (childVertex == null || childVertex.getHopRef() == null)
				continue;
			Hop childHop = childVertex.getHopRef();
			if (childHop.getDataType() == null || !childHop.getDataType().isMatrix())
				continue;
			ExecType childExec = execSelection.getOrDefault(childHopId, ExecType.CP);
			FederatedOutput childOut = outSelection.getOrDefault(childHopId, FederatedOutput.LOUT);
			List<Long> activeFedConsumers = new ArrayList<>();
			List<Long> activeLocalConsumers = new ArrayList<>();
			for (EffectiveDemandRecord record : entry.getValue()) {
				if (record.side == EffectiveDemandSide.FED
						&& isDemandActive(record, ExecType.FED, execSelection))
					activeFedConsumers.add(record.consumerHopId);
				else if (record.side == EffectiveDemandSide.LOCAL
						&& isDemandActive(record, ExecType.CP, execSelection))
					activeLocalConsumers.add(record.consumerHopId);
			}
			if (!activeFedConsumers.isEmpty()
					&& childExec == ExecType.CP
					&& childOut == FederatedOutput.LOUT) {
				FType fType = childVertex.getCpFoutDataType();
				if (fType == null)
					fType = childVertex.getDataType();
				selectedObligations.add(new SelectedObligation(
						ObligationKind.U, childHopId, activeFedConsumers, fType,
						"CP/LOUT child has active FED consumers"));
			}
			if (!activeLocalConsumers.isEmpty()
					&& childExec == ExecType.FED
					&& childOut == FederatedOutput.FOUT) {
				selectedObligations.add(new SelectedObligation(
						ObligationKind.D, childHopId, activeLocalConsumers, childVertex.getDataType(),
						"FED/FOUT child has active LOCAL consumers"));
			}
		}
	}

	private boolean isDemandActive(EffectiveDemandRecord record, ExecType activeConsumerExec,
			Map<Long, ExecType> execSelection) {
		if (record == null)
			return false;
		if (record.kind != null && (record.kind.startsWith("loop-carry")
				|| record.kind.startsWith("boundary")
				|| record.kind.startsWith("final")
				|| record.kind.startsWith("memo")
				|| record.kind.startsWith("tw-tr")))
			return true;
		Vertex consumerVertex = memoTable.get(record.consumerHopId);
		if (consumerVertex == null)
			return true;
		return execSelection.getOrDefault(record.consumerHopId, ExecType.CP) == activeConsumerExec;
	}

	private double computeTransientReadLocalMaterializationCost(Vertex vertex) {
		if (vertex == null)
			return 0.0;
		double baseDownload = vertex.getDownloadCostWithoutWeight();
		if (Double.isNaN(baseDownload) || baseDownload <= 0.0)
			return 0.0;
		double explicitProducerSharedCost = computeExplicitTransientReadFamilyLocalMaterializationCost(vertex, baseDownload);
		if (!Double.isNaN(explicitProducerSharedCost))
			return explicitProducerSharedCost;
		if (!shouldReuseTransientReadDownload(vertex))
			return vertex.getOpWeight() * baseDownload;
		double opWeight = Math.max(1.0, vertex.getOpWeight());
		double networkWeight = vertex.getNetworkWeight();
		if (Double.isNaN(networkWeight) || networkWeight <= 0.0)
			networkWeight = 1.0;
		double reuseShare = Math.min(1.0, networkWeight / opWeight);
		int numParents = Math.max(1, vertex.getNumParents());
		return baseDownload * reuseShare / numParents;
	}

	private double computeTransientReadDownloadEdgeCost(Vertex vertex) {
		if (vertex == null)
			return 0.0;
		double explicitProducerSharedCost = computeExplicitTransientReadFamilyLocalMaterializationCost(
			vertex, vertex.getDownloadCostWithoutWeight());
		if (!Double.isNaN(explicitProducerSharedCost))
			return explicitProducerSharedCost;
		if (shouldReuseTransientReadDownload(vertex))
			return computeTransientReadLocalMaterializationCost(vertex);
		double baseDownload = vertex.getDownloadCostWithoutWeight();
		if (Double.isNaN(baseDownload) || baseDownload <= 0.0)
			return 0.0;
		return vertex.getOpWeight() * baseDownload;
	}

	private static boolean shouldReuseTransientReadDownload(Vertex vertex) {
		if (vertex == null)
			return false;
		Hop hop = vertex.getHopRef();
		if (!(hop instanceof DataOp))
			return false;
		DataOp dataOp = (DataOp) hop;
		if (dataOp.getOp() != Types.OpOpData.TRANSIENTREAD)
			return false;
		String name = dataOp.getName();
		return vertex.isStableFederatedInputRead()
			|| (name != null && FederatedPlannerUtils.isFedInitVar(name));
	}

	private double computeExplicitTransientReadFamilyLocalMaterializationCost(Vertex vertex, double baseDownload) {
		Long transientWriteHopId = vertex.getTransientWriteHopId();
		if (transientWriteHopId == null)
			return Double.NaN;
		Hop hop = vertex.getHopRef();
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD)
			return Double.NaN;
		String hopName = hop.getName();
		if (hopName == null || hopName.isEmpty())
			return Double.NaN;
		Vertex transientWriteVertex = memoTable.get(transientWriteHopId);
		boolean localProducerFamily = transientWriteVertex != null
			&& transientWriteVertex.getCaps() != null
			&& transientWriteVertex.getCaps().allowCP_LOUT;
		if (localProducerFamily)
			return 0.0;
		if (!shouldReuseTransientReadDownload(vertex))
			return Double.NaN;

		int sharedConsumerCount = 0;
		for (Vertex siblingVertex : memoTable.values()) {
			if (siblingVertex == null || !Objects.equals(transientWriteHopId, siblingVertex.getTransientWriteHopId()))
				continue;
			Hop siblingHop = siblingVertex.getHopRef();
			if (!(siblingHop instanceof DataOp)
					|| ((DataOp) siblingHop).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;
			if (!Objects.equals(hopName, siblingHop.getName()))
				continue;
			ExecPlacementCaps siblingCaps = siblingVertex.getCaps();
			if (siblingCaps == null || !siblingCaps.allowCP_LOUT)
				continue;
			sharedConsumerCount += Math.max(1, siblingVertex.getNumParents());
		}
		if (sharedConsumerCount <= 0)
			sharedConsumerCount = Math.max(1, vertex.getNumParents());
		return baseDownload / sharedConsumerCount;
	}

	public void markParentChildUploadHint(long parentHopId, long childHopId) {
		if (parentHopId < 0 || childHopId < 0)
			return;
		parentChildUploadHintAdded.add(Pair.of(parentHopId, childHopId));
	}

	public boolean hasParentChildUploadHint(long parentHopId, long childHopId) {
		if (parentHopId < 0 || childHopId < 0)
			return false;
		return parentChildUploadHintAdded.contains(Pair.of(parentHopId, childHopId));
	}

	public void forbidCombinationCP_FOUT(long cId, long pId) {
		addCap(pId, cId, HARD_CONSTRAINT);
	}

	public void forbidCombinationFED_LOUT(long cId, long pId) {
		addCap(cId, pId, HARD_CONSTRAINT);
	}

	private void addXorEdge(long u, long v, double w) {
		addCap(u, v, w);
		addCap(v, u, w);
	}

	private void addCap(long u, long v, double cap) {
		if (Double.isNaN(cap) || cap < 0)
			return;
		DefaultWeightedEdge e = graph.getEdge(u, v);
		if (e == null) {
			graph.addVertex(u);
			graph.addVertex(v);
			e = graph.addEdge(u, v);
			if (e == null)
				return; // 방어
			graph.setEdgeWeight(e, cap);
		} else {
			graph.setEdgeWeight(e, graph.getEdgeWeight(e) + cap);
		}
	}

	private void addParentChildHyperEdge(long parentC, long childP, HyperEdgeDirection direction,
			FType conversionType, double cost) {
		if (Double.isNaN(cost) || cost < 0) {
			return;
		}

		HyperEdgeKey key = new HyperEdgeKey(childP, direction, conversionType);
		HyperEdgeGroup group = parentChildHyperEdges.get(key);
		if (group == null) {
			long auxNodeId = nextAuxNodeId--;
			graph.addVertex(auxNodeId);
			group = new HyperEdgeGroup(auxNodeId, cost);
			parentChildHyperEdges.put(key, group);
			addParentChildGroupCostEdge(group, childP, direction, cost);
		}
		else if (cost > group.cost) {
			// Use max to remain conservative when costs differ across parents.
			double delta = cost - group.cost;
			addParentChildGroupCostEdge(group, childP, direction, delta);
			logParentChildCostMismatch(group, direction, childP, conversionType);
			group.cost = cost;
		}
		else if (cost != group.cost) {
			logParentChildCostMismatch(group, direction, childP, conversionType);
		}

		if (group.parents.add(parentC)) {
			if (direction == HyperEdgeDirection.UPLOAD) {
				// Use HARD_CONSTRAINT to keep OR semantics intact even when total finite costs are large.
				addCap(parentC, group.auxNodeId, HARD_CONSTRAINT);
			}
			else {
				addCap(group.auxNodeId, parentC, HARD_CONSTRAINT);
			}
		}
	}

	private void addParentChildGroupCostEdge(HyperEdgeGroup group, long childP,
			HyperEdgeDirection direction, double cost) {
		if (direction == HyperEdgeDirection.UPLOAD) {
			addCap(group.auxNodeId, childP, cost);
		}
		else {
			addCap(childP, group.auxNodeId, cost);
		}
	}

	private void logParentChildCostMismatch(HyperEdgeGroup group, HyperEdgeDirection direction,
			long childP, FType conversionType) {
		if (group.mismatchLogged) {
			return;
		}
		FederatedPlannerLogger.logInfoMessage(String.format(
				"[MinST] Parent-child %s costs differ for childP=%d type=%s; using max.",
				direction.name(), childP, conversionType));
		group.mismatchLogged = true;
	}

	private enum HyperEdgeDirection {
		UPLOAD,
		DOWNLOAD
	}

	private static final class HyperEdgeKey {
		private final long childPlacementId;
		private final HyperEdgeDirection direction;
		private final FType conversionType;

		private HyperEdgeKey(long childPlacementId, HyperEdgeDirection direction, FType conversionType) {
			this.childPlacementId = childPlacementId;
			this.direction = direction;
			this.conversionType = conversionType;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof HyperEdgeKey))
				return false;
			HyperEdgeKey other = (HyperEdgeKey) obj;
			return childPlacementId == other.childPlacementId
					&& direction == other.direction
					&& conversionType == other.conversionType;
		}

		@Override
		public int hashCode() {
			return Objects.hash(childPlacementId, direction, conversionType);
		}
	}

	private static final class HyperEdgeGroup {
		private final long auxNodeId;
		private final Set<Long> parents = new HashSet<>();
		private double cost;
		private boolean mismatchLogged = false;

		private HyperEdgeGroup(long auxNodeId, double cost) {
			this.auxNodeId = auxNodeId;
			this.cost = cost;
		}
	}

	public enum EffectiveDemandClass {
		NONE,
		LOCAL_ONLY,
		FED_ONLY,
		MIXED_LOCAL_FED
	}

	public enum ObligationKind {
		U,
		D
	}

	public static final class SelectedObligation {
		private final ObligationKind kind;
		private final long childHopId;
		private final long originalHopId;
		private final String domainId;
		private final List<Long> consumerHopIds;
		private final FType fType;
		private final boolean capability;
		private final String capabilityReason;
		private final String reason;

		private SelectedObligation(ObligationKind kind, long childHopId,
				List<Long> consumerHopIds, FType fType, String reason) {
			this(kind, childHopId, childHopId, consumerHopIds, fType, true, "capability-proven-by-planner", reason);
		}

		private SelectedObligation(ObligationKind kind, long childHopId, long originalHopId,
				List<Long> consumerHopIds, FType fType, boolean capability,
				String capabilityReason, String reason) {
			this.kind = kind;
			this.childHopId = childHopId;
			this.originalHopId = originalHopId;
			this.consumerHopIds = Collections.unmodifiableList(new ArrayList<>(consumerHopIds));
			this.fType = fType;
			this.capability = capability;
			this.capabilityReason = capabilityReason;
			this.reason = reason;
			this.domainId = String.format("%s:%d:%s:%s", kind, originalHopId, fType, this.consumerHopIds);
		}

		public ObligationKind getKind() {
			return kind;
		}

		public long getChildHopId() {
			return childHopId;
		}

		public long getOriginalHopId() {
			return originalHopId;
		}

		public String getDomainId() {
			return domainId;
		}

		public List<Long> getConsumerHopIds() {
			return consumerHopIds;
		}

		public FType getFType() {
			return fType;
		}

		public boolean hasCapability() {
			return capability;
		}

		public String getCapabilityReason() {
			return capabilityReason;
		}

		public String getReason() {
			return reason;
		}

		@Override
		public String toString() {
			return String.format("kind=%s domain=%s child=%d original=%d consumers=%s fType=%s capability=%s capabilityReason=%s reason=%s",
					kind, domainId, childHopId, originalHopId, consumerHopIds, fType,
					capability, capabilityReason, reason);
		}
	}

	private enum EffectiveDemandSide {
		LOCAL,
		FED
	}

	private static final class EffectiveDemandRecord {
		private final EffectiveDemandSide side;
		private final long consumerHopId;
		private final String kind;
		private final double weight;
		private final FType fType;

		private EffectiveDemandRecord(EffectiveDemandSide side, long consumerHopId,
				String kind, double weight, FType fType) {
			this.side = side;
			this.consumerHopId = consumerHopId;
			this.kind = kind;
			this.weight = weight;
			this.fType = fType;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof EffectiveDemandRecord))
				return false;
			EffectiveDemandRecord other = (EffectiveDemandRecord) obj;
			return side == other.side
					&& consumerHopId == other.consumerHopId
					&& Objects.equals(kind, other.kind)
					&& Double.compare(weight, other.weight) == 0
					&& fType == other.fType;
		}

		@Override
		public int hashCode() {
			return Objects.hash(side, consumerHopId, kind, weight, fType);
		}

		@Override
		public String toString() {
			return String.format("side=%s consumer=%d kind=%s weight=%.6f fType=%s",
					side, consumerHopId, kind, weight, fType);
		}
	}

	public Hop getHopRef(long hopID) {
		return memoTable.get(hopID).getHopRef();
	}

	public Vertex getVertex(long hopID) {
		return memoTable.get(hopID);
	}

	public boolean contains(long hopID) {
		return memoTable.containsKey(hopID);
	}

		public void getOptimalPlan() {
			PushRelabelMFImpl<Long, DefaultWeightedEdge> algo = new PushRelabelMFImpl<>(graph);
			algo.calculateMinCut(leafedSource, rootLocalSink);

			Set<Long> sourceSide = algo.getSourcePartition(); // S
			FederatedPlannerTrace.logGlobal("MinST-Cut",
					"sourcePartitionSize=" + sourceSide.size() + ", totalGraphVertices=" + graph.vertexSet().size());

			Map<Long, ExecType> execSelection = new HashMap<>();
			Map<Long, FederatedOutput> outSelection = new HashMap<>();
			for (Vertex vertex : memoTable.values()) {
				long hopID = vertex.getHopID();
				long cId = FederatedPlanMinSTPlanner.computeId(hopID);
				long pId = FederatedPlanMinSTPlanner.placementId(hopID);

				ExecType exec = sourceSide.contains(cId) ? ExecType.FED : ExecType.CP;
				FederatedOutput out = sourceSide.contains(pId) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
				execSelection.put(hopID, exec);
				outSelection.put(hopID, out);
			}

			// U/D obligations are pre-cut graph terms. Do not silently reverse C/P or
			// placement decisions after the cut; downstream validation and selected
			// obligation registration must either satisfy the selected plan or fail.
			computeSelectedObligations(execSelection, outSelection);
			Map<Long, FType> finalFTypeMap = buildSelectedFTypeMap(execSelection, outSelection);

			for (Vertex vertex : memoTable.values()) {
				long hopID = vertex.getHopID();
				ExecType exec = execSelection.getOrDefault(hopID, ExecType.CP);
				FederatedOutput out = outSelection.getOrDefault(hopID, FederatedOutput.LOUT);
				ExecType rewriteExec = ExecPlacementPolicy.normalizeRewriteExecType(vertex.getHopRef(), exec);
				vertex.getHopRef().setForcedExecType(rewriteExec);
				vertex.getHopRef().setFederatedOutput(out);
				boolean derivedSelected = vertex.isDerivedFedFout()
						&& exec == ExecType.FED && out == FederatedOutput.FOUT;
				vertex.getHopRef().setFederatedOutputDerived(derivedSelected);
				logSelectedDecision(vertex, sourceSide);
				logFinalSelectedDecision(vertex, sourceSide, exec, out, finalFTypeMap.get(hopID), derivedSelected);
			}
		}

	private void repairSelectionFixpoint(Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		if (execSelection == null || outSelection == null)
			return;
		boolean changed;
		int iter = 0;
			do {
				changed = false;
				// These repairs are coupled:
				// - FED-input repair can demote a TWrite after the initial TR/TW alignment pass
				// - the linked TRead must then be reconsidered against the repaired TWrite
				// - required-local demand is now encoded as graph constraints
				// - CP/FOUT parity repair back-propagates output materialization through selected FOUT chains
				// - cap repair normalizes any intermediate illegal combination exposed by the repairs
				changed |= repairTransientReadWriteSelection(execSelection, outSelection);
				changed |= repairCpfoutPropagationSelection(execSelection, outSelection);
				changed |= repairCapsInconsistentSelection(execSelection, outSelection);
				changed |= repairFederatedInputSelection(execSelection, outSelection);
				changed |= repairCpfoutPropagationSelection(execSelection, outSelection);
				changed |= repairCapsInconsistentSelection(execSelection, outSelection);
				iter++;
			}
			while (changed && iter < 6);
		}

	private boolean repairTransientReadWriteSelection(Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		if (execSelection == null || outSelection == null)
			return false;
		boolean changed;
		boolean changedAny = false;
		int iter = 0;
		do {
			changed = false;
			iter++;
			for (Vertex trVertex : memoTable.values()) {
				if (trVertex == null || trVertex.getHopRef() == null || trVertex.getCaps() == null)
					continue;
				Hop trHop = trVertex.getHopRef();
				if (!(trHop instanceof DataOp)
						|| ((DataOp) trHop).getOp() != Types.OpOpData.TRANSIENTREAD)
					continue;
				Long twHopId = trVertex.getTransientWriteHopId();
				if (twHopId == null)
					continue;
				Vertex twVertex = memoTable.get(twHopId);
				if (twVertex == null || twVertex.getCaps() == null)
					continue;
				ExecType trExec = execSelection.get(trVertex.getHopID());
				ExecType twExec = execSelection.get(twHopId);
				if (trExec == null || twExec == null || trExec == twExec)
					continue;

				ExecPlacementCaps trCaps = trVertex.getCaps();
				ExecPlacementCaps twCaps = twVertex.getCaps();
				if (trExec == ExecType.FED && twExec == ExecType.CP) {
					if (trCaps.allowCP_LOUT) {
						execSelection.put(trVertex.getHopID(), ExecType.CP);
						outSelection.put(trVertex.getHopID(), FederatedOutput.LOUT);
						changed = true;
						changedAny = true;
						if (FederatedPlannerTrace.shouldTrace(trHop))
							FederatedPlannerTrace.log(trHop, "MinST-TRTW-Repair",
									"demote TR " + trVertex.getHopID() + " to CP/LOUT to match TW");
					}
					else if (twCaps.allowFED_FOUT) {
						execSelection.put(twHopId, ExecType.FED);
						outSelection.put(twHopId, FederatedOutput.FOUT);
						changed = true;
						changedAny = true;
						if (FederatedPlannerTrace.shouldTrace(trHop))
							FederatedPlannerTrace.log(trHop, "MinST-TRTW-Repair",
									"promote TW " + twHopId + " to FED/FOUT to match TR");
					}
				}
				else if (trExec == ExecType.CP && twExec == ExecType.FED) {
					if (twCaps.allowCP_LOUT) {
						execSelection.put(twHopId, ExecType.CP);
						outSelection.put(twHopId, FederatedOutput.LOUT);
						changed = true;
						changedAny = true;
						if (FederatedPlannerTrace.shouldTrace(trHop))
							FederatedPlannerTrace.log(trHop, "MinST-TRTW-Repair",
									"demote TW " + twHopId + " to CP/LOUT to match TR");
					}
					else if (trCaps.allowFED_FOUT) {
						execSelection.put(trVertex.getHopID(), ExecType.FED);
						outSelection.put(trVertex.getHopID(), FederatedOutput.FOUT);
						changed = true;
						changedAny = true;
						if (FederatedPlannerTrace.shouldTrace(trHop))
							FederatedPlannerTrace.log(trHop, "MinST-TRTW-Repair",
									"promote TR " + trVertex.getHopID() + " to FED/FOUT to match TW");
					}
				}
			}
		}
		while (changed && iter < 4);
		return changedAny;
	}

		private boolean repairFederatedInputSelection(Map<Long, ExecType> execSelection,
				Map<Long, FederatedOutput> outSelection) {
		if (execSelection == null || outSelection == null)
			return false;
		boolean changed;
		boolean changedAny = false;
		int iter = 0;
		do {
			changed = false;
			iter++;
			Map<Long, FType> selectedFTypeMap = buildSelectedFTypeMap(execSelection, outSelection);
			for (Vertex vertex : memoTable.values()) {
				if (vertex == null || vertex.getHopRef() == null || vertex.getCaps() == null)
					continue;
				long hopId = vertex.getHopID();
				if (execSelection.getOrDefault(hopId, ExecType.CP) != ExecType.FED)
					continue;

				Hop hop = vertex.getHopRef();
				if (FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(hop, selectedFTypeMap))
					continue;

				ExecPlacementCaps caps = vertex.getCaps();
				if (caps.allowCP_LOUT || caps.allowCP_FOUT) {
					execSelection.put(hopId, ExecType.CP);
					outSelection.put(hopId, FederatedOutput.LOUT);
					selectedFTypeMap.put(hopId, null);
					changed = true;
					changedAny = true;
					if (FederatedPlannerTrace.shouldTrace(hop))
						FederatedPlannerTrace.log(hop, "MinST-FedInput-Repair",
								"demote parent " + hopId + " to CP/LOUT due unsatisfied FED inputs");
				}
			}
		}
			while (changed && iter < 4);
			return changedAny;
		}

	private boolean repairRequiredLocalInputSelection(Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		if (execSelection == null || outSelection == null)
			return false;
		boolean changedAny = false;
		Map<Long, FType> selectedFTypeMap = buildSelectedFTypeMap(execSelection, outSelection);
		for (Pair<Long, Long> parentChild : requiredLocalInputAdded) {
			if (parentChild == null)
				continue;
			long parentHopId = parentChild.getLeft();
			long childHopId = parentChild.getRight();
			if (execSelection.getOrDefault(parentHopId, ExecType.CP) != ExecType.CP)
				continue;

			Vertex parentVertex = memoTable.get(parentHopId);
			Vertex childVertex = memoTable.get(childHopId);
			if (parentVertex == null || childVertex == null
					|| parentVertex.getCaps() == null || childVertex.getCaps() == null)
				continue;

			ExecType childExec = execSelection.getOrDefault(childHopId, ExecType.CP);
			FederatedOutput childOut = outSelection.getOrDefault(childHopId, FederatedOutput.LOUT);
			boolean childHasLocalOutput = childExec == ExecType.CP
					|| childOut == FederatedOutput.LOUT
					|| childVertex.isDerivedFedFout();
			if (childHasLocalOutput)
				continue;

			Hop parentHop = parentVertex.getHopRef();
			ExecPlacementCaps parentCaps = parentVertex.getCaps();
			ExecPlacementCaps childCaps = childVertex.getCaps();
			FType adjustedCpFoutType = getAdjustedCpFoutType(parentVertex);
			if (parentCaps.allowCP_FOUT && adjustedCpFoutType != null
					&& hasSelectedFoutConsumerOpportunity(
							parentVertex, selectedFTypeMap, execSelection, outSelection, childHopId)) {
				execSelection.put(parentHopId, ExecType.CP);
				outSelection.put(parentHopId, FederatedOutput.FOUT);
				selectedFTypeMap.put(parentHopId, adjustedCpFoutType);
				changedAny = true;
				if (FederatedPlannerTrace.shouldTrace(parentHop)) {
					FederatedPlannerTrace.log(parentHop, "MinST-RequiredLocal-Repair",
							"preserve parent " + parentHopId
									+ " as CP/FOUT because selected FOUT consumers can materialize child "
									+ childHopId);
				}
				continue;
			}
			if (numOfWorkers > 1
					&& childCaps.allowCP_LOUT
					&& getConfiguredLocalToFedCtrlOverheadMs()
							> REQUIRED_LOCAL_REPAIR_DEMOTE_CTRL_PENALTY_THRESHOLD_MS) {
				execSelection.put(childHopId, ExecType.CP);
				outSelection.put(childHopId, FederatedOutput.LOUT);
				selectedFTypeMap.put(childHopId, null);
				changedAny = true;
				Hop childHop = childVertex.getHopRef();
				if (FederatedPlannerTrace.shouldTrace(childHop)) {
					FederatedPlannerTrace.log(childHop, "MinST-RequiredLocal-Repair",
							"demote child " + childHopId + " to CP/LOUT for CP parent " + parentHopId
									+ " under high local-to-federated control penalty");
				}
				continue;
			}
			boolean parentCanFed = parentCaps.allowFED_LOUT || parentCaps.allowFED_FOUT;
			if (parentCanFed && parentHop != null
					&& FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(parentHop, selectedFTypeMap)) {
				FederatedOutput promotedOut = parentCaps.allowFED_LOUT
						? FederatedOutput.LOUT
						: FederatedOutput.FOUT;
				execSelection.put(parentHopId, ExecType.FED);
				outSelection.put(parentHopId, promotedOut);
				selectedFTypeMap.put(parentHopId, parentVertex.getDataType());
				changedAny = true;
				if (FederatedPlannerTrace.shouldTrace(parentHop)) {
					FederatedPlannerTrace.log(parentHop, "MinST-RequiredLocal-Repair",
							"promote parent " + parentHopId + " to FED/" + promotedOut
									+ " because child " + childHopId + " has no local output");
				}
				continue;
			}

			if (childCaps.allowCP_LOUT) {
				execSelection.put(childHopId, ExecType.CP);
				outSelection.put(childHopId, FederatedOutput.LOUT);
				selectedFTypeMap.put(childHopId, null);
				changedAny = true;
				Hop childHop = childVertex.getHopRef();
				if (FederatedPlannerTrace.shouldTrace(childHop)) {
					FederatedPlannerTrace.log(childHop, "MinST-RequiredLocal-Repair",
							"demote child " + childHopId + " to CP/LOUT for CP parent " + parentHopId);
				}
			}
		}
		return changedAny;
	}

	private boolean repairCpfoutPropagationSelection(Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		if (execSelection == null || outSelection == null)
			return false;
		boolean changed;
		boolean changedAny = false;
		int iter = 0;
		do {
			changed = false;
			iter++;
			Map<Long, FType> selectedFTypeMap = buildSelectedFTypeMap(execSelection, outSelection);
			for (Vertex vertex : memoTable.values()) {
				if (vertex == null || vertex.getHopRef() == null || vertex.getCaps() == null)
					continue;
				long hopId = vertex.getHopID();
				ExecPlacementCaps caps = vertex.getCaps();
				if (!caps.allowCP_FOUT)
					continue;

				Hop hop = vertex.getHopRef();
				if (shouldSkipCpfoutPropagationRepair(hop))
					continue;

				ExecType exec = execSelection.getOrDefault(hopId, ExecType.CP);
				FederatedOutput out = outSelection.getOrDefault(hopId, FederatedOutput.LOUT);
				// CP/FOUT propagation exists to materialize an upstream local placement for a
				// selected downstream FOUT chain. If the current selection already exposes a
				// federated output, the chain is preserved and we must not rewrite a raw FED/FOUT
				// decision down to CP/FOUT. Doing so can pull iterative federated kernels (for
				// example kmeans under 1 worker) into slow CP elementwise loops even though the
				// original FOUT path is already valid and cheaper at runtime.
				if (out == FederatedOutput.FOUT)
					continue;

				// MinST parity-follow: if the raw cut already selected FED/LOUT, and a
				// selected downstream FOUT chain makes FOUT desirable, prefer promoting the
				// existing federated path to FED/FOUT instead of rewriting it to CP/FOUT.
				// Rewriting a raw FED/LOUT aggregate into CP/FOUT can pull heavy iterative
				// kernels into local ba(+*) loops even though the runtime supports keeping the
				// hop federated and only changing its output placement.
				FType adjustedType = getAdjustedCpFoutType(vertex);
				FType promotedFedType = vertex.getDataType() != null
						? vertex.getDataType()
						: adjustedType != null ? adjustedType : FType.BROADCAST;
				if (exec == ExecType.FED && caps.allowFED_FOUT) {
					Map<Long, FType> promotedFedFTypeMap = new HashMap<>(selectedFTypeMap);
					promotedFedFTypeMap.put(hopId, promotedFedType);
					if (findSelectedFoutConsumerOpportunity(
							hop, promotedFedFTypeMap, execSelection, outSelection, new HashSet<>()) != null
							&& FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(
									hop, promotedFedFTypeMap)) {
						outSelection.put(hopId, FederatedOutput.FOUT);
						selectedFTypeMap.put(hopId, promotedFedType);
						changed = true;
						changedAny = true;
						if (FederatedPlannerTrace.shouldTrace(hop)) {
							FederatedPlannerTrace.log(hop, "MinST-CpFout-Repair",
									"promote " + hopId
											+ " from FED/LOUT to FED/FOUT to preserve selected FOUT consumer chain");
						}
						continue;
					}
				}

				if (adjustedType == null)
					continue;

				Map<Long, FType> candidateFTypeMap = new HashMap<>(selectedFTypeMap);
				candidateFTypeMap.put(hopId, adjustedType);

				if (!hasSelectedFoutConsumerOpportunity(vertex, candidateFTypeMap, execSelection, outSelection))
					continue;

				execSelection.put(hopId, ExecType.CP);
				outSelection.put(hopId, FederatedOutput.FOUT);
				selectedFTypeMap.put(hopId, adjustedType);
				changed = true;
				changedAny = true;
				if (FederatedPlannerTrace.shouldTrace(hop)) {
					FederatedPlannerTrace.log(hop, "MinST-CpFout-Repair",
							"switch " + hopId + " to CP/FOUT to preserve selected FOUT consumer chain");
				}
			}
		}
		while (changed && iter < 4);
		return changedAny;
	}

	private FType getAdjustedCpFoutType(Vertex vertex) {
		if (vertex == null || vertex.getHopRef() == null)
			return null;
		FType cpFoutType = vertex.getCpFoutDataType();
		if (cpFoutType == null)
			cpFoutType = vertex.getDataType();
		if (cpFoutType == null)
			return null;
		return FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(vertex.getHopRef(), cpFoutType);
	}

	private boolean shouldSkipCpfoutPropagationRepair(Hop hop) {
		if (hop == null)
			return true;
		if (!(hop instanceof DataOp))
			return false;
		Types.OpOpData op = ((DataOp) hop).getOp();
		return op == Types.OpOpData.TRANSIENTREAD || op == Types.OpOpData.TRANSIENTWRITE;
	}

	private boolean hasSelectedFoutConsumerOpportunity(Vertex vertex, Map<Long, FType> candidateFTypeMap,
			Map<Long, ExecType> execSelection, Map<Long, FederatedOutput> outSelection) {
		return hasSelectedFoutConsumerOpportunity(vertex, candidateFTypeMap, execSelection, outSelection, -1L);
	}

	private boolean hasSelectedFoutConsumerOpportunity(Vertex vertex, Map<Long, FType> candidateFTypeMap,
			Map<Long, ExecType> execSelection, Map<Long, FederatedOutput> outSelection,
			long requiredLocalChildHopId) {
		Hop hop = (vertex != null) ? vertex.getHopRef() : null;
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return false;
		FType adjustedType = getAdjustedCpFoutType(vertex);
		SelectedFoutOpportunity opportunity = findSelectedFoutConsumerOpportunity(
				hop, candidateFTypeMap, execSelection, outSelection, new HashSet<>());
		if (opportunity == null)
			return false;
		double weightedCpfoutUploadCost = computeWeightedCpfoutUploadCost(
				vertex, adjustedType, opportunity.downstreamWeight);
		weightedCpfoutUploadCost = Math.max(weightedCpfoutUploadCost,
				computeMaxRequiredLocalChildUploadCost(
						vertex, execSelection, outSelection, requiredLocalChildHopId,
						opportunity.downstreamWeight));
		double federatedUnaryCost = computeEstimatedFederatedUnaryCost(vertex);
		if (opportunity.reachesFederatedTerminal
				&& weightedCpfoutUploadCost > 0.0
				&& federatedUnaryCost > 0.0
				&& weightedCpfoutUploadCost > federatedUnaryCost) {
			if (FederatedPlannerTrace.shouldTrace(hop)) {
				FederatedPlannerTrace.log(hop, "MinST-CpFout-Repair",
						String.format("skip CP/FOUT propagation for selected FOUT chain terminal %d because weighted upload %.6f exceeds federated unary %.6f (downstreamWeight=%.6f)",
								opportunity.terminalHopId, weightedCpfoutUploadCost, federatedUnaryCost,
								opportunity.downstreamWeight));
			}
			return false;
		}
		return true;
	}

	private double computeMaxRequiredLocalChildUploadCost(Vertex parentVertex,
			Map<Long, ExecType> execSelection, Map<Long, FederatedOutput> outSelection,
			long requiredLocalChildHopId, double downstreamWeight) {
		if (parentVertex == null || execSelection == null || outSelection == null)
			return 0.0;
		double maxUploadCost = 0.0;
		long parentHopId = parentVertex.getHopID();
		for (Pair<Long, Long> parentChild : requiredLocalInputAdded) {
			if (parentChild == null || parentChild.getLeft() == null || parentChild.getRight() == null)
				continue;
			if (parentChild.getLeft() != parentHopId)
				continue;
			long childHopId = parentChild.getRight();
			Vertex childVertex = memoTable.get(childHopId);
			if (childVertex == null)
				continue;
			ExecType childExec = execSelection.getOrDefault(childHopId, ExecType.CP);
			FederatedOutput childOut = outSelection.getOrDefault(childHopId, FederatedOutput.LOUT);
			boolean childHasLocalOutput = childExec == ExecType.CP
					|| childOut == FederatedOutput.LOUT
					|| childVertex.isDerivedFedFout();
			if (childHasLocalOutput && childHopId != requiredLocalChildHopId)
				continue;
			maxUploadCost = Math.max(maxUploadCost,
					computeRequiredLocalChildUploadCost(parentVertex, childHopId, downstreamWeight));
		}
		return maxUploadCost;
	}

	private double computeRequiredLocalChildUploadCost(Vertex parentVertex, long childHopId,
			double downstreamWeight) {
		if (parentVertex == null || childHopId < 0)
			return 0.0;
		Vertex childVertex = memoTable.get(childHopId);
		if (childVertex == null || childVertex.getHopRef() == null)
			return 0.0;
		Hop childHop = childVertex.getHopRef();
		if (childHop.getDataType() == null || !childHop.getDataType().isMatrix())
			return 0.0;
		FType uploadType = getAdjustedCpFoutType(childVertex);
		if (uploadType == null)
			uploadType = childVertex.getDataType();
		if (uploadType == null)
			uploadType = FType.BROADCAST;
		double baseUpload = childVertex.getCpUploadCostWithoutWeight();
		if (Double.isNaN(baseUpload) || baseUpload <= 0.0) {
			double effectiveUploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(childHop);
			if (effectiveUploadMem > 0.0) {
				baseUpload = FederatedCostModel.computeUploadNetworkCost(
						effectiveUploadMem, uploadType, numOfWorkers);
			}
		}
		if (Double.isNaN(baseUpload) || baseUpload <= 0.0)
			return 0.0;
		double forwardingPenalty = FederatedCostModel.computeLocalToFedForwardingPenalty(uploadType, numOfWorkers);
		double forwardingWeight = parentVertex.computeForwardingWeightOfChild(childVertex.getLoopContext());
		if (Double.isNaN(forwardingWeight) || forwardingWeight <= 0.0)
			forwardingWeight = Math.max(1.0, parentVertex.getNetworkWeight());
		forwardingWeight = Math.max(forwardingWeight, Math.max(1.0, downstreamWeight));
		return forwardingWeight * (baseUpload + forwardingPenalty);
	}

	private SelectedFoutOpportunity findSelectedFoutConsumerOpportunity(Hop hop,
			Map<Long, FType> candidateFTypeMap, Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection, Set<Long> visited) {
		if (hop == null || hop.getParent() == null || hop.getParent().isEmpty())
			return null;
		if (!visited.add(hop.getHopID()))
			return null;
		SelectedFoutOpportunity best = null;
		for (Hop consumer : hop.getParent()) {
			if (consumer == null)
				continue;
			long consumerHopId = consumer.getHopID();
			FederatedOutput consumerOut = outSelection.getOrDefault(consumerHopId, FederatedOutput.LOUT);
			if (consumerOut != FederatedOutput.FOUT)
				continue;
			ExecType consumerExec = execSelection.getOrDefault(consumerHopId, ExecType.CP);
			if (consumerExec == ExecType.FED
					&& !FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(consumer, candidateFTypeMap)) {
				continue;
			}
			double consumerWeight = computeSelectedFoutConsumerWeight(consumer, execSelection, outSelection);
			boolean reachesFederatedTerminal = consumerExec == ExecType.FED;
			SelectedFoutOpportunity downstream = findSelectedFoutConsumerOpportunity(
					consumer, candidateFTypeMap, execSelection, outSelection, visited);
			if (downstream != null) {
				consumerWeight = Math.max(consumerWeight, downstream.downstreamWeight);
				reachesFederatedTerminal |= downstream.reachesFederatedTerminal;
			}
			SelectedFoutOpportunity candidate = new SelectedFoutOpportunity(
					(downstream != null && downstream.reachesFederatedTerminal)
							? downstream.terminalHopId
							: consumerHopId,
					reachesFederatedTerminal, consumerWeight);
			best = chooseBetterSelectedFoutOpportunity(best, candidate);
		}
		visited.remove(hop.getHopID());
		return best;
	}

	private SelectedFoutOpportunity chooseBetterSelectedFoutOpportunity(
			SelectedFoutOpportunity current, SelectedFoutOpportunity candidate) {
		if (candidate == null)
			return current;
		if (current == null)
			return candidate;
		if (candidate.reachesFederatedTerminal != current.reachesFederatedTerminal)
			return candidate.reachesFederatedTerminal ? candidate : current;
		return candidate.downstreamWeight > current.downstreamWeight ? candidate : current;
	}

	private double computeSelectedFoutConsumerWeight(Hop consumer, Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		double weight = 1.0;
		Vertex consumerVertex = memoTable.get(consumer.getHopID());
		if (consumerVertex != null) {
			weight = Math.max(weight,
					Math.max(consumerVertex.getNetworkWeight(), consumerVertex.getOpWeight()));
		}
		if (consumer instanceof DataOp
				&& ((DataOp) consumer).getOp() == Types.OpOpData.TRANSIENTWRITE) {
			weight = Math.max(weight,
					computeSelectedTransientReadFamilyWeight(consumer.getHopID(), execSelection, outSelection));
		}
		return weight;
	}

	private double computeSelectedTransientReadFamilyWeight(long transientWriteHopId,
			Map<Long, ExecType> execSelection, Map<Long, FederatedOutput> outSelection) {
		double weight = 1.0;
		for (Vertex candidate : memoTable.values()) {
			if (candidate == null || !Objects.equals(transientWriteHopId, candidate.getTransientWriteHopId()))
				continue;
			long hopId = candidate.getHopID();
			if (outSelection.getOrDefault(hopId, FederatedOutput.LOUT) != FederatedOutput.FOUT)
				continue;
			if (execSelection.getOrDefault(hopId, ExecType.CP) != ExecType.FED)
				continue;
			weight = Math.max(weight,
					Math.max(candidate.getNetworkWeight(), candidate.getOpWeight()));
		}
		return weight;
	}

	private double computeWeightedCpfoutUploadCost(Vertex vertex, FType adjustedType,
			double downstreamWeight) {
		if (vertex == null || adjustedType == null)
			return 0.0;
		double baseUpload = vertex.getCpUploadCostWithoutWeight();
		if (Double.isNaN(baseUpload) || baseUpload <= 0.0) {
			Hop hop = vertex.getHopRef();
			double effectiveUploadMem = FederatedCostModel.getEffectiveUploadMemEstimate(hop);
			if (effectiveUploadMem > 0.0) {
				baseUpload = FederatedCostModel.computeUploadNetworkCost(
						effectiveUploadMem, adjustedType, numOfWorkers);
			}
		}
		if (Double.isNaN(baseUpload) || baseUpload <= 0.0)
			return 0.0;
		baseUpload += FederatedCostModel.computeLocalToFedForwardingPenalty(adjustedType, numOfWorkers);
		double effectiveWeight = Math.max(1.0,
				Math.max(Math.max(vertex.getNetworkWeight(), vertex.getOpWeight()), downstreamWeight));
		return effectiveWeight * baseUpload;
	}

	private static final class SelectedFoutOpportunity {
		private final long terminalHopId;
		private final boolean reachesFederatedTerminal;
		private final double downstreamWeight;

		private SelectedFoutOpportunity(long terminalHopId, boolean reachesFederatedTerminal,
				double downstreamWeight) {
			this.terminalHopId = terminalHopId;
			this.reachesFederatedTerminal = reachesFederatedTerminal;
			this.downstreamWeight = downstreamWeight;
		}
	}

	private double computeEstimatedFederatedUnaryCost(Vertex vertex) {
		if (vertex == null || vertex.getHopRef() == null)
			return 0.0;
		Hop hop = vertex.getHopRef();
		double cpCost = vertex.getOpCostWithWeight();
		double fedOverhead = 0.0;
		if (!(hop instanceof DataOp)) {
			fedOverhead = vertex.getOpWeight()
					* FederatedCostModel.computeFedCoordinationCost(numOfWorkers);
		}
		fedOverhead = FederatedCostModel.adjustFedCoordinationCost(hop, vertex.getDataType(), fedOverhead);
		boolean hasMatrixInputForFedCompute = false;
		boolean hasNonBroadcastMatrixInputForFedCompute = false;
		if (hop.getInput() != null) {
			for (Hop in : hop.getInput()) {
				if (in == null || in.getDataType() == null || !in.getDataType().isMatrix())
					continue;
				hasMatrixInputForFedCompute = true;
				Vertex inVertex = memoTable.get(in.getHopID());
				FType inType = (inVertex != null) ? inVertex.getDataType() : null;
				if (inType != null && inType != FType.BROADCAST) {
					hasNonBroadcastMatrixInputForFedCompute = true;
					break;
				}
			}
		}
			boolean broadcastOnlyFedCompute = hasMatrixInputForFedCompute && !hasNonBroadcastMatrixInputForFedCompute;
			double defaultFedComputeCost = FederatedCostModel.computeFederatedComputeCost(
					hop, cpCost, numOfWorkers, broadcastOnlyFedCompute);
			double fedComputeCost = FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
					hop, vertex.getDataType(), defaultFedComputeCost);
			fedComputeCost = FederatedCostModel.computeNativeFederatedIndexingCost(
					hop, vertex.getDataType(), fedComputeCost);
			double fedInstructionLatencyCost = FederatedCostModel.computeControlDominatedFederatedInstructionCost(
					hop, vertex.getDataType(), vertex.getOpWeight(), numOfWorkers, broadcastOnlyFedCompute);
		return fedComputeCost + fedOverhead
				+ fedInstructionLatencyCost
				+ FederatedCostModel.computeSingleWorkerFedExecPenalty(
						hop, vertex.getOpWeight(), numOfWorkers);
	}

		private boolean repairCapsInconsistentSelection(Map<Long, ExecType> execSelection,
				Map<Long, FederatedOutput> outSelection) {
		if (execSelection == null || outSelection == null)
			return false;
		boolean changed;
		boolean changedAny = false;
		int iter = 0;
		do {
			changed = false;
			iter++;
			for (Vertex vertex : memoTable.values()) {
				if (vertex == null || vertex.getHopRef() == null || vertex.getCaps() == null)
					continue;
				long hopId = vertex.getHopID();
				ExecPlacementCaps caps = vertex.getCaps();
				ExecType exec = execSelection.getOrDefault(hopId, ExecType.CP);
				FederatedOutput out = outSelection.getOrDefault(hopId, FederatedOutput.LOUT);
				if (caps.get(exec, out))
					continue;

				ExecType newExec = exec;
				FederatedOutput newOut = out;
				if (exec == ExecType.CP && out == FederatedOutput.FOUT && caps.allowCP_LOUT)
					newOut = FederatedOutput.LOUT;
				else if (exec == ExecType.FED && out == FederatedOutput.FOUT && caps.allowFED_LOUT)
					newOut = FederatedOutput.LOUT;
				else if (exec == ExecType.FED && out == FederatedOutput.LOUT && caps.allowFED_FOUT)
					newOut = FederatedOutput.FOUT;
				else if (exec == ExecType.CP && out == FederatedOutput.LOUT && caps.allowCP_FOUT)
					newOut = FederatedOutput.FOUT;
				else if (caps.allowCP_LOUT) {
					newExec = ExecType.CP;
					newOut = FederatedOutput.LOUT;
				}
				else if (caps.allowCP_FOUT) {
					newExec = ExecType.CP;
					newOut = FederatedOutput.FOUT;
				}
				else if (caps.allowFED_LOUT) {
					newExec = ExecType.FED;
					newOut = FederatedOutput.LOUT;
				}
				else if (caps.allowFED_FOUT) {
					newExec = ExecType.FED;
					newOut = FederatedOutput.FOUT;
				}
				else {
					continue;
				}

				if (newExec != exec || newOut != out) {
					execSelection.put(hopId, newExec);
					outSelection.put(hopId, newOut);
					changed = true;
					changedAny = true;
					Hop hop = vertex.getHopRef();
					if (FederatedPlannerTrace.shouldTrace(hop))
						FederatedPlannerTrace.log(hop, "MinST-Caps-Repair",
								"adjust " + exec + "/" + out + " -> " + newExec + "/" + newOut
										+ " due caps [CP_LOUT=" + caps.allowCP_LOUT
										+ ",CP_FOUT=" + caps.allowCP_FOUT
										+ ",FED_LOUT=" + caps.allowFED_LOUT
										+ ",FED_FOUT=" + caps.allowFED_FOUT + "]");
				}
			}
		}
		while (changed && iter < 4);
		return changedAny;
	}

	private Map<Long, FType> buildSelectedFTypeMap(Map<Long, ExecType> execSelection,
			Map<Long, FederatedOutput> outSelection) {
		Map<Long, FType> selected = new HashMap<>();
		for (Vertex vertex : memoTable.values()) {
			if (vertex == null)
				continue;
			long hopId = vertex.getHopID();
			ExecType exec = execSelection.getOrDefault(hopId, ExecType.CP);
			FederatedOutput out = outSelection.getOrDefault(hopId, FederatedOutput.LOUT);
			if (out == FederatedOutput.FOUT) {
				FType type = (exec == ExecType.FED) ? vertex.getDataType() : vertex.getCpFoutDataType();
				if (exec == ExecType.CP)
					type = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(vertex.getHopRef(), type);
				selected.put(hopId, type != null ? type : FType.BROADCAST);
			}
			else {
				selected.put(hopId, null);
			}
		}
		return selected;
	}

	private void logSelectedDecision(Vertex vertex, Set<Long> sourceSide) {
		Hop hop = (vertex != null) ? vertex.getHopRef() : null;
		if (!FederatedPlannerTrace.shouldTrace(hop))
			return;

		long hopID = vertex.getHopID();
		long cId = FederatedPlanMinSTPlanner.computeId(hopID);
		long pId = FederatedPlanMinSTPlanner.placementId(hopID);

		boolean cSide = sourceSide.contains(cId);
		boolean pSide = sourceSide.contains(pId);
		ExecType exec = cSide ? ExecType.FED : ExecType.CP;
		FederatedOutput out = pSide ? FederatedOutput.FOUT : FederatedOutput.LOUT;

		double unaryCP = getEdgeWeightOrZero(leafedSource, cId);
		double unaryFED = getEdgeWeightOrZero(cId, rootLocalSink);
		double uploadPtoC = getEdgeWeightOrZero(pId, cId);
		double uploadPtoT = getEdgeWeightOrZero(pId, rootLocalSink);
		double downloadCtoP = getEdgeWeightOrZero(cId, pId);

		ExecPlacementCaps caps = vertex.getCaps();
		FederatedPlannerTrace.log(hop, "MinST-Select", String.format(
				"selected=%s/%s side[c=%s,p=%s] unary[CP=%.6f,FED=%.6f] conv[p->c=%.6f,p->t=%.6f,c->p=%.6f] caps=[CP_LOUT=%s,CP_FOUT=%s,FED_LOUT=%s,FED_FOUT=%s] fType=%s cpFoutType=%s",
				exec, out, cSide ? "S" : "T", pSide ? "S" : "T",
				unaryCP, unaryFED, uploadPtoC, uploadPtoT, downloadCtoP,
				caps.allowCP_LOUT, caps.allowCP_FOUT, caps.allowFED_LOUT, caps.allowFED_FOUT,
				vertex.getDataType(), vertex.getCpFoutDataType()));

		List<String> cutEdges = collectIncidentCutEdges(cId, pId, sourceSide,
				FederatedPlannerTrace.getMaxEdgeLogsPerHop());
		for (String edgeLine : cutEdges) {
			FederatedPlannerTrace.log(hop, "MinST-CutEdge", edgeLine);
		}
	}

	private void logFinalSelectedDecision(Vertex vertex, Set<Long> sourceSide, ExecType finalExec,
			FederatedOutput finalOut, FType finalFType, boolean derivedFedFout) {
		Hop hop = (vertex != null) ? vertex.getHopRef() : null;
		if (!FederatedPlannerTrace.shouldTrace(hop))
			return;

		long hopID = vertex.getHopID();
		long cId = FederatedPlanMinSTPlanner.computeId(hopID);
		long pId = FederatedPlanMinSTPlanner.placementId(hopID);
		ExecType rawExec = sourceSide.contains(cId) ? ExecType.FED : ExecType.CP;
		FederatedOutput rawOut = sourceSide.contains(pId) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
		boolean repaired = rawExec != finalExec || rawOut != finalOut;
		ExecPlacementCaps caps = vertex.getCaps();
		EffectiveDemandClass demandClass = getEffectiveDemandClass(hopID);

		FederatedPlannerTrace.log(hop, "MinST-FinalSelect", String.format(
				"raw=%s/%s final=%s/%s repaired=%s derivedFedFout=%s demand=%s caps=[CP_LOUT=%s,CP_FOUT=%s,FED_LOUT=%s,FED_FOUT=%s] finalFType=%s cpFoutType=%s",
				rawExec, rawOut, finalExec, finalOut, repaired, derivedFedFout,
				demandClass, caps.allowCP_LOUT, caps.allowCP_FOUT, caps.allowFED_LOUT, caps.allowFED_FOUT,
				finalFType, vertex.getCpFoutDataType()));
	}

	private List<String> collectIncidentCutEdges(long cId, long pId, Set<Long> sourceSide, int maxEdges) {
		Set<DefaultWeightedEdge> seen = new HashSet<>();
		List<CutEdgeInfo> cutEdges = new ArrayList<>();
		long[] focusNodes = new long[] { cId, pId };
		for (long nodeId : focusNodes) {
			for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(nodeId)) {
				collectCutEdge(edge, sourceSide, seen, cutEdges);
			}
			for (DefaultWeightedEdge edge : graph.incomingEdgesOf(nodeId)) {
				collectCutEdge(edge, sourceSide, seen, cutEdges);
			}
		}

		if (cutEdges.isEmpty()) {
			return Collections.singletonList("none");
		}

		cutEdges.sort(Comparator.comparingDouble((CutEdgeInfo info) -> info.weight).reversed());
		int limit = Math.max(1, maxEdges);
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < cutEdges.size() && i < limit; i++) {
			CutEdgeInfo info = cutEdges.get(i);
			lines.add(String.format("%s -> %s w=%.6f",
					describeNode(info.src), describeNode(info.dst), info.weight));
		}
		if (cutEdges.size() > limit) {
			lines.add("... +" + (cutEdges.size() - limit) + " more cut edges");
		}
		return lines;
	}

	private void collectCutEdge(DefaultWeightedEdge edge, Set<Long> sourceSide, Set<DefaultWeightedEdge> seen,
			List<CutEdgeInfo> cutEdges) {
		if (edge == null || !seen.add(edge))
			return;
		long src = graph.getEdgeSource(edge);
		long dst = graph.getEdgeTarget(edge);
		if (sourceSide.contains(src) && !sourceSide.contains(dst)) {
			cutEdges.add(new CutEdgeInfo(src, dst, graph.getEdgeWeight(edge)));
		}
	}

	private double getEdgeWeightOrZero(long src, long dst) {
		DefaultWeightedEdge edge = graph.getEdge(src, dst);
		return (edge == null) ? 0.0 : graph.getEdgeWeight(edge);
	}

	private static String describeNode(long nodeId) {
		if (nodeId == leafedSource)
			return "S";
		if (nodeId == rootLocalSink)
			return "T";
		if (nodeId <= auxNodeBase)
			return "AUX(" + nodeId + ")";
		long hopId = nodeId >> 2;
		long role = nodeId & 3L;
		if (role == 0L)
			return "C(" + hopId + ")";
		if (role == 1L)
			return "P(" + hopId + ")";
		if (role == 2L)
			return "L(" + hopId + ")";
		return "N(" + nodeId + ")";
	}

	private static class CutEdgeInfo {
		private final long src;
		private final long dst;
		private final double weight;

		private CutEdgeInfo(long src, long dst, double weight) {
			this.src = src;
			this.dst = dst;
			this.weight = weight;
		}
	}

	public static class ExecPlacementCaps {
		public enum FedFoutMode {
			DISABLED,
			NATIVE,
			DERIVED_REFED
		}

		public boolean allowCP_LOUT = true;
		public boolean allowCP_FOUT = true;
		public boolean allowFED_LOUT = true;
		public boolean allowFED_FOUT = true;
		public FedFoutMode fedFoutMode = FedFoutMode.DISABLED;

		public ExecPlacementCaps() {
		}

		public ExecPlacementCaps(ExecPlacementCaps other) {
			this.allowCP_LOUT = other.allowCP_LOUT;
			this.allowCP_FOUT = other.allowCP_FOUT;
			this.allowFED_LOUT = other.allowFED_LOUT;
			this.allowFED_FOUT = other.allowFED_FOUT;
			this.fedFoutMode = other.fedFoutMode;
		}

		public boolean get(ExecType exec, FederatedOutput out) {
			if (exec == ExecType.CP && out == FederatedOutput.LOUT)
				return allowCP_LOUT;
			if (exec == ExecType.CP && out == FederatedOutput.FOUT)
				return allowCP_FOUT;
			if (exec == ExecType.FED && out == FederatedOutput.LOUT)
				return allowFED_LOUT;
			if (exec == ExecType.FED && out == FederatedOutput.FOUT)
				return allowFED_FOUT;
			return false;
		}

		public void set(ExecType exec, FederatedOutput out, boolean value) {
			if (exec == ExecType.CP && out == FederatedOutput.LOUT)
				allowCP_LOUT = value;
			else if (exec == ExecType.CP && out == FederatedOutput.FOUT)
				allowCP_FOUT = value;
			else if (exec == ExecType.FED && out == FederatedOutput.LOUT)
				allowFED_LOUT = value;
			else if (exec == ExecType.FED && out == FederatedOutput.FOUT)
				allowFED_FOUT = value;
		}

		public boolean hasAny() {
			return allowCP_LOUT || allowCP_FOUT || allowFED_LOUT || allowFED_FOUT;
		}

		public boolean isDerivedFedFout() {
			return fedFoutMode == FedFoutMode.DERIVED_REFED;
		}
	}

	public static class LoopCarryEdge {
		private final long endWriterHopId;
		private final long frontReaderHopId;
		private final double weight;

		public LoopCarryEdge(long endWriterHopId, long frontReaderHopId, double weight) {
			this.endWriterHopId = endWriterHopId;
			this.frontReaderHopId = frontReaderHopId;
			this.weight = weight;
		}

		public long getEndWriterHopId() {
			return endWriterHopId;
		}

		public long getFrontReaderHopId() {
			return frontReaderHopId;
		}

		public double getWeight() {
			return weight;
		}
	}

	public static class Vertex {
		public final Hop hop_;
		public final long hopId_;

		public final Privacy privacy_;
		public final FType dataType_;
		public final FType cpFoutDataType_;
		public final ExecPlacementCaps caps_;

		public final boolean isFedExecutable_;
		public final boolean isLocalExecutable_;

		private double opCostWithWeight_;
		private double uploadCostWithoutWeight_;
		private double cpUploadCostWithoutWeight_;
		private double downloadCostWithoutWeight_;
		private double fedInputPreparationCostWithWeight_;
		private double sourceOutputMemEstimateOverride_ = -1.0;
		private boolean stableFederatedInputRead_ = false;

		private double opWeight; // Weight used to calculate cost based on hop execution frequency
		private double networkWeight; // Weight used to calculate cost based on hop execution frequency
		private List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists
		// Currently unused; retained for potential TR/TW hard-constraint handling.
		private Long transientWriteHopId;
		private int numParents = 1;

		public Vertex(Hop hop, Privacy privacy, FType dataType, ExecPlacementCaps caps) {
			this(hop, privacy, dataType, dataType, caps);
		}

		public Vertex(Hop hop, Privacy privacy, FType dataType, FType cpFoutDataType, ExecPlacementCaps caps) {
			this.hop_ = hop;
			this.hopId_ = hop.getHopID();
			this.privacy_ = privacy;
			this.dataType_ = dataType;
			this.cpFoutDataType_ = cpFoutDataType;
			this.caps_ = caps;

			isFedExecutable_ = caps != null && (caps.allowFED_LOUT || caps.allowFED_FOUT);
			isLocalExecutable_ = caps != null && (caps.allowCP_LOUT || caps.allowCP_FOUT);
		}

		public Hop getHopRef() {
			return hop_;
		}

		public long getHopID() {
			return hopId_;
		}

		public Privacy getPrivacy() {
			return privacy_;
		}

		public FType getDataType() {
			return dataType_;
		}

		public FType getCpFoutDataType() {
			return cpFoutDataType_;
		}

		public ExecPlacementCaps getCaps() {
			return caps_;
		}

		public boolean isDerivedFedFout() {
			return caps_ != null && caps_.isDerivedFedFout();
		}

		public double getOpCostWithWeight() {
			return opCostWithWeight_;
		}

		public double getUploadCostWithoutWeight() {
			return uploadCostWithoutWeight_;
		}

		public double getCpUploadCostWithoutWeight() {
			return cpUploadCostWithoutWeight_;
		}

		public double getDownloadCostWithoutWeight() {
			return downloadCostWithoutWeight_;
		}

		public double getFedInputPreparationCostWithWeight() {
			return fedInputPreparationCostWithWeight_;
		}

		public double getSourceOutputMemEstimateOverride() {
			return sourceOutputMemEstimateOverride_;
		}

		public boolean isStableFederatedInputRead() {
			return stableFederatedInputRead_;
		}

		public double getOpWeight() {
			return opWeight;
		}

		public double getNetworkWeight() {
			return networkWeight;
		}

		public void setSourceOutputMemEstimateOverride(double sourceOutputMemEstimateOverride) {
			sourceOutputMemEstimateOverride_ = sourceOutputMemEstimateOverride;
		}

		public void setStableFederatedInputRead(boolean stableFederatedInputRead) {
			stableFederatedInputRead_ = stableFederatedInputRead;
		}

		public List<Pair<Long, Double>> getLoopContext() {
			return loopContext;
		}

		public int getNumParents() {
			return numParents;
		}

		public void setMetadata(double opWeight, double networkWeight, List<Pair<Long, Double>> loopContext) {
			this.opWeight = opWeight;
			this.networkWeight = networkWeight;
			this.loopContext = loopContext;
		}

		public void setCost(double opCostWithWeight, double uploadCostWithoutWeight,
				double downloadCostWithoutWeight) {
			this.opCostWithWeight_ = opCostWithWeight;
			this.uploadCostWithoutWeight_ = uploadCostWithoutWeight;
			this.cpUploadCostWithoutWeight_ = uploadCostWithoutWeight;
			this.downloadCostWithoutWeight_ = downloadCostWithoutWeight;
		}

		public void setCpUploadCostWithoutWeight(double cpUploadCostWithoutWeight) {
			this.cpUploadCostWithoutWeight_ = cpUploadCostWithoutWeight;
		}

		public void setFedInputPreparationCostWithWeight(double fedInputPreparationCostWithWeight) {
			this.fedInputPreparationCostWithWeight_ =
				Double.isFinite(fedInputPreparationCostWithWeight) && fedInputPreparationCostWithWeight > 0.0
					? fedInputPreparationCostWithWeight
					: 0.0;
		}

		public void setTransientWriteHopId(Long transientWriteHopId) {
			this.transientWriteHopId = transientWriteHopId;
		}

		public Long getTransientWriteHopId() {
			return transientWriteHopId;
		}

		public void setNumParents(int numParents) {
			this.numParents = Math.max(1, numParents);
		}

		/**
		 * Estimates how many times this parent's output is forwarded to a child by
		 * amortizing the parent's networkWeight over loops the child does not execute.
		 *
		 * Example:
		 * parent loopContext = [(for1, 100), (while2, 10)]
		 * childLoopContext = [(for1, 100)]
		 * => forwardingWeight = networkWeight / 10 (child result reused across while2 iterations)
		 */
		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
			return FederatedPlannerUtils.computeForwardingWeightOfChild(
					networkWeight, loopContext, childLoopContext);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;
			if (!(o instanceof Vertex))
				return false;
			Vertex other = (Vertex) o;
			return hopId_ == other.hopId_ && privacy_ == other.privacy_ && dataType_ == other.dataType_;
		}

		@Override
		public int hashCode() {
			return Objects.hash(hopId_, privacy_, dataType_);
		}

		@Override
		public String toString() { // 디버깅 편의
			return "h" + hopId_ + ":" + privacy_ + ":" + dataType_;
		}
	}

}
