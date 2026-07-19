/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireConsumerEdge;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Authoritative compile-time RED for the production post-rewire occurrence snapshot. */
public class CampaignBG014RewireOccurrenceSnapshotRedTest {
	@Test
	public void b09PublishesTheExactProductionRewireUniverse() {
		DpInvocationReceipt invocation = invoke("B-09");
		PlacementAnalysis analysis = invocation.analysis();
		RewireOccurrenceSnapshot snapshot = invocation.semanticConsumption().rewireSnapshot();

		Assert.assertSame(analysis, snapshot.analysis());
		Assert.assertEquals(analysis.analysisFingerprint(), snapshot.analysisFingerprint());
		assertIdentityList(analysis.occurrences(), snapshot.occurrences(), "occurrences");
		Assert.assertFalse("scope key must bind one real enumeration", snapshot.enumerationScopeKey().isBlank());
		Assert.assertEquals("B-09 must retain its semantic recompile clone", 1, snapshot.cloneReceipts().size());
		FederatedPlannerDpRewireTransTable.CloneReceipt semanticClone = snapshot.cloneReceipts().get(0);
		assertPhysicalCloneDomainIsDisjoint(analysis, snapshot);
		Assert.assertFalse("semantic clone Hop ID must not be a physical loop-unroll clone key",
			snapshot.cloneToOriginal().containsKey(semanticClone.cloneOccurrence().hop().getHopID()));
		assertImmutable(snapshot.occurrences());
		assertImmutable(snapshot.cloneReceipts());
		assertImmutable(snapshot.additionalRoots());
		assertImmutable(snapshot.consumerEdges());
		assertImmutable(snapshot.cloneToOriginal());
		assertConsumerEdgesAreExact(analysis, snapshot.consumerEdges());
	}

	@Test
	public void b05PreservesMultiplicityAndInputPositionsWithoutCloneRepair() {
		DpInvocationReceipt invocation = invoke("B-05");
		RewireOccurrenceSnapshot snapshot = invocation.semanticConsumption().rewireSnapshot();

		Assert.assertTrue("B-05 must not fabricate clone receipts", snapshot.cloneReceipts().isEmpty());
		assertPhysicalCloneDomainIsDisjoint(invocation.analysis(), snapshot);
		assertIdentityList(invocation.analysis().occurrences(), snapshot.occurrences(), "B-05 occurrences");
		assertConsumerEdgesAreExact(invocation.analysis(), snapshot.consumerEdges());
	}

	private static void assertPhysicalCloneDomainIsDisjoint(PlacementAnalysis analysis,
		RewireOccurrenceSnapshot snapshot) {
		Set<Long> analysisHopIds = new HashSet<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			analysisHopIds.add(occurrence.hop().getHopID());
		Assert.assertFalse("loop-unroll fixture must retain physical clone mappings",
			snapshot.cloneToOriginal().isEmpty());
		for(Map.Entry<Long, Long> physicalClone : snapshot.cloneToOriginal().entrySet()) {
			Assert.assertFalse("physical clone key must not be an analysis-owned Hop ID",
				analysisHopIds.contains(physicalClone.getKey()));
			Assert.assertTrue("physical clone original must be an analysis-owned Hop ID",
				analysisHopIds.contains(physicalClone.getValue()));
		}
	}

	private static void assertConsumerEdgesAreExact(PlacementAnalysis analysis, List<RewireConsumerEdge> edges) {
		List<ExpectedEdge> expected = independentlyDeriveEdges(analysis);
		Assert.assertEquals("consumer edge completeness/multiplicity", expected.size(), edges.size());
		for(int i = 0; i < expected.size(); i++) {
			ExpectedEdge want = expected.get(i);
			RewireConsumerEdge actual = edges.get(i);
			Assert.assertSame("parent occurrence identity " + i, want.parent().key(), actual.parentOccurrence());
			Assert.assertSame("child occurrence identity " + i, want.child().key(), actual.childOccurrence());
			Assert.assertEquals("input position " + i, want.inputPosition(), actual.inputPosition());
		}
	}

	private static List<ExpectedEdge> independentlyDeriveEdges(PlacementAnalysis analysis) {
		Map<Hop, HopOccurrenceProjection> exactByHop = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Assert.assertNull("one exact occurrence per concrete rewired Hop",
				exactByHop.put(occurrence.hop(), occurrence));
		}
		List<ExpectedEdge> expected = new ArrayList<>();
		for(HopOccurrenceProjection parent : analysis.occurrences()) {
			for(int inputPosition = 0; inputPosition < parent.hop().getInput().size(); inputPosition++) {
				Hop input = parent.hop().getInput().get(inputPosition);
				HopOccurrenceProjection child = exactByHop.get(input);
				Assert.assertNotNull("concrete input must resolve by exact object identity", child);
				expected.add(new ExpectedEdge(parent, child, inputPosition));
			}
		}
		return List.copyOf(expected);
	}

	private static DpInvocationReceipt invoke(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
				new DMLTranslator(program).constructLops(program, value -> {
					if(!receipt.compareAndSet(null, value))
						throw new AssertionError("G014_REWIRE_MULTIPLE_RECEIPTS");
				});
			}
			finally {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old);
			}
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return (DpInvocationReceipt) receipt.get();
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile G014 rewire fixture " + id, e);
		}
	}

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++) Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values) {
		try { ((List) values).add(null); Assert.fail("mutable list"); }
		catch(UnsupportedOperationException expected) { }
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(java.util.Map<?, ?> values) {
		try { ((java.util.Map) values).put(null, null); Assert.fail("mutable map"); }
		catch(UnsupportedOperationException expected) { }
	}

	private record ExpectedEdge(HopOccurrenceProjection parent, HopOccurrenceProjection child,
		int inputPosition) { }
}
