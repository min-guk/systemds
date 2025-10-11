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
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
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
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.FederatedPlanMinSTRewire;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FederatedMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.FederatedMemoTable.HopCommon;
import java.util.*;

public class FederatedPlanMinSTCostEstimator {
	// Default value is used as a reasonable estimate since we only need
	// to compare relative costs between different federated plans
	// Memory bandwidth for local computations (25 GB/s)
	private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
	// Network bandwidth for data transfers between federated sites (1 Gbps)
	private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
	private static final double DEFAULT_MBS_NETWORK_LATENCY = 0.001;

	/**
	 * Enumerates the entire DML program to generate federated execution plans.
	 * It processes each statement block, computes the optimal federated plan,
	 * detects and resolves conflicts, and optionally prints the plan tree.
	 *
	 * @param prog    The DML program to enumerate.
	 * @param isPrint A boolean indicating whether to print the federated plan tree.
	 */
	public static void estimateProgram(DMLProgram prog, FederatedPlanMinSTGraph graph, 
				Map<Long, List<Hop>> rewireTable, Set<Long> unRefTwriteSet, int numOfWorkers, boolean isPrint) {
		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();

		for (StatementBlock sb : prog.getStatementBlocks()) {
			estimateStatementBlock(sb, prog, graph, rewireTable, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		}

		return;
	}

	public static void estimateFunctionDynamic(FunctionStatementBlock function, FederatedPlanMinSTGraph graph,
			boolean isPrint) {
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlanMinSTRewire.rewireFunctionDynamic(function, rewireTable, graph, 
				fedMap, unRefTwriteSet, unRefSet, progRootHopSet);

		Set<String> fnStack = new HashSet<>();
		Set<Long> visitedHops = new HashSet<>();
		estimateStatementBlock(function, null, graph, rewireTable, 
				unRefTwriteSet, fnStack, fedMap.size(), visitedHops);
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
	public static void estimateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable, Set<Long> unRefTwriteSet, Set<String> fnStack,
			int numOfWorkers, Set<Long> visitedHops) {
		// TODO: VERIFY - Debug log for StatementBlock processing
		// System.out.println("[DEBUG] estimateStatementBlock: " + sb.getClass().getSimpleName() + " ID:" + sb.getSBID());

		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			estimateHopDAG(isb.getPredicateHops(), prog, graph, rewireTable, 
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops);

			for (StatementBlock innerIsb : istmt.getIfBody())
				estimateStatementBlock(innerIsb, prog, graph, rewireTable, 
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops);

			for (StatementBlock innerIsb : istmt.getElseBody())
				estimateStatementBlock(innerIsb, prog, graph, rewireTable, 
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			estimateHopDAG(fsb.getFromHops(), prog, graph, rewireTable, 
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			estimateHopDAG(fsb.getToHops(), prog, graph, rewireTable, 
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			if (fsb.getIncrementHops() != null) {
				estimateHopDAG(fsb.getIncrementHops(), prog, graph, rewireTable,
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			}

			for (StatementBlock innerFsb : fstmt.getBody())
				estimateStatementBlock(innerFsb, prog, graph, rewireTable, 
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			estimateHopDAG(wsb.getPredicateHops(), prog, graph, rewireTable, 
					unRefTwriteSet, fnStack, numOfWorkers, visitedHops);

			for (StatementBlock innerWsb : wstmt.getBody())
				estimateStatementBlock(innerWsb, prog, graph, rewireTable, 
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				estimateStatementBlock(innerFsb, prog, graph, rewireTable, 
						unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					estimateHopDAG(c, prog, graph, rewireTable, 
							unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			}
		}
	}

	/**
	 * Rewires and enumerates federated execution plans for a given Hop.
	 * This method processes all input nodes, rewires TWrite and TRead operations,
	 * and generates federated plan variants for both inner and outer code blocks.
	 */
	private static void estimateHopDAG(Hop hop, DMLProgram prog, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable, Set<Long> unRefTwriteSet,
			Set<String> fnStack, int numOfWorkers, Set<Long> visitedHops) {
		// TODO: VERIFY - Debug log for hop processing
		System.out.println("[DEBUG] estimateHopDAG: Hop " + hop.getHopID() + " (" + hop.getClass().getSimpleName() +
			", Name: " + hop.getName() + ")");

		// Process all input nodes first if not already in memo table

		List<Hop> childHops = new ArrayList<>(hop.getInput());

		// Todo: Check if is right
		if ((hop instanceof DataOp) && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			List<Hop> transChildHops = rewireTable.get(hop.getHopID());
			System.out.println("[DEBUG]   TRead - rewireTable children: " +
				(transChildHops == null ? "null" : transChildHops.stream().map(h -> String.valueOf(h.getHopID())).collect(java.util.stream.Collectors.joining(", "))));
			if (transChildHops != null) {
				childHops.addAll(transChildHops);
			}
		}

		for (Hop inputHop : childHops) {
			long inputHopID = inputHop.getHopID();
			// Bug fix: graph.contains() is always true because vertices are added during rewire phase
			// We should only check visitedHops to determine if we need to process this hop
			// Original condition: if (!graph.contains(inputHopID) && !visitedHops.contains(inputHopID))
			// This prevented cost calculation for most hops since graph.contains() was always true
			if (!visitedHops.contains(inputHopID)) {
				System.out.println("[DEBUG]   -> Visiting child Hop " + inputHopID);
				visitedHops.add(inputHopID);
				estimateHopDAG(inputHop, prog, graph, rewireTable, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
			} else {
				System.out.println("[DEBUG]   -> Skipping child Hop " + inputHopID + " (already visited)");
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

					estimateStatementBlock(fsb, prog, graph, rewireTable, unRefTwriteSet, fnStack, numOfWorkers, visitedHops);
				}
			}
		}

		// Enumerate the federated plan for the current Hop
		estimateHop(hop, graph, rewireTable, unRefTwriteSet, numOfWorkers);
	}

	/**
	 * Enumerates federated execution plans for a given Hop.
	 * This method calculates the self cost and child costs for the Hop,
	 * generates federated plan variants for both LOUT and FOUT output types,
	 * and prunes redundant plans before adding them to the memo table.
	 */
	private static void estimateHop(Hop hop, FederatedPlanMinSTGraph graph,
			Map<Long, List<Hop>> rewireTable, Set<Long> unRefTwriteSet, int numOfWorkers) {
		long hopID = hop.getHopID();
		Vertex vertex = graph.getVertex(hopID);

		// TODO: VERIFY - Check if vertex is null (should not happen if rewire worked correctly)
		if (vertex == null) {
			System.err.println("[ERROR] estimateHop: vertex is null for Hop " + hopID +
				" (Name: " + hop.getName() + ", Type: " + hop.getClass().getSimpleName() + ")");
			return;
		}

		// Operation Cost
		computeVertexCost(vertex);
		graph.setVertexCost(vertex);

		boolean isTRead = false;
		List<Hop> childHops = new ArrayList<>(hop.getInput());
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			isTRead = true;
			List<Hop> transChildHops = rewireTable.get(hop.getHopID());
			if (transChildHops != null) {
				childHops.addAll(transChildHops);
			}
		}

		// Network Cost(Rest of all)
		for (Hop childHop : childHops) {
			Vertex childVertex = graph.getVertex(childHop.getHopID());
			
			if (childVertex == null) {
				continue;
			}
			
			// Todo: 근데 TWrite끼리 충돌할 수도 있을 것 같은데?
			if(isTRead) {
				graph.addTransReadWriteEdgeWithNetCost(childVertex, childHop.getHopID(), vertex, hopID);
			}
			else {
				graph.addEdgeWithNetCost(childVertex, childHop.getHopID(), vertex, hopID);
			}
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
	public static void computeVertexCost(Vertex vertex) {
		Hop hop = vertex.getHopRef();
		double opCostWithWeight = 0, netCostWithoutWeight = 0;

		// TWrite and TRead are meta-data operations, hence selfCost is zero
		if (hop instanceof DataOp) {
			if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
				opCostWithWeight = netCostWithoutWeight = 0;
			} else if (((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
				opCostWithWeight = 0;
				netCostWithoutWeight = computeHopForwardingCost(hop.getOutputMemEstimate());
			} else {
				double opCost = computeOpCost(hop);
				opCostWithWeight = vertex.getOpWeight() * opCost;
				netCostWithoutWeight = computeHopForwardingCost(hop.getOutputMemEstimate());
			}
			vertex.setCost(opCostWithWeight, netCostWithoutWeight);
			return;
		}

		double opCost = computeOpCost(hop);
		opCostWithWeight = vertex.getOpWeight() * opCost;
		netCostWithoutWeight = computeHopForwardingCost(hop.getOutputMemEstimate());

		vertex.setCost(opCostWithWeight, netCostWithoutWeight);
		return;
	}

	/**
	 * Computes the cost for the current Hop node.
	 *
	 * @param currentHop The Hop node whose cost needs to be computed
	 * @return The total cost for the current node's operation
	 */
	private static double computeOpCost(Hop currentHop) {
		double computeCost = ComputeCost.getHOPComputeCost(currentHop);
		double inputAccessCost = computeHopMemoryAccessCost(currentHop.getInputMemEstimate());
		double ouputAccessCost = computeHopMemoryAccessCost(currentHop.getOutputMemEstimate());

		// Compute total cost assuming:
		// 1. Computation and input access can be overlapped (hence taking max)
		// 2. Output access must wait for both to complete (hence adding)
		double totalCost = Math.max(computeCost, inputAccessCost) + ouputAccessCost;

		return totalCost;
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
		// Bug fix: memSize can be -1 (unknown), 0, or negative
		// - getOutputMemEstimate() returns -1 when size is unknown
		// - This causes network cost to be negative or near-zero
		// - All network edges are skipped because netCost <= 0 in addEdgeWithNetCost
		double cost;
		if (memSize <= 0) {
			// Use a default minimum network cost to avoid skipping edges
			// This represents the minimum latency cost even for small data
			cost = DEFAULT_MBS_NETWORK_LATENCY;
		} else {
			cost = DEFAULT_MBS_NETWORK_LATENCY + (memSize / (1024 * 1024) / DEFAULT_MBS_NETWORK_BANDWIDTH);
		}
		return cost;
	}
}
