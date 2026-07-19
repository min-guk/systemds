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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceLifecycleCapture.LifecycleDurabilityReceipt;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.junit.Assert;
import org.junit.Test;

/** A2 contract: lifecycle durability evidence is test-only and immutable. */
public class AnchorProvenanceLifecycleDurabilityContractTest {
	@Test
	public void lifecycleReceiptUsesDistinctRealFixtureBackedBoundaries() {
		LifecycleDurabilityReceipt receipt = AnchorProvenanceLifecycleCapture.captureStableLifecycle(
			FullSpaceTestFixtures.analysis(), List.of(AnchorAccessForm.values()));

		Assert.assertEquals("G014_A2_REAL_LIFECYCLE_STAGE_COUNT", 5, receipt.boundaries().size());
		Assert.assertEquals("G014_A2_REAL_LIFECYCLE_DISTINCT_STAGE_NAMES",
			Set.of("cleanup", "clone", "unroll", "additional-roots", "recompile"),
			new HashSet<>(receipt.boundaries().stream().map(
				AnchorProvenanceLifecycleCapture.BoundaryComparison::stage).toList()));
		Assert.assertEquals("G014_A2_REAL_LIFECYCLE_FIXTURES",
			Set.of("B-05", "B-09", "B-10", "B-11"),
			new HashSet<>(receipt.boundaries().stream().map(
				AnchorProvenanceLifecycleCapture.BoundaryComparison::fixtureId).toList()));
		for(AnchorProvenanceLifecycleCapture.BoundaryComparison boundary : receipt.boundaries()) {
			Assert.assertTrue("G014_A2_REAL_LIFECYCLE_EVIDENCE|" + boundary.stage() + boundary.evidence(),
				boundary.realLifecycleEvidence());
			Assert.assertNotSame("G014_A2_REAL_LIFECYCLE_NO_REUSED_BEFORE_AFTER|" + boundary.stage(),
				boundary.before(), boundary.after());
		}
		Assert.assertNotSame("G014_A2_REAL_LIFECYCLE_NO_REUSED_CLEANUP_CLONE",
			receipt.afterCleanup(), receipt.afterClone());
		Assert.assertNotSame("G014_A2_REAL_LIFECYCLE_NO_REUSED_CLONE_RECOMPILE",
			receipt.afterClone(), receipt.afterRecompile());
		Assert.assertTrue("G014_A2_CLEANUP_BOUNDARY_EXECUTED", receipt.cleanupBoundaryExecuted());
		Assert.assertTrue("G014_A2_CLONE_UNROLL_BOUNDARY_EXECUTED", receipt.cloneUnrollBoundaryExecuted());
		Assert.assertTrue("G014_A2_ADDITIONAL_ROOTS_BOUNDARY_EXECUTED",
			receipt.additionalRootsBoundaryExecuted());
		Assert.assertTrue("G014_A2_REGISTRY_SNAPSHOT_CLEAR_RECONSTRUCT_EXECUTED",
			receipt.registrySnapshotClearReconstructExecuted());
		Assert.assertTrue("G014_A2_RECOMPILE_BOUNDARY_EXECUTED", receipt.recompileBoundaryExecuted());
		Assert.assertEquals("G014_A2_TWO_RUN_NORMALIZED_DIGEST", receipt.normalizedDigest(),
			AnchorProvenanceLifecycleCapture.captureStableLifecycle(FullSpaceTestFixtures.analysis(),
				List.of(AnchorAccessForm.values())).normalizedDigest());
		assertUnmodifiable("G014_A2_BOUNDARY_EVIDENCE_IMMUTABLE", receipt.afterCleanup().evidence());
	}

	@Test
	public void recompileBoundaryEvidenceComesFromB09ExclusionNotHardcodedFalse() {
		LifecycleDurabilityReceipt receipt = AnchorProvenanceLifecycleCapture.captureStableLifecycle(
			FullSpaceTestFixtures.analysis(), List.of(AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE));
		AnchorProvenanceLifecycleCapture.BoundaryComparison recompile = receipt.afterRecompile();

		Assert.assertEquals("G014_A2_RECOMPILE_FIXTURE", "B-09", recompile.fixtureId());
		Assert.assertTrue("G014_A2_RECOMPILE_REAL_EVIDENCE", recompile.realLifecycleEvidence());
		Assert.assertTrue("G014_A2_RECOMPILE_EXCLUSION_EVIDENCE",
			recompile.evidence().contains("recompileCpFoutExclusions=1"));
		Assert.assertFalse("G014_A2_NO_PRODUCTION_CPFOUT_RECOMPILE_ESCAPE",
			recompile.allowedCpFoutInRecompile());
	}

	@Test
	public void cleanupCloneUnrollAdditionalRootsAndRecompilePreserveCapturedFacts() {
		PlacementAnalysis analysis = FullSpaceTestFixtures.analysis();
		String beforeFingerprint = analysis.analysisFingerprint();
		int beforeOccurrences = analysis.occurrences().size();
		int beforeNodes = analysis.graph().nodes().size();

		LifecycleDurabilityReceipt receipt = AnchorProvenanceLifecycleCapture.captureStableLifecycle(analysis,
			List.of(AnchorAccessForm.FEDINIT_LITERAL, AnchorAccessForm.FEDINIT_SIGNATURE,
				AnchorAccessForm.RUNTIME_RECOMPILE_SIGNATURE));

		Assert.assertTrue("G014_A2_CLEANUP_FACT_DURABILITY", receipt.afterCleanup().sameAnchorFacts());
		Assert.assertTrue("G014_A2_CLONE_CANONICAL_ORIGIN_DURABILITY", receipt.afterClone().sameCanonicalOrigins());
		Assert.assertTrue("G014_A2_UNROLL_OCCURRENCE_DURABILITY", receipt.afterUnroll().sameOccurrences());
		Assert.assertTrue("G014_A2_ADDITIONAL_ROOT_SCOPE_DURABILITY",
			receipt.afterAdditionalRoots().sameStatementBlockScopes());
		Assert.assertTrue("G014_A2_RECOMPILE_SIGNATURE_DURABILITY",
			receipt.afterRecompile().sameRuntimeSignatureFacts());
		Assert.assertFalse("G014_A2_NO_PRODUCTION_CPFOUT_RECOMPILE_ESCAPE",
			receipt.afterRecompile().allowedCpFoutInRecompile());
		Assert.assertEquals("G014_A2_ANALYSIS_FINGERPRINT_UNCHANGED", beforeFingerprint, analysis.analysisFingerprint());
		Assert.assertEquals("G014_A2_OCCURRENCES_UNCHANGED", beforeOccurrences, analysis.occurrences().size());
		Assert.assertEquals("G014_A2_GRAPH_NODES_UNCHANGED", beforeNodes, analysis.graph().nodes().size());
	}

	private static void assertUnmodifiable(String code, List<String> values) {
		try {
			values.add("mutation");
			Assert.fail(code);
		}
		catch(UnsupportedOperationException expected) {
			// expected immutable snapshot
		}
	}
}
