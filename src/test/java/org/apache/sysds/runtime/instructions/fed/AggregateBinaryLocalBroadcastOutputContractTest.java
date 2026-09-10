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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
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

public class AggregateBinaryLocalBroadcastOutputContractTest {
	private static class ResultFederationMap extends FederationMap {
		private int _retrievalCalls;

		ResultFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap) {
			super(id, fedMap, FType.BROADCAST);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			boolean retrieval = false;
			for(FederatedRequest request : requests)
				retrieval |= request.getType() == FederatedRequest.RequestType.GET_VAR;

			Future<FederatedResponse>[] responses = new Future[getSize()];
			if(retrieval) {
				_retrievalCalls++;
				responses[0] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, new MatrixBlock(1, 3, 4.0)));
				responses[1] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, new MatrixBlock(1, 3, 90.0)));
			}
			else {
				responses[0] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(3)));
				responses[1] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(29)));
			}
			return responses;
		}
	}

	@Test
	public void forcedLocalUsesOneReplicatedWorkerResultWithoutDoubleCounting() {
		ExecutionContext ec = executionContext();

		AggregateBinaryFEDInstruction.parseInstruction(instruction(FederatedOutput.LOUT))
			.processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("O");
		assertNull("FED/LOUT must materialize the result locally", output.getFedMapping());
		MatrixBlock result = output.acquireRead();
		try {
			assertEquals(1, result.getNumRows());
			assertEquals(3, result.getNumColumns());
			assertEquals(4.0, result.get(0, 0), 0.0);
			assertEquals(4.0, result.get(0, 2), 0.0);
		}
		finally {
			output.release();
		}
		assertEquals("Replicated results must be retrieved once and selected, not added",
			1, inputMap(ec)._retrievalCalls);
	}

	@Test
	public void forcedFederatedKeepsReplicatedOutputWithExactResultRanges() {
		ExecutionContext ec = executionContext();

		AggregateBinaryFEDInstruction.parseInstruction(instruction(FederatedOutput.FOUT))
			.processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("O");
		assertNotNull("FED/FOUT must keep the complete replicated results federated", output.getFedMapping());
		assertEquals(FType.BROADCAST, output.getFedMapping().getType());
		assertEquals(2, output.getFedMapping().getSize());
		assertEquals(1, output.getNumRows());
		assertEquals(3, output.getNumColumns());
		assertEquals("Replicated worker nnz metadata must not be double counted", 3,
			output.getDataCharacteristics().getNonZeros());
		for(FederatedRange range : output.getFedMapping().getFederatedRanges()) {
			assertEquals(0, range.getBeginDims()[0]);
			assertEquals(0, range.getBeginDims()[1]);
			assertEquals(1, range.getEndDims()[0]);
			assertEquals(3, range.getEndDims()[1]);
		}
		assertEquals("FED/FOUT must not retrieve the remote result", 0, inputMap(ec)._retrievalCalls);
	}

	private static ExecutionContext executionContext() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("L", localMatrix("L", 1, 2));

		MatrixObject right = new MatrixObject(ValueType.FP64, "R",
			new MetaData(new MatrixCharacteristics(2, 3, 1024, 6)));
		right.setFedMapping(new ResultFederationMap(81, broadcastRanges(2, 3)));
		ec.setVariable("R", right);
		ec.setVariable("O", new MatrixObject(ValueType.FP64, "O",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024))));
		return ec;
	}

	private static MatrixObject localMatrix(String name, int rows, int cols) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(rows, cols, 1024, (long) rows * cols)));
		matrix.acquireModify(new MatrixBlock(rows, cols, 1.0));
		matrix.release();
		return matrix;
	}

	private static List<Pair<FederatedRange, FederatedData>> broadcastRanges(int rows, int cols) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(int i = 0; i < 2; i++) {
			FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {rows, cols});
			FederatedData data = new FederatedData(DataType.MATRIX,
				new InetSocketAddress("localhost", 19001 + i), "dummy");
			entries.add(Pair.of(range, data));
		}
		return entries;
	}

	private static ResultFederationMap inputMap(ExecutionContext ec) {
		return (ResultFederationMap) ec.getMatrixObject("R").getFedMapping();
	}

	private static String instruction(FederatedOutput output) {
		return InstructionUtils.concatOperands(
			"FED", "ba+*",
			InstructionUtils.concatOperandParts("L", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("R", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("O", DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", output.name());
	}
}
