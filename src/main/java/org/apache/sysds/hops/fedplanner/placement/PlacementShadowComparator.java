/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Observational normalized comparison; it never selects or repairs a plan. */
public final class PlacementShadowComparator {
	public record Diff(List<String> candidates, List<String> exclusions, List<String> constraints,
		List<String> identities, List<String> relocations, List<String> obligations) {
		public Diff {
			candidates = immutable(candidates); exclusions = immutable(exclusions);
			constraints = immutable(constraints); identities = immutable(identities);
			relocations = immutable(relocations); obligations = immutable(obligations);
		}
		public boolean isEmpty() { return candidates.isEmpty() && exclusions.isEmpty() && constraints.isEmpty()
			&& identities.isEmpty() && relocations.isEmpty() && obligations.isEmpty(); }
	}

	public Diff compare(NeutralPlacementGraph expected, NeutralPlacementGraph actual) {
		return new Diff(delta(expected.normalizedCandidateUniverse(), actual.normalizedCandidateUniverse()),
			delta(expected.normalizedExclusions(), actual.normalizedExclusions()),
			delta(expected.normalizedConstraints(), actual.normalizedConstraints()),
			delta(expected.normalizedIdentities(), actual.normalizedIdentities()),
			delta(expected.normalizedRelocationActions(), actual.normalizedRelocationActions()),
			delta(expected.normalizedObligations(), actual.normalizedObligations()));
	}

	private static List<String> delta(List<String> left, List<String> right) {
		List<String> result = new ArrayList<>();
		for(String value : left) if(!right.contains(value)) result.add("-" + value);
		for(String value : right) if(!left.contains(value)) result.add("+" + value);
		return result;
	}
	private static List<String> immutable(List<String> values) {
		List<String> copy = new ArrayList<>(values); Collections.sort(copy); return Collections.unmodifiableList(copy);
	}
}
