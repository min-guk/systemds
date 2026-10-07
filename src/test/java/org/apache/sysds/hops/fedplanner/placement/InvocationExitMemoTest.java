/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.Definition;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.JointTuple;
import org.apache.sysds.hops.fedplanner.placement.PlacementJointInputAnalysis.SourceKind;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.junit.Assert;
import org.junit.Test;

public class InvocationExitMemoTest {
	@Test
	public void equalCalleeKeepsDistinctCallerReturnCorrelations() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"f=function(matrix[double] X) return(matrix[double] A){"
				+ "if(sum(X)>0){A=X;}else{A=X+0;}}"
				+ "X=matrix(7,2,2);p=as.scalar(rand(rows=1,cols=1));"
				+ "if(p>0.5){U=matrix(1,2,2);}else{U=matrix(2,2,2);}"
				+ "A=f(X);C=A+U;print(sum(C));");

		List<JointTuple> tuples = tuples(analysis, "A", "U", "main");
		Assert.assertEquals("two function exits must be mapped back onto both caller paths", 4, tuples.size());
		Assert.assertEquals("both callers bind the same callee and must execute its body once", 2,
			counter(analysis, "invocationAttempts"));
		Assert.assertEquals(1, counter(analysis, "functionBodyExecutions"));
		Assert.assertEquals(2, tuples.stream().map(tuple -> tuple.inputs().get(1).source()).distinct().count());
		Assert.assertTrue(tuples.stream().allMatch(tuple ->
			tuple.inputs().get(0).source().kind() == SourceKind.FUNCTION_RETURN));
		assertMemoEmpty(analysis);
	}

	@Test
	public void nestedFunctionLoopReusesOnlyExactInputsAndRetainsReadObservations() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"g=function(matrix[double] X) return(matrix[double] Y){Y=X;}"
				+ "f=function(matrix[double] X) return(matrix[double] Y){T=g(X);Y=T;}"
				+ "A=matrix(1,2,2);i=1;while(i<3){A=f(A);i=i+1;}"
				+ "B=A+A;print(sum(B));");

		List<JointTuple> result = tuples(analysis, "A", "A", "main");
		Assert.assertFalse(result.isEmpty());
		Assert.assertTrue("the loop fixed point must replay at least one exact invocation input",
			counter(analysis, "invocationAttempts") > counter(analysis, "functionBodyExecutions"));
		List<JointTuple> nestedReads = tuples(analysis, "X", "X", "function/");
		Assert.assertFalse("memo hits must not lose observations recorded by the first nested execution",
			nestedReads.isEmpty());
		Assert.assertTrue(nestedReads.stream().flatMap(tuple -> tuple.inputs().stream()).allMatch(input ->
			input.source().kind() == SourceKind.FUNCTION_INPUT
				&& input.source().callContext().contains("/call-")));
		assertMemoEmpty(analysis);
	}

	@Test
	public void invocationInputUsesFullEnvironmentEquality() throws Exception {
		Definition first = new Definition(SourceKind.OCCURRENCE, null, 1, -1,
			"main", "first-provenance", null);
		Definition second = new Definition(SourceKind.OCCURRENCE, null, 1, -1,
			"main", "second-provenance", null);
		Assert.assertEquals(first.stableKey(), second.stableKey());
		Object firstEnvironment = environment(Map.of("X", first));
		Object secondEnvironment = environment(Map.of("X", second));
		Assert.assertNotEquals(firstEnvironment, secondEnvironment);

		Class<?> inputType = Class.forName(PlacementJointInputAnalysis.class.getName() + "$InvocationInput");
		Constructor<?> constructor = inputType.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Object firstInput = constructor.newInstance("main/call-7", firstEnvironment);
		Object secondInput = constructor.newInstance("main/call-7", secondEnvironment);
		Assert.assertNotEquals("equal ordering text must not collapse distinct provenance", firstInput, secondInput);
	}

	@Test
	public void memoIsClearedAcrossTrackedSlices() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"f=function(matrix[double] X) return(matrix[double] A){"
				+ "if(sum(X)>0){A=X;}else{A=X+0;}}"
				+ "X=matrix(1,2,2);Y=matrix(2,2,2);A=f(X);B=f(Y);"
				+ "C=A+A;D=B+B;print(sum(C)+sum(D));");
		tuples(analysis, "A", "A", "main");
		Assert.assertEquals(2, counter(analysis, "functionBodyExecutions"));
		assertMemoEmpty(analysis);
		tuples(analysis, "B", "B", "main");
		Assert.assertEquals("a new tracked slice must execute rather than reuse a prior-pass exit", 2,
			counter(analysis, "functionBodyExecutions"));
		assertMemoEmpty(analysis);
	}

	@Test
	public void memoIsClearedWhenAnalysisFails() throws Exception {
		StringBuilder script = new StringBuilder(
			"f=function(matrix[double] X) return(matrix[double] A){"
				+ "if(sum(X)>0){A=X;}else{A=X+0;}}X=matrix(1,2,2);A=f(X);"
				+ "p=as.scalar(rand(rows=1,cols=1));");
		for(int index = 0; index < 15; index++)
			script.append("if(p>").append(index).append("){U").append(index)
				.append("=matrix(1,2,2);}else{U").append(index).append("=matrix(2,2,2);}");
		script.append("C=A");
		for(int index = 0; index < 15; index++)
			script.append("+U").append(index);
		script.append(";print(sum(C));");
		PlacementJointInputAnalysis analysis = analyze(script.toString());
		List<Integer> reads = readsInLargestBlock(analysis, Set.of(
			"A", "U0", "U1", "U2", "U3", "U4", "U5", "U6", "U7",
			"U8", "U9", "U10", "U11", "U12", "U13", "U14"));
		Assert.assertEquals(16, reads.size());
		try {
			analysis.tuplesForReads(reads);
			Assert.fail("the exact environment limit fixture must fail");
		}
		catch(PlacementJointInputAnalysis.ResourceLimitException expected) {
			Assert.assertTrue(expected.getMessage().contains("finite environment limit"));
		}
		assertMemoEmpty(analysis);
	}

	@Test
	public void recursionGuardPrecedesWarmMemoLookup() throws Exception {
		PlacementJointInputAnalysis analysis = analyze(
			"f=function(matrix[double] X) return(matrix[double] A){"
				+ "if(sum(X)>0){A=X;}else{A=X+0;}}"
				+ "X=matrix(1,2,2);A=f(X);print(sum(A));");
		int callOrdinal = functionCallOrdinal(analysis, "f");
		FunctionOp call = (FunctionOp)analysis.occurrenceHop(callOrdinal);
		DMLProgram program = (DMLProgram)field(analysis, "program");
		FunctionStatementBlock function = program.getNamedNSFunctionStatementBlocks().get("f");
		Object caller = environment(Map.of("X", new Definition(SourceKind.OCCURRENCE,
			analysis.occurrenceKey(0), 0, -1, "main", "", analysis.occurrenceKey(0))));
		String context = "main/call-" + callOrdinal;
		Method bind = PlacementJointInputAnalysis.class.getDeclaredMethod("bindArguments", int.class,
			FunctionOp.class, List.class, caller.getClass(), String.class);
		bind.setAccessible(true);
		Object callee = bind.invoke(analysis, callOrdinal, call, List.of("X"), caller, context);
		Class<?> inputType = Class.forName(PlacementJointInputAnalysis.class.getName() + "$InvocationInput");
		Constructor<?> inputConstructor = inputType.getDeclaredConstructors()[0];
		inputConstructor.setAccessible(true);
		Object input = inputConstructor.newInstance(context, callee);
		@SuppressWarnings("unchecked")
		Map<FunctionStatementBlock,Map<Object,Set<Object>>> memo =
			(Map<FunctionStatementBlock,Map<Object,Set<Object>>>)field(analysis, "invocationExitMemo");
		Map<Object,Set<Object>> exits = new HashMap<>();
		exits.put(input, Set.of(callee));
		memo.put(function, exits);
		@SuppressWarnings("unchecked")
		Set<String> active = (Set<String>)field(analysis, "activeFunctions");
		active.add("f");
		Method invoke = PlacementJointInputAnalysis.class.getDeclaredMethod("invoke", int.class,
			FunctionOp.class, Set.class, String.class);
		invoke.setAccessible(true);
		try {
			invoke.invoke(analysis, callOrdinal, call, Set.of(caller), "main");
			Assert.fail("a warm memo entry must not hide recursive invocation");
		}
		catch(InvocationTargetException expected) {
			Assert.assertTrue(expected.getCause() instanceof UnsupportedOperationException);
			Assert.assertTrue(expected.getCause().getMessage().contains("Recursive DML functions"));
		}
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

	private static List<JointTuple> tuples(PlacementJointInputAnalysis analysis,
		String left, String right, String context) {
		List<Integer> reads = new ArrayList<>();
		StatementBlock block = null;
		for(int ordinal = 0; ordinal < analysis.occurrenceCount(); ordinal++) {
			Hop hop = analysis.occurrenceHop(ordinal);
			if(!PlacementProgramFacts.isTransientRead(hop)
				|| !(hop.getName().equals(left) || hop.getName().equals(right))
				|| !analysis.occurrenceKey(ordinal).callSitePath().contains(context))
				continue;
			StatementBlock candidate = analysis.occurrenceBlock(ordinal);
			if(block == null || block == candidate) {
				block = candidate;
				reads.add(ordinal);
			}
		}
		if(left.equals(right)) {
			int ordinal = reads.get(0);
			return analysis.tuplesForReads(List.of(ordinal, ordinal));
		}
		for(int leftOrdinal : reads)
			for(int rightOrdinal : reads)
				if(leftOrdinal != rightOrdinal
					&& analysis.occurrenceHop(leftOrdinal).getName().equals(left)
					&& analysis.occurrenceHop(rightOrdinal).getName().equals(right)
					&& analysis.occurrenceBlock(leftOrdinal) == analysis.occurrenceBlock(rightOrdinal))
					return analysis.tuplesForReads(List.of(leftOrdinal, rightOrdinal));
		throw new AssertionError("no shared-block reads for " + left + "/" + right + " in " + context);
	}

	private static int functionCallOrdinal(PlacementJointInputAnalysis analysis, String name) {
		for(int ordinal = 0; ordinal < analysis.occurrenceCount(); ordinal++)
			if(analysis.occurrenceHop(ordinal) instanceof FunctionOp call
				&& name.equals(call.getFunctionName()))
				return ordinal;
		throw new AssertionError("missing call " + name);
	}

	private static List<Integer> readsInLargestBlock(PlacementJointInputAnalysis analysis,
		Set<String> variables) {
		Map<StatementBlock,List<Integer>> byBlock = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < analysis.occurrenceCount(); ordinal++) {
			Hop hop = analysis.occurrenceHop(ordinal);
			if(PlacementProgramFacts.isTransientRead(hop) && variables.contains(hop.getName()))
				byBlock.computeIfAbsent(analysis.occurrenceBlock(ordinal), ignored -> new ArrayList<>()).add(ordinal);
		}
		return byBlock.values().stream().max(java.util.Comparator.comparingInt(List::size)).orElseThrow();
	}

	private static Object environment(Map<String,Definition> values) throws Exception {
		Class<?> type = Class.forName(PlacementJointInputAnalysis.class.getName() + "$Environment");
		Constructor<?> constructor = type.getDeclaredConstructor(Map.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(values, Map.of());
	}

	private static int counter(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(target);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void assertMemoEmpty(PlacementJointInputAnalysis analysis) throws Exception {
		Assert.assertTrue("invocation exits must not survive an analysis pass",
			((Map<?,?>)field(analysis, "invocationExitMemo")).isEmpty());
	}
}
