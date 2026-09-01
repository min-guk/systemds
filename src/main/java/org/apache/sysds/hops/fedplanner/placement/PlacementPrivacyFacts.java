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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;

/**
 * Immutable occurrence- and value-version-scoped privacy authority produced by
 * the canonical placement-analysis construction pass.
 */
public final class PlacementPrivacyFacts {
	public record PrivacyFact(CompiledHopKey occurrence, ValueVersionKey valueVersion,
		Privacy privacy, List<CompiledHopKey> predecessors) {
		public PrivacyFact {
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(valueVersion, "valueVersion");
			Objects.requireNonNull(privacy, "privacy");
			predecessors = List.copyOf(Objects.requireNonNull(predecessors, "predecessors"));
		}

		public String normalizedSignature() {
			return occurrence.normalizedSignature() + "|value=" + valueVersion.normalizedSignature()
				+ "|privacy=" + privacy.name() + "|predecessors=" + predecessors.stream()
					.map(CompiledHopKey::normalizedSignature).toList();
		}
	}

	private final List<PrivacyFact> orderedFacts;
	private final Map<CompiledHopKey,PrivacyFact> factsByIdentity;
	private final Map<CompiledHopKey,Privacy> privacyByIdentity;
	private final int numWorkers;

	PlacementPrivacyFacts(List<Node> nodes, List<PrivacyFact> facts, int numWorkers) {
		Objects.requireNonNull(nodes, "nodes");
		Objects.requireNonNull(facts, "facts");
		if(numWorkers < 0)
			throw new IllegalArgumentException("Federated worker count must be non-negative");
		if(nodes.size() != facts.size())
			throw new IllegalArgumentException("Privacy facts must exactly cover placement nodes");
		IdentityHashMap<CompiledHopKey,PrivacyFact> indexed = new IdentityHashMap<>();
		IdentityHashMap<CompiledHopKey,Privacy> privacy = new IdentityHashMap<>();
		for(int i = 0; i < nodes.size(); i++) {
			Node node = Objects.requireNonNull(nodes.get(i), "node");
			PrivacyFact fact = Objects.requireNonNull(facts.get(i), "privacy fact");
			if(fact.occurrence() != node.key() || fact.valueVersion() != node.valueVersion())
				throw new IllegalArgumentException("Privacy fact occurrence/value identity differs from graph node");
			if(indexed.put(fact.occurrence(), fact) != null)
				throw new IllegalArgumentException("Duplicate privacy fact occurrence");
			privacy.put(fact.occurrence(), fact.privacy());
		}
		this.orderedFacts = List.copyOf(facts);
		this.factsByIdentity = Collections.unmodifiableMap(indexed);
		this.privacyByIdentity = Collections.unmodifiableMap(privacy);
		this.numWorkers = numWorkers;
	}

	public List<PrivacyFact> orderedFacts() {
		return orderedFacts;
	}

	public PrivacyFact requireExact(CompiledHopKey occurrence) {
		PrivacyFact fact = factsByIdentity.get(Objects.requireNonNull(occurrence, "occurrence"));
		if(fact == null)
			throw new IllegalArgumentException("Privacy occurrence is foreign to this placement analysis");
		return fact;
	}

	public Privacy requirePrivacy(CompiledHopKey occurrence) {
		return requireExact(occurrence).privacy();
	}

	/** Identity-keyed, immutable compatibility projection. */
	public Map<CompiledHopKey,Privacy> asMap() {
		return privacyByIdentity;
	}

	public int numWorkers() {
		return numWorkers;
	}

	public String normalizedSignature() {
		return "workers=" + numWorkers + '\n' + String.join("\n",
			orderedFacts.stream().map(PrivacyFact::normalizedSignature).toList());
	}
}
