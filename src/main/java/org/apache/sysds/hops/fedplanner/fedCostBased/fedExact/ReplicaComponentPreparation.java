/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.List;
import java.util.Objects;

/**
 * Instance-scoped preparation statistics and bounded elimination-order reuse for
 * incrementally rebuilt replica components.
 *
 * <p>Only variable keys from a previous elimination order are reused. Every call
 * validates the current variables and factors, rebuilds separators from the current
 * scopes, and checks all exact-solver and work limits. Numeric factor values,
 * backpointers, and variable-index mappings are never cached.</p>
 */
final class ReplicaComponentPreparation {
	record Counters(long reuseHits, long reuseMisses, long reuseFallbacks,
		long preparationNanos) { }

	record Prepared(ExactCategoricalSolver.CompiledProblem compiled,
		boolean usedPreferred, Counters counters) {
		Prepared {
			Objects.requireNonNull(compiled, "compiled");
			Objects.requireNonNull(counters, "counters");
		}
	}

	private long reuseHits;
	private long reuseMisses;
	private long reuseFallbacks;
	private long preparationNanos;

	Prepared prepare(List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, ExactCategoricalSolver.Limits limits,
		long maximumWork, List<String> preferredEliminationOrder) {
		if(maximumWork <= 0)
			throw new IllegalArgumentException("INCREMENTAL_REPLICA_WORK_LIMIT_INVALID");
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(factors, "factors");
		Objects.requireNonNull(limits, "limits");

		if(preferredEliminationOrder == null || preferredEliminationOrder.isEmpty()) {
			reuseMisses = saturatedAdd(reuseMisses, 1L);
			return finish(ordinary(variables, factors, limits, maximumWork), false);
		}

		ExactCategoricalSolver.CompiledProblem preferred;
		long started = System.nanoTime();
		try {
			preferred = ExactCategoricalSolver.compilePreferred(
				variables, factors, limits, preferredEliminationOrder);
		}
		catch(IllegalArgumentException failure) {
			preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - started);
			if(!isReusableOrderFailure(failure))
				throw failure;
			reuseFallbacks = saturatedAdd(reuseFallbacks, 1L);
			return finish(ordinary(variables, factors, limits, maximumWork), false);
		}
		preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - started);
		if(ExactCategoricalSolver.statistics(preferred).eliminationAssignments() > maximumWork) {
			reuseFallbacks = saturatedAdd(reuseFallbacks, 1L);
			return finish(ordinary(variables, factors, limits, maximumWork), false);
		}
		reuseHits = saturatedAdd(reuseHits, 1L);
		return finish(preferred, true);
	}

	private ExactCategoricalSolver.CompiledProblem ordinary(
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> factors, ExactCategoricalSolver.Limits limits,
		long maximumWork) {
		long started = System.nanoTime();
		ExactCategoricalSolver.CompiledProblem compiled;
		try {
			compiled = ExactCategoricalSolver.compile(variables, factors, limits);
		}
		finally {
			preparationNanos = saturatedAdd(preparationNanos, System.nanoTime() - started);
		}
		long work = ExactCategoricalSolver.statistics(compiled).eliminationAssignments();
		if(work > maximumWork)
			throw new MiniBucketLowerBound.ResourceLimitException(
				"INCREMENTAL_REPLICA_COMPONENT_WORK_LIMIT|work=" + work
					+ "|limit=" + maximumWork);
		return compiled;
	}

	private Prepared finish(ExactCategoricalSolver.CompiledProblem compiled,
		boolean usedPreferred) {
		return new Prepared(compiled, usedPreferred, counters());
	}

	Counters counters() {
		return new Counters(reuseHits, reuseMisses, reuseFallbacks, preparationNanos);
	}

	private static boolean isReusableOrderFailure(IllegalArgumentException failure) {
		String message = failure.getMessage();
		return "EXACT_VE_PREFERRED_ORDER_INVALID".equals(message)
			|| RegionalSearchProblem.isResourceLimit(failure);
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}
}
