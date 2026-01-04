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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTPlanner.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTPlanner.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedTypePropagator;
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

public final class FederatedPlanMinSTPlanner {
	private static int countDistinctWorkers(List<Pair<FederatedRange, FederatedData>> fedMap) {
		Set<InetSocketAddress> workerAddrs = new HashSet<>();
		for (Pair<FederatedRange, FederatedData> p : fedMap) {
			FederatedData data = p.getRight();
			if (data != null && data.getAddress() != null)
				workerAddrs.add(data.getAddress());
		}
		return workerAddrs.size();
	}

	private static long computeId(long hopId) {
		return hopId << 1;
	}

	private static long placementId(long hopId) {
		return (hopId << 1) | 1;
	}

	public static class FederatedPlanMinSTCostEstimator {
		// Default value is used as a reasonable estimate since we only need
		// to compare relative costs between different federated plans
		// Memory bandwidth for local computations (25 GB/s)
		private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
		// Network bandwidth for data transfers between federated sites (1 Gbps)
		private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
		private static final double DEFAULT_MBS_NETWORK_LATENCY = 0.001;

		public static void estimateProgram(DMLProgram prog, FederatedPlanMinSTGraph graph,
				Map<Long, List<Hop>> rewireTable, boolean isPrint) {
			Set<String> fnStack = new HashSet<>();
			Set<Long> visitedHops = new HashSet<>();

			for (StatementBlock sb : prog.getStatementBlocks()) {
				estimateStatementBlock(sb, prog, graph, rewireTable, fnStack, visitedHops);
			}
		}

		public static void estimateFunctionDynamic(FunctionStatementBlock function, FederatedPlanMinSTGraph graph,
				boolean isPrint) {
			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);

			DMLProgram prog = function.getDMLProg();
			FederatedPlanMinSTRewire.rewireFunctionDynamic(function, prog, rewireTable, graph,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, oracleFacade);

			Set<String> fnStack = new HashSet<>();
			Set<Long> visitedHops = new HashSet<>();
			int numOfWorkers = countDistinctWorkers(fedMap);
			graph.setNumOfWorkers(numOfWorkers);
			estimateStatementBlock(function, prog, graph, rewireTable, fnStack, visitedHops);
		}

