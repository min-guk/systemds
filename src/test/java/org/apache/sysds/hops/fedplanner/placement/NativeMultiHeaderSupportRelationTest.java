/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

public class NativeMultiHeaderSupportRelationTest {
	@Test
	public void fixedSeedRandomHeadersMatchExplicitCanonicalRankAndDonorIdentity() {
		Random random = new Random(0x4d554c5449484541L);
		for(int trial = 0; trial < 100; trial++) {
			CompiledHopKey owner = key("random-consumer-" + trial);
			CompiledHopKey leftOwner = key("random-left-" + trial);
			CompiledHopKey rightOwner = key("random-right-" + trial);
			DurableAnchorKey output = pool("random-output-" + trial);
			List<List<CandidateRealizationInputBinding>> authorityAxes = List.of(
				randomAxis(random, 0, leftOwner, "left-" + trial),
				randomAxis(random, 1, rightOwner, "right-" + trial));
			int headers = 2 + random.nextInt(3);
			NativeContinuitySupportClauses union = null;
			List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
			java.util.Map<String,CandidateRealizationSupportClause> donorBySignature =
				new java.util.HashMap<>();
			for(int header = 0; header < headers; header++) {
				String padding = "x".repeat(switch((trial + header) % 4) {
					case 0 -> 8; case 1 -> 9; case 2 -> 10; default -> 99;
				});
				DurableAnchorKey seed = pool("seed-" + header + '-' + padding);
				List<List<CandidateRealizationInputBinding>> axes = authorityAxes.stream()
					.map(axis -> axis.stream().map(
						NativeMultiHeaderSupportRelationTest::directLike).toList()).toList();
				NativeContinuitySupportClauses relation = relation(owner, seed, output, axes);
				for(int ordinal = 0; ordinal < relation.size(); ordinal++) {
					CandidateRealizationSupportClause clause = relation.get(ordinal);
					donorBySignature.put(clause.normalizedSignature(), clause);
				}
				explicit.addAll(explicit(owner, seed, output, axes));
				union = union == null ? relation : union.multiHeaderUnion(relation).orElseThrow();
			}
			explicit.sort(PlacementAnalysis.canonicalComparator());
			Assert.assertNotNull(union);
			Assert.assertEquals(explicit.size(), union.size());
			for(int ordinal = 0; ordinal < explicit.size(); ordinal++) {
				String signature = explicit.get(ordinal).normalizedSignature();
				Assert.assertEquals("trial " + trial + " ordinal " + ordinal,
					signature, union.get(ordinal).normalizedSignature());
				Assert.assertSame(donorBySignature.get(signature), union.get(ordinal));
			}
		}
	}

	private static List<CandidateRealizationInputBinding> randomAxis(Random random,
		int position, CompiledHopKey owner, String prefix) {
		List<CandidateRealizationInputBinding> options = new ArrayList<>();
		for(int option = 0; option < 2 + random.nextInt(2); option++)
			options.add(direct(position, prefix + '-' + option + '-'
				+ "v".repeat(1 + random.nextInt(30)), owner));
		return options.stream().sorted(PlacementAnalysis.canonicalComparator()).toList();
	}

	@Test
	public void commonAxesHeadersRetainGlobalCanonicalOrderAndExactDonors() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey leftOwner = key("left-owner");
		CompiledHopKey rightOwner = key("right-owner");
		DurableAnchorKey output = pool("shared-output-with-a-long-name");
		DurableAnchorKey shortSeed = pool("s");
		DurableAnchorKey longSeed = pool("a-much-longer-seed-authority");
		var leftA = direct(0, "a", leftOwner);
		var leftLong = direct(0, "left-option-with-a-long-authority", leftOwner);
		var rightB = direct(1, "b", rightOwner);
		var rightLong = direct(1, "right-option-with-a-much-longer-authority", rightOwner);
		List<List<CandidateRealizationInputBinding>> firstAxes = List.of(
			sorted(leftA, leftLong), sorted(rightB, rightLong));
		List<List<CandidateRealizationInputBinding>> secondAxes = firstAxes.stream()
			.map(axis -> axis.stream().map(NativeMultiHeaderSupportRelationTest::directLike).toList())
			.toList();
		NativeContinuitySupportClauses first = relation(owner, shortSeed, output, firstAxes);
		NativeContinuitySupportClauses second = relation(owner, longSeed, output, secondAxes);
		CandidateRealizationSupportClause firstDonor = first.get(2);
		CandidateRealizationSupportClause secondDonor = second.get(1);

