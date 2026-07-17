/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.selector;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.ExactPlacementSelector;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementScore;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementSelection;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExactSelectorOracle;
import org.apache.sysds.test.component.federated.placement.oracle.selector.ExplicitSelectorGraph.Choice;
import org.apache.sysds.test.component.federated.placement.selector.IsomorphicSelectorContractFixtures.Case;
import org.junit.Assert;
import org.junit.Test;

/** Genuine RED contract for exact selection against independently constructed isomorphic universes. */
public class ExactPlacementSelectorContractTest {
	private static final PlacementState CP = new PlacementState(
		org.apache.sysds.common.Types.ExecType.CP,
		org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT, null, false);

	@Test
	public void mixedDecisionAndTraceGraphSelectsOnlyDecisionKeysAndRetainsFullProof() {
		NeutralPlacementGraph graph = mixedDecisionTraceGraph(false);
		Set<CompiledHopKey> expected = expectedDecisionKeys(graph);
		PlacementSelection actual;
		try {
			actual = new ExactPlacementSelector().select(graph);
		}
		catch(Throwable failure) {
			Assert.fail("DECISION_NODE_SELECTOR_THROW|" + failure.getClass().getName() + "|" + failure.getMessage());
			return;
		}
		Assert.assertEquals(expected, actual.assignment().keySet());
		Assert.assertEquals(graph.nodes().size(), actual.certificate().graphNodeCount());
		Assert.assertEquals(sha256(graph.normalizedSignature()), actual.certificate().graphFingerprint());
		Assert.assertEquals(independentAssignmentUniverseSize(graph),
			actual.certificate().exploredCount() + actual.certificate().prunedCount());
		Assert.assertEquals("trace constraints remain auditable", 1, graph.constraints().size());
		Assert.assertTrue("trace-only relocation must be placement-inactive", actual.selectedRelocations().isEmpty());
	}

	@Test
	public void emittedNodeWithEmptyAlternativesIsStructurallyRejected() {
		expectStructural("EMITTED_EMPTY", () -> node("bad-emitted", true, List.of()));
	}

	@Test
	public void traceNodeWithNonemptyAlternativesIsStructurallyRejected() {
		expectStructural("TRACE_NONEMPTY", () -> node("bad-trace", false, List.of(CP)));
	}

	@Test
	public void activeDecisionObligationCannotBeSourcedOnlyByTraceNode() {
		NeutralPlacementGraph graph = mixedDecisionTraceGraph(true);
		try {
			new ExactPlacementSelector().select(graph);
			Assert.fail("EXPECTED_STRUCTURAL_REJECTION|TRACE_SOURCE_FOR_DECISION_OBLIGATION");
		}
		catch(IllegalStateException failure) {
			Assert.assertEquals("decision relocation source is trace-only", failure.getMessage());
			Assert.assertFalse(failure.getMessage().contains("selectable graph node has no legal alternatives"));
		}
	}
	@Test
	public void exactSelectorMatchesFullIndependentS01ThroughS08Results() {
		ExactPlacementSelector selector = new ExactPlacementSelector();
		for(Case fixture : IsomorphicSelectorContractFixtures.all()) {
			ExactSelectorOracle.Result expected = ExactSelectorOracle.select(fixture.oracle(),
				ExactSelectorOracle.Policy.FED_ALL);
			PlacementSelection actual = selector.select(fixture.production());
			Assert.assertEquals(fixture.id(), expected.getScore().getFedCount(), actual.score().emittedFedCount());
			Assert.assertEquals(fixture.id(), expected.getScore().getFoutCount(), actual.score().foutCount());
			Assert.assertEquals(fixture.id(), expected.getScore().getRelocationCount(),
				actual.score().distinctRelocationCount());
			Assert.assertEquals(fixture.id(), semanticOracleAssignment(fixture, expected.getAssignment()),
				semanticProductionAssignment(actual.assignment()));
			Assert.assertEquals(fixture.id(), oracleRelocations(expected.getAssignment()),
				productionRelocations(actual.selectedRelocations()));
			String independentlyNormalized = productionSignature(actual.assignment(), actual.selectedRelocations());
			Assert.assertEquals(fixture.id(), independentlyNormalized, actual.score().normalizedSignature());
			assertExactCertificate(fixture, expected, actual);
		}
	}

