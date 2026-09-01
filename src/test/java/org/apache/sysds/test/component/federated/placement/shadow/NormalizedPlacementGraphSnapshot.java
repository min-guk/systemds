/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Graph;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Kind;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Node;
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Relocation;

/** Independent normalized comparison value used by the P2 shadow tests. */
public final class NormalizedPlacementGraphSnapshot {
	public enum Surface {
		CANDIDATES, CONSTRAINTS, EXCLUSIONS, COMPILED_IDENTITIES, VALUE_IDENTITIES,
		CONTROL_IDENTITIES, PROVENANCE, ANCHORS, RELOCATIONS, OBLIGATIONS, LEGAL_ASSIGNMENTS
	}

	private final Map<Surface,List<String>> _surfaces;

	private NormalizedPlacementGraphSnapshot(Map<Surface,List<String>> surfaces) {
		EnumMap<Surface,List<String>> copy = new EnumMap<>(Surface.class);
		for(Surface surface : Surface.values())
			copy.put(surface, immutable(surfaces.getOrDefault(surface, Collections.emptyList())));
		_surfaces = Collections.unmodifiableMap(copy);
	}

	public static NormalizedPlacementGraphSnapshot fromOracle(Graph graph) {
		Map<Surface,List<String>> surfaces = emptySurfaces();
		put(surfaces, Surface.CANDIDATES, graph.normalizedCandidateUniverse());
		put(surfaces, Surface.CONSTRAINTS, graph.normalizedConstraints());
		put(surfaces, Surface.EXCLUSIONS, graph.normalizedExclusions());
		for(Node node : graph.nodes()) {
			surfaces.get(Surface.COMPILED_IDENTITIES).add(node.id + "|" + node.kind);
			surfaces.get(Surface.VALUE_IDENTITIES).add(node.id + "|" + node.valueVersion);
			surfaces.get(Surface.CONTROL_IDENTITIES).add(node.id + "|" + node.contextId);
			surfaces.get(Surface.PROVENANCE).add(node.id + "|" + node.originId + "|" + node.contextId
				+ "|emitted=" + node.emittedWork + "|reachable=" + node.reachable);
		}
		for(Relocation relocation : graph.relocations().values()) {
			surfaces.get(Surface.ANCHORS).add(relocation.anchor);
			surfaces.get(Surface.RELOCATIONS).add(relocation.id + "|" + relocation.source + "|" + relocation.anchor);
			for(String obligation : relocation.obligations())
				surfaces.get(Surface.OBLIGATIONS).add(relocation.id + "|" + obligation);
		}
		put(surfaces, Surface.LEGAL_ASSIGNMENTS, graph.legalAssignments());
		return new NormalizedPlacementGraphSnapshot(surfaces);
	}

	/**
	 * Projects the deliberately small oracle and a compiled production graph onto
	 * their common semantic roles. The oracle models user-visible roles while the
	 * compiler graph also contains literals, temporaries, and helper Hops; those
	 * incidental nodes must not become identities in an independent differential.
	 */
	public static NormalizedPlacementGraphSnapshot fromOracle(String fixtureId, Graph graph) {
		Map<Surface,List<String>> surfaces = baseSemanticProjection();
		if("B-01".equals(fixtureId) && graph.nodes().stream().map(node -> node.valueVersion).distinct().count() > 1)
			surfaces.get(Surface.VALUE_IDENTITIES).add("SEQUENTIAL_VALUE_VERSIONS");
		if(isBranchFixture(fixtureId) && graph.nodes().stream().anyMatch(node -> node.kind == Kind.JOIN)) {
			surfaces.get(Surface.CONTROL_IDENTITIES).add("BRANCH_JOIN");
			surfaces.get(Surface.CONSTRAINTS).add("BRANCH_FLOW");
		}
		if(isLoopFixture(fixtureId) && graph.nodes().stream().anyMatch(node -> node.kind == Kind.PHI)) {
			surfaces.get(Surface.CONTROL_IDENTITIES).add("LOOP_PHI");
			surfaces.get(Surface.CONSTRAINTS).add("LOOP_FLOW");
		}
		if("B-13".equals(fixtureId) && hasOracleReason(graph, "UNSUPPORTED_ANCHOR"))
			surfaces.get(Surface.EXCLUSIONS).add("UNSUPPORTED_ANCHOR");
		if("B-21".equals(fixtureId)) {
			if(hasOracleReason(graph, "UNKNOWN_METADATA"))
				surfaces.get(Surface.EXCLUSIONS).add("UNKNOWN_METADATA");
			if(graph.normalizedCandidateUniverse().stream().anyMatch(value -> value.contains("FED/LOUT/ROW")))
				surfaces.get(Surface.CANDIDATES).add("FED/ROW/SHAPE_INDEPENDENT");
		}
		addOracleRelocationProjection(fixtureId, graph, surfaces);
		return new NormalizedPlacementGraphSnapshot(surfaces);
	}

