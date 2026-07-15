/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.ExactPlacementSelector;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementScore;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementSelection;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph;
import org.apache.sysds.test.component.federated.placement.oracle.selector.SelectorOracleFixtures;
import org.junit.Assert;
import org.junit.Test;

/** Genuine RED contract for the pure exact production selector and proof certificate. */
public class ExactPlacementSelectorContractTest {
	@Test
	public void exactSelectorMatchesIndependentS01ThroughS08ScoreFrontier() {
		List<ExplicitSelectorGraph> oracleGraphs = new ArrayList<>(List.of(
			SelectorOracleFixtures.independentHops(), SelectorOracleFixtures.parentChildFTypeConflict(),
			SelectorOracleFixtures.sharedDiamond(), SelectorOracleFixtures.sharedRelocation(),
			SelectorOracleFixtures.fedBeforeFout(), SelectorOracleFixtures.fewerRelocations(),
			SelectorOracleFixtures.stableTie()));
		oracleGraphs.addAll(SelectorOracleFixtures.generatedCorpus());
		ExactPlacementSelector selector = new ExactPlacementSelector();
		for(ExplicitSelectorGraph oracleGraph : oracleGraphs) {
			ExactSelectorOracle.Result expected = ExactSelectorOracle.select(oracleGraph,
				ExactSelectorOracle.Policy.FED_ALL);
			NeutralPlacementGraph productionGraph = parallelNeutralFixture(oracleGraph);
			PlacementSelection actual = selector.select(productionGraph);
			Assert.assertEquals(oracleGraph.getSizeClass(), expected.getScore().getFedCount(),
				actual.score().emittedFedCount());
			Assert.assertEquals(oracleGraph.getSizeClass(), expected.getScore().getFoutCount(),
				actual.score().foutCount());
			Assert.assertEquals(oracleGraph.getSizeClass(), expected.getScore().getRelocationCount(),
				actual.score().distinctRelocationCount());
			assertExactCertificate(productionGraph, actual);
		}
	}

	@Test
	public void scoreOrderIsFedThenFoutThenDistinctRelocationThenStableSignature() {
		PlacementScore base = new PlacementScore(1, 1, 1, "b");
		Assert.assertTrue(new PlacementScore(2, 0, 99, "z").compareTo(base) > 0);
		Assert.assertTrue(new PlacementScore(1, 2, 99, "z").compareTo(base) > 0);
		Assert.assertTrue(new PlacementScore(1, 1, 0, "z").compareTo(base) > 0);
		Assert.assertTrue(new PlacementScore(1, 1, 1, "a").compareTo(base) > 0);
	}

