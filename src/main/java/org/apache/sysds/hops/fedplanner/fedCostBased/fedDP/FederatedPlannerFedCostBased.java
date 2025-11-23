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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
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
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.InitFEDInstruction;
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
	// Global privacy policy: never allow CP overrides for protected data unless this flag flips.
	private static final boolean ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA = false;

	@Override
	public void rewriteProgram( DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes )
	{
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FederatedMemoTable.FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateProgram(prog, memoTable, true);
		Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited = new HashMap<>();

		List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = optimalPlan.getChildFedPlans();
		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : childFedPlanPairs) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
			rewriteHop(childPlan, memoTable, visited);
		 }
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FederatedMemoTable.FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateFunctionDynamic(function, memoTable, true);
		Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited = new HashMap<>(); // hop ID, selected placement/exec

		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
			// Propagate the actual selected output type of the child plan (LOUT/FOUT)
			rewriteHop(childPlan, memoTable, visited);
		}
	}

	private void rewriteHop(FederatedMemoTable.FedPlan optimalPlan, FederatedMemoTable memoTable,
		Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited) {
		long hopID = optimalPlan.getHopRef().getHopID();
		boolean hasPlacementConflict = false;
		ExecType execType = optimalPlan.getExecType();
		FEDInstruction.FederatedOutput thisOutType = optimalPlan.getFedOutType();

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
					FederatedPlannerLogger.logExecTypeConflict(optimalPlan.getHopRef(),
						prev.getRight(), execType, pickExecType(prev.getRight(), execType), "REWRITE_HOP");
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

			rewriteHop(childPlan, memoTable, visited);
		}

		optimalPlan.setForcedExecType(resolvedExecType);

		if (hasPlacementConflict){
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
	public static FederatedMemoTable.FedPlan enumerateProgram(DMLProgram prog, FederatedMemoTable memoTable, boolean isPrint) {
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
		// double additionalTotalCost = detectAndResolveConflictFedPlan(optimalPlan, memoTable);
		
		
		double additionalTotalCost = 0.0;
		FederatedPlannerLogger.logInfoMessage("[Todo]detectAndResolveConflictFedPlan call has been commented out.");

		unRefSet.addAll(unRefTwriteSet);
		// Print the federated plan tree if requested
		if (isPrint) {
			FederatedPlannerLogger.printFedPlanTree(optimalPlan, unRefSet, memoTable, additionalTotalCost);
		}

		return optimalPlan;
	}

	public static FederatedMemoTable.FedPlan enumerateFunctionDynamic(FunctionStatementBlock function, FederatedMemoTable memoTable,
			boolean isPrint) {
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Map<Long, FederatedMemoTable.HopCommon> hopCommonTable = new HashMap<>();

		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlanRewireTransTable.rewireFunctionDynamic(function, rewireTable, hopCommonTable, privacyConstraintMap,
				fedMap, unRefTwriteSet, unRefSet, progRootHopSet);

		RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
		OracleFacade oracleFacade = new OracleFacade(registry);

		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();
		enumerateStatementBlock(function, null, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				unRefTwriteSet, fnStack, fedMap.size(), visitedHops, oracleFacade);

		FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

		// Detect conflicts in the federated plans where different FedPlans have
		// different FederatedOutput types
		// Todo : Fix & Update Conflict Resolve Plan
		// double additionalTotalCost = detectAndResolveConflictFedPlan(optimalPlan, memoTable);

		double additionalTotalCost = 0.0;
		FederatedPlannerLogger.logInfoMessage("[Todo]detectAndResolveConflictFedPlan call has been commented out.");
		
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

			enumerateHopDAG(isb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

			for (StatementBlock innerIsb : istmt.getIfBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

			for (StatementBlock innerIsb : istmt.getElseBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
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
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			enumerateHopDAG(wsb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);

			for (StatementBlock innerWsb : wstmt.getBody())
				enumerateStatementBlock(innerWsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
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
					FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(),
							fop.getFunctionName());

					enumerateStatementBlock(fsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops, oracleFacade);
				}
			}
		}

		// Enumerate the federated plan for the current Hop
		enumerateHop(hop, memoTable, hopCommonTable, rewireTable, privacyConstraintMap, 
			unRefTwriteSet, numOfWorkers, oracleFacade);

//		FederatedPlanRewireTransTable.logHopInfo(hop, privacyConstraintMap, "enumerateHopDAG");

	}

	/**
	 * Enumerates federated execution plans for a given Hop.
	 * This method calculates the self cost and child costs for the Hop,
	 * generates federated plan variants for both LOUT and FOUT output types,
	 * and prunes redundant plans before adding them to the memo table.
	 */
	private static void enumerateHop(Hop hop, FederatedMemoTable memoTable, Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, Map<Long, Privacy> privacyConstraintMap,
			Set<Long> unRefTwriteSet, int numOfWorkers, OracleFacade oracleFacade) {
		long hopID = hop.getHopID();
		List<Hop> childHops = new ArrayList<>(hop.getInput());
		int numParentHops = hop.getParent().size();

		if (hop instanceof DataOp){
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

		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCost = new ArrayList<>();

		List<Hop> fOUTOnlyinputHops = new ArrayList<>();
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCost = new ArrayList<>();

		// The self cost follows its own weight, while the forwarding cost follows the
		// parent's weight.
		FederatedPlanCostEstimator.getChildCosts(hopCommon, memoTable, childHops, childCumulativeCost,
				childForwardingCost, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost, lOUTOnlychildForwardingCost,
				fOUTOnlyinputHops, fOUTOnlychildCumulativeCost, fOUTOnlychildForwardingCost);

		// childCumulativeCost/childForwardingCost are treated as buffers sized by initialNumInputs;
		// only indices [0, numInputs) are populated after getChildCosts mutates childHops.
		int numInputs = childHops.size();
		int numLoutOnlyInputs = lOUTOnlyinputHops.size();
		int numFoutOnlyInputs = fOUTOnlyinputHops.size();
		Privacy privacyConstraint = privacyConstraintMap.get(hopID);
		if (privacyConstraint == null) {
			privacyConstraint = Privacy.PUBLIC;
		}
		String inputPrivacy = summarizeInputPrivacy(childHops, privacyConstraintMap);

		double cpSelfCost = baseSelfCost;
		double fedSelfCost = baseSelfCost / Math.max(1, numOfWorkers);
		double resultNetCost = FederatedPlanCostEstimator.computeHopForwardingCost(hop.getOutputMemEstimate());

		if (numInputs >= Integer.SIZE - 1) {
			throw new DMLRuntimeException("Too many inputs (" + numInputs + ") for federated plan enumeration; "
				+ "cannot enumerate 2^n combinations safely.");
		}
		if (numInputs > MAX_ENUM_INPUTS) {
			throw new DMLRuntimeException("Too many inputs (" + numInputs + ") for exhaustive federated plan enumeration; "
				+ "limit is " + MAX_ENUM_INPUTS + " until a heuristic fallback is implemented.");
		}
		final int enumerationLimit = 1 << numInputs;

		FederatedMemoTable.FedPlanVariants lOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);
		FederatedMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);
		Map<List<FType>, OpCaps> oracleCache = new HashMap<>();

		for (int i = 0; i < enumerationLimit; i++) {
			List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();
			List<FType> inFTypes = new ArrayList<>();
			// Costs from children, split by the parent's ExecType semantics.
			double childCostCPExec = 0;  // Parent executes in CP; forwarding only from FOUT children.
			double childCostFEDExec = 0; // Parent executes in FED; forwarding only from LOUT children.
			boolean isValid = true;

			for (int j = 0; j < numInputs; j++) {
				Hop inputHop = childHops.get(j);
				final int bit = (i & (1 << j)) != 0 ? 1 : 0;
				final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
				FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(), childType);

				if (childPlan == null) {
					isValid = false;
					break;
				}

				planChilds.add(Pair.of(inputHop.getHopID(), childType));
				inFTypes.add(childPlan.getFType());
				childCostCPExec += childCumulativeCost[j][bit] + childForwardingCost[j] * bit;
				childCostFEDExec += childCumulativeCost[j][bit] + childForwardingCost[j] * (1 - bit);
			}

			if (!isValid) {
				continue;
			}

			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(), FederatedOutput.LOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + inputHop.getHopID()
						+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
						+ hop.getOpString() + "), privacy=" + privacyConstraint);
				}
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
				inFTypes.add(childPlan.getFType());
				childCostCPExec += lOUTOnlychildCumulativeCost.get(j);
				childCostFEDExec += lOUTOnlychildCumulativeCost.get(j) + lOUTOnlychildForwardingCost.get(j);
			}

			for (int j = 0; j < numFoutOnlyInputs; j++) {
				Hop inputHop = fOUTOnlyinputHops.get(j);
				FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(), FederatedOutput.FOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + inputHop.getHopID()
						+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
						+ hop.getOpString() + "), privacy=" + privacyConstraint);
				}
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				inFTypes.add(childPlan.getFType());
				childCostCPExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCost.get(j);
				childCostFEDExec += fOUTOnlychildCumulativeCost.get(j);
			}

			List<FType> oracleKey = Collections.unmodifiableList(new ArrayList<>(inFTypes));
			OpCaps caps = oracleCache.computeIfAbsent(oracleKey, k -> oracleFacade.decide(hop, k));
			ExecType oracleExec = caps.exec();
			FederatedOutput placement = caps.placement();
			if (oracleExec != ExecType.CP && oracleExec != ExecType.FED) {
				throw new DMLRuntimeException("Unsupported exec type " + oracleExec + " for hop " + hopID
					+ " (" + hop.getOpString() + "), privacy=" + privacyConstraint + ", placement=" + placement
					+ ", inputs=" + inFTypes);
			}

			FType logicalFType = deriveLogicalFType(hop, caps);

			boolean oracleAllowsCPOnProtectedData = allowsCPOverride(privacyConstraint, caps);
			if (privacyConstraint != Privacy.PUBLIC && oracleExec == ExecType.CP && !oracleAllowsCPOnProtectedData) {
				throw new DMLRuntimeException("Privacy " + privacyConstraint + " disallows CP execution (requires FED/FOUT"
					+ (privacyConstraint == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC ? " or FED/LOUT after aggregation" : "")
					+ "), but Oracle returned CP-only caps (exec=" + oracleExec + ", placement=" + placement
					+ ") for hop " + hopID + " (" + hop.getOpString() + "), inputPrivacy=" + inputPrivacy + ")");
			}

			/*
			 * Privacy × placement quick matrix (keep in sync with the branches below and
			 * isAllowedByPrivacy):
			 *   PUBLIC: CP/LOUT, CP/FOUT, FED/FOUT (if oracle permits), FED/LOUT (run FED,
			 *           then gather to local).
			 *   PRIVATE & PRIVATE_AGGREGATE: FED/FOUT only.
			 *   PRIVATE_AGGREGATE_TO_PUBLIC: FED/FOUT (intermediate) plus FED/LOUT after aggregation to public.
			 */
			boolean privacyAllowsLOUT = isAllowedByPrivacy(privacyConstraint, oracleExec, FederatedOutput.LOUT);
			boolean privacyAllowsFOUT = isAllowedByPrivacy(privacyConstraint, oracleExec, FederatedOutput.FOUT);
			if ((placement == FederatedOutput.LOUT && !privacyAllowsLOUT)
				|| (placement == FederatedOutput.FOUT && !privacyAllowsFOUT)) {
				throw new DMLRuntimeException("Oracle placement " + placement + " (exec=" + oracleExec
					+ ") violates privacy " + privacyConstraint + " for hop " + hopID + " ("
					+ hop.getOpString() + ")");
			}

			if (privacyConstraint == Privacy.PUBLIC) {
				FederatedMemoTable.FedPlan cpLOutPlan = new FederatedMemoTable.FedPlan(cpSelfCost + childCostCPExec,
					lOutFedPlanVariants, planChilds);
				cpLOutPlan.setExecType(ExecType.CP);
				cpLOutPlan.setFType(logicalFType);
				if (isAllowedByPrivacy(privacyConstraint, cpLOutPlan.getExecType(), lOutFedPlanVariants.getFedOutType())) {
					lOutFedPlanVariants.addFedPlan(cpLOutPlan);
				}

				if (oracleExec == ExecType.CP) {
					FederatedMemoTable.FedPlan cpFOutPlan = new FederatedMemoTable.FedPlan(cpSelfCost + childCostCPExec + resultNetCost,
						fOutFedPlanVariants, planChilds);
					cpFOutPlan.setExecType(ExecType.CP);
					cpFOutPlan.setFType(logicalFType);
					if (isAllowedByPrivacy(privacyConstraint, cpFOutPlan.getExecType(), fOutFedPlanVariants.getFedOutType())) {
						fOutFedPlanVariants.addFedPlan(cpFOutPlan);
					}
				}

				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						FederatedMemoTable.FedPlan fedFOutPlan = new FederatedMemoTable.FedPlan(fedSelfCost + childCostFEDExec,
							fOutFedPlanVariants, planChilds);
						fedFOutPlan.setExecType(ExecType.FED);
						fedFOutPlan.setFType(logicalFType);
						if (isAllowedByPrivacy(privacyConstraint, fedFOutPlan.getExecType(), fOutFedPlanVariants.getFedOutType())) {
							fOutFedPlanVariants.addFedPlan(fedFOutPlan);
						}
					}

					FederatedMemoTable.FedPlan fedLOutPlan = new FederatedMemoTable.FedPlan(fedSelfCost + childCostFEDExec + resultNetCost,
						lOutFedPlanVariants, planChilds);
					fedLOutPlan.setExecType(ExecType.FED);
					fedLOutPlan.setFType(logicalFType);
					if (isAllowedByPrivacy(privacyConstraint, fedLOutPlan.getExecType(), lOutFedPlanVariants.getFedOutType())) {
						lOutFedPlanVariants.addFedPlan(fedLOutPlan);
					}
				}
			} else {
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						FederatedMemoTable.FedPlan fedFOutPlan = new FederatedMemoTable.FedPlan(fedSelfCost + childCostFEDExec,
							fOutFedPlanVariants, planChilds);
						fedFOutPlan.setExecType(ExecType.FED);
						fedFOutPlan.setFType(logicalFType);
						if (isAllowedByPrivacy(privacyConstraint, fedFOutPlan.getExecType(), fOutFedPlanVariants.getFedOutType())) {
							fOutFedPlanVariants.addFedPlan(fedFOutPlan);
						}
					}

					if (privacyConstraint == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC) {
						FederatedMemoTable.FedPlan fedLOutPlan = new FederatedMemoTable.FedPlan(fedSelfCost + childCostFEDExec + resultNetCost,
							lOutFedPlanVariants, planChilds);
						fedLOutPlan.setExecType(ExecType.FED);
						fedLOutPlan.setFType(logicalFType);
						if (isAllowedByPrivacy(privacyConstraint, fedLOutPlan.getExecType(), lOutFedPlanVariants.getFedOutType())) {
							lOutFedPlanVariants.addFedPlan(fedLOutPlan);
						}
					}
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

		FederatedMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);
		FederatedMemoTable.FedPlan fedPlan = new FederatedMemoTable.FedPlan(0.0, fOutFedPlanVariants, Collections.emptyList());
		fedPlan.setExecType(ExecType.FED);
		fedPlan.setFType(baseFType);
		fOutFedPlanVariants.addFedPlan(fedPlan);
		memoTable.addFedPlanVariants(dataOp.getHopID(), FederatedOutput.FOUT, fOutFedPlanVariants);
	}

	// TODO(FType policy):
	// deriveLogicalFType currently treats matrix/frame results as BROADCAST
	// unless the Oracle provides a "strong" federated layout hint.
	// In particular, we drop Oracle foutFType() == NF/LOCAL and normalize
	// them to BROADCAST. This is a conservative choice ("better a uniform
	// BROADCAST layout than broken mismatched layouts across workers"),
	// but it also overrides Oracle decisions like "this result is only
	// meaningful as LOCAL".
	//
	// Possible refinements (to revisit once OpCaps and the Oracle rules are
	// stable enough):
	//  * If oracleExec == CP and foutFType == LOCAL, keep LOCAL as-is.
	//  * If oracleExec == FED and placement == FOUT but foutFType == NF,
	//    decide explicitly whether to:
	//      - keep NF (treat as 'non-federated' logical result), or
	//      - promote NF -> BROADCAST for safety.
	//  * In general, trust Oracle for layout hints like LOCAL/ROW/COL/FULL,
	//    and only fall back to BROADCAST when no meaningful layout is known.
	//
	// Right now we always return BROADCAST in the ambiguous cases to avoid
	// subtle layout inconsistencies, at the cost of losing some precision
	// in the Oracle's foutFType() hints.
	private static FType deriveLogicalFType(Hop hop, OpCaps caps) {
		boolean isMatrixOrFrame = hop.getDataType().isMatrix() || hop.getDataType().isFrame();
		Optional<FType> foutTypeOpt = caps != null ? caps.foutFType() : Optional.empty();

		if (!isMatrixOrFrame) {
			return FType.LOCAL;
		}

		if (foutTypeOpt.isPresent()) {
			FType oracleFType = foutTypeOpt.get();
			if (oracleFType != FType.NF && oracleFType != FType.LOCAL) {
				return oracleFType;
			}
		}

		// Default to a federated-safe broadcast layout when no usable hint is available.
		return FType.BROADCAST;
	}

	private static boolean isAllowedByPrivacy(Privacy p, ExecType exec, FederatedOutput out) {
		switch (p) {
			case PUBLIC:
				return (exec == ExecType.CP || exec == ExecType.FED)
					&& (out == FederatedOutput.LOUT || out == FederatedOutput.FOUT);
			case PRIVATE:
			case PRIVATE_AGGREGATE:
				return exec == ExecType.FED && out == FederatedOutput.FOUT;
			case PRIVATE_AGGREGATE_TO_PUBLIC:
				return exec == ExecType.FED
					&& (out == FederatedOutput.FOUT || out == FederatedOutput.LOUT);
			default:
				return false;
		}
	}

	private static boolean allowsCPOverride(Privacy privacyConstraint, OpCaps caps) {
		// Policy gate: CP override is globally disabled for protected data. Flip
		// ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA only when the oracle can prove privacy guarantees.
		if (!ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA) {
			return false;
		}
		if (caps == null || privacyConstraint == null) {
			return false;
		}
		return false;
	}

	private static String summarizeInputPrivacy(List<Hop> inputs, Map<Long, Privacy> privacyMap) {
		if (inputs == null || inputs.isEmpty()) {
			return "[]";
		}

		List<String> parts = new ArrayList<>();
		for (Hop input : inputs) {
			Privacy p = privacyMap.get(input.getHopID());
			if (p == null) {
				FederatedPlannerLogger.logWarnMessage("Missing privacy entry for input hop "
					+ input.getHopID() + " (" + input.getOpString()
					+ "); treating as PUBLIC in summarizeInputPrivacy.");
				p = Privacy.PUBLIC;
			}
			if (p == Privacy.PUBLIC) {
				continue;
			}
			String opString = input.getOpString();
			if (opString == null) {
				opString = "";
			}
			parts.add(input.getHopID() + ":" + p + (opString.isEmpty() ? "" : "(" + opString + ")"));
		}

		if (parts.isEmpty()) {
			return "[all inputs PUBLIC]";
		}
		return "[" + String.join(", ", parts) + "]";
	}

	// Creates a dummy root node (fedplan) and selects the FedPlan with the minimum
	// cost to return.
	// The dummy root node does not have LOUT or FOUT.
	private static FederatedMemoTable.FedPlan getMinCostRootFedPlan(Set<Hop> progRootHopSet, FederatedMemoTable memoTable) {
		double cumulativeCost = 0;
		List<Pair<Long, FederatedOutput>> rootFedPlanChilds = new ArrayList<>();

		// Iterate over each Hop in the progRootHopSet
		for (Hop endHop : progRootHopSet) {
			// Retrieve the pruned FedPlan for LOUT and FOUT from the memo table
			FederatedMemoTable.FedPlan lOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(), FederatedOutput.LOUT);
			FederatedMemoTable.FedPlan fOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(), FederatedOutput.FOUT);

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
	 * Detects and resolves conflicts in federated plans starting from the root
	 * plan.
	 * This function performs a breadth-first search (BFS) to traverse the federated
	 * plan tree.
	 * It identifies conflicts where the same plan ID has different federated output
	 * types.
	 * For each conflict, it records the plan ID and its conflicting parent plans.
	 * The function ensures that each plan ID is associated with a consistent
	 * federated output type
	 * by resolving these conflicts iteratively.
	 *
	 * The process involves:
	 * - Using a map to track conflicts, associating each plan ID with its federated
	 * output type
	 * and a list of parent plans.
	 * - Storing detected conflicts in a linked map, each entry containing a plan ID
	 * and its
	 * conflicting parent plans.
	 * - Performing BFS traversal starting from the root plan, checking each child
	 * plan for conflicts.
	 * - If a conflict is detected (i.e., a plan ID has different output types), the
	 * conflicting plan
	 * is removed from the BFS queue and added to the conflict map to prevent
	 * duplicate calculations.
	 * - Resolving conflicts by ensuring a consistent federated output type across
	 * the plan.
	 * - Re-running BFS with resolved conflicts to ensure all inconsistencies are
	 * addressed.
	 */
	private static double detectAndResolveConflictFedPlan(FederatedMemoTable.FedPlan rootPlan, FederatedMemoTable memoTable) {
		// Map to track conflicts: maps a plan ID to its federated output type and list
		// of parent plans
		Map<Long, Pair<FederatedOutput, List<FederatedMemoTable.FedPlan>>> conflictCheckMap = new HashMap<>();

		// LinkedMap to store detected conflicts, each with a plan ID and its
		// conflicting parent plans
		LinkedHashMap<Long, List<FederatedMemoTable.FedPlan>> conflictLinkedMap = new LinkedHashMap<>();

		// LinkedMap for BFS traversal starting from the root plan (Do not use value
		// (boolean))
		LinkedHashMap<FederatedMemoTable.FedPlan, Boolean> bfsLinkedMap = new LinkedHashMap<>();
		bfsLinkedMap.put(rootPlan, true);

		// Array to store cumulative additional cost for resolving conflicts
		double[] cumulativeAdditionalCost = new double[] { 0.0 };

		while (!bfsLinkedMap.isEmpty()) {
			// Perform BFS to detect conflicts in federated plans
			while (!bfsLinkedMap.isEmpty()) {
				FederatedMemoTable.FedPlan currentPlan = bfsLinkedMap.keySet().iterator().next();
				bfsLinkedMap.remove(currentPlan);

				// Iterate over each child plan of the current plan
				for (Pair<Long, FederatedOutput> childPlanPair : currentPlan.getChildFedPlans()) {
					FederatedMemoTable.FedPlan childFedPlan = memoTable.getFedPlanAfterPrune(childPlanPair);

					if (childFedPlan == null) {
						// Todo: Handle Error
						FederatedPlannerLogger.logNullFedPlanError(childPlanPair.getLeft(), "Resolve Conflict");
					}

					// Check if the child plan ID is already visited
					if (conflictCheckMap.containsKey(childPlanPair.getLeft())) {
						// Retrieve the existing conflict pair for the child plan
						Pair<FederatedOutput, List<FederatedMemoTable.FedPlan>> conflictChildPlanPair = conflictCheckMap
								.get(childPlanPair.getLeft());
						// Add the current plan to the list of parent plans
						conflictChildPlanPair.getRight().add(currentPlan);

						// If the federated output type differs, a conflict is detected
						if (conflictChildPlanPair.getLeft() != childPlanPair.getRight()) {
							// If this is the first detection, remove conflictChildFedPlan from the BFS
							// queue and add it to the conflict linked map (queue)
							// If the existing FedPlan is not removed from the bfsqueue or both actions are
							// performed, duplicate calculations for the same FedPlan and its children occur
							if (!conflictLinkedMap.containsKey(childPlanPair.getLeft())) {
								conflictLinkedMap.put(childPlanPair.getLeft(), conflictChildPlanPair.getRight());
								bfsLinkedMap.remove(childFedPlan);
							}
						}
					} else {
						// If no conflict exists, create a new entry in the conflict check map
						List<FederatedMemoTable.FedPlan> parentFedPlanList = new ArrayList<>();
						parentFedPlanList.add(currentPlan);

						// Map the child plan ID to its output type and list of parent plans
						conflictCheckMap.put(childPlanPair.getLeft(),
								new ImmutablePair<>(childPlanPair.getRight(), parentFedPlanList));
						// Add the child plan to the BFS queue
						bfsLinkedMap.put(childFedPlan, true);
					}
				}
			}
			// Resolve these conflicts to ensure a consistent federated output type across
			// the plan
			// Re-run BFS with resolved conflicts
			bfsLinkedMap = FederatedPlanCostEstimator.resolveConflictFedPlan(memoTable, conflictLinkedMap,
					cumulativeAdditionalCost);
			conflictLinkedMap.clear();
		}

		// Return the cumulative additional cost for resolving conflicts
		return cumulativeAdditionalCost[0];
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
	 * Retrieves the cumulative and forwarding costs of the child hops and stores them in arrays.
	 * Note: this method mutates {@code inputHops} in place, removing children that have only
	 * FOUT or only LOUT plans and putting them into the respective lists so that {@code inputHops}
	 * retains only children with both plan variants. The caller must pre-size the cost arrays to
	 * the original {@code inputHops.size()}, but only the prefix matching the (possibly smaller)
	 * mutated {@code inputHops.size()} will be populated.
	 */
	public static void getChildCosts(FederatedMemoTable.HopCommon hopCommon, FederatedMemoTable memoTable, List<Hop> inputHops,
			double[][] childCumulativeCost, double[] childForwardingCost, List<Hop> lOUTOnlyinputHops,
			List<Double> lOUTOnlychildCumulativeCost, List<Double> lOUTOnlychildForwardingCost,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCost) {

		Hop parentHop = hopCommon.getHopRef();
		Iterator<Hop> iterator = inputHops.iterator();
		int currentIndex = 0;

		// Populate the cost buffers sequentially for children that retain both plan variants.
		// Indices beyond the mutated inputHops.size() are intentionally left untouched.
		while (iterator.hasNext()) {
			Hop childHop = iterator.next();
			long childHopID = childHop.getHopID();

			FederatedMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID, FederatedOutput.FOUT);
			if (childFOutFedPlan == null) {
				lOUTOnlyinputHops.add(childHop);
				iterator.remove();
				continue;
			}

			FederatedMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID, FederatedOutput.LOUT);
			if (childLOutFedPlan == null) {
				fOUTOnlyinputHops.add(childHop);
				iterator.remove();
				continue;
			}

			childCumulativeCost[currentIndex][0] = childLOutFedPlan.getCumulativeCostPerParents();
			childCumulativeCost[currentIndex][1] = childFOutFedPlan.getCumulativeCostPerParents();
			childForwardingCost[currentIndex] = hopCommon.computeForwardingWeightOfChild(childLOutFedPlan.getLoopContext())
					* childLOutFedPlan.getForwardingCostPerParents();
			currentIndex++;
		}

		for (int i = 0; i < lOUTOnlyinputHops.size(); i++) {
			Hop childHop = lOUTOnlyinputHops.get(i);
			long childHopID = childHop.getHopID();

			FederatedMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID, FederatedOutput.LOUT);
			
			if (childLOutFedPlan == null) {
				throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + childHopID + " ("
					+ childHop.getOpString() + ") while processing parent " + parentHop.getHopID() + " ("
					+ parentHop.getOpString() + ")");
			}
			lOUTOnlychildCumulativeCost.add(childLOutFedPlan.getCumulativeCostPerParents());
			lOUTOnlychildForwardingCost.add(hopCommon.computeForwardingWeightOfChild(childLOutFedPlan.getLoopContext())
					* childLOutFedPlan.getForwardingCostPerParents());
		}

		for (int i = 0; i < fOUTOnlyinputHops.size(); i++) {
			Hop childHop = fOUTOnlyinputHops.get(i);
			long childHopID = childHop.getHopID();

			FederatedMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID, FederatedOutput.FOUT);

			if (childFOutFedPlan == null) {
				throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + childHopID + " ("
					+ childHop.getOpString() + ") while processing parent " + parentHop.getHopID() + " ("
					+ parentHop.getOpString() + ")");
			}
			fOUTOnlychildCumulativeCost.add(childFOutFedPlan.getCumulativeCostPerParents());
			fOUTOnlychildForwardingCost.add(hopCommon.computeForwardingWeightOfChild(childFOutFedPlan.getLoopContext())
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
		if (memSize <= 0) return 0.0;
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

	/**
	 * Resolves conflicts in federated plans where different plans have different
	 * FederatedOutput types.
	 * This function traverses the list of conflicting plans in reverse order to
	 * ensure that conflicts
	 * are resolved from the bottom-up, allowing for consistent federated output
	 * types across the plan.
	 * It calculates additional costs for each potential resolution and updates the
	 * cumulative additional cost.
	 *
	 * @param memoTable                The FederatedMemoTable containing all
	 *                                 federated plan variants.
	 * @param conflictFedPlanLinkedMap A map of plan IDs to lists of parent plans
	 *                                 with conflicting federated outputs.
	 * @param cumulativeAdditionalCost An array to store the cumulative additional
	 *                                 cost incurred by resolving conflicts.
	 * @return A LinkedHashMap of resolved federated plans, marked with a boolean
	 *         indicating resolution status.
	 */
	public static LinkedHashMap<FederatedMemoTable.FedPlan, Boolean> resolveConflictFedPlan(FederatedMemoTable memoTable,
			LinkedHashMap<Long, List<FederatedMemoTable.FedPlan>> conflictFedPlanLinkedMap, double[] cumulativeAdditionalCost) {
		// LinkedHashMap to store resolved federated plans for BFS traversal.
		LinkedHashMap<FederatedMemoTable.FedPlan, Boolean> resolvedFedPlanLinkedMap = new LinkedHashMap<>();

		// Traverse the conflictFedPlanList in reverse order after BFS to resolve
		// conflicts
		for (Map.Entry<Long, List<FederatedMemoTable.FedPlan>> conflictFedPlanPair : conflictFedPlanLinkedMap.entrySet()) {
			long conflictHopID = conflictFedPlanPair.getKey();
			List<FederatedMemoTable.FedPlan> conflictParentFedPlans = conflictFedPlanPair.getValue();

			// Retrieve the conflicting federated plans for LOUT and FOUT types
			FederatedMemoTable.FedPlan confilctLOutFedPlan = memoTable.getFedPlanAfterPrune(conflictHopID, FederatedOutput.LOUT);
			FederatedMemoTable.FedPlan confilctFOutFedPlan = memoTable.getFedPlanAfterPrune(conflictHopID, FederatedOutput.FOUT);

			if (confilctLOutFedPlan == null || confilctFOutFedPlan == null) {
				// Todo: Handle Error
				FederatedPlannerLogger.logConflictResolutionError(conflictHopID, confilctLOutFedPlan, "Resolve Conflict");
				continue;
			}

			// Variables to store additional costs for LOUT and FOUT types
			double lOutAdditionalCost = 0;
			double fOutAdditionalCost = 0;

			// Flags to check if the plan involves network transfer
			// Network transfer cost is calculated only once, even if it occurs multiple
			// times
			boolean isLOutForwarding = false;
			boolean isFOutForwarding = false;

			// Determine the optimal federated output type based on the calculated costs
			FederatedOutput optimalFedOutType;

			// Iterate over each parent federated plan in the current conflict pair
			for (FederatedMemoTable.FedPlan conflictParentFedPlan : conflictParentFedPlans) {
				// Find the calculated FedOutType of the child plan
				Pair<Long, FederatedOutput> cacluatedConflictPlanPair = conflictParentFedPlan.getChildFedPlans()
						.stream()
						.filter(pair -> pair.getLeft().equals(conflictHopID))
						.findFirst()
						.orElseThrow(
								() -> new NoSuchElementException("No matching pair found for ID: " + conflictHopID));

				// CASE 1. Calculated LOUT / Parent LOUT / Current LOUT: Total cost remains
				// unchanged.
				// CASE 2. Calculated LOUT / Parent FOUT / Current LOUT: Total cost remains
				// unchanged, subtract net cost, add net cost later.
				// CASE 3. Calculated FOUT / Parent LOUT / Current LOUT: Change total cost,
				// subtract net cost.
				// CASE 4. Calculated FOUT / Parent FOUT / Current LOUT: Change total cost, add
				// net cost later.
				// CASE 5. Calculated LOUT / Parent LOUT / Current FOUT: Change total cost, add
				// net cost later.
				// CASE 6. Calculated LOUT / Parent FOUT / Current FOUT: Change total cost,
				// subtract net cost.
				// CASE 7. Calculated FOUT / Parent LOUT / Current FOUT: Total cost remains
				// unchanged, subtract net cost, add net cost later.
				// CASE 8. Calculated FOUT / Parent FOUT / Current FOUT: Total cost remains
				// unchanged.

				// Adjust LOUT, FOUT costs based on the calculated plan's output type
				if (cacluatedConflictPlanPair.getRight() == FederatedOutput.LOUT) {
					// When changing from calculated LOUT to current FOUT, subtract the existing
					// LOUT total cost and add the FOUT total cost
					// When maintaining calculated LOUT to current LOUT, the total cost remains
					// unchanged.
					fOutAdditionalCost += confilctFOutFedPlan.getCumulativeCostPerParents()
							- confilctLOutFedPlan.getCumulativeCostPerParents();

					if (conflictParentFedPlan.getFedOutType() == FederatedOutput.LOUT) {
						// (CASE 1) Previously, calculated was LOUT and parent was LOUT, so no network
						// transfer cost occurred
						// (CASE 5) If changing from calculated LOUT to current FOUT, network transfer
						// cost occurs, but calculated later
						isFOutForwarding = true;
					} else {
						// Previously, calculated was LOUT and parent was FOUT, so network transfer cost
						// occurred
						// (CASE 2) If maintaining calculated LOUT to current LOUT, subtract existing
						// network transfer cost and calculate later
						isLOutForwarding = true;
						lOutAdditionalCost -= confilctLOutFedPlan.getForwardingCostPerParents();

						// (CASE 6) If changing from calculated LOUT to current FOUT, no network
						// transfer cost occurs, so subtract it
						fOutAdditionalCost -= confilctLOutFedPlan.getForwardingCostPerParents();
					}
				} else {
					lOutAdditionalCost += confilctLOutFedPlan.getCumulativeCostPerParents()
							- confilctFOutFedPlan.getCumulativeCostPerParents();

					if (conflictParentFedPlan.getFedOutType() == FederatedOutput.FOUT) {
						isLOutForwarding = true;
					} else {
						isFOutForwarding = true;
						lOutAdditionalCost -= conflictParentFedPlan
								.computeForwardingWeightOfChild(confilctLOutFedPlan.getLoopContext())
								* confilctLOutFedPlan.getForwardingCostPerParents();
						fOutAdditionalCost -= conflictParentFedPlan
								.computeForwardingWeightOfChild(confilctLOutFedPlan.getLoopContext())
								* confilctLOutFedPlan.getForwardingCostPerParents();
					}
				}
			}

			// Add network transfer costs if applicable
			if (isLOutForwarding) {
				lOutAdditionalCost += confilctLOutFedPlan.getForwardingCost();
			}
			if (isFOutForwarding) {
				fOutAdditionalCost += confilctFOutFedPlan.getForwardingCost();
			}

			// Determine the optimal federated output type based on the calculated costs
			if (lOutAdditionalCost <= fOutAdditionalCost) {
				optimalFedOutType = FederatedOutput.LOUT;
				cumulativeAdditionalCost[0] += lOutAdditionalCost;
				resolvedFedPlanLinkedMap.put(confilctLOutFedPlan, true);
			} else {
				optimalFedOutType = FederatedOutput.FOUT;
				cumulativeAdditionalCost[0] += fOutAdditionalCost;
				resolvedFedPlanLinkedMap.put(confilctFOutFedPlan, true);
			}

			// Update only the optimal federated output type, not the cost itself or
			// recursively
			for (FederatedMemoTable.FedPlan conflictParentFedPlan : conflictParentFedPlans) {
				for (Pair<Long, FederatedOutput> childPlanPair : conflictParentFedPlan.getChildFedPlans()) {
					if (childPlanPair.getLeft() == conflictHopID && childPlanPair.getRight() != optimalFedOutType) {
						int index = conflictParentFedPlan.getChildFedPlans().indexOf(childPlanPair);
						conflictParentFedPlan.getChildFedPlans().set(index,
								Pair.of(childPlanPair.getLeft(), optimalFedOutType));
						break;
					}
				}
			}
		}
		return resolvedFedPlanLinkedMap;
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
		private double cumulativeCost;                  // Total cost = sum of selfCost + cumulativeCost of child plans
		private final FedPlanVariants fedPlanVariants;  // Reference to variant list
		private final List<Pair<Long, FederatedOutput>> childFedPlans;  // Child plan references
		private ExecType execType;
		private FType fType;

		public FedPlan(double cumulativeCost, FedPlanVariants fedPlanVariants, List<Pair<Long, FederatedOutput>> childFedPlans) {
			this.cumulativeCost = cumulativeCost;
			this.fedPlanVariants = fedPlanVariants;
			this.childFedPlans = childFedPlans;			
		}

		public Hop getHopRef() {return fedPlanVariants.hopCommon.getHopRef();}
		public long getHopID() {return fedPlanVariants.hopCommon.getHopRef().getHopID();}
		public FederatedOutput getFedOutType() {return fedPlanVariants.getFedOutType();}
		public double getCumulativeCost() {return cumulativeCost;}
		public double getCumulativeCostPerParents() {
			double cumulativeCostPerParents = cumulativeCost;
			int numOfParents = fedPlanVariants.hopCommon.getNumOfParents();
			if (numOfParents >= 2){
				cumulativeCostPerParents /= numOfParents;
			}
			return cumulativeCostPerParents;
		}
		public double getSelfCost() {return fedPlanVariants.hopCommon.getSelfCost();}
		public double getForwardingCost() {return fedPlanVariants.hopCommon.getForwardingCost();}
		public double getForwardingCostPerParents() {
			double forwardingCostPerParents = fedPlanVariants.hopCommon.getForwardingCost();
			int numOfParents = fedPlanVariants.hopCommon.getNumOfParents();
			if (numOfParents >= 2){
				forwardingCostPerParents /= numOfParents;
			}
			return forwardingCostPerParents;
		}
		public double getComputeWeight() {return fedPlanVariants.hopCommon.getComputeWeight();}
		public double getNetworkWeight() {return fedPlanVariants.hopCommon.getNetworkWeight();}
		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {return fedPlanVariants.hopCommon.computeForwardingWeightOfChild(childLoopContext);}
		public List<Pair<Long, Double>> getLoopContext() {return fedPlanVariants.hopCommon.getLoopContext();}
		public List<Pair<Long, FederatedOutput>> getChildFedPlans() {return childFedPlans;}
		public void setFederatedOutput(FederatedOutput fedOutType) {fedPlanVariants.hopCommon.hopRef.setFederatedOutput(fedOutType);}
		public void setForcedExecType(ExecType execType) {fedPlanVariants.hopCommon.hopRef.setForcedExecType(execType);}
		public ExecType getExecType() {return execType;}
		public void setExecType(ExecType execType) {this.execType = execType;}
		public FType getFType() {return fType;}
		public void setFType(FType fType) {this.fType = fType;}
	}

	/**
	 * Represents a collection of federated execution plan variants for a specific Hop and FederatedOutput.
	 * This class contains cost information and references to the associated plans.
	 * It uses HopCommon to store common properties and costs related to the Hop.
	 */
	public static class FedPlanVariants {
		protected HopCommon hopCommon;      // Common properties and costs for the Hop
		private final FederatedOutput fedOutType;  // Output type (FOUT/LOUT)
		protected List<FedPlan> _fedPlanVariants;  // List of plan variants

		public FedPlanVariants(HopCommon hopCommon, FederatedOutput fedOutType) {
			this.hopCommon = hopCommon;
			this.fedOutType = fedOutType;
			this._fedPlanVariants = new ArrayList<>();
		}

		public boolean isEmpty() {return _fedPlanVariants.isEmpty();}
		public void addFedPlan(FedPlan fedPlan) {
			if (fedPlan.getExecType() == null || fedPlan.getFType() == null) {
				throw new DMLRuntimeException("FedPlan missing execType or fType for hop "
					+ fedPlan.getHopID() + " (" + fedPlan.getHopRef().getOpString() + "), fedOutType="
					+ fedPlan.getFedOutType());
			}
			_fedPlanVariants.add(fedPlan);
		}
		public List<FedPlan> getFedPlanVariants() {return _fedPlanVariants;}
		public FederatedOutput getFedOutType() {return fedOutType;}

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
	 * This class holds a reference to the Hop and tracks its execution and network forwarding (transfer) costs.
	 * It also maintains the loop context information to properly calculate forwarding costs within loops.
	 */
	public static class HopCommon {
		protected final Hop hopRef; // Reference to the associated Hop
		protected double selfCost; // Cost of the hop's computation and memory access
		protected double forwardingCost; // Cost of forwarding the hop's output to its parent
		protected int numOfParents;
		protected double computeWeight; // Weight used to calculate cost based on hop execution frequency
		protected double networkWeight; // Weight used to calculate cost based on hop execution frequency
		protected List<Pair<Long, Double>> loopContext; // Loop context in which this hop exists

		public HopCommon(Hop hopRef, double computeWeight, double networkWeight, int numOfParents, List<Pair<Long, Double>> loopContext) {
			this.hopRef = hopRef;
			this.selfCost = 0;
			this.forwardingCost = 0;
			this.numOfParents = numOfParents;
			this.computeWeight = computeWeight;
			this.networkWeight = networkWeight;
			this.loopContext = loopContext != null ? new ArrayList<>(loopContext) : new ArrayList<>();
		}

		public Hop getHopRef() {return hopRef;}
		public double getSelfCost() {return selfCost;}
		public double getForwardingCost() {return forwardingCost;}
		public double getComputeWeight() {return computeWeight;}
		public double getNetworkWeight() {return networkWeight;}
		public int getNumOfParents() {return numOfParents;}
		public List<Pair<Long, Double>> getLoopContext() {return loopContext;}

		protected void setSelfCost(double selfCost) {this.selfCost = selfCost;}
		protected void setForwardingCost(double forwardingCost) {this.forwardingCost = forwardingCost;}
		protected void setNumOfParentHops(int numOfParentHops) {this.numOfParents = numOfParentHops;}
		
		/**
		 * Estimates how many times this parent's output is forwarded to a child by
		 * amortizing the parent's networkWeight over loops the child does not execute.
		 *
		 * Example:
		 *  parent loopContext = [(for1, 100), (while2, 10)]
		 *  childLoopContext  = [(for1, 100)]
		 *  => forwardingWeight = networkWeight / 10 (child result reused across while2 iterations)
		 */
		public double computeForwardingWeightOfChild(List<Pair<Long, Double>> childLoopContext) {
			// If this hop has no loop context, just use its networkWeight as-is.
			if (loopContext.isEmpty()) {
				return networkWeight;
			}

			// Build a map of the child's loop context keyed by loop ID (SBID).
			// This makes the computation robust to ordering differences between
			// parent and child loop stacks.
			Map<Long, Double> childLoopMap = new HashMap<>();
			if (childLoopContext != null) {
				for (Pair<Long, Double> p : childLoopContext) {
					childLoopMap.put(p.getLeft(), p.getRight());
				}
			}

			double forwardingWeight = networkWeight;

			// Divide out only those parent loops that the child does NOT belong to.
			// Interpretation:
			// - For loops present in both parent and child, we assume forwarding
			//   happens once per iteration (keep the loop weight).
			// - For loops present only in the parent, we assume the child result
			//   is reused across iterations, so we divide out that loop weight.
			for (Pair<Long, Double> p : loopContext) {
				long loopId = p.getLeft();
				double w = p.getRight();
				// Avoid dividing by zero/negative weights from degenerate loops
				if (!childLoopMap.containsKey(loopId) && w > 0) {
					forwardingWeight /= w;
				}
			}

			return forwardingWeight;
		}
	}
}
	public static class FederatedPlanRewireTransTable {
	    
	    private static final double DEFAULT_LOOP_WEIGHT = 10.0;
	    private static final double DEFAULT_IF_ELSE_WEIGHT = 0.5;

	    public static final String FED_MATRIX_IDENTIFIER = "matrix";
	    public static final String FED_FRAME_IDENTIFIER = "frame";

	    private static class FedWorkerContext {
	        final String host;
	        final int port;
	        final String filePath;
	        final FederatedData data;

	        FedWorkerContext(String host, int port, String filePath, FederatedData data) {
	            this.host = host;
	            this.port = port;
	            this.filePath = filePath;
	            this.data = data;
	        }
	    }

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

	    public static void rewireFunctionDynamic(FunctionStatementBlock function, Map<Long, List<Hop>> rewireTable,
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
	        rewireStatementBlock(function, null, visitedHops, rewireTable, hopCommonTable, outerTransTableList, null,
	                privacyConstraintMap,
	                fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, 1, 1, loopStack);
	    }

	    public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog, Set<Long> visitedHops,
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

	            rewireHopDAG(isb.getPredicateHops(), prog, visitedHops, rewireTable, hopCommonTable, newOuterTransTableList,
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
	                        privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
	                        networkWeight, parentLoopStack));

	            for (StatementBlock innerIsb : istmt.getElseBody())
	                elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
	                        hopCommonTable, newOuterTransTableList, elseFormerTransTable,
	                        privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
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
	            Hop incr = (fsb.getIncrementHops() != null) ? fsb.getIncrementHops().getInput().get(0) : new LiteralOp(1);

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
	            rewireHopDAG(fsb.getToHops(), prog, visitedHops, rewireTable, hopCommonTable, newOuterTransTableList, null,
	                    innerTransTable,
	                    privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
	                    networkWeight, currentLoopStack);

	            if (fsb.getIncrementHops() != null) {
	                rewireHopDAG(fsb.getIncrementHops(), prog, visitedHops, rewireTable, hopCommonTable,
	                        newOuterTransTableList, null, innerTransTable,
	                        privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
	                        networkWeight, currentLoopStack);
	            }
	            newFormerTransTable.putAll(innerTransTable);

	            for (StatementBlock innerFsb : fstmt.getBody())
	                newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
	                        hopCommonTable, newOuterTransTableList, newFormerTransTable,
	                        privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
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

	            rewireHopDAG(wsb.getPredicateHops(), prog, visitedHops, rewireTable, hopCommonTable, newOuterTransTableList,
	                    null, innerTransTable,
	                    privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
	                    networkWeight, currentLoopStack);
	            newFormerTransTable.putAll(innerTransTable);

	            for (StatementBlock innerWsb : wstmt.getBody())
	                newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
	                        hopCommonTable, newOuterTransTableList, newFormerTransTable,
	                        privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
	                        networkWeight, currentLoopStack));

	            // Wire UnRefTwrite to liveOutHops
	            wireUnRefTwriteToLiveOut(wsb, unRefTwriteSet, hopCommonTable, newFormerTransTable);
	        } else if (sb instanceof FunctionStatementBlock) {
	            FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
	            FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

	            for (StatementBlock innerFsb : fstmt.getBody())
	                newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
	                        hopCommonTable, newOuterTransTableList, newFormerTransTable,
	                        privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, computeWeight,
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

	    private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops, Map<Long, List<Hop>> rewireTable,
	            Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, List<Map<String, List<Hop>>> outerTransTableList,
	            Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
	            Map<Long, Privacy> privacyConstraintMap,
	            List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
	            Set<Hop> progRootHopSet,
	            Set<String> fnStack, double computeWeight, double networkWeight, List<Pair<Long, Double>> loopStack) {

	        if (hop.getInput() != null) {
	            for (Hop inputHop : hop.getInput()) {
	                long inputHopID = inputHop.getHopID();
	                if (!visitedHops.contains(inputHopID)) {
	                    visitedHops.add(inputHopID);
	                    rewireHopDAG(inputHop, prog, visitedHops, rewireTable, hopCommonTable, outerTransTableList,
	                            formerTransTable, innerTransTable,
	                            privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
	                            computeWeight, networkWeight, loopStack);
	                }
	            }
	        }

	        hopCommonTable.put(hop.getHopID(), new FederatedMemoTable.HopCommon(hop, computeWeight, networkWeight, 0, loopStack));

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
	                        newFormerTransTable.computeIfAbsent(inputArgs[i], k -> new ArrayList<>()).add(inputHops.get(i));
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
	            privacyConstraintMap.put(hop.getHopID(),
	                    getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap));
	            return;
	        }

	        rewireTransHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable, privacyConstraintMap,
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
	            Privacy privacy = getFedWorkerMetaData(fedMap, dataOp);
	            privacyConstraintMap.put(hop.getHopID(), privacy);
	        } else if (opType == Types.OpOpData.TRANSIENTWRITE) {
	            // Rewire TransWrite
	            innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
	            unRefTwriteSet.add(hop.getHopID());
	            // Propagate Privacy Constraint
	            privacyConstraintMap.put(hop.getHopID(), getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap));
	        } else if (opType == Types.OpOpData.TRANSIENTREAD) {
	            // Rewire TransRead
	            List<Hop> childHops = rewireTransRead(hopName, innerTransTable, formerTransTable, outerTransTableList);
	            // Handle rewire table (TransRead -> TransWrite)
	            rewireTable.put(hop.getHopID(), childHops);

	            // Todo: Handle exception when TRead has no Child (check why it's missing)
	            if (childHops == null || childHops.isEmpty()) {
	                FederatedPlannerLogger.logTransReadRewireDebug(hopName, hop.getHopID(), childHops, true, "RewireTransHop");
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

	            // Todo: Handle exception when TRead has no Filtered Child (check why it's missing)
	            if (filteredChildHops.isEmpty()) {
	                FederatedPlannerLogger.logFilteredChildHopsDebug(hopName, hop.getHopID(), filteredChildHops, true, "RewireTransHop");
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
	            privacyConstraintMap.put(hop.getHopID(),
	                    getPrivacyConstraint(hop, filteredChildHops, privacyConstraintMap));
	        } else {
	            privacyConstraintMap.put(hop.getHopID(),
	                    getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap));
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

	    // NOTE:
	    //  - fedMap is a global list of all (FederatedRange, FederatedData) pairs seen in the program.
	    //  - It is used as a rough approximation of the total number of federated workers (numOfWorkers).
	    //  - Privacy for each FEDERATED DataOp is computed locally from only the workers of that DataOp
	    //    (localWorkers), and is no longer required to match across different FEDERATED variables.
	    private static Privacy getFedWorkerMetaData(List<Pair<FederatedRange, FederatedData>> fedMap, DataOp initFedOp) {
	        // Address
	        Hop addressListHop = initFedOp.getInput(initFedOp.getParameterIndex("addresses"));
	        List<String> addressList = new ArrayList<>();
	        for (Hop addressHop : addressListHop.getInput()) {
	            addressList.add(addressHop.getName());
	        }

	        // Range
	        Hop rangeListHop = initFedOp.getInput(initFedOp.getParameterIndex("ranges"));
	        List<long[]> rangeList = new ArrayList<>();
	        for (Hop rangeHop : rangeListHop.getInput()) {
	            long beginRange = (long) Double.parseDouble(rangeHop.getInput(0).getName());
	            long endRange = (long) Double.parseDouble(rangeHop.getInput(1).getName());
	            rangeList.add(new long[] { beginRange, endRange });
	        }

	        // Type
	        String type = initFedOp.getInput(initFedOp.getParameterIndex("type")).getName();
	        Types.DataType fedDataType;

	        if (type.equalsIgnoreCase(FED_MATRIX_IDENTIFIER))
	            fedDataType = Types.DataType.MATRIX;
	        else
	            fedDataType = Types.DataType.FRAME;

	        // Local list for privacy calculation of this DataOp only
	        List<FedWorkerContext> localWorkers = new ArrayList<>();

	        // Init Fed Data
	        for (int i = 0; i < addressList.size(); i++) {
	            String address = addressList.get(i);
	            // We split address into url/ip, the port and file path of file to read
	            String[] parsedValues = InitFEDInstruction.parseURL(address);
	            String host = parsedValues[0];
	            int port = Integer.parseInt(parsedValues[1]);
	            String filePath = parsedValues[2];

	            long[] beginRange = rangeList.get(2 * i);
	            long[] endRange = rangeList.get(2 * i + 1);

	            try {
	                FederatedData federatedData = new FederatedData(fedDataType,
	                        new InetSocketAddress(InetAddress.getByName(host), port), filePath);
	                FederatedRange range = new FederatedRange(beginRange, endRange);
	                Pair<FederatedRange, FederatedData> pair = new ImmutablePair<>(range, federatedData);

	                fedMap.add(pair);      // Global worker count approximation
	                localWorkers.add(new FedWorkerContext(host, port, filePath, federatedData));
	            } catch (UnknownHostException e) {
	                throw new DMLRuntimeException("federated host was unknown: " + host, e);
	            }
	        }
	        Privacy privacyConstraint = null;
	        boolean hadPrivacyFailure = false;

	        // Request Privacy Constraints.
	        // Privacy is derived only from this DataOp's workers (localWorkers).
	        for (FedWorkerContext wctx : localWorkers) {
	            FederatedData data = wctx.data;
	            if (!data.isInitialized())
	                data.initFederatedData(FederationUtils.getNextFedDataID());

	            Future<FederatedResponse> future = data.requestPrivacyConstraints();
	            try {
	                FederatedResponse response = future.get(); // Get actual response from Future

	                if (response.isSuccessful()) {
	                    Object[] responseData = response.getData();
	                    String privacyConstraints = (String) responseData[0]; // Cast privacy constraint as string

	                    if (privacyConstraints == null) {
	                        String msg = "Worker " + wctx.host + ":" + wctx.port + " (" + wctx.filePath
	                            + ") returned null privacy constraints for FEDERATED data op '" + initFedOp.getName()
	                            + "' (hopID=" + initFedOp.getHopID() + ")";
	                        FederatedPlannerLogger.logErrorMessage("[FederatedPlanner] " + msg);
	                        hadPrivacyFailure = true;
	                        continue;
	                    }

	                    Privacy tempPrivacy = null;
	                    String pcLower = privacyConstraints.trim().toLowerCase();

	                    // Map to appropriate PrivacyConstraint value based on input string
	                    if (pcLower.equals("private")
	                            || pcLower.equals(Privacy.PRIVATE.toString().toLowerCase())) {
	                        tempPrivacy = Privacy.PRIVATE;
	                    } else if (pcLower.equals("private-aggregate") || pcLower.equals("private_aggregate")
	                            || pcLower.equals(Privacy.PRIVATE_AGGREGATE.toString().toLowerCase())) {
	                        tempPrivacy = Privacy.PRIVATE_AGGREGATE;
	                    } else if (pcLower.equals("private-aggregate-to-public")
	                            || pcLower.equals("private_aggregate_to_public")
	                            || pcLower.equals(Privacy.PRIVATE_AGGREGATE_TO_PUBLIC.toString().toLowerCase())) {
	                        tempPrivacy = Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
	                    } else if (pcLower.equals("public")
	                            || pcLower.equals(Privacy.PUBLIC.toString().toLowerCase())) {
	                        tempPrivacy = Privacy.PUBLIC;
	                    } else {
	                        throw new DMLRuntimeException("Invalid privacy constraint: " + privacyConstraints
	                                + ". Must be one of 'PRIVATE', 'PRIVATE_AGGREGATE', 'PRIVATE_AGGREGATE_TO_PUBLIC', 'PUBLIC'.");
	                    }

	                    if (privacyConstraint == null) {
	                        privacyConstraint = tempPrivacy;
	                    } else {
	                        privacyConstraint = joinPrivacy(privacyConstraint, tempPrivacy);
	                    }
	                } else {
	                    // Error handling: treat any unsuccessful response as fatal for planning
	                    String errorMsg = response.getErrorMessage();
	                    FederatedPlannerLogger.logErrorMessage(
	                        "Failed to request privacy constraints from " + wctx.host + ":" + wctx.port + " (" + wctx.filePath
	                            + ") for FEDERATED data op '" + initFedOp.getName() + "' (hopID=" + initFedOp.getHopID()
	                            + "): " + errorMsg);
	                    hadPrivacyFailure = true;
	                }
	            } catch (Exception e) {
	                // Exception handling: also treated as fatal for planning
	                String errorContext = "Failed to request privacy constraints from " + wctx.host + ":" + wctx.port + " ("
	                    + wctx.filePath + ") for FEDERATED data op '" + initFedOp.getName() + "' (hopID="
	                    + initFedOp.getHopID() + ")";
	                FederatedPlannerLogger.logException(errorContext, e);
	                hadPrivacyFailure = true;
	            }
	        }
			if (privacyConstraint == null || hadPrivacyFailure) {
				String errorMsg = "One or more federated workers failed to provide valid privacy constraints for FEDERATED data op '"
					+ initFedOp.getName() + "' (hopID=" + initFedOp.getHopID()
					+ "); cannot safely plan federated execution.";
				FederatedPlannerLogger.logErrorMessage("[FederatedPlanner] " + errorMsg + " Aborting planning.");
				throw new DMLRuntimeException(errorMsg);
			}
			return privacyConstraint;
	    }

	    private static Privacy joinPrivacy(Privacy a, Privacy b) {
	        if (a == null) return b;
	        if (b == null) return a;
	        if (a == b)    return a;

	        // Strongest privacy wins: PRIVATE > PRIVATE_AGGREGATE > PRIVATE_AGGREGATE_TO_PUBLIC > PUBLIC
	        if (a == Privacy.PRIVATE || b == Privacy.PRIVATE)
	            return Privacy.PRIVATE;
	        if (a == Privacy.PRIVATE_AGGREGATE || b == Privacy.PRIVATE_AGGREGATE)
	            return Privacy.PRIVATE_AGGREGATE;
	        if (a == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC || b == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC)
	            return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
	        return Privacy.PUBLIC;
	    }

	    private static Privacy getPrivacyConstraint(Hop hop, List<Hop> inputHops, Map<Long, Privacy> privacyMap) {
	        Privacy[] pc = new Privacy[inputHops.size()];
	        StringBuilder missingPrivacy = new StringBuilder();
	        for (int i = 0; i < inputHops.size(); i++) {
	            Hop inputHop = inputHops.get(i);
	            Privacy p = privacyMap.get(inputHop.getHopID());
	            if (p == null) {
	                if (missingPrivacy.length() > 0)
	                    missingPrivacy.append(", ");
	                missingPrivacy.append(inputHop.getHopID()).append(" (").append(inputHop.getOpString()).append(")");
	            }
	            pc[i] = p;
	        }

	        if (missingPrivacy.length() > 0) {
	            FederatedPlannerLogger.logWarnMessage(
	                "Missing privacy entry for input hop(s): " + missingPrivacy +
	                " while evaluating hop " + hop.getHopID() + " (" + hop.getOpString() + "); treating as PUBLIC.");
	        }

	        boolean hasPrivateAggreate = false;

	        for (Privacy p : pc) {
	            if (p == Privacy.PRIVATE) {
	                return Privacy.PRIVATE;
	            } else if (p == Privacy.PRIVATE_AGGREGATE) {
	                hasPrivateAggreate = true;
	            }
	        }

	        if (hasPrivateAggreate) {
	            if (hop instanceof AggUnaryOp || hop instanceof AggBinaryOp || hop instanceof QuaternaryOp) {
	                return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
	            } else if (hop instanceof TernaryOp) {
	                switch (((TernaryOp) hop).getOp()) {
	                    case MOMENT:
	                    case COV:
	                    case CTABLE:
	                    case INTERQUANTILE:
	                    case QUANTILE:
	                        return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
	                    default:
	                        return Privacy.PRIVATE_AGGREGATE;
	                }
	            } else if (hop instanceof ParameterizedBuiltinOp
	                    && ((ParameterizedBuiltinOp) hop).getOp() == ParamBuiltinOp.GROUPEDAGG) {
	                return Privacy.PRIVATE_AGGREGATE_TO_PUBLIC;
	            } else {
	                return Privacy.PRIVATE_AGGREGATE;
	            }
	        }

	        return Privacy.PUBLIC;
	    }
	    
	    private static void wireUnRefTwriteToLiveOut(StatementBlock sb, Set<Long> unRefTwriteSet,
	            Map<Long, FederatedMemoTable.HopCommon> hopCommonTable, Map<String, List<Hop>> newFormerTransTable) {
	        if (unRefTwriteSet.isEmpty())
	            return;

	        VariableSet genHops = sb.getGen();
	        VariableSet updatedHops = sb.variablesUpdated();
	        VariableSet liveOutHops = sb.liveOut();

	//        FederatedPlannerLogger.logWireUnRefTwriteStart(unRefTwriteSet.size());

	        Iterator<Long> unRefTwriteIterator = unRefTwriteSet.iterator();
	        while (unRefTwriteIterator.hasNext()) {
	            Long unRefTwriteHopID = unRefTwriteIterator.next();
	            Hop unRefTwriteHop = hopCommonTable.get(unRefTwriteHopID).getHopRef();
	            String unRefTwriteHopName = unRefTwriteHop.getName();

	            if (liveOutHops.containsVariable(unRefTwriteHopName)) {
	                continue;
	            }

			if (unRefTwriteHop instanceof FunctionOp || genHops.containsVariable(unRefTwriteHopName) || updatedHops.containsVariable(unRefTwriteHopName)) {
				String bestLiveOutHopName = null;
				int bestPriority = Integer.MAX_VALUE;
				int bestScore = Integer.MAX_VALUE;
				int bestNameScore = Integer.MAX_VALUE;

				Iterator<String> liveOutHopsIterator = liveOutHops.getVariableNames().iterator();
				while (liveOutHopsIterator.hasNext()) {
					String liveOutHopName = liveOutHopsIterator.next();
					List<Hop> liveOutHopsList = newFormerTransTable.get(liveOutHopName);

					if (liveOutHopsList != null && !liveOutHopsList.isEmpty()) {
						Hop representativeLiveOutHop = liveOutHopsList.get(0);
						CompatibilityResult compatResult = calculateCompatibilityScore(unRefTwriteHop, representativeLiveOutHop, hopCommonTable);

						if (compatResult.priority < bestPriority
								|| (compatResult.priority == bestPriority && compatResult.score < bestScore)
								|| (compatResult.priority == bestPriority && compatResult.score == bestScore
									&& compatResult.nameScore < bestNameScore)) {
							bestPriority = compatResult.priority;
							bestScore = compatResult.score;
							bestNameScore = compatResult.nameScore;
							bestLiveOutHopName = liveOutHopName;
						}
					}
				}

				if (bestLiveOutHopName == null) {
					throw new DMLRuntimeException("No liveOutHops found for " + unRefTwriteHopName + " (hopID="
						+ unRefTwriteHop.getHopID() + ", opcode=" + unRefTwriteHop.getOpString() + ")");
				}

				List<Hop> bestLiveOutHopsList = newFormerTransTable.get(bestLiveOutHopName);
				List<Hop> copyLiveOutHopsList = new ArrayList<>(bestLiveOutHopsList);
				copyLiveOutHopsList.add(unRefTwriteHop);
				newFormerTransTable.put(bestLiveOutHopName, copyLiveOutHopsList);
				unRefTwriteIterator.remove();
			}
		}
	}

	// 호환성 결과를 담는 클래스
	private static class CompatibilityResult {
		final int priority;
		final int score;
		final int nameScore;
		
		CompatibilityResult(int priority, int score, int nameScore) {
			this.priority = priority;
			this.score = score;
			this.nameScore = nameScore;
		}
	}

	private static CompatibilityResult calculateCompatibilityScore(Hop unRefTwriteHop, Hop liveOutHop, 
	                                                              Map<Long, FederatedMemoTable.HopCommon> hopCommonTable) {
		int nameScore = getMatchingPriority(unRefTwriteHop.getName(), liveOutHop.getName());
		boolean sameDataType = unRefTwriteHop.getDataType() == liveOutHop.getDataType() && 
		                      unRefTwriteHop.getValueType() == liveOutHop.getValueType();
		
		if (sameDataType) {
			return new CompatibilityResult(1, 0, nameScore);
		}
		
		double dimSimilarity = calculateDimensionSimilarity(unRefTwriteHop, liveOutHop);
		if (dimSimilarity > 0) {
			int dimScore = (int)Math.round((1 - dimSimilarity) * 100);
			return new CompatibilityResult(2, dimScore, nameScore);
		}
		
		double commonChildMemEstimate = findCommonChildrenMemEstimate(unRefTwriteHop, liveOutHop, hopCommonTable);
		if (commonChildMemEstimate > 0) {
			int childScore = (int)Math.max(0, 10000 - Math.min(commonChildMemEstimate, 10000));
			return new CompatibilityResult(3, childScore, nameScore);
		}

		return new CompatibilityResult(4, 0, nameScore);
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
	        double ratio1 = (dim1_1 == 0 || dim2_1 == 0) ? 0 : Math.min(dim1_1, dim2_1) / (double)Math.max(dim1_1, dim2_1);
	        double ratio2 = (dim1_2 == 0 || dim2_2 == 0) ? 0 : Math.min(dim1_2, dim2_2) / (double)Math.max(dim1_2, dim2_2);
	        
	        // 평균 유사성
	        return (ratio1 + ratio2) / 2.0;
	    }

	    // 공통 child들의 메모리 추정치 계산 (재귀적 탐색, depth 5 제한)
	    private static double findCommonChildrenMemEstimate(Hop hop1, Hop hop2, Map<Long, FederatedMemoTable.HopCommon> hopCommonTable) {
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
