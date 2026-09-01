/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.functions.federated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

public class FederatedForcedStateAuditRunnerSelectionTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void baseMethodEnumeratesLeavesAndExactCaseSelectsOne() {
		List<Description> cases = FederatedForcedStateAuditRunnerTest.matchingTestCases(
			ParameterizedFixture.class, "target");
		assertEquals(2, cases.size());
		String exactCase = cases.get(0).getMethodName();
		assertEquals(1, FederatedForcedStateAuditRunnerTest.matchingTestCases(
			ParameterizedFixture.class, exactCase).size());
	}

	@Test
	public void exactDiscoveryRunnerPropagatesOneParameterLeaf() throws Exception {
		List<Description> cases = FederatedForcedStateAuditRunnerTest.matchingTestCases(
			ParameterizedFixture.class, "target");
		String invocation = ParameterizedFixture.class.getName() + '#'
			+ cases.get(1).getMethodName();
		ParameterizedFixture.observedInvocations.clear();
		System.setProperty(FederatedPlannerAuditDiscoveryRunnerTest.INVOCATION_PROPERTY,
			invocation);
		try {
			new FederatedPlannerAuditDiscoveryRunnerTest().discoverExactLeaf();
		}
		finally {
			System.clearProperty(
				FederatedPlannerAuditDiscoveryRunnerTest.INVOCATION_PROPERTY);
		}
		assertEquals(List.of(invocation), ParameterizedFixture.observedInvocations);
	}

	@Test
	public void invocationUsesFirstSeparatorAndPreservesParameterDisplayHash() {
		String className = ParameterizedFixture.class.getName();
		FederatedForcedStateAuditRunnerTest.InvocationIdentity identity =
			FederatedForcedStateAuditRunnerTest.parseInvocation(
				className + "#target[0:value#fragment]", "test invocation");
		assertEquals(className, identity.className());
		assertEquals("target[0:value#fragment]", identity.methodName());
	}

	@Test
	public void invocationRejectsMalformedClassAndMissingMethod() {
		assertThrows(IllegalArgumentException.class, () ->
			FederatedForcedStateAuditRunnerTest.parseInvocation("not a class#target[0]", "test"));
		assertThrows(IllegalArgumentException.class, () ->
			FederatedForcedStateAuditRunnerTest.parseInvocation(
				ParameterizedFixture.class.getName() + '#', "test"));
	}

	@Test
	public void strictReplayRequiresExactInvocationWithoutLegacyFallback() {
		Map<String,Object> exactLeaf = target("fedplanner-forced-state-manifest-v1");
		exactLeaf.put("exactReplayLeaf", true);
		assertThrows(IllegalArgumentException.class, () ->
			FederatedForcedStateAuditRunnerTest.requiredReplayInvocation(exactLeaf));

		Map<String,Object> strictSchema = target("fedplanner-forced-state-manifest-v2");
		strictSchema.put("exactReplayLeaf", false);
		assertThrows(IllegalArgumentException.class, () ->
			FederatedForcedStateAuditRunnerTest.requiredReplayInvocation(strictSchema));

		Map<String,Object> legacy = target("fedplanner-forced-state-manifest-v1");
		legacy.put("exactReplayLeaf", false);
		assertFalse(FederatedForcedStateAuditRunnerTest.requiresExactReplay(legacy));
		assertEquals(null,
			FederatedForcedStateAuditRunnerTest.requiredReplayInvocation(legacy));

		strictSchema.put("replayInvocation",
			ParameterizedFixture.class.getName() + "#target[0:left]");
		assertTrue(FederatedForcedStateAuditRunnerTest.requiresExactReplay(strictSchema));
		assertEquals(strictSchema.get("replayInvocation"),
			FederatedForcedStateAuditRunnerTest.requiredReplayInvocation(strictSchema));
	}

	@Test
	public void strictManifestFailsBeforeAnyParameterizedSiblingCanRun() throws Exception {
		Path manifest = temporaryFolder.newFile("manifest.jsonl").toPath();
		Path output = temporaryFolder.newFolder("campaign").toPath();
		Files.writeString(manifest, "{\"schema\":\"fedplanner-forced-state-manifest-v1\","
			+ "\"targetId\":\"0123456789abcdef\",\"exactReplayLeaf\":true,"
			+ "\"replayContext\":\"" + ParameterizedFixture.class.getName()
			+ "#target\"}\n");
		System.setProperty(FederatedForcedStateAuditRunnerTest.MANIFEST_PROPERTY,
			manifest.toString());
		System.setProperty(FederatedForcedStateAuditRunnerTest.OUTPUT_PROPERTY,
			output.toString());
		try {
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> new FederatedForcedStateAuditRunnerTest().replayManifestShard());
			assertTrue(failure.getMessage().contains("missing replayInvocation"));
		}
		finally {
			System.clearProperty(FederatedForcedStateAuditRunnerTest.MANIFEST_PROPERTY);
			System.clearProperty(FederatedForcedStateAuditRunnerTest.OUTPUT_PROPERTY);
		}
	}

	@Test
	public void targetIdAndOutputBoundaryAreFailClosed() {
		Path campaign = Path.of("build", "campaign", "..", "campaign");
		Path output = FederatedForcedStateAuditRunnerTest.targetOutput(
			campaign, "0123456789abcdef");
		Path targets = campaign.toAbsolutePath().normalize().resolve("targets");
		assertEquals(targets, output.getParent());
		assertTrue(output.startsWith(targets));
		for(String invalid : List.of("0123456789abcde", "0123456789ABCDE0",
			"0123456789abcdef0", "../0123456789abcd"))
			assertThrows(IllegalArgumentException.class, () ->
				FederatedForcedStateAuditRunnerTest.targetOutput(campaign, invalid));
	}

	private static Map<String,Object> target(String schema) {
		Map<String,Object> target = new LinkedHashMap<>();
		target.put("schema", schema);
		return target;
	}

	@RunWith(Parameterized.class)
	public static class ParameterizedFixture {
		private static final List<String> observedInvocations = new ArrayList<>();
		@Parameters(name = "{index}:{0}")
		public static Iterable<Object[]> parameters() {
			return List.of(new Object[] {"left"}, new Object[] {"right#fragment"});
		}

		@SuppressWarnings("unused")
		public ParameterizedFixture(String value) {
			// parameter identity only
		}

		@Test
		public void target() {
			String invocation = PlannerCandidateSpaceAudit.currentAuditInvocation();
			if(invocation != null)
				observedInvocations.add(invocation);
		}
	}
}
