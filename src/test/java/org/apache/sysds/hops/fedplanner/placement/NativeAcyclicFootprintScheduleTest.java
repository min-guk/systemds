/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeAcyclicFootprintScheduleTest {
	@Test
	public void cachedDefaultScheduleAvoidsDependencyRescanAndKeepsIdentitySuccessors()
		throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Fixture fixture = fixture(true, 3, metrics);
		Assert.assertEquals(List.of(fixture.firstLeaf(), fixture.secondLeaf()),
			fixture.scheduledSuccessors());
		fixture.dependencies().reset();

		Object footprint = footprint(fixture);
		Assert.assertNotNull(footprint);
		Assert.assertEquals("the footprint must consume the already cached unique-successor schedule",
			0, fixture.dependencies().elementReads());
		assertCompleteIdentityFootprint(footprint, fixture);
		Assert.assertEquals(1L, directWork(metrics, "COMPONENT_FOOTPRINT_SCHEDULES"));
		Assert.assertEquals(6L, directWork(metrics, "COMPONENT_FOOTPRINT_RAW_EDGES"));
		Assert.assertEquals(2L, directWork(metrics, "COMPONENT_FOOTPRINT_UNIQUE_EDGES"));
		Assert.assertEquals("footprint reuse must not report a graph-traversal schedule hit",
			0L, metricField(metrics, "proofDefaultScheduleHits"));
		Assert.assertEquals(0L, metricField(metrics, "proofDefaultScheduleBuilds"));
	}

	@Test
	public void ordinaryAlternativeListStillWalksEveryDependency() throws Exception {
		Fixture fixture = fixture(false);
		fixture.dependencies().reset();
		Object footprint = footprint(fixture);
		Assert.assertNotNull(footprint);
		Assert.assertTrue("plain and overlay lists retain the legacy dependency walk",
			fixture.dependencies().elementReads() > 0);
		assertCompleteIdentityFootprint(footprint, fixture);
	}

	@Test
	public void cachedScheduleHasTheSameFootprintWithOneStateOfBudgetSlack() throws Exception {
		Fixture fixture = fixture(true, 4);
		fixture.dependencies().reset();
		Object footprint = footprint(fixture);
		Assert.assertNotNull(footprint);
		Assert.assertEquals(0, fixture.dependencies().elementReads());
		assertCompleteIdentityFootprint(footprint, fixture);
	}

	@Test
	public void cachedAndPlainTraversalBothRejectTheSameOverBudgetFootprint()
		throws Exception {
		for(boolean defaultList : List.of(true, false)) {
			Fixture fixture = fixture(defaultList, 2);
			fixture.dependencies().reset();
			Assert.assertNull("a three-state footprint must exceed a two-state cap",
				footprint(fixture));
			if(defaultList)
				Assert.assertEquals(0, fixture.dependencies().elementReads());
			else
				Assert.assertTrue(fixture.dependencies().elementReads() > 0);
		}
	}

	@Test
	public void sameOwnerStatesWithDistinctHandlesAndWitnessesRemainCounted() throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
		String prior = System.getProperty(property);
		System.setProperty(property, "4");
		try {
			NativePlacementContinuity continuity = new NativePlacementContinuity(
				Map.of(), Map.of(), List.of(), List.of(), Map.of());
			Object firstWitness = invoke(continuity, "nativeWitness",
				new Class<?>[] {DurableAnchorKey.class}, pool("same-owner-first"));
			Object secondWitness = invoke(continuity, "nativeWitness",
				new Class<?>[] {DurableAnchorKey.class},
				pool("same-owner-second", "other-worker"));
			CompiledHopKey rootOwner = key("same-owner-root");
			CompiledHopKey sharedOwner = key("same-owner-child");
			Object root = state(rootOwner, firstWitness, 1);
			Object first = dependency(sharedOwner, firstWitness, 9, 0);
			Object distinctHandle = dependency(sharedOwner, firstWitness, 10, 1);
			Object distinctWitness = dependency(sharedOwner, secondWitness, 9, 2);
			Object firstState = invokeAccessor(first, "state");
			Object handleState = invokeAccessor(distinctHandle, "state");
			Object witnessState = invokeAccessor(distinctWitness, "state");
			CountingDependencyList dependencies = new CountingDependencyList(
				List.of(first, distinctHandle, distinctWitness));
			Object row = proof(dependencies, firstWitness);
			@SuppressWarnings("unchecked")
			List<Object> defaultRows = (List<Object>)construct(nested("DefaultAlternativeList"),
				new Class<?>[] {List.class}, List.of(row));
			Object schedule = invoke(defaultRows, "traversalSchedule",
				new Class<?>[] {SearchSpaceMetrics.class}, new Object[] {null});
			@SuppressWarnings("unchecked")
			List<Object> successors = (List<Object>)invokeAccessor(schedule, "uniqueSuccessors");
			Assert.assertEquals(List.of(firstState, handleState, witnessState), successors);
			dependencies.reset();
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(root, defaultRows);
			graph.put(firstState, List.of());
			graph.put(handleState, List.of());
			graph.put(witnessState, List.of());
			Object footprint = footprint(continuity, root, graph, newTraversal());
			Assert.assertEquals(0, dependencies.elementReads());
			@SuppressWarnings("unchecked")
			Set<CompiledHopKey> owners =
				(Set<CompiledHopKey>)invokeAccessor(footprint, "occurrences");
			Assert.assertEquals(2, owners.size());
			Assert.assertEquals(4L, invokeAccessor(footprint, "retainedStates"));
		}
		finally {
			restore(property, prior);
		}
	}

	@Test
	public void cachedSchedulePreservesHiddenReadsAndNestedReusedSummaryFootprint()
		throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
		String prior = System.getProperty(property);
		System.setProperty(property, "4");
		try {
			NativePlacementContinuity continuity = new NativePlacementContinuity(
				Map.of(), Map.of(), List.of(), List.of(), Map.of());
			Object witness = invoke(continuity, "nativeWitness",
				new Class<?>[] {DurableAnchorKey.class}, pool("nested-summary"));
			CompiledHopKey rootOwner = key("nested-root");
			CompiledHopKey rootHiddenOwner = key("nested-root-hidden");
			CompiledHopKey nestedOwner = key("nested-reused-owner");
			CompiledHopKey nestedHiddenOwner = key("nested-reused-hidden");
			Object root = state(rootOwner, witness, 1);
			Object nestedDependency = dependency(nestedOwner, witness, 2, 0);
			Object nestedState = invokeAccessor(nestedDependency, "state");
			CountingDependencyList dependencies =
				new CountingDependencyList(List.of(nestedDependency));
			Object row = proof(dependencies, witness);
			@SuppressWarnings("unchecked")
			List<Object> defaultRows = (List<Object>)construct(nested("DefaultAlternativeList"),
				new Class<?>[] {List.class}, List.of(row));
			invoke(defaultRows, "traversalSchedule",
				new Class<?>[] {SearchSpaceMetrics.class}, new Object[] {null});
			dependencies.reset();
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(root, defaultRows);
			Object traversal = newTraversal();
			Set<CompiledHopKey> nestedOwners = java.util.Collections.newSetFromMap(
				new IdentityHashMap<>());
			nestedOwners.add(nestedOwner);
			nestedOwners.add(nestedHiddenOwner);
			Object summary = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class},
				List.of(), nestedOwners, 2L);
			putTraversalMap(traversal, "reusedComponents", nestedState, summary);
			Set<CompiledHopKey> rootHidden = java.util.Collections.newSetFromMap(
				new IdentityHashMap<>());
			rootHidden.add(rootHiddenOwner);
			putTraversalMap(traversal, "hiddenOwnerReadsByState", root, rootHidden);
			Object footprint = footprint(continuity, root, graph, traversal);
			Assert.assertEquals(0, dependencies.elementReads());
			@SuppressWarnings("unchecked")
			Set<CompiledHopKey> owners =
				(Set<CompiledHopKey>)invokeAccessor(footprint, "occurrences");
			Assert.assertEquals(4, owners.size());
			Assert.assertEquals(4L, invokeAccessor(footprint, "retainedStates"));
			for(CompiledHopKey expected :
				List.of(rootOwner, rootHiddenOwner, nestedOwner, nestedHiddenOwner))
				Assert.assertTrue(owners.stream().anyMatch(owner -> owner == expected));
		}
		finally {
			restore(property, prior);
		}
	}

	private static Fixture fixture(boolean defaultList) throws Exception {
		return fixture(defaultList, 3, null);
	}

	private static Fixture fixture(boolean defaultList, int maxStates) throws Exception {
		return fixture(defaultList, maxStates, null);
	}

	private static Fixture fixture(boolean defaultList, int maxStates,
		SearchSpaceMetrics metrics) throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
		String prior = System.getProperty(property);
		System.setProperty(property, Integer.toString(maxStates));
		try {
			NativePlacementContinuity continuity = metrics == null
				? new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of())
				: new NativePlacementContinuity(
					Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
			Object witness = invoke(continuity, "nativeWitness",
				new Class<?>[] {DurableAnchorKey.class}, pool("schedule-pool"));
			CompiledHopKey rootOwner = key("schedule-root");
			CompiledHopKey equalLeafA = key("schedule-equal-leaf");
			CompiledHopKey equalLeafB = key("schedule-equal-leaf");
			Assert.assertEquals(equalLeafA, equalLeafB);
			Assert.assertNotSame(equalLeafA, equalLeafB);
			Object root = state(rootOwner, witness, 1);
			Object firstDependency = dependency(equalLeafA, witness, 9, 0);
			Object duplicateFirstDependency = dependency(equalLeafA, witness, 9, 1);
			Object secondDependency = dependency(equalLeafB, witness, 9, 2);
			Object firstLeaf = invokeAccessor(firstDependency, "state");
			Object secondLeaf = invokeAccessor(secondDependency, "state");
			CountingDependencyList dependencies = new CountingDependencyList(
				List.of(firstDependency, duplicateFirstDependency, secondDependency));
			Object row = proof(dependencies, witness);
			List<Object> rows;
			List<Object> scheduled = List.of();
			if(defaultList) {
				Class<?> listType = nested("DefaultAlternativeList");
				@SuppressWarnings("unchecked")
				List<Object> wrapped = (List<Object>)construct(
					listType, new Class<?>[] {List.class}, List.of(row, row));
				rows = wrapped;
				Object schedule = invoke(wrapped, "traversalSchedule",
					new Class<?>[] {SearchSpaceMetrics.class}, new Object[] {null});
				@SuppressWarnings("unchecked")
				List<Object> unique = (List<Object>)invokeAccessor(schedule, "uniqueSuccessors");
				scheduled = unique;
			}
			else
				rows = List.of(row, row);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(root, rows);
			graph.put(firstLeaf, List.of());
			graph.put(secondLeaf, List.of());
			return new Fixture(continuity, root, firstLeaf, secondLeaf,
				dependencies, graph, scheduled);
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	private static Object footprint(Fixture fixture) throws Exception {
		return footprint(fixture.continuity(), fixture.root(), fixture.graph(), newTraversal());
	}

	private static Object footprint(NativePlacementContinuity continuity, Object root,
		Map<Object,List<Object>> graph, Object traversal) throws Exception {
		Class<?> traversalType = nested("CandidateProofTraversal");
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"acyclicComponentFootprint", nested("CandidateProofState"), Map.class, traversalType);
		method.setAccessible(true);
		return method.invoke(continuity, root, graph, traversal);
	}

	private static Object newTraversal() throws Exception {
		return construct(nested("CandidateProofTraversal"), new Class<?>[0]);
	}

	@SuppressWarnings("unchecked")
	private static void putTraversalMap(Object traversal, String fieldName,
		Object key, Object value) throws Exception {
		Field field = traversal.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		((Map<Object,Object>)field.get(traversal)).put(key, value);
	}

	private static void restore(String property, String prior) {
		if(prior == null)
			System.clearProperty(property);
		else
			System.setProperty(property, prior);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static long directWork(SearchSpaceMetrics metrics, String name) throws Exception {
		Class<? extends Enum> type = (Class<? extends Enum>)Class.forName(
			SearchSpaceMetrics.class.getName() + "$DirectWork");
		Object work = Enum.valueOf(type, name);
		Method method = SearchSpaceMetrics.class.getDeclaredMethod("directWorkCount", type);
		method.setAccessible(true);
		return (Long)method.invoke(metrics, work);
	}

	private static long metricField(SearchSpaceMetrics metrics, String name) throws Exception {
		Field field = SearchSpaceMetrics.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(metrics);
	}

	private static void assertCompleteIdentityFootprint(Object footprint, Fixture fixture)
		throws Exception {
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> owners = (Set<CompiledHopKey>)invokeAccessor(footprint, "occurrences");
		Assert.assertEquals(3, owners.size());
		Assert.assertEquals(3L, invokeAccessor(footprint, "retainedStates"));
		CompiledHopKey root = (CompiledHopKey)invokeAccessor(fixture.root(), "key");
		CompiledHopKey first = (CompiledHopKey)invokeAccessor(fixture.firstLeaf(), "key");
		CompiledHopKey second = (CompiledHopKey)invokeAccessor(fixture.secondLeaf(), "key");
		Assert.assertTrue(owners.stream().anyMatch(owner -> owner == root));
		Assert.assertTrue(owners.stream().anyMatch(owner -> owner == first));
		Assert.assertTrue(owners.stream().anyMatch(owner -> owner == second));
	}

	private static Object dependency(CompiledHopKey owner, Object witness,
		int handle, int position) throws Exception {
		return construct(nested("CandidateProofDependency"),
			new Class<?>[] {CompiledHopKey.class,
				PlacementIdentity.CandidateRealizationReference.class, int.class,
				witness.getClass(), int.class, boolean.class},
			owner, null, handle, witness, position, false);
	}

	private static Object state(CompiledHopKey owner, Object witness, int handle)
		throws Exception {
		return construct(nested("CandidateProofState"),
			new Class<?>[] {CompiledHopKey.class,
				PlacementIdentity.CandidateRealizationReference.class, int.class,
				witness.getClass(), boolean.class}, owner, null, handle, witness, false);
	}

	private static Object proof(List<?> dependencies, Object witness) throws Exception {
		return construct(nested("SelectedCandidateProof"),
			new Class<?>[] {PlacementIdentity.CandidateRealizationReference.class,
				List.class, boolean.class, witness.getClass()},
			null, dependencies, false, witness);
	}

	private static Class<?> nested(String name) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static Object construct(Class<?> type, Class<?>[] signature, Object... arguments)
		throws Exception {
		Constructor<?> constructor = type.getDeclaredConstructor(signature);
		constructor.setAccessible(true);
		return constructor.newInstance(arguments);
	}

	private static Object invoke(Object receiver, String name, Class<?>[] signature,
		Object... arguments) throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name, signature);
		method.setAccessible(true);
		return method.invoke(receiver, arguments);
	}

	private static Object invokeAccessor(Object receiver, String name) throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(receiver);
	}

	private static CompiledHopKey key(String name) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod("key", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static DurableAnchorKey pool(String id) {
		return pool(id, "worker");
	}

	private static DurableAnchorKey pool(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static final class CountingDependencyList extends AbstractList<Object> {
		private final List<Object> values;
		private int elementReads;
		private CountingDependencyList(List<Object> values) { this.values = List.copyOf(values); }
		@Override public Object get(int index) { elementReads++; return values.get(index); }
		@Override public int size() { return values.size(); }
		@Override public Iterator<Object> iterator() {
			Iterator<Object> delegate = values.iterator();
			return new Iterator<>() {
				@Override public boolean hasNext() { return delegate.hasNext(); }
				@Override public Object next() { elementReads++; return delegate.next(); }
			};
		}
		private int elementReads() { return elementReads; }
		private void reset() { elementReads = 0; }
	}

	private record Fixture(NativePlacementContinuity continuity, Object root,
		Object firstLeaf, Object secondLeaf, CountingDependencyList dependencies,
		Map<Object,List<Object>> graph, List<Object> scheduledSuccessors) { }
}
