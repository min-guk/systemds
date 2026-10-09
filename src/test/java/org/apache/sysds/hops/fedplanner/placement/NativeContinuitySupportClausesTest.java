/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeContinuitySupportClausesTest {
	@Test
	public void rectangularProductRestoresEveryExactMemberLazilyInCanonicalOrder() {
		CompiledHopKey owner = key("owner");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		CompiledHopKey leftOwner = key("left");
		CompiledHopKey rightOwner = key("right");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(0, "left-a", leftOwner), direct(0, "left-b", leftOwner)),
			List.of(direct(1, "right-a", rightOwner), direct(1, "right-b", rightOwner),
				direct(1, "right-c", rightOwner)));
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		NativeContinuitySupportClauses relation =
			new NativeContinuitySupportClauses(owner, product, output, true);
		Assert.assertEquals(6, relation.size());
		Assert.assertEquals(0, relation.materializedHandleCount());

		List<CandidateRealizationSupportClause> explicit =
			explicitClauses(owner, seed, output, axes);
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
		Assert.assertEquals(6, relation.materializedHandleCount());
	}

	@Test
	public void selectedMemberNeverBorrowsFirstMembersProofAuthority() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var first = direct(0, "a", sourceOwner);
		var selected = direct(0, "b", sourceOwner);
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(first, selected)));
		var relation = new NativeContinuitySupportClauses(owner, product, output, true);

		CandidateRealizationSupportClause clause = relation.get(1);
		Assert.assertSame(selected.source(), clause.inputBindings().get(0).source());
		Assert.assertTrue(clause.proofDependencies().get(0).authoritySignature()
			.contains(selected.source().normalizedSignature()));
		Assert.assertFalse(clause.proofDependencies().get(0).authoritySignature()
			.contains(first.source().normalizedSignature()));
		Assert.assertEquals(1, relation.materializedHandleCount());
	}

	@Test
	public void factoryRejectsCorrelatedMixedUnorderedAndStagingAxes() {
		CompiledHopKey shared = key("shared");
		CompiledHopKey other = key("other");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var a = direct(0, "a", shared);
		var b = direct(0, "b", shared);
		var c = direct(1, "c", shared);
		var d = direct(1, "d", other);

		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of()));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(a, b), List.of(c))));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(b, a), List.of(d))));
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(d), List.of(a))));
	}

	@Test
	public void exactAuthorityKeepsEqualButForeignOwnerIdentitiesDistinct() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey foreignOwner = cloneKey(owner);
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(direct(0, "a", sourceOwner))));
		var retained = new NativeContinuitySupportClauses(owner, product, output, true);
		var foreign = new NativeContinuitySupportClauses(foreignOwner, product, output, true);
		Assert.assertEquals(owner, foreignOwner);
		Assert.assertNotSame(owner, foreignOwner);
		Assert.assertFalse(retained.sameExactAuthority(foreign));
	}

	@Test
	public void exactAuthorityKeepsEqualButForeignSourceOwnerIdentitiesDistinct() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey sourceOwner = key("source");
		CompiledHopKey foreignSourceOwner = cloneKey(sourceOwner);
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var retainedProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(direct(0, "a", sourceOwner))));
		var foreignProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(
			seed, output, true, List.of(List.of(direct(0, "a", foreignSourceOwner))));
		Assert.assertNotNull(retainedProduct);
		Assert.assertNotNull(foreignProduct);
		Assert.assertFalse(new NativeContinuitySupportClauses(owner, retainedProduct, output, true)
			.sameExactAuthority(new NativeContinuitySupportClauses(owner, foreignProduct, output, true)));
	}

	@Test
	public void oversizedOrderedProductFailsBeforeCardinalityOverflow() {
		List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
		for(int position = 0; position < 31; position++) {
			CompiledHopKey sourceOwner = key("source-" + position);
			axes.add(List.of(direct(position, "a", sourceOwner),
				direct(position, "b", sourceOwner)));
		}
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, axes));
	}

	@Test
	public void multiDigitInputPositionsRetainExplicitCanonicalOrder() {
		CompiledHopKey owner = key("owner");
		CompiledHopKey left = key("position-2");
		CompiledHopKey right = key("position-10");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			List.of(direct(2, "a", left), direct(2, "b", left)),
			List.of(direct(10, "a", right), direct(10, "b", right)));
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true, axes);
		Assert.assertNotNull(product);
		var relation = new NativeContinuitySupportClauses(owner, product, output, true);
		List<CandidateRealizationSupportClause> explicit =
			explicitClauses(owner, seed, output, axes);
		for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
			Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
				relation.get(ordinal).normalizedSignature());
	}

	@Test
	public void fixedSeedRandomRectanglesMatchIndependentNestedEnumeration() {
		Random random = new Random(0x5eedL);
		for(int iteration = 0; iteration < 32; iteration++) {
			CompiledHopKey owner = key("owner-" + iteration);
			DurableAnchorKey seed = pool("seed-" + iteration);
			DurableAnchorKey output = pool("output-" + iteration);
			List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
			for(int position = 0; position < 1 + random.nextInt(4); position++) {
				CompiledHopKey sourceOwner = key("source-" + iteration + '-' + position);
				List<CandidateRealizationInputBinding> options = new ArrayList<>();
				for(int option = 0; option < 1 + random.nextInt(4); option++)
					options.add(direct(position, "option-" + option, sourceOwner));
				Collections.shuffle(options, random);
				options.sort(PlacementAnalysis.canonicalComparator());
				axes.add(List.copyOf(options));
			}
			var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, true, axes);
			Assert.assertNotNull(product);
			var relation = new NativeContinuitySupportClauses(owner, product, output, true);
			List<CandidateRealizationSupportClause> explicit =
				explicitClauses(owner, seed, output, axes);
			Assert.assertEquals(explicit.size(), relation.size());
			for(int ordinal = 0; ordinal < explicit.size(); ordinal++)
				Assert.assertEquals(explicit.get(ordinal).normalizedSignature(),
					relation.get(ordinal).normalizedSignature());
		}
	}

	@Test
	public void variableLengthBindingAuthorityFallsBackToExplicitClauses() {
		CompiledHopKey leftOwner = key("left-source");
		CompiledHopKey rightOwner = key("right-source");
		Assert.assertNull(NativePlacementContinuity.NativeSupportProduct.tryCreate(
			pool("seed"), pool("output"), true, List.of(
				List.of(direct(0, "left-a", leftOwner), direct(0, "left-b", leftOwner)),
				List.of(direct(1, "short", rightOwner),
					direct(1, "a-much-longer-source-lineage", rightOwner)))));
	}

	private static List<CandidateRealizationSupportClause> explicitClauses(
		CompiledHopKey owner, DurableAnchorKey seed, DurableAnchorKey output,
		List<List<CandidateRealizationInputBinding>> axes) {
		List<CandidateRealizationSupportClause> explicit = new ArrayList<>();
		enumerateExplicit(owner, seed, output, axes, 0, new ArrayList<>(), explicit);
		explicit.sort(PlacementAnalysis.canonicalComparator());
		return explicit;
	}

	private static void enumerateExplicit(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey output, List<List<CandidateRealizationInputBinding>> axes,
		int axis, List<CandidateRealizationInputBinding> selected,
		List<CandidateRealizationSupportClause> explicit) {
		if(axis < axes.size()) {
			for(CandidateRealizationInputBinding option : axes.get(axis)) {
				selected.add(option);
				enumerateExplicit(owner, seed, output, axes, axis + 1, selected, explicit);
				selected.remove(selected.size() - 1);
			}
			return;
		}
		List<CandidateRealizationInputBinding> bindings =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				List.copyOf(selected), "independent explicit binding");
		var proof = new NativePlacementContinuity.NativeContinuityProof(
			seed, output, true, bindings).continuityProofKey(owner);
		explicit.add(new CandidateRealizationSupportClause(
			List.of(proof), bindings, output, true));
	}

	@Test
	public void candidateConstructionAndMetadataDoNotMaterializeMembers() {
		CompiledHopKey owner = key("consumer");
		CompiledHopKey sourceOwner = key("source");
		DurableAnchorKey seed = pool("seed");
		DurableAnchorKey output = pool("output");
		var product = NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, output, true,
			List.of(List.of(direct(0, "a", sourceOwner), direct(0, "b", sourceOwner))));
		var clauses = new NativeContinuitySupportClauses(owner, product, output, true);
		PlacementEmissionState emission = emission();
		var realization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission, "native-product"), clauses);
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(2,
			realization.nativeContinuitySupportProduct().orElseThrow().logicalClauseCount());
		Assert.assertEquals(0, realization.fullyMaterializedSupportClauseCount());
	}

	private static CandidateRealizationInputBinding direct(int position, String name,
		CompiledHopKey owner) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(rule,
			PlacementRealizationKey.nativeLineage(emission(), "source-" + name));
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
			"native-product", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("native-product", "main", name, "compiled",
			region, name, name);
	}

	private static CompiledHopKey cloneKey(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance(), key.canonicalSourceOrigin());
	}
}
