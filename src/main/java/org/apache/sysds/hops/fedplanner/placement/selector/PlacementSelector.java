/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;

/** Pure planner-neutral selection over an immutable placement graph. */
public interface PlacementSelector {
	PlacementSelection select(NeutralPlacementGraph graph);
}
