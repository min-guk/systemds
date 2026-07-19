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

import java.util.Map;

import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.AnchorProvenanceObserver.AnchorAccessForm;
import org.junit.Assert;
import org.junit.Test;

/** A2/A1 contract: upstream metadata snapshots are copy-on-read and not placement-owned facts. */
public class AnchorProvenanceReadOnlySnapshotContractTest {
	@Test
	public void fedInitSnapshotsAreCopiesAndDoNotExposeLiveState() {
		FederatedPlannerUtils.resetFederatedPlannerRunState();
		FederatedPlannerUtils.registerFedInitVar("G014_X", org.apache.sysds.hops.fedplanner.FTypes.FType.ROW,
			"sig:G014_X");
		FederatedPlannerUtils.registerFedAnchorKey("G014_X", "anchor:G014_X");

		Map<String, String> anchorKeys = FederatedPlannerUtils.snapshotFedAnchorKeys();
		Map<String, String> signatures = FederatedPlannerUtils.snapshotFedInitSignatures();
		Map<String, org.apache.sysds.hops.fedplanner.FTypes.FType> fTypes = FederatedPlannerUtils.snapshotFedInitTypes();
		Map<String, ?> varState = FederatedPlannerUtils.snapshotFedState();

		Assert.assertEquals("anchor:G014_X", anchorKeys.get("G014_X"));
		Assert.assertEquals("sig:G014_X", signatures.get("G014_X"));
		Assert.assertEquals(org.apache.sysds.hops.fedplanner.FTypes.FType.ROW, fTypes.get("G014_X"));
		Assert.assertTrue("G014_SNAPSHOT_VAR_STATE_CONTAINS_VAR", varState.containsKey("G014_X"));
		assertUnmodifiable(anchorKeys);
		assertUnmodifiable(signatures);
		FederatedPlannerUtils.removeFedInitVar("G014_X");
		Assert.assertEquals("G014_SNAPSHOT_MUST_BE_STABLE_AFTER_LIVE_CLEAR", "anchor:G014_X", anchorKeys.get("G014_X"));
	}

	@Test
	public void cpfoutSnapshotCannotMasqueradeAsPlacementOwnedFact() {
		Map<?, ?> cpfout = FederatedRefedPolicy.snapshotCpfoutAnchorCache();
		assertUnmodifiable(cpfout);
		PlacementAnalysis analysis = FullSpaceTestFixtures.analysis();
		var occurrence = FullSpaceTestFixtures.anchoredOccurrence(analysis);
		var fact = FullSpaceTestFixtures.validFact(analysis, occurrence, AnchorAccessForm.CPFOUT_ANCHOR_CACHE)
			.fromMutableCpfoutCacheOnly();
		Assert.assertFalse("G014_CPFOUT_SNAPSHOT_NOT_PLACEMENT_OWNED",
			AnchorProvenanceObserverFactory.observer().acceptsFullSpaceFact(fact));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertUnmodifiable(Map<?, ?> map) {
		try {
			((Map) map).put("G014_MUTATION", "forbidden");
			Assert.fail("G014_SNAPSHOT_SHOULD_BE_UNMODIFIABLE");
		}
		catch(UnsupportedOperationException expected) {
			// expected
		}
	}
}
