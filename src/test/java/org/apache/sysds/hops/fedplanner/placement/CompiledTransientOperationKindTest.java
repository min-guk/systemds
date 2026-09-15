/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact compiled-occurrence classification for transient reads and writes. */
public class CompiledTransientOperationKindTest {
	@Test
	public void acceptsOrdinaryAndCfgJoinTransientOccurrences() {
		DataOp read = transientRead("X");
		DataOp write = transientWrite("X");

		Assert.assertTrue(PlacementAnalysis.isCompiledTransientAccess(read,
			node(NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, "main/0"), OpOpData.TRANSIENTREAD));
		Assert.assertTrue(PlacementAnalysis.isCompiledTransientAccess(write,
			node(NodeKind.TRANSIENT_WRITE, VersionKind.ORDINARY, "main/1"), OpOpData.TRANSIENTWRITE));
		Assert.assertTrue("A physical loop-backedge TWrite may be represented by LOOP_PHI",
			PlacementAnalysis.isCompiledTransientAccess(write,
				node(NodeKind.LOOP_PHI, VersionKind.LOOP_BACKEDGE, "main/2/loop-backedge/0"),
				OpOpData.TRANSIENTWRITE));
		Assert.assertTrue("A compiled branch join may retain the physical transient read",
			PlacementAnalysis.isCompiledTransientAccess(read,
				node(NodeKind.BRANCH_JOIN, VersionKind.BRANCH_JOIN_PHI, "main/3/branch-join/0"),
				OpOpData.TRANSIENTREAD));
	}

	@Test
	public void acyclicForwardingAcceptsBranchJoinButRejectsLoopPhi() {
		DataOp read = transientRead("X");
		DataOp write = transientWrite("X");

		Assert.assertTrue(PlacementAnalysis.isCompiledAcyclicTransientForwardAccess(read,
			node(NodeKind.BRANCH_JOIN, VersionKind.BRANCH_JOIN_PHI, "main/3/branch-join/0"),
			OpOpData.TRANSIENTREAD));
		Assert.assertFalse(PlacementAnalysis.isCompiledAcyclicTransientForwardAccess(write,
			node(NodeKind.LOOP_PHI, VersionKind.LOOP_BACKEDGE, "main/2/loop-backedge/0"),
			OpOpData.TRANSIENTWRITE));
	}

	@Test
	public void rejectsOperationAndDirectionMismatches() {
		DataOp read = transientRead("X");
		DataOp write = transientWrite("X");

		Assert.assertFalse("Requested operation must equal the DataOp operation",
			PlacementAnalysis.isCompiledTransientAccess(read,
				node(NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, "main/0"),
				OpOpData.TRANSIENTWRITE));
		Assert.assertFalse("A read cannot masquerade as a transient-write node",
			PlacementAnalysis.isCompiledTransientAccess(read,
				node(NodeKind.TRANSIENT_WRITE, VersionKind.ORDINARY, "main/1"),
				OpOpData.TRANSIENTREAD));
		Assert.assertFalse("A write cannot masquerade as a transient-read node",
			PlacementAnalysis.isCompiledTransientAccess(write,
				node(NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, "main/2"),
				OpOpData.TRANSIENTWRITE));
		Assert.assertFalse("OPERATION does not identify a compiled transient access",
			PlacementAnalysis.isCompiledTransientAccess(read,
				node(NodeKind.OPERATION, VersionKind.ORDINARY, "main/3"), OpOpData.TRANSIENTREAD));
		Assert.assertFalse("A non-DataOp remains non-transient even when a PHI kind is attached",
			PlacementAnalysis.isCompiledTransientAccess(new LiteralOp(1L),
				node(NodeKind.LOOP_PHI, VersionKind.LOOP_BACKEDGE, "main/4/loop-backedge/0"),
				OpOpData.TRANSIENTREAD));
		Assert.assertFalse("Persistent operations are outside the transient classifier",
			PlacementAnalysis.isCompiledTransientAccess(read,
				node(NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, "main/5"),
				OpOpData.PERSISTENTREAD));
	}

	@Test
	public void rejectsCloneSyntheticAndFunctionBoundaryOccurrences() {
		DataOp read = transientRead("X");
		DataOp write = transientWrite("X");

		Assert.assertFalse(PlacementAnalysis.isCompiledTransientAccess(read,
			node(NodeKind.CLONE, VersionKind.CLONE_RECOMPILE, "main/clone/0"),
			OpOpData.TRANSIENTREAD));
		Assert.assertFalse(PlacementAnalysis.isCompiledTransientAccess(read,
			node(NodeKind.FUNCTION_INPUT, VersionKind.FUNCTION_INPUT, "main/function-input/0"),
			OpOpData.TRANSIENTREAD));
		Assert.assertFalse(PlacementAnalysis.isCompiledTransientAccess(write,
			node(NodeKind.FUNCTION_OUTPUT, VersionKind.FUNCTION_OUTPUT, "main/function-output/0"),
			OpOpData.TRANSIENTWRITE));
		Assert.assertFalse("Function-boundary regions are synthetic even with a LOOP_PHI node",
			PlacementAnalysis.isCompiledTransientAccess(write,
				node(NodeKind.LOOP_PHI, VersionKind.LOOP_BACKEDGE,
					"function-boundary:main->lib::f/output-0"), OpOpData.TRANSIENTWRITE));
	}

	private static DataOp transientRead(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 10, 5, 50, 1000);
	}

	private static DataOp transientWrite(String name) {
		Hop input = new LiteralOp(1d);
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, input,
			OpOpData.TRANSIENTWRITE, name);
	}

	private static Node node(NodeKind kind, VersionKind versionKind, String path) {
		String fingerprint = "compiled-transient-operation-kind";
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of(path),
			path, "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", path, "compiled",
			region, "hop:" + path, "source:" + path);
		ValueVersionKey value = new ValueVersionKey(fingerprint, "X", region, 0,
			versionKind, List.of());
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		return new Node(key, kind, value, true, List.of(local), List.of(), List.of());
	}
}
