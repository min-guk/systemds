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

public class NativeSameAxesSeedUnionTest {
	@Test
	public void candidateMergeAndRebindCompressDistinctFullSeedsWithExactAuthority() {
		Fixture fixture = fixture();
		Assert.assertEquals(0, fixture.seedA.materializedHandleCount());
		Assert.assertEquals(0, fixture.seedB.materializedHandleCount());
		Assert.assertEquals(0, fixture.seedC.materializedHandleCount());
		Assert.assertEquals(0, fixture.duplicateA.materializedHandleCount());

		int firstOrdinal = fixture.seedA.product()
			.ordinalOfExactAuthorityBindings(List.of(fixture.a, fixture.c));
		CandidateRealizationSupportClause firstAuthority = fixture.seedA.get(firstOrdinal);
		Assert.assertEquals(1, fixture.seedA.materializedHandleCount());
		CandidateEmissionFact firstWave = merge(fixture.outputKey,
			fixture.seedA, fixture.seedB);
		Assert.assertEquals(1, firstWave.realizations().size());
		Assert.assertTrue("same axes with distinct full seeds must remain relation-native",
			firstWave.realizations().get(0).supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(8, firstWave.realizations().get(0).supportClauses().size());
		Assert.assertEquals(1, fixture.seedA.materializedHandleCount());
		Assert.assertEquals("merge must not enumerate a new seed donor", 0,
			fixture.seedB.materializedHandleCount());

		CandidateEmissionFact rebound = new CandidateEmissionFact(emission(), FType.ROW, null, List.of(
			firstWave.realizations().get(0),
			new CandidateEmissionRealization(fixture.outputKey, fixture.seedC),
			new CandidateEmissionRealization(fixture.outputKey, fixture.duplicateA)));
		Assert.assertEquals(1, rebound.realizations().size());
		Assert.assertTrue(rebound.realizations().get(0).supportClauses()
			instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses family = (NativeContinuitySupportClauses)
			rebound.realizations().get(0).supportClauses();
		Assert.assertEquals("duplicate full seed authority is deduplicated", 12, family.size());
		Assert.assertEquals(1, fixture.seedA.materializedHandleCount());
		Assert.assertEquals(0, fixture.seedB.materializedHandleCount());
		Assert.assertEquals(0, fixture.seedC.materializedHandleCount());
		Assert.assertEquals(0, fixture.duplicateA.materializedHandleCount());

		NativeContinuitySupportClauses restricted = family.restrictBindings(
			binding -> binding != fixture.a).orElseThrow();
		Assert.assertEquals("restriction keeps every seed and removes one option from one axis",
			6, restricted.size());
		Assert.assertEquals(1, fixture.seedA.materializedHandleCount());
		Assert.assertEquals(0, fixture.seedB.materializedHandleCount());
		Assert.assertEquals(0, fixture.seedC.materializedHandleCount());
		Assert.assertEquals(0, fixture.duplicateA.materializedHandleCount());

		List<CandidateRealizationSupportClause> expected = explicit(fixture.owner,
			List.of(fixture.fullSeedA, fixture.fullSeedB, fixture.fullSeedC), fixture.proofOutput,
			List.of(sorted(fixture.a, fixture.b), sorted(fixture.c, fixture.d)));
		List<CandidateRealizationSupportClause> actual = List.copyOf(family);
		Assert.assertEquals(expected.stream()
			.map(CandidateRealizationSupportClause::normalizedSignature).toList(), actual.stream()
			.map(CandidateRealizationSupportClause::normalizedSignature).toList());
		CandidateRealizationSupportClause retainedFirst = actual.stream()
			.filter(clause -> clause.normalizedSignature().equals(firstAuthority.normalizedSignature()))
			.findFirst().orElseThrow();
		Assert.assertSame("the first seed donor retains exact clause authority",
			firstAuthority, retainedFirst);

		List<CandidateRealizationSupportClause> expectedRestricted = explicit(fixture.owner,
			List.of(fixture.fullSeedA, fixture.fullSeedB, fixture.fullSeedC), fixture.proofOutput,
			List.of(List.of(fixture.b), sorted(fixture.c, fixture.d)));
		Assert.assertEquals(expectedRestricted.stream()
			.map(CandidateRealizationSupportClause::normalizedSignature).toList(), restricted.stream()
			.map(CandidateRealizationSupportClause::normalizedSignature).toList());
	}

	@Test
	public void compressionAcceptsEqualOwnerAuthorityButRejectsForeignOwnerIdentity() {
		Fixture fixture = fixture();
		CandidateRealizationInputBinding clonedA = directLike(fixture.a);
		CandidateRealizationInputBinding clonedB = directLike(fixture.b);
		CandidateRealizationInputBinding clonedC = directLike(fixture.c);
		CandidateRealizationInputBinding clonedD = directLike(fixture.d);
		List<List<CandidateRealizationInputBinding>> clonedAxes = List.of(
			sorted(clonedA, clonedB), sorted(clonedC, clonedD));
		Assert.assertEquals(fixture.a, clonedA);
		Assert.assertNotSame(fixture.a, clonedA);
		NativeContinuitySupportClauses clonedBindings = relation(fixture.owner,
			fixture.fullSeedB, fixture.proofOutput, clonedAxes);
		CandidateRealizationSupportClause firstA = fixture.seedA.get(
			fixture.seedA.product().ordinalOfExactAuthorityBindings(List.of(fixture.b, fixture.d)));
		CandidateRealizationSupportClause firstB = clonedBindings.get(
			clonedBindings.product().ordinalOfExactAuthorityBindings(List.of(clonedB, clonedD)));
		CandidateEmissionFact equalOwnerUnion = merge(fixture.outputKey,
			fixture.seedA, clonedBindings);
		Assert.assertTrue("equal-distinct bindings with identical source-owner authority are safe",
			equalOwnerUnion.realizations().get(0).supportClauses()
				instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses family = (NativeContinuitySupportClauses)
			equalOwnerUnion.realizations().get(0).supportClauses();
		Assert.assertEquals(8, family.size());
		Assert.assertTrue(family.stream().anyMatch(clause -> clause == firstA));
		CandidateRealizationSupportClause retainedB = family.stream()
			.filter(clause -> clause == firstB).findFirst().orElseThrow();
		Assert.assertSame("the second seed retains its donor-local binding authority",
			clonedB, retainedB.inputBindings().get(0));
		Assert.assertSame(clonedD, retainedB.inputBindings().get(1));

		NativeContinuitySupportClauses restricted = family.restrictBindings(
			binding -> binding != fixture.a).orElseThrow();
		Assert.assertEquals(4, restricted.size());
		Assert.assertTrue("identity-sensitive restriction must preserve the equal-owner donor seed",
			restricted.stream().anyMatch(clause -> clause == firstB));

		CompiledHopKey foreignOwner = cloneKey(fixture.owner);
		NativeContinuitySupportClauses foreign = relation(foreignOwner,
			fixture.fullSeedB, fixture.proofOutput,
			List.of(sorted(fixture.a, fixture.b), sorted(fixture.c, fixture.d)));
		CandidateEmissionFact ownerMismatch = merge(fixture.outputKey, fixture.seedA, foreign);
		Assert.assertFalse("structurally equal owner values cannot replace exact owner identity",
			ownerMismatch.realizations().get(0).supportClauses()
				instanceof NativeContinuitySupportClauses);
	}

	private static Fixture fixture() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey firstSource = key("source-0");
		CompiledHopKey secondSource = key("source-1");
		CandidateRealizationInputBinding a = binding(0, "2", firstSource);
		CandidateRealizationInputBinding b = binding(0,
			"10-long-variable-boundary-ß", firstSource);
		CandidateRealizationInputBinding c = binding(1, "9", secondSource);
		CandidateRealizationInputBinding d = binding(1,
			"100-unicode-東京", secondSource);
		List<List<CandidateRealizationInputBinding>> axes =
			List.of(sorted(a, b), sorted(c, d));
		DurableAnchorKey seedA = fullPool("seed-9", "localhost:8901");
		DurableAnchorKey seedB = fullPool("seed-10", "localhost:8901");
		DurableAnchorKey seedC = fullPool("seed-100-東京", "localhost:8901");
		DurableAnchorKey proofOutput = rowPool("proof-output", "localhost:8902");
		DurableAnchorKey clauseOutput = rowPool("clause-output", "localhost:8903");
		PlacementRealizationKey outputKey =
			PlacementRealizationKey.nativeLineage(emission(), "same-output");
		return new Fixture(owner, a, b, c, d, seedA, seedB, seedC, proofOutput, outputKey,
			relation(owner, seedA, proofOutput, clauseOutput, axes),
			relation(owner, seedB, proofOutput, clauseOutput, axes),
			relation(owner, seedC, proofOutput, clauseOutput, axes),
			relation(owner, fullPool("seed-9", "localhost:8901"), proofOutput,
				clauseOutput, axes));
	}

	private static CandidateEmissionFact merge(PlacementRealizationKey outputKey,
		NativeContinuitySupportClauses... relations) {
		return new CandidateEmissionFact(emission(), FType.ROW, null,
			java.util.Arrays.stream(relations)
				.map(relation -> new CandidateEmissionRealization(outputKey, relation)).toList());
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey proofOutput,
		List<List<CandidateRealizationInputBinding>> axes) {
		return relation(owner, seed, proofOutput,
			rowPool("clause-output", "localhost:8903"), axes);
	}

	private static NativeContinuitySupportClauses relation(CompiledHopKey owner,
		DurableAnchorKey seed, DurableAnchorKey proofOutput, DurableAnchorKey clauseOutput,
		List<List<CandidateRealizationInputBinding>> axes) {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, proofOutput, true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(owner, product, clauseOutput, true);
	}

	private static List<CandidateRealizationSupportClause> explicit(CompiledHopKey owner,
		List<DurableAnchorKey> seeds, DurableAnchorKey proofOutput,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(DurableAnchorKey seed : seeds)
			enumerate(owner, seed, proofOutput, axes, 0, new ArrayList<>(), clauses);
		clauses.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(clauses);
	}

	private static void enumerate(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey proofOutput, List<List<CandidateRealizationInputBinding>> axes,
		int axis, List<CandidateRealizationInputBinding> selected,
		List<CandidateRealizationSupportClause> clauses) {
		if(axis < axes.size()) {
			for(CandidateRealizationInputBinding option : axes.get(axis)) {
				selected.add(option);
				enumerate(owner, seed, proofOutput, axes, axis + 1, selected, clauses);
				selected.remove(selected.size() - 1);
			}
			return;
		}
		List<CandidateRealizationInputBinding> bindings =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				List.copyOf(selected), "same-axes seed reference");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, proofOutput, true, bindings).continuityProofKey(owner);
		clauses.add(new CandidateRealizationSupportClause(List.of(proof), bindings,
			rowPool("clause-output", "localhost:8903"), true));
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

	private static DurableAnchorKey rowPool(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static DurableAnchorKey fullPool(String id, String worker) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"same-axes-seed-union", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("same-axes-seed-union", "main", name, "compiled",
			region, name, name);
	}

	private static CompiledHopKey cloneKey(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}

	private record Fixture(CompiledHopKey owner,
		CandidateRealizationInputBinding a, CandidateRealizationInputBinding b,
		CandidateRealizationInputBinding c, CandidateRealizationInputBinding d,
		DurableAnchorKey fullSeedA, DurableAnchorKey fullSeedB, DurableAnchorKey fullSeedC,
		DurableAnchorKey proofOutput, PlacementRealizationKey outputKey,
		NativeContinuitySupportClauses seedA, NativeContinuitySupportClauses seedB,
		NativeContinuitySupportClauses seedC, NativeContinuitySupportClauses duplicateA) { }
}
