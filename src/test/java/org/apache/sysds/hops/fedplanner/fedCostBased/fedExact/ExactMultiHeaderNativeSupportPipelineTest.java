/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NativeContinuitySupportFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Physical/Cost/DP parity for a common-axis relation with multiple exact proof headers. */
public class ExactMultiHeaderNativeSupportPipelineTest {
	@Test(timeout = 30000)
	public void multiHeaderRelationUsesExactPhysicalFallbackAndRestoresCanonicalReceipt() throws Exception {
		Object compactFixture = multiHeaderFixture(nativeFixture(), true);
		Object explicitFixture = multiHeaderFixture(nativeFixture(), false);
		CandidateEmissionRealization compactRealization = realization(compactFixture);
		CandidateEmissionRealization explicitRealization = realization(explicitFixture);

		var nativeView = compactRealization.nativeContinuitySupportProduct().orElseThrow();
		Assert.assertEquals(2, nativeView.headerCount());
		Assert.assertEquals(4, nativeView.logicalClauseCount());
		Assert.assertTrue("fixture publication and ordering must not expand the relation",
			NativeContinuitySupportFixtureBridge.materialized(compactRealization) <= 1);
		Assert.assertFalse(explicitRealization.nativeContinuitySupportProduct().isPresent());
		Assert.assertEquals(4, explicitRealization.supportClauses().size());
		PlacementAnalysis compactAnalysis = (PlacementAnalysis)component(compactFixture, "analysis");
		Assert.assertEquals("native member ranks require the exact Physical fallback", 0,
			compactRealization.compactSupportRegions(binding ->
				ExactPhysicalModel.deliveredSupportLayoutForTest(compactAnalysis, binding)).size());

		ExactPhysicalModel compactModel = ExactPhysicalModel.build(
			(PlacementAnalysis)component(compactFixture, "analysis"));
		CompiledHopKey consumer = (CompiledHopKey)component(compactFixture, "consumer");
		var consumerDomain = compactModel.domains().stream()
			.filter(domain -> domain.node().key() == consumer).findFirst().orElseThrow();
		var exactMembers = consumerDomain.alternatives().stream()
			.filter(alternative -> alternative.realization() != null
				&& "multi-header-unary".equals(alternative.realization().key().nativeLineage())).toList();
		Assert.assertEquals(4, exactMembers.size());
		Assert.assertTrue(exactMembers.stream().allMatch(alternative -> alternative.compactSupport() == null));
		Assert.assertEquals("Physical is the deliberate exact expansion boundary", 4,
			NativeContinuitySupportFixtureBridge.materialized(compactRealization));
		assertEqualCostCompetitors(compactAnalysis, compactModel, consumer, exactMembers);

		Assert.assertEquals("unconstrained solves keep raw objective bits and exact receipt",
			solveAll(explicitFixture), solveAll(compactFixture));
		SelectionPair compactSelections = selections(compactFixture);
		SelectionPair explicitSelections = selections(explicitFixture);
		Assert.assertEquals(compactSelections.local(), compactSelections.exact());
		Assert.assertEquals(explicitSelections.local(), compactSelections.local());
		Assert.assertEquals(explicitSelections.exact(), compactSelections.exact());
		for(int option = 0; option < 2; option++) {
			SelectionPair compactForced = selections(multiHeaderFixture(nativeFixture(option), true));
			SelectionPair explicitForced = selections(multiHeaderFixture(nativeFixture(option), false));
			Assert.assertEquals("Local and Exact must select the same exact receipt for source option " + option,
				compactForced.local(), compactForced.exact());
			Assert.assertEquals("relation and explicit reference must preserve the selected receipt for source option "
				+ option, explicitForced, compactForced);
		}
	}

