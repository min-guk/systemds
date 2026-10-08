/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DirectSourceSeedProjectionTest {
	private static final String FINGERPRINT = "direct-seed-projection";
	private static final ControlRegionKey REGION = new ControlRegionKey(FINGERPRINT, "main",
		List.of("main"), "main", "compiled");
	private static final PlacementState ROW_STATE =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState ROW_EMISSION =
		new PlacementEmissionState(ROW_STATE, false);

	@Test
	public void repeatedLookupsReuseOneExactClauseProjection() throws Exception {
		CompiledHopKey owner = key("many-clauses");
		DurableAnchorKey pool = pool("pool", FType.ROW, "worker-a", 8);
		CandidateRuleFact fact = nativeFact(owner, "many", pool, 200);
		Object index = sourceIndex(List.of(fact));
		NativePlacementContinuity continuity = continuity(List.of(fact));
		Object projection = projection(Map.of(owner, node(owner, List.of())), index, continuity);

		for(int lookup = 0; lookup < 100; lookup++)
			Assert.assertEquals(List.of(pool), seeds(projection, owner, FType.ROW));
		Assert.assertEquals(200, clauseVisits(projection));

		long coldVisits = 0;
		for(int lookup = 0; lookup < 100; lookup++) {
			Object cold = projection(Map.of(owner, node(owner, List.of())), index, continuity);
			Assert.assertEquals(List.of(pool), seeds(cold, owner, FType.ROW));
			coldVisits += clauseVisits(cold);
		}
		Assert.assertEquals("cache-disabled control rescans every clause", 20_000, coldVisits);
	}

	@Test
	public void projectionMatchesLegacyOrderGlobalLastWinsAndFirstEqualRepresentative()
		throws Exception {
		CompiledHopKey owner = key("duplicate-owner");
		CompiledHopKey foreignEqualOwner = key("duplicate-owner");
		Assert.assertNotSame(owner, foreignEqualOwner);
		Assert.assertEquals(owner, foreignEqualOwner);
		DurableAnchorKey stable = pool("stable", FType.ROW, "stable-worker", 6);
		DurableAnchorKey loser = pool("loser", FType.ROW, "old-worker", 7);
		DurableAnchorKey winner = pool("winner", FType.ROW, "new-worker", 8);
		DurableAnchorKey literalFirst = copy(winner);
		Assert.assertNotSame(literalFirst, winner);
		Assert.assertEquals(literalFirst, winner);
		CandidateRuleFact stableFact = nativeFact(owner, "stable-reference", stable, 1);
		CandidateRuleFact first = nativeFact(owner, "same-reference", loser, 1);
		CandidateRuleFact second = nativeFact(foreignEqualOwner, "same-reference", winner, 1);
		List<CandidateRuleFact> facts = List.of(stableFact, first, second);
		Object index = sourceIndex(facts);
		NativePlacementContinuity continuity = continuity(facts);
		Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		nodes.put(owner, node(owner, List.of(literalFirst)));
		nodes.put(foreignEqualOwner, node(foreignEqualOwner, List.of()));

		List<DurableAnchorKey> expected = legacySeeds(nodes, index, continuity, owner, FType.ROW);
		Object projection = projection(nodes, index, continuity);
		List<DurableAnchorKey> actual = seeds(projection, owner, FType.ROW);
		Assert.assertEquals(expected, actual);
		Assert.assertEquals(List.of(literalFirst, stable), actual);
		Assert.assertSame("literal anchor remains the first equal representative", literalFirst, actual.get(0));
		Assert.assertEquals(expected.stream().distinct().sorted().toList(),
			actual.stream().distinct().sorted().toList());
		Assert.assertFalse(actual.contains(loser));
		Assert.assertTrue(seeds(projection, owner, FType.COL).isEmpty());
		Assert.assertTrue(seeds(projection, null, FType.ROW).isEmpty());
		Assert.assertTrue(seeds(projection, key("absent"), FType.ROW).isEmpty());
	}

	@Test
	public void freshBinderProjectionObservesDeepValueMapWithdrawal() throws Exception {
		CompiledHopKey leafOwner = key("leaf");
		CompiledHopKey mapOwner = key("value-map");
		DurableAnchorKey pool = pool("leaf-pool", FType.ROW, "worker", 8);
		CandidateRuleFact leaf = nativeFact(leafOwner, "leaf", pool, 1);
		CandidateRealizationReference leafReference = reference(leaf);
		CandidateRuleFact valueMap = valueMapFact(mapOwner, leafReference);
		List<CandidateRuleFact> before = List.of(leaf, valueMap);
		Object index = sourceIndex(before);
		Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		nodes.put(leafOwner, node(leafOwner, List.of()));
		nodes.put(mapOwner, node(mapOwner, List.of()));
		Object first = projection(nodes, index, continuity(before));
		Assert.assertEquals(List.of(pool), seeds(first, mapOwner, FType.ROW));
		Assert.assertTrue("deep VALUE_MAP metadata requires conservative resubscription",
			fixedValueMapConsulted(first, mapOwner, FType.ROW));

		CandidateRuleFact withdrawn = failed(leafOwner);
		List<CandidateRuleFact> after = List.of(withdrawn, valueMap);
		revise(index, after, leafOwner);
		Object fresh = projection(nodes, index, continuity(after));
		Assert.assertTrue("a new binder call must not retain the prior VALUE_MAP leaf pool",
			seeds(fresh, mapOwner, FType.ROW).isEmpty());
	}

	@Test
	public void incompleteDeepSeedSubscriptionKeepsTransitiveConsumerInDirtyCone()
		throws Exception {
		CompiledHopKey leaf = key("deep-leaf");
		CompiledHopKey valueMap = key("deep-map");
		CompiledHopKey consumer = key("deep-consumer");
		Constructor<?> subscriptionsConstructor =
			nested("DirectQuerySubscriptions").getDeclaredConstructor();
		subscriptionsConstructor.setAccessible(true);
		Object subscriptions = subscriptionsConstructor.newInstance();
		Method replace = nested("DirectQuerySubscriptions").getDeclaredMethod(
			"replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		Set<CompiledHopKey> incomplete = identitySet();
		incomplete.add(consumer);
		replace.invoke(subscriptions, identitySet(consumer), Map.of(consumer, identitySet(consumer)),
			incomplete);

		Map<CompiledHopKey,Set<CompiledHopKey>> potential = new IdentityHashMap<>();
		potential.put(leaf, identitySet(valueMap));
		potential.put(valueMap, identitySet(consumer));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Method required = PlacementRelationClosure.class.getDeclaredMethod(
			"requiredDirectClosureOccurrences", Set.class, Map.class, Map.class, Map.class,
			Map.class, nested("DirectQuerySubscriptions"), SearchSpaceMetrics.class);
		required.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> affected = (Set<CompiledHopKey>)required.invoke(null,
			identitySet(leaf), potential, Map.of(), Map.of(), Map.of(), subscriptions, metrics);
		Assert.assertTrue("deep leaf change must recompute an incompletely subscribed consumer",
			affected.contains(consumer));
		Assert.assertEquals(1, directMetric(metrics, "INVALIDATION_SELF_INCIDENCES"));
		Assert.assertEquals(1, directMetric(metrics, "INVALIDATION_IMMEDIATE_INCIDENCES"));
		Assert.assertEquals(0, directMetric(metrics, "INVALIDATION_ALIAS_INCIDENCES"));
		Assert.assertEquals(0, directMetric(metrics, "INVALIDATION_SUBSCRIBER_INCIDENCES"));
		Assert.assertEquals(3, directMetric(metrics,
			"INVALIDATION_INCOMPLETE_TRANSITIVE_INCIDENCES"));
		Assert.assertEquals(2, directMetric(metrics, "INVALIDATION_UNIQUE_EXTRA_OWNERS"));
		Assert.assertEquals(1, directMetric(metrics,
			"INVALIDATION_INCOMPLETE_ONLY_EXTRA_OWNERS"));
	}

	@Test
	public void directBinderMarksDeepValueMapSeedFootprintIncomplete() throws Exception {
		CompiledHopKey leafOwner = key("binder-leaf");
		CompiledHopKey mapOwner = key("binder-map");
		CompiledHopKey consumerOwner = key("binder-consumer");
		CandidateRuleFact leaf = nativeFact(leafOwner, "binder-leaf", pool(
			"binder-pool", FType.ROW, "worker", 8), 1);
		CandidateRuleFact valueMap = valueMapFact(mapOwner, reference(leaf));
		CandidateRuleFact consumer = fact(new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW))),
			CandidateEmissionRealization.nativeLineage(ROW_EMISSION, "binder-consumer",
				List.of(), List.of()));
		List<CandidateRuleFact> allFacts = List.of(leaf, valueMap, consumer);
		List<Node> nodes = List.of(node(leafOwner, List.of()), node(mapOwner, List.of()),
			node(consumerOwner, List.of()));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(mapOwner, consumerOwner, 0));
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, allFacts, nodes, edges, allFacts, Map.of(), Map.of());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, metrics, true,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", nested("DirectBindingIndex"),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Object result = bind.invoke(closure, index, List.of(consumer), new IdentityHashMap<>(),
			new IdentityHashMap<>(),
			continuity(allFacts), identitySet(consumerOwner));
		Method incomplete = result.getClass().getDeclaredMethod("incompleteDependencyOccurrences");
		incomplete.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> incompleteOwners = (Set<CompiledHopKey>)incomplete.invoke(result);
		Assert.assertTrue("binder must not publish a complete subscription for a deep map seed",
			incompleteOwners.contains(consumerOwner));
		Assert.assertEquals(1, directMetric(metrics, "INCOMPLETE_SEED_VALUE_MAP_INCIDENCES"));
		Assert.assertEquals(0, directMetric(metrics, "INCOMPLETE_PROOF_METADATA_INCIDENCES"));
		Assert.assertEquals(1, directMetric(metrics, "INCOMPLETE_UNIQUE_OWNERS"));
	}

	@Test
	public void nativeQueryThroughValueMapKeepsHiddenLeafOnFullCone() throws Exception {
		CompiledHopKey leafOwner = key("query-leaf");
		CompiledHopKey mapOwner = key("query-map");
		CompiledHopKey bridgeOwner = key("query-native-bridge");
		CompiledHopKey consumerOwner = key("query-consumer");
		DurableAnchorKey pool = pool("query-pool", FType.ROW, "worker", 8);
		CandidateRuleFact leaf = nativeFact(leafOwner, "query-leaf", pool, 1);
		CandidateRuleFact valueMap = valueMapFact(mapOwner, reference(leaf));
		CandidateEmissionRealization bridgeRealization = CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, "query-bridge", List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, reference(valueMap))));
		CandidateRuleFact bridge = fact(new CandidateRuleKey(bridgeOwner,
			List.of(CandidateInputState.present(FType.ROW))), bridgeRealization);
		CandidateRuleFact consumer = fact(new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW))),
			CandidateEmissionRealization.nativeLineage(ROW_EMISSION, "query-consumer",
				List.of(), List.of()));
		List<CandidateRuleFact> allFacts = List.of(leaf, valueMap, bridge, consumer);
		List<Node> nodes = List.of(node(leafOwner, List.of()), node(mapOwner, List.of()),
			node(bridgeOwner, List.of(pool)), node(consumerOwner, List.of()));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(leafOwner, mapOwner, 0),
			new CompiledInputEdgeFact(mapOwner, bridgeOwner, 0),
			new CompiledInputEdgeFact(bridgeOwner, consumerOwner, 0));
		org.apache.sysds.hops.DataOp leafHop = new org.apache.sysds.hops.DataOp("query-leaf",
			org.apache.sysds.common.Types.DataType.MATRIX, org.apache.sysds.common.Types.ValueType.FP64,
			org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD, "query-leaf", 8, 2, 16, 1000);
		org.apache.sysds.hops.UnaryOp mapHop = new org.apache.sysds.hops.UnaryOp("query-map",
			org.apache.sysds.common.Types.DataType.MATRIX, org.apache.sysds.common.Types.ValueType.FP64,
			org.apache.sysds.common.Types.OpOp1.LOG, leafHop);
		org.apache.sysds.hops.UnaryOp bridgeHop = new org.apache.sysds.hops.UnaryOp("query-bridge",
			org.apache.sysds.common.Types.DataType.MATRIX, org.apache.sysds.common.Types.ValueType.FP64,
			org.apache.sysds.common.Types.OpOp1.LOG, mapHop);
		org.apache.sysds.hops.UnaryOp consumerHop = new org.apache.sysds.hops.UnaryOp("query-consumer",
			org.apache.sysds.common.Types.DataType.MATRIX, org.apache.sysds.common.Types.ValueType.FP64,
			org.apache.sysds.common.Types.OpOp1.LOG, bridgeHop);
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> origins = new IdentityHashMap<>();
		origins.put(leafOwner, leafHop);
		origins.put(mapOwner, mapHop);
		origins.put(bridgeOwner, bridgeHop);
		origins.put(consumerOwner, consumerHop);
		Map<org.apache.sysds.hops.Hop,PlacementAnalysis.NodeShapeFact> shapes = new IdentityHashMap<>();
		for(org.apache.sysds.hops.Hop hop : origins.values())
			shapes.put(hop, new PlacementAnalysis.NodeShapeFact(
				org.apache.sysds.common.Types.DataType.MATRIX, 8, 2));
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, allFacts, nodes, edges, allFacts, origins, shapes);
		Object sourceIndex = sourceIndex(allFacts);
		Object projection = projection(nodes.stream().collect(java.util.stream.Collectors.toMap(
			Node::key, node -> node, (left, right) -> right, IdentityHashMap::new)),
			sourceIndex, continuity(allFacts));
		Assert.assertFalse("the immediate native source does not itself resolve a VALUE_MAP",
			fixedValueMapConsulted(projection, bridgeOwner, FType.ROW));

		NativePlacementContinuity resolver = new NativePlacementContinuity(
			nodes.stream().collect(java.util.stream.Collectors.toMap(
				Node::key, node -> node, (left, right) -> right, IdentityHashMap::new)),
			origins, allFacts, edges, Map.of());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, metrics, true,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", nested("DirectBindingIndex"),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Object result = bind.invoke(closure, index, List.of(consumer), origins, shapes,
			resolver, identitySet(consumerOwner));
		Method dependencies = result.getClass().getDeclaredMethod("dependencyOccurrences");
		dependencies.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,Set<CompiledHopKey>> dependencyReads =
			(Map<CompiledHopKey,Set<CompiledHopKey>>)dependencies.invoke(result);
		Set<CompiledHopKey> queryReads = dependencyReads.getOrDefault(consumerOwner, Set.of());
		Assert.assertTrue("the current Native query must visit unselected VALUE_MAP V",
			queryReads.contains(mapOwner));
		Method incomplete = result.getClass().getDeclaredMethod("incompleteDependencyOccurrences");
		incomplete.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> incompleteOwners = (Set<CompiledHopKey>)incomplete.invoke(result);
		Assert.assertTrue("R must stay on the full cone because V hides S metadata",
			incompleteOwners.contains(consumerOwner));
		Assert.assertEquals(0, directMetric(metrics, "INCOMPLETE_SEED_VALUE_MAP_INCIDENCES"));
		Assert.assertEquals(1, directMetric(metrics, "INCOMPLETE_PROOF_METADATA_INCIDENCES"));
		Assert.assertEquals(1, directMetric(metrics, "INCOMPLETE_UNIQUE_OWNERS"));

		Constructor<?> subscriptionsConstructor = nested("DirectQuerySubscriptions").getDeclaredConstructor();
		subscriptionsConstructor.setAccessible(true);
		Object subscriptions = subscriptionsConstructor.newInstance();
		Method replace = nested("DirectQuerySubscriptions").getDeclaredMethod(
			"replace", Set.class, Map.class, Set.class);
		replace.setAccessible(true);
		replace.invoke(subscriptions, identitySet(consumerOwner), dependencyReads, incompleteOwners);
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = new IdentityHashMap<>();
		potential.put(leafOwner, identitySet(mapOwner));
		potential.put(mapOwner, identitySet(bridgeOwner));
		potential.put(bridgeOwner, identitySet(consumerOwner));
		Method required = PlacementRelationClosure.class.getDeclaredMethod(
			"requiredDirectClosureOccurrences", Set.class, Map.class, Map.class, Map.class,
			Map.class, nested("DirectQuerySubscriptions"));
		required.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> affected = (Set<CompiledHopKey>)required.invoke(null,
			identitySet(leafOwner), potential, Map.of(), Map.of(), Map.of(), subscriptions);
		Assert.assertTrue("S withdrawal must recompute R through B and V", affected.contains(consumerOwner));
	}

	private static List<DurableAnchorKey> legacySeeds(Map<CompiledHopKey,Node> nodes,
		Object index, NativePlacementContinuity continuity, CompiledHopKey sourceKey,
		FType inputType) throws Exception {
		Node source = nodes.get(sourceKey);
		if(source == null)
			return List.of();
		LinkedHashSet<DurableAnchorKey> result = new LinkedHashSet<>();
		source.anchors().stream().filter(anchor -> anchor.fType() == inputType).forEach(result::add);
		for(CandidateRealizationReference reference : nativeByParent(index, sourceKey)) {
			CandidateEmissionRealization realization = nativeRealization(index, reference);
			if(reference.realization().emissionState().placementState().fType() != inputType
				|| realization == null || !executable(index, reference))
				continue;
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				DurableAnchorKey pool = realization.nativeWorkerPoolResidencyForOwnedClause(clause);
				if(pool != null && pool.fType() == inputType)
					result.add(pool);
			}
			NativePlacementContinuity.FixedValueMapPool fixed = continuity.fixedValueMapPool(reference);
			if(fixed != null && fixed.pool().fType() == inputType)
				result.add(fixed.pool());
		}
		return List.copyOf(result);
	}

	private static CandidateRuleFact nativeFact(CompiledHopKey owner, String lineage,
		DurableAnchorKey pool, int clauses) {
		List<CandidateRealizationSupportClause> support = new ArrayList<>();
		for(int index = 0; index < clauses; index++)
			support.add(new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, owner, "proof-" + index)), List.of(), pool));
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.nativeLineage(ROW_EMISSION, lineage), support);
		return fact(new CandidateRuleKey(owner, List.of()), realization);
	}

	private static CandidateRuleFact valueMapFact(CompiledHopKey owner,
		CandidateRealizationReference leaf) {
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, leaf)));
		CandidateEmissionRealization realization = CandidateEmissionRealization.valueMap(
			ROW_EMISSION, "value-map", List.of(clause));
		return fact(new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.ROW))), realization);
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(rule.orderedInputs().stream().map(CandidateInputState::fType).toList(), ""),
			List.of(new CandidateEmissionFact(ROW_EMISSION, FType.ROW, null, List.of(realization))), "");
	}

	private static CandidateRuleFact failed(CompiledHopKey owner) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.RULE_ERROR, null,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), "failed"), List.of(), "failed");
	}

	private static NativePlacementContinuity continuity(List<CandidateRuleFact> facts) {
		return new NativePlacementContinuity(Map.of(), Map.of(), facts, List.of(), Map.of());
	}

	private static Node node(CompiledHopKey key, List<DurableAnchorKey> anchors) {
		return new Node(key, NodeKind.OPERATION,
			new ValueVersionKey(FINGERPRINT, key.canonicalSourceOrigin(), REGION, 0,
				VersionKind.ORDINARY, List.of()), true, List.of(ROW_STATE), List.of(), anchors);
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey(FINGERPRINT, "main", "main", "compiled", REGION, name, name);
	}

	private static DurableAnchorKey pool(String id, FType type, String worker, long rows) {
		return new DurableAnchorKey(id, type, List.of(
			new AnchorPartition(worker, List.of(0L, 0L), List.of(rows, 2L))));
	}

	private static DurableAnchorKey copy(DurableAnchorKey pool) {
		return new DurableAnchorKey(pool.placementId(), pool.fType(), pool.partitions());
	}

	private static CandidateRealizationReference reference(CandidateRuleFact fact) {
		return CandidateRealizationReference.of(fact.key(),
			fact.allowedEmissionFacts().get(0).realizations().get(0));
	}

	private static Class<?> nested(String name) {
		for(Class<?> type : PlacementRelationClosure.class.getDeclaredClasses())
			if(type.getSimpleName().equals(name))
				return type;
		throw new AssertionError(name + " was not found");
	}

	private static Object sourceIndex(List<CandidateRuleFact> facts) throws Exception {
		Constructor<?> constructor = nested("DirectSourceIndex").getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	private static Object projection(Map<CompiledHopKey,Node> nodes, Object index,
		NativePlacementContinuity continuity) throws Exception {
		Constructor<?> constructor = nested("DirectSourceSeedProjection").getDeclaredConstructor(
			Map.class, nested("DirectSourceIndex"), NativePlacementContinuity.class);
		constructor.setAccessible(true);
		return constructor.newInstance(nodes, index, continuity);
	}

	@SuppressWarnings("unchecked")
	private static List<DurableAnchorKey> seeds(Object projection, CompiledHopKey owner, FType type)
		throws Exception {
		Method method = nested("DirectSourceSeedProjection").getDeclaredMethod(
			"seeds", CompiledHopKey.class, FType.class);
		method.setAccessible(true);
		return (List<DurableAnchorKey>) method.invoke(projection, owner, type);
	}

	private static long clauseVisits(Object projection) throws Exception {
		Method method = nested("DirectSourceSeedProjection").getDeclaredMethod("clauseVisits");
		method.setAccessible(true);
		return (long) method.invoke(projection);
	}

	private static boolean fixedValueMapConsulted(Object projection, CompiledHopKey owner,
		FType type) throws Exception {
		Method method = nested("DirectSourceSeedProjection").getDeclaredMethod(
			"fixedValueMapConsulted", CompiledHopKey.class, FType.class);
		method.setAccessible(true);
		return (boolean)method.invoke(projection, owner, type);
	}

	private static long directMetric(SearchSpaceMetrics metrics, String name) {
		return metrics.directBindingSnapshot().entrySet().stream()
			.filter(entry -> entry.getKey().name().equals(name))
			.mapToLong(Map.Entry::getValue).findFirst().orElse(0);
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey... values) {
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, values);
		return result;
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> nativeByParent(Object index,
		CompiledHopKey owner) throws Exception {
		Method method = nested("DirectSourceIndex").getDeclaredMethod(
			"nativeByParent", CompiledHopKey.class);
		method.setAccessible(true);
		return (List<CandidateRealizationReference>) method.invoke(index, owner);
	}

	private static CandidateEmissionRealization nativeRealization(Object index,
		CandidateRealizationReference reference) throws Exception {
		Method method = nested("DirectSourceIndex").getDeclaredMethod(
			"nativeRealization", CandidateRealizationReference.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization) method.invoke(index, reference);
	}

	private static boolean executable(Object index, CandidateRealizationReference reference)
		throws Exception {
		Method method = nested("DirectSourceIndex").getDeclaredMethod(
			"executable", CandidateRealizationReference.class);
		method.setAccessible(true);
		return (boolean) method.invoke(index, reference);
	}

	private static void revise(Object index, List<CandidateRuleFact> facts,
		CompiledHopKey owner) throws Exception {
		Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		changed.add(owner);
		Method method = nested("DirectSourceIndex").getDeclaredMethod("nextRevision", List.class, Set.class);
		method.setAccessible(true);
		method.invoke(index, facts, changed);
	}
}
