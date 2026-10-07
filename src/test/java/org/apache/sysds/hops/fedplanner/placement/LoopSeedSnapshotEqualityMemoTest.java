/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/** Equality memoization must retain the complete immutable List contract. */
public class LoopSeedSnapshotEqualityMemoTest {
	@Test
	public void equalDistinctSnapshotsMemoizeOneIdentityPair() throws Exception {
		AtomicInteger comparisons = new AtomicInteger();
		List<?> left = snapshot(List.of(new Probe(1, 7, comparisons), new Probe(2, 7, comparisons)));
		List<?> right = snapshot(List.of(new Probe(1, 7, new AtomicInteger()),
			new Probe(2, 7, new AtomicInteger())));

		Assert.assertEquals(left, right);
		int firstComparisonCount = comparisons.get();
		Assert.assertTrue(firstComparisonCount > 0);
		Assert.assertEquals(left, right);
		Assert.assertEquals("the repeated snapshot identity pair must use its memoized result",
			firstComparisonCount, comparisons.get());
		Assert.assertEquals(left.hashCode(), right.hashCode());
	}

	@Test
	public void unequalSameHashSnapshotsRemainUnequalAndMemoized() throws Exception {
		AtomicInteger comparisons = new AtomicInteger();
		List<?> left = snapshot(List.of(new Probe(1, 31, comparisons)));
		List<?> collision = snapshot(List.of(new Probe(2, 31, new AtomicInteger())));

		Assert.assertEquals(left.hashCode(), collision.hashCode());
		Assert.assertNotEquals(left, collision);
		int firstComparisonCount = comparisons.get();
		Assert.assertNotEquals(left, collision);
		Assert.assertEquals("negative equality results are safe for immutable snapshot pairs",
			firstComparisonCount, comparisons.get());
	}

	@Test
	public void alternatingSnapshotIdentityInvalidatesOnlyTheOneEntryMemo() throws Exception {
		AtomicInteger comparisons = new AtomicInteger();
		List<?> left = snapshot(List.of(new Probe(1, 5, comparisons)));
		List<?> equal = snapshot(List.of(new Probe(1, 5, new AtomicInteger())));
		List<?> unequal = snapshot(List.of(new Probe(2, 5, new AtomicInteger())));

		Assert.assertEquals(left, equal);
		int afterEqual = comparisons.get();
		Assert.assertNotEquals(left, unequal);
		int afterUnequal = comparisons.get();
		Assert.assertTrue(afterUnequal > afterEqual);
		Assert.assertEquals(left, equal);
		Assert.assertTrue("returning to the former identity recomputes after one-entry eviction",
			comparisons.get() > afterUnequal);
		int restored = comparisons.get();
		Assert.assertEquals(left, equal);
		Assert.assertEquals(restored, comparisons.get());
	}

	@Test
	public void reflexiveNullAndMutableListSemanticsAreUnchanged() throws Exception {
		AtomicInteger comparisons = new AtomicInteger();
		List<?> snapshot = snapshot(List.of(new Probe(1, 11, comparisons)));
		Assert.assertEquals(snapshot, snapshot);
		Assert.assertNotEquals(snapshot, null);
		Assert.assertEquals(0, comparisons.get());

		List<Probe> mutable = new ArrayList<>();
		mutable.add(new Probe(1, 11, new AtomicInteger()));
		Assert.assertEquals(snapshot, mutable);
		mutable.set(0, new Probe(2, 11, new AtomicInteger()));
		Assert.assertNotEquals("ordinary List equality must not be identity-memoized", snapshot, mutable);
	}

	private static List<?> snapshot(List<?> values) throws Exception {
		Class<?> type = Arrays.stream(PlacementRelationClosure.class.getDeclaredClasses())
			.filter(candidate -> candidate.getSimpleName().equals("LoopSeedProofSnapshot"))
			.findFirst().orElseThrow();
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return (List<?>) constructor.newInstance(values);
	}

	private static final class Probe {
		private final int value;
		private final int hash;
		private final AtomicInteger comparisons;

		private Probe(int value, int hash, AtomicInteger comparisons) {
			this.value = value;
			this.hash = hash;
			this.comparisons = comparisons;
		}

		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			comparisons.incrementAndGet();
			return other instanceof Probe probe && value == probe.value;
		}
	}
}
