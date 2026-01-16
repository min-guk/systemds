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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedDP;

import java.util.ArrayDeque;
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
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
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

/**
 * Baseline federated planner that compiles all hops
 * that support federated execution on federated inputs to
 * forced federated operations.
 */
public class FederatedPlannerFedCostBased extends AFederatedPlanner {
	private static final int MAX_ENUM_INPUTS = 20; // guard against 2^n blowups and shift overflow
	// Global privacy policy: never allow CP overrides for protected data unless
	// this flag flips.
	private static final boolean ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA = false;

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FederatedMemoTable.FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateProgram(prog, memoTable, true);

			Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited = new HashMap<>();
			Map<Long, Boolean> visitedFromClone = new HashMap<>();
			Set<Long> visitedPlanHops = new HashSet<>();
			Map<Long, FType> fTypeMap = new HashMap<>();

		List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = optimalPlan.getChildFedPlans();
		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : childFedPlanPairs) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
				rewriteHop(childPlan, memoTable, visited, visitedFromClone, visitedPlanHops, fTypeMap);
			}
			FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FederatedMemoTable.FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateFunctionDynamic(function,
				memoTable, true);
			Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited = new HashMap<>(); // hop ID, selected
																									// placement/exec
			Map<Long, Boolean> visitedFromClone = new HashMap<>();
			Set<Long> visitedPlanHops = new HashSet<>();
			Map<Long, FType> fTypeMap = new HashMap<>();

		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
			// Propagate the actual selected output type of the child plan (LOUT/FOUT)
				rewriteHop(childPlan, memoTable, visited, visitedFromClone, visitedPlanHops, fTypeMap);
			}
			FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
		}

		private void rewriteHop(FederatedMemoTable.FedPlan optimalPlan, FederatedMemoTable memoTable,
				Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited, Map<Long, Boolean> visitedFromClone,
				Set<Long> visitedPlanHops,
				Map<Long, FType> fTypeMap) {
			long planHopId = optimalPlan.getHopRef().getHopID();
			if (visitedPlanHops != null && !visitedPlanHops.add(planHopId))
				return;
			long hopID = memoTable.resolveOriginalHopId(planHopId);
			boolean fromClone = (hopID != planHopId);
			Hop targetHop = memoTable.resolveOriginalHop(planHopId);
			if (targetHop == null)
				targetHop = optimalPlan.getHopRef();
			ExecType execType = optimalPlan.getExecType();
			FEDInstruction.FederatedOutput thisOutType = optimalPlan.getFedOutType();

			if (execType == null) {
				throw new DMLRuntimeException("ExecType is null in FedPlan for hop " + hopID + " / "
						+ optimalPlan.getHopRef().getOpString());
			}

			Pair<FEDInstruction.FederatedOutput, ExecType> prev = visited.get(hopID);
			boolean prevFromClone = visitedFromClone != null && Boolean.TRUE.equals(visitedFromClone.get(hopID));
			ExecType resolvedExecType = execType;
			FEDInstruction.FederatedOutput resolvedOutType = thisOutType;

			if (prev != null) {
				if (prevFromClone && !fromClone) {
					resolvedExecType = prev.getRight();
					resolvedOutType = prev.getLeft();
				} else if (!prevFromClone && fromClone) {
					// Prefer clone decisions (Iter1) over originals (Iter0)
					resolvedExecType = execType;
					resolvedOutType = thisOutType;
				} else if (prev.getLeft() == thisOutType) {
					if (prev.getRight() != execType) {
						FederatedPlannerLogger.logWarnMessage(
								"[FederatedPlannerFedCostBased] ExecType conflict in rewriteHop for hop "
										+ hopID + " (" + optimalPlan.getHopRef().getOpString() + "): existing="
										+ prev.getRight() + ", incoming=" + execType + ", chosen="
										+ pickExecType(prev.getRight(), execType));
					}
					resolvedExecType = pickExecType(prev.getRight(), execType);
				} else {
					resolvedExecType = pickExecType(prev.getRight(), execType);
					FederatedPlannerLogger.logPlacementConflict(optimalPlan.getHopRef(), null,
							prev.getLeft(), thisOutType, "REWRITE_HOP");
				}
			} else {
				visited.put(hopID, Pair.of(thisOutType, execType));
			}

		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);

			// Todo: Remove later
			// DEBUG: Check if getFedPlanAfterPrune returns null
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}

				rewriteHop(childPlan, memoTable, visited, visitedFromClone, visitedPlanHops, fTypeMap);
			}

			optimalPlan.setForcedExecType(resolvedExecType);
			if (targetHop != optimalPlan.getHopRef())
				targetHop.setForcedExecType(resolvedExecType);

			// Keep resolvedOutType as-is; Iter1 preference already applied when clones exist.
			optimalPlan.setFederatedOutput(resolvedOutType);
			if (targetHop != optimalPlan.getHopRef())
				targetHop.setFederatedOutput(resolvedOutType);
			visited.put(hopID, Pair.of(resolvedOutType, resolvedExecType));
			if (visitedFromClone != null) {
				visitedFromClone.put(hopID, prevFromClone || fromClone);
			}
			if (resolvedOutType == FederatedOutput.FOUT) {
				FType fType = optimalPlan.getFType();
				if (fType != null) {
					fTypeMap.put(hopID, fType);
				}
			} else {
				fTypeMap.remove(hopID);
			}

		}

	private static ExecType pickExecType(ExecType existing, ExecType incoming) {
		if (existing == null) {
			return incoming;
		}
		if (incoming == null) {
			return existing;
		}
		if (existing == incoming) {
			return existing;
		}

		int existingPriority = execTypePriority(existing);
		int incomingPriority = execTypePriority(incoming);

		return incomingPriority < existingPriority ? incoming : existing;
	}

	private static int execTypePriority(ExecType execType) {
		switch (execType) {
			case FED:
				return 0;
			case CP:
				return 1;
			case CP_FILE:
				return 2;
			case GPU:
				return 3;
			case SPARK:
				return 4;
			case OOC:
				return 5;
			default:
				return Integer.MAX_VALUE;
		}
	}

	public static class FederatedPlanCostEnumerator {
		/**
		 * Enumerates the entire DML program to generate federated execution plans.
		 * It processes each statement block, computes the optimal federated plan,
		 * detects and resolves conflicts, and optionally prints the plan tree.
		 *
		 * @param prog    The DML program to enumerate.
		 * @param isPrint A boolean indicating whether to print the federated plan tree.
		 */
		public static FederatedMemoTable.FedPlan enumerateProgram(DMLProgram prog, FederatedMemoTable memoTable,
				boolean isPrint) {
			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			Map<Long, FederatedMemoTable.HopCommon> hopCommonTable = new HashMap<>();
			FederatedPlanRewireTransTable.UnrollContext unrollCtx = new FederatedPlanRewireTransTable.UnrollContext();

			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

			FederatedPlanRewireTransTable.rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
					unRefTwriteSet, unRefSet, progRootHopSet, unrollCtx);
			memoTable.registerHopRefs(hopCommonTable);
			memoTable.registerCloneMapping(unrollCtx.getCloneToOrig());

			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);
			int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);

			for (long hopID : unRefTwriteSet) {
				// Todo (Future): Need to check unRefTwriteSet connecting to progRoot.
				progRootHopSet.add(hopCommonTable.get(hopID).getHopRef());
			}
			Set<String> fnStack = new HashSet<>();
			Set<Long> visitedHops = new HashSet<>();

			for (StatementBlock sb : prog.getStatementBlocks()) {
				enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			}
			for (Hop iter1Root : unrollCtx.getIter1Roots()) {
				if (iter1Root == null)
					continue;
				enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			}

			FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

			// Todo : Fix & Update Conflict Resolve Plan
			// Detect conflicts in the federated plans where different FedPlans have
			// different FederatedOutput types
			double additionalTotalCost = detectAndResolveConflictFedPlan(memoTable, optimalPlan, numOfWorkers);

			unRefSet.addAll(unRefTwriteSet);
			// Print the federated plan tree if requested
			if (isPrint) {
				Set<Long> printableUnRefSet = new HashSet<>();
				for (long hopId : unRefSet) {
					if (memoTable.getFedPlanAfterPrune(hopId, FederatedOutput.LOUT) != null
							|| memoTable.getFedPlanAfterPrune(hopId, FederatedOutput.FOUT) != null) {
						printableUnRefSet.add(hopId);
					} else {
						FederatedPlannerLogger.logWarnMessage(
								"[Planner] Skipping unreferenced hop " + hopId
										+ " because no federated plan variants are available");
					}
				}
				FederatedPlannerLogger.printFedPlanTree(optimalPlan, printableUnRefSet, memoTable,
						additionalTotalCost);
			}

			return optimalPlan;
		}

		public static FederatedMemoTable.FedPlan enumerateFunctionDynamic(FunctionStatementBlock function,
				FederatedMemoTable memoTable,
				boolean isPrint) {
			Map<Long, List<Hop>> rewireTable = new HashMap<>();
			Set<Hop> progRootHopSet = new HashSet<>();
			Set<Long> unRefTwriteSet = new HashSet<>();
			Set<Long> unRefSet = new HashSet<>();
			Map<Long, FederatedMemoTable.HopCommon> hopCommonTable = new HashMap<>();
			FederatedPlanRewireTransTable.UnrollContext unrollCtx = new FederatedPlanRewireTransTable.UnrollContext();

			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

			DMLProgram prog = function.getDMLProg();
			FederatedPlanRewireTransTable.rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable,
					privacyConstraintMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, unrollCtx);
			memoTable.registerHopRefs(hopCommonTable);
			memoTable.registerCloneMapping(unrollCtx.getCloneToOrig());

			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);
			int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);

			Set<String> fnStack = new HashSet<>();
			Set<Long> visitedHops = new HashSet<>();
			enumerateStatementBlock(function, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			for (Hop iter1Root : unrollCtx.getIter1Roots()) {
				if (iter1Root == null)
					continue;
				enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			}

			FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

			// Detect conflicts in the federated plans where different FedPlans have
			// different FederatedOutput types
			// Todo : Fix & Update Conflict Resolve Plan
			double additionalTotalCost = detectAndResolveConflictFedPlan(memoTable, optimalPlan, numOfWorkers);

			// Print the federated plan tree if requested
			if (isPrint) {
				FederatedPlannerLogger.printFedPlanTree(optimalPlan, unRefTwriteSet, memoTable, additionalTotalCost);
			}

			return optimalPlan;
		}

		/**
		 * Enumerates the statement block and updates the transient and memoization
		 * tables.
		 * This method processes different types of statement blocks such as If, For,
		 * While, and Function blocks.
		 * It recursively enumerates the Hop DAGs within these blocks and updates the
		 * corresponding tables.
		 * The method also calculates weights recursively for if-else/loops and handles
		 * inner and outer block distinctions.
		 */
		public static void enumerateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedMemoTable memoTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
				Map<Long, Privacy> privacyConstraintMap, Set<Long> unRefTwriteSet, Set<String> fnStack,
				int numOfWorkers, Set<Long> visitedHops, OracleFacade oracleFacade) {
			if (sb instanceof IfStatementBlock) {
				IfStatementBlock isb = (IfStatementBlock) sb;
				IfStatement istmt = (IfStatement) isb.getStatement(0);

				enumerateHopDAG(isb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

				for (StatementBlock innerIsb : istmt.getIfBody())
					enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
							privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

				for (StatementBlock innerIsb : istmt.getElseBody())
					enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
							privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			} else if (sb instanceof ForStatementBlock) { // incl parfor
				ForStatementBlock fsb = (ForStatementBlock) sb;
				ForStatement fstmt = (ForStatement) fsb.getStatement(0);

				enumerateHopDAG(fsb.getFromHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
				enumerateHopDAG(fsb.getToHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
				if (fsb.getIncrementHops() != null) {
					enumerateHopDAG(fsb.getIncrementHops(), prog, memoTable, hopCommonTable, rewireTable,
							privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
				}

				for (StatementBlock innerFsb : fstmt.getBody())
					enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
							privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			} else if (sb instanceof WhileStatementBlock) {
				WhileStatementBlock wsb = (WhileStatementBlock) sb;
				WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

				enumerateHopDAG(wsb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

				for (StatementBlock innerWsb : wstmt.getBody())
					enumerateStatementBlock(innerWsb, prog, memoTable, hopCommonTable, rewireTable,
							privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			} else if (sb instanceof FunctionStatementBlock) {
				FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
				FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

				for (StatementBlock innerFsb : fstmt.getBody())
					enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
							privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			} else { // generic (last-level)
				if (sb.getHops() != null) {
					for (Hop c : sb.getHops())
						enumerateHopDAG(c, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
								unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
				}
			}
		}

		/**
		 * Rewires and enumerates federated execution plans for a given Hop.
		 * This method processes all input nodes, rewires TWrite and TRead operations,
		 * and generates federated plan variants for both inner and outer code blocks.
		 */
		private static void enumerateHopDAG(Hop hop, DMLProgram prog, FederatedMemoTable memoTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
				Map<Long, Privacy> privacyConstraintMap, Set<Long> unRefTwriteSet,
				Set<String> fnStack, int numOfWorkers, Set<Long> visitedHops, OracleFacade oracleFacade) {
			// Process all input nodes first if not already in memo table

			List<Hop> childHops = new ArrayList<>(hop.getInput());

			// Todo: Check if is right
			if ((hop instanceof DataOp) && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
				List<Hop> transChildHops = rewireTable.get(hop.getHopID());
				if (transChildHops != null) {
					childHops.addAll(transChildHops);
				}
			}

			for (Hop inputHop : childHops) {
				long inputHopID = inputHop.getHopID();
				if (!memoTable.contains(inputHopID, FederatedOutput.FOUT)
						&& !memoTable.contains(inputHopID, FederatedOutput.LOUT)) {
					if (!visitedHops.contains(inputHopID)) {
						visitedHops.add(inputHopID);
						enumerateHopDAG(inputHop, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
								unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
					}
				}
			}

			if (hop instanceof FunctionOp) {
				// maintain counters and investigate functions if not seen so far
				FunctionOp fop = (FunctionOp) hop;
				if (fop.getFunctionType() == FunctionType.DML) {
					String fkey = fop.getFunctionKey();

					if (!fnStack.contains(fkey)) {
						fnStack.add(fkey);
						if (prog == null) {
							FederatedPlannerLogger.logWarnMessage(
									"[FederatedCost] Skipping nested function " + fkey
											+ " because DMLProgram is unavailable in dynamic planning");
						} else {
							FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(),
									fop.getFunctionName());

							if (fsb == null) {
								FederatedPlannerLogger.logWarnMessage(
										"[FederatedCost] Function " + fkey
												+ " not found in DMLProgram; skipping nested planning");
							} else {
								enumerateStatementBlock(fsb, prog, memoTable, hopCommonTable, rewireTable,
										privacyConstraintMap, unRefTwriteSet, fnStack, numOfWorkers,
										visitedHops, oracleFacade);
							}
						}
					}
				}
			}

			// Enumerate the federated plan for the current Hop
			enumerateHop(hop, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					unRefTwriteSet, numOfWorkers, oracleFacade);

			// FederatedPlanRewireTransTable.logHopInfo(hop, privacyConstraintMap,
			// "enumerateHopDAG");

		}

		/**
		 * Enumerates federated execution plans for a given Hop.
		 * This method calculates the self cost and child costs for the Hop,
		 * generates federated plan variants for both LOUT and FOUT output types,
		 * and prunes redundant plans before adding them to the memo table.
		 */
		private static void enumerateHop(Hop hop, FederatedMemoTable memoTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				Map<Long, List<Hop>> rewireTable, Map<Long, Privacy> privacyConstraintMap,
				Set<Long> unRefTwriteSet, int numOfWorkers, OracleFacade oracleFacade) {
			long hopID = hop.getHopID();
			List<Hop> childHops = new ArrayList<>(hop.getInput());
			int numParentHops = hop.getParent().size();

			if (hop instanceof DataOp) {
				Types.OpOpData opType = ((DataOp) hop).getOp();
				if (opType == Types.OpOpData.TRANSIENTWRITE && !hop.getName().equals("__pred")) {
					List<Hop> transParentHops = rewireTable.get(hop.getHopID());
					if (transParentHops != null) {
						numParentHops += transParentHops.size();
					}
				} else if (opType == Types.OpOpData.TRANSIENTREAD) {
					List<Hop> transChildHops = rewireTable.get(hop.getHopID());
					if (transChildHops != null) {
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
			} else {
				for (Hop parentHop : hop.getParent()) {
					if (parentHop instanceof DataOp
							&& unRefTwriteSet.contains(parentHop.getHopID())) {
						numParentHops--;
					}
				}
			}

			FederatedMemoTable.HopCommon hopCommon = hopCommonTable.get(hopID);
			hopCommon.setNumOfParentHops(numParentHops);
			if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
				enumerateFederatedDataOp((DataOp) hop, memoTable, hopCommon);
				return;
			}

			double baseSelfCost = FederatedPlanCostEstimator.computeHopCost(hopCommon);

			int initialNumInputs = childHops.size();
			// Preallocate for the original child count; getChildCosts may shrink childHops,
			// so only the first numInputs entries remain valid afterwards.
			double[][] childCumulativeCost = new double[initialNumInputs][2]; // # of child, LOUT/FOUT of child
			double[] childForwardingCostToCP = new double[initialNumInputs]; // # of child (FOUT -> CP)
			double[] childForwardingCostToFED = new double[initialNumInputs]; // # of child (LOUT -> FED)
			List<Hop> lOutfOutChildHops = new ArrayList<>(childHops);

			List<Hop> lOUTOnlyinputHops = new ArrayList<>();
			List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
			List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();

			List<Hop> fOUTOnlyinputHops = new ArrayList<>();
			List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
			List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();

			// The self cost follows its own weight, while the forwarding cost follows the
			// parent's weight.
			FederatedPlanCostEstimator.getChildCosts(hopCommon, memoTable, lOutfOutChildHops, childCumulativeCost,
					childForwardingCostToCP, childForwardingCostToFED, lOUTOnlyinputHops,
					lOUTOnlychildCumulativeCost, lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops,
					fOUTOnlychildCumulativeCost, fOUTOnlychildForwardingCostToCP, numOfWorkers);

			// childCumulativeCost/childForwardingCost arrays are treated as buffers sized
			// by
			// initialNumInputs;
			// only indices [0, numInputs) are populated after getChildCosts mutates
			// childHops.
			int numBothOutInputs = lOutfOutChildHops.size();
			int numLoutOnlyInputs = lOUTOnlyinputHops.size();
			int numFoutOnlyInputs = fOUTOnlyinputHops.size();
			List<Hop> inputHopsForPrivacy = new ArrayList<>(childHops);
			inputHopsForPrivacy.addAll(lOUTOnlyinputHops);
			inputHopsForPrivacy.addAll(fOUTOnlyinputHops);
			final Privacy privacyConstraint = privacyConstraintMap.getOrDefault(hopID, Privacy.PUBLIC);

			double cpSelfCost = baseSelfCost;
			double fedSelfCost = baseSelfCost / Math.max(1, numOfWorkers);
			double hopNetworkWeight = hopCommon.getNetworkWeight();
			double resultDownloadCost = hopNetworkWeight
					* FederatedPlanCostEstimator.computeDownloadNetworkCost(hop.getOutputMemEstimate());

			final int enumerationLimit = 1 << numBothOutInputs;

			FederatedMemoTable.FedPlanVariants lOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.LOUT);
			FederatedMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.FOUT);
			Map<List<FType>, OpCaps> oracleCache = new HashMap<>();

			for (int i = 0; i < enumerationLimit; i++) {
				List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();
				List<FType> collectedFTypes = new ArrayList<>();
				List<Hop> collectedHops = new ArrayList<>();
				// Costs from children, split by the parent's ExecType semantics.
				double childCostCPExec = 0; // Parent executes in CP; forwarding only from FOUT children.
				double childCostFEDExec = 0; // Parent executes in FED; forwarding only from LOUT children.

				for (int j = 0; j < numBothOutInputs; j++) {
					Hop inputHop = lOutfOutChildHops.get(j);
					final int bit = (i & (1 << j)) != 0 ? 1 : 0;
					final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
					FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
							childType);

					if (childPlan == null) {
						throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + inputHop.getHopID()
								+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
								+ hop.getOpString() + "), privacy=" + privacyConstraint);
					}

					planChilds.add(Pair.of(inputHop.getHopID(), childType));
					collectedFTypes.add(childPlan.getFType());
					collectedHops.add(inputHop);
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=%s exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
							childType, childPlan.getExecType(), childPlan.getFType()));
					childCostCPExec += childCumulativeCost[j][bit] + childForwardingCostToCP[j] * bit;
					childCostFEDExec += childCumulativeCost[j][bit] + childForwardingCostToFED[j] * (1 - bit);
				}

				for (int j = 0; j < numLoutOnlyInputs; j++) {
					Hop inputHop = lOUTOnlyinputHops.get(j);
					FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
							FederatedOutput.LOUT);
					if (childPlan == null) {
						throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + inputHop.getHopID()
								+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
								+ hop.getOpString() + "), privacy=" + privacyConstraint);
					}
					planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
					collectedFTypes.add(childPlan.getFType());
					collectedHops.add(inputHop);
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=LOUT exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
							childPlan.getExecType(), childPlan.getFType()));
					childCostCPExec += lOUTOnlychildCumulativeCost.get(j);
					childCostFEDExec += lOUTOnlychildCumulativeCost.get(j) + lOUTOnlychildForwardingCostToFED.get(j);
				}

				for (int j = 0; j < numFoutOnlyInputs; j++) {
					Hop inputHop = fOUTOnlyinputHops.get(j);
					FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
							FederatedOutput.FOUT);
					if (childPlan == null) {
						throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + inputHop.getHopID()
								+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
								+ hop.getOpString() + "), privacy=" + privacyConstraint);
					}
					planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
					collectedFTypes.add(childPlan.getFType());
					collectedHops.add(inputHop);
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=FOUT exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
							childPlan.getExecType(), childPlan.getFType()));
					childCostCPExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCostToCP.get(j);
					childCostFEDExec += fOUTOnlychildCumulativeCost.get(j);
				}

				List<FType> alignedInputFTypes = OracleUtils.alignInputFTypes(hop, collectedHops, collectedFTypes);
				OpCaps caps = oracleCache.computeIfAbsent(alignedInputFTypes, k -> {
					OpCaps decision = oracleFacade.decide(hop, k);
					FederatedPlannerLogger.logOracleDecision(hop, privacyConstraint, k, decision, rewireTable);
					return decision;
				});

				ExecType oracleExec = caps.exec();
				FType oracleLogicalFType = deriveLogicalFType(hop, caps);
				double resultUploadCost = hopNetworkWeight * FederatedPlanCostEstimator.computeUploadNetworkCost(
						hop.getOutputMemEstimate(), oracleLogicalFType, numOfWorkers);

				if (privacyConstraint != Privacy.PUBLIC && oracleExec == ExecType.CP
						&& !allowsCPOverride(privacyConstraint, caps)) {
					FederatedPlannerLogger.logWarnMessage(
							"[Planner] Skipping CP-only plan for hop " + hopID + " (" + hop.getOpString()
									+ ") due to privacy " + privacyConstraint + " and oracle caps reason "
									+ caps.reason()
									+ " (inputs=" + alignedInputFTypes + ")");
					continue;
				}

				ExecPlacementPolicy.Decision placementDecision = ExecPlacementPolicy.decide(
						hop, privacyConstraint, oracleLogicalFType, caps);

				// FED Exec, FOUT placement
				if (placementDecision.allowFED_FOUT) {
					FederatedMemoTable.FedPlan fedFOutPlan = new FederatedMemoTable.FedPlan(
							fedSelfCost + childCostFEDExec,
							fOutFedPlanVariants, planChilds);
					fedFOutPlan.setExecType(ExecType.FED);
					fedFOutPlan.setFType(oracleLogicalFType);
					fOutFedPlanVariants.addFedPlan(fedFOutPlan);
				}

				// FED Exec, LOUT placement
				if (placementDecision.allowFED_LOUT) {
					FederatedMemoTable.FedPlan fedLOutPlan = new FederatedMemoTable.FedPlan(
							fedSelfCost + childCostFEDExec + resultDownloadCost,
							lOutFedPlanVariants, planChilds);
					fedLOutPlan.setExecType(ExecType.FED);
					fedLOutPlan.setFType(oracleLogicalFType);
					lOutFedPlanVariants.addFedPlan(fedLOutPlan);
				}

				// CP Exec, LOUT/FOUT placement
				if (placementDecision.allowCP_LOUT) {
					FederatedMemoTable.FedPlan cpLOutPlan = new FederatedMemoTable.FedPlan(
							cpSelfCost + childCostCPExec,
							lOutFedPlanVariants, planChilds);
					cpLOutPlan.setExecType(ExecType.CP);
					cpLOutPlan.setFType(oracleLogicalFType);
					lOutFedPlanVariants.addFedPlan(cpLOutPlan);
				}
				if (placementDecision.allowCP_FOUT) {
					FederatedMemoTable.FedPlan cpFOutPlan = new FederatedMemoTable.FedPlan(
							cpSelfCost + childCostCPExec + resultUploadCost,
							fOutFedPlanVariants, planChilds);
					cpFOutPlan.setExecType(ExecType.CP);
					cpFOutPlan.setFType(oracleLogicalFType);
					fOutFedPlanVariants.addFedPlan(cpFOutPlan);
				}
			}

			boolean hasLOutPlan = !lOutFedPlanVariants.isEmpty();
			boolean hasFOutPlan = !fOutFedPlanVariants.isEmpty();

			if (hasLOutPlan) {
				lOutFedPlanVariants.pruneFedPlans();
				memoTable.addFedPlanVariants(hopID, FederatedOutput.LOUT, lOutFedPlanVariants);
			}
			if (hasFOutPlan) {
				fOutFedPlanVariants.pruneFedPlans();
				memoTable.addFedPlanVariants(hopID, FederatedOutput.FOUT, fOutFedPlanVariants);
			}

			if (!hasLOutPlan && !hasFOutPlan) {
				throw new DMLRuntimeException("No valid federated plan for hop " + hopID + " (" + hop.getOpString()
						+ ") under privacy " + privacyConstraint + " (LOUT candidates=" + hasLOutPlan
						+ ", FOUT candidates=" + hasFOutPlan + ")");
			}
		}

		private static void enumerateFederatedDataOp(DataOp dataOp, FederatedMemoTable memoTable,
				FederatedMemoTable.HopCommon hopCommon) {
			FType baseFType = FederatedTypePropagator.deriveFType(dataOp);

			FederatedMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.FOUT);
			FederatedMemoTable.FedPlan fedPlan = new FederatedMemoTable.FedPlan(0.0, fOutFedPlanVariants,
					Collections.emptyList());
			fedPlan.setExecType(ExecType.FED);
			fedPlan.setFType(baseFType);
			fOutFedPlanVariants.addFedPlan(fedPlan);
			memoTable.addFedPlanVariants(dataOp.getHopID(), FederatedOutput.FOUT, fOutFedPlanVariants);
		}

		// Logical FType follows the oracle hint whenever available. This keeps
		// the planner aligned with the oracle's view of the result layout.
		private static FType deriveLogicalFType(Hop hop, OpCaps caps) {
			Optional<FType> foutTypeOpt = caps != null ? caps.foutFType() : Optional.empty();

			if (foutTypeOpt.isPresent()) {
				return foutTypeOpt.get();
			}

			return null;

		}

		private static boolean allowsCPOverride(Privacy privacyConstraint, OpCaps caps) {
			// Policy gate: CP override is globally disabled for protected data. Flip
			// ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA only when the oracle can prove privacy
			// guarantees.
			if (!ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA) {
				return false;
			}
			if (caps == null || privacyConstraint == null) {
				return false;
			}
			return false;
		}

		// Creates a dummy root node (fedplan) and selects the FedPlan with the minimum
		// cost to return.
		// The dummy root node does not have LOUT or FOUT.
		private static FederatedMemoTable.FedPlan getMinCostRootFedPlan(Set<Hop> progRootHopSet,
				FederatedMemoTable memoTable) {
			double cumulativeCost = 0;
			List<Pair<Long, FederatedOutput>> rootFedPlanChilds = new ArrayList<>();

			// Iterate over each Hop in the progRootHopSet
			for (Hop endHop : progRootHopSet) {
				// Retrieve the pruned FedPlan for LOUT and FOUT from the memo table
				FederatedMemoTable.FedPlan lOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(),
						FederatedOutput.LOUT);
				FederatedMemoTable.FedPlan fOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(),
						FederatedOutput.FOUT);

				if (lOutFedPlan == null && fOutFedPlan == null) {
					FederatedPlannerLogger.logWarnMessage(
							"[Planner] Skipping root hop " + endHop.getHopID() + " (" + endHop.getOpString()
									+ ") because no federated plan variants are available");
					continue;
				}

				if (fOutFedPlan == null) {
					cumulativeCost += lOutFedPlan.getCumulativeCost();
					rootFedPlanChilds.add(Pair.of(endHop.getHopID(), FederatedOutput.LOUT));
				} else if (lOutFedPlan == null) {
					cumulativeCost += fOutFedPlan.getCumulativeCost();
					rootFedPlanChilds.add(Pair.of(endHop.getHopID(), FederatedOutput.FOUT));
				} else {
					// Compare the cumulative costs of LOUT and FOUT FedPlans
					if (lOutFedPlan.getCumulativeCost() <= fOutFedPlan.getCumulativeCost()) {
						cumulativeCost += lOutFedPlan.getCumulativeCost();
						rootFedPlanChilds.add(Pair.of(endHop.getHopID(), FederatedOutput.LOUT));
					} else {
						cumulativeCost += fOutFedPlan.getCumulativeCost();
						rootFedPlanChilds.add(Pair.of(endHop.getHopID(), FederatedOutput.FOUT));
					}
				}
			}

			return new FederatedMemoTable.FedPlan(cumulativeCost, null, rootFedPlanChilds);
		}

		/**
		 * Detects and resolves federated placement conflicts in a single BFS + single
		 * resolve pass.
		 */
		public static double detectAndResolveConflictFedPlan(
				FederatedMemoTable memoTable, FederatedMemoTable.FedPlan rootPlan, int numOfWorkers) {

			if (rootPlan == null)
				return 0.0;

			Map<Long, ConflictEntry> conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan);
			Map<Long, ConflictEntry> conflictMap = filterTrueConflicts(memoTable, conflictCheckMap);
			double additionalTotalCost = resolveAllConflictsSinglePass(memoTable, conflictMap, numOfWorkers);
			return additionalTotalCost;
		}

		/**
		 * Collect all parent usages of each hop via a single BFS traversal starting at
		 * the dummy root's children.
		 * Visitation is based on FedPlan object identity; we assume one FedPlan
		 * instance per (hopID, FederatedOutput).
		 */
		private static Map<Long, ConflictEntry> collectConflictsSingleBFS(
				FederatedMemoTable memoTable, FederatedMemoTable.FedPlan rootPlan) {

			Map<Long, ConflictEntry> conflictCheckMap = new HashMap<>();
			Queue<FederatedMemoTable.FedPlan> queue = new ArrayDeque<>();
			Set<FederatedMemoTable.FedPlan> visited = new HashSet<>();

			for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
				FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(rootChild);
				if (childPlan == null) {
					String msg = "NULL FedPlan for root child hop " + rootChild.getKey();
					if (OptimizerUtils.isStrictFederatedConflictCheck())
						throw new DMLRuntimeException(msg);
					else
						FederatedPlannerLogger.logNullFedPlanError(rootChild.getKey(), msg);
					continue;
				}
				queue.add(childPlan);
			}

			while (!queue.isEmpty()) {
				FederatedMemoTable.FedPlan current = queue.poll();
				if (!visited.add(current))
					continue;

				Hop currentHop = current.getHopRef();

				for (Pair<Long, FederatedOutput> childEdge : current.getChildFedPlans()) {
					long childHopID = childEdge.getKey();
					FederatedOutput childOut = childEdge.getValue();

					FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childEdge);
					if (childPlan == null) {
						String msg = "NULL FedPlan for hop " + childHopID
								+ " as child of hop " + (currentHop != null ? currentHop.getHopID() : -1)
								+ " (" + (currentHop != null ? currentHop.getOpString() : "null") + ")";
						if (OptimizerUtils.isStrictFederatedConflictCheck())
							throw new DMLRuntimeException(msg);
						else
							FederatedPlannerLogger.logNullFedPlanError(childHopID, msg);
						continue;
					}

					ConflictEntry entry = conflictCheckMap.get(childHopID);
					if (entry == null) {
						conflictCheckMap.put(childHopID, new ConflictEntry(childOut, current));
					} else {
						entry.addUsage(childOut, current);
					}

					queue.add(childPlan);
				}
			}

			return conflictCheckMap;
		}

		private static Map<Long, ConflictEntry> filterTrueConflicts(
				FederatedMemoTable memoTable, Map<Long, ConflictEntry> conflictCheckMap) {

			Map<Long, ConflictEntry> conflictMap = new LinkedHashMap<>();

			for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
				long hopID = e.getKey();
				ConflictEntry entry = e.getValue();

				if (!entry.isTrulyConflicting())
					continue;

				FederatedMemoTable.FedPlan lOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
				FederatedMemoTable.FedPlan fOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);

				boolean hasLOUT = (lOutPlan != null);
				boolean hasFOUT = (fOutPlan != null);

				if (!hasLOUT || !hasFOUT) {
					String msg = "Federated placement conflict on hop " + hopID
							+ " but only one of LOUT/FOUT exists (hasLOUT=" + hasLOUT + ", hasFOUT=" + hasFOUT + ")";
					if (OptimizerUtils.isStrictFederatedConflictCheck())
						throw new DMLRuntimeException(msg);
					else
						FederatedPlannerLogger.logWarnMessage("[Planner] " + msg);
					continue;
				}

				conflictMap.put(hopID, entry);
			}

			return conflictMap;
		}

		/**
		 * Resolves placement conflicts independently per hopID without recomputing
		 * FedPlan costs.
		 * Assumes per-hop LOUT/FOUT costs are independent across conflicts and across
		 * the DAG.
		 * Only childFedPlans edges are mutated; the returned cost is an estimate
		 * aggregated over conflicts.
		 */
		private static double resolveAllConflictsSinglePass(
				FederatedMemoTable memoTable, Map<Long, ConflictEntry> conflictMap, int numOfWorkers) {

			double totalAdditionalCost = 0.0;

			if (!conflictMap.isEmpty()) {
				FederatedPlannerLogger.logInfoMessage("[Planner] Resolving "
						+ conflictMap.size() + " federated placement conflicts");
			}

			for (Map.Entry<Long, ConflictEntry> e : conflictMap.entrySet()) {
				long hopID = e.getKey();
				ConflictEntry entry = e.getValue();
				totalAdditionalCost += resolveOneHopConflict(memoTable, hopID, entry, numOfWorkers);
			}

			return totalAdditionalCost;
		}

		private static double resolveOneHopConflict(
				FederatedMemoTable memoTable, long hopID, ConflictEntry entry, int numOfWorkers) {

			FederatedMemoTable.FedPlan lOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
			FederatedMemoTable.FedPlan fOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);

			if (lOutPlan == null || fOutPlan == null) {
				throw new DMLRuntimeException("Expected both LOUT and FOUT plans for hop " + hopID);
			}

			double outputMem = lOutPlan.getHopRef().getOutputMemEstimate();
			double lOutUploadCost = FederatedPlanCostEstimator.computeUploadNetworkCost(
					outputMem, lOutPlan.getFType(), numOfWorkers);
			double fOutDownloadCost = FederatedPlanCostEstimator.computeDownloadNetworkCost(outputMem);
			double lOutUploadCostPerParents = FederatedPlanCostEstimator.computeForwardingCostPerParents(
					lOutUploadCost, lOutPlan);
			double fOutDownloadCostPerParents = FederatedPlanCostEstimator.computeForwardingCostPerParents(
					fOutDownloadCost, fOutPlan);

			double lOutAdditionalCost = 0.0;
			double fOutAdditionalCost = 0.0;
			boolean lOutNeedsForwarding = false;
			boolean fOutNeedsForwarding = false;

			for (FederatedMemoTable.FedPlan parentPlan : entry.parents) {
				List<Pair<Integer, Pair<Long, FederatedOutput>>> childEdges = findChildEdges(parentPlan, hopID);
				if (childEdges.isEmpty()) {
					String msg = "Parent plan for hop " + hopID + " lost its child edge.";
					if (OptimizerUtils.isStrictFederatedConflictCheck())
						throw new DMLRuntimeException(msg);
					else {
						Hop parentHop = parentPlan.getHopRef();
						FederatedPlannerLogger.logWarnMessage("[Planner] " + msg
								+ " parentHop=" + (parentHop != null ? parentHop.getHopID() : -1));
					}
					continue;
				}

				for (Pair<Integer, Pair<Long, FederatedOutput>> edgeEntry : childEdges) {
					FederatedOutput originalOut = edgeEntry.getValue().getValue();
					FederatedOutput parentOut = parentPlan.getFedOutType();

					if (originalOut == FederatedOutput.LOUT) {
						fOutAdditionalCost += fOutPlan.getCumulativeCostPerParents()
								- lOutPlan.getCumulativeCostPerParents();

						if (parentOut == FederatedOutput.LOUT) {
							fOutNeedsForwarding = true;
						} else if (parentOut == FederatedOutput.FOUT) {
							lOutNeedsForwarding = true;
							lOutAdditionalCost -= lOutUploadCostPerParents;
							fOutAdditionalCost -= lOutUploadCostPerParents;
						}
					} else if (originalOut == FederatedOutput.FOUT) {
						lOutAdditionalCost += lOutPlan.getCumulativeCostPerParents()
								- fOutPlan.getCumulativeCostPerParents();

						if (parentOut == FederatedOutput.FOUT) {
							lOutNeedsForwarding = true;
						} else if (parentOut == FederatedOutput.LOUT) {
							fOutNeedsForwarding = true;
								double weightedForwarding = parentPlan
										.computeForwardingWeightOfChild(lOutPlan.getLoopContext(),
												parentPlan.getMultiplicity())
										* fOutDownloadCostPerParents;
								lOutAdditionalCost -= weightedForwarding;
								fOutAdditionalCost -= weightedForwarding;
							}
					} else {
						Hop parentHop = parentPlan.getHopRef();
						FederatedPlannerLogger.logWarnMessage("[Planner] Unexpected child placement " + originalOut
								+ " for hop " + hopID + " under parent "
								+ (parentHop != null ? parentHop.getHopID() : -1));
					}
				}
			}

			if (lOutNeedsForwarding)
				lOutAdditionalCost += lOutUploadCost;
			if (fOutNeedsForwarding)
				fOutAdditionalCost += fOutDownloadCost;

			FederatedOutput chosen;
			double chosenCost;
			if (lOutAdditionalCost <= fOutAdditionalCost) {
				chosen = FederatedOutput.LOUT;
				chosenCost = lOutAdditionalCost;
			} else {
				chosen = FederatedOutput.FOUT;
				chosenCost = fOutAdditionalCost;
			}

			for (FederatedMemoTable.FedPlan parentPlan : entry.parents) {
				List<Pair<Long, FederatedOutput>> childs = parentPlan.getChildFedPlans();
				for (int i = 0; i < childs.size(); i++) {
					Pair<Long, FederatedOutput> edge = childs.get(i);
					if (edge.getKey() == hopID && edge.getValue() != chosen) {
						childs.set(i, Pair.of(edge.getKey(), chosen));
					}
				}
			}

			return chosenCost;
		}

		// Parent-child edge uniqueness: duplicates of the same hopID in a parent's
		// childFedPlans
		// are not expected, but we return all matches so duplicates are processed
		// uniformly.
		private static List<Pair<Integer, Pair<Long, FederatedOutput>>> findChildEdges(
				FederatedMemoTable.FedPlan parentPlan, long hopID) {

			List<Pair<Integer, Pair<Long, FederatedOutput>>> matches = new ArrayList<>();
			List<Pair<Long, FederatedOutput>> childs = parentPlan.getChildFedPlans();
			for (int i = 0; i < childs.size(); i++) {
				Pair<Long, FederatedOutput> edge = childs.get(i);
				if (edge.getKey() == hopID) {
					matches.add(Pair.of(i, edge));
				}
			}
			return matches;
		}

		private static final class ConflictEntry {
			// firstSeenOut is kept for debugging/tracing only.
			final FederatedOutput firstSeenOut;
			// LinkedHashSet keeps insertion order for debugging while avoiding duplicate
			// parents.
			final java.util.LinkedHashSet<FederatedMemoTable.FedPlan> parents;
			boolean seenLOUT;
			boolean seenFOUT;

			ConflictEntry(FederatedOutput out, FederatedMemoTable.FedPlan parent) {
				this.firstSeenOut = out;
				this.parents = new java.util.LinkedHashSet<>();
				this.parents.add(parent);
				this.seenLOUT = (out == FederatedOutput.LOUT);
				this.seenFOUT = (out == FederatedOutput.FOUT);
			}

			void addUsage(FederatedOutput out, FederatedMemoTable.FedPlan parent) {
				this.parents.add(parent);
				if (out == FederatedOutput.LOUT)
					this.seenLOUT = true;
				else if (out == FederatedOutput.FOUT)
					this.seenFOUT = true;
			}

			boolean isTrulyConflicting() {
				return seenLOUT && seenFOUT;
			}
		}
	}

	public static class FederatedPlanCostEstimator {
		/**
		 * Retrieves the cumulative and forwarding costs of the child hops and stores
		 * them in arrays.
		 * Note: this method mutates {@code inputHops} in place, removing children that
		 * have only
		 * FOUT or only LOUT plans and putting them into the respective lists so that
		 * {@code inputHops}
		 * retains only children with both plan variants. The caller must pre-size the
		 * cost arrays to
		 * the original {@code inputHops.size()}, but only the prefix matching the
		 * (possibly smaller)
		 * mutated {@code inputHops.size()} will be populated.
		 */
		public static void getChildCosts(FederatedMemoTable.HopCommon hopCommon, FederatedMemoTable memoTable,
				List<Hop> inputHops,
				double[][] childCumulativeCost, double[] childForwardingCostToCP,
				double[] childForwardingCostToFED, List<Hop> lOUTOnlyinputHops,
				List<Double> lOUTOnlychildCumulativeCost, List<Double> lOUTOnlychildForwardingCostToFED,
				List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
				List<Double> fOUTOnlychildForwardingCostToCP, int numOfWorkers) {

			Hop parentHop = hopCommon.getHopRef();
			Iterator<Hop> iterator = inputHops.iterator();
			int currentIndex = 0;

			// Populate the cost buffers sequentially for children that retain both plan
			// variants.
			// Indices beyond the mutated inputHops.size() are intentionally left untouched.
			while (iterator.hasNext()) {
				Hop childHop = iterator.next();
				long childHopID = childHop.getHopID();

				FederatedMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
						FederatedOutput.FOUT);
				if (childFOutFedPlan == null) {
					lOUTOnlyinputHops.add(childHop);
					iterator.remove();
					continue;
				}

				FederatedMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
						FederatedOutput.LOUT);
				if (childLOutFedPlan == null) {
					fOUTOnlyinputHops.add(childHop);
					iterator.remove();
					continue;
				}

				childCumulativeCost[currentIndex][0] = childLOutFedPlan.getCumulativeCostPerParents();
				childCumulativeCost[currentIndex][1] = childFOutFedPlan.getCumulativeCostPerParents();
				double outputMem = childHop.getOutputMemEstimate();
				double downloadCost = computeDownloadNetworkCost(outputMem);
				double uploadCost = computeUploadNetworkCost(outputMem, childLOutFedPlan.getFType(), numOfWorkers);
				double downloadCostPerParents = computeForwardingCostPerParents(downloadCost, childFOutFedPlan);
				double uploadCostPerParents = computeForwardingCostPerParents(uploadCost, childLOutFedPlan);
					double forwardingWeight = hopCommon.computeForwardingWeightOfChild(
							childLOutFedPlan.getLoopContext(), hopCommon.getMultiplicity());
					childForwardingCostToCP[currentIndex] = forwardingWeight * downloadCostPerParents;
					childForwardingCostToFED[currentIndex] = forwardingWeight * uploadCostPerParents;
					currentIndex++;
				}

			for (int i = 0; i < lOUTOnlyinputHops.size(); i++) {
				Hop childHop = lOUTOnlyinputHops.get(i);
				long childHopID = childHop.getHopID();

				FederatedMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
						FederatedOutput.LOUT);

				if (childLOutFedPlan == null) {
					throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + childHopID + " ("
							+ childHop.getOpString() + ") while processing parent " + parentHop.getHopID() + " ("
							+ parentHop.getOpString() + ")");
				}
				lOUTOnlychildCumulativeCost.add(childLOutFedPlan.getCumulativeCostPerParents());
				double outputMem = childHop.getOutputMemEstimate();
				double uploadCost = computeUploadNetworkCost(outputMem, childLOutFedPlan.getFType(), numOfWorkers);
				double uploadCostPerParents = computeForwardingCostPerParents(uploadCost, childLOutFedPlan);
					double forwardingWeight = hopCommon.computeForwardingWeightOfChild(
							childLOutFedPlan.getLoopContext(), hopCommon.getMultiplicity());
					lOUTOnlychildForwardingCostToFED.add(forwardingWeight * uploadCostPerParents);
				}

			for (int i = 0; i < fOUTOnlyinputHops.size(); i++) {
				Hop childHop = fOUTOnlyinputHops.get(i);
				long childHopID = childHop.getHopID();

				FederatedMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
						FederatedOutput.FOUT);

				if (childFOutFedPlan == null) {
					throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + childHopID + " ("
							+ childHop.getOpString() + ") while processing parent " + parentHop.getHopID() + " ("
							+ parentHop.getOpString() + ")");
				}
				fOUTOnlychildCumulativeCost.add(childFOutFedPlan.getCumulativeCostPerParents());
				double outputMem = childHop.getOutputMemEstimate();
				double downloadCost = computeDownloadNetworkCost(outputMem);
				double downloadCostPerParents = computeForwardingCostPerParents(downloadCost, childFOutFedPlan);
					double forwardingWeight = hopCommon.computeForwardingWeightOfChild(
							childFOutFedPlan.getLoopContext(), hopCommon.getMultiplicity());
					fOUTOnlychildForwardingCostToCP.add(forwardingWeight * downloadCostPerParents);
				}
			}

		/**
		 * Computes the cost associated with a given Hop node.
		 * This method calculates both the self cost and the forwarding cost for the
		 * Hop,
		 * taking into account its type and the number of parent nodes.
		 *
		 * @param hopCommon The HopCommon object containing the Hop and its properties.
		 * @return The self cost of the Hop.
		 */
		public static double computeHopCost(FederatedMemoTable.HopCommon hopCommon) {
			// TWrite and TRead are meta-data operations, hence selfCost is zero
			if (hopCommon.hopRef instanceof DataOp) {
				if (((DataOp) hopCommon.hopRef).getOp() == Types.OpOpData.TRANSIENTWRITE) {
					hopCommon.setSelfCost(0);
					// Since TWrite and TRead have the same FedOutType, forwarding cost is zero
					hopCommon.setForwardingCost(0);
					return 0;
				} else if (((DataOp) hopCommon.hopRef).getOp() == Types.OpOpData.TRANSIENTREAD) {
					hopCommon.setSelfCost(0);
					// TRead may have a different FedOutType from its parent, so calculate
					// forwarding cost
					hopCommon.setForwardingCost(computeDownloadNetworkCost(hopCommon.hopRef.getOutputMemEstimate()));
					return 0;
				}
			}

			double selfCost = hopCommon.getComputeWeight() * hopCommon.getMultiplicity()
					* FederatedCostModel.computeOpCost(hopCommon.hopRef);
			double forwardingCost = computeDownloadNetworkCost(hopCommon.hopRef.getOutputMemEstimate());

			hopCommon.setSelfCost(selfCost);
			hopCommon.setForwardingCost(forwardingCost);

			return selfCost;
		}

		static double computeDownloadNetworkCost(double memSize) {
			return FederatedCostModel.computeDownloadNetworkCost(memSize);
		}

		static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
			return FederatedCostModel.computeUploadNetworkCost(memSize, fType, numWorkers);
		}

		public static double computeRefedNetworkCost(double memSize, FType fType, int numWorkers) {
			return FederatedCostModel.computeRefedNetworkCost(memSize, fType, numWorkers);
		}

		static double computeForwardingCostPerParents(double cost, FederatedMemoTable.FedPlan plan) {
			int numParents = plan.getNumOfParents();
			if (numParents >= 2) {
				return cost / numParents;
			}
			return cost;
		}

	}

	public static class FederatedMemoTable {
		// Maps Hop ID and fedOutType pairs to their plan variants
		private final Map<Pair<Long, FederatedOutput>, FedPlanVariants> hopMemoTable = new HashMap<>();
		private final Map<Long, Hop> hopRefMap = new HashMap<>();
		private final Map<Long, Long> cloneToOrig = new HashMap<>();

		public void addFedPlanVariants(long hopID, FederatedOutput fedOutType, FedPlanVariants fedPlanVariants) {
			hopMemoTable.put(new ImmutablePair<>(hopID, fedOutType), fedPlanVariants);
		}

		public FedPlanVariants getFedPlanVariants(Pair<Long, FederatedOutput> fedPlanPair) {
			return hopMemoTable.get(fedPlanPair);
		}

		public FedPlan getFedPlanAfterPrune(long hopID, FederatedOutput federatedOutput) {
			FedPlanVariants fedPlanVariantList = hopMemoTable.get(new ImmutablePair<>(hopID, federatedOutput));
			if (fedPlanVariantList == null || fedPlanVariantList.isEmpty()) {
				return null;
			}
			return fedPlanVariantList._fedPlanVariants.get(0);
		}

		public FedPlan getFedPlanAfterPrune(Pair<Long, FederatedOutput> fedPlanPair) {
			FedPlanVariants fedPlanVariantList = hopMemoTable.get(fedPlanPair);
			if (fedPlanVariantList == null || fedPlanVariantList.isEmpty()) {
				return null;
			}
			return fedPlanVariantList._fedPlanVariants.get(0);
		}

		public boolean contains(long hopID, FederatedOutput fedOutType) {
			return hopMemoTable.containsKey(new ImmutablePair<>(hopID, fedOutType));
		}

		public void registerHopRefs(Map<Long, HopCommon> hopCommonTable) {
			if (hopCommonTable == null)
				return;
			for (Map.Entry<Long, HopCommon> entry : hopCommonTable.entrySet()) {
				HopCommon hc = entry.getValue();
				if (hc != null && hc.getHopRef() != null)
					hopRefMap.put(entry.getKey(), hc.getHopRef());
			}
		}

		public void registerCloneMapping(Map<Long, Long> cloneToOrigMap) {
			if (cloneToOrigMap == null || cloneToOrigMap.isEmpty())
				return;
			cloneToOrig.putAll(cloneToOrigMap);
		}

		public long resolveOriginalHopId(long hopId) {
			Long orig = cloneToOrig.get(hopId);
			return orig != null ? orig : hopId;
		}

		public Hop resolveOriginalHop(long hopId) {
			long origId = resolveOriginalHopId(hopId);
			Hop hop = hopRefMap.get(origId);
			if (hop != null)
				return hop;
			return hopRefMap.get(hopId);
		}

		/**
		 * Represents a single federated execution plan with its associated costs and
		 * dependencies.
		 * This class contains:
		 * 1. selfCost: Cost of the current hop (computation + input/output memory
		 * access).
		 * 2. cumulativeCost: Total cost including this plan's selfCost and all child
		 * plans' cumulativeCost.
		 * 3. forwardingCost: Network transfer cost for this plan to the parent plan.
		 * 
		 * FedPlan is linked to FedPlanVariants, which in turn uses HopCommon to manage
		 * common properties and costs.
		 */
		public static class FedPlan {
			private double cumulativeCost; // Total cost = sum of selfCost + cumulativeCost of child plans
			private final FedPlanVariants fedPlanVariants; // Reference to variant list
			private final List<Pair<Long, FederatedOutput>> childFedPlans; // Child plan references
			private ExecType execType;
			private FType fType;

			public FedPlan(double cumulativeCost, FedPlanVariants fedPlanVariants,
					List<Pair<Long, FederatedOutput>> childFedPlans) {
				this.cumulativeCost = cumulativeCost;
				this.fedPlanVariants = fedPlanVariants;
				this.childFedPlans = childFedPlans;
			}

			public Hop getHopRef() {
				return fedPlanVariants.hopCommon.getHopRef();
			}

			public long getHopID() {
				return fedPlanVariants.hopCommon.getHopRef().getHopID();
			}

			public FederatedOutput getFedOutType() {
				return fedPlanVariants.getFedOutType();
			}

			public double getCumulativeCost() {
				return cumulativeCost;
			}

			public double getCumulativeCostPerParents() {
				double cumulativeCostPerParents = cumulativeCost;
				int numOfParents = fedPlanVariants.hopCommon.getNumOfParents();
				if (numOfParents >= 2) {
					cumulativeCostPerParents /= numOfParents;
				}
				return cumulativeCostPerParents;
			}

			public double getSelfCost() {
				return fedPlanVariants.hopCommon.getSelfCost();
			}

			public double getForwardingCost() {
				return fedPlanVariants.hopCommon.getForwardingCost();
			}

			public double getForwardingCostPerParents() {
				double forwardingCostPerParents = fedPlanVariants.hopCommon.getForwardingCost();
				int numOfParents = fedPlanVariants.hopCommon.getNumOfParents();
				if (numOfParents >= 2) {
					forwardingCostPerParents /= numOfParents;
				}
				return forwardingCostPerParents;
			}

			public double getComputeWeight() {
				return fedPlanVariants.hopCommon.getComputeWeight();
			}

			public double getNetworkWeight() {
				return fedPlanVariants.hopCommon.getNetworkWeight();
			}

			public double getMultiplicity() {
				return fedPlanVariants.hopCommon.getMultiplicity();
			}

			public int getNumOfParents() {
				return fedPlanVariants.hopCommon.getNumOfParents();
			}

			public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext,
					double childMultiplicity) {
				return fedPlanVariants.hopCommon.computeForwardingWeightOfChild(childLoopContext, childMultiplicity);
			}

			public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
				return fedPlanVariants.hopCommon.computeForwardingWeightOfChild(childLoopContext);
			}

			public List<Pair<Long, Double>> getLoopContext() {
				return fedPlanVariants.hopCommon.getLoopContext();
			}

			public List<Pair<Long, FederatedOutput>> getChildFedPlans() {
				return childFedPlans;
			}

			public void setFederatedOutput(FederatedOutput fedOutType) {
				fedPlanVariants.hopCommon.hopRef.setFederatedOutput(fedOutType);
			}

			public void setForcedExecType(ExecType execType) {
				fedPlanVariants.hopCommon.hopRef.setForcedExecType(execType);
			}

			public ExecType getExecType() {
				return execType;
			}

			public void setExecType(ExecType execType) {
				this.execType = execType;
			}

			public FType getFType() {
				return fType;
			}

			public void setFType(FType fType) {
				this.fType = fType;
			}
		}

		/**
		 * Represents a collection of federated execution plan variants for a specific
		 * Hop and FederatedOutput.
		 * This class contains cost information and references to the associated plans.
		 * It uses HopCommon to store common properties and costs related to the Hop.
		 */
		public static class FedPlanVariants {
			protected HopCommon hopCommon; // Common properties and costs for the Hop
			private final FederatedOutput fedOutType; // Output type (FOUT/LOUT)
			protected List<FedPlan> _fedPlanVariants; // List of plan variants

			public FedPlanVariants(HopCommon hopCommon, FederatedOutput fedOutType) {
				this.hopCommon = hopCommon;
				this.fedOutType = fedOutType;
				this._fedPlanVariants = new ArrayList<>();
			}

			public boolean isEmpty() {
				return _fedPlanVariants.isEmpty();
			}

			public void addFedPlan(FedPlan fedPlan) {
				if (fedPlan.getExecType() == null) {
					throw new DMLRuntimeException("FedPlan missing execType for hop "
							+ fedPlan.getHopID() + " (" + fedPlan.getHopRef().getOpString() + "), fedOutType="
							+ fedPlan.getFedOutType());
				}
				_fedPlanVariants.add(fedPlan);
			}

			public List<FedPlan> getFedPlanVariants() {
				return _fedPlanVariants;
			}

			public FederatedOutput getFedOutType() {
				return fedOutType;
			}

			public boolean pruneFedPlans() {
				if (!_fedPlanVariants.isEmpty()) {
					// Find the FedPlan with the minimum cumulative cost
					FedPlan minCostPlan = _fedPlanVariants.stream()
							.min(Comparator.comparingDouble(FedPlan::getCumulativeCost))
							.orElse(null);

					// Retain only the minimum cost plan
					_fedPlanVariants.clear();
					_fedPlanVariants.add(minCostPlan);
					return true;
				}
				return false;
			}
		}

		/**
		 * Represents common properties and costs associated with a Hop.
		 * This class holds a reference to the Hop and tracks its execution and network
		 * forwarding (transfer) costs.
		 * It also maintains the loop context information to properly calculate
		 * forwarding costs within loops.
		 */
		public static class HopCommon {
			protected final Hop hopRef; // Reference to the associated Hop
			protected double selfCost; // Cost of the hop's computation and memory access
			protected double forwardingCost; // Cost of forwarding the hop's output to its parent
			protected int numOfParents;
			protected double computeWeight; // Weight used to calculate cost based on hop execution frequency
			protected double networkWeight; // Weight used to calculate cost based on hop execution frequency
			protected double multiplicity; // Execution multiplicity for unrolled loops
			protected List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists

			public HopCommon(Hop hopRef, double computeWeight, double networkWeight, double multiplicity, int numOfParents,
					List<Pair<Long, Double>> loopContext) {
				this.hopRef = hopRef;
				this.selfCost = 0;
				this.forwardingCost = 0;
				this.numOfParents = numOfParents;
				this.computeWeight = computeWeight;
				this.networkWeight = networkWeight;
				this.multiplicity = multiplicity;
				this.loopContext = loopContext != null ? new ArrayList<>(loopContext) : new ArrayList<>();
			}

			public Hop getHopRef() {
				return hopRef;
			}

			public double getSelfCost() {
				return selfCost;
			}

			public double getForwardingCost() {
				return forwardingCost;
			}

			public double getComputeWeight() {
				return computeWeight;
			}

			public double getNetworkWeight() {
				return networkWeight;
			}

			public double getMultiplicity() {
				return multiplicity;
			}

			public int getNumOfParents() {
				return numOfParents;
			}

			public List<Pair<Long, Double>> getLoopContext() {
				return loopContext;
			}

			protected void setSelfCost(double selfCost) {
				this.selfCost = selfCost;
			}

			protected void setForwardingCost(double forwardingCost) {
				this.forwardingCost = forwardingCost;
			}

			protected void setNumOfParentHops(int numOfParentHops) {
				this.numOfParents = numOfParentHops;
			}

			/**
			 * Estimates how many times this parent's output is forwarded to a child by
			 * amortizing the parent's networkWeight over loops the child does not execute.
			 *
			 * Example:
			 * parent loopContext = [(for1, 100), (while2, 10)]
			 * childLoopContext = [(for1, 100)]
			 * => forwardingWeight = networkWeight / 10 (child result reused across while2
			 * iterations)
			 */
			public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext,
					double childMultiplicity) {
				return FederatedPlannerUtils.computeForwardingWeightOfChild(
						networkWeight, loopContext, childLoopContext, childMultiplicity);
			}

			public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
				return computeForwardingWeightOfChild(childLoopContext, 1.0);
			}
		}
	}

	public static class FederatedPlanRewireTransTable {
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

			private LoopAnalysisContext(boolean trackReadFromOutside, boolean trackHeaderReads) {
				this.trackReadFromOutside = trackReadFromOutside;
				this.trackHeaderReads = trackHeaderReads;
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
		}

		public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
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
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
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
				Map<Long, List<Hop>> rewireTable, Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
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
				// Todo: network weight을 0.5로 안하는 이유가 있나? 잘 모르겠음. 고민해봐야함.
				// networkWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;

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
					LoopAnalysisContext probeCtx = new LoopAnalysisContext(true, false);
					Set<Long> probeVisited = new HashSet<>();
					Map<Long, List<Hop>> probeRewireTable = new HashMap<>();
					Map<Long, FederatedMemoTable.HopCommon> probeHopCommon = new HashMap<>();
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

					LoopAnalysisContext iter0Analysis = new LoopAnalysisContext(true, false);
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

				LoopAnalysisContext iter1Analysis = new LoopAnalysisContext(false, true);
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

				double loopWeight = estimateWhileLoopWeight(wsb);
				double iter1Factor = Math.max(loopWeight - 1.0, 0.0);
				boolean allowUnroll = unrollCtx != null && loopCtx == null
						&& unrollDepth < MAX_UNROLL_DEPTH && iter1Factor > 0.0;
				Set<String> loopCarriedVars = Collections.emptySet();

				if (allowUnroll) {
					LoopAnalysisContext probeCtx = new LoopAnalysisContext(true, false);
					Set<Long> probeVisited = new HashSet<>();
					Map<Long, List<Hop>> probeRewireTable = new HashMap<>();
					Map<Long, FederatedMemoTable.HopCommon> probeHopCommon = new HashMap<>();
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

					LoopAnalysisContext iter0Analysis = new LoopAnalysisContext(true, false);
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

				LoopAnalysisContext iter1Analysis = new LoopAnalysisContext(false, true);
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

		private static double estimateWhileLoopWeight(WhileStatementBlock wsb) {
			Hop predicate = wsb.getPredicateHops();
			if (predicate == null) {
				return RewireConstants.DEFAULT_LOOP_WEIGHT;
			}

			double maxLiteralBound = -1;
			Queue<Hop> queue = new ArrayDeque<>();
			queue.add(predicate);

			while (!queue.isEmpty()) {
				Hop hop = queue.poll();
				if (hop instanceof BinaryOp) {
					BinaryOp bop = (BinaryOp) hop;
					Types.OpOp2 op = bop.getOp();
					Hop left = bop.getInput().get(0);
					Hop right = bop.getInput().get(1);

					if ((op == Types.OpOp2.LESS || op == Types.OpOp2.LESSEQUAL)
							&& right instanceof LiteralOp && !(left instanceof LiteralOp)) {
						maxLiteralBound = Math.max(maxLiteralBound,
								HopRewriteUtils.getDoubleValue((LiteralOp) right));
					} else if ((op == Types.OpOp2.GREATER || op == Types.OpOp2.GREATEREQUAL)
							&& left instanceof LiteralOp && !(right instanceof LiteralOp)) {
						maxLiteralBound = Math.max(maxLiteralBound,
								HopRewriteUtils.getDoubleValue((LiteralOp) left));
					}
				}

				if (hop.getInput() != null) {
					queue.addAll(hop.getInput());
				}
			}

			return maxLiteralBound > 0 ? maxLiteralBound : RewireConstants.DEFAULT_LOOP_WEIGHT;
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
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				List<Map<String, List<Hop>>> outerTransTableList,
				Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
				Map<Long, Privacy> privacyConstraintMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet,
				Set<String> fnStack, Set<Long> injectedIds,
				Map<String, Map<String, List<Hop>>> functionTransTableCache,
				double computeWeight, double networkWeight, double multiplicity, List<Pair<Long, Double>> loopStack,
				int unrollDepth, LoopAnalysisContext loopCtx, UnrollContext unrollCtx) {
			if (!visitedHops.add(hop.getHopID())) {
				return;
			}

			if (hop.getInput() != null) {
				for (Hop inputHop : hop.getInput()) {
					rewireHopDAG(inputHop, prog, visitedHops, rewireTable, hopCommonTable, outerTransTableList,
							formerTransTable, innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight, networkWeight, multiplicity, loopStack,
							unrollDepth, loopCtx, unrollCtx);
				}
			}

			hopCommonTable.put(hop.getHopID(),
					new FederatedMemoTable.HopCommon(hop, computeWeight, networkWeight, multiplicity, 0, loopStack));
			FederatedPlannerLogger.logBasicHopInfo(hop, "RewireHopDAG:addCommon");

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
								if (inputArgs != null && inputHops != null) {
									int limit = Math.min(inputArgs.length, inputHops.size());
									for (int i = 0; i < limit; i++) {
										Hop inputHop = inputHops.get(i);
										List<Hop> mappedHops = new ArrayList<>();

										if (inputHop instanceof DataOp
												&& ((DataOp) inputHop).getOp() == Types.OpOpData.TRANSIENTREAD) {
											List<Hop> transChildHops = rewireTable.get(inputHop.getHopID());
											if (transChildHops != null && !transChildHops.isEmpty()) {
												for (Hop childHop : transChildHops) {
													if (childHop instanceof DataOp
															&& ((DataOp) childHop)
																	.getOp() == Types.OpOpData.TRANSIENTREAD) {
														continue;
													}
													mappedHops.add(childHop);
												}
												if (mappedHops.isEmpty())
													mappedHops.addAll(transChildHops);
											}
										}

										if (mappedHops.isEmpty())
											mappedHops.add(inputHop);

										for (Hop mappedHop : mappedHops) {
											newFormerTransTable.computeIfAbsent(inputArgs[i], k -> new ArrayList<>())
													.add(mappedHop);
										}
									}
								}

								functionTransTable = rewireStatementBlock(fsb, prog, visitedHops,
										rewireTable, hopCommonTable, outerTransTableList, newFormerTransTable,
										privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet,
										fnStack, injectedIds, functionTransTableCache, computeWeight, networkWeight,
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

					if (functionTransTable != null && fsb != null) {
						for (int i = 0; i < fop.getOutputVariableNames().length; i++) {
							String tWriteName = fop.getOutputVariableNames()[i];
							List<Hop> outputHops = functionTransTable.get(fsb.getOutputsofSB().get(i).getName());
							if (outputHops == null || outputHops.isEmpty()) {
								continue;
							}
							innerTransTable.computeIfAbsent(tWriteName, k -> new ArrayList<>()).addAll(outputHops);
							for (Hop outputHop : outputHops) {
								if (!hopCommonTable.containsKey(outputHop.getHopID())) {
									hopCommonTable.put(outputHop.getHopID(),
											new FederatedMemoTable.HopCommon(outputHop, computeWeight, networkWeight,
													multiplicity, 0, loopStack));
								}
								unRefTwriteSet.add(outputHop.getHopID());
							}
						}
					}
				} else if (fop.getFunctionType() == FunctionType.MULTIRETURN_BUILTIN) {
					String[] outputNames = fop.getOutputVariableNames();
					ArrayList<Hop> outputHops = fop.getOutputs();
					if (outputNames != null && outputHops != null) {
						int limit = Math.min(outputNames.length, outputHops.size());
						for (int i = 0; i < limit; i++) {
							Hop outputHop = outputHops.get(i);
							if (outputHop == null)
								continue;
							if (!hopCommonTable.containsKey(outputHop.getHopID())) {
								hopCommonTable.put(outputHop.getHopID(),
										new FederatedMemoTable.HopCommon(outputHop, computeWeight, networkWeight,
												multiplicity, 0, loopStack));
							}
							innerTransTable.computeIfAbsent(outputNames[i], k -> new ArrayList<>()).add(outputHop);
							unRefTwriteSet.add(outputHop.getHopID());
						}
					}
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
				List<Hop> childHops = TransTableRewireUtils.rewireTransRead(
						hopName, innerTransTable, formerTransTable, outerTransTableList);
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

				List<Hop> filteredChildHops = new ArrayList<>();
				for (Hop childHop : childHops) {
					if (injectedIds != null && injectedIds.contains(childHop.getHopID()))
						continue;
					filteredChildHops.add(childHop);
				}
				if (filteredChildHops.isEmpty()) {
					filteredChildHops.addAll(childHops);
				}

				boolean hasNonTRead = false;
				for (Hop childHop : filteredChildHops) {
					if (!(childHop instanceof DataOp)
							|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTREAD) {
						hasNonTRead = true;
						break;
					}
				}
				if (hasNonTRead) {
					List<Hop> nonTReadChildHops = new ArrayList<>();
					for (Hop childHop : filteredChildHops) {
						if (childHop instanceof DataOp
								&& ((DataOp) childHop).getOp() == Types.OpOpData.TRANSIENTREAD) {
							continue;
						}
						nonTReadChildHops.add(childHop);
					}
					filteredChildHops = nonTReadChildHops;
				}

				FederatedPlannerLogger.logRewireHierarchy(hop, childHops, filteredChildHops, "RewireTransHop");

				// Todo: Handle exception when TRead has no Filtered Child (check why it's
				// missing)
				if (filteredChildHops.isEmpty()) {
					FederatedPlannerLogger.logFilteredChildHopsDebug(hopName, hop.getHopID(), filteredChildHops, true,
							"RewireTransHop");
					privacyConstraintMap.put(hop.getHopID(), Privacy.PUBLIC);
					return;
				}

				// Handle rewire table (TransRead -> TransWrite)
				rewireTable.put(hop.getHopID(), filteredChildHops);

				for (Hop filteredChildHop : filteredChildHops) {
					long filteredChildHopID = filteredChildHop.getHopID();
					// Rewire (TransWrite -> TransRead)
					rewireTable.computeIfAbsent(filteredChildHopID, k -> new ArrayList<>()).add(hop);
					// Remove refTWrite from unRefTwriteSet
					unRefTwriteSet.remove(filteredChildHopID);
				}
				// Propagate Privacy Constraint
				privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
						hop, filteredChildHops, privacyConstraintMap));
			} else {
				privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
						hop, hop.getInput(), privacyConstraintMap));
			}
		}

		private static void wireUnRefTwriteToLiveOut(
				StatementBlock sb, Set<Long> unRefTwriteSet,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				Map<String, List<Hop>> newFormerTransTable) {

			Function<Long, Hop> hopLookup = id -> {
				FederatedMemoTable.HopCommon hc = hopCommonTable.get(id);
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
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				Map<String, List<Hop>> newFormerTransTable, Set<Long> injectedIds) {
			if (injectedIds == null) {
				wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
				return;
			}
			Set<Long> before = new HashSet<>(unRefTwriteSet);
			wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
			for (Long hopId : before) {
				if (!unRefTwriteSet.contains(hopId))
					injectedIds.add(hopId);
			}
		}

	}

}
