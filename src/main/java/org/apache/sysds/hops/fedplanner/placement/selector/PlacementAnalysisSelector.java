/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Placement selection that consumes the shared analysis and an optional policy projection of its graph. */
public interface PlacementAnalysisSelector {
	PlacementSelection select(PlacementAnalysis analysis, NeutralPlacementGraph graph);

	default PlacementSelection select(PlacementAnalysis analysis) {
		return select(analysis, analysis.graph());
	}
}
