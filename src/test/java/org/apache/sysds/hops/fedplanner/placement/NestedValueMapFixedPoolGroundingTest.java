/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NestedValueMapFixedPoolGroundingTest {
	private static final PlacementState FED_FOUT =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(FED_FOUT, false);

	@Test
	public void nestedBranchMapPassedThroughFunctionArgumentKeepsOnePool() {
		Fixture fixture = fixture(false);
		Assert.assertTrue(fixture.grounding().matchesFixedPool(
			fixture.selectedNestedClause(), fixture.expectedPool(), false));
		Assert.assertEquals("clause-independent nested pools must remain invariant-memoized",
			1, fixture.grounding().supportOwners().size());
	}

	@Test
	public void oneNestedBranchLeafOnAnotherPoolRejectsTheFunctionArgument() {
		Fixture fixture = fixture(true);
		Assert.assertFalse(fixture.grounding().matchesFixedPool(
			fixture.selectedNestedClause(), fixture.expectedPool(), false));
		Assert.assertEquals("only clause-dependent nested owners must enter the exact factor scope",
			3, fixture.grounding().supportOwners().size());
	}

	@Test
	public void unselectedSamePoolClauseCannotAuthorizeSelectedMismatchedNestedClause() {
		Fixture fixture = fixture(true);
		Assert.assertFalse(fixture.grounding().matchesFixedPool(
			fixture.selectedNestedClause(), fixture.expectedPool(), false));
		Assert.assertTrue("control clause must independently name the expected-pool branch",
			fixture.grounding().matchesFixedPool(
				fixture.selectedDirectClause(), fixture.expectedPool(), false));
	}

	@Test
	public void partialSearchDefersOnlyTheMissingSelectedNestedOwner() {
		Fixture fixture = fixture(true);
		Map<CompiledHopKey,CandidateSelectionReceipt> partial = Map.of(fixture.owner(),
			fixture.selectedNestedClause().get(fixture.owner()));
		Assert.assertTrue(fixture.grounding().matchesFixedPool(partial, fixture.expectedPool(), true));
		Assert.assertFalse(fixture.grounding().matchesFixedPool(partial, fixture.expectedPool(), false));
	}

	@Test
	public void selectedSelfAliasCycleIsGroundedByItsSamePoolLeaf() {
		CycleFixture fixture = cycleFixture(false, true, false);
		Assert.assertTrue(fixture.grounding().matchesFixedPool(
			fixture.selected(), fixture.expectedPool(), false));
	}

	@Test
	public void selectedMutualAliasCycleIsGroundedByItsSamePoolLeaf() {
		CycleFixture fixture = cycleFixture(true, true, false);
		Assert.assertTrue(fixture.grounding().matchesFixedPool(
			fixture.selected(), fixture.expectedPool(), false));
	}

	@Test
	public void groundedAliasCycleRejectsADifferentLeafPool() {
		CycleFixture fixture = cycleFixture(true, true, true);
		Assert.assertFalse(fixture.grounding().matchesFixedPool(
			fixture.selected(), fixture.expectedPool(), false));
	}

	@Test
	public void selectedAliasCycleWithoutAStaticLeafCannotGroundItself() {
		CycleFixture self = cycleFixture(false, false, false);
		Assert.assertFalse(self.grounding().matchesFixedPool(
			self.selected(), self.expectedPool(), false));
		CycleFixture mutual = cycleFixture(true, false, false);
		Assert.assertFalse(mutual.grounding().matchesFixedPool(
			mutual.selected(), mutual.expectedPool(), false));
	}

	@Test
	public void groundedSiblingCannotAuthorizeASeparateUngroundedCycle() {
		CycleFixture fixture = cycleFixture(true, true, false, false);
		Assert.assertFalse(fixture.grounding().matchesFixedPool(
			fixture.selected(), fixture.expectedPool(), false));
	}

	private record Fixture(JointValueMapRelations.Grounding grounding,
		DurableAnchorKey expectedPool,
		CompiledHopKey owner,
		Map<CompiledHopKey,CandidateSelectionReceipt> selectedNestedClause,
		Map<CompiledHopKey,CandidateSelectionReceipt> selectedDirectClause) { }

	private record CycleFixture(JointValueMapRelations.Grounding grounding,
		DurableAnchorKey expectedPool,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected) { }

	private static CycleFixture cycleFixture(boolean mutual, boolean includeLeaf,
		boolean differentLeafPool) {
		return cycleFixture(mutual, includeLeaf, differentLeafPool, true);
	}

	private static CycleFixture cycleFixture(boolean mutual, boolean includeLeaf,
		boolean differentLeafPool, boolean peerBackToOwner) {
		Node owner = node("cycle-owner", NodeKind.LOOP_PHI, 10);
		Node peer = node("cycle-peer", NodeKind.TRANSIENT_READ, 11);
		Node leaf = node("cycle-leaf", NodeKind.OPERATION, 12);
		DurableAnchorKey expected = pool("worker-a:9000", "worker-b:9000");
		DurableAnchorKey leafPool = differentLeafPool
			? pool("worker-a:9000", "other-worker:9000") : expected;
		CandidateRuleKey ownerRule = rule(owner, 1 + (includeLeaf ? 1 : 0));
		CandidateRuleKey peerRule = rule(peer, 1);
		CandidateRuleKey leafRule = rule(leaf, 0);
		PlacementRealizationKey ownerKey = PlacementRealizationKey.valueMap(
			EMISSION, "selected-cycle-owner");
		PlacementRealizationKey peerKey = PlacementRealizationKey.valueMap(
			EMISSION, "selected-cycle-peer");
		CandidateEmissionRealization leafRealization = durable(leaf, leafPool);
		CandidateRealizationReference ownerReference =
			new CandidateRealizationReference(ownerRule, ownerKey);
		CandidateRealizationReference peerReference =
			new CandidateRealizationReference(peerRule, peerKey);
		CandidateRealizationReference leafReference =
			CandidateRealizationReference.of(leafRule, leafRealization);

		List<CandidateRealizationInputBinding> ownerBindings = new ArrayList<>();
		ownerBindings.add(CandidateRealizationInputBinding.logicalTransient(0,
			mutual ? peerReference : ownerReference));
		if(includeLeaf)
			ownerBindings.add(CandidateRealizationInputBinding.logicalTransient(1, leafReference));
		CandidateRealizationSupportClause ownerClause = new CandidateRealizationSupportClause(
			List.of(), ownerBindings);
		CandidateEmissionRealization ownerRealization = new CandidateEmissionRealization(
			ownerKey, List.of(ownerClause));
		CandidateEmissionFact ownerEmission = emission(ownerRealization);

		List<Node> nodes = new ArrayList<>(List.of(owner));
		List<CandidateRuleFact> facts = new ArrayList<>(List.of(fact(ownerRule, ownerEmission)));
		CandidateEmissionRealization peerRealization = null;
		CandidateEmissionFact peerEmission = null;
		if(mutual) {
			CandidateRealizationSupportClause peerClause = clause(
				CandidateRealizationInputBinding.logicalTransient(0,
					peerBackToOwner ? ownerReference : peerReference));
			peerRealization = new CandidateEmissionRealization(peerKey, List.of(peerClause));
			peerEmission = emission(peerRealization);
			nodes.add(peer);
			facts.add(fact(peerRule, peerEmission));
		}
		if(includeLeaf) {
			nodes.add(leaf);
			facts.add(fact(leafRule, emission(leafRealization)));
		}

		PlacementAnalysis analysis = analysis(nodes, facts);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(owner.key(), analysis.canonicalCandidateReceipt(
			ownerRule, ownerEmission, ownerRealization, ownerClause));
		if(mutual)
			selected.put(peer.key(), analysis.canonicalCandidateReceipt(
				peerRule, peerEmission, peerRealization));
		if(includeLeaf) {
			CandidateEmissionFact leafEmission = facts.get(facts.size() - 1).allowedEmissionFacts().get(0);
			selected.put(leaf.key(), analysis.canonicalCandidateReceipt(
				leafRule, leafEmission, leafRealization));
		}
		return new CycleFixture(JointValueMapRelations.Grounding.fixedPool(
			analysis, owner.key()), expected, Map.copyOf(selected));
	}

	private static Fixture fixture(boolean differentSecondLeafPool) {
		Node a = node("branch-a", NodeKind.OPERATION, 0);
		Node b = node("branch-b", NodeKind.OPERATION, 1);
		Node phi = node("if-phi", NodeKind.BRANCH_JOIN, 2);
		Node formal = node("function-formal", NodeKind.FUNCTION_INPUT, 3);
		Node owner = node("function-body-read", NodeKind.TRANSIENT_READ, 4);
		DurableAnchorKey expected = pool("worker-a:9000", "worker-b:9000");
		DurableAnchorKey second = differentSecondLeafPool
			? pool("worker-a:9000", "other-worker:9000") : expected;

		CandidateRuleKey aRule = rule(a, 0), bRule = rule(b, 0);
		CandidateEmissionRealization aRealization = durable(a, expected);
		CandidateEmissionRealization bRealization = durable(b, second);
		CandidateEmissionFact aEmission = emission(aRealization);
		CandidateEmissionFact bEmission = emission(bRealization);

		CandidateRuleKey phiRule = rule(phi, 2);
		CandidateRealizationSupportClause phiClause = clause(
			CandidateRealizationInputBinding.logicalTransient(0,
				CandidateRealizationReference.of(aRule, aRealization)),
			CandidateRealizationInputBinding.logicalTransient(1,
				CandidateRealizationReference.of(bRule, bRealization)));
		CandidateEmissionRealization phiRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "if-phi-map", List.of(phiClause));
		CandidateEmissionFact phiEmission = emission(phiRealization);

		CandidateRuleKey formalRule = rule(formal, 1);
		CandidateRealizationSupportClause formalClause = clause(
			CandidateRealizationInputBinding.logicalTransient(0,
				CandidateRealizationReference.of(phiRule, phiRealization)));
		CandidateEmissionRealization formalRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "function-argument-map", List.of(formalClause));
		CandidateEmissionFact formalEmission = emission(formalRealization);

		CandidateRuleKey ownerRule = rule(owner, 1);
		CandidateRealizationSupportClause nestedClause = clause(
			CandidateRealizationInputBinding.logicalTransient(0,
				CandidateRealizationReference.of(formalRule, formalRealization)));
		CandidateRealizationSupportClause directSamePoolClause = clause(
			CandidateRealizationInputBinding.logicalTransient(0,
				CandidateRealizationReference.of(aRule, aRealization)));
		CandidateEmissionRealization ownerRealization = CandidateEmissionRealization.valueMap(
			EMISSION, "function-body-value-map", List.of(nestedClause, directSamePoolClause));
		CandidateEmissionFact ownerEmission = emission(ownerRealization);

		List<CandidateRuleFact> facts = List.of(fact(aRule, aEmission), fact(bRule, bEmission),
			fact(phiRule, phiEmission), fact(formalRule, formalEmission), fact(ownerRule, ownerEmission));
		PlacementAnalysis analysis = analysis(List.of(a, b, phi, formal, owner), facts);
		Map<CompiledHopKey,CandidateSelectionReceipt> common = new IdentityHashMap<>();
		common.put(a.key(), analysis.canonicalCandidateReceipt(aRule, aEmission, aRealization));
		common.put(b.key(), analysis.canonicalCandidateReceipt(bRule, bEmission, bRealization));
		common.put(phi.key(), analysis.canonicalCandidateReceipt(phiRule, phiEmission, phiRealization));
		common.put(formal.key(), analysis.canonicalCandidateReceipt(
			formalRule, formalEmission, formalRealization));
		Map<CompiledHopKey,CandidateSelectionReceipt> nested = new IdentityHashMap<>(common);
		nested.put(owner.key(), analysis.canonicalCandidateReceipt(
			ownerRule, ownerEmission, ownerRealization, nestedClause));
		Map<CompiledHopKey,CandidateSelectionReceipt> direct = new IdentityHashMap<>(common);
		direct.put(owner.key(), analysis.canonicalCandidateReceipt(
			ownerRule, ownerEmission, ownerRealization, directSamePoolClause));
		return new Fixture(JointValueMapRelations.Grounding.fixedPool(analysis, owner.key()),
			expected, owner.key(), Map.copyOf(nested), Map.copyOf(direct));
	}

	private static CandidateEmissionRealization durable(Node node, DurableAnchorKey pool) {
		return CandidateEmissionRealization.durable(EMISSION, pool,
			List.of(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
				node.key(), pool.normalizedSignature())), List.of());
	}

	private static CandidateEmissionFact emission(CandidateEmissionRealization realization) {
		return new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(realization));
	}

	private static CandidateRealizationSupportClause clause(
		CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(List.of(), List.of(bindings));
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule, CandidateEmissionFact emission) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "nested-value-map", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "nested-value-map", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), List.of(emission), "");
	}

	private static CandidateRuleKey rule(Node node, int inputs) {
		return new CandidateRuleKey(node.key(),
			java.util.Collections.nCopies(inputs, CandidateInputState.present(FType.ROW)));
	}

	private static Node node(String id, NodeKind kind, int ordinal) {
		ControlRegionKey region = new ControlRegionKey(
			"nested-value-map", "main", List.of("if", "function-call"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("nested-value-map", "main", "root", "compiled",
			region, id + '@' + ordinal, id);
		return new Node(key, kind, new ValueVersionKey("nested-value-map", id, region, ordinal,
			VersionKind.ORDINARY, List.of()), true, List.of(FED_FOUT), List.of(), List.of());
	}

	private static DurableAnchorKey pool(String first, String second) {
		return new DurableAnchorKey("nested-pool", FType.ROW, List.of(
			new AnchorPartition(first, List.of(0L, 0L), List.of(4L, 2L)),
			new AnchorPartition(second, List.of(4L, 0L), List.of(8L, 2L))));
	}

	private static PlacementAnalysis analysis(List<Node> nodes, List<CandidateRuleFact> facts) {
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, List.of(), List.of());
		List<PlacementAnalysis.HopOccurrenceProjection> occurrences = new ArrayList<>();
		Map<CompiledHopKey,PlacementAnalysis.NodeShapeFact> shapes = new LinkedHashMap<>();
		Set<CompiledHopKey> keys = new LinkedHashSet<>();
		for(Node node : nodes) {
			occurrences.add(new PlacementAnalysis.HopOccurrenceProjection(node.key(),
				new LiteralOp(occurrences.size()), -1, occurrences.size(), node.key().normalizedSignature()));
			keys.add(node.key());
			shapes.put(node.key(), new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		}
		return new PlacementAnalysis(graph, occurrences, new DMLProgram(),
			new PlacementShapeFacts(shapes, keys), "nested-value-map-analysis",
			new PlacementAnalysis.HeuristicPolicyFacts(List.of()),
			facts.stream().map(CandidateRuleFact::key).toList(), facts, List.of(), List.of());
	}
}
