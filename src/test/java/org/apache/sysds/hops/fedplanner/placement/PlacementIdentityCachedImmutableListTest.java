/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractSequentialList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.junit.Assert;
import org.junit.Test;

public class PlacementIdentityCachedImmutableListTest {
	@Test
	public void repeatedHashTraversesElementsOnceIncludingZeroHash() throws Exception {
		CountingComparable first = new CountingComparable(7);
		CountingComparable second = new CountingComparable(11);
		List<CountingComparable> cached = cached(List.of(first, second));
		int legacyHash = List.of(first, second).hashCode();
		first.hashCalls = 0;
		second.hashCalls = 0;
		Assert.assertEquals(legacyHash, cached.hashCode());
		int visits = first.hashCalls + second.hashCalls;
		Assert.assertEquals(2, visits);
		Assert.assertEquals(cached.hashCode(), cached.hashCode());
		Assert.assertEquals(visits, first.hashCalls + second.hashCalls);

		CountingComparable zero = new CountingComparable(-31);
		List<CountingComparable> zeroHash = cached(List.of(zero));
		Assert.assertEquals(0, zeroHash.hashCode());
		Assert.assertEquals(0, zeroHash.hashCode());
		Assert.assertEquals(1, zero.hashCalls);
	}

	@Test
	public void equalityIsSymmetricForRandomAndSequentialLists() throws Exception {
		List<Integer> cached = cached(Arrays.asList(3, 1, 1, 2));
		List<Integer> array = new ArrayList<>(List.of(3, 1, 1, 2));
		CountingSequentialList<Integer> linked = new CountingSequentialList<>(array);
		for(List<Integer> ordinary : List.of(array, linked)) {
			Assert.assertEquals(ordinary, cached);
			Assert.assertEquals(cached, ordinary);
			Assert.assertEquals(ordinary.hashCode(), cached.hashCode());
		}
		Assert.assertEquals("each List operation must use one sequential iterator", 3,
			linked.iteratorCreations);
		Assert.assertFalse(cached.equals(List.of(3, 1, 2, 1)));
	}

	@Test
	public void helperCopiesMutableInputRejectsNullAndReusesItsOwnWrapper() throws Exception {
		List<String> source = new ArrayList<>(List.of("first", "second"));
		List<String> cached = cached(source);
		source.set(0, "changed");
		Assert.assertEquals(List.of("first", "second"), cached);
		Assert.assertSame(cached, cached(cached));
		try {
			cached(Arrays.asList("present", null));
			Assert.fail("null immutable-list element must be rejected");
		}
		catch(InvocationTargetException expected) {
			Assert.assertTrue(expected.getCause() instanceof NullPointerException);
		}
	}

