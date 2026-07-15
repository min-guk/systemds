/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge.ProjectionOrder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.test.component.federated.placement.guard.R4SharedFedAllAdapterBridge.Score;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.selector.CampaignBSelectorFixtureBridge;
import org.junit.Assert;
import org.junit.Test;

/** Executable RED: FedAll result and every proof field equal independent exhaustive authority. */
public class CampaignBFedAllExactAdapterContractTest {
	@Test public void exactAssignmentScoreRelocationsHashesBoundsUniverseAndTerminationEqualOracle() throws Exception {
		List<String> missing=new ArrayList<>();
		for(var fixture:CampaignBSelectorFixtureBridge.all()) {
			PlacementAnalysis analysis=CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(fixture.production());
			var oracle=ExactSelectorOracle.select(fixture.oracle(),ExactSelectorOracle.Policy.FED_ALL);
			var expected=expected(fixture,analysis,oracle);
			try {
				var handle=R4SharedFedAllAdapterBridge.open(R4SharedFedAllAdapterBridge.Planner.FED_ALL);
				var actual=R4SharedFedAllAdapterBridge.select(handle,analysis);
				R4SharedFedAllSemanticValidator.shared(analysis,actual); R4SharedFedAllSemanticValidator.fedAll(expected,actual);
			}
			catch(AssertionError e){recordMissing(missing,fixture.id(),e);}
		}
		Assert.assertEquals("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING",List.of(),missing);
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

	private static R4SharedFedAllSemanticValidator.Expected expected(CampaignBSelectorFixtureBridge.Case fixture,
		PlacementAnalysis analysis,ExactSelectorOracle.Result oracle) {
		Map<String,CompiledHopKey> keys=new LinkedHashMap<>();for(var n:fixture.production().nodes())keys.put(n.key().emittedHopInstance(),n.key());
		Map<CompiledHopKey,PlacementState> assignment=new LinkedHashMap<>();LinkedHashSet<String> relocationIds=new LinkedHashSet<>();
		for(Map.Entry<String,Choice> e:oracle.getAssignment().entrySet()) {
			CompiledHopKey key=keys.get(e.getKey());String signature=CampaignBSelectorFixtureBridge.productionChoice(fixture.id(),e.getKey(),e.getValue().getId());
			PlacementState state=fixture.production().node(key).orElseThrow().legalAlternatives().stream()
				.filter(s->s.normalizedSignature().equals(signature)).findFirst().orElseThrow();assignment.put(key,state);relocationIds.addAll(e.getValue().getRelocationActions());
		}
		Map<String,RelocationActionKey> relocationMap=CampaignBSelectorFixtureBridge.productionRelocations(fixture);
		List<RelocationActionKey> relocations=relocationIds.stream().map(id->{RelocationActionKey k=relocationMap.get(id);if(k==null)throw new AssertionError("R4_RELOCATION_KEY|unmapped="+id);return k;})
			.sorted().toList();
		Score score=new Score(oracle.getScore().getFedCount(),oracle.getScore().getFoutCount(),oracle.getScore().getRelocationCount(),oracle.getScore().getSignature());
		long explored=oracle.getCertificate().getExploredCount(),pruned=oracle.getCertificate().getPrunedCount();
		var bounds=R4SharedFedAllSemanticValidator.componentBounds(fixture.production());
		return new R4SharedFedAllSemanticValidator.Expected(Map.copyOf(assignment),relocations,score,
			R4SharedFedAllAdapterBridge.graphHash(analysis),R4SharedFedAllAdapterBridge.assignmentHash(assignment),
			explored,pruned,explored+pruned,fixture.production().nodes().size(),fixture.production().constraints().size(),
			oracle.getCertificate().getComponentCount(),oracle.getCertificate().getBoundDerivation(),bounds);
	}
	private static void recordMissing(List<String> out,String id,AssertionError e){if(e.getMessage()!=null&&e.getMessage().startsWith("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING"))out.add(id+'|'+e.getMessage());else throw e;}
}
