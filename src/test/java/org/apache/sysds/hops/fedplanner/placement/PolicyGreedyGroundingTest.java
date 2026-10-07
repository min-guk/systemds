/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyPlacementSelector;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact synthetic authority seams, not runtime/privacy capability fixtures. */
public class PolicyGreedyGroundingTest {
	private static final PlacementState LOCAL = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(LOCAL, false);
	private static final PlacementRealizationKey LAYOUT = PlacementRealizationKey.local(EMISSION);
	private static final PlacementState FED_FULL =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState FED_EMISSION = new PlacementEmissionState(FED_FULL, false);

	@Test
	public void selectedLoopWithExactExternalEntryRemainsGrounded() {
		PlacementAnalysis analysis = cycle(true);
		var selected = new PolicyGreedyPlacementSelector().select(analysis);
		Assert.assertEquals(3, selected.assignment().size());
		Assert.assertEquals(3, selected.selectedCandidateSelections().size());
	}

	@Test
	public void unusedExternalSchedulingEdgeCannotGroundTheSelectedProofCycle() {
		PlacementAnalysis analysis = cycle(false);
		var error = Assert.assertThrows(PolicyGreedyPlacementSelector.NoSupportedPolicyWitnessException.class,
			() -> new PolicyGreedyPlacementSelector().select(analysis));
		Assert.assertTrue(error.getMessage().contains("not global infeasibility"));
		Assert.assertTrue(rootCause(error).getMessage().contains("no external value entry"));
	}

	@Test
	public void seedlessLocalFunctionBoundaryCannotDisappearFromTheValueProof() {
		Node a = node("read-a",NodeKind.TRANSIENT_READ), b = node("read-b",NodeKind.TRANSIENT_READ);
		var graph = new NeutralPlacementGraph(List.of(a,b), List.of(
			new Constraint(ConstraintKind.SAME_PLACEMENT,a.key(),b.key(),0,"cfg-function-output-value:a"),
			new Constraint(ConstraintKind.SAME_PLACEMENT,b.key(),a.key(),0,"cfg-function-output-value:b")),List.of());
		PlacementAnalysis analysis = analysis(graph,List.of(fact(rule(a,0),List.of(clause())),
			fact(rule(b,0),List.of(clause()))));
		Assert.assertEquals(List.of(b.key()),analysis.logicalBoundaryRealizations().sources(a.key()));
		Assert.assertTrue("The FOUT-only relation is deliberately empty for this local fixture",
			analysis.logicalBoundaryRealizations().relations().isEmpty());
		var error = Assert.assertThrows(PolicyGreedyPlacementSelector.NoSupportedPolicyWitnessException.class,
			() -> new PolicyGreedyPlacementSelector().select(analysis));
		Assert.assertTrue(rootCause(error).getMessage().contains("no external value entry"));
	}

	@Test
	public void retiringOneClauseDoesNotRemoveTheSharedRealizationReference() {
		Node seed = node("seed"), a = node("a"), consumer = node("consumer");
		CandidateRuleKey seedRule = rule(seed, 0), aRule = rule(a, 1), consumerRule = rule(consumer, 1);
		var seedRef = new CandidateRealizationReference(seedRule, LAYOUT);
		var aRef = new CandidateRealizationReference(aRule, LAYOUT);
		var first = new CandidateRealizationSupportClause(List.of(new PlacementIdentity.PlacementProofKey(
			PlacementIdentity.PlacementProofKind.SHAPE, a.key(), "first")),
			List.of(CandidateRealizationInputBinding.direct(0, seedRef)));
		var second = new CandidateRealizationSupportClause(List.of(new PlacementIdentity.PlacementProofKey(
			PlacementIdentity.PlacementProofKind.SHAPE, a.key(), "second")),
			List.of(CandidateRealizationInputBinding.direct(0, seedRef)));
		var aFact = fact(aRule, List.of(first, second));
		PlacementAnalysis analysis = analysis(new NeutralPlacementGraph(List.of(seed,a,consumer), List.of(
			new Constraint(ConstraintKind.DOMINATES, seed.key(), a.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, a.key(), consumer.key(), 0, "data-input")), List.of()),
			List.of(fact(seedRule, List.of(clause())), aFact,
				fact(consumerRule, List.of(clause(CandidateRealizationInputBinding.direct(0, aRef))))));
		var rows = analysis.canonicalCandidateReceipts(aRule, aFact.allowedEmissionFacts().get(0));
		Assert.assertEquals(2, rows.size());
		Assert.assertSame(rows.get(0).realization(), rows.get(1).realization());
		var run = new PolicyGreedyPlacementSelector().selectWithMetrics(analysis, analysis.graph());
		Assert.assertEquals(1, run.metrics().deletedRows());
		Assert.assertEquals(3, run.selection().selectedCandidateSelections().size());
	}

