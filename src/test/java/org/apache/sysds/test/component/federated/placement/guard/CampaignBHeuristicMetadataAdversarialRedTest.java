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
		var unsafe = R4HeuristicMetadataFixtureBridge.broadcast(true);
		var safe = R4HeuristicMetadataFixtureBridge.broadcast(false);
		var unsafeBefore = unsafe.snapshot();
		var safeBefore = safe.snapshot();
		var unsafeSelection = R4Heuristic2AdapterBridge.select(unsafe.analysis(), unsafe.markers());
		var safeSelection = R4Heuristic2AdapterBridge.select(safe.analysis(), safe.markers());
		List<String> violations = new ArrayList<>();
		if(R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(unsafeSelection, unsafe))
			violations.add("broadcast bypassed matching UNKNOWN_METADATA");
		if(!R4HeuristicMetadataFixtureBridge.hasSynthesizedCandidate(safeSelection, safe))
			violations.add("explicitly known matrix broadcast was rejected");
		Assert.assertEquals("broadcast safety must be explicit and positive", List.of(), violations);
		R4Heuristic2Probe.unchanged(unsafeBefore, unsafe.snapshot());
		R4Heuristic2Probe.unchanged(safeBefore, safe.snapshot());
	}

	@Test
	public void twoMarkersUseUnionOfExactDescendantsDeterministically() throws Exception {
		var fixture = R4HeuristicMetadataFixtureBridge.twoMarkerFixture();
		var markers = R4HeuristicMetadataFixtureBridge.twoMarkers(fixture);
		var before = R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis());
		var selection = R4Heuristic2AdapterBridge.select(fixture.analysis(), markers);
		var closure = R4HeuristicMetadataFixtureBridge.closure(fixture.analysis().graph(),
			R4HeuristicMetadataFixtureBridge.twoMarkers(fixture));
		Assert.assertTrue("multi-marker exclusions must stay inside the exact union closure",
			R4HeuristicMetadataFixtureBridge.exclusionWithin(selection, closure));
		Assert.assertEquals("multi-marker selection must be deterministic", selection,
			R4Heuristic2AdapterBridge.select(fixture.analysis(),
				R4HeuristicMetadataFixtureBridge.twoMarkers(fixture)));
		R4Heuristic2Probe.unchanged(before, R4Heuristic2Probe.snapshot(fixture.program(), fixture.analysis()));
	}

	@Test
	public void emptyProvableRefedSetIsExactWithoutInventedExclusion() throws Exception {
		var scenario = R4HeuristicMetadataFixtureBridge.noProvableCandidate();
		var before = scenario.snapshot();
		var selection = R4Heuristic2AdapterBridge.select(scenario.analysis(), scenario.markers());
		Assert.assertEquals("safe policy view may have no synthesized refederation candidate",
			List.of(), selection.exclusions());
		Assert.assertFalse("safe empty policy view cannot enable fallback", selection.certificate().fallback());
		Assert.assertEquals("safe empty policy view remains exhaustive", "EXHAUSTED",
			selection.certificate().termination());
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
