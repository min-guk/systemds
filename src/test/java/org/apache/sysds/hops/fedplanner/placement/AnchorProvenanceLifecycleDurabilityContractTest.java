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

import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceLifecycleCapture.LifecycleDurabilityReceipt;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.junit.Assert;
import org.junit.Test;

/** A2 contract: lifecycle durability evidence is test-only and immutable. */
public class AnchorProvenanceLifecycleDurabilityContractTest {
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
}
