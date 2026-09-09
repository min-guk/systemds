/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.rewrite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.lops.Ctable;
import org.apache.sysds.lops.Transform;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry.ConsumerInputSpec;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Test;

/** Regressions for dynamic rewrites that run before planner state restoration. */
public class RewritePlannerRecompileAuthorityTest {
	@After
	public void clearPlannerAuthority() {
		FederatedPlannerUtils.clearPlannerRecompileStates();
		FederatedLocalMaterializeRegistry.clear();
	}

	@Test
	public void scalarBinaryCastUsesTheCpOwnerRecompileSignature() throws Exception {
		BinaryOp planned = binaryAtLine(93);
		FederatedPlannerUtils.registerPlannerRecompileState(
			planned, ExecType.CP, FederatedOutput.LOUT);
		BinaryOp recompiled = binaryAtLine(93);

		invokeScalarBinaryRewrite(recompiled);

		Hop cast = recompiled.getInput(1);
		assertEquals("DYNAMIC_BINARY_SCALAR_CAST", cast.getPlannerLoweringAuxiliaryKind());
		cast.constructLops();
		assertEquals(FederatedPlannerUtils.plannerRecompileSignature(planned),
			cast.getLops().getPlannerRecompileSignature());
	}

	@Test
	public void scalarBinaryCastDoesNotTurnAFedOwnerIntoAnUnplannedCpHelper() throws Exception {
		BinaryOp planned = binaryAtLine(94);
		FederatedPlannerUtils.registerPlannerRecompileState(
			planned, ExecType.FED, FederatedOutput.FOUT);
		BinaryOp recompiled = binaryAtLine(94);
		Hop originalMatrixInput = recompiled.getInput(1);

		invokeScalarBinaryRewrite(recompiled);

		assertSame(originalMatrixInput, recompiled.getInput(1));
	}

	@Test
	public void staticScalarDecompositionDoesNotRewriteAPlannerOwnedRuntimeAdapter() throws Exception {
		BinaryOp authority = binaryAtLine(95);
		FederatedPlannerUtils.registerPlannerRecompileState(
			authority, ExecType.CP, FederatedOutput.LOUT);
		BinaryOp matrixProduct = binaryAtLine(96);
		Hop scalarAdapter = HopRewriteUtils.createUnary(matrixProduct, OpOp1.CAST_AS_SCALAR);
		DataOp parent = new DataOp("result", DataType.SCALAR, ValueType.FP64,
			scalarAdapter, OpOpData.TRANSIENTWRITE, null);

		Method rewrite = RewriteAlgebraicSimplificationStatic.class.getDeclaredMethod(
			"simplifyBinaryMatrixScalarOperation", Hop.class, Hop.class, int.class);
		rewrite.setAccessible(true);
		Hop result = (Hop) rewrite.invoke(null, parent, scalarAdapter, 0);

		assertSame(scalarAdapter, result);
		assertSame(scalarAdapter, parent.getInput(0));
	}

	@Test
	public void dynamicOneByOneTransposeRetainsSelectedOccurrence() throws Exception {
		DataOp input = matrixRead("input", 1, 1);
		ReorgOp transpose = new ReorgOp("transpose", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.TRANS, input);
		transpose.setPlannerPlacementSelected(true);
		transpose.setForcedExecType(ExecType.CP);
		transpose.setFederatedOutput(FederatedOutput.LOUT);
		DataOp parent = new DataOp("result", DataType.MATRIX, ValueType.FP64,
			transpose, OpOpData.TRANSIENTWRITE, null);

		Method rewrite = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"removeUnnecessaryReorgOperation", Hop.class, Hop.class, int.class);
		rewrite.setAccessible(true);
		Hop result = (Hop) rewrite.invoke(null, parent, transpose, 0);

