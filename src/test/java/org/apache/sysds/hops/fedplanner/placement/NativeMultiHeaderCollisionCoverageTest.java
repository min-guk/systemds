/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeMultiHeaderCollisionCoverageTest {
	private static final PlacementEmissionState ROW_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void filteredNonRequiredOptionCannotPassLosslessPublicationCertificate() throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("lossless-collision-source");
		CompiledHopKey consumerOwner = fixtureKey("lossless-collision-consumer");
		DurableAnchorKey sourcePool = pool("source-pool", 2);
		CandidateEmissionRealization first = exactSource(sourceOwner, sourcePool, "first");
		CandidateEmissionRealization second = exactSource(sourceOwner, sourcePool, "second");
		CandidateEmissionRealization dead = CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, "dead-non-required-staging", List.of(), List.of());
		CandidateRuleFact source = fact(new CandidateRuleKey(sourceOwner, List.of()),
			List.of(first, second, dead));
		List<CandidateRealizationInputBinding> axis = List.of(first, second, dead).stream()
			.map(realization -> CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(source.key(), realization))).toList();
		DurableAnchorKey firstSeed = pool("seed-a", 1);
		DurableAnchorKey secondSeed = pool("seed-b", 5);
		DurableAnchorKey proofOutput = pool("proof-output", 2);
		DurableAnchorKey collidingOutput = pool("same-durable-output", 2);
		NativePlacementContinuity.NativeSupportProduct firstProduct = product(
			firstSeed, proofOutput, axis);
		NativePlacementContinuity.NativeSupportProduct secondProduct = product(
			secondSeed, proofOutput, axis);
		Object sources = directSources(List.of(source));
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false);
		CandidateEmissionRealization firstFiltered = productPublication(closure,
			firstProduct, consumerOwner, collidingOutput, "collision-a", sources);
		CandidateEmissionRealization secondFiltered = productPublication(closure,
			secondProduct, consumerOwner, collidingOutput, "collision-b", sources);

		Assert.assertEquals(3, firstProduct.size());
		Assert.assertEquals(3, secondProduct.size());
		Assert.assertEquals("the preparation helper demonstrably filtered one non-required option",
			2, firstFiltered.supportClauses().size());
		Assert.assertEquals(2, secondFiltered.supportClauses().size());
		Assert.assertEquals(firstFiltered.key(), secondFiltered.key());
		Assert.assertTrue(firstFiltered.supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertTrue(secondFiltered.supportClauses() instanceof NativeContinuitySupportClauses);

		CandidateEmissionFact filteredCollision = new CandidateEmissionFact(
			ROW_EMISSION, FType.ROW, null, List.of(firstFiltered, secondFiltered));
		CandidateEmissionFact frozenScalarReference = scalarReference(closure, consumerOwner,
			collidingOutput, List.of(firstProduct, secondProduct), dead.key());
		Assert.assertEquals(4, filteredCollision.realizations().stream()
			.mapToInt(realization -> realization.supportClauses().size()).sum());
		Assert.assertEquals(6, frozenScalarReference.realizations().stream()
			.mapToInt(realization -> realization.supportClauses().size()).sum());
		Assert.assertEquals("each seed retains its omitted dead binding as a native scalar receipt", 2,
			frozenScalarReference.realizations().stream()
				.flatMap(realization -> realization.supportClauses().stream())
				.filter(clause -> clause.inputBindings().get(0).source().realization().equals(dead.key()))
				.count());
		Assert.assertFalse(filteredCollision.realizations().stream()
			.flatMap(realization -> realization.supportClauses().stream())
			.anyMatch(clause -> clause.inputBindings().get(0).source().realization().equals(dead.key())));
		Assert.assertNotEquals("filtered descriptors are not a lossless replacement for full scalar support",
			frozenScalarReference.realizations(), filteredCollision.realizations());
		Assert.assertFalse("a collision may compress only when every original member is represented",
			isLossless(List.of(firstFiltered), firstProduct));
		Assert.assertFalse("each colliding header needs its own lossless coverage certificate",
			isLossless(List.of(secondFiltered), secondProduct));

		NativePlacementContinuity.NativeSupportProduct liveOnly = product(firstSeed,
			proofOutput, axis.subList(0, 2));
		Assert.assertTrue("the exact complete subset remains a valid full-query certificate",
			isLossless(List.of(firstFiltered), liveOnly));

		CandidateEmissionRealization dynamic = CandidateEmissionRealization.nativeLineageDynamicLayout(
			ROW_EMISSION, "dynamic-live", sourcePool, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, sourceOwner, "source-dynamic-live")), List.of());
		CandidateRuleFact mixedSource = fact(new CandidateRuleKey(sourceOwner, List.of()),
			List.of(first, dynamic));
		List<CandidateRealizationInputBinding> mixedAxis = List.of(first, dynamic).stream()
			.map(realization -> CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(mixedSource.key(), realization))).toList();
		NativePlacementContinuity.NativeSupportProduct mixedProduct = product(
			firstSeed, proofOutput, mixedAxis);
		List<CandidateEmissionRealization> split = productPublications(closure, mixedProduct,
			consumerOwner, collidingOutput, "mixed-lossless", directSources(List.of(mixedSource)));
		Assert.assertEquals("one mixed axis prepares two disjoint publication parts", 2, split.size());
		Assert.assertEquals(2, split.stream()
			.mapToInt(realization -> realization.supportClauses().size()).sum());
		Assert.assertTrue("both parts together exactly cover the original mixed product",
			isLossless(split, mixedProduct));
	}

	private static NativePlacementContinuity.NativeSupportProduct product(
		DurableAnchorKey seed, DurableAnchorKey output,
		List<CandidateRealizationInputBinding> axis) {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, output, true, List.of(axis));
		Assert.assertNotNull(product);
		return product;
	}

	@SuppressWarnings("unchecked")
	private static CandidateEmissionRealization productPublication(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey output, String lineage, Object sources)
		throws Exception {
		List<CandidateEmissionRealization> publications = productPublications(
			closure, product, owner, output, lineage, sources);
		Assert.assertEquals(1, publications.size());
		return publications.get(0);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> productPublications(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey output, String lineage, Object sources)
		throws Exception {
		Class<?> trace = Class.forName(
			PlacementRelationClosure.class.getName() + "$NativePublicationTrace");
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativeProductPublication", product.getClass(), CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, List.class,
			sources.getClass(), Map.class, trace);
		method.setAccessible(true);
		List<CandidateEmissionRealization> publications =
			(List<CandidateEmissionRealization>)method.invoke(closure, product, owner,
				ROW_EMISSION, output, lineage, List.of(), sources,
				new IdentityHashMap<CandidateEmissionRealization,Boolean>(), null);
		Assert.assertNotNull(publications);
		return publications;
	}

	private static CandidateEmissionFact scalarReference(PlacementRelationClosure closure,
		CompiledHopKey owner, DurableAnchorKey output,
		List<NativePlacementContinuity.NativeSupportProduct> products,
		PlacementIdentity.PlacementRealizationKey deadSource) throws Exception {
		Method publication = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativePublication", NativePlacementContinuity.NativeContinuityProof.class,
			CompiledHopKey.class, PlacementEmissionState.class, DurableAnchorKey.class,
			String.class, boolean.class);
		publication.setAccessible(true);
		List<CandidateEmissionRealization> members = new ArrayList<>();
		for(NativePlacementContinuity.NativeSupportProduct product : products)
			for(int ordinal = 0; ordinal < product.size(); ordinal++) {
				var proof = new NativePlacementContinuity.NativeContinuityProof(
					product.externalSeed(), product.outputWorkerPoolWitness(),
					product.exactPartitionRanges(), product.bindingsAt(ordinal));
				members.add((CandidateEmissionRealization)publication.invoke(closure,
					proof, owner, ROW_EMISSION, output, "scalar-reference",
					product.bindingsAt(ordinal).stream()
						.noneMatch(binding -> binding.source().realization().equals(deadSource))));
			}
		return new CandidateEmissionFact(ROW_EMISSION, FType.ROW, null, members);
	}

	private static boolean isLossless(List<CandidateEmissionRealization> publications,
		NativePlacementContinuity.NativeSupportProduct original) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"nativeProductPublicationIsLossless", List.class, original.getClass());
		method.setAccessible(true);
		return (boolean)method.invoke(null, publications, original);
	}

	private static Object directSources(List<CandidateRuleFact> facts) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectSourceIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, key, realizations);
	}

	private static CompiledHopKey fixtureKey(String name) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fixtureKey", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static CandidateEmissionRealization exactSource(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage) {
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "source-" + lineage);
		return CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, lineage, pool, List.of(proof), List.of());
	}

	private static DurableAnchorKey pool(String id, long columns) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("shared-worker", List.of(0L, 0L), List.of(8L, columns))));
	}
}
