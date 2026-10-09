/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression locks for exact input-authority product and evaluator optimizations. */
public class ExactInputAuthorityOptimizationTest {
	@Test
	public void identityOrderedInputScopePreservesLegacyEqualityFallbackAndOrder()
		throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(
			ExactNativeLocalAnchorFanoutCostTest.analysis(false));
		Assert.assertTrue("fixture must expose multiple decision domains", model.domains().size() > 1);
		Assert.assertEquals("one variable key per canonical decision node", model.domains().size(),
			model.domains().stream().map(domain -> domain.variable().key()).distinct().count());
		Assert.assertEquals("same-build domains cannot be record-equal", model.domains().size(),
			new HashSet<>(model.domains()).size());
		var first = model.domains().get(0);
		var second = model.domains().get(1);
		List<ExactPhysicalModel.DecisionDomain> scope =
			ExactPhysicalModel.identityOrderedScopeForTest(
				List.of(first, second, first, second));
		Assert.assertEquals(2, scope.size());
		Assert.assertSame(first, scope.get(0));
		Assert.assertSame(second, scope.get(1));

		var equalButDistinct = new ExactPhysicalModel.DecisionDomain(
			first.node(), first.variable(), first.alternatives());
		Assert.assertNotSame(first, equalButDistinct);
		Assert.assertEquals(first, equalButDistinct);
		Assert.assertEquals(List.of(first),
			ExactPhysicalModel.identityOrderedScopeForTest(List.of(first, equalButDistinct)));

