/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/** Complete proof snapshots keep their original hash/equality, not size-only partitions. */
public class LoopSeedProofSnapshotTest {
	private record LegacyRevision(String read, List<?> nodes, List<?> keys, List<?> facts,
		List<?> logical, List<?> edges, List<?> constraints, List<?> actions,
		boolean privacy, boolean materialization) { }

	private static final class CountedHash {
		private final int value;
		private int calls;
		private CountedHash(int value) { this.value = value; }
		@Override public int hashCode() { calls++; return value; }
		@Override public boolean equals(Object other) {
			return other instanceof CountedHash that && value == that.value;
		}
	}

	@Test
	public void allReadKeysShareOneFullHashPerProofList() throws Exception {
		Object[] fields = fields();
		List<CountedHash> elements = new ArrayList<>();
		for(int field = 1; field <= 7; field++) {
			CountedHash element = new CountedHash(field);
			elements.add(element);
			fields[field] = snapshot(List.of(element));
		}
		Assert.assertEquals("snapshot construction must not eagerly hash", 0,
			elements.stream().mapToInt(value -> value.calls).sum());
		Map<Object,String> ledger = new LinkedHashMap<>();
		for(int read = 0; read < 100; read++) {
			fields[0] = "read-" + read;
			Object key = revision(fields);
			ledger.putIfAbsent(key, "value-" + read);
			Assert.assertEquals("value-" + read, ledger.get(revision(fields)));
		}
		Assert.assertEquals(100, ledger.size());
		Assert.assertEquals("seven shared complete lists, independent of read/get/put count", 7,
			elements.stream().mapToInt(value -> value.calls).sum());
	}

	@Test
	public void generatedRecordHashAndEveryFullFieldMatchLegacy() throws Exception {
		Map<Object,String> actual = new LinkedHashMap<>();
		Map<LegacyRevision,String> legacy = new LinkedHashMap<>();
		List<Object[]> inputs = new ArrayList<>();
		Object[] base = fields();
		inputs.add(base);
		for(int field = 0; field < base.length; field++) {
			Object[] changed = base.clone();
			changed[field] = field == 0 ? "other-read" : field < 8
				? List.of("other-authority-" + field) : !((Boolean) changed[field]);
			inputs.add(changed);
		}
		for(int index = 0; index < inputs.size(); index++) {
			Object[] values = inputs.get(index), wrapped = wrap(values);
			Assert.assertEquals(revision(values), revision(wrapped));
			Assert.assertEquals(revision(wrapped), revision(values));
			Assert.assertEquals(legacy(values).hashCode(), revision(wrapped).hashCode());
			actual.put(revision(wrapped), "entry-" + index);
			legacy.put(legacy(values), "entry-" + index);
		}
		Assert.assertEquals(legacy.size(), actual.size());
		for(Object[] values : inputs)
			Assert.assertEquals(legacy.get(legacy(values)), actual.get(revision(wrap(values))));
		Object first = actual.keySet().iterator().next();
		Assert.assertEquals("entry-0", actual.putIfAbsent(revision(wrap(base)), "stale"));
		Assert.assertSame(first, actual.keySet().iterator().next());
		Assert.assertEquals(new ArrayList<>(legacy.values()), new ArrayList<>(actual.values()));
	}

	@Test
	public void realHashCollisionNeverReusesDifferentAuthority() throws Exception {
		Object[] a = fields(), b = fields();
		a[3] = List.of("Aa");
		b[3] = List.of("BB");
		Object left = revision(wrap(a)), right = revision(wrap(b));
		Assert.assertEquals(left.hashCode(), right.hashCode());
		Assert.assertNotEquals(left, right);
		Assert.assertEquals("right", Map.of(left, "left", right, "right").get(revision(wrap(b))));
	}

	@Test
	public void snapshotOwnsImmutableCopyAndPreservesSymmetricListEquality() throws Exception {
		List<String> input = new ArrayList<>(List.of("first", "second"));
		List<?> frozen = snapshot(input);
		int hash = frozen.hashCode();
		input.set(0, "changed");
		input.add("extra");
		Assert.assertEquals(List.of("first", "second"), frozen);
		Assert.assertTrue(frozen.equals(List.of("first", "second")));
		Assert.assertEquals(hash, frozen.hashCode());
		Assert.assertEquals(frozen, snapshot(List.of("first", "second")));
		Assert.assertThrows(UnsupportedOperationException.class, frozen::clear);
	}

	@Test
	public void zeroHashIsMemoizedRatherThanUsedAsUninitializedSentinel() throws Exception {
		CountedHash element = new CountedHash(-31);
		List<?> frozen = snapshot(List.of(element));
		for(int i = 0; i < 10; i++)
			Assert.assertEquals(0, frozen.hashCode());
		Assert.assertEquals(1, element.calls);
	}

	@Test
	public void noEligibleReadDoesNotCopyDomainOrActionLists() throws Exception {
		List<Object> unused = new java.util.AbstractList<>() {
			@Override public int size() { return 1; }
			@Override public Object get(int index) { throw new AssertionError("unused authority copied"); }
		};
		Method method = java.util.Arrays.stream(PlacementRelationClosure.class.getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals("loopSeedEligibleReadsMeasured"))
			.findFirst().orElseThrow();
		method.setAccessible(true);
		Object result = method.invoke(null, List.of(), List.of(), null, unused, List.of(),
			List.of(), List.of(), List.of(), unused, false, false);
		Assert.assertEquals(Map.of(), result);
	}

	private static Object[] fields() {
		return new Object[] {"read", List.of("node"), List.of("key"), List.of("fact"),
			List.of("logical"), List.of("edge"), List.of("constraint"), List.of("action"), true, true};
	}

	private static Object[] wrap(Object[] values) throws Exception {
		Object[] wrapped = values.clone();
		for(int field = 1; field <= 7; field++)
			wrapped[field] = snapshot((List<?>) values[field]);
		return wrapped;
	}

	private static List<?> snapshot(List<?> values) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("loopSeedProofSnapshot", List.class);
		method.setAccessible(true);
		return (List<?>) method.invoke(null, values);
	}

	private static Object revision(Object[] fields) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$LoopSeedRevision");
		Constructor<?> constructor = type.getDeclaredConstructor(String.class, List.class, List.class,
			List.class, List.class, List.class, List.class, List.class, boolean.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(fields);
	}

	private static LegacyRevision legacy(Object[] fields) {
		return new LegacyRevision((String) fields[0], (List<?>) fields[1], (List<?>) fields[2],
			(List<?>) fields[3], (List<?>) fields[4], (List<?>) fields[5], (List<?>) fields[6],
			(List<?>) fields[7], (Boolean) fields[8], (Boolean) fields[9]);
	}
}
