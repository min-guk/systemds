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
import org.apache.sysds.lops.WeightedDivMM.WDivMMType;
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

public class QuaternaryWDivMMFEDInstructionOutputContractTest {
	private static class ResultFederationMap extends FederationMap {
		private final int _resultRows;
		private final int _resultCols;
		private int _retrievalCalls;
		private int _cleanupRequests;

		ResultFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap, FType type,
			int resultRows, int resultCols) {
			super(id, fedMap, type);
			_resultRows = resultRows;
			_resultCols = resultCols;
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			return responses(requests);
		}

		@Override
		public Future<FederatedResponse>[] executeMultipleSlices(long tid, boolean wait,
			FederatedRequest[][] slicedRequests, FederatedRequest[] requests) {
			return responses(requests);
		}

		@SuppressWarnings("unchecked")
		private Future<FederatedResponse>[] responses(FederatedRequest[] requests) {
			boolean retrieval = false;
			for(FederatedRequest request : requests) {
				retrieval |= request.getType() == FederatedRequest.RequestType.GET_VAR;
				if(request.getType() == FederatedRequest.RequestType.EXEC_INST && request.getID() == -1)
					_cleanupRequests++;
			}
			Future<FederatedResponse>[] responses = new Future[getSize()];
			if(retrieval) {
				_retrievalCalls++;
				for(int i = 0; i < responses.length; i++)
					responses[i] = CompletableFuture.completedFuture(new FederatedResponse(ResponseType.SUCCESS,
						new MatrixBlock(_resultRows, _resultCols, i + 2.0)));
			}
			else {
				for(int i = 0; i < responses.length; i++)
					responses[i] = CompletableFuture.completedFuture(
						new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(1)));
			}
			return responses;
		}
	}

	@Test
	public void forcedLocalRightRowRetrievesAndRowBindsWorkerResults() {
		ExecutionContext ec = executionContext(FType.ROW, 4, 3, 2, 2);

		QuaternaryFEDInstruction.parseInstruction(
			instruction(WDivMMType.MULT_RIGHT, FederatedOutput.LOUT)).processInstruction(ec);

		assertLocalResult(ec, 4, 2, 2.0, 3.0);
		ResultFederationMap map = inputMap(ec);
		assertEquals(1, map._retrievalCalls);
		assertEquals(1, map._cleanupRequests);
	}

	@Test
	public void forcedLocalLeftColUsesOutputRowsRatherThanInputColumnsForBinding() {
		ExecutionContext ec = executionContext(FType.COL, 4, 4, 2, 2);

		QuaternaryFEDInstruction.parseInstruction(
			instruction(WDivMMType.MULT_LEFT, FederatedOutput.LOUT)).processInstruction(ec);

		// LEFT transposes the partition semantics: COL-partitioned X produces ROW-partitioned output.
		assertLocalResult(ec, 4, 2, 2.0, 3.0);
		ResultFederationMap map = inputMap(ec);
		assertEquals(1, map._retrievalCalls);
		assertEquals(1, map._cleanupRequests);
	}

	@Test
	public void forcedLocalBasicColPreservesColumnPartitionedOutput() {
		ExecutionContext ec = executionContext(FType.COL, 4, 4, 4, 2);

		QuaternaryFEDInstruction.parseInstruction(
			instruction(WDivMMType.MULT_BASIC, FederatedOutput.LOUT)).processInstruction(ec);

		assertLocalResult(ec, 4, 4, 2.0, 3.0);
		ResultFederationMap map = inputMap(ec);
		assertEquals(1, map._retrievalCalls);
		assertEquals(1, map._cleanupRequests);
	}

	@Test
	public void overlappingRightColPartialResultsAreAggregatedInsteadOfBound() {
		ExecutionContext ec = executionContext(FType.COL, 4, 4, 4, 2);

		QuaternaryFEDInstruction.parseInstruction(
			instruction(WDivMMType.MULT_RIGHT, FederatedOutput.LOUT)).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("Y");
		assertNull(output.getFedMapping());
		MatrixBlock result = output.acquireRead();
		try {
			assertEquals(4, result.getNumRows());
			assertEquals(2, result.getNumColumns());
			assertEquals(5.0, result.get(0, 0), 0.0);
			assertEquals(5.0, result.get(3, 1), 0.0);
		}
		finally {
			output.release();
		}
	}

	@Test
	public void forcedFederatedRightRowKeepsFederatedMappingWithoutRetrieval() {
		ExecutionContext ec = executionContext(FType.ROW, 4, 3, 2, 2);

		QuaternaryFEDInstruction.parseInstruction(
			instruction(WDivMMType.MULT_RIGHT, FederatedOutput.FOUT)).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("Y");
		assertNotNull(output.getFedMapping());
		assertEquals(FType.ROW, output.getFedMapping().getType());
		ResultFederationMap map = inputMap(ec);
		assertEquals(0, map._retrievalCalls);
		assertEquals(0, map._cleanupRequests);
	}

	@Test
	public void forcedFederatedRightFullUsesTheSingleRangeNativePath() {
		ExecutionContext ec = executionContext(FType.FULL, 4, 3, 4, 2);

		QuaternaryFEDInstruction.parseInstruction(
			instruction(WDivMMType.MULT_RIGHT, FederatedOutput.FOUT)).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("Y");
		assertNotNull(output.getFedMapping());
		assertEquals(FType.FULL, output.getFedMapping().getType());
		ResultFederationMap map = inputMap(ec);
		assertEquals(0, map._retrievalCalls);
		assertEquals(0, map._cleanupRequests);
	}

	private static ExecutionContext executionContext(FType type, int rows, int cols,
		int workerResultRows, int workerResultCols) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject x = new MatrixObject(ValueType.FP64, "W",
			new MetaData(new MatrixCharacteristics(rows, cols, 1024, -1)));
		x.setFedMapping(new ResultFederationMap(71, ranges(type, rows, cols), type,
			workerResultRows, workerResultCols));
		ec.setVariable("W", x);
		ec.setVariable("U", localMatrix("U", rows, 2));
		ec.setVariable("V", localMatrix("V", cols, 2));
		ec.setVariable("Y", new MatrixObject(ValueType.FP64, "Y",
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

	private static List<Pair<FederatedRange, FederatedData>> ranges(FType type, int rows, int cols) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		if(type == FType.ROW) {
			int split = rows / 2;
			entries.add(entry(0, 0, split, cols, 18001));
			entries.add(entry(split, 0, rows, cols, 18002));
		}
		else if(type == FType.COL) {
			int split = cols / 2;
			entries.add(entry(0, 0, rows, split, 18001));
			entries.add(entry(0, split, rows, cols, 18002));
		}
		else if(type == FType.FULL)
			entries.add(entry(0, 0, rows, cols, 18001));
		else
			throw new IllegalArgumentException("Unsupported test layout: " + type);
		return entries;
	}

	private static Pair<FederatedRange, FederatedData> entry(long br, long bc, long er, long ec, int port) {
		return Pair.of(new FederatedRange(new long[] {br, bc}, new long[] {er, ec}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", port), "dummy"));
	}

	private static ResultFederationMap inputMap(ExecutionContext ec) {
		return (ResultFederationMap) ec.getMatrixObject("W").getFedMapping();
	}

	private static void assertLocalResult(ExecutionContext ec, int rows, int cols,
		double firstWorkerValue, double secondWorkerValue) {
		MatrixObject output = ec.getMatrixObject("Y");
		assertNull("FED/LOUT must not leave a federated output mapping", output.getFedMapping());
		MatrixBlock result = output.acquireRead();
		try {
			assertEquals(rows, result.getNumRows());
			assertEquals(cols, result.getNumColumns());
			assertEquals(firstWorkerValue, result.get(0, 0), 0.0);
			assertEquals(secondWorkerValue, result.get(rows - 1, cols - 1), 0.0);
		}
		finally {
			output.release();
		}
	}

	private static String instruction(WDivMMType type, FederatedOutput output) {
		return InstructionUtils.concatOperands(
			"FED", "wdivmm",
			InstructionUtils.concatOperandParts("W", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("U", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("V", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.createLiteralOperand("-1", ValueType.INT64),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			type.name(), "8", output.name());
	}
}
