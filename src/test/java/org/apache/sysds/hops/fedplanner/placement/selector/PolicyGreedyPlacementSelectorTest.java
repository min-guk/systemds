/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections.AuditDemandOptions;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class PolicyGreedyPlacementSelectorTest {

	@Test
	public void noSpeculativeUploadOfUnrelatedLocalWork() throws Exception {
		PlacementAnalysis a = analyze("X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,3)));v=matrix(2,rows=4,cols=3);"
			+ "w=exp(v);print(sum(X)+sum(w));");
		for(var policy : PolicyGreedyPlacementSelector.Policy.values()) {
			var selected = new PolicyGreedyPlacementSelector(policy).select(a);
			int checked = 0;
			for(var occurrence : a.compiledHopOccurrences())
				if(occurrence.hop().getOpString().equals("u(exp)")) {
					checked++;
					Assert.assertEquals(ExecType.CP, selected.assignment().get(occurrence.key()).execType());
					Assert.assertEquals(FederatedOutput.LOUT, selected.assignment().get(occurrence.key()).output());
				}
			Assert.assertTrue("Fixture must contain local exp work", checked > 0);
			Assert.assertTrue(selected.selectedRelocations().isEmpty());
		}
	}

	@Test
	public void canonicalOrderAndSelectorReuseAreDeterministic() throws Exception {
		PlacementAnalysis a = analyze("X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,3)));print(sum(exp(X)));");
		var selector = new PolicyGreedyPlacementSelector();
		var first = selector.select(a);
		var again = selector.select(a);
		Assert.assertEquals(first, again);
		List<Node> nodes = new ArrayList<>(a.graph().nodes());
		Collections.reverse(nodes);
		var reversed = new NeutralPlacementGraph(nodes, a.graph().constraints(), a.graph().relocationActions(),
			a.graph().derivedFoutMaterializationActions());
		Assert.assertEquals(first, selector.select(a, reversed));
		Assert.assertEquals(first.selectedCandidateSelections(), CandidateSelections.resolveAndValidate(a,
			first.assignment(), first.selectedCandidateSelections()));
		var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> selector.select(a));
		var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> selector.select(a));
		Assert.assertEquals(first, one.join());
		Assert.assertEquals(first, two.join());
	}

	@Test
	public void selectedValidatorRejectsMissingAssignmentsAndForeignRuleAuthority() throws Exception {
		PlacementAnalysis a = analyze("X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,3)));print(sum(X));");
		var selected = new PolicyGreedyPlacementSelector().select(a);
		var partial = new HashMap<>(selected.assignment());
		partial.remove(a.graph().decisionNodes().get(0).key());
		try {
			CandidateSelections.resolveAndValidateSelected(a,a.graph(),partial,selected.selectedCandidateSelections());
			Assert.fail("Complete witness cannot use unassigned legal alternatives");
		}
		catch(IllegalArgumentException expected) { Assert.assertTrue(expected.getMessage().contains("total node-owned")); }
		var key = a.graph().decisionNodes().get(0).key();
		var foreignKey = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(), key.callSitePath(),
			key.recompileContext(), key.controlRegion(), key.emittedHopInstance(), key.canonicalSourceOrigin());
		var foreignAssignment = new HashMap<>(selected.assignment());
		var state = foreignAssignment.remove(key);
		foreignAssignment.put(foreignKey,state);
		Assert.assertEquals(key,foreignKey);
		Assert.assertNotSame(key,foreignKey);
		Assert.assertThrows(IllegalArgumentException.class, () -> CandidateSelections.resolveAndValidateSelected(a,
			a.graph(),foreignAssignment,selected.selectedCandidateSelections()));
		var rows = new ArrayList<>(selected.selectedCandidateSelections());
		var owned = rows.get(0);
		var foreign = new PlacementAnalysis.CandidateRuleKey(owned.rule().parentOccurrence(), owned.rule().orderedInputs());
		rows.set(0,new PlacementIdentity.CandidateSelectionReceipt(foreign,owned.emission(),owned.realization(),
			owned.supportClause(),List.of()));
		try {
			CandidateSelections.resolveAndValidateSelected(a,a.graph(),selected.assignment(),rows);
			Assert.fail("Equal structural signatures cannot forge rule ownership");
		}
		catch(IllegalArgumentException expected) { Assert.assertTrue(expected.getMessage().contains("foreign")); }
	}

	@Test
	public void reconvergenceFailsTypedWithoutPretendingInfeasibilityOrRetrying() {
		Node p = node(0), l = node(1), r = node(2);
		PlacementState local = p.legalAlternatives().stream().filter(s -> s.execType() == ExecType.CP).findFirst().orElseThrow();
		PlacementState fed = p.legalAlternatives().stream().filter(s -> s.execType() == ExecType.FED).findFirst().orElseThrow();
		var graph = new NeutralPlacementGraph(List.of(p,l,r), List.of(
			new Constraint(ConstraintKind.DOMINATES, p.key(), l.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, p.key(), r.key(), 0, "data-input"),
			forbid(p,l,fed,fed), forbid(p,r,fed,local), forbid(l,r,local,fed), forbid(l,r,fed,local)), List.of());
		Assert.assertNotNull("An existing feasible plan is not a greedy completeness guarantee",
			new PolicyFirstFeasiblePlacementSelector().select(graph));
		try { new PolicyGreedyPlacementSelector().select(graph); Assert.fail("No backtracking allowed"); }
		catch(PolicyGreedyPlacementSelector.GreedyConflictException expected) {
			Assert.assertTrue(expected.getMessage().contains("not global infeasibility"));
			Assert.assertTrue(expected.getMessage().contains("commits=1"));
		}
	}

	@Test
	public void constraintCycleIsNotMistakenForAnUngroundedProofCycle() {
		Node a = node(0), b = node(1);
		var graph = new NeutralPlacementGraph(List.of(a,b), List.of(
			new Constraint(ConstraintKind.DOMINATES, a.key(), b.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, b.key(), a.key(), 0, "data-input"),
			new Constraint(ConstraintKind.SAME_PLACEMENT, a.key(), b.key(), 0, "same")), List.of());
		Assert.assertEquals(2, new PolicyGreedyPlacementSelector().selectWithMetrics(null,graph).metrics().decisionCommits());
	}

	@Test
	public void relocationUsesPoolIntersectionNotIndependentFirstChoices() {
		DurableAnchorKey a = pool("a", 2), b = pool("b", 4), bAlias = pool("same-layout-new-value", 4);
		RelocationChoiceReceipt firstA = choice(0,a), firstB = choice(0,b), secondB = choice(1,bAlias);
		var selected = PolicyGreedyPlacementSelector.choosePools(List.of(
			new AuditDemandOptions(firstA.demand(), List.of(firstA,firstB)),
			new AuditDemandOptions(secondB.demand(), List.of(secondB))));
		Assert.assertEquals(List.of(firstB,secondB), selected);
		try {
			PolicyGreedyPlacementSelector.choosePools(List.of(
				new AuditDemandOptions(firstA.demand(), List.of(firstA)),
				new AuditDemandOptions(secondB.demand(), List.of(secondB))));
			Assert.fail("Endpoint equality must not erase partitioned-axis differences");
		}
		catch(PolicyGreedyPlacementSelector.GreedyConflictException expected) {
			Assert.assertTrue(expected.getMessage().contains("no common relocation pool"));
		}
	}

	@Test
	public void hashedPoolIdentityMatchesTheExistingAuthorityPredicate() {
		List<DurableAnchorKey> pools = List.of(pool("one",2), pool("alias",2), pool("two",4),
			new DurableAnchorKey("other-worker",FType.ROW,List.of(new AnchorPartition("other:1234/X",
				List.of(0L,0L),List.of(2L,3L)))));
		for(var left : pools) for(var right : pools) {
			Assert.assertEquals(PlacementIdentity.samePhysicalWorkerPool(left,right),
				PlacementIdentity.physicalWorkerPoolIdentity(left).equals(PlacementIdentity.physicalWorkerPoolIdentity(right)));
			Assert.assertEquals(PlacementIdentity.samePhysicalWorkerEndpoints(left,right),
				PlacementIdentity.physicalWorkerEndpointIdentity(left).equals(PlacementIdentity.physicalWorkerEndpointIdentity(right)));
		}
	}

	private static Constraint forbid(Node left, Node right, PlacementState l, PlacementState r) {
		return new Constraint(ConstraintKind.CONJUNCTIVE,left.key(),right.key(),0,
			"forbid-pair:" + l.normalizedSignature() + "=>" + r.normalizedSignature());
	}
	private static DurableAnchorKey pool(String id, long end) {
		return new DurableAnchorKey(id,FType.ROW,List.of(new AnchorPartition("worker:1234/X",
			List.of(0L,0L),List.of(end,3L))));
	}
	private static RelocationChoiceReceipt choice(int position, DurableAnchorKey anchor) {
		Node source = node(position), consumer = node(9);
		PlacementState state = new PlacementState(ExecType.FED,FederatedOutput.FOUT,FType.ROW,false);
		var demand = new RelocationDemandKey(source.valueVersion(), consumer.key(), position, state, "compiled");
		var action = new RelocationActionKey(source.valueVersion(),state,anchor,"main",List.of(consumer.key()));
		return new RelocationChoiceReceipt(demand,action);
	}

	@Test
	public void deepConnectedGraphCommitsOnceWithoutJavaRecursion() {
		int size = 4096;
		List<Node> nodes = new ArrayList<>();
		List<Constraint> edges = new ArrayList<>();
		for(int i = 0; i < size; i++) {
			nodes.add(node(i));
			if(i > 0) {
				edges.add(new Constraint(ConstraintKind.DOMINATES, nodes.get(i - 1).key(),
					nodes.get(i).key(), 0, "data-input"));
				edges.add(new Constraint(ConstraintKind.CONJUNCTIVE, nodes.get(i - 1).key(),
					nodes.get(i).key(), 0, "value-flow"));
			}
		}
		var graph = new NeutralPlacementGraph(nodes, edges, List.of());
		var run = new PolicyGreedyPlacementSelector().selectWithMetrics(null, graph);
		Assert.assertEquals(size, run.metrics().decisionCommits());
		Assert.assertEquals(size, run.selection().assignment().size());
		Assert.assertEquals(size, run.selection().score().emittedFedCount());
		Assert.assertTrue(run.metrics().candidateChecks() <= 2L * size);
		Assert.assertTrue(run.metrics().supportIncidences() < 32L * size);
		Assert.assertFalse(run.selection().certificate().optimalityProven());
		for(Node n : nodes)
			Assert.assertTrue(n.legalAlternatives().stream()
				.anyMatch(s -> s == run.selection().assignment().get(n.key())));
	}

	@Test
	public void protectedNestedAggregatesKeepExactLocalOutputWitnesses() throws Exception {
		PlacementAnalysis a = analyze("X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,3)));v=matrix(1,rows=3,cols=1);"
			+ "z=X%*%v;q=t(X)%*%z;print(sum(q));");
		String fingerprint = a.analysisFingerprint();
		String universe = a.graph().normalizedSignature();
		var run = new PolicyGreedyPlacementSelector(PolicyGreedyPlacementSelector.Policy.AGG_LOCAL)
			.selectWithMetrics(a, a.graph());
		for(var occurrence : a.compiledHopOccurrences())
			if(occurrence.hop() instanceof AggBinaryOp) {
				var state = run.selection().assignment().get(occurrence.key());
				Assert.assertEquals(ExecType.FED, state.execType());
				Assert.assertEquals(FederatedOutput.LOUT, state.output());
			}
		Assert.assertTrue(run.selection().selectedRelocations().isEmpty());
		CandidateSelections.validateRealizationSelections(a, run.selection().assignment(),
			run.selection().selectedCandidateSelections(), run.selection().selectedRelocationChoices());
		Assert.assertEquals(fingerprint, a.analysisFingerprint());
		Assert.assertEquals(universe, a.graph().normalizedSignature());
		Assert.assertEquals(a.graph().decisionNodes().size(), run.metrics().decisionCommits());
	}

	@Test
	public void aggregatePreferenceUsesOrientationAndDoesNotTreatEveryAggregateAsAMatrixVector() throws Exception {
		PlacementAnalysis a = analyze("X=federated(addresses=list(\"localhost:1234/X\"),"
			+ "ranges=list(list(0,0),list(4,3)));v=matrix(1,rows=3,cols=1);z=X%*%v;print(sum(z));");
		var result = new HeuristicPlacementAdapter().select(a, java.util.Set.of());
		Assert.assertSame(a.graph(), result.selectorGraph());
		var mm = a.compiledHopOccurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(h -> h instanceof AggBinaryOp).findFirst().orElseThrow();
		var unary = a.compiledHopOccurrences().stream().map(PlacementAnalysis.HopOccurrenceProjection::hop)
			.filter(h -> h instanceof org.apache.sysds.hops.AggUnaryOp).findFirst().orElseThrow();
		var one = PlacementAnalysis.DimensionFact.exact(1);
		var many = PlacementAnalysis.DimensionFact.unknown();
		var column = new PlacementAnalysis.AbstractShapeFact(org.apache.sysds.common.Types.DataType.MATRIX, many, one);
		var row = new PlacementAnalysis.AbstractShapeFact(org.apache.sysds.common.Types.DataType.MATRIX, one, many);
		var matrix = new PlacementAnalysis.AbstractShapeFact(org.apache.sysds.common.Types.DataType.MATRIX, many, many);
		Assert.assertTrue(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, column, FType.ROW));
		Assert.assertTrue(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, row, FType.COL));
		Assert.assertFalse(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, row, FType.ROW));
		Assert.assertFalse(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, column, FType.COL));
		Assert.assertTrue(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, row, FType.FULL));
		Assert.assertTrue(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, column, FType.FULL));
		Assert.assertFalse(PolicyGreedyPlacementSelector.prefersLocalAggregate(mm, matrix, FType.FULL));
		Assert.assertFalse(PolicyGreedyPlacementSelector.prefersLocalAggregate(unary, column, FType.ROW));
	}

	@Test
	public void sharedProtectedFormalOverridesOnlyTheLocalPreference() throws Exception {
		PlacementAnalysis a = analyze("f=function(Matrix[double] A) return(Matrix[double] R) {"
			+ "if(sum(A)>0){R=exp(A);}else{R=abs(A);}}"
			+ "X=federated(addresses=list(\"localhost:1234/X\"),ranges=list(list(0,0),list(4,3)));"
			+ "Y=federated(addresses=list(\"localhost:1234/Y\"),ranges=list(list(0,0),list(4,1)));"
			+ "v=matrix(1,rows=3,cols=1);z=X%*%v;a=f(z);b=f(Y);print(sum(a)+sum(b));");
		var selected = new PolicyGreedyPlacementSelector(PolicyGreedyPlacementSelector.Policy.AGG_LOCAL).select(a);
		for(var occurrence : a.compiledHopOccurrences())
			if(occurrence.hop() instanceof AggBinaryOp)
				Assert.assertEquals(FederatedOutput.FOUT, selected.assignment().get(occurrence.key()).output());
		CandidateSelections.validateRealizationSelections(a, selected.assignment(),
			selected.selectedCandidateSelections(), selected.selectedRelocationChoices());
	}

	private static PlacementAnalysis analyze(String script) throws Exception {
		DMLProgram p = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator t = new DMLTranslator(p);
		t.liveVariableAnalysis(p);
		t.validateParseTree(p);
		t.constructHops(p);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(p, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(p);
	}

	private static Node node(int i) {
		ControlRegionKey region = new ControlRegionKey("greedy-depth", "main", List.of("main"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("greedy-depth", "main", "root", "compiled", region,
			String.format("hop-%06d", i), "origin-" + i);
		return new Node(key, NodeKind.OPERATION, new ValueVersionKey("greedy-depth", "v" + i,
			region, i, VersionKind.ORDINARY, List.of()), true, List.of(
				new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false),
				new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false)), List.of(), List.of());
	}
}
