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
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
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
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"readyDirectOwners", PlacementDependencyComponents.class, Set.class);
		method.setAccessible(true);
		Assert.assertEquals(keys(a), method.invoke(null, schedule, keys(a, b)));
		Assert.assertEquals("settled alias must not be reintroduced without an export change",
			keys(b), method.invoke(null, schedule, keys(b)));
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
		Method changedMethod = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
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
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod("directComponentSchedule",
			List.class, Map.class, Map.class, List.class);
		method.setAccessible(true);
		return (PlacementDependencyComponents)method.invoke(null, nodes.stream().map(Node::key).toList(),
			Map.of(), support, List.of());
	}

	private static Object supportIndex(List<CandidateRuleFact> facts) throws Exception {
		Class<?> type = Class.forName(NeutralPlacementGraphBuilder.class.getName() + "$DirectSupportIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, List.class);
		constructor.setAccessible(true);
		Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		for(int i = 0; i < facts.size(); i++)
			slots.computeIfAbsent(facts.get(i).key().parentOccurrence(), ignored -> new ArrayList<>()).add(i);
		return constructor.newInstance(slots, facts);
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
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"changedCandidateOccurrences", List.class, List.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, before, after);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,Set<CompiledHopKey>> fullSupport(List<CandidateRuleFact> facts) throws Exception {
		Map<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>();
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
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

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> postPhysicalDirty(List<Node> beforeNodes,
		List<CandidateRuleFact> beforeFacts, List<CompiledInputEdgeFact> beforeEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> beforeReaching,
		List<Node> afterNodes, List<CandidateRuleFact> afterFacts,
		List<CompiledInputEdgeFact> afterEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> afterReaching,
		Set<CompiledHopKey> changedLoopSeeds) throws Exception {
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
			"initialPostPhysicalDirectDirty", List.class, List.class, List.class, Map.class,
			List.class, List.class, List.class, Map.class, Set.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null,
			beforeNodes, beforeFacts, beforeEdges, beforeReaching,
			afterNodes, afterFacts, afterEdges, afterReaching, changedLoopSeeds);
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
		Method method = NeutralPlacementGraphBuilder.class.getDeclaredMethod(
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
