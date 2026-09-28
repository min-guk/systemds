/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.List;

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
		Assert.assertEquals("conflicting final prefixes are not recursively visited", 11,
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
