/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.Definition;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.SourceKind;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class PlacementJointInputEnvironmentTest {
	@Test
	public void definitionCanonicalKeyIsImmutableAndCached() {
		Definition definition = new Definition(SourceKind.FUNCTION_RETURN, null, 4, 2,
			"main/call-3", "ignored-provenance", null);
		String key = definition.stableKey();

		Assert.assertSame("definition comparisons must reuse the immutable canonical key", key,
			definition.stableKey());
		Assert.assertEquals("FUNCTION_RETURN|4|2|main/call-3||", key);
		Assert.assertEquals(definition, new Definition(SourceKind.FUNCTION_RETURN, null, 4, 2,
			"main/call-3", "ignored-provenance", null));
		Assert.assertNotEquals("provenance remains part of definition identity",
			definition, new Definition(SourceKind.FUNCTION_RETURN, null, 4, 2,
				"main/call-3", "different-provenance", null));
	}

	@Test
	public void segmentedComparisonMatchesIndependentLegacyStringsWithoutFlattening() throws Exception {
		Random random = new Random(830278419L);
		String[] names = {"a", "a;", "x=y", "|reads=", "한글", "astral-\ud83d\ude80"};
		int[] readOrdinals = {2, 10, 1, 20, 7};
		for(int trial = 0; trial < 250; trial++) {
			Map<String,Definition> leftValues = new LinkedHashMap<>();
			Map<String,Definition> rightValues = new LinkedHashMap<>();
			Map<Integer,Definition> leftReads = new LinkedHashMap<>();
			Map<Integer,Definition> rightReads = new LinkedHashMap<>();
			for(String name : names) {
				if(random.nextBoolean())
					leftValues.put(name, definition(random.nextInt(6), "left-" + trial));
				if(random.nextBoolean())
					rightValues.put(name, definition(random.nextInt(6), "right-" + trial));
			}
			for(int ordinal : readOrdinals) {
				if(random.nextBoolean())
					leftReads.put(ordinal, definition(random.nextInt(6), "left-read-" + trial));
				if(random.nextBoolean())
					rightReads.put(ordinal, definition(random.nextInt(6), "right-read-" + trial));
			}
			Object left = environment(leftValues, leftReads);
			Object right = environment(rightValues, rightReads);
			int expected = legacyKey(leftValues, leftReads).compareTo(legacyKey(rightValues, rightReads));
			Assert.assertEquals("segmented comparison changed legacy UTF-16 order at trial " + trial,
				Integer.signum(expected), Integer.signum(compare(left, right)));
			assertUnmaterialized(left);
			assertUnmaterialized(right);
		}
	}

	@Test
	public void equalLegacyKeysWithDifferentProvenanceStayUnflattened() throws Exception {
		Definition first = definition(2, "first");
		Definition second = definition(2, "second");
		Assert.assertEquals(first.stableKey(), second.stableKey());
		Assert.assertNotEquals(first, second);
		Object left = environment(Map.of("a;|reads=", first), Map.of(2, first, 10, second));
		Object right = environment(Map.of("a;|reads=", second), Map.of(2, second, 10, first));

		Assert.assertEquals(0, compare(left, right));
		Assert.assertNotEquals("full environment equality must still retain provenance", left, right);
		assertUnmaterialized(left);
		assertUnmaterialized(right);
	}

	@Test
	public void unchangedDefinitionsReuseEnvironmentWithoutIgnoringProvenance() throws Exception {
		Definition first = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Definition equal = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Definition differentProvenance = new Definition(
			SourceKind.OCCURRENCE, null, 1, 0, "main", "second", null);
		Object environment = environment(Map.of("A", first), Map.of());
		Method with = environment.getClass().getDeclaredMethod("with", String.class, Definition.class);
		with.setAccessible(true);

		Assert.assertSame("a full-equality no-op must preserve the immutable environment",
			environment, with.invoke(environment, "A", equal));
		Assert.assertNotSame("provenance is omitted from the legacy sort key but remains semantic state",
			environment, with.invoke(environment, "A", differentProvenance));
	}

	@Test
	public void comparisonKeyIsCreatedLazily() throws Exception {
		Definition definition = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Object environment = environment(Map.of("A", definition), Map.of());
		Field key = environment.getClass().getDeclaredField("stableKey");
		key.setAccessible(true);
		Assert.assertNull("construction must not serialize the whole environment", key.get(environment));

		Method stableKey = environment.getClass().getDeclaredMethod("stableKey");
		stableKey.setAccessible(true);
		String serialized = (String) stableKey.invoke(environment);
		Assert.assertSame(serialized, key.get(environment));
		Assert.assertSame(serialized, stableKey.invoke(environment));
	}

	@Test
	public void persistentUpdatesShareTheUnchangedImmutableMap() throws Exception {
		Definition first = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Definition second = new Definition(SourceKind.OCCURRENCE, null, 2, 0, "main", "second", null);
		Object environment = environment(Map.of("A", first), Map.of(3, first));
		Class<?> type = environment.getClass();
		Method with = type.getDeclaredMethod("with", String.class, Definition.class);
		Method observe = type.getDeclaredMethod("observe", int.class, Definition.class);
		Field values = type.getDeclaredField("values");
		Field readSources = type.getDeclaredField("readSources");
		Field valuesText = type.getDeclaredField("valuesText");
		Field readSourcesText = type.getDeclaredField("readSourcesText");
		with.setAccessible(true);
		observe.setAccessible(true);
		values.setAccessible(true);
		readSources.setAccessible(true);
		valuesText.setAccessible(true);
		readSourcesText.setAccessible(true);

		Object updatedValue = with.invoke(environment, "B", second);
		Object updatedRead = observe.invoke(environment, 4, second);
		Assert.assertSame("a value update must share the unchanged read map",
			readSources.get(environment), readSources.get(updatedValue));
		Assert.assertSame("an observation must share the unchanged value map",
			values.get(environment), values.get(updatedRead));
		Assert.assertSame("a value update must share the unchanged segmented read text",
			readSourcesText.get(environment), readSourcesText.get(updatedValue));
		Assert.assertSame("an observation must share the unchanged segmented value text",
			valuesText.get(environment), valuesText.get(updatedRead));
	}

	@Test
	public void canonicalComparisonKeyIsImmutableAndCached() throws Exception {
		Definition first = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Definition second = new Definition(SourceKind.FUNCTION_INPUT, null, 2, 3, "call", "second", null);
		Map<String,Definition> values = new LinkedHashMap<>();
		values.put("z", second);
		values.put("a", first);
		Map<Integer,Definition> reads = new LinkedHashMap<>();
		reads.put(7, second);
		reads.put(2, first);

		Object environment = environment(values, reads);
		Object reordered = environment(Map.of("a", first, "z", second), Map.of(2, first, 7, second));
		Method stableKey = environment.getClass().getDeclaredMethod("stableKey");
		stableKey.setAccessible(true);
		Method compareTo = environment.getClass().getDeclaredMethod("compareTo", environment.getClass());
		compareTo.setAccessible(true);
		String key = (String) stableKey.invoke(environment);

		Assert.assertSame("comparison must reuse the immutable canonical key", key,
			stableKey.invoke(environment));
		Assert.assertEquals("a=" + first.stableKey() + ";z=" + second.stableKey()
			+ "|reads=2=>" + first.stableKey() + ";7=>" + second.stableKey(), key);
		Assert.assertEquals(environment, reordered);
		Assert.assertEquals(environment.hashCode(), reordered.hashCode());
		Assert.assertEquals(0, compareTo.invoke(environment, reordered));

		values.clear();
		reads.clear();
		Assert.assertSame("constructor input mutation must not invalidate the cached key", key,
			stableKey.invoke(environment));
	}

	@Test
	public void unchangedUpdatesReuseEnvironmentAndChangedAxisSharesTheOtherMap() throws Exception {
		Definition first = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Definition equalFirst = new Definition(SourceKind.OCCURRENCE, null, 1, 0, "main", "first", null);
		Definition second = new Definition(SourceKind.OCCURRENCE, null, 2, 0, "main", "second", null);
		Object environment = environment(Map.of("x", first), Map.of(3, first));
		Class<?> type = environment.getClass();
		Method with = type.getDeclaredMethod("with", String.class, Definition.class);
		Method observe = type.getDeclaredMethod("observe", int.class, Definition.class);
		Method nextBlock = type.getDeclaredMethod("nextBlock");
		with.setAccessible(true);
		observe.setAccessible(true);
		nextBlock.setAccessible(true);

		Assert.assertSame("equal assignments must not copy an immutable environment", environment,
			with.invoke(environment, "x", equalFirst));
		Assert.assertSame("equal observations must not copy an immutable environment", environment,
			observe.invoke(environment, 3, equalFirst));

		Object changedValue = with.invoke(environment, "x", second);
		Object changedRead = observe.invoke(environment, 3, second);
		Assert.assertSame("a value update must share the immutable read-source map",
			field(environment, "readSources"), field(changedValue, "readSources"));
		Assert.assertSame("a read update must share the immutable value map",
			field(environment, "values"), field(changedRead, "values"));

		Object cleared = nextBlock.invoke(environment);
		Assert.assertSame("block transition must share immutable reaching definitions",
			field(environment, "values"), field(cleared, "values"));
		Assert.assertSame("an already-cleared block transition must be a no-op", cleared,
			nextBlock.invoke(cleared));
	}

	@Test
	public void sameOrderingKeyDoesNotHideAProvenanceChange() throws Exception {
		Definition original = new Definition(SourceKind.OCCURRENCE, null, 1, 0,
			"main", "original-provenance", null);
		Definition replacement = new Definition(SourceKind.OCCURRENCE, null, 1, 0,
			"main", "replacement-provenance", null);
		Object environment = environment(Map.of("x", original), Map.of());
		Class<?> type = environment.getClass();
		Method with = type.getDeclaredMethod("with", String.class, Definition.class);
		Method compareTo = type.getDeclaredMethod("compareTo", type);
		with.setAccessible(true);
		compareTo.setAccessible(true);

		Object changed = with.invoke(environment, "x", replacement);
		Assert.assertNotSame("no-op detection must use complete definition equality", environment, changed);
		Assert.assertNotEquals(environment, changed);
		Assert.assertEquals("legacy canonical ordering deliberately excludes provenance", 0,
			compareTo.invoke(environment, changed));
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void orderedEnvironmentSetsStayImmutableAndReuseOnlyTheirOwnedComparator() throws Exception {
		Object analysis = analysis();
		Method ordered = PlacementJointInputAnalysis.class.getDeclaredMethod("ordered", Set.class);
		ordered.setAccessible(true);
		Definition definition = definition(2, "shared");
		Object lower = environment(Map.of("a", definition), Map.of());
		Object upper = environment(Map.of("z", definition), Map.of());
		Comparator<Object> reverse = (left, right) -> {
			try {
				return -compare(left, right);
			}
			catch(Exception failure) {
				throw new AssertionError(failure);
			}
		};
		SortedSet<Object> external = new TreeSet<>(reverse);
		external.add(lower);
		external.add(upper);

		Set<Object> result = (Set<Object>)ordered.invoke(analysis, external);
		Assert.assertTrue("the immutable result must retain its sorted-set marker",
			result instanceof SortedSet<?>);
		Assert.assertNotSame("an externally mutable or differently ordered set must be copied",
			external, result);
		Assert.assertSame(lower, ((SortedSet<Object>)result).first());
		Assert.assertSame("an already-owned sorted result must pass through unchanged",
			result, ordered.invoke(analysis, result));
		Object otherAnalysis = analysis();
		Set<Object> rebound = (Set<Object>)ordered.invoke(otherAnalysis, result);
		Assert.assertNotSame("a set owned by another analysis has a different reusable comparator",
			result, rebound);
		Assert.assertNotSame(((SortedSet<Object>)result).comparator(),
			((SortedSet<Object>)rebound).comparator());
		Assert.assertEquals(result, rebound);
		try {
			((Set)result).add(environment(Map.of(), Map.of()));
			Assert.fail("ordered environment results must be immutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected.
		}
		external.clear();
		Assert.assertEquals("later mutation of an input set must not affect the analysis", 2, result.size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void compareEqualProvenanceKeepsTheFirstRepresentativeAndHashCollisionsStayDistinct()
		throws Exception {
		Object analysis = analysis();
		Method ordered = PlacementJointInputAnalysis.class.getDeclaredMethod("ordered", Set.class);
		ordered.setAccessible(true);
		Definition first = definition(2, "first");
		Definition second = definition(2, "second");
		Object firstEnvironment = environment(Map.of("same", first), Map.of());
		Object secondEnvironment = environment(Map.of("same", second), Map.of());
		Set<Object> provenanceOrder = new LinkedHashSet<>();
		provenanceOrder.add(secondEnvironment);
		provenanceOrder.add(firstEnvironment);
		Set<Object> collapsed = (Set<Object>)ordered.invoke(analysis, provenanceOrder);
		Assert.assertEquals(1, collapsed.size());
		Assert.assertSame("legacy compare-equal insertion keeps the first authority representative",
			secondEnvironment, collapsed.iterator().next());

		Object aa = environment(Map.of("Aa", first), Map.of());
		Object bb = environment(Map.of("BB", first), Map.of());
		Assert.assertEquals("the fixture must exercise equal Java string hashes",
			field(aa, "orderingText").hashCode(), field(bb, "orderingText").hashCode());
		Assert.assertNotEquals(0, compare(aa, bb));
		Set<Object> collisions = (Set<Object>)ordered.invoke(
			analysis, new LinkedHashSet<>(java.util.List.of(bb, aa)));
		Assert.assertEquals("hash collisions must not deduplicate different legacy keys", 2,
			collisions.size());
		Assert.assertSame(aa, ((SortedSet<Object>)collisions).first());
	}

	private static Object environment(Map<String,Definition> values, Map<Integer,Definition> reads)
		throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(values, reads);
	}

	private static Object analysis() throws Exception {
		Constructor<PlacementJointInputAnalysis> constructor = PlacementJointInputAnalysis.class
			.getDeclaredConstructor(DMLProgram.class, java.util.List.class, java.util.List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(new DMLProgram(), java.util.List.of(), java.util.List.of());
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Definition definition(int ordinal, String provenance) {
		return new Definition(ordinal % 2 == 0 ? SourceKind.OCCURRENCE : SourceKind.FUNCTION_INPUT,
			null, ordinal, ordinal - 2, ordinal % 3 == 0 ? "main;=" : "call|한글",
			provenance, null);
	}

	private static int compare(Object left, Object right) throws Exception {
		Method compareTo = left.getClass().getDeclaredMethod("compareTo", left.getClass());
		compareTo.setAccessible(true);
		return (Integer) compareTo.invoke(left, right);
	}

	private static String legacyKey(Map<String,Definition> values, Map<Integer,Definition> reads) {
		StringBuilder key = new StringBuilder();
		for(Map.Entry<String,Definition> entry : new TreeMap<>(values).entrySet()) {
			if(key.length() > 0)
				key.append(';');
			key.append(entry.getKey()).append('=').append(entry.getValue().stableKey());
		}
		key.append("|reads=");
		boolean firstRead = true;
		for(Map.Entry<Integer,Definition> entry : new TreeMap<>(reads).entrySet()) {
			if(!firstRead)
				key.append(';');
			key.append(entry.getKey()).append("=>").append(entry.getValue().stableKey());
			firstRead = false;
		}
		return key.toString();
	}

	private static void assertUnmaterialized(Object environment) throws Exception {
		Field stableKey = environment.getClass().getDeclaredField("stableKey");
		Field orderingText = environment.getClass().getDeclaredField("orderingText");
		stableKey.setAccessible(true);
		orderingText.setAccessible(true);
		Object text = orderingText.get(environment);
		Field materialized = text.getClass().getDeclaredField("materialized");
		materialized.setAccessible(true);
		Assert.assertNull("comparison must not cache a flat environment key", stableKey.get(environment));
		Assert.assertNull("comparison must not flatten segmented ordering text", materialized.get(text));
	}
}
