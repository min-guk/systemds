/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Limits;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.junit.Test;

public class IncrementalConflictScoreCacheTest {
	private static final Limits LIMITS = new Limits(1_000_000, 10_000_000);

	@Test
	public void disconnectedFanoutReusesOnlyUnchangedCandidateScores() throws Exception {
		Random random = new Random(0x5ca1eL);
		List<Variable> variables = new ArrayList<>();
		List<Factor> factors = new ArrayList<>();
		for(int component = 0; component < 8; component++) {
			Variable a = new Variable("cache-a-" + component, 3);
			Variable b = new Variable("cache-b-" + component, 3);
			Variable c = new Variable("cache-c-" + component, 3);
			variables.add(a);
			variables.add(b);
			variables.add(c);
			factors.add(randomPair(random, a, b));
			factors.add(randomPair(random, b, c));
			factors.add(randomPair(random, a, c));
			// Distinct unary offsets make the complete optimum deterministic.
			factors.add(Factor.dense(List.of(a), component * 11 + 1, component * 11 + 4,
				component * 11 + 9));
			factors.add(Factor.dense(List.of(b), component * 13 + 2, component * 13 + 7,
				component * 13 + 15));
			factors.add(Factor.dense(List.of(c), component * 17 + 3, component * 17 + 8,
				component * 17 + 19));
		}
		List<Integer> seed = java.util.Collections.nCopies(variables.size(), 2);
		ExactCategoricalSolver.Result reference = ExactCategoricalSolver.solve(variables, factors, LIMITS);
		IncrementalRegionalOptimizer optimizer = optimizer(variables, factors, seed,
			new IncrementalRegionalOptimizer.Options(0d, 0L, 0L, 0L, 0, false));
		IncrementalRegionalOptimizer.Result actual = run(optimizer);

		assertEquals("EXACT", actual.stopReason());
		assertEquals(reference.objective(), actual.upper(), 0d);
		assertEquals(reference.assignmentInVariableOrder(), actual.assignment());
		assertTrue("unchanged disconnected candidates must hit the score cache",
			optimizer.conflictScoreCacheHitsForTest() > 0);
		long scoreRequests = optimizer.conflictScoreEvaluationsForTest()
			+ optimizer.conflictScoreCacheHitsForTest();
		assertTrue("the cache must remove a material fraction of repeated scoring",
			optimizer.conflictScoreEvaluationsForTest() * 2 < scoreRequests);
	}

	@Test
	public void boundedMarginalReleaseKeepsExactResourceBehavior() throws Exception {
		Variable a = new Variable("bounded-a", 3);
		Variable b = new Variable("bounded-b", 3);
		Variable c = new Variable("bounded-c", 3);
		Variable d = new Variable("bounded-d", 3);
		List<Variable> variables = List.of(a, b, c, d);
		List<Factor> factors = List.of(
			Factor.dense(List.of(a, b), 8, 5, 7, 4, 3, 6, 9, 2, 1),
			Factor.dense(List.of(b, c), 7, 4, 8, 3, 2, 5, 9, 6, 1),
			Factor.dense(List.of(c, d), 9, 6, 4, 8, 5, 3, 7, 2, 1),
			Factor.dense(List.of(a, d), 6, 5, 9, 4, 3, 8, 7, 2, 1));
		ExactCategoricalSolver.Result reference = ExactCategoricalSolver.solve(variables, factors, LIMITS);
		IncrementalRegionalOptimizer optimizer = optimizer(variables, factors, List.of(0, 0, 0, 0),
			new IncrementalRegionalOptimizer.Options(0d, 100_000L, 5L, 0L, 16, false));
		IncrementalRegionalOptimizer.Result actual = run(optimizer);

		assertTrue(actual.stopReason().equals("EXACT") || actual.stopReason().equals("RESOURCE"));
		assertTrue(actual.lower() <= reference.objective());
		assertTrue(actual.upper() >= reference.objective());
		assertTrue("fixture must exercise bounded marginal release or rejection",
			actual.checkpoints().stream().anyMatch(checkpoint -> checkpoint.resourceRejected() > 0));
		assertTrue("an incomplete resource-limited score must remain uncached",
			optimizer.incompleteConflictScoresForTest() > 0);
		assertEquals(actual.upper(), RegionalSearchProblem.evaluateFactors(
			variables, factors, actual.assignment()), 0d);
	}