	@Test
	public void scoreOrderIsFedThenFoutThenDistinctRelocationThenStableSignature() {
		PlacementScore base = new PlacementScore(1, 1, 1, "b");
		Assert.assertTrue(new PlacementScore(2, 0, 99, "z").compareTo(base) > 0);
		Assert.assertTrue(new PlacementScore(1, 2, 99, "z").compareTo(base) > 0);
		Assert.assertTrue(new PlacementScore(1, 1, 0, "z").compareTo(base) > 0);
		Assert.assertTrue(new PlacementScore(1, 1, 1, "a").compareTo(base) > 0);
	}

	@Test
	public void resultAndProofAreImmutableAndInsertionOrderDeterministic() {
		for(Case fixture : IsomorphicSelectorContractFixtures.all()) {
			NeutralPlacementGraph reversed = reversed(fixture.production());
			PlacementSelection first = new ExactPlacementSelector().select(fixture.production());
			PlacementSelection second = new ExactPlacementSelector().select(reversed);
			Assert.assertEquals(fixture.id(), first.score(), second.score());
			Assert.assertEquals(fixture.id(), first.assignment(), second.assignment());
			Assert.assertEquals(fixture.id(), first.selectedRelocations(), second.selectedRelocations());
			Assert.assertEquals(fixture.id(), first.certificate().assignmentHash(),
				second.certificate().assignmentHash());
			Assert.assertEquals(fixture.id(), first.certificate().exploredCount(),
				second.certificate().exploredCount());
			Assert.assertEquals(fixture.id(), first.certificate().prunedCount(),
				second.certificate().prunedCount());
		}
	}

	@Test
	public void onlyProofCompleteTerminationCanRepresentSuccess() {
		Assert.assertEquals(List.of("EXHAUSTED", "TIGHT_BOUND_EQUALITY"),
			java.util.Arrays.stream(PlacementCertificate.TerminationReason.values())
				.map(Enum::name).sorted().toList());
	}

