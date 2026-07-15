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
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
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
			Assert.assertEquals(fixture.id(), semanticOracleAssignment(expected.getAssignment()),
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
		long componentNodes = certificate.componentBounds().stream()
			.mapToLong(bound -> readLong(bound, "graphNodeCount", "nodeCount")).sum();
		Assert.assertEquals(fixture.id(), graph.nodes().size(), componentNodes);
		for(Object bound : certificate.componentBounds()) {
			Assert.assertFalse(fixture.id(), readText(bound, "componentIdentity", "identity").isBlank());
			Assert.assertTrue(fixture.id(), readLong(bound, "graphNodeCount", "nodeCount") > 0);
			Assert.assertTrue(fixture.id(), readLong(bound, "graphEdgeCount", "edgeCount") >= 0);
			Assert.assertFalse(fixture.id(), readText(bound, "derivation", "boundDerivation").isBlank());
			Object upper = invoke(bound, "upperBound", "boundScore");
			Assert.assertTrue(fixture.id(), upper instanceof PlacementScore);
		}
		Assert.assertTrue(fixture.id(), List.of("EXHAUSTED", "TIGHT_BOUND_EQUALITY")
			.contains(certificate.terminationReason().name()));
	}

	private static String semanticOracleAssignment(Map<String,Choice> assignment) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((node, choice) -> entries.add(node + '=' + choice.getId()));
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
		int components = 0;
		for(CompiledHopKey node : adjacency.keySet()) {
			if(!visited.add(node))
				continue;
			components++;
			Deque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.add(node);
			while(!pending.isEmpty())
				for(CompiledHopKey adjacent : adjacency.get(pending.removeFirst()))
					if(visited.add(adjacent))
						pending.add(adjacent);
		}
		return components;
	}

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
