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

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.spark.CumulativeOffsetSPInstruction;
import org.apache.sysds.runtime.matrix.operators.Operator;

/**
 * Federated replacement for Spark's block-offset cumulative instruction.
 *
 * Spark lowers a cumulative HOP into a partial aggregate followed by a
 * {@code bcumoff*} instruction because cumulative state has to cross Spark
 * blocks. A federated worker, however, executes its complete matrix partition
 * as a CP matrix. The Spark offset input and its Spark-only operands are
 * therefore neither required nor valid worker-CP operands. We translate the
 * instruction back to its unary cumulative operation and reuse the federated
 * unary implementation, which already reconciles state across row partitions.
 */
public class CumulativeOffsetFEDInstruction extends BinaryFEDInstruction {
	private CumulativeOffsetFEDInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out,
		String opcode, String istr) {
		super(FEDType.CumsumOffset, op, in1, in2, out, opcode, istr);
	}

	public static CumulativeOffsetFEDInstruction parseInstruction(CumulativeOffsetSPInstruction instr) {
		return new CumulativeOffsetFEDInstruction(instr.getOperator(), instr.input1, instr.input2, instr.output,
			instr.getOpcode(), instr.getInstructionString());
	}

	public static CumulativeOffsetFEDInstruction parseInstruction(String str) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		InstructionUtils.checkNumFields(parts, 5);
		return new CumulativeOffsetFEDInstruction(null, new CPOperand(parts[1]), new CPOperand(parts[2]),
			new CPOperand(parts[3]), parts[0], str);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		String unaryOpcode = getUnaryCumulativeOpcode(getOpcode());
		String unaryInstruction = InstructionUtils.concatOperands(
			ExecType.CP.name(), unaryOpcode, InstructionUtils.createOperand(input1),
			InstructionUtils.createOperand(output), "1", "false");
		UnaryMatrixFEDInstruction unary = UnaryMatrixFEDInstruction.parseInstruction(unaryInstruction);
		unary.setTID(getTID());
		unary.processInstruction(ec);
	}

	private static String getUnaryCumulativeOpcode(String opcode) {
		if(Opcodes.BCUMOFFKP.toString().equalsIgnoreCase(opcode))
			return Opcodes.UCUMKP.toString();
		if(Opcodes.BCUMOFFM.toString().equalsIgnoreCase(opcode))
			return Opcodes.UCUMM.toString();
		if(Opcodes.BCUMOFFPM.toString().equalsIgnoreCase(opcode))
			return Opcodes.UCUMKPM.toString();
		if(Opcodes.BCUMOFFMIN.toString().equalsIgnoreCase(opcode))
			return Opcodes.UCUMMIN.toString();
		if(Opcodes.BCUMOFFMAX.toString().equalsIgnoreCase(opcode))
			return Opcodes.UCUMMAX.toString();
		throw new DMLRuntimeException("Unsupported cumulative-offset opcode: " + opcode);
	}
}
