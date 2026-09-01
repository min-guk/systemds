/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.EnumSet;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorMetadataDisposition;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.FullSpaceObservationReceipt;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.FullSpaceObservationRequest;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationResult;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.ObservationState;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.PlacementOwnedAnchorFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.junit.Assert;
import org.junit.Test;

/** GREEN A1 contract: typed placement-owned full-space facts without lifecycle claims. */
public class AnchorProvenanceFullSpacePlacementGreenContractTest {
	@Test
	public void legalAccessFormsStayExplicitAndDpComplete() {
		Assert.assertEquals("G014_A1_ACCESS_FORMS_CHANGED", EnumSet.of(
			AnchorAccessForm.FEDINIT_LITERAL,
			AnchorAccessForm.FEDINIT_SIGNATURE,
			AnchorAccessForm.GLOBAL_SIGNATURE_ANCHOR_KEY,
			AnchorAccessForm.VAR_ANCHOR_KEY,
			AnchorAccessForm.CPFOUT_ANCHOR_CACHE,
			AnchorAccessForm.REFED_REGISTRY_RECORD,
			AnchorAccessForm.FOUT_MATERIALIZE_RECORD,
			AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE),
			AnchorAccessForm.legalDpUsedForms());
	}

	@Test
	public void precheckHasSemanticDispositionBeforeAnyRejectionText() {
		FullSpaceObservationReceipt receipt = AnchorProvenanceObserverFactory.observer()
			.observeFullSpace(FullSpaceObservationRequest.forCandidatePrecheck(
				AnchorAccessForm.CPFOUT_ANCHOR_CACHE, "sb0", 7L, "anchor-x", null));

		Assert.assertTrue("G014_A1_TYPED_DISPOSITION_MISSING",
			List.of(AnchorMetadataDisposition.ANCHOR_METADATA_INCOMPLETE,
				AnchorMetadataDisposition.UNSUPPORTED_ANCHOR_METADATA,
				AnchorMetadataDisposition.AVAILABLE).contains(receipt.disposition()));
		Assert.assertTrue("G014_A1_DISPOSITION_MUST_PRECEDE_REJECTION",
			receipt.dispositionSequence() < receipt.candidateRejectionSequence());
		Assert.assertFalse("G014_A1_NO_HEURISTIC_REJECTION_WORDS", receipt.rejectionReason().contains("workload")
			|| receipt.rejectionReason().contains("shape") || receipt.rejectionReason().contains("worker"));
	}

	@Test
	public void exactAnalysisFactIsPlacementOwnedImmutableAndLiteralBridgeUnchanged() {
		PlacementAnalysis analysis = FullSpaceTestFixtures.analysis();
		CompiledHopKey occurrence = FullSpaceTestFixtures.anchoredOccurrence(analysis);
		String beforeFingerprint = analysis.analysisFingerprint();
		int beforeOccurrences = analysis.occurrences().size();
		int beforeNodes = analysis.graph().nodes().size();

		FullSpaceObservationReceipt receipt = AnchorProvenanceObserverFactory.observer()
			.observeFullSpace(FullSpaceObservationRequest.forExactAnalysis(
				analysis, occurrence, AnchorAccessForm.FEDINIT_SIGNATURE, "sig:literal"));
		PlacementOwnedAnchorFact fact = receipt.fact().orElseThrow(AssertionError::new);

		Assert.assertEquals(AnchorMetadataDisposition.AVAILABLE, receipt.disposition());
		Assert.assertSame("G014_A1_FACT_ANALYSIS_IDENTITY", analysis, fact.analysis());
		Assert.assertSame("G014_A1_FACT_OCCURRENCE_IDENTITY", occurrence, fact.occurrence());
		Assert.assertEquals(AnchorAccessForm.FEDINIT_SIGNATURE, fact.accessForm());
		Assert.assertEquals(fact.normalizedAnchorIdentity().fType(), fact.fType());
		Assert.assertEquals(fact.normalizedAnchorIdentity().partitions(), fact.partitions());
		Assert.assertFalse("G014_A1_NO_FABRICATED_PARTITIONS", fact.fabricatedPartitions());
		Assert.assertFalse("G014_A1_NO_RUNTIME_FALLBACK", fact.runtimeFallbackUsed());
		Assert.assertTrue("G014_A1_SOURCE_SIGNATURE_RETAINED", fact.sourceSignature().isPresent());

		ObservationResult literal = AnchorProvenanceObserverFactory.observer()
			.observe(analysis, AnchorProvenanceObserverFactoryContractTestExactSource.source(analysis));
		Assert.assertEquals("G014_A1_LITERAL_BRIDGE_STILL_AVAILABLE", ObservationState.AVAILABLE, literal.state());
		Assert.assertEquals("G014_A1_ANALYSIS_FINGERPRINT_UNCHANGED", beforeFingerprint, analysis.analysisFingerprint());
		Assert.assertEquals("G014_A1_OCCURRENCES_UNCHANGED", beforeOccurrences, analysis.occurrences().size());
		Assert.assertEquals("G014_A1_GRAPH_NODES_UNCHANGED", beforeNodes, analysis.graph().nodes().size());
	}

	@Test
	public void copiedForeignKeyOnlyAndMutableCacheFactsAreRejectedWithoutRepair() {
		PlacementAnalysis analysis = FullSpaceTestFixtures.analysis();
		CompiledHopKey occurrence = FullSpaceTestFixtures.anchoredOccurrence(analysis);
		PlacementOwnedAnchorFact fact = FullSpaceTestFixtures.validFact(analysis, occurrence,
			AnchorAccessForm.FEDINIT_SIGNATURE);

		Assert.assertFalse("G014_A1_REJECT_COPIED_OCCURRENCE",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(
				fact.withOccurrence(FullSpaceTestFixtures.copiedOccurrence(occurrence))));
		Assert.assertFalse("G014_A1_REJECT_FOREIGN_ANALYSIS",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(
				fact.withAnalysis(FullSpaceTestFixtures.foreignAnalysis())));
		Assert.assertFalse("G014_A1_REJECT_KEY_ONLY_METADATA",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(
				fact.withoutWorkerRangeMetadata()));
		Assert.assertFalse("G014_A1_REJECT_MUTABLE_CACHE_ONLY",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(
				fact.fromMutableCpfoutCacheOnly()));
	}
}
