/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationEndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFactsProducer;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.runtime.io.MatrixWriterFactory;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;
import org.apache.sysds.runtime.util.HDFSTool;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Focused RED shadow for the public exact MinST selector over immutable producer facts. */
public class CampaignBR5MinStExactSelectorShadowRedTest {
	private static final String SELECTOR =
		"org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelector";
	private static final String RED_REASON = "G012_TASK20_RED_MINST_EXACT_SELECTOR_MISSING";

	@Test
	public void actualRootFactsHaveUniqueLiteralCutBeforeSelectorShadowComparison() throws Exception {
		PlacementAnalysis analysis = actualRootAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		Assert.assertSame("R5_MINST_FACTS_ANALYSIS_IDENTITY", analysis, facts.analysis());
		Assert.assertEquals("R5_MINST_FACTS_SCOPE_IDENTITY", scope(analysis), facts.orderedScope());

		CutSelection expected = enumerateUniqueMinimum(facts);
		List<PlacementState> expectedStates = selectedStates(facts, expected.sourceNodeIds());
		List<String> expectedObligations = selectedObligations(facts, expected.sourceNodeIds());

		Object actual = invokeSelector(facts);
		Assert.assertEquals("R5_MINST_SELECTOR_OBJECTIVE_BITS", expected.objectiveBits(),
			((Number)call(actual, "objectiveBits")).longValue());
		Assert.assertEquals("R5_MINST_SELECTOR_SOURCE_IDS", expected.sourceNodeIds(),
			longList(call(actual, "sourcePartitionNodeIds")));
		Assert.assertEquals("R5_MINST_SELECTOR_SELECTED_STATES", expectedStates,
			call(actual, "selectedStatesInScopeOrder"));
		Assert.assertEquals("R5_MINST_SELECTOR_OBLIGATIONS", expectedObligations,
			normalizedSelectedObligations(call(actual, "obligationReceiptsInOrder")));
	}

	@Test
	public void actualRootUploadAndDownloadSelectionsBindToAuthoritativeRelocationActions() throws Exception {
		PlacementAnalysis analysis = occurrenceAnalysis();
		MinStExactCostFacts facts = MinStExactCostFactsProducer.derive(analysis, scope(analysis));
		CompiledHopKey consumer = keyByHopName(analysis, "Yout1");
		AuxiliaryGroupFact upload = CampaignBR5MinStExactSelectorShadowRedTest
			.uploadGroupContaining(facts, consumer);
		AuxiliaryGroupFact download = CampaignBR5MinStExactSelectorShadowRedTest
			.transferGroupContaining(facts, Direction.DOWNLOAD, consumer, "X");

		List<String> uploadObligations = selectedObligations(facts, List.of(upload.auxiliaryNodeId()));
		List<String> downloadObligations = selectedObligations(facts,
			List.of(download.producerPlacementNodeId()));

		Assert.assertFalse("R5_MINST_ACTUAL_ROOT_UPLOAD_SELECTED_OBLIGATIONS_MISSING",
			uploadObligations.isEmpty());
		Assert.assertFalse("R5_MINST_ACTUAL_ROOT_DOWNLOAD_SELECTED_OBLIGATIONS_MISSING",
			downloadObligations.isEmpty());
		Assert.assertEquals("R5_MINST_ACTUAL_ROOT_UPLOAD_ENDPOINT_COVERAGE",
			upload.endpointsInCanonicalOrder().size(), uploadObligations.size());
		Assert.assertEquals("R5_MINST_ACTUAL_ROOT_DOWNLOAD_ENDPOINT_COVERAGE",
			download.endpointsInCanonicalOrder().size(), downloadObligations.size());
	}

	private static CutSelection enumerateUniqueMinimum(MinStExactCostFacts facts) {
		List<Long> freeNodes = freeNonDecisionNodes(facts);
		List<List<List<Long>>> decisionChoices = facts.decisionFactsInScopeOrder().stream()
			.map(CampaignBR5MinStExactSelectorShadowRedTest::legalDecisionNodeChoices).toList();
		long legalCutCount = (1L << freeNodes.size());
		for(List<List<Long>> choices : decisionChoices)
			legalCutCount = Math.multiplyExact(legalCutCount, choices.size());
		Assert.assertTrue("R5_MINST_FIXTURE_TOO_BROAD_FOR_EXACT_SHADOW|legalCuts=" + legalCutCount,
			legalCutCount <= 1_000_000L);

		List<CutSelection> minima = new ArrayList<>();
		double[] minimum = new double[] {Double.POSITIVE_INFINITY};
		enumerateDecisionCuts(facts, decisionChoices, 0, new ArrayList<>(), freeNodes,
			minimum, minima);
		Assert.assertEquals("R5_MINST_ACTUAL_ROOT_MINIMUM_NOT_UNIQUE|objective=" + minimum[0]
			+ "|minima=" + minima, 1, minima.size());
		return minima.get(0);
	}

