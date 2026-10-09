/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeSingleAxisProductUnionTest {
	@Test
	public void oneAxisUnionRetainsFirstClauseAuthorityInCanonicalOrder() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey leftOwner = key("left");
		CompiledHopKey rightOwner = key("right");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var sharedLeft = direct(0, "shared-left", leftOwner);
		var leftA = direct(1, "a", rightOwner);
		var leftLong = direct(1, "a-very-long-retained-lineage", rightOwner);
		var rightMedium = direct(1, "medium-new-lineage", rightOwner);
		List<List<CandidateRealizationInputBinding>> retainedAxes = List.of(
			List.of(sharedLeft), sorted(leftA, leftLong));
		List<List<CandidateRealizationInputBinding>> newAxes = List.of(
			List.of(directLike(sharedLeft)), sorted(directLike(leftLong), rightMedium));
		NativeContinuitySupportClauses retained = relation(owner, seed, output, retainedAxes);
		NativeContinuitySupportClauses added = relation(owner, seed, output, newAxes);
		CandidateRealizationSupportClause retainedLong = retained.get(
			retained.product().ordinalOfExactAuthorityBindings(List.of(sharedLeft, leftLong)));
		CandidateRealizationSupportClause addedMedium = added.get(
			added.product().ordinalOfExactAuthorityBindings(List.of(directLike(sharedLeft), rightMedium)));

		NativeContinuitySupportClauses union = retained.oneAxisUnion(added).orElseThrow();
		Assert.assertEquals(3, union.size());
		List<CandidateRealizationSupportClause> explicit = explicit(owner, seed, output,
			List.of(List.of(sharedLeft), sorted(leftA, leftLong, rightMedium)));
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				union.get(ordinal).normalizedSignature());
			Assert.assertEquals(ordinal,
				union.product().ordinalOfBindings(union.product().bindingsAt(ordinal)));
		}
		int unionLong = union.product().ordinalOfExactAuthorityBindings(List.of(sharedLeft, leftLong));
		Assert.assertSame(retainedLong, union.get(unionLong));
		Assert.assertSame(leftLong.source(), union.get(unionLong).inputBindings().get(1).source());
		int unionMedium = union.product().ordinalOfExactAuthorityBindings(List.of(sharedLeft, rightMedium));
		Assert.assertTrue("fixture keeps the added-only member at a nonzero distinct ordinal",
			unionMedium > 0 && unionMedium != unionLong);
		Assert.assertSame(addedMedium, union.get(unionMedium));
		Assert.assertSame(rightMedium.source(), union.get(unionMedium).inputBindings().get(1).source());
	}

	@Test
	public void equivalentNativeUnionsCompareWithoutMaterializingMembers() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", source);
		var b = direct(0, "b", source);
		var c = direct(0, "c", source);
		NativeContinuitySupportClauses first = relation(owner, seed, output, List.of(sorted(a, b)))
			.oneAxisUnion(relation(owner, seed, output,
				List.of(sorted(directLike(b), c)))).orElseThrow();
		NativeContinuitySupportClauses second = relation(owner, seed, output,
			List.of(sorted(directLike(a), directLike(c))))
			.oneAxisUnion(relation(owner, seed, output, List.of(List.of(directLike(b))))).orElseThrow();
		Assert.assertEquals(first, second);
		PlacementRealizationKey key = PlacementRealizationKey.nativeLineage(emission(), "same-output");
		CandidateEmissionFact firstFact = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(key, first)));
		CandidateEmissionFact secondFact = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(key, second)));
		Assert.assertEquals(firstFact, secondFact);
		Assert.assertEquals(0, first.materializedHandleCount());
		Assert.assertEquals(0, second.materializedHandleCount());
	}

	@Test
	public void candidateEmissionMergeKeepsNativeUnionLazyAndRestrictionKeepsDonors() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", source);
		var b = direct(0, "b", source);
		var c = direct(0, "c", source);
		NativeContinuitySupportClauses retained = relation(owner, seed, output, List.of(sorted(a, b)));
		NativeContinuitySupportClauses added = relation(owner, seed, output,
			List.of(sorted(directLike(b), c)));
		CandidateRealizationSupportClause retainedB = retained.get(
			retained.product().ordinalOfExactAuthorityBindings(List.of(b)));
		PlacementRealizationKey key = PlacementRealizationKey.nativeLineage(emission(), "same-output");
		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			new CandidateEmissionRealization(key, retained),
			new CandidateEmissionRealization(key, added)));
		Assert.assertEquals(1, merged.realizations().size());
		NativeContinuitySupportClauses union = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(3, union.size());
		Assert.assertEquals(2, union.authorityDonorCount());
		Assert.assertEquals(0, added.materializedHandleCount());
		CandidateRealizationSupportClause oldAddedC = added.get(
			added.product().ordinalOfExactAuthorityBindings(List.of(c)));
		NativeContinuitySupportClauses withdrawn = union.restrictBindings(binding ->
			!binding.source().equals(c.source())).orElseThrow();
		Assert.assertEquals(2, withdrawn.size());
		Assert.assertEquals("withdrawal removes the donor scope that contributed only c",
			1, withdrawn.authorityDonorCount());
		Assert.assertEquals(-1, withdrawn.product().ordinalOfExactAuthorityBindings(List.of(c)));
		NativeContinuitySupportClauses readded = relation(owner, seed, output,
			List.of(List.of(directLike(c))));
		CandidateRealizationSupportClause currentAddedC = readded.get(0);
		NativeContinuitySupportClauses restored = withdrawn.oneAxisUnion(readded).orElseThrow();
		CandidateRealizationSupportClause restoredC = restored.get(
			restored.product().ordinalOfExactAuthorityBindings(List.of(c)));
		Assert.assertSame("re-added authority comes from the current revision donor",
			currentAddedC, restoredC);
		Assert.assertNotSame(oldAddedC, restoredC);
		NativeContinuitySupportClauses restricted = union.restrictBindings(binding ->
			binding.source().equals(b.source()) || binding.source().equals(c.source())).orElseThrow();
		Assert.assertEquals(2, restricted.size());
		Assert.assertSame(retainedB, restricted.get(
			restricted.product().ordinalOfExactAuthorityBindings(List.of(b))));
	}

	@Test
	public void fixedPointWithdrawalRestrictsEqualDistinctDonorsAgainstUnionAuthority() {
		CompiledHopKey consumerOwner = key("fixed-point-consumer");
		CompiledHopKey fixedOwner = key("fixed-source");
		CompiledHopKey alternativesOwner = key("alternative-source");
		DurableAnchorKey seed = pool("fixed-point-seed");
		DurableAnchorKey output = pool("fixed-point-output");

		CandidateEmissionRealization xRealization = sourceRealization("x");
		CandidateRuleKey missingKey = new CandidateRuleKey(key("missing-source"), List.of());
		CandidateRealizationReference missing = new CandidateRealizationReference(
			missingKey, sourceRealization("missing").key());
		CandidateRuleKey doomedKey = new CandidateRuleKey(key("doomed-source"),
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization doomedRealization = dependentSourceRealization(
			"doomed", CandidateRealizationInputBinding.direct(0, missing));
		CandidateRuleFact doomedSource = fact(doomedKey,
			new CandidateEmissionFact(emission(), FType.ROW, null, List.of(doomedRealization)));
		CandidateRuleKey alternativesKey = new CandidateRuleKey(alternativesOwner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization aRealization = dependentSourceRealization("a",
			CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(doomedKey, doomedRealization)));
		CandidateEmissionRealization bRealization = sourceRealization("b");
		CandidateEmissionRealization cRealization = sourceRealization("c");
		CandidateRuleFact fixedSource = fact(fixedOwner, List.of(xRealization));
		CandidateRuleFact allAlternatives = fact(alternativesKey,
			new CandidateEmissionFact(emission(), FType.ROW, null,
				List.of(aRealization, bRealization, cRealization)));

		CandidateRealizationInputBinding x = sourceBinding(0, fixedSource, xRealization);
		CandidateRealizationInputBinding a = sourceBinding(1, allAlternatives, aRealization);
		CandidateRealizationInputBinding b = sourceBinding(1, allAlternatives, bRealization);
		CandidateRealizationInputBinding c = sourceBinding(1, allAlternatives, cRealization);
		CandidateRealizationInputBinding donorX = directLike(x);
		CandidateRealizationInputBinding donorB = directLike(b);
		CandidateRealizationInputBinding donorC = directLike(c);
		Assert.assertEquals(x, donorX);
		Assert.assertNotSame(x, donorX);
		Assert.assertEquals(b, donorB);
		Assert.assertNotSame(b, donorB);
		NativeContinuitySupportClauses left = relation(consumerOwner, seed, output,
			List.of(List.of(x), sorted(a, b)));
		NativeContinuitySupportClauses right = relation(consumerOwner, seed, output,
			List.of(List.of(donorX), sorted(donorB, donorC)));
		CandidateRealizationSupportClause leftB = left.get(
			left.product().ordinalOfExactAuthorityBindings(List.of(x, b)));
		CandidateRealizationSupportClause rightC = right.get(
			right.product().ordinalOfExactAuthorityBindings(List.of(donorX, donorC)));
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "fixed-point-output");
		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			new CandidateEmissionRealization(outputKey, left),
			new CandidateEmissionRealization(outputKey, right)));
		NativeContinuitySupportClauses union = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(3, union.size());
		Assert.assertEquals(2, union.authorityDonorCount());
		CandidateRuleFact consumer = fact(new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW))), merged);

		List<CandidateRuleFact> facts =
			List.of(fixedSource, doomedSource, allAlternatives, consumer);
		List<CandidateRuleFact> initial = PlacementSupportRelations.pruneUnsupportedRealizations(facts);
		NativeContinuitySupportClauses initiallyRetained = (NativeContinuitySupportClauses)initial.stream()
			.filter(candidate -> candidate.key().parentOccurrence() == consumerOwner)
			.flatMap(candidate -> candidate.allowedEmissionFacts().stream())
			.flatMap(candidate -> candidate.realizations().stream())
			.filter(candidate -> candidate.key().equals(outputKey)).findFirst().orElseThrow()
			.supportClauses();
		Assert.assertEquals("the initial projection must retain a until its source is queue-deleted",
			3, initiallyRetained.size());
		PlacementSupportRelations.WorklistResult fixedPoint = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(facts, null, List.of(), Map.of());
		Assert.assertTrue("the fixture must reach native restriction through cascading deletion",
			fixedPoint.work().queueVisits() >= 2 && fixedPoint.work().deletedRealizations() >= 2);
		List<CandidateRuleFact> pruned = fixedPoint.facts();
		CandidateRuleFact prunedConsumer = pruned.stream()
			.filter(candidate -> candidate.key().parentOccurrence() == consumerOwner)
			.findFirst().orElseThrow();
		CandidateEmissionRealization prunedRealization = prunedConsumer.allowedEmissionFacts().stream()
			.flatMap(candidate -> candidate.realizations().stream())
			.filter(candidate -> candidate.key().equals(outputKey)).findFirst().orElseThrow();
		NativeContinuitySupportClauses restricted =
			(NativeContinuitySupportClauses)prunedRealization.supportClauses();

		Assert.assertEquals("withdrawing a must retain both independently supported alternatives", 2,
			restricted.size());
		List<CandidateRealizationSupportClause> cold = explicit(consumerOwner, seed, output,
			List.of(List.of(x), sorted(b, c)));
		Assert.assertEquals("fixed-point restriction must preserve full cold canonical support",
			cold.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList(),
			restricted.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList());
		int bOrdinal = restricted.product().ordinalOfExactAuthorityBindings(List.of(x, b));
		int cOrdinal = restricted.product().ordinalOfExactAuthorityBindings(List.of(x, c));
		Assert.assertTrue(bOrdinal >= 0);
		Assert.assertTrue(cOrdinal >= 0);
		Assert.assertSame("the overlapping member keeps the first union donor authority",
			leftB, restricted.get(bOrdinal));
		Assert.assertSame("the added-only member keeps its exact donor authority",
			rightC, restricted.get(cOrdinal));
		Assert.assertSame(b.source(), restricted.get(bOrdinal).inputBindings().get(1).source());
		Assert.assertEquals(c.source(), restricted.get(cOrdinal).inputBindings().get(1).source());
		Assert.assertNotSame("the added donor must keep its equal-but-distinct source reference",
			c.source(), restricted.get(cOrdinal).inputBindings().get(1).source());
		Assert.assertSame(alternativesOwner,
			restricted.get(cOrdinal).inputBindings().get(1).source().rule().parentOccurrence());
	}

	@Test
	public void differentHeadersForeignOwnersAndTwoChangedAxesFallBack() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey foreignOwner = cloneKey(owner);
		CompiledHopKey first = key("first");
		CompiledHopKey second = key("second");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var left = relation(owner, seed, output, List.of(
			List.of(direct(0, "a", first)), List.of(direct(1, "a", second))));
		var twoAxes = relation(owner, seed, output, List.of(
			List.of(direct(0, "b", first)), List.of(direct(1, "b", second))));
		Assert.assertTrue(left.oneAxisUnion(twoAxes).isEmpty());
		Assert.assertTrue(left.oneAxisUnion(relation(foreignOwner, seed, output,
			left.product().axes())).isEmpty());
		Assert.assertTrue(left.oneAxisUnion(relation(owner, pool("other-seed"), output,
			left.product().axes())).isEmpty());
		Assert.assertTrue(left.oneAxisUnion(new NativeContinuitySupportClauses(owner,
			left.product(), pool("other-witness"), true)).isEmpty());
		Assert.assertTrue(left.oneAxisUnion(new NativeContinuitySupportClauses(owner,
			left.product(), output, false)).isEmpty());
		var differentOutputProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, pool("different-proof-output"), true, left.product().axes());
		Assert.assertTrue(left.oneAxisUnion(new NativeContinuitySupportClauses(owner,
			differentOutputProduct, output, true)).isEmpty());
		var dynamicRanges = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, false, left.product().axes());
		Assert.assertTrue(left.oneAxisUnion(new NativeContinuitySupportClauses(owner,
			dynamicRanges, output, true)).isEmpty());
		var foreignSource = relation(owner, seed, output, List.of(
			List.of(direct(0, "a", cloneKey(first))), List.of(directLike(left.product().axes().get(1).get(0)))));
		Assert.assertTrue(left.oneAxisUnion(foreignSource).isEmpty());
	}

	@Test
	public void canonicalRankBudgetAndStagingAuthorityFailClosed() throws Exception {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", source);
		var medium = direct(0, "medium-lineage", source);
		var veryLong = direct(0, "a-very-long-lineage-for-rank-budget", source);
		var leftProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(sorted(a, veryLong)));
		var rightProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(sorted(directLike(veryLong), medium)));
		Assert.assertNotNull(leftProduct);
		Assert.assertNotNull(rightProduct);
		Assert.assertTrue("union reconstruction must fail closed at the canonical-index budget",
			leftProduct.oneAxisUnion(rightProduct, 2, 100).isEmpty());

		NativeContinuitySupportClauses retained = new NativeContinuitySupportClauses(
			owner, leftProduct, output, true);
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "same-output");
		CandidateEmissionRealization retainedRealization =
			new CandidateEmissionRealization(outputKey, retained);
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			emission(), "staging", List.of(), List.of());
		CandidateEmissionFact grounded = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(retainedRealization, staging));
		Method prepare = PlacementRelationClosure.class.getDeclaredMethod(
			"prepareGroundedNativeSupport", CandidateEmissionFact.class);
		prepare.setAccessible(true);
		Object preparation = prepare.invoke(null, grounded);
		int retainedHandlesBeforeAdmission = retained.materializedHandleCount();
		CandidateEmissionRealization candidate = new CandidateEmissionRealization(outputKey,
			new NativeContinuitySupportClauses(owner, rightProduct, output, true));
		Method admission = PlacementRelationClosure.class.getDeclaredMethod(
			"nativeProductAdmission", CandidateEmissionRealization.class,
			preparation.getClass(), boolean.class, boolean.class);
		admission.setAccessible(true);
		Object result = admission.invoke(null, candidate, preparation, true, true);
		Method admitted = result.getClass().getDeclaredMethod("admitted");
		admitted.setAccessible(true);
		Assert.assertFalse("staging authority must keep the exact scalar fallback",
			(boolean)admitted.invoke(result));
		Assert.assertEquals(retainedHandlesBeforeAdmission, retained.materializedHandleCount());
		Assert.assertEquals(0,
			((NativeContinuitySupportClauses)candidate.supportClauses()).materializedHandleCount());
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, output, true);
	}

	private static List<CandidateRealizationInputBinding> sorted(
		CandidateRealizationInputBinding... bindings) {
		return java.util.Arrays.stream(bindings).sorted(PlacementAnalysis.canonicalComparator()).toList();
	}

	private static CandidateRealizationInputBinding directLike(CandidateRealizationInputBinding binding) {
		return CandidateRealizationInputBinding.direct(binding.inputPosition(),
			new CandidateRealizationReference(binding.source().rule(), binding.source().realization()));
	}

	private static List<CandidateRealizationSupportClause> explicit(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		enumerate(owner, seed, output, axes, 0, new ArrayList<>(), clauses);
		clauses.sort(PlacementAnalysis.canonicalComparator());
		return clauses;
	}

	private static void enumerate(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey output, List<List<CandidateRealizationInputBinding>> axes,
		int axis, List<CandidateRealizationInputBinding> selected,
		List<CandidateRealizationSupportClause> clauses) {
		if(axis < axes.size()) {
			for(var option : axes.get(axis)) {
				selected.add(option);
				enumerate(owner, seed, output, axes, axis + 1, selected, clauses);
				selected.remove(selected.size() - 1);
			}
			return;
		}
		List<CandidateRealizationInputBinding> bindings =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(List.copyOf(selected), "union reference");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, bindings).continuityProofKey(owner);
		clauses.add(new CandidateRealizationSupportClause(List.of(proof), bindings, output, true));
	}

	private static CandidateRealizationInputBinding direct(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(rule,
			PlacementRealizationKey.nativeLineage(emission(), "source-" + name));
		return CandidateRealizationInputBinding.direct(position, reference);
	}

	private static CandidateEmissionRealization sourceRealization(String name) {
		return CandidateEmissionRealization.durable(
			emission(), pool("source-" + name), List.of(), List.of());
	}

	private static CandidateEmissionRealization dependentSourceRealization(String name,
		CandidateRealizationInputBinding binding) {
		return new CandidateEmissionRealization(sourceRealization(name).key(),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of(binding))));
	}

	private static CandidateRealizationInputBinding sourceBinding(int position,
		CandidateRuleFact source, CandidateEmissionRealization realization) {
		return CandidateRealizationInputBinding.direct(position,
			CandidateRealizationReference.of(source.key(), realization));
	}

	private static CandidateRuleFact fact(CompiledHopKey owner,
		List<CandidateEmissionRealization> realizations) {
		return fact(new CandidateRuleKey(owner, List.of()),
			new CandidateEmissionFact(emission(), FType.ROW, null, realizations));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key, CandidateEmissionFact emissionFact) {
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(key.orderedInputs().stream()
				.map(CandidateInputState::fType).toList(), ""), List.of(emissionFact), "");
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"native-union", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-union", "main", name, "compiled",
			region, name, name);
	}

	private static CompiledHopKey cloneKey(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
