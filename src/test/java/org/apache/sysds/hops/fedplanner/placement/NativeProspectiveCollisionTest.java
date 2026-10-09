/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProspectiveCollisionTest {
	private static final PlacementEmissionState ROW_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void equalProspectiveDurableOutputsDoNotCollideWhenActualNativeKeysDiffer()
		throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("prospective-collision-source");
		CompiledHopKey consumerOwner = fixtureKey("prospective-collision-consumer");
		DurableAnchorKey narrow = pool("narrow-seed", 1);
		DurableAnchorKey wide = pool("wide-seed", 2);
		List<CandidateEmissionRealization> variants = List.of(
			dynamicSource(sourceOwner, narrow, "narrow-1"),
			dynamicSource(sourceOwner, narrow, "narrow-2"),
			dynamicSource(sourceOwner, wide, "wide-1"),
			dynamicSource(sourceOwner, wide, "wide-2"));
		CandidateRuleFact source = fact(new CandidateRuleKey(sourceOwner, List.of()), variants);
		CandidateRuleFact consumer = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("prospective-collision-source", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "prospective-collision-source",
			8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("prospective-collision-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		NodeShapeFact knownShape = new NodeShapeFact(DataType.MATRIX, 8, 2);

		DurableAnchorKey narrowProspective = nativeOutputAnchor(
			narrow, knownShape, consumerOwner);
		DurableAnchorKey wideProspective = nativeOutputAnchor(
			wide, knownShape, consumerOwner);
		Assert.assertEquals("ROW prospective output ignores the source's nonpartition extent",
			narrowProspective, wideProspective);

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false);
		Map<Object,PlacementProofKey> trustedProofs = new LinkedHashMap<>();
		CandidateRuleFact rebound = bind(closure, source, consumer, sourceHop, consumerHop,
			Map.of(narrow, 4, wide, 4), knownShape, trustedProofs);
		CandidateEmissionFact actual = rebound.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> actualNativeKeys = actual.realizations().stream()
			.filter(realization -> realization.key().layoutKind()
				== PlacementLayoutKind.NATIVE_LINEAGE).toList();
		Assert.assertEquals("actual dynamic publications must have two distinct native keys", 2,
			actualNativeKeys.stream().map(CandidateEmissionRealization::key)
				.collect(java.util.stream.Collectors.toSet()).size());
		Assert.assertEquals(8, trustedProofs.size());
		for(PlacementProofKey proof : trustedProofs.values())
			Assert.assertEquals("trusted generated query must remain dynamic", false,
				component(nativeDescriptor(proof), "exactPartitionRanges"));
		List<CandidateEmissionRealization> products = actual.realizations().stream()
			.filter(realization -> realization.supportClauses()
				instanceof NativeContinuitySupportClauses).toList();
		Assert.assertEquals("distinct actual native keys must admit both products", 2,
			products.size());
		Assert.assertEquals(2, products.stream().map(CandidateEmissionRealization::key)
			.collect(java.util.stream.Collectors.toSet()).size());
		Assert.assertTrue(products.stream().allMatch(realization ->
			realization.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE));
		Assert.assertEquals(Set.of(narrow, wide), products.stream().map(realization ->
			((NativeContinuitySupportClauses)realization.supportClauses())
				.product().externalSeed()).collect(java.util.stream.Collectors.toSet()));
		Set<PlacementIdentity.PlacementRealizationKey> expectedSourceKeys = variants.stream()
			.map(CandidateEmissionRealization::key).collect(java.util.stream.Collectors.toSet());
		for(CandidateEmissionRealization product : products) {
			NativeContinuitySupportClauses clauses =
				(NativeContinuitySupportClauses)product.supportClauses();
			Assert.assertFalse(clauses.product().exactPartitionRanges());
			Assert.assertEquals(4, clauses.size());
			Assert.assertEquals("publication admission must not materialize members", 0,
				clauses.materializedHandleCount());
			Assert.assertEquals("each seed product must retain all four dynamic source keys",
				expectedSourceKeys, clauses.product().axes().get(0).stream()
					.map(binding -> binding.source().realization())
					.collect(java.util.stream.Collectors.toSet()));
		}

		CandidateEmissionFact scalarReference = scalarReference(
			products, consumerOwner, narrowProspective);
		Assert.assertEquals("factored products must preserve the complete ordered scalar union",
			scalarReference.realizations(), actual.realizations());
		Set<PlacementProofKey> actualProofs = new java.util.HashSet<>();
		for(CandidateEmissionRealization realization : products)
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				Assert.assertSame(consumerOwner, clause.proofDependencies().get(0).owner());
				Assert.assertSame(sourceOwner,
					clause.inputBindings().get(0).source().rule().parentOccurrence());
				actualProofs.addAll(clause.proofDependencies());
			}
		Assert.assertEquals("published members must use the exact trusted query proof keys",
			Set.copyOf(trustedProofs.values()), actualProofs);
		CandidateRuleFact replay = bind(closure, source, rebound, sourceHop, consumerHop,
			Map.of(narrow, 4, wide, 4), knownShape, new LinkedHashMap<>());
		for(CandidateEmissionRealization retained : products)
			Assert.assertTrue("unchanged product replay must preserve exact realization identity",
				replay.allowedEmissionFacts().get(0).realizations().stream()
					.anyMatch(candidate -> candidate == retained));
		SearchSpaceMetrics.NativePublicationCount published = metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome() == SearchSpaceMetrics.NativePublicationOutcome.PUBLISHED)
			.findFirst().orElseThrow();
		Assert.assertEquals(2, published.queries());
		Assert.assertEquals(8, published.logicalProofs());
		Assert.assertEquals(0, published.consumedProofs());
		SearchSpaceMetrics.NativePublicationCount covered = metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome()
				== SearchSpaceMetrics.NativePublicationOutcome.COVERED_RETAINED)
			.findFirst().orElseThrow();
		Assert.assertEquals(2, covered.queries());
		Assert.assertEquals(8, covered.logicalProofs());
		Assert.assertEquals(0, covered.consumedProofs());
		Assert.assertEquals("neither publication nor exact replay may consume scalar members",
			0L, metrics.directBindingSnapshot().get(
				SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED).longValue());
	}

	private static CandidateEmissionFact scalarReference(
		List<CandidateEmissionRealization> products, CompiledHopKey owner,
		DurableAnchorKey prospectiveOutput) throws Exception {
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false);
		List<CandidateEmissionRealization> explicit = new ArrayList<>();
		for(CandidateEmissionRealization product : products) {
			NativeContinuitySupportClauses relation =
				(NativeContinuitySupportClauses)product.supportClauses();
			String lineage = product.key().nativeLineage();
			lineage = lineage.substring(0, lineage.lastIndexOf("|output-layout="));
			for(List<CandidateRealizationInputBinding> bindings : relation.product().axes().get(0).stream()
				.map(List::of).toList()) {
				NativePlacementContinuity.NativeContinuityProof proof =
					new NativePlacementContinuity.NativeContinuityProof(
						relation.product().externalSeed(),
						relation.product().outputWorkerPoolWitness(), false, bindings);
				explicit.add(referencePublication(closure, proof, owner,
					prospectiveOutput, lineage));
			}
		}
		return new CandidateEmissionFact(ROW_EMISSION, FType.ROW, null, explicit);
	}

	private static CandidateEmissionRealization referencePublication(
		PlacementRelationClosure closure,
		NativePlacementContinuity.NativeContinuityProof proof, CompiledHopKey owner,
		DurableAnchorKey outputAnchor, String lineage) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativePlacementContinuity.NativeContinuityProof.class, CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			ROW_EMISSION, outputAnchor, lineage, false);
	}

	private static CandidateRuleFact bind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, Map<DurableAnchorKey,Integer> widths,
		NodeShapeFact consumerShape, Map<Object,PlacementProofKey> trustedProofs)
		throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("bind",
			PlacementRelationClosure.class, CandidateRuleFact.class, CandidateRuleFact.class,
			DataOp.class, UnaryOp.class, Map.class, NodeShapeFact.class, Map.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, closure, source, consumer,
			sourceHop, consumerHop, widths, consumerShape, trustedProofs);
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, key, realizations);
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"consumerFact", CompiledHopKey.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, owner);
	}

	private static CompiledHopKey fixtureKey(String name) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fixtureKey", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static DurableAnchorKey nativeOutputAnchor(DurableAnchorKey seed,
		NodeShapeFact shape, CompiledHopKey owner) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("nativeOutputAnchor",
			DurableAnchorKey.class, FType.class, NodeShapeFact.class, CompiledHopKey.class);
		method.setAccessible(true);
		return (DurableAnchorKey)method.invoke(null, seed, FType.ROW, shape, owner);
	}

	private static Object nativeDescriptor(PlacementProofKey proof) throws Exception {
		var field = PlacementProofKey.class.getDeclaredField("nativeContinuityDescriptor");
		field.setAccessible(true);
		return field.get(proof);
	}

	private static Object component(Object record, String name) throws Exception {
		Method method = record.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(record);
	}

	private static CandidateEmissionRealization dynamicSource(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage) {
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "source-" + lineage);
		return CandidateEmissionRealization.nativeLineageDynamicLayout(
			ROW_EMISSION, lineage, pool, List.of(proof), List.of());
	}

	private static DurableAnchorKey pool(String id, long columns) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("same-worker", List.of(0L, 0L),
				List.of(8L, columns))));
	}
}