	@Test
	public void reciprocalInputRequirementsMustHaveOneCommonRowPair() {
		Node seed = node("seed"), a = node("a"), b = node("b");
		var seedRule = rule(seed, 0);
		var aBad = rule(a, 2);
		var aGood = rule(a, 3);
		var bBad = rule(b, 1);
		var bGood = rule(b, 2);
		var seedRef = new CandidateRealizationReference(seedRule, LAYOUT);
		var aBadRef = new CandidateRealizationReference(aBad, LAYOUT);
		var aGoodRef = new CandidateRealizationReference(aGood, LAYOUT);
		var bBadRef = new CandidateRealizationReference(bBad, LAYOUT);
		var bGoodRef = new CandidateRealizationReference(bGood, LAYOUT);
		var goodClause = clause(CandidateRealizationInputBinding.direct(0, aGoodRef));
		var graph = new NeutralPlacementGraph(List.of(seed, a, b), List.of(
			new Constraint(ConstraintKind.DOMINATES, seed.key(), a.key(), 1, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, b.key(), a.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, a.key(), b.key(), 0, "data-input")), List.of());
		var analysis = analysis(graph, List.of(fact(seedRule, List.of(clause())),
			fact(aBad, List.of(clause(CandidateRealizationInputBinding.direct(0, bBadRef),
				CandidateRealizationInputBinding.direct(1, seedRef)))),
			fact(aGood, List.of(clause(CandidateRealizationInputBinding.direct(0, bGoodRef),
				CandidateRealizationInputBinding.direct(1, seedRef)))),
			fact(bBad, List.of(clause(CandidateRealizationInputBinding.direct(0, aGoodRef)))),
			fact(bGood, List.of(clause(CandidateRealizationInputBinding.direct(0, aBadRef)), goodClause))));
		// aBad can find bBad in isolation, and a different bGood clause accepts aBad.
		// No single pair satisfies both directions; only aGood/bGood is feasible.
		for(var policy : PolicyGreedyPlacementSelector.Policy.values()) {
			var run = new PolicyGreedyPlacementSelector(policy).selectWithMetrics(analysis, graph);
			Assert.assertEquals(3, run.metrics().decisionCommits());
			var rows = run.selection().selectedCandidateSelections();
			Assert.assertTrue(rows.stream().anyMatch(row -> row.rule() == aGood));
			Assert.assertTrue(rows.stream().anyMatch(row -> row.rule() == bGood
				&& row.supportClause() == goodClause));
			Assert.assertEquals(rows, CandidateSelections.resolveAndValidateSelected(analysis, graph,
				run.selection().assignment(), rows));
		}
	}

	@Test
	public void durableOutputSupportJoinsAlternateProducerRuleWithoutWeakeningOtherIdentities() {
		OutputSupportFixture fixture = outputSupportFixture(OutputSupportCase.SAME_DURABLE);
		for(var policy : PolicyGreedyPlacementSelector.Policy.values()) {
			var run = new PolicyGreedyPlacementSelector(policy)
				.selectWithMetrics(fixture.analysis(), fixture.graph());
			var selected = run.selection().selectedCandidateSelections();
			Assert.assertTrue(selected.stream().anyMatch(row -> row.rule() == fixture.selectedRule()));
			Assert.assertFalse(selected.stream().anyMatch(row -> row.rule() == fixture.requiredRule()));
			Assert.assertEquals(selected, CandidateSelections.resolveAndValidateSelected(fixture.analysis(),
				fixture.graph(), run.selection().assignment(), selected));
		}
	}

	@Test
	public void outputSupportDoesNotCrossOwnerLayoutOrValueMapRule() {
		for(OutputSupportCase kind : List.of(OutputSupportCase.DIFFERENT_OWNER,
			OutputSupportCase.DIFFERENT_LAYOUT, OutputSupportCase.VALUE_MAP)) {
			OutputSupportFixture fixture = outputSupportFixture(kind);
			Assert.assertThrows(kind.name(),
				PolicyGreedyPlacementSelector.NoSupportedPolicyWitnessException.class,
				() -> new PolicyGreedyPlacementSelector().select(fixture.analysis(), fixture.graph()));
		}
	}

