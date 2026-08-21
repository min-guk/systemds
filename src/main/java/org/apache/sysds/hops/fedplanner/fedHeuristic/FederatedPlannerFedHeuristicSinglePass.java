/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedHeuristic;

import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;

/** Heuristic policy variant that stops after the first constraint-coherent filtered placement. */
public final class FederatedPlannerFedHeuristicSinglePass extends FederatedPlannerFedHeuristic {
	private final HeuristicPlacementAdapter adapter = new HeuristicPlacementAdapter(
		new PolicyFirstFeasiblePlacementSelector());

	@Override
	public HeuristicPlacementAdapter.Result select(PlacementAnalysis analysis,
		Set<ValueVersionKey> markers) {
		return adapter.select(analysis, markers);
	}
}
