/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Test-only Cartesian probe of graph assignments and candidate realizations.
 * This is a production-surface comparator, not an independent legality oracle. */
public final class ProductionJointPlanSpaceProbe {
	public enum Status { BOUNDED_COMPLETE, UNKNOWN }

	public record JointPlan(Map<CompiledHopKey,PlacementState> assignment,
		List<CandidateSelectionReceipt> receipts) {
		public JointPlan {
			assignment = Collections.unmodifiableMap(new LinkedHashMap<>(assignment));
			receipts = List.copyOf(receipts);
		}
	}

	public record Result(Status status, String reason, List<JointPlan> plans) {
		public Result {
			Objects.requireNonNull(status, "status");
			reason = Objects.requireNonNull(reason, "reason");
			plans = List.copyOf(plans);
		}
	}

	private ProductionJointPlanSpaceProbe() { }

	/** No selector policy, component pruning, row objective, or canonical row collapse. */
	public static Result enumerate(PlacementAnalysis analysis, long maxRawCombinations) {
		Objects.requireNonNull(analysis, "analysis");
		if(maxRawCombinations < 1)
			throw new IllegalArgumentException("maxRawCombinations must be positive");
		NeutralPlacementGraph graph = analysis.graph();
		if(!graph.relocationActions().isEmpty())
			return new Result(Status.UNKNOWN,
				"Relocation-choice alternatives are not exposed by this probe", List.of());
		if(!graph.derivedFoutMaterializationActions().isEmpty())
			return new Result(Status.UNKNOWN,
				"Derived-FOUT materialization alternatives are not exposed by this probe", List.of());
		List<NeutralPlacementGraph.Node> nodes = graph.decisionNodes();
		BigInteger assignmentCount = BigInteger.ONE;
		for(NeutralPlacementGraph.Node node : nodes)
			assignmentCount = assignmentCount.multiply(BigInteger.valueOf(node.legalAlternatives().size()));
		if(assignmentCount.compareTo(BigInteger.valueOf(maxRawCombinations)) > 0)
			return new Result(Status.UNKNOWN, "Assignment domain exceeds explicit probe bound", List.of());
		Accumulator result = new Accumulator(maxRawCombinations);
		enumerateAssignments(analysis, graph, nodes, 0, new LinkedHashMap<>(), result);
		if(result.overflow)
			return new Result(Status.UNKNOWN, "Joint receipt domain exceeds explicit probe bound", List.of());
		if(result.unknown)
			return new Result(Status.UNKNOWN, "Receipt-free nonlocal node has no proven physical authority", List.of());
		return new Result(Status.BOUNDED_COMPLETE,
			"Complete only relative to published graph, candidate receipts, and action-free scope",
			result.plans);
	}

	private static final class Accumulator {
		private final long limit;
		private long visited;
		private boolean overflow;
		private boolean unknown;
		private final List<JointPlan> plans = new ArrayList<>();
		private Accumulator(long limit) { this.limit = limit; }
	}

	private static void enumerateAssignments(PlacementAnalysis analysis, NeutralPlacementGraph graph,
		List<NeutralPlacementGraph.Node> nodes, int index,
		Map<CompiledHopKey,PlacementState> assignment, Accumulator result) {
		if(result.overflow || result.unknown)
			return;
		if(index < nodes.size()) {
			NeutralPlacementGraph.Node node = nodes.get(index);
			for(PlacementState state : node.legalAlternatives()) {
				assignment.put(node.key(), state);
				enumerateAssignments(analysis, graph, nodes, index + 1, assignment, result);
				assignment.remove(node.key());
				if(result.overflow || result.unknown)
					return;
			}
			return;
		}
		for(NeutralPlacementGraph.Constraint constraint : graph.constraints()) {
			PlacementState left = assignment.get(constraint.left());
			PlacementState right = assignment.get(constraint.right());
			if(left != null && right != null
				&& !NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
				return;
		}
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants;
		try {
			variants = CandidateSelections.feasibleVariants(analysis, graph, List.of(), assignment);
		}
		catch(IllegalStateException noReachableRow) {
			if(noReachableRow.getMessage() == null || !noReachableRow.getMessage().startsWith(
				"Active exact candidate has no source-reachable row:"))
				throw noReachableRow;
			return;
		}
		for(CompiledHopKey owner : variants.keySet())
			if(nodes.stream().noneMatch(node -> node.key().equals(owner)))
				throw new IllegalStateException("P probe found a receipt owner outside the decision graph: " + owner);
		for(NeutralPlacementGraph.Node node : nodes) {
			PlacementState state = assignment.get(node.key());
			if(!variants.containsKey(node.key()) &&
				(state.execType() != ExecType.CP || state.output() != FederatedOutput.LOUT)) {
				result.unknown = true;
				return;
			}
		}
		List<List<CandidateSelectionReceipt>> domains = new ArrayList<>();
		for(NeutralPlacementGraph.Node node : nodes)
			if(variants.containsKey(node.key()))
				domains.add(variants.get(node.key()));
		enumerateReceipts(analysis, assignment, domains, 0, new ArrayList<>(), result);
	}

	private static void enumerateReceipts(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		List<List<CandidateSelectionReceipt>> domains, int index,
		List<CandidateSelectionReceipt> selected, Accumulator result) {
		if(result.overflow)
			return;
		if(index < domains.size()) {
			for(CandidateSelectionReceipt receipt : domains.get(index)) {
				selected.add(receipt);
				enumerateReceipts(analysis, assignment, domains, index + 1, selected, result);
				selected.remove(selected.size() - 1);
				if(result.overflow)
					return;
			}
			return;
		}
		if(result.visited >= result.limit) {
			result.overflow = true;
			return;
		}
		result.visited++;
		if(!CandidateSelections.realizationsCanStillBeCompatible(analysis, assignment, selected))
			return;
		try {
			CandidateSelections.validateRealizationSelections(analysis, assignment, selected, List.of());
			result.plans.add(new JointPlan(assignment, selected));
		}
		catch(IllegalArgumentException incompatible) {
			// Validation owns the cross-consumer realization and physical-action contract.
		}
	}
}
