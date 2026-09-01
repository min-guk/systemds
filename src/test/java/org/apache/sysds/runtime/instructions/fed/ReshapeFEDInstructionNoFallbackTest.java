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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.Transform;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class ReshapeFEDInstructionNoFallbackTest {
	private static class SuccessfulFederationMap extends FederationMap {
		private final boolean failWorkerInstruction;
		private String workerInstruction;

		SuccessfulFederationMap(long id, List<Pair<FederatedRange, FederatedData>> map, FType type) {
			this(id, map, type, false);
		}

		SuccessfulFederationMap(long id, List<Pair<FederatedRange, FederatedData>> map, FType type,
			boolean failWorkerInstruction) {
			super(id, map, type);
			this.failWorkerInstruction = failWorkerInstruction;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			return new Future[] {CompletableFuture.completedFuture(
				new FederatedResponse(FederatedResponse.ResponseType.SUCCESS_EMPTY))};
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			for(FederatedRequest request : slices)
				if(request.getType() == FederatedRequest.RequestType.EXEC_INST)
					workerInstruction = (String) request.getParam(0);
			if(failWorkerInstruction)
				return new Future[] {CompletableFuture.completedFuture(new FederatedResponse(
					FederatedResponse.ResponseType.ERROR, new DMLRuntimeException("remote reshape failure")))};
			return execute(tid, wait, requests);
		}
	}

	private static class MissingVariableFederationMap extends FederationMap {
		private int executeCalls;

		MissingVariableFederationMap(long id, List<Pair<FederatedRange, FederatedData>> map, FType type) {
			super(id, map, type);
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			executeCalls++;
			throw new DMLRuntimeException("Unknown variable on federated worker");
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			executeCalls++;
			throw new DMLRuntimeException("Unknown variable on federated worker");
		}
	}

	@Test
	public void testSharedFederatedParserDispatchesReshape() {
		FEDInstruction instruction = FEDInstructionParser.parseSingleInstruction(createInstruction());
		assertTrue(instruction instanceof ReshapeFEDInstruction);
		assertEquals(FederatedOutput.FOUT, instruction.getFederatedOutput());
	}

	@Test
	public void testFederatedTransformEncodesReshapeOutputContract() {
		Data input = new Data(OpOpData.TRANSIENTREAD, null, null, "X", null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		Lop[] inputs = new Lop[] {input,
			Data.createLiteralLop(ValueType.INT64, "3"),
			Data.createLiteralLop(ValueType.INT64, "2"),
			Data.createLiteralLop(ValueType.INT64, "0"),
			Data.createLiteralLop(ValueType.BOOLEAN, "true")};
		Transform reshape = new Transform(inputs, ReOrgOp.RESHAPE,
			DataType.MATRIX, ValueType.FP64, ExecType.FED, 1);
		reshape.setFederatedOutput(FederatedOutput.FOUT);

		String instruction = reshape.getInstructions("X", "rows", "cols", "dims", "byrow", "Y");
		assertTrue(instruction.endsWith(Lop.OPERAND_DELIMITOR + FederatedOutput.FOUT.name()));
		assertEquals(FederatedOutput.FOUT,
			FEDInstructionParser.parseSingleInstruction(instruction).getFederatedOutput());
	}

	@Test
	public void testLocalInputDoesNotExecuteCpReshapeInsideFedInstruction() {
		ExecutionContext ec = createExecutionContext(false);
		String instruction = createInstruction();

		assertThrows("A FED reshape with a local input must fail instead of executing CP locally",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(instruction).processInstruction(ec));
	}

	@Test
	public void testMissingRemoteVariableDoesNotFallBackToLocalReshape() {
		ExecutionContext ec = createExecutionContext(true);
		String instruction = createInstruction();

		assertThrows("A FED reshape with missing remote state must fail instead of executing CP locally",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(instruction).processInstruction(ec));
	}

	@Test
	public void testMissingDimensionDoesNotDefaultAndReachWorkers() {
		MissingVariableFederationMap map = missingVariableMap();
		ExecutionContext ec = createExecutionContext(map);
		ec.removeVariable("rows");

		assertThrows("A missing reshape dimension must fail before federated execution",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec));
		assertEquals("Missing dimensions must not be guessed from input metadata", 0, map.executeCalls);
	}

	@Test
	public void testMissingByRowDoesNotDefaultAndReachWorkers() {
		MissingVariableFederationMap map = missingVariableMap();
		ExecutionContext ec = createExecutionContext(map);
		ec.removeVariable("byrow");

		assertThrows("A missing reshape orientation must fail before federated execution",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec));
		assertEquals("Missing byrow must not silently default to true", 0, map.executeCalls);
	}

	@Test
	public void testByRowReshapeSetsRowOutputContract() {
		SuccessfulFederationMap map = successfulFederationMap(false);
		ExecutionContext ec = createExecutionContext(map);

		ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec);

		assertEquals(FType.ROW, ec.getMatrixObject("Y").getFedMapping().getType());
		assertTrue(map.workerInstruction.startsWith(ExecType.CP.name() + Lop.OPERAND_DELIMITOR + "rshape"));
	}

	@Test
	public void testByColumnReshapeSetsColumnOutputContract() {
		ExecutionContext ec = createExecutionContext(successfulFederationMap(false));
		ec.setVariable("byrow", new BooleanObject(false));

		ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec);

		assertEquals(FType.COL, ec.getMatrixObject("Y").getFedMapping().getType());
	}

	@Test
	public void testWorkerFailureIsObservedWhenInputNnzIsKnown() {
		ExecutionContext ec = createExecutionContext(successfulFederationMap(true));

		assertThrows("The coordinator must observe asynchronous worker failures even with known input nnz",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec));
	}

	private static ExecutionContext createExecutionContext(boolean federated) {
		return createExecutionContext(federated ? missingVariableMap() : null);
	}

	private static MissingVariableFederationMap missingVariableMap() {
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {2, 3});
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", 19999), "dummy");
		return new MissingVariableFederationMap(71, List.of(Pair.of(range, data)), FType.FULL);
	}

	private static SuccessfulFederationMap successfulFederationMap(boolean failWorkerInstruction) {
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {2, 3});
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", 19999), "dummy");
		return new SuccessfulFederationMap(71, List.of(Pair.of(range, data)), FType.FULL,
			failWorkerInstruction);
	}

	private static ExecutionContext createExecutionContext(FederationMap map) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(2, 3, 1024, 6)));
		input.acquireModify(new MatrixBlock(2, 3, 1.0));
		input.release();
		if(map != null)
			input.setFedMapping(map);

		ec.setVariable("X", input);
		ec.setVariable("rows", new IntObject(3));
		ec.setVariable("cols", new IntObject(2));
		ec.setVariable("dims", new IntObject(0));
		ec.setVariable("byrow", new BooleanObject(true));
		ec.setVariable("Y", new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(3, 2, 1024))));
		return ec;
	}

	private static String createInstruction() {
		return InstructionUtils.concatOperands(
			"FED", "rshape",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("rows", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("cols", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("dims", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("byrow", DataType.SCALAR.name(), ValueType.BOOLEAN.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()));
	}
}
