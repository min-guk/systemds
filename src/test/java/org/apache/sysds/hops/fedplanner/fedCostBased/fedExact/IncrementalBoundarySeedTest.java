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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Test;

public class IncrementalBoundarySeedTest {
	private static final Limits LIMITS = new Limits(100_000, 1_000_000);

	@Test
	public void choosesJointCompromiseInsteadOfEitherLeafMinimum() {
		var boundary = new Variable("three-state-boundary", 3);
		List<Variable> variables = List.of(boundary);
		List<Factor> factors = List.of(
			Factor.dense(variables, 0, 9, 2),
			Factor.dense(variables, 9, 0, 2));

		var result = run(variables, factors, List.of(0));
		assertSeedCheckpoint(result, 9, 4);
		assertEquals(List.of(2), result.assignment());
		assertCanonical(result, variables, factors, 4);
	}

	@Test
	public void changesCoupledVariablesTogetherWhenIndividualMovesAreForbidden() {
		var left = new Variable("coupled-left", 2);
		var right = new Variable("coupled-right", 2);
		List<Variable> variables = List.of(left, right);
		List<Factor> factors = List.of(
			Factor.dense(List.of(left), 5, 0),
			Factor.dense(List.of(right), 5, 0),
			equality(left, right));

		var result = run(variables, factors, List.of(0, 0));
		assertSeedCheckpoint(result, 10, 0);
		assertEquals(List.of(1, 1), result.assignment());
		assertCanonical(result, variables, factors, 0);
	}

	@Test
	public void changesThreeOriginalsAcrossAnEqualityChain() {
		var first = new Variable("chain-first", 2);
		var second = new Variable("chain-second", 2);
		var third = new Variable("chain-third", 2);
		List<Variable> variables = List.of(first, second, third);
		List<Factor> factors = List.of(
			Factor.dense(List.of(first), 4, 0),
			Factor.dense(List.of(second), 5, 0),
			Factor.dense(List.of(third), 6, 0),
			equality(first, second),
			equality(second, third));

		var result = run(variables, factors, List.of(0, 0, 0));
		assertSeedCheckpoint(result, 15, 0);
		assertEquals(List.of(1, 1, 1), result.assignment());
		assertCanonical(result, variables, factors, 0);
	}

	@Test
	public void includesCrossingHardAndCostFactorsWithOutsideValueFixed() {
		var left = new Variable("crossing-left", 2);
		var right = new Variable("crossing-right", 2);
		var outside = new Variable("fixed-outside", 2);
		List<Variable> variables = List.of(left, right, outside);
		List<Factor> factors = List.of(
			Factor.dense(List.of(left), 8, 0),
			Factor.dense(List.of(right), 8, 0),
			equality(left, right),
			// At outside=0, only left=1 is allowed. At outside=1, only left=0 is allowed.
			Factor.dense(List.of(left, outside),
				Double.POSITIVE_INFINITY, 0, 0, Double.POSITIVE_INFINITY),
			// This crossing cost must be included, but must not entice the pass to move outside.
			Factor.dense(List.of(right, outside), 20, 0, 3, 100),
			Factor.dense(List.of(outside), 0, 100));

		var result = run(variables, factors, List.of(1, 1, 0));
		assertSeedCheckpoint(result, 3, 3);
		assertEquals("the seed pass must retain the fixed outside value",
			Integer.valueOf(0), result.assignment().get(2));
		assertCanonical(result, variables, factors, 3);
	}

	@Test
	public void leavesOriginalBeyondTwoInteractionRingsFixedDuringSeedPass() {
		var first = new Variable("ring-first", 2);
		var second = new Variable("ring-second", 2);
		var third = new Variable("ring-third", 2);
		var outside = new Variable("ring-outside", 2);
		List<Variable> variables = List.of(first, second, third, outside);
		List<Factor> factors = List.of(
			Factor.dense(List.of(first), 10, 0),
			equality(first, second),
			equality(second, third),
			equality(third, outside));

		var result = run(variables, factors, List.of(0, 0, 0, 0));
		assertSeedCheckpoint(result, 10, 10);
		assertEquals("the exact merge may subsequently change the full chain",
			List.of(1, 1, 1, 1), result.assignment());
		assertCanonical(result, variables, factors, 0);
	}