	@Test
	public void selectionAndCertificateAreImmutableAndDeterministicUnderInsertionOrder() {
		NeutralPlacementGraph forward = ordinaryGraph("determinism", 4, false);
		NeutralPlacementGraph reverse = ordinaryGraph("determinism", 4, true);
		PlacementSelection first = new ExactPlacementSelector().select(forward);
		PlacementSelection second = new ExactPlacementSelector().select(reverse);
		Assert.assertEquals(first.score(), second.score());
		Assert.assertEquals(first.certificate().assignmentHash(), second.certificate().assignmentHash());
		Assert.assertEquals(first.certificate().exploredCount(), second.certificate().exploredCount());
		Assert.assertEquals(first.certificate().prunedCount(), second.certificate().prunedCount());
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> first.assignment().put(first.assignment().keySet().iterator().next(), CP));
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> first.selectedRelocations().clear());
	}

	@Test
	public void onlyProofCompleteTerminationCanRepresentSuccess() {
		Assert.assertEquals(List.of("EXHAUSTED", "TIGHT_BOUND_EQUALITY"),
			java.util.Arrays.stream(PlacementCertificate.TerminationReason.values())
				.map(Enum::name).sorted().toList());
	}

	private static void assertExactCertificate(NeutralPlacementGraph graph, PlacementSelection selection) {
		PlacementCertificate certificate = selection.certificate();
		Assert.assertEquals(selection.score(), certificate.incumbentScore());
		Assert.assertTrue(certificate.finalUpperBound().compareTo(selection.score()) <= 0);
		Assert.assertTrue(certificate.exploredCount() >= 0);
		Assert.assertTrue(certificate.prunedCount() >= 0);
		Assert.assertEquals(graph.nodes().size(), certificate.graphNodeCount());
		Assert.assertTrue(certificate.graphEdgeCount() >= 0);
		Assert.assertTrue(certificate.componentCount() >= 1);
		Assert.assertFalse(certificate.assignmentHash().isBlank());
		Assert.assertFalse(certificate.graphFingerprint().isBlank());
		Assert.assertFalse(certificate.boundDerivation().isBlank());
		Assert.assertFalse(certificate.generatorSizeClass().isBlank());
		Assert.assertTrue(certificate.closureDepth() >= 0);
		Assert.assertNotNull(certificate.componentBounds());
		Assert.assertTrue(List.of("EXHAUSTED", "TIGHT_BOUND_EQUALITY")
			.contains(certificate.terminationReason().name()));
	}

	/*
	 * Independently spelled neutral counterparts to S-01..S-08. They do not inspect oracle output;
	 * the comparison above is the only point where the two independently constructed universes meet.
	 */
	private static NeutralPlacementGraph parallelNeutralFixture(ExplicitSelectorGraph oracle) {
		String id = oracle.getSizeClass();
		if(id.equals("S-01"))
			return ordinaryGraph(id, 2, false);
		if(id.equals("S-02"))
			return graph(id, List.of(List.of(FF), List.of(CP)));
		if(id.equals("S-03"))
			return ordinaryGraph(id, 3, false);
		if(id.equals("S-04"))
			return graphWithSharedRelocation(id, List.of(List.of(FF), List.of(FF), List.of(CF)), 2,
				List.of(0, 1), CF);
		if(id.equals("S-05"))
			return graph(id, List.of(List.of(FL), List.of(CP), List.of(CP)));
		if(id.equals("S-06"))
			return graphWithSharedRelocation(id, List.of(List.of(FF), List.of(FF)), 0, List.of(0, 1), FF);
		if(id.equals("S-07"))
			return graph(id, List.of(List.of(FL)));
		if(id.startsWith("S-08-"))
			return ordinaryGraph(id, oracle.getNodes().size(), false);
		throw new IllegalArgumentException("missing neutral counterpart for " + id);
	}

	private static NeutralPlacementGraph graph(String id, List<List<PlacementState>> alternatives) {
		List<Node> nodes = new ArrayList<>();
		for(int i = 0; i < alternatives.size(); i++)
			nodes.add(node(id, "n" + i, alternatives.get(i)));
		return new NeutralPlacementGraph(nodes, List.of(), List.of());
	}

	private static NeutralPlacementGraph graphWithSharedRelocation(String id,
		List<List<PlacementState>> alternatives, int sourceIndex, List<Integer> consumerIndices,
		PlacementState target) {
		List<Node> nodes = new ArrayList<>();
		for(int i = 0; i < alternatives.size(); i++)
			nodes.add(node(id, "n" + i, alternatives.get(i)));
		ValueVersionKey source = nodes.get(sourceIndex).valueVersion();
		List<CompiledHopKey> consumers = consumerIndices.stream().map(i -> nodes.get(i).key()).toList();
		DurableAnchorKey anchor = new DurableAnchorKey(id + "-anchor", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(1L, 1L))));
		RelocationActionKey action = new RelocationActionKey(source, target, anchor, id, consumers);
		List<ObligationKey> obligations = new ArrayList<>();
		for(int i = 0; i < consumers.size(); i++)
			obligations.add(new ObligationKey(consumers.get(i), i, source, target, action, "compiled"));
		return new NeutralPlacementGraph(nodes, List.of(),
			List.of(new RelocationAction(action, obligations)));
	}

	private static NeutralPlacementGraph ordinaryGraph(String id, int size, boolean reverse) {
		List<Node> nodes = new ArrayList<>();
		for(int i = 0; i < size; i++)
			nodes.add(node(id, "n" + i, List.of(CP, FL, FF)));
		if(reverse)
			Collections.reverse(nodes);
		return new NeutralPlacementGraph(nodes, List.of(), List.of());
	}

	private static Node node(String graphId, String id, List<PlacementState> states) {
		ControlRegionKey region = new ControlRegionKey(graphId, "main", List.of("root"), "main", "compiled");
		CompiledHopKey key = new CompiledHopKey(graphId, "main", "main", "compiled", region, id, id);
		ValueVersionKey value = new ValueVersionKey(graphId, id, region, 0, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, states, List.of(), List.of());
	}

	private static final PlacementState CP = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState CF = new PlacementState(ExecType.CP, FederatedOutput.FOUT, null, false);
	private static final PlacementState FL = new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
	private static final PlacementState FF = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
}
