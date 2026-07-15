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

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Static boundary locks for the Campaign-A foundation; no planner cutover is authorized here. */
public class PlacementFoundationArchitectureGuardTest {
	private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();
	private static final Path PRODUCTION = ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner");
	private static final Path PLACEMENT = PRODUCTION.resolve("placement");
	private static final Path ORACLE = ROOT.resolve(
		"src/test/java/org/apache/sysds/test/component/federated/placement/oracle");
	private static final String ORACLE_PREFIX = "org.apache.sysds.test.component.federated.placement.oracle";
	private static final String PRODUCTION_PREFIX = "org.apache.sysds.hops.fedplanner.placement";

	@Test
	public void builderExposesOneObservableAnalysisUniverseAndLegacyParity() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-22");
		String before = PlacementGraphFingerprint.capture(program);
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		PlacementAnalysis analysis = builder.buildAnalysis(program);
		Assert.assertSame(analysis.graph(), analysis.graph());
		Assert.assertEquals(analysis.graph().normalizedSignature(), builder.build(program).normalizedSignature());
		Assert.assertEquals(before, PlacementGraphFingerprint.capture(program));
		List<String> entryPoints = Stream.of(NeutralPlacementGraphBuilder.class.getDeclaredMethods())
			.filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
			.filter(method -> method.getReturnType() == NeutralPlacementGraph.class
				|| method.getReturnType() == PlacementAnalysis.class)
			.map(method -> method.getName() + ':' + method.getReturnType().getSimpleName()).sorted()
			.collect(Collectors.toList());
		Assert.assertEquals(List.of("build:NeutralPlacementGraph", "buildAnalysis:PlacementAnalysis"), entryPoints);
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
			if(!JavaSourceBoundaryScanner.forbiddenReferences(read(source), ORACLE_PREFIX).isEmpty())
				violations.add(relative(source) + ": production imports test oracle");
		for(Path source : javaSources(ORACLE))
			if(!JavaSourceBoundaryScanner.forbiddenReferences(read(source), PRODUCTION_PREFIX).isEmpty())
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

	private static String relative(Path path) {
		return ROOT.relativize(path).toString().replace('\\', '/');
	}
}
