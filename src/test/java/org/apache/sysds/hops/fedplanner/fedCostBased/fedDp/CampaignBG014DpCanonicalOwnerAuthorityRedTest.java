/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Canonical owner identity must preserve exact candidate and relocation receipts. */
public class CampaignBG014DpCanonicalOwnerAuthorityRedTest {
	@Test
	public void missingExpectedCandidateDoesNotWildcardAnActualCandidate() {
		CandidateSelectionReceipt actual = Mockito.mock(CandidateSelectionReceipt.class);
		Assert.assertFalse(FederatedPlannerDpFedCostBased.exactDirectAuthority(
			actual, List.of(), null, List.of()));
	}

	@Test
	public void emptyExpectedRelocationsDoNotWildcardActualRelocations() {
		RelocationChoiceReceipt actual = Mockito.mock(RelocationChoiceReceipt.class);
		Assert.assertFalse(FederatedPlannerDpFedCostBased.exactDirectAuthority(
			null, List.of(actual), null, List.of()));
	}

	@Test
	public void identicalDirectAuthorityRemainsCanonicalizable() {
		CandidateSelectionReceipt candidate = Mockito.mock(CandidateSelectionReceipt.class);
		RelocationChoiceReceipt relocation = Mockito.mock(RelocationChoiceReceipt.class);
		Assert.assertTrue(FederatedPlannerDpFedCostBased.exactDirectAuthority(
			candidate, List.of(relocation), candidate, List.of(relocation)));
	}
}
