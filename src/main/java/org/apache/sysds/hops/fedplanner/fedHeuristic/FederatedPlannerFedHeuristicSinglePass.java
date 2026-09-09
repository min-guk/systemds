/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedHeuristic;

import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;

/**
 * Apache-style FedAll preference with analysis-owned local aggregate-vector paths.
 * The adapter applies the Heuristic policy; the shared selector retains FedFirst's
 * producer-first FED/FOUT preference and stops at the first legal assignment.
 * Scalar/vector-only continuations prefer legal CP supply, including shared collection
 * of public vector siblings. Other work re-enters FED through certified input frontiers.
 */
public final class FederatedPlannerFedHeuristicSinglePass extends FederatedPlannerFedHeuristic {
	private final HeuristicPlacementAdapter adapter = new HeuristicPlacementAdapter(
		new PolicyFirstFeasiblePlacementSelector());

	@Override
	public HeuristicPlacementAdapter.Result select(PlacementAnalysis analysis,
		Set<ValueVersionKey> markers) {
		return adapter.select(analysis, markers);
	}
}
