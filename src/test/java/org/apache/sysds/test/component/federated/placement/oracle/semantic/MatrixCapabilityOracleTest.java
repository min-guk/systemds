/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.test.component.federated.placement.oracle.semantic;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Direction;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.ColumnBounds;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Family;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.FullRowColumnSlice;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Input;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Layout;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Output;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Status;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.Tuple;
import org.apache.sysds.test.component.federated.placement.oracle.semantic.MatrixCapabilityOracle.WorkerRow;

public class MatrixCapabilityOracleTest {
	private static final MatrixCapabilityOracle ORACLE = new MatrixCapabilityOracle();
	private static Input local(long rows, long cols) { return new Input(Layout.LOCAL, 0, rows, cols); }
	private static Input fed(Layout layout, int ranges, long rows, long cols) {
		return new Input(layout, ranges, rows, cols);
	}

	@Test public void validDynamicColumnPreservesOnlyTheExactRowAxis() {
		List<WorkerRow> rows = List.of(new WorkerRow("w3", 4, 7), new WorkerRow("w1", 0, 2),
			new WorkerRow("w4", 7, 8), new WorkerRow("w2", 2, 4));
		var slice = new FullRowColumnSlice(fed(Layout.ROW, 4, 8, 2100), rows, true,
			ColumnBounds.VALID_AT_RUNTIME, Output.FOUT);
		var verdict = ORACLE.checkFullRowColumnSliceRowAxis(slice);
		Assert.assertEquals(Status.SUPPORTED, verdict.status());
		Assert.assertEquals(List.of(rows.get(1), rows.get(3), rows.get(0), rows.get(2)),
			verdict.workerRows());
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkFullRowColumnSliceRowAxis(
			new FullRowColumnSlice(slice.input(), rows, false, slice.columnBounds(), Output.FOUT)).status());
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkFullRowColumnSliceRowAxis(
			new FullRowColumnSlice(slice.input(), rows, true, ColumnBounds.UNKNOWN, Output.FOUT)).status());
		Assert.assertEquals(Status.REJECTED, ORACLE.checkFullRowColumnSliceRowAxis(
			new FullRowColumnSlice(slice.input(), rows, true, ColumnBounds.INVALID, Output.FOUT)).status());
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkFullRowColumnSliceRowAxis(
			new FullRowColumnSlice(fed(Layout.COL, 4, 8, 2100), rows, true,
				ColumnBounds.VALID_AT_RUNTIME, Output.FOUT)).status());
		Assert.assertEquals(Status.UNKNOWN, ORACLE.checkFullRowColumnSliceRowAxis(
			new FullRowColumnSlice(slice.input(), List.of(rows.get(1), new WorkerRow("w2", 3, 4),
				rows.get(0), rows.get(2)), true, ColumnBounds.VALID_AT_RUNTIME, Output.FOUT)).status());
	}
	private static Tuple tuple(Family family, Input left, Input right, Output out,
		Direction direction, boolean weighted, boolean byRow, long outRows, long outCols, long... cells) {
		return new Tuple(family, left, right, out, true, direction, weighted,
			byRow, outRows, outCols, cells);
	}
	private static void assertVerdict(Status expected, Layout layout, Tuple tuple) {
		MatrixCapabilityOracle.Verdict verdict = ORACLE.check(tuple);
		Assert.assertEquals(verdict.source(), expected, verdict.status());
		Assert.assertEquals(verdict.source(), layout, verdict.outputLayout());
	}

	@Test public void sparkMMHasNarrowRowBranches() {
		Input row = fed(Layout.ROW, 2, 6, 3);
		Input rhs = local(3, 2);
		assertVerdict(Status.SUPPORTED, Layout.ROW,
			tuple(Family.MM_SPARK, row, rhs, Output.FOUT, Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.SUPPORTED, Layout.LOCAL,
			tuple(Family.MM_SPARK, row, rhs, Output.LOUT, Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.MM_SPARK, local(6, 3), rhs, Output.LOUT, Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.UNKNOWN, null,
			tuple(Family.MM_SPARK, fed(Layout.COL, 2, 6, 3), rhs, Output.FOUT,
				Direction.NONE, false, false, 0, 0));
	}

	@Test public void cpMMDistinguishesSingleFullAndLocalPair() {
		assertVerdict(Status.SUPPORTED, Layout.FULL,
			tuple(Family.MM_CP, local(2, 3), fed(Layout.FULL, 1, 3, 4), Output.FOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.SUPPORTED, Layout.LOCAL,
			tuple(Family.MM_CP, fed(Layout.ROW, 2, 2, 3), local(3, 4), Output.LOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.MM_CP, local(2, 3), local(3, 4), Output.LOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.MM_CP, local(2, 5), fed(Layout.FULL, 1, 3, 4), Output.FOUT,
				Direction.NONE, false, false, 0, 0));
	}

	@Test public void chainRequiresRowOrSingleFullAndLocalResult() {
		Input vector = local(3, 1);
		assertVerdict(Status.SUPPORTED, Layout.LOCAL,
			tuple(Family.MM_CHAIN, fed(Layout.ROW, 2, 6, 3), vector, Output.LOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.SUPPORTED, Layout.LOCAL,
			tuple(Family.MM_CHAIN, fed(Layout.FULL, 1, 6, 3), vector, Output.LOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.MM_CHAIN, fed(Layout.COL, 2, 6, 3), vector, Output.LOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.MM_CHAIN, fed(Layout.ROW, 2, 6, 3), vector, Output.FOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.UNKNOWN, null,
			tuple(Family.MM_CHAIN, fed(Layout.ROW, 2, 6, 3), vector, Output.LOUT,
				Direction.NONE, true, false, 0, 0));
	}

	@Test public void tsmmDirectionAndOutputAreExplicit() {
		assertVerdict(Status.SUPPORTED, Layout.BROADCAST,
			tuple(Family.TSMM, fed(Layout.ROW, 2, 6, 3), null, Output.FOUT,
				Direction.LEFT, false, false, 0, 0));
		assertVerdict(Status.SUPPORTED, Layout.LOCAL,
			tuple(Family.TSMM, fed(Layout.COL, 2, 6, 3), null, Output.LOUT,
				Direction.RIGHT, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.TSMM, fed(Layout.ROW, 2, 6, 3), null, Output.LOUT,
				Direction.RIGHT, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.TSMM, local(6, 3), null, Output.LOUT,
				Direction.LEFT, false, false, 0, 0));
	}

	@Test public void transposeSwapsAxesAndRejectsPart() {
		assertVerdict(Status.SUPPORTED, Layout.COL,
			tuple(Family.TRANSPOSE, fed(Layout.ROW, 2, 6, 3), null, Output.FOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.SUPPORTED, Layout.LOCAL,
			tuple(Family.TRANSPOSE, fed(Layout.BROADCAST, 2, 6, 3), null, Output.LOUT,
				Direction.NONE, false, false, 0, 0));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.TRANSPOSE, fed(Layout.PART, 2, 6, 3), null, Output.FOUT,
				Direction.NONE, false, false, 0, 0));
	}

	@Test public void reshapeRequiresEqualCellsAndRangeDivisibility() {
		Input full = fed(Layout.FULL, 1, 2, 6);
		assertVerdict(Status.SUPPORTED, Layout.ROW,
			tuple(Family.RESHAPE, full, null, Output.FOUT,
				Direction.NONE, false, true, 3, 4, 12));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.RESHAPE, full, null, Output.FOUT,
				Direction.NONE, false, true, 3, 5, 12));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.RESHAPE, fed(Layout.ROW, 2, 2, 6), null, Output.FOUT,
				Direction.NONE, false, true, 3, 4, 5, 7));
		assertVerdict(Status.UNKNOWN, null,
			tuple(Family.RESHAPE, fed(Layout.ROW, 2, 2, 6), null, Output.FOUT,
				Direction.NONE, false, true, 3, 4, 4, 8));
		assertVerdict(Status.REJECTED, null,
			tuple(Family.RESHAPE, full, null, Output.LOUT,
				Direction.NONE, false, true, 3, 4, 12));
	}

	@Test public void solveAndPrivateInputsStayUnknown() {
		Tuple solve = tuple(Family.SOLVE, fed(Layout.ROW, 2, 3, 3), local(3, 1),
			Output.LOUT, Direction.NONE, false, false, 0, 0);
		assertVerdict(Status.UNKNOWN, null, solve);
		Tuple privateTsmm = new Tuple(Family.TSMM, fed(Layout.ROW, 2, 6, 3), null,
			Output.LOUT, false, Direction.LEFT, false, false, 0, 0, null);
		assertVerdict(Status.UNKNOWN, null, privateTsmm);
	}
}
