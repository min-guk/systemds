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

package org.apache.sysds.runtime.instructions.fed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.junit.Test;

/** Locks the worker batches and transferred values used by aggregate auxiliary stages. */
public class AggregateAuxiliaryBatchContractTest {
	@Test
	public void varianceFetchesMeanAndVarianceInSeparateBatches() {
		ExecutionContext ec = context();
		RecordingMap map = recordingMap(ResponseShape.SCALAR, 2);
		attach(ec, "X", 8, 2, map);

		AggregateUnaryFEDInstruction.parseInstruction(InstructionUtils.concatOperands(
			"FED", "uavar", operand("X"), scalar("V"), "1", FederatedOutput.LOUT.name()))
			.processInstruction(ec);

		assertEquals(2, map.batches.size());
		assertTypes(map.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR);
		assertTypes(map.batches.get(1), RequestType.EXEC_INST, RequestType.GET_VAR);
		assertTrue(instruction(map.batches.get(0)).contains("uamean"));
		assertTrue(instruction(map.batches.get(1)).contains("uavar"));
		assertEquals(List.of("scalar", "scalar"), map.responsePayloads);
	}

	@Test
	public void alignedCovarianceUsesCovarianceAndTwoMeanBatches() {
		ExecutionContext ec = context();
		RecordingMap map = recordingMap(ResponseShape.SCALAR, 2);
		attach(ec, "X1", 8, 1, map);
		attach(ec, "X2", 8, 1, map);

		CovarianceFEDInstruction.parseInstruction(InstructionUtils.concatOperands(
			"FED", "cov", operand("X1"), operand("X2"), scalar("C"), "1", FederatedOutput.LOUT.name()))
			.processInstruction(ec);

		assertEquals(3, map.batches.size());
		for(List<FederatedRequest> batch : map.batches)
			assertTypes(batch, RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTrue(instruction(map.batches.get(0)).contains("cov"));
		assertTrue(instruction(map.batches.get(1)).contains("uamean"));
		assertTrue(instruction(map.batches.get(2)).contains("uamean"));
		assertEquals(List.of("scalar", "scalar", "scalar"), map.responsePayloads);
	}

	@Test
	public void alignedWeightedCovarianceAddsWeightsSumBatch() {
		ExecutionContext ec = context();
		RecordingMap map = recordingMap(ResponseShape.SCALAR, 2);
		attach(ec, "X1", 8, 1, map);
		attach(ec, "X2", 8, 1, map);
		attach(ec, "X3", 8, 1, map);

		CovarianceFEDInstruction.parseInstruction(InstructionUtils.concatOperands(
			"FED", "cov", operand("X1"), operand("X2"), operand("X3"), scalar("X4"), "1",
			FederatedOutput.LOUT.name())).processInstruction(ec);

		assertEquals(4, map.batches.size());
		assertTypes(map.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTypes(map.batches.get(1), RequestType.EXEC_INST, RequestType.EXEC_INST, RequestType.EXEC_INST,
			RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTypes(map.batches.get(2), RequestType.EXEC_INST, RequestType.EXEC_INST, RequestType.EXEC_INST,
			RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertTypes(map.batches.get(3), RequestType.EXEC_INST, RequestType.GET_VAR, RequestType.EXEC_INST);
		assertEquals(List.of("scalar", "scalar", "scalar", "scalar"), map.responsePayloads);
	}

	@Test
	public void rowCumulativeUsesPartialCorrectionAndFinalBatches() {
		ExecutionContext ec = context();
		RecordingMap map = recordingMap(ResponseShape.ROW_VECTOR, 2);
		attach(ec, "X", 8, 2, map);
		output(ec, "Y", 8, 2);

		UnaryMatrixFEDInstruction.parseInstruction(cumulativeInstruction("ucumk+"))
			.processInstruction(ec);

		assertEquals(3, map.batches.size());
		assertTypes(map.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR);
		assertTypes(map.batches.get(1), RequestType.PUT_VAR, RequestType.EXEC_INST);
		assertTypes(map.batches.get(2), RequestType.EXEC_INST);
		assertEquals(List.of("1x2", "none", "none"), map.responsePayloads);
		assertPutShape(map.batches.get(1), 4, 2);
	}

	@Test
	public void rowCumulativeSumProductUsesTwoIntermediatesAndTwoCorrectionPuts() {
		ExecutionContext ec = context();
		RecordingMap map = recordingMap(ResponseShape.SUM_PRODUCT, 2);
		attach(ec, "X", 8, 2, map);
		output(ec, "Y", 8, 1);

		UnaryMatrixFEDInstruction.parseInstruction(cumulativeInstruction("ucumk+*"))
			.processInstruction(ec);

		assertEquals(4, map.batches.size());
		assertTypes(map.batches.get(0), RequestType.EXEC_INST, RequestType.GET_VAR);
		assertTypes(map.batches.get(1), RequestType.EXEC_INST, RequestType.GET_VAR);
		assertTypes(map.batches.get(2), RequestType.PUT_VAR, RequestType.PUT_VAR, RequestType.EXEC_INST);
		assertTypes(map.batches.get(3), RequestType.EXEC_INST);
		assertEquals(List.of("2x2", "2x2", "none", "none"), map.responsePayloads);
		assertPutShape(map.batches.get(2), 0, 4, 2);
		assertPutShape(map.batches.get(2), 1, 4, 2);
	}

	private static String cumulativeInstruction(String opcode) {
		return InstructionUtils.concatOperands("FED", opcode, operand("X"), operand("Y"), "1", "false",
			FederatedOutput.FOUT.name());
	}

	private static String operand(String name) {
		return InstructionUtils.concatOperandParts(name, DataType.MATRIX.name(), ValueType.FP64.name());
	}

	private static String scalar(String name) {
		return InstructionUtils.concatOperandParts(name, DataType.SCALAR.name(), ValueType.FP64.name());
	}

	private static ExecutionContext context() {
		return new ExecutionContext(new LocalVariableMap());
	}

	private static void attach(ExecutionContext ec, String name, long rows, long cols, FederationMap map) {
		MatrixObject matrix = output(ec, name, rows, cols);
		matrix.setFedMapping(map);
	}

	private static MatrixObject output(ExecutionContext ec, String name, long rows, long cols) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaDataFormat(new MatrixCharacteristics(rows, cols, 1024, -1), FileFormat.BINARY));
		ec.setVariable(name, matrix);
		return matrix;
	}

	private static RecordingMap recordingMap(ResponseShape shape, int workers) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(int i = 0; i < workers; i++) {
			long id = 100 + i;
			entries.add(Pair.of(new FederatedRange(new long[] {i * 4, 0}, new long[] {(i + 1) * 4, 2}),
				new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", (int)(19000 + id)), "dummy", id)));
		}
		return new RecordingMap(99, entries, FType.ROW, shape);
	}

	private static String instruction(List<FederatedRequest> batch) {
		return batch.stream().filter(request -> request.getType() == RequestType.EXEC_INST)
			.map(request -> String.valueOf(request.getParam(0))).findFirst().orElse("");
	}

	private static void assertTypes(List<FederatedRequest> requests, RequestType... expected) {
		assertEquals(Arrays.asList(expected), requests.stream().map(FederatedRequest::getType).toList());
	}

	private static void assertPutShape(List<FederatedRequest> batch, int rows, int cols) {
		int put = 0;
		for(int i = 0; i < batch.size(); i++)
			if(batch.get(i).getType() == RequestType.PUT_VAR) {
				assertPutShape(batch, put, rows, cols);
				put++;
			}
	}

	private static void assertPutShape(List<FederatedRequest> batch, int putIndex, int rows, int cols) {
		FederatedRequest request = batch.stream().filter(item -> item.getType() == RequestType.PUT_VAR)
			.skip(putIndex).findFirst().orElseThrow();
		MatrixBlock block = (MatrixBlock) request.getParam(0);
		assertEquals(rows, block.getNumRows());
		assertEquals(cols, block.getNumColumns());
		assertEquals(512 + block.getExactSerializedSize(), request.estimateSerializationBufferSize());
	}

	private enum ResponseShape { SCALAR, ROW_VECTOR, SUM_PRODUCT }

	private static final class RecordingMap extends FederationMap {
		private final List<List<FederatedRequest>> batches = new ArrayList<>();
		private final List<String> responsePayloads = new ArrayList<>();
		private final ResponseShape responseShape;

		private RecordingMap(long id, List<Pair<FederatedRange, FederatedData>> entries, FType type,
			ResponseShape responseShape) {
			super(id, entries, type);
			this.responseShape = responseShape;
		}

		@Override
		public FederationMap copyWithNewID(long id) {
			return this;
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			return record(requests);
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			return record(combine(slices[0], requests));
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] firstSlices,
			FederatedRequest[] secondSlices, FederatedRequest... requests) {
			return record(combine(firstSlices[0], secondSlices[0], requests));
		}

		@SuppressWarnings("unchecked")
		private Future<FederatedResponse>[] record(FederatedRequest... requests) {
			batches.add(List.copyOf(Arrays.asList(requests)));
			boolean get = Arrays.stream(requests).anyMatch(request -> request.getType() == RequestType.GET_VAR);
			Object response = response(get);
			responsePayloads.add(payload(response, get));
			Future<FederatedResponse>[] futures = new Future[getSize()];
			for(int i = 0; i < futures.length; i++)
				futures[i] = CompletableFuture.completedFuture(new FederatedResponse(
					FederatedResponse.ResponseType.SUCCESS, response));
			return futures;
		}

		private Object response(boolean get) {
			if(!get)
				return Long.valueOf(0);
			switch(responseShape) {
				case SCALAR: return new DoubleObject(1);
				case ROW_VECTOR: return new MatrixBlock(1, 2, 1);
				case SUM_PRODUCT: return new MatrixBlock(2, 2, 1);
				default: throw new IllegalStateException("Unexpected response shape " + responseShape);
			}
		}

		private static String payload(Object response, boolean get) {
			if(!get)
				return "none";
			if(response instanceof MatrixBlock) {
				MatrixBlock block = (MatrixBlock) response;
				return block.getNumRows() + "x" + block.getNumColumns();
			}
			return "scalar";
		}

		private static FederatedRequest[] combine(FederatedRequest slice, FederatedRequest... requests) {
			FederatedRequest[] combined = new FederatedRequest[requests.length + 1];
			combined[0] = slice;
			System.arraycopy(requests, 0, combined, 1, requests.length);
			return combined;
		}

		private static FederatedRequest[] combine(FederatedRequest first, FederatedRequest second,
			FederatedRequest... requests) {
			FederatedRequest[] combined = new FederatedRequest[requests.length + 2];
			combined[0] = first;
			combined[1] = second;
			System.arraycopy(requests, 0, combined, 2, requests.length);
			return combined;
		}
	}
}
