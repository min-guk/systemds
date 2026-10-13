/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

public class NativeQueryGateInterningTest {
	private static final String ENTRIES =
		"sysds.fedplanner.continuityAcyclicComponent.maxEntries";
	private static final String STATES =
		"sysds.fedplanner.continuityAcyclicComponent.maxStates";
	private static final String ROWS =
		"sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";

	@Test
	public void activeInternedGateUsesTheExistingCycleProtocol() throws Exception {
		NativePlacementContinuity continuity = continuity();
		Object witness = witness(continuity, "active-query-gate");
		Object key = gateKey(witness, 0);
		Object exit = dependency(key("active-query-exit"), witness, 1, 0);
		List<Object> alternatives = List.of(proof(List.of(exit), witness, false));
		Object gate = gate(key, key("active-query-owner"), witness, alternatives);
		Object state = accessor(gateDependency(gate), "state");
		Object traversal = traversalWithBudget(continuity);
		remember(continuity, gate, traversal);
		Assert.assertSame(gate, query(continuity, key, traversal));

		@SuppressWarnings("unchecked")
		java.util.Set<Object> active =
			(java.util.Set<Object>)fieldValue(traversal, "active");
		active.add(state);
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		graph.put(state, alternatives);
		invoke(continuity, "buildCandidateProofGraph",
			new Class<?>[] {nested("CandidateProofState"), nested("CandidateProofState"),
				nested("GenerationRoot"), Map.class, traversal.getClass(),
				nested("FixedCandidateBoundary"), long[].class},
			state, state, null, graph, traversal, null, null);

		Assert.assertTrue((Boolean)fieldValue(traversal, "cycleDetected"));
		Assert.assertTrue("an active gate is a cycle edge, not a reusable grounded summary",
			((Map<?,?>)fieldValue(traversal, "reusedComponents")).isEmpty());
		Assert.assertSame(alternatives, graph.get(state));
	}

	@Test
	public void exactAuthorityLookupIsTraversalLocalAndReleasedAtGraphExit() throws Exception {
		NativePlacementContinuity continuity = continuity();
		Object witness = witness(continuity, "lifecycle-query-gate");
		Object key = gateKey(witness, 0);
		Object equalKey = gateKey(witness, 0);
		Object foreignAxis = gateKey(witness, 1);
		Object gate = gate(key, key("lifecycle-query-owner"), witness, List.of());
		Object traversal = traversalWithBudget(continuity);

		remember(continuity, gate, traversal);

		Assert.assertSame("equal exact authority must resolve to the one built gate",
			gate, query(continuity, equalKey, traversal));
		Assert.assertNull("a different axis is different authority",
			query(continuity, foreignAxis, traversal));
		invoke(continuity, "releaseQueryNativeAxisGates",
			new Class<?>[] {traversal.getClass()}, traversal);
		Assert.assertNull("query-local identities must not escape graph construction",
			query(continuity, key, traversal));
	}

	@Test
	public void zeroEntryStateOrRowLimitDisablesTheQueryIndex() throws Exception {
		long[][] limits = {{0, 8, 8}, {8, 0, 8}, {8, 8, 0}};
		for(int index = 0; index < limits.length; index++) {
			long[] limit = limits[index];
			int caseIndex = index;
			withLimits((int)limit[0], limit[1], limit[2], () -> {
				NativePlacementContinuity continuity = continuity();
				Object witness = witness(continuity, "disabled-query-gate-" + caseIndex);
				Object key = gateKey(witness, 0);
				Object gate = gate(key, key("disabled-query-owner-" + caseIndex),
					witness, List.of());
				Object traversal = traversalWithBudget(continuity);

				remember(continuity, gate, traversal);

				Assert.assertNull(query(continuity, key, traversal));
				Assert.assertNull(fieldValue(fieldValue(traversal, "gateBudget"), "queryGates"));
				Assert.assertEquals(0, fieldValue(
					fieldValue(traversal, "gateBudget"), "indexEntries"));
			});
		}
	}

