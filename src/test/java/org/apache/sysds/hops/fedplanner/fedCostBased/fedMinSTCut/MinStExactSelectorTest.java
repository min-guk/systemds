/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.lang.reflect.Field;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationEndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
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

public class MinStExactSelectorTest {
	private static final PlacementState CP = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FF = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final CompiledHopKey PRODUCER = key("producer");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final CompiledHopKey OTHER = key("other");
	private static final ValueVersionKey PRODUCER_VERSION = version("producer-version");
	private static final ValueVersionKey OTHER_VERSION = version("other-version");

	@Test
	public void selectedReceiptRejectsWrongActionSignature() throws Exception {
		MinStExactCostFacts facts = uploadFacts(List.of(action(PRODUCER_VERSION, FF, CONSUMER)),
			List.of(obligationFact("missing-signature", CONSUMER, 0, FF)));
		assertRejectsCode("MINST_EXACT_AUTHORITY_ACTION_MISSING", () -> MinStExactSelector.select(facts));
	}

	@Test
	public void selectedReceiptRejectsActionSourceValueMismatch() throws Exception {
		RelocationAction wrong = action(OTHER_VERSION, FF, CONSUMER);
		MinStExactCostFacts facts = uploadFacts(List.of(wrong),
			List.of(obligationFact(wrong.normalizedSignature(), CONSUMER, 0, FF)));
		assertRejectsCode("MINST_EXACT_AUTHORITY_ACTION_SOURCE_MISMATCH", () -> MinStExactSelector.select(facts));
	}

	@Test
	public void selectedReceiptRejectsWrongRequiredPlacement() throws Exception {
		RelocationAction action = action(PRODUCER_VERSION, FF, CONSUMER);
		MinStExactCostFacts facts = uploadFacts(List.of(action),
			List.of(obligationFact(action.normalizedSignature(), CONSUMER, 0, CP)));
		assertRejectsCode("MINST_EXACT_OBLIGATION_AUTHORITY_MISSING", () -> MinStExactSelector.select(facts));
	}

	@Test
	public void selectedReceiptRejectsAmbiguousSameSourceActionsForSameEndpoint() throws Exception {
		RelocationAction first = action(PRODUCER_VERSION, FF, CONSUMER, "anchor-a");
		RelocationAction second = action(PRODUCER_VERSION, FF, CONSUMER, "anchor-b");
		MinStExactCostFacts facts = uploadFacts(List.of(first, second), List.of(
			obligationFact(first.normalizedSignature(), CONSUMER, 0, FF),
			obligationFact(second.normalizedSignature(), CONSUMER, 0, FF)));
		assertRejectsCode("MINST_EXACT_OBLIGATION_AUTHORITY_AMBIGUOUS", () -> MinStExactSelector.select(facts));
	}

	@Test
	public void validUnrelatedProducerObligationIsIgnored() throws Exception {
		RelocationAction valid = action(PRODUCER_VERSION, FF, CONSUMER);
		RelocationAction unrelated = action(OTHER_VERSION, FF, OTHER);
		MinStExactCostFacts facts = uploadFacts(List.of(valid, unrelated), List.of(
			obligationFact(valid.normalizedSignature(), CONSUMER, 0, FF),
			obligationFact(unrelated.normalizedSignature(), OTHER, 0, FF)));
		MinStExactSelection selection = MinStExactSelector.select(facts);
		Assert.assertEquals(MinStExactSelection.UNIQUE, selection.tieCertificate());
		Assert.assertEquals(1, selection.obligationReceiptsInOrder().size());
		Assert.assertEquals(CONSUMER, selection.obligationReceiptsInOrder().get(0).consumerKey());
	}

	@Test
	public void selectedDownloadReceiptRejectsWrongRequiredPlacement() throws Exception {
		RelocationAction action = action(PRODUCER_VERSION, FF, CONSUMER);
		MinStExactCostFacts facts = downloadFacts(List.of(action),
			List.of(obligationFact(action.normalizedSignature(), CONSUMER, 0, CP)));
		assertRejectsCode("MINST_EXACT_OBLIGATION_AUTHORITY_MISSING", () -> MinStExactSelector.select(facts));
	}

	@Test
	public void collapsedDuplicatePlacementMembershipDoesNotCreateFalseTie() {
		// Concrete-state duplicates are collapsed to one cut membership before the
		// solver. Exercise that exact solver boundary without forging a facts-owned
		// MembershipRepresentative in an Unsafe selector fixture.
		MinStExactCutSolver.Result result = MinStExactCutSolver.solve(-1L, -2L,
			List.of(new MinStExactCutSolver.Decision(List.of(
				new MinStExactCutSolver.Choice(List.of())))), List.of(),
			List.of(new MinStExactCutSolver.Edge(-1L, 0L, bits(5.0)),
				new MinStExactCutSolver.Edge(0L, -2L, bits(1.0))));
		Assert.assertTrue(result.unique());
		Assert.assertEquals(bits(5.0), result.objectiveBits());
		Assert.assertEquals(List.of(), result.minima().get(0).sourceNodeIds());
	}