	@Test
	public void incompleteScoreRetriesSameCandidateThenCachesOnlyCompletion() throws Exception {
		Variable pivot = new Variable("retry-pivot", 3);
		Variable left = new Variable("retry-left", 3);
		Variable right = new Variable("retry-right", 3);
		List<Variable> variables = List.of(pivot, left, right);
		List<Factor> factors = List.of(
			Factor.dense(List.of(pivot, left), 9, 3, 8, 6, 2, 7, 5, 1, 4),
			Factor.dense(List.of(pivot, right), 8, 5, 2, 7, 4, 1, 9, 6, 3));
		IncrementalRegionalOptimizer optimizer = optimizer(variables, factors, List.of(0, 0, 0),
			new IncrementalRegionalOptimizer.Options(0d, 100_000L, 100L, 0L, 16, false));
		CandidateFixture fixture = initializeCandidate(optimizer, pivot);
		Object candidate = fixture.candidate();
		Method score = conflictScoreMethod(candidate);

		setLong(optimizer, "slots", 100L);
		assertEquals(0d, (double)score.invoke(optimizer, candidate), 0d);
		assertEquals(0d, (double)score.invoke(optimizer, candidate), 0d);
		assertEquals("an incomplete score must be evaluated again for the same candidate",
			2L, optimizer.conflictScoreEvaluationsForTest());
		assertEquals(0L, optimizer.conflictScoreCacheHitsForTest());
		assertEquals(2L, optimizer.incompleteConflictScoresForTest());
		assertEquals(0, conflictCache(optimizer).size());

		setLong(optimizer, "slots", 0L);
		double completed = (double)score.invoke(optimizer, candidate);
		assertTrue(Double.isFinite(completed));
		assertEquals(completed, (double)score.invoke(optimizer, candidate), 0d);
		assertEquals(3L, optimizer.conflictScoreEvaluationsForTest());
		assertEquals(1L, optimizer.conflictScoreCacheHitsForTest());
		assertEquals(1, conflictCache(optimizer).size());
	}

	@Test
	public void refreshAndDiscardEvictOnlyTheScoredCandidate() throws Exception {
		Variable pivot = new Variable("evict-pivot", 3);
		Variable left = new Variable("evict-left", 3);
		Variable right = new Variable("evict-right", 3);
		List<Variable> variables = List.of(pivot, left, right);
		List<Factor> factors = List.of(
			Factor.dense(List.of(pivot, left), 6, 4, 8, 3, 2, 7, 9, 5, 1),
			Factor.dense(List.of(pivot, right), 7, 3, 9, 5, 1, 8, 6, 2, 4));
		IncrementalRegionalOptimizer optimizer = optimizer(variables, factors, List.of(0, 0, 0),
			new IncrementalRegionalOptimizer.Options(0d, 100_000L, 1_000L, 0L, 16, false));
		CandidateFixture fixture = initializeCandidate(optimizer, pivot);
		pivot = fixture.pivot();
		Object first = fixture.candidate();
		Method score = conflictScoreMethod(first);
		score.invoke(optimizer, first);
		assertCacheBoundedByLiveCandidates(optimizer, 1);

		invokeCandidateLifecycle(optimizer, "refresh", pivot);
		Object refreshed = candidates(optimizer).get(pivot);
		assertTrue("refresh must replace the immutable candidate", refreshed != first);
		assertEquals("refresh must evict the discarded candidate score", 0,
			conflictCache(optimizer).size());
		assertCacheBoundedByLiveCandidates(optimizer, 1);

		conflictScoreMethod(refreshed).invoke(optimizer, refreshed);
		assertCacheBoundedByLiveCandidates(optimizer, 1);
		invokeCandidateLifecycle(optimizer, "discard", pivot);
		assertEquals(0, candidates(optimizer).size());
		assertEquals(0, conflictCache(optimizer).size());
		assertCacheBoundedByLiveCandidates(optimizer, 0);
	}

	private static Factor randomPair(Random random, Variable left, Variable right) {
		double[] values = new double[left.domainSize() * right.domainSize()];
		for(int index = 0; index < values.length; index++)
			values[index] = 1 + random.nextInt(100);
		return Factor.dense(List.of(left, right), values);
	}

	private static IncrementalRegionalOptimizer optimizer(List<Variable> variables,
		List<Factor> factors, List<Integer> seed, IncrementalRegionalOptimizer.Options options)
		throws Exception {
		RegionalSearchProblem problem = RegionalSearchProblem.generic(variables, factors);
		Field mergerField = IncrementalRegionalOptimizer.class.getDeclaredField("DEFAULT_BOUNDARY_MERGER");
		mergerField.setAccessible(true);
		Constructor<IncrementalRegionalOptimizer> constructor = IncrementalRegionalOptimizer.class
			.getDeclaredConstructor(RegionalSearchProblem.class, ExactPhysicalReducedSolver.CompactModel.class,
				List.class, Limits.class, IncrementalRegionalOptimizer.Options.class, Consumer.class,
				ExactCategoricalSolver.BoundaryMergeCounters.class,
				IncrementalRegionalOptimizer.BoundaryMerger.class);
		constructor.setAccessible(true);
		return constructor.newInstance(problem, problem.reducedRoot(LIMITS), seed, LIMITS, options,
			(Consumer<IncrementalRegionalOptimizer.Checkpoint>)ignored -> { }, null,
			mergerField.get(null));
	}

