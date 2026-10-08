/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.Definition;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.SourceKind;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Exact behavioral locks for bulk construction of ordered joint-input environments. */
public class PlacementJointInputBulkEnvironmentTest {
	@Test
	public void bulkFreezeMatchesTreeSetOrderAndRepresentativeIdentityRandomized() throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Comparator<Object> order = environmentOrder(analysis);
		Random random = new Random(8817301L);
		for(int trial = 0; trial < 80; trial++) {
			List<Object> candidates = new ArrayList<>();
			for(int index = 0; index < 48; index++) {
				int key = random.nextInt(19);
				Definition definition = definition(key, "p-" + trial + '-' + index);
				String variable = switch(index % 5) {
					case 0 -> "한글🚀;|reads=";
					case 1 -> "x=" + key;
					default -> "v-" + (key % 7);
				};
				Object environment = environment(Map.of(variable, definition), Map.of(),
					value -> "shared-prefix-".repeat(12) + value.stableKey());
				if(index % 3 == 0)
					environment = observe(environment, index % 11, definition(key + 1, "read"));
				candidates.add(environment);
			}
			TreeSet<Object> reference = new TreeSet<>(order);
			reference.addAll(candidates);
			Set<Object> actual = freezeCandidates(analysis, candidates);
			Assert.assertEquals(reference.size(), actual.size());
			var expectedIterator = reference.iterator();
			for(Object value : actual) {
				Assert.assertTrue(expectedIterator.hasNext());
				Assert.assertSame("bulk ordering must keep TreeSet's first encountered representative",
					expectedIterator.next(), value);
			}
			Assert.assertFalse(expectedIterator.hasNext());
		}
	}

	@Test
	public void comparatorTiesRetainTheEarliestEncounteredRepresentative() throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Definition firstDefinition = definition(4, "first");
		Definition secondDefinition = definition(4, "second");
		Assert.assertNotEquals(firstDefinition, secondDefinition);
		Assert.assertEquals(firstDefinition.stableKey(), secondDefinition.stableKey());
		Object low = environment(Map.of("a", definition(1, "low")), Map.of(), Definition::stableKey);
		Object first = environment(Map.of("m", firstDefinition), Map.of(), Definition::stableKey);
		Object second = environment(Map.of("m", secondDefinition), Map.of(), Definition::stableKey);
		Object high = environment(Map.of("z", definition(7, "high")), Map.of(), Definition::stableKey);

		Set<Object> forward = freezeCandidates(analysis, List.of(high, first, low, second));
		Assert.assertEquals(List.of(low, first, high), List.copyOf(forward));
		Set<Object> reverse = freezeCandidates(analysis, List.of(high, second, low, first));
		Assert.assertEquals(List.of(low, second, high), List.copyOf(reverse));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void orderedBulkResultPreservesSortedSetViewsMembershipAndImmutability() throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Definition representative = definition(4, "representative");
		Object low = environment(Map.of("a", definition(1, "low")), Map.of(), Definition::stableKey);
		Object middle = environment(Map.of("m", representative), Map.of(), Definition::stableKey);
		Object equalMiddle = environment(Map.of("m", definition(4, "other")), Map.of(), Definition::stableKey);
		Object high = environment(Map.of("z", definition(7, "high")), Map.of(), Definition::stableKey);
		Set<Object> result = freezeCandidates(analysis, List.of(high, middle, low));
		Assert.assertTrue(result instanceof SortedSet<?>);
		SortedSet<Object> sorted = (SortedSet<Object>)result;
		Assert.assertSame(environmentOrder(analysis), sorted.comparator());
		Assert.assertSame(low, sorted.first());
		Assert.assertSame(high, sorted.last());
		Assert.assertTrue("membership remains comparator-based", sorted.contains(equalMiddle));
		Assert.assertEquals(List.of(low), List.copyOf(sorted.headSet(middle)));
		Assert.assertEquals(List.of(middle, high), List.copyOf(sorted.tailSet(middle)));
		Assert.assertEquals(List.of(low, middle), List.copyOf(sorted.subSet(low, high)));
		for(Set<Object> view : List.of(sorted, sorted.headSet(high), sorted.tailSet(low))) {
			try {
				view.clear();
				Assert.fail("ordered environments and every view must be immutable");
			}
			catch(UnsupportedOperationException expected) {
				// Expected.
			}
		}
		var iterator = sorted.iterator();
		iterator.next();
		try {
			iterator.remove();
			Assert.fail("ordered environment iterators must stay immutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected.
		}
		try {
			sorted.subSet(high, low);
			Assert.fail("invalid sorted-set ranges must be rejected");
		}
		catch(IllegalArgumentException expected) {
			// Expected.
		}
		SortedSet<Object> boundedHead = sorted.headSet(middle);
		try {
			boundedHead.tailSet(high);
			Assert.fail("nested views must reject endpoints outside their parent range");
		}
		catch(IllegalArgumentException expected) {
			// Expected.
		}
		SortedSet<Object> empty = (SortedSet<Object>)freezeCandidates(analysis, List.of());
		Assert.assertTrue(empty.isEmpty());
		try {
			empty.clear();
			Assert.fail("even no-op mutations on an empty immutable set must fail");
		}
		catch(UnsupportedOperationException expected) {
			// Expected.
		}
		try {
			empty.remove(low);
			Assert.fail("absent removal from an immutable set must fail");
		}
		catch(UnsupportedOperationException expected) {
			// Expected.
		}
		try {
			empty.first();
			Assert.fail("empty first must match SortedSet semantics");
		}
		catch(java.util.NoSuchElementException expected) {
			// Expected.
		}
		try {
			empty.last();
			Assert.fail("empty last must match SortedSet semantics");
		}
		catch(java.util.NoSuchElementException expected) {
			// Expected.
		}
	}

	@Test
	public void bulkFreezeCompactsRawTiesAcrossBoundedRunBoundariesBeforeCap()
		throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Object first = environment(Map.of("tie", definition(4, "first")), Map.of(),
			Definition::stableKey);
		Object later = environment(Map.of("tie", definition(4, "later")), Map.of(),
			Definition::stableKey);
		List<Object> raw = new ArrayList<>(17_000);
		raw.add(first);
		for(int index = 1; index < 17_000; index++)
			raw.add(later);
		Set<Object> compacted = freezeCandidates(analysis, raw);
		Assert.assertEquals("raw multiplicity above the environment cap is legal after exact tie collapse",
			1, compacted.size());
		Assert.assertSame("the earliest representative must survive across bounded run merges",
			first, compacted.iterator().next());
	}

	@Test
	public void linearUnionMatchesTreeSetAndRetainsLeftTieRepresentative() throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Comparator<Object> order = environmentOrder(analysis);
		Object low = environment(Map.of("a", definition(1, "low")), Map.of(), Definition::stableKey);
		Object leftTie = environment(Map.of("m", definition(4, "left")), Map.of(), Definition::stableKey);
		Object rightTie = environment(Map.of("m", definition(4, "right")), Map.of(), Definition::stableKey);
		Object high = environment(Map.of("z", definition(7, "high")), Map.of(), Definition::stableKey);
		Set<Object> left = freezeCandidates(analysis, List.of(leftTie, low));
		Set<Object> right = freezeCandidates(analysis, List.of(high, rightTie));
		TreeSet<Object> reference = new TreeSet<>(order);
		reference.addAll(left);
		reference.addAll(right);
		Method union = PlacementJointInputAnalysis.class.getDeclaredMethod("union", Set.class, Set.class);
		union.setAccessible(true);
		Set<Object> actual = (Set<Object>)union.invoke(analysis, left, right);
		Assert.assertEquals(List.copyOf(reference), List.copyOf(actual));
		Assert.assertSame(leftTie, List.copyOf(actual).get(1));
	}

	@SuppressWarnings("unchecked")
	private static Set<Object> freezeCandidates(PlacementJointInputAnalysis analysis,
		List<Object> candidates) throws Exception {
		Method method = PlacementJointInputAnalysis.class.getDeclaredMethod("freezeCandidates", List.class);
		method.setAccessible(true);
		return (Set<Object>)method.invoke(analysis, candidates);
	}

	@SuppressWarnings("unchecked")
	private static Comparator<Object> environmentOrder(PlacementJointInputAnalysis analysis)
		throws Exception {
		return (Comparator<Object>)field(analysis, "environmentOrder");
	}

	private static Object environment(Map<String,Definition> values, Map<Integer,Definition> reads,
		Function<Definition,String> definitionKey) throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class, Function.class);
		constructor.setAccessible(true);
		return constructor.newInstance(values, reads, definitionKey);
	}

	private static Object observe(Object environment, int ordinal, Definition definition)
		throws Exception {
		Method observe = environment.getClass().getDeclaredMethod("observe", int.class, Definition.class);
		observe.setAccessible(true);
		return observe.invoke(environment, ordinal, definition);
	}

	private static Definition definition(int ordinal, String provenance) {
		return new Definition(ordinal % 3 == 0 ? SourceKind.FUNCTION_RETURN
			: ordinal % 2 == 0 ? SourceKind.FUNCTION_INPUT : SourceKind.OCCURRENCE,
			null, ordinal, ordinal - 50, ordinal % 2 == 0 ? "main;|=" : "call/한글🚀",
			provenance, null);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static PlacementJointInputAnalysis emptyAnalysis() throws Exception {
		Constructor<PlacementJointInputAnalysis> constructor = PlacementJointInputAnalysis.class
			.getDeclaredConstructor(DMLProgram.class, List.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(new DMLProgram(), List.of(), List.of());
	}
}
