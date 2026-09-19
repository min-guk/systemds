/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Strict evaluator for timeout-free G009 candidate-space performance measurements. */
public class G009PlanningPerformanceEvaluatorTest {
	private static final String OUTPUT_PROPERTY = "g009.outputSnapshot";
	private static final String EXPECTED_PROPERTY = "g009.expectedSnapshot";
	private static final String TIMING_PROPERTY = "g009.timingOutput";
	private static final String BUDGET_PROPERTY = "g009.maxPlanningMillis";
	private static final String GENERATE_PROPERTY = "g009.generateBaseline";
	private static final String WORKLOAD_PROPERTY = "g009.workload";

	@Test
	public void planningOutputMatchesExpectedSnapshotWithinBudget() throws Exception {
		String workload = System.getProperty(WORKLOAD_PROPERTY, "glm").trim();
		DMLProgram program = compileWorkload(workload);
		long started = System.nanoTime();
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		long planningMillis = (System.nanoTime() - started) / 1_000_000L;

		Path output = requiredPath(OUTPUT_PROPERTY);
		createParent(output);
		writeSnapshot(analysis, output);
		String timing = System.getProperty(TIMING_PROPERTY, "").trim();
		if(!timing.isEmpty()) {
			Path timingOutput = Path.of(timing);
			createParent(timingOutput);
			Files.writeString(timingOutput, Long.toString(planningMillis) + System.lineSeparator(),
				StandardCharsets.UTF_8);
		}

		long budgetMillis = Long.parseLong(System.getProperty(BUDGET_PROPERTY, "0"));
		if(budgetMillis > 0)
			Assert.assertTrue("planning took " + planningMillis + " ms; budget is " + budgetMillis + " ms",
				planningMillis <= budgetMillis);

		String expected = System.getProperty(EXPECTED_PROPERTY, "").trim();
		if(expected.isEmpty()) {
			Assert.assertTrue("missing expected snapshot outside explicit baseline generation",
				Boolean.parseBoolean(System.getProperty(GENERATE_PROPERTY, "false")));
		}
		else {
			Path expectedSnapshot = Path.of(expected);
			Assert.assertTrue("expected snapshot is missing: " + expectedSnapshot,
				Files.isRegularFile(expectedSnapshot));
			long mismatch = Files.mismatch(expectedSnapshot, output);
			Assert.assertEquals("candidate/support/proof snapshot differs at byte " + mismatch,
				-1L, mismatch);
		}
		System.out.println("G009_WORKLOAD=" + workload);
		System.out.println("G009_PLANNING_MILLIS=" + planningMillis);
	}

	private static DMLProgram compileWorkload(String workload) throws Exception {
		DMLProgram program = switch(workload) {
			case "two-source" ->
				NeutralPlacementGraphUploadRelocationRedTest.compileTwoFederatedSourceFixture(false);
			case "local-mix" -> NeutralPlacementGraphUploadRelocationRedTest.compileFixture();
			case "lm" -> NeutralPlacementGraphUploadRelocationRedTest.compileFunctionFixture();
			case "glm" -> NeutralPlacementGraphUploadRelocationRedTest.compileBuiltinGlmFixture();
			default -> throw new IllegalArgumentException("unknown G009 workload: " + workload);
		};
		if(!"glm".equals(workload))
			org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory
				.registerHermeticSourcePrivacy(program);
		return program;
	}

	private static void writeSnapshot(PlacementAnalysis analysis, Path output) throws Exception {
		try(BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
			writeRow(writer, "GRAPH", analysis.graph().normalizedSignature());
			for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
				writeRow(writer, "RULE", fact.key().normalizedSignature() + "|status=" + fact.status().name()
					+ "|failure=" + fact.failureCode());
				for(var emission : fact.allowedEmissionFacts()) {
					writeRow(writer, "EMISSION", fact.key().normalizedSignature() + '|'
						+ emission.normalizedSignature());
					if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
						for(CandidateSelectionReceipt receipt :
							analysis.canonicalCandidateReceipts(fact.key(), emission))
							writeRow(writer, "RECEIPT", receipt.normalizedSignature());
				}
			}
		}
	}

	private static void writeRow(BufferedWriter writer, String kind, String value) throws Exception {
		writer.write(kind);
		writer.write('\t');
		writer.write(value);
		writer.newLine();
	}

	private static Path requiredPath(String property) {
		String value = System.getProperty(property, "").trim();
		if(value.isEmpty())
			throw new IllegalArgumentException("missing system property: " + property);
		return Path.of(value);
	}

	private static void createParent(Path path) throws Exception {
		Path parent = path.toAbsolutePath().getParent();
		if(parent != null)
			Files.createDirectories(parent);
	}
}
