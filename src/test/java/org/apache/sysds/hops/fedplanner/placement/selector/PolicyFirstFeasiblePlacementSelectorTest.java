/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Contract coverage for deterministic first-feasible policy selection. */
public class PolicyFirstFeasiblePlacementSelectorTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState FED_BROADCAST =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.BROADCAST, false);

	@Test
	public void independentPolicyChoicesStopAfterTheFirstCompleteAssignment() {
		List<Node> nodes = new ArrayList<>();
		for(int index = 0; index < 24; index++)
			nodes.add(node("first-feasible-wide", "hop-" + index, index, List.of(LOCAL, FED)));

		PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(
			new NeutralPlacementGraph(nodes, List.of(), List.of()));

		Assert.assertEquals(24, selected.score().emittedFedCount());
		Assert.assertEquals(24, selected.score().foutCount());
		Assert.assertTrue(selected.assignment().values().stream().allMatch(FED::equals));
		Assert.assertEquals("the selector must not prove the remaining 2^24 policy space",
			1, selected.certificate().exploredCount());
		Assert.assertEquals(TerminationReason.POLICY_FEASIBLE,
			selected.certificate().terminationReason());
	}

	@Test
	public void localizedPropagationResolvesOnlyTheConflictingNeighbor() {
		Node left = node("first-feasible-conflict", "left", 0, List.of(LOCAL, FED));
		Node right = node("first-feasible-conflict", "right", 1, List.of(LOCAL, FED));
		Constraint conflict = new Constraint(ConstraintKind.CONJUNCTIVE,
			left.key(), right.key(), 0,
			"forbid-pair:" + FED.normalizedSignature() + "=>" + FED.normalizedSignature());

		PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(
			new NeutralPlacementGraph(List.of(left, right), List.of(conflict), List.of()));

		Assert.assertEquals("the first local policy choice remains FED", FED,
			selected.assignment().get(left.key()));
		Assert.assertEquals("arc propagation must demote only the conflicting neighbor", LOCAL,
			selected.assignment().get(right.key()));
		Assert.assertTrue(NeutralPlacementGraph.constraintSatisfied(conflict,
			selected.assignment().get(left.key()), selected.assignment().get(right.key())));
		Assert.assertEquals(1, selected.certificate().exploredCount());
	}

	@Test
	public void samePlacementComponentsRetainEachNodesOwnedStateIdentity() {
		PlacementState leftLocal = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState leftFed = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		PlacementState rightLocal = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState rightFed = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		Node left = node("first-feasible-identity", "left", 0, List.of(leftLocal, leftFed));
		Node right = node("first-feasible-identity", "right", 1, List.of(rightLocal, rightFed));
		Constraint same = new Constraint(ConstraintKind.SAME_PLACEMENT,
			left.key(), right.key(), 0, "logical-value-identity");

		PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(
			new NeutralPlacementGraph(List.of(left, right), List.of(same), List.of()));

		Assert.assertSame(leftFed, selected.assignment().get(left.key()));
		Assert.assertSame(rightFed, selected.assignment().get(right.key()));
		Assert.assertNotSame(selected.assignment().get(left.key()), selected.assignment().get(right.key()));
	}

	@Test
	public void canonicalSelectionDoesNotDependOnGraphInsertionOrder() {
		Node left = node("first-feasible-order", "left", 0, List.of(LOCAL, FED));
		Node right = node("first-feasible-order", "right", 1, List.of(LOCAL, FED));
		Constraint conflict = new Constraint(ConstraintKind.CONJUNCTIVE,
			left.key(), right.key(), 0,
			"forbid-pair:" + FED.normalizedSignature() + "=>" + FED.normalizedSignature());
		NeutralPlacementGraph canonical = new NeutralPlacementGraph(
			List.of(left, right), List.of(conflict), List.of());
		List<Node> reversedNodes = new ArrayList<>(canonical.nodes());
		Collections.reverse(reversedNodes);

		PlacementSelection first = new PolicyFirstFeasiblePlacementSelector().select(canonical);
		PlacementSelection second = new PolicyFirstFeasiblePlacementSelector().select(
			new NeutralPlacementGraph(reversedNodes, List.of(conflict), List.of()));

		Assert.assertEquals(first.assignment(), second.assignment());
		Assert.assertEquals(first.score(), second.score());
		Assert.assertEquals(first.selectedRelocations(), second.selectedRelocations());
	}

	@Test
	public void equalPolicyStatesPreferTheLocallyDirectLayout() {
		String fingerprint = "first-feasible-local-movement";
		Node source = node(fingerprint, "source", 0, List.of(FED_BROADCAST, FED));
		Node consumer = node(fingerprint, "consumer", 1, List.of(FED));
		DurableAnchorKey anchor = new DurableAnchorKey("row-workers", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(9L, 9L))));
		RelocationActionKey key = new RelocationActionKey(source.valueVersion(), FED,
			FType.ROW, anchor, "main", List.of(consumer.key()));
		RelocationAction relocation = new RelocationAction(key, List.of(new ObligationKey(
			consumer.key(), 0, source.valueVersion(), FED, key, "compiled")), List.of(FED));

		PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(
			new NeutralPlacementGraph(List.of(source, consumer), List.of(), List.of(relocation)));

		Assert.assertEquals("FED/FOUT ties must choose the direct source layout instead of refederating",
			FED, selected.assignment().get(source.key()));
		Assert.assertTrue(selected.selectedRelocations().isEmpty());
		Assert.assertEquals(0, selected.score().distinctRelocationCount());
		Assert.assertEquals("local ordering must still stop at the first reachable leaf",
			1, selected.certificate().exploredCount());
	}

	@Test
	public void certainDerivedMaterializationPrecedesSpeculativeRelocationRisk() {
		String fingerprint = "first-feasible-derived-before-risk";
		PlacementState fedLout = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
		Node producer = node(fingerprint, "a-producer", 0,
			List.of(fedLout, FED_BROADCAST, FED));
		Node ownerBase = node(fingerprint, "owner", 1, List.of(FED));
		DurableAnchorKey rowAnchor = new DurableAnchorKey("row-workers", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(9L, 9L))));
		Node owner = new Node(ownerBase.key(), NodeKind.OPERATION, ownerBase.valueVersion(),
			true, List.of(FED), List.of(), List.of(rowAnchor));
		PlacementState fedBroadcastLout = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.BROADCAST, false);
		Node consumer = node(fingerprint, "z-consumer", 2,
			List.of(LOCAL, fedBroadcastLout, FED_BROADCAST));
		CandidateRuleKey rule = new CandidateRuleKey(producer.key(),
			List.of(CandidateInputState.present(FType.ROW)));
		DerivedFoutMaterializationActionKey actionKey =
			new DerivedFoutMaterializationActionKey(producer.key(), producer.valueVersion(),
				rule, fedLout, FED_BROADCAST, rowAnchor, owner.key(), FType.ROW,
				FType.BROADCAST, "main");
		DurableAnchorKey broadcastAnchor = new DurableAnchorKey("broadcast-workers", FType.BROADCAST,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(9L, 9L))));
		RelocationActionKey relocationKey = new RelocationActionKey(producer.valueVersion(),
			FED_BROADCAST, FType.BROADCAST, broadcastAnchor, "main", List.of(consumer.key()));
		RelocationAction relocation = new RelocationAction(relocationKey,
			List.of(new ObligationKey(consumer.key(), 0, producer.valueVersion(),
				FED_BROADCAST, relocationKey, "compiled")), List.of(FED_BROADCAST));
		Constraint noBroadcastConsumerForNativeRow = new Constraint(ConstraintKind.CONJUNCTIVE,
			producer.key(), consumer.key(), 0,
			"forbid-pair:" + FED.normalizedSignature() + "=>" + FED_BROADCAST.normalizedSignature());

		PlacementSelection selected = new PolicyFirstFeasiblePlacementSelector().select(
			new NeutralPlacementGraph(List.of(producer, owner, consumer),
				List.of(noBroadcastConsumerForNativeRow), List.of(relocation),
				List.of(new DerivedFoutMaterializationAction(actionKey))));

		Assert.assertEquals("a certain output materialization must lose to an equally ranked native FOUT",
			FED, selected.assignment().get(producer.key()));
		Assert.assertEquals(1, selected.certificate().exploredCount());
	}

	private static Node node(String fingerprint, String topology, int version,
		List<PlacementState> alternatives) {
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb"),
			"main", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled",
			region, topology, topology);
		ValueVersionKey value = new ValueVersionKey(fingerprint, "v" + version, region,
			version, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true, alternatives, List.of(), List.of());
	}
}
