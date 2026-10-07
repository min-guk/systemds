/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlannerCandidateSpaceAuditEncodingTest {
	@Rule public final TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void replayControlPathsPreserveLegacyNumericSegmentSemantics() {
		for(String path : List.of("", "/", "//", "12", "/12/", "main/5/branch-if/0",
			"main/12x/000/٣/１２/-1/+2/3.0/", "1\n/\t2/한글/3", "000000000000000000000"))
			assertLegacyPath(path);
		java.util.Random random = new java.util.Random(42);
		String alphabet = "/001239ab-+\n\t٣１２한글";
		for(int sample = 0; sample < 1_000; sample++) {
			StringBuilder path = new StringBuilder();
			for(int remaining = random.nextInt(100); remaining > 0; remaining--)
				path.append(alphabet.charAt(random.nextInt(alphabet.length())));
			assertLegacyPath(path.toString());
		}
	}

	private static void assertLegacyPath(String path) {
		String expected = String.join("/", Arrays.stream(path.split("/", -1))
			.map(segment -> segment.matches("[0-9]+") ? "*" : segment).toList());
		Assert.assertEquals(expected, PlannerCandidateSpaceAudit.normalizeReplayControlPath(path));
	}

	@Test
	public void appendPreservesJsonlBytesAndRecordBoundaries() throws Exception {
		String previous = System.getProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
		Path directory = temporary.newFolder("audit").toPath();
		try {
			System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, directory.toString());
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("schema", "encoding-regression");
			row.put("signature", ("quote\" slash\\ newline\n tab\t\u0000 한글 🙂 ").repeat(4_000));
			row.put("values", Arrays.asList(null, true, 0, 1.25, Double.POSITIVE_INFINITY));
			Map<String,Object> nested = new LinkedHashMap<>();
			nested.put("present", null);
			nested.put("states", List.of("CP", "FED"));
			row.put("nested", nested);
			List<Map<String,Object>> rows = List.of(row, Map.of("empty", List.of()));
			Method append = PlannerCandidateSpaceAudit.class.getDeclaredMethod("append", List.class);
			append.setAccessible(true);
			append.invoke(null, rows);
			append.invoke(null, List.of());
			append.invoke(null, List.of(row));
			ObjectMapper oracle = new ObjectMapper();
			String expected = oracle.writeValueAsString(row) + System.lineSeparator()
				+ oracle.writeValueAsString(rows.get(1)) + System.lineSeparator()
				+ oracle.writeValueAsString(row) + System.lineSeparator();
			Path output = directory.resolve("candidate-space-" + ProcessHandle.current().pid() + ".jsonl");
			Assert.assertArrayEquals("JSONL appends must retain exact escaping, ordering and delimiters",
				expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output));
			Assert.assertEquals(3, Files.readAllLines(output, StandardCharsets.UTF_8).size());
		}
		finally {
			if(previous == null)
				System.clearProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
			else
				System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, previous);
		}
	}
}
