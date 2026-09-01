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
package org.apache.sysds.hops.fedplanner.placement;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.fed.FEDFoutInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDRefedInstruction;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Positive runtime-capability witness recorder for successfully executed FED instructions.
 *
 * <p>The recorder snapshots inputs before execution and writes only after
 * {@link Instruction#processInstruction(ExecutionContext)} and post-processing complete.
 * Consequently, every row is an actual member of the witnessed runtime set R, not a parser
 * or lowering prediction.  Reflection is confined to this off-by-default audit and is used
 * only to enumerate the heterogeneous {@link CPOperand} fields of FED instruction classes.</p>
 */
public final class PlannerRuntimeCapabilityAudit {
	public static final String PROPERTY = "sysds.fedplanner.capability.audit";
	public static final String DIRECTORY_PROPERTY = "sysds.fedplanner.capability.audit.dir";
	private static final String DEFAULT_DIRECTORY = "target/fedplanner-space-audit";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Object WRITE_LOCK = new Object();
	private static final Set<String> WRITTEN = ConcurrentHashMap.newKeySet();
	private static final Set<String> WRITTEN_FRONTIERS = ConcurrentHashMap.newKeySet();

	/** Pre-execution evidence retained until the physical instruction succeeds. */
	public record Observation(Map<String,Object> instruction, List<Map<String,Object>> inputs) { }

	private PlannerRuntimeCapabilityAudit() {
		// utility class
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(PROPERTY);
	}

	/** Snapshot the physical FED instruction and its runtime input residency. */
	public static Observation begin(Instruction instruction, ExecutionContext ec) {
		if(!isEnabled() || !(instruction instanceof FEDInstruction fed))
			return null;
		List<Map<String,Object>> inputs = operandDescriptors(instruction, ec);
		List<String> occurrences = PlannerRuntimePlacementAudit.plannedOccurrenceKeyHashes(instruction);
		Map<String,List<String>> plannedInputs =
			PlannerRuntimePlacementAudit.plannedInputSignatures(instruction);
		Map<String,List<String>> plannedRoles =
			PlannerRuntimePlacementAudit.plannedInputRoles(instruction);
		String loweringAuxiliaryKind = instruction.getPlannerLoweringAuxiliaryKind();
		ActualInputMatch actualInputs = actualInputSignatures(
			occurrences, plannedInputs, plannedRoles, inputs, loweringAuxiliaryKind);
		Map<String,Object> identity = new LinkedHashMap<>();
		identity.put("instructionClass", instruction.getClass().getName());
		identity.put("instructionSimpleClass", instruction.getClass().getSimpleName());
		// VariableFEDInstruction is a legacy conversion wrapper and intentionally
		// carries no FEDType.  Capability auditing must describe that runtime
		// surface without changing whether the instruction can execute.
		identity.put("fedType", fed.getFEDInstructionType() == null
			? null : fed.getFEDInstructionType().name());
		identity.put("opcode", instruction.getOpcode());
		identity.put("federatedOutput", fed.getFederatedOutput() == null
			? null : fed.getFederatedOutput().name());
		identity.put("syntheticFType", syntheticFType(instruction));
		identity.put("hopId", instruction.getHopID());
		identity.put("originHopId", instruction.getPlannerOriginHopID());
		identity.put("lopId", instruction.getLopID());
		identity.put("plannerAuditKey", instruction.getPlannerAuditKey());
		identity.put("plannerSyntheticActionKey", instruction.getPlannerSyntheticActionKey());
		identity.put("plannerLoweringAuxiliaryKind", loweringAuxiliaryKind);
		identity.put("plannerRewriteReplacementKind", instruction.getPlannerRewriteReplacementKind());
		identity.put("occurrenceKeyHashes", occurrences);
		identity.put("plannerPlanHash", PlannerRuntimePlacementAudit.plannedPlanHash(instruction));
		identity.put("plannerAnalysisFingerprint",
			PlannerRuntimePlacementAudit.plannedAnalysisFingerprint(instruction));
		identity.put("auditContext", PlannerCandidateSpaceAudit.currentAuditContext());
		identity.put("plannedTargetStates", PlannerRuntimePlacementAudit.plannedTargetStates(instruction));
		identity.put("plannedPhysicalStates", PlannerRuntimePlacementAudit.plannedPhysicalStates(instruction));
		identity.put("plannedInputSignatures", plannedInputs);
		identity.put("plannedInputRoles", plannedRoles);
		identity.put("actualInputSignatures", actualInputs.signatures());
		identity.put("actualInputSignatureMethod", actualInputs.method());
		identity.put("recompileSignature", instruction.getPlannerRecompileSignature());
		identity.put("instruction", instruction.toString());
		return new Observation(Collections.unmodifiableMap(new LinkedHashMap<>(identity)),
			inputs);
	}

	/**
	 * Record the runtime conversion frontier before physical execution.
	 *
	 * <p>This receipt is deliberately separate from the positive capability
	 * witness. It identifies direct FED instructions, CP/SP instructions that the
	 * runtime converted to FED, and instructions that retained a non-FED form in
	 * the presence of a federated input. A frontier row is therefore parser and
	 * dispatch evidence, not proof that the resulting instruction executed.</p>
	 */
	public static void recordRuntimeFrontier(Instruction source, Instruction result,
		ExecutionContext ec) {
		Map<String,Object> row = runtimeFrontier(source, result, ec);
		if(row == null)
			return;
		Map<String,Object> persisted = new LinkedHashMap<>();
		persisted.put("schema", "fed-runtime-conversion-frontier-v1");
		persisted.put("pid", ProcessHandle.current().pid());
		persisted.putAll(row);
		try {
			String json = MAPPER.writeValueAsString(persisted);
			if(WRITTEN_FRONTIERS.add(json))
				append("runtime-conversion-frontier-", json);
		}
		catch(IOException ex) {
			throw new IllegalStateException("Unable to serialize FED runtime conversion frontier", ex);
		}
	}

	static Map<String,Object> runtimeFrontier(Instruction source, Instruction result,
		ExecutionContext ec) {
		if(!isEnabled() || source == null || result == null)
			return null;
		List<Map<String,Object>> sourceInputs = operandDescriptors(source, ec);
		boolean sourceFederated = sourceInputs.stream()
			.anyMatch(input -> Boolean.TRUE.equals(input.get("federated")));
		String kind;
		if(source instanceof FEDInstruction)
			kind = "DIRECT_FED";
		else if(result instanceof FEDInstruction)
			kind = "RUNTIME_TO_FED";
		else if(sourceFederated)
			kind = "FEDERATED_INPUT_NOT_CONVERTED";
		else
			return null;

		List<Map<String,Object>> resultInputs = source == result
			? sourceInputs : operandDescriptors(result, ec);
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("frontierKind", kind);
		row.put("auditContext", PlannerCandidateSpaceAudit.currentAuditContext());
		row.put("sourceInstructionClass", source.getClass().getName());
		row.put("sourceInstructionSimpleClass", source.getClass().getSimpleName());
		row.put("sourceOpcode", source.getOpcode());
		row.put("sourceHopId", source.getHopID());
		row.put("sourceOriginHopId", source.getPlannerOriginHopID());
		row.put("sourceLopId", source.getLopID());
		row.put("sourcePlannerAuditKey", source.getPlannerAuditKey());
		row.put("sourceInputs", sourceInputs);
		row.put("sourceInputStates", sourceInputs.stream()
			.map(PlannerRuntimeCapabilityAudit::runtimeInputState).toList());
		row.put("resultInstructionClass", result.getClass().getName());
		row.put("resultInstructionSimpleClass", result.getClass().getSimpleName());
		row.put("resultOpcode", result.getOpcode());
		row.put("resultFederatedOutput", result instanceof FEDInstruction fed
			&& fed.getFederatedOutput() != null ? fed.getFederatedOutput().name() : null);
		row.put("resultInputs", resultInputs);
		row.put("resultInputStates", resultInputs.stream()
			.map(PlannerRuntimeCapabilityAudit::runtimeInputState).toList());
		row.put("plannerPlanHash", PlannerRuntimePlacementAudit.plannedPlanHash(result));
		row.put("plannerAnalysisFingerprint",
			PlannerRuntimePlacementAudit.plannedAnalysisFingerprint(result));
		row.put("plannedTargetStates", PlannerRuntimePlacementAudit.plannedTargetStates(result));
		row.put("plannedPhysicalStates", PlannerRuntimePlacementAudit.plannedPhysicalStates(result));
		return Collections.unmodifiableMap(row);
	}

	/** Persist one positive R witness only after successful physical execution. */
	public static void recordSuccessful(Observation observation, Instruction instruction,
		ExecutionContext ec) {
		if(observation == null)
			return;
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "fed-runtime-capability-v1");
		row.put("outcome", "SUCCESS");
		row.put("pid", ProcessHandle.current().pid());
		row.putAll(observation.instruction());
		row.put("inputs", observation.inputs());
		row.put("output", outputDescriptor(instruction, ec));
		try {
			String json = MAPPER.writeValueAsString(row);
			if(WRITTEN.add(json))
				append(json);
		}
		catch(IOException ex) {
			throw new IllegalStateException("Unable to serialize FED runtime capability witness", ex);
		}
	}

	/** Persist a failed physical attempt separately; the comparator never treats it as an R member. */
	public static void recordFailure(Observation observation, Instruction instruction, Throwable failure) {
		if(observation == null)
			return;
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "fed-runtime-capability-v1");
		row.put("outcome", "FAILURE");
		row.put("pid", ProcessHandle.current().pid());
		row.putAll(observation.instruction());
		row.put("inputs", observation.inputs());
		row.put("failureClass", failure == null ? null : failure.getClass().getName());
		row.put("failureMessage", failure == null ? null : failure.getMessage());
		try {
			String json = MAPPER.writeValueAsString(row);
			if(WRITTEN.add(json))
				append(json);
		}
		catch(IOException ex) {
			throw new IllegalStateException("Unable to serialize failed FED runtime capability attempt", ex);
		}
	}

	private static List<Map<String,Object>> operandDescriptors(Instruction instruction,
		ExecutionContext ec) {
		List<OperandField> operands = new ArrayList<>();
		collectInstructionOperands(instruction, instruction.getClass().getName(), operands, ec,
			new IdentityHashMap<>());
		String output = instruction.getOutputVariableName();
		List<OperandField> inputs = operands.stream()
			.filter(operand -> output == null || !output.equals(operand.operand().getName()))
			.toList();
		List<Map<String,Object>> descriptors = new ArrayList<>();
		for(PositionedOperand operand : positionOperands(instruction, inputs))
			descriptors.add(operandDescriptor(operand.field(), operand.operand(),
				operand.position(), ec));
		return List.copyOf(descriptors);
	}

	private static List<PositionedOperand> positionOperands(Instruction instruction,
		List<OperandField> operands) {
		List<OperandField> ordered = operands.stream().sorted(Comparator
			.comparingInt((OperandField operand) -> logicalFieldPosition(operand.field()))
			.thenComparing(OperandField::field)).toList();
		Set<Integer> used = new HashSet<>();
		List<PositionedOperand> positioned = new ArrayList<>();
		for(OperandField operand : ordered) {
			int logical = logicalFieldPosition(operand.field());
			int position = logical != Integer.MAX_VALUE && !used.contains(logical)
				? logical : instructionPosition(instruction, operand.operand(), used);
			if(position != Integer.MAX_VALUE)
				used.add(position);
			positioned.add(new PositionedOperand(operand.field(), operand.operand(), position));
		}
		positioned.sort(Comparator.comparingInt(PositionedOperand::position)
			.thenComparing(PositionedOperand::field));
		return List.copyOf(positioned);
	}

	private static int logicalFieldPosition(String field) {
		java.util.regex.Matcher scalar = java.util.regex.Pattern
			.compile("#_?input([0-9]+)$").matcher(field);
		if(scalar.find())
			return Integer.parseInt(scalar.group(1));
		java.util.regex.Matcher array = java.util.regex.Pattern
			.compile("#_?inputs\\[([0-9]+)]$").matcher(field);
		if(array.find())
			return Integer.parseInt(array.group(1)) + 1;
		return Integer.MAX_VALUE;
	}

	private static int instructionPosition(Instruction instruction, CPOperand operand,
		Set<Integer> used) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(
			instruction.getInstructionString());
		String typed = InstructionUtils.concatOperandParts(operand.getName(),
			operand.getDataType().name(), operand.getValueType().name());
		String literal = operand.getLineageLiteral();
		for(int position = 1; position < parts.length; position++) {
			if(used.contains(position))
				continue;
			String serialized = parameterValue(parts[position]);
			if(serialized.equals(operand.getName()) || serialized.equals(literal)
				|| serialized.equals(typed)
				|| serialized.startsWith(typed + Instruction.VALUETYPE_PREFIX))
				return position;
		}
		return Integer.MAX_VALUE;
	}

	private static String parameterValue(String serialized) {
		int separator = serialized.indexOf('=');
		return separator < 0 ? serialized : serialized.substring(separator + 1);
	}

	private record ActualInputMatch(Map<String,List<String>> signatures, String method) { }

	private static ActualInputMatch actualInputSignatures(List<String> occurrences,
		Map<String,List<String>> plannedInputs, Map<String,List<String>> plannedRoles,
		List<Map<String,Object>> inputs, String loweringAuxiliaryKind) {
		// A lowering auxiliary is a physical boundary introduced below the selected
		// HOP occurrence (for example, the rblk after a federated data read). Its
		// operands are therefore not the selected HOP's input tuple. Keep the
		// successful runtime witness in R, but do not manufacture an occurrence-level
		// P/R input-signature comparison from two different semantic boundaries.
		if(loweringAuxiliaryKind != null)
			return new ActualInputMatch(Map.of(),
				"UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY");
		if(occurrences.isEmpty())
			return new ActualInputMatch(Map.of(), "UNAVAILABLE_NO_PLANNED_OCCURRENCE");
		if(occurrences.size() != 1)
			return new ActualInputMatch(Map.of(), "UNAVAILABLE_FOR_FUSED_INSTRUCTION");
		String occurrence = occurrences.get(0);
		List<String> planned = plannedInputs.get(occurrence);
		if(planned == null)
			return new ActualInputMatch(Map.of(), "UNAVAILABLE_NO_PLANNED_SIGNATURE");
		List<String> roles = plannedRoles.get(occurrence);
		if(roles != null) {
			if(roles.size() != planned.size())
				return new ActualInputMatch(Map.of(), "UNAVAILABLE_PARAMETER_ROLE_CARDINALITY");
			List<String> actual = orderedRuntimeInputStates(roles, inputs);
			if(actual.isEmpty() && !roles.isEmpty())
				return new ActualInputMatch(Map.of(), "UNAVAILABLE_PARAMETER_ROLE_MATCH");
			return new ActualInputMatch(Map.of(occurrence, actual), "PARAMETER_ROLE_ORDER");
		}
		if(inputs.size() < planned.size())
			return new ActualInputMatch(Map.of(), "UNAVAILABLE_OPERAND_CARDINALITY");
		List<String> actual = inputs.stream().limit(planned.size())
			.map(PlannerRuntimeCapabilityAudit::runtimeInputState).toList();
		return new ActualInputMatch(Map.of(occurrence, actual), "INSTRUCTION_OPERAND_ORDER");
	}

	static List<String> orderedRuntimeInputStates(List<String> roles,
		List<Map<String,Object>> inputs) {
		List<String> states = new ArrayList<>();
		Set<Integer> used = new HashSet<>();
		for(String role : roles) {
			int match = -1;
			String suffix = '[' + role + ']';
			for(int i = 0; i < inputs.size(); i++) {
				if(!used.contains(i) && String.valueOf(inputs.get(i).get("field")).endsWith(suffix)) {
					match = i;
					break;
				}
			}
			if(match < 0)
				return List.of();
			used.add(match);
			states.add(runtimeInputState(inputs.get(match)));
		}
		return List.copyOf(states);
	}

	private static String runtimeInputState(Map<String,Object> input) {
		if(Boolean.TRUE.equals(input.get("federated")))
			return "PRESENT:" + String.valueOf(input.getOrDefault("fType", "?"));
		return "ABSENT_LOCAL:-";
	}

	private record OperandField(String field, CPOperand operand) { }

	private record PositionedOperand(String field, CPOperand operand, int position) { }

	private static void collectInstructionOperands(Instruction instruction, String prefix,
		List<OperandField> operands, ExecutionContext ec, IdentityHashMap<Object,Boolean> seen) {
		if(seen.put(instruction, Boolean.TRUE) != null)
			return;
		Class<?> type = instruction.getClass();
		while(type != null && Instruction.class.isAssignableFrom(type)) {
			for(Field field : type.getDeclaredFields()) {
				if(Modifier.isStatic(field.getModifiers()))
					continue;
				String name = prefix + "->" + type.getName() + '#' + field.getName();
				try {
					field.setAccessible(true);
					collectOperands(name, field.get(instruction), operands, ec, seen);
				}
				catch(ReflectiveOperationException | RuntimeException ex) {
					throw new IllegalStateException("Unable to inspect FED instruction operands: "
						+ name, ex);
				}
			}
			type = type.getSuperclass();
		}
	}

	private static void collectOperands(String field, Object value, List<OperandField> operands,
		ExecutionContext ec, IdentityHashMap<Object,Boolean> seen) {
		if(value instanceof CPOperand operand) {
			if(seen.put(operand, Boolean.TRUE) == null)
				operands.add(new OperandField(field, operand));
			return;
		}
		if(value == null)
			return;
		if(value instanceof Instruction nested) {
			collectInstructionOperands(nested, field, operands, ec, seen);
			return;
		}
		if(value.getClass().isArray()) {
			for(int i = 0; i < Array.getLength(value); i++)
				collectOperands(field + '[' + i + ']', Array.get(value, i), operands, ec, seen);
		}
		else if(value instanceof Collection<?> collection) {
			int index = 0;
			for(Object item : collection)
				collectOperands(field + '[' + index++ + ']', item, operands, ec, seen);
		}
		else if(value instanceof Map<?,?> map) {
			boolean encodedParameters = field.endsWith("#params") || field.endsWith("#_params");
			for(Map.Entry<?,?> entry : map.entrySet()) {
				String itemField = field + '[' + String.valueOf(entry.getKey()) + ']';
				if(encodedParameters && entry.getValue() instanceof String raw)
					operands.add(new OperandField(itemField, parameterOperand(raw, ec)));
				else
					collectOperands(itemField, entry.getValue(), operands, ec, seen);
			}
		}
	}

	private static CPOperand parameterOperand(String value, ExecutionContext ec) {
		if(ec != null && ec.containsVariable(value))
			return new CPOperand(value, ec.getVariable(value));
		if(value.indexOf(Instruction.VALUETYPE_PREFIX) >= 0)
			return new CPOperand(value);
		if("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
			return new CPOperand(value, ValueType.BOOLEAN, DataType.SCALAR, true);
		try {
			Long.parseLong(value);
			return new CPOperand(value, ValueType.INT64, DataType.SCALAR, true);
		}
		catch(NumberFormatException ignored) {
			// try the next literal representation
		}
		try {
			Double.parseDouble(value);
			return new CPOperand(value, ValueType.FP64, DataType.SCALAR, true);
		}
		catch(NumberFormatException ignored) {
			return new CPOperand(value, ValueType.STRING, DataType.SCALAR, true);
		}
	}

	private static Map<String,Object> operandDescriptor(String field, CPOperand operand,
		int instructionPosition, ExecutionContext ec) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("field", field);
		out.put("instructionPosition", instructionPosition == Integer.MAX_VALUE
			? null : instructionPosition);
		out.put("name", operand.getName());
		out.put("declaredDataType", operand.getDataType().name());
		out.put("declaredValueType", operand.getValueType().name());
		out.put("literal", operand.isLiteral());
		if(operand.isLiteral()) {
			out.put("present", true);
			return out;
		}
		boolean present = ec != null && ec.containsVariable(operand.getName());
		out.put("present", present);
		if(present)
			addRuntimeData(out, ec.getVariable(operand.getName()));
		return out;
	}

	private static Map<String,Object> outputDescriptor(Instruction instruction, ExecutionContext ec) {
		Map<String,Object> out = new LinkedHashMap<>();
		String name = instruction.getOutputVariableName();
		out.put("name", name);
		boolean present = name != null && ec != null && ec.containsVariable(name);
		out.put("present", present);
		if(present)
			addRuntimeData(out, ec.getVariable(name));
		return out;
	}

	private static void addRuntimeData(Map<String,Object> out, Data data) {
		if(data == null) {
			out.put("runtimeClass", null);
			return;
		}
		out.put("runtimeClass", data.getClass().getName());
		out.put("runtimeDataType", data.getDataType().name());
		out.put("runtimeValueType", data.getValueType().name());
		if(data instanceof CacheableData<?> cacheable) {
			boolean federated = cacheable.isFederated();
			out.put("federated", federated);
			out.put("fType", federated && cacheable.getFedMapping() != null
				? cacheable.getFedMapping().getType().name() : null);
			out.put("rows", cacheable.getNumRows());
			out.put("cols", cacheable.getNumColumns());
		}
		else {
			out.put("federated", false);
			out.put("fType", null);
		}
	}

	private static String syntheticFType(Instruction instruction) {
		if(instruction instanceof FEDFoutInstruction fout)
			return fout.getMaterializationFType().name();
		if(instruction instanceof FEDRefedInstruction refed)
			return refed.getMaterializationFType().name();
		return null;
	}

	private static void append(String json) throws IOException {
		append("runtime-capability-", json);
	}

	private static void append(String prefix, String json) throws IOException {
		Path directory = Path.of(System.getProperty(DIRECTORY_PROPERTY, DEFAULT_DIRECTORY));
		Path output = directory.resolve(prefix + ProcessHandle.current().pid() + ".jsonl");
		synchronized(WRITE_LOCK) {
			Files.createDirectories(directory);
			Files.writeString(output, json + '\n', StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
	}

	static void resetForTesting() {
		WRITTEN.clear();
		WRITTEN_FRONTIERS.clear();
	}

	/** Reset per-JVM de-duplication before replaying an independent forced target. */
	public static void resetRecordedWitnesses() {
		WRITTEN.clear();
		WRITTEN_FRONTIERS.clear();
	}
}
