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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.parser.DMLProgram;

/** Immutable result of constructing one neutral placement universe for a compiled program. */
public final class PlacementAnalysis {
	/** Exact producer/value pair for one immutable Heuristic demotion fact. */
	public record HeuristicPolicyFact(CompiledHopKey producer, ValueVersionKey valueVersion)
		implements Comparable<HeuristicPolicyFact> {
		public HeuristicPolicyFact {
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(valueVersion, "valueVersion");
			if(!producer.programFingerprint().equals(valueVersion.programFingerprint()))
				throw new IllegalArgumentException("Heuristic policy producer and value fingerprints differ");
		}

		@Override
		public int compareTo(HeuristicPolicyFact that) {
			int producerOrder = producer.compareTo(that.producer);
			return producerOrder != 0 ? producerOrder : valueVersion.compareTo(that.valueVersion);
		}
	}

	/** Deterministic, deeply immutable set of producer-scoped Heuristic demotions. */
	public record HeuristicPolicyFacts(List<HeuristicPolicyFact> demotions) {
		public HeuristicPolicyFacts {
			Objects.requireNonNull(demotions, "demotions");
			List<HeuristicPolicyFact> sorted = demotions.stream()
				.map(fact -> Objects.requireNonNull(fact, "demotion fact")).sorted().toList();
			Map<CompiledHopKey, ValueVersionKey> valuesByProducer = new LinkedHashMap<>();
			Map<ValueVersionKey, CompiledHopKey> producersByValue = new LinkedHashMap<>();
			HeuristicPolicyFact previous = null;
			for(HeuristicPolicyFact fact : sorted) {
				if(fact.equals(previous))
					throw new IllegalArgumentException("Duplicate Heuristic policy fact");
				ValueVersionKey priorValue = valuesByProducer.putIfAbsent(fact.producer(), fact.valueVersion());
				if(priorValue != null && !priorValue.equals(fact.valueVersion()))
					throw new IllegalArgumentException("Heuristic policy producer maps to multiple values");
				CompiledHopKey priorProducer = producersByValue.putIfAbsent(fact.valueVersion(), fact.producer());
				if(priorProducer != null && !priorProducer.equals(fact.producer()))
					throw new IllegalArgumentException("Heuristic policy value maps to multiple producers");
				previous = fact;
			}
			demotions = List.copyOf(sorted);
		}
	}

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
	private final PlacementShapeFacts shapeFacts;
	private final String analysisFingerprint;
	private final HeuristicPolicyFacts heuristicPolicyFacts;
	private final DMLProgram programOwner;

	PlacementAnalysis(NeutralPlacementGraph graph, List<HopOccurrenceProjection> occurrences,
		DMLProgram programOwner, PlacementShapeFacts shapeFacts, String analysisFingerprint,
		HeuristicPolicyFacts heuristicPolicyFacts) {
		this.graph = Objects.requireNonNull(graph, "graph");
		this.programOwner = programOwner;
		this.occurrences = List.copyOf(occurrences);
		Map<CompiledHopKey, Hop> indexed = new LinkedHashMap<>();
		for(HopOccurrenceProjection occurrence : this.occurrences)
			if(indexed.put(occurrence.key(), occurrence.hop()) != null)
				throw new IllegalArgumentException("Duplicate compiled Hop projection key: " + occurrence.key());
		if(indexed.size() != graph.nodes().size())
			throw new IllegalArgumentException("Projection does not cover the neutral placement graph");
		this.shapeFacts = Objects.requireNonNull(shapeFacts, "shapeFacts");
		if(!shapeFacts.keys().equals(indexed.keySet()))
			throw new IllegalArgumentException("Shape facts do not exactly cover indexed placement projections");
		hopsByKey = Map.copyOf(indexed);
		if(analysisFingerprint == null || analysisFingerprint.isBlank())
			throw new IllegalArgumentException("analysisFingerprint must not be blank");
		this.analysisFingerprint = analysisFingerprint;
		this.heuristicPolicyFacts = Objects.requireNonNull(heuristicPolicyFacts, "heuristicPolicyFacts");
		for(HeuristicPolicyFact fact : heuristicPolicyFacts.demotions()) {
			NeutralPlacementGraph.Node producer = graph.node(fact.producer()).orElseThrow(() ->
				new IllegalArgumentException("Heuristic policy producer is missing from the analysis graph"));
			if(!producer.valueVersion().equals(fact.valueVersion()))
				throw new IllegalArgumentException("Heuristic policy producer/value pair does not match the analysis graph");
		}
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
		return shapeFacts.shapeFact(key);
	}

	public String analysisFingerprint() {
		return analysisFingerprint;
	}

	public HeuristicPolicyFacts heuristicPolicyFacts() {
		return heuristicPolicyFacts;
	}

	public void assertProgramOwner(DMLProgram program) {
		if(program == null || program != programOwner)
			throw new IllegalArgumentException("Placement analysis is foreign to the supplied program");
	}

	public void assertCanonicalProgramAuthority(DMLProgram program) {
		assertProgramOwner(program);
		program.requirePlacementAnalysisAuthority(this);
	}
}
