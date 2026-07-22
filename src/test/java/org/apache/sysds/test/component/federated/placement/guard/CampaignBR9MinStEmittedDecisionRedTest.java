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
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** RED guard for MinST exact placement decisions that must be scoped to emitted occurrences. */
public class CampaignBR9MinStEmittedDecisionRedTest {
	@Test
	public void derivesOnlyEmittedDecisionsAndFailsClosedOnAmbiguousProjection() throws Exception {
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

		Assert.assertEquals("full canonical occurrence ownership must remain in exact facts", exactScope,
			facts.orderedScope());
		Assert.assertEquals("exact decisions must be emitted-only in canonical order", emittedKeys,
			facts.decisionFactsInScopeOrder().stream().map(decision -> decision.key()).toList());
		Assert.assertEquals("genuine equal cut must remain explicit", MinStExactSelection.TIE_UNSPECIFIED,
			selection.tieCertificate());
		Assert.assertEquals("both exact minimum certificates must be retained", 2,
			selection.minimaCertificates().size());
		Assert.assertTrue("ambiguous selection must not publish states",
			selection.selectedStatesInScopeOrder().isEmpty());
		Assert.assertThrows("ambiguous projection must fail closed", IllegalArgumentException.class,
			() -> MinStExactPlacementProjector.project(facts, selection));
		analysis.assertProgramStructureUnchanged();
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
