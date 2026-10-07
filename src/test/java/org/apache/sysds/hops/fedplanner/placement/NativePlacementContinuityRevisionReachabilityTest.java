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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.junit.Assert;
import org.junit.Test;

public class NativePlacementContinuityRevisionReachabilityTest {
	@Test
	public void queryResetAndEmptyCacheRevisionDoNotBuildReverseIndex() throws Exception {
		NativePlacementContinuity resolver = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of());
		java.lang.reflect.Field index = NativePlacementContinuity.class.getDeclaredField(
			"boundCandidateReadersBySource");
		index.setAccessible(true);
		Assert.assertNull(index.get(resolver));

		NativePlacementContinuity fresh = resolver.freshQueryState();
		Assert.assertNull("query-local reset must not scan immutable candidate clauses", index.get(fresh));
		NativePlacementContinuity revised = resolver.nextRevisionWithCompleteCandidateDelta(
			List.of(), identitySet());
		Assert.assertNull("a revision with no reusable entries must not build the reverse index",
			index.get(resolver));
		Assert.assertNull(index.get(revised));
	}

	@Test
	public void reverseReachabilityMatchesLegacyOwnerDfsWithCyclesAndIdentityKeys() throws Exception {
		CompiledHopKey changed = key("changed", 0);
		CompiledHopKey first = key("first", 1);
		CompiledHopKey second = key("second", 2);
		CompiledHopKey root = key("root", 3);
		CompiledHopKey unaffected = key("unaffected", 4);
		CompiledHopKey equalButForeign = key("changed", 0);
		Assert.assertEquals(changed, equalButForeign);
		Assert.assertNotSame(changed, equalButForeign);

		Map<CompiledHopKey,List<CompiledHopKey>> readersBySource = identityMap();
		readersBySource.put(changed, List.of(first));
		readersBySource.put(first, List.of(second));
		readersBySource.put(second, List.of(first, root));
		readersBySource.put(equalButForeign, List.of(unaffected));

		Set<CompiledHopKey> actual = reverseReachableReaders(identitySet(changed), readersBySource);
		Set<CompiledHopKey> expected = legacyAffectedOwners(
			List.of(changed, first, second, root, unaffected, equalButForeign),
			identitySet(changed), readersBySource);
		assertIdentitySetEquals(expected, actual);
		Assert.assertTrue(actual.contains(first));
		Assert.assertTrue(actual.contains(second));
		Assert.assertTrue(actual.contains(root));
		Assert.assertFalse(actual.contains(unaffected));
		Assert.assertFalse(actual.contains(equalButForeign));
	}

	@Test
	public void beforeAndAfterReachabilityDoNotInventMixedRevisionPath() throws Exception {
		CompiledHopKey changed = key("changed", 0);
		CompiledHopKey middle = key("middle", 1);
		CompiledHopKey root = key("root", 2);

		Map<CompiledHopKey,List<CompiledHopKey>> before = identityMap();
		before.put(changed, List.of(middle));
		Map<CompiledHopKey,List<CompiledHopKey>> after = identityMap();
		after.put(middle, List.of(root));

		Set<CompiledHopKey> separatelyAffected = identitySet();
		separatelyAffected.addAll(reverseReachableReaders(identitySet(changed), before));
		separatelyAffected.addAll(reverseReachableReaders(identitySet(changed), after));
		Assert.assertTrue(separatelyAffected.contains(middle));
		Assert.assertFalse("edges from different revisions must not form a synthetic path",
			separatelyAffected.contains(root));

		Map<CompiledHopKey,List<CompiledHopKey>> mixed = identityMap();
		mixed.put(changed, List.of(middle));
		mixed.put(middle, List.of(root));
		Assert.assertTrue("the control demonstrates why a merged graph is incorrect",
			reverseReachableReaders(identitySet(changed), mixed).contains(root));
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> reverseReachableReaders(Set<CompiledHopKey> changed,
		Map<CompiledHopKey,List<CompiledHopKey>> readersBySource) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"reverseReachableReaders", Set.class, Map.class);
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(null, changed, readersBySource);
	}

	private static Set<CompiledHopKey> legacyAffectedOwners(List<CompiledHopKey> owners,
		Set<CompiledHopKey> changed, Map<CompiledHopKey,List<CompiledHopKey>> readersBySource) {
		Map<CompiledHopKey,List<CompiledHopKey>> sourcesByReader = identityMap();
		for(var entry : readersBySource.entrySet())
			for(CompiledHopKey reader : entry.getValue())
				sourcesByReader.computeIfAbsent(reader, ignored -> new ArrayList<>()).add(entry.getKey());
		Set<CompiledHopKey> affected = identitySet();
		for(CompiledHopKey owner : owners) {
			Set<CompiledHopKey> reads = identitySet();
			collectLegacyReads(owner, sourcesByReader, reads, identitySet());
			if(reads.stream().anyMatch(changed::contains))
				affected.add(owner);
		}
		return affected;
	}

	private static void collectLegacyReads(CompiledHopKey owner,
		Map<CompiledHopKey,List<CompiledHopKey>> sourcesByReader, Set<CompiledHopKey> reads,
		Set<CompiledHopKey> visited) {
		if(!visited.add(owner))
			return;
		for(CompiledHopKey source : sourcesByReader.getOrDefault(owner, List.of())) {
			reads.add(source);
			collectLegacyReads(source, sourcesByReader, reads, visited);
		}
	}

	private static CompiledHopKey key(String name, int ordinal) {
		ControlRegionKey region = new ControlRegionKey("revision-reachability", "main",
			List.of("main/" + ordinal), "main", "compiled");
		return new CompiledHopKey("revision-reachability", "main", "main", "compiled",
			region, name + '@' + ordinal, name);
	}

	private static <K,V> Map<K,V> identityMap() {
		return new IdentityHashMap<>();
	}

	@SafeVarargs
	private static <T> Set<T> identitySet(T... values) {
		Set<T> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, values);
		return result;
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey key : expected)
			Assert.assertTrue("missing identity " + key, actual.contains(key));
	}
}
