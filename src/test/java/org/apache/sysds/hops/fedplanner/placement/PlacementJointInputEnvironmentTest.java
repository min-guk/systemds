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
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.Definition;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.SourceKind;
import org.junit.Assert;
import org.junit.Test;

public class PlacementJointInputEnvironmentTest {
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

	private static Object environment(Map<String,Definition> values, Map<Integer,Definition> reads)
		throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(values, reads);
	}
}
