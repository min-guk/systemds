/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Bound;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Score;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Selection;

/** Field-exact validator shared by executable contracts and complete-but-wrong fake tests. */
final class R4SharedFedAllSemanticValidator {
	record Expected(Map<CompiledHopKey,PlacementState> assignment, List<RelocationActionKey> relocations,
		Score score, String graphHash, String assignmentHash, long explored, long pruned, long universe,
		int nodes, int constraints, int components, String derivation, List<Bound> bounds) { }

	static void shared(PlacementAnalysis supplied, Selection actual) {
		require(actual.analysis() == supplied, "R4_ANALYSIS_IDENTITY");
		Set<CompiledHopKey> keys = supplied.graph().nodes().stream().map(NeutralPlacementGraph.Node::key)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		require(actual.assignment().keySet().equals(keys), "R4_ASSIGNMENT_KEYS");
		for(var e : actual.assignment().entrySet()) require(supplied.graph().node(e.getKey()).orElseThrow()
			.legalAlternatives().contains(e.getValue()), "R4_ASSIGNMENT_STATE|" + e.getKey().normalizedSignature());
		Map<RelocationActionKey,NeutralPlacementGraph.RelocationAction> graphActions = new LinkedHashMap<>();
		for(var a : supplied.graph().relocationActions()) graphActions.put(a.key(), a);
		require(new LinkedHashSet<>(actual.relocations()).size() == actual.relocations().size(), "R4_RELOCATION_KEY|duplicate");
		for(RelocationActionKey key : actual.relocations()) {
			NeutralPlacementGraph.RelocationAction action = graphActions.get(key);
			require(action != null, "R4_RELOCATION_KEY|foreign");
			for(var obligation : action.obligations()) {
				require(keys.contains(obligation.consumer()), "R4_RELOCATION_OBLIGATION|consumer");
				require(key.equals(obligation.relocationAction()), "R4_RELOCATION_OBLIGATION|key");
			}
		}
		require(actual.certificate().graphFingerprint().equals(R4SharedFedAllAdapterBridge.graphHash(supplied)), "R4_GRAPH_HASH");
		require(actual.certificate().assignmentHash().equals(R4SharedFedAllAdapterBridge.assignmentHash(actual.assignment())), "R4_ASSIGNMENT_HASH");
		require(!actual.certificate().fallbackUsed(), "R4_FALLBACK");
	}

	static void fedAll(Expected expected, Selection actual) {
		require(actual.assignment().equals(expected.assignment()), "R4_ASSIGNMENT_STATE");
		require(actual.relocations().equals(expected.relocations()), "R4_RELOCATION_KEY");
		require(actual.score().fed() == expected.score().fed() && actual.score().fout() == expected.score().fout()
			&& actual.score().relocations() == expected.score().relocations(), "R4_SCORE_COUNTS");
		require(actual.score().signature().equals(expected.score().signature()), "R4_SCORE_SIGNATURE");
		var c = actual.certificate();
		require(c.graphFingerprint().equals(expected.graphHash()), "R4_GRAPH_HASH");
		require(c.assignmentHash().equals(expected.assignmentHash()), "R4_ASSIGNMENT_HASH");
		require(c.incumbent().equals(actual.score()) && c.upperBound().equals(actual.score()), "R4_BOUND_AGGREGATE");
		require(c.bounds().equals(expected.bounds()), "R4_BOUND_COMPONENT");
		require(c.boundDerivation().equals(expected.derivation()), "R4_BOUND_DERIVATION");
		require(c.explored() == expected.explored() && c.pruned() == expected.pruned()
			&& c.universe() == expected.universe() && c.explored() + c.pruned() == c.universe(), "R4_UNIVERSE");
		require(c.graphNodes() == expected.nodes() && c.graphConstraints() == expected.constraints()
			&& c.graphComponents() == expected.components(), "R4_GRAPH_COUNTS");
		require(c.termination().equals("EXHAUSTED"), "R4_TERMINATION");
		require(!c.fallbackUsed(), "R4_FALLBACK");
	}

	static List<Bound> componentBounds(NeutralPlacementGraph graph) {
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(var n : graph.nodes()) adjacency.put(n.key(), new LinkedHashSet<>());
		for(var c : graph.constraints()) { adjacency.get(c.left()).add(c.right()); adjacency.get(c.right()).add(c.left()); }
		Set<CompiledHopKey> seen = new LinkedHashSet<>(); List<Bound> out = new ArrayList<>();
		for(CompiledHopKey start : adjacency.keySet().stream().sorted().toList()) {
			if(!seen.add(start)) continue; ArrayDeque<CompiledHopKey> q = new ArrayDeque<>(); q.add(start); List<CompiledHopKey> nodes = new ArrayList<>();
			while(!q.isEmpty()) { CompiledHopKey k = q.remove(); nodes.add(k); for(CompiledHopKey n : adjacency.get(k)) if(seen.add(n)) q.add(n); }
			nodes.sort(Comparator.naturalOrder()); int fed = 0, fout = 0;
			for(CompiledHopKey key : nodes) { var states = graph.node(key).orElseThrow().legalAlternatives();
				if(states.stream().anyMatch(s -> s.execType() == ExecType.FED)) fed++;
				if(states.stream().anyMatch(s -> s.output() == FederatedOutput.FOUT)) fout++; }
			String id = Integer.toHexString(nodes.stream().map(CompiledHopKey::normalizedSignature).toList().hashCode());
			out.add(new Bound(id, List.copyOf(nodes), fed, fout, 0, "independent-component-envelope"));
		}
		out.sort(Comparator.comparing(Bound::id)); return List.copyOf(out);
	}

	static void stable(Selection left, Selection right, String code) {
		require(R4SharedFedAllAdapterBridge.normalize(left).equals(R4SharedFedAllAdapterBridge.normalize(right)), code);
	}

	static void require(boolean value, String code) { if(!value) throw new AssertionError(code); }
	private R4SharedFedAllSemanticValidator() { }
}
