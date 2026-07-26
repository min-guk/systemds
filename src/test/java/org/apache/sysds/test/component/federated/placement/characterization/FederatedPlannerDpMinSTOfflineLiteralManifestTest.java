/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.characterization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.LegacyDpOfflineSelectedCapture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.LegacyMinstOfflineSelectedCapture;
import org.junit.Test;

/** Frozen offline literal selected-plan authority for the DP and MinST adapter cutover. */
public class FederatedPlannerDpMinSTOfflineLiteralManifestTest {
	private static final String BASE = "5e4253ac87bed98e951054cf586a22a2784779e9";
	private static final String ROOT = "/org/apache/sysds/test/component/federated/placement/characterization/";
	private static final String SPEC = ROOT + "g004b-c2-offline-fixture-spec.tsv";
	private static final String MANIFEST = ROOT + "g004b-c2-dp-minst-offline-literal.manifest";
	private static final String DIGEST = MANIFEST + ".sha256";

	@Test
	public void literalManifestMatchesExactOfflineLegacySelection() throws Exception {
		String actual = capture();
		String output = System.getProperty("g004b.captureLegacy");
		if(output != null) {
			Files.writeString(Path.of(output), actual, StandardCharsets.UTF_8);
			return;
		}
		String expected = resource(MANIFEST);
		assertEquals(resource(DIGEST).trim(), sha256(expected));
		assertEquals(expected, actual);
	}

	@Test
	public void dpOnlyFrozenManifestIsolatedAndComplete() throws Exception {
		String expected = resource(MANIFEST);
		assertEquals(resource(DIGEST).trim(), sha256(expected));
		List<String> frozen = new ArrayList<>();
		for(String line : expected.split("\\R"))
			if(line.contains("|planner=DP|")) frozen.add(line.replace("|planner=DP|", "|"));
		List<String> actual = new ArrayList<>();
		for(String row : LegacyDpOfflineSelectedCapture.capture()) actual.add(tagPlanner(row, "DP").replace("|planner=DP|", "|"));
		Collections.sort(frozen); Collections.sort(actual);
		assertEquals(frozen, actual);
		for(String token : List.of("DP_ROOT_OBJECTIVE", "rootChildren=", "selectedStates=", "selectedPlans=", "semanticFacts=", "registrySnapshots="))
			assertTrue("DP frozen manifest missing " + token, frozen.stream().anyMatch(r -> r.contains(token)));
		assertTrue(frozen.stream().anyMatch(r -> r.startsWith("C2-DP-01-ROOT-EQUAL-LOUT|")
			&& r.contains("selected=LOUT") && r.contains("tieRule=LOUT_LE_FOUT")));
	}

