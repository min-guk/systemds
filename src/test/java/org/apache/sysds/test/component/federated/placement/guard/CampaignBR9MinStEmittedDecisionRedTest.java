/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPlacementProjector;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED guard for MinST exact placement decisions that must be scoped to emitted occurrences. */
public class CampaignBR9MinStEmittedDecisionRedTest {
	@Test
	public void derivesOnlyEmittedDecisionsWhileProjectingAllOccurrenceReceipts() throws Exception {
		PlacementAnalysis analysis = federatedFunctionAnalysis();
		List<CompiledHopKey> emittedKeys = emittedKeys(analysis);
		List<CompiledHopKey> exactScope = compiledScope(analysis);

		Assert.assertFalse("fixture must have compiled occurrences", exactScope.isEmpty());
		Assert.assertTrue("fixture must include a real federated worker anchor", workerCount(analysis) >= 1);
		Assert.assertTrue("fixture must include emitted decisions", !emittedKeys.isEmpty());
		Assert.assertTrue("fixture must include non-emitted occurrences", analysis.occurrences().stream()
			.anyMatch(occurrence -> !analysis.graph().node(occurrence.key()).orElseThrow().emittedWork()));
		Assert.assertTrue("baseline must exercise the all-occurrence scope hazard", exactScope.size() > emittedKeys.size());

		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, exactScope);
		MinStExactSelection selection = MinStExactSelector.select(facts);
		MinStPlacementInput input = MinStExactPlacementProjector.project(facts, selection);

		Assert.assertEquals("full canonical occurrence ownership must remain in exact facts", exactScope,
			facts.orderedScope());
		Assert.assertEquals("exact decisions must be emitted-only in canonical order", emittedKeys,
			facts.decisionFactsInScopeOrder().stream().map(decision -> decision.key()).toList());
		Assert.assertEquals("selected states must cover emitted decisions only", emittedKeys.size(),
			selection.selectedStatesInScopeOrder().size());
		Assert.assertEquals("carrier receipts must cover every neutral occurrence", analysis.occurrences().size(),
			input.occurrenceReceipts().size());

		Set<CompiledHopKey> emittedByIdentity = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		emittedByIdentity.addAll(emittedKeys);
		for(int i = 0; i < analysis.occurrences().size(); i++) {
			PlacementAnalysis.HopOccurrenceProjection occurrence = analysis.occurrences().get(i);
			MinStPlacementInput.OccurrenceReceipt receipt = input.occurrenceReceipts().get(i);
			Assert.assertSame("receipt must retain exact occurrence owner identity", occurrence.key(),
				receipt.planningKey());
			Assert.assertSame("receipt must retain exact Hop owner identity", occurrence.hop(),
				receipt.planningHop());
			if(!emittedByIdentity.contains(occurrence.key())) {
				Assert.assertNull("non-emitted receipt execType", receipt.execType());
				Assert.assertEquals("non-emitted receipt output", FederatedOutput.NONE, receipt.output());
			}
		}
	}

	private static PlacementAnalysis federatedFunctionAnalysis() throws Exception {
		return new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile("B-21"));
	}

	private static List<CompiledHopKey> compiledScope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
	}

	private static List<CompiledHopKey> emittedKeys(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key)
			.filter(key -> analysis.graph().node(key).orElseThrow().emittedWork()).toList();
	}

	private static int workerCount(PlacementAnalysis analysis) {
		Set<String> workers = new LinkedHashSet<>();
		analysis.graph().nodes().stream().flatMap(node -> node.anchors().stream())
			.flatMap(anchor -> anchor.partitions().stream())
			.map(partition -> partition.workerId()).forEach(workers::add);
		return workers.size();
	}

}
