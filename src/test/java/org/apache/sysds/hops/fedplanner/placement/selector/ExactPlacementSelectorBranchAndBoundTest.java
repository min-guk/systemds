/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
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
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for the production-size exact-search path. */
public class ExactPlacementSelectorBranchAndBoundTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState FED_COL =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, false);

	@Test
	public void productionSizeSearchProvesTheExactFedAllMaximumWithoutCartesianExpansion() {
		String fingerprint = "exact-selector-branch-bound";
		List<Node> nodes = new ArrayList<>();
		List<Constraint> constraints = new ArrayList<>();
		for(int i = 0; i < 17; i++) {
			ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb"),
				"main", "compiled");
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
				"hop-" + i, "hop-" + i);
			ValueVersionKey value = new ValueVersionKey(fingerprint, "v" + i, region, i,
				VersionKind.ORDINARY, List.of());
			nodes.add(new Node(key, NodeKind.OPERATION, value, true,
				List.of(LOCAL, FED), List.of(), List.of()));
			if(i > 0)
				constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT,
					nodes.get(0).key(), key, i, "shared-placement"));
		}

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, constraints, List.of()));

		Assert.assertEquals(17, selection.score().emittedFedCount());
		Assert.assertEquals(17, selection.score().foutCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED::equals));
		Assert.assertEquals(TerminationReason.TIGHT_BOUND_EQUALITY,
			selection.certificate().terminationReason());
		Assert.assertEquals(1, selection.certificate().exploredCount());
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void productionSizeEqualObjectiveTiesUseTheStableSignatureBound() {
		List<Node> nodes = equalObjectiveNodes("exact-selector-equal-ties");

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, List.of(), List.of()));

		Assert.assertEquals(17, selection.score().emittedFedCount());
		Assert.assertEquals(17, selection.score().foutCount());
		Assert.assertEquals(0, selection.score().distinctRelocationCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED_COL::equals));
		Assert.assertTrue("equal-score ties must not expand the 2^17 Cartesian product",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void productionSizeRelocationTiesUseAnAdmissibleRelocationLowerBound() {
		String fingerprint = "exact-selector-relocation-ties";
		List<Node> nodes = equalObjectiveNodes(fingerprint);
		List<CompiledHopKey> consumers = nodes.stream().map(Node::key).toList();
		DurableAnchorKey anchor = new DurableAnchorKey("exact-selector-anchor", FType.COL,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(1L, 1L))));
		RelocationActionKey actionKey = new RelocationActionKey(nodes.get(0).valueVersion(),
			FED_COL, anchor, "exact-selector-scope", consumers);
		List<ObligationKey> obligations = new ArrayList<>();
		for(int i = 0; i < nodes.size(); i++)
			obligations.add(new ObligationKey(nodes.get(i).key(), i, nodes.get(0).valueVersion(),
				FED_COL, actionKey, "exact-selector-scope"));

		PlacementSelection selection = new ExactPlacementSelector().select(new NeutralPlacementGraph(
			nodes, List.of(), List.of(new RelocationAction(actionKey, obligations))));

		Assert.assertEquals(0, selection.score().distinctRelocationCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED::equals));
		Assert.assertTrue(selection.selectedRelocations().isEmpty());
		Assert.assertTrue("relocation ties must not expand the 2^17 Cartesian product",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void productionSizeIndependentConstraintComponentsAvoidGlobalCartesianExpansion() {
		String fingerprint = "exact-selector-independent-components";
		List<Node> nodes = new ArrayList<>();
		List<Constraint> constraints = new ArrayList<>();
		for(int i = 0; i < 9; i++) {
			Node left = decisionNode(fingerprint, "pair-" + i + "-left", i * 2);
			Node right = decisionNode(fingerprint, "pair-" + i + "-right", i * 2 + 1);
			nodes.add(left);
			nodes.add(right);
			constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, left.key(), right.key(), i,
				"forbid-pair:" + FED.normalizedSignature() + "=>" + FED.normalizedSignature()));
		}

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, constraints, List.of()));

		Assert.assertEquals(9, selection.score().emittedFedCount());
		Assert.assertEquals(9, selection.score().foutCount());
		for(Constraint constraint : constraints) {
			Assert.assertFalse(FED.equals(selection.assignment().get(constraint.left()))
				&& FED.equals(selection.assignment().get(constraint.right())));
			Assert.assertEquals("stable exact tie break", LOCAL,
				selection.assignment().get(constraint.left()));
			Assert.assertEquals("stable exact tie break", FED,
				selection.assignment().get(constraint.right()));
		}
		Assert.assertTrue("independent exact components must not form a graph-wide Cartesian product",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	private static List<Node> equalObjectiveNodes(String fingerprint) {
		List<Node> nodes = new ArrayList<>();
		for(int i = 0; i < 17; i++) {
			Node node = decisionNode(fingerprint, "hop-" + i, i);
			nodes.add(new Node(node.key(), node.kind(), node.valueVersion(), true,
				List.of(FED_COL, FED), List.of(), List.of()));
		}
		return nodes;
	}

	private static Node decisionNode(String fingerprint, String id, int ordinal) {
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb"),
			"main", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
			id, id);
		ValueVersionKey value = new ValueVersionKey(fingerprint, "v" + ordinal, region, ordinal,
			VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true,
			List.of(LOCAL, FED), List.of(), List.of());
	}
}
