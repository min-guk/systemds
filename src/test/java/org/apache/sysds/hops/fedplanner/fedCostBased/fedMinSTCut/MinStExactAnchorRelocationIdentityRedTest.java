/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact relocation identity contract for equal FType across distinct durable anchors. */
public class MinStExactAnchorRelocationIdentityRedTest {
	private static final PlacementState FED_ROW =
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, true);
	private static final CompiledHopKey PRODUCER = key("producer");
	private static final CompiledHopKey CONSUMER = key("consumer");
	private static final CompiledHopKey CONSUMER_B = key("consumer-b");
	private static final ValueVersionKey PRODUCER_VERSION = version("producer-version");
	private static final ValueVersionKey CONSUMER_VERSION = version("consumer-version");
	private static final ValueVersionKey CONSUMER_B_VERSION = version("consumer-b-version");
	private static final DurableAnchorKey ANCHOR_A = anchor("anchor-a", "worker-a");
	private static final DurableAnchorKey ANCHOR_B = anchor("anchor-b", "worker-b");

	@Test
	public void equalFTypeAtDifferentAnchorStillRequiresExactUpload() {
		RelocationAction crossAnchor = action(ANCHOR_B, CONSUMER);
		NeutralPlacementGraph graph = graph(crossAnchor);
		Map<CompiledHopKey, PlacementState> selected = Map.of(PRODUCER, FED_ROW, CONSUMER, FED_ROW);

		Assert.assertEquals(ANCHOR_A.fType(), ANCHOR_B.fType());
		Assert.assertNotEquals(ANCHOR_A, ANCHOR_B);
		Assert.assertTrue("P4_CROSS_ANCHOR_RELOCATION_MUST_BE_ACTIVE",
			graph.isRelocationActive(crossAnchor, selected));
		var choices = RelocationSelections.selectCanonical(graph, selected);
		var resolved = RelocationSelections.resolveAndValidate(graph, selected, choices);
		Assert.assertEquals("P4_CROSS_ANCHOR_EXACT_DEMAND_COUNT", 1, resolved.size());
		Assert.assertTrue("P4_CROSS_ANCHOR_EXACT_DEMAND_REQUIRES_EMISSION",
			resolved.get(0).requiresEmission());
		Assert.assertEquals("P4_CROSS_ANCHOR_EXACT_ACTION",
			List.of(crossAnchor.key()), RelocationSelections.emittedActions(graph, selected, choices));
		Assert.assertFalse("MINST_DIRECT_FOUT_MUST_REJECT_EQUAL_FTYPE_AT_DIFFERENT_ANCHOR",
			MinStExactPhysicalModel.directFoutSatisfied(graph, graph.node(PRODUCER).orElseThrow(),
				CONSUMER, 0, FED_ROW, FType.ROW, selected));
	}

	@Test
	public void equalFTypeAtExactSameAnchorSuppressesRedundantUpload() {
		RelocationAction sameAnchor = action(ANCHOR_A, CONSUMER);
		NeutralPlacementGraph graph = graph(sameAnchor);
		Map<CompiledHopKey, PlacementState> selected = Map.of(PRODUCER, FED_ROW, CONSUMER, FED_ROW);

		Assert.assertFalse("P4_SAME_ANCHOR_RELOCATION_MUST_BE_DIRECT",
			graph.isRelocationActive(sameAnchor, selected));
		var choices = RelocationSelections.selectCanonical(graph, selected);
		var resolved = RelocationSelections.resolveAndValidate(graph, selected, choices);
		Assert.assertEquals("P4_SAME_ANCHOR_EXACT_DEMAND_COUNT", 1, resolved.size());
		Assert.assertFalse("P4_SAME_ANCHOR_EXACT_DEMAND_MUST_NOT_EMIT",
			resolved.get(0).requiresEmission());
		Assert.assertTrue("P4_SAME_ANCHOR_EMITTED_UPLOAD_MUST_BE_EMPTY",
			RelocationSelections.emittedActions(graph, selected, choices).isEmpty());
		Assert.assertTrue("MINST_DIRECT_FOUT_MUST_ACCEPT_EXACT_SAME_ANCHOR",
			MinStExactPhysicalModel.directFoutSatisfied(graph, graph.node(PRODUCER).orElseThrow(),
				CONSUMER, 0, FED_ROW, FType.ROW, selected));
	}

	@Test
	public void groupedSameAndCrossAnchorEndpointsRetainExactlyOneRequiredUpload() {
		RelocationAction sameAnchor = action(ANCHOR_A, CONSUMER);
		RelocationAction crossAnchor = action(ANCHOR_B, CONSUMER_B);
		NeutralPlacementGraph graph = groupedGraph(sameAnchor, crossAnchor);
		Map<CompiledHopKey, PlacementState> selected =
			Map.of(PRODUCER, FED_ROW, CONSUMER, FED_ROW, CONSUMER_B, FED_ROW);

		var choices = RelocationSelections.selectCanonical(graph, selected);
		var resolved = RelocationSelections.resolveAndValidate(graph, selected, choices);
		Assert.assertEquals("P4_GROUPED_EXACT_DEMAND_COUNT", 2, resolved.size());
		Assert.assertEquals("P4_GROUPED_REQUIRED_EMISSION_COUNT", 1L,
			resolved.stream().filter(RelocationSelections.ResolvedChoice::requiresEmission).count());
		Assert.assertEquals("P4_GROUPED_CROSS_ANCHOR_ACTION_ONLY", List.of(crossAnchor.key()),
			RelocationSelections.emittedActions(graph, selected, choices));
	}

	@Test
	public void oneConsumerCannotMixDirectReceiptsFromDifferentAnchors() {
		CompiledHopKey producerB = key("producer-b");
		ValueVersionKey producerBVersion = version("producer-b-version");
		Node producerA = new Node(PRODUCER, NodeKind.OPERATION, PRODUCER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node producerBNode = new Node(producerB, NodeKind.OPERATION, producerBVersion, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_B));
		Node consumer = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of());
		RelocationAction aToA = action(PRODUCER_VERSION, ANCHOR_A, CONSUMER, 0);
		RelocationAction aToB = action(PRODUCER_VERSION, ANCHOR_B, CONSUMER, 0);
		RelocationAction bToA = action(producerBVersion, ANCHOR_A, CONSUMER, 1);
		RelocationAction bToB = action(producerBVersion, ANCHOR_B, CONSUMER, 1);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(
			List.of(producerA, producerBNode, consumer), List.of(),
			List.of(aToA, aToB, bToA, bToB));
		Map<CompiledHopKey,PlacementState> selected =
			Map.of(PRODUCER, FED_ROW, producerB, FED_ROW, CONSUMER, FED_ROW);

		var choices = RelocationSelections.selectCanonical(graph, selected);
		var resolved = RelocationSelections.resolveAndValidate(graph, selected, choices);

		Assert.assertEquals("both physical inputs retain explicit exact receipts", 2, resolved.size());
		Assert.assertEquals("one input must be moved to the other input's common worker pool", 1L,
			resolved.stream().filter(RelocationSelections.ResolvedChoice::requiresEmission).count());
		Assert.assertEquals("all receipts for one FED consumer use one durable anchor", 1L,
			resolved.stream().map(choice -> choice.action().key().durableAnchor()).distinct().count());

		List<RelocationChoiceReceipt> invalidMixedDirect = List.of(
			new RelocationChoiceReceipt(new RelocationDemandKey(PRODUCER_VERSION, CONSUMER, 0,
				FED_ROW, "scope"), aToA.key()),
			new RelocationChoiceReceipt(new RelocationDemandKey(producerBVersion, CONSUMER, 1,
				FED_ROW, "scope"), bToB.key()));
		Assert.assertThrows("externally supplied mixed-anchor receipts must also fail closed",
			IllegalArgumentException.class,
			() -> RelocationSelections.resolveAndValidate(graph, selected, invalidMixedDirect));
	}

	@Test
	public void physicalAlternativesBindEveryPresentInputToOneExactCommonAnchor() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileTwoFederatedSourceFixture());
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		var consumer = model.domains().stream().filter(domain -> analysis.hop(domain.node().key())
			.map(hop -> "C".equals(hop.getName())).orElse(false)).findFirst().orElseThrow();
		var presentFed = consumer.alternatives().stream().filter(alternative ->
			alternative.state().execType() == ExecType.FED
				&& alternative.orderedInputs().size() == 2
				&& alternative.orderedInputs().stream().allMatch(PlacementAnalysis.CandidateInputState::present))
			.toList();
		Assert.assertFalse("fixture must retain FED alternatives for the two-source consumer", presentFed.isEmpty());
		for(var alternative : presentFed) {
			Assert.assertEquals("every PRESENT matrix edge needs one exact receipt authority",
				2, alternative.inputAuthorities().size());
			Assert.assertTrue("direct authority must name the exact graph-owned receipt action",
				alternative.inputAuthorities().stream().allMatch(authority ->
					authority.relocationAction() != null));
			Assert.assertEquals("one FED consumer cannot mix exact input anchors",
				1L, alternative.inputAuthorities().stream()
					.map(authority -> authority.relocationAction().key().durableAnchor()).distinct().count());
			for(var authority : alternative.inputAuthorities()) {
				var edge = analysis.compiledInputEdgesInCanonicalOrder().stream().filter(candidate ->
					candidate.consumer() == consumer.node().key()
						&& candidate.inputPosition() == authority.inputPosition()).findFirst().orElseThrow();
				Assert.assertEquals("receipt action must remain bound to its exact producer value/version",
					analysis.graph().node(edge.producer()).orElseThrow().valueVersion(),
					authority.relocationAction().key().sourceValueVersion());
			}
		}
	}

	@Test
	public void physicalCostAndProjectionRetainTheChosenAnchorEmissionIdentity() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(
			compileTwoFederatedSourceFixture());
		MinStExactPhysicalModel model = MinStExactPhysicalModel.build(analysis);
		MinStExactCostFactsProducer.PhysicalCostSurface surface =
			MinStExactCostFactsProducer.physicalCostSurface(analysis, model);
		var consumer = model.domains().stream().filter(domain -> analysis.hop(domain.node().key())
			.map(hop -> "C".equals(hop.getName())).orElse(false)).findFirst().orElseThrow();
		List<DurableAnchorKey> anchors = analysis.graph().relocationActions().stream()
			.filter(action -> action.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == consumer.node().key()))
			.map(action -> action.key().durableAnchor()).distinct().sorted().toList();
		Assert.assertEquals("fixture must expose both exact common-anchor physical alternatives",
			2, anchors.size());

		for(var producer : model.domains().stream().filter(domain -> analysis.hop(domain.node().key())
			.map(hop -> "A".equals(hop.getName()) || "B".equals(hop.getName())).orElse(false)).toList()) {
			var uploadKeys = surface.transferKeys().stream()
				.filter(key -> key.direction() == MinStExactCostFacts.Direction.UPLOAD)
				.filter(key -> key.sourceValueVersion().equals(producer.node().valueVersion()))
				.filter(key -> key.endpoints().stream().anyMatch(endpoint ->
					endpoint.consumer() == consumer.node().key()))
				.toList();
			var expectedIdentities = analysis.graph().relocationActions().stream()
				.filter(action -> action.key().sourceValueVersion().equals(producer.node().valueVersion()))
				.filter(action -> action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == consumer.node().key()))
				.map(action -> RelocationSelections.physicalEmissionIdentity(action.key()))
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			var actualIdentities = uploadKeys.stream()
				.map(MinStExactCostFactsProducer.PhysicalTransferKey::physicalEmissionIdentity)
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
			Assert.assertEquals("cost factors must retain every graph-owned physical emission identity",
				expectedIdentities, actualIdentities);
			Assert.assertEquals("equal FType emissions to two anchors must not be coalesced", 2,
				analysis.graph().relocationActions().stream()
					.filter(action -> action.key().sourceValueVersion().equals(producer.node().valueVersion()))
					.filter(action -> action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == consumer.node().key()))
					.map(action -> action.key().durableAnchor()).distinct().count());
		}

		for(DurableAnchorKey anchor : anchors) {
			double[] forceCosts = new double[consumer.alternatives().size()];
			java.util.Arrays.fill(forceCosts, Double.POSITIVE_INFINITY);
			for(int value = 0; value < consumer.alternatives().size(); value++) {
				var alternative = consumer.alternatives().get(value);
				if(alternative.state().execType() == ExecType.FED
					&& alternative.orderedInputs().size() == 2
					&& alternative.orderedInputs().stream()
						.allMatch(PlacementAnalysis.CandidateInputState::present)
					&& alternative.inputAuthorities().size() == 2
					&& alternative.inputAuthorities().stream().allMatch(authority ->
						authority.relocationAction() != null
							&& authority.relocationAction().key().durableAnchor().equals(anchor)))
					forceCosts[value] = 0.0;
			}
			List<MinStExactCategoricalSolver.Factor> factors = new java.util.ArrayList<>(model.hardFactors());
			factors.addAll(surface.factors());
			factors.add(MinStExactCategoricalSolver.Factor.dense(
				List.of(consumer.variable()), forceCosts));
			MinStExactCategoricalSolver.Result solved = MinStExactCategoricalSolver.solve(
				model.variables(), factors, MinStExactPhysicalOptimizer.PRODUCTION_LIMITS);
			long objectiveBits = surface.evaluateCanonical(solved.assignmentInVariableOrder());
			Assert.assertEquals("zero-cost forcing factor must not change the canonical objective",
				objectiveBits, Double.doubleToRawLongBits(solved.objective()));
			MinStExactPhysicalSelection selected = MinStExactPhysicalSelection.create(model,
				new MinStExactPhysicalOptimizer.Result(solved, objectiveBits,
					surface.contributionFingerprint()));
			var projected = MinStExactPhysicalPlacementProjector.project(selected);
			MinStExactPhysicalModel.Alternative forced = selected.alternativesInDecisionOrder()
				.get(model.domains().indexOf(consumer));

			Assert.assertTrue("forced common-anchor domain must survive exact selection",
				forced.inputAuthorities().stream().allMatch(authority ->
					authority.relocationAction().key().durableAnchor().equals(anchor)));
			Assert.assertEquals("one source must be direct on the chosen pool", 1L,
				forced.inputAuthorities().stream().filter(authority -> authority.kind()
					== MinStExactPhysicalModel.InputAuthorityKind.DIRECT_FOUT).count());
			Assert.assertEquals("the other source must be explicitly relocated", 1L,
				forced.inputAuthorities().stream().filter(authority -> authority.kind()
					== MinStExactPhysicalModel.InputAuthorityKind.RELOCATION).count());
			List<RelocationActionKey> expectedReceipts = forced.inputAuthorities().stream()
				.map(authority -> authority.relocationAction().key()).sorted().toList();
			List<RelocationActionKey> selectedReceipts = selected.relocationChoices().stream()
				.filter(choice -> choice.demand().consumer() == consumer.node().key())
				.map(RelocationChoiceReceipt::action).sorted().toList();
			Assert.assertEquals("projection must retain both exact input receipts",
				expectedReceipts, selectedReceipts);
			List<RelocationActionKey> expectedEmitted = forced.inputAuthorities().stream()
				.filter(authority -> authority.kind()
					== MinStExactPhysicalModel.InputAuthorityKind.RELOCATION)
				.map(authority -> authority.relocationAction().key()).toList();
			List<RelocationActionKey> projectedEmitted = projected.normalizedResult()
				.selectedRelocations().stream()
				.filter(action -> action.compatibleConsumers().contains(consumer.node().key())).toList();
			Assert.assertEquals("projector must emit exactly the chosen cross-anchor action",
				expectedEmitted, projectedEmitted);
		}
	}

	private static NeutralPlacementGraph graph(RelocationAction action) {
		Node producer = new Node(PRODUCER, NodeKind.OPERATION, PRODUCER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumer = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(action.key().durableAnchor()));
		return new NeutralPlacementGraph(List.of(producer, consumer), List.of(), List.of(action));
	}

	private static NeutralPlacementGraph groupedGraph(RelocationAction sameAnchor,
		RelocationAction crossAnchor) {
		Node producer = new Node(PRODUCER, NodeKind.OPERATION, PRODUCER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumerA = new Node(CONSUMER, NodeKind.OPERATION, CONSUMER_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_A));
		Node consumerB = new Node(CONSUMER_B, NodeKind.OPERATION, CONSUMER_B_VERSION, true,
			List.of(FED_ROW), List.of(), List.of(ANCHOR_B));
		return new NeutralPlacementGraph(List.of(producer, consumerA, consumerB), List.of(),
			List.of(sameAnchor, crossAnchor));
	}

	private static RelocationAction action(DurableAnchorKey targetAnchor, CompiledHopKey consumer) {
		return action(PRODUCER_VERSION, targetAnchor, consumer, 0);
	}

	private static RelocationAction action(ValueVersionKey source, DurableAnchorKey targetAnchor,
		CompiledHopKey consumer, int inputPosition) {
		RelocationActionKey key = new RelocationActionKey(source, FED_ROW, targetAnchor,
			"scope", List.of(consumer));
		return new RelocationAction(key, List.of(new ObligationKey(consumer, inputPosition, source,
			FED_ROW, key, "scope")));
	}

	private static DurableAnchorKey anchor(String placementId, String worker) {
		return new DurableAnchorKey(placementId, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static CompiledHopKey key(String id) {
		ControlRegionKey region = region();
		return new CompiledHopKey("program", "ns", "call", "rc", region, id, id);
	}

	private static ValueVersionKey version(String id) {
		return new ValueVersionKey("program", id, region(), 0, VersionKind.ORDINARY, List.of());
	}

	private static ControlRegionKey region() {
		return new ControlRegionKey("program", "ns", List.of("main/0"), "call", "rc");
	}

	private static DMLProgram compileTwoFederatedSourceFixture() throws Exception {
		String ranges = "list(list(0,0),list(2,2),list(2,0),list(4,2))";
		String script = "A=federated(addresses=list(\"localhost:1234/A1\",\"localhost:1235/A2\"),"
			+ "ranges=" + ranges + ");\n"
			+ "B=federated(addresses=list(\"localhost:2234/B1\",\"localhost:2235/B2\"),"
			+ "ranges=" + ranges + ");\n"
			+ "C=A+B;\nwrite(C,\"/tmp/g014-minst-two-pool-C\",format=\"binary\");\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
