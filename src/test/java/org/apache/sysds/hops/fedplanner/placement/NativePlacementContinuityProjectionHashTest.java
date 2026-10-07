/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Differential oracle for the private continuity projection algebra. */
public class NativePlacementContinuityProjectionHashTest {
	@Test
	public void cachedProjectionMatchesLegacySetAlgebraAndOwnerIdentity() throws Exception {
		CompiledHopKey factOwnerA = owner("fact-a");
		CompiledHopKey factOwnerB = owner("fact-b");
		CompiledHopKey sourceOwner = owner("source");
		CompiledHopKey equalForeignSource = owner("source");
		Assert.assertEquals(sourceOwner, equalForeignSource);
		Assert.assertNotSame(sourceOwner, equalForeignSource);

		CandidateRuleFact a = fact(factOwnerA, sourceOwner, "proof-a", ExecType.CP);
		CandidateRuleFact proofOnly = fact(factOwnerA, sourceOwner, "proof-b", ExecType.CP);
		CandidateRuleFact rebound = fact(factOwnerA, equalForeignSource, "proof-a", ExecType.CP);
		CandidateRuleFact changedEmission = fact(factOwnerA, sourceOwner, "proof-a", ExecType.FED);
		CandidateRuleFact b = fact(factOwnerB, sourceOwner, "proof-c", ExecType.CP);

		assertLegacyRelation(List.of(a), List.of(proofOnly), true);
		assertLegacyRelation(List.of(a), List.of(rebound), true);
		assertLegacyRelation(List.of(a), List.of(changedEmission), true);
		assertLegacyRelation(List.of(a, b), List.of(b, a), true);
		assertLegacyRelation(List.of(a), List.of(rebound), false);
		assertLegacyRelation(List.of(a), List.of(changedEmission), false);
	}

