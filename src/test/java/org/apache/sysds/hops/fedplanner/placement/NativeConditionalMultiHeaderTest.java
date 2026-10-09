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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
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

public class NativeConditionalMultiHeaderTest {
	@Test
	public void identicalConditionalDomainsKeepEachHeaderAndItsOriginalAuthority() {
		Fixture f = fixture("headers", 3, 3);
		var mask = List.of(f.axes.get(0).subList(0, 2), f.axes.get(1).subList(0, 2));
		var first = relation(f, "a", mask);
		var second = relation(f, "a-much-longer-header", mask);
		var union = first.multiHeaderUnion(second).orElseThrow();
		Assert.assertEquals(10, union.size());
		Assert.assertEquals(2, union.headerCount());
		Assert.assertEquals(0, first.materializedHandleCount() + second.materializedHandleCount());
		Assert.assertEquals(0, union.materializedHandleCount());
		int hash = union.hashCode();
		Assert.assertEquals(0, union.materializedHandleCount());
		checkExplicitAndDonors(f, mask, List.of(first, second), union);
		Assert.assertEquals(new ArrayList<>(union).hashCode(), hash);
		Assert.assertEquals(-1, union.ordinalOfExactAuthorityMember(first.product(),
			List.of(f.axes.get(0).get(0), f.axes.get(1).get(0))));
		Assert.assertSame(union, union.multiHeaderUnion(first).orElseThrow());
	}

	@Test
	public void fixedSeedThreeHeaderRelationsMatchExplicitAfterRestriction() {
		Random random = new Random(772031);
		for(int trial = 0; trial < 40; trial++) {
			Fixture f = fixture("random-" + trial, 2 + random.nextInt(3), 2 + random.nextInt(3));
			var mask = List.of(f.axes.get(0).subList(0, 1 + random.nextInt(f.axes.get(0).size() - 1)),
				f.axes.get(1).subList(0, 1 + random.nextInt(f.axes.get(1).size() - 1)));
			var donors = List.of(relation(f, "third", mask), relation(f, "first-long", mask),
				relation(f, "second-longer", mask));
			var union = NativeContinuitySupportClauses.unionSameAxesHeaderGroup(donors).orElseThrow();
			Assert.assertEquals(0, union.materializedHandleCount());
			checkExplicitAndDonors(f, mask, donors, union);
			var chosen = f.axes.get(0).get(0);
			var restricted = union.restrictBindings(binding -> binding.inputPosition() != 0
				|| binding == chosen).orElseThrow();
			Assert.assertEquals(0, restricted.materializedHandleCount());
			var expected = union.stream().filter(clause -> clause.inputBindings().get(0) == chosen).toList();
			Assert.assertEquals(expected, new ArrayList<>(restricted));
			for(int ordinal = 0; ordinal < restricted.size(); ordinal++)
				Assert.assertSame(expected.get(ordinal), restricted.get(ordinal));
			Assert.assertEquals(new ArrayList<>(restricted).hashCode(), restricted.hashCode());
			var outside = f.axes.get(0).get(f.axes.get(0).size() - 1);
			var ordinary = union.restrictBindings(binding -> binding.inputPosition() != 0
				|| binding == outside).orElseThrow();
			Assert.assertFalse(ordinary.conditionalComplement());
			Assert.assertEquals(3 * f.axes.get(1).size(), ordinary.size());
			Assert.assertEquals(0, ordinary.materializedHandleCount());
			var expectedOrdinary = union.stream().filter(clause -> clause.inputBindings().get(0) == outside).toList();
			Assert.assertEquals(expectedOrdinary, new ArrayList<>(ordinary));
			for(int ordinal = 0; ordinal < ordinary.size(); ordinal++)
				Assert.assertSame(expectedOrdinary.get(ordinal), ordinary.get(ordinal));
		}
	}

	@Test
	public void differentMasksAndForeignOwnersRetainExactFallback() {
		Fixture f = fixture("fallback", 3, 3);
		var mask = List.of(f.axes.get(0).subList(0, 2), f.axes.get(1).subList(0, 2));
		var first = relation(f, "first", mask);
		var otherMask = List.of(f.axes.get(0).subList(1, 3), f.axes.get(1).subList(0, 2));
		var second = relation(f, "second", otherMask);
		Assert.assertTrue(first.multiHeaderUnion(second).isEmpty());
		var foreign = NativeContinuitySupportClauses.exactComplement(key("foreign"),
			f.base, mask, f.output, true);
		Assert.assertTrue(first.multiHeaderUnion(foreign).isEmpty());
		Assert.assertEquals(0, first.materializedHandleCount() + second.materializedHandleCount()
			+ foreign.materializedHandleCount());
	}

