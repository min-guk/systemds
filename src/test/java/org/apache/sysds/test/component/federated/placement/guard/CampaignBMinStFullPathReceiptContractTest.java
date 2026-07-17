/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.LegacyMinstOfflineSelectedCapture;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED for retaining a real MinST full-path invocation through the test-only bridge. */
public class CampaignBMinStFullPathReceiptContractTest {
	private static final String CAPTURE = "MINST_FULLPATH_EXPLICIT_RECEIPT_CAPTURE_MISSING";
	private static final String ADAPTER = "MINST_FULLPATH_ADAPTER_RETENTION_MISSING";
	private static final String EXTRACTOR = "MINST_FULLPATH_EXTRACTOR_RETENTION_MISSING";
	private static final long NO_RANDOM_SEED = -1L;

	@Test
	public void captureRetainsTheExactInvocationReceipt() {
		try {
			Capture first = capture("B-16");
			assertCaptureIdentity(first);

			Capture second = capture("B-16");
			Assert.assertNotSame("MINST_FULLPATH_CAPTURE_RETAINED_ALIAS", first.retained(), second.retained());
			Assert.assertNotSame("MINST_FULLPATH_CAPTURE_INPUT_ALIAS", first.input(), second.input());
			Assert.assertNotSame("MINST_FULLPATH_CAPTURE_ANALYSIS_ALIAS", first.analysis(), second.analysis());
			assertCaptureIdentity(second);

			MinStPlacementAdapter.Selection replay = new MinStPlacementAdapter()
				.select(first.analysis(), first.input());
			assertReplay(first.selection(), replay);
			assertCaptureSourceGuards();
		}
		catch(Throwable failure) {
			throw missing(CAPTURE, failure);
		}
	}

	@Test
	public void adapterRetainsLiveMinstSelectionAndB07NoneReceipts() {
		try {
			Class<?> wrapperType = Class.forName(CampaignBFrozenCostFixtureBridge.class.getName()
				+ "$RetainedMinstReceipt");
			Assert.assertTrue("MINST_FULLPATH_ADAPTER_WRAPPER_CONTRACT",
				CampaignBFrozenCostFixtureBridge.FullPathReceipt.class.isAssignableFrom(wrapperType));

			Capture capture = capture("B-07");
			CampaignBFrozenCostFixtureBridge.FullPathReceipt wrapper = wrap(wrapperType, capture.retained());
			CampaignBFrozenCostFixtureBridge.FullPathInput input = fullInput("MINST-LIVE-B07", capture, wrapper);
			Assert.assertSame("MINST_FULLPATH_ADAPTER_INPUT_PRODUCER", capture.selection().producer(),
				input.producer());
			Assert.assertFalse("MINST_FULLPATH_ADAPTER_SYNTHETIC_CERTIFICATE",
				wrapper instanceof CampaignBFrozenCostFixtureBridge.ExistingCertificateReceipt);

			R4CostAdapterBridge.Selection adapted = R4CostAdapterBridge.select(input);
			Assert.assertSame("MINST_FULLPATH_ADAPTER_ANALYSIS_IDENTITY", capture.analysis(), adapted.analysis());
			Assert.assertSame("MINST_FULLPATH_ADAPTER_PRODUCER_IDENTITY", capture.selection().producer(),
				adapted.producer());
			Assert.assertSame("MINST_FULLPATH_ADAPTER_INPUT_RECEIPT_IDENTITY", capture.input(),
				adapted.selectedReceipt());
			Assert.assertEquals("MINST_FULLPATH_ADAPTER_OBJECTIVE", capture.selection().cutObjectiveBits(),
				adapted.objectiveCostBits());
			Assert.assertEquals("MINST_FULLPATH_ADAPTER_FINGERPRINT", capture.analysis().analysisFingerprint(),
				adapted.analysisFingerprint());
			Assert.assertTrue("MINST_FULLPATH_ADAPTER_SELECTED_RECEIPTS",
				sameObjects(capture.selection().selectedReceipts(), adapted.orderedReceipts()));
			Assert.assertTrue("MINST_FULLPATH_ADAPTER_OBLIGATION_RECEIPTS",
				sameObjects(capture.selection().selectedObligations(), adapted.obligationReceipts()));

			List<MinStPlacementAdapter.SelectedReceipt> noneReceipts = capture.selection().selectedReceipts().stream()
				.filter(receipt -> receipt.execType() == null && receipt.output() == FederatedOutput.NONE).toList();
			Assert.assertEquals("MINST_FULLPATH_ADAPTER_B07_NONE_COUNT", 4, noneReceipts.size());
			for(MinStPlacementAdapter.SelectedReceipt receipt : noneReceipts) {
				NeutralPlacementGraph.Node node = capture.analysis().graph().node(receipt.planningKey()).orElseThrow();
				Assert.assertEquals("MINST_FULLPATH_ADAPTER_B07_NONE_KIND",
					NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED, node.kind());
				Assert.assertFalse("MINST_FULLPATH_ADAPTER_B07_NONE_EMITTED", node.emittedWork());
			}
			assertAdapterSourceGuards();
		}
		catch(Throwable failure) {
			throw missing(ADAPTER, failure);
		}
	}

