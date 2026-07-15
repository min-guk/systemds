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

package org.apache.sysds.test.component.federated.placement.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator;
import org.apache.sysds.hops.fedplanner.placement.PlacementShadowCoordinator.Observation;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Test;

/** Structural guard for the planner-neutral, observational P2 shadow boundary. */
public class NeutralGraphShadowArchitectureGuardTest {
	private static final String P1_BOUNDARY = "623491db5e4c48485e83011249accc58210203d8";
	private static final String P2_PRODUCTION_BOUNDARY = "1d42bb0df7d9db64e7d1884f81fe124e7cef87d1";
	private static final Path REPOSITORY = Paths.get("").toAbsolutePath().normalize();
	private static final Path FEDPLANNER_ROOT = REPOSITORY.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner");
	private static final Path PLACEMENT_ROOT = FEDPLANNER_ROOT.resolve("placement");
	private static final Path IPA = REPOSITORY.resolve(
		"src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java");
	private static final Path ORACLE_ROOT = REPOSITORY.resolve(
		"src/test/java/org/apache/sysds/test/component/federated/placement/oracle");

	private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$]*\\b");
	private static final Pattern TYPE_DECLARATION = Pattern.compile(
		"\\b(?:class|record|interface|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
	private static final Pattern HOP_OR_TOPOLOGY_WRITE = Pattern.compile(
		"\\.\\s*(?:set[A-Z][A-Za-z0-9_]*|addInput|removeInput|removeAllChildReferences|"
			+ "replaceChildReference|replaceInput)\\s*\\(");
	private static final Pattern REGISTRY_WRITE = Pattern.compile(
		"Federated(?:Refed|FoutMaterialize|LocalMaterialize)Registry\\s*\\.\\s*"
			+ "(?:clear|register|remove|put|set)[A-Za-z0-9_]*\\s*\\(");
	private static final Pattern PLANNER_OR_RUNTIME_ACTION = Pattern.compile(
		"\\.\\s*(?:rewriteProgram|constructLops|selectPlan|emitInstruction|repairPlan|"
			+ "fallbackPlan)\\s*\\(");

	@Test
	public void productionAndTestOraclesRemainIndependent() throws IOException {
		List<String> violations = new ArrayList<>();
		for(Path source : javaSources(PLACEMENT_ROOT)) {
			String text = stripComments(read(source));
			if(text.contains("org.apache.sysds.test.component.federated.placement.oracle"))
				violations.add(relative(source) + " references a test oracle");
		}
		for(String oraclePackage : List.of("builder", "selector")) {
			for(Path source : javaSources(ORACLE_ROOT.resolve(oraclePackage))) {
				String text = stripComments(read(source));
				if(text.contains("org.apache.sysds.hops.fedplanner.placement")
					|| text.matches("(?s).*org\\.apache\\.sysds\\.hops\\.fedplanner\\."
						+ "(?:fedAll|fedHeuristic|fedCostBased)\\..*"))
					violations.add(relative(source) + " references production placement/planner output");
			}
		}
		assertTrue("P2 dependency direction violations: " + violations, violations.isEmpty());
	}

	@Test
	public void analysisCannotMutateOrExecuteAPlan() throws IOException {
		List<String> violations = new ArrayList<>();
		for(Path source : javaSources(PLACEMENT_ROOT)) {
			String executable = stripCommentsAndLiterals(read(source))
				.replace("LAST_RECORDED.set(observation)", "")
				.replace("LAST_RECORDED.set(null)", "");
			collectMatches(source, executable, HOP_OR_TOPOLOGY_WRITE, "Hop/topology write", violations);
			collectMatches(source, executable, REGISTRY_WRITE, "registry write", violations);
			collectMatches(source, executable, PLANNER_OR_RUNTIME_ACTION, "planner/runtime action", violations);
			if(executable.matches("(?s).*\\b(?:FederatedPlannerFedAll|FederatedPlannerHeuristic|"
				+ "FederatedPlannerCostbased|FederatedPlannerDp|FederatedPlannerMinST)\\b.*"))
				violations.add(relative(source) + " imports or invokes a concrete planner");
		}
		assertTrue("P2 analysis must remain read-only and planner-neutral: " + violations, violations.isEmpty());
	}

	@Test
	public void builderAndComparatorHaveNoForbiddenSuccessEscapeHatch() throws IOException {
		List<String> violations = new ArrayList<>();
		for(String name : List.of("NeutralPlacementGraphBuilder.java", "PlacementShadowComparator.java",
			"PlacementShadowCoordinator.java")) {
			Path source = PLACEMENT_ROOT.resolve(name);
			Matcher identifiers = IDENTIFIER.matcher(stripCommentsAndLiterals(read(source)));
			while(identifiers.find()) {
				String identifier = identifiers.group();
				String normalized = identifier.toLowerCase(Locale.ROOT).replace("_", "");
				if(containsAny(normalized, "timeout", "timelimit", "deadline", "statecap", "maxstates",
					"passcap", "maxpasses", "greedy", "approx", "fallback", "besteffort", "partialsuccess"))
					violations.add(name + ':' + identifier);
			}
		}
		assertTrue("A bounded/approximate/fallback path cannot count as shadow success: " + violations,
			violations.isEmpty());
	}

	@Test
	public void analysisHasNoInstanceOrEnvironmentSemanticHeuristic() throws IOException {
		Pattern forbiddenCall = Pattern.compile("(?:System\\s*\\.\\s*(?:getenv|getProperty|currentTimeMillis|nanoTime)|"
			+ "\\.\\s*(?:getHopID|getDim1|getDim2|getNnz|getNumRows|getNumColumns|getLength|getPort)\\s*\\(|"
			+ "\\b(?:Socket|ServerSocket|InetAddress|URL)\\b)");
		List<String> violations = new ArrayList<>();
		for(Path source : javaSources(PLACEMENT_ROOT)) {
			String executable = stripCommentsAndLiterals(read(source));
			collectMatches(source, executable, forbiddenCall, "instance/environment heuristic", violations);
			Matcher identifiers = IDENTIFIER.matcher(executable);
			while(identifiers.find()) {
				String normalized = identifiers.group().toLowerCase(Locale.ROOT).replace("_", "");
				if(Set.of("workload", "workloadname", "workloadid", "workercount", "numworkers",
					"rowcount", "numrows", "port", "ports", "portnumber").contains(normalized))
					violations.add(relative(source) + ':' + identifiers.group());
			}
		}
		assertTrue("P2 semantics cannot depend on runtime instance/environment heuristics: " + violations,
			violations.isEmpty());
	}

	@Test
	public void p2AddsNoDependencyDescriptor() throws Exception {
		List<String> changed = gitLines("diff", "--name-only", P1_BOUNDARY, P2_PRODUCTION_BOUNDARY);
		List<String> descriptors = changed.stream().filter(NeutralGraphShadowArchitectureGuardTest::isDependencyDescriptor)
			.collect(Collectors.toList());
		assertTrue("P2 changed dependency descriptors: " + descriptors, descriptors.isEmpty());
	}

	@Test
	public void exactlyOneNeutralPlacementGraphModelExists() throws IOException {
		List<String> graphTypes = new ArrayList<>();
		for(Path source : javaSources(FEDPLANNER_ROOT)) {
			Matcher declarations = TYPE_DECLARATION.matcher(stripCommentsAndLiterals(read(source)));
			while(declarations.find()) {
				String type = declarations.group(1);
				if(type.toLowerCase(Locale.ROOT).contains("placementgraph"))
					graphTypes.add(relative(source) + ':' + type);
			}
		}
		assertEquals("Unexpected planner-specific placement graph/preprocessing universe",
			List.of(
				"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraph.java:NeutralPlacementGraph",
				"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java:NeutralPlacementGraphBuilder",
				"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementGraphFingerprint.java:PlacementGraphFingerprint"),
			graphTypes);
	}

	@Test
	public void ipaShadowHookSurroundsButDoesNotReplacePlannerRewrite() throws IOException {
		String source = stripComments(read(IPA));
		String begin = "PlacementShadowCoordinator.begin(prog)";
		String rewrite = "planner.getPlanner().rewriteProgram(prog, fgraph, fcallSizes)";
		String record = "PlacementShadowCoordinator.record(shadow.observe(prog))";
		assertEquals("IPA must have one planner rewrite", 1, occurrences(source, rewrite));
		assertEquals("IPA must have one shadow begin hook", 1, occurrences(source, begin));
		assertEquals("IPA must record one completed observation", 1, occurrences(source, record));
		assertTrue("Shadow begin must precede planner mutation", source.indexOf(begin) < source.indexOf(rewrite));
		assertTrue("Shadow observation must follow planner mutation", source.indexOf(rewrite) < source.indexOf(record));
		assertFalse("Shadow code must not route or replace the planner", source.contains("shadow.getPlanner"));
	}

	@Test
	public void representativeShadowObservationLeavesSelectedOutputUnchanged() throws Exception {
		for(String fixture : List.of("B-03", "B-20", "B-22")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			String graphBefore = PlacementGraphFingerprint.capture(program);
			List<String> selectedBefore = new NeutralPlacementGraphBuilder().selectedProjection(program);
			Observation observation = PlacementShadowCoordinator.begin(program).observe(program);
			assertEquals(fixture + " selected output changed", selectedBefore, observation.selectedAfter());
			assertTrue(fixture + " selected output differential is non-zero: "
				+ observation.selectedProjectionDiff(), observation.selectedProjectionDiff().isEmpty());
			assertEquals(fixture + " Hop graph changed", graphBefore, PlacementGraphFingerprint.capture(program));
		}
	}

	private static void collectMatches(Path source, String text, Pattern pattern, String label,
		List<String> violations) {
		Matcher matcher = pattern.matcher(text);
		while(matcher.find())
			violations.add(relative(source) + ':' + label + ':' + matcher.group());
	}

	private static boolean containsAny(String value, String... needles) {
		return Arrays.stream(needles).anyMatch(value::contains);
	}

	private static int occurrences(String text, String token) {
		int count = 0;
		for(int index = 0; (index = text.indexOf(token, index)) >= 0; index += token.length())
			count++;
		return count;
	}

	private static boolean isDependencyDescriptor(String path) {
		String name = Paths.get(path).getFileName().toString();
		return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
			|| name.equals("settings.gradle") || name.equals("gradle.properties") || name.equals("ivy.xml")
			|| path.equals(".mvn/extensions.xml");
	}

	private static List<Path> javaSources(Path root) throws IOException {
		try(Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private static String relative(Path path) {
		return REPOSITORY.relativize(path).toString().replace('\\', '/');
	}

	private static String stripCommentsAndLiterals(String source) {
		return stripComments(source)
			.replaceAll("(?s)\"(?:\\\\.|[^\"\\\\])*\"", " ")
			.replaceAll("'(?:\\\\.|[^'\\\\])'", " ");
	}

	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	private static List<String> gitLines(String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(Arrays.asList(arguments));
		Process process = new ProcessBuilder(command).directory(REPOSITORY.toFile())
			.redirectErrorStream(true).start();
		List<String> output;
		try(BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			output = reader.lines().collect(Collectors.toList());
		}
		int exitCode = process.waitFor();
		if(exitCode != 0)
			fail("git command failed (" + exitCode + "): " + command + "\n" + String.join("\n", output));
		return output;
	}
}
