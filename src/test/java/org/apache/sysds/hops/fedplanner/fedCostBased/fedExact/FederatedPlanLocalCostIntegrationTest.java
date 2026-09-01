/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class FederatedPlanLocalCostIntegrationTest {
	@Test
	public void productionFixtureEmitsOneCompletePrivacyFilteredSelection() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-16");
		PlacementAnalysis analysis =
			CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		ExactPlacementInput receipt = new FederatedPlanLocalCost()
			.rewriteProgram(program, null, null, analysis);

		Assert.assertSame(analysis, receipt.analysis());
		Assert.assertEquals("DP-LocalConflict", receipt.normalizedResult().plannerId());
		Assert.assertEquals(PlacementEmissionTransaction.canonicalPlanHash(receipt.normalizedResult()),
			receipt.emissionReceipt().planHash());
		Assert.assertTrue(receipt.emissionReceipt().applied());
		Assert.assertEquals(analysis.graph().decisionNodes().size(),
			receipt.exactSelectedStates().size());
		analysis.graph().decisionNodes().forEach(node -> Assert.assertTrue(
			"selection must come from the common privacy-filtered legal domain",
			node.legalAlternatives().stream()
				.anyMatch(state -> state == receipt.exactSelectedStates().get(node.key()))));
		Set<?> expected = Set.copyOf(analysis.occurrences().stream().map(o -> o.key()).toList());
		Set<?> actual = Set.copyOf(new ExactPlacementAdapter().select(analysis, receipt)
			.selectedReceipts().stream().map(r -> r.planningKey()).toList());
		Assert.assertEquals(expected, actual);
	}
}
