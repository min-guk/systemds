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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.AggregateUnaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.ComputationCPInstruction;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.cp.Data.WorkerPrivacyLevel;
import org.apache.sysds.runtime.instructions.cp.ListObject;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.io.IOUtilFunctions;
import org.apache.sysds.runtime.meta.MetaDataAll;

/** Worker-side, fail-closed privacy labels and release checks. */
final class FederatedWorkerPrivacy {
	private FederatedWorkerPrivacy() {
		// utility class
	}

	static void attachReadLabel(Data data, String filename) {
		setRecursively(data, resolveReadLabel(filename));
	}

	static void attachReadLabel(Data data, WorkerPrivacyLevel label) {
		setRecursively(data, label);
	}

	static void quarantineReadAliases(ExecutionContext ec, String filename) {
		if(ec == null || filename == null)
			return;
		for(String variable : ec.getVariables().keySet()) {
			Data data = ec.getVariable(variable);
			if(data instanceof CacheableData && filename.equals(((CacheableData<?>) data).getFileName()))
				setRecursively(data, WorkerPrivacyLevel.UNKNOWN);
		}
	}

	static void markCoordinatorOwned(Data data) {
		if(data == null)
			return;
		if(data instanceof ListObject)
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy denies list ingress without trusted wire labels.");
		// Only classify an unlabeled coordinator value. An explicit protected or
		// UNKNOWN label must survive serialization/PUT and cannot be laundered.
		if(!data.hasWorkerPrivacyLevel())
			data.setWorkerPrivacyLevel(WorkerPrivacyLevel.PUBLIC);
	}

	static void validateRawRelease(Data data) {
		if(data == null)
			throw new FederatedWorkerHandlerException("Cannot release a null federated worker value.");
		validateRawReleaseRecursive(data);
	}

	private static void validateRawReleaseRecursive(Data data) {
		if(data.getWorkerPrivacyLevel() != WorkerPrivacyLevel.PUBLIC)
			throw new FederatedWorkerHandlerException("Federated worker privacy policy denies raw release (label="
				+ data.getWorkerPrivacyLevel() + ").");
		if(data instanceof ListObject)
			for(Data child : ((ListObject)data).getData())
				validateRawReleaseRecursive(child);
	}

	static void validateUdfInputs(FederatedUDF udf, Data[] inputs) {
		// A generic UDF receives the full execution context and can read variables
		// not declared by getInputIDs(). Only this exact metadata query is currently
		// side-effect-free and covered by a worker-owned release contract.
		if(udf == null || udf.getClass() != FederatedData.GetPrivacyConstraints.class)
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy denies an unsupported UDF.");
		if(inputs == null)
			return;
		for(Data input : inputs)
			validateRawRelease(input);
	}

	/** Infer a conservative output label before an instruction may consume/move its inputs. */
	static WorkerPrivacyLevel inferInstructionOutput(ExecutionContext ec, Instruction instruction) {
		validateInstructionInputs(instruction);
		WorkerPrivacyLevel label = WorkerPrivacyLevel.PUBLIC;
		if(isSupportedAggregate(instruction)) {
			ComputationCPInstruction cp = (ComputationCPInstruction) instruction;
			for(CPOperand input : cp.getInputs())
				if(input != null && input.isLiteral())
					throw new FederatedWorkerHandlerException(
						"Federated worker privacy policy denies a literal aggregate input.");
			label = mergeInputs(ec, Arrays.asList(cp.getInputs()));
			if(isFullAggregateRelease(cp) && label == WorkerPrivacyLevel.PRIVATE_AGGREGATE)
				label = WorkerPrivacyLevel.PUBLIC;
		}
		else if(isSupportedVariable(instruction)) {
			VariableCPInstruction variable = (VariableCPInstruction) instruction;
			CPOperand source = variable.getInput1();
			if(source == null)
				label = WorkerPrivacyLevel.UNKNOWN;
			else if(source.isLiteral()) {
				if(!variable.isAssignVariable() || source.getDataType() != DataType.SCALAR)
					throw new FederatedWorkerHandlerException(
						"Federated worker privacy policy denies a literal copy or move source.");
				label = WorkerPrivacyLevel.PUBLIC;
			}
			else
				label = labelOf(ec, source);
		}
		return label;
	}

	private static void validateInstructionInputs(Instruction instruction) {
		if(isSupportedInstruction(instruction))
			return;
		// Unknown instructions can acquire data or perform side effects not represented
		// by their declared operands, so existing PUBLIC state is not sufficient proof.
		throw new FederatedWorkerHandlerException(
			"Federated worker privacy policy denies an unsupported instruction.");
	}

	private static boolean isSupportedInstruction(Instruction instruction) {
		return isSupportedAggregate(instruction) || isSupportedVariable(instruction);
	}

	private static boolean isSupportedAggregate(Instruction instruction) {
		return instruction.getClass() == AggregateUnaryCPInstruction.class
			&& "uak+".equalsIgnoreCase(instruction.getOpcode());
	}

