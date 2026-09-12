/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.hops.ipa.FederatedPlannerFactory;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class PlannerApplicationBoundaryTest {
	@After
	public void clearTiming() { PlannerPipelineTiming.clear(); }

	@Test
	public void allFourPlannersCrossCompleteCommonBoundaryAndApplyTheirSelectedPlan() throws Exception {
		for(FederatedPlanner kind : new FederatedPlanner[] {
			FederatedPlanner.COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS,
			FederatedPlanner.COMPILE_FED_HEURISTIC_SINGLE_PASS,
			FederatedPlanner.COMPILE_COST_BASED, FederatedPlanner.COMPILE_EXACT}) {
			DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
			PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
			AFederatedPlanner planner = FederatedPlannerFactory.create(kind);
			PlannerPipelineTiming.begin(System.nanoTime());
			AFederatedPlanner.PlannerInvocationReceipt receipt = planner.rewriteProgram(program, null, null, analysis);
			PlannerPipelineTiming.Timing timing = PlannerPipelineTiming.finish(System.nanoTime());
			PlannerPipelineTiming.clear();
			Assert.assertNotNull(kind.name(), timing);
			Assert.assertEquals(timing.totalNanos(), timing.planningNanos() + timing.diagnosticsNanos()
				+ timing.conversionNanos() + timing.applicationNanos() + timing.finalizationNanos());
			NormalizedPlannerResult normalized = (NormalizedPlannerResult) receipt.getClass()
				.getMethod("normalizedResult").invoke(receipt);
			PlacementEmissionTransaction.PlacementEmissionReceipt emission =
				(PlacementEmissionTransaction.PlacementEmissionReceipt) receipt.getClass()
					.getMethod("emissionReceipt").invoke(receipt);
			Assert.assertSame(analysis, normalized.analysis());
			Assert.assertEquals(PlacementEmissionTransaction.canonicalPlanHash(normalized), emission.planHash());
			Assert.assertTrue(emission.applied());
			Assert.assertFalse(emission.noOp());
			Assert.assertSame("immutable common handoff must not copy", normalized,
				PlacementPlannerAdapter.normalize(analysis, normalized));
		}
	}

	@Test
	public void immutableHandoffRejectsForeignAnalysis() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		NormalizedPlannerResult selected = new FederatedPlannerFedAllMaxFedFoutSinglePass().select(analysis);
		NormalizedPlannerResult normalized = PlacementPlannerAdapter.normalize(analysis, selected);
		PlacementAnalysis foreign = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(
			ProductionShadowFixtureFactory.compile("B-01"));
		Assert.assertThrows(IllegalStateException.class,
			() -> PlacementPlannerAdapter.normalize(foreign, normalized));
	}

	@Test
	public void failedDiagnosticsCannotApplyOrProduceACompleteTiming() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-01");
		PlacementAnalysis analysis = CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
		NormalizedPlannerResult selected = new FederatedPlannerFedAllMaxFedFoutSinglePass().select(analysis);
		var before = analysis.occurrences().stream().map(o -> o.hop().getForcedExecType()).toList();
		PlannerPipelineTiming.begin(System.nanoTime());
		Assert.assertThrows(IllegalArgumentException.class, () -> PlacementPlanApplication.complete(
			program, analysis, () -> { throw new IllegalArgumentException("injected diagnostic failure"); },
			() -> selected, result -> result,
			(result, normalized, emission) -> new AFederatedPlanner.SuppliedAnalysisReceipt(analysis)));
		Assert.assertEquals(before, analysis.occurrences().stream().map(o -> o.hop().getForcedExecType()).toList());
		Assert.assertThrows(IllegalStateException.class, () -> PlannerPipelineTiming.finish(System.nanoTime()));
	}
}
