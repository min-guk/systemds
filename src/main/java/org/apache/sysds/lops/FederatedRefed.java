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

package org.apache.sysds.lops;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.lops.LopsException;

public class FederatedRefed extends Lop {
	private final Lop _input;
	private final Lop _anchor;
	private final String _anchorKey;

	public FederatedRefed(Lop input, Lop anchor) {
		this(input, anchor, input.getDataType(), input.getValueType());
	}

	public FederatedRefed(Lop input, Lop anchor, DataType dataType, ValueType valueType) {
		super(Type.FederatedRefed, dataType, valueType);
		_input = input;
		_anchor = anchor;
		_anchorKey = null;
		addInput(input);
		input.addOutput(this);
		addInput(anchor);
		anchor.addOutput(this);
		setLevel();
		lps.setProperties(inputs, ExecType.FED);
	}

	public FederatedRefed(Lop input, String anchorKey) {
		this(input, anchorKey, input.getDataType(), input.getValueType());
	}

	public FederatedRefed(Lop input, String anchorKey, DataType dataType, ValueType valueType) {
		super(Type.FederatedRefed, dataType, valueType);
		_input = input;
		_anchor = null;
		_anchorKey = anchorKey;
		addInput(input);
		input.addOutput(this);
		setLevel();
		lps.setProperties(inputs, ExecType.FED);
	}

	@Override
	public String getInstructions(String input, String anchor, String output) {
		String anchorOperand = (_anchorKey != null)
			? InstructionUtils.createLiteralOperand(_anchorKey, ValueType.STRING)
			: _anchor.prepInputOperand(anchor);
		return InstructionUtils.concatOperands(
			"FED", "fed_refed",
			_input.prepInputOperand(input),
			anchorOperand,
			prepOutputOperand(output));
	}

	@Override
	public String getInstructions(String input, String output) {
		if (_anchorKey == null)
			throw new LopsException("FederatedRefed requires an anchor key when only one input is present.");
		return InstructionUtils.concatOperands(
			"FED", "fed_refed",
			_input.prepInputOperand(input),
			InstructionUtils.createLiteralOperand(_anchorKey, ValueType.STRING),
			prepOutputOperand(output));
	}

	@Override
	public String toString() {
		return "FedRefed";
	}
}
