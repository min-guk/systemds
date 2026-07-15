/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;

/** Immutable result of constructing one neutral placement universe for a compiled program. */
public final class PlacementAnalysis {
	public record NodeShapeFact(DataType dataType, long rows, long cols) {
		public NodeShapeFact { Objects.requireNonNull(dataType, "dataType"); }
		public boolean knownPositiveMatrix() { return dataType == DataType.MATRIX && rows > 0 && cols > 0; }
	}
	/** Stable association between a neutral graph key and its concrete compiled Hop origin. */
	public record HopOccurrenceProjection(CompiledHopKey key, Hop hop, int normalizedOrdinal,
		String normalizedSignature) {
		public HopOccurrenceProjection {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(hop, "hop");
			if(normalizedOrdinal < 0)
				throw new IllegalArgumentException("normalizedOrdinal must be non-negative");
			if(normalizedSignature == null || normalizedSignature.isBlank())
				throw new IllegalArgumentException("normalizedSignature must not be blank");
		}
	}

	private final NeutralPlacementGraph graph;
	private final List<HopOccurrenceProjection> occurrences;
	private final Map<CompiledHopKey, Hop> hopsByKey;
	private final Map<CompiledHopKey, NodeShapeFact> shapeFacts;
	private final String analysisFingerprint;

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences) {
		this.graph = Objects.requireNonNull(graph, "graph");
		this.occurrences = List.copyOf(occurrences);
		Map<CompiledHopKey, Hop> indexed = new LinkedHashMap<>();
		for(HopOccurrenceProjection occurrence : this.occurrences)
			if(indexed.put(occurrence.key(), occurrence.hop()) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop projection key: " + occurrence.key());
		if(indexed.size() != graph.nodes().size())
			throw new IllegalArgumentException("Projection does not cover the neutral placement graph");
		hopsByKey = Map.copyOf(indexed);
		Map<CompiledHopKey, NodeShapeFact> shapes = new LinkedHashMap<>();
		for(var entry : indexed.entrySet()) {
			var shape = OracleFacade.nodeShape(entry.getValue());
			shapes.put(entry.getKey(), new NodeShapeFact(shape.dataType(), shape.rows(), shape.cols()));
		}
		shapeFacts = Map.copyOf(shapes);
		List<String> projectionSignatures = this.occurrences.stream()
			.map(HopOccurrenceProjection::normalizedSignature).collect(java.util.stream.Collectors.toList());
		analysisFingerprint = PlacementGraphFingerprint.sha256(graph.normalizedSignature() + '\n'
			+ String.join("\n", projectionSignatures));
	}

	public NeutralPlacementGraph graph() {
		return graph;
	}

	public List<HopOccurrenceProjection> occurrences() {
		return occurrences;
	}

	public Optional<Hop> hop(CompiledHopKey key) {
		return Optional.ofNullable(hopsByKey.get(Objects.requireNonNull(key, "key")));
	}

	public Optional<NodeShapeFact> shapeFact(CompiledHopKey key) {
		return Optional.ofNullable(shapeFacts.get(Objects.requireNonNull(key, "key")));
	}

	public String analysisFingerprint() {
		return analysisFingerprint;
	}
}