		public static void estimateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlanMinSTGraph graph,
				Map<Long, List<Hop>> rewireTable, Set<String> fnStack, Set<Long> visitedHops) {

			if (sb instanceof IfStatementBlock) {
				IfStatementBlock isb = (IfStatementBlock) sb;
				IfStatement istmt = (IfStatement) isb.getStatement(0);

				estimateHopDAG(isb.getPredicateHops(), prog, graph, rewireTable, fnStack, visitedHops);

				for (StatementBlock innerIsb : istmt.getIfBody())
					estimateStatementBlock(innerIsb, prog, graph, rewireTable, fnStack, visitedHops);

				for (StatementBlock innerIsb : istmt.getElseBody())
					estimateStatementBlock(innerIsb, prog, graph, rewireTable, fnStack, visitedHops);
			} else if (sb instanceof ForStatementBlock) { // incl parfor
				ForStatementBlock fsb = (ForStatementBlock) sb;
				ForStatement fstmt = (ForStatement) fsb.getStatement(0);

				estimateHopDAG(fsb.getFromHops(), prog, graph, rewireTable, fnStack, visitedHops);
				estimateHopDAG(fsb.getToHops(), prog, graph, rewireTable, fnStack, visitedHops);
				if (fsb.getIncrementHops() != null) {
					estimateHopDAG(fsb.getIncrementHops(), prog, graph, rewireTable, fnStack, visitedHops);
				}

				for (StatementBlock innerFsb : fstmt.getBody())
					estimateStatementBlock(innerFsb, prog, graph, rewireTable, fnStack, visitedHops);
			} else if (sb instanceof WhileStatementBlock) {
				WhileStatementBlock wsb = (WhileStatementBlock) sb;
				WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

				estimateHopDAG(wsb.getPredicateHops(), prog, graph, rewireTable, fnStack, visitedHops);

				for (StatementBlock innerWsb : wstmt.getBody())
					estimateStatementBlock(innerWsb, prog, graph, rewireTable, fnStack, visitedHops);
			} else if (sb instanceof FunctionStatementBlock) {
				FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
				FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

				for (StatementBlock innerFsb : fstmt.getBody())
					estimateStatementBlock(innerFsb, prog, graph, rewireTable, fnStack, visitedHops);
			} else { // generic (last-level)
				if (sb.getHops() != null) {
					for (Hop c : sb.getHops())
						estimateHopDAG(c, prog, graph, rewireTable, fnStack, visitedHops);
				}
			}
		}

		private static void estimateHopDAG(Hop hop, DMLProgram prog, FederatedPlanMinSTGraph graph,
				Map<Long, List<Hop>> rewireTable, Set<String> fnStack, Set<Long> visitedHops) {

			if (!visitedHops.add(hop.getHopID())) {
				return;
			}

			List<Hop> childHops = new ArrayList<>(hop.getInput());

			if ((hop instanceof DataOp) && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
				List<Hop> transChildHops = rewireTable.get(hop.getHopID());
				if (transChildHops != null) {
					childHops.addAll(transChildHops);
				}
			}

			for (Hop inputHop : childHops) {
				estimateHopDAG(inputHop, prog, graph, rewireTable, fnStack, visitedHops);
			}

			if (hop instanceof FunctionOp) {
				FunctionOp fop = (FunctionOp) hop;
				if (fop.getFunctionType() == FunctionType.DML) {
					String fkey = fop.getFunctionKey();

					if (!fnStack.contains(fkey)) {
						fnStack.add(fkey);
						if (prog == null) {
							FederatedPlannerLogger.logWarnMessage(
									"[FederatedMinST] Skipping nested function " + fkey
											+ " because DMLProgram is unavailable in dynamic planning");
						} else {
							FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(),
									fop.getFunctionName());
							if (fsb == null) {
								FederatedPlannerLogger.logWarnMessage(
										"[FederatedMinST] Function " + fkey
												+ " not found in DMLProgram; skipping nested planning");
							} else {
								estimateStatementBlock(fsb, prog, graph, rewireTable, fnStack, visitedHops);
							}
						}
					}
				}
			}

			estimateHop(hop, graph, rewireTable);
		}

		private static void estimateHop(Hop hop, FederatedPlanMinSTGraph graph,
				Map<Long, List<Hop>> rewireTable) {
			long hopID = hop.getHopID();
			Vertex vertex = graph.getVertex(hopID);

			if (vertex == null) {
				return;
			}

			computeVertexCost(vertex, graph.getNumOfWorkers());

			ExecPlacementCaps caps = vertex.getCaps();
			boolean acL = caps.allowCP_LOUT;
			boolean acF = caps.allowCP_FOUT;
			boolean afL = caps.allowFED_LOUT;
			boolean afF = caps.allowFED_FOUT;
			long cId = computeId(hopID);
			long pId = placementId(hopID);

			if (!acL && !afL) {
				graph.forbidLOUTUnary(pId);
			}
			if (!acF && !afF) {
				graph.forbidFOUTUnary(pId);
			}

			graph.setVertexCost(vertex);

			if (!acF && afF) {
				graph.forbidCombinationCP_FOUT(cId, pId);
			}
			if (!afL && acL) {
				graph.forbidCombinationFED_LOUT(cId, pId);
			}

			graph.addExecPlacementResultEdge(vertex);

			List<Hop> childHops = new ArrayList<>(hop.getInput());
			for (Hop childHop : childHops) {
				Vertex childVertex = graph.getVertex(childHop.getHopID());

				if (childVertex == null) {
					continue;
				}

				if (childHop instanceof DataOp
						&& ((DataOp) childHop).getOp() == Types.OpOpData.TRANSIENTREAD) {
					Long twId = childVertex.getTransientWriteHopId();
					if (twId != null) {
						Vertex twVertex = graph.getVertex(twId);
						if (twVertex == null) {
							throw new DMLRuntimeException("Missing TWrite vertex for TRead hop " + childHop.getHopID());
						}
						graph.addTransReadWriteConsistencyEdges(twVertex, twId, childVertex, childHop.getHopID());
					}
					graph.addParentChildNetEdge(childVertex, childHop.getHopID(), vertex, hopID);
				} else {
					graph.addParentChildNetEdge(childVertex, childHop.getHopID(), vertex, hopID);
				}
			}
		}

		public static void computeVertexCost(Vertex vertex, int numOfWorkers) {
			Hop hop = vertex.getHopRef();
			double opCostWithWeight = 0, netCostWithoutWeight = 0;

			if (hop instanceof DataOp) {
				if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
					opCostWithWeight = netCostWithoutWeight = 0;
				} else if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
					opCostWithWeight = 0;
					netCostWithoutWeight = computeRefedNetworkCost(
							hop.getOutputMemEstimate(), vertex.getDataType(), numOfWorkers);
				} else {
					double opCost = computeOpCost(hop);
					opCostWithWeight = vertex.getOpWeight() * opCost;
					netCostWithoutWeight = computeRefedNetworkCost(
							hop.getOutputMemEstimate(), vertex.getDataType(), numOfWorkers);
				}
				vertex.setCost(opCostWithWeight, netCostWithoutWeight);
				return;
			}

			double opCost = computeOpCost(hop);
			opCostWithWeight = vertex.getOpWeight() * opCost;
			netCostWithoutWeight = computeRefedNetworkCost(
					hop.getOutputMemEstimate(), vertex.getDataType(), numOfWorkers);

			vertex.setCost(opCostWithWeight, netCostWithoutWeight);
		}

		private static double computeOpCost(Hop currentHop) {
			double computeCost = ComputeCost.getHOPComputeCost(currentHop);
			double inputAccessCost = computeHopMemoryAccessCost(currentHop.getInputMemEstimate());
			double ouputAccessCost = computeHopMemoryAccessCost(currentHop.getOutputMemEstimate());

			return Math.max(computeCost, inputAccessCost) + ouputAccessCost;
		}

		private static double computeHopMemoryAccessCost(double memSize) {
			if (memSize <= 0)
				return 0.0;
			return memSize / (1024 * 1024) / DEFAULT_MBS_MEMORY_BANDWIDTH;
		}

		private static double computeHopForwardingCost(double memSize) {
			return DEFAULT_MBS_NETWORK_LATENCY + (memSize / (1024 * 1024) / DEFAULT_MBS_NETWORK_BANDWIDTH);
		}

		private static double computeRefedNetworkCost(double memSize, FType fType, int numOfWorkers) {
			if (memSize <= 0)
				return 0.0;
			if (fType == FType.FULL || fType == FType.BROADCAST)
				return computeHopForwardingCost(memSize * Math.max(1, numOfWorkers));
			if (fType == FType.ROW || fType == FType.COL)
				return computeHopForwardingCost(memSize);
			return computeHopForwardingCost(memSize);
		}
	}

	public static class FederatedPlanMinSTCut extends AFederatedPlanner {
		@Override
		public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);

			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

			FederatedPlanMinSTRewire.rewireProgram(prog, rewireTable, graph, fedMap,
					unRefTwriteSet, unRefSet, progRootHopSet, oracleFacade);
			for (long hopID : unRefTwriteSet) {
				progRootHopSet.add(graph.getHopRef(hopID));
			}

			int numOfWorkers = countDistinctWorkers(fedMap);
			graph.setNumOfWorkers(numOfWorkers);
			FederatedPlanMinSTCostEstimator.estimateProgram(prog, graph, rewireTable, true);

			graph.getOptimalPlan();
			Map<Long, FType> fTypeMap = new HashMap<>();
			for (Vertex vertex : graph.getMemoTable().values()) {
				if (vertex.getDataType() == null)
					continue;
				Hop hop = vertex.getHopRef();
				if (hop != null && hop.getFederatedOutput() == FederatedOutput.FOUT)
					fTypeMap.put(vertex.getHopID(), vertex.getDataType());
			}
			FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
			FederatedPlannerLogger.logOptimalPlan(graph, true);
		}

		@Override
		public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
			FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
			FederatedPlanMinSTCostEstimator.estimateFunctionDynamic(function, graph, true);
			graph.getOptimalPlan();
			Map<Long, FType> fTypeMap = new HashMap<>();
			for (Vertex vertex : graph.getMemoTable().values()) {
				if (vertex.getDataType() == null)
					continue;
				Hop hop = vertex.getHopRef();
				if (hop != null && hop.getFederatedOutput() == FederatedOutput.FOUT)
					fTypeMap.put(vertex.getHopID(), vertex.getDataType());
			}
			FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
			FederatedPlannerLogger.logOptimalPlan(graph, true);
		}
	}

	public static class FederatedPlanMinSTGraph {
		// HARD_INF: prohibitively large cost used to block a placement/exec option
		// entirely
		private static final double HARD_INF = 1e12;
		// HARD_CONSTRAINT: represents impossible states (consistency violations or
		// illegal combos)
		private static final double HARD_CONSTRAINT = 1e15;
		private static final long leafedSource = -1L;
		private static final long rootLocalSink = -2L;

		private final Map<Long, Vertex> memoTable = new HashMap<>();
		private final Graph<Long, DefaultWeightedEdge> graph = new DefaultDirectedWeightedGraph<>(
				DefaultWeightedEdge.class);
		private final Set<Long> trConsistencyAdded = new HashSet<>();
		private int numOfWorkers = 0;

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
			graph.addVertex(computeId(hopID));
			graph.addVertex(placementId(hopID));
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
			long cId = computeId(hopID);
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
			if (hopIsPrintOrPWrite(hop))
				return;
			if (hop instanceof DataOp &&
					((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD)
				return; // Align with DP: TR uses only parent-child forwarding cost
			long hopId = vertex.getHopID();
			addXorEdge(computeId(hopId), placementId(hopId), vertex.getNetCostWithoutWeight());
		}

		public void addParentChildNetEdge(Vertex childVertex, long childHopID,
				Vertex parentVertex, long parentHopID) {
			long parentC = computeId(parentHopID);
			long childP = placementId(childHopID);

			double forwardingWeight = parentVertex.computeForwardingWeightOfChild(childVertex.getLoopContext());
			double netCost = forwardingWeight * childVertex.getNetCostWithoutWeight();
			FType dt = childVertex.getDataType();

			if (dt == FType.ROW || dt == FType.COL) {
				netCost /= Math.max(1, numOfWorkers);
			}

			addXorEdge(parentC, childP, netCost);
		}

		public void addTransReadWriteConsistencyEdges(Vertex tw, long twId, Vertex tr, long trId) {
			if (!trConsistencyAdded.add(trId))
				return;
			long twC = computeId(twId), trC = computeId(trId);
			long twP = placementId(twId), trP = placementId(trId);
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
				long cId = computeId(hopID);
				long pId = placementId(hopID);

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

		public static class Vertex {
			public final Hop hop_;
			public final long hopId_;

			public final Privacy privacy_;
			public final FType dataType_;
			public final ExecPlacementCaps caps_;

			public final boolean isFedExecutable_;
			public final boolean isLocalExecutable_;

			private double opCostWithWeight_;
			private double netCostWithoutWeight_;

			private double opWeight; // Weight used to calculate cost based on hop execution frequency
			private double networkWeight; // Weight used to calculate cost based on hop execution frequency
			private List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists
			private Long transientWriteHopId;

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

			public double getNetCostWithoutWeight() {
				return netCostWithoutWeight_;
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

			public void setMetadata(double opWeight, double networkWeight, List<Pair<Long, Double>> loopContext) {
				this.opWeight = opWeight;
				this.networkWeight = networkWeight;
				this.loopContext = loopContext;
			}

			public void setCost(double opCostWithWeight, double netCostWithoutWeight) {
				this.opCostWithWeight_ = opCostWithWeight;
				this.netCostWithoutWeight_ = netCostWithoutWeight;
			}

			public void setTransientWriteHopId(Long transientWriteHopId) {
				this.transientWriteHopId = transientWriteHopId;
			}

			public Long getTransientWriteHopId() {
				return transientWriteHopId;
			}

			/**
			 * Estimates how often this parent's output is forwarded to the given child hop.
			 * We start from the parent's networkWeight and amortize by loop iterations that
			 * exist only in the child's loopContext beyond the longest common prefix,
			 * assuming the forwarded result is reused across those inner iterations.
			 *
			 * Example:
			 * parent loopContext = [(for1, 100), (while2, 10)]
			 * childLoopContext = [(for1, 100), (while2, 10), (for3, 5)]
			 * => forwardingWeight = networkWeight / 5 (reused across the inner for3 loop)
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

	private static boolean hopIsPrintOrPWrite(Hop hop) {
		if (hop instanceof UnaryOp && ((UnaryOp) hop).getOp() == OpOp1.PRINT)
			return true;
		return hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.PERSISTENTWRITE;
	}

	private static List<FType> alignInputFTypes(Hop hop, List<Hop> collectedHops, List<FType> collectedFTypes) {
		if (hop == null) {
			return collectedFTypes;
		}
		int numInputs = hop.getInput() == null ? 0 : hop.getInput().size();
		List<FType> aligned = new ArrayList<>(Collections.nCopies(numInputs, null));
		if (numInputs == 0) {
			return aligned;
		}
		for (int i = 0; i < collectedHops.size(); i++) {
			Hop child = collectedHops.get(i);
			FType ftype = collectedFTypes.get(i);
			if (child == null) {
				FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping unmatched child null for hop "
						+ hop.getHopID());
				continue;
			}
			int pos = hop.getInput().indexOf(child);
			if (pos >= 0 && pos < numInputs) {
				aligned.set(pos, ftype);
			} else {
				FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping unmatched child "
						+ child.getHopID() + " for hop " + hop.getHopID());
			}
		}
		return aligned;
	}

	private static FederatedPlanMinSTGraph.ExecPlacementCaps buildExecPlacementCaps(
			Hop hop, Privacy privacy, FType fType, OpCaps capsOracle) {

		FederatedPlanMinSTGraph.ExecPlacementCaps caps = new FederatedPlanMinSTGraph.ExecPlacementCaps();

		// 0) 처음엔 전부 false로 시작 (DP가 실제로 생성하는 조합만 켜기 위함)
		caps.allowCP_LOUT = false;
		caps.allowCP_FOUT = false;
		caps.allowFED_LOUT = false;
		caps.allowFED_FOUT = false;

		// 0. FEDERATED DataOp는 DP와 동일하게 FED/FOUT만 허용
		if (hop instanceof DataOp &&
				((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
			caps.allowFED_FOUT = true;

			if (!caps.hasAny()) {
				throw new DMLRuntimeException("No legal Exec/Placement combination for FEDERATED hop "
						+ hop.getHopID() + " (" + hop.getOpString() + ")");
			}

			return caps;
		}

		// 1) Oracle 힌트 해석 (DP enumerateHop와 동일한 제약)
		ExecType oracleExec;
		FederatedOutput placement;

		if (capsOracle != null) {
			oracleExec = capsOracle.exec();
			placement = capsOracle.placement();
		} else {
			// Oracle 정보가 없으면 보수적으로 CP/LOUT로 가정
			oracleExec = ExecType.CP;
			placement = FederatedOutput.LOUT;
		}

		// 2) Oracle × Privacy 매트릭스를 DP enumerateHop에서 그대로 옮김
		switch (privacy) {
			case PUBLIC:
				// CP/LOUT: 항상 허용 (DP에서 무조건 플랜 생성)
				caps.allowCP_LOUT = true;
				caps.allowCP_FOUT = true;

				if (oracleExec == ExecType.FED) {
					// FED/FOUT: placement == FOUT 일 때만
					// Todo:
					if (placement == FederatedOutput.FOUT) {
						caps.allowFED_FOUT = true;
					}
					// FED/LOUT: placement와 무관하게 허용 (FED 실행 후 gather)
					caps.allowFED_LOUT = true;
				}
				break;

			case PRIVATE:
			case PRIVATE_AGGREGATE:
				// FED/FOUT only (oracleExec == FED && placement == FOUT)
				if (oracleExec == ExecType.FED && placement == FederatedOutput.FOUT) {
					caps.allowFED_FOUT = true;
				}
				break;

			case PRIVATE_AGGREGATE_TO_PUBLIC:
				if (oracleExec == ExecType.FED) {
					// FED/FOUT: placement == FOUT 일 때만
					if (placement == FederatedOutput.FOUT) {
						caps.allowFED_FOUT = true;
					}
					// aggregation 이후 FED/LOUT 허용
					caps.allowFED_LOUT = true;
				}
				break;

			default:
				throw new DMLRuntimeException("Unsupported privacy level " + privacy
						+ " for hop " + hop.getHopID() + " (" + hop.getOpString() + ")");
		}

		// // 4) FType 정보가 없으면 FED는 사용 불가 (MinST 고유 제약)
		// if (fType == null) {
		// caps.allowFED_LOUT = false;
		// caps.allowFED_FOUT = false;
		// }

		// 5) scalar / PRINT / PWRITE override
		// - PUBLIC 에서만 CP/LOUT로 강제
		// - PRIVATE 계열은 DP 매트릭스 + privacy 필터 결과를 그대로 사용 (override X)
		if (hopIsPrintOrPWrite(hop)) {
			caps.allowCP_LOUT = true;
			caps.allowCP_FOUT = false;
			caps.allowFED_LOUT = false;
			caps.allowFED_FOUT = false;
		}

		if (!caps.hasAny()) {
			throw new DMLRuntimeException("No legal Exec/Placement combination for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		}

		return caps;
	}

	public static class FederatedPlanMinSTRewire {

		private static final double DEFAULT_LOOP_WEIGHT = 10.0;
		private static final double DEFAULT_IF_ELSE_WEIGHT = 0.5;

		public static final String FED_MATRIX_IDENTIFIER = "matrix";
		public static final String FED_FRAME_IDENTIFIER = "frame";

		public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
				FederatedPlanMinSTGraph graph, List<Pair<FederatedRange, FederatedData>> fedMap,
				Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet,
				OracleFacade oracleFacade) {
			// Maps HopID -> Privacy constraint
			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			Map<Long, FType> fTypeMap = new HashMap<>();
			Set<Long> visitedHops = new HashSet<>();
			Set<String> fnStack = new HashSet<>();
			List<Pair<Long, Double>> loopStack = new ArrayList<>();

			List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
			Map<String, List<Hop>> outerTransTable = new HashMap<>();
			outerTransTableList.add(outerTransTable);

			for (StatementBlock sb : prog.getStatementBlocks()) {
				Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
						graph, outerTransTableList, null, privacyConstraintMap, fTypeMap,
						fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack, oracleFacade);
				outerTransTableList.get(0).putAll(innerTransTable);
			}
		}

		public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
				Map<Long, List<Hop>> rewireTable,
				FederatedPlanMinSTGraph graph, List<Pair<FederatedRange, FederatedData>> fedMap,
				Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet, OracleFacade oracleFacade) {
			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			Map<Long, FType> fTypeMap = new HashMap<>();
			Set<Long> visitedHops = new HashSet<>();
			Set<String> fnStack = new HashSet<>();
			List<Pair<Long, Double>> loopStack = new ArrayList<>();
			List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
			Map<String, List<Hop>> outerTransTable = new HashMap<>();
			outerTransTableList.add(outerTransTable);
			// Todo (Future): not tested & not used
			rewireStatementBlock(function, prog, visitedHops, rewireTable, graph, outerTransTableList, null,
					privacyConstraintMap, fTypeMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack, oracleFacade);
		}

		public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog,
				Set<Long> visitedHops,
				Map<Long, List<Hop>> rewireTable, FederatedPlanMinSTGraph graph,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet, Set<String> fnStack,
				double computeWeight, double networkWeight, List<Pair<Long, Double>> parentLoopStack,
				OracleFacade oracleFacade) {
			List<Map<String, List<Hop>>> newOuterTransTableList = new ArrayList<>();
			if (outerTransTableList != null) {
				for (Map<String, List<Hop>> outerTable : outerTransTableList) {
					if (outerTable != null && !outerTable.isEmpty()) {
						newOuterTransTableList.add(outerTable);
					}
				}
			}
			if (formerTransTable != null && !formerTransTable.isEmpty()) {
				newOuterTransTableList.add(formerTransTable);
			}

			Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
			Map<String, List<Hop>> innerTransTable = new HashMap<>();

			if (sb instanceof IfStatementBlock) {
				IfStatementBlock isb = (IfStatementBlock) sb;
				IfStatement istmt = (IfStatement) isb.getStatement(0);

				rewireHopDAG(isb.getPredicateHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						computeWeight,
						networkWeight, parentLoopStack, oracleFacade);

				newFormerTransTable.putAll(innerTransTable);
				Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
				elseFormerTransTable.putAll(innerTransTable);
				computeWeight *= DEFAULT_IF_ELSE_WEIGHT;
				// Todo: network weight을 0.5로 안하는 이유가 있나? 잘 모르겠음. 고민해봐야함.
				// networkWeight *= DEFAULT_IF_ELSE_WEIGHT;

				for (StatementBlock innerIsb : istmt.getIfBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, parentLoopStack, oracleFacade));

				for (StatementBlock innerIsb : istmt.getElseBody())
					elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, elseFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, parentLoopStack, oracleFacade));

				// If there are common keys: merge elseValue list into ifValue list
				elseFormerTransTable.forEach((key, elseValue) -> {
					newFormerTransTable.merge(key, elseValue, (ifValue, newValue) -> {
						ifValue.addAll(newValue);
						return ifValue;
					});
				});
			} else if (sb instanceof ForStatementBlock) { // incl parfor
				ForStatementBlock fsb = (ForStatementBlock) sb;
				ForStatement fstmt = (ForStatement) fsb.getStatement(0);

				// Calculate for-loop iteration count if possible
				double loopWeight = DEFAULT_LOOP_WEIGHT;
				Hop from = fsb.getFromHops().getInput().get(0);
				Hop to = fsb.getToHops().getInput().get(0);
				Hop incr = (fsb.getIncrementHops() != null) ? fsb.getIncrementHops().getInput().get(0)
						: new LiteralOp(1);

				// Calculate for-loop iteration count (weight) if from, to, and incr are literal
				// ops (constant values)
				if (from instanceof LiteralOp && to instanceof LiteralOp && incr instanceof LiteralOp) {
					double dfrom = HopRewriteUtils.getDoubleValue((LiteralOp) from);
					double dto = HopRewriteUtils.getDoubleValue((LiteralOp) to);
					double dincr = HopRewriteUtils.getDoubleValue((LiteralOp) incr);
					if (dfrom > dto && dincr == 1)
						dincr = -1;
					loopWeight = UtilFunctions.getSeqLength(dfrom, dto, dincr, false);
				}
				computeWeight *= loopWeight;
				networkWeight *= loopWeight;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(fsb.getFromHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						computeWeight,
						networkWeight, currentLoopStack, oracleFacade);
				rewireHopDAG(fsb.getToHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList, null,
						innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						computeWeight,
						networkWeight, currentLoopStack, oracleFacade);

				if (fsb.getIncrementHops() != null) {
					rewireHopDAG(fsb.getIncrementHops(), prog, visitedHops, rewireTable, graph,
							newOuterTransTableList, null, innerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, currentLoopStack, oracleFacade);
				}
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, currentLoopStack, oracleFacade));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOut(fsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
			} else if (sb instanceof WhileStatementBlock) {
				WhileStatementBlock wsb = (WhileStatementBlock) sb;
				WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

				computeWeight *= DEFAULT_LOOP_WEIGHT;
				networkWeight *= DEFAULT_LOOP_WEIGHT;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), DEFAULT_LOOP_WEIGHT));

				rewireHopDAG(wsb.getPredicateHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						computeWeight,
						networkWeight, currentLoopStack, oracleFacade);
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerWsb : wstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, currentLoopStack, oracleFacade));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOut(wsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
			} else if (sb instanceof FunctionStatementBlock) {
				FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
				FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

				for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, parentLoopStack, oracleFacade));

				// Wire fcall operation to liveOutHops
				wireUnRefTwriteToLiveOut(fsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
			} else { // generic (last-level)
				if (sb.getHops() != null) {
					for (Hop c : sb.getHops())
						rewireHopDAG(c, prog, visitedHops, rewireTable, graph, newOuterTransTableList, null,
								innerTransTable,
								privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet,
								fnStack,
								computeWeight, networkWeight, parentLoopStack, oracleFacade);
				}

				return innerTransTable;
			}
			return newFormerTransTable;
		}

		private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops,
				Map<Long, List<Hop>> rewireTable,
				FederatedPlanMinSTGraph graph, List<Map<String, List<Hop>>> outerTransTableList,
				Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
				Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet,
				Set<String> fnStack, double computeWeight, double networkWeight, List<Pair<Long, Double>> loopStack,
				OracleFacade oracleFacade) {

			if (!visitedHops.add(hop.getHopID())) {
				return;
			}

			// Collect all child hops including rewired TRead children
			List<Hop> childHops = new ArrayList<>();
			if (hop.getInput() != null) {
				childHops.addAll(hop.getInput());
			}

			// TODO: VERIFY - Fix for missing hops in memoTable (e.g., Hop 282)
			// For TRead: populate rewireTable BEFORE visiting children
			// This is necessary because rewireTable is normally populated in rewireHop(),
			// which is called AFTER child visiting, but we need it DURING child visiting
			if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
				String hopName = hop.getName();
				List<Hop> transChildHops = rewireTransRead(hopName, innerTransTable, formerTransTable,
						outerTransTableList);
				if (transChildHops != null && !transChildHops.isEmpty()) {
					rewireTable.put(hop.getHopID(), transChildHops);
					childHops.addAll(transChildHops);
				}
			}

			// Process all child hops
			for (Hop inputHop : childHops) {
				rewireHopDAG(inputHop, prog, visitedHops, rewireTable, graph, outerTransTableList,
						formerTransTable, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						computeWeight, networkWeight, loopStack, oracleFacade);
			}

			// Identify hops to connect to the root dummy node
			// Connect TWrite pred and u(print) to the root dummy node
			if ((hop instanceof DataOp && (hop.getName().equals("__pred"))) // TWrite "__pred"
					|| (hop instanceof UnaryOp && ((UnaryOp) hop).getOp() == OpOp1.PRINT) // u(print)
					|| (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.PERSISTENTWRITE)) { // PWrite
				progRootHopSet.add(hop);
			} else if (!(hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
					&& hop.getParent().size() == 0) {
				unRefSet.add(hop.getHopID());
			}

			if (hop instanceof FunctionOp) {
				// maintain counters and investigate functions if not seen so far
				FunctionOp fop = (FunctionOp) hop;
				unRefTwriteSet.add(fop.getHopID());

				if (fop.getFunctionType() == FunctionType.DML) {
					String fkey = fop.getFunctionKey();

					if (!fnStack.contains(fkey)) {
						fnStack.add(fkey);
						if (prog == null) {
							FederatedPlannerLogger.logWarnMessage(
									"[FederatedMinSTRewire] Skipping nested function " + fkey
											+ " because DMLProgram is unavailable in dynamic rewiring");
						} else {
							FunctionStatementBlock fsb = prog.getFunctionStatementBlock(
									fop.getFunctionNamespace(), fop.getFunctionName());

							if (fsb == null) {
								FederatedPlannerLogger.logWarnMessage(
										"[FederatedMinSTRewire] Function " + fkey
												+ " not found in DMLProgram; skipping nested rewiring");
							} else {
								Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
								if (formerTransTable != null) {
									newFormerTransTable.putAll(formerTransTable);
								}
								newFormerTransTable.putAll(innerTransTable);

								String[] inputArgs = fop.getInputVariableNames();
								List<Hop> inputHops = fop.getInput();

								// Only used outside of functionTransTable.
								for (int i = 0; i < inputHops.size(); i++) {
									newFormerTransTable.computeIfAbsent(inputArgs[i], k -> new ArrayList<>())
											.add(inputHops.get(i));
								}

								Map<String, List<Hop>> functionTransTable = rewireStatementBlock(fsb, prog, visitedHops,
										rewireTable, graph, outerTransTableList, newFormerTransTable,
										privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet,
										progRootHopSet, fnStack,
										computeWeight, networkWeight, loopStack, oracleFacade);

								for (int i = 0; i < fop.getOutputVariableNames().length; i++) {
									String tWriteName = fop.getOutputVariableNames()[i];
									List<Hop> outputHops = functionTransTable
											.get(fsb.getOutputsofSB().get(i).getName());
									innerTransTable.computeIfAbsent(tWriteName, k -> new ArrayList<>())
											.addAll(outputHops);
									for (Hop outputHop : outputHops) {
										unRefTwriteSet.add(outputHop.getHopID());
									}
								}
							}
						}
					}
				}
			}

			Vertex vertex = rewireHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
					privacyConstraintMap,
					graph, fTypeMap, fedMap, unRefTwriteSet, oracleFacade);
			if (vertex != null) {
				vertex.setMetadata(computeWeight, networkWeight, loopStack);
				graph.addVertex(vertex);
			}
		}

		private static Vertex rewireHop(Hop hop, Map<Long, List<Hop>> rewireTable,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap,
				FederatedPlanMinSTGraph graph, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet,
				OracleFacade oracleFacade) {

			Privacy privacy;
			FType fType = null; // ★ 기본은 null, Oracle이 채우도록

			ExecPlacementCaps caps;

			if (!(hop instanceof DataOp)) {
				// 1) 비-DataOp: privacy만 전파, FType은 Oracle 결과로만 결정
				privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
				// fType은 여기서 추론하지 않는다 (getFederatedType 제거)
			} else {
				DataOp dataOp = (DataOp) hop;
				Types.OpOpData opType = dataOp.getOp();
				String hopName = dataOp.getName();

				if (opType == Types.OpOpData.FEDERATED) {
					// 2) FEDERATED DataOp: privacy + partition metadata 기반 FType
					privacy = FederatedPlannerUtils.getFedWorkerMetaData(fedMap, dataOp);
					fType = FederatedTypePropagator.deriveFType(dataOp);
					FederatedPlannerLogger.logDataOpFTypeDebug(
							hop, fType, "FEDERATED", "Derived from partition ranges");
				} else if (opType == Types.OpOpData.TRANSIENTWRITE) {
					// 3) TWrite: 입력 Hop의 FType을 그대로 복사
					innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
					unRefTwriteSet.add(hop.getHopID());
					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
					fType = fTypeMap.get(hop.getInput(0).getHopID());
					FederatedPlannerLogger.logDataOpFTypeDebug(
							hop, fType, "TRANSIENTWRITE",
							"Propagated from single input (HopID: " + hop.getInput(0).getHopID() + ")");
				} else if (opType == Types.OpOpData.TRANSIENTREAD) {
					// 4) TRead: TWrite로부터 privacy/FType/caps 복사 (Oracle 사용 X)
					List<Hop> childHops = rewireTable.get(hop.getHopID());
					if (childHops == null) {
						childHops = rewireTransRead(hopName, innerTransTable, formerTransTable, outerTransTableList);
						rewireTable.put(hop.getHopID(), childHops);
					}

					if (childHops == null || childHops.isEmpty()) {
						FederatedPlannerLogger.logTransReadRewireDebug(
								hopName, hop.getHopID(), childHops, true, "RewireTransHop");
						return null;
					}

					List<Hop> filteredChildHops = new ArrayList<>();
					for (Hop childHop : childHops) {
						if (hopName.equals(childHop.getName()))
							filteredChildHops.add(childHop);
					}

					FederatedPlannerLogger.logRewireHierarchy(
							hop, childHops, filteredChildHops, "RewireTransHop");

					if (filteredChildHops.isEmpty()) {
						FederatedPlannerLogger.logFilteredChildHopsDebug(
								hopName, hop.getHopID(), filteredChildHops, true, "RewireTransHop");
						return null;
					}

					Hop twHop = filteredChildHops.get(0);
					for (Hop filteredChildHop : filteredChildHops) {
						rewireTable.computeIfAbsent(filteredChildHop.getHopID(), k -> new ArrayList<>()).add(hop);
						unRefTwriteSet.remove(filteredChildHop.getHopID());
					}

					// TWrite에서 privacy/FType 가져오기
					fType = fTypeMap.get(twHop.getHopID());
					privacy = privacyConstraintMap.getOrDefault(
							twHop.getHopID(),
							FederatedPlannerUtils.getPrivacyConstraint(twHop, twHop.getInput(), privacyConstraintMap));

					Vertex twVertex = graph.getVertex(twHop.getHopID());
					caps = (twVertex != null && twVertex.getCaps() != null)
							? new ExecPlacementCaps(twVertex.getCaps())
							: buildExecPlacementCaps(hop, privacy, fType, null);

					privacyConstraintMap.put(hop.getHopID(), privacy);
					fTypeMap.put(hop.getHopID(), fType);

					Vertex v = new Vertex(hop, privacy, fType, caps);
					v.setTransientWriteHopId(twHop.getHopID());
					return v;
				} else {
					// 5) 기타 DataOp (PREAD, PWRITE 등): privacy만, FType은 Oracle에 맡김
					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
					// fType = null 유지 (getFederatedType 제거)
				}
			}

			// ==== 여기서부터는 모든 Hop(비 DataOp + DataOp 공통) 처리 ====

			// 자식 FType들에서 alignedFTypes 구성
			List<Hop> collectedHops = hop.getInput() == null ? Collections.emptyList() : hop.getInput();
			List<FType> collectedFTypes = new ArrayList<>();
			List<Hop> collectedHopList = new ArrayList<>();
			for (Hop input : collectedHops) {
				collectedHopList.add(input);
				collectedFTypes.add(fTypeMap.get(input.getHopID()));
			}
			List<FType> alignedFTypes = alignInputFTypes(hop, collectedHopList, collectedFTypes);

			// Oracle 호출: exec/placement + foutFType
			OpCaps opCaps = oracleFacade != null ? oracleFacade.decide(hop, alignedFTypes) : null;
			if (opCaps != null) {
				FederatedPlannerLogger.logOracleDecision(
						hop, privacy, alignedFTypes, opCaps, rewireTable);
			}

			// Oracle foutFType을 FType으로 반영 (getFederatedType 대체)
			if (opCaps != null && opCaps.foutFType().isPresent()) {
				FType oracleFType = opCaps.foutFType().get();
				// FEDERATED DataOp는 partition 기반 FType과 충돌할 수 있으니, 필요하면 로깅
				if (fType != null && oracleFType != null && !fType.equals(oracleFType)) {
					FederatedPlannerLogger.logInfoMessage(
							"[MinST] Oracle foutFType " + oracleFType + " overrides existing FType "
									+ fType + " for hop " + hop.getHopID() + " (" + hop.getOpString() + ")");
				}
				fType = oracleFType;
			}

			// Exec/Placement capability 결정
			caps = buildExecPlacementCaps(hop, privacy, fType, opCaps);
			if (!hop.getDataType().isMatrix() || fType == null || fType == FType.PART || fType == FType.OTHER) {
				caps.allowCP_FOUT = false;
			}

			// 최종 privacy/FType 저장
			privacyConstraintMap.put(hop.getHopID(), privacy);
			fTypeMap.put(hop.getHopID(), fType);

			return new Vertex(hop, privacy, fType, caps);
		}

		private static List<Hop> rewireTransRead(String hopName, Map<String, List<Hop>> innerTransTable,
				Map<String, List<Hop>> formerTransTable, List<Map<String, List<Hop>>> outerTransTableList) {
			List<Hop> childHops = new ArrayList<>();

			// Read according to priority: inner -> former -> outer
			if (!innerTransTable.isEmpty()) {
				childHops = innerTransTable.get(hopName);
			}

			if ((childHops == null || childHops.isEmpty()) && formerTransTable != null) {
				childHops = formerTransTable.get(hopName);
			}

			if (childHops == null || childHops.isEmpty()) {
				// Traverse in reverse order from the last inserted outerTransTable
				for (int i = outerTransTableList.size() - 1; i >= 0; i--) {
					Map<String, List<Hop>> outerTransTable = outerTransTableList.get(i);
					childHops = outerTransTable.get(hopName);
					if (childHops != null && !childHops.isEmpty())
						break;
				}
			}

			return childHops;
		}

		private static void wireUnRefTwriteToLiveOut(StatementBlock sb, Set<Long> unRefTwriteSet,
				FederatedPlanMinSTGraph graph,
				Map<String, List<Hop>> newFormerTransTable,
				Map<Long, FType> fTypeMap) {

			FederatedPlannerUtils.wireUnRefTwriteToLiveOutCommon(
					sb,
					unRefTwriteSet,
					// hopLookup: hopID -> Hop (Vertex가 없으면 null)
					id -> {
						FederatedPlanMinSTGraph.Vertex v = graph.getVertex(id);
						return (v != null) ? v.getHopRef() : null;
					},
					newFormerTransTable,
					// compatFn: unRefTwriteHop vs 대표 liveOutHop
					(unRefTwriteHop, liveOutHop) -> calculateCompatibilityScore(unRefTwriteHop, liveOutHop, graph),
					"[MinST]");
		}

		// NOTE: keep in sync with DP planner:
		// FederatedPlannerFedCostBased.FederatedPlanRewireTransTable.calculateCompatibilityScore
		private static FederatedPlannerUtils.CompatibilityScore calculateCompatibilityScore(
				Hop unRefTwriteHop, Hop liveOutHop,
				FederatedPlanMinSTGraph graph) {

			int nameScore = getMatchingPriority(unRefTwriteHop.getName(), liveOutHop.getName());
			boolean sameDataType = unRefTwriteHop.getDataType() == liveOutHop.getDataType()
					&& unRefTwriteHop.getValueType() == liveOutHop.getValueType();

			if (sameDataType) {
				return new FederatedPlannerUtils.CompatibilityScore(1, 0, nameScore);
			}

			double dimSimilarity = calculateDimensionSimilarity(unRefTwriteHop, liveOutHop);
			if (dimSimilarity > 0) {
				int dimScore = (int) Math.round((1 - dimSimilarity) * 100);
				return new FederatedPlannerUtils.CompatibilityScore(2, dimScore, nameScore);
			}

			double commonChildMemEstimate = findCommonChildrenMemEstimate(
					unRefTwriteHop, liveOutHop, graph);
			if (commonChildMemEstimate > 0) {
				int childScore = (int) Math.max(0, 10000 - Math.min(commonChildMemEstimate, 10000));
				return new FederatedPlannerUtils.CompatibilityScore(3, childScore, nameScore);
			}

			return new FederatedPlannerUtils.CompatibilityScore(4, 0, nameScore);
		}

		private static int getMatchingPriority(String unRefTwriteHopName, String liveOutHopName) {
			if (unRefTwriteHopName.equals(liveOutHopName)) {
				return 1; // 정확한 이름 매칭 (최우선)
			}

			if (unRefTwriteHopName.startsWith(liveOutHopName) ||
					liveOutHopName.startsWith(unRefTwriteHopName)) {
				return 2; // 접두사 매칭
			}

			if (unRefTwriteHopName.contains(liveOutHopName) ||
					liveOutHopName.contains(unRefTwriteHopName)) {
				return 3; // 부분 문자열 매칭
			}

			return 4; // 매칭 없음
		}

		// 차원 유사성 계산 (0.0 ~ 1.0, 1.0이 가장 유사)
		private static double calculateDimensionSimilarity(Hop hop1, Hop hop2) {
			long dim1_1 = hop1.getDim1();
			long dim1_2 = hop1.getDim2();
			long dim2_1 = hop2.getDim1();
			long dim2_2 = hop2.getDim2();

			// 완전히 같은 차원
			if (dim1_1 == dim2_1 && dim1_2 == dim2_2) {
				return 1.0;
			}

			// 한 차원이라도 -1이면 유사성 낮음
			if (dim1_1 == -1 || dim1_2 == -1 || dim2_1 == -1 || dim2_2 == -1) {
				return 0.1;
			}

			// 차원 비율 계산
			double ratio1 = (dim1_1 == 0 || dim2_1 == 0) ? 0
					: Math.min(dim1_1, dim2_1) / (double) Math.max(dim1_1, dim2_1);
			double ratio2 = (dim1_2 == 0 || dim2_2 == 0) ? 0
					: Math.min(dim1_2, dim2_2) / (double) Math.max(dim1_2, dim2_2);

			// 평균 유사성
			return (ratio1 + ratio2) / 2.0;
		}

		// 공통 child들의 메모리 추정치 계산 (재귀적 탐색, depth 5 제한)
		private static double findCommonChildrenMemEstimate(Hop hop1, Hop hop2, FederatedPlanMinSTGraph graph) {
			Set<Long> children1 = getAllChildren(hop1, new HashSet<>(), 5);
			Set<Long> children2 = getAllChildren(hop2, new HashSet<>(), 5);

			// 교집합 찾기
			Set<Long> commonChildren = new HashSet<>(children1);
			commonChildren.retainAll(children2);

			// 공통 child들의 총 메모리 추정치 계산 (HopRef 기반)
			double totalMemEstimate = 0.0;
			for (Long childId : commonChildren) {
				Vertex v = graph.getVertex(childId);
				if (v != null) {
					Hop childHop = v.getHopRef();
					if (childHop != null) {
						// HopRef의 output memory estimate 사용
						totalMemEstimate += childHop.getOutputMemEstimate();
					}
				}
			}

			return totalMemEstimate;
		}

		// 모든 자식 hop들을 재귀적으로 수집 (depth 제한)
		private static Set<Long> getAllChildren(Hop hop, Set<Long> visited, int maxDepth) {
			Set<Long> children = new HashSet<>();

			if (maxDepth <= 0 || visited.contains(hop.getHopID())) {
				return children; // depth 제한 또는 순환 방지
			}

			visited.add(hop.getHopID());

			if (hop.getInput() != null) {
				for (Hop child : hop.getInput()) {
					children.add(child.getHopID());
					children.addAll(getAllChildren(child, visited, maxDepth - 1));
				}
			}

			return children;
		}
	}

}
