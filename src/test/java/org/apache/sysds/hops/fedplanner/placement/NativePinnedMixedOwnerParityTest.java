/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.junit.Assert;
import org.junit.Test;

/** Adversarial parity for ordinary pinned rows that share an owner with native siblings. */
public class NativePinnedMixedOwnerParityTest {
	@Test
	public void declaredEmptyOrdinaryRowsSharingOneHandlePreserveLegacyBucket() throws Exception {
		Object scenario = scenario();
		CandidateEmissionFact emission = (CandidateEmissionFact)accessor(scenario, "childEmission");
		CandidateEmissionRealization empty = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission.emissionState(), "empty-ordinary"),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionRealization equalDistinct = new CandidateEmissionRealization(
			empty.key(), List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		NativePlacementContinuity.CandidateSupportResult eager = queryInstalled(scenario,
			installResolver(scenario, true, List.of(empty, equalDistinct)), "empty-ordinary");

		CandidateEmissionRealization lazyEmpty = new CandidateEmissionRealization(
			empty.key(), List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionRealization lazyEqualDistinct = new CandidateEmissionRealization(
			empty.key(), List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		NativeContinuitySupportClauses relation = install(
			scenario, false, List.of(lazyEmpty, lazyEqualDistinct));
		NativePlacementContinuity resolver = resolver(scenario, null);
		NativePlacementContinuity.CandidateSupportResult actual =
			queryInstalled(scenario, resolver, "empty-ordinary");
		assertParity(eager, actual);
		Assert.assertEquals("equal empty rows retain their legacy structural handle bucket",
			1, relation.materializedHandleCount());
		Assert.assertEquals(0, ownerTopologyCount(resolver, childKey(scenario)));
	}

	@Test
	public void structuralHandleCollisionAndRevisionPreserveExactPublicProofs() throws Exception {
		Object scenario = scenario();
		CandidateRealizationReference ordinary = installedReferenceAfterInstall(scenario, true, "a-choice");
		CandidateEmissionRealization ordinaryRealization = realizationObject(
			candidateFacts(fixture(scenario)), childKey(scenario), "a-choice");
		CandidateEmissionRealization duplicate = new CandidateEmissionRealization(
			ordinary.realization(), List.copyOf(ordinaryRealization.supportClauses()));
		NativePlacementContinuity.CandidateSupportResult eager = queryInstalled(
			scenario, installResolver(scenario, true, List.of(duplicate)), "a-choice");

		NativeContinuitySupportClauses relation = install(scenario, false, List.of(duplicate));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity initial = resolver(scenario, metrics);
		NativePlacementContinuity.CandidateSupportResult actual =
			queryInstalled(scenario, initial, "a-choice");
		assertParity(eager, actual);
		Assert.assertEquals("only the native circuit representative may be read", 1,
			relation.materializedHandleCount());
		Assert.assertEquals("ordinary coverage must not populate full-owner topology", 0,
			ownerTopologyCount(initial, childKey(scenario)));

		List<CandidateRuleFact> withNative = List.copyOf(candidateFacts(fixture(scenario)));
		List<CandidateRuleFact> withoutNative = replaceChildRealizations(
			scenario, withNative, realization -> !(realization.supportClauses()
				instanceof NativeContinuitySupportClauses));
		NativePlacementContinuity withdrawn = initial.nextRevisionWithCompleteCandidateDelta(
			withoutNative, identitySet(childKey(scenario)));
		NativePlacementContinuity.CandidateSupportResult warmWithdrawn =
			queryReference(withdrawn, reference(withoutNative, childKey(scenario), "a-choice"), pool(scenario));
		NativePlacementContinuity.CandidateSupportResult coldWithdrawn = queryReference(
			resolverWithFacts(scenario, withoutNative),
			reference(withoutNative, childKey(scenario), "a-choice"), pool(scenario));
		assertParity(coldWithdrawn, warmWithdrawn);

		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			withNative, identitySet(childKey(scenario)));
		NativePlacementContinuity.CandidateSupportResult warmRestored =
			queryInstalled(scenario, restored, "a-choice");
		NativePlacementContinuity.CandidateSupportResult coldRestored =
			queryInstalled(scenario, resolverWithFacts(scenario, withNative), "a-choice");
		assertParity(coldRestored, warmRestored);
		assertParity(actual, warmRestored);
	}

	@Test
	public void fixedOwnerCycleNativeSiblingFallsBackWithExactParity() throws Exception {
		Object scenario = scenario();
		install(scenario, true, List.of());
		CandidateRealizationReference ordinary = installedReference(scenario, "a-choice");
		CandidateRealizationInputBinding self = CandidateRealizationInputBinding.direct(0, ordinary);
		NativeContinuitySupportClauses cycle = relation(scenario, List.of(List.of(self)));
		CandidateEmissionRealization cycleRealization = realization(scenario, "cycle-native", cycle);
		NativePlacementContinuity eagerResolver = installResolver(scenario, true, List.of(cycleRealization));
		NativePlacementContinuity.CandidateSupportResult eager = queryInstalled(
			scenario, eagerResolver, "a-choice");
		assertFullExplicitOracle(scenario, eagerResolver);

		NativeContinuitySupportClauses lazyCycle = relation(scenario, List.of(List.of(self)));
		CandidateEmissionRealization lazy = realization(scenario, "cycle-native", lazyCycle);
		NativePlacementContinuity lazyResolver = installResolver(scenario, false, List.of(lazy));
		NativePlacementContinuity.CandidateSupportResult actual = queryInstalled(
			scenario, lazyResolver, "a-choice");
		assertParity(eager, actual);
		Assert.assertEquals("rejection may read only the relation's one circuit representative",
			1, lazyCycle.materializedHandleCount());
		Assert.assertEquals("ordinary coverage must not populate full-owner topology", 0,
			ownerTopologyCount(lazyResolver, childKey(scenario)));
	}

	@Test
	public void missingInputTemplateNativeSiblingFallsBackWithExactParity() throws Exception {
		Object scenario = scenario();
		NativePlacementContinuity.NativeSupportProduct base = product(scenario);
		CandidateRealizationInputBinding source = base.axes().get(0).get(0);
		CandidateRealizationInputBinding missing = CandidateRealizationInputBinding.direct(
			17, source.source());
		NativeContinuitySupportClauses explicitRelation = relation(scenario, List.of(List.of(missing)));
		CandidateEmissionRealization explicitMissing =
			realization(scenario, "missing-template", explicitRelation);
		NativePlacementContinuity eagerResolver = installResolver(
			scenario, true, List.of(explicitMissing));
		NativePlacementContinuity.CandidateSupportResult eager = queryInstalled(
			scenario, eagerResolver, "a-choice");
		assertFullExplicitOracle(scenario, eagerResolver);

		NativeContinuitySupportClauses lazyRelation = relation(scenario, List.of(List.of(missing)));
		CandidateEmissionRealization lazyMissing =
			realization(scenario, "missing-template", lazyRelation);
		NativePlacementContinuity.CandidateSupportResult actual = queryInstalled(
			scenario, installResolver(scenario, false, List.of(lazyMissing)), "a-choice");
		assertParity(eager, actual);
		Assert.assertEquals("template rejection may read only the relation representative",
			1, lazyRelation.materializedHandleCount());
	}

	private static Object scenario() throws Exception {
		Method method = NativeHybridUnpinnedTopologyTest.class.getDeclaredMethod(
			"scenario", String.class, List.class);
		method.setAccessible(true);
		return method.invoke(null, "m-choice", List.of("a-choice", "z-choice"));
	}

	private static NativePlacementContinuity installResolver(Object scenario, boolean explicit,
		List<CandidateEmissionRealization> additional) throws Exception {
		install(scenario, explicit, additional);
		return resolver(scenario, null);
	}

	private static NativeContinuitySupportClauses install(Object scenario, boolean explicit,
		List<CandidateEmissionRealization> additional) throws Exception {
		List<CandidateEmissionRealization> supplied = additional;
		if(explicit) {
			supplied = additional.stream().map(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses relation
					? new CandidateEmissionRealization(realization.key(), List.copyOf(relation))
					: realization).toList();
		}
		return (NativeContinuitySupportClauses)invoke(scenario, "install", explicit, supplied);
	}

	private static void assertFullExplicitOracle(Object scenario,
		NativePlacementContinuity resolver) throws Exception {
		for(CandidateRuleFact fact : candidateFacts(fixture(scenario)))
			if(fact.key().parentOccurrence() == childKey(scenario))
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						Assert.assertFalse("the oracle must contain no native support carrier",
							realization.supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertTrue("the oracle must execute and retain the full legacy topology",
			ownerTopologyCount(resolver, childKey(scenario)) > 0);
	}

	private static CandidateRealizationReference installedReferenceAfterInstall(Object scenario,
		boolean explicit, String lineage) throws Exception {
		install(scenario, explicit, List.of());
		return installedReference(scenario, lineage);
	}

	private static CandidateRealizationReference installedReference(Object scenario,
		String lineage) throws Exception {
		return (CandidateRealizationReference)invoke(scenario, "installedReference", lineage);
	}

	private static NativePlacementContinuity.CandidateSupportResult queryInstalled(Object scenario,
		NativePlacementContinuity resolver, String lineage) throws Exception {
		return (NativePlacementContinuity.CandidateSupportResult)invoke(
			scenario, "queryInstalled", lineage, resolver);
	}

	private static NativePlacementContinuity.CandidateSupportResult queryReference(
		NativePlacementContinuity resolver, CandidateRealizationReference reference,
		DurableAnchorKey pool) {
		return resolver.proveCandidateSupport(reference, pool);
	}

	private static NativePlacementContinuity resolver(Object scenario,
		SearchSpaceMetrics metrics) throws Exception {
		return metrics == null
			? (NativePlacementContinuity)invoke(fixture(scenario), "resolver")
			: (NativePlacementContinuity)invoke(fixture(scenario), "resolver", metrics, 128, 2048L);
	}

	private static NativePlacementContinuity resolverWithFacts(Object scenario,
		List<CandidateRuleFact> facts) throws Exception {
		List<CandidateRuleFact> live = candidateFacts(fixture(scenario));
		List<CandidateRuleFact> saved = List.copyOf(live);
		try {
			live.clear();
			live.addAll(facts);
			return resolver(scenario, null);
		}
		finally {
			live.clear();
			live.addAll(saved);
		}
	}

	private static NativeContinuitySupportClauses relation(Object scenario,
		List<List<CandidateRealizationInputBinding>> axes) throws Exception {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				pool(scenario), pool(scenario), true, axes);
		Assert.assertNotNull(product);
		return new NativeContinuitySupportClauses(childKey(scenario), product, pool(scenario), true);
	}

	private static CandidateEmissionRealization realization(Object scenario, String lineage,
		NativeContinuitySupportClauses relation) throws Exception {
		CandidateEmissionFact emission = (CandidateEmissionFact)accessor(scenario, "childEmission");
		return new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(emission.emissionState(), lineage), relation);
	}

