/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Test;

/** Static boundary locks for the Campaign-A foundation; no planner cutover is authorized here. */
public class PlacementFoundationArchitectureGuardTest {
	private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();
	private static final Path PRODUCTION = ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner");
	private static final Path PLACEMENT = PRODUCTION.resolve("placement");
	private static final Path ORACLE = ROOT.resolve(
		"src/test/java/org/apache/sysds/test/component/federated/placement/oracle");

	@Test
	public void builderHasOneAnalysisUniverseAndLegacyBuildDelegatesToIt() throws IOException {
		String builder = read(PLACEMENT.resolve("NeutralPlacementGraphBuilder.java"));
		Assert.assertTrue("missing production analysis entry point", builder.contains("buildAnalysis("));
		Assert.assertTrue("legacy build must delegate to the single analysis path",
			builder.matches("(?s).*NeutralPlacementGraph\\s+build\\s*\\([^)]*\\)\\s*\\{[^}]*buildAnalysis\\([^)]*\\)\\.graph\\(\\).*"));
		Assert.assertEquals("only the production builder may construct the semantic graph", 1,
			javaSources(PRODUCTION).stream().filter(path -> !path.getFileName().toString().equals("NeutralPlacementGraph.java"))
				.mapToInt(path -> occurrences(uncheckedRead(path), "new NeutralPlacementGraph(")).sum());
	}

	@Test
	public void selectorBoundaryIsGraphOnlyAndContainsNoFallbackSuccessVocabulary() throws IOException {
		Path selectorRoot = PLACEMENT.resolve("selector");
		List<String> violations = new ArrayList<>();
		for(Path source : javaSources(selectorRoot)) {
			String text = read(source);
			if(text.matches("(?s).*select\\s*\\([^)]*(?:DMLProgram|Hop|StatementBlock|FederatedPlanner)[^)]*\\).*"))
				violations.add(relative(source) + ": selector consumes a planner/program/Hop universe");
			String lower = text.toLowerCase();
			for(String forbidden : List.of("timeout_success", "cap_success", "greedy_success", "approximate_success",
				"partial_success", "fallback_success", "runtime_repair"))
				if(lower.contains(forbidden))
					violations.add(relative(source) + ':' + forbidden);
		}
		Assert.assertTrue("selector boundary violations: " + violations, violations.isEmpty());
	}

	@Test
	public void productionAndIndependentOracleCannotImportEachOther() throws IOException {
		List<String> violations = new ArrayList<>();
		for(Path source : javaSources(PLACEMENT))
			if(read(source).contains("org.apache.sysds.test.component.federated.placement.oracle"))
				violations.add(relative(source) + ": production imports test oracle");
		for(Path source : javaSources(ORACLE))
			if(read(source).contains("org.apache.sysds.hops.fedplanner.placement"))
				violations.add(relative(source) + ": independent oracle imports production placement");
		Assert.assertTrue("oracle independence violations: " + violations, violations.isEmpty());
	}

	private static List<Path> javaSources(Path root) throws IOException {
		if(!Files.exists(root))
			return List.of();
		try(Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
				.sorted().collect(Collectors.toList());
		}
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private static String uncheckedRead(Path path) {
		try { return read(path); }
		catch(IOException e) { throw new IllegalStateException(e); }
	}

	private static int occurrences(String text, String token) {
		int count = 0;
		for(int i = 0; (i = text.indexOf(token, i)) >= 0; i += token.length())
			count++;
		return count;
	}

	private static String relative(Path path) {
		return ROOT.relativize(path).toString().replace('\\', '/');
	}
}
