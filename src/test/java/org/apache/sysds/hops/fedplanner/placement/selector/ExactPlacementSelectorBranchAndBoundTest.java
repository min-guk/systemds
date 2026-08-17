/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for the production-size exact-search path. */
public class ExactPlacementSelectorBranchAndBoundTest {
	private static final PlacementState LOCAL =
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementState FED =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementState FED_COL =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.COL, false);

	@Test
	public void productionSizeSearchProvesTheExactFedAllMaximumWithoutCartesianExpansion() {
		String fingerprint = "exact-selector-branch-bound";
		List<Node> nodes = new ArrayList<>();
		List<Constraint> constraints = new ArrayList<>();
		for(int i = 0; i < 17; i++) {
			ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb"),
				"main", "compiled");
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
				"hop-" + i, "hop-" + i);
			ValueVersionKey value = new ValueVersionKey(fingerprint, "v" + i, region, i,
				VersionKind.ORDINARY, List.of());
			nodes.add(new Node(key, NodeKind.OPERATION, value, true,
				List.of(LOCAL, FED), List.of(), List.of()));
			if(i > 0)
				constraints.add(new Constraint(ConstraintKind.SAME_PLACEMENT,
					nodes.get(0).key(), key, i, "shared-placement"));
		}

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, constraints, List.of()));

		Assert.assertEquals(17, selection.score().emittedFedCount());
		Assert.assertEquals(17, selection.score().foutCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED::equals));
		Assert.assertEquals(TerminationReason.TIGHT_BOUND_EQUALITY,
			selection.certificate().terminationReason());
		Assert.assertEquals(1, selection.certificate().exploredCount());
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void wideTernaryDomainUsesExactBranchAndBoundBelowTheLegacyNodeThreshold() {
		String fingerprint = "exact-selector-wide-ternary-domain";
		List<Node> nodes = new ArrayList<>();
		for(int i = 0; i < 14; i++) {
			Node node = decisionNode(fingerprint, "hop-" + i, i);
			nodes.add(new Node(node.key(), node.kind(), node.valueVersion(), true,
				List.of(LOCAL, FED, FED_COL), List.of(), List.of()));
		}

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, List.of(), List.of()));

		Assert.assertEquals(14, selection.score().emittedFedCount());
		Assert.assertEquals(14, selection.score().foutCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED_COL::equals));
		Assert.assertEquals("3^14 must not enter complete Cartesian enumeration",
			TerminationReason.TIGHT_BOUND_EQUALITY, selection.certificate().terminationReason());
		Assert.assertTrue("independent exact components must remain bounded",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void samePlacementGroupCanonicalizesEveryMemberToItsOwnStateIdentity() {
		String fingerprint = "exact-selector-node-owned-state";
		Node leftTemplate = decisionNode(fingerprint, "left", 0);
		Node rightTemplate = decisionNode(fingerprint, "right", 1);
		PlacementState leftLocal = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState leftFed = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		PlacementState rightLocal = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState rightFed = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		Node left = new Node(leftTemplate.key(), leftTemplate.kind(), leftTemplate.valueVersion(), true,
			List.of(leftLocal, leftFed), List.of(), List.of());
		Node right = new Node(rightTemplate.key(), rightTemplate.kind(), rightTemplate.valueVersion(), true,
			List.of(rightLocal, rightFed), List.of(), List.of());
		Constraint same = new Constraint(ConstraintKind.SAME_PLACEMENT,
			left.key(), right.key(), 0, "node-owned-state");

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(List.of(left, right), List.of(same), List.of()));

		Assert.assertSame(leftFed, selection.assignment().get(left.key()));
		Assert.assertSame(rightFed, selection.assignment().get(right.key()));
		Assert.assertNotSame(selection.assignment().get(left.key()), selection.assignment().get(right.key()));
	}

	@Test
	public void productionSizeEqualObjectiveTiesUseTheStableSignatureBound() {
		List<Node> nodes = equalObjectiveNodes("exact-selector-equal-ties");

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, List.of(), List.of()));

		Assert.assertEquals(17, selection.score().emittedFedCount());
		Assert.assertEquals(17, selection.score().foutCount());
		Assert.assertEquals(0, selection.score().distinctRelocationCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED_COL::equals));
		Assert.assertTrue("equal-score ties must not expand the 2^17 Cartesian product",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void candidateAwareZeroRelocationSearchUsesSafeAssignmentPrefixTieBound() {
		List<Node> nodes = equalObjectiveNodes("exact-selector-candidate-aware-ties");
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, List.of(), List.of());

		PlacementSelection selection = new ExactPlacementSelector().select(
			CampaignBPlacementAnalysisFixtureBridge.fromSelectorGraph(graph));

		Assert.assertEquals(17, selection.score().emittedFedCount());
		Assert.assertEquals(17, selection.score().foutCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED_COL::equals));
		Assert.assertEquals("zero-relocation candidate components need only the lexicographically minimal arm",
			17, selection.certificate().exploredCount());
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void productionSizeRelocationTiesUseAnAdmissibleRelocationLowerBound() {
		String fingerprint = "exact-selector-relocation-ties";
		List<Node> nodes = equalObjectiveNodes(fingerprint);
		List<CompiledHopKey> consumers = nodes.stream().map(Node::key).toList();
		DurableAnchorKey anchor = new DurableAnchorKey("exact-selector-anchor", FType.COL,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(1L, 1L))));
		RelocationActionKey actionKey = new RelocationActionKey(nodes.get(0).valueVersion(),
			FED_COL, anchor, "exact-selector-scope", consumers);
		List<ObligationKey> obligations = new ArrayList<>();
		for(int i = 0; i < nodes.size(); i++)
			obligations.add(new ObligationKey(nodes.get(i).key(), i, nodes.get(0).valueVersion(),
				FED_COL, actionKey, "exact-selector-scope"));

		PlacementSelection selection = new ExactPlacementSelector().select(new NeutralPlacementGraph(
			nodes, List.of(), List.of(new RelocationAction(actionKey, obligations))));

		Assert.assertEquals(0, selection.score().distinctRelocationCount());
		Assert.assertTrue(selection.assignment().values().stream().allMatch(FED::equals));
		Assert.assertTrue(selection.selectedRelocations().isEmpty());
		Assert.assertTrue("relocation ties must not expand the 2^17 Cartesian product",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void compatibleRefedActionsCountOnePhysicalUploadButRetainBothExactReceipts() {
		String fingerprint = "exact-selector-physical-refed-count";
		Node sourceTemplate = decisionNode(fingerprint, "source", 0);
		Node firstTemplate = decisionNode(fingerprint, "consumer-a", 1);
		Node secondTemplate = decisionNode(fingerprint, "consumer-b", 2);
		Node source = new Node(sourceTemplate.key(), sourceTemplate.kind(), sourceTemplate.valueVersion(),
			true, List.of(LOCAL), List.of(), List.of());
		Node first = new Node(firstTemplate.key(), firstTemplate.kind(), firstTemplate.valueVersion(),
			true, List.of(FED), List.of(), List.of());
		Node second = new Node(secondTemplate.key(), secondTemplate.kind(), secondTemplate.valueVersion(),
			true, List.of(FED), List.of(), List.of());
		DurableAnchorKey anchor = new DurableAnchorKey("shared-refed-anchor", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(1L, 1L))));
		RelocationActionKey firstKey = new RelocationActionKey(source.valueVersion(), FED,
			FType.ROW, anchor, "shared-scope", List.of(first.key()));
		RelocationActionKey secondKey = new RelocationActionKey(source.valueVersion(), FED,
			FType.ROW, anchor, "shared-scope", List.of(second.key()));
		RelocationAction firstAction = new RelocationAction(firstKey, List.of(new ObligationKey(
			first.key(), 0, source.valueVersion(), FED, firstKey, "compiled")));
		RelocationAction secondAction = new RelocationAction(secondKey, List.of(new ObligationKey(
			second.key(), 0, source.valueVersion(), FED, secondKey, "compiled")));

		PlacementSelection selection = new ExactPlacementSelector().select(new NeutralPlacementGraph(
			List.of(source, first, second), List.of(), List.of(firstAction, secondAction)));

		Assert.assertEquals("one source upload to one durable anchor is one physical relocation",
			1, selection.score().distinctRelocationCount());
		Assert.assertEquals("both consumer-specific action receipts remain explicit for lowering",
			2, selection.selectedRelocations().size());
	}

	@Test
	public void productionSizeIndependentConstraintComponentsAvoidGlobalCartesianExpansion() {
		String fingerprint = "exact-selector-independent-components";
		List<Node> nodes = new ArrayList<>();
		List<Constraint> constraints = new ArrayList<>();
		for(int i = 0; i < 9; i++) {
			Node left = decisionNode(fingerprint, "pair-" + i + "-left", i * 2);
			Node right = decisionNode(fingerprint, "pair-" + i + "-right", i * 2 + 1);
			nodes.add(left);
			nodes.add(right);
			constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, left.key(), right.key(), i,
				"forbid-pair:" + FED.normalizedSignature() + "=>" + FED.normalizedSignature()));
		}

		PlacementSelection selection = new ExactPlacementSelector().select(
			new NeutralPlacementGraph(nodes, constraints, List.of()));

		Assert.assertEquals(9, selection.score().emittedFedCount());
		Assert.assertEquals(9, selection.score().foutCount());
		for(Constraint constraint : constraints) {
			Assert.assertFalse(FED.equals(selection.assignment().get(constraint.left()))
				&& FED.equals(selection.assignment().get(constraint.right())));
			Assert.assertEquals("stable exact tie break", LOCAL,
				selection.assignment().get(constraint.left()));
			Assert.assertEquals("stable exact tie break", FED,
				selection.assignment().get(constraint.right()));
		}
		Assert.assertTrue("independent exact components must not form a graph-wide Cartesian product",
			selection.certificate().exploredCount() < 100);
		Assert.assertTrue(selection.certificate().prunedCount() > 0);
	}

	@Test
	public void activeCandidateCannotDisappearWhenItsPhysicalSourceAssignmentIsUnavailable() throws Exception {
		CandidateDependency dependency = directCandidateDependency();
		Map<CompiledHopKey,PlacementState> incomplete = new IdentityHashMap<>();
		incomplete.put(dependency.fact().key().parentOccurrence(),
			dependency.emission().emissionState().placementState());

		Assert.assertThrows("an active exact candidate with zero reachable rows must fail closed",
			IllegalStateException.class, () -> CandidateSelections.feasibleVariants(
				dependency.analysis(), List.of(), incomplete));
	}

	@Test
	public void productionComponentsIncludeDirectCandidateReachabilityDependencies() throws Exception {
		CandidateDependency dependency = directCandidateDependency();
		PlacementAnalysis padded = CampaignBPlacementAnalysisFixtureBridge.pinAndPadCandidateAnalysis(
			dependency.analysis(), dependency.pinnedStates(), 17, List.of(LOCAL, FED));

		PlacementSelection selection = new ExactPlacementSelector().select(padded);

		Assert.assertEquals(TerminationReason.TIGHT_BOUND_EQUALITY,
			selection.certificate().terminationReason());
		Assert.assertTrue("the pinned active candidate row must survive component solving",
			selection.selectedCandidateSelections().stream().anyMatch(receipt ->
				receipt.rule() == dependency.fact().key()
					&& receipt.emission() == dependency.emission()));
		CandidateSelections.resolveAndValidate(padded, padded.graph().relocationActions(),
			selection.assignment(), selection.selectedCandidateSelections());
	}

	@Test
	public void indexedPartialReachabilityMatchesTheDiagnosticOracle() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			ProductionShadowFixtureFactory.compile("B-11"));
		var indexed = CandidateSelections.partialReachabilityIndex(
			analysis, analysis.graph(), analysis.graph().relocationActions());
		java.util.Random random = new java.util.Random(2026081401L);
		for(int sample = 0; sample < 256; sample++) {
			Map<CompiledHopKey,PlacementState> partial = new IdentityHashMap<>();
			for(Node node : analysis.graph().decisionNodes())
				if(random.nextInt(4) == 0)
					partial.put(node.key(), node.legalAlternatives().get(
						random.nextInt(node.legalAlternatives().size())));
			boolean diagnostic = CandidateSelections.canStillBeReachable(
				analysis, analysis.graph(), analysis.graph().relocationActions(), partial);
			Assert.assertEquals("indexed pruning must be exactly equivalent for sample " + sample,
				diagnostic, indexed.canStillBeReachable(partial));
			for(Node changed : analysis.graph().decisionNodes()) {
				var probe = indexed.changedNodesProbe(List.of(changed));
				Assert.assertEquals("precompiled changed-node probe must preserve exact reachability",
					indexed.canStillBeReachableForChangedNodes(partial, List.of(changed)),
					indexed.canStillBeReachable(partial, probe));
			}
		}
	}

	@Test
	public void indexedCompleteCandidateScoringMatchesTheCanonicalOracle() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			ProductionShadowFixtureFactory.compile("B-11"));
		var actions = analysis.graph().relocationActions();
		var order = RelocationSelections.canonicalOrderIndex(actions);
		var indexed = CandidateSelections.partialReachabilityIndex(
			analysis, analysis.graph(), actions);
		java.util.Random random = new java.util.Random(2026081402L);
		int compared = 0;
		for(int sample = 0; sample < 512; sample++) {
			Map<CompiledHopKey,PlacementState> assignment = new IdentityHashMap<>();
			for(Node node : analysis.graph().decisionNodes())
				assignment.put(node.key(), node.legalAlternatives().get(
					random.nextInt(node.legalAlternatives().size())));
			if(!indexed.canStillBeReachable(assignment))
				continue;
			var canonical = CandidateSelections.selectMaterializationMaximal(
				analysis, analysis.graph(), actions, assignment, order);
			var optimized = CandidateSelections.selectMaterializationMaximal(
				analysis, analysis.graph(), actions, assignment, order, indexed);
			Assert.assertEquals("indexed physical effects must preserve candidate rows",
				canonical.candidates().stream().sorted().toList(),
				optimized.candidates().stream().sorted().toList());
			Assert.assertEquals("indexed physical effects must preserve relocation choices",
				canonical.relocationChoices(), optimized.relocationChoices());
			Assert.assertEquals("indexed physical effects must preserve emitted actions",
				canonical.emittedActions(), optimized.emittedActions());
			Assert.assertEquals(canonical.materializedInputCount(),
				optimized.materializedInputCount());
			Assert.assertEquals(canonical.relocationPhysicalEmissionCount(),
				optimized.relocationPhysicalEmissionCount());
			Assert.assertEquals(canonical.localMaterializationActionCount(),
				optimized.localMaterializationActionCount());
			Assert.assertEquals(canonical.foutMaterializationActionCount(),
				optimized.foutMaterializationActionCount());
			compared++;
		}
		Assert.assertTrue("fixture must expose reachable complete assignments", compared > 0);
	}

	private static CandidateDependency directCandidateDependency() throws Exception {
		PlacementAnalysis source = new NeutralPlacementGraphBuilder().buildAnalysis(
			ProductionShadowFixtureFactory.compile("B-11"));
		for(CandidateRuleFact fact : source.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			Node consumer = source.graph().node(fact.key().parentOccurrence()).orElseThrow();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState consumerState = emission.emissionState().placementState();
				if(consumerState.execType() != ExecType.FED
					|| !consumer.legalAlternatives().contains(consumerState))
					continue;
				Map<CompiledHopKey,PlacementState> pins = new IdentityHashMap<>();
				pins.put(consumer.key(), consumerState);
				boolean physical = false;
				boolean feasible = true;
				for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
					var input = fact.key().orderedInputs().get(position);
					if(!input.present())
						continue;
					final int exactPosition = position;
					List<PlacementAnalysis.CompiledInputEdgeFact> edges = source.compiledInputEdgesInCanonicalOrder()
						.stream().filter(edge -> edge.consumer() == consumer.key()
							&& edge.inputPosition() == exactPosition).toList();
					if(edges.isEmpty())
						continue;
					if(edges.size() != 1) {
						feasible = false;
						break;
					}
					physical = true;
					Node producer = source.graph().node(edges.get(0).producer()).orElseThrow();
					PlacementState direct = producer.legalAlternatives().stream().filter(state ->
						state.output() == FederatedOutput.FOUT && state.fType() == input.fType())
						.findFirst().orElse(null);
					if(direct == null || pins.containsKey(producer.key())
						&& !pins.get(producer.key()).equals(direct)) {
						feasible = false;
						break;
					}
					pins.put(producer.key(), direct);
				}
				if(feasible && physical) {
					PlacementAnalysis restricted = CampaignBPlacementAnalysisFixtureBridge
						.withOnlyCandidateFact(source, fact);
					Assert.assertTrue("exact relocation/candidate edges must now couple source and consumer",
						pins.keySet().stream().filter(key -> key != consumer.key())
							.allMatch(key -> legacyComponentCoupled(source.graph(), key, consumer.key())));
					return new CandidateDependency(restricted, fact, emission,
						java.util.Collections.unmodifiableMap(pins));
				}
			}
		}
		throw new AssertionError("B-11 must expose one exact direct physical candidate dependency");
	}

	private static boolean legacyComponentCoupled(NeutralPlacementGraph graph,
		CompiledHopKey source, CompiledHopKey consumer) {
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency = new IdentityHashMap<>();
		for(Node node : graph.decisionNodes())
			adjacency.put(node.key(), java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
		for(Constraint constraint : graph.constraints())
			if((constraint.kind() == ConstraintKind.SAME_PLACEMENT
				|| constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				|| constraint.kind() == ConstraintKind.SAME_FTYPE
				|| constraint.kind() == ConstraintKind.CONJUNCTIVE)
				&& adjacency.containsKey(constraint.left()) && adjacency.containsKey(constraint.right()))
				connect(adjacency, constraint.left(), constraint.right());
		for(RelocationAction action : graph.relocationActions()) {
			Set<CompiledHopKey> participants = new LinkedHashSet<>();
			for(Node node : graph.decisionNodes())
				if(node.valueVersion().equals(action.key().sourceValueVersion()))
					participants.add(node.key());
			for(var obligation : action.obligations())
				if(adjacency.containsKey(obligation.consumer()))
					participants.add(obligation.consumer());
			if(!participants.isEmpty()) {
				CompiledHopKey first = participants.iterator().next();
				for(CompiledHopKey participant : participants)
					connect(adjacency, first, participant);
			}
		}
		Set<CompiledHopKey> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
		visited.add(source);
		pending.add(source);
		while(!pending.isEmpty()) {
			CompiledHopKey current = pending.removeFirst();
			if(current == consumer)
				return true;
			for(CompiledHopKey adjacent : adjacency.getOrDefault(current, Set.of()))
				if(visited.add(adjacent))
					pending.addLast(adjacent);
		}
		return false;
	}

	private static void connect(Map<CompiledHopKey,Set<CompiledHopKey>> adjacency,
		CompiledHopKey left, CompiledHopKey right) {
		adjacency.get(left).add(right);
		adjacency.get(right).add(left);
	}

	private record CandidateDependency(PlacementAnalysis analysis, CandidateRuleFact fact,
		CandidateEmissionFact emission, Map<CompiledHopKey,PlacementState> pinnedStates) { }

	private static List<Node> equalObjectiveNodes(String fingerprint) {
		List<Node> nodes = new ArrayList<>();
		for(int i = 0; i < 17; i++) {
			Node node = decisionNode(fingerprint, "hop-" + i, i);
			nodes.add(new Node(node.key(), node.kind(), node.valueVersion(), true,
				List.of(FED_COL, FED), List.of(), List.of()));
		}
		return nodes;
	}

	private static Node decisionNode(String fingerprint, String id, int ordinal) {
		ControlRegionKey region = new ControlRegionKey(fingerprint, "main", List.of("sb"),
			"main", "compiled");
		CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled", region,
			id, id);
		ValueVersionKey value = new ValueVersionKey(fingerprint, "v" + ordinal, region, ordinal,
			VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, true,
			List.of(LOCAL, FED), List.of(), List.of());
	}
}
