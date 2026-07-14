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

package org.apache.sysds.test.component.federated.placement.oracle.independence;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Static contract checks that keep the P1 builder and selector oracles independent.
 */
public class OracleIndependenceContractTest {
	private static final String APPROVED_BASELINE = "a6248a770c596617898da54a68b8bcbbe318d8ca";
	private static final Path REPOSITORY = Paths.get("").toAbsolutePath().normalize();
	private static final Path ORACLE_ROOT = REPOSITORY.resolve(
		"src/test/java/org/apache/sysds/test/component/federated/placement/oracle");
	private static final Path BUILDER_ROOT = ORACLE_ROOT.resolve("builder");
	private static final Path SELECTOR_ROOT = ORACLE_ROOT.resolve("selector");

	private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([^;]+);");
	private static final Pattern PRODUCTION_ORACLE_IMPORT = Pattern.compile(
		"^org\\.apache\\.sysds\\.hops\\.fedplanner\\..*(?:builder|selector).*$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$]*\\b");

	@Test
	public void oraclePackagesStayIndependentAndProductionFree() throws IOException {
		assertJavaSourcesExist(BUILDER_ROOT, "builder");
		assertJavaSourcesExist(SELECTOR_ROOT, "selector");

		assertNoForbiddenImports(BUILDER_ROOT, ".placement.oracle.selector");
		assertNoForbiddenImports(SELECTOR_ROOT, ".placement.oracle.builder");
	}

	@Test
	public void allRequiredFixtureIdsAreDeclared() throws IOException {
		assertFixtureIds(BUILDER_ROOT, "B-", 22);
		assertFixtureIds(SELECTOR_ROOT, "S-", 8);
	}

	@Test
	public void selectorHasNoFallbackSuccessContract() throws IOException {
		for(Path source : javaSources(SELECTOR_ROOT)) {
			String executableTokens = stripCommentsAndLiterals(read(source));
			List<String> forbidden = fallbackSuccessIdentifiers(executableTokens);
			assertTrue(source + " contains fallback/timeout/cap/approximation identifiers presented as success: "
				+ forbidden, forbidden.isEmpty());
		}
	}

	@Test
	public void p1ChangesRemainTestOnly() throws Exception {
		List<String> changed = gitLines("diff", "--name-only", APPROVED_BASELINE);
		changed.addAll(gitLines("ls-files", "--others", "--exclude-standard"));
		List<String> forbidden = changed.stream()
			.filter(path -> path.equals("pom.xml") || path.startsWith("src/main/"))
			.collect(Collectors.toList());
		assertTrue("P1 oracle foundation changed production/dependency files: " + forbidden, forbidden.isEmpty());
	}

	private static void assertNoForbiddenImports(Path root, String peerPackage) throws IOException {
		List<String> violations = new ArrayList<>();
		for(Path source : javaSources(root)) {
			Matcher imports = IMPORT.matcher(read(source));
			while(imports.find()) {
				String imported = imports.group(1);
				if(imported.contains(peerPackage) || PRODUCTION_ORACLE_IMPORT.matcher(imported).matches())
					violations.add(REPOSITORY.relativize(source) + " -> " + imported);
			}
		}
		assertTrue("Oracle independence violations: " + violations, violations.isEmpty());
	}

	private static void assertFixtureIds(Path root, String prefix, int count) throws IOException {
		assertJavaSourcesExist(root, prefix);
		String allSources = javaSources(root).stream().map(path -> {
			try {
				return read(path);
			}
			catch(IOException ex) {
				throw new FixtureReadException(ex);
			}
		}).collect(Collectors.joining("\n"));
		List<String> missing;
		try {
			missing = IntStream.rangeClosed(1, count)
				.mapToObj(number -> String.format(Locale.ROOT, "%s%02d", prefix, number))
				.filter(id -> !allSources.contains(id))
				.collect(Collectors.toList());
		}
		catch(FixtureReadException ex) {
			throw ex.ioException;
		}
		assertTrue("Missing required oracle fixtures under " + root + ": " + missing, missing.isEmpty());
	}

	private static void assertJavaSourcesExist(Path root, String label) throws IOException {
		assertTrue("Missing " + label + " oracle package: " + root, Files.isDirectory(root));
		assertTrue("No Java sources in " + label + " oracle package: " + root, !javaSources(root).isEmpty());
	}

	private static List<Path> javaSources(Path root) throws IOException {
		if(!Files.isDirectory(root))
			return new ArrayList<>();
		try(Stream<Path> paths = Files.walk(root)) {
			return paths.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	private static String read(Path path) throws IOException {
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}

	private static String stripCommentsAndLiterals(String source) {
		return source
			.replaceAll("(?s)/\\*.*?\\*/", " ")
			.replaceAll("(?m)//.*$", " ")
			.replaceAll("(?s)\"(?:\\\\.|[^\"\\\\])*\"", " ")
			.replaceAll("'(?:\\\\.|[^'\\\\])'", " ");
	}

	private static List<String> fallbackSuccessIdentifiers(String source) {
		List<String> forbidden = new ArrayList<>();
		Matcher identifiers = IDENTIFIER.matcher(source);
		while(identifiers.find()) {
			String identifier = identifiers.group();
			String normalized = identifier.toLowerCase(Locale.ROOT).replace("_", "");
			boolean escapeHatch = normalized.contains("timeout") || normalized.contains("statecap")
				|| normalized.contains("greedy") || normalized.contains("approx") || normalized.contains("fallback");
			boolean claimsSuccess = normalized.contains("success") || normalized.contains("succeed")
				|| normalized.contains("exact") || normalized.equals("greedyfallback")
				|| normalized.equals("approximatefallback");
			boolean explicitlyRejected = normalized.contains("cannot") || normalized.contains("reject")
				|| normalized.contains("forbid") || normalized.contains("fail") || normalized.startsWith("no");
			if(escapeHatch && claimsSuccess && !explicitlyRejected)
				forbidden.add(identifier);
		}
		return forbidden;
	}

	private static List<String> gitLines(String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(Arrays.asList(arguments));
		Process process = new ProcessBuilder(command)
			.directory(REPOSITORY.toFile())
			.redirectErrorStream(true)
			.start();
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

	private static final class FixtureReadException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final IOException ioException;

		private FixtureReadException(IOException cause) {
			super(cause);
			ioException = cause;
		}
	}
}
