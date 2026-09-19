/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/** Explicit opt-in evaluator used by the G009 performance evidence scripts. */
public class SearchSpaceMetricsEvaluatorTest {
	@Test
	public void requestedFixtureWritesBoundedAggregateMetrics() throws Exception {
		String output = System.getProperty("g009.metrics.output", "");
		Assume.assumeTrue("set -Dg009.metrics.output=<path> to run the GLM evaluator", !output.isEmpty());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		long started = System.nanoTime();
		String fixture = System.getProperty("g009.metrics.fixture", "glm");
		Path path = Path.of(output);
		Path parent = path.toAbsolutePath().getParent();
		if(parent != null)
			Files.createDirectories(parent);
		PlacementAnalysis analysis = null;
		try {
			analysis = new NeutralPlacementGraphBuilder(null, metrics)
				.buildAnalysis("actions".equals(fixture)
					? NeutralPlacementFixedPointCompositionTest.compileProtected(
						NeutralPlacementFixedPointCompositionTest.ACTIONS)
					: NeutralPlacementGraphUploadRelocationRedTest.compileBuiltinGlmFixture());
			if("glm".equals(fixture))
				Assert.assertTrue(analysis.graph().constraints().stream().anyMatch(constraint ->
					constraint.evidence().equals("inlined-function-result:new_z")));
		}
		catch(RuntimeException | Error failure) {
			Files.writeString(path, toJson(metrics.snapshot(), System.nanoTime() - started, analysis),
				StandardCharsets.UTF_8);
			throw failure;
		}
		Files.writeString(path, toJson(metrics.snapshot(), System.nanoTime() - started, analysis),
			StandardCharsets.UTF_8);
	}

	private static String toJson(SearchSpaceMetrics.Snapshot snapshot, long elapsedNanos,
		PlacementAnalysis analysis) throws ReflectiveOperationException {
		StringBuilder json = new StringBuilder("{\n");
		json.append("  \"elapsedNanos\": ").append(elapsedNanos).append(",\n");
		json.append("  \"analysisFingerprint\": \"")
			.append(analysis == null ? "INCOMPLETE" : escape(analysis.analysisFingerprint())).append("\",\n");
		json.append("  \"nodeCount\": ").append(analysis == null ? -1 : analysis.graph().nodes().size())
			.append(",\n");
		json.append("  \"candidateFactCount\": ")
			.append(analysis == null ? -1 : analysis.candidateRuleFacts().orderedFacts().size()).append(",\n");
		json.append("  \"relocationActionCount\": ")
			.append(analysis == null ? -1 : analysis.graph().relocationActions().size()).append(",\n");
		json.append("  \"metrics\": {\n");
		RecordComponent[] fields = SearchSpaceMetrics.Snapshot.class.getRecordComponents();
		for(int index = 0; index < fields.length; index++) {
			RecordComponent field = fields[index];
			json.append("    \"").append(field.getName()).append("\": ")
				.append(field.getAccessor().invoke(snapshot));
			json.append(index + 1 == fields.length ? "\n" : ",\n");
		}
		return json.append("  }\n}\n").toString();
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r");
	}
}
