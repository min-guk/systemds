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
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTCut extends AFederatedPlanner {
	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
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
		Map<Long, FType> fTypeMap = new HashMap<>();
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex.getDataType() == null)
				continue;
			Hop hop = vertex.getHopRef();
			if (hop != null && hop.getFederatedOutput() == FederatedOutput.FOUT)
				fTypeMap.put(vertex.getHopID(), vertex.getDataType());
		}
		FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
		FederatedPlannerLogger.logOptimalPlan(graph, true);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedPlanMinSTGraph graph = new FederatedPlanMinSTGraph();
		FederatedPlanMinSTCostEstimator.estimateFunctionDynamic(function, graph, true);
		graph.getOptimalPlan();
		Map<Long, FType> fTypeMap = new HashMap<>();
		for (Vertex vertex : graph.getMemoTable().values()) {
			if (vertex.getDataType() == null)
				continue;
			Hop hop = vertex.getHopRef();
			if (hop != null && hop.getFederatedOutput() == FederatedOutput.FOUT)
				fTypeMap.put(vertex.getHopID(), vertex.getDataType());
		}
		FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
		FederatedPlannerLogger.logOptimalPlan(graph, true);
	}
}
