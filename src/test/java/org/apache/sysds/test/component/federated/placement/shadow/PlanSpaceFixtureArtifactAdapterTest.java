/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlanSpaceFixtureArtifactAdapterTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	@Rule public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void protectedPFixtureProducesDeterministicNativeAudit() throws Exception {
		verify("P", "B-22", "candidateReceipts", "relocationReceipts");
	}

	@Test
	public void protectedEFixtureRetainsAlternativeAuthorityAndActions() throws Exception {
		verify("E", "B-21", "choices", "modelStatus");
	}

	private void verify(String kind, String fixture, String first, String second) throws Exception {
		BigInteger total = PlanSpaceFixtureArtifactAdapter.size(kind, fixture);
		Assert.assertTrue(total.signum() > 0);
		BigInteger stop = total.min(BigInteger.valueOf(3));
		Path root = temp.newFolder(kind).toPath();
		Path rows = root.resolve("rows.jsonl");
		Path receipt = root.resolve("receipt.json");
		Path audit = root.resolve("audit.jsonl");
		PlanSpaceFixtureArtifactAdapter.export(kind, fixture, BigInteger.ZERO, stop,
			rows, receipt, audit);
		List<String> firstRows = Files.readAllLines(rows);
		List<String> firstAudit = Files.readAllLines(audit);
		String firstReceipt = Files.readString(receipt);
		Assert.assertEquals(stop.intValueExact(), firstRows.size());
		Assert.assertEquals(firstRows.size(), firstAudit.size());
		JsonNode proof = JSON.readTree(firstReceipt);
		Assert.assertEquals("UNKNOWN", proof.get("status").asText());
		Assert.assertEquals(stop.intValueExact(), proof.get("unknown").asInt());
		Assert.assertEquals(stop.intValueExact(), proof.get("processed").asInt());
		Assert.assertEquals("UNPROVED", proof.get("physicalDecode").asText());
		Assert.assertEquals(stop.intValueExact(), proof.get("nativeEmitted").asInt()
			+ proof.get("nativeRejected").asInt() + proof.get("nativeErrors").asInt());
		Assert.assertEquals(64, proof.get("rowsSha256").asText().length());
		Assert.assertEquals(64, proof.get("auditSha256").asText().length());
		for(int index = 0; index < firstRows.size(); index++) {
			JsonNode row = JSON.readTree(firstRows.get(index));
			JsonNode auditRow = JSON.readTree(firstAudit.get(index));
			Assert.assertEquals("raw-fixture-export-v1", row.get("schema").asText());
			Assert.assertEquals(kind, row.get("kind").asText());
			Assert.assertEquals("UNKNOWN", row.get("globalCoverageStatus").asText());
			Assert.assertEquals(index, row.get("ordinal").asInt());
			Assert.assertEquals(index, auditRow.get("index").asInt());
			Assert.assertTrue(List.of("EMITTED", "REJECTED", "ERROR")
				.contains(auditRow.get("status").asText()));
			Assert.assertEquals(row, auditRow.get("raw"));
			Assert.assertTrue(row.get("identity").has(first));
			if("P".equals(kind))
				Assert.assertTrue(row.get("identity").has(second));
			else {
				Assert.assertTrue(row.get("identity").has(second));
				for(JsonNode choice : row.get("identity").get("choices")) {
					Assert.assertTrue(choice.has("authorityKind"));
					Assert.assertTrue(choice.has("relocationAction"));
					Assert.assertTrue(choice.has("derivedFoutAction"));
					Assert.assertTrue(choice.has("orderedInputs"));
				}
			}
		}
		PlanSpaceFixtureArtifactAdapter.main(new String[] {kind, fixture, "0", stop.toString(),
			rows.toString(), receipt.toString(), audit.toString()});
		Assert.assertEquals(firstRows, Files.readAllLines(rows));
		Assert.assertEquals(firstAudit, Files.readAllLines(audit));
		Assert.assertEquals(firstReceipt, Files.readString(receipt));
	}

	@Test
	public void b01NativeAuditCoversBothFiniteDomainsWithoutClaimingPhysicalEquality() throws Exception {
		for(String kind : List.of("P", "E")) {
			BigInteger total = PlanSpaceFixtureArtifactAdapter.size(kind, "B-01");
			Path root = temp.newFolder("full-" + kind).toPath();
			Path rows = root.resolve("rows.jsonl");
			Path receipt = root.resolve("receipt.json");
			Path audit = root.resolve("audit.jsonl");
			PlanSpaceFixtureArtifactAdapter.export(kind, "B-01", BigInteger.ZERO, total,
				rows, receipt, audit);
			JsonNode proof = JSON.readTree(Files.readString(receipt));
			Assert.assertEquals("COMPLETE", proof.get("nativeCoverage").asText());
			Assert.assertEquals("UNPROVED", proof.get("physicalDecode").asText());
			Assert.assertEquals(1, proof.get("nativeEmitted").asInt());
			Assert.assertEquals(0, proof.get("nativeErrors").asInt());
			Assert.assertEquals(total.intValueExact() - 1, proof.get("nativeRejected").asInt());
		}
	}
}