	private enum OutputSupportCase { SAME_DURABLE, DIFFERENT_OWNER, DIFFERENT_LAYOUT, VALUE_MAP }
	private record OutputSupportFixture(PlacementAnalysis analysis, NeutralPlacementGraph graph,
		CandidateRuleKey requiredRule, CandidateRuleKey selectedRule) { }
	private static OutputSupportFixture outputSupportFixture(OutputSupportCase kind) {
		Node gate = node("gate");
		DurableAnchorKey anchorA = anchor("pool-a", "localhost:13001/X");
		DurableAnchorKey anchorB = anchor("pool-b", "localhost:13002/X");
		Node producer = node("producer", NodeKind.OPERATION, List.of(FED_FULL), List.of(anchorA, anchorB));
		Node requiredOwner = kind == OutputSupportCase.DIFFERENT_OWNER
			? node("other", NodeKind.OPERATION, List.of(FED_FULL), List.of(anchorA)) : producer;
		Node consumer = node("consumer");
		CandidateRuleKey gateGood = rule(gate, 0);
		CandidateRuleKey gateUnavailable = rule(gate, CandidateInputState.present(FType.FULL));
		CandidateRuleKey producerRequired = rule(requiredOwner, CandidateInputState.present(FType.FULL));
		CandidateRuleKey producerSelected = rule(producer, 1);
		CandidateRuleKey consumerRule = rule(consumer, CandidateInputState.present(FType.FULL));
		PlacementRealizationKey requiredLayout = kind == OutputSupportCase.VALUE_MAP
			? PlacementRealizationKey.valueMap(FED_EMISSION, "value-map")
			: PlacementRealizationKey.durable(FED_EMISSION, anchorA);
		PlacementRealizationKey selectedLayout = kind == OutputSupportCase.VALUE_MAP
			? PlacementRealizationKey.valueMap(FED_EMISSION, "value-map")
			: PlacementRealizationKey.durable(FED_EMISSION,
				kind == OutputSupportCase.DIFFERENT_LAYOUT ? anchorB : anchorA);
		CandidateRealizationReference gateGoodRef = new CandidateRealizationReference(gateGood, LAYOUT);
		PlacementRealizationKey unavailableGateLayout =
			PlacementRealizationKey.valueMap(FED_EMISSION, "unavailable-gate-layout");
		CandidateRealizationReference gateUnavailableRef =
			new CandidateRealizationReference(gateUnavailable, unavailableGateLayout);
		CandidateRealizationReference requiredProducer =
			new CandidateRealizationReference(producerRequired, requiredLayout);
		List<Node> nodes = requiredOwner == producer ? List.of(gate, producer, consumer)
			: List.of(gate, producer, requiredOwner, consumer);
		// The DIRECT edge and selected receipt remain exact. Only the producer output
		// reference may change input rule after the same durable output has been proved.
		List<Constraint> edges = new ArrayList<>(List.of(
			new Constraint(ConstraintKind.DOMINATES, gate.key(), producer.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, requiredOwner.key(), consumer.key(), 0, "data-input")));
		if(requiredOwner != producer)
			edges.add(new Constraint(ConstraintKind.DOMINATES, gate.key(), requiredOwner.key(), 0, "data-input"));
		var graph = new NeutralPlacementGraph(nodes, edges, List.of());
		List<CandidateRuleFact> facts = new ArrayList<>(List.of(
			fact(gateGood, List.of(clause())),
			fact(gateUnavailable, FED_EMISSION, unavailableGateLayout, List.of(clause())),
			fact(producerSelected, FED_EMISSION, selectedLayout,
				List.of(clause(CandidateRealizationInputBinding.direct(0, gateGoodRef))))));
		facts.add(fact(producerRequired, FED_EMISSION, requiredLayout,
			List.of(clause(CandidateRealizationInputBinding.direct(0, gateUnavailableRef)))));
		facts.add(fact(consumerRule,
			List.of(clause(CandidateRealizationInputBinding.direct(0, requiredProducer)))));
		PlacementAnalysis analysis = analysis(graph, facts, Privacy.PUBLIC);
		return new OutputSupportFixture(analysis, graph, producerRequired, producerSelected);
	}

