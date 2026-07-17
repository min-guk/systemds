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

package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Typed adversarial fixture transformations around the public Heuristic adapter seam. */
final class R4HeuristicMetadataFixtureBridge {
	enum BroadcastCase { UNKNOWN, UNSUPPORTED, NON_MATRIX, MISSING_HOP, SAFE_MATRIX }
	record MarkerContribution(ValueVersionKey marker, CompiledHopKey markerKey,
		Set<CompiledHopKey> descendants, Set<String> candidateKeys, List<String> exclusions) { }
	record MultiMarkerScenario(CampaignBProvenanceFixtureBridge.Fixture fixture,
		LinkedHashMap<ValueVersionKey, MarkerContribution> contributions,
		CompiledHopKey independent, String independentFedFout) { }
	record Scenario(CampaignBProvenanceFixtureBridge.Fixture fixture, PlacementAnalysis analysis,
		CompiledHopKey target, PlacementState targetState, Set<ValueVersionKey> markers) {
		R4Heuristic2Probe.Snapshot snapshot() {
			return R4Heuristic2Probe.snapshot(fixture.program(), analysis);
		}
	}

	static Scenario matchingExclusion(ReasonCode reason, String detail) throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		var proof = fixture.candidateProofs().values().stream().findFirst().orElseThrow();
		PlacementAnalysis analysis = replace(fixture.analysis(), Map.of(proof.provenNode(),
			withExclusion(fixture.analysis().graph().node(proof.provenNode()).orElseThrow(),
				new Exclusion(proof.state(), reason, detail))));
		return new Scenario(fixture, analysis, proof.provenNode(), proof.state(), Set.of(fixture.marker()));
	}

	static Scenario nodeDimensions(long rows, long cols) throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		var proof = fixture.candidateProofs().values().stream().findFirst().orElseThrow();
		Hop hop = fixture.analysis().hop(proof.provenNode()).orElseThrow();
		hop.setDim1(rows);
		hop.setDim2(cols);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(fixture.program());
		return new Scenario(fixture, analysis, proof.provenNode(), proof.state(), Set.of(fixture.marker()));
	}

	static Scenario broadcast(boolean unsafeMetadata) throws Exception {
		return broadcast(unsafeMetadata ? BroadcastCase.UNKNOWN : BroadcastCase.SAFE_MATRIX);
	}

	static Scenario broadcast(BroadcastCase kind) throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		var proof = fixture.candidateProofs().values().stream().findFirst().orElseThrow();
		DurableAnchorKey broadcast = new DurableAnchorKey("broadcast-safe", FType.BROADCAST,
			proof.anchor().partitions());
		Map<CompiledHopKey, Node> replacements = new LinkedHashMap<>();
		for(Node node : fixture.analysis().graph().nodes()) {
			List<DurableAnchorKey> anchors = node.anchors().stream()
				.map(a -> a.equals(proof.anchor()) ? broadcast : a).toList();
			if(!anchors.equals(node.anchors()))
				replacements.put(node.key(), copy(node, node.exclusions(), anchors));
		}
		PlacementState state = targetState(fixture.analysis().graph().node(proof.provenNode()).orElseThrow(),
			broadcast);
		if(kind == BroadcastCase.UNKNOWN || kind == BroadcastCase.UNSUPPORTED) {
			Node target = replacements.getOrDefault(proof.provenNode(),
				fixture.analysis().graph().node(proof.provenNode()).orElseThrow());
			replacements.put(target.key(), withExclusion(target,
				new Exclusion(state, kind == BroadcastCase.UNKNOWN ? ReasonCode.UNKNOWN_METADATA :
					ReasonCode.UNSUPPORTED_OPERATION_SHAPE, "missingRequiredFacts=rank")));
		}
		PlacementAnalysis analysis = replace(fixture.analysis(), replacements);
		if(kind == BroadcastCase.NON_MATRIX)
			analysis = CampaignBPlacementAnalysisFixtureBridge.replaceHopAndShapeFact(analysis, proof.provenNode(),
				new org.apache.sysds.hops.LiteralOp(7L), new PlacementAnalysis.NodeShapeFact(DataType.SCALAR, -1, -1));
		else if(kind == BroadcastCase.MISSING_HOP)
			analysis = CampaignBPlacementAnalysisFixtureBridge.missingHopProjectionTrap(analysis, proof.provenNode());
		return new Scenario(fixture, analysis, proof.provenNode(), state, Set.of(fixture.marker()));
	}

	static MultiMarkerScenario multiMarker() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		DurableAnchorKey anchor = fixture.candidateProofs().values().iterator().next().anchor();
		Node first = fixture.analysis().graph().node(fixture.markerKey()).orElseThrow();
		Node second = fixture.candidateProofs().values().stream().map(p ->
			fixture.analysis().graph().node(p.provenNode()).orElseThrow())
			.filter(n -> !n.valueVersion().equals(first.valueVersion()))
			.min(java.util.Comparator.comparingInt(n -> closure(fixture.analysis().graph(),
				Set.of(n.valueVersion())).size())).orElseThrow();
		List<Node> markerNodes = List.of(first, second);
		LinkedHashMap<ValueVersionKey, MarkerContribution> result = new LinkedHashMap<>();
		for(Node markerNode : markerNodes) {
			Set<CompiledHopKey> descendants = closure(fixture.analysis().graph(), Set.of(markerNode.valueVersion()));
			Set<String> keys = new LinkedHashSet<>(); List<String> exclusions = new ArrayList<>();
			for(CompiledHopKey descendant : descendants) {
				var proof = CampaignBProvenanceFixtureBridge.candidateProof(fixture.analysis(),
					fixture.analysis().graph().node(descendant).orElseThrow(), anchor, markerNode.key());
				if(proof.isEmpty())
					continue;
				keys.add(proof.get().candidate()); exclusions.add("NO_REFED|" + proof.get().candidate() + "|proof="
					+ CampaignBProvenanceFixtureBridge.proofSignature(proof.get()) + "|marker="
					+ markerNode.valueVersion().normalizedSignature());
			}
			if(keys.isEmpty()) throw new AssertionError("MULTI_MARKER_FIXTURE_EMPTY|marker="
				+ markerNode.valueVersion().normalizedSignature());
			result.put(markerNode.valueVersion(), new MarkerContribution(markerNode.valueVersion(), markerNode.key(),
				descendants, Set.copyOf(keys), exclusions.stream().sorted().toList()));
		}
		Set<CompiledHopKey> union = new LinkedHashSet<>(); result.values().forEach(c -> union.addAll(c.descendants()));
		CompiledHopKey independent = fixture.roles().get("Y_INDEPENDENT");
		if(independent == null || union.contains(independent)) throw new AssertionError("MULTI_MARKER_NO_INDEPENDENT");
		String fed = fixture.baseAlternatives().get(independent.normalizedSignature()).stream()
			.filter(x -> x.contains("FED") && x.contains("FOUT")).findFirst().orElseThrow();
		return new MultiMarkerScenario(fixture, result, independent, fed);
	}

	static Scenario noProvableCandidate() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		Map<CompiledHopKey, Node> replacements = new LinkedHashMap<>();
		for(var proof : fixture.candidateProofs().values()) {
			Node node = fixture.analysis().graph().node(proof.provenNode()).orElseThrow();
			replacements.put(node.key(), withExclusion(node,
				new Exclusion(proof.state(), ReasonCode.UNKNOWN_METADATA, "rows=UNKNOWN")));
		}
		PlacementAnalysis analysis = replace(fixture.analysis(), replacements);
		return new Scenario(fixture, analysis, fixture.markerKey(),
			fixture.candidateProofs().values().iterator().next().state(), Set.of(fixture.marker()));
	}

	static Scenario standard() throws Exception {
		var fixture = CampaignBProvenanceFixtureBridge.fresh("H-09-INDEPENDENT-ANCHOR-RELEASE");
		var proof = fixture.candidateProofs().values().stream().findFirst().orElseThrow();
		return new Scenario(fixture, fixture.analysis(), proof.provenNode(), proof.state(), Set.of(fixture.marker()));
	}

	static CampaignBProvenanceFixtureBridge.Fixture twoMarkerFixture() throws Exception {
		return CampaignBProvenanceFixtureBridge.fresh("H-10-SAME-SHAPE-DISTINCT-ANCHORS");
	}

	static Set<String> exclusionKeys(R4Heuristic2AdapterBridge.Selection selection) {
		Set<String> result = new LinkedHashSet<>();
		for(String exclusion : selection.exclusions())
			if(exclusion.startsWith("NO_REFED|"))
				result.add(exclusion.substring("NO_REFED|".length(), exclusion.indexOf("|proof=")));
		return Set.copyOf(result);
	}

	static Set<ValueVersionKey> twoMarkers(CampaignBProvenanceFixtureBridge.Fixture fixture) {
		Node first = fixture.analysis().graph().node(fixture.markerKey()).orElseThrow();
		Node second = fixture.analysis().graph().nodes().stream()
			.filter(n -> n.emittedWork() && n.anchors().isEmpty() && !n.key().equals(first.key()))
			.filter(n -> n.key().canonicalSourceOrigin() != null && n.key().canonicalSourceOrigin().endsWith(":Y"))
			.findFirst().orElseThrow();
		return Set.of(first.valueVersion(), second.valueVersion());
	}

	static Set<CompiledHopKey> closure(NeutralPlacementGraph graph, Set<ValueVersionKey> markers) {
		Set<CompiledHopKey> roots = new LinkedHashSet<>();
		for(Node node : graph.nodes())
			if(markers.contains(node.valueVersion()))
				roots.add(node.key());
		Set<CompiledHopKey> result = new LinkedHashSet<>();
		ArrayDeque<CompiledHopKey> work = new ArrayDeque<>(roots);
		while(!work.isEmpty()) {
			CompiledHopKey key = work.remove();
			if(!result.add(key))
				continue;
			for(var constraint : graph.constraints())
				if(constraint.left().equals(key) && Set.of(ConstraintKind.DOMINATES, ConstraintKind.SAME_ORIGIN,
					ConstraintKind.SAME_PLACEMENT, ConstraintKind.CONJUNCTIVE).contains(constraint.kind()))
					work.add(constraint.right());
		}
		return Set.copyOf(result);
	}

	static boolean hasSynthesizedCandidate(R4Heuristic2AdapterBridge.Selection selection, Scenario scenario) {
		String prefix = "NO_REFED|" + scenario.target().normalizedSignature() + '='
			+ scenario.targetState().normalizedSignature() + "|proof=";
		return selection.exclusions().stream().anyMatch(x -> x.startsWith(prefix));
	}

	static boolean exclusionWithin(R4Heuristic2AdapterBridge.Selection selection, Set<CompiledHopKey> closure) {
		Set<String> signatures = closure.stream().map(CompiledHopKey::normalizedSignature)
			.collect(java.util.stream.Collectors.toSet());
		for(String exclusion : selection.exclusions()) {
			if(!exclusion.startsWith("NO_REFED|"))
				continue;
			String atom = exclusion.substring("NO_REFED|".length(), exclusion.indexOf('=', "NO_REFED|".length()));
			if(!signatures.contains(atom))
				return false;
		}
		return true;
	}

	private static Node withExclusion(Node node, Exclusion exclusion) {
		List<Exclusion> exclusions = new ArrayList<>(node.exclusions());
		exclusions.removeIf(x -> x.state().equals(exclusion.state()));
		exclusions.add(exclusion);
		List<PlacementState> legal = node.legalAlternatives().stream()
			.filter(x -> !x.equals(exclusion.state())).toList();
		return new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(), legal,
			exclusions, node.anchors());
	}

	private static Node copy(Node node, List<Exclusion> exclusions, List<DurableAnchorKey> anchors) {
		return new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
			node.legalAlternatives(), exclusions, anchors);
	}

	private static PlacementState targetState(Node node, DurableAnchorKey anchor) {
		boolean boundary = node.kind() == NodeKind.TRANSIENT_READ || node.kind() == NodeKind.TRANSIENT_WRITE
			|| "recompile".equals(node.key().recompileContext());
		return new PlacementState(boundary ? ExecType.FED : ExecType.CP, FederatedOutput.FOUT,
			anchor.fType(), anchor.fType() != FType.BROADCAST);
	}

	private static PlacementAnalysis replace(PlacementAnalysis source, Map<CompiledHopKey, Node> replacements)
		throws Exception {
		List<Node> nodes = source.graph().nodes().stream()
			.map(n -> replacements.getOrDefault(n.key(), n)).toList();
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, source.graph().constraints(),
			source.graph().relocationActions());
		return CampaignBPlacementAnalysisFixtureBridge.replaceGraph(source, graph);
	}

	private R4HeuristicMetadataFixtureBridge() {
	}
}
