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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.fedplanner.FTypes.AlignType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedResponse;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.controlprogram.federated.MatrixLineagePair;
import org.apache.sysds.runtime.functionobjects.Builtin;
import org.apache.sysds.runtime.functionobjects.Multiply;
import org.apache.sysds.runtime.functionobjects.Plus;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.BuiltinNaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.ScalarObject;
import org.apache.sysds.runtime.lineage.LineageItem;
import org.apache.sysds.runtime.lineage.LineageItemUtils;
import org.apache.sysds.runtime.lineage.LineageTraceable;
import org.apache.sysds.runtime.matrix.operators.Operator;
import org.apache.sysds.runtime.matrix.operators.SimpleOperator;

public class BuiltinNaryFEDInstruction extends FEDInstruction implements LineageTraceable {
	private final CPOperand output;
	private final CPOperand[] inputs;

	protected BuiltinNaryFEDInstruction(Operator op, CPOperand output, CPOperand[] inputs, String opcode,
		String istr, FederatedOutput fedOut) {
		super(FEDType.BuiltinNary, op, opcode, istr, fedOut);
		this.output = output;
		this.inputs = inputs;
	}

	public static BuiltinNaryFEDInstruction parseInstruction(BuiltinNaryCPInstruction inst, ExecutionContext ec) {
		if (inst == null || ec == null)
			return null;
		if (!isSupportedOpcode(inst.getOpcode()))
			return null;
		boolean hasFederated = false;
		for (CPOperand in : inst.getInputs()) {
			if (in != null && in.isMatrix() && ec.getMatrixObject(in).isFederatedExcept(FType.BROADCAST)) {
				hasFederated = true;
				break;
			}
		}
		if (!hasFederated)
			return null;
		String fedStr = InstructionUtils.concatOperands(inst.getInstructionString(), FederatedOutput.NONE.name());
		return parseInstruction(fedStr);
	}

	public static BuiltinNaryFEDInstruction parseInstruction(String str) {
		if (str == null || str.isEmpty())
			return null;
		if (str.startsWith(Types.ExecType.SPARK.name()))
			str = BinaryFEDInstruction.rewriteSparkInstructionToCP(str);

		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		if (!isSupportedOpcode(opcode))
			throw new DMLRuntimeException("Unsupported federated nary opcode: " + opcode);
		FederatedOutput fedOut = FederatedOutput.NONE;
		int end = parts.length;
		if (end > 0 && isFederatedOutput(parts[end - 1])) {
			fedOut = FederatedOutput.valueOf(parts[end - 1]);
			end -= 1;
		}
		CPOperand out = new CPOperand(parts[end - 1]);
		CPOperand[] in = new CPOperand[end - 2];
		for (int i = 1; i < end - 1; i++)
			in[i - 1] = new CPOperand(parts[i]);

		Operator op = parseOperator(opcode);
		return new BuiltinNaryFEDInstruction(op, out, in, opcode, str, fedOut);
	}

	private static Operator parseOperator(String opcode) {
		if (Opcodes.NP.toString().equals(opcode))
			return new SimpleOperator(Plus.getPlusFnObject());
		if (Opcodes.NM.toString().equals(opcode))
			return new SimpleOperator(Multiply.getMultiplyFnObject());
		if (Opcodes.NMIN.toString().equals(opcode) || Opcodes.NMAX.toString().equals(opcode))
			return new SimpleOperator(Builtin.getBuiltinFnObject(opcode.substring(1)));
		return null;
	}

	private static boolean isSupportedOpcode(String opcode) {
		return Opcodes.NP.toString().equals(opcode)
			|| Opcodes.NM.toString().equals(opcode)
			|| Opcodes.NMIN.toString().equals(opcode)
			|| Opcodes.NMAX.toString().equals(opcode);
	}

