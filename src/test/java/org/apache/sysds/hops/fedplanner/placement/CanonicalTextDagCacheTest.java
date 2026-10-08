/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.junit.Assert;
import org.junit.Test;

/** Admission-accounting seam tests; scoped-cache tests cover real descriptor dispatch. */
public class CanonicalTextDagCacheTest {
	private static final Class<?> TEXT = nested("CanonicalText");
	private static final Class<?> CACHE = nested("ScopedCanonicalTextCache");

	@Test
	public void sharedSubtreesAcrossAndWithinRootsAreChargedOnce() throws Exception {
		Object child = text(List.of("shared".repeat(400)));
		Object first = text(List.of(child, child, child, child));
		Object second = text(List.of(child, child));
		long exact = expectedWeight(List.of(first, second), 2);
		Object cache = cache(2, exact);
		Object firstKey = key("first"), secondKey = key("second");
		retain(cache, firstKey, first);
		Assert.assertSame(first, get(cache, firstKey));
		Assert.assertEquals(expectedWeight(List.of(first), 1), weight(cache));
		retain(cache, secondKey, second);
		Assert.assertSame(second, get(cache, secondKey));
		Assert.assertEquals(exact, weight(cache));
		Assert.assertEquals(2, values(cache).size());
	}

	@Test
	public void equalButDistinctLiteralsAndRootsKeepTheirIdentities() throws Exception {
		String firstLiteral = new String("equal-literal".repeat(80));
		String equalLiteral = new String(firstLiteral);
		Assert.assertNotSame(firstLiteral, equalLiteral);
		Object shared = text(List.of(firstLiteral, firstLiteral));
		Object distinct = text(List.of(firstLiteral, equalLiteral));
		Assert.assertTrue(expectedWeight(List.of(distinct), 1) > expectedWeight(List.of(shared), 1));
		Object cache = cache(8, 32_768);
		Object a = key("same"), b = key("same");
		Assert.assertEquals(a, b);
		retain(cache, a, shared);
		retain(cache, b, distinct);
		Assert.assertSame(shared, get(cache, a));
		Assert.assertSame(distinct, get(cache, b));
		Assert.assertEquals(expectedWeight(List.of(shared, distinct), 2), weight(cache));
	}

	@Test
	public void partialRejectedTraversalDoesNotPolluteLaterAdmissions() throws Exception {
		Object first = text(List.of("retained"));
		Object prefix = text(List.of("new-prefix"));
		Object tooLarge = text(List.of(prefix, text(List.of("large".repeat(10_000)))));
		Object later = text(List.of(prefix, first));
		long budget = expectedWeight(List.of(first, later), 2);
		Object cache = cache(4, budget);
		Object a = key("a"), rejected = key("rejected"), b = key("b");
		retain(cache, a, first);
		long before = weight(cache);
		int descriptors = ledger(cache).size();
		retain(cache, rejected, tooLarge);
		Assert.assertNull(get(cache, rejected));
		Assert.assertEquals(before, weight(cache));
		Assert.assertEquals(descriptors, ledger(cache).size());
		Assert.assertEquals(1, values(cache).size());
		retain(cache, b, later);
		Assert.assertSame(later, get(cache, b));
		Assert.assertEquals(budget, weight(cache));
	}

	@Test
	public void exactFitOneByteShortAndEntryLimitsAreIndependent() throws Exception {
		Object root = text(List.of("boundary"));
		Object a = key("a"), b = key("b");
		long exact = expectedWeight(List.of(root), 1);
		for(long budget : new long[] {0, 1, exact - 1, exact}) {
			Object cache = cache(1, budget);
			retain(cache, a, root);
			Assert.assertEquals(budget == exact ? exact : 0, weight(cache));
			Assert.assertEquals(budget == exact ? 1 : 0, values(cache).size());
		}
		Object disabled = cache(0, Long.MAX_VALUE);
		retain(disabled, a, root);
		Assert.assertEquals(0, weight(disabled));
		Object capped = cache(1, Long.MAX_VALUE);
		retain(capped, a, root);
		retain(capped, b, root);
		Assert.assertNull("entry-cap replacement discards the prior identity generation", get(capped, a));
		Assert.assertSame(root, get(capped, b));
		Assert.assertEquals(exact, weight(capped));
	}

	@Test
	public void weightReplacementReaccountsSharedSubtreeFromTheNewRoot() throws Exception {
		Object shared = text(List.of("shared".repeat(200)));
		Object first = text(List.of(shared, "first".repeat(200)));
		Object second = text(List.of(shared, "second".repeat(200)));
		long firstWeight = expectedWeight(List.of(first), 1);
		long secondWeight = expectedWeight(List.of(second), 1);
		long budget = Math.max(firstWeight, secondWeight);
		Assert.assertTrue(expectedWeight(List.of(first, second), 2) > budget);
		Object cache = cache(8, budget);
		Object a = key("first"), b = key("second");
		retain(cache, a, first);
		retain(cache, b, second);
		Assert.assertNull(get(cache, a));
		Assert.assertSame(second, get(cache, b));
		Assert.assertEquals(secondWeight, weight(cache));
		Assert.assertEquals(identityDagSize(second), ledger(cache).size());
	}