	@Test
	public void extractorUsesLiveRetainedFactsNotExpectedValues() {
		try {
			Method retainedFields = R4CostTypedExtractor.class.getDeclaredMethod("retainedMinstFields",
				CampaignBFrozenCostFixtureBridge.FullPathInput.class, R4CostAdapterBridge.Selection.class);
			retainedFields.setAccessible(true);
			Assert.assertTrue("MINST_FULLPATH_EXTRACTOR_FIELDS_STATIC",
				Modifier.isStatic(retainedFields.getModifiers()));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_FIELDS_RETURN", Map.class,
				retainedFields.getReturnType());

			Class<?> wrapperType = Class.forName(CampaignBFrozenCostFixtureBridge.class.getName()
				+ "$RetainedMinstReceipt");
			Method structuralFacts = R4CostTypedExtractor.class.getDeclaredMethod("retainedMinstSemanticFacts",
				PlacementAnalysis.class, MinStPlacementAdapter.Selection.class);
			structuralFacts.setAccessible(true);
			Assert.assertTrue("MINST_FULLPATH_EXTRACTOR_FACTS_STATIC",
				Modifier.isStatic(structuralFacts.getModifiers()));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_FACTS_RETURN", String.class,
				structuralFacts.getReturnType());

			Capture capture = capture("B-09");
			CampaignBFrozenCostFixtureBridge.FullPathReceipt wrapper = wrap(wrapperType, capture.retained());
			CampaignBFrozenCostFixtureBridge.FullPathInput input = fullInput("MINST-LIVE-B09-A", capture,
				wrapper, "POISON_ALIAS_A");
			R4CostAdapterBridge.Selection adapted = R4CostAdapterBridge.select(input);

