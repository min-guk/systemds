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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
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