	private static boolean isSupportedVariable(Instruction instruction) {
		if(instruction.getClass() != VariableCPInstruction.class)
			return false;
		VariableCPInstruction variable = (VariableCPInstruction) instruction;
		return variable.isAssignOrCopyVariable()
			|| variable.isMoveVariable() && variable.getOutputVariableName() != null;
	}

	/** Attach the previously inferred label after successful instruction execution. */
	static void applyInstructionOutput(ExecutionContext ec, Instruction instruction, WorkerPrivacyLevel label) {
		String outputName = instruction.getOutputVariableName();
		if(outputName == null || !ec.containsVariable(outputName))
			return;
		Data output = ec.getVariable(outputName);
		output.setWorkerPrivacyLevel(label);
	}

	private static boolean isFullAggregateRelease(ComputationCPInstruction instruction) {
		return instruction instanceof AggregateUnaryCPInstruction
			&& "uak+".equalsIgnoreCase(instruction.getOpcode());
	}

	private static WorkerPrivacyLevel mergeInputs(ExecutionContext ec, List<CPOperand> inputs) {
		WorkerPrivacyLevel result = WorkerPrivacyLevel.PUBLIC;
		for(CPOperand input : inputs) {
			WorkerPrivacyLevel next = labelOf(ec, input);
			if(next == WorkerPrivacyLevel.PRIVATE)
				return next;
			if(next == WorkerPrivacyLevel.UNKNOWN)
				result = WorkerPrivacyLevel.UNKNOWN;
			else if(next == WorkerPrivacyLevel.PRIVATE_AGGREGATE && result == WorkerPrivacyLevel.PUBLIC)
				result = next;
		}
		return result;
	}

	private static WorkerPrivacyLevel labelOf(ExecutionContext ec, CPOperand operand) {
		if(operand == null)
			return WorkerPrivacyLevel.PUBLIC;
		if(ec.containsVariable(operand.getName())) {
			Data data = ec.getVariable(operand.getName());
			return data == null ? WorkerPrivacyLevel.UNKNOWN : effectiveLabel(data);
		}
		if(operand.isLiteral())
			return WorkerPrivacyLevel.PUBLIC;
		else
			return WorkerPrivacyLevel.UNKNOWN;
	}

	private static WorkerPrivacyLevel effectiveLabel(Data data) {
		WorkerPrivacyLevel result = data.getWorkerPrivacyLevel();
		if(data instanceof ListObject) {
			for(Data child : ((ListObject)data).getData())
				result = merge(result, effectiveLabel(child));
		}
		return result;
	}

	private static WorkerPrivacyLevel merge(WorkerPrivacyLevel left, WorkerPrivacyLevel right) {
		if(left == WorkerPrivacyLevel.PRIVATE || right == WorkerPrivacyLevel.PRIVATE)
			return WorkerPrivacyLevel.PRIVATE;
		if(left == WorkerPrivacyLevel.UNKNOWN || right == WorkerPrivacyLevel.UNKNOWN)
			return WorkerPrivacyLevel.UNKNOWN;
		if(left == WorkerPrivacyLevel.PRIVATE_AGGREGATE || right == WorkerPrivacyLevel.PRIVATE_AGGREGATE)
			return WorkerPrivacyLevel.PRIVATE_AGGREGATE;
		return WorkerPrivacyLevel.PUBLIC;
	}

	private static void setRecursively(Data data, WorkerPrivacyLevel label) {
		if(data == null)
			return;
		data.setWorkerPrivacyLevel(label);
		if(data instanceof ListObject)
			for(Data child : ((ListObject)data).getData())
				setRecursively(child, label);
	}

	static WorkerPrivacyLevel resolveReadLabel(String filename) {
		String metadataFile = DataExpression.getMTDFileName(filename);
		FileSystem fs = null;
		try {
			fs = IOUtilFunctions.getFileSystem(metadataFile);
			Path path = new Path(metadataFile);
			if(!fs.exists(path))
				throw new FederatedWorkerHandlerException("Privacy metadata does not exist for worker read.");
			try(BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(path)))) {
				MetaDataAll metadata = new MetaDataAll(reader);
				if(!metadata.mtdExists())
					throw new FederatedWorkerHandlerException("Could not parse privacy metadata for worker read.");
				String privacy = metadata.getPrivacyConstraints();
				if(privacy == null || privacy.trim().isEmpty())
					return WorkerPrivacyLevel.UNKNOWN;
				if("public".equals(privacy))
					return WorkerPrivacyLevel.PUBLIC;
				if("private-aggregate".equals(privacy))
					return WorkerPrivacyLevel.PRIVATE_AGGREGATE;
				if("private".equals(privacy))
					return WorkerPrivacyLevel.PRIVATE;
				return WorkerPrivacyLevel.UNKNOWN;
			}
		}
		catch(FederatedWorkerHandlerException ex) {
			throw ex;
		}
		catch(Exception ex) {
			throw new FederatedWorkerHandlerException("Cannot establish worker-owned privacy label.", ex);
		}
		finally {
			IOUtilFunctions.closeSilently(fs);
		}
	}
}
