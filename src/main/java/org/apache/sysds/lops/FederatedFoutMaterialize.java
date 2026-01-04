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

public class FederatedFoutMaterialize extends Lop {
	private final Lop _input;
	private final Lop _anchor;
	private final String _fTypeHint;

	public FederatedFoutMaterialize(Lop input, Lop anchor, String fTypeHint) {
		this(input, anchor, input.getDataType(), input.getValueType(), fTypeHint);
	}

	public FederatedFoutMaterialize(Lop input, Lop anchor, DataType dataType, ValueType valueType, String fTypeHint) {
		super(Type.FederatedFoutMaterialize, dataType, valueType);
		_input = input;
		_anchor = anchor;
		_fTypeHint = fTypeHint;
		addInput(input);
		input.addOutput(this);
		addInput(anchor);
		anchor.addOutput(this);
		setLevel();
		lps.setProperties(inputs, ExecType.FED);
	}

	@Override
	public String getInstructions(String input, String anchor, String output) {
		return InstructionUtils.concatOperands(
			"FED", "fed_fout",
			_input.prepInputOperand(input),
			_anchor.prepInputOperand(anchor),
			prepOutputOperand(output),
			_fTypeHint);
	}

	@Override
	public String toString() {
		return "FedFoutMaterialize";
	}
}
