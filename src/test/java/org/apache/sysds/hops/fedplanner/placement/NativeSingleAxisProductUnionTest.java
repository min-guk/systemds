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
	public void sameKeyExplicitMapCanCertifyAnEquivalentNativeSuffix() throws Exception {
		CompiledHopKey owner = key("explicit-native-reuse-owner");
		CompiledHopKey source = key("explicit-native-reuse-source");
		DurableAnchorKey seed = pool("explicit-native-reuse-seed");
		DurableAnchorKey output = pool("explicit-native-reuse-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(0, "explicit-native-reuse", source)));
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "explicit-native-reuse");
		CandidateEmissionRealization explicitRealization = new CandidateEmissionRealization(
			outputKey, explicit(owner, seed, output, axes));
		CandidateEmissionFact grounded = new CandidateEmissionFact(
			emission(), FType.ROW, null, List.of(explicitRealization));

		Method prepare = PlacementRelationClosure.class.getDeclaredMethod(
			"prepareGroundedNativeSupport", CandidateEmissionFact.class);
		prepare.setAccessible(true);
		Object preparation = prepare.invoke(null, grounded);
		Method retained = preparation.getClass().getDeclaredMethod("retained");
		retained.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateEmissionRealization> scheduled = new ArrayList<>(
			(List<CandidateEmissionRealization>)retained.invoke(preparation));
		scheduled.add(new CandidateEmissionRealization(
			outputKey, relation(owner, seed, output, axes)));
		Method containsExact = preparation.getClass().getDeclaredMethod(
			"containsExact", List.class);
		containsExact.setAccessible(true);
		Assert.assertTrue("same-key explicit clauses must retain the exact emission reuse certificate",
			(boolean)containsExact.invoke(preparation, scheduled));
	}

	@Test
	public void sameKeyExplicitLastMemberCannotCertifyWiderUnreadNativeSuffix() throws Exception {
		CompiledHopKey owner = key("explicit-native-cardinality-owner");
		CompiledHopKey source = key("explicit-native-cardinality-source");
		DurableAnchorKey seed = pool("explicit-native-cardinality-seed");
		DurableAnchorKey output = pool("explicit-native-cardinality-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(sorted(
			direct(0, "a", source), direct(0, "medium-member", source),
			direct(0, "last-member-with-the-longest-lineage", source)));
		NativeContinuitySupportClauses wider = relation(owner, seed, output, axes);
		List<CandidateRealizationInputBinding> lastBindings =
			wider.product().bindingsAt(wider.size() - 1).stream().map(
				NativeSingleAxisProductUnionTest::directLike).toList();
		var lastProof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, lastBindings).continuityProofKey(owner);
		CandidateRealizationSupportClause retainedLast = new CandidateRealizationSupportClause(
			List.of(lastProof), lastBindings, output, true);
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "explicit-native-cardinality");
		CandidateEmissionFact grounded = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(outputKey, List.of(retainedLast))));

		Method prepare = PlacementRelationClosure.class.getDeclaredMethod(
			"prepareGroundedNativeSupport", CandidateEmissionFact.class);
		prepare.setAccessible(true);
		Object preparation = prepare.invoke(null, grounded);
		Method retained = preparation.getClass().getDeclaredMethod("retained");
		retained.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateEmissionRealization> scheduled = new ArrayList<>(
			(List<CandidateEmissionRealization>)retained.invoke(preparation));
		scheduled.add(new CandidateEmissionRealization(outputKey, wider));
		Method containsExact = preparation.getClass().getDeclaredMethod("containsExact", List.class);
		containsExact.setAccessible(true);

		Assert.assertFalse("one retained member cannot certify a wider native suffix",
			(boolean)containsExact.invoke(preparation, scheduled));
		Assert.assertEquals("cardinality rejection must precede native member reads", 0,
			wider.materializedHandleCount());
	}

	@Test
	public void diagnosticsKeepTwoWayNativeUnionsLazyAndCountLogicalDuplicates() {
		CompiledHopKey owner = key("diagnostic-consumer");
		CompiledHopKey source = key("diagnostic-source");
		DurableAnchorKey seed = pool("diagnostic-seed");
		DurableAnchorKey output = pool("diagnostic-output");
		var a = direct(0, "diagnostic-a", source);
		var b = direct(0, "diagnostic-b", source);
		var c = direct(0, "diagnostic-c", source);
		PlacementRealizationKey key = PlacementRealizationKey.nativeLineage(
			emission(), "diagnostic-output");
		NativeContinuitySupportClauses shared = relation(
			owner, seed, output, List.of(sorted(a, b)));
		CandidateEmissionRealization sharedRealization =
			new CandidateEmissionRealization(key, shared);
		SearchSpaceMetrics sharedMetrics =
			new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(sharedMetrics);
		try {
			new CandidateEmissionFact(emission(), FType.ROW, null,
				List.of(sharedRealization, sharedRealization));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		Assert.assertEquals(0, shared.materializedHandleCount());
		Assert.assertEquals(2,
			sharedMetrics.duplicateMergeDiagnosticsSnapshot().nativeRelationDuplicateMembers());

		NativeContinuitySupportClauses identicalLeft = relation(
			owner, seed, output, List.of(sorted(a, b)));
		NativeContinuitySupportClauses identicalRight = relation(
			owner, seed, output, List.of(sorted(directLike(a), directLike(b))));
		SearchSpaceMetrics identicalMetrics =
			new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(identicalMetrics);
		CandidateEmissionFact identical;
		try {
			identical = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
				new CandidateEmissionRealization(key, identicalLeft),
				new CandidateEmissionRealization(key, identicalRight)));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		Assert.assertSame(identicalLeft, identical.realizations().get(0).supportClauses());
		Assert.assertEquals(0, identicalLeft.materializedHandleCount());
		Assert.assertEquals(0, identicalRight.materializedHandleCount());
		var identicalDiagnostics = identicalMetrics.duplicateMergeDiagnosticsSnapshot();
		Assert.assertEquals(2, identicalDiagnostics.nativeRelationDuplicateMembers());
		Assert.assertEquals(2, identicalDiagnostics.observedDuplicateClauses());
		Assert.assertEquals(2, identicalDiagnostics.legacyDuplicateClauses());
		Assert.assertEquals("TWO_NATIVE_RELATION_UNION",
			identicalDiagnostics.traces().get(0).mergeShape());
		Assert.assertTrue(identicalDiagnostics.traces().get(0).reusedRealization());

		NativeContinuitySupportClauses overlapLeft = relation(
			owner, seed, output, List.of(sorted(a, b)));
		NativeContinuitySupportClauses overlapRight = relation(
			owner, seed, output, List.of(sorted(directLike(b), c)));
		SearchSpaceMetrics overlapMetrics =
			new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(overlapMetrics);
		CandidateEmissionFact overlap;
		try {
			overlap = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
				new CandidateEmissionRealization(key, overlapLeft),
				new CandidateEmissionRealization(key, overlapRight)));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		NativeContinuitySupportClauses union = (NativeContinuitySupportClauses)
			overlap.realizations().get(0).supportClauses();
		Assert.assertEquals(3, union.size());
		Assert.assertEquals(0, overlapLeft.materializedHandleCount());
		Assert.assertEquals(0, overlapRight.materializedHandleCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		Assert.assertEquals(1,
			overlapMetrics.duplicateMergeDiagnosticsSnapshot().nativeRelationDuplicateMembers());
		Assert.assertEquals("diagnostic merge must not construct member clauses", 0,
			overlapMetrics.objectCreationSnapshot().explicitSupportClauses());
		Assert.assertEquals(0, overlapMetrics.objectCreationSnapshot().indexedSupportHandles());
	}

	@Test
	public void diagnosticsKeepFullMultiHeaderAxisGrowthLazy() {
		CompiledHopKey owner = key("diagnostic-header-axis-consumer");
		CompiledHopKey source = key("diagnostic-header-axis-source");
		DurableAnchorKey output = pool("diagnostic-header-axis-output");
		var a = direct(0, "diagnostic-header-axis-a", source);
		var b = direct(0, "diagnostic-header-axis-b", source);
		var c = direct(0, "diagnostic-header-axis-c", source);
		NativeContinuitySupportClauses left = relation(owner,
			pool("diagnostic-header-axis-seed-a"), output, List.of(sorted(a, b)))
			.multiHeaderUnion(relation(owner, pool("diagnostic-header-axis-seed-b"),
				output, List.of(sorted(directLike(a), directLike(b))))).orElseThrow();
		NativeContinuitySupportClauses right = relation(owner,
			pool("diagnostic-header-axis-seed-a"), output,
			List.of(sorted(directLike(b), c)))
			.multiHeaderUnion(relation(owner, pool("diagnostic-header-axis-seed-b"),
				output, List.of(sorted(directLike(b), directLike(c))))).orElseThrow();
		PlacementRealizationKey key = PlacementRealizationKey.nativeLineage(
			emission(), "diagnostic-header-axis-output");
		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(metrics);
		CandidateEmissionFact merged;
		try {
			merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
				new CandidateEmissionRealization(key, left),
				new CandidateEmissionRealization(key, right)));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		NativeContinuitySupportClauses union = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(2, union.headerCount());
		Assert.assertEquals(6, union.size());
		Assert.assertEquals(0, left.materializedHandleCount());
		Assert.assertEquals(0, right.materializedHandleCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		Assert.assertEquals(2,
			metrics.duplicateMergeDiagnosticsSnapshot().nativeRelationDuplicateMembers());
	}

	@Test
	public void diagnosticsKeepKWayMultiHeaderNativeUnionLazy() {
		CompiledHopKey owner = key("diagnostic-k-consumer");
		CompiledHopKey source = key("diagnostic-k-source");
		DurableAnchorKey output = pool("diagnostic-k-output");
		var a = direct(0, "diagnostic-k-a", source);
		var b = direct(0, "diagnostic-k-b", source);
		List<List<CandidateRealizationInputBinding>> axes = List.of(sorted(a, b));
		NativeContinuitySupportClauses first = relation(
			owner, pool("diagnostic-k-seed-a"), output, axes);
		NativeContinuitySupportClauses duplicate = relation(owner,
			pool("diagnostic-k-seed-a"), output,
			List.of(sorted(directLike(a), directLike(b))));
		NativeContinuitySupportClauses secondHeader = relation(owner,
			pool("diagnostic-k-seed-b"), output,
			List.of(sorted(directLike(a), directLike(b))));
		PlacementRealizationKey key = PlacementRealizationKey.nativeLineage(
			emission(), "diagnostic-k-output");
		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(metrics);
		CandidateEmissionFact merged;
		try {
			merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
				new CandidateEmissionRealization(key, first),
				new CandidateEmissionRealization(key, duplicate),
				new CandidateEmissionRealization(key, secondHeader)));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		NativeContinuitySupportClauses union = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(4, union.size());
		Assert.assertEquals(2, union.headerCount());
		Assert.assertEquals(0, first.materializedHandleCount());
		Assert.assertEquals(0, duplicate.materializedHandleCount());
		Assert.assertEquals(0, secondHeader.materializedHandleCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		var diagnostics = metrics.duplicateMergeDiagnosticsSnapshot();
		Assert.assertEquals(2, diagnostics.nativeRelationDuplicateMembers());
		Assert.assertEquals(2, diagnostics.observedDuplicateClauses());
		Assert.assertEquals("K_NATIVE_RELATION_UNION", diagnostics.traces().get(0).mergeShape());
	}

	@Test
	public void diagnosticsPreserveForeignOwnerFallbackAndExactComplementCompletion() {
		CompiledHopKey owner = key("diagnostic-fallback-consumer");
		CompiledHopKey source = key("diagnostic-fallback-source");
		CompiledHopKey foreignSource = cloneKey(source);
		DurableAnchorKey seed = pool("diagnostic-fallback-seed");
		DurableAnchorKey output = pool("diagnostic-fallback-output");
		var a = direct(0, "diagnostic-fallback-a", source);
		NativeContinuitySupportClauses left = relation(
			owner, seed, output, List.of(List.of(a)));
		NativeContinuitySupportClauses foreign = relation(owner, seed, output,
			List.of(List.of(direct(0, "diagnostic-fallback-a", foreignSource))));
		Assert.assertTrue(left.oneAxisUnion(foreign).isEmpty());
		Assert.assertTrue(left.multiHeaderUnion(foreign).isEmpty());
		Assert.assertTrue(NativeContinuitySupportClauses.unionSameAxesHeaderGroup(
			List.of(left, foreign)).isEmpty());
		PlacementRealizationKey key = PlacementRealizationKey.nativeLineage(
			emission(), "diagnostic-fallback-output");
		SearchSpaceMetrics foreignMetrics =
			new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(foreignMetrics);
		CandidateEmissionFact foreignMerged;
		try {
			foreignMerged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
				new CandidateEmissionRealization(key, left),
				new CandidateEmissionRealization(key, foreign)));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		Assert.assertSame("failed native union keeps the legacy exact left authority",
			left, foreignMerged.realizations().get(0).supportClauses());
		Assert.assertEquals(1, left.materializedHandleCount());
		Assert.assertEquals(1, foreign.materializedHandleCount());
		Assert.assertEquals(0,
			foreignMetrics.duplicateMergeDiagnosticsSnapshot().nativeRelationDuplicateMembers());

		var b = direct(0, "diagnostic-fallback-b", source);
		NativePlacementContinuity.NativeSupportProduct base =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, true, List.of(sorted(a, b)));
		NativeContinuitySupportClauses conditional = NativeContinuitySupportClauses.exactComplement(
			owner, base, List.of(List.of(a)), output, true);
		Assert.assertNotNull(conditional);
		SearchSpaceMetrics conditionalMetrics =
			new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(4);
		PlacementIdentity.setActiveMetrics(conditionalMetrics);
		CandidateEmissionFact conditionalMerged;
		try {
			conditionalMerged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
				new CandidateEmissionRealization(key, left),
				new CandidateEmissionRealization(key, conditional)));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
		Assert.assertTrue(conditionalMerged.realizations().get(0).supportClauses()
			instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses completed = (NativeContinuitySupportClauses)
			conditionalMerged.realizations().get(0).supportClauses();
		Assert.assertEquals(2, completed.size());
		Assert.assertSame(left.get(0), completed.get(
			completed.product().ordinalOfExactAuthorityBindings(List.of(a))));
		Assert.assertSame(conditional.get(0), completed.get(
			completed.product().ordinalOfExactAuthorityBindings(List.of(b))));
		Assert.assertEquals(0,
			conditionalMetrics.duplicateMergeDiagnosticsSnapshot().nativeRelationDuplicateMembers());
	}
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
	public void candidateEmissionMergeRetainsEarlierExplicitMemberInsideUnreadNativeProduct() {
		CompiledHopKey owner = key("explicit-native-bridge-consumer");
		CompiledHopKey leftOwner = key("explicit-native-bridge-left");
		CompiledHopKey rightOwner = key("explicit-native-bridge-right");
		DurableAnchorKey seed = pool("explicit-native-bridge-seed");
		DurableAnchorKey output = pool("explicit-native-bridge-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			sorted(direct(0, "a", leftOwner), direct(0, "left-long", leftOwner)),
			sorted(direct(1, "a", rightOwner), direct(1, "right-medium", rightOwner),
				direct(1, "right-much-longer", rightOwner)));
		NativeContinuitySupportClauses laterNative = relation(owner, seed, output, axes);
		Assert.assertEquals(6, laterNative.size());
		Assert.assertEquals(0, laterNative.materializedHandleCount());

		List<CandidateRealizationInputBinding> selected = List.of(
			directLike(axes.get(0).get(1)), directLike(axes.get(1).get(2)));
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, selected).continuityProofKey(owner);
		CandidateRealizationSupportClause earlierExplicit =
			new CandidateRealizationSupportClause(List.of(proof), selected, output, true);
		int authorityOrdinal = laterNative.product()
			.ordinalOfExactAuthorityBindings(earlierExplicit.inputBindings());
		Assert.assertTrue("fixture selects a nonzero native canonical ordinal", authorityOrdinal > 0);
		List<CandidateRealizationSupportClause> explicitOracle = explicit(
			owner, seed, output, axes);
		PlacementRealizationKey realizationKey =
			PlacementRealizationKey.nativeLineage(emission(), "explicit-native-bridge-output");

		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			new CandidateEmissionRealization(realizationKey, List.of(earlierExplicit)),
			new CandidateEmissionRealization(realizationKey, laterNative)));
		Assert.assertEquals(1, merged.realizations().size());
		Assert.assertTrue("an exact earlier member must remain represented by the native product",
			merged.realizations().get(0).supportClauses() instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses bridged = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(6, bridged.size());
		Assert.assertEquals("merge must not enumerate the later native relation", 0,
			laterNative.materializedHandleCount());
		Assert.assertEquals("native list hash must match independent explicit enumeration",
			explicitOracle.hashCode(), bridged.hashCode());
		Assert.assertEquals("hashing must not enumerate the later native relation", 0,
			laterNative.materializedHandleCount());
		Assert.assertSame("the earlier exact clause remains the authority donor",
			earlierExplicit, bridged.get(authorityOrdinal));
		Assert.assertEquals("donor lookup must not enumerate the later native relation", 0,
			laterNative.materializedHandleCount());
		int donorCount = bridged.authorityDonorCount();
		CandidateEmissionFact repeated = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(realizationKey, List.of(earlierExplicit)),
				merged.realizations().get(0)));
		Assert.assertSame("a repeated earlier prefix reuses the existing overlay",
			bridged, repeated.realizations().get(0).supportClauses());
		Assert.assertEquals(donorCount, bridged.authorityDonorCount());
		Assert.assertEquals("repeated-prefix recognition must not enumerate the later relation", 0,
			laterNative.materializedHandleCount());
		CandidateRealizationSupportClause replacement = new CandidateRealizationSupportClause(
			earlierExplicit.proofDependencies(), earlierExplicit.inputBindings(),
			earlierExplicit.nativeWorkerPoolWitness(),
			earlierExplicit.nativeWorkerPoolLayoutExact());
		CandidateEmissionFact replaced = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(realizationKey, List.of(replacement)),
				merged.realizations().get(0)));
		NativeContinuitySupportClauses replacedBridge = (NativeContinuitySupportClauses)
			replaced.realizations().get(0).supportClauses();
		Assert.assertSame("an equal-distinct earlier clause becomes the new first donor",
			replacement, replacedBridge.get(authorityOrdinal));
		Assert.assertEquals(donorCount + 1, replacedBridge.authorityDonorCount());
		NativeContinuitySupportClauses withdrawn = bridged.restrictBindings(binding ->
			!binding.equals(selected.get(1))).orElseThrow();
		Assert.assertEquals(-1, withdrawn.product().ordinalOfExactAuthorityBindings(selected));
		NativeContinuitySupportClauses readded = relation(owner, seed, output, List.of(
			axes.get(0), List.of(directLike(selected.get(1)))));
		int readdedOrdinal = readded.product().ordinalOfExactAuthorityBindings(selected);
		CandidateRealizationSupportClause current = readded.get(readdedOrdinal);
		NativeContinuitySupportClauses restored = withdrawn.oneAxisUnion(readded).orElseThrow();
		int restoredOrdinal = restored.product().ordinalOfExactAuthorityBindings(selected);
		Assert.assertSame("withdrawal drops the old singleton donor before re-addition",
			current, restored.get(restoredOrdinal));
		Assert.assertNotSame(earlierExplicit, restored.get(restoredOrdinal));

		Assert.assertEquals(explicitOracle.stream().map(
			CandidateRealizationSupportClause::normalizedSignature).toList(), bridged.stream().map(
				CandidateRealizationSupportClause::normalizedSignature).toList());
		Assert.assertEquals(explicitOracle, bridged);
	}

	@Test
	public void candidateEmissionMergeDoesNotBridgeExplicitMemberFromDifferentFullSeed() {
		CompiledHopKey owner = key("explicit-native-seed-guard-consumer");
		CompiledHopKey sourceOwner = key("explicit-native-seed-guard-source");
		DurableAnchorKey nativeSeed = pool("explicit-native-seed-guard-native");
		DurableAnchorKey explicitSeed = pool("explicit-native-seed-guard-explicit");
		DurableAnchorKey output = pool("explicit-native-seed-guard-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(sorted(
			direct(0, "a", sourceOwner), direct(0, "b", sourceOwner)));
		NativeContinuitySupportClauses laterNative = relation(
			owner, nativeSeed, output, axes);
		CandidateRealizationSupportClause foreignSeed = explicit(
			owner, explicitSeed, output, List.of(List.of(directLike(axes.get(0).get(1))))).get(0);
		Assert.assertEquals(-1, laterNative.ordinalOfExactAuthorityClause(foreignSeed));
		Assert.assertEquals(0, laterNative.materializedHandleCount());
		PlacementRealizationKey realizationKey =
			PlacementRealizationKey.nativeLineage(emission(), "explicit-native-seed-guard-output");

		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			new CandidateEmissionRealization(realizationKey, List.of(foreignSeed)),
			new CandidateEmissionRealization(realizationKey, laterNative)));
		List<CandidateRealizationSupportClause> clauses = merged.realizations().get(0).supportClauses();
		Assert.assertEquals("a different full external seed remains an independent alternative",
			3, clauses.size());
		Assert.assertTrue("fallback preserves the earlier explicit authority object",
			clauses.stream().anyMatch(clause -> clause == foreignSeed));
	}

	@Test
	public void candidateEmissionGroupBridgesExplicitPrefixBeforeNativeGrowth() {
		CompiledHopKey owner = key("explicit-native-group-owner");
		CompiledHopKey leftOwner = key("explicit-native-group-left");
		CompiledHopKey rightOwner = key("explicit-native-group-right");
		DurableAnchorKey seed = pool("explicit-native-group-seed");
		DurableAnchorKey output = pool("explicit-native-group-output");
		var leftA = direct(0, "a", leftOwner);
		var leftB = direct(0, "b", leftOwner);
		var rightA = direct(1, "a", rightOwner);
		var rightB = direct(1, "b", rightOwner);
		var rightC = direct(1, "c", rightOwner);
		List<List<CandidateRealizationInputBinding>> baseAxes = List.of(
			sorted(leftA, leftB), sorted(rightA, rightB));
		List<List<CandidateRealizationInputBinding>> grownAxes = List.of(
			sorted(directLike(leftA), directLike(leftB)),
			sorted(directLike(rightA), directLike(rightB), rightC));
		NativeContinuitySupportClauses base = relation(owner, seed, output, baseAxes);
		NativeContinuitySupportClauses grown = relation(owner, seed, output, grownAxes);
		List<CandidateRealizationInputBinding> selected = List.of(
			directLike(leftB), directLike(rightB));
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, selected).continuityProofKey(owner);
		CandidateRealizationSupportClause earlierExplicit = new CandidateRealizationSupportClause(
			List.of(proof), selected, output, true);
		PlacementRealizationKey realizationKey =
			PlacementRealizationKey.nativeLineage(emission(), "explicit-native-group-output");

		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			new CandidateEmissionRealization(realizationKey, List.of(earlierExplicit)),
			new CandidateEmissionRealization(realizationKey, base),
			new CandidateEmissionRealization(realizationKey, grown)));
		Assert.assertEquals(1, merged.realizations().size());
		Assert.assertTrue(merged.realizations().get(0).supportClauses()
			instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses union = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(6, union.size());
		Assert.assertEquals(0, base.materializedHandleCount());
		Assert.assertEquals(0, grown.materializedHandleCount());
		List<CandidateRealizationSupportClause> oracle = explicit(owner, seed, output, grownAxes);
		Assert.assertEquals(oracle.hashCode(), union.hashCode());
		Assert.assertEquals(0, base.materializedHandleCount());
		Assert.assertEquals(0, grown.materializedHandleCount());
		int ordinal = union.product().ordinalOfExactAuthorityBindings(selected);
		Assert.assertSame(earlierExplicit, union.get(ordinal));
		Assert.assertEquals(0, base.materializedHandleCount());
		Assert.assertEquals(0, grown.materializedHandleCount());
		Assert.assertEquals(oracle, union);
	}

	@Test
	public void explicitAuthorityBridgeRejectsEveryInexactAuthorityBoundaryWithoutReads() {
		CompiledHopKey owner = key("explicit-bridge-boundary-owner");
		CompiledHopKey sourceOwner = key("explicit-bridge-boundary-source");
		DurableAnchorKey seed = pool("explicit-bridge-boundary-seed");
		DurableAnchorKey output = pool("explicit-bridge-boundary-output");
		var a = direct(0, "a", sourceOwner);
		var b = direct(0, "b", sourceOwner);
		List<List<CandidateRealizationInputBinding>> axes = List.of(sorted(a, b));
		NativeContinuitySupportClauses relation = relation(owner, seed, output, axes);
		CandidateRealizationSupportClause exact = explicit(
			owner, seed, output, List.of(List.of(directLike(b)))).get(0);
		Assert.assertNotNull(relation.withExplicitAuthority(List.of(exact)).relation());

		assertExplicitOverlayRejected(relation, explicit(
			cloneKey(owner), seed, output, List.of(List.of(directLike(b)))).get(0));
		assertExplicitOverlayRejected(relation, explicit(
			owner, seed, output, List.of(List.of(direct(0, "b", cloneKey(sourceOwner))))).get(0));
		var foreignOutputProof = new NativePlacementContinuity.NativeContinuityProof(
			seed, pool("explicit-bridge-boundary-foreign-output"), true,
			exact.inputBindings()).continuityProofKey(owner);
		assertExplicitOverlayRejected(relation, new CandidateRealizationSupportClause(
			List.of(foreignOutputProof), exact.inputBindings(), output, true));
		var dynamicRangesProof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, false, exact.inputBindings()).continuityProofKey(owner);
		assertExplicitOverlayRejected(relation, new CandidateRealizationSupportClause(
			List.of(dynamicRangesProof), exact.inputBindings(), output, true));
		assertExplicitOverlayRejected(relation, new CandidateRealizationSupportClause(
			exact.proofDependencies(), exact.inputBindings(),
			pool("explicit-bridge-boundary-foreign-witness"), true));
		assertExplicitOverlayRejected(relation, new CandidateRealizationSupportClause(
			exact.proofDependencies(), exact.inputBindings(), output, false));
		assertExplicitOverlayRejected(relation, new CandidateRealizationSupportClause(
			List.of(exact.proofDependencies().get(0), foreignOutputProof),
			exact.inputBindings(), output, true));
		assertExplicitOverlayRejected(relation, explicit(owner, seed, output,
			List.of(List.of(direct(0, "not-a-member", sourceOwner)))).get(0));

		NativePlacementContinuity.NativeSupportProduct base =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		NativeContinuitySupportClauses conditional = NativeContinuitySupportClauses.exactComplement(
			owner, base, List.of(List.of(a)), output, true);
		Assert.assertNotNull(conditional);
		CandidateRealizationSupportClause conditionalMember = explicit(
			owner, seed, output, List.of(List.of(directLike(b)))).get(0);
		assertExplicitOverlayRejected(conditional, conditionalMember);
		NativeContinuitySupportClauses multiHeader = relation(owner, seed, output, axes)
			.multiHeaderUnion(relation(owner, pool("explicit-bridge-boundary-second-seed"),
				output, List.of(sorted(directLike(a), directLike(b))))).orElseThrow();
		assertExplicitOverlayRejected(multiHeader, exact);
	}

	@Test
	public void explicitAuthorityBridgeHonorsDonorAndRetentionCapsWithoutMemberReads() {
		CompiledHopKey owner = key("explicit-bridge-cap-owner");
		CompiledHopKey sourceOwner = key("explicit-bridge-cap-source");
		DurableAnchorKey seed = pool("explicit-bridge-cap-seed");
		DurableAnchorKey output = pool("explicit-bridge-cap-output");
		List<CandidateRealizationInputBinding> options = new ArrayList<>();
		for(int index = 0; index < 65; index++)
			options.add(direct(0, String.format("member-%02d", index), sourceOwner));
		options.sort(PlacementAnalysis.canonicalComparator());
		NativeContinuitySupportClauses relation = relation(
			owner, seed, output, List.of(List.copyOf(options)));
		List<CandidateRealizationSupportClause> explicit = explicit(
			owner, seed, output, List.of(List.copyOf(options)));
		var boundary = relation.withExplicitAuthority(explicit.subList(0, 63));
		Assert.assertNotNull(boundary.relation());
		Assert.assertFalse(boundary.capRejected());
		Assert.assertEquals(64, boundary.relation().authorityDonorCount());
		var capped = relation.withExplicitAuthority(explicit.subList(0, 64));
		Assert.assertNull(capped.relation());
		Assert.assertTrue(capped.capRejected());
		Assert.assertEquals(0, relation.materializedHandleCount());

		List<CandidateRealizationInputBinding> left = new ArrayList<>();
		List<CandidateRealizationInputBinding> right = new ArrayList<>();
		CompiledHopKey leftOwner = key("retention-left");
		CompiledHopKey rightOwner = key("retention-right");
		for(int index = 0; index < 1001; index++) {
			left.add(direct(0, String.format("left-%04d", index), leftOwner));
			right.add(direct(1, String.format("right-%04d", index), rightOwner));
		}
		left.sort(PlacementAnalysis.canonicalComparator());
		right.sort(PlacementAnalysis.canonicalComparator());
		NativeContinuitySupportClauses wide = relation(owner, seed, output,
			List.of(List.copyOf(left), List.copyOf(right)));
		CandidateRealizationSupportClause oneMember = explicit(owner, seed, output,
			List.of(List.of(directLike(left.get(0))), List.of(directLike(right.get(0))))).get(0);
		var retentionRejected = wide.withExplicitAuthority(List.of(oneMember));
		Assert.assertNull(retentionRejected.relation());
		Assert.assertTrue(retentionRejected.capRejected());
		Assert.assertEquals(0, wide.materializedHandleCount());
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
	public void identityScopedRestrictionRetainsStructurallyExactDonorBindings() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey firstSource = key("first-source");
		CompiledHopKey secondSource = key("second-source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", firstSource);
		var b = direct(0, "b", firstSource);
		var retained = direct(1, "retained", secondSource);
		var added = direct(1, "added", secondSource);
		NativeContinuitySupportClauses left = relation(owner, seed, output,
			List.of(sorted(a, b), List.of(retained)));
		NativeContinuitySupportClauses right = relation(owner, seed, output,
			List.of(sorted(directLike(a), directLike(b)), List.of(added)));
		CandidateRealizationSupportClause exactAdded = right.get(
			right.product().ordinalOfExactAuthorityBindings(
				List.of(directLike(a), added)));
		NativeContinuitySupportClauses union = left.oneAxisUnion(right).orElseThrow();
		java.util.Set<CandidateRealizationInputBinding> supported =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		supported.add(union.product().axes().get(0).get(0));
		supported.addAll(union.product().axes().get(1));
		NativeContinuitySupportClauses restricted = union.restrictBindings(supported::contains)
			.orElseThrow();
		int addedOrdinal = restricted.product().ordinalOfExactAuthorityBindings(
			List.of(a, added));
		Assert.assertTrue(addedOrdinal >= 0);
		Assert.assertSame("restriction must retain the donor by exact authority, not binding identity",
			exactAdded, restricted.get(addedOrdinal));
		CandidateEmissionRealization rebound = PlacementSupportRelations.rebindRealization(
			new CandidateEmissionRealization(
				PlacementRealizationKey.nativeLineage(emission(), "restricted-union"), restricted),
			emission());
		Assert.assertEquals(restricted.size(), rebound.supportClauses().size());
		Assert.assertSame("the real rebinding traversal retains exact donor authority",
			exactAdded, rebound.supportClauses().get(addedOrdinal));
	}

	@Test
	public void fixedPointWithdrawalRestrictsEveryCommonAxisHeaderWithoutFlattening() {
		CompiledHopKey consumerOwner = key("multi-header-fixed-point-consumer");
		CompiledHopKey alternativesOwner = key("multi-header-alternative-source");
		DurableAnchorKey output = pool("multi-header-fixed-point-output");
		CandidateRuleKey missingKey = new CandidateRuleKey(key("multi-header-missing"), List.of());
		CandidateRealizationReference missing = new CandidateRealizationReference(
			missingKey, sourceRealization("multi-header-missing").key());
		CandidateRuleKey doomedKey = new CandidateRuleKey(key("multi-header-doomed"),
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization doomedRealization = dependentSourceRealization(
			"multi-header-doomed", CandidateRealizationInputBinding.direct(0, missing));
		CandidateRuleFact doomedSource = fact(doomedKey,
			new CandidateEmissionFact(emission(), FType.ROW, null, List.of(doomedRealization)));
		CandidateRuleKey alternativesKey = new CandidateRuleKey(alternativesOwner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization doomedAlternative = dependentSourceRealization(
			"multi-header-a", CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(doomedKey, doomedRealization)));
		CandidateEmissionRealization liveAlternative = sourceRealization("multi-header-b");
		CandidateRuleFact alternatives = fact(alternativesKey,
			new CandidateEmissionFact(emission(), FType.ROW, null,
				List.of(doomedAlternative, liveAlternative)));
		CandidateRealizationInputBinding a = sourceBinding(0, alternatives, doomedAlternative);
		CandidateRealizationInputBinding b = sourceBinding(0, alternatives, liveAlternative);
		NativeContinuitySupportClauses first = relation(consumerOwner, pool("header-a"), output,
			List.of(sorted(a, b)));
		NativeContinuitySupportClauses second = relation(consumerOwner, pool("header-b"), output,
			List.of(sorted(directLike(a), directLike(b))));
		CandidateRealizationSupportClause firstB = first.get(
			first.product().ordinalOfExactAuthorityBindings(List.of(b)));
		CandidateRealizationSupportClause secondB = second.get(
			second.product().ordinalOfExactAuthorityBindings(List.of(directLike(b))));
		NativeContinuitySupportClauses union = first.multiHeaderUnion(second).orElseThrow();
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "multi-header-fixed-point-output");
		CandidateEmissionFact emission = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(outputKey, union)));
		CandidateRuleFact consumer = fact(new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW))), emission);
		PlacementSupportRelations.WorklistResult fixedPoint = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPointWithWork(
				List.of(doomedSource, alternatives, consumer), null, List.of(), Map.of());
		Assert.assertEquals("one dependent source clause plus one member per seed header", 3L,
			fixedPoint.work().invalidatedClauses());
		CandidateEmissionRealization retained = fixedPoint.facts().stream()
			.filter(candidate -> candidate.key().parentOccurrence() == consumerOwner)
			.flatMap(candidate -> candidate.allowedEmissionFacts().stream())
			.flatMap(candidate -> candidate.realizations().stream())
			.filter(candidate -> candidate.key().equals(outputKey)).findFirst().orElseThrow();
		NativeContinuitySupportClauses restricted =
			(NativeContinuitySupportClauses)retained.supportClauses();
		Assert.assertEquals(2, restricted.size());
		java.util.Set<CandidateRealizationSupportClause> expected =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		expected.add(firstB);
		expected.add(secondB);
		for(int ordinal = 0; ordinal < restricted.size(); ordinal++)
			Assert.assertTrue("each header retains its exact donor through the real worklist",
				expected.remove(restricted.get(ordinal)));
		Assert.assertTrue(expected.isEmpty());
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

	private static void assertExplicitOverlayRejected(NativeContinuitySupportClauses relation,
		CandidateRealizationSupportClause clause) {
		int handles = relation.materializedHandleCount();
		var rejected = relation.withExplicitAuthority(List.of(clause));
		Assert.assertNull(rejected.relation());
		Assert.assertFalse(rejected.capRejected());
		Assert.assertEquals(handles, relation.materializedHandleCount());
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
