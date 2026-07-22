/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Public normalization bridge for planner roots whose native result is not a common adapter result. */
public final class NormalizedPlannerResults {
	private NormalizedPlannerResults() { }

	public static NormalizedPlannerResult create(PlacementAnalysis analysis, String plannerId,
		Map<CompiledHopKey, PlacementState> selectedStates, String objectiveCertificate) {
		Objects.requireNonNull(analysis, "analysis");
		List<RelocationActionKey> relocations = analysis.graph().relocationActions().stream()
			.filter(action -> analysis.graph().isRelocationActive(action, selectedStates))
			.map(action -> action.key()).toList();
		NormalizedPlannerResult draft = new Draft(analysis, plannerId, analysis.analysisFingerprint(),
			Map.copyOf(selectedStates), relocations, objectiveCertificate);
		return PlacementPlannerAdapter.normalize(analysis, draft);
	}

	private record Draft(PlacementAnalysis analysis, String plannerId, String analysisFingerprint,
		Map<CompiledHopKey, PlacementState> selectedStates, List<RelocationActionKey> selectedRelocations,
		String objectiveCertificate) implements NormalizedPlannerResult {
		@Override public String normalizedPlanFingerprint() { return "canonicalized-at-boundary"; }
	}
}
