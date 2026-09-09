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

import java.util.ArrayList;
import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class RegionalCompactTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 5_000_000);

	@Test
	public void nonCompactBlockUsesExactReductionWithoutRemovingSingletonVariables() {
		Variable forced = variable("reduced-forced", 4);
		Variable equivalent = variable("reduced-equivalent", 3);
		List<Variable> variables = List.of(forced, equivalent);
		List<Factor> hard = List.of(Factor.dense(List.of(forced),
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d,
			Double.POSITIVE_INFINITY));
		List<Factor> cost = List.of(
			// A cheaper but illegal value forces the joint block through exact solve.
			Factor.dense(List.of(forced), 0d, 8d, 3d, 9d),
			// Values 0 and 1 have identical observations and share one exact quotient class.
			Factor.dense(List.of(equivalent), 1d, 1d, 6d));
		List<Factor> factors = concatenate(hard, cost);

		LocalCategoricalOptimizer.Result regional = optimize(
			variables, hard, cost, List.of(variables), 0, false);
		ExactPhysicalReducedSolver.Prepared reduced = ExactPhysicalReducedSolver.prepare(
			variables.size(), variables, factors, GENEROUS);
		ExactCategoricalSolver.CompiledProblem raw = ExactCategoricalSolver.compile(
			variables, factors, GENEROUS);

		Assert.assertEquals(List.of(2, 0), regional.assignmentInVariableOrder());
		Assert.assertEquals(4d, regional.objective(), 0d);
		Assert.assertEquals(regional.objective(), ExactCategoricalSolver.evaluate(
			variables, factors, GENEROUS, regional.assignmentInVariableOrder()), 0d);
		Assert.assertEquals("Regional must report the matched reduced solve work",
			reduced.statistics().eliminationAssignments(),
			regional.statistics().blockAssignments());
		Assert.assertTrue("support and equivalence reduction must beat raw compilation",
			regional.statistics().blockAssignments()
				< ExactCategoricalSolver.statistics(raw).eliminationAssignments());
		Assert.assertEquals("noncompact reduction retains its singleton variable",
			variables.size(), reduced.compiledVariableCount());
		Assert.assertEquals(0, regional.statistics().finalHardViolations());
	}

	@Test
	public void compactBlockMatchesConditionalOracleAndRestoresOriginalValues() {
		Variable boundary = variable("boundary", 2);
		Variable forced = variable("forced", 3);
		Variable equivalent = variable("equivalent", 3);
		List<Variable> variables = List.of(boundary, forced, equivalent);
		List<Factor> hard = List.of(Factor.lazy(List.of(boundary, forced), values ->
			values[0] == 1 && values[1] == 2 ? 0d
				: values[0] == 0 && values[1] == 0 ? 0d : Double.POSITIVE_INFINITY));
		List<Factor> cost = List.of(
			Factor.dense(List.of(), 2.5d),
			Factor.dense(List.of(boundary), 4d, 0d),
			// The cheaper forced=0 value makes the block require an exact solve,
			// while the fixed boundary leaves only forced=2 legal.
			Factor.dense(List.of(forced), 0d, 8d, 3d),
			// Values 0 and 1 are observationally equivalent and must expand to the
			// deterministic original representative after quotienting.
			Factor.dense(List.of(equivalent), 1d, 1d, 6d));

		LocalCategoricalOptimizer.Result legacy = optimize(
			variables, hard, cost, List.of(List.of(forced, equivalent)), 0, false);
		LocalCategoricalOptimizer.Result compact = optimize(
			variables, hard, cost, List.of(List.of(forced, equivalent)), 0, true);
		double conditionalOracle = conditionalObjective(variables, hard, cost, boundary, 1);

		Assert.assertEquals(List.of(1, 2, 0), compact.assignmentInVariableOrder());
		Assert.assertEquals(legacy.assignmentInVariableOrder(), compact.assignmentInVariableOrder());
		Assert.assertEquals(conditionalOracle, compact.objective(), 0d);
		Assert.assertEquals(compact.objective(), ExactCategoricalSolver.evaluate(
			variables, concatenate(hard, cost), GENEROUS, compact.assignmentInVariableOrder()), 0d);
		Assert.assertTrue("singleton substitution must reduce completed elimination work",
			compact.statistics().blockAssignments() < legacy.statistics().blockAssignments());
		Assert.assertEquals(0, compact.statistics().finalHardViolations());
	}

	@Test
	public void allSingletonBlockKeepsCollapsedConstantsAndFeasibleRepresentatives() {
		Variable boundary = variable("boundary", 2);
		Variable forced = variable("forced", 2);
		Variable equivalentForced = variable("equivalent-forced", 3);
		List<Variable> variables = List.of(boundary, forced, equivalentForced);
		List<Factor> hard = List.of(
			Factor.lazy(List.of(boundary, forced), values -> values[0] == values[1]
				? 0d : Double.POSITIVE_INFINITY),
			Factor.dense(List.of(equivalentForced), Double.POSITIVE_INFINITY, 0d, 0d));
		List<Factor> cost = List.of(
			Factor.dense(List.of(), 2d),
			Factor.dense(List.of(boundary), 3d, 0d),
			Factor.dense(List.of(forced), 0d, 4d),
			Factor.dense(List.of(equivalentForced), 0d, 5d, 5d));

		LocalCategoricalOptimizer.Result compact = optimize(variables, hard, cost,
			List.of(List.of(forced, equivalentForced)), 0, true);
		double conditionalOracle = conditionalObjective(variables, hard, cost, boundary, 1);

		Assert.assertEquals(List.of(1, 1, 1), compact.assignmentInVariableOrder());
		Assert.assertEquals(11d, compact.objective(), 0d);
		Assert.assertEquals(conditionalOracle, compact.objective(), 0d);
		Assert.assertEquals("both reduced variables are substituted before elimination",
			0L, compact.statistics().blockAssignments());
		Assert.assertEquals(0, compact.statistics().finalHardViolations());
	}

	@Test
	public void compactRevisitRebuildsFromTheChangedFixedBoundary() {
		Variable boundary = variable("boundary", 2);
		Variable forced = variable("forced", 2);
		Variable follower = variable("follower", 2);
		Variable driver = variable("driver", 2);
		List<Variable> variables = List.of(boundary, forced, follower, driver);
		List<Factor> hard = List.of(Factor.lazy(List.of(boundary, forced), values ->
			values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY));
		List<Factor> cost = List.of(
			Factor.dense(List.of(boundary), 0d, 4d),
			Factor.dense(List.of(forced), 5d, 0d),
			Factor.dense(List.of(boundary, driver), 10d, 10d, 10d, 0d),
			Factor.dense(List.of(forced, follower), 0d, 5d, 5d, 0d));

		LocalCategoricalOptimizer.Result result = optimize(variables, hard, cost,
			List.of(List.of(forced, follower), List.of(boundary, forced, driver)), 1, true);
		LocalCategoricalOptimizer.Result nonCompact = optimize(variables, hard, cost,
			List.of(List.of(forced, follower), List.of(boundary, forced, driver)), 1, false);
		ExactCategoricalSolver.Result oracle = ExactCategoricalSolver.solve(
			variables, concatenate(hard, cost), GENEROUS);

		Assert.assertEquals("the revisit must not reuse forced=0 from the first boundary",
			List.of(1, 1, 1, 1), result.assignmentInVariableOrder());
		Assert.assertEquals(oracle.objective(), result.objective(), 0d);
		Assert.assertEquals(4d, result.objective(), 0d);
		Assert.assertTrue("the first block must be revisited after its dependency changes",
			result.statistics().localBlockRevisits() > 0);
		Assert.assertEquals(0, result.statistics().finalHardViolations());
		Assert.assertEquals("matched reduction must also rebuild from the new boundary",
			result.assignmentInVariableOrder(), nonCompact.assignmentInVariableOrder());
		Assert.assertEquals(oracle.objective(), nonCompact.objective(), 0d);
		Assert.assertTrue(nonCompact.statistics().localBlockRevisits() > 0);
		Assert.assertEquals(0, nonCompact.statistics().finalHardViolations());
	}

	@Test
	public void infeasibleConditionalCompactBlockExpandsWithoutCorruptingDomains() {
		Variable boundary = variable("boundary", 2);
		Variable leftBridge = variable("left-bridge", 2);
		Variable rightBridge = variable("right-bridge", 2);
		Variable left = variable("left", 2);
		Variable right = variable("right", 2);
		List<Variable> variables = List.of(boundary, leftBridge, rightBridge, left, right);
		List<Factor> hard = List.of(
			Factor.lazy(List.of(left, right), values -> values[0] == values[1]
				? 0d : Double.POSITIVE_INFINITY),
			Factor.lazy(List.of(leftBridge, left), values -> values[0] == values[1]
				? 0d : Double.POSITIVE_INFINITY),
			Factor.lazy(List.of(rightBridge, right), values -> values[0] == values[1]
				? 0d : Double.POSITIVE_INFINITY),
			// The initial response region reaches both bridges but not this two-hop
			// boundary. At boundary=0 the bridges are forced to disagree, so that
			// conditional compact solve is infeasible. Expanding to boundary=1
			// restores a feasible all-zero assignment without mutating any domain.
			Factor.dense(List.of(boundary, leftBridge),
				0d, Double.POSITIVE_INFINITY, 0d, 0d),
			Factor.dense(List.of(boundary, rightBridge),
				Double.POSITIVE_INFINITY, 0d, 0d, 0d));
		List<Factor> cost = List.of(
			Factor.dense(List.of(boundary), 0d, 2d),
			Factor.dense(List.of(leftBridge), 0d, 1d),
			Factor.dense(List.of(rightBridge), 0d, 1d),
			Factor.dense(List.of(left), 0d, 1d),
			Factor.dense(List.of(right), 2d, 0d));

		LocalCategoricalOptimizer.Result compact = optimize(
			variables, hard, cost, List.of(), 0, true);
		LocalCategoricalOptimizer.Result legacy = optimize(
			variables, hard, cost, List.of(), 0, false);
		ExactCategoricalSolver.Result oracle = ExactCategoricalSolver.solve(
			variables, concatenate(hard, cost), GENEROUS);

		Assert.assertEquals(List.of(1, 0, 0, 0, 0), compact.assignmentInVariableOrder());
		Assert.assertEquals(legacy.assignmentInVariableOrder(), compact.assignmentInVariableOrder());
		Assert.assertEquals(oracle.objective(), compact.objective(), 0d);
		Assert.assertTrue("the infeasible conditional repair must expand to its boundary",
			compact.statistics().conflictBlockExpansions() > 0);
		Assert.assertEquals(0, compact.statistics().finalHardViolations());
		Assert.assertTrue("matched reduction must preserve the same safe expansion path",
			legacy.statistics().conflictBlockExpansions() > 0);
		Assert.assertEquals(0, legacy.statistics().finalHardViolations());
	}

	@Test
	public void regionalCompactPropertyDefaultsOffRejectsInvalidValuesAndIsRestored() {
		String key = LocalCategoricalOptimizer.COMPACT_PROPERTY;
		String previous = System.getProperty(key);
		try {
			System.clearProperty(key);
			Assert.assertFalse(LocalCategoricalOptimizer.configuredCompaction());
			System.setProperty(key, "TrUe");
			Assert.assertTrue(LocalCategoricalOptimizer.configuredCompaction());
			System.setProperty(key, "FALSE");
			Assert.assertFalse(LocalCategoricalOptimizer.configuredCompaction());
			System.setProperty(key, "invalid");
			IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
				LocalCategoricalOptimizer::configuredCompaction);
			Assert.assertEquals("LOCAL_COMPACT_OPTION_INVALID|invalid", failure.getMessage());
		}
		finally {
			if(previous == null)
				System.clearProperty(key);
			else
				System.setProperty(key, previous);
		}
		Assert.assertEquals(previous, System.getProperty(key));
	}

	private static LocalCategoricalOptimizer.Result optimize(List<Variable> variables,
		List<Factor> hard, List<Factor> cost, List<List<Variable>> blocks,
		int revisitPasses, boolean compact) {
		return LocalCategoricalOptimizer.optimize(variables, hard, cost, variables, blocks,
			ignored -> List.of(), (variable, value) -> value, revisitPasses, compact);
	}

	private static double conditionalObjective(List<Variable> variables, List<Factor> hard,
		List<Factor> cost, Variable fixed, int value) {
		List<Factor> factors = new ArrayList<>(concatenate(hard, cost));
		factors.add(Factor.lazy(List.of(fixed), values -> values[0] == value
			? 0d : Double.POSITIVE_INFINITY));
		return ExactCategoricalSolver.solve(variables, factors, GENEROUS).objective();
	}

	private static List<Factor> concatenate(List<Factor> first, List<Factor> second) {
		List<Factor> result = new ArrayList<>(first.size() + second.size());
		result.addAll(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	private static Variable variable(String key, int domain) {
		return new Variable(key, domain);
	}
}
