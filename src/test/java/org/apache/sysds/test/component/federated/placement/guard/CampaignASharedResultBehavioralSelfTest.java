/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import org.junit.Assert;
import org.junit.Test;

/** Self-controls prove every intended behavioral arm is nonempty and independently RED on Task52. */
public class CampaignASharedResultBehavioralSelfTest {
	@Test public void fixtureUsesRealNonemptyR4KeysAndEqualFingerprintDistinctAnalyses() {
		var f = CampaignASharedResultBehavioralProbe.fixture();
		Assert.assertNotSame(f.exact(), f.foreign());
		Assert.assertEquals(f.exact().analysisFingerprint(), f.foreign().analysisFingerprint());
		Assert.assertFalse(f.states().isEmpty());
		Assert.assertTrue(f.relocations().size() >= 2);
	}

	@Test public void publicDraftReceiptAndInterfaceTypedBoundaryHaveNoGenericReturnTrap() {
		Assert.assertTrue(CampaignASharedResultBehavioralProbe.interfaceTypedBoundaryIsCompileFeasible());
	}
}
