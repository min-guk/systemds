/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.junit.Assert;
import org.junit.Test;

public class NativeAcyclicFootprintReuseTest {
	@Test
	public void directlyReusedChildReturnsTheCachedImmutableOccurrenceSet() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		CompiledHopKey childOwner = key("reused-child");
		CompiledHopKey equalForeignA = key("equal-foreign-owner");
		CompiledHopKey equalForeignB = key("equal-foreign-owner");
		Assert.assertEquals(equalForeignA, equalForeignB);
		Assert.assertNotSame(equalForeignA, equalForeignB);
		Object witness = invoke(continuity, "nativeWitness",
			new Class<?>[] {DurableAnchorKey.class}, pool("reused-pool"));
		Class<?> stateType = nested("CandidateProofState");
		Object child = construct(stateType,
			new Class<?>[] {CompiledHopKey.class,
				PlacementIdentity.CandidateRealizationReference.class, int.class,
				witness.getClass(), boolean.class},
			childOwner, null, 7, witness, false);
		Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
		owners.add(childOwner);
		owners.add(equalForeignA);
		owners.add(equalForeignB);
		Class<?> summaryType = nested("AcyclicComponentSummary");
		Object summary = construct(summaryType,
			new Class<?>[] {List.class, Set.class, long.class}, List.of(), owners, 1L);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> cachedOwners = (Set<CompiledHopKey>)invokeAccessor(summary, "occurrences");
		Class<?> traversalType = nested("CandidateProofTraversal");
		Object traversal = construct(traversalType, new Class<?>[0]);
		Field reused = traversalType.getDeclaredField("reusedComponents");
		reused.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Object,Object> reusedComponents = (Map<Object,Object>)reused.get(traversal);
		reusedComponents.put(child, summary);

		Method footprintMethod = NativePlacementContinuity.class.getDeclaredMethod(
			"acyclicComponentFootprint", stateType, Map.class, traversalType);
		footprintMethod.setAccessible(true);
		Object footprint = footprintMethod.invoke(continuity, child, Map.of(), traversal);
		Assert.assertNotNull(footprint);
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> actualOwners =
			(Set<CompiledHopKey>)invokeAccessor(footprint, "occurrences");
		Assert.assertSame("a directly reused summary must not copy its complete occurrence set",
			cachedOwners, actualOwners);
		Assert.assertEquals("the full identity-owner count must dominate cached retained states",
			3L, invokeAccessor(footprint, "retainedStates"));
		Assert.assertEquals(3, actualOwners.size());
		Assert.assertTrue(actualOwners.stream().anyMatch(owner -> owner == childOwner));
		Assert.assertTrue(actualOwners.stream().anyMatch(owner -> owner == equalForeignA));
		Assert.assertTrue(actualOwners.stream().anyMatch(owner -> owner == equalForeignB));
		Assert.assertEquals(1L, directWork(metrics, "COMPONENT_FOOTPRINT_REQUESTS"));
		Assert.assertEquals(1L, directWork(metrics, "COMPONENT_FOOTPRINT_REUSED_BOUNDARIES"));
		Assert.assertEquals(3L, directWork(metrics, "COMPONENT_FOOTPRINT_REUSED_OWNERS"));
		try {
			actualOwners.add(key("forbidden-mutation"));
			Assert.fail("cached occurrence authority must remain immutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected: the footprint shares the summary's immutable set.
		}
	}

