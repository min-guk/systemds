/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeValueMapFixedPoolContinuityTest {
	private static final PlacementState FED_FOUT =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(FED_FOUT, false);

	@Test
	public void everyAlternativeClauseMustProveTheSamePool() {
		Fixture fixture = fixture(false);
		NativePlacementContinuity.FixedValueMapPool fixed =
			fixture.continuity().fixedValueMapPool(fixture.valueMap());
		Assert.assertNotNull(fixed);
		Assert.assertTrue(fixed.exactLayout());
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(
			fixture.expectedPool(), fixed.pool()));
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(fixture.expectedPool(),
			PlacementRelationClosure.relocationCandidatePool(fixture.valueMap(),
				valueMapClause(fixture), fixture.continuity())));
		Assert.assertNotNull(fixture.continuity().proveCandidate(
			fixture.valueMap(), fixture.expectedPool()));
	}

	@Test
	public void mixedAlternativePoolsCannotLendNativeAuthority() {
		Fixture fixture = fixture(true);
		Assert.assertNull(fixture.continuity().fixedValueMapPool(fixture.valueMap()));
		Assert.assertNull(PlacementRelationClosure.relocationCandidatePool(fixture.valueMap(),
			valueMapClause(fixture), fixture.continuity()));
		Assert.assertNull(fixture.continuity().proveCandidate(
			fixture.valueMap(), fixture.expectedPool()));
	}

	@Test
	public void ungroundedAliasCycleCannotAuthorizeItself() {
		Node alias = node("cycle", 0);
		CandidateRuleKey rule = rule(alias, 1);
		PlacementRealizationKey key = PlacementRealizationKey.valueMap(EMISSION, "self-cycle");
		CandidateRealizationReference reference = new CandidateRealizationReference(rule, key);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(key, List.of(
			clause(CandidateRealizationInputBinding.logicalTransient(0, reference))));
		NativePlacementContinuity continuity = continuity(List.of(alias), List.of(fact(rule, realization)));
		Assert.assertNull(continuity.fixedValueMapPool(reference));
	}

	@Test
	public void sameClauseSelfCycleIsGroundedByItsExactLeaf() {
		CycleFixture fixture = cycleFixture(false);
		Assert.assertNotNull(fixture.continuity().fixedValueMapPool(fixture.root()));
	}

	@Test
	public void separatelySelectableSelfCycleCannotBorrowSiblingLeaf() {
		CycleFixture fixture = cycleFixture(true);
		Assert.assertNull(fixture.continuity().fixedValueMapPool(fixture.root()));
	}

	@Test
	public void dynamicLayoutMakesExactGeometryComparisonOrderIndependent() {
		MixedLayoutFixture exactFirst = mixedLayoutPool(false);
		MixedLayoutFixture dynamicFirst = mixedLayoutPool(true);
		Assert.assertEquals(List.of("a", "b", "c"), exactFirst.canonicalSourceOrder());
		Assert.assertEquals(List.of("c", "a", "b"), dynamicFirst.canonicalSourceOrder());
		Assert.assertNotEquals(exactFirst.canonicalSourceOrder(), dynamicFirst.canonicalSourceOrder());
		Assert.assertNotNull(exactFirst.fixedPool());
		Assert.assertNotNull(dynamicFirst.fixedPool());
		Assert.assertFalse(exactFirst.fixedPool().exactLayout());
		Assert.assertFalse(dynamicFirst.fixedPool().exactLayout());
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerEndpoints(
			exactFirst.fixedPool().pool(), dynamicFirst.fixedPool().pool()));
		Assert.assertNull(PlacementRelationClosure.relocationCandidatePool(
			exactFirst.reference(), exactFirst.clause(), exactFirst.continuity()));
		Assert.assertNull(PlacementRelationClosure.relocationCandidatePool(
			dynamicFirst.reference(), dynamicFirst.clause(), dynamicFirst.continuity()));
	}

	@Test
	public void workerPartitionAgreementDoesNotInventFullRelocationGeometry() {
		Node first = node("extent-first", 24);
		Node second = node("extent-second", 25);
		Node alias = node("extent-alias", 26);
		CandidateRuleKey firstRule = rule(first, 0);
		CandidateRuleKey secondRule = rule(second, 0);
		CandidateEmissionRealization firstRealization = durable(
			pool("extent-a", "a:9000", "b:9000", 4, 2));
		CandidateEmissionRealization secondRealization = durable(
			pool("extent-b", "a:9000", "b:9000", 4, 3));
		CandidateRuleKey aliasRule = rule(alias, 2);
		CandidateEmissionRealization aliasRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "extent-map", List.of(new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.logicalTransient(0,
					CandidateRealizationReference.of(firstRule, firstRealization)),
				CandidateRealizationInputBinding.logicalTransient(1,
					CandidateRealizationReference.of(secondRule, secondRealization))))));
		List<CandidateRuleFact> facts = List.of(fact(firstRule, firstRealization),
			fact(secondRule, secondRealization), fact(aliasRule, aliasRealization));
		NativePlacementContinuity continuity = continuity(List.of(first, second, alias), facts);
		CandidateRealizationReference reference =
			CandidateRealizationReference.of(aliasRule, aliasRealization);
		NativePlacementContinuity.FixedValueMapPool fixed = continuity.fixedValueMapPool(reference);
		Assert.assertNotNull(fixed);
		Assert.assertTrue("ROW partition authority ignores the orthogonal extent", fixed.exactLayout());
		Assert.assertFalse("relocation binding requires the complete physical geometry",
			fixed.exactPhysicalLayout());
		DurableAnchorKey directPool = PlacementRelationClosure.directCandidatePool(reference,
			aliasRealization.supportClauses().get(0), continuity);
		Assert.assertNotNull("direct binding only requires the exact worker partition layout",
			directPool);
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(fixed.pool(), directPool));
		Assert.assertNull(PlacementRelationClosure.relocationCandidatePool(reference,
			aliasRealization.supportClauses().get(0), continuity));
	}

	@Test
	public void broadcastDirectReceiptUsesWorkerEndpointsInsteadOfPartitionAxis() {
		DurableAnchorKey rowTarget = pool("row-target", "a:9000", "b:9000");
		DurableAnchorKey broadcast = broadcastPool("broadcast", "a:9000", "b:9000");
		DurableAnchorKey otherWorkers = broadcastPool("other", "a:9000", "c:9000");
		Assert.assertTrue(PlacementRelationClosure.directRelocationPoolMatch(
			broadcast, rowTarget, FType.BROADCAST));
		Assert.assertTrue("resolver anchors retain their partition FType while the candidate state is broadcast",
			PlacementRelationClosure.directRelocationPoolMatch(
				rowTarget, rowTarget, FType.BROADCAST));
		Assert.assertFalse(PlacementRelationClosure.directRelocationPoolMatch(
			otherWorkers, rowTarget, FType.BROADCAST));
		Assert.assertFalse("partitioned layouts retain their exact FType/range contract",
			PlacementRelationClosure.directRelocationPoolMatch(broadcast, rowTarget, FType.ROW));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void broadcastOnPartitionedTargetPublishesAsBoundDirectAction() throws Exception {
		DurableAnchorKey rowTarget = pool("direct-row", "a:9000", "b:9000");
		PlacementState broadcastState = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.BROADCAST, false);
		PlacementEmissionState broadcastEmission = new PlacementEmissionState(broadcastState, false);
		Node seed = new Node(key("direct-seed"), NodeKind.OPERATION,
			version("direct-seed", 26), true, List.of(FED_FOUT), List.of(), List.of(rowTarget));
		Node source = new Node(key("direct-source"), NodeKind.OPERATION,
			version("direct-source", 27), true, List.of(broadcastState), List.of(), List.of());
		Node consumer = node("direct-consumer", 28);
		CandidateRuleKey seedRule = rule(seed, 0);
		CandidateEmissionRealization seedRealization = durable(rowTarget);
		CandidateRuleKey sourceRule = rule(source, 1);
		CandidateEmissionRealization sourceRealization = CandidateEmissionRealization.nativeLineage(
			broadcastEmission, "derived-broadcast", List.of(), List.of(
				CandidateRealizationInputBinding.direct(0,
					CandidateRealizationReference.of(seedRule, seedRealization))));
		List<CandidateRuleFact> facts = List.of(fact(seedRule, seedRealization),
			fact(sourceRule, new CandidateEmissionFact(broadcastEmission, FType.BROADCAST,
				null, List.of(sourceRealization))));
		Map<CompiledHopKey,Node> nodesByKey = new java.util.IdentityHashMap<>();
		nodesByKey.put(seed.key(), seed);
		nodesByKey.put(source.key(), source);
		nodesByKey.put(consumer.key(), consumer);
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> origins = new java.util.IdentityHashMap<>();
		Map<org.apache.sysds.hops.Hop,NodeShapeFact> shapes = new java.util.IdentityHashMap<>();
		for(Node node : List.of(seed, source, consumer)) {
			DataOp hop = hop(node.key().canonicalSourceOrigin());
			origins.put(node.key(), hop);
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		}
		Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edges = new java.util.IdentityHashMap<>();
		edges.put(source.key(), Map.of(0,
			new CompiledInputEdgeFact(seed.key(), source.key(), 0)));

		Class<?> resolverClass = Class.forName(PlacementRelationClosure.class.getName()
			+ "$WorkerPoolAnchorResolver");
		Constructor<?> resolverConstructor = resolverClass.getDeclaredConstructor(
			Map.class, Map.class, List.class, List.class, java.util.Collection.class,
			Map.class, Map.class);
		resolverConstructor.setAccessible(true);
		Object resolver = resolverConstructor.newInstance(nodesByKey, edges, facts,
			List.of(), List.of(), origins, shapes);
		Class<?> groupClass = Class.forName(PlacementRelationClosure.class.getName()
			+ "$RelocationGroup");
		Constructor<?> groupConstructor = groupClass.getDeclaredConstructor(ValueVersionKey.class,
			PlacementState.class, FType.class, DurableAnchorKey.class, String.class);
		groupConstructor.setAccessible(true);
		Object group = groupConstructor.newInstance(source.valueVersion(), FED_FOUT,
			FType.BROADCAST, rowTarget, "scope");
		Method direct = PlacementRelationClosure.class.getDeclaredMethod("directSourcePlacements",
			groupClass, List.class, resolverClass);
		direct.setAccessible(true);
		List<PlacementState> directStates = (List<PlacementState>)direct.invoke(
			null, group, List.of(source, consumer), resolver);
		Assert.assertEquals(List.of(broadcastState), directStates);

		RelocationActionKey actionKey = new RelocationActionKey(source.valueVersion(), FED_FOUT,
			FType.BROADCAST, rowTarget, "scope", List.of(consumer.key()));
		NeutralPlacementGraph.RelocationAction action = new NeutralPlacementGraph.RelocationAction(
			actionKey, List.of(new ObligationKey(consumer.key(), 1, source.valueVersion(),
				FED_FOUT, actionKey, "scope")), directStates);
		PlacementSupportRelations.verifyPublishedRelocationRealizations(List.of(), List.of(action));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void exactDurableBroadcastOutputIsAResolvableDirectReceipt() throws Exception {
		DurableAnchorKey rowTarget = pool("durable-row", "a:9000", "b:9000");
		DurableAnchorKey outputPool = broadcastPool("durable-broadcast", "a:9000", "b:9000");
		PlacementState state = new PlacementState(ExecType.CP, FederatedOutput.FOUT,
			FType.BROADCAST, false);
		PlacementEmissionState emission = new PlacementEmissionState(state, false);
		Node source = new Node(key("durable-source"), NodeKind.OPERATION,
			version("durable-source", 40), true, List.of(state), List.of(), List.of());
		CandidateRuleKey sourceRule = rule(source, 0);
		CandidateEmissionRealization realization = CandidateEmissionRealization.durable(
			emission, outputPool, List.of(), List.of());
		List<CandidateRuleFact> facts = List.of(fact(sourceRule,
			new CandidateEmissionFact(emission, null, null, List.of(realization))));
		Map<CompiledHopKey,Node> nodes = new java.util.IdentityHashMap<>();
		nodes.put(source.key(), source);
		DataOp hop = hop(source.key().canonicalSourceOrigin());
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> origins = new java.util.IdentityHashMap<>();
		origins.put(source.key(), hop);
		Map<org.apache.sysds.hops.Hop,NodeShapeFact> shapes = new java.util.IdentityHashMap<>();
		shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		Class<?> resolverClass = Class.forName(PlacementRelationClosure.class.getName()
			+ "$WorkerPoolAnchorResolver");
		Constructor<?> resolverConstructor = resolverClass.getDeclaredConstructor(
			Map.class, Map.class, List.class, List.class, java.util.Collection.class,
			Map.class, Map.class);
		resolverConstructor.setAccessible(true);
		Object resolver = resolverConstructor.newInstance(nodes, Map.of(), facts,
			List.of(), List.of(), origins, shapes);
		Class<?> groupClass = Class.forName(PlacementRelationClosure.class.getName()
			+ "$RelocationGroup");
		Constructor<?> groupConstructor = groupClass.getDeclaredConstructor(ValueVersionKey.class,
			PlacementState.class, FType.class, DurableAnchorKey.class, String.class);
		groupConstructor.setAccessible(true);
		Object group = groupConstructor.newInstance(source.valueVersion(), FED_FOUT,
			FType.BROADCAST, rowTarget, "scope");
		Method direct = PlacementRelationClosure.class.getDeclaredMethod("directSourcePlacements",
			groupClass, List.class, resolverClass);
		direct.setAccessible(true);
		Assert.assertEquals(List.of(state), direct.invoke(null, group, List.of(source), resolver));
	}

	@Test
	public void directPublicationDoesNotLetAnotherSameStatePoolSkipMovement() {
		DurableAnchorKey target = pool("selected-target", "a:9000", "b:9000");
		DurableAnchorKey poolA = broadcastPool("selected-a", "a:9000", "b:9000");
		DurableAnchorKey poolB = broadcastPool("selected-b", "a:9000", "c:9000");
		PlacementState sourceState = new PlacementState(ExecType.FED, FederatedOutput.FOUT,
			FType.BROADCAST, false);
		PlacementEmissionState sourceEmissionState = new PlacementEmissionState(sourceState, false);
		Node source = new Node(key("selected-source"), NodeKind.OPERATION,
			version("selected-source", 41), true, List.of(sourceState), List.of(), List.of());
		Node consumer = node("selected-consumer", 42);
		CandidateRuleKey sourceRule = rule(source, 0);
		CandidateEmissionRealization sourceA = CandidateEmissionRealization.durable(
			sourceEmissionState, poolA, List.of(), List.of());
		CandidateEmissionRealization sourceB = CandidateEmissionRealization.durable(
			sourceEmissionState, poolB, List.of(), List.of());
		CandidateEmissionFact sourceEmission = new CandidateEmissionFact(sourceEmissionState,
			FType.BROADCAST, null, List.of(sourceA, sourceB));
		CandidateRuleKey consumerRule = new CandidateRuleKey(consumer.key(),
			List.of(CandidateInputState.present(FType.BROADCAST)));
		CandidateEmissionRealization consumerA = CandidateEmissionRealization.valueMap(
			EMISSION, "selected-consumer-a", List.of(clause(
				CandidateRealizationInputBinding.direct(0,
					CandidateRealizationReference.of(sourceRule, sourceA)))));
		CandidateEmissionRealization consumerB = CandidateEmissionRealization.valueMap(
			EMISSION, "selected-consumer-b", List.of(clause(
				CandidateRealizationInputBinding.direct(0,
					CandidateRealizationReference.of(sourceRule, sourceB)))));
		CandidateEmissionFact consumerEmission = new CandidateEmissionFact(EMISSION, FType.ROW,
			null, List.of(consumerA, consumerB));
		RelocationActionKey actionKey = new RelocationActionKey(source.valueVersion(), FED_FOUT,
			FType.BROADCAST, target, "scope", List.of(consumer.key()));
		NeutralPlacementGraph.RelocationAction action = new NeutralPlacementGraph.RelocationAction(
			actionKey, List.of(new ObligationKey(consumer.key(), 0, source.valueVersion(),
				FED_FOUT, actionKey, "scope")), List.of(sourceState));
		NeutralPlacementGraph graph = new NeutralPlacementGraph(
			List.of(source, consumer), List.of(), List.of(action));
		Map<CompiledHopKey,PlacementState> assignment = new java.util.IdentityHashMap<>();
		assignment.put(source.key(), sourceState);
		assignment.put(consumer.key(), FED_FOUT);
		PlacementIdentity.CandidateSelectionReceipt selectedSourceA =
			new PlacementIdentity.CandidateSelectionReceipt(sourceRule, sourceEmission, sourceA, List.of());
		PlacementIdentity.CandidateSelectionReceipt selectedConsumerA =
			new PlacementIdentity.CandidateSelectionReceipt(consumerRule, consumerEmission, consumerA, List.of());
		PlacementIdentity.CandidateSelectionReceipt selectedSourceB =
			new PlacementIdentity.CandidateSelectionReceipt(sourceRule, sourceEmission, sourceB, List.of());
		PlacementIdentity.CandidateSelectionReceipt selectedConsumerB =
			new PlacementIdentity.CandidateSelectionReceipt(consumerRule, consumerEmission, consumerB, List.of());
		Assert.assertFalse(graph.isRelocationActive(action, assignment,
			List.of(selectedSourceA, selectedConsumerA)));
		Assert.assertTrue("a same-state realization on different endpoints still requires movement",
			graph.isRelocationActive(action, assignment,
				List.of(selectedSourceB, selectedConsumerB)));
	}

	private static CandidateRealizationSupportClause valueMapClause(Fixture fixture) {
		return fixture.facts().stream()
			.filter(fact -> fact.key().equals(fixture.valueMap().rule()))
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.key().equals(fixture.valueMap().realization()))
			.flatMap(realization -> realization.supportClauses().stream())
			.findFirst().orElseThrow();
	}

	@Test
	public void sourceWithdrawalInvalidatesTransitivePoolAuthority() {
		Fixture fixture = fixture(false);
		Assert.assertNotNull(fixture.continuity().proveCandidate(
			fixture.valueMap(), fixture.expectedPool()));
		List<CandidateRuleFact> withdrawnFacts = fixture.facts().stream()
			.filter(fact -> fact.key().parentOccurrence() != fixture.secondLeaf()).toList();
		NativePlacementContinuity revised = fixture.continuity().nextRevision(withdrawnFacts);
		NativePlacementContinuity fresh = fixture.continuity()
			.nextOwnerRevision(fixture.secondLeaf(), List.of());
		Assert.assertNull(revised.fixedValueMapPool(fixture.valueMap()));
		Assert.assertNull(revised.proveCandidate(fixture.valueMap(), fixture.expectedPool()));
		Assert.assertEquals(fresh.proveCandidate(fixture.valueMap(), fixture.expectedPool()),
			revised.proveCandidate(fixture.valueMap(), fixture.expectedPool()));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void relocationBinderCombinesFixedValueMapDirectInputWithOtherRelocation() throws Exception {
		Node leaf = node("binder-leaf", 30);
		Node alias = node("binder-alias", 31);
		Node uploaded = new Node(key("binder-uploaded"), NodeKind.OPERATION,
			version("binder-uploaded", 32), true, List.of(
				new PlacementState(ExecType.CP, FederatedOutput.LOUT, FType.ROW, false)),
			List.of(), List.of());
		Node consumer = node("binder-consumer", 33);
		DurableAnchorKey targetPool = pool("binder-pool", "a:9000", "b:9000");

		CandidateRuleKey leafRule = rule(leaf, 0);
		CandidateEmissionRealization leafRealization = durable(targetPool);
		CandidateRuleKey aliasRule = rule(alias, 1);
		CandidateEmissionRealization aliasRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "binder-map", List.of(clause(CandidateRealizationInputBinding.logicalTransient(
				0, CandidateRealizationReference.of(leafRule, leafRealization)))));
		PlacementState localState = uploaded.legalAlternatives().get(0);
		PlacementEmissionState localEmission = new PlacementEmissionState(localState, false);
		CandidateRuleKey uploadedRule = rule(uploaded, 0);
		CandidateEmissionRealization uploadedRealization =
			CandidateEmissionRealization.local(localEmission, List.of(), List.of());
		CandidateRuleKey consumerRule = rule(consumer, 2);
		CandidateEmissionRealization consumerBase = CandidateEmissionRealization.valueMap(
			EMISSION, "consumer-base", List.of(new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0,
					CandidateRealizationReference.of(aliasRule, aliasRealization)),
				CandidateRealizationInputBinding.direct(1,
					CandidateRealizationReference.of(uploadedRule, uploadedRealization))))));
		List<CandidateRuleFact> facts = List.of(fact(leafRule, leafRealization),
			fact(aliasRule, aliasRealization), fact(uploadedRule,
				new CandidateEmissionFact(localEmission, null, null, List.of(uploadedRealization))),
			fact(consumerRule, consumerBase));
		List<Node> nodes = List.of(leaf, alias, uploaded, consumer);
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(alias.key(), consumer.key(), 0),
			new CompiledInputEdgeFact(uploaded.key(), consumer.key(), 1));
		RelocationActionKey actionKey = new RelocationActionKey(uploaded.valueVersion(), FED_FOUT,
			FType.ROW, targetPool, "scope", List.of(consumer.key()));
		NeutralPlacementGraph.RelocationAction action = new NeutralPlacementGraph.RelocationAction(
			actionKey, List.of(new ObligationKey(consumer.key(), 1, uploaded.valueVersion(),
				FED_FOUT, actionKey, "scope")));
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> origins = new java.util.IdentityHashMap<>();
		Map<org.apache.sysds.hops.Hop,NodeShapeFact> shapes = new java.util.IdentityHashMap<>();
		for(Node node : nodes) {
			DataOp hop = hop(node.key().canonicalSourceOrigin());
			origins.put(node.key(), hop);
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		}

		PlacementRelationClosure closure = PlacementBuilderTestAccess.relationClosure(
			new NeutralPlacementGraphBuilder());
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindRelocationCandidateRealizationsMeasured", List.class, List.class, List.class,
			List.class, Map.class, Map.class);
		bind.setAccessible(true);
		List<CandidateRuleFact> rebound = (List<CandidateRuleFact>)bind.invoke(closure,
			facts, nodes, edges, List.of(action), origins, shapes);
		List<CandidateRealizationSupportClause> relocationClauses = rebound.stream()
			.filter(fact -> fact.key().equals(consumerRule))
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.filter(support -> support.inputBindings().stream().anyMatch(binding ->
				binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION))
			.toList();
		Assert.assertFalse("the fixed VALUE_MAP input and the independent upload must form a product",
			relocationClauses.isEmpty());
		Assert.assertTrue(relocationClauses.stream().anyMatch(support ->
			support.inputBindings().stream().anyMatch(binding -> binding.inputPosition() == 0
				&& binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT
				&& binding.source().equals(CandidateRealizationReference.of(aliasRule, aliasRealization)))
			&& support.inputBindings().stream().anyMatch(binding -> binding.inputPosition() == 1
				&& binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
				&& binding.relocationAction().equals(actionKey))));
	}

	private record Fixture(NativePlacementContinuity continuity,
		CandidateRealizationReference valueMap, DurableAnchorKey expectedPool,
		CompiledHopKey secondLeaf, List<CandidateRuleFact> facts) { }
	private record CycleFixture(NativePlacementContinuity continuity,
		CandidateRealizationReference root) { }
	private record MixedLayoutFixture(NativePlacementContinuity.FixedValueMapPool fixedPool,
		List<String> canonicalSourceOrder, NativePlacementContinuity continuity,
		CandidateRealizationReference reference, CandidateRealizationSupportClause clause) { }

	private static CycleFixture cycleFixture(boolean separateClauses) {
		Node leaf = node("cycle-leaf", 10);
		Node alias = node("cycle-alias", 11);
		CandidateRuleKey leafRule = rule(leaf, 0);
		CandidateEmissionRealization leafRealization = durable(pool(
			"cycle-pool", "a:9000", "b:9000"));
		CandidateRuleKey aliasRule = rule(alias, 2);
		PlacementRealizationKey aliasKey = PlacementRealizationKey.valueMap(EMISSION, "cycle-map");
		CandidateRealizationReference aliasReference =
			new CandidateRealizationReference(aliasRule, aliasKey);
		CandidateRealizationInputBinding self =
			CandidateRealizationInputBinding.logicalTransient(0, aliasReference);
		CandidateRealizationInputBinding exact = CandidateRealizationInputBinding.logicalTransient(1,
			CandidateRealizationReference.of(leafRule, leafRealization));
		List<CandidateRealizationSupportClause> clauses = separateClauses
			? List.of(clause(self), clause(exact))
			: List.of(new CandidateRealizationSupportClause(List.of(), List.of(self, exact)));
		CandidateEmissionRealization aliasRealization =
			new CandidateEmissionRealization(aliasKey, clauses);
		return new CycleFixture(continuity(List.of(leaf, alias), List.of(
			fact(leafRule, leafRealization), fact(aliasRule, aliasRealization))), aliasReference);
	}

	private static MixedLayoutFixture mixedLayoutPool(
		boolean dynamicFirst) {
		Node a = node("layout-a", 20);
		Node b = node("layout-b", 21);
		Node c = node("layout-c", 22);
		Node alias = node("layout-alias", 23);
		DurableAnchorKey poolA = pool("layout-a", "a:9000", "b:9000", 4);
		DurableAnchorKey poolB = pool("layout-b", "a:9000", "b:9000", 5);
		CandidateRuleKey aRule = rule(a, 0), bRule = rule(b, 0), cRule = rule(c, 0);
		CandidateEmissionRealization aRealization = durable(poolA);
		CandidateEmissionRealization bRealization = durable(poolB);
		CandidateEmissionRealization cRealization = CandidateEmissionRealization
			.nativeLineageDynamicLayout(EMISSION, "dynamic-layout", poolA,
				List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
					c.key(), poolA.normalizedSignature())), List.of());
		CandidateRealizationInputBinding aBinding = CandidateRealizationInputBinding.logicalTransient(
			dynamicFirst ? 1 : 0, CandidateRealizationReference.of(aRule, aRealization));
		CandidateRealizationInputBinding bBinding = CandidateRealizationInputBinding.logicalTransient(
			dynamicFirst ? 2 : 1, CandidateRealizationReference.of(bRule, bRealization));
		CandidateRealizationInputBinding cBinding = CandidateRealizationInputBinding.logicalTransient(
			dynamicFirst ? 0 : 2, CandidateRealizationReference.of(cRule, cRealization));
		CandidateRuleKey aliasRule = rule(alias, 3);
		CandidateEmissionRealization aliasRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "mixed-layout-map", List.of(
				new CandidateRealizationSupportClause(List.of(), List.of(aBinding, bBinding, cBinding))));
		List<CandidateRuleFact> facts = List.of(fact(aRule, aRealization),
			fact(bRule, bRealization), fact(cRule, cRealization), fact(aliasRule, aliasRealization));
		List<String> canonicalSourceOrder = aliasRealization.supportClauses().get(0).inputBindings()
			.stream().map(binding -> binding.source().rule() == aRule ? "a"
				: binding.source().rule() == bRule ? "b" : "c").toList();
		NativePlacementContinuity continuity = continuity(List.of(a, b, c, alias), facts);
		CandidateRealizationReference reference =
			CandidateRealizationReference.of(aliasRule, aliasRealization);
		return new MixedLayoutFixture(continuity.fixedValueMapPool(reference), canonicalSourceOrder,
			continuity, reference, aliasRealization.supportClauses().get(0));
	}

	private static Fixture fixture(boolean mixedPools) {
		Node first = node("first", 0);
		Node second = node("second", 1);
		Node alias = node("alias", 2);
		DurableAnchorKey poolA = pool("pool-a", "a:9000", "b:9000");
		DurableAnchorKey poolB = mixedPools
			? pool("pool-b", "a:9000", "c:9000") : poolA;
		CandidateRuleKey firstRule = rule(first, 0);
		CandidateRuleKey secondRule = rule(second, 0);
		CandidateEmissionRealization firstRealization = durable(poolA);
		CandidateEmissionRealization secondRealization = durable(poolB);
		CandidateRuleKey aliasRule = rule(alias, 1);
		CandidateEmissionRealization aliasRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "all-clauses", List.of(
				clause(CandidateRealizationInputBinding.logicalTransient(0,
					CandidateRealizationReference.of(firstRule, firstRealization))),
				clause(CandidateRealizationInputBinding.logicalTransient(0,
					CandidateRealizationReference.of(secondRule, secondRealization)))));
		List<CandidateRuleFact> facts = List.of(
			fact(firstRule, firstRealization), fact(secondRule, secondRealization),
			fact(aliasRule, aliasRealization));
		NativePlacementContinuity continuity = continuity(List.of(first, second, alias), facts);
		return new Fixture(continuity,
			CandidateRealizationReference.of(aliasRule, aliasRealization), poolA, second.key(), facts);
	}

	private static NativePlacementContinuity continuity(List<Node> nodes,
		List<CandidateRuleFact> facts) {
		Map<CompiledHopKey,Node> nodeMap = new java.util.IdentityHashMap<>();
		Map<CompiledHopKey,org.apache.sysds.hops.Hop> hops = new java.util.IdentityHashMap<>();
		for(Node node : nodes) {
			nodeMap.put(node.key(), node);
			hops.put(node.key(), new DataOp(node.key().canonicalSourceOrigin(),
				DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				node.key().canonicalSourceOrigin(), 8, 2, 16, 1000));
		}
		return new NativePlacementContinuity(nodeMap, hops, facts, List.of(), Map.of());
	}

	private static DataOp hop(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 8, 2, 16, 1000);
	}

	private static CandidateEmissionRealization durable(DurableAnchorKey pool) {
		return CandidateEmissionRealization.durable(EMISSION, pool, List.of(), List.of());
	}

	private static CandidateRealizationSupportClause clause(
		CandidateRealizationInputBinding binding) {
		return new CandidateRealizationSupportClause(List.of(), List.of(binding));
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return fact(rule, new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(realization)));
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule,
		CandidateEmissionFact emission) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixed-value-map", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixed-value-map", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(rule.orderedInputs().stream()
				.map(CandidateInputState::fType).toList(), ""), List.of(emission), "");
	}

	private static CandidateRuleKey rule(Node node, int inputs) {
		return new CandidateRuleKey(node.key(),
			java.util.Collections.nCopies(inputs, CandidateInputState.present(FType.ROW)));
	}

	private static Node node(String name, int ordinal) {
		ControlRegionKey region = new ControlRegionKey(
			"fixed-value-map", "main", List.of("main/0"), "main", "compiled");
		CompiledHopKey key = new CompiledHopKey("fixed-value-map", "main", "main", "compiled",
			region, name, name);
		return new Node(key, NodeKind.OPERATION,
			new ValueVersionKey("fixed-value-map", name, region, ordinal,
				VersionKind.ORDINARY, List.of()), true, List.of(FED_FOUT), List.of(), List.of());
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"fixed-value-map", "main", List.of("main/0"), "main", "compiled");
		return new CompiledHopKey("fixed-value-map", "main", "main", "compiled",
			region, name, name);
	}

	private static ValueVersionKey version(String name, int ordinal) {
		return new ValueVersionKey("fixed-value-map", name, key(name).controlRegion(), ordinal,
			VersionKind.ORDINARY, List.of());
	}

	private static DurableAnchorKey pool(String id, String first, String second) {
		return pool(id, first, second, 4);
	}

	private static DurableAnchorKey pool(String id, String first, String second, long split) {
		return pool(id, first, second, split, 2);
	}

	private static DurableAnchorKey pool(String id, String first, String second, long split,
		long columns) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition(first, List.of(0L, 0L), List.of(split, columns)),
			new AnchorPartition(second, List.of(split, 0L), List.of(8L, columns))));
	}

	private static DurableAnchorKey broadcastPool(String id, String first, String second) {
		return new DurableAnchorKey(id, FType.BROADCAST, List.of(
			new AnchorPartition(first, List.of(0L, 0L), List.of(1L, 1L)),
			new AnchorPartition(second, List.of(0L, 0L), List.of(1L, 1L))));
	}
}