	private static void assertExactCertificate(Case fixture, ExactSelectorOracle.Result oracle,
		PlacementSelection selection) {
		NeutralPlacementGraph graph = fixture.production();
		PlacementCertificate certificate = selection.certificate();
		Assert.assertEquals(fixture.id(), selection.score(), certificate.incumbentScore());
		Assert.assertTrue(fixture.id(), certificate.finalUpperBound().compareTo(selection.score()) <= 0);
		Assert.assertEquals(fixture.id(), sha256(selection.score().normalizedSignature()),
			certificate.assignmentHash());
		Assert.assertEquals(fixture.id(), sha256(graph.normalizedSignature()), certificate.graphFingerprint());
		Assert.assertEquals(fixture.id(), graph.nodes().size(), certificate.graphNodeCount());
		Assert.assertEquals(fixture.id(), graph.constraints().size(), certificate.graphEdgeCount());
		Assert.assertEquals(fixture.id(), componentCount(graph), certificate.componentCount());
		Assert.assertTrue(fixture.id(), certificate.exploredCount() >= 0);
		Assert.assertTrue(fixture.id(), certificate.prunedCount() >= 0);
		Assert.assertTrue(fixture.id(), certificate.exploredCount() + certificate.prunedCount() > 0);
		Assert.assertTrue(fixture.id(), certificate.closureDepth() >= 0);
		Assert.assertFalse(fixture.id(), certificate.boundDerivation().isBlank());
		Assert.assertEquals(fixture.id(), "production", certificate.generatorSizeClass());
		Assert.assertEquals(fixture.id(), -1L, certificate.generatorSeed());
		Assert.assertEquals(fixture.id(), fixture.oracle().getSizeClass(),
			oracle.getCertificate().getGeneratorSizeClass());
		Assert.assertEquals(fixture.id(), fixture.seed(), oracle.getCertificate().getSeed());
		Assert.assertEquals(fixture.id(), certificate.componentCount(), certificate.componentBounds().size());
		List<IndependentComponent> expectedComponents = independentComponents(graph);
		Assert.assertEquals(fixture.id(), expectedComponents.size(), certificate.componentBounds().size());
		for(IndependentComponent expected : expectedComponents) {
			Object bound = certificate.componentBounds().stream().filter(value -> expected.identity().equals(
				readText(value, "componentIdentity", "identity"))).findFirst().orElseThrow();
			Assert.assertEquals(fixture.id(), expected.nodes(), readStringSet(bound,
				"normalizedNodeSet", "nodeIdentities", "nodes"));
			Assert.assertEquals(fixture.id(), expected.nodes().size(), readLong(bound, "graphNodeCount", "nodeCount"));
			Assert.assertEquals(fixture.id(), expected.edgeCount(), readLong(bound, "graphEdgeCount", "edgeCount"));
			Assert.assertEquals(fixture.id(), expected.upperBound(), invoke(bound, "upperBound", "boundScore"));
			Assert.assertEquals(fixture.id(), expected.derivation(),
				readText(bound, "derivation", "boundDerivation"));
		}
		Assert.assertEquals(fixture.id(), independentAssignmentUniverseSize(graph),
			certificate.exploredCount() + certificate.prunedCount());
		Assert.assertTrue(fixture.id(), List.of("EXHAUSTED", "TIGHT_BOUND_EQUALITY")
			.contains(certificate.terminationReason().name()));
		Assert.assertEquals(fixture.id(), certificate.incumbentScore(), certificate.finalUpperBound());
	}

