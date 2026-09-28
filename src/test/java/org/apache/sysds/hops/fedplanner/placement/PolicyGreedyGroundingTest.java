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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
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
		var error = Assert.assertThrows(PolicyGreedyPlacementSelector.UnresolvedBoundaryContractException.class,
			() -> new PolicyGreedyPlacementSelector().select(analysis));
		Assert.assertTrue(error.getMessage().contains("no external value entry"));
		Assert.assertTrue(error.getMessage().contains("not global infeasibility"));
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
		Assert.assertThrows(PolicyGreedyPlacementSelector.UnresolvedBoundaryContractException.class,
			() -> new PolicyGreedyPlacementSelector().select(analysis));
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
	private static CandidateRuleFact fact(CandidateRuleKey rule, List<CandidateRealizationSupportClause> clauses) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.CP, FederatedOutput.LOUT,
				null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()), new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(EMISSION, null, null,
				List.of(new CandidateEmissionRealization(LAYOUT, clauses)))), "");
	}
	private static CandidateRuleKey rule(Node node, int inputs) {
		return new CandidateRuleKey(node.key(), java.util.Collections.nCopies(inputs, CandidateInputState.absentLocal()));
	}
	private static Node node(String id) {
		return node(id,NodeKind.OPERATION);
	}
	private static Node node(String id, NodeKind kind) {
		var region = new ControlRegionKey("greedy-proof", "main", List.of("main"), "root", "compiled");
		var key = new CompiledHopKey("greedy-proof", "main", "root", "compiled", region, id, id);
		return new Node(key, kind, new ValueVersionKey("greedy-proof", id, region, 0,
			VersionKind.ORDINARY, List.of()), true, List.of(LOCAL), List.of(), List.of());
	}
	private static PlacementAnalysis analysis(NeutralPlacementGraph graph, List<CandidateRuleFact> facts) {
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
			new PlacementPrivacyFacts.PrivacyFact(n.key(), n.valueVersion(), Privacy.PRIVATE_AGGREGATE, List.of())).toList(), 2);
		return new PlacementAnalysis(graph, projections, List.of(), null, new PlacementShapeFacts(shapes, keys),
			"synthetic-greedy-proof", new PlacementAnalysis.HeuristicPolicyFacts(List.of()),
			facts.stream().map(CandidateRuleFact::key).toList(), facts, List.of(), List.of(), List.of(), edges,
			List.of(), privacy, null);
	}
}
