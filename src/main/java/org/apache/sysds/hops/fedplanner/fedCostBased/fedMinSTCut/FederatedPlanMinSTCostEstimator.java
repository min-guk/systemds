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
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTCostEstimator {
	public static void estimateProgram(DMLProgram prog, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable, boolean isPrint) {
		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();
		Set<String> functionEstimateCache = new HashSet<>();

		for (StatementBlock sb : prog.getStatementBlocks()) {
			estimateStatementBlock(sb, prog, graph, rewireTable, fnStack, functionEstimateCache, visitedHops);
		}
		// Ensure we also estimate vertices that are not reachable from statement block roots
		// (e.g., FunctionOp output hops such as MULTIRETURN_BUILTIN FUNCTIONOUTPUT nodes).
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex == null || vertex.getHopRef() == null)
				continue;
			estimateHopDAG(vertex.getHopRef(), prog, graph, rewireTable, fnStack, functionEstimateCache,
					visitedHops);
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
		Set<String> functionEstimateCache = new HashSet<>();
		int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);
		graph.setNumOfWorkers(numOfWorkers);
		estimateStatementBlock(function, prog, graph, rewireTable, fnStack, functionEstimateCache, visitedHops);
		// Ensure we also estimate vertices that are not reachable from statement block roots.
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex == null || vertex.getHopRef() == null)
				continue;
			estimateHopDAG(vertex.getHopRef(), prog, graph, rewireTable, fnStack, functionEstimateCache,
					visitedHops);
		}
	}

	public static void estimateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable, Set<String> fnStack, Set<String> functionEstimateCache,
			Set<Long> visitedHops) {

		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			estimateHopDAG(isb.getPredicateHops(), prog, graph, rewireTable, fnStack, functionEstimateCache,
					visitedHops);

			for (StatementBlock innerIsb : istmt.getIfBody())
				estimateStatementBlock(innerIsb, prog, graph, rewireTable, fnStack, functionEstimateCache,
						visitedHops);

			for (StatementBlock innerIsb : istmt.getElseBody())
				estimateStatementBlock(innerIsb, prog, graph, rewireTable, fnStack, functionEstimateCache,
						visitedHops);
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			estimateHopDAG(fsb.getFromHops(), prog, graph, rewireTable, fnStack, functionEstimateCache,
					visitedHops);
			estimateHopDAG(fsb.getToHops(), prog, graph, rewireTable, fnStack, functionEstimateCache,
					visitedHops);
			if (fsb.getIncrementHops() != null) {
				estimateHopDAG(fsb.getIncrementHops(), prog, graph, rewireTable, fnStack, functionEstimateCache,
						visitedHops);
			}

			for (StatementBlock innerFsb : fstmt.getBody())
				estimateStatementBlock(innerFsb, prog, graph, rewireTable, fnStack, functionEstimateCache,
						visitedHops);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			estimateHopDAG(wsb.getPredicateHops(), prog, graph, rewireTable, fnStack, functionEstimateCache,
					visitedHops);

			for (StatementBlock innerWsb : wstmt.getBody())
				estimateStatementBlock(innerWsb, prog, graph, rewireTable, fnStack, functionEstimateCache,
						visitedHops);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				estimateStatementBlock(innerFsb, prog, graph, rewireTable, fnStack, functionEstimateCache,
						visitedHops);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					estimateHopDAG(c, prog, graph, rewireTable, fnStack, functionEstimateCache, visitedHops);
			}
		}
	}

	private static void estimateHopDAG(Hop hop, DMLProgram prog, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable, Set<String> fnStack, Set<String> functionEstimateCache,
			Set<Long> visitedHops) {

		if (!visitedHops.add(hop.getHopID())) {
			return;
		}

		List<Hop> childHops = (hop.getInput() != null) ? new ArrayList<>(hop.getInput()) : new ArrayList<>();
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			List<Hop> transChildHops = rewireTable.get(hop.getHopID());
			if (transChildHops != null && !transChildHops.isEmpty()) {
				for (Hop transChildHop : transChildHops) {
					if (transChildHop instanceof DataOp
							&& ((DataOp) transChildHop).getOp() == Types.OpOpData.TRANSIENTREAD
							&& hop.getName().equals(transChildHop.getName())) {
						continue;
					}
					childHops.add(transChildHop);
				}
			}
		}

		for (Hop inputHop : childHops) {
			estimateHopDAG(inputHop, prog, graph, rewireTable, fnStack, functionEstimateCache, visitedHops);
		}

		if (hop instanceof FunctionOp) {
			FunctionOp fop = (FunctionOp) hop;
			if (fop.getFunctionType() == FunctionType.DML) {
				String fkey = fop.getFunctionKey();

				if (functionEstimateCache != null && functionEstimateCache.contains(fkey)) {
					// already accounted for; avoid duplicate estimation
				} else if (!fnStack.contains(fkey)) {
					boolean pushed = false;
					fnStack.add(fkey);
					pushed = true;
					try {
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
								estimateStatementBlock(fsb, prog, graph, rewireTable, fnStack,
										functionEstimateCache, visitedHops);
								if (functionEstimateCache != null) {
									functionEstimateCache.add(fkey);
								}
							}
						}
					} finally {
						if (pushed) {
							fnStack.remove(fkey);
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

		vertex.setNumParents(estimateNumParents(hop, rewireTable));
		computeVertexCost(vertex, graph.getNumOfWorkers());

		ExecPlacementCaps caps = vertex.getCaps();
		boolean acL = caps.allowCP_LOUT;
		boolean acF = caps.allowCP_FOUT;
		boolean afL = caps.allowFED_LOUT;
		boolean afF = caps.allowFED_FOUT;
		long cId = FederatedPlanMinSTPlanner.computeId(hopID);
		long pId = FederatedPlanMinSTPlanner.placementId(hopID);

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
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE
				&& !"__pred".equals(hop.getName())) {
			List<Hop> inputs = hop.getInput();
			if (inputs != null && !inputs.isEmpty() && inputs.get(0) != null) {
				graph.addTransWriteInputPlacementConsistencyEdge(hopID, inputs.get(0).getHopID());
			}
		}
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			Set<Long> twHopIds = collectTransientWriteChildIds(hop, rewireTable);
			for (Long twHopId : twHopIds) {
				Vertex twVertex = graph.getVertex(twHopId);
				if (twVertex == null) {
					FederatedPlannerLogger.logWarnMessage(
							"[FederatedMinST] Missing TWrite vertex for TRead hop " + hopID);
					continue;
				}
				graph.addTransReadWriteConsistencyEdges(twVertex, twHopId, vertex, hopID);
			}
		}

		List<Hop> childHops = (hop.getInput() != null) ? new ArrayList<>(hop.getInput()) : new ArrayList<>();
		Map<Long, FType> inputFTypeMap = null;
		if (!childHops.isEmpty()) {
			inputFTypeMap = new HashMap<>();
			for (Hop inputHop : childHops) {
				if (inputHop == null)
					continue;
				Vertex inputVertex = graph.getVertex(inputHop.getHopID());
				FType inputFType = (inputVertex != null) ? inputVertex.getDataType() : null;
				if (inputFType != null)
					inputFTypeMap.put(inputHop.getHopID(), inputFType);
			}
		}
		for (int i = 0; i < childHops.size(); i++) {
			Hop childHop = childHops.get(i);
			if (childHop == null)
				continue;
			Vertex childVertex = graph.getVertex(childHop.getHopID());
			if (childVertex == null)
				continue;

			graph.addParentChildNetEdge(childVertex, childHop.getHopID(), vertex, hopID);

			// Enforce: if the parent executes in FED, required matrix inputs must be federated (FOUT).
			// This prevents FED ops from consuming local-only inputs at runtime.
			if (childHop.getDataType() != null && childHop.getDataType().isMatrix()) {
				FederatedRefedPolicy.InputRequirement req =
						FederatedRefedPolicy.getInputRequirementForFedExec(hop, childHop, i, inputFTypeMap);
				if (req == FederatedRefedPolicy.InputRequirement.REQUIRED
						|| req == FederatedRefedPolicy.InputRequirement.AMBIGUOUS) {
					graph.addRequiredFedInputEdge(hopID, childHop.getHopID());
				}
			}
		}

		addLoopCarryEdgesForHop(hop, vertex, graph);
	}

	public static void computeVertexCost(Vertex vertex, int numOfWorkers) {
		Hop hop = vertex.getHopRef();
		double opCostWithWeight = 0;
		double uploadCostWithoutWeight = 0;
		double cpUploadCostWithoutWeight = 0;
		double downloadCostWithoutWeight = 0;
		FType cpFoutType = vertex.getCpFoutDataType();
		if (cpFoutType == null) {
			cpFoutType = vertex.getDataType();
		}

		if (hop instanceof DataOp) {
			if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
				opCostWithWeight = 0;
				uploadCostWithoutWeight = 0;
				cpUploadCostWithoutWeight = 0;
				downloadCostWithoutWeight = 0;
			} else if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
				opCostWithWeight = 0;
				uploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						hop.getOutputMemEstimate(), vertex.getDataType(), numOfWorkers);
				cpUploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						hop.getOutputMemEstimate(), cpFoutType, numOfWorkers);
				downloadCostWithoutWeight = FederatedCostModel.computeDownloadNetworkCost(
						hop.getOutputMemEstimate());
			} else {
				double opCost = FederatedCostModel.computeOpCost(hop);
				opCostWithWeight = vertex.getOpWeight() * opCost;
				uploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						hop.getOutputMemEstimate(), vertex.getDataType(), numOfWorkers);
				cpUploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						hop.getOutputMemEstimate(), cpFoutType, numOfWorkers);
				downloadCostWithoutWeight = FederatedCostModel.computeDownloadNetworkCost(
						hop.getOutputMemEstimate());
			}
			vertex.setCost(opCostWithWeight, uploadCostWithoutWeight, downloadCostWithoutWeight);
			vertex.setCpUploadCostWithoutWeight(cpUploadCostWithoutWeight);
			return;
		}

		double opCost = FederatedCostModel.computeOpCost(hop);
		opCostWithWeight = vertex.getOpWeight() * opCost;
		uploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
				hop.getOutputMemEstimate(), vertex.getDataType(), numOfWorkers);
		cpUploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
				hop.getOutputMemEstimate(), cpFoutType, numOfWorkers);
		downloadCostWithoutWeight = FederatedCostModel.computeDownloadNetworkCost(
				hop.getOutputMemEstimate());

		vertex.setCost(opCostWithWeight, uploadCostWithoutWeight, downloadCostWithoutWeight);
		vertex.setCpUploadCostWithoutWeight(cpUploadCostWithoutWeight);
	}

	private static int estimateNumParents(Hop hop, Map<Long, List<Hop>> rewireTable) {
		if (hop == null) {
			return 1;
		}

		int numParents = (hop.getParent() != null) ? hop.getParent().size() : 0;
		if (hop instanceof DataOp) {
			Types.OpOpData opType = ((DataOp) hop).getOp();
			if (opType == Types.OpOpData.TRANSIENTWRITE && !"__pred".equals(hop.getName())) {
				List<Hop> transParents = (rewireTable != null) ? rewireTable.get(hop.getHopID()) : null;
				if (transParents != null) {
					numParents += transParents.size();
				}
			}
		}

		return Math.max(1, numParents);
	}

	private static Set<Long> collectTransientWriteChildIds(Hop hop, Map<Long, List<Hop>> rewireTable) {
		Set<Long> matches = new HashSet<>();
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD) {
			return matches;
		}
		if (rewireTable == null) {
			return matches;
		}
		List<Hop> candidates = rewireTable.get(hop.getHopID());
		if (candidates == null || candidates.isEmpty()) {
			return matches;
		}
		String hopName = hop.getName();
		Set<Long> fallback = new HashSet<>();
		for (Hop candidate : candidates) {
			if (!(candidate instanceof DataOp)
					|| ((DataOp) candidate).getOp() != Types.OpOpData.TRANSIENTWRITE) {
				continue;
			}
			fallback.add(candidate.getHopID());
			if (hopName != null && hopName.equals(candidate.getName())) {
				matches.add(candidate.getHopID());
			}
		}
		if (matches.isEmpty()) {
			matches.addAll(fallback);
		}
		return matches;
	}

	private static void addLoopCarryEdgesForHop(Hop hop, Vertex vertex, FederatedPlanMinSTGraph graph) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD) {
			return;
		}
		List<FederatedPlanMinSTGraph.LoopCarryEdge> loopEdges = graph.getLoopCarryEdges();
		if (loopEdges.isEmpty()) {
			return;
		}
		long hopId = hop.getHopID();
		for (FederatedPlanMinSTGraph.LoopCarryEdge edge : loopEdges) {
			if (edge.getFrontReaderHopId() != hopId) {
				continue;
			}
			if (graph.getVertex(edge.getEndWriterHopId()) == null) {
				continue;
			}
			double weight = edge.getWeight();
			if (weight <= 0.0) {
				continue;
			}
			// Enforce TW/TR consistency across loop iterations as a hard constraint (planner must
			// not rely on runtime fallbacks for loop-carried transient variables).
			Vertex writerVertex = graph.getVertex(edge.getEndWriterHopId());
			if (writerVertex != null) {
				graph.addTransReadWriteConsistencyEdges(writerVertex, edge.getEndWriterHopId(), vertex, hopId);
			}
			// Use CP->FOUT upload cost here as well: loop-carry edges model data movement
			// between local/federated contexts, and the CP upload FType (e.g., BROADCAST)
			// must be reflected in the cost when applicable.
			double uploadWeighted = weight * vertex.getCpUploadCostWithoutWeight();
			double downloadWeighted = weight * vertex.getDownloadCostWithoutWeight();
			graph.addLoopCarryNetEdge(hopId, edge.getEndWriterHopId(), uploadWeighted, downloadWeighted);
		}
	}
}
