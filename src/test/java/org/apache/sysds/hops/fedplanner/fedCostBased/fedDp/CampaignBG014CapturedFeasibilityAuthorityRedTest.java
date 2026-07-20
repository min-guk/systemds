/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy.CapturedPlacementRequest;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable behavioral RED for immutable, exact DP captured-feasibility ownership. */
public class CampaignBG014CapturedFeasibilityAuthorityRedTest {
	private static final Path POLICY_SOURCE = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/ExecPlacementPolicy.java");
	private static final Path ADAPTER_SOURCE = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java");

	@Test
	public void capturedRequestCarriesExactOccurrenceReceiptAndOrderedInputFacts() throws Exception {
		String source = codeOnly(Files.readString(POLICY_SOURCE));
		String header = recordHeader(source, "CapturedPlacementRequest");
		String constructor = compactRecordConstructorBody(source, "CapturedPlacementRequest");
		String decision = methodBody(source, "Decision", "decideCaptured", "CapturedPlacementRequest");
		String adapter = codeOnly(Files.readString(ADAPTER_SOURCE));
		String resolution = methodBody(adapter, "CandidateDecisionReceipt", "resolveCandidateDecision",
			"NeutralEnumerationContext", "NormalizedCandidateInputs", "long");
		Assert.assertTrue("G014_RED3_CAPTURED_REQUEST_MISSING_EXACT_OCCURRENCE",
			Pattern.compile("CandidateOccurrenceSnapshot\\s+candidateSnapshot").matcher(header).find());
		Assert.assertTrue("G014_RED3_CAPTURED_REQUEST_MISSING_INVOCATION_EVIDENCE",
			Pattern.compile("CapturedInvocationEvidence\\s+invocationEvidence").matcher(header).find());
		Assert.assertTrue("G014_RED3_CAPTURED_REQUEST_MISSING_ORDERED_ORACLE_INPUTS",
			Pattern.compile("List\\s*<\\s*OracleInputState\\s*>\\s+orderedOracleInputs").matcher(header).find());
		Assert.assertTrue("G014_RED3_POLICY_DID_NOT_VALIDATE_CAPTURED_RECEIPT_IDENTITY",
			Pattern.compile("candidateSnapshot\\s*\\(\\s*\\)\\s*\\.\\s*context\\s*\\(\\s*\\)\\s*!="
				+ "\\s*invocationEvidence\\s*\\(\\s*\\)\\s*\\.\\s*context\\s*\\(\\s*\\)")
				.matcher(constructor).find());
		Assert.assertTrue("G014_RED3_POLICY_DID_NOT_REJECT_MISSING_OR_REORDERED_FACTS",
			Pattern.compile("orderedOracleInputs\\s*\\(\\s*\\).*candidateSnapshot\\s*\\(\\s*\\)"
				+ "\\s*\\.\\s*orderedOracleInputs\\s*\\(\\s*\\)", Pattern.DOTALL).matcher(constructor).find());
		Assert.assertTrue("G014_RED3_ADAPTER_DROPPED_EXACT_CAPTURED_AUTHORITY",
			Pattern.compile("new\\s+CapturedPlacementRequest\\s*\\(\\s*parentHop\\s*,\\s*privacy\\s*,"
				+ "\\s*resolved\\s*\\.\\s*logicalFType\\s*\\(\\s*\\)\\s*,\\s*caps\\s*,\\s*snapshot\\s*,"
				+ "\\s*invocationEvidence\\s*,\\s*snapshot\\s*\\.\\s*orderedOracleInputs\\s*\\(\\s*\\)\\s*\\)")
				.matcher(resolution).find());
		Assert.assertTrue("G014_RED3_DECISION_DID_NOT_CONSUME_EXACT_CAPTURED_AUTHORITY",
			Pattern.compile("request\\s*\\.\\s*candidateSnapshot\\s*\\(\\s*\\).*request\\s*\\.\\s*"
				+ "invocationEvidence\\s*\\(\\s*\\).*request\\s*\\.\\s*orderedOracleInputs\\s*\\(\\s*\\)",
				Pattern.DOTALL).matcher(decision).find());
		Assert.assertTrue("G014_RED3_POLICY_RECONSTRUCTED_INPUT_AUTHORITY",
			!compact(header).contains("Map<Long,FType>effectiveFTypes"));
	}

