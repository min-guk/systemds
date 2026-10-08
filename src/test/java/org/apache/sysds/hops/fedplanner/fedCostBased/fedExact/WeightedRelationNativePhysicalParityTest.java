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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.WeightedRelationNativeClosureParityTest;
import org.junit.Assert;
import org.junit.Test;

/** Solver and final-receipt parity against an independently generated exact-row closure. */
public class WeightedRelationNativePhysicalParityTest {
	@Test(timeout = 30000)
	public void publicWeightedRelationMatchesExplicitPhysicalSelection() throws Exception {
		assertPhysicalParity(Privacy.PUBLIC);
	}

	@Test(timeout = 30000)
	public void privateAggregateWeightedRelationMatchesExplicitPhysicalSelection() throws Exception {
		assertPhysicalParity(Privacy.PRIVATE_AGGREGATE);
	}

	@Test(timeout = 30000)
	public void publicWeightedCrossEntropyMatchesExplicitPhysicalSelection() throws Exception {
		assertPhysicalParity(Privacy.PUBLIC, true);
	}

	@Test(timeout = 30000)
	public void parsedIndependentAxesMatchExplicitPhysicalSelection() throws Exception {
		PlacementAnalysis explicit = WeightedRelationNativeClosureParityTest
			.multiAlternativeAnalysisForTest(false);
		PlacementAnalysis compact = WeightedRelationNativeClosureParityTest
			.multiAlternativeAnalysisForTest(true);
		Assert.assertTrue(compact.candidateRuleFacts().candidateRelations().stream()
			.map(relation -> relation.logicalSize())
			.anyMatch(size -> size.compareTo(java.math.BigInteger.ONE) > 0));
		ExactPhysicalSelection explicitLocal = localSelection(explicit);
		ExactPhysicalSelection compactLocal = localSelection(compact);
		ExactPhysicalSelection explicitExact = exactSelection(explicit);
		ExactPhysicalSelection compactExact = exactSelection(compact);
		Assert.assertEquals(explicitLocal.objectiveBits(), compactLocal.objectiveBits());
		Assert.assertEquals(stateSignatures(explicitLocal), stateSignatures(compactLocal));
		Assert.assertEquals(explicitExact.objectiveBits(), compactExact.objectiveBits());
		Assert.assertEquals(stateSignatures(explicitExact), stateSignatures(compactExact));
		String diagnostic = "Local explicit=" + weightedReceiptSignatures(explicit, explicitLocal)
			+ " compact=" + weightedReceiptSignatures(compact, compactLocal)
			+ "; Exact explicit=" + weightedReceiptSignatures(explicit, explicitExact)
			+ " compact=" + weightedReceiptSignatures(compact, compactExact);
		Assert.assertEquals(diagnostic, receiptSignatures(explicitLocal),
			receiptSignatures(compactLocal));
		Assert.assertEquals(diagnostic, receiptSignatures(explicitExact),
			receiptSignatures(compactExact));
	}

	private static void assertPhysicalParity(Privacy privacy) throws Exception {
		assertPhysicalParity(privacy, false);
	}

	private static void assertPhysicalParity(Privacy privacy, boolean crossEntropy) throws Exception {
		PlacementAnalysis explicit = WeightedRelationNativeClosureParityTest.analysisForTest(
			privacy, false, crossEntropy);
		PlacementAnalysis compact = WeightedRelationNativeClosureParityTest.analysisForTest(
			privacy, true, crossEntropy);
		assertPhysicalParity(explicit, compact);
	}

	private static void assertPhysicalParity(PlacementAnalysis explicit,
		PlacementAnalysis compact) {
		ExactPhysicalSelection explicitLocal = localSelection(explicit);
		ExactPhysicalSelection compactLocal = localSelection(compact);
		Assert.assertEquals(explicitLocal.objectiveBits(), compactLocal.objectiveBits());
		Assert.assertEquals(stateSignatures(explicitLocal), stateSignatures(compactLocal));
		Assert.assertEquals("weighted Local receipt differs",
			weightedReceiptSignatures(explicit, explicitLocal),
			weightedReceiptSignatures(compact, compactLocal));
		Assert.assertEquals(receiptSignatures(explicitLocal), receiptSignatures(compactLocal));

		ExactPhysicalSelection explicitExact = exactSelection(explicit);
		ExactPhysicalSelection compactExact = exactSelection(compact);
		Assert.assertEquals(explicitExact.objectiveBits(), compactExact.objectiveBits());
		Assert.assertEquals(stateSignatures(explicitExact), stateSignatures(compactExact));
		Assert.assertEquals("weighted Exact receipt differs",
			weightedReceiptSignatures(explicit, explicitExact),
			weightedReceiptSignatures(compact, compactExact));
		Assert.assertEquals(receiptSignatures(explicitExact), receiptSignatures(compactExact));
	}

	private static ExactPhysicalSelection localSelection(PlacementAnalysis analysis) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		return ExactPhysicalSelection.create(model,
			LocalPhysicalOptimizer.optimize(model, surface).physicalResult());
	}

	private static ExactPhysicalSelection exactSelection(PlacementAnalysis analysis) {
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		return ExactPhysicalSelection.create(model, ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS));
	}

	private static List<String> stateSignatures(ExactPhysicalSelection selection) {
		return selection.selectedStates().entrySet().stream()
			.map(entry -> entry.getKey().normalizedSignature() + '='
				+ entry.getValue().normalizedSignature()).sorted().toList();
	}

	private static List<String> receiptSignatures(ExactPhysicalSelection selection) {
		return selection.candidateReceipts().stream()
			.map(receipt -> receipt.normalizedSignature()).sorted().toList();
	}

	private static List<String> weightedReceiptSignatures(PlacementAnalysis analysis,
		ExactPhysicalSelection selection) {
		return selection.candidateReceipts().stream()
			.filter(receipt -> analysis.hop(receipt.rule().parentOccurrence()).orElse(null)
				instanceof org.apache.sysds.hops.QuaternaryOp)
			.map(receipt -> receipt.normalizedSignature()).sorted().toList();
	}
}
