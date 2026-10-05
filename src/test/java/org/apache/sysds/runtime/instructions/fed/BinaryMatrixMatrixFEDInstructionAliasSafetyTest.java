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

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.InstructionParser;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.BinaryMatrixMatrixCPInstruction;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;

public class BinaryMatrixMatrixFEDInstructionAliasSafetyTest {
	@Test
	public void cpToFedConversionRemovesOnlyInPlaceMarkerAndPreservesCanonicalInput() {
		String cpString = InstructionUtils.concatOperands("CP", "+", operand("A"), operand("B"), operand("C"),
			"1", "InPlace");
		BinaryMatrixMatrixCPInstruction cp =
			(BinaryMatrixMatrixCPInstruction) InstructionParser.parseSingleInstruction(cpString);
		assertTrue(cp.isInPlace());

		BinaryMatrixMatrixFEDInstruction fed = BinaryMatrixMatrixFEDInstruction.parseInstruction(cp);
		assertFalse(fed.getInstructionString().endsWith("InPlace"));
		assertEquals(cpString.substring(0, cpString.lastIndexOf(Lop.OPERAND_DELIMITOR)),
			fed.getInstructionString());

		ExecutionContext worker = new ExecutionContext(new LocalVariableMap());
		MatrixBlock canonical = new MatrixBlock(2, 1, false);
		canonical.set(0, 0, 2);
		canonical.set(1, 0, 3);
		worker.setVariable("A", ExecutionContext.createMatrixObject(canonical));
		worker.setVariable("B", ExecutionContext.createMatrixObject(new MatrixBlock(2, 1, 1)));
		worker.setVariable("C", ExecutionContext.createMatrixObject(new MatrixBlock()));
		InstructionParser.parseSingleInstruction(fed.getInstructionString()).processInstruction(worker);
		MatrixBlock unchanged = worker.getMatrixInput("A");
		assertEquals(2, unchanged.get(0, 0), 0);
		assertEquals(3, unchanged.get(1, 0), 0);
		worker.releaseMatrixInput("A");
	}

	private static String operand(String name) {
		return InstructionUtils.concatOperandParts(name, DataType.MATRIX.name(), ValueType.FP64.name());
	}
}
