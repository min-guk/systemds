/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateMapEntry;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.MapEntryState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.OracleInputState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Authoritative compile-time RED for neutral raw/promoted DP candidate facts. */
public class CampaignBG014CandidateOccurrenceSnapshotRedTest {
	@Test
	public void semanticDomainsHaveTheExactStableOrderAndDoNotCollapsePresentNull() {
		Assert.assertEquals(List.of("ABSENT_LOCAL", "PRESENT_NULL", "PRESENT_ROW", "PRESENT_COL",
			"PRESENT_FULL", "PRESENT_BROADCAST", "PRESENT_PART", "PRESENT_OTHER"),
			Arrays.stream(MapEntryState.values()).map(Enum::name).toList());
		Assert.assertEquals(List.of("ABSENT_LOCAL", "ROW", "COL", "FULL", "BROADCAST", "PART", "OTHER"),
			Arrays.stream(OracleInputState.values()).map(Enum::name).toList());
		Assert.assertEquals(List.of("AVAILABLE", "ANCHOR_METADATA_INCOMPLETE", "UNSUPPORTED_ANCHOR_METADATA",
			"FOREIGN_CONTEXT", "STALE_CONTEXT", "DUPLICATE_OCCURRENCE", "REORDERED_EDGE",
			"UNMAPPABLE_OCCURRENCE"),
			Arrays.stream(ConstructionDisposition.values()).map(Enum::name).toList());

		DpInvocationReceipt invocation = invoke("B-11");
		HopOccurrenceProjection occurrence = invocation.analysis().occurrences().get(0);
		CandidateMapEntry presentNull = new CandidateMapEntry(occurrence.key(), 0, true, null,
			MapEntryState.PRESENT_NULL, null);
		Assert.assertTrue(presentNull.mapContainsKey());
		Assert.assertNull(presentNull.rawFType());
		Assert.assertSame(MapEntryState.PRESENT_NULL, presentNull.mapEntryState());
		Assert.assertNull("present-null has no oracle state", presentNull.oracleInputState());
	}

	@Test
	public void realInvocationCapturesEveryRawCandidateBeforeSelection() {
		DpInvocationReceipt invocation = invoke("B-11");
		PreSelectionSemanticBlock block = invocation.semanticConsumption().semanticBlock();
		Assert.assertSame(invocation.analysis(), block.context().analysis());
		Assert.assertSame(invocation.semanticConsumption().rewireSnapshot(), block.context().rewireSnapshot());
		Assert.assertEquals(block.rawCandidateCount(), block.capturedCandidateCount());
		Assert.assertTrue("successful canonical enumeration is zero-difference", block.zeroDifference());
		Assert.assertFalse("fixture must exercise candidate capture", block.candidateSnapshots().isEmpty());
		for(CandidateOccurrenceSnapshot snapshot : block.candidateSnapshots()) {
			Assert.assertSame(block.context(), snapshot.context());
			Assert.assertSame(ConstructionDisposition.AVAILABLE, snapshot.disposition());
			Assert.assertEquals("AVAILABLE", snapshot.reasonCode());
			Assert.assertEquals(snapshot.rawEntries().size(), snapshot.promotedEntries().size());
			Assert.assertEquals(snapshot.promotedEntries().size(), snapshot.orderedOracleInputs().size());
			for(int i = 0; i < snapshot.rawEntries().size(); i++) {
				CandidateMapEntry raw = snapshot.rawEntries().get(i);
				CandidateMapEntry promoted = snapshot.promotedEntries().get(i);
				Assert.assertEquals(i, raw.edgePosition());
				Assert.assertEquals(i, promoted.edgePosition());
				Assert.assertEquals(raw.occurrence(), promoted.occurrence());
				if(!raw.mapContainsKey()) Assert.assertSame(MapEntryState.ABSENT_LOCAL, raw.mapEntryState());
				if(raw.mapContainsKey() && raw.rawFType() == null)
					Assert.assertSame(MapEntryState.PRESENT_NULL, raw.mapEntryState());
				if(raw.rawFType() != null) assertFTypeProjection(raw.rawFType(), raw);
			}
		}
		assertImmutable(block.candidateSnapshots());
	}

	private static void assertFTypeProjection(FType type, CandidateMapEntry entry) {
		Assert.assertEquals("PRESENT_" + type.name(), entry.mapEntryState().name());
		Assert.assertEquals(type.name(), entry.oracleInputState().name());
	}

	private static DpInvocationReceipt invoke(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			String old = ConfigurationManager.getDMLConfig().getTextValue(DMLConfig.FEDERATED_PLANNER);
			AtomicReference<PlannerInvocationReceipt> receipt = new AtomicReference<>();
			try {
				ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
				new DMLTranslator(program).constructLops(program, receipt::set);
			}
			finally { ConfigurationManager.getDMLConfig().setTextValue(DMLConfig.FEDERATED_PLANNER, old); }
			Assert.assertTrue(receipt.get() instanceof DpInvocationReceipt);
			return (DpInvocationReceipt) receipt.get();
		}
		catch(Exception e) { throw new AssertionError("Unable to compile G014 candidate fixture " + id, e); }
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values) {
		try { ((List) values).add(null); Assert.fail("mutable candidate snapshots"); }
		catch(UnsupportedOperationException expected) { }
	}
}
