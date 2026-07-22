/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.ipa.FederatedPlannerFactory;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED contract for the real FedAll supplied-analysis root and its immutable typed receipt. */
public class CampaignBFedAllInvocationReceiptContractTest {
	private static final String RECEIPT_TYPE = FederatedPlannerFedAll.class.getName()
		+ "$FedAllInvocationReceipt";
	private static final String COUNTERS_TYPE = FederatedPlannerFedAll.class.getName()
		+ "$InvocationCounters";

	@Test
	public void realFourArgumentRootSelectsExactlyOnceAndReturnsTheExactTypedReceipt() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		List<String> hopStateBefore = hopState(analysis);
		String fingerprintBefore = analysis.analysisFingerprint();
		TrackingFedAll planner = new TrackingFedAll();

		AFederatedPlanner.PlannerInvocationReceipt receipt =
			planner.rewriteProgram(program, null, null, analysis);

		Assert.assertEquals("FEDALL_ROOT_SELECTION_COUNT", 1, planner.selectionCount);
		Assert.assertNotNull("FEDALL_ROOT_SELECTED_RESULT", planner.selected);
		assertTypedReceipt(receipt, analysis, planner.selected);
		Assert.assertEquals("FEDALL_ROOT_ANALYSIS_FINGERPRINT_MUTATION", fingerprintBefore,
			analysis.analysisFingerprint());
		Assert.assertEquals("FEDALL_ROOT_HOP_MUTATION", hopStateBefore, hopState(analysis));
		Assert.assertThrows("FEDALL_ROOT_ASSIGNMENT_MUTABLE", UnsupportedOperationException.class,
			() -> planner.selected.assignment().clear());
		Assert.assertThrows("FEDALL_ROOT_RELOCATIONS_MUTABLE", UnsupportedOperationException.class,
			() -> planner.selected.selectedRelocations().clear());
	}

	@Test
	public void realAndCompatibilityFactoryRoutesReturnEquivalentTypedReceipts() throws Exception {
		Invocation fedAll = invokeFactory(FederatedPlanner.COMPILE_FED_ALL, "B-15");
		Invocation compatibility = invokeFactory(
			FederatedPlanner.COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS, "B-15");

		Assert.assertEquals("FEDALL_COMPAT_ASSIGNMENT", fedAll.result.assignment(),
			compatibility.result.assignment());
		Assert.assertEquals("FEDALL_COMPAT_CERTIFICATE", fedAll.result.certificate(),
			compatibility.result.certificate());
		Assert.assertEquals("FEDALL_COMPAT_ANALYSIS_FINGERPRINT", fedAll.result.analysisFingerprint(),
			compatibility.result.analysisFingerprint());
		Assert.assertEquals("FEDALL_COMPAT_PLAN_FINGERPRINT", fedAll.result.normalizedPlanFingerprint(),
			compatibility.result.normalizedPlanFingerprint());
	}

	@Test
	public void suppliedAnalysisOwnershipAndLegacyRoutesRemainFailClosed() throws Exception {
		DMLProgram owner = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(owner);
		FederatedPlannerFedAll planner = new FederatedPlannerFedAll();

		Assert.assertThrows("FEDALL_ROOT_NULL_ANALYSIS", NullPointerException.class,
			() -> planner.rewriteProgram(owner, null, null, null));
		IllegalArgumentException foreign = Assert.assertThrows("FEDALL_ROOT_FOREIGN_ANALYSIS",
			IllegalArgumentException.class, () -> planner.rewriteProgram(
				ProductionShadowFixtureFactory.compile("B-01"), null, null, analysis));
		Assert.assertTrue("FEDALL_ROOT_FOREIGN_OWNER_MESSAGE",
			foreign.getMessage().contains("foreign"));
		Assert.assertThrows("FEDALL_LEGACY_ROUTE_MUST_FAIL_CLOSED", UnsupportedOperationException.class,
			() -> planner.rewriteProgram(owner, null, null));
		Assert.assertThrows("FEDALL_DYNAMIC_ROUTE_MUST_FAIL_CLOSED", UnsupportedOperationException.class,
			() -> planner.rewriteFunctionDynamic(null, null));
	}

	private static Invocation invokeFactory(FederatedPlanner kind, String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		AFederatedPlanner planner = FederatedPlannerFactory.create(kind);
		AFederatedPlanner.PlannerInvocationReceipt receipt =
			planner.rewriteProgram(program, null, null, analysis);
		FedAllPlacementAdapter.Result result = result(receipt);
		assertTypedReceipt(receipt, analysis, result);
		return new Invocation(result);
	}

	private static void assertTypedReceipt(AFederatedPlanner.PlannerInvocationReceipt receipt,
		PlacementAnalysis analysis, FedAllPlacementAdapter.Result exactResult) throws Exception {
		Class<?> receiptType = Class.forName(RECEIPT_TYPE);
		Assert.assertEquals("FEDALL_RECEIPT_EXACT_TYPE", receiptType, receipt.getClass());
		Assert.assertTrue("FEDALL_RECEIPT_MUST_BE_RECORD", receiptType.isRecord());
		Assert.assertTrue("FEDALL_RECEIPT_MUST_BE_FINAL", Modifier.isFinal(receiptType.getModifiers()));
		Assert.assertTrue("FEDALL_RECEIPT_BOUNDARY",
			AFederatedPlanner.PlannerInvocationReceipt.class.isAssignableFrom(receiptType));
		Assert.assertSame("FEDALL_RECEIPT_ANALYSIS_IDENTITY", analysis, receipt.analysis());
		Assert.assertSame("FEDALL_RECEIPT_RESULT_IDENTITY", exactResult, result(receipt));
		Assert.assertSame("FEDALL_RESULT_ANALYSIS_IDENTITY", analysis, exactResult.analysis());
		Assert.assertEquals("FEDALL_RECEIPT_FINGERPRINT_BEFORE", analysis.analysisFingerprint(),
			invoke(receipt, "analysisFingerprintBefore"));
		Assert.assertEquals("FEDALL_RECEIPT_FINGERPRINT_AFTER", analysis.analysisFingerprint(),
			invoke(receipt, "analysisFingerprintAfter"));
		Assert.assertNotNull("FEDALL_RECEIPT_CERTIFICATE", exactResult.certificate());
		Assert.assertFalse("FEDALL_RECEIPT_PLAN_FINGERPRINT",
			exactResult.normalizedPlanFingerprint().isBlank());
		assertSingleCanonicalEmission(receipt, analysis);

		Object counters = invoke(receipt, "counters");
		Class<?> countersType = Class.forName(COUNTERS_TYPE);
		Assert.assertEquals("FEDALL_COUNTERS_EXACT_TYPE", countersType, counters.getClass());
		Assert.assertTrue("FEDALL_COUNTERS_MUST_BE_RECORD", countersType.isRecord());
		Assert.assertTrue("FEDALL_COUNTERS_MUST_BE_FINAL", Modifier.isFinal(countersType.getModifiers()));
		assertCounter(counters, "selectionCount", 1);
		assertCounter(counters, "internalAnalysisBuildCount", 0);
		assertCounter(counters, "legacyRouteCount", 0);
		assertCounter(counters, "repairCount", 0);
		assertCounter(counters, "fallbackCount", 0);
		assertCounter(counters, "mutationCount", 0);
		assertCounter(counters, "applicationCount", 1);
		assertCounter(counters, "doubleApplicationCount", 0);
	}

	private static void assertSingleCanonicalEmission(Object receipt, PlacementAnalysis analysis) throws Exception {
		Object normalized = invoke(receipt, "normalizedResult");
		Assert.assertTrue("FEDALL_NORMALIZED_RESULT_TYPE", normalized instanceof NormalizedPlannerResult);
		Assert.assertSame("FEDALL_NORMALIZED_ANALYSIS_IDENTITY", analysis,
			((NormalizedPlannerResult) normalized).analysis());
		String canonical = PlacementEmissionTransaction.canonicalPlanHash((NormalizedPlannerResult) normalized);
		Assert.assertEquals("FEDALL_PUBLIC_CANONICAL_HASH_AUTHORITY", canonical,
			((NormalizedPlannerResult) normalized).normalizedPlanFingerprint());
		Object emission = invoke(receipt, "emissionReceipt");
		Assert.assertEquals("FEDALL_EXACTLY_ONE_EMISSION_HASH", canonical, invoke(emission, "planHash"));
		Assert.assertEquals("FEDALL_EMISSION_APPLIED", true, invoke(emission, "applied"));
		Assert.assertEquals("FEDALL_EMISSION_NOT_NOOP", false, invoke(emission, "noOp"));
	}

	private static FedAllPlacementAdapter.Result result(Object receipt) throws Exception {
		Method accessor = receipt.getClass().getMethod("result");
		Assert.assertEquals("FEDALL_RECEIPT_RESULT_TYPE", FedAllPlacementAdapter.Result.class,
			accessor.getReturnType());
		return (FedAllPlacementAdapter.Result) accessor.invoke(receipt);
	}

	private static Object invoke(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static void assertCounter(Object counters, String accessor, int expected) throws Exception {
		Method method = counters.getClass().getMethod(accessor);
		Assert.assertEquals("FEDALL_COUNTER_TYPE_" + accessor, int.class, method.getReturnType());
		Assert.assertEquals("FEDALL_COUNTER_" + accessor, expected, method.invoke(counters));
	}

	private static List<String> hopState(PlacementAnalysis analysis) {
		return analysis.occurrences().stream().map(occurrence -> occurrence.key().normalizedSignature()
			+ '|' + occurrence.hop().getForcedExecType() + '|' + occurrence.hop().getFederatedOutput()
			+ '|' + occurrence.hop().isFederatedOutputDerived()).sorted().toList();
	}

	private static final class TrackingFedAll extends FederatedPlannerFedAll {
		private int selectionCount;
		private FedAllPlacementAdapter.Result selected;

		@Override
		public FedAllPlacementAdapter.Result select(PlacementAnalysis analysis) {
			selectionCount++;
			selected = super.select(analysis);
			return selected;
		}
	}

	private record Invocation(FedAllPlacementAdapter.Result result) { }
}
