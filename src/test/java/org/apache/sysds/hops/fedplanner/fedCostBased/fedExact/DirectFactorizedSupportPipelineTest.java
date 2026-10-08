/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** End-to-end parity for a direct-input Cartesian relation kept compact through selection. */
public class DirectFactorizedSupportPipelineTest {
	@Test(timeout = 30000)
	public void directProductMatchesExplicitCostAndSelectsNonRepresentativeReceipt() throws Exception {
		Fixture explicit = fixture(false);
		Fixture compact = fixture(true);
		Assert.assertEquals(0, CampaignBPlacementAnalysisFixtureBridge
			.materializedFactorizedClauseCount(compact.realization()));
		int last = compact.sourceKeys().size() - 1;
		Result explicitResult = solve(explicit, last);
		Result compactResult = solve(compact, last);
		Assert.assertEquals(explicitResult.objectiveBits(), compactResult.objectiveBits());
		Assert.assertEquals(explicitResult.optimumBits(), compactResult.optimumBits());
		Assert.assertEquals(explicitResult.selectedSource(), compactResult.selectedSource());
		Assert.assertEquals(compact.sourceKeys().get(last), compactResult.selectedSource());
		Assert.assertTrue("downstream may materialize only its representative and final receipt, never P",
			CampaignBPlacementAnalysisFixtureBridge
				.materializedFactorizedClauseCount(compact.realization()) <= 2);
	}

	@Test
	public void heterogeneousDirectAxesRemainExplicitDpRelations() throws Exception {
		Fixture compact = fixture(true);
		var product = compact.realization().factorizedSupportProduct().orElseThrow();
		var factor = new ArrayList<>(product.factors().get(0));
		CandidateRealizationInputBinding first = factor.get(0);
		factor.set(1, CandidateRealizationInputBinding.relocation(0, factor.get(1).source(),
			compact.analysis().graph().relocationActions().get(0).key()));
		CandidateEmissionRealization mixed = CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
			compact.realization().key(), List.of(), List.of(factor));
		Assert.assertTrue(mixed.independentSupportProduct().isEmpty());