	@Test
	public void summaryReadmissionPreservesNegativeResultsAndFallsBackOnChangedAuthority() throws Exception {
		for(int mode = 0; mode < 6; mode++) {
			NativePlacementContinuity continuity = new NativePlacementContinuity(
				Map.of(), Map.of(), List.of(), List.of(), Map.of());
			CompiledHopKey owner = key("readmission-owner");
			Object witness = invoke(continuity, "nativeWitness", new Class<?>[] {DurableAnchorKey.class}, pool("readmission"));
			Object child = state(owner, witness, 7);
			Object row = construct(nested("SelectedCandidateProof"), new Class<?>[] {
				PlacementIdentity.CandidateRealizationReference.class, List.class, boolean.class, witness.getClass()},
				null, List.of(), true, witness);
			Object summary = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class}, mode == 0 ? List.of() : List.of(row), Set.of(owner), 1L);
			@SuppressWarnings("unchecked")
			List<Object> rows = (List<Object>)invokeAccessor(summary, "supportedAlternatives");
			@SuppressWarnings("unchecked")
			Set<CompiledHopKey> owners = (Set<CompiledHopKey>)invokeAccessor(summary, "occurrences");
			Set<CompiledHopKey> changedOwners = Collections.newSetFromMap(new IdentityHashMap<>());
			changedOwners.addAll(owners);
			CompiledHopKey addedOwner = key("new-metadata-owner");
			changedOwners.add(addedOwner);
			Object footprint = construct(nested("AcyclicComponentFootprint"),
				new Class<?>[] {Set.class, long.class}, mode == 2 ? changedOwners : owners, mode == 2 ? 2L : 1L);
			List<Object> viableRows = mode == 1 ? new java.util.ArrayList<>(rows) : mode == 3 ? List.of() : rows;
			Object traversal = construct(nested("CandidateProofTraversal"), new Class<?>[0]);
			mutableMap(traversal, "reusedComponents").put(child, summary);
			invoke(continuity, "cacheAcyclicRootChildren", new Class<?>[] {Map.class, Map.class, Set.class,
				CompiledHopKey.class, traversal.getClass()}, Map.of(child, footprint), Map.of(child, viableRows),
				viableRows.isEmpty() ? Set.of() : Set.of(child), mode == 4 ? owner : mode == 5 ? key("readmission-owner") : null,
				traversal);
			Map<Object,Object> memo = mutableMap(continuity, "acyclicComponentMemo");
			if(mode == 4) {
				Assert.assertTrue("generated root in exact footprint still prohibits admission", memo.isEmpty());
				continue;
			}
			Object admitted = memo.get(child);
			Assert.assertNotNull(admitted);
			if(mode == 0 || mode == 5)
				Assert.assertSame("negative summaries and identity-distinct fixed owners preserve original authority", summary, admitted);
			else
				Assert.assertNotSame("changed list or footprint must use ordinary reconstruction", summary, admitted);
			Assert.assertEquals(viableRows.size(), ((List<?>)invokeAccessor(admitted, "supportedAlternatives")).size());
			if(mode == 2) {
				Assert.assertTrue(((Set<?>)invokeAccessor(admitted, "occurrences")).contains(addedOwner));
				Assert.assertEquals(2L, invokeAccessor(admitted, "retainedStates"));
			}
		}
	}

	@Test
	public void summaryReadmissionAfterInterveningEvictionUsesExistingBudgetAndOrder() throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxEntries";
		String previous = System.getProperty(property);
		System.setProperty(property, "1");
		try {
			NativePlacementContinuity continuity = new NativePlacementContinuity(
				Map.of(), Map.of(), List.of(), List.of(), Map.of());
			CompiledHopKey owner = key("evicted-owner"), otherOwner = key("other-owner");
			Object witness = invoke(continuity, "nativeWitness", new Class<?>[] {DurableAnchorKey.class}, pool("evicted"));
			Object child = state(owner, witness, 7), other = state(otherOwner, witness, 8);
			Object summary = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class}, List.of(), Set.of(owner), 1L);
			Object otherSummary = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class}, List.of(), Set.of(otherOwner), 1L);
			Class<?>[] cacheTypes = {child.getClass(), summary.getClass()};
			invoke(continuity, "cacheAcyclicSummary", cacheTypes, child, summary);
			Object traversal = construct(nested("CandidateProofTraversal"), new Class<?>[0]);
			mutableMap(traversal, "reusedComponents").put(child, summary);
			invoke(continuity, "cacheAcyclicSummary", cacheTypes, other, otherSummary);
			Map<Object,Object> memo = mutableMap(continuity, "acyclicComponentMemo");
			Assert.assertEquals(List.of(other), List.copyOf(memo.keySet()));
			Object footprint = construct(nested("AcyclicComponentFootprint"),
				new Class<?>[] {Set.class, long.class}, invokeAccessor(summary, "occurrences"), 1L);
			invoke(continuity, "cacheAcyclicRootChildren", new Class<?>[] {Map.class, Map.class, Set.class,
				CompiledHopKey.class, traversal.getClass()}, Map.of(child, footprint),
				Map.of(child, invokeAccessor(summary, "supportedAlternatives")), Set.of(), null, traversal);
			Assert.assertEquals(List.of(child), List.copyOf(memo.keySet()));
			Assert.assertSame(summary, memo.get(child));
			Field retained = NativePlacementContinuity.class.getDeclaredField("acyclicComponentRetainedStates");
			retained.setAccessible(true);
			Assert.assertEquals(1L, retained.getLong(continuity));
		}
		finally {
			if(previous == null) System.clearProperty(property); else System.setProperty(property, previous);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> mutableMap(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (Map<Object,Object>)field.get(target);
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

	@Test
	public void acyclicRootChildrenMemoizeAnOverBudgetFootprintWithinTheQuery()
		throws Exception {
		assertOverBudgetChildWalkedOnce("acyclicRootChildFootprints");
	}

	@Test
	public void rootIndependentChildrenMemoizeAnOverBudgetFootprintWithinTheQuery()
		throws Exception {
		assertOverBudgetChildWalkedOnce("rootIndependentChildFootprints");
	}

	@Test
	public void directlyReusedSummaryStillHonorsBothStateAndOwnerCaps() throws Exception {
		assertReusedSummaryRejectedAtCap(3L, 1);
		assertReusedSummaryRejectedAtCap(1L, 3);
	}

	private static void assertReusedSummaryRejectedAtCap(long retainedStates, int ownerCount)
		throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
		String prior = System.getProperty(property);
		System.setProperty(property, "2");
		try {
			NativePlacementContinuity continuity = new NativePlacementContinuity(
				Map.of(), Map.of(), List.of(), List.of(), Map.of());
			CompiledHopKey childOwner = key("cap-child-" + retainedStates + '-' + ownerCount);
			Object witness = invoke(continuity, "nativeWitness",
				new Class<?>[] {DurableAnchorKey.class}, pool("cap-pool"));
			Object child = state(childOwner, witness, 7);
			Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
			for(int index = 0; index < ownerCount; index++)
				owners.add(key("cap-owner-" + index));
			Class<?> summaryType = nested("AcyclicComponentSummary");
			Object summary = construct(summaryType,
				new Class<?>[] {List.class, Set.class, long.class},
				List.of(), owners, retainedStates);
			Class<?> traversalType = nested("CandidateProofTraversal");
			Object traversal = construct(traversalType, new Class<?>[0]);
			Field reused = traversalType.getDeclaredField("reusedComponents");
			reused.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Object,Object> reusedComponents = (Map<Object,Object>)reused.get(traversal);
			reusedComponents.put(child, summary);
			Method footprintMethod = NativePlacementContinuity.class.getDeclaredMethod(
				"acyclicComponentFootprint", nested("CandidateProofState"), Map.class,
				traversalType);
			footprintMethod.setAccessible(true);
			Assert.assertNull(footprintMethod.invoke(continuity, child, Map.of(), traversal));
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	private static void assertOverBudgetChildWalkedOnce(String helper) throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxStates";
		String prior = System.getProperty(property);
		System.setProperty(property, "1");
		try {
			CompiledHopKey rootOwner = key(helper + "-root");
			CompiledHopKey childOwner = key(helper + "-child");
			CompiledHopKey grandchildOwner = key(helper + "-grandchild");
			CompiledHopKey positiveFirstOwner = key(helper + "-positive-first");
			CompiledHopKey positiveSecondOwner = key(helper + "-positive-second");
			Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
			nodes.put(rootOwner, node(rootOwner));
			nodes.put(childOwner, node(childOwner));
			nodes.put(grandchildOwner, node(grandchildOwner));
			nodes.put(positiveFirstOwner, node(positiveFirstOwner));
			nodes.put(positiveSecondOwner, node(positiveSecondOwner));
			NativePlacementContinuity continuity = new NativePlacementContinuity(
				nodes, Map.of(), List.of(), List.of(), Map.of());
			Object witness = invoke(continuity, "nativeWitness",
				new Class<?>[] {DurableAnchorKey.class}, pool(helper + "-pool"));
			Class<?> dependencyType = nested("CandidateProofDependency");
			Object childDependency = dependency(dependencyType, childOwner, witness, 1);
			Object grandchildDependency = dependency(dependencyType, grandchildOwner, witness, 2);
			Object positiveFirstDependency = dependency(
				dependencyType, positiveFirstOwner, witness, 3);
			Object positiveSecondDependency = dependency(
				dependencyType, positiveSecondOwner, witness, 4);
			Object root = state(rootOwner, witness, 0);
			Object child = invokeAccessor(childDependency, "state");
			Object positiveFirst = invokeAccessor(positiveFirstDependency, "state");
			Object positiveSecond = invokeAccessor(positiveSecondDependency, "state");
			Class<?> proofType = nested("SelectedCandidateProof");
			Object firstRootRow = proof(proofType, List.of(positiveFirstDependency), witness);
			Object secondRootRow = proof(proofType, List.of(childDependency), witness);
			Object thirdRootRow = proof(proofType, List.of(positiveSecondDependency), witness);
			Object fourthRootRow = proof(proofType, List.of(childDependency), witness);
			Object childRow = proof(proofType, List.of(grandchildDependency), witness);
			CountingGraph graph = new CountingGraph();
			graph.put(root, List.of(firstRootRow, secondRootRow, thirdRootRow, fourthRootRow));
			graph.put(child, List.of(childRow));
			Class<?> traversalType = nested("CandidateProofTraversal");
			Object traversal = construct(traversalType, new Class<?>[0]);
			Method method = NativePlacementContinuity.class.getDeclaredMethod(helper,
				nested("CandidateProofState"), Map.class, traversalType);
			method.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<Object,Object> first = (Map<Object,Object>)method.invoke(
				continuity, root, graph, traversal);
			Assert.assertEquals("a null over-budget result must be remembered for duplicate children",
				4, graph.lookups);
			Assert.assertEquals(List.of(positiveFirst, positiveSecond),
				List.copyOf(first.keySet()));
			Assert.assertFalse(first.containsKey(child));
			Assert.assertFalse(first.containsValue(null));
			@SuppressWarnings("unchecked")
			Map<Object,Object> second = (Map<Object,Object>)method.invoke(
				continuity, root, graph, traversal);
			Assert.assertEquals("negative memoization must be query-local, not retained in traversal",
				8, graph.lookups);
			Assert.assertEquals(List.of(positiveFirst, positiveSecond),
				List.copyOf(second.keySet()));
			Assert.assertFalse(second.containsValue(null));
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	private static Object dependency(Class<?> type, CompiledHopKey owner, Object witness,
		int position) throws Exception {
		return construct(type, new Class<?>[] {CompiledHopKey.class,
			PlacementIdentity.CandidateRealizationReference.class, int.class,
			witness.getClass(), int.class, boolean.class},
			owner, null, position, witness, position, false);
	}

	private static Object state(CompiledHopKey owner, Object witness, int handle)
		throws Exception {
		return construct(nested("CandidateProofState"), new Class<?>[] {CompiledHopKey.class,
			PlacementIdentity.CandidateRealizationReference.class, int.class,
			witness.getClass(), boolean.class}, owner, null, handle, witness, false);
	}

	private static Object proof(Class<?> type, List<?> dependencies, Object witness)
		throws Exception {
		return construct(type, new Class<?>[] {
			PlacementIdentity.CandidateRealizationReference.class, List.class,
			boolean.class, witness.getClass()}, null, dependencies, false, witness);
	}

	private static Node node(CompiledHopKey key) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod(
			"node", CompiledHopKey.class, List.class);
		method.setAccessible(true);
		return (Node)method.invoke(null, key, List.of());
	}

	private static final class CountingGraph extends HashMap<Object,List<Object>> {
		private int lookups;
		@Override
		public List<Object> getOrDefault(Object key, List<Object> defaultValue) {
			lookups++;
			return super.getOrDefault(key, defaultValue);
		}
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
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L))));
	}
}
