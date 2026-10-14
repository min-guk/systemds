/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.runtime.instructions.fed;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.PlannedSupplyRuntime;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.Data;

/** Runtime marker emitted immediately after an exact selected native producer. */
public final class MemoDefinitionFEDInstruction extends FEDInstruction {
	private final CPOperand _input;
	private final CPOperand _output;
	private final long _originValueId;

	private MemoDefinitionFEDInstruction(CPOperand input, CPOperand output, long originValueId,
		String opcode, String instruction) {
		super(FEDType.Supply, null, opcode, instruction, FederatedOutput.NONE);
		_input = input;
		_output = output;
		_originValueId = originValueId;
	}

	public static MemoDefinitionFEDInstruction parseInstruction(String instruction) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(instruction);
		if(parts.length != 4)
			throw new DMLRuntimeException("Invalid number of operands in memo_def instruction: " + instruction);
		try {
			long origin = Long.parseLong(parts[3]);
			if(origin < 0)
				throw new NumberFormatException("negative origin");
			return new MemoDefinitionFEDInstruction(new CPOperand(parts[1]), new CPOperand(parts[2]),
				origin, parts[0], instruction);
		}
		catch(IllegalArgumentException ex) {
			throw new DMLRuntimeException("Invalid memo_def instruction: " + instruction, ex);
		}
	}

	@Override
	public String getOutputVariableName() {
		return _output.getName();
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		if((!_input.isMatrix() && !_input.isFrame()) || _input.getDataType() != _output.getDataType())
			throw new DMLRuntimeException("memo_def supports matching matrix or frame operands only.");
		CacheableData<?> input = ec.getCacheableData(_input);
		PlannedSupplyRuntime.markDefinition(ec, _originValueId, input);
		Data displaced = ec.removeVariable(_output.getName());
		if(displaced != null && displaced != input)
			ec.cleanupDataObject(displaced);
		ec.setVariable(_output.getName(), input);
	}
}
