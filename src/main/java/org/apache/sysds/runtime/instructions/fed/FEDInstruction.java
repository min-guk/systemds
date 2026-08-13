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

import java.util.regex.Pattern;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.runtime.instructions.cp.CPInstruction;
import org.apache.sysds.runtime.instructions.FEDInstructionParser;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.matrix.operators.Operator;

public abstract class FEDInstruction extends Instruction {

	public enum FEDType {
		AggregateBinary,
		AggregateUnary,
		AggregateTernary,
		Append,
		Binary,
		BuiltinNary,
		Cast,
		CentralMoment,
		Checkpoint,
		Covariance,
		CSVReblock,
		Ctable,
		CumulativeAggregate,
		CumsumOffset,
		Init,
		MultiReturnParameterizedBuiltin,
		MMChain,
		MAPMM,
		MatrixIndexing,
		Ternary,
		Tsmm,
		ParameterizedBuiltin,
		Quaternary,
		QSort,
		QPick,
		Refed,
		Fout,
		Reblock,
		Reorg,
		Reshape,
		SpoofFused,
		Unary
	}
	
	public enum FederatedOutput {
		FOUT, // forced federated output 
		LOUT, // forced local output (consolidated in CP)
		NONE; // runtime heuristics
		public boolean isForcedFederated() {
			return this == FOUT;
		}
		public boolean isForcedLocal() {
			return this == LOUT;
		}
		public boolean isForced(){
			return this == FOUT || this == LOUT;
		}
	}

	protected final FEDType _fedType;
	protected long _tid = -1; //main
	protected FederatedOutput _fedOut = FederatedOutput.NONE;

	protected FEDInstruction(FEDType type, String opcode, String istr) {
		this(type, null, opcode, istr);
	}

	protected FEDInstruction(FEDType type, Operator op, String opcode, String istr) {
		this(type, op, opcode, istr, FederatedOutput.NONE);
	}

	protected FEDInstruction(FEDType type, Operator op, String opcode, String istr, FederatedOutput fedOut) {
		super(op);
		_fedType = type;
		instString = istr;
		instOpcode = opcode;
		_fedOut = fedOut;
	}

	@Override
	public IType getType() {
		return IType.FEDERATED;
	}

	public FEDType getFEDInstructionType() {
		return _fedType;
	}

	public FederatedOutput getFederatedOutput() {
		return _fedOut;
	}

	public long getTID() {
		return _tid;
	}

	public void setTID(long tid) {
		_tid = tid;
	}

	@Override
	public Instruction preprocessInstruction(ExecutionContext ec) {
		Instruction tmp = super.preprocessInstruction(ec);
		if (tmp.requiresLabelUpdate()) {
			Instruction original = tmp;
			String originalInst = tmp.toString();
			String updInst = CPInstruction.updateLabels(originalInst, ec.getVariables());
			updInst = markPatchedScalarOperandsAsLiterals(originalInst, updInst);
			tmp = FEDInstructionParser.parseSingleInstruction(updInst);
			tmp.setLocation(original);
			if (DMLScript.LINEAGE)
				ec.traceLineage(tmp);
		}
		return tmp;
	}

	/**
	 * FED scalar placeholders are replaced with their concrete value before the
	 * instruction is reparsed. The serialized literal bit must change with that
	 * replacement; otherwise the runtime looks up a variable named after the
	 * value and individual instructions are forced to guess or default it.
	 */
	static String markPatchedScalarOperandsAsLiterals(String originalInst, String updatedInst) {
		String operandDelimiter = Pattern.quote(Lop.OPERAND_DELIMITOR);
		String valueDelimiter = Pattern.quote(Lop.VALUETYPE_PREFIX);
		String[] originalParts = originalInst.split(operandDelimiter, -1);
		String[] updatedParts = updatedInst.split(operandDelimiter, -1);
		if(originalParts.length != updatedParts.length)
			throw new IllegalStateException("FED label update changed the instruction operand count: " + updatedInst);

		for(int i = 0; i < originalParts.length; i++) {
			if(!originalParts[i].contains(Lop.VARIABLE_NAME_PLACEHOLDER))
				continue;
			String[] fields = updatedParts[i].split(valueDelimiter, -1);
			if(fields.length == 4 && DataType.SCALAR.name().equals(fields[1])) {
				fields[3] = Boolean.TRUE.toString();
				updatedParts[i] = String.join(Lop.VALUETYPE_PREFIX, fields);
			}
		}
		return String.join(Lop.OPERAND_DELIMITOR, updatedParts);
	}
}
