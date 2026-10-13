/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
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

public class NativeGateAdmissionBudgetTest {
	private static final String ENTRIES =
		"sysds.fedplanner.continuityAcyclicComponent.maxEntries";
	private static final String STATES =
		"sysds.fedplanner.continuityAcyclicComponent.maxStates";
	private static final String ROWS =
		"sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";

	@Test
	public void fullOrdinaryCacheRejectsGateBeforeFootprintTraversal() throws Exception {
		// entry/state/row saturation must independently reject before traversal.
		long[][] cases = {{1, 8, 8, 1, 0}, {4, 1, 8, 1, 0}, {4, 8, 1, 1, 1}};
		for(int index = 0; index < cases.length; index++) {
			long[] limits = cases[index];
			int caseIndex = index;
			withLimits((int)limits[0], limits[1], limits[2], () -> {
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity continuity = continuity(metrics);
				Object witness = witness(continuity, "full-cache-preflight-" + caseIndex);
				Object residentDependency = dependency(
					key("full-cache-resident-" + caseIndex), witness, 1, 0);
				Object residentState = accessor(residentDependency, "state");
				Object residentSummary = construct(nested("AcyclicComponentSummary"),
					new Class<?>[] {List.class, Set.class, long.class, long.class},
					List.of(), Set.of(key("full-cache-resident-occurrence-" + caseIndex)),
					limits[3], limits[4]);
				invoke(continuity, "cacheAcyclicSummary",
					new Class<?>[] {residentState.getClass(), residentSummary.getClass()},
					residentState, residentSummary);

				Object leafDependency = dependency(
					key("full-cache-leaf-" + caseIndex), witness, 2, 0);
				Object leafState = accessor(leafDependency, "state");
				List<Object> gateRows = List.of(proof(List.of(leafDependency), witness));
				Object gate = gate(key("full-cache-gate-" + caseIndex), witness, gateRows);
				Map<Object,List<Object>> graph = new LinkedHashMap<>();
				graph.put(accessor(gateDependency(gate), "state"), gateRows);
				graph.put(leafState, List.of());
				Map<Object,Object> footprints = new LinkedHashMap<>();

				remember(continuity, graph, newTraversal(), footprints);

				Assert.assertTrue("a saturated ordinary cache cannot admit an additional gate",
					footprints.isEmpty());
				Assert.assertEquals("capacity rejection must precede optional footprint traversal",
					0L, directWork(metrics, "COMPONENT_FOOTPRINT_REQUESTS"));
				Assert.assertEquals(1L, directWork(metrics,
					"COMPONENT_GATE_ADMISSION_CAPACITY_BYPASSES"));
			});
		}
	}

