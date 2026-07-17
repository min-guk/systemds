/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator.ChildCostReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator.EstimatorReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEstimator.EstimatorRequest;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile-time RED contract for an exact, analysis-owned and mutation-free DP estimator seam. */
public class CampaignBDpEstimatorOwnerContractTest {
	@Test
	public void exactRequestFreezesRawCostsAndChildOrderWithoutMutation() {
		Fixture fixture = fixture("B-01");
		PlanGraph graph = planGraph(fixture);
		Snapshot before = snapshot(fixture, graph);

		EstimatorRequest request = new EstimatorRequest(
			fixture.analysis(), fixture.root(), graph.memo(), graph.rootPlan());
		EstimatorReceipt receipt = FederatedPlannerDpCostEstimator.estimateExact(request);

		Assert.assertSame("receipt analysis owner", fixture.analysis(), receipt.analysis());
		Assert.assertSame("receipt occurrence owner", fixture.root(), receipt.occurrence());
		Assert.assertSame("receipt occurrence key", fixture.root().key(), receipt.key());
		Assert.assertSame("receipt exact plan", graph.rootPlan(), receipt.plan());
		Assert.assertEquals("self cost raw bits", bits(0x1.0p-4), receipt.selfCostBits());
		Assert.assertEquals("forwarding cost raw bits", bits(0x1.0p-3), receipt.forwardingCostBits());
		Assert.assertEquals("cumulative cost raw bits", bits(0x1.8p2), receipt.cumulativeCostBits());
		Assert.assertEquals("child receipt count", 2, receipt.childCosts().size());
		assertChild(receipt.childCosts().get(0), fixture.children().get(0), graph.childPlans().get(0),
			FederatedOutput.LOUT, 0x1.0p0, 0x1.0p-4);
		assertChild(receipt.childCosts().get(1), fixture.children().get(1), graph.childPlans().get(1),
			FederatedOutput.FOUT, 0x1.0p1, 0x1.8p-4);
		assertUnmodifiable(receipt.childCosts());
		assertSnapshotSame(before, snapshot(fixture, graph));
	}

	@Test
	public void copiedAndForeignOwnersRejectWithoutMutation() {
		Fixture owner = fixture("B-01");
		PlanGraph graph = planGraph(owner);
		Snapshot before = snapshot(owner, graph);

		PlacementAnalysis copied = new NeutralPlacementGraphBuilder().buildAnalysis(owner.program());
		HopOccurrenceProjection copiedOccurrence = copied.occurrences().stream()
			.filter(occurrence -> occurrence.key().equals(owner.root().key()))
			.findFirst().orElseThrow();
		expectReject(() -> FederatedPlannerDpCostEstimator.estimateExact(
			new EstimatorRequest(copied, copiedOccurrence, graph.memo(), graph.rootPlan())));
		assertSnapshotSame(before, snapshot(owner, graph));

		FedPlan copiedHopCommonPlan = plan(owner.root().hop(), FederatedOutput.FOUT, ExecType.FED,
			0x1.8p2, 0x1.0p-4, 0x1.0p-3, graph.rootPlan().getChildFedPlans());
		expectReject(() -> FederatedPlannerDpCostEstimator.estimateExact(
			new EstimatorRequest(owner.analysis(), owner.root(), graph.memo(), copiedHopCommonPlan)));
		assertSnapshotSame(before, snapshot(owner, graph));

		Fixture foreign = fixture("B-02");
		FedPlan foreignPlan = plan(foreign.root().hop(), FederatedOutput.FOUT, ExecType.FED,
			0x1.8p2, 0x1.0p-4, 0x1.0p-3, List.of());
		expectReject(() -> FederatedPlannerDpCostEstimator.estimateExact(
			new EstimatorRequest(foreign.analysis(), foreign.root(), graph.memo(), foreignPlan)));
		assertSnapshotSame(before, snapshot(owner, graph));
	}

