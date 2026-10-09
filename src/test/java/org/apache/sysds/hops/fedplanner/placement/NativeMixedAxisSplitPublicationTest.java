/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeMixedAxisSplitPublicationTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"native-mixed-split", "main", List.of("root"), "call", "compiled");
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void oneMixedAxisSplitsIntoExactDisjointProductsWithScalarAuthorityParity()
		throws Exception {
		CompiledHopKey consumer = key("consumer");
		Axis mixed = axis(key("mixed-source"), 0, "mixed", 2, 2);
		Axis uniform = axis(key("uniform-source"), 1, "uniform", 2, 0);
		DurableAnchorKey seed = anchor("seed");
		DurableAnchorKey proofOutput = anchor("proof-output");
		DurableAnchorKey durableOutput = anchor("durable-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			mixed.bindings(), uniform.exactBindings());
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, proofOutput, true, axes);
		Assert.assertNotNull(product);
		Assert.assertEquals(8, product.size());

		PlacementRelationClosure closure = closure();
		List<CandidateEmissionRealization> publications = pluralPublication(closure, product,
			consumer, durableOutput, "mixed-split", directSources(List.of(mixed.fact(), uniform.fact())));
		Assert.assertNotNull(publications);
		Assert.assertEquals(2, publications.size());
		Assert.assertEquals(Set.of(PlacementLayoutKind.DURABLE_MAP, PlacementLayoutKind.NATIVE_LINEAGE),
			publications.stream().map(value -> value.key().layoutKind())
				.collect(java.util.stream.Collectors.toSet()));
		Assert.assertEquals(product.size(), publications.stream()
			.mapToInt(value -> value.supportClauses().size()).sum());
		for(CandidateEmissionRealization publication : publications) {
			Assert.assertTrue(publication.supportClauses() instanceof NativeContinuitySupportClauses);
			Assert.assertEquals(0, publication.fullyMaterializedSupportClauseCount());
		}

		List<CandidateEmissionRealization> explicit = new ArrayList<>();
		for(int ordinal = 0; ordinal < product.size(); ordinal++) {
			List<CandidateRealizationInputBinding> bindings = product.bindingsAt(ordinal);
			boolean exact = mixed.exactBindings().stream().anyMatch(
				binding -> binding == bindings.get(0));
			NativeContinuityProof proof = new NativeContinuityProof(seed, proofOutput, true, bindings);
			explicit.add(scalarPublication(closure, proof, consumer, durableOutput,
				"mixed-split", exact));
		}
		CandidateEmissionFact expected = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, explicit);
		CandidateEmissionFact actual = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, publications);
		Assert.assertEquals(expected.realizations(), actual.realizations());
		for(CandidateEmissionRealization realization : actual.realizations())
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				Assert.assertSame(consumer, clause.proofDependencies().get(0).owner());
				for(CandidateRealizationInputBinding binding : clause.inputBindings())
					Assert.assertTrue(binding.source().rule().parentOccurrence() == mixed.owner()
						|| binding.source().rule().parentOccurrence() == uniform.owner());
			}
	}

	@Test
	public void twoMixedAxesFallBackAndSingletonDescriptorsRemainDisjoint() throws Exception {
		CompiledHopKey consumer = key("fallback-consumer");
		Axis first = axis(key("fallback-first"), 0, "first", 1, 1);
		Axis second = axis(key("fallback-second"), 1, "second", 1, 1);
		NativePlacementContinuity.NativeSupportProduct twoMixed =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("fallback-seed"), anchor("fallback-proof"), true,
				List.of(first.bindings(), second.bindings()));
		Assert.assertNotNull(twoMixed);
		Assert.assertNull(pluralPublication(closure(), twoMixed, consumer,
			anchor("fallback-output"), "two-mixed",
			directSources(List.of(first.fact(), second.fact()))));

		Axis singletonMixed = axis(key("singleton-source"), 0, "singleton", 1, 1);
		NativePlacementContinuity.NativeSupportProduct singletonRegions =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("singleton-seed"), anchor("singleton-proof"), true,
				List.of(singletonMixed.bindings()));
		Assert.assertNotNull(singletonRegions);
		List<CandidateEmissionRealization> descriptors = pluralPublication(closure(),
			singletonRegions, consumer, anchor("singleton-output"), "singleton-regions",
			directSources(List.of(singletonMixed.fact())));
		Assert.assertEquals(2, descriptors.size());
		Assert.assertTrue(descriptors.stream().allMatch(value -> value.supportClauses().size() == 1));
		Assert.assertNotEquals(descriptors.get(0).key(), descriptors.get(1).key());
		// Actual publication rejects both singleton descriptors; that complete
		// scalar fallback is exercised by NativeMixedExactnessPartitionTest.
	}

	@Test
	public void structurallyEqualForeignSourceOwnerCannotEnterSplitAuthority() throws Exception {
		CompiledHopKey consumer = key("identity-consumer");
		Axis canonical = axis(key("identity-source"), 0, "identity", 2, 2);
		CompiledHopKey foreignOwner = key("identity-source");
		Assert.assertEquals(canonical.owner(), foreignOwner);
		Assert.assertNotSame(canonical.owner(), foreignOwner);
		List<CandidateRealizationInputBinding> foreignBindings = canonical.bindings().stream()
			.map(binding -> CandidateRealizationInputBinding.direct(binding.inputPosition(),
				new CandidateRealizationReference(new CandidateRuleKey(foreignOwner,
					binding.source().rule().orderedInputs()), binding.source().realization())))
			.toList();
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("identity-seed"), anchor("identity-proof"), true,
				List.of(foreignBindings));
		Assert.assertNotNull(product);
		Assert.assertNull("source executability is owner-identity scoped",
			pluralPublication(closure(), product, consumer, anchor("identity-output"),
				"identity", directSources(List.of(canonical.fact()))));
	}

	@Test
	public void actualBinderRetainsEachRegionAndWithdrawalMatchesColdAuthority() throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("binder-source");
		CompiledHopKey consumerOwner = fixtureKey("binder-consumer");
		DurableAnchorKey pool = anchor("binder-pool");
		CandidateRuleKey sourceKey = new CandidateRuleKey(sourceOwner, List.of());
		CandidateEmissionRealization exact0 = sourceRealization(
			sourceOwner, pool, "binder-e0", true);
		CandidateEmissionRealization exact1 = sourceRealization(
			sourceOwner, pool, "binder-e1", true);
		CandidateEmissionRealization mapped0 = valueMapSource(
			"binder-d0", sourceKey, exact0);
		CandidateEmissionRealization mapped1 = valueMapSource(
			"binder-d1", sourceKey, exact1);
		List<CandidateEmissionRealization> variants = List.of(exact0, exact1, mapped0, mapped1);
		CandidateRuleFact source = fact(sourceKey, variants);
		CandidateRuleFact staging = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("binder-source", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "binder-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("binder-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = closure(metrics);

		CandidateRuleFact first = bind(closure, source, staging, sourceHop, consumerHop, 4);
		List<CandidateEmissionRealization> firstProducts = products(first);
		Assert.assertEquals(2, firstProducts.size());
		Assert.assertEquals(Set.of(PlacementLayoutKind.DURABLE_MAP, PlacementLayoutKind.NATIVE_LINEAGE),
			firstProducts.stream().map(value -> value.key().layoutKind())
				.collect(java.util.stream.Collectors.toSet()));
		Assert.assertEquals(4, firstProducts.stream().mapToInt(value -> value.supportClauses().size()).sum());
		Assert.assertTrue(firstProducts.stream().allMatch(value ->
			value.supportClauses() instanceof NativeContinuitySupportClauses
				&& value.fullyMaterializedSupportClauseCount() == 0));
		List<CandidateEmissionRealization> explicitMembers = firstProducts.stream()
			.map(value -> new CandidateEmissionRealization(value.key(), List.copyOf(value.supportClauses())))
			.toList();
		Assert.assertEquals("the two lazy regions preserve every explicit proof/source member",
			new CandidateEmissionFact(EMISSION, FType.ROW, null, explicitMembers).realizations(),
			new CandidateEmissionFact(EMISSION, FType.ROW, null, firstProducts).realizations());
		Assert.assertEquals(Set.copyOf(variants.stream().map(CandidateEmissionRealization::key).toList()),
			firstProducts.stream().flatMap(value -> value.supportClauses().stream())
				.map(clause -> clause.inputBindings().get(0).source().realization())
				.collect(java.util.stream.Collectors.toSet()));
		SearchSpaceMetrics.NativePublicationCount split = metrics.nativePublicationSnapshot().stream()
			.filter(value -> value.outcome() == SearchSpaceMetrics.NativePublicationOutcome.PARTITIONED)
			.findFirst().orElseThrow();
		Assert.assertEquals(1, split.queries());
		Assert.assertEquals(4, split.logicalProofs());
		Assert.assertEquals(0, split.consumedProofs());
		CandidateRuleFact metricsOff = bind(
			closure(), source, staging, sourceHop, consumerHop, 4);
		Assert.assertEquals("metrics must not change exact realization, proof, or source authority",
			exactMemberSignatures(first), exactMemberSignatures(metricsOff));

		CandidateRuleFact second = bind(closure, source, first, sourceHop, consumerHop, 4);
		for(CandidateEmissionRealization retained : firstProducts)
			Assert.assertTrue("unchanged durable and native regions retain exact authority objects",
				products(second).stream().anyMatch(candidate -> candidate == retained));

		CandidateEmissionRealization durable = firstProducts.stream().filter(value ->
			value.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP).findFirst().orElseThrow();
		CandidateEmissionRealization nativeRegion = firstProducts.stream().filter(value ->
			value.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE).findFirst().orElseThrow();
		CandidateEmissionRealization stagingRealization = staging.allowedEmissionFacts().get(0)
			.realizations().get(0);
		for(CandidateEmissionRealization retained : List.of(durable, nativeRegion)) {
			CandidateRuleFact retainedOne = fact(staging.key(), List.of(retained, stagingRealization));
			CandidateRuleFact rebound = bind(closure(), source, retainedOne, sourceHop, consumerHop, 4);
			Assert.assertTrue("covered region retains the original object",
				products(rebound).stream().anyMatch(candidate -> candidate == retained));
			Assert.assertEquals("the disjoint region remains relation-native", 2, products(rebound).size());
			Assert.assertTrue(products(rebound).stream().allMatch(value ->
				value.supportClauses() instanceof NativeContinuitySupportClauses));
		}

		List<CandidateEmissionRealization> reducedVariants = variants.subList(0, 3);
		CandidateRuleFact reducedSource = fact(sourceKey, reducedVariants);
		CandidateRuleFact rebound = bind(closure, reducedSource, second, sourceHop, consumerHop, 3);
		CandidateRuleFact pruned = PlacementSupportRelations.pruneUnsupportedRealizations(
			List.of(reducedSource, rebound)).stream().filter(value ->
				value.key().parentOccurrence() == consumerOwner).findFirst().orElseThrow();
		CandidateRuleFact cold = bind(closure(), reducedSource, staging, sourceHop, consumerHop, 3);
		Assert.assertEquals("withdrawal after retained split authority equals a cold exact rebuild",
			expandedSignatures(cold), expandedSignatures(pruned));
		Assert.assertFalse(expandedSignatures(pruned).stream().anyMatch(signature ->
			signature.contains("binder-d1")));
	}

	private static Axis axis(CompiledHopKey owner, int position, String prefix,
		int exactCount, int dynamicCount) throws Exception {
		DurableAnchorKey pool = anchor(prefix + "-pool");
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		List<CandidateRealizationInputBinding> exact = new ArrayList<>();
		List<CandidateRealizationInputBinding> dynamic = new ArrayList<>();
		for(int index = 0; index < exactCount + dynamicCount; index++) {
			boolean isExact = index < exactCount;
			String lineage = prefix + '-' + index;
			PlacementProofKey proof = new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, owner, lineage + "-proof");
			CandidateEmissionRealization realization = isExact
				? CandidateEmissionRealization.nativeLineage(
					EMISSION, lineage, pool, List.of(proof), List.of())
				: CandidateEmissionRealization.nativeLineageDynamicLayout(
					EMISSION, lineage, pool, List.of(proof), List.of());
			realizations.add(realization);
		}
		CandidateRuleFact fact = sourceFact(owner, realizations);
		for(int index = 0; index < realizations.size(); index++) {
			CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(
				position, CandidateRealizationReference.of(fact.key(), realizations.get(index)));
			(isExact(realizations.get(index)) ? exact : dynamic).add(binding);
		}
		List<CandidateRealizationInputBinding> all = new ArrayList<>(exact);
		all.addAll(dynamic);
		all.sort(PlacementAnalysis.canonicalComparator());
		return new Axis(owner, fact, List.copyOf(exact), List.copyOf(dynamic), List.copyOf(all));
	}

	private static boolean isExact(CandidateEmissionRealization realization) {
		return realization.allOwnedSupportClausesHaveExactNativeLayout();
	}

	private static CandidateRuleFact sourceFact(CompiledHopKey owner,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null,
			new CandidateRuleKey(owner, List.of()), realizations);
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner) throws Exception {
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			EMISSION, "binder-staging", List.of(), List.of());
		return fact(new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW))), List.of(staging));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, key, realizations);
	}

	private static CandidateEmissionRealization sourceRealization(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage, boolean exact) {
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, lineage + "-proof");
		return exact
			? CandidateEmissionRealization.nativeLineage(EMISSION, lineage, pool, List.of(proof), List.of())
			: CandidateEmissionRealization.nativeLineageDynamicLayout(
				EMISSION, lineage, pool, List.of(proof), List.of());
	}

	private static CandidateEmissionRealization valueMapSource(String relation,
		CandidateRuleKey rule, CandidateEmissionRealization exactSource) {
		return CandidateEmissionRealization.valueMap(EMISSION, relation,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.logicalTransient(0,
					CandidateRealizationReference.of(rule, exactSource))))));
	}

	private static CandidateRuleFact bind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, int width) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("bind",
			PlacementRelationClosure.class, CandidateRuleFact.class, CandidateRuleFact.class,
			DataOp.class, UnaryOp.class, Map.class, PlacementAnalysis.NodeShapeFact.class, Map.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, closure, source, consumer, sourceHop,
			consumerHop, Map.of(anchor("binder-pool"), width),
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2), null);
	}

	private static List<CandidateEmissionRealization> products(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(value -> value.realizations().stream())
			.filter(value -> value.supportClauses() instanceof NativeContinuitySupportClauses).toList();
	}

	private static List<String> expandedSignatures(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(value -> value.realizations().stream())
			.flatMap(value -> value.supportClauses().stream())
			.map(CandidateRealizationSupportClause::normalizedSignature).sorted().toList();
	}

	private static List<String> exactMemberSignatures(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(value -> value.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream().map(clause ->
				realization.key().normalizedSignature() + "|" + clause.normalizedSignature()))
			.sorted().toList();
	}

	private static Object directSources(List<CandidateRuleFact> facts) throws Exception {
		Class<?> type = Class.forName(
			PlacementRelationClosure.class.getName() + "$DirectSourceIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> pluralPublication(
		PlacementRelationClosure closure,
		NativePlacementContinuity.NativeSupportProduct product, CompiledHopKey owner,
		DurableAnchorKey output, String lineage, Object sources) throws Exception {
		Class<?> trace = Class.forName(
			PlacementRelationClosure.class.getName() + "$NativePublicationTrace");
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativeProductPublication", product.getClass(), CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, List.class,
			sources.getClass(), Map.class, trace);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(closure, product, owner,
			EMISSION, output, lineage, List.of(), sources,
			new IdentityHashMap<CandidateEmissionRealization,Boolean>(), null);
	}

	private static CandidateEmissionRealization scalarPublication(
		PlacementRelationClosure closure, NativeContinuityProof proof, CompiledHopKey owner,
		DurableAnchorKey output, String lineage, boolean exact) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			EMISSION, output, lineage, exact);
	}

	private static PlacementRelationClosure closure() {
		return closure(null);
	}

	private static PlacementRelationClosure closure(SearchSpaceMetrics metrics) {
		return new PlacementRelationClosure(null, null, metrics, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("native-mixed-split", "main", "call",
			"compiled", REGION, id, id);
	}

	private static CompiledHopKey fixtureKey(String id) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("fixtureKey", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, id);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private record Axis(CompiledHopKey owner, CandidateRuleFact fact,
		List<CandidateRealizationInputBinding> exactBindings,
		List<CandidateRealizationInputBinding> dynamicBindings,
		List<CandidateRealizationInputBinding> bindings) { }
}