		assertSame(transpose, result);
		assertSame(transpose, parent.getInput(0));
	}

	@Test
	public void dynamicColAggregateRetainsSelectedDirectionAndResultType() throws Exception {
		DataOp input = matrixRead("input", 10, 1);
		AggUnaryOp aggregate = new AggUnaryOp("colSums", DataType.MATRIX, ValueType.FP64,
			AggOp.SUM, Direction.Col, input);
		aggregate.setPlannerPlacementSelected(true);
		aggregate.setForcedExecType(ExecType.CP);
		aggregate.setFederatedOutput(FederatedOutput.LOUT);
		DataOp parent = new DataOp("result", DataType.MATRIX, ValueType.FP64,
			aggregate, OpOpData.TRANSIENTWRITE, null);

		Method rewrite = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"simplifyColwiseAggregate", Hop.class, Hop.class, int.class);
		rewrite.setAccessible(true);
		Hop result = (Hop) rewrite.invoke(null, parent, aggregate, 0);

		assertSame(aggregate, result);
		assertEquals(Direction.Col, aggregate.getDirection());
		assertEquals(DataType.MATRIX, aggregate.getDataType());
	}

	@Test
	public void physicalOneByOneTransposeRetainsSelectedInstruction() {
		DataOp input = matrixRead("input", 1, 1);
		ReorgOp transpose = new ReorgOp("transpose", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.TRANS, input);
		transpose.setPlannerPlacementSelected(true);
		transpose.setForcedExecType(ExecType.CP);
		transpose.setFederatedOutput(FederatedOutput.LOUT);

		assertTrue(transpose.constructLops() instanceof Transform);
		assertEquals(transpose.getHopID(), transpose.getLops().getHopID());
	}

	@Test
	public void hiddenLeftTransposeLoweringIsDisabledAfterPlacement() {
		DMLConfig oldConfig = ConfigurationManager.getDMLConfig();
		DMLConfig testConfig = new DMLConfig(oldConfig);
		testConfig.setTextValue(DMLConfig.COMPRESSED_LINALG, "false");
		ConfigurationManager.setGlobalConfig(testConfig);
		ConfigurationManager.setLocalConfig(testConfig);
		try {
			DataOp x = matrixRead("X", 100000, 100);
			ReorgOp transposeX = HopRewriteUtils.createTranspose(x);
			DataOp y = matrixRead("Y", 100000, 2);
			AggBinaryOp product = HopRewriteUtils.createMatrixMultiply(transposeX, y);
			assertTrue(product.usesLeftTransposeRewrite(ExecType.CP));

			product.setPlannerPlacementSelected(true);
			product.setForcedExecType(ExecType.CP);
			product.setFederatedOutput(FederatedOutput.LOUT);
			assertFalse(product.usesLeftTransposeRewrite(ExecType.CP));
		}
		finally {
			ConfigurationManager.setGlobalConfig(oldConfig);
			ConfigurationManager.setLocalConfig(oldConfig);
		}
	}

	@Test
	public void ctableReshapeRewriteRetainsSelectedLocalMaterializationBoundary() {
		LiteralOp rows = new LiteralOp(100L);
		LiteralOp cols = new LiteralOp(1L);
		LiteralOp dims = new LiteralOp(0L);
		LiteralOp byRow = new LiteralOp(true);
		ReorgOp left = new ReorgOp("left", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.RESHAPE, List.of(matrixRead("leftInput", 10, 10), rows, cols, dims, byRow));
		ReorgOp right = new ReorgOp("right", DataType.MATRIX, ValueType.FP64,
			ReOrgOp.RESHAPE, List.of(matrixRead("rightInput", 10, 10), rows, cols, dims, byRow));
		TernaryOp ctable = new TernaryOp("table", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, left, right, new LiteralOp(1D));

		assertTrue(ctable.isCTableReshapeRewriteApplicable(ExecType.CP,
			Ctable.OperationTypes.CTABLE_TRANSFORM_SCALAR_WEIGHT));
		FederatedLocalMaterializeRegistry.registerConsumerInputs(-1L, right.getHopID(),
			List.of(new ConsumerInputSpec(ctable.getHopID(), 1)), "ROW", "selected-boundary");

		assertFalse("ctable fusion must not erase the selected FED/FOUT-to-CP edge",
			ctable.isCTableReshapeRewriteApplicable(ExecType.CP,
				Ctable.OperationTypes.CTABLE_TRANSFORM_SCALAR_WEIGHT));
	}

	@Test
	public void staticComparisonChainRetainsBothPlannedOccurrencesBeforeStateRestore() throws Exception {
		DataOp left = matrixRead("left", 100, 1);
		DataOp right = matrixRead("right", 100, 1);
		BinaryOp inner = new BinaryOp("inner", DataType.MATRIX, ValueType.BOOLEAN,
			OpOp2.LESSEQUAL, left, right);
		inner.setBeginLine(211);
		inner.setBeginColumn(9);
		inner.setEndLine(211);
		inner.setEndColumn(27);
		BinaryOp outer = new BinaryOp("outer", DataType.MATRIX, ValueType.BOOLEAN,
			OpOp2.EQUAL, inner, new LiteralOp(0L));
		outer.setBeginLine(213);
		outer.setBeginColumn(8);
		outer.setEndLine(213);
		outer.setEndColumn(28);
		DataOp parent = new DataOp("result", DataType.MATRIX, ValueType.BOOLEAN,
			outer, OpOpData.TRANSIENTWRITE, null);

		FederatedPlannerUtils.registerPlannerRecompileState(
			inner, ExecType.CP, FederatedOutput.LOUT);
		FederatedPlannerUtils.registerPlannerRecompileState(
			outer, ExecType.CP, FederatedOutput.LOUT);

		Method rewrite = RewriteAlgebraicSimplificationStatic.class.getDeclaredMethod(
			"simplifyBinaryComparisonChain", Hop.class, Hop.class, int.class);
		rewrite.setAccessible(true);
		Hop result = (Hop) rewrite.invoke(null, parent, outer, 0);

		assertSame(outer, result);
		assertSame(outer, parent.getInput(0));
		assertSame(inner, outer.getInput(0));
	}

	@Test
	public void dynamicAlgebraicRewriteCannotReplaceAPlannedEmptySliceWithDatagen() {
		DataOp input = matrixRead("empty", 10, 10);
		input.setNnz(0);
		IndexingOp slice = new IndexingOp("slice", DataType.MATRIX, ValueType.FP64,
			input, new LiteralOp(1L), new LiteralOp(1L), new LiteralOp(1L),
			new LiteralOp(1L), true, true);
		slice.setDim1(1);
		slice.setDim2(1);
		slice.setNnz(0);
		slice.setBeginLine(365);
		slice.setBeginColumn(12);
		slice.setEndLine(365);
		slice.setEndColumn(20);
		DataOp parent = new DataOp("result", DataType.MATRIX, ValueType.FP64,
			slice, OpOpData.TRANSIENTWRITE, null);

		FederatedPlannerUtils.registerPlannerRecompileState(
			slice, ExecType.CP, FederatedOutput.LOUT);
		new RewriteAlgebraicSimplificationDynamic().rewriteHopDAG(parent, null);

		assertSame(slice, parent.getInput(0));
	}

	private static void invokeScalarBinaryRewrite(Hop hop) throws Exception {
		Method rewrite = RewriteAlgebraicSimplificationDynamic.class.getDeclaredMethod(
			"simplifyScalarMVBinaryOperation", Hop.class);
		rewrite.setAccessible(true);
		rewrite.invoke(null, hop);
	}

	private static BinaryOp binaryAtLine(int line) {
		DataOp left = matrixRead("left", 10, 10);
		DataOp right = matrixRead("right", 1, 1);
		BinaryOp binary = new BinaryOp("product", DataType.MATRIX, ValueType.FP64,
			OpOp2.MULT, left, right);
		binary.setBeginLine(line);
		binary.setBeginColumn(4);
		binary.setEndLine(line);
		binary.setEndColumn(61);
		return binary;
	}

	private static DataOp matrixRead(String name, long rows, long cols) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
	}
}
