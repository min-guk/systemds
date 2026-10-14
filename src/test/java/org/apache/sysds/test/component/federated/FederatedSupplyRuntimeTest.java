/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.FederatedSupply;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.FrameObject;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.CanonicalWorkerPool;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedUDF;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.frame.data.FrameBlock;
import org.apache.sysds.runtime.frame.data.columns.ColumnMetadata;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.fed.FEDFoutInstruction;
import org.apache.sysds.runtime.lineage.LineageItem;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.Test;

public class FederatedSupplyRuntimeTest {
	@Test
	public void guardedLocalAndRowBranchesDispatchOnlySelectedRecipes() throws Exception {
		int port1 = AutomatedTestBase.getRandomAvailablePort();
		int port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null;
		Thread worker2 = null;
		Path privacyMetadata = null;
		try {
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
			CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
				new InetSocketAddress("localhost", port1), new InetSocketAddress("localhost", port2)));
			MatrixBlock matrix = matrix();
			List<Recipe> toLocal = List.of(
				new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null),
				new Recipe(MemoPartition.ROW, Movement.COLLECT, pool.encode()));

			ExecutionContext localToLocal = context(matrix);
			MatrixObject localInput = localToLocal.getMatrixObject("X");
			runSupply(localToLocal, MemoPartition.LOCAL, toLocal);
			assertSame(localInput, localToLocal.getMatrixObject("Y"));
			assertFalse(localToLocal.getMatrixObject("Y").isFederated());
			assertMatrix(matrix, localToLocal.getMatrixObject("Y").acquireReadAndRelease());

			ExecutionContext rowToLocal = federatedContext(matrix, pool, "ROW");
			runSupply(rowToLocal, MemoPartition.LOCAL, toLocal);
			assertFalse(rowToLocal.getMatrixObject("Y").isFederated());
			assertMatrix(matrix, rowToLocal.getMatrixObject("Y").acquireReadAndRelease());

			List<Recipe> toRow = List.of(
				new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode()),
				new Recipe(MemoPartition.ROW, Movement.DIRECT, pool.encode()));
			ExecutionContext localToRow = context(matrix);
			runSupply(localToRow, MemoPartition.ROW, toRow);
			pool.validateIngress(localToRow.getMatrixObject("Y").getFedMapping(), DataType.MATRIX,
				MemoPartition.ROW.runtimeType(pool.size()), 3, 2);

			ExecutionContext rowToRow = federatedContext(matrix, pool, "ROW");
			MatrixObject rowInput = rowToRow.getMatrixObject("X");
			FederationMap original = rowInput.getFedMapping();
			runSupply(rowToRow, MemoPartition.ROW, toRow);
			assertSame(rowInput, rowToRow.getMatrixObject("Y"));
			assertSame(original, rowToRow.getMatrixObject("Y").getFedMapping());
			MatrixObject removedAlias = (MatrixObject) rowToRow.removeVariable("Y");
			rowToRow.cleanupDataObject(removedAlias);
			rowToRow.setVariable("Y", matrixObject("Y", null));
			runSupply(rowToRow, MemoPartition.LOCAL, toLocal);
			assertMatrix(matrix, rowToRow.getMatrixObject("Y").acquireReadAndRelease());

			ExecutionContext colToRow = federatedContext(matrix, pool, "COL");
			assertThrows(RuntimeException.class, () -> runSupply(colToRow, MemoPartition.ROW, toRow));

			ExecutionContext protectedRow = federatedContext(matrix, pool, "ROW");
			privacyMetadata = Files.createTempFile("fed-supply-private", ".mtd");
			Files.writeString(privacyMetadata, "{\"data_type\":\"matrix\",\"value_type\":\"double\","
				+ "\"rows\":3,\"cols\":2,\"nnz\":6,\"format\":\"binary\",\"privacy\":\"private\"}");
			FederationMap protectedMap = withFilepath(protectedRow.getMatrixObject("X").getFedMapping(),
				privacyMetadata.toString().substring(0, privacyMetadata.toString().length() - 4));
			protectedRow.getMatrixObject("X").setFedMapping(protectedMap);
			runSupply(protectedRow, MemoPartition.LOCAL, toLocal);
			assertMatrix(matrix, protectedRow.getMatrixObject("Y").acquireReadAndRelease());

			ExecutionContext replicated = federatedContext(matrix, pool, "REP");
			FederationMap replicatedMap = replicated.getMatrixObject("X").getFedMapping();
			long aggregateId = FederationUtils.getNextFedDataID();
			replicatedMap.execute(-1, true, new FederatedRequest(FederatedRequest.RequestType.EXEC_UDF,
				aggregateId, new FullSumMatrix(replicatedMap.getID(), aggregateId)));
			FederationMap aggregateMap = derivedMap(replicatedMap, aggregateId,
				privacyMetadata.toString().substring(0, privacyMetadata.toString().length() - 4));
			ExecutionContext derivedAggregate = new ExecutionContext(new LocalVariableMap());
			MatrixObject aggregate = matrixObject("X", null);
			aggregate.getDataCharacteristics().set(1, 1, 1024, 1);
			aggregate.setFedMapping(aggregateMap);
			derivedAggregate.setVariable("X", aggregate);
			derivedAggregate.setVariable("Y", matrixObject("Y", null));
			runSupply(derivedAggregate, MemoPartition.LOCAL, List.of(
				new Recipe(MemoPartition.REP, Movement.COLLECT, pool.encode())));
			assertEquals(matrix.sum(), derivedAggregate.getMatrixObject("Y").acquireReadAndRelease().get(0, 0), 0);

			ExecutionContext missingRemote = federatedContext(matrix, pool, "ROW");
			FederationMap missingMap = withId(missingRemote.getMatrixObject("X").getFedMapping(), Long.MAX_VALUE - 7);
			missingRemote.getMatrixObject("X").setFedMapping(missingMap);
			assertThrows(RuntimeException.class,
				() -> runSupply(missingRemote, MemoPartition.LOCAL, toLocal));
		}
		finally {
			FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker1, worker2);
			if(privacyMetadata != null)
				Files.deleteIfExists(privacyMetadata);
		}
	}

	@Test
	public void frameUploadAndCollectPreserveSchemaNamesMetadataAndPayload() throws Exception {
		int port1 = AutomatedTestBase.getRandomAvailablePort();
		int port2 = AutomatedTestBase.getRandomAvailablePort();
		while(port2 == port1)
			port2 = AutomatedTestBase.getRandomAvailablePort();
		Thread worker1 = null;
		Thread worker2 = null;
		try {
			worker1 = AutomatedTestBase.startLocalFedWorkerThread(port1, 1000);
			worker2 = AutomatedTestBase.startLocalFedWorkerThread(port2, 1000);
			CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
				new InetSocketAddress("localhost", port1), new InetSocketAddress("localhost", port2)));
			FrameBlock expected = frame();
			ExecutionContext upload = frameContext(expected);
			runFrameSupply(upload, MemoPartition.REP, List.of(
				new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode())));
			assertArrayEquals(expected.getSchema(), upload.getFrameObject("Y").getSchema());

			ExecutionContext collect = new ExecutionContext(new LocalVariableMap());
			collect.setVariable("X", upload.getFrameObject("Y"));
			collect.setVariable("Y", frameObject("Y", null));
			runFrameSupply(collect, MemoPartition.LOCAL, List.of(
				new Recipe(MemoPartition.REP, Movement.COLLECT, pool.encode())));
			assertFrame(expected, collect.getFrameObject("Y").acquireReadAndRelease());
		}
		finally {
			FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(worker1, worker2);
		}
	}

	private static ExecutionContext federatedContext(MatrixBlock block, CanonicalWorkerPool pool, String target) {
		ExecutionContext upload = context(block);
		String instruction = InstructionUtils.concatOperands("FED", "fed_fout",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.createLiteralOperand(pool.encode(), ValueType.STRING),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()), target);
		FEDFoutInstruction.parseInstruction(instruction).processInstruction(upload);
		ExecutionContext result = new ExecutionContext(new LocalVariableMap());
		result.setVariable("X", upload.getMatrixObject("Y"));
		result.setVariable("Y", matrixObject("Y", null));
		return result;
	}

	private static ExecutionContext context(MatrixBlock block) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("X", matrixObject("X", block));
		ec.setVariable("Y", matrixObject("Y", null));
		return ec;
	}

	private static ExecutionContext frameContext(FrameBlock block) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("X", frameObject("X", block));
		ec.setVariable("Y", frameObject("Y", null));
		return ec;
	}

	private static MatrixObject matrixObject(String name, MatrixBlock block) {
		long rows = block == null ? -1 : block.getNumRows();
		long cols = block == null ? -1 : block.getNumColumns();
		long nnz = block == null ? -1 : block.getNonZeros();
		return new MatrixObject(ValueType.FP64, name,
			new MetaDataFormat(new MatrixCharacteristics(rows, cols, 1024, nnz), FileFormat.BINARY), block);
	}

	private static FrameObject frameObject(String name, FrameBlock block) {
		long rows = block == null ? -1 : block.getNumRows();
		long cols = block == null ? -1 : block.getNumColumns();
		ValueType[] schema = block == null ? new ValueType[] {ValueType.STRING, ValueType.INT64}
			: block.getSchema().clone();
		FrameObject object = new FrameObject(name,
			new MetaDataFormat(new MatrixCharacteristics(rows, cols, 1024, -1), FileFormat.BINARY), schema);
		if(block != null) {
			object.acquireModify(block);
			object.release();
		}
		return object;
	}

	private static void runSupply(ExecutionContext ec, MemoPartition target, List<Recipe> recipes) {
		runSupply(ec, target, recipes, DataType.MATRIX, ValueType.FP64);
	}

	private static void runFrameSupply(ExecutionContext ec, MemoPartition target, List<Recipe> recipes) {
		runSupply(ec, target, recipes, DataType.FRAME, ValueType.STRING);
	}

	private static void runSupply(ExecutionContext ec, MemoPartition target, List<Recipe> recipes,
		DataType dataType, ValueType valueType) {
		String instruction = InstructionUtils.concatOperands("FED", "fed_supply",
			InstructionUtils.concatOperandParts("X", dataType.name(), valueType.name()),
			InstructionUtils.concatOperandParts("Y", dataType.name(), valueType.name()),
			target.name(), InstructionUtils.createLiteralOperand(
				FederatedSupply.encodeRecipes(recipes), ValueType.STRING));
		InstructionParser.parseSingleInstruction(instruction).processInstruction(ec);
	}

	private static FederationMap withFilepath(FederationMap source, String filepath) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(Pair<FederatedRange, FederatedData> entry : source.getMap())
			entries.add(Pair.of(entry.getKey(), new FederatedData(DataType.MATRIX,
				entry.getValue().getAddress(), filepath, source.getID())));
		return new FederationMap(source.getID(), entries, source.getType());
	}

	private static FederationMap withId(FederationMap source, long id) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(Pair<FederatedRange, FederatedData> entry : source.getMap())
			entries.add(Pair.of(entry.getKey(), entry.getValue().copyWithNewID(id)));
		return new FederationMap(id, entries, source.getType());
	}

	private static FederationMap derivedMap(FederationMap source, long id, String staleFilepath) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for(Pair<FederatedRange, FederatedData> entry : source.getMap())
			entries.add(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {1, 1}),
				new FederatedData(DataType.MATRIX, entry.getValue().getAddress(), staleFilepath, id)));
		return new FederationMap(id, entries, FType.BROADCAST);
	}

	private static final class FullSumMatrix extends FederatedUDF {
		private static final long serialVersionUID = 1L;
		private final long _outputId;

		private FullSumMatrix(long inputId, long outputId) {
			super(new long[] {inputId});
			_outputId = outputId;
		}

		@Override
		public FederatedResponse execute(ExecutionContext ec, Data... data) {
			MatrixBlock input = ((MatrixObject) data[0]).acquireReadAndRelease();
			ec.setVariable(Long.toString(_outputId),
				ExecutionContext.createMatrixObject(new MatrixBlock(1, 1, input.sum())));
			return new FederatedResponse(FederatedResponse.ResponseType.SUCCESS_EMPTY);
		}

		@Override
		public Pair<String, LineageItem> getLineageItem(ExecutionContext ec) {
			return null;
		}
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
		FrameBlock frame = new FrameBlock(new ValueType[] {ValueType.STRING, ValueType.INT64},
			new String[] {"name", "id"});
		frame.appendRow(new Object[] {"a", 1L});
		frame.appendRow(new Object[] {"b", 2L});
		frame.setColumnMetadata(new ColumnMetadata[] {
			new ColumnMetadata(2, "missing-name"), new ColumnMetadata(2, "-1")});
		return frame;
	}

	private static void assertMatrix(MatrixBlock expected, MatrixBlock actual) {
		assertEquals(expected.getNumRows(), actual.getNumRows());
		assertEquals(expected.getNumColumns(), actual.getNumColumns());
		for(int r = 0; r < expected.getNumRows(); r++)
			for(int c = 0; c < expected.getNumColumns(); c++)
				assertEquals(expected.get(r, c), actual.get(r, c), 0);
	}

	private static void assertFrame(FrameBlock expected, FrameBlock actual) {
		assertArrayEquals(expected.getSchema(), actual.getSchema());
		assertArrayEquals(expected.getColumnNames(), actual.getColumnNames());
		assertArrayEquals(expected.getColumnMetadata(), actual.getColumnMetadata());
		assertEquals(expected.getNumRows(), actual.getNumRows());
		assertEquals(expected.getNumColumns(), actual.getNumColumns());
		for(int r = 0; r < expected.getNumRows(); r++)
			for(int c = 0; c < expected.getNumColumns(); c++)
				assertEquals(expected.get(r, c), actual.get(r, c));
	}
}
