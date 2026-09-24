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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
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
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.cp.DoubleObject;
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
			this(type, 0);
		}

		RecordingMap(FType type, int portOffset) {
			this(type, portOffset, 0, false);
		}

		RecordingMap(FType type, int portOffset, int rowOffset, boolean singlePartition) {
			super(71 + portOffset, ranges(portOffset, rowOffset, singlePartition), type);
		}

		RecordingMap(FType type, List<Pair<FederatedRange, FederatedData>> entries) {
			super(71, entries, type);
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

	@Test
	public void unalignedFailureReportsPlacementWithoutMatrixValues() {
		ExecutionContext ec = context();
		MatrixObject y = new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(4, 2, 1024, 8)));
		y.setFedMapping(new RecordingMap(FType.ROW, 100));
		ec.setVariable("Y", y);

		try {
			BuiltinNaryFEDInstruction.parseInstruction(instruction("X", "Y", "X", "Z"))
				.processInstruction(ec);
			fail("Expected unaligned federated inputs to fail");
		}
		catch(DMLRuntimeException ex) {
			assertTrue(ex.getMessage().contains("base={type=ROW,dims=4x2,ranges="));
			assertTrue(ex.getMessage().contains("input=Y {type=ROW,dims=4x2,ranges="));
			assertTrue(ex.getMessage().contains("localhost/127.0.0.1:18001"));
			assertTrue(ex.getMessage().contains("localhost/127.0.0.1:18101"));
		}
	}

	@Test
	public void singleFullRangeMismatchStillFailsClosed() {
		ExecutionContext ec = singleFullContext("X", 0);
		ec.setVariable("Y", singleFullMatrix("Y", 1));
		ec.setVariable("Z", new MatrixObject(ValueType.FP64, "Z",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024))));

		try {
			BuiltinNaryFEDInstruction.parseInstruction(instruction("X", "Y", "X", "Z"))
				.processInstruction(ec);
			fail("Expected mismatched FULL ranges to fail");
		}
		catch(DMLRuntimeException ex) {
			assertTrue(ex.getMessage().contains("base={type=FULL,dims=4x2,ranges="));
			assertTrue(ex.getMessage().contains("input=Y {type=FULL,dims=4x2,ranges="));
		}
	}

	@Test
	public void runtimeAnchorLiteralIpAlignsWithNativeFedInitForNary() throws Exception {
		String host = "130.149.237.12";
		int port = 8001;
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {50_000, 1});
		InetSocketAddress nativeAddress = new InetSocketAddress(InetAddress.getByName(host), port);
		RecordingMap nativeMap = new RecordingMap(FType.FULL, List.of(Pair.of(range,
			new FederatedData(DataType.MATRIX, nativeAddress, "native"))));
		FederationMap anchorMap = FederationUtils.buildAnchorMapFromKey(
			host + ':' + port + ";|0,0,50000,1;|FULL");

		assertNotNull(anchorMap);
		assertEquals(nativeAddress, anchorMap.getFederatedData()[0].getAddress());
		ExecutionContext ec = fullContext(nativeMap, anchorMap);
		ec.setVariable("S", new DoubleObject(2.5));
		BuiltinNaryFEDInstruction.parseInstruction(instructionWithScalar("X", "Y", "S", "Z"))
			.processInstruction(ec);

		assertEquals(1, nativeMap.directCalls);
		String workerInstruction = (String) nativeMap.instruction.getParam(0);
		assertTrue(workerInstruction.contains("2.5·SCALAR·FP64·true"));
		assertFalse(workerInstruction.contains("°S·SCALAR·FP64"));
		assertEquals(FType.FULL, ec.getMatrixObject("Z").getFedMapping().getType());
		assertEquals(50_000, ec.getMatrixObject("Z").getNumRows());
		assertEquals(1, ec.getMatrixObject("Z").getNumColumns());
	}

	@Test
	public void runtimeAnchorDifferentIpPortOrRangeStillFailsClosed() throws Exception {
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {50_000, 1});
		for(String anchorKey : List.of(
			"130.149.237.13:8001;|0,0,50000,1;|FULL",
			"130.149.237.12:8002;|0,0,50000,1;|FULL",
			"130.149.237.12:8001;|1,0,50001,1;|FULL")) {
			RecordingMap nativeMap = new RecordingMap(FType.FULL, List.of(Pair.of(range,
				new FederatedData(DataType.MATRIX,
					new InetSocketAddress(InetAddress.getByName("130.149.237.12"), 8001), "native"))));
			FederationMap anchorMap = FederationUtils.buildAnchorMapFromKey(anchorKey);
			try {
				BuiltinNaryFEDInstruction.parseInstruction(instruction("X", "Y", "X", "Z"))
					.processInstruction(fullContext(nativeMap, anchorMap));
				fail("Expected a different worker IP, port, or range to remain unaligned: " + anchorKey);
			}
			catch(DMLRuntimeException ex) {
				assertTrue(ex.getMessage().contains("Federated nary ops require aligned federated inputs"));
			}
		}
	}

	private static String instruction(String first, String second, String third, String output) {
		return "CP°n*°" + operand(first) + "°" + operand(second) + "°"
			+ operand(third) + "°" + operand(output);
	}

	private static String instructionWithScalar(String first, String second, String scalar, String output) {
		return "CP°n*°" + operand(first) + "°" + operand(second) + "°"
			+ scalar + "·SCALAR·FP64°" + operand(output);
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

	private static ExecutionContext singleFullContext(String name, int rowOffset) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable(name, singleFullMatrix(name, rowOffset));
		return ec;
	}

	private static MatrixObject singleFullMatrix(String name, int rowOffset) {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, name,
			new MetaData(new MatrixCharacteristics(4, 2, 1024, 8)));
		matrix.setFedMapping(new RecordingMap(FType.FULL, 0, rowOffset, true));
		return matrix;
	}

	private static ExecutionContext fullContext(FederationMap xMap, FederationMap yMap) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		for(Pair<String, FederationMap> input : List.of(Pair.of("X", xMap), Pair.of("Y", yMap))) {
			MatrixObject matrix = new MatrixObject(ValueType.FP64, input.getLeft(),
				new MetaData(new MatrixCharacteristics(50_000, 1, 1024, 50_000)));
			matrix.setFedMapping(input.getRight());
			ec.setVariable(input.getLeft(), matrix);
		}
		ec.setVariable("Z", new MatrixObject(ValueType.FP64, "Z",
			new MetaData(new MatrixCharacteristics(-1, -1, 1024))));
		return ec;
	}

	private static List<Pair<FederatedRange, FederatedData>> ranges(int portOffset, int rowOffset,
		boolean singlePartition) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		if(singlePartition)
			entries.add(entry(rowOffset, rowOffset + 4, 18001 + portOffset));
		else {
			entries.add(entry(rowOffset, rowOffset + 2, 18001 + portOffset));
			entries.add(entry(rowOffset + 2, rowOffset + 4, 18002 + portOffset));
		}
		return entries;
	}

	private static Pair<FederatedRange, FederatedData> entry(int begin, int end, int port) {
		return Pair.of(new FederatedRange(new long[] {begin, 0}, new long[] {end, 2}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", port), "dummy"));
	}
}
