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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactFactorizedSupportPipelineTest {
	@Test(timeout = 30000)
	public void fixedOwnerHundredByHundredStaysCompactThroughLocalDpAndReceiptResolution() throws Exception {
		Fixture fixture = fixture(100, 100, true);
		Assert.assertEquals(0, CampaignBPlacementAnalysisFixtureBridge
			.materializedFactorizedClauseCount(fixture.supportRealization()));
		ExactPhysicalModel model = ExactPhysicalModel.build(fixture.analysis());
		var consumer = model.domains().stream().filter(domain -> domain.node().key() == fixture.consumer())
			.findFirst().orElseThrow();
		var compact = consumer.alternatives().stream().filter(alternative -> alternative.compactSupport() != null)
			.toList();
		Assert.assertTrue("consumer domain remains constant-sized", consumer.alternatives().size() < 16);
		Assert.assertEquals("one compact consumer representative replaces the 10,000 clauses", 1, compact.size());
		Assert.assertEquals(10_000, compact.get(0).compactSupport().logicalClauseCount());
		Assert.assertEquals(2, compact.get(0).compactSupport().axes().size());

		var surface = ExactPhysicalCostModel.physicalCostSurface(fixture.analysis(), model);
		var optimized = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		Assert.assertNotNull("the unmodified Local optimum must produce a validated selection",
			ExactPhysicalSelection.create(model, optimized));
		Assert.assertTrue(CampaignBPlacementAnalysisFixtureBridge
			.materializedFactorizedClauseCount(fixture.supportRealization()) <= 1);
		ExactPhysicalSelection selected = supportedSelection(model, surface, optimized,
			fixture, fixture.leftSupportKeys().size() - 1, fixture.rightSupportKeys().size() - 1);
		Assert.assertTrue(selected.alternativesInDecisionOrder().stream().anyMatch(alternative ->
			alternative.decision() == fixture.consumer() && alternative.compactSupport() != null));
		var receipt = selected.candidateReceipts().stream().filter(candidate ->
			candidate.rule().parentOccurrence() == fixture.consumer()).findFirst().orElseThrow();
		Assert.assertNotNull(receipt.supportClause());
		Assert.assertEquals("selection materializes only the two chosen axis bindings", 2,
			receipt.supportClause().inputBindings().size());
		Assert.assertTrue(CampaignBPlacementAnalysisFixtureBridge
			.materializedFactorizedClauseCount(fixture.supportRealization()) <= 2);
		System.out.println("FactorizedPipelineWork logicalClauses=10000 consumerAlternatives="
			+ consumer.alternatives().size() + " ownedRepresentatives="
			+ CampaignBPlacementAnalysisFixtureBridge.materializedFactorizedClauseCount(
				fixture.supportRealization()) + " selectedBindings="
			+ receipt.supportClause().inputBindings().size());
	}

	@Test(timeout = 30000)
	public void twoByThreeCompactAndExplicitRelationsHaveIdenticalObjectiveBitsAndReceipt() throws Exception {
		Fixture compactFixture = fixture(2, 3, true);
		Fixture explicitFixture = fixture(2, 3, false);
		Assert.assertEquals(solveAll(explicitFixture), solveAll(compactFixture));
	}

	@Test
	public void mixedBindingAuthorityAndExplicitClausesRemainOutsideCompactDp() throws Exception {
		Fixture compact = fixture(2, 2, true);
		var factors = compact.supportRealization().factorizedSupportProduct().orElseThrow().factors();
		List<List<CandidateRealizationInputBinding>> mixedFactors = new ArrayList<>();
		for(List<CandidateRealizationInputBinding> factor : factors)
			mixedFactors.add(new ArrayList<>(factor));
		CandidateRealizationInputBinding relocation = mixedFactors.get(0).get(0);
		mixedFactors.get(0).set(1, CandidateRealizationInputBinding.direct(0, relocation.source()));
		CandidateEmissionRealization mixed = CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
			compact.supportRealization().key(), List.of(), mixedFactors);
		Assert.assertTrue(mixed.factorizedSupportProduct().isPresent());
		Assert.assertTrue("mixed direct/relocation authority must use explicit fallback",
			mixed.independentSupportProduct().isEmpty());

		Fixture explicit = fixture(2, 2, false);
		Assert.assertTrue(explicit.supportRealization().factorizedSupportProduct().isEmpty());
		Assert.assertTrue(explicit.supportRealization().independentSupportProduct().isEmpty());
	}

	@Test(timeout = 30000)
	public void localAndFederatedSourceChoicesKeepDistinctCostsInCompactRelation() throws Exception {
		RelationResult compact = solveAll(fixture(1, 1, true, true));
		RelationResult explicit = solveAll(fixture(1, 1, false, true));
		Assert.assertEquals(explicit, compact);
		Assert.assertEquals(2, compact.rows().size());
		Assert.assertNotEquals("LOCAL upload and resident FED source must retain distinct costs",
			compact.rows().get(0).objectiveBits(), compact.rows().get(1).objectiveBits());
		System.out.println("FactorizedCostParity localBits="
			+ Long.toUnsignedString(compact.rows().get(0).objectiveBits()) + " fedBits="
			+ Long.toUnsignedString(compact.rows().get(1).objectiveBits()) + " optimumBits="
			+ Long.toUnsignedString(compact.optimumBits()));
	}

	@Test(timeout = 30000)
	public void sparseCorrelatedIndexedRelationMatchesExplicitPipelineAndReceipts() throws Exception {
		List<SupportPair> admitted = List.of(
			new SupportPair(0, 0), new SupportPair(0, 2),
			new SupportPair(1, 1), new SupportPair(1, 2));
		Fixture explicit = fixture(2, 3, SupportEncoding.EXPLICIT, false, admitted);
		Fixture indexed = fixture(2, 3, SupportEncoding.INDEXED, false, admitted);

		Assert.assertFalse(explicit.supportRealization().indexedSupport());
		Assert.assertTrue(indexed.supportRealization().indexedSupport());
		Assert.assertEquals(admitted.size(), explicit.supportRealization().fullyMaterializedSupportClauseCount());
		Assert.assertEquals(0, indexed.supportRealization().fullyMaterializedSupportClauseCount());
		Assert.assertEquals(explicit.supportRealization().supportClauses(),
			indexed.supportRealization().supportClauses());
		Assert.assertEquals(explicit.supportRealization().supportClauses().hashCode(),
			indexed.supportRealization().supportClauses().hashCode());
		for(CandidateRealizationSupportClause handle : indexed.supportRealization().supportClauses()) {
			Assert.assertTrue(handle.isIndexed());
			Assert.assertEquals(handle, indexed.supportRealization()
				.supportClauseForCombinationId(handle.indexedCombinationId()));
		}

		RelationResult explicitResult = solveAll(explicit);
		RelationResult indexedResult = solveAll(indexed);
		Assert.assertEquals(explicitResult, indexedResult);
		Assert.assertEquals(admitted.size(), indexedResult.rows().size());
		ExactPhysicalModel model = ExactPhysicalModel.build(indexed.analysis());
		var surface = ExactPhysicalCostModel.physicalCostSurface(indexed.analysis(), model);
		var optimized = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var selected = supportedSelection(model, surface, optimized, indexed, 1, 2);
		var receipt = selected.candidateReceipts().stream().filter(candidate ->
			candidate.rule().parentOccurrence() == indexed.consumer()).findFirst().orElseThrow();
		Assert.assertTrue("final receipt must retain the indexed whole-combination handle",
			receipt.supportClause().isIndexed());
		Assert.assertEquals(receipt.supportClause(), indexed.supportRealization()
			.supportClauseForCombinationId(receipt.supportClause().indexedCombinationId()));
		Assert.assertEquals(0, indexed.supportRealization().fullyMaterializedSupportClauseCount());
		System.out.println("IndexedPipelineWork admitted=" + admitted.size()
			+ " cartesian=6 materialized="
			+ indexed.supportRealization().fullyMaterializedSupportClauseCount());
	}

	@Test(timeout = 30000)
	public void mixedDirectAndRelocationIndexedRelationMatchesExplicitPipeline() throws Exception {
		List<SupportPair> admitted = rectangularPairs(2, 1);
		Fixture explicit = fixture(2, 1, SupportEncoding.EXPLICIT, false, admitted, true);
		Fixture indexed = fixture(2, 1, SupportEncoding.INDEXED, false, admitted, true);
		Assert.assertTrue(indexed.supportRealization().supportClauses().stream()
			.flatMap(clause -> clause.inputBindings().stream()).anyMatch(binding -> binding.relocationAction() == null));
		Assert.assertTrue(indexed.supportRealization().supportClauses().stream()
			.flatMap(clause -> clause.inputBindings().stream()).anyMatch(binding -> binding.relocationAction() != null));
		Assert.assertEquals(solveAll(explicit), solveAll(indexed));
	}

	private static RelationResult solveAll(Fixture fixture) {
		ExactPhysicalModel model = ExactPhysicalModel.build(fixture.analysis());
		var surface = ExactPhysicalCostModel.physicalCostSurface(fixture.analysis(), model);
		var local = LocalPhysicalOptimizer.optimize(model, surface).physicalResult();
		var exact = ExactPhysicalOptimizer.optimize(model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals("Local and Exact must agree on the unmodified optimum",
			local.canonicalObjectiveBits(), exact.canonicalObjectiveBits());
		List<Result> results = new ArrayList<>();
		for(SupportPair pair : fixture.admittedPairs()) {
			int left = pair.leftOption();
			int right = pair.rightOption();
			var localSelection = supportedSelection(model, surface, local, fixture, left, right);
			var exactSelection = supportedSelection(model, surface, exact, fixture, left, right);
			Assert.assertEquals(localSelection.objectiveBits(), exactSelection.objectiveBits());
			var receipt = localSelection.candidateReceipts().stream().filter(candidate ->
				candidate.rule().parentOccurrence() == fixture.consumer()).findFirst().orElseThrow();
			Assert.assertEquals(2, receipt.supportClause().inputBindings().size());
			if(!fixture.mixedAuthority())
				Assert.assertTrue(receipt.supportClause().inputBindings().stream().allMatch(binding ->
					binding.relocationAction() != null));
			results.add(new Result(localSelection.objectiveBits(),
				receipt.supportClause().normalizedSignature()));
		}
		return new RelationResult(local.canonicalObjectiveBits(), List.copyOf(results));
	}

	private static ExactPhysicalSelection supportedSelection(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface, ExactPhysicalOptimizer.Result optimized,
		Fixture fixture, int leftOption, int rightOption) {
		List<Integer> assignment = new ArrayList<>(optimized.solverResult().assignmentInVariableOrder());
		var consumerDomain = model.domains().stream().filter(domain -> domain.node().key() == fixture.consumer())
			.findFirst().orElseThrow();
		java.util.IdentityHashMap<CompiledHopKey,
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey>
			desired = new java.util.IdentityHashMap<>();
		desired.put(fixture.left(), fixture.leftSupportKeys().get(leftOption));
		desired.put(fixture.right(), fixture.rightSupportKeys().get(rightOption));
		int consumerIndex = java.util.stream.IntStream.range(0, consumerDomain.alternatives().size())
			.filter(index -> fixture.compact()
				? consumerDomain.alternatives().get(index).compactSupport() != null
				: consumerDomain.alternatives().get(index).captured()
					&& consumerDomain.alternatives().get(index).supportClause().inputBindings().size() == 2
					&& consumerDomain.alternatives().get(index).supportClause().inputBindings().stream()
						.allMatch(binding -> desired.get(binding.source().rule().parentOccurrence()).equals(
							CandidateSelections.requiredInputSupportIdentity(binding.source()))))
			.findFirst().orElseThrow();
		var consumerAlternative = consumerDomain.alternatives().get(consumerIndex);
		assignment.set(model.domains().indexOf(consumerDomain), consumerIndex);
		List<SourceChoice> sourceChoices = new ArrayList<>();
		if(consumerAlternative.compactSupport() != null)
			for(var axis : consumerAlternative.compactSupport().axes())
				sourceChoices.add(new SourceChoice(axis.sourceOwner(), desired.get(axis.sourceOwner())));
		else
			for(var binding : consumerAlternative.supportClause().inputBindings())
				sourceChoices.add(new SourceChoice(binding.source().rule().parentOccurrence(),
					CandidateSelections.requiredInputSupportIdentity(binding.source())));
		for(SourceChoice sourceChoice : sourceChoices) {
			var sourceDomain = model.domains().stream().filter(domain ->
				domain.node().key() == sourceChoice.owner()).findFirst().orElseThrow();
			int sourceIndex = java.util.stream.IntStream.range(0, sourceDomain.alternatives().size())
				.filter(index -> sourceChoice.supportKey().equals(
					supportKey(sourceDomain.alternatives().get(index))))
				.findFirst().orElseThrow();
			assignment.set(model.domains().indexOf(sourceDomain), sourceIndex);
		}
		long objectiveBits = surface.evaluateCanonical(assignment);
		assertHardFactorsFinite(model, assignment);
		var result = new ExactCategoricalSolver.Result(Double.longBitsToDouble(objectiveBits), assignment,
			optimized.solverResult().statistics());
		return ExactPhysicalSelection.create(model, new ExactPhysicalOptimizer.Result(result,
			objectiveBits, surface.contributionFingerprint(), surface.selectedSharedSupplyLifetimes(assignment)));
	}

	private static void assertHardFactorsFinite(ExactPhysicalModel model, List<Integer> assignment) {
		java.util.IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions =
			new java.util.IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		for(var factor : model.hardFactors()) {
			int[] local = new int[factor.scope().size()];
			for(int index = 0; index < local.length; index++)
				local[index] = assignment.get(positions.get(factor.scope().get(index)));
			Assert.assertTrue("enumerated support row must satisfy every model hard factor",
				Double.isFinite(factor.cost(local)));
		}
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey
		supportKey(ExactPhysicalModel.Alternative alternative) {
		CandidateRuleFact rule = alternative.captured()
			? alternative.candidateRule() : alternative.executionRule();
		if(rule == null || alternative.realization() == null)
			return null;
		return CandidateSelections.requiredInputSupportIdentity(
			CandidateRealizationReference.of(rule.key(), alternative.realization()));
	}

	private static Fixture fixture(int leftSize, int rightSize, boolean compact) throws Exception {
		return fixture(leftSize, rightSize, compact, false);
	}

	private static Fixture fixture(int leftSize, int rightSize, boolean compact,
		boolean includeLocalLeftChoice) throws Exception {
		return fixture(leftSize, rightSize,
			compact ? SupportEncoding.FACTORIZED : SupportEncoding.EXPLICIT,
			includeLocalLeftChoice,
			rectangularPairs(leftSize + (includeLocalLeftChoice ? 1 : 0), rightSize));
	}

	private static Fixture fixture(int leftSize, int rightSize, SupportEncoding encoding,
		boolean includeLocalLeftChoice, List<SupportPair> admittedPairs) throws Exception {
		return fixture(leftSize, rightSize, encoding, includeLocalLeftChoice, admittedPairs, false);
	}

	private static Fixture fixture(int leftSize, int rightSize, SupportEncoding encoding,
		boolean includeLocalLeftChoice, List<SupportPair> admittedPairs,
		boolean mixedAuthority) throws Exception {
		String script = "A=federated(addresses=list(\"localhost:13001/A\"),ranges=list(list(0,0),list(8,4)));\n"
			+ "B=federated(addresses=list(\"localhost:13002/B\"),ranges=list(list(0,0),list(8,4)));\n"
			+ (includeLocalLeftChoice ? "PA=A+1;PB=B+1;" : "PA=colSums(A);PB=colSums(B);")
			+ "C=PA*PB;print(sum(C));\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program,
			includeLocalLeftChoice ? Privacy.PUBLIC : Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis base = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		CompiledHopKey left = owner(base, "PA"), right = owner(base, "PB"), consumer = owner(base, "C");
		CandidateRuleFact leftRule = available(base, left);
		CandidateRuleFact rightRule = available(base, right);
		CandidateRuleFact consumerRule = base.candidateRuleFacts().orderedFactsForParent(consumer).stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
			.filter(fact -> fact.key().orderedInputs().size() == 2
				&& fact.key().orderedInputs().stream().allMatch(input -> input.present()))
			.findFirst().orElseThrow();
		CandidateRealizationReference localLeft = includeLocalLeftChoice
			? localReference(base, left) : null;
		ExpandedSource expandedLeft = expandedSource(leftRule, leftSize, "left", localLeft);
		ExpandedSource expandedRight = expandedSource(rightRule, rightSize, "right", null);
		java.util.concurrent.atomic.AtomicReference<RelocationPair> selectedPair =
			new java.util.concurrent.atomic.AtomicReference<>();
		CandidateEmissionFact oldEmission = consumerRule.allowedEmissionFacts().stream()
			.filter(emission -> {
				RelocationPair pair = relocationPair(base, left, right, consumer,
					emission.emissionState().placementState());
				if(pair == null)
					return false;
				selectedPair.set(pair);
				return true;
			})
			.findFirst().orElseThrow();
		RelocationPair pair = selectedPair.get();
		List<CandidateRealizationInputBinding> leftBindings = new ArrayList<>(
			bindings(0, expandedLeft, pair.left()));
		if(mixedAuthority)
			leftBindings.set(0, CandidateRealizationInputBinding.direct(0,
				leftBindings.get(0).source()));
		List<CandidateRealizationInputBinding> rightBindings = bindings(1, expandedRight, pair.right());
		CandidateEmissionRealization oldRealization = oldEmission.realizations().get(0);
		CandidateEmissionRealization realization;
		if(encoding == SupportEncoding.FACTORIZED) {
			if(admittedPairs.size() != leftBindings.size() * rightBindings.size())
				throw new IllegalArgumentException("Factorized fixture requires a complete Cartesian relation");
			realization = CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
				oldRealization.key(), List.of(), List.of(leftBindings, rightBindings));
		}
		else {
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
			for(SupportPair admitted : admittedPairs)
				clauses.add(new CandidateRealizationSupportClause(List.of(), List.of(
					leftBindings.get(admitted.leftOption()), rightBindings.get(admitted.rightOption()))));
			realization = new CandidateEmissionRealization(oldRealization.key(), clauses);
			if(encoding == SupportEncoding.INDEXED)
				realization = CampaignBPlacementAnalysisFixtureBridge.indexedRealization(realization);
		}
		CandidateEmissionFact consumerEmission = new CandidateEmissionFact(oldEmission.emissionState(),
			oldEmission.executionFType(), oldEmission.derivedFoutAction(), List.of(realization));
		CandidateRuleFact expandedConsumer = copy(consumerRule, List.of(consumerEmission));
		List<CandidateRuleFact> facts = base.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> isFixtureProducer(base, fact.key().parentOccurrence()))
			.filter(fact -> fact != leftRule && fact != rightRule && fact != consumerRule)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		facts.add(expandedLeft.rule());
		facts.add(expandedRight.rule());
		facts.add(expandedConsumer);
		facts.sort(java.util.Comparator.comparing(fact -> fact.key().normalizedSignature()));
		return new Fixture(CampaignBPlacementAnalysisFixtureBridge.withCandidateFacts(base, program, facts),
			left, right, consumer, realization, encoding == SupportEncoding.FACTORIZED,
			leftBindings.stream().map(binding ->
				CandidateSelections.requiredInputSupportIdentity(binding.source())).toList(),
			rightBindings.stream().map(binding ->
				CandidateSelections.requiredInputSupportIdentity(binding.source())).toList(),
			List.copyOf(admittedPairs), mixedAuthority);
	}

	private static List<SupportPair> rectangularPairs(int leftSize, int rightSize) {
		List<SupportPair> pairs = new ArrayList<>(leftSize * rightSize);
		for(int left = 0; left < leftSize; left++)
			for(int right = 0; right < rightSize; right++)
				pairs.add(new SupportPair(left, right));
		return List.copyOf(pairs);
	}

	private static ExpandedSource expandedSource(CandidateRuleFact rule, int size, String prefix,
		CandidateRealizationReference extraChoice) {
		CandidateEmissionFact emission = rule.allowedEmissionFacts().stream().filter(candidate ->
			candidate.realizations().stream().anyMatch(realization -> realization.key().durableAnchor() != null))
			.findFirst().orElseThrow();
		CandidateEmissionRealization template = emission.realizations().stream()
			.filter(realization -> realization.key().durableAnchor() != null)
			.findFirst().orElseThrow();
		DurableAnchorKey anchor = template.key().durableAnchor();
		PlacementProofKey nativeProof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
			rule.key().parentOccurrence(), "test-native-pool:" + anchor.normalizedSignature());
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		for(int index = 0; index < size; index++)
			realizations.add(CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), prefix + '-' + index, anchor, List.of(nativeProof), List.of()));
		List<CandidateEmissionRealization> allRealizations = new ArrayList<>(emission.realizations());
		allRealizations.addAll(realizations);
		CandidateEmissionFact expandedEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), allRealizations);
		List<CandidateEmissionFact> emissions = rule.allowedEmissionFacts().stream()
			.map(candidate -> candidate == emission ? expandedEmission : candidate).toList();
		CandidateRuleFact expandedRule = copy(rule, emissions);
		List<CandidateRealizationReference> choices = new ArrayList<>();
		if(extraChoice != null)
			choices.add(extraChoice);
		for(CandidateEmissionRealization realization : realizations)
			choices.add(CandidateRealizationReference.of(expandedRule.key(), realization));
		return new ExpandedSource(expandedRule, List.copyOf(choices));
	}

	private static List<CandidateRealizationInputBinding> bindings(int position, ExpandedSource source,
		RelocationActionKey action) {
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(CandidateRealizationReference reference : source.references())
			bindings.add(CandidateRealizationInputBinding.relocation(position, reference, action));
		return bindings;
	}

	private static CandidateRealizationReference localReference(PlacementAnalysis analysis,
		CompiledHopKey owner) {
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFactsForParent(owner))
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(realization.key().layoutKind() == PlacementLayoutKind.LOCAL)
							return CandidateRealizationReference.of(fact.key(), realization);
		throw new IllegalStateException("Fixture producer has no legal LOCAL realization");
	}

	private static List<RelocationActionKey> relocations(PlacementAnalysis analysis, CompiledHopKey source,
		CompiledHopKey consumer, int position, org.apache.sysds.hops.fedplanner.placement.PlacementState target) {
		var sourceValue = analysis.graph().node(source).orElseThrow().valueVersion();
		return analysis.graph().relocationActions().stream()
			.filter(action -> action.key().sourceValueVersion().equals(sourceValue))
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == consumer && obligation.inputPosition() == position
					&& obligation.requiredPlacement().equals(target)))
			.map(action -> action.key()).toList();
	}

	private static RelocationPair relocationPair(PlacementAnalysis analysis, CompiledHopKey left,
		CompiledHopKey right, CompiledHopKey consumer,
		org.apache.sysds.hops.fedplanner.placement.PlacementState target) {
		for(RelocationActionKey leftAction : relocations(analysis, left, consumer, 0, target))
			for(RelocationActionKey rightAction : relocations(analysis, right, consumer, 1, target))
				if(org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.samePhysicalWorkerPool(
					leftAction.durableAnchor(), rightAction.durableAnchor()))
					return new RelocationPair(leftAction, rightAction);
		return null;
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

	private static boolean isFixtureProducer(PlacementAnalysis analysis, CompiledHopKey owner) {
		String name = analysis.compiledHopOccurrences().stream().filter(occurrence -> occurrence.key() == owner)
			.map(occurrence -> occurrence.hop().getName()).findFirst().orElse("");
		return name.equals("A") || name.equals("B") || name.equals("PA") || name.equals("PB");
	}

	private record Fixture(PlacementAnalysis analysis, CompiledHopKey left, CompiledHopKey right,
		CompiledHopKey consumer, CandidateEmissionRealization supportRealization, boolean compact,
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey>
			leftSupportKeys,
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey>
			rightSupportKeys,
		List<SupportPair> admittedPairs,
		boolean mixedAuthority) { }
	private enum SupportEncoding { EXPLICIT, FACTORIZED, INDEXED }
	private record SupportPair(int leftOption, int rightOption) { }
	private record Result(long objectiveBits, String supportSignature) { }
	private record RelationResult(long optimumBits, List<Result> rows) { }
	private record RelocationPair(RelocationActionKey left, RelocationActionKey right) { }
	private record SourceChoice(CompiledHopKey owner,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationSupportKey supportKey) { }
	private record ExpandedSource(CandidateRuleFact rule,
		List<CandidateRealizationReference> references) { }
}
