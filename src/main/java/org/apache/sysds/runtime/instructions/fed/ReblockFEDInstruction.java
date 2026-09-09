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
import org.apache.sysds.common.Types;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.spark.ReblockSPInstruction;
import org.apache.sysds.runtime.matrix.operators.Operator;
import org.apache.sysds.runtime.meta.DataCharacteristics;

public class ReblockFEDInstruction extends UnaryFEDInstruction {
	private int blen;

	private ReblockFEDInstruction(Operator op, CPOperand in, CPOperand out, int blen, boolean emptyBlocks,
		String opcode, String instr) {
		// Reblock preserves the input FederationMap and always publishes a new
		// federated variable. Declare this inherent output explicitly so lowering
		// and runtime placement audits do not depend on NONE/runtime heuristics.
		super(FEDInstruction.FEDType.Reblock, op, in, out, opcode, instr, FederatedOutput.FOUT);
		this.blen = blen;
	}

	public static ReblockFEDInstruction parseInstruction(ReblockSPInstruction instr) {
		return new ReblockFEDInstruction(instr.getOperator(), instr.input1, instr.output, instr.getBlockLength(),
			instr.getOutputEmptyBlocks(), instr.getOpcode(), instr.getInstructionString());
	}

	public static ReblockFEDInstruction parseInstruction(String str) {
		String parts[] = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];

		if(!opcode.equals("rblk")) {
			throw new DMLRuntimeException("Incorrect opcode for ReblockFEDInstruction:" + opcode);
		}

		CPOperand in = new CPOperand(parts[1]);
		CPOperand out = new CPOperand(parts[2]);
		int blen=Integer.parseInt(parts[3]);
		boolean outputEmptyBlocks = Boolean.parseBoolean(parts[4]);

		Operator op = null; // no operator for ReblockFEDInstruction
		return new ReblockFEDInstruction(op, in, out, blen, outputEmptyBlocks, opcode, str);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		// A worker stores one logical partition as a CP MatrixBlock/FrameBlock.  Spark
		// block repartitioning therefore has no worker-local data transformation to
		// perform; only the coordinator-side blocksize metadata changes.  The old
		// implementation rewrote SPARK rblk to CP rblk, but CP has no rblk parser,
		// so the asynchronous worker request failed while a nonexistent output ID
		// was still published.  Create a distinct worker symbol with cpvar instead.
		CacheableData<?> obj = ec.getCacheableData(input1.getName());
		DataCharacteristics mc = ec.getDataCharacteristics(input1.getName());
		DataCharacteristics mcOut = ec.getDataCharacteristics(output.getName());
		mcOut.set(mc.getRows(), mc.getCols(), blen, mc.getNonZeros());
		String copyInstruction = InstructionUtils.concatOperands(
			Types.ExecType.CP.name(), Opcodes.CPVAR.toString(),
			InstructionUtils.createOperand(input1), InstructionUtils.createOperand(output));
		FederatedRequest copy = FederationUtils.callInstruction(copyInstruction, output,
			new CPOperand[] {input1}, new long[] {obj.getFedMapping().getID()});

		// sumNonZeros synchronously consumes the responses and propagates any worker
		// error instead of publishing an invalid mapping after an asynchronous fault.
		long nnz = FederationUtils.sumNonZeros(
			obj.getFedMapping().execute(getTID(), true, copy));
		CacheableData<?> out = ec.getCacheableData(output);
		out.setFedMapping(obj.getFedMapping().copyWithNewID(copy.getID()));
		out.getDataCharacteristics().set(mcOut).setNonZeros(nnz);
	}
}
