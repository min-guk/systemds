/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Contracts for lossless DP pruning by complete future-observable boundaries. */
public class FederatedPlannerDpBoundaryFrontierTest {
	@Test
	public void retainsEveryDistinctBoundarySignatureBeyondLegacyTopK() {
		FedPlanVariants variants = variants(FederatedOutput.FOUT);
		int expected = 0;
		for(FType fType : FType.values())
			for(boolean shapeDependent : List.of(false, true)) {
				PlacementState state = new PlacementState(
					ExecType.CP, FederatedOutput.FOUT, fType, shapeDependent);
				variants.addFedPlan(plan(variants, state, ++expected));
			}

		Assert.assertTrue(variants.pruneFedPlans());
		Assert.assertTrue("fixture must exceed the removed top-8 frontier",
			expected > 8);
		Assert.assertEquals("distinct complete boundaries must never be truncated",
			expected, variants.getFedPlanVariants().size());
	}

	@Test
	public void retainsOnlyTheCheapestPlanForAnIdenticalBoundarySignature() {
		FedPlanVariants variants = variants(FederatedOutput.FOUT);
		PlacementState state = new PlacementState(
			ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		FedPlan expensive = plan(variants, state, 9d);
		FedPlan cheapest = plan(variants, state, 3d);
		FedPlan middle = plan(variants, state, 5d);
		variants.addFedPlan(expensive);
		variants.addFedPlan(cheapest);
		variants.addFedPlan(middle);

		Assert.assertTrue(variants.pruneFedPlans());
		Assert.assertEquals(1, variants.getFedPlanVariants().size());
		Assert.assertSame("minimum cumulative-cost representative", cheapest,
			variants.getFedPlanVariants().get(0));
	}

	@Test
	public void materializationAndUploadTypeRemainPartOfTheBoundary() {
		FedPlanVariants variants = variants(FederatedOutput.FOUT);
		PlacementState state = new PlacementState(
			ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		FedPlan accounted = plan(variants, state, 1d);
		accounted.setFoutMaterializationAccounted(true);
		FedPlan unaccounted = plan(variants, state, 2d);
		FedPlan differentUpload = plan(variants, state, 3d);
		differentUpload.setCpFoutType(FType.COL);
		variants.addFedPlan(accounted);
		variants.addFedPlan(unaccounted);
		variants.addFedPlan(differentUpload);

		Assert.assertTrue(variants.pruneFedPlans());
		Assert.assertEquals("future boundary-cost authority must not be merged",
			3, variants.getFedPlanVariants().size());
	}

	private static FedPlanVariants variants(FederatedOutput output) {
		Hop hop = Mockito.mock(Hop.class);
		Mockito.when(hop.getHopID()).thenReturn(7L);
		Mockito.when(hop.getOpString()).thenReturn("boundary-frontier-fixture");
		HopCommon common = new HopCommon(hop, 1d, 1d, 1d, 1, List.of());
		common.setSelfCost(1d);
		common.setForwardingCost(1d);
		return new FedPlanVariants(common, output);
	}

	private static FedPlan plan(FedPlanVariants variants, PlacementState state, double cost) {
		FedPlan plan = new FedPlan(cost, variants, List.of());
		plan.setExecType(state.execType());
		plan.setFType(state.fType());
		plan.setCpFoutType(state.fType());
		plan.setSelectedPlacementState(state);
		return plan;
	}
}
