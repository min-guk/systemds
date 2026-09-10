/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.test.component.federated.placement.selector.CampaignBSelectorFixtureBridge;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Contract coverage for the active deterministic first-feasible FedAll adapter. */
public class CampaignBFedAllFirstFeasibleAdapterContractTest {
	@Test public void nonEmittedFunctionTraceNodesStayAuditableButOutsideFedAllDecisionProjection() throws Exception {
		List<String> failures = new ArrayList<>();
		for(String id : List.of("B-07", "B-08", "B-17", "B-21")) {
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
				ProductionShadowFixtureFactory.compile(id));
			var decisionKeys = R4SharedFedAllSemanticValidator.decisionKeys(analysis.graph());
			Assert.assertTrue(id + " must preserve trace nodes", analysis.graph().nodes().stream()
				.anyMatch(n -> !n.emittedWork() && n.legalAlternatives().isEmpty()));
			Assert.assertEquals(id, analysis.graph().nodes().size(), analysis.occurrences().size());
			try {
				var handle = R4SharedFedAllAdapterBridge.open(R4SharedFedAllAdapterBridge.Planner.FED_ALL);
				var actual = R4SharedFedAllAdapterBridge.select(handle, analysis);
				R4SharedFedAllSemanticValidator.shared(analysis, actual);
				Assert.assertEquals(id, decisionKeys, actual.assignment().keySet());
				Assert.assertEquals(id, analysis.graph().nodes().size(), actual.certificate().graphNodes());
			}
			catch(Throwable failure) {
				failures.add("FEDALL_DECISION_PROJECTION_THROW|" + id + "|" + failure.getClass().getName()
					+ "|" + failure.getMessage());
			}
		}
		Assert.assertEquals("all four actual function fixtures must select", List.of(), failures);
	}
	@Test public void everyFixtureReturnsOneCompleteLegalFirstFeasibleAssignment() throws Exception {
		List<String> failures = new ArrayList<>();
		for(var fixture : CampaignBSelectorFixtureBridge.all()) {
			PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
			try {
				var handle = R4SharedFedAllAdapterBridge.open(R4SharedFedAllAdapterBridge.Planner.FED_ALL);
				var actual = R4SharedFedAllAdapterBridge.select(handle, analysis);
				R4SharedFedAllSemanticValidator.shared(analysis, actual);
				Assert.assertEquals(fixture.id(), "POLICY_FEASIBLE", actual.certificate().termination());
				Assert.assertEquals(fixture.id(), 1, actual.certificate().explored());
				Assert.assertEquals(fixture.id(), actual.certificate().universe(),
					actual.certificate().explored() + actual.certificate().pruned());
			}
			catch(AssertionError failure) { recordMissing(failures, fixture.id(), failure); }
		}
		Assert.assertEquals("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING", List.of(), failures);
	}

	@Test public void fedAllNormalReverseRepeatAndStartBarrierConcurrentProofsAreIdentical() throws Exception {
		List<String> missing=new ArrayList<>();
		for(var fixture:CampaignBSelectorFixtureBridge.all()) {
			PlacementAnalysis normal=CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production(),ProjectionOrder.NORMAL);
			PlacementAnalysis reverse=CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production(),ProjectionOrder.REVERSED);
			try {
				var handle=R4SharedFedAllAdapterBridge.open(R4SharedFedAllAdapterBridge.Planner.FED_ALL);
				var baseline=R4SharedFedAllAdapterBridge.select(handle,normal);
				R4SharedFedAllSemanticValidator.stable(baseline,R4SharedFedAllAdapterBridge.select(handle,reverse),"R4_ORDER_STABILITY");
				R4SharedFedAllSemanticValidator.stable(baseline,R4SharedFedAllAdapterBridge.select(handle,normal),"R4_REPEAT_STABILITY");
				CountDownLatch ready=new CountDownLatch(4),start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
				var futures=new ArrayList<java.util.concurrent.Future<R4SharedFedAllAdapterBridge.Selection>>();
				for(int i=0;i<4;i++)futures.add(pool.submit(()->{ready.countDown();start.await();return R4SharedFedAllAdapterBridge.select(handle,normal);}));
				ready.await();start.countDown();for(var f:futures)R4SharedFedAllSemanticValidator.stable(baseline,f.get(),"R4_CONCURRENCY_STABILITY");pool.shutdownNow();
			}
			catch(AssertionError e){recordMissing(missing,fixture.id(),e);}
		}
		Assert.assertEquals("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING",List.of(),missing);
	}

	private static void recordMissing(List<String> out,String id,AssertionError e){if(e.getMessage()!=null&&e.getMessage().startsWith("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING"))out.add(id+'|'+e.getMessage());else throw e;}
}
