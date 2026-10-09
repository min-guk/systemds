/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.test.functions.federated.fedplanning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.junit.Assert;
import org.junit.Test;

public class MatrixCampaignProbePlanEvidenceTest {
	@Test
	public void splitEvidenceReconstructsTheProductionCanonicalHash() {
		String objective = "physical-ve-objective=4607182418800017408;costSurface=surface;"
			+ "assignment=[];maxFactorCells=17";
		NormalizedPlannerResult result = emptyResult(objective);

		MatrixCampaignProbe.PlanEvidence evidence = MatrixCampaignProbe.planEvidence(result);

		Assert.assertEquals(Long.valueOf(4607182418800017408L), evidence.objectiveRawBits());
		Assert.assertEquals(6, evidence.sectionFingerprints().size());
		Assert.assertTrue(evidence.sectionCounts().values().stream().allMatch(count -> count == 0));
		Assert.assertTrue(evidence.selectedCandidateSelections().isEmpty());
		Assert.assertEquals(PlacementEmissionTransaction.canonicalPlanHash(result),
			evidence.reconstructedPlanFingerprint());
		Assert.assertNotEquals(evidence.objectiveCertificateSha256(),
			evidence.selectedPlanFieldsFingerprint());
	}

	@Test
	public void localObjectiveRawBitsAreRecognized() {
		MatrixCampaignProbe.PlanEvidence evidence = MatrixCampaignProbe.planEvidence(
			emptyResult("local-conflict-objective=0;assignment=[]"));

		Assert.assertEquals(Long.valueOf(0), evidence.objectiveRawBits());
	}

	@Test
	public void ambiguousRawObjectiveFieldsFailClosed() {
		NormalizedPlannerResult result = emptyResult(
			"physical-ve-objective=1;local-conflict-objective=2;assignment=[]");

		Assert.assertThrows(IllegalStateException.class, () -> MatrixCampaignProbe.planEvidence(result));
	}

	private static NormalizedPlannerResult emptyResult(String objective) {
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		RelocationSelections.CanonicalOrderIndex relocationOrder =
			mock(RelocationSelections.CanonicalOrderIndex.class);
		when(analysis.canonicalCandidateReceipts(any())).thenReturn(List.of());
		when(analysis.relocationOrder()).thenReturn(relocationOrder);
		when(relocationOrder.canonicalChoices(any())).thenReturn(List.of());
		when(relocationOrder.canonicalActions(any())).thenReturn(List.of());
		return new NormalizedPlannerResult() {
			@Override
			public PlacementAnalysis analysis() {
				return analysis;
			}

			@Override
			public String plannerId() {
				return "EXACT";
			}

			@Override
			public String analysisFingerprint() {
				return "a".repeat(64);
			}

			@Override
			public Map<CompiledHopKey,PlacementState> selectedStates() {
				return Map.of();
			}

			@Override
			public Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates() {
				return Map.of();
			}

			@Override
			public List<RelocationActionKey> selectedRelocations() {
				return List.of();
			}

			@Override
			public List<CandidateSelectionReceipt> selectedCandidateSelections() {
				return List.of();
			}

			@Override
			public List<RelocationChoiceReceipt> selectedRelocationChoices() {
				return List.of();
			}

			@Override
			public String objectiveCertificate() {
				return objective;
			}

			@Override
			public Set<String> sharedSupplyLifetimes() {
				return Set.of();
			}

			@Override
			public String normalizedPlanFingerprint() {
				return "unused-by-split-helper";
			}
		};
	}
}
