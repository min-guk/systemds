/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.lops;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.instructions.InstructionUtils;

/**
 * An optional placement site has no transfer semantics of its own. The exact
 * LOCAL/REFED lowering rewires its physical input before this alias executes.
 * Keeping an actual input edge lets the ordinary action receipt, privacy gate,
 * branch frequency and recompile authority cover the move without a new runtime
 * fallback. A FED alias copies the Data object, including its FederationMap.
 */
public final class PlacementAlias extends Lop {
	public PlacementAlias(Lop input, DataType dataType, ValueType valueType, ExecType execType) {
		super(Type.UnaryCP, dataType, valueType);
		addInput(input);
		input.addOutput(this);
		lps.setProperties(inputs, execType);
	}

	@Override
	public String getInstructions(String input, String output) {
		return InstructionUtils.concatOperands(ExecType.CP.name(), "cpvar", input, output);
	}

	@Override
	public String toString() {
		return "placement-alias";
	}
}
