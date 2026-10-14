/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.lops;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.instructions.InstructionUtils;

/** Side-effecting marker for one exact selected native value definition. */
public final class MemoDefinition extends Lop {
	private final long _originValueId;

	public MemoDefinition(Lop input, long originValueId) {
		super(Type.MemoDefinition, input.getDataType(), input.getValueType());
		if(originValueId < 0)
			throw new LopsException("Memo definition requires a non-negative origin value id.");
		_originValueId = originValueId;
		addInput(input);
		input.addOutput(this);
		setLevel();
		lps.setProperties(inputs, ExecType.FED);
	}

	@Override
	public String getInstructions(String input, String output) {
		return InstructionUtils.concatOperands("FED", "memo_def", getInput(0).prepInputOperand(input),
			prepOutputOperand(output), Long.toString(_originValueId));
	}

	@Override
	public String toString() {
		return "MemoDefinition";
	}
}
