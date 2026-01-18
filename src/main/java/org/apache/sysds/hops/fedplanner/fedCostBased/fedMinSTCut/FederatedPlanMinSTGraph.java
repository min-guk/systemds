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
	}

	public void forbidLOUTUnary(long pId) {
		addCap(leafedSource, pId, HARD_INF);
		addCap(pId, rootLocalSink, 0.0);
	}

	public void forbidFOUTUnary(long pId) {
		addCap(leafedSource, pId, 0.0);
		addCap(pId, rootLocalSink, HARD_INF);
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
		double fedCost = cpCost / Math.max(1, numOfWorkers);

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
		if (hop instanceof DataOp &&
				((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD)
			return; // Align with DP: TR uses only parent-child forwarding cost
		long hopId = vertex.getHopID();
		long cId = FederatedPlanMinSTPlanner.computeId(hopId);
		long pId = FederatedPlanMinSTPlanner.placementId(hopId);
		double uploadCost = vertex.getNetworkWeight() * vertex.getUploadCostWithoutWeight();
		double downloadCost = vertex.getNetworkWeight() * vertex.getDownloadCostWithoutWeight();
		addCap(cId, pId, downloadCost);
		addCap(pId, cId, uploadCost);
	}

	public void addParentChildNetEdge(Vertex childVertex, long childHopID,
			Vertex parentVertex, long parentHopID) {
		long parentC = FederatedPlanMinSTPlanner.computeId(parentHopID);
		long childP = FederatedPlanMinSTPlanner.placementId(childHopID);

		double forwardingWeight = parentVertex.computeForwardingWeightOfChild(childVertex.getLoopContext());
		double uploadCost = childVertex.getUploadCostWithoutWeight();
		double downloadCost = childVertex.getDownloadCostWithoutWeight();
		double uploadWeighted = forwardingWeight * uploadCost;
		double downloadWeighted = forwardingWeight * downloadCost;
		// Use child FType as a proxy conversion key since per-input conversion detail is not available here.
		FType conversionType = childVertex.getDataType();

		addParentChildHyperEdge(parentC, childP, HyperEdgeDirection.UPLOAD, conversionType, uploadWeighted);
		addParentChildHyperEdge(parentC, childP, HyperEdgeDirection.DOWNLOAD, conversionType, downloadWeighted);
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
		// TR/TW hard-constraint helper for enforcing exec/placement consistency.
		if (!trConsistencyAdded.add(Pair.of(twId, trId)))
			return;
		long twC = FederatedPlanMinSTPlanner.computeId(twId), trC = FederatedPlanMinSTPlanner.computeId(trId);
		long twP = FederatedPlanMinSTPlanner.placementId(twId), trP = FederatedPlanMinSTPlanner.placementId(trId);
		addXorEdge(twC, trC, HARD_CONSTRAINT);
		addXorEdge(twP, trP, HARD_CONSTRAINT);
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
		}
	}

	public static class ExecPlacementCaps {
		public boolean allowCP_LOUT = true;
		public boolean allowCP_FOUT = true;
		public boolean allowFED_LOUT = true;
		public boolean allowFED_FOUT = true;

		public ExecPlacementCaps() {
		}

		public ExecPlacementCaps(ExecPlacementCaps other) {
			this.allowCP_LOUT = other.allowCP_LOUT;
			this.allowCP_FOUT = other.allowCP_FOUT;
			this.allowFED_LOUT = other.allowFED_LOUT;
			this.allowFED_FOUT = other.allowFED_FOUT;
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
		public final ExecPlacementCaps caps_;

		public final boolean isFedExecutable_;
		public final boolean isLocalExecutable_;

		private double opCostWithWeight_;
		private double uploadCostWithoutWeight_;
		private double downloadCostWithoutWeight_;

		private double opWeight; // Weight used to calculate cost based on hop execution frequency
		private double networkWeight; // Weight used to calculate cost based on hop execution frequency
		private List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists
		// Currently unused; retained for potential TR/TW hard-constraint handling.
		private Long transientWriteHopId;
		private int numParents = 1;

		public Vertex(Hop hop, Privacy privacy, FType dataType, ExecPlacementCaps caps) {
			this.hop_ = hop;
			this.hopId_ = hop.getHopID();
			this.privacy_ = privacy;
			this.dataType_ = dataType;
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

		public ExecPlacementCaps getCaps() {
			return caps_;
		}

		public double getOpCostWithWeight() {
			return opCostWithWeight_;
		}

		public double getUploadCostWithoutWeight() {
			return uploadCostWithoutWeight_;
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
			this.downloadCostWithoutWeight_ = downloadCostWithoutWeight;
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