	private static void enumerateDecisionCuts(MinStExactCostFacts facts,
		List<List<List<Long>>> decisionChoices, int decisionIndex, List<Long> source,
		List<Long> freeNodes, double[] minimum, List<CutSelection> minima) {
		if(decisionIndex < decisionChoices.size()) {
			for(List<Long> choice : decisionChoices.get(decisionIndex)) {
				int size = source.size();
				source.addAll(choice);
				enumerateDecisionCuts(facts, decisionChoices, decisionIndex + 1,
					source, freeNodes, minimum, minima);
				source.subList(size, source.size()).clear();
			}
			return;
		}
		long limit = 1L << freeNodes.size();
		for(long mask = 0; mask < limit; mask++) {
			List<Long> candidate = new ArrayList<>(source);
			for(int index = 0; index < freeNodes.size(); index++)
				if((mask & (1L << index)) != 0)
					candidate.add(freeNodes.get(index));
			candidate = candidate.stream().sorted().toList();
			long objectiveBits = cutBits(facts, candidate);
			double objective = Double.longBitsToDouble(objectiveBits);
			if(Double.compare(objective, minimum[0]) < 0) {
				minimum[0] = objective;
				minima.clear();
			}
			if(Double.compare(objective, minimum[0]) == 0)
				minima.add(new CutSelection(objectiveBits, candidate));
		}
	}

	private static List<List<Long>> legalDecisionNodeChoices(DecisionFact decision) {
		List<List<Long>> choices = new ArrayList<>();
		for(PlacementState state : decision.legalStatesInCanonicalOrder()) {
			boolean computeSource = state.execType() == ExecType.FED;
			boolean placementSource = state.output() == FederatedOutput.FOUT;
			if(computeSource && placementSource)
				choices.add(List.of(decision.computeNodeId(), decision.placementNodeId()));
			else if(computeSource)
				choices.add(List.of(decision.computeNodeId()));
			else if(placementSource)
				choices.add(List.of(decision.placementNodeId()));
			else
				choices.add(List.of());
		}
		return choices;
	}

	private static List<Long> freeNonDecisionNodes(MinStExactCostFacts facts) {
		Set<Long> fixed = new LinkedHashSet<>();
		for(DecisionFact decision : facts.decisionFactsInScopeOrder()) {
			fixed.add(decision.computeNodeId());
			fixed.add(decision.placementNodeId());
		}
		return nonTerminalNodes(facts).stream().filter(node -> !fixed.contains(node)).toList();
	}

	private static List<Long> nonTerminalNodes(MinStExactCostFacts facts) {
		Set<Long> nodes = new LinkedHashSet<>();
		for(DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder()) {
			nodes.add(edge.fromNodeId());
			nodes.add(edge.toNodeId());
		}
		nodes.remove(facts.sourceNodeId());
		nodes.remove(facts.sinkNodeId());
		return nodes.stream().sorted().toList();
	}

	private static long cutBits(MinStExactCostFacts facts, List<Long> sourceNodeIds) {
		double total = 0.0;
		for(DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder()) {
			double capacity = canonicalCapacity(edge.capacityBits(), edge);
			boolean fromSource = edge.fromNodeId() == facts.sourceNodeId()
				|| sourceNodeIds.contains(edge.fromNodeId());
			boolean toSource = edge.toNodeId() != facts.sinkNodeId()
				&& sourceNodeIds.contains(edge.toNodeId());
			if(fromSource && !toSource) {
				total += capacity;
				if(!Double.isFinite(total) || total < 0.0
					|| Double.doubleToRawLongBits(total) == Double.doubleToRawLongBits(-0.0))
					throw new AssertionError("R5_MINST_CUT_TOTAL_NOT_CANONICAL|total=" + total);
			}
		}
		return Double.doubleToRawLongBits(total);
	}

