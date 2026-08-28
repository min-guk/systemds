/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.recompile;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.rewrite.RewriteAlgebraicSimplificationDynamic;
import org.apache.sysds.hops.rewrite.RewriteAlgebraicSimplificationStatic;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Regression for the exact derived-FOUT bit used by conflict resolution and runtime recompilation. */
public class CampaignBG014DerivedFoutRecompileStateRedTest {
	@Test
	public void dynamicWeightedDivMmTransposePairCarriesOuterPlannerAuthority() throws Exception {
		Hop x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		Hop u = new DataOp("U", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "U", 100, 10, 1000, 1000);
		Hop v = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "V", 20, 10, 200, 1000);
		Hop innerOwner = new AggBinaryOp("planned-mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, x, v);
		innerOwner.setExecType(ExecType.FED);
		innerOwner.setForcedExecType(ExecType.FED);
		innerOwner.setFederatedOutput(FederatedOutput.FOUT);
		innerOwner.setFederatedOutputDerived(true);
		innerOwner.setPlannerPlacementSelected(true);
		Hop weightedDivMm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 1, true, true);
		ReorgOp replacementTranspose = HopRewriteUtils.createTranspose(weightedDivMm);

		Method inheritWeighted = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"inheritWeightedDivMmReplacementPlacement", Hop.class, Hop.class);
		inheritWeighted.setAccessible(true);
		inheritWeighted.invoke(null, innerOwner, replacementTranspose);
		Assert.assertEquals("DYNAMIC_WEIGHTED_DIV_MM",
			replacementTranspose.getPlannerRewriteReplacementKind());

		ReorgOp outerOwner = HopRewriteUtils.createTranspose(replacementTranspose);
		outerOwner.setExecType(ExecType.FED);
		outerOwner.setForcedExecType(ExecType.FED);
		outerOwner.setFederatedOutput(FederatedOutput.FOUT);
		outerOwner.setFederatedOutputDerived(true);
		outerOwner.setPlannerPlacementSelected(true);
		DataOp parent = new DataOp("HS", DataType.MATRIX, ValueType.FP64,
			outerOwner, OpOpData.TRANSIENTWRITE, null);

		Method collapse = RewriteAlgebraicSimplificationStatic.class.getDeclaredMethod(
			"removeUnnecessaryReorgOperation", Hop.class, Hop.class, int.class);
		collapse.setAccessible(true);
		Hop collapsed = (Hop) collapse.invoke(null, parent, outerOwner, 0);

		Assert.assertSame(weightedDivMm, collapsed);
		Assert.assertSame(weightedDivMm, parent.getInput(0));
		Assert.assertEquals(outerOwner.getPlannerOriginHopID(), weightedDivMm.getPlannerOriginHopID());
		Assert.assertEquals("DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR",
			weightedDivMm.getPlannerRewriteReplacementKind());
		Assert.assertEquals(ExecType.FED, weightedDivMm.getForcedExecType());
		Assert.assertEquals(FederatedOutput.FOUT, weightedDivMm.getFederatedOutput());
		Assert.assertTrue(weightedDivMm.isFederatedOutputDerived());
		Assert.assertTrue(weightedDivMm.isPlannerPlacementSelected());
	}

	@Test
	public void dynamicSumSquaredDoesNotEraseASelectedFoutToLoutBoundary() throws Exception {
		DataOp x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		BinaryOp power = new BinaryOp("power", DataType.MATRIX, ValueType.FP64,
			OpOp2.POW, x, new LiteralOp(2L));
		power.setExecType(ExecType.FED);
		power.setForcedExecType(ExecType.FED);
		power.setFederatedOutput(FederatedOutput.FOUT);
		power.setPlannerPlacementSelected(true);
		AggUnaryOp sum = new AggUnaryOp("sum", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Col, power);
		sum.setExecType(ExecType.FED);
		sum.setForcedExecType(ExecType.FED);
		sum.setFederatedOutput(FederatedOutput.LOUT);
		sum.setPlannerPlacementSelected(true);
		UnaryOp parent = new UnaryOp("sqrt", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, sum);

		Method fuse = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"fuseSumSquared", Hop.class, Hop.class, int.class);
		fuse.setAccessible(true);
		Hop result = (Hop) fuse.invoke(null, parent, sum, 0);

		Assert.assertSame("The selected FED/FOUT-to-FED/LOUT boundary is physical", sum, result);
		Assert.assertSame(sum, parent.getInput(0));
	}

	@Test
	public void dynamicSumSquaredCarriesAuthorityOnlyAcrossAnIdenticalSelectedPlacement() throws Exception {
		DataOp x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		BinaryOp power = new BinaryOp("power", DataType.MATRIX, ValueType.FP64,
			OpOp2.POW, x, new LiteralOp(2L));
		power.setExecType(ExecType.CP);
		power.setForcedExecType(ExecType.CP);
		power.setFederatedOutput(FederatedOutput.LOUT);
		power.setPlannerPlacementSelected(true);
		AggUnaryOp sum = new AggUnaryOp("sum", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Col, power);
		sum.setExecType(ExecType.CP);
		sum.setForcedExecType(ExecType.CP);
		sum.setFederatedOutput(FederatedOutput.LOUT);
		sum.setPlannerPlacementSelected(true);
		UnaryOp parent = new UnaryOp("sqrt", DataType.MATRIX, ValueType.FP64, OpOp1.SQRT, sum);

		Method fuse = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"fuseSumSquared", Hop.class, Hop.class, int.class);
		fuse.setAccessible(true);
		Hop result = (Hop) fuse.invoke(null, parent, sum, 0);

		Assert.assertTrue(result instanceof AggUnaryOp);
		Assert.assertEquals(AggOp.SUM_SQ, ((AggUnaryOp) result).getOp());
		Assert.assertEquals(sum.getPlannerOriginHopID(), result.getPlannerOriginHopID());
		Assert.assertEquals("DYNAMIC_SUM_SQUARED", result.getPlannerRewriteReplacementKind());
		Assert.assertEquals(ExecType.CP, result.getForcedExecType());
		Assert.assertEquals(FederatedOutput.LOUT, result.getFederatedOutput());
		Assert.assertTrue(result.isPlannerPlacementSelected());
	}

	@Test
	public void runtimeStaticDistributiveRewriteDoesNotReplaceSelectedOperations() throws Exception {
		DataOp x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		DataOp mask = new DataOp("mask", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "mask", 100, 20, 2000, 1000);
		BinaryOp maskedX = new BinaryOp("masked-X", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, mask, x);
		markSelectedFedFout(maskedX);
		BinaryOp clippedX = new BinaryOp("clipped-X", DataType.MATRIX, ValueType.FP64,
			OpOp2.MINUS, x, maskedX);
		markSelectedFedFout(clippedX);
		DataOp parent = new DataOp("result", DataType.MATRIX, ValueType.FP64,
			clippedX, OpOpData.TRANSIENTWRITE, null);

		Method rewrite = RewriteAlgebraicSimplificationStatic.class.getDeclaredMethod(
			"simplifyDistributiveBinaryOperation", Hop.class, Hop.class, int.class);
		rewrite.setAccessible(true);
		Hop result = (Hop) rewrite.invoke(null, parent, clippedX, 0);

		Assert.assertSame("Runtime recompilation must retain the two selected physical operations",
			clippedX, result);
		Assert.assertSame(clippedX, parent.getInput(0));
		Assert.assertSame(maskedX, clippedX.getInput(1));
	}

	@Test
	public void prePlannerWeightedDivMmTransposePairDoesNotManufactureAuthority() throws Exception {
		Hop x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		Hop u = new DataOp("U", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "U", 100, 10, 1000, 1000);
		Hop v = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "V", 20, 10, 200, 1000);
		Hop weightedDivMm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 1, true, true);
		ReorgOp inner = HopRewriteUtils.createTranspose(weightedDivMm);
		ReorgOp outer = HopRewriteUtils.createTranspose(inner);
		DataOp parent = new DataOp("HS", DataType.MATRIX, ValueType.FP64,
			outer, OpOpData.TRANSIENTWRITE, null);
		long replacementIdentity = weightedDivMm.getPlannerOriginHopID();

		Method collapse = RewriteAlgebraicSimplificationStatic.class.getDeclaredMethod(
			"removeUnnecessaryReorgOperation", Hop.class, Hop.class, int.class);
		collapse.setAccessible(true);
		collapse.invoke(null, parent, outer, 0);

		Assert.assertEquals(replacementIdentity, weightedDivMm.getPlannerOriginHopID());
		Assert.assertNull(weightedDivMm.getPlannerRewriteReplacementKind());
		Assert.assertFalse(weightedDivMm.isPlannerPlacementSelected());
	}

	@Test
	public void dynamicWeightedDivMmFusionCarriesExactPlannerOrigin() throws Exception {
		Hop x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		Hop u = new DataOp("U", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "U", 100, 10, 1000, 1000);
		Hop v = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "V", 20, 10, 200, 1000);
		Hop owner = new AggBinaryOp("planned-mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, x, v);
		owner.setExecType(ExecType.FED);
		owner.setForcedExecType(ExecType.FED);
		owner.setFederatedOutput(FederatedOutput.FOUT);
		owner.setPlannerPlacementSelected(true);
		Hop replacement = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 2, false, false);

		Method inherit = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"inheritExactReplacementPlacement", Hop.class, Hop.class);
		inherit.setAccessible(true);
		inherit.invoke(null, owner, replacement);

		Assert.assertEquals(owner.getPlannerOriginHopID(), replacement.getPlannerOriginHopID());
		Assert.assertEquals("DYNAMIC_WEIGHTED_DIV_MM",
			replacement.getPlannerRewriteReplacementKind());
		Assert.assertEquals(ExecType.FED, replacement.getForcedExecType());
		Assert.assertEquals(FederatedOutput.FOUT, replacement.getFederatedOutput());
		Assert.assertTrue(replacement.isPlannerPlacementSelected());
	}

	@Test
	public void prePlannerWeightedDivMmFusionDoesNotManufacturePlannerAuthority() throws Exception {
		Hop x = new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
		Hop u = new DataOp("U", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "U", 100, 10, 1000, 1000);
		Hop v = new DataOp("V", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "V", 20, 10, 200, 1000);
		Hop owner = new AggBinaryOp("unplanned-mm", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, AggOp.SUM, x, v);
		Hop replacement = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 2, false, false);
		long replacementIdentity = replacement.getPlannerOriginHopID();

		Method inherit = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"inheritExactReplacementPlacement", Hop.class, Hop.class);
		inherit.setAccessible(true);
		inherit.invoke(null, owner, replacement);

		Assert.assertEquals(replacementIdentity, replacement.getPlannerOriginHopID());
		Assert.assertNull(replacement.getPlannerRewriteReplacementKind());
		Assert.assertFalse(replacement.isPlannerPlacementSelected());
	}

	@Test
	public void rewriteOriginOutranksACollidingPlannerSignature() throws Exception {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		try {
			Hop vector = new DataOp("v", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "v", 50, 1, 50, 1000);
			Hop owner = new AggUnaryOp("sum", DataType.SCALAR, ValueType.FP64,
				AggOp.SUM, Direction.RowCol, vector);
			setLocation(owner, 194, 25, 194, 25);
			owner.setExecType(ExecType.CP);
			owner.setForcedExecType(ExecType.CP);
			owner.setFederatedOutput(FederatedOutput.LOUT);
			owner.setPlannerPlacementSelected(true);

			Method snapshot = Recompiler.class.getDeclaredMethod("snapshotHopStates", List.class);
			snapshot.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Long, ?> baseStates = (Map<Long, ?>) snapshot.invoke(null, List.of(owner));

			Hop left = new DataOp("left", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "left", 1, 50, 50, 1000);
			Hop right = new DataOp("right", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "right", 50, 1, 50, 1000);
			Hop collidingPlan = new AggBinaryOp("planned-mm", DataType.MATRIX, ValueType.FP64,
				OpOp2.MULT, AggOp.SUM, left, right);
			setLocation(collidingPlan, 194, 25, 194, 25);
			collidingPlan.setExecType(ExecType.FED);
			collidingPlan.setForcedExecType(ExecType.FED);
			collidingPlan.setFederatedOutput(FederatedOutput.FOUT);
			FederatedPlannerUtils.registerPlannerRecompileState(
				collidingPlan, ExecType.FED, FederatedOutput.FOUT);

			Hop replacement = new AggBinaryOp("dot-rewrite", DataType.MATRIX, ValueType.FP64,
				OpOp2.MULT, AggOp.SUM, left, right);
			setLocation(replacement, 194, 25, 194, 25);
			replacement.setPlannerRewriteReplacement(owner, "DYNAMIC_DOT_PRODUCT");
			Assert.assertEquals("The rewrite must initially inherit its owner", ExecType.CP,
				replacement.getForcedExecType());

			Method restore = Recompiler.class.getDeclaredMethod(
				"restoreHopStates", List.class, Map.class, Map.class);
			restore.setAccessible(true);
			restore.invoke(null, List.of(replacement), baseStates, null);

			Assert.assertEquals("Exact rewrite origin must beat a colliding ba(+*) signature",
				ExecType.CP, replacement.getForcedExecType());
			Assert.assertEquals(FederatedOutput.LOUT, replacement.getFederatedOutput());
			Assert.assertTrue(replacement.isPlannerPlacementSelected());
		}
		finally {
			FederatedPlannerUtils.clearPlannerRecompileStates();
		}
	}

	@Test
	public void plannerRecompileStatePreservesDerivedFoutAuthority() throws Exception {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		try {
			Hop input = new DataOp("X", DataType.MATRIX, ValueType.FP64,
				OpOpData.TRANSIENTREAD, "X", 100, 20, 2000, 1000);
			Hop producer = new UnaryOp("derived", DataType.MATRIX, ValueType.FP64, OpOp1.EXP, input);
			producer.setBeginLine(59);
			producer.setBeginColumn(2);
			producer.setEndLine(59);
			producer.setEndColumn(20);
			producer.setExecType(ExecType.FED);
			producer.setForcedExecType(ExecType.FED);
			producer.setFederatedOutput(FederatedOutput.FOUT);
			producer.setFederatedOutputDerived(true);

			FederatedPlannerUtils.registerPlannerRecompileState(
				producer, ExecType.FED, FederatedOutput.FOUT);
			String signature = FederatedPlannerUtils.plannerRecompileSignature(producer);
			Object published = FederatedPlannerUtils.snapshotPlannerRecompileStates().get(signature);
			Assert.assertNotNull("The exact planner state must be published", published);
			Method derivedAccessor = published.getClass().getMethod("federatedOutputDerived");
			Assert.assertTrue("The published state must retain the derived-FOUT authority bit",
				(Boolean) derivedAccessor.invoke(published));

			Method snapshot = Recompiler.class.getDeclaredMethod("snapshotHopStates", List.class);
			snapshot.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Long, ?> baseStates = (Map<Long, ?>) snapshot.invoke(null, List.of(producer));

			producer.setExecType(ExecType.CP);
			producer.setForcedExecType(ExecType.CP);
			producer.setFederatedOutput(FederatedOutput.LOUT);
			Assert.assertFalse(producer.isFederatedOutputDerived());

			Method restore = Recompiler.class.getDeclaredMethod(
				"restoreHopStates", List.class, Map.class, Map.class);
			restore.setAccessible(true);
			restore.invoke(null, List.of(producer), baseStates, null);

			Assert.assertEquals(ExecType.FED, producer.getForcedExecType());
			Assert.assertEquals(FederatedOutput.FOUT, producer.getFederatedOutput());
			Assert.assertTrue("Runtime recompile must restore derived FOUT, not plain FED/FOUT",
				producer.isFederatedOutputDerived());
		}
		finally {
			FederatedPlannerUtils.clearPlannerRecompileStates();
		}
	}

	private static void setLocation(Hop hop, int beginLine, int beginColumn, int endLine, int endColumn) {
		hop.setBeginLine(beginLine);
		hop.setBeginColumn(beginColumn);
		hop.setEndLine(endLine);
		hop.setEndColumn(endColumn);
	}

	private static void markSelectedFedFout(Hop hop) {
		hop.setExecType(ExecType.FED);
		hop.setForcedExecType(ExecType.FED);
		hop.setFederatedOutput(FederatedOutput.FOUT);
		hop.setPlannerPlacementSelected(true);
	}
}
