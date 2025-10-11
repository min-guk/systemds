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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.hops.fedplanner.FederatedMemoTable.FedPlan;
import org.apache.commons.lang3.tuple.Pair;	
import org.apache.sysds.hops.Hop;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;

/**
 * Baseline federated planner that compiles all hops
 * that support federated execution on federated inputs to
 * forced federated operations.
 */
public class FederatedPlanMinSTCut extends AFederatedPlanner {
	@Override
	public void rewriteProgram( DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes )
	{
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();

		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlanMinSTRewire.rewireProgram(prog, rewireTable, graph, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet);
		for (long hopID : unRefTwriteSet) {
			progRootHopSet.add(graph.getHopRef(hopID));
		}
		
		graph.setNumOfWorkers(fedMap.size());
		FederatedPlanMinSTCostEstimator.estimateProgram(prog, graph, rewireTable, 
				unRefTwriteSet, fedMap.size(), true);
		
		graph.getOptimalPlan();
		FederatedPlannerLogger.logOptimalPlan(graph, true);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedMemoTable memoTable = new FederatedMemoTable();
		FedPlan optimalPlan = FederatedPlanCostEnumerator.enumerateFunctionDynamic(function, memoTable, true);
		Map<Long, FEDInstruction.FederatedOutput> visited = new HashMap<>(); // hop ID, parent FOUTType
	}
}
