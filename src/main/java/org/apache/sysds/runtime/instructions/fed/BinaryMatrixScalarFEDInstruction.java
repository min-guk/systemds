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

import java.util.concurrent.Future;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.BinaryMatrixScalarCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.spark.BinaryMatrixScalarSPInstruction;
import org.apache.sysds.runtime.matrix.operators.Operator;

public class BinaryMatrixScalarFEDInstruction extends BinaryFEDInstruction
{
	protected BinaryMatrixScalarFEDInstruction(Operator op,
		CPOperand in1, CPOperand in2, CPOperand out, String opcode, String istr, FederatedOutput fedOut) {
		super(FEDType.Binary, op, in1, in2, out, opcode, istr, fedOut);
	}

	public static BinaryMatrixScalarFEDInstruction parseInstruction(BinaryMatrixScalarCPInstruction instr) {
		return new BinaryMatrixScalarFEDInstruction(instr.getOperator(), instr.input1, instr.input2, instr.output,
			instr.getOpcode(), instr.getInstructionString(), FederatedOutput.NONE);
	}

	public static BinaryMatrixScalarFEDInstruction parseInstruction(BinaryMatrixScalarSPInstruction instr) {
		String instrStr = rewriteSparkInstructionToCP(instr.getInstructionString());
		String opcode = InstructionUtils.getInstructionPartsWithValueType(instrStr)[0];
		return new BinaryMatrixScalarFEDInstruction(instr.getOperator(), instr.input1, instr.input2, instr.output,
			opcode, instrStr, FederatedOutput.NONE);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		CPOperand matrix = input1.isMatrix() ? input1 : input2;
		CPOperand scalar = input2.isScalar() ? input2 : input1;
		MatrixObject mo = ec.getMatrixObject(matrix);

		if(mo.getFedMapping() == null)
			throw new DMLRuntimeException("FED matrix-scalar requires a planner-provided federated input; "
				+ "runtime CP fallback is forbidden. inst=" + instString);

		//prepare federated request matrix-scalar
		FederatedRequest fr1 = !scalar.isLiteral() ?
			mo.getFedMapping().broadcast(ec.getScalarInput(scalar)) : null;
		FederatedRequest fr2 = FederationUtils.callInstruction(instString, output,
			new CPOperand[]{matrix, (fr1 != null)?scalar:null},
			new long[]{mo.getFedMapping().getID(), (fr1 != null)?fr1.getID():-1}, true);
		
		//execute federated matrix-scalar operation and cleanups
		Future<FederatedResponse>[] ffr = null;
		if( fr1 != null ) {
			FederatedRequest fr3 = mo.getFedMapping().cleanup(getTID(), fr1.getID());
			ffr = mo.getFedMapping().execute(getTID(), true, fr1, fr2, fr3);
		}
		else {
			ffr = mo.getFedMapping().execute(getTID(), true, fr2);
		}

		long nnz = FederationUtils.sumNonZeros(ffr);
		// The serialized LOUT flag is planner authority, not a hint. Matrix-scalar
		// operations execute partition-wise, so forced-local output must retrieve and
		// bind those exact worker results before returning to the coordinator.
		if(_fedOut != null && _fedOut.isForcedLocal()) {
			FederationMap outMap = mo.getFedMapping();
			if(outMap.getSize() == 0)
				throw new DMLRuntimeException(
					"FED matrix-scalar cannot produce local output without a federated mapping");

			long outId = fr2.getID();
			FederatedRequest frG = new FederatedRequest(FederatedRequest.RequestType.GET_VAR, outId);
			FederatedRequest frC = outMap.cleanup(getTID(), outId);
			Future<FederatedResponse>[] ffrGet = outMap.execute(getTID(), frG, frC);
			org.apache.sysds.runtime.matrix.data.MatrixBlock ret;
			if(outMap.getType() == FType.BROADCAST)
				ret = FederationUtils.getResults(ffrGet)[0];
			else
				ret = FederationUtils.bind(ffrGet, outMap.getType() == FType.COL);
			ec.setMatrixOutput(output.getName(), ret);
			return;
		}

		//derive new fed mapping for output
		MatrixObject out = ec.getMatrixObject(output);
		out.getDataCharacteristics().set(mo.getDataCharacteristics())
			.setNonZeros(nnz);
		out.setFedMapping(mo.getFedMapping().copyWithNewID(fr2.getID()));
	}
}