	@Test
	public void cachedSetPaysElementHashOnceAndRemainsImmutable() throws Exception {
		Class<?> type = Class.forName(NativePlacementContinuity.class.getName() + "$CachedImmutableSet");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class);
		constructor.setAccessible(true);
		CountingKey key = new CountingKey(17);
		Set<CountingKey> owned = new HashSet<>();
		owned.add(key);
		key.calls = 0;
		@SuppressWarnings("unchecked") Set<CountingKey> cached = (Set<CountingKey>)constructor.newInstance(owned);
		Assert.assertEquals(1, key.calls);
		int expected = cached.hashCode();
		for(int i = 0; i < 20; i++)
			Assert.assertEquals(expected, cached.hashCode());
		Assert.assertEquals("repeated parent hashing must not recurse into elements", 1, key.calls);
		Assert.assertThrows(UnsupportedOperationException.class, () -> cached.add(new CountingKey(2)));
		Iterator<CountingKey> iterator = cached.iterator();
		iterator.next();
		Assert.assertThrows(UnsupportedOperationException.class, iterator::remove);
	}

	@Test
	public void cachedHashCollisionStillChecksExactSetEquality() throws Exception {
		Assert.assertEquals("fixture requires a real String hash collision", "Aa".hashCode(), "BB".hashCode());
		Set<?> aa = cached(Set.of("Aa"));
		Set<?> bb = cached(Set.of("BB"));
		Assert.assertEquals(aa.hashCode(), bb.hashCode());
		Assert.assertNotEquals(aa, bb);
		Assert.assertNotEquals(bb, aa);
		Assert.assertEquals(Set.of("Aa"), aa);
		Assert.assertEquals(aa, Set.of("Aa"));
	}

	private static void assertLegacyRelation(List<CandidateRuleFact> left, List<CandidateRuleFact> right,
		boolean includePublishedRealizations) throws Exception {
		Set<LegacyFact> legacyLeft = legacy(left, includePublishedRealizations);
		Set<LegacyFact> legacyRight = legacy(right, includePublishedRealizations);
		Set<?> optimizedLeft = optimized(left, includePublishedRealizations);
		Set<?> optimizedRight = optimized(right, includePublishedRealizations);
		Assert.assertEquals("optimized projection changed legacy equality",
			legacyLeft.equals(legacyRight), optimizedLeft.equals(optimizedRight));
		Assert.assertEquals("left projection hash changed", legacyLeft.hashCode(), optimizedLeft.hashCode());
		Assert.assertEquals("right projection hash changed", legacyRight.hashCode(), optimizedRight.hashCode());
	}

	private static Set<?> optimized(List<CandidateRuleFact> facts, boolean include) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("continuityProjection", List.class,
			boolean.class);
		method.setAccessible(true);
		return (Set<?>)method.invoke(null, facts, include);
	}

	private static Set<LegacyFact> legacy(List<CandidateRuleFact> facts, boolean include) {
		Set<LegacyFact> projected = new HashSet<>();
		for(CandidateRuleFact fact : facts) {
			Set<LegacyEmission> emissions = new HashSet<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				Set<LegacyRealization> realizations = new HashSet<>();
				for(CandidateEmissionRealization realization : include ? emission.realizations()
					: List.<CandidateEmissionRealization>of()) {
					Set<LegacyClause> clauses = new HashSet<>();
					for(CandidateRealizationSupportClause clause : realization.supportClauses())
						clauses.add(new LegacyClause(clause.inputBindings().stream().map(LegacyBinding::new).toList(),
							clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact()));
					realizations.add(new LegacyRealization(realization.key(), clauses));
				}
				emissions.add(new LegacyEmission(emission.emissionState(), emission.executionFType(),
					emission.derivedFoutAction(), realizations));
			}
			projected.add(new LegacyFact(fact.key(), fact.status(), emissions));
		}
		return projected;
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, CompiledHopKey sourceOwner, String proof,
		ExecType exec) {
		PlacementEmissionState emission = new PlacementEmissionState(
			new PlacementState(exec, FederatedOutput.LOUT, null, false), false);
		PlacementEmissionState sourceEmission = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		CandidateRealizationReference source = new CandidateRealizationReference(
			new CandidateRuleKey(sourceOwner, List.of()), PlacementRealizationKey.local(sourceEmission));
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, owner, proof)),
			List.of(CandidateRealizationInputBinding.direct(0, source)));
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.local(emission), List.of(clause));
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()), CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", exec, FederatedOutput.LOUT, null,
				ReasonCode.OK, "metadata", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()), new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(emission, null, null, List.of(realization))), "");
	}

	private static CompiledHopKey owner(String value) {
		ControlRegionKey region = new ControlRegionKey("projection", "main", List.of(value), value, "compiled");
		return new CompiledHopKey("projection", "main", value, "compiled", region, value, value);
	}

	private static Set<?> cached(Set<?> values) throws Exception {
		Class<?> type = Class.forName(NativePlacementContinuity.class.getName() + "$CachedImmutableSet");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class);
		constructor.setAccessible(true);
		return (Set<?>)constructor.newInstance(new HashSet<>(values));
	}

	private record LegacyFact(CandidateRuleKey key, CandidateEvaluationStatus status,
		Set<LegacyEmission> emissions) { }
	private record LegacyEmission(PlacementEmissionState state, org.apache.sysds.hops.fedplanner.FTypes.FType type,
		DerivedFoutMaterializationActionKey action, Set<LegacyRealization> realizations) { }
	private record LegacyRealization(PlacementRealizationKey key, Set<LegacyClause> clauses) { }
	private record LegacyClause(List<LegacyBinding> bindings, DurableAnchorKey witness, boolean exact) { }
	private static final class LegacyBinding {
		private final CandidateRealizationInputBinding binding;
		private final CompiledHopKey owner;
		private final int hashCode;
		private LegacyBinding(CandidateRealizationInputBinding binding) {
			this.binding = binding;
			owner = binding.source().rule().parentOccurrence();
			hashCode = 31 * binding.hashCode() + System.identityHashCode(owner);
		}
		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object other) {
			return this == other || other instanceof LegacyBinding that
				&& owner == that.owner && binding.equals(that.binding);
		}
	}
	private static final class CountingKey {
		private final int value;
		private int calls;
		private CountingKey(int value) { this.value = value; }
		@Override public int hashCode() { calls++; return value; }
		@Override public boolean equals(Object other) {
			return other instanceof CountingKey that && value == that.value;
		}
	}
}
