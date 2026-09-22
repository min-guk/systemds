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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A deliberately finite, planner-independent reading of FED runtime branches.
 * SUPPORTED establishes branch reachability for this tuple, never complete plan
 * feasibility. Unmodeled transfer, privacy, worker state, and mappings remain UNKNOWN.
 */
public final class MatrixCapabilityOracle {
	public enum Family { MM_SPARK, MM_CP, MM_CHAIN, TSMM, SOLVE, TRANSPOSE, RESHAPE }
	public enum Layout { LOCAL, ROW, COL, FULL, BROADCAST, PART, OTHER }
	public enum Output { LOUT, FOUT }
	public enum Status { SUPPORTED, REJECTED, UNKNOWN }
	public enum Direction { LEFT, RIGHT, NONE }
	public enum ColumnBounds { VALID_AT_RUNTIME, INVALID, UNKNOWN }

	/** Only the partitioned row axis: a column slice may change its other dimension. */
	public record WorkerRow(String worker, long begin, long end) { }
	public record FullRowColumnSlice(Input input, List<WorkerRow> workerRows,
		boolean allRows, ColumnBounds columnBounds, Output output) {
		public FullRowColumnSlice {
			Objects.requireNonNull(input);
			workerRows = List.copyOf(Objects.requireNonNull(workerRows));
			Objects.requireNonNull(columnBounds);
			Objects.requireNonNull(output);
		}
	}
	public record RowAxisVerdict(Status status, List<WorkerRow> workerRows, String source) { }

	public record Input(Layout layout, int ranges, long rows, long cols) {
		public Input {
			Objects.requireNonNull(layout);
			if (ranges < 0 || rows <= 0 || cols <= 0)
				throw new IllegalArgumentException("invalid matrix dimensions or ranges");
			if ((layout == Layout.LOCAL) != (ranges == 0))
				throw new IllegalArgumentException("local input must have zero ranges; federated input must have ranges");
		}
	}

	public record Tuple(Family family, Input left, Input right, Output output,
		boolean publicData, Direction direction, boolean weighted,
		boolean byRow, long outRows, long outCols, long[] rangeCells) {
		public Tuple {
			Objects.requireNonNull(family);
			Objects.requireNonNull(left);
			Objects.requireNonNull(output);
			Objects.requireNonNull(direction);
			rangeCells = rangeCells == null ? new long[0] : rangeCells.clone();
		}
		@Override public long[] rangeCells() { return rangeCells.clone(); }
	}

	public record Verdict(Status status, Layout outputLayout, String source) { }

	/**
	 * Conditional layout claim for a valid runtime column index, not a whole-plan or
	 * privacy certificate. No production placement predicate is consulted here.
	 */
	public RowAxisVerdict checkFullRowColumnSliceRowAxis(FullRowColumnSlice slice) {
		Objects.requireNonNull(slice);
		if(slice.columnBounds() == ColumnBounds.INVALID)
			return new RowAxisVerdict(Status.REJECTED, List.of(), "invalid global column interval");
		if(slice.columnBounds() != ColumnBounds.VALID_AT_RUNTIME || !slice.allRows()
			|| slice.output() != Output.FOUT || slice.input().layout() != Layout.ROW
			|| slice.input().ranges() < 2 || slice.workerRows().size() != slice.input().ranges())
			return new RowAxisVerdict(Status.UNKNOWN, List.of(), "row-axis preservation not established");
		List<WorkerRow> sorted = new ArrayList<>(slice.workerRows());
		sorted.sort(Comparator.comparingLong(WorkerRow::begin));
		Set<String> workers = new HashSet<>();
		long next = 0;
		for(WorkerRow row : sorted) {
			if(row.worker() == null || row.worker().isBlank() || !workers.add(row.worker())
				|| row.begin() != next || row.end() <= row.begin() || row.end() > slice.input().rows())
				return new RowAxisVerdict(Status.UNKNOWN, List.of(), "worker-row intervals are not exact");
			next = row.end();
		}
		if(next != slice.input().rows())
			return new RowAxisVerdict(Status.UNKNOWN, List.of(), "worker-row intervals do not cover all rows");
		return new RowAxisVerdict(Status.SUPPORTED, List.copyOf(sorted),
			"conditional full-row column slice preserves only the exact worker-row axis");
	}

	public Verdict check(Tuple t) {
		Objects.requireNonNull(t);
		if (!t.publicData()) return unknown("privacy constraints require a separate proof");
		switch (t.family()) {
			case MM_SPARK: return sparkMM(t);
			case MM_CP: return cpMM(t);
			case MM_CHAIN: return chain(t);
			case TSMM: return tsmm(t);
			case TRANSPOSE: return transpose(t);
			case RESHAPE: return reshape(t);
			case SOLVE: return unknown("no SolveFEDInstruction or solve FED dispatch found");
			default: return unknown("uncovered family");
		}
	}