	@Test
	public void exactAnchorDecisionRetainsCompleteCapturedAuthority() {
		DpInvocationReceipt invocation = invoke("B-11");
		CandidateDecisionReceipt captured = derivedFoutDecision(invocation);
		Assert.assertTrue("the exact B-11 anchor must enable the captured FOUT arm", captured.allowFEDFOUT());
		Assert.assertSame(captured.context(), captured.candidateSnapshot().context());
		Assert.assertEquals(captured.candidateSnapshot().orderedOracleInputs(), captured.orderedOracleInputs());
		Assert.assertNotNull(captured.invocationEvidence());
		Assert.assertFalse("captured authority must include ordered input facts",
			captured.orderedOracleInputs().isEmpty());
	}

	@Test
	public void legacyCapturedRequestRejectsMissingReorderedAndIncompatibleFactsWithoutMutation() {
		DpInvocationReceipt invocation = invoke("B-17");
		CandidateDecisionReceipt captured = invocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream()
			.filter(value -> value.orderedOracleInputs().size() >= 2)
			.findFirst().orElseThrow(() -> new AssertionError("B-17 must expose ordered multi-input authority"));
		Hop parent = invocation.analysis().hop(captured.candidateSnapshot().parentOccurrence()).orElseThrow();
		Assert.assertEquals(parent.getInput().size(), captured.orderedOracleInputs().size());

		LinkedHashMap<Long, FType> ordered = legacyFacts(parent, captured, false);
		LinkedHashMap<Long, FType> missing = new LinkedHashMap<>(ordered);
		missing.remove(parent.getInput().get(0).getHopID());
		LinkedHashMap<Long, FType> reordered = legacyFacts(parent, captured, true);
		LinkedHashMap<Long, FType> incompatible = new LinkedHashMap<>(ordered);
		long firstInput = parent.getInput().get(0).getHopID();
		incompatible.put(firstInput, differentFType(incompatible.get(firstInput)));
		PlannerState before = plannerState(invocation);

		assertLegacyRequestRejected(parent, captured, missing, "missing ordered fact");
		assertLegacyRequestRejected(parent, captured, reordered, "reordered facts hidden by Map authority");
		assertLegacyRequestRejected(parent, captured, incompatible, "incompatible fact");
		Assert.assertEquals("negative captured requests mutated planner/global state", before, plannerState(invocation));
	}

	@Test
	public void sourceScannerRemovesCommentsLiteralsAndEscapedTextBlockQuotes() {
		assertSanitizer(codeOnly(sanitizerFixture()));
	}

	@Test
	public void missingAnchorCannotBePromotedByInjectedInputTypes() {
		DpInvocationReceipt invocation = invoke("B-12");
		CandidateDecisionReceipt captured = invocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream().filter(value -> value.allowFEDLOUT() && !value.allowFEDFOUT())
			.findFirst().orElseThrow(() -> new AssertionError("B-12 must expose a missing-anchor FED/LOUT arm"));
		Assert.assertFalse("injected broad input types cannot synthesize exact missing anchor authority",
			captured.allowFEDFOUT());
	}

