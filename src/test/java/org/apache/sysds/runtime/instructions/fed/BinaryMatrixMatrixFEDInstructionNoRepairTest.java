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
import static org.junit.Assert.assertThrows;

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
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class BinaryMatrixMatrixFEDInstructionNoRepairTest {
	private static class CountingFederationMap extends FederationMap {
		private int executeCalls;

		CountingFederationMap(long id, List<Pair<FederatedRange, FederatedData>> fedMap) {
			super(id, fedMap, FType.OTHER);
		}

		@Override
		@SuppressWarnings("unchecked")
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			executeCalls++;
			Future<FederatedResponse>[] responses = new Future[getSize()];
			for(int i = 0; i < responses.length; i++)
				responses[i] = CompletableFuture.completedFuture(
					new FederatedResponse(ResponseType.SUCCESS, Long.valueOf(0)));
			return responses;
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			return execute(tid, wait, requests);
		}
	}

	@Test
	public void testOtherInputIsNotReclassifiedInsideOperation() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = federatedOtherMatrix();
		CountingFederationMap map = (CountingFederationMap) input.getFedMapping();
		ec.setVariable("X", input);
		ec.setVariable("V", ExecutionContext.createMatrixObject(new MatrixBlock(1, 3, 1.0)));
		ec.setVariable("Y", new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(4, 3, 1024))));

		String instruction = InstructionUtils.concatOperands(
			"FED", "+",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("V", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			FederatedOutput.FOUT.name());

		assertThrows("A FED matrix-matrix instruction must reject an unsupported OTHER placement instead of "
			+ "reclassifying it during execution", DMLRuntimeException.class,
			() -> BinaryFEDInstruction.parseInstruction(instruction).processInstruction(ec));
		assertEquals("The runtime must not mutate the planner-supplied input placement", FType.OTHER, map.getType());
		assertEquals("Unsupported placement must fail before contacting workers", 0, map.executeCalls);
	}

	private static MatrixObject federatedOtherMatrix() {
		MatrixObject matrix = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(4, 3, 1024, -1)));
		List<Pair<FederatedRange, FederatedData>> entries = new ArrayList<>();
		entries.add(Pair.of(new FederatedRange(new long[] {0, 0}, new long[] {2, 3}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 18001), "dummy")));
		entries.add(Pair.of(new FederatedRange(new long[] {2, 0}, new long[] {4, 3}),
			new FederatedData(DataType.MATRIX, new InetSocketAddress("localhost", 18002), "dummy")));
		matrix.setFedMapping(new CountingFederationMap(71, entries));
		return matrix;
	}
}
