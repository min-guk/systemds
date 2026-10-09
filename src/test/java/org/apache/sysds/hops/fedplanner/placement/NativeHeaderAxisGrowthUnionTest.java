/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeHeaderAxisGrowthUnionTest {
	@Test
	public void completePerHeaderAxisGrowthStaysLazyAndRetainsOldAuthority() {
		Fixture fixture = fixture();
		CandidateRealizationSupportClause oldA = fixture.oldA.get(
			fixture.oldA.product().ordinalOfExactAuthorityBindings(List.of(fixture.a)));
		CandidateRealizationSupportClause oldB = fixture.oldB.get(
			fixture.oldB.product().ordinalOfExactAuthorityBindings(List.of(fixture.b)));
		CandidateEmissionRealization retained = merge(fixture.outputKey,
			fixture.oldA, fixture.oldB).realizations().get(0);
		Assert.assertTrue(retained.supportClauses() instanceof NativeContinuitySupportClauses);

		CandidateEmissionFact grown = mergeRealizations(fixture.outputKey, retained,
			fixture.grownA, fixture.grownB);
		CandidateEmissionFact reversed = mergeRealizations(fixture.outputKey, retained,
			fixture.grownB, fixture.grownA);
		Assert.assertEquals(1, grown.realizations().size());
		Assert.assertTrue("complete growth for every retained header must remain compressed",
			grown.realizations().get(0).supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertTrue("reversing the new header order must retain the same compressed form",
			reversed.realizations().get(0).supportClauses() instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses relation = (NativeContinuitySupportClauses)
			grown.realizations().get(0).supportClauses();
		Assert.assertEquals(6, relation.size());
		Assert.assertEquals("batch union must not materialize its own member handles", 0,
			relation.materializedHandleCount());
		Assert.assertEquals(1, fixture.oldA.materializedHandleCount());
		Assert.assertEquals(1, fixture.oldB.materializedHandleCount());
		Assert.assertEquals(0, fixture.grownA.materializedHandleCount());
		Assert.assertEquals(0, fixture.grownB.materializedHandleCount());

		List<CandidateRealizationSupportClause> expected = explicit(fixture.owner,
			List.of(new Header(fixture.seedA, List.of(sorted(fixture.a, fixture.b, fixture.c))),
				new Header(fixture.seedB, List.of(sorted(fixture.a, fixture.b, fixture.c)))),
			fixture.output);
		List<CandidateRealizationSupportClause> actual = List.copyOf(relation);
		Assert.assertEquals(signatures(expected), signatures(actual));
		Assert.assertEquals(signatures(actual), signatures(
			reversed.realizations().get(0).supportClauses()));
		Assert.assertTrue(actual.stream().anyMatch(clause -> clause == oldA));
		Assert.assertTrue(actual.stream().anyMatch(clause -> clause == oldB));
	}

	@Test
	public void partialHeaderGrowthFallsBackWithoutInventingCorrelatedHole() {
		Fixture fixture = fixture();
		CandidateRealizationSupportClause oldB = fixture.oldB.get(
			fixture.oldB.product().ordinalOfExactAuthorityBindings(List.of(fixture.b)));
		CandidateEmissionRealization retained = merge(fixture.outputKey,
			fixture.oldA, fixture.oldB).realizations().get(0);
		CandidateEmissionFact partial = mergeRealizations(fixture.outputKey,
			retained, fixture.grownA);
		Assert.assertEquals(1, partial.realizations().size());
		Assert.assertFalse("a rectangular family would invent the unsupported seed-B/c member",
			partial.realizations().get(0).supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(5, partial.realizations().get(0).supportClauses().size());
		List<CandidateRealizationSupportClause> expected = explicit(fixture.owner,
			List.of(new Header(fixture.seedA, List.of(sorted(fixture.a, fixture.b, fixture.c))),
				new Header(fixture.seedB, List.of(sorted(fixture.a, fixture.b)))), fixture.output);
		Assert.assertEquals(signatures(expected),
			signatures(partial.realizations().get(0).supportClauses()));
		Assert.assertTrue("fallback retains old seed-B clause identity",
			partial.realizations().get(0).supportClauses().stream()
				.anyMatch(clause -> clause == oldB));
		Assert.assertFalse(partial.realizations().get(0).supportClauses().stream()
			.anyMatch(clause -> clause.inputBindings().stream().anyMatch(binding ->
				binding.source().realization().equals(fixture.c.source().realization()))
				&& clause.proofDependencies().get(0).normalizedSignature()
					.contains(fixture.seedB.placementId())));
	}

	@Test
	public void completeTwoAxisGrowthCompressesButPartialGrowthFallsBack() {
		CompiledHopKey owner = key("two-axis-owner");
		CompiledHopKey firstOwner = key("two-axis-first");
		CompiledHopKey secondOwner = key("two-axis-second");
		CandidateRealizationInputBinding a = binding(0, "a", firstOwner);
		CandidateRealizationInputBinding b = binding(0, "b", firstOwner);
		CandidateRealizationInputBinding c = binding(0, "c", firstOwner);
		CandidateRealizationInputBinding x = binding(1, "x", secondOwner);
		CandidateRealizationInputBinding y = binding(1, "y", secondOwner);
		DurableAnchorKey seedA = fullPool("two-axis-seed-a");
		DurableAnchorKey seedB = fullPool("two-axis-seed-b");
		DurableAnchorKey output = rowPool("two-axis-output");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "two-axis-result");
		List<List<CandidateRealizationInputBinding>> oldAxes =
			List.of(sorted(a, b), sorted(x, y));
		List<List<CandidateRealizationInputBinding>> grownAxes =
			List.of(sorted(directLike(a), directLike(b), directLike(c)),
				sorted(directLike(x), directLike(y)));
		NativeContinuitySupportClauses oldA = relation(owner, seedA, output, oldAxes);
		NativeContinuitySupportClauses oldB = relation(owner, seedB, output, oldAxes);
		NativeContinuitySupportClauses grownA = relation(owner, seedA, output, grownAxes);
		NativeContinuitySupportClauses grownB = relation(owner, seedB, output, grownAxes);
		CandidateRealizationSupportClause retained = oldA.get(
			oldA.product().ordinalOfExactAuthorityBindings(List.of(a, x)));
		CandidateEmissionRealization oldFamily = merge(outputKey, oldA, oldB).realizations().get(0);

		CandidateEmissionFact complete = mergeRealizations(outputKey, oldFamily, grownA, grownB);
		Assert.assertTrue(complete.realizations().get(0).supportClauses()
			instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(12, complete.realizations().get(0).supportClauses().size());
		Assert.assertEquals(0, ((NativeContinuitySupportClauses)complete.realizations().get(0)
			.supportClauses()).materializedHandleCount());
		List<CandidateRealizationSupportClause> expected = explicit(owner, List.of(
			new Header(seedA, List.of(sorted(a, b, c), sorted(x, y))),
			new Header(seedB, List.of(sorted(a, b, c), sorted(x, y)))), output);
		List<CandidateRealizationSupportClause> completeMembers =
			List.copyOf(complete.realizations().get(0).supportClauses());
		Assert.assertEquals(signatures(expected), signatures(completeMembers));
		Assert.assertTrue(completeMembers.stream().anyMatch(clause -> clause == retained));

		CandidateEmissionFact partial = mergeRealizations(outputKey, oldFamily, grownA);
		Assert.assertFalse(partial.realizations().get(0).supportClauses()
			instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(10, partial.realizations().get(0).supportClauses().size());
		List<CandidateRealizationSupportClause> expectedPartial = explicit(owner, List.of(
			new Header(seedA, List.of(sorted(a, b, c), sorted(x, y))),
			new Header(seedB, List.of(sorted(a, b), sorted(x, y)))), output);
		Assert.assertEquals(signatures(expectedPartial),
			signatures(partial.realizations().get(0).supportClauses()));
	}

	@Test
	public void twoCompleteFamiliesUseFastPairUnionAndRestrictionCanReaddGrowth() {
		CompiledHopKey owner = key("pair-owner");
		CompiledHopKey firstOwner = key("pair-first");
		CompiledHopKey secondOwner = key("pair-second");
		CandidateRealizationInputBinding a = binding(0, "a", firstOwner);
		CandidateRealizationInputBinding b = binding(0, "b", firstOwner);
		CandidateRealizationInputBinding c = binding(0, "c", firstOwner);
		CandidateRealizationInputBinding x = binding(1, "x", secondOwner);
		CandidateRealizationInputBinding y = binding(1, "y", secondOwner);
		DurableAnchorKey seedA = fullPool("pair-seed-a");
		DurableAnchorKey seedB = fullPool("pair-seed-b");
		DurableAnchorKey output = rowPool("pair-output");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "pair-result");
		List<List<CandidateRealizationInputBinding>> oldAxes =
			List.of(sorted(a, b), sorted(x, y));
		List<List<CandidateRealizationInputBinding>> grownAxes = List.of(
			sorted(directLike(a), directLike(b), directLike(c)),
			sorted(directLike(x), directLike(y)));
		NativeContinuitySupportClauses oldA = relation(owner, seedA, output, oldAxes);
		NativeContinuitySupportClauses oldB = relation(owner, seedB, output, oldAxes);
		NativeContinuitySupportClauses grownA = relation(owner, seedA, output, grownAxes);
		NativeContinuitySupportClauses grownB = relation(owner, seedB, output, grownAxes);
		CandidateRealizationSupportClause oldAuthority = oldB.get(
			oldB.product().ordinalOfExactAuthorityBindings(List.of(b, y)));
		CandidateEmissionRealization oldFamily = merge(outputKey, oldA, oldB).realizations().get(0);
		CandidateEmissionRealization grownFamily =
			merge(outputKey, grownA, grownB).realizations().get(0);
		CandidateEmissionFact paired = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(oldFamily, grownFamily));
		Assert.assertTrue("the two-realization fast path must union complete header families",
			paired.realizations().get(0).supportClauses() instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses complete = (NativeContinuitySupportClauses)
			paired.realizations().get(0).supportClauses();
		Assert.assertEquals(12, complete.size());
		Assert.assertEquals(0, complete.materializedHandleCount());
		Assert.assertEquals(1, oldB.materializedHandleCount());
		Assert.assertEquals(0, grownA.materializedHandleCount());
		Assert.assertEquals(0, grownB.materializedHandleCount());
		List<CandidateRealizationSupportClause> expected = explicit(owner, List.of(
			new Header(seedA, List.of(sorted(a, b, c), sorted(x, y))),
			new Header(seedB, List.of(sorted(a, b, c), sorted(x, y)))), output);
		Assert.assertEquals(signatures(expected), signatures(complete));
		Assert.assertTrue(complete.stream().anyMatch(clause -> clause == oldAuthority));

		NativeContinuitySupportClauses withdrawn = complete.restrictBindings(binding ->
			!binding.source().realization().equals(c.source().realization())).orElseThrow();
		Assert.assertEquals(8, withdrawn.size());
		CandidateEmissionFact restored = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(outputKey, withdrawn), grownFamily));
		Assert.assertTrue(restored.realizations().get(0).supportClauses()
			instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(12, restored.realizations().get(0).supportClauses().size());
		Assert.assertEquals(signatures(expected),
			signatures(restored.realizations().get(0).supportClauses()));
		Assert.assertTrue("withdraw/re-add keeps the retained old donor scope",
			restored.realizations().get(0).supportClauses().stream()
				.anyMatch(clause -> clause == oldAuthority));
	}

	@Test
	public void diagonalTwoAxisGrowthFallsBackWithoutCrossHeaderHoles() {
		CompiledHopKey owner = key("diagonal-owner");
		CompiledHopKey firstOwner = key("diagonal-first");
		CompiledHopKey secondOwner = key("diagonal-second");
		CandidateRealizationInputBinding a = binding(0, "a", firstOwner);
		CandidateRealizationInputBinding b = binding(0, "b", firstOwner);
		CandidateRealizationInputBinding c = binding(0, "c", firstOwner);
		CandidateRealizationInputBinding x = binding(1, "x", secondOwner);
		CandidateRealizationInputBinding y = binding(1, "y", secondOwner);
		CandidateRealizationInputBinding z = binding(1, "z", secondOwner);
		DurableAnchorKey seedA = fullPool("diagonal-seed-a");
		DurableAnchorKey seedB = fullPool("diagonal-seed-b");
		DurableAnchorKey output = rowPool("diagonal-output");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "diagonal-result");
		List<List<CandidateRealizationInputBinding>> oldAxes =
			List.of(sorted(a, b), sorted(x, y));
		NativeContinuitySupportClauses oldA = relation(owner, seedA, output, oldAxes);
		NativeContinuitySupportClauses oldB = relation(owner, seedB, output, oldAxes);
		CandidateEmissionRealization retained = merge(outputKey, oldA, oldB).realizations().get(0);
		NativeContinuitySupportClauses firstAxisGrowth = relation(owner, seedA, output,
			List.of(sorted(directLike(a), directLike(b), directLike(c)),
				sorted(directLike(x), directLike(y))));
		NativeContinuitySupportClauses secondAxisGrowth = relation(owner, seedB, output,
			List.of(sorted(directLike(a), directLike(b)),
				sorted(directLike(x), directLike(y), directLike(z))));
		CandidateEmissionFact diagonal = mergeRealizations(outputKey, retained,
			firstAxisGrowth, secondAxisGrowth);
		Assert.assertFalse("diagonal growth cannot be represented as one rectangular family",
			diagonal.realizations().get(0).supportClauses()
				instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(12, diagonal.realizations().get(0).supportClauses().size());
		List<CandidateRealizationSupportClause> expected = explicit(owner, List.of(
			new Header(seedA, List.of(sorted(a, b, c), sorted(x, y))),
			new Header(seedB, List.of(sorted(a, b), sorted(x, y, z)))), output);
		Assert.assertEquals(signatures(expected),
			signatures(diagonal.realizations().get(0).supportClauses()));
	}

	@Test
	public void unchangedThreeOperandHeaderGroupReturnsFirstExactRealization() {
		CompiledHopKey owner = key("unchanged-owner");
		CompiledHopKey source = key("unchanged-source");
		CandidateRealizationInputBinding a = binding(0, "a", source);
		CandidateRealizationInputBinding b = binding(0, "b", source);
		List<List<CandidateRealizationInputBinding>> axes = List.of(sorted(a, b));
		DurableAnchorKey seedA = fullPool("unchanged-seed-a");
		DurableAnchorKey seedB = fullPool("unchanged-seed-b");
		DurableAnchorKey output = rowPool("unchanged-output");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "unchanged-result");
		NativeContinuitySupportClauses firstA = relation(owner, seedA, output, axes);
		NativeContinuitySupportClauses firstB = relation(owner, seedB, output, axes);
		CandidateEmissionRealization first = merge(outputKey, firstA, firstB).realizations().get(0);
		NativeContinuitySupportClauses equalA = relation(owner, seedA, output,
			List.of(sorted(directLike(a), directLike(b))));
		NativeContinuitySupportClauses equalB = relation(owner, seedB, output,
			List.of(sorted(directLike(a), directLike(b))));
		CandidateEmissionRealization equal = merge(outputKey, equalA, equalB).realizations().get(0);
		Assert.assertNotSame(first, equal);
		Assert.assertEquals(first, equal);
		Assert.assertEquals(0, ((NativeContinuitySupportClauses)first.supportClauses())
			.materializedHandleCount());
		Assert.assertEquals(0, ((NativeContinuitySupportClauses)equal.supportClauses())
			.materializedHandleCount());

		CandidateEmissionFact unchanged = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(first, first, equal));
		Assert.assertEquals(1, unchanged.realizations().size());
		Assert.assertSame("an unchanged K-group must retain the first exact realization object",
			first, unchanged.realizations().get(0));
		Assert.assertSame(first.supportClauses(), unchanged.realizations().get(0).supportClauses());
		Assert.assertEquals("identity reuse must happen before hash or member iteration", 0,
			((NativeContinuitySupportClauses)first.supportClauses()).materializedHandleCount());
		Assert.assertEquals(0, ((NativeContinuitySupportClauses)equal.supportClauses())
			.materializedHandleCount());
	}

	@Test
	public void sixtyFiveDistinctHeadersFallBackExactlyAtDonorCap() {
		CompiledHopKey owner = key("header-cap-owner");
		CompiledHopKey source = key("header-cap-source");
		CandidateRealizationInputBinding binding = binding(0, "only", source);
		List<List<CandidateRealizationInputBinding>> axes = List.of(List.of(binding));
		DurableAnchorKey output = rowPool("header-cap-output");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "header-cap-result");
		List<NativeContinuitySupportClauses> relations = new ArrayList<>();
		List<Header> headers = new ArrayList<>();
		for(int index = 0; index < 65; index++) {
			DurableAnchorKey seed = fullPool("header-cap-seed-" + index);
			relations.add(relation(owner, seed, output, axes));
			headers.add(new Header(seed, axes));
		}
		CandidateRealizationSupportClause firstDonor = relations.get(0).get(0);
		CandidateEmissionFact fallback = merge(outputKey,
			relations.toArray(NativeContinuitySupportClauses[]::new));
		Assert.assertEquals(1, fallback.realizations().size());
		Assert.assertFalse("the 65th distinct header exceeds the exact donor cap",
			fallback.realizations().get(0).supportClauses()
				instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(65, fallback.realizations().get(0).supportClauses().size());
		Assert.assertEquals(signatures(explicit(owner, headers, output)),
			signatures(fallback.realizations().get(0).supportClauses()));
		Assert.assertTrue("fallback keeps the already materialized first donor authority",
			fallback.realizations().get(0).supportClauses().stream()
				.anyMatch(clause -> clause == firstDonor));
	}

	private static Fixture fixture() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey source = key("source");
		CandidateRealizationInputBinding a = binding(0, "a", source);
		CandidateRealizationInputBinding b = binding(0, "b", source);
		CandidateRealizationInputBinding c = binding(0, "c", source);
		DurableAnchorKey seedA = fullPool("seed-a");
		DurableAnchorKey seedB = fullPool("seed-b");
		DurableAnchorKey output = rowPool("output");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "result");
		List<List<CandidateRealizationInputBinding>> oldAxes = List.of(sorted(a, b));
		List<List<CandidateRealizationInputBinding>> grownAxes =
			List.of(sorted(directLike(a), directLike(b), directLike(c)));
		return new Fixture(owner, a, b, c, seedA, seedB, output, outputKey,
			relation(owner, seedA, output, oldAxes), relation(owner, seedB, output, oldAxes),
			relation(owner, seedA, output, grownAxes), relation(owner, seedB, output, grownAxes));
	}

	private static CandidateEmissionFact merge(PlacementRealizationKey key,
		NativeContinuitySupportClauses... relations) {
		return new CandidateEmissionFact(emission(), FType.ROW, null,
			java.util.Arrays.stream(relations)
				.map(relation -> new CandidateEmissionRealization(key, relation)).toList());
	}

	private static CandidateEmissionFact mergeRealizations(PlacementRealizationKey key,
		CandidateEmissionRealization retained, NativeContinuitySupportClauses... additions) {
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		realizations.add(retained);
		for(NativeContinuitySupportClauses addition : additions)
			realizations.add(new CandidateEmissionRealization(key, addition));
		return new CandidateEmissionFact(emission(), FType.ROW, null, realizations);
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, output, true);
	}

	private static List<CandidateRealizationSupportClause> explicit(CompiledHopKey owner,
		List<Header> headers, DurableAnchorKey output) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(Header header : headers)
			enumerate(owner, header.seed, output, header.axes, 0, new ArrayList<>(), clauses);
		clauses.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(clauses);
	}

	private static void enumerate(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey output, List<List<CandidateRealizationInputBinding>> axes,
		int axis, List<CandidateRealizationInputBinding> selected,
		List<CandidateRealizationSupportClause> clauses) {
		if(axis < axes.size()) {
			for(CandidateRealizationInputBinding binding : axes.get(axis)) {
				selected.add(binding);
				enumerate(owner, seed, output, axes, axis + 1, selected, clauses);
				selected.remove(selected.size() - 1);
			}
			return;
		}
		List<CandidateRealizationInputBinding> bindings =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				List.copyOf(selected), "header-axis-growth reference");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, bindings).continuityProofKey(owner);
		clauses.add(new CandidateRealizationSupportClause(
			List.of(proof), bindings, output, true));
	}

	private static List<String> signatures(List<CandidateRealizationSupportClause> clauses) {
		return clauses.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList();
	}

	private static List<CandidateRealizationInputBinding> sorted(
		CandidateRealizationInputBinding... bindings) {
		return java.util.Arrays.stream(bindings)
			.sorted(PlacementAnalysis.canonicalComparator()).toList();
	}

	private static CandidateRealizationInputBinding binding(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		return CandidateRealizationInputBinding.direct(position,
			new CandidateRealizationReference(rule,
				PlacementRealizationKey.nativeLineage(emission(), "source-" + name)));
	}

	private static CandidateRealizationInputBinding directLike(
		CandidateRealizationInputBinding binding) {
		return CandidateRealizationInputBinding.direct(binding.inputPosition(),
			new CandidateRealizationReference(binding.source().rule(), binding.source().realization()));
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	}

	private static DurableAnchorKey rowPool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:9101", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static DurableAnchorKey fullPool(String id) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition("localhost:9102", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"header-axis-growth", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("header-axis-growth", "main", name, "compiled",
			region, name, name);
	}

	private record Header(DurableAnchorKey seed,
		List<List<CandidateRealizationInputBinding>> axes) { }

	private record Fixture(CompiledHopKey owner,
		CandidateRealizationInputBinding a, CandidateRealizationInputBinding b,
		CandidateRealizationInputBinding c, DurableAnchorKey seedA, DurableAnchorKey seedB,
		DurableAnchorKey output, PlacementRealizationKey outputKey,
		NativeContinuitySupportClauses oldA, NativeContinuitySupportClauses oldB,
		NativeContinuitySupportClauses grownA, NativeContinuitySupportClauses grownB) { }
}
