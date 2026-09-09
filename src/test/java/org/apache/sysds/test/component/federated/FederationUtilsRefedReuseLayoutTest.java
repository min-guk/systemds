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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse.ResponseType;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FederationUtilsRefedReuseLayoutTest {
	@Before
	public void setUp() {
		FederationUtils.clearRefedReuseCache();
	}

	@After
	public void tearDown() {
		FederationUtils.clearRefedReuseCache();
	}

	@Test
	public void testRefedReuseSameLayoutDifferentMapIdHits() {
		FederationMap left = rowMap(1L, 0, 5, 5, 10);
		FederationMap right = rowMap(999L, 0, 5, 5, 10);

		String leftLayout = FederationUtils.deriveFedLayoutSignature(left);
		String rightLayout = FederationUtils.deriveFedLayoutSignature(right);
		assertEquals(leftLayout, rightLayout);
		assertNotEquals(left.getID(), right.getID());

		FederationUtils.putRefedReuseMap("_mVar311", 77L, 1L,
			10, 50, 500, leftLayout, FType.ROW, left);

		FederationMap cached = FederationUtils.getRefedReuseMap("_mVar311", 88L, 1L,
			10, 50, 500, rightLayout, FType.ROW);
		assertNotNull("same semantic layout should hit even if anchor map ids differ", cached);
		assertEquals(FType.ROW, cached.getType());
		assertEquals(2, cached.getSize());
	}

	@Test
	public void testRefedReuseDifferentLayoutMisses() {
		FederationMap left = rowMap(1L, 0, 5, 5, 10);
		FederationMap right = rowMap(2L, 0, 4, 4, 10);

		FederationUtils.putRefedReuseMap("_mVar311", 77L, 1L,
			10, 50, 500, FederationUtils.deriveFedLayoutSignature(left), FType.ROW, left);

		FederationMap cached = FederationUtils.getRefedReuseMap("_mVar311", 88L, 1L,
			10, 50, 500, FederationUtils.deriveFedLayoutSignature(right), FType.ROW);
		assertNull("different row ranges must miss", cached);
	}

	@Test
	public void testRefedReuseMutationMisses() {
		FederationMap layout = rowMap(1L, 0, 5, 5, 10);
		String layoutSig = FederationUtils.deriveFedLayoutSignature(layout);

		FederationUtils.putRefedReuseMap("_mVar311", 77L, 1L,
			10, 50, 500, layoutSig, FType.ROW, layout);

		FederationMap cached = FederationUtils.getRefedReuseMap("_mVar311", 88L, 2L,
			10, 50, 500, layoutSig, FType.ROW);
		assertNull("mutation changes must still invalidate reuse", cached);
	}

	@Test
	public void testBuildAnchorMapFromKeyPreservesRowRanges() {
		FederationMap original = rowMap(1L, 0, 5, 5, 10);
		String anchorKey = FederationUtils.deriveFedLayoutSignature(original);

		FederationMap rebuilt = FederationUtils.buildAnchorMapFromKey(anchorKey);
		assertNotNull(rebuilt);
		assertEquals(FType.ROW, rebuilt.getType());
		assertEquals(original.getSize(), rebuilt.getSize());

		FederatedRange[] origRanges = original.getFederatedRanges();
		FederatedRange[] rebuiltRanges = rebuilt.getFederatedRanges();
		for (int i = 0; i < origRanges.length; i++) {
			assertEquals(origRanges[i].getBeginDims()[0], rebuiltRanges[i].getBeginDims()[0]);
			assertEquals(origRanges[i].getEndDims()[0], rebuiltRanges[i].getEndDims()[0]);
			assertEquals(original.getFederatedData()[i].getAddress(), rebuilt.getFederatedData()[i].getAddress());
		}
	}

	@Test(expected = DMLRuntimeException.class)
	public void testWaitForPropagatesWorkerErrorResponse() {
		Future<FederatedResponse> failed = CompletableFuture.completedFuture(
			new FederatedResponse(ResponseType.ERROR,
				new DMLRuntimeException("worker execution failed")));
		FederationUtils.waitFor(List.of(failed));
	}

	@Test
	public void testSparkOriginWorkerInstructionDropsPlannerOutputFlag() {
		CPOperand in = new CPOperand("X", Types.ValueType.FP64, Types.DataType.MATRIX);
		CPOperand out = new CPOperand("Y", Types.ValueType.FP64, Types.DataType.MATRIX);
		String instruction = InstructionUtils.concatOperands(
			Types.ExecType.SPARK.name(), Opcodes.RIGHT_INDEX.toString(), InstructionUtils.createOperand(in),
			InstructionUtils.createLiteralOperand("1", Types.ValueType.INT64),
			InstructionUtils.createLiteralOperand("10", Types.ValueType.INT64),
			InstructionUtils.createLiteralOperand("1", Types.ValueType.INT64),
			InstructionUtils.createLiteralOperand("2", Types.ValueType.INT64),
			InstructionUtils.createOperand(out), "NONE");

		FederatedRequest arrayRequest = FederationUtils.callInstruction(new String[] {instruction}, out, 99,
			new CPOperand[] {in}, new long[] {7}, Types.ExecType.SPARK)[0];
		assertWorkerCPInstructionWithoutPlannerFlag((String) arrayRequest.getParam(0));

		FederatedRequest singleRequest = FederationUtils.callInstruction(instruction, out, 100,
			new CPOperand[] {in}, new long[] {8}, Types.ExecType.SPARK, false);
		assertWorkerCPInstructionWithoutPlannerFlag((String) singleRequest.getParam(0));
	}

	private static void assertWorkerCPInstructionWithoutPlannerFlag(String instruction) {
		assertEquals(Types.ExecType.CP, InstructionUtils.getExecType(instruction));
		assertEquals(7, InstructionUtils.getInstructionPartsWithValueType(instruction).length);
		assertFalse(instruction.endsWith(Lop.OPERAND_DELIMITOR + "NONE"));
	}

	private static FederationMap rowMap(long mapId, long r0, long r1, long r2, long r3) {
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(new ImmutablePair<>(
			new FederatedRange(new long[] {r0, 0}, new long[] {r1, 50}),
			new FederatedData(Types.DataType.MATRIX, new InetSocketAddress("localhost", 14000), "left")));
		entries.add(new ImmutablePair<>(
			new FederatedRange(new long[] {r2, 0}, new long[] {r3, 50}),
			new FederatedData(Types.DataType.MATRIX, new InetSocketAddress("localhost", 14001), "right")));
		return new FederationMap(mapId, entries, FType.ROW);
	}
}
