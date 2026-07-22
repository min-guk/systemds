/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityKind;
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

/** RED contract preventing relocation authority from shadowing a DOWNLOAD's durable source. */
public class MinStDownloadAuthorityAmbiguityRedTest {
	private static final PlacementState CP =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);
	private static final CompiledHopKey PRODUCER = key("producer");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final ValueVersionKey PRODUCER_VERSION = version("producer-version");
	private static final ValueVersionKey CONSUMER_VERSION = version("consumer-version");
	private static final DurableAnchorKey ANCHOR_A = anchor("anchor-a", "worker-a");
	private static final DurableAnchorKey ANCHOR_B = anchor("anchor-b", "worker-b");

	@Test
	public void downloadPublishesOnlyExactDurableSourceAuthority() throws Exception {
		Fixture fixture = fixture();
		AuxiliaryGroupFact download = group(Direction.DOWNLOAD);
		List<TransferAuthorityFact> authorities = productionAuthorities(fixture.analysis, download);

		assertExactFixture(fixture);
		Assert.assertEquals("P4_DOWNLOAD_HAS_ONE_EXACT_DURABLE_SOURCE_AUTHORITY", 1L,
			count(authorities, TransferAuthorityKind.DURABLE_SOURCE));
		Assert.assertEquals("P4_DOWNLOAD_MUST_NOT_PUBLISH_RELOCATION_AUTHORITY", 0L,
			count(authorities, TransferAuthorityKind.RELOCATION_OBLIGATION));
		Assert.assertEquals("P4_DOWNLOAD_TRANSFER_AUTHORITY_IS_UNAMBIGUOUS", 1, authorities.size());
		TransferAuthorityFact authority = authorities.get(0);
		Assert.assertSame("P4_DOWNLOAD_AUTHORITY_RETAINS_EXACT_INPUT", fixture.input, authority.inputEdge());
		Assert.assertSame("P4_DOWNLOAD_AUTHORITY_RETAINS_EXACT_ANCHOR_A",
			ANCHOR_A, authority.independentAnchorOrNull());
		Assert.assertSame("P4_DOWNLOAD_AUTHORITY_RETAINS_EXACT_FOUT_STATE",
			FED_ROW, authority.requiredPlacement());
		validateProductionOwnership(fixture.analysis, download, authorities);
		MinStExactCostFacts facts = facts(fixture.analysis, download, authorities);
		MinStExactSelection selection = MinStExactSelector.select(facts);
		Assert.assertEquals("P4_DOWNLOAD_SELECTOR_EMITS_ONE_EXACT_RECEIPT", 1,
			selection.obligationReceiptsInOrder().size());
		Assert.assertSame("P4_DOWNLOAD_RECEIPT_RETAINS_EXACT_PRODUCER", PRODUCER,
			selection.obligationReceiptsInOrder().get(0).producerKey());
		Assert.assertSame("P4_DOWNLOAD_RECEIPT_RETAINS_EXACT_CONSUMER", CONSUMER,
			selection.obligationReceiptsInOrder().get(0).consumerKey());
		Assert.assertEquals("P4_DOWNLOAD_RECEIPT_RETAINS_EXACT_INPUT_POSITION", 0,
			selection.obligationReceiptsInOrder().get(0).inputPosition());
		validateProjectorReceipt(facts, selection);
	}

	@Test
	public void uploadStillPrefersExactRelocationOverIndependentFallback() throws Exception {
		Fixture fixture = fixture();
		List<TransferAuthorityFact> authorities = productionAuthorities(fixture.analysis, group(Direction.UPLOAD));

		Assert.assertEquals("P4_UPLOAD_RETAINS_ONE_EXACT_RELOCATION_AUTHORITY", 1L,
			count(authorities, TransferAuthorityKind.RELOCATION_OBLIGATION));
		Assert.assertEquals("P4_UPLOAD_SKIPS_INDEPENDENT_FALLBACK_WHEN_RELOCATION_EXISTS", 0L,
			count(authorities, TransferAuthorityKind.INDEPENDENT_ANCHOR));
		Assert.assertEquals("P4_UPLOAD_TRANSFER_AUTHORITY_IS_UNAMBIGUOUS", 1, authorities.size());
		TransferAuthorityFact authority = authorities.get(0);
		Assert.assertSame("P4_UPLOAD_AUTHORITY_RETAINS_EXACT_ACTION",
			fixture.action, authority.actionOrNull());
		Assert.assertSame("P4_UPLOAD_AUTHORITY_RETAINS_EXACT_ANCHOR_B",
			ANCHOR_B, authority.actionOrNull().key().durableAnchor());
	}

	private static long count(List<TransferAuthorityFact> authorities, TransferAuthorityKind kind) {
		return authorities.stream().filter(authority -> authority.authorityKind() == kind).count();
	}

	private static void validateProductionOwnership(PlacementAnalysis analysis,
		AuxiliaryGroupFact group, List<TransferAuthorityFact> authorities) throws Exception {
		Method method = MinStExactCostFactsProducer.class.getDeclaredMethod(
			"validateTransferAuthorityOwnership", PlacementAnalysis.class, List.class, List.class);
		method.setAccessible(true);
		method.invoke(null, analysis, List.of(group), authorities);
	}

	private static void validateProjectorReceipt(MinStExactCostFacts facts,
		MinStExactSelection selection) throws Exception {
		Method method = MinStExactPlacementProjector.class.getDeclaredMethod("validateObligation",
			MinStExactCostFacts.class, List.class, MinStExactSelection.ObligationReceipt.class);
		method.setAccessible(true);
		Object validated = method.invoke(null, facts, selection.sourcePartitionNodeIds(),
			selection.obligationReceiptsInOrder().get(0));
		Assert.assertNotNull("P4_DOWNLOAD_PROJECTOR_ACCEPTS_EXACT_DURABLE_SOURCE_RECEIPT", validated);
	}

	private static MinStExactCostFacts facts(PlacementAnalysis analysis, AuxiliaryGroupFact group,
		List<TransferAuthorityFact> authorities) throws Exception {
		MinStExactCostFacts facts = allocate(MinStExactCostFacts.class);
		set(facts, "analysis", analysis);
		set(facts, "analysisFingerprint", analysis.analysisFingerprint());
		set(facts, "orderedScope", List.of());
		set(facts, "decisions", List.<DecisionFact>of());
		set(facts, "membershipRepresentatives", List.of());
		set(facts, "edges", List.of(edge(-1L, group.producerPlacementNodeId(), 5.0),
			edge(group.auxiliaryNodeId(), -2L, 5.0), edge(-1L, -2L, 1.0)));
		set(facts, "groups", List.of(group));
		set(facts, "transferAuthorities", authorities);
		set(facts, "obligations", List.of());
		set(facts, "derivationFingerprint", "task18-download-authority");
		return facts;
	}

	private static DirectedEdgeFact edge(long from, long to, double capacity) {
		return new DirectedEdgeFact(from, to, Double.doubleToRawLongBits(capacity), List.of());
	}

	private static void assertExactFixture(Fixture fixture) {
		Assert.assertSame("P4_DOWNLOAD_EXACT_INPUT_PRODUCER", PRODUCER, fixture.input.producer());
		Assert.assertSame("P4_DOWNLOAD_EXACT_INPUT_CONSUMER", CONSUMER, fixture.input.consumer());
		Assert.assertEquals("P4_DOWNLOAD_EXACT_INPUT_POSITION", 0, fixture.input.inputPosition());
		Assert.assertNotEquals("P4_DOWNLOAD_REQUIRES_DISTINCT_SAME_FTYPE_ANCHORS", ANCHOR_A, ANCHOR_B);
		Assert.assertEquals("P4_DOWNLOAD_ANCHORS_SHARE_FTYPE", ANCHOR_A.fType(), ANCHOR_B.fType());
		Assert.assertEquals("P4_DOWNLOAD_NEUTRAL_GRAPH_HAS_COMPILED_OWNERS_ONLY", 2,
			fixture.analysis.graph().nodes().size());
		Assert.assertTrue("P4_DOWNLOAD_NEUTRAL_GRAPH_EXCLUDES_DP_CLONE_AND_MINST_AUXILIARY",
			fixture.analysis.graph().nodes().stream().allMatch(node -> node.kind() == NodeKind.OPERATION
				&& node.anchors().size() <= 1));
	}

	@SuppressWarnings("unchecked")
	private static List<TransferAuthorityFact> productionAuthorities(PlacementAnalysis analysis,
		AuxiliaryGroupFact group) throws Exception {
		Method method = MinStExactCostFactsProducer.class.getDeclaredMethod("transferAuthorities",
			PlacementAnalysis.class, List.class);
		method.setAccessible(true);
		return (List<TransferAuthorityFact>)method.invoke(null, analysis, List.of(group));
	}

	private static Fixture fixture() throws Exception {
		RelocationActionKey actionKey = new RelocationActionKey(PRODUCER_VERSION, FED_ROW, ANCHOR_B,
			"scope", List.of(CONSUMER));
		ObligationKey obligation = new ObligationKey(CONSUMER, 0, PRODUCER_VERSION, FED_ROW,
			actionKey, "scope");
		RelocationAction action = new RelocationAction(actionKey, List.of(obligation));
		Node producer = new Node(PRODUCER, NodeKind.OPERATION, PRODUCER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumer = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(CP, FED_ROW), List.of(), List.of(ANCHOR_B));
		NeutralPlacementGraph graph = new NeutralPlacementGraph(List.of(producer, consumer),
			List.of(), List.of(action));
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(graph);
		CompiledInputEdgeFact input = CampaignBPlacementAnalysisFixtureBridge
			.compiledInputEdge(PRODUCER, CONSUMER, 0);
		installExactInput(analysis, input);
		return new Fixture(analysis, input, action);
	}

	private static AuxiliaryGroupFact group(Direction direction) {
		long price = Double.doubleToRawLongBits(1.0);
		return new AuxiliaryGroupFact(-3L, direction, PRODUCER, 1L, FType.ROW, price,
			List.of(new EndpointFact(PRODUCER, CONSUMER, 0, 2L, price)));
	}

	private static void installExactInput(PlacementAnalysis analysis, CompiledInputEdgeFact input)
		throws Exception {
		IdentityHashMap<CompiledHopKey,Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>>> index =
			new IdentityHashMap<>();
		IdentityHashMap<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> consumers = new IdentityHashMap<>();
		consumers.put(CONSUMER, Map.of(0, input));
		index.put(PRODUCER, consumers);
		set(analysis, "compiledInputEdgesInCanonicalOrder", List.of(input));
		set(analysis, "inputEdgesByIdentity", index);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (T)((Unsafe)field.get(null)).allocateInstance(type);
	}

	private static DurableAnchorKey anchor(String placement, String worker) {
		return new DurableAnchorKey(placement, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("program", "ns", "call", "rc", region(), id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("program", id, region(), 0, VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("program", "ns", List.of("main/0"), "call", "rc");
	}

	private record Fixture(PlacementAnalysis analysis, CompiledInputEdgeFact input,
		RelocationAction action) { }
}
