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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

public class CtableFEDInstructionOutputContractTest {
	@Test
	public void testForcedFederatedRequiresSeparableRanges() {
		assertTrue(CtableFEDInstruction.resolveFederatedOutput(FederatedOutput.FOUT, true));
		assertThrows(DMLRuntimeException.class,
			() -> CtableFEDInstruction.resolveFederatedOutput(FederatedOutput.FOUT, false));
	}

	@Test
	public void testForcedLocalOverridesRuntimeFeasibility() {
		assertFalse(CtableFEDInstruction.resolveFederatedOutput(FederatedOutput.LOUT, true));
		assertFalse(CtableFEDInstruction.resolveFederatedOutput(FederatedOutput.LOUT, false));
	}

	@Test
	public void testUnspecifiedOutputUsesRuntimeFeasibility() {
		assertTrue(CtableFEDInstruction.resolveFederatedOutput(FederatedOutput.NONE, true));
		assertFalse(CtableFEDInstruction.resolveFederatedOutput(FederatedOutput.NONE, false));
	}

	@Test
	public void testWorkerInstructionUsesResolvedLiteralOutputDimensions() {
		String instruction = InstructionUtils.concatOperands(
			"FED", "ctable",
			InstructionUtils.concatOperandParts("A", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("B", DataType.MATRIX.name(), ValueType.FP64.name()),
			InstructionUtils.concatOperandParts("1", DataType.SCALAR.name(), ValueType.INT64.name(), "true"),
			"dynamicRows" + Instruction.LITERAL_PREFIX + "false",
			"dynamicCols" + Instruction.LITERAL_PREFIX + "false",
			InstructionUtils.concatOperandParts("C", DataType.MATRIX.name(), ValueType.FP64.name()),
			"false", "8", FederatedOutput.FOUT.name());

		String resolved = CtableFEDInstruction.withResolvedOutputDimensions(instruction, 17, 23);
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(resolved);

		assertEquals("17" + Instruction.LITERAL_PREFIX + "true", parts[4]);
		assertEquals("23" + Instruction.LITERAL_PREFIX + "true", parts[5]);
	}

	@Test
	public void testFoutCroppingUsesExplicitUnevenRowRanges() {
		MatrixBlock globalPartial = new MatrixBlock(5, 2, false);
		for(int row = 0; row < 5; row++) {
			globalPartial.set(row, 0, row + 1);
			globalPartial.set(row, 1, 10 + row);
		}

		MatrixBlock first = CtableFEDInstruction.sliceOutputBlock(globalPartial, 2, 0, 3, true);
		MatrixBlock second = CtableFEDInstruction.sliceOutputBlock(globalPartial, 2, 3, 5, true);

		assertEquals(3, first.getNumRows());
		assertEquals(2, second.getNumRows());
		assertEquals(1D, first.get(0, 0), 0D);
		assertEquals(3D, first.get(2, 0), 0D);
		assertEquals(4D, second.get(0, 0), 0D);
		assertEquals(5D, second.get(1, 0), 0D);
	}

	@Test
	public void testFoutCroppingUsesExplicitUnevenColumnRanges() {
		MatrixBlock globalPartial = new MatrixBlock(2, 5, false);
		for(int col = 0; col < 5; col++) {
			globalPartial.set(0, col, col + 1);
			globalPartial.set(1, col, 10 + col);
		}

		MatrixBlock first = CtableFEDInstruction.sliceOutputBlock(globalPartial, 2, 0, 3, false);
		MatrixBlock second = CtableFEDInstruction.sliceOutputBlock(globalPartial, 2, 3, 5, false);

		assertEquals(3, first.getNumColumns());
		assertEquals(2, second.getNumColumns());
		assertEquals(1D, first.get(0, 0), 0D);
		assertEquals(3D, first.get(0, 2), 0D);
		assertEquals(4D, second.get(0, 0), 0D);
		assertEquals(5D, second.get(0, 1), 0D);
	}
}
