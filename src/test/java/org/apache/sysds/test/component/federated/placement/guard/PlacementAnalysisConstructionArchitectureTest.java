/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;

/** Syntax-aware locks for the single-pass placement-analysis construction boundary. */
public class PlacementAnalysisConstructionArchitectureTest {
	private static final Path ROOT = Paths.get("").toAbsolutePath().normalize();
	private static final Path BUILDER = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java");
	private static final Path SHADOW = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementShadowCoordinator.java");
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