	private static String semanticOracleAssignment(Case fixture, Map<String,Choice> assignment) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((node, choice) -> entries.add(node + '='
			+ IsomorphicSelectorContractFixtures.productionChoice(fixture.id(), node, choice.getId())));
		Collections.sort(entries);
		return String.join("|", entries);
	}

	private static String semanticProductionAssignment(Map<CompiledHopKey,PlacementState> assignment) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((key, state) -> entries.add(key.canonicalSourceOrigin() + '=' + state.normalizedSignature()));
		Collections.sort(entries);
		return String.join("|", entries);
	}

	private static Set<String> oracleRelocations(Map<String,Choice> assignment) {
		Set<String> relocations = new java.util.TreeSet<>();
		assignment.values().forEach(choice -> relocations.addAll(choice.getRelocationActions()));
		return relocations;
	}

	private static Set<String> productionRelocations(Set<RelocationActionKey> relocations) {
		Set<String> normalized = new java.util.TreeSet<>();
		for(RelocationActionKey relocation : relocations)
			normalized.add(relocation.durableAnchor().placementId());
		return normalized;
	}

	private static String productionSignature(Map<CompiledHopKey,PlacementState> assignment,
		Set<RelocationActionKey> relocations) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((key, state) -> entries.add(key.normalizedSignature() + '=' + state.normalizedSignature()));
		Collections.sort(entries);
		List<String> actions = relocations.stream().map(RelocationActionKey::normalizedSignature)
			.sorted().toList();
		return String.join("|", entries) + "#" + String.join("|", actions);
	}

	private static int componentCount(NeutralPlacementGraph graph) {
		return independentComponents(graph).size();
	}

	private static List<IndependentComponent> independentComponents(NeutralPlacementGraph graph) {
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node node : graph.nodes())
			adjacency.put(node.key(), new LinkedHashSet<>());
		graph.constraints().forEach(constraint -> connect(adjacency, constraint.left(), constraint.right()));
		Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey,CompiledHopKey> valueOwners =
			new HashMap<>();
		for(Node node : graph.nodes())
			valueOwners.put(node.valueVersion(), node.key());
		graph.relocationActions().forEach(action -> {
			CompiledHopKey source = valueOwners.get(action.key().sourceValueVersion());
			for(CompiledHopKey consumer : action.key().compatibleConsumers())
				connect(adjacency, source, consumer);
		});
		Set<CompiledHopKey> visited = new HashSet<>();
		List<IndependentComponent> components = new ArrayList<>();
		for(CompiledHopKey node : adjacency.keySet()) {
			if(!visited.add(node))
				continue;
			Set<CompiledHopKey> members = new LinkedHashSet<>();
			Deque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.add(node);
			while(!pending.isEmpty()) {
				CompiledHopKey current = pending.removeFirst();
				members.add(current);
				for(CompiledHopKey adjacent : adjacency.get(current))
					if(visited.add(adjacent))
						pending.add(adjacent);
			}
			Set<String> normalizedNodes = new java.util.TreeSet<>();
			members.forEach(value -> normalizedNodes.add(value.normalizedSignature()));
			Set<String> edges = independentComponentEdges(graph, members, valueOwners);
			int maxFed = 0;
			int maxFout = 0;
			for(Node graphNode : graph.nodes())
				if(members.contains(graphNode.key())) {
					if(graphNode.emittedWork() && graphNode.legalAlternatives().stream()
						.anyMatch(state -> state.execType() == org.apache.sysds.common.Types.ExecType.FED))
						maxFed++;
					if(graphNode.legalAlternatives().stream().anyMatch(state -> state.output() ==
						org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT))
						maxFout++;
				}
			String nodeSignature = String.join("|", normalizedNodes);
			String identity = sha256(nodeSignature);
			String derivation = "nodewise-admissible:maxFed=" + maxFed + ",maxFout=" + maxFout
				+ ",minRelocations=0,nodes=" + nodeSignature;
			components.add(new IndependentComponent(identity, Set.copyOf(normalizedNodes), edges.size(),
				new PlacementScore(maxFed, maxFout, 0, nodeSignature), derivation));
		}
		components.sort(java.util.Comparator.comparing(IndependentComponent::identity));
		return List.copyOf(components);
	}

	private static Set<String> independentComponentEdges(NeutralPlacementGraph graph, Set<CompiledHopKey> members,
		Map<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey,CompiledHopKey> owners) {
		Set<String> edges = new java.util.TreeSet<>();
		graph.constraints().stream().filter(value -> members.contains(value.left()) && members.contains(value.right()))
			.forEach(value -> edges.add("constraint:" + value.normalizedSignature()));
		graph.relocationActions().forEach(action -> {
			CompiledHopKey source = owners.get(action.key().sourceValueVersion());
			for(CompiledHopKey consumer : action.key().compatibleConsumers())
				if(members.contains(source) && members.contains(consumer))
					edges.add("relocation:" + source.normalizedSignature() + "->" + consumer.normalizedSignature()
						+ ':' + action.key().normalizedSignature());
		});
		return edges;
	}

	private static long independentAssignmentUniverseSize(NeutralPlacementGraph graph) {
		long result = 1;
		for(Node node : graph.nodes()) {
			Assert.assertEquals("decision/trace polarity", node.emittedWork(), !node.legalAlternatives().isEmpty());
			if(!node.emittedWork())
				continue;
			result = Math.multiplyExact(result, node.legalAlternatives().size());
		}
		return result;
	}

	private static Set<CompiledHopKey> expectedDecisionKeys(NeutralPlacementGraph graph) {
		Set<CompiledHopKey> keys = new LinkedHashSet<>();
		for(Node node : graph.nodes()) {
			Assert.assertEquals("decision/trace polarity", node.emittedWork(), !node.legalAlternatives().isEmpty());
			if(node.emittedWork() && !node.legalAlternatives().isEmpty())
				keys.add(node.key());
		}
		return Set.copyOf(keys);
	}

	private static NeutralPlacementGraph mixedDecisionTraceGraph(boolean traceSourcesDecisionObligation) {
		Node decision = node("decision", true, List.of(CP));
		Node trace = node("trace", false, List.of());
		CompiledHopKey consumer = traceSourcesDecisionObligation ? decision.key() : trace.key();
		ValueVersionKey source = traceSourcesDecisionObligation ? trace.valueVersion() : decision.valueVersion();
		DurableAnchorKey anchor = new DurableAnchorKey("decision-red-anchor",
			org.apache.sysds.hops.fedplanner.FTypes.FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(1L, 1L))));
		RelocationActionKey actionKey = new RelocationActionKey(source, CP, anchor, "decision-red-scope",
			List.of(consumer));
		ObligationKey obligation = new ObligationKey(consumer, 0, source, CP, actionKey, "decision-red-scope");
		return new NeutralPlacementGraph(List.of(decision, trace),
			List.of(new Constraint(ConstraintKind.SAME_PLACEMENT, decision.key(), trace.key())),
			List.of(new RelocationAction(actionKey, List.of(obligation))));
	}

	private static Node node(String id, boolean emitted, List<PlacementState> alternatives) {
		ControlRegionKey region = new ControlRegionKey("decision-red", "main", List.of(id), id, "compiled");
		CompiledHopKey key = new CompiledHopKey("decision-red", "main", id, "compiled", region, id, id);
		ValueVersionKey value = new ValueVersionKey("decision-red", id, region, 0, VersionKind.ORDINARY, List.of());
		return new Node(key, NodeKind.OPERATION, value, emitted, alternatives, List.of(), List.of());
	}

	private static void expectStructural(String code, Runnable action) {
		try {
			action.run();
			Assert.fail("EXPECTED_STRUCTURAL_REJECTION|" + code);
		}
		catch(IllegalArgumentException failure) {
			Assert.assertFalse("structural rejection must differ from selector selectable-empty failure",
				String.valueOf(failure.getMessage()).contains("selectable graph node has no legal alternatives"));
		}
	}

	private record IndependentComponent(String identity, Set<String> nodes, long edgeCount,
		PlacementScore upperBound, String derivation) { }

	private static void connect(Map<CompiledHopKey,Set<CompiledHopKey>> adjacency,
		CompiledHopKey left, CompiledHopKey right) {
		adjacency.get(left).add(right);
		adjacency.get(right).add(left);
	}

	private static NeutralPlacementGraph reversed(NeutralPlacementGraph graph) {
		List<Node> nodes = new ArrayList<>(graph.nodes());
		List<NeutralPlacementGraph.Constraint> constraints = new ArrayList<>(graph.constraints());
		List<NeutralPlacementGraph.RelocationAction> relocations = new ArrayList<>(graph.relocationActions());
		Collections.reverse(nodes);
		Collections.reverse(constraints);
		Collections.reverse(relocations);
		return new NeutralPlacementGraph(nodes, constraints, relocations);
	}

	private static long readLong(Object value, String... accessors) {
		Object result = invoke(value, accessors);
		return ((Number) result).longValue();
	}

	private static String readText(Object value, String... accessors) {
		return String.valueOf(invoke(value, accessors));
	}

	private static Set<String> readStringSet(Object value, String... accessors) {
		Object result = invoke(value, accessors);
		if(!(result instanceof Iterable<?>))
			throw new AssertionError("component node set is not iterable: " + result);
		Set<String> normalized = new java.util.TreeSet<>();
		for(Object entry : (Iterable<?>) result)
			normalized.add(String.valueOf(entry));
		return Set.copyOf(normalized);
	}

	private static Object invoke(Object value, String... accessors) {
		for(String accessor : accessors) {
			try {
				Method method = value.getClass().getMethod(accessor);
				return method.invoke(value);
			}
			catch(ReflectiveOperationException ignored) {
				// try the representation-neutral alternative name
			}
		}
		throw new AssertionError("missing certificate component accessor " + List.of(accessors));
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for(byte value : digest)
				result.append(String.format("%02x", value));
			return result.toString();
		}
		catch(Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
