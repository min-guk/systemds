/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

/** Regression coverage for sharing unconditional root reduction with local repair. */
public class SharedReductionRepairTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 10_000_000);

	@Test
	public void conditionalSupportIsRecomputedWhenBoundaryChangesOrBecomesFree() {
		Variable repair = variable("conditional-repair", 2);
		Variable boundary = variable("conditional-boundary", 2);
		List<Variable> variables = List.of(repair, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(repair, boundary),
				0d, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0d),
			Factor.dense(List.of(repair), 5d, 0d));

		for(boolean compact : new boolean[] {false, true}) {
			SharedRegionalPreparation preparation = shared(variables, factors, compact);

			assertMatchesOracle(preparation, variables, factors, new int[] {1, 0}, new int[] {0});
			assertMatchesOracle(preparation, variables, factors, new int[] {0, 1}, new int[] {0});
			assertMatchesOracle(preparation, variables, factors, new int[] {0, 0}, new int[] {0, 1});

			Assert.assertEquals(List.of(0), solve(preparation, new int[] {1, 0}, new int[] {0})
				.assignmentInVariableOrder());
			Assert.assertEquals(List.of(1), solve(preparation, new int[] {0, 1}, new int[] {0})
				.assignmentInVariableOrder());
			Assert.assertEquals(List.of(1, 1), solve(preparation, new int[] {0, 0}, new int[] {0, 1})
				.assignmentInVariableOrder());
		}
	}

	@Test
	public void conditionallyInfeasibleSliceBecomesFeasibleAfterBoundaryExpansion() {
		Variable repair = variable("expanded-repair", 2);
		Variable released = variable("expanded-released", 2);
		Variable fixed = variable("expanded-fixed", 2);
		List<Variable> variables = List.of(repair, released, fixed);
		List<Factor> factors = List.of(
			Factor.dense(List.of(repair, released),
				0d, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0d),
			Factor.dense(List.of(repair, fixed),
				0d, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0d));

		for(boolean compact : new boolean[] {false, true}) {
			SharedRegionalPreparation preparation = shared(variables, factors, compact);
			LocalCategoricalOptimizer.PreparedBlockSolver impossible =
				preparation.prepare(new int[] {0, 0, 1}, new int[] {0});
			IllegalArgumentException failure = Assert.assertThrows(
				IllegalArgumentException.class, impossible::solve);
			Assert.assertTrue(failure.getMessage(),
				failure.getMessage().startsWith("EXACT_VE_NO_FEASIBLE_ASSIGNMENT"));

			int[] expanded = {0, 1};
			int[] assignment = {0, 0, 1};
			assertMatchesOracle(preparation, variables, factors, assignment, expanded);
			Assert.assertEquals(List.of(1, 1),
				solve(preparation, assignment, expanded).assignmentInVariableOrder());
		}
	}

	@Test
	public void preparedConditionalSolverDoesNotChangeWhenAnotherBoundaryIsPrepared() {
		Variable repair = variable("immutable-repair", 2);
		Variable boundary = variable("immutable-boundary", 2);
		List<Variable> variables = List.of(repair, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(repair, boundary),
			0d, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, 0d));

		for(boolean compact : new boolean[] {false, true}) {
			SharedRegionalPreparation preparation = shared(variables, factors, compact);
			LocalCategoricalOptimizer.PreparedBlockSolver atZero =
				preparation.prepare(new int[] {1, 0}, new int[] {0});
			LocalCategoricalOptimizer.PreparedBlockSolver atOne =
				preparation.prepare(new int[] {0, 1}, new int[] {0});

			Assert.assertEquals(List.of(0), atZero.solve().assignmentInVariableOrder());
			Assert.assertEquals(List.of(1), atOne.solve().assignmentInVariableOrder());
		}
	}

	@Test
	public void nonContiguousOriginalValuesAndAuxiliaryClosureSurviveSharedReduction()
		throws Exception {
		Variable repair = variable("projected-repair", 5);
		Variable boundary = variable("projected-boundary", 2);
		Variable auxiliary = variable("projected-auxiliary", 2);
		List<Variable> variables = List.of(repair, boundary, auxiliary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(repair),
				Double.POSITIVE_INFINITY, 4d, Double.POSITIVE_INFINITY, 1d,
				Double.POSITIVE_INFINITY),
			Factor.dense(List.of(repair, auxiliary),
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
				0d, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0d,
				Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
			Factor.dense(List.of(auxiliary, boundary),
				0d, Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY, 0d));
		RegionalSearchProblem problem = problemWithDecisionPrefix(variables, factors, 2);

		for(boolean compact : new boolean[] {false, true}) {
			SharedRegionalPreparation preparation =
				new SharedRegionalPreparation(problem, GENEROUS, compact);
			int[][] unconditional = preparation.unconditionalDomains(List.of(repair, boundary));
			Assert.assertArrayEquals(new int[] {1, 3}, unconditional[0]);
			Assert.assertArrayEquals(new int[] {0, 1}, unconditional[1]);
			ExactCategoricalSolver.Result atZero =
				solve(preparation, new int[] {3, 0}, new int[] {0});
			ExactCategoricalSolver.Result atOne =
				solve(preparation, new int[] {1, 1}, new int[] {0});

			Assert.assertEquals(List.of(1), atZero.assignmentInVariableOrder());
			Assert.assertEquals(4d, atZero.objective(), 0d);
			Assert.assertEquals(List.of(3), atOne.assignmentInVariableOrder());
			Assert.assertEquals(1d, atOne.objective(), 0d);
		}
	}

	@Test
	public void allUnsupportedBoundariesAreReportedForOneRepairExpansion() {
		Variable repair = variable("batch-repair", 2);
		Variable leftBoundary = variable("batch-left-boundary", 2);
		Variable rightBoundary = variable("batch-right-boundary", 2);
		List<Variable> variables = List.of(repair, leftBoundary, rightBoundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(repair, leftBoundary), 0d, 1d, 1d, 0d),
			Factor.dense(List.of(repair, rightBoundary), 0d, 1d, 1d, 0d),
			Factor.dense(List.of(leftBoundary), Double.POSITIVE_INFINITY, 0d),
			Factor.dense(List.of(rightBoundary), Double.POSITIVE_INFINITY, 0d));

		for(boolean compact : new boolean[] {false, true}) {
			SharedRegionalPreparation preparation = shared(variables, factors, compact);
			LocalCategoricalOptimizer.UnsupportedBoundaryException failure = Assert.assertThrows(
				LocalCategoricalOptimizer.UnsupportedBoundaryException.class,
				() -> preparation.prepare(new int[] {0, 0, 0}, new int[] {0}));
			Assert.assertArrayEquals(new int[] {1, 2}, failure.variables);
			Assert.assertEquals(0L, preparation.blocks());
			Assert.assertEquals(0L, preparation.tableBuilds());
		}
	}

	@Test
	public void compactAndPlainRepairsMatchExhaustiveOracleAcrossSmallModels() {
		for(int seed = 0; seed < 12; seed++) {
			Random random = new Random(seed);
			Variable left = variable("random-left-" + seed, 3);
			Variable middle = variable("random-middle-" + seed, 3);
			Variable boundary = variable("random-boundary-" + seed, 3);
			List<Variable> variables = List.of(left, middle, boundary);
			double[] leftMiddle = supportedBinary(random, 3);
			double[] middleBoundary = supportedBinary(random, 3);
			double[] leftCost = randomCosts(random, 3);
			double[] middleCost = randomCosts(random, 3);
			List<Factor> factors = List.of(
				Factor.dense(List.of(left, middle), leftMiddle),
				Factor.dense(List.of(middle, boundary), middleBoundary),
				Factor.dense(List.of(left), leftCost),
				Factor.dense(List.of(middle), middleCost));

			SharedRegionalPreparation plain = shared(variables, factors, false);
			SharedRegionalPreparation compact = shared(variables, factors, true);
			for(int fixed = 0; fixed < boundary.domainSize(); fixed++) {
				int[] assignment = {random.nextInt(3), random.nextInt(3), fixed};
				int[] block = {0, 1};
				Oracle oracle = oracle(variables, factors, assignment, block);
				assertMatchesOracle(plain, variables, factors, assignment, block, oracle);
				assertMatchesOracle(compact, variables, factors, assignment, block, oracle);
			}
		}
	}

	private static SharedRegionalPreparation shared(List<Variable> variables,
		List<Factor> factors, boolean compact) {
		return new SharedRegionalPreparation(
			RegionalSearchProblem.generic(variables, factors), GENEROUS, compact);
	}

	private static ExactCategoricalSolver.Result solve(SharedRegionalPreparation preparation,
		int[] assignment, int[] block) {
		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(assignment, block);
		Assert.assertNotNull(solver);
		return solver.solve();
	}

	private static void assertMatchesOracle(SharedRegionalPreparation preparation,
		List<Variable> variables, List<Factor> factors, int[] assignment, int[] block) {
		assertMatchesOracle(preparation, variables, factors, assignment, block,
			oracle(variables, factors, assignment, block));
	}

	private static void assertMatchesOracle(SharedRegionalPreparation preparation,
		List<Variable> variables, List<Factor> factors, int[] assignment, int[] block,
		Oracle oracle) {
		ExactCategoricalSolver.Result actual = solve(preparation, assignment, block);
		Assert.assertEquals(Double.doubleToRawLongBits(oracle.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertTrue("non-optimal block assignment " + actual.assignmentInVariableOrder()
			+ " not in " + oracle.assignments(),
			oracle.assignments().contains(actual.assignmentInVariableOrder()));

		List<Integer> full = asList(assignment);
		for(int offset = 0; offset < block.length; offset++)
			full.set(block[offset], actual.assignmentInVariableOrder().get(offset));
		Assert.assertEquals(Double.doubleToRawLongBits(actual.objective()),
			Double.doubleToRawLongBits(
				RegionalSearchProblem.evaluateFactors(variables, factors, full)));
	}

	private static Oracle oracle(List<Variable> variables, List<Factor> factors,
		int[] assignment, int[] block) {
		double[] optimum = {Double.POSITIVE_INFINITY};
		Set<List<Integer>> assignments = new LinkedHashSet<>();
		enumerate(variables, factors, assignment.clone(), block, 0, optimum, assignments);
		Assert.assertTrue("oracle unexpectedly found no feasible block assignment",
			Double.isFinite(optimum[0]));
		return new Oracle(optimum[0], Set.copyOf(assignments));
	}

	private static void enumerate(List<Variable> variables, List<Factor> factors,
		int[] assignment, int[] block, int offset, double[] optimum,
		Set<List<Integer>> assignments) {
		if(offset < block.length) {
			int variable = block[offset];
			for(int value = 0; value < variables.get(variable).domainSize(); value++) {
				assignment[variable] = value;
				enumerate(variables, factors, assignment, block, offset + 1,
					optimum, assignments);
			}
			return;
		}

		double objective = RegionalSearchProblem.evaluateFactors(
			variables, factors, asList(assignment));
		if(!Double.isFinite(objective))
			return;
		List<Integer> local = new ArrayList<>(block.length);
		for(int variable : block)
			local.add(assignment[variable]);
		int comparison = Double.compare(objective, optimum[0]);
		if(comparison < 0) {
			optimum[0] = objective;
			assignments.clear();
			assignments.add(List.copyOf(local));
		}
		else if(comparison == 0)
			assignments.add(List.copyOf(local));
	}

	private static double[] supportedBinary(Random random, int domain) {
		double[] values = new double[domain * domain];
		for(int left = 0; left < domain; left++)
			for(int right = 0; right < domain; right++)
				values[left * domain + right] = left == right || random.nextBoolean()
					? 0d : Double.POSITIVE_INFINITY;
		return values;
	}

	private static double[] randomCosts(Random random, int domain) {
		double[] costs = new double[domain];
		for(int value = 0; value < domain; value++)
			costs[value] = random.nextInt(9);
		return costs;
	}

	@SuppressWarnings("unchecked")
	private static RegionalSearchProblem problemWithDecisionPrefix(List<Variable> variables,
		List<Factor> factors, int decisionCount) throws Exception {
		Constructor<RegionalSearchProblem> constructor = RegionalSearchProblem.class
			.getDeclaredConstructor(List.class, List.class, int.class, ToDoubleFunction.class);
		constructor.setAccessible(true);
		ToDoubleFunction<List<Integer>> evaluator = ignored -> 0d;
		return constructor.newInstance(variables, factors, decisionCount, evaluator);
	}

	private static List<Integer> asList(int[] assignment) {
		List<Integer> result = new ArrayList<>(assignment.length);
		for(int value : assignment)
			result.add(value);
		return result;
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private record Oracle(double objective, Set<List<Integer>> assignments) { }
}