	@Test
	public void ambiguousCallSiteAuthorityRejectsMissingAndIncompatibleFactsWithoutPublication() {
		DpInvocationReceipt invocation = invoke("B-17");
		CandidateDecisionReceipt captured = invocation.semanticConsumption().semanticBlock()
			.candidateDecisionReceipts().stream()
			.filter(value -> invocation.analysis().hop(value.candidateSnapshot().parentOccurrence())
				.map(hop -> hop.getInput().size() >= 2).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError("B-17 must expose conflicting multi-input authority"));
		PlannerState before = plannerState(invocation);

		Assert.assertFalse("ambiguous captured authority cannot publish FED/FOUT", captured.allowFEDFOUT());
		Assert.assertEquals(captured.candidateSnapshot().orderedOracleInputs(), captured.orderedOracleInputs());
		Assert.assertEquals("ambiguous rejection mutated planner/global state", before, plannerState(invocation));
	}

	private static CandidateDecisionReceipt derivedFoutDecision(DpInvocationReceipt invocation) {
		return invocation.semanticConsumption().semanticBlock().candidateDecisionReceipts().stream()
			.filter(value -> value.allowFEDLOUT() && value.allowFEDFOUT())
			.findFirst().orElseThrow(() -> new AssertionError("B-11 must exercise derived FED/FOUT feasibility"));
	}

