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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.AppendFEDInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class AppendFoutRuntimeTest {
	private static class NoOpFederationMap extends FederationMap {
		NoOpFederationMap(long id, List<Pair<FederatedRange, FederatedData>> map, FType type) {
			super(id, map, type);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			Future<FederatedResponse>[] responses = new Future[getSize()];
			for(int i = 0; i < responses.length; i++)
				responses[i] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(8)));
			return responses;
		}
	}

	private static class ResultFederationMap extends FederationMap {
		private final List<MatrixBlock> _results;

		ResultFederationMap(long id, List<Pair<FederatedRange, FederatedData>> map,
			FType type, List<MatrixBlock> results) {
			super(id, map, type);
			_results = results;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			boolean get = false;
			for(FederatedRequest request : requests)
				get |= request != null && request.getType() == FederatedRequest.RequestType.GET_VAR;
			Future<FederatedResponse>[] responses = new Future[getSize()];
			for(int i = 0; i < responses.length; i++) {
				MatrixBlock result = _results.get(i % _results.size());
				Object data = get ? result : Long.valueOf(result.getNonZeros());
				responses[i] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, data));
			}
			return responses;
		}

		@Override
		public FederationMap copyWithNewID(long id) {
			List<Pair<FederatedRange, FederatedData>> copy = new ArrayList<>();
			for(Pair<FederatedRange, FederatedData> entry : getMap())
				copy.add(Pair.of(new FederatedRange(entry.getLeft()),
					entry.getRight().copyWithNewID(id)));
			return new ResultFederationMap(id, copy, getType(), _results);
		}
	}

	@Test
	public void singleWorkerFullPlusLocalCbindFoutStaysRemoteFull() {
		assertSingleWorkerFullLocalAppend(true, true, 4, 1, 4, 1, 4, 2);
	}

	@Test
	public void singleWorkerLocalPlusFullCbindFoutStaysRemoteFull() {
		assertSingleWorkerFullLocalAppend(false, true, 4, 1, 4, 1, 4, 2);
	}

	@Test
	public void singleWorkerFullPlusLocalRbindFoutStaysRemoteFull() {
		assertSingleWorkerFullLocalAppend(true, false, 2, 3, 2, 3, 4, 3);
	}

	@Test
	public void singleWorkerLocalPlusFullRbindFoutStaysRemoteFull() {
		assertSingleWorkerFullLocalAppend(false, false, 2, 3, 2, 3, 4, 3);
	}

	@Test
	public void rowCbindLoutIsLocal() {
		assertPartitionedLout(FType.ROW, true, 4, 2, 4, 4,
			List.of(new MatrixBlock(2, 4, 1.0), new MatrixBlock(2, 4, 2.0)));
	}

	@Test
	public void rowRbindLoutIsLocal() {
		assertPartitionedLout(FType.ROW, false, 4, 2, 8, 2,
			List.of(new MatrixBlock(2, 2, 1.0), new MatrixBlock(2, 2, 2.0)));
	}

	@Test
	public void colRbindLoutIsLocal() {
		assertPartitionedLout(FType.COL, false, 4, 2, 8, 2,
			List.of(new MatrixBlock(8, 1, 1.0), new MatrixBlock(8, 1, 2.0)));
	}

	@Test
	public void colCbindLoutIsLocal() {
		assertPartitionedLout(FType.COL, true, 4, 2, 4, 4,
			List.of(new MatrixBlock(4, 1, 1.0), new MatrixBlock(4, 1, 2.0)));
	}

	private static void assertSingleWorkerFullLocalAppend(boolean federatedLeft, boolean cbind,
		int leftRows, int leftCols, int rightRows, int rightCols, int outRows, int outCols) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", federatedLeft
			? remoteFullMatrix("L", leftRows, leftCols, 11)
			: localMatrix("L", leftRows, leftCols));
		ec.setVariable("R", federatedLeft
			? localMatrix("R", rightRows, rightCols)
			: remoteFullMatrix("R", rightRows, rightCols, 11));
		ec.setVariable("O", emptyMatrix("O", outRows, outCols));

		String inst = InstructionUtils.concatOperands(
			"FED", "append",
			InstructionUtils.concatOperandParts("L", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("R", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("1", Types.DataType.SCALAR.name(), ValueType.INT64.name(), "true"),
			InstructionUtils.concatOperandParts("O", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			Boolean.toString(cbind), "FOUT");
		AppendFEDInstruction.parseInstruction(inst).processInstruction(ec);

		MatrixObject out = ec.getMatrixObject("O");
		assertTrue(out.isFederated());
		assertEquals(FType.FULL, out.getFedMapping().getType());
		assertEquals("FULL is one complete non-replicated worker object", 1, out.getFedMapping().getSize());
		assertFalse("A planner-selected FOUT must not contain coordinator-local federated data",
			out.getFedMapping().getMap().get(0).getRight() instanceof FederatedLocalData);
		assertEquals(outRows, out.getFedMapping().getFederatedRanges()[0].getEndDims()[0]);
		assertEquals(outCols, out.getFedMapping().getFederatedRanges()[0].getEndDims()[1]);
	}

	private static void assertPartitionedLout(FType type, boolean cbind, int inputRows,
		int inputCols, int outputRows, int outputCols, List<MatrixBlock> workerResults) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = partitionedMatrix("I", inputRows, inputCols, type, workerResults);
		ec.setVariable("I", input);
		ec.setVariable("O", emptyMatrix("O", outputRows, outputCols));

		String inst = InstructionUtils.concatOperands(
			"FED", "append",
			InstructionUtils.concatOperandParts("I", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("I", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts(Integer.toString(inputRows), Types.DataType.SCALAR.name(),
				ValueType.INT64.name(), "true"),
			InstructionUtils.concatOperandParts("O", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			Boolean.toString(cbind), "LOUT");
		AppendFEDInstruction.parseInstruction(inst).processInstruction(ec);

		MatrixObject out = ec.getMatrixObject("O");
		assertFalse("LOUT must clear the federated output mapping", out.isFederated());
		MatrixBlock result = out.acquireReadAndRelease();
		assertEquals(outputRows, result.getNumRows());
		assertEquals(outputCols, result.getNumColumns());
		for(int row = 0; row < outputRows; row++)
			for(int col = 0; col < outputCols; col++) {
				int partitionIndex = type == FType.ROW
					? (cbind ? row : row % inputRows) / (inputRows / 2)
					: (cbind ? col % inputCols : col) / (inputCols / 2);
				assertEquals(partitionIndex + 1.0, result.get(row, col), 0.0);
			}
	}

	private static MatrixObject remoteFullMatrix(String name, long rows, long cols, long id) {
		MatrixObject matrix = emptyMatrix(name, rows, cols);
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {rows, cols});
		FederatedData data = new FederatedData(Types.DataType.MATRIX,
			new InetSocketAddress("localhost", 14000), "dummy", id);
		matrix.setFedMapping(new NoOpFederationMap(id, List.of(Pair.of(range, data)), FType.FULL));
		return matrix;
	}

	private static MatrixObject partitionedMatrix(String name, int rows, int cols,
		FType type, List<MatrixBlock> results) {
		MatrixObject matrix = emptyMatrix(name, rows, cols);
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(int i = 0; i < 2; i++) {
			long[] begin = type == FType.ROW ? new long[] {i * (rows / 2), 0}
				: new long[] {0, i * (cols / 2)};
			long[] end = type == FType.ROW ? new long[] {(i + 1) * (rows / 2), cols}
				: new long[] {rows, (i + 1) * (cols / 2)};
			entries.add(Pair.of(new FederatedRange(begin, end),
				new FederatedData(Types.DataType.MATRIX,
					new InetSocketAddress("localhost", 14000 + i), "dummy", 21)));
		}
		matrix.setFedMapping(new ResultFederationMap(21, entries, type, results));
		return matrix;
	}

	private static MatrixObject localMatrix(String name, int rows, int cols) {
		MatrixObject matrix = emptyMatrix(name, rows, cols);
		matrix.acquireModify(new MatrixBlock(rows, cols, 1.0));
		matrix.release();
		return matrix;
	}

	private static MatrixObject emptyMatrix(String name, long rows, long cols) {
		return new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(rows, cols, 1024, -1)));
	}
}
