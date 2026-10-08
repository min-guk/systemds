/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact grounding rules for a declared CP/LOUT-to-CP/FOUT materialized root. */
public class MaterializedContinuityTest {
	@Test
	public void declaredMaterializationIsAContinuityGroundOnlyWithItsExactAction() {
		Fixture valid = fixture(true, true, false);
		Assert.assertNotNull(valid.continuity().proveCandidate(valid.output(), valid.outputPool()));

		Fixture missingAction = fixture(false, true, false);
		Assert.assertNull("a durable CP/FOUT realization alone is not upload authority",
			missingAction.continuity().proveCandidate(missingAction.output(), missingAction.outputPool()));
	}

	@Test
	public void materializationRejectsAnAnchorOwnerFromAnotherPhysicalPool() {
		Fixture wrongPool = fixture(true, true, true);
		Assert.assertNull("the action owner must prove the same concrete worker pool",
			wrongPool.continuity().proveCandidate(wrongPool.output(), wrongPool.outputPool()));
	}

	@Test
	public void materializationRejectsAnActionWhoseLocalSourceEmissionWasRemoved() {
		Fixture missingSource = fixture(true, false, false);
		Assert.assertNull("an upload action cannot survive removal of its CP/LOUT source row",
			missingSource.continuity().proveCandidate(
				missingSource.output(), missingSource.outputPool()));
	}

	@Test
	public void declaredMaterializationCannotSelfGroundAGeneratedTemplate() {
		Fixture valid = fixture(true, true, false);
		Assert.assertTrue("generated native templates require ordinary input continuity",
			valid.continuity().proveGeneratedCandidateAlternatives(valid.producerFact(),
				valid.uploadEmission(), valid.output(), valid.outputPool()).isEmpty());
	}

	@Test
	public void formalOwnerUsesItsExactNativePoolWithoutALiteralNodeAnchor() {
		Fixture valid = nativeBoundaryFixture(NativeOwnerMode.EXACT_ACTUAL_TO_FORMAL);
		Assert.assertNotNull("normalized function formals retain their analysis-owned native pool",
			valid.continuity().proveCandidate(valid.output(), valid.outputPool()));
	}

	@Test
	public void formalOwnerRejectsWrongOrNonFederatedNativeAuthority() {
		for(NativeOwnerMode mode : List.of(NativeOwnerMode.WRONG_POOL, NativeOwnerMode.LOCAL_ONLY)) {
			Fixture invalid = nativeBoundaryFixture(mode);
			Assert.assertNull(mode.name(), invalid.continuity().proveCandidate(
				invalid.output(), invalid.outputPool()));
		}
	}

	@Test
	public void certificateFreeSelfAndMutualCyclesCannotAuthorizeAnUpload() {
		for(NativeOwnerMode mode : List.of(NativeOwnerMode.SELF_CYCLE, NativeOwnerMode.MUTUAL_CYCLE)) {
			Fixture invalid = nativeBoundaryFixture(mode);
			Assert.assertNull(mode.name(), invalid.continuity().proveCandidate(
				invalid.output(), invalid.outputPool()));
		}
	}

	@Test
	public void ownerCertificateWithdrawalInvalidatesDeclaredUploadRevisionCaches() {
		Fixture valid = nativeBoundaryFixture(NativeOwnerMode.EXACT_ACTUAL_TO_FORMAL);
		Assert.assertNotNull(valid.continuity().proveCandidate(valid.output(), valid.outputPool()));
		Assert.assertNull("conservative revision must not retain the former owner certificate",
			valid.continuity().nextRevision(valid.withdrawnFacts())
				.proveCandidate(valid.output(), valid.outputPool()));
		Assert.assertNull("complete-delta revision must read the declared action owner",
			valid.continuity().nextRevisionWithCompleteCandidateDelta(
				valid.withdrawnFacts(), Set.of(valid.authorityOwner()))
				.proveCandidate(valid.output(), valid.outputPool()));
		Assert.assertNull("fresh authority without the owner certificate is the reference result",
			valid.freshWithdrawn().proveCandidate(valid.output(), valid.outputPool()));
	}