		DurableAnchorKey foreign = new DurableAnchorKey("foreign", first.source().realization()
			.durableAnchor().fType(), List.of(new PlacementIdentity.AnchorPartition(
				"localhost:13999/X", List.of(0L, 0L), List.of(4L, 8L))));
		PlacementRealizationKey foreignKey = PlacementRealizationKey.durable(
			first.source().realization().emissionState(), foreign);
		CandidateRealizationReference foreignRef = CandidateRealizationReference.of(
			first.source().rule(), new CandidateEmissionRealization(foreignKey, List.of(
				new CandidateRealizationSupportClause(List.of(), List.of()))));
		CandidateEmissionRealization layouts = CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
			compact.realization().key(), List.of(), List.of(List.of(first,
				CandidateRealizationInputBinding.direct(0, foreignRef))));
		Assert.assertTrue(layouts.independentSupportProduct().isEmpty());

		CandidateRuleFact foreignRule = compact.analysis().candidateRuleFacts()
			.orderedFactsForParent(compact.consumer()).get(0);
		CandidateRealizationReference foreignOwner = new CandidateRealizationReference(
			foreignRule.key(), first.source().realization());
		CandidateEmissionRealization owners = CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
			compact.realization().key(), List.of(), List.of(List.of(first,
				CandidateRealizationInputBinding.direct(0, foreignOwner))));
		Assert.assertTrue(owners.independentSupportProduct().isEmpty());
	}

	private static Result solve(Fixture fixture, int selectedOption) {
		ExactPhysicalModel model = ExactPhysicalModel.build(fixture.analysis());
		var surface = ExactPhysicalCostModel.physicalCostSurface(fixture.analysis(), model);
		var local = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var exact = ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(local.canonicalObjectiveBits(), exact.canonicalObjectiveBits());
		List<Integer> assignment = new ArrayList<>(local.solverResult().assignmentInVariableOrder());
		var sourceDomain = model.domains().stream()
			.filter(domain -> domain.node().key() == fixture.source()).findFirst().orElseThrow();
		int sourceValue = java.util.stream.IntStream.range(0, sourceDomain.alternatives().size())
			.filter(index -> fixture.sourceKeys().get(selectedOption).equals(
				supportKey(sourceDomain.alternatives().get(index))))
			.findFirst().orElse(-1);
		Assert.assertTrue("selected direct source option missing; domain keys="
			+ sourceDomain.alternatives().stream().map(DirectFactorizedSupportPipelineTest::supportKey).toList(),
			sourceValue >= 0);
		assignment.set(model.domains().indexOf(sourceDomain), sourceValue);
		var consumerDomain = model.domains().stream()
			.filter(domain -> domain.node().key() == fixture.consumer()).findFirst().orElseThrow();
		int consumerValue = java.util.stream.IntStream.range(0, consumerDomain.alternatives().size())
			.filter(index -> {
				var alternative = consumerDomain.alternatives().get(index);
				if(fixture.compact())
					return alternative.compactSupport() != null;
				return alternative.supportClause() != null && alternative.supportClause().inputBindings().stream()
					.anyMatch(binding -> CandidateSelections.requiredInputSupportIdentity(binding.source())
						.equals(fixture.sourceKeys().get(selectedOption)));
			}).findFirst().orElseThrow();
		assignment.set(model.domains().indexOf(consumerDomain), consumerValue);
		long objectiveBits = surface.evaluateCanonical(assignment);
		var result = new ExactCategoricalSolver.Result(Double.longBitsToDouble(objectiveBits), assignment,
			local.solverResult().statistics());
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(model,
			new ExactPhysicalOptimizer.Result(result, objectiveBits, surface.contributionFingerprint(),
				surface.selectedSharedSupplyLifetimes(assignment)));
		Assert.assertNotNull(selection);
		var receipt = selection.candidateReceipts().stream()
			.filter(candidate -> candidate.rule().parentOccurrence() == fixture.consumer())
			.findFirst().orElseThrow();
		var selectedBinding = receipt.supportClause().inputBindings().get(0);
		return new Result(objectiveBits, local.canonicalObjectiveBits(),
			CandidateSelections.requiredInputSupportIdentity(selectedBinding.source()));
	}

	private static PlacementIdentity.CandidateRealizationSupportKey supportKey(
		ExactPhysicalModel.Alternative alternative) {
		CandidateRuleFact rule = alternative.captured()
			? alternative.candidateRule() : alternative.executionRule();
		return rule == null || alternative.realization() == null ? null
			: CandidateSelections.requiredInputSupportIdentity(
				CandidateRealizationReference.of(rule.key(), alternative.realization()));
	}

	private static Fixture fixture(boolean compact) throws Exception {
		String script = "A=federated(addresses=list(\"localhost:13001/A\"),"
			+ "ranges=list(list(0,0),list(8,4)));PA=colSums(A);print(sum(PA));";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis base = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CompiledHopKey source = owner(base, "A"), consumer = owner(base, "PA");
		CandidateRuleFact sourceRule = available(base, source);
		CandidateEmissionFact sourceEmission = sourceRule.allowedEmissionFacts().stream()
			.filter(emission -> emission.realizations().stream()
				.anyMatch(realization -> realization.key().durableAnchor() != null))
			.findFirst().orElseThrow();
		CandidateEmissionRealization template = sourceEmission.realizations().stream()
			.filter(realization -> realization.key().durableAnchor() != null).findFirst().orElseThrow();
		List<CandidateEmissionRealization> sourceRealizations = new ArrayList<>();
		List<CandidateRealizationReference> references = new ArrayList<>();
		for(int option = 0; option < 16; option++) {
			DurableAnchorKey anchor = new DurableAnchorKey("direct-option-" + option,
				template.key().durableAnchor().fType(), template.key().durableAnchor().partitions());
			CandidateEmissionRealization realization = new CandidateEmissionRealization(
				PlacementRealizationKey.durable(template.key().emissionState(), anchor),
				template.supportClauses());
			sourceRealizations.add(realization);
			references.add(CandidateRealizationReference.of(sourceRule.key(), realization));
		}
		CandidateEmissionFact expandedSourceEmission = new CandidateEmissionFact(
			sourceEmission.emissionState(), sourceEmission.executionFType(),
			sourceEmission.derivedFoutAction(), sourceRealizations);
		CandidateRuleFact expandedSource = copy(sourceRule, List.of(expandedSourceEmission));

		CandidateRuleFact consumerRule = base.candidateRuleFacts().orderedFactsForParent(consumer).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> !fact.key().orderedInputs().isEmpty() && fact.key().orderedInputs().get(0).present())
			.findFirst().orElseThrow();
		CandidateEmissionFact oldConsumerEmission = consumerRule.allowedEmissionFacts().stream()
			.filter(emission -> emission.realizations().stream()
				.anyMatch(realization -> realization.key().durableAnchor() != null))
			.findFirst().orElseThrow();
		CandidateEmissionRealization oldConsumerRealization = oldConsumerEmission.realizations().stream()
			.filter(realization -> realization.key().durableAnchor() != null).findFirst().orElseThrow();
		List<CandidateRealizationInputBinding> bindings = references.stream()
			.map(reference -> CandidateRealizationInputBinding.direct(0, reference)).toList();
		DurableAnchorKey consumerAnchor = new DurableAnchorKey("direct-consumer",
			oldConsumerRealization.key().durableAnchor().fType(),
			oldConsumerRealization.key().durableAnchor().partitions());
		PlacementRealizationKey consumerKey = PlacementRealizationKey.durable(
			oldConsumerRealization.key().emissionState(), consumerAnchor);
		CandidateEmissionRealization consumerRealization = compact
			? CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
				consumerKey, List.of(), List.of(bindings))
			: new CandidateEmissionRealization(consumerKey, bindings.stream()
				.map(binding -> new CandidateRealizationSupportClause(List.of(), List.of(binding))).toList());
		CandidateEmissionFact consumerEmission = new CandidateEmissionFact(
			oldConsumerEmission.emissionState(), oldConsumerEmission.executionFType(),
			oldConsumerEmission.derivedFoutAction(), List.of(consumerRealization));
		CandidateRuleFact expandedConsumer = copy(consumerRule, List.of(consumerEmission));
		List<CandidateRuleFact> facts = new ArrayList<>();
		facts.add(expandedSource);
		facts.add(expandedConsumer);
		facts.sort(java.util.Comparator.comparing(fact -> fact.key().normalizedSignature()));
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge
			.withCandidateFacts(base, program, facts);
		return new Fixture(analysis, source, consumer, consumerRealization, compact,
			references.stream().map(CandidateSelections::requiredInputSupportIdentity).toList());
	}

	private static CandidateRuleFact copy(CandidateRuleFact rule, List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(rule.key(), rule.status(), rule.capability(), rule.shapeProof(),
			rule.profile(), emissions, rule.failureCode());
	}

	private static CandidateRuleFact available(PlacementAnalysis analysis, CompiledHopKey owner) {
		return analysis.candidateRuleFacts().orderedFactsForParent(owner).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
				.anyMatch(realization -> realization.key().durableAnchor() != null))
			.findFirst().orElseThrow();
	}

	private static CompiledHopKey owner(PlacementAnalysis analysis, String name) {
		return analysis.compiledHopOccurrences().stream().filter(occurrence -> name.equals(occurrence.hop().getName()))
			.map(PlacementAnalysis.HopOccurrenceProjection::key).findFirst().orElseThrow();
	}

	private record Fixture(PlacementAnalysis analysis, CompiledHopKey source, CompiledHopKey consumer,
		CandidateEmissionRealization realization, boolean compact,
		List<PlacementIdentity.CandidateRealizationSupportKey> sourceKeys) { }
	private record Result(long objectiveBits, long optimumBits,
		PlacementIdentity.CandidateRealizationSupportKey selectedSource) { }
}
