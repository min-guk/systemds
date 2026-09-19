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
			var program = switch(fixture) {
				case "actions" -> NeutralPlacementFixedPointCompositionTest.compileProtected(
					NeutralPlacementFixedPointCompositionTest.ACTIONS);
				case "lm" -> NeutralPlacementGraphUploadRelocationRedTest.compileFunctionFixture();
				case "glm" -> NeutralPlacementGraphUploadRelocationRedTest.compileBuiltinGlmFixture();
				default -> throw new IllegalArgumentException("unknown G009 metrics fixture: " + fixture);
			};
			if("lm".equals(fixture))
				org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory
					.registerHermeticSourcePrivacy(program);
			analysis = new NeutralPlacementGraphBuilder(null, metrics)
				.buildAnalysis(program);
			if("glm".equals(fixture))
				Assert.assertTrue(analysis.graph().constraints().stream().anyMatch(constraint ->
					constraint.evidence().equals("inlined-function-result:new_z")));
		}
		catch(RuntimeException | Error failure) {
			Files.writeString(path, toJson(metrics.snapshot(), metrics.attributionSnapshot(),
				System.nanoTime() - started, analysis),
				StandardCharsets.UTF_8);
			throw failure;
		}
		Files.writeString(path, toJson(metrics.snapshot(), metrics.attributionSnapshot(),
			System.nanoTime() - started, analysis),
			StandardCharsets.UTF_8);
	}

	private static String toJson(SearchSpaceMetrics.Snapshot snapshot,
		SearchSpaceMetrics.AttributionSnapshot attribution, long elapsedNanos,
		PlacementAnalysis analysis) throws ReflectiveOperationException {
		Assert.assertEquals("every proof query must have one bounded context observation",
			snapshot.proofQueries(), snapshot.exactContextUniqueQueries()
				+ snapshot.exactContextRepeatedQueries() + snapshot.exactContextOverflowQueries());
		StringBuilder json = new StringBuilder("{\n");
		json.append("  \"schema\": \"g009-search-attribution-v2\",\n");
		json.append("  \"partitionSemantics\": \"exclusive-adds-to-analysis-inclusive\",\n");
		json.append("  \"cpuSemantics\": \"current-thread-minus-one-unknown\",\n");
		json.append("  \"allocationSemantics\": \"current-thread-coarse-minus-one-unknown\",\n");
		json.append("  \"elapsedNanos\": ").append(elapsedNanos).append(",\n");
		json.append("  \"observerOverheadFraction\": ")
			.append(elapsedNanos == 0 ? 0.0
				: (double) attribution.phase(SearchSpaceMetrics.Phase.CONTEXT_OBSERVER)
					.inclusiveWallNanos() / elapsedNanos).append(",\n");
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
		json.append("  },\n");
		appendAttribution(json, attribution);
		return json.append("}\n").toString();
	}

	static void appendAttribution(StringBuilder json,
		SearchSpaceMetrics.AttributionSnapshot attribution) {
		SearchSpaceMetrics.ContextDistribution distribution = attribution.contextDistribution();
		json.append("  \"contextDistribution\": {\"unique\": ").append(distribution.unique())
			.append(", \"repeated\": ").append(distribution.repeated())
			.append(", \"overflow\": ").append(distribution.overflow())
			.append(", \"total\": ").append(distribution.total())
			.append(", \"uniqueWeight\": ").append(distribution.uniqueWeight())
			.append(", \"repeatedWeight\": ").append(distribution.repeatedWeight())
			.append(", \"overflowWeight\": ").append(distribution.overflowWeight())
			.append("},\n  \"phasePartition\": {\n");
		for(int index = 0; index < attribution.phases().size(); index++) {
			SearchSpaceMetrics.PhaseMeasurement phase = attribution.phases().get(index);
			json.append("    \"").append(phase.phase()).append("\": {\"calls\": ")
				.append(phase.calls()).append(", \"inclusiveWallNanos\": ")
				.append(phase.inclusiveWallNanos()).append(", \"exclusiveWallNanos\": ")
				.append(phase.exclusiveWallNanos()).append(", \"inclusiveCpuNanos\": ")
				.append(phase.inclusiveCpuNanos()).append(", \"exclusiveCpuNanos\": ")
				.append(phase.exclusiveCpuNanos()).append(", \"inclusiveAllocatedBytes\": ")
				.append(phase.inclusiveAllocatedBytes()).append(", \"exclusiveAllocatedBytes\": ")
				.append(phase.exclusiveAllocatedBytes()).append('}')
				.append(index + 1 == attribution.phases().size() ? "\n" : ",\n");
		}
		json.append("  }\n");
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r");
	}
}
