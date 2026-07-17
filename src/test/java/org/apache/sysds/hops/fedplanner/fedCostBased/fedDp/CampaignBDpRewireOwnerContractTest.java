/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.HopCommon;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.CloneReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireRequest;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile-time RED for a direct, analysis-owned, mutation-free DP rewire projection. */
public class CampaignBDpRewireOwnerContractTest {
	@Test
	public void b09ReturnsExactCloneOriginAndRecompileTopologyWithoutMutation() {
		Fixture fixture = fixture("B-09");
		PlanGraph plans = planGraph(fixture);
		Snapshot before = snapshot(fixture, plans);

		RewireReceipt receipt = FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(fixture.analysis(), fixture.program(), fixture.analysis().occurrences()));

		Assert.assertSame("analysis owner", fixture.analysis(), receipt.analysis());
		Assert.assertSame("program owner", fixture.program(), receipt.program());
		Assert.assertEquals("analysis fingerprint", fixture.analysis().analysisFingerprint(),
			receipt.analysisFingerprint());
		assertIdentityList(fixture.analysis().occurrences(), receipt.orderedOccurrences(), "occurrences");
		Assert.assertEquals("normalized identity order", fixture.analysis().graph().normalizedIdentities(),
			receipt.orderedNormalizedIdentities());

