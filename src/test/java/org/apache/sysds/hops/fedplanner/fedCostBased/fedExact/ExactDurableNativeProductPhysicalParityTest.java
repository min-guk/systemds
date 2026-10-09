/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NativeContinuitySupportFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** End-to-end parity for the relation-native durable publication introduced by direct Closure. */
public class ExactDurableNativeProductPhysicalParityTest {
	@Test(timeout = 30000)
	public void durableNativeProductMatchesExplicitPhysicalLocalAndExactReceipts() throws Exception {
		Object base = nativeFixture();
		Object lazy = durableFixture(base, true);
		Object explicit = durableFixture(base, false);
		CandidateEmissionRealization lazyRealization = realization(lazy);
		CandidateEmissionRealization explicitRealization = realization(explicit);

		Assert.assertEquals(PlacementLayoutKind.DURABLE_MAP, lazyRealization.key().layoutKind());
		Assert.assertTrue(lazyRealization.nativeContinuitySupportProduct().isPresent());
		Assert.assertEquals("durable publication starts without exact member handles", 0,
			NativeContinuitySupportFixtureBridge.materialized(lazyRealization));
		Assert.assertFalse(explicitRealization.nativeContinuitySupportProduct().isPresent());
		Assert.assertEquals(6, explicitRealization.supportClauses().size());

		Object lazyRows = solveAll(lazy);
		Object explicitRows = solveAll(explicit);
		Assert.assertEquals("every forced exact member keeps raw cost and receipt authority",
			explicitRows, lazyRows);
		assertContainsEqualCostReceiptTie(lazyRows);

		SelectionPair lazySelections = selections(lazy);
		SelectionPair explicitSelections = selections(explicit);
		Assert.assertEquals("Local and Exact choose the same durable receipt under equal-cost ties",
			lazySelections.local(), lazySelections.exact());
		Assert.assertEquals("explicit and relation-native Local selections are identical",
			explicitSelections.local(), lazySelections.local());
		Assert.assertEquals("explicit and relation-native Exact selections are identical",
			explicitSelections.exact(), lazySelections.exact());
		Assert.assertEquals("Physical fallback expands every exact durable member", 6,
			NativeContinuitySupportFixtureBridge.materialized(lazyRealization));
	}

	private static SelectionPair selections(Object fixture) throws Exception {
		PlacementAnalysis analysis = (PlacementAnalysis)component(fixture, "analysis");
		CompiledHopKey consumer = (CompiledHopKey)component(fixture, "consumer");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		ExactPhysicalOptimizer.Result local =
			LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		ExactPhysicalOptimizer.Result exact = ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(local.canonicalObjectiveBits(), exact.canonicalObjectiveBits());
		return new SelectionPair(summary(model, local, consumer), summary(model, exact, consumer));
	}

	private static SelectionSummary summary(ExactPhysicalModel model,
		ExactPhysicalOptimizer.Result result, CompiledHopKey consumer) {
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(model, result);
		Assert.assertNotNull(selection);
		CandidateSelectionReceipt receipt = selection.candidateReceipts().stream()
			.filter(candidate -> candidate.rule().parentOccurrence() == consumer)
			.findFirst().orElseThrow();
		return new SelectionSummary(selection.objectiveBits(), receipt.normalizedSignature(),
			receipt.supportClause().proofDependencies().stream()
				.map(proof -> proof.normalizedSignature()).toList(),
			receipt.supportClause().inputBindings().stream()
				.map(binding -> binding.source().normalizedSignature()).toList(),
			selection.sharedSupplyLifetimes().stream().sorted().toList());
	}

	private static Object nativeFixture() throws Exception {
		Class<?> suite = ExactFactorizedSupportPipelineTest.class;
		Class<?> encoding = Arrays.stream(suite.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("SupportEncoding")).findFirst().orElseThrow();
		@SuppressWarnings({"rawtypes", "unchecked"})
		Object nativeEncoding = Enum.valueOf((Class<? extends Enum>)encoding, "NATIVE");
		Method pairs = suite.getDeclaredMethod("rectangularPairs", int.class, int.class);
		pairs.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<Object> admitted = (List<Object>)pairs.invoke(null, 2, 3);
		Method fixture = suite.getDeclaredMethod(
			"fixture", int.class, int.class, encoding, boolean.class, List.class);
		fixture.setAccessible(true);
		return fixture.invoke(null, 2, 3, nativeEncoding, false, admitted);
	}