	private static boolean isFederatedOutput(String token) {
		for (FederatedOutput out : FederatedOutput.values()) {
			if (out.name().equals(token))
				return true;
		}
		return false;
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		if (output.getDataType() != DataType.MATRIX)
			throw new DMLRuntimeException("Federated nary ops currently support matrix outputs only.");

		MatrixLineagePair base = selectBaseFederatedInput(ec);
		List<CPOperand> matrixOps = new ArrayList<>();
		List<Long> matrixIds = new ArrayList<>();
		FederatedRequest[] broadcastSlices = null;

		for (int idx = 0; idx < inputs.length; idx++) {
			CPOperand in = inputs[idx];
			if (in == null)
				continue;
			if (in.isScalar()) {
				if (!in.isLiteral()) {
					ScalarObject scalar = ec.getScalarInput(in);
					String lit = InstructionUtils.createLiteralOperand(scalar.getStringValue(), scalar.getValueType());
					instString = InstructionUtils.replaceOperand(instString, idx + 2, lit);
				}
				continue;
			}
			if (!in.isMatrix())
				throw new DMLRuntimeException("Federated nary ops currently support matrix/scalar inputs only.");

			MatrixLineagePair mo = ec.getMatrixLineagePair(in);
			if (mo.isFederated()) {
				if (base.isFederatedExcept(FType.BROADCAST) && mo.isFederatedExcept(FType.BROADCAST)
					&& !isAligned(base, mo)) {
					throw new DMLRuntimeException("Federated nary ops require aligned federated inputs.");
				}
				matrixOps.add(in);
				matrixIds.add(mo.getFedMapping().getID());
			}
			else {
				if (broadcastSlices != null) {
					throw new DMLRuntimeException("Federated nary ops support at most one local matrix input.");
				}
				broadcastSlices = base.getFedMapping().broadcastSliced(mo, false);
				matrixOps.add(in);
				matrixIds.add(broadcastSlices[0].getID());
			}
		}

		if (matrixOps.isEmpty())
			throw new DMLRuntimeException("Federated nary ops require at least one matrix input.");

		CPOperand[] inOps = matrixOps.toArray(new CPOperand[0]);
		long[] inIds = new long[matrixIds.size()];
		for (int i = 0; i < matrixIds.size(); i++)
			inIds[i] = matrixIds.get(i);

		FederatedRequest fr = FederationUtils.callInstruction(instString, output, inOps, inIds, true);
		Future<FederatedResponse>[] ffr = (broadcastSlices == null)
			? base.getFedMapping().execute(getTID(), true, fr)
			: base.getFedMapping().execute(getTID(), true, broadcastSlices, fr);

		long nnz = FederationUtils.sumNonZeros(ffr);
		setOutputFedMapping(ec, base.getMO(), fr.getID(), nnz);
	}

	private static boolean isAligned(MatrixLineagePair base, MatrixLineagePair other) {
		if (base.isFederated(FType.ROW))
			return base.getFedMapping().isAligned(other.getFedMapping(), AlignType.ROW);
		if (base.isFederated(FType.COL))
			return base.getFedMapping().isAligned(other.getFedMapping(), AlignType.COL);
		if (base.isFederated(FType.FULL))
			return base.getFedMapping().isAligned(other.getFedMapping(), AlignType.FULL);
		return base.isFederated(FType.BROADCAST);
	}

	private MatrixLineagePair selectBaseFederatedInput(ExecutionContext ec) {
		MatrixLineagePair base = null;
		for (CPOperand in : inputs) {
			if (in != null && in.isMatrix()) {
				MatrixLineagePair mo = ec.getMatrixLineagePair(in);
				if (mo.isFederatedExcept(FType.BROADCAST)) {
					base = mo;
					break;
				}
			}
		}
		if (base == null) {
			for (CPOperand in : inputs) {
				if (in != null && in.isMatrix()) {
					MatrixLineagePair mo = ec.getMatrixLineagePair(in);
					if (mo.isFederated()) {
						base = mo;
						break;
					}
				}
			}
		}
		if (base == null)
			throw new DMLRuntimeException("Federated nary ops require at least one federated matrix input.");
		return base;
	}

	private void setOutputFedMapping(ExecutionContext ec, MatrixObject fedMapObj, long fedOutputID, long nnz) {
		MatrixObject out = ec.getMatrixObject(output);
		out.getDataCharacteristics().set(fedMapObj.getDataCharacteristics()).setNonZeros(nnz);
		out.setFedMapping(fedMapObj.getFedMapping().copyWithNewID(fedOutputID));
	}

	@Override
	public Pair<String, LineageItem> getLineageItem(ExecutionContext ec) {
		return Pair.of(output.getName(), new LineageItem(getOpcode(), LineageItemUtils.getLineage(ec, inputs)));
	}
}
