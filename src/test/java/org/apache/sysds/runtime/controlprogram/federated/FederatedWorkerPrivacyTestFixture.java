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

package org.apache.sysds.runtime.controlprogram.federated;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.AggregateUnaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.cp.ListObject;
import org.apache.sysds.runtime.instructions.cp.MatrixIndexingCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ParameterizedBuiltinCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ReorgCPInstruction;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;

/**
 * Test-only model of the removed experimental worker privacy prototype.
 * Labels deliberately live here instead of in production Data objects.
 */
final class FederatedWorkerPrivacyTestFixture {
	enum Label {
		PUBLIC,
		PRIVATE_AGGREGATE,
		PRIVATE,
		UNKNOWN
	}

	private final Map<Data, Label> labels = new IdentityHashMap<>();

	void label(Data data, Label label) {
		setRecursively(data, label);
	}

	Label labelOf(Data data) {
		if(data == null)
			return Label.UNKNOWN;
		Label result = labels.getOrDefault(data, Label.UNKNOWN);
		if(data instanceof ListObject)
			for(Data child : ((ListObject) data).getData())
				result = merge(result, labelOf(child));
		return result;
	}

	void validateRawRelease(Data data) {
		if(data == null || labelOf(data) != Label.PUBLIC)
			throw denied("raw release");
		if(data instanceof ListObject)
			for(Data child : ((ListObject) data).getData())
				validateRawRelease(child);
	}

	Label inferInstructionOutput(ExecutionContext ec, Instruction instruction) {
		validateInstruction(instruction);
		if(instruction.getClass() == AggregateUnaryCPInstruction.class) {
			AggregateUnaryCPInstruction aggregate = (AggregateUnaryCPInstruction) instruction;
			requireVariable(aggregate.input1);
			Label label = labelOf(ec, aggregate.input1);
			return "uacmax".equalsIgnoreCase(aggregate.getOpcode())
				|| "uacmean".equalsIgnoreCase(aggregate.getOpcode()) ? nonDeclassifiable(label) : label;
		}
		if(instruction.getClass() == ReorgCPInstruction.class) {
			ReorgCPInstruction reorg = (ReorgCPInstruction) instruction;
			requireVariable(reorg.input1);
			return labelOf(ec, reorg.input1);
		}
		if(instruction.getClass() == MatrixIndexingCPInstruction.class) {
			MatrixIndexingCPInstruction indexing = (MatrixIndexingCPInstruction) instruction;
			requireVariable(indexing.input1);
			Label label = labelOf(ec, indexing.input1);
			if(isLeftIndex(indexing)) {
				if(indexing.input2 == null)
					throw denied("missing left-index input");
				if(indexing.input2.getDataType() != DataType.SCALAR)
					requireVariable(indexing.input2);
				label = merge(label, labelOf(ec, indexing.input2));
			}
			for(CPOperand bound : Arrays.asList(indexing.getRowLower(), indexing.getRowUpper(),
				indexing.getColLower(), indexing.getColUpper()))
				label = merge(label, labelOf(ec, requireBound(bound)));
			return nonDeclassifiable(label);
		}
		if(instruction.getClass() == ParameterizedBuiltinCPInstruction.class) {
			ParameterizedBuiltinCPInstruction replacement = (ParameterizedBuiltinCPInstruction) instruction;
			String target = replacement.getParam("target");
			if(target == null || !ec.containsVariable(target) || !(ec.getVariable(target) instanceof MatrixObject))
				throw denied("missing replace target");
			return nonDeclassifiable(labelOf(ec.getVariable(target)));
		}
		VariableCPInstruction variable = (VariableCPInstruction) instruction;
		if(variable.isRemoveVariableNoFile())
			return Label.PUBLIC;
		CPOperand source = variable.getInput1();
		if(source == null)
			return Label.UNKNOWN;
		if(source.isLiteral()) {
			if(!variable.isAssignVariable() || source.getDataType() != DataType.SCALAR)
				throw denied("literal copy source");
			return Label.PUBLIC;
		}
		return labelOf(ec, source);
	}

	void protectInstructionAliases(ExecutionContext ec, Instruction instruction, Label label) {
		if(instruction.getClass() == VariableCPInstruction.class
			&& ((VariableCPInstruction) instruction).isRemoveVariableNoFile())
			return;
		String outputName = instruction.getOutputVariableName();
		if(outputName != null && ec.containsVariable(outputName))
			protectAliases(ec, ec.getVariable(outputName), label);
		if(instruction.getClass() == MatrixIndexingCPInstruction.class && isLeftIndex(instruction)) {
			MatrixIndexingCPInstruction indexing = (MatrixIndexingCPInstruction) instruction;
			if(ec.containsVariable(indexing.input1.getName())) {
				Data input = ec.getVariable(indexing.input1.getName());
				if(input instanceof MatrixObject && ((MatrixObject) input).getUpdateType().isInPlace())
					protectAliases(ec, input, label);
			}
		}
	}

