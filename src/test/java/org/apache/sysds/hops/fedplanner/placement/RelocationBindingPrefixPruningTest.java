/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact-owner conflicts must be rejected before relocation product leaves. */
public class RelocationBindingPrefixPruningTest {
	private static final PlacementEmissionState ROW = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void mostConstrainedInputIsChosenBeforeIndependentHundredWayDomain() {
		CandidateRuleKey ownerA = rule("mrv-a"), ownerB = rule("mrv-b");
		List<CandidateRealizationInputBinding> independent = new ArrayList<>();
		List<CandidateRealizationInputBinding> constrained = new ArrayList<>();
		for(int option = 0; option < 100; option++) {
			independent.add(direct(0, source(ownerB, "mrv-b" + option, "localhost:18101", 8 + option)));
			constrained.add(direct(1, source(ownerA, "mrv-a" + option, "localhost:18102", 8 + option)));
		}
		CandidateRealizationReference fixed = constrained.get(73).source();
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			independent, constrained, List.of(direct(2, fixed)));
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure.enumerateBindingAssignments(
			choices, 0, new ArrayList<>(), actual::add, metrics);
		Assert.assertEquals(bruteForce(choices), actual);
		Assert.assertEquals(100, actual.size());
		Assert.assertEquals("fixed last input narrows the middle input before independent branching",
			103, metrics.snapshot().relocationPrefixes());
	}

	@Test
	public void contradictoryRepeatedOwnerChoicesArePrunedAtThePrefix() {
		CandidateRuleKey ownerA = rule("owner-a"), ownerB = rule("owner-b");
		CandidateRealizationReference a1 = source(ownerA, "a1", "localhost:18101", 8);
		CandidateRealizationReference a2 = source(ownerA, "a2", "localhost:18102", 13);
		CandidateRealizationReference b1 = source(ownerB, "b1", "localhost:18101", 5);
		CandidateRealizationReference b2 = source(ownerB, "b2", "localhost:18103", 21);
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			List.of(direct(0, a1), direct(0, a2)),
			List.of(direct(1, b1), direct(1, b2)),
			List.of(direct(2, a1), direct(2, a2)));
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		PlacementRelationClosure.enumerateBindingAssignments(
			choices, 0, new ArrayList<>(), actual::add, metrics);

		Assert.assertEquals(List.of(
			List.of(direct(0, a1), direct(1, b1), direct(2, a1)),
			List.of(direct(0, a1), direct(1, b2), direct(2, a1)),
			List.of(direct(0, a2), direct(1, b1), direct(2, a2)),
			List.of(direct(0, a2), direct(1, b2), direct(2, a2))), actual);
		Assert.assertEquals("half of the Cartesian leaves conflict on owner A", 4,
			metrics.snapshot().relocationLeaves());
		Assert.assertEquals("the repeated-owner axis is narrowed before independent branching", 9,
			metrics.snapshot().relocationPrefixes());
	}

	@Test
	public void alreadyCoherentChoicesRetainCanonicalNestedLoopOrder() {
		CandidateRealizationReference a = source(rule("coherent-a"), "a", "localhost:18101", 8);
		CandidateRealizationReference b1 = source(rule("coherent-b1"), "b1", "localhost:18102", 9);
		CandidateRealizationReference b2 = source(rule("coherent-b2"), "b2", "localhost:18103", 10);
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			List.of(direct(0, a)), List.of(direct(1, b1), direct(1, b2)), List.of(direct(2, a)));
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();

		PlacementRelationClosure.enumerateBindingAssignments(
			choices, 0, new ArrayList<>(), actual::add, null);

		Assert.assertEquals(List.of(
			List.of(direct(0, a), direct(1, b1), direct(2, a)),
			List.of(direct(0, a), direct(1, b2), direct(2, a))), actual);
	}

	@Test
	public void differentOwnersAreIndependentOfEndpointGeometry() {
		CandidateRuleKey ownerA = rule("geometry-a"), ownerB = rule("geometry-b");
		CandidateRealizationReference a1 = source(ownerA, "a1", "localhost:18101", 8);
		CandidateRealizationReference a2 = source(ownerA, "a2", "localhost:18102", 16);
		CandidateRealizationReference b1 = source(ownerB, "b1", "localhost:18101", 8);
		CandidateRealizationReference b2 = source(ownerB, "b2", "localhost:18104", 32);
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();

		PlacementRelationClosure.enumerateBindingAssignments(List.of(
			List.of(direct(0, a1), direct(0, a2)), List.of(direct(1, b1), direct(1, b2))),
			0, new ArrayList<>(), actual::add, null);

		Assert.assertEquals(List.of(
			List.of(direct(0, a1), direct(1, b1)), List.of(direct(0, a1), direct(1, b2)),
			List.of(direct(0, a2), direct(1, b1)), List.of(direct(0, a2), direct(1, b2))), actual);
	}

	@Test
	public void impossibleFutureOwnerDomainStopsBeforeIndependentBranching() {
		CandidateRuleKey ownerA = rule("forward-a"), ownerB = rule("forward-b");
		CandidateRealizationReference a1 = source(ownerA, "forward-a1", "localhost:18101", 8);
		CandidateRealizationReference a2 = source(ownerA, "forward-a2", "localhost:18102", 13);
		List<CandidateRealizationInputBinding> independent = new ArrayList<>();
		for(int index = 0; index < 5; index++)
			independent.add(direct(1, source(ownerB, "forward-b" + index,
				"localhost:" + (18200 + index), 20 + index)));
		List<List<CandidateRealizationInputBinding>> choices = List.of(
			List.of(direct(0, a1), direct(0, a2)), List.copyOf(independent), List.of(direct(2, a1)));
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		PlacementRelationClosure.enumerateBindingAssignments(
			choices, 0, new ArrayList<>(), actual::add, metrics);

		Assert.assertEquals(5, actual.size());
		Assert.assertTrue(actual.stream().allMatch(assignment -> assignment.get(0).source().equals(a1)));
		Assert.assertEquals("the impossible a2 prefix stops before the five-way independent domain",
			8, metrics.snapshot().relocationPrefixes());
	}

	@Test
	public void independentOwnersRetainFullPrefixTreeAndCanonicalOrder() {
		List<List<CandidateRealizationInputBinding>> choices = new ArrayList<>();
		for(int ordinal = 0; ordinal < 3; ordinal++) {
			CandidateRuleKey owner = rule("independent-" + ordinal);
			choices.add(List.of(
				direct(ordinal, source(owner, "independent-" + ordinal + "-0", "localhost:18301", 8)),
				direct(ordinal, source(owner, "independent-" + ordinal + "-1", "localhost:18302", 9))));
		}
		List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		PlacementRelationClosure.enumerateBindingAssignments(
			choices, 0, new ArrayList<>(), actual::add, metrics);

		Assert.assertEquals(bruteForce(choices), actual);
		Assert.assertEquals(8, metrics.snapshot().relocationLeaves());
		Assert.assertEquals("independent domains retain the ordinary full binary prefix tree",
			15, metrics.snapshot().relocationPrefixes());
	}

	@Test
	public void randomizedProductsMatchIndependentBruteForceAndPreserveInputPositions() {
		Random random = new Random(20261008L);
		CandidateRuleKey[] owners = {rule("random-a"), rule("random-b"), rule("random-c")};
		CandidateRealizationReference[][] sources = new CandidateRealizationReference[owners.length][2];
		for(int owner = 0; owner < owners.length; owner++)
			for(int source = 0; source < sources[owner].length; source++)
				sources[owner][source] = source(owners[owner], "random-" + owner + '-' + source,
					"localhost:" + (18400 + owner * 10 + source), 8 + owner + source);
		for(int trial = 0; trial < 250; trial++) {
			int dimensions = random.nextInt(6);
			List<List<CandidateRealizationInputBinding>> choices = new ArrayList<>();
			for(int ordinal = 0; ordinal < dimensions; ordinal++) {
				int optionCount = random.nextInt(5);
				boolean[][] used = new boolean[owners.length][2];
				List<CandidateRealizationInputBinding> domain = new ArrayList<>();
				while(domain.size() < optionCount) {
					int owner = random.nextInt(owners.length);
					int source = random.nextInt(2);
					if(!used[owner][source]) {
						used[owner][source] = true;
						domain.add(direct(ordinal, sources[owner][source]));
					}
				}
				choices.add(List.copyOf(domain));
			}
			List<List<CandidateRealizationInputBinding>> actual = new ArrayList<>();
			PlacementRelationClosure.enumerateBindingAssignments(
				List.copyOf(choices), 0, new ArrayList<>(), actual::add, null);
			List<List<CandidateRealizationInputBinding>> expected = bruteForce(choices);
			Assert.assertEquals("MRV must neither duplicate nor lose a legal tuple", expected.size(), actual.size());
			Assert.assertEquals("trial=" + trial + " choices=" + choices,
				new java.util.HashSet<>(expected), new java.util.HashSet<>(actual));
		}
	}

	private static List<List<CandidateRealizationInputBinding>> bruteForce(
		List<List<CandidateRealizationInputBinding>> choices) {
		List<List<CandidateRealizationInputBinding>> result = new ArrayList<>();
		bruteForce(choices, 0, new ArrayList<>(), result);
		return result;
	}

	private static void bruteForce(List<List<CandidateRealizationInputBinding>> choices, int ordinal,
		List<CandidateRealizationInputBinding> current,
		List<List<CandidateRealizationInputBinding>> result) {
		if(ordinal == choices.size()) {
			Map<CompiledHopKey,CandidateRealizationReference> selected = new IdentityHashMap<>();
			for(CandidateRealizationInputBinding binding : current) {
				CandidateRealizationReference prior = selected.putIfAbsent(
					binding.source().rule().parentOccurrence(), binding.source());
				if(prior != null && !prior.equals(binding.source()))
					return;
			}
			result.add(List.copyOf(current));
			return;
		}
		for(CandidateRealizationInputBinding binding : choices.get(ordinal)) {
			current.add(binding);
			bruteForce(choices, ordinal + 1, current, result);
			current.remove(current.size() - 1);
		}
	}

	private static CandidateRealizationInputBinding direct(
		int position, CandidateRealizationReference source) {
		return CandidateRealizationInputBinding.direct(position, source);
	}

	private static CandidateRealizationReference source(CandidateRuleKey rule,
		String id, String endpoint, long rows) {
		DurableAnchorKey anchor = new DurableAnchorKey("prefix-" + id, FType.ROW,
			List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(rows, 2L))));
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.durable(ROW, anchor, List.of(), List.of()));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"prefix-test", "main", List.of("root"), "root", "compiled");
		return new CandidateRuleKey(new CompiledHopKey(
			"prefix-test", "main", "root", "compiled", region, name, name), List.of());
	}
}
