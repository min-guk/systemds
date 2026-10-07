/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Statistics;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class EliminationOrderScoreCacheTest {
	private static final List<String> ORDERINGS = List.of(
		"MIN_FILL", "MIN_SEPARATOR_CELLS", "MIN_ELIMINATION_ASSIGNMENTS", "MIN_DEGREE");
	private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

	@Test
	public void allPoliciesMatchFreshScoresAcrossRandomGraphs() {
		Random random = new Random(0x5ca1ab1eL);
		for(int trial = 0; trial < 160; trial++) {
			int count = 1 + random.nextInt(9);
			int[] domains = new int[count];
			for(int index = 0; index < count; index++) {
				int selector = random.nextInt(12);
				domains[index] = selector == 0 ? Integer.MAX_VALUE : selector < 4 ? 1 : 2 + random.nextInt(8);
			}
			List<Variable> variables = variables(domains, random);
			List<int[]> scopes = randomScopes(count, random);
			for(String ordering : ORDERINGS)
				assertOrderParity("trial=" + trial + " ordering=" + ordering,
					variables, domains, scopes, ordering);
		}
	}

	@Test
	public void newFillEdgeInvalidatesCommonNeighborOutsideEliminatedNeighborhood() {
		List<Variable> variables = List.of(new Variable("0-s", 2), new Variable("1-w", 2),
			new Variable("2-a", 2), new Variable("3-b", 2));
		int[] domains = {2, 2, 2, 2};
		List<int[]> cycle = List.of(new int[] {0, 2}, new int[] {2, 1},
			new int[] {1, 3}, new int[] {3, 0});

		assertOrderParity("four-cycle common-neighbor invalidation", variables, domains, cycle, "MIN_FILL");
		var actual = ExactCategoricalSolver.eliminationOrderScoreForTest(
			variables, domains, cycle, "MIN_FILL");
		Assert.assertEquals(List.of("0-s", "1-w", "2-a", "3-b"), actual.statistics().eliminationOrder());
		Assert.assertEquals(List.of("2-a", "3-b"), actual.separators().get(0));
		Assert.assertEquals(List.of("2-a", "3-b"), actual.separators().get(1));
	}

	@Test
	public void tiesSingletonsAndSaturatedProductsPreserveEveryPolicy() {
		List<Variable> variables = List.of(new Variable("k4", 1),
			new Variable("k1", Integer.MAX_VALUE), new Variable("k3", Integer.MAX_VALUE),
			new Variable("k0", 1), new Variable("k2", 3));
		int[] domains = variables.stream().mapToInt(Variable::domainSize).toArray();
		List<int[]> scopes = List.of(new int[] {0, 1, 2}, new int[] {0, 3},
			new int[] {3, 4, 1}, new int[] {2, 4});
		for(String ordering : ORDERINGS)
			assertOrderParity("saturation ordering=" + ordering, variables, domains, scopes, ordering);
	}

	@Test
	public void portfolioChoiceMatchesIndependentCandidateComparison() {
		Random random = new Random(0x706f7274666f6c69L);
		for(int trial = 0; trial < 80; trial++) {
			int count = 2 + random.nextInt(8);
			int[] domains = new int[count];
			for(int index = 0; index < count; index++)
				domains[index] = 1 + random.nextInt(7);
			List<Variable> variables = variables(domains, random);
			List<int[]> scopes = randomScopes(count, random);
			ReferencePlan expected = null;
			int expectedPriority = -1;
			for(int priority = 0; priority < ORDERINGS.size(); priority++) {
				ReferencePlan candidate = reference(variables, domains, scopes, ORDERINGS.get(priority));
				if(expected == null || compare(candidate.statistics, priority,
					expected.statistics, expectedPriority) < 0) {
					expected = candidate;
					expectedPriority = priority;
				}
			}
			Assert.assertEquals("trial=" + trial, expected.statistics,
				ExactCategoricalSolver.eliminationPortfolioScoreForTest(variables, domains, scopes));
		}
	}

	@Test
	public void lazyScoresAvoidRepeatedComparatorWorkOnStableCandidates() {
		int count = 64;
		List<Variable> variables = new ArrayList<>();
		for(int index = count - 1; index >= 0; index--)
			variables.add(new Variable(String.format("v%02d", index), 2));
		int[] domains = new int[count];
		java.util.Arrays.fill(domains, 2);

		for(String ordering : ORDERINGS) {
			var scored = ExactCategoricalSolver.eliminationOrderScoreForTest(
				variables, domains, List.of(), ordering);
			Assert.assertEquals(ordering, count, scored.fillComputations());
			Assert.assertEquals(ordering, count, scored.neighborCellComputations());
			if(ordering.equals("MIN_DEGREE"))
				Assert.assertEquals(count, scored.degreeComputations());
			else
				Assert.assertEquals(0, scored.degreeComputations());
			long uncachedComparisonLowerBound = (long)count * (count - 1);
			Assert.assertTrue(ordering, scored.fillComputations() * 10 < uncachedComparisonLowerBound);
		}
	}

	private static void assertOrderParity(String message, List<Variable> variables,
		int[] domains, List<int[]> scopes, String ordering) {
		ReferencePlan expected = reference(variables, domains, scopes, ordering);
		var actual = ExactCategoricalSolver.eliminationOrderScoreForTest(
			variables, domains, scopes, ordering);
		Assert.assertEquals(message, expected.statistics, actual.statistics());
		Assert.assertEquals(message, expected.separators, actual.separators());
	}

	private static ReferencePlan reference(List<Variable> variables, int[] domains,
		List<int[]> scopes, String ordering) {
		int count = variables.size();
		boolean[][] edges = new boolean[count][count];
		for(int[] scope : scopes)
			for(int left = 0; left < scope.length; left++)
				for(int right = left + 1; right < scope.length; right++)
					edges[scope[left]][scope[right]] = edges[scope[right]][scope[left]] = true;
		boolean[] remaining = new boolean[count];
		java.util.Arrays.fill(remaining, true);
		List<String> order = new ArrayList<>();
		List<List<String>> separators = new ArrayList<>();
		long maximumCells = 0;
		long materializedCells = 0;
		long maximumAssignments = 0;
		long assignments = 0;
		int width = 0;
		for(int step = 0; step < count; step++) {
			int selected = -1;
			Score selectedScore = null;
			for(int candidate = 0; candidate < count; candidate++) {
				if(!remaining[candidate])
					continue;
				Score score = freshScore(candidate, edges, remaining, domains);
				if(selected < 0 || compare(score, variables.get(candidate).key(), selectedScore,
					variables.get(selected).key(), ordering) < 0) {
					selected = candidate;
					selectedScore = score;
				}
			}
			List<Integer> neighbors = new ArrayList<>();
			for(int index = 0; index < count; index++)
				if(remaining[index] && edges[selected][index])
					neighbors.add(index);
			order.add(variables.get(selected).key());
			separators.add(neighbors.stream().map(index -> variables.get(index).key()).toList());
			width = Math.max(width, neighbors.size());
			long cells = saturatedProduct(neighbors, domains);
			long eliminated = saturatedMultiply(cells, domains[selected]);
			maximumCells = Math.max(maximumCells, cells);
			materializedCells = saturatedAdd(materializedCells, cells);
			maximumAssignments = Math.max(maximumAssignments, eliminated);
			assignments = saturatedAdd(assignments, eliminated);
			for(int left = 0; left < neighbors.size(); left++)
				for(int right = left + 1; right < neighbors.size(); right++)
					edges[neighbors.get(left)][neighbors.get(right)] =
						edges[neighbors.get(right)][neighbors.get(left)] = true;
			remaining[selected] = false;
		}
		return new ReferencePlan(new Statistics(order, width, maximumCells, materializedCells,
			maximumAssignments, assignments), List.copyOf(separators));
	}

	private static Score freshScore(int variable, boolean[][] edges,
		boolean[] remaining, int[] domains) {
		List<Integer> neighbors = new ArrayList<>();
		for(int candidate = 0; candidate < remaining.length; candidate++)
			if(remaining[candidate] && edges[variable][candidate])
				neighbors.add(candidate);
		long fill = 0;
		for(int left = 0; left < neighbors.size(); left++)
			for(int right = left + 1; right < neighbors.size(); right++)
				if(!edges[neighbors.get(left)][neighbors.get(right)])
					fill++;
		long cells = saturatedProduct(neighbors, domains);
		return new Score(fill, cells, saturatedMultiply(cells, domains[variable]), neighbors.size());
	}

	private static int compare(Score left, String leftKey, Score right, String rightKey,
		String ordering) {
		int comparison = switch(ordering) {
			case "MIN_FILL" -> compare(left.fill, right.fill, left.cells, right.cells);
			case "MIN_SEPARATOR_CELLS" -> compare(left.cells, right.cells, left.fill, right.fill);
			case "MIN_ELIMINATION_ASSIGNMENTS" -> compare(left.assignments, right.assignments,
				left.cells, right.cells, left.fill, right.fill);
			case "MIN_DEGREE" -> compare(left.degree, right.degree, left.cells, right.cells,
				left.fill, right.fill);
			default -> throw new IllegalArgumentException(ordering);
		};
		return comparison != 0 ? comparison : leftKey.compareTo(rightKey);
	}

	private static int compare(long... values) {
		for(int index = 0; index < values.length; index += 2) {
			int comparison = Long.compare(values[index], values[index + 1]);
			if(comparison != 0)
				return comparison;
		}
		return 0;
	}

	private static int compare(Statistics left, int leftPriority,
		Statistics right, int rightPriority) {
		int comparison = compare(left.maximumFactorCells(), right.maximumFactorCells(),
			left.materializedFactorCells(), right.materializedFactorCells(),
			left.maximumEliminationAssignments(), right.maximumEliminationAssignments(),
			left.eliminationAssignments(), right.eliminationAssignments(),
			left.inducedWidth(), right.inducedWidth());
		return comparison != 0 ? comparison : Integer.compare(leftPriority, rightPriority);
	}

	private static long saturatedProduct(List<Integer> variables, int[] domains) {
		BigInteger product = BigInteger.ONE;
		for(int variable : variables)
			product = product.multiply(BigInteger.valueOf(domains[variable]));
		return product.compareTo(LONG_MAX) >= 0 ? Long.MAX_VALUE : product.longValueExact();
	}

	private static long saturatedMultiply(long left, long right) {
		BigInteger product = BigInteger.valueOf(left).multiply(BigInteger.valueOf(right));
		return product.compareTo(LONG_MAX) >= 0 ? Long.MAX_VALUE : product.longValueExact();
	}

	private static long saturatedAdd(long left, long right) {
		BigInteger sum = BigInteger.valueOf(left).add(BigInteger.valueOf(right));
		return sum.compareTo(LONG_MAX) >= 0 ? Long.MAX_VALUE : sum.longValueExact();
	}

	private static List<Variable> variables(int[] domains, Random random) {
		List<String> keys = new ArrayList<>();
		for(int index = 0; index < domains.length; index++)
			keys.add(String.format("key-%02d", index));
		Collections.shuffle(keys, random);
		List<Variable> variables = new ArrayList<>();
		for(int index = 0; index < keys.size(); index++)
			variables.add(new Variable(keys.get(index), domains[index]));
		return variables;
	}

	private static List<int[]> randomScopes(int count, Random random) {
		List<int[]> scopes = new ArrayList<>();
		int scopeCount = random.nextInt(count * 2 + 1);
		for(int scopeIndex = 0; scopeIndex < scopeCount; scopeIndex++) {
			List<Integer> candidates = new ArrayList<>();
			for(int index = 0; index < count; index++)
				candidates.add(index);
			Collections.shuffle(candidates, random);
			int size = 1 + random.nextInt(Math.min(4, count));
			int[] scope = candidates.subList(0, size).stream().mapToInt(Integer::intValue).toArray();
			scopes.add(scope);
		}
		return scopes;
	}

	private record Score(long fill, long cells, long assignments, long degree) { }
	private record ReferencePlan(Statistics statistics, List<List<String>> separators) { }
}
