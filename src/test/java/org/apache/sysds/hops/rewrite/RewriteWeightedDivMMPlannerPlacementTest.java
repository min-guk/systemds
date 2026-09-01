/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.rewrite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
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
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

/** Regression for planner placement loss on runtime-created direct WDIVMM replacements. */
public class RewriteWeightedDivMMPlannerPlacementTest {
	private static final int BLOCKSIZE = 1000;

	@After
	public void clearPlannerAuthority() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
	}

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

	@Test
	public void plannerAuthorityStillAppliesModeledWeightedDivMMTransposePair() {
		DataOp w = matrixRead("W", 100, 20);
		DataOp u = matrixRead("U", 100, 10);
		DataOp s = matrixRead("S", 20, 10);
		Hop us = HopRewriteUtils.createMatrixMultiply(u, HopRewriteUtils.createTranspose(s));
		Hop weighted = HopRewriteUtils.createBinary(w, us, OpOp2.MULT);
		Hop inner = HopRewriteUtils.createMatrixMultiply(HopRewriteUtils.createTranspose(u), weighted);
		markSelected(inner, 130, 2);
		ReorgOp outer = HopRewriteUtils.createTranspose(inner);
		markSelected(outer, 130, 1);
		DataOp root = HopRewriteUtils.createTransientWrite("HS", outer);

		FederatedPlannerUtils.registerPlannerRecompileState(
			inner, ExecType.FED, FederatedOutput.LOUT);
		FederatedPlannerUtils.registerPlannerRecompileState(
			outer, ExecType.FED, FederatedOutput.LOUT);

		new RewriteAlgebraicSimplificationDynamic().rewriteHopDAG(
			root, new ProgramRewriteStatus());
		new RewriteAlgebraicSimplificationStatic().rewriteHopDAG(
			root, new ProgramRewriteStatus());

		Hop replacement = root.getInput(0);
		assertTrue("The modeled transpose pair must lower to one WDIVMM under planner authority",
			replacement instanceof QuaternaryOp
				&& ((QuaternaryOp) replacement).getOp() == OpOp4.WDIVMM);
		assertEquals("The surviving kernel must discharge the outer occurrence",
			outer.getPlannerOriginHopID(), replacement.getPlannerOriginHopID());
		assertEquals("DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR",
			replacement.getPlannerRewriteReplacementKind());
		assertEquals(ExecType.FED, replacement.getForcedExecType());
		assertEquals(FederatedOutput.LOUT, replacement.getFederatedOutput());
	}

	@Test
	public void plannerAuthorityDoesNotOpenUnmodeledWeightedDivMMPatterns() {
		DataOp w = matrixRead("W", 100, 2000);
		DataOp u = matrixRead("U", 100, 10);
		DataOp s = matrixRead("S", 2000, 10);
		Hop outerProduct = HopRewriteUtils.createMatrixMultiply(
			u, HopRewriteUtils.createTranspose(s));
		Hop weighted = HopRewriteUtils.createBinary(w, outerProduct, OpOp2.MULT);
		markSelected(weighted, 131, 2);
		ReorgOp outer = HopRewriteUtils.createTranspose(weighted);
		markSelected(outer, 131, 1);
		DataOp root = HopRewriteUtils.createTransientWrite("HS", outer);

		FederatedPlannerUtils.registerPlannerRecompileState(
			weighted, ExecType.FED, FederatedOutput.LOUT);
		FederatedPlannerUtils.registerPlannerRecompileState(
			outer, ExecType.FED, FederatedOutput.LOUT);
		new RewriteAlgebraicSimplificationDynamic().rewriteHopDAG(
			root, new ProgramRewriteStatus());

		assertSame("Pattern 7 is not the modeled transpose-pair replacement",
			weighted, outer.getInput(0));
	}

	private static void markSelected(Hop hop, int line, int column) {
		hop.setBeginLine(line);
		hop.setBeginColumn(column);
		hop.setEndLine(line);
		hop.setEndColumn(column + 1);
		hop.setExecType(ExecType.FED);
		hop.setForcedExecType(ExecType.FED);
		hop.setFederatedOutput(FederatedOutput.LOUT);
		hop.setPlannerPlacementSelected(true);
	}

	private static DataOp matrixRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			null, rows, cols, rows * cols, BLOCKSIZE);
	}
}