	private static Verdict sparkMM(Tuple t) {
		if (t.right() == null) return unknown("missing right input");
		if (t.left().cols() != t.right().rows()) return reject("matrix multiplication dimension mismatch");
		if (t.left().layout() == Layout.LOCAL && t.right().layout() == Layout.LOCAL)
			return reject("MMFEDInstruction.java:160-165");
		if (t.left().layout() == Layout.ROW && t.right().layout() == Layout.LOCAL) {
			if (t.output() == Output.FOUT && t.right().cols() > 1)
				return support(Layout.ROW, "MMFEDInstruction.java:100-123");
			if (t.output() == Output.LOUT)
				return support(Layout.LOCAL, "MMFEDInstruction.java:120-123");
		}
		return unknown("partial aggregate, alignment or sliced broadcast not proved");
	}

	private static Verdict cpMM(Tuple t) {
		if (t.right() == null) return unknown("missing right input");
		if (t.left().cols() != t.right().rows()) return reject("matrix multiplication dimension mismatch");
		if (t.left().layout() == Layout.LOCAL && t.right().layout() == Layout.LOCAL)
			return reject("AggregateBinaryFEDInstruction.java:116-122");
		if (t.left().layout() == Layout.ROW && t.right().layout() == Layout.LOCAL) {
			if (t.output() == Output.FOUT)
				return support(Layout.ROW, "AggregateBinaryFEDInstruction.java:186-231");
			return support(Layout.LOCAL, "AggregateBinaryFEDInstruction.java:231-253");
		}
		if (t.left().layout() == Layout.LOCAL && t.right().layout() == Layout.FULL
			&& t.right().ranges() == 1 && t.output() == Output.FOUT)
			return support(Layout.FULL, "AggregateBinaryFEDInstruction.java:268-281");
		return unknown("worker pool, alignment, or aggregate behavior not proved");
	}

	private static Verdict chain(Tuple t) {
		Layout in = t.left().layout();
		if (in != Layout.ROW && !(in == Layout.FULL && t.left().ranges() == 1))
			return reject("MMChainFEDInstruction.java:59-65,100-104");
		if (t.output() == Output.FOUT) return reject("MMChainFEDInstruction.java:118-149 always materializes locally");
		if (t.right() == null || t.right().rows() != t.left().cols() || t.right().cols() != 1)
			return unknown("MMChain vector shape not established");
		if (t.weighted()) return unknown("weighted third input and row alignment not modeled");
		return support(Layout.LOCAL, "MMChainFEDInstruction.java:126-136");
	}

	private static Verdict tsmm(Tuple t) {
		Layout in = t.left().layout();
		if (in == Layout.LOCAL || !((in == Layout.ROW && t.direction() == Direction.LEFT)
			|| (in == Layout.COL && t.direction() == Direction.RIGHT)))
			return reject("TsmmFEDInstruction.java:55-60,84-99");
		return t.output() == Output.FOUT
			? support(Layout.BROADCAST, "TsmmFEDInstruction.java:105-114")
			: support(Layout.LOCAL, "TsmmFEDInstruction.java:115-128");
	}

	private static Verdict transpose(Tuple t) {
		Layout in = t.left().layout();
		if (in == Layout.LOCAL || in == Layout.PART || in == Layout.OTHER)
			return reject("ReorgFEDInstruction.java:171-180");
		if (t.output() == Output.LOUT)
			return support(Layout.LOCAL, "ReorgFEDInstruction.java:207-221");
		Layout out = in == Layout.ROW ? Layout.COL : in == Layout.COL ? Layout.ROW : in;
		return support(out, "ReorgFEDInstruction.java:189-205; FederationMap.transpose");
	}

	private static Verdict reshape(Tuple t) {
		if (t.left().layout() == Layout.LOCAL) return reject("ReshapeFEDInstruction.java:111-113");
		if (t.outRows() <= 0 || t.outCols() <= 0) return unknown("inferred dimension resolution not modeled");
		long inputCells;
		long outputCells;
		try {
			inputCells = Math.multiplyExact(t.left().rows(), t.left().cols());
			outputCells = Math.multiplyExact(t.outRows(), t.outCols());
		}
		catch (ArithmeticException ex) {
			return unknown("matrix cell count overflow");
		}
		if (inputCells != outputCells)
			return reject("ReshapeFEDInstruction.java:114-116");
		long[] cells = t.rangeCells();
		if (cells.length != t.left().ranges()) return unknown("range cardinality not established");
		long denominator = t.byRow() ? t.outCols() : t.outRows();
		for (long cell : cells)
			if (cell <= 0 || cell % denominator != 0)
				return reject("ReshapeFEDInstruction.java:118-124");
		if (t.output() == Output.LOUT) return reject("ReshapeFEDInstruction.java:142-165 publishes a FED mapping");
		if (t.left().layout() != Layout.FULL || t.left().ranges() != 1 || cells[0] != inputCells)
			return unknown("range placement and reindexing not proved");
		return support(t.byRow() ? Layout.ROW : Layout.COL, "ReshapeFEDInstruction.java:128-165");
	}

	private static Verdict support(Layout layout, String source) { return new Verdict(Status.SUPPORTED, layout, source); }
	private static Verdict reject(String source) { return new Verdict(Status.REJECTED, null, source); }
	private static Verdict unknown(String reason) { return new Verdict(Status.UNKNOWN, null, reason); }
}
