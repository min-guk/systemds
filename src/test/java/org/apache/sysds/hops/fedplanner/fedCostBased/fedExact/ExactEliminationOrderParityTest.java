/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class ExactEliminationOrderParityTest {
	private static final List<String> ORDERINGS = List.of(
		"MIN_FILL", "MIN_SEPARATOR_CELLS", "MIN_ELIMINATION_ASSIGNMENTS", "MIN_DEGREE");

	@Test
	public void cachedOrderScoresMatchLegacyReferenceForEveryOrdering() throws Exception {
		List<Fixture> fixtures = List.of(
			randomSparseFixture(),
			disconnectedCliquesFixture(),
			commonNeighborFillFixture(),
			saturatedDomainsFixture());
		for(Fixture fixture : fixtures)
			for(String ordering : ORDERINGS)
				assertPlanEquals(fixture, ordering);
	}

	@Test
	public void wordBoundariesMatchLegacyReferenceForEveryOrdering() throws Exception {
		for(int count : new int[] {63, 64, 65, 127, 128, 129}) {
			Fixture fixture = wordBoundaryFixture(count);
			for(String ordering : ORDERINGS)
				assertPlanEquals(fixture, ordering);
		}
	}

	@Test
	public void adjacencyRepresentationAdaptsWithoutChangingTheExactSearch() throws Exception {
		Assert.assertEquals("SparseEliminationGraph",
			productionGraph(100_000,List.of()).getClass().getSimpleName());
		List<int[]> chain = new ArrayList<>(99_999);
		for(int variable = 1; variable < 100_000; variable++)
			chain.add(new int[] {variable - 1,variable});
		Assert.assertEquals("SparseEliminationGraph",
			productionGraph(100_000,chain).getClass().getSimpleName());
		Assert.assertEquals("DenseEliminationGraph",
			productionGraph(2_572,List.of(range(0,512))).getClass().getSimpleName());

		List<int[]> star = new ArrayList<>();
		for(int variable = 1; variable < 129; variable++)
			star.add(new int[] {0,variable});
		Object transition = productionGraph(129,star);
		Assert.assertEquals("SparseEliminationGraph",transition.getClass().getSimpleName());
		int[] separator = (int[])invokeGraph(transition,"remainingNeighbors",
			new Class<?>[] {int.class},0);
		invokeGraph(transition,"connectClique",new Class<?>[] {int[].class},(Object)separator);
		invokeGraph(transition,"removeRemaining",new Class<?>[] {int.class},0);
		Method adapt = ExactCategoricalSolver.class.getDeclaredMethod(
			"densifyIfBeneficial",Class.forName(
				ExactCategoricalSolver.class.getName() + "$EliminationGraph"));
		adapt.setAccessible(true);
		transition = adapt.invoke(null,transition);
		Assert.assertEquals("DenseEliminationGraph",transition.getClass().getSimpleName());
		Assert.assertEquals("sparse-to-dense conversion must retain the eliminated set",
			1,invokeGraph(transition,"nextRemaining",new Class<?>[] {int.class},0));
	}

	@Test
	public void sparseScoringReusesOrdinalsAndItsNeighborBuffer() throws Exception {
		List<int[]> chain = new ArrayList<>();
		for(int variable = 1; variable < 512; variable++)
			chain.add(new int[] {variable - 1,variable});
		Object sparse = productionGraph(512,chain);
		Assert.assertEquals("SparseEliminationGraph",sparse.getClass().getSimpleName());
		Integer[] ordinals = (Integer[])field(sparse,"ordinals");
		@SuppressWarnings("unchecked")
		Set<Integer>[] adjacency = (Set<Integer>[])field(sparse,"adjacency");
		Integer stored = adjacency[200].stream().filter(value -> value.intValue() == 201)
			.findFirst().orElseThrow();
		Assert.assertSame("sparse rows must retain the graph-owned ordinal object",
			ordinals[201],stored);
		Object buffer = field(sparse,"neighborBuffer");
		invokeGraph(sparse,"fillEdges",new Class<?>[] {int.class},200);
		invokeGraph(sparse,"fillEdges",new Class<?>[] {int.class},201);
		Assert.assertSame("successive fill scores must reuse one graph-local neighbor buffer",
			buffer,field(sparse,"neighborBuffer"));
	}

	private static void assertPlanEquals(Fixture fixture, String ordering) throws Exception {
		LegacyPlan expected = legacyPlan(fixture.variables, fixture.domains, fixture.scopes, ordering);
		Object actual = productionPlan(fixture, ordering);
		Method stepsAccessor = actual.getClass().getDeclaredMethod("steps");
		Method widthAccessor = actual.getClass().getDeclaredMethod("inducedWidth");
		stepsAccessor.setAccessible(true);
		widthAccessor.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<Object> actualSteps = (List<Object>)stepsAccessor.invoke(actual);
		Assert.assertEquals(fixture.name + '|' + ordering, expected.steps.size(), actualSteps.size());
		for(int index = 0; index < expected.steps.size(); index++) {
			LegacyStep expectedStep = expected.steps.get(index);
			Object actualStep = actualSteps.get(index);
			Method variableAccessor = actualStep.getClass().getDeclaredMethod("variable");
			Method separatorAccessor = actualStep.getClass().getDeclaredMethod("separator");
			variableAccessor.setAccessible(true);
			separatorAccessor.setAccessible(true);
			Assert.assertEquals(fixture.name + '|' + ordering + "|variable=" + index,
				expectedStep.variable, variableAccessor.invoke(actualStep));
			Assert.assertArrayEquals(fixture.name + '|' + ordering + "|separator=" + index,
				expectedStep.separator, (int[])separatorAccessor.invoke(actualStep));
		}
		Assert.assertEquals(fixture.name + '|' + ordering + "|width", expected.width,
			widthAccessor.invoke(actual));

		long[] expectedMetrics = metrics(expected, fixture.domains);
		Method metricsMethod = ExactCategoricalSolver.class.getDeclaredMethod("planMetrics",
			actual.getClass(), int[].class);
		metricsMethod.setAccessible(true);
		Object actualMetrics = metricsMethod.invoke(null,actual,fixture.domains);
		String[] accessors = {"maximumFactorCells", "materializedFactorCells",
			"maximumEliminationAssignments", "eliminationAssignments"};
		for(int index = 0; index < accessors.length; index++) {
			Method accessor = actualMetrics.getClass().getDeclaredMethod(accessors[index]);
			accessor.setAccessible(true);
			Assert.assertEquals(fixture.name + '|' + ordering + '|' + accessors[index],
				expectedMetrics[index], accessor.invoke(actualMetrics));
		}
	}

	private static Object productionPlan(Fixture fixture, String ordering) throws Exception {
		Class<?> orderingClass = Class.forName(ExactCategoricalSolver.class.getName() + "$PlanOrdering");
		@SuppressWarnings({"rawtypes", "unchecked"})
		Object value = Enum.valueOf((Class<? extends Enum>)orderingClass,ordering);
		Method method = ExactCategoricalSolver.class.getDeclaredMethod("eliminationPlan",
			List.class,int[].class,List.class,orderingClass);
		method.setAccessible(true);
		return method.invoke(null,fixture.variables,fixture.domains,fixture.scopes,value);
	}

	private static Object productionGraph(int variableCount, List<int[]> scopes) throws Exception {
		Method method = ExactCategoricalSolver.class.getDeclaredMethod(
			"interactionGraph",int.class,List.class);
		method.setAccessible(true);
		return method.invoke(null,variableCount,scopes);
	}

	private static Object invokeGraph(Object graph, String methodName,
		Class<?>[] parameterTypes, Object... arguments) throws Exception {
		Method method = graph.getClass().getDeclaredMethod(methodName,parameterTypes);
		method.setAccessible(true);
		return method.invoke(graph,arguments);
	}

	private static Object field(Object owner, String fieldName) throws Exception {
		Field field = owner.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static LegacyPlan legacyPlan(List<ExactCategoricalSolver.Variable> variables,
		int[] domains, List<int[]> scopes, String ordering) {
		List<Set<Integer>> graph = graph(variables.size(),scopes);
		Set<Integer> remaining = new HashSet<>();
		for(int variable = 0; variable < variables.size(); variable++)
			remaining.add(variable);
		List<LegacyStep> steps = new ArrayList<>(variables.size());
		int width = 0;
		while(!remaining.isEmpty()) {
			Comparator<Integer> comparator = switch(ordering) {
				case "MIN_FILL" -> Comparator
					.comparingLong((Integer variable) -> fillEdges(variable,graph,remaining))
					.thenComparingLong(variable -> neighborCells(variable,graph,remaining,domains));
				case "MIN_SEPARATOR_CELLS" -> Comparator
					.comparingLong((Integer variable) -> neighborCells(variable,graph,remaining,domains))
					.thenComparingLong(variable -> fillEdges(variable,graph,remaining));
				case "MIN_ELIMINATION_ASSIGNMENTS" -> Comparator
					.comparingLong((Integer variable) -> saturatedMultiply(
						neighborCells(variable,graph,remaining,domains),domains[variable]))
					.thenComparingLong(variable -> neighborCells(variable,graph,remaining,domains))
					.thenComparingLong(variable -> fillEdges(variable,graph,remaining));
				case "MIN_DEGREE" -> Comparator
					.comparingLong((Integer variable) -> graph.get(variable).stream()
						.filter(remaining::contains).count())
					.thenComparingLong(variable -> neighborCells(variable,graph,remaining,domains))
					.thenComparingLong(variable -> fillEdges(variable,graph,remaining));
				default -> throw new IllegalArgumentException(ordering);
			};
			int selected = remaining.stream().min(comparator
				.thenComparing(variable -> variables.get(variable).key())).orElseThrow();
			int[] separator = graph.get(selected).stream().filter(remaining::contains)
				.sorted().mapToInt(Integer::intValue).toArray();
			width = Math.max(width,separator.length);
			for(int i = 0; i < separator.length; i++)
				for(int j = i + 1; j < separator.length; j++) {
					graph.get(separator[i]).add(separator[j]);
					graph.get(separator[j]).add(separator[i]);
				}
			remaining.remove(selected);
			steps.add(new LegacyStep(selected,separator));
		}
		return new LegacyPlan(steps,width);
	}

	private static List<Set<Integer>> graph(int count, List<int[]> scopes) {
		List<Set<Integer>> graph = new ArrayList<>(count);
		for(int variable = 0; variable < count; variable++)
			graph.add(new HashSet<>());
		for(int[] scope : scopes)
			for(int i = 0; i < scope.length; i++)
				for(int j = i + 1; j < scope.length; j++) {
					graph.get(scope[i]).add(scope[j]);
					graph.get(scope[j]).add(scope[i]);
				}
		return graph;
	}

	private static long fillEdges(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining) {
		int[] neighbors = graph.get(variable).stream().filter(remaining::contains)
			.sorted().mapToInt(Integer::intValue).toArray();
		long missing = 0L;
		for(int i = 0; i < neighbors.length; i++)
			for(int j = i + 1; j < neighbors.length; j++)
				if(!graph.get(neighbors[i]).contains(neighbors[j]))
					missing++;
		return missing;
	}

	private static long neighborCells(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining, int[] domains) {
		long cells = 1L;
		for(int neighbor : graph.get(variable)) {
			if(!remaining.contains(neighbor))
				continue;
			if(cells > Long.MAX_VALUE / domains[neighbor])
				return Long.MAX_VALUE;
			cells *= domains[neighbor];
		}
		return cells;
	}

	private static long[] metrics(LegacyPlan plan, int[] domains) {
		long maximumCells = 0L;
		long totalCells = 0L;
		long maximumAssignments = 0L;
		long totalAssignments = 0L;
		for(LegacyStep step : plan.steps) {
			long cells = 1L;
			for(int variable : step.separator)
				cells = saturatedMultiply(cells,domains[variable]);
			long assignments = saturatedMultiply(cells,domains[step.variable]);
			maximumCells = Math.max(maximumCells,cells);
			totalCells = saturatedAdd(totalCells,cells);
			maximumAssignments = Math.max(maximumAssignments,assignments);
			totalAssignments = saturatedAdd(totalAssignments,assignments);
		}
		return new long[] {maximumCells,totalCells,maximumAssignments,totalAssignments};
	}

	private static long saturatedMultiply(long left, long right) {
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	private static Fixture randomSparseFixture() {
		Random random = new Random(841_337L);
		int count = 140;
		List<ExactCategoricalSolver.Variable> variables = variables(count,
			index -> index == 2 ? "2" : index == 10 ? "10" : "v" + index,
			index -> 2 + random.nextInt(11));
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		List<int[]> scopes = new ArrayList<>();
		for(int index = 1; index < count; index++)
			scopes.add(new int[] {index,random.nextInt(index)});
		for(int index = 0; index < 180; index++) {
			int a = random.nextInt(count);
			int b = random.nextInt(count);
			int c = random.nextInt(count);
			if(a != b && a != c && b != c)
				scopes.add(new int[] {a,b,c});
		}
		return new Fixture("random-140",variables,domains,scopes);
	}

	private static Fixture disconnectedCliquesFixture() {
		int count = 34;
		List<ExactCategoricalSolver.Variable> variables = variables(count,
			index -> index == 2 ? "2" : index == 10 ? "10" : "node-" + index,
			index -> 2 + index % 9);
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		List<int[]> scopes = new ArrayList<>();
		scopes.add(range(0,9));
		scopes.add(range(9,17));
		for(int index = 17; index < 29; index++)
			scopes.add(new int[] {index,index + 1});
		return new Fixture("disconnected-cliques",variables,domains,scopes);
	}

	private static Fixture saturatedDomainsFixture() {
		int count = 18;
		List<ExactCategoricalSolver.Variable> variables = variables(count,
			index -> Integer.toString(index), index -> 1_000_000_000 - index);
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		List<int[]> scopes = new ArrayList<>();
		for(int index = 0; index < count; index++)
			scopes.add(new int[] {index,(index + 1) % count,(index + 5) % count});
		return new Fixture("saturated",variables,domains,scopes);
	}

	private static Fixture commonNeighborFillFixture() {
		int count = 16;
		List<ExactCategoricalSolver.Variable> variables = variables(count,
			index -> switch(index) {
				case 0 -> "2";
				case 1 -> "10";
				default -> "tie-" + (char)('a' + index);
			}, index -> 3);
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		List<int[]> scopes = new ArrayList<>();
		for(int neighbor = 2; neighbor <= 8; neighbor++) {
			scopes.add(new int[] {0,neighbor});
			scopes.add(new int[] {1,neighbor});
		}
		scopes.addAll(List.of(
			new int[] {2,3}, new int[] {3,4}, new int[] {5,6}, new int[] {7,8},
			new int[] {2,9,10}, new int[] {4,10,11}, new int[] {6,11,12},
			new int[] {8,12,13}, new int[] {9,14}, new int[] {13,15}));
		return new Fixture("common-neighbor-fill",variables,domains,scopes);
	}

	private static Fixture wordBoundaryFixture(int count) {
		List<ExactCategoricalSolver.Variable> variables = variables(count,
			index -> index == 2 ? "2" : index == 10 ? "10" : "word-" + index,
			index -> index % 13 == 0 ? 1_000_000_000 - index : 2 + index % 7);
		int[] domains = variables.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize).toArray();
		List<int[]> scopes = new ArrayList<>();
		for(int index = 1; index < count; index++)
			scopes.add(new int[] {index - 1,index});
		for(int index = 0; index < count; index += 5) {
			int second = (index + 31) % count;
			int third = (index + 64) % count;
			if(index != second && index != third && second != third)
				scopes.add(new int[] {index,second,third});
		}
		for(int index = 1; index < count; index += 11)
			scopes.add(new int[] {0,index});
		return new Fixture("word-boundary-" + count,variables,domains,scopes);
	}

	private static List<ExactCategoricalSolver.Variable> variables(int count,
		java.util.function.IntFunction<String> key,
		java.util.function.IntUnaryOperator domain) {
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>(count);
		for(int index = 0; index < count; index++)
			variables.add(new ExactCategoricalSolver.Variable(key.apply(index),domain.applyAsInt(index)));
		return variables;
	}

	private static int[] range(int from, int to) {
		int[] values = new int[to - from];
		for(int index = 0; index < values.length; index++)
			values[index] = from + index;
		return values;
	}

	private record Fixture(String name, List<ExactCategoricalSolver.Variable> variables,
		int[] domains, List<int[]> scopes) { }
	private record LegacyStep(int variable, int[] separator) { }
	private record LegacyPlan(List<LegacyStep> steps, int width) { }
}
