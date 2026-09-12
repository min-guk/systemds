/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Certificate, budget and trace boundary for one initial bound and remaining exact completion. */
final class RegionalSearchOptimizer {
	enum Algorithm { REMAINING_EXACT }
	enum StopReason { TARGET_REACHED, GLOBAL_EXACT, TIME_BUDGET, RESOURCE_LIMIT }

	record Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, long exactClosureAssignments,
		long costShiftMillis, int costShiftMaxSweeps, MiniBucketLowerBound.PlanningPolicy lbPlanning) {
		Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, long exactClosureAssignments) {
			this(algorithm, common, exactClosureAssignments, 0L, 1000);
		}
		Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, long exactClosureAssignments,
			long costShiftMillis, int costShiftMaxSweeps) {
			this(algorithm, common, exactClosureAssignments, costShiftMillis, costShiftMaxSweeps,
				new MiniBucketLowerBound.PlanningPolicy(MiniBucketLowerBound.EliminationOrder.INPUT,
					MiniBucketLowerBound.PartitionStrategy.FIRST_FIT));
		}
		Options {
			Objects.requireNonNull(algorithm, "algorithm");
			Objects.requireNonNull(common, "common");
			Objects.requireNonNull(lbPlanning, "lbPlanning");
			if(exactClosureAssignments < 0 || costShiftMillis < 0 || costShiftMaxSweeps < 1)
				throw new IllegalArgumentException("REGIONAL_SEARCH_OPTIONS_INVALID");
		}
		static Options configured(CertifiedRegionalOptimizer.Options common) {
			String value = System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "algorithm",
				common == null ? "off" : "remaining-exact");
			if(common == null && (value.equals("off") || value.equals("legacy")))
				return null;
			if(!value.equals("remaining-exact"))
				throw new IllegalArgumentException("REGIONAL_SEARCH_ALGORITHM_REMOVED|value=" + value
					+ "|supported=remaining-exact");
			if(common == null)
				throw new IllegalArgumentException("REGIONAL_SEARCH_REQUIRES_ENABLED_MODE");
			return new Options(Algorithm.REMAINING_EXACT, common,
				Long.parseLong(System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX
					+ "exactClosureAssignments", "100000")),
				Long.parseLong(System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX
					+ "costShiftMillis", "0")),
				Integer.parseInt(System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX
					+ "costShiftMaxSweeps", "1000")),
				new MiniBucketLowerBound.PlanningPolicy(
					MiniBucketLowerBound.EliminationOrder.valueOf(policyProperty("lbOrder", "input")),
					MiniBucketLowerBound.PartitionStrategy.valueOf(policyProperty("lbPartition", "first-fit"))));
		}
		private static String policyProperty(String name, String fallback) {
			return System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + name, fallback)
				.toUpperCase(Locale.ROOT).replace('-', '_');
		}
	}

	static final class Statistics {
		private final Map<String,Long> counts = new LinkedHashMap<>();
		Statistics() {
			for(String key : List.of("boundCalls", "exactCalls", "boundAssignments", "exactAssignments",
				"boundMaterializedCells", "maxFactorCells", "boundNanos", "exactNanos", "resourceFailures",
				"remainingClosureAttempts", "remainingClosureCompleted", "costShiftCalls", "costShiftNanos",
				"costShiftUpdates", "costShiftSweeps", "costShiftScannedCells", "costShiftResourceLimited"))
				counts.put(key, 0L);
		}
		void add(String key, long value) { counts.put(key, Math.addExact(get(key), value)); }
		void set(String key, long value) {
			if(value < 0)
				throw new IllegalArgumentException("REGIONAL_SEARCH_NEGATIVE_STATISTIC");
			counts.put(key, value);
		}
		void max(String key, long value) { set(key, Math.max(get(key), value)); }
		long get(String key) { return counts.getOrDefault(key, 0L); }
		Map<String,Long> snapshot() { return Collections.unmodifiableMap(new LinkedHashMap<>(counts)); }
	}

	record Checkpoint(int iteration, String phase, Algorithm algorithm, double lowerBound, double upperBound,
		long elapsedNanos, Map<String,Long> statistics, List<Integer> assignment, String details) {
		Checkpoint {
			statistics = Collections.unmodifiableMap(new LinkedHashMap<>(statistics));
			assignment = List.copyOf(assignment);
		}
		double absoluteGap() { return gap(lowerBound, upperBound); }
		double relativeGap() { return relative(lowerBound, upperBound); }
	}
	record Result(Algorithm algorithm, List<Integer> assignment, double lowerBound, double upperBound,
		double seedUpperBound, boolean targetReached, StopReason stopReason, long elapsedNanos,
		String orderFingerprint, Map<String,Long> statistics, List<Checkpoint> checkpoints) {
		Result {
			assignment = List.copyOf(assignment);
			statistics = Collections.unmodifiableMap(new LinkedHashMap<>(statistics));
			checkpoints = List.copyOf(checkpoints);
		}
		double absoluteGap() { return gap(lowerBound, upperBound); }
		double relativeGap() { return relative(lowerBound, upperBound); }
	}

	static final class State {
		final RegionalSearchProblem problem;
		final Options options;
		final Statistics stats = new Statistics();
		final double seedUpperBound;
		final long start;
		final long budgetNanos;
		final List<Checkpoint> history = new ArrayList<>();
		final Consumer<Checkpoint> observer;
		List<Integer> assignment;
		double lower;
		double upper;

		State(RegionalSearchProblem problem, List<Integer> seed, Options options, Consumer<Checkpoint> observer) {
			this.options = Objects.requireNonNull(options, "options");
			this.problem = Objects.requireNonNull(problem, "problem").usingCompactedPreparation(false);
			this.observer = Objects.requireNonNull(observer, "observer");
			assignment = List.copyOf(seed);
			upper = this.problem.evaluate(assignment);
			seedUpperBound = upper;
			start = System.nanoTime();
			budgetNanos = options.common().timeBudgetMillis() > Long.MAX_VALUE / 1_000_000L
				? Long.MAX_VALUE : options.common().timeBudgetMillis() * 1_000_000L;
		}
		boolean expired() {
			return Thread.currentThread().isInterrupted() || System.nanoTime() - start >= budgetNanos;
		}
		boolean reached() {
			return gap(lower, upper) <= options.common().absoluteTolerance()
				|| relative(lower, upper) <= options.common().relativeTolerance();
		}
		void raiseLower(double candidate) {
			if(!Double.isFinite(candidate) || candidate < 0 || candidate > upper)
				throw new IllegalStateException("REGIONAL_SEARCH_BOUND_INVALID|lower=" + candidate + "|upper=" + upper);
			lower = Math.max(lower, candidate);
		}
		boolean accept(RegionalSearchProblem.Solution solution) {
			if(!solution.feasible())
				return false;
			double canonical = problem.evaluate(solution.assignment());
			if(Double.doubleToRawLongBits(canonical) != Double.doubleToRawLongBits(solution.objective()))
				throw new IllegalStateException("REGIONAL_SEARCH_CANDIDATE_OBJECTIVE_MISMATCH");
			if(canonical < lower)
				throw new IllegalStateException("REGIONAL_SEARCH_INCUMBENT_BELOW_BOUND");
			if(canonical >= upper)
				return false;
			assignment = List.copyOf(solution.assignment());
			upper = canonical;
			return true;
		}
		void publish(String phase) {
			publish(phase, "");
		}
		void publish(String phase, String details) {
			if(lower > upper || !Double.isFinite(lower) || !Double.isFinite(upper))
				throw new IllegalStateException("REGIONAL_SEARCH_CERTIFICATE_INVALID");
			Checkpoint checkpoint = new Checkpoint(history.size(), phase, options.algorithm(), lower, upper,
				System.nanoTime() - start, stats.snapshot(), assignment, details);
			history.add(checkpoint);
			observer.accept(checkpoint);
		}
		Result finish(StopReason reason) {
			publish("STOP_" + reason);
			return new Result(options.algorithm(), assignment, lower, upper, seedUpperBound, reached(), reason,
				System.nanoTime() - start, problem.orderFingerprint(), stats.snapshot(), history);
		}
	}

	private RegionalSearchOptimizer() { }
	static Result optimize(List<Variable> variables, List<Factor> factors, List<Integer> seed, Options options) {
		return optimize(RegionalSearchProblem.generic(variables, factors), seed, options, ignored -> { });
	}
	static Result optimizePhysical(ExactPhysicalModel model, ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactPhysicalForcedStateAudit.Constraint forced, List<Integer> seed, Options options, Consumer<Checkpoint> observer) {
		return optimize(RegionalSearchProblem.physical(model, surface, forced), seed, options, observer);
	}
	static Result optimize(RegionalSearchProblem problem, List<Integer> seed, Options options, Consumer<Checkpoint> observer) {
		State state = new State(problem, seed, options, observer);
		state.publish("INITIAL");
		if(state.reached())
			return state.finish(state.upper == 0 ? StopReason.GLOBAL_EXACT : StopReason.TARGET_REACHED);
		try {
			return RemainingExactOptimizer.run(state);
		}
		catch(CancellationException cancelled) { return state.finish(StopReason.TIME_BUDGET); }
		catch(MiniBucketLowerBound.ResourceLimitException limited) {
			state.stats.add("resourceFailures", 1);
			return state.finish(StopReason.RESOURCE_LIMIT);
		}
		catch(IllegalArgumentException failure) {
			if(!RegionalSearchProblem.isResourceLimit(failure))
				throw failure;
			state.stats.add("resourceFailures", 1);
			return state.finish(StopReason.RESOURCE_LIMIT);
		}
	}
	static double gap(double lower, double upper) {
		return lower >= upper ? 0d : Math.nextUp(upper - lower);
	}
	static double relative(double lower, double upper) {
		return lower >= upper ? 0d : lower > 0 ? Math.nextUp(gap(lower, upper) / lower) : Double.POSITIVE_INFINITY;
	}
	static String statisticsTrace(Map<String,Long> statistics) {
		StringBuilder text = new StringBuilder();
		statistics.forEach((key, value) -> text.append(' ').append(key).append('=').append(value));
		return text.toString();
	}
	static String checkpointTrace(Checkpoint checkpoint) {
		return String.format(Locale.ROOT,
			"phase=CHECKPOINT action=%s algorithm=%s iteration=%d lower=%.17g upper=%.17g gap=%.17g "
				+ "relativeGap=%.17g elapsedMs=%.6f%s", checkpoint.phase(), checkpoint.algorithm(), checkpoint.iteration(),
			checkpoint.lowerBound(), checkpoint.upperBound(), checkpoint.absoluteGap(), checkpoint.relativeGap(),
			checkpoint.elapsedNanos() / 1e6, statisticsTrace(checkpoint.statistics()))
			+ " assignmentFingerprint=" + assignmentFingerprint(checkpoint.assignment())
			+ (checkpoint.details().isEmpty() ? "" : " " + checkpoint.details());
	}

	static String assignmentFingerprint(List<Integer> assignment) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(assignment.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch(NoSuchAlgorithmException failure) {
			throw new IllegalStateException("SHA-256 unavailable", failure);
		}
	}
}
