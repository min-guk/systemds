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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;

/** Shared numerical certificate, budget and trace boundary; algorithm policies are separate. */
final class RegionalSearchOptimizer {
	enum Algorithm { ANYTIME_TARGET, THRESHOLD, TARGET_GAP, REUSE }
	enum StopReason { TARGET_REACHED, GLOBAL_EXACT, TIME_BUDGET, RESOURCE_LIMIT, STEP_LIMIT, REGION_LIMIT, FRONTIER_LIMIT }

	record Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, int maxSteps,
		int probeCandidates, int coveragePeriod, int maximumFrontier, long exactClosureAssignments,
		long regionWorkLimit, int incumbentRescueAttempts, boolean targetCompactPreparation) {
		Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, int maxSteps,
			int probeCandidates, int coveragePeriod, int maximumFrontier, long exactClosureAssignments,
			long regionWorkLimit, int incumbentRescueAttempts) {
			this(algorithm, common, maxSteps, probeCandidates, coveragePeriod, maximumFrontier,
				exactClosureAssignments, regionWorkLimit, incumbentRescueAttempts, true);
		}
		Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, int maxSteps,
			int probeCandidates, int coveragePeriod, int maximumFrontier, long exactClosureAssignments,
			long regionWorkLimit) {
			this(algorithm, common, maxSteps, probeCandidates, coveragePeriod, maximumFrontier,
				exactClosureAssignments, regionWorkLimit, 0);
		}
		Options(Algorithm algorithm, CertifiedRegionalOptimizer.Options common, int maxSteps,
			int probeCandidates, int coveragePeriod, int maximumFrontier, long exactClosureAssignments) {
			this(algorithm, common, maxSteps, probeCandidates, coveragePeriod, maximumFrontier,
				exactClosureAssignments, 100_000L);
		}
		Options {
			Objects.requireNonNull(algorithm, "algorithm");
			Objects.requireNonNull(common, "common");
			if(maxSteps < 1 || probeCandidates < 1 || coveragePeriod < 1 || maximumFrontier < 1
				|| exactClosureAssignments < 0 || regionWorkLimit < 0 || !common.expandRegions()
				|| incumbentRescueAttempts < 0 || incumbentRescueAttempts > 2)
				throw new IllegalArgumentException("REGIONAL_SEARCH_OPTIONS_INVALID");
		}
		static Options configured(CertifiedRegionalOptimizer.Options common) {
			String value = System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "algorithm", "legacy");
			if(value.equals("legacy"))
				return null;
			Algorithm algorithm = switch(value) {
				case "anytime-target" -> Algorithm.ANYTIME_TARGET;
				case "threshold" -> Algorithm.THRESHOLD;
				case "target-gap" -> Algorithm.TARGET_GAP;
				case "reuse" -> Algorithm.REUSE;
				default -> throw new IllegalArgumentException("REGIONAL_SEARCH_ALGORITHM_INVALID|value=" + value);
			};
			if(common == null || !common.expandRegions())
				throw new IllegalArgumentException("REGIONAL_SEARCH_REQUIRES_ANYTIME_MODE");
			return new Options(algorithm, common, integer("maxSteps", 256), integer("probeCandidates", 2),
				integer("coveragePeriod", 3), integer("maxFrontier", 2048),
				Long.parseLong(System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX
					+ "exactClosureAssignments", "100000")),
				Long.parseLong(System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX
					+ "regionWorkLimit", "100000")), integer("incumbentRescueAttempts", 2),
				booleanOption("targetCompactPreparation", true));
		}
		private static int integer(String key, int fallback) {
			return Integer.parseInt(System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + key,
				Integer.toString(fallback)));
		}
		private static boolean booleanOption(String key, boolean fallback) {
			String value = System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + key,
				Boolean.toString(fallback)).toLowerCase(Locale.ROOT);
			return switch(value) {
				case "true" -> true;
				case "false" -> false;
				default -> throw new IllegalArgumentException("REGIONAL_SEARCH_BOOLEAN_INVALID|key=" + key);
			};
		}
	}

	static final class Statistics {
		private final Map<String,Long> counts = new LinkedHashMap<>();
		Statistics() {
			for(String key : List.of("boundCalls", "regionCalls", "exactCalls", "probes", "probeVariables",
				"cacheHits", "generatedNodes", "branchedNodes", "closedNodes", "prunedNodes", "deferredNodes",
				"frontier", "maxFrontier", "regionVariables", "maxRegion", "replicaVariables", "restoredEqualities",
				"boundAssignments", "exactAssignments", "boundMaterializedCells", "maxFactorCells",
				"boundNanos", "regionNanos", "exactNanos", "diagnosticNanos", "zeroGainActions", "forcedExpansions",
				"boundActions", "regionActions", "resourceFailures", "widthStrengthenings", "steps",
				"regionPreflightCalls", "regionWorkSkips", "regionHardResourceSkips",
				"regionPreflightAssignments", "regionPreflightMaterializedCells",
				"wholePreflightCalls", "wholePreflightSkips", "wholeHardResourceSkips",
				"wholePreflightAssignments", "wholePreflightMaterializedCells", "coverageAttempts",
				"coverageVariables", "zeroUbGrowthAccelerations", "boundWidthPasses",
				"boundWidthSuspensions", "boundWidthResumptions", "wholeClosureAttempts", "wholeClosureCompleted",
				"incumbentRescueAttempts", "incumbentRescueImprovements"))
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
		MiniBucketLowerBound.Result initialBound;

		State(RegionalSearchProblem problem, List<Integer> seed, Options options, Consumer<Checkpoint> observer) {
			this.options = Objects.requireNonNull(options, "options");
			this.problem = Objects.requireNonNull(problem, "problem").usingCompactedPreparation(
				options.algorithm() == Algorithm.ANYTIME_TARGET && options.targetCompactPreparation());
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
		boolean nodeSufficient(double nodeLower) {
			return gap(nodeLower, upper) <= options.common().absoluteTolerance()
				|| relative(nodeLower, upper) <= options.common().relativeTolerance();
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
		MiniBucketLowerBound.Result bound(int[] fixed, int width) {
			long phaseStart = System.nanoTime();
			stats.add("boundCalls", 1);
			try {
				MiniBucketLowerBound.Result result = problem.bound(fixed, width, options.common().limits(), this::expired);
				if(Double.isNaN(result.lowerBound()) || result.lowerBound() < 0)
					throw new IllegalStateException("REGIONAL_SEARCH_NODE_BOUND_INVALID");
				stats.add("boundAssignments", result.statistics().evaluatedAssignments());
				stats.add("boundMaterializedCells", result.statistics().materializedCells());
				stats.max("maxFactorCells", result.statistics().maximumFactorCells());
				return result;
			}
			finally { stats.add("boundNanos", System.nanoTime() - phaseStart); }
		}
		RegionalSearchProblem.Solution region(int[] fixed, Set<Integer> region, List<Integer> reference) {
			long diagnosticStart = System.nanoTime();
			stats.add("regionPreflightCalls", 1);
			RegionalSearchProblem.RegionalWork work;
			try {
				work = problem.preflightRegion(fixed, region, reference, options.common().limits(),
					options.regionWorkLimit(), this::expired);
				stats.add("regionPreflightAssignments", work.eliminationAssignments());
				stats.add("regionPreflightMaterializedCells", work.materializedFactorCells());
				stats.max("maxFactorCells", work.maximumFactorCells());
			}
			finally {
				stats.add("diagnosticNanos", System.nanoTime() - diagnosticStart);
			}
			if(!work.admitted()) {
				stats.add("regionWorkSkips", 1);
				if(work.hardResourceLimited())
					stats.add("regionHardResourceSkips", 1);
				throw new IllegalArgumentException("REGIONAL_SEARCH_WORK_LIMIT_EXCEEDED|assignments="
					+ work.eliminationAssignments() + "|limit=" + options.regionWorkLimit()
					+ "|hardResource=" + work.hardResourceLimited());
			}
			long phaseStart = System.nanoTime();
			stats.add("regionCalls", 1);
			stats.set("regionVariables", region.size());
			stats.max("maxRegion", region.size());
			try {
				RegionalSearchProblem.Solution solution = problem.solveRegion(fixed, region, reference,
					options.common().limits(), this::expired);
				recordExact(solution);
				return solution;
			}
			finally { stats.add("regionNanos", System.nanoTime() - phaseStart); }
		}
		RegionalSearchProblem.Solution whole(int[] fixed) {
			long phaseStart = System.nanoTime();
			stats.add("exactCalls", 1);
			try {
				RegionalSearchProblem.Solution solution = problem.solveWhole(fixed, options.common().limits(), this::expired);
				recordExact(solution);
				return solution;
			}
			finally { stats.add("exactNanos", System.nanoTime() - phaseStart); }
		}
		RegionalSearchProblem.RegionalWork preflightWhole(int[] fixed) {
			long phaseStart = System.nanoTime();
			stats.add("wholePreflightCalls", 1);
			try {
				RegionalSearchProblem.RegionalWork work = problem.preflightWhole(fixed,
					options.common().limits(), options.exactClosureAssignments(), this::expired);
				stats.add("wholePreflightAssignments", work.eliminationAssignments());
				stats.add("wholePreflightMaterializedCells", work.materializedFactorCells());
				stats.max("maxFactorCells", work.maximumFactorCells());
				if(!work.admitted()) {
					stats.add("wholePreflightSkips", 1);
					if(work.hardResourceLimited())
						stats.add("wholeHardResourceSkips", 1);
				}
				return work;
			}
			finally { stats.add("diagnosticNanos", System.nanoTime() - phaseStart); }
		}
		boolean canSolveWhole(int[] fixed) {
			return preflightWhole(fixed).admitted();
		}
		private void recordExact(RegionalSearchProblem.Solution solution) {
			if(solution.statistics() != null) {
				stats.add("exactAssignments", solution.statistics().eliminationAssignments());
				stats.max("maxFactorCells", solution.statistics().maximumFactorCells());
			}
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
			state.initialBound = state.bound(problem.unconstrained(), options.common().initialWidth());
			state.raiseLower(state.initialBound.lowerBound());
			state.publish("INITIAL_BOUND");
			if(state.reached())
				return state.finish(StopReason.TARGET_REACHED);
			if(state.expired())
				return state.finish(StopReason.TIME_BUDGET);
			return switch(options.algorithm()) {
				case ANYTIME_TARGET -> TargetAnytimeOptimizer.run(state);
				case THRESHOLD -> AdaptiveThresholdOptimizer.run(state);
				case TARGET_GAP, REUSE -> BranchingRegionalOptimizer.run(state);
			};
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
