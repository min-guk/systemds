/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
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

public class NativeSupportHashMemoTest {
	@Test
	public void singleProductHashMatchesExplicitListAndWarmHashDoesNoHandleLookup()
		throws Exception {
		CompiledHopKey owner = key("single-owner");
		CompiledHopKey source = key("single-source");
		CandidateRealizationInputBinding a = binding(0, "a", source);
		CandidateRealizationInputBinding b = binding(0, "b", source);
		DurableAnchorKey seed = fullPool("single-seed");
		DurableAnchorKey output = rowPool("single-output");
		NativeContinuitySupportClauses relation = relation(owner, seed, output,
			List.of(sorted(a, b)));
		List<CandidateRealizationSupportClause> explicit = explicit(owner,
			List.of(seed), output, List.of(sorted(a, b)));
		CountingHandles handles = countedHandles(relation);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission(), "single-result"), relation);
		CandidateEmissionRealization explicitRealization = new CandidateEmissionRealization(
			realization.key(), explicit);

		Assert.assertEquals(explicit.hashCode(), relation.hashCode());
		Assert.assertTrue("cold hash must preserve the legacy member traversal", handles.gets > 0);
		Assert.assertEquals(explicit, relation);
		Assert.assertEquals(relation, explicit);
		Assert.assertEquals(explicitRealization.hashCode(), realization.hashCode());
		handles.reset();
		Assert.assertEquals(explicit.hashCode(), relation.hashCode());
		Assert.assertEquals("warm native-list hash must not revisit member handles", 0, handles.gets);
		handles.reset();
		Assert.assertEquals(explicitRealization.hashCode(), realization.hashCode());
		Assert.assertEquals("containing realization hash must reuse the native-list hash", 0, handles.gets);
	}

	@Test
	public void oneAxisUnionAndRestrictionKeepListHashAndDonorIdentity() throws Exception {
		CompiledHopKey owner = key("axis-owner");
		CompiledHopKey source = key("axis-source");
		CandidateRealizationInputBinding a = binding(0, "a", source);
		CandidateRealizationInputBinding b = binding(0, "b", source);
		CandidateRealizationInputBinding c = binding(0, "c", source);
		DurableAnchorKey seed = fullPool("axis-seed");
		DurableAnchorKey output = rowPool("axis-output");
		NativeContinuitySupportClauses left = relation(owner, seed, output,
			List.of(sorted(a, b)));
		CandidateRealizationInputBinding donorB = directLike(b);
		CandidateRealizationInputBinding donorC = directLike(c);
		NativeContinuitySupportClauses right = relation(owner, seed, output,
			List.of(sorted(donorB, donorC)));
		CandidateRealizationSupportClause firstB = left.get(
			left.product().ordinalOfExactAuthorityBindings(List.of(b)));
		CandidateRealizationSupportClause firstC = right.get(
			right.product().ordinalOfExactAuthorityBindings(List.of(donorC)));
		NativeContinuitySupportClauses union = left.oneAxisUnion(right).orElseThrow();
		List<CandidateRealizationSupportClause> explicit = explicit(owner,
			List.of(seed), output, List.of(sorted(a, b, c)));
		CountingHandles unionHandles = countedHandles(union);

		Assert.assertEquals(explicit.hashCode(), union.hashCode());
		Assert.assertEquals(explicit, union);
		Assert.assertSame(firstB, memberWithSource(union, b.source().realization()));
		Assert.assertSame(firstC, memberWithSource(union, c.source().realization()));
		unionHandles.reset();
		Assert.assertEquals(explicit.hashCode(), union.hashCode());
		Assert.assertEquals(0, unionHandles.gets);

		NativeContinuitySupportClauses restricted = union.restrictBindings(
			binding -> binding != a).orElseThrow();
		List<CandidateRealizationSupportClause> explicitRestricted = explicit(owner,
			List.of(seed), output, List.of(sorted(b, c)));
		CountingHandles restrictedHandles = countedHandles(restricted);
		Assert.assertEquals(explicitRestricted.hashCode(), restricted.hashCode());
		Assert.assertEquals(explicitRestricted, restricted);
		Assert.assertSame(firstB, memberWithSource(restricted, b.source().realization()));
		Assert.assertSame(firstC, memberWithSource(restricted, c.source().realization()));
		restrictedHandles.reset();
		Assert.assertEquals(explicitRestricted.hashCode(), restricted.hashCode());
		Assert.assertEquals(0, restrictedHandles.gets);
	}

	@Test
	public void multiHeaderHashMatchesExplicitUnionAndRetainsEverySeedDonor() throws Exception {
		CompiledHopKey owner = key("header-owner");
		CompiledHopKey source = key("header-source");
		CandidateRealizationInputBinding a = binding(0, "a", source);
		CandidateRealizationInputBinding b = binding(0, "b", source);
		List<List<CandidateRealizationInputBinding>> axes = List.of(sorted(a, b));
		DurableAnchorKey seedA = fullPool("header-seed-a");
		DurableAnchorKey seedB = fullPool("header-seed-b");
		DurableAnchorKey output = rowPool("header-output");
		NativeContinuitySupportClauses first = relation(owner, seedA, output, axes);
		NativeContinuitySupportClauses second = relation(owner, seedB, output, axes);
		CandidateRealizationSupportClause firstA = first.get(
			first.product().ordinalOfExactAuthorityBindings(List.of(a)));
		CandidateRealizationSupportClause secondB = second.get(
			second.product().ordinalOfExactAuthorityBindings(List.of(b)));
		NativeContinuitySupportClauses union = first.multiHeaderUnion(second).orElseThrow();
		List<CandidateRealizationSupportClause> explicit = explicit(owner,
			List.of(seedA, seedB), output, axes);
		CountingHandles handles = countedHandles(union);

		Assert.assertEquals(explicit.hashCode(), union.hashCode());
		Assert.assertEquals(explicit, union);
		Assert.assertTrue(union.stream().anyMatch(clause -> clause == firstA));
		Assert.assertTrue(union.stream().anyMatch(clause -> clause == secondB));
		handles.reset();
		Assert.assertEquals(explicit.hashCode(), union.hashCode());
		Assert.assertEquals("warm multi-header hash must use the same cached list hash", 0, handles.gets);
	}

	private static CandidateRealizationSupportClause memberWithSource(
		NativeContinuitySupportClauses relation, PlacementRealizationKey source) {
		return relation.stream().filter(clause -> clause.inputBindings().stream()
			.anyMatch(binding -> binding.source().realization().equals(source)))
			.findFirst().orElseThrow();
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
		List<DurableAnchorKey> seeds, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(DurableAnchorKey seed : seeds)
			enumerate(owner, seed, output, axes, 0, new ArrayList<>(), clauses);
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
				List.copyOf(selected), "native support hash reference");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, bindings).continuityProofKey(owner);
		clauses.add(new CandidateRealizationSupportClause(
			List.of(proof), bindings, output, true));
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

	@SuppressWarnings("unchecked")
	private static CountingHandles countedHandles(NativeContinuitySupportClauses relation)
		throws Exception {
		Field field = NativeContinuitySupportClauses.class.getDeclaredField("handles");
		field.setAccessible(true);
		ConcurrentHashMap<Integer,CandidateRealizationSupportClause> current =
			(ConcurrentHashMap<Integer,CandidateRealizationSupportClause>)field.get(relation);
		CountingHandles counted = new CountingHandles();
		counted.putAll(current);
		field.set(relation, counted);
		return counted;
	}

	private static PlacementEmissionState emission() {
		return new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	}

	private static DurableAnchorKey rowPool(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:9001", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static DurableAnchorKey fullPool(String id) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition("localhost:9002", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"native-support-hash", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-support-hash", "main", name, "compiled",
			region, name, name);
	}

	private static final class CountingHandles
		extends ConcurrentHashMap<Integer,CandidateRealizationSupportClause> {
		private int gets;
		@Override public CandidateRealizationSupportClause get(Object key) {
			gets++;
			return super.get(key);
		}
		private void reset() { gets = 0; }
	}
}
