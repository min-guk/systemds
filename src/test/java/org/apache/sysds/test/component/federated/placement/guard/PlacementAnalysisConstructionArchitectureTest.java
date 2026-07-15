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
			+ "   String fake = \"return build(program); projectConcreteOrigins\";"
			+ "   return buildAnalysis(program).graph(); }"
			+ " PlacementAnalysis buildAnalysis(DMLProgram program) {"
			+ "   String before = PlacementGraphFingerprint.capture(program);"
			+ "   String registryBefore = registrySentinel(program);"
			+ "   var occurrences = PlacementGraphFingerprint.orderedOccurrences(program);"
			+ "   PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
			+ "   String after = PlacementGraphFingerprint.capture(program);"
			+ "   if(!registryBefore.equals(registrySentinel(program))) throw failure;"
			+ "   return analysis; }"
			+ " // PlacementAnalysis buildAnalysis(DMLProgram program) { return build(program); }"
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

		String duplicate = validBuilder().replace("PlacementAnalysis analysis =",
			"var duplicate = " + ORDERED_OCCURRENCES + "; PlacementAnalysis analysis =");
		Assert.assertTrue(constructionViolations(duplicate, validShadow()).contains(
			"buildAnalysis must perform exactly one ordered occurrence pass"));
	}

	@Test
	public void postHocOriginMatchingAndEarlySentinelFixturesAreRejected() {
		String postHoc = validBuilder().replace("PlacementAnalysis analysis =",
			"var projectConcreteOrigins = graph.nodes().stream().map(Node::key)"
				+ ".filter(key -> key.callSitePath().equals(path) && key.emittedHopInstance().equals(topology)"
				+ " && key.canonicalSourceOrigin().equals(source)); PlacementAnalysis analysis =");
		List<String> matching = constructionViolations(postHoc, validShadow());
		Assert.assertTrue(matching.contains("post-hoc origin projection helper is forbidden"));
		Assert.assertTrue(matching.contains("post-hoc path/topology/source matching is forbidden"));

		String early = validBuilder()
			.replace("PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);", "")
			.replace("return analysis;", "return new PlacementAnalysis(graph, projections);");
		Assert.assertTrue(constructionViolations(early, validShadow()).contains(
			"mutation sentinels do not enclose PlacementAnalysis construction"));
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
		if(count(compact(analysis), compact(ORDERED_OCCURRENCES)) != 1)
			violations.add("buildAnalysis must perform exactly one ordered occurrence pass");
		if(builderCode.matches("(?s).*\\bprojectConcreteOrigins\\b.*"))
			violations.add("post-hoc origin projection helper is forbidden");
		if(builderCode.matches("(?s).*graph\\s*\\.\\s*nodes\\s*\\(\\)\\s*\\.\\s*stream\\s*\\(\\)"
			+ ".{0,1200}callSitePath\\s*\\(\\).{0,1200}emittedHopInstance\\s*\\(\\)"
			+ ".{0,1200}canonicalSourceOrigin\\s*\\(\\).*"))
			violations.add("post-hoc path/topology/source matching is forbidden");
		int construction = analysisConstructionPosition(analysis);
		int finalHopSentinel = analysis.lastIndexOf("PlacementGraphFingerprint.capture(program)");
		int finalRegistrySentinel = analysis.lastIndexOf("registrySentinel(program)");
		if(construction < 0 || finalHopSentinel < construction || finalRegistrySentinel < construction)
			violations.add("mutation sentinels do not enclose PlacementAnalysis construction");
		String shadowBuild = JavaSourceBoundaryScanner.methodBody(shadowSource, "build", "DMLProgram");
		if(!compact(shadowBuild).contains("buildAnalysis(program).graph()")
			|| Pattern.compile("(?<![A-Za-z0-9_$])build\\s*\\(\\s*program\\s*\\)").matcher(shadowBuild).find())
			violations.add("shadow does not consume buildAnalysis path");
		return List.copyOf(violations);
	}

	private static int analysisConstructionPosition(String analysisBody) {
		int position = analysisBody.indexOf("new PlacementAnalysis");
		Matcher assignment = Pattern.compile("\\bPlacementAnalysis\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*=")
			.matcher(analysisBody);
		while(assignment.find()) position = Math.max(position, assignment.start());
		return position;
	}

	private static String validBuilder() {
		return "class Builder {"
			+ " NeutralPlacementGraph build(DMLProgram program) { return buildAnalysis(program).graph(); }"
			+ " PlacementAnalysis buildAnalysis(DMLProgram program) {"
			+ " String before = PlacementGraphFingerprint.capture(program);"
			+ " String registryBefore = registrySentinel(program);"
			+ " var occurrences = " + ORDERED_OCCURRENCES + ';'
			+ " PlacementAnalysis analysis = new PlacementAnalysis(graph, projections);"
			+ " String after = PlacementGraphFingerprint.capture(program);"
			+ " if(!registryBefore.equals(registrySentinel(program))) throw failure;"
			+ " return analysis; } }";
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
}
