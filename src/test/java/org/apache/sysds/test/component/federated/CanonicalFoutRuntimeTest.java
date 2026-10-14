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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.FederatedFoutMaterialize;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.fed.FEDFoutInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Test;

public class CanonicalFoutRuntimeTest {
	@Test
	public void lopFactoryEmitsExplicitPoolLiteralAndRepTarget() {
		Data input = new Data(OpOpData.TRANSIENTREAD, null, null, "X", null,
			DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		input.setExecType(ExecType.CP);
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(
			List.of(new InetSocketAddress("worker-a", 1234)));
		FederatedFoutMaterialize materialize = FederatedFoutMaterialize.forWorkerPool(
			input, pool.encode(), "REP");
		String instruction = materialize.getInstructions("X", "Y");
		assertEquals(true, materialize.usesCanonicalWorkerPool());
		assertEquals(FType.BROADCAST,
			FEDFoutInstruction.parseInstruction(instruction).getMaterializationFType());
	}

	@Test
	public void actualWorkersReceiveCanonicalMatrixAndFrameUploads() throws Exception {
		int port1 = AutomatedTestBase.getRandomAvailablePort();
		int port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
			InetSocketAddress address1 = new InetSocketAddress("localhost", port1);
			InetSocketAddress address2 = new InetSocketAddress("localhost", port2);
			CanonicalWorkerPool oneWorker = CanonicalWorkerPool.of(List.of(address1));
			CanonicalWorkerPool twoWorkers = CanonicalWorkerPool.of(List.of(address1, address2));

			MatrixBlock matrix = matrix();
			ExecutionContext oneMatrix = matrixContext(matrix);
			runFout(oneMatrix, oneWorker, DataType.MATRIX, ValueType.FP64, "ROW");
			FederationMap oneMatrixMap = oneMatrix.getMatrixObject("Y").getFedMapping();
			assertEquals(FType.FULL, oneMatrixMap.getType());
			assertRange(oneMatrixMap, 0, new long[] {0, 0}, new long[] {3, 2});
			assertMatrix(matrix, getMatrix(address1, oneMatrixMap.getID()));

			ExecutionContext twoMatrix = matrixContext(matrix);
			runFout(twoMatrix, twoWorkers, DataType.MATRIX, ValueType.FP64, "ROW");
			FederationMap twoMatrixMap = twoMatrix.getMatrixObject("Y").getFedMapping();
			assertEquals(FType.ROW, twoMatrixMap.getType());
			assertRange(twoMatrixMap, 0, new long[] {0, 0}, new long[] {2, 2});
			assertRange(twoMatrixMap, 1, new long[] {2, 0}, new long[] {3, 2});
			assertMatrix(matrix.slice(0, 1, 0, 1), getMatrix(address1, twoMatrixMap.getID()));
			assertMatrix(matrix.slice(2, 2, 0, 1), getMatrix(address2, twoMatrixMap.getID()));

			FrameBlock frame = frame();
			ExecutionContext oneFrame = frameContext(frame);
			runFout(oneFrame, oneWorker, DataType.FRAME, ValueType.STRING, "COL");
			FederationMap oneFrameMap = oneFrame.getFrameObject("Y").getFedMapping();
			assertEquals(FType.FULL, oneFrameMap.getType());
			assertFrame(frame, getFrame(address1, oneFrameMap.getID()));

			ExecutionContext twoFrame = frameContext(frame);
			runFout(twoFrame, twoWorkers, DataType.FRAME, ValueType.STRING, "REP");
			FederationMap twoFrameMap = twoFrame.getFrameObject("Y").getFedMapping();
			assertEquals(FType.BROADCAST, twoFrameMap.getType());
			assertRange(twoFrameMap, 0, new long[] {0, 0}, new long[] {2, 3});
			assertRange(twoFrameMap, 1, new long[] {0, 0}, new long[] {2, 3});
			assertFrame(frame, getFrame(address1, twoFrameMap.getID()));
			assertFrame(frame, getFrame(address2, twoFrameMap.getID()));
		}
		finally {
			FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	@Test
	public void scalarInputIsRejectedBeforeAnyUpload() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("X", new DoubleObject(7));
		ec.setVariable("Y", new DoubleObject(0));
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(
			List.of(new InetSocketAddress("localhost", 6553)));
		String instruction = InstructionUtils.concatOperands("FED", "fed_fout",
			InstructionUtils.concatOperandParts("X", DataType.SCALAR.name(), ValueType.FP64.name()),
			InstructionUtils.createLiteralOperand(pool.encode(), ValueType.STRING),
			InstructionUtils.concatOperandParts("Y", DataType.SCALAR.name(), ValueType.FP64.name()), "REP");
		assertThrows(RuntimeException.class,
			() -> FEDFoutInstruction.parseInstruction(instruction).processInstruction(ec));
	}

	private static ExecutionContext matrixContext(MatrixBlock block) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(block.getNumRows(), block.getNumColumns(), 1024,
				block.getNonZeros())), block);
		MatrixObject output = new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024, -1)));
		ec.setVariable("X", input);
		ec.setVariable("Y", output);
		return ec;
	}

	private static ExecutionContext frameContext(FrameBlock block) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MetaData inputMetadata = new MetaData(new MatrixCharacteristics(
			block.getNumRows(), block.getNumColumns(), 1024, -1));
		FrameObject input = new FrameObject("X", inputMetadata, block.getSchema().clone());
		input.acquireModify(block);
		input.release();
		FrameObject output = new FrameObject("Y",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024, -1)), block.getSchema().clone());
		ec.setVariable("X", input);
		ec.setVariable("Y", output);
		return ec;
	}

	private static void runFout(ExecutionContext ec, CanonicalWorkerPool pool,
		DataType dataType, ValueType valueType, String target) {
		String instruction = InstructionUtils.concatOperands("FED", "fed_fout",
			InstructionUtils.concatOperandParts("X", dataType.name(), valueType.name()),
			InstructionUtils.createLiteralOperand(pool.encode(), ValueType.STRING),
			InstructionUtils.concatOperandParts("Y", dataType.name(), valueType.name()), target);
		FEDFoutInstruction.parseInstruction(instruction).processInstruction(ec);
	}

	private static MatrixBlock matrix() {
		MatrixBlock matrix = new MatrixBlock(3, 2, false);
		for(int r = 0; r < 3; r++)
			for(int c = 0; c < 2; c++)
				matrix.set(r, c, r * 10 + c + 1);
		matrix.recomputeNonZeros();
		return matrix;
	}

	private static FrameBlock frame() {
		FrameBlock frame = new FrameBlock(
			new ValueType[] {ValueType.STRING, ValueType.INT64, ValueType.FP64},
			new String[] {"name", "id", "score"});
		frame.appendRow(new Object[] {"a", 1L, 1.5});
		frame.appendRow(new Object[] {"b", 2L, 2.5});
		return frame;
	}

	private static MatrixBlock getMatrix(InetSocketAddress address, long id) throws Exception {
		return (MatrixBlock) getBlock(address, id);
	}

	private static FrameBlock getFrame(InetSocketAddress address, long id) throws Exception {
		return (FrameBlock) getBlock(address, id);
	}

	private static Object getBlock(InetSocketAddress address, long id) throws Exception {
		FederatedResponse response = FederatedData.executeFederatedOperation(address,
			new FederatedRequest(RequestType.GET_VAR, id)).get(5, TimeUnit.SECONDS);
		return response.getData()[0];
	}

	private static void assertRange(FederationMap map, int index, long[] begin, long[] end) {
		assertArrayEquals(begin, map.getFederatedRanges()[index].getBeginDims());
		assertArrayEquals(end, map.getFederatedRanges()[index].getEndDims());
	}

	private static void assertMatrix(MatrixBlock expected, MatrixBlock actual) {
		assertEquals(expected.getNumRows(), actual.getNumRows());
		assertEquals(expected.getNumColumns(), actual.getNumColumns());
		assertEquals(expected.getNonZeros(), actual.getNonZeros());
		for(int r = 0; r < expected.getNumRows(); r++)
			for(int c = 0; c < expected.getNumColumns(); c++)
				assertEquals(expected.get(r, c), actual.get(r, c), 0);
	}

	private static void assertFrame(FrameBlock expected, FrameBlock actual) {
		assertArrayEquals(expected.getSchema(), actual.getSchema());
		assertArrayEquals(expected.getColumnNames(), actual.getColumnNames());
		assertEquals(expected.getNumRows(), actual.getNumRows());
		assertEquals(expected.getNumColumns(), actual.getNumColumns());
		for(int r = 0; r < expected.getNumRows(); r++)
			for(int c = 0; c < expected.getNumColumns(); c++)
				assertEquals(expected.get(r, c), actual.get(r, c));
	}
}
