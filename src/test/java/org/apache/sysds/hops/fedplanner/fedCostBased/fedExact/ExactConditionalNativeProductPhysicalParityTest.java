/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Compare a conditional native carrier with an independently filtered ordinary relation. */
public class ExactConditionalNativeProductPhysicalParityTest {

	@Test(timeout = 30000)
	public void rectangularHolesPreservePhysicalCostsAndLocalExactReceipts() throws Exception {
		verify(3, 3, 1, 1);
		verify(3, 3, 2, 2);
	}

	@Test(timeout = 30000)
	public void oneRemainingMixedAxisKeepsExcludedChoicesOutOfPhysicalAlternatives() throws Exception {
		verify(3, 3, 3, 2);
		verify(3, 3, 2, 3);
	}

	@SuppressWarnings("unchecked")
	private static void verify(int left, int right, int excludedLeft, int excludedRight) throws Exception {
		Object base = fixture(left, right);
		CandidateEmissionRealization ordinary = (CandidateEmissionRealization)component(base, "supportRealization");
		Object ordinaryClauses = ordinary.supportClauses();
		Object product = invoke(ordinaryClauses, "product");
		List<List<CandidateRealizationInputBinding>> axes =
			(List<List<CandidateRealizationInputBinding>>)invoke(product, "axes");
		List<List<CandidateRealizationInputBinding>> excluded = List.of(
			axes.get(0).subList(0, excludedLeft), axes.get(1).subList(0, excludedRight));
		Method complement = product.getClass().getDeclaredMethod(
			"tryCreateExactComplement", product.getClass(), List.class);
		complement.setAccessible(true);
		Object conditionalProduct = complement.invoke(null, product, excluded);
		Assert.assertNotNull(conditionalProduct);
		CompiledHopKey consumer = (CompiledHopKey)component(base, "consumer");
		DurableAnchorKey witness = ordinary.nativeContinuitySupportProduct().orElseThrow()
			.nativeWorkerPoolWitness();
		Constructor<?> constructor = ordinaryClauses.getClass().getDeclaredConstructor(
			CompiledHopKey.class, product.getClass(), DurableAnchorKey.class, boolean.class);
		constructor.setAccessible(true);
		List<CandidateRealizationSupportClause> conditionalClauses =
			(List<CandidateRealizationSupportClause>)constructor.newInstance(
				consumer, conditionalProduct, witness, true);
		CandidateEmissionRealization lazy = new CandidateEmissionRealization(ordinary.key(), conditionalClauses);

		// The reference enumerates the ordinary product, never the conditional rank/get implementation.
		List<CandidateRealizationSupportClause> explicitClauses = ordinary.supportClauses().stream()
			.filter(clause -> !excludedTuple(clause.inputBindings(), excluded)).toList();
		Assert.assertEquals(left * right - excludedLeft * excludedRight, explicitClauses.size());
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(ordinary.key(), explicitClauses);
		List<Object> admitted = new ArrayList<>();
		for(Object pair : (List<Object>)component(base, "admittedPairs"))
			if((int)component(pair, "leftOption") >= excludedLeft
				|| (int)component(pair, "rightOption") >= excludedRight)
				admitted.add(pair);
		Object lazyFixture = replace(base, lazy, admitted);
		Object explicitFixture = replace(base, explicit, admitted);
		Assert.assertTrue(lazy.nativeContinuitySupportProduct().isPresent());
		Assert.assertEquals("analysis must not enumerate conditional members", 0,
			NativeContinuitySupportFixtureBridge.materialized(lazy));

		ExactPhysicalModel model = ExactPhysicalModel.build((PlacementAnalysis)component(lazyFixture, "analysis"));
		var domain = model.domains().stream().filter(candidate -> candidate.node().key() == consumer)
			.findFirst().orElseThrow();
		List<CandidateRealizationSupportClause> physicalClauses = domain.alternatives().stream()
			.filter(alternative -> alternative.captured() && alternative.supportClause() != null
				&& alternative.supportClause().inputBindings().size() == 2)
			.map(ExactPhysicalModel.Alternative::supportClause).toList();
		ExactPhysicalModel referenceModel = ExactPhysicalModel.build(
			(PlacementAnalysis)component(explicitFixture, "analysis"));
		List<CandidateRealizationSupportClause> referencePhysicalClauses = referenceModel.domains().stream()
			.filter(candidate -> candidate.node().key() == consumer).findFirst().orElseThrow()
			.alternatives().stream()
			.filter(alternative -> alternative.captured() && alternative.supportClause() != null
				&& alternative.supportClause().inputBindings().size() == 2)
			.map(ExactPhysicalModel.Alternative::supportClause).toList();
		Assert.assertEquals("Physical preserves the explicit alternative order including repeated supports",
			referencePhysicalClauses, physicalClauses);
		Assert.assertTrue("every admitted member remains reachable", physicalClauses.containsAll(explicitClauses));
		Assert.assertTrue("excluded rectangle cannot reappear in downstream alternatives",
			physicalClauses.stream().noneMatch(clause -> excludedTuple(clause.inputBindings(), excluded)));
		Assert.assertEquals("current Physical fallback materializes only admitted members",
			explicitClauses.size(), NativeContinuitySupportFixtureBridge.materialized(lazy));
		Assert.assertEquals("every forced legal member preserves objective bits, proof and relocation receipt",
			solveAll(explicitFixture), solveAll(lazyFixture));
		Assert.assertEquals("unconstrained Local/Exact assignment receipts and shared lifetimes agree",
			selections(explicitFixture), selections(lazyFixture));
		Assert.assertEquals("all original decision assignments and final receipts agree in each planner",
			plans(explicitFixture), plans(lazyFixture));
	}

