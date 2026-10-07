/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact immutable proof indexes may outlive an owner query, never its mutable query state. */
public class MaterializationProofInventoryTest {
	private static final PlacementState FULL = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.FED, FederatedOutput.LOUT, FType.FULL, false);

	@Test
	public void sharedInventoryMatchesColdOwnersInBothOrdersWithoutQueryHistory() throws Exception {
		Fixture f = new Fixture();
		for(List<Integer> order : List.of(List.of(1, 2), List.of(2, 1))) {
			Object inventory = f.inventory(f.nodes, f.edges);
			Object previousResolver = null;
			for(int index : order) {
				Object expected = f.cold(f.nodes.get(index), f.facts.get(index), f.nodes, f.edges);
				Object actual = shared(f.nodes.get(index), f.facts.get(index), inventory);
				Assert.assertEquals("complete nodes/facts/action/source order must equal a cold query", expected, actual);
				CandidateEmissionFact derived = outputFacts(actual).stream()
					.flatMap(fact -> fact.allowedEmissionFacts().stream())
					.filter(emission -> emission.emissionState().derivedFedFout()).findFirst().orElseThrow();
				Assert.assertSame(f.nodes.get(index).key(), derived.derivedFoutAction().producer());
				Assert.assertSame(f.nodes.get(0).key(), derived.derivedFoutAction().durableAnchorOwner());
				Object resolver = field(inventory, "resolver");
				Object nativeQuery = field(resolver, "nativeContinuity");
				Assert.assertNull("ordinary materialization proof must not build native continuity",
					nativeQuery);
				Assert.assertTrue(((Map<?,?>)field(resolver, "active")).isEmpty());
				Assert.assertFalse(((Map<?,?>)field(resolver, "memo")).isEmpty());
				if(previousResolver != null)
					Assert.assertSame(previousResolver, resolver);
				previousResolver = resolver;
			}
		}
	}

	@Test
	public void edgeAndResolverQueriesAreOrderIndependentAndRevisionScoped() throws Exception {
		Fixture f = new Fixture();
		Node source = f.nodes.get(0);
		List<Node> changedNodes = new ArrayList<>(f.nodes);
		DurableAnchorKey changedAnchor = new DurableAnchorKey("changed-pool", FType.FULL,
			List.of(new AnchorPartition("worker-b", List.of(0L, 0L), List.of(4L, 2L))));
		changedNodes.set(0, new Node(source.key(), source.kind(), source.valueVersion(),
			source.emittedWork(), source.legalAlternatives(), source.exclusions(), List.of(changedAnchor)));
		Object original = f.cold(f.nodes.get(1), f.facts.get(1), f.nodes, f.edges);
		Object changed = f.cold(f.nodes.get(1), f.facts.get(1), changedNodes, f.edges);
		Assert.assertNotEquals("fixture must distinguish the committed worker-pool revision", original, changed);
		Method edge = inventoryType().getDeclaredMethod("inputEdge", CompiledHopKey.class, int.class);
		edge.setAccessible(true);
		for(boolean edgeFirst : List.of(true, false)) {
			Object oldInventory = f.inventory(f.nodes, f.edges);
			Object newInventory = f.inventory(changedNodes, f.edges);
			for(Object inventory : List.of(oldInventory, newInventory)) {
				if(edgeFirst)
					Assert.assertSame(f.edges.get(0), edge.invoke(inventory, f.nodes.get(1).key(), 0));
				Assert.assertEquals(inventory == oldInventory ? original : changed,
					shared(f.nodes.get(1), f.facts.get(1), inventory));
				Assert.assertSame(f.edges.get(0), edge.invoke(inventory, f.nodes.get(1).key(), 0));
			}
			Assert.assertEquals("querying the newer revision cannot change the prior inventory",
				original, shared(f.nodes.get(1), f.facts.get(1), oldInventory));
		}
	}

	@Test
	public void lazyInventoryRetainsImmutableConstructorTimeNodesAndFacts() throws Exception {
		Fixture f = new Fixture();
		Node raw = f.nodes.get(1);
		CandidateRuleFact fact = f.facts.get(1);
		List<Node> expectedNodes = List.copyOf(f.nodes);
		List<CandidateRuleFact> expectedFacts = List.copyOf(f.facts);
		Object expected = f.cold(raw, fact, f.nodes, f.edges);
		Object inventory = f.inventory(f.nodes, f.edges);
		Assert.assertNull(field(inventory, "resolver"));
		f.nodes.clear();
		f.facts.clear();
		Assert.assertEquals(expectedNodes, field(inventory, "nodes"));
		Assert.assertEquals(expectedFacts, field(inventory, "facts"));
		Assert.assertEquals(expected, shared(raw, fact, inventory));
		@SuppressWarnings("unchecked")
		List<Node> retainedNodes = (List<Node>)field(inventory, "nodes");
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> retainedFacts = (List<CandidateRuleFact>)field(inventory, "facts");
		for(int index = 0; index < expectedNodes.size(); index++) {
			Assert.assertSame(expectedNodes.get(index), retainedNodes.get(index));
			Assert.assertSame(expectedFacts.get(index), retainedFacts.get(index));
		}
		Assert.assertThrows(UnsupportedOperationException.class, () -> retainedNodes.add(raw));
		Assert.assertThrows(UnsupportedOperationException.class, () -> retainedFacts.add(fact));
	}

	@Test
	public void firstEligibleOwnerRetainsDuplicateEdgeValidationBoundary() throws Exception {
		Fixture f = new Fixture();
		f.add("scalar", new LiteralOp(1.0), new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false),
			List.of(), List.of(), new NodeShapeFact(DataType.SCALAR, 0, 0));
		List<CompiledInputEdgeFact> duplicates = new ArrayList<>(f.edges);
		duplicates.add(f.edges.get(0));
		Object inventory = f.ownerInventory(f.nodes, f.facts, duplicates);
		inventory = replaceOwner(inventory, 1, f.nodes.get(1).key(), f.nodes.get(1),
			List.of(f.facts.get(1)));
		inventory = replaceOwner(inventory, 1, f.nodes.get(1).key(), f.nodes.get(1),
			List.of(f.facts.get(1)));
		Assert.assertNull("owner deltas must not move duplicate-edge validation earlier",
			field(inventory, "resolver"));
		final Object deltaInventory = inventory;
		Node source = f.nodes.get(0);
		Node transientSource = new Node(source.key(), NodeKind.TRANSIENT_READ, source.valueVersion(),
			true, source.legalAlternatives(), List.of(), source.anchors());
		shared(transientSource, f.facts.get(0), inventory);
		shared(f.nodes.get(3), f.facts.get(3), inventory);
		CandidateRuleFact available = f.facts.get(1);
		CandidateRuleFact excluded = new CandidateRuleFact(available.key(),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, available.capability(), available.shapeProof(),
			available.profile(), List.of(), "PRIVATE_AGGREGATE");
		shared(f.nodes.get(1), excluded, inventory);
		Assert.assertNull("ineligible owners must not eagerly construct/validate the proof graph",
			field(inventory, "resolver"));
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> shared(f.nodes.get(2), f.facts.get(2), deltaInventory));
		Assert.assertTrue(error.getCause() instanceof IllegalStateException);
		Assert.assertTrue(error.getCause().getMessage().contains("Duplicate compiled matrix edge"));
		Assert.assertNull("a partially constructed resolver must not be published", field(inventory, "resolver"));
		Object separateValidInventory = f.inventory(f.nodes, f.edges);
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), separateValidInventory));
	}

	@Test
	public void outputOnlyAnchorCannotGroundItsOwnCfgInput() throws Exception {
		Fixture f = new Fixture();
		Node source = f.nodes.get(0), owner = f.nodes.get(1);
		ValueVersionKey circularValue = new ValueVersionKey(source.valueVersion().programFingerprint(),
			source.valueVersion().lexicalVariable(), source.valueVersion().definingControlRegion(),
			source.valueVersion().definitionOrdinal(), source.valueVersion().versionKind(),
			List.of("cfg-definition:" + owner.valueVersion().cfgReferenceSignature()));
		Node ungroundedRead = new Node(source.key(), NodeKind.TRANSIENT_READ, circularValue,
			true, source.legalAlternatives(), List.of(), List.of());
		List<Node> committed = List.of(ungroundedRead, owner, f.nodes.get(2));
		Node rawAnchoredOutput = new Node(owner.key(), owner.kind(), owner.valueVersion(),
			true, owner.legalAlternatives(), List.of(), List.of(f.anchor));
		List<Node> improperlyInjected = List.of(ungroundedRead, rawAnchoredOutput, f.nodes.get(2));
		Assert.assertTrue("fixture must detect raw output injection into the proof inventory",
			outputFacts(f.cold(rawAnchoredOutput, f.facts.get(1), improperlyInjected, f.edges)).stream()
				.flatMap(fact -> fact.allowedEmissionFacts().stream())
				.anyMatch(emission -> emission.emissionState().derivedFedFout()));
		Object actual = shared(rawAnchoredOutput, f.facts.get(1), f.inventory(committed, f.edges));
		Assert.assertEquals(f.cold(rawAnchoredOutput, f.facts.get(1), committed, f.edges), actual);
		Assert.assertFalse("raw output metadata is not committed proof authority", outputFacts(actual).stream()
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.anyMatch(emission -> emission.emissionState().derivedFedFout()));
	}

	@Test
	public void ownerDeltaSharesUnchangedSlicesAndValidatedStructure() throws Exception {
		Fixture f = new Fixture();
		Object original = f.ownerInventory(f.nodes, f.facts, f.edges);
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), original));
		Object originalResolver = field(original, "resolver");
		Object originalEdges = field(original, "matrixEdgesByConsumer");
		@SuppressWarnings("unchecked")
		List<List<CandidateRuleFact>> originalSlices =
			(List<List<CandidateRuleFact>>)field(original, "factsByOrdinal");
		CandidateRuleFact available = f.facts.get(1);
		CandidateRuleFact withdrawn = new CandidateRuleFact(available.key(),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, available.capability(), available.shapeProof(),
			available.profile(), List.of(), "PRIVATE_AGGREGATE");
		Object revised = replaceOwner(original, 1, f.nodes.get(1).key(), f.nodes.get(1), List.of(withdrawn));
		@SuppressWarnings("unchecked")
		List<List<CandidateRuleFact>> revisedSlices =
			(List<List<CandidateRuleFact>>)field(revised, "factsByOrdinal");
		Assert.assertSame(originalSlices.get(0), revisedSlices.get(0));
		Assert.assertSame(originalSlices.get(2), revisedSlices.get(2));
		Assert.assertNotSame(originalSlices.get(1), revisedSlices.get(1));
		Assert.assertSame("validated invariant edge index is revision-shared", originalEdges,
			field(revised, "matrixEdgesByConsumer"));
		Assert.assertNotSame("query state is revision-local", originalResolver, field(revised, "resolver"));
		Assert.assertNull(field(originalResolver, "nativeContinuity"));
		Object revisedResolver = field(revised, "resolver");
		Assert.assertNull(field(revisedResolver, "nativeContinuity"));
		Object originalNative = invoke(originalResolver, "nativeContinuity");
		Object revisedNative = invoke(revisedResolver, "nativeContinuity");
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,List<CandidateRuleFact>> originalNativeFacts =
			(Map<CompiledHopKey,List<CandidateRuleFact>>)field(originalNative, "candidateFactsByKey");
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,List<CandidateRuleFact>> revisedNativeFacts =
			(Map<CompiledHopKey,List<CandidateRuleFact>>)field(revisedNative, "candidateFactsByKey");
		Assert.assertSame(available, originalNativeFacts.get(available.key().parentOccurrence()).get(0));
		Assert.assertSame("first fallback must observe the current withdrawn owner slice",
			withdrawn, revisedNativeFacts.get(withdrawn.key().parentOccurrence()).get(0));
		Object work = field(revised, "revisionWork");
		Assert.assertEquals(0L, accessor(work, "unchangedOwnerFactsScanned"));
		Assert.assertEquals(1L, accessor(work, "changedOwnerFactsScanned"));
		Assert.assertEquals(0L, accessor(work, "staticIndexesBuilt"));
		Assert.assertEquals("old revision remains immutable", f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), original));
		List<CandidateRuleFact> withdrawnFacts = new ArrayList<>(f.facts);
		withdrawnFacts.set(1, withdrawn);
		Assert.assertEquals("withdrawn owner delta must equal an independent cold inventory",
			f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, withdrawnFacts, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), revised));
		Object restored = replaceOwner(revised, 1, f.nodes.get(1).key(), f.nodes.get(1),
			List.of(available));
		Assert.assertEquals("restoring the owner slice restores exact cold behavior",
			f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), restored));
	}

	@Test
	public void ownerNodeAuthorityDeltaMatchesColdAndKeepsOldRevisionImmutable() throws Exception {
		Fixture f = new Fixture();
		Object original = f.ownerInventory(f.nodes, f.facts, f.edges);
		Object oldResult = shared(f.nodes.get(1), f.facts.get(1), original);
		Node source = f.nodes.get(0);
		DurableAnchorKey changedAnchor = new DurableAnchorKey("changed-owner-delta", FType.FULL,
			List.of(new AnchorPartition("worker-b", List.of(0L, 0L), List.of(4L, 2L))));
		Node replacement = new Node(source.key(), source.kind(), source.valueVersion(), source.emittedWork(),
			source.legalAlternatives(), source.exclusions(), List.of(changedAnchor));
		Object revised = replaceOwner(original, 0, source.key(), replacement, List.of(f.facts.get(0)));
		List<Node> coldNodes = new ArrayList<>(f.nodes);
		coldNodes.set(0, replacement);
		Assert.assertEquals(f.cold(f.nodes.get(1), f.facts.get(1), coldNodes, f.edges),
			shared(f.nodes.get(1), f.facts.get(1), revised));
		Assert.assertEquals(oldResult, shared(f.nodes.get(1), f.facts.get(1), original));
		Assert.assertSame(field(field(original, "resolver"), "matrixEdgesByConsumer"),
			field(field(revised, "resolver"), "matrixEdgesByConsumer"));
		Assert.assertNull("pre-fallback old revision remains lazy",
			field(field(original, "resolver"), "nativeContinuity"));
		Assert.assertNull("pre-fallback new revision remains lazy",
			field(field(revised, "resolver"), "nativeContinuity"));
		Object oldNative = invoke(field(original, "resolver"), "nativeContinuity");
		Object revisedNative = invoke(field(revised, "resolver"), "nativeContinuity");
		Assert.assertNotSame("changed node authority owns a fresh native structural context",
			oldNative, revisedNative);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,Node> oldNativeNodes =
			(Map<CompiledHopKey,Node>)field(oldNative, "nodesByKey");
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,Node> revisedNativeNodes =
			(Map<CompiledHopKey,Node>)field(revisedNative, "nodesByKey");
		Assert.assertSame(source, oldNativeNodes.get(source.key()));
		Assert.assertSame("first fallback must observe the current node authority",
			replacement, revisedNativeNodes.get(source.key()));
		Assert.assertSame(oldNative, field(field(original, "resolver"), "nativeContinuity"));
		Assert.assertSame(revisedNative, field(field(revised, "resolver"), "nativeContinuity"));
	}

	@Test
	public void ownedInventoryUpdatesResolverInPlaceAcrossFactAndAnchorRevisions() throws Exception {
		Fixture f = new Fixture();
		Object inventory = f.ownedInventory(f.nodes, f.facts, f.edges);
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), inventory));
		Object resolver = field(inventory, "resolver");
		Object nodeIndex = field(inventory, "nodesByKey");

		CandidateRuleFact available = f.facts.get(1);
		CandidateRuleFact withdrawn = new CandidateRuleFact(available.key(),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, available.capability(), available.shapeProof(),
			available.profile(), List.of(), "PRIVATE_AGGREGATE");
		Assert.assertSame(inventory, replaceOwnerOwned(inventory, 1, f.nodes.get(1).key(),
			f.nodes.get(1), List.of(withdrawn)));
		Assert.assertSame("the method-local resolver is updated instead of copied", resolver,
			field(inventory, "resolver"));
		List<CandidateRuleFact> withdrawnFacts = new ArrayList<>(f.facts);
		withdrawnFacts.set(1, withdrawn);
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, withdrawnFacts, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), inventory));

		replaceOwnerOwned(inventory, 1, f.nodes.get(1).key(), f.nodes.get(1), List.of(available));
		Assert.assertSame(resolver, field(inventory, "resolver"));
		Assert.assertEquals("restoring the owner restores exact cold behavior",
			f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), inventory));

		Node source = f.nodes.get(0);
		DurableAnchorKey changedAnchor = new DurableAnchorKey("owned-changed-anchor", FType.FULL,
			List.of(new AnchorPartition("worker-b", List.of(0L, 0L), List.of(4L, 2L))));
		Node replacement = new Node(source.key(), source.kind(), source.valueVersion(), source.emittedWork(),
			source.legalAlternatives(), source.exclusions(), List.of(changedAnchor));
		replaceOwnerOwned(inventory, 0, source.key(), replacement, List.of(f.facts.get(0)));
		List<Node> changedNodes = new ArrayList<>(f.nodes);
		changedNodes.set(0, replacement);
		Assert.assertSame("the exclusively owned node index is updated in place", nodeIndex,
			field(inventory, "nodesByKey"));
		Assert.assertSame(replacement, ((Map<?,?>)nodeIndex).get(source.key()));
		Assert.assertSame(resolver, field(inventory, "resolver"));
		Assert.assertEquals(f.cold(f.nodes.get(1), f.facts.get(1), changedNodes, f.edges),
			shared(f.nodes.get(1), f.facts.get(1), inventory));
		Assert.assertNull("owner and node revisions before fallback keep native continuity lazy",
			field(resolver, "nativeContinuity"));
		Object materialized = invoke(resolver, "nativeContinuity");
		replaceOwnerOwned(inventory, 1, f.nodes.get(1).key(), f.nodes.get(1), List.of(available));
		Assert.assertNotSame("after first fallback, owner revisions keep the exact revision path",
			materialized, field(resolver, "nativeContinuity"));
	}

	@Test
	public void ownedInventoryMaintainsLastSlotCollisionWinnerAcrossRevisions() throws Exception {
		Fixture f = new Fixture();
		CandidateRuleFact base = f.facts.get(0);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization first = emission.realizations().get(0);
		CandidateEmissionRealization second = new CandidateEmissionRealization(first.key(),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact firstFact = withRealization(base, first);
		CandidateRuleFact secondFact = withRealization(base, second);
		List<CandidateRuleFact> initialFacts = List.of(firstFact, secondFact,
			f.facts.get(1), f.facts.get(2));
		Object inventory = f.ownedInventory(f.nodes, initialFacts, f.edges);
		Object resolver = invoke(inventory, "resolverForQuery");
		Assert.assertSame(second, collisionWinner(resolver, first));

		replaceOwnerOwned(inventory, 0, f.nodes.get(0).key(), f.nodes.get(0), List.of(firstFact));
		Assert.assertSame(resolver, field(inventory, "resolver"));
		Assert.assertSame("withdrawing the final slot reveals the previous realization", first,
			collisionWinner(resolver, first));
		List<CandidateRuleFact> withdrawnFacts = List.of(firstFact, f.facts.get(1), f.facts.get(2));
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, withdrawnFacts, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), inventory));

		replaceOwnerOwned(inventory, 0, f.nodes.get(0).key(), f.nodes.get(0),
			List.of(firstFact, secondFact));
		Assert.assertSame(second, collisionWinner(resolver, first));
		Assert.assertEquals(f.cold(f.nodes.get(2), f.facts.get(2), f.nodes, initialFacts, f.edges),
			shared(f.nodes.get(2), f.facts.get(2), inventory));
	}

	@Test
	public void retainedInventoryCannotEnterOwnedMutationPath() throws Exception {
		Fixture f = new Fixture();
		Object retained = f.ownerInventory(f.nodes, f.facts, f.edges);
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> replaceOwnerOwned(retained, 1, f.nodes.get(1).key(), f.nodes.get(1),
				List.of(f.facts.get(1))));
		Assert.assertTrue(error.getCause() instanceof IllegalStateException);
		Assert.assertEquals("Proof inventory is not exclusively owned", error.getCause().getMessage());
	}

	@Test
	public void ownedInventoryCannotPublishAnImmutableAlias() throws Exception {
		Fixture f = new Fixture();
		Object owned = f.ownedInventory(f.nodes, f.facts, f.edges);
		Object resolver = invoke(owned, "resolverForQuery");
		Object nodes = field(owned, "nodes");
		Object facts = field(owned, "factsByOrdinal");
		Object nodeIndex = field(owned, "nodesByKey");
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> replaceOwner(owned, 1, f.nodes.get(1).key(), f.nodes.get(1),
				List.of(f.facts.get(1))));
		Assert.assertTrue(error.getCause() instanceof IllegalStateException);
		Assert.assertEquals("Exclusively owned proof inventory requires owned replacement",
			error.getCause().getMessage());
		Assert.assertSame(nodes, field(owned, "nodes"));
		Assert.assertSame(facts, field(owned, "factsByOrdinal"));
		Assert.assertSame(nodeIndex, field(owned, "nodesByKey"));
		Assert.assertSame(resolver, field(owned, "resolver"));
	}

	@Test
	public void changedValueVersionUsesLazyColdStructuralInventory() throws Exception {
		Fixture f = new Fixture();
		Object original = f.ownerInventory(f.nodes, f.facts, f.edges);
		shared(f.nodes.get(1), f.facts.get(1), original);
		Node source = f.nodes.get(0);
		ValueVersionKey changedVersion = new ValueVersionKey(f.fingerprint, "source-v2", f.region,
			99, VersionKind.ORDINARY, List.of("cfg-definition:changed"));
		Node replacement = new Node(source.key(), source.kind(), changedVersion, source.emittedWork(),
			source.legalAlternatives(), source.exclusions(), source.anchors());
		Object revised = replaceOwner(original, 0, source.key(), replacement, List.of(f.facts.get(0)));
		Assert.assertNull("unsupported structural changes retain cold lazy validation", field(revised, "resolver"));
		Assert.assertNull(field(revised, "matrixEdgesByConsumer"));
		List<Node> coldNodes = new ArrayList<>(f.nodes);
		coldNodes.set(0, replacement);
		Assert.assertEquals(f.cold(f.nodes.get(1), f.facts.get(1), coldNodes, f.edges),
			shared(f.nodes.get(1), f.facts.get(1), revised));
	}

	@Test
	public void normalizedReferenceCollisionWithdrawalRevealsPreviousGlobalWinner() throws Exception {
		Fixture f = new Fixture();
		CandidateRuleFact base = f.facts.get(0);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization first = emission.realizations().get(0);
		CandidateEmissionRealization second = new CandidateEmissionRealization(first.key(),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact firstFact = withRealization(base, first);
		CandidateRuleFact secondFact = withRealization(base, second);
		Object inventory = f.ownerInventory(f.nodes, List.of(firstFact, secondFact), f.edges);
		Object resolver = invoke(inventory, "resolverForQuery");
		Assert.assertSame("cold global traversal remains last-wins", second,
			collisionWinner(resolver, first));
		Object withdrawn = replaceOwner(inventory, 0, f.nodes.get(0).key(), f.nodes.get(0),
			List.of(firstFact));
		Assert.assertSame("withdrawing the later collision reveals the prior slot", first,
			collisionWinner(field(withdrawn, "resolver"), first));
		Object restored = replaceOwner(withdrawn, 0, f.nodes.get(0).key(), f.nodes.get(0),
			List.of(firstFact, secondFact));
		Assert.assertSame(second, collisionWinner(field(restored, "resolver"), first));
	}

	@Test
	public void interleavedCrossOwnerCollisionKeepsColdGlobalPrecedenceAndFailsDeltaClosed() throws Exception {
		Fixture f = new Fixture();
		Node original = f.nodes.get(0);
		CompiledHopKey key = original.key();
		CompiledHopKey freshEqualKey = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(), key.emittedHopInstance(),
			key.canonicalSourceOrigin());
		Node equalOwner = new Node(freshEqualKey, original.kind(), original.valueVersion(),
			original.emittedWork(), original.legalAlternatives(), original.exclusions(), original.anchors());
		CandidateRuleFact base = f.facts.get(0);
		CandidateEmissionRealization first = base.allowedEmissionFacts().get(0).realizations().get(0);
		CandidateEmissionRealization middle = new CandidateEmissionRealization(first.key(),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionRealization last = new CandidateEmissionRealization(first.key(),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact firstFact = withRealization(base, first);
		CandidateRuleFact middleFact = withOwnerAndRealization(base, freshEqualKey, middle);
		CandidateRuleFact lastFact = withRealization(base, last);
		Object inventory = f.inventory(List.of(original, equalOwner),
			List.of(firstFact, middleFact, lastFact), List.of());
		Object resolver = invoke(inventory, "resolverForQuery");
		Assert.assertSame("interleaved cold traversal must keep the final A2 global winner", last,
			collisionWinner(resolver, first));
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> replaceOwner(inventory, 1, freshEqualKey, equalOwner, List.of(middleFact)));
		Assert.assertTrue(error.getCause() instanceof IllegalStateException);
		Assert.assertSame("failed cold revision must leave the exact cold winner immutable", last,
			collisionWinner(field(inventory, "resolver"), first));
	}

	@Test
	public void equalButForeignReplacementKeyFailsClosedEvenWithNoFacts() throws Exception {
		Fixture f = new Fixture();
		Node previous = f.nodes.get(0);
		CompiledHopKey key = previous.key();
		CompiledHopKey freshEqualKey = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(), key.emittedHopInstance(),
			key.canonicalSourceOrigin());
		Assert.assertEquals(key, freshEqualKey);
		Assert.assertNotSame(key, freshEqualKey);
		Node foreign = new Node(freshEqualKey, previous.kind(), previous.valueVersion(), previous.emittedWork(),
			previous.legalAlternatives(), previous.exclusions(), previous.anchors());
		Object inventory = f.ownerInventory(f.nodes, f.facts, f.edges);
		InvocationTargetException error = Assert.assertThrows(InvocationTargetException.class,
			() -> replaceOwner(inventory, 0, key, foreign, List.of()));
		Assert.assertTrue(error.getCause() instanceof IllegalArgumentException);
		@SuppressWarnings("unchecked")
		List<Node> retainedNodes = (List<Node>)field(inventory, "nodes");
		Assert.assertSame(key, retainedNodes.get(0).key());
	}

	private static CandidateRuleFact withRealization(CandidateRuleFact base,
		CandidateEmissionRealization realization) {
		CandidateEmissionFact prior = base.allowedEmissionFacts().get(0);
		CandidateEmissionFact emission = new CandidateEmissionFact(prior.emissionState(),
			prior.executionFType(), prior.derivedFoutAction(), List.of(realization));
		return new CandidateRuleFact(base.key(), base.status(), base.capability(), base.shapeProof(),
			base.profile(), List.of(emission), base.failureCode());
	}

	private static CandidateRuleFact withOwnerAndRealization(CandidateRuleFact base,
		CompiledHopKey owner, CandidateEmissionRealization realization) {
		CandidateRuleFact replaced = withRealization(base, realization);
		return new CandidateRuleFact(new CandidateRuleKey(owner, base.key().orderedInputs()),
			replaced.status(), replaced.capability(), replaced.shapeProof(), replaced.profile(),
			replaced.allowedEmissionFacts(), replaced.failureCode());
	}

	private static CandidateEmissionRealization collisionWinner(Object resolver,
		CandidateEmissionRealization member) throws Exception {
		@SuppressWarnings("unchecked")
		Map<String,List<?>> buckets = (Map<String,List<?>>)field(resolver, "realizationSlotsByReference");
		List<?> slots = buckets.values().stream().filter(bucket -> bucket.stream().anyMatch(slot -> {
			try {
				return invoke(slot, "realization") == member;
			}
			catch(Exception exception) {
				throw new IllegalStateException(exception);
			}
		})).findFirst().orElseThrow();
		return (CandidateEmissionRealization)invoke(slots.get(slots.size() - 1), "realization");
	}

	private static final class Fixture {
		private final String fingerprint = "materialization-proof-inventory";
		private final ControlRegionKey region = new ControlRegionKey(fingerprint, "main",
			List.of("body"), "main", "recompile");
		private final DurableAnchorKey anchor = new DurableAnchorKey("source-pool", FType.FULL,
			List.of(new AnchorPartition("worker-a", List.of(0L, 0L), List.of(4L, 2L))));
		private final List<Node> nodes = new ArrayList<>();
		private final List<CandidateRuleFact> facts = new ArrayList<>();
		private final List<CompiledInputEdgeFact> edges = new ArrayList<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();

		private Fixture() {
			DataOp source = new DataOp("source", DataType.MATRIX, ValueType.FP64,
				OpOpData.FEDERATED, "source", 4, 2, 8, 1000);
			FederatedPlannerUtils.setFederatedSourcePrivacyForTesting(source, Privacy.PRIVATE_AGGREGATE);
			add("source", source, FULL, List.of(anchor), List.of(), new NodeShapeFact(DataType.MATRIX, 4, 2));
			for(String name : List.of("first", "second")) {
				Hop owner = new AggUnaryOp(name, DataType.MATRIX, ValueType.FP64, AggOp.SUM, Direction.Col, source);
				add(name, owner, LOCAL, List.of(), List.of(CandidateInputState.present(FType.FULL)),
					new NodeShapeFact(DataType.MATRIX, 1, 2));
				edges.add(new CompiledInputEdgeFact(nodes.get(0).key(), nodes.get(nodes.size() - 1).key(), 0));
			}
		}

		private void add(String name, Hop hop, PlacementState state, List<DurableAnchorKey> anchors,
			List<CandidateInputState> inputs, NodeShapeFact shape) {
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "root", "recompile", region, name, name);
			ValueVersionKey version = new ValueVersionKey(fingerprint, name, region, nodes.size(),
				VersionKind.ORDINARY, List.of());
			nodes.add(new Node(key, NodeKind.OPERATION, version, true, List.of(state), List.of(), anchors));
			facts.add(new CandidateRuleFact(new CandidateRuleKey(key, inputs), CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", state.execType(), state.output(),
					state.fType(), ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(FType.FULL), ""),
				List.of(new CandidateEmissionFact(new PlacementEmissionState(state, false),
					state.execType() == ExecType.FED ? FType.FULL : null)), ""));
			origins.put(key, hop);
			shapes.put(hop, shape);
		}

		private Object inventory(List<Node> proofNodes, List<CompiledInputEdgeFact> proofEdges) throws Exception {
			return inventory(proofNodes, facts, proofEdges);
		}

		private Object inventory(List<Node> proofNodes, List<CandidateRuleFact> proofFacts,
			List<CompiledInputEdgeFact> proofEdges) throws Exception {
			Constructor<?> constructor = inventoryType().getDeclaredConstructor(List.class, List.class,
				List.class, List.class, Collection.class, Map.class, Map.class);
			constructor.setAccessible(true);
			return constructor.newInstance(proofNodes, proofFacts, proofEdges, List.of(), List.of(), origins, shapes);
		}

		private Object ownerInventory(List<Node> proofNodes, List<CandidateRuleFact> proofFacts,
			List<CompiledInputEdgeFact> proofEdges) throws Exception {
			Map<CompiledHopKey,Integer> ordinalByOwner = new IdentityHashMap<>();
			List<List<CandidateRuleFact>> factsByOrdinal = new ArrayList<>();
			for(int ordinal = 0; ordinal < proofNodes.size(); ordinal++) {
				ordinalByOwner.put(proofNodes.get(ordinal).key(), ordinal);
				factsByOrdinal.add(new ArrayList<>());
			}
			for(CandidateRuleFact fact : proofFacts)
				factsByOrdinal.get(ordinalByOwner.get(fact.key().parentOccurrence())).add(fact);
			Constructor<?> constructor = inventoryType().getDeclaredConstructor(List.class, List.class,
				List.class, List.class, Collection.class, Map.class, Map.class, boolean.class);
			constructor.setAccessible(true);
			return constructor.newInstance(proofNodes, factsByOrdinal, proofEdges, List.of(), List.of(),
				origins, shapes, true);
		}

		private Object ownedInventory(List<Node> proofNodes, List<CandidateRuleFact> proofFacts,
			List<CompiledInputEdgeFact> proofEdges) throws Exception {
			Map<CompiledHopKey,Integer> ordinalByOwner = new IdentityHashMap<>();
			List<List<CandidateRuleFact>> factsByOrdinal = new ArrayList<>();
			for(int ordinal = 0; ordinal < proofNodes.size(); ordinal++) {
				ordinalByOwner.put(proofNodes.get(ordinal).key(), ordinal);
				factsByOrdinal.add(new ArrayList<>());
			}
			for(CandidateRuleFact fact : proofFacts)
				factsByOrdinal.get(ordinalByOwner.get(fact.key().parentOccurrence())).add(fact);
			Method method = inventoryType().getDeclaredMethod("owned", List.class, List.class,
				List.class, List.class, Collection.class, Map.class, Map.class);
			method.setAccessible(true);
			return method.invoke(null, proofNodes, factsByOrdinal, proofEdges, List.of(), List.of(),
				origins, shapes);
		}

		private Object cold(Node raw, CandidateRuleFact fact, List<Node> proofNodes,
			List<CompiledInputEdgeFact> proofEdges) throws Exception {
			return cold(raw, fact, proofNodes, facts, proofEdges);
		}

		private Object cold(Node raw, CandidateRuleFact fact, List<Node> proofNodes,
			List<CandidateRuleFact> proofFacts, List<CompiledInputEdgeFact> proofEdges) throws Exception {
			Method method = PlacementRelationClosure.class.getDeclaredMethod(
				"closeDerivedWorkerPoolMaterializationCandidates", List.class, List.class, List.class,
				List.class, List.class, List.class, Collection.class, Map.class, Map.class);
			method.setAccessible(true);
			return method.invoke(closure(origins, shapes), List.of(raw), List.of(fact), proofNodes, proofFacts,
				proofEdges, List.of(), List.of(), origins, shapes);
		}
	}

	private static Object shared(Node raw, CandidateRuleFact fact, Object inventory) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"closeDerivedWorkerPoolMaterializationCandidates", List.class, List.class, inventoryType());
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,Hop> origins = (Map<CompiledHopKey,Hop>)field(inventory, "origins");
		@SuppressWarnings("unchecked")
		Map<Hop,NodeShapeFact> shapes = (Map<Hop,NodeShapeFact>)field(inventory, "shapeFactsByHop");
		return method.invoke(closure(origins, shapes), List.of(raw), List.of(fact), inventory);
	}

	private static PlacementRelationClosure closure(Map<CompiledHopKey,Hop> origins,
		Map<Hop,NodeShapeFact> shapes) throws Exception {
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, null, false, null, false);
		Field originsField = PlacementRelationClosure.class.getDeclaredField("origins");
		originsField.setAccessible(true);
		originsField.set(closure, origins);
		Field shapesField = PlacementRelationClosure.class.getDeclaredField("shapeFactsByHop");
		shapesField.setAccessible(true);
		shapesField.set(closure, shapes);
		return closure;
	}

	private static Object replaceOwner(Object inventory, int ordinal, CompiledHopKey owner,
		Node replacement, List<CandidateRuleFact> facts) throws Exception {
		Method method = inventoryType().getDeclaredMethod("replaceOwner", int.class,
			CompiledHopKey.class, Node.class, List.class);
		method.setAccessible(true);
		return method.invoke(inventory, ordinal, owner, replacement, facts);
	}

	private static Object replaceOwnerOwned(Object inventory, int ordinal, CompiledHopKey owner,
		Node replacement, List<CandidateRuleFact> facts) throws Exception {
		Method method = inventoryType().getDeclaredMethod("replaceOwnerOwned", int.class,
			CompiledHopKey.class, Node.class, List.class);
		method.setAccessible(true);
		return method.invoke(inventory, ordinal, owner, replacement, facts);
	}

	private static long accessor(Object owner, String name) throws Exception {
		Method method = owner.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return ((Number)method.invoke(owner)).longValue();
	}

	private static Object invoke(Object owner, String name) throws Exception {
		Method method = owner.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(owner);
	}

	private static Class<?> inventoryType() throws ClassNotFoundException {
		return Class.forName(PlacementRelationClosure.class.getName() + "$CommittedProofInventory");
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> outputFacts(Object result) throws Exception {
		Method method = result.getClass().getDeclaredMethod("ruleFacts");
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(result);
	}
}
