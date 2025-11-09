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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlanRewireTransTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
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
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Baseline federated planner that compiles all hops
 * that support federated execution on federated inputs to
 * forced federated operations.
 */
public class FederatedPlannerFedCostBased extends AFederatedPlanner {
	@Override
	public void rewriteProgram( DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes )
	{
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FederatedMemoTable.FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateProgram(prog, memoTable, true);
		Map<Long, FEDInstruction.FederatedOutput> visited = new HashMap<>();

		List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = optimalPlan.getChildFedPlans();
		 for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : childFedPlanPairs) {
			FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			rewriteHop(childPlan, FEDInstruction.FederatedOutput.LOUT, memoTable, visited);
		 }
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FederatedMemoTable.FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateFunctionDynamic(function, memoTable, true);
		Map<Long, FEDInstruction.FederatedOutput> visited = new HashMap<>(); // hop ID, parent FOUTType
		rewriteHop(optimalPlan, FEDInstruction.FederatedOutput.LOUT, memoTable, visited);
	}

	private void rewriteHop(FederatedMemoTable.FedPlan optimalPlan, FEDInstruction.FederatedOutput parentFedOutType, FederatedMemoTable memoTable, Map<Long, FEDInstruction.FederatedOutput> visited) {
		long hopID = optimalPlan.getHopRef().getHopID();
		boolean hasPlacementConflict = false;

		if (visited.containsKey(hopID)){
			if (visited.get(hopID) == parentFedOutType){
				return;
			} else {
				// Todo: Conflict
				hasPlacementConflict = true;
				FederatedPlannerLogger.logPlacementConflict(optimalPlan.getHopRef(), null,
					visited.get(hopID), parentFedOutType, "REWRITE_HOP");
			}
		} else{
			visited.put(hopID, parentFedOutType);
		}

        for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
            FederatedMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);

			// Todo: Remove later
            // DEBUG: Check if getFedPlanAfterPrune returns null
            if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
                continue;
            }
            
			rewriteHop(childPlan, childFedPlanPair.getRight(), memoTable, visited);
        }

		if (optimalPlan.getFedOutType() == FEDInstruction.FederatedOutput.LOUT) {
			optimalPlan.setForcedExecType(ExecType.CP);
		} else {
			optimalPlan.setForcedExecType(ExecType.FED);
			
			// Todo 
			// 1) Only Matrix + Scalar
			// 2) Dummy Operations
		}

		// Todo: 이거 고민해봐야함. 어떻게 runtime이 구현되어 있는지.
		if (hasPlacementConflict){
			optimalPlan.setFederatedOutput(FEDInstruction.FederatedOutput.FOUT);
		} else {
			optimalPlan.setFederatedOutput(parentFedOutType);
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
		Map<Long, FType> fTypeMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlanRewireTransTable.rewireProgram(prog, rewireTable, hopCommonTable, privacyConstraintMap, fTypeMap, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet);

		for (long hopID : unRefTwriteSet) {
			// Todo (Future): Need to check unRefTwriteSet connecting to progRoot.
			progRootHopSet.add(hopCommonTable.get(hopID).getHopRef());
		}
		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();

		for (StatementBlock sb : prog.getStatementBlocks()) {
			enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					fTypeMap, unRefTwriteSet, fnStack, fedMap.size(), visitedHops);
		}

		FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

		// Todo : Fix & Update Conflict Resolve Plan
		// Detect conflicts in the federated plans where different FedPlans have
		// different FederatedOutput types
		// double additionalTotalCost = detectAndResolveConflictFedPlan(optimalPlan, memoTable);
		
		
		double additionalTotalCost = 0.0;
		System.out.println("[Todo]detectAndResolveConflictFedPlan call has been commented out.");

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
		Map<Long, FType> fTypeMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlanRewireTransTable.rewireFunctionDynamic(function, rewireTable, hopCommonTable, privacyConstraintMap, fTypeMap,
				fedMap, unRefTwriteSet, unRefSet, progRootHopSet);

		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();
		enumerateStatementBlock(function, null, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				fTypeMap, unRefTwriteSet, fnStack, fedMap.size(), visitedHops);

		FederatedMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

		// Detect conflicts in the federated plans where different FedPlans have
		// different FederatedOutput types
		// Todo : Fix & Update Conflict Resolve Plan
		// double additionalTotalCost = detectAndResolveConflictFedPlan(optimalPlan, memoTable);

		double additionalTotalCost = 0.0;
		System.out.println("[Todo]detectAndResolveConflictFedPlan call has been commented out.");
		
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
			Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
			Set<Long> unRefTwriteSet, Set<String> fnStack, int numOfWorkers, Set<Long> visitedHops) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			enumerateHopDAG(isb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);

			for (StatementBlock innerIsb : istmt.getIfBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);

			for (StatementBlock innerIsb : istmt.getElseBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			enumerateHopDAG(fsb.getFromHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			enumerateHopDAG(fsb.getToHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			if (fsb.getIncrementHops() != null) {
				enumerateHopDAG(fsb.getIncrementHops(), prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			}

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			enumerateHopDAG(wsb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);

			for (StatementBlock innerWsb : wstmt.getBody())
				enumerateStatementBlock(innerWsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					enumerateHopDAG(c, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
							fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
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
			Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap, Set<Long> unRefTwriteSet, 
			Set<String> fnStack, int numOfWorkers, Set<Long> visitedHops) {
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
						fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
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
							fTypeMap, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
				}
			}
		}

		// Enumerate the federated plan for the current Hop
		enumerateHop(hop, memoTable, hopCommonTable, rewireTable, privacyConstraintMap, 
			fTypeMap, unRefTwriteSet, numOfWorkers);

//		FederatedPlanRewireTransTable.logHopInfo(hop, privacyConstraintMap, fTypeMap, "enumerateHopDAG");

	}

	/**
	 * Enumerates federated execution plans for a given Hop.
	 * This method calculates the self cost and child costs for the Hop,
	 * generates federated plan variants for both LOUT and FOUT output types,
	 * and prunes redundant plans before adding them to the memo table.
	 */
	private static void enumerateHop(Hop hop, FederatedMemoTable memoTable, Map<Long, FederatedMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, Map<Long, Privacy> privacyConstraintMap,
			Map<Long, FType> fTypeMap, Set<Long> unRefTwriteSet, int numOfWorkers) {
		long hopID = hop.getHopID();
		List<Hop> childHops = new ArrayList<>(hop.getInput());
		int numParentHops = hop.getParent().size();
		boolean isTrans = false;

		if (hop instanceof DataOp){
			Types.OpOpData opType = ((DataOp) hop).getOp();
			if (opType == Types.OpOpData.TRANSIENTWRITE && !hop.getName().equals("__pred")) {
				List<Hop> transParentHops = rewireTable.get(hop.getHopID());
				if (transParentHops != null) {
					numParentHops += transParentHops.size();
					isTrans = true;
				}
			} else if (opType == Types.OpOpData.TRANSIENTREAD) {
				List<Hop> transChildHops = rewireTable.get(hop.getHopID());
				if (transChildHops != null) {
					childHops.addAll(transChildHops);
				}
				isTrans = true;
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
		double selfCost = FederatedPlanCostEstimator.computeHopCost(hopCommon);
		int numInputs = childHops.size();

		double[][] childCumulativeCost = new double[numInputs][2]; // # of child, LOUT/FOUT of child
		double[] childForwardingCost = new double[numInputs]; // # of child

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

		Privacy privacyConstraint = privacyConstraintMap.get(hopID);
		FType fType = fTypeMap.get(hopID);

//		if (isTrans) {
//			FedPlanVariants lOutFedPlanVariants = new FedPlanVariants(hopCommon, FederatedOutput.LOUT);
//			FedPlanVariants fOutFedPlanVariants = new FedPlanVariants(hopCommon, FederatedOutput.FOUT);
//
//			// TODO: If any child is LOUT/FOUT only, create transHop as LOUT/FOUT only as well. Need to verify if this is correct.
//			enumerateTransChildFedPlan(lOutFedPlanVariants, fOutFedPlanVariants, childHops, childCumulativeCost,
//					lOUTOnlyinputHops, lOUTOnlychildCumulativeCost, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
//					selfCost, numOfWorkers);
//
//			if (lOutFedPlanVariants.pruneFedPlans()){
//				memoTable.addFedPlanVariants(hopID, FederatedOutput.LOUT, lOutFedPlanVariants);
//			}
//			if (fOutFedPlanVariants.pruneFedPlans()){
//				memoTable.addFedPlanVariants(hopID, FederatedOutput.FOUT, fOutFedPlanVariants);
//			}
//		} else
		if (fType == null) {
			FederatedMemoTable.FedPlanVariants lOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);

			singleTypeEnumerateChildFedPlan(lOutFedPlanVariants, FederatedOutput.LOUT, childHops,
				childCumulativeCost, childForwardingCost, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
				lOUTOnlychildForwardingCost, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
				fOUTOnlychildForwardingCost, selfCost, numOfWorkers);

			lOutFedPlanVariants.pruneFedPlans();
			memoTable.addFedPlanVariants(hopID, FederatedOutput.LOUT, lOutFedPlanVariants);
		} else if (privacyConstraint == Privacy.PRIVATE || privacyConstraint == Privacy.PRIVATE_AGGREGATE
						||privacyConstraint == Privacy.PRIVATE_AGGREGATE_TO_PUBLIC){
			FederatedMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);

			singleTypeEnumerateChildFedPlan(fOutFedPlanVariants, FederatedOutput.FOUT, childHops,
				childCumulativeCost, childForwardingCost, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
				lOUTOnlychildForwardingCost, fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
				fOUTOnlychildForwardingCost, selfCost, numOfWorkers);

			fOutFedPlanVariants.pruneFedPlans();
			memoTable.addFedPlanVariants(hopID, FederatedOutput.FOUT, fOutFedPlanVariants);
		} else { // privacyConstraint == PUBLIC, fType != null >> both LOUT/FOUT are possible
			FederatedMemoTable.FedPlanVariants lOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);
			FederatedMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);

			enumerateChildFedPlan(lOutFedPlanVariants, fOutFedPlanVariants, childHops, childCumulativeCost,
				childForwardingCost, lOUTOnlyinputHops, lOUTOnlychildCumulativeCost,
				lOUTOnlychildForwardingCost,
				fOUTOnlyinputHops, fOUTOnlychildCumulativeCost, fOUTOnlychildForwardingCost, selfCost,
				numOfWorkers);

			lOutFedPlanVariants.pruneFedPlans();
			fOutFedPlanVariants.pruneFedPlans();

			memoTable.addFedPlanVariants(hopID, FederatedOutput.LOUT, lOutFedPlanVariants);
			memoTable.addFedPlanVariants(hopID, FederatedOutput.FOUT, fOutFedPlanVariants);
		}
	}

	/**
	 * Enumerates federated execution plans for initial child hops only.
	 * This method generates all possible combinations of federated output types
	 * (LOUT and FOUT)
	 * for the initial child hops and calculates their cumulative costs
	 */
	private static void enumerateChildFedPlan(FederatedMemoTable.FedPlanVariants lOutFedPlanVariants, FederatedMemoTable.FedPlanVariants fOutFedPlanVariants,
			List<Hop> childHops, double[][] childCumulativeCost, double[] childForwardingCost,
			List<Hop> lOUTOnlyinputHops, List<Double> lOUTOnlychildCumulativeCost,
			List<Double> lOUTOnlychildForwardingCost,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCost,
			double selfCost, int numOfWorkers) {
		// Iterate 2^n times, generating two FedPlans (LOUT, FOUT) each time.
		int numInputs = childHops.size();
		int numLoutOnlyInputs = lOUTOnlyinputHops.size();
		int numFoutOnlyInputs = fOUTOnlyinputHops.size();

		for (int i = 0; i < (1 << numInputs); i++) {
			double[] cumulativeCost = new double[] { selfCost, selfCost / numOfWorkers };
			List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();

			// LOUT and FOUT share the same planChilds in each iteration (only forwarding
			// cost differs).
			for (int j = 0; j < numInputs; j++) {
				Hop inputHop = childHops.get(j);
				// Calculate the bit value to decide between FOUT and LOUT for the current input
				final int bit = (i & (1 << j)) != 0 ? 1 : 0; // Determine the bit value (decides FOUT/LOUT)
				final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
				planChilds.add(Pair.of(inputHop.getHopID(), childType));

				// Update the cumulative cost for LOUT, FOUT
				cumulativeCost[0] += childCumulativeCost[j][bit] + childForwardingCost[j] * bit;
				cumulativeCost[1] += childCumulativeCost[j][bit] + childForwardingCost[j] * (1 - bit);
			}

			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
				// Update the cumulative cost for LOUT, FOUT
				cumulativeCost[0] += lOUTOnlychildCumulativeCost.get(j);
				cumulativeCost[1] += lOUTOnlychildCumulativeCost.get(j) + lOUTOnlychildForwardingCost.get(j);
			}

			for (int j = 0; j < numFoutOnlyInputs; j++) {
				Hop inputHop = fOUTOnlyinputHops.get(j);
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				// Update the cumulative cost for LOUT, FOUT
				cumulativeCost[0] += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCost.get(j);
				cumulativeCost[1] += fOUTOnlychildCumulativeCost.get(j);
			}

			lOutFedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(cumulativeCost[0], lOutFedPlanVariants, planChilds));
			fOutFedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(cumulativeCost[1], fOutFedPlanVariants, planChilds));
		}
	}

	private static void singleTypeEnumerateChildFedPlan(FederatedMemoTable.FedPlanVariants fedPlanVariants, FederatedOutput fedOutType,
			List<Hop> childHops, double[][] childCumulativeCost, double[] childForwardingCost,
			List<Hop> lOUTOnlyinputHops, List<Double> lOUTOnlychildCumulativeCost,
			List<Double> lOUTOnlychildForwardingCost,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCost, double selfCost, int numOfWorkers) {
		// Iterate 2^n times, generating two FedPlans (LOUT, FOUT) each time.
		int numInputs = childHops.size();
		int numLoutOnlyInputs = lOUTOnlyinputHops.size();
		int numFoutOnlyInputs = fOUTOnlyinputHops.size();

		for (int i = 0; i < (1 << numInputs); i++) {
			double cumulativeCost = fedOutType == FederatedOutput.LOUT ? selfCost : selfCost / numOfWorkers;
			List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();

			// LOUT and FOUT share the same planChilds in each iteration (only forwarding
			// cost differs).
			for (int j = 0; j < numInputs; j++) {
				Hop inputHop = childHops.get(j);
				// Calculate the bit value to decide between FOUT and LOUT for the current input
				final int bit = (i & (1 << j)) != 0 ? 1 : 0; // Determine the bit value (decides FOUT/LOUT)
				final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
				planChilds.add(Pair.of(inputHop.getHopID(), childType));

				// Update the cumulative cost for LOUT, FOUT
				cumulativeCost += childCumulativeCost[j][bit];
				cumulativeCost += fedOutType == FederatedOutput.LOUT ? childForwardingCost[j] * (bit)
						: childForwardingCost[j] * (1 - bit);
			}

			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
				// Update the cumulative cost for LOUT, FOUT
				cumulativeCost += lOUTOnlychildCumulativeCost.get(j);
				cumulativeCost += fedOutType == FederatedOutput.LOUT ? 0 : lOUTOnlychildForwardingCost.get(j);
			}

			for (int j = 0; j < numFoutOnlyInputs; j++) {
				Hop inputHop = fOUTOnlyinputHops.get(j);
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				// Update the cumulative cost for LOUT, FOUT
				cumulativeCost += fOUTOnlychildCumulativeCost.get(j);
				cumulativeCost += fedOutType == FederatedOutput.LOUT ? fOUTOnlychildForwardingCost.get(j) : 0;
			}

			fedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(cumulativeCost, fedPlanVariants, planChilds));
		}
	}

	/**
	 * Enumerates federated execution plans for a TRead/TWrite hop.
	 * This method calculates the cumulative costs for both LOUT and FOUT federated
	 * output types
	 * considering that TRead/TWrite hops have only one child (TWrite/Child of
	 * TWrite).
	 * Since TRead, TWrite and Child of TWrite have the same federated output type,
	 * it generates only
	 * a single plan for each output type
	 */
	private static void enumerateTransChildFedPlan(FederatedMemoTable.FedPlanVariants lOutFedPlanVariants,
			FederatedMemoTable.FedPlanVariants fOutFedPlanVariants,
			List<Hop> childHops, double[][] childCumulativeCost,
			List<Hop> lOUTOnlyinputHops, List<Double> lOUTOnlychildCumulativeCost,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			double selfCost, int numOfWorkers) {

		int numInputs = childHops.size();
		int numLoutOnlyInputs = lOUTOnlyinputHops.size();
		int numFoutOnlyInputs = fOUTOnlyinputHops.size();

		if (numLoutOnlyInputs > 0) {
			double lOUTcumulativeCost = selfCost;
			List<Pair<Long, FederatedOutput>> lOutTransPlanChilds = new ArrayList<>();

			for (int i = 0; i < numInputs; i++) {
				Hop inputHop = childHops.get(i);
				lOutTransPlanChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
				lOUTcumulativeCost += childCumulativeCost[i][0];
			}

			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				lOutTransPlanChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
				lOUTcumulativeCost += lOUTOnlychildCumulativeCost.get(j);
			}
			// Generate only a single plan for each output type as "TRead, TWrite and Child
			// of TWrite" have the same FedOutType
			lOutFedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(lOUTcumulativeCost, lOutFedPlanVariants, lOutTransPlanChilds));
			return;
		}

		if (numFoutOnlyInputs > 0) {
			double fOUTcumulativeCost = selfCost / numOfWorkers;
			List<Pair<Long, FederatedOutput>> fOutTransPlanChilds = new ArrayList<>();

			for (int i = 0; i < numInputs; i++) {
				Hop inputHop = childHops.get(i);
				fOutTransPlanChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				fOUTcumulativeCost += childCumulativeCost[i][1];
			}

			for (int j = 0; j < numFoutOnlyInputs; j++) {
				Hop inputHop = fOUTOnlyinputHops.get(j);
				fOutTransPlanChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				fOUTcumulativeCost += fOUTOnlychildCumulativeCost.get(j);
			}
			// Generate only a single plan for each output type as "TRead, TWrite and Child
			// of TWrite" have the same FedOutType
			fOutFedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(fOUTcumulativeCost, fOutFedPlanVariants, fOutTransPlanChilds));
			return;
		}

		double[] cumulativeCost = new double[] { selfCost, selfCost / numOfWorkers };
		List<Pair<Long, FederatedOutput>> lOutTransPlanChilds = new ArrayList<>();
		List<Pair<Long, FederatedOutput>> fOutTransPlanChilds = new ArrayList<>();

		for (int i = 0; i < numInputs; i++) {
			Hop inputHop = childHops.get(i);

			lOutTransPlanChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
			fOutTransPlanChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));

			cumulativeCost[0] += childCumulativeCost[i][0];
			cumulativeCost[1] += childCumulativeCost[i][1];
		}

		// Generate only a single plan for each output type as "TRead, TWrite and Child
		// of TWrite" have the same FedOutType
		lOutFedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(cumulativeCost[0], lOutFedPlanVariants, lOutTransPlanChilds));
		fOutFedPlanVariants.addFedPlan(new FederatedMemoTable.FedPlan(cumulativeCost[1], fOutFedPlanVariants, fOutTransPlanChilds));
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

	// Retrieves the cumulative and forwarding costs of the child hops and stores
	// them in arrays
	public static void getChildCosts(FederatedMemoTable.HopCommon hopCommon, FederatedMemoTable memoTable, List<Hop> inputHops,
			double[][] childCumulativeCost, double[] childForwardingCost, List<Hop> lOUTOnlyinputHops,
			List<Double> lOUTOnlychildCumulativeCost, List<Double> lOUTOnlychildForwardingCost,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCost) {

		Iterator<Hop> iterator = inputHops.iterator();
		int currentIndex = 0;

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
			childForwardingCost[currentIndex] = hopCommon.getChildForwardingWeight(childLOutFedPlan.getLoopContext())
					* childLOutFedPlan.getForwardingCostPerParents();
			currentIndex++;
		}

		for (int i = 0; i < lOUTOnlyinputHops.size(); i++) {
			Hop childHop = lOUTOnlyinputHops.get(i);
			long childHopID = childHop.getHopID();

			FederatedMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID, FederatedOutput.LOUT);
			
			if (childLOutFedPlan == null) {
				throw new RuntimeException("childLOutFedPlan is null for hopID: " + childHopID + " (see details above)");
			}
			lOUTOnlychildCumulativeCost.add(childLOutFedPlan.getCumulativeCostPerParents());
			lOUTOnlychildForwardingCost.add(hopCommon.getChildForwardingWeight(childLOutFedPlan.getLoopContext())
					* childLOutFedPlan.getForwardingCostPerParents());
		}

		for (int i = 0; i < fOUTOnlyinputHops.size(); i++) {
			Hop childHop = fOUTOnlyinputHops.get(i);
			long childHopID = childHop.getHopID();

			FederatedMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID, FederatedOutput.FOUT);

			if (childFOutFedPlan == null) {
				throw new RuntimeException("childFOutFedPlan is null for hopID: " + childHopID + " (see details above)");
			}
			fOUTOnlychildCumulativeCost.add(childFOutFedPlan.getCumulativeCostPerParents());
			fOUTOnlychildForwardingCost.add(hopCommon.getChildForwardingWeight(childFOutFedPlan.getLoopContext())
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
								.getChildForwardingWeight(confilctLOutFedPlan.getLoopContext())
								* confilctLOutFedPlan.getForwardingCostPerParents();
						fOutAdditionalCost -= conflictParentFedPlan
								.getChildForwardingWeight(confilctLOutFedPlan.getLoopContext())
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
		public double getChildForwardingWeight(List<Pair<Long, Double>> childLoopContext) {return fedPlanVariants.hopCommon.getChildForwardingWeight(childLoopContext);}
		public List<Pair<Long, Double>> getLoopContext() {return fedPlanVariants.hopCommon.getLoopContext();}
		public List<Pair<Long, FederatedOutput>> getChildFedPlans() {return childFedPlans;}
		public void setFederatedOutput(FederatedOutput fedOutType) {fedPlanVariants.hopCommon.hopRef.setFederatedOutput(fedOutType);}
		public void setForcedExecType(ExecType execType) {fedPlanVariants.hopCommon.hopRef.setForcedExecType(execType);}
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
		public void addFedPlan(FedPlan fedPlan) {_fedPlanVariants.add(fedPlan);}
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
		
		public double getChildForwardingWeight(List<Pair<Long, Double>> childLoopContext) {
			if (loopContext.isEmpty()) {
				return networkWeight;
			}

			double forwardingWeight = this.networkWeight;
			
			for (int i = 0; i < loopContext.size(); i++) {
				// Todo: 이상함. 공통 루프만 제거해야하는 것 아닌가?
				if (i >= childLoopContext.size() || loopContext.get(i).getLeft() != childLoopContext.get(i).getLeft()) {
					forwardingWeight /=loopContext.get(i).getRight();
				}
			}

			// Check if the innermost loops are the same
			return forwardingWeight;
		}
	}
}
}
