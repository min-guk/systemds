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
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.LifecycleDurabilityReceipt;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.PlacementOwnedAnchorFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

/**
 * RED contract for G014 full-space anchor provenance.
 *
 * <p>The accepted G013 observer is intentionally literal-only. These tests are
 * compile-time RED until the shared placement-owned observer exposes every legal
 * DP-used anchor access form as typed metadata. The contract is src/test-only:
 * it must not be satisfied by planner heuristics, workload labels, fabricated
 * ranges, runtime fallback, or candidate-space shrinkage.</p>
 */
public class AnchorProvenanceFullSpaceRedContractTest {
	@Test
	public void everyLegalDpUsedAnchorAccessFormIsAFirstClassTypedForm() {
		Assert.assertEquals("G014_FULLSPACE_ACCESS_FORM_SET_CHANGED", EnumSet.of(
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
	public void metadataIncompleteAndUnsupportedAreTypedBeforeCandidateRejection() {
		FullSpaceObservationRequest request = FullSpaceObservationRequest.forCandidatePrecheck(
			AnchorAccessForm.CPFOUT_ANCHOR_CACHE, "sb0", 42L, "X", null);
		FullSpaceObservationReceipt receipt = AnchorProvenanceObserverFactory.observer()
			.observeFullSpace(request);

		Assert.assertTrue("G014_FULLSPACE_TYPED_DISPOSITION_REQUIRED",
			List.of(AnchorMetadataDisposition.ANCHOR_METADATA_INCOMPLETE,
				AnchorMetadataDisposition.UNSUPPORTED_ANCHOR_METADATA,
				AnchorMetadataDisposition.AVAILABLE).contains(receipt.disposition()));
		Assert.assertTrue("G014_FULLSPACE_DISPOSITION_BEFORE_REJECTION_REQUIRED",
			receipt.dispositionSequence() < receipt.candidateRejectionSequence());
		Assert.assertFalse("G014_FULLSPACE_NO_HEURISTIC_REJECTION",
			receipt.rejectionReason().contains("workload")
				|| receipt.rejectionReason().contains("worker")
				|| receipt.rejectionReason().contains("hop-id-only")
				|| receipt.rejectionReason().contains("shape"));
	}

	@Test
	public void availableFactsCarryPlacementOwnedWorkersRangesFTypeOccurrenceScopeAndSourceForm() {
		FullSpaceObservationReceipt receipt = AnchorProvenanceObserverFactory.observer()
			.observeFullSpace(FullSpaceObservationRequest.forExactAnalysis(
				analysis(), occurrence(), AnchorAccessForm.FEDINIT_SIGNATURE, "sig:literal"));
		PlacementOwnedAnchorFact fact = receipt.fact().orElseThrow(AssertionError::new);

		Assert.assertEquals(AnchorMetadataDisposition.AVAILABLE, receipt.disposition());
		Assert.assertSame("G014_FULLSPACE_ANALYSIS_IDENTITY", analysis(), fact.analysis());
		Assert.assertSame("G014_FULLSPACE_OCCURRENCE_IDENTITY", occurrence(), fact.occurrence());
		Assert.assertEquals(AnchorAccessForm.FEDINIT_SIGNATURE, fact.accessForm());
		Assert.assertNotNull("G014_FULLSPACE_NON_NULL_FTYPE", fact.fType());
		Assert.assertFalse("G014_FULLSPACE_LITERAL_PARTITIONS_REQUIRED",
			fact.normalizedAnchorIdentity().partitions().isEmpty());
		Assert.assertEquals("G014_FULLSPACE_PARTITION_IDENTITY_MATCH",
			fact.normalizedAnchorIdentity().partitions(), fact.partitions());
		Assert.assertFalse("G014_FULLSPACE_NO_FABRICATED_PARTITIONS",
			fact.fabricatedPartitions());
		Assert.assertFalse("G014_FULLSPACE_NO_RUNTIME_FALLBACK", fact.runtimeFallbackUsed());
	}

	@Test
	public void provenanceSurvivesCleanupCloneUnrollAdditionalRootsAndRecompileByTypedReceipt() {
		LifecycleDurabilityReceipt lifecycle = AnchorProvenanceObserverFactory.observer()
			.observeFullSpace(FullSpaceObservationRequest.forLifecycleMatrix(
				analysis(), List.of(AnchorAccessForm.values())))
			.lifecycle().orElseThrow(AssertionError::new);

		Assert.assertTrue("G014_FULLSPACE_CLEANUP_DURABILITY", lifecycle.afterCleanup().sameAnchorFacts());
		Assert.assertTrue("G014_FULLSPACE_CLONE_DURABILITY", lifecycle.afterClone().sameCanonicalOrigins());
		Assert.assertTrue("G014_FULLSPACE_UNROLL_DURABILITY", lifecycle.afterUnroll().sameOccurrences());
		Assert.assertTrue("G014_FULLSPACE_ADDITIONAL_ROOT_DURABILITY",
			lifecycle.afterAdditionalRoots().sameStatementBlockScopes());
		Assert.assertTrue("G014_FULLSPACE_RECOMPILE_DURABILITY",
			lifecycle.afterRecompile().sameRuntimeSignatureFacts());
		Assert.assertFalse("G014_FULLSPACE_NO_CPFOUT_RECOMPILE_ESCAPE",
			lifecycle.afterRecompile().allowedCpFoutInRecompile());
	}

	@Test
	public void copiedForeignOrKeyOnlyMetadataCannotMasqueradeAsPlacementOwnedProvenance() {
		PlacementOwnedAnchorFact fact = validFact();
		Assert.assertFalse("G014_FULLSPACE_REJECT_COPIED_OCCURRENCE",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(fact.withOccurrence(copiedOccurrence())));
		Assert.assertFalse("G014_FULLSPACE_REJECT_FOREIGN_ANALYSIS",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(fact.withAnalysis(foreignAnalysis())));
		Assert.assertFalse("G014_FULLSPACE_REJECT_KEY_ONLY_WITHOUT_METADATA",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(fact.withoutWorkerRangeMetadata()));
		Assert.assertFalse("G014_FULLSPACE_REJECT_MUTABLE_CACHE_ONLY",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(fact.fromMutableCpfoutCacheOnly()));
	}

	private static PlacementAnalysis analysis() {
		throw new AssertionError("G014_FULLSPACE_FIXTURE_PENDING");
	}

	private static PlacementAnalysis foreignAnalysis() {
		throw new AssertionError("G014_FULLSPACE_FOREIGN_FIXTURE_PENDING");
	}

	private static CompiledHopKey occurrence() {
		throw new AssertionError("G014_FULLSPACE_OCCURRENCE_FIXTURE_PENDING");
	}

	private static CompiledHopKey copiedOccurrence() {
		throw new AssertionError("G014_FULLSPACE_COPIED_OCCURRENCE_FIXTURE_PENDING");
	}

	private static PlacementOwnedAnchorFact validFact() {
		throw new AssertionError("G014_FULLSPACE_VALID_FACT_FIXTURE_PENDING");
	}

	@SuppressWarnings("unused")
	private static DurableAnchorKey anchor(PlacementOwnedAnchorFact fact) {
		return fact.normalizedAnchorIdentity();
	}
}
