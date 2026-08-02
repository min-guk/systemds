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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.BooleanObject;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class ReshapeFEDInstructionNoFallbackTest {
	private static class MissingVariableFederationMap extends FederationMap {
		private int executeCalls;

		MissingVariableFederationMap(long id, List<Pair<FederatedRange, FederatedData>> map, FType type) {
			super(id, map, type);
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest... requests) {
			executeCalls++;
			throw new DMLRuntimeException("Unknown variable on federated worker");
		}

		@Override
		public Future<FederatedResponse>[] execute(long tid, boolean wait, FederatedRequest[] slices,
			FederatedRequest... requests) {
			executeCalls++;
			throw new DMLRuntimeException("Unknown variable on federated worker");
		}
	}

	@Test
	public void testLocalInputDoesNotExecuteCpReshapeInsideFedInstruction() {
		ExecutionContext ec = createExecutionContext(false);
		String instruction = createInstruction();

		assertThrows("A FED reshape with a local input must fail instead of executing CP locally",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(instruction).processInstruction(ec));
	}

	@Test
	public void testMissingRemoteVariableDoesNotFallBackToLocalReshape() {
		ExecutionContext ec = createExecutionContext(true);
		String instruction = createInstruction();

		assertThrows("A FED reshape with missing remote state must fail instead of executing CP locally",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(instruction).processInstruction(ec));
	}

	@Test
	public void testMissingDimensionDoesNotDefaultAndReachWorkers() {
		MissingVariableFederationMap map = missingVariableMap();
		ExecutionContext ec = createExecutionContext(map);
		ec.removeVariable("rows");

		assertThrows("A missing reshape dimension must fail before federated execution",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec));
		assertEquals("Missing dimensions must not be guessed from input metadata", 0, map.executeCalls);
	}

	@Test
	public void testMissingByRowDoesNotDefaultAndReachWorkers() {
		MissingVariableFederationMap map = missingVariableMap();
		ExecutionContext ec = createExecutionContext(map);
		ec.removeVariable("byrow");

		assertThrows("A missing reshape orientation must fail before federated execution",
			DMLRuntimeException.class,
			() -> ReshapeFEDInstruction.parseInstruction(createInstruction()).processInstruction(ec));
		assertEquals("Missing byrow must not silently default to true", 0, map.executeCalls);
	}

	private static ExecutionContext createExecutionContext(boolean federated) {
		return createExecutionContext(federated ? missingVariableMap() : null);
	}

	private static MissingVariableFederationMap missingVariableMap() {
		FederatedRange range = new FederatedRange(new long[] {0, 0}, new long[] {2, 3});
		FederatedData data = new FederatedData(DataType.MATRIX,
			new InetSocketAddress("localhost", 19999), "dummy");
		return new MissingVariableFederationMap(71, List.of(Pair.of(range, data)), FType.FULL);
	}

	private static ExecutionContext createExecutionContext(MissingVariableFederationMap map) {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(2, 3, 1024, 6)));
		input.acquireModify(new MatrixBlock(2, 3, 1.0));
		input.release();
		if(map != null)
			input.setFedMapping(map);

		ec.setVariable("X", input);
		ec.setVariable("rows", new IntObject(3));
		ec.setVariable("cols", new IntObject(2));
		ec.setVariable("dims", new IntObject(0));
		ec.setVariable("byrow", new BooleanObject(true));
		ec.setVariable("Y", new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(3, 2, 1024))));
		return ec;
	}

	private static String createInstruction() {
		return InstructionUtils.concatOperands(
			"FED", "rshape",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("rows", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("cols", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("dims", DataType.SCALAR.name(), ValueType.INT64.name()),
			InstructionUtils.concatOperandParts("byrow", DataType.SCALAR.name(), ValueType.BOOLEAN.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()));
	}
}
