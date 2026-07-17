/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AdditionalRootInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.AppliedPlanReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.DpInvocationReceipt;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased.InvocationCounters;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlanVariants;
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
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ExactSelection;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Compile-time RED for a direct, analysis-owned, mutation-free DP rewire projection. */
public class CampaignBDpRewireOwnerContractTest {
	@Test
	public void b09ReturnsOneExactCloneAssociationWithoutChangingTheRealDpReceipt() {
		RealDp real = realDp("B-09");
		Claims claims = exactClaims(real.fixture().analysis());
		Snapshot before = snapshot(real);

		RewireReceipt receipt = inspect(real, claims);

		Assert.assertSame(real.fixture().analysis(), receipt.analysis());
		Assert.assertSame(real.fixture().program(), receipt.program());
		Assert.assertEquals(real.fixture().analysis().analysisFingerprint(), receipt.analysisFingerprint());
		assertIdentityList(real.fixture().analysis().occurrences(), receipt.orderedOccurrences(), "occurrences");
		Assert.assertEquals(real.fixture().analysis().graph().normalizedIdentities(),
			receipt.orderedNormalizedIdentities());
		Assert.assertEquals("exact B-09 clone claim multiplicity", 1, claims.cloneAssociations().size());
		Assert.assertEquals(1, receipt.cloneReceipts().size());
		Assert.assertSame("exact request association", claims.cloneAssociations().get(0),
			receipt.cloneReceipts().get(0));
		assertClone(claims.cloneAssociations().get(0));
		Assert.assertEquals(Map.of(claims.cloneAssociations().get(0).cloneOccurrence().hop().getHopID(),
			claims.cloneAssociations().get(0).originOccurrence().hop().getHopID()), receipt.cloneToOrig());
		Assert.assertTrue("neutral B-09 has no request-injectable legacy additional root",
			receipt.orderedAdditionalRoots().isEmpty());
		assertImmutable(receipt.cloneReceipts(), "clone receipts");
		assertImmutable(receipt.orderedOccurrences(), "ordered occurrences");
		assertImmutable(receipt.orderedAdditionalRoots(), "additional roots");
		assertImmutable(receipt.orderedNormalizedIdentities(), "normalized identities");
		assertImmutable(receipt.cloneToOrig(), "clone/original map");
		assertSnapshotSame(before, snapshot(real));
	}

