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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
	import java.util.Map;
	import java.util.TreeMap;

/** Observational normalized comparison; it never selects or repairs a plan. */
public final class PlacementShadowComparator {
	public record Diff(List<String> candidates, List<String> exclusions, List<String> constraints,
		List<String> identities, List<String> valueVersions, List<String> provenance, List<String> anchors,
		List<String> relocations, List<String> obligations,
		List<String> legalAssignments, boolean normalizedSignatureEqual) {
		public Diff {
			candidates = immutable(candidates); exclusions = immutable(exclusions);
			constraints = immutable(constraints); identities = immutable(identities);
			valueVersions = immutable(valueVersions); provenance = immutable(provenance); anchors = immutable(anchors);
			relocations = immutable(relocations); obligations = immutable(obligations);
			legalAssignments = immutable(legalAssignments);
		}
		public boolean isEmpty() { return candidates.isEmpty() && exclusions.isEmpty() && constraints.isEmpty()
			&& identities.isEmpty() && valueVersions.isEmpty() && provenance.isEmpty() && anchors.isEmpty()
			&& relocations.isEmpty() && obligations.isEmpty()
			&& legalAssignments.isEmpty() && normalizedSignatureEqual; }
	}

	public Diff compare(NeutralPlacementGraph expected, NeutralPlacementGraph actual) {
		return compare(expected, actual, true);
	}

	public Diff compareProductionSurfaces(NeutralPlacementGraph expected, NeutralPlacementGraph actual) {
		return compare(expected, actual, false);
	}

	private Diff compare(NeutralPlacementGraph expected, NeutralPlacementGraph actual, boolean boundedAssignments) {
		return new Diff(delta(expected.normalizedCandidateUniverse(), actual.normalizedCandidateUniverse()),
			delta(expected.normalizedExclusions(), actual.normalizedExclusions()),
			delta(expected.normalizedConstraints(), actual.normalizedConstraints()),
			delta(expected.normalizedIdentities(), actual.normalizedIdentities()),
			delta(expected.normalizedValueVersions(), actual.normalizedValueVersions()),
			delta(expected.normalizedProvenance(), actual.normalizedProvenance()),
			delta(expected.normalizedAnchors(), actual.normalizedAnchors()),
			delta(expected.normalizedRelocationActions(), actual.normalizedRelocationActions()),
			delta(expected.normalizedObligations(), actual.normalizedObligations()),
			boundedAssignments ? delta(expected.normalizedLegalAssignments(), actual.normalizedLegalAssignments()) : List.of(),
			expected.normalizedSignature().equals(actual.normalizedSignature()));
	}

	private static List<String> delta(List<String> left, List<String> right) {
		List<String> result = new ArrayList<>();
		Map<String,Integer> counts = new TreeMap<>();
		for(String value : left) counts.merge(value, 1, Integer::sum);
		for(String value : right) counts.merge(value, -1, Integer::sum);
		for(Map.Entry<String,Integer> entry : counts.entrySet()) {
			for(int i = 0; i < entry.getValue(); i++) result.add("-" + entry.getKey());
			for(int i = 0; i > entry.getValue(); i--) result.add("+" + entry.getKey());
		}
		return result;
	}
	private static List<String> immutable(List<String> values) {
		List<String> copy = new ArrayList<>(values); Collections.sort(copy); return Collections.unmodifiableList(copy);
	}
}
