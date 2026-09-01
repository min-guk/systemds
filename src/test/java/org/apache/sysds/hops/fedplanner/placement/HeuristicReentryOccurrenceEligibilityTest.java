/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact occurrence eligibility for analysis-owned Heuristic pathwise re-entry. */
public class HeuristicReentryOccurrenceEligibilityTest {
	@Test
	public void functionLoopAndRecompileOccurrencesAreEligibleButSyntheticAndCloneNodesAreNot() {
		Assert.assertTrue(NeutralPlacementGraphBuilder.exactHeuristicReentryOccurrence(
			node(NodeKind.OPERATION, VersionKind.ORDINARY,
				"lib::slicefinderX", "function/lib::slicefinderX/body/1/loop-body/0", "recompile", true)));
		Assert.assertTrue(NeutralPlacementGraphBuilder.exactHeuristicReentryOccurrence(
			node(NodeKind.TRANSIENT_READ, VersionKind.ORDINARY,
				"main", "main/2/loop-body/0", "compiled", true)));

		Assert.assertFalse(NeutralPlacementGraphBuilder.exactHeuristicReentryOccurrence(
			node(NodeKind.CLONE, VersionKind.CLONE_RECOMPILE,
				"lib::slicefinderX", "function/lib::slicefinderX/body/0", "recompile", true)));
		Assert.assertFalse(NeutralPlacementGraphBuilder.exactHeuristicReentryOccurrence(
			node(NodeKind.FUNCTION_CALL, VersionKind.ORDINARY,
				"main", "main/1", "compiled", true)));
		Assert.assertFalse(NeutralPlacementGraphBuilder.exactHeuristicReentryOccurrence(
			node(NodeKind.FUNCTION_INPUT, VersionKind.FUNCTION_INPUT,
				"lib::slicefinderX", "main/1->lib::slicefinderX/input-0", "callsite:test", true)));
		Assert.assertFalse(NeutralPlacementGraphBuilder.exactHeuristicReentryOccurrence(
			node(NodeKind.FUNCTION_BODY_NON_EMITTED, VersionKind.ORDINARY,
				"unused", "function/unused/body/0", "compiled", false)));
	}

	private static Node node(NodeKind kind, VersionKind versionKind, String namespace,
		String path, String context, boolean emitted) {
		String fingerprint = "fixture";
		ControlRegionKey region = new ControlRegionKey(fingerprint, namespace,
			List.of(path), path, context);
		CompiledHopKey key = new CompiledHopKey(fingerprint, namespace, path, context,
			region, "root-0", "fixture");
		ValueVersionKey value = new ValueVersionKey(fingerprint, "v", region, 0,
			versionKind, List.of());
		List<PlacementState> alternatives = emitted
			? List.of(new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false))
			: List.of();
		return new Node(key, kind, value, emitted, alternatives, List.of(), List.of());
	}
}
