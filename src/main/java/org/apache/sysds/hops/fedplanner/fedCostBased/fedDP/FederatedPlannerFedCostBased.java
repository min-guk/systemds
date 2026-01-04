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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
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
		Map<Long, FType> fTypeMap = new HashMap<>();

		List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = optimalPlan.getChildFedPlans();
		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : childFedPlanPairs) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
			rewriteHop(childPlan, memoTable, visited, fTypeMap);
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
		Map<Long, FType> fTypeMap = new HashMap<>();

		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
			// Propagate the actual selected output type of the child plan (LOUT/FOUT)
			rewriteHop(childPlan, memoTable, visited, fTypeMap);
		}
		FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
	}

	private void rewriteHop(FederatedMemoTable.FedPlan optimalPlan, FederatedMemoTable memoTable,
			Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited, Map<Long, FType> fTypeMap) {
		long hopID = optimalPlan.getHopRef().getHopID();
		boolean hasPlacementConflict = false;
		ExecType execType = optimalPlan.getExecType();
		FEDInstruction.FederatedOutput thisOutType = optimalPlan.getFedOutType();
		if (optimalPlan.getFType() != null && thisOutType == FederatedOutput.FOUT)
			fTypeMap.put(hopID, optimalPlan.getFType());

		if (execType == null) {
			throw new DMLRuntimeException("ExecType is null in FedPlan for hop " + hopID + " / "
					+ optimalPlan.getHopRef().getOpString());
		}

		Pair<FEDInstruction.FederatedOutput, ExecType> prev = visited.get(hopID);
		ExecType resolvedExecType = execType;
		FEDInstruction.FederatedOutput resolvedOutType = thisOutType;

		if (prev != null) {
			if (prev.getLeft() == thisOutType) {
				if (prev.getRight() != execType) {
					FederatedPlannerLogger.logWarnMessage(
							"[FederatedPlannerFedCostBased] ExecType conflict in rewriteHop for hop "
									+ hopID + " (" + optimalPlan.getHopRef().getOpString() + "): existing="
									+ prev.getRight() + ", incoming=" + execType + ", chosen="
									+ pickExecType(prev.getRight(), execType));
				}
				resolvedExecType = pickExecType(prev.getRight(), execType);
				optimalPlan.setForcedExecType(resolvedExecType);
				optimalPlan.setFederatedOutput(resolvedOutType);
				visited.put(hopID, Pair.of(resolvedOutType, resolvedExecType));
				return;
			} else {
				hasPlacementConflict = true;
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

			rewriteHop(childPlan, memoTable, visited, fTypeMap);
		}

		optimalPlan.setForcedExecType(resolvedExecType);

		if (hasPlacementConflict) {
			resolvedOutType = FEDInstruction.FederatedOutput.FOUT;
		}
		optimalPlan.setFederatedOutput(resolvedOutType);
		visited.put(hopID, Pair.of(resolvedOutType, resolvedExecType));

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

			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

			FederatedPlanRewireTransTable.rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
					unRefTwriteSet, unRefSet, progRootHopSet);

			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);

			for (long hopID : unRefTwriteSet) {
				// Todo (Future): Need to check unRefTwriteSet connecting to progRoot.
				progRootHopSet.add(hopCommonTable.get(hopID).getHopRef());
			}
			Set<String> fnStack = new HashSet<>();
			Set<Long> visitedHops = new HashSet<>();

			for (StatementBlock sb : prog.getStatementBlocks()) {
				enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, fedMap.size(), visitedHops, oracleFacade);
			}

			FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

			// Todo : Fix & Update Conflict Resolve Plan
			// Detect conflicts in the federated plans where different FedPlans have
			// different FederatedOutput types
			double additionalTotalCost = detectAndResolveConflictFedPlan(memoTable, optimalPlan);

			unRefSet.addAll(unRefTwriteSet);
			// Print the federated plan tree if requested
			if (isPrint) {
				FederatedPlannerLogger.printFedPlanTree(optimalPlan, unRefSet, memoTable, additionalTotalCost);
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

			Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

			DMLProgram prog = function.getDMLProg();
			FederatedPlanRewireTransTable.rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable,
					privacyConstraintMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet);

			RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
			OracleFacade oracleFacade = new OracleFacade(registry);

			Set<String> fnStack = new HashSet<>();
			Set<Long> visitedHops = new HashSet<>();
			enumerateStatementBlock(function, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					unRefTwriteSet, fnStack, fedMap.size(), visitedHops, oracleFacade);

			FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

			// Detect conflicts in the federated plans where different FedPlans have
			// different FederatedOutput types
			// Todo : Fix & Update Conflict Resolve Plan
			double additionalTotalCost = detectAndResolveConflictFedPlan(memoTable, optimalPlan);

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
						childHops.addAll(transChildHops);
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
			double[] childForwardingCost = new double[initialNumInputs]; // # of child
			List<Hop> lOutfOutChildHops = new ArrayList<>(childHops);

			List<Hop> lOUTOnlyinputHops = new ArrayList<>();
			List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
			List<Double> lOUTOnlychildForwardingCost = new ArrayList<>();

			List<Hop> fOUTOnlyinputHops = new ArrayList<>();
			List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
			List<Double> fOUTOnlychildForwardingCost = new ArrayList<>();

			// The self cost follows its own weight, while the forwarding cost follows the
			// parent's weight.
			FederatedPlanCostEstimator.getChildCosts(hopCommon, memoTable, lOutfOutChildHops, childCumulativeCost,
					childForwardingCost, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost, lOUTOnlychildForwardingCost,
					fOUTOnlyinputHops, fOUTOnlychildCumulativeCost, fOUTOnlychildForwardingCost);

			// childCumulativeCost/childForwardingCost are treated as buffers sized by
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
			double resultNetCost = FederatedPlanCostEstimator.computeHopForwardingCost(hop.getOutputMemEstimate());

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
					childCostCPExec += childCumulativeCost[j][bit] + childForwardingCost[j] * bit;
					childCostFEDExec += childCumulativeCost[j][bit] + childForwardingCost[j] * (1 - bit);
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
					childCostFEDExec += lOUTOnlychildCumulativeCost.get(j) + lOUTOnlychildForwardingCost.get(j);
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
					childCostCPExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCost.get(j);
					childCostFEDExec += fOUTOnlychildCumulativeCost.get(j);
				}

				List<FType> alignedInputFTypes = alignInputFTypes(hop, collectedHops, collectedFTypes);
				OpCaps caps = oracleCache.computeIfAbsent(alignedInputFTypes, k -> {
					OpCaps decision = oracleFacade.decide(hop, k);
					FederatedPlannerLogger.logOracleDecision(hop, privacyConstraint, k, decision, rewireTable);
					return decision;
				});

				ExecType oracleExec = caps.exec();
				FederatedOutput oraclePlacement = caps.placement();
				FType oracleLogicalFType = deriveLogicalFType(hop, caps);

				if (privacyConstraint != Privacy.PUBLIC && oracleExec == ExecType.CP
						&& !allowsCPOverride(privacyConstraint, caps)) {
					FederatedPlannerLogger.logWarnMessage(
							"[Planner] Skipping CP-only plan for hop " + hopID + " (" + hop.getOpString()
									+ ") due to privacy " + privacyConstraint + " and oracle caps reason "
									+ caps.reason()
									+ " (inputs=" + alignedInputFTypes + ")");
					continue;
				}

				/*
				 * Privacy × placement quick matrix (keep in sync with the branches below):
				 * PUBLIC: CP/LOUT, CP/FOUT, FED/FOUT, FED/LOUT
				 * PRIVATE & PRIVATE_AGGREGATE: FED/FOUT only.
				 * PRIVATE_AGGREGATE_TO_PUBLIC: FED/FOUT, FED/LOUT
				 */

				// 1. FED Exec, FOUT placement
				if (oracleExec == ExecType.FED && oraclePlacement == FederatedOutput.FOUT) {
					FederatedMemoTable.FedPlan fedFOutPlan = new FederatedMemoTable.FedPlan(
							fedSelfCost + childCostFEDExec,
							fOutFedPlanVariants, planChilds);
					fedFOutPlan.setExecType(ExecType.FED);
					fedFOutPlan.setFType(oracleLogicalFType);
					fOutFedPlanVariants.addFedPlan(fedFOutPlan);
				}

				// 2. FED Exec, LOUT placement
				if (privacyConstraint == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC || privacyConstraint == Privacy.PUBLIC) {
					if (oracleExec == ExecType.FED) {
						FederatedMemoTable.FedPlan fedLOutPlan = new FederatedMemoTable.FedPlan(
								fedSelfCost + childCostFEDExec + resultNetCost,
								lOutFedPlanVariants, planChilds);
						fedLOutPlan.setExecType(ExecType.FED);
						fedLOutPlan.setFType(oracleLogicalFType);
						lOutFedPlanVariants.addFedPlan(fedLOutPlan);
					}
				}

				// 3. CP Exec, LOUT/FOUT placement
				if (privacyConstraint == Privacy.PUBLIC) {
					FederatedMemoTable.FedPlan cpLOutPlan = new FederatedMemoTable.FedPlan(
							cpSelfCost + childCostCPExec,
							lOutFedPlanVariants, planChilds);
					cpLOutPlan.setExecType(ExecType.CP);
					cpLOutPlan.setFType(oracleLogicalFType);
					lOutFedPlanVariants.addFedPlan(cpLOutPlan);

					if (hop.getDataType().isMatrix()) {
						FederatedMemoTable.FedPlan cpFOutPlan = new FederatedMemoTable.FedPlan(
								cpSelfCost + childCostCPExec + resultNetCost,
								fOutFedPlanVariants, planChilds);
						cpFOutPlan.setExecType(ExecType.CP);
						cpFOutPlan.setFType(oracleLogicalFType);
						fOutFedPlanVariants.addFedPlan(cpFOutPlan);
					}
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

		private static List<FType> alignInputFTypes(Hop hop, List<Hop> collectedHops, List<FType> collectedFTypes) {
			if (hop == null) {
				return collectedFTypes;
			}
			List<Hop> parentInputs = hop.getInput();
			int numInputs = parentInputs == null ? 0 : parentInputs.size();
			List<FType> aligned = new ArrayList<>(Collections.nCopies(numInputs, null));
			if (numInputs == 0) {
				return aligned;
			}

			for (int i = 0; i < collectedHops.size(); i++) {
				Hop child = collectedHops.get(i);
				FType ftype = collectedFTypes.get(i);
				if (child == null) {
					FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping null child for hop "
							+ hop.getHopID());
					continue;
				}
				int pos = parentInputs.indexOf(child);
				if (pos >= 0 && pos < numInputs) {
					aligned.set(pos, ftype);
				} else {
					FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping unmatched child "
							+ (child != null ? child.getHopID() : "null") + " for hop " + hop.getHopID());
				}
			}
			return aligned;
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
				FederatedMemoTable memoTable, FederatedMemoTable.FedPlan rootPlan) {

			if (rootPlan == null)
				return 0.0;

			Map<Long, ConflictEntry> conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan);
			Map<Long, ConflictEntry> conflictMap = filterTrueConflicts(memoTable, conflictCheckMap);
			double additionalTotalCost = resolveAllConflictsSinglePass(memoTable, conflictMap);
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
				FederatedMemoTable memoTable, Map<Long, ConflictEntry> conflictMap) {

			double totalAdditionalCost = 0.0;

			if (!conflictMap.isEmpty()) {
				FederatedPlannerLogger.logInfoMessage("[Planner] Resolving "
						+ conflictMap.size() + " federated placement conflicts");
			}

			for (Map.Entry<Long, ConflictEntry> e : conflictMap.entrySet()) {
				long hopID = e.getKey();
				ConflictEntry entry = e.getValue();
				totalAdditionalCost += resolveOneHopConflict(memoTable, hopID, entry);
			}

			return totalAdditionalCost;
		}

		private static double resolveOneHopConflict(
				FederatedMemoTable memoTable, long hopID, ConflictEntry entry) {

			FederatedMemoTable.FedPlan lOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
			FederatedMemoTable.FedPlan fOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);

			if (lOutPlan == null || fOutPlan == null) {
				throw new DMLRuntimeException("Expected both LOUT and FOUT plans for hop " + hopID);
			}

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
							lOutAdditionalCost -= lOutPlan.getForwardingCostPerParents();
							fOutAdditionalCost -= lOutPlan.getForwardingCostPerParents();
						}
					} else if (originalOut == FederatedOutput.FOUT) {
						lOutAdditionalCost += lOutPlan.getCumulativeCostPerParents()
								- fOutPlan.getCumulativeCostPerParents();

						if (parentOut == FederatedOutput.FOUT) {
							lOutNeedsForwarding = true;
						} else if (parentOut == FederatedOutput.LOUT) {
							fOutNeedsForwarding = true;
							double weightedForwarding = parentPlan
									.computeForwardingWeightOfChild(lOutPlan.getLoopContext())
									* lOutPlan.getForwardingCostPerParents();
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
				lOutAdditionalCost += lOutPlan.getForwardingCost();
			if (fOutNeedsForwarding)
				fOutAdditionalCost += fOutPlan.getForwardingCost();

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
		// Default value is used as a reasonable estimate since we only need
		// to compare relative costs between different federated plans
		// Memory bandwidth for local computations (25 GB/s)
		private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
		// Network bandwidth for data transfers between federated sites (1 Gbps)
		private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
		private static final double DEFAULT_MBS_NETWORK_LATENCY = 0.001;

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
				double[][] childCumulativeCost, double[] childForwardingCost, List<Hop> lOUTOnlyinputHops,
				List<Double> lOUTOnlychildCumulativeCost, List<Double> lOUTOnlychildForwardingCost,
				List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
				List<Double> fOUTOnlychildForwardingCost) {

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
				childForwardingCost[currentIndex] = hopCommon
						.computeForwardingWeightOfChild(childLOutFedPlan.getLoopContext())
						* childLOutFedPlan.getForwardingCostPerParents();
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
				lOUTOnlychildForwardingCost
						.add(hopCommon.computeForwardingWeightOfChild(childLOutFedPlan.getLoopContext())
								* childLOutFedPlan.getForwardingCostPerParents());
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
				fOUTOnlychildForwardingCost
						.add(hopCommon.computeForwardingWeightOfChild(childFOutFedPlan.getLoopContext())
								* childFOutFedPlan.getForwardingCostPerParents());
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
					hopCommon.setForwardingCost(computeHopForwardingCost(hopCommon.hopRef.getOutputMemEstimate()));
					return 0;
				}
			}

			double selfCost = hopCommon.getComputeWeight() * computeSelfCost(hopCommon.hopRef);
			double forwardingCost = computeHopForwardingCost(hopCommon.hopRef.getOutputMemEstimate());

			hopCommon.setSelfCost(selfCost);
			hopCommon.setForwardingCost(forwardingCost);

			return selfCost;
		}

		/**
		 * Computes the cost for the current Hop node.
		 *
		 * @param currentHop The Hop node whose cost needs to be computed
		 * @return The total cost for the current node's operation
		 */
		private static double computeSelfCost(Hop currentHop) {
			double computeCost = ComputeCost.getHOPComputeCost(currentHop);
			double inputAccessCost = computeHopMemoryAccessCost(currentHop.getInputMemEstimate());
			double ouputAccessCost = computeHopMemoryAccessCost(currentHop.getOutputMemEstimate());

			// Compute total cost assuming:
			// 1. Computation and input access can be overlapped (hence taking max)
			// 2. Output access must wait for both to complete (hence adding)
			return Math.max(computeCost, inputAccessCost) + ouputAccessCost;
		}

		/**
		 * Calculates the memory access cost based on data size and memory bandwidth.
		 *
		 * @param memSize Size of data to be accessed (in bytes)
		 * @return Time cost for memory access (in seconds)
		 */
		private static double computeHopMemoryAccessCost(double memSize) {
			if (memSize <= 0)
				return 0.0;
			return memSize / (1024 * 1024) / DEFAULT_MBS_MEMORY_BANDWIDTH;
		}

		/**
		 * Calculates the network transfer cost based on data size and network
		 * bandwidth.
		 * Used when federation status changes between parent and child plans.
		 *
		 * @param memSize Size of data to be transferred (in bytes)
		 * @return Time cost for network transfer (in seconds)
		 */
		private static double computeHopForwardingCost(double memSize) {
			return DEFAULT_MBS_NETWORK_LATENCY + (memSize / (1024 * 1024) / DEFAULT_MBS_NETWORK_BANDWIDTH);
		}

		public static double computeRefedNetworkCost(double memSize, FType fType, int numWorkers) {
			if (memSize <= 0)
				return 0.0;
			if (fType == null)
				return computeHopForwardingCost(memSize);
			double multiplier = (fType == FType.FULL || fType == FType.BROADCAST)
					? Math.max(1, numWorkers)
					: 1.0;
			return computeHopForwardingCost(memSize * multiplier);
		}

	}

	public static class FederatedMemoTable {
		// Maps Hop ID and fedOutType pairs to their plan variants
		private final Map<Pair<Long, FederatedOutput>, FedPlanVariants> hopMemoTable = new HashMap<>();

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
			protected List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists

			public HopCommon(Hop hopRef, double computeWeight, double networkWeight, int numOfParents,
					List<Pair<Long, Double>> loopContext) {
				this.hopRef = hopRef;
				this.selfCost = 0;
				this.forwardingCost = 0;
				this.numOfParents = numOfParents;
				this.computeWeight = computeWeight;
				this.networkWeight = networkWeight;
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
			public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
				return FederatedPlannerUtils.computeForwardingWeightOfChild(
						networkWeight, loopContext, childLoopContext);
			}
		}
	}

	public static class FederatedPlanRewireTransTable {

		private static final double DEFAULT_LOOP_WEIGHT = 10.0;
		private static final double DEFAULT_IF_ELSE_WEIGHT = 0.5;

		public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet) {
			// Maps Hop ID and fedOutType pairs to their plan variants
			Set<Long> visitedHops = new HashSet<>();
			Set<String> fnStack = new HashSet<>();
			List<Pair<Long, Double>> loopStack = new ArrayList<>();

			List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
			Map<String, List<Hop>> outerTransTable = new HashMap<>();
			outerTransTableList.add(outerTransTable);

			for (StatementBlock sb : prog.getStatementBlocks()) {
				Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
						hopCommonTable, outerTransTableList, null, privacyConstraintMap,
						fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack);
				outerTransTableList.get(0).putAll(innerTransTable);
			}
		}

		public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
				Map<Long, List<Hop>> rewireTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<Long, Privacy> privacyConstraintMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet) {
			Set<Long> visitedHops = new HashSet<>();
			Set<String> fnStack = new HashSet<>();
			List<Pair<Long, Double>> loopStack = new ArrayList<>();
			List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
			Map<String, List<Hop>> outerTransTable = new HashMap<>();
			outerTransTableList.add(outerTransTable);
			// Todo (Future): not tested & not used
			rewireStatementBlock(function, prog, visitedHops, rewireTable, hopCommonTable, outerTransTableList, null,
					privacyConstraintMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack);
		}

		public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog,
				Set<Long> visitedHops,
				Map<Long, List<Hop>> rewireTable, Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<Long, Privacy> privacyConstraintMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet, Set<String> fnStack,
				double computeWeight, double networkWeight, List<Pair<Long, Double>> parentLoopStack) {
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

				rewireHopDAG(isb.getPredicateHops(), prog, visitedHops, rewireTable, hopCommonTable,
						newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
						networkWeight, parentLoopStack);

				newFormerTransTable.putAll(innerTransTable);
				Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
				elseFormerTransTable.putAll(innerTransTable);
				computeWeight *= DEFAULT_IF_ELSE_WEIGHT;
				// Todo: network weight을 0.5로 안하는 이유가 있나? 잘 모르겠음. 고민해봐야함.
				// networkWeight *= DEFAULT_IF_ELSE_WEIGHT;

				for (StatementBlock innerIsb : istmt.getIfBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, parentLoopStack));

				for (StatementBlock innerIsb : istmt.getElseBody())
					elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, elseFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, parentLoopStack));

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

				rewireHopDAG(fsb.getFromHops(), prog, visitedHops, rewireTable, hopCommonTable, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
						networkWeight, currentLoopStack);
				rewireHopDAG(fsb.getToHops(), prog, visitedHops, rewireTable, hopCommonTable, newOuterTransTableList,
						null,
						innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
						networkWeight, currentLoopStack);

				if (fsb.getIncrementHops() != null) {
					rewireHopDAG(fsb.getIncrementHops(), prog, visitedHops, rewireTable, hopCommonTable,
							newOuterTransTableList, null, innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, currentLoopStack);
				}
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, currentLoopStack));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOut(fsb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
			} else if (sb instanceof WhileStatementBlock) {
				WhileStatementBlock wsb = (WhileStatementBlock) sb;
				WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

				computeWeight *= DEFAULT_LOOP_WEIGHT;
				networkWeight *= DEFAULT_LOOP_WEIGHT;

				// Create current loop context (copy parent context)
				List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
				currentLoopStack.add(Pair.of(sb.getSBID(), DEFAULT_LOOP_WEIGHT));

				rewireHopDAG(wsb.getPredicateHops(), prog, visitedHops, rewireTable, hopCommonTable,
						newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
						networkWeight, currentLoopStack);
				newFormerTransTable.putAll(innerTransTable);

				for (StatementBlock innerWsb : wstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, currentLoopStack));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOut(wsb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
			} else if (sb instanceof FunctionStatementBlock) {
				FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
				FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

				for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							hopCommonTable, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight,
							networkWeight, parentLoopStack));

				// Wire fcall operation to liveOutHops
				wireUnRefTwriteToLiveOut(fsb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
			} else { // generic (last-level)
				if (sb.getHops() != null) {
					for (Hop c : sb.getHops())
						rewireHopDAG(c, prog, visitedHops, rewireTable, hopCommonTable, newOuterTransTableList, null,
								innerTransTable,
								privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
								computeWeight, networkWeight, parentLoopStack);
				}

				return innerTransTable;
			}
			return newFormerTransTable;
		}

		private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops,
				Map<Long, List<Hop>> rewireTable,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				List<Map<String, List<Hop>>> outerTransTableList,
				Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
				Map<Long, Privacy> privacyConstraintMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet,
				Set<String> fnStack, double computeWeight, double networkWeight, List<Pair<Long, Double>> loopStack) {
			if (!visitedHops.add(hop.getHopID())) {
				return;
			}

			if (hop.getInput() != null) {
				for (Hop inputHop : hop.getInput()) {
					rewireHopDAG(inputHop, prog, visitedHops, rewireTable, hopCommonTable, outerTransTableList,
							formerTransTable, innerTransTable,
							privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							computeWeight, networkWeight, loopStack);
				}
			}

			hopCommonTable.put(hop.getHopID(),
					new FederatedMemoTable.HopCommon(hop, computeWeight, networkWeight, 0, loopStack));

			// Identify hops to connect to the root dummy node
			// Connect TWrite pred and u(print) to the root dummy node
			if ((hop instanceof DataOp && (hop.getName().equals("__pred"))) // TWrite "__pred"
					|| (hop instanceof UnaryOp && ((UnaryOp) hop).getOp() == Types.OpOp1.PRINT) // u(print)
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
						FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(),
								fop.getFunctionName());

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
								rewireTable, hopCommonTable, outerTransTableList, newFormerTransTable,
								privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
								computeWeight, networkWeight, loopStack);

						for (int i = 0; i < fop.getOutputVariableNames().length; i++) {
							String tWriteName = fop.getOutputVariableNames()[i];
							List<Hop> outputHops = functionTransTable.get(fsb.getOutputsofSB().get(i).getName());
							innerTransTable.computeIfAbsent(tWriteName, k -> new ArrayList<>()).addAll(outputHops);
							for (Hop outputHop : outputHops) {
								unRefTwriteSet.add(outputHop.getHopID());
							}
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
					fedMap, unRefTwriteSet);
		}

		private static void rewireTransHop(Hop hop, Map<Long, List<Hop>> rewireTable,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet) {
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
				// Propagate Privacy Constraint
				privacyConstraintMap.put(hop.getHopID(), FederatedPlannerUtils.getPrivacyConstraint(
						hop, hop.getInput(), privacyConstraintMap));
			} else if (opType == Types.OpOpData.TRANSIENTREAD) {
				// Rewire TransRead
				List<Hop> childHops = rewireTransRead(hopName, innerTransTable, formerTransTable, outerTransTableList);
				// Handle rewire table (TransRead -> TransWrite)
				rewireTable.put(hop.getHopID(), childHops);

				// Todo: Handle exception when TRead has no Child (check why it's missing)
				if (childHops == null || childHops.isEmpty()) {
					FederatedPlannerLogger.logTransReadRewireDebug(hopName, hop.getHopID(), childHops, true,
							"RewireTransHop");
					privacyConstraintMap.put(hop.getHopID(), Privacy.PUBLIC);
					return;
				}

				// Remove childHops that have different hopVarName
				List<Hop> filteredChildHops = new ArrayList<>();
				for (Hop childHop : childHops) {
					String hopVarName = hop.getName();

					if (hopVarName.equals(childHop.getName())) {
						filteredChildHops.add(childHop);
					}
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

		private static void wireUnRefTwriteToLiveOut(
				StatementBlock sb, Set<Long> unRefTwriteSet,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
				Map<String, List<Hop>> newFormerTransTable) {

			FederatedPlannerUtils.wireUnRefTwriteToLiveOutCommon(
					sb,
					unRefTwriteSet,
					// hopLookup: hopID -> Hop
					id -> {
						FederatedMemoTable.HopCommon hc = hopCommonTable.get(id);
						return (hc != null) ? hc.getHopRef() : null;
					},
					newFormerTransTable,
					// compatFn: unRefTwriteHop vs 대표 liveOutHop
					(unRefTwriteHop, liveOutHop) -> calculateCompatibilityScore(unRefTwriteHop, liveOutHop,
							hopCommonTable),
					"[DP]");
		}

		// NOTE: keep in sync with MinST planner:
		// FederatedPlanMinSTPlanner.calculateCompatibilityScore
		private static FederatedPlannerUtils.CompatibilityScore calculateCompatibilityScore(Hop unRefTwriteHop,
				Hop liveOutHop,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable) {
			int nameScore = getMatchingPriority(unRefTwriteHop.getName(), liveOutHop.getName());
			boolean sameDataType = unRefTwriteHop.getDataType() == liveOutHop.getDataType() &&
					unRefTwriteHop.getValueType() == liveOutHop.getValueType();

			if (sameDataType) {
				return new FederatedPlannerUtils.CompatibilityScore(1, 0, nameScore);
			}

			double dimSimilarity = calculateDimensionSimilarity(unRefTwriteHop, liveOutHop);
			if (dimSimilarity > 0) {
				int dimScore = (int) Math.round((1 - dimSimilarity) * 100);
				return new FederatedPlannerUtils.CompatibilityScore(2, dimScore, nameScore);
			}

			double commonChildMemEstimate = findCommonChildrenMemEstimate(unRefTwriteHop, liveOutHop, hopCommonTable);
			if (commonChildMemEstimate > 0) {
				int childScore = (int) Math.max(0, 10000 - Math.min(commonChildMemEstimate, 10000));
				return new FederatedPlannerUtils.CompatibilityScore(3, childScore, nameScore);
			}

			return new FederatedPlannerUtils.CompatibilityScore(4, 0, nameScore);
		}

		private static int getMatchingPriority(String unRefTwriteHopName, String liveOutHopName) {
			if (unRefTwriteHopName.equals(liveOutHopName)) {
				return 1;
			}

			if (unRefTwriteHopName.startsWith(liveOutHopName) ||
					liveOutHopName.startsWith(unRefTwriteHopName)) {
				return 2;
			}

			if (unRefTwriteHopName.contains(liveOutHopName) ||
					liveOutHopName.contains(unRefTwriteHopName)) {
				return 3;
			}

			return 4;
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
		private static double findCommonChildrenMemEstimate(Hop hop1, Hop hop2,
				Map<Long, FederatedMemoTable.HopCommon> hopCommonTable) {
			Set<Long> children1 = getAllChildren(hop1, new HashSet<>(), 5);
			Set<Long> children2 = getAllChildren(hop2, new HashSet<>(), 5);

			// 교집합 찾기
			Set<Long> commonChildren = new HashSet<>(children1);
			commonChildren.retainAll(children2);

			// 공통 child들의 총 메모리 추정치 계산 (HopRef 기반)
			double totalMemEstimate = 0.0;
			for (Long childId : commonChildren) {
				FederatedMemoTable.HopCommon hopCommon = hopCommonTable.get(childId);
				if (hopCommon != null) {
					Hop childHop = hopCommon.getHopRef();
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
