/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
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
		LocalCategoricalOptimizer.Result normalized = LocalCategoricalOptimizer.optimize(
			variables, hard, cost, variables, List.of(),
			(v, value) -> NormalizedText.literal(Integer.toString(value)));

		Assert.assertEquals(List.of(1, 0, 1), result.assignmentInVariableOrder());
		Assert.assertTrue(result.statistics().initialHardViolations() > 0);
		Assert.assertEquals(0, result.statistics().finalHardViolations());
		Assert.assertTrue(result.statistics().conflictBlocksSolved() > 0);
		Assert.assertEquals(2d, result.objective(), 0d);
		Assert.assertEquals("normalized state keys changed repaired assignment",
			result.assignmentInVariableOrder(), normalized.assignmentInVariableOrder());
		Assert.assertEquals("normalized state keys changed repaired objective bits",
			Double.doubleToRawLongBits(result.objective()),
			Double.doubleToRawLongBits(normalized.objective()));
		Assert.assertEquals("normalized state keys changed seed/repair trace",
			withoutTimings(result.statistics()), withoutTimings(normalized.statistics()));
	}

	@Test
	public void sharedSupportRestrictsRepairWithoutChangingSeedOrDroppingEquivalentValues() {
		for(boolean compact : new boolean[] {false, true}) {
			Variable x = new Variable("supported-x", 4);
			Variable y = new Variable("supported-y", 3);
			Variable free = new Variable("supported-free", 2);
			List<Variable> variables = List.of(x, y, free);
			List<Factor> hard = List.of(
				Factor.dense(List.of(y), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0d),
				Factor.lazy(List.of(x, y), v -> (v[0] % 2 == 1 && v[1] == 2)
					|| (v[0] % 2 == 0 && v[1] == 0) ? 0d : Double.POSITIVE_INFINITY));
			AtomicInteger impossibleCostEvaluations = new AtomicInteger();
			List<Factor> cost = List.of(Factor.lazy(List.of(x), v -> {
				if(v[0] % 2 == 0)
					impossibleCostEvaluations.incrementAndGet();
				return v[0] % 2;
			}), Factor.dense(List.of(free), 0d, 2d));
			List<Factor> factors = new ArrayList<>(hard);
			factors.addAll(cost);
			RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
			var root = problem.reducedRoot(ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			Assert.assertEquals(root.reducedValue(0, 1), root.reducedValue(0, 3));
			var oracle = ExactCategoricalSolver.solve(variables, factors,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			SharedRegionalPreparation shared = new SharedRegionalPreparation(problem,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS, compact);
			impossibleCostEvaluations.set(0);
			// A method reference exposes preparation without the shared-domain API.
			var broader = LocalCategoricalOptimizer.optimize(
				variables, hard, cost, variables, List.of(List.of(x, free)), ignored -> List.of(),
				(v, value) -> value, 0, compact, shared::prepare);
			int broaderImpossibleEvaluations = impossibleCostEvaluations.get();
			impossibleCostEvaluations.set(0);

			LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
				variables, hard, cost, variables, List.of(List.of(x, free)), ignored -> List.of(),
				(v, value) -> value, 0, compact, shared);

			Assert.assertEquals(oracle.objective(), result.objective(), 0d);
			Assert.assertEquals(List.of(1, 2, 0), result.assignmentInVariableOrder());
			Assert.assertEquals(broader.assignmentInVariableOrder(), result.assignmentInVariableOrder());
			Assert.assertArrayEquals(new int[] {1, 3}, shared.unconditionalDomains(variables)[0]);
			Assert.assertEquals("preserve the original greedy seed search", 9L,
				result.statistics().rawLocalAlternatives());
			Assert.assertEquals("only the seed may evaluate unsupported x=0 and x=2", 2,
				impossibleCostEvaluations.get());
			Assert.assertTrue(broaderImpossibleEvaluations > impossibleCostEvaluations.get());
			Assert.assertTrue("shared support should avoid unnecessary block assignments",
				result.statistics().blockAssignments() < broader.statistics().blockAssignments());
			Assert.assertEquals(broader.statistics().initialHardViolations(),
				result.statistics().initialHardViolations());
			Assert.assertTrue(result.statistics().initialHardViolations() > 0);
		}
	}

	@Test
	public void originalFactorRetryKeepsSharedSupportAndProjectsOriginalValues() {
		for(boolean compact : new boolean[] {false, true}) {
			Variable x = new Variable("retry-x", 4);
			Variable a = new Variable("retry-a", 2);
			Variable b = new Variable("retry-b", 2);
			List<Variable> variables = List.of(x, a, b);
			List<Factor> hard = List.of(
				Factor.dense(List.of(x), Double.POSITIVE_INFINITY, 0d, Double.POSITIVE_INFINITY, 0d),
				Factor.lazy(List.of(x, b), v -> (v[0] == 1 && v[1] == 0)
					|| (v[0] == 3 && v[1] == 1) ? 0d : Double.POSITIVE_INFINITY),
				Factor.lazy(List.of(a, b), v -> v[0] != v[1] ? 0d : Double.POSITIVE_INFINITY));
			List<Factor> cost = List.of(Factor.dense(List.of(x), 0d, 0d, 0d, 2d),
				Factor.dense(List.of(x, a), 0d, 0d, 0d, 10d, 0d, 0d, 0d, 0d));
			List<Factor> factors = new ArrayList<>(hard);
			factors.addAll(cost);
			SharedRegionalPreparation shared = new SharedRegionalPreparation(
				RegionalSearchProblem.generic(variables, factors),
				ExactPhysicalOptimizer.PRODUCTION_LIMITS, compact);
			// Model the existing resource retry: root support remains available, but
			// the block must be prepared from original factors with smaller domains.
			LocalCategoricalOptimizer.BlockPreparation retry = new LocalCategoricalOptimizer.BlockPreparation() {
				@Override
				public int[][] unconditionalDomains(List<Variable> originals) {
					return shared.unconditionalDomains(originals);
				}
				@Override
				public LocalCategoricalOptimizer.PreparedBlockSolver prepare(int[] assignment, int[] block) {
					return null;
				}
			};
			var result = LocalCategoricalOptimizer.optimize(variables, hard, cost, variables,
				List.of(), ignored -> List.of(), (v, value) -> value, 0, compact, retry);
			var oracle = ExactCategoricalSolver.solve(variables, factors,
				ExactPhysicalOptimizer.PRODUCTION_LIMITS);
			Assert.assertEquals(List.of(3, 0, 1), result.assignmentInVariableOrder());
			Assert.assertEquals(oracle.objective(), result.objective(), 0d);
			Assert.assertTrue(result.statistics().initialHardViolations() > 0);
			Assert.assertEquals(0, result.statistics().finalHardViolations());
		}
	}

	@Test
	public void hardRepairReleasesUnsupportedCostBoundaryWithoutCanonicalFallback() {
		for(boolean compact : new boolean[] {false, true}) {
			Variable x = new Variable("repair-x", 2);
			Variable a = new Variable("repair-a", 2);
			Variable b = new Variable("repair-b", 2);
			Variable z = new Variable("repair-z", 2);
			Variable c = new Variable("repair-c", 2);
			Variable d = new Variable("repair-d", 2);
			List<Variable> variables = List.of(x, a, b, z, c, d);
			List<Factor> hard = List.of(
				Factor.lazy(List.of(x, b), v -> v[0] == v[1] ? 0d : Double.POSITIVE_INFINITY),
				Factor.lazy(List.of(a, b), v -> v[0] != v[1] ? 0d : Double.POSITIVE_INFINITY),
				Factor.lazy(List.of(z, d), v -> v[0] == v[1] ? 0d : Double.POSITIVE_INFINITY),
				Factor.dense(List.of(d), Double.POSITIVE_INFINITY, 0d),
				Factor.lazy(List.of(c, d), v -> v[0] != v[1] ? 0d : Double.POSITIVE_INFINITY));
			List<Factor> cost = List.of(
				Factor.dense(List.of(x, z), 0d, 4d, 2d, 6d),
				Factor.dense(List.of(x, a), 0d, 10d, 0d, 0d));
			List<Factor> factors = new ArrayList<>(hard);
			factors.addAll(cost);
			SharedRegionalPreparation shared = new SharedRegionalPreparation(
				RegionalSearchProblem.generic(variables, factors),
				ExactPhysicalOptimizer.PRODUCTION_LIMITS, compact);

			// The greedy pass creates two hard-conflict components. Repairing x/a/b
			// sees z=0 through a cost factor, although global support has removed z=0.
			// Release z and include its hard relationship to d before solving the block.
			LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
				variables, hard, cost, variables, List.of(), ignored -> List.of(),
				(v, value) -> value, 0, compact, shared);
			ExactCategoricalSolver.Result oracle = ExactCategoricalSolver.solve(
				variables, factors, ExactPhysicalOptimizer.PRODUCTION_LIMITS);

			Assert.assertEquals(List.of(1, 0, 1, 1, 0, 1), result.assignmentInVariableOrder());
			Assert.assertEquals(oracle.objective(), result.objective(), 0d);
			Assert.assertTrue(result.statistics().initialHardViolations() > 0);
			Assert.assertEquals(0, result.statistics().finalHardViolations());
			Assert.assertTrue(result.statistics().conflictBlockExpansions() > 0);
			Assert.assertEquals("unsupported boundary discarded shared factorization", 0L, shared.fallbacks());
		}
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
		Assert.assertTrue(result.statistics().totalOptimizationNanos() > 0L);
		Assert.assertTrue(result.statistics().exactBlockPreparationNanos() > 0L);
		Assert.assertTrue(result.statistics().exactBlockSolveNanos() > 0L);
		Assert.assertTrue(result.statistics().totalOptimizationNanos()
			>= result.statistics().exactBlockPreparationNanos()
				+ result.statistics().exactBlockSolveNanos());
	}

	@Test
	public void legacyStatisticsConstructorDefaultsTimingFieldsToZero() {
		LocalCategoricalOptimizer.Statistics statistics =
			new LocalCategoricalOptimizer.Statistics(0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, 0, 0, 0);

		Assert.assertEquals(0L, statistics.totalOptimizationNanos());
		Assert.assertEquals(0L, statistics.exactBlockPreparationNanos());
		Assert.assertEquals(0L, statistics.exactBlockSolveNanos());
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
	public void validatedDenseZeroMinimumAvoidsFactorAssignmentEnumeration() {
		Variable x = new Variable("x", 5);
		Variable y = new Variable("y", 5);
		double[] costs = new double[25];
		Arrays.fill(costs, 2d);
		costs[0] = 0d;
		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			List.of(x, y), List.of(), List.of(Factor.dense(List.of(x, y), costs)),
			List.of(x, y), List.of(List.of(x, y)), (v, value) -> value);

		Assert.assertEquals(List.of(0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(0d, result.objective(), 0d);
		Assert.assertEquals(1, result.statistics().factorwiseMinimumSkips());
		Assert.assertEquals("zero is a proven floor after validating the dense table",
			0L, result.statistics().factorwiseMinimumAssignments());
		Assert.assertEquals(0L, result.statistics().blockAssignments());
	}

	@Test
	public void lazyZeroFactorStillEnumeratesAndRejectsHiddenNegativeCost() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		AtomicInteger evaluations = new AtomicInteger();
		Factor untrusted = Factor.lazy(List.of(x, y), values -> {
			evaluations.incrementAndGet();
			return values[0] == 1 && values[1] == 1 ? -1d : 0d;
		});

		IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
			() -> LocalCategoricalOptimizer.optimize(List.of(x, y), List.of(), List.of(untrusted),
				List.of(x, y), List.of(List.of(x, y)), (v, value) -> value));
		Assert.assertTrue(error.getMessage().startsWith("LOCAL_COST_FACTOR_INVALID|"));
		Assert.assertTrue("the untrusted lazy alternative must still be evaluated",
			evaluations.get() >= 4);
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
		Assert.assertEquals(withoutTimings(maximalOnly.statistics()),
			withoutTimings(withContainedAfter.statistics()));
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
	public void explicitRevisitImprovesAnOverlappingChain() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 1d),
			Factor.dense(List.of(x, a), 0d, 4d, 4d, 0d),
			Factor.dense(List.of(a, b), 10d, 10d, 10d, 0d));

		LocalCategoricalOptimizer.Result legacy = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a), List.of(a, b)), (v, value) -> value);
		LocalCategoricalOptimizer.Result revisited = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a), List.of(a, b)), (v, value) -> value, 2);

		Assert.assertEquals("the legacy overload remains a single block pass",
			List.of(0, 1, 1), legacy.assignmentInVariableOrder());
		Assert.assertEquals(4d, legacy.objective(), 0d);
		Assert.assertEquals(List.of(1, 1, 1), revisited.assignmentInVariableOrder());
		Assert.assertEquals(1d, revisited.objective(), 0d);
		Assert.assertEquals(2, revisited.statistics().localBlockRevisits());
	}

	@Test
	public void factorDependencyInvalidatesDisjointBlocks() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		Variable c = new Variable("c", 2);
		List<Variable> variables = List.of(x, a, b, c);
		List<Factor> hard = List.of(Factor.lazy(List.of(b, c), values ->
			values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY));
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 1d),
			Factor.dense(List.of(x, a), 0d, 4d, 4d, 0d),
			Factor.dense(List.of(a, b), 0d, 4d, 4d, 0d),
			Factor.dense(List.of(b, c), 10d, 10d, 10d, 0d));

		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			variables, hard, costs, variables,
			List.of(List.of(x, a), List.of(b, c)), (v, value) -> value, 1);

		Assert.assertEquals("the a-b factor must dirty the disjoint x-a block",
			List.of(1, 1, 1, 1), result.assignmentInVariableOrder());
		Assert.assertEquals(1d, result.objective(), 0d);
		Assert.assertEquals(0, result.statistics().finalHardViolations());
		Assert.assertTrue(result.statistics().localBlockRevisits() > 0);
	}

	@Test
	public void unchangedBoundariesAvoidRevisitExactWork() {
		Variable x = new Variable("x", 2);
		Variable y = new Variable("y", 2);
		LocalCategoricalOptimizer.Result result = LocalCategoricalOptimizer.optimize(
			List.of(x, y), List.of(), List.of(
				Factor.dense(List.of(x), 0d, 2d),
				Factor.dense(List.of(y), 0d, 3d),
				Factor.dense(List.of(x, y), 0d, 4d, 5d, 6d)),
			List.of(x, y), List.of(List.of(x, y)), (v, value) -> value, 2);

		Assert.assertEquals(List.of(0, 0), result.assignmentInVariableOrder());
		Assert.assertEquals(0, result.statistics().localBlockRevisits());
		Assert.assertEquals(0, result.statistics().factorizedBlockCompilations());
		Assert.assertEquals(0L, result.statistics().blockAssignments());
	}

	@Test
	public void revisitPassCapBoundsDirtyBlockWork() {
		Variable x = new Variable("x", 2);
		Variable a = new Variable("a", 2);
		Variable b = new Variable("b", 2);
		List<Variable> variables = List.of(x, a, b);
		List<Factor> costs = List.of(
			Factor.dense(List.of(x), 0d, 1d),
			Factor.dense(List.of(x, a), 0d, 4d, 4d, 0d),
			Factor.dense(List.of(a, b), 10d, 10d, 10d, 0d));

		LocalCategoricalOptimizer.Result capped = LocalCategoricalOptimizer.optimize(
			variables, List.of(), costs, variables,
			List.of(List.of(x, a), List.of(a, b)), (v, value) -> value, 1);

		Assert.assertTrue(capped.statistics().localBlockRevisits()
			<= capped.statistics().localBlocks());
		Assert.assertEquals(1d, capped.objective(), 0d);
		Assert.assertThrows(IllegalArgumentException.class, () ->
			LocalCategoricalOptimizer.optimize(variables, List.of(), costs, variables,
				List.of(List.of(x, a)), (v, value) -> value, 17));
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

	private static LocalCategoricalOptimizer.Statistics withoutTimings(
		LocalCategoricalOptimizer.Statistics statistics) {
		return new LocalCategoricalOptimizer.Statistics(statistics.rawLocalAlternatives(),
			statistics.retainedLocalStates(), statistics.prunedLocalRepresentatives(),
			statistics.initialHardViolations(), statistics.finalHardViolations(),
			statistics.conflictBlocksSolved(), statistics.conflictBlockExpansions(),
			statistics.localBlocks(), statistics.localBlockImprovements(),
			statistics.localBlockRevisits(), statistics.factorizedBlockCompilations(),
			statistics.factorizedBlockSolves(), statistics.factorwiseMinimumSkips(),
			statistics.maximumBlockVariables(), statistics.maximumBlockAssignments(),
			statistics.blockAssignments());
	}
}
