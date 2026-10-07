/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Assert;
import org.junit.Test;

public class IncrementalConditionalReplayTest {
	private static final Limits LIMITS = new Limits(100_000, 1_000_000);

	@Test
	public void successfulBlockIsReplayedAfterCoverRelease() {
		Variable x = new Variable("conditional-x", 2);
		Variable y = new Variable("conditional-y", 2);
		List<Variable> variables = List.of(x, y);
		List<Factor> factors = List.of(
			Factor.dense(List.of(x, y), 50, 50, 50, 0),
			Factor.dense(List.of(x, y), 50, 50, 50, 0));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);

		IncrementalRegionalOptimizer.Result result = IncrementalRegionalOptimizer.optimize(
			problem, problem.reducedRoot(LIMITS), List.of(0, 0), LIMITS,
			new IncrementalRegionalOptimizer.Options(0, 100, 4, 0, 16, false), ignored -> { });

		Assert.assertEquals("RESOURCE", result.stopReason());
		Assert.assertEquals(List.of(1, 1), result.assignment());
		Assert.assertEquals(0d, result.lower(), 0d);
		Assert.assertEquals(0d, result.upper(), 0d);
		Assert.assertEquals(0d,
			RegionalSearchProblem.evaluateFactors(variables, factors, result.assignment()), 0d);
		Assert.assertEquals("the repeated post-cover block must not run exact solve again", 1,
			result.checkpoints().get(result.checkpoints().size() - 1).conditionalAttempts());
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(checkpoint -> checkpoint.phase().equals("RESOURCE_COVER")));
		Assert.assertTrue(result.checkpoints().stream()
			.anyMatch(checkpoint -> checkpoint.phase().equals("CONDITIONAL")));
	}

	@Test
	public void replayIdentityIgnoresBlockValuesButMatchesEveryOutsideValue() {
		IncrementalRegionalOptimizer.ConditionalReplayCache cache =
			new IncrementalRegionalOptimizer.ConditionalReplayCache();
		int[] source = {1, 4, 2, 7};
		int[] block = {2, 0};
		List<Integer> solved = new ArrayList<>(List.of(9, 8));
		cache.rememberSuccessful(source, block, solved);

		source[1] = 99;
		block[0] = 3;
		solved.set(0, 77);
		int[] sameOutside = {6, 4, 5, 7};
		Assert.assertTrue(cache.applyIfPresent(sameOutside, new int[] {0, 2}));
		Assert.assertArrayEquals(new int[] {8, 4, 9, 7}, sameOutside);

		for(int outside : new int[] {1, 3}) {
			int[] changedOutside = {6, 4, 5, 7};
			changedOutside[outside]++;
			int[] before = changedOutside.clone();
			Assert.assertFalse(cache.applyIfPresent(changedOutside, new int[] {2, 0}));
			Assert.assertArrayEquals("a miss must not partially apply cached values", before, changedOutside);
		}
		Assert.assertEquals(1, cache.size());
	}

	@Test
	public void newerOutsideStateReplacesTheBlocksSingleEntry() {
		IncrementalRegionalOptimizer.ConditionalReplayCache cache =
			new IncrementalRegionalOptimizer.ConditionalReplayCache();
		cache.rememberSuccessful(new int[] {0, 1, 2}, new int[] {1}, List.of(7));
		cache.rememberSuccessful(new int[] {0, 1, 3}, new int[] {1}, List.of(8));

		Assert.assertEquals(1, cache.size());
		Assert.assertFalse(cache.applyIfPresent(new int[] {0, 9, 2}, new int[] {1}));
		int[] latest = {0, 9, 3};
		Assert.assertTrue(cache.applyIfPresent(latest, new int[] {1}));
		Assert.assertArrayEquals(new int[] {0, 8, 3}, latest);
	}

	@Test
	public void actualMergeAllocationFailureReleasesConditionalReplay() throws Exception {
		Variable x = new Variable("release-x", 2), y = new Variable("release-y", 2),
			z = new Variable("release-z", 2);
		List<Variable> variables = List.of(x, y, z);
		List<Factor> factors = List.of(Factor.dense(List.of(x, y), 9, 3, 4, 1),
			Factor.dense(List.of(y, z), 7, 2, 5, 1), Factor.dense(List.of(x, z), 8, 6, 2, 1));
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		IncrementalRegionalOptimizer optimizer = optimizer(problem, problem.reducedRoot(LIMITS),
			List.of(0, 0, 0), (messages, boundary, limits, maximumAssignments, counters) -> {
				if(attempts.incrementAndGet() == 2)
					throw PlannerResourceGuard.allocationFailure("conditional-release", -1L,
						"injected-merge", new OutOfMemoryError("injected merge allocation failure"));
				return ExactCategoricalSolver.mergeBoundary(messages, boundary, limits, counters);
			});
		Field cacheField = IncrementalRegionalOptimizer.class.getDeclaredField("conditionalReplayCache");
		cacheField.setAccessible(true);
		IncrementalRegionalOptimizer.ConditionalReplayCache cache =
			(IncrementalRegionalOptimizer.ConditionalReplayCache)cacheField.get(optimizer);
		cache.rememberSuccessful(new int[] {1, 1, 1}, new int[] {0}, List.of(1));
		Assert.assertEquals(1, cache.size());
		Method run = IncrementalRegionalOptimizer.class.getDeclaredMethod("run");
		run.setAccessible(true);
		IncrementalRegionalOptimizer.Result result = (IncrementalRegionalOptimizer.Result)run.invoke(optimizer);
		Assert.assertEquals("RESOURCE", result.stopReason());
		Assert.assertEquals(2, attempts.get());
		Assert.assertEquals(0, cache.size());
		Assert.assertEquals(result.upper(), RegionalSearchProblem.evaluateFactors(
			variables, factors, result.assignment()), 0d);
	}

	@Test
	public void mappedWitnessSkipsOnlyCanonicalNonImprovementAndNeverChangesIncumbent()
		throws Exception {
		Variable decision = new Variable("mapped-decision", 2);
		Variable auxiliary = new Variable("mapped-auxiliary", 3);
		List<Variable> variables = List.of(decision, auxiliary);
		List<Factor> factors = List.of(
			Factor.dense(List.of(decision), 5d, 0d),
			Factor.dense(List.of(auxiliary), 0d, 0d, 1d));
		RegionalSearchProblem problem = new RegionalSearchProblem(variables, factors, 1,
			assignment -> assignment.get(0) == 0 ? 5d : 0d);
		ExactPhysicalReducedSolver.CompactModel root = problem.reducedRoot(LIMITS);
		IncrementalRegionalOptimizer optimizer = optimizer(problem, root, List.of(0));
		Method method = IncrementalRegionalOptimizer.class.getDeclaredMethod(
			"mappedWitnessProvesNoImprovement", int[].class);
		method.setAccessible(true);

		Assert.assertTrue((boolean)method.invoke(optimizer, (Object)new int[] {0, 1}));
		Assert.assertFalse("a strict source improvement must retain legacy lifting",
			(boolean)method.invoke(optimizer, (Object)new int[] {1, 0}));
		Assert.assertFalse("a noncanonical auxiliary witness must fall back",
			(boolean)method.invoke(optimizer, (Object)new int[] {0, 2}));
		Field incumbent = IncrementalRegionalOptimizer.class.getDeclaredField("incumbent");
		incumbent.setAccessible(true);
		Assert.assertArrayEquals("witness validation must not publish auxiliary tie choices",
			new int[] {0, 0}, (int[])incumbent.get(optimizer));

		Field lower = IncrementalRegionalOptimizer.class.getDeclaredField("lower");
		lower.setAccessible(true);
		lower.setDouble(optimizer, 6d);
		InvocationTargetException below = Assert.assertThrows(InvocationTargetException.class,
			() -> method.invoke(optimizer, (Object)new int[] {0, 1}));
		Assert.assertEquals("INCREMENTAL_CANDIDATE_BELOW_PUBLISHED_LOWER",
			below.getCause().getMessage());
	}

	private static IncrementalRegionalOptimizer optimizer(RegionalSearchProblem problem,
		ExactPhysicalReducedSolver.CompactModel root, List<Integer> seed) throws Exception {
		Field merger = IncrementalRegionalOptimizer.class.getDeclaredField("DEFAULT_BOUNDARY_MERGER");
		merger.setAccessible(true);
		return optimizer(problem, root, seed, (IncrementalRegionalOptimizer.BoundaryMerger)merger.get(null));
	}

	private static IncrementalRegionalOptimizer optimizer(RegionalSearchProblem problem,
		ExactPhysicalReducedSolver.CompactModel root, List<Integer> seed,
		IncrementalRegionalOptimizer.BoundaryMerger merger) throws Exception {
		Constructor<IncrementalRegionalOptimizer> constructor =
			IncrementalRegionalOptimizer.class.getDeclaredConstructor(RegionalSearchProblem.class,
				ExactPhysicalReducedSolver.CompactModel.class, List.class, Limits.class,
				IncrementalRegionalOptimizer.Options.class, Consumer.class,
				ExactCategoricalSolver.BoundaryMergeCounters.class,
				IncrementalRegionalOptimizer.BoundaryMerger.class);
		constructor.setAccessible(true);
		return constructor.newInstance(problem, root, seed, LIMITS,
			new IncrementalRegionalOptimizer.Options(0d, 100, 1000, 0, 4, false),
			(Consumer<IncrementalRegionalOptimizer.Checkpoint>)ignored -> { }, null, merger);
	}
}