	private static double canonicalCapacity(long capacityBits, DirectedEdgeFact edge) {
		double capacity = Double.longBitsToDouble(capacityBits);
		if(!Double.isFinite(capacity) || capacity < 0.0
			|| capacityBits == Double.doubleToRawLongBits(-0.0))
			throw new AssertionError("R5_MINST_EDGE_CAPACITY_NOT_CANONICAL|from="
				+ edge.fromNodeId() + "|to=" + edge.toNodeId() + "|capacity=" + capacity);
		return capacity;
	}

	private static List<PlacementState> selectedStates(MinStExactCostFacts facts,
		List<Long> sourceNodeIds) {
		List<PlacementState> states = new ArrayList<>();
		for(DecisionFact decision : facts.decisionFactsInScopeOrder()) {
			ExecType exec = sourceNodeIds.contains(decision.computeNodeId()) ? ExecType.FED : ExecType.CP;
			FederatedOutput output = sourceNodeIds.contains(decision.placementNodeId())
				? FederatedOutput.FOUT : FederatedOutput.LOUT;
			List<PlacementState> matches = decision.legalStatesInCanonicalOrder().stream()
				.filter(state -> state.execType() == exec && state.output() == output).toList();
			Assert.assertEquals("R5_MINST_SELECTED_STATE_NOT_LEGAL|key="
				+ decision.key().normalizedSignature() + "|exec=" + exec + "|output=" + output,
				1, matches.size());
			states.add(matches.get(0));
		}
		return List.copyOf(states);
	}

	private static List<String> selectedObligations(MinStExactCostFacts facts,
		List<Long> sourceNodeIds) {
		List<String> result = new ArrayList<>();
		for(AuxiliaryGroupFact group : facts.auxiliaryGroupsInCanonicalOrder()) {
			boolean auxSource = sourceNodeIds.contains(group.auxiliaryNodeId());
			boolean producerPlacementSource = sourceNodeIds.contains(group.producerPlacementNodeId());
			if(group.direction() == Direction.UPLOAD && auxSource && !producerPlacementSource)
				addGroupObligations(result, facts, group, Direction.UPLOAD);
			if(group.direction() == Direction.DOWNLOAD && producerPlacementSource && !auxSource)
				addGroupObligations(result, facts, group, Direction.DOWNLOAD);
		}
		return result.stream().sorted().toList();
	}