	private static boolean excludedTuple(List<CandidateRealizationInputBinding> bindings,
		List<List<CandidateRealizationInputBinding>> excluded) {
		return bindings.size() == excluded.size() && bindings.stream().allMatch(binding ->
			excluded.get(binding.inputPosition()).stream().anyMatch(option -> option.equals(binding)
				&& option.source().rule().parentOccurrence() == binding.source().rule().parentOccurrence()));
	}

	private static Object fixture(int left, int right) throws Exception {
		Class<?> suite = ExactFactorizedSupportPipelineTest.class;
		Class<?> encoding = Arrays.stream(suite.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("SupportEncoding")).findFirst().orElseThrow();
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object nativeEncoding = Enum.valueOf((Class<? extends Enum>)encoding, "NATIVE");
		Method pairs = suite.getDeclaredMethod("rectangularPairs", int.class, int.class);
		pairs.setAccessible(true);
		Method fixture = suite.getDeclaredMethod("fixture", int.class, int.class, encoding, boolean.class, List.class);
		fixture.setAccessible(true);
		return fixture.invoke(null, left, right, nativeEncoding, false, pairs.invoke(null, left, right));
	}

	private static Object replace(Object fixture, CandidateEmissionRealization replacement,
		List<Object> admitted) throws Exception {
		PlacementAnalysis source = (PlacementAnalysis)component(fixture, "analysis");
		CandidateEmissionRealization original = (CandidateEmissionRealization)component(fixture, "supportRealization");
		List<CandidateRuleFact> facts = new ArrayList<>();
		for(CandidateRuleFact fact : source.candidateRuleFacts().orderedFacts()) {
			boolean changed = false;
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				if(emission.realizations().stream().noneMatch(candidate -> candidate == original)) {
					emissions.add(emission);
					continue;
				}
				changed = true;
				emissions.add(new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
					emission.derivedFoutAction(), emission.realizations().stream()
						.map(candidate -> candidate == original ? replacement : candidate).toList()));
			}
			facts.add(changed ? new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()) : fact);
		}
		Field owner = PlacementAnalysis.class.getDeclaredField("programOwner");
		owner.setAccessible(true);
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.withCandidateFacts(
			source, (DMLProgram)owner.get(source), facts);
		RecordComponent[] components = fixture.getClass().getRecordComponents();
		Object[] values = new Object[components.length];
		Class<?>[] types = new Class<?>[components.length];
		for(int index = 0; index < components.length; index++) {
			String name = components[index].getName();
			types[index] = components[index].getType();
			values[index] = switch(name) {
				case "analysis" -> analysis;
				case "supportRealization" -> replacement;
				case "admittedPairs" -> List.copyOf(admitted);
				default -> component(fixture, name);
			};
		}
		Constructor<?> constructor = fixture.getClass().getDeclaredConstructor(types);
		constructor.setAccessible(true);
		return constructor.newInstance(values);
	}

	private static Object component(Object value, String name) throws Exception { return invoke(value, name); }
	private static Object invoke(Object value, String name) throws Exception {
		Method method = value.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(value);
	}
	private static Object solveAll(Object fixture) throws Exception {
		Method method = ExactFactorizedSupportPipelineTest.class.getDeclaredMethod("solveAll", fixture.getClass());
		method.setAccessible(true);
		return method.invoke(null, fixture);
	}
	private static Object selections(Object fixture) throws Exception {
		Method method = ExactDurableNativeProductPhysicalParityTest.class.getDeclaredMethod("selections", Object.class);
		method.setAccessible(true);
		return method.invoke(null, fixture);
	}
	private static List<Plan> plans(Object fixture) throws Exception {
		PlacementAnalysis analysis = (PlacementAnalysis)component(fixture, "analysis");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		List<Plan> plans = new ArrayList<>();
		for(var result : List.of(LocalPhysicalOptimizer.optimize(model, surface).physicalResult(),
			ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS))) {
			var selection = ExactPhysicalSelection.create(model, result);
			plans.add(new Plan(result.canonicalObjectiveBits(), result.solverResult().assignmentInVariableOrder(),
				selection.candidateReceipts().stream().map(receipt -> receipt.normalizedSignature()).toList(),
				selection.sharedSupplyLifetimes().stream().sorted().toList()));
		}
		return List.copyOf(plans);
	}
	private record Plan(long objectiveBits, List<Integer> assignment,
		List<String> receipts, List<String> sharedLifetimes) { }
}