	@Test
	public void b05HasNoCloneClaimsAndPreservesTheRealDpReceipt() {
		RealDp real = realDp("B-05");
		Claims claims = exactClaims(real.fixture().analysis());
		Snapshot before = snapshot(real);

		RewireReceipt receipt = inspect(real, claims);

		Assert.assertTrue(claims.cloneAssociations().isEmpty());
		Assert.assertTrue(receipt.cloneReceipts().isEmpty());
		Assert.assertTrue(receipt.cloneToOrig().isEmpty());
		Assert.assertTrue(receipt.orderedAdditionalRoots().isEmpty());
		assertIdentityList(real.fixture().analysis().occurrences(), receipt.orderedOccurrences(), "B-05 originals");
		Assert.assertFalse(real.fixture().analysis().graph().nodes().stream()
			.anyMatch(node -> node.kind() == NodeKind.CLONE));
		Assert.assertFalse(real.fixture().analysis().graph().nodes().stream()
			.flatMap(node -> node.exclusions().stream())
			.anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT));
		assertSnapshotSame(before, snapshot(real));
	}

	@Test
	public void occurrenceAnalysisAndProgramSubstitutionsRejectBeforeMutation() {
		RealDp owner = realDp("B-09");
		Claims claims = exactClaims(owner.fixture().analysis());
		Snapshot before = snapshot(owner);

		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			new ArrayList<>(owner.fixture().analysis().occurrences()), claims.cloneAssociations(),
			claims.additionalRoots()));
		List<HopOccurrenceProjection> copiedOccurrence = new ArrayList<>(owner.fixture().analysis().occurrences());
		HopOccurrenceProjection first = copiedOccurrence.get(0);
		copiedOccurrence.set(0, new HopOccurrenceProjection(first.key(), first.hop(), first.normalizedOrdinal(),
			first.normalizedSignature()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			copiedOccurrence, claims.cloneAssociations(), claims.additionalRoots()));
		List<HopOccurrenceProjection> reordered = new ArrayList<>(owner.fixture().analysis().occurrences());
		java.util.Collections.swap(reordered, 0, 1);
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			reordered, claims.cloneAssociations(), claims.additionalRoots()));

		Fixture alternateSameFixture = fixture("B-09");
		Claims alternateClaims = exactClaims(alternateSameFixture.analysis());
		expectReject(owner, before, new RewireRequest(alternateSameFixture.analysis(), owner.fixture().program(),
			alternateSameFixture.analysis().occurrences(), alternateClaims.cloneAssociations(),
			alternateClaims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), alternateSameFixture.program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), claims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			alternateSameFixture.analysis().occurrences(), alternateClaims.cloneAssociations(),
			alternateClaims.additionalRoots()));

		expectReject(owner, before, new RewireRequest(null, owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), claims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), null,
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), claims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(), null,
			claims.cloneAssociations(), claims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), null, claims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), null));
	}

	@Test
	public void copiedDetachedSwappedAndForeignAssociationsRejectBeforeMutation() {
		RealDp owner = realDp("B-09");
		Claims claims = exactClaims(owner.fixture().analysis());
		CloneReceipt exact = claims.cloneAssociations().get(0);
		Snapshot before = snapshot(owner);
		Fixture foreign = fixture("B-09");
		CloneReceipt foreignClaim = exactClaims(foreign.analysis()).cloneAssociations().get(0);

		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), List.of(), claims.additionalRoots()));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), List.of(exact, exact), claims.additionalRoots()));

		Node detachedOrigin = copy(exact.originNode());
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), detachedOrigin, exact.cloneNode(), exact.sameOrigin(),
			exact.recompileCpFoutExclusion()));
		Node sameKeyCloneCopy = copy(exact.cloneNode());
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), exact.originNode(), sameKeyCloneCopy, exact.sameOrigin(),
			exact.recompileCpFoutExclusion()));

		Constraint copiedConstraint = copy(exact.sameOrigin());
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), exact.originNode(), exact.cloneNode(), copiedConstraint,
			exact.recompileCpFoutExclusion()));
		Constraint swappedConstraint = new Constraint(exact.sameOrigin().kind(), exact.sameOrigin().right(),
			exact.sameOrigin().left(), exact.sameOrigin().inputPosition(), exact.sameOrigin().evidence());
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), exact.originNode(), exact.cloneNode(), swappedConstraint,
			exact.recompileCpFoutExclusion()));
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), exact.originNode(), exact.cloneNode(), foreignClaim.sameOrigin(),
			exact.recompileCpFoutExclusion()));

		Exclusion copiedExclusion = copy(exact.recompileCpFoutExclusion());
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), exact.originNode(), exact.cloneNode(), exact.sameOrigin(), copiedExclusion));
		expectAssociationReject(owner, before, claims, new CloneReceipt(exact.originOccurrence(),
			exact.cloneOccurrence(), exact.originNode(), exact.cloneNode(), exact.sameOrigin(),
			foreignClaim.recompileCpFoutExclusion()));

		HopOccurrenceProjection wrongOrigin = owner.fixture().analysis().occurrences().stream()
			.filter(item -> item != exact.originOccurrence() && item != exact.cloneOccurrence()).findFirst().orElseThrow();
		Node wrongOriginNode = owner.fixture().analysis().graph().node(wrongOrigin.key()).orElseThrow();
		expectAssociationReject(owner, before, claims, new CloneReceipt(wrongOrigin, exact.cloneOccurrence(),
			wrongOriginNode, exact.cloneNode(), exact.sameOrigin(), exact.recompileCpFoutExclusion()));
	}

	@Test
	public void b05ContaminationAndAdditionalRootInjectionRejectBeforeMutation() {
		RealDp b05 = realDp("B-05");
		Claims clean = exactClaims(b05.fixture().analysis());
		Snapshot before = snapshot(b05);
		Fixture b09 = fixture("B-09");
		CloneReceipt b09Association = exactClaims(b09.analysis()).cloneAssociations().get(0);

		expectReject(b05, before, new RewireRequest(b05.fixture().analysis(), b05.fixture().program(),
			b05.fixture().analysis().occurrences(), List.of(b09Association), clean.additionalRoots()));
		HopOccurrenceProjection substitutedRoot = b05.fixture().analysis().occurrences().get(0);
		expectReject(b05, before, new RewireRequest(b05.fixture().analysis(), b05.fixture().program(),
			b05.fixture().analysis().occurrences(), clean.cloneAssociations(), List.of(substitutedRoot)));
	}

	@Test
	public void b09AdditionalRootInjectionVariantsRejectBeforeMutation() {
		RealDp owner = realDp("B-09");
		Claims claims = exactClaims(owner.fixture().analysis());
		Snapshot before = snapshot(owner);
		HopOccurrenceProjection substitutedRoot = owner.fixture().analysis().occurrences().get(0);

		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), List.of(substitutedRoot)));
		HopOccurrenceProjection copiedRoot = new HopOccurrenceProjection(substitutedRoot.key(),
			substitutedRoot.hop(), substitutedRoot.normalizedOrdinal(), substitutedRoot.normalizedSignature());
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), List.of(copiedRoot)));
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(),
			List.of(substitutedRoot, substitutedRoot)));
		List<HopOccurrenceProjection> reorderedRoots = List.of(owner.fixture().analysis().occurrences().get(1),
			substitutedRoot);
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), reorderedRoots));
		Fixture foreign = fixture("B-09");
		HopOccurrenceProjection foreignRoot = foreign.analysis().occurrences().get(0);
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), claims.cloneAssociations(), List.of(foreignRoot)));
	}

	private static RewireReceipt inspect(RealDp real, Claims claims) {
		return FederatedPlannerDpRewireTransTable.inspectExact(new RewireRequest(real.fixture().analysis(),
			real.fixture().program(), real.fixture().analysis().occurrences(), claims.cloneAssociations(),
			claims.additionalRoots()));
	}

	private static void expectAssociationReject(RealDp owner, Snapshot before, Claims claims,
		CloneReceipt replacement) {
		expectReject(owner, before, new RewireRequest(owner.fixture().analysis(), owner.fixture().program(),
			owner.fixture().analysis().occurrences(), List.of(replacement), claims.additionalRoots()));
	}

	private static void expectReject(RealDp owner, Snapshot before, RewireRequest request) {
		try {
			FederatedPlannerDpRewireTransTable.inspectExact(request);
			Assert.fail("accepted copied, foreign, reordered, contaminated, or substituted rewire evidence");
		}
		catch(IllegalArgumentException expected) {
			// Identity and association validation must finish before any owner mutation.
		}
		assertSnapshotSame(before, snapshot(owner));
	}

	private static Claims exactClaims(PlacementAnalysis analysis) {
		List<Node> clones = analysis.graph().nodes().stream().filter(node -> node.kind() == NodeKind.CLONE).toList();
		Assert.assertTrue("fixture has more than one clone", clones.size() <= 1);
		if(clones.isEmpty())
			return new Claims(List.of(), List.of());
		Node clone = clones.get(0);
		List<Constraint> associations = analysis.graph().constraints().stream()
			.filter(item -> item.kind() == ConstraintKind.SAME_ORIGIN)
			.filter(item -> item.left().equals(clone.key()) || item.right().equals(clone.key())).toList();
		Assert.assertEquals("exact SAME_ORIGIN multiplicity", 1, associations.size());
		Constraint association = associations.get(0);
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey originKey =
			association.left().equals(clone.key()) ? association.right() : association.left();
		Node origin = analysis.graph().node(originKey).orElseThrow();
		Assert.assertEquals("canonical source origin", origin.key().canonicalSourceOrigin(),
			clone.key().canonicalSourceOrigin());
		List<Exclusion> exclusions = clone.exclusions().stream()
			.filter(item -> item.reasonCode() == ReasonCode.RECOMPILE_CP_FOUT).toList();
		Assert.assertEquals("exact clone-owned recompile exclusion multiplicity", 1, exclusions.size());
		CloneReceipt claim = new CloneReceipt(occurrence(analysis, origin), occurrence(analysis, clone),
			origin, clone, association, exclusions.get(0));
		assertClone(claim);
		return new Claims(List.of(claim), List.of());
	}

	private static void assertClone(CloneReceipt claim) {
		Assert.assertEquals(NodeKind.CLONE, claim.cloneNode().kind());
		Assert.assertEquals("CLONE_RECOMPILE", claim.cloneNode().valueVersion().versionKind().name());
		Assert.assertEquals("recompile", claim.cloneNode().key().recompileContext());
		Assert.assertEquals(ConstraintKind.SAME_ORIGIN, claim.sameOrigin().kind());
		Assert.assertEquals(ReasonCode.RECOMPILE_CP_FOUT, claim.recompileCpFoutExclusion().reasonCode());
		Assert.assertEquals(claim.originNode().key().canonicalSourceOrigin(),
			claim.cloneNode().key().canonicalSourceOrigin());
	}

	private static HopOccurrenceProjection occurrence(PlacementAnalysis analysis, Node node) {
		List<HopOccurrenceProjection> matches = analysis.occurrences().stream()
			.filter(item -> item.key().equals(node.key())).toList();
		Assert.assertEquals("exact occurrence multiplicity", 1, matches.size());
		return matches.get(0);
	}

	private static Node copy(Node node) {
		return new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(), node.legalAlternatives(),
			node.exclusions(), node.anchors());
	}

	private static Constraint copy(Constraint constraint) {
		return new Constraint(constraint.kind(), constraint.left(), constraint.right(), constraint.inputPosition(),
			constraint.evidence());
	}

	private static Exclusion copy(Exclusion exclusion) {
		return new Exclusion(exclusion.state(), exclusion.reasonCode(), exclusion.detail());
	}

	private static RealDp realDp(String id) {
		Fixture fixture = fixture(id);
		DpInvocationReceipt receipt = new FederatedPlannerDpFedCostBased().rewriteProgram(fixture.program(),
			new FunctionCallGraph(fixture.program()), null, fixture.analysis());
		Assert.assertSame(fixture.analysis(), receipt.analysis());
		Assert.assertEquals(new InvocationCounters(1, 1, 1, receipt.appliedPlans().size(),
			receipt.additionalRootInvocations().size(),
			(int) receipt.additionalRootInvocations().stream()
				.filter(item -> item.disposition() == FederatedPlannerDpFedCostBased.AdditionalRootDisposition.ALREADY_VISITED)
				.count(), 0, 0, 0, 0, 0, 0), receipt.counters());
		return new RealDp(fixture, receipt);
	}

	private static Fixture fixture(String id) {
		try {
			DMLProgram program = ProductionShadowFixtureFactory.compile(id);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			return new Fixture(program, analysis);
		}
		catch(Exception e) {
			throw new AssertionError("Unable to compile DP rewire fixture " + id, e);
		}
	}

	private static Snapshot snapshot(RealDp real) {
		PlacementAnalysis analysis = real.fixture().analysis();
		DpInvocationReceipt receipt = real.receipt();
		List<OccurrenceState> occurrences = analysis.occurrences().stream()
			.map(item -> new OccurrenceState(item, item.key(), item.hop(), item.normalizedOrdinal(),
				item.normalizedSignature())).toList();
		List<HopState> hops = analysis.occurrences().stream().map(HopOccurrenceProjection::hop).distinct()
			.map(CampaignBDpRewireOwnerContractTest::snapshotHop).toList();
		List<OwnedExclusion> exclusions = new ArrayList<>();
		for(Node node : analysis.graph().nodes())
			for(Exclusion exclusion : node.exclusions())
				exclusions.add(new OwnedExclusion(node, exclusion));
		FedPlan aggregate = receipt.legacyOptimalPlan();
		AggregateState aggregateState = snapshotAggregate(aggregate);
		List<FedPlan> selectedRootPlans = List.copyOf(receipt.exactSelection().selectedRootPlans());
		List<MemoCoordinateState> memoCoordinates = memoCoordinates(analysis, receipt.memo(),
			aggregateState.childEdgeSnapshot(), selectedRootPlans);
		return new Snapshot(analysis, real.fixture().program(), PlacementGraphFingerprint.capture(real.fixture().program()),
			analysis.analysisFingerprint(), List.copyOf(occurrences), List.copyOf(hops), analysis.graph().nodes(),
			analysis.graph().constraints(), List.copyOf(exclusions), receipt, receipt.memo(),
			aggregate, aggregateState, receipt.exactSelection(),
			List.copyOf(receipt.exactSelection().aggregateChildEdges()),
			selectedRootPlans,
			List.copyOf(receipt.exactSelection().selectedRootHops()), List.copyOf(receipt.appliedPlans()),
			List.copyOf(receipt.additionalRootInvocations()), receipt.counters(), memoCoordinates,
			List.copyOf(receipt.memo().getAdditionalRootHopIDs()));
	}

	private static List<MemoCoordinateState> memoCoordinates(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, List<Pair<Long, FederatedOutput>> aggregateEdges,
		List<FedPlan> selectedRootPlans) {
		Set<Long> ids = new LinkedHashSet<>();
		analysis.occurrences().forEach(item -> ids.add(item.hop().getHopID()));
		ids.addAll(memo.getAdditionalRootHopIDs());
		aggregateEdges.forEach(edge -> ids.add(edge.getLeft()));
		Set<FedPlan> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<FedPlan> queue = new ArrayDeque<>();
		queue.addAll(selectedRootPlans);
		while(!queue.isEmpty()) {
			FedPlan plan = queue.removeFirst();
			if(!seen.add(plan)) continue;
			ids.add(plan.getHopID());
			for(Pair<Long, FederatedOutput> edge : plan.getChildFedPlans()) {
				ids.add(edge.getLeft());
				FedPlan child = memo.getFedPlanAfterPrune(edge);
				if(child != null) queue.add(child);
			}
		}
		List<MemoCoordinateState> result = new ArrayList<>();
		for(long id : ids)
			for(FederatedOutput output : FederatedOutput.values()) {
				boolean present = memo.contains(id, output);
				FedPlanVariants variants = present ? memo.getFedPlanVariants(Pair.of(id, output)) : null;
				List<PlanState> plans = variants == null ? List.of() : variants.getFedPlanVariants().stream()
					.map(CampaignBDpRewireOwnerContractTest::snapshotPlan).toList();
				result.add(new MemoCoordinateState(id, output, present, variants,
					variants == null ? List.of() : List.copyOf(variants.getFedPlanVariants()),
					memo.getFedPlanAfterPrune(id, output), plans));
			}
		return List.copyOf(result);
	}

	private static HopState snapshotHop(Hop hop) {
		return new HopState(hop, hop.getHopID(), hop.getForcedExecType(), hop.getFederatedOutput(),
			hop.requiresRecompile(), List.copyOf(hop.getInput()), List.copyOf(hop.getParent()));
	}

	private static AggregateState snapshotAggregate(FedPlan aggregate) {
		List<Pair<Long, FederatedOutput>> childEdgeCarrier = aggregate.getChildFedPlans();
		return new AggregateState(aggregate, childEdgeCarrier,
			Double.doubleToRawLongBits(aggregate.getCumulativeCost()), List.copyOf(childEdgeCarrier));
	}

	private static PlanState snapshotPlan(FedPlan plan) {
		return new PlanState(plan, plan.getHopRef(), Double.doubleToRawLongBits(plan.getCumulativeCost()),
			Double.doubleToRawLongBits(plan.getSelfCost()), Double.doubleToRawLongBits(plan.getForwardingCost()),
			plan.getExecType(), plan.getFType(), List.copyOf(plan.getChildFedPlans()));
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
		for(int i = 0; i < expected.hops().size(); i++) assertHopSame(expected.hops().get(i), actual.hops().get(i));
		assertIdentityList(expected.nodes(), actual.nodes(), "nodes");
		assertIdentityList(expected.constraints(), actual.constraints(), "constraints");
		Assert.assertEquals(expected.exclusions().size(), actual.exclusions().size());
		for(int i = 0; i < expected.exclusions().size(); i++) {
			Assert.assertSame(expected.exclusions().get(i).owner(), actual.exclusions().get(i).owner());
			Assert.assertSame(expected.exclusions().get(i).exclusion(), actual.exclusions().get(i).exclusion());
		}
		Assert.assertSame(expected.receipt(), actual.receipt());
		Assert.assertSame(expected.memo(), actual.memo());
		Assert.assertSame(expected.aggregate(), actual.aggregate());
		Assert.assertSame(expected.exactSelection(), actual.exactSelection());
		assertAggregateSame(expected, actual);
		assertIdentityList(expected.aggregateEdges(), actual.aggregateEdges(), "aggregate edges");
		assertIdentityList(expected.selectedRootPlans(), actual.selectedRootPlans(), "selected root plans");
		assertIdentityList(expected.selectedRootHops(), actual.selectedRootHops(), "selected root Hops");
		assertIdentityList(expected.appliedPlans(), actual.appliedPlans(), "applied plan receipts");
		assertIdentityList(expected.additionalRootInvocations(), actual.additionalRootInvocations(),
			"additional-root invocation receipts");
		Assert.assertEquals(expected.counters(), actual.counters());
		Assert.assertEquals(expected.memoCoordinates().size(), actual.memoCoordinates().size());
		for(int i = 0; i < expected.memoCoordinates().size(); i++)
			assertMemoSame(expected.memoCoordinates().get(i), actual.memoCoordinates().get(i));
		Assert.assertEquals(expected.additionalRootIds(), actual.additionalRootIds());
	}

	private static void assertAggregateSame(Snapshot expected, Snapshot actual) {
		Assert.assertSame(expected.aggregate(), expected.aggregateState().aggregate());
		Assert.assertSame(actual.aggregate(), actual.aggregateState().aggregate());
		Assert.assertSame(expected.aggregate(), expected.exactSelection().legacyOptimalPlan());
		Assert.assertSame(actual.aggregate(), actual.exactSelection().legacyOptimalPlan());
		Assert.assertSame(expected.aggregateState().childEdgeCarrier(), actual.aggregateState().childEdgeCarrier());
		Assert.assertEquals(expected.aggregateState().cumulativeBits(), actual.aggregateState().cumulativeBits());
		assertIdentityList(expected.aggregateState().childEdgeSnapshot(),
			actual.aggregateState().childEdgeSnapshot(), "aggregate carrier edges");
		assertIdentityList(expected.aggregateState().childEdgeSnapshot(), expected.aggregateEdges(),
			"expected exact-selection edge copy");
		assertIdentityList(actual.aggregateState().childEdgeSnapshot(), actual.aggregateEdges(),
			"actual exact-selection edge copy");
		for(FedPlan selected : expected.selectedRootPlans())
			Assert.assertNotSame("synthetic aggregate is not a selected concrete plan", expected.aggregate(), selected);
		for(FedPlan selected : actual.selectedRootPlans())
			Assert.assertNotSame("synthetic aggregate is not a selected concrete plan", actual.aggregate(), selected);
	}

	private static void assertHopSame(HopState expected, HopState actual) {
		Assert.assertSame(expected.hop(), actual.hop());
		Assert.assertEquals(expected.hopId(), actual.hopId());
		Assert.assertSame(expected.execType(), actual.execType());
		Assert.assertSame(expected.output(), actual.output());
		Assert.assertEquals(expected.requiresRecompile(), actual.requiresRecompile());
		assertIdentityList(expected.inputs(), actual.inputs(), "Hop inputs");
		assertIdentityList(expected.parents(), actual.parents(), "Hop parents");
	}

	private static void assertMemoSame(MemoCoordinateState expected, MemoCoordinateState actual) {
		Assert.assertEquals(expected.hopId(), actual.hopId());
		Assert.assertSame(expected.output(), actual.output());
		Assert.assertEquals(expected.present(), actual.present());
		Assert.assertSame(expected.variants(), actual.variants());
		assertIdentityList(expected.variantOrder(), actual.variantOrder(), "memo variant order");
		Assert.assertSame(expected.selected(), actual.selected());
		Assert.assertEquals(expected.plans().size(), actual.plans().size());
		for(int i = 0; i < expected.plans().size(); i++)
			assertPlanSame(expected.plans().get(i), actual.plans().get(i));
	}

	private static void assertPlanSame(PlanState expected, PlanState actual) {
		Assert.assertSame(expected.plan(), actual.plan());
		Assert.assertSame(expected.hop(), actual.hop());
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
		try { ((List) values).add(null); Assert.fail("mutable " + label); }
		catch(UnsupportedOperationException expected) { }
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(Map<?, ?> values, String label) {
		try { ((Map) values).put(null, null); Assert.fail("mutable " + label); }
		catch(UnsupportedOperationException expected) { }
	}

	private record Fixture(DMLProgram program, PlacementAnalysis analysis) { }
	private record RealDp(Fixture fixture, DpInvocationReceipt receipt) { }
	private record Claims(List<CloneReceipt> cloneAssociations, List<HopOccurrenceProjection> additionalRoots) { }
	private record OccurrenceState(HopOccurrenceProjection occurrence,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey key, Hop hop, int ordinal,
		String signature) { }
	private record HopState(Hop hop, long hopId, org.apache.sysds.common.Types.ExecType execType,
		FederatedOutput output, boolean requiresRecompile, List<Hop> inputs, List<Hop> parents) { }
	private record OwnedExclusion(Node owner, Exclusion exclusion) { }
	private record AggregateState(FedPlan aggregate, List<Pair<Long, FederatedOutput>> childEdgeCarrier,
		long cumulativeBits, List<Pair<Long, FederatedOutput>> childEdgeSnapshot) { }
	private record PlanState(FedPlan plan, Hop hop, long cumulativeBits, long selfBits, long forwardingBits,
		org.apache.sysds.common.Types.ExecType execType, org.apache.sysds.hops.fedplanner.FTypes.FType fType,
		List<Pair<Long, FederatedOutput>> childEdges) { }
	private record MemoCoordinateState(long hopId, FederatedOutput output, boolean present,
		FedPlanVariants variants, List<FedPlan> variantOrder, FedPlan selected, List<PlanState> plans) { }
	private record Snapshot(PlacementAnalysis analysis, DMLProgram program, String programFingerprint,
		String analysisFingerprint, List<OccurrenceState> occurrences, List<HopState> hops, List<Node> nodes,
		List<Constraint> constraints, List<OwnedExclusion> exclusions, DpInvocationReceipt receipt,
		FederatedPlannerDpMemoTable memo, FedPlan aggregate, AggregateState aggregateState,
		ExactSelection exactSelection, List<Pair<Long, FederatedOutput>> aggregateEdges,
		List<FedPlan> selectedRootPlans, List<Hop> selectedRootHops, List<AppliedPlanReceipt> appliedPlans,
		List<AdditionalRootInvocationReceipt> additionalRootInvocations, InvocationCounters counters,
		List<MemoCoordinateState> memoCoordinates, List<Long> additionalRootIds) { }
}
