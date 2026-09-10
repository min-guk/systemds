/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
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
	private static final String RECEIPT_TYPE = FederatedPlannerFedAllMaxFedFoutSinglePass.class.getName()
		+ "$FedAllInvocationReceipt";
	private static final String COUNTERS_TYPE = FederatedPlannerFedAllMaxFedFoutSinglePass.class.getName()
		+ "$InvocationCounters";

	@Test
	public void realFourArgumentRootSelectsExactlyOnceAndReturnsTheExactTypedReceipt() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		String fingerprintBefore = analysis.analysisFingerprint();
		FederatedPlannerFedAllMaxFedFoutSinglePass planner =
			new FederatedPlannerFedAllMaxFedFoutSinglePass();

		AFederatedPlanner.PlannerInvocationReceipt receipt =
			planner.rewriteProgram(program, null, null, analysis);

		FedAllPlacementAdapter.Result selected = result(receipt);
		assertTypedReceipt(receipt, analysis, selected);
		Assert.assertEquals("FEDALL_ROOT_ANALYSIS_FINGERPRINT_MUTATION", fingerprintBefore,
			analysis.analysisFingerprint());
		assertExactAppliedEmission(receipt, analysis);
		Assert.assertThrows("FEDALL_ROOT_ASSIGNMENT_MUTABLE", UnsupportedOperationException.class,
			() -> selected.assignment().clear());
		Assert.assertThrows("FEDALL_ROOT_RELOCATIONS_MUTABLE", UnsupportedOperationException.class,
			() -> selected.selectedRelocations().clear());
	}

	@Test
	public void factoryRouteUsesFirstFeasibleSelectionAndCompleteEmission() throws Exception {
		Invocation firstFeasible = invokeFactory(
			FederatedPlanner.COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS, "B-15");

		Assert.assertEquals("FEDALL_FIRST_FEASIBLE_TERMINATION", "POLICY_FEASIBLE",
			firstFeasible.result.certificate().terminationReason());
		Assert.assertEquals("FEDALL_FIRST_FEASIBLE_DECISION_COVERAGE",
			firstFeasible.result.analysis().graph().decisionNodes().size(),
			firstFeasible.result.assignment().size());
	}

	@Test
	public void directSelectionIsMutationFreeAndReturnsImmutablePolicyState() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		String analysisBefore = analysis.analysisFingerprint();
		String hopsBefore = decisionHopFingerprint(analysis);

		FedAllPlacementAdapter.Result selected =
			new FederatedPlannerFedAllMaxFedFoutSinglePass().select(analysis);

		Assert.assertEquals("FEDALL_SELECT_MUTATED_ANALYSIS", analysisBefore,
			analysis.analysisFingerprint());
		Assert.assertEquals("FEDALL_SELECT_MUTATED_CONCRETE_HOPS", hopsBefore,
			decisionHopFingerprint(analysis));
		Assert.assertThrows("FEDALL_SELECT_ASSIGNMENT_MUTABLE", UnsupportedOperationException.class,
			() -> selected.assignment().clear());
		Assert.assertThrows("FEDALL_SELECT_RELOCATIONS_MUTABLE", UnsupportedOperationException.class,
			() -> selected.selectedRelocations().clear());
	}

	@Test
	public void suppliedAnalysisOwnershipAndLegacyRoutesRemainFailClosed() throws Exception {
		DMLProgram owner = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(owner);
		FederatedPlannerFedAllMaxFedFoutSinglePass planner = new FederatedPlannerFedAllMaxFedFoutSinglePass();

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

	private static void assertExactAppliedEmission(Object receipt, PlacementAnalysis analysis) throws Exception {
		Object normalized = invoke(receipt, "normalizedResult");
		Assert.assertTrue("FEDALL_APPLIED_NORMALIZED_RESULT_TYPE", normalized instanceof NormalizedPlannerResult);
		Map<CompiledHopKey, PlacementEmissionState> selected =
			((NormalizedPlannerResult) normalized).selectedEmissionStates();
		List<Node> decisionNodes = analysis.graph().decisionNodes();
		Assert.assertEquals("FEDALL_APPLIED_DECISION_COVERAGE", decisionNodes.size(), selected.size());
		Map<Hop, Boolean> concreteWrites = new IdentityHashMap<>();
		for(Node node : decisionNodes) {
			PlacementEmissionState emissionState = exactEmissionState(selected, node.key());
			Assert.assertNotNull("FEDALL_APPLIED_SELECTED_STATE|"
				+ node.key().normalizedSignature(), emissionState);
			Assert.assertTrue("FEDALL_APPLIED_LEGAL_STATE|" + node.key().normalizedSignature(),
				node.legalAlternatives().contains(emissionState.placementState()));
			if(!analysis.isCompiledHopOccurrence(node.key()))
				continue;
			Hop hop = analysis.hop(node.key()).orElseThrow(AssertionError::new);
			concreteWrites.put(hop, Boolean.TRUE);
			Assert.assertEquals("FEDALL_APPLIED_HOP_EXEC|" + node.key().normalizedSignature(),
				emissionState.placementState().execType(), hop.getExecType());
			Assert.assertEquals("FEDALL_APPLIED_HOP_FORCED_EXEC|" + node.key().normalizedSignature(),
				emissionState.placementState().execType(), hop.getForcedExecType());
			Assert.assertEquals("FEDALL_APPLIED_HOP_OUTPUT|" + node.key().normalizedSignature(),
				emissionState.placementState().output(), hop.getFederatedOutput());
			Assert.assertEquals("FEDALL_APPLIED_HOP_DERIVED|" + node.key().normalizedSignature(),
				emissionState.derivedFedFout(), hop.isFederatedOutputDerived());
		}
		Object emission = invoke(receipt, "emissionReceipt");
		Assert.assertEquals("FEDALL_APPLIED_HOP_MUTATION_COUNT", concreteWrites.size(),
			invoke(emission, "hopMutations"));
		Assert.assertEquals("FEDALL_APPLIED_B01_REGISTRY_WRITE_COUNT", 0,
			invoke(emission, "registryWrites"));
	}

	private static PlacementEmissionState exactEmissionState(
		Map<CompiledHopKey, PlacementEmissionState> selected, CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return entry.getValue();
		return null;
	}

	private static String decisionHopFingerprint(PlacementAnalysis analysis) {
		return analysis.graph().decisionNodes().stream().map(Node::key).sorted()
			.map(key -> {
				Hop hop = analysis.hop(key).orElseThrow(AssertionError::new);
				return key.normalizedSignature() + '|' + System.identityHashCode(hop) + '|'
					+ hop.getExecType() + '|' + hop.getForcedExecType() + '|'
					+ hop.getFederatedOutput() + '|' + hop.isFederatedOutputDerived() + '|'
					+ hop.requiresRecompile() + '|' + hop.isVisited();
			})
			.collect(java.util.stream.Collectors.joining("\n"));
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

	private record Invocation(FedAllPlacementAdapter.Result result) { }
}
