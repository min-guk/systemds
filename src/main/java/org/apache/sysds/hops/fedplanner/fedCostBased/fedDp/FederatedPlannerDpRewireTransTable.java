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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.HopsException;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
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
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.apache.sysds.runtime.DMLRuntimeException;

public class FederatedPlannerDpRewireTransTable {
	private static final int MAX_UNROLL_DEPTH = 1;

	public static class UnrollContext {
		private final Map<Long, Long> cloneToOrig = new HashMap<>();
		private final List<Hop> iter1Roots = new ArrayList<>();

		public Map<Long, Long> getCloneToOrig() {
			return cloneToOrig;
		}

		public List<Hop> getIter1Roots() {
			return iter1Roots;
		}

		private void addIter1Roots(List<Hop> roots) {
			if (roots == null || roots.isEmpty())
				return;
			iter1Roots.addAll(roots);
		}
	}

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
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, UnrollContext unrollCtx) {
		// Maps Hop ID and fedOutType pairs to their plan variants
		Set<Long> visitedHops = new HashSet<>();
		Set<String> fnStack = new HashSet<>();
		Set<Long> injectedIds = new HashSet<>();
		Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
		List<Pair<Long, Double>> loopStack = new ArrayList<>();

		List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);

		for (StatementBlock sb : prog.getStatementBlocks()) {
			Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
					hopCommonTable, outerTransTableList, null, privacyConstraintMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds,
					functionTransTableCache, 1, 1, 1, loopStack, 0, null, null, unrollCtx);
			outerTransTableList.get(0).putAll(innerTransTable);
		}
	}

	public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, UnrollContext unrollCtx) {
		Set<Long> visitedHops = new HashSet<>();
		Set<String> fnStack = new HashSet<>();
		Set<Long> injectedIds = new HashSet<>();
		Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
		List<Pair<Long, Double>> loopStack = new ArrayList<>();
		List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);
		// Todo (Future): not tested & not used
		rewireStatementBlock(function, prog, visitedHops, rewireTable, hopCommonTable, outerTransTableList, null,
				privacyConstraintMap,
				fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds,
				functionTransTableCache, 1, 1, 1, loopStack, 0, null, null, unrollCtx);
	}

	public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog,
			Set<Long> visitedHops,
			Map<Long, List<Hop>> rewireTable, Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
			Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet, Set<String> fnStack, Set<Long> injectedIds,
			Map<String, Map<String, List<Hop>>> functionTransTableCache,
			double computeWeight, double networkWeight, double multiplicity, List<Pair<Long, Double>> parentLoopStack,
			int unrollDepth, Map<Long, Hop> hopCloneMap, LoopAnalysisContext loopCtx,
			UnrollContext unrollCtx) {
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

			Set<String> writtenBeforeIf = null;
			if (loopCtx != null) {
				writtenBeforeIf = loopCtx.snapshotWritten();
			}

			rewireHopDAG(selectHop(isb.getPredicateHops(), hopCloneMap), prog, visitedHops, rewireTable,
					hopCommonTable,
					newOuterTransTableList,
					null, innerTransTable,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds,
					functionTransTableCache, computeWeight, networkWeight, multiplicity, parentLoopStack,
					unrollDepth, loopCtx, unrollCtx);

			newFormerTransTable.putAll(innerTransTable);
			Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
			elseFormerTransTable.putAll(innerTransTable);
			computeWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;
			networkWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;

			for (StatementBlock innerIsb : istmt.getIfBody())
				newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, parentLoopStack, unrollDepth, hopCloneMap, loopCtx,
						unrollCtx));

			Set<String> writtenAfterIf = null;
			if (loopCtx != null) {
				writtenAfterIf = loopCtx.snapshotWritten();
				loopCtx.restoreWritten(writtenBeforeIf);
			}

			for (StatementBlock innerIsb : istmt.getElseBody())
				elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, elseFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, parentLoopStack, unrollDepth, hopCloneMap, loopCtx,
						unrollCtx));

			if (loopCtx != null) {
				Set<String> writtenAfterElse = loopCtx.snapshotWritten();
				loopCtx.restoreWritten(writtenBeforeIf);
				if (writtenAfterIf == null)
					writtenAfterIf = writtenBeforeIf;
				loopCtx.restoreWritten(writtenAfterIf);
				loopCtx.retainWritten(writtenAfterElse);
			}

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
			boolean allowUnroll = unrollCtx != null && loopCtx == null
					&& unrollDepth < MAX_UNROLL_DEPTH && iter1Factor > 0.0;
			Set<String> loopCarriedVars = Collections.emptySet();

			if (allowUnroll) {
				LoopAnalysisContext probeCtx = new LoopAnalysisContext(true, false, false);
				Set<Long> probeVisited = new HashSet<>();
				Map<Long, List<Hop>> probeRewireTable = new HashMap<>();
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> probeHopCommon = new HashMap<>();
				Map<Long, Privacy> probePrivacy = new HashMap<>();
				Set<Long> probeUnRefTwriteSet = new HashSet<>();
				Set<Long> probeUnRefSet = new HashSet<>();
				Set<Hop> probeRootSet = new HashSet<>();
				Set<Long> probeInjectedIds = new HashSet<>();
				Map<String, Map<String, List<Hop>>> probeFnCache = new HashMap<>();
				Set<String> probeFnStack = new HashSet<>(fnStack);
				List<Pair<FederatedRange, FederatedData>> probeFedMap = new ArrayList<>(fedMap);

				Map<String, List<Hop>> probeInner = new HashMap<>();
				rewireHopDAG(selectHop(fsb.getFromHops(), hopCloneMap), prog, probeVisited, probeRewireTable,
						probeHopCommon, newOuterTransTableList, null, probeInner,
						probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet, probeFnStack,
						probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
						parentLoopStack, unrollDepth, probeCtx, null);
				rewireHopDAG(selectHop(fsb.getToHops(), hopCloneMap), prog, probeVisited, probeRewireTable,
						probeHopCommon, newOuterTransTableList, null, probeInner,
						probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet, probeFnStack,
						probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
						parentLoopStack, unrollDepth, probeCtx, null);
				if (fsb.getIncrementHops() != null) {
					rewireHopDAG(selectHop(fsb.getIncrementHops(), hopCloneMap), prog, probeVisited,
							probeRewireTable, probeHopCommon, newOuterTransTableList, null, probeInner,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet,
							probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
							parentLoopStack, unrollDepth, probeCtx, null);
				}
				Map<String, List<Hop>> probeFormer = new HashMap<>();
				probeFormer.putAll(probeInner);
				for (StatementBlock innerFsb : fstmt.getBody())
					probeFormer.putAll(rewireStatementBlock(innerFsb, prog, probeVisited, probeRewireTable,
							probeHopCommon, newOuterTransTableList, probeFormer,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet,
							probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight,
							networkWeight, multiplicity, parentLoopStack, MAX_UNROLL_DEPTH, hopCloneMap,
							probeCtx, null));
				loopCarriedVars = computeLoopCarriedVars(probeCtx, probeFormer);
				if (loopCarriedVars.isEmpty())
					allowUnroll = false;
			}

			if (!allowUnroll) {
				computeWeight *= loopWeight;
				networkWeight *= loopWeight;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(selectHop(fsb.getFromHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
						currentLoopStack, unrollDepth, loopCtx, unrollCtx);
				rewireHopDAG(selectHop(fsb.getToHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList,
						null,
						innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
						currentLoopStack, unrollDepth, loopCtx, unrollCtx);

				if (fsb.getIncrementHops() != null) {
					rewireHopDAG(selectHop(fsb.getIncrementHops(), hopCloneMap), prog, visitedHops, rewireTable,
							hopCommonTable,
							newOuterTransTableList, null, innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, currentLoopStack, unrollDepth, loopCtx, unrollCtx);
				}
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, currentLoopStack, unrollDepth, hopCloneMap, loopCtx,
							unrollCtx));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, newFormerTransTable,
						injectedIds);
				return newFormerTransTable;
			}

			List<Pair<Long, Double>> iter0LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter0LoopStack.addAll(parentLoopStack);
			iter0LoopStack.add(Pair.of(sb.getSBID(), 1.0));

			LoopAnalysisContext iter0Analysis = new LoopAnalysisContext(true, false, false);
			rewireHopDAG(selectHop(fsb.getFromHops(), hopCloneMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null, innerTransTable,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
					iter0LoopStack, unrollDepth, iter0Analysis, unrollCtx);
			rewireHopDAG(selectHop(fsb.getToHops(), hopCloneMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null,
					innerTransTable,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
					iter0LoopStack, unrollDepth, iter0Analysis, unrollCtx);
			if (fsb.getIncrementHops() != null) {
				rewireHopDAG(selectHop(fsb.getIncrementHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList, null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, iter0LoopStack, unrollDepth, iter0Analysis, unrollCtx);
			}
			newFormerTransTable.putAll(innerTransTable);
			for (StatementBlock innerFsb : fstmt.getBody())
				newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, iter0LoopStack, unrollDepth + 1, hopCloneMap,
						iter0Analysis, unrollCtx));

			Map<String, List<Hop>> iter0End = newFormerTransTable;
			loopCarriedVars = computeLoopCarriedVars(iter0Analysis, iter0End);
			double iter1Multiplicity = multiplicity * iter1Factor;
			if (loopCarriedVars.isEmpty() || iter1Multiplicity <= 0.0) {
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, iter0End, injectedIds);
				return iter0End;
			}

			List<Pair<Long, Double>> iter1LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter1LoopStack.addAll(parentLoopStack);
			iter1LoopStack.add(Pair.of(sb.getSBID(), iter1Factor));

			Map<Long, Hop> iter1HopMap = cloneStatementBlockHops(fsb, hopCloneMap, unrollCtx);
			unrollCtx.addIter1Roots(collectStatementBlockRoots(fsb, iter1HopMap));

			LoopAnalysisContext iter1Analysis = new LoopAnalysisContext(false, true, false);
			Map<String, List<Hop>> iter1Inner = new HashMap<>();
			Map<String, List<Hop>> iter1Former = new HashMap<>();

			rewireHopDAG(selectHop(fsb.getFromHops(), iter1HopMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null, iter1Inner,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, iter1Multiplicity,
					iter1LoopStack, unrollDepth, iter1Analysis, unrollCtx);
			rewireHopDAG(selectHop(fsb.getToHops(), iter1HopMap), prog, visitedHops, rewireTable,
					hopCommonTable, newOuterTransTableList,
					null,
					iter1Inner,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, iter1Multiplicity,
					iter1LoopStack, unrollDepth, iter1Analysis, unrollCtx);
			if (fsb.getIncrementHops() != null) {
				rewireHopDAG(selectHop(fsb.getIncrementHops(), iter1HopMap), prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList, null, iter1Inner,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, iter1Multiplicity, iter1LoopStack, unrollDepth, iter1Analysis, unrollCtx);
			}
			iter1Former.putAll(iter1Inner);
			for (StatementBlock innerFsb : fstmt.getBody())
				iter1Former.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, iter1Former,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, iter1Multiplicity, iter1LoopStack, unrollDepth + 1, iter1HopMap,
						iter1Analysis, unrollCtx));

			addCrossIterEdges(loopCarriedVars, iter0End, iter1Analysis, rewireTable, unRefTwriteSet);
			mergeIter0EndIntoIter1Former(iter1Former, iter0End);
			wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, iter1Former, injectedIds);
			return iter1Former;
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			double loopWeight = RewireConstants.estimateWhileLoopWeight(wsb);
			double iter1Factor = Math.max(loopWeight - 1.0, 0.0);
			boolean allowUnroll = unrollCtx != null && loopCtx == null
					&& unrollDepth < MAX_UNROLL_DEPTH && iter1Factor > 0.0;
			Set<String> loopCarriedVars = Collections.emptySet();

			if (allowUnroll) {
				LoopAnalysisContext probeCtx = new LoopAnalysisContext(true, false, false);
				Set<Long> probeVisited = new HashSet<>();
				Map<Long, List<Hop>> probeRewireTable = new HashMap<>();
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> probeHopCommon = new HashMap<>();
				Map<Long, Privacy> probePrivacy = new HashMap<>();
				Set<Long> probeUnRefTwriteSet = new HashSet<>();
				Set<Long> probeUnRefSet = new HashSet<>();
				Set<Hop> probeRootSet = new HashSet<>();
				Set<Long> probeInjectedIds = new HashSet<>();
				Map<String, Map<String, List<Hop>>> probeFnCache = new HashMap<>();
				Set<String> probeFnStack = new HashSet<>(fnStack);
				List<Pair<FederatedRange, FederatedData>> probeFedMap = new ArrayList<>(fedMap);

				Map<String, List<Hop>> probeInner = new HashMap<>();
				rewireHopDAG(selectHop(wsb.getPredicateHops(), hopCloneMap), prog, probeVisited, probeRewireTable,
						probeHopCommon, newOuterTransTableList, null, probeInner,
						probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet, probeFnStack,
						probeInjectedIds, probeFnCache, computeWeight, networkWeight, multiplicity,
						parentLoopStack, unrollDepth, probeCtx, null);
				Map<String, List<Hop>> probeFormer = new HashMap<>();
				probeFormer.putAll(probeInner);
				for (StatementBlock innerWsb : wstmt.getBody())
					probeFormer.putAll(rewireStatementBlock(innerWsb, prog, probeVisited, probeRewireTable,
							probeHopCommon, newOuterTransTableList, probeFormer,
							probePrivacy, probeFedMap, probeUnRefTwriteSet, probeUnRefSet, probeRootSet,
							probeFnStack,
							probeInjectedIds, probeFnCache, computeWeight,
							networkWeight, multiplicity, parentLoopStack, MAX_UNROLL_DEPTH, hopCloneMap,
							probeCtx, null));
				loopCarriedVars = computeLoopCarriedVars(probeCtx, probeFormer);
				if (loopCarriedVars.isEmpty())
					allowUnroll = false;
			}

			if (!allowUnroll) {
				computeWeight *= loopWeight;
				networkWeight *= loopWeight;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(selectHop(wsb.getPredicateHops(), hopCloneMap), prog, visitedHops, rewireTable,
						hopCommonTable,
						newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
						currentLoopStack, unrollDepth, loopCtx, unrollCtx);
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerWsb : wstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, multiplicity, currentLoopStack, unrollDepth, hopCloneMap, loopCtx,
							unrollCtx));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, hopCommonTable, newFormerTransTable,
						injectedIds);
				return newFormerTransTable;
			}

			List<Pair<Long, Double>> iter0LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter0LoopStack.addAll(parentLoopStack);
			iter0LoopStack.add(Pair.of(sb.getSBID(), 1.0));

			LoopAnalysisContext iter0Analysis = new LoopAnalysisContext(true, false, false);
			rewireHopDAG(selectHop(wsb.getPredicateHops(), hopCloneMap), prog, visitedHops, rewireTable,
					hopCommonTable,
					newOuterTransTableList,
					null, innerTransTable,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity,
					iter0LoopStack, unrollDepth, iter0Analysis, unrollCtx);
			newFormerTransTable.putAll(innerTransTable);

			for (StatementBlock innerWsb : wstmt.getBody())
				newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, iter0LoopStack, unrollDepth + 1, hopCloneMap,
						iter0Analysis, unrollCtx));

			Map<String, List<Hop>> iter0End = newFormerTransTable;
			loopCarriedVars = computeLoopCarriedVars(iter0Analysis, iter0End);
			double iter1Multiplicity = multiplicity * iter1Factor;
			if (loopCarriedVars.isEmpty() || iter1Multiplicity <= 0.0) {
				wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, hopCommonTable, iter0End, injectedIds);
				return iter0End;
			}

			List<Pair<Long, Double>> iter1LoopStack = new ArrayList<>();
			if (parentLoopStack != null)
				iter1LoopStack.addAll(parentLoopStack);
			iter1LoopStack.add(Pair.of(sb.getSBID(), iter1Factor));

			Map<Long, Hop> iter1HopMap = cloneStatementBlockHops(wsb, hopCloneMap, unrollCtx);
			unrollCtx.addIter1Roots(collectStatementBlockRoots(wsb, iter1HopMap));

			LoopAnalysisContext iter1Analysis = new LoopAnalysisContext(false, true, false);
			Map<String, List<Hop>> iter1Inner = new HashMap<>();
			Map<String, List<Hop>> iter1Former = new HashMap<>();

			rewireHopDAG(selectHop(wsb.getPredicateHops(), iter1HopMap), prog, visitedHops, rewireTable,
					hopCommonTable,
					newOuterTransTableList,
					null, iter1Inner,
					privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
					injectedIds, functionTransTableCache, computeWeight, networkWeight, iter1Multiplicity,
					iter1LoopStack, unrollDepth, iter1Analysis, unrollCtx);
			iter1Former.putAll(iter1Inner);

			for (StatementBlock innerWsb : wstmt.getBody())
				iter1Former.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, iter1Former,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, iter1Multiplicity, iter1LoopStack, unrollDepth + 1, iter1HopMap,
						iter1Analysis, unrollCtx));

			addCrossIterEdges(loopCarriedVars, iter0End, iter1Analysis, rewireTable, unRefTwriteSet);
			mergeIter0EndIntoIter1Former(iter1Former, iter0End);
			wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, hopCommonTable, iter1Former, injectedIds);
			return iter1Former;
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
						hopCommonTable, newOuterTransTableList, newFormerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, multiplicity, parentLoopStack, unrollDepth, hopCloneMap, loopCtx,
						unrollCtx));

			// Wire fcall operation to liveOutHops
			wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, hopCommonTable, newFormerTransTable,
					injectedIds);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					rewireHopDAG(selectHop(c, hopCloneMap), prog, visitedHops, rewireTable, hopCommonTable,
							newOuterTransTableList, formerTransTable,
							innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache,
							computeWeight, networkWeight, multiplicity, parentLoopStack, unrollDepth, loopCtx,
							unrollCtx);
			}

			return innerTransTable;
		}
		return newFormerTransTable;
	}

	private static Hop selectHop(Hop hop, Map<Long, Hop> hopCloneMap) {
		if (hop == null || hopCloneMap == null)
			return hop;
		Hop clone = hopCloneMap.get(hop.getHopID());
		return clone != null ? clone : hop;
	}

	private static List<Hop> collectStatementBlockRoots(StatementBlock sb, Map<Long, Hop> hopCloneMap) {
		List<Hop> roots = new ArrayList<>();
		collectStatementBlockRoots(sb, hopCloneMap, roots);
		return roots;
	}

	private static void collectStatementBlockRoots(StatementBlock sb, Map<Long, Hop> hopCloneMap,
			List<Hop> roots) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			roots.add(selectHop(isb.getPredicateHops(), hopCloneMap));
			for (StatementBlock inner : istmt.getIfBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
			for (StatementBlock inner : istmt.getElseBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			roots.add(selectHop(fsb.getFromHops(), hopCloneMap));
			roots.add(selectHop(fsb.getToHops(), hopCloneMap));
			if (fsb.getIncrementHops() != null)
				roots.add(selectHop(fsb.getIncrementHops(), hopCloneMap));
			for (StatementBlock inner : fstmt.getBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			roots.add(selectHop(wsb.getPredicateHops(), hopCloneMap));
			for (StatementBlock inner : wstmt.getBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				collectStatementBlockRoots(inner, hopCloneMap, roots);
		} else {
			if (sb.getHops() != null) {
				for (Hop hop : sb.getHops())
					roots.add(selectHop(hop, hopCloneMap));
			}
		}
	}

	private static Hop deepCopyHop(Hop hop, Map<Long, Hop> memo) {
		Hop cached = memo.get(hop.getHopID());
		if (cached != null)
			return cached;

		try {
			Hop copy = (Hop) hop.clone();
			copy.getInput().clear();
			copy.getParent().clear();
			memo.put(hop.getHopID(), copy);
			if (hop.getInput() != null) {
				for (Hop in : hop.getInput()) {
					Hop inCopy = deepCopyHop(in, memo);
					copy.getInput().add(inCopy);
					inCopy.getParent().add(copy);
				}
			}
			return copy;
		} catch (CloneNotSupportedException ex) {
			throw new HopsException(ex);
		}
	}

	private static Map<Long, Hop> cloneStatementBlockHops(StatementBlock sb, Map<Long, Hop> baseHopMap,
			UnrollContext unrollCtx) {
		Map<Long, Hop> memo = new HashMap<>();
		cloneStatementBlockHops(sb, baseHopMap, memo);
		if (unrollCtx != null) {
			for (Map.Entry<Long, Hop> entry : memo.entrySet()) {
				long baseId = entry.getKey();
				Hop clone = entry.getValue();
				long origId = resolveOriginalHopId(baseId, unrollCtx.cloneToOrig);
				unrollCtx.cloneToOrig.put(clone.getHopID(), origId);
			}
		}
		return memo;
	}

	private static void cloneStatementBlockHops(StatementBlock sb, Map<Long, Hop> baseHopMap,
			Map<Long, Hop> memo) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			deepCopyHop(selectHop(isb.getPredicateHops(), baseHopMap), memo);
			for (StatementBlock inner : istmt.getIfBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
			for (StatementBlock inner : istmt.getElseBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			deepCopyHop(selectHop(fsb.getFromHops(), baseHopMap), memo);
			deepCopyHop(selectHop(fsb.getToHops(), baseHopMap), memo);
			if (fsb.getIncrementHops() != null)
				deepCopyHop(selectHop(fsb.getIncrementHops(), baseHopMap), memo);
			for (StatementBlock inner : fstmt.getBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			deepCopyHop(selectHop(wsb.getPredicateHops(), baseHopMap), memo);
			for (StatementBlock inner : wstmt.getBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock inner : fstmt.getBody())
				cloneStatementBlockHops(inner, baseHopMap, memo);
		} else {
			if (sb.getHops() != null) {
				for (Hop hop : sb.getHops())
					deepCopyHop(selectHop(hop, baseHopMap), memo);
			}
		}
	}

	private static long resolveOriginalHopId(long baseHopId, Map<Long, Long> cloneToOrig) {
		Long origId = cloneToOrig.get(baseHopId);
		return origId != null ? origId : baseHopId;
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

	private static void addCrossIterEdges(Set<String> loopCarriedVars, Map<String, List<Hop>> iter0End,
			LoopAnalysisContext iter1Ctx, Map<Long, List<Hop>> rewireTable, Set<Long> unRefTwriteSet) {
		if (loopCarriedVars == null || loopCarriedVars.isEmpty() || iter1Ctx == null || iter0End == null)
			return;
		Map<String, List<Hop>> iter1Reads = iter1Ctx.getHeaderReads();
		for (String var : loopCarriedVars) {
			List<Hop> writes = iter0End.get(var);
			List<Hop> reads = iter1Reads.get(var);
			if (writes == null || writes.isEmpty() || reads == null || reads.isEmpty())
				continue;
			List<Hop> uniqueWrites = new ArrayList<>();
			for (Hop writeHop : writes) {
				if (writeHop != null && !uniqueWrites.contains(writeHop))
					uniqueWrites.add(writeHop);
			}
			if (uniqueWrites.isEmpty())
				continue;
			for (Hop readHop : reads) {
				if (readHop == null)
					continue;
				List<Hop> prevParents = rewireTable.get(readHop.getHopID());
				if (prevParents != null && !prevParents.isEmpty()) {
					for (Hop prevParent : prevParents) {
						if (prevParent == null)
							continue;
						List<Hop> siblings = rewireTable.get(prevParent.getHopID());
						if (siblings != null)
							siblings.removeIf(hop -> hop == readHop);
					}
				}
				rewireTable.put(readHop.getHopID(), new ArrayList<>(uniqueWrites));
				for (Hop writeHop : uniqueWrites) {
					List<Hop> children = rewireTable.computeIfAbsent(writeHop.getHopID(), k -> new ArrayList<>());
					if (!children.contains(readHop))
						children.add(readHop);
					unRefTwriteSet.remove(writeHop.getHopID());
				}
			}
		}
	}

	private static void mergeIter0EndIntoIter1Former(Map<String, List<Hop>> iter1Former,
			Map<String, List<Hop>> iter0End) {
		if (iter1Former == null || iter0End == null || iter0End.isEmpty())
			return;
		for (Map.Entry<String, List<Hop>> entry : iter0End.entrySet()) {
			List<Hop> existing = iter1Former.get(entry.getKey());
			if (existing == null || existing.isEmpty())
				iter1Former.put(entry.getKey(), entry.getValue());
		}
	}

	private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			List<Map<String, List<Hop>>> outerTransTableList,
			Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
			Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet,
			Set<String> fnStack, Set<Long> injectedIds,
			Map<String, Map<String, List<Hop>>> functionTransTableCache,
			double computeWeight, double networkWeight, double multiplicity, List<Pair<Long, Double>> loopStack,
			int unrollDepth, LoopAnalysisContext loopCtx, UnrollContext unrollCtx) {
		boolean includeTransReadChildren = loopCtx == null || loopCtx.includeTransReadChildren();
		RewireDagWalker.Context ctx = new RewireDagWalker.Context(
				visitedHops, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
				includeTransReadChildren);
		RewireDagWalker.walk(hop, ctx, new RewireDagWalker.Visitor() {
			@Override
			public void afterChildren(Hop hop, RewireDagWalker.Context ctx) {
				double hopComputeWeight = computeWeight;
				double hopNetworkWeight = networkWeight;
				double hopMultiplicity = multiplicity;
				List<Pair<Long, Double>> hopLoopStack = loopStack;

				FederatedPlannerDpMemoTable.HopCommon passThroughCommon = resolvePassThroughSourceCommon(hop,
						hopCommonTable, ctx.rewireTable());
				if (passThroughCommon == null) {
					passThroughCommon = resolvePassThroughInputCommon(hop, hopCommonTable);
				}
				if (passThroughCommon != null) {
					hopComputeWeight = passThroughCommon.getComputeWeight();
					hopNetworkWeight = passThroughCommon.getNetworkWeight();
					hopMultiplicity = passThroughCommon.getMultiplicity();
					hopLoopStack = passThroughCommon.getLoopContext();
				}

				hopCommonTable.put(hop.getHopID(),
						new FederatedPlannerDpMemoTable.HopCommon(hop, hopComputeWeight, hopNetworkWeight,
								hopMultiplicity, 0, hopLoopStack));
				FederatedPlannerLogger.logBasicHopInfo(hop, "RewireHopDAG:addCommon");

				// Identify hops to connect to the root dummy node
				// Connect TWrite pred and u(print) to the root dummy node
				if (HopUtils.isPredTWrite(hop) || HopUtils.isPrintOrPWrite(hop)) {
					progRootHopSet.add(hop);
				} else if (!(hop instanceof DataOp
						&& ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
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
											"[FederatedCost] Skipping nested function " + fkey
													+ " because DMLProgram is unavailable");
								} else if (fsb == null) {
									FederatedPlannerLogger.logWarnMessage(
											"[FederatedCost] Function " + fkey
													+ " not found in DMLProgram; skipping nested planning");
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
											rewireTable, hopCommonTable, outerTransTableList, newFormerTransTable,
											privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet,
											fnStack, injectedIds, functionTransTableCache, computeWeight,
											networkWeight,
											multiplicity, loopStack, unrollDepth, null, loopCtx, unrollCtx);
									if (functionTransTable != null) {
										functionTransTableCache.put(fkey, functionTransTable);
									}
								}
							} finally {
								if (pushed)
									fnStack.remove(fkey);
							}
						}

						TransTableRewireUtils.mapFunctionOutputs(
								fop, fsb, functionTransTable, innerTransTable,
								outputHop -> {
									if (!hopCommonTable.containsKey(outputHop.getHopID())) {
										hopCommonTable.put(outputHop.getHopID(),
												new FederatedPlannerDpMemoTable.HopCommon(outputHop, computeWeight,
														networkWeight,
														multiplicity, 0, loopStack));
									}
									unRefTwriteSet.add(outputHop.getHopID());
								});
					} else if (fop.getFunctionType() == FunctionType.MULTIRETURN_BUILTIN) {
						TransTableRewireUtils.mapFunctionOutputs(
								fop, null, null, innerTransTable,
								outputHop -> {
									if (!hopCommonTable.containsKey(outputHop.getHopID())) {
										hopCommonTable.put(outputHop.getHopID(),
												new FederatedPlannerDpMemoTable.HopCommon(outputHop, computeWeight,
														networkWeight,
														multiplicity, 0, loopStack));
									}
									unRefTwriteSet.add(outputHop.getHopID());
								});
					}
				}

				// Propagate Privacy Constraint
				if (!(hop instanceof DataOp) || hop.getName().equals("__pred")
						|| (((DataOp) hop).getOp() == Types.OpOpData.PERSISTENTWRITE)) {
					privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
							hop, hop.getInput(), privacyConstraintMap));
					return;
				}

				rewireTransHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
						privacyConstraintMap,
						fedMap, unRefTwriteSet, injectedIds, loopCtx);
			}
		});
	}

	private static void rewireTransHop(Hop hop, Map<Long, List<Hop>> rewireTable,
			List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
			Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> injectedIds,
			LoopAnalysisContext loopCtx) {
		DataOp dataOp = (DataOp) hop;
		Types.OpOpData opType = dataOp.getOp();
		String hopName = dataOp.getName();

		if (opType == Types.OpOpData.FEDERATED) {
			Privacy privacy = FederatedPlannerUtils.getFedWorkerMetaData(fedMap, dataOp);
			privacyConstraintMap.put(hop.getHopID(), privacy);
			FederatedPlannerLogger.logInfoMessage("[RewireTransHop] FED init detected: var="
					+ hopName + ", hopID=" + hop.getHopID() + ", privacy=" + privacy);
		} else if (opType == Types.OpOpData.TRANSIENTWRITE) {
			// Rewire TransWrite
			innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
			unRefTwriteSet.add(hop.getHopID());
			if (loopCtx != null)
				loopCtx.markWritten(hopName);
			// Propagate Privacy Constraint
			privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
					hop, hop.getInput(), privacyConstraintMap));
		} else if (opType == Types.OpOpData.TRANSIENTREAD) {
			// Rewire TransRead
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
			if (fromOutside && loopCtx != null && !loopCtx.hasWritten(hopName)) {
				loopCtx.markReadFromOutside(hopName);
				loopCtx.recordHeaderRead(hopName, hop);
			}

			// Todo: Handle exception when TRead has no Child (check why it's missing)
			if (childHops == null || childHops.isEmpty()) {
				FederatedPlannerLogger.logTransReadRewireDebug(hopName, hop.getHopID(), childHops, true,
						"RewireTransHop");
				privacyConstraintMap.put(hop.getHopID(), Privacy.PUBLIC);
				return;
			}

			List<Hop> filteredChildHops = TransTableRewireUtils.filterTransReadChildren(
					hopName, childHops, injectedIds, true, false);

			FederatedPlannerLogger.logRewireHierarchy(hop, childHops, filteredChildHops, "RewireTransHop");

			// Todo: Handle exception when TRead has no Filtered Child (check why it's
			// missing)
			if (filteredChildHops.isEmpty()) {
				rewireTable.remove(hop.getHopID());
				FederatedPlannerLogger.logFilteredChildHopsDebug(hopName, hop.getHopID(), filteredChildHops, true,
						"RewireTransHop");
				privacyConstraintMap.put(hop.getHopID(), Privacy.PUBLIC);
				return;
			}

			TransTableRewireUtils.registerTransReadMapping(hop.getHopID(), filteredChildHops, rewireTable);
			TransTableRewireUtils.registerTransWriteLinks(hop, filteredChildHops, rewireTable, unRefTwriteSet);
			// Propagate Privacy Constraint
			privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
					hop, filteredChildHops, privacyConstraintMap));
		} else {
			privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
					hop, hop.getInput(), privacyConstraintMap));
		}
	}

	private static FederatedPlannerDpMemoTable.HopCommon resolvePassThroughSourceCommon(Hop hop,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable) {
		Hop sourceHop = TransTableRewireUtils.resolvePassThroughSourceHop(hop, rewireTable);
		if (sourceHop == null || sourceHop == hop) {
			return null;
		}
		return hopCommonTable.get(sourceHop.getHopID());
	}

	private static FederatedPlannerDpMemoTable.HopCommon resolvePassThroughInputCommon(Hop hop,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
		if (!TransTableRewireUtils.isPassThroughTWrite(hop)) {
			return null;
		}
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty()) {
			return null;
		}
		return hopCommonTable.get(inputs.get(0).getHopID());
	}

	private static void wireUnRefTwriteToLiveOut(
			StatementBlock sb, Set<Long> unRefTwriteSet,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<String, List<Hop>> newFormerTransTable) {

		Function<Long, Hop> hopLookup = id -> {
			FederatedPlannerDpMemoTable.HopCommon hc = hopCommonTable.get(id);
			return (hc != null) ? hc.getHopRef() : null;
		};

		FederatedPlannerUtils.wireUnRefTwriteToLiveOutCommon(
				sb,
				unRefTwriteSet,
				hopLookup,
				newFormerTransTable,
				// compatFn: unRefTwriteHop vs 대표 liveOutHop
				(unRefTwriteHop, liveOutHop) -> TransTableRewireUtils.calculateCompatibilityScore(
						unRefTwriteHop, liveOutHop, hopLookup),
				"[DP]");
	}

	private static void wireUnRefTwriteToLiveOutWithTracking(
			StatementBlock sb, Set<Long> unRefTwriteSet,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<String, List<Hop>> newFormerTransTable, Set<Long> injectedIds) {
		if (injectedIds == null) {
			wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
			return;
		}
		Set<Long> before = new HashSet<>(unRefTwriteSet);
		wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
		for (Long hopId : before) {
			if (!unRefTwriteSet.contains(hopId)) {
				injectedIds.add(hopId);
			}
		}
	}

}
