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
import org.apache.sysds.test.component.federated.placement.oracle.builder.BuilderOracle.Graph;
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
		put(surfaces, Surface.LEGAL_ASSIGNMENTS, graph.normalizedLegalAssignments());
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
