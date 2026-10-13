/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.junit.Assert;
import org.junit.Test;

public class NativeAxisGateComponentReuseTest {
	@Test
	public void cyclicRootGateAdmitsOnlyIndependentNegativeChildWithCompleteFootprint()
		throws Exception {
		CompiledHopKey rootOwner = key("gate-cycle-root");
		CompiledHopKey dependentOwner = key("gate-cycle-dependent");
		CompiledHopKey independentOwner = key("gate-cycle-independent");
		CompiledHopKey hiddenOwner = key("gate-cycle-hidden");
		Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		for(CompiledHopKey owner : List.of(rootOwner, dependentOwner, independentOwner, hiddenOwner))
			nodes.put(owner, node(owner));
		NativePlacementContinuity continuity = new NativePlacementContinuity(nodes, Map.of(),
			List.of(), List.of(new CompiledInputEdgeFact(rootOwner, dependentOwner, 0),
				new CompiledInputEdgeFact(dependentOwner, rootOwner, 0)), Map.of());
		Object witness = invoke(continuity, "nativeWitness",
			new Class<?>[] {DurableAnchorKey.class}, pool());
		Object root = state(rootOwner, witness, 1);
		Object dependent = dependency(dependentOwner, witness, 2, 0);
		Object independent = dependency(independentOwner, witness, 3, 0);
		Object dependentState = accessor(dependent, "state");
		Object independentState = accessor(independent, "state");

		Class<?> proofType = nested("SelectedCandidateProof");
		List<Object> gateAlternatives = List.of(
			proof(proofType, List.of(dependent), witness),
			proof(proofType, List.of(independent), witness));
		Object gate = construct(nested("CandidateAxisGate"),
			new Class<?>[] {CompiledHopKey.class, int.class, witness.getClass(), List.class},
			dependentOwner, 0, witness, gateAlternatives);
		Object gateDependency = construct(nested("CandidateProofDependency"),
			new Class<?>[] {gate.getClass()}, gate);
		Object gateState = accessor(gateDependency, "state");
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		graph.put(root, List.of(proof(proofType, List.of(gateDependency), witness)));
		graph.put(gateState, gateAlternatives);
		graph.put(dependentState, List.of());
		graph.put(independentState, List.of());
		Object traversal = construct(nested("CandidateProofTraversal"), new Class<?>[0]);
		identityOwnerReads(traversal, independentState).add(hiddenOwner);

		@SuppressWarnings("unchecked")
		Map<Object,Object> footprints = (Map<Object,Object>)invoke(continuity,
			"rootIndependentChildFootprints",
			new Class<?>[] {nested("CandidateProofState"), Map.class, traversal.getClass()},
			root, graph, traversal);
		Assert.assertEquals("the real child in the root SCC must stay ineligible",
			List.of(independentState), List.copyOf(footprints.keySet()));
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> occurrences =
			(Set<CompiledHopKey>)accessor(footprints.get(independentState), "occurrences");
		Assert.assertEquals(2, occurrences.size());
		Assert.assertTrue(occurrences.stream().anyMatch(owner -> owner == independentOwner));
		Assert.assertTrue(occurrences.stream().anyMatch(owner -> owner == hiddenOwner));
		Assert.assertFalse(occurrences.stream().anyMatch(owner -> owner == rootOwner));
		Assert.assertFalse(occurrences.stream().anyMatch(owner -> owner == dependentOwner));

		invoke(continuity, "cacheAcyclicRootChildren",
			new Class<?>[] {Map.class, Map.class, Set.class, CompiledHopKey.class,
				traversal.getClass()}, footprints, graph, Set.of(), rootOwner, traversal);
		Map<Object,Object> memo = mutableMap(continuity, "acyclicComponentMemo");
		Assert.assertEquals(List.of(independentState), List.copyOf(memo.keySet()));
		Assert.assertTrue("an unsupported independent child remains a reusable negative summary",
			((List<?>)accessor(memo.get(independentState), "supportedAlternatives")).isEmpty());
		@SuppressWarnings("unchecked")
		Set<CompiledHopKey> retained =
			(Set<CompiledHopKey>)accessor(memo.get(independentState), "occurrences");
		Assert.assertEquals(occurrences.size(), retained.size());
		for(CompiledHopKey owner : occurrences)
			Assert.assertTrue("the admitted summary must retain every identity owner",
				retained.stream().anyMatch(candidate -> candidate == owner));
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> identityOwnerReads(Object traversal, Object state)
		throws Exception {
		Field field = traversal.getClass().getDeclaredField("hiddenOwnerReadsByState");
		field.setAccessible(true);
		Map<Object,Set<CompiledHopKey>> reads =
			(Map<Object,Set<CompiledHopKey>>)field.get(traversal);
		Set<CompiledHopKey> owners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		reads.put(state, owners);
		return owners;
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> mutableMap(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (Map<Object,Object>)field.get(target);
	}

	private static Object state(CompiledHopKey owner, Object witness, int handle)
		throws Exception {
		return construct(nested("CandidateProofState"), new Class<?>[] {CompiledHopKey.class,
			PlacementIdentity.CandidateRealizationReference.class, int.class,
			witness.getClass(), boolean.class}, owner, null, handle, witness, false);
	}

	private static Object dependency(CompiledHopKey owner, Object witness, int handle,
		int position) throws Exception {
		return construct(nested("CandidateProofDependency"), new Class<?>[] {CompiledHopKey.class,
			PlacementIdentity.CandidateRealizationReference.class, int.class,
			witness.getClass(), int.class, boolean.class},
			owner, null, handle, witness, position, false);
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

	private static CompiledHopKey key(String name) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod("key", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static DurableAnchorKey pool() {
		return new DurableAnchorKey("gate-cycle-pool", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L))));
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

	private static Object accessor(Object receiver, String name) throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(receiver);
	}
}