	private static String recordHeader(String source, String name) {
		Matcher matcher = Pattern.compile("\\brecord\\s+" + Pattern.quote(name) + "\\s*\\(([^)]*)\\)",
			Pattern.DOTALL).matcher(source);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String compactRecordConstructorBody(String source, String name) {
		Matcher record = Pattern.compile("\\brecord\\s+" + Pattern.quote(name) + "\\b").matcher(source);
		if(!record.find()) return "";
		String body = balancedBody(source, record.end());
		Matcher constructor = Pattern.compile("\\bpublic\\s+" + Pattern.quote(name) + "\\s*\\{").matcher(body);
		return constructor.find() ? balancedBody(body, constructor.start()) : "";
	}

	private static String methodBody(String source, String returnType, String name, String parameterType) {
		Matcher matcher = Pattern.compile("\\b" + Pattern.quote(returnType) + "\\s+" + Pattern.quote(name)
			+ "\\s*\\(\\s*" + Pattern.quote(parameterType) + "\\s+\\w+\\s*\\)").matcher(source);
		return matcher.find() ? balancedBody(source, matcher.end()) : "";
	}

	private static String balancedBody(String source, int from) {
		int open = source.indexOf('{', from);
		int depth = 0;
		for(int i = open; i >= 0 && i < source.length(); i++) {
			char current = source.charAt(i);
			if(current == '{') depth++;
			else if(current == '}' && --depth == 0) return source.substring(open, i + 1);
		}
		return "";
	}

	private static String codeOnly(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int state = 0;
		for(int i = 0; i < source.length(); i++) {
			char current = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
			if(state == 0 && current == '/' && next == '/') { state = 1; out.append("  "); i++; }
			else if(state == 0 && current == '/' && next == '*') { state = 2; out.append("  "); i++; }
			else if(state == 0 && current == '"' && next == '"'
				&& i + 2 < source.length() && source.charAt(i + 2) == '"') {
				state = 5; out.append("   "); i += 2;
			}
			else if(state == 0 && current == '"') { state = 3; out.append(' '); }
			else if(state == 0 && current == '\'') { state = 4; out.append(' '); }
			else if(state == 1 && current == '\n') { state = 0; out.append('\n'); }
			else if(state == 2 && current == '*' && next == '/') { state = 0; out.append("  "); i++; }
			else if((state == 3 || state == 4) && current == '\\' && next != '\0') { out.append("  "); i++; }
			else if(state == 3 && current == '"') { state = 0; out.append(' '); }
			else if(state == 4 && current == '\'') { state = 0; out.append(' '); }
			else if(state == 5 && current == '\\' && next != '\0') {
				out.append(' ');
				out.append(next == '\n' || next == '\r' ? next : ' ');
				i++;
			}
			else if(state == 5 && current == '"' && next == '"'
				&& i + 2 < source.length() && source.charAt(i + 2) == '"') {
				state = 0; out.append("   "); i += 2;
			}
			else if(state == 0) out.append(current);
			else out.append(current == '\n' ? '\n' : ' ');
		}
		return out.toString();
	}

	private static LinkedHashMap<Long, FType> legacyFacts(Hop parent, CandidateDecisionReceipt captured,
		boolean reverse) {
		LinkedHashMap<Long, FType> facts = new LinkedHashMap<>();
		for(int offset = 0; offset < parent.getInput().size(); offset++) {
			int index = reverse ? parent.getInput().size() - 1 - offset : offset;
			OracleInputState state = captured.orderedOracleInputs().get(index);
			FType type = state == OracleInputState.ABSENT_LOCAL
				? (captured.logicalFType() == null ? FType.ROW : captured.logicalFType())
				: FType.valueOf(state.name());
			facts.put(parent.getInput().get(index).getHopID(), type);
		}
		return facts;
	}

	private static FType differentFType(FType type) {
		for(FType candidate : FType.values())
			if(candidate != type) return candidate;
		throw new AssertionError("FType must expose an incompatible value");
	}

	private static void assertLegacyRequestRejected(Hop parent, CandidateDecisionReceipt captured,
		Map<Long, FType> facts, String label) {
		try {
			ExecPlacementPolicy.decideCaptured(new CapturedPlacementRequest(parent, captured.privacy(),
				captured.logicalFType(), captured.capabilityFact(), facts));
			Assert.fail("accepted " + label);
		}
		catch(IllegalArgumentException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	private static String sanitizerFixture() {
		String triple = "\"\"\"";
		String slash = "\\";
		String escapedTriple = slash + triple;
		return "REAL_BEFORE(); // DECOY_LINE();\\n"
			+ "/* DECOY_BLOCK(); */ char c = '{'; String s = \"DECOY_STRING();\";\\n"
			+ "String escaped = \"prefix " + slash + "\" DECOY_ESCAPED_STRING();\";\\n"
			+ "String block = " + triple + "\\nDECOY_TEXT_BLOCK();\\n" + escapedTriple
			+ " DECOY_AFTER_ESCAPED_TRIPLE();\\n" + triple + ";\\nREAL_AFTER();\\n";
	}

	private static void assertSanitizer(String source) {
		Assert.assertTrue(source.contains("REAL_BEFORE();"));
		Assert.assertTrue(source.contains("REAL_AFTER();"));
		Assert.assertFalse("sanitizer leaked a brace from a char literal", source.contains("{"));
		for(String decoy : List.of("DECOY_LINE", "DECOY_BLOCK", "DECOY_STRING", "DECOY_ESCAPED_STRING",
			"DECOY_TEXT_BLOCK", "DECOY_AFTER_ESCAPED_TRIPLE"))
			Assert.assertFalse("sanitizer leaked " + decoy, source.contains(decoy));
	}

	private static DpInvocationReceipt invoke(String fixture) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER,
					"compile_cost_based");
				new DMLTranslator(program).constructLops(program, receipt::set);
			}
			finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return (DpInvocationReceipt) receipt.get();
		}
		catch(Exception e) { throw new AssertionError("Unable to compile captured-feasibility fixture " + fixture, e); }
	}

	private static String compact(String source) {
		return source.replaceAll("\\s+", "");
	}

	private static PlannerState plannerState(DpInvocationReceipt invocation) {
		return new PlannerState(invocation.analysis().analysisFingerprint(),
			FederatedPlannerUtils.snapshotFedState(), FederatedPlannerUtils.snapshotFedAnchorKeys(),
			invocation.counters().repairCount(), invocation.counters().fallbackCount(),
			invocation.counters().reenumerationCount());
	}

	private record PlannerState(String analysisFingerprint,
		Map<String, FederatedPlannerUtils.FedVarSnapshot> fedState, Map<String, String> anchorKeys,
		int repairs, int fallbacks, int reenumerations) { }
}