	private static void addGroupObligations(List<String> result, MinStExactCostFacts facts,
		AuxiliaryGroupFact group, Direction direction) {
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			PlacementState expected = exactGraphRequiredPlacement(facts, group, endpoint);
			AuthoritativeObligation obligation = authoritativeObligation(facts, group, endpoint, expected);
			result.add(obligationSignature(direction.name(), group.producerKey(), endpoint.consumerKey(),
				endpoint.inputPosition(), obligation.endpoint().requiredPlacement()));
		}
	}

	private static AuthoritativeObligation authoritativeObligation(MinStExactCostFacts facts,
		AuxiliaryGroupFact group, EndpointFact endpoint, PlacementState expectedPlacement) {
		assertEndpointBoundToProducerAndCanonicalEdge(facts, group, endpoint);
		List<AuthoritativeObligation> matches = new ArrayList<>();
		for(ObligationFact obligation : facts.obligationFactsInCanonicalOrder()) {
			String actionSignature = obligation.actionSignature();
			Assert.assertNotNull("R5_MINST_OBLIGATION_ACTION_SIGNATURE_MISSING",
				actionSignature);
			for(ObligationEndpointFact candidate : obligation.endpointsInCanonicalOrder())
				if(candidate.consumerKey().equals(endpoint.consumerKey())
					&& candidate.inputPosition() == endpoint.inputPosition()
					&& candidate.requiredPlacement().equals(expectedPlacement)) {
					NeutralPlacementGraph.RelocationAction action = exactActionForSignature(facts, group,
						actionSignature);
					if(actionContainsExactObligation(action, candidate))
						matches.add(new AuthoritativeObligation(actionSignature, candidate));
				}
		}
		if(matches.size() != 1)
			throw new AssertionError("R5_MINST_AUTHORITY_OBLIGATION_"
				+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS")
				+ "|direction=" + group.direction()
				+ "|producer=" + group.producerKey().normalizedSignature()
				+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
				+ "|input=" + endpoint.inputPosition()
				+ "|expectedPlacement=" + expectedPlacement.normalizedSignature()
				+ "|actions=" + matches.stream().map(AuthoritativeObligation::actionSignature).toList());
		return matches.get(0);
	}

	private static NeutralPlacementGraph.RelocationAction exactActionForSignature(MinStExactCostFacts facts,
		AuxiliaryGroupFact group, String actionSignature) {
		List<NeutralPlacementGraph.RelocationAction> actions = facts.analysis().graph().relocationActions().stream()
			.filter(action -> action.normalizedSignature().equals(actionSignature))
			.toList();
		if(actions.size() != 1)
			throw new AssertionError("R5_MINST_AUTHORITY_ACTION_"
				+ (actions.isEmpty() ? "MISSING" : "AMBIGUOUS")
				+ "|producer=" + group.producerKey().normalizedSignature()
				+ "|signature=" + actionSignature + "|matches=" + actions.size());
		NeutralPlacementGraph.RelocationAction action = actions.get(0);
		Assert.assertEquals("R5_MINST_AUTHORITY_ACTION_SOURCE_VALUE_DRIFT",
			facts.analysis().graph().node(group.producerKey()).orElseThrow().valueVersion(),
			action.key().sourceValueVersion());
		return action;
	}

	private static PlacementState exactGraphRequiredPlacement(MinStExactCostFacts facts,
		AuxiliaryGroupFact group, EndpointFact endpoint) {
		List<PlacementState> placements = new ArrayList<>();
		for(NeutralPlacementGraph.RelocationAction action : facts.analysis().graph().relocationActions()) {
			if(!action.key().sourceValueVersion().equals(
				facts.analysis().graph().node(group.producerKey()).orElseThrow().valueVersion()))
				continue;
			for(ObligationKey obligation : action.obligations())
				if(obligation.consumer().equals(endpoint.consumerKey())
					&& obligation.inputPosition() == endpoint.inputPosition()) {
					Assert.assertEquals("R5_MINST_AUTHORITY_ACTION_TARGET_DRIFT",
						action.key().targetPlacement(), obligation.requiredPlacement());
					placements.add(obligation.requiredPlacement());
				}
		}
		List<PlacementState> unique = placements.stream().distinct().toList();
		if(unique.size() != 1)
			throw new AssertionError("R5_MINST_AUTHORITY_EXPECTED_PLACEMENT_"
				+ (unique.isEmpty() ? "MISSING" : "AMBIGUOUS")
				+ "|direction=" + group.direction()
				+ "|producer=" + group.producerKey().normalizedSignature()
				+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
				+ "|input=" + endpoint.inputPosition()
				+ "|placements=" + unique.stream().map(PlacementState::normalizedSignature).toList());
		return unique.get(0);
	}

	private static boolean actionContainsExactObligation(NeutralPlacementGraph.RelocationAction action,
		ObligationEndpointFact candidate) {
		for(ObligationKey obligation : action.obligations())
			if(obligation.consumer().equals(candidate.consumerKey())
				&& obligation.inputPosition() == candidate.inputPosition()
				&& obligation.requiredPlacement().equals(candidate.requiredPlacement())
				&& obligation.sourceValueVersion().equals(action.key().sourceValueVersion())
				&& obligation.relocationAction().equals(action.key()))
				return true;
		return false;
	}

	private static void assertEndpointBoundToProducerAndCanonicalEdge(MinStExactCostFacts facts,
		AuxiliaryGroupFact group, EndpointFact endpoint) {
		Assert.assertSame("R5_MINST_ENDPOINT_GROUP_PRODUCER_IDENTITY_DRIFT",
			group.producerKey(), endpoint.producerKey());
		Assert.assertTrue("R5_MINST_ENDPOINT_PRODUCER_HOP_MISSING",
			facts.analysis().hop(group.producerKey()).isPresent());
		CompiledInputEdgeFact edge = facts.analysis().requireExactCompiledInputEdge(
			group.producerKey(), endpoint.consumerKey(), endpoint.inputPosition());
		Assert.assertSame("R5_MINST_ENDPOINT_CANONICAL_EDGE_PRODUCER_DRIFT",
			group.producerKey(), edge.producer());
		Assert.assertSame("R5_MINST_ENDPOINT_CANONICAL_EDGE_CONSUMER_DRIFT",
			endpoint.consumerKey(), edge.consumer());
		Assert.assertEquals("R5_MINST_ENDPOINT_CANONICAL_EDGE_INPUT_DRIFT",
			endpoint.inputPosition(), edge.inputPosition());
		assertCanonicalEdge(facts.analysis(), endpoint);
	}

	private static Object invokeSelector(MinStExactCostFacts facts) throws Exception {
		try {
			Class<?> selector = Class.forName(SELECTOR);
			Method select = selector.getMethod("select", MinStExactCostFacts.class);
			Assert.assertTrue("R5_MINST_SELECTOR_SELECT_MUST_BE_PUBLIC", Modifier.isPublic(select.getModifiers()));
			if(Modifier.isStatic(select.getModifiers()))
				return select.invoke(null, facts);
			Constructor<?> ctor = selector.getConstructor();
			Assert.assertTrue("R5_MINST_SELECTOR_CTOR_MUST_BE_PUBLIC", Modifier.isPublic(ctor.getModifiers()));
			return select.invoke(ctor.newInstance(), facts);
		}
		catch(ClassNotFoundException | NoSuchMethodException ex) {
			Assert.fail(RED_REASON);
			throw new AssertionError(ex);
		}
		catch(InvocationTargetException ex) {
			throw new AssertionError("R5_MINST_SELECTOR_INVOCATION_FAILED", ex.getCause());
		}
	}

	private static List<String> normalizedSelectedObligations(Object raw) throws Exception {
		List<String> result = new ArrayList<>();
		for(Object obligation : (List<?>)raw) {
			Object direction = callAny(obligation, "direction", "kind");
			CompiledHopKey producer = (CompiledHopKey)callAny(obligation, "producerKey", "producer");
			CompiledHopKey consumer = (CompiledHopKey)callAny(obligation, "consumerKey", "consumer");
			int inputPosition = ((Number)call(obligation, "inputPosition")).intValue();
			PlacementState requiredPlacement = (PlacementState)callAny(obligation,
				"requiredPlacement", "targetPlacement");
			result.add(obligationSignature(direction.toString(), producer, consumer,
				inputPosition, requiredPlacement));
		}
		return result.stream().sorted().toList();
	}

	private static String obligationSignature(String direction, CompiledHopKey producer,
		CompiledHopKey consumer, int inputPosition, PlacementState requiredPlacement) {
		return direction + "|producer=" + producer.normalizedSignature()
			+ "|consumer=" + consumer.normalizedSignature()
			+ "|input=" + inputPosition
			+ "|required=" + requiredPlacement.normalizedSignature();
	}

	private static Object call(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static Object callAny(Object target, String... methods) throws Exception {
		for(String method : methods) {
			try {
				return call(target, method);
			}
			catch(NoSuchMethodException ignored) {
				// try the next selector receipt spelling
			}
		}
		throw new NoSuchMethodException(target.getClass().getName() + "." + List.of(methods));
	}

	private static List<Long> longList(Object raw) {
		return ((List<?>)raw).stream().map(value -> ((Number)value).longValue()).toList();
	}

	private static CompiledHopKey keyByHopName(PlacementAnalysis analysis, String name) {
		return scope(analysis).stream()
			.filter(key -> analysis.hop(key).map(hop -> name.equals(hop.getName())).orElse(false))
			.findFirst().orElseThrow(() -> new AssertionError(
				"R5_MINST_ACTUAL_ROOT_HOP_MISSING|name=" + name));
	}

	private static List<CompiledHopKey> scope(PlacementAnalysis analysis) {
		return analysis.compiledHopOccurrences().stream()
			.map(HopOccurrenceProjection::key).toList();
	}


	private static void assertCanonicalEdge(PlacementAnalysis analysis, EndpointFact endpoint) {
		CompiledInputEdgeFact edge = analysis.requireExactCompiledInputEdge(endpoint.producerKey(),
			endpoint.consumerKey(), endpoint.inputPosition());
		Assert.assertSame("R5_MINST_ENDPOINT_PRODUCER_IDENTITY_DRIFT", endpoint.producerKey(), edge.producer());
		Assert.assertSame("R5_MINST_ENDPOINT_CONSUMER_IDENTITY_DRIFT", endpoint.consumerKey(), edge.consumer());
		Assert.assertEquals("R5_MINST_ENDPOINT_INPUT_POSITION_DRIFT",
			endpoint.inputPosition(), edge.inputPosition());
	}

	private static AuxiliaryGroupFact uploadGroupContaining(MinStExactCostFacts facts,
		CompiledHopKey consumer) {
		return facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == Direction.UPLOAD)
			.filter(group -> group.endpointsInCanonicalOrder().stream()
				.anyMatch(endpoint -> endpoint.consumerKey() == consumer))
			.findFirst().orElseThrow(() -> new AssertionError(
				"R5_MINST_UPLOAD_GROUP_MISSING|consumer=" + consumer.normalizedSignature()));
	}

	private static AuxiliaryGroupFact transferGroupContaining(MinStExactCostFacts facts,
		Direction direction, CompiledHopKey consumer, String producerName) {
		return facts.auxiliaryGroupsInCanonicalOrder().stream()
			.filter(group -> group.direction() == direction)
			.filter(group -> facts.analysis().hop(group.producerKey())
				.map(hop -> producerName.equals(hop.getName())).orElse(false))
			.filter(group -> group.endpointsInCanonicalOrder().stream()
				.anyMatch(endpoint -> endpoint.consumerKey() == consumer))
			.findFirst().orElseThrow(() -> new AssertionError(
				"R5_MINST_TRANSFER_GROUP_MISSING|direction=" + direction
					+ "|producer=" + producerName + "|consumer=" + consumer.normalizedSignature()));
	}

	private static PlacementAnalysis occurrenceAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r5-occurrence-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"S=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"Yout1=X+S;", "Yout2=X-S;",
				"write(Yout1,\"out-1\",format=\"binary\");",
				"write(Yout2,\"out-2\",format=\"binary\");",
				"for(i in 1:2) {",
				"  Sloop=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"  Xloop=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"  Yloop1=Xloop-Sloop;", "  Yloop2=Xloop+Sloop;",
				"  write(Yloop1,\"out-loop-1\",format=\"binary\");",
				"  write(Yloop2,\"out-loop-2\",format=\"binary\");", "}") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis functionLoopAnalysis() throws Exception {
		Path directory = Files.createTempDirectory("minst-r5-function-loop-");
		String input = directory.resolve("S").toString();
		writePrivateLocalMatrix(input);
		try {
			String script = String.join("\n",
				"f=function(integer n) return(matrix[double] Y){",
				"  for(i in 1:n) {",
				"    Sloop=read(\"" + input + "\",data_type=\"matrix\",value_type=\"double\","
					+ "rows=4,cols=2,format=\"binary\");",
				"    Xloop=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
					+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
				"    Y=Xloop-Sloop;", "  }", "}", "Z=f(2);",
				"write(Z,\"out-function\",format=\"binary\");") + "\n";
			return buildAnalysis(script);
		}
		finally {
			HDFSTool.deleteFileIfExistOnHDFS(input);
			HDFSTool.deleteFileIfExistOnHDFS(input + ".mtd");
			Files.deleteIfExists(directory);
		}
	}

	private static PlacementAnalysis buildAnalysis(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static void writePrivateLocalMatrix(String path) throws Exception {
		MatrixBlock block = new MatrixBlock(4, 2, 3.0);
		MatrixCharacteristics characteristics = new MatrixCharacteristics(4, 2, 1024,
			block.getNonZeros());
		MatrixWriterFactory.createMatrixWriter(FileFormat.BINARY).writeMatrixToHDFS(block, path,
			4, 2, 1024, block.getNonZeros());
		HDFSTool.writeMetaDataFile(path + ".mtd", ValueType.FP64, null, DataType.MATRIX,
			characteristics, FileFormat.BINARY, null, "private");
	}

	private static PlacementAnalysis actualRootAnalysis() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\")," +
				"ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));",
			"write(X,\"out-r5-selector-shadow\",format=\"binary\");") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private record AuthoritativeObligation(String actionSignature, ObligationEndpointFact endpoint) { }

	private record CutSelection(long objectiveBits, List<Long> sourceNodeIds) {
		CutSelection {
			sourceNodeIds = List.copyOf(sourceNodeIds.stream().sorted().toList());
		}
	}
}
