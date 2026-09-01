/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import org.junit.Assert;
import org.junit.Test;

/** Deterministic arithmetic contract for the fixed stage of one logical FED instruction. */
public class FederatedCostModelFixedInstructionStageTest {
	@Test
	public void fixedInstructionStageAddsIndependentLatencyAndControl() {
		Assert.assertEquals("Seven executions must each pay one millisecond of network latency"
			+ " plus one millisecond of coordinator control", 14.0,
			FederatedCostModel.computeFixedFederatedInstructionStageCost(7.0, 1.0, 1.0), 0.0);
		Assert.assertEquals(7.0,
			FederatedCostModel.computeFixedFederatedInstructionStageCost(7.0, 1.0, 0.0), 0.0);
		Assert.assertEquals(7.0,
			FederatedCostModel.computeFixedFederatedInstructionStageCost(7.0, 0.0, 1.0), 0.0);
		Assert.assertEquals("A branch probability is an execution weight, not a minimum-one count",
			1.0, FederatedCostModel.computeFixedFederatedInstructionStageCost(0.5, 1.0, 1.0), 0.0);
	}
}
