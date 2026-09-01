/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class RelocationSelectionsPhysicalAnchorTest {
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);
	private static final CompiledHopKey SOURCE_A = key("source-a");
	private static final CompiledHopKey SOURCE_B = key("source-b");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final ValueVersionKey VERSION_A = version("version-a");
	private static final ValueVersionKey VERSION_B = version("version-b");
	private static final ValueVersionKey CONSUMER_VERSION = version("consumer-version");
	private static final DurableAnchorKey POOL_A_FROM_X = anchor("fed-init:X", "localhost:1234");
	private static final DurableAnchorKey POOL_A_FROM_Y = anchor("fed-init:Y", "localhost:1234");
	private static final DurableAnchorKey POOL_B = anchor("fed-init:Z", "localhost:2234");

	@Test
	public void receiptAcceptsDifferentPlacementIdsForTheSamePhysicalPool() {
		CandidateSelectionReceipt receipt = receipt();
		List<RelocationAction> actions = List.of(
			action(VERSION_A, 0, POOL_A_FROM_X), action(VERSION_B, 1, POOL_A_FROM_Y));

		Assert.assertNotEquals(POOL_A_FROM_X, POOL_A_FROM_Y);
		Assert.assertTrue(PlacementIdentity.samePhysicalWorkerPool(
			POOL_A_FROM_X, POOL_A_FROM_Y));
		Assert.assertTrue(RelocationSelections.candidateReceiptHasCommonPhysicalAnchor(
			actions, receipt));
	}

	@Test
	public void receiptRejectsDisjointPhysicalPools() {
		CandidateSelectionReceipt receipt = receipt();
		List<RelocationAction> actions = List.of(
			action(VERSION_A, 0, POOL_A_FROM_X), action(VERSION_B, 1, POOL_B));

		Assert.assertFalse(PlacementIdentity.samePhysicalWorkerPool(POOL_A_FROM_X, POOL_B));
		Assert.assertFalse(RelocationSelections.candidateReceiptHasCommonPhysicalAnchor(
			actions, receipt));
	}

	@Test
	public void oneCompatibleAlternativeKeepsTheReceiptReachable() {
		CandidateSelectionReceipt receipt = receipt();
		List<RelocationAction> actions = List.of(
			action(VERSION_A, 0, POOL_A_FROM_X),
			action(VERSION_B, 1, POOL_B), action(VERSION_B, 1, POOL_A_FROM_Y));

		Assert.assertTrue(RelocationSelections.candidateReceiptHasCommonPhysicalAnchor(
			actions, receipt));
	}

	@Test
	public void exactRelocationBindsSamePoolAliasesButRejectsDifferentPools() {
		RelocationAction sourceA = action(VERSION_A, 0, POOL_A_FROM_X);
		RelocationAction sourceBSamePool = action(VERSION_B, 1, POOL_A_FROM_Y);
		NeutralPlacementGraph compatible = graph(List.of(sourceA, sourceBSamePool));
		Map<CompiledHopKey,PlacementState> assignment =
			Map.of(SOURCE_A, FED_ROW, SOURCE_B, FED_ROW, CONSUMER, FED_ROW);

		var choices = RelocationSelections.selectCanonical(compatible, assignment);
		Assert.assertEquals(2, choices.size());
		Assert.assertEquals(2,
			RelocationSelections.resolveAndValidate(compatible, assignment, choices).size());

		RelocationAction sourceBDifferentPool = action(VERSION_B, 1, POOL_B);
		NeutralPlacementGraph incompatible = graph(List.of(sourceA, sourceBDifferentPool));
		Assert.assertThrows(IllegalStateException.class,
			() -> RelocationSelections.selectCanonical(incompatible, assignment));
	}

	private static CandidateSelectionReceipt receipt() {
		CandidateRuleKey rule = new CandidateRuleKey(CONSUMER,
			List.of(CandidateInputState.present(FType.ROW),
				CandidateInputState.present(FType.ROW)));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			new PlacementEmissionState(FED_ROW, false), FType.ROW);
		return new CandidateSelectionReceipt(rule, emission, List.of());
	}

	private static NeutralPlacementGraph graph(List<RelocationAction> actions) {
		Node sourceA = new Node(SOURCE_A, NodeKind.OPERATION, VERSION_A, true,
			List.of(FED_ROW), List.of(), List.of(POOL_A_FROM_X));
		Node sourceB = new Node(SOURCE_B, NodeKind.OPERATION, VERSION_B, true,
			List.of(FED_ROW), List.of(), List.of(POOL_A_FROM_Y, POOL_B));
		Node consumer = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of());
		return new NeutralPlacementGraph(List.of(sourceA, sourceB, consumer), List.of(), actions);
	}

	private static RelocationAction action(ValueVersionKey source, int position,
		DurableAnchorKey anchor) {
		RelocationActionKey key = new RelocationActionKey(source, FED_ROW, anchor,
			"scope", List.of(CONSUMER));
		ObligationKey obligation = new ObligationKey(CONSUMER, position, source,
			FED_ROW, key, "scope");
		return new RelocationAction(key, List.of(obligation));
	}

	private static DurableAnchorKey anchor(String placementId, String worker) {
		return new DurableAnchorKey(placementId, FType.ROW,
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
}
