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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

public class ExactEliminationScoreReuseTest {
	private static final List<String> ORDERINGS = List.of(
		"MIN_FILL", "MIN_SEPARATOR_CELLS", "MIN_ELIMINATION_ASSIGNMENTS", "MIN_DEGREE");

	@Test
	public void incrementalInvalidationMatchesTheLegacyEveryStepOracleOnRandomGraphs()
		throws Exception {
		Random random = new Random(9_041_337L);
		for(int trial = 0; trial < 100; trial++) {
			int count = 2 + random.nextInt(27);
			List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(count);
			int[] domains = new int[count];
			for(int variable = 0; variable < count; variable++) {
				int domain = trial % 9 == 0 && variable % 3 == 0
					? 1_000_000_000 - variable : 2 + random.nextInt(23);
				domains[variable] = domain;
				variables.add(new ExactCategoricalSolver.Variable(
					variable == 2 ? "2" : variable == 10 ? "10" : "v" + variable, domain));
			}
			List<int[]> scopes = randomScopes(random, count);
			for(String ordering : ORDERINGS)
				assertPlanEqualsLegacy(variables, domains, scopes, ordering,
					"trial=" + trial + '|' + ordering);
		}
	}

	@Test
	public void chainReusesUnaffectedScoresAndMatchesColdScoresAtEveryStep() throws Exception {
		int count = 256;
		int[] domains = new int[count];
		Arrays.setAll(domains, variable -> 2 + variable % 17);
		List<int[]> scopes = new ArrayList<>();
		for(int variable = 1; variable < count; variable++)
			scopes.add(new int[] {variable - 1, variable});
		Object graph = graph(count, scopes);
		Map<String,Long> scoreCalls = new HashMap<>();
		Object countingGraph = countingGraph(graph, scoreCalls);
		Object cache = scoreCache(countingGraph, domains, count);

		long fullResetEvaluations = 0L;
		while((boolean)invokeGraph(graph, "hasRemaining", new Class<?>[0])) {
			for(int variable = (int)invokeGraph(graph, "nextRemaining",
				new Class<?>[] {int.class}, 0); variable >= 0; variable = (int)invokeGraph(graph,
					"nextRemaining", new Class<?>[] {int.class}, variable + 1)) {
				Assert.assertEquals(invokeGraph(graph, "fillEdges",
					new Class<?>[] {int.class}, variable), invokeCache(cache, "fillEdges", variable));
				Assert.assertEquals(invokeGraph(graph, "neighborCells",
					new Class<?>[] {int.class, int[].class}, variable, domains),
					invokeCache(cache, "neighborCells", variable));
				Assert.assertEquals(invokeGraph(graph, "remainingDegree",
					new Class<?>[] {int.class}, variable),
					invokeCache(cache, "remainingDegree", variable));
				fullResetEvaluations += 3L;
			}
			int selected = (int)invokeGraph(graph, "nextRemaining",
				new Class<?>[] {int.class}, 0);
			int[] separator = (int[])invokeGraph(graph, "remainingNeighbors",
				new Class<?>[] {int.class}, selected);
			invokeInvalidate(cache, separator);
			invokeGraph(graph, "connectClique", new Class<?>[] {int[].class}, (Object)separator);
			invokeGraph(graph, "removeRemaining", new Class<?>[] {int.class}, selected);
		}

		long actualEvaluations = scoreCalls.values().stream().mapToLong(Long::longValue).sum();
		Assert.assertTrue("local invalidation must avoid the quadratic full-reset work: actual="
			+ actualEvaluations + ", full=" + fullResetEvaluations,
			actualEvaluations < fullResetEvaluations / 10L);
	}

	@Test
	public void denseTransitionAndSelfEdgesPreserveExactScores() throws Exception {
		int count = 129;
		int[] domains = new int[count];
		Arrays.fill(domains, 3);
		List<int[]> scopes = new ArrayList<>();
		for(int variable = 1; variable < count; variable++) {
			scopes.add(new int[] {0, variable});
			if(variable % 11 == 0) {
				scopes.add(new int[] {variable, variable});
				scopes.add(new int[] {0, variable});
			}
		}
		Object graph = graph(count, scopes);
		Object cache = scoreCache(graph, domains, count);
		for(int variable = 0; variable < count; variable++) {
			invokeCache(cache, "fillEdges", variable);
			invokeCache(cache, "neighborCells", variable);
			invokeCache(cache, "remainingDegree", variable);
		}
		int[] separator = (int[])invokeGraph(graph, "remainingNeighbors",
			new Class<?>[] {int.class}, 0);
		invokeInvalidate(cache, separator);
		invokeGraph(graph, "connectClique", new Class<?>[] {int[].class}, (Object)separator);
		invokeGraph(graph, "removeRemaining", new Class<?>[] {int.class}, 0);
		graph = densify(graph);
		Assert.assertEquals("DenseEliminationGraph", graph.getClass().getSimpleName());
		invokeCache(cache, "setGraph", graph);
		for(int variable = 1; variable < count; variable++) {
			Assert.assertEquals(invokeGraph(graph, "fillEdges", new Class<?>[] {int.class}, variable),
				invokeCache(cache, "fillEdges", variable));
			Assert.assertEquals(invokeGraph(graph, "neighborCells",
				new Class<?>[] {int.class, int[].class}, variable, domains),
				invokeCache(cache, "neighborCells", variable));
			Assert.assertEquals(invokeGraph(graph, "remainingDegree",
				new Class<?>[] {int.class}, variable),
				invokeCache(cache, "remainingDegree", variable));
		}
	}

