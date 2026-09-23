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
import java.util.Map;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.cp.AggregateUnaryCPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.cp.Data.WorkerPrivacyLevel;
import org.apache.sysds.runtime.instructions.cp.ListObject;
import org.apache.sysds.runtime.instructions.cp.MatrixIndexingCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ParameterizedBuiltinCPInstruction;
import org.apache.sysds.runtime.instructions.cp.ReorgCPInstruction;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstructionUtils;
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
		validateInstruction(instruction);
		if(instruction.getClass() == AggregateUnaryCPInstruction.class) {
			AggregateUnaryCPInstruction aggregate = (AggregateUnaryCPInstruction) instruction;
			requireVariableDataOperand(aggregate.input1, "aggregate input");
			// Execution and release are separate policy decisions. Keep the source
			// label during execution and only declassify an explicitly contracted
			// aggregate after successful completion.
			WorkerPrivacyLevel label = labelOf(ec, aggregate.input1);
			// uacmax/uacmean are partial column-wise reductions. Their results are not the
			// original full aggregate authorized for PRIVATE_AGGREGATE, so a later
			// uak+ must not declassify it.
			return ("uacmax".equalsIgnoreCase(aggregate.getOpcode()) ||
				"uacmean".equalsIgnoreCase(aggregate.getOpcode()))
				? makeNonDeclassifiable(label) : label;
		}
		else if(instruction.getClass() == ReorgCPInstruction.class) {
			ReorgCPInstruction reorg = (ReorgCPInstruction) instruction;
			requireVariableDataOperand(reorg.input1, "reorg input");
			return labelOf(ec, reorg.input1);
		}
		else if(instruction.getClass() == MatrixIndexingCPInstruction.class) {
			MatrixIndexingCPInstruction indexing = (MatrixIndexingCPInstruction) instruction;
			requireVariableDataOperand(indexing.input1, "indexing input");
			WorkerPrivacyLevel label = labelOf(ec, indexing.input1);
			if(isLeftIndex(indexing)) {
				if(indexing.input2 == null)
					throw new FederatedWorkerHandlerException(
						"Federated worker privacy policy requires a left-index value.");
				if(indexing.input2.getDataType() != DataType.SCALAR)
					requireVariableDataOperand(indexing.input2, "left-index value");
				label = merge(label, labelOf(ec, indexing.input2));
			}
			for(CPOperand bound : Arrays.asList(indexing.getRowLower(), indexing.getRowUpper(),
				indexing.getColLower(), indexing.getColUpper()))
				label = merge(label, labelOf(ec, requireIndexBound(bound)));
			// Selecting or replacing a subrange destroys full-aggregate provenance.
			// Conservatively make every PA-dependent indexing result non-releasable.
			return makeNonDeclassifiable(label);
		}
		else if(instruction.getClass() == ParameterizedBuiltinCPInstruction.class) {
			ParameterizedBuiltinCPInstruction replacement = (ParameterizedBuiltinCPInstruction) instruction;
			String target = replacement.getParam("target");
			if(target == null || !ec.containsVariable(target) || !(ec.getVariable(target) instanceof MatrixObject))
				throw new FederatedWorkerHandlerException(
					"Federated worker privacy policy requires a matrix replace target.");
			// replace is a local, non-bijective transform: never turn a partial
			// PRIVATE_AGGREGATE shard into a releasable full aggregate.
			return makeNonDeclassifiable(effectiveLabel(ec.getVariable(target)));
		}
		else if(instruction.getClass() == VariableCPInstruction.class) {
			VariableCPInstruction variable = (VariableCPInstruction) instruction;
			if(variable.isRemoveVariableNoFile())
				return WorkerPrivacyLevel.PUBLIC;
			CPOperand source = variable.getInput1();
			if(source == null)
				return WorkerPrivacyLevel.UNKNOWN;
			else if(source.isLiteral()) {
				if(!variable.isAssignVariable() || source.getDataType() != DataType.SCALAR)
					throw new FederatedWorkerHandlerException(
						"Federated worker privacy policy denies a literal copy or move source.");
				return WorkerPrivacyLevel.PUBLIC;
			}
			else
				return labelOf(ec, source);
		}
		throw new FederatedWorkerHandlerException(
			"Federated worker privacy policy denies an unsupported instruction.");
	}

	/**
	 * Ensure BasicProgramBlock will execute the same instruction identity that was
	 * authorized. Worker privacy does not currently authorize dynamic label patching
	 * or CP-to-FED replacement because either can change operands or the destination
	 * after the privacy contract was evaluated.
	 */
	static void validateStableExecutionTarget(ExecutionContext ec, Instruction instruction) {
		if(instruction.requiresLabelUpdate())
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy denies runtime-patched instruction labels.");
		if(ConfigurationManager.isFederatedRuntimePlanner()
			&& FEDInstructionUtils.checkAndReplaceCP(instruction, ec) != instruction)
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy denies runtime instruction replacement.");
	}

	private static void validateInstruction(Instruction instruction) {
		if(isSupportedInstruction(instruction))
			return;
		// Unknown instructions can acquire data or perform side effects not represented
		// by their declared operands, so existing PUBLIC state is not sufficient proof.
		throw new FederatedWorkerHandlerException(
			"Federated worker privacy policy denies an unsupported instruction.");
	}

	private static boolean isSupportedInstruction(Instruction instruction) {
		return isSupportedAggregate(instruction) || isSupportedReorg(instruction)
			|| isSupportedIndexing(instruction) || isSupportedReplace(instruction)
			|| isSupportedVariable(instruction);
	}

	private static boolean isSupportedReplace(Instruction instruction) {
		if(instruction.getClass() != ParameterizedBuiltinCPInstruction.class
			|| !"replace".equalsIgnoreCase(instruction.getOpcode()))
			return false;
		ParameterizedBuiltinCPInstruction replacement = (ParameterizedBuiltinCPInstruction) instruction;
		if(replacement.getParameterMap().size() != 3 || replacement.getParam("target") == null
			|| replacement.getParam("pattern") == null || replacement.getParam("replacement") == null)
			return false;
		try {
			Double.parseDouble(replacement.getParam("pattern"));
			Double.parseDouble(replacement.getParam("replacement"));
			return true;
		}
		catch(NumberFormatException ex) {
			return false;
		}
	}

	private static boolean isSupportedAggregate(Instruction instruction) {
		return instruction.getClass() == AggregateUnaryCPInstruction.class
			&& ("uak+".equalsIgnoreCase(instruction.getOpcode())
				|| "uacmax".equalsIgnoreCase(instruction.getOpcode())
				|| "uacmean".equalsIgnoreCase(instruction.getOpcode()));
	}

	private static boolean isSupportedReorg(Instruction instruction) {
		return instruction.getClass() == ReorgCPInstruction.class
			&& ("r'".equalsIgnoreCase(instruction.getOpcode())
				|| "rev".equalsIgnoreCase(instruction.getOpcode()));
	}

	private static boolean isSupportedIndexing(Instruction instruction) {
		return instruction.getClass() == MatrixIndexingCPInstruction.class
			&& (isLeftIndex(instruction) || "rightIndex".equalsIgnoreCase(instruction.getOpcode()));
	}

	private static boolean isSupportedVariable(Instruction instruction) {
		if(instruction.getClass() != VariableCPInstruction.class)
			return false;
		VariableCPInstruction variable = (VariableCPInstruction) instruction;
		return variable.isRemoveVariableNoFile() || variable.isAssignOrCopyVariable()
			|| variable.isMoveVariable() && variable.getOutputVariableName() != null;
	}

	/**
	 * Protect objects that an allowed instruction may mutate before it executes. This
	 * deliberately runs before the instruction, so a partial failure cannot leave a
	 * mutated object reachable through an older PUBLIC alias.
	 */
	static void protectInstructionAliases(ExecutionContext ec, Instruction instruction, WorkerPrivacyLevel label) {
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

	private static void protectAliases(ExecutionContext ec, Data target, WorkerPrivacyLevel label) {
		if(target == null)
			return;
		for(Map.Entry<String, Data> entry : ec.getVariables().entrySet())
			if(entry.getValue() == target)
				setRecursively(entry.getValue(), merge(effectiveLabel(entry.getValue()), label));
	}

	/** Attach the previously inferred label after successful instruction execution. */
	static void applyInstructionOutput(ExecutionContext ec, Instruction instruction, WorkerPrivacyLevel label) {
		String outputName = instruction.getOutputVariableName();
		if(outputName == null || !ec.containsVariable(outputName))
			return;
		Data output = ec.getVariable(outputName);
		WorkerPrivacyLevel released = releaseLabel(instruction, label);
		// An unassigned output is newly materialized and receives the contract label.
		// An assigned output object was reused/mutated in place; retain its previous
		// restriction because a partial update or alias may still expose old content.
		output.setWorkerPrivacyLevel(output.hasWorkerPrivacyLevel()
			? merge(effectiveLabel(output), released) : released);
	}

	private static WorkerPrivacyLevel releaseLabel(Instruction instruction, WorkerPrivacyLevel executionLabel) {
		if(instruction.getClass() == AggregateUnaryCPInstruction.class
			&& isFullAggregateRelease((AggregateUnaryCPInstruction) instruction)
			&& executionLabel == WorkerPrivacyLevel.PRIVATE_AGGREGATE)
			return WorkerPrivacyLevel.PUBLIC;
		return executionLabel;
	}

	private static boolean isFullAggregateRelease(AggregateUnaryCPInstruction instruction) {
		return "uak+".equalsIgnoreCase(instruction.getOpcode());
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

	private static CPOperand requireIndexBound(CPOperand operand) {
		if(operand == null)
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy requires every index bound.");
		if(!operand.isLiteral() && operand.getDataType() != DataType.SCALAR)
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy requires scalar index bounds.");
		return operand;
	}

	private static void requireVariableDataOperand(CPOperand operand, String description) {
		if(operand == null || operand.isLiteral())
			throw new FederatedWorkerHandlerException(
				"Federated worker privacy policy denies a literal or missing " + description + ".");
	}

	private static boolean isLeftIndex(Instruction instruction) {
		return "leftIndex".equalsIgnoreCase(instruction.getOpcode());
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

	private static WorkerPrivacyLevel makeNonDeclassifiable(WorkerPrivacyLevel label) {
		return label == WorkerPrivacyLevel.PRIVATE_AGGREGATE ? WorkerPrivacyLevel.PRIVATE : label;
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