	private static PlanGraph planGraph(Fixture fixture) {
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(fixture.analysis());
		FedPlan left = plan(fixture.children().get(0).hop(), FederatedOutput.LOUT, ExecType.CP,
			0x1.0p0, 0x1.0p-5, 0x1.0p-4, List.of());
		FedPlan right = plan(fixture.children().get(1).hop(), FederatedOutput.FOUT, ExecType.FED,
			0x1.0p1, 0x1.8p-5, 0x1.8p-4, List.of());
		register(memo, fixture.children().get(0), left);
		register(memo, fixture.children().get(1), right);
		List<Pair<Long, FederatedOutput>> childEdges = List.of(
			Pair.of(fixture.children().get(0).hop().getHopID(), FederatedOutput.LOUT),
			Pair.of(fixture.children().get(1).hop().getHopID(), FederatedOutput.FOUT));
		FedPlan root = plan(fixture.root().hop(), FederatedOutput.FOUT, ExecType.FED,
			0x1.8p2, 0x1.0p-4, 0x1.0p-3, childEdges);
		register(memo, fixture.root(), root);
		return new PlanGraph(memo, root, List.of(left, right));
	}

	private static FedPlan plan(Hop hop, FederatedOutput output, ExecType execType, double cumulativeCost,
		double selfCost, double forwardingCost, List<Pair<Long, FederatedOutput>> childEdges) {
		HopCommon common = new HopCommon(hop, 1, 1, 1, 1, List.of());
		common.setSelfCost(selfCost);
		common.setForwardingCost(forwardingCost);
		FedPlanVariants variants = new FedPlanVariants(common, output);
		FedPlan plan = new FedPlan(cumulativeCost, variants, childEdges);
		plan.setExecType(execType);
		plan.setFType(FType.ROW);
		variants.addFedPlan(plan);
		return plan;
	}

