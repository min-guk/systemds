/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class CandidateRuleKeyEqualityTest {
	private record RecordOracle(CompiledHopKey parentOccurrence,
		List<CandidateInputState> orderedInputs) { }

	@Test
	public void indexedComparisonMatchesGeneratedRecordForOrderedInputs() {
		Random random = new Random(871239L);
		List<CandidateRuleKey> keys = new ArrayList<>();
		for(int sample = 0; sample < 160; sample++) {
			List<CandidateInputState> inputs = new ArrayList<>();
			for(int count = random.nextInt(9); count > 0; count--)
				inputs.add(random.nextBoolean() ? CandidateInputState.absentLocal()
					: CandidateInputState.present(FType.values()[random.nextInt(FType.values().length)]));
			CandidateRuleKey key = new CandidateRuleKey(owner("owner" + random.nextInt(8)), inputs);
			keys.add(key);
			keys.add(new CandidateRuleKey(owner(key.parentOccurrence().emittedHopInstance()),
				inputs.stream().map(input -> new CandidateInputState(input.presence(), input.fType())).toList()));
		}
		for(CandidateRuleKey left : keys) {
			RecordOracle oracle = new RecordOracle(left.parentOccurrence(), left.orderedInputs());
			Assert.assertEquals(oracle.hashCode(), left.hashCode());
			Assert.assertFalse(left.equals(null));
			Assert.assertFalse(left.equals(oracle));
			for(CandidateRuleKey right : keys)
				Assert.assertEquals(oracle.equals(new RecordOracle(right.parentOccurrence(), right.orderedInputs())),
					left.equals(right));
		}
	}

	@Test
	public void collisionAndReorderedInputsKeepDistinctValues() {
		CandidateInputState local = CandidateInputState.absentLocal();
		CandidateInputState fed = CandidateInputState.present(FType.FULL);
		CandidateRuleKey left = new CandidateRuleKey(owner("Aa"), List.of(local, fed));
		CandidateRuleKey collision = new CandidateRuleKey(owner("BB"), List.of(local, fed));
		CandidateRuleKey reversed = new CandidateRuleKey(left.parentOccurrence(), List.of(fed, local));
		CandidateRuleKey equal = new CandidateRuleKey(owner("Aa"), List.of(
			new CandidateInputState(local.presence(), local.fType()),
			new CandidateInputState(fed.presence(), fed.fType())));
		Assert.assertEquals(left.hashCode(), collision.hashCode());
		Assert.assertNotEquals(left, collision);
		Assert.assertNotEquals(left, reversed);
		Assert.assertNotSame(left.parentOccurrence(), equal.parentOccurrence());
		Assert.assertEquals(left, equal);
		Assert.assertTrue(new HashSet<>(List.of(left)).contains(equal));
		Assert.assertEquals(3, new HashSet<>(List.of(left, equal, collision, reversed)).size());
	}

	private static CompiledHopKey owner(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"rule-equality", "main", List.of("main"), "main", "compiled");
		return new CompiledHopKey("rule-equality", "main", "main", "compiled", region, name, name);
	}
}
