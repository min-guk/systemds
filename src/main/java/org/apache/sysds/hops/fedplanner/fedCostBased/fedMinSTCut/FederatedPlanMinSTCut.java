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
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
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
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTCut extends AFederatedPlanner {
	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
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

		int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);
		graph.setNumOfWorkers(numOfWorkers);
		FederatedPlanMinSTCostEstimator.estimateProgram(prog, graph, rewireTable, true);

		graph.getOptimalPlan();
		Map<Long, Pair<ExecType, FederatedOutput>> plannedExecOut = capturePlannedExecOutputs(graph);
		Map<Long, FType> plannedFTypeMap = buildPlannedFTypeMap(graph);
		Map<Long, FType> fTypeMap = new HashMap<>(plannedFTypeMap);
		FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
		restoreMinstPlanDecisions(plannedExecOut, graph);
		registerMinstCpfoutSelections(graph, fTypeMap);
		registerMinstSelectedObligations(graph, fTypeMap);
		restoreMinstPlanDecisions(plannedExecOut, graph);
		Map<Long, FType> resolvedFTypeMap = buildPlannedFTypeMap(graph);
		validateMinstPlanConsistency(plannedExecOut, resolvedFTypeMap, graph);
		FederatedPlannerLogger.logOptimalPlanStructured(graph);
		FederatedPlannerLogger.logOptimalPlan(graph, true);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		FederatedPlanMinSTCostEstimator.estimateFunctionDynamic(function, graph, true);
		graph.getOptimalPlan();
		Map<Long, Pair<ExecType, FederatedOutput>> plannedExecOut = capturePlannedExecOutputs(graph);
		Map<Long, FType> plannedFTypeMap = buildPlannedFTypeMap(graph);
		Map<Long, FType> fTypeMap = new HashMap<>(plannedFTypeMap);
		FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
		restoreMinstPlanDecisions(plannedExecOut, graph);
		registerMinstCpfoutSelections(graph, fTypeMap);
		registerMinstSelectedObligations(graph, fTypeMap);
		restoreMinstPlanDecisions(plannedExecOut, graph);
		Map<Long, FType> resolvedFTypeMap = buildPlannedFTypeMap(graph);
		validateMinstPlanConsistency(plannedExecOut, resolvedFTypeMap, graph);
		FederatedPlannerLogger.logOptimalPlanStructured(graph);
		FederatedPlannerLogger.logOptimalPlan(graph, true);
	}

	private static Map<Long, Pair<ExecType, FederatedOutput>> capturePlannedExecOutputs(
			FederatedPlanMinSTGraph graph) {
		Map<Long, Pair<ExecType, FederatedOutput>> planned = new HashMap<>();
		if (graph == null)
			return planned;
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex == null)
				continue;
			Hop hop = vertex.getHopRef();
			if (hop == null)
				continue;
			ExecType exec = hop.getForcedExecType();
			if (exec == null)
				exec = hop.getExecType();
			FederatedOutput out = hop.getFederatedOutput();
			planned.put(vertex.getHopID(), Pair.of(exec, out));
		}
		return planned;
	}

	private static void restoreMinstPlanDecisions(Map<Long, Pair<ExecType, FederatedOutput>> plannedExecOut,
			FederatedPlanMinSTGraph graph) {
		if (plannedExecOut == null || graph == null)
			return;
		for (Map.Entry<Long, Pair<ExecType, FederatedOutput>> entry : plannedExecOut.entrySet()) {
			Vertex vertex = graph.getVertex(entry.getKey());
			if (vertex == null || vertex.getHopRef() == null || entry.getValue() == null)
				continue;
			Hop hop = vertex.getHopRef();
			hop.setForcedExecType(entry.getValue().getLeft());
			hop.setFederatedOutput(entry.getValue().getRight());
		}
	}

	private static List<Hop> collectGraphHops(FederatedPlanMinSTGraph graph) {
		List<Hop> hops = new ArrayList<>();
		if (graph == null)
			return hops;
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex == null || vertex.getHopRef() == null)
				continue;
			hops.add(vertex.getHopRef());
		}
		return hops;
	}

	private static void registerMinstCpfoutSelections(FederatedPlanMinSTGraph graph,
			Map<Long, FType> fTypeMap) {
		if (graph == null || fTypeMap == null)
			return;
		List<Hop> cpfoutHops = new ArrayList<>();
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex == null || vertex.getHopRef() == null)
				continue;
			Hop hop = vertex.getHopRef();
			ExecType exec = hop.getForcedExecType();
			if (exec == null)
				exec = hop.getExecType();
			if (exec == ExecType.CP && hop.getFederatedOutput() == FederatedOutput.FOUT)
				cpfoutHops.add(hop);
		}
		if (!cpfoutHops.isEmpty())
			FederatedRefedPolicy.registerFoutMaterializeCandidates(cpfoutHops, fTypeMap, -1L);
	}

	private static void registerMinstSelectedObligations(FederatedPlanMinSTGraph graph,
			Map<Long, FType> fTypeMap) {
		if (graph == null || fTypeMap == null)
			return;
		for (FederatedPlanMinSTGraph.SelectedObligation obligation : graph.getSelectedObligations()) {
			if (obligation == null)
				continue;
			if (!obligation.hasCapability()) {
				throw new DMLRuntimeException("MinST selected obligation without runtime capability: "
						+ obligation);
			}
			if (obligation.getKind() == FederatedPlanMinSTGraph.ObligationKind.D) {
				String fTypeHint = obligation.getFType() != null ? obligation.getFType().name() : null;
				FederatedPlannerLogger.logInfoMessage("[MinST] Register selected D obligation: " + obligation);
				FederatedLocalMaterializeRegistry.register(-1L, obligation.getChildHopId(),
						obligation.getConsumerHopIds(), fTypeHint, obligation.getReason());
				continue;
			}
			if (obligation.getKind() != FederatedPlanMinSTGraph.ObligationKind.U)
				continue;
			Hop hop = graph.getHopRef(obligation.getChildHopId());
			if (hop == null)
				continue;
			ExecType oldExec = hop.getForcedExecType();
			FederatedOutput oldOut = hop.getFederatedOutput();
			if (obligation.getFType() != null)
				fTypeMap.put(obligation.getChildHopId(), obligation.getFType());
			FederatedPlannerLogger.logInfoMessage("[MinST] Register selected U obligation: " + obligation);
			FederatedRefedPolicy.registerFoutMaterializeObligation(hop,
					collectObligationConsumers(graph, obligation), fTypeMap, -1L);
			hop.setForcedExecType(oldExec);
			hop.setFederatedOutput(oldOut);
			if (!hasCpFoutRegistration(hop)) {
				throw new DMLRuntimeException("MinST selected U obligation for hop "
						+ obligation.getChildHopId()
						+ " but no refed/materialize entry was registered: " + obligation);
			}
		}
	}

	private static List<Hop> collectObligationConsumers(FederatedPlanMinSTGraph graph,
			FederatedPlanMinSTGraph.SelectedObligation obligation) {
		List<Hop> consumers = new ArrayList<>();
		if (graph == null || obligation == null)
			return consumers;
		for (Long consumerId : obligation.getConsumerHopIds()) {
			if (consumerId == null || !graph.contains(consumerId))
				continue;
			Hop consumer = graph.getHopRef(consumerId);
			if (consumer != null)
				consumers.add(consumer);
		}
		return consumers;
	}

	private static Map<Long, FType> buildPlannedFTypeMap(FederatedPlanMinSTGraph graph) {
		Map<Long, FType> fTypeMap = new HashMap<>();
		if (graph == null)
			return fTypeMap;
		for (Vertex vertex : graph.getMemoTable().values()) {
			Hop hop = vertex.getHopRef();
			if (hop == null)
				continue;
			boolean isTransient = (hop instanceof DataOp)
				&& (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD
					|| ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE);
			FType fType = null;
			if (hop.getFederatedOutput() == FederatedOutput.FOUT) {
				fType = vertex.getDataType();
				if (hop.getForcedExecType() == ExecType.CP) {
					FType cpFoutType = vertex.getCpFoutDataType();
					if (cpFoutType != null)
						fType = cpFoutType;
				}
			} else if (!isTransient) {
				// Local outputs can still require CP->FOUT materialization; keep the
				// inferred FType as a hint for refed insertion, but they are NOT treated
				// as federated sources (see isFederatedInput()).
				fType = vertex.getCpFoutDataType();
				// Some vertices can miss an explicit cpFout type even though the logical
				// FType is already BROADCAST/ROW/COL. Keep that planner signal so
				// refed-policy does not silently fall back to fed_refed on unknown dims.
				if (fType == null)
					fType = vertex.getDataType();
			}
			if (fType != null)
				fTypeMap.put(vertex.getHopID(), fType);
		}
		return fTypeMap;
	}

	private static void validateMinstPlanConsistency(
			Map<Long, Pair<ExecType, FederatedOutput>> plannedExecOut,
			Map<Long, FType> plannedFTypeMap,
			FederatedPlanMinSTGraph graph) {
		if (graph == null || plannedExecOut == null || plannedExecOut.isEmpty())
			return;
		for (Map.Entry<Long, Pair<ExecType, FederatedOutput>> entry : plannedExecOut.entrySet()) {
			long hopId = entry.getKey();
			Pair<ExecType, FederatedOutput> planned = entry.getValue();
			Vertex vertex = graph.getVertex(hopId);
			if (vertex == null)
				continue;
			Hop hop = vertex.getHopRef();
			if (hop == null)
				continue;
			ExecType curExec = hop.getForcedExecType();
			if (curExec == null)
				curExec = hop.getExecType();
			FederatedOutput curOut = hop.getFederatedOutput();
			if (planned.getLeft() != curExec || planned.getRight() != curOut) {
				throw new DMLRuntimeException("MinST plan changed during resolve for hop "
						+ hopId + " (" + hop.getOpString() + "): planned=" + planned.getLeft()
						+ "/" + planned.getRight() + " resolved=" + curExec + "/" + curOut);
			}

			if (planned.getLeft() == ExecType.CP && planned.getRight() == FederatedOutput.FOUT) {
				if (!hasCpFoutRegistration(hop)) {
					throw new DMLRuntimeException("MinST plan requires CP->FOUT for hop " + hopId
							+ " (" + hop.getOpString() + ") but no refed/materialize entry was registered.");
				}
			}

			if (planned.getLeft() == ExecType.FED) {
				// Validate FED feasibility against the actual planned ExecType/FedOutput markers.
				// Using the FType-only variant would incorrectly treat CP->FOUT hint entries as
				// already-federated sources.
				boolean ok = FederatedRefedPolicy.canSatisfyFederatedInputs(hop, plannedFTypeMap);
				if (!ok)
					FederatedPlannerLogger.logWarnMessage(
							"[MinST] FED feasibility check failed after resolve for hop "
									+ hopId + " (" + hop.getOpString() + "); continuing (policy may have inserted refed/fout via transient anchors).");
			}
		}
	}

	private static boolean hasCpFoutRegistration(Hop hop) {
		if (hop == null)
			return false;
		if (isExistingFederatedTransientSource(hop))
			return true;
		long hopId = hop.getHopID();
		if (FederatedRefedRegistry.hasEntry(hopId) || FederatedFoutMaterializeRegistry.hasEntry(hopId))
			return true;
		List<Hop> parents = hop.getParent();
		if (parents == null || parents.isEmpty())
			return false;
		for (Hop parent : parents) {
			if (!(parent instanceof DataOp))
				continue;
			DataOp dataOp = (DataOp) parent;
			if (dataOp.getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;
			List<Hop> inputs = dataOp.getInput();
			if (inputs == null || inputs.isEmpty() || inputs.get(0) != hop)
				continue;
			long parentHopId = dataOp.getHopID();
			if (FederatedRefedRegistry.hasEntry(parentHopId)
					|| FederatedFoutMaterializeRegistry.hasEntry(parentHopId))
				return true;
		}
		return false;
	}

	private static boolean isExistingFederatedTransientSource(Hop hop) {
		if (!(hop instanceof DataOp))
			return false;
		DataOp dataOp = (DataOp) hop;
		if (dataOp.getOp() != Types.OpOpData.TRANSIENTREAD)
			return false;
		String name = dataOp.getName();
		if (name == null || name.isEmpty())
			return false;
		return FederatedPlannerUtils.isFedInitVar(name);
	}
}