	private static IncrementalRegionalOptimizer.Result run(IncrementalRegionalOptimizer optimizer)
		throws Exception {
		Method run = IncrementalRegionalOptimizer.class.getDeclaredMethod("run");
		run.setAccessible(true);
		return (IncrementalRegionalOptimizer.Result)run.invoke(optimizer);
	}

	private record CandidateFixture(Variable pivot, Object candidate) { }

	private static CandidateFixture initializeCandidate(
		IncrementalRegionalOptimizer optimizer, Variable requestedPivot)
		throws Exception {
		Field rootField = IncrementalRegionalOptimizer.class.getDeclaredField("root");
		rootField.setAccessible(true);
		ExactPhysicalReducedSolver.CompactModel root =
			(ExactPhysicalReducedSolver.CompactModel)rootField.get(optimizer);
		Variable pivot = root.variables().stream()
			.filter(variable -> variable.key().endsWith("|" + requestedPivot.key()))
			.findFirst().orElseThrow(() -> new AssertionError(
				"reduced pivot is missing from " + root.variables()));
		List<ExactCategoricalSolver.BoundaryMessage> leaves = ExactCategoricalSolver.boundaryLeaves(
			root.variables(), root.factors(), LIMITS);
		Class<?> nodeClass = Class.forName(IncrementalRegionalOptimizer.class.getName() + "$Node");
		Constructor<?> nodeConstructor = nodeClass.getDeclaredConstructor(
			int.class, int[].class, ExactCategoricalSolver.BoundaryMessage.class);
		nodeConstructor.setAccessible(true);
		Set<Object> members = new LinkedHashSet<>();
		for(int ordinal = 0; ordinal < leaves.size(); ordinal++)
			members.add(nodeConstructor.newInstance(ordinal, new int[]{ordinal}, leaves.get(ordinal)));
		Field incidenceField = IncrementalRegionalOptimizer.class.getDeclaredField("incidence");
		incidenceField.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Variable,Set<Object>> incidence = (Map<Variable,Set<Object>>)incidenceField.get(optimizer);
		incidence.put(pivot, members);
		invokeCandidateLifecycle(optimizer, "refresh", pivot);
		Object candidate = candidates(optimizer).get(pivot);
		if(candidate == null)
			throw new AssertionError("fixture did not create a conflict candidate");
		return new CandidateFixture(pivot, candidate);
	}

	private static Method conflictScoreMethod(Object candidate) throws Exception {
		Method method = IncrementalRegionalOptimizer.class.getDeclaredMethod(
			"conflictScore", candidate.getClass());
		method.setAccessible(true);
		return method;
	}

	private static void invokeCandidateLifecycle(IncrementalRegionalOptimizer optimizer,
		String methodName, Variable pivot) throws Exception {
		Method method = IncrementalRegionalOptimizer.class.getDeclaredMethod(methodName, Variable.class);
		method.setAccessible(true);
		method.invoke(optimizer, pivot);
	}

	private static void setLong(IncrementalRegionalOptimizer optimizer, String fieldName, long value)
		throws Exception {
		Field field = IncrementalRegionalOptimizer.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.setLong(optimizer, value);
	}

	private static Map<Object,Double> conflictCache(IncrementalRegionalOptimizer optimizer)
		throws Exception {
		Field field = IncrementalRegionalOptimizer.class.getDeclaredField("successfulConflictScores");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Object,Double> cache = (Map<Object,Double>)field.get(optimizer);
		assertTrue("score cache must remain identity-scoped", cache instanceof IdentityHashMap);
		return cache;
	}

	private static Map<Variable,Object> candidates(IncrementalRegionalOptimizer optimizer)
		throws Exception {
		Field field = IncrementalRegionalOptimizer.class.getDeclaredField("candidates");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Variable,Object> candidates = (Map<Variable,Object>)field.get(optimizer);
		return candidates;
	}

	private static void assertCacheBoundedByLiveCandidates(
		IncrementalRegionalOptimizer optimizer, int expectedLive) throws Exception {
		assertEquals(expectedLive, candidates(optimizer).size());
		assertTrue("score cache cannot outlive the candidate registry",
			conflictCache(optimizer).size() <= candidates(optimizer).size());
	}
}