	@Test
	public void genuineDistinctEqualCutMembershipsRemainNonUnique() {
		// BR7/BR10 retain selector tie-before-state and real certificate coverage;
		// this synthetic graph owns only exhaustive equal-minimum solver semantics.
		MinStExactCutSolver.Result result = MinStExactCutSolver.solve(-1L, -2L,
			List.of(new MinStExactCutSolver.Decision(List.of(
				new MinStExactCutSolver.Choice(List.of()),
				new MinStExactCutSolver.Choice(List.of(0L, 1L))))), List.of(),
			List.of(new MinStExactCutSolver.Edge(-1L, -2L, bits(2.0))));
		Assert.assertFalse(result.unique());
		Assert.assertEquals(bits(2.0), result.objectiveBits());
		Assert.assertEquals(List.of(List.of(), List.of(0L, 1L)), result.minima().stream()
			.map(MinStExactCutSolver.Minimum::sourceNodeIds).toList());
	}

	private static MinStExactCostFacts uploadFacts(List<RelocationAction> actions,
		List<ObligationFact> obligations) throws Exception {
		AuxiliaryGroupFact group = new AuxiliaryGroupFact(-3L, Direction.UPLOAD, PRODUCER, 1L,
			FType.ROW, bits(1.0), List.of(new EndpointFact(PRODUCER, CONSUMER, 0, 2L, bits(1.0))));
		NeutralPlacementGraph graph = graph(actions);
		return facts(graph, List.of(), List.of(edge(-1L, -3L, 5.0), edge(1L, -2L, 5.0),
			edge(-1L, -2L, 1.0)), List.of(group), obligations);
	}

	private static MinStExactCostFacts downloadFacts(List<RelocationAction> actions,
		List<ObligationFact> obligations) throws Exception {
		AuxiliaryGroupFact group = new AuxiliaryGroupFact(-3L, Direction.DOWNLOAD, PRODUCER, 1L,
			FType.ROW, bits(1.0), List.of(new EndpointFact(PRODUCER, CONSUMER, 0, 2L, bits(1.0))));
		NeutralPlacementGraph graph = graph(actions);
		return facts(graph, List.of(), List.of(edge(-1L, 1L, 5.0), edge(1L, -2L, 1.0),
			edge(-1L, -3L, 1.0), edge(-3L, -2L, 5.0), edge(-1L, -2L, 1.0)),
			List.of(group), obligations);
	}

	private static NeutralPlacementGraph graph(List<RelocationAction> actions) {
		return new NeutralPlacementGraph(List.of(node(PRODUCER, PRODUCER_VERSION),
			node(CONSUMER, version("consumer-version")), node(OTHER, OTHER_VERSION)), List.of(), actions);
	}

	private static MinStExactCostFacts facts(NeutralPlacementGraph graph, List<DecisionFact> decisions,
		List<DirectedEdgeFact> edges, List<AuxiliaryGroupFact> groups,
		List<ObligationFact> obligations) throws Exception {
		PlacementAnalysis analysis = allocate(PlacementAnalysis.class);
		set(analysis, "graph", graph);
		MinStExactCostFacts facts = allocate(MinStExactCostFacts.class);
		set(facts, "analysis", analysis);
		set(facts, "analysisFingerprint", "test-analysis");
		set(facts, "orderedScope", List.of(PRODUCER));
		set(facts, "decisions", decisions);
		set(facts, "membershipRepresentatives", List.of());
		set(facts, "edges", edges);
		set(facts, "groups", groups);
		set(facts, "obligations", obligations);
		set(facts, "derivationFingerprint", "test-derivation");
		return facts;
	}

	private static ObligationFact obligationFact(String signature, CompiledHopKey consumer,
		int input, PlacementState placement) {
		return new ObligationFact(signature, List.of(new ObligationEndpointFact(consumer, input, placement)));
	}

	private static RelocationAction action(ValueVersionKey source, PlacementState target,
		CompiledHopKey consumer) {
		return action(source, target, consumer, source.lexicalVariable());
	}

	private static RelocationAction action(ValueVersionKey source, PlacementState target,
		CompiledHopKey consumer, String anchorId) {
		RelocationActionKey action = new RelocationActionKey(source, target, anchor(anchorId),
			"scope", List.of(consumer));
		return new RelocationAction(action, List.of(new ObligationKey(consumer, 0, source, target,
			action, "context")));
	}

	private static Node node(CompiledHopKey key, ValueVersionKey version) {
		return new Node(key, NodeKind.OPERATION, version, true, List.of(CP), List.of(), List.of());
	}

	private static DirectedEdgeFact edge(long from, long to, double capacity) {
		return new DirectedEdgeFact(from, to, bits(capacity), List.of());
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(new AnchorPartition("w", List.of(0L), List.of(1L))));
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

	private static long bits(double value) { return Double.doubleToRawLongBits(value); }

	private static void assertRejectsCode(String code, ThrowingRunnable action) throws Exception {
		try {
			action.run();
			Assert.fail("Expected exact selector rejection code " + code);
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue("Expected code " + code + " but got " + expected.getMessage(),
				expected.getMessage().startsWith(code));
		}
	}

	private static void set(Object target, String field, Object value) throws Exception {
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		Field f = Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		return (T)((Unsafe)f.get(null)).allocateInstance(type);
	}

	@FunctionalInterface
	private interface ThrowingRunnable { void run() throws Exception; }
}
