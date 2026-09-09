/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class LocalCategoricalOptimizerTest {
	@Test
	public void localPruningRetainsOnlyTheMinimumRepresentativePerState() {
		Variable variable = new Variable("x", 3);
		Factor cost = Factor.dense(List.of(variable), 8d, 2d, 3d);
		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			List.of(variable), List.of(), List.of(cost), List.of(variable), List.of(),
			(v, value) -> value < 2 ? "CP/LOUT" : "FED/FOUT");

		Assert.assertEquals(List.of(1), result.assignmentInVariableOrder());
		Assert.assertEquals(3L, result.statistics().rawLocalAlternatives());
		Assert.assertEquals(2L, result.statistics().retainedLocalStates());
		Assert.assertEquals(1L, result.statistics().prunedLocalRepresentatives());
	}

	@Test
	public void localPruningHasNoTopKCap() {
		Variable variable = new Variable("x", 12);
		double[] costs = new double[12];
		for(int value = 0; value < costs.length; value++)
			costs[value] = costs.length - value;
		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			List.of(variable), List.of(), List.of(Factor.dense(List.of(variable), costs)),
			List.of(variable), List.of(), (v, value) -> "state-" + value);

		Assert.assertEquals(List.of(11), result.assignmentInVariableOrder());
		Assert.assertEquals(12L, result.statistics().retainedLocalStates());
		Assert.assertEquals(0L, result.statistics().prunedLocalRepresentatives());
	}

	@Test
	public void conflictBlockChoosesTheCheapestLegalAssignmentNotTheFirstCoherentOne() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> hard = List.of(
			Factor.lazy(List.of(x, b), values -> values[0] == values[1]
				? 0d : Double.POSITIVE_INFINITY),
			Factor.lazy(List.of(a, b), values -> values[0] != values[1]
				? 0d : Double.POSITIVE_INFINITY));
		List<Factor> cost = List.of(
			Factor.dense(List.of(x), 0d, 2d),
			Factor.dense(List.of(a), 0d, 0d),
			// With x fixed to zero, the local pass chooses a=0 and creates a
			// simultaneous conflict at b.  The first coherent block assignment
			// (x=0,a=1,b=0) costs 10, while (x=1,a=0,b=1) costs 2.
			Factor.dense(List.of(x, a), 0d, 10d, 0d, 0d));

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, hard, cost, variables, List.of(), (v, value) -> value);

		Assert.assertEquals(List.of(1, 0, 1), result.assignmentInVariableOrder());
		Assert.assertTrue(result.statistics().initialHardViolations() > 0);
		Assert.assertEquals(0, result.statistics().finalHardViolations());
		Assert.assertTrue(result.statistics().conflictBlocksSolved() > 0);
		Assert.assertEquals(2d, result.objective(), 0d);
	}

	@Test
	public void sharedProducerBlockOptimizesProducerAndBothParentsTogether() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = new ArrayList<>();
		costs.add(Factor.dense(List.of(x), 0d, 4d));
		costs.add(Factor.dense(List.of(x, a), 5d, 7d, 8d, 0d));
		costs.add(Factor.dense(List.of(x, b), 5d, 7d, 8d, 0d));

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables, List.of(List.of(x, a, b)),
			(v, value) -> value);

		Assert.assertEquals("the joint x+a+b block must overturn the greedy x=0 choice",
			List.of(1, 1, 1), result.assignmentInVariableOrder());
		Assert.assertEquals(4d, result.objective(), 0d);
		Assert.assertEquals(1, result.statistics().localBlocks());
		Assert.assertEquals(1, result.statistics().localBlockImprovements());
	}

	@Test
	public void factorwiseMinimumSkipsAProvablyNonImprovingExactBlock() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			List.of(x, y), List.of(), List.of(
				Factor.dense(List.of(x), 0d, 2d),
				Factor.dense(List.of(y), 0d, 3d),
				Factor.dense(List.of(x, y), 0d, 4d, 5d, 6d)),
			List.of(x, y), List.of(List.of(x, y)), (v, value) -> value);

		Assert.assertEquals(List.of(0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(1, result.statistics().factorwiseMinimumSkips());
		Assert.assertEquals(0, result.statistics().factorizedBlockCompilations());
		Assert.assertEquals(0L, result.statistics().blockAssignments());
	}

	@Test
	public void deferredBlockIsDerivedFromTheCurrentFixedPoint() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 4d),
			Factor.dense(List.of(x, a), 5d, 7d, 8d, 0d),
			Factor.dense(List.of(x, b), 5d, 7d, 8d, 0d));
		AtomicInteger providerCalls = new AtomicInteger();

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables, List.of(), assignment -> {
				providerCalls.incrementAndGet();
				return assignment.equals(List.of(0, 0, 0))
					? List.of(List.of(x, a, b)) : List.of();
			}, (v, value) -> value);

		Assert.assertEquals("the deferred neighborhood must cross the local cost barrier",
			List.of(1, 1, 1), result.assignmentInVariableOrder());
		Assert.assertEquals(4d, result.objective(), 0d);
		Assert.assertEquals(1, result.statistics().localBlocks());
		Assert.assertEquals(1, result.statistics().localBlockImprovements());
		Assert.assertEquals("the provider must be reevaluated after its block changes the plan",
			2, providerCalls.get());
	}

	@Test
	public void initialDeferredSupersetRetiresContainedBlockBeforeSolving() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 4d),
			Factor.dense(List.of(x, a), 5d, 7d, 8d, 0d),
			Factor.dense(List.of(x, b), 5d, 7d, 8d, 0d));

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables, List.of(List.of(x, a)),
			ignored -> List.of(List.of(x, a, b)), (v, value) -> value);

		Assert.assertEquals(List.of(1, 1, 1), result.assignmentInVariableOrder());
		Assert.assertEquals(4d, result.objective(), 0d);
		Assert.assertEquals("the deferred superset must retire its contained ordinary block",
			1, result.statistics().localBlocks());
		Assert.assertEquals("only the maximal block should require an exact solve",
			1, result.statistics().factorizedBlockCompilations());
	}

	@Test
	public void callerOwnedProducerChainBlocksAreNotTransitivelyMerged() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		Variable c = new Variable("c", 2);
		Variable d = new Variable("d", 2);
		List<Variable> variables = List.of(x, a, b, c, d);
		List<Factor> costs = variables.stream()
			.map(variable -> Factor.dense(List.of(variable), 0d, 1d)).toList();

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a, b), List.of(a, c, d)), (v, value) -> value);

		Assert.assertEquals(List.of(0, 0, 0, 0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(2, result.statistics().localBlocks());
	}

	@Test
	public void exactSupersetBlockRetiresContainedExactSubproblem() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 4d),
			Factor.dense(List.of(x, a), 5d, 7d, 8d, 0d),
			Factor.dense(List.of(x, b), 5d, 7d, 8d, 0d));

		LocalCategoricalOptimizer.Result maximalOnly = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables, List.of(List.of(x, a, b)),
			(v, value) -> value);
		LocalCategoricalOptimizer.Result withContained = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a), List.of(x, a, b)), (v, value) -> value);
		LocalCategoricalOptimizer.Result withContainedAfter = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a, b), List.of(x, a)), (v, value) -> value);

		Assert.assertEquals(maximalOnly.assignmentInVariableOrder(),
			withContained.assignmentInVariableOrder());
		Assert.assertEquals(maximalOnly.objective(), withContained.objective(), 0d);
		Assert.assertEquals("only the exact maximal neighborhood remains active",
			1, withContained.statistics().localBlocks());
		Assert.assertEquals("the contained exact subproblem must not be solved",
			maximalOnly.statistics().blockAssignments(),
			withContained.statistics().blockAssignments());
		Assert.assertEquals(maximalOnly.assignmentInVariableOrder(),
			withContainedAfter.assignmentInVariableOrder());
		Assert.assertEquals(maximalOnly.statistics(), withContainedAfter.statistics());
	}

	@Test
	public void overlappingLocalBlocksAreSolvedOnceInCallerOrder() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 1d),
			Factor.dense(List.of(x, a), 0d, 4d, 4d, 0d),
			Factor.dense(List.of(a, b), 10d, 10d, 10d, 0d));

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a), List.of(a, b)), (v, value) -> value);

		Assert.assertEquals(List.of(0, 1, 1), result.assignmentInVariableOrder());
		Assert.assertEquals(4d, result.objective(), 0d);
		Assert.assertEquals(1, result.statistics().localBlockImprovements());
		Assert.assertEquals(0, result.statistics().localBlockRevisits());
		Assert.assertTrue("factorwise proof should avoid the initial non-improving solve",
			result.statistics().factorwiseMinimumSkips() > 0);
		Assert.assertTrue(result.statistics().factorizedBlockCompilations()
			<= result.statistics().localBlocks());
	}

	@Test
	public void deferredBlockDoesNotRestartCompletedLocalPass() {
		Variable x = new Variable("x", 2);
		Variable w = new Variable("w", 2);
		Variable y = new Variable("y", 2);
		Variable z = new Variable("z", 2);
		List<Variable> variables = List.of(x, w, y, z);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x, y), 0d, 4d, 4d, 0d),
			Factor.dense(List.of(x, w), 10d, 10d, 0d, 0d),
			Factor.dense(List.of(y, z), 0d, 10d, 10d, 0d));

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(y, z)), ignored -> List.of(List.of(x, w)),
			(v, value) -> value);

		Assert.assertEquals(List.of(1, 0, 0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(4d, result.objective(), 0d);
		Assert.assertEquals(2, result.statistics().localBlocks());
		Assert.assertEquals(1, result.statistics().localBlockImprovements());
		Assert.assertEquals(0, result.statistics().localBlockRevisits());
	}
}