		NativeContinuitySupportClauses union = first.multiHeaderUnion(second).orElseThrow();
		Assert.assertEquals(2, union.headerCount());
		Assert.assertTrue(union.singleProduct().isEmpty());
		Assert.assertEquals(8, union.size());
		Assert.assertEquals(0, union.materializedHandleCount());
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
		explicit.addAll(explicit(owner, shortSeed, output, firstAxes));
		explicit.addAll(explicit(owner, longSeed, output, secondAxes));
		explicit.sort(PlacementAnalysis.canonicalComparator());
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
			Assert.assertEquals("global header/binding rank " + ordinal,
				explicit.get(ordinal).normalizedSignature(), union.get(ordinal).normalizedSignature());
		int firstOrdinal = identityOrdinal(union, firstDonor);
		int secondOrdinal = identityOrdinal(union, secondDonor);
		Assert.assertTrue(firstOrdinal >= 0);
		Assert.assertTrue(secondOrdinal >= 0);
		Assert.assertSame(firstDonor, union.get(firstOrdinal));
		Assert.assertSame(secondDonor, union.get(secondOrdinal));
		Assert.assertSame(firstDonor.inputBindings().get(0).source(),
			union.get(firstOrdinal).inputBindings().get(0).source());
		Assert.assertSame(secondDonor.proofDependencies().get(0),
			union.get(secondOrdinal).proofDependencies().get(0));
	}

	@Test
	public void restrictionAndReaddNeverResurrectStaleHeaderDonor() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", source);
		var b = direct(0, "b", source);
		NativeContinuitySupportClauses first = relation(owner, pool("seed-a"), output,
			List.of(sorted(a, b)));
		NativeContinuitySupportClauses oldSecond = relation(owner, pool("seed-b"), output,
			List.of(sorted(directLike(a), directLike(b))));
		CandidateRealizationSupportClause retainedSecond = oldSecond.get(0);
		CandidateRealizationSupportClause staleB = oldSecond.get(1);
		NativeContinuitySupportClauses union = first.multiHeaderUnion(oldSecond).orElseThrow();
		NativeContinuitySupportClauses restricted = union.restrictBindings(binding ->
			!binding.source().equals(b.source())).orElseThrow();
		Assert.assertEquals(2, restricted.size());
		Assert.assertTrue(identityOrdinal(restricted, retainedSecond) >= 0);
		Assert.assertEquals(-1, identityOrdinal(restricted, staleB));
		for(int ordinal = 0; ordinal < restricted.size(); ordinal++)
			Assert.assertNotNull(restricted.get(ordinal));
	}

	@Test
	public void relationViewIsHeaderCompleteAndUnsupportedAuthorityFallsBack() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey source = key("source");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", source);
		var b = direct(0, "b", source);
		NativeContinuitySupportClauses first = relation(owner, pool("seed-a"), output,
			List.of(sorted(a, b)));
		NativeContinuitySupportClauses second = relation(owner, pool("seed-b"), output,
			List.of(sorted(directLike(a), directLike(b))));
		PlacementAnalysis.NativeContinuitySupportProduct singleView =
			new PlacementAnalysis.NativeContinuitySupportProduct(first);
		Assert.assertSame(first.get(0), singleView.select(List.of(a)));
		Assert.assertTrue(new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "single-output"), first)
			.independentSupportProduct().isEmpty());
		NativeContinuitySupportClauses union = first.multiHeaderUnion(second).orElseThrow();
		PlacementAnalysis.NativeContinuitySupportProduct view =
			new PlacementAnalysis.NativeContinuitySupportProduct(union);
		Assert.assertEquals(2, view.headerCount());
		Assert.assertEquals(2, view.headers().size());
		Assert.assertEquals(4, view.logicalClauseCount());
		Assert.assertEquals("proof headers reuse one reverse-incidence domain", 2,
			union.retainedFactorOptionCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		CandidateRealizationSupportClause selected = view.select(List.of(a));
		Assert.assertSame(union.get(0), selected);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "multi-output"), union);
		Assert.assertTrue("native headers alone do not prove Physical tie-rank equivalence",
			realization.independentSupportProduct().isEmpty());
		Assert.assertTrue(realization.compactSupportRegions(ignored -> output).isEmpty());
		Assert.assertEquals("Physical eligibility must not expand additional members", 1,
			union.materializedHandleCount());
		Assert.assertTrue(view.authoritySignature().contains("seed-a"));
		Assert.assertTrue(view.authoritySignature().contains("seed-b"));

		Assert.assertTrue(first.multiHeaderUnion(relation(key("foreign"), pool("seed-c"),
			output, List.of(sorted(directLike(a), directLike(b))))).isEmpty());
		Assert.assertTrue(first.multiHeaderUnion(relation(owner, pool("seed-c"), output,
			List.of(List.of(directLike(a))))).isEmpty());
		CompiledHopKey foreignSource = cloneKey(source);
		Assert.assertTrue(first.multiHeaderUnion(relation(owner, pool("seed-c"), output,
			List.of(sorted(direct(0, "a", foreignSource), direct(0, "b", foreignSource))))).isEmpty());
		var differentOutput = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed-c"), pool("different-output"), true,
			List.of(sorted(directLike(a), directLike(b))));
		Assert.assertNotNull(differentOutput);
		Assert.assertTrue(first.multiHeaderUnion(new NativeContinuitySupportClauses(
			owner, differentOutput, output, true)).isEmpty());
		var dynamicRanges = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed-c"), output, false, List.of(sorted(directLike(a), directLike(b))));
		Assert.assertNotNull(dynamicRanges);
		Assert.assertTrue(first.multiHeaderUnion(new NativeContinuitySupportClauses(
			owner, dynamicRanges, output, true)).isEmpty());
	}

	@Test
	public void emissionMergeKeepsCommonAxisHeadersLazyAndAuthoritative() {
		CompiledHopKey owner = key("merge-consumer");
		CompiledHopKey source = key("merge-source");
		DurableAnchorKey output = pool("merge-output");
		var a = direct(0, "merge-a", source);
		var b = direct(0, "merge-b", source);
		NativeContinuitySupportClauses first = relation(owner, pool("merge-seed-a"), output,
			List.of(sorted(a, b)));
		NativeContinuitySupportClauses second = relation(owner, pool("merge-seed-b"), output,
			List.of(sorted(directLike(a), directLike(b))));
		PlacementRealizationKey realization = PlacementRealizationKey.nativeLineage(
			emission(), "merged-native-output");
		CandidateEmissionFact merged = new CandidateEmissionFact(emission(), FType.ROW, null,
			List.of(new CandidateEmissionRealization(realization, first),
				new CandidateEmissionRealization(realization, second)));
		Assert.assertEquals(1, merged.realizations().size());
		NativeContinuitySupportClauses relation = (NativeContinuitySupportClauses)
			merged.realizations().get(0).supportClauses();
		Assert.assertEquals(2, relation.headerCount());
		Assert.assertEquals(4, relation.size());
		Assert.assertEquals(0, relation.materializedHandleCount());
		Assert.assertSame(first.get(1), relation.get(identityOrdinal(relation, first.get(1))));
		Assert.assertSame(second.get(0), relation.get(identityOrdinal(relation, second.get(0))));
	}

	private static int identityOrdinal(NativeContinuitySupportClauses relation, Object clause) {
		for(int ordinal = 0; ordinal < relation.size(); ordinal++)
			if(relation.get(ordinal) == clause)
				return ordinal;
		return -1;
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, output, true);
	}

	private static List<CandidateRealizationSupportClause> explicit(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		enumerate(owner, seed, output, axes, 0, new ArrayList<>(), clauses);
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
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(List.copyOf(selected), "multi header reference");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, bindings).continuityProofKey(owner);
		clauses.add(new CandidateRealizationSupportClause(List.of(proof), bindings, output, true));
	}

	private static List<CandidateRealizationInputBinding> sorted(
		CandidateRealizationInputBinding... bindings) {
		return java.util.Arrays.stream(bindings).sorted(PlacementAnalysis.canonicalComparator()).toList();
	}
	private static CandidateRealizationInputBinding directLike(CandidateRealizationInputBinding binding) {
		return CandidateRealizationInputBinding.direct(binding.inputPosition(),
			new CandidateRealizationReference(binding.source().rule(), binding.source().realization()));
	}
	private static CandidateRealizationInputBinding direct(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		return CandidateRealizationInputBinding.direct(position,
			new CandidateRealizationReference(rule,
				PlacementRealizationKey.nativeLineage(emission(), "source-" + name)));
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
			"native-multi-header", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-multi-header", "main", name, "compiled",
			region, name, name);
	}
	private static CompiledHopKey cloneKey(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
