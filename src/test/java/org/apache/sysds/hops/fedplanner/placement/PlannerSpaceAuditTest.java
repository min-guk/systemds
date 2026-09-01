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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimeCapabilityAudit.Observation;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.instructions.cp.CPOperand;
import org.apache.sysds.runtime.instructions.cp.CPInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PlannerSpaceAuditTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private Path directory;

	@Before
	public void setUp() throws Exception {
		directory = Files.createTempDirectory("fed-planner-space-audit-");
		PlannerRuntimeCapabilityAudit.resetForTesting();
		System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, directory.toString());
		System.setProperty(PlannerRuntimeCapabilityAudit.DIRECTORY_PROPERTY, directory.toString());
	}

	@After
	public void tearDown() throws Exception {
		System.clearProperty(PlannerCandidateSpaceAudit.PROPERTY);
		System.clearProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
		System.clearProperty(PlannerRuntimeCapabilityAudit.PROPERTY);
		System.clearProperty(PlannerRuntimeCapabilityAudit.DIRECTORY_PROPERTY);
		PlannerRuntimeCapabilityAudit.resetForTesting();
		if(directory != null && Files.exists(directory))
			try(var files = Files.walk(directory)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					}
					catch(Exception ex) {
						throw new RuntimeException(ex);
					}
				});
			}
	}

	@Test
	public void candidateAuditCapturesPrePrivacyAndPublishedDomains() throws Exception {
		System.setProperty(PlannerCandidateSpaceAudit.PROPERTY, Boolean.TRUE.toString());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			ProductionShadowFixtureFactory.compile("B-13"));
		Path output;
		try(var files = Files.list(directory)) {
			output = files.filter(path -> path.getFileName().toString().startsWith("candidate-space-"))
				.findFirst().orElseThrow();
		}
		List<String> lines = Files.readAllLines(output);
		assertFalse(lines.isEmpty());
		JsonNode row = MAPPER.readTree(lines.get(0));
		assertEquals("fedplanner-candidate-space-v1", row.path("schema").asText());
		assertEquals(analysis.analysisFingerprint(), row.path("analysisFingerprint").asText());
		assertTrue(row.has("prePrivacyRule"));
		assertTrue(row.has("publishedRule"));
		assertTrue(row.has("publishedStatesP"));
		assertFalse(row.path("occurrenceKeyHash").asText().isBlank());
		assertFalse(row.path("semanticReplayOccurrenceKeyHash").asText().isBlank());
		assertEquals(PlannerSpaceAuditTest.class.getName()
			+ "#candidateAuditCapturesPrePrivacyAndPublishedDomains",
			row.path("auditContext").asText());
	}

	@Test
	public void disabledCandidateAuditHasNoSideEffect() throws Exception {
		new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile("B-01"));
		try(var files = Files.list(directory)) {
			assertEquals(0, files.count());
		}
	}

	@Test
	public void replayOccurrenceIgnoresUnstableControlStatementOrdinals() {
		CompiledHopKey discovery = occurrence("main/5/branch-if/0", "source.dml:60:4:TWrite Y");
		CompiledHopKey replay = occurrence("main/2/branch-if/0", "source.dml:60:4:TWrite Y");
		assertEquals("101fe411da336f07",
			PlannerCandidateSpaceAudit.replayOccurrenceHash(discovery));
		assertEquals(PlannerCandidateSpaceAudit.replayOccurrenceHash(discovery),
			PlannerCandidateSpaceAudit.replayOccurrenceHash(replay));
	}

	@Test
	public void replayOccurrenceRetainsSourceAndEmittedInstanceIdentity() {
		CompiledHopKey sourceA = occurrence("main/5/branch-if/0", "source.dml:60:4:TWrite Y");
		CompiledHopKey sourceB = occurrence("main/2/branch-if/0", "source.dml:61:4:TWrite Y");
		CompiledHopKey instanceB = new CompiledHopKey("program-b", "main",
			"main/2/branch-if/0", "compiled",
			new ControlRegionKey("program-b", "main", List.of("main/2/branch-if/0"),
				"main/2/branch-if/0", "compiled"),
			"root-1", "source.dml:60:4:TWrite Y");
		assertFalse(PlannerCandidateSpaceAudit.replayOccurrenceHash(sourceA).equals(
			PlannerCandidateSpaceAudit.replayOccurrenceHash(sourceB)));
		assertFalse(PlannerCandidateSpaceAudit.replayOccurrenceHash(sourceA).equals(
			PlannerCandidateSpaceAudit.replayOccurrenceHash(instanceB)));
		assertFalse(PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(sourceA).equals(
			PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(sourceB)));
		assertEquals(PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(sourceA),
			PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(instanceB));
	}

	@Test
	public void semanticReplayOccurrenceRetainsControlContext() {
		CompiledHopKey branch = occurrence("main/5/branch-if/0", "source.dml:60:4:b(+)");
		CompiledHopKey loop = occurrence("main/2/loop-body/0", "source.dml:60:4:b(+)");
		assertFalse(PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(branch).equals(
			PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(loop)));
	}

	@Test
	public void runtimeAuditWritesOnlyExplicitSuccessOrFailureOutcomes() throws Exception {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		AuditFedInstruction instruction = new AuditFedInstruction();
		Observation success = PlannerRuntimeCapabilityAudit.begin(instruction, null);
		PlannerRuntimeCapabilityAudit.recordSuccessful(success, instruction, null);
		Observation failure = PlannerRuntimeCapabilityAudit.begin(instruction, null);
		PlannerRuntimeCapabilityAudit.recordFailure(failure, instruction,
			new IllegalStateException("unsupported-test-state"));
		Path output;
		try(var files = Files.list(directory)) {
			output = files.filter(path -> path.getFileName().toString().startsWith("runtime-capability-"))
				.findFirst().orElseThrow();
		}
		List<String> outcomes = Files.readAllLines(output).stream().map(line -> {
			try {
				return MAPPER.readTree(line).path("outcome").asText();
			}
			catch(Exception ex) {
				throw new RuntimeException(ex);
			}
		}).sorted().toList();
		assertEquals(List.of("FAILURE", "SUCCESS"), outcomes);
	}

	@Test
	public void runtimeOperandsFollowSerializedInstructionOrder() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		Observation observation = PlannerRuntimeCapabilityAudit.begin(
			new OrderedAuditFedInstruction(), null);
		assertEquals(List.of("first", "second"), observation.inputs().stream()
			.map(input -> String.valueOf(input.get("name"))).toList());
		assertEquals(List.of(1, 2), observation.inputs().stream()
			.map(input -> (Integer)input.get("instructionPosition")).toList());
	}

	@Test
	public void rewrittenLiteralKeepsTheOriginalLogicalTernaryInputOrder() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		Observation observation = PlannerRuntimeCapabilityAudit.begin(
			new RewrittenTernaryAuditFedInstruction(), null);
		assertEquals(List.of("first", "second", "third"), observation.inputs().stream()
			.map(input -> String.valueOf(input.get("name"))).toList());
		assertEquals(List.of(1, 2, 3), observation.inputs().stream()
			.map(input -> (Integer)input.get("instructionPosition")).toList());
	}

	@Test
	public void runtimeAuditReadsMapEncodedParameterizedBuiltinOperands() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		Observation observation = PlannerRuntimeCapabilityAudit.begin(
			new MapEncodedAuditFedInstruction(), null);
		assertEquals(List.of("rows", "X"), observation.inputs().stream()
			.map(input -> String.valueOf(input.get("name"))).toList());
		assertEquals(List.of(1, 2), observation.inputs().stream()
			.map(input -> (Integer)input.get("instructionPosition")).toList());
	}

	@Test
	public void runtimeAuditOrdersMapOperandsByPlannerParameterRoles() {
		List<Map<String,Object>> inputs = List.of(
			Map.of("field", "instruction#params[margin]", "present", true),
			Map.of("field", "instruction#params[target]", "present", true,
				"federated", true, "fType", "COL"));
		assertEquals(List.of("PRESENT:COL", "ABSENT_LOCAL:-"),
			PlannerRuntimeCapabilityAudit.orderedRuntimeInputStates(
				List.of("target", "margin"), inputs));
	}

	@Test
	public void plannerInputRolesPreserveHopParameterOrder() {
		LinkedHashMap<String,org.apache.sysds.hops.Hop> inputs = new LinkedHashMap<>();
		inputs.put("target", new LiteralOp(1L));
		inputs.put("margin", new LiteralOp("rows"));
		inputs.put("select", new LiteralOp(2L));
		ParameterizedBuiltinOp hop = new ParameterizedBuiltinOp("out", DataType.MATRIX,
			ValueType.FP64, ParamBuiltinOp.RMEMPTY, inputs);
		assertEquals(List.of("target", "margin", "select"),
			PlannerRuntimePlacementAudit.namedInputRoles(hop));
	}

	@Test
	public void runtimeAuditReadsOperandsFromNestedInstructionWrapper() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		Observation observation = PlannerRuntimeCapabilityAudit.begin(
			new NestedAuditFedInstruction(), null);
		assertEquals(List.of("first", "second"), observation.inputs().stream()
			.map(input -> String.valueOf(input.get("name"))).toList());
		assertEquals(List.of(1, 2), observation.inputs().stream()
			.map(input -> (Integer)input.get("instructionPosition")).toList());
	}

	@Test
	public void repeatedSerializedOperandsConsumeDistinctPositions() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		Observation observation = PlannerRuntimeCapabilityAudit.begin(
			new RepeatedAuditFedInstruction(), null);
		assertEquals(List.of("same", "same"), observation.inputs().stream()
			.map(input -> String.valueOf(input.get("name"))).toList());
		assertEquals(List.of(1, 2), observation.inputs().stream()
			.map(input -> (Integer)input.get("instructionPosition")).toList());
	}

	@Test
	public void runtimeAuditAcceptsLegacyInstructionWithoutFedTypeOrOutputMode() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		Observation observation = PlannerRuntimeCapabilityAudit.begin(
			new LegacyAuditFedInstruction(), null);
		assertTrue(observation.instruction().containsKey("fedType"));
		assertNull(observation.instruction().get("fedType"));
		assertTrue(observation.instruction().containsKey("federatedOutput"));
		assertNull(observation.instruction().get("federatedOutput"));
	}

	@Test
	public void loweringAuxiliaryDoesNotPretendToExposeTheSelectedHopInputTuple() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		OrderedAuditFedInstruction instruction = new OrderedAuditFedInstruction();
		instruction.setPlannerLoweringAuxiliaryKind("PHYSICAL_REBLOCK");
		Observation observation = PlannerRuntimeCapabilityAudit.begin(instruction, null);
		assertEquals("PHYSICAL_REBLOCK",
			observation.instruction().get("plannerLoweringAuxiliaryKind"));
		assertEquals("UNAVAILABLE_LOWERING_AUXILIARY_INPUT_BOUNDARY",
			observation.instruction().get("actualInputSignatureMethod"));
		assertEquals(Map.of(), observation.instruction().get("actualInputSignatures"));
	}

	@Test
	public void runtimeFrontierSeparatesDirectAndDynamicFedDispatch() {
		System.setProperty(PlannerRuntimeCapabilityAudit.PROPERTY, Boolean.TRUE.toString());
		OrderedAuditFedInstruction direct = new OrderedAuditFedInstruction();
		assertEquals("DIRECT_FED", PlannerRuntimeCapabilityAudit
			.runtimeFrontier(direct, direct, null).get("frontierKind"));

		Map<String,Object> converted = PlannerRuntimeCapabilityAudit.runtimeFrontier(
			new AuditCPInstruction(), new OrderedAuditFedInstruction(), null);
		assertEquals("RUNTIME_TO_FED", converted.get("frontierKind"));
		assertEquals(AuditCPInstruction.class.getName(), converted.get("sourceInstructionClass"));
		assertEquals(OrderedAuditFedInstruction.class.getName(),
			converted.get("resultInstructionClass"));
		assertEquals("LOUT", converted.get("resultFederatedOutput"));
	}

	private static final class AuditFedInstruction extends FEDInstruction {
		private AuditFedInstruction() {
			super(FEDType.AggregateBinary, null, "audit", "audit", FederatedOutput.LOUT);
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}

	private static final class AuditCPInstruction extends CPInstruction {
		@SuppressWarnings("unused")
		private final CPOperand first = new CPOperand("first", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand second = new CPOperand("second", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand output = new CPOperand("out", ValueType.FP64, DataType.MATRIX);

		private AuditCPInstruction() {
			super(CPType.Binary, "audit",
				"CP°audit°first·MATRIX·FP64·false°second·MATRIX·FP64·false"
					+ "°out·MATRIX·FP64·false");
		}

		@Override
		public String getOutputVariableName() {
			return "out";
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}

	private static CompiledHopKey occurrence(String path, String source) {
		return new CompiledHopKey("program-a", "main", path, "compiled",
			new ControlRegionKey("program-a", "main", List.of(path), path, "compiled"),
			"root-0", source);
	}

	private static final class OrderedAuditFedInstruction extends FEDInstruction {
		@SuppressWarnings("unused")
		private final CPOperand second = new CPOperand("second", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand first = new CPOperand("first", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand output = new CPOperand("out", ValueType.FP64, DataType.MATRIX);

		private OrderedAuditFedInstruction() {
			super(FEDType.Binary, null, "audit",
				"FED°audit°first·MATRIX·FP64·false°second·MATRIX·FP64·false"
					+ "°out·MATRIX·FP64·false",
				FederatedOutput.LOUT);
		}

		@Override
		public String getOutputVariableName() {
			return "out";
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}

	private static final class LegacyAuditFedInstruction extends FEDInstruction {
		private LegacyAuditFedInstruction() {
			super(null, null, "audit", "audit", null);
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op legacy-style test instruction
		}
	}

	private static final class RewrittenTernaryAuditFedInstruction extends FEDInstruction {
		@SuppressWarnings("unused")
		private final CPOperand input1 = new CPOperand("first", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand input2 = new CPOperand("second", ValueType.FP64, DataType.SCALAR);
		@SuppressWarnings("unused")
		private final CPOperand input3 = new CPOperand("third", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand output = new CPOperand("out", ValueType.FP64, DataType.MATRIX);

		private RewrittenTernaryAuditFedInstruction() {
			super(FEDType.Ternary, null, "audit",
				"FED°audit°first·MATRIX·FP64·false°7.0·SCALAR·FP64·true"
					+ "°third·MATRIX·FP64·false°out·MATRIX·FP64·false",
				FederatedOutput.FOUT);
		}

		@Override
		public String getOutputVariableName() {
			return "out";
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}

	private static final class MapEncodedAuditFedInstruction extends FEDInstruction {
		@SuppressWarnings("unused")
		private final Map<String,String> params = new LinkedHashMap<>();
		@SuppressWarnings("unused")
		private final CPOperand output = new CPOperand("out", ValueType.FP64, DataType.MATRIX);

		private MapEncodedAuditFedInstruction() {
			super(FEDType.ParameterizedBuiltin, null, "rmempty",
				"FED°rmempty°margin=rows°target=X°out·MATRIX·FP64",
				FederatedOutput.FOUT);
			params.put("margin", "rows");
			params.put("target", "X");
		}

		@Override
		public String getOutputVariableName() {
			return "out";
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}

	private static final class NestedAuditFedInstruction extends FEDInstruction {
		@SuppressWarnings("unused")
		private final AuditCPInstruction nested = new AuditCPInstruction();

		private NestedAuditFedInstruction() {
			super(null, null, "audit",
				"CP°audit°first·MATRIX·FP64·false°second·MATRIX·FP64·false"
					+ "°out·MATRIX·FP64·false",
				FederatedOutput.NONE);
		}

		@Override
		public String getOutputVariableName() {
			return "out";
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}

	private static final class RepeatedAuditFedInstruction extends FEDInstruction {
		@SuppressWarnings("unused")
		private final CPOperand input1 = new CPOperand("same", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand input2 = new CPOperand("same", ValueType.FP64, DataType.MATRIX);
		@SuppressWarnings("unused")
		private final CPOperand output = new CPOperand("out", ValueType.FP64, DataType.MATRIX);

		private RepeatedAuditFedInstruction() {
			super(FEDType.Binary, null, "audit",
				"FED°audit°same·MATRIX·FP64·false°same·MATRIX·FP64·false"
					+ "°out·MATRIX·FP64·false",
				FederatedOutput.LOUT);
		}

		@Override
		public String getOutputVariableName() {
			return "out";
		}

		@Override
		public void processInstruction(ExecutionContext ec) {
			// no-op test instruction
		}
	}
}
