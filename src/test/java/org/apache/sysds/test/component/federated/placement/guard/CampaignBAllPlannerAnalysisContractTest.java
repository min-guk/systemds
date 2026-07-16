/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.selector.CampaignBSelectorFixtureBridge;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED for one exact immutable analysis across all planners and accepted B/S inputs. */
public class CampaignBAllPlannerAnalysisContractTest {
	@Test public void allFourAdaptersConsumeExactSuppliedAnalysisAcrossActualBAndS() throws Exception {
		List<String> missing = new ArrayList<>();
		for(String id : ProductionShadowFixtureFactory.ids()) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.build(program);
			PlacementAnalysis twin = CampaignBPlacementAnalysisFixtureBridge.build(program);
			if(id.equals("B-17")) {
				analysis = CampaignBPlacementAnalysisFixtureBridge.sameHopContextTrap(analysis);
				twin = CampaignBPlacementAnalysisFixtureBridge.sameHopContextTrap(twin);
			}
			Assert.assertNotSame(analysis, twin); Assert.assertEquals(analysis.analysisFingerprint(), twin.analysisFingerprint());
			List<String> before = CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(analysis);
			for(var planner : R4SharedFedAllAdapterBridge.analysisOnlyPlanners()) invokeOrRecord(planner, analysis, twin, before, missing, id);
			invokeMinStOrRecord(analysis, twin, before, missing, id);
		}
		for(var fixture : CampaignBSelectorFixtureBridge.all()) {
			PlacementAnalysis normal = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production(), ProjectionOrder.NORMAL);
			PlacementAnalysis reverse = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production(), ProjectionOrder.REVERSED);
			for(var planner : R4SharedFedAllAdapterBridge.analysisOnlyPlanners()) {
				try {
					var handle = R4SharedFedAllAdapterBridge.open(planner);
					var left = select(handle, normal);
					var right = select(handle, reverse);
					R4SharedFedAllSemanticValidator.shared(normal, left); R4SharedFedAllSemanticValidator.shared(reverse, right);
					R4SharedFedAllSemanticValidator.stable(left, right, "R4_ORDER_STABILITY|" + fixture.id() + '|' + planner);
				}
				catch(AssertionError e) { recordMissing(missing, fixture.id(), e); }
			}
			try {
				var handle = MinStAnalysisContractBridge.open();
				var left = MinStAnalysisContractBridge.select(handle, normal);
				var right = MinStAnalysisContractBridge.select(handle, reverse);
				MinStAnalysisContractBridge.stable(left, right, "R4_MINST_ORDER_STABILITY|" + fixture.id());
			}
			catch(AssertionError e) { recordMissing(missing, fixture.id(), e); }
		}
		Assert.assertEquals("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING", List.of(), missing);
	}

	@Test public void b17SameHopDifferentContextKeysRemainDistinct() throws Exception {
		PlacementAnalysis compiled = CampaignBPlacementAnalysisFixtureBridge.build(ProductionShadowFixtureFactory.compile("B-17"));
		PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.sameHopContextTrap(compiled);
		IdentityHashMap<Hop,List<PlacementAnalysis.HopOccurrenceProjection>> groups = new IdentityHashMap<>();
		for(var p : analysis.occurrences()) groups.computeIfAbsent(p.hop(), k -> new ArrayList<>()).add(p);
		boolean distinct = groups.values().stream().anyMatch(v -> v.size() > 1 && v.stream().map(p -> p.key())
			.distinct().count() == v.size());
		Assert.assertTrue("R4_SAME_HOP_CONTEXT|B-17", distinct);
	}

	@Test public void sameAdapterRepeatedAndStartBarrierConcurrentCallsAreStable() throws Exception {
		List<String> missing = new ArrayList<>();
		for(var fixture : CampaignBSelectorFixtureBridge.all().stream().filter(c -> Set.of("S-03","S-04","S-06","S-07","S-08-n6").contains(c.id())).toList()) {
			PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
			for(var planner : R4SharedFedAllAdapterBridge.analysisOnlyPlanners()) {
				try {
					var handle = R4SharedFedAllAdapterBridge.open(planner); var baseline = select(handle, analysis);
					for(int i=0;i<3;i++) R4SharedFedAllSemanticValidator.stable(baseline, select(handle,analysis), "R4_REPEAT_STABILITY");
					CountDownLatch ready=new CountDownLatch(8), start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(8);
					List<Future<R4SharedFedAllAdapterBridge.Selection>> futures=new ArrayList<>();
					for(int i=0;i<8;i++) futures.add(pool.submit(() -> {ready.countDown();start.await();return select(handle,analysis);}));
					ready.await(); start.countDown();
					for(var f:futures) R4SharedFedAllSemanticValidator.stable(baseline,f.get(),"R4_CONCURRENCY_STABILITY");
					pool.shutdownNow();
				}
				catch(AssertionError e) { recordMissing(missing,fixture.id(),e); }
			}
		}
		try {
			PlacementAnalysis analysis = CampaignBPlacementAnalysisFixtureBridge.build(
				ProductionShadowFixtureFactory.compile("B-01"));
			MinStAnalysisContractBridge.verifyFixture(analysis);
			var handle = MinStAnalysisContractBridge.open();
			var prepared = MinStAnalysisContractBridge.prepare(handle, analysis);
			var baseline = MinStAnalysisContractBridge.select(handle, prepared, analysis);
			for(int i=0;i<3;i++) MinStAnalysisContractBridge.stable(baseline,
				MinStAnalysisContractBridge.select(handle, prepared, analysis), "R4_MINST_REPEAT_STABILITY");
			CountDownLatch ready=new CountDownLatch(8), start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(8);
			List<Future<MinStAnalysisContractBridge.Selection>> futures=new ArrayList<>();
			for(int i=0;i<8;i++) futures.add(pool.submit(() -> {ready.countDown();start.await();
				return MinStAnalysisContractBridge.select(handle,prepared,analysis);}));
			ready.await(); start.countDown();
			for(var f:futures) MinStAnalysisContractBridge.stable(baseline,f.get(),"R4_MINST_CONCURRENCY_STABILITY");
			pool.shutdownNow();
		}
		catch(AssertionError e) { recordMissing(missing,"B-01",e); }
		Assert.assertEquals("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING",List.of(),missing);
	}

	@Test public void completeAnalysisCollectionsAreDeeplyImmutable() throws Exception {
		for(String id : ProductionShadowFixtureFactory.ids()) {
			PlacementAnalysis a=CampaignBPlacementAnalysisFixtureBridge.build(ProductionShadowFixtureFactory.compile(id));
			expectUoe(() -> raw(a.occurrences()).add(null)); expectUoe(() -> raw(a.graph().nodes()).add(null));
			expectUoe(() -> raw(a.graph().constraints()).add(null)); expectUoe(() -> raw(a.graph().relocationActions()).add(null));
			for(var n:a.graph().nodes()) {expectUoe(() -> raw(n.legalAlternatives()).add(null));expectUoe(() -> raw(n.exclusions()).add(null));expectUoe(() -> raw(n.anchors()).add(null));}
			for(var r:a.graph().relocationActions()) {expectUoe(() -> raw(r.obligations()).add(null));expectUoe(() -> raw(r.key().compatibleConsumers()).add(null));}
		}
	}

	private static void invokeOrRecord(R4SharedFedAllAdapterBridge.Planner planner,PlacementAnalysis analysis,
		PlacementAnalysis twin,List<String> before,List<String> missing,String id) {
		try {
			long count=CampaignBPlacementAnalysisFixtureBridge.constructionCount(); var handle=R4SharedFedAllAdapterBridge.open(planner);
			var selected=select(handle,analysis); R4SharedFedAllSemanticValidator.shared(analysis,selected);
			Assert.assertNotSame(twin,selected.analysis()); Assert.assertEquals(count,CampaignBPlacementAnalysisFixtureBridge.constructionCount());
			Assert.assertEquals(before,CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(analysis));
		}
		catch(AssertionError e) {recordMissing(missing,id,e);}
	}
	private static void invokeMinStOrRecord(PlacementAnalysis analysis,PlacementAnalysis twin,List<String> before,
		List<String> missing,String id) {
		try {
			long count=CampaignBPlacementAnalysisFixtureBridge.constructionCount();
			if(id.equals("B-01")) MinStAnalysisContractBridge.verifyFixture(analysis);
			var handle=MinStAnalysisContractBridge.open(); var selected=MinStAnalysisContractBridge.select(handle,analysis);
			Assert.assertSame(analysis,selected.analysis()); Assert.assertFalse(selected.receipts().isEmpty());
			Assert.assertFalse(selected.obligations().isEmpty()); MinStAnalysisContractBridge.rejectForeign(handle,analysis,twin);
			Assert.assertEquals(count,CampaignBPlacementAnalysisFixtureBridge.constructionCount());
			Assert.assertEquals(before,CampaignBPlacementAnalysisFixtureBridge.fullSnapshot(analysis));
		}
		catch(AssertionError e) {recordMissing(missing,id,e);}
	}
	private static R4SharedFedAllAdapterBridge.Selection select(R4SharedFedAllAdapterBridge.Handle h,PlacementAnalysis a) {
		return h.planner()==R4SharedFedAllAdapterBridge.Planner.HEURISTIC ? R4SharedFedAllAdapterBridge.select(h,a,Set.of())
			: R4SharedFedAllAdapterBridge.select(h,a);
	}
	private static void recordMissing(List<String> out,String id,AssertionError e) {
		if(e.getMessage()!=null&&e.getMessage().startsWith("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING")) out.add(id+'|'+e.getMessage()); else throw e;
	}
	@SuppressWarnings({"rawtypes","unchecked"}) private static List raw(List<?> list){return (List)list;}
	private static void expectUoe(Runnable r){try{r.run();Assert.fail("R4_INPUT_MUTABILITY");}catch(UnsupportedOperationException expected){}}
}
