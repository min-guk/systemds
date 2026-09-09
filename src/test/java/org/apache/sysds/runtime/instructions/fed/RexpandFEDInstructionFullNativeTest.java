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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

/** Physical kernel/map contract only; privacy-domain exclusion is tested at shared analysis. */
public class RexpandFEDInstructionFullNativeTest {
	@Test
	public void fullColumnExpansionStaysRemote() throws Exception {
		assertNativeFull("cols", 4, 3);
	}

	@Test
	public void fullRowExpansionStaysFullAfterTranspose() throws Exception {
		assertNativeFull("rows", 3, 4);
	}

	private static void assertNativeFull(String direction, long rows, long cols) throws Exception {
		long id = "cols".equals(direction) ? 9101 : 9201;
		MatrixBlock labels = new MatrixBlock(4, 1, false);
		labels.allocateDenseBlock();
		for(int row = 0; row < 4; row++)
			labels.set(row, 0, row % 3 + 1);
		MatrixObject workerInput = ExecutionContext.createMatrixObject(labels);
		RecordingData data = new RecordingData(id, workerInput);
		MatrixObject input = empty("Y", 4, 1);
		input.setFedMapping(new FederationMap(id, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {4, 1}), data)), FType.FULL));
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("Y", input);
		ec.setVariable("O", empty("O", -1, -1));
		String instruction = String.join(Lop.OPERAND_DELIMITOR, "FED", "rexpand", "target=Y",
			"max=3", "dir=" + direction, "cast=true", "ignore=true", "k=1",
			"O" + Lop.DATATYPE_PREFIX + "MATRIX" + Lop.VALUETYPE_PREFIX + "FP64");

		ParameterizedBuiltinFEDInstruction.parseInstruction(instruction).processInstruction(ec);

		MatrixObject output = ec.getMatrixObject("O");
		assertEquals(FType.FULL, output.getFedMapping().getType());
		assertEquals(rows, output.getNumRows());
		assertEquals(cols, output.getNumColumns());
		assertEquals(1, output.getFedMapping().getSize());
		assertArrayEquals(new long[] {0, 0}, output.getFedMapping().getFederatedRanges()[0].getBeginDims());
		assertArrayEquals(new long[] {rows, cols}, output.getFedMapping().getFederatedRanges()[0].getEndDims());
		assertEquals(1, data.requests.size());
		assertEquals(FederatedRequest.RequestType.EXEC_INST, data.requests.get(0).getType());
		// Test-only inspection follows the no-GET runtime assertion. The expansion
		// above uses the real worker handler and CP kernel, not a fake response.
		FederatedResponse response = data.inspect(new FederatedRequest(
			FederatedRequest.RequestType.GET_VAR, output.getFedMapping().getID())).get();
		assertTrue(response.isSuccessful());
		MatrixBlock actual = (MatrixBlock) response.getData()[0];
		assertEquals(rows, actual.getNumRows());
		assertEquals(cols, actual.getNumColumns());
		for(int observation = 0; observation < 4; observation++)
			for(int category = 0; category < 3; category++)
				assertEquals(observation % 3 == category ? 1.0 : 0.0,
					"rows".equals(direction) ? actual.get(category, observation) : actual.get(observation, category), 0.0);
	}

	private static MatrixObject empty(String name, long rows, long cols) {
		return new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(rows, cols, 1000, -1)));
	}

	private static final class RecordingData extends FederatedLocalData {
		private final List<FederatedRequest> requests = new ArrayList<>();
		private final InetSocketAddress address = new InetSocketAddress("localhost", 19901);

		private RecordingData(long id, MatrixObject input) {
			super(id, input);
		}

		@Override
		public InetSocketAddress getAddress() {
			return address;
		}

		@Override
		public FederatedData copyWithNewID(long id) {
			return new FederatedData(DataType.MATRIX, address, "in-process-worker", id);
		}

		@Override
		public synchronized Future<FederatedResponse> executeFederatedOperation(FederatedRequest... batch) {
			requests.addAll(Arrays.asList(batch));
			return super.executeFederatedOperation(batch);
		}

		private Future<FederatedResponse> inspect(FederatedRequest request) {
			return super.executeFederatedOperation(request);
		}
	}
}