	private static void assertEqualCostCompetitors(PlacementAnalysis analysis,
		ExactPhysicalModel model, CompiledHopKey consumer,
		List<ExactPhysicalModel.Alternative> exactMembers) {
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var optimized = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var consumerDomain = model.domains().stream()
			.filter(domain -> domain.node().key() == consumer).findFirst().orElseThrow();
		var axis = realizationFromAlternatives(exactMembers).nativeContinuitySupportProduct()
			.orElseThrow().axes().get(0);
		var sourceDomain = model.domains().stream()
			.filter(domain -> domain.node().key() == axis.get(0).source().rule().parentOccurrence())
			.findFirst().orElseThrow();
		for(int option = 0; option < axis.size(); option++) {
			var selectedKey = CandidateSelections.requiredInputSupportIdentity(axis.get(option).source());
			int sourceIndex = sourceAlternativeIndex(sourceDomain, selectedKey);
			int memberIndex = java.util.stream.IntStream.range(0, consumerDomain.alternatives().size())
				.filter(index -> exactMembers.contains(consumerDomain.alternatives().get(index)))
				.filter(index -> selectedKey.equals(CandidateSelections.requiredInputSupportIdentity(
					consumerDomain.alternatives().get(index).supportClause().inputBindings().get(0).source())))
				.findFirst().orElseThrow();
			String competitorLineage = "equal-cost-competitor-" + option;
			int competitorIndex = java.util.stream.IntStream.range(0, consumerDomain.alternatives().size())
				.filter(index -> consumerDomain.alternatives().get(index).realization() != null
					&& competitorLineage.equals(consumerDomain.alternatives().get(index)
						.realization().key().nativeLineage())).findFirst().orElseThrow();
			List<Integer> memberAssignment = new ArrayList<>(optimized.solverResult().assignmentInVariableOrder());
			memberAssignment.set(model.domains().indexOf(sourceDomain), sourceIndex);
			memberAssignment.set(model.domains().indexOf(consumerDomain), memberIndex);
			List<Integer> competitorAssignment = new ArrayList<>(memberAssignment);
			competitorAssignment.set(model.domains().indexOf(consumerDomain), competitorIndex);
			Assert.assertEquals("fixture must retain an equal-cost competitor for source option " + option,
				surface.evaluateCanonical(memberAssignment), surface.evaluateCanonical(competitorAssignment));
		}
	}

	private static CandidateEmissionRealization realizationFromAlternatives(
		List<ExactPhysicalModel.Alternative> alternatives) {
		return alternatives.get(0).realization();
	}

	private static int sourceAlternativeIndex(ExactPhysicalModel.DecisionDomain sourceDomain,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey selectedKey) {
		return java.util.stream.IntStream.range(0, sourceDomain.alternatives().size())
			.filter(index -> {
				var alternative = sourceDomain.alternatives().get(index);
				return alternative.candidateRule() != null && alternative.realization() != null
					&& CandidateSelections.requiredInputSupportIdentity(CandidateRealizationReference.of(
						alternative.candidateRule().key(), alternative.realization())).equals(selectedKey);
			}).findFirst().orElseThrow();
	}

	private static SelectionPair selections(Object fixture) throws Exception {
		PlacementAnalysis analysis = (PlacementAnalysis)component(fixture, "analysis");
		CompiledHopKey consumer = (CompiledHopKey)component(fixture, "consumer");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var local = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var exact = ExactPhysicalOptimizer.optimize(
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
		return nativeFixture(null);
	}

	private static Object nativeFixture(Integer selectedOption) throws Exception {
		String script = "A=federated(addresses=list(\"localhost:13001/A\"),"
			+ "ranges=list(list(0,0),list(8,4)));"
			+ "PA=colSums(A);C=exp(PA);print(sum(C));\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis base = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CompiledHopKey source = owner(base, "PA");
		CompiledHopKey consumer = owner(base, "C");
		CandidateRuleFact sourceRule = available(base, source);
		CandidateEmissionFact sourceEmission = sourceRule.allowedEmissionFacts().stream()
			.filter(candidate -> candidate.realizations().stream()
				.anyMatch(realization -> realization.key().durableAnchor() != null))
			.findFirst().orElseThrow();
		CandidateEmissionRealization sourceTemplate = sourceEmission.realizations().stream()
			.filter(realization -> realization.key().durableAnchor() != null).findFirst().orElseThrow();
		DurableAnchorKey witness = sourceTemplate.key().durableAnchor();
		PlacementProofKey sourceProof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			source, "multi-header-source:" + witness.normalizedSignature());
		List<CandidateEmissionRealization> sourceChoices = new ArrayList<>();
		for(int option = 0; option < 2; option++)
			sourceChoices.add(CandidateEmissionRealization.nativeLineage(
				sourceEmission.emissionState(), "unary-source-" + option, witness,
				List.of(sourceProof), List.of()));
		List<CandidateEmissionRealization> admittedSourceChoices = selectedOption == null
			? sourceChoices : List.of(sourceChoices.get(selectedOption));
		List<CandidateEmissionRealization> sourceRealizations = new ArrayList<>(sourceEmission.realizations());
		sourceRealizations.addAll(admittedSourceChoices);
		CandidateEmissionFact expandedSourceEmission = new CandidateEmissionFact(
			sourceEmission.emissionState(), sourceEmission.executionFType(),
			sourceEmission.derivedFoutAction(), sourceRealizations);
		CandidateRuleFact expandedSource = copy(sourceRule, sourceRule.allowedEmissionFacts().stream()
			.map(candidate -> candidate == sourceEmission ? expandedSourceEmission : candidate).toList());
		List<CandidateRealizationInputBinding> bindings = admittedSourceChoices.stream()
			.map(realization -> CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(expandedSource.key(), realization))).toList();

