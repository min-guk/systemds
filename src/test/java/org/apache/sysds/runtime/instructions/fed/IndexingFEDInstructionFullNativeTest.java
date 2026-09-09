/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.runtime.instructions.fed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class IndexingFEDInstructionFullNativeTest {
	private static final int ROWS = 4;
	private static final int COLS = 2;
	private static final int PORT = 19001;

	private static final class AddressedLocalData extends FederatedLocalData {
		private final InetSocketAddress address;
		private final List<FederatedRequest> runtimeRequests = new ArrayList<>();

		AddressedLocalData(long id, MatrixObject data, InetSocketAddress address) {
			super(id, data);
			this.address = address;
		}

		@Override
		public InetSocketAddress getAddress() {
			return address;
		}

		@Override
		public FederatedData copyWithNewID(long varID) {
			return new DelegatingLocalData(varID, address, this);
		}

		@Override
		public synchronized Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			runtimeRequests.addAll(Arrays.asList(requests));
			return super.executeFederatedOperation(requests);
		}

		private Future<FederatedResponse> inspect(FederatedRequest request) {
			return super.executeFederatedOperation(request);
		}
	}

	private static final class DelegatingLocalData extends FederatedData {
		private final AddressedLocalData owner;

		DelegatingLocalData(long id, InetSocketAddress address, AddressedLocalData owner) {
			super(DataType.MATRIX, address, "in-process-worker", id);
			this.owner = owner;
		}

		@Override
		public FederatedData copyWithNewID(long varID) {
			return new DelegatingLocalData(varID, getAddress(), owner);
		}

		@Override
		public Future<FederatedResponse> executeFederatedOperation(FederatedRequest... requests) {
			return owner.executeFederatedOperation(requests);
		}
	}

	private static final class RecordingFederationMap extends FederationMap {
		private int executeCalls;
		private int broadcastCalls;
		private CacheableData<?> broadcastInput;
		private final List<FederatedRequest> requests = new ArrayList<>();

		RecordingFederationMap(long id, List<Pair<FederatedRange, FederatedData>> entries, FType type) {
			super(id, entries, type);
		}

		@Override
		public FederatedRequest broadcast(CacheableData<?> data) {
			broadcastCalls++;
			broadcastInput = data;
			return new FederatedRequest(FederatedRequest.RequestType.PUT_VAR,
				FederationUtils.getNextFedDataID(), new MatrixBlock(1, 1, 0.0));
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... batch) {
			executeCalls++;
			record(batch);
			return success();
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait,
			FederatedRequest[] slices, FederatedRequest... batch) {
			executeCalls++;
			record(slices);
			record(batch);
			return success();
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait,
			FederatedRange[] ranges, FederatedRequest elseRequest,
			FederatedRequest[] slices1, FederatedRequest[] slices2, FederatedRequest... batch) {
			executeCalls++;
			record(elseRequest);
			record(slices1);
			record(slices2);
			record(batch);
			return success();
		}

		private void record(FederatedRequest... batch) {
			if(batch != null)
				for(FederatedRequest request : batch)
					if(request != null)
						requests.add(request);
		}

		@SuppressWarnings("unchecked")
		private Future<FederatedResponse>[] success() {
			return new Future[] {CompletableFuture.completedFuture(new FederatedResponse(
				FederatedResponse.ResponseType.SUCCESS, Long.valueOf(0)))};
		}

		private long requestCount(FederatedRequest.RequestType type) {
			return requests.stream().filter(request -> request.getType() == type).count();
		}

		private String workerInstruction() {
			return requests.stream()
				.filter(request -> request.getType() == FederatedRequest.RequestType.EXEC_INST)
				.map(request -> String.valueOf(request.getParam(0)))
				.findFirst().orElse("");
		}
	}

	@Test
	public void localLhsUploadsOnlyToSingleFullRhsAnchor() {
		RecordingFederationMap rhsMap = singleFullMap(81, ROWS, 1, PORT);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec);

		MatrixObject out = ec.getMatrixObject("O");
		assertEquals(1, rhsMap.executeCalls);
		assertEquals(1, rhsMap.broadcastCalls);
		assertSame("Only the coordinator-local LHS may be uploaded", ec.getMatrixObject("L"), rhsMap.broadcastInput);
		assertEquals("The protected RHS must never be collected", 0,
			rhsMap.requestCount(FederatedRequest.RequestType.GET_VAR));
		assertEquals(1, rhsMap.requestCount(FederatedRequest.RequestType.PUT_VAR));
		assertTrue(rhsMap.workerInstruction().startsWith("CP" + Lop.OPERAND_DELIMITOR + "leftIndex"));
		assertRemoteFullOutput(out, ROWS, COLS, PORT);
	}

	@Test
	public void mapLeftIndexIsNormalizedToTheCpWorkerOpcode() {
		RecordingFederationMap rhsMap = singleFullMap(81, ROWS, 1, PORT);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		IndexingFEDInstruction.parseInstruction(instruction("mapLeftIndex", 1, ROWS, 1, 1))
			.processInstruction(ec);

		assertEquals(1, rhsMap.executeCalls);
		assertTrue(rhsMap.workerInstruction().startsWith("CP" + Lop.OPERAND_DELIMITOR + "leftIndex"));
		assertFalse(rhsMap.workerInstruction().contains("mapLeftIndex"));
		assertRemoteFullOutput(ec.getMatrixObject("O"), ROWS, COLS, PORT);
	}

	@Test
	public void fullLhsAndFullRhsOnSameEndpointUseRemoteIdsDirectly() {
		RecordingFederationMap lhsMap = singleFullMap(71, ROWS, COLS, PORT);
		RecordingFederationMap rhsMap = singleFullMap(81, ROWS, 1, PORT);
		ExecutionContext ec = context(remoteMatrix("L", ROWS, COLS, lhsMap),
			remoteMatrix("R", ROWS, 1, rhsMap));

		IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 2, 2)).processInstruction(ec);

		assertEquals(1, lhsMap.executeCalls);
		assertEquals(0, lhsMap.broadcastCalls);
		assertEquals(0, rhsMap.broadcastCalls);
		assertEquals(0, lhsMap.requestCount(FederatedRequest.RequestType.PUT_VAR));
		assertEquals(0, lhsMap.requestCount(FederatedRequest.RequestType.GET_VAR));
		String[] workerParts = lhsMap.workerInstruction().split(Lop.OPERAND_DELIMITOR);
		assertTrue(workerParts[2].startsWith("71" + Lop.DATATYPE_PREFIX));
		assertTrue(workerParts[3].startsWith("81" + Lop.DATATYPE_PREFIX));
		assertRemoteFullOutput(ec.getMatrixObject("O"), ROWS, COLS, PORT);
	}

	@Test
	public void inProcessWorkerKernelExecutesTwoSequentialFullColumnUpdates() throws Exception {
		InetSocketAddress endpoint = new InetSocketAddress("localhost", PORT);
		AddressedLocalData rhs1Data = new AddressedLocalData(1001,
			ExecutionContext.createMatrixObject(column(10, 20, 30, 40)), endpoint);
		AddressedLocalData rhs2Data = new AddressedLocalData(1002,
			ExecutionContext.createMatrixObject(column(11, 21, 31, 41)), endpoint);
		MatrixObject rhs1 = remoteMatrix("R1", ROWS, 1,
			new FederationMap(1001, List.of(Pair.of(originRange(ROWS, 1), rhs1Data)), FType.FULL));
		MatrixObject rhs2 = remoteMatrix("R2", ROWS, 1,
			new FederationMap(1002, List.of(Pair.of(originRange(ROWS, 1), rhs2Data)), FType.FULL));
		MatrixObject lhs = ExecutionContext.createMatrixObject(matrix(
			new double[][] {{1, 2}, {3, 4}, {5, 6}, {7, 8}}));
		ExecutionContext ec = context(lhs, rhs1);

		IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec);
		MatrixObject first = ec.getMatrixObject("O");
		ec.setVariable("L", first);
		ec.setVariable("R", rhs2);
		ec.setVariable("O", emptyMatrix("O", ROWS, COLS));
		IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 2, 2)).processInstruction(ec);
		MatrixObject second = ec.getMatrixObject("O");

		long runtimeGets = rhs1Data.runtimeRequests.stream().filter(
			request -> request.getType() == FederatedRequest.RequestType.GET_VAR).count()
			+ rhs2Data.runtimeRequests.stream().filter(
				request -> request.getType() == FederatedRequest.RequestType.GET_VAR).count();
		assertEquals("native updates must not collect either protected RHS", 0, runtimeGets);
		assertEquals("only the original local LHS is uploaded", 1,
			rhs1Data.runtimeRequests.stream().filter(
				request -> request.getType() == FederatedRequest.RequestType.PUT_VAR).count());
		assertEquals(0, rhs2Data.runtimeRequests.stream().filter(
			request -> request.getType() == FederatedRequest.RequestType.PUT_VAR).count());

		// Test-only inspection occurs after the no-GET assertion. The two updates above execute
		// through FederatedWorkerHandler and the real MatrixIndexingCPInstruction kernel.
		FederatedResponse response = rhs1Data.inspect(new FederatedRequest(
			FederatedRequest.RequestType.GET_VAR, second.getFedMapping().getID())).get();
		MatrixBlock actual = (MatrixBlock) response.getData()[0];
		for(int row = 0; row < ROWS; row++) {
			assertEquals(10 + 10 * row, actual.get(row, 0), 0.0);
			assertEquals(11 + 10 * row, actual.get(row, 1), 0.0);
		}
	}

	@Test
	public void differentFullEndpointsFailBeforeRpc() {
		RecordingFederationMap lhsMap = singleFullMap(71, ROWS, COLS, PORT);
		RecordingFederationMap rhsMap = singleFullMap(81, ROWS, 1, PORT + 1);
		ExecutionContext ec = context(remoteMatrix("L", ROWS, COLS, lhsMap),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, lhsMap.executeCalls);
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, lhsMap.broadcastCalls);
	}

	@Test
	public void multiEntryFullRhsFailsBeforeRpc() {
		RecordingFederationMap rhsMap = multiEntryFullMap(81, ROWS, 1);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	@Test
	public void nonOriginFullRangeFailsBeforeRpc() {
		RecordingFederationMap rhsMap = new RecordingFederationMap(81, List.of(Pair.of(
			new FederatedRange(new long[] {1, 0}, new long[] {ROWS + 1, 1}), data(81, PORT))), FType.FULL);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	@Test
	public void uninitializedFullMapFailsBeforeRpc() {
		RecordingFederationMap rhsMap = singleFullMap(-1, ROWS, 1, PORT);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	@Test
	public void uninitializedFullEntryFailsBeforeRpc() {
		RecordingFederationMap rhsMap = new RecordingFederationMap(81, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {ROWS, 1}), data(-1, PORT))), FType.FULL);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	@Test
	public void mismatchedMapAndEntryIdsFailBeforeRpc() {
		RecordingFederationMap rhsMap = new RecordingFederationMap(81, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {ROWS, 1}), data(82, PORT))), FType.FULL);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	@Test
	public void fullLhsWithLocalMatrixDoesNotEnterCopyOnlyLegacyPath() {
		RecordingFederationMap lhsMap = singleFullMap(71, ROWS, COLS, PORT);
		ExecutionContext ec = context(remoteMatrix("L", ROWS, COLS, lhsMap), localMatrix("R", ROWS, 1));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, lhsMap.executeCalls);
		assertEquals(0, lhsMap.broadcastCalls);
	}

	@Test
	public void scalarUpdateRetainsTheInitializedSingleFullPath() {
		RecordingFederationMap lhsMap = singleFullMap(71, ROWS, COLS, PORT);
		ExecutionContext ec = scalarContext(remoteMatrix("L", ROWS, COLS, lhsMap));

		IndexingFEDInstruction.parseInstruction(scalarInstruction()).processInstruction(ec);

		assertEquals(1, lhsMap.executeCalls);
		assertEquals(0, lhsMap.requestCount(FederatedRequest.RequestType.GET_VAR));
		assertRemoteFullOutput(ec.getMatrixObject("O"), ROWS, COLS, PORT);
	}

	@Test
	public void scalarUpdateRejectsMultiEntryFullBeforeRpc() {
		RecordingFederationMap lhsMap = multiEntryFullMap(71, ROWS, COLS);
		ExecutionContext ec = scalarContext(remoteMatrix("L", ROWS, COLS, lhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(scalarInstruction()).processInstruction(ec));
		assertEquals(0, lhsMap.executeCalls);
	}

	@Test
	public void rhsShapeMismatchFailsBeforeRpc() {
		RecordingFederationMap rhsMap = singleFullMap(81, ROWS - 1, 1, PORT);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS - 1, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	@Test
	public void outOfBoundsUpdateFailsBeforeRpc() {
		RecordingFederationMap rhsMap = singleFullMap(81, ROWS, 1, PORT);
		ExecutionContext ec = context(localMatrix("L", ROWS, COLS),
			remoteMatrix("R", ROWS, 1, rhsMap));

		assertThrows(DMLRuntimeException.class,
			() -> IndexingFEDInstruction.parseInstruction(instruction(1, ROWS + 1, 1, 1)).processInstruction(ec));
		assertEquals(0, rhsMap.executeCalls);
		assertEquals(0, rhsMap.broadcastCalls);
	}

	private static ExecutionContext context(MatrixObject lhs, MatrixObject rhs) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", lhs);
		ec.setVariable("R", rhs);
		ec.setVariable("O", emptyMatrix("O", ROWS, COLS));
		return ec;
	}

	private static ExecutionContext scalarContext(MatrixObject lhs) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", lhs);
		ec.setVariable("O", emptyMatrix("O", ROWS, COLS));
		return ec;
	}

	private static MatrixObject localMatrix(String name, int rows, int cols) {
		MatrixObject matrix = emptyMatrix(name, rows, cols);
		matrix.acquireModify(new MatrixBlock(rows, cols, 1.0));
		matrix.release();
		return matrix;
	}

	private static MatrixObject remoteMatrix(String name, int rows, int cols, FederationMap map) {
		MatrixObject matrix = emptyMatrix(name, rows, cols);
		matrix.setFedMapping(map);
		return matrix;
	}

	private static MatrixObject emptyMatrix(String name, long rows, long cols) {
		return new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(rows, cols, 1024, -1)));
	}

	private static RecordingFederationMap singleFullMap(long id, long rows, long cols, int port) {
		return new RecordingFederationMap(id, List.of(Pair.of(
			originRange(rows, cols), data(id, port))), FType.FULL);
	}

	private static FederatedRange originRange(long rows, long cols) {
		return new FederatedRange(new long[] {0, 0}, new long[] {rows, cols});
	}

	private static MatrixBlock column(double... values) {
		double[][] data = new double[values.length][1];
		for(int row = 0; row < values.length; row++)
			data[row][0] = values[row];
		return matrix(data);
	}

	private static MatrixBlock matrix(double[][] values) {
		MatrixBlock block = new MatrixBlock(values.length, values[0].length, false);
		block.allocateDenseBlock();
		for(int row = 0; row < values.length; row++)
			for(int col = 0; col < values[row].length; col++)
				block.set(row, col, values[row][col]);
		return block;
	}

	private static RecordingFederationMap multiEntryFullMap(long id, long rows, long cols) {
		return new RecordingFederationMap(id, Arrays.asList(
			Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {rows / 2, cols}), data(id, PORT)),
			Pair.of(new FederatedRange(new long[] {rows / 2, 0}, new long[] {rows, cols}), data(id, PORT + 1))),
			FType.FULL);
	}

	private static FederatedData data(long id, int port) {
		return new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", port), "dummy", id);
	}

	private static String instruction(long rowLower, long rowUpper, long colLower, long colUpper) {
		return instruction("leftIndex", rowLower, rowUpper, colLower, colUpper);
	}

	private static String instruction(String opcode, long rowLower, long rowUpper, long colLower, long colUpper) {
		return InstructionUtils.concatOperands(
			"FED", opcode,
			operand("L", DataType.MATRIX, ValueType.FP64, false),
			operand("R", DataType.MATRIX, ValueType.FP64, false),
			operand(Long.toString(rowLower), DataType.SCALAR, ValueType.INT64, true),
			operand(Long.toString(rowUpper), DataType.SCALAR, ValueType.INT64, true),
			operand(Long.toString(colLower), DataType.SCALAR, ValueType.INT64, true),
			operand(Long.toString(colUpper), DataType.SCALAR, ValueType.INT64, true),
			operand("O", DataType.MATRIX, ValueType.FP64, false),
			FederatedOutput.FOUT.name());
	}

	private static String scalarInstruction() {
		return InstructionUtils.concatOperands(
			"FED", "leftIndex",
			operand("L", DataType.MATRIX, ValueType.FP64, false),
			operand("7", DataType.SCALAR, ValueType.FP64, true),
			operand("1", DataType.SCALAR, ValueType.INT64, true),
			operand("1", DataType.SCALAR, ValueType.INT64, true),
			operand("1", DataType.SCALAR, ValueType.INT64, true),
			operand("1", DataType.SCALAR, ValueType.INT64, true),
			operand("O", DataType.MATRIX, ValueType.FP64, false),
			FederatedOutput.FOUT.name());
	}

	private static String operand(String name, DataType dataType, ValueType valueType, boolean literal) {
		return InstructionUtils.concatOperandParts(name, dataType.name(), valueType.name(), Boolean.toString(literal));
	}

	private static void assertRemoteFullOutput(MatrixObject out, long rows, long cols, int port) {
		assertTrue(out.isFederated());
		assertEquals(FType.FULL, out.getFedMapping().getType());
		assertEquals(1, out.getFedMapping().getSize());
		FederatedRange range = out.getFedMapping().getFederatedRanges()[0];
		assertEquals(0, range.getBeginDims()[0]);
		assertEquals(0, range.getBeginDims()[1]);
		assertEquals(rows, range.getEndDims()[0]);
		assertEquals(cols, range.getEndDims()[1]);
		assertEquals(port, out.getFedMapping().getMap().get(0).getRight().getAddress().getPort());
		assertFalse(out.isFederated(FType.BROADCAST));
	}
}
