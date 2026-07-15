/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.selector.IsomorphicSelectorContractFixtures.Case;
import org.junit.Assert;
import org.junit.Test;

/** Proves the independently spelled RED fixture universes are semantically isomorphic before cutover. */
public class IsomorphicSelectorContractFixturesTest {
	@Test
	public void everyExplicitAndNeutralFixtureHasTheSameCompleteLegalUniverseAndOptimum() {
		for(Case fixture : IsomorphicSelectorContractFixtures.all()) {
			List<Candidate> neutral = neutralCandidates(fixture.production());
			List<String> neutralUniverse = neutral.stream().map(Candidate::semanticAssignment).sorted().toList();
			Assert.assertEquals(fixture.id(), fixture.oracle().normalizedLegalAssignments(), neutralUniverse);
			long dependencyEdges = fixture.production().constraints().stream()
				.filter(constraint -> constraint.kind() == ConstraintKind.DOMINATES).count();
			Assert.assertEquals(fixture.id(), fixture.oracle().getEdgeCount(), dependencyEdges);

			ExactSelectorOracle.Result expected = ExactSelectorOracle.select(fixture.oracle(),
				ExactSelectorOracle.Policy.FED_ALL);
			Candidate actual = neutral.stream().max(Candidate::compareTo).orElseThrow();
			Assert.assertEquals(fixture.id(), semanticOracleAssignment(expected.getAssignment()),
				actual.semanticAssignment());
			Assert.assertEquals(fixture.id(), oracleRelocations(expected.getAssignment()), actual.relocations());
			Assert.assertEquals(fixture.id(), expected.getScore().getFedCount(), actual.fed());
			Assert.assertEquals(fixture.id(), expected.getScore().getFoutCount(), actual.fout());
			Assert.assertEquals(fixture.id(), expected.getScore().getRelocationCount(), actual.relocations().size());
		}
	}

	private static List<Candidate> neutralCandidates(NeutralPlacementGraph graph) {
		List<Candidate> result = new ArrayList<>();
		enumerate(graph, graph.nodes(), 0, new LinkedHashMap<>(), result);
		return result;
	}

	private static void enumerate(NeutralPlacementGraph graph, List<Node> nodes, int index,
		Map<CompiledHopKey,PlacementState> assignment, List<Candidate> result) {
		if(index == nodes.size()) {
			if(legal(graph, assignment))
				result.add(candidate(graph, assignment));
			return;
		}
		Node node = nodes.get(index);
		for(PlacementState state : node.legalAlternatives()) {
			assignment.put(node.key(), state);
			enumerate(graph, nodes, index + 1, assignment, result);
		}
		assignment.remove(node.key());
	}

	private static boolean legal(NeutralPlacementGraph graph, Map<CompiledHopKey,PlacementState> assignment) {
		for(NeutralPlacementGraph.Constraint constraint : graph.constraints()) {
			PlacementState left = assignment.get(constraint.left());
			PlacementState right = assignment.get(constraint.right());
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT && !left.equals(right))
				return false;
			if(constraint.kind() == ConstraintKind.SAME_FTYPE && !Objects.equals(left.fType(), right.fType()))
				return false;
			if(constraint.kind() == ConstraintKind.CONJUNCTIVE && right.output() == FederatedOutput.FOUT
				&& (left.output() != FederatedOutput.FOUT || !Objects.equals(left.fType(), right.fType())))
				return false;
		}
		return true;
	}

	private static Candidate candidate(NeutralPlacementGraph graph,
		Map<CompiledHopKey,PlacementState> assignment) {
		int fed = 0;
		int fout = 0;
		List<String> entries = new ArrayList<>();
		for(Node node : graph.nodes()) {
			PlacementState state = assignment.get(node.key());
			if(node.emittedWork() && state.execType() == ExecType.FED)
				fed++;
			if(state.output() == FederatedOutput.FOUT)
				fout++;
			entries.add(node.key().canonicalSourceOrigin() + '=' + state.normalizedSignature());
		}
		Collections.sort(entries);
		Set<String> relocations = new TreeSet<>();
		graph.relocationActions().forEach(action -> {
			boolean required = action.obligations().stream().anyMatch(obligation ->
				obligation.requiredPlacement().equals(assignment.get(obligation.consumer())));
			if(required)
				relocations.add(action.key().durableAnchor().placementId());
		});
		return new Candidate(fed, fout, Set.copyOf(relocations), String.join("|", entries));
	}

	private static String semanticOracleAssignment(Map<String,Choice> assignment) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((node, choice) -> entries.add(node + '=' + choice.getId()));
		Collections.sort(entries);
		return String.join("|", entries);
	}

	private static Set<String> oracleRelocations(Map<String,Choice> assignment) {
		Set<String> result = new TreeSet<>();
		assignment.values().forEach(choice -> result.addAll(choice.getRelocationActions()));
		return result;
	}

	private record Candidate(int fed, int fout, Set<String> relocations, String semanticAssignment)
		implements Comparable<Candidate> {
		@Override
		public int compareTo(Candidate that) {
			int comparison = Integer.compare(fed, that.fed);
			if(comparison != 0)
				return comparison;
			comparison = Integer.compare(fout, that.fout);
			if(comparison != 0)
				return comparison;
			comparison = Integer.compare(that.relocations.size(), relocations.size());
			if(comparison != 0)
				return comparison;
			return that.semanticAssignment.compareTo(semanticAssignment);
		}
	}
}