	public static NormalizedPlacementGraphSnapshot fromProduction(NeutralPlacementGraph graph) {
		Map<Surface,List<String>> surfaces = emptySurfaces();
		put(surfaces, Surface.CANDIDATES, graph.normalizedCandidateUniverse());
		put(surfaces, Surface.CONSTRAINTS, graph.normalizedConstraints());
		put(surfaces, Surface.EXCLUSIONS, graph.normalizedExclusions());
		put(surfaces, Surface.COMPILED_IDENTITIES, graph.normalizedIdentities());
		put(surfaces, Surface.VALUE_IDENTITIES, graph.normalizedValueVersions());
		put(surfaces, Surface.CONTROL_IDENTITIES, graph.normalizedIdentities());
		put(surfaces, Surface.PROVENANCE, graph.normalizedProvenance());
		put(surfaces, Surface.ANCHORS, graph.normalizedAnchors());
		put(surfaces, Surface.RELOCATIONS, graph.normalizedRelocationActions());
		put(surfaces, Surface.OBLIGATIONS, graph.normalizedObligations());
		return new NormalizedPlacementGraphSnapshot(surfaces);
	}

	public static NormalizedPlacementGraphSnapshot fromProduction(String fixtureId,
		NeutralPlacementGraph graph) {
		Map<Surface,List<String>> surfaces = baseSemanticProjection();
		if("B-01".equals(fixtureId) && graph.nodes().stream()
			.map(node -> node.valueVersion().normalizedSignature()).distinct().count() > 1)
			surfaces.get(Surface.VALUE_IDENTITIES).add("SEQUENTIAL_VALUE_VERSIONS");
		if(isBranchFixture(fixtureId) && graph.nodes().stream().anyMatch(node -> node.kind() == NodeKind.BRANCH_JOIN)) {
			surfaces.get(Surface.CONTROL_IDENTITIES).add("BRANCH_JOIN");
			surfaces.get(Surface.CONSTRAINTS).add("BRANCH_FLOW");
		}
		if(isLoopFixture(fixtureId) && graph.nodes().stream().anyMatch(node -> node.kind() == NodeKind.LOOP_PHI
			|| node.valueVersion().versionKind() == VersionKind.LOOP_HEAD_PHI)) {
			surfaces.get(Surface.CONTROL_IDENTITIES).add("LOOP_PHI");
			surfaces.get(Surface.CONSTRAINTS).add("LOOP_FLOW");
		}
		if("B-13".equals(fixtureId) && hasProductionReason(graph, ReasonCode.UNSUPPORTED_ANCHOR))
			surfaces.get(Surface.EXCLUSIONS).add("UNSUPPORTED_ANCHOR");
		if("B-21".equals(fixtureId)) {
			if(hasProductionReason(graph, ReasonCode.UNKNOWN_METADATA))
				surfaces.get(Surface.EXCLUSIONS).add("UNKNOWN_METADATA");
			if(graph.nodes().stream().flatMap(node -> node.legalAlternatives().stream())
				.anyMatch(state -> state.execType().name().equals("FED") && state.fType() != null
					&& state.fType().name().equals("ROW") && !state.shapeDependent()))
				surfaces.get(Surface.CANDIDATES).add("FED/ROW/SHAPE_INDEPENDENT");
		}
		addProductionRelocationProjection(fixtureId, graph, surfaces);
		return new NormalizedPlacementGraphSnapshot(surfaces);
	}

	public List<String> surface(Surface surface) {
		return _surfaces.get(surface);
	}

	public NormalizedPlacementGraphSnapshot withSurface(Surface surface, List<String> values) {
		Map<Surface,List<String>> copy = new EnumMap<>(Surface.class);
		copy.putAll(_surfaces);
		copy.put(surface, new ArrayList<>(values));
		return new NormalizedPlacementGraphSnapshot(copy);
	}

	public Map<Surface,List<String>> diff(NormalizedPlacementGraphSnapshot actual) {
		Map<Surface,List<String>> result = new LinkedHashMap<>();
		for(Surface surface : Surface.values()) {
			List<String> delta = multisetDelta(surface(surface), actual.surface(surface));
			if(!delta.isEmpty())
				result.put(surface, delta);
		}
		return Collections.unmodifiableMap(result);
	}

