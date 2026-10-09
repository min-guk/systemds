/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class NativePlacementPruneOwnerTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"native-prune", "main", List.of("root"), "root", "compiled");

	@Test
	public void sharedAlternativeIsRemovedIndependentlyForEveryOwner() throws Exception {
		SearchSpaceMetrics sharedMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity sharedContinuity = continuity(sharedMetrics);
		Object witness = witness();
		CompiledHopKey deadKey = key("dead");
		CompiledHopKey sharedOwnerKey = key("shared-owner");
		CompiledHopKey leftAncestorKey = key("left-ancestor");
		CompiledHopKey rightAncestorKey = key("right-ancestor");
		Object dead = state(deadKey, 1, witness);
		Object left = state(sharedOwnerKey, 0, witness);
		Object right = state(sharedOwnerKey, 2, witness);
		Object leftAncestor = state(leftAncestorKey, 4, witness);
		Object rightAncestor = state(rightAncestorKey, 5, witness);
		Object shared = alternative(List.of(dependency(deadKey, 1, witness)), witness);
		Map<Object,List<Object>> sharedGraph = graph(
			dead, List.of(),
			left, List.of(shared),
			right, List.of(shared),
			leftAncestor, List.of(alternative(List.of(dependency(sharedOwnerKey, 0, witness)), witness)),
			rightAncestor, List.of(alternative(List.of(dependency(sharedOwnerKey, 2, witness)), witness)));

		Map<?,?> sharedPruned = prune(sharedContinuity, sharedGraph);

		Assert.assertSame("pruning mutates only graph values in a fresh result map",
			sharedGraph.keySet().iterator().next(), sharedPruned.keySet().iterator().next());
		assertAllEmpty(sharedPruned);
		Assert.assertEquals("each owner-list alternative is scanned exactly once",
			4, sharedMetrics.snapshot().ownerCompactionElementsScanned());

		SearchSpaceMetrics unsharedMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity unsharedContinuity = continuity(unsharedMetrics);
		Map<Object,List<Object>> unsharedGraph = graph(
			dead, List.of(),
			left, List.of(alternative(List.of(dependency(deadKey, 1, witness)), witness)),
			right, List.of(alternative(List.of(dependency(deadKey, 1, witness)), witness)),
			leftAncestor, List.of(alternative(List.of(dependency(sharedOwnerKey, 0, witness)), witness)),
			rightAncestor, List.of(alternative(List.of(dependency(sharedOwnerKey, 2, witness)), witness)));
		Map<?,?> unsharedPruned = prune(unsharedContinuity, unsharedGraph);
		assertAllEmpty(unsharedPruned);
		Assert.assertEquals(unsharedPruned, sharedPruned);
		Assert.assertEquals(4, unsharedMetrics.snapshot().ownerCompactionElementsScanned());
	}

	@Test
	public void completeNonemptyGraphReturnsOriginalMapWithoutLosingScanMetrics() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = continuity(metrics);
		Object witness = witness();
		CompiledHopKey groundedKey = key("grounded");
		Object grounded = state(groundedKey, 1, witness);
		Object owner = state(key("owner"), 2, witness);
		Map<Object,List<Object>> graph = graph(
			grounded, List.of(alternative(List.of(), witness)),
			owner, List.of(alternative(List.of(dependency(groundedKey, 1, witness)), witness)));

		Map<?,?> pruned = prune(continuity, graph);

		Assert.assertSame("a graph with no dead seed or missing dependency needs no copy", graph, pruned);
		Assert.assertEquals(2, metrics.snapshot().ownerCompactionElementsScanned());
	}

	@Test
	public void untouchedWideOwnerSkipsCompactionWorkBesideRemovedPeer() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Object witness = witness();
		CompiledHopKey deadKey = key("untouched-wide-dead");
		Object dead = state(deadKey, 1, witness);
		CompiledHopKey liveKey = key("untouched-wide-live");
		Object live = state(liveKey, 2, witness);
		Object grounded = alternative(List.of(), witness);
		Object wideOwner = state(key("untouched-wide-owner"), 3, witness);
		Object doomedOwner = state(key("untouched-wide-doomed"), 4, witness);
		Object doomed = alternative(List.of(dependency(deadKey, 1, witness)), witness);
		List<Object> alternatives = new ArrayList<>();
		for(int slot = 0; slot < 512; slot++)
			alternatives.add(alternative(List.of(dependency(liveKey, 2, witness)), witness));
		CountingList<Object> wide = new CountingList<>(alternatives);
		Map<Object,List<Object>> input = graph(
			dead, List.of(), live, List.of(grounded), wideOwner, wide, doomedOwner, List.of(doomed));

		Map<?,?> actual = prune(continuity(metrics), input);

		Assert.assertSame("an owner with no removed alternative must retain its exact list",
			wide, actual.get(wideOwner));
		Assert.assertTrue("the peer that reads the dead state must still be removed",
			((List<?>)actual.get(doomedOwner)).isEmpty());
		Assert.assertEquals("only the two dense-index construction passes read the live alternatives",
			2 * wide.size(), wide.getCount());
		Assert.assertEquals(wide.size() + 2,
			metrics.snapshot().ownerCompactionElementsScanned());
		Assert.assertEquals(wide.size() + 1,
			directMetric(metrics, "PRUNE_UNCHANGED_OWNER_SLOTS_SKIPPED"));
	}

	@Test
	public void missingDependencyPrunesItsTransitiveAncestors() throws Exception {
		Object witness = witness();
		CompiledHopKey missingKey = key("missing");
		CompiledHopKey ownerKey = key("owner");
		Object owner = state(ownerKey, 1, witness);
		Object ancestor = state(key("ancestor"), 2, witness);
		Map<Object,List<Object>> missingGraph = graph(
			owner, List.of(alternative(List.of(dependency(missingKey, 9, witness)), witness)),
			ancestor, List.of(alternative(List.of(dependency(ownerKey, 1, witness)), witness)));
		Map<?,?> missingPruned = prune(continuity(new SearchSpaceMetrics()), missingGraph);
		assertAllEmpty(missingPruned);
	}

	@Test
	public void closedCycleRetainsPhysicallyViableAlternatives() throws Exception {
		Object witness = witness();
		CompiledHopKey leftKey = key("cycle-left");
		CompiledHopKey rightKey = key("cycle-right");
		Object left = state(leftKey, 3, witness);
		Object right = state(rightKey, 4, witness);
		Map<Object,List<Object>> closedCycle = graph(
			left, List.of(alternative(List.of(dependency(rightKey, 4, witness)), witness)),
			right, List.of(alternative(List.of(dependency(leftKey, 3, witness)), witness)));
		Map<?,?> cyclePruned = prune(continuity(new SearchSpaceMetrics()), closedCycle);
		Assert.assertSame("physical viability pruning retains cycle alternatives; program validity is a frontend obligation",
			closedCycle, cyclePruned);
	}

	@Test
	public void selfCycleAndRepeatedDependencyEdgesMatchFrozenPropagation() throws Exception {
		Object witness = witness();
		CompiledHopKey deadKey = key("repeated-dead"), selfKey = key("self-cycle");
		Object dead = state(deadKey, 1, witness), self = state(selfKey, 2, witness);
		Object removed = alternative(List.of(dependency(deadKey, 1, witness),
			dependency(deadKey, 1, witness)), witness);
		Object selfCycle = alternative(List.of(dependency(selfKey, 2, witness),
			dependency(selfKey, 2, witness)), witness);
		Map<Object,List<Object>> dependencies = new IdentityHashMap<>();
		dependencies.put(removed, List.of(dead, dead));
		dependencies.put(selfCycle, List.of(self, self));
		Map<Object,List<Object>> graph = graph(dead, List.of(), self, List.of(selfCycle),
			state(key("removed-owner"), 3, witness), List.of(removed));
		long[] expectedCounts = new long[4];
		Map<Object,List<Object>> expected = legacyPrune(graph, dependencies, expectedCounts);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Map<?,?> actual = prune(continuity(metrics), graph);
		assertIdenticalOrderedGraph("repeated dependency edges", expected, actual);
		Assert.assertSame("the self-cycle alternative stays live by identity",
			selfCycle, ((List<?>)actual.get(self)).get(0));
		Assert.assertEquals(expectedCounts[0], metrics.snapshot().ownerCompactionElementsScanned());
		Assert.assertEquals(expectedCounts[1], metrics.snapshot().deadStatesQueued());
		Assert.assertEquals(expectedCounts[2], metrics.snapshot().dependencyNotifications());
		Assert.assertEquals(expectedCounts[3], metrics.snapshot().alternativesRemoved());
	}

	@Test
	public void duplicateObjectSlotsRetainLegacyOwnerCountButDistinctCopiesDoNot() throws Exception {
		Object witness = witness();
		CompiledHopKey deadKey = key("duplicate-dead"), ownerKey = key("duplicate-owner");
		Object dead = state(deadKey, 1, witness), owner = state(ownerKey, 2, witness);
		Object ancestor = state(key("duplicate-ancestor"), 3, witness);
		List<Object> dependencies = List.of(dependency(deadKey, 1, witness));
		Object shared = alternative(dependencies, witness);
		List<Object> ancestorAlternatives = List.of(alternative(
			List.of(dependency(ownerKey, 2, witness)), witness));
		SearchSpaceMetrics sharedMetrics = new SearchSpaceMetrics();
		Map<?,?> sharedResult = prune(continuity(sharedMetrics), graph(dead, List.of(),
			owner, List.of(shared, shared), ancestor, ancestorAlternatives));
		Assert.assertTrue(((List<?>) sharedResult.get(owner)).isEmpty());
		Assert.assertSame("legacy counts duplicate slots but removes their object identity only once",
			ancestorAlternatives, sharedResult.get(ancestor));
		Assert.assertEquals(1, sharedMetrics.snapshot().deadStatesQueued());
		Assert.assertEquals(2, sharedMetrics.snapshot().dependencyNotifications());
		Assert.assertEquals(1, sharedMetrics.snapshot().alternativesRemoved());

		SearchSpaceMetrics distinctMetrics = new SearchSpaceMetrics();
		Map<?,?> distinctResult = prune(continuity(distinctMetrics), graph(dead, List.of(),
			owner, List.of(shared, alternative(dependencies, witness)), ancestor, ancestorAlternatives));
		assertAllEmpty(distinctResult);
		Assert.assertEquals(3, distinctMetrics.snapshot().deadStatesQueued());
		Assert.assertEquals(3, distinctMetrics.snapshot().dependencyNotifications());
		Assert.assertEquals(3, distinctMetrics.snapshot().alternativesRemoved());
	}

	@Test
	public void wideOwnerThenSingletonsAndSmallDuplicatesMatchFrozenPropagation() throws Exception {
		Object witness = witness();
		CompiledHopKey deadKey = key("capacity-dead"), liveKey = key("capacity-live");
		Object dead = state(deadKey, 1, witness), live = state(liveKey, 2, witness);
		Map<Object,List<Object>> dependencies = new IdentityHashMap<>();
		Map<Object,List<Object>> input = new LinkedHashMap<>();
		input.put(dead, List.of());
		Object ground = alternative(List.of(), witness);
		dependencies.put(ground, List.of());
		input.put(live, List.of(ground));

		List<Object> wide = new ArrayList<>();
		for(int slot = 0; slot < 512; slot++) {
			Object alternative = alternative(List.of(dependency(liveKey, 2, witness)), witness);
			dependencies.put(alternative, List.of(live));
			wide.add(alternative);
		}
		input.put(state(key("capacity-wide"), 3, witness), List.copyOf(wide));
		for(int owner = 0; owner < 128; owner++) {
			Object singleton = alternative(List.of(dependency(liveKey, 2, witness)), witness);
			dependencies.put(singleton, List.of(live));
			input.put(state(key("capacity-singleton-" + owner), owner + 4, witness),
				List.of(singleton));
		}

		CompiledHopKey duplicateOwnerKey = key("capacity-duplicate-owner");
		Object duplicateOwner = state(duplicateOwnerKey, 200, witness);
		List<Object> deadDependencies = List.of(dependency(deadKey, 1, witness));
		Object shared = alternative(deadDependencies, witness);
		Object equalDistinct = alternative(deadDependencies, witness);
		Assert.assertEquals(shared, equalDistinct);
		Assert.assertNotSame(shared, equalDistinct);
		dependencies.put(shared, List.of(dead));
		dependencies.put(equalDistinct, List.of(dead));
		input.put(duplicateOwner, List.of(shared, shared, equalDistinct));
		Object ancestor = state(key("capacity-ancestor"), 201, witness);
		Object ancestorAlternative = alternative(
			List.of(dependency(duplicateOwnerKey, 200, witness)), witness);
		dependencies.put(ancestorAlternative, List.of(duplicateOwner));
		input.put(ancestor, List.of(ancestorAlternative));

		Map<Object,List<Object>> original = new LinkedHashMap<>(input);
		long[] expectedCounts = new long[4];
		Map<Object,List<Object>> expected = legacyPrune(input, dependencies, expectedCounts);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Map<?,?> actual = prune(continuity(metrics), input);

		assertIdenticalOrderedGraph("wide/singleton capacity history", expected, actual);
		assertIdenticalOrderedGraph("capacity-history input remains immutable", original, input);
		Assert.assertSame("duplicate slots retain the frozen owner live-count behavior",
			ancestorAlternative, ((List<?>)actual.get(ancestor)).get(0));
		Assert.assertEquals(expectedCounts[0], metrics.snapshot().ownerCompactionElementsScanned());
		Assert.assertEquals(expectedCounts[1], metrics.snapshot().deadStatesQueued());
		Assert.assertEquals(expectedCounts[2], metrics.snapshot().dependencyNotifications());
		Assert.assertEquals(expectedCounts[3], metrics.snapshot().alternativesRemoved());
	}

	@Test
	public void collidingWitnessHashesDoNotAliasDifferentStates() throws Exception {
		Object firstWitness = witness("Aa:1234"), secondWitness = witness("BB:1234");
		CompiledHopKey sharedKey = key("hash-collision");
		Object dead = state(sharedKey, 1, firstWitness);
		Object live = state(sharedKey, 1, secondWitness);
		Assert.assertEquals(dead.hashCode(), live.hashCode());
		Assert.assertNotEquals(dead, live);
		Object deadOwner = state(key("dead-owner"), 2, firstWitness);
		Object liveOwner = state(key("live-owner"), 2, secondWitness);
		List<Object> liveAlternatives = List.of(alternative(List.of(), secondWitness));
		List<Object> liveOwnerAlternatives = List.of(alternative(
			List.of(dependency(sharedKey, 1, secondWitness)), secondWitness));
		Map<?,?> result = prune(continuity(null), graph(dead, List.of(), live, liveAlternatives,
			deadOwner, List.of(alternative(List.of(dependency(sharedKey, 1, firstWitness)), firstWitness)),
			liveOwner, liveOwnerAlternatives));
		Assert.assertTrue(((List<?>) result.get(deadOwner)).isEmpty());
		Assert.assertSame(liveAlternatives, result.get(live));
		Assert.assertSame(liveOwnerAlternatives, result.get(liveOwner));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void randomizedPropagationMatchesFrozenOwnerIdentityAlgorithm() throws Exception {
		Random random = new Random(0x5EED1357L);
		for(int trial = 0; trial < 300; trial++) {
			int owners = 1 + random.nextInt(18);
			Object poolWitness = witness();
			List<CompiledHopKey> keys = new ArrayList<>();
			List<Object> states = new ArrayList<>();
			for(int index = 0; index < owners + 3; index++) {
				keys.add(key("trial-" + trial + "-state-" + index));
				states.add(state(keys.get(index), index + 1, poolWitness));
			}
			Map<Object,List<Object>> dependencies = new IdentityHashMap<>();
			List<Object> sharedAlternatives = new ArrayList<>();
			Map<Object,List<Object>> input = new LinkedHashMap<>();
			for(int owner = 0; owner < owners; owner++) {
				List<Object> alternatives = new ArrayList<>();
				int count = random.nextInt(7);
				for(int slot = 0; slot < count; slot++) {
					if(!sharedAlternatives.isEmpty() && random.nextInt(5) == 0) {
						// Reuse both across owners and, occasionally, within one owner.
						alternatives.add(sharedAlternatives.get(random.nextInt(sharedAlternatives.size())));
						continue;
					}
					List<Object> actualDependencies = new ArrayList<>();
					List<Object> expectedDependencies = new ArrayList<>();
					int degree = random.nextInt(5);
					for(int edge = 0; edge < degree; edge++) {
						int target = random.nextInt(states.size());
						actualDependencies.add(dependency(keys.get(target), target + 1, poolWitness));
						// A distinct but equal state checks exact map equality, not identity indexing.
						expectedDependencies.add(state(keys.get(target), target + 1, poolWitness));
						if(random.nextInt(4) == 0) {
							actualDependencies.add(dependency(keys.get(target), target + 1, poolWitness));
							expectedDependencies.add(state(keys.get(target), target + 1, poolWitness));
						}
					}
					Object value = alternative(List.copyOf(actualDependencies), poolWitness);
					dependencies.put(value, List.copyOf(expectedDependencies));
					sharedAlternatives.add(value);
					alternatives.add(value);
					if(random.nextInt(6) == 0) {
						// Equal records must not share the removed-object bit.
						Object equalCopy = alternative(List.copyOf(actualDependencies), poolWitness);
						dependencies.put(equalCopy, List.copyOf(expectedDependencies));
						alternatives.add(equalCopy);
					}
				}
				input.put(states.get(owner), List.copyOf(alternatives));
			}
			Map<Object,List<Object>> original = new LinkedHashMap<>(input);
			long[] expectedCounts = new long[4];
			Map<Object,List<Object>> expected = legacyPrune(input, dependencies, expectedCounts);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			Map<?,?> actual = prune(continuity(metrics), input);
			assertIdenticalOrderedGraph("trial " + trial, expected, actual);
			assertIdenticalOrderedGraph("unmodified input " + trial, original, input);
			for(Object state : original.keySet())
				Assert.assertSame(original.get(state), input.get(state));
			Assert.assertEquals(expectedCounts[0], metrics.snapshot().ownerCompactionElementsScanned());
			Assert.assertEquals(expectedCounts[1], metrics.snapshot().deadStatesQueued());
			Assert.assertEquals(expectedCounts[2], metrics.snapshot().dependencyNotifications());
			Assert.assertEquals(expectedCounts[3], metrics.snapshot().alternativesRemoved());
			assertIdenticalOrderedGraph("metrics off " + trial, expected, prune(continuity(null), input));
			if(expected == input)
				Assert.assertSame(input, actual);

			// The production builder includes every dependency row. Complete the random
			// graph with explicit dead rows and exercise the same certificate-bearing
			// entry point, including cycles, repeated edges and shared object slots.
			Map<Object,List<Object>> closed = new LinkedHashMap<>(input);
			for(Object state : states)
				closed.putIfAbsent(state, List.of());
			SearchSpaceMetrics conservativeMetrics = new SearchSpaceMetrics();
			SearchSpaceMetrics certifiedMetrics = new SearchSpaceMetrics();
			Map<Object,List<Object>> conservative =
				(Map<Object,List<Object>>)prune(continuity(conservativeMetrics), closed);
			Map<?,?> certified = certifiedPrune(continuity(certifiedMetrics), closed);
			assertIdenticalOrderedGraph("certified closed graph " + trial,
				conservative, certified);
			Assert.assertEquals("all logical pruning counters remain equal",
				conservativeMetrics.snapshot(), certifiedMetrics.snapshot());
		}
	}

	private static void assertIdenticalOrderedGraph(String label,
		Map<Object,List<Object>> expected, Map<?,?> actual) {
		List<Object> expectedKeys = new ArrayList<>(expected.keySet());
		List<?> actualKeys = new ArrayList<>(actual.keySet());
		Assert.assertEquals(label, expectedKeys.size(), actualKeys.size());
		for(int index = 0; index < expectedKeys.size(); index++) {
			Object key = expectedKeys.get(index);
			Assert.assertSame(label + " key identity/order", key, actualKeys.get(index));
			List<Object> expectedValues = expected.get(key);
			List<?> actualValues = (List<?>) actual.get(key);
			Assert.assertEquals(label, expectedValues.size(), actualValues.size());
			for(int slot = 0; slot < expectedValues.size(); slot++)
				Assert.assertSame(label + " survivor identity/order", expectedValues.get(slot), actualValues.get(slot));
		}
	}

	/** Frozen r1 map/set propagation oracle; intentionally independent of dense indices. */
	private static Map<Object,List<Object>> legacyPrune(Map<Object,List<Object>> graph,
		Map<Object,List<Object>> dependencies, long[] counts) {
		boolean hasDeadSeed = false;
		for(var entry : graph.entrySet()) {
			counts[0] += entry.getValue().size();
			hasDeadSeed |= entry.getValue().isEmpty();
			if(!hasDeadSeed)
				for(Object alternative : entry.getValue())
					for(Object dependency : dependencies.get(alternative))
						if(!graph.containsKey(dependency))
							hasDeadSeed = true;
		}
		if(!hasDeadSeed)
			return graph;
		Map<Object,List<LegacyDependent>> reverse = new HashMap<>();
		Map<Object,Integer> liveCounts = new HashMap<>();
		for(var entry : graph.entrySet()) {
			liveCounts.put(entry.getKey(), entry.getValue().size());
			for(Object alternative : entry.getValue())
				for(Object dependency : new HashSet<>(dependencies.get(alternative)))
					reverse.computeIfAbsent(dependency, ignored -> new ArrayList<>())
						.add(new LegacyDependent(entry.getKey(), alternative));
		}
		Map<Object,List<Object>> viable = new LinkedHashMap<>(graph);
		ArrayDeque<Object> dead = new ArrayDeque<>();
		Set<Object> knownDead = new HashSet<>();
		for(var entry : viable.entrySet())
			if(entry.getValue().isEmpty() && knownDead.add(entry.getKey())) {
				dead.addLast(entry.getKey());
				counts[1]++;
			}
		for(Object dependency : reverse.keySet())
			if(!viable.containsKey(dependency) && knownDead.add(dependency)) {
				dead.addLast(dependency);
				counts[1]++;
			}
		Map<Object,Set<Object>> removedByOwner = new HashMap<>();
		while(!dead.isEmpty())
			for(LegacyDependent dependent : reverse.getOrDefault(dead.removeFirst(), List.of())) {
				counts[2]++;
				Set<Object> removed = removedByOwner.computeIfAbsent(dependent.owner(),
					ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
				if(!removed.add(dependent.alternative()))
					continue;
				counts[3]++;
				int live = liveCounts.compute(dependent.owner(), (ignored, count) -> count - 1);
				if(live == 0 && knownDead.add(dependent.owner())) {
					dead.addLast(dependent.owner());
					counts[1]++;
				}
			}
		for(var entry : removedByOwner.entrySet())
			viable.put(entry.getKey(), viable.get(entry.getKey()).stream()
				.filter(alternative -> !entry.getValue().contains(alternative)).toList());
		return viable;
	}

	private record LegacyDependent(Object owner, Object alternative) { }

	private static final class CountingList<T> extends AbstractList<T> {
		private final List<T> values;
		private int gets;

		private CountingList(List<T> values) {
			this.values = List.copyOf(values);
		}

		@Override
		public T get(int index) {
			gets++;
			return values.get(index);
		}

		@Override
		public int size() {
			return values.size();
		}

		private int getCount() {
			return gets;
		}
	}

	private static NativePlacementContinuity continuity(SearchSpaceMetrics metrics) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
	}

	private static long directMetric(SearchSpaceMetrics metrics, String name) {
		try {
			return metrics.directWorkCount(Enum.valueOf(SearchSpaceMetrics.DirectWork.class, name));
		}
		catch(IllegalArgumentException missingBeforeProductionChange) {
			return 0;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<?,?> prune(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"pruneDeadAlternatives", Map.class);
		method.setAccessible(true);
		return (Map<?,?>)method.invoke(continuity, graph);
	}

	private static Map<?,?> certifiedPrune(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph) throws Exception {
		Class<?> traversalType = nested("CandidateProofTraversal");
		Constructor<?> constructor = traversalType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object traversal = constructor.newInstance();
		java.lang.reflect.Field empty = traversalType.getDeclaredField("emptyFilteredStates");
		empty.setAccessible(true);
		empty.setLong(traversal, graph.values().stream().filter(List::isEmpty).count());
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"pruneDeadAlternatives", Map.class, traversalType, long.class);
		method.setAccessible(true);
		return (Map<?,?>)method.invoke(continuity, graph, traversal,
			graph.values().stream().mapToLong(List::size).sum());
	}

	private static void assertAllEmpty(Map<?,?> graph) {
		for(Object alternatives : graph.values())
			Assert.assertTrue(((List<?>)alternatives).isEmpty());
	}

	private static Object witness() throws Exception {
		return witness("localhost:1234");
	}

	private static Object witness(String endpoint) throws Exception {
		Class<?> type = nested("NativePoolWitness");
		Constructor<?> constructor = type.getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.ROW, List.of(endpoint), List.of(), false);
	}

	private static Object state(CompiledHopKey key, int handle, Object witness) throws Exception {
		Class<?> type = nested("CandidateProofState");
		Constructor<?> constructor = type.getDeclaredConstructor(
			CompiledHopKey.class, Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.PlacementIdentity$CandidateRealizationReference"),
			int.class, nested("NativePoolWitness"), boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(key, null, handle, witness, false);
	}

	private static Object dependency(CompiledHopKey key, int handle, Object witness) throws Exception {
		Class<?> type = nested("CandidateProofDependency");
		Constructor<?> constructor = type.getDeclaredConstructor(
			CompiledHopKey.class, Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.PlacementIdentity$CandidateRealizationReference"),
			int.class, nested("NativePoolWitness"), int.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(key, null, handle, witness, 0, false);
	}

	private static Object alternative(List<Object> dependencies, Object witness) throws Exception {
		Class<?> type = nested("SelectedCandidateProof");
		Constructor<?> constructor = type.getDeclaredConstructor(Class.forName(
			"org.apache.sysds.hops.fedplanner.placement.PlacementIdentity$CandidateRealizationReference"),
			List.class, boolean.class, nested("NativePoolWitness"));
		constructor.setAccessible(true);
		return constructor.newInstance(null, dependencies, false, witness);
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("native-prune", "main", "root", "compiled", REGION, id, id);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,List<Object>> graph(Object... entries) {
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		for(int index = 0; index < entries.length; index += 2)
			graph.put(entries[index], (List<Object>)entries[index + 1]);
		return graph;
	}
}
