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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedLocalData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.meta.MetaData;
import org.junit.Test;

public class AggregateUnaryFEDInstructionNoFallbackTest {
	@Test
	public void federatedColumnAggregateDerivesUnknownOutputRangeFromRuntimeInput() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixBlock block = new MatrixBlock(2, 3, false);
		block.allocateDenseBlock();
		block.set(0, 0, 1);
		block.set(0, 1, 7);
		block.set(0, 2, 3);
		MatrixObject input = ExecutionContext.createMatrixObject(block);
		long inputId = FederationUtils.getNextFedDataID();
		input.setFedMapping(new FederationMap(inputId, List.of(Pair.of(
			new FederatedRange(new long[] {0, 0}, new long[] {2, 3}),
			new FederatedLocalData(inputId, input))), FType.FULL));
		MatrixObject output = new MatrixObject(ValueType.FP64, "Y",
			new MetaData(new MatrixCharacteristics(1, -1, 1024, -1)));
		ec.setVariable("X", input);
		ec.setVariable("Y", output);

		String instruction = InstructionUtils.concatOperands(
			"FED", "uacmax",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			"1", FederatedOutput.FOUT.name());
		AggregateUnaryFEDInstruction.parseInstruction(instruction).processInstruction(ec);

		assertTrue(output.isFederated(FType.FULL));
		assertEquals(1, output.getNumRows());
		assertEquals(3, output.getNumColumns());
		assertArrayEquals(new long[] {1, 3},
			output.getFedMapping().getFederatedRanges()[0].getEndDims());
	}

	@Test
	public void testLocalInputDoesNotExecuteCpAggregateInsideFedInstruction() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		MatrixObject input = new MatrixObject(ValueType.FP64, "X",
			new MetaData(new MatrixCharacteristics(2, 3, 1024, 6)));
		input.acquireModify(new MatrixBlock(2, 3, 1.0));
		input.release();
		ec.setVariable("X", input);

		String instruction = InstructionUtils.concatOperands(
			"FED", "uak+",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("sum", DataType.SCALAR.name(), ValueType.FP64.name()),
			"1", FederatedOutput.LOUT.name());

		assertThrows("A FED aggregate with a local input must fail instead of executing CP locally",
			DMLRuntimeException.class,
			() -> AggregateUnaryFEDInstruction.parseInstruction(instruction).processInstruction(ec));
	}
}