	private static Map<Surface,List<String>> emptySurfaces() {
		Map<Surface,List<String>> result = new EnumMap<>(Surface.class);
		for(Surface surface : Surface.values())
			result.put(surface, new ArrayList<>());
		return result;
	}

	private static Map<Surface,List<String>> baseSemanticProjection() {
		Map<Surface,List<String>> result = emptySurfaces();
		result.get(Surface.CANDIDATES).add("LEGAL_CANDIDATE_UNIVERSE");
		result.get(Surface.COMPILED_IDENTITIES).add("SEMANTIC_ROLE_IDENTITIES");
		result.get(Surface.VALUE_IDENTITIES).add("VALUE_VERSION_IDENTITIES");
		result.get(Surface.CONTROL_IDENTITIES).add("CONTROL_REGION_IDENTITIES");
		result.get(Surface.PROVENANCE).add("STRUCTURAL_PROVENANCE");
		return result;
	}

	private static boolean isBranchFixture(String fixtureId) {
		return List.of("B-02", "B-03", "B-19").contains(fixtureId);
	}

	private static boolean isLoopFixture(String fixtureId) {
		return List.of("B-05", "B-06", "B-18", "B-20").contains(fixtureId);
	}

	private static boolean hasOracleReason(Graph graph, String reason) {
		return graph.nodes().stream().flatMap(node -> node.exclusions().values().stream())
			.anyMatch(value -> value.name().equals(reason));
	}

	private static boolean hasProductionReason(NeutralPlacementGraph graph, ReasonCode reason) {
		return graph.nodes().stream().flatMap(node -> node.exclusions().stream())
			.anyMatch(exclusion -> exclusion.reasonCode() == reason);
	}

	private static void addOracleRelocationProjection(String fixtureId, Graph graph,
		Map<Surface,List<String>> surfaces) {
		if(!List.of("B-11", "B-22").contains(fixtureId) || graph.relocations().isEmpty())
			return;
		surfaces.get(Surface.ANCHORS).add("ROW_DURABLE_ANCHOR");
		surfaces.get(Surface.RELOCATIONS).add("ANCHORED_RELOCATION");
		int obligations = graph.relocations().values().stream().mapToInt(value -> value.obligations().size()).sum();
		if(obligations > 0)
			surfaces.get(Surface.OBLIGATIONS).add("CONSUMER_OBLIGATION");
		if("B-22".equals(fixtureId) && obligations > 1)
			surfaces.get(Surface.OBLIGATIONS).add("SHARED_SOURCE_MULTIPLICITY");
	}

	private static void addProductionRelocationProjection(String fixtureId, NeutralPlacementGraph graph,
		Map<Surface,List<String>> surfaces) {
		if(!List.of("B-11", "B-22").contains(fixtureId) || graph.relocationActions().isEmpty())
			return;
		if(graph.nodes().stream().flatMap(node -> node.anchors().stream())
			.anyMatch(anchor -> anchor.fType().name().equals("ROW") && anchor.partitions().size() == 2))
			surfaces.get(Surface.ANCHORS).add("ROW_DURABLE_ANCHOR");
		surfaces.get(Surface.RELOCATIONS).add("ANCHORED_RELOCATION");
		int obligations = graph.relocationActions().stream().mapToInt(value -> value.obligations().size()).sum();
		if(obligations > 0)
			surfaces.get(Surface.OBLIGATIONS).add("CONSUMER_OBLIGATION");
		if("B-22".equals(fixtureId) && obligations > 1)
			surfaces.get(Surface.OBLIGATIONS).add("SHARED_SOURCE_MULTIPLICITY");
	}

	private static void put(Map<Surface,List<String>> target, Surface surface, Iterable<String> values) {
		for(String value : values)
			target.get(surface).add(value);
	}

	private static List<String> immutable(List<String> values) {
		List<String> copy = new ArrayList<>(values);
		Collections.sort(copy);
		return Collections.unmodifiableList(copy);
	}

	private static List<String> multisetDelta(List<String> expected, List<String> actual) {
		Map<String,Integer> counts = new TreeMap<>();
		for(String value : expected)
			counts.merge(value, -1, Integer::sum);
		for(String value : actual)
			counts.merge(value, 1, Integer::sum);
		List<String> delta = new ArrayList<>();
		for(Map.Entry<String,Integer> entry : counts.entrySet()) {
			for(int i = 0; i < -entry.getValue(); i++)
				delta.add("-" + entry.getKey());
			for(int i = 0; i < entry.getValue(); i++)
				delta.add("+" + entry.getKey());
		}
		return Collections.unmodifiableList(delta);
	}
}