		var reorderable = model.domains().stream()
			.filter(domain -> domain.alternatives().size() > 1).findFirst().orElseThrow();
		List<ExactPhysicalModel.Alternative> reversed = new java.util.ArrayList<>(
			reorderable.alternatives());
		java.util.Collections.reverse(reversed);
		var sameVariableButUnequal = new ExactPhysicalModel.DecisionDomain(
			reorderable.node(), reorderable.variable(), reversed);
		Assert.assertNotEquals(reorderable, sameVariableButUnequal);
		Assert.assertEquals(List.of(reorderable, sameVariableButUnequal),
			ExactPhysicalModel.identityOrderedScopeForTest(
				List.of(reorderable, sameVariableButUnequal)));
	}

	@Test
	public void allocationFreeEvaluationPreservesFactorScopesOrderAndRawTruth() throws Exception {
		var analysis = privateAggregateAnalysis();
		ExactPhysicalModel optimized = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel allocating =
			ExactPhysicalModel.buildWithAllocatingInputAuthorityEvaluationForTest(analysis);
		Assert.assertEquals(domainSignatures(allocating), domainSignatures(optimized));
		Assert.assertEquals(factorTruth(allocating), factorTruth(optimized));
	}

	@Test
	public void lazyAlternativeSignaturesPreserveEagerDomainsTruthAndRecordContracts()
		throws Exception {
		var analysis = privateAggregateAnalysis();
		ExactPhysicalModel lazy = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel eager =
			ExactPhysicalModel.buildWithEagerAlternativeSignaturesForTest(analysis);
		Assert.assertEquals(domainSignatures(eager), domainSignatures(lazy));
		Assert.assertEquals(factorTruth(eager), factorTruth(lazy));
		for(int domain = 0; domain < lazy.domains().size(); domain++)
			for(int value = 0; value < lazy.domains().get(domain).alternatives().size(); value++) {
				var actual = lazy.domains().get(domain).alternatives().get(value);
				var legacy = eager.domains().get(domain).alternatives().get(value);
				Assert.assertArrayEquals(legacy.signature().getBytes(StandardCharsets.UTF_8),
					actual.signature().getBytes(StandardCharsets.UTF_8));
				Assert.assertEquals(
					ExactPhysicalCostModel.physicalAlternativeSignatureFingerprintForTest(legacy),
					ExactPhysicalCostModel.physicalAlternativeSignatureFingerprintForTest(actual));
				Assert.assertEquals(legacy, actual);
				Assert.assertEquals(legacy.hashCode(), actual.hashCode());
				Assert.assertEquals(legacy.toString(), actual.toString());
				var copy = new ExactPhysicalModel.Alternative(actual.decision(), actual.state(),
					actual.authorityKind(), actual.candidateRule(), actual.candidateEmission(),
					actual.executionRule(), actual.executionEmission(), actual.durableAnchor(),
					actual.relocationAction(), actual.derivedFoutAction(), actual.orderedInputs(),
					actual.inputAuthorities(), actual.realization(), actual.supportClause(),
					actual.compactSupport(), actual.signature());
				Assert.assertEquals(copy, actual);
				Assert.assertEquals(expectedAlternativeHash(actual), actual.hashCode());
				Assert.assertEquals(expectedAlternativeToString(actual), actual.toString());
				List<String> chunks = new java.util.ArrayList<>();
				actual.appendSignature(chunks::add);
				Assert.assertEquals(actual.signature(), String.join("", chunks));
			}
		var sample = lazy.domains().get(0).alternatives().get(0);
		IllegalArgumentException invalid = Assert.assertThrows(IllegalArgumentException.class, () ->
			new ExactPhysicalModel.Alternative(sample.decision(), sample.state(), sample.authorityKind(),
				sample.candidateRule(), sample.candidateEmission(), sample.executionRule(),
				sample.executionEmission(), sample.durableAnchor(), sample.relocationAction(),
				sample.derivedFoutAction(), sample.orderedInputs(), sample.inputAuthorities(),
				sample.realization(), sample.supportClause(), " \t"));
		Assert.assertEquals("EXACT_PHYSICAL_ALTERNATIVE_SIGNATURE_INVALID", invalid.getMessage());
		IllegalArgumentException nullSignature = Assert.assertThrows(IllegalArgumentException.class, () ->
			new ExactPhysicalModel.Alternative(sample.decision(), sample.state(), sample.authorityKind(),
				sample.candidateRule(), sample.candidateEmission(), sample.executionRule(),
				sample.executionEmission(), sample.durableAnchor(), sample.relocationAction(),
				sample.derivedFoutAction(), sample.orderedInputs(), sample.inputAuthorities(),
				sample.realization(), sample.supportClause(), (String) null));
		Assert.assertEquals("EXACT_PHYSICAL_ALTERNATIVE_SIGNATURE_INVALID",
			nullSignature.getMessage());
		NullPointerException invalidDecisionFirst = Assert.assertThrows(NullPointerException.class, () ->
			new ExactPhysicalModel.Alternative(null, sample.state(), sample.authorityKind(),
				sample.candidateRule(), sample.candidateEmission(), sample.executionRule(),
				sample.executionEmission(), sample.durableAnchor(), sample.relocationAction(),
				sample.derivedFoutAction(), sample.orderedInputs(), sample.inputAuthorities(),
				sample.realization(), sample.supportClause(), (String) null));
		Assert.assertEquals("decision", invalidDecisionFirst.getMessage());
		Assert.assertThrows(NullPointerException.class, () ->
			new ExactPhysicalModel.Alternative(sample.decision(), sample.state(), sample.authorityKind(),
				sample.candidateRule(), sample.candidateEmission(), sample.executionRule(),
				sample.executionEmission(), sample.durableAnchor(), sample.relocationAction(),
				sample.derivedFoutAction(), null, sample.inputAuthorities(), sample.realization(),
				sample.supportClause(), (String) null));
		var duplicate = new ExactPhysicalModel.Alternative(sample.decision(), sample.state(),
			sample.authorityKind(), sample.candidateRule(), sample.candidateEmission(),
			sample.executionRule(), sample.executionEmission(), sample.durableAnchor(),
			sample.relocationAction(), sample.derivedFoutAction(), sample.orderedInputs(),
			sample.inputAuthorities(), sample.realization(), sample.supportClause(), sample.signature());
		List<ExactPhysicalModel.Alternative> duplicates = new java.util.ArrayList<>(
			List.of(sample, duplicate));
		List<ExactPhysicalModel.Alternative> deduplicated =
			ExactPhysicalModel.sortAndDeduplicateAlternatives(duplicates);
		Assert.assertEquals(1, deduplicated.size());
		Assert.assertSame("stable lexical sort must retain the first generated duplicate",
			sample, deduplicated.get(0));
	}

	@Test
	public void relocationExecutionAndInputAuthoritySignaturesReuseExactSegments()
		throws Exception {
		var analysis = privateAggregateAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel.Alternative execution = model.domains().stream()
			.flatMap(domain -> domain.alternatives().stream())
			.filter(ExactPhysicalModel.Alternative::captured)
			.filter(alternative -> alternative.inputAuthorities().stream().anyMatch(authority ->
				authority.sourceDecision() != null || authority.relocationAction() != null))
			.findFirst().orElseThrow(() -> new AssertionError(
				"fixture must expose a captured input-authority signature"));
		var action = analysis.graph().relocationActions().stream().findFirst().orElseThrow(() ->
			new AssertionError("fixture must expose a relocation action"));
		var node = analysis.graph().node(execution.decision()).orElseThrow();
		RelocationSignaturePair pair = relocationSignaturePair(node, action, execution);
		Assert.assertArrayEquals(pair.eager().signature().getBytes(StandardCharsets.UTF_8),
			pair.lazy().signature().getBytes(StandardCharsets.UTF_8));
		Assert.assertEquals(
			ExactPhysicalCostModel.physicalAlternativeSignatureFingerprintForTest(pair.eager()),
			ExactPhysicalCostModel.physicalAlternativeSignatureFingerprintForTest(pair.lazy()));
		Assert.assertTrue(sameAuthorityIdentities(pair.lazy().inputAuthorities(),
			execution.inputAuthorities()));
		Assert.assertTrue("relocation/captured signatures must share canonical child text",
			shareCanonicalDescendant(pair.lazy(), pair.captured()));
		Assert.assertNotNull("input authority must have a segmented normalized representation",
			pair.authorityText());
		Assert.assertEquals(pair.segmentedAuthority().signature(),
			pair.authorityText().materialize());
		Assert.assertTrue("input authority must share nested source/action text",
			hasCanonicalChild(pair.authorityText()));

		SignatureRepresentation eagerRepresentation = signatureRepresentation(List.of(pair.eager()));
		SignatureRepresentation lazyRepresentation = signatureRepresentation(List.of(pair.lazy()));
		Assert.assertTrue("segmented relocation signatures must retain fewer literal characters: eager="
			+ eagerRepresentation + ",lazy=" + lazyRepresentation,
			lazyRepresentation.uniqueLiteralCharacters()
				< eagerRepresentation.uniqueLiteralCharacters());
		System.out.println("EXACT_SIGNATURE_SHARING_EVIDENCE|eagerRelocations="
			+ eagerRepresentation + "|lazyRelocations=" + lazyRepresentation);
	}

	private static RelocationSignaturePair relocationSignaturePair(
		org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node node,
		org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction action,
		ExactPhysicalModel.Alternative execution) throws Exception {
		var method = java.util.Arrays.stream(ExactPhysicalModel.class.getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals("nonCandidate"))
			.filter(candidate -> candidate.getParameterCount() == 11)
			.findFirst()
			.orElseThrow();
		method.setAccessible(true);
		var candidate = java.util.Arrays.stream(ExactPhysicalModel.class.getDeclaredMethods())
			.filter(candidateMethod -> candidateMethod.getName().equals("candidate"))
			.filter(candidateMethod -> candidateMethod.getParameterCount() == 9)
			.findFirst().orElseThrow();
		candidate.setAccessible(true);
		Class<?> contextClass = candidate.getParameterTypes()[candidate.getParameterCount() - 1];
		var constructor = contextClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object context = constructor.newInstance();
		ExactPhysicalModel.InputAuthority segmentedAuthority = execution.inputAuthorities().stream()
			.filter(authority -> authority.sourceDecision() != null
				|| authority.relocationAction() != null).findFirst().orElseThrow();
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText authorityText = null;
		try {
			var authorityMethod = contextClass.getDeclaredMethod("inputAuthority",
				ExactPhysicalModel.InputAuthority.class);
			authorityMethod.setAccessible(true);
			authorityText = (org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText)
				authorityMethod.invoke(context, segmentedAuthority);
		}
		catch(NoSuchMethodException ignored) {
			// The frozen V34 implementation is the intentional RED oracle.
		}
		ExactPhysicalModel.Alternative captured = (ExactPhysicalModel.Alternative) candidate.invoke(null,
			node, execution.state(), execution.candidateRule(), execution.candidateEmission(),
			execution.realization(), execution.supportClause(), execution.derivedFoutAction(),
			execution.inputAuthorities(), context);
		List<Object> arguments = new java.util.ArrayList<>(List.of(node, execution.state(),
			ExactPhysicalModel.AuthorityKind.RELOCATION_SOURCE, action.key().durableAnchor(), action,
			execution.candidateRule(), execution.candidateEmission(), execution.realization(),
			execution.supportClause(), execution.inputAuthorities()));
		ExactPhysicalModel.Alternative eager;
		ExactPhysicalModel.Alternative lazy;
		if(method.getParameterCount() == 11) {
			arguments.add(null);
			eager = (ExactPhysicalModel.Alternative) method.invoke(null, arguments.toArray());
			arguments.set(10, context);
			lazy = (ExactPhysicalModel.Alternative) method.invoke(null, arguments.toArray());
		}
		else {
			eager = (ExactPhysicalModel.Alternative) method.invoke(null, arguments.toArray());
			lazy = (ExactPhysicalModel.Alternative) method.invoke(null, arguments.toArray());
		}
		return new RelocationSignaturePair(eager, lazy, captured,
			segmentedAuthority, authorityText);
	}

	private record RelocationSignaturePair(ExactPhysicalModel.Alternative eager,
		ExactPhysicalModel.Alternative lazy,
		ExactPhysicalModel.Alternative captured,
		ExactPhysicalModel.InputAuthority segmentedAuthority,
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText authorityText) { }

	private static boolean sameAuthorityIdentities(
		List<ExactPhysicalModel.InputAuthority> left,
		List<ExactPhysicalModel.InputAuthority> right) {
		if(left.size() != right.size())
			return false;
		for(int index = 0; index < left.size(); index++)
			if(left.get(index) != right.get(index))
				return false;
		return true;
	}

	private static SignatureRepresentation signatureRepresentation(
		List<ExactPhysicalModel.Alternative> alternatives)
		throws Exception {
		var textField = org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText.class
			.getDeclaredField("text");
		textField.setAccessible(true);
		var piecesField = textField.getType().getDeclaredField("pieces");
		piecesField.setAccessible(true);
		IdentityHashMap<Object,Boolean> canonicalTexts = new IdentityHashMap<>();
		IdentityHashMap<String,Boolean> literals = new IdentityHashMap<>();
		java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
		for(var alternative : alternatives)
			pending.addLast(textField.get(alternative.normalizedSignature()));
		long characters = 0L;
		long utf8Bytes = 0L;
		int references = 0;
		while(!pending.isEmpty()) {
			Object text = pending.removeLast();
			if(canonicalTexts.put(text, Boolean.TRUE) != null)
				continue;
			for(Object piece : (Object[]) piecesField.get(text)) {
				if(piece instanceof String literal) {
					references++;
					if(literals.put(literal, Boolean.TRUE) == null) {
						characters += literal.length();
						utf8Bytes += literal.getBytes(StandardCharsets.UTF_8).length;
					}
				}
				else
					pending.addLast(piece);
			}
		}
		return new SignatureRepresentation(characters, utf8Bytes, literals.size(),
			canonicalTexts.size(), references);
	}

	private static boolean shareCanonicalDescendant(ExactPhysicalModel.Alternative left,
		ExactPhysicalModel.Alternative right) throws ReflectiveOperationException {
		Set<Object> leftDescendants = canonicalDescendants(left, false);
		return canonicalDescendants(right, false).stream().anyMatch(leftDescendants::contains);
	}

	private static Set<Object> canonicalDescendants(ExactPhysicalModel.Alternative alternative,
		boolean includeRoot) throws ReflectiveOperationException {
		var textField = org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText.class
			.getDeclaredField("text");
		textField.setAccessible(true);
		var piecesField = textField.getType().getDeclaredField("pieces");
		piecesField.setAccessible(true);
		Object root = textField.get(alternative.normalizedSignature());
		Set<Object> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
		pending.add(root);
		while(!pending.isEmpty()) {
			Object text = pending.removeLast();
			if(!result.add(text))
				continue;
			for(Object piece : (Object[]) piecesField.get(text))
				if(!(piece instanceof String))
					pending.addLast(piece);
		}
		if(!includeRoot)
			result.remove(root);
		return result;
	}

	private static boolean hasCanonicalChild(
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText value) {
		try {
			var textField = org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText.class
				.getDeclaredField("text");
			textField.setAccessible(true);
			var piecesField = textField.getType().getDeclaredField("pieces");
			piecesField.setAccessible(true);
			return java.util.Arrays.stream((Object[]) piecesField.get(textField.get(value)))
				.anyMatch(piece -> !(piece instanceof String));
		}
		catch(ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

	private record SignatureRepresentation(long uniqueLiteralCharacters,
		long uniqueLiteralUtf8Bytes, int uniqueLiterals, int canonicalTexts,
		int literalReferences) { }

	@Test
	public void lazyRegionalStateKeysPreserveLegacySeedRepairAndAssignmentTrace() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(privateAggregateAnalysis());
		var surface = ExactPhysicalCostModel.physicalCostSurface(model.analysis(), model);
		var legacy = LocalPhysicalOptimizer.regionalSeedForTest(model, surface, true);
		var lazy = LocalPhysicalOptimizer.regionalSeedForTest(model, surface, false);

		Assert.assertEquals("regional seed objective changed",
			Double.doubleToRawLongBits(legacy.objective()),
			Double.doubleToRawLongBits(lazy.objective()));
		Assert.assertEquals("regional seed assignment changed",
			legacy.assignmentInVariableOrder(), lazy.assignmentInVariableOrder());
		Assert.assertEquals("regional seed/repair trace changed",
			deterministicSeedTrace(legacy.statistics()),
			deterministicSeedTrace(lazy.statistics()));
	}

	private static List<Long> deterministicSeedTrace(LocalCategoricalOptimizer.Statistics value) {
		return List.of(value.rawLocalAlternatives(), value.retainedLocalStates(),
			value.prunedLocalRepresentatives(), (long) value.initialHardViolations(),
			(long) value.finalHardViolations(), (long) value.conflictBlocksSolved(),
			(long) value.conflictBlockExpansions(), (long) value.localBlocks(),
			(long) value.localBlockImprovements(), (long) value.localBlockRevisits(),
			(long) value.factorizedBlockCompilations(), (long) value.factorizedBlockSolves(),
			(long) value.factorwiseMinimumSkips(), value.factorwiseMinimumAssignments(),
			(long) value.maximumBlockVariables(), value.maximumBlockAssignments(),
			value.blockAssignments());
	}

	private static int expectedAlternativeHash(ExactPhysicalModel.Alternative value) {
		int hash = java.util.Objects.hashCode(value.decision());
		hash = 31 * hash + java.util.Objects.hashCode(value.state());
		hash = 31 * hash + java.util.Objects.hashCode(value.authorityKind());
		hash = 31 * hash + java.util.Objects.hashCode(value.candidateRule());
		hash = 31 * hash + java.util.Objects.hashCode(value.candidateEmission());
		hash = 31 * hash + java.util.Objects.hashCode(value.executionRule());
		hash = 31 * hash + java.util.Objects.hashCode(value.executionEmission());
		hash = 31 * hash + java.util.Objects.hashCode(value.durableAnchor());
		hash = 31 * hash + java.util.Objects.hashCode(value.relocationAction());
		hash = 31 * hash + java.util.Objects.hashCode(value.derivedFoutAction());
		hash = 31 * hash + java.util.Objects.hashCode(value.orderedInputs());
		hash = 31 * hash + java.util.Objects.hashCode(value.inputAuthorities());
		hash = 31 * hash + java.util.Objects.hashCode(value.realization());
		hash = 31 * hash + java.util.Objects.hashCode(value.supportClause());
		hash = 31 * hash + java.util.Objects.hashCode(value.compactSupport());
		return 31 * hash + value.signature().hashCode();
	}

	private static String expectedAlternativeToString(ExactPhysicalModel.Alternative value) {
		return "Alternative[decision=" + value.decision() + ", state=" + value.state()
			+ ", authorityKind=" + value.authorityKind() + ", candidateRule=" + value.candidateRule()
			+ ", candidateEmission=" + value.candidateEmission() + ", executionRule="
			+ value.executionRule() + ", executionEmission=" + value.executionEmission()
			+ ", durableAnchor=" + value.durableAnchor() + ", relocationAction="
			+ value.relocationAction() + ", derivedFoutAction=" + value.derivedFoutAction()
			+ ", orderedInputs=" + value.orderedInputs() + ", inputAuthorities="
			+ value.inputAuthorities() + ", realization=" + value.realization()
			+ ", supportClause=" + value.supportClause() + ", compactSupport="
			+ (value.compactSupport() == null ? "-" : value.compactSupport().logicalClauseCount())
			+ ", signature=" + value.signature() + "]";
	}

	@Test
	public void preparedEvaluationViewIsIsolatedAcrossThreads() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(privateAggregateAnalysis());
		String expected = factorTruth(model);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var left = executor.submit(() -> factorTruth(model));
			var right = executor.submit(() -> factorTruth(model));
			Assert.assertEquals(expected, left.get());
			Assert.assertEquals(expected, right.get());
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void privacyPruningRemovesOnlyAlwaysIllegalRelocationAuthoritiesAndRetainsDirectFout()
		throws Exception {
		var analysis = privateAggregateAnalysis();
		ExactPhysicalModel optimized = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel legacy =
			ExactPhysicalModel.buildWithLegacyInputAuthorityProductsForTest(analysis);
		var privacy = RelocationSelections.relocationPrivacyIndex(
			analysis, analysis.graph(), analysis.graph().relocationActions());

		Map<String,ExactPhysicalModel.Alternative> optimizedAlternatives = alternatives(optimized);
		Map<String,ExactPhysicalModel.Alternative> legacyAlternatives = alternatives(legacy);
		Set<DomainValue> unconditionallyRejected = unconditionallyRejectedValues(legacy);
		int removed = 0;
		for(int domainIndex = 0; domainIndex < legacy.domains().size(); domainIndex++) {
			var legacyDomain = legacy.domains().get(domainIndex);
			var optimizedDomain = optimized.domains().get(domainIndex);
			Assert.assertSame(legacyDomain.node().key(), optimizedDomain.node().key());
			List<String> expectedSurvivorOrder = new java.util.ArrayList<>();
			for(int value = 0; value < legacyDomain.alternatives().size(); value++) {
				var alternative = legacyDomain.alternatives().get(value);
				String key = alternativeKey(legacyDomain, alternative);
				boolean permanentlyIllegal = permanentlyIllegal(alternative, privacy);
				if(permanentlyIllegal) {
					removed++;
					Assert.assertFalse("privacy-illegal relocation product survived: " + key,
						optimizedAlternatives.containsKey(key));
					Assert.assertTrue("removed value was not hard-rejected for every completion: " + key,
						unconditionallyRejected.contains(new DomainValue(legacyDomain.variable(), value)));
				}
				else {

					expectedSurvivorOrder.add(alternative.signature());
					Assert.assertTrue("legal/inactive authority product was removed: " + key,
						optimizedAlternatives.containsKey(key));
				}
			}
			Assert.assertEquals("surviving alternative order changed",
				expectedSurvivorOrder,
				optimizedDomain.alternatives().stream().map(
					ExactPhysicalModel.Alternative::signature).toList());
		}
		Assert.assertTrue("fixture must exercise pre-product privacy pruning", removed > 0);
		Assert.assertTrue("optimized alternatives must be a strict subset",
			optimizedAlternatives.size() < legacyAlternatives.size());
		Assert.assertTrue("inactive DIRECT_FOUT for a privacy-bound action must remain available",
			optimizedAlternatives.values().stream().flatMap(alternative ->
				alternative.inputAuthorities().stream()).anyMatch(authority ->
					authority.kind() == ExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT
					&& authority.relocationAction() != null
					&& !privacy.isPrivacySafe(authority.relocationAction(), true)));
		assertSurvivingRawFactorTruth(legacy, optimized);
		System.out.println("EXACT_AUTHORITY_PRUNING_EVIDENCE|legacyAlternatives="
			+ legacyAlternatives.size() + "|optimizedAlternatives=" + optimizedAlternatives.size()
			+ "|removed=" + removed + "|legacyFactorCells=" + factorCells(legacy)
			+ "|optimizedFactorCells=" + factorCells(optimized));
	}

	@Test
	public void structuralPruningPreservesDeterministicRegionalQualityAndReducesInitialSlots()
		throws Exception {
		var analysis = privateAggregateAnalysis();
		ExactPhysicalModel optimized = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel legacy =
			ExactPhysicalModel.buildWithLegacyInputAuthorityProductsForTest(analysis);
		StructuralRun before = structuralRun(legacy);
		StructuralRun after = structuralRun(optimized);
		Assert.assertEquals("canonical objective changed",
			before.canonicalObjectiveBits(), after.canonicalObjectiveBits());
		Assert.assertEquals("selected semantic alternatives changed",
			before.selectedSignatures(), after.selectedSignatures());
		Assert.assertEquals("regional stop policy changed", before.stopReason(), after.stopReason());
		Assert.assertEquals("regional lower bound changed", before.lowerBits(), after.lowerBits());
		Assert.assertEquals("regional upper bound changed", before.upperBits(), after.upperBits());
		Assert.assertTrue("structural pruning must reduce the exact initial cover",
			after.initialSlots() < before.initialSlots());
		Assert.assertTrue("optimized initial cover must remain below its configured cap",
			after.initialSlots() <= before.retainedSlotCap());
	}

	@Test
	public void solverObservationEncodingsExactlyProjectCanonicalTruth() throws Exception {
		ExactPhysicalModel model = ExactPhysicalModel.build(logregAnalysis());
		var statistics = model.hardFactorizationStatistics();
		Assert.assertFalse("fixture must exercise solver-only hard factorization: " + statistics,
			model.hardFactorEncodings().isEmpty());
		Assert.assertEquals(model.hardFactorEncodings().size(), statistics.factorizedFactors());
		Assert.assertTrue("encoded hard cells must be a strict reduction",
			statistics.encodedCells() < statistics.canonicalCells());
		Assert.assertTrue("fixture must exercise input-authority factorization",
			statistics.inputAuthorityFactors() > 0);
		System.out.println("EXACT_HARD_FACTORIZATION_EVIDENCE|" + statistics);
		int exhaustivelyChecked = 0;
		for(var encoding : model.hardFactorEncodings()) {
			Assert.assertSame(encoding.canonicalFactor(),
				model.hardFactors().get(encoding.canonicalOrdinal()));
			var canonical = encoding.canonicalFactor();
			var decomposition = encoding.decomposition();
			// This oracle enumerates one category per canonical input axis. Pool-proof
			// circuits have a different auxiliary graph, tested by exhaustive existential
			// projection and forced optimizer parity in DerivedFoutAnchorPartialHardTest.
			if(!decomposition.isObservationStar())
				continue;
			List<int[]> observations = decomposition.observations();
			int[][] representatives = observationRepresentatives(decomposition);
			int categoryCells = decomposition.auxiliaryVariables().stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			int[] categories = new int[canonical.scope().size()];
			int[] values = new int[canonical.scope().size()];
			for(int categoryCell = 0; categoryCell < categoryCells; categoryCell++) {
				decode(decomposition.solverFactors().get(decomposition.solverFactors().size() - 1),
					categoryCell, categories);
				for(int position = 0; position < values.length; position++)
					values[position] = representatives[position][categories[position]];
				Map<ExactCategoricalSolver.Variable,Integer> assignment = new IdentityHashMap<>();
				for(int position = 0; position < canonical.scope().size(); position++) {
					assignment.put(canonical.scope().get(position), values[position]);
					assignment.put(decomposition.auxiliaryVariables().get(position), categories[position]);
				}
				double encodedCost = 0.0;
				for(var factor : decomposition.solverFactors())
					encodedCost += factor.cost(factor.scope().stream()
						.mapToInt(assignment::get).toArray());
				Assert.assertEquals("encoded relation changed at canonical factor="
					+ encoding.canonicalOrdinal() + ",categoryCell=" + categoryCell,
					Double.doubleToRawLongBits(canonical.cost(values)),
					Double.doubleToRawLongBits(encodedCost));
				for(int position = 0; position < values.length; position++) {
					int representative = values[position];
					for(int value = 0; value < canonical.scope().get(position).domainSize(); value++)
						if(observations.get(position)[value] == categories[position]) {
							values[position] = value;
							Assert.assertEquals("observation merged different truth at canonical factor="
								+ encoding.canonicalOrdinal() + ",position=" + position,
								Double.doubleToRawLongBits(encodedCost),
								Double.doubleToRawLongBits(canonical.cost(values)));
						}
					values[position] = representative;
				}
			}
			if(factorCellCount(canonical) <= 100_000) {
				assertEveryCanonicalCellProjects(canonical, decomposition, observations,
					encoding.canonicalOrdinal());
				exhaustivelyChecked++;
			}
		}
		Assert.assertTrue("fixture must include a bounded full-Cartesian encoding",
			exhaustivelyChecked > 0);
	}

	private static void assertEveryCanonicalCellProjects(ExactCategoricalSolver.Factor canonical,
		ExactHardFactorObservationDecomposition.Result decomposition, List<int[]> observations,
		int ordinal) {
		int[] values = new int[canonical.scope().size()];
		for(int cell = 0; cell < factorCellCount(canonical); cell++) {
			decode(canonical, cell, values);
			Map<ExactCategoricalSolver.Variable,Integer> assignment = new IdentityHashMap<>();
			for(int position = 0; position < values.length; position++) {
				assignment.put(canonical.scope().get(position), values[position]);
				assignment.put(decomposition.auxiliaryVariables().get(position),
					observations.get(position)[values[position]]);
			}
			double encoded = 0.0;
			for(var factor : decomposition.solverFactors())
				encoded += factor.cost(factor.scope().stream().mapToInt(assignment::get).toArray());
			Assert.assertEquals("full Cartesian relation changed at factor=" + ordinal + ",cell=" + cell,
				Double.doubleToRawLongBits(canonical.cost(values)),
				Double.doubleToRawLongBits(encoded));
		}
	}

	private static int[][] observationRepresentatives(
		ExactHardFactorObservationDecomposition.Result decomposition) {
		int[][] result = new int[decomposition.observations().size()][];
		for(int position = 0; position < result.length; position++) {
			result[position] = new int[decomposition.auxiliaryVariables().get(position).domainSize()];
			java.util.Arrays.fill(result[position], -1);
			int[] observations = decomposition.observations().get(position);
			for(int value = 0; value < observations.length; value++)
				if(result[position][observations[value]] < 0)
					result[position][observations[value]] = value;
		}
		return result;
	}

	private static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis privateAggregateAnalysis()
		throws Exception {
		var program = ProductionShadowFixtureFactory.compile("B-11");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	static org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis logregAnalysis()
		throws Exception {
		String script = "X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(25000,2100),list(25000,0),list(50000,2100)));\n"
			+ "Y=federated(addresses=list(\"localhost:1234/Y1\",\"localhost:1235/Y2\"),"
			+ "ranges=list(list(0,0),list(25000,1),list(25000,0),list(50000,1)));\n"
			+ "Y=(Y<0)+1;\nB=multiLogReg(X=X,Y=Y,verbose=FALSE,maxi=30,maxii=5,"
			+ "tol=1e-9,icpt=0,numclasses=2,numrows=50000,numcols=2100);\n"
			+ "write(B,\"out\",format=\"csv\");\n";
		var program = org.apache.sysds.parser.ParserFactory.createParser().parse(
			org.apache.sysds.api.DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		var translator = new org.apache.sysds.parser.DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static StructuralRun structuralRun(ExactPhysicalModel model) {
		var surface = ExactPhysicalCostModel.physicalCostSurface(model.analysis(), model);
		var optimized = ExactPhysicalOptimizer.optimize(model, surface,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		var problem = RegionalSearchProblem.physical(model, surface, null);
		var root = problem.reducedRoot(ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		long initialSlots = root.factors().stream().mapToLong(factor ->
			factor.scope().stream().mapToLong(ExactCategoricalSolver.Variable::domainSize)
				.reduce(1L, Math::multiplyExact)).sum();
		var options = new IncrementalRegionalOptimizer.Options(
			0.0, 10_000_000L, 10_000_000L, 0L, 16, true);
		var regional = IncrementalRegionalOptimizer.optimize(problem, root,
			optimized.solverResult().assignmentInVariableOrder(),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS, options, ignored -> { });
		var selection = model.physicalSelection(new ExactCategoricalSolver.Result(
			regional.upper(), regional.assignment(), optimized.solverResult().statistics()));
		return new StructuralRun(Double.doubleToRawLongBits(regional.upper()),
			selection.alternativesInDecisionOrder().stream()
				.map(ExactPhysicalModel.Alternative::signature).toList(),
			regional.stopReason(), Double.doubleToRawLongBits(regional.lower()),
			Double.doubleToRawLongBits(regional.upper()), initialSlots,
			options.maximumRetainedSlots());
	}

	private record StructuralRun(long canonicalObjectiveBits, List<String> selectedSignatures,
		String stopReason, long lowerBits, long upperBits, long initialSlots,
		long retainedSlotCap) { }

	private static Map<String,ExactPhysicalModel.Alternative> alternatives(ExactPhysicalModel model) {
		Map<String,ExactPhysicalModel.Alternative> result = new HashMap<>();
		for(var domain : model.domains())
			for(var alternative : domain.alternatives())
				Assert.assertNull("duplicate alternative signature", result.put(
					alternativeKey(domain, alternative), alternative));
		return result;
	}

	private static String alternativeKey(ExactPhysicalModel.DecisionDomain domain,
		ExactPhysicalModel.Alternative alternative) {
		return domain.node().key().normalizedSignature() + '|' + alternative.signature();
	}

	private static boolean permanentlyIllegal(ExactPhysicalModel.Alternative alternative,
		RelocationSelections.RelocationPrivacyIndex privacy) {
		return alternative.inputAuthorities().stream().anyMatch(authority ->
			authority.kind() == ExactPhysicalModel.InputAuthorityKind.RELOCATION
				&& !privacy.isPrivacySafe(authority.relocationAction(), true));
	}

	private static void assertSurvivingRawFactorTruth(ExactPhysicalModel legacy,
		ExactPhysicalModel optimized) {
		Assert.assertEquals("factor count changed", legacy.hardFactors().size(),
			optimized.hardFactors().size());
		Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain> legacyDomains =
			domainsByVariable(legacy);
		Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain> optimizedDomains =
			domainsByVariable(optimized);
		for(int factorIndex = 0; factorIndex < optimized.hardFactors().size(); factorIndex++) {
			var oldFactor = legacy.hardFactors().get(factorIndex);
			var newFactor = optimized.hardFactors().get(factorIndex);
			Assert.assertEquals("factor arity changed at " + factorIndex,
				oldFactor.scope().size(), newFactor.scope().size());
			int[][] newToOld = new int[newFactor.scope().size()][];
			for(int position = 0; position < newFactor.scope().size(); position++) {
				var oldDomain = legacyDomains.get(oldFactor.scope().get(position));
				var newDomain = optimizedDomains.get(newFactor.scope().get(position));
				Assert.assertSame("factor decision/order changed at " + factorIndex,
					oldDomain.node().key(), newDomain.node().key());
				Map<String,Integer> oldOrdinal = new HashMap<>();
				for(int value = 0; value < oldDomain.alternatives().size(); value++)
					oldOrdinal.put(oldDomain.alternatives().get(value).signature(), value);
				newToOld[position] = new int[newDomain.alternatives().size()];
				for(int value = 0; value < newDomain.alternatives().size(); value++)
					newToOld[position][value] = oldOrdinal.get(
						newDomain.alternatives().get(value).signature());
			}
			int cells = factorCellCount(newFactor);
			int[] newValues = new int[newFactor.scope().size()];
			int[] oldValues = new int[oldFactor.scope().size()];
			for(int cell = 0; cell < cells; cell++) {
				decode(newFactor, cell, newValues);
				for(int position = 0; position < newValues.length; position++)
					oldValues[position] = newToOld[position][newValues[position]];
				Assert.assertEquals("raw hard truth changed at factor=" + factorIndex + ",cell=" + cell,
					Double.doubleToRawLongBits(oldFactor.cost(oldValues)),
					Double.doubleToRawLongBits(newFactor.cost(newValues)));
			}
		}
	}

	private static Set<DomainValue> unconditionallyRejectedValues(ExactPhysicalModel model) {
		Set<DomainValue> rejected = new HashSet<>();
		for(var factor : model.hardFactors())
			for(int position = 0; position < factor.scope().size(); position++)
				for(int value = 0; value < factor.scope().get(position).domainSize(); value++) {
					boolean alwaysInfinite = true;
					int[] values = new int[factor.scope().size()];
					for(int cell = 0; cell < factorCellCount(factor) && alwaysInfinite; cell++) {
						decode(factor, cell, values);
						if(values[position] == value && !Double.isInfinite(factor.cost(values)))
							alwaysInfinite = false;
					}
					if(alwaysInfinite)
						rejected.add(new DomainValue(factor.scope().get(position), value));
				}
		return rejected;
	}

	private static Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain>
		domainsByVariable(ExactPhysicalModel model) {
		Map<ExactCategoricalSolver.Variable,ExactPhysicalModel.DecisionDomain> result =
			new IdentityHashMap<>();
		for(var domain : model.domains())
			result.put(domain.variable(), domain);
		return result;
	}

	private static long factorCells(ExactPhysicalModel model) {
		return model.hardFactors().stream().mapToLong(ExactInputAuthorityOptimizationTest::factorCellCount).sum();
	}

	private static int factorCellCount(ExactCategoricalSolver.Factor factor) {
		return factor.scope().stream().mapToInt(
			ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
	}

	private static void decode(ExactCategoricalSolver.Factor factor, int cell, int[] values) {
		int remainder = cell;
		for(int position = values.length - 1; position >= 0; position--) {
			int radix = factor.scope().get(position).domainSize();
			values[position] = remainder % radix;
			remainder /= radix;
		}
	}

	private record DomainValue(ExactCategoricalSolver.Variable variable, int value) { }

	private static String domainSignatures(ExactPhysicalModel model) {
		return model.domains().stream().map(domain -> domain.node().key().normalizedSignature() + "="
			+ domain.alternatives().stream().map(ExactPhysicalModel.Alternative::signature).toList())
			.toList().toString();
	}

	private static String factorTruth(ExactPhysicalModel model) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		Map<ExactCategoricalSolver.Variable,Integer> positions = new java.util.IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		for(var factor : model.hardFactors()) {
			update(digest, factor.scope().stream().map(variable ->
				positions.get(variable) + ":" + variable.domainSize()).toList().toString());
			int cells = factor.scope().stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
			int[] values = new int[factor.scope().size()];
			for(int cell = 0; cell < cells; cell++) {
				int remainder = cell;
				for(int position = values.length - 1; position >= 0; position--) {
					int radix = factor.scope().get(position).domainSize();
					values[position] = remainder % radix;
					remainder /= radix;
				}
				update(digest, Long.toUnsignedString(Double.doubleToRawLongBits(factor.cost(values))));
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
	}
}
