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
import static org.junit.Assert.assertTrue;

import org.apache.sysds.runtime.instructions.cp.BooleanObject;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

public class FEDLocalMaterializeUtilTest {
	private static class NoOpFederationMap extends FederationMap {
		public NoOpFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap, FType type) {
			super(id, fedMap, type);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... fr) {
			return (Future<FederatedResponse>[]) new Future<?>[0];
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] frSlices, FederatedRequest... fr) {
			return (Future<FederatedResponse>[]) new Future<?>[0];
		}
	}

	@Test
	public void testNormalizeOtherSingleWorkerAnchorToFull() {
		FederationMap map = federationMap(FType.OTHER,
			new long[][][] {{{0, 0}, {100, 20}}});

		assertEquals(FType.FULL, FEDLocalMaterializeUtil.normalizeSupportedAnchorType(map));
		assertEquals(FType.FULL, map.getType());
	}

	@Test
	public void testNormalizeOtherRowPartitionedAnchor() {
		FederationMap map = federationMap(FType.OTHER,
			new long[][][] {{{0, 0}, {50, 20}}, {{50, 0}, {100, 20}}});

		assertEquals(FType.ROW, FEDLocalMaterializeUtil.normalizeSupportedAnchorType(map));
		assertEquals(FType.ROW, map.getType());
	}

	@Test
	public void testNormalizeOtherIrregularAnchorRemainsOther() {
		FederationMap map = federationMap(FType.OTHER,
			new long[][][] {{{0, 0}, {50, 10}}, {{50, 10}, {100, 20}}});

		assertEquals(FType.OTHER, FEDLocalMaterializeUtil.normalizeSupportedAnchorType(map));
		assertEquals(FType.OTHER, map.getType());
	}

	@Test
	public void testSingleWorkerBroadcastSlicedUsesFullBroadcast() {
		FederationMap map = federationMap(FType.COL,
			new long[][][] {{{0, 0}, {50000, 2100}}});
		MatrixObject local = ExecutionContext.createMatrixObject(new MatrixBlock(1, 2, 1.0));

		FederatedRequest[] requests = map.broadcastSliced(local, true);

		assertEquals(1, requests.length);
		assertEquals(FederatedRequest.RequestType.PUT_VAR, requests[0].getType());
	}

	@Test
	public void testSingletonBroadcastSlicedReplicatesAcrossRanges() {
		FederationMap map = federationMap(FType.COL,
			new long[][][] {{{0, 0}, {50000, 1}}, {{0, 1}, {50000, 2}}});
		MatrixObject local = ExecutionContext.createMatrixObject(new MatrixBlock(1, 2, 1.0));

		FederatedRequest[] requests = map.broadcastSliced(local, true);

		assertEquals(2, requests.length);
		assertEquals(FederatedRequest.RequestType.PUT_VAR, requests[0].getType());
		assertEquals(requests[0].getID(), requests[1].getID());
	}

	@Test
	public void testContainsFallsBackToCpWhenTargetMaterializedLocal() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixBlock mb = new MatrixBlock(2, 2, false);
		mb.allocateDenseBlock();
		mb.set(0, 1, Double.POSITIVE_INFINITY);
		ec.setVariable("X", ExecutionContext.createMatrixObject(mb));

		String inst = InstructionUtils.concatOperands("FED", "contains",
			"pattern=Infinity", "target=X", "k=1",
			InstructionUtils.concatOperandParts("out", DataType.SCALAR.name(), "BOOLEAN"));
		ParameterizedBuiltinFEDInstruction.parseInstruction(inst).processInstruction(ec);

		assertTrue(((BooleanObject) ec.getVariable("out")).getBooleanValue());
	}

	@Test
	public void testReorgLocalInputFoutFallsBackToCpThenUploadsToUniqueAnchor() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setAutoCreateVars(true);
		MatrixBlock mb = new MatrixBlock(2, 3, false);
		mb.allocateDenseBlock();
		mb.set(0, 1, 7.0);
		ec.setVariable("X", ExecutionContext.createMatrixObject(mb));
		ec.setVariable("A", federatedAnchor(FType.ROW,
			new long[][][] {{{0, 0}, {10, 3}}, {{10, 0}, {20, 3}}}));

		String inst = InstructionUtils.concatOperands("FED", "r'",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", "FOUT");
		ReorgFEDInstruction.parseInstruction(inst).processInstruction(ec);

		MatrixObject out = ec.getMatrixObject("Y");
		assertTrue(out.isFederated());
		assertEquals(FType.BROADCAST, out.getFedMapping().getType());
		assertEquals(2, out.getFedMapping().getSize());
		assertEquals(3, out.getNumRows());
		assertEquals(2, out.getNumColumns());
	}

	@Test
	public void testDetectsMixedLocalFederatedData() {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		MatrixObject local = ExecutionContext.createMatrixObject(new MatrixBlock(10, 1, 1.0));
		entries.add(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {10, 1}),
			new FederatedLocalData(7, local)));
		entries.add(Pair.of(new FederatedRange(new long[] {10, 0}, new long[] {20, 1}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 15001), null)));

		assertTrue(FEDLocalMaterializeUtil.hasLocalFederatedData(new FederationMap(1, entries, FType.ROW)));
		assertFalse(FEDLocalMaterializeUtil.hasLocalFederatedData(federationMap(FType.ROW,
			new long[][][] {{{0, 0}, {10, 1}}, {{10, 0}, {20, 1}}})));
	}

	private static FederationMap federationMap(FType type, long[][][] ranges) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for (int i = 0; i < ranges.length; i++) {
			FederatedRange range = new FederatedRange(ranges[i][0], ranges[i][1]);
			FederatedData data = new FederatedData(DataType.MATRIX,
				new InetSocketAddress("localhost", 15000 + i), null);
			entries.add(Pair.of(range, data));
		}
		return new FederationMap(1, entries, type);
	}

	private static MatrixObject federatedAnchor(FType type, long[][][] ranges) {
		MatrixObject anchor = ExecutionContext.createMatrixObject(new MatrixBlock(1, 1, 1.0));
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		for (int i = 0; i < ranges.length; i++) {
			FederatedRange range = new FederatedRange(ranges[i][0], ranges[i][1]);
			FederatedData data = new FederatedData(DataType.MATRIX,
				new InetSocketAddress("localhost", 15000 + i), null);
			entries.add(Pair.of(range, data));
		}
		anchor.setFedMapping(new NoOpFederationMap(1, entries, type));
		return anchor;
	}
}
