/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementCostSemantics;
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
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.CampaignBG014PlacementAuthorityTestBridge;
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
		List<Node> foreignNodes = a.graph().nodes().stream().map(node -> node.key() == key
			? new Node(foreignKey, node.kind(), node.valueVersion(), node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), node.anchors()) : node).toList();
		var foreignGraph = new NeutralPlacementGraph(foreignNodes, a.graph().constraints(),
			a.graph().relocationActions(), a.graph().derivedFoutMaterializationActions());
		var invariant = Assert.assertThrows(IllegalArgumentException.class,
			() -> new PolicyGreedyPlacementSelector().select(a, foreignGraph));
		Assert.assertNotEquals("Foreign analysis ownership is an invariant error, not repair exhaustion",
			PolicyGreedyPlacementSelector.BoundedRepairExhaustedException.class, invariant.getClass());
	}

	@Test
	public void reconvergenceRepairsThePolicyChoiceAndRestoresConditionalSupports() {
		Node p = node(0), l = node(1), r = node(2);
		PlacementState local = p.legalAlternatives().stream().filter(s -> s.execType() == ExecType.CP).findFirst().orElseThrow();
		PlacementState fed = p.legalAlternatives().stream().filter(s -> s.execType() == ExecType.FED).findFirst().orElseThrow();
		var graph = new NeutralPlacementGraph(List.of(p,l,r), List.of(
			new Constraint(ConstraintKind.DOMINATES, p.key(), l.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, p.key(), r.key(), 0, "data-input"),
			forbid(p,l,fed,fed), forbid(p,r,fed,local), forbid(l,r,local,fed), forbid(l,r,fed,local)), List.of());
		Assert.assertNotNull("An existing feasible plan is not a greedy completeness guarantee",
			new PolicyFirstFeasiblePlacementSelector().select(graph));
		for(var policy : PolicyGreedyPlacementSelector.Policy.values()) {
			var selected = new PolicyGreedyPlacementSelector(policy).select(graph);
			Assert.assertSame("The conflicting FED policy row must be replaced", local,
				selected.assignment().get(p.key()));
			Assert.assertEquals("Rollback must restore the FED support removed by the failed branch", fed,
				selected.assignment().get(l.key()));
			Assert.assertEquals("Both restored OR alternatives remain usable", fed,
				selected.assignment().get(r.key()));
		}
	}

	@Test
	public void boundedRepairExhaustionIsDistinctFromExhaustiveInfeasibility() {
		Node p = node(0), l = node(1), r = node(2);
		PlacementState local = p.legalAlternatives().get(0), fed = p.legalAlternatives().get(1);
		var recoverable = new NeutralPlacementGraph(List.of(p,l,r), List.of(
			new Constraint(ConstraintKind.DOMINATES, p.key(), l.key(), 0, "data-input"),
			new Constraint(ConstraintKind.DOMINATES, p.key(), r.key(), 0, "data-input"),
			forbid(p,l,fed,fed), forbid(p,r,fed,local), forbid(l,r,local,fed), forbid(l,r,fed,local)), List.of());
		var exhausted = Assert.assertThrows(PolicyGreedyPlacementSelector.BoundedRepairExhaustedException.class,
			() -> new PolicyGreedyPlacementSelector(PolicyGreedyPlacementSelector.Policy.FED_FIRST, 0)
				.select(recoverable));
		Assert.assertTrue(exhausted.getMessage().contains("repairBudget=0"));

		Node a = node(10), b = node(11);
		List<Constraint> impossiblePairs = new ArrayList<>();
		for(PlacementState left : a.legalAlternatives())
			for(PlacementState right : b.legalAlternatives())
				impossiblePairs.add(forbid(a,b,left,right));
		var impossible = new NeutralPlacementGraph(List.of(a,b), impossiblePairs, List.of());
		var infeasible = Assert.assertThrows(PolicyGreedyPlacementSelector.NoSupportedPolicyWitnessException.class,
			() -> new PolicyGreedyPlacementSelector().select(impossible));
		Assert.assertTrue(infeasible.getMessage().contains("not global infeasibility"));
	}

	@Test
	public void directWdivmmRuntimeInputRelationKeepsOnlyCompatibleSelections() throws Exception {
		PlacementAnalysis analysis = wdivmmAnalysis(false);
		var owner = analysis.graph().decisionNodes().stream()
			.filter(node -> PlacementCostSemantics.directWdivmmRuntimeFact(analysis, node.key()) != null)
			.findFirst().orElseThrow();
		var runtime = PlacementCostSemantics.directWdivmmRuntimeFact(analysis, owner.key());
		var weights = analysis.graph().node(runtime.weights()).orElseThrow();
		for(var policy : PolicyGreedyPlacementSelector.Policy.values()) {
			var selected = new PolicyGreedyPlacementSelector(policy).select(analysis);
			PlacementState selectedOwner = selected.assignment().get(owner.key());
			PlacementState selectedWeights = selected.assignment().get(weights.key());
			Assert.assertTrue(PlacementCostSemantics.directWdivmmRuntimeAssignmentCompatible(
				runtime, selectedOwner, selectedWeights));
		}
	}

	@Test
	public void latentWdivmmOwnerKeepsItsExactRuntimeWeightsSupport() throws Exception {
		PlacementAnalysis analysis = wdivmmAnalysis(true);
		var owner = analysis.graph().decisionNodes().stream()
			.filter(node -> PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, node.key()) != null)
			.findFirst().orElseThrow();
		var runtime = PlacementCostSemantics.latentWdivmmTransposePairFact(analysis, owner.key());
		Assert.assertNotNull(runtime.partitionedInputFType());
		var selected = new PolicyGreedyPlacementSelector().select(analysis);
		PlacementState selectedOwner = selected.assignment().get(owner.key());
		PlacementState selectedWeights = selected.assignment().get(runtime.weights());
		Assert.assertTrue(selectedOwner.execType() != ExecType.FED
			|| selectedWeights.output() == FederatedOutput.FOUT
				&& selectedWeights.fType() == runtime.partitionedInputFType());
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

	private static PlacementAnalysis wdivmmAnalysis(boolean latent) throws Exception {
		String script = "W=federated(addresses=list(\"localhost:1234/W\",\"localhost:1235/W\"),"
			+ "ranges=list(list(0,0),list(50,20),list(50,0),list(100,20)));"
			+ "U=rand(rows=100,cols=2,seed=7);V=rand(rows=20,cols=2,seed=8);"
			+ "print(sum(W)+sum(U)+sum(V));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		List<Hop> hops = new ArrayList<>();
		for(var block : program.getStatementBlocks())
			for(Hop root : block.getHops()) collect(root, java.util.Collections.newSetFromMap(
				new java.util.IdentityHashMap<>()), hops);
		Hop weights = hops.stream().filter(hop -> hop instanceof DataOp data
			&& data.getOp() == OpOpData.FEDERATED).findFirst().orElseThrow();
		Hop u = hops.stream().filter(hop -> hop instanceof DataGenOp && hop.getDim1() == 100
			&& hop.getDim2() == 2).findFirst().orElseThrow();
		Hop v = hops.stream().filter(hop -> hop instanceof DataGenOp && hop.getDim1() == 20
			&& hop.getDim2() == 2).findFirst().orElseThrow();
		Hop weighted = HopRewriteUtils.createBinary(weights,
			HopRewriteUtils.createMatrixMultiply(u, HopRewriteUtils.createTranspose(v)), OpOp2.MULT);
		Hop owner = latent ? HopRewriteUtils.createTranspose(HopRewriteUtils.createMatrixMultiply(
			HopRewriteUtils.createTranspose(u), weighted))
			: HopRewriteUtils.createMatrixMultiply(weighted, v);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(List.of(new DataOp("H", DataType.MATRIX, ValueType.FP64,
			owner, OpOpData.TRANSIENTWRITE, "H"))));
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return CampaignBG014PlacementAuthorityTestBridge.bindAtFinalHopBoundary(program);
	}
	private static void collect(Hop hop, java.util.Set<Hop> seen, List<Hop> hops) {
		if(!seen.add(hop)) return;
		hop.setBlocksize(1000);
		hops.add(hop);
		for(Hop input : hop.getInput()) collect(input, seen, hops);
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
