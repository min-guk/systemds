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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.Definition;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.JointTuple;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.SourceKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class PlacementJointInputOrderedEnvironmentOptimizationTest {
	@Test
	public void cachedPrefixComparatorIsDifferentiallyIdenticalToLegacyCanonicalText() throws Exception {
		Random random = new Random(209704821L);
		String[] names = {"", "a", "a;", "a=b", "|reads=", "slash-\\", "quote-\"",
			"line\nfeed", "tab\tname", "한글", "rocket-🚀"};
		int[] ordinals = {0, 1, 2, 9, 10, 11, 99, 100, 101};
		for(int trial = 0; trial < 400; trial++) {
			Map<String,Definition> leftValues = randomDefinitions(random, names, trial * 31);
			Map<String,Definition> rightValues = randomDefinitions(random, names, trial * 31 + 7);
			Map<Integer,Definition> leftReads = randomReads(random, ordinals, trial * 17);
			Map<Integer,Definition> rightReads = randomReads(random, ordinals, trial * 17 + 5);
			Object left = environment(leftValues, leftReads, Definition::stableKey);
			Object right = environment(rightValues, rightReads, Definition::stableKey);
			int expected = legacyKey(leftValues, leftReads, Definition::stableKey)
				.compareTo(legacyKey(rightValues, rightReads, Definition::stableKey));
			Assert.assertEquals("canonical ordering changed at trial " + trial,
				Integer.signum(expected), Integer.signum(compare(left, right)));
		}
	}

	@Test
	public void equalShortPrefixesFallBackToExactLongCanonicalComparisonWithoutFlattening() throws Exception {
		Definition leftDefinition = definition(1, "left");
		Definition rightDefinition = definition(2, "right");
		String common = "prefix".repeat(80);
		Function<Definition,String> keys = value -> common + (value == leftDefinition ? "A" : "B");
		Object left = environment(Map.of("v", leftDefinition), Map.of(99, leftDefinition), keys);
		Object right = environment(Map.of("v", rightDefinition), Map.of(99, rightDefinition), keys);

		String expectedLeft = legacyKey(Map.of("v", leftDefinition), Map.of(99, leftDefinition), keys);
		String expectedRight = legacyKey(Map.of("v", rightDefinition), Map.of(99, rightDefinition), keys);
		Assert.assertEquals(Integer.signum(expectedLeft.compareTo(expectedRight)),
			Integer.signum(compare(left, right)));
		Assert.assertEquals("the cached prefix must stay bounded", 96,
			((String)field(left, "orderingPrefix")).length());
		Assert.assertNull("comparison must not flatten the full canonical environment text",
			field(field(left, "orderingText"), "materialized"));
	}

	@Test
	public void persistentObservationUpdatesCompareOnlyTheChangedReadSuffix() throws Exception {
		Definition common = definition(1, "common");
		Definition leftDefinition = definition(2, "left");
		Definition rightDefinition = definition(3, "right");
		Map<String,Definition> values = new TreeMap<>();
		for(int index = 0; index < 80; index++)
			values.put(String.format("shared-%03d", index), common);
		Object base = environment(values, Map.of(), Definition::stableKey);
		Method observe = base.getClass().getDeclaredMethod("observe", int.class, Definition.class);
		observe.setAccessible(true);
		Object left = observe.invoke(base, 7, leftDefinition);
		Object right = observe.invoke(base, 7, rightDefinition);
		PlacementAnalysis.NormalizedText leftReads =
			(PlacementAnalysis.NormalizedText)field(left, "readSourcesText");
		PlacementAnalysis.NormalizedText rightReads =
			(PlacementAnalysis.NormalizedText)field(right, "readSourcesText");
		AtomicReference<PlacementAnalysis.NormalizedText> comparedLeft = new AtomicReference<>();
		AtomicReference<PlacementAnalysis.NormalizedText> comparedRight = new AtomicReference<>();
		Comparator<PlacementAnalysis.NormalizedText> exactOrder = (first, second) -> {
			comparedLeft.set(first);
			comparedRight.set(second);
			return first.compareTo(second);
		};
		Method compareCanonical = left.getClass().getDeclaredMethod(
			"compareCanonical", left.getClass(), Comparator.class);
		compareCanonical.setAccessible(true);

		int actual = (Integer)compareCanonical.invoke(left, right, exactOrder);
		String expectedLeft = legacyKey(values, Map.of(7, leftDefinition), Definition::stableKey);
		String expectedRight = legacyKey(values, Map.of(7, rightDefinition), Definition::stableKey);
		Assert.assertEquals(Integer.signum(expectedLeft.compareTo(expectedRight)), Integer.signum(actual));
		Assert.assertSame("the unchanged reaching-definition prefix must be skipped exactly",
			leftReads, comparedLeft.get());
		Assert.assertSame("only the changed read-source suffix should reach the exact comparator",
			rightReads, comparedRight.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void analysisLocalMemoReusesExactPairOrderAndKeepsFirstCanonicalRepresentative()
		throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Definition first = definition(4, "first-provenance");
		Definition second = definition(4, "second-provenance");
		Assert.assertNotEquals(first, second);
		Assert.assertEquals(first.stableKey(), second.stableKey());
		Object left = environment(Map.of("x", first), Map.of(), Definition::stableKey);
		Object right = environment(Map.of("x", second), Map.of(), Definition::stableKey);
		Comparator<Object> order = (Comparator<Object>)field(analysis, "environmentOrder");

		Assert.assertEquals(0, order.compare(left, right));
		Assert.assertEquals("one exact pair result must be retained", 1,
			field(analysis, "environmentComparisonMemoEntries"));
		Assert.assertEquals(0, order.compare(left, right));
		Assert.assertEquals(0, order.compare(right, left));
		Assert.assertEquals("forward and reverse lookups must reuse the same exact result", 1,
			field(analysis, "environmentComparisonMemoEntries"));

		Method ordered = method("ordered", Set.class);
		Set<Object> result = (Set<Object>)ordered.invoke(analysis,
			new LinkedHashSet<>(List.of(left, right)));
		Assert.assertEquals(1, result.size());
		Assert.assertSame("canonical equality must retain the first inserted representative",
			left, result.iterator().next());
	}

	@Test
	public void exactAxisInterningReusesReconstructedCanonicalStructure() throws Exception {
		Object pool = canonicalAxisPool();
		Definition definition = definition(9, "retained-provenance");
		Map<String,Definition> firstMap = new TreeMap<>();
		Map<String,Definition> secondMap = new TreeMap<>();
		for(int index = 0; index < 48; index++) {
			String key = String.format("v-%03d", index);
			firstMap.put(key, definition);
			secondMap.put(key, definition);
		}
		Object first = environment(firstMap, Map.of(), pool);
		Object second = environment(secondMap, Map.of(), pool);

		Assert.assertNotSame(firstMap, secondMap);
		Assert.assertSame("exact Map.equals must reuse the retained persistent canonical axis",
			field(first, "valuesAxis"), field(second, "valuesAxis"));
		Assert.assertSame("axis reuse must expose the same normalized text identity",
			field(first, "valuesText"), field(second, "valuesText"));
	}

	@Test
	public void persistentBalancedUpdateSharesTheUntouchedCanonicalSubtree() throws Exception {
		Map<String,Definition> values = new TreeMap<>();
		for(int index = 0; index < 127; index++)
			values.put(String.format("v-%03d", index), definition(index, "base"));
		Object base = environment(values, Map.of(), canonicalAxisPool());
		Method with = base.getClass().getDeclaredMethod("with", String.class, Definition.class);
		with.setAccessible(true);
		Object updated = with.invoke(base, "v-126", definition(211, "replacement"));
		Object baseRoot = field(field(base, "valuesAxis"), "root");
		Object updatedRoot = field(field(updated, "valuesAxis"), "root");

		Assert.assertNotSame(baseRoot, updatedRoot);
		Assert.assertSame("a right-edge update must retain the complete untouched left subtree",
			field(baseRoot, "left"), field(updatedRoot, "left"));
		Map<String,Definition> expectedValues = new TreeMap<>(values);
		expectedValues.put("v-126", definition(211, "replacement"));
		String expected = legacyKey(expectedValues, Map.of(), Definition::stableKey);
		Assert.assertEquals(expected, stableKey(updated));
	}

	@Test
	public void persistentAxesRemainByteExactAcrossRotationsUpdatesAndUtf16Delimiters()
		throws Exception {
		Random random = new Random(77190234L);
		List<String> keys = new ArrayList<>();
		for(int index = 0; index < 63; index++)
			keys.add(String.format("k;%03d=|reads=한글🚀", index));
		Function<Definition,String> definitionKey = definition -> definition.stableKey()
			+ ";=>|reads=\ud83d\ude80";
		for(int trial = 0; trial < 24; trial++) {
			List<String> insertionOrder = new ArrayList<>(keys);
			Collections.shuffle(insertionOrder, random);
			Object environment = environment(Map.of(), Map.of(), definitionKey);
			Map<String,Definition> expectedValues = new TreeMap<>();
			for(int index = 0; index < insertionOrder.size(); index++) {
				Definition definition = definition(trial * 100 + index, "rotation-" + trial);
				environment = with(environment, insertionOrder.get(index), definition);
				expectedValues.put(insertionOrder.get(index), definition);
			}
			for(int update = 0; update < 30; update++) {
				String key = keys.get(random.nextInt(keys.size()));
				Definition definition = definition(5_000 + trial * 100 + update, "update-" + update);
				environment = with(environment, key, definition);
				expectedValues.put(key, definition);
			}
			Map<Integer,Definition> expectedReads = new TreeMap<>();
			for(int read = 0; read < 37; read++) {
				int ordinal = random.nextInt(200);
				Definition definition = definition(9_000 + trial * 100 + read, "read-" + read);
				environment = observe(environment, ordinal, definition);
				expectedReads.put(ordinal, definition);
			}
			Assert.assertEquals("persistent serialization changed at trial " + trial,
				legacyKey(expectedValues, expectedReads, definitionKey), stableKey(environment));
		}

		List<String> shapeKeys = new ArrayList<>(keys.subList(0, 62));
		List<String> reversed = new ArrayList<>(shapeKeys);
		Collections.reverse(reversed);
		Object ascending = environment(Map.of(), Map.of(), definitionKey);
		Object descending = environment(Map.of(), Map.of(), definitionKey);
		Map<String,Definition> expected = new TreeMap<>();
		for(int index = 0; index < shapeKeys.size(); index++) {
			Definition definition = definition(index, "same");
			expected.put(shapeKeys.get(index), definition);
			ascending = with(ascending, shapeKeys.get(index), definition);
		}
		for(String key : reversed)
			descending = with(descending, key, expected.get(key));
		Object ascendingRoot = field(field(ascending, "valuesAxis"), "root");
		Object descendingRoot = field(field(descending, "valuesAxis"), "root");
		Assert.assertNotEquals("the comparison must cover different balanced tree shapes",
			treeShape(ascendingRoot), treeShape(descendingRoot));
		Assert.assertEquals(legacyKey(expected, Map.of(), definitionKey), stableKey(ascending));
		Assert.assertEquals(stableKey(ascending), stableKey(descending));
		Assert.assertEquals("different balanced shapes must retain exact UTF-16 canonical equality",
			0, compare(ascending, descending));
	}

	@Test
	public void axisInterningIsBoundedAndClearReleasesEveryRetainedAxis() throws Exception {
		Object pool = canonicalAxisPool();
		Object first = null;
		for(int index = 0; index < 4_200; index++) {
			Object environment = environment(
				Map.of("unique-" + index, definition(index, "pool")), Map.of(), pool);
			if(index == 0)
				first = environment;
		}
		Assert.assertEquals(4_096, field(pool, "retainedAxes"));
		Assert.assertTrue((Integer)field(pool, "retainedDefinitions") <= 65_536);
		Object repeated = environment(Map.of("unique-0", definition(0, "pool")), Map.of(), pool);
		Assert.assertSame("an exact retained axis remains reusable after saturation",
			field(first, "valuesAxis"), field(repeated, "valuesAxis"));

		Object overflowFirst = environment(Map.of("overflow", definition(9_999, "first")), Map.of(), pool);
		Object overflowSecond = environment(Map.of("overflow", definition(9_999, "first")), Map.of(), pool);
		Assert.assertNotSame("overflow loses only sharing and must not expand retention",
			field(overflowFirst, "valuesAxis"), field(overflowSecond, "valuesAxis"));
		Method clear = pool.getClass().getDeclaredMethod("clear");
		clear.setAccessible(true);
		clear.invoke(pool);
		Assert.assertEquals(0, field(pool, "retainedAxes"));
		Assert.assertEquals(0, field(pool, "retainedDefinitions"));
		Assert.assertTrue(((Map<?,?>)field(pool, "values")).isEmpty());
		Assert.assertTrue(((Map<?,?>)field(pool, "reads")).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void noOpTransitionsAndSubsetUnionsReuseOwnedImmutableSets() throws Exception {
		PlacementJointInputAnalysis analysis = emptyAnalysis();
		Object low = environment(Map.of("a", definition(1, "a")), Map.of(), Definition::stableKey);
		Object high = environment(Map.of("z", definition(2, "z")), Map.of(), Definition::stableKey);
		Method ordered = method("ordered", Set.class);
		Method nextBlock = method("nextBlock", Set.class);
		Method union = method("union", Set.class, Set.class);
		Set<Object> owned = (Set<Object>)ordered.invoke(analysis,
			new LinkedHashSet<>(List.of(high, low)));
		Set<Object> subset = (Set<Object>)ordered.invoke(analysis, Set.of(low));

		Assert.assertSame("a block transition with no observations is an immutable no-op",
			owned, nextBlock.invoke(analysis, owned));
		Assert.assertSame("union with the same set must reuse the owned set",
			owned, union.invoke(analysis, owned, owned));
		Assert.assertSame("union with a compare-contained subset must reuse the owned set",
			owned, union.invoke(analysis, owned, subset));
		Assert.assertEquals(List.of(low, high), List.copyOf((SortedSet<Object>)owned));
		try {
			owned.clear();
			Assert.fail("analysis-owned environment sets must stay immutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected.
		}

		PlacementJointInputAnalysis other = emptyAnalysis();
		Set<Object> rebound = (Set<Object>)ordered.invoke(other, owned);
		Assert.assertNotSame("comparator ownership must remain analysis-local", owned, rebound);
		Assert.assertEquals(owned, rebound);
	}

	@Test
	public void loopAndBranchAnalysisKeepsJointCorrelationAtFixedPoint() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"p=as.scalar(rand(rows=1,cols=1));A=matrix(1,2,2);B=matrix(2,2,2);i=1;"
			+ "while(i<3){if(p>0){X=A;Y=B;}else{X=B;Y=A;}A=X;B=Y;i=i+1;}C=A+B;");
		List<Integer> reads = latestSameBlockReads(analysis, "A", "B");
		List<JointTuple> tuples = analysis.tuplesForReads(reads);
		Assert.assertEquals("the two correlated branch alternatives must not become a Cartesian product",
			2, tuples.size());
		Assert.assertSame("joint tuples must be cached by immutable read-list equality", tuples,
			analysis.tuplesForReads(List.copyOf(reads)));
	}

	private static Map<String,Definition> randomDefinitions(Random random, String[] names, int salt) {
		Map<String,Definition> result = new LinkedHashMap<>();
		for(int index = 0; index < names.length; index++)
			if(random.nextBoolean())
				result.put(names[index], definition(Math.floorMod(salt + index, 112), "p" + salt));
		return result;
	}

	private static Map<Integer,Definition> randomReads(Random random, int[] ordinals, int salt) {
		Map<Integer,Definition> result = new LinkedHashMap<>();
		for(int ordinal : ordinals)
			if(random.nextBoolean())
				result.put(ordinal, definition(Math.floorMod(salt + ordinal, 112), "r" + salt));
		return result;
	}

	private static Definition definition(int ordinal, String provenance) {
		return new Definition(ordinal % 3 == 0 ? SourceKind.FUNCTION_RETURN
			: ordinal % 2 == 0 ? SourceKind.FUNCTION_INPUT : SourceKind.OCCURRENCE,
			null, ordinal, ordinal - 50, ordinal % 2 == 0 ? "main;|=" : "call/한글🚀",
			provenance, null);
	}

	private static Object environment(Map<String,Definition> values, Map<Integer,Definition> reads,
		Function<Definition,String> definitionKey) throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class, Function.class);
		constructor.setAccessible(true);
		return constructor.newInstance(values, reads, definitionKey);
	}

	private static Object environment(Map<String,Definition> values, Map<Integer,Definition> reads,
		Object axisPool) throws Exception {
		Class<?> environment = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Class<?> pool = Class.forName(PlacementJointInputAnalysis.class.getName() + "$CanonicalAxisPool");
		Constructor<?> constructor = environment.getDeclaredConstructor(Map.class, Map.class, pool);
		constructor.setAccessible(true);
		return constructor.newInstance(values, reads, axisPool);
	}

	private static Object canonicalAxisPool() throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$CanonicalAxisPool");
		Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static String stableKey(Object environment) throws Exception {
		Method stableKey = environment.getClass().getDeclaredMethod("stableKey");
		stableKey.setAccessible(true);
		return (String)stableKey.invoke(environment);
	}

	private static Object with(Object environment, String variable, Definition definition)
		throws Exception {
		Method with = environment.getClass().getDeclaredMethod("with", String.class, Definition.class);
		with.setAccessible(true);
		return with.invoke(environment, variable, definition);
	}

	private static Object observe(Object environment, int ordinal, Definition definition)
		throws Exception {
		Method observe = environment.getClass().getDeclaredMethod("observe", int.class, Definition.class);
		observe.setAccessible(true);
		return observe.invoke(environment, ordinal, definition);
	}

	private static String treeShape(Object node) throws Exception {
		if(node == null)
			return "-";
		return '(' + field(node, "key").toString() + treeShape(field(node, "left"))
			+ treeShape(field(node, "right")) + ')';
	}

	private static String legacyKey(Map<String,Definition> values, Map<Integer,Definition> reads,
		Function<Definition,String> definitionKey) {
		StringBuilder result = new StringBuilder();
		for(Map.Entry<String,Definition> entry : new TreeMap<>(values).entrySet()) {
			if(result.length() > 0)
				result.append(';');
			result.append(entry.getKey()).append('=').append(definitionKey.apply(entry.getValue()));
		}
		result.append("|reads=");
		boolean first = true;
		for(Map.Entry<Integer,Definition> entry : new TreeMap<>(reads).entrySet()) {
			if(!first)
				result.append(';');
			result.append(entry.getKey()).append("=>").append(definitionKey.apply(entry.getValue()));
			first = false;
		}
		return result.toString();
	}

	private static int compare(Object left, Object right) throws Exception {
		Method compare = left.getClass().getDeclaredMethod("compareTo", left.getClass());
		compare.setAccessible(true);
		return (Integer)compare.invoke(left, right);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Method method(String name, Class<?>... arguments) throws Exception {
		Method method = PlacementJointInputAnalysis.class.getDeclaredMethod(name, arguments);
		method.setAccessible(true);
		return method;
	}

	private static PlacementJointInputAnalysis emptyAnalysis() throws Exception {
		Constructor<PlacementJointInputAnalysis> constructor = PlacementJointInputAnalysis.class
			.getDeclaredConstructor(DMLProgram.class, List.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(new DMLProgram(), List.of(), List.of());
	}

	private static PlacementJointInputAnalysis analyze(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return PlacementJointInputAnalysis.analyze(program);
	}

	private static List<Integer> latestSameBlockReads(PlacementJointInputAnalysis analysis,
		String leftName, String rightName) {
		int left = -1;
		int right = -1;
		for(int a = 0; a < analysis.occurrenceCount(); a++) {
			Hop leftHop = analysis.occurrenceHop(a);
			if(!PlacementProgramFacts.isTransientRead(leftHop) || !leftName.equals(leftHop.getName()))
				continue;
			for(int b = 0; b < analysis.occurrenceCount(); b++) {
				Hop rightHop = analysis.occurrenceHop(b);
				if(PlacementProgramFacts.isTransientRead(rightHop) && rightName.equals(rightHop.getName())
					&& analysis.occurrenceBlock(a) == analysis.occurrenceBlock(b)
					&& Math.max(a, b) > Math.max(left, right)) {
					left = a;
					right = b;
				}
			}
		}
		if(left < 0)
			throw new AssertionError("missing same-block consumer reads");
		return List.of(left, right);
	}
}
