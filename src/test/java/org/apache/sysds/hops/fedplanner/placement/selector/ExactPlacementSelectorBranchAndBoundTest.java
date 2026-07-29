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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
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
}
