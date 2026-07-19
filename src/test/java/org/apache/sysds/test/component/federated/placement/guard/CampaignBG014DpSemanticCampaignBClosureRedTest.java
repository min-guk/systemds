/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner.PlannerInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpSemanticConsumptionReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.SemanticConsumptionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile-time RED for complete, real-invocation Slice-B semantic consumption. */
public class CampaignBG014DpSemanticCampaignBClosureRedTest {
	private static final List<String> APPLICABLE = List.of("B-05", "B-09", "B-11", "B-13", "B-21", "B-22");

	@Test
	public void applicableFixturesConsumeTheExactFrozenSemanticBlock() throws Exception {
		for(String id : APPLICABLE)
			assertConsumed(run(id));
	}

	@Test
	public void repeatedAndFreshAnalysesNeverReuseOrFabricateSemanticEvidence() throws Exception {
		Invocation first = run("B-09");
		Invocation second = rerun(first);
		Invocation fresh = run("B-09");
		assertConsumed(first);
		assertConsumed(second);
		assertConsumed(fresh);
		Assert.assertSame("G014_REPEAT_ANALYSIS_IDENTITY", first.analysis(), second.analysis());
		Assert.assertNotSame("G014_FRESH_ANALYSIS_IDENTITY", first.analysis(), fresh.analysis());
		Assert.assertNotSame("G014_FRESH_SEMANTIC_BLOCK_IDENTITY", semantic(first).semanticBlock(),
			semantic(fresh).semanticBlock());
	}

	@Test
	public void reverseFixtureOrderDoesNotChangePerFixtureSemanticEvidence() throws Exception {
		List<String> reverse = new ArrayList<>(APPLICABLE);
		Collections.reverse(reverse);
		for(String id : reverse)
			assertConsumed(run(id));
	}

	@Test
	public void concurrentFreshAnalysesRemainExactlyOwned() throws Exception {
		var pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Invocation>> work = List.of(() -> run("B-05"), () -> run("B-09"));
			for(var future : pool.invokeAll(work))
				assertConsumed(future.get());
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static void assertConsumed(Invocation invocation) {
		DpInvocationReceipt receipt = invocation.receipt();
		DpSemanticConsumptionReceipt semantic = semantic(invocation);
		Assert.assertSame("G014_CONSUMPTION_ANALYSIS_IDENTITY", invocation.analysis(), semantic.analysis());
		Assert.assertSame("G014_CONSUMPTION_REWIRE_IDENTITY", receipt.exactSelection().analysis(),
			semantic.rewireSnapshot().analysis());
		Assert.assertSame("G014_CONSUMPTION_SELECTION_IDENTITY", receipt.exactSelection(),
			semantic.exactSelection());
		Assert.assertSame("G014_CONSUMPTION_BLOCK_CONTEXT_IDENTITY", semantic.rewireSnapshot(),
			semantic.semanticBlock().context().rewireSnapshot());
		Assert.assertEquals("G014_CONSUMPTION_STATE", SemanticConsumptionState.CONSUMED, semantic.state());
		Assert.assertTrue("G014_ZERO_CANDIDATE_DIFFERENCE", semantic.semanticBlock().zeroDifference());
		Assert.assertEquals("G014_RAW_CAPTURE_COUNT",
			semantic.semanticBlock().rawCandidateCount(), semantic.semanticBlock().capturedCandidateCount());
		Assert.assertEquals("G014_ANALYSIS_FINGERPRINT_BEFORE", invocation.analysis().analysisFingerprint(),
			semantic.analysisFingerprintBefore());
		Assert.assertEquals("G014_ANALYSIS_FINGERPRINT_AFTER", semantic.analysisFingerprintBefore(),
			semantic.analysisFingerprintAfter());
		Assert.assertEquals("G014_NO_REENUMERATION", 0, receipt.counters().reenumerationCount());
		Assert.assertEquals("G014_NO_REPAIR", 0, receipt.counters().repairCount());
		Assert.assertEquals("G014_NO_FALLBACK", 0, receipt.counters().fallbackCount());
		Assert.assertEquals("G014_NO_DOUBLE_APPLICATION", 0, receipt.counters().doubleApplicationCount());
	}

	private static DpSemanticConsumptionReceipt semantic(Invocation invocation) {
		return invocation.receipt().semanticConsumption();
	}

	private static Invocation rerun(Invocation invocation) {
		DpInvocationReceipt receipt = new FederatedPlannerDpFedCostBased().rewriteProgram(invocation.program(),
			new FunctionCallGraph(invocation.program()), null, invocation.analysis());
		return new Invocation(invocation.program(), invocation.analysis(), receipt);
	}

	private static Invocation run(String id) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(id);
		String oldPlanner = org.apache.sysds.conf.ConfigurationManager.getDMLConfig()
			.getTextValue(org.apache.sysds.conf.DMLConfig.FEDERATED_PLANNER);
		java.util.concurrent.atomic.AtomicReference<PlannerInvocationReceipt> finalBoundary =
			new java.util.concurrent.atomic.AtomicReference<>();
		try {
			org.apache.sysds.conf.ConfigurationManager.getDMLConfig()
				.setTextValue(org.apache.sysds.conf.DMLConfig.FEDERATED_PLANNER, "compile_cost_based");
			new org.apache.sysds.parser.DMLTranslator(program).constructLops(program, receipt -> {
				if(!finalBoundary.compareAndSet(null, receipt))
					throw new AssertionError("G014_FINAL_BOUNDARY_MULTIPLE_RECEIPTS|" + id);
			});
		}
		finally {
			org.apache.sysds.conf.ConfigurationManager.getDMLConfig()
				.setTextValue(org.apache.sysds.conf.DMLConfig.FEDERATED_PLANNER, oldPlanner);
		}
		Assert.assertNotNull("G014_FINAL_BOUNDARY_RECEIPT_MISSING|" + id, finalBoundary.get());
		PlacementAnalysis analysis = finalBoundary.get().analysis();
		analysis.assertCanonicalProgramAuthority(program);
		DpInvocationReceipt receipt = new FederatedPlannerDpFedCostBased().rewriteProgram(program,
			new FunctionCallGraph(program), null, analysis);
		return new Invocation(program, analysis, receipt);
	}

	private record Invocation(DMLProgram program, PlacementAnalysis analysis, DpInvocationReceipt receipt) { }
}
