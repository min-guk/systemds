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
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
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

public class FederatedPlannerDpCostEnumerator {
	// Global privacy policy: never allow CP overrides for protected data unless
	// this flag flips.
	private static final boolean ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA = false;
	// Planner option: disallow CP->FOUT in recompile regions (function/while).
	private static final boolean DISALLOW_CPFOUT_ON_RECOMPILE = true;
	/**
	 * Enumerates the entire DML program to generate federated execution plans.
	 * It processes each statement block, computes the optimal federated plan,
	 * detects and resolves conflicts, and optionally prints the plan tree.
	 *
	 * @param prog    The DML program to enumerate.
	 * @param isPrint A boolean indicating whether to print the federated plan tree.
	 */
	public static FederatedPlannerDpMemoTable.FedPlan enumerateProgram(DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			boolean isPrint) {
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Map<Long, Set<Long>> parentChildUploadHints = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable = new HashMap<>();
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx = new FederatedPlannerDpRewireTransTable.UnrollContext();

		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlannerDpRewireTransTable.rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx);
		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(unrollCtx.getCloneToOrig());
		populateParentChildUploadHintsFromRewire(parentChildUploadHints, rewireTable, hopCommonTable);

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
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		}
		for (Hop iter1Root : unrollCtx.getIter1Roots()) {
			if (iter1Root == null)
				continue;
			enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		}

		FederatedPlannerDpMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

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

	public static FederatedPlannerDpMemoTable.FedPlan enumerateFunctionDynamic(FunctionStatementBlock function,
			FederatedPlannerDpMemoTable memoTable,
			boolean isPrint) {
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Map<Long, Set<Long>> parentChildUploadHints = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable = new HashMap<>();
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx = new FederatedPlannerDpRewireTransTable.UnrollContext();

		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		DMLProgram prog = function.getDMLProg();
		FederatedPlannerDpRewireTransTable.rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable,
				privacyConstraintMap,
				fedMap, unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx);
		memoTable.registerHopRefs(hopCommonTable);
		memoTable.registerCloneMapping(unrollCtx.getCloneToOrig());
		populateParentChildUploadHintsFromRewire(parentChildUploadHints, rewireTable, hopCommonTable);

		RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
		OracleFacade oracleFacade = new OracleFacade(registry);
		int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);

		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();
		enumerateStatementBlock(function, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		for (Hop iter1Root : unrollCtx.getIter1Roots()) {
			if (iter1Root == null)
				continue;
			enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		}

		FederatedPlannerDpMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

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
	public static void enumerateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Privacy> privacyConstraintMap, Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet, Set<String> fnStack,
			int numOfWorkers, Set<Long> visitedHops, OracleFacade oracleFacade) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			enumerateHopDAG(isb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
					privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

			for (StatementBlock innerIsb : istmt.getIfBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

			for (StatementBlock innerIsb : istmt.getElseBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			enumerateHopDAG(fsb.getFromHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			enumerateHopDAG(fsb.getToHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			if (fsb.getIncrementHops() != null) {
				enumerateHopDAG(fsb.getIncrementHops(), prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			}

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			enumerateHopDAG(wsb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
					privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

			for (StatementBlock innerWsb : wstmt.getBody())
				enumerateStatementBlock(innerWsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					enumerateHopDAG(c, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
							parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
			}
		}
	}

	/**
	 * Rewires and enumerates federated execution plans for a given Hop.
	 * This method processes all input nodes, rewires TWrite and TRead operations,
	 * and generates federated plan variants for both inner and outer code blocks.
	 */
	private static void enumerateHopDAG(Hop hop, DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Privacy> privacyConstraintMap, Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet,
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
							parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
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
									privacyConstraintMap, parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers,
									visitedHops, oracleFacade);
						}
					}
				}
			}
		}

		// Enumerate the federated plan for the current Hop
		enumerateHop(hop, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				parentChildUploadHints, unRefTwriteSet, numOfWorkers, oracleFacade);

		// FederatedPlannerDpRewireTransTable.logHopInfo(hop, privacyConstraintMap,
		// "enumerateHopDAG");

	}

	/**
	 * Enumerates federated execution plans for a given Hop.
	 * This method calculates the self cost and child costs for the Hop,
	 * generates federated plan variants for both LOUT and FOUT output types,
	 * and prunes redundant plans before adding them to the memo table.
	 */
	private static void enumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, Map<Long, Privacy> privacyConstraintMap,
			Set<Long> unRefTwriteSet, int numOfWorkers, OracleFacade oracleFacade) {
		enumerateHop(hop, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				new HashMap<>(), unRefTwriteSet, numOfWorkers, oracleFacade);
	}

	private static void enumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, Map<Long, Privacy> privacyConstraintMap,
			Map<Long, Set<Long>> parentChildUploadHints,
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

		FederatedPlannerDpMemoTable.HopCommon hopCommon = hopCommonTable.get(hopID);
		hopCommon.setNumOfParentHops(numParentHops);
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
			enumerateFederatedDataOp((DataOp) hop, memoTable, hopCommon);
			return;
		}
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			if (enumerateTransientReadDataOp((DataOp) hop, childHops, memoTable, hopCommon)) {
				return;
			}
		}

		Set<Long> tWriteChildIds = collectTransientWriteChildIds(hop, childHops);
		final boolean enforceTReadConsistency = !tWriteChildIds.isEmpty();

		double baseSelfCost = FederatedPlannerDpCostEstimator.computeHopCost(hopCommon);

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
		FederatedPlannerDpCostEstimator.getChildCosts(hopCommon, memoTable, lOutfOutChildHops, childCumulativeCost,
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
		// Boundary transfer accounting is based on selected execution/output combinations:
		// FED parent + LOUT child always incurs LOUT->FED upload (shared by parent count/weight).
		// CP parent + FOUT child always incurs FOUT->CP download.
		List<Hop> inputHopsForPrivacy = new ArrayList<>(childHops);
		inputHopsForPrivacy.addAll(lOUTOnlyinputHops);
		inputHopsForPrivacy.addAll(fOUTOnlyinputHops);
		final Privacy privacyConstraint = privacyConstraintMap.getOrDefault(hopID, Privacy.PUBLIC);

		double hopNetworkWeight = hopCommon.getNetworkWeight();
		// Hop-local placement conversion (FED<->local result materialization) follows
		// the hop's own execution frequency, independent of parent-child forwarding.
		double hopPlacementWeight = placementTransferWeight(hopCommon);
		double outputMemEstimate = FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		double cpSelfCost = baseSelfCost;
		// Align with MinST: FED execution has a fixed per-op coordination overhead that
		// should be modeled even when compute cost scales down with workers.
		double fedOverhead = (hop instanceof DataOp)
				? 0.0
				: hopNetworkWeight * FederatedCostModel.computeNetworkCost(0);
		double fedSelfCost = baseSelfCost / Math.max(1, numOfWorkers) + fedOverhead;
		double resultDownloadCost = hopPlacementWeight
				* FederatedPlannerDpCostEstimator.computeDownloadNetworkCost(outputMemEstimate);

		final int enumerationLimit = 1 << numBothOutInputs;

			FederatedPlannerDpMemoTable.FedPlanVariants lOutFedPlanVariants = new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.FOUT);
			Map<List<FType>, OpCaps> oracleCache = new HashMap<>();

			boolean sawOracleFedFout = false;
			boolean sawAllowFedFout = false;
			boolean sawCanSatisfyFedInputs = false;
			boolean sawAllowCpLout = false;
			boolean sawAllowCpFout = false;
			boolean sawAllowFedLout = false;

			for (int i = 0; i < enumerationLimit; i++) {
				List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();
				List<FType> collectedFTypes = new ArrayList<>();
				List<Hop> collectedHops = new ArrayList<>();
				Map<Long, FType> fedInputTypeMap = new HashMap<>();
				int[] selectedBits = new int[numBothOutInputs];
				ExecType tWriteExec = null;
				FederatedOutput tWriteOut = null;
				boolean tWriteConflict = false;
				boolean tWriteSeen = false;
			// Costs from children, split by the parent's ExecType semantics.
			double childCostCPExec = 0; // Parent executes in CP; forwarding only from FOUT children.
			double childCostFEDExec = 0; // Parent executes in FED; forwarding only from LOUT children.

				for (int j = 0; j < numBothOutInputs; j++) {
					Hop inputHop = lOutfOutChildHops.get(j);
					final int bit = (i & (1 << j)) != 0 ? 1 : 0;
					selectedBits[j] = bit;
					final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
					FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
							childType);
	
					if (childPlan == null) {
						throw new DMLRuntimeException("Missing " + childType + " federated plan for child hop "
								+ inputHop.getHopID() + " (" + inputHop.getOpString()
								+ ") while enumerating parent " + hopID + " (" + hop.getOpString()
								+ "), privacy=" + privacyConstraint);
					}

					planChilds.add(Pair.of(inputHop.getHopID(), childType));
					collectedFTypes.add(childType == FederatedOutput.LOUT ? null : childPlan.getFType());
					collectedHops.add(inputHop);
					if (childType == FederatedOutput.FOUT)
						fedInputTypeMap.put(inputHop.getHopID(), childPlan.getFType());
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=%s exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
						childType, childPlan.getExecType(), childPlan.getFType()));
				if (enforceTReadConsistency && tWriteChildIds.contains(inputHop.getHopID())) {
					tWriteSeen = true;
					if (tWriteExec == null) {
						tWriteExec = childPlan.getExecType();
						tWriteOut = childType;
					} else if (tWriteExec != childPlan.getExecType() || tWriteOut != childType) {
						tWriteConflict = true;
					}
				}
				}

			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
						FederatedOutput.LOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + inputHop.getHopID()
							+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
							+ hop.getOpString() + "), privacy=" + privacyConstraint);
					}
					planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
					collectedFTypes.add(null);
					collectedHops.add(inputHop);
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=LOUT exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
						childPlan.getExecType(), childPlan.getFType()));
				if (enforceTReadConsistency && tWriteChildIds.contains(inputHop.getHopID())) {
					tWriteSeen = true;
					if (tWriteExec == null) {
						tWriteExec = childPlan.getExecType();
						tWriteOut = FederatedOutput.LOUT;
					} else if (tWriteExec != childPlan.getExecType() || tWriteOut != FederatedOutput.LOUT) {
						tWriteConflict = true;
					}
				}
				}

			for (int j = 0; j < numFoutOnlyInputs; j++) {
				Hop inputHop = fOUTOnlyinputHops.get(j);
				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
						FederatedOutput.FOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + inputHop.getHopID()
							+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
							+ hop.getOpString() + "), privacy=" + privacyConstraint);
				}
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				collectedFTypes.add(childPlan.getFType());
				collectedHops.add(inputHop);
				fedInputTypeMap.put(inputHop.getHopID(), childPlan.getFType());
				FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
						"[Planner] parent=%d (%s) child=%d (%s) type=FOUT exec=%s fType=%s",
						hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
						childPlan.getExecType(), childPlan.getFType()));
				if (enforceTReadConsistency && tWriteChildIds.contains(inputHop.getHopID())) {
					tWriteSeen = true;
					if (tWriteExec == null) {
						tWriteExec = childPlan.getExecType();
						tWriteOut = FederatedOutput.FOUT;
					} else if (tWriteExec != childPlan.getExecType() || tWriteOut != FederatedOutput.FOUT) {
						tWriteConflict = true;
					}
				}
			}

			if (enforceTReadConsistency) {
				if (!tWriteSeen) {
					continue;
				}
				if (tWriteConflict) {
					continue;
				}
			}
			final boolean hasTWriteRequirement = enforceTReadConsistency;
			for (int j = 0; j < numBothOutInputs; j++) {
				Hop inputHop = lOutfOutChildHops.get(j);
				int bit = selectedBits[j];
				childCostCPExec += childCumulativeCost[j][bit] + childForwardingCostToCP[j] * bit;
				double fedForwardingCost = childForwardingCostToFED[j] * (1 - bit);
				childCostFEDExec += childCumulativeCost[j][bit] + fedForwardingCost;
			}
			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				childCostCPExec += lOUTOnlychildCumulativeCost.get(j);
				double fedForwardingCost = lOUTOnlychildForwardingCostToFED.get(j);
				childCostFEDExec += lOUTOnlychildCumulativeCost.get(j) + fedForwardingCost;
			}
			for (int j = 0; j < numFoutOnlyInputs; j++) {
				childCostCPExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCostToCP.get(j);
				childCostFEDExec += fOUTOnlychildCumulativeCost.get(j);
			}

				OracleUtils.OracleDecision oracleDecision = OracleUtils.decideWithOracle(
						hop, privacyConstraint, collectedHops, collectedFTypes,
						oracleFacade, oracleCache, rewireTable);
				OpCaps caps = oracleDecision.caps();
				boolean canSatisfyFedInputs = FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(
						hop, fedInputTypeMap);
				if (DISALLOW_CPFOUT_ON_RECOMPILE && isRecompileRegion(hop)) {
					boolean hasPlannedFedInput = fedInputTypeMap != null && !fedInputTypeMap.isEmpty();
					if (!hasPlannedFedInput)
						canSatisfyFedInputs = false;
				}

				FType oracleLogicalFType = oracleDecision.logicalFType();
				FType cpLogicalFType = OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(
						hop, oracleLogicalFType, rewireTable, numOfWorkers);
				cpLogicalFType = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(hop, cpLogicalFType);
				double cpUploadCost = hopPlacementWeight * FederatedPlannerDpCostEstimator.computeUploadNetworkCost(
						outputMemEstimate, cpLogicalFType, numOfWorkers);

				ExecPlacementPolicy.Decision placementDecision = ExecPlacementPolicy.decide(
						hop, privacyConstraint, oracleLogicalFType, caps);
				boolean derivedFedFout = shouldEnableDerivedFedFout(
						hop, privacyConstraint, fedInputTypeMap, caps, placementDecision);
				if (derivedFedFout) {
					placementDecision.allowFED_FOUT = true;
				}
				// Align DP with MinST: when runtime does not support FED/FOUT for this op,
				// disallow both CP_FOUT and FED_FOUT candidates.
				if (caps != null && caps.reason() == ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME) {
					placementDecision.allowCP_FOUT = false;
					placementDecision.allowFED_FOUT = false;
					derivedFedFout = false;
				}
				if (DISALLOW_CPFOUT_ON_RECOMPILE && isRecompileRegion(hop)) {
					placementDecision.allowCP_FOUT = false;
				}
				if (caps != null && caps.exec() == ExecType.FED && caps.placement() == FederatedOutput.FOUT) {
					sawOracleFedFout = true;
				}
				sawAllowCpLout |= placementDecision.allowCP_LOUT;
				sawAllowCpFout |= placementDecision.allowCP_FOUT;
				sawAllowFedLout |= placementDecision.allowFED_LOUT;
				sawAllowFedFout |= placementDecision.allowFED_FOUT;
				sawCanSatisfyFedInputs |= canSatisfyFedInputs;

				// FED Exec, FOUT placement
				if (canSatisfyFedInputs && placementDecision.allowFED_FOUT
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.FED, FederatedOutput.FOUT, tWriteExec, tWriteOut))) {
					double fedFoutCost = fedSelfCost + childCostFEDExec
							+ derivedFedFoutBoundaryCost(derivedFedFout, cpUploadCost, resultDownloadCost);
					FederatedPlannerDpMemoTable.FedPlan fedFOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
						fedFoutCost,
						fOutFedPlanVariants, planChilds);
					fedFOutPlan.setExecType(ExecType.FED);
					fedFOutPlan.setFType(derivedFedFout ? cpLogicalFType : oracleLogicalFType);
					fedFOutPlan.setDerivedFedFout(derivedFedFout);
					fOutFedPlanVariants.addFedPlan(fedFOutPlan);
				}

			// FED Exec, LOUT placement
			if (canSatisfyFedInputs && placementDecision.allowFED_LOUT
					&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
							ExecType.FED, FederatedOutput.LOUT, tWriteExec, tWriteOut))) {
					FederatedPlannerDpMemoTable.FedPlan fedLOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
						fedSelfCost + childCostFEDExec + resultDownloadCost,
						lOutFedPlanVariants, planChilds);
					fedLOutPlan.setExecType(ExecType.FED);
					fedLOutPlan.setFType(cpLogicalFType);
					lOutFedPlanVariants.addFedPlan(fedLOutPlan);
				}

			// CP Exec, LOUT/FOUT placement
				if (placementDecision.allowCP_LOUT
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.CP, FederatedOutput.LOUT, tWriteExec, tWriteOut))) {
						FederatedPlannerDpMemoTable.FedPlan cpLOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
							cpSelfCost + childCostCPExec,
							lOutFedPlanVariants, planChilds);
						cpLOutPlan.setExecType(ExecType.CP);
						cpLOutPlan.setFType(cpLogicalFType);
						lOutFedPlanVariants.addFedPlan(cpLOutPlan);
					}
				if (placementDecision.allowCP_FOUT
						&& canGenerateCpfoutCandidateSafe(hop, fedInputTypeMap)
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.CP, FederatedOutput.FOUT, tWriteExec, tWriteOut))) {
					FederatedPlannerDpMemoTable.FedPlan cpFOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
							cpSelfCost + childCostCPExec + cpUploadCost,
							fOutFedPlanVariants, planChilds);
					cpFOutPlan.setExecType(ExecType.CP);
					cpFOutPlan.setFType(cpLogicalFType);
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
						+ ", FOUT candidates=" + hasFOutPlan
						+ ", allowCpLout=" + sawAllowCpLout
						+ ", allowCpFout=" + sawAllowCpFout
						+ ", allowFedLout=" + sawAllowFedLout
						+ ", oracleFedFout=" + sawOracleFedFout
						+ ", allowFedFout=" + sawAllowFedFout
						+ ", canSatisfyFedInputs=" + sawCanSatisfyFedInputs + ")");
			}
		}

	private static void enumerateFederatedDataOp(DataOp dataOp, FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.HopCommon hopCommon) {
		FType baseFType = FederatedTypePropagator.deriveFType(dataOp);
		FederatedPlannerUtils.registerFedInitVar(dataOp.getName(), baseFType,
			FederatedPlannerUtils.deriveFedInitSignature(dataOp));

		FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon,
				FederatedOutput.FOUT);
		FederatedPlannerDpMemoTable.FedPlan fedPlan = new FederatedPlannerDpMemoTable.FedPlan(0.0, fOutFedPlanVariants,
				Collections.emptyList());
		fedPlan.setExecType(ExecType.FED);
		fedPlan.setFType(baseFType);
		fOutFedPlanVariants.addFedPlan(fedPlan);
		memoTable.addFedPlanVariants(dataOp.getHopID(), FederatedOutput.FOUT, fOutFedPlanVariants);
	}

	private static boolean enumerateTransientReadDataOp(DataOp dataOp, List<Hop> childHops,
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.HopCommon hopCommon) {
		if (dataOp == null || dataOp.getOp() != Types.OpOpData.TRANSIENTREAD) {
			return false;
		}

		Set<Long> tWriteChildIds = collectTransientWriteChildIds(dataOp, childHops);
		if (tWriteChildIds.isEmpty()) {
			return false;
		}

		double baseSelfCost = FederatedPlannerDpCostEstimator.computeHopCost(hopCommon);

		boolean allowLOUT = true;
		boolean allowFOUT = true;
		FType loutFType = null;
		FType foutFType = null;
		double loutCost = baseSelfCost;
		double foutCost = baseSelfCost;
		List<Pair<Long, FederatedOutput>> loutChilds = new ArrayList<>();
		List<Pair<Long, FederatedOutput>> foutChilds = new ArrayList<>();

		for (Long childId : tWriteChildIds) {
			FederatedPlannerDpMemoTable.FedPlan loutPlan = memoTable.getFedPlanAfterPrune(childId,
					FederatedOutput.LOUT);
			if (loutPlan == null) {
				allowLOUT = false;
			} else {
				FType childFType = loutPlan.getFType();
				if (childFType != null) {
					if (loutFType == null) {
						loutFType = childFType;
					} else if (loutFType != childFType) {
						allowLOUT = false;
					}
				}
				loutCost += loutPlan.getCumulativeCostPerParents();
				loutChilds.add(Pair.of(childId, FederatedOutput.LOUT));
			}

			FederatedPlannerDpMemoTable.FedPlan foutPlan = memoTable.getFedPlanAfterPrune(childId,
					FederatedOutput.FOUT);
			if (foutPlan == null || foutPlan.getFType() == null) {
				allowFOUT = false;
			} else {
				FType childFType = foutPlan.getFType();
				if (foutFType == null) {
					foutFType = childFType;
				} else if (foutFType != childFType) {
					allowFOUT = false;
				}
				foutCost += foutPlan.getCumulativeCostPerParents();
				foutChilds.add(Pair.of(childId, FederatedOutput.FOUT));
			}
		}

		if (!allowLOUT && !allowFOUT) {
			throw new DMLRuntimeException("No valid federated plan for hop " + dataOp.getHopID()
					+ " (" + dataOp.getOpString() + ") based on transient write placements");
		}

		if (allowLOUT) {
			FederatedPlannerDpMemoTable.FedPlanVariants lOutFedPlanVariants =
					new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan loutPlan =
					new FederatedPlannerDpMemoTable.FedPlan(loutCost, lOutFedPlanVariants, loutChilds);
			loutPlan.setExecType(ExecType.CP);
			loutPlan.setFType(loutFType);
			lOutFedPlanVariants.addFedPlan(loutPlan);
			lOutFedPlanVariants.pruneFedPlans();
			memoTable.addFedPlanVariants(dataOp.getHopID(), FederatedOutput.LOUT, lOutFedPlanVariants);
		}

		if (allowFOUT) {
			FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants =
					new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan foutPlan =
					new FederatedPlannerDpMemoTable.FedPlan(foutCost, fOutFedPlanVariants, foutChilds);
			foutPlan.setExecType(ExecType.FED);
			foutPlan.setFType(foutFType);
			fOutFedPlanVariants.addFedPlan(foutPlan);
			fOutFedPlanVariants.pruneFedPlans();
			memoTable.addFedPlanVariants(dataOp.getHopID(), FederatedOutput.FOUT, fOutFedPlanVariants);
		}

		return true;
	}

	private static Set<Long> collectTransientWriteChildIds(Hop hop, List<Hop> childHops) {
		Set<Long> matches = new LinkedHashSet<>();
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD) {
			return matches;
		}
		if (childHops == null || childHops.isEmpty()) {
			return matches;
		}
		String hopName = hop.getName();
		Set<Long> fallback = new LinkedHashSet<>();
		for (Hop childHop : childHops) {
			if (!(childHop instanceof DataOp)
					|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTWRITE) {
				continue;
			}
			fallback.add(childHop.getHopID());
			if (hopName != null && hopName.equals(childHop.getName())) {
				matches.add(childHop.getHopID());
			}
		}
		if (matches.isEmpty()) {
			matches.addAll(fallback);
		}
		return matches;
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

	private static boolean isTReadConsistentWithTWrite(ExecType execType, FederatedOutput fedOutType,
			ExecType tWriteExec, FederatedOutput tWriteOut) {
		if (tWriteExec == null || tWriteOut == null) {
			return false;
		}
		if (tWriteOut == FederatedOutput.LOUT) {
			return execType == ExecType.CP && fedOutType == FederatedOutput.LOUT;
		}
		if (tWriteOut == FederatedOutput.FOUT) {
			return execType == ExecType.FED && fedOutType == FederatedOutput.FOUT;
		}
		return false;
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

	private static double placementTransferWeight(FederatedPlannerDpMemoTable.HopCommon hopCommon) {
		if (hopCommon == null)
			return 1.0;
		return hopCommon.getComputeWeight() * hopCommon.getMultiplicity();
	}

	private static boolean shouldEnableDerivedFedFout(Hop hop, Privacy privacy,
			Map<Long, FType> fTypeMap, OpCaps caps, ExecPlacementPolicy.Decision decision) {
		if (decision == null || decision.allowFED_FOUT || !decision.allowFED_LOUT)
			return false;
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
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

	private static double derivedFedFoutBoundaryCost(boolean derivedFedFout, double cpUploadCost,
			double resultDownloadCost) {
		return derivedFedFout ? cpUploadCost + resultDownloadCost : 0.0;
	}

		private static boolean canGenerateCpfoutCandidateSafe(Hop hop, Map<Long, FType> fTypeMap) {
			try {
				if (hop == null)
					return false;
				// Root CP->FOUT materialization is still legal when an anchor exists, even if the hop has
				// no FED parents (e.g., to keep the final result federated for privacy constraints).
				List<Hop> parents = hop.getParent();
				if (parents == null || parents.isEmpty()) {
					if (fTypeMap != null && !fTypeMap.isEmpty()) {
						for (FType fType : fTypeMap.values()) {
							if (fType == null || (fType != FType.PART && fType != FType.OTHER))
								return true;
						}
						return false;
					}
					return FederatedPlannerUtils.getUniqueFedInitVarName() != null;
				}
				return FederatedRefedPolicy.canGenerateCpfoutCandidateFromFTypes(hop, fTypeMap);
			}
			catch (DMLRuntimeException ex) {
				return false;
			}
		}

		private static void populateParentChildUploadHintsFromRewire(
				Map<Long, Set<Long>> parentChildUploadHints,
				Map<Long, List<Hop>> rewireTable,
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
			if (parentChildUploadHints == null || rewireTable == null || rewireTable.isEmpty()
					|| hopCommonTable == null || hopCommonTable.isEmpty())
				return;

		for (FederatedPlannerDpMemoTable.HopCommon hopCommon : hopCommonTable.values()) {
			if (hopCommon == null)
				continue;
			Hop parentHop = hopCommon.getHopRef();
			if (parentHop == null || parentHop.getInput() == null || parentHop.getInput().isEmpty())
				continue;

			for (Hop inputHop : parentHop.getInput()) {
				if (!(inputHop instanceof DataOp) ||
						((DataOp) inputHop).getOp() != Types.OpOpData.TRANSIENTREAD)
					continue;
				List<Hop> transReadSources = rewireTable.get(inputHop.getHopID());
				if (transReadSources == null || transReadSources.isEmpty())
					continue;

				boolean hasTransientWriteSource = false;
				for (Hop sourceHop : transReadSources) {
					if (sourceHop instanceof DataOp &&
							((DataOp) sourceHop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
						hasTransientWriteSource = true;
						break;
					}
				}
				if (!hasTransientWriteSource)
					continue;

				TransTableRewireUtils.markParentChildUploadHint(
						parentChildUploadHints, parentHop.getHopID(), inputHop.getHopID());
			}
		}
	}

	private static int[] resolveParentInputIndices(Hop parentHop, List<Hop> inputHops) {
		int[] indices = new int[inputHops == null ? 0 : inputHops.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = -1;
		}
		if (parentHop == null || parentHop.getInput() == null || inputHops == null || inputHops.isEmpty())
			return indices;

		List<Hop> parentInputs = parentHop.getInput();
		Map<Long, Integer> nextSearchStart = new HashMap<>();
		for (int i = 0; i < inputHops.size(); i++) {
			Hop inputHop = inputHops.get(i);
			if (inputHop == null)
				continue;
			long hopID = inputHop.getHopID();
			int start = nextSearchStart.getOrDefault(hopID, 0);
			int found = -1;
			for (int j = start; j < parentInputs.size(); j++) {
				Hop parentInput = parentInputs.get(j);
				if (parentInput != null && parentInput.getHopID() == hopID) {
					found = j;
					break;
				}
			}
			if (found < 0 && start > 0) {
				for (int j = 0; j < start && j < parentInputs.size(); j++) {
					Hop parentInput = parentInputs.get(j);
					if (parentInput != null && parentInput.getHopID() == hopID) {
						found = j;
						break;
					}
				}
			}
			indices[i] = found;
			if (found >= 0)
				nextSearchStart.put(hopID, found + 1);
		}
		return indices;
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

	private static boolean shouldAddFedForwardingForParentInput(Hop parentHop, Hop inputHop, int inputIndex,
			Map<Long, FType> inputFTypes, Map<Long, Set<Long>> parentChildUploadHints) {
		if (inputHop == null || inputHop.getDataType() == null)
			return false;
		// Even OPTIONAL inputs need to be transferred to FED sites for FED execution (e.g., broadcast vectors).
		// Non-matrix inputs are treated as embedded literals and do not incur separate forwarding costs.
		return inputHop.getDataType().isMatrix();
	}

	// Creates a dummy root node (fedplan) and selects the FedPlan with the minimum
	// cost to return.
	// The dummy root node does not have LOUT or FOUT.
	private static FederatedPlannerDpMemoTable.FedPlan getMinCostRootFedPlan(Set<Hop> progRootHopSet,
			FederatedPlannerDpMemoTable memoTable) {
		double cumulativeCost = 0;
		List<Pair<Long, FederatedOutput>> rootFedPlanChilds = new ArrayList<>();

		// Iterate over each Hop in the progRootHopSet
		for (Hop endHop : progRootHopSet) {
			// Retrieve the pruned FedPlan for LOUT and FOUT from the memo table
			FederatedPlannerDpMemoTable.FedPlan lOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(),
					FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan fOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(),
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

		return new FederatedPlannerDpMemoTable.FedPlan(cumulativeCost, null, rootFedPlanChilds);
	}

	/**
	 * Detects and resolves federated placement conflicts in a single BFS + single
	 * resolve pass.
	 */
	public static double detectAndResolveConflictFedPlan(
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan, int numOfWorkers) {

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
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan) {

		Map<Long, ConflictEntry> conflictCheckMap = new HashMap<>();
		Queue<FederatedPlannerDpMemoTable.FedPlan> queue = new ArrayDeque<>();
		Set<FederatedPlannerDpMemoTable.FedPlan> visited = new HashSet<>();

		for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(rootChild);
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
			FederatedPlannerDpMemoTable.FedPlan current = queue.poll();
			if (!visited.add(current))
				continue;

			Hop currentHop = current.getHopRef();

			for (Pair<Long, FederatedOutput> childEdge : current.getChildFedPlans()) {
				long childHopID = childEdge.getKey();
				FederatedOutput childOut = childEdge.getValue();

				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childEdge);
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
			FederatedPlannerDpMemoTable memoTable, Map<Long, ConflictEntry> conflictCheckMap) {

		Map<Long, ConflictEntry> conflictMap = new LinkedHashMap<>();

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();

			if (!entry.isTrulyConflicting())
				continue;

			FederatedPlannerDpMemoTable.FedPlan lOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan fOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);

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
			FederatedPlannerDpMemoTable memoTable, Map<Long, ConflictEntry> conflictMap, int numOfWorkers) {

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
			FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry, int numOfWorkers) {

		FederatedPlannerDpMemoTable.FedPlan lOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlan fOutPlan = memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);

		if (lOutPlan == null || fOutPlan == null) {
			throw new DMLRuntimeException("Expected both LOUT and FOUT plans for hop " + hopID);
		}

		double outputMem = FederatedCostModel.getEffectiveOutputMemEstimate(lOutPlan.getHopRef());
		double lOutUploadCost = FederatedPlannerDpCostEstimator.computeUploadNetworkCost(
				outputMem, lOutPlan.getFType(), numOfWorkers);
		double fOutDownloadCost = FederatedPlannerDpCostEstimator.computeDownloadNetworkCost(outputMem);

		double lOutAdditionalCost = 0.0;
		double fOutAdditionalCost = 0.0;
		boolean lOutNeedsForwarding = false;
		boolean fOutNeedsForwarding = false;

		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
			double lOutCumulativeCostShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
					lOutPlan.getCumulativeCost(), lOutPlan);
			double fOutCumulativeCostShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
					fOutPlan.getCumulativeCost(), fOutPlan);
			double lOutUploadCostShare = FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(
					lOutUploadCost, lOutPlan, parentPlan);
			double fOutDownloadCostShare = FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(
					fOutDownloadCost, fOutPlan, parentPlan);
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
					fOutAdditionalCost += fOutCumulativeCostShare - lOutCumulativeCostShare;

					if (parentOut == FederatedOutput.LOUT) {
						fOutNeedsForwarding = true;
					} else if (parentOut == FederatedOutput.FOUT) {
						lOutNeedsForwarding = true;
						lOutAdditionalCost -= lOutUploadCostShare;
						fOutAdditionalCost -= lOutUploadCostShare;
					}
				} else if (originalOut == FederatedOutput.FOUT) {
					lOutAdditionalCost += lOutCumulativeCostShare - fOutCumulativeCostShare;

					if (parentOut == FederatedOutput.FOUT) {
						lOutNeedsForwarding = true;
					} else if (parentOut == FederatedOutput.LOUT) {
						fOutNeedsForwarding = true;
						lOutAdditionalCost -= fOutDownloadCostShare;
						fOutAdditionalCost -= fOutDownloadCostShare;
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

		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
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
			FederatedPlannerDpMemoTable.FedPlan parentPlan, long hopID) {

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
		final java.util.LinkedHashSet<FederatedPlannerDpMemoTable.FedPlan> parents;
		boolean seenLOUT;
		boolean seenFOUT;

		ConflictEntry(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent) {
			this.firstSeenOut = out;
			this.parents = new java.util.LinkedHashSet<>();
			this.parents.add(parent);
			this.seenLOUT = (out == FederatedOutput.LOUT);
			this.seenFOUT = (out == FederatedOutput.FOUT);
		}

		void addUsage(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent) {
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
