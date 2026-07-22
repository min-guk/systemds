/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationEndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityFact;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

import sun.misc.Unsafe;

/** Exact neutral-graph contract for same-FType relocations across distinct durable anchors. */
public class MinStExactAnchorRelocationIdentityRedTest {
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);
	private static final CompiledHopKey PRODUCER = key("producer");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final CompiledHopKey CONSUMER_B = key("consumer-b");
	private static final ValueVersionKey PRODUCER_VERSION = version("producer-version");
	private static final ValueVersionKey CONSUMER_VERSION = version("consumer-version");
	private static final ValueVersionKey CONSUMER_B_VERSION = version("consumer-b-version");
	private static final DurableAnchorKey ANCHOR_A = anchor("anchor-a", "worker-a");
	private static final DurableAnchorKey ANCHOR_B = anchor("anchor-b", "worker-b");

	@Test
	public void equalFTypeAtDifferentAnchorStillRequiresExactUpload() throws Exception {
		CompiledInputEdgeFact input = CampaignBPlacementAnalysisFixtureBridge
			.compiledInputEdge(PRODUCER, CONSUMER, 0);
		RelocationAction crossAnchor = action(ANCHOR_B);
		NeutralPlacementGraph graph = graph(crossAnchor);
		Map<CompiledHopKey, PlacementState> selected = Map.of(PRODUCER, FED_ROW, CONSUMER, FED_ROW);

		assertExactNeutralOwnership(graph, input, crossAnchor);
		Assert.assertEquals("P4_CROSS_ANCHOR_FIXTURE_REQUIRES_EQUAL_FTYPE",
			ANCHOR_A.fType(), ANCHOR_B.fType());
		Assert.assertNotEquals("P4_CROSS_ANCHOR_FIXTURE_REQUIRES_DISTINCT_DURABLE_KEYS",
			ANCHOR_A, ANCHOR_B);
		Assert.assertSame("P4_CROSS_ANCHOR_ACTION_RETAINS_EXACT_REQUIRED_ANCHOR",
			ANCHOR_B, crossAnchor.key().durableAnchor());
		Assert.assertSame("P4_CROSS_ANCHOR_SOURCE_SELECTION_IS_EXACT",
			FED_ROW, selected.get(PRODUCER));
		Assert.assertSame("P4_CROSS_ANCHOR_CONSUMER_SELECTION_IS_EXACT",
			FED_ROW, selected.get(CONSUMER));
		Assert.assertFalse("P4_MINST_MUST_NOT_TREAT_EQUAL_FTYPE_AS_EXACT_ANCHOR_COMPATIBILITY",
			MinStExactCostFactsProducer.hasExactCompatibleDurableSource(
				CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(graph), uploadGroup()));
		Assert.assertEquals("P4_CROSS_ANCHOR_REQUIRES_ONE_UPLOAD", 1L,
			graph.relocationActions().stream().filter(action -> graph.isRelocationActive(action, selected)).count());
		MinStExactSelection selection = MinStExactSelector.select(facts(graph, uploadGroup()));
		Assert.assertEquals("P4_MINST_CROSS_ANCHOR_EMITS_ONE_EXACT_UPLOAD_RECEIPT|source="
			+ selection.sourcePartitionNodeIds() + "|objective=" + selection.objectiveBits(), 1L,
			uploadReceipts(selection, CONSUMER));
		Assert.assertEquals("P4_MINST_CROSS_ANCHOR_OBJECTIVE_INCLUDES_ONE_UPLOAD_PRICE",
			Double.doubleToRawLongBits(2.0), selection.objectiveBits());
	}

	@Test
	public void equalFTypeAtExactSameAnchorSuppressesRedundantUpload() throws Exception {
		CompiledInputEdgeFact input = CampaignBPlacementAnalysisFixtureBridge
			.compiledInputEdge(PRODUCER, CONSUMER, 0);
		RelocationAction sameAnchor = action(ANCHOR_A);
		NeutralPlacementGraph graph = graph(sameAnchor);
		Map<CompiledHopKey, PlacementState> selected = Map.of(PRODUCER, FED_ROW, CONSUMER, FED_ROW);

		assertExactNeutralOwnership(graph, input, sameAnchor);
		Assert.assertSame("P4_SAME_ANCHOR_ACTION_RETAINS_EXACT_SOURCE_ANCHOR",
			ANCHOR_A, sameAnchor.key().durableAnchor());
		Assert.assertTrue("P4_MINST_RECOGNIZES_EXACT_SAME_ANCHOR_COMPATIBILITY",
			MinStExactCostFactsProducer.hasExactCompatibleDurableSource(
				CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(graph), uploadGroup()));
		Assert.assertEquals("P4_SAME_ANCHOR_SUPPRESSES_REDUNDANT_UPLOAD", 0L,
			graph.relocationActions().stream().filter(action -> graph.isRelocationActive(action, selected)).count());
		MinStExactSelection selection = MinStExactSelector.select(facts(graph, uploadGroup()));
		Assert.assertEquals("P4_MINST_SAME_ANCHOR_EMITS_ZERO_UPLOAD_RECEIPTS", 0L,
			uploadReceipts(selection, CONSUMER));
		Assert.assertEquals("P4_MINST_SAME_ANCHOR_OBJECTIVE_EXCLUDES_UPLOAD_PRICE",
			Double.doubleToRawLongBits(1.0), selection.objectiveBits());
	}

	@Test
	public void groupedSameAndCrossAnchorEndpointsRetainExactlyOneRequiredUpload() throws Exception {
		RelocationAction sameAnchor = action(ANCHOR_A, CONSUMER);
		RelocationAction crossAnchor = action(ANCHOR_B, CONSUMER_B);
		NeutralPlacementGraph graph = groupedGraph(sameAnchor, crossAnchor);
		AuxiliaryGroupFact group = groupedUploadGroup();
		Map<CompiledHopKey, PlacementState> selected =
			Map.of(PRODUCER, FED_ROW, CONSUMER, FED_ROW, CONSUMER_B, FED_ROW);

		Assert.assertEquals("P4_GROUPED_UPLOAD_HAS_TWO_EXACT_ENDPOINTS", 2,
			group.endpointsInCanonicalOrder().size());
		Assert.assertFalse("P4_GROUPED_A_AND_B_IS_NOT_FULLY_COMPATIBLE_WITH_SOURCE_A",
			MinStExactCostFactsProducer.hasExactCompatibleDurableSource(
				CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(graph), group));
		Assert.assertEquals("P4_GROUPED_A_AND_B_RETAINS_ONLY_THE_CROSS_ANCHOR_UPLOAD", 1L,
			graph.relocationActions().stream().filter(action -> graph.isRelocationActive(action, selected)).count());
		Assert.assertSame("P4_GROUPED_CROSS_ACTION_RETAINS_EXACT_ANCHOR_B",
			ANCHOR_B, crossAnchor.key().durableAnchor());
		Assert.assertTrue("P4_BUILDER_ANCHOR_CARDINALITY_IS_ZERO_OR_ONE",
			graph.nodes().stream().allMatch(node -> node.anchors().size() <= 1));
		Assert.assertEquals("P4_GROUPED_NEUTRAL_GRAPH_HAS_COMPILED_OCCURRENCES_ONLY",
			List.of(PRODUCER, CONSUMER, CONSUMER_B).stream().sorted().toList(),
			graph.nodes().stream().map(Node::key).toList());
		MinStExactSelection selection = MinStExactSelector.select(facts(graph, group));
		Assert.assertEquals("P4_MINST_GROUPED_UPLOAD_RETAINS_BOTH_EXACT_ENDPOINT_RECEIPTS|source="
			+ selection.sourcePartitionNodeIds() + "|objective=" + selection.objectiveBits(), 2L,
			selection.obligationReceiptsInOrder().stream().filter(receipt ->
				receipt.direction() == Direction.UPLOAD && receipt.producerKey() == PRODUCER).count());
		Assert.assertEquals("P4_MINST_GROUPED_UPLOAD_HAS_ONE_RECEIPT_FOR_CROSS_ANCHOR_B", 1L,
			uploadReceipts(selection, CONSUMER_B));
		Assert.assertEquals("P4_MINST_GROUPED_UPLOAD_PRICES_SHARED_CONVERSION_EXACTLY_ONCE",
			Double.doubleToRawLongBits(2.0), selection.objectiveBits());
	}

	private static long uploadReceipts(MinStExactSelection selection, CompiledHopKey consumer) {
		return selection.obligationReceiptsInOrder().stream().filter(receipt ->
			receipt.direction() == Direction.UPLOAD && receipt.producerKey() == PRODUCER
				&& receipt.consumerKey() == consumer && receipt.inputPosition() == 0).count();
	}

	private static AuxiliaryGroupFact uploadGroup() {
		return new AuxiliaryGroupFact(-3L, Direction.UPLOAD, PRODUCER, 1L, FType.ROW,
			Double.doubleToRawLongBits(1.0), List.of(new EndpointFact(PRODUCER, CONSUMER, 0, 2L,
				Double.doubleToRawLongBits(1.0))));
	}

	private static AuxiliaryGroupFact groupedUploadGroup() {
		long price = Double.doubleToRawLongBits(1.0);
		return new AuxiliaryGroupFact(-3L, Direction.UPLOAD, PRODUCER, 1L, FType.ROW, price,
			List.of(new EndpointFact(PRODUCER, CONSUMER, 0, 2L, price),
				new EndpointFact(PRODUCER, CONSUMER_B, 0, 3L, price)));
	}

	private static MinStExactCostFacts facts(NeutralPlacementGraph graph,
		AuxiliaryGroupFact group) throws Exception {
		List<ObligationFact> obligations = graph.relocationActions().stream().map(action ->
			new ObligationFact(action.normalizedSignature(), action.obligations().stream().map(obligation ->
				new ObligationEndpointFact(obligation.consumer(), obligation.inputPosition(),
					obligation.requiredPlacement())).toList())).toList();
		PlacementAnalysis analysis = allocate(PlacementAnalysis.class);
		set(analysis, "graph", graph);
		MinStExactCostFacts facts = allocate(MinStExactCostFacts.class);
		set(facts, "analysis", analysis);
		set(facts, "analysisFingerprint", "task16-analysis");
		set(facts, "orderedScope", List.of(PRODUCER));
		set(facts, "decisions", List.<DecisionFact>of());
		set(facts, "membershipRepresentatives", List.of());
		List<DirectedEdgeFact> edges = new ArrayList<>();
		edges.add(edge(-1L, group.auxiliaryNodeId(), 5.0));
		edges.add(edge(-1L, group.producerPlacementNodeId(), 5.0));
		edges.add(edge(-1L, -2L, 1.0));
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder())
			edges.add(edge(endpoint.consumerComputeNodeId(), -2L, 5.0));
		edges.addAll(productionPriceEdges(analysis, group));
		set(facts, "edges", List.copyOf(edges));
		set(facts, "groups", List.of(group));
		set(facts, "transferAuthorities", transferAuthorities(graph, group, obligations));
		set(facts, "obligations", obligations);
		set(facts, "derivationFingerprint", "task16-derivation");
		return facts;
	}

	@SuppressWarnings("unchecked")
	private static List<DirectedEdgeFact> productionPriceEdges(PlacementAnalysis analysis,
		AuxiliaryGroupFact group) throws Exception {
		Class<?> accumulatorType = List.of(MinStExactCostFactsProducer.class.getDeclaredClasses()).stream()
			.filter(type -> type.getSimpleName().equals("EdgeAccumulator")).findFirst().orElseThrow();
		var constructor = accumulatorType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object accumulator = constructor.newInstance();
		Method add = MinStExactCostFactsProducer.class.getDeclaredMethod("addGroupEdges",
			PlacementAnalysis.class, AuxiliaryGroupFact.class, accumulatorType);
		add.setAccessible(true);
		add.invoke(null, analysis, group, accumulator);
		Method freeze = accumulatorType.getDeclaredMethod("freeze");
		freeze.setAccessible(true);
		List<DirectedEdgeFact> edges = (List<DirectedEdgeFact>)freeze.invoke(accumulator);
		List<DirectedEdgeFact> prices = edges.stream().filter(edge -> edge.contributionsInDerivationOrder()
			.stream().anyMatch(contribution -> contribution.kind() == ContributionKind.PRICE_UPLOAD_OR)).toList();
		Assert.assertEquals("P4_PRODUCTION_DERIVES_ONE_SHARED_UPLOAD_PRICE_EDGE", 1, prices.size());
		DirectedEdgeFact price = prices.get(0);
		Assert.assertEquals("P4_PRODUCTION_UPLOAD_PRICE_EDGE_STARTS_AT_SHARED_AUXILIARY",
			group.auxiliaryNodeId(), price.fromNodeId());
		Assert.assertEquals("P4_PRODUCTION_UPLOAD_PRICE_EDGE_HAS_EXACT_SHARED_PRICE",
			group.priceBits(), price.capacityBits());
		long expectedTarget = MinStExactCostFactsProducer.hasExactCompatibleDurableSource(analysis, group)
			? group.producerPlacementNodeId() : -2L;
		Assert.assertEquals("P4_PRODUCTION_UPLOAD_PRICE_EDGE_HAS_EXACT_ANCHOR_POLARITY",
			expectedTarget, price.toNodeId());
		Assert.assertEquals("P4_PRODUCTION_UPLOAD_PRICE_EDGE_RETAINS_PRICE_AUTHORITY", 1L,
			price.contributionsInDerivationOrder().stream()
				.filter(contribution -> contribution.kind() == ContributionKind.PRICE_UPLOAD_OR).count());
		return prices;
	}

	private static List<TransferAuthorityFact> transferAuthorities(NeutralPlacementGraph graph,
		AuxiliaryGroupFact group, List<ObligationFact> obligationFacts) {
		List<TransferAuthorityFact> result = new ArrayList<>();
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			for(RelocationAction action : graph.relocationActions()) {
				for(ObligationKey obligation : action.obligations()) {
					if(action.key().sourceValueVersion() != PRODUCER_VERSION
						|| obligation.consumer() != endpoint.consumerKey()
						|| obligation.inputPosition() != endpoint.inputPosition()
						|| !published(obligationFacts, action, obligation))
						continue;
					result.add(TransferAuthorityFact.relocation(group, endpoint,
						CampaignBPlacementAnalysisFixtureBridge.compiledInputEdge(
							endpoint.producerKey(), endpoint.consumerKey(), endpoint.inputPosition()),
						PRODUCER_VERSION, action, obligation));
				}
			}
		}
		return List.copyOf(result);
	}

	private static boolean published(List<ObligationFact> facts, RelocationAction action,
		ObligationKey obligation) {
		return facts.stream().anyMatch(fact -> fact.actionSignature().equals(action.normalizedSignature())
			&& fact.endpointsInCanonicalOrder().stream().anyMatch(endpoint ->
				endpoint.consumerKey() == obligation.consumer()
					&& endpoint.inputPosition() == obligation.inputPosition()
					&& endpoint.requiredPlacement() == obligation.requiredPlacement()));
	}

	private static DirectedEdgeFact edge(long from, long to, double capacity) {
		return new DirectedEdgeFact(from, to, Double.doubleToRawLongBits(capacity), List.of());
	}

	private static void assertExactNeutralOwnership(NeutralPlacementGraph graph,
		CompiledInputEdgeFact input, RelocationAction action) {
		Assert.assertEquals("P4_NEUTRAL_GRAPH_CONTAINS_ONLY_COMPILED_HOP_OWNERS", 2, graph.nodes().size());
		Assert.assertEquals("P4_NEUTRAL_GRAPH_EXCLUDES_DP_CLONES_AND_MINST_AUXILIARIES",
			List.of(NodeKind.OPERATION, NodeKind.OPERATION),
			graph.nodes().stream().map(Node::kind).toList());
		Assert.assertSame("P4_EXACT_INPUT_PRODUCER_IDENTITY", PRODUCER, input.producer());
		Assert.assertSame("P4_EXACT_INPUT_CONSUMER_IDENTITY", CONSUMER, input.consumer());
		Assert.assertEquals("P4_EXACT_INPUT_POSITION", 0, input.inputPosition());
		Assert.assertSame("P4_ACTION_SOURCE_VALUE_IDENTITY", PRODUCER_VERSION,
			action.key().sourceValueVersion());
		Assert.assertEquals("P4_ACTION_HAS_ONE_EXACT_COMPILED_INPUT_OBLIGATION", 1,
			action.obligations().size());
		ObligationKey obligation = action.obligations().get(0);
		Assert.assertSame("P4_OBLIGATION_CONSUMER_IS_EXACT_COMPILED_INPUT_OWNER",
			input.consumer(), obligation.consumer());
		Assert.assertEquals("P4_OBLIGATION_POSITION_IS_EXACT_COMPILED_INPUT_POSITION",
			input.inputPosition(), obligation.inputPosition());
		Assert.assertSame("P4_OBLIGATION_REQUIRED_SELECTION_IS_EXACT",
			FED_ROW, obligation.requiredPlacement());
		Assert.assertSame("P4_OBLIGATION_RETAINS_EXACT_ACTION_IDENTITY",
			action.key(), obligation.relocationAction());
	}

	private static NeutralPlacementGraph graph(RelocationAction action) {
		Node producer = new Node(PRODUCER, NodeKind.OPERATION, PRODUCER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumer = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(action.key().durableAnchor()));
		return new NeutralPlacementGraph(List.of(producer, consumer), List.of(), List.of(action));
	}

	private static NeutralPlacementGraph groupedGraph(RelocationAction sameAnchor,
		RelocationAction crossAnchor) {
		Node producer = new Node(PRODUCER, NodeKind.OPERATION, PRODUCER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumerA = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumerB = new Node(CONSUMER_B, NodeKind.OPERATION, CONSUMER_B_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_B));
		return new NeutralPlacementGraph(List.of(producer, consumerA, consumerB), List.of(),
			List.of(sameAnchor, crossAnchor));
	}

	private static RelocationAction action(DurableAnchorKey targetAnchor) {
		return action(targetAnchor, CONSUMER);
	}

	private static RelocationAction action(DurableAnchorKey targetAnchor, CompiledHopKey consumer) {
		RelocationActionKey key = new RelocationActionKey(PRODUCER_VERSION, FED_ROW, targetAnchor,
			"scope", List.of(consumer));
		return new RelocationAction(key, List.of(new ObligationKey(consumer, 0, PRODUCER_VERSION,
			FED_ROW, key, "scope")));
	}

	private static DurableAnchorKey anchor(String placementId, String worker) {
		return new DurableAnchorKey(placementId, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = region();
		return new CompiledHopKey("program", "ns", "call", "rc", region, id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("program", id, region(), 0, VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("program", "ns", List.of("main/0"), "call", "rc");
	}

	private static void set(Object target, String field, Object value) throws Exception {
		Field declared = target.getClass().getDeclaredField(field);
		declared.setAccessible(true);
		declared.set(target, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (T)((Unsafe)field.get(null)).allocateInstance(type);
	}
}
