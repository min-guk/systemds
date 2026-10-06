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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Data;
import org.apache.sysds.lops.FederatedRefed;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedStatistics;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.instructions.fed.FEDRefedInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaDataFormat;
import org.apache.sysds.test.component.federated.FederatedTestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Docker-only compiler-to-worker proof. The enclosing suite deliberately does not end in
 * {@code Test}; the ordinary host build has no workers on these fixed harness ports.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({JointBoundaryPhysicalModelProofTest.class,
	InvariantFoutSharingDockerProof.RuntimeProof.class})
public class InvariantFoutSharingDockerProof {
	public static class RuntimeProof {
		private static final InetSocketAddress SOURCE = new InetSocketAddress("localhost", 13001);
		private static final InetSocketAddress TARGET = new InetSocketAddress("localhost", 13002);
		private static final int ROWS = 3;
		private static final int COLS = 1;
		private boolean oldStatistics;

		@Before
		public void resetBefore() {
			oldStatistics = DMLScript.STATISTICS;
			DMLScript.STATISTICS = true;
			FederationUtils.clearRefedReuseCache();
			FederatedStatistics.reset();
		}

		@After
		public void resetAfter() {
			FederationUtils.clearRefedReuseCache();
			FederatedStatistics.reset();
			DMLScript.STATISTICS = oldStatistics;
		}

		@Test
		public void invariantFoutUsesTheCompiledSharingGroupAndUploadsOnce() throws Exception {
			var fixture = ExactCompiledSupplySharingTest.fixture();
			var selected = ExactCompiledSupplySharingTest.forcedUpload(
				fixture, "stable", FederatedOutput.FOUT);
			var lifetimes = fixture.surface().selectedSharedSupplyLifetimes(selected.assignment());
			Assert.assertTrue("compiled stable FOUT selection must derive its runtime lifetime",
				lifetimes.contains(selected.physicalIdentity()));
			double price = ExactCompiledSupplySharingTest.movementCost(fixture.surface(), selected);
			Assert.assertTrue("compiled stable FOUT movement must be priced once", price > 0d);

			MatrixBlock expected = block(1d);
			long sourceId = FederatedTestUtils.putMatrixBlock(expected, SOURCE);
			long anchorId = FederatedTestUtils.putMatrixBlock(block(0d), TARGET);
			MatrixObject source = federated("X", SOURCE, sourceId);
			MatrixObject anchor = federated("A", TARGET, anchorId);
			FEDRefedInstruction instruction = stagedInstruction(selected.physicalIdentity(),
				selected.physicalIdentity());
			ExecutionContext context = context(source, anchor);
			FederatedStatistics.reset();

			for(int iteration = 0; iteration < 3; iteration++) {
				MatrixObject output = empty("O" + iteration);
				context.setVariable("O", output);
				instruction.processInstruction(context);
				assertRemoteEquals(expected, output);
				context.cleanupDataObject(context.removeVariable("O"));
			}

			long uploads = putCount();
			long sourceGets = getCount() - 3L;
			Assert.assertEquals("one invariant FOUT source version must execute one target upload",
				1L, uploads);
			Assert.assertEquals("one invariant FOUT source version must be collected once",
				1L, sourceGets);
			System.out.println("INVARIANT_FOUT_SHARING_PROOF stableCost=" + price
				+ " executions=3 getVar=" + sourceGets + " putVar=" + uploads
				+ " aliasCleanup=passed numeric=passed");
		}

