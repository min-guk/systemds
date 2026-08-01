/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.rewrite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** Regression for planner placement loss on runtime-created direct WDIVMM replacements. */
public class RewriteWeightedDivMMPlannerPlacementTest {
	private static final int BLOCKSIZE = 1000;

	@Test
	public void directWeightedDivMMReplacementPreservesExactPlannerPlacement() {
		DataOp w = matrixRead("W", 100, 20);
		DataOp u = matrixRead("U", 100, 10);
		DataOp v = matrixRead("V", 20, 10);
		Hop uv = HopRewriteUtils.createMatrixMultiply(u, HopRewriteUtils.createTranspose(v));
		Hop weighted = HopRewriteUtils.createBinary(w, uv, OpOp2.DIV);
		Hop selected = HopRewriteUtils.createMatrixMultiply(weighted, v);
		selected.setExecType(ExecType.FED);
		selected.setForcedExecType(ExecType.FED);
		selected.setFederatedOutput(FederatedOutput.FOUT);
		DataOp root = HopRewriteUtils.createTransientWrite("HS", selected);

		new RewriteAlgebraicSimplificationDynamic().rewriteHopDAG(root, new ProgramRewriteStatus());

		Hop replacement = root.getInput(0);
		assertTrue("Expected the direct matrix-multiply chain to fuse into WDIVMM",
			replacement instanceof QuaternaryOp
				&& ((QuaternaryOp) replacement).getOp() == OpOp4.WDIVMM);
		assertEquals("The exact replaced producer must retain the selected execution type",
			ExecType.FED, replacement.getForcedExecType());
		assertEquals("The exact replaced producer must retain the selected output placement",
			FederatedOutput.FOUT, replacement.getFederatedOutput());
	}

	private static DataOp matrixRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, rows, cols, rows * cols, BLOCKSIZE);
	}
}