	@Test
	public void arbitraryCachedListNeverAcquiresCanonicalMarkerAuthority() throws Exception {
		PlacementProofKey first = new PlacementProofKey(
			PlacementProofKind.VALUE_IDENTITY, null, "z-proof");
		PlacementProofKey second = new PlacementProofKey(
			PlacementProofKind.VALUE_IDENTITY, null, "a-proof");
		List<PlacementProofKey> arbitrary = cached(List.of(first, second));
		List<PlacementProofKey> canonical = PlacementAnalysis.sharedCanonicalComparableList(
			arbitrary, "proof");
		Assert.assertEquals(List.of(second, first), canonical);
		Assert.assertNotSame(arbitrary, canonical);

		try {
			PlacementAnalysis.sharedCanonicalComparableList(
				cached(List.of(first, second, second)), "proof");
			Assert.fail("duplicate canonical values must remain rejected");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("Duplicate"));
		}
	}

	@Test
	public void identityComponentsPreserveLegacyValuesValidationAndSignatures() {
		AnchorPartition partition = new AnchorPartition("w-α",
			List.of(0L, 0L), List.of(5L, 8L));
		Assert.assertEquals(List.of(0L, 0L), partition.begin());
		Assert.assertEquals(List.of(5L, 8L), partition.end());
		AnchorPartition reused = new AnchorPartition("other-worker", partition.begin(), partition.end());
		Assert.assertSame("validated immutable coordinates retain their prepared hash",
			partition.begin(), reused.begin());
		Assert.assertSame(partition.end(), reused.end());
		assertUnmodifiable(partition.begin());

		AnchorPartition equal = new AnchorPartition("w-α",
			new LinkedList<>(List.of(0L, 0L)), new LinkedList<>(List.of(5L, 8L)));
		Assert.assertEquals(partition, equal);
		Assert.assertEquals(partition.hashCode(), equal.hashCode());
		Assert.assertEquals(partition.normalizedSignature(), equal.normalizedSignature());

		DurableAnchorKey anchor = new DurableAnchorKey("pool-β", FType.ROW,
			List.of(partition));
		DurableAnchorKey same = new DurableAnchorKey("pool-β", FType.ROW,
			new LinkedList<>(List.of(equal)));
		Assert.assertEquals(anchor, same);
		Assert.assertEquals(anchor.hashCode(), same.hashCode());
		Assert.assertEquals(anchor.normalizedSignature(), same.normalizedSignature());

		ControlRegionKey region = new ControlRegionKey("program", "ns",
			List.of("loop", "loop", "branch-λ"), "call", "compiled");
		Assert.assertEquals(List.of("loop", "loop", "branch-λ"), region.regionPath());
		ValueVersionKey version = new ValueVersionKey("program", "x", region, 2,
			VersionKind.ORDINARY, List.of("z", "a"));
		Assert.assertEquals(List.of("a", "z"), version.predecessorVersions());

		assertIllegal(() -> new AnchorPartition("w", List.of(0L, 0L), List.of(1L)));
		assertIllegal(() -> new DurableAnchorKey("pool", FType.ROW,
			List.of(partition, partition)));
		assertIllegal(() -> new ValueVersionKey("program", "x", region, 2,
			VersionKind.ORDINARY, List.of("same", "same")));
	}

	@Test
	public void cachedComponentsRemainOrdinaryMapAndSetKeys() {
		AnchorPartition first = new AnchorPartition("worker",
			List.of(0L, 0L), List.of(4L, 2L));
		AnchorPartition equal = new AnchorPartition("worker",
			new ArrayList<>(List.of(0L, 0L)), new LinkedList<>(List.of(4L, 2L)));
		Map<AnchorPartition,String> map = new HashMap<>();
		map.put(first, "present");
		Assert.assertEquals("present", map.get(equal));
		Set<AnchorPartition> set = new HashSet<>();
		set.add(first);
		Assert.assertFalse(set.add(equal));
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> cached(Collection<T> values) throws Exception {
		Method method = PlacementAnalysis.class.getDeclaredMethod(
			"sharedImmutableList", Collection.class, String.class);
		method.setAccessible(true);
		return (List<T>)method.invoke(null, values, "test value");
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertUnmodifiable(List<?> values) {
		try {
			((List)values).add(values.get(0));
			Assert.fail("cached component list must be immutable");
		}
		catch(UnsupportedOperationException expected) {
			// expected
		}
	}

	private static void assertIllegal(Runnable action) {
		try {
			action.run();
			Assert.fail("expected construction failure");
		}
		catch(IllegalArgumentException expected) {
			// expected
		}
	}

	private static final class CountingComparable implements Comparable<CountingComparable> {
		private final int hash;
		private int hashCalls;
		private CountingComparable(int hash) { this.hash = hash; }
		@Override public int compareTo(CountingComparable that) {
			return Integer.compare(hash, that.hash);
		}
		@Override public int hashCode() {
			hashCalls++;
			return hash;
		}
		@Override public boolean equals(Object that) {
			return that instanceof CountingComparable value && hash == value.hash;
		}
	}

	private static final class CountingSequentialList<T> extends AbstractSequentialList<T> {
		private final LinkedList<T> values;
		private int iteratorCreations;
		private CountingSequentialList(List<T> values) { this.values = new LinkedList<>(values); }
		@Override public java.util.ListIterator<T> listIterator(int index) {
			iteratorCreations++;
			return values.listIterator(index);
		}
		@Override public int size() { return values.size(); }
	}
}