			@SuppressWarnings("unchecked")
			Map<String, String> live = (Map<String, String>) retainedFields.invoke(null, input, adapted);
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_EVIDENCE", "ACTUAL_RETAINED", live.get("evidence"));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_SEED", Long.toString(NO_RANDOM_SEED), live.get("seed"));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_FIXTURE", input.fixtureId(), live.get("fixture"));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_STATES", selectedStates(capture.selection()).toString(),
				live.get("selectedStates"));
			String facts = live.get("semanticFacts");
			Assert.assertNotNull("MINST_FULLPATH_EXTRACTOR_FACTS", facts);
			Assert.assertTrue("MINST_FULLPATH_EXTRACTOR_RECOMPILE_FACT", facts.contains("recompileCpFout=UNSUPPORTED"));
			Assert.assertFalse("MINST_FULLPATH_EXTRACTOR_FIXTURE_CLASSIFIER", facts.contains("B-09"));
			Assert.assertFalse("MINST_FULLPATH_EXTRACTOR_ROW_CLASSIFIER", facts.contains("C2-X-11"));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_STRUCTURAL_HELPER", facts,
				invoke(structuralFacts, null, capture.analysis(), capture.selection()));

			CampaignBFrozenCostFixtureBridge.FullPathInput relabeled = fullInput("MINST-LIVE-B09-B",
				capture, wrapper, "POISON_ALIAS_B");
			R4CostAdapterBridge.Selection relabeledSelection = R4CostAdapterBridge.select(relabeled);
			@SuppressWarnings("unchecked")
			Map<String, String> relabeledLive = (Map<String, String>) retainedFields.invoke(null,
				relabeled, relabeledSelection);
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_FIXTURE_ALIAS_INDEPENDENCE", facts,
				relabeledLive.get("semanticFacts"));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_ROLE_ALIAS_INDEPENDENCE", live.get("selectedStates"),
				relabeledLive.get("selectedStates"));

			Capture loopCapture = capture("B-05");
			CampaignBFrozenCostFixtureBridge.FullPathReceipt loopWrapper = wrap(wrapperType,
				loopCapture.retained());
			CampaignBFrozenCostFixtureBridge.FullPathInput loopInput = fullInput("MINST-LIVE-LOOP",
				loopCapture, loopWrapper, "POISON_ALIAS_LOOP");
			R4CostAdapterBridge.Selection loopSelection = R4CostAdapterBridge.select(loopInput);
			@SuppressWarnings("unchecked")
			Map<String, String> loopLive = (Map<String, String>) retainedFields.invoke(null,
				loopInput, loopSelection);
			String loopFacts = loopLive.get("semanticFacts");
			Assert.assertNotNull("MINST_FULLPATH_EXTRACTOR_LOOP_FACTS", loopFacts);
			Assert.assertTrue("MINST_FULLPATH_EXTRACTOR_LOOP_CONTROL", loopFacts.contains("LOOP"));
			Assert.assertFalse("MINST_FULLPATH_EXTRACTOR_LOOP_NOT_RECOMPILE",
				loopFacts.contains("recompileCpFout"));

			CampaignBLiteralAuthority.Expected poisonA = expected("POISON_EXPECTED_FIXTURE_A",
				"POISON_A", "POISON-ROW-A");
			CampaignBLiteralAuthority.Expected poisonB = expected("POISON_EXPECTED_FIXTURE_B",
				"POISON_B", "POISON-ROW-B");
			CampaignBLiteralAuthority.TypedPlan actualA = R4CostTypedExtractor.extract(poisonA, adapted);
			CampaignBLiteralAuthority.TypedPlan actualB = R4CostTypedExtractor.extract(poisonB,
				relabeledSelection);
			Map<String, String> actualFieldsA = fields(actualA, "POISON-ROW-A");
			Map<String, String> actualFieldsB = fields(actualB, "POISON-ROW-B");
			for(String invariant : List.of("evidence", "seed", "selectedStates", "semanticFacts"))
				Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_EXPECTED_VALUE_LEAK|" + invariant,
					actualFieldsA.get(invariant), actualFieldsB.get(invariant));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_EXPECTED_FIXTURE_A", input.fixtureId(),
				actualFieldsA.get("fixture"));
			Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_EXPECTED_FIXTURE_B", relabeled.fixtureId(),
				actualFieldsB.get("fixture"));
			for(Map.Entry<String, String> field : live.entrySet())
				Assert.assertEquals("MINST_FULLPATH_EXTRACTOR_LIVE_FIELD|" + field.getKey(), field.getValue(),
					actualFieldsA.get(field.getKey()));
			assertExtractorSourceGuards();
		}
		catch(Throwable failure) {
			throw missing(EXTRACTOR, failure);
		}
	}

	private static Capture capture(String fixture) throws Exception {
		Class<?> retainedType = nested(LegacyMinstOfflineSelectedCapture.class, "RetainedFullPath");
		Method method = LegacyMinstOfflineSelectedCapture.class.getDeclaredMethod("captureFullPath",
			DMLProgram.class, PlacementAnalysis.class, long.class);
		Assert.assertTrue("MINST_FULLPATH_CAPTURE_STATIC", Modifier.isStatic(method.getModifiers()));
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_RETURN", retainedType, method.getReturnType());
		method.setAccessible(true);

		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		String fingerprint = analysis.analysisFingerprint();
		Object retained = invoke(method, null, program, analysis, NO_RANDOM_SEED);
		Assert.assertSame("MINST_FULLPATH_CAPTURE_ANALYSIS", analysis, accessor(retained, "analysis"));
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_SEED", NO_RANDOM_SEED, accessor(retained, "seed"));
		MinStPlacementInput input = (MinStPlacementInput) accessor(retained, "input");
		MinStPlacementAdapter.Selection selection = (MinStPlacementAdapter.Selection) accessor(retained, "selection");
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_ANALYSIS_MUTATED", fingerprint, analysis.analysisFingerprint());
		return new Capture(program, analysis, retained, input, selection);
	}

	private static void assertCaptureIdentity(Capture capture) throws Exception {
		Assert.assertSame("MINST_FULLPATH_CAPTURE_INPUT_ANALYSIS", capture.analysis(), capture.input().analysis());
		Assert.assertSame("MINST_FULLPATH_CAPTURE_SELECTION_ANALYSIS", capture.analysis(),
			capture.selection().analysis());
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_CARDINALITY", capture.analysis().occurrences().size(),
			capture.selection().selectedReceipts().size());
		Set<?> expectedKeys = new HashSet<>(capture.analysis().occurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList());
		Set<?> selectedKeys = new HashSet<>(capture.selection().selectedReceipts().stream()
			.map(MinStPlacementAdapter.SelectedReceipt::planningKey).toList());
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_BIJECTION", expectedKeys, selectedKeys);
		for(MinStPlacementAdapter.SelectedReceipt receipt : capture.selection().selectedReceipts()) {
			Assert.assertSame("MINST_FULLPATH_CAPTURE_PLANNING_HOP", capture.analysis()
				.hop(receipt.planningKey()).orElseThrow(), receipt.planningHop());
			Assert.assertSame("MINST_FULLPATH_CAPTURE_EXECUTABLE_HOP", receipt.planningHop(),
				receipt.executableHop());
		}
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_STATES", selectedStates(capture.selection()),
			accessor(capture.retained(), "selectedStates"));
		Assert.assertTrue("MINST_FULLPATH_CAPTURE_FACTS_TYPE",
			accessor(capture.retained(), "semanticFacts") instanceof String);
	}

	private static CampaignBFrozenCostFixtureBridge.FullPathReceipt wrap(Class<?> wrapperType,
		Object retained) throws Exception {
		Constructor<?> constructor = List.of(wrapperType.getDeclaredConstructors()).stream()
			.filter(candidate -> candidate.getParameterCount() == 1
				&& candidate.getParameterTypes()[0].isInstance(retained)).findFirst().orElseThrow();
		constructor.setAccessible(true);
		Object wrapper = constructor.newInstance(retained);
		Assert.assertSame("MINST_FULLPATH_ADAPTER_WRAPPER_IDENTITY", retained, accessor(wrapper, "retained"));
		return (CampaignBFrozenCostFixtureBridge.FullPathReceipt) wrapper;
	}

	private static CampaignBFrozenCostFixtureBridge.FullPathInput fullInput(String fixtureId,
		Capture capture, CampaignBFrozenCostFixtureBridge.FullPathReceipt wrapper) {
		return fullInput(fixtureId, capture, wrapper, "LIVE_ALIAS");
	}

	private static CampaignBFrozenCostFixtureBridge.FullPathInput fullInput(String fixtureId,
		Capture capture, CampaignBFrozenCostFixtureBridge.FullPathReceipt wrapper, String aliasPrefix) {
		int[] index = {0};
		List<CampaignBFrozenCostFixtureBridge.RoleAlias> aliases = capture.analysis().occurrences().stream()
			.map(occurrence -> new CampaignBFrozenCostFixtureBridge.RoleAlias(
				aliasPrefix + '_' + index[0]++, aliasPrefix, occurrence.key(), occurrence.hop().getHopID()))
			.toList();
		return new CampaignBFrozenCostFixtureBridge.FullPathInput(R4CostAdapterBridge.Planner.MIN_ST,
			fixtureId, capture.analysis(), aliases, wrapper, capture.analysis().analysisFingerprint());
	}

	private static CampaignBLiteralAuthority.Expected expected(String fixture, String poison,
		String rowDigest) {
		Map<String, String> fields = new LinkedHashMap<>();
		for(String field : List.of("evidence", "seed", "fixture", "selectedStates", "semanticFacts"))
			fields.put(field, poison + '_' + field);
		CampaignBLiteralAuthority.Row row = new CampaignBLiteralAuthority.Row(fixture, "MIN_ST",
			"MINST_FULL_OFFLINE_SELECTION", Map.copyOf(fields), rowDigest);
		return new CampaignBLiteralAuthority.Expected("MIN_ST", fixture, List.of(row), Map.of(),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
			List.of(), List.of(), Map.of(), poison);
	}

	private static Map<String, String> fields(CampaignBLiteralAuthority.TypedPlan actual,
		String rowDigest) {
		Map<String, String> fields = new LinkedHashMap<>();
		for(String field : List.of("evidence", "seed", "fixture", "selectedStates", "semanticFacts"))
			fields.put(field, actual.facts().get(rowDigest + '.' + field));
		return Map.copyOf(fields);
	}

	private static List<String> selectedStates(MinStPlacementAdapter.Selection selection) {
		return selection.selectedReceipts().stream().map(receipt -> receipt.planningKey().normalizedSignature()
			+ '=' + receipt.execType() + '/' + receipt.output()).sorted().toList();
	}

	private static Class<?> nested(Class<?> owner, String simpleName) throws ClassNotFoundException {
		return Class.forName(owner.getName() + '$' + simpleName);
	}

	private static Object accessor(Object owner, String name) throws Exception {
		Method method = owner.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return invoke(method, owner);
	}

	private static Object invoke(Method method, Object owner, Object... arguments) throws Exception {
		try {
			return method.invoke(owner, arguments);
		}
		catch(InvocationTargetException failure) {
			Throwable cause = failure.getCause();
			if(cause instanceof Exception exception)
				throw exception;
			if(cause instanceof Error error)
				throw error;
			throw failure;
		}
	}

	private static boolean sameObjects(List<?> expected, List<?> actual) {
		if(expected.size() != actual.size())
			return false;
		for(int index = 0; index < expected.size(); index++)
			if(expected.get(index) != actual.get(index))
				return false;
		return true;
	}

	private static void assertReplay(MinStPlacementAdapter.Selection retained,
		MinStPlacementAdapter.Selection replay) {
		Assert.assertSame("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_ANALYSIS", retained.analysis(),
			replay.analysis());
		Assert.assertSame("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_STALE", retained.producer(),
			replay.producer());
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_FINGERPRINT",
			retained.analysisFingerprint(), replay.analysisFingerprint());
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_OBJECTIVE", retained.cutObjectiveBits(),
			replay.cutObjectiveBits());
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_PARTITION",
			retained.sourcePartitionNodeIds(), replay.sourcePartitionNodeIds());
		Assert.assertTrue("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_OBLIGATIONS",
			sameObjects(retained.selectedObligations(), replay.selectedObligations()));
		Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_CARDINALITY",
			retained.selectedReceipts().size(), replay.selectedReceipts().size());
		for(int index = 0; index < retained.selectedReceipts().size(); index++) {
			MinStPlacementAdapter.SelectedReceipt expected = retained.selectedReceipts().get(index);
			MinStPlacementAdapter.SelectedReceipt actual = replay.selectedReceipts().get(index);
			Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_KEY", expected.planningKey(),
				actual.planningKey());
			Assert.assertSame("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_PLANNING_HOP", expected.planningHop(),
				actual.planningHop());
			Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_PLANNING_ID", expected.planningHopId(),
				actual.planningHopId());
			Assert.assertSame("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_EXECUTABLE_HOP", expected.executableHop(),
				actual.executableHop());
			Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_EXECUTABLE_ID", expected.executableHopId(),
				actual.executableHopId());
			Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_EXEC", expected.execType(), actual.execType());
			Assert.assertEquals("MINST_FULLPATH_CAPTURE_FIRST_RECEIPT_OUTPUT", expected.output(), actual.output());
		}
	}

	private static void assertCaptureSourceGuards() throws Exception {
		String source = source("src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/"
			+ "LegacyMinstOfflineSelectedCapture.java");
		String body = methodBody(source, "RetainedFullPath captureFullPath");
		Assert.assertFalse("MINST_FULLPATH_CAPTURE_SECOND_ANALYSIS", body.contains("NeutralPlacementGraphBuilder"));
		Assert.assertFalse("MINST_FULLPATH_CAPTURE_THREE_ARG_REWRITE",
			body.contains("rewriteProgram(program, null, null)"));
		Assert.assertFalse("MINST_FULLPATH_CAPTURE_ROW_CLASSIFIER", source.contains("semanticFacts(String rowId"));
	}

	private static void assertAdapterSourceGuards() throws Exception {
		String bridge = source("src/test/java/org/apache/sysds/test/component/federated/placement/guard/"
			+ "CampaignBFrozenCostFixtureBridge.java");
		String full = methodBody(bridge, "FullPathInput full");
		Assert.assertFalse("MINST_FULLPATH_ADAPTER_EXPECTED_ASSIGNMENTS", full.contains("expected.assignments()"));
		Assert.assertFalse("MINST_FULLPATH_ADAPTER_EXPECTED_ROWS", full.contains("expected.rows()"));
		String adapter = source("src/test/java/org/apache/sysds/test/component/federated/placement/guard/"
			+ "R4CostAdapterBridge.java");
		Assert.assertTrue("MINST_FULLPATH_ADAPTER_RETAINED_BRANCH", adapter.contains("RetainedMinstReceipt"));
	}

	private static void assertExtractorSourceGuards() throws Exception {
		String source = source("src/test/java/org/apache/sysds/test/component/federated/placement/guard/"
			+ "R4CostTypedExtractor.java");
		Assert.assertTrue("MINST_FULLPATH_EXTRACTOR_LIVE_HELPER", source.contains("retainedMinstFields"));
		String classifier = methodBody(source, "String retainedMinstSemanticFacts").toLowerCase();
		for(String forbidden : List.of("fixture", "rowid", "workload", "gethopid", "shape", "worker",
			"system.getenv", "manifest", "expected", "golden"))
			Assert.assertFalse("MINST_FULLPATH_EXTRACTOR_FORBIDDEN_CLASSIFIER_INPUT|" + forbidden,
				classifier.contains(forbidden));
		Assert.assertFalse("MINST_FULLPATH_EXTRACTOR_ANALYSIS_FINGERPRINT_FACT",
			source.contains("case \"semanticFacts\"->cert.analysisFingerprint()"));
	}

	private static String source(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	private static String methodBody(String source, String marker) {
		int markerIndex = source.indexOf(marker);
		if(markerIndex < 0)
			throw new AssertionError("MISSING_METHOD_SOURCE|" + marker);
		int start = source.indexOf('{', markerIndex);
		int depth = 0;
		for(int index = start; index < source.length(); index++) {
			char current = source.charAt(index);
			if(current == '{')
				depth++;
			else if(current == '}' && --depth == 0)
				return source.substring(start, index + 1);
		}
		throw new AssertionError("UNTERMINATED_METHOD_SOURCE|" + marker);
	}

	private static AssertionError missing(String token, Throwable failure) {
		Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
			? failure.getCause() : failure;
		if(cause instanceof AssertionError assertion && assertion.getMessage() != null
			&& assertion.getMessage().startsWith(token))
			return assertion;
		return new AssertionError(token + '|' + cause.getClass().getSimpleName() + '|'
			+ String.valueOf(cause.getMessage()), cause);
	}

	private record Capture(DMLProgram program, PlacementAnalysis analysis, Object retained,
		MinStPlacementInput input, MinStPlacementAdapter.Selection selection) { }
}
