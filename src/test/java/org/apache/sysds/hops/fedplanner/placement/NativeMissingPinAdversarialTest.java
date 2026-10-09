/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.CandidateSupportResult;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeMissingPinAdversarialTest {
	@Test
	public void structurallyEqualNativeAliasStillUsesNativeAuthority() throws Exception {
		Object scenario = scenario();
		install(scenario, true);
		CandidateRealizationReference eagerNative = installedReference(scenario, "m-choice");
		CandidateSupportResult expected = resolver(scenario, null)
			.proveCandidateSupport(equalAlias(eagerNative), pool(scenario));

		NativeContinuitySupportClauses relation = install(scenario, false);
		CandidateRealizationReference lazyNative = installedReference(scenario, "m-choice");
		CandidateSupportResult actual = resolver(scenario, new SearchSpaceMetrics())
			.proveCandidateSupport(equalAlias(lazyNative), pool(scenario));

		assertParity(expected, actual);
		Assert.assertEquals("native aliases retain the factorized native circuit",
			1, relation.materializedHandleCount());
	}

	@Test
	public void structurallyEqualForeignOwnerCannotBorrowMissingCoverage() throws Exception {
		Object scenario = scenario();
		NativeContinuitySupportClauses relation = install(scenario, false);
		CandidateRealizationReference installed = installedReference(scenario, "a-choice");
		CompiledHopKey owner = installed.rule().parentOccurrence();
		CompiledHopKey foreign = new CompiledHopKey(owner.programFingerprint(),
			owner.functionNamespace(), owner.callSitePath(), owner.recompileContext(),
			owner.controlRegion(), owner.emittedHopInstance(), owner.canonicalSourceOrigin());
		Assert.assertEquals(owner, foreign);
		Assert.assertNotSame(owner, foreign);
		CandidateRealizationReference missing = new CandidateRealizationReference(
			new CandidateRuleKey(foreign, installed.rule().orderedInputs()),
			PlacementRealizationKey.nativeLineage(
				installed.realization().emissionState(), "foreign-missing-template"));

		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> resolver(scenario, new SearchSpaceMetrics())
				.proveCandidateSupport(missing, pool(scenario)));
		Assert.assertEquals("unknown owner identity", error.getMessage());
		Assert.assertTrue("foreign rejection cannot enumerate the native family",
			relation.materializedHandleCount() <= 1);
	}

	@Test
	public void disabledTopologyBudgetRetainsCompleteMissingPinFallback() throws Exception {
		synchronized(NativeMissingPinAdversarialTest.class) {
			String property = "sysds.fedplanner.continuityTopology.maxEntries";
			String prior = System.getProperty(property);
			try {
				Object scenario = scenario();
				install(scenario, true);
				CandidateRealizationReference eagerMissing = missing(scenario, "budget-missing");
				CandidateSupportResult expected = resolver(scenario, null)
					.proveCandidateSupport(eagerMissing, pool(scenario));

				System.setProperty(property, "0");
				NativeContinuitySupportClauses relation = install(scenario, false);
				CandidateSupportResult actual = resolver(scenario, new SearchSpaceMetrics())
					.proveCandidateSupport(missing(scenario, "budget-missing"), pool(scenario));

				assertParity(expected, actual);
				Assert.assertEquals("disabled coverage retains the complete legacy traversal",
					relation.size(), relation.materializedHandleCount());
			}
			finally {
				if(prior == null)
					System.clearProperty(property);
				else
					System.setProperty(property, prior);
			}
		}
	}

	@Test
	public void missingPinRetainsValueMapMetadataAcrossRevision() throws Exception {
		Object scenario = scenario();
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object fixture = invoke(scenario, "fixture");
		Object hiddenSeed = invoke(fixture, "source", "missing-hidden-seed", pool(scenario));
		Object hidden = invoke(fixture, "unary", "missing-hidden-owner", OpOp1.LOG, hiddenSeed, false);
		invoke(fixture, "samePoolRealizations", hidden, unary,
			new DurableAnchorKey[] {pool(scenario)});
		CompiledHopKey hiddenOwner = (CompiledHopKey)invoke(hidden, "key");
		CandidateRealizationReference hiddenReference =
			(CandidateRealizationReference)invoke(fixture, "reference", hidden, unary);
		CandidateEmissionFact childEmission = (CandidateEmissionFact)invoke(scenario, "childEmission");
		CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(
			childEmission.emissionState(), "missing-hidden-value-map",
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, hiddenReference)))));

		install(scenario, false, List.of(valueMap));
		List<CandidateRuleFact> activeFacts = List.copyOf(candidateFacts(fixture));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity active = resolver(scenario, metrics);
		CandidateSupportResult activeResult = active.proveCandidateSupport(
			missing(scenario, "metadata-missing"), pool(scenario));
		Assert.assertTrue(activeResult.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));

		List<CandidateRuleFact> withdrawnFacts = activeFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		long before = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity withdrawn = active.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, Set.of(hiddenOwner));
		CandidateSupportResult actual = withdrawn.proveCandidateSupport(
			missing(scenario, "metadata-missing"), pool(scenario));
		CandidateSupportResult cold = resolverWithFacts(scenario, withdrawnFacts)
			.proveCandidateSupport(missing(scenario, "metadata-missing"), pool(scenario));
		assertParity(cold, actual);
		Assert.assertTrue(actual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("metadata-only withdrawal invalidates missing-pin support",
			metrics.snapshot().proofGraphsBuilt() > before);
	}

	@Test
	public void missingPinRetainsDerivedMetadataAcrossRevision() throws Exception {
		Object scenario = scenario();
		Object fixture = invoke(scenario, "fixture");
		Object hiddenSeed = invoke(fixture, "source", "missing-derived-seed", pool(scenario));
		Object hidden = invoke(fixture, "unary", "missing-derived-owner",
			OpOp1.LOG, hiddenSeed, false);
		CompiledHopKey hiddenOwner = (CompiledHopKey)invoke(hidden, "key");
		invoke(scenario, "installDerived", false, hiddenOwner);
		List<CandidateRuleFact> validFacts = List.copyOf(candidateFacts(fixture));
		List<CandidateRuleFact> invalidFacts = validFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity invalid = resolverWithFacts(scenario, invalidFacts, metrics);
		CandidateSupportResult invalidResult = invalid.proveCandidateSupport(
			missing(scenario, "derived-missing"), pool(scenario));
		Assert.assertTrue(invalidResult.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));

		long before = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity valid = invalid.nextRevisionWithCompleteCandidateDelta(
			validFacts, Set.of(hiddenOwner));
		CandidateSupportResult actual = valid.proveCandidateSupport(
			missing(scenario, "derived-missing"), pool(scenario));
		CandidateSupportResult cold = resolverWithFacts(scenario, validFacts)
			.proveCandidateSupport(missing(scenario, "derived-missing"), pool(scenario));
		assertParity(cold, actual);
		Assert.assertTrue(actual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("validating hidden derived authority invalidates missing-pin support",
			metrics.snapshot().proofGraphsBuilt() > before);
	}

	private static CandidateRealizationReference equalAlias(CandidateRealizationReference reference) {
		return new CandidateRealizationReference(new CandidateRuleKey(
			reference.rule().parentOccurrence(), reference.rule().orderedInputs()),
			reference.realization());
	}

	private static CandidateRealizationReference missing(Object scenario, String lineage)
		throws Exception {
		CandidateRuleFact fact = (CandidateRuleFact)invoke(scenario, "childFact");
		CandidateEmissionFact emission = (CandidateEmissionFact)invoke(scenario, "childEmission");
		return CandidateRealizationReference.of(fact.key(),
			CandidateEmissionRealization.nativeLineage(emission.emissionState(),
				lineage, List.of(), List.of()));
	}

	private static Object scenario() throws Exception {
		Method method = NativeHybridUnpinnedTopologyTest.class.getDeclaredMethod(
			"scenario", String.class, List.class);
		method.setAccessible(true);
		return method.invoke(null, "m-choice", List.of("a-choice", "z-choice"));
	}

	private static NativeContinuitySupportClauses install(Object scenario, boolean explicit,
		Object... additional) throws Exception {
		if(additional.length == 0)
			return (NativeContinuitySupportClauses)invoke(scenario, "install", explicit);
		return (NativeContinuitySupportClauses)invoke(scenario, "install", explicit, additional[0]);
	}

	private static CandidateRealizationReference installedReference(Object scenario, String lineage)
		throws Exception {
		return (CandidateRealizationReference)invoke(scenario, "installedReference", lineage);
	}

	private static DurableAnchorKey pool(Object scenario) throws Exception {
		return (DurableAnchorKey)invoke(scenario, "pool");
	}

	private static NativePlacementContinuity resolver(Object scenario, SearchSpaceMetrics metrics)
		throws Exception {
		return (NativePlacementContinuity)invoke(invoke(scenario, "fixture"),
			"resolver", metrics, 128, 2048L);
	}

	private static NativePlacementContinuity resolverWithFacts(Object scenario,
		List<CandidateRuleFact> facts) throws Exception {
		return resolverWithFacts(scenario, facts, new SearchSpaceMetrics());
	}

	private static NativePlacementContinuity resolverWithFacts(Object scenario,
		List<CandidateRuleFact> facts, SearchSpaceMetrics metrics) throws Exception {
		Method method = NativeHybridUnpinnedTopologyTest.class.getDeclaredMethod(
			"resolverWithCandidateFacts", scenario.getClass(), List.class, SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (NativePlacementContinuity)method.invoke(null, scenario, facts, metrics);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> candidateFacts(Object fixture) throws Exception {
		Field field = fixture.getClass().getDeclaredField("candidates");
		field.setAccessible(true);
		return (List<CandidateRuleFact>)field.get(fixture);
	}

	private static void assertParity(CandidateSupportResult expected, CandidateSupportResult actual)
		throws Exception {
		Method method = NativePinnedMixedOwnerParityTest.class.getDeclaredMethod(
			"assertParity", CandidateSupportResult.class, CandidateSupportResult.class);
		method.setAccessible(true);
		method.invoke(null, expected, actual);
	}

	private static Object invoke(Object receiver, String name, Object... arguments)
		throws Exception {
		for(Method method : receiver.getClass().getDeclaredMethods())
			if(method.getName().equals(name) && method.getParameterCount() == arguments.length) {
				method.setAccessible(true);
				return method.invoke(receiver, arguments);
			}
		throw new NoSuchMethodException(receiver.getClass().getName() + '.' + name);
	}
}
