/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;

import org.junit.Assert;
import org.junit.Test;

/** Final CFG replay should reuse continuity without introducing structural context rebuilds. */
public class ContinuityRefreshReuseTest {
	@Test
	public void actionReceiptRefreshDoesNotAddStructuralContinuityRebuilds() throws Exception {
		SearchSpaceMetrics fullMetrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder fullBuilder =
			new NeutralPlacementGraphBuilder(null, fullMetrics, false);
		PlacementAnalysis expected = fullBuilder.buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NeutralPlacementGraphBuilder observedBuilder = new NeutralPlacementGraphBuilder(null, metrics);
		PlacementAnalysis observed = observedBuilder.buildAnalysis(
			NeutralPlacementFixedPointCompositionTest.compileProtected(
				NeutralPlacementFixedPointCompositionTest.ACTIONS));

		Assert.assertFalse("fixture must publish action-backed receipts",
			observed.graph().relocationActions().isEmpty());
		Assert.assertEquals(expected.analysisFingerprint(), observed.analysisFingerprint());
		Assert.assertEquals(expected.candidateRuleFacts().orderedFacts(),
			observed.candidateRuleFacts().orderedFacts());
		PlacementSupportRelations.verifyPublishedRelocationRealizations(
			observed.candidateRuleFacts().orderedFacts(), observed.graph().relocationActions());
		Object fullClosure = PlacementBuilderTestAccess.relationClosure(fullBuilder);
		Object observedClosure = PlacementBuilderTestAccess.relationClosure(observedBuilder);
		Assert.assertEquals("the fixture has three distinct structural continuity contexts", 3,
			intField(fullClosure, "nativeContextMisses"));
		Assert.assertEquals("incremental scheduling must not add structural continuity rebuilds",
			intField(fullClosure, "nativeContextMisses"),
			intField(observedClosure, "nativeContextMisses"));
		Assert.assertTrue("the final receipt revisions must reuse their continuity context",
			intField(observedClosure, "nativeContextHits") > 0);
	}

	private static int intField(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(owner);
	}
}
