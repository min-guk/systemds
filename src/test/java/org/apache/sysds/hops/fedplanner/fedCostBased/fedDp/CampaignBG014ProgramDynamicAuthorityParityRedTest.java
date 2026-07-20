/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpDynamicInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED for final program/dynamic DP authority and adapter-receipt parity. */
public class CampaignBG014ProgramDynamicAuthorityParityRedTest {
	private static final Path DP_SOURCE = Path.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java");

	@Test
	public void dynamicReceiptCarriesExactSelectionAndAppliedRootAuthority() throws Exception {
		String source = executableSource(Files.readString(DP_SOURCE));
		String header = recordHeader(source, "DpDynamicInvocationReceipt");
		String constructor = compactRecordConstructorBody(source, "DpDynamicInvocationReceipt");
		String rewrite = methodBody(source, "DpDynamicInvocationReceipt", "rewriteFunctionDynamic",
			"FunctionStatementBlock", "LocalVariableMap", "PlacementAnalysis");
		Assert.assertFalse("G014_RED5_DYNAMIC_CONSTRUCTOR_SCOPE_NOT_FOUND", constructor.isEmpty());
		Assert.assertFalse("G014_RED5_EXACT_REWRITE_SCOPE_NOT_FOUND", rewrite.isEmpty());
		Assert.assertTrue("G014_RED5_DYNAMIC_RECEIPT_MISSING_EXACT_SELECTION",
			Pattern.compile("\\bExactSelection\\s+exactSelection\\b.*"
				+ "\\bList<AppliedPlanReceipt>\\s+appliedPlans\\b", Pattern.DOTALL).matcher(header).find());
		Assert.assertTrue("G014_RED5_DYNAMIC_RECEIPT_DID_NOT_BIND_SELECTION_TO_ENUMERATION",
			Pattern.compile("exactSelection\\s*\\.\\s*analysis\\s*\\(\\s*\\)\\s*!=\\s*analysis"
				+ ".*exactSelection\\s*\\.\\s*memo\\s*\\(\\s*\\)\\s*!=\\s*memoTable", Pattern.DOTALL)
				.matcher(constructor).find());
		Assert.assertTrue("G014_RED5_DYNAMIC_RECEIPT_DID_NOT_BIND_APPLIED_ROOT_IDENTITY",
			Pattern.compile("applied\\s*\\.\\s*plan\\s*\\(\\s*\\)\\s*!=\\s*exactSelection\\s*\\.\\s*"
				+ "selectedRootPlans\\s*\\(\\s*\\)\\s*\\.\\s*get\\s*\\(\\s*i\\s*\\)", Pattern.DOTALL)
				.matcher(constructor).find());
		Assert.assertTrue("G014_RED5_DYNAMIC_RETURN_DROPPED_EXACT_AUTHORITY",
			Pattern.compile("return\\s+new\\s+DpDynamicInvocationReceipt\\s*\\(\\s*analysis\\s*,\\s*memoTable"
				+ "\\s*,\\s*enumerationResult\\s*,\\s*exactSelection\\s*,\\s*appliedPlans\\s*,"
				+ "\\s*fingerprintBefore\\s*,\\s*fingerprintAfter\\s*\\)", Pattern.DOTALL)
				.matcher(rewrite).find());
	}

	@Test
	public void sourceScannerRemovesCommentsLiteralsAndEscapedTextBlockQuotes() {
		assertSanitizer(executableSource(sanitizerFixture()));
	}

	@Test
	public void programAndDynamicEntrypointsRetainExactAdapterReceipts() {
		ProgramInvocation owner = invokeProgram("B-21");
		FunctionStatementBlock function = owner.program().getFunctionStatementBlock(
			DMLProgram.DEFAULT_NAMESPACE, "f");
		assertAppliedPlansAreExactReceipts(owner.receipt());
		DpDynamicInvocationReceipt dynamic = new FederatedPlannerDpFedCostBased().rewriteFunctionDynamic(
			function, new LocalVariableMap(), owner.receipt().analysis());

		Assert.assertSame(owner.receipt().analysis(), owner.receipt().exactSelection().analysis());
		Assert.assertSame(owner.receipt().analysis(), owner.receipt().semanticConsumption().analysis());
		Assert.assertSame(owner.receipt().analysis(), dynamic.analysis());
		Assert.assertSame(dynamic.analysis(), dynamic.memoTable().analysis());
		Assert.assertSame(dynamic.analysis(), dynamic.enumerationResult().rewireSnapshot().analysis());
		Assert.assertSame(dynamic.analysis(), dynamic.enumerationResult().semanticBlock().context().analysis());
		Assert.assertFalse("B-21 must exercise an exact function selection",
			owner.receipt().exactSelection().selectedRootPlans().isEmpty());
		Assert.assertEquals(dynamic.fingerprintBefore(), dynamic.fingerprintAfter());
		Assert.assertEquals(dynamic.analysis().analysisFingerprint(), dynamic.fingerprintAfter());
	}

