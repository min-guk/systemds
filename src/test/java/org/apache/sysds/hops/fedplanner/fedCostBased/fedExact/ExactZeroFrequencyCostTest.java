/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class ExactZeroFrequencyCostTest {
	@Test
	public void unreachableProducerAndConsumersHaveZeroActivation() {
		Assert.assertEquals(0.0, ExactPhysicalCostModel.reusableActivationUnion(
			List.of(List.of()), List.of(0.0), 0.0), 0.0);
	}

	@Test
	public void deadBroadEventCannotSubsumeALiveNarrowEvent() {
		var liveBranch = new ExactPhysicalCostModel.BranchLiteral("main/0", true);
		Assert.assertEquals(0.5, ExactPhysicalCostModel.reusableActivationUnion(
			List.of(List.of(), List.of(liveBranch)), List.of(0.0, 0.5), 1.0), 0.0);
	}

	@Test
	public void knownBranchAndDeadSiblingRetainOneLiveMaterialization() {
		var live = new ExactPhysicalCostModel.BranchLiteral("main/0", true);
		var dead = new ExactPhysicalCostModel.BranchLiteral("main/0", false);
		Assert.assertEquals(1.0, ExactPhysicalCostModel.reusableActivationUnion(
			List.of(List.of(live), List.of(dead)), List.of(1.0, 0.0), 1.0), 0.0);
	}

	@Test
	public void zeroDoesNotAuthorizeInvalidWeights() {
		for(double invalid : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
			Assert.assertThrows(IllegalArgumentException.class, () ->
				ExactPhysicalCostModel.reusableActivationUnion(
					List.of(List.of()), List.of(invalid), 1.0));
			Assert.assertThrows(IllegalArgumentException.class, () ->
				ExactPhysicalCostModel.reusableActivationUnion(
					List.of(List.of()), List.of(0.0), invalid));
		}
		Assert.assertThrows(IllegalArgumentException.class, () ->
			ExactPhysicalCostModel.reusableActivationUnion(
				List.of(List.of()), List.of(1.0), 0.0));
	}
}
