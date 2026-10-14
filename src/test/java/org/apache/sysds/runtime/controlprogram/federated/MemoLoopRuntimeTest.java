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
package org.apache.sysds.runtime.controlprogram.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.memo.MemoPartition;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Movement;
import org.apache.sysds.lops.compile.MemoSupplyRegistry.Recipe;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.apache.sysds.runtime.instructions.cp.StringObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestUtils;
import org.junit.After;
import org.junit.Test;

public class MemoLoopRuntimeTest {
	@After
	public void resetRegistry() {
		MemoLoopRuntime.clear();
		FederatedData.clearFederatedWorkers();
	}

	@Test
	public void twoWorkerEntryUploadsOnceAndBackedgeIsDirectOnly() throws Exception {
		Workers workers = workers();
		try {
			ExecutionContext ec = context("X", matrix());
			StatementBlock loop = new StatementBlock();
			MemoLoopRuntime.register(loop, List.of(contract("X", workers.pool)));

			try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
				MatrixObject entered = ec.getMatrixObject("X");
				assertTrue(entered.isFederated());
				workers.pool.validateIngress(entered.getFedMapping(), DataType.MATRIX,
					MemoPartition.ROW.runtimeType(2), 3, 2);
				assertEquals(1, MemoLoopRuntime.entryTransfers());
				scope.iterationCompleted(true);
				scope.iterationCompleted(false);
				scope.complete();
				assertEquals(0, MemoLoopRuntime.backedgeTransfers());
			}
			assertTrue("the final carried value remains available to exit consumers",
				ec.containsVariable("X"));
			assertTrue(ec.getMatrixObject("X").isFederated());
		}
		finally { workers.close(); }
	}

	@Test
	public void backedgeLayoutMismatchThrowsWithoutRepairTransfer() throws Exception {
		Workers workers = workers();
		try {
			ExecutionContext ec = context("X", matrix());
			StatementBlock loop = new StatementBlock();
			MemoLoopRuntime.register(loop, List.of(contract("X", workers.pool)));
			try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
				ec.setVariable("X", matrixObject("X", matrix()));
				assertThrows(RuntimeException.class, () -> scope.iterationCompleted(true));
				assertEquals(0, MemoLoopRuntime.backedgeTransfers());
			}
		}
		finally { workers.close(); }
	}

	@Test
	public void guardedTwoCarriedValuesChooseOneCorrelatedRuntimeArm() throws Exception {
		Workers workers = workers();
		try {
			ExecutionContext ec = context("X", matrix());
			ec.setVariable("Y", matrixObject("Y", matrix()));
			List<MemoLoopRuntime.Contract> contracts = List.of(
				guardedContract("X", workers.pool), guardedContract("Y", workers.pool));
			StatementBlock localArm = new StatementBlock();
			MemoLoopRuntime.register(localArm, contracts);
			try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(localArm, ec, 0)) {
				assertTrue(ec.getMatrixObject("X").isFederated());
				assertTrue(ec.getMatrixObject("Y").isFederated());
				scope.iterationCompleted(true);
				scope.iterationCompleted(false);
				scope.complete();
			}
			assertEquals(2, MemoLoopRuntime.entryTransfers());

			StatementBlock federatedArm = new StatementBlock();
			MemoLoopRuntime.register(federatedArm, contracts);
			MatrixObject x = ec.getMatrixObject("X");
			MatrixObject y = ec.getMatrixObject("Y");
			try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(federatedArm, ec, 0)) {
				assertSame(x, ec.getMatrixObject("X"));
				assertSame(y, ec.getMatrixObject("Y"));
				scope.iterationCompleted(true);
				scope.iterationCompleted(false);
				scope.complete();
			}
			assertEquals("DIRECT entry recipes do not materialize again", 2,
				MemoLoopRuntime.entryTransfers());
		}
		finally { workers.close(); }
	}

	@Test
	public void scalarContractIsLocalDirectAndStatementBlockIdentityIsExact() {
		MemoLoopRuntime.Contract scalar = new MemoLoopRuntime.Contract("i", DataType.SCALAR,
			ValueType.INT64, MemoPartition.LOCAL, -1, -1, Privacy.PUBLIC,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), null);
		assertEquals(ValueType.INT64, scalar.entryValueType());
		assertEquals(scalar.entryValueType(), scalar.valueType());
		StatementBlock selected = new StatementBlock();
		StatementBlock unselected = new StatementBlock();
		MemoLoopRuntime.register(selected, List.of(scalar));
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("i", new IntObject(7));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(unselected, ec, 0)) {
			assertEquals(0, MemoLoopRuntime.entryTransfers());
			scope.complete();
		}
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(selected, ec, 0)) {
			scope.iterationCompleted(true);
			scope.iterationCompleted(false);
			scope.complete();
		}
		assertFalse(ec.getVariable("i") == null);
	}

	@Test
	public void numericScalarEntryAndRepeatTypesAreValidatedByPhase() {
		MemoLoopRuntime.Contract scalar = new MemoLoopRuntime.Contract("step_sz", DataType.SCALAR,
			ValueType.INT64, ValueType.FP64, MemoPartition.LOCAL, -1, -1, -1, -1, true, true,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), null);
		StatementBlock loop = new StatementBlock();
		MemoLoopRuntime.register(loop, List.of(scalar));
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("step_sz", new IntObject(0));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			ec.setVariable("step_sz", new DoubleObject(0.5));
			scope.iterationCompleted(true);
			scope.iterationCompleted(false);
			scope.complete();
		}
		ec.setVariable("step_sz", new IntObject(0));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			scope.complete();
		}
		ec.setVariable("step_sz", new IntObject(0));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			ec.setVariable("step_sz", new DoubleObject(0.5));
			scope.iterationCompleted(false);
			scope.complete();
		}
		assertEquals(ValueType.INT64, scalar.entryValueType());
		assertEquals(ValueType.FP64, scalar.valueType());
	}

	@Test
	public void numericScalarTransitionRejectsWrongEntryAndWrongRepeatTypes() {
		MemoLoopRuntime.Contract scalar = new MemoLoopRuntime.Contract("step_sz", DataType.SCALAR,
			ValueType.INT64, ValueType.FP64, MemoPartition.LOCAL, -1, -1, -1, -1, true, true,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), null);
		StatementBlock loop = new StatementBlock();
		MemoLoopRuntime.register(loop, List.of(scalar));
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("step_sz", new DoubleObject(0));
		assertThrows(DMLRuntimeException.class, () -> MemoLoopRuntime.enter(loop, ec, 0));

		ec.setVariable("step_sz", new IntObject(0));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			for(Data wrong : List.of(new IntObject(1), new BooleanObject(true), new StringObject("x"),
				matrixObject("X", matrix(1, 1)))) {
				ec.setVariable("step_sz", wrong);
				assertThrows(DMLRuntimeException.class, () -> scope.iterationCompleted(true));
			}
		}
	}

	@Test
	public void contractRejectsEveryOtherEntryRepeatTypeTransition() {
		List<Recipe> direct = List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null));
		assertThrows(IllegalArgumentException.class, () -> new MemoLoopRuntime.Contract("x", DataType.SCALAR,
			ValueType.FP64, ValueType.INT64, MemoPartition.LOCAL, -1, -1, -1, -1, true, true, direct, null));
		assertThrows(IllegalArgumentException.class, () -> new MemoLoopRuntime.Contract("x", DataType.SCALAR,
			ValueType.INT32, ValueType.FP64, MemoPartition.LOCAL, -1, -1, -1, -1, true, true, direct, null));
		assertThrows(IllegalArgumentException.class, () -> new MemoLoopRuntime.Contract("X", DataType.MATRIX,
			ValueType.INT64, ValueType.FP64, MemoPartition.LOCAL, 1, 1, 1, 1, true, true, direct, null));
	}

	@Test
	public void finalIterationMayKeepADifferentExitLayout() throws Exception {
		Workers workers = workers();
		try {
			ExecutionContext ec = context("X", matrix());
			StatementBlock loop = new StatementBlock();
			MemoLoopRuntime.register(loop, List.of(contract("X", workers.pool)));
			try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
				scope.iterationCompleted(true);
				MatrixObject finalLocal = matrixObject("final-X", matrix());
				ec.setVariable("X", finalLocal);
				scope.iterationCompleted(false);
				scope.complete();
				assertSame(finalLocal, ec.getMatrixObject("X"));
				assertFalse(finalLocal.isFederated());
			}
		}
		finally { workers.close(); }
	}

	@Test
	public void requiredEntryAndRepeatMayHaveDifferentShapes() {
		ExecutionContext ec = context("X", matrix(1, 1));
		StatementBlock loop = new StatementBlock();
		MemoLoopRuntime.Contract contract = new MemoLoopRuntime.Contract("X", DataType.MATRIX,
			ValueType.FP64, MemoPartition.LOCAL, 1, 1, 3, 2, true, Privacy.PUBLIC,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), null);
		MemoLoopRuntime.register(loop, List.of(contract));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			ec.setVariable("X", matrixObject("repeat-X", matrix()));
			scope.iterationCompleted(true);
			scope.iterationCompleted(false);
			scope.complete();
		}
	}

	@Test
	public void deadFirstEntrySkipsInitialValueChecksAndTransfer() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		StatementBlock loop = new StatementBlock();
		MemoLoopRuntime.Contract contract = new MemoLoopRuntime.Contract("X", DataType.MATRIX,
			ValueType.FP64, MemoPartition.LOCAL, -1, -1, 3, 2, false, Privacy.PUBLIC,
			List.of(), null);
		MemoLoopRuntime.register(loop, List.of(contract));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			assertEquals(0, MemoLoopRuntime.entryTransfers());
			ec.setVariable("X", matrixObject("repeat-X", matrix()));
			scope.iterationCompleted(true);
			scope.iterationCompleted(false);
			scope.complete();
		}
	}

	@Test
	public void selectedEmptyContractAcceptsZeroAndOneIterationOnNormalCompletion() {
		StatementBlock loop = new StatementBlock();
		MemoLoopRuntime.register(loop, List.of());
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		assertTrue(MemoLoopRuntime.isSelected(loop));
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			scope.complete();
		}
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(loop, ec, 0)) {
			scope.iterationCompleted(false);
			scope.complete();
		}
		try(MemoLoopRuntime.Scope scope = MemoLoopRuntime.enter(new StatementBlock(), ec, 0)) {
			scope.complete();
		}
	}

	@Test
	public void unknownShapeAndProtectedLocalContractsAreRejected() {
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
			InetSocketAddress.createUnresolved("localhost", 12345)));
		assertThrows(IllegalArgumentException.class, () -> new MemoLoopRuntime.Contract("X",
			DataType.MATRIX, ValueType.FP64, MemoPartition.ROW, -1, 2, Privacy.PUBLIC,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode())), pool));
		assertThrows(IllegalArgumentException.class, () -> new MemoLoopRuntime.Contract("X",
			DataType.MATRIX, ValueType.FP64, MemoPartition.LOCAL, 3, 2, Privacy.PRIVATE,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), pool));
		assertThrows(IllegalArgumentException.class, () -> new MemoLoopRuntime.Contract("X",
			DataType.MATRIX, ValueType.FP64, MemoPartition.LOCAL, 3, 2, 3, 2, true, false,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), pool));
		MemoLoopRuntime.Contract released = new MemoLoopRuntime.Contract("X", DataType.MATRIX,
			ValueType.FP64, MemoPartition.LOCAL, 3, 2, 3, 2, true, true,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.DIRECT, null)), pool);
		assertTrue(released.localResidencyApproved());
		MemoLoopRuntime.Contract federated = new MemoLoopRuntime.Contract("X", DataType.MATRIX,
			ValueType.FP64, MemoPartition.ROW, 3, 2, 3, 2, true, false,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode())), pool);
		assertFalse("FED loop heads do not require one invariant privacy label",
			federated.localResidencyApproved());
	}

	private static MemoLoopRuntime.Contract contract(String variable, CanonicalWorkerPool pool) {
		return new MemoLoopRuntime.Contract(variable, DataType.MATRIX, ValueType.FP64,
			MemoPartition.ROW, 3, 2, Privacy.PUBLIC,
			List.of(new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode())), pool);
	}

	private static MemoLoopRuntime.Contract guardedContract(String variable, CanonicalWorkerPool pool) {
		return new MemoLoopRuntime.Contract(variable, DataType.MATRIX, ValueType.FP64,
			MemoPartition.ROW, 3, 2, Privacy.PUBLIC, List.of(
				new Recipe(MemoPartition.LOCAL, Movement.UPLOAD, pool.encode()),
				new Recipe(MemoPartition.ROW, Movement.DIRECT, pool.encode())), pool);
	}

	private static ExecutionContext context(String name, MatrixBlock block) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable(name, matrixObject(name, block));
		return ec;
	}

	private static MatrixObject matrixObject(String name, MatrixBlock block) {
		return new MatrixObject(ValueType.FP64, name, new MetaDataFormat(
			new MatrixCharacteristics(block.getNumRows(), block.getNumColumns(), 1024,
				block.getNonZeros()), FileFormat.BINARY), block);
	}

	private static MatrixBlock matrix() {
		return matrix(3, 2);
	}

	private static MatrixBlock matrix(int rows, int columns) {
		MatrixBlock block = new MatrixBlock(rows, columns, false);
		for(int row = 0; row < block.getNumRows(); row++)
			for(int column = 0; column < block.getNumColumns(); column++)
				block.set(row, column, row * block.getNumColumns() + column + 1);
		block.recomputeNonZeros();
		return block;
	}

	private static Workers workers() throws Exception {
		int firstPort = AutomatedTestBase.getRandomAvailablePort();
		int secondPort = AutomatedTestBase.getRandomAvailablePort();
		while(secondPort == firstPort)
			secondPort = AutomatedTestBase.getRandomAvailablePort();
		Thread first = AutomatedTestBase.startLocalFedWorkerThread(firstPort, 1000);
		Thread second = AutomatedTestBase.startLocalFedWorkerThread(secondPort, 1000);
		CanonicalWorkerPool pool = CanonicalWorkerPool.of(List.of(
			new InetSocketAddress("localhost", firstPort), new InetSocketAddress("localhost", secondPort)));
		return new Workers(pool, first, second);
	}

	private record Workers(CanonicalWorkerPool pool, Thread first, Thread second) implements AutoCloseable {
		@Override public void close() {
			FederatedData.clearFederatedWorkers();
			TestUtils.shutdownThreads(first, second);
		}
	}
}