	private static String capture() throws Exception {
		String spec = resource(SPEC);
		assertEquals("d6b14149a4413c82b5c62f04bcbef4043380cb931b916a0c1196d45e0f806e4f",
			sha256(spec));
		List<String> rows = new ArrayList<>();
		rows.add("SCHEMA|g004b-c2-offline-selected-plan-v1");
		rows.add("BASE|" + BASE);
		rows.add("SPEC_SHA256|" + sha256(spec));
		rows.add("EVIDENCE|ACTUAL_RETAINED|EXACT_PRIVATE_REPLAY|SYNTHETIC_SELECTOR_FIXTURE|NEUTRAL_GRAPH_EXCLUSION");
		for(String row : LegacyDpOfflineSelectedCapture.capture()) rows.add(tagPlanner(row, "DP"));
		for(String row : LegacyMinstOfflineSelectedCapture.capture()) rows.add(tagPlanner(row, "MINST"));
		Set<String> observed = new LinkedHashSet<>();
		for(String row : rows) {
			if(row.contains("|planner=DP|")) observed.add(row.split("\\|", 2)[0] + "|DP");
			if(row.contains("|planner=MINST|")) observed.add(row.split("\\|", 2)[0] + "|MINST");
		}
		Set<String> expectedPairs = new LinkedHashSet<>();
		for(String line : spec.split("\\R")) {
			if(line.isBlank()) continue;
			String[] fields = line.split("\\|", 4);
			if(fields[1].equals("BOTH")) {
				expectedPairs.add(fields[0] + "|DP"); expectedPairs.add(fields[0] + "|MINST");
			}
			else expectedPairs.add(fields[0] + "|" + fields[1]);
		}
		assertEquals("Every declared fixture/planner pair must be explicitly exercised", expectedPairs, observed);
		assertFixture(rows, "C2-DP-05-SHARED-DIAMOND", "DP", "classification=ACTUAL_ALL_LOCAL_SHARED_GRAPH",
			"allCpLout=true", "registrySnapshots=[NONE{reason=NO_SELECTED_MATERIALIZATION}]",
			"observedFloatNormalization=DECIMAL_SIGNIFICANT_12_HALF_EVEN");
		assertFixture(rows, "C2-DP-06-TRTW-EXACT", "DP", "classification=ACTUAL_TRTW_ALL_LOCAL",
			"reads=[", "writes=[", "sameVariables=true");
		assertFixture(rows, "C2-MS-02-CAPS-FIXPOINT", "MINST", "capabilityGateApplied=true",
			"finalExec=FED", "finalOutput=FOUT");
		assertFixture(rows, "C2-MS-03-SHARED-DOWNLOAD", "MINST", "evidence=EXACT_PRIVATE_REPLAY",
			"consumerCount=2", "source=FROZEN_SELECTED_D");
		assertFixture(rows, "C2-MS-04-ANCHORED-UPLOAD", "MINST", "evidence=EXACT_PRIVATE_REPLAY",
			"producer=64:", "source=FROZEN_SELECTED_");
		assertFixture(rows, "C2-MS-05-MISSING-ANCHOR", "MINST", "evidence=EXACT_PRIVATE_REPLAY",
			"reason=NONE");
		assertFixture(rows, "C2-MS-07-TRTW-SHARED-D", "MINST",
			"classification=ACTUAL_ALL_LOCAL_NO_TRTW_RELATION", "registry=NONE");
		assertFixture(rows, "C2-MS-08-LOOP-EQUAL-FIXPOINT", "MINST",
			"classification=ACTUAL_ALL_LOCAL_LOOP", "equalCut=NONE", "reason=NO_EQUAL_CUT_CLAIM");
		for(String planner : List.of("DP", "MINST")) {
			assertFixture(rows, "C2-X-09-BRANCH-JOIN", planner, "classification=ACTUAL_ALL_LOCAL");
			assertFixture(rows, "C2-X-10-FUNCTION-CALLSITE", planner,
				"classification=ACTUAL_CONTEXTUAL_NONE", "nodeKind=FUNCTION_BODY_NON_EMITTED",
				"emittedWork=false", "caps=NONE", "reason=NON_EMITTED_FUNCTION_BODY_CONTEXT");
			assertFixture(rows, "C2-X-11-CLONE-RECOMPILE", planner, "recompileCpFout=UNSUPPORTED",
				"reason=RECOMPILE_CP_FOUT_FORBIDDEN");
		}
		rows.subList(4, rows.size()).sort(String::compareTo);
		String manifest = String.join("\n", rows) + "\n";
		assertFalse(manifest.contains("Connection refused"));
		assertFalse(manifest.contains("WORKER_METADATA_UNAVAILABLE"));
		assertTrue(manifest.contains("finalExec=CP|finalOutput=LOUT"));
		assertTrue(manifest.contains("finalExec=CP|finalOutput=FOUT"));
		assertTrue(manifest.contains("finalExec=FED|finalOutput=LOUT"));
		assertTrue(manifest.contains("finalExec=FED|finalOutput=FOUT"));
		assertTrue(manifest.contains("REGISTRY_REFED"));
		assertTrue(manifest.contains("REGISTRY_FOUT_MATERIALIZE"));
		assertTrue(manifest.contains("REGISTRY_LOCAL_MATERIALIZE"));
		return manifest;
	}

	private static void assertFixture(List<String> rows, String id, String planner, String... required) {
		String evidence = rows.stream().filter(row -> row.startsWith(id + "|planner=" + planner + "|"))
			.reduce("", (left, right) -> left + "\n" + right);
		assertFalse("Missing fixture rows for " + id + "/" + planner, evidence.isEmpty());
		for(String token : required)
			assertTrue(id + "/" + planner + " must contain " + token, evidence.contains(token));
	}

	private static String tagPlanner(String row, String planner) {
		int split = row.indexOf('|');
		return row.substring(0, split + 1) + "planner=" + planner + "|" + row.substring(split + 1);
	}

	private static String resource(String name) throws Exception {
		try(InputStream in = FederatedPlannerDpMinSTOfflineLiteralManifestTest.class.getResourceAsStream(name)) {
			if(in == null) throw new IllegalStateException("Missing resource " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}
