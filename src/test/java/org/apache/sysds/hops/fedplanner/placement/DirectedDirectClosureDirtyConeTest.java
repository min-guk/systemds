/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DirectedDirectClosureDirtyConeTest {
	private static final String FINGERPRINT = "direct-dirty-cone";
	private static final ControlRegionKey REGION = new ControlRegionKey(FINGERPRINT, "main",
		List.of("main"), "main", "compiled");
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);

	@Test
	@SuppressWarnings("unchecked")
	public void unchangedAliasPredecessorDoesNotStarveReadySuccessor() throws Exception {
		Node a = node("alias-predecessor"), b = node("alias-successor");
		PlacementDependencyComponents schedule = new PlacementDependencyComponents(
			List.of(a.key(), b.key()), List.of(new PlacementDependencyComponents.SemanticDependency(a.key(), b.key())),
			List.of(new PlacementDependencyComponents.InvalidationAdjacency(a.key(), b.key())));
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"readyDirectOwners", PlacementDependencyComponents.class, Set.class);
		method.setAccessible(true);
		Assert.assertEquals(keys(a), method.invoke(null, schedule, keys(a, b)));
		Assert.assertEquals("settled alias must not be reintroduced without an export change",
			keys(b), method.invoke(null, schedule, keys(b)));
	}

	@Test
	public void directBindingEligibilityMatchesInvariantBinderSkips() throws Exception {
		Node owner = node("eligible-owner");
		CandidateRuleFact eligible = directFact(owner, CandidateEvaluationStatus.AVAILABLE, true);
		CandidateRuleFact unavailable = directFact(owner, CandidateEvaluationStatus.PRIVACY_EXCLUDED, true);
		CandidateRuleFact noPresentInput = directFact(owner, CandidateEvaluationStatus.AVAILABLE, false);
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> operation = new IdentityHashMap<>();
		operation.put(owner.key(), new LiteralOp(1L));

		Assert.assertTrue(directBindingEligible(eligible, operation));
		Assert.assertFalse(directBindingEligible(unavailable, operation));
		Assert.assertFalse(directBindingEligible(noPresentInput, operation));

		Map<CompiledHopKey,org.apache.sysds.hops.Hop> transientRead = new IdentityHashMap<>();
		transientRead.put(owner.key(), new DataOp("eligible-owner", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "eligible-owner", 8, 3, 24, 1000));
		Assert.assertFalse(directBindingEligible(eligible, transientRead));
	}

	@Test
	public void noEligibleBindingClassificationRequiresEveryOwnerSlotToBeIneligible() throws Exception {
		Node allIneligible = node("all-ineligible"), mixed = node("mixed-slots");
		Node transientRead = node("transient-read"), noRows = node("no-rows");
		List<CandidateRuleFact> facts = List.of(
			directFact(allIneligible, CandidateEvaluationStatus.PRIVACY_EXCLUDED, true),
			directFact(allIneligible, CandidateEvaluationStatus.AVAILABLE, false),
			directFact(mixed, CandidateEvaluationStatus.PRIVACY_EXCLUDED, true),
			directFact(mixed, CandidateEvaluationStatus.AVAILABLE, true),
			directFact(transientRead, CandidateEvaluationStatus.AVAILABLE, true));
		java.util.BitSet eligibleSlots = new java.util.BitSet(facts.size());
		eligibleSlots.set(3);

		Assert.assertEquals(keys(allIneligible, transientRead, noRows),
			noEligibleDirectBindingOwners(
				List.of(allIneligible.key(), mixed.key(), transientRead.key(), noRows.key()),
				facts, eligibleSlots));
	}

	@Test
	public void unvisitedNoQueryOwnerNeedsNoFallbackButKeepsNoObservedReceipt() throws Exception {
		Node source = node("static-source"), bridge = node("static-bridge");
		Node noQuery = node("static-no-query");
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = dependencies(
			source, bridge, bridge, noQuery);
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class, boolean.class);
		constructor.setAccessible(true);
		Method complete = type.getDeclaredMethod("complete", CompiledHopKey.class);
		complete.setAccessible(true);
		for(boolean measured : List.of(false, true)) {
			Object subscriptions = constructor.newInstance(keys(noQuery), measured);
			SearchSpaceMetrics metrics = measured ? new SearchSpaceMetrics() : null;
			assertSameKeys(keys(source, bridge), required(keys(source), potential, Map.of(), Map.of(),
				Map.of(), subscriptions, metrics, Set.of()));
			Assert.assertFalse("static no-query authority must not manufacture an observed receipt",
				(boolean)complete.invoke(subscriptions, noQuery.key()));
			assertSameKeys(keys(noQuery), required(keys(noQuery), Map.of(), Map.of(), Map.of(),
				Map.of(), subscriptions));
			if(metrics != null) {
				Assert.assertEquals(0, directMetric(metrics,
					"INVALIDATION_INCOMPLETE_NEW_NO_BINDING_OWNERS"));
				Assert.assertEquals(1, directMetric(metrics, "STATIC_NO_QUERY_NEW_PENDING_SKIPS"));
			}
		}
		// Eligibility is recomputed for each closure; there is no cross-invocation certificate.
		Object next = constructor.newInstance(Set.of(), false);
		assertSameKeys(keys(source, bridge, noQuery), required(keys(source), potential, Map.of(),
			Map.of(), Map.of(), next));
	}

	@Test
	public void staticNoQueryCertificateNeverSuppressesPendingOrEligibleSccMembers() throws Exception {
		Node pending = node("static-pending"), eligible = node("static-eligible");
		Node noQuery = node("static-sibling");
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class, boolean.class);
		constructor.setAccessible(true);
		Object subscriptions = constructor.newInstance(keys(noQuery), false);
		PlacementDependencyComponents schedule = supportSchedule(List.of(pending, eligible, noQuery),
			dependencies(pending, eligible, eligible, noQuery, noQuery, pending), Map.of());
		assertSameKeys(keys(pending, eligible), ready(schedule, keys(pending), subscriptions));
		assertSameKeys(keys(pending, eligible, noQuery), ready(schedule, keys(noQuery), subscriptions));
		Assert.assertTrue(ready(schedule, Set.of(), subscriptions).isEmpty());
		PlacementDependencyComponents sole = supportSchedule(List.of(noQuery), Map.of(), Map.of());
		assertSameKeys(keys(noQuery), ready(sole, keys(noQuery), subscriptions));
	}

	@Test
	public void diamondVisitsOnlyChangedRowAndItsConsumers() throws Exception {
		Node a = node("A"), b = node("B"), c = node("C"), d = node("D");
		List<Node> nodes = List.of(a, b, c, d);
		List<CompiledInputEdgeFact> edges = List.of(edge(a, b), edge(a, c), edge(b, d), edge(c, d));
		assertSameKeys(keys(b, d), affected(keys(b), nodes, edges, Map.of()));
		assertSameKeys(keys(a, b, c, d), affected(keys(a), nodes, edges, Map.of()));
	}

	@Test
	public void cycleAndFunctionReachingFollowProducerToConsumer() throws Exception {
		Node upstream = node("upstream"), argument = node("argument"), body = node("body");
		Node result = node("result"), downstream = node("downstream"), unrelated = node("unrelated");
		List<Node> nodes = List.of(upstream, argument, body, result, downstream, unrelated);
		List<CompiledInputEdgeFact> edges = List.of(edge(argument, body), edge(body, result),
			edge(result, body), edge(result, downstream));
		Map<CompiledHopKey,List<CompiledHopKey>> reaching =
			Map.of(argument.key(), List.of(upstream.key()));
		assertSameKeys(keys(body, result, downstream), affected(keys(body), nodes, edges, reaching));
		assertSameKeys(keys(upstream, argument, body, result, downstream),
			affected(keys(upstream), nodes, edges, reaching));
		assertSameKeys(keys(unrelated), affected(keys(unrelated), nodes, edges, reaching));
	}

	@Test
	public void completeQueryFootprintsSkipUnobservedTransitiveDescendants() throws Exception {
		Node a = node("subscription-a"), b = node("subscription-b");
		Node readsA = node("subscription-reads-a"), independent = node("subscription-independent");
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = dependencies(
			a, b, b, readsA, readsA, independent);
		Object subscriptions = subscriptions(Map.of(
			b.key(), keys(b), readsA.key(), keys(a, readsA), independent.key(), keys(independent)));
		Assert.assertEquals(keys(a, b, readsA), required(keys(a), potential,
			Map.of(), Map.of(), Map.of(), subscriptions));
	}

	@Test
	public void missingFootprintKeepsConservativeTransitiveFallback() throws Exception {
		Node a = node("fallback-a"), b = node("fallback-b"), c = node("fallback-c");
		Object subscriptions = subscriptions(Map.of(b.key(), keys(b)));
		Assert.assertEquals(keys(a, b, c), required(keys(a), dependencies(a, b, b, c),
			Map.of(), Map.of(), Map.of(), subscriptions));
	}

	@Test
	public void invalidationMetricsSeparateAlreadyPendingFromNewQueueWork() throws Exception {
		Node changed = node("pending-changed"), alreadyPending = node("pending-existing");
		Node incompleteNew = node("pending-incomplete-new");
		Node incompleteAlreadyPending = node("pending-incomplete-existing");
		Map<CompiledHopKey,Set<CompiledHopKey>> potential =
			dependencies(changed, alreadyPending, alreadyPending, incompleteNew,
				alreadyPending, incompleteAlreadyPending);
		Object measuredSubscriptions = subscriptions(Map.of(
			alreadyPending.key(), keys(alreadyPending)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Set<CompiledHopKey> measured = required(keys(changed), potential, Map.of(), Map.of(),
			Map.of(), measuredSubscriptions, metrics,
			keys(alreadyPending, incompleteAlreadyPending));

		Object unmeasuredSubscriptions = subscriptions(Map.of(
			alreadyPending.key(), keys(alreadyPending)));
		Set<CompiledHopKey> unmeasured = required(keys(changed), potential, Map.of(), Map.of(),
			Map.of(), unmeasuredSubscriptions);
		assertSameKeys(unmeasured, measured);
		Object nullMetricsSubscriptions = subscriptions(Map.of(
			alreadyPending.key(), keys(alreadyPending)));
		Set<CompiledHopKey> nullMetrics = required(keys(changed), potential, Map.of(), Map.of(),
			Map.of(), nullMetricsSubscriptions, null,
			keys(alreadyPending, incompleteAlreadyPending));
		assertSameKeys(unmeasured, nullMetrics);
		Assert.assertEquals(2, directMetric(metrics, "INVALIDATION_NEW_PENDING_OWNERS"));
		Assert.assertEquals(2, directMetric(metrics, "INVALIDATION_ALREADY_PENDING_OWNERS"));
		Assert.assertEquals(1, directMetric(metrics,
			"INVALIDATION_INCOMPLETE_ONLY_NEW_PENDING_OWNERS"));
		Assert.assertEquals(2, directMetric(metrics,
			"INVALIDATION_INCOMPLETE_ONLY_EXTRA_OWNERS"));
	}

	@Test
	public void newIncompletePendingWorkIsPartitionedByBindingAndInvalidationCause() throws Exception {
		Node changed = node("cause-changed"), bridge = node("cause-bridge");
		Node noBinding = node("cause-no-binding"), committed = node("cause-committed");
		Node cancelled = node("cause-cancelled"), unknown = node("cause-unknown");
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = dependencies(
			changed, bridge, bridge, noBinding, noBinding, committed, committed, cancelled, cancelled, unknown);
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class);
		constructor.setAccessible(true);
		Object subscriptions = constructor.newInstance(keys(noBinding));
		Map<CompiledHopKey,Set<CompiledHopKey>> receipts = Map.of(
			bridge.key(), keys(bridge), noBinding.key(), keys(noBinding),
			committed.key(), keys(committed), cancelled.key(), keys(cancelled));
		Method replace = type.getDeclaredMethod("replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		replace.invoke(subscriptions, receipts.keySet(), receipts, Set.of());
		Method invalidate = type.getDeclaredMethod("invalidate", Set.class);
		invalidate.setAccessible(true);
		invalidate.invoke(subscriptions, keys(noBinding, committed, cancelled));
		Method causes = type.getDeclaredMethod("recordInvalidationCauses", Set.class, Set.class);
		causes.setAccessible(true);
		causes.invoke(subscriptions, keys(noBinding, committed, cancelled), keys(noBinding, committed));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Set<CompiledHopKey> result = required(keys(changed), potential, Map.of(), Map.of(), Map.of(),
			subscriptions, metrics, Set.of());
		Object unmeasured = constructor.newInstance(keys(noBinding));
		replace.invoke(unmeasured, receipts.keySet(), receipts, Set.of());
		invalidate.invoke(unmeasured, keys(noBinding, committed, cancelled));
		causes.invoke(unmeasured, keys(noBinding, committed, cancelled), keys(noBinding, committed));
		assertSameKeys(result, required(keys(changed), potential, Map.of(), Map.of(), Map.of(),
			unmeasured));
		assertSameKeys(keys(changed, bridge, committed, cancelled, unknown), result);
		Assert.assertEquals(3, directMetric(metrics, "INVALIDATION_INCOMPLETE_ONLY_NEW_PENDING_OWNERS"));
		Assert.assertEquals(0, directMetric(metrics,
			"INVALIDATION_INCOMPLETE_NEW_NO_BINDING_OWNERS"));
		for(String category : List.of("COMMITTED", "CANCELLED", "OTHER"))
			Assert.assertEquals(category, 1, directMetric(metrics,
				"INVALIDATION_INCOMPLETE_NEW_" + category + "_OWNERS"));
		// A fresh receipt must forget a previous cancellation/committed-change label.
		replace.invoke(subscriptions, keys(committed), Map.of(committed.key(), keys(committed)), Set.of());
		invalidate.invoke(subscriptions, keys(committed));
		SearchSpaceMetrics fresh = new SearchSpaceMetrics();
		required(keys(changed), potential, Map.of(), Map.of(), Map.of(), subscriptions, fresh, Set.of());
		Assert.assertEquals(0, directMetric(fresh, "INVALIDATION_INCOMPLETE_NEW_COMMITTED_OWNERS"));
		Assert.assertEquals(2, directMetric(fresh, "INVALIDATION_INCOMPLETE_NEW_OTHER_OWNERS"));
	}

	@Test
	public void noBindingInvalidationPreservesOnlyExactSelfSingletonReceipt() throws Exception {
		Node certified = node("certified-no-binding"), other = node("certified-other");
		Node equalTwin = node("certified-no-binding");
		Assert.assertEquals(certified.key(), equalTwin.key());
		Assert.assertNotSame(certified.key(), equalTwin.key());
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class);
		constructor.setAccessible(true);
		Method replace = type.getDeclaredMethod("replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		Method invalidate = type.getDeclaredMethod("invalidate", Set.class);
		invalidate.setAccessible(true);
		Method complete = type.getDeclaredMethod("complete", CompiledHopKey.class);
		complete.setAccessible(true);

		Object exact = constructor.newInstance(keys(certified));
		Assert.assertFalse("classification alone must not pre-complete an owner",
			(boolean)complete.invoke(exact, certified.key()));
		replace.invoke(exact, keys(certified), Map.of(certified.key(), keys(certified)), Set.of());
		invalidate.invoke(exact, keys(certified));
		Assert.assertTrue("an exact self-only receipt proves that no direct query can become stale",
			(boolean)complete.invoke(exact, certified.key()));

		for(Set<CompiledHopKey> unsafe : List.of(keys(certified, other), keys(equalTwin))) {
			Object subscriptions = constructor.newInstance(keys(certified));
			replace.invoke(subscriptions, keys(certified), Map.of(certified.key(), unsafe), Set.of());
			invalidate.invoke(subscriptions, keys(certified));
			Assert.assertFalse("non-singleton and structurally-equal foreign receipts must be removed",
				(boolean)complete.invoke(subscriptions, certified.key()));
		}

		Object empty = constructor.newInstance(keys(certified));
		replace.invoke(empty, keys(certified), Map.of(certified.key(), Set.of()), Set.of());
		invalidate.invoke(empty, keys(certified));
		Assert.assertFalse("an explicitly empty query receipt is not the exact self certificate",
			(boolean)complete.invoke(empty, certified.key()));

		Object incomplete = constructor.newInstance(keys(certified));
		replace.invoke(incomplete, keys(certified), Map.of(certified.key(), keys(certified)), Set.of());
		replace.invoke(incomplete, keys(certified), Map.of(certified.key(), keys(certified)), keys(certified));
		invalidate.invoke(incomplete, keys(certified));
		Assert.assertFalse("an incomplete replacement must withdraw the prior complete receipt",
			(boolean)complete.invoke(incomplete, certified.key()));

		Object ordinary = constructor.newInstance(Set.of());
		replace.invoke(ordinary, keys(certified), Map.of(certified.key(), keys(certified)), Set.of());
		invalidate.invoke(ordinary, keys(certified));
		Assert.assertFalse((boolean)complete.invoke(ordinary, certified.key()));
	}

	@Test
	public void cancelledBoundaryDeltaSkipsOnlyCertifiedFallbackWork() throws Exception {
		Node boundary = node("boundary-rewrite-source");
		Node bridge = node("boundary-rewrite-bridge");
		Node noBinding = node("boundary-rewrite-no-binding");
		Node consumer = node("boundary-rewrite-consumer");
		List<CandidateRuleFact> original = List.of(localFact(boundary), localFact(bridge),
			directFact(noBinding, CandidateEvaluationStatus.PRIVACY_EXCLUDED, true), localFact(consumer));
		List<CandidateRuleFact> rewritten = List.of(excludedFact(boundary), original.get(1),
			original.get(2), original.get(3));
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = dependencies(
			boundary, bridge, bridge, noBinding, noBinding, consumer);

		Class<?> type = Class.forName(PlacementRelationClosure.class.getName()
			+ "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class);
		constructor.setAccessible(true);
		Method replace = type.getDeclaredMethod("replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		Method invalidate = type.getDeclaredMethod("invalidate", Set.class);
		invalidate.setAccessible(true);
		Set<CompiledHopKey> owners = keys(boundary, bridge, noBinding, consumer);
		Map<CompiledHopKey,Set<CompiledHopKey>> receipts = Map.of(
			boundary.key(), keys(boundary), bridge.key(), keys(bridge),
			noBinding.key(), keys(noBinding), consumer.key(), keys(noBinding, consumer));
		Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		slots.put(boundary.key(), List.of(0));
		slots.put(bridge.key(), List.of(1));
		slots.put(noBinding.key(), List.of(2));
		slots.put(consumer.key(), List.of(3));
		Method changedOwners = PlacementRelationClosure.class.getDeclaredMethod(
			"changedCandidateOwnersInSlots", List.class, List.class, Map.class, Set.class);
		changedOwners.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> committedChanged = (Set<CompiledHopKey>)changedOwners.invoke(null,
			original, rewritten, slots, keys(boundary, noBinding));
		assertSameKeys(keys(boundary), committedChanged);

		// A boundary rewrite that restores the no-binding row is a touched-but-cancelled
		// write. The certificate may retain only its exact self receipt.
		Object certified = constructor.newInstance(keys(noBinding));
		Object conservative = constructor.newInstance(Set.of());
		replace.invoke(certified, owners, receipts, Set.of());
		replace.invoke(conservative, owners, receipts, Set.of());
		invalidate.invoke(certified, keys(noBinding));
		invalidate.invoke(conservative, keys(noBinding));

		SearchSpaceMetrics certifiedMetrics = new SearchSpaceMetrics();
		SearchSpaceMetrics conservativeMetrics = new SearchSpaceMetrics();
		Set<CompiledHopKey> certifiedRequired = required(committedChanged, potential, Map.of(), Map.of(),
			Map.of(), certified, certifiedMetrics, Set.of());
		Set<CompiledHopKey> conservativeRequired = required(committedChanged, potential, Map.of(), Map.of(),
			Map.of(), conservative, conservativeMetrics, Set.of());
		assertSameKeys(keys(boundary, bridge), certifiedRequired);
		assertSameKeys(keys(boundary, bridge, noBinding), conservativeRequired);
		Assert.assertEquals(0, directMetric(certifiedMetrics,
			"INVALIDATION_INCOMPLETE_ONLY_NEW_PENDING_OWNERS"));
		Assert.assertEquals(1, directMetric(conservativeMetrics,
			"INVALIDATION_INCOMPLETE_ONLY_NEW_PENDING_OWNERS"));

		Constructor<?> unmeasuredConstructor = type.getDeclaredConstructor(Set.class, boolean.class);
		unmeasuredConstructor.setAccessible(true);
		Object certifiedUnmeasured = unmeasuredConstructor.newInstance(keys(noBinding), false);
		Object conservativeUnmeasured = unmeasuredConstructor.newInstance(Set.of(), false);
		replace.invoke(certifiedUnmeasured, owners, receipts, Set.of());
		replace.invoke(conservativeUnmeasured, owners, receipts, Set.of());
		invalidate.invoke(certifiedUnmeasured, keys(noBinding));
		invalidate.invoke(conservativeUnmeasured, keys(noBinding));
		assertSameKeys(certifiedRequired, required(committedChanged, potential, Map.of(), Map.of(),
			Map.of(), certifiedUnmeasured));
		assertSameKeys(conservativeRequired, required(committedChanged, potential, Map.of(), Map.of(),
			Map.of(), conservativeUnmeasured));

		// Static no-query authority is independent of observed receipt replacement;
		// no observed query result is manufactured.
		replace.invoke(certified, keys(noBinding), Map.of(), keys(noBinding));
		assertSameKeys(certifiedRequired, required(committedChanged, potential, Map.of(), Map.of(),
			Map.of(), certified));
		replace.invoke(certified, keys(noBinding), Map.of(noBinding.key(), keys(noBinding)), Set.of());
		invalidate.invoke(certified, keys(noBinding));
		assertSameKeys(certifiedRequired, required(committedChanged, potential, Map.of(), Map.of(),
			Map.of(), certified));
	}

	@Test
	public void changedNoBindingOwnerStillSchedulesItselfAndItsSubscriber() throws Exception {
		Node noBinding = node("changed-no-binding"), consumer = node("changed-consumer");
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor(Set.class);
		constructor.setAccessible(true);
		Object subscriptions = constructor.newInstance(keys(noBinding));
		Method replace = type.getDeclaredMethod("replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		replace.invoke(subscriptions, keys(noBinding, consumer), Map.of(
			noBinding.key(), keys(noBinding), consumer.key(), keys(noBinding, consumer)), Set.of());
		Method invalidate = type.getDeclaredMethod("invalidate", Set.class);
		invalidate.setAccessible(true);
		invalidate.invoke(subscriptions, keys(noBinding));
		Method complete = type.getDeclaredMethod("complete", CompiledHopKey.class);
		complete.setAccessible(true);
		Assert.assertTrue((boolean)complete.invoke(subscriptions, noBinding.key()));
		assertSameKeys(keys(noBinding, consumer), required(keys(noBinding), Map.of(),
			Map.of(), Map.of(), Map.of(), subscriptions));
	}

	@Test
	public void removedSupportCyclesAndAliasesRemainImmediateInvalidations() throws Exception {
		Node source = node("hybrid-source"), first = node("hybrid-first");
		Node second = node("hybrid-second"), alias = node("hybrid-alias");
		Object subscriptions = subscriptions(Map.of(
			first.key(), keys(first), second.key(), keys(second), alias.key(), keys(alias)));
		Map<CompiledHopKey,Set<CompiledHopKey>> support = dependencies(first, second, second, first);
		Map<CompiledHopKey,Set<CompiledHopKey>> removed = dependencies(source, second);
		Map<CompiledHopKey,List<CompiledHopKey>> aliases = new IdentityHashMap<>();
		aliases.put(source.key(), List.of(source.key(), alias.key()));
		Assert.assertEquals(keys(source, second, alias), required(keys(source), Map.of(), support,
			removed, aliases, subscriptions));
	}

	@Test
	public void cancelledDirectOutputMakesItsOwnerIncompleteForTheNextSccWave() throws Exception {
		Node restored = node("cancelled-restored"), peer = node("cancelled-peer");
		Object subscriptions = subscriptions(Map.of(
			restored.key(), keys(restored), peer.key(), keys(peer)));
		Method invalidate = subscriptions.getClass().getDeclaredMethod("invalidate", Set.class);
		invalidate.setAccessible(true);
		invalidate.invoke(subscriptions, keys(restored));
		PlacementDependencyComponents component = new PlacementDependencyComponents(
			List.of(restored.key(), peer.key()),
			List.of(new PlacementDependencyComponents.SemanticDependency(restored.key(), peer.key()),
				new PlacementDependencyComponents.SemanticDependency(peer.key(), restored.key())), List.of());
		Method ready = PlacementRelationClosure.class.getDeclaredMethod("readyDirectOwners",
			PlacementDependencyComponents.class, Set.class, subscriptions.getClass());
		ready.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> selected = (Set<CompiledHopKey>)ready.invoke(
			null, component, keys(peer), subscriptions);
		Assert.assertEquals("a boundary-restored owner must rerun with another dirty SCC member",
			keys(restored, peer), selected);
	}


	@Test
	public void oneValueVersionExpandsAliasesWithoutReversingDependencies() throws Exception {
		Node producer = node("producer"), alias = node("alias", producer.valueVersion());
		Node consumer = node("consumer"), upstream = node("upstream");
		List<Node> nodes = List.of(producer, alias, consumer, upstream);
		List<CompiledInputEdgeFact> edges = List.of(edge(upstream, producer), edge(alias, consumer));
		assertSameKeys(keys(producer, alias, consumer),
			affected(keys(producer), nodes, edges, Map.of()));
	}

	@Test
	public void removedOrAddedSupportStillInvalidatesItsConsumer() throws Exception {
		Node source = node("support-source"), owner = node("support-owner");
		Node other = node("unrelated");
		List<Node> nodes = List.of(source, owner, other);
		List<CandidateRuleFact> bound = List.of(supportFact(source, owner));
		assertSameKeys(keys(source, owner), affected(keys(source), nodes, List.of(), Map.of(),
			bound, List.of()));
		assertSameKeys(keys(source, owner), affected(keys(source), nodes, List.of(), Map.of(),
			List.of(), bound));
	}

	@Test
	public void postPhysicalFirstPassPreservesUnchangedIndependentRows() throws Exception {
		Node a = node("post-a"), b = node("post-b"), other = node("post-other");
		List<Node> nodes = List.of(a, b, other);
		List<CandidateRuleFact> facts = List.of(localFact(a), localFact(b), localFact(other));
		List<CompiledInputEdgeFact> edges = List.of(edge(a, b));
		assertSameKeys(Set.of(), postPhysicalDirty(nodes, facts, edges, Map.of(),
			nodes, facts, edges, Map.of(), Set.of()));
		assertSameKeys(keys(a, b), postPhysicalDirty(nodes, facts, edges, Map.of(),
			nodes, List.of(excludedFact(a), facts.get(1), facts.get(2)), edges, Map.of(), Set.of()));
	}

	@Test
	public void regeneratedIdenticalEdgesDoNotDirtyTheirConsumers() throws Exception {
		Node a = node("same-edge-a"), b = node("same-edge-b"), c = node("same-edge-c");
		List<Node> nodes = List.of(a, b, c);
		List<CandidateRuleFact> facts = List.of(localFact(a), localFact(b), localFact(c));
		Assert.assertNotSame("fixture needs independently regenerated edge objects",
			edge(a, b), edge(a, b));
		assertSameKeys(Set.of(), postPhysicalDirty(nodes, facts,
			List.of(edge(a, b), edge(b, c)), Map.of(), nodes, facts,
			List.of(edge(a, b), edge(b, c)), Map.of(), Set.of()));
	}

	@Test
	public void exactEmptyDirtySetPreservesDirectAuthoritiesWithoutOpeningClosure() throws Exception {
		Node owner = node("clean-direct-owner");
		List<CandidateRuleFact> facts = List.of(localFact(owner));
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(owner.key(), owner), Map.of(), facts, List.of(), Map.of());
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"unchangedDirectClosure", List.class, NativePlacementContinuity.class, Set.class);
		method.setAccessible(true);
		Object result = method.invoke(null, facts, continuity, Set.of());
		Assert.assertNotNull(result);
		Method resultFacts = result.getClass().getDeclaredMethod("facts");
		Method resultContinuity = result.getClass().getDeclaredMethod("continuity");
		resultFacts.setAccessible(true);
		resultContinuity.setAccessible(true);
		Assert.assertSame(facts, resultFacts.invoke(result));
		Assert.assertSame(continuity, resultContinuity.invoke(result));
		Assert.assertNull("null means the first/unpairable revision must run the full closure",
			method.invoke(null, facts, continuity, null));
		Assert.assertNull("a real dirty owner must run the incremental closure",
			method.invoke(null, facts, continuity, keys(owner)));
	}

	@Test
	public void postPhysicalFirstPassUsesOldAndNewDependencies() throws Exception {
		Node a = node("edge-a"), b = node("edge-b"), c = node("edge-c");
		Node d = node("edge-d"), unrelated = node("edge-unrelated");
		List<Node> nodes = List.of(a, b, c, d, unrelated);
		List<CandidateRuleFact> before = List.of(localFact(a), localFact(b), localFact(c),
			localFact(d), localFact(unrelated));
		List<CandidateRuleFact> after = List.of(excludedFact(a), before.get(1), before.get(2),
			before.get(3), before.get(4));
		List<CompiledInputEdgeFact> oldEdges = List.of(edge(a, b), edge(b, c));
		List<CompiledInputEdgeFact> newEdges = List.of(edge(a, d), edge(d, c));
		assertSameKeys(keys(a, b, c, d), postPhysicalDirty(nodes, before, oldEdges, Map.of(),
			nodes, after, newEdges, Map.of(), Set.of()));
	}

	@Test
	public void postPhysicalFirstPassSeedsChangedEdgesAndReachingWithoutFactChanges() throws Exception {
		Node a = node("route-a"), b = node("route-b"), c = node("route-c");
		List<Node> nodes = List.of(a, b, c);
		List<CandidateRuleFact> facts = List.of(localFact(a), localFact(b), localFact(c));
		assertSameKeys(keys(b, c), postPhysicalDirty(nodes, facts, List.of(edge(a, b)), Map.of(),
			nodes, facts, List.of(edge(b, c)), Map.of(), Set.of()));
		assertSameKeys(keys(c), postPhysicalDirty(nodes, facts, List.of(),
			Map.of(c.key(), List.of(a.key())), nodes, facts, List.of(),
			Map.of(c.key(), List.of(b.key())), Set.of()));
	}

	@Test
	public void postPhysicalFirstPassSeesAbsentSourceAndNewLoopSeed() throws Exception {
		Node source = node("new-source"), consumer = node("new-consumer");
		Node loopRead = node("loop-read"), loopBody = node("loop-body");
		List<Node> nodes = List.of(source, consumer, loopRead, loopBody);
		List<CandidateRuleFact> after = List.of(localFact(source), localFact(consumer),
			localFact(loopRead), localFact(loopBody));
		List<CandidateRuleFact> before = List.of(excludedFact(source), after.get(1),
			after.get(2), after.get(3));
		List<CompiledInputEdgeFact> edges = List.of(edge(source, consumer),
			edge(loopRead, loopBody), edge(loopBody, loopRead));
		assertSameKeys(keys(source, consumer, loopRead, loopBody), postPhysicalDirty(nodes, before,
			List.of(), Map.of(), nodes, after, edges, Map.of(), keys(loopRead)));
	}

	@Test
	public void postPhysicalFirstPassFallsBackWhenGlobalAnchorInventoryChanges() throws Exception {
		Node source = node("anchor-source"), consumer = node("anchor-consumer");
		DurableAnchorKey anchor = new DurableAnchorKey("new-row-pool", FType.ROW,
			List.of(new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(2L, 2L))));
		Node anchored = new Node(source.key(), source.kind(), source.valueVersion(),
			source.emittedWork(), source.legalAlternatives(), source.exclusions(), List.of(anchor));
		List<CandidateRuleFact> facts = List.of(localFact(source), localFact(consumer));
		Assert.assertNull("new global durable seed may enable a previously absent source",
			postPhysicalDirty(List.of(source, consumer), facts, List.of(), Map.of(),
				List.of(anchored, consumer), facts, List.of(), Map.of(), Set.of()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertNull(outerDirty(List.of(source, consumer), facts, List.of(), Map.of(),
			List.of(anchored, consumer), facts, List.of(), Map.of(), Set.of(), metrics));
		Assert.assertEquals(1, directMetric(metrics, "INITIAL_FULL_RESET_BASELINE"));
	}

	@Test
	public void outerPassFallsBackWhenReferencedDerivedAnchorOwnerChanges() throws Exception {
		Node anchorOwner = node("derived-anchor-owner"), producer = node("derived-producer");
		CandidateRuleFact ownerBefore = localFact(anchorOwner);
		CandidateRuleFact ownerAfter = excludedFact(anchorOwner);
		CandidateRuleFact derived = derivedFact(producer, anchorOwner);
		List<Node> nodes = List.of(anchorOwner, producer);

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertNull("derived action metadata reads the anchor owner's complete node/fact row",
			outerDirty(nodes, List.of(ownerBefore, derived), List.of(), Map.of(),
				nodes, List.of(ownerAfter, derived), List.of(), Map.of(), Set.of(), metrics));
		Assert.assertEquals(1, directMetric(metrics,
			"INITIAL_FULL_RESET_DERIVED_ANCHOR_CONTEXT"));
	}

	@Test
	public void outerPassKeepsIndependentPreparedRowsClean() throws Exception {
		Node source = node("outer-source"), consumer = node("outer-consumer");
		Node independent = node("outer-independent");
		List<Node> nodes = List.of(source, consumer, independent);
		List<CandidateRuleFact> before = List.of(
			localFact(source), localFact(consumer), localFact(independent));
		List<CandidateRuleFact> after = List.of(
			excludedFact(source), before.get(1), before.get(2));
		List<CompiledInputEdgeFact> edges = List.of(edge(source, consumer));

		assertSameKeys(keys(source, consumer), outerDirty(nodes, before, edges, Map.of(),
			nodes, after, edges, Map.of(), Set.of()));
	}

	@Test
	public void outerPassFallsBackWhenUpstreamChangeDirtiesReferencedAnchorOwner() throws Exception {
		Node source = node("metadata-source"), anchorOwner = node("metadata-anchor-owner");
		Node producer = node("metadata-derived-producer");
		CandidateRuleFact owner = localFact(anchorOwner);
		CandidateRuleFact derived = derivedFact(producer, anchorOwner);
		List<Node> nodes = List.of(source, anchorOwner, producer);
		List<CandidateRuleFact> before = List.of(localFact(source), owner, derived);
		List<CandidateRuleFact> after = List.of(excludedFact(source), owner, derived);
		List<CompiledInputEdgeFact> edges = List.of(edge(source, anchorOwner));

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertNull("a dirty anchor owner can change metadata observed by an unrelated action",
			outerDirty(nodes, before, edges, Map.of(),
				nodes, after, edges, Map.of(), Set.of(), metrics));
		Assert.assertEquals(1, directMetric(metrics,
			"INITIAL_FULL_RESET_REFERENCED_OWNER_DIRTY"));
	}

	@Test
	public void incrementalSupportMatchesFullScanForDuplicatesWithdrawalAndRestoration() throws Exception {
		Node source = node("index-source"), alternate = node("index-alternate");
		Node owner = node("index-owner"), independent = node("index-independent");
		List<CandidateRuleFact> before = List.of(supportFact(source, owner),
			supportFact(source, owner), supportFact(source, independent));
		Object index = supportIndex(before);
		assertAdjacency(fullSupport(before), indexedSupport(index));
		List<List<CandidateRuleFact>> revisions = List.of(
			List.of(excludedFact(owner), before.get(1), before.get(2)),
			List.of(excludedFact(owner), excludedFact(owner), before.get(2)),
			List.of(supportFact(alternate, owner), supportFact(source, owner), before.get(2)),
			List.of(supportFact(source, owner), supportFact(source, owner), before.get(2)));
		for(List<CandidateRuleFact> after : revisions) {
			Map<CompiledHopKey,Set<CompiledHopKey>> old = fullSupport(before);
			Object delta = updateSupport(index, keys(owner), after);
			Map<CompiledHopKey,Set<CompiledHopKey>> current = indexedSupport(index);
			assertAdjacency(fullSupport(after), current);
			assertAdjacency(unionSupport(old, fullSupport(after)),
				unionSupport(current, removedSupport(delta)));
			Assert.assertEquals(!sameAdjacency(old, current), supportChanged(delta));
			before = after;
		}
	}

	@Test
	public void incrementalSupportUsesOccurrenceIdentityAndOnlyChangedOwnerSlots() throws Exception {
		Node source = node("same-source"), equalSource = node("same-source");
		Node owner = node("same-owner"), equalOwner = node("same-owner");
		Assert.assertNotSame(source.key(), equalSource.key());
		List<CandidateRuleFact> before = List.of(supportFact(source, owner),
			supportFact(equalSource, equalOwner), supportFact(source, owner));
		Object index = supportIndex(before);
		List<CandidateRuleFact> after = List.of(supportFact(equalSource, owner),
			before.get(1), excludedFact(owner));
		List<Integer> visited = new ArrayList<>();
		List<CandidateRuleFact> guarded = new AbstractList<>() {
			@Override public int size() { return after.size(); }
			@Override public CandidateRuleFact get(int slot) {
				Assert.assertNotEquals("unchanged owner's rows must not be revisited", 1, slot);
				visited.add(slot);
				return after.get(slot);
			}
		};
		Object delta = updateSupport(index, keys(owner), guarded);
		Assert.assertEquals(List.of(0, 2), visited);
		assertAdjacency(fullSupport(after), indexedSupport(index));
		Assert.assertEquals(keys(owner, equalOwner), indexedSupport(index).get(equalSource.key()));
		Assert.assertFalse(indexedSupport(index).containsKey(source.key()));
		Assert.assertEquals(keys(owner), removedSupport(delta).get(source.key()));
	}

	@Test
	public void postBoundaryChangedOwnersRefreshEvenWhenAdjacencyIsUnchanged() throws Exception {
		Node source = node("proof-source"), other = node("proof-other");
		Node selected = node("selected"), boundary = node("unselected-boundary");
		CandidateRuleFact bound = supportFact(source, boundary);
		CandidateRuleFact proofChanged = new CandidateRuleFact(bound.key(), bound.status(),
			bound.capability(), bound.shapeProof(), new CandidateProfileFact(List.of(FType.ROW), ""),
			bound.allowedEmissionFacts(), bound.failureCode());
		List<CandidateRuleFact> before = List.of(supportFact(source, selected), bound);
		List<CandidateRuleFact> after = List.of(supportFact(other, selected), proofChanged);
		Object index = supportIndex(before);
		Method changedMethod = PlacementRelationClosure.class.getDeclaredMethod(
			"changedCandidateOccurrences", List.class, List.class);
		changedMethod.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> changed = (Set<CompiledHopKey>)changedMethod.invoke(null, before, after);
		Assert.assertEquals(keys(selected, boundary), changed);
		Object delta = updateSupport(index, changed, after);
		assertAdjacency(fullSupport(after), indexedSupport(index));
		Assert.assertTrue(supportChanged(delta));
		CandidateRuleFact again = new CandidateRuleFact(proofChanged.key(), proofChanged.status(),
			proofChanged.capability(), proofChanged.shapeProof(), new CandidateProfileFact(List.of(FType.COL), ""),
			proofChanged.allowedEmissionFacts(), proofChanged.failureCode());
		List<CandidateRuleFact> unchangedAdjacency = List.of(after.get(0), again);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> proofDirty = (Set<CompiledHopKey>)changedMethod.invoke(null, after, unchangedAdjacency);
		Assert.assertEquals(keys(boundary), proofDirty);
		Assert.assertFalse(supportChanged(updateSupport(index, proofDirty, unchangedAdjacency)));
		assertAdjacency(fullSupport(unchangedAdjacency), indexedSupport(index));
	}

	@Test
	public void identicalFactListAvoidsAllChangeDetectionTraversal() throws Exception {
		Node source = node("stable-source"), first = node("stable-first"), second = node("stable-second");
		CountingFactList facts = new CountingFactList(List.of(
			supportFact(source, first), supportFact(source, second)));

		Assert.assertTrue(changedCandidateOccurrences(facts, facts).isEmpty());
		Assert.assertEquals("identical list relation must return before owner-map grouping",
			0, facts.accesses());
	}

	@Test
	public void independentlyRebuiltCompleteFactsWithSharedOwnersRemainUnchanged() throws Exception {
		Node source = node("equal-source"), first = node("equal-first"), second = node("equal-second");
		List<CandidateRuleFact> before = List.of(
			supportFact(source, first), supportFact(source, second));
		List<CandidateRuleFact> after = List.of(
			supportFact(source, first), supportFact(source, second));

		Assert.assertEquals(before, after);
		for(int slot = 0; slot < before.size(); slot++) {
			CandidateRuleFact oldFact = before.get(slot), newFact = after.get(slot);
			Assert.assertNotSame(oldFact, newFact);
			Assert.assertSame(oldFact.key().parentOccurrence(), newFact.key().parentOccurrence());
			Assert.assertNotSame(oldFact.allowedEmissionFacts().get(0),
				newFact.allowedEmissionFacts().get(0));
			Assert.assertNotSame(oldFact.allowedEmissionFacts().get(0).realizations().get(0),
				newFact.allowedEmissionFacts().get(0).realizations().get(0));
			Assert.assertNotSame(oldFact.allowedEmissionFacts().get(0).realizations().get(0)
				.requireSingletonSupportClause(), newFact.allowedEmissionFacts().get(0).realizations().get(0)
				.requireSingletonSupportClause());
		}
		Assert.assertTrue(changedCandidateOccurrences(before, after).isEmpty());
	}

	@Test
	public void structurallyEqualButDistinctOwnerIdentitiesBothRemainDirty() throws Exception {
		Node source = node("identity-source");
		Node beforeOwner = node("identity-owner"), afterOwner = node("identity-owner");
		Assert.assertNotSame(beforeOwner.key(), afterOwner.key());
		Assert.assertEquals(beforeOwner.key(), afterOwner.key());
		CandidateRuleFact before = supportFact(source, beforeOwner);
		CandidateRuleFact after = supportFact(source, afterOwner);
		Assert.assertEquals("fixture must defeat bare structural list equality", before, after);

		assertSameKeys(keys(beforeOwner, afterOwner),
			changedCandidateOccurrences(List.of(before), List.of(after)));
	}

	@Test
	public void nestedAuthorityChangeMarksOnlyItsExactOwner() throws Exception {
		Node oldSource = node("nested-old-source"), newSource = node("nested-new-source");
		Node changedOwner = node("nested-owner"), stableOwner = node("nested-stable-owner");
		CandidateRuleFact oldChanged = supportFact(oldSource, changedOwner);
		CandidateRuleFact newChanged = supportFact(newSource, changedOwner);
		CandidateRuleFact oldStable = supportFact(oldSource, stableOwner);
		CandidateRuleFact newStable = supportFact(oldSource, stableOwner);
		Assert.assertEquals(oldChanged.key(), newChanged.key());
		Assert.assertEquals(oldChanged.allowedEmissionFacts().get(0).emissionState(),
			newChanged.allowedEmissionFacts().get(0).emissionState());
		Assert.assertNotEquals(oldChanged, newChanged);
		Assert.assertEquals(oldStable, newStable);

		assertSameKeys(keys(changedOwner), changedCandidateOccurrences(
			List.of(oldChanged, oldStable), List.of(newChanged, newStable)));
	}

	@Test
	public void ownerInventoryMultirowAndOrderingUseExactFallbackSemantics() throws Exception {
		Node sourceOne = node("fallback-source-one"), sourceTwo = node("fallback-source-two");
		Node sourceThree = node("fallback-source-three");
		Node owner = node("fallback-owner"), other = node("fallback-other");
		CandidateRuleFact first = supportFact(sourceOne, owner);
		CandidateRuleFact second = supportFact(sourceTwo, owner);
		CandidateRuleFact otherFact = supportFact(sourceOne, other);

		assertSameKeys(keys(other), changedCandidateOccurrences(
			List.of(first, otherFact), List.of(first)));
		assertSameKeys(keys(other), changedCandidateOccurrences(
			List.of(first), List.of(first, otherFact)));
		assertSameKeys(keys(owner), changedCandidateOccurrences(
			List.of(first, second, otherFact),
			List.of(first, supportFact(sourceThree, owner), otherFact)));
		assertSameKeys(keys(owner), changedCandidateOccurrences(
			List.of(first, second, otherFact), List.of(second, first, otherFact)));
		Assert.assertTrue("global owner order may change while each owner's row order stays exact",
			changedCandidateOccurrences(List.of(first, otherFact, second),
				List.of(otherFact, first, second)).isEmpty());
	}

	@Test
	public void replayShrinkAndReorderReportsSemanticOwnersWithoutPositionalAccess() throws Exception {
		Node oldSource = node("replay-old-source"), newSource = node("replay-new-source");
		Node changed = node("replay-changed-owner"), stable = node("replay-stable-owner");
		Node removed = node("replay-removed-owner");
		CandidateRuleFact changedBefore = supportFact(oldSource, changed);
		CandidateRuleFact stableBefore = supportFact(oldSource, stable);
		CandidateRuleFact removedBefore = supportFact(oldSource, removed);
		CandidateRuleFact changedAfter = supportFact(newSource, changed);
		CandidateRuleFact stableAfter = supportFact(oldSource, stable);

		assertSameKeys(keys(changed, removed), changedCandidateOccurrences(
			List.of(changedBefore, stableBefore, removedBefore),
			List.of(stableAfter, changedAfter)));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void completeTouchedOwnerDeltaMatchesColdAndSkipsUnrelatedSlots() throws Exception {
		Node restored = node("delta-restored"), changed = node("delta-changed"), unrelated = node("delta-unrelated");
		List<CandidateRuleFact> before = List.of(localFact(restored), localFact(changed), localFact(unrelated));
		List<CandidateRuleFact> after = List.of(before.get(0), excludedFact(changed), before.get(2));
		Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		slots.put(restored.key(), List.of(0));
		slots.put(changed.key(), List.of(1));
		slots.put(unrelated.key(), List.of(2));
		List<CandidateRuleFact> guarded = new AbstractList<>() {
			@Override public int size() { return after.size(); }
			@Override public CandidateRuleFact get(int index) {
				if(index == 2)
					throw new AssertionError("An unchanged owner outside the complete delta must not be visited");
				return after.get(index);
			}
		};
		Method method = PlacementRelationClosure.class.getDeclaredMethod("changedCandidateOwnersInSlots",
			List.class, List.class, Map.class, Set.class);
		method.setAccessible(true);
		Set<CompiledHopKey> actual = (Set<CompiledHopKey>)method.invoke(null, before, guarded, slots,
			keys(restored, changed));
		Assert.assertEquals(changedCandidateOccurrences(before, after), actual);
		Assert.assertEquals("a boundary change that restores an original row cancels the direct delta",
			keys(changed), actual);
		List<CandidateRuleFact> foreign = List.of(before.get(0), localFact(node("delta-changed")), before.get(2));
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> method.invoke(null, before, foreign, slots, keys(changed)));
		Assert.assertTrue(error.getCause() instanceof IllegalStateException);
	}

	@Test
	public void supportAdditionsAndDeletionsRebuildCycleSchedule() throws Exception {
		Node a = node("cycle-index-a"), b = node("cycle-index-b"), c = node("cycle-index-c");
		List<CandidateRuleFact> before = List.of(excludedFact(a), supportFact(a, b), supportFact(b, c));
		Object index = supportIndex(before);
		Assert.assertEquals(3, supportSchedule(List.of(a, b, c), indexedSupport(index)).topologicalOrder().size());
		List<CandidateRuleFact> cycle = List.of(supportFact(c, a), before.get(1), before.get(2));
		Assert.assertTrue(supportChanged(updateSupport(index, keys(a), cycle)));
		Assert.assertEquals(1, supportSchedule(List.of(a, b, c), indexedSupport(index)).topologicalOrder().size());
		Object removed = updateSupport(index, keys(a), before);
		Assert.assertTrue(supportChanged(removed));
		Assert.assertEquals(keys(a), removedSupport(removed).get(c.key()));
		Assert.assertEquals(3, supportSchedule(List.of(a, b, c), indexedSupport(index)).topologicalOrder().size());
	}

	@Test
	public void coveredSupportChangesDoNotRequeueCertifiedCleanSccMembers() throws Exception {
		Node a = node("covered-a"), b = node("covered-b"), c = node("covered-c");
		List<Node> nodes = List.of(a, b, c);
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = dependencies(a, b, b, c, c, a);
		List<CandidateRuleFact> before = List.of(excludedFact(a), excludedFact(b), excludedFact(c));
		List<CandidateRuleFact> added = List.of(before.get(0), supportFact(a, b), before.get(2));
		Object index = supportIndex(before, potential);
		PlacementDependencyComponents schedule = supportSchedule(nodes, potential, indexedSupport(index));
		Assert.assertEquals(1, schedule.topologicalOrder().size());
		for(List<CandidateRuleFact> revision : List.of(added, before)) {
			Object update = updateSupport(index, keys(b), revision);
			Assert.assertTrue(supportChanged(update));
			Object subscriptions = subscriptions(Map.of(a.key(), keys(a), b.key(), keys(b), c.key(), keys(c)));
			Set<CompiledHopKey> pending = required(keys(b), potential, indexedSupport(index),
				removedSupport(update), Map.of(), subscriptions);
			assertSameKeys(keys(b, c), pending);
			Assert.assertFalse(prepareRebuild(update, Set.of(schedule.componentOf(b.key())), pending));
			assertSameKeys(keys(b, c), pending);
			assertSameKeys(keys(b, c), ready(schedule, pending, subscriptions));
			Method invalidate = subscriptions.getClass().getDeclaredMethod("invalidate", Set.class);
			invalidate.setAccessible(true);
			invalidate.invoke(subscriptions, keys(a));
			assertSameKeys(keys(a, b, c), ready(schedule, pending, subscriptions));
			// Settled does not mean immune: the next genuine predecessor delta requeues A.
			Assert.assertTrue(required(keys(c), potential, indexedSupport(index), Map.of(),
				Map.of(), subscriptions).contains(a.key()));
		}
	}

	@Test
	public void dependencyUnionChangesPreserveUnsettledOldSccMembers() throws Exception {
		Node a = node("rebuild-a"), b = node("rebuild-b"), c = node("rebuild-c");
		List<Node> nodes = List.of(a, b, c);
		List<CandidateRuleFact> before = List.of(excludedFact(a), supportFact(a, b), supportFact(b, c));
		List<CandidateRuleFact> cycle = List.of(supportFact(c, a), before.get(1), before.get(2));
		Object index = supportIndex(before);
		PlacementDependencyComponents oldSchedule = supportSchedule(nodes, indexedSupport(index));
		Object added = updateSupport(index, keys(a), cycle);
		Set<CompiledHopKey> pending = keys(a);
		Assert.assertTrue(prepareRebuild(added, Set.copyOf(oldSchedule.topologicalOrder()), pending));
		PlacementDependencyComponents cyclic = supportSchedule(nodes, indexedSupport(index));
		Assert.assertEquals(1, cyclic.topologicalOrder().size());
		Object removed = updateSupport(index, keys(a), before);
		pending = keys(a);
		Assert.assertTrue(prepareRebuild(removed, Set.of(cyclic.componentOf(a.key())), pending));
		assertSameKeys(keys(a, b, c), pending);
		Assert.assertEquals(3, supportSchedule(nodes, indexedSupport(index)).topologicalOrder().size());
	}

	@Test
	public void unionRebuildGuardDoesNotConfuseStructurallyEqualOwners() throws Exception {
		Node source = node("covered-identity"), twin = node("covered-identity"), owner = node("covered-owner");
		List<CandidateRuleFact> before = List.of(excludedFact(owner));
		Object index = supportIndex(before, dependencies(source, owner));
		Object update = updateSupport(index, keys(owner), List.of(supportFact(twin, owner)));
		Assert.assertTrue(prepareRebuild(update, Set.of(), keys(owner)));
	}

	@Test
	public void incrementalSupportRefusesInventoryOwnerOrRuleKeyDrift() throws Exception {
		Node owner = node("fixed-slot-owner"), other = node("foreign-slot-owner");
		CandidateRuleFact fact = supportFact(owner, owner);
		CandidateRuleFact differentRule = new CandidateRuleFact(
			new CandidateRuleKey(owner.key(), List.of(PlacementAnalysis.CandidateInputState.absentLocal())),
			fact.status(), fact.capability(), fact.shapeProof(), fact.profile(), fact.allowedEmissionFacts(), "");
		for(List<CandidateRuleFact> invalid : List.of(List.<CandidateRuleFact>of(),
			List.of(supportFact(other, other)), List.of(differentRule))) {
			Object index = supportIndex(List.of(fact));
			try {
				updateSupport(index, keys(owner), invalid);
				Assert.fail("fixed slot/key drift must fail explicitly, never silently rebuild or skip");
			}
			catch(InvocationTargetException expected) {
				Assert.assertTrue(expected.getCause() instanceof IllegalStateException);
				Assert.assertTrue(expected.getCause().getMessage().startsWith("Direct closure changed"));
			}
		}
	}

	private static PlacementDependencyComponents supportSchedule(List<Node> nodes,
		Map<CompiledHopKey,Set<CompiledHopKey>> support) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directComponentSchedule",
			List.class, Map.class, Map.class, List.class);
		method.setAccessible(true);
		return (PlacementDependencyComponents)method.invoke(null, nodes.stream().map(Node::key).toList(),
			Map.of(), support, List.of());
	}

	private static PlacementDependencyComponents supportSchedule(List<Node> nodes,
		Map<CompiledHopKey,Set<CompiledHopKey>> potential,
		Map<CompiledHopKey,Set<CompiledHopKey>> support) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directComponentSchedule",
			List.class, Map.class, Map.class, List.class);
		method.setAccessible(true);
		return (PlacementDependencyComponents)method.invoke(null, nodes.stream().map(Node::key).toList(),
			potential, support, List.of());
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> ready(PlacementDependencyComponents schedule,
		Set<CompiledHopKey> pending, Object subscriptions) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("readyDirectOwners",
			PlacementDependencyComponents.class, Set.class, subscriptions.getClass());
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, schedule, pending, subscriptions);
	}

	private static boolean prepareRebuild(Object update,
		Set<PlacementDependencyComponents.Component> dirty, Set<CompiledHopKey> pending) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"prepareDirectComponentRebuild", update.getClass(), Set.class, Set.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null, update, dirty, pending);
	}

	private static Object supportIndex(List<CandidateRuleFact> facts) throws Exception {
		return supportIndex(facts, Map.of());
	}

	private static Object supportIndex(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Set<CompiledHopKey>> potential) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectSupportIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, List.class, Map.class);
		constructor.setAccessible(true);
		Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		for(int i = 0; i < facts.size(); i++)
			slots.computeIfAbsent(facts.get(i).key().parentOccurrence(), ignored -> new ArrayList<>()).add(i);
		return constructor.newInstance(slots, facts, potential);
	}

	private static Object updateSupport(Object index, Set<CompiledHopKey> changed,
		List<CandidateRuleFact> facts) throws Exception {
		Method method = index.getClass().getDeclaredMethod("update", Set.class, List.class);
		method.setAccessible(true);
		return method.invoke(index, changed, facts);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,Set<CompiledHopKey>> indexedSupport(Object index) throws Exception {
		Field field = index.getClass().getDeclaredField("consumersBySource");
		field.setAccessible(true);
		return (Map<CompiledHopKey,Set<CompiledHopKey>>)field.get(index);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,Set<CompiledHopKey>> removedSupport(Object delta) throws Exception {
		Method method = delta.getClass().getDeclaredMethod("removed");
		method.setAccessible(true);
		return (Map<CompiledHopKey,Set<CompiledHopKey>>)method.invoke(delta);
	}

	private static boolean supportChanged(Object delta) throws Exception {
		Method method = delta.getClass().getDeclaredMethod("changed");
		method.setAccessible(true);
		return (boolean)method.invoke(delta);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> changedCandidateOccurrences(List<CandidateRuleFact> before,
		List<CandidateRuleFact> after) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"changedCandidateOccurrences", List.class, List.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, before, after);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,Set<CompiledHopKey>> fullSupport(List<CandidateRuleFact> facts) throws Exception {
		Map<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>();
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"addDirectSupportDependencies", Map.class, List.class);
		method.setAccessible(true);
		method.invoke(null, result, facts);
		return result;
	}

	private static Map<CompiledHopKey,Set<CompiledHopKey>> unionSupport(
		Map<CompiledHopKey,Set<CompiledHopKey>> left, Map<CompiledHopKey,Set<CompiledHopKey>> right) {
		Map<CompiledHopKey,Set<CompiledHopKey>> union = new IdentityHashMap<>();
		for(Map<CompiledHopKey,Set<CompiledHopKey>> part : List.of(left, right))
			for(var entry : part.entrySet())
				union.computeIfAbsent(entry.getKey(), ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
					.addAll(entry.getValue());
		return union;
	}

	private static boolean sameAdjacency(Map<CompiledHopKey,Set<CompiledHopKey>> left,
		Map<CompiledHopKey,Set<CompiledHopKey>> right) {
		return left.size() == right.size() && left.entrySet().stream()
			.allMatch(entry -> entry.getValue().equals(right.get(entry.getKey())));
	}

	private static void assertAdjacency(Map<CompiledHopKey,Set<CompiledHopKey>> expected,
		Map<CompiledHopKey,Set<CompiledHopKey>> actual) {
		Assert.assertTrue("identity adjacency differs", sameAdjacency(expected, actual));
	}

	private static CandidateRuleFact localFact(Node owner) {
		return supportFact(owner, owner);
	}

	private static CandidateRuleFact excludedFact(Node owner) {
		CandidateRuleFact available = localFact(owner);
		return new CandidateRuleFact(available.key(), CandidateEvaluationStatus.PRIVACY_EXCLUDED,
			available.capability(), available.shapeProof(), available.profile(), List.of(),
			"PRIVATE_AGGREGATE");
	}

	private static CandidateRuleFact directFact(Node owner, CandidateEvaluationStatus status,
		boolean presentInput) {
		CandidateRuleFact base = localFact(owner);
		CandidateInputState input = presentInput
			? CandidateInputState.present(FType.FULL) : CandidateInputState.absentLocal();
		return new CandidateRuleFact(new CandidateRuleKey(owner.key(), List.of(input)), status,
			base.capability(), base.shapeProof(), base.profile(),
			status == CandidateEvaluationStatus.AVAILABLE ? base.allowedEmissionFacts() : List.of(),
			status == CandidateEvaluationStatus.AVAILABLE ? "" : "PRIVATE_AGGREGATE");
	}

	private static boolean directBindingEligible(CandidateRuleFact fact,
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> origins) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directBindingEligible", CandidateRuleFact.class, Map.class);
		method.setAccessible(true);
		return (boolean)method.invoke(null, fact, origins);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> noEligibleDirectBindingOwners(
		List<CompiledHopKey> owners, List<CandidateRuleFact> facts,
		java.util.BitSet directBindingSlots) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"noEligibleDirectBindingOwners", List.class, List.class, java.util.BitSet.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, owners, facts, directBindingSlots);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> postPhysicalDirty(List<Node> beforeNodes,
		List<CandidateRuleFact> beforeFacts, List<CompiledInputEdgeFact> beforeEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> beforeReaching,
		List<Node> afterNodes, List<CandidateRuleFact> afterFacts,
		List<CompiledInputEdgeFact> afterEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> afterReaching,
		Set<CompiledHopKey> changedLoopSeeds) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"initialPostPhysicalDirectDirty", List.class, List.class, List.class, Map.class,
			List.class, List.class, List.class, Map.class, Set.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null,
			beforeNodes, beforeFacts, beforeEdges, beforeReaching,
			afterNodes, afterFacts, afterEdges, afterReaching, changedLoopSeeds);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> outerDirty(List<Node> beforeNodes,
		List<CandidateRuleFact> beforeFacts, List<CompiledInputEdgeFact> beforeEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> beforeReaching,
		List<Node> afterNodes, List<CandidateRuleFact> afterFacts,
		List<CompiledInputEdgeFact> afterEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> afterReaching,
		Set<CompiledHopKey> changedLoopSeeds) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"initialOuterDirectDirty", List.class, List.class, List.class, Map.class,
			List.class, List.class, List.class, Map.class, Set.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null,
			beforeNodes, beforeFacts, beforeEdges, beforeReaching,
			afterNodes, afterFacts, afterEdges, afterReaching, changedLoopSeeds);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> outerDirty(List<Node> beforeNodes,
		List<CandidateRuleFact> beforeFacts, List<CompiledInputEdgeFact> beforeEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> beforeReaching,
		List<Node> afterNodes, List<CandidateRuleFact> afterFacts,
		List<CompiledInputEdgeFact> afterEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> afterReaching,
		Set<CompiledHopKey> changedLoopSeeds, SearchSpaceMetrics metrics) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"initialOuterDirectDirty", List.class, List.class, List.class, Map.class,
			List.class, List.class, List.class, Map.class, Set.class, SearchSpaceMetrics.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null,
			beforeNodes, beforeFacts, beforeEdges, beforeReaching,
			afterNodes, afterFacts, afterEdges, afterReaching, changedLoopSeeds, metrics);
	}

	private static long directMetric(SearchSpaceMetrics metrics, String name) {
		return metrics.directBindingSnapshot().entrySet().stream()
			.filter(entry -> entry.getKey().name().equals(name))
			.mapToLong(Map.Entry::getValue).findFirst().orElse(0);
	}

	private static CandidateRuleFact derivedFact(Node producer, Node anchorOwner) {
		PlacementState source = new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
		PlacementState target = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		PlacementEmissionState emission = new PlacementEmissionState(target, true);
		CandidateRuleKey rule = new CandidateRuleKey(producer.key(), List.of());
		DurableAnchorKey anchor = new DurableAnchorKey("derived-anchor", FType.ROW,
			List.of(new AnchorPartition("worker:9010", List.of(0L, 0L), List.of(8L, 3L))));
		DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
			producer.key(), producer.valueVersion(), rule, source, target, anchor,
			anchorOwner.key(), FType.ROW, FType.ROW, REGION.normalizedSignature());
		CandidateEmissionFact output = new CandidateEmissionFact(emission, FType.ROW, action,
			List.of(CandidateEmissionRealization.durable(emission, anchor, List.of(), List.of())));
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "derived", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "derived", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(output), "");
	}

	private static CandidateRuleFact supportFact(Node source, Node owner) {
		PlacementEmissionState emission = new PlacementEmissionState(LOCAL, false);
		CandidateRuleKey sourceRule = new CandidateRuleKey(source.key(), List.of());
		CandidateRealizationReference sourceRef = CandidateRealizationReference.of(sourceRule,
			CandidateEmissionRealization.local(emission));
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(emission,
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, sourceRef)));
		CandidateEmissionFact output = new CandidateEmissionFact(emission, null, null,
			List.of(realization));
		return new CandidateRuleFact(new CandidateRuleKey(owner.key(), List.of()),
			CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), List.of(output), "");
	}

	private static Node node(String name) {
		return node(name, new ValueVersionKey(FINGERPRINT, name, REGION, 0,
			VersionKind.ORDINARY, List.of()));
	}

	private static Node node(String name, ValueVersionKey version) {
		CompiledHopKey key = new CompiledHopKey(FINGERPRINT, "main", "main", "compiled",
			REGION, name, name);
		return new Node(key, NodeKind.OPERATION, version, true,
			List.of(LOCAL), List.of(), List.of());
	}

	private static CompiledInputEdgeFact edge(Node source, Node consumer) {
		return new CompiledInputEdgeFact(source.key(), consumer.key(), 0);
	}

	private static Map<CompiledHopKey,Set<CompiledHopKey>> dependencies(Node... endpoints) {
		Map<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>();
		for(int index = 0; index < endpoints.length; index += 2)
			result.computeIfAbsent(endpoints[index].key(),
				ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
				.add(endpoints[index + 1].key());
		return result;
	}

	private static Object subscriptions(Map<CompiledHopKey,Set<CompiledHopKey>> footprints)
		throws Exception {
		Class<?> type = Class.forName(
			PlacementRelationClosure.class.getName() + "$DirectQuerySubscriptions");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object subscriptions = constructor.newInstance();
		Method replace = type.getDeclaredMethod("replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
		owners.addAll(footprints.keySet());
		replace.invoke(subscriptions, owners, footprints, Set.of());
		return subscriptions;
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> required(Set<CompiledHopKey> changed,
		Map<CompiledHopKey,Set<CompiledHopKey>> potential,
		Map<CompiledHopKey,Set<CompiledHopKey>> support,
		Map<CompiledHopKey,Set<CompiledHopKey>> removed,
		Map<CompiledHopKey,List<CompiledHopKey>> aliases, Object subscriptions) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"requiredDirectClosureOccurrences", Set.class, Map.class, Map.class, Map.class,
			Map.class, subscriptions.getClass());
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null,
			changed, potential, support, removed, aliases, subscriptions);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> required(Set<CompiledHopKey> changed,
		Map<CompiledHopKey,Set<CompiledHopKey>> potential,
		Map<CompiledHopKey,Set<CompiledHopKey>> support,
		Map<CompiledHopKey,Set<CompiledHopKey>> removed,
		Map<CompiledHopKey,List<CompiledHopKey>> aliases, Object subscriptions,
		SearchSpaceMetrics metrics, Set<CompiledHopKey> pending) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"requiredDirectClosureOccurrences", Set.class, Map.class, Map.class, Map.class,
			Map.class, subscriptions.getClass(), SearchSpaceMetrics.class, Set.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null,
			changed, potential, support, removed, aliases, subscriptions, metrics, pending);
	}

	private static Set<CompiledHopKey> keys(Node... nodes) {
		Set<CompiledHopKey> keys = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : nodes)
			keys.add(node.key());
		return keys;
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> affected(Set<CompiledHopKey> changed, List<Node> nodes,
		List<CompiledInputEdgeFact> edges, Map<CompiledHopKey,List<CompiledHopKey>> reaching)
		throws Exception {
		return affected(changed, nodes, edges, reaching, List.of(), List.of());
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> affected(Set<CompiledHopKey> changed, List<Node> nodes,
		List<CompiledInputEdgeFact> edges, Map<CompiledHopKey,List<CompiledHopKey>> reaching,
		List<CandidateRuleFact> before, List<CandidateRuleFact> after) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"affectedDirectClosureOccurrences", Set.class, List.class, List.class, Map.class,
			List.class, List.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, changed, nodes, edges, reaching,
			before, after);
	}

	private static void assertSameKeys(Set<CompiledHopKey> expected, Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected, actual);
	}

	private static final class CountingFactList extends AbstractList<CandidateRuleFact> {
		private final List<CandidateRuleFact> facts;
		private int accesses;

		private CountingFactList(List<CandidateRuleFact> facts) {
			this.facts = List.copyOf(facts);
		}

		@Override
		public CandidateRuleFact get(int index) {
			accesses++;
			return facts.get(index);
		}

		@Override
		public int size() {
			return facts.size();
		}

		private int accesses() {
			return accesses;
		}
	}
}