	@Test
	public void derivedActionMetadataFootprintIncludesExactAnchorOwnerColdAndWarm() {
		for(Fixture fixture : List.of(fixture(true, true, false),
			nativeBoundaryFixture(NativeOwnerMode.EXACT_ACTUAL_TO_FORMAL))) {
			CompiledHopKey producer = fixture.output().rule().parentOccurrence();
			for(int invocation = 0; invocation < 2; invocation++) {
				Set<CompiledHopKey> footprint = identitySet(producer);
				Assert.assertTrue("certified anchor authority has a complete exact read inventory",
					fixture.continuity().expandValueMapMetadataDependencies(footprint));
				Assert.assertTrue("derived materialization must subscribe to its exact anchor owner",
					containsIdentity(footprint, fixture.authorityOwner()));
				Assert.assertNotNull("second expansion must cover a warm positive proof cache",
					fixture.continuity().proveCandidate(fixture.output(), fixture.outputPool()));
			}
		}
	}

	@Test
	public void derivedActionFootprintStaysCompleteAcrossNegativeAuthorityAndWithdrawal() {
		for(NativeOwnerMode mode : List.of(NativeOwnerMode.WRONG_POOL, NativeOwnerMode.LOCAL_ONLY)) {
			Fixture fixture = nativeBoundaryFixture(mode);
			Set<CompiledHopKey> footprint = identitySet(
				fixture.output().rule().parentOccurrence());
			Assert.assertTrue(mode.name(),
				fixture.continuity().expandValueMapMetadataDependencies(footprint));
			Assert.assertTrue(mode.name(), containsIdentity(footprint, fixture.authorityOwner()));
			Assert.assertNull("footprint completeness must not grant materialization legality",
				fixture.continuity().proveCandidate(fixture.output(), fixture.outputPool()));
		}

		Fixture valid = nativeBoundaryFixture(NativeOwnerMode.EXACT_ACTUAL_TO_FORMAL);
		Assert.assertNotNull("withdrawal revision must start from a warm certified proof",
			valid.continuity().proveCandidate(valid.output(), valid.outputPool()));
		NativePlacementContinuity withdrawn = valid.continuity().nextRevision(valid.withdrawnFacts());
		Set<CompiledHopKey> revisedFootprint = identitySet(
			valid.output().rule().parentOccurrence());
		Assert.assertTrue(withdrawn.expandValueMapMetadataDependencies(revisedFootprint));
		Assert.assertTrue(containsIdentity(revisedFootprint, valid.authorityOwner()));
		Assert.assertNull(withdrawn.proveCandidate(valid.output(), valid.outputPool()));
		Set<CompiledHopKey> freshFootprint = identitySet(
			valid.output().rule().parentOccurrence());
		Assert.assertTrue(valid.freshWithdrawn()
			.expandValueMapMetadataDependencies(freshFootprint));
		Assert.assertTrue(containsIdentity(freshFootprint, valid.authorityOwner()));
		assertIdentitySetEquals(freshFootprint, revisedFootprint);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void anchorOnlyChangeInvalidatesItsSubscribedMaterializationConsumer() throws Exception {
		Fixture fixture = fixture(true, true, false);
		CompiledHopKey consumer = fixture.output().rule().parentOccurrence();
		Set<CompiledHopKey> footprint = identitySet(consumer);
		Assert.assertTrue(fixture.continuity().expandValueMapMetadataDependencies(footprint));

		Class<?> subscriptionsType = Class.forName(
			PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = subscriptionsType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object subscriptions = constructor.newInstance();
		Method replace = subscriptionsType.getDeclaredMethod(
			"replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		replace.invoke(subscriptions, identitySet(consumer), Map.of(consumer, footprint), Set.of());
		Method required = PlacementRelationClosure.class.getDeclaredMethod(
			"requiredDirectClosureOccurrences", Set.class, Map.class, Map.class, Map.class,
			Map.class, subscriptionsType);
		required.setAccessible(true);
		Set<CompiledHopKey> invalidated = (Set<CompiledHopKey>)required.invoke(null,
			identitySet(fixture.authorityOwner()), Map.of(), Map.of(), Map.of(), Map.of(),
			subscriptions);
		Assert.assertTrue("an anchor-only revision must requeue the subscribed upload consumer",
			containsIdentity(invalidated, consumer));
	}

	private enum NativeOwnerMode {
		EXACT_ACTUAL_TO_FORMAL,
		WRONG_POOL,
		LOCAL_ONLY,
		SELF_CYCLE,
		MUTUAL_CYCLE
	}

	private static Fixture nativeBoundaryFixture(NativeOwnerMode mode) {
		ControlRegionKey region = new ControlRegionKey(
			"materialized-native-boundary", "main", List.of("call/0"), "main", "compiled");
		CompiledHopKey producer = boundaryKey(region, "producer");
		CompiledHopKey formal = boundaryKey(region, "formal");
		CompiledHopKey actual = boundaryKey(region, "actual");
		CompiledHopKey peer = boundaryKey(region, "peer");
		ValueVersionKey producerVersion = boundaryVersion(region, "producer", 0);
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState upload = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		PlacementState resident = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		DurableAnchorKey expected = pool("boundary-output", "worker-a:1234", "worker-b:1234");
		DurableAnchorKey selectedPool = mode == NativeOwnerMode.WRONG_POOL
			? pool("wrong-boundary", "worker-c:1234", "worker-d:1234") : expected;

		CandidateRuleKey producerRule = new CandidateRuleKey(producer, List.of());
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			producer, producerVersion, producerRule, local, upload, expected, formal,
			FType.ROW, FType.ROW, region.normalizedSignature());
		PlacementEmissionState localState = new PlacementEmissionState(local, false);
		PlacementEmissionState uploadState = new PlacementEmissionState(upload, false);
		CandidateEmissionFact localEmission = new CandidateEmissionFact(
			localState, null, null, List.of(CandidateEmissionRealization.local(localState)));
		CandidateEmissionFact uploadEmission = new CandidateEmissionFact(uploadState, null, action,
			List.of(CandidateEmissionRealization.durable(uploadState, expected,
				List.of(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, producer,
					"derived-fout:" + action.normalizedSignature())), List.of())));
		CandidateRuleFact producerFact = fact(producerRule, ExecType.CP, FederatedOutput.FOUT,
			FType.ROW, List.of(), List.of(localEmission, uploadEmission));

		PlacementEmissionState residentState = new PlacementEmissionState(resident, false);
		CandidateRuleKey actualRule = new CandidateRuleKey(actual, List.of());
		CandidateEmissionRealization actualRealization = CandidateEmissionRealization.durable(
			residentState, expected, List.of(), List.of());
		CandidateEmissionFact actualEmission = new CandidateEmissionFact(
			residentState, FType.ROW, null, List.of(actualRealization));
		CandidateRuleFact actualFact = fact(actualRule, ExecType.FED, FederatedOutput.FOUT,
			FType.ROW, List.of(FType.ROW), List.of(actualEmission));

		CandidateRuleKey formalRule = new CandidateRuleKey(formal,
			List.of(CandidateInputState.present(FType.ROW)));
		PlacementEmissionState formalState = mode == NativeOwnerMode.LOCAL_ONLY
			? localState : residentState;
		PlacementRealizationKey formalLayout = mode == NativeOwnerMode.LOCAL_ONLY
			? PlacementRealizationKey.local(formalState)
			: PlacementRealizationKey.nativeLineage(formalState, "actual-to-formal");
		CandidateRuleKey peerRule = new CandidateRuleKey(peer,
			List.of(CandidateInputState.present(FType.ROW)));
		PlacementRealizationKey peerLayout = PlacementRealizationKey.nativeLineage(
			residentState, "mutual-peer");
		CandidateRealizationReference formalReference =
			new CandidateRealizationReference(formalRule, formalLayout);
		CandidateRealizationReference support = switch(mode) {
			case SELF_CYCLE -> formalReference;
			case MUTUAL_CYCLE -> new CandidateRealizationReference(peerRule, peerLayout);
			default -> CandidateRealizationReference.of(actualRule, actualRealization);
		};
		List<PlacementProofKey> nativeProof = mode == NativeOwnerMode.EXACT_ACTUAL_TO_FORMAL
			|| mode == NativeOwnerMode.WRONG_POOL ? List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, formal, "actual-to-formal")) : List.of();
		CandidateRealizationSupportClause formalClause = nativeProof.isEmpty()
			? new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, support)))
			: new CandidateRealizationSupportClause(nativeProof, List.of(
				CandidateRealizationInputBinding.direct(0, support)), selectedPool);
		CandidateEmissionRealization formalRealization = mode == NativeOwnerMode.LOCAL_ONLY
			? CandidateEmissionRealization.local(formalState)
			: new CandidateEmissionRealization(formalLayout, List.of(formalClause));
		CandidateEmissionFact formalEmission = mode == NativeOwnerMode.LOCAL_ONLY
			? new CandidateEmissionFact(formalState, null, null, List.of(formalRealization))
			: new CandidateEmissionFact(formalState, FType.ROW, null, List.of(formalRealization));
		CandidateRuleFact formalFact = fact(formalRule,
			mode == NativeOwnerMode.LOCAL_ONLY ? ExecType.CP : ExecType.FED,
			mode == NativeOwnerMode.LOCAL_ONLY ? FederatedOutput.LOUT : FederatedOutput.FOUT,
			mode == NativeOwnerMode.LOCAL_ONLY ? null : FType.ROW,
			List.of(FType.ROW), List.of(formalEmission));

		List<CandidateRuleFact> facts = new java.util.ArrayList<>(
			List.of(producerFact, actualFact, formalFact));
		if(mode == NativeOwnerMode.MUTUAL_CYCLE) {
			CandidateRealizationSupportClause peerClause = new CandidateRealizationSupportClause(
				List.of(), List.of(CandidateRealizationInputBinding.direct(0, formalReference)));
			CandidateEmissionRealization peerRealization = new CandidateEmissionRealization(
				peerLayout, List.of(peerClause));
			facts.add(fact(peerRule, ExecType.FED, FederatedOutput.FOUT, FType.ROW,
				List.of(FType.ROW), List.of(new CandidateEmissionFact(
					residentState, FType.ROW, null, List.of(peerRealization)))));
		}

		DataOp producerHop = data("producer", OpOpData.TRANSIENTREAD);
		DataOp formalHop = data("formal", OpOpData.TRANSIENTREAD);
		DataOp actualHop = data("actual", OpOpData.FEDERATED);
		DataOp peerHop = data("peer", OpOpData.TRANSIENTREAD);
		Node producerNode = new Node(producer, NodeKind.OPERATION, producerVersion, true,
			List.of(local, upload), List.of(), List.of());
		Node formalNode = new Node(formal, NodeKind.FUNCTION_INPUT,
			boundaryVersion(region, "formal", 1), true, List.of(resident), List.of(), List.of());
		Node actualNode = new Node(actual, NodeKind.OPERATION,
			boundaryVersion(region, "actual", 2), true, List.of(resident), List.of(), List.of(expected));
		Node peerNode = new Node(peer, NodeKind.BRANCH_JOIN,
			boundaryVersion(region, "peer", 3), true, List.of(resident), List.of(), List.of());
		Map<CompiledHopKey,Node> nodes = new java.util.IdentityHashMap<>();
		nodes.put(producer, producerNode);
		nodes.put(formal, formalNode);
		nodes.put(actual, actualNode);
		if(mode == NativeOwnerMode.MUTUAL_CYCLE)
			nodes.put(peer, peerNode);
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> hops = new java.util.IdentityHashMap<>();
		hops.put(producer, producerHop);
		hops.put(formal, formalHop);
		hops.put(actual, actualHop);
		if(mode == NativeOwnerMode.MUTUAL_CYCLE)
			hops.put(peer, peerHop);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodes, hops, facts, List.of(), Map.of());
		CandidateRealizationSupportClause withdrawnClause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(actualRule, actualRealization))));
		CandidateEmissionRealization withdrawnRealization = new CandidateEmissionRealization(
			PlacementRealizationKey.nativeLineage(residentState, "actual-to-formal"),
			List.of(withdrawnClause));
		CandidateRuleFact withdrawnFormal = fact(formalRule, ExecType.FED, FederatedOutput.FOUT,
			FType.ROW, List.of(FType.ROW), List.of(new CandidateEmissionFact(
				residentState, FType.ROW, null, List.of(withdrawnRealization))));
		List<CandidateRuleFact> withdrawnFacts = facts.stream()
			.map(candidate -> candidate.key().parentOccurrence() == formal ? withdrawnFormal : candidate)
			.toList();
		NativePlacementContinuity freshWithdrawn = new NativePlacementContinuity(
			nodes, hops, withdrawnFacts, List.of(), Map.of());
		return new Fixture(continuity, producerFact, uploadEmission,
			CandidateRealizationReference.of(producerRule, uploadEmission.realizations().get(0)), expected,
			withdrawnFacts, freshWithdrawn, formal);
	}

	private static Fixture fixture(boolean includeAction, boolean includeLocalSource,
		boolean wrongOwnerPool) {
		ControlRegionKey region = new ControlRegionKey(
			"materialized-continuity", "main", List.of("main/0"), "main", "compiled");
		CompiledHopKey producer = key(region, "producer");
		CompiledHopKey anchorOwner = key(region, "anchor");
		ValueVersionKey producerVersion = version(region, "producer", 0);
		ValueVersionKey anchorVersion = version(region, "anchor", 1);
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState upload = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		PlacementState resident = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		DurableAnchorKey outputPool = pool("output", "worker-a:1234", "worker-b:1234");
		DurableAnchorKey ownerPool = wrongOwnerPool
			? pool("foreign", "worker-c:1234", "worker-d:1234") : outputPool;

		CandidateRuleKey producerRule = new CandidateRuleKey(producer, List.of());
		DerivedFoutMaterializationActionKey action = includeAction
			? new DerivedFoutMaterializationActionKey(producer, producerVersion, producerRule,
				local, upload, outputPool, anchorOwner, FType.ROW, FType.ROW,
				region.normalizedSignature()) : null;
		PlacementEmissionState localEmissionState = new PlacementEmissionState(local, false);
		PlacementEmissionState uploadEmissionState = new PlacementEmissionState(upload, false);
		CandidateEmissionFact localEmission = new CandidateEmissionFact(
			localEmissionState, null, null, List.of(CandidateEmissionRealization.local(localEmissionState)));
		List<PlacementProofKey> uploadProofs = action == null ? List.of() : List.of(
			new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR, producer,
				"derived-fout:" + action.normalizedSignature()));
		CandidateEmissionFact uploadEmission = new CandidateEmissionFact(uploadEmissionState, null, action,
			List.of(CandidateEmissionRealization.durable(
				uploadEmissionState, outputPool, uploadProofs, List.of())));
		List<CandidateEmissionFact> producerEmissions = includeLocalSource
			? List.of(localEmission, uploadEmission) : List.of(uploadEmission);
		CandidateRuleFact producerFact = fact(producerRule, ExecType.CP,
			FederatedOutput.FOUT, FType.ROW, List.of(), producerEmissions);

		CandidateRuleKey anchorRule = new CandidateRuleKey(anchorOwner, List.of());
		PlacementEmissionState residentEmissionState = new PlacementEmissionState(resident, false);
		CandidateEmissionFact residentEmission = new CandidateEmissionFact(
			residentEmissionState, FType.ROW, null, List.of(CandidateEmissionRealization.durable(
				residentEmissionState, ownerPool, List.of(), List.of())));
		CandidateRuleFact anchorFact = fact(anchorRule, ExecType.FED,
			FederatedOutput.FOUT, FType.ROW, List.of(FType.ROW), List.of(residentEmission));

		DataOp producerHop = new DataOp("producer", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "producer", 8, 2, 16, 1000);
		DataOp anchorHop = new DataOp("anchor", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, "anchor", 8, 2, 16, 1000);
		Node producerNode = new Node(producer, NodeKind.OPERATION, producerVersion, true,
			List.of(local, upload), List.of(), List.of());
		Node anchorNode = new Node(anchorOwner, NodeKind.OPERATION, anchorVersion, true,
			List.of(resident), List.of(), List.of(ownerPool));
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(producer, producerNode, anchorOwner, anchorNode),
			Map.of(producer, producerHop, anchorOwner, anchorHop),
			List.of(producerFact, anchorFact), List.of(), Map.of());
		CandidateRealizationReference output = CandidateRealizationReference.of(
			producerRule, uploadEmission.realizations().get(0));
		return new Fixture(continuity, producerFact, uploadEmission, output, outputPool,
			List.of(), null, anchorOwner);
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule, ExecType exec,
		FederatedOutput output, FType outputType, List<FType> inputs,
		List<CandidateEmissionFact> emissions) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", exec, output,
				outputType, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(inputs, ""), emissions, "");
	}

	private static CompiledHopKey key(ControlRegionKey region, String name) {
		return new CompiledHopKey("materialized-continuity", "main", "main", "compiled",
			region, name, name);
	}

	private static CompiledHopKey boundaryKey(ControlRegionKey region, String name) {
		return new CompiledHopKey("materialized-native-boundary", "main", "main", "compiled",
			region, name, name);
	}

	private static ValueVersionKey version(ControlRegionKey region, String name, int ordinal) {
		return new ValueVersionKey("materialized-continuity", name, region, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static ValueVersionKey boundaryVersion(ControlRegionKey region, String name, int ordinal) {
		return new ValueVersionKey("materialized-native-boundary", name, region, ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static DataOp data(String name, OpOpData operation) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			operation, name, 8, 2, 16, 1000);
	}

	private static DurableAnchorKey pool(String id, String first, String second) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(first, List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition(second, List.of(4L, 0L), List.of(8L, 2L))));
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey... owners) {
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, owners);
		return result;
	}

	private static boolean containsIdentity(Set<CompiledHopKey> owners, CompiledHopKey target) {
		return owners.stream().anyMatch(owner -> owner == target);
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey owner : expected)
			Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == owner));
	}

	private record Fixture(NativePlacementContinuity continuity,
		CandidateRuleFact producerFact, CandidateEmissionFact uploadEmission,
		CandidateRealizationReference output, DurableAnchorKey outputPool,
		List<CandidateRuleFact> withdrawnFacts, NativePlacementContinuity freshWithdrawn,
		CompiledHopKey authorityOwner) { }
}