		@Test
		public void updatedFoutUsesSingleSupplyAndUploadsEveryVersion() throws Exception {
			var fixture = ExactCompiledSupplySharingTest.fixture();
			var selected = ExactCompiledSupplySharingTest.forcedUpload(
				fixture, "updated", FederatedOutput.FOUT);
			Assert.assertFalse("compiled updated FOUT selection must not derive a cross-version lifetime",
				fixture.surface().selectedSharedSupplyLifetimes(selected.assignment())
					.contains(selected.physicalIdentity()));
			var stable = ExactCompiledSupplySharingTest.forcedUpload(
				fixture, "stable", FederatedOutput.FOUT);
			double stablePrice = ExactCompiledSupplySharingTest.movementCost(fixture.surface(), stable);
			double updatedPrice = ExactCompiledSupplySharingTest.movementCost(fixture.surface(), selected);
			Assert.assertEquals("compiled updated FOUT must price all three produced versions",
				3d * stablePrice, updatedPrice, Math.max(1e-12, stablePrice * 1e-12));

			MatrixBlock[] expected = {block(11d), block(21d), block(31d)};
			long[] sourceIds = new long[expected.length];
			for(int i = 0; i < expected.length; i++)
				sourceIds[i] = FederatedTestUtils.putMatrixBlock(expected[i], SOURCE);
			long anchorId = FederatedTestUtils.putMatrixBlock(block(0d), TARGET);
			MatrixObject anchor = federated("A", TARGET, anchorId);
			FEDRefedInstruction instruction = stagedInstruction(selected.physicalIdentity(), "");
			ExecutionContext context = context(federated("X0", SOURCE, sourceIds[0]), anchor);
			FederatedStatistics.reset();

			for(int iteration = 0; iteration < expected.length; iteration++) {
				context.setVariable("X", federated("X" + iteration, SOURCE, sourceIds[iteration]));
				MatrixObject output = empty("O" + iteration);
				context.setVariable("O", output);
				instruction.processInstruction(context);
				assertRemoteEquals(expected[iteration], output);
				context.cleanupDataObject(context.removeVariable("O"));
			}

			long uploads = putCount();
			long sourceGets = getCount() - 3L;
			Assert.assertEquals("three FOUT value versions must execute three target uploads",
				3L, uploads);
			Assert.assertEquals("three FOUT value versions must execute three source collections",
				3L, sourceGets);
			System.out.println("INVARIANT_FOUT_SHARING_PROOF updatedCost=" + updatedPrice
				+ " executions=3 getVar=" + sourceGets + " putVar=" + uploads
				+ " aliasCleanup=passed numeric=passed");
		}

		private static FEDRefedInstruction stagedInstruction(String action, String group) {
			Data input = data("X");
			Data anchor = data("A");
			FederatedRefed lop = new FederatedRefed(input, anchor,
				DataType.MATRIX, ValueType.FP64, FType.ROW.name());
			lop.setPlannerSyntheticActionKey(action);
			lop.setSupplySharingGroup(group);
			lop.setRequiresLocalMaterialization(true);
			FEDRefedInstruction instruction = FEDRefedInstruction.parseInstruction(
				lop.getInstructions("X", "A", "O"));
			instruction.setPlannerSyntheticActionKey(action);
			Assert.assertTrue(instruction.requiresLocalMaterialization());
			Assert.assertEquals(group, instruction.getSupplySharingGroup());
			return instruction;
		}

		private static Data data(String name) {
			return new Data(OpOpData.TRANSIENTREAD, null, null, name, null,
				DataType.MATRIX, ValueType.FP64, FileFormat.BINARY);
		}

		private static ExecutionContext context(MatrixObject source, MatrixObject anchor) {
			ExecutionContext context = ExecutionContextFactory.createContext();
			context.setVariable("X", source);
			context.setVariable("A", anchor);
			context.setVariable("O", empty("O"));
			return context;
		}

		private static MatrixObject federated(String name, InetSocketAddress address, long id) {
			MatrixObject matrix = empty(name);
			matrix.setFedMapping(new FederationMap(id, List.of(Pair.of(
				new FederatedRange(new long[] {0, 0}, new long[] {ROWS, COLS}),
				new FederatedData(DataType.MATRIX, address, null, id))), FType.ROW));
			return matrix;
		}

		private static MatrixObject empty(String name) {
			return new MatrixObject(ValueType.FP64, name,
				new MetaDataFormat(new MatrixCharacteristics(ROWS, COLS, 1024, ROWS),
					FileFormat.BINARY));
		}

		private static MatrixBlock block(double first) {
			return new MatrixBlock(ROWS, COLS, new double[] {first, first + 1d, first + 2d});
		}

		private static void assertRemoteEquals(MatrixBlock expected, MatrixObject output) {
			Assert.assertTrue("staged REFED output must remain federated", output.isFederated());
			FederationMap map = output.getFedMapping();
			Assert.assertEquals(1, map.getSize());
			FederatedData data = map.getFederatedData()[0];
			MatrixBlock actual = FederatedTestUtils.getMatrixBlock(data.getVarID(), data.getAddress());
			Assert.assertEquals(expected.getNumRows(), actual.getNumRows());
			Assert.assertEquals(expected.getNumColumns(), actual.getNumColumns());
			for(int row = 0; row < expected.getNumRows(); row++)
				for(int col = 0; col < expected.getNumColumns(); col++)
					Assert.assertEquals(expected.get(row, col), actual.get(row, col), 0d);
		}

		private static long putCount() throws Exception {
			return counter("putCount");
		}

		private static long getCount() throws Exception {
			return counter("getCount");
		}

		private static long counter(String name) throws Exception {
			Field field = FederatedStatistics.class.getDeclaredField(name);
			field.setAccessible(true);
			return ((LongAdder) field.get(null)).longValue();
		}
	}
}
