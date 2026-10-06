/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** Runtime truth for alias-only value binding across a DML function boundary. */
public class FunctionBoundaryRuntimeAliasContractTest {
	@Test
	public void functionInputIsAnExactValueAliasLikeTransientBinding() {
		Constraint boundary = new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, key("actual"), key("formal"),
			0, "function-argument:X");
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState fedLocal = new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, true);
		PlacementState cpFout = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, true);
		PlacementState fedFout = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		PlacementState differentFout = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);

		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, local, local));
		assertTrue("Execution type may differ when both sides expose the same local value",
			NeutralPlacementGraph.constraintSatisfied(boundary, fedLocal, local));
		assertTrue("Execution type may differ when both sides expose the same federated value",
			NeutralPlacementGraph.constraintSatisfied(boundary, cpFout, fedFout));
		assertFalse("A function binding cannot hide a FOUT-to-LOUT download",
			NeutralPlacementGraph.constraintSatisfied(boundary, fedFout, local));
		assertFalse("A function binding cannot hide a LOUT-to-FOUT upload",
			NeutralPlacementGraph.constraintSatisfied(boundary, local, fedFout));
		assertFalse("A federated alias must preserve its partitioning type",
			NeutralPlacementGraph.constraintSatisfied(boundary, fedFout, differentFout));
	}

	@Test
	public void functionOutputIsAnExactAliasUntilRuntimeOwnsAnExplicitMaterialization() {
		Constraint boundary = new Constraint(ConstraintKind.SAME_VALUE_PLACEMENT, key("returned"), key("bound"),
			0, "function-result:X");
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState fedLocal = new PlacementState(ExecType.FED, FederatedOutput.LOUT, FType.ROW, true);
		PlacementState full = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);

		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, local, local));
		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, fedLocal, local));
		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, full, full));
		assertFalse(NeutralPlacementGraph.constraintSatisfied(boundary, full, local));
		assertFalse(NeutralPlacementGraph.constraintSatisfied(boundary, local, full));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey("program", "main", java.util.List.of("root"),
			"root", "static");
		return new CompiledHopKey("program", "main", "root", "static", region, name, name);
	}
}
