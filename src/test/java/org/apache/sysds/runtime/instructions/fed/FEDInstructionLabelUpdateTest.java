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
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.IntObject;
import org.junit.Test;

public class FEDInstructionLabelUpdateTest {
	@Test
	public void testPatchedScalarOperandsBecomeLiteralsBeforeFedReparse() {
		ExecutionContext ec = new ExecutionContext(new LocalVariableMap());
		ec.setVariable("rl", new IntObject(2));
		ec.setVariable("ru", new IntObject(3));
		ec.setVariable("cl", new IntObject(1));
		ec.setVariable("cu", new IntObject(4));

		String instruction = InstructionUtils.concatOperands(
			"FED", "rightIndex",
			InstructionUtils.concatOperandParts("X", DataType.MATRIX.name(), ValueType.FP64.name()),
			placeholderScalar("rl"), placeholderScalar("ru"),
			placeholderScalar("cl"), placeholderScalar("cu"),
			InstructionUtils.concatOperandParts("Y", DataType.MATRIX.name(), ValueType.FP64.name()),
			FEDInstruction.FederatedOutput.LOUT.name());

		Instruction updated = FEDInstructionParser.parseSingleInstruction(instruction).preprocessInstruction(ec);
		String updatedString = updated.getInstructionString();
		assertFalse(updatedString.contains(Lop.VARIABLE_NAME_PLACEHOLDER));
		assertTrue(updatedString.contains(InstructionUtils.concatOperandParts(
			"2", DataType.SCALAR.name(), ValueType.INT64.name(), "true")));
		assertTrue(updatedString.contains(InstructionUtils.concatOperandParts(
			"4", DataType.SCALAR.name(), ValueType.INT64.name(), "true")));
	}

	private static String placeholderScalar(String name) {
		return InstructionUtils.concatOperandParts(
			Lop.VARIABLE_NAME_PLACEHOLDER + name + Lop.VARIABLE_NAME_PLACEHOLDER,
			DataType.SCALAR.name(), ValueType.INT64.name(), "false");
	}
}