	void applyInstructionOutput(ExecutionContext ec, Instruction instruction, Label executionLabel) {
		String outputName = instruction.getOutputVariableName();
		if(outputName == null || !ec.containsVariable(outputName))
			return;
		Data output = ec.getVariable(outputName);
		Label released = instruction.getClass() == AggregateUnaryCPInstruction.class
			&& "uak+".equalsIgnoreCase(instruction.getOpcode())
			&& executionLabel == Label.PRIVATE_AGGREGATE ? Label.PUBLIC : executionLabel;
		labels.put(output, labels.containsKey(output) ? merge(labelOf(output), released) : released);
	}

	private void validateInstruction(Instruction instruction) {
		if(instruction.getClass() == AggregateUnaryCPInstruction.class
			&& Arrays.asList("uak+", "uacmax", "uacmean").contains(instruction.getOpcode().toLowerCase()))
			return;
		if(instruction.getClass() == ReorgCPInstruction.class
			&& Arrays.asList("r'", "rev").contains(instruction.getOpcode().toLowerCase()))
			return;
		if(instruction.getClass() == MatrixIndexingCPInstruction.class
			&& (isLeftIndex(instruction) || "rightIndex".equalsIgnoreCase(instruction.getOpcode())))
			return;
		if(instruction.getClass() == ParameterizedBuiltinCPInstruction.class
			&& "replace".equalsIgnoreCase(instruction.getOpcode())) {
			ParameterizedBuiltinCPInstruction replacement = (ParameterizedBuiltinCPInstruction) instruction;
			if(replacement.getParameterMap().size() == 3 && numeric(replacement.getParam("pattern"))
				&& numeric(replacement.getParam("replacement")) && replacement.getParam("target") != null)
				return;
		}
		if(instruction.getClass() == VariableCPInstruction.class) {
			VariableCPInstruction variable = (VariableCPInstruction) instruction;
			if(variable.isRemoveVariableNoFile() || variable.isAssignOrCopyVariable()
				|| variable.isMoveVariable() && variable.getOutputVariableName() != null)
				return;
		}
		throw denied("unsupported instruction");
	}

	private Label labelOf(ExecutionContext ec, CPOperand operand) {
		if(operand == null)
			return Label.PUBLIC;
		if(ec.containsVariable(operand.getName()))
			return labelOf(ec.getVariable(operand.getName()));
		return operand.isLiteral() ? Label.PUBLIC : Label.UNKNOWN;
	}

	private void protectAliases(ExecutionContext ec, Data target, Label label) {
		for(Map.Entry<String, Data> entry : ec.getVariables().entrySet())
			if(entry.getValue() == target)
				setRecursively(entry.getValue(), merge(labelOf(entry.getValue()), label));
	}

	private void setRecursively(Data data, Label label) {
		if(data == null)
			return;
		labels.put(data, label);
		if(data instanceof ListObject)
			for(Data child : ((ListObject) data).getData())
				setRecursively(child, label);
	}

	private static CPOperand requireBound(CPOperand operand) {
		if(operand == null || !operand.isLiteral() && operand.getDataType() != DataType.SCALAR)
			throw denied("invalid index bound");
		return operand;
	}

	private static void requireVariable(CPOperand operand) {
		if(operand == null || operand.isLiteral())
			throw denied("literal or missing data operand");
	}

	private static boolean numeric(String value) {
		try {
			Double.parseDouble(value);
			return true;
		}
		catch(Exception ex) {
			return false;
		}
	}

	private static boolean isLeftIndex(Instruction instruction) {
		return "leftIndex".equalsIgnoreCase(instruction.getOpcode());
	}

	private static Label nonDeclassifiable(Label label) {
		return label == Label.PRIVATE_AGGREGATE ? Label.PRIVATE : label;
	}

	private static Label merge(Label left, Label right) {
		if(left == Label.PRIVATE || right == Label.PRIVATE)
			return Label.PRIVATE;
		if(left == Label.UNKNOWN || right == Label.UNKNOWN)
			return Label.UNKNOWN;
		if(left == Label.PRIVATE_AGGREGATE || right == Label.PRIVATE_AGGREGATE)
			return Label.PRIVATE_AGGREGATE;
		return Label.PUBLIC;
	}

	private static FederatedWorkerHandlerException denied(String reason) {
		return new FederatedWorkerHandlerException("Test-only worker privacy fixture denies " + reason + '.');
	}
}
