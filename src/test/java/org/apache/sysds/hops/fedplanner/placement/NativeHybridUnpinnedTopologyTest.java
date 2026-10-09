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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeHybridUnpinnedTopologyTest {
	private static final List<String> HYBRID_OUTCOMES = List.of(
		"NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_CONTEXT",
		"NATIVE_HYBRID_REJECT_DERIVED", "NATIVE_HYBRID_REJECT_REFERENCE",
		"NATIVE_HYBRID_REJECT_LAYOUT", "NATIVE_HYBRID_REJECT_PRODUCT",
		"NATIVE_HYBRID_REJECT_NO_NATIVE");
	private static final List<String> PINNED_OUTCOMES = List.of(
		"NATIVE_PINNED_ACCEPTED", "NATIVE_PINNED_REJECT_CONTEXT",
		"NATIVE_PINNED_REJECT_DUPLICATE", "NATIVE_PINNED_REJECT_ORDINARY",
		"NATIVE_PINNED_REJECT_LAYOUT", "NATIVE_PINNED_REJECT_MISSING",
		"NATIVE_PINNED_REJECT_PRODUCT");

	@Test
	public void nativeInventoryReadsExplicitOwnerFactsOnceAcrossRepeatedLookups()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		NativeContinuitySupportClauses lazy = scenario.install(false);
		CandidateRuleFact installed = installedChildFact(scenario);
		CandidateRealizationReference nativePin = scenario.installedReference("m-choice");
		CandidateRealizationReference ordinaryPin = scenario.installedReference("a-choice");
		List<CandidateRuleFact> ownerFacts = new ArrayList<>();
		for(int arity = 1; arity <= 32; arity++)
			ownerFacts.add(ruleVariant(installed, java.util.Collections.nCopies(
				arity, CandidateInputState.absentLocal())));
		ownerFacts.add(installed);
		CountingFactList counted = new CountingFactList(ownerFacts);
		NativePlacementContinuity resolver =
			(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
		replaceOwnerFacts(resolver, scenario.childKey(), counted);

		Assert.assertTrue(hasNativeContinuityRelation(resolver, scenario.childKey(), nativePin));
		for(int repeat = 0; repeat < 4; repeat++)
			Assert.assertNotNull(nativeFactoredAlternatives(
				resolver, scenario.childKey(), nativePin, scenario.pool()));
		Assert.assertFalse(hasNativeContinuityRelation(resolver, scenario.childKey(), ordinaryPin));
		Assert.assertNull(nativeFactoredAlternatives(
			resolver, scenario.childKey(), ordinaryPin, scenario.pool()));
		CandidateRealizationReference missing = new CandidateRealizationReference(
			nativePin.rule(), PlacementRealizationKey.nativeLineage(
				nativePin.realization().emissionState(), "missing-pinned-inventory-row"));
		Assert.assertFalse(hasNativeContinuityRelation(resolver, scenario.childKey(), missing));
		Assert.assertNull(nativeFactoredAlternatives(
			resolver, scenario.childKey(), missing, scenario.pool()));

		Assert.assertEquals("the owner inventory snapshots explicit facts once, not once per query",
			ownerFacts.size(), counted.gets());
		Assert.assertEquals("inventory lookup must not enumerate the native Cartesian product",
			1, lazy.materializedHandleCount());
	}

	@Test
	public void nativeInventoryPreservesFirstMatchingFactAndDuplicateClassification()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		NativeContinuitySupportClauses relation = scenario.install(false);
		CandidateRuleFact installed = installedChildFact(scenario);
		CandidateEmissionFact emission = installed.allowedEmissionFacts().get(0);
		CandidateRealizationReference ordinaryPin = scenario.installedReference("a-choice");
		CandidateEmissionRealization ordinary = emission.realizations().stream()
			.filter(realization -> realization.key().equals(ordinaryPin.realization()))
			.findFirst().orElseThrow();
		CandidateEmissionRealization nativeAlias = new CandidateEmissionRealization(
			ordinary.key(), relation);
		CandidateRuleFact ordinaryFact = singleRealizationFact(installed, emission, ordinary);
		CandidateRuleFact nativeFact = singleRealizationFact(installed, emission, nativeAlias);

		SearchSpaceMetrics ordinaryMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity ordinaryFirst = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", ordinaryMetrics, 128, 2048L);
		replaceOwnerFacts(ordinaryFirst, scenario.childKey(), List.of(ordinaryFact, nativeFact));
		Assert.assertNull(nativeFactoredAlternatives(
			ordinaryFirst, scenario.childKey(), ordinaryPin, scenario.pool()));
		Assert.assertEquals(1, directWork(ordinaryMetrics, "NATIVE_PINNED_REJECT_ORDINARY"));
		Assert.assertEquals(0, directWork(ordinaryMetrics, "NATIVE_PINNED_REJECT_DUPLICATE"));

		SearchSpaceMetrics duplicateMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity nativeFirst = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", duplicateMetrics, 128, 2048L);
		replaceOwnerFacts(nativeFirst, scenario.childKey(), List.of(nativeFact, ordinaryFact));
		Assert.assertNull(nativeFactoredAlternatives(
			nativeFirst, scenario.childKey(), ordinaryPin, scenario.pool()));
		Assert.assertEquals(0, directWork(duplicateMetrics, "NATIVE_PINNED_REJECT_ORDINARY"));
		Assert.assertEquals(1, directWork(duplicateMetrics, "NATIVE_PINNED_REJECT_DUPLICATE"));
		Assert.assertEquals("classification must not enumerate either native alias",
			1, relation.materializedHandleCount());
	}

	@Test
	public void publicOrdinaryPinFallsBackExactlyForDuplicateRealizationAuthority()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		NativeContinuitySupportClauses eagerRelation = scenario.install(true);
		CandidateRuleFact eagerInstalled = installedChildFact(scenario);
		CandidateEmissionFact eagerEmission = eagerInstalled.allowedEmissionFacts().get(0);
		CandidateRealizationReference eagerPin = scenario.installedReference("a-choice");
		CandidateEmissionRealization eagerOrdinary = eagerEmission.realizations().stream()
			.filter(realization -> realization.key().equals(eagerPin.realization()))
			.findFirst().orElseThrow();
		CandidateRuleFact eagerOrdinaryFact = singleRealizationFact(
			eagerInstalled, eagerEmission, eagerOrdinary);
		CandidateRuleFact eagerNativeFact = singleRealizationFact(eagerInstalled, eagerEmission,
			new CandidateEmissionRealization(eagerOrdinary.key(), List.copyOf(eagerRelation)));
		NativePlacementContinuity.CandidateSupportResult eagerOrdinaryFirst =
			queryInstalledWithOwnerFacts(scenario, "a-choice",
				List.of(eagerOrdinaryFact, eagerNativeFact), new SearchSpaceMetrics());
		NativePlacementContinuity.CandidateSupportResult eagerNativeFirst =
			queryInstalledWithOwnerFacts(scenario, "a-choice",
				List.of(eagerNativeFact, eagerOrdinaryFact), new SearchSpaceMetrics());

		NativeContinuitySupportClauses lazy = scenario.install(false);
		CandidateRuleFact lazyInstalled = installedChildFact(scenario);
		CandidateEmissionFact lazyEmission = lazyInstalled.allowedEmissionFacts().get(0);
		CandidateRealizationReference lazyPin = scenario.installedReference("a-choice");
		CandidateEmissionRealization lazyOrdinary = lazyEmission.realizations().stream()
			.filter(realization -> realization.key().equals(lazyPin.realization()))
			.findFirst().orElseThrow();
		CandidateRuleFact lazyOrdinaryFact = singleRealizationFact(
			lazyInstalled, lazyEmission, lazyOrdinary);
		CandidateRuleFact lazyNativeFact = singleRealizationFact(lazyInstalled, lazyEmission,
			new CandidateEmissionRealization(lazyOrdinary.key(), lazy));
		SearchSpaceMetrics ordinaryFirstMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult ordinaryFirst =
			queryInstalledWithOwnerFacts(scenario, "a-choice",
				List.of(lazyOrdinaryFact, lazyNativeFact), ordinaryFirstMetrics);
		SearchSpaceMetrics nativeFirstMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult nativeFirst =
			queryInstalledWithOwnerFacts(scenario, "a-choice",
				List.of(lazyNativeFact, lazyOrdinaryFact), nativeFirstMetrics);

		Assert.assertFalse(ordinaryFirst.proofs().isEmpty());
		Assert.assertFalse(nativeFirst.proofs().isEmpty());
		Assert.assertEquals(signatures(eagerOrdinaryFirst), signatures(ordinaryFirst));
		Assert.assertEquals(signatures(eagerNativeFirst), signatures(nativeFirst));
		assertBindingSourceIdentity(eagerOrdinaryFirst, ordinaryFirst);
		assertBindingSourceIdentity(eagerNativeFirst, nativeFirst);
		assertIdentitySetEquals(eagerOrdinaryFirst.dependencyOccurrences(),
			ordinaryFirst.dependencyOccurrences());
		assertIdentitySetEquals(eagerNativeFirst.dependencyOccurrences(),
			nativeFirst.dependencyOccurrences());
		Assert.assertTrue("ordinary-first ambiguity must retain the ordinary fallback",
			directWork(ordinaryFirstMetrics, "NATIVE_PINNED_REJECT_ORDINARY") > 0);
		Assert.assertTrue("native-first ambiguity must retain duplicate fallback",
			directWork(nativeFirstMetrics, "NATIVE_PINNED_REJECT_DUPLICATE") > 0);
	}

	@Test
	public void nativeInventoryRejectsForeignOwnerAndDoesNotSurviveRevisionReplacement()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		scenario.install(false);
		CandidateRuleFact installed = installedChildFact(scenario);
		CandidateRealizationReference nativePin = scenario.installedReference("m-choice");
		NativePlacementContinuity resolver =
			(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
		Assert.assertTrue(hasNativeContinuityRelation(resolver, scenario.childKey(), nativePin));

		CandidateRealizationReference equalRule = new CandidateRealizationReference(
			new CandidateRuleKey(scenario.childKey(), nativePin.rule().orderedInputs()),
			nativePin.realization());
		Assert.assertTrue("equal rule and realization keys retain the exact owner identity",
			hasNativeContinuityRelation(resolver, scenario.childKey(), equalRule));
		CompiledHopKey owner = scenario.childKey();
		CompiledHopKey foreignOwner = new CompiledHopKey(owner.programFingerprint(),
			owner.functionNamespace(), owner.callSitePath(), owner.recompileContext(),
			owner.controlRegion(), owner.emittedHopInstance(), owner.canonicalSourceOrigin());
		Assert.assertEquals(owner, foreignOwner);
		Assert.assertNotSame(owner, foreignOwner);
		CandidateRealizationReference foreignPin = new CandidateRealizationReference(
			new CandidateRuleKey(foreignOwner, nativePin.rule().orderedInputs()),
			nativePin.realization());
		Assert.assertFalse("structurally equal foreign owners never acquire inventory authority",
			hasNativeContinuityRelation(resolver, foreignOwner, foreignPin));
		IllegalArgumentException foreign = Assert.assertThrows(IllegalArgumentException.class,
			() -> resolver.proveCandidateSupport(foreignPin, scenario.pool()));
		Assert.assertEquals("unknown owner identity", foreign.getMessage());

		CandidateEmissionFact emission = installed.allowedEmissionFacts().get(0);
		CandidateEmissionRealization ordinary = emission.realizations().stream()
			.filter(realization -> !(realization.supportClauses()
				instanceof NativeContinuitySupportClauses)).findFirst().orElseThrow();
		CandidateRuleFact ordinaryOnly = singleRealizationFact(installed, emission, ordinary);
		List<CandidateRuleFact> revisedFacts = new ArrayList<>(candidateFacts(scenario.fixture()));
		int position = revisedFacts.indexOf(installed);
		Assert.assertTrue(position >= 0);
		revisedFacts.set(position, ordinaryOnly);
		NativePlacementContinuity revision = resolver.nextRevisionWithCompleteCandidateDelta(
			List.copyOf(revisedFacts), Set.of(owner));
		Assert.assertFalse("a revision must build a fresh owner inventory from replacement facts",
			hasNativeContinuityRelation(revision, owner, nativePin));
		Assert.assertNull(nativeFactoredAlternatives(revision, owner, nativePin, scenario.pool()));
	}

	@Test
	public void metricsClassifyAcceptedHybridWithoutChangingProofAuthority() throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		NativePlacementContinuity.CandidateSupportResult explicit = scenario.query(true);
		NativeContinuitySupportClauses relation = scenario.install(false);
		NativePlacementContinuity.CandidateSupportResult metricsOff = scenario.queryCurrent();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		NativePlacementContinuity.CandidateSupportResult metricsOn = scenario.queryCurrent(resolver);

		Assert.assertEquals(signatures(explicit), signatures(metricsOff));
		Assert.assertEquals(signatures(metricsOff), signatures(metricsOn));
		assertBindingSourceIdentity(metricsOff, metricsOn);
		assertIdentitySetEquals(metricsOff.dependencyOccurrences(), metricsOn.dependencyOccurrences());
		Assert.assertEquals(1, relation.materializedHandleCount());
		// The exact witness admits the circuit; the dynamic witness has no eligible native row.
		assertHybridCounts(metrics, "NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE");
	}

	@Test
	public void pinnedNativeAndOrdinaryRowsClassifyColdAndResidentTopologyWithoutChangingAuthority()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		Assert.assertEquals("the fixture must expose a genuine two-by-three native product",
			6, scenario.product().size());
		scenario.install(true);
		NativePlacementContinuity eagerResolver =
			(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
		NativePlacementContinuity.CandidateSupportResult eager =
			scenario.queryInstalled("m-choice", eagerResolver);
		NativePlacementContinuity.CandidateSupportResult eagerOrdinaryA =
			scenario.queryInstalled("a-choice", eagerResolver);
		NativePlacementContinuity.CandidateSupportResult eagerOrdinaryZ =
			scenario.queryInstalled("z-choice", eagerResolver);
		scenario.install(false);
		NativePlacementContinuity metricsOffResolver =
			(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
		NativePlacementContinuity.CandidateSupportResult metricsOff =
			scenario.queryInstalled("m-choice", metricsOffResolver);
		NativePlacementContinuity.CandidateSupportResult metricsOffOrdinaryA =
			scenario.queryInstalled("a-choice", metricsOffResolver);
		NativePlacementContinuity.CandidateSupportResult metricsOffOrdinaryZ =
			scenario.queryInstalled("z-choice", metricsOffResolver);
		NativeContinuitySupportClauses lazy = scenario.install(false);
		Assert.assertEquals("an ordinary pin must precede and follow the native pin",
			1, scenario.installedNativeOrdinal());
		Assert.assertEquals("canonical candidate construction reads only the representative member",
			1, lazy.materializedHandleCount());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity nativeResolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		NativePlacementContinuity.CandidateSupportResult metricsOn = scenario.queryInstalled(
			"m-choice", nativeResolver);

		Assert.assertEquals(signatures(eager), signatures(metricsOn));
		Assert.assertEquals(signatures(metricsOff), signatures(metricsOn));
		assertBindingSourceIdentity(eager, metricsOn);
		assertBindingSourceIdentity(metricsOff, metricsOn);
		assertIdentitySetEquals(eager.dependencyOccurrences(), metricsOn.dependencyOccurrences());
		assertIdentitySetEquals(metricsOff.dependencyOccurrences(), metricsOn.dependencyOccurrences());
		Assert.assertEquals("the accepted native pin does not expand beyond the representative",
			1, lazy.materializedHandleCount());
		assertNativePinnedCounts(metrics);

		// Keep the legacy topology counters on an explicitly materialized fixture.
		// Selective ordinary projection has its own coverage and intentionally bypasses
		// this fallback route when a lazy native sibling is present.
		scenario.install(true);
		NativePlacementContinuity ordinaryResolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		NativePlacementContinuity.CandidateSupportResult metricsOnOrdinaryA =
			scenario.queryInstalled("a-choice", ordinaryResolver);
		NativePlacementContinuity.CandidateSupportResult metricsOnOrdinaryZ =
			scenario.queryInstalled("z-choice", ordinaryResolver);
		assertResultParity(eagerOrdinaryA, metricsOffOrdinaryA, metricsOnOrdinaryA);
		assertResultParity(eagerOrdinaryZ, metricsOffOrdinaryZ, metricsOnOrdinaryZ);
		assertAcyclicRootTopologyCounts(metrics);

		metrics.reset();
		Assert.assertEquals(0, directWork(metrics, "NATIVE_PINNED_REQUESTS"));
		Assert.assertEquals(0, directWork(metrics, "NATIVE_PINNED_ORDINARY_COLD_TOPOLOGY"));
		Assert.assertEquals(0, directWork(metrics, "NATIVE_PINNED_ORDINARY_RESIDENT_TOPOLOGY"));
		Assert.assertEquals(0, directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_REQUESTS"));
		Assert.assertEquals(0, directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_COLD"));
		Assert.assertEquals(0, directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_RESIDENT"));
		Assert.assertEquals(0, directWork(metrics, "SUPPORT_QUERY_TOPOLOGY_REQUESTS"));
		Assert.assertEquals(0, directWork(metrics, "SUPPORT_QUERY_TOPOLOGY_COLD"));
		Assert.assertEquals(0, directWork(metrics, "SUPPORT_QUERY_TOPOLOGY_RESIDENT"));
		for(String outcome : PINNED_OUTCOMES)
			Assert.assertEquals(outcome, 0, directWork(metrics, outcome));
	}

	@Test
	public void ordinaryPinsReusePartialTopologyWithoutExpandingTheNativeProduct()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		Assert.assertEquals("the fixture must expose a genuine two-by-three native product",
			6, scenario.product().size());

		scenario.install(true);
		NativePlacementContinuity eagerResolver =
			(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
		NativePlacementContinuity.CandidateSupportResult eagerA =
			scenario.queryInstalled("a-choice", eagerResolver);
		NativePlacementContinuity.CandidateSupportResult eagerZ =
			scenario.queryInstalled("z-choice", eagerResolver);

		scenario.install(false);
		NativePlacementContinuity offResolver =
			(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
		NativePlacementContinuity.CandidateSupportResult offA =
			scenario.queryInstalled("a-choice", offResolver);
		NativePlacementContinuity.CandidateSupportResult offZ =
			scenario.queryInstalled("z-choice", offResolver);

		NativeContinuitySupportClauses lazy = scenario.install(false);
		Assert.assertEquals("candidate construction reads exactly one representative",
			1, lazy.materializedHandleCount());
		List<CandidateRuleFact> unchangedFacts = List.copyOf(candidateFacts(scenario.fixture()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);

		NativePlacementContinuity.CandidateSupportResult actualA =
			scenario.queryInstalled("a-choice", resolver);
		assertResultParity(eagerA, offA, actualA);
		Assert.assertEquals("projecting the first ordinary pin must not enumerate the product",
			1, lazy.materializedHandleCount());
		NativePlacementContinuity.CandidateSupportResult actualZ =
			scenario.queryInstalled("z-choice", resolver);
		assertResultParity(eagerZ, offZ, actualZ);
		Assert.assertEquals("a second ordinary pin must also avoid native product expansion",
			1, lazy.materializedHandleCount());

		NativePlacementContinuity.CandidateSupportResult replayA =
			scenario.queryInstalled("a-choice", resolver);
		assertResultParity(eagerA, offA, replayA);
		Assert.assertEquals("a warm replay must retain the representative-only relation",
			1, lazy.materializedHandleCount());

		NativePlacementContinuity unchanged = resolver.nextRevisionWithCompleteCandidateDelta(
			unchangedFacts, Set.of());
		NativePlacementContinuity.CandidateSupportResult revisedZ =
			scenario.queryInstalled("z-choice", unchanged);
		assertResultParity(eagerZ, offZ, revisedZ);
		Assert.assertEquals("an exact unmodified revision must retain lazy projected authority",
			1, lazy.materializedHandleCount());
	}

	@Test
	public void ordinaryPartialTopologyIsSharedAcrossPinsAndUpgradedForGenericTraversal()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		NativeContinuitySupportClauses lazy = scenario.install(false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 0, 0L);

		NativePlacementContinuity.CandidateSupportResult first =
			scenario.queryInstalled("a-choice", resolver);
		Assert.assertFalse(first.proofs().isEmpty());
		Object partial = residentTopology(resolver, scenario.childKey(), scenario.pool());
		Assert.assertNotNull("the first ordinary pin admits one owner-wide partial topology", partial);
		Assert.assertTrue("the admitted topology is tagged ordinary-only", ordinaryOnly(partial));
		Assert.assertTrue("the first build indexes the first ordinary handle",
			topologyContainsHandle(partial,
				candidateHandle(resolver, scenario.installedReference("a-choice"))));
		Assert.assertTrue("the same build indexes every other declared ordinary handle",
			topologyContainsHandle(partial,
				candidateHandle(resolver, scenario.installedReference("z-choice"))));
		Assert.assertFalse("ordinary-only topology excludes the native sibling handle",
			topologyContainsHandle(partial,
				candidateHandle(resolver, scenario.installedReference("m-choice"))));
		long attempts = directWork(metrics, "ORDINARY_TOPOLOGY_BUILD_ATTEMPTS");
		long admissions = directWork(metrics, "ORDINARY_TOPOLOGY_ADMISSIONS");
		long hits = directWork(metrics, "ORDINARY_TOPOLOGY_RESIDENT_HITS");
		Assert.assertTrue(attempts > 0);
		Assert.assertTrue(admissions > 0);
		Assert.assertTrue("the native family is skipped by logical cardinality",
			directWork(metrics, "ORDINARY_TOPOLOGY_NATIVE_LOGICAL_MEMBERS_SKIPPED")
				>= scenario.product().size());

		NativePlacementContinuity.CandidateSupportResult second =
			scenario.queryInstalled("z-choice", resolver);
		Assert.assertFalse(second.proofs().isEmpty());
		Assert.assertSame("a different ordinary handle shares the owner-wide partial topology",
			partial, residentTopology(resolver, scenario.childKey(), scenario.pool()));
		Assert.assertEquals("the second pin must not rebuild the partial topology", attempts,
			directWork(metrics, "ORDINARY_TOPOLOGY_BUILD_ATTEMPTS"));
		Assert.assertEquals(admissions, directWork(metrics, "ORDINARY_TOPOLOGY_ADMISSIONS"));
		Assert.assertTrue(directWork(metrics, "ORDINARY_TOPOLOGY_RESIDENT_HITS") > hits);
		hits = directWork(metrics, "ORDINARY_TOPOLOGY_RESIDENT_HITS");
		scenario.queryInstalled("a-choice", resolver);
		Assert.assertSame(partial, residentTopology(resolver, scenario.childKey(), scenario.pool()));
		Assert.assertTrue("a repeated first pin also hits the same resident topology",
			directWork(metrics, "ORDINARY_TOPOLOGY_RESIDENT_HITS") > hits);
		Assert.assertEquals(1, lazy.materializedHandleCount());

		Object complete = genericCandidateTopology(resolver, scenario.childKey(), scenario.pool());
		Assert.assertNotSame("generic traversal replaces rather than reuses an incomplete topology",
			partial, complete);
		Assert.assertFalse("generic traversal upgrades the resident entry to complete",
			ordinaryOnly(complete));
		Assert.assertSame(complete,
			residentTopology(resolver, scenario.childKey(), scenario.pool()));
		Assert.assertTrue("the complete replacement restores the native sibling handle",
			topologyContainsHandle(complete,
				candidateHandle(resolver, scenario.installedReference("m-choice"))));
		Assert.assertTrue("the generic request records one complete-upgrade attempt",
			directWork(metrics, "ORDINARY_TOPOLOGY_COMPLETE_UPGRADE_ATTEMPTS") > 0);
		Assert.assertEquals("complete legacy traversal materializes the six native members",
			scenario.product().size(), lazy.materializedHandleCount());
	}

	@Test
	public void failedCompleteUpgradeKeepsTheResidentOrdinaryPartialTopology()
		throws Exception {
		synchronized(NativeHybridUnpinnedTopologyTest.class) {
			String entriesKey = "sysds.fedplanner.continuityTopology.maxEntries";
			String rowsKey = "sysds.fedplanner.continuityTopology.maxRows";
			String oldEntries = System.getProperty(entriesKey);
			String oldRows = System.getProperty(rowsKey);
			try {
				Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
				scenario.install(true);
				NativePlacementContinuity eagerResolver =
					(NativePlacementContinuity)invoke(scenario.fixture(), "resolver");
				NativePlacementContinuity.CandidateSupportResult eagerA =
					scenario.queryInstalled("a-choice", eagerResolver);
				NativePlacementContinuity.CandidateSupportResult eagerZ =
					scenario.queryInstalled("z-choice", eagerResolver);

				System.setProperty(entriesKey, "8");
				// The two ordinary rows and their dependency topologies fit; the
				// eight-row complete owner topology alone exceeds this shared budget.
				System.setProperty(rowsKey, "7");
				NativeContinuitySupportClauses lazy = scenario.install(false);
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
					scenario.fixture(), "resolver", metrics, 0, 0L);

				NativePlacementContinuity.CandidateSupportResult actualA =
					scenario.queryInstalled("a-choice", resolver);
				assertResultParity(eagerA, actualA, actualA);
				Object partial = residentTopology(resolver, scenario.childKey(), scenario.pool());
				Assert.assertNotNull(partial);
				Assert.assertTrue(ordinaryOnly(partial));
				Assert.assertEquals(1, lazy.materializedHandleCount());

				long bypasses = metrics.snapshot().topologyCacheBypasses();
				Object complete = genericCandidateTopology(resolver,
					scenario.childKey(), scenario.pool());
				Assert.assertNotSame(partial, complete);
				Assert.assertFalse("the caller still receives the complete traversal",
					ordinaryOnly(complete));
				Assert.assertTrue("the complete traversal contains the native sibling",
					topologyContainsHandle(complete,
						candidateHandle(resolver, scenario.installedReference("m-choice"))));
				Assert.assertSame("a failed complete admission must not evict the useful partial",
					partial, residentTopology(resolver, scenario.childKey(), scenario.pool()));
				Assert.assertTrue(ordinaryOnly(
					residentTopology(resolver, scenario.childKey(), scenario.pool())));
				Assert.assertTrue(directWork(metrics,
					"ORDINARY_TOPOLOGY_COMPLETE_UPGRADE_ATTEMPTS") > 0);
				Assert.assertTrue(metrics.snapshot().topologyCacheBypasses() > bypasses);
				Assert.assertEquals("the deliberate complete traversal materializes all members",
					scenario.product().size(), lazy.materializedHandleCount());

				NativePlacementContinuity.CandidateSupportResult actualZ =
					scenario.queryInstalled("z-choice", resolver);
				assertResultParity(eagerZ, actualZ, actualZ);
				Assert.assertSame("the next ordinary pin reuses the surviving partial topology",
					partial, residentTopology(resolver, scenario.childKey(), scenario.pool()));
			}
			finally {
				restoreProperty(entriesKey, oldEntries);
				restoreProperty(rowsKey, oldRows);
			}
		}
	}

	@Test
	public void ordinaryPartialTopologyPreservesParityWithAnalysisAndResolverLocalHandles()
		throws Exception {
		synchronized(NativeHybridUnpinnedTopologyTest.class) {
			String arenaKey = "sysds.fedplanner.structuralArena.maxEntries";
			String oldArena = System.getProperty(arenaKey);
			try {
				for(boolean exhaustArena : List.of(false, true)) {
					System.setProperty(arenaKey, exhaustArena ? "0" : "1024");
					SearchSpaceMetrics metrics = new SearchSpaceMetrics();
					PlacementIdentity.beginAnalysisScope(metrics);
					try {
						Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
						scenario.install(true);
						NativePlacementContinuity.CandidateSupportResult eager =
							scenario.queryInstalled("a-choice",
								(NativePlacementContinuity)invoke(scenario.fixture(), "resolver"));
						NativeContinuitySupportClauses lazy = scenario.install(false);
						CandidateRealizationReference pinned =
							scenario.installedReference("a-choice");
						Integer analysisHandle = PlacementIdentity.structuralHandle(pinned);
						if(exhaustArena)
							Assert.assertNull("the exhausted arena selects resolver-local handle identity",
								analysisHandle);
						else
							Assert.assertNotNull("the active arena supplies the shared structural identity",
								analysisHandle);

						NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
							scenario.fixture(), "resolver", metrics, 0, 0L);
						NativePlacementContinuity.CandidateSupportResult actual =
							scenario.queryInstalled("a-choice", resolver);
						Assert.assertEquals(signatures(eager), signatures(actual));
						assertBindingSourceIdentity(eager, actual);
						assertIdentitySetEquals(eager.dependencyOccurrences(),
							actual.dependencyOccurrences());
						Assert.assertEquals("either handle authority keeps the native family compressed",
							1, lazy.materializedHandleCount());
						Object partial = residentTopology(resolver,
							scenario.childKey(), scenario.pool());
						Assert.assertNotNull(partial);
						Assert.assertTrue(ordinaryOnly(partial));
					}
					finally {
						PlacementIdentity.endAnalysisScope();
					}
				}
			}
			finally {
				PlacementIdentity.endAnalysisScope();
				restoreProperty(arenaKey, oldArena);
			}
		}
	}

	@Test
	public void disabledTopologyBudgetsBypassPartialAdmissionAndKeepExactFallback()
		throws Exception {
		synchronized(NativeHybridUnpinnedTopologyTest.class) {
			String entriesKey = "sysds.fedplanner.continuityTopology.maxEntries";
			String rowsKey = "sysds.fedplanner.continuityTopology.maxRows";
			String oldEntries = System.getProperty(entriesKey);
			String oldRows = System.getProperty(rowsKey);
			try {
				for(String[] budget : List.of(
					new String[] {"0", "2048"}, new String[] {"2048", "0"})) {
					Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
					scenario.install(true);
					NativePlacementContinuity.CandidateSupportResult eager = scenario.queryInstalled(
						"a-choice", (NativePlacementContinuity)invoke(scenario.fixture(), "resolver"));
					System.setProperty(entriesKey, budget[0]);
					System.setProperty(rowsKey, budget[1]);
					NativeContinuitySupportClauses lazy = scenario.install(false);
					SearchSpaceMetrics metrics = new SearchSpaceMetrics();
					NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
						scenario.fixture(), "resolver", metrics, 128, 2048L);
					NativePlacementContinuity.CandidateSupportResult actual =
						scenario.queryInstalled("a-choice", resolver);
					Assert.assertEquals(signatures(eager), signatures(actual));
					assertBindingSourceIdentity(eager, actual);
					assertIdentitySetEquals(eager.dependencyOccurrences(), actual.dependencyOccurrences());
					Assert.assertNull(
						residentTopology(resolver, scenario.childKey(), scenario.pool()));
					Assert.assertEquals(0, directWork(metrics, "ORDINARY_TOPOLOGY_ADMISSIONS"));
					Assert.assertTrue(directWork(metrics, "ORDINARY_TOPOLOGY_BYPASSES") > 0);
					Assert.assertEquals("disabled topology admission preserves complete legacy fallback",
						scenario.product().size(), lazy.materializedHandleCount());
					restoreProperty(entriesKey, oldEntries);
					restoreProperty(rowsKey, oldRows);
				}
			}
			finally {
				restoreProperty(entriesKey, oldEntries);
				restoreProperty(rowsKey, oldRows);
			}
		}
	}

	@Test
	public void insufficientRowBudgetRejectsPartialAdmissionWithoutChangingAuthority()
		throws Exception {
		synchronized(NativeHybridUnpinnedTopologyTest.class) {
			String entriesKey = "sysds.fedplanner.continuityTopology.maxEntries";
			String rowsKey = "sysds.fedplanner.continuityTopology.maxRows";
			String oldEntries = System.getProperty(entriesKey);
			String oldRows = System.getProperty(rowsKey);
			try {
				Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
				scenario.install(true);
				NativePlacementContinuity.CandidateSupportResult eager = scenario.queryInstalled(
					"a-choice", (NativePlacementContinuity)invoke(scenario.fixture(), "resolver"));
				System.setProperty(entriesKey, "8");
				System.setProperty(rowsKey, "1");
				NativeContinuitySupportClauses lazy = scenario.install(false);
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
					scenario.fixture(), "resolver", metrics, 128, 2048L);
				NativePlacementContinuity.CandidateSupportResult actual =
					scenario.queryInstalled("a-choice", resolver);
				Assert.assertEquals(signatures(eager), signatures(actual));
				assertBindingSourceIdentity(eager, actual);
				assertIdentitySetEquals(eager.dependencyOccurrences(), actual.dependencyOccurrences());
				Assert.assertNull(
					residentTopology(resolver, scenario.childKey(), scenario.pool()));
				// A different, dynamic witness may still admit an empty ordinary
				// topology. The exact witness above must not be resident.
				Assert.assertTrue(directWork(metrics, "ORDINARY_TOPOLOGY_BYPASSES") > 0);
				Assert.assertEquals("a rejected partial entry must fall back to complete enumeration",
					scenario.product().size(), lazy.materializedHandleCount());
			}
			finally {
				restoreProperty(entriesKey, oldEntries);
				restoreProperty(rowsKey, oldRows);
			}
		}
	}

	@Test
	public void ordinaryOnlyTopologyIsRebuiltRatherThanMigratedAcrossRevision()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		NativeContinuitySupportClauses lazy = scenario.install(false);
		List<CandidateRuleFact> unchangedFacts = List.copyOf(candidateFacts(scenario.fixture()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 0, 0L);
		scenario.queryInstalled("a-choice", resolver);
		Object original = residentTopology(resolver, scenario.childKey(), scenario.pool());
		Assert.assertNotNull(original);
		Assert.assertTrue(ordinaryOnly(original));
		long admissions = directWork(metrics, "ORDINARY_TOPOLOGY_ADMISSIONS");

		NativePlacementContinuity revision = resolver.nextRevisionWithCompleteCandidateDelta(
			unchangedFacts, Set.of());
		Assert.assertNull("ordinary-only topology entries are revision-local",
			residentTopology(revision, scenario.childKey(), scenario.pool()));
		scenario.queryInstalled("z-choice", revision);
		Object rebuilt = residentTopology(revision, scenario.childKey(), scenario.pool());
		Assert.assertNotNull(rebuilt);
		Assert.assertNotSame(original, rebuilt);
		Assert.assertTrue(ordinaryOnly(rebuilt));
		Assert.assertTrue("the first query in the revision admits a fresh partial topology",
			directWork(metrics, "ORDINARY_TOPOLOGY_ADMISSIONS") > admissions);
		Assert.assertEquals(1, lazy.materializedHandleCount());
	}

	@Test
	public void ordinaryPinRetainsHiddenValueMapMetadataAcrossWithdrawalAndRestoration()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object hiddenSeed = invoke(scenario.fixture(), "source",
			"pinned-hidden-seed", scenario.pool());
		Object hidden = invoke(scenario.fixture(), "unary", "pinned-hidden-owner",
			OpOp1.LOG, hiddenSeed, false);
		invoke(scenario.fixture(), "samePoolRealizations", hidden, unary,
			new DurableAnchorKey[] {scenario.pool()});
		CompiledHopKey hiddenOwner = (CompiledHopKey)invoke(hidden, "key");
		CandidateRealizationReference hiddenReference = (CandidateRealizationReference)invoke(
			scenario.fixture(), "reference", hidden, unary);
		CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(
			scenario.childEmission().emissionState(), "pinned-hidden-value-map",
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, hiddenReference)))));

		scenario.install(true, List.of(valueMap));
		NativePlacementContinuity.CandidateSupportResult eager = scenario.queryInstalled(
			"a-choice", (NativePlacementContinuity)invoke(scenario.fixture(), "resolver"));
		scenario.install(false, List.of(valueMap));
		NativePlacementContinuity.CandidateSupportResult off = scenario.queryInstalled(
			"a-choice", (NativePlacementContinuity)invoke(scenario.fixture(), "resolver"));
		NativeContinuitySupportClauses lazy = scenario.install(false, List.of(valueMap));
		List<CandidateRuleFact> activeFacts = List.copyOf(candidateFacts(scenario.fixture()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		NativePlacementContinuity.CandidateSupportResult active =
			scenario.queryInstalled("a-choice", resolver);
		assertResultParity(eager, off, active);
		Assert.assertTrue("the unselected VALUE_MAP metadata owner remains in the footprint",
			active.dependencyOccurrences().stream().anyMatch(owner -> owner == hiddenOwner));
		Assert.assertFalse("the hidden metadata owner is outside the native product axes",
			lazy.commonAxes().stream().flatMap(List::stream)
				.anyMatch(binding -> binding.source().rule().parentOccurrence() == hiddenOwner));
		Assert.assertEquals("the ordinary pin must not expand the native product",
			1, lazy.materializedHandleCount());

		List<CandidateRuleFact> withdrawnFacts = activeFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		Assert.assertEquals(activeFacts.size() - 1, withdrawnFacts.size());
		long beforeWithdrawal = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity withdrawn = resolver.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult withdrawnActual =
			scenario.queryInstalled("a-choice", withdrawn);
		NativePlacementContinuity.CandidateSupportResult withdrawnCold =
			queryInstalledWithCandidateFacts(scenario, "a-choice", withdrawnFacts);
		Assert.assertEquals(signatures(withdrawnCold), signatures(withdrawnActual));
		assertBindingSourceIdentity(withdrawnCold, withdrawnActual);
		assertIdentitySetEquals(withdrawnCold.dependencyOccurrences(),
			withdrawnActual.dependencyOccurrences());
		Assert.assertTrue("the conservative footprint must retain the withdrawn metadata identity",
			withdrawnActual.dependencyOccurrences().stream().anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("withdrawing only hidden metadata must invalidate completed support",
			metrics.snapshot().proofGraphsBuilt() > beforeWithdrawal);
		Assert.assertEquals(1, lazy.materializedHandleCount());

		long beforeRestoration = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			activeFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult restoredActual =
			scenario.queryInstalled("a-choice", restored);
		NativePlacementContinuity.CandidateSupportResult restoredCold =
			queryInstalledWithCandidateFacts(scenario, "a-choice", activeFacts);
		Assert.assertEquals(signatures(eager), signatures(restoredActual));
		Assert.assertEquals(signatures(restoredCold), signatures(restoredActual));
		assertBindingSourceIdentity(eager, restoredActual);
		assertIdentitySetEquals(restoredCold.dependencyOccurrences(),
			restoredActual.dependencyOccurrences());
		Assert.assertTrue(restoredActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("restoring only hidden metadata must invalidate withdrawn support",
			metrics.snapshot().proofGraphsBuilt() > beforeRestoration);
		Assert.assertEquals("withdrawal and restoration must preserve lazy projected authority",
			1, lazy.materializedHandleCount());
	}

	@Test
	public void ordinaryPinRetainsInvalidDerivedMetadataAcrossWithdrawalAndRestoration()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object hiddenSeed = invoke(scenario.fixture(), "source",
			"pinned-derived-hidden-seed", scenario.pool());
		Object hidden = invoke(scenario.fixture(), "unary", "pinned-derived-hidden-owner",
			OpOp1.LOG, hiddenSeed, false);
		CompiledHopKey hiddenOwner = (CompiledHopKey)invoke(hidden, "key");
		invoke(scenario.fixture(), "withClauses", hidden, unary,
			List.of(new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, hiddenOwner,
				"pinned-derived-hidden-authority")), List.of(), scenario.pool(), true)));

		scenario.installDerived(true, hiddenOwner);
		List<CandidateRuleFact> eagerValidFacts = List.copyOf(candidateFacts(scenario.fixture()));
		List<CandidateRuleFact> eagerInvalidFacts = eagerValidFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		NativePlacementContinuity.CandidateSupportResult eagerValid =
			queryInstalledWithCandidateFacts(scenario, "a-choice", eagerValidFacts);
		NativePlacementContinuity.CandidateSupportResult eagerInvalid =
			queryInstalledWithCandidateFacts(scenario, "a-choice", eagerInvalidFacts);

		NativeContinuitySupportClauses lazy = scenario.installDerived(false, hiddenOwner);
		List<CandidateRuleFact> validFacts = List.copyOf(candidateFacts(scenario.fixture()));
		List<CandidateRuleFact> invalidFacts = validFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity invalid = resolverWithCandidateFacts(
			scenario, invalidFacts, metrics);
		NativePlacementContinuity.CandidateSupportResult invalidActual =
			scenario.queryInstalled("a-choice", invalid);
		NativePlacementContinuity.CandidateSupportResult invalidCold =
			queryInstalledWithCandidateFacts(scenario, "a-choice", invalidFacts);
		Assert.assertEquals(signatures(eagerInvalid), signatures(invalidActual));
		Assert.assertEquals(signatures(invalidCold), signatures(invalidActual));
		assertBindingSourceIdentity(eagerInvalid, invalidActual);
		assertIdentitySetEquals(invalidCold.dependencyOccurrences(),
			invalidActual.dependencyOccurrences());
		Assert.assertTrue("an invalid unselected derived row still owns conservative metadata",
			invalidActual.dependencyOccurrences().stream().anyMatch(owner -> owner == hiddenOwner));
		Assert.assertFalse("the hidden derived owner is outside every native product axis",
			lazy.commonAxes().stream().flatMap(List::stream)
				.anyMatch(binding -> binding.source().rule().parentOccurrence() == hiddenOwner));
		Assert.assertEquals("invalid sibling metadata must not force native product expansion",
			1, lazy.materializedHandleCount());

		long beforeValidation = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity valid = invalid.nextRevisionWithCompleteCandidateDelta(
			validFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult validActual =
			scenario.queryInstalled("a-choice", valid);
		NativePlacementContinuity.CandidateSupportResult validCold =
			queryInstalledWithCandidateFacts(scenario, "a-choice", validFacts);
		Assert.assertEquals(signatures(eagerValid), signatures(validActual));
		Assert.assertEquals(signatures(validCold), signatures(validActual));
		assertBindingSourceIdentity(eagerValid, validActual);
		assertIdentitySetEquals(validCold.dependencyOccurrences(), validActual.dependencyOccurrences());
		Assert.assertTrue(validActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("validating only the hidden owner must invalidate pinned support",
			metrics.snapshot().proofGraphsBuilt() > beforeValidation);
		Assert.assertEquals(1, lazy.materializedHandleCount());

		long beforeWithdrawal = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity withdrawn = valid.nextRevisionWithCompleteCandidateDelta(
			invalidFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult withdrawnActual =
			scenario.queryInstalled("a-choice", withdrawn);
		Assert.assertEquals(signatures(eagerInvalid), signatures(withdrawnActual));
		assertBindingSourceIdentity(eagerInvalid, withdrawnActual);
		Assert.assertTrue(withdrawnActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("withdrawing hidden derived authority must invalidate pinned support",
			metrics.snapshot().proofGraphsBuilt() > beforeWithdrawal);

		long beforeRestoration = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			validFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult restoredActual =
			scenario.queryInstalled("a-choice", restored);
		Assert.assertEquals(signatures(eagerValid), signatures(restoredActual));
		assertBindingSourceIdentity(eagerValid, restoredActual);
		assertIdentitySetEquals(validCold.dependencyOccurrences(),
			restoredActual.dependencyOccurrences());
		Assert.assertTrue("restoring hidden derived authority must invalidate pinned support",
			metrics.snapshot().proofGraphsBuilt() > beforeRestoration);
		Assert.assertEquals("every lifecycle wave preserves the native representative only",
			1, lazy.materializedHandleCount());
	}

	@Test
	public void directAlternativeSeamObservesColdThenResidentFallbackTopology() throws Exception {
		// The public-query regression above intentionally covers support-query key prewarming.
		// This narrow seam enters alternatives directly to observe the fallback's own
		// cold-to-resident transition without relabeling it as public root behavior.
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		// This seam deliberately measures the unchanged legacy fallback topology.
		scenario.install(true);
		CandidateRealizationReference first = scenario.installedReference("a-choice");
		CandidateRealizationReference second = scenario.installedReference("z-choice");
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);

		Assert.assertFalse("the first ordinary pin retains its exact legacy fallback",
			candidateAlternatives(resolver, scenario.childKey(), first, scenario.pool()).isEmpty());
		long builds = metrics.snapshot().topologyExpansionBuilds();
		long hits = metrics.snapshot().topologyExpansionHits();
		Assert.assertTrue("the first explicit fallback constructs its complete topology",
			builds > 0);
		Assert.assertFalse("the second ordinary pin reuses the resident fallback topology",
			candidateAlternatives(resolver, scenario.childKey(), second, scenario.pool()).isEmpty());
		Assert.assertEquals("the resident explicit topology is not rebuilt",
			builds, metrics.snapshot().topologyExpansionBuilds());
		Assert.assertTrue("the second explicit fallback records a resident topology hit",
			metrics.snapshot().topologyExpansionHits() > hits);
		Assert.assertEquals("an owner without a native relation bypasses pinned classification",
			0, directWork(metrics, "NATIVE_PINNED_REQUESTS"));
		Assert.assertEquals("the narrow non-root seam bypasses acyclic-root preprocessing",
			0, directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_REQUESTS"));
	}

	@Test
	public void invalidDerivedRowDoesNotDisableOtherwiseValidHybridAuthority() throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		Object foreignSeed = invoke(scenario.fixture(), "source", "invalid-derived-seed",
			pool("other-worker:9000", 0, 50));
		Object foreign = invoke(scenario.fixture(), "unary", "invalid-derived-owner",
			OpOp1.LOG, foreignSeed, false);
		CompiledHopKey foreignOwner = (CompiledHopKey)invoke(foreign, "key");
		scenario.installDerived(true, foreignOwner);
		NativePlacementContinuity.CandidateSupportResult eager = scenario.queryCurrent();
		NativeContinuitySupportClauses relation = scenario.installDerived(false, foreignOwner);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		NativePlacementContinuity.CandidateSupportResult metricsOn = scenario.queryCurrent(resolver);
		NativePlacementContinuity.CandidateSupportResult metricsOff = scenario.queryCurrent();

		Assert.assertEquals(signatures(eager), signatures(metricsOn));
		Assert.assertEquals(signatures(metricsOff), signatures(metricsOn));
		assertBindingSourceIdentity(eager, metricsOn);
		assertBindingSourceIdentity(metricsOff, metricsOn);
		assertIdentitySetEquals(metricsOff.dependencyOccurrences(), metricsOn.dependencyOccurrences());
		Assert.assertTrue("failed derived validation still reads its hidden anchor owner",
			metricsOn.dependencyOccurrences().stream().anyMatch(owner -> owner == foreignOwner));
		Assert.assertEquals("invalid derived metadata must not flatten valid native authority",
			1, relation.materializedHandleCount());
		assertHybridCounts(metrics, "NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE");
		Assert.assertEquals(0, directWork(metrics, "NATIVE_HYBRID_REJECT_DERIVED"));
	}

	@Test
	public void hiddenDerivedAnchorAuthorityAddsAndWithdrawsOneExactHybridRow()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object hiddenSeed = invoke(scenario.fixture(), "source", "derived-hidden-seed", scenario.pool());
		Object hidden = invoke(scenario.fixture(), "unary", "derived-hidden-owner",
			OpOp1.LOG, hiddenSeed, false);
		CompiledHopKey hiddenOwner = (CompiledHopKey)invoke(hidden, "key");
		invoke(scenario.fixture(), "withClauses", hidden, unary,
			List.of(new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, hiddenOwner,
				"derived-hidden-native-authority")), List.of(), scenario.pool(), true)));

		scenario.installDerived(true, hiddenOwner);
		List<CandidateRuleFact> eagerValidFacts = List.copyOf(candidateFacts(scenario.fixture()));
		NativePlacementContinuity.CandidateSupportResult eagerValid = scenario.queryCurrent();
		List<CandidateRuleFact> eagerInvalidFacts = eagerValidFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		NativePlacementContinuity.CandidateSupportResult eagerInvalid =
			queryWithCandidateFacts(scenario, eagerInvalidFacts);

		NativeContinuitySupportClauses lazy = scenario.installDerived(false, hiddenOwner);
		List<CandidateRuleFact> validFacts = List.copyOf(candidateFacts(scenario.fixture()));
		List<CandidateRuleFact> invalidFacts = validFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity invalid = resolverWithCandidateFacts(scenario, invalidFacts, metrics);
		NativePlacementContinuity.CandidateSupportResult invalidActual = scenario.queryCurrent(invalid);
		Assert.assertEquals(signatures(eagerInvalid), signatures(invalidActual));
		assertBindingSourceIdentity(eagerInvalid, invalidActual);
		assertIdentitySetEquals(eagerInvalid.dependencyOccurrences(),
			invalidActual.dependencyOccurrences());
		Assert.assertTrue(invalidActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));

		long beforeValid = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity valid = invalid.nextRevisionWithCompleteCandidateDelta(
			validFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult validActual = scenario.queryCurrent(valid);
		Assert.assertEquals(signatures(eagerValid), signatures(validActual));
		assertBindingSourceIdentity(eagerValid, validActual);
		assertIdentitySetEquals(eagerValid.dependencyOccurrences(), validActual.dependencyOccurrences());
		Assert.assertTrue(validActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertEquals("one validated derived action adds exactly one ordered proof row",
			invalidActual.proofs().size() + 1, validActual.proofs().size());
		Assert.assertTrue("the added proof selects the exact durable derived realization",
			validActual.proofs().stream().flatMap(proof -> proof.immediateBindings().stream())
				.anyMatch(binding -> binding.source().rule().parentOccurrence() == scenario.childKey()
					&& binding.source().realization().layoutKind() == PlacementLayoutKind.DURABLE_MAP
					&& scenario.pool().equals(binding.source().realization().durableAnchor())));
		Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > beforeValid);

		long beforeWithdraw = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity withdrawn = valid.nextRevisionWithCompleteCandidateDelta(
			invalidFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult withdrawnActual =
			scenario.queryCurrent(withdrawn);
		Assert.assertEquals(signatures(eagerInvalid), signatures(withdrawnActual));
		assertBindingSourceIdentity(eagerInvalid, withdrawnActual);
		assertIdentitySetEquals(eagerInvalid.dependencyOccurrences(),
			withdrawnActual.dependencyOccurrences());
		Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > beforeWithdraw);

		long beforeRestore = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			validFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult restoredActual =
			scenario.queryCurrent(restored);
		Assert.assertEquals(signatures(eagerValid), signatures(restoredActual));
		assertBindingSourceIdentity(eagerValid, restoredActual);
		assertIdentitySetEquals(eagerValid.dependencyOccurrences(),
			restoredActual.dependencyOccurrences());
		Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > beforeRestore);
		Assert.assertEquals("all lifecycle waves retain the lazy native representative",
			1, lazy.materializedHandleCount());
		Assert.assertTrue("the hidden certificate owner must not be a literal anchor",
			fixtureNode(scenario.fixture(), hiddenOwner).anchors().isEmpty());
		Assert.assertFalse("the derived anchor owner is metadata, never a native product axis",
			lazy.commonAxes().stream().flatMap(List::stream).anyMatch(binding ->
				binding.source().rule().parentOccurrence() == hiddenOwner));
		assertHybridCounts(metrics,
			"NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE",
			"NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE",
			"NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE",
			"NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE");

		NativeContinuitySupportClauses ordinaryFirst =
			scenario.installDerived(false, hiddenOwner, false);
		SearchSpaceMetrics orderingMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult ordinaryFirstActual = scenario.queryCurrent(
			(NativePlacementContinuity)invoke(
				scenario.fixture(), "resolver", orderingMetrics, 128, 2048L));
		Assert.assertEquals("derived validation is independent of emission encounter order",
			signatures(eagerValid), signatures(ordinaryFirstActual));
		assertBindingSourceIdentity(eagerValid, ordinaryFirstActual);
		assertIdentitySetEquals(eagerValid.dependencyOccurrences(),
			ordinaryFirstActual.dependencyOccurrences());
		Assert.assertEquals(1, ordinaryFirst.materializedHandleCount());
		assertHybridCounts(orderingMetrics,
			"NATIVE_HYBRID_ACCEPTED", "NATIVE_HYBRID_REJECT_NO_NATIVE");
	}

	@Test
	public void mixedNativeAndExplicitRowsPreserveEveryCanonicalProofLazily()
		throws Exception {
		assertMixedParity("a-choice", List.of("m-choice", "z-choice"), 0);
		assertMixedParity("m-choice", List.of("a-choice", "z-choice"), 1);
		assertMixedParity("z-choice", List.of("a-choice", "m-choice"), 2);
	}

	@Test
	public void hiddenValueMapOwnerAloneInvalidatesAndRestoresWarmHybridSupport()
		throws Exception {
		Scenario scenario = scenario("m-choice", List.of("a-choice", "z-choice"));
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object hiddenSeed = invoke(scenario.fixture(), "source", "hybrid-hidden-seed", scenario.pool());
		Object hidden = invoke(scenario.fixture(), "unary", "hybrid-hidden-owner",
			OpOp1.LOG, hiddenSeed, false);
		invoke(scenario.fixture(), "samePoolRealizations", hidden, unary,
			new DurableAnchorKey[] {scenario.pool()});
		CompiledHopKey hiddenOwner = (CompiledHopKey)invoke(hidden, "key");
		CandidateRealizationReference hiddenReference = (CandidateRealizationReference)invoke(
			scenario.fixture(), "reference", hidden, unary);
		CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(
			scenario.childEmission().emissionState(), "hybrid-hidden-value-map",
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, hiddenReference)))));

		scenario.install(true, List.of(valueMap));
		NativePlacementContinuity.CandidateSupportResult explicit = scenario.queryCurrent();
		NativeContinuitySupportClauses lazyRelation = scenario.install(false, List.of(valueMap));
		List<CandidateRuleFact> activeFacts = List.copyOf(candidateFacts(scenario.fixture()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity cached = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		NativePlacementContinuity.CandidateSupportResult warm = scenario.queryCurrent(cached);
		long builtBeforeWarmRepeat = metrics.snapshot().proofGraphsBuilt();
		Assert.assertEquals(warm.proofs(), scenario.queryCurrent(cached).proofs());
		Assert.assertEquals("an unchanged warm query must reuse its completed support",
			builtBeforeWarmRepeat, metrics.snapshot().proofGraphsBuilt());
		long builtBeforeWithdrawal = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity.CandidateSupportResult fresh = scenario.queryCurrent();
		Assert.assertEquals(signatures(explicit), signatures(fresh));
		Assert.assertEquals(signatures(fresh), signatures(warm));
		assertBindingSourceIdentity(explicit, warm);
		assertIdentitySetEquals(explicit.dependencyOccurrences(), warm.dependencyOccurrences());
		assertIdentitySetEquals(fresh.dependencyOccurrences(), warm.dependencyOccurrences());
		Assert.assertTrue("hybrid support must retain the VALUE_MAP metadata owner by identity",
			warm.dependencyOccurrences().stream().anyMatch(owner -> owner == hiddenOwner));
		Assert.assertFalse("the metadata owner is deliberately outside every native product axis",
			lazyRelation.commonAxes().stream().flatMap(List::stream)
				.anyMatch(binding -> binding.source().rule().parentOccurrence() == hiddenOwner));
		Assert.assertEquals(1, lazyRelation.materializedHandleCount());

		List<CandidateRuleFact> withdrawnFacts = activeFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() != hiddenOwner).toList();
		Assert.assertEquals(activeFacts.size() - 1, withdrawnFacts.size());
		NativePlacementContinuity withdrawn = cached.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult withdrawnActual =
			scenario.queryCurrent(withdrawn);
		NativePlacementContinuity.CandidateSupportResult withdrawnCold =
			queryWithCandidateFacts(scenario, withdrawnFacts);
		Assert.assertEquals(signatures(withdrawnCold), signatures(withdrawnActual));
		assertIdentitySetEquals(withdrawnCold.dependencyOccurrences(),
			withdrawnActual.dependencyOccurrences());
		Assert.assertTrue(withdrawnActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("withdrawing only the hidden metadata owner must invalidate warm support",
			metrics.snapshot().proofGraphsBuilt() > builtBeforeWithdrawal);

		long builtBeforeRestoration = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			activeFacts, Set.of(hiddenOwner));
		NativePlacementContinuity.CandidateSupportResult restoredActual = scenario.queryCurrent(restored);
		NativePlacementContinuity.CandidateSupportResult restoredCold = scenario.queryCurrent();
		Assert.assertEquals(signatures(explicit), signatures(restoredActual));
		Assert.assertEquals(signatures(restoredCold), signatures(restoredActual));
		assertBindingSourceIdentity(explicit, restoredActual);
		assertIdentitySetEquals(restoredCold.dependencyOccurrences(),
			restoredActual.dependencyOccurrences());
		Assert.assertTrue(restoredActual.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == hiddenOwner));
		Assert.assertTrue("restoring only the hidden metadata owner must invalidate withdrawn support",
			metrics.snapshot().proofGraphsBuilt() > builtBeforeRestoration);
	}

	private static void assertMixedParity(String nativeLineage,
		List<String> explicitLineages, int expectedNativeOrdinal) throws Exception {
		Scenario scenario = scenario(nativeLineage, explicitLineages);
		NativePlacementContinuity.CandidateSupportResult explicit = scenario.query(true);
		NativeContinuitySupportClauses lazyRelation = scenario.install(false);
		Assert.assertEquals("equal-length lineage order must place the native row at its lexical rank",
			expectedNativeOrdinal, scenario.installedNativeOrdinal());
		NativePlacementContinuity.CandidateSupportResult hybrid = scenario.queryCurrent();

		Assert.assertEquals(3, explicit.proofs().size());
		Assert.assertEquals(signatures(explicit), signatures(hybrid));
		assertBindingSourceIdentity(explicit, hybrid);
		assertIdentitySetEquals(explicit.dependencyOccurrences(), hybrid.dependencyOccurrences());
		Assert.assertEquals("the native 2x3 family contributes one representative circuit row",
			1, lazyRelation.materializedHandleCount());
	}

	private static Scenario scenario(String nativeLineage,
		List<String> explicitLineages) throws Exception {
		Object fixture = newFixture(FType.FULL);
		DurableAnchorKey pool = pool("hybrid-worker:8001", 0, 50);
		Object seed = invoke(fixture, "source", "hybrid-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object left = invoke(fixture, "unary", "hybrid-left", OpOp1.LOG, seed, false);
		Object right = invoke(fixture, "unary", "hybrid-right", OpOp1.LOG, seed, false);
		invoke(fixture, "samePoolRealizations", left, unary, new DurableAnchorKey[] {
			new DurableAnchorKey("hybrid-left-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("hybrid-left-b", FType.FULL, pool.partitions())});
		invoke(fixture, "samePoolRealizations", right, unary, new DurableAnchorKey[] {
			new DurableAnchorKey("hybrid-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("hybrid-right-b", FType.FULL, pool.partitions()),
			new DurableAnchorKey("hybrid-right-c", FType.FULL, pool.partitions())});
		Object child = invoke(fixture, "binary", "hybrid-child", OpOp2.PLUS,
			left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRuleFact childFact = (CandidateRuleFact)invoke(fixture, "fact", child, binary);
		CandidateEmissionFact childEmission = childFact.allowedEmissionFacts().get(0);
		CompiledHopKey childKey = (CompiledHopKey)invoke(child, "key");
		NativePlacementContinuity.NativeSupportProduct product =
			((NativePlacementContinuity)invoke(fixture, "resolver"))
				.proveCandidateSupport((CandidateRealizationReference)invoke(
					fixture, "reference", child, binary), pool).supportProduct();
		Assert.assertNotNull(product);

		Object outer = invoke(fixture, "unary", "hybrid-outer", OpOp1.EXP, child, false);
		CandidateRuleFact outerFact = (CandidateRuleFact)invoke(fixture, "fact", outer, unary);
		CandidateEmissionFact outerEmission = outerFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(outerFact.key(),
			CandidateEmissionRealization.nativeLineage(outerEmission.emissionState(),
				"hybrid-output", List.of(), List.of()));
		return new Scenario(fixture, childKey, childFact, childEmission, product, pool,
			nativeLineage, List.copyOf(explicitLineages), outerFact, outerEmission, proposed);
	}

	private record Scenario(Object fixture, CompiledHopKey childKey,
		CandidateRuleFact childFact, CandidateEmissionFact childEmission,
		NativePlacementContinuity.NativeSupportProduct product, DurableAnchorKey pool,
		String nativeLineage, List<String> explicitLineages, CandidateRuleFact outerFact,
		CandidateEmissionFact outerEmission, CandidateRealizationReference proposed) {
		private NativePlacementContinuity.CandidateSupportResult query(boolean explicit)
			throws Exception {
			install(explicit);
			return queryCurrent();
		}

		private NativePlacementContinuity.CandidateSupportResult queryCurrent() throws Exception {
			return queryCurrent((NativePlacementContinuity)invoke(fixture, "resolver"));
		}

		private NativePlacementContinuity.CandidateSupportResult queryCurrent(
			NativePlacementContinuity resolver) {
			return resolver.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
		}

		private NativeContinuitySupportClauses install(boolean explicit) throws Exception {
			return install(explicit, List.of());
		}

		private NativeContinuitySupportClauses install(boolean explicit,
			List<CandidateEmissionRealization> additional) throws Exception {
			NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
				childKey, product, pool, true);
			CandidateEmissionRealization nativeRealization = new CandidateEmissionRealization(
				PlacementIdentity.PlacementRealizationKey.nativeLineage(
					childEmission.emissionState(), nativeLineage),
				explicit ? List.copyOf(relation) : relation);
			List<CandidateEmissionRealization> realizations = new ArrayList<>();
			for(String lineage : explicitLineages)
				realizations.add(new CandidateEmissionRealization(
					PlacementIdentity.PlacementRealizationKey.nativeLineage(
						childEmission.emissionState(), lineage),
					List.of(new CandidateRealizationSupportClause(
						List.of(), product.bindingsAt(0)))));
			realizations.addAll(additional);
			// Encounter order intentionally differs from canonical reference order.
			realizations.add(1, nativeRealization);
			replaceChildFact(fixture, childKey, childFact, childEmission, realizations);
			return relation;
		}

		private NativeContinuitySupportClauses installDerived(boolean explicit,
			CompiledHopKey anchorOwner) throws Exception {
			return installDerived(explicit, anchorOwner, true);
		}

		private NativeContinuitySupportClauses installDerived(boolean explicit,
			CompiledHopKey anchorOwner, boolean derivedFirst) throws Exception {
			NativeContinuitySupportClauses relation = install(explicit);
			CandidateRuleFact installed = candidateFacts(fixture).stream()
				.filter(fact -> fact.key().parentOccurrence() == childKey).findFirst().orElseThrow();
			CandidateEmissionFact ordinary = installed.allowedEmissionFacts().get(0);
			PlacementState target = ordinary.emissionState().placementState();
			PlacementState source = new PlacementState(
				ExecType.FED, FederatedOutput.LOUT, target.fType(), target.shapeDependent());
			PlacementEmissionState sourceState = new PlacementEmissionState(source, false);
			PlacementEmissionState derivedState = new PlacementEmissionState(target, true);
			ValueVersionKey version = fixtureNode(fixture, childKey).valueVersion();
			DerivedFoutMaterializationActionKey action =
				new DerivedFoutMaterializationActionKey(childKey, version, installed.key(),
					source, target, pool, anchorOwner, FType.FULL, FType.FULL,
					childKey.controlRegion().normalizedSignature());
			CandidateEmissionFact sourceEmission = new CandidateEmissionFact(
				sourceState, null, null, List.of(new CandidateEmissionRealization(
					PlacementRealizationKey.local(sourceState),
					List.of(new CandidateRealizationSupportClause(List.of(), List.of())))));
			CandidateEmissionRealization derived = new CandidateEmissionRealization(
				PlacementRealizationKey.durable(derivedState, pool),
				List.of(new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
					PlacementProofKind.DURABLE_ANCHOR, childKey,
					"derived-fout:" + action.normalizedSignature())), List.of())));
			CandidateEmissionFact derivedEmission = new CandidateEmissionFact(
				derivedState, FType.FULL, action, List.of(derived));
			CandidateRuleFact replacement = new CandidateRuleFact(installed.key(), installed.status(),
				installed.capability(), installed.shapeProof(), installed.profile(),
				derivedFirst ? List.of(sourceEmission, derivedEmission, ordinary)
					: List.of(sourceEmission, ordinary, derivedEmission), installed.failureCode());
			replaceFact(candidateFacts(fixture), installed, replacement);
			return relation;
		}

		private int installedNativeOrdinal() throws Exception {
			CandidateRuleFact installed = candidateFacts(fixture).stream()
				.filter(fact -> fact.key().parentOccurrence() == childKey).findFirst().orElseThrow();
			List<CandidateEmissionRealization> realizations =
				installed.allowedEmissionFacts().get(0).realizations();
			for(int ordinal = 0; ordinal < realizations.size(); ordinal++)
				if(nativeLineage.equals(realizations.get(ordinal).key().nativeLineage()))
					return ordinal;
			throw new AssertionError("installed native realization is missing");
		}

		private NativePlacementContinuity.CandidateSupportResult queryInstalled(
			String lineage, NativePlacementContinuity resolver) throws Exception {
			return resolver.proveCandidateSupport(installedReference(lineage), pool);
		}

		private CandidateRealizationReference installedReference(String lineage) throws Exception {
			CandidateRuleFact installed = candidateFacts(fixture).stream()
				.filter(fact -> fact.key().parentOccurrence() == childKey).findFirst().orElseThrow();
			for(CandidateEmissionFact emission : installed.allowedEmissionFacts())
				for(CandidateEmissionRealization realization : emission.realizations())
					if(lineage.equals(realization.key().nativeLineage()))
						return CandidateRealizationReference.of(installed.key(), realization);
			throw new AssertionError("installed realization is missing: " + lineage);
		}
	}

	private static void assertHybridCounts(SearchSpaceMetrics metrics, String... outcomes) {
		// The public FULL-pool query tries both exact and dynamic partition witnesses.
		Assert.assertEquals(outcomes.length, directWork(metrics, "NATIVE_HYBRID_REQUESTS"));
		long classified = 0;
		for(String candidate : HYBRID_OUTCOMES) {
			long count = directWork(metrics, candidate);
			Assert.assertEquals(candidate + " counts=" + HYBRID_OUTCOMES.stream()
				.map(name -> name + "=" + directWork(metrics, name)).toList(),
				java.util.Collections.frequency(List.of(outcomes), candidate), count);
			classified += count;
		}
		Assert.assertEquals("every completed request has exactly one outcome",
			directWork(metrics, "NATIVE_HYBRID_REQUESTS"), classified);
	}

	private static void assertNativePinnedCounts(SearchSpaceMetrics metrics) {
		long requests = directWork(metrics, "NATIVE_PINNED_REQUESTS");
		long classified = 0;
		for(String outcome : PINNED_OUTCOMES)
			classified += directWork(metrics, outcome);
		Assert.assertTrue("the native relation must admit at least one pinned lazy circuit",
			directWork(metrics, "NATIVE_PINNED_ACCEPTED") > 0);
		long ordinary = directWork(metrics, "NATIVE_PINNED_REJECT_ORDINARY");
		long cold = directWork(metrics, "NATIVE_PINNED_ORDINARY_COLD_TOPOLOGY");
		long resident = directWork(metrics, "NATIVE_PINNED_ORDINARY_RESIDENT_TOPOLOGY");
		Assert.assertEquals("the separate native query must not mix ordinary fallback outcomes", 0, ordinary);
		Assert.assertEquals(0, cold);
		Assert.assertEquals(0, resident);
		Assert.assertEquals("every completed pinned request has exactly one outcome",
			requests, classified);
	}

	private static void assertAcyclicRootTopologyCounts(SearchSpaceMetrics metrics) {
		long requests = directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_REQUESTS");
		long cold = directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_COLD");
		long resident = directWork(metrics, "ACYCLIC_ROOT_TOPOLOGY_RESIDENT");
		Assert.assertEquals("support-query key construction already warmed the ordinary root topology", 0, cold);
		Assert.assertTrue("the acyclic root observes the resident topology",
			resident > 0);
		Assert.assertEquals("acyclic root topology requests are exactly partitioned",
			requests, cold + resident);
		long queryRequests = directWork(metrics, "SUPPORT_QUERY_TOPOLOGY_REQUESTS");
		long queryCold = directWork(metrics, "SUPPORT_QUERY_TOPOLOGY_COLD");
		long queryResident = directWork(metrics, "SUPPORT_QUERY_TOPOLOGY_RESIDENT");
		Assert.assertTrue("the first support-query key builds its ordinary root topology", queryCold > 0);
		Assert.assertTrue("a later support-query key observes the resident topology", queryResident > 0);
		Assert.assertEquals("support-query topology requests are exactly partitioned",
			queryRequests, queryCold + queryResident);
	}

	private static void assertResultParity(
		NativePlacementContinuity.CandidateSupportResult eager,
		NativePlacementContinuity.CandidateSupportResult metricsOff,
		NativePlacementContinuity.CandidateSupportResult metricsOn) {
		Assert.assertEquals(signatures(eager), signatures(metricsOff));
		Assert.assertEquals(signatures(metricsOff), signatures(metricsOn));
		assertBindingSourceIdentity(eager, metricsOff);
		assertBindingSourceIdentity(metricsOff, metricsOn);
		assertIdentitySetEquals(eager.dependencyOccurrences(), metricsOff.dependencyOccurrences());
		assertIdentitySetEquals(metricsOff.dependencyOccurrences(), metricsOn.dependencyOccurrences());
	}

	private static long directWork(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(SearchSpaceMetrics.DirectWork.valueOf(name));
	}

	private static boolean hasNativeContinuityRelation(NativePlacementContinuity resolver,
		CompiledHopKey owner, CandidateRealizationReference pinned) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"hasNativeContinuityRelation", CompiledHopKey.class,
			CandidateRealizationReference.class);
		method.setAccessible(true);
		return (boolean)method.invoke(resolver, owner, pinned);
	}

	private static Object nativeFactoredAlternatives(NativePlacementContinuity resolver,
		CompiledHopKey owner, CandidateRealizationReference pinned, DurableAnchorKey pool)
		throws Exception {
		Method handleMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		handleMethod.setAccessible(true);
		int handle = (int)handleMethod.invoke(resolver, pinned);
		Method witnessMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		witnessMethod.setAccessible(true);
		Object witness = witnessMethod.invoke(resolver, pool);
		Class<?> fixedType = nested("FixedCandidateBoundary");
		Constructor<?> fixedConstructor = fixedType.getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class);
		fixedConstructor.setAccessible(true);
		Object fixed = fixedConstructor.newInstance(owner, pinned, handle);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeFactoredProofAlternatives", CompiledHopKey.class,
			CandidateRealizationReference.class, nested("NativePoolWitness"), fixedType);
		method.setAccessible(true);
		return method.invoke(resolver, owner, pinned, witness, fixed);
	}

	@SuppressWarnings("unchecked")
	private static Object residentTopology(NativePlacementContinuity resolver,
		CompiledHopKey owner, DurableAnchorKey pool) throws Exception {
		Method witnessMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		witnessMethod.setAccessible(true);
		Object targetWitness = witnessMethod.invoke(resolver, pool);
		Field field = NativePlacementContinuity.class.getDeclaredField("candidateTopologies");
		field.setAccessible(true);
		Map<Object,Object> topologies = (Map<Object,Object>)field.get(resolver);
		for(var entry : topologies.entrySet()) {
			Field occurrence = entry.getKey().getClass().getDeclaredField("occurrence");
			occurrence.setAccessible(true);
			Field witness = entry.getKey().getClass().getDeclaredField("witness");
			witness.setAccessible(true);
			if(occurrence.get(entry.getKey()) == owner
				&& targetWitness.equals(witness.get(entry.getKey())))
				return entry.getValue();
		}
		return null;
	}

	private static boolean ordinaryOnly(Object topology) throws Exception {
		Field field = topology.getClass().getDeclaredField("ordinaryOnly");
		field.setAccessible(true);
		return field.getBoolean(topology);
	}

	@SuppressWarnings("unchecked")
	private static boolean topologyContainsHandle(Object topology, int handle) throws Exception {
		Field field = topology.getClass().getDeclaredField("rowsByHandle");
		field.setAccessible(true);
		return ((Map<Integer,?>)field.get(topology)).containsKey(handle);
	}

	private static int candidateHandle(NativePlacementContinuity resolver,
		CandidateRealizationReference reference) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		method.setAccessible(true);
		return (int)method.invoke(resolver, reference);
	}

	private static Object genericCandidateTopology(NativePlacementContinuity resolver,
		CompiledHopKey owner, DurableAnchorKey pool) throws Exception {
		Method witnessMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		witnessMethod.setAccessible(true);
		Object witness = witnessMethod.invoke(resolver, pool);
		Method topology = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateTopology", CompiledHopKey.class, nested("NativePoolWitness"));
		topology.setAccessible(true);
		return topology.invoke(resolver, owner, witness);
	}

	private static void restoreProperty(String key, String value) {
		if(value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

	private static CandidateRuleFact installedChildFact(Scenario scenario) throws Exception {
		return candidateFacts(scenario.fixture()).stream()
			.filter(fact -> fact.key().parentOccurrence() == scenario.childKey())
			.findFirst().orElseThrow();
	}

	private static CandidateRuleFact ruleVariant(CandidateRuleFact template,
		List<CandidateInputState> inputs) {
		return new CandidateRuleFact(new CandidateRuleKey(
			template.key().parentOccurrence(), inputs), template.status(), template.capability(),
			template.shapeProof(), template.profile(), template.allowedEmissionFacts(),
			template.failureCode());
	}

	private static CandidateRuleFact singleRealizationFact(CandidateRuleFact template,
		CandidateEmissionFact emission, CandidateEmissionRealization realization) {
		CandidateEmissionFact single = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(realization));
		return new CandidateRuleFact(template.key(), template.status(), template.capability(),
			template.shapeProof(), template.profile(), List.of(single), template.failureCode());
	}

	@SuppressWarnings("unchecked")
	private static void replaceOwnerFacts(NativePlacementContinuity resolver,
		CompiledHopKey owner, List<CandidateRuleFact> facts) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("candidateFactsByKey");
		field.setAccessible(true);
		Map<CompiledHopKey,List<CandidateRuleFact>> current =
			(Map<CompiledHopKey,List<CandidateRuleFact>>)field.get(resolver);
		Map<CompiledHopKey,List<CandidateRuleFact>> replacement = new IdentityHashMap<>(current);
		replacement.put(owner, facts);
		field.set(resolver, java.util.Collections.unmodifiableMap(replacement));
	}

	private static NativePlacementContinuity.CandidateSupportResult queryInstalledWithOwnerFacts(
		Scenario scenario, String lineage, List<CandidateRuleFact> ownerFacts,
		SearchSpaceMetrics metrics) throws Exception {
		NativePlacementContinuity resolver = (NativePlacementContinuity)invoke(
			scenario.fixture(), "resolver", metrics, 128, 2048L);
		replaceOwnerFacts(resolver, scenario.childKey(), ownerFacts);
		return scenario.queryInstalled(lineage, resolver);
	}

	@SuppressWarnings("unchecked")
	private static List<?> candidateAlternatives(NativePlacementContinuity resolver,
		CompiledHopKey owner, CandidateRealizationReference pinned, DurableAnchorKey pool)
		throws Exception {
		Method handleMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		handleMethod.setAccessible(true);
		int handle = (int)handleMethod.invoke(resolver, pinned);
		Method witnessMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		witnessMethod.setAccessible(true);
		Object witness = witnessMethod.invoke(resolver, pool);
		Class<?> fixedType = nested("FixedCandidateBoundary");
		Constructor<?> fixedConstructor = fixedType.getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class);
		fixedConstructor.setAccessible(true);
		Object fixed = fixedConstructor.newInstance(owner, pinned, handle);
		Method alternatives = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateProofAlternatives", CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, nested("NativePoolWitness"),
			boolean.class, fixedType, nested("GenerationRoot"));
		alternatives.setAccessible(true);
		return (List<?>)alternatives.invoke(
			resolver, owner, pinned, handle, witness, true, fixed, null);
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static void replaceFact(List<CandidateRuleFact> facts,
		CandidateRuleFact prior, CandidateRuleFact replacement) {
		int index = facts.indexOf(prior);
		Assert.assertTrue(index >= 0);
		facts.set(index, replacement);
	}

	@SuppressWarnings("unchecked")
	private static void replaceChildFact(Object fixture, CompiledHopKey childKey,
		CandidateRuleFact template, CandidateEmissionFact base,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Field candidatesField = fixture.getClass().getDeclaredField("candidates");
		candidatesField.setAccessible(true);
		List<CandidateRuleFact> candidates = (List<CandidateRuleFact>)candidatesField.get(fixture);
		int position = -1;
		for(int index = 0; index < candidates.size(); index++)
			if(candidates.get(index).key().parentOccurrence() == childKey) {
				position = index;
				break;
			}
		Assert.assertTrue(position >= 0);
		candidates.removeIf(fact -> fact.key().parentOccurrence() == childKey);
		CandidateEmissionFact emission = new CandidateEmissionFact(base.emissionState(),
			base.executionFType(), base.derivedFoutAction(), List.copyOf(realizations));
		candidates.add(position, new CandidateRuleFact(template.key(), template.status(),
			template.capability(), template.shapeProof(), template.profile(),
			List.of(emission), template.failureCode()));
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> candidateFacts(Object fixture) throws Exception {
		Field candidatesField = fixture.getClass().getDeclaredField("candidates");
		candidatesField.setAccessible(true);
		return (List<CandidateRuleFact>)candidatesField.get(fixture);
	}

	@SuppressWarnings("unchecked")
	private static Node fixtureNode(Object fixture, CompiledHopKey key) throws Exception {
		Field field = fixture.getClass().getDeclaredField("nodes");
		field.setAccessible(true);
		return ((Map<CompiledHopKey,Node>)field.get(fixture)).get(key);
	}

	private static NativePlacementContinuity resolverWithCandidateFacts(Scenario scenario,
		List<CandidateRuleFact> facts, SearchSpaceMetrics metrics) throws Exception {
		List<CandidateRuleFact> live = candidateFacts(scenario.fixture());
		List<CandidateRuleFact> prior = List.copyOf(live);
		try {
			live.clear();
			live.addAll(facts);
			return (NativePlacementContinuity)invoke(
				scenario.fixture(), "resolver", metrics, 128, 2048L);
		}
		finally {
			live.clear();
			live.addAll(prior);
		}
	}

	private static NativePlacementContinuity.CandidateSupportResult queryWithCandidateFacts(
		Scenario scenario, List<CandidateRuleFact> facts) throws Exception {
		List<CandidateRuleFact> live = candidateFacts(scenario.fixture());
		List<CandidateRuleFact> prior = List.copyOf(live);
		try {
			live.clear();
			live.addAll(facts);
			return scenario.queryCurrent();
		}
		finally {
			live.clear();
			live.addAll(prior);
		}
	}

	private static NativePlacementContinuity.CandidateSupportResult queryInstalledWithCandidateFacts(
		Scenario scenario, String lineage, List<CandidateRuleFact> facts) throws Exception {
		NativePlacementContinuity resolver = resolverWithCandidateFacts(
			scenario, facts, new SearchSpaceMetrics());
		return scenario.queryInstalled(lineage, resolver);
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
		return new DurableAnchorKey("hybrid-pool", FType.FULL,
			List.of(new AnchorPartition(worker, List.of(begin, 0L), List.of(end, 2L))));
	}

	private static List<String> signatures(
		NativePlacementContinuity.CandidateSupportResult result) {
		return result.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList();
	}

	private static void assertBindingSourceIdentity(
		NativePlacementContinuity.CandidateSupportResult expected,
		NativePlacementContinuity.CandidateSupportResult actual) {
		Assert.assertEquals(expected.proofs().size(), actual.proofs().size());
		for(int proof = 0; proof < expected.proofs().size(); proof++) {
			List<CandidateRealizationInputBinding> left =
				expected.proofs().get(proof).immediateBindings();
			List<CandidateRealizationInputBinding> right =
				actual.proofs().get(proof).immediateBindings();
			Assert.assertEquals(left.size(), right.size());
			for(int binding = 0; binding < left.size(); binding++) {
				CandidateRealizationReference leftSource = left.get(binding).source();
				CandidateRealizationReference rightSource = right.get(binding).source();
				Assert.assertEquals(leftSource, rightSource);
				Assert.assertSame(leftSource.rule().parentOccurrence(),
					rightSource.rule().parentOccurrence());
			}
		}
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey key : expected)
			Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == key));
	}

	private static final class CountingFactList extends AbstractList<CandidateRuleFact> {
		private final List<CandidateRuleFact> facts;
		private int gets;

		private CountingFactList(List<CandidateRuleFact> facts) {
			this.facts = List.copyOf(facts);
		}

		@Override
		public CandidateRuleFact get(int index) {
			gets++;
			return facts.get(index);
		}

		@Override
		public int size() {
			return facts.size();
		}

		private int gets() {
			return gets;
		}
	}
}