	private static PlacementAnalysis cycle(boolean seeded) {
		Node seed = node("seed"), a = node("a"), b = node("b");
		CandidateRuleKey seedRule = rule(seed, 0), aRule = rule(a, 2), bRule = rule(b, 1);
		var bindings = new ArrayList<CandidateRealizationInputBinding>();
		if(seeded) bindings.add(CandidateRealizationInputBinding.direct(0,
			new CandidateRealizationReference(seedRule, LAYOUT)));
		bindings.add(CandidateRealizationInputBinding.direct(1, new CandidateRealizationReference(bRule, LAYOUT)));
		var graph = new NeutralPlacementGraph(List.of(seed,a,b), List.of(
			new Constraint(ConstraintKind.DOMINATES, seed.key(), a.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, b.key(), a.key(), 1, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, a.key(), b.key(), 0, "data-input")), List.of());
		return analysis(graph, List.of(fact(seedRule, List.of(clause())),
			fact(aRule, List.of(new CandidateRealizationSupportClause(List.of(), bindings))),
			fact(bRule, List.of(clause(CandidateRealizationInputBinding.direct(0,
				new CandidateRealizationReference(aRule, LAYOUT)))))));
	}
	private static CandidateRealizationSupportClause clause(CandidateRealizationInputBinding... bindings) {
		return new CandidateRealizationSupportClause(List.of(), List.of(bindings));
	}
	private static Throwable rootCause(Throwable error) {
		while(error.getCause() != null) error = error.getCause();
		return error;
	}
	private static CandidateRuleFact fact(CandidateRuleKey rule, List<CandidateRealizationSupportClause> clauses) {
		return fact(rule, EMISSION, LAYOUT, clauses);
	}
	private static CandidateRuleFact fact(CandidateRuleKey rule, PlacementEmissionState emission,
		PlacementRealizationKey layout, List<CandidateRealizationSupportClause> clauses) {
		PlacementState state = emission.placementState();
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", state.execType(), state.output(),
				state.fType(), ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()), new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(emission,
				state.execType() == ExecType.FED ? state.fType() : null, null,
				List.of(new CandidateEmissionRealization(layout, clauses)))), "");
	}
	private static CandidateRuleKey rule(Node node, int inputs) {
		return new CandidateRuleKey(node.key(), java.util.Collections.nCopies(inputs, CandidateInputState.absentLocal()));
	}
	private static CandidateRuleKey rule(Node node, CandidateInputState... inputs) {
		return new CandidateRuleKey(node.key(), List.of(inputs));
	}
	private static Node node(String id) {
		return node(id,NodeKind.OPERATION);
	}
	private static Node node(String id, NodeKind kind) {
		return node(id, kind, List.of(LOCAL), List.of());
	}
	private static Node node(String id, NodeKind kind, List<PlacementState> states,
		List<DurableAnchorKey> anchors) {
		var region = new ControlRegionKey("greedy-proof", "main", List.of("main"), "root", "compiled");
		var key = new CompiledHopKey("greedy-proof", "main", "root", "compiled", region, id, id);
		return new Node(key, kind, new ValueVersionKey("greedy-proof", id, region, 0,
			VersionKind.ORDINARY, List.of()), true, states, List.of(), anchors);
	}
	private static DurableAnchorKey anchor(String id, String worker) {
		return new DurableAnchorKey(id, FType.FULL,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 4L))));
	}
	private static PlacementAnalysis analysis(NeutralPlacementGraph graph, List<CandidateRuleFact> facts) {
		return analysis(graph, facts, Privacy.PRIVATE_AGGREGATE);
	}
	private static PlacementAnalysis analysis(NeutralPlacementGraph graph, List<CandidateRuleFact> facts,
		Privacy privacyLevel) {
		var projections = new ArrayList<PlacementAnalysis.HopOccurrenceProjection>();
		var shapes = new LinkedHashMap<CompiledHopKey,PlacementAnalysis.NodeShapeFact>();
		var keys = new LinkedHashSet<CompiledHopKey>();
		var edges = new ArrayList<PlacementAnalysis.CompiledInputEdgeFact>();
		for(Node node : graph.nodes()) {
			var hop = node.kind() == NodeKind.TRANSIENT_READ
				? new org.apache.sysds.hops.DataOp(node.key().emittedHopInstance(),DataType.MATRIX,
					org.apache.sysds.common.Types.ValueType.FP64,org.apache.sysds.common.Types.OpOpData.TRANSIENTREAD,
					"X",4,4,16,1000) : new LiteralOp(projections.size());
			projections.add(new PlacementAnalysis.HopOccurrenceProjection(node.key(), hop,
				-1, projections.size(), node.key().normalizedSignature()));
			keys.add(node.key());
			shapes.put(node.key(), new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 4, 4));
			graph.constraints().stream().filter(c -> c.right() == node.key() && c.kind() == ConstraintKind.DOMINATES)
				.sorted(java.util.Comparator.comparingInt(Constraint::inputPosition))
				.forEach(c -> edges.add(new PlacementAnalysis.CompiledInputEdgeFact(c.left(), c.right(), c.inputPosition())));
		}
		var privacy = new PlacementPrivacyFacts(graph.nodes(), graph.nodes().stream().map(n ->
			new PlacementPrivacyFacts.PrivacyFact(n.key(), n.valueVersion(), privacyLevel, List.of())).toList(), 2);
		return new PlacementAnalysis(graph, projections, List.of(), null, new PlacementShapeFacts(shapes, keys),
			"synthetic-greedy-proof", new PlacementAnalysis.HeuristicPolicyFacts(List.of()),
			facts.stream().map(CandidateRuleFact::key).toList(), facts, List.of(), List.of(), List.of(), edges,
			List.of(), privacy, null);
	}
}
