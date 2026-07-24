/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.conf.DMLConfig;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpSemanticConsumptionReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.SemanticConsumptionState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Authoritative compile-time RED for exact semantic consumption by the canonical DP invocation. */
public class CampaignBG014InvocationSemanticReceiptRedTest {
	@Test
	public void canonicalInvocationConsumesTheExactEnumerationEvidence() {
		DpInvocationReceipt invocation = invoke("B-11");
		DpSemanticConsumptionReceipt semantic = invocation.semanticConsumption();

		Assert.assertSame(invocation.analysis(), semantic.analysis());
		Assert.assertSame(invocation.exactSelection(), semantic.exactSelection());
		Assert.assertSame(semantic.analysis(), semantic.rewireSnapshot().analysis());
		Assert.assertSame(semantic.rewireSnapshot(), semantic.semanticBlock().context().rewireSnapshot());
		Assert.assertSame(semantic.analysis(), semantic.semanticBlock().context().analysis());
		Assert.assertSame(SemanticConsumptionState.CONSUMED, semantic.state());
		Assert.assertEquals(semantic.analysisFingerprintBefore(), semantic.analysisFingerprintAfter());
		Assert.assertEquals(invocation.analysisFingerprintBefore(), semantic.analysisFingerprintBefore());
		Assert.assertEquals(invocation.analysisFingerprintAfter(), semantic.analysisFingerprintAfter());
		Assert.assertEquals(semantic.semanticBlock().rawCandidateCount(),
			semantic.semanticBlock().capturedCandidateCount());
		Assert.assertTrue(semantic.semanticBlock().zeroDifference());
		Assert.assertEquals(0, invocation.counters().reenumerationCount());
		Assert.assertEquals(0, invocation.counters().repairCount());
		Assert.assertEquals(0, invocation.counters().fallbackCount());
		Assert.assertEquals(0, invocation.counters().doubleApplicationCount());
	}

	@Test
	public void copiedOrPostHocSemanticBlocksCannotFabricateConsumedSuccess() {
		DpInvocationReceipt invocation = invoke("B-11");
		DpSemanticConsumptionReceipt exact = invocation.semanticConsumption();
		PreSelectionSemanticBlock copied = new PreSelectionSemanticBlock(exact.semanticBlock().context(),
			new ArrayList<>(exact.semanticBlock().candidateSnapshots()), exact.semanticBlock().rawCandidateCount(),
			exact.semanticBlock().capturedCandidateCount(), exact.semanticBlock().zeroDifference());
		try {
			new DpSemanticConsumptionReceipt(exact.analysis(), exact.rewireSnapshot(), copied,
				exact.exactSelection(), SemanticConsumptionState.CONSUMED,
				exact.analysisFingerprintBefore(), exact.analysisFingerprintAfter());
			Assert.fail("accepted a copied/post-hoc semantic block as consumed production evidence");
		}
		catch(IllegalArgumentException expected) {
			// Exact producer identity is mandatory.
		}
	}

	private static DMLProgram compileFixture(String id) throws Exception {
		return "B-11".equals(id) ? CampaignBG014HermeticPlannerFixtureFactory.compile(id)
			: ProductionShadowFixtureFactory.compile(id);
	}

	private static DpInvocationReceipt invoke(String id) {
		try {
			DMLProgram program = compileFixture(id);
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
		catch(Exception e) { throw new AssertionError("Unable to compile G014 invocation fixture " + id, e); }
	}
}
