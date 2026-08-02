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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
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

	private static class ChannelCloseThenSuccessMap extends NoOpFederationMap {
		private int executeCalls;

		ChannelCloseThenSuccessMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap, FType type) {
			super(id, fedMap, type);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... fr) {
			executeCalls++;
			if(executeCalls == 1)
				throw new DMLRuntimeException("channel closed");
			return (Future<FederatedResponse>[]) new Future<?>[0];
		}
	}

	@Test
	public void testOtherSingleWorkerAnchorIsNotRepairedToFull() {
		FederationMap map = federationMap(FType.OTHER,
			new long[][][] {{{0, 0}, {100, 20}}});

		assertEquals(FType.OTHER, FEDLocalMaterializeUtil.declaredAnchorType(map));
		assertEquals(FType.OTHER, map.getType());
	}

	@Test
	public void testSingleWorkerFullMaterializationRetainsFullType() {
		assertEquals(FType.FULL,
			FEDLocalMaterializeUtil.normalizeReplicatedMapType(FType.FULL, FType.FULL, 1));
	}

	@Test
	public void testMultiWorkerFullMaterializationIsRejectedInsteadOfRelabelledBroadcast() {
		assertThrows(DMLRuntimeException.class,
			() -> FEDLocalMaterializeUtil.normalizeReplicatedMapType(FType.FULL, FType.FULL, 2));
		assertEquals(FType.BROADCAST,
			FEDLocalMaterializeUtil.normalizeReplicatedMapType(FType.FULL, FType.BROADCAST, 2));
	}

	@Test
	public void testMalformedMultiWorkerFullAnchorIsRejected() {
		FederationMap map = federationMap(FType.FULL,
			new long[][][] {{{0, 0}, {10, 2}}, {{0, 0}, {10, 2}}});

		assertThrows(DMLRuntimeException.class, () -> FEDLocalMaterializeUtil.declaredAnchorType(map));
	}

	@Test
	public void testExistingFederatedReuseRequiresExactTypeCardinalityAndRanges() {
		FederationMap singletonFull = federationMap(FType.FULL,
			new long[][][] {{{0, 0}, {10, 2}}});
		FederationMap singletonBroadcast = federationMap(FType.BROADCAST,
			new long[][][] {{{0, 0}, {10, 2}}});
		assertTrue(FEDLocalMaterializeUtil.matchesPlannedLayout(singletonFull, singletonFull,
			FType.FULL, FType.FULL, 10, 2));
		assertTrue(FEDLocalMaterializeUtil.matchesPlannedLayout(singletonFull, singletonBroadcast,
			FType.FULL, FType.BROADCAST, 10, 2));

		FederationMap replicated = federationMap(FType.BROADCAST,
			new long[][][] {{{0, 0}, {10, 2}}, {{0, 0}, {10, 2}}});
		FederationMap twoWorkerAnchor = federationMap(FType.BROADCAST,
			new long[][][] {{{0, 0}, {10, 2}}, {{0, 0}, {10, 2}}});
		assertFalse("A multi-worker broadcast cannot be relabelled FULL",
			FEDLocalMaterializeUtil.matchesPlannedLayout(replicated, twoWorkerAnchor,
				FType.FULL, FType.FULL, 10, 2));

		FederationMap rowAnchor = federatedAnchor(FType.ROW,
			new long[][][] {{{0, 0}, {3, 2}}, {{3, 0}, {10, 2}}}).getFedMapping();
		FederationMap wrongRowRanges = federationMap(FType.ROW,
			new long[][][] {{{0, 0}, {5, 2}}, {{5, 0}, {10, 2}}});
		assertFalse("Equal ROW labels do not prove exact anchor alignment",
			FEDLocalMaterializeUtil.matchesPlannedLayout(wrongRowRanges, rowAnchor,
				FType.ROW, FType.ROW, 10, 2));
		assertTrue(FEDLocalMaterializeUtil.matchesPlannedLayout(rowAnchor, rowAnchor,
			FType.ROW, FType.ROW, 10, 2));
	}

	@Test
	public void testUndersizedRowAndColPlacementsAreRejectedInsteadOfChangedToBroadcast() {
		FederationMap rowAnchor = federationMap(FType.ROW,
			new long[][][] {{{0, 0}, {1, 10}}, {{1, 0}, {2, 10}}});
		FederationMap colAnchor = federationMap(FType.COL,
			new long[][][] {{{0, 0}, {10, 1}}, {{0, 1}, {10, 2}}});
		MatrixObject oneByTen = ExecutionContext.createMatrixObject(new MatrixBlock(1, 10, 1.0));
		MatrixObject tenByOne = ExecutionContext.createMatrixObject(new MatrixBlock(10, 1, 1.0));

		assertThrows(DMLRuntimeException.class,
			() -> FEDLocalMaterializeUtil.materializeLocalToAnchor(
				1, oneByTen, rowAnchor, FType.ROW, FType.ROW, 1, 10));
		assertThrows(DMLRuntimeException.class,
			() -> FEDLocalMaterializeUtil.materializeLocalToAnchor(
				1, tenByOne, colAnchor, FType.COL, FType.COL, 10, 1));
	}

	@Test
	public void testMaterializationPreservesExactUnevenAnchorRangesForSameShape() {
		FederationMap rowAnchor = federatedAnchor(FType.ROW,
			new long[][][] {{{0, 0}, {3, 2}}, {{3, 0}, {10, 2}}}).getFedMapping();
		MatrixObject local = ExecutionContext.createMatrixObject(new MatrixBlock(10, 2, 1.0));

		FederationMap materialized = FEDLocalMaterializeUtil.materializeLocalToAnchor(
			1, local, rowAnchor, FType.ROW, FType.ROW, 10, 2);

		assertEquals(3, materialized.getFederatedRanges()[0].getEndDims()[0]);
		assertEquals(3, materialized.getFederatedRanges()[1].getBeginDims()[0]);
		assertTrue(FEDLocalMaterializeUtil.matchesPlannedLayout(materialized, rowAnchor,
			FType.ROW, FType.ROW, 10, 2));
	}

	@Test
	public void testOtherRowShapedAnchorIsNotRepairedToRow() {
		FederationMap map = federationMap(FType.OTHER,
			new long[][][] {{{0, 0}, {50, 20}}, {{50, 0}, {100, 20}}});

		assertEquals(FType.OTHER, FEDLocalMaterializeUtil.declaredAnchorType(map));
		assertEquals(FType.OTHER, map.getType());
	}

	@Test
	public void testNormalizeOtherIrregularAnchorRemainsOther() {
		FederationMap map = federationMap(FType.OTHER,
			new long[][][] {{{0, 0}, {50, 10}}, {{50, 10}, {100, 20}}});

		assertEquals(FType.OTHER, FEDLocalMaterializeUtil.declaredAnchorType(map));
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
	public void testContainsRejectsLocalTargetInsteadOfFallingBackToCp() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixBlock mb = new MatrixBlock(2, 2, false);
		mb.allocateDenseBlock();
		mb.set(0, 1, Double.POSITIVE_INFINITY);
		ec.setVariable("X", ExecutionContext.createMatrixObject(mb));

		String inst = InstructionUtils.concatOperands("FED", "contains",
			"pattern=Infinity", "target=X", "k=1",
			InstructionUtils.concatOperandParts("out", DataType.SCALAR.name(), "BOOLEAN"));
		assertThrows("FED contains must reject a local target instead of silently executing CP",
			RuntimeException.class,
			() -> ParameterizedBuiltinFEDInstruction.parseInstruction(inst).processInstruction(ec));
	}

	@Test
	public void testReorgLocalInputDoesNotExecuteCpOrUseUnrelatedAnchor() {
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
		assertThrows("FED reorg must reject a local input instead of stealing an unrelated worker pool",
			RuntimeException.class, () -> ReorgFEDInstruction.parseInstruction(inst).processInstruction(ec));
	}

	@Test
	public void testRefedDoesNotStealUnrelatedFederatedWorkerPoolWhenAnchorIsLocal() {
		String anchorName = "MissingSelectedAnchor_20260802";
		FederationUtils.removeAnchorMap(anchorName);
		FederationUtils.removeAnchorKey(anchorName);
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setAutoCreateVars(true);
		ec.setVariable("X", ExecutionContext.createMatrixObject(new MatrixBlock(2, 3, 1.0)));
		ec.setVariable(anchorName, ExecutionContext.createMatrixObject(new MatrixBlock(2, 3, 1.0)));
		ec.setVariable("UnrelatedPool", federatedAnchor(FType.ROW,
			new long[][][] {{{0, 0}, {10, 3}}, {{10, 0}, {20, 3}}}));
		ec.setVariable("Y", ExecutionContext.createMatrixObject(new MatrixBlock()));

		String inst = InstructionUtils.concatOperands("FED", "fed_refed",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts(anchorName, DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()));
		assertThrows("fed_refed must require its selected anchor instead of stealing an unrelated worker pool",
			RuntimeException.class, () -> FEDRefedInstruction.parseInstruction(inst).processInstruction(ec));
	}

	@Test
	public void testRefedDoesNotImplicitlyDownloadAndUploadIncompatibleFederatedInput() {
		FederationUtils.clearRefedReuseCache();
		try {
			ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
			ec.setAutoCreateVars(true);
			MatrixObject input = ExecutionContext.createMatrixObject(new MatrixBlock(2, 3, 1.0));
			input.setFileName("NoImplicitRefedFallbackInput_20260802");
			FederatedRange inputRange = new FederatedRange(new long[] {0, 0}, new long[] {2, 3});
			FederatedData inputData = new FederatedData(DataType.MATRIX,
				new InetSocketAddress("localhost", 16001), null);
			input.setFedMapping(new NoOpFederationMap(81,
				List.of(Pair.of(inputRange, inputData)), FType.FULL));
			ec.setVariable("IncompatibleFed", input);
			ec.setVariable("SelectedAnchor", federatedAnchor(FType.FULL,
				new long[][][] {{{0, 0}, {2, 3}}}));
			ec.setVariable("RefedOut", ExecutionContext.createMatrixObject(new MatrixBlock()));

			String inst = InstructionUtils.concatOperands("FED", "fed_refed",
				InstructionUtils.concatOperandParts("IncompatibleFed", DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts("SelectedAnchor", DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts("RefedOut", DataType.MATRIX.name(), ValueType.FP64.name()));
			assertThrows("An incompatible federated source must be lowered through an explicit FED->LOUT->FOUT "
				+ "plan, not repaired inside fed_refed", RuntimeException.class,
				() -> FEDRefedInstruction.parseInstruction(inst).processInstruction(ec));
		}
		finally {
			FederationUtils.clearRefedReuseCache();
		}
	}

	@Test
	public void testRefedHonorsExplicitBroadcastLayoutOverRowAnchor() {
		FederationUtils.clearRefedReuseCache();
		try {
			ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
			ec.setAutoCreateVars(true);
			MatrixObject local = ExecutionContext.createMatrixObject(new MatrixBlock(1, 10, 1.0));
			local.setFileName("ExplicitBroadcastRefedInput_20260802");
			ec.setVariable("LocalRowVector", local);
			ec.setVariable("SelectedRowAnchor", federatedAnchor(FType.ROW,
				new long[][][] {{{0, 0}, {5, 10}}, {{5, 0}, {10, 10}}}));
			ec.setVariable("BroadcastOut", ExecutionContext.createMatrixObject(new MatrixBlock()));

			String inst = InstructionUtils.concatOperands("FED", "fed_refed",
				InstructionUtils.concatOperandParts("LocalRowVector", DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts("SelectedRowAnchor", DataType.MATRIX.name(), ValueType.FP64.name()),
				InstructionUtils.concatOperandParts("BroadcastOut", DataType.MATRIX.name(), ValueType.FP64.name()),
				FType.BROADCAST.name());
			FEDRefedInstruction.parseInstruction(inst).processInstruction(ec);

			FederationMap out = ec.getMatrixObject("BroadcastOut").getFedMapping();
			assertEquals("The runtime must execute the planner-selected materialization layout",
				FType.BROADCAST, out.getType());
			assertEquals(2, out.getSize());
			for(FederatedRange range : out.getFederatedRanges()) {
				assertEquals(0, range.getBeginDims()[0]);
				assertEquals(0, range.getBeginDims()[1]);
				assertEquals(1, range.getEndDims()[0]);
				assertEquals(10, range.getEndDims()[1]);
			}
		}
		finally {
			FederationUtils.clearRefedReuseCache();
		}
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

	@Test
	public void testMaterializationDoesNotRetryAfterChannelClosure() {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {2, 3}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 15000), null)));
		ChannelCloseThenSuccessMap anchor = new ChannelCloseThenSuccessMap(1, entries, FType.FULL);
		MatrixObject local = ExecutionContext.createMatrixObject(new MatrixBlock(2, 3, 1.0));

		assertThrows("A failed planned upload must invalidate the run instead of silently retrying",
			DMLRuntimeException.class,
			() -> FEDLocalMaterializeUtil.materializeLocalToAnchor(
				1, local, anchor, FType.FULL, FType.FULL, 2, 3));
		assertEquals("A single experiment cell must issue the upload only once", 1, anchor.executeCalls);
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
