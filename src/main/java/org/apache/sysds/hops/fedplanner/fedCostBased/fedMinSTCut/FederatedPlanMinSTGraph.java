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
	private final Map<HyperEdgeKey, HyperEdgeGroup> parentChildHyperEdges = new HashMap<>();
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
		graph.addVertex(FederatedPlanMinSTPlanner.localityId(hopID));
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
		// Force locality node to sink side (has local output).
		addCap(leafedSource, lId, 0.0);
		addCap(lId, rootLocalSink, HARD_INF);
	}

	public void addNoLocalImplicationEdges(long hopId) {
		if (hopId <= 0)
			return;
		long cId = FederatedPlanMinSTPlanner.computeId(hopId);
		long pId = FederatedPlanMinSTPlanner.placementId(hopId);
		long lId = FederatedPlanMinSTPlanner.localityId(hopId);
		// lId represents the boolean "NO_LOCAL" (source side=true).
		// NO_LOCAL implies both FED execution and a federated output (FOUT).
		addCap(lId, pId, HARD_CONSTRAINT);
		addCap(lId, cId, HARD_CONSTRAINT);
	}

	public void setVertexCost(Vertex vertex) {
		long hopID = vertex.getHopID();
		long cId = FederatedPlanMinSTPlanner.computeId(hopID);
		ExecPlacementCaps caps = vertex.getCaps();
		boolean acL = caps.allowCP_LOUT;
		boolean acF = caps.allowCP_FOUT;
		boolean afL = caps.allowFED_LOUT;
		boolean afF = caps.allowFED_FOUT;

		double cpCost = vertex.getOpCostWithWeight();
		double fedOverhead = 0.0;
		if (!(vertex.getHopRef() instanceof DataOp)) {
			fedOverhead = vertex.getNetworkWeight() * FederatedCostModel.computeNetworkCost(0);
		}
		double fedCost = cpCost / Math.max(1, numOfWorkers) + fedOverhead;

		if (!acL && !acF)
			cpCost = HARD_INF;
		if (!afL && !afF)
			fedCost = HARD_INF;

		addCap(leafedSource, cId, cpCost);
		addCap(cId, rootLocalSink, fedCost);
	}

	public void addExecPlacementResultEdge(Vertex vertex) {
		Hop hop = vertex.getHopRef();
		if (HopUtils.isPrintOrPWrite(hop))
			return;
		long hopId = vertex.getHopID();
		long cId = FederatedPlanMinSTPlanner.computeId(hopId);
		long pId = FederatedPlanMinSTPlanner.placementId(hopId);
		long lId = FederatedPlanMinSTPlanner.localityId(hopId);
		if (hop instanceof DataOp &&
				((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			// DP parity: if a TRead is chosen as FED/FOUT but consumed by a CP parent,
			// the plan must pay the download needed to materialize local output.
			// Upload for TRead is modeled via parent-child forwarding edges.
			double trDownloadCost = vertex.getNetworkWeight() * vertex.getDownloadCostWithoutWeight();
			addCap(cId, lId, trDownloadCost);
			return;
		}
		double uploadCost = vertex.getNetworkWeight() * vertex.getCpUploadCostWithoutWeight();
		double downloadCost = vertex.getNetworkWeight() * vertex.getDownloadCostWithoutWeight();
		// Download is paid when we execute in FED and the hop needs to have a local materialization.
		addCap(cId, lId, downloadCost);
		if (vertex.isDerivedFedFout()) {
			// Derived FED/FOUT: FOUT is produced via refed from a local intermediate, so upload depends only on placement.
			addCap(pId, rootLocalSink, uploadCost);
			return;
		}
		addCap(pId, cId, uploadCost);
	}

	public void addParentChildNetEdge(Vertex childVertex, long childHopID,
			Vertex parentVertex, long parentHopID, boolean requiresFederatedInput) {
		long parentC = FederatedPlanMinSTPlanner.computeId(parentHopID);
		long childP = FederatedPlanMinSTPlanner.placementId(childHopID);
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
		// Use CP->FOUT upload cost here as well: a parent-child edge can represent
		// local (LOUT/CP) -> federated (FED) forwarding, and in such cases the CP->FOUT
		// upload FType (e.g., BROADCAST for vector axis mismatch) must be reflected.
		double uploadCost = childVertex.getCpUploadCostWithoutWeight();
		if (Double.isNaN(uploadCost) || uploadCost <= 0.0) {
			final double originalUploadCost = uploadCost;
			Hop childHop = childHopRef;
			double outputMemEstimate = FederatedCostModel.getEffectiveOutputMemEstimate(childHop);
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
		if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0)) {
			uploadCost += FederatedCostModel.computeLocalToFedForwardingPenalty(
					uploadConversionType, numOfWorkers);
		}
		double uploadWeighted = forwardingWeight * uploadCost;

		// If a parent executes in CP, the child must have a local materialization (either natively or via download).
		addRequiredLocalInputEdge(parentHopID, childHopID);
		// Cost-model policy: matrix inputs can require local->federated boundary transfer
		// whenever the parent executes FED, regardless of OPTIONAL/REQUIRED classification.
		// Upload hints are still used by rewire/materialization policy, but they no longer
		// gate parent-child boundary cost edges in MinST.
		if (!matrixInput)
			return;
		addParentChildHyperEdge(parentC, childP, HyperEdgeDirection.UPLOAD, uploadConversionType, uploadWeighted);
	}

	public void addLoopCarryEdge(long endWriterHopId, long frontReaderHopId, double weight) {
		if (weight <= 0.0 || endWriterHopId <= 0 || frontReaderHopId <= 0)
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
		}

	public void addTransWriteInputPlacementConsistencyEdge(long twId, long inputHopId) {
		if (twId <= 0 || inputHopId <= 0)
			return;
		if (!twInputPlacementConsistencyAdded.add(Pair.of(twId, inputHopId)))
			return;
		long twP = FederatedPlanMinSTPlanner.placementId(twId);
		long inputP = FederatedPlanMinSTPlanner.placementId(inputHopId);
		addXorEdge(twP, inputP, HARD_CONSTRAINT);
	}

	public void addRequiredLocalInputEdge(long parentHopId, long childHopId) {
		if (parentHopId <= 0 || childHopId <= 0)
			return;
		if (!requiredLocalInputAdded.add(Pair.of(parentHopId, childHopId)))
			return;
		long parentC = FederatedPlanMinSTPlanner.computeId(parentHopId);
		long childL = FederatedPlanMinSTPlanner.localityId(childHopId);
		// If the child has NO_LOCAL (lId on source side), the parent cannot execute CP.
		addCap(childL, parentC, HARD_CONSTRAINT);
	}

	public void markParentChildUploadHint(long parentHopId, long childHopId) {
		if (parentHopId <= 0 || childHopId <= 0)
			return;
		parentChildUploadHintAdded.add(Pair.of(parentHopId, childHopId));
	}

	public boolean hasParentChildUploadHint(long parentHopId, long childHopId) {
		if (parentHopId <= 0 || childHopId <= 0)
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

			for (Vertex vertex : memoTable.values()) {
				long hopID = vertex.getHopID();
				long cId = FederatedPlanMinSTPlanner.computeId(hopID);
				long pId = FederatedPlanMinSTPlanner.placementId(hopID);

				ExecType exec = sourceSide.contains(cId) ? ExecType.FED : ExecType.CP;
				FederatedOutput out = sourceSide.contains(pId) ? FederatedOutput.FOUT : FederatedOutput.LOUT;

				vertex.getHopRef().setForcedExecType(exec);
				vertex.getHopRef().setFederatedOutput(out);
				boolean derivedSelected = vertex.isDerivedFedFout()
						&& exec == ExecType.FED && out == FederatedOutput.FOUT;
				vertex.getHopRef().setFederatedOutputDerived(derivedSelected);
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

		public double getOpWeight() {
			return opWeight;
		}

		public double getNetworkWeight() {
			return networkWeight;
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
