/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.ScalarLiteralFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Immutable shape metadata derived at the placement-analysis construction boundary. */
final class PlacementShapeFacts {
	private final Map<CompiledHopKey, NodeShapeFact> facts;
	private final Map<CompiledHopKey, AbstractShapeFact> abstractFacts;
	private final Map<CompiledHopKey, ScalarLiteralFact> scalarLiterals;

	PlacementShapeFacts(Map<CompiledHopKey, NodeShapeFact> facts,
		Map<CompiledHopKey, AbstractShapeFact> abstractFacts,
		Map<CompiledHopKey, ScalarLiteralFact> scalarLiterals,
		Set<CompiledHopKey> expectedKeys) {
		Objects.requireNonNull(facts, "facts");
		Objects.requireNonNull(abstractFacts, "abstractFacts");
		Objects.requireNonNull(scalarLiterals, "scalarLiterals");
		Objects.requireNonNull(expectedKeys, "expectedKeys");
		if(!facts.keySet().equals(expectedKeys))
			throw new IllegalArgumentException("Shape-fact keys must exactly match placement projection keys");
		if(!abstractFacts.keySet().equals(expectedKeys))
			throw new IllegalArgumentException("Abstract-shape keys must exactly match placement projection keys");
		if(!expectedKeys.containsAll(scalarLiterals.keySet()))
			throw new IllegalArgumentException("Scalar-literal facts reference a foreign placement projection");
		this.facts = Map.copyOf(facts);
		this.abstractFacts = Map.copyOf(abstractFacts);
		this.scalarLiterals = Map.copyOf(scalarLiterals);
	}

	PlacementShapeFacts(Map<CompiledHopKey, NodeShapeFact> facts, Set<CompiledHopKey> expectedKeys) {
		this(facts, facts.entrySet().stream().collect(java.util.stream.Collectors.toMap(
			Map.Entry::getKey, entry -> AbstractShapeFact.fromConcrete(entry.getValue()),
			(left, right) -> left, java.util.LinkedHashMap::new)), Map.of(), expectedKeys);
	}

	Set<CompiledHopKey> keys() {
		return facts.keySet();
	}

	Optional<NodeShapeFact> shapeFact(CompiledHopKey key) {
		return Optional.ofNullable(facts.get(Objects.requireNonNull(key, "key")));
	}

	Optional<AbstractShapeFact> abstractShapeFact(CompiledHopKey key) {
		return Optional.ofNullable(abstractFacts.get(Objects.requireNonNull(key, "key")));
	}

	Optional<ScalarLiteralFact> scalarLiteralFact(CompiledHopKey key) {
		return Optional.ofNullable(scalarLiterals.get(Objects.requireNonNull(key, "key")));
	}

	String normalizedSignature() {
		java.util.List<String> rows = new java.util.ArrayList<>();
		for(CompiledHopKey key : new java.util.TreeSet<>(abstractFacts.keySet())) {
			String scalar = scalarLiterals.containsKey(key)
				? scalarLiterals.get(key).normalizedSignature() : "-";
			rows.add(key.normalizedSignature() + "|abstract="
				+ abstractFacts.get(key).normalizedSignature() + "|scalar=" + scalar);
		}
		return String.join("\n", rows);
	}
}
