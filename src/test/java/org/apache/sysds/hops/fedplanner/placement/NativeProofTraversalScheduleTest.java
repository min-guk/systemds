/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofTraversalScheduleTest {
	@Test
	public void wideDefaultDagRetainsRowsAndVisitsEachSuccessorOnce() throws Exception {
		Object witness = witness();
		CompiledHopKey left = owner("left"), right = owner("right");
		List<Object> dependencies = new ArrayList<>();
		for(int index = 0; index < 32; index++) {
			dependencies.add(dependency(index % 2 == 0 ? left : right, witness, index));
			dependencies.add(dependency(left, witness, index + 100));
		}
		Object emptyNonGround = alternative(List.of(), false, witness);
		Object directGround = alternative(List.of(), true, witness);
		Object wide = alternative(dependencies, false, witness);
		List<?> defaults = defaultList(List.of(emptyNonGround, directGround, wide));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Object first = schedule(defaults, metrics);
		Assert.assertEquals("the topology list remains unfiltered for fallback decisions", 3, defaults.size());
		Assert.assertSame(emptyNonGround, defaults.get(0));
		List<?> filtered = (List<?>)field(first, "filteredAlternatives");
		Assert.assertEquals(2, filtered.size());
		Assert.assertSame(directGround, filtered.get(0));
		Assert.assertSame(wide, filtered.get(1));
		Assert.assertEquals(64L, field(first, "rawDependencyCount"));
		List<?> successors = (List<?>)field(first, "uniqueSuccessors");
		Assert.assertEquals(2, successors.size());
		Assert.assertSame(left, field(successors.get(0), "key"));
		Assert.assertSame(right, field(successors.get(1), "key"));

		Assert.assertSame("the immutable schedule is lazy and topology-owned", first,
			schedule(defaults, metrics));
		SearchSpaceMetrics.Snapshot snapshot = metrics.snapshot();
		Assert.assertEquals(1, snapshot.proofDefaultScheduleBuilds());
		Assert.assertEquals(1, snapshot.proofDefaultScheduleHits());
		Assert.assertEquals(128, snapshot.proofDefaultRawSuccessorVisits());
		Assert.assertEquals(4, snapshot.proofDefaultUniqueSuccessorVisits());
	}

	@Test
	public void successorOrderHandlesCyclesWithoutChangingAlternativeMultiplicity() throws Exception {
		Object witness = witness();
		CompiledHopKey root = owner("root"), child = owner("child");
		Object first = alternative(List.of(dependency(root, witness, 0),
			dependency(child, witness, 1)), false, witness);
		Object second = alternative(List.of(dependency(child, witness, 2),
			dependency(root, witness, 3)), false, witness);
		List<?> defaults = defaultList(List.of(first, second));
		Object schedule = schedule(defaults, new SearchSpaceMetrics());

		Assert.assertEquals(List.of(first, second), field(schedule, "filteredAlternatives"));
		Assert.assertEquals(4L, field(schedule, "rawDependencyCount"));
		List<?> successors = (List<?>)field(schedule, "uniqueSuccessors");
		Assert.assertEquals(2, successors.size());
		Assert.assertSame(root, field(successors.get(0), "key"));
		Assert.assertSame(child, field(successors.get(1), "key"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void topologyOwnsIndependentWholeAndPinnedSchedulesAcrossReindex() throws Exception {
		Object witness = witness();
		CandidateRealizationReference firstReference = reference("first");
		CandidateRealizationReference secondReference = reference("second");
		Object first = topologyRow(firstReference,
			List.of(skeleton(owner("shared"), witness, 0)), witness);
		Object second = topologyRow(secondReference,
			List.of(skeleton(owner("shared"), witness, 1)), witness);
		Object topology = topology(List.of(first, second), Map.of(7, List.of(first)));
		List<?> allDefaults = (List<?>)field(topology, "defaultAlternatives");
		Map<Integer,List<?>> byHandle = (Map<Integer,List<?>>)field(topology,
			"defaultAlternativesByHandle");
		Assert.assertEquals(nested("DefaultAlternativeList"), allDefaults.getClass());
		Assert.assertEquals(nested("DefaultAlternativeList"), byHandle.get(7).getClass());
		Assert.assertNotSame(allDefaults, byHandle.get(7));
		Assert.assertNotSame(schedule(allDefaults, new SearchSpaceMetrics()),
			schedule(byHandle.get(7), new SearchSpaceMetrics()));

		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of());
		Method reindex = method(NativePlacementContinuity.class, "reindexTopology",
			nested("CandidateTopology"));
		Object reindexed = reindex.invoke(continuity, topology);
		List<?> revisedDefaults = (List<?>)field(reindexed, "defaultAlternatives");
		Assert.assertEquals(nested("DefaultAlternativeList"), revisedDefaults.getClass());
		Assert.assertNotSame("a reindexed revision cannot retain stale handles or schedules",
			allDefaults, revisedDefaults);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void reusedNegativeComponentIsAnEmptyStateForTheNoDeadSeedCertificate() throws Exception {
		Object witness = witness();
		CompiledHopKey childOwner = owner("cached-negative");
		Object dependency = dependency(childOwner, witness, 0);
		Object state = field(dependency, "state");
		Constructor<?> summaryConstructor = nested("AcyclicComponentSummary").getDeclaredConstructor(
			List.class, java.util.Set.class, long.class);
		summaryConstructor.setAccessible(true);
		Object negative = summaryConstructor.newInstance(List.of(), java.util.Set.of(childOwner), 1L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		((Map<Object,Object>)field(continuity, "acyclicComponentMemo")).put(state, negative);
		CandidateRealizationReference rootReference = reference("cached-negative-parent");
		CompiledHopKey rootOwner = rootReference.rule().parentOccurrence();
		Object rootRow = topologyRow(rootReference,
			List.of(skeleton(childOwner, witness, 0)), witness);
		((Map<Object,Object>)field(continuity, "candidateTopologies")).put(
			topologyKey(rootOwner, witness), topology(List.of(rootRow), Map.of()));
		Object rootState = field(dependency(rootOwner, witness, 0), "state");
		Constructor<?> traversalConstructor = nested("CandidateProofTraversal").getDeclaredConstructor();
		traversalConstructor.setAccessible(true);
		Object traversal = traversalConstructor.newInstance();
		CountingContainsMap<Object,List<?>> graph = new CountingContainsMap<>();
		long[] graphWork = new long[2];
		Method build = method(NativePlacementContinuity.class, "buildCandidateProofGraph",
			nested("CandidateProofState"), nested("CandidateProofState"), nested("GenerationRoot"),
			Map.class, nested("CandidateProofTraversal"), Map.class, Map.class, long[].class);
		build.invoke(continuity, rootState, rootState, null, graph, traversal,
			new IdentityHashMap<>(), new IdentityHashMap<>(), graphWork);
		Assert.assertTrue(graph.get(state).isEmpty());
		Assert.assertEquals("a reused failed component must force ordinary dead pruning", 1L,
			field(traversal, "emptyFilteredStates"));
		graph.resetContainsCalls();
		Map<?,?> pruned = certifiedPrune(continuity, graph, traversal, graphWork[0]);
		Assert.assertTrue(((List<?>)pruned.get(state)).isEmpty());
		Assert.assertTrue("cached negative child propagates into its built parent",
			((List<?>)pruned.get(rootState)).isEmpty());
		Assert.assertEquals("a certified cached-negative seed needs no missing-dependency scan",
			0, graph.containsCalls);
		Assert.assertEquals(1, metrics.snapshot().ownerCompactionElementsScanned());
		Assert.assertEquals(2, metrics.snapshot().deadStatesQueued());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void builtClosedCycleCertificateSkipsAllInitialMembershipScans() throws Exception {
		Object witness = witness();
		CandidateRealizationReference rootReference = reference("certificate-root");
		CandidateRealizationReference childReference = reference("certificate-child");
		CompiledHopKey rootOwner = rootReference.rule().parentOccurrence();
		CompiledHopKey childOwner = childReference.rule().parentOccurrence();
		Object rootRow = topologyRow(rootReference,
			List.of(skeleton(childOwner, witness, 0)), witness);
		Object childRow = topologyRow(childReference,
			List.of(skeleton(rootOwner, witness, 0)), witness);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Map<Object,Object> topologies = (Map<Object,Object>)field(continuity, "candidateTopologies");
		topologies.put(topologyKey(rootOwner, witness), topology(List.of(rootRow), Map.of()));
		topologies.put(topologyKey(childOwner, witness), topology(List.of(childRow), Map.of()));

		Object rootState = field(dependency(rootOwner, witness, 0), "state");
		Constructor<?> traversalConstructor = nested("CandidateProofTraversal").getDeclaredConstructor();
		traversalConstructor.setAccessible(true);
		Object traversal = traversalConstructor.newInstance();
		CountingContainsMap<Object,List<?>> graph = new CountingContainsMap<>();
		long[] graphWork = new long[2];
		Method build = method(NativePlacementContinuity.class, "buildCandidateProofGraph",
			nested("CandidateProofState"), nested("CandidateProofState"), nested("GenerationRoot"),
			Map.class, nested("CandidateProofTraversal"), Map.class, Map.class, long[].class);
		build.invoke(continuity, rootState, rootState, null, graph, traversal,
			new IdentityHashMap<>(), new IdentityHashMap<>(), graphWork);
		Assert.assertEquals(2, graph.size());
		Assert.assertEquals(2L, graphWork[0]);
		Assert.assertEquals(true, field(traversal, "cycleDetected"));
		Assert.assertEquals(0L, field(traversal, "emptyFilteredStates"));
		for(List<?> alternatives : graph.values())
			for(Object alternative : alternatives)
				for(Object dependency : (List<?>)field(alternative, "dependencies"))
					Assert.assertTrue("the builder certificate requires every successor row",
						graph.containsKey(field(dependency, "state")));

		graph.resetContainsCalls();
		Map<?,?> pruned = certifiedPrune(continuity, graph, traversal, graphWork[0]);
		Assert.assertSame(graph, pruned);
		Assert.assertEquals("the complete nonempty builder certificate avoids membership scans",
			0, graph.containsCalls);
		Assert.assertEquals(2, metrics.snapshot().ownerCompactionElementsScanned());
	}

	@Test
	public void noEmptyDagSkipMetricIsExplicitAndDoesNotMasqueradeAsRemoval() {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		metrics.recordNoEmptyDagPruningSkip();
		SearchSpaceMetrics.Snapshot snapshot = metrics.snapshot();
		Assert.assertEquals(1, snapshot.proofNoEmptyDagPruningSkips());
		Assert.assertEquals(0, snapshot.acyclicAlternativesRemoved());
		Assert.assertEquals(0, snapshot.alternativesRemoved());
	}

	private static List<?> defaultList(List<?> alternatives) throws Exception {
		Constructor<?> constructor = nested("DefaultAlternativeList").getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return (List<?>)constructor.newInstance(alternatives);
	}

	private static Object schedule(List<?> alternatives, SearchSpaceMetrics metrics) throws Exception {
		Method method = method(nested("DefaultAlternativeList"), "traversalSchedule",
			SearchSpaceMetrics.class);
		return method.invoke(alternatives, metrics);
	}

	private static Object alternative(List<?> dependencies, boolean directGround,
		Object witness) throws Exception {
		Constructor<?> constructor = nested("SelectedCandidateProof").getDeclaredConstructor(
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		constructor.setAccessible(true);
		return constructor.newInstance(null, dependencies, directGround, witness);
	}

	private static Object dependency(CompiledHopKey owner, Object witness, int position)
		throws Exception {
		Constructor<?> constructor = nested("CandidateProofDependency").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), int.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner, null, 0, witness, position, false);
	}

	private static Object skeleton(CompiledHopKey owner, Object witness, int position)
		throws Exception {
		Constructor<?> constructor = nested("CandidateDependencySkeleton").getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return constructor.newInstance(owner, null, 0, witness, position);
	}

	private static Object topologyRow(CandidateRealizationReference reference,
		List<?> dependencies, Object witness) throws Exception {
		Method method = method(nested("CandidateTopologyRow"), "create",
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		return method.invoke(null, reference, dependencies, false, witness);
	}

	private static Object topology(List<?> rows, Map<Integer,? extends List<?>> rowsByHandle)
		throws Exception {
		Constructor<?> constructor = nested("CandidateTopology").getDeclaredConstructor(
			boolean.class, boolean.class, List.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(true, false, rows, rowsByHandle);
	}

	private static Object topologyKey(CompiledHopKey owner, Object witness) throws Exception {
		Constructor<?> constructor = nested("CandidateTopologyKey").getDeclaredConstructor(
			CompiledHopKey.class, nested("NativePoolWitness"));
		constructor.setAccessible(true);
		return constructor.newInstance(owner, witness);
	}

	@SuppressWarnings("unchecked")
	private static Map<?,?> certifiedPrune(NativePlacementContinuity continuity, Map<?,?> graph,
		Object traversal, long alternatives) throws Exception {
		Method method = method(NativePlacementContinuity.class, "pruneDeadAlternatives",
			Map.class, nested("CandidateProofTraversal"), long.class);
		return (Map<?,?>)method.invoke(continuity, graph, traversal, alternatives);
	}

	private static Object witness() throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.FULL, List.of("worker:8001"), List.of(), true);
	}

	private static CandidateRealizationReference reference(String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner(id), List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		return CandidateRealizationReference.of(rule, CandidateEmissionRealization.nativeLineage(emission, "schedule:" + id, List.of(), List.of()));
	}

	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"proof-schedule", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("proof-schedule", "main", "call", "compiled",
			region, id, "origin-" + id);
	}

	private static Method method(Class<?> owner, String name, Class<?>... parameters)
		throws Exception {
		Method method = owner.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static Class<?> nested(String simpleName) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + simpleName);
	}

	private static final class CountingContainsMap<K,V> extends java.util.LinkedHashMap<K,V> {
		private int containsCalls;
		@Override
		public boolean containsKey(Object key) {
			containsCalls++;
			return super.containsKey(key);
		}
		private void resetContainsCalls() { containsCalls = 0; }
	}
}