		ExpectedClone expected = expectedClone(fixture.analysis());
		Assert.assertEquals("B-09 clone receipt count", 1, receipt.cloneReceipts().size());
		CloneReceipt clone = receipt.cloneReceipts().get(0);
		Assert.assertSame("origin occurrence", expected.originOccurrence(), clone.originOccurrence());
		Assert.assertSame("clone occurrence", expected.cloneOccurrence(), clone.cloneOccurrence());
		Assert.assertSame("origin node", expected.originNode(), clone.originNode());
		Assert.assertSame("clone node", expected.cloneNode(), clone.cloneNode());
		Assert.assertSame("SAME_ORIGIN constraint", expected.sameOrigin(), clone.sameOrigin());
		Assert.assertSame("RECOMPILE_CP_FOUT exclusion", expected.exclusion(),
			clone.recompileCpFoutExclusion());
		Assert.assertEquals("clone value version", "CLONE_RECOMPILE",
			clone.cloneNode().valueVersion().versionKind().name());
		Assert.assertEquals("clone recompile context", "recompile",
			clone.cloneNode().key().recompileContext());
		Assert.assertEquals("clone/original map size", 1, receipt.cloneToOrig().size());
		Assert.assertEquals("clone/original Hop IDs", expected.originOccurrence().hop().getHopID(),
			receipt.cloneToOrig().get(expected.cloneOccurrence().hop().getHopID()).longValue());
		Assert.assertTrue("B-09 must not fabricate legacy-only additional roots",
			receipt.orderedAdditionalRoots().isEmpty());
		assertImmutable(receipt.cloneReceipts(), "clone receipts");
		assertImmutable(receipt.orderedOccurrences(), "ordered occurrences");
		assertImmutable(receipt.orderedAdditionalRoots(), "additional roots");
		assertImmutable(receipt.orderedNormalizedIdentities(), "normalized identities");
		assertImmutable(receipt.cloneToOrig(), "clone/original map");
		assertSnapshotSame(before, snapshot(fixture, plans));
	}

	@Test
	public void b05ReturnsNoCloneAndPreservesEveryOriginalIdentity() {
		Fixture fixture = fixture("B-05");
		PlanGraph plans = planGraph(fixture);
		Snapshot before = snapshot(fixture, plans);

		RewireReceipt receipt = FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(fixture.analysis(), fixture.program(), fixture.analysis().occurrences()));

		Assert.assertSame(fixture.analysis(), receipt.analysis());
		Assert.assertSame(fixture.program(), receipt.program());
		assertIdentityList(fixture.analysis().occurrences(), receipt.orderedOccurrences(), "B-05 originals");
		Assert.assertTrue("B-05 clone receipts", receipt.cloneReceipts().isEmpty());
		Assert.assertTrue("B-05 clone map", receipt.cloneToOrig().isEmpty());
		Assert.assertTrue("B-05 additional roots", receipt.orderedAdditionalRoots().isEmpty());
		Assert.assertFalse("B-05 has clone node", fixture.analysis().graph().nodes().stream()
			.anyMatch(node -> node.kind() == NodeKind.CLONE));
		Assert.assertFalse("B-05 has recompile exclusion", fixture.analysis().graph().nodes().stream()
			.flatMap(node -> node.exclusions().stream())
			.anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT));
		assertSnapshotSame(before, snapshot(fixture, plans));
	}

	@Test
	public void copiedForeignAndSubstitutedOwnersRejectBeforeMutation() {
		Fixture owner = fixture("B-09");
		PlanGraph plans = planGraph(owner);
		Snapshot before = snapshot(owner, plans);

		List<HopOccurrenceProjection> copiedList = new ArrayList<>(owner.analysis().occurrences());
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), owner.program(), copiedList)), owner, plans, before);

		List<HopOccurrenceProjection> copiedOccurrenceList = new ArrayList<>(owner.analysis().occurrences());
		HopOccurrenceProjection original = copiedOccurrenceList.get(0);
		copiedOccurrenceList.set(0, new HopOccurrenceProjection(original.key(), original.hop(),
			original.normalizedOrdinal(), original.normalizedSignature()));
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), owner.program(), copiedOccurrenceList)), owner, plans, before);

		List<HopOccurrenceProjection> reordered = new ArrayList<>(owner.analysis().occurrences());
		java.util.Collections.swap(reordered, 0, 1);
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), owner.program(), reordered)), owner, plans, before);

		Fixture foreign = fixture("B-05");
		PlanGraph foreignPlans = planGraph(foreign);
		Snapshot foreignBefore = snapshot(foreign, foreignPlans);
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(foreign.analysis(), owner.program(), foreign.analysis().occurrences())),
			owner, plans, before);
		assertSnapshotSame(foreignBefore, snapshot(foreign, foreignPlans));

		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), foreign.program(), owner.analysis().occurrences())),
			owner, plans, before);
		assertSnapshotSame(foreignBefore, snapshot(foreign, foreignPlans));
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), owner.program(), foreign.analysis().occurrences())),
			owner, plans, before);
		assertSnapshotSame(foreignBefore, snapshot(foreign, foreignPlans));

		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(null, owner.program(), owner.analysis().occurrences())), owner, plans, before);
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), null, owner.analysis().occurrences())), owner, plans, before);
		expectRejectAndUnchanged(() -> FederatedPlannerDpRewireTransTable.inspectExact(
			new RewireRequest(owner.analysis(), owner.program(), null)), owner, plans, before);
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			HopOccurrenceProjection root = analysis.occurrences().stream()
				.filter(occurrence -> !occurrence.hop().getInput().isEmpty()).findFirst().orElseThrow();
			Map<Hop, HopOccurrenceProjection> occurrenceByHop = new IdentityHashMap<>();
			for(HopOccurrenceProjection occurrence : analysis.occurrences())
				occurrenceByHop.put(occurrence.hop(), occurrence);
			List<HopOccurrenceProjection> children = root.hop().getInput().stream().limit(2)
				.map(occurrenceByHop::get).filter(java.util.Objects::nonNull).toList();
			return new Fixture(program, analysis, root, children);
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile DP rewire fixture " + id, e);
		}
	}

	private static ExpectedClone expectedClone(PlacementAnalysis analysis) {
		Node cloneNode = analysis.graph().nodes().stream().filter(node -> node.kind() == NodeKind.CLONE)
			.findFirst().orElseThrow();
		Constraint sameOrigin = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.kind() == ConstraintKind.SAME_ORIGIN)
			.filter(constraint -> constraint.left().equals(cloneNode.key())
				|| constraint.right().equals(cloneNode.key())).findFirst().orElseThrow();
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey originKey =
			sameOrigin.left().equals(cloneNode.key()) ? sameOrigin.right() : sameOrigin.left();
		Node originNode = analysis.graph().node(originKey).orElseThrow();
		HopOccurrenceProjection cloneOccurrence = occurrence(analysis, cloneNode);
		HopOccurrenceProjection originOccurrence = occurrence(analysis, originNode);
		Exclusion exclusion = cloneNode.exclusions().stream()
			.filter(item -> item.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT).findFirst().orElseThrow();
		return new ExpectedClone(originOccurrence, cloneOccurrence, originNode, cloneNode, sameOrigin, exclusion);
	}

	private static HopOccurrenceProjection occurrence(PlacementAnalysis analysis, Node node) {
		return analysis.occurrences().stream().filter(item -> item.key().equals(node.key())).findFirst().orElseThrow();
	}

	private static PlanGraph planGraph(Fixture fixture) {
		FederatedPlannerDpMemoTable memo = new FederatedPlannerDpMemoTable(fixture.analysis());
		List<FedPlan> children = new ArrayList<>();
		List<Pair<Long, FederatedOutput>> edges = new ArrayList<>();
		for(int i = 0; i < fixture.children().size(); i++) {
			HopOccurrenceProjection occurrence = fixture.children().get(i);
			FederatedOutput output = i % 2 == 0 ? FederatedOutput.LOUT : FederatedOutput.FOUT;
			FedPlan plan = plan(occurrence.hop(), output, output == FederatedOutput.FOUT ? ExecType.FED : ExecType.CP,
				0x1.0p0 + i, 0x1.0p-5 + i * 0x1.0p-7, 0x1.0p-4 + i * 0x1.0p-6, List.of());
			register(memo, occurrence, plan);
			children.add(plan);
			edges.add(Pair.of(occurrence.hop().getHopID(), output));
		}
		FedPlan root = plan(fixture.root().hop(), FederatedOutput.FOUT, ExecType.FED,
			0x1.8p2, 0x1.0p-3, 0x1.8p-3, List.copyOf(edges));
		register(memo, fixture.root(), root);
		return new PlanGraph(memo, root, List.copyOf(children));
	}

	private static FedPlan plan(Hop hop, FederatedOutput output, ExecType execType, double cumulative,
		double self, double forwarding, List<Pair<Long, FederatedOutput>> children) {
		HopCommon common = new HopCommon(hop, 1, 1, 1, 1, List.of());
		common.setSelfCost(self);
		common.setForwardingCost(forwarding);
		FedPlanVariants variants = new FedPlanVariants(common, output);
		FedPlan plan = new FedPlan(cumulative, variants, children);
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

	private static Snapshot snapshot(Fixture fixture, PlanGraph plans) {
		List<OccurrenceState> occurrences = fixture.analysis().occurrences().stream()
			.map(item -> new OccurrenceState(item, item.key(), item.hop(), item.normalizedOrdinal(),
				item.normalizedSignature())).toList();
		List<HopState> hops = fixture.analysis().occurrences().stream().map(HopOccurrenceProjection::hop)
			.distinct().map(CampaignBDpRewireOwnerContractTest::snapshotHop).toList();
		List<OwnedExclusion> exclusions = new ArrayList<>();
		for(Node node : fixture.analysis().graph().nodes())
			for(Exclusion exclusion : node.exclusions())
				exclusions.add(new OwnedExclusion(node, exclusion));
		List<PlanState> planStates = new ArrayList<>();
		planStates.add(snapshotPlan(plans.rootPlan()));
		plans.childPlans().stream().map(CampaignBDpRewireOwnerContractTest::snapshotPlan).forEach(planStates::add);
		List<MemoEntryState> memoEntries = new ArrayList<>();
		memoEntries.add(snapshotMemo(plans.memo(), fixture.root(), plans.rootPlan()));
		for(int i = 0; i < fixture.children().size(); i++)
			memoEntries.add(snapshotMemo(plans.memo(), fixture.children().get(i), plans.childPlans().get(i)));
		List<MemoPresence> presence = memoPresence(fixture.analysis(), plans.memo());
		Counters counters = new Counters(occurrences.size(), fixture.analysis().graph().nodes().size(),
			fixture.analysis().graph().constraints().size(), exclusions.size(),
			(int) fixture.analysis().graph().nodes().stream().filter(node -> node.kind() == NodeKind.CLONE).count(),
			(int) presence.stream().filter(MemoPresence::present).count(), planStates.size());
		return new Snapshot(fixture.analysis(), fixture.program(), PlacementGraphFingerprint.capture(fixture.program()),
			fixture.analysis().analysisFingerprint(), List.copyOf(occurrences), List.copyOf(hops),
			fixture.analysis().graph().nodes(), fixture.analysis().graph().constraints(), List.copyOf(exclusions),
			plans.memo(), List.copyOf(planStates), List.copyOf(memoEntries), presence, counters);
	}

	private static HopState snapshotHop(Hop hop) {
		return new HopState(hop, hop.getHopID(), hop.getForcedExecType(), hop.getFederatedOutput(),
			hop.requiresRecompile(), List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
	}

	private static PlanState snapshotPlan(FedPlan plan) {
		return new PlanState(plan, bits(plan.getCumulativeCost()), bits(plan.getSelfCost()),
			bits(plan.getForwardingCost()), plan.getExecType(), plan.getFType(), List.copyOf(plan.getChildFedPlans()));
	}

	private static MemoEntryState snapshotMemo(FederatedPlannerDpMemoTable memo,
		HopOccurrenceProjection occurrence, FedPlan plan) {
		FedPlanVariants variants = memo.getFedPlanVariants(Pair.of(occurrence.hop().getHopID(),
			plan.getFedOutType()));
		return new MemoEntryState(occurrence, variants, List.copyOf(variants.getFedPlanVariants()),
			memo.getFedPlanAfterPrune(occurrence, plan.getFedOutType()));
	}

	private static List<MemoPresence> memoPresence(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo) {
		Set<Long> ids = new LinkedHashSet<>();
		analysis.occurrences().forEach(occurrence -> ids.add(occurrence.hop().getHopID()));
		List<MemoPresence> result = new ArrayList<>();
		for(long id : ids)
			for(FederatedOutput output : FederatedOutput.values())
				result.add(new MemoPresence(id, output, memo.contains(id, output)));
		return List.copyOf(result);
	}

	private static void expectRejectAndUnchanged(Runnable action, Fixture fixture, PlanGraph plans,
		Snapshot before) {
		try {
			action.run();
			Assert.fail("accepted copied, foreign, reordered, or substituted rewire owner");
		}
		catch(IllegalArgumentException expected) {
			// All ownership checks must complete before any topology or planner mutation.
		}
		assertSnapshotSame(before, snapshot(fixture, plans));
	}

	private static void assertSnapshotSame(Snapshot expected, Snapshot actual) {
		Assert.assertSame(expected.analysis(), actual.analysis());
		Assert.assertSame(expected.program(), actual.program());
		Assert.assertEquals(expected.programFingerprint(), actual.programFingerprint());
		Assert.assertEquals(expected.analysisFingerprint(), actual.analysisFingerprint());
		Assert.assertEquals(expected.occurrences().size(), actual.occurrences().size());
		for(int i = 0; i < expected.occurrences().size(); i++) {
			OccurrenceState left = expected.occurrences().get(i), right = actual.occurrences().get(i);
			Assert.assertSame(left.occurrence(), right.occurrence());
			Assert.assertSame(left.key(), right.key());
			Assert.assertSame(left.hop(), right.hop());
			Assert.assertEquals(left.ordinal(), right.ordinal());
			Assert.assertEquals(left.signature(), right.signature());
		}
		Assert.assertEquals(expected.hops().size(), actual.hops().size());
		for(int i = 0; i < expected.hops().size(); i++) {
			HopState left = expected.hops().get(i), right = actual.hops().get(i);
			Assert.assertSame(left.hop(), right.hop());
			Assert.assertEquals(left.hopId(), right.hopId());
			Assert.assertSame(left.execType(), right.execType());
			Assert.assertSame(left.output(), right.output());
			Assert.assertEquals(left.requiresRecompile(), right.requiresRecompile());
			assertIdentityList(left.inputs(), right.inputs(), "Hop inputs");
			assertIdentityList(left.parents(), right.parents(), "Hop parents");
		}
		assertIdentityList(expected.nodes(), actual.nodes(), "nodes");
		assertIdentityList(expected.constraints(), actual.constraints(), "constraints");
		Assert.assertEquals(expected.exclusions().size(), actual.exclusions().size());
		for(int i = 0; i < expected.exclusions().size(); i++) {
			Assert.assertSame(expected.exclusions().get(i).owner(), actual.exclusions().get(i).owner());
			Assert.assertSame(expected.exclusions().get(i).exclusion(), actual.exclusions().get(i).exclusion());
		}
		Assert.assertSame(expected.memo(), actual.memo());
		Assert.assertEquals(expected.plans().size(), actual.plans().size());
		for(int i = 0; i < expected.plans().size(); i++)
			assertPlanSame(expected.plans().get(i), actual.plans().get(i));
		Assert.assertEquals(expected.memoEntries().size(), actual.memoEntries().size());
		for(int i = 0; i < expected.memoEntries().size(); i++) {
			MemoEntryState left = expected.memoEntries().get(i), right = actual.memoEntries().get(i);
			Assert.assertSame(left.occurrence(), right.occurrence());
			Assert.assertSame(left.variants(), right.variants());
			assertIdentityList(left.variantOrder(), right.variantOrder(), "memo variant order");
			Assert.assertSame(left.selected(), right.selected());
		}
		Assert.assertEquals(expected.memoPresence(), actual.memoPresence());
		Assert.assertEquals(expected.counters(), actual.counters());
	}

	private static void assertPlanSame(PlanState expected, PlanState actual) {
		Assert.assertSame(expected.plan(), actual.plan());
		Assert.assertEquals(expected.cumulativeBits(), actual.cumulativeBits());
		Assert.assertEquals(expected.selfBits(), actual.selfBits());
		Assert.assertEquals(expected.forwardingBits(), actual.forwardingBits());
		Assert.assertSame(expected.execType(), actual.execType());
		Assert.assertSame(expected.fType(), actual.fType());
		Assert.assertEquals(expected.childEdges(), actual.childEdges());
		assertIdentityList(expected.childEdges(), actual.childEdges(), "plan child edges");
	}

	private static void assertIdentityList(List<?> expected, List<?> actual, String label) {
		Assert.assertEquals(label, expected.size(), actual.size());
		for(int i = 0; i < expected.size(); i++)
			Assert.assertSame(label + '[' + i + ']', expected.get(i), actual.get(i));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> values, String label) {
		try {
			((List) values).add(null);
			Assert.fail("mutable " + label);
		}
		catch(UnsupportedOperationException expected) {
			// Receipt collections must preserve exact analysis order without writable aliases.
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(Map<?, ?> values, String label) {
		try {
			((Map) values).put(null, null);
			Assert.fail("mutable " + label);
		}
		catch(UnsupportedOperationException expected) {
			// Receipt maps must be immutable after all owner checks complete.
		}
	}

	private static long bits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis, HopOccurrenceProjection root,
		List<HopOccurrenceProjection> children) { }
	private record ExpectedClone(HopOccurrenceProjection originOccurrence,
		HopOccurrenceProjection cloneOccurrence, Node originNode, Node cloneNode, Constraint sameOrigin,
		Exclusion exclusion) { }
	private record PlanGraph(FederatedPlannerDpMemoTable memo, FedPlan rootPlan, List<FedPlan> childPlans) { }
	private record OccurrenceState(HopOccurrenceProjection occurrence,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key, Hop hop, int ordinal,
		String signature) { }
	private record HopState(Hop hop, long hopId, ExecType execType, FederatedOutput output,
		boolean requiresRecompile, List<Hop> inputs, List<Hop> parents) { }
	private record OwnedExclusion(Node owner, Exclusion exclusion) { }
	private record PlanState(FedPlan plan, long cumulativeBits, long selfBits, long forwardingBits,
		ExecType execType, FType fType, List<Pair<Long, FederatedOutput>> childEdges) { }
	private record MemoEntryState(HopOccurrenceProjection occurrence, FedPlanVariants variants,
		List<FedPlan> variantOrder, FedPlan selected) { }
	private record MemoPresence(long hopId, FederatedOutput output, boolean present) { }
	private record Counters(int occurrenceCount, int nodeCount, int constraintCount, int exclusionCount,
		int cloneCount, int presentMemoCoordinateCount, int planCount) { }
	private record Snapshot(PlacementAnalysis analysis, DMLProgram program, String programFingerprint,
		String analysisFingerprint, List<OccurrenceState> occurrences, List<HopState> hops, List<Node> nodes,
		List<Constraint> constraints, List<OwnedExclusion> exclusions, FederatedPlannerDpMemoTable memo,
		List<PlanState> plans, List<MemoEntryState> memoEntries, List<MemoPresence> memoPresence,
		Counters counters) { }
}