	@Test
	public void duplicateInsertionDoesNotReserveCapacityTwice() throws Exception {
		withLimits(2, 8, 16, () -> {
			NativePlacementContinuity continuity = continuity();
			Object witness = witness(continuity, "duplicate-query-gate");
			Object key = gateKey(witness, 0);
			Object gate = gate(key, key("duplicate-query-owner"), witness, List.of());
			Object traversal = traversalWithBudget(continuity);
			Object budget = fieldValue(traversal, "gateBudget");

			remember(continuity, gate, traversal);
			long statesAfterFirst = (Long)fieldValue(budget, "statesRemaining");
			long rowsAfterFirst = (Long)fieldValue(budget, "rowsRemaining");
			int entriesAfterFirst = (Integer)fieldValue(budget, "entriesRemaining");
			remember(continuity, gate, traversal);

			Assert.assertEquals(1, fieldValue(budget, "indexEntries"));
			Assert.assertEquals(1L, fieldValue(budget, "indexRows"));
			Assert.assertEquals(statesAfterFirst, fieldValue(budget, "statesRemaining"));
			Assert.assertEquals(rowsAfterFirst, fieldValue(budget, "rowsRemaining"));
			Assert.assertEquals(entriesAfterFirst, fieldValue(budget, "entriesRemaining"));
			Assert.assertSame(gate, query(continuity, key, traversal));
		});
	}

