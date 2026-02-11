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
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
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

public class FederatedPlanMinSTRewire {
	public static final String FED_MATRIX_IDENTIFIER = "matrix";
	public static final String FED_FRAME_IDENTIFIER = "frame";
	// Guard rail for enabling planner-only federated alternative fallback.
	// High local->fed forwarding control penalty (e.g., WAN) should keep strict feasibility
	// and avoid resurrecting expensive FED alternatives for optional/local-capable edges.
	private static final double FED_ALT_FALLBACK_MAX_CTRL_PENALTY_MS = 10.0;

	private static class LoopAnalysisContext {
		private final Map<String, Boolean> readFromOutside = new HashMap<>();
		private final Map<String, List<Hop>> headerReads = new HashMap<>();
		private final Set<String> writtenVars = new HashSet<>();
		private final boolean trackReadFromOutside;
		private final boolean trackHeaderReads;
		private final boolean includeTransReadChildren;

		private LoopAnalysisContext(boolean trackReadFromOutside, boolean trackHeaderReads,
				boolean includeTransReadChildren) {
			this.trackReadFromOutside = trackReadFromOutside;
			this.trackHeaderReads = trackHeaderReads;
			this.includeTransReadChildren = includeTransReadChildren;
		}

		private void markReadFromOutside(String var) {
			if (!trackReadFromOutside || var == null)
				return;
			readFromOutside.put(var, true);
		}

		private void markWritten(String var) {
			if (var == null)
				return;
			writtenVars.add(var);
		}

		private boolean hasWritten(String var) {
			return var != null && writtenVars.contains(var);
		}

		private Set<String> snapshotWritten() {
			return new HashSet<>(writtenVars);
		}

		private void restoreWritten(Set<String> snapshot) {
			writtenVars.clear();
			if (snapshot != null && !snapshot.isEmpty())
				writtenVars.addAll(snapshot);
		}

		private void retainWritten(Set<String> other) {
			if (other == null)
				writtenVars.clear();
			else
				writtenVars.retainAll(other);
		}

		private void recordHeaderRead(String var, Hop treadHop) {
			if (!trackHeaderReads || var == null || treadHop == null)
				return;
			headerReads.computeIfAbsent(var, k -> new ArrayList<>()).add(treadHop);
		}

		private Map<String, Boolean> getReadFromOutside() {
			return readFromOutside;
		}

		private Map<String, List<Hop>> getHeaderReads() {
			return headerReads;
		}

		private boolean includeTransReadChildren() {
			return includeTransReadChildren;
		}
	}

