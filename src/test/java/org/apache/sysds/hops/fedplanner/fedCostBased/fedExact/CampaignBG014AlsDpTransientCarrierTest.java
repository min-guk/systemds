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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

@net.jcip.annotations.NotThreadSafe
public class CampaignBG014AlsDpTransientCarrierTest {
	@Test
	public void workerFiveProductionDepthUsesSelectedCloneInputForTransientCopy() throws Exception {
		try {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
			DMLProgram program = CampaignBG014AlsPartitionedComputeCostRedTest.als(5, 10);
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge
				.bindAtFinalHopBoundary(program);
			var invocation = new FederatedPlannerDpFedCostBased()
				.selectProgram(program, null, null, analysis);

			List<CompiledHopKey> innerMaskReads = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTREAD && "W".equals(data.getName())
					&& data.getBeginLine() == 125)
				.map(PlacementAnalysis.HopOccurrenceProjection::key)
				.toList();
			Assert.assertFalse("ALS worker-5 fixture must expose the inner-CG TRead W",
				innerMaskReads.isEmpty());
			for(CompiledHopKey read : innerMaskReads) {
				var state = invocation.normalizedResult().selectedStates().get(read);
				Assert.assertNotNull("DP must emit the exact inner-CG TRead W occurrence", state);
				Assert.assertEquals(ExecType.FED, state.execType());
				Assert.assertEquals(FederatedOutput.FOUT, state.output());
				Assert.assertEquals(FType.ROW, state.fType());
			}

			boolean exercisedCloneCarrier = analysis.compiledHopOccurrences().stream()
				.filter(occurrence -> occurrence.hop() instanceof DataOp data
					&& data.getOp() == OpOpData.TRANSIENTWRITE && "W".equals(data.getName()))
				.flatMap(occurrence -> invocation.memo()
					.getExactPlansAfterPrune(occurrence, FederatedOutput.FOUT).stream())
				.anyMatch(plan -> {
					var carrier = plan.getHopRef();
					var original = invocation.memo().resolveOriginalHop(carrier.getHopID());
					return carrier != original && carrier.getInput().size() == 1
						&& plan.getExactChildPlanEdges().size() == 1
						&& plan.getExactChildPlanEdges().get(0).carrier() == carrier.getInput(0);
				});
			Assert.assertTrue("Regression must exercise an exact TWrite clone plan whose child edge"
				+ " is bound to that selected carrier's direct input", exercisedCloneCarrier);
		}
		finally {
			FederatedPlannerUtils.resetFederatedPlannerRunState();
		}
	}
}
