/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.Map;

import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Regression for an invalid ALS refinement replacing the executable initial DP forest. */
@net.jcip.annotations.NotThreadSafe
public class CampaignBG014DpAlsExecutableIncumbentRedTest {
	@Test
	public void wanMidAlsKeepsExecutableInitialForestAtProductionIterationCount() throws Exception {
		Map<String,String> oldProperties =
			CampaignBG014AlsPartitionedComputeCostRedTest.installWanMidCostProperties();
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = CampaignBG014AlsPartitionedComputeCostRedTest.als(1, 10);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			NormalizedPlannerResult dp = new FederatedPlannerDpFedCostBased()
				.selectProgram(program, null, null, analysis).normalizedResult();
			Assert.assertEquals("DP must retain one executable exact selection per decision",
				analysis.graph().decisionNodes().size(), dp.selectedStates().size());
		}
		finally {
			CampaignBG014AlsPartitionedComputeCostRedTest.restoreProperties(oldProperties);
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}
}
