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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class BinaryMatrixScalarFEDInstructionNoFallbackTest {
	private static class ResultFederationMap extends FederationMap {
		private int operationCalls;
		private int retrievalCalls;
		private int cleanupRequests;

		ResultFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap) {
			super(id, fedMap, FType.ROW);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			boolean retrieval = false;
			for(FederatedRequest request : requests) {
				retrieval |= request.getType() == FederatedRequest.RequestType.GET_VAR;
				if(request.getType() == FederatedRequest.RequestType.EXEC_INST && request.getID() == -1)
					cleanupRequests++;
			}
			Future<FederatedResponse>[] responses = new Future[getSize()];
			if(retrieval) {
				retrievalCalls++;
				for(int i = 0; i < responses.length; i++)
					responses[i] = CompletableFuture.completedFuture(new FederatedResponse(ResponseType.SUCCESS,
						new MatrixBlock(2, 3, i + 2.0)));
			}
			else {
				operationCalls++;
				for(int i = 0; i < responses.length; i++)
					responses[i] = CompletableFuture.completedFuture(
						new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(6)));
			}
			return responses;
		}
	}

	@Test
	public void testLocalMatrixDoesNotExecuteCpBinaryInsideFedInstruction() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(2, 3, 1024, 6)));
		input.acquireModify(new MatrixBlock(2, 3, 1.0));
		input.release();
		ec.setVariable("X", input);
		ec.setVariable("Y", new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(2, 3, 1024))));

		String instruction = InstructionUtils.concatOperands(
			"FED", "+",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.createLiteralOperand("1", ValueType.FP64),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			FederatedOutput.LOUT.name());

		assertThrows("A FED matrix-scalar operation with a local matrix must fail instead of executing CP locally",
			DMLRuntimeException.class,
			() -> BinaryFEDInstruction.parseInstruction(instruction).processInstruction(ec));
	}

	@Test
	public void testForcedLocalOutputRetrievesAndRowBindsWorkerResults() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = federatedRowMatrix();
		ResultFederationMap inputMap = (ResultFederationMap) input.getFedMapping();
		ec.setVariable("X", input);
		ec.setVariable("Y", outputMatrix());

		BinaryFEDInstruction.parseInstruction(matrixScalarInstruction(FederatedOutput.LOUT)).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("Y");
		assertNull("FED/LOUT must not leave a federated output mapping", output.getFedMapping());
		assertSame("Forced local output must not mutate the planner-provided input mapping",
			inputMap, input.getFedMapping());
		MatrixBlock result = output.acquireRead();
		try {
			assertEquals(4, result.getNumRows());
			assertEquals(3, result.getNumColumns());
			assertEquals(2.0, result.get(0, 0), 0.0);
			assertEquals(2.0, result.get(1, 2), 0.0);
			assertEquals(3.0, result.get(2, 0), 0.0);
			assertEquals(3.0, result.get(3, 2), 0.0);
		}
		finally {
			output.release();
		}
		assertEquals(1, inputMap.operationCalls);
		assertEquals("FED/LOUT must retrieve the worker outputs exactly once", 1, inputMap.retrievalCalls);
		assertEquals("Retrieved worker outputs must be removed after materialization", 1, inputMap.cleanupRequests);
	}

	@Test
	public void testForcedFederatedOutputPreservesRowMappingWithoutRetrieval() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = federatedRowMatrix();
		ResultFederationMap inputMap = (ResultFederationMap) input.getFedMapping();
		ec.setVariable("X", input);
		ec.setVariable("Y", outputMatrix());

		BinaryFEDInstruction.parseInstruction(matrixScalarInstruction(FederatedOutput.FOUT)).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("Y");
		assertNotNull("FED/FOUT must retain a federated output mapping", output.getFedMapping());
		assertEquals(FType.ROW, output.getFedMapping().getType());
		assertEquals(1, inputMap.operationCalls);
		assertEquals("FED/FOUT must not retrieve the worker outputs", 0, inputMap.retrievalCalls);
		assertEquals(0, inputMap.cleanupRequests);
	}

	private static MatrixObject federatedRowMatrix() {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(4, 3, 1024, -1)));
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {2, 3}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 18001), "dummy")));
		entries.add(Pair.of(new FederatedRange(new long[] {2, 0}, new long[] {4, 3}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 18002), "dummy")));
		matrix.setFedMapping(new ResultFederationMap(71, entries));
		return matrix;
	}

	private static MatrixObject outputMatrix() {
		return new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(4, 3, 1024)));
	}

	private static String matrixScalarInstruction(FederatedOutput output) {
		return InstructionUtils.concatOperands(
			"FED", "+",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.createLiteralOperand("1", ValueType.FP64),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			output.name());
	}
}
