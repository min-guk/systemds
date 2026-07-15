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

package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.junit.Assert;
import org.junit.Test;

/** Live adversarial RED contracts for Heuristic metadata and multi-marker safety. */
public class CampaignBHeuristicMetadataAdversarialRedTest {
	@Test
	public void unknownMetadataReasonVetoesWithoutDetailParsing() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.matchingExclusion(
			ReasonCode.UNKNOWN_METADATA, "missingRequiredFacts=nnz");
		var before = scenario.snapshot();
		var selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers());
		Assert.assertFalse("UNKNOWN_METADATA matching the synthesized state must veto candidate proof",
			R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(selection, scenario));
		R4Heuristic2Probe.unchanged(before, scenario.snapshot());
	}

	@Test
	public void unknownNodeDimensionsCannotBorrowAnchorExtents() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.nodeDimensions(-1, -1);
		var before = scenario.snapshot();
		var selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers());
		Assert.assertFalse("unknown node dimensions cannot become KNOWN_COMPATIBLE_DIMENSIONS",
			R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(selection, scenario));
		R4Heuristic2Probe.unchanged(before, scenario.snapshot());
	}

	@Test
	public void incompatibleKnownNodeDimensionsCannotBorrowAnchorExtents() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.nodeDimensions(5, 2);
		var before = scenario.snapshot();
		var selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers());
		Assert.assertFalse("known node dimensions must exactly match durable-anchor geometry",
			R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(selection, scenario));
		R4Heuristic2Probe.unchanged(before, scenario.snapshot());
	}

	@Test
	public void unsupportedOperationShapeVetoesMatchingTargetState() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.matchingExclusion(
			ReasonCode.UNSUPPORTED_OPERATION_SHAPE, "operation=aggregate");
		var before = scenario.snapshot();
		var selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers());
		Assert.assertFalse("UNSUPPORTED_OPERATION_SHAPE must veto the exact target state",
			R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(selection, scenario));
		R4Heuristic2Probe.unchanged(before, scenario.snapshot());
	}

	@Test
	public void broadcastRequiresTypedSafetyButKnownMatrixRemainsAdmissible() throws Exception {
		List<String> violations = new ArrayList<>();
		for(var kind : List.of(R4HeuristicMetadataFixtureBridge.BroadcastCase.UNKNOWN,
			R4HeuristicMetadataFixtureBridge.BroadcastCase.UNSUPPORTED,
			R4HeuristicMetadataFixtureBridge.BroadcastCase.NON_MATRIX,
			R4HeuristicMetadataFixtureBridge.BroadcastCase.MISSING_HOP)) {
			var unsafe = R4HeuristicMetadataFixtureBridge.broadcast(kind); var before = unsafe.snapshot();
			try {
				var selected = R4Heuristic2AdapterBridge.select(unsafe.analysis(), unsafe.markers());
				if(R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(selected, unsafe))
					violations.add("BROADCAST_UNSAFE_" + kind);
			}
			catch(AssertionError safelyRejected) {
				// Rejection is safe; the RED signal is an unsafe synthesized candidate, never reflection itself.
			}
			R4Heuristic2Probe.unchanged(before, unsafe.snapshot());
		}
		var safe = R4HeuristicMetadataFixtureBridge.broadcast(
			R4HeuristicMetadataFixtureBridge.BroadcastCase.SAFE_MATRIX); var safeBefore = safe.snapshot();
		var safeSelection = R4Heuristic2AdapterBridge.select(safe.analysis(), safe.markers());
		if(!R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(safeSelection, safe))
			violations.add("BROADCAST_SAFE_MATRIX_REJECTED");
		Assert.assertEquals("broadcast safety must be explicit and positive", List.of(), violations);
		R4Heuristic2Probe.unchanged(safeBefore, safe.snapshot());
	}

	@Test
	public void twoMarkersUseUnionOfExactDescendantsDeterministically() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.multiMarker(); var fixture = scenario.fixture();
		var values = new ArrayList<>(scenario.contributions().keySet());
		var forward = new java.util.LinkedHashSet<>(values);
		java.util.Collections.reverse(values); var reverse = new java.util.LinkedHashSet<>(values);
		var before = R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis());
		R4Heuristic2AdapterBridge.Selection selected;
		try { selected = R4Heuristic2AdapterBridge.select(fixture.analysis(), forward); }
		catch(AssertionError e) { throw new AssertionError("MULTI_MARKER_SINGLETON_REJECTION|count="
			+ forward.size(), e); }
		Set<String> expectedKeys = new java.util.LinkedHashSet<>(); List<String> expectedExclusions = new ArrayList<>();
		for(var contribution : scenario.contributions().values()) {
			Assert.assertFalse("MULTI_MARKER_EMPTY_CONTRIBUTION|marker=" + contribution.marker(),
				contribution.candidateKeys().isEmpty());
			expectedKeys.addAll(contribution.candidateKeys()); expectedExclusions.addAll(contribution.exclusions());
			Assert.assertTrue("MULTI_MARKER_ATTRIBUTION_MISSING|marker=" + contribution.marker(),
				selected.exclusions().containsAll(contribution.exclusions()));
		}
		Assert.assertEquals("MULTI_MARKER_EXACT_UNION", Set.copyOf(expectedKeys),
			R4HeuristicMetadataFixtureBridge.exclusionKeys(selected));
		String independent = scenario.independent().normalizedSignature();
		Assert.assertTrue("MULTI_MARKER_INDEPENDENT_FED_CANDIDATE", selected.candidates().contains(independent
			+ '=' + scenario.independentFedFout()));
		Assert.assertEquals("MULTI_MARKER_INDEPENDENT_SELECTED_FED", scenario.independentFedFout(),
			selected.assignments().get(independent));
		Assert.assertTrue("MULTI_MARKER_CROSS_ANCHOR_LAUNDERING", selected.exclusions().stream()
			.noneMatch(x -> x.contains(independent)));
		var reversed = R4Heuristic2AdapterBridge.select(fixture.analysis(), reverse);
		Assert.assertEquals("MULTI_MARKER_REVERSED_CANDIDATES", selected.candidates(), reversed.candidates());
		Assert.assertEquals("MULTI_MARKER_REVERSED_EXCLUSIONS", selected.exclusions(), reversed.exclusions());
		Assert.assertEquals("MULTI_MARKER_REVERSED_ASSIGNMENT", selected.assignments(), reversed.assignments());
		Assert.assertEquals("MULTI_MARKER_REVERSED_CERTIFICATE", selected.certificate(), reversed.certificate());
		Assert.assertEquals("MULTI_MARKER_REVERSED_PLAN", selected.planFingerprint(), reversed.planFingerprint());
		R4Heuristic2Probe.unchanged(before, R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis()));
	}

	@Test
	public void emptyProvableRefedSetIsExactWithoutInventedExclusion() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.noProvableCandidate();
		var before = scenario.snapshot();
		R4Heuristic2AdapterBridge.Selection selection;
		try { selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers()); }
		catch(AssertionError e) { throw new AssertionError("EMPTY_POLICY_VIEW_REJECTED|expected=SAFE_EXHAUSTIVE", e); }
		Assert.assertEquals("safe policy view may have no synthesized refederation candidate",
			List.of(), selection.exclusions());
		Assert.assertEquals("EMPTY_POLICY_CANDIDATE_UNIVERSE_UNCHANGED",
			scenario.analysis().graph().normalizedCandidateUniverse(), selection.candidates());
		Assert.assertEquals("EMPTY_POLICY_NO_RELOCATIONS", List.of(), selection.relocations());
		Assert.assertEquals("EMPTY_POLICY_NO_OBLIGATIONS", List.of(), selection.obligations());
		Assert.assertFalse("safe empty policy view cannot enable fallback", selection.certificate().fallback());
		Assert.assertEquals("safe empty policy view remains exhaustive", "EXHAUSTED",
			selection.certificate().termination());
		Assert.assertEquals("EMPTY_POLICY_UNIVERSE_COUNT", selection.candidates().size(),
			selection.certificate().universe());
		Assert.assertTrue("EMPTY_POLICY_NO_REPAIR_FACT", selection.plannerFacts().entrySet().stream()
			.noneMatch(e -> (e.getKey() + e.getValue()).toLowerCase().matches(".*(repair|fallback).*true.*")));
		R4Heuristic2Probe.unchanged(before, scenario.snapshot());
	}

	@Test
	public void suppliedAnalysisGraphHopsAndRegistriesRemainImmutable() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.standard();
		var before = scenario.snapshot();
		var selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers());
		Assert.assertSame("supplied PlacementAnalysis identity", scenario.analysis(), selection.analysis());
		Assert.assertEquals("supplied analysis fingerprint", scenario.analysis().analysisFingerprint(),
			selection.analysisFingerprint());
		R4Heuristic2Probe.unchanged(before, scenario.snapshot());
		Assert.assertEquals("adapter must not emit registry state", List.of(), selection.refedRegistry());
		Assert.assertEquals("adapter must not emit FOUT registry state", List.of(), selection.foutRegistry());
		Assert.assertEquals("adapter must not emit local registry state", List.of(), selection.localRegistry());
	}
}
