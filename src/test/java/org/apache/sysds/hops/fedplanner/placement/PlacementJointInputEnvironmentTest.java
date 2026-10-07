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
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.Definition;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.SourceKind;
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

	private static Object environment(Map<String,Definition> values, Map<Integer,Definition> reads)
		throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(values, reads);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}
}
