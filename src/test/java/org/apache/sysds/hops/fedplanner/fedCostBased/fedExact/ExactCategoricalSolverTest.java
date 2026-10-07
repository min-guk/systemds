/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ExactCategoricalSolverTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(10_000_000, 50_000_000);

	@Test
	public void denseFactorDefensivelyCopiesCallerValues() {
		var value = variable("value", 2);
		double[] costs = {0d, 7d};
		var factor = ExactCategoricalSolver.Factor.dense(List.of(value), costs);
		costs[0] = 9d;
		costs[1] = 0d;

		var result = ExactCategoricalSolver.solve(List.of(value), List.of(factor), GENEROUS);
		Assert.assertEquals(List.of(0), result.assignmentInVariableOrder());
		Assert.assertEquals(0d, result.objective(), 0d);
	}

	@Test
	public void compiledProblemReusesTopologyButReevaluatesDynamicFactors() {
		var value = variable("value", 2);
		AtomicInteger boundary = new AtomicInteger(0);
		var dynamic = ExactCategoricalSolver.Factor.lazy(List.of(value), assignment ->
			assignment[0] == boundary.get() ? 0d : 10d);
		ExactCategoricalSolver.CompiledProblem compiled =
			ExactCategoricalSolver.compile(List.of(value), List.of(dynamic), GENEROUS);

		Assert.assertEquals(List.of(0),
			ExactCategoricalSolver.solve(compiled).assignmentInVariableOrder());
		boundary.set(1);
		Assert.assertEquals(List.of(1),
			ExactCategoricalSolver.solve(compiled).assignmentInVariableOrder());
	}

	@Test
	public void regionalFastCompilationMatchesBruteForceWithHardDenseFactors() {
		var a = variable("fast-a", 2);
		var b = variable("fast-b", 3);
		var c = variable("fast-c", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b),
				4d, 1d, Double.POSITIVE_INFINITY,
				0d, 7d, 2d),
			ExactCategoricalSolver.Factor.dense(List.of(b, c),
				3d, 0d,
				1d, Double.POSITIVE_INFINITY,
				5d, 2d),
			ExactCategoricalSolver.Factor.dense(List.of(c), 2d, 0d));

		ExactCategoricalSolver.RegionalCompilation compilation =
			ExactCategoricalSolver.compileRegionalFast(variables, factors, GENEROUS);
		ExactCategoricalSolver.Result result = ExactCategoricalSolver.solve(compilation.compiled());
		BruteForce expected = bruteForce(variables, factors);

		Assert.assertTrue(compilation.fastOrderAccepted());
		Assert.assertEquals(expected.objective, result.objective(), 0d);
		Assert.assertEquals(expected.objective,
			evaluate(variables, factors, result.assignmentInVariableOrder()), 0d);
	}

	@Test
	public void regionalFastCompilationFallsBackAboveWorkGuard() {
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		for(int index = 0; index < 18; index++)
			variables.add(variable("guard-" + index, 2));
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int left = 0; left < variables.size(); left++)
			for(int right = left + 1; right < variables.size(); right++)
				factors.add(ExactCategoricalSolver.Factor.dense(
					List.of(variables.get(left), variables.get(right)), 0d, 1d, 1d, 0d));

		ExactCategoricalSolver.RegionalCompilation compilation =
			ExactCategoricalSolver.compileRegionalFast(variables, factors,
				new ExactCategoricalSolver.Limits(200_000, 1_000_000));
		ExactCategoricalSolver.CompiledProblem portfolio = ExactCategoricalSolver.compile(
			variables, factors, new ExactCategoricalSolver.Limits(200_000, 1_000_000));

		Assert.assertFalse(compilation.fastOrderAccepted());
		Assert.assertTrue(ExactCategoricalSolver.statistics(compilation.compiled())
			.eliminationAssignments() > 100_000L);
		Assert.assertEquals(ExactCategoricalSolver.statistics(portfolio),
			ExactCategoricalSolver.statistics(compilation.compiled()));

		ExactCategoricalSolver.RegionalCompilation raisedCap =
			ExactCategoricalSolver.compileRegionalFast(variables, factors,
				new ExactCategoricalSolver.Limits(200_000, 1_000_000), 1_000_000L);
		Assert.assertTrue(raisedCap.fastOrderAccepted());
		Assert.assertEquals(compilation.fastOrderAssignments(), raisedCap.fastOrderAssignments());
	}

	@Test
	public void commonFastOrderPreservesCanonicalTieAssignment() {
		var z = variable("common-z", 2);
		var a = variable("common-a", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(z, a);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), 0d, 0d, 0d, 0d));
		ExactCategoricalSolver.OrderCompilation fast =
			ExactCategoricalSolver.compileWithFastOrder(variables, factors, GENEROUS,
				true, 1_000_000L);
		ExactCategoricalSolver.OrderCompilation portfolio =
			ExactCategoricalSolver.compileWithFastOrder(variables, factors, GENEROUS,
				false, 1_000_000L);

		Assert.assertTrue(fast.fastOrderConfigured());
		Assert.assertTrue(fast.fastOrderAccepted());
		Assert.assertFalse(fast.fastOrderFallback());
		Assert.assertEquals(List.of(0, 0),
			ExactCategoricalSolver.solve(fast.compiled()).assignmentInVariableOrder());
		ExactCategoricalSolver.Result portfolioResult =
			ExactCategoricalSolver.solve(portfolio.compiled());
		ExactCategoricalSolver.Result fastResult = ExactCategoricalSolver.solve(fast.compiled());
		Assert.assertEquals(portfolioResult.objective(), fastResult.objective(), 0d);
		Assert.assertEquals(portfolioResult.assignmentInVariableOrder(),
			fastResult.assignmentInVariableOrder());
	}

	@Test
	public void boundedPortfolioUsesExactOrderThatFitsPerStepWorkLimit() {
		int[] domains = {9,4,5,2,6,4,2,9};
		List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		for(int index=0; index<domains.length; index++)
			variables.add(variable("bounded-order-" + index,domains[index]));
		int[][] scopes = {{1},{1,2,3,5},{0,2,6},{2,4},{0,3,5},{2,4,7},{5},{3},
			{3,4,7},{7},{4,6},{1},{0,1,4,5},{2,6,7},{0,3,6},{7},{0,2,5,6},
			{0,3,5},{3,4,7}};
		List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		for(int[] scope : scopes) {
			List<ExactCategoricalSolver.Variable> factorVariables = Arrays.stream(scope)
				.mapToObj(variables::get).toList();
			int cells = factorVariables.stream().mapToInt(
				ExactCategoricalSolver.Variable::domainSize).reduce(1,(left,right) -> left*right);
			factors.add(ExactCategoricalSolver.Factor.dense(factorVariables,new double[cells]));
		}
		ExactCategoricalSolver.CompiledProblem ordinary =
			ExactCategoricalSolver.compile(variables,factors,GENEROUS);
		ExactCategoricalSolver.CompiledProblem bounded =
			ExactCategoricalSolver.compileWithinMaximumEliminationAssignments(
				variables,factors,GENEROUS,16_384L);

		Assert.assertEquals(17_280L,
			ExactCategoricalSolver.statistics(ordinary).maximumEliminationAssignments());
		Assert.assertEquals(8_640L,
			ExactCategoricalSolver.statistics(bounded).maximumEliminationAssignments());
		ExactCategoricalSolver.Result ordinaryResult = ExactCategoricalSolver.solve(ordinary);
		ExactCategoricalSolver.Result boundedResult = ExactCategoricalSolver.solve(bounded);
		Assert.assertEquals(ordinaryResult.objective(),boundedResult.objective(),0d);
		Assert.assertEquals(ordinaryResult.assignmentInVariableOrder(),
			boundedResult.assignmentInVariableOrder());
	}

	@Test
	public void regionalFastCompilationRejectsNonPositiveWorkLimit() {
		var value = variable("work-limit", 2);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.compileRegionalFast(List.of(value), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(value), 0d, 1d)), GENEROUS, 0L));
		Assert.assertEquals("EXACT_VE_REGIONAL_FAST_ORDER_WORK_INVALID|value=0", error.getMessage());
	}

	@Test
	public void regionalFastCompilationDoesNotHideMalformedInput() {
		var value = variable("malformed", 2);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.compileRegionalFast(List.of(value), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(value), 0d, Double.NaN)), GENEROUS));
		Assert.assertEquals("EXACT_VE_FACTOR_COST_INVALID|value=NaN", error.getMessage());
	}

	@Test
	public void randomModelsMatchBruteForce() {
		Random random = new Random(713947L);
		for(int trial = 0; trial < 100; trial++) {
			List<ExactCategoricalSolver.Variable> variables = List.of(
				variable("a", 2 + random.nextInt(2)), variable("b", 2 + random.nextInt(2)),
				variable("c", 2), variable("d", 2));
			List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(1))));
			factors.add(randomFactor(random, List.of(variables.get(1), variables.get(2), variables.get(3))));
			factors.add(randomFactor(random, List.of(variables.get(0), variables.get(3))));
			ExactCategoricalSolver.Result actual =
				ExactCategoricalSolver.solve(variables, factors, GENEROUS);
			BruteForce expected = bruteForce(variables, factors);
			Assert.assertEquals(expected.objective, actual.objective(), 0.0);
			Assert.assertEquals(expected.objective,
				evaluate(variables, factors, actual.assignmentInVariableOrder()), 0.0);
		}
	}

	@Test
	public void disconnectedFactorsAreSolvedTogether() {
		var a = variable("a", 2);
		var b = variable("b", 3);
		var result = ExactCategoricalSolver.solve(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 4, 1),
			ExactCategoricalSolver.Factor.dense(List.of(b), 9, 2, 3)), GENEROUS);
		Assert.assertEquals(3.0, result.objective(), 0.0);
		Assert.assertEquals(List.of(1, 1), result.assignmentInVariableOrder());
	}

	@Test
	public void highOrderSharedTransferFactorChargesOnce() {
		var producer = variable("producer", 2);
		var left = variable("left", 2);
		var right = variable("right", 2);
		var shared = ExactCategoricalSolver.Factor.lazy(List.of(producer, left, right), values ->
			values[0] == 0 && (values[1] == 1 || values[2] == 1) ? 7.0 : 0.0);
		var rewardLeft = ExactCategoricalSolver.Factor.dense(List.of(left), 0.0, -5.0);
		var rewardRight = ExactCategoricalSolver.Factor.dense(List.of(right), 0.0, -5.0);
		var pinProducer = ExactCategoricalSolver.Factor.dense(List.of(producer), 0.0,
			Double.POSITIVE_INFINITY);
		var result = ExactCategoricalSolver.solve(List.of(producer, left, right),
			List.of(shared, rewardLeft, rewardRight, pinProducer), GENEROUS);
		Assert.assertEquals(-3.0, result.objective(), 0.0);
		Assert.assertEquals(List.of(0, 1, 1), result.assignmentInVariableOrder());
	}

	@Test
	public void infeasibleModelFailsClosed() {
		var a = variable("a", 2);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a),
					Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)), GENEROUS));
		Assert.assertEquals("EXACT_VE_NO_FEASIBLE_ASSIGNMENT", error.getMessage());
	}

	@Test
	public void tiesAreDeterministicAcrossRuns() {
		var z = variable("z", 2);
		var a = variable("a", 2);
		List<Integer> first = ExactCategoricalSolver.solve(List.of(z, a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(z, a), 0, 0, 0, 0)), GENEROUS)
			.assignmentInVariableOrder();
		for(int repeat = 0; repeat < 20; repeat++)
			Assert.assertEquals(first, ExactCategoricalSolver.solve(List.of(z, a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(z, a), 0, 0, 0, 0)), GENEROUS)
				.assignmentInVariableOrder());
		Assert.assertEquals(List.of(0, 0), first);
	}

	@Test
	public void additiveTieCostResolvesAttributionInvariantPrimaryTie() {
		var producer = variable("producer", 2);
		var consumer = variable("consumer", 2);
		double transfer = 2.000030517578125;
		double compute = 0.000002167820930480957;
		var result = ExactCategoricalSolver.solve(List.of(producer, consumer), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(producer), transfer, 0d),
			ExactCategoricalSolver.Factor.dense(List.of(consumer), 0d, transfer),
			ExactCategoricalSolver.Factor.dense(List.of(consumer), compute, compute),
			ExactCategoricalSolver.Factor.dense(List.of(producer, consumer),
				0d, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d)), GENEROUS,
			(variable, value) -> variable.equals(producer) && value == 1 ? 1L : 0L);
		Assert.assertEquals(List.of(0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(transfer + compute, result.objective(), 0d);
	}

	@Test
	public void additiveTieCostNeverOverridesDistinctPrimaryCost() {
		var value = variable("value", 2);
		var result = ExactCategoricalSolver.solve(List.of(value), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(value), 1d, Math.nextUp(1d))),
			GENEROUS, (variable, selected) -> selected == 0 ? 100L : 0L);
		Assert.assertEquals(List.of(0), result.assignmentInVariableOrder());
		Assert.assertEquals(1d, result.objective(), 0d);
	}

	@Test
	public void statisticsAndLimitsAreAvailableBeforeEvaluation() {
		var a = variable("a", 100);
		var b = variable("b", 100);
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> {
			throw new AssertionError("must not evaluate beyond the declared limit");
		});
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.analyze(List.of(a, b), List.of(factor),
				new ExactCategoricalSolver.Limits(9_999, 1_000_000)));
		Assert.assertTrue(error.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));

		var stats = ExactCategoricalSolver.analyze(List.of(a, b), List.of(factor), GENEROUS);
		Assert.assertEquals(1, stats.inducedWidth());
		Assert.assertEquals(10_000, stats.maximumFactorCells());
		Assert.assertEquals(List.of("a", "b"), stats.eliminationOrder());
	}

	@Test
	public void productionLimitsAcceptW1357LogregFactorStructureBeforeEvaluation() {
		var rows = variable("rows", 7_248);
		var columns = variable("columns", 2_339);
		AtomicInteger evaluations = new AtomicInteger();
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(rows, columns), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		var stats = ExactCategoricalSolver.analyze(List.of(rows, columns), List.of(factor),
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);

		Assert.assertEquals(16_953_072L, stats.maximumFactorCells());
		Assert.assertEquals(0, evaluations.get());
		Assert.assertEquals(Integer.MAX_VALUE,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumFactorCells());
		Assert.assertEquals(Long.MAX_VALUE,
			ExactPhysicalOptimizer.PRODUCTION_LIMITS.maximumMaterializedCells());
	}

	@Test
	public void productionLimitsStillRejectUnrepresentableFactorBeforeEvaluation() {
		var rows = variable("rows", 46_341);
		var columns = variable("columns", 46_341);
		AtomicInteger evaluations = new AtomicInteger();
		var factor = ExactCategoricalSolver.Factor.lazy(List.of(rows, columns), values -> {
			evaluations.incrementAndGet();
			return 0d;
		});

		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.analyze(List.of(rows, columns), List.of(factor),
				ExactPhysicalOptimizer.PRODUCTION_LIMITS));

		Assert.assertEquals("EXACT_VE_FACTOR_CELL_OVERFLOW", error.getMessage());
		Assert.assertEquals(0, evaluations.get());
	}

	@Test
	public void domainWeightedOrderAvoidsMinFillMaterializationBlowup() {
		var a = variable("a", 2);
		var b = variable("b", 100);
		var c = variable("c", 2);
		var d = variable("d", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(a, b), values -> 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(b, c), values -> 0d),
			ExactCategoricalSolver.Factor.lazy(List.of(b, d), values -> 0d));
		var stats = ExactCategoricalSolver.analyze(List.of(a, b, c, d), factors,
			new ExactCategoricalSolver.Limits(1_000, 700));
		Assert.assertEquals(List.of("b", "a", "c", "d"), stats.eliminationOrder());
		Assert.assertEquals(200L, stats.maximumFactorCells());
		Assert.assertEquals(615L, stats.materializedFactorCells());
	}

	@Test
	public void cachedSelectionMetricsPreserveLegacyPortfolioOrders() throws Exception {
		Random random = new Random(0xE11A1L);
		for(int trial = 0; trial < 400; trial++) {
			int count = 1 + random.nextInt(20);
			List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
			int[] domains = new int[count];
			for(int variable = 0; variable < count; variable++) {
				domains[variable] = random.nextInt(8) == 0
					? 1_000_000_000 + random.nextInt(1_000_000_000)
					: 2 + random.nextInt(5);
				variables.add(variable("metric-" + trial + '-' + variable, domains[variable]));
			}
			List<int[]> scopes = new ArrayList<>();
			for(int factor = 0; factor < 1 + random.nextInt(3 * count); factor++) {
				int[] shuffled = java.util.stream.IntStream.range(0, count).toArray();
				for(int index = shuffled.length - 1; index > 0; index--) {
					int swap = random.nextInt(index + 1);
					int value = shuffled[index];
					shuffled[index] = shuffled[swap];
					shuffled[swap] = value;
				}
				int arity = 1 + random.nextInt(Math.min(6, count));
				scopes.add(Arrays.copyOf(shuffled, arity));
			}
			for(String ordering : List.of("MIN_FILL", "MIN_SEPARATOR_CELLS",
				"MIN_ELIMINATION_ASSIGNMENTS", "MIN_DEGREE"))
				Assert.assertEquals("trial=" + trial + "|ordering=" + ordering,
					legacyEliminationOrder(variables, domains, scopes, ordering),
					optimizedEliminationOrder(variables, domains, scopes, ordering));
		}
	}

	@Test
	public void finiteObjectiveOverflowFailsClosed() {
		var a = variable("a", 1);
		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> ExactCategoricalSolver.solve(List.of(a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a), Double.MAX_VALUE),
				ExactCategoricalSolver.Factor.dense(List.of(a), Double.MAX_VALUE)), GENEROUS));
		Assert.assertEquals("EXACT_VE_OBJECTIVE_OVERFLOW", error.getMessage());
	}

	@Test
	public void objectiveUsesCanonicalCompensatedSummation() {
		var a = variable("a", 1);
		var result = ExactCategoricalSolver.solve(List.of(a), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0e16),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0),
			ExactCategoricalSolver.Factor.dense(List.of(a), 1.0)), GENEROUS);
		Assert.assertEquals(1.0e16 + 2.0, result.objective(), 0.0);
		Assert.assertEquals(result.objective(), ExactCategoricalSolver.evaluate(
			List.of(a), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(a), 1.0e16),
				ExactCategoricalSolver.Factor.dense(List.of(a), 1.0),
				ExactCategoricalSolver.Factor.dense(List.of(a), 1.0)),
			GENEROUS, List.of(0)), 0.0);
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static List<Integer> optimizedEliminationOrder(
		List<ExactCategoricalSolver.Variable> variables, int[] domains,
		List<int[]> scopes, String ordering) throws Exception {
		Class<? extends Enum> orderingType = (Class<? extends Enum>)Class.forName(
			ExactCategoricalSolver.class.getName() + "$PlanOrdering");
		Object selectedOrdering = Enum.valueOf(orderingType, ordering);
		var method = ExactCategoricalSolver.class.getDeclaredMethod("eliminationPlan",
			List.class, int[].class, List.class, orderingType);
		method.setAccessible(true);
		Object plan = method.invoke(null, variables, domains, scopes, selectedOrdering);
		var stepsMethod = plan.getClass().getDeclaredMethod("steps");
		stepsMethod.setAccessible(true);
		List<?> steps = (List<?>)stepsMethod.invoke(plan);
		List<Integer> result = new ArrayList<>(steps.size());
		for(Object step : steps) {
			var variableMethod = step.getClass().getDeclaredMethod("variable");
			variableMethod.setAccessible(true);
			result.add((Integer)variableMethod.invoke(step));
		}
		return result;
	}

	private static List<Integer> legacyEliminationOrder(
		List<ExactCategoricalSolver.Variable> variables, int[] domains,
		List<int[]> scopes, String ordering) {
		List<Set<Integer>> graph = interactionGraph(variables.size(), scopes);
		Set<Integer> remaining = new HashSet<>();
		for(int variable = 0; variable < variables.size(); variable++)
			remaining.add(variable);
		List<Integer> result = new ArrayList<>(variables.size());
		while(!remaining.isEmpty()) {
			Comparator<Integer> comparator = switch(ordering) {
				case "MIN_FILL" -> Comparator
					.comparingLong((Integer variable) -> legacyFillEdges(variable, graph, remaining))
					.thenComparingLong(variable -> legacyNeighborCells(
						variable, graph, remaining, domains));
				case "MIN_SEPARATOR_CELLS" -> Comparator
					.comparingLong((Integer variable) -> legacyNeighborCells(
						variable, graph, remaining, domains))
					.thenComparingLong(variable -> legacyFillEdges(variable, graph, remaining));
				case "MIN_ELIMINATION_ASSIGNMENTS" -> Comparator
					.comparingLong((Integer variable) -> saturatedMultiply(
						legacyNeighborCells(variable, graph, remaining, domains), domains[variable]))
					.thenComparingLong(variable -> legacyNeighborCells(
						variable, graph, remaining, domains))
					.thenComparingLong(variable -> legacyFillEdges(variable, graph, remaining));
				case "MIN_DEGREE" -> Comparator
					.comparingLong((Integer variable) -> graph.get(variable).stream()
						.filter(remaining::contains).count())
					.thenComparingLong(variable -> legacyNeighborCells(
						variable, graph, remaining, domains))
					.thenComparingLong(variable -> legacyFillEdges(variable, graph, remaining));
				default -> throw new IllegalArgumentException(ordering);
			};
			int selected = remaining.stream().min(comparator.thenComparing(
				variable -> variables.get(variable).key())).orElseThrow();
			int[] separator = graph.get(selected).stream().filter(remaining::contains)
				.sorted().mapToInt(Integer::intValue).toArray();
			for(int left = 0; left < separator.length; left++)
				for(int right = left + 1; right < separator.length; right++) {
					graph.get(separator[left]).add(separator[right]);
					graph.get(separator[right]).add(separator[left]);
				}
			remaining.remove(selected);
			result.add(selected);
		}
		return result;
	}

	private static List<Set<Integer>> interactionGraph(int count, List<int[]> scopes) {
		List<Set<Integer>> graph = new ArrayList<>(count);
		for(int variable = 0; variable < count; variable++)
			graph.add(new HashSet<>());
		for(int[] scope : scopes)
			for(int left = 0; left < scope.length; left++)
				for(int right = left + 1; right < scope.length; right++) {
					graph.get(scope[left]).add(scope[right]);
					graph.get(scope[right]).add(scope[left]);
				}
		return graph;
	}

	private static long legacyFillEdges(int variable, List<Set<Integer>> graph,
		Set<Integer> remaining) {
		int[] neighbors = graph.get(variable).stream().filter(remaining::contains)
			.sorted().mapToInt(Integer::intValue).toArray();
		long missing = 0L;
		for(int left = 0; left < neighbors.length; left++)
			for(int right = left + 1; right < neighbors.length; right++)
				if(!graph.get(neighbors[left]).contains(neighbors[right]))
					missing++;
		return missing;
	}

	private static long legacyNeighborCells(int variable, List<Set<Integer>> graph,
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

	private static long saturatedMultiply(long left, long right) {
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static ExactCategoricalSolver.Factor randomFactor(Random random,
		List<ExactCategoricalSolver.Variable> scope) {
		int cells = scope.stream().mapToInt(ExactCategoricalSolver.Variable::domainSize)
			.reduce(1, Math::multiplyExact);
		double[] values = new double[cells];
		for(int i = 0; i < cells; i++)
			values[i] = random.nextInt(21) - 10;
		return ExactCategoricalSolver.Factor.dense(scope, values);
	}

	private static BruteForce bruteForce(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors) {
		int[] values = new int[variables.size()];
		double[] best = {Double.POSITIVE_INFINITY};
		enumerate(variables, factors, 0, values, best);
		return new BruteForce(best[0]);
	}

	private static void enumerate(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, int index, int[] values, double[] best) {
		if(index == variables.size()) {
			best[0] = Math.min(best[0], evaluate(variables, factors, ArraysAsList(values)));
			return;
		}
		for(int value = 0; value < variables.get(index).domainSize(); value++) {
			values[index] = value;
			enumerate(variables, factors, index + 1, values, best);
		}
	}

	private static double evaluate(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, List<Integer> assignment) {
		double total = 0;
		for(ExactCategoricalSolver.Factor factor : factors) {
			int cell = 0;
			for(ExactCategoricalSolver.Variable variable : factor.scope())
				cell = cell * variable.domainSize() + assignment.get(variables.indexOf(variable));
			total += factor.denseCostAt(cell);
		}
		return total;
	}

	private static List<Integer> ArraysAsList(int[] values) {
		return java.util.Arrays.stream(values).boxed().toList();
	}

	private record BruteForce(double objective) { }
}