	@Test
	public void restoresPrivateInteriorDecisionAfterBoundaryChanges() {
		var interior = new Variable("private-interior", 2);
		var boundary = new Variable("backtrace-boundary", 2);
		List<Variable> variables = List.of(interior, boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(interior, boundary), 4, 50, 50, 0),
			Factor.dense(List.of(boundary), 0, 1));

		var result = run(variables, factors, List.of(0, 0));
		assertSeedCheckpoint(result, 4, 1);
		assertEquals(List.of(1, 1), result.assignment());
		assertCanonical(result, variables, factors, 1);
	}

	@Test
	public void followsAuxiliaryOnlyCostOwnerBackToOriginalDecisions() {
		var singletonSource = new Variable("singleton-source", 1);
		var firstConsumer = new Variable("first-consumer", 2);
		var secondConsumer = new Variable("second-consumer", 2);
		var firstOr = new Variable("first-or-aux", 2);
		var lastOr = new Variable("last-or-aux", 2);
		List<Variable> variables = List.of(singletonSource, firstConsumer, secondConsumer, firstOr, lastOr);
		List<Factor> factors = List.of(
			Factor.dense(List.of(firstConsumer), 1, 0),
			Factor.dense(List.of(secondConsumer), 2, 0),
			or(singletonSource, firstConsumer, firstOr),
			or(firstOr, secondConsumer, lastOr),
			// Its only original variable is a singleton, so direct ownerOriginals is empty.
			Factor.dense(List.of(singletonSource, lastOr), 0, 10));
		var problem = new RegionalSearchProblem(variables, factors, 3,
			assignment -> 1d - assignment.get(1) + 2d * (1 - assignment.get(2))
				+ 10d * (assignment.get(1) | assignment.get(2)));

		var result = run(problem, List.of(0, 1, 1));
		assertSeedCheckpoint(result, 10, 3);
		assertEquals(List.of(0, 0, 0), result.assignment());
		assertEquals(3, problem.evaluate(result.assignment()), 0);
	}

	@Test
	public void seedImprovementCanSatisfyEarlyStopBeforeAnyMerge() {
		var boundary = new Variable("early-stop-boundary", 3);
		List<Variable> variables = List.of(boundary);
		List<Factor> factors = List.of(
			Factor.dense(variables, 2, 9, 2),
			Factor.dense(variables, 9, 2, 2));
		var options = new IncrementalRegionalOptimizer.Options(.1, 100_000, 1_000_000, 0, 16, true);

		var result = run(RegionalSearchProblem.generic(variables, factors), List.of(0), options);
		assertSeedCheckpoint(result, 11, 4);
		assertEquals("TARGET_REACHED", result.stopReason());
		assertEquals(-1, indexOf(result, "MERGE"));
		assertEquals(List.of(2), result.assignment());
		assertCanonical(result, variables, factors, 4);
	}

	@Test
	public void rejectedSeedNeighborhoodRetainsFeasibleIncumbent() {
		var boundary = new Variable("resource-boundary", 3);
		List<Variable> variables = List.of(boundary);
		List<Factor> factors = List.of(
			Factor.dense(variables, 0, 9, 2),
			Factor.dense(variables, 9, 0, 2));
		var options = new IncrementalRegionalOptimizer.Options(0, 1, 1, 0, 16, false);

		var result = run(RegionalSearchProblem.generic(variables, factors), List.of(0), options);
		assertSeedCheckpoint(result, 9, 9);
		assertEquals("RESOURCE", result.stopReason());
		assertEquals(List.of(0), result.assignment());
		assertCanonical(result, variables, factors, 9);
	}

	@Test
	public void chargesEveryDuplicateFactorOccurrence() {
		var boundary = new Variable("duplicate-owner-boundary", 2);
		Factor repeated = Factor.dense(List.of(boundary), 0, 6);
		List<Variable> variables = List.of(boundary);
		List<Factor> factors = List.of(repeated, repeated, Factor.dense(List.of(boundary), 10, 0));

		var result = run(variables, factors, List.of(0));
		assertSeedCheckpoint(result, 10, 10);
		assertEquals(List.of(0), result.assignment());
		assertCanonical(result, variables, factors, 10);
	}

	@Test
	public void deterministicTieRetainsIncumbentAssignment() {
		var boundary = new Variable("tie-boundary", 2);
		List<Variable> variables = List.of(boundary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(boundary), 0, 1),
			Factor.dense(List.of(boundary), 1, 0));

		var result = run(variables, factors, List.of(1));
		assertSeedCheckpoint(result, 1, 1);
		assertEquals(List.of(1), result.assignment());
		assertCanonical(result, variables, factors, 1);
	}

	private static IncrementalRegionalOptimizer.Result run(List<Variable> variables,
		List<Factor> factors, List<Integer> seed) {
		var problem = RegionalSearchProblem.generic(variables, factors);
		return run(problem, seed);
	}

	private static IncrementalRegionalOptimizer.Result run(RegionalSearchProblem problem,
		List<Integer> seed) {
		return run(problem, seed,
			new IncrementalRegionalOptimizer.Options(0, 100_000, 1_000_000, 0, 16, false));
	}

	private static IncrementalRegionalOptimizer.Result run(RegionalSearchProblem problem,
		List<Integer> seed, IncrementalRegionalOptimizer.Options options) {
		return IncrementalRegionalOptimizer.optimize(problem, problem.reducedRoot(LIMITS), seed, LIMITS,
			options, ignored -> { });
	}

	private static void assertSeedCheckpoint(IncrementalRegionalOptimizer.Result result,
		double initialUpper, double seedUpper) {
		var initial = checkpoint(result, "INITIAL_BOUND");
		var seed = checkpoint(result, "SEED_BOUNDARY");
		assertTrue(indexOf(result, "INITIAL_BOUND") < indexOf(result, "SEED_BOUNDARY"));
		int firstMerge = indexOf(result, "MERGE");
		assertTrue("the seed pass must precede persistent message merges",
			firstMerge < 0 || indexOf(result, "SEED_BOUNDARY") < firstMerge);
		assertEquals(initialUpper, initial.upper(), 0);
		assertEquals(seedUpper, seed.upper(), 0);
		assertEquals(initial.lower(), seed.lower(), 0);
		assertEquals(initial.merges(), seed.merges());
		assertEquals(initial.activeClusters(), seed.activeClusters());
		assertEquals(initial.assignments(), seed.assignments());
		assertEquals(initial.retainedSlots(), seed.retainedSlots());
		assertEquals(initial.internalDecisions(), seed.internalDecisions());
	}

	private static IncrementalRegionalOptimizer.Checkpoint checkpoint(
		IncrementalRegionalOptimizer.Result result, String phase) {
		return result.checkpoints().stream().filter(cp -> cp.phase().equals(phase)).findFirst().orElseThrow();
	}

	private static int indexOf(IncrementalRegionalOptimizer.Result result, String phase) {
		for(int index = 0; index < result.checkpoints().size(); index++)
			if(result.checkpoints().get(index).phase().equals(phase))
				return index;
		return -1;
	}

	private static void assertCanonical(IncrementalRegionalOptimizer.Result result,
		List<Variable> variables, List<Factor> factors, double expected) {
		assertEquals(expected, result.upper(), 0);
		assertEquals(expected,
			RegionalSearchProblem.evaluateFactors(variables, factors, result.assignment()), 0);
	}

	private static Factor equality(Variable left, Variable right) {
		return Factor.dense(List.of(left, right),
			0, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0);
	}

	private static Factor or(Variable left, Variable right, Variable output) {
		double inf = Double.POSITIVE_INFINITY;
		if(left.domainSize() == 1)
			return Factor.dense(List.of(left, right, output), 0, inf, inf, 0);
		return Factor.dense(List.of(left, right, output),
			0, inf, inf, 0, inf, 0, inf, 0);
	}
}
