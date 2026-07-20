/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.parser;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Test-only access to the production final-Hop-boundary authority binding. */
public final class CampaignBG014PlacementAuthorityTestBridge {
	private CampaignBG014PlacementAuthorityTestBridge() { }

	public static PlacementAnalysis bindAtFinalHopBoundary(DMLProgram program) {
		return program.bindPlacementAnalysisAtFinalHopBoundary();
	}
}
