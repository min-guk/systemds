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

/** Final CFG replay should consume one continuity context for its final receipt revision. */
public class ContinuityRefreshReuseTest {
	@Test
	public void actionReceiptRefreshUsesOneFinalContinuityContext() throws Exception {
		PlacementAnalysis expected = new NeutralPlacementGraphBuilder().buildAnalysis(
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
		Object closure = PlacementBuilderTestAccess.relationClosure(observedBuilder);
		int continuityContexts = intField(closure, "nativeContextHits")
			+ intField(closure, "nativeContextMisses");
		Assert.assertEquals("only final receipt revisions may rebuild continuity", 10,
			continuityContexts);
	}

	private static int intField(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(owner);
	}
}
