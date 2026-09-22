/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** An opt-in sibling-repository smoke check of the frozen planning DML compiler boundary. */
public class PlanningTemplateSnapshotSmokeTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Set<String> EXPECTED_CASES = Set.of("als", "glm", "gmm-vvi", "gnmf", "kmeans",
		"l2svm", "lm", "logreg", "pca", "P1_FULL", "P2_PREP", "sliceline-adult",
		"sliceline-covtype", "steplm");
	private static final Pattern SOURCE = Pattern.compile("source\\(\"([^\"]+)\"\\)");
	private static final Pattern FEDERATED = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*"
		+ "federated\\(addresses=list\\(([^\\n]+?)\\),\\s*ranges=");
	private static final Pattern ADDRESS = Pattern.compile("\"([^\"]+)\"");

	@Test public void frozenProfileTemplatesReachRawPrebuilderSnapshot() throws Exception {
		Path root = templateRoot();
		Assume.assumeTrue("Optional frozen planning template tree is absent: " + root,
			Files.isDirectory(root) && Files.isRegularFile(root.resolve("SHA256SUMS")));
		Map<String, String> hashes = frozenHashes(root);
		List<String> failures = new ArrayList<>();
		int captured = 0;
		int templatesWithUnknownCallNames = 0;
		int callsWithUnknownNames = 0;
		for(int workers : new int[] {1, 3, 5, 7}) {
			JsonNode cases = JSON.readTree(root.resolveSibling("context-w" + workers + ".json").toFile())
				.path("cases");
			Assert.assertEquals("Unexpected planning case count at w" + workers, 14, cases.size());
			Set<String> caseNames = new HashSet<>();
			for(JsonNode testCase : cases)
				Assert.assertTrue("Duplicate planning case at w" + workers,
					caseNames.add(testCase.path("workload").asText()));
			Assert.assertEquals("Unexpected planning case names at w" + workers, EXPECTED_CASES, caseNames);
			for(JsonNode testCase : cases) {
				String relative = "w" + workers + "/" + testCase.path("program").asText();
				try {
					Path path = root.resolve(relative);
					String script = Files.readString(path, StandardCharsets.UTF_8);
					Assert.assertEquals("Worker context mismatch: " + relative, workers,
						testCase.path("workers").asInt(-1));
					Assert.assertEquals("Context program hash mismatch: " + relative,
						testCase.path("program_sha256").asText(), sha256(script.getBytes(StandardCharsets.UTF_8)));
					Assert.assertEquals("Staged DML differs from frozen SHA list: " + relative,
						hashes.get(relative), sha256(script.getBytes(StandardCharsets.UTF_8)));
					Map<String, PrebuilderSnapshot.ExternalSource> sourceFacts = sourceFacts(relative, script, testCase,
						workers);
					String resolved = resolveImports(root, script, hashes);
					DMLProgram program = ParserFactory.createParser().parse(path.toString(), resolved, new HashMap<>());
					DMLTranslator translator = new DMLTranslator(program);
					translator.liveVariableAnalysis(program);
					translator.validateParseTree(program);
					translator.constructHops(program);
					Map<Long, PrebuilderSnapshot.ExternalSource> sources = locateSources(program, sourceFacts);
					PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
					PrebuilderSnapshotVerifier.assertMatches(program, snapshot);
					Assert.assertFalse("No HOPs: " + relative, snapshot.nodes().isEmpty());
					Assert.assertEquals("Some source statements were lost: " + relative,
						sourceFacts.size(), sources.size());
					int unknownCalls = (int) snapshot.calls().stream().filter(call ->
						call.inputNames() == null || call.outputNames() == null).count();
					if(unknownCalls > 0) templatesWithUnknownCallNames++;
					callsWithUnknownNames += unknownCalls;
					captured++;
				}
				catch(Exception | AssertionError ex) {
					failures.add(relative + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage()
						+ " at " + ex.getStackTrace()[0]);
				}
			}
		}
		Assert.assertTrue("Prebuilder snapshot failures (" + captured + "/56 captured):\n"
			+ String.join("\n", failures), failures.isEmpty());
		Assert.assertEquals(56, captured);
		System.out.println("Planning template raw snapshots: captured=" + captured
			+ ", templatesWithUnknownCallNames=" + templatesWithUnknownCallNames
			+ ", callsWithUnknownNames=" + callsWithUnknownNames);
	}

	private static Path templateRoot() {
		String configured = System.getProperty("plan.space.planning.templates");
		if(configured != null) return Path.of(configured).toAbsolutePath().normalize();
		return Path.of("../cofee-evaluation/planning_study/native/input_templates")
			.toAbsolutePath().normalize();
	}

	private static Map<String, String> frozenHashes(Path root) throws Exception {
		Map<String, String> hashes = new HashMap<>();
		for(String line : Files.readAllLines(root.resolve("SHA256SUMS"), StandardCharsets.UTF_8)) {
			int split = line.indexOf("  ");
			if(split > 0) hashes.put(line.substring(split + 2), line.substring(0, split));
		}
		return hashes;
	}

	private static String resolveImports(Path root, String script,
		Map<String, String> hashes) throws Exception {
		Matcher matcher = SOURCE.matcher(script);
		StringBuffer resolved = new StringBuffer();
		while(matcher.find()) {
			String relative = "common/" + matcher.group(1);
			Path imported = root.resolve(relative).normalize();
			if(!imported.startsWith(root) || !Files.isRegularFile(imported))
				throw new IllegalArgumentException("Unresolved source import: " + relative);
			Assert.assertEquals("Imported source differs from frozen SHA list: " + relative,
				hashes.get(relative), sha256(Files.readAllBytes(imported)));
			matcher.appendReplacement(resolved, Matcher.quoteReplacement("source(\"" + imported + "\")"));
		}
		matcher.appendTail(resolved);
		return resolved.toString();
	}

	private static Map<String, PrebuilderSnapshot.ExternalSource> sourceFacts(String relative,
		String script, JsonNode testCase, int workers) {
		Map<String, PrebuilderSnapshot.ExternalSource> result = new HashMap<>();
		Matcher statement = FEDERATED.matcher(script);
		while(statement.find()) {
			String variable = statement.group(1);
			String metadataKey = variable.equals("X") || variable.equals("Xraw") ? "X" :
				variable.equals("Y") || variable.equals("y") || variable.equals("e") ? "Y" : "";
			if(metadataKey.isEmpty() || !testCase.path("metadata").has(metadataKey))
				throw new IllegalArgumentException("No context metadata for source variable " + variable);
			Matcher addresses = ADDRESS.matcher(statement.group(2));
			List<String> origins = new ArrayList<>();
			while(addresses.find()) origins.add(addresses.group(1));
			if(origins.size() != workers)
				throw new IllegalArgumentException("Address count for " + variable + " differs from workers=" + workers);
			String privacy = testCase.path("metadata").path(metadataKey).path("privacy").asText()
				.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
			if(!privacy.equals("PUBLIC") && !privacy.equals("PRIVATE_AGGREGATE"))
				throw new IllegalArgumentException("Unknown frozen privacy for " + variable + ": " + privacy);
			if(result.put(variable, new PrebuilderSnapshot.ExternalSource(
				relative + ":" + String.join(",", origins), privacy, "ROW")) != null)
				throw new IllegalArgumentException("Duplicate source variable " + variable);
		}
		if(result.isEmpty()) throw new IllegalArgumentException("No literal federated statements");
		return result;
	}

	private static Map<Long, PrebuilderSnapshot.ExternalSource> locateSources(DMLProgram program,
		Map<String, PrebuilderSnapshot.ExternalSource> sourceFacts) {
		Map<Long, PrebuilderSnapshot.ExternalSource> result = new HashMap<>();
		Set<Hop> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		program.getStatementBlocks().forEach(block -> {
			if(block.getHops() != null)
				for(Hop root : block.getHops()) walk(root, sourceFacts, result, visited);
		});
		if(result.size() != sourceFacts.size())
			throw new IllegalArgumentException("Federated source HOP count " + result.size()
				+ " differs from source statement count " + sourceFacts.size());
		return result;
	}

	private static void walk(Hop hop, Map<String, PrebuilderSnapshot.ExternalSource> facts,
		Map<Long, PrebuilderSnapshot.ExternalSource> result, Set<Hop> visited) {
		if(!visited.add(hop)) return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			PrebuilderSnapshot.ExternalSource source = facts.get(data.getName());
			if(source == null) throw new IllegalArgumentException("Unknown federated source " + data.getName());
			result.put(data.getHopID(), source);
		}
		for(Hop input : hop.getInput()) walk(input, facts, result, visited);
	}

	private static String sha256(byte[] bytes) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder hex = new StringBuilder(64);
		for(byte value : digest) hex.append(String.format("%02x", value & 0xff));
		return hex.toString();
	}
}
