/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeDefaultPruningOrdinalTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"native-default-ordinal", "main", List.of("root"), "root", "compiled");

	@Test
	public void acyclicWarmLiveDefaultsCheckOnlyUniqueSuccessors() throws Exception {
		Object witness = witness();
		CompiledHopKey leftKey = key("acyclic-left"), rightKey = key("acyclic-right");
		Object left = state(leftKey, 1, witness), right = state(rightKey, 2, witness);
		Object root = state(key("acyclic-root"), 3, witness);
		Object ground = alternative(List.of(), true, witness);
		List<Object> rows = new ArrayList<>();
		for(int index = 0; index < 128; index++)
			rows.add(alternative(List.of(dependency(leftKey, 1, witness),
				dependency(rightKey, 2, witness), dependency(leftKey, 1, witness)), false, witness));
		List<?> defaults = defaultList(rows);
		ordinals(defaults); // Same schedule already warmed by production graph traversal.
		CountingGraph graph = new CountingGraph();
		graph.put(left, List.of(ground)); graph.put(right, List.of(ground)); graph.put(root, defaults);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertEquals(0, acyclicPrune(metrics, graph, List.of(left,right,root)));
		Assert.assertEquals("warm DAG pruning checks unique child states, not 384 repeated edges",
			2, graph.lookups);
		Assert.assertSame(defaults, graph.get(root));
		Assert.assertEquals(128, directMetric(metrics, "PRUNE_UNCHANGED_OWNER_SLOTS_SKIPPED"));

		List<?> groundedDefaults = defaultList(List.of(ground));
		ordinals(groundedDefaults);
		CountingGraph isolated = new CountingGraph(); isolated.put(root, groundedDefaults);
		Assert.assertEquals(0, acyclicPrune(null, isolated, List.of(root)));
		Assert.assertEquals("a direct ground with zero successors is already live", 0, isolated.lookups);
	}

	@Test
	public void acyclicColdFilteredMissingAndDeadChildrenKeepLegacyRows() throws Exception {
		Object witness = witness();
		CompiledHopKey childKey = key("acyclic-control-child");
		Object child = state(childKey, 1, witness), root = state(key("acyclic-control-root"), 2, witness);
		Object ground = alternative(List.of(), true, witness);
		Object conditional = alternative(List.of(dependency(childKey, 1, witness)), false, witness);
		Object empty = alternative(List.of(), false, witness);
		for(int mode = 0; mode < 4; mode++) {
			List<?> rows = mode == 1 ? List.of(conditional,empty,ground) : List.of(conditional,ground);
			List<?> defaults = defaultList(rows);
			if(mode != 0) ordinals(defaults);
			Map<Object,List<?>> plain = new LinkedHashMap<>(), actual = new LinkedHashMap<>();
			if(mode != 2) {
				plain.put(child, mode == 3 ? List.of() : List.of(ground));
				actual.put(child, mode == 3 ? List.of() : List.of(ground));
			}
			plain.put(root, rows); actual.put(root, defaults);
			List<Object> order = mode == 2 ? List.of(root) : List.of(child,root);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			Assert.assertEquals(acyclicPrune(null, plain, order), acyclicPrune(metrics, actual, order));
			assertIdenticalGraph("acyclic fallback mode " + mode, plain, actual);
			Assert.assertEquals(0, directMetric(metrics, "PRUNE_UNCHANGED_OWNER_SLOTS_SKIPPED"));
			if(mode == 0) Assert.assertNull("cold pruning must not build optional metadata", field(defaults, "schedule"));
		}
	}

	@Test
	public void randomizedAcyclicPruningMatchesPlainRowsWithWarmAndColdSchedules() throws Exception {
		Random random = new Random(270340L);
		Object witness = witness();
		for(int trial = 0; trial < 120; trial++) {
			List<CompiledHopKey> owners = new ArrayList<>();
			List<Object> states = new ArrayList<>();
			Map<Object,List<?>> plain = new LinkedHashMap<>(), actual = new LinkedHashMap<>();
			for(int owner = 0; owner < 10; owner++) {
				owners.add(key("acyclic-random-" + trial + '-' + owner));
				states.add(state(owners.get(owner), owner + 1, witness));
				List<Object> rows = new ArrayList<>();
				for(int row = 0, count = random.nextInt(8); row < count; row++) {
					List<Object> dependencies = new ArrayList<>();
					if(owner > 0)
						for(int edge = 0, countEdges = random.nextInt(5); edge < countEdges; edge++) {
							int source = random.nextInt(owner);
							dependencies.add(dependency(owners.get(source), source + 1, witness));
						}
					rows.add(alternative(dependencies, dependencies.isEmpty(), witness));
				}
				List<?> defaults = defaultList(rows);
				if(random.nextBoolean()) ordinals(defaults);
				plain.put(states.get(owner), List.copyOf(rows)); actual.put(states.get(owner), defaults);
			}
			SearchSpaceMetrics plainMetrics = new SearchSpaceMetrics(), actualMetrics = new SearchSpaceMetrics();
			Assert.assertEquals(acyclicPrune(plainMetrics, plain, states), acyclicPrune(actualMetrics, actual, states));
			assertIdenticalGraph("acyclic random trial " + trial, plain, actual);
			Assert.assertEquals(plainMetrics.snapshot().alternativesRemoved(), actualMetrics.snapshot().alternativesRemoved());
		}
	}

	private static long acyclicPrune(SearchSpaceMetrics metrics, Map<Object,List<?>> graph,
		List<Object> order) throws Exception {
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"pruneDeadAcyclicAlternatives", Map.class, List.class);
		method.setAccessible(true); return (long)method.invoke(continuity, graph, order);
	}

	private static final class CountingGraph extends LinkedHashMap<Object,List<?>> {
		private int lookups;
		@Override public List<?> get(Object key) { lookups++; return super.get(key); }
	}

	@Test
	public void ordinalCacheIsStableAndPreservesOriginalDependencyOrder() throws Exception {
		Object witness = witness();
		CompiledHopKey left = key("order-left"), right = key("order-right");
		Object leftDependency = dependency(left, 1, witness);
		Object rightDependency = dependency(right, 2, witness);
		Object first = alternative(List.of(rightDependency, leftDependency, rightDependency), false, witness);
		Object second = alternative(List.of(leftDependency), false, witness);
		List<?> defaults = defaultList(List.of(first, second));

		int[] cold = ordinals(defaults);
		Assert.assertArrayEquals(new int[] {0, 1, 0, 1}, cold);
		Assert.assertSame("the immutable topology list owns one lazy ordinal array",
			cold, ordinals(defaults));
		Assert.assertSame(first, defaults.get(0));
		Assert.assertSame(second, defaults.get(1));
	}

	@Test
	public void ordinalCacheRejectsDenseFilteredAndOverBudgetLists() throws Exception {
		Object witness = witness();
		Object repeated = dependency(key("budget-repeated"), 1, witness);
		Assert.assertNull("more than eight edges per alternative is outside the optional budget",
			ordinals(defaultList(List.of(alternative(java.util.Collections.nCopies(9, repeated),
				false, witness)))));

		Object first = dependency(key("dense-first"), 2, witness);
		Object second = dependency(key("dense-second"), 3, witness);
		Assert.assertNull("a dependency array without repeated successors cannot save lookups",
			ordinals(defaultList(List.of(alternative(List.of(first, second), false, witness)))));

		Object filtered = alternative(List.of(), false, witness);
		Object retained = alternative(List.of(repeated, repeated), false, witness);
		Assert.assertNull("filtering changes the alternative/edge coordinates and must use fallback",
			ordinals(defaultList(List.of(filtered, retained))));

		List<Object> overCap = new ArrayList<>(8193);
		List<Object> eight = java.util.Collections.nCopies(8, repeated);
		for(int index = 0; index < 8192; index++)
			overCap.add(alternative(eight, false, witness));
		overCap.add(alternative(List.of(repeated), false, witness));
		Assert.assertNull("the optional cache never exceeds 65,536 dependency ordinals",
			ordinals(defaultList(overCap)));
	}

	@Test
	public void duplicateAlternativePruningIsIndependentOfDenseGraphOrder() throws Exception {
		Object witness = witness();
		CompiledHopKey deadKey = key("graph-dead"), liveKey = key("graph-live");
		Object dead = state(deadKey, 1, witness), live = state(liveKey, 2, witness);
		Object root = state(key("graph-root"), 3, witness);
		Object grounded = alternative(List.of(), true, witness);
		Object doomed = alternative(List.of(dependency(deadKey, 1, witness)), false, witness);
		Object survivor = alternative(List.of(dependency(liveKey, 2, witness)), false, witness);
		List<?> defaults = defaultList(List.of(doomed, doomed, survivor));
		Assert.assertArrayEquals(new int[] {0, 0, 1}, ordinals(defaults));

		Map<Object,List<?>> rootFirst = graph(root, defaults, dead, List.of(), live, List.of(grounded));
		Map<Object,List<?>> deadFirst = graph(dead, List.of(), live, List.of(grounded), root, defaults);
		SearchSpaceMetrics firstMetrics = new SearchSpaceMetrics();
		SearchSpaceMetrics secondMetrics = new SearchSpaceMetrics();
		Map<?,?> firstResult = prune(firstMetrics, rootFirst);
		Map<?,?> secondResult = prune(secondMetrics, deadFirst);

		assertOnlySurvivor(firstResult, root, survivor);
		assertOnlySurvivor(secondResult, root, survivor);
		Assert.assertSame(defaults, rootFirst.get(root));
		Assert.assertSame(defaults, deadFirst.get(root));
		Assert.assertEquals("dense IDs and map order cannot change pruning counters",
			firstMetrics.snapshot(), secondMetrics.snapshot());
	}

	@Test
	public void randomDefaultListsMatchPlainPruningOracle() throws Exception {
		Random random = new Random(784621L);
		Object witness = witness();
		for(int trial = 0; trial < 100; trial++) {
			int width = 3 + random.nextInt(6);
			List<CompiledHopKey> owners = new ArrayList<>(width);
			List<Object> states = new ArrayList<>(width);
			for(int owner = 0; owner < width; owner++) {
				CompiledHopKey key = key("random-" + trial + '-' + owner);
				owners.add(key);
				states.add(state(key, owner + 1, witness));
			}
			Map<Object,List<?>> plain = new LinkedHashMap<>();
			Map<Object,List<?>> defaults = new LinkedHashMap<>();
			for(int owner = 0; owner < width; owner++) {
				List<Object> alternatives = new ArrayList<>();
				int alternativeCount = owner == 0 ? 0 : random.nextInt(5);
				for(int alternative = 0; alternative < alternativeCount; alternative++) {
					List<Object> dependencies = new ArrayList<>();
					int dependencyCount = random.nextInt(5);
					for(int dependency = 0; dependency < dependencyCount; dependency++) {
						int source = random.nextInt(width);
						dependencies.add(dependency(owners.get(source), source + 1, witness));
					}
					Object value = alternative(dependencies, dependencyCount == 0, witness);
					alternatives.add(value);
					if(random.nextInt(5) == 0)
						alternatives.add(value);
				}
				List<Object> immutable = List.copyOf(alternatives);
				plain.put(states.get(owner), immutable);
				defaults.put(states.get(owner), defaultList(immutable));
			}
			SearchSpaceMetrics plainMetrics = new SearchSpaceMetrics();
			SearchSpaceMetrics defaultMetrics = new SearchSpaceMetrics();
			Map<?,?> expected = prune(plainMetrics, plain);
			Map<?,?> actual = prune(defaultMetrics, defaults);
			assertIdenticalGraph("trial " + trial, expected, actual);
			Assert.assertEquals("ordinal caching cannot change legacy pruning counters",
				plainMetrics.snapshot(), defaultMetrics.snapshot());
		}
	}

	@Test
	public void noDeadAndFallbackPathsDoNotInitializeOrCountOrdinalCache() throws Exception {
		Object witness = witness();
		CompiledHopKey liveKey = key("no-dead-live");
		Object live = state(liveKey, 1, witness);
		Object owner = state(key("no-dead-owner"), 2, witness);
		Object ground = alternative(List.of(), true, witness);
		List<?> eligible = defaultList(List.of(alternative(List.of(
			dependency(liveKey, 1, witness), dependency(liveKey, 1, witness)), false, witness)));
		Map<Object,List<?>> complete = graph(live, List.of(ground), owner, eligible);
		Assert.assertSame(complete, prune(new SearchSpaceMetrics(), complete));
		Assert.assertEquals(false, field(eligible, "pruningOrdinalsAttempted"));

		Object dead = state(key("fallback-dead"), 3, witness);
		Object plainOwner = state(key("fallback-plain"), 4, witness);
		SearchSpaceMetrics plainMetrics = new SearchSpaceMetrics();
		prune(plainMetrics, graph(dead, List.of(), live, List.of(ground), plainOwner,
			List.of(alternative(List.of(dependency(liveKey, 1, witness)), false, witness))));
		Assert.assertEquals(0, directMetric(plainMetrics, "PRUNE_DEFAULT_ORDINAL_EDGES"));
		Assert.assertEquals(0, directMetric(plainMetrics, "PRUNE_DEFAULT_DENSE_RESOLUTIONS"));

		Object repeated = dependency(liveKey, 1, witness);
		List<Object> overCap = new ArrayList<>(8193);
		List<Object> eight = java.util.Collections.nCopies(8, repeated);
		for(int index = 0; index < 8192; index++)
			overCap.add(alternative(eight, false, witness));
		overCap.add(alternative(List.of(repeated), false, witness));
		SearchSpaceMetrics capMetrics = new SearchSpaceMetrics();
		prune(capMetrics, graph(dead, List.of(), live, List.of(ground), owner, defaultList(overCap)));
		Assert.assertEquals(0, directMetric(capMetrics, "PRUNE_DEFAULT_ORDINAL_EDGES"));
		Assert.assertEquals(0, directMetric(capMetrics, "PRUNE_DEFAULT_DENSE_RESOLUTIONS"));
	}

	@Test
	public void missingStateUsesOrdinalPathWithoutChangingConservativeRemoval() throws Exception {
		Object witness = witness();
		CompiledHopKey missing = key("ordinal-missing");
		Object root = state(key("ordinal-missing-root"), 1, witness);
		Object dependency = dependency(missing, 2, witness);
		List<?> defaults = defaultList(List.of(alternative(
			List.of(dependency, dependency), false, witness)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Map<?,?> result = prune(metrics, graph(root, defaults));
		Assert.assertTrue(((List<?>)result.get(root)).isEmpty());
		Assert.assertEquals(2, directMetric(metrics, "PRUNE_DEFAULT_ORDINAL_EDGES"));
		Assert.assertEquals(1, directMetric(metrics, "PRUNE_DEFAULT_DENSE_RESOLUTIONS"));
	}

	@Test
	public void certifiedClosedGraphMatchesConservativeWithMetricsDisabled() throws Exception {
		Object witness = witness();
		CompiledHopKey deadKey = key("certified-dead"), liveKey = key("certified-live");
		Object dead = state(deadKey, 1, witness), live = state(liveKey, 2, witness);
		Object root = state(key("certified-root"), 3, witness);
		Object ground = alternative(List.of(), true, witness);
		Object doomed = alternative(List.of(dependency(deadKey, 1, witness)), false, witness);
		Object survivor = alternative(List.of(dependency(liveKey, 2, witness),
			dependency(liveKey, 2, witness)), false, witness);
		List<?> defaults = defaultList(List.of(doomed, doomed, survivor));
		Assert.assertArrayEquals(new int[] {0, 0, 1, 1}, ordinals(defaults));
		Map<Object,List<?>> graph = graph(dead, List.of(), live, List.of(ground), root, defaults);

		Map<?,?> conservative = prune(null, graph);
		Map<?,?> certified = certifiedPrune(null, graph);

		assertIdenticalGraph("certified metrics-off pruning", conservative, certified);
		assertOnlySurvivor(certified, root, survivor);
		Assert.assertSame("both entry points leave the topology-owned list untouched",
			defaults, graph.get(root));
	}

	@Test
	public void certifiedCountingReusesOnlyAnExistingUnfilteredDefaultSchedule() throws Exception {
		for(boolean filtered : List.of(false, true)) {
			Object witness = witness();
			CompiledHopKey liveOwner = key("count-live");
			Object live = state(liveOwner, 1, witness);
			Object root = state(key("count-root"), 2, witness);
			Object dead = state(key("count-dead"), 3, witness);
			Object row = alternative(List.of(dependency(liveOwner, 1, witness)), false, witness);
			Object second = filtered ? alternative(List.of(), false, witness)
				: alternative(List.of(dependency(liveOwner, 1, witness)), false, witness);
			CountingAlternatives backing = new CountingAlternatives(List.of(row, second));
			List<?> defaults = defaultList(backing);
			java.lang.reflect.Field field = nested("DefaultAlternativeList").getDeclaredField("alternatives");
			field.setAccessible(true);
			// Same immutable contents, counted access only; install before building either index.
			field.set(defaults, backing);
			ordinals(defaults);
			backing.reads = 0;
			Map<Object,List<?>> input = graph(dead, List.of(), live,
				List.of(alternative(List.of(), true, witness)), root, defaults);
			Map<?,?> result = knownDeadPrune(input, true);
			Assert.assertSame("unchanged owners retain the original list", defaults, result.get(root));
			Assert.assertEquals("only warm, unfiltered certified rows avoid the first counting pass",
				filtered ? 4 : 2, backing.reads);
		}
	}

	@Test
	public void coldDefaultAndPlainListsRetainTheirCountingPass() throws Exception {
		for(boolean useDefault : List.of(false, true)) {
			Object witness = witness();
			CompiledHopKey liveOwner = key("cold-count-live");
			Object live = state(liveOwner, 1, witness);
			Object root = state(key("cold-count-root"), 2, witness);
			Object dead = state(key("cold-count-dead"), 3, witness);
			Object row = alternative(List.of(dependency(liveOwner, 1, witness)), false, witness);
			Object second = alternative(List.of(dependency(liveOwner, 1, witness)), false, witness);
			CountingAlternatives backing = new CountingAlternatives(List.of(row, second));
			List<?> rows = backing;
			if(useDefault) {
				rows = defaultList(backing);
				java.lang.reflect.Field field = nested("DefaultAlternativeList").getDeclaredField("alternatives");
				field.setAccessible(true);
				field.set(rows, backing);
				Assert.assertNull(field(rows, "schedule"));
			}
			backing.reads = 0;
			Map<?,?> result = knownDeadPrune(graph(dead, List.of(), live,
				List.of(alternative(List.of(), true, witness)), root, rows), true);
			Assert.assertSame(rows, result.get(root));
			Assert.assertEquals("cold default retains counting plus later schedule/ordinal construction",
				useDefault ? 8 : 4, backing.reads);
		}
	}

	@Test
	public void conservativeCountingStillDiscoversMissingDependencies() throws Exception {
		Object witness = witness();
		CompiledHopKey missingOwner = key("count-missing");
		Object root = state(key("count-root-missing"), 2, witness);
		Object doomed = alternative(List.of(dependency(missingOwner, 1, witness)), false, witness);
		Object ground = alternative(List.of(), true, witness);
		List<?> defaults = defaultList(List.of(doomed, ground));
		ordinals(defaults);
		Map<?,?> result = knownDeadPrune(graph(root, defaults), false);
		assertOnlySurvivor(result, root, ground);
	}

	private static final class CountingAlternatives extends java.util.AbstractList<Object> {
		private final List<?> values;
		private int reads;
		private CountingAlternatives(List<?> values) { this.values = List.copyOf(values); }
		@Override public int size() { return values.size(); }
		@Override public Object get(int index) { reads++; return values.get(index); }
	}

	private static Map<?,?> knownDeadPrune(Map<Object,List<?>> graph, boolean closed) throws Exception {
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of());
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"pruneDeadAlternativesFromKnownDeadSeed", Map.class, boolean.class);
		method.setAccessible(true);
		return (Map<?,?>)method.invoke(continuity, graph, closed);
	}

	private static void assertOnlySurvivor(Map<?,?> graph, Object root, Object survivor) {
		List<?> alternatives = (List<?>)graph.get(root);
		Assert.assertEquals(1, alternatives.size());
		Assert.assertSame(survivor, alternatives.get(0));
	}

	private static void assertIdenticalGraph(String label, Map<?,?> expected, Map<?,?> actual) {
		List<?> expectedKeys = new ArrayList<>(expected.keySet());
		List<?> actualKeys = new ArrayList<>(actual.keySet());
		Assert.assertEquals(label, expectedKeys.size(), actualKeys.size());
		for(int owner = 0; owner < expectedKeys.size(); owner++) {
			Object key = expectedKeys.get(owner);
			Assert.assertSame(label + " owner identity/order", key, actualKeys.get(owner));
			List<?> left = (List<?>)expected.get(key), right = (List<?>)actual.get(key);
			Assert.assertEquals(label, left.size(), right.size());
			for(int index = 0; index < left.size(); index++)
				Assert.assertSame(label, left.get(index), right.get(index));
		}
	}

	private static Object field(Object target, String name) throws Exception {
		java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static long directMetric(SearchSpaceMetrics metrics, String name) {
		try {
			return metrics.directWorkCount(Enum.valueOf(SearchSpaceMetrics.DirectWork.class, name));
		}
		catch(IllegalArgumentException absentBeforeProductionChange) {
			return 0;
		}
	}

	private static int[] ordinals(List<?> defaults) throws Exception {
		Method method = nested("DefaultAlternativeList").getDeclaredMethod(
			"pruningDependencyOrdinals");
		method.setAccessible(true);
		return (int[])method.invoke(defaults);
	}

	private static List<?> defaultList(List<?> alternatives) throws Exception {
		Constructor<?> constructor = nested("DefaultAlternativeList").getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return (List<?>)constructor.newInstance(alternatives);
	}

	private static Object alternative(List<?> dependencies, boolean directGround,
		Object witness) throws Exception {
		Constructor<?> constructor = nested("SelectedCandidateProof").getDeclaredConstructor(
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		constructor.setAccessible(true);
		return constructor.newInstance(null, dependencies, directGround, witness);
	}

	private static Object dependency(CompiledHopKey owner, int handle, Object witness)
		throws Exception {
		Constructor<?> constructor = nested("CandidateProofDependency").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), int.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner, null, handle, witness, 0, false);
	}

	private static Object state(CompiledHopKey owner, int handle, Object witness) throws Exception {
		Constructor<?> constructor = nested("CandidateProofState").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner, null, handle, witness, false);
	}

	private static Object witness() throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.ROW, List.of("localhost:1234"), List.of(), false);
	}

	@SuppressWarnings("unchecked")
	private static Map<?,?> prune(SearchSpaceMetrics metrics, Map<Object,List<?>> graph)
		throws Exception {
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"pruneDeadAlternatives", Map.class);
		method.setAccessible(true);
		return (Map<?,?>)method.invoke(continuity, graph);
	}

	@SuppressWarnings("unchecked")
	private static Map<?,?> certifiedPrune(SearchSpaceMetrics metrics,
		Map<Object,List<?>> graph) throws Exception {
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
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

	private static Map<Object,List<?>> graph(Object... entries) {
		Map<Object,List<?>> graph = new LinkedHashMap<>();
		for(int index = 0; index < entries.length; index += 2)
			graph.put(entries[index], (List<?>)entries[index + 1]);
		return graph;
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("native-default-ordinal", "main", "root", "compiled",
			REGION, id, id);
	}
}