	private static void register(FederatedPlannerDpMemoTable memo, HopOccurrenceProjection occurrence,
		FedPlan plan) {
		FedPlanVariants variants = new FedPlanVariants(
			new HopCommon(occurrence.hop(), 1, 1, 1, 1, List.of()), plan.getFedOutType());
		variants.getFedPlanVariants().add(plan);
		memo.addFedPlanVariants(occurrence, plan.getFedOutType(), variants);
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			HopOccurrenceProjection root = analysis.occurrences().stream()
				.filter(occurrence -> occurrence.hop().getInput().size() >= 2)
				.findFirst().orElseThrow();
			List<HopOccurrenceProjection> children = root.hop().getInput().stream().limit(2)
				.map(child -> analysis.occurrences().stream()
					.filter(occurrence -> occurrence.hop() == child).findFirst().orElseThrow())
				.toList();
			return new Fixture(program, analysis, root, children);
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile DP estimator owner fixture " + id, e);
		}
	}

	private static void assertChild(ChildCostReceipt receipt, HopOccurrenceProjection occurrence,
		FedPlan plan, FederatedOutput output, double cumulativeCost, double forwardingCost) {
		Assert.assertSame("child occurrence order", occurrence, receipt.occurrence());
		Assert.assertSame("child occurrence key", occurrence.key(), receipt.key());
		Assert.assertSame("child exact plan order", plan, receipt.plan());
		Assert.assertSame("child output order", output, receipt.output());
		Assert.assertEquals("child cumulative raw bits", bits(cumulativeCost), receipt.cumulativeCostBits());
		Assert.assertEquals("child forwarding raw bits", bits(forwardingCost), receipt.forwardingCostBits());
	}

	private static Snapshot snapshot(Fixture fixture, PlanGraph graph) {
		List<PlanSnapshot> plans = new java.util.ArrayList<>();
		plans.add(snapshotPlan(graph.rootPlan()));
		graph.childPlans().stream().map(CampaignBDpEstimatorOwnerContractTest::snapshotPlan).forEach(plans::add);
		List<MemoEntrySnapshot> memoEntries = new java.util.ArrayList<>();
		memoEntries.add(snapshotMemoEntry(graph.memo(), fixture.root(), graph.rootPlan()));
		for(int i = 0; i < fixture.children().size(); i++)
			memoEntries.add(snapshotMemoEntry(graph.memo(), fixture.children().get(i), graph.childPlans().get(i)));
		return new Snapshot(fixture.analysis(), fixture.analysis().analysisFingerprint(),
			List.copyOf(fixture.analysis().occurrences()), List.copyOf(plans), List.copyOf(memoEntries),
			snapshotMemoPresence(fixture.analysis(), graph.memo()));
	}

	private static PlanSnapshot snapshotPlan(FedPlan plan) {
		return new PlanSnapshot(plan, bits(plan.getCumulativeCost()), bits(plan.getSelfCost()),
			bits(plan.getForwardingCost()), plan.getExecType(), plan.getFType(),
			List.copyOf(plan.getChildFedPlans()));
	}

	private static MemoEntrySnapshot snapshotMemoEntry(FederatedPlannerDpMemoTable memo,
		HopOccurrenceProjection occurrence, FedPlan plan) {
		FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(occurrence.hop().getHopID(), plan.getFedOutType()));
		return new MemoEntrySnapshot(occurrence, plan.getFedOutType(), variants,
			List.copyOf(variants.getFedPlanVariants()), memo.getFedPlanAfterPrune(occurrence, plan.getFedOutType()));
	}

	private static List<MemoPresence> snapshotMemoPresence(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo) {
		java.util.LinkedHashSet<Long> analysisHopIds = new java.util.LinkedHashSet<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			analysisHopIds.add(occurrence.hop().getHopID());
		List<MemoPresence> presence = new java.util.ArrayList<>();
		for(long hopId : analysisHopIds)
			for(FederatedOutput output : FederatedOutput.values())
				presence.add(new MemoPresence(hopId, output, memo.contains(hopId, output)));
		return List.copyOf(presence);
	}

	private static void assertSnapshotSame(Snapshot expected, Snapshot actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertEquals(expected.fingerprint(), actual.fingerprint());
		assertIdentityList(expected.occurrences(), actual.occurrences());
		Assert.assertEquals(expected.plans().size(), actual.plans().size());
		for(int i = 0; i < expected.plans().size(); i++)
			assertPlanSnapshotSame(expected.plans().get(i), actual.plans().get(i));
		Assert.assertEquals(expected.memoEntries().size(), actual.memoEntries().size());
		for(int i = 0; i < expected.memoEntries().size(); i++)
			assertMemoEntrySnapshotSame(expected.memoEntries().get(i), actual.memoEntries().get(i));
		Assert.assertEquals(expected.memoPresence(), actual.memoPresence());
	}

	private static void assertPlanSnapshotSame(PlanSnapshot expected, PlanSnapshot actual) {
		Assert.assertSame(expected.plan(), actual.plan());
		Assert.assertEquals(expected.cumulativeBits(), actual.cumulativeBits());
		Assert.assertEquals(expected.selfBits(), actual.selfBits());
		Assert.assertEquals(expected.forwardingBits(), actual.forwardingBits());
		Assert.assertSame(expected.execType(), actual.execType());
		Assert.assertSame(expected.fType(), actual.fType());
		Assert.assertEquals(expected.childEdges(), actual.childEdges());
		assertIdentityList(expected.childEdges(), actual.childEdges());
	}

	private static void assertMemoEntrySnapshotSame(MemoEntrySnapshot expected, MemoEntrySnapshot actual) {
		Assert.assertSame(expected.occurrence(), actual.occurrence());
		Assert.assertSame(expected.output(), actual.output());
		Assert.assertSame(expected.variants(), actual.variants());
		assertIdentityList(expected.variantOrder(), actual.variantOrder());
		Assert.assertSame(expected.selectedPlan(), actual.selectedPlan());
	}

	private static void assertIdentityList(List<?> expected, List<?> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++)
			Assert.assertSame("identity at " + i, expected.get(i), actual.get(i));
	}

	private static void assertUnmodifiable(List<ChildCostReceipt> receipts) {
		try {
			receipts.add(receipts.get(0));
			Assert.fail("estimator child receipt order is mutable");
		}
		catch(UnsupportedOperationException expected) {
			// Immutable ordered evidence is required by the owner contract.
		}
	}

	private static void expectReject(Runnable action) {
		try {
			action.run();
			Assert.fail("accepted copied or foreign estimator owner");
		}
		catch(IllegalArgumentException expected) {
			// Exact identity rejection is the contract under test.
		}
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, HopOccurrenceProjection root,
		List<HopOccurrenceProjection> children) { }
	private record PlanGraph(FederatedPlannerDpMemoTable memo, FedPlan rootPlan, List<FedPlan> childPlans) { }
	private record PlanSnapshot(FedPlan plan, long cumulativeBits, long selfBits, long forwardingBits,
		ExecType execType, FType fType, List<Pair<Long, FederatedOutput>> childEdges) { }
	private record MemoEntrySnapshot(HopOccurrenceProjection occurrence, FederatedOutput output,
		FedPlanVariants variants, List<FedPlan> variantOrder, FedPlan selectedPlan) { }
	private record MemoPresence(long hopId, FederatedOutput output, boolean present) { }
	private record Snapshot(PlacementAnalysis analysis, String fingerprint,
		List<HopOccurrenceProjection> occurrences, List<PlanSnapshot> plans,
		List<MemoEntrySnapshot> memoEntries, List<MemoPresence> memoPresence) { }
}
