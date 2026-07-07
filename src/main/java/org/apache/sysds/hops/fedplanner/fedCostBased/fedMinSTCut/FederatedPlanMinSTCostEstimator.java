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
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy.InputRequirement;
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
	private static final boolean ENABLE_TRANSREAD_DEBUG =
			Boolean.getBoolean("sysds.fedplanner.transread.debug");
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
		appendFunctionOutputHopsIfNeeded(hop, rewireTable, childHops);

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

		List<Hop> childHops = (hop.getInput() != null) ? new ArrayList<>(hop.getInput()) : new ArrayList<>();
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			List<Hop> transChildHops = (rewireTable != null) ? rewireTable.get(hopID) : null;
			if (transChildHops != null && !transChildHops.isEmpty()) {
				Set<Long> seenChildIds = new HashSet<>();
				for (Hop childHop : childHops) {
					if (childHop != null)
						seenChildIds.add(childHop.getHopID());
				}
				for (Hop transChildHop : transChildHops) {
					if (transChildHop == null) {
						continue;
					}
					if (transChildHop instanceof DataOp
							&& ((DataOp) transChildHop).getOp() == Types.OpOpData.TRANSIENTREAD
							&& Objects.equals(hop.getName(), transChildHop.getName())) {
						continue;
					}
					if (seenChildIds.add(transChildHop.getHopID()))
						childHops.add(transChildHop);
				}
			}
		}
		appendFunctionOutputHopsIfNeeded(hop, rewireTable, childHops);
		Hop explicitFunctionOutputSourceHop = (hop instanceof DataOp
				&& ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD)
				? FederatedPlannerUtils.getPreferredMultiReturnFunctionOutputSourceForTransientRead(
						(DataOp) hop, childHops)
				: null;
		if (explicitFunctionOutputSourceHop != null) {
			FederatedPlannerUtils.propagateMultiReturnFunctionOutputStatsToTransientRead(
					(DataOp) hop, explicitFunctionOutputSourceHop);
		}
		vertex.setSourceOutputMemEstimateOverride(explicitFunctionOutputSourceHop != null
				? FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(hop, explicitFunctionOutputSourceHop)
				: -1.0);

		vertex.setNumParents(estimateNumParents(hop, rewireTable));
		computeVertexCost(vertex, graph.getNumOfWorkers(), graph);

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

		Set<Long> tWriteChildIds = collectTransientWriteChildIds(hop, rewireTable);
		if (tWriteChildIds.isEmpty()) {
			tWriteChildIds = collectTransientWriteByName(hop, graph);
		}
		if (!tWriteChildIds.isEmpty()) {
			if (vertex.getTransientWriteHopId() == null) {
				vertex.setTransientWriteHopId(tWriteChildIds.iterator().next());
			}
			for (Long twHopId : tWriteChildIds) {
				if (twHopId == null) {
					continue;
				}
				Vertex twVertex = graph.getVertex(twHopId);
				if (twVertex == null) {
					continue;
				}
				graph.addTransReadWriteConsistencyEdges(twVertex, twHopId, vertex, hopID);
			}
		}
		for (int i = 0; i < childHops.size(); i++) {
			Hop childHop = childHops.get(i);
			if (childHop == null)
				continue;
			Vertex childVertex = graph.getVertex(childHop.getHopID());
			if (childVertex == null)
				continue;

			boolean requiresFederatedInput = requiresFederatedInputForParent(hop, childHop, i, graph);
			graph.addParentChildNetEdge(childVertex, childHop.getHopID(), vertex, hopID, requiresFederatedInput);

		}

		addLoopCarryEdgesForHop(hop, vertex, graph);
	}

	private static void appendFunctionOutputHopsIfNeeded(Hop hop, Map<Long, List<Hop>> rewireTable, List<Hop> childHops) {
		if (!(hop instanceof FunctionOp)
				|| ((FunctionOp) hop).getFunctionType() != FunctionType.DML
				|| rewireTable == null)
			return;
		List<Hop> functionOutputHops = rewireTable.get(hop.getHopID());
		if (functionOutputHops == null || functionOutputHops.isEmpty())
			return;
		Set<Long> seenChildIds = new HashSet<>();
		for (Hop childHop : childHops) {
			if (childHop != null)
				seenChildIds.add(childHop.getHopID());
		}
		for (Hop outputHop : functionOutputHops) {
			if (outputHop == null)
				continue;
			if (seenChildIds.add(outputHop.getHopID()))
				childHops.add(outputHop);
		}
	}

	private static boolean requiresFederatedInputForParent(Hop parentHop, Hop inputHop, int inputIndex,
			FederatedPlanMinSTGraph graph) {
		if (parentHop == null || inputHop == null || graph == null)
			return true;
		if (inputHop.getDataType() == null || !inputHop.getDataType().isMatrix())
			return false;

		Map<Long, FType> knownFTypes = collectKnownInputFTypes(parentHop, graph);
		InputRequirement requirement = FederatedRefedPolicy.getInputRequirementForFedExec(
				parentHop, inputHop, inputIndex, knownFTypes);
		return requirement != InputRequirement.OPTIONAL;
	}

	private static Map<Long, FType> collectKnownInputFTypes(Hop parentHop, FederatedPlanMinSTGraph graph) {
		Map<Long, FType> knownFTypes = new HashMap<>();
		List<Hop> inputs = parentHop.getInput();
		if (inputs == null || inputs.isEmpty())
			return knownFTypes;
		for (Hop input : inputs) {
			if (input == null)
				continue;
			Vertex inputVertex = graph.getVertex(input.getHopID());
			if (inputVertex == null)
				continue;
			FType inputFType = inputVertex.getDataType();
			if (inputFType != null)
				knownFTypes.put(input.getHopID(), inputFType);
		}
		return knownFTypes;
	}

	public static void computeVertexCost(Vertex vertex, int numOfWorkers) {
		computeVertexCost(vertex, numOfWorkers, null);
	}

	public static void computeVertexCost(Vertex vertex, int numOfWorkers, FederatedPlanMinSTGraph graph) {
		Hop hop = vertex.getHopRef();
		double opCostWithWeight = 0;
		double uploadCostWithoutWeight = 0;
		double cpUploadCostWithoutWeight = 0;
		double downloadCostWithoutWeight = 0;
		double sourceOutputMemEstimateOverride = vertex.getSourceOutputMemEstimateOverride();
		double outputMemEstimate = sourceOutputMemEstimateOverride > 0.0
				? sourceOutputMemEstimateOverride
				: FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		double uploadMemEstimate = sourceOutputMemEstimateOverride > 0.0
				? sourceOutputMemEstimateOverride
				: FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		FType cpFoutType = vertex.getCpFoutDataType();
		if (cpFoutType == null) {
			cpFoutType = vertex.getDataType();
		}
		// Mirror runtime CP->FOUT materialization behavior when a global anchor key is present.
		// If the anchor implies ROW/COL partitioning but this hop's axis length doesn't match,
		// local->federated uploads will effectively broadcast at runtime; reflect that in cost.
		cpFoutType = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(hop, cpFoutType);

		if (hop instanceof DataOp) {
			if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
				opCostWithWeight = 0;
				uploadCostWithoutWeight = 0;
				cpUploadCostWithoutWeight = 0;
				downloadCostWithoutWeight = 0;
			} else if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
				opCostWithWeight = 0;
				uploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						uploadMemEstimate, vertex.getDataType(), numOfWorkers);
				cpUploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						uploadMemEstimate, cpFoutType, numOfWorkers);
				downloadCostWithoutWeight = FederatedCostModel.computeDownloadNetworkCost(
						uploadMemEstimate);
			} else {
				double opCost = FederatedCostModel.computeOpCostWithFallback(hop);
				opCostWithWeight = vertex.getOpWeight() * opCost;
				uploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						uploadMemEstimate, vertex.getDataType(), numOfWorkers);
				cpUploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
						uploadMemEstimate, cpFoutType, numOfWorkers);
				downloadCostWithoutWeight = FederatedCostModel.computeDownloadNetworkCost(
						uploadMemEstimate);
			}
			vertex.setCost(opCostWithWeight, uploadCostWithoutWeight, downloadCostWithoutWeight);
			vertex.setCpUploadCostWithoutWeight(cpUploadCostWithoutWeight);
			vertex.setFedInputPreparationCostWithWeight(0.0);
			return;
		}

		double opCost = FederatedCostModel.computeLocalIndexingCostWithFallback(
				hop, FederatedCostModel.computeOpCostWithFallback(hop));
		opCostWithWeight = vertex.getOpWeight() * opCost;
		uploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
				uploadMemEstimate, vertex.getDataType(), numOfWorkers);
		cpUploadCostWithoutWeight = FederatedCostModel.computeUploadNetworkCost(
				uploadMemEstimate, cpFoutType, numOfWorkers);
		FederatedCostModel.MixedFedLocalCost mixedFedLocalCost =
				FederatedCostModel.computeMixedFedLocalCost(
						hop, collectVertexInputHops(hop),
						collectVertexInputFTypes(hop, graph), vertex.getDataType(),
						opCost, outputMemEstimate, numOfWorkers);
		double genericDownloadCostWithoutWeight = FederatedCostModel.computeDownloadNetworkCost(uploadMemEstimate);
		double nativeAggUnaryDownloadCostWithoutWeight =
				FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
						hop, vertex.getDataType(), outputMemEstimate, numOfWorkers,
						genericDownloadCostWithoutWeight);
		downloadCostWithoutWeight = mixedFedLocalCost.hasCoordinatorPhase()
				? mixedFedLocalCost.getCoordinatorPhaseCost()
				: nativeAggUnaryDownloadCostWithoutWeight;
		double fedInputPreparationCostWithWeight =
				vertex.getOpWeight() * mixedFedLocalCost.getInputPreparationCost();

		vertex.setCost(opCostWithWeight, uploadCostWithoutWeight, downloadCostWithoutWeight);
		vertex.setCpUploadCostWithoutWeight(cpUploadCostWithoutWeight);
		vertex.setFedInputPreparationCostWithWeight(fedInputPreparationCostWithWeight);
	}

	private static List<Hop> collectVertexInputHops(Hop hop) {
		if (hop == null || hop.getInput() == null || hop.getInput().isEmpty())
			return Collections.emptyList();
		return new ArrayList<>(hop.getInput());
	}

	private static List<FType> collectVertexInputFTypes(Hop hop, FederatedPlanMinSTGraph graph) {
		if (hop == null || hop.getInput() == null || hop.getInput().isEmpty())
			return Collections.emptyList();
		List<FType> inputFTypes = new ArrayList<>(hop.getInput().size());
		for (Hop input : hop.getInput()) {
			Vertex inputVertex = (graph != null && input != null)
					? graph.getVertex(input.getHopID())
					: null;
			inputFTypes.add(inputVertex != null ? inputVertex.getDataType() : null);
		}
		return inputFTypes;
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
		Set<Long> preferred = matches.isEmpty() ? fallback : matches;
		Set<Long> dimCompatible = new HashSet<>();
		for (Hop candidate : candidates) {
			if (!(candidate instanceof DataOp)
					|| ((DataOp) candidate).getOp() != Types.OpOpData.TRANSIENTWRITE) {
				continue;
			}
			if (!preferred.contains(candidate.getHopID())) {
				continue;
			}
			if (dimsCompatible(hop.getDim1(), hop.getDim2(), candidate.getDim1(), candidate.getDim2())) {
				dimCompatible.add(candidate.getHopID());
			}
		}
		if (!dimCompatible.isEmpty()) {
			preferred = dimCompatible;
		}
		List<Hop> preferredHops = new ArrayList<>();
		for (Hop candidate : candidates) {
			if (!(candidate instanceof DataOp)
					|| ((DataOp) candidate).getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;
			if (preferred.contains(candidate.getHopID()))
				preferredHops.add(candidate);
		}
		List<Hop> dominating = TransTableRewireUtils.preferDominatingTransientWrites(preferredHops, (DataOp) hop);
		if (dominating == preferredHops)
			return preferred;
		Set<Long> dominatingIds = new HashSet<>();
		for (Hop dominatingHop : dominating)
			dominatingIds.add(dominatingHop.getHopID());
		return dominatingIds.isEmpty() ? preferred : dominatingIds;
	}

	private static Set<Long> collectTransientWriteByName(Hop hop, FederatedPlanMinSTGraph graph) {
		Set<Long> matches = new HashSet<>();
		if (graph == null) {
			return matches;
		}
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD) {
			return matches;
		}
		String hopName = hop.getName();
		if (hopName == null || hopName.isEmpty() || "__pred".equals(hopName)) {
			return matches;
		}
		long d1 = hop.getDim1();
		long d2 = hop.getDim2();
		for (Vertex v : graph.getMemoTable().values()) {
			if (v == null || v.getHopRef() == null) {
				continue;
			}
			Hop candidate = v.getHopRef();
			if (!(candidate instanceof DataOp)
					|| ((DataOp) candidate).getOp() != Types.OpOpData.TRANSIENTWRITE) {
				continue;
			}
			if ("__pred".equals(candidate.getName())) {
				continue;
			}
			if (hopName.equals(candidate.getName())
					&& hop.getDataType() == candidate.getDataType()
					&& dimsCompatible(d1, d2, candidate.getDim1(), candidate.getDim2())) {
				matches.add(candidate.getHopID());
			}
		}
		return matches;
	}

	private static boolean dimsCompatible(long d1a, long d2a, long d1b, long d2b) {
		boolean d1Known = d1a > 0 && d1b > 0;
		boolean d2Known = d2a > 0 && d2b > 0;
		if (d1Known && d1a != d1b) {
			return false;
		}
		if (d2Known && d2a != d2b) {
			return false;
		}
		return true;
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
			Vertex writerVertex = graph.getVertex(edge.getEndWriterHopId());
			if (writerVertex == null) {
				continue;
			}
			double weight = edge.getWeight();
			if (weight <= 0.0) {
				continue;
			}
			// Use CP->FOUT upload cost here as well: loop-carry edges model data movement
			// between local/federated contexts, and the CP upload FType (e.g., BROADCAST)
			// must be reflected in the cost when applicable.
			FType uploadType = vertex.getCpFoutDataType();
			if (uploadType == null)
				uploadType = vertex.getDataType();
			double uploadCost = vertex.getCpUploadCostWithoutWeight();
			if (Double.isNaN(uploadCost) || uploadCost <= 0.0) {
				double readerOutputMemEstimate = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
				boolean preferWriterFallback = readerOutputMemEstimate <= 0.0
						&& hop.getDim1() <= 0 && hop.getDim2() <= 0;
				double outputMemEstimate = preferWriterFallback
						? 0.0
						: FederatedCostModel.getEffectiveUploadMemEstimate(hop);
				// Prefer reader estimate first, but for fully-unknown TRead loop-carried vars
				// with no concrete reader-side output estimate, fall back to the matched writer
				// instead of charging a synthetic unknown-dim upload floor.
				if (outputMemEstimate <= 0.0 && writerVertex.getHopRef() != null) {
					outputMemEstimate = FederatedCostModel.getEffectiveUploadMemEstimate(writerVertex.getHopRef());
				}
				if (outputMemEstimate > 0.0) {
					uploadCost = FederatedCostModel.computeUploadNetworkCost(
							outputMemEstimate, uploadType, graph.getNumOfWorkers());
				}
			}
			if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0)) {
				uploadCost += FederatedCostModel.computeLocalToFedForwardingPenalty(
						uploadType, graph.getNumOfWorkers());
			}
			double uploadWeighted = weight * uploadCost;
			double downloadWeighted = weight * vertex.getDownloadCostWithoutWeight();
			graph.addLoopCarryNetEdge(hopId, edge.getEndWriterHopId(), uploadWeighted, downloadWeighted);
		}
	}
}
