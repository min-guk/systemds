/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ReplicaComponentPreparationTest {
	private static final ExactCategoricalSolver.Limits GENEROUS =
		new ExactCategoricalSolver.Limits(1_000_000, 5_000_000);
	private static final long GENEROUS_WORK = 5_000_000L;

	@Test
	public void preferredOrderRebuildsCurrentNumericFactorsAndPreservesTieContract() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var preparation = new ReplicaComponentPreparation();
		var initial = preparation.prepare(List.of(a, b), List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 0d, 0d, 0d, 0d)),
			GENEROUS, GENEROUS_WORK, List.of());
		List<String> preferred =
			ExactCategoricalSolver.statistics(initial.compiled()).eliminationOrder();

		AtomicInteger preferredValue = new AtomicInteger(1);
		List<ExactCategoricalSolver.Factor> current = List.of(
			ExactCategoricalSolver.Factor.lazy(List.of(a), values ->
				values[0] == preferredValue.get() ? 0d : 4d),
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 0d, 0d, 0d, 0d));
		var reused = preparation.prepare(List.of(a, b), current,
			GENEROUS, GENEROUS_WORK, preferred);
		var reusedResult = ExactCategoricalSolver.solve(reused.compiled());
		var ordinaryResult = ExactCategoricalSolver.solve(List.of(a, b), current, GENEROUS);

		Assert.assertTrue(reused.usedPreferred());
		Assert.assertEquals(ordinaryResult.objective(), reusedResult.objective(), 0d);
		Assert.assertEquals(ordinaryResult.assignmentInVariableOrder(),
			reusedResult.assignmentInVariableOrder());
		Assert.assertEquals(List.of(1, 0), reusedResult.assignmentInVariableOrder());
		Assert.assertEquals(1L, reused.counters().reuseHits());
		Assert.assertEquals(1L, reused.counters().reuseMisses());
		Assert.assertEquals(0L, reused.counters().reuseFallbacks());
	}

	@Test
	public void preferredOrderIsReplannedAgainstChangedDomainsAndScopes() {
		var oldA = variable("a", 2);
		var oldB = variable("b", 2);
		var oldC = variable("c", 2);
		List<String> oldOrder = ExactCategoricalSolver.analyze(
			List.of(oldA, oldB, oldC), List.of(
				ExactCategoricalSolver.Factor.dense(List.of(oldA, oldB), 0d, 1d, 2d, 3d),
				ExactCategoricalSolver.Factor.dense(List.of(oldB, oldC), 0d, 1d, 2d, 3d)),
			GENEROUS).eliminationOrder();

		var a = variable("a", 3);
		var b = variable("b", 2);
		var c = variable("c", 2);
		List<ExactCategoricalSolver.Factor> changed = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a), 5d, 0d, 9d),
			ExactCategoricalSolver.Factor.dense(List.of(a, b, c),
				8d, 7d, 6d, 5d, 0d, 3d, 4d, 2d, 9d, 9d, 9d, 9d));
		var reused = new ReplicaComponentPreparation().prepare(
			List.of(a, b, c), changed, GENEROUS, GENEROUS_WORK, oldOrder);
		var reusedResult = ExactCategoricalSolver.solve(reused.compiled());
		var ordinaryResult = ExactCategoricalSolver.solve(List.of(a, b, c), changed, GENEROUS);

		Assert.assertTrue(reused.usedPreferred());
		Assert.assertEquals(oldOrder,
			ExactCategoricalSolver.statistics(reused.compiled()).eliminationOrder());
		Assert.assertEquals(ordinaryResult.objective(), reusedResult.objective(), 0d);
		Assert.assertEquals(ordinaryResult.assignmentInVariableOrder(),
			reusedResult.assignmentInVariableOrder());
	}

	@Test
	public void invalidPreferredOrderFallsBackToOrdinaryPlanning() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			ExactCategoricalSolver.Factor.dense(List.of(a, b), 3d, 2d, 1d, 0d));
		var prepared = new ReplicaComponentPreparation().prepare(List.of(a, b), factors,
			GENEROUS, GENEROUS_WORK, List.of("a", "missing"));

		Assert.assertFalse(prepared.usedPreferred());
		Assert.assertEquals(1L, prepared.counters().reuseFallbacks());
		Assert.assertEquals(0d, ExactCategoricalSolver.solve(prepared.compiled()).objective(), 0d);
	}

	@Test
	public void resourceHeavyPreferredOrderFallsBackToBoundedDefault() {
		var a = variable("a", 2);
		var b = variable("b", 100);
		var c = variable("c", 2);
		var d = variable("d", 2);
		List<ExactCategoricalSolver.Variable> variables = List.of(a, b, c, d);
		List<ExactCategoricalSolver.Factor> factors = List.of(
			zeroFactor(List.of(a, b)), zeroFactor(List.of(b, c)), zeroFactor(List.of(b, d)));
		var prepared = new ReplicaComponentPreparation().prepare(variables, factors,
			new ExactCategoricalSolver.Limits(1_000, 700), GENEROUS_WORK,
			List.of("a", "c", "d", "b"));

		Assert.assertFalse(prepared.usedPreferred());
		Assert.assertEquals(1L, prepared.counters().reuseFallbacks());
		Assert.assertEquals(List.of("b", "a", "c", "d"),
			ExactCategoricalSolver.statistics(prepared.compiled()).eliminationOrder());
	}

	@Test
	public void finalSelectedPlanMustRespectMaximumWork() {
		var a = variable("a", 2);
		var b = variable("b", 2);
		var preparation = new ReplicaComponentPreparation();
		MiniBucketLowerBound.ResourceLimitException failure = Assert.assertThrows(
			MiniBucketLowerBound.ResourceLimitException.class,
			() -> preparation.prepare(List.of(a, b), List.of(zeroFactor(List.of(a, b))),
				GENEROUS, 1L, List.of("a", "b")));
		Assert.assertTrue(failure.getMessage().startsWith(
			"INCREMENTAL_REPLICA_COMPONENT_WORK_LIMIT"));
	}

	@Test
	public void failedOrdinaryFallbackIsAttemptedAndCountedOnlyOnce() {
		var a = variable("a", 100);
		var preparation = new ReplicaComponentPreparation();
		IllegalArgumentException failure = Assert.assertThrows(IllegalArgumentException.class,
			() -> preparation.prepare(List.of(a), List.of(zeroFactor(List.of(a))),
				new ExactCategoricalSolver.Limits(99, 1_000), GENEROUS_WORK, List.of("a")));

		Assert.assertTrue(failure.getMessage().startsWith("EXACT_VE_FACTOR_LIMIT_EXCEEDED"));
		Assert.assertEquals(1L, preparation.counters().reuseFallbacks());
		Assert.assertEquals(0L, preparation.counters().reuseHits());
		Assert.assertEquals(0L, preparation.counters().reuseMisses());
	}

	private static ExactCategoricalSolver.Variable variable(String key, int domain) {
		return new ExactCategoricalSolver.Variable(key, domain);
	}

	private static ExactCategoricalSolver.Factor zeroFactor(
		List<ExactCategoricalSolver.Variable> scope) {
		return ExactCategoricalSolver.Factor.lazy(scope, values -> 0d);
	}
}
