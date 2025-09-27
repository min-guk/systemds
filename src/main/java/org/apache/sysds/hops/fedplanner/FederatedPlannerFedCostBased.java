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

package org.apache.sysds.hops.fedplanner;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.hops.fedplanner.FederatedMemoTable.FedPlan;
import org.apache.commons.lang3.tuple.Pair;

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
		FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateProgram(prog, memoTable, true);
		Map<Long, FEDInstruction.FederatedOutput> visited = new HashMap<>();

		List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = optimalPlan.getChildFedPlans();
		 for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : childFedPlanPairs) {
			FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			rewriteHop(childPlan, FEDInstruction.FederatedOutput.LOUT, memoTable, visited);
		 }
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateFunctionDynamic(function, memoTable, true);
		Map<Long, FEDInstruction.FederatedOutput> visited = new HashMap<>(); // hop ID, parent FOUTType
		rewriteHop(optimalPlan, FEDInstruction.FederatedOutput.LOUT, memoTable, visited);
	}

	private void rewriteHop(FedPlan optimalPlan, FEDInstruction.FederatedOutput parentFedOutType, FederatedMemoTable memoTable, Map<Long, FEDInstruction.FederatedOutput> visited) {
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
            FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);

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
}
