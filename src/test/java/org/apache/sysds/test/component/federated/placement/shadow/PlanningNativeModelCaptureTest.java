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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
		Path artifact = Files.createTempFile("planning-native-model-", ".json.gz");
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
			Assert.assertEquals("closed-native-model-artifact-v1", saved.path("schema").asText());
			Assert.assertEquals(cell, saved.path("summary").path("cell").asText());
			Assert.assertEquals("PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
				saved.path("acceptance").asText());
			Assert.assertEquals(((Number) capture.get("placementCoordinates")).intValue(),
				saved.path("nativeDomain").path("placementDomains").size());
			Assert.assertEquals(((Number) capture.get("candidateCoordinates")).intValue(),
				saved.path("nativeDomain").path("candidateDomains").size());
			Assert.assertEquals(((Number) capture.get("relocationCoordinates")).intValue(),
				saved.path("nativeDomain").path("relocationDomains").size());
			Assert.assertEquals(((Number) capture.get("prebuilderNodes")).intValue(),
				saved.path("preRewriteGraph").path("nodes").size());
			Assert.assertEquals(((Number) capture.get("finalHopNodes")).intValue(),
				saved.path("finalHopGraph").path("nodes").size());
			Assert.assertEquals(((Number) capture.get("artifactBytes")).intValue(), plain.length);
			Assert.assertEquals(64, ((String) capture.get("artifactSha256")).length());
			Assert.assertEquals("STRUCTURE_VERIFIED", PlanningNativeModelCapture.verifyArtifact(
				artifact, (String) capture.get("artifactSha256")).get("status"));
			try {
				PlanningNativeModelCapture.verifyArtifact(artifact, "0".repeat(64));
				Assert.fail("A wrong artifact digest must fail closed");
			}
			catch(IllegalArgumentException expected) {
				Assert.assertTrue(expected.getMessage().contains("digest differs"));
			}
		}
		finally {
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

	private static String cellId(String discovery, String condition) throws Exception {
		JsonNode rows = new ObjectMapper().readTree(CATALOG.toFile()).path("cells");
		for(JsonNode row : rows)
			if(discovery.equals(row.path("discoveryId").asText())
				&& condition.equals(row.path("conditionId").asText()))
				return row.path("id").asText();
		throw new IllegalArgumentException("Planning test cell absent");
	}
}