	@Test
	public void equalDistinctSourceWrappersKeepEachHeaderOwnedMember() {
		Fixture f = fixture("rebuilt-headers", 3, 3);
		var clonedAxes = f.axes.stream().map(axis -> axis.stream().map(binding ->
			CandidateRealizationInputBinding.direct(binding.inputPosition(), new CandidateRealizationReference(
				new CandidateRuleKey(binding.source().rule().parentOccurrence(), binding.source().rule().orderedInputs()),
				binding.source().realization()))).toList()).toList();
		var mask = List.of(f.axes.get(0).subList(0, 2), f.axes.get(1).subList(0, 2));
		var clonedMask = List.of(clonedAxes.get(0).subList(0, 2), clonedAxes.get(1).subList(0, 2));
		var first = relation(f, "z-first", mask);
		var second = NativeContinuitySupportClauses.exactComplement(f.owner,
			NativePlacementContinuity.NativeSupportProduct.tryCreate(pool("a-second"), f.output, true, clonedAxes),
			clonedMask, f.output, true);
		var union = first.multiHeaderUnion(second).orElseThrow();
		Assert.assertEquals(0, union.materializedHandleCount());
		checkExplicitAndDonors(f, mask, List.of(first, second), union);
		var rightMember = second.get(0);
		var selected = union.get(union.ordinalOfExactAuthorityMember(second.product(), rightMember.inputBindings()));
		Assert.assertSame(rightMember, selected);
		Assert.assertTrue(clonedAxes.get(0).stream().anyMatch(binding -> binding == selected.inputBindings().get(0)));
		Assert.assertTrue(f.axes.get(0).stream().noneMatch(binding -> binding == selected.inputBindings().get(0)));
	}

	@Test
	public void sixtyFifthDistinctAuthorityKeepsBoundedFallbackWithoutMaterialization() {
		Fixture f = fixture("cap", 2, 2);
		var mask = List.of(List.of(f.axes.get(0).get(0)), List.of(f.axes.get(1).get(0)));
		var union = relation(f, "header-0", mask);
		for(int index = 1; index < 64; index++)
			union = union.multiHeaderUnion(relation(f, "header-" + index, mask)).orElseThrow();
		Assert.assertEquals(64, union.headerCount());
		Assert.assertEquals(192, union.size());
		var next = relation(f, "header-64", mask);
		Assert.assertTrue(union.multiHeaderUnion(next).isEmpty());
		Assert.assertEquals(0, union.materializedHandleCount() + next.materializedHandleCount());
	}

	private static NativeContinuitySupportClauses relation(Fixture f, String header,
		List<List<CandidateRealizationInputBinding>> mask) {
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool(header), f.output, true, f.axes);
		return NativeContinuitySupportClauses.exactComplement(f.owner, base, mask, f.output, true);
	}

	private static void checkExplicitAndDonors(Fixture f,
		List<List<CandidateRealizationInputBinding>> mask, List<NativeContinuitySupportClauses> donors,
		NativeContinuitySupportClauses union) {
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
		for(var donor : donors)
			for(var left : f.axes.get(0))
				for(var right : f.axes.get(1)) {
					if(mask.get(0).contains(left) && mask.get(1).contains(right))
						continue;
					var bindings = PlacementAnalysis.sharedAlreadyCanonicalComparableList(
						List.of(left, right), "explicit multiheader");
					var proof = new NativePlacementContinuity.NativeContinuityProof(
						donor.product().externalSeed(), f.output, true, bindings).continuityProofKey(f.owner);
					explicit.add(new CandidateRealizationSupportClause(List.of(proof), bindings, f.output, true));
				}
		explicit.sort(PlacementAnalysis.canonicalComparator());
		Assert.assertEquals(explicit, new ArrayList<>(union));
		Assert.assertEquals(explicit.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList(),
			union.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList());
		for(var donor : donors)
			for(int ordinal = 0; ordinal < donor.size(); ordinal++) {
				var clause = donor.get(ordinal);
				int unionOrdinal = union.ordinalOfExactAuthorityMember(donor.product(), clause.inputBindings());
				Assert.assertTrue(unionOrdinal >= 0);
				Assert.assertSame(clause, union.get(unionOrdinal));
			}
	}

	private static Fixture fixture(String id, int leftWidth, int rightWidth) {
		CompiledHopKey owner = key(id + "-owner");
		List<CompiledHopKey> sourceOwners = List.of(key(id + "-left"), key(id + "-right"));
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			axis(0, leftWidth, sourceOwners.get(0), id + "-l"),
			axis(1, rightWidth, sourceOwners.get(1), id + "-r"));
		DurableAnchorKey seed = pool(id + "-seed");
		DurableAnchorKey output = pool(id + "-output");
		var base = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, axes);
		return new Fixture(owner, sourceOwners, seed, output, axes, base);
	}

	private static List<CandidateRealizationInputBinding> axis(int position, int width,
		CompiledHopKey owner, String prefix) {
		List<CandidateRealizationInputBinding> axis = new ArrayList<>();
		for(int option = 0; option < width; option++)
			axis.add(direct(position, prefix + '-' + option + "x".repeat(option * 7), owner));
		axis.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(axis);
	}

	private static CandidateRealizationInputBinding direct(int position, String lineage,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(rule,
			PlacementRealizationKey.nativeLineage(emission(), lineage));
		return CandidateRealizationInputBinding.direct(position, reference);
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
			"native-conditional-union", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-conditional-union", "main", name,
			"compiled", region, name, name);
	}

	private record Fixture(CompiledHopKey owner, List<CompiledHopKey> sourceOwners,
		DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes,
		NativePlacementContinuity.NativeSupportProduct base) { }
}