	private static Object durableFixture(Object base, boolean lazy) throws Exception {
		PlacementAnalysis source = (PlacementAnalysis)component(base, "analysis");
		CompiledHopKey consumer = (CompiledHopKey)component(base, "consumer");
		CandidateEmissionRealization original = realization(base);
		DurableAnchorKey anchor = original.nativeContinuitySupportProduct().orElseThrow()
			.nativeWorkerPoolWitness();
		PlacementRealizationKey key = PlacementRealizationKey.durable(
			original.key().emissionState(), anchor);
		List<CandidateRealizationSupportClause> relation = freshDurableRelation(original, consumer);
		CandidateEmissionRealization replacement = new CandidateEmissionRealization(
			key, lazy ? relation : List.copyOf(relation));
		List<CandidateRuleFact> facts = new ArrayList<>();
		for(CandidateRuleFact fact : source.candidateRuleFacts().orderedFacts()) {
			boolean changed = false;
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = emission.realizations().stream()
					.map(candidate -> candidate == original ? replacement : candidate).toList();
				if(!realizations.equals(emission.realizations())) {
					emissions.add(new CandidateEmissionFact(emission.emissionState(),
						emission.executionFType(), emission.derivedFoutAction(), realizations));
					changed = true;
				}
				else
					emissions.add(emission);
			}
			facts.add(changed ? new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()) : fact);
		}
		Field owner = PlacementAnalysis.class.getDeclaredField("programOwner");
		owner.setAccessible(true);
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.withCandidateFacts(
			source, (DMLProgram)owner.get(source), facts);
		return replaceFixtureComponents(base, analysis, replacement);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationSupportClause> freshDurableRelation(
		CandidateEmissionRealization original, CompiledHopKey consumer) throws Exception {
		Object originalClauses = original.supportClauses();
		Method product = originalClauses.getClass().getDeclaredMethod("product");
		product.setAccessible(true);
		Object nativeProduct = product.invoke(originalClauses);
		Constructor<?> constructor = originalClauses.getClass().getDeclaredConstructor(
			CompiledHopKey.class, nativeProduct.getClass(), DurableAnchorKey.class, boolean.class);
		constructor.setAccessible(true);
		return (List<CandidateRealizationSupportClause>)constructor.newInstance(
			consumer, nativeProduct, null, true);
	}

	private static Object replaceFixtureComponents(Object fixture, PlacementAnalysis analysis,
		CandidateEmissionRealization realization) throws Exception {
		RecordComponent[] components = fixture.getClass().getRecordComponents();
		Object[] values = new Object[components.length];
		Class<?>[] types = new Class<?>[components.length];
		for(int index = 0; index < components.length; index++) {
			Method accessor = components[index].getAccessor();
			accessor.setAccessible(true);
			values[index] = accessor.invoke(fixture);
			types[index] = components[index].getType();
			if(components[index].getName().equals("analysis"))
				values[index] = analysis;
			else if(components[index].getName().equals("supportRealization"))
				values[index] = realization;
		}
		Constructor<?> constructor = fixture.getClass().getDeclaredConstructor(types);
		constructor.setAccessible(true);
		return constructor.newInstance(values);
	}

	private static CandidateEmissionRealization realization(Object fixture) throws Exception {
		return (CandidateEmissionRealization)component(fixture, "supportRealization");
	}

	private static Object component(Object record, String name) throws Exception {
		Method accessor = record.getClass().getDeclaredMethod(name);
		accessor.setAccessible(true);
		return accessor.invoke(record);
	}

	private static Object solveAll(Object fixture) throws Exception {
		Method solve = ExactFactorizedSupportPipelineTest.class.getDeclaredMethod(
			"solveAll", fixture.getClass());
		solve.setAccessible(true);
		return solve.invoke(null, fixture);
	}

	private static void assertContainsEqualCostReceiptTie(Object relationResult) throws Exception {
		@SuppressWarnings("unchecked")
		List<Object> rows = (List<Object>)component(relationResult, "rows");
		boolean tiedDistinctReceipts = false;
		for(int left = 0; left < rows.size(); left++)
			for(int right = left + 1; right < rows.size(); right++)
				if(component(rows.get(left), "objectiveBits").equals(
					component(rows.get(right), "objectiveBits"))
					&& !component(rows.get(left), "supportSignature").equals(
						component(rows.get(right), "supportSignature")))
					tiedDistinctReceipts = true;
		Assert.assertTrue("fixture must contain equal-cost alternatives with distinct exact receipts",
			tiedDistinctReceipts);
	}

	private record SelectionPair(SelectionSummary local, SelectionSummary exact) { }
	private record SelectionSummary(long objectiveBits, String receiptSignature,
		List<String> proofSignatures, List<String> selectedSources,
		List<String> sharedSupplyLifetimes) { }
}