	@Test
	public void releasedSingleEntryCanBeUsedByLaterGateSummaryAdmission() throws Exception {
		withLimits(1, 16, 64, () -> {
			NativePlacementContinuity continuity = continuity();
			SearchSpaceMetrics metrics = (SearchSpaceMetrics)fieldValue(continuity, "metrics");
			Object witness = witness(continuity, "released-query-gate");
			Object leafDependency = dependency(key("released-query-leaf"), witness, 20, 0);
			Object leafState = accessor(leafDependency, "state");
			List<Object> rows = List.of(proof(List.of(leafDependency), witness, false));
			Object gate = gate(gateKey(witness, 0), key("released-query-owner"), witness, rows);
			Object gateState = accessor(gateDependency(gate), "state");
			Object traversal = traversalWithBudget(continuity);
			Object budget = fieldValue(traversal, "gateBudget");
			remember(continuity, gate, traversal);
			invoke(continuity, "releaseQueryNativeAxisGates",
				new Class<?>[] {traversal.getClass()}, traversal);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, rows);
			graph.put(leafState, List.of());

			cacheGates(continuity, graph, traversal, Set.of(gateState), budget);

			Assert.assertTrue(componentMemo(continuity).containsKey(gateState));
			Assert.assertEquals(0, fieldValue(budget, "indexEntries"));
			Assert.assertEquals(0L, fieldValue(budget, "indexRows"));
			Assert.assertTrue(saturatedAdd(
				directWork(metrics, "NATIVE_AXIS_GATE_QUERY_WORK"),
				directWork(metrics, "COMPONENT_GATE_ADMISSION_WORK")) <= 80L);
		});
	}

	@Test
	public void exhaustedQueryWorkRemainsExhaustedAfterRelease() throws Exception {
		withLimits(2, 2, 4, () -> {
			NativePlacementContinuity continuity = continuity();
			SearchSpaceMetrics metrics = (SearchSpaceMetrics)fieldValue(continuity, "metrics");
			Object witness = witness(continuity, "exhausted-query-gate");
			Object leafDependency = dependency(key("exhausted-query-leaf"), witness, 30, 0);
			Object leafState = accessor(leafDependency, "state");
			List<Object> rows = List.of(proof(List.of(leafDependency), witness, false));
			Object gate = gate(gateKey(witness, 0), key("exhausted-query-owner"), witness, rows);
			Object gateState = accessor(gateDependency(gate), "state");
			Object traversal = traversalWithBudget(continuity);
			Object budget = fieldValue(traversal, "gateBudget");
			remember(continuity, gate, traversal);
			while(!(Boolean)fieldValue(budget, "exhausted"))
				query(continuity, gateKey(witness, 1), traversal);
			invoke(continuity, "releaseQueryNativeAxisGates",
				new Class<?>[] {traversal.getClass()}, traversal);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, rows);
			graph.put(leafState, List.of());

			prune(continuity, graph, List.of(leafState, gateState), budget);
			cacheGates(continuity, graph, traversal, Set.of(), budget);

			Assert.assertTrue((Boolean)fieldValue(budget, "exhausted"));
			Assert.assertNull(fieldValue(budget, "originalRows"));
			Assert.assertTrue(graph.get(gateState).isEmpty());
			Assert.assertFalse(componentMemo(continuity).containsKey(gateState));
			Assert.assertTrue(saturatedAdd(
				directWork(metrics, "NATIVE_AXIS_GATE_QUERY_WORK"),
				directWork(metrics, "COMPONENT_GATE_ADMISSION_WORK")) <= 6L);
		});
	}

	@Test
	public void repeatedCapacityMissesRemainWithinTheSharedWorkBound() throws Exception {
		withLimits(1, 2, 8, () -> {
			NativePlacementContinuity continuity = continuity();
			SearchSpaceMetrics metrics = (SearchSpaceMetrics)fieldValue(continuity, "metrics");
			Object witness = witness(continuity, "failed-query-gate");
			Object traversal = traversalWithBudget(continuity);
			Object budget = fieldValue(traversal, "gateBudget");
			Object first = gate(gateKey(witness, 0), key("failed-query-owner-0"),
				witness, List.of());
			remember(continuity, first, traversal);
			for(int index = 1; index < 32; index++)
				remember(continuity, gate(gateKey(witness, index),
					key("failed-query-owner-" + index), witness, List.of()), traversal);

			Assert.assertTrue((Boolean)fieldValue(budget, "exhausted"));
			Assert.assertEquals(1, ((Map<?,?>)fieldValue(budget, "queryGates")).size());
			Assert.assertEquals(1, fieldValue(budget, "indexEntries"));
			Assert.assertTrue(directWork(metrics, "NATIVE_AXIS_GATE_QUERY_WORK") <= 10L);
		});
	}

	@Test
	public void sharedAndUnsharedGateCircuitsHaveTheSameRealStateViability()
		throws Exception {
		NativePlacementContinuity continuity = continuity();
		Object witness = witness(continuity, "circuit-query-gate");
		CompiledHopKey pOwner = key("circuit-p");
		CompiledHopKey qOwner = key("circuit-q");
		CompiledHopKey gateOwner = key("circuit-gate");
		Object pState = accessor(dependency(pOwner, witness, 10, 0), "state");
		Object qState = accessor(dependency(qOwner, witness, 11, 0), "state");
		Object exitDependency = dependency(key("circuit-exit"), witness, 12, 0);
		Object exitState = accessor(exitDependency, "state");
		Object deadDependency = dependency(key("circuit-dead"), witness, 13, 0);
		Object deadState = accessor(deadDependency, "state");
		Object qDependency = dependencyForState(qOwner, witness, 11, 0);
		Object liveCycleRow = proof(List.of(qDependency), witness, false);
		Object liveExitRow = proof(List.of(exitDependency), witness, false);
		Object deadRow = proof(List.of(deadDependency), witness, false);
		List<Object> exactGateRows = List.of(liveCycleRow, liveExitRow, deadRow);
		List<Object> groundedExitRows = List.of(proof(List.of(), witness, true));

		Object sharedGate = gate(gateKey(witness, 0), gateOwner, witness, exactGateRows);
		Object sharedDependency = gateDependency(sharedGate);
		Object sharedState = accessor(sharedDependency, "state");
		Map<Object,List<Object>> shared = new LinkedHashMap<>();
		shared.put(pState, List.of(proof(List.of(sharedDependency), witness, false)));
		shared.put(qState, List.of(proof(List.of(sharedDependency), witness, false)));
		shared.put(sharedState, exactGateRows);
		shared.put(exitState, groundedExitRows);
		shared.put(deadState, List.of());

		Object firstGate = gate(gateKey(witness, 0), gateOwner, witness, exactGateRows);
		Object secondGate = gate(gateKey(witness, 0), gateOwner, witness, exactGateRows);
		Object firstDependency = gateDependency(firstGate);
		Object secondDependency = gateDependency(secondGate);
		Object firstState = accessor(firstDependency, "state");
		Object secondState = accessor(secondDependency, "state");
		Map<Object,List<Object>> unshared = new LinkedHashMap<>();
		unshared.put(pState, List.of(proof(List.of(firstDependency), witness, false)));
		unshared.put(qState, List.of(proof(List.of(secondDependency), witness, false)));
		unshared.put(firstState, exactGateRows);
		unshared.put(secondState, exactGateRows);
		unshared.put(exitState, groundedExitRows);
		unshared.put(deadState, List.of());

		@SuppressWarnings("unchecked")
		Map<Object,List<Object>> sharedViable = (Map<Object,List<Object>>)invoke(
			continuity, "pruneDeadAlternatives", new Class<?>[] {Map.class}, shared);
		@SuppressWarnings("unchecked")
		Map<Object,List<Object>> unsharedViable = (Map<Object,List<Object>>)invoke(
			continuity, "pruneDeadAlternatives", new Class<?>[] {Map.class}, unshared);

		Assert.assertEquals(1, sharedViable.get(pState).size());
		Assert.assertEquals(1, unsharedViable.get(pState).size());
		Assert.assertEquals(1, sharedViable.get(qState).size());
		Assert.assertEquals(1, unsharedViable.get(qState).size());
		Assert.assertSame(groundedExitRows, sharedViable.get(exitState));
		Assert.assertSame(groundedExitRows, unsharedViable.get(exitState));
		Assert.assertTrue(sharedViable.get(deadState).isEmpty());
		Assert.assertTrue(unsharedViable.get(deadState).isEmpty());
		for(Object gateState : List.of(sharedState, firstState, secondState)) {
			Map<Object,List<Object>> result = gateState == sharedState ? sharedViable : unsharedViable;
			Assert.assertEquals(2, result.get(gateState).size());
			Assert.assertSame("the exact live cycle row keeps its source authority",
				liveCycleRow, result.get(gateState).get(0));
			Assert.assertSame("the grounded exit keeps its source authority and order",
				liveExitRow, result.get(gateState).get(1));
		}
		Assert.assertEquals("sharing changes only synthetic gate identity",
			Set.of(pState, qState, exitState, deadState), realStates(sharedViable));
		Assert.assertEquals(realStates(sharedViable), realStates(unsharedViable));
	}

	private static Object traversalWithBudget(NativePlacementContinuity continuity)
		throws Exception {
		Object traversal = construct(nested("CandidateProofTraversal"), new Class<?>[0]);
		Object budget = construct(nested("NativeGateAdmissionBudget"),
			new Class<?>[] {NativePlacementContinuity.class}, continuity);
		Field field = traversal.getClass().getDeclaredField("gateBudget");
		field.setAccessible(true);
		field.set(traversal, budget);
		return traversal;
	}

	private static void cacheGates(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph, Object traversal, Set<Object> supported,
		Object budget) throws Exception {
		invoke(continuity, "cacheNativeGateComponents",
			new Class<?>[] {Map.class, traversal.getClass(), Set.class,
				CompiledHopKey.class, budget.getClass()},
			graph, traversal, supported, null, budget);
	}

	private static long prune(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph, List<Object> completionOrder, Object budget)
		throws Exception {
		return (Long)invoke(continuity, "pruneDeadAcyclicAlternatives",
			new Class<?>[] {Map.class, List.class, budget.getClass()},
			graph, completionOrder, budget);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> componentMemo(NativePlacementContinuity continuity)
		throws Exception {
		return (Map<Object,Object>)fieldValue(continuity, "acyclicComponentMemo");
	}

	private static Set<Object> realStates(Map<Object,List<Object>> graph) throws Exception {
		Set<Object> result = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for(Object state : graph.keySet())
			if(accessor(state, "axisGate") == null)
				result.add(state);
		return result;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static long directWork(SearchSpaceMetrics metrics, String name) throws Exception {
		Class<? extends Enum> type = (Class<? extends Enum>)Class.forName(
			SearchSpaceMetrics.class.getName() + "$DirectWork");
		Object work = Enum.valueOf(type, name);
		Method count = SearchSpaceMetrics.class.getDeclaredMethod("directWorkCount", type);
		count.setAccessible(true);
		return (Long)count.invoke(metrics, work);
	}

	private static void remember(NativePlacementContinuity continuity, Object gate,
		Object traversal) throws Exception {
		invoke(continuity, "rememberQueryNativeAxisGate",
			new Class<?>[] {gate.getClass(), traversal.getClass()}, gate, traversal);
	}

	private static Object query(NativePlacementContinuity continuity, Object key,
		Object traversal) throws Exception {
		return invoke(continuity, "queryNativeAxisGate",
			new Class<?>[] {key.getClass(), traversal.getClass()}, key, traversal);
	}

	private static NativePlacementContinuity continuity() {
		return new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), new SearchSpaceMetrics());
	}

	private static Object witness(NativePlacementContinuity continuity, String name)
		throws Exception {
		return invoke(continuity, "nativeWitness", new Class<?>[] {DurableAnchorKey.class},
			new DurableAnchorKey(name, FType.ROW,
				List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L)))));
	}

	private static Object gateKey(Object witness, int axis) throws Exception {
		return construct(nested("NativeAxisGateKey"), new Class<?>[] {
			PlacementAnalysis.CandidateRuleFact.class,
			PlacementAnalysis.CandidateEmissionRealization.class,
			NativeContinuitySupportClauses.class,
			witness.getClass(), int.class, int.class},
			null, null, null, witness, -1, axis);
	}

	private static Object gate(Object key, CompiledHopKey owner, Object witness,
		List<?> alternatives) throws Exception {
		return construct(nested("CandidateAxisGate"), new Class<?>[] {
			key.getClass(), CompiledHopKey.class, int.class, witness.getClass(), List.class},
			key, owner, 0, witness, alternatives);
	}

	private static Object gateDependency(Object gate) throws Exception {
		return construct(nested("CandidateProofDependency"),
			new Class<?>[] {gate.getClass()}, gate);
	}

	private static Object dependency(CompiledHopKey owner, Object witness, int handle,
		int position) throws Exception {
		return construct(nested("CandidateProofDependency"), new Class<?>[] {
			CompiledHopKey.class, PlacementIdentity.CandidateRealizationReference.class,
			int.class, witness.getClass(), int.class, boolean.class},
			owner, null, handle, witness, position, false);
	}

	private static Object dependencyForState(CompiledHopKey owner, Object witness,
		int handle, int position) throws Exception {
		return dependency(owner, witness, handle, position);
	}

	private static Object proof(List<?> dependencies, Object witness, boolean directGround)
		throws Exception {
		return construct(nested("SelectedCandidateProof"), new Class<?>[] {
			PlacementIdentity.CandidateRealizationReference.class, List.class,
			boolean.class, witness.getClass()}, null, dependencies, directGround, witness);
	}

	private static CompiledHopKey key(String name) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod("key", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static Class<?> nested(String name) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static Object construct(Class<?> type, Class<?>[] signature,
		Object... arguments) throws Exception {
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

	private static Object accessor(Object receiver, String name) throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(receiver);
	}

	private static Object fieldValue(Object receiver, String name) throws Exception {
		Field field = receiver.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(receiver);
	}

	private static long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}

	private static void withLimits(int maxEntries, long maxStates, long maxRows,
		ThrowingRunnable body) throws Exception {
		String priorEntries = System.getProperty(ENTRIES);
		String priorStates = System.getProperty(STATES);
		String priorRows = System.getProperty(ROWS);
		System.setProperty(ENTRIES, Integer.toString(maxEntries));
		System.setProperty(STATES, Long.toString(maxStates));
		System.setProperty(ROWS, Long.toString(maxRows));
		try {
			body.run();
		}
		finally {
			restore(ENTRIES, priorEntries);
			restore(STATES, priorStates);
			restore(ROWS, priorRows);
		}
	}

	private static void restore(String property, String value) {
		if(value == null)
			System.clearProperty(property);
		else
			System.setProperty(property, value);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