	private static NativePlacementContinuity.NativeSupportProduct product(Object scenario)
		throws Exception {
		return (NativePlacementContinuity.NativeSupportProduct)accessor(scenario, "product");
	}

	private static Object fixture(Object scenario) throws Exception {
		return accessor(scenario, "fixture");
	}

	private static CompiledHopKey childKey(Object scenario) throws Exception {
		return (CompiledHopKey)accessor(scenario, "childKey");
	}

	private static DurableAnchorKey pool(Object scenario) throws Exception {
		return (DurableAnchorKey)accessor(scenario, "pool");
	}

	private static Object accessor(Object receiver, String name) throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(receiver);
	}

	private static Object invoke(Object receiver, String name, Object... arguments) throws Exception {
		for(Method method : receiver.getClass().getDeclaredMethods()) {
			if(method.getName().equals(name) && method.getParameterCount() == arguments.length) {
				method.setAccessible(true);
				return method.invoke(receiver, arguments);
			}
		}
		throw new NoSuchMethodException(name);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> candidateFacts(Object fixture) throws Exception {
		Field field = fixture.getClass().getDeclaredField("candidates");
		field.setAccessible(true);
		return (List<CandidateRuleFact>)field.get(fixture);
	}

	private static List<CandidateRuleFact> replaceChildRealizations(Object scenario,
		List<CandidateRuleFact> facts,
		java.util.function.Predicate<CandidateEmissionRealization> retained) throws Exception {
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		CompiledHopKey child = childKey(scenario);
		for(CandidateRuleFact fact : facts) {
			if(fact.key().parentOccurrence() != child) {
				result.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				emissions.add(new CandidateEmissionFact(emission.emissionState(),
					emission.executionFType(), emission.derivedFoutAction(),
					emission.realizations().stream().filter(retained).toList()));
			result.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
				fact.shapeProof(), fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(result);
	}

	private static CandidateRealizationReference reference(List<CandidateRuleFact> facts,
		CompiledHopKey owner, String lineage) {
		for(CandidateRuleFact fact : facts)
			if(fact.key().parentOccurrence() == owner)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(lineage.equals(realization.key().nativeLineage()))
							return CandidateRealizationReference.of(fact.key(), realization);
		throw new AssertionError("missing realization " + lineage);
	}

	private static CandidateEmissionRealization realizationObject(List<CandidateRuleFact> facts,
		CompiledHopKey owner, String lineage) {
		for(CandidateRuleFact fact : facts)
			if(fact.key().parentOccurrence() == owner)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(lineage.equals(realization.key().nativeLineage()))
							return realization;
		throw new AssertionError("missing realization " + lineage);
	}

	@SuppressWarnings("unchecked")
	private static int ownerTopologyCount(NativePlacementContinuity resolver,
		CompiledHopKey owner) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("candidateTopologies");
		field.setAccessible(true);
		int count = 0;
		for(var entry : ((Map<Object,Object>)field.get(resolver)).entrySet()) {
			Object key = entry.getKey();
			Field occurrence = key.getClass().getDeclaredField("occurrence");
			occurrence.setAccessible(true);
			Field ordinaryOnly = entry.getValue().getClass().getDeclaredField("ordinaryOnly");
			ordinaryOnly.setAccessible(true);
			if(occurrence.get(key) == owner && !ordinaryOnly.getBoolean(entry.getValue()))
				count++;
		}
		return count;
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey key) {
		Set<CompiledHopKey> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		result.add(key);
		return result;
	}

	private static void assertParity(NativePlacementContinuity.CandidateSupportResult expected,
		NativePlacementContinuity.CandidateSupportResult actual) {
		Assert.assertEquals(expected.proofs().stream()
			.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			actual.proofs().stream()
				.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		Assert.assertEquals(expected.proofs().size(), actual.proofs().size());
		for(int proof = 0; proof < expected.proofs().size(); proof++) {
			List<CandidateRealizationInputBinding> left = expected.proofs().get(proof).immediateBindings();
			List<CandidateRealizationInputBinding> right = actual.proofs().get(proof).immediateBindings();
			Assert.assertEquals(left.size(), right.size());
			for(int binding = 0; binding < left.size(); binding++) {
				Assert.assertEquals(left.get(binding).source(), right.get(binding).source());
				Assert.assertSame(left.get(binding).source().rule().parentOccurrence(),
					right.get(binding).source().rule().parentOccurrence());
			}
		}
		Assert.assertEquals(expected.dependencyOccurrences().size(),
			actual.dependencyOccurrences().size());
		for(CompiledHopKey owner : expected.dependencyOccurrences())
			Assert.assertTrue(actual.dependencyOccurrences().stream().anyMatch(candidate -> candidate == owner));
	}
}
