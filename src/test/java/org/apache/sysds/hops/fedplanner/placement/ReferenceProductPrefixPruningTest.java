/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class ReferenceProductPrefixPruningTest {
	private static final PlacementEmissionState ROW = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void conflictingRepeatedOwnerPrefixDoesNotVisitRemainingAxes() {
		CandidateRuleKey owner = rule("owner");
		CandidateRealizationReference first = source(owner, "first", "worker-a:18101");
		CandidateRealizationReference conflicting = source(owner, "conflicting", "worker-b:18101");
		CountingAxis remaining = new CountingAxis(source(rule("remaining"), "remaining", "worker-c:18101"));
		List<List<CandidateRealizationReference>> products = new ArrayList<>();

		PlacementRelationClosure.enumerateReferenceProducts(List.of(
			List.of(first), List.of(conflicting), remaining), 0, new ArrayList<>(), products);

		Assert.assertTrue(products.isEmpty());
		Assert.assertEquals("an illegal prefix must not expand any later axis", 0, remaining.visits);
	}

	@Test
	public void legalProductsMatchExhaustiveOwnerFilterWithoutDeduplicatingOrReordering() {
		CandidateRuleKey ownerA = rule("owner-a");
		CandidateRealizationReference a1 = source(ownerA, "a1", "worker-a:18101");
		CandidateRealizationReference a2 = source(ownerA, "a2", "worker-b:18101");
		CandidateRealizationReference b1 = source(rule("owner-b"), "b1", "worker-a:18101");
		CandidateRealizationReference b2 = source(rule("owner-c"), "b2", "worker-c:18101");
		List<List<CandidateRealizationReference>> choices = List.of(
			List.of(a1, a1, a2), List.of(b1, b2), List.of(a1, a2));
		List<List<CandidateRealizationReference>> expected = exhaustiveLegalProducts(choices);
		List<List<CandidateRealizationReference>> actual = new ArrayList<>();

		PlacementRelationClosure.enumerateReferenceProducts(
			choices, 0, new ArrayList<>(), actual);

		Assert.assertEquals(expected, actual);
		Assert.assertEquals("equal input alternatives remain distinct product occurrences", 6, actual.size());
		Assert.assertEquals("different owners may use heterogeneous worker pools", b2, actual.get(1).get(1));
	}

	private static List<List<CandidateRealizationReference>> exhaustiveLegalProducts(
		List<List<CandidateRealizationReference>> choices) {
		List<List<CandidateRealizationReference>> result = new ArrayList<>();
		for(CandidateRealizationReference left : choices.get(0))
			for(CandidateRealizationReference middle : choices.get(1))
				for(CandidateRealizationReference right : choices.get(2)) {
					List<CandidateRealizationReference> row = List.of(left, middle, right);
					IdentityHashMap<CompiledHopKey,CandidateRealizationReference> selected = new IdentityHashMap<>();
					boolean legal = true;
					for(CandidateRealizationReference reference : row) {
						CandidateRealizationReference prior = selected.putIfAbsent(
							reference.rule().parentOccurrence(), reference);
						if(prior != null && !prior.equals(reference)) {
							legal = false;
							break;
						}
					}
					if(legal)
						result.add(row);
				}
		return result;
	}

	private static CandidateRealizationReference source(CandidateRuleKey rule, String id, String endpoint) {
		DurableAnchorKey anchor = new DurableAnchorKey("reference-prefix-" + id, FType.ROW,
			List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(8L, 2L))));
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.durable(ROW, anchor, List.of(), List.of()));
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"reference-prefix-test", "main", List.of("root"), "root", "compiled");
		return new CandidateRuleKey(new CompiledHopKey(
			"reference-prefix-test", "main", "root", "compiled", region, name, name), List.of());
	}

	private static final class CountingAxis extends AbstractList<CandidateRealizationReference> {
		private final CandidateRealizationReference reference;
		private int visits;

		private CountingAxis(CandidateRealizationReference reference) {
			this.reference = reference;
		}

		@Override
		public CandidateRealizationReference get(int index) {
			if(index != 0)
				throw new IndexOutOfBoundsException(index);
			visits++;
			return reference;
		}

		@Override
		public int size() {
			return 1;
		}
	}
}
