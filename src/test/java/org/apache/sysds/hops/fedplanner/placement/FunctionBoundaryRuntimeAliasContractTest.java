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

/** Runtime truth for explicit placement transfer across a DML function value boundary. */
public class FunctionBoundaryRuntimeAliasContractTest {
	@Test
	public void functionInputAllowsCostedFoutToLoutButRejectsLoutToFout() {
		Constraint boundary = new Constraint(ConstraintKind.CONJUNCTIVE, key("actual"), key("formal"),
			0, "function-argument:X");
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState full = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);

		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, local, local));
		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, full, full));
		assertTrue(NeutralPlacementGraph.constraintSatisfied(boundary, full, local));
		assertFalse(NeutralPlacementGraph.constraintSatisfied(boundary, local, full));
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