	@Test
	public void prunedGateStillRetainsDeadDependencyAndHiddenOwner() throws Exception {
		withLimits(4, 32, 32, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "pruned-gate-footprint");
			CompiledHopKey deadOwner = key("pruned-gate-dead-owner");
			CompiledHopKey deepDeadOwner = key("pruned-gate-deep-dead-owner");
			CompiledHopKey liveOwner = key("pruned-gate-live-owner");
			CompiledHopKey hiddenOwner = key("pruned-gate-hidden-owner");
			Object deepDeadDependency = dependency(deepDeadOwner, witness, 10, 0);
			Object deepDeadState = accessor(deepDeadDependency, "state");
			Object deadDependency = dependency(deadOwner, witness, 11, 0);
			Object deadState = accessor(deadDependency, "state");
			Object liveDependency = dependency(liveOwner, witness, 12, 0);
			Object liveState = accessor(liveDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(deadDependency), witness),
				proof(List.of(liveDependency), witness));
			Object gate = gate(key("pruned-gate"), witness, gateRows);
			Object gateState = accessor(gateDependency(gate), "state");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, gateRows);
			graph.put(deadState, List.of(proof(List.of(deepDeadDependency), witness)));
			graph.put(deepDeadState, List.of());
			graph.put(liveState, List.of(proof(List.of(), witness, true)));
			Object traversal = newTraversal();
			identityOwnerReads(traversal, deepDeadState).add(hiddenOwner);
			Object budget = newBudget(continuity);

			prune(continuity, graph,
				List.of(deepDeadState, deadState, liveState, gateState), budget);
			Assert.assertTrue("the dead ordinary descendant must be pruned",
				graph.get(deadState).isEmpty());
			Assert.assertEquals("the independent live gate row must survive", 1,
				graph.get(gateState).size());
			Assert.assertSame("pruning must preserve the exact surviving row authority",
				gateRows.get(1), graph.get(gateState).get(0));
			cacheGates(continuity, graph, traversal, Set.of(liveState, gateState), budget);

			Object summary = componentMemo(continuity).get(gateState);
			Assert.assertNotNull("the negative gate summary remains cacheable", summary);
			Assert.assertEquals("the cached summary must retain the one live result row", 1,
				((List<?>)accessor(summary, "supportedAlternatives")).size());
			@SuppressWarnings("unchecked")
			Set<CompiledHopKey> occurrences =
				(Set<CompiledHopKey>)accessor(summary, "occurrences");
			Assert.assertTrue(occurrences.stream().anyMatch(owner -> owner == deadOwner));
			Assert.assertTrue(occurrences.stream().anyMatch(owner -> owner == deepDeadOwner));
			Assert.assertTrue(occurrences.stream().anyMatch(owner -> owner == liveOwner));
			Assert.assertTrue(occurrences.stream().anyMatch(owner -> owner == hiddenOwner));
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_GATE_ADMISSION_SNAPSHOT_STATES") > 0);
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_GATE_ADMISSION_SNAPSHOT_ROWS") > 0);
			assertBudgetMetrics(metrics, 4, 32, 32, false);
		});
	}

	@Test
	public void generatedRootInOriginalDeadBranchRejectsGateSummary() throws Exception {
		withLimits(4, 32, 32, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "generated-root-dead-branch");
			CompiledHopKey generatedRoot = key("generated-root-owner");
			Object deadDependency = dependency(generatedRoot, witness, 15, 0);
			Object deadState = accessor(deadDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(deadDependency), witness));
			Object gate = gate(key("generated-root-gate"), witness, gateRows);
			Object gateState = accessor(gateDependency(gate), "state");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, gateRows);
			graph.put(deadState, List.of());
			Object budget = newBudget(continuity);

			prune(continuity, graph, List.of(deadState, gateState), budget);
			cacheGates(continuity, graph, newTraversal(), Set.of(), generatedRoot, budget);

			Assert.assertFalse(componentMemo(continuity).containsKey(gateState));
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_SUMMARY_ROOT_DEPENDENCE_REJECT") > 0);
		});
	}

	@Test
	public void journalExhaustionDoesNotChangePruningOutcomeOrPublishGate() throws Exception {
		withLimits(4, 2, 3, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "journal-exhaustion");
			Object deepDeadDependency = dependency(key("journal-deep-dead"), witness, 20, 0);
			Object deepDeadState = accessor(deepDeadDependency, "state");
			Object deadDependency = dependency(key("journal-dead"), witness, 21, 0);
			Object deadState = accessor(deadDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(deadDependency), witness));
			Object gate = gate(key("journal-gate"), witness, gateRows);
			Object gateState = accessor(gateDependency(gate), "state");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, gateRows);
			graph.put(deadState, List.of(proof(List.of(deepDeadDependency), witness)));
			graph.put(deepDeadState, List.of());
			Object budget = newBudget(continuity);

			prune(continuity, graph, List.of(deepDeadState, deadState, gateState), budget);
			Assert.assertTrue("the first overwritten owner must have been journaled",
				directWork(metrics, "COMPONENT_GATE_ADMISSION_SNAPSHOT_STATES") > 0);
			Assert.assertTrue(graph.get(deadState).isEmpty());
			Assert.assertTrue("optional journaling must not suppress mandatory pruning",
				graph.get(gateState).isEmpty());
			cacheGates(continuity, graph, newTraversal(), Set.of(), budget);

			Assert.assertFalse(componentMemo(continuity).containsKey(gateState));
			Assert.assertTrue((Boolean)fieldValue(budget, "exhausted"));
			Assert.assertNull("a partial authority journal must be discarded atomically",
				fieldValue(budget, "originalRows"));
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_GATE_ADMISSION_BUDGET_BYPASSES") > 0);
			assertBudgetMetrics(metrics, 4, 2, 3, true);
		});
	}

	@Test
	public void unchangedDagCreatesNoOriginalRowJournal() throws Exception {
		withLimits(4, 16, 16, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "unchanged-no-journal");
			Object liveDependency = dependency(key("unchanged-live"), witness, 25, 0);
			Object liveState = accessor(liveDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(liveDependency), witness));
			Object gate = gate(key("unchanged-gate"), witness, gateRows);
			Object gateState = accessor(gateDependency(gate), "state");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, gateRows);
			graph.put(liveState, List.of(proof(List.of(), witness, true)));
			Object budget = newBudget(continuity);

			Assert.assertEquals(0L, prune(continuity, graph,
				List.of(liveState, gateState), budget));

			Assert.assertSame(gateRows, graph.get(gateState));
			Assert.assertNull(fieldValue(budget, "originalRows"));
			Assert.assertFalse((Boolean)fieldValue(budget, "exhausted"));
			Assert.assertEquals(0L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_SNAPSHOT_STATES"));
			Assert.assertEquals(0L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_SNAPSHOT_ROWS"));
		});
	}

	@Test
	public void rootReplacementCanFreeCapacityForSettledGateAdmission() throws Exception {
		withLimits(2, 4, 16, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "root-replacement-capacity");
			Object rootState = accessor(dependency(
				key("replacement-root"), witness, 30, 0), "state");
			Object large = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class},
				List.of(), Set.of(key("replacement-root-large")), 4L);
			Object small = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class},
				List.of(), Set.of(key("replacement-root-small")), 1L);
			invoke(continuity, "cacheAcyclicSummary",
				new Class<?>[] {rootState.getClass(), large.getClass()}, rootState, large);
			invoke(continuity, "cacheAcyclicSummary",
				new Class<?>[] {rootState.getClass(), small.getClass()}, rootState, small);

			Object leafDependency = dependency(key("replacement-leaf"), witness, 31, 0);
			Object leafState = accessor(leafDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(leafDependency), witness));
			Object gate = gate(key("replacement-gate"), witness, gateRows);
			Object gateState = accessor(gateDependency(gate), "state");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, gateRows);
			graph.put(leafState, List.of());
			Map<Object,Object> admitted = new LinkedHashMap<>();

			remember(continuity, graph, newTraversal(), admitted);

			Assert.assertTrue("gate preflight must observe capacity after root replacement",
				admitted.containsKey(gateState));
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_GATE_ADMISSION_ADMISSIONS") > 0);
		});
	}

	@Test
	public void sameGateReplacementReceivesItsResidentCapacityCredits() throws Exception {
		withLimits(1, 3, 16, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "same-gate-capacity");
			Object leafDependency = dependency(key("same-gate-leaf"), witness, 40, 0);
			Object leafState = accessor(leafDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(leafDependency), witness));
			Object gate = gate(key("same-gate"), witness, gateRows);
			Object gateState = accessor(gateDependency(gate), "state");
			Object resident = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class},
				List.of(), Set.of(key("same-gate-old-a"), key("same-gate-old-b")), 3L);
			invoke(continuity, "cacheAcyclicSummary",
				new Class<?>[] {gateState.getClass(), resident.getClass()}, gateState, resident);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(gateState, gateRows);
			graph.put(leafState, List.of());
			Map<Object,Object> admitted = new LinkedHashMap<>();

			remember(continuity, graph, newTraversal(), admitted);

			Assert.assertTrue("same-key credits must allow a non-evicting replacement",
				admitted.containsKey(gateState));
			Assert.assertNotSame(resident, admitted.get(gateState));
			Assert.assertEquals(0L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_CAPACITY_BYPASSES"));
		});
	}

	@Test
	public void secondGateSeesCapacityConsumedByFirstActualAdmission() throws Exception {
		withLimits(1, 16, 32, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "streaming-capacity");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			Object firstLeaf = dependency(key("streaming-first-leaf"), witness, 50, 0);
			List<Object> firstRows = List.of(proof(List.of(firstLeaf), witness));
			Object firstGate = gate(key("streaming-first-gate"), witness, firstRows);
			Object firstState = accessor(gateDependency(firstGate), "state");
			graph.put(firstState, firstRows);
			graph.put(accessor(firstLeaf, "state"), List.of());
			Object secondLeaf = dependency(key("streaming-second-leaf"), witness, 51, 0);
			List<Object> secondRows = List.of(proof(List.of(secondLeaf), witness));
			Object secondGate = gate(key("streaming-second-gate"), witness, secondRows);
			Object secondState = accessor(gateDependency(secondGate), "state");
			graph.put(secondState, secondRows);
			graph.put(accessor(secondLeaf, "state"), List.of());
			Map<Object,Object> admitted = new LinkedHashMap<>();

			remember(continuity, graph, newTraversal(), admitted);

			Assert.assertTrue(admitted.containsKey(firstState));
			Assert.assertFalse(admitted.containsKey(secondState));
			Assert.assertEquals(1L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_ADMISSIONS"));
			Assert.assertEquals(2L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_ATTEMPTS"));
			Assert.assertEquals(1L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_FOOTPRINTS"));
			Assert.assertEquals(1L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_CAPACITY_BYPASSES"));
		});
	}

	// Defensive private-boundary fixtures below intentionally bypass the graph
	// builder so each optional-budget failure mode can be isolated deterministically.
	@Test
	public void alternatingGateAndRealChainHasOneAggregateAdmissionBudget()
		throws Exception {
		withLimits(64, 64, 512, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "alternating-gate-budget");
			Map<Object,List<Object>> graph = alternatingChain(witness, 12);
			Object traversal = newTraversal();
			CompiledHopKey hiddenOwner = key("alternating-hidden-owner");
			Object lastReal = null;
			for(Object state : graph.keySet())
				if(axisGate(state) == null)
					lastReal = state;
			Assert.assertNotNull(lastReal);
			identityOwnerReads(traversal, lastReal).add(hiddenOwner);
			Map<Object,List<Object>> original = new IdentityHashMap<>(graph);
			Map<Object,Object> footprints = new LinkedHashMap<>();

			remember(continuity, graph, traversal, footprints);

			Assert.assertFalse("at least one individually eligible suffix must be admitted",
				footprints.isEmpty());
			Assert.assertTrue("aggregate retained states, not only each suffix, must obey the cap",
				totalRetainedStates(footprints) <= 64);
			Assert.assertTrue("aggregate gate entries must obey the existing entry cap",
				footprints.size() <= 64);
			assertOnlyCompleteFootprints(graph, traversal, footprints);
			assertGraphUnchanged(graph, original);
			for(Object footprint : footprints.values())
				Assert.assertNotNull("a rejected gate must not publish a partial footprint", footprint);
			assertBudgetMetrics(metrics, 64, 64, 512, true);
		}	);
	}

	@Test
	public void wideDuplicateEdgesAndHiddenOwnersCannotMultiplyStaging()
		throws Exception {
		withLimits(3, 6, 10, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "wide-gate-budget");
			CompiledHopKey narrowOwner = key("wide-narrow-real");
			Object narrowDependency = dependency(narrowOwner, witness, 80, 0);
			Object narrowState = accessor(narrowDependency, "state");
			CompiledHopKey sharedOwner = key("wide-shared-real");
			Object sharedDependency = dependency(sharedOwner, witness, 90, 0);
			Object sharedState = accessor(sharedDependency, "state");
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			List<Object> narrowRows = List.of(proof(List.of(narrowDependency), witness));
			Object narrowGate = gate(key("wide-narrow-gate"), witness, narrowRows);
			graph.put(accessor(gateDependency(narrowGate), "state"), narrowRows);
			graph.put(narrowState, List.of());
			for(int gateIndex = 0; gateIndex < 18; gateIndex++) {
				List<Object> duplicateDependencies = Collections.nCopies(10, sharedDependency);
				Object row = proof(duplicateDependencies, witness);
				List<Object> alternatives = Collections.nCopies(4, row);
				Object gate = gate(key("wide-gate-" + gateIndex), witness, alternatives);
				Object gateState = accessor(gateDependency(gate), "state");
				graph.put(gateState, alternatives);
			}
			graph.put(sharedState, List.of());
			Object traversal = newTraversal();
			CompiledHopKey hiddenOwner = key("wide-hidden-owner");
			identityOwnerReads(traversal, sharedState).add(hiddenOwner);
			Map<Object,List<Object>> original = new IdentityHashMap<>(graph);
			Map<Object,Object> footprints = new LinkedHashMap<>();

			remember(continuity, graph, traversal, footprints);

			Assert.assertTrue("the optional pass must not stage more gates than cache entries",
				footprints.size() <= 3);
			Assert.assertTrue(totalRetainedStates(footprints) <= 6);
			for(Object footprint : footprints.values())
				Assert.assertNotNull("an exhausted wide gate must not leave a null marker", footprint);
			Assert.assertTrue("the small leading gate proves staged-state accounting is live",
				directWork(metrics, "COMPONENT_GATE_ADMISSION_STAGED_STATES") > 0);
			Assert.assertTrue("the small leading gate proves staged-row accounting is live",
				directWork(metrics, "COMPONENT_GATE_ADMISSION_STAGED_ROWS") > 0);
			Assert.assertTrue("the small leading gate proves staged-entry accounting is live",
				directWork(metrics, "COMPONENT_GATE_ADMISSION_STAGED_ENTRIES") > 0);
			assertBudgetMetrics(metrics, 3, 6, 10, true);
			assertOnlyCompleteFootprints(graph, traversal, footprints);
			assertGraphUnchanged(graph, original);
			for(Object footprint : footprints.values()) {
				@SuppressWarnings("unchecked")
				Set<CompiledHopKey> owners = (Set<CompiledHopKey>)accessor(footprint, "occurrences");
				if(owners.stream().anyMatch(owner -> owner == sharedOwner))
					Assert.assertTrue("a published shared-leaf footprint must include its hidden owner",
						owners.stream().anyMatch(owner -> owner == hiddenOwner));
			}
		});
	}

	@Test
	public void disabledComponentBudgetDoesNotCollectGateFootprints() throws Exception {
		long[][] limits = {{0, 64, 64}, {64, 0, 64}, {64, 64, 0}};
		for(long[] limit : limits)
			withLimits((int)limit[0], limit[1], limit[2], () -> {
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity continuity = continuity(metrics);
				Object witness = witness(continuity, "disabled-gate-budget");
				Map<Object,List<Object>> graph = alternatingChain(witness, 5);
				Map<Object,Object> footprints = new LinkedHashMap<>();

				remember(continuity, graph, newTraversal(), footprints);

				Assert.assertTrue(footprints.isEmpty());
				assertBudgetMetrics(metrics, limit[0], limit[1], limit[2], false);
				Assert.assertEquals(0L, directWork(metrics,
					"COMPONENT_GATE_ADMISSION_BUDGET_BYPASSES"));
			});
	}

	@Test
	public void duplicateHeavyOrdinaryDescendantExhaustsWorkWithoutPartialPublication()
		throws Exception {
		withLimits(4, 8, 24, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "duplicate-descendant-budget");
			Object leafDependency = dependency(key("duplicate-descendant-leaf"), witness, 202, 0);
			Object leafState = accessor(leafDependency, "state");
			Object childDependency = dependency(key("duplicate-descendant-child"), witness, 201, 0);
			Object childState = accessor(childDependency, "state");
			List<Object> childRows = List.of(proof(Collections.nCopies(32, leafDependency), witness));
			List<Object> gateRows = List.of(proof(List.of(childDependency), witness));
			Object gate = gate(key("duplicate-descendant-gate"), witness, gateRows);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(accessor(gateDependency(gate), "state"), gateRows);
			graph.put(childState, childRows);
			graph.put(leafState, List.of());
			Map<Object,List<Object>> original = new IdentityHashMap<>(graph);
			Map<Object,Object> footprints = new LinkedHashMap<>();

			remember(continuity, graph, newTraversal(), footprints);

			Assert.assertTrue("duplicate dependency work must not leave a partial footprint",
				footprints.isEmpty());
			Assert.assertTrue("the small gate must reach footprint collection before exhaustion",
				directWork(metrics, "COMPONENT_FOOTPRINT_REQUESTS") > 0);
			// Private accounting contract: 13 units cover scan, gate preflight/setup,
			// gate traversal, and the ordinary child row; unit 14 is its first edge.
			Assert.assertTrue("work must reach duplicate descendant-edge enqueueing",
				directWork(metrics, "COMPONENT_GATE_ADMISSION_WORK") >= 14);
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_GATE_ADMISSION_BUDGET_BYPASSES") > 0);
			assertBudgetMetrics(metrics, 4, 8, 24, true);
			assertOnlyCompleteFootprints(graph, newTraversal(), footprints);
			assertGraphUnchanged(graph, original);
		});
	}

	@Test
	public void largeHiddenOwnerSetIsRejectedByEffectiveStateCeiling()
		throws Exception {
		withLimits(4, 256, 4, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "hidden-owner-copy-budget");
			Object childDependency = dependency(key("hidden-owner-copy-child"), witness, 301, 0);
			Object childState = accessor(childDependency, "state");
			List<Object> gateRows = List.of(proof(List.of(childDependency), witness));
			Object gate = gate(key("hidden-owner-copy-gate"), witness, gateRows);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(accessor(gateDependency(gate), "state"), gateRows);
			graph.put(childState, List.of());
			Object traversal = newTraversal();
			Set<CompiledHopKey> hiddenOwners = identityOwnerReads(traversal, childState);
			for(int owner = 0; owner < 512; owner++)
				hiddenOwners.add(key("hidden-owner-copy-" + owner));
			Map<Object,List<Object>> original = new IdentityHashMap<>(graph);
			Map<Object,Object> footprints = new LinkedHashMap<>();

			remember(continuity, graph, traversal, footprints);

			Assert.assertTrue("an interrupted owner copy must not expose a partial identity set",
				footprints.isEmpty());
			Assert.assertTrue("the small gate must reach footprint collection before exhaustion",
				directWork(metrics, "COMPONENT_FOOTPRINT_REQUESTS") > 0);
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_FOOTPRINT_STATE_BYPASS") > 0);
			Assert.assertEquals(0L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_STAGED_ENTRIES"));
			Assert.assertTrue(directWork(metrics, "COMPONENT_GATE_ADMISSION_WORK")
				<= saturatedAdd(256, 4));
			assertOnlyCompleteFootprints(graph, traversal, footprints);
			assertGraphUnchanged(graph, original);
		});
	}

	@Test
	public void oversizedNestedSummaryIsRejectedByEffectiveStateCeiling()
		throws Exception {
		withLimits(4, 256, 24, () -> {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity continuity = continuity(metrics);
			Object witness = witness(continuity, "reused-owner-copy-budget");
			Object boundaryDependency = dependency(key("reused-owner-boundary"), witness, 403, 0);
			Object boundaryState = accessor(boundaryDependency, "state");
			Object childDependency = dependency(key("reused-owner-child"), witness, 402, 0);
			Object childState = accessor(childDependency, "state");
			List<Object> childRows = List.of(proof(List.of(boundaryDependency), witness));
			List<Object> gateRows = List.of(proof(List.of(childDependency), witness));
			Object gate = gate(key("reused-owner-gate"), witness, gateRows);
			Map<Object,List<Object>> graph = new LinkedHashMap<>();
			graph.put(accessor(gateDependency(gate), "state"), gateRows);
			graph.put(childState, childRows);
			graph.put(boundaryState, List.of());
			Object traversal = newTraversal();
			Set<CompiledHopKey> reusedOwners = Collections.newSetFromMap(new IdentityHashMap<>());
			for(int owner = 0; owner < 512; owner++)
				reusedOwners.add(key("reused-owner-copy-" + owner));
			Object summary = construct(nested("AcyclicComponentSummary"),
				new Class<?>[] {List.class, Set.class, long.class},
				List.of(), reusedOwners, 512L);
			putTraversalMap(traversal, "reusedComponents", boundaryState, summary);
			Map<Object,List<Object>> original = new IdentityHashMap<>(graph);
			Map<Object,Object> footprints = new LinkedHashMap<>();

			remember(continuity, graph, traversal, footprints);

			Assert.assertTrue("an interrupted reused-summary copy must remain unpublished",
				footprints.isEmpty());
			Assert.assertTrue("the nested boundary must be reached through the ordinary child",
				directWork(metrics, "COMPONENT_FOOTPRINT_REQUESTS") > 0);
			Assert.assertTrue(directWork(metrics,
				"COMPONENT_FOOTPRINT_STATE_BYPASS") > 0);
			Assert.assertEquals(0L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_STAGED_ENTRIES"));
			Assert.assertTrue(directWork(metrics, "COMPONENT_GATE_ADMISSION_WORK")
				<= saturatedAdd(256, 24));
			assertOnlyCompleteFootprints(graph, traversal, footprints);
			assertGraphUnchanged(graph, original);
		});
	}

	private static long totalRetainedStates(Map<Object,Object> footprints) throws Exception {
		long total = 0;
		for(Object footprint : footprints.values()) {
			Assert.assertNotNull("only complete footprints may be published", footprint);
			long retained = (Long)accessor(footprint, "retainedStates");
			total = saturatedAdd(total, retained);
		}
		return total;
	}

	private static Map<Object,List<Object>> alternatingChain(Object witness, int pairs)
		throws Exception {
		Map<Object,List<Object>> reversed = new LinkedHashMap<>();
		Object nextGateDependency = null;
		for(int index = pairs - 1; index >= 0; index--) {
			CompiledHopKey realOwner = key("alternating-real-" + index);
			Object realDependency = dependency(realOwner, witness, 100 + index, 0);
			Object realState = accessor(realDependency, "state");
			List<Object> realRows = nextGateDependency == null ? List.of()
				: List.of(proof(List.of(nextGateDependency), witness));
			Object gateRow = proof(List.of(realDependency), witness);
			List<Object> gateRows = List.of(gateRow);
			Object gate = gate(key("alternating-gate-" + index), witness, gateRows);
			Object gateDependency = gateDependency(gate);
			Object gateState = accessor(gateDependency, "state");
			reversed.put(realState, realRows);
			reversed.put(gateState, gateRows);
			nextGateDependency = gateDependency;
		}
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		List<Map.Entry<Object,List<Object>>> entries = List.copyOf(reversed.entrySet());
		for(int index = entries.size() - 1; index >= 0; index--)
			graph.put(entries.get(index).getKey(), entries.get(index).getValue());
		return graph;
	}

	private static void assertBudgetMetrics(SearchSpaceMetrics metrics, long maxEntries,
		long maxStates, long maxRows, boolean expectBypass) throws Exception {
		long work = directWork(metrics, "COMPONENT_GATE_ADMISSION_WORK");
		long stagedStates = directWork(metrics, "COMPONENT_GATE_ADMISSION_STAGED_STATES");
		long stagedRows = directWork(metrics, "COMPONENT_GATE_ADMISSION_STAGED_ROWS");
		long stagedEntries = directWork(metrics, "COMPONENT_GATE_ADMISSION_STAGED_ENTRIES");
		long snapshotStates = directWork(metrics,
			"COMPONENT_GATE_ADMISSION_SNAPSHOT_STATES");
		long snapshotRows = directWork(metrics,
			"COMPONENT_GATE_ADMISSION_SNAPSHOT_ROWS");
		Assert.assertTrue("optional gate work must share one query-local cap",
			work <= saturatedAdd(maxStates, maxRows));
		Assert.assertTrue("journal and admitted footprints must share the state cap",
			saturatedAdd(snapshotStates, stagedStates) <= maxStates);
		Assert.assertTrue("journal and admitted summaries must share the row cap",
			saturatedAdd(snapshotRows, stagedRows) <= maxRows);
		Assert.assertTrue(stagedEntries <= maxEntries);
		if(expectBypass)
			Assert.assertTrue("the deliberately oversized fixture must exhaust optional admission",
				directWork(metrics, "COMPONENT_GATE_ADMISSION_BUDGET_BYPASSES") > 0);
		else {
			Assert.assertEquals(0L, directWork(metrics,
				"COMPONENT_GATE_ADMISSION_BUDGET_BYPASSES"));
			if(maxEntries == 0 || maxStates == 0 || maxRows == 0) {
				Assert.assertEquals(0L, work);
				Assert.assertEquals(0L, stagedStates);
				Assert.assertEquals(0L, stagedRows);
				Assert.assertEquals(0L, stagedEntries);
				Assert.assertEquals(0L, snapshotStates);
				Assert.assertEquals(0L, snapshotRows);
			}
		}
	}

	private static void assertOnlyCompleteFootprints(Map<Object,List<Object>> graph,
		Object traversal, Map<Object,Object> footprints) throws Exception {
		for(Map.Entry<Object,Object> admitted : footprints.entrySet()) {
			Set<CompiledHopKey> expected = reachableOwners(admitted.getKey(), graph, traversal);
			@SuppressWarnings("unchecked")
			Set<CompiledHopKey> actual =
				(Set<CompiledHopKey>)accessor(admitted.getValue(), "occurrences");
			Assert.assertEquals(expected.size(), actual.size());
			for(CompiledHopKey owner : expected)
				Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == owner));
		}
	}

	private static Set<CompiledHopKey> reachableOwners(Object root,
		Map<Object,List<Object>> graph, Object traversal) throws Exception {
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<CompiledHopKey> owners = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Object> pending = new ArrayDeque<>();
		pending.add(root);
		while(!pending.isEmpty()) {
			Object state = pending.removeFirst();
			if(!visited.add(state))
				continue;
			owners.add((CompiledHopKey)accessor(state, "key"));
			owners.addAll(identityOwnerReads(traversal, state));
			for(Object alternative : graph.getOrDefault(state, List.of()))
				for(Object dependency : (List<?>)accessor(alternative, "dependencies"))
					pending.addLast(accessor(dependency, "state"));
		}
		return owners;
	}

	private static void assertGraphUnchanged(Map<Object,List<Object>> graph,
		Map<Object,List<Object>> original) {
		Assert.assertEquals(original.size(), graph.size());
		for(Map.Entry<Object,List<Object>> entry : original.entrySet())
			Assert.assertSame("optional collection must not rewrite proof rows",
				entry.getValue(), graph.get(entry.getKey()));
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> identityOwnerReads(Object traversal, Object state)
		throws Exception {
		Field field = traversal.getClass().getDeclaredField("hiddenOwnerReadsByState");
		field.setAccessible(true);
		Map<Object,Set<CompiledHopKey>> reads =
			(Map<Object,Set<CompiledHopKey>>)field.get(traversal);
		return reads.computeIfAbsent(state,
			ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	@SuppressWarnings("unchecked")
	private static void putTraversalMap(Object traversal, String fieldName,
		Object key, Object value) throws Exception {
		Field field = traversal.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		((Map<Object,Object>)field.get(traversal)).put(key, value);
	}

	private static void remember(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph, Object traversal, Map<Object,Object> footprints)
		throws Exception {
		Set<Object> supported = Collections.newSetFromMap(new IdentityHashMap<>());
		graph.forEach((state, rows) -> {
			if(!rows.isEmpty())
				supported.add(state);
		});
		Object budget = construct(nested("NativeGateAdmissionBudget"),
			new Class<?>[] {NativePlacementContinuity.class}, continuity);
		invoke(continuity, "cacheNativeGateComponents",
			new Class<?>[] {Map.class, traversal.getClass(), Set.class,
				CompiledHopKey.class, budget.getClass()},
			graph, traversal, supported, null, budget);
		Field memoField = NativePlacementContinuity.class.getDeclaredField(
			"acyclicComponentMemo");
		memoField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Object,Object> memo = (Map<Object,Object>)memoField.get(continuity);
		for(Object state : graph.keySet())
			if(axisGate(state) != null && memo.containsKey(state))
				footprints.put(state, memo.get(state));
	}

	private static Object newBudget(NativePlacementContinuity continuity) throws Exception {
		return construct(nested("NativeGateAdmissionBudget"),
			new Class<?>[] {NativePlacementContinuity.class}, continuity);
	}

	private static long prune(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph, List<Object> completionOrder, Object budget)
		throws Exception {
		return (Long)invoke(continuity, "pruneDeadAcyclicAlternatives",
			new Class<?>[] {Map.class, List.class, budget.getClass()},
			graph, completionOrder, budget);
	}

	private static void cacheGates(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph, Object traversal, Set<Object> supported,
		Object budget) throws Exception {
		cacheGates(continuity, graph, traversal, supported, null, budget);
	}

	private static void cacheGates(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph, Object traversal, Set<Object> supported,
		CompiledHopKey generatedRoot, Object budget) throws Exception {
		invoke(continuity, "cacheNativeGateComponents",
			new Class<?>[] {Map.class, traversal.getClass(), Set.class,
				CompiledHopKey.class, budget.getClass()},
			graph, traversal, supported, generatedRoot, budget);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> componentMemo(NativePlacementContinuity continuity)
		throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("acyclicComponentMemo");
		field.setAccessible(true);
		return (Map<Object,Object>)field.get(continuity);
	}

	private static NativePlacementContinuity continuity(SearchSpaceMetrics metrics) {
		return new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
	}

	private static Object witness(NativePlacementContinuity continuity, String name)
		throws Exception {
		return invoke(continuity, "nativeWitness",
			new Class<?>[] {DurableAnchorKey.class}, pool(name));
	}

	private static Object gate(CompiledHopKey owner, Object witness, List<?> alternatives)
		throws Exception {
		return construct(nested("CandidateAxisGate"),
			new Class<?>[] {nested("NativeAxisGateKey"), CompiledHopKey.class,
				int.class, witness.getClass(), List.class},
			null, owner, 0, witness, alternatives);
	}

	private static Object gateDependency(Object gate) throws Exception {
		return construct(nested("CandidateProofDependency"),
			new Class<?>[] {gate.getClass()}, gate);
	}

	private static Object dependency(CompiledHopKey owner, Object witness, int handle,
		int position) throws Exception {
		return construct(nested("CandidateProofDependency"),
			new Class<?>[] {CompiledHopKey.class,
				PlacementIdentity.CandidateRealizationReference.class, int.class,
				witness.getClass(), int.class, boolean.class},
			owner, null, handle, witness, position, false);
	}

	private static Object proof(List<?> dependencies, Object witness) throws Exception {
		return proof(dependencies, witness, false);
	}

	private static Object proof(List<?> dependencies, Object witness, boolean directGround)
		throws Exception {
		return construct(nested("SelectedCandidateProof"),
			new Class<?>[] {PlacementIdentity.CandidateRealizationReference.class,
				List.class, boolean.class, witness.getClass()},
			null, dependencies, directGround, witness);
	}

	private static Object axisGate(Object state) throws Exception {
		return accessor(state, "axisGate");
	}

	private static Object newTraversal() throws Exception {
		return construct(nested("CandidateProofTraversal"), new Class<?>[0]);
	}

	private static CompiledHopKey key(String name) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod("key", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static DurableAnchorKey pool(String name) {
		return new DurableAnchorKey(name, FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static long saturatedAdd(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
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
