/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Test;

/** Syntax-aware locks for the single-pass placement-analysis construction boundary. */
public class PlacementAnalysisConstructionArchitectureTest {
	private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();
	private static final Path BUILDER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java");
	private static final Path SHADOW = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementShadowCoordinator.java");
	private static final Path ANALYSIS = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java");
	private static final Path SHAPE_FACTS = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementShapeFacts.java");
	private static final Path FIXTURE = ROOT.resolve(
		"src/test/java/org/apache/sysds/hops/fedplanner/placement/CampaignBPlacementAnalysisFixtureBridge.java");
	private static final String ORDERED_OCCURRENCES =
		"PlacementGraphFingerprint.orderedOccurrences(program)";

	@Test
	public void productionUsesOneForwardMutationGuardedAnalysisPath() throws Exception {
		List<String> violations = constructionViolations(read(BUILDER), read(SHADOW));
		Assert.assertTrue("placement analysis construction violations: " + violations, violations.isEmpty());
	}

	@Test
	public void validForwardDelegationFixturePassesWithoutLiteralOrCommentSpoofing() {
		String builder = "class Builder {"
			+ " NeutralPlacementGraph build(DMLProgram program) {"
			+ "   String fake = \"return build(program); projectConcreteOrigins; "
			+ "PlacementGraphFingerprint.orderedOccurrences(program)\";"
			+ "   return buildAnalysis(program).graph(); }"
			+ " PlacementAnalysis buildAnalysis(DMLProgram program) {"
			+ "   String before = PlacementGraphFingerprint.capture(program);"
			+ "   String registryBefore = registrySentinel(program);"
			+ "   var occurrences = PlacementGraphFingerprint.orderedOccurrences(program);"
			+ "   PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
			+ "   if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;"
			+ "   if(!registryBefore.equals(registrySentinel(program))) throw failure;"
			+ "   return analysis; }"
			+ " // PlacementAnalysis buildAnalysis(DMLProgram program) {"
			+ " PlacementGraphFingerprint.orderedOccurrences(program); return build(program); }"
			+ "}";
		String shadow = shadowCalling("new NeutralPlacementGraphBuilder().buildAnalysis(program).graph()");
		Assert.assertEquals(List.of(), constructionViolations(builder, shadow));
	}

	@Test
	public void reverseDelegationAndDuplicateTraversalFixturesAreRejected() {
		String reverse = validBuilder().replace("return buildAnalysis(program).graph();", "return graph;")
			.replace("var occurrences = " + ORDERED_OCCURRENCES + ';',
				"NeutralPlacementGraph graph = build(program); var occurrences = " + ORDERED_OCCURRENCES + ';');
		Assert.assertTrue(constructionViolations(reverse, validShadow()).contains("legacy build is not a forward delegate"));
		Assert.assertTrue(constructionViolations(reverse, validShadow()).contains("buildAnalysis calls legacy build"));

		String extraLegacy = validBuilder().replace("return buildAnalysis(program).graph();",
			"var extra = " + ORDERED_OCCURRENCES + "; return buildAnalysis(program).graph();");
		List<String> extraLegacyViolations = constructionViolations(extraLegacy, validShadow());
		Assert.assertTrue(extraLegacyViolations.contains("builder must perform exactly one ordered occurrence pass"));
		Assert.assertTrue(extraLegacyViolations.contains("legacy build must not traverse ordered occurrences"));

		String duplicate = validBuilder().replace("PlacementAnalysis analysis =",
			"var duplicate = " + ORDERED_OCCURRENCES + "; PlacementAnalysis analysis =");
		Assert.assertTrue(constructionViolations(duplicate, validShadow()).contains(
			"buildAnalysis must perform exactly one ordered occurrence pass"));
	}

	@Test
	public void postHocOriginMatchingAndEarlySentinelFixturesAreRejected() {
		String postHoc = insertBeforeClassEnd(validBuilder(),
			" Map projectConcreteOrigins(DMLProgram program, Graph graph) {"
				+ " return graph.nodes().stream().map(Node::key)"
				+ ".filter(key -> key.callSitePath().equals(path) && key.emittedHopInstance().equals(topology)"
				+ " && key.canonicalSourceOrigin().equals(source)); }")
			.replace("PlacementAnalysis analysis =",
				"var origins = projectConcreteOrigins(program, graph); PlacementAnalysis analysis =");
		List<String> matching = constructionViolations(postHoc, validShadow());
		Assert.assertTrue(matching.contains("post-hoc origin projection helper is forbidden"));
		Assert.assertTrue(matching.contains("post-hoc path/topology/source matching is forbidden"));

		String early = validBuilder()
			.replace("PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);", "")
			.replace("return analysis;", "return new PlacementAnalysis(graph, projections);");
		Assert.assertTrue(constructionViolations(early, validShadow()).contains(
			"Hop mutation sentinel does not enclose and compare the analysis construction"));
	}

