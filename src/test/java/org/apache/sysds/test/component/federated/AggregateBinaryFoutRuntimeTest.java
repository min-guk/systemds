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
import static org.junit.Assert.assertThrows;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.AggregateBinaryFEDInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class AggregateBinaryFoutRuntimeTest {
	private static class NoOpFederationMap extends FederationMap {
		NoOpFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap, FType type) {
			super(id, fedMap, type);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			boolean retrievesOutput = false;
			for (FederatedRequest request : requests)
				retrievesOutput |= request != null && request.getType() == RequestType.GET_VAR;
			Object result = retrievesOutput ? new MatrixBlock(2, 4, false) : Long.valueOf(0);
			Future<FederatedResponse>[] responses = new Future[getSize()];
			for (int i = 0; i < responses.length; i++)
				responses[i] = CompletableFuture.completedFuture(new FederatedResponse(ResponseType.SUCCESS, result));
			return responses;
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			return execute(tid, wait, requests);
		}
	}

	@Test
	public void testSingleWorkerCoLocatedFoutMatMultStaysFederated() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", federatedMatrix("L", 2, 3, 11, FType.BROADCAST, 1));
		ec.setVariable("R", federatedMatrix("R", 3, 4, 12, FType.FULL, 1));
		ec.setVariable("O", new MatrixObject(ValueType.FP64, "O",
			new MetaData(new MatrixCharacteristics(2, 4, 1024))));

		String inst = InstructionUtils.concatOperands(
			"FED", "ba+*",
			InstructionUtils.concatOperandParts("L", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("R", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("O", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", "FOUT");
		AggregateBinaryFEDInstruction.parseInstruction(inst).processInstruction(ec);

		MatrixObject out = ec.getMatrixObject("O");
		assertTrue("A forced FOUT matrix multiply over co-located single-worker federated inputs must not download",
			out.isFederated());
		assertEquals(FType.BROADCAST, out.getFedMapping().getType());
		assertEquals(2, out.getNumRows());
		assertEquals(4, out.getNumColumns());
	}

	@Test
	public void testReplicatedMultiWorkerFoutMatMultStaysFederated() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", federatedMatrix("L", 2, 3, 21, FType.BROADCAST, 2));
		ec.setVariable("R", federatedMatrix("R", 3, 4, 22, FType.BROADCAST, 2));
		ec.setVariable("O", new MatrixObject(ValueType.FP64, "O",
			new MetaData(new MatrixCharacteristics(2, 4, 1024))));

		String inst = InstructionUtils.concatOperands(
			"FED", "ba+*",
			InstructionUtils.concatOperandParts("L", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("R", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("O", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", "FOUT");
		AggregateBinaryFEDInstruction.parseInstruction(inst).processInstruction(ec);

		MatrixObject out = ec.getMatrixObject("O");
		assertTrue("Replicated inputs produce complete results on every co-located worker and must retain FOUT",
			out.isFederated());
		assertEquals(FType.BROADCAST, out.getFedMapping().getType());
		assertEquals(2, out.getFedMapping().getSize());
		for (FederatedRange range : out.getFedMapping().getFederatedRanges()) {
			assertEquals(0, range.getBeginDims()[0]);
			assertEquals(0, range.getBeginDims()[1]);
			assertEquals(2, range.getEndDims()[0]);
			assertEquals(4, range.getEndDims()[1]);
		}
	}

	@Test
	public void testThreeReplicatedWorkersUseTheSameCompleteOutputRule() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", federatedMatrix("L", 2, 3, 31, FType.BROADCAST, 3));
		ec.setVariable("R", federatedMatrix("R", 3, 4, 32, FType.BROADCAST, 3));
		ec.setVariable("O", new MatrixObject(ValueType.FP64, "O",
			new MetaData(new MatrixCharacteristics(2, 4, 1024))));

		runForcedFoutMatMult(ec);

		MatrixObject out = ec.getMatrixObject("O");
		assertTrue(out.isFederated());
		assertEquals(FType.BROADCAST, out.getFedMapping().getType());
		assertEquals(3, out.getFedMapping().getSize());
	}

	@Test
	public void testIncompleteAlignedInputsStillAggregateLocally() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", federatedMatrix("L", 2, 3, 41, FType.COL, 1,
			new long[] {0, 0}, new long[] {2, 2}));
		ec.setVariable("R", federatedMatrix("R", 3, 4, 42, FType.ROW, 1,
			new long[] {0, 0}, new long[] {2, 4}));
		ec.setVariable("O", new MatrixObject(ValueType.FP64, "O",
			new MetaData(new MatrixCharacteristics(2, 4, 1024))));

		runForcedFoutMatMult(ec);

		assertTrue("Partial products must still be downloaded and aggregated locally",
			!ec.getMatrixObject("O").isFederated());
	}

	@Test
	public void testCompleteInputsOnDifferentWorkersCannotRetainFout() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", federatedMatrix("L", 2, 3, 51, FType.FULL, 1, 15000));
		ec.setVariable("R", federatedMatrix("R", 3, 4, 52, FType.BROADCAST, 1, 16000));
		ec.setVariable("O", new MatrixObject(ValueType.FP64, "O",
			new MetaData(new MatrixCharacteristics(2, 4, 1024))));

		assertThrows("Complete ranges are not sufficient when the operands are on different workers",
			RuntimeException.class, () -> runForcedFoutMatMult(ec));
	}

	private static void runForcedFoutMatMult(ExecutionContext ec) {
		String inst = InstructionUtils.concatOperands(
			"FED", "ba+*",
			InstructionUtils.concatOperandParts("L", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("R", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("O", Types.DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", "FOUT");
		AggregateBinaryFEDInstruction.parseInstruction(inst).processInstruction(ec);
	}

	private static MatrixObject federatedMatrix(String name, long rows, long cols, long id, FType type,
		int workers) {
		return federatedMatrix(name, rows, cols, id, type, workers, 14000);
	}

	private static MatrixObject federatedMatrix(String name, long rows, long cols, long id, FType type,
		int workers, int basePort) {
		return federatedMatrix(name, rows, cols, id, type, workers,
			new long[] {0, 0}, new long[] {rows, cols}, basePort);
	}

	private static MatrixObject federatedMatrix(String name, long rows, long cols, long id, FType type,
		int workers, long[] begin, long[] end) {
		return federatedMatrix(name, rows, cols, id, type, workers, begin, end, 14000);
	}

	private static MatrixObject federatedMatrix(String name, long rows, long cols, long id, FType type,
		int workers, long[] begin, long[] end, int basePort) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(rows, cols, 1024, -1)));
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for (int i = 0; i < workers; i++) {
			FederatedRange range = new FederatedRange(begin.clone(), end.clone());
			FederatedData data = new FederatedData(Types.DataType.MATRIX,
				new InetSocketAddress("localhost", basePort + i), "dummy");
			entries.add(Pair.of(range, data));
		}
		matrix.setFedMapping(new NoOpFederationMap(id, entries, type));
		return matrix;
	}
}
