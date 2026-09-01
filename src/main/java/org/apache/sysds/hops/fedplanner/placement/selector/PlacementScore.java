/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.Objects;

/** Exact FedAll objective. Greater scores are better. */
public record PlacementScore(int emittedFedCount, int foutCount, int distinctRelocationCount,
	String normalizedSignature) implements Comparable<PlacementScore> {
	public PlacementScore {
		if(emittedFedCount < 0 || foutCount < 0 || distinctRelocationCount < 0)
			throw new IllegalArgumentException("placement score counts must be non-negative");
		Objects.requireNonNull(normalizedSignature, "normalizedSignature");
	}

	@Override
	public int compareTo(PlacementScore that) {
		int comparison = Integer.compare(emittedFedCount, that.emittedFedCount);
		if(comparison != 0)
			return comparison;
		comparison = Integer.compare(foutCount, that.foutCount);
		if(comparison != 0)
			return comparison;
		comparison = Integer.compare(that.distinctRelocationCount, distinctRelocationCount);
		if(comparison != 0)
			return comparison;
		return that.normalizedSignature.compareTo(normalizedSignature);
	}
}
