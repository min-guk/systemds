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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class BuiltinNaryFEDInstructionBroadcastTest {
	private static class RecordingMap extends FederationMap {
		private FederatedRequest[][] slices;
		private FederatedRequest instruction;
		private int directCalls;
		private int singleSliceCalls;

		RecordingMap(FType type) {
			super(71, ranges(), type);
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			directCalls++;
			instruction = requests[0];
			return responses();
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait,
			FederatedRequest[] oneSlice, FederatedRequest... requests) {
			singleSliceCalls++;
			slices = new FederatedRequest[][] {oneSlice};
			instruction = requests[0];
			return responses();
		}

		@Override
		public Future<FederatedResponse>[] executeMultipleSlices(long tid, boolean wait,
			FederatedRequest[][] allSlices, FederatedRequest[] requests) {
			slices = allSlices;
			instruction = requests[0];
			return responses();
		}

		@SuppressWarnings("unchecked")
		private Future<FederatedResponse>[] responses() {
			Future<FederatedResponse>[] results = new Future[getSize()];
			for(int i = 0; i < results.length; i++)
				results[i] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(1)));
			return results;
		}
	}

	@Test
	public void twoLocalMatricesUseDistinctAlignedBroadcasts() {
		ExecutionContext ec = context();
		BuiltinNaryFEDInstruction.parseInstruction(instruction("X", "A", "B", "Z"))
			.processInstruction(ec);

		RecordingMap map = (RecordingMap) ec.getMatrixObject("X").getFedMapping();
		assertNotNull(map.slices);
		assertEquals(2, map.slices.length);
		assertEquals(2, map.slices[0].length);
		assertEquals(2, map.slices[1].length);
		assertNotEquals(map.slices[0][0].getID(), map.slices[1][0].getID());
		assertEquals(map.slices[0][0].getID(), map.slices[0][1].getID());
		assertEquals(map.slices[1][0].getID(), map.slices[1][1].getID());
		String workerInstruction = (String) map.instruction.getParam(0);
		int firstLocalPosition = workerInstruction.indexOf("°" + map.slices[0][0].getID() + "·MATRIX");
		int secondLocalPosition = workerInstruction.indexOf("°" + map.slices[1][0].getID() + "·MATRIX");
		assertTrue(firstLocalPosition > 0);
		assertTrue(secondLocalPosition > firstLocalPosition);
		assertEquals(FType.ROW, ec.getMatrixObject("Z").getFedMapping().getType());
	}

	@Test
	public void zeroAndOneLocalMatrixKeepExistingExecutionPaths() {
		ExecutionContext none = context();
		BuiltinNaryFEDInstruction.parseInstruction(instruction("X", "X", "X", "Z"))
			.processInstruction(none);
		RecordingMap noBroadcast = (RecordingMap) none.getMatrixObject("X").getFedMapping();
		assertEquals(1, noBroadcast.directCalls);
		assertEquals(0, noBroadcast.singleSliceCalls);

		ExecutionContext one = context();
		BuiltinNaryFEDInstruction.parseInstruction(instruction("X", "A", "X", "Z"))
			.processInstruction(one);
		RecordingMap oneBroadcast = (RecordingMap) one.getMatrixObject("X").getFedMapping();
		assertEquals(0, oneBroadcast.directCalls);
		assertEquals(1, oneBroadcast.singleSliceCalls);
	}

	private static String instruction(String first, String second, String third, String output) {
		return "CP°n*°" + operand(first) + "°" + operand(second) + "°"
			+ operand(third) + "°" + operand(output);
	}

	private static String operand(String name) {
		return name + "·MATRIX·FP64";
	}

	private static ExecutionContext context() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject x = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(4, 2, 1024, 8)));
		x.setFedMapping(new RecordingMap(FType.ROW));
		ec.setVariable("X", x);
		for(String name : new String[] {"A", "B"}) {
			MatrixObject local = new MatrixObject(ValueType.FP64, name,
				new MetaData(new MatrixCharacteristics(4, 2, 1024, 8)));
			local.acquireModify(new MatrixBlock(4, 2, 1.0));
			local.release();
			ec.setVariable(name, local);
		}
		ec.setVariable("Z", new MatrixObject(ValueType.FP64, "Z",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024))));
		return ec;
	}

	private static List<Pair<FederatedRange, FederatedData>> ranges() {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(entry(0, 2, 18001));
		entries.add(entry(2, 4, 18002));
		return entries;
	}

	private static Pair<FederatedRange, FederatedData> entry(int begin, int end, int port) {
		return Pair.of(new FederatedRange(new long[] {begin, 0}, new long[] {end, 2}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", port), "dummy"));
	}
}
