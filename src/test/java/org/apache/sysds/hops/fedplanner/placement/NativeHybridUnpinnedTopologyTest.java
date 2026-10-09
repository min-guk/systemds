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
import java.util.ArrayList;
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

	private static long directWork(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(SearchSpaceMetrics.DirectWork.valueOf(name));
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
}