	@Test
	public void foreignAndUnboundAuthorityRejectBeforePlannerStatePublication() {
		ProgramInvocation owner = invokeProgram("B-21");
		DMLProgram foreignProgram = compile("B-21");
		PlacementAnalysis foreign = new NeutralPlacementGraphBuilder().buildAnalysis(foreignProgram);
		FunctionStatementBlock function = owner.program().getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "f");
		FederatedPlannerDpFedCostBased planner = new FederatedPlannerDpFedCostBased();

		BoundaryState before = snapshot(owner.program(), owner.receipt().analysis());
		assertRejects(() -> planner.rewriteProgram(owner.program(), null, null, foreign), "foreign program authority");
		assertRejects(() -> planner.rewriteFunctionDynamic(function, new LocalVariableMap(), foreign),
			"foreign dynamic authority");
		Assert.assertEquals("foreign authority rejection mutated program/global state", before,
			snapshot(owner.program(), owner.receipt().analysis()));

		DMLProgram unbound = compile("B-21");
		FunctionStatementBlock unboundFunction = unbound.getFunctionStatementBlock(DMLProgram.DEFAULT_NAMESPACE, "f");
		BoundaryState unboundBefore = snapshot(unbound, null);
		assertRejects(() -> planner.rewriteProgram(unbound, null, null), "unbound program authority");
		assertRejects(() -> planner.rewriteFunctionDynamic(unboundFunction, new LocalVariableMap()),
			"unbound dynamic authority");
		Assert.assertEquals("unbound authority rejection mutated program/global state", unboundBefore,
			snapshot(unbound, null));
		Assert.assertSame(owner.receipt().analysis(), owner.program().requirePlacementAnalysisAuthority());
		Assert.assertEquals(owner.receipt().analysisFingerprintBefore(),
			owner.receipt().analysisFingerprintAfter());
		Assert.assertEquals(0, owner.receipt().counters().repairCount());
		Assert.assertEquals(0, owner.receipt().counters().fallbackCount());
		Assert.assertEquals(0, owner.receipt().counters().reenumerationCount());
	}

	private static void assertAppliedPlansAreExactReceipts(DpInvocationReceipt receipt) {
		Assert.assertSame(receipt.memo(), receipt.exactSelection().memo());
		List<FedPlan> roots = receipt.exactSelection().selectedRootPlans();
		Assert.assertFalse("exact adapter selection must publish root receipts", roots.isEmpty());
		for(int i = 0; i < roots.size(); i++)
			Assert.assertSame("applied plan must be the adapter-selected receipt", roots.get(i),
				receipt.appliedPlans().get(i).plan());
	}

	private static BoundaryState snapshot(DMLProgram program, PlacementAnalysis analysis) {
		return new BoundaryState(PlacementGraphFingerprint.capture(program),
			analysis == null ? null : analysis.analysisFingerprint(),
			FederatedPlannerUtils.snapshotFedState(), FederatedPlannerUtils.snapshotFedAnchorKeys());
	}

	private static ProgramInvocation invokeProgram(String fixture) {
		DMLProgram program = compile(fixture);
		String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
		AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
		try {
			ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			new DMLTranslator(program).constructLops(program, receipt::set);
		}
		catch(Exception e) { throw new AssertionError("Unable to invoke program fixture " + fixture, e); }
		finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
		Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
		return new ProgramInvocation(program, (DpInvocationReceipt) receipt.get());
	}

	private static DMLProgram compile(String fixture) {
		try { return ProductionShadowFixtureFactory.compile(fixture); }
		catch(Exception e) { throw new AssertionError("Unable to compile authority fixture " + fixture, e); }
	}

	private static void assertRejects(Runnable action, String label) {
		try { action.run(); Assert.fail("accepted " + label); }
		catch(IllegalArgumentException expected) { Assert.assertNotNull(expected.getMessage()); }
	}

	private static String typeBody(String source, String typeName) {
		Matcher matcher = Pattern.compile("\\b(?:class|record)\\s+" + Pattern.quote(typeName) + "\\b")
			.matcher(source);
		return matcher.find() ? bracedBody(source, source.indexOf('{', matcher.end())) : "";
	}

	private static String recordHeader(String source, String recordName) {
		Matcher matcher = Pattern.compile("\\brecord\\s+" + Pattern.quote(recordName) + "\\s*\\(([^)]*)\\)",
			Pattern.DOTALL).matcher(source);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String compactRecordConstructorBody(String source, String recordName) {
		String body = typeBody(source, recordName);
		Matcher constructor = Pattern.compile("\\bpublic\\s+" + Pattern.quote(recordName) + "\\s*\\{")
			.matcher(body);
		return constructor.find() ? bracedBody(body, body.indexOf('{', constructor.start())) : "";
	}

	private static String methodBody(String source, String returnType, String methodName,
		String... parameterTypes) {
		StringBuilder signature = new StringBuilder("\\b").append(Pattern.quote(returnType))
			.append("\\s+").append(Pattern.quote(methodName)).append("\\s*\\(");
		for(int i = 0; i < parameterTypes.length; i++) {
			if(i > 0) signature.append("\\s*,\\s*");
			signature.append("(?:[\\w.]+\\.)?").append(Pattern.quote(parameterTypes[i])).append("\\s+\\w+");
		}
		signature.append("\\s*\\)");
		Matcher matcher = Pattern.compile(signature.toString(), Pattern.DOTALL).matcher(source);
		return matcher.find() ? bracedBody(source, source.indexOf('{', matcher.end())) : "";
	}

	private static String executableSource(String source) {
		char[] executable = source.toCharArray();
		int state = 0;
		for(int i = 0; i < executable.length; i++) {
			char current = executable[i];
			char next = i + 1 < executable.length ? executable[i + 1] : '\0';
			if(state == 0) {
				if(current == '/' && next == '/') {
					executable[i] = executable[++i] = ' ';
					state = 1;
				}
				else if(current == '/' && next == '*') {
					executable[i] = executable[++i] = ' ';
					state = 2;
				}
				else if(current == '\"' && next == '\"'
					&& i + 2 < executable.length && executable[i + 2] == '\"') {
					executable[i] = executable[++i] = ' ';
					executable[++i] = ' ';
					state = 5;
				}
				else if(current == '\"' || current == '\'') {
					executable[i] = ' ';
					state = current == '\"' ? 3 : 4;
				}
			}
			else if(state == 1) {
				if(current == '\n' || current == '\r') state = 0;
				else executable[i] = ' ';
			}
			else if(state == 2) {
				if(current == '*' && next == '/') {
					executable[i] = executable[++i] = ' ';
					state = 0;
				}
				else if(current != '\n' && current != '\r') executable[i] = ' ';
			}
			else if(state == 3 || state == 4) {
				executable[i] = current == '\n' || current == '\r' ? current : ' ';
				if(current == '\\' && i + 1 < executable.length) executable[++i] = ' ';
				else if((state == 3 && current == '\"') || (state == 4 && current == '\'')) state = 0;
			}
			else {
				executable[i] = current == '\n' || current == '\r' ? current : ' ';
				if(current == '\\' && i + 1 < executable.length) {
					i++;
					executable[i] = executable[i] == '\n' || executable[i] == '\r' ? executable[i] : ' ';
				}
				else if(current == '\"' && next == '\"'
					&& i + 2 < executable.length && executable[i + 2] == '\"') {
					executable[i] = executable[++i] = ' ';
					executable[++i] = ' ';
					state = 0;
				}
			}
		}
		return new String(executable);
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

	private static String bracedBody(String source, int open) {
		if(open < 0) return "";
		int depth = 0;
		for(int i = open; i < source.length(); i++) {
			char current = source.charAt(i);
			if(current == '{') depth++;
			else if(current == '}' && --depth == 0) return source.substring(open, i + 1);
		}
		return "";
	}

	private record BoundaryState(String programFingerprint, String analysisFingerprint,
		Map<String, FederatedPlannerUtils.FedVarSnapshot> fedState, Map<String, String> anchorKeys) { }
	private record ProgramInvocation(DMLProgram program, DpInvocationReceipt receipt) { }
}
