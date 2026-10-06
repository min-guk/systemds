/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class SharedRegionalCompactionParityTest {
	private static final Limits GENEROUS = new Limits(1_000_000, 10_000_000);

	@Test
	public void heterogeneousConditionedBlocksMatchExhaustiveOracleAndReuseBoundaries() {
		Random random = new Random(28_092_026L);
		for(int trial = 0; trial < 24; trial++) {
			Variable left = variable("left-" + trial, 2);
			Variable boundary = variable("boundary-" + trial, 3);
			Variable right = variable("right-" + trial, 2);
			List<Variable> variables = List.of(left, boundary, right);
			List<Factor> factors = List.of(
				Factor.dense(List.of(left), randomFiniteCosts(random, 2)),
				Factor.dense(List.of(left, boundary), randomFiniteCosts(random, 6)),
				Factor.dense(List.of(boundary, right), randomFiniteCosts(random, 6)),
				Factor.dense(List.of(left, right), 0d, Double.POSITIVE_INFINITY,
					Double.POSITIVE_INFINITY, 0d),
				Factor.dense(List.of(left, boundary, right), randomFiniteCosts(random, 12)));
			SharedRegionalPreparation preparation = compact(variables, factors);

			for(int boundaryValue = 0; boundaryValue < boundary.domainSize(); boundaryValue++) {
				int[] assignment = {trial & 1, boundaryValue, (trial + 1) & 1};
				assertMatchesOracle(preparation, variables, factors, assignment,
					new int[] {0, 2});
				long builds = preparation.tableBuilds();
				long hits = preparation.cacheHits();

				assignment[0] ^= 1;
				assignment[2] ^= 1;
				assertMatchesOracle(preparation, variables, factors, assignment,
					new int[] {0, 2});
				Assert.assertEquals("same fixed boundary rebuilt a conditioned table",
					builds, preparation.tableBuilds());
				Assert.assertTrue("same fixed boundary did not hit the conditioned cache",
					preparation.cacheHits() > hits);
			}
			Assert.assertEquals(0L, preparation.fallbacks());
			Assert.assertEquals(6L, preparation.blocks());
		}
	}

	@Test
	public void changedBoundaryRebuildsWithoutChangingConditionalExactness() {
		Variable block = variable("cache-block", 3);
		Variable boundary = variable("cache-boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			8d, 0d,
			1d, 6d,
			4d, 2d));
		SharedRegionalPreparation preparation = compact(variables, factors);

		assertMatchesOracle(preparation, variables, factors, new int[] {2, 0}, new int[] {0});
		long firstBuilds = preparation.tableBuilds();
		assertMatchesOracle(preparation, variables, factors, new int[] {0, 0}, new int[] {0});
		Assert.assertEquals(firstBuilds, preparation.tableBuilds());
		Assert.assertTrue(preparation.cacheHits() > 0L);

		assertMatchesOracle(preparation, variables, factors, new int[] {1, 1}, new int[] {0});
		Assert.assertTrue("changed fixed boundary did not rebuild its conditioned table",
			preparation.tableBuilds() > firstBuilds);
	}

	@Test
	public void unsupportedFixedBoundaryRequestsOriginalDecisionRepair() {
		Variable block = variable("fallback-block", 2);
		Variable boundary = variable("fallback-boundary", 3);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(block, boundary),
				1d, 2d, 3d,
				3d, 2d, 1d),
			Factor.dense(List.of(boundary), Double.POSITIVE_INFINITY, 0d, 0d));
		SharedRegionalPreparation preparation = compact(variables, factors);

		LocalCategoricalOptimizer.UnsupportedBoundaryException boundaryFailure = Assert.assertThrows(
			LocalCategoricalOptimizer.UnsupportedBoundaryException.class,
			() -> preparation.prepare(new int[] {0, 0}, new int[] {0}));
		Assert.assertArrayEquals(new int[] {1}, boundaryFailure.variables);
		Assert.assertEquals(0L, preparation.fallbacks());
		Assert.assertEquals(0L, preparation.blocks());
	}

	@Test
	public void conditionedSingletonConstantsRetainObjectiveAndOriginalSourceValue() {
		Variable block = variable("singleton-block", 3);
		Variable boundary = variable("singleton-boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(block), Double.POSITIVE_INFINITY, 2d,
				Double.POSITIVE_INFINITY),
			Factor.dense(List.of(block), 1d, 1d, 1d),
			Factor.dense(List.of(block, boundary),
				9d, 8d,
				4d, 0.5d,
				7d, 6d));
		SharedRegionalPreparation preparation = compact(variables, factors);

		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(new int[] {0, 1}, new int[] {0});
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();

		Assert.assertEquals(List.of(1), result.assignmentInVariableOrder());
		Assert.assertEquals(0L, result.statistics().eliminationAssignments());
		Assert.assertEquals(Double.doubleToRawLongBits(3.5d),
			Double.doubleToRawLongBits(result.objective()));
		Assert.assertEquals(Double.doubleToRawLongBits(result.objective()),
			Double.doubleToRawLongBits(RegionalSearchProblem.evaluateFactors(
				variables, factors, List.of(1, 1))));
	}

	@Test
	public void conditioningActivatesSupportQuotientAndSingletonCompaction() {
		Variable block = variable("activated-block", 3);
		Variable boundary = variable("activated-boundary", 2);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			0d, 5d,
			0d, 6d,
			Double.POSITIVE_INFINITY, 1d));
		int[] assignment = {2, 0};
		int[] selectedBlock = {0};
		SharedRegionalPreparation compact = compact(variables, factors);
		SharedRegionalPreparation plain = new SharedRegionalPreparation(
			RegionalSearchProblem.generic(variables, factors), GENEROUS, false);

		LocalCategoricalOptimizer.PreparedBlockSolver compactSolver =
			compact.prepare(assignment, selectedBlock);
		LocalCategoricalOptimizer.PreparedBlockSolver plainSolver =
			plain.prepare(assignment, selectedBlock);
		Assert.assertNotNull(compactSolver);
		Assert.assertNotNull(plainSolver);
		ExactCategoricalSolver.Result compactResult = compactSolver.solve();
		ExactCategoricalSolver.Result plainResult = plainSolver.solve();
		Oracle oracle = oracle(variables, factors, assignment, selectedBlock);

		Assert.assertEquals(Double.doubleToRawLongBits(oracle.objective()),
			Double.doubleToRawLongBits(compactResult.objective()));
		Assert.assertTrue(oracle.optimalBlockAssignments()
			.contains(compactResult.assignmentInVariableOrder()));
		Assert.assertEquals(List.of(0), compactResult.assignmentInVariableOrder());
		Assert.assertEquals(0L, compactResult.statistics().eliminationAssignments());
		Assert.assertTrue("plain shared preparation unexpectedly performed no elimination work",
			plainResult.statistics().eliminationAssignments() > 0L);
		Assert.assertTrue("conditioned reduction did not reduce exact elimination work",
			compactResult.statistics().eliminationAssignments()
				< plainResult.statistics().eliminationAssignments());
		Assert.assertEquals(Double.doubleToRawLongBits(compactResult.objective()),
			Double.doubleToRawLongBits(plainResult.objective()));
		Assert.assertTrue(oracle.optimalBlockAssignments()
			.contains(plainResult.assignmentInVariableOrder()));
	}

	@Test
	public void coupledEqualCostTieRequiresOnlyOptimalFeasibleMembership() {
		Variable left = variable("tie-left", 2);
		Variable right = variable("tie-right", 2);
		Variable boundary = variable("tie-boundary", 2);
		List<Variable> variables = List.of(left, right, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(left, right),
				Double.POSITIVE_INFINITY, 0d,
				0d, Double.POSITIVE_INFINITY),
			Factor.dense(List.of(left, boundary), 0d, 2d, 0d, 2d));
		SharedRegionalPreparation preparation = compact(variables, factors);

		LocalCategoricalOptimizer.PreparedBlockSolver solver =
			preparation.prepare(new int[] {0, 0, 1}, new int[] {0, 1});
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result result = solver.solve();

		Assert.assertEquals(Double.doubleToRawLongBits(2d),
			Double.doubleToRawLongBits(result.objective()));
		Assert.assertTrue(Set.of(List.of(0, 1), List.of(1, 0))
			.contains(result.assignmentInVariableOrder()));
		List<Integer> full = List.of(result.assignmentInVariableOrder().get(0),
			result.assignmentInVariableOrder().get(1), 1);
		Assert.assertEquals(Double.doubleToRawLongBits(result.objective()),
			Double.doubleToRawLongBits(RegionalSearchProblem.evaluateFactors(
				variables, factors, full)));
	}

	@Test
	public void oneUlpConditionalCostDifferenceIsNotQuotientedAway() {
		Variable block = variable("ulp-block", 2);
		Variable boundary = variable("ulp-boundary", 2);
		double high = Math.nextUp(1d);
		List<Variable> variables = List.of(block, boundary);
		List<Factor> factors = List.of(Factor.dense(List.of(block, boundary),
			1d, high,
			high, 1d));
		SharedRegionalPreparation preparation = compact(variables, factors);

		for(int boundaryValue = 0; boundaryValue < 2; boundaryValue++) {
			LocalCategoricalOptimizer.PreparedBlockSolver solver = preparation.prepare(
				new int[] {1 - boundaryValue, boundaryValue}, new int[] {0});
			Assert.assertNotNull(solver);
			ExactCategoricalSolver.Result result = solver.solve();
			Assert.assertEquals(List.of(boundaryValue), result.assignmentInVariableOrder());
			Assert.assertEquals(Double.doubleToRawLongBits(1d),
				Double.doubleToRawLongBits(result.objective()));
		}
	}

	private static void assertMatchesOracle(SharedRegionalPreparation preparation,
		List<Variable> variables, List<Factor> factors, int[] assignment, int[] block) {
		LocalCategoricalOptimizer.PreparedBlockSolver solver = preparation.prepare(assignment, block);
		Assert.assertNotNull(solver);
		ExactCategoricalSolver.Result actual = solver.solve();
		Oracle oracle = oracle(variables, factors, assignment, block);

		Assert.assertEquals(Double.doubleToRawLongBits(oracle.objective()),
			Double.doubleToRawLongBits(actual.objective()));
		Assert.assertTrue("non-optimal source assignment " + actual.assignmentInVariableOrder()
			+ " not in " + oracle.optimalBlockAssignments(),
			oracle.optimalBlockAssignments().contains(actual.assignmentInVariableOrder()));
		List<Integer> full = new ArrayList<>(assignment.length);
		for(int value : assignment)
			full.add(value);
		for(int i = 0; i < block.length; i++)
			full.set(block[i], actual.assignmentInVariableOrder().get(i));
		Assert.assertEquals(Double.doubleToRawLongBits(actual.objective()),
			Double.doubleToRawLongBits(RegionalSearchProblem.evaluateFactors(
				variables, factors, full)));
	}

	private static Oracle oracle(List<Variable> variables, List<Factor> factors,
		int[] assignment, int[] block) {
		double[] optimum = {Double.POSITIVE_INFINITY};
		Set<List<Integer>> optimal = new LinkedHashSet<>();
		enumerate(variables, factors, assignment.clone(), block, 0, optimum, optimal);
		Assert.assertTrue("oracle unexpectedly found no feasible assignment", Double.isFinite(optimum[0]));
		return new Oracle(optimum[0], Set.copyOf(optimal));
	}

	private static void enumerate(List<Variable> variables, List<Factor> factors,
		int[] assignment, int[] block, int offset, double[] optimum,
		Set<List<Integer>> optimal) {
		if(offset < block.length) {
			int variable = block[offset];
			for(int value = 0; value < variables.get(variable).domainSize(); value++) {
				assignment[variable] = value;
				enumerate(variables, factors, assignment, block, offset + 1, optimum, optimal);
			}
			return;
		}
		List<Integer> full = new ArrayList<>(assignment.length);
		for(int value : assignment)
			full.add(value);
		double objective = RegionalSearchProblem.evaluateFactors(variables, factors, full);
		if(!Double.isFinite(objective))
			return;
		List<Integer> blockAssignment = new ArrayList<>(block.length);
		for(int variable : block)
			blockAssignment.add(assignment[variable]);
		int comparison = Double.compare(objective, optimum[0]);
		if(comparison < 0) {
			optimum[0] = objective;
			optimal.clear();
			optimal.add(List.copyOf(blockAssignment));
		}
		else if(comparison == 0)
			optimal.add(List.copyOf(blockAssignment));
	}

	private static SharedRegionalPreparation compact(List<Variable> variables,
		List<Factor> factors) {
		return new SharedRegionalPreparation(
			RegionalSearchProblem.generic(variables, factors), GENEROUS, true);
	}

	private static double[] randomFiniteCosts(Random random, int cells) {
		double[] costs = new double[cells];
		for(int cell = 0; cell < cells; cell++)
			costs[cell] = random.nextInt(17);
		return costs;
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}

	private record Oracle(double objective, Set<List<Integer>> optimalBlockAssignments) { }
}
