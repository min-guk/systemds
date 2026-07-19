/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;
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
		Assert.assertEquals(snapshot.cloneReceipts().stream().map(value -> value.cloneOccurrence().hop().getHopID())
			.collect(java.util.stream.Collectors.toMap(value -> value,
				value -> snapshot.cloneToOriginal().get(value))), snapshot.cloneToOriginal());
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
		Assert.assertTrue("B-05 must not fabricate clone mappings", snapshot.cloneToOriginal().isEmpty());
		assertIdentityList(invocation.analysis().occurrences(), snapshot.occurrences(), "B-05 occurrences");
		assertConsumerEdgesAreExact(invocation.analysis(), snapshot.consumerEdges());
	}

	private static void assertConsumerEdgesAreExact(PlacementAnalysis analysis, List<RewireConsumerEdge> edges) {
		for(RewireConsumerEdge edge : edges) {
			HopOccurrenceProjection parent = exact(analysis, edge.parentOccurrence());
			HopOccurrenceProjection child = exact(analysis, edge.childOccurrence());
			Assert.assertTrue(edge.inputPosition() >= 0 && edge.inputPosition() < parent.hop().getInput().size());
			Hop input = parent.hop().getInput().get(edge.inputPosition());
			Assert.assertTrue("edge child must resolve through exact or clone/original production identity",
				input == child.hop() || input.getHopID() == child.hop().getHopID());
		}
	}

	private static HopOccurrenceProjection exact(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key) {
		List<HopOccurrenceProjection> matches = analysis.occurrences().stream()
			.filter(value -> value.key().equals(key)).toList();
		Assert.assertEquals("consumer endpoint occurrence multiplicity", 1, matches.size());
		return matches.get(0);
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
}
