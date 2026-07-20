/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** Structural contract for the DP program entrypoint's single authoritative owner path. */
public class CampaignBG011DpProgramEntrypointOwnerContractTest {
	private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
	private static final Path DP_ROOT = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");
	private static final Path ENUMERATOR = ROOT.resolve(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEnumerator.java");

	@Test
	public void voidProgramEntrypointDelegatesOnceThroughAuthoritativeTypedOwnerPath() throws Exception {
		String rootSource = Files.readString(DP_ROOT);
		String rootBody = JavaSourceBoundaryScanner.methodBody(
			rootSource, "rewriteProgram", "FunctionCallSizeInfo fcallSizes");
		List<JavaSourceTokenScanner.Token> rootTokens = JavaSourceTokenScanner.tokens(rootBody);

		int acquire = sequence(rootTokens, 0, "PlacementAnalysis", "analysis", "=", "prog", ".",
			"requirePlacementAnalysisAuthority", "(", ")", ";");
		int delegate = sequence(rootTokens, Math.max(0, acquire + 1), "rewriteProgram", "(", "prog", ",",
			"fgraph", ",", "fcallSizes", ",", "analysis", ")", ";");
		List<String> failures = new ArrayList<>();
		if(!(acquire >= 0 && delegate > acquire))
			failures.add("authorityMustPrecedeDelegation");
		int delegations = countSequence(rootTokens, "rewriteProgram", "(", "prog", ",", "fgraph", ",",
			"fcallSizes", ",", "analysis", ")");
		if(delegations != 1)
			failures.add("typedDelegations=" + delegations);

		for(String forbidden : List.of("resetFederatedPlannerRunState", "FederatedPlannerDpMemoTable",
			"enumerateProgram", "computeOutputDecisions", "collectConflictsSingleBFS", "rewriteHop",
			"applyDeferredOutputDecisionStates", "registerFromProgram", "registerDpLocalMaterializeRequests"))
			if(containsIdentifier(rootTokens, forbidden))
				failures.add("duplicatePipeline=" + forbidden);
		Assert.assertEquals("G011_DP_PROGRAM_ENTRYPOINT_OWNER_BOUNDARY", List.of(), failures);
	}

	@Test
	public void obsoleteProgramEnumeratorSurfaceIsRemovedWhileReceiptPathRemains() throws Exception {
		List<JavaSourceTokenScanner.Token> tokens =
			JavaSourceTokenScanner.tokens(Files.readString(ENUMERATOR));
		Assert.assertEquals("G011_DP_LEGACY_ENUMERATE_PROGRAM_DECLARATION_REMAINS", -1,
			sequence(tokens, 0, "public", "static", "FederatedPlannerDpMemoTable", ".", "FedPlan",
				"enumerateProgram", "("));
		Assert.assertTrue("G011_DP_RECEIPT_ENUMERATOR_MUST_REMAIN",
			sequence(tokens, 0, "public", "static", "DpEnumerationResult",
				"enumerateProgramWithReceipts", "(") >= 0);
	}

	private static boolean containsIdentifier(List<JavaSourceTokenScanner.Token> tokens, String identifier) {
		return tokens.stream().anyMatch(token -> token.text().equals(identifier));
	}

	private static int countSequence(List<JavaSourceTokenScanner.Token> tokens, String... expected) {
		int count = 0;
		for(int from = 0;;) {
			int found = sequence(tokens, from, expected);
			if(found < 0)
				return count;
			count++;
			from = found + expected.length;
		}
	}

	private static int sequence(List<JavaSourceTokenScanner.Token> tokens, int from, String... expected) {
		outer: for(int i = Math.max(0, from); i + expected.length <= tokens.size(); i++) {
			for(int j = 0; j < expected.length; j++)
				if(!tokens.get(i + j).text().equals(expected[j]))
					continue outer;
			return i;
		}
		return -1;
	}
}
