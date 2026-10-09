/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeUnpinnedInventoryLinearTest {
	@Test
	public void unpinnedNativeInventoryIsVisitedOnceAndMatchesExplicitReference()
		throws Exception {
		Scenario scenario = scenario(24, false);
		NativePlacementContinuity.CandidateSupportResult explicit = scenario.query(true, null);
		CountingList<CandidateRuleFact> visits = new CountingList<>();
		NativePlacementContinuity.CandidateSupportResult factored = scenario.query(false, visits);

		Assert.assertEquals(signatures(explicit), signatures(factored));
		assertIdentitySetEquals(explicit.dependencyOccurrences(), factored.dependencyOccurrences());
		Assert.assertEquals(24, factored.proofs().size());
		Assert.assertTrue("bounded representation/skeleton preflights plus one inventory pass are linear: "
			+ visits.gets(), visits.gets() <= 4 * 24 + 4);
	}

	@Test
	public void duplicateStructuralAuthorityStillFallsBackToLegacyTopology() throws Exception {
		Scenario scenario = scenario(2, true);
		NativePlacementContinuity.CandidateSupportResult explicit = scenario.query(true, null);
		NativePlacementContinuity.CandidateSupportResult factored = scenario.query(false, null);
		Assert.assertEquals(signatures(explicit), signatures(factored));
		assertIdentitySetEquals(explicit.dependencyOccurrences(), factored.dependencyOccurrences());
	}

	private static Scenario scenario(int alternatives, boolean duplicateKey) throws Exception {
		Object fixture = newFixture(FType.FULL);
		DurableAnchorKey pool = pool("inventory-worker:8001", 0, 50);
		Object seed = invoke(fixture, "source", "inventory-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object left = invoke(fixture, "unary", "inventory-left", OpOp1.LOG, seed, false);
		Object right = invoke(fixture, "unary", "inventory-right", OpOp1.LOG, seed, false);
		invoke(fixture, "samePoolRealizations", left, unary, new DurableAnchorKey[] {
			new DurableAnchorKey("left-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("left-b", FType.FULL, pool.partitions())});
		invoke(fixture, "samePoolRealizations", right, unary, new DurableAnchorKey[] {
			new DurableAnchorKey("right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("right-b", FType.FULL, pool.partitions())});
		Object child = invoke(fixture, "binary", "inventory-child", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		NativePlacementContinuity seedResolver =
			(NativePlacementContinuity)invoke(fixture, "resolver");
		CandidateRealizationReference childReference =
			(CandidateRealizationReference)invoke(fixture, "reference", child, binary);
		NativePlacementContinuity.NativeSupportProduct product = seedResolver
			.proveCandidateSupport(childReference, pool).supportProduct();
		Assert.assertNotNull(product);

		Object outer = invoke(fixture, "unary", "inventory-outer", OpOp1.EXP, child, false);
		CandidateRuleFact outerFact = (CandidateRuleFact)invoke(fixture, "fact", outer, unary);
		CandidateEmissionFact outerEmission = outerFact.allowedEmissionFacts().get(0);
		CompiledHopKey outerKey = (CompiledHopKey)invoke(outer, "key");
		CandidateRealizationReference proposed = CandidateRealizationReference.of(outerFact.key(),
			CandidateEmissionRealization.nativeLineage(outerEmission.emissionState(),
				"inventory-proposed-" + alternatives + '-' + duplicateKey, List.of(), List.of()));
		CompiledHopKey childKey = (CompiledHopKey)invoke(child, "key");
		return new Scenario(fixture, childKey, product, pool, alternatives, duplicateKey,
			outerFact, outerEmission, proposed, outerKey);
	}

	private record Scenario(Object fixture, CompiledHopKey childKey,
		NativePlacementContinuity.NativeSupportProduct product, DurableAnchorKey pool,
		int alternatives, boolean duplicateKey, CandidateRuleFact outerFact,
		CandidateEmissionFact outerEmission, CandidateRealizationReference proposed,
		CompiledHopKey outerKey) {
		private NativePlacementContinuity.CandidateSupportResult query(boolean explicit,
			CountingList<CandidateRuleFact> visits) throws Exception {
			replaceChildFacts(fixture, childKey, product, pool, alternatives, duplicateKey, explicit);
			NativePlacementContinuity resolver =
				(NativePlacementContinuity)invoke(fixture, "resolver");
			if(visits != null)
				installCountingOwnerInventory(resolver, childKey, visits);
			return resolver.proveGeneratedCandidateSupport(
				outerFact, outerEmission, proposed, pool);
		}
	}

	@SuppressWarnings("unchecked")
	private static void replaceChildFacts(Object fixture, CompiledHopKey childKey,
		NativePlacementContinuity.NativeSupportProduct product, DurableAnchorKey pool,
		int alternatives, boolean duplicateKey, boolean explicit) throws Exception {
		Field candidatesField = fixture.getClass().getDeclaredField("candidates");
		candidatesField.setAccessible(true);
		List<CandidateRuleFact> candidates = (List<CandidateRuleFact>)candidatesField.get(fixture);
		int position = -1;
		CandidateRuleFact template = null;
		for(int index = 0; index < candidates.size(); index++)
			if(candidates.get(index).key().parentOccurrence() == childKey) {
				position = index;
				template = candidates.get(index);
				break;
			}
		Assert.assertNotNull(template);
		candidates.removeIf(fact -> fact.key().parentOccurrence() == childKey);
		List<CandidateRuleFact> replacements = new ArrayList<>();
		CandidateEmissionFact base = template.allowedEmissionFacts().get(0);
		for(int index = 0; index < alternatives; index++) {
			NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
				childKey, product, pool, true);
			List<PlacementAnalysis.CandidateRealizationSupportClause> clauses = explicit
				? List.copyOf(relation) : relation;
			String lineage = duplicateKey ? "duplicate" : String.format("member-%03d", index);
			CandidateEmissionRealization realization = new CandidateEmissionRealization(
				PlacementIdentity.PlacementRealizationKey.nativeLineage(
					base.emissionState(), lineage), clauses);
			CandidateEmissionFact emission = new CandidateEmissionFact(base.emissionState(),
				base.executionFType(), base.derivedFoutAction(), List.of(realization));
			replacements.add(new CandidateRuleFact(template.key(), template.status(),
				template.capability(), template.shapeProof(), template.profile(),
				List.of(emission), template.failureCode()));
		}
		candidates.addAll(position, replacements);
	}

	@SuppressWarnings("unchecked")
	private static void installCountingOwnerInventory(NativePlacementContinuity resolver,
		CompiledHopKey owner, CountingList<CandidateRuleFact> visits) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("candidateFactsByKey");
		field.setAccessible(true);
		Map<CompiledHopKey,List<CandidateRuleFact>> original =
			(Map<CompiledHopKey,List<CandidateRuleFact>>)field.get(resolver);
		visits.values = original.get(owner);
		Map<CompiledHopKey,List<CandidateRuleFact>> counted = new IdentityHashMap<>(original);
		counted.put(owner, visits);
		field.set(resolver, Collections.unmodifiableMap(counted));
	}

	private static Object newFixture(FType type) throws Exception {
		Class<?> fixture = Class.forName(NativePlacementContinuityTest.class.getName() + "$Fixture");
		Constructor<?> constructor = fixture.getDeclaredConstructor(FType.class);
		constructor.setAccessible(true);
		return constructor.newInstance(type);
	}

	private static Object invoke(Object receiver, String name, Object... arguments)
		throws Exception {
		for(Method method : receiver.getClass().getDeclaredMethods()) {
			if(!method.getName().equals(name) || method.getParameterCount() != arguments.length)
				continue;
			method.setAccessible(true);
			return method.invoke(receiver, arguments);
		}
		throw new NoSuchMethodException(receiver.getClass().getName() + '.' + name);
	}

	private static DurableAnchorKey pool(String worker, long begin, long end) {
		return new DurableAnchorKey("inventory-pool", FType.FULL,
			List.of(new AnchorPartition(worker, List.of(begin, 0L), List.of(end, 2L))));
	}

	private static List<String> signatures(
		NativePlacementContinuity.CandidateSupportResult result) {
		return result.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList();
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey key : expected)
			Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == key));
	}

	private static final class CountingList<T> extends AbstractList<T> {
		private List<T> values = List.of();
		private long gets;
		@Override public T get(int index) { gets++; return values.get(index); }
		@Override public int size() { return values.size(); }
		private long gets() { return gets; }
	}
}