	public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			FederatedPlanMinSTGraph graph, List<Pair<FederatedRange, FederatedData>> fedMap,
			Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet,
			OracleFacade oracleFacade) {
		// Maps HopID -> Privacy constraint
		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
		Set<Long> visitedHops = new HashSet<>();
		Set<String> fnStack = new HashSet<>();
		Set<Long> injectedIds = new HashSet<>();
		Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
		List<Pair<Long, Double>> loopStack = new ArrayList<>();
		List<LoopAnalysisContext> loopCtxStack = new ArrayList<>();

		List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);

			for (StatementBlock sb : prog.getStatementBlocks()) {
				Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
						graph, outerTransTableList, null, privacyConstraintMap, fTypeMap,
						fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds, functionTransTableCache,
						1, 1, loopStack, loopCtxStack, oracleFacade);
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
			Set<Long> injectedIds = new HashSet<>();
			Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
			List<Pair<Long, Double>> loopStack = new ArrayList<>();
			List<LoopAnalysisContext> loopCtxStack = new ArrayList<>();
			List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);
			// Todo (Future): not tested & not used
			rewireStatementBlock(function, prog, visitedHops, rewireTable, graph, outerTransTableList, null,
					privacyConstraintMap, fTypeMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds, functionTransTableCache,
					1, 1, loopStack, loopCtxStack, oracleFacade);
		}

		public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog,
				Set<Long> visitedHops,
				Map<Long, List<Hop>> rewireTable, FederatedPlanMinSTGraph graph,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet, Set<String> fnStack,
				Set<Long> injectedIds,
				Map<String, Map<String, List<Hop>>> functionTransTableCache,
				double computeWeight, double networkWeight, List<Pair<Long, Double>> parentLoopStack,
				List<LoopAnalysisContext> loopCtxStack,
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

			List<Set<String>> writtenBeforeIf = snapshotWritten(loopCtxStack);

				rewireHopDAG(isb.getPredicateHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, parentLoopStack, loopCtxStack, oracleFacade);

			newFormerTransTable.putAll(innerTransTable);
			Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
			elseFormerTransTable.putAll(innerTransTable);
			computeWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;
			networkWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;

			for (StatementBlock innerIsb : istmt.getIfBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, parentLoopStack, loopCtxStack, oracleFacade));

			List<Set<String>> writtenAfterIf = snapshotWritten(loopCtxStack);
			restoreWritten(loopCtxStack, writtenBeforeIf);

			for (StatementBlock innerIsb : istmt.getElseBody())
					elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, elseFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, parentLoopStack, loopCtxStack, oracleFacade));

			List<Set<String>> writtenAfterElse = snapshotWritten(loopCtxStack);
			restoreWritten(loopCtxStack, writtenBeforeIf);
			if (writtenAfterIf == null)
				writtenAfterIf = writtenBeforeIf;
			restoreWritten(loopCtxStack, writtenAfterIf);
			retainWritten(loopCtxStack, writtenAfterElse);

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
			double loopWeight = RewireConstants.DEFAULT_LOOP_WEIGHT;
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
			double iter1Factor = Math.max(loopWeight - 1.0, 0.0);
			double outerWeight = networkWeight;
			LoopAnalysisContext loopCtx = null;
			if (iter1Factor > 0.0 && loopCtxStack != null) {
				loopCtx = new LoopAnalysisContext(true, true, false);
				loopCtxStack.add(loopCtx);
			}
			computeWeight *= loopWeight;
			networkWeight *= loopWeight;

			// Create current loop context (copy parent context)
			List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
			currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(fsb.getFromHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, currentLoopStack, loopCtxStack, oracleFacade);
				rewireHopDAG(fsb.getToHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList, null,
						innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, currentLoopStack, loopCtxStack, oracleFacade);

			if (fsb.getIncrementHops() != null) {
					rewireHopDAG(fsb.getIncrementHops(), prog, visitedHops, rewireTable, graph,
							newOuterTransTableList, null, innerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, currentLoopStack, loopCtxStack, oracleFacade);
			}
			newFormerTransTable.putAll(innerTransTable);

			for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, currentLoopStack, loopCtxStack, oracleFacade));

			if (loopCtx != null && loopCtxStack != null && !loopCtxStack.isEmpty()) {
				Set<String> loopCarriedVars = computeLoopCarriedVars(loopCtx, newFormerTransTable);
				double loopCarryWeight = outerWeight * iter1Factor;
				addLoopCarryEdges(loopCarriedVars, newFormerTransTable, loopCtx, graph, loopCarryWeight,
						unRefTwriteSet);
				adjustLoopInvariantReadWeights(loopCtx, graph, outerWeight);
				loopCtxStack.remove(loopCtxStack.size() - 1);
			}

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap,
						injectedIds);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			double loopWeight = RewireConstants.estimateWhileLoopWeight(wsb);
			double iter1Factor = Math.max(loopWeight - 1.0, 0.0);
			double outerWeight = networkWeight;
			LoopAnalysisContext loopCtx = null;
			if (iter1Factor > 0.0 && loopCtxStack != null) {
				loopCtx = new LoopAnalysisContext(true, true, false);
				loopCtxStack.add(loopCtx);
			}
			computeWeight *= loopWeight;
			networkWeight *= loopWeight;

			// Create current loop context (copy parent context)
			List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
			currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(wsb.getPredicateHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, currentLoopStack, loopCtxStack, oracleFacade);
			newFormerTransTable.putAll(innerTransTable);

			for (StatementBlock innerWsb : wstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, currentLoopStack, loopCtxStack, oracleFacade));

			if (loopCtx != null && loopCtxStack != null && !loopCtxStack.isEmpty()) {
				Set<String> loopCarriedVars = computeLoopCarriedVars(loopCtx, newFormerTransTable);
				double loopCarryWeight = outerWeight * iter1Factor;
				addLoopCarryEdges(loopCarriedVars, newFormerTransTable, loopCtx, graph, loopCarryWeight,
						unRefTwriteSet);
				adjustLoopInvariantReadWeights(loopCtx, graph, outerWeight);
				loopCtxStack.remove(loopCtxStack.size() - 1);
			}

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap,
						injectedIds);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, parentLoopStack, loopCtxStack, oracleFacade));

				// Wire fcall operation to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap,
						injectedIds);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
						rewireHopDAG(c, prog, visitedHops, rewireTable, graph, newOuterTransTableList, null,
								innerTransTable,
								privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet,
								fnStack, injectedIds, functionTransTableCache,
								computeWeight, networkWeight, parentLoopStack, loopCtxStack, oracleFacade);
			}

			return innerTransTable;
		}
		return newFormerTransTable;
	}

	private static List<Set<String>> snapshotWritten(List<LoopAnalysisContext> loopCtxStack) {
		if (loopCtxStack == null || loopCtxStack.isEmpty()) {
			return null;
		}
		List<Set<String>> snapshots = new ArrayList<>(loopCtxStack.size());
		for (LoopAnalysisContext ctx : loopCtxStack) {
			snapshots.add(ctx.snapshotWritten());
		}
		return snapshots;
	}

	private static void restoreWritten(List<LoopAnalysisContext> loopCtxStack, List<Set<String>> snapshots) {
		if (loopCtxStack == null || loopCtxStack.isEmpty() || snapshots == null) {
			return;
		}
		int limit = Math.min(loopCtxStack.size(), snapshots.size());
		for (int i = 0; i < limit; i++) {
			loopCtxStack.get(i).restoreWritten(snapshots.get(i));
		}
	}

	private static void retainWritten(List<LoopAnalysisContext> loopCtxStack, List<Set<String>> snapshots) {
		if (loopCtxStack == null || loopCtxStack.isEmpty() || snapshots == null) {
			return;
		}
		int limit = Math.min(loopCtxStack.size(), snapshots.size());
		for (int i = 0; i < limit; i++) {
			loopCtxStack.get(i).retainWritten(snapshots.get(i));
		}
	}

	private static void markWritten(List<LoopAnalysisContext> loopCtxStack, String var) {
		if (loopCtxStack == null || loopCtxStack.isEmpty()) {
			return;
		}
		for (LoopAnalysisContext ctx : loopCtxStack) {
			ctx.markWritten(var);
		}
	}

	private static void recordReadFromOutside(List<LoopAnalysisContext> loopCtxStack, String var, Hop treadHop,
			boolean fromOutside) {
		if (loopCtxStack == null || loopCtxStack.isEmpty()) {
			return;
		}
		for (LoopAnalysisContext ctx : loopCtxStack) {
			if (ctx.hasWritten(var)) {
				continue;
			}
			if (fromOutside) {
				ctx.markReadFromOutside(var);
			}
			ctx.recordHeaderRead(var, treadHop);
		}
	}

	private static Set<String> computeLoopCarriedVars(LoopAnalysisContext ctx,
			Map<String, List<Hop>> endTransTable) {
		Set<String> loopCarried = new HashSet<>();
		if (ctx == null || endTransTable == null || endTransTable.isEmpty())
			return loopCarried;
		for (Map.Entry<String, Boolean> entry : ctx.getReadFromOutside().entrySet()) {
			if (!Boolean.TRUE.equals(entry.getValue()))
				continue;
			List<Hop> writes = endTransTable.get(entry.getKey());
			if (writes != null && !writes.isEmpty())
				loopCarried.add(entry.getKey());
		}
		return loopCarried;
	}

	private static void addLoopCarryEdges(Set<String> loopCarriedVars, Map<String, List<Hop>> endTransTable,
			LoopAnalysisContext loopCtx, FederatedPlanMinSTGraph graph, double loopCarryWeight,
			Set<Long> unRefTwriteSet) {
		if (loopCarriedVars == null || loopCarriedVars.isEmpty() || loopCtx == null || endTransTable == null) {
			return;
		}
		if (loopCarryWeight <= 0.0) {
			return;
		}
		Map<String, List<Hop>> headerReads = loopCtx.getHeaderReads();
		for (String var : loopCarriedVars) {
			Hop endWriter = selectLastHop(endTransTable.get(var));
			Hop frontReader = selectFirstHop(headerReads.get(var));
			if (endWriter == null || frontReader == null) {
				continue;
			}
			graph.addLoopCarryEdge(endWriter.getHopID(), frontReader.getHopID(), loopCarryWeight);
			if (unRefTwriteSet != null) {
				unRefTwriteSet.remove(endWriter.getHopID());
			}
		}
	}

	private static void adjustLoopInvariantReadWeights(LoopAnalysisContext loopCtx,
			FederatedPlanMinSTGraph graph, double outerWeight) {
		if (loopCtx == null || graph == null || outerWeight <= 0.0) {
			return;
		}
		Map<String, List<Hop>> headerReads = loopCtx.getHeaderReads();
		if (headerReads == null || headerReads.isEmpty()) {
			return;
		}
		for (Map.Entry<String, List<Hop>> entry : headerReads.entrySet()) {
			String var = entry.getKey();
			if (loopCtx.hasWritten(var)) {
				continue;
			}
			List<Hop> reads = entry.getValue();
			if (reads == null || reads.isEmpty()) {
				continue;
			}
			for (Hop readHop : reads) {
				if (readHop == null) {
					continue;
				}
				if (!(readHop instanceof DataOp)) {
					continue;
				}
				Types.OpOpData op = ((DataOp) readHop).getOp();
				if (op != Types.OpOpData.TRANSIENTREAD && op != Types.OpOpData.FEDERATED) {
					continue;
				}
				Vertex vertex = graph.getVertex(readHop.getHopID());
				if (vertex == null) {
					continue;
				}
				vertex.setMetadata(vertex.getOpWeight(), outerWeight, vertex.getLoopContext());
			}
		}
	}

	private static Hop selectFirstHop(List<Hop> hops) {
		if (hops == null || hops.isEmpty()) {
			return null;
		}
		for (Hop hop : hops) {
			if (hop != null) {
				return hop;
			}
		}
		return null;
	}

	private static Hop selectLastHop(List<Hop> hops) {
		if (hops == null || hops.isEmpty()) {
			return null;
		}
		for (int i = hops.size() - 1; i >= 0; i--) {
			Hop hop = hops.get(i);
			if (hop != null) {
				return hop;
			}
		}
		return null;
	}

	private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops,
			Map<Long, List<Hop>> rewireTable,
			FederatedPlanMinSTGraph graph, List<Map<String, List<Hop>>> outerTransTableList,
			Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
			Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet,
			Set<String> fnStack, Set<Long> injectedIds, Map<String, Map<String, List<Hop>>> functionTransTableCache,
			double computeWeight, double networkWeight, List<Pair<Long, Double>> loopStack,
			List<LoopAnalysisContext> loopCtxStack,
			OracleFacade oracleFacade) {

		LoopAnalysisContext activeLoopCtx = (loopCtxStack != null && !loopCtxStack.isEmpty())
				? loopCtxStack.get(loopCtxStack.size() - 1)
				: null;
		boolean includeTransReadChildren = activeLoopCtx == null || activeLoopCtx.includeTransReadChildren();
		RewireDagWalker.Context ctx = new RewireDagWalker.Context(
				visitedHops, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
				includeTransReadChildren);
		RewireDagWalker.walk(hop, ctx, new RewireDagWalker.Visitor() {
			@Override
			public void afterChildren(Hop hop, RewireDagWalker.Context ctx) {
				// Identify hops to connect to the root dummy node
				// Connect TWrite pred and u(print) to the root dummy node
				if (HopUtils.isPredTWrite(hop) || HopUtils.isPrintOrPWrite(hop)) {
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
						FunctionStatementBlock fsb = (prog != null)
								? prog.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName())
								: null;
						Map<String, List<Hop>> functionTransTable = functionTransTableCache.get(fkey);
						boolean pushed = false;

						if (functionTransTable == null && !fnStack.contains(fkey)) {
							fnStack.add(fkey);
							pushed = true;
							try {
								if (prog == null) {
									FederatedPlannerLogger.logWarnMessage(
											"[FederatedMinSTRewire] Skipping nested function " + fkey
													+ " because DMLProgram is unavailable in dynamic rewiring");
								} else if (fsb == null) {
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
									TransTableRewireUtils.mapFunctionInputsToFormerTransTable(
											inputArgs, inputHops, rewireTable, newFormerTransTable);

										functionTransTable = rewireStatementBlock(fsb, prog, visitedHops,
												rewireTable, graph, outerTransTableList, newFormerTransTable,
												privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet,
												progRootHopSet, fnStack, injectedIds, functionTransTableCache,
												computeWeight, networkWeight, loopStack, loopCtxStack, oracleFacade);
									if (functionTransTable != null)
										functionTransTableCache.put(fkey, functionTransTable);
								}
							} finally {
								if (pushed) {
									fnStack.remove(fkey);
								}
							}
						}

							TransTableRewireUtils.mapFunctionOutputs(
									fop, fsb, functionTransTable, innerTransTable,
									outputHop -> {
										if (outputHop == null)
											return;
										unRefTwriteSet.add(outputHop.getHopID());
										if (!graph.contains(outputHop.getHopID())) {
											Vertex outputVertex = rewireHop(outputHop, rewireTable, outerTransTableList,
													formerTransTable, innerTransTable, privacyConstraintMap, graph,
													fTypeMap, fedMap, unRefTwriteSet, injectedIds, loopCtxStack, oracleFacade);
											if (outputVertex != null) {
												outputVertex.setMetadata(computeWeight, networkWeight, loopStack);
												graph.addVertex(outputVertex);
												visitedHops.add(outputHop.getHopID());
											}
										}
									});
						} else if (fop.getFunctionType() == FunctionType.MULTIRETURN_BUILTIN) {
							TransTableRewireUtils.mapFunctionOutputs(
									fop, null, null, innerTransTable,
									outputHop -> {
										if (outputHop == null)
											return;
										unRefTwriteSet.add(outputHop.getHopID());
										if (!graph.contains(outputHop.getHopID())) {
											Vertex outputVertex = rewireHop(outputHop, rewireTable, outerTransTableList,
													formerTransTable, innerTransTable, privacyConstraintMap, graph,
													fTypeMap, fedMap, unRefTwriteSet, injectedIds, loopCtxStack, oracleFacade);
											if (outputVertex != null) {
												outputVertex.setMetadata(computeWeight, networkWeight, loopStack);
												graph.addVertex(outputVertex);
												visitedHops.add(outputHop.getHopID());
											}
										}
									});
						}
					}

				double hopComputeWeight = computeWeight;
				double hopNetworkWeight = networkWeight;
				List<Pair<Long, Double>> hopLoopStack = loopStack;

				Vertex passThroughVertex = null;
				Hop sourceHop = TransTableRewireUtils.resolvePassThroughSourceHop(hop, ctx.rewireTable());
				if (sourceHop != null && sourceHop != hop) {
					passThroughVertex = graph.getVertex(sourceHop.getHopID());
				}
				if (passThroughVertex == null && TransTableRewireUtils.isPassThroughTWrite(hop)) {
					List<Hop> inputs = hop.getInput();
					if (inputs != null && !inputs.isEmpty()) {
						passThroughVertex = graph.getVertex(inputs.get(0).getHopID());
					}
				}
				if (passThroughVertex != null) {
					hopComputeWeight = passThroughVertex.getOpWeight();
					hopNetworkWeight = passThroughVertex.getNetworkWeight();
					hopLoopStack = passThroughVertex.getLoopContext();
				}

					Vertex vertex = rewireHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
							privacyConstraintMap,
							graph, fTypeMap, fedMap, unRefTwriteSet, injectedIds, loopCtxStack, oracleFacade);
				if (vertex != null) {
					vertex.setMetadata(hopComputeWeight, hopNetworkWeight, hopLoopStack);
					graph.addVertex(vertex);
				}
			}
		});
	}

		private static Vertex rewireHop(Hop hop, Map<Long, List<Hop>> rewireTable,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap,
				FederatedPlanMinSTGraph graph, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet,
				Set<Long> injectedIds, List<LoopAnalysisContext> loopCtxStack, OracleFacade oracleFacade) {

		Privacy privacy;
		FType fType = null;
		ExecPlacementCaps caps;

			if (hop instanceof DataOp) {
				DataOp dataOp = (DataOp) hop;
				Types.OpOpData opType = dataOp.getOp();
				String hopName = dataOp.getName();

				if (opType == Types.OpOpData.FEDERATED) {
					// 2) FEDERATED DataOp: preserve partition-derived FType (DP parity).
					// Do not route fedinit through Oracle decision, which can override ROW/COL to
					// BROADCAST and trigger repeated FED_FOUT uploads in iterative workloads.
					privacy = FederatedPlannerUtils.getFedWorkerMetaData(fedMap, dataOp);
					fType = FederatedTypePropagator.deriveFType(dataOp);
					FederatedPlannerUtils.registerFedInitVar(hopName, fType,
						FederatedPlannerUtils.deriveFedInitSignature(dataOp));
					FederatedPlannerLogger.logDataOpFTypeDebug(
							hop, fType, "FEDERATED", "Derived from partition ranges");
					caps = buildExecPlacementCaps(hop, privacy, fType, null, fTypeMap);
					privacyConstraintMap.put(hop.getHopID(), privacy);
					fTypeMap.put(hop.getHopID(), fType);
					return new Vertex(hop, privacy, fType, caps);
				} else if (opType == Types.OpOpData.TRANSIENTWRITE) {
				if ("__pred".equals(hopName)) {
					// Align with DP: skip transient rewire for __pred.
					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
				} else {
					// 3) TWrite: 입력 Hop의 FType을 그대로 복사
					innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
					unRefTwriteSet.add(hop.getHopID());
					markWritten(loopCtxStack, hopName);
					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
					fType = fTypeMap.get(hop.getInput(0).getHopID());
					FederatedPlannerLogger.logDataOpFTypeDebug(
							hop, fType, "TRANSIENTWRITE",
							"Propagated from single input (HopID: " + hop.getInput(0).getHopID() + ")");
				}
				} else if (opType == Types.OpOpData.TRANSIENTREAD) {
					// 4) TRead: mapped source hops로부터 privacy/FType/caps 전파
					List<Hop> childHops = TransTableRewireUtils.resolveTransReadChildren(
							hop.getHopID(), hopName, rewireTable,
							innerTransTable, formerTransTable, outerTransTableList);

					boolean hasInner = false;
					if (innerTransTable != null) {
						List<Hop> innerHops = innerTransTable.get(hopName);
						hasInner = innerHops != null && !innerHops.isEmpty();
					}
					boolean hasFormer = false;
					if (formerTransTable != null) {
						List<Hop> formerHops = formerTransTable.get(hopName);
						hasFormer = formerHops != null && !formerHops.isEmpty();
					}
					boolean fromOutside = !hasInner && !hasFormer && childHops != null && !childHops.isEmpty();
					recordReadFromOutside(loopCtxStack, hopName, hop, fromOutside);

					if (childHops == null || childHops.isEmpty()) {
						FederatedPlannerLogger.logTransReadRewireDebug(
								hopName, hop.getHopID(), childHops, true, "RewireTransHop");
						privacy = Privacy.PUBLIC;
						caps = buildExecPlacementCaps(hop, privacy, null, null, fTypeMap);
						privacyConstraintMap.put(hop.getHopID(), privacy);
						fTypeMap.put(hop.getHopID(), null);
						return new Vertex(hop, privacy, null, caps);
					}

					List<Hop> filteredChildHops = TransTableRewireUtils.filterTransReadChildren(
							hopName, childHops, injectedIds, true, false);

					FederatedPlannerLogger.logRewireHierarchy(
							hop, childHops, filteredChildHops, "RewireTransHop");

					if (filteredChildHops.isEmpty()) {
						rewireTable.remove(hop.getHopID());
						FederatedPlannerLogger.logFilteredChildHopsDebug(
								hopName, hop.getHopID(), filteredChildHops, true, "RewireTransHop");
						privacy = Privacy.PUBLIC;
						caps = buildExecPlacementCaps(hop, privacy, null, null, fTypeMap);
						privacyConstraintMap.put(hop.getHopID(), privacy);
						fTypeMap.put(hop.getHopID(), null);
						return new Vertex(hop, privacy, null, caps);
					}

					TransTableRewireUtils.registerTransReadMapping(hop.getHopID(), filteredChildHops, rewireTable);
					TransTableRewireUtils.registerTransWriteLinks(
							hop, filteredChildHops, rewireTable, unRefTwriteSet);

					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, filteredChildHops, privacyConstraintMap);

					FType resolvedFType = null;
					boolean fTypeMismatch = false;
					ExecPlacementCaps resolvedCaps = null;
					Long transientWriteHopId = null;
					boolean hasFedInitChild = false;
					boolean hasLocalSource = false;
					for (Hop childHop : filteredChildHops) {
						if (childHop == null) {
							continue;
						}
						if (childHop instanceof DataOp
								&& ((DataOp) childHop).getOp() == Types.OpOpData.FEDERATED) {
							hasFedInitChild = true;
						}
						// Propagate FType/caps from all mapped sources (not only TW).
						// This is critical for function arguments where the mapped source can be a FEDERATED
						// init DataOp (or another FED output) without an intermediate TransientWrite.
						FType childFType = fTypeMap.get(childHop.getHopID());
						if (childFType != null) {
							if (resolvedFType == null && !fTypeMismatch) {
								resolvedFType = childFType;
							} else if (resolvedFType != childFType) {
								resolvedFType = null;
								fTypeMismatch = true;
							}
						}
						Vertex childVertex = graph.getVertex(childHop.getHopID());
						if (childVertex != null && childVertex.getCaps() != null) {
							ExecPlacementCaps childCaps = childVertex.getCaps();
							if (childCaps.allowCP_LOUT || childCaps.allowFED_LOUT)
								hasLocalSource = true;
							if (resolvedCaps == null) {
								resolvedCaps = new ExecPlacementCaps(childCaps);
							} else {
								resolvedCaps.allowCP_LOUT &= childCaps.allowCP_LOUT;
								resolvedCaps.allowCP_FOUT &= childCaps.allowCP_FOUT;
								resolvedCaps.allowFED_LOUT &= childCaps.allowFED_LOUT;
								resolvedCaps.allowFED_FOUT &= childCaps.allowFED_FOUT;
							}
						}
						if (transientWriteHopId == null
								&& childHop instanceof DataOp
								&& ((DataOp) childHop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
							transientWriteHopId = childHop.getHopID();
						}
					}
					resolvedCaps = applyTransientPlacementRestrictions(hop, resolvedCaps);
					if (resolvedCaps != null && !resolvedCaps.hasAny()) {
						// If sources disagree on legal combinations, fall back to policy caps.
						resolvedCaps = null;
					}
					// If we could not resolve a consistent FType from multiple TW sources,
					// infer a CP->FOUT (or local->FED forwarding) type from consumers to avoid
					// passing null into the Oracle (which can lead to "NOT_FEDERATED_INPUTS"
					// and illegal privacy/exec combinations).
					if (resolvedFType == null && hop.getDataType() != null && hop.getDataType().isMatrix()) {
						FType inferred = OracleUtils.inferFallbackFType(hop, Collections.emptyList(), oracleFacade, rewireTable);
						if (FederatedPlannerUtils.isScalarLikeMatrix(hop)) {
							inferred = FType.BROADCAST;
						}
						if (inferred == null) {
							FType axis = FederatedPlannerUtils.getVectorAxis(hop);
							inferred = (axis != null) ? axis : FType.ROW;
						}
						resolvedFType = inferred;
					}
					fType = resolvedFType;
					if (resolvedCaps != null) {
						caps = resolvedCaps;
					} else {
						OpCaps policyCaps = OpCaps.allow(ExecType.FED, FederatedOutput.FOUT).build();
						caps = buildExecPlacementCaps(hop, privacy, fType, policyCaps, fTypeMap);
					}
					if (hasFedInitChild && !hasLocalSource && caps != null) {
						// A TRead that is directly backed by a FED init should not assume a local value
						// without an explicit download/copy path.
						caps.allowCP_LOUT = false;
					}

					privacyConstraintMap.put(hop.getHopID(), privacy);
					fTypeMap.put(hop.getHopID(), fType);

					Vertex v = new Vertex(hop, privacy, fType, caps);
					v.setTransientWriteHopId(transientWriteHopId);
					return v;
				} else {
					// 5) 기타 DataOp (PREAD, PWRITE 등): privacy만, FType은 Oracle에 맡김
					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
				}
		} else {
			privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
		}

			// ==== 여기서부터는 모든 Hop(비 DataOp + DataOp 공통) 처리 ====

			// Build Oracle-only input FTypes. Keep them separate from node logical FTypes stored in fTypeMap.
			List<Hop> collectedHops = hop.getInput() == null ? Collections.emptyList() : hop.getInput();
			List<FType> oracleInputFTypes = new ArrayList<>();
			List<Hop> collectedHopList = new ArrayList<>();
			List<Integer> collectedInputIndices = new ArrayList<>();
			boolean hasGuaranteedFoutInput = false;
			boolean hasNonConcreteLocalMatrixInput = false;
			boolean hasSubtractiveLocalMatrixInput = false;
			boolean hasFederatedHintInput = false;
			for (int inputIndex = 0; inputIndex < collectedHops.size(); inputIndex++) {
				Hop input = collectedHops.get(inputIndex);
				if (input == null)
					continue;
				collectedHopList.add(input);
				collectedInputIndices.add(inputIndex);

				// For local-capable inputs, expose a FED hint to Oracle only when both:
				// 1) a concrete FOUT path exists (native FOUT or refed), and
				// 2) the parent actually demands a federated input at this edge.
				FType oracleInputFType = fTypeMap.get(input.getHopID());
				Vertex inputVertex = graph.getVertex(input.getHopID());
				ExecPlacementCaps inputCaps = inputVertex != null ? inputVertex.getCaps() : null;

				if (inputVertex != null) {
					boolean inputGuaranteedFout = inputCaps != null
						&& !inputCaps.allowCP_LOUT
						&& !inputCaps.allowFED_LOUT;
					hasGuaranteedFoutInput = hasGuaranteedFoutInput || inputGuaranteedFout;

					boolean inputMayBeLocal = inputCaps != null
						&& (inputCaps.allowCP_LOUT || inputCaps.allowFED_LOUT);
					boolean inputHasConcreteFoutPath = inputCaps != null
						&& (inputCaps.allowCP_FOUT || inputCaps.allowFED_FOUT);
						boolean vectorParent = FederatedPlannerUtils.isVectorShape(hop);
						boolean requiresFederatedInput = requiresFederatedInputForParent(hop, input, inputIndex, fTypeMap);
						boolean shouldAllowRequiredRefedInference =
							requiresFederatedInput
							&& !vectorParent
							&& (hop instanceof AggBinaryOp)
							&& (input instanceof IndexingOp);
						boolean inputNativeFedFout = inputCaps != null
							&& inputCaps.allowFED_FOUT
							&& inputCaps.fedFoutMode == ExecPlacementCaps.FedFoutMode.NATIVE;
						boolean inputCanRefed = false;
					// Important: when child caps are already known and expose no FOUT path,
					// do not resurrect a FED hint via canGenerateCpfoutCandidate(). That
					// candidate check is anchor-based and can ignore runtime FOUT constraints
					// (e.g., FOUT_NOT_SUPPORTED ops like REXPAND).
					// Exception: for non-vector parents that require a federated input on this edge,
					// we must still test concrete CP->FOUT feasibility for AggBinary+Indexing paths;
					// otherwise required edges can get stuck in CP-only and force broad local plans
					// (e.g., PCA right-index chains).
					boolean allowRefedInference = (inputCaps == null)
						|| inputHasConcreteFoutPath
						|| shouldAllowRequiredRefedInference;
						if (!inputGuaranteedFout && inputMayBeLocal && allowRefedInference) {
							try {
								inputCanRefed = FederatedRefedPolicy.canGenerateCpfoutCandidate(input, fTypeMap);
							}
							catch (DMLRuntimeException ex) {
								inputCanRefed = false;
							}
						}
						// For vector-like parents, be conservative on derived/refed inference, but always
						// treat native FED/FOUT support as a concrete materialization path.
						// For non-vector parents, explicit child caps exposing a concrete FOUT path are safe.
						boolean hasConcreteFoutPath = inputGuaranteedFout || inputCanRefed;
						if (!vectorParent)
							hasConcreteFoutPath = hasConcreteFoutPath || inputHasConcreteFoutPath;
						boolean hasParentUploadHint = graph.hasParentChildUploadHint(hop.getHopID(), input.getHopID());
						// Keep Oracle FED-hint gating strict: only hard input requirements should force
						// federated demand. Parent upload hints are soft cost signals and must not push
						// optional local-capable edges into FED execution candidates.
						boolean hasFederatedDemand = requiresFederatedInput;
						if (oracleInputFType == null && inputNativeFedFout && hasFederatedDemand) {
							oracleInputFType = inputVertex.getDataType();
							if (oracleInputFType == null)
								oracleInputFType = inputVertex.getCpFoutDataType();
						}
						boolean transientReadWithoutFedInit = false;
						boolean localCapableTransientRead = false;
						if (input instanceof DataOp
								&& ((DataOp) input).getOp() == Types.OpOpData.TRANSIENTREAD) {
							String transientVar = ((DataOp) input).getName();
							transientReadWithoutFedInit = !FederatedPlannerUtils.isFedInitVar(transientVar);
							// DP parity: local-capable transient reads without FED-init provenance should
							// not be pre-hinted as FED inputs in MinST rewire.
							localCapableTransientRead = transientReadWithoutFedInit && inputMayBeLocal;
						}
						if (inputMayBeLocal
								&& (localCapableTransientRead
									|| (!inputGuaranteedFout
										&& (!hasConcreteFoutPath || !hasFederatedDemand)))) {
							oracleInputFType = null;
						}
						if (transientReadWithoutFedInit)
							oracleInputFType = null;
						if (oracleInputFType == null) {
							boolean shouldInferHint = !transientReadWithoutFedInit
								&& (!inputMayBeLocal
								|| inputGuaranteedFout
								|| (hasFederatedDemand && hasConcreteFoutPath));
							if (shouldInferHint) {
								oracleInputFType = inferFoutInputFType(input, fTypeMap, oracleFacade, rewireTable);
							}
						}
					if (input.getDataType() != null && input.getDataType().isMatrix()) {
						if (oracleInputFType != null)
							hasFederatedHintInput = true;
						if (inputMayBeLocal && !inputGuaranteedFout && !hasConcreteFoutPath) {
							hasNonConcreteLocalMatrixInput = true;
							if (isSubtractiveMatrixBinary(input))
								hasSubtractiveLocalMatrixInput = true;
						}
					}
				}
				else if (input.getDataType() != null && input.getDataType().isMatrix()
						&& oracleInputFType != null) {
					hasFederatedHintInput = true;
				}
				oracleInputFTypes.add(oracleInputFType);
			}
			// For protected aggregate matmul: if any matrix input is local-only without a concrete
			// materialization path, avoid mixed FED/local hints that lead to repeated per-iteration refed.
			// Force Oracle re-evaluation with local inputs to keep this hop on CP unless all matrix inputs
			// are concretely federatable.
			if (hop instanceof AggBinaryOp
				&& privacy == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC
				&& hasNonConcreteLocalMatrixInput
				&& ((hop.getParent() != null && hop.getParent().size() > 2)
					|| hasSubtractiveLocalMatrixInput)
				&& hasFederatedHintInput) {
				for (int i = 0; i < collectedHopList.size() && i < oracleInputFTypes.size(); i++) {
					Hop input = collectedHopList.get(i);
					if (input != null && input.getDataType() != null && input.getDataType().isMatrix())
						oracleInputFTypes.set(i, null);
				}
			}

			OracleUtils.OracleDecision oracleDecision = OracleUtils.decideWithOracle(
				hop, privacy, collectedHopList, oracleInputFTypes,
				oracleFacade, null, rewireTable);
			OpCaps opCaps = oracleDecision.caps();

			// Oracle foutFType을 FType으로 반영 (getFederatedType 대체)
			FType oracleFType = oracleDecision.logicalFType();
			if (oracleFType != null) {
				// FEDERATED DataOp는 partition 기반 FType과 충돌할 수 있으니, 필요하면 로깅
				if (fType != null && !fType.equals(oracleFType)) {
					FederatedPlannerLogger.logInfoMessage(
						"[MinST] Oracle foutFType " + oracleFType + " overrides existing FType "
							+ fType + " for hop " + hop.getHopID() + " (" + hop.getOpString() + ")");
				}
				fType = oracleFType;
			}
			int numWorkersEstimate = FederatedWorkerUtils.countDistinctWorkers(fedMap);
			if (!hasGuaranteedFoutInput
					&& FederatedPlannerUtils.isVectorShape(hop)
					&& !(hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED)) {
				boolean canRefed = false;
				try {
					canRefed = FederatedRefedPolicy.canGenerateCpfoutCandidate(hop, fTypeMap);
				}
				catch (DMLRuntimeException ex) {
					canRefed = false;
				}
				// Only override vector FType for CP->FOUT planning when refed is actually feasible.
				// If refed is not feasible, keep Oracle/axis-derived type and leave optional inputs local.
				if (canRefed) {
					FType axisType = FederatedPlannerUtils.getVectorAxis(hop);
					boolean axisSafe = axisType != null
						&& OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(
							hop, axisType, rewireTable, numWorkersEstimate) == axisType;
					fType = axisSafe ? axisType : FType.BROADCAST;
				}
			}

			FType cpFoutType = OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(
				hop, fType, rewireTable, numWorkersEstimate);

				// Exec/Placement capability 결정
				caps = buildExecPlacementCaps(hop, privacy, fType, opCaps, fTypeMap);
				// If Oracle input hints are mixed (some FED hints, some local/null), a single Oracle
				// decision can over-constrain this hop to FED-only or CP-only even though both are
				// legal under different materialization choices. Keep planning candidates complete by
				// evaluating a local-only alternative and merging CP placements.
				ExecPlacementCaps localAlternativeCaps = deriveLocalAlternativeCaps(
					hop, privacy, collectedHopList, oracleInputFTypes, oracleFacade, rewireTable, fTypeMap);
				if (localAlternativeCaps != null) {
					caps.allowCP_LOUT |= localAlternativeCaps.allowCP_LOUT;
					caps.allowCP_FOUT |= localAlternativeCaps.allowCP_FOUT;
				}
				ExecPlacementCaps federatedAlternativeCaps = deriveFederatedAlternativeCaps(
					hop, privacy, collectedHopList, oracleInputFTypes, oracleFacade, rewireTable, fTypeMap);
				if (federatedAlternativeCaps != null) {
					caps.allowFED_LOUT |= federatedAlternativeCaps.allowFED_LOUT;
					caps.allowFED_FOUT |= federatedAlternativeCaps.allowFED_FOUT;
					if (caps.fedFoutMode == ExecPlacementCaps.FedFoutMode.DISABLED
							&& federatedAlternativeCaps.allowFED_FOUT) {
						caps.fedFoutMode = federatedAlternativeCaps.fedFoutMode;
					}
				}
				Map<Long, FType> oracleAlignedInputFTypeMap =
					buildOracleAlignedInputFTypeMap(fTypeMap, collectedHopList, oracleInputFTypes);
				boolean fedInputsSatisfied = FederatedRefedPolicy
					.canSatisfyFederatedInputsFromFTypes(hop, oracleAlignedInputFTypeMap);
				if (!fedInputsSatisfied && shouldEnableFederatedAlternativeFallback(
						hop, federatedAlternativeCaps, cpFoutType, numWorkersEstimate)) {
					fedInputsSatisfied = true;
				}
				if (!fedInputsSatisfied) {
					caps.allowFED_LOUT = false;
					caps.allowFED_FOUT = false;
				if (!caps.hasAny()) {
					List<FType> relaxedOracleInputFTypes = relaxUnsatisfiedRequiredInputsToLocal(
						hop, collectedHopList, collectedInputIndices, oracleInputFTypes, oracleAlignedInputFTypeMap);
					if (relaxedOracleInputFTypes != null) {
						OracleUtils.OracleDecision relaxedOracleDecision = OracleUtils.decideWithOracle(
							hop, privacy, collectedHopList, relaxedOracleInputFTypes,
							oracleFacade, null, rewireTable);
						OpCaps relaxedOpCaps = relaxedOracleDecision.caps();
						FType relaxedOracleFType = relaxedOracleDecision.logicalFType();
						FType relaxedFType = (relaxedOracleFType != null) ? relaxedOracleFType : fType;
						if (!hasGuaranteedFoutInput
								&& FederatedPlannerUtils.isVectorShape(hop)
								&& !(hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED)) {
							boolean canRefed = false;
							try {
								canRefed = FederatedRefedPolicy.canGenerateCpfoutCandidate(hop, fTypeMap);
							}
							catch (DMLRuntimeException ex) {
								canRefed = false;
							}
							if (canRefed) {
								FType axisType = FederatedPlannerUtils.getVectorAxis(hop);
								boolean axisSafe = axisType != null
									&& OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(
										hop, axisType, rewireTable, numWorkersEstimate) == axisType;
								relaxedFType = axisSafe ? axisType : FType.BROADCAST;
							}
						}
						FType relaxedCpFoutType = OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(
							hop, relaxedFType, rewireTable, numWorkersEstimate);
						ExecPlacementCaps relaxedCaps = buildExecPlacementCaps(
							hop, privacy, relaxedFType, relaxedOpCaps, fTypeMap);
						Map<Long, FType> relaxedAlignedInputFTypeMap =
							buildOracleAlignedInputFTypeMap(fTypeMap, collectedHopList, relaxedOracleInputFTypes);
						boolean relaxedFedInputsSatisfied = FederatedRefedPolicy
							.canSatisfyFederatedInputsFromFTypes(hop, relaxedAlignedInputFTypeMap);
						if (!relaxedFedInputsSatisfied) {
							relaxedCaps.allowFED_LOUT = false;
							relaxedCaps.allowFED_FOUT = false;
						}
						// Last-resort local fallback for this hop: if FED feasibility is broken and
						// Oracle-aligned hints still leave no legal combination, clear Oracle caps and
						// keep only CP placements for this hop.
						if (!relaxedCaps.hasAny()) {
							ExecPlacementCaps relaxedLocalCaps = buildLocalOnlyCaps(
								hop, privacy, relaxedFType, fTypeMap);
							if (relaxedLocalCaps != null) {
								relaxedCaps = relaxedLocalCaps;
								relaxedFedInputsSatisfied = false;
							}
						}
						if (relaxedCaps.hasAny()) {
							oracleInputFTypes = relaxedOracleInputFTypes;
							opCaps = relaxedOpCaps;
							oracleFType = relaxedOracleFType;
							fType = relaxedFType;
							cpFoutType = relaxedCpFoutType;
							caps = relaxedCaps;
							fedInputsSatisfied = relaxedFedInputsSatisfied;
						}
					}
				}
					// If only OPTIONAL inputs are unsatisfied, required-input relaxation may not fire.
					// In that case still allow a local-only fallback for this hop instead of aborting.
					if (!caps.hasAny()) {
						ExecPlacementCaps localCaps = buildLocalOnlyCaps(hop, privacy, fType, fTypeMap);
						if (localCaps != null) {
							caps = localCaps;
							opCaps = null;
							fedInputsSatisfied = false;
						}
					}
					if (!caps.hasAny()) {
						throw new DMLRuntimeException("No legal Exec/Placement combination for hop "
							+ hop.getHopID() + " (" + hop.getOpString() + ")");
					}
				}

				traceRewireDecision(hop, privacy, collectedHopList, oracleInputFTypes, opCaps,
					oracleFType, fType, cpFoutType, fedInputsSatisfied, caps, fTypeMap);

			// 최종 privacy/FType 저장
			privacyConstraintMap.put(hop.getHopID(), privacy);
			fTypeMap.put(hop.getHopID(), fType);

		return new Vertex(hop, privacy, fType, cpFoutType, caps);
	}

	private static boolean shouldEnableFederatedAlternativeFallback(Hop hop,
			ExecPlacementCaps federatedAlternativeCaps, FType cpFoutType, int numWorkersEstimate) {
		// Keep indexing chains on strict feasibility: planner-only FED fallback here tends to
		// create unnecessary CP->FOUT/rightIndex materialization paths.
		if (hop instanceof IndexingOp)
			return false;
		if (federatedAlternativeCaps == null)
			return false;
		if (!(federatedAlternativeCaps.allowFED_LOUT || federatedAlternativeCaps.allowFED_FOUT))
			return false;
		FType penaltyType = (cpFoutType != null) ? cpFoutType : FType.BROADCAST;
		double forwardingPenalty = FederatedCostModel.computeLocalToFedForwardingPenalty(
				penaltyType, Math.max(1, numWorkersEstimate));
		return forwardingPenalty <= FED_ALT_FALLBACK_MAX_CTRL_PENALTY_MS;
	}

	private static void traceRewireDecision(Hop hop, Privacy privacy, List<Hop> inputHops,
			List<FType> alignedInputFTypes, OpCaps opCaps, FType oracleFType, FType finalFType,
			FType cpFoutType, boolean fedInputsSatisfied, ExecPlacementCaps caps,
			Map<Long, FType> plannedFTypeMap) {
		if (!FederatedPlannerTrace.shouldTrace(hop))
			return;

		String reason = (opCaps != null && opCaps.reason() != null) ? opCaps.reason().name() : "null";
		String detail = (opCaps != null && opCaps.detail().isPresent()) ? opCaps.detail().get() : "";
		String notes = formatOracleNotes(opCaps);
		String inputs = formatInputTrace(inputHops, alignedInputFTypes, plannedFTypeMap);
		FederatedPlannerTrace.log(hop, "MinST-Rewire", String.format(
				"privacy=%s oracleReason=%s oracleDetail=%s notes=%s inputs=%s oracleFType=%s finalFType=%s cpFoutType=%s fedInputsSatisfied=%s caps=[CP_LOUT=%s,CP_FOUT=%s,FED_LOUT=%s,FED_FOUT=%s,mode=%s]",
				privacy, reason, detail, notes, inputs, oracleFType, finalFType, cpFoutType,
				fedInputsSatisfied,
				caps.allowCP_LOUT, caps.allowCP_FOUT, caps.allowFED_LOUT, caps.allowFED_FOUT,
				caps.fedFoutMode));
	}

	private static String formatInputTrace(List<Hop> inputHops, List<FType> alignedInputFTypes,
			Map<Long, FType> plannedFTypeMap) {
		if (inputHops == null || inputHops.isEmpty())
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < inputHops.size(); i++) {
			Hop input = inputHops.get(i);
			if (input == null)
				continue;
			if (sb.length() > 1)
				sb.append("; ");
			FType aligned = (alignedInputFTypes != null && i < alignedInputFTypes.size())
					? alignedInputFTypes.get(i) : null;
			FType planned = (plannedFTypeMap != null) ? plannedFTypeMap.get(input.getHopID()) : null;
			sb.append(input.getHopID()).append(":").append(input.getOpString())
					.append("{aligned=").append(aligned)
					.append(",planned=").append(planned).append("}");
		}
		sb.append("]");
		return sb.toString();
	}

	private static String formatOracleNotes(OpCaps opCaps) {
		if (opCaps == null || opCaps.notes() == null || opCaps.notes().isEmpty())
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < opCaps.notes().size(); i++) {
			OpCaps.DecisionNote note = opCaps.notes().get(i);
			if (i > 0)
				sb.append("; ");
			sb.append(note.code());
			if (note.message() != null && !note.message().isEmpty())
				sb.append(":").append(note.message());
		}
		sb.append("]");
		return sb.toString();
	}

	private static Map<Long, FType> buildOracleAlignedInputFTypeMap(Map<Long, FType> baseFTypeMap,
			List<Hop> inputHops, List<FType> alignedInputFTypes) {
		Map<Long, FType> alignedMap = new HashMap<>();
		if (baseFTypeMap != null)
			alignedMap.putAll(baseFTypeMap);
		if (inputHops == null || inputHops.isEmpty())
			return alignedMap;
		for (int i = 0; i < inputHops.size(); i++) {
			Hop input = inputHops.get(i);
			if (input == null)
				continue;
			FType alignedInputFType = (alignedInputFTypes != null && i < alignedInputFTypes.size())
				? alignedInputFTypes.get(i) : null;
			if (alignedInputFType == null)
				alignedMap.remove(input.getHopID());
			else
				alignedMap.put(input.getHopID(), alignedInputFType);
		}
		return alignedMap;
	}

	private static List<FType> relaxUnsatisfiedRequiredInputsToLocal(Hop parentHop,
			List<Hop> inputHops, List<Integer> originalInputIndices, List<FType> oracleInputFTypes,
			Map<Long, FType> oracleAlignedInputFTypeMap) {
		if (parentHop == null || inputHops == null || oracleInputFTypes == null
				|| inputHops.size() != oracleInputFTypes.size())
			return null;
		List<FType> relaxed = new ArrayList<>(oracleInputFTypes);
		boolean needsReeval = false;
		for (int i = 0; i < inputHops.size(); i++) {
			Hop input = inputHops.get(i);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			int inputIndex = (originalInputIndices != null && i < originalInputIndices.size())
				? originalInputIndices.get(i)
				: i;
			FederatedRefedPolicy.InputRequirement requirement = FederatedRefedPolicy.getInputRequirementForFedExec(
				parentHop, input, inputIndex, oracleAlignedInputFTypeMap);
			if (requirement == FederatedRefedPolicy.InputRequirement.OPTIONAL)
				continue;

			boolean plannedFed = oracleAlignedInputFTypeMap != null
				&& oracleAlignedInputFTypeMap.containsKey(input.getHopID());
			if (plannedFed)
				continue;

			boolean hasConcreteFoutPath = false;
			try {
				// Keep fallback re-evaluation on concrete CP->FOUT feasibility only.
				hasConcreteFoutPath = FederatedRefedPolicy.canGenerateCpfoutCandidate(
					input, oracleAlignedInputFTypeMap);
			}
			catch (DMLRuntimeException ex) {
				hasConcreteFoutPath = false;
			}
			if (!hasConcreteFoutPath) {
				needsReeval = true;
				relaxed.set(i, null);
			}
		}
		return needsReeval ? relaxed : null;
	}

	private static boolean requiresFederatedInputForParent(Hop parentHop, Hop inputHop, int inputIndex,
				Map<Long, FType> inputFTypes) {
		if (parentHop == null || inputHop == null)
			return true;
		if (inputHop.getDataType() == null || !inputHop.getDataType().isMatrix())
			return false;
		if (inputIndex < 0)
			return true;
		FederatedRefedPolicy.InputRequirement requirement = FederatedRefedPolicy.getInputRequirementForFedExec(
				parentHop, inputHop, inputIndex, inputFTypes);
		return requirement != FederatedRefedPolicy.InputRequirement.OPTIONAL;
	}

	private static boolean isSubtractiveMatrixBinary(Hop hop) {
		if (!(hop instanceof BinaryOp))
			return false;
		if (hop.getDataType() == null || !hop.getDataType().isMatrix())
			return false;
		Types.OpOp2 op = ((BinaryOp) hop).getOp();
		return op == Types.OpOp2.MINUS || op == Types.OpOp2.MINUS1_MULT;
	}

	private static ExecPlacementCaps deriveLocalAlternativeCaps(Hop hop, Privacy privacy,
			List<Hop> inputHops, List<FType> oracleInputFTypes,
			OracleFacade oracleFacade, Map<Long, List<Hop>> rewireTable,
			Map<Long, FType> fTypeMap) {
		if (hop == null || inputHops == null || inputHops.isEmpty()
				|| oracleInputFTypes == null || oracleInputFTypes.size() != inputHops.size())
			return null;
		boolean hasFedHint = false;
		boolean hasNullHint = false;
		for (int i = 0; i < inputHops.size(); i++) {
			Hop input = inputHops.get(i);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			FType hint = oracleInputFTypes.get(i);
			if (hint == null)
				hasNullHint = true;
			else
				hasFedHint = true;
		}
		// If any matrix input is hinted as FED, also evaluate local-only hints so
		// Oracle-driven FED selections do not suppress legal CP candidates.
		if (!hasFedHint)
			return null;

		List<FType> localOnlyInputHints = new ArrayList<>(oracleInputFTypes.size());
		for (int i = 0; i < inputHops.size(); i++) {
			Hop input = inputHops.get(i);
			if (input != null && input.getDataType() != null && input.getDataType().isMatrix())
				localOnlyInputHints.add(null);
			else
				localOnlyInputHints.add(oracleInputFTypes.get(i));
		}
		try {
			OracleUtils.OracleDecision localOracleDecision = OracleUtils.decideWithOracle(
				hop, privacy, inputHops, localOnlyInputHints, oracleFacade, null, rewireTable);
			OpCaps localOpCaps = localOracleDecision.caps();
			FType localFType = localOracleDecision.logicalFType();
			if (localFType == null && fTypeMap != null)
				localFType = fTypeMap.get(hop.getHopID());
			return buildExecPlacementCaps(hop, privacy, localFType, localOpCaps, fTypeMap);
		}
		catch (DMLRuntimeException ex) {
			return null;
		}
	}

	private static ExecPlacementCaps deriveFederatedAlternativeCaps(Hop hop, Privacy privacy,
			List<Hop> inputHops, List<FType> oracleInputFTypes,
			OracleFacade oracleFacade, Map<Long, List<Hop>> rewireTable,
			Map<Long, FType> fTypeMap) {
		if (hop == null || inputHops == null || inputHops.isEmpty()
				|| oracleInputFTypes == null || oracleInputFTypes.size() != inputHops.size()
				|| fTypeMap == null || fTypeMap.isEmpty())
			return null;

		ExecPlacementCaps mergedFedCaps = null;
		for (int i = 0; i < inputHops.size(); i++) {
			Hop input = inputHops.get(i);
			if (input == null || input.getDataType() == null || !input.getDataType().isMatrix())
				continue;
			if (oracleInputFTypes.get(i) != null)
				continue;

			FType plannedInputFType = fTypeMap.get(input.getHopID());
			if (plannedInputFType == null)
				continue;

			List<FType> promotedInputHints = new ArrayList<>(oracleInputFTypes);
			promotedInputHints.set(i, plannedInputFType);
			try {
				OracleUtils.OracleDecision promotedDecision = OracleUtils.decideWithOracle(
					hop, privacy, inputHops, promotedInputHints, oracleFacade, null, rewireTable);
				OpCaps promotedOpCaps = promotedDecision.caps();
				FType promotedFType = promotedDecision.logicalFType();
				if (promotedFType == null)
					promotedFType = fTypeMap.get(hop.getHopID());
				ExecPlacementCaps promotedCaps = buildExecPlacementCaps(
					hop, privacy, promotedFType, promotedOpCaps, fTypeMap);
				Map<Long, FType> promotedAlignedMap =
					buildOracleAlignedInputFTypeMap(fTypeMap, inputHops, promotedInputHints);
				boolean promotedFedSatisfied = FederatedRefedPolicy
					.canSatisfyFederatedInputsFromFTypes(hop, promotedAlignedMap);
				if (!promotedFedSatisfied)
					continue;
				if (!promotedCaps.allowFED_LOUT && !promotedCaps.allowFED_FOUT)
					continue;

				if (mergedFedCaps == null) {
					mergedFedCaps = new ExecPlacementCaps();
					mergedFedCaps.allowCP_LOUT = false;
					mergedFedCaps.allowCP_FOUT = false;
					mergedFedCaps.allowFED_LOUT = false;
					mergedFedCaps.allowFED_FOUT = false;
					mergedFedCaps.fedFoutMode = ExecPlacementCaps.FedFoutMode.DISABLED;
				}
				mergedFedCaps.allowFED_LOUT |= promotedCaps.allowFED_LOUT;
				mergedFedCaps.allowFED_FOUT |= promotedCaps.allowFED_FOUT;
				if (mergedFedCaps.fedFoutMode == ExecPlacementCaps.FedFoutMode.DISABLED
						&& promotedCaps.allowFED_FOUT) {
					mergedFedCaps.fedFoutMode = promotedCaps.fedFoutMode;
				}
			}
			catch (DMLRuntimeException ex) {
				// Best-effort candidate expansion: ignore invalid promoted hints.
			}
		}
		return (mergedFedCaps != null && (mergedFedCaps.allowFED_LOUT || mergedFedCaps.allowFED_FOUT))
			? mergedFedCaps : null;
	}

	private static ExecPlacementCaps buildLocalOnlyCaps(Hop hop, Privacy privacy, FType fType,
			Map<Long, FType> fTypeMap) {
		ExecPlacementCaps localCaps = buildExecPlacementCaps(hop, privacy, fType, null, fTypeMap);
		localCaps.allowFED_LOUT = false;
		localCaps.allowFED_FOUT = false;
		return localCaps.hasAny() ? localCaps : null;
	}

	private static FType inferFoutInputFType(Hop hop, Map<Long, FType> fTypeMap,
			OracleFacade oracleFacade, Map<Long, List<Hop>> rewireTable) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return null;
		List<Hop> inputs = hop.getInput();
		List<FType> alignedInputFTypes = new ArrayList<>();
		if (inputs != null) {
			for (Hop in : inputs) {
				if (in == null)
					continue;
				alignedInputFTypes.add(fTypeMap.get(in.getHopID()));
			}
		}
		FType inferred = OracleUtils.inferFallbackFType(hop, alignedInputFTypes, oracleFacade, rewireTable);
		if (FederatedPlannerUtils.isScalarLikeMatrix(hop)) {
			inferred = FType.BROADCAST;
		}
		if (inferred == null) {
			FType axis = FederatedPlannerUtils.getVectorAxis(hop);
			inferred = (axis != null) ? axis : FType.ROW;
		}
		return inferred;
	}

	private static ExecPlacementCaps buildExecPlacementCaps(Hop hop, Privacy privacy, FType fType, OpCaps capsOracle,
			Map<Long, FType> fTypeMap) {
		ExecPlacementCaps caps = new ExecPlacementCaps();

		// 0) 처음엔 전부 false로 시작 (DP가 실제로 생성하는 조합만 켜기 위함)
		caps.allowCP_LOUT = false;
		caps.allowCP_FOUT = false;
		caps.allowFED_LOUT = false;
		caps.allowFED_FOUT = false;

		ExecPlacementPolicy.Decision policyDecision = ExecPlacementPolicy.decide(
				hop, privacy, fType, capsOracle);
		if (!policyDecision.hasAny()) {
			FederatedPlannerLogger.logWarnMessage(
					"[MinST] Empty exec/placement policy decision for hop " + hop.getHopID()
							+ " (" + hop.getOpString() + "), privacy=" + privacy
							+ ". Applying conservative fallback.");
			policyDecision = buildConservativePolicyDecision(privacy);
		}

		caps.allowCP_LOUT = policyDecision.allowCP_LOUT;
		caps.allowCP_FOUT = policyDecision.allowCP_FOUT;
		caps.allowFED_LOUT = policyDecision.allowFED_LOUT;
		caps.allowFED_FOUT = policyDecision.allowFED_FOUT;
		caps.fedFoutMode = caps.allowFED_FOUT
				? ExecPlacementCaps.FedFoutMode.NATIVE
				: ExecPlacementCaps.FedFoutMode.DISABLED;

		if (shouldEnableDerivedFedFout(hop, privacy, fTypeMap, caps)) {
			caps.allowFED_FOUT = true;
			caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.DERIVED_REFED;
		}

		// If the oracle reports that FOUT is not supported by the runtime for this op, avoid
		// producing a federated output altogether. MinST's 2-node encoding cannot reliably
		// express "CP->FOUT allowed but FED->FOUT forbidden" without risking illegal FED/FOUT
		// selections, so we force LOUT at the placement level.
		if (capsOracle != null && capsOracle.reason() == ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME) {
			caps.allowCP_FOUT = false;
			caps.allowFED_FOUT = false;
			// REXPAND with FED/LOUT + materialization can create cyclic anchor rewrites (Y <- fed_fout(rexpand(Y))).
			// Keep this op local when runtime has no native FOUT support.
			if (hop instanceof ParameterizedBuiltinOp
					&& ((ParameterizedBuiltinOp) hop).getOp() == Types.ParamBuiltinOp.REXPAND) {
				caps.allowFED_LOUT = false;
				caps.allowCP_LOUT = true;
			}
		}
		// MinST's 2-node encoding cannot safely encode cases where CP->FOUT is allowed but FED->FOUT is not.
		// If we keep CP->FOUT enabled, the min-cut can still choose (FED,FOUT) because placement/execution are
		// represented by independent nodes. Force LOUT by disabling CP->FOUT as well.
		if (caps.allowCP_FOUT && !caps.allowFED_FOUT) {
			caps.allowCP_FOUT = false;
		}

		if (isRecompileRegion(hop)) {
			caps.allowCP_FOUT = false;
		}

		caps = applyFunctionPlacementRestrictions(hop, caps);
		caps = applyTransientPlacementRestrictions(hop, caps);
		if (!caps.hasAny()) {
			throw new DMLRuntimeException("No legal Exec/Placement combination for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		}
		return caps;
	}

	private static ExecPlacementPolicy.Decision buildConservativePolicyDecision(Privacy privacy) {
		ExecPlacementPolicy.Decision fallback = new ExecPlacementPolicy.Decision();
		if (privacy == Privacy.PRIVATE) {
			// Keep strict privacy-safe fallback for PRIVATE results.
			fallback.allowFED_FOUT = true;
		}
		else {
			// For all other levels, keep execution local rather than failing rewrite.
			fallback.allowCP_LOUT = true;
		}
		return fallback;
	}

	private static ExecPlacementCaps applyTransientPlacementRestrictions(Hop hop, ExecPlacementCaps caps) {
		if (caps == null || !(hop instanceof DataOp)) {
			return caps;
		}
		Types.OpOpData op = ((DataOp) hop).getOp();
		if (op != Types.OpOpData.TRANSIENTREAD && op != Types.OpOpData.TRANSIENTWRITE) {
			return caps;
		}
		caps.allowCP_FOUT = false;
		caps.allowFED_LOUT = false;
		return caps;
	}

	private static ExecPlacementCaps applyFunctionPlacementRestrictions(Hop hop, ExecPlacementCaps caps) {
		if (caps == null || hop == null)
			return caps;

		// Runtime does not support federated instructions for multi-return builtins (e.g., eigen).
		// Keep only this hop local; adjacent hops still keep normal candidate wiring/inference.
		if (hop instanceof FunctionOp
				&& ((FunctionOp) hop).getFunctionType() == FunctionType.MULTIRETURN_BUILTIN) {
			caps.allowCP_LOUT = true;
			caps.allowCP_FOUT = false;
			caps.allowFED_LOUT = false;
			caps.allowFED_FOUT = false;
			caps.fedFoutMode = ExecPlacementCaps.FedFoutMode.DISABLED;
		}
		return caps;
	}

	private static boolean isRecompileRegion(Hop hop) {
		if (hop == null)
			return false;
		if (hop.requiresRecompile())
			return true;
		List<Hop> inputs = hop.getInput();
		if (inputs == null)
			return false;
		for (Hop in : inputs) {
			if (in != null && in.requiresRecompile())
				return true;
		}
		return false;
	}

	private static boolean shouldEnableDerivedFedFout(Hop hop, Privacy privacy,
			Map<Long, FType> fTypeMap, ExecPlacementCaps caps) {
		if (caps == null || caps.allowFED_FOUT || !caps.allowFED_LOUT)
			return false;
		if (hop == null || !hop.getDataType().isMatrix())
			return false;
		if (!isDerivedFoutPrivacyAllowed(privacy))
			return false;
		if (fTypeMap == null || !FederatedRefedPolicy.canGenerateCpfoutCandidateFromFTypes(hop, fTypeMap))
			return false;
		return true;
	}

	private static boolean isDerivedFoutPrivacyAllowed(Privacy privacy) {
		return privacy == Privacy.PUBLIC || privacy == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
	}
	

	private static void wireUnRefTwriteToLiveOutWithTracking(StatementBlock sb, Set<Long> unRefTwriteSet,
			FederatedPlanMinSTGraph graph, Map<String, List<Hop>> newFormerTransTable,
			Map<Long, FType> fTypeMap, Set<Long> injectedIds) {
		if (injectedIds == null) {
			wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
			return;
		}
		Set<Long> before = new HashSet<>(unRefTwriteSet);
		wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
		for (Long hopId : before) {
			if (!unRefTwriteSet.contains(hopId)) {
				injectedIds.add(hopId);
			}
		}
	}

	private static void wireUnRefTwriteToLiveOut(StatementBlock sb, Set<Long> unRefTwriteSet,
			FederatedPlanMinSTGraph graph,
			Map<String, List<Hop>> newFormerTransTable,
			Map<Long, FType> fTypeMap) {

		Function<Long, Hop> hopLookup = id -> {
			FederatedPlanMinSTGraph.Vertex v = graph.getVertex(id);
			return (v != null) ? v.getHopRef() : null;
		};

		FederatedPlannerUtils.wireUnRefTwriteToLiveOutCommon(
				sb,
				unRefTwriteSet,
				hopLookup,
				newFormerTransTable,
				// compatFn: unRefTwriteHop vs 대표 liveOutHop
					(unRefTwriteHop, liveOutHop) -> TransTableRewireUtils.calculateCompatibilityScore(
							unRefTwriteHop, liveOutHop, hopLookup),
					"[MinST]");
	}
}
