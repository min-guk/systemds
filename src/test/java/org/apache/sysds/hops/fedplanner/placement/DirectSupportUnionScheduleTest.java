/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DirectSupportUnionScheduleTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"support-union", "main", List.of("root"), "call", "compiled");
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);

	@Test
	public void potentialOverlapKeepsOldChangedSignalButNotUnionSignal() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));
		Object index = index(absent, adjacency(source, owner));

		Object added = update(index, owner, List.of(supportFact(source, owner)));
		Assert.assertTrue(changed(added));
		Assert.assertFalse(dependencyUnionChanged(added));

		Object removed = update(index, owner, absent);
		Assert.assertTrue(changed(removed));
		Assert.assertFalse(dependencyUnionChanged(removed));
		Assert.assertTrue(removed(removed).get(source).contains(owner));
	}

	@Test
	public void nonPotentialAndIdentityDistinctEdgesChangeTheDependencyUnion() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		CompiledHopKey equalSource = key("source"), equalOwner = key("owner");
		Assert.assertEquals(source, equalSource);
		Assert.assertEquals(owner, equalOwner);
		Assert.assertNotSame(source, equalSource);
		Assert.assertNotSame(owner, equalOwner);
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));
		Object index = index(absent, adjacency(equalSource, equalOwner));

		Object added = update(index, owner, List.of(supportFact(source, owner)));
		Assert.assertTrue(changed(added));
		Assert.assertTrue("structurally equal potential endpoints are not identity authority",
			dependencyUnionChanged(added));
		Object removed = update(index, owner, absent);
		Assert.assertTrue(dependencyUnionChanged(removed));

		CompiledHopKey unknown = key("unknown");
		Object unknownIndex = index(absent, Map.of());
		Assert.assertTrue(dependencyUnionChanged(
			update(unknownIndex, owner, List.of(supportFact(unknown, owner)))));
	}

	@Test
	public void identityPairMembershipRequiresBothExactEndpoints() throws Exception {
		CompiledHopKey source = key("source"), owner = key("owner");
		CompiledHopKey equalSource = key("source"), equalOwner = key("owner");
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));

		Object distinctConsumer = index(absent, adjacency(source, equalOwner));
		Assert.assertTrue("the same source cannot authorize an equal but distinct consumer",
			dependencyUnionChanged(update(distinctConsumer, owner, List.of(supportFact(source, owner)))));

		Object distinctSource = index(absent, adjacency(equalSource, owner));
		Assert.assertTrue("the same consumer cannot authorize an equal but distinct source",
			dependencyUnionChanged(update(distinctSource, owner, List.of(supportFact(source, owner)))));
	}

	@Test
	public void mixedCoveredAndUncoveredDeltaChangesTheDependencyUnion() throws Exception {
		CompiledHopKey covered = key("covered"), uncovered = key("uncovered"), owner = key("owner");
		List<CandidateRuleFact> absent = List.of(excludedFact(owner));
		Object index = index(absent, adjacency(covered, owner));

		Object added = update(index, owner, List.of(supportFact(List.of(covered, uncovered), owner)));
		Assert.assertTrue(changed(added));
		Assert.assertTrue(dependencyUnionChanged(added));

		Object removed = update(index, owner, absent);
		Assert.assertTrue(changed(removed));
		Assert.assertTrue(dependencyUnionChanged(removed));
		Assert.assertTrue(removed(removed).get(covered).contains(owner));
		Assert.assertTrue(removed(removed).get(uncovered).contains(owner));
	}

	@Test
	public void selfEdgeAndCycleUseExactPairMembershipRatherThanSccHeuristics() throws Exception {
		CompiledHopKey a = key("a"), b = key("b");
		Map<CompiledHopKey,Set<CompiledHopKey>> potential = new IdentityHashMap<>();
		add(potential, a, a);
		add(potential, a, b);
		add(potential, b, a);

		Object self = index(List.of(excludedFact(a)), potential);
		Assert.assertFalse(dependencyUnionChanged(update(self, a, List.of(supportFact(a, a)))));
		Assert.assertFalse(dependencyUnionChanged(update(self, a, List.of(excludedFact(a)))));

		Object cycleEdge = index(List.of(excludedFact(b)), potential);
		Assert.assertFalse(dependencyUnionChanged(update(cycleEdge, b, List.of(supportFact(a, b)))));
		CompiledHopKey outside = key("outside");
		Assert.assertTrue("same SCC is not a substitute for exact potential-pair membership",
			dependencyUnionChanged(update(cycleEdge, b, List.of(supportFact(outside, b)))));
	}

	private static Object index(List<CandidateRuleFact> facts,
		Map<CompiledHopKey,Set<CompiledHopKey>> potential) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectSupportIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, List.class, Map.class);
		constructor.setAccessible(true);
		Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		for(int slot = 0; slot < facts.size(); slot++)
			slots.computeIfAbsent(facts.get(slot).key().parentOccurrence(), ignored -> new ArrayList<>()).add(slot);
		return constructor.newInstance(slots, facts, potential);
	}

	private static Object update(Object index, CompiledHopKey owner,
		List<CandidateRuleFact> facts) throws Exception {
		Method method = index.getClass().getDeclaredMethod("update", Set.class, List.class);
		method.setAccessible(true);
		Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		changed.add(owner);
		return method.invoke(index, changed, facts);
	}

	private static boolean changed(Object update) throws Exception {
		Method method = update.getClass().getDeclaredMethod("changed");
		method.setAccessible(true);
		return (boolean)method.invoke(update);
	}

	private static boolean dependencyUnionChanged(Object update) throws Exception {
		Method method = update.getClass().getDeclaredMethod("dependencyUnionChanged");
		method.setAccessible(true);
		return (boolean)method.invoke(update);
	}

	@SuppressWarnings("unchecked")
	private static Map<CompiledHopKey,Set<CompiledHopKey>> removed(Object update) throws Exception {
		Method method = update.getClass().getDeclaredMethod("removed");
		method.setAccessible(true);
		return (Map<CompiledHopKey,Set<CompiledHopKey>>)method.invoke(update);
	}

	private static Map<CompiledHopKey,Set<CompiledHopKey>> adjacency(
		CompiledHopKey source, CompiledHopKey owner) {
		Map<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>();
		add(result, source, owner);
		return result;
	}

	private static void add(Map<CompiledHopKey,Set<CompiledHopKey>> graph,
		CompiledHopKey source, CompiledHopKey owner) {
		graph.computeIfAbsent(source,
			ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(owner);
	}

	private static CandidateRuleFact supportFact(CompiledHopKey source, CompiledHopKey owner) {
		return supportFact(List.of(source), owner);
	}

	private static CandidateRuleFact supportFact(List<CompiledHopKey> sources, CompiledHopKey owner) {
		PlacementEmissionState emission = new PlacementEmissionState(LOCAL, false);
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(int inputIndex = 0; inputIndex < sources.size(); inputIndex++) {
			CandidateRuleKey sourceRule = new CandidateRuleKey(sources.get(inputIndex), List.of());
			CandidateRealizationReference sourceRef = CandidateRealizationReference.of(sourceRule,
				CandidateEmissionRealization.local(emission));
			bindings.add(CandidateRealizationInputBinding.direct(inputIndex, sourceRef));
		}
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(emission,
			List.of(), bindings);
		CandidateEmissionFact output = new CandidateEmissionFact(emission, null, null, List.of(realization));
		return fact(owner, CandidateEvaluationStatus.AVAILABLE, List.of(output), "");
	}

	private static CandidateRuleFact excludedFact(CompiledHopKey owner) {
		return fact(owner, CandidateEvaluationStatus.PRIVACY_EXCLUDED, List.of(), "PRIVATE_AGGREGATE");
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, CandidateEvaluationStatus status,
		List<CandidateEmissionFact> emissions, String failure) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()), status,
			new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emissions, failure);
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey("support-union", "main", "call", "compiled", REGION, name, name);
	}
}
