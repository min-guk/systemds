/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Structural contract for planner-neutral acquisition of external configuration. */
public class CampaignBDpExternalConfigurationBoundaryContractTest {
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path PLANNER_ROOT = ROOT.resolve("src/main/java/org/apache/sysds/hops/fedplanner");
	private static final Path CONFIGURATION = ROOT.resolve(
		"src/main/java/org/apache/sysds/conf/FederatedPlannerConfiguration.java");
	private static final List<Path> CONSUMERS = List.of(
		PLANNER_ROOT.resolve("FederatedRefedPolicy.java"),
		PLANNER_ROOT.resolve("fedCostBased/FederatedPlannerTrace.java"),
		PLANNER_ROOT.resolve("fedCostBased/commons/FederatedCostModel.java"));

	@Test
	public void plannerConsumersUseTheNeutralConfigurationBoundary() throws Exception {
		for(Path consumer : CONSUMERS) {
			List<JavaSourceTokenScanner.Token> tokens = JavaSourceTokenScanner.tokens(Files.readString(consumer));
			Assert.assertFalse(consumer.toString(),
				JavaSourceTokenScanner.containsSequence(tokens, "System", ".", "getProperty", "("));
			Assert.assertTrue(consumer.toString(), JavaSourceTokenScanner.containsSequence(tokens,
				"import", "org", ".", "apache", ".", "sysds", ".", "conf", ".",
				"FederatedPlannerConfiguration", ";"));
			Assert.assertTrue(consumer.toString(), tokens.stream()
				.anyMatch(token -> token.text().equals("FederatedPlannerConfiguration")));
		}
	}

	@Test
	public void configurationBoundaryIsDirectAndPlannerNeutral() throws Exception {
		Assert.assertTrue("configuration boundary must exist", Files.isRegularFile(CONFIGURATION));
		Assert.assertFalse("configuration boundary must remain outside planner index",
			CONFIGURATION.normalize().startsWith(PLANNER_ROOT.normalize()));
		List<JavaSourceTokenScanner.Token> tokens =
			JavaSourceTokenScanner.tokens(Files.readString(CONFIGURATION));
		Assert.assertTrue(JavaSourceTokenScanner.containsSequence(tokens, "System", ".", "getProperty", "("));
		Assert.assertTrue(JavaSourceTokenScanner.containsSequence(tokens, "System", ".", "getenv", "("));
		for(String forbidden : List.of("reflect", "MethodHandles", "AccessController", "getProperties",
			"fedplanner", "Hop", "FType", "Runtime", "selector", "traversal", "materialization"))
			Assert.assertFalse("forbidden configuration-boundary token: " + forbidden,
				tokens.stream().anyMatch(token -> token.text().equals(forbidden)));
		for(String method : List.of("captureProperty", "captureTrimmedPropertyOrEnvironment",
			"captureNonEmptyPropertyOrEnvironment"))
			Assert.assertTrue("missing exact configuration API: " + method,
				tokens.stream().anyMatch(token -> token.text().equals(method)));
	}

	@Test
	public void costModelUsesOnlyTheNeutralTypedConfigurationOwner() throws Exception {
		Path costModel = PLANNER_ROOT.resolve("fedCostBased/commons/FederatedCostModel.java");
		List<JavaSourceTokenScanner.Token> tokens = JavaSourceTokenScanner.tokens(Files.readString(costModel));

		Assert.assertFalse("CostModel must not retain the private typed configuration owner",
			tokens.stream().anyMatch(token -> token.text().equals("getConfiguredDouble")));
		Assert.assertFalse("CostModel must not parse external doubles directly",
			JavaSourceTokenScanner.containsSequence(tokens, "Double", ".", "parseDouble", "("));
		Assert.assertEquals("all thirteen CostModel constants must use the neutral typed configuration API", 13,
			countSequence(tokens, "FederatedPlannerConfiguration", ".",
				"captureDoublePropertyOrEnvironment", "("));
	}

	private static int countSequence(List<JavaSourceTokenScanner.Token> tokens, String... sequence) {
		int count = 0;
		for(int i = 0; i + sequence.length <= tokens.size(); i++) {
			boolean match = true;
			for(int j = 0; j < sequence.length; j++)
				match &= tokens.get(i + j).text().equals(sequence[j]);
			if(match)
				count++;
		}
		return count;
	}
}