	@Test
	public void oversizedReplacementRefusalPreservesThePriorGenerationExactly() throws Exception {
		Object first = text(List.of("retained"));
		Object oversized = text(List.of("oversized".repeat(10_000)));
		long budget = expectedWeight(List.of(first), 1);
		Object a = key("first"), b = key("oversized");
		for(int maxEntries : new int[] {1, 8}) {
			Object cache = cache(maxEntries, budget);
			retain(cache, a, first);
			long beforeWeight = weight(cache);
			Map<?,?> beforeValues = values(cache), beforeLedger = ledger(cache);
			retain(cache, b, oversized);
			Assert.assertSame(first, get(cache, a));
			Assert.assertNull(get(cache, b));
			Assert.assertEquals(beforeWeight, weight(cache));
			Assert.assertSame("failed replacement keeps the exact authority map", beforeValues, values(cache));
			Assert.assertSame("failed replacement keeps the exact descriptor ledger", beforeLedger, ledger(cache));
			Assert.assertEquals(1, values(cache).size());
		}
	}

	@Test
	public void retainedDescendantCanAcquireItsOwnAuthorityEntry() throws Exception {
		Object child = text(List.of("child"));
		Object root = text(List.of(child));
		long budget = expectedWeight(List.of(root, child), 2);
		Object cache = cache(2, budget);
		Object a = key("parent"), b = key("child");
		retain(cache, a, root);
		long before = weight(cache);
		retain(cache, b, child);
		Assert.assertSame(child, get(cache, b));
		Assert.assertEquals(64, weight(cache) - before);
		Assert.assertEquals(budget, weight(cache));
	}

	@Test
	public void deepDagAdmissionIsStackSafeAndCloseClearsTheLedger() throws Exception {
		Object root = text(List.of("leaf"));
		for(int i = 0; i < 20_000; i++)
			root = text(List.of(root));
		long exact = expectedWeight(List.of(root), 1);
		Object cache = cache(1, exact), authority = key("deep");
		retain(cache, authority, root);
		Assert.assertSame(root, get(cache, authority));
		Assert.assertEquals(exact, weight(cache));
		Assert.assertEquals(20_002, ledger(cache).size());
		invoke(cache, "clear", new Class<?>[0]);
		Assert.assertTrue(values(cache).isEmpty());
		Assert.assertTrue(ledger(cache).isEmpty());
		Assert.assertEquals(0, weight(cache));
	}

	private static long expectedWeight(List<Object> roots, int keys) throws Exception {
		// Independent identity union using the documented conservative descriptor model.
		long total = 256 + 64L * keys;
		IdentityHashMap<Object,Boolean> seen = new IdentityHashMap<>();
		ArrayDeque<Object> queue = new ArrayDeque<>(roots);
		while(!queue.isEmpty()) {
			Object value = queue.removeLast();
			if(seen.put(value, Boolean.TRUE) != null)
				continue;
			total += 128;
			if(value instanceof String literal)
				total += 40 + 2L * literal.length();
			else {
				Object[] pieces = (Object[]) field(value, "pieces");
				total += 64 + 8L * pieces.length;
				java.util.Collections.addAll(queue, pieces);
			}
		}
		return total;
	}

	private static int identityDagSize(Object root) throws Exception {
		IdentityHashMap<Object,Boolean> seen = new IdentityHashMap<>();
		ArrayDeque<Object> queue = new ArrayDeque<>();
		queue.add(root);
		while(!queue.isEmpty()) {
			Object value = queue.removeLast();
			if(seen.put(value, Boolean.TRUE) != null || value instanceof String)
				continue;
			java.util.Collections.addAll(queue, (Object[])field(value, "pieces"));
		}
		return seen.size();
	}

	private static Object key(String name) {
		return new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, null, name);
	}
	private static Class<?> nested(String name) {
		try { return Class.forName(PlacementAnalysis.class.getName() + "$" + name); }
		catch(ClassNotFoundException e) { throw new AssertionError(e); }
	}
	private static Object text(List<?> pieces) throws Exception {
		Constructor<?> constructor = TEXT.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(pieces);
	}
	private static Object cache(int entries, long weight) throws Exception {
		Constructor<?> constructor = CACHE.getDeclaredConstructor(int.class, long.class);
		constructor.setAccessible(true);
		return constructor.newInstance(entries, weight);
	}
	private static Object invoke(Object receiver, String name, Class<?>[] types, Object... args)
		throws Exception {
		Method method = receiver.getClass().getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(receiver, args);
	}
	private static void retain(Object cache, Object key, Object text) throws Exception {
		invoke(cache, "retain", new Class<?>[] {Object.class, TEXT}, key, text);
	}
	private static Object get(Object cache, Object key) throws Exception {
		return invoke(cache, "get", new Class<?>[] {Object.class}, key);
	}
	private static Object field(Object receiver, String name) throws Exception {
		Field field = receiver.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(receiver);
	}
	private static long weight(Object cache) throws Exception { return (Long)field(cache, "retainedWeight"); }
	private static Map<?,?> values(Object cache) throws Exception { return (Map<?,?>)field(cache, "values"); }
	private static Map<?,?> ledger(Object cache) throws Exception {
		return (Map<?,?>)field(cache, "retainedDescriptors");
	}
}