	@Test
	public void unrelatedDiagnosticMatcherPassesButIncompleteSentinelsFail() {
		String diagnostic = insertBeforeClassEnd(validBuilder(),
			" List diagnostic(Graph graph) { return graph.nodes().stream().map(Node::key)"
				+ ".filter(key -> key.callSitePath().equals(path) && key.emittedHopInstance().equals(topology)"
				+ " && key.canonicalSourceOrigin().equals(source)).toList(); }");
		Assert.assertEquals(List.of(), constructionViolations(diagnostic, validShadow()));

		String afterOnly = validBuilder()
			.replace("String before = PlacementGraphFingerprint.capture(program);", "")
			.replace("String registryBefore = registrySentinel(program);", "")
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"PlacementGraphFingerprint.capture(program);")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"registrySentinel(program);");
		assertBothSentinelsRejected(afterOnly);

		String unusedBefore = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"PlacementGraphFingerprint.capture(program);")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"registrySentinel(program);");
		assertBothSentinelsRejected(unusedBefore);
	}

	@Test
	public void capturedAfterThrowShapePassesButNonEnforcingComparisonsFail() {
		String capturedAfter = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"String after = PlacementGraphFingerprint.capture(program);"
					+ " if(!after.equals(before)) throw failure;")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"String registryAfter = registrySentinel(program);"
					+ " if(!registryBefore.equals(registryAfter)) throw failure;");
		Assert.assertEquals(List.of(), constructionViolations(capturedAfter, validShadow()));

		String ignored = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"boolean hopSame = before.equals(PlacementGraphFingerprint.capture(program));")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"boolean registrySame = registryBefore.equals(registrySentinel(program));");
		assertBothSentinelsRejected(ignored);

		String loggingOnly = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"LOG.info(before.equals(PlacementGraphFingerprint.capture(program)));")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"metrics.record(registryBefore.equals(registrySentinel(program)));");
		assertBothSentinelsRejected(loggingOnly);

		String assertOnly = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"assert before.equals(PlacementGraphFingerprint.capture(program));")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"assert registryBefore.equals(registrySentinel(program));");
		assertBothSentinelsRejected(assertOnly);

		String unreachable = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"if(false && !before.equals(PlacementGraphFingerprint.capture(program))) throw failure;")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"if(false && !registryBefore.equals(registrySentinel(program))) throw failure;");
		assertBothSentinelsRejected(unreachable);

		String throwIdentifiers = validBuilder()
			.replace("if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;",
				"if(!before.equals(PlacementGraphFingerprint.capture(program))) throwMetric.record();")
			.replace("if(!registryBefore.equals(registrySentinel(program))) throw failure;",
				"if(!registryBefore.equals(registrySentinel(program))) throwLogger.warn();");
		assertBothSentinelsRejected(throwIdentifiers);

		String earlySuccess = validBuilder().replace(
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);",
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
				+ " if(condition) return analysis;");
		assertBothSentinelsRejected(earlySuccess);

		String parenthesizedEarlySuccess = validBuilder().replace(
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);",
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
				+ " if(condition) return(analysis);");
		assertBothSentinelsRejected(parenthesizedEarlySuccess);

		String parenthesizedThrow = validBuilder().replace("throw failure;", "throw(new Failure());");
		Assert.assertEquals(List.of(), constructionViolations(parenthesizedThrow, validShadow()));

		String capturedParenthesizedThrow = capturedAfter.replace("throw failure;", "throw(new Failure());");
		Assert.assertEquals(List.of(), constructionViolations(capturedParenthesizedThrow, validShadow()));

		String returnIdentifier = validBuilder().replace(
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);",
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections); returnValue(analysis);");
		Assert.assertEquals(List.of(), constructionViolations(returnIdentifier, validShadow()));

		String javaIdentifierPrefixes = validBuilder().replace(
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);",
			"PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
				+ " throw$Metric.record(); throw_Logger.warn(); throw0(); throwé(); throw\u0301();"
				+ " return$Value(analysis); return_Value(analysis); return2(analysis); returné(analysis); return\u0301(analysis);");
		Assert.assertEquals(List.of(), constructionViolations(javaIdentifierPrefixes, validShadow()));
	}

	@Test
	public void placementShapeFactsAreOwnedByTheBuilderBoundary() throws Exception {
		assertA2ScannerRejectsSpoofing();
		List<String> violations = shapeFactsOwnershipViolations();
		Assert.assertTrue("A2 shape-facts ownership violations: " + violations, violations.isEmpty());
	}

	private static List<String> shapeFactsOwnershipViolations() throws Exception {
		assertExactKeyGuardRejectsAdversarialFixtures();
		List<String> violations = new ArrayList<>();
		String analysisSource = read(ANALYSIS);
		String analysis = JavaSourceBoundaryScanner.codeOnly(analysisSource);
		String builder = JavaSourceBoundaryScanner.codeOnly(read(BUILDER));
		String fixtureSource = read(FIXTURE);
		String fixture = JavaSourceBoundaryScanner.codeOnly(fixtureSource);
		if(matches(analysis, "\\bOracleFacade\\b") || matches(analysis,
			"\\bOracleFacade\\s*\\.\\s*nodeShape\\s*\\(") || matches(analysis, "\\bnodeShape\\s*\\("))
			violations.add("PlacementAnalysis must not import, reference, or derive through OracleFacade");
		if(matches(analysis, "\\.\\s*(?:getDataType|getDim1|getDim2)\\s*\\("))
			violations.add("PlacementAnalysis must not derive shape facts through direct Hop shape getters");
		if(countMatches(analysis, "\\bpublic\\s+record\\s+NodeShapeFact\\b") != 1)
			violations.add("public PlacementAnalysis.NodeShapeFact API must remain present exactly once");
		if(countMatches(analysis,
			"\\bpublic\\s+(?:java\\.util\\.)?Optional\\s*<\\s*NodeShapeFact\\s*>\\s+shapeFact\\s*\\(\\s*CompiledHopKey\\b") != 1)
			violations.add("public PlacementAnalysis.shapeFact(CompiledHopKey) compatibility API must remain exactly once");

		String facts = "";
		if(!Files.isRegularFile(SHAPE_FACTS))
			violations.add("PlacementShapeFacts.java is absent");
		else {
			String factsSource = read(SHAPE_FACTS);
			facts = JavaSourceBoundaryScanner.codeOnly(factsSource);
			if(countMatches(facts, "\\bpackage\\s+org\\.apache\\.sysds\\.hops\\.fedplanner\\.placement\\s*;") != 1
				|| countMatches(facts, "\\bfinal\\s+class\\s+PlacementShapeFacts\\b") != 1)
				violations.add("PlacementShapeFacts must be an explicit final carrier in the placement package");
			if(countMatches(facts, "\\bprivate\\s+final\\s+(?:java\\.util\\.)?Map\\s*<\\s*(?:PlacementIdentity\\s*\\.\\s*)?CompiledHopKey\\s*,\\s*(?:PlacementAnalysis\\s*\\.\\s*)?NodeShapeFact\\s*>\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*;") != 1
				|| countMatches(facts, "\\b(?:java\\.util\\.)?Map\\s*\\.\\s*copyOf\\s*\\(") != 1)
				violations.add("PlacementShapeFacts must own exactly one immutable CompiledHopKey-to-NodeShapeFact map");
			if(matches(facts, "\\bOracleFacade\\b|\\bnodeShape\\s*\\(")
				|| matches(facts, "\\.\\s*(?:getDataType|getDim1|getDim2)\\s*\\("))
				violations.add("PlacementShapeFacts must store builder-owned facts, not derive shape metadata");
			if(countMatches(facts, "\\b(?:java\\.util\\.)?Set\\s*<\\s*(?:PlacementIdentity\\s*\\.\\s*)?CompiledHopKey\\s*>\\s+keys\\s*\\(\\s*\\)") != 1
				|| !matches(facts, "\\.\\s*keySet\\s*\\(\\s*\\)"))
				violations.add("PlacementShapeFacts must expose an exact typed immutable key set");
			if(!hasExactKeyEqualityGuard(factsSource))
				violations.add("PlacementShapeFacts must reject both missing and extra keys by exact set equality");
		}

		if(countMatches(builder, "\\bOracleFacade\\s*\\.\\s*nodeShape\\s*\\(") != 1)
			violations.add("builder must derive shape facts through exactly one OracleFacade.nodeShape call");
		Matcher factVariable = Pattern.compile("\\bPlacementShapeFacts\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*new\\s+PlacementShapeFacts\\s*\\(")
			.matcher(builder);
		String variable = factVariable.find() ? factVariable.group(1) : null;
		if(variable == null || factVariable.find())
			violations.add("builder must create exactly one named PlacementShapeFacts carrier");
		List<String> builderAllocations = constructorArguments(builder, "PlacementAnalysis");
		if(builderAllocations.size() != 1 || variable == null
			|| !matches(builderAllocations.get(0), "\\b" + Pattern.quote(variable) + "\\b"))
			violations.add("builder must pass its exact facts carrier to the sole PlacementAnalysis allocation");
		boolean hasFactsConstructor = countMatches(analysis,
			"\\bPlacementAnalysis\\s*\\([^)]*\\bPlacementShapeFacts\\s+[A-Za-z_$][A-Za-z0-9_$]*") >= 1;
		if(!hasFactsConstructor)
			violations.add("PlacementAnalysis must receive PlacementShapeFacts through its constructor seam");
		if(countMatches(analysis, "\\bprivate\\s+final\\s+PlacementShapeFacts\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*;") != 1)
			violations.add("PlacementAnalysis must retain exactly one typed immutable facts carrier without erasure");
		if(!hasFactsConstructor || !hasAnalysisExactKeyGuard(analysisSource))
			violations.add("PlacementAnalysis must reject facts whose keys differ from indexed graph/projection keys");

		Map<Path, String> production = productionJava();
		List<String> allocations = new ArrayList<>();
		List<String> carriers = new ArrayList<>();
		List<String> derivations = new ArrayList<>();
		for(Map.Entry<Path, String> entry : production.entrySet()) {
			for(int i = 0; i < countMatches(entry.getValue(), "\\bnew\\s+PlacementAnalysis\\s*\\("); i++)
				allocations.add(ROOT.relativize(entry.getKey()).toString());
			for(int i = 0; i < countMatches(entry.getValue(), "\\bnew\\s+PlacementShapeFacts\\s*\\("); i++)
				carriers.add(ROOT.relativize(entry.getKey()).toString());
			for(int i = 0; i < countMatches(entry.getValue(), "\\bOracleFacade\\s*\\.\\s*nodeShape\\s*\\("); i++)
				derivations.add(ROOT.relativize(entry.getKey()).toString());
		}
		String builderPath = ROOT.relativize(BUILDER).toString();
		if(!derivations.equals(List.of(builderPath)))
			violations.add("builder must own the sole production OracleFacade.nodeShape derivation: " + derivations);
		if(!allocations.equals(List.of(builderPath)))
			violations.add("builder must be the sole production PlacementAnalysis allocation root: " + allocations);
		if(!carriers.equals(List.of(builderPath)))
			violations.add("builder must be the sole production PlacementShapeFacts construction root: " + carriers);
		List<String> allowedFactsPaths = List.of(ROOT.relativize(SHAPE_FACTS).toString(), builderPath,
			ROOT.relativize(ANALYSIS).toString());
		for(Map.Entry<Path, String> entry : production.entrySet())
			if(matches(entry.getValue(), "\\bPlacementShapeFacts\\b")
				&& !allowedFactsPaths.contains(ROOT.relativize(entry.getKey()).toString()))
				violations.add("alternate PlacementShapeFacts wrapper/delegate path: " + ROOT.relativize(entry.getKey()));
		if(!facts.isEmpty()) {
			for(Map.Entry<Path, String> entry : production.entrySet())
				if(!entry.getKey().equals(SHAPE_FACTS) && matches(entry.getValue(),
					"\\bMap\\s*<\\s*CompiledHopKey\\s*,\\s*(?:PlacementAnalysis\\s*\\.\\s*)?NodeShapeFact\\s*>"))
					violations.add("alternate shape-fact map carrier: " + ROOT.relativize(entry.getKey()));
		}

		if(matches(fixture, "\\bOracleFacade\\b") || matches(fixture, "\\bnodeShape\\s*\\("))
			violations.add("fixture bridge must not derive shape facts through OracleFacade");
		if(matches(builder, "\\.\\s*(?:getDataType|getDim1|getDim2)\\s*\\("))
			violations.add("builder must derive shape facts only through its sole OracleFacade.nodeShape call");
		if(matches(fixture, "\\.\\s*(?:getDataType|getDim1|getDim2)\\s*\\("))
			violations.add("fixture bridge must use explicit/projected facts rather than direct Hop shape getters");
		addForbiddenA2Indirection(violations, "PlacementAnalysis", analysis);
		if(!facts.isEmpty()) addForbiddenA2Indirection(violations, "PlacementShapeFacts", facts);
		addForbiddenA2Indirection(violations, "NeutralPlacementGraphBuilder", builder);
		addForbiddenA2Indirection(violations, "CampaignBPlacementAnalysisFixtureBridge", fixture);
		List<String> fixtureAllocations = constructorArguments(fixture, "PlacementAnalysis");
		List<String> fixtureFactNames = declaredNames(fixture, "PlacementShapeFacts");
		if(fixtureFactNames.isEmpty() || fixtureAllocations.isEmpty()
			|| fixtureAllocations.stream().anyMatch(args -> fixtureFactNames.stream()
				.noneMatch(name -> matches(args, "\\b" + Pattern.quote(name) + "\\b"))))
			violations.add("fixture bridge must pass an explicit PlacementShapeFacts seam to every analysis");
		assertFixtureFactsBinding(violations, fixtureSource, "fromSelectorGraph", "ProjectionOrder",
			List.of("graph", "projections"), List.of("graph", "projections"));
		assertFixtureFactsBinding(violations, fixtureSource, "prefix", "occurrenceCount",
			List.of("source", "projections"), List.of("projected", "projections"));
		assertFixtureFactsBinding(violations, fixtureSource, "sameHopContextTrap", "PlacementAnalysis",
			List.of("source", "projections"), List.of("source.graph", "projections"));
		return List.copyOf(violations);
	}

	private static boolean hasExactKeyEqualityGuard(String source) {
		String parameters = methodParameters(source, "PlacementShapeFacts", "Map");
		String body = JavaSourceBoundaryScanner.methodBody(source, "PlacementShapeFacts", "Map");
		String mapName = typedParameterName(parameters,
			"(?:java\\.util\\.)?Map\\s*<\\s*(?:PlacementIdentity\\s*\\.\\s*)?CompiledHopKey\\s*,\\s*(?:PlacementAnalysis\\s*\\.\\s*)?NodeShapeFact\\s*>");
		String expectedName = typedParameterName(parameters,
			"(?:java\\.util\\.)?Set\\s*<\\s*(?:PlacementIdentity\\s*\\.\\s*)?CompiledHopKey\\s*>");
		if(mapName == null || expectedName == null || mapName.equals(expectedName)
			|| !matches(body, "\\bIllegalArgumentException\\b")) return false;
		String mapKeys = "\\b" + Pattern.quote(mapName) + "\\s*\\.\\s*keySet\\s*\\(\\s*\\)";
		String expected = "\\b" + Pattern.quote(expectedName) + "\\b";
		return hasImmediateNegatedEqualityThrow(body,
			mapKeys + "\\s*\\.\\s*equals\\s*\\(\\s*" + expected + "\\s*\\)",
			expected + "\\s*\\.\\s*equals\\s*\\(\\s*" + mapKeys + "\\s*\\)");
	}

	private static boolean hasAnalysisExactKeyGuard(String source) {
		String parameters = methodParameters(source, "PlacementAnalysis", "PlacementShapeFacts");
		String body = JavaSourceBoundaryScanner.methodBody(source, "PlacementAnalysis", "PlacementShapeFacts");
		String factsName = typedParameterName(parameters, "PlacementShapeFacts");
		Matcher indexed = Pattern.compile("\\b(?:java\\.util\\.)?Map\\s*<\\s*(?:PlacementIdentity\\s*\\.\\s*)?CompiledHopKey\\s*,\\s*(?:org\\.apache\\.sysds\\.hops\\.)?Hop\\s*>\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b")
			.matcher(body);
		if(factsName == null || !indexed.find() || !matches(body, "\\bIllegalArgumentException\\b")) return false;
		String indexedName = indexed.group(1);
		String factsKeys = "\\b" + Pattern.quote(factsName) + "\\s*\\.\\s*keys\\s*\\(\\s*\\)";
		String indexedKeys = "\\b" + Pattern.quote(indexedName) + "\\s*\\.\\s*keySet\\s*\\(\\s*\\)";
		return hasImmediateNegatedEqualityThrow(body,
			factsKeys + "\\s*\\.\\s*equals\\s*\\(\\s*" + indexedKeys + "\\s*\\)",
			indexedKeys + "\\s*\\.\\s*equals\\s*\\(\\s*" + factsKeys + "\\s*\\)");
	}

	private static boolean hasImmediateNegatedEqualityThrow(String body, String forward, String reverse) {
		String equality = "(?:" + forward + '|' + reverse + ')';
		String exception = "(?:java\\s*\\.\\s*lang\\s*\\.\\s*)?IllegalArgumentException";
		String immediateThrow = "(?:\\{\\s*)?throw\\s+new\\s+" + exception
			+ "\\s*\\([^;]*\\)\\s*;\\s*(?:\\})?";
		return matches(body, "\\bif\\s*\\(\\s*!\\s*\\(?\\s*" + equality
			+ "\\s*\\)?\\s*\\)\\s*" + immediateThrow);
	}

	private static String methodParameters(String source, String methodName, String parameterToken) {
		String code = JavaSourceBoundaryScanner.codeOnly(source);
		Matcher matcher = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[^\\{]+)?\\{")
			.matcher(code);
		while(matcher.find()) if(matcher.group(1).contains(parameterToken)) return matcher.group(1);
		return "";
	}

	private static String typedParameterName(String parameters, String typeExpression) {
		Matcher matcher = Pattern.compile("(?:^|,)\\s*(?:final\\s+)?" + typeExpression
			+ "\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?=,|$)").matcher(parameters);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static void assertExactKeyGuardRejectsAdversarialFixtures() {
		String factsPrefix = "final class PlacementShapeFacts { PlacementShapeFacts("
			+ "java.util.Map<CompiledHopKey, PlacementAnalysis.NodeShapeFact> facts, "
			+ "java.util.Set<CompiledHopKey> expectedKeys) { ";
		Assert.assertTrue(hasExactKeyEqualityGuard(factsPrefix
			+ "if(!facts.keySet().equals(expectedKeys)) throw new IllegalArgumentException(); }}"));
		Assert.assertTrue(hasExactKeyEqualityGuard(factsPrefix
			+ "if(!expectedKeys.equals(facts.keySet())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasExactKeyEqualityGuard(factsPrefix
			+ "if(!facts.keySet().equals(facts.keySet())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasExactKeyEqualityGuard(factsPrefix
			+ "if(!facts.keySet().containsAll(expectedKeys)) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasExactKeyEqualityGuard(factsPrefix
			+ "if(!expectedKeys.containsAll(facts.keySet())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasExactKeyEqualityGuard(factsPrefix
			+ "facts.keySet().equals(expectedKeys); if(false) throw new IllegalArgumentException(); }}"));

		String analysisPrefix = "final class PlacementAnalysis { PlacementAnalysis(Graph graph, "
			+ "PlacementShapeFacts facts) { java.util.Map<CompiledHopKey, Hop> indexed = make(); ";
		Assert.assertTrue(hasAnalysisExactKeyGuard(analysisPrefix
			+ "if(!facts.keys().equals(indexed.keySet())) throw new IllegalArgumentException(); }}"));
		Assert.assertTrue(hasAnalysisExactKeyGuard(analysisPrefix
			+ "if(!indexed.keySet().equals(facts.keys())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasAnalysisExactKeyGuard(analysisPrefix
			+ "indexed.keySet(); if(!facts.keys().equals(facts.keys())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasAnalysisExactKeyGuard(analysisPrefix
			+ "if(!facts.keys().containsAll(indexed.keySet())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasAnalysisExactKeyGuard(analysisPrefix
			+ "if(!indexed.keySet().containsAll(facts.keys())) throw new IllegalArgumentException(); }}"));
		Assert.assertFalse(hasAnalysisExactKeyGuard(analysisPrefix
			+ "facts.keys().equals(indexed.keySet()); if(false) throw new IllegalArgumentException(); }}"));
	}

	private static void addForbiddenA2Indirection(List<String> violations, String owner, String source) {
		String withoutDiagnosticStringValueOf = source.replaceAll(
			"\\bString\\s*\\.\\s*valueOf\\s*\\(", "diagnosticStringConversion(");
		List<String> forbidden = List.of(
			"\\bObject\\b",
			"\\b(?:Class|Method|Field|Constructor)\\s*\\.\\s*forName\\s*\\(",
			"\\bClass\\s*\\.\\s*forName\\s*\\(",
			"\\.\\s*getDeclared(?:Method|Methods|Field|Fields|Constructor|Constructors)\\s*\\(",
			"\\.\\s*(?:loadClass|getMethod|getMethods|getConstructor|getConstructors|newInstance)\\s*\\(",
			"\\.\\s*invoke\\s*\\(",
			"\\b(?:ClassLoader|MethodHandle|MethodHandles)\\b",
			"\\bSupplier\\s*<",
			"\\bFunction\\s*<",
			"\\bServiceLoader\\b",
			"\\b(?:registry|serviceRegistry)\\s*\\.\\s*(?:get|lookup|resolve)\\s*\\(",
			"\\.\\s*valueOf\\s*\\(",
			"\\b(?:wrapper|delegate)\\b");
		for(String expression : forbidden)
			if(matches(withoutDiagnosticStringValueOf, expression)) {
				violations.add(owner + " contains forbidden A2 type hiding or indirection: " + expression);
				return;
			}
	}

	private static void assertFixtureFactsBinding(List<String> violations, String fixtureSource, String method,
		String parameterToken, List<String> factExpressionTokens, List<String> analysisArgumentTokens)
	{
		String body = JavaSourceBoundaryScanner.methodBody(fixtureSource, method, parameterToken);
		Matcher assignment = Pattern.compile(
			"\\bPlacementShapeFacts\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*([^;]+);").matcher(body);
		if(!assignment.find()) {
			violations.add("fixture " + method + " must create its own projection-bound PlacementShapeFacts carrier");
			return;
		}
		String name = assignment.group(1);
		String expression = assignment.group(2);
		if(assignment.find())
			violations.add("fixture " + method + " must have exactly one explicit facts carrier binding");
		for(String token : factExpressionTokens)
			if(!matches(expression, Pattern.quote(token)))
				violations.add("fixture " + method + " facts must be tied to exact " + token + " identity/keys");
		List<String> allocations = constructorArguments(body, "PlacementAnalysis");
		if(allocations.size() != 1 || !matches(allocations.get(0), "\\b" + Pattern.quote(name) + "\\b")) {
			violations.add("fixture " + method + " must pass its own facts carrier to its sole analysis allocation");
			return;
		}
		for(String token : analysisArgumentTokens)
			if(!matches(allocations.get(0), Pattern.quote(token)))
				violations.add("fixture " + method + " analysis/facts seam must preserve exact " + token + " binding");
	}

	private static void assertA2ScannerRejectsSpoofing() {
		String fixture = "// OracleFacade.nodeShape(fake); new PlacementShapeFacts(fake);\n"
			+ "String text = \"OracleFacade.nodeShape(fake) new PlacementAnalysis(fake)\";\n"
			+ "char quote = '\\''; /* new PlacementShapeFacts(fake); */\n"
			+ "OracleFacadeHelper . nodeShape ( fake ); nodeShapeHelper(fake);\n"
			+ "var real = OracleFacade \n . nodeShape \n ( hop );\n"
			+ "PlacementShapeFacts facts = new \n PlacementShapeFacts \n ( map );";
		String code = JavaSourceBoundaryScanner.codeOnly(fixture);
		Assert.assertEquals(1, countMatches(code, "\\bOracleFacade\\s*\\.\\s*nodeShape\\s*\\("));
		Assert.assertEquals(1, countMatches(code, "\\bnew\\s+PlacementShapeFacts\\s*\\("));
	}

	private static Map<Path, String> productionJava() throws Exception {
		Map<Path, String> sources = new LinkedHashMap<>();
		Path main = ROOT.resolve("src/main/java");
		try(Stream<Path> paths = Files.walk(main)) {
			for(Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
				.sorted().toList())
				sources.put(path, JavaSourceBoundaryScanner.codeOnly(read(path)));
		}
		return sources;
	}

	private static List<String> constructorArguments(String source, String type) {
		List<String> arguments = new ArrayList<>();
		Matcher matcher = Pattern.compile("\\bnew\\s+" + Pattern.quote(type) + "\\s*\\(").matcher(source);
		while(matcher.find()) {
			int start = matcher.end(), depth = 1;
			for(int i = start; i < source.length(); i++) {
				char current = source.charAt(i);
				if(current == '(') depth++;
				else if(current == ')' && --depth == 0) {
					arguments.add(source.substring(start, i));
					break;
				}
			}
		}
		return arguments;
	}

	private static List<String> declaredNames(String source, String type) {
		List<String> names = new ArrayList<>();
		Matcher matcher = Pattern.compile("\\b" + Pattern.quote(type) + "\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b")
			.matcher(source);
		while(matcher.find()) names.add(matcher.group(1));
		return names;
	}

	private static int countMatches(String source, String expression) {
		int count = 0;
		Matcher matcher = Pattern.compile(expression, Pattern.MULTILINE | Pattern.DOTALL).matcher(source);
		while(matcher.find()) count++;
		return count;
	}

	private static boolean matches(String source, String expression) {
		return Pattern.compile(expression, Pattern.MULTILINE | Pattern.DOTALL).matcher(source).find();
	}

	private static List<String> constructionViolations(String builderSource, String shadowSource) {
		String builderCode = JavaSourceBoundaryScanner.codeOnly(builderSource);
		String build = JavaSourceBoundaryScanner.methodBody(builderSource, "build", "DMLProgram");
		String analysis = JavaSourceBoundaryScanner.methodBody(builderSource, "buildAnalysis", "DMLProgram");
		List<String> violations = new ArrayList<>();
		if(!compact(build).matches("(?s).*return(?:this\\.)?buildAnalysis\\(program\\)\\.graph\\(\\);.*"))
			violations.add("legacy build is not a forward delegate");
		if(Pattern.compile("(?<![A-Za-z0-9_$])build\\s*\\(\\s*program\\s*\\)").matcher(analysis).find())
			violations.add("buildAnalysis calls legacy build");
		if(count(compact(builderCode), compact(ORDERED_OCCURRENCES)) != 1)
			violations.add("builder must perform exactly one ordered occurrence pass");
		if(count(compact(build), compact(ORDERED_OCCURRENCES)) != 0)
			violations.add("legacy build must not traverse ordered occurrences");
		if(count(compact(analysis), compact(ORDERED_OCCURRENCES)) != 1)
			violations.add("buildAnalysis must perform exactly one ordered occurrence pass");
		if(builderCode.matches("(?s).*\\bprojectConcreteOrigins\\b.*"))
			violations.add("post-hoc origin projection helper is forbidden");
		String originProjection = optionalMethodBody(builderSource, "projectConcreteOrigins", "DMLProgram");
		if(originProjection.matches("(?s).*graph\\s*\\.\\s*nodes\\s*\\(\\)\\s*\\.\\s*stream\\s*\\(\\)"
			+ ".*callSitePath\\s*\\(\\).*emittedHopInstance\\s*\\(\\).*canonicalSourceOrigin\\s*\\(\\).*"))
			violations.add("post-hoc path/topology/source matching is forbidden");
		int construction = analysisConstructionPosition(analysis);
		if(!sentinelEncloses(analysis, "PlacementGraphFingerprint.capture(program)", construction))
			violations.add("Hop mutation sentinel does not enclose and compare the analysis construction");
		if(!sentinelEncloses(analysis, "registrySentinel(program)", construction))
			violations.add("registry mutation sentinel does not enclose and compare the analysis construction");
		String shadowBuild = JavaSourceBoundaryScanner.methodBody(shadowSource, "build", "DMLProgram");
		if(!compact(shadowBuild).contains("buildAnalysis(program).graph()")
			|| Pattern.compile("(?<![A-Za-z0-9_$])build\\s*\\(\\s*program\\s*\\)").matcher(shadowBuild).find())
			violations.add("shadow does not consume buildAnalysis path");
		return List.copyOf(violations);
	}

	private static int analysisConstructionPosition(String analysisBody) {
		String compactBody = compact(analysisBody);
		int position = compactBody.indexOf("newPlacementAnalysis");
		Matcher assignment = Pattern.compile("\\bPlacementAnalysis[A-Za-z_$][A-Za-z0-9_$]*=")
			.matcher(compactBody);
		while(assignment.find()) position = Math.max(position, assignment.start());
		return position;
	}

	private static boolean sentinelEncloses(String analysisBody, String sentinelCall, int construction) {
		String body = compact(protectControlKeywords(analysisBody));
		String call = compact(sentinelCall);
		int firstSemanticStep = body.indexOf(compact(ORDERED_OCCURRENCES));
		if(construction < 0 || firstSemanticStep < 0)
			return false;
		Matcher assignments = Pattern.compile("(?:final)?(?:String|var)([A-Za-z_$][A-Za-z0-9_$]*)="
			+ Pattern.quote(call) + ";").matcher(body);
		List<Snapshot> snapshots = new ArrayList<>();
		while(assignments.find()) snapshots.add(new Snapshot(assignments.group(1), assignments.start()));
		for(Snapshot before : snapshots) {
			if(before.position() >= firstSemanticStep)
				continue;
			String directForward = before.name() + ".equals(" + call + ")";
			String directReverse = call + ".equals(" + before.name() + ")";
			if(enforcesMismatch(body, directForward, construction)
				|| enforcesMismatch(body, directReverse, construction))
				return true;
			for(Snapshot after : snapshots) {
				if(after.position() <= construction)
					continue;
				if(enforcesMismatch(body, before.name() + ".equals(" + after.name() + ")", construction)
					|| enforcesMismatch(body, after.name() + ".equals(" + before.name() + ")", construction))
					return true;
			}
		}
		return false;
	}

	private static boolean enforcesMismatch(String source, String equality, int construction) {
		Matcher enforcing = Pattern.compile("if\\(!" + Pattern.quote(equality) + "\\)\\{?throw@[^;]+;")
			.matcher(source);
		int finalReturn = source.lastIndexOf("return@");
		while(enforcing.find())
			if(enforcing.start() > construction && enforcing.end() <= finalReturn
				&& braceDepth(source, enforcing.start()) == 0
				&& source.indexOf("return@", construction + 1) >= enforcing.start())
				return true;
		return false;
	}

	private static String protectControlKeywords(String source) {
		return protectKeyword(protectKeyword(source, "throw"), "return");
	}

	private static String protectKeyword(String source, String keyword) {
		StringBuilder protectedSource = new StringBuilder(source.length() + 8);
		for(int i = 0; i < source.length();) {
			if(source.startsWith(keyword, i) && isIdentifierBoundary(source, i, keyword.length())) {
				protectedSource.append(keyword).append('@');
				i += keyword.length();
			}
			else {
				int codePoint = source.codePointAt(i);
				protectedSource.appendCodePoint(codePoint);
				i += Character.charCount(codePoint);
			}
		}
		return protectedSource.toString();
	}

	private static boolean isIdentifierBoundary(String source, int start, int length) {
		int end = start + length;
		boolean leftBoundary = start == 0 || !Character.isJavaIdentifierPart(source.codePointBefore(start));
		boolean rightBoundary = end == source.length() || !Character.isJavaIdentifierPart(source.codePointAt(end));
		return leftBoundary && rightBoundary;
	}

	private static int braceDepth(String source, int end) {
		int depth = 0;
		for(int i = 0; i < end; i++) {
			if(source.charAt(i) == '{') depth++;
			else if(source.charAt(i) == '}') depth--;
		}
		return depth;
	}

	private static String optionalMethodBody(String source, String methodName, String parameterToken) {
		try {
			return JavaSourceBoundaryScanner.methodBody(source, methodName, parameterToken);
		}
		catch(IllegalArgumentException ignored) {
			return "";
		}
	}

	private static String validBuilder() {
		return "class Builder {"
			+ " NeutralPlacementGraph build(DMLProgram program) { return buildAnalysis(program).graph(); }"
			+ " PlacementAnalysis buildAnalysis(DMLProgram program) {"
			+ " String before = PlacementGraphFingerprint.capture(program);"
			+ " String registryBefore = registrySentinel(program);"
			+ " var occurrences = " + ORDERED_OCCURRENCES + ';'
			+ " PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
			+ " if(!before.equals(PlacementGraphFingerprint.capture(program))) throw failure;"
			+ " if(!registryBefore.equals(registrySentinel(program))) throw failure;"
			+ " return analysis; } }";
	}

	private static void assertBothSentinelsRejected(String builder) {
		List<String> violations = constructionViolations(builder, validShadow());
		Assert.assertTrue(violations.contains(
			"Hop mutation sentinel does not enclose and compare the analysis construction"));
		Assert.assertTrue(violations.contains(
			"registry mutation sentinel does not enclose and compare the analysis construction"));
	}

	private static String insertBeforeClassEnd(String source, String method) {
		return source.substring(0, source.length() - 1) + method + '}';
	}

	private static String validShadow() {
		return shadowCalling("new NeutralPlacementGraphBuilder().buildAnalysis(program).graph()");
	}

	private static String shadowCalling(String expression) {
		return "class PlacementShadowCoordinator { NeutralPlacementGraph build(DMLProgram program) { return "
			+ expression + "; } }";
	}

	private static int count(String source, String token) {
		int count = 0;
		for(int from = 0; (from = source.indexOf(token, from)) >= 0; from += token.length()) count++;
		return count;
	}

	private static String compact(String source) {
		return source.replaceAll("\\s+", "");
	}

	private static String read(Path path) throws Exception {
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private record Snapshot(String name, int position) { }
}
