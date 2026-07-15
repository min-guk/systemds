/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Recomputable proof metadata for an exact selector result. */
public record PlacementCertificate(PlacementScore incumbentScore, PlacementScore finalUpperBound,
	long exploredCount, long prunedCount, String assignmentHash, String graphFingerprint,
	int graphNodeCount, int graphEdgeCount, int componentCount, int closureDepth,
	List<ComponentBound> componentBounds, String boundDerivation, String generatorSizeClass,
	long generatorSeed, TerminationReason terminationReason) {
	public enum TerminationReason {
		EXHAUSTED,
		TIGHT_BOUND_EQUALITY
	}

	public PlacementCertificate {
		Objects.requireNonNull(incumbentScore, "incumbentScore");
		Objects.requireNonNull(finalUpperBound, "finalUpperBound");
		Objects.requireNonNull(assignmentHash, "assignmentHash");
		Objects.requireNonNull(graphFingerprint, "graphFingerprint");
		Objects.requireNonNull(componentBounds, "componentBounds");
		Objects.requireNonNull(boundDerivation, "boundDerivation");
		Objects.requireNonNull(generatorSizeClass, "generatorSizeClass");
		Objects.requireNonNull(terminationReason, "terminationReason");
		if(exploredCount < 0 || prunedCount < 0 || graphNodeCount < 0 || graphEdgeCount < 0
			|| componentCount < 0 || closureDepth < 0)
			throw new IllegalArgumentException("certificate counts must be non-negative");
		if(boundDerivation.isBlank() || generatorSizeClass.isBlank())
			throw new IllegalArgumentException("certificate descriptions must not be blank");
		List<ComponentBound> bounds = new ArrayList<>(componentBounds);
		Collections.sort(bounds);
		componentBounds = List.copyOf(bounds);
		if(componentBounds.size() != componentCount)
			throw new IllegalArgumentException("component bound count differs from componentCount");
		if(finalUpperBound.compareTo(incumbentScore) > 0)
			throw new IllegalArgumentException("successful certificate retains a superior upper bound");
	}

	public record ComponentBound(String componentIdentity, Set<String> normalizedNodeSet,
		long graphNodeCount, long graphEdgeCount, PlacementScore upperBound, String derivation)
		implements Comparable<ComponentBound> {
		public ComponentBound {
			Objects.requireNonNull(componentIdentity, "componentIdentity");
			Objects.requireNonNull(normalizedNodeSet, "normalizedNodeSet");
			Objects.requireNonNull(upperBound, "upperBound");
			Objects.requireNonNull(derivation, "derivation");
			if(componentIdentity.isBlank() || derivation.isBlank())
				throw new IllegalArgumentException("component proof descriptions must not be blank");
			if(graphNodeCount < 0 || graphEdgeCount < 0)
				throw new IllegalArgumentException("component counts must be non-negative");
			List<String> nodes = new ArrayList<>(normalizedNodeSet);
			Collections.sort(nodes);
			normalizedNodeSet = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
		}

		@Override
		public int compareTo(ComponentBound that) {
			return componentIdentity.compareTo(that.componentIdentity);
		}
	}
}