		CandidateRuleFact consumerRule = base.candidateRuleFacts().orderedFactsForParent(consumer).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.key().orderedInputs().size() == 1
				&& fact.key().orderedInputs().get(0).present()).findFirst().orElseThrow();
		CandidateEmissionFact oldEmission = consumerRule.allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState().execType()
				== org.apache.sysds.common.Types.ExecType.FED)
			.filter(emission -> emission.emissionState().placementState().fType() == witness.fType())
			.findFirst().orElseThrow();
		CandidateEmissionRealization oldRealization = oldEmission.realizations().get(0);
		CandidateEmissionRealization realization = NativeContinuitySupportFixtureBridge.nativeRelation(
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey
				.nativeLineage(oldRealization.key().emissionState(), "multi-header-unary"),
			consumer, witness, witness, true, List.of(bindings));
		List<CandidateEmissionRealization> tieCompetitors = new ArrayList<>();
		for(int option = 0; option < bindings.size(); option++)
			tieCompetitors.add(NativeContinuitySupportFixtureBridge.explicitNativeRelation(
				org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey
					.nativeLineage(oldRealization.key().emissionState(), "equal-cost-competitor-" + option),
				consumer, witness, witness, true, List.of(List.of(bindings.get(option)))));
		List<CandidateEmissionRealization> consumerRealizations = new ArrayList<>();
		consumerRealizations.add(realization);
		consumerRealizations.addAll(tieCompetitors);
		CandidateEmissionFact consumerEmission = new CandidateEmissionFact(oldEmission.emissionState(),
			oldEmission.executionFType(), oldEmission.derivedFoutAction(),
			consumerRealizations);
		CandidateRuleFact expandedConsumer = copy(consumerRule,
			consumerRule.allowedEmissionFacts().stream()
				.map(candidate -> candidate == oldEmission ? consumerEmission : candidate).toList());
		List<CandidateRuleFact> facts = base.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> isUnaryProducer(base, fact.key().parentOccurrence()))
			.filter(fact -> fact != sourceRule && fact != consumerRule)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		facts.add(expandedSource);
		facts.add(expandedConsumer);
		facts.sort(java.util.Comparator.comparing(fact -> fact.key().normalizedSignature()));
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.withCandidateFacts(
			base, program, facts);
		return new UnaryFixture(analysis, source, consumer, realization, bindings.stream()
			.map(binding -> CandidateSelections.requiredInputSupportIdentity(binding.source())).toList());
	}

	private static CandidateRuleFact available(PlacementAnalysis analysis, CompiledHopKey owner) {
		return analysis.candidateRuleFacts().orderedFactsForParent(owner).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.anyMatch(realization -> realization.key().durableAnchor() != null))
			.findFirst().orElseThrow();
	}

	private static CompiledHopKey owner(PlacementAnalysis analysis, String name) {
		return analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> name.equals(occurrence.hop().getName()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).findFirst().orElseThrow();
	}

	private static boolean isUnaryProducer(PlacementAnalysis analysis, CompiledHopKey owner) {
		String name = analysis.compiledHopOccurrences().stream()
			.filter(occurrence -> occurrence.key() == owner)
			.map(occurrence -> occurrence.hop().getName()).findFirst().orElse("");
		return name.equals("A") || name.equals("PA");
	}

	private static CandidateRuleFact copy(CandidateRuleFact rule, List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(rule.key(), rule.status(), rule.capability(), rule.shapeProof(),
			rule.profile(), emissions, rule.failureCode());
	}

	private static Object multiHeaderFixture(Object base, boolean compact) throws Exception {
		PlacementAnalysis source = (PlacementAnalysis)component(base, "analysis");
		CompiledHopKey consumer = (CompiledHopKey)component(base, "consumer");
		CandidateEmissionRealization original = realization(base);
		List<CandidateRealizationSupportClause> relation = multiHeaderRelation(original, consumer);
		CandidateEmissionRealization replacement = new CandidateEmissionRealization(
			original.key(), compact ? relation : List.copyOf(relation));
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference>
			sourceReferences = original.nativeContinuitySupportProduct().orElseThrow().axes().stream()
				.flatMap(List::stream).map(binding -> binding.source()).toList();
		List<CandidateRuleFact> facts = new ArrayList<>();
		for(CandidateRuleFact fact : source.candidateRuleFacts().orderedFacts()) {
			boolean changed = false;
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				List<CandidateEmissionRealization> realizations = emission.realizations().stream()
					.map(candidate -> candidate == original ? replacement
						: ownsReference(fact, candidate, sourceReferences)
							? exactNativeSource(candidate) : candidate).toList();
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

	private static boolean ownsReference(CandidateRuleFact fact,
		CandidateEmissionRealization realization,
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference> references) {
		return references.stream().anyMatch(reference ->
			reference.rule().parentOccurrence() == fact.key().parentOccurrence()
				&& reference.rule().equals(fact.key())
				&& reference.realization().equals(realization.key()));
	}

	private static CandidateEmissionRealization exactNativeSource(
		CandidateEmissionRealization realization) {
		DurableAnchorKey witness = realization.nativeContinuitySupportProduct()
			.map(PlacementAnalysis.NativeContinuitySupportProduct::nativeWorkerPoolWitness)
			.orElseGet(() -> realization.supportClauses().get(0).nativeWorkerPoolWitness());
		Assert.assertNotNull(witness);
		List<CandidateRealizationSupportClause> clauses = realization.supportClauses().stream()
			.map(clause -> new CandidateRealizationSupportClause(clause.proofDependencies(),
				clause.inputBindings(), witness, true)).toList();
		return new CandidateEmissionRealization(realization.key(), clauses);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationSupportClause> multiHeaderRelation(
		CandidateEmissionRealization original, CompiledHopKey owner) throws Exception {
		Object firstRelation = original.supportClauses();
		Method productMethod = firstRelation.getClass().getDeclaredMethod("product");
		productMethod.setAccessible(true);
		Object firstProduct = productMethod.invoke(firstRelation);
		Class<?> productClass = firstProduct.getClass();
		Method seedMethod = productClass.getDeclaredMethod("externalSeed");
		Method outputMethod = productClass.getDeclaredMethod("outputWorkerPoolWitness");
		Method exactMethod = productClass.getDeclaredMethod("exactPartitionRanges");
		Method axesMethod = productClass.getDeclaredMethod("axes");
		seedMethod.setAccessible(true);
		outputMethod.setAccessible(true);
		exactMethod.setAccessible(true);
		axesMethod.setAccessible(true);
		DurableAnchorKey firstSeed = (DurableAnchorKey)seedMethod.invoke(firstProduct);
		DurableAnchorKey secondSeed = new DurableAnchorKey(
			firstSeed.placementId() + "-second-header", firstSeed.fType(), firstSeed.partitions());
		Method tryCreate = productClass.getDeclaredMethod("tryCreate", DurableAnchorKey.class,
			DurableAnchorKey.class, boolean.class, List.class);
		tryCreate.setAccessible(true);
		Object secondProduct = tryCreate.invoke(null, secondSeed,
			outputMethod.invoke(firstProduct), exactMethod.invoke(firstProduct), axesMethod.invoke(firstProduct));
		Assert.assertNotNull(secondProduct);
		Constructor<?> relationConstructor = firstRelation.getClass().getDeclaredConstructor(
			CompiledHopKey.class, productClass, DurableAnchorKey.class, boolean.class);
		relationConstructor.setAccessible(true);
		DurableAnchorKey witness = original.nativeContinuitySupportProduct().orElseThrow()
			.nativeWorkerPoolWitness();
		Object secondRelation = relationConstructor.newInstance(owner, secondProduct, witness,
			original.nativeContinuitySupportProduct().orElseThrow().nativeWorkerPoolLayoutExact());
		Method union = firstRelation.getClass().getDeclaredMethod(
			"multiHeaderUnion", firstRelation.getClass());
		union.setAccessible(true);
		Optional<?> merged = (Optional<?>)union.invoke(firstRelation, secondRelation);
		Assert.assertTrue(merged.isPresent());
		return (List<CandidateRealizationSupportClause>)merged.orElseThrow();
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

	private static SolveSummary solveAll(Object fixture) throws Exception {
		PlacementAnalysis analysis = (PlacementAnalysis)component(fixture, "analysis");
		CompiledHopKey consumer = (CompiledHopKey)component(fixture, "consumer");
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var surface = ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		var local = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var exact = ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		return new SolveSummary(local.canonicalObjectiveBits(), summary(model, local, consumer),
			summary(model, exact, consumer));
	}

	private record UnaryFixture(PlacementAnalysis analysis, CompiledHopKey source,
		CompiledHopKey consumer, CandidateEmissionRealization supportRealization,
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey>
			supportKeys) { }
	private record SolveSummary(long objectiveBits, SelectionSummary local, SelectionSummary exact) { }
	private record SelectionPair(SelectionSummary local, SelectionSummary exact) { }
	private record SelectionSummary(long objectiveBits, String receiptSignature,
		List<String> proofSignatures, List<String> selectedSources,
		List<String> sharedSupplyLifetimes) { }
}