	private static List<int[]> randomScopes(Random random, int count) {
		List<int[]> scopes = new ArrayList<>();
		for(int edge = 0; edge < count * 2; edge++) {
			int size = 1 + random.nextInt(Math.min(5, count));
			int[] scope = new int[size];
			for(int index = 0; index < size; index++)
				scope[index] = random.nextInt(count);
			scopes.add(scope);
			if(edge % 7 == 0)
				scopes.add(scope.clone());
		}
		for(int variable = 1; variable < count / 2; variable++)
			scopes.add(new int[] {variable - 1, variable});
		return scopes;
	}

	private static void assertPlanEqualsLegacy(List<ExactCategoricalSolver.Variable> variables,
		int[] domains, List<int[]> scopes, String ordering, String message) throws Exception {
		Method legacyMethod = ExactEliminationOrderParityTest.class.getDeclaredMethod(
			"legacyPlan", List.class, int[].class, List.class, String.class);
		legacyMethod.setAccessible(true);
		Object expected = legacyMethod.invoke(null, variables, domains, scopes, ordering);
		Object actual = productionPlan(variables, domains, scopes, ordering);
		List<?> expectedSteps = (List<?>)accessor(expected, "steps");
		List<?> actualSteps = (List<?>)accessor(actual, "steps");
		Assert.assertEquals(message, expectedSteps.size(), actualSteps.size());
		for(int index = 0; index < expectedSteps.size(); index++) {
			Assert.assertEquals(message + "|variable=" + index,
				accessor(expectedSteps.get(index), "variable"),
				accessor(actualSteps.get(index), "variable"));
			Assert.assertArrayEquals(message + "|separator=" + index,
				(int[])accessor(expectedSteps.get(index), "separator"),
				(int[])accessor(actualSteps.get(index), "separator"));
		}
		Assert.assertEquals(message + "|width", accessor(expected, "width"),
			accessor(actual, "inducedWidth"));
	}

	private static Object productionPlan(List<ExactCategoricalSolver.Variable> variables,
		int[] domains, List<int[]> scopes, String ordering) throws Exception {
		Class<?> orderingClass = nested("PlanOrdering");
		@SuppressWarnings({"rawtypes", "unchecked"})
		Object value = Enum.valueOf((Class<? extends Enum>)orderingClass, ordering);
		Method method = ExactCategoricalSolver.class.getDeclaredMethod("eliminationPlan",
			List.class, int[].class, List.class, orderingClass);
		method.setAccessible(true);
		return method.invoke(null, variables, domains, scopes, value);
	}

	private static Object graph(int count, List<int[]> scopes) throws Exception {
		Method method = ExactCategoricalSolver.class.getDeclaredMethod(
			"interactionGraph", int.class, List.class);
		method.setAccessible(true);
		return method.invoke(null, count, scopes);
	}

	private static Object densify(Object graph) throws Exception {
		Method method = ExactCategoricalSolver.class.getDeclaredMethod(
			"densifyIfBeneficial", nested("EliminationGraph"));
		method.setAccessible(true);
		return method.invoke(null, graph);
	}

	private static Object countingGraph(Object delegate, Map<String,Long> scoreCalls)
		throws Exception {
		Class<?> graphType = nested("EliminationGraph");
		InvocationHandler handler = (proxy, method, arguments) -> {
			if(method.getName().equals("fillEdges") || method.getName().equals("neighborCells")
				|| method.getName().equals("remainingDegree"))
				scoreCalls.merge(method.getName(), 1L, Long::sum);
			Method implementation = delegate.getClass().getDeclaredMethod(
				method.getName(), method.getParameterTypes());
			implementation.setAccessible(true);
			return implementation.invoke(delegate, arguments);
		};
		return Proxy.newProxyInstance(ExactCategoricalSolver.class.getClassLoader(),
			new Class<?>[] {graphType}, handler);
	}

	private static Object scoreCache(Object graph, int[] domains, int count) throws Exception {
		Class<?> type = nested("OrderScoreCache");
		Constructor<?> constructor = type.getDeclaredConstructor(
			nested("EliminationGraph"), int[].class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(graph, domains, count);
	}

	private static Object invokeCache(Object cache, String method, int variable) throws Exception {
		Method target = cache.getClass().getDeclaredMethod(method, int.class);
		target.setAccessible(true);
		return target.invoke(cache, variable);
	}

	private static void invokeCache(Object cache, String method, Object graph) throws Exception {
		Method target = cache.getClass().getDeclaredMethod(method, nested("EliminationGraph"));
		target.setAccessible(true);
		target.invoke(cache, graph);
	}

	private static void invokeInvalidate(Object cache, int[] separator) throws Exception {
		Method method = cache.getClass().getDeclaredMethod(
			"invalidateAfterElimination", int[].class);
		method.setAccessible(true);
		method.invoke(cache, (Object)separator);
	}

	private static Object invokeGraph(Object graph, String method, Class<?>[] types,
		Object... arguments) throws Exception {
		Method target = graph.getClass().getDeclaredMethod(method, types);
		target.setAccessible(true);
		return target.invoke(graph, arguments);
	}

	private static Object accessor(Object value, String name) throws Exception {
		Method method = value.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(value);
	}

	private static Class<?> nested(String name) throws Exception {
		return Class.forName(ExactCategoricalSolver.class.getName() + '$' + name);
	}
}
