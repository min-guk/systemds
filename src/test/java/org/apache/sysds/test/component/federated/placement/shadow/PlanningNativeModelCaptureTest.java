/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.runtime.instructions.fed.FEDInstructionUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class PlanningNativeModelCaptureTest {
	private static final Path CATALOG = Path.of(
		"src/test/resources/fedplanner/plan-space/closed-comparison-cases.json").toAbsolutePath();
	private static final Path EVALUATION = Path.of(System.getProperty("plan.space.evaluation.root",
		"../cofee-evaluation")).toAbsolutePath();

	@Test public void frozenPlanningProfileCapturesExactNativeDomainWithoutSemanticClaim() throws Exception {
		Assume.assumeTrue(Files.isRegularFile(CATALOG) && Files.isDirectory(EVALUATION));
		String cell = cellId("planning-w1:P1_FULL", "lan");
		String retainedArtifact = System.getProperty("plan.space.capture.test.artifact", "");
		Path artifact = retainedArtifact.isBlank()
			? Files.createTempFile("planning-native-model-", ".json.gz")
			: Path.of(retainedArtifact).toAbsolutePath();
		Map<String,Object> capture;
		try {
			capture = PlanningNativeModelCapture.captureWithArtifact(CATALOG, EVALUATION,
				cell, false, artifact);
			Assert.assertEquals(capture.toString(), "COMPLETE", capture.get("status"));
			byte[] plain;
			try(GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(artifact))) {
				plain = stream.readAllBytes();
			}
			JsonNode saved = new ObjectMapper().readTree(plain);
			Assert.assertEquals("closed-native-model-artifact-v2", saved.path("schema").asText());
			Assert.assertEquals(cell, saved.path("summary").path("cell").asText());
			Assert.assertEquals("PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
				saved.path("acceptance").asText());
			Assert.assertEquals(((Number) capture.get("placementCoordinates")).intValue(),
				saved.path("nativeDomain").path("placementDomains").size());
			Assert.assertEquals(((Number) capture.get("candidateCoordinates")).intValue(),
				saved.path("nativeDomain").path("candidateDomains").size());
			int candidateReceipts = 0;
			for(JsonNode coordinate : saved.path("nativeDomain").path("candidateDomains"))
				candidateReceipts += coordinate.path(1).size();
			Assert.assertEquals(candidateReceipts,
				saved.path("nativeDomain").path("candidateReceiptSemanticFacts").size());
			Assert.assertEquals(candidateReceipts,
				saved.path("nativeDomain").path("candidateReceiptActivationFacts").size());
			Assert.assertTrue(saved.path("nativeDomain").path("candidateRealizationReferenceFacts").isArray());
			JsonNode clauseInventory = saved.path("nativeDomain")
				.path("candidateRealizationClauseInventory");
			Assert.assertTrue(clauseInventory.isArray());
			Assert.assertFalse(clauseInventory.isEmpty());
			Assert.assertEquals(saved.path("nativeDomain")
				.path("candidateRealizationReferenceFacts").size(), clauseInventory.size());
			int clauseCount = 0;
			Set<String> workerAuthorityKinds = new LinkedHashSet<>();
			Set<String> bindingKinds = new LinkedHashSet<>();
			for(JsonNode realization : clauseInventory)
				for(JsonNode clause : realization.path("clauses")) {
					clauseCount++;
					workerAuthorityKinds.add(clause.path("workerPoolAuthority").path("kind").asText());
					for(JsonNode binding : clause.path("inputBindings"))
						bindingKinds.add(binding.path("kind").asText());
				}
			int authorityCount = 0;
			for(JsonNode realization : saved.path("nativeDomain")
				.path("candidateRealizationSupportAuthorities"))
				authorityCount += realization.path("supportAuthorities").size();
			Assert.assertEquals(authorityCount, clauseCount);
			Assert.assertTrue(workerAuthorityKinds.contains("EXACT_LAYOUT"));
			Assert.assertTrue(workerAuthorityKinds.contains("DYNAMIC_RESIDENCY"));
			Assert.assertTrue(bindingKinds.contains("DIRECT"));
			Assert.assertTrue(bindingKinds.contains("RELOCATION"));
			Assert.assertTrue(saved.path("nativeDomain").path("compiledCandidateInputEdges").isArray());
			Assert.assertTrue(saved.path("nativeDomain").path("logicalCandidateReachability").isObject());
			Assert.assertTrue(saved.path("nativeDomain").path("relocationActionFacts").isArray());
			Assert.assertEquals(((Number) capture.get("relocationCoordinates")).intValue(),
				saved.path("nativeDomain").path("relocationDomains").size());
			Assert.assertTrue(saved.path("nativeDomain").path("derivedFoutActions").isArray());
			Assert.assertTrue(saved.path("nativeDomain").path("derivedFoutOwnershipBindings").isArray());
			Assert.assertTrue(saved.path("nativeDomain").path("candidatePrivacyClosurePasses").isArray());
			Assert.assertFalse(saved.path("nativeDomain").path("candidatePrivacyClosurePasses").isEmpty());
			Assert.assertTrue(saved.path("nativeDomain").path("candidateRuleFactInventory").isArray());
			Assert.assertEquals(((Number) capture.get("prebuilderNodes")).intValue(),
				saved.path("preRewriteGraph").path("nodes").size());
			Assert.assertEquals(((Number) capture.get("finalHopNodes")).intValue(),
				saved.path("finalHopGraph").path("nodes").size());
			Assert.assertEquals(((Number) capture.get("artifactBytes")).intValue(), plain.length);
			Assert.assertEquals(64, ((String) capture.get("artifactSha256")).length());
			Assert.assertEquals("STRUCTURE_VERIFIED", PlanningNativeModelCapture.verifyArtifact(
				artifact, (String) capture.get("artifactSha256")).get("status"));
			assertArtifactMutationRejected(saved, root ->
				((ObjectNode) root.path("nativeDomain")).remove("candidateRealizationClauseInventory"),
				"clause inventory coverage differs");
			assertArtifactMutationRejected(saved, root ->
				((ObjectNode) root.path("nativeDomain")).putArray("candidateRealizationClauseInventory"),
				"clause inventory coverage differs");
			assertArtifactMutationRejected(saved, root -> ((ObjectNode) root.path("nativeDomain")
				.path("candidateRealizationClauseInventory").path(0).path("clauses").path(0))
				.put("clauseIdentity", "forged"), "support-clause identity differs");
			try {
				PlanningNativeModelCapture.verifyArtifact(artifact, "0".repeat(64));
				Assert.fail("A wrong artifact digest must fail closed");
			}
			catch(IllegalArgumentException expected) {
				Assert.assertTrue(expected.getMessage().contains("digest differs"));
			}
		}
		finally {
			if(retainedArtifact.isBlank())
				Files.deleteIfExists(artifact);
		}
		Assert.assertEquals(capture.toString(), "COMPLETE", capture.get("status"));
		Assert.assertEquals("closed-native-model-capture-v1", capture.get("schema"));
		Assert.assertEquals("P_C0", capture.get("source"));
		Assert.assertEquals("UNRESOLVED", capture.get("physicalDecode"));
		Assert.assertEquals("NOT_ASSESSED_BY_THIS_CONTRACT", capture.get("runtimeSemanticCoverage"));
		Assert.assertEquals(64, ((String) capture.get("nativeDomainSha256")).length());
		Assert.assertEquals("POST_REWRITE_HOPS_DAG_PRE_PLANNER", capture.get("compilerBoundary"));
		Assert.assertEquals(64, ((String) capture.get("preRewriteGraphSha256")).length());
		Assert.assertEquals(64, ((String) capture.get("finalHopGraphSha256")).length());
		Assert.assertNotEquals("P1 must analyze the rewritten graph", capture.get("preRewriteGraphSha256"),
			capture.get("finalHopGraphSha256"));
		Assert.assertTrue(((Number) capture.get("prebuilderNodes")).intValue()
			> ((Number) capture.get("finalHopNodes")).intValue());
		Assert.assertEquals(1, ((Number) capture.get("finalSourceHops")).intValue());
		BigInteger product = BigInteger.ONE;
		for(Object radix : (List<?>) capture.get("radices"))
			product = product.multiply(BigInteger.valueOf(((Number) radix).longValue()));
		Assert.assertEquals(new BigInteger((String) capture.get("rawCount")), product);
	}

	@Test public void supportClauseInventoryIsReceiptIndependentAndCanonicallyOrdered() {
		CandidateRuleFact reversed = supportClauseFact("b", "a");
		CandidateRuleFact forward = supportClauseFact("a", "b");
		List<Map<String,Object>> expected =
			PlanningNativeModelCapture.candidateRealizationClauseInventory(List.of(reversed));
		Assert.assertEquals(expected,
			PlanningNativeModelCapture.candidateRealizationClauseInventory(List.of(forward)));

		CandidateEmissionFact emission = reversed.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		List<CandidateSelectionReceipt> hostileReceipts = new ArrayList<>();
		for(CandidateRealizationSupportClause clause : realization.supportClauses())
			hostileReceipts.add(new CandidateSelectionReceipt(reversed.key(), emission,
				realization, clause, List.of()));
		Collections.reverse(hostileReceipts);
		hostileReceipts.add(hostileReceipts.get(0));
		hostileReceipts.remove(1);
		Assert.assertEquals("receipt mutations must not alter rule-fact clause authority", expected,
			PlanningNativeModelCapture.candidateRealizationClauseInventory(List.of(reversed)));

		Map<String,Object> realizationRow = expected.get(0);
		Assert.assertEquals(2, ((List<?>) realizationRow.get("clauses")).size());
		Assert.assertEquals(reversed.key().normalizedSignature(), realizationRow.get("rule"));
		@SuppressWarnings("unchecked")
		List<Map<String,Object>> clauses = (List<Map<String,Object>>) realizationRow.get("clauses");
		for(int ordinal = 0; ordinal < clauses.size(); ordinal++) {
			Map<String,Object> clause = clauses.get(ordinal);
			Assert.assertEquals(ordinal, clause.get("ordinal"));
			Assert.assertEquals(realization.supportClauses().get(ordinal).normalizedSignature(),
				clause.get("clauseIdentity"));
			Assert.assertEquals("ABSENT", ((Map<?,?>) clause.get("workerPoolAuthority")).get("kind"));
			Assert.assertTrue(((List<?>) clause.get("requiredInputSupport")).isEmpty());
			Assert.assertTrue(((List<?>) clause.get("inputBindings")).isEmpty());
		}
		CandidateRealizationSupportClause duplicate = realization.supportClauses().get(0);
		IllegalArgumentException duplicateClause = Assert.assertThrows(IllegalArgumentException.class,
			() -> new CandidateEmissionRealization(realization.key(), List.of(duplicate, duplicate)));
		Assert.assertTrue(duplicateClause.getMessage().contains("Duplicate realization support clause"));
		IllegalStateException duplicateReference = Assert.assertThrows(IllegalStateException.class,
			() -> PlanningNativeModelCapture.candidateRealizationClauseInventory(
				List.of(reversed, reversed)));
		Assert.assertTrue(duplicateReference.getMessage().contains(
			"Duplicate candidate realization clause authority"));
	}

	@Test public void unrecognizedFederatedSourceCannotAcquireAnonymousPrivacy() {
		DataOp source = new DataOp("unregistered", DataType.MATRIX, ValueType.FP64,
			OpOpData.FEDERATED, new LinkedHashMap<>());
		try {
			PlanningNativeModelCapture.registerSource(source,
				Map.of("X", new PrebuilderSnapshot.ExternalSource("worker/X", "PRIVATE_AGGREGATE", "ROW")),
				new LinkedHashMap<>(), new LinkedHashSet<>(),
				java.util.Collections.newSetFromMap(new IdentityHashMap<Hop,Boolean>()));
			Assert.fail("An unpinned federated source must fail closed");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("Unrecognized source HOP"));
		}
	}

	@Test public void p2RequiresPinnedMetadataReleaseJvmProperty() throws Exception {
		Assume.assumeTrue(Files.isRegularFile(CATALOG) && Files.isDirectory(EVALUATION));
		String property = "sysds.privacy.allowPublicRecodeMetadata";
		String prior = System.getProperty(property);
		String cell = cellId("planning-w1:P2_PREP", "lan");
		try {
			System.clearProperty(property);
			Map<String,Object> missing = PlanningNativeModelCapture.captureResult(CATALOG, EVALUATION,
				cell, false);
			Assert.assertEquals("ERROR", missing.get("status"));
			Assert.assertTrue(((String) missing.get("error")).contains("Frozen workload JVM property differs"));
			Assert.assertFalse(missing.containsKey("radices"));
			System.setProperty(property, "true");
			Map<String,Object> captured = PlanningNativeModelCapture.captureResult(CATALOG, EVALUATION,
				cell, false);
			Assert.assertEquals(captured.toString(), "COMPLETE", captured.get("status"));
			Assert.assertEquals(Map.of(property, "true"), captured.get("workloadJvmProperties"));
		}
		finally {
			if(prior == null) System.clearProperty(property);
			else System.setProperty(property, prior);
		}
	}

	@Test public void unknownCellProducesMachineReadableErrorWithoutEmptyDomainFallback() {
		Map<String,Object> capture = PlanningNativeModelCapture.captureResult(CATALOG, EVALUATION,
			"missing-planning-cell", false);
		Assert.assertEquals("closed-native-model-capture-v1", capture.get("schema"));
		Assert.assertEquals("ERROR", capture.get("status"));
		Assert.assertEquals("java.lang.IllegalArgumentException", capture.get("errorClass"));
		Assert.assertFalse(capture.containsKey("radices"));
		Assert.assertFalse(((List<?>) capture.get("stackTrace")).isEmpty());
	}

	@Test public void promotedCompileConditionAcceptsSemicolonAndPreparesPinnedSourceFacts() throws Exception {
		Path root = Files.createTempDirectory("promoted-capture-input-");
		String property = "sysds.test.promoted.capture";
		String prior = System.getProperty(property);
		ExecMode priorExecMode = DMLScript.getGlobalExecMode();
		int priorSeed = DMLScript.SEED;
		boolean priorStats = DMLScript.STATISTICS;
		int priorStatsCount = DMLScript.STATISTICS_COUNT;
		boolean priorNoFedConversion = FEDInstructionUtils.noFedRuntimeConversion;
		try {
			Path program = root.resolve("frozen/program.dml");
			Files.createDirectories(program.getParent());
			String script = "X = federated(addresses=list(\"worker1/data/X\"), "
				+ "ranges=list(list(0,0),list(10,2)));\nprint(sum(X))\n";
			Files.writeString(program, script);
			String programSha = sha(script);
			Map<String,Object> planned = Map.of("workers", 1,
				"network", Map.of("cost_environment", Map.of("SYSDS_FED_COST_NET_BW", "2")),
				"case", Map.of("program", "frozen/program.dml", "program_sha256", programSha,
					"arguments", Map.of(), "imports", Map.of(), "compileLocalArguments", Map.of(),
					"federatedSources", List.of(
						Map.of("variable", "X", "origins", List.of("worker1/data/X"),
							"ranges", List.of(List.of(0, 0), List.of(10, 2)),
							"privacy", "PRIVATE_AGGREGATE", "federationType", "ROW"))));
			Map<String,Object> binding = Map.of("kind", "campaign-compile-condition",
				"conditionStatus", "SNAPSHOT_ONLY", "conditionSha256", "condition",
				"plannedCondition", planned,
				"compilerArgv", List.of("-exec", "singlenode", "-seed", "7",
					"-noFedRuntimeConversion", "-stats", "100"),
				"workloadJvmOptions", List.of("-D" + property + "=true"));
			Map<String,Object> cell = Map.of("id", "promoted", "inventoryStatus", "IN_SCOPE",
				"sourceFiles", Map.of("frozen/program.dml", programSha), "sourceBinding", binding);
			Path catalog = root.resolve("catalog.json");
			new ObjectMapper().writeValue(catalog.toFile(), Map.of("cells", List.of(cell)));
			System.setProperty(property, "true");
			var input = PlanningNativeModelCapture.prepareInput(catalog, root, "promoted", false);
			Assert.assertEquals(programSha, input.programSha256());
			Assert.assertEquals(Map.of(property, "true"), input.workloadJvmProperties());
			Assert.assertEquals(Map.of("SYSDS_FED_COST_NET_BW", "2"), input.networkEnvironment());
			Assert.assertEquals(1, input.finalSources().size());
			Assert.assertEquals(List.of("-exec", "singlenode", "-seed", "7",
				"-noFedRuntimeConversion", "-stats", "100"), input.compilerArgv());
			Assert.assertEquals("SINGLE_NODE", input.compilerConfiguration().get("execMode"));
			Assert.assertEquals(7, input.compilerConfiguration().get("seed"));
			Assert.assertEquals("PARSE_VALIDATE_CONSTRUCT_REWRITE",
				input.compilerConfiguration().get("appliedBefore"));
			Assert.assertEquals(ExecMode.SINGLE_NODE, DMLScript.getGlobalExecMode());
			Assert.assertEquals(7, DMLScript.SEED);
			Assert.assertTrue(DMLScript.STATISTICS);
			Assert.assertEquals(100, DMLScript.STATISTICS_COUNT);
			Assert.assertTrue(FEDInstructionUtils.noFedRuntimeConversion);
		}
		finally {
			if(prior == null) System.clearProperty(property);
			else System.setProperty(property, prior);
			DMLScript.setGlobalExecMode(priorExecMode);
			DMLScript.SEED = priorSeed;
			DMLScript.STATISTICS = priorStats;
			DMLScript.STATISTICS_COUNT = priorStatsCount;
			FEDInstructionUtils.noFedRuntimeConversion = priorNoFedConversion;
		}
	}

	@Test public void promotedCompileConditionRejectsProgramSourceLiteralDrift() throws Exception {
		Path root = Files.createTempDirectory("promoted-source-drift-");
		Path catalog = promotedCatalog(root,
			"X = federated(addresses=list(\"worker2/data/X\"), ranges=list(list(0,0),list(10,2)))\n"
				+ "print(sum(X))\n",
			List.of("worker1/data/X"), List.of(List.of(0, 0), List.of(10, 2)),
			List.of("-exec", "singlenode", "-seed", "7", "-noFedRuntimeConversion", "-stats", "100"));
		try {
			PlanningNativeModelCapture.prepareInput(catalog, root, "promoted", false);
			Assert.fail("A program/source address mismatch must fail closed");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("literal differs"));
		}
	}

	@Test public void promotedCompileConditionRejectsUnsupportedCompilerArgv() throws Exception {
		Path root = Files.createTempDirectory("promoted-compiler-argv-");
		Path catalog = promotedCatalog(root,
			"X = federated(addresses=list(\"worker1/data/X\"), ranges=list(list(0,0),list(10,2)))\n"
				+ "print(sum(X))\n",
			List.of("worker1/data/X"), List.of(List.of(0, 0), List.of(10, 2)),
			List.of("-exec", "hybrid", "-seed", "7", "-noFedRuntimeConversion", "-stats", "100"));
		try {
			PlanningNativeModelCapture.prepareInput(catalog, root, "promoted", false);
			Assert.fail("An unsupported compiler argv must fail closed");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("supports only -exec singlenode"));
		}
	}

	private static String cellId(String discovery, String condition) throws Exception {
		JsonNode rows = new ObjectMapper().readTree(CATALOG.toFile()).path("cells");
		for(JsonNode row : rows)
			if(discovery.equals(row.path("discoveryId").asText())
				&& condition.equals(row.path("conditionId").asText()))
				return row.path("id").asText();
		throw new IllegalArgumentException("Planning test cell absent");
	}

	private static String sha(String value) throws Exception {
		return sha(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha(byte[] value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static void assertArtifactMutationRejected(JsonNode original,
		Consumer<ObjectNode> mutation, String expectedMessage) throws Exception {
		ObjectNode mutated = original.deepCopy();
		mutation.accept(mutated);
		ObjectMapper mapper = new ObjectMapper()
			.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		byte[] domain = mapper.writeValueAsBytes(mutated.path("nativeDomain"));
		((ObjectNode) mutated.path("summary")).put("nativeDomainSha256", sha(domain));
		byte[] plain = mapper.writeValueAsBytes(mutated);
		Path artifact = Files.createTempFile("mutated-planning-native-model-", ".json.gz");
		try {
			try(GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(artifact))) {
				gzip.write(plain);
			}
			IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
				() -> PlanningNativeModelCapture.verifyArtifact(artifact, sha(plain)));
			Assert.assertTrue(error.getMessage(), error.getMessage().contains(expectedMessage));
		}
		finally {
			Files.deleteIfExists(artifact);
		}
	}

	private static CandidateRuleFact supportClauseFact(String first, String second) {
		ControlRegionKey region = new ControlRegionKey(
			"capture-clause", "main", List.of("main"), "main", "compiled");
		CompiledHopKey owner = new CompiledHopKey("capture-clause", "main", "main",
			"compiled", region, "owner", "owner");
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState state = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		CandidateRealizationSupportClause left = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, owner, first)), List.of());
		CandidateRealizationSupportClause right = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, owner, second)), List.of());
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementRealizationKey.local(state), List.of(left, right));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			state, null, null, List.of(realization));
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), List.of(emission), "");
	}

	private static Path promotedCatalog(Path root, String script, List<String> origins,
		List<List<Integer>> ranges, List<String> compilerArgv) throws Exception {
		Path program = root.resolve("frozen/program.dml");
		Files.createDirectories(program.getParent());
		Files.writeString(program, script);
		String programSha = sha(script);
		Map<String,Object> source = Map.of("variable", "X", "origins", origins, "ranges", ranges,
			"privacy", "PRIVATE_AGGREGATE", "federationType", "ROW");
		Map<String,Object> testCase = Map.of("program", "frozen/program.dml",
			"program_sha256", programSha, "arguments", Map.of(), "imports", Map.of(),
			"compileLocalArguments", Map.of(), "federatedSources", List.of(source));
		Map<String,Object> planned = Map.of("workers", 1,
			"network", Map.of("cost_environment", Map.of("SYSDS_FED_COST_NET_BW", "2")),
			"case", testCase);
		Map<String,Object> binding = Map.of("kind", "campaign-compile-condition",
			"conditionStatus", "SNAPSHOT_ONLY", "conditionSha256", "condition",
			"plannedCondition", planned, "compilerArgv", compilerArgv, "workloadJvmOptions", List.of());
		Map<String,Object> cell = Map.of("id", "promoted", "inventoryStatus", "IN_SCOPE",
			"sourceFiles", Map.of("frozen/program.dml", programSha), "sourceBinding", binding);
		Path catalog = root.resolve("catalog.json");
		new ObjectMapper().writeValue(catalog.toFile(), Map.of("cells", List.of(cell)));
		return catalog;
	}
}
