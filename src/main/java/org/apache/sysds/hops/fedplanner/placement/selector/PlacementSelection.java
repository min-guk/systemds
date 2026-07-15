/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Immutable exact assignment and its proof. */
public record PlacementSelection(Map<CompiledHopKey, PlacementState> assignment,
	Set<RelocationActionKey> selectedRelocations, PlacementScore score, PlacementCertificate certificate) {
	public PlacementSelection {
		Objects.requireNonNull(assignment, "assignment");
		Objects.requireNonNull(selectedRelocations, "selectedRelocations");
		Objects.requireNonNull(score, "score");
		Objects.requireNonNull(certificate, "certificate");
		List<Map.Entry<CompiledHopKey, PlacementState>> entries = new ArrayList<>(assignment.entrySet());
		entries.sort(Map.Entry.comparingByKey());
		Map<CompiledHopKey, PlacementState> ordered = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey, PlacementState> entry : entries)
			ordered.put(Objects.requireNonNull(entry.getKey(), "assignment key"),
				Objects.requireNonNull(entry.getValue(), "assignment state"));
		assignment = Collections.unmodifiableMap(ordered);
		List<RelocationActionKey> relocations = new ArrayList<>(selectedRelocations);
		Collections.sort(relocations);
		selectedRelocations = Collections.unmodifiableSet(new LinkedHashSet<>(relocations));
		if(!score.equals(certificate.incumbentScore()))
			throw new IllegalArgumentException("selection score and certificate incumbent differ");
	}
}
