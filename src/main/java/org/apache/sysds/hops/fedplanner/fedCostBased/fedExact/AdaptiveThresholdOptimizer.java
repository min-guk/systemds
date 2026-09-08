/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Algorithm 1: adaptive primal/dual threshold closure with nested replicas. */
final class AdaptiveThresholdOptimizer {
	private static final double GAIN_SMOOTHING = 0.5d;

	private AdaptiveThresholdOptimizer() { }

	static RegionalSearchOptimizer.Result run(RegionalSearchOptimizer.State state) {
		Set<Integer> region = new LinkedHashSet<>();
		NestedMiniBucketRelaxation relaxation = initializeRelaxation(state);
		if(state.expired())
			return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
		if(relaxation != null && relaxation.fullyRestored())
			return closeFromRelaxation(state, relaxation);

		int maximumRegion = Math.min(state.problem.decisionCount(),
			state.options.common().maximumRegionVariables());
		int actionsSinceCoverage = 0;
		boolean regionResourceLimited = false;
		double primalGainEstimate = initialPrimalGain(state, maximumRegion);
		double lowerGainEstimate = initialLowerGain(state, relaxation);
		for(int step = 0; step < state.options.maxSteps(); step++) {
			state.stats.set("steps", step + 1L);
			if(state.reached())
				return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
			if(state.expired())
				return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);

			boolean primalAvailable = !regionResourceLimited && region.size() < maximumRegion;
			boolean lowerAvailable = relaxation != null && !relaxation.candidates().isEmpty();
			if(!primalAvailable && !lowerAvailable)
				return state.finish(region.size() < state.problem.decisionCount()
					? RegionalSearchOptimizer.StopReason.REGION_LIMIT
					: RegionalSearchOptimizer.StopReason.RESOURCE_LIMIT);

			Set<Integer> proposedRegion = primalAvailable
				? proposedRegion(state, region, maximumRegion, relaxation) : Set.of();
			long primalWork = primalAvailable ? predictedPrimalWork(state, proposedRegion) : Long.MAX_VALUE;
			long lowerWork = lowerAvailable ? predictedLowerWork(relaxation, state.options.probeCandidates()) : Long.MAX_VALUE;
			double tau = state.options.common().relativeTolerance();
			double predictedPrimalGain = primalGainEstimate;
			double predictedLowerGain = lowerGainEstimate;
			double primalScore = primalAvailable ? predictedPrimalGain / primalWork : -1d;
			double lowerScore = lowerAvailable ? (1d + tau) * predictedLowerGain / lowerWork : -1d;
			boolean forced = primalAvailable && actionsSinceCoverage >= state.options.coveragePeriod();
			boolean choosePrimal = primalAvailable && (forced || !lowerAvailable || primalScore >= lowerScore);

			if(choosePrimal) {
				double before = state.upper;
				region.clear();
				region.addAll(proposedRegion);
				state.stats.add("regionActions", 1);
				if(forced)
					state.stats.add("forcedExpansions", 1);
				try {
					RegionalSearchProblem.Solution solution = state.region(
						state.problem.unconstrained(), region, state.assignment);
					boolean improved = state.accept(solution);
					double actual = before - state.upper;
					primalGainEstimate = smooth(primalGainEstimate, actual);
					if(actual == 0d)
						state.stats.add("zeroGainActions", 1);
					state.publish("THRESHOLD_PRIMAL", details("P", forced, predictedPrimalGain,
						0d, primalWork, primalScore, region, relaxation,
						"improved=" + improved + " actualDeltaU=" + number(actual)));
					if(region.size() == state.problem.decisionCount()) {
						if(!solution.feasible())
							throw new IllegalStateException("THRESHOLD_FULL_REGION_INFEASIBLE");
						state.raiseLower(state.upper);
						return state.finish(RegionalSearchOptimizer.StopReason.GLOBAL_EXACT);
					}
				}
				catch(IllegalArgumentException failure) {
					if(!RegionalSearchProblem.isResourceLimit(failure))
						throw failure;
					state.stats.add("resourceFailures", 1);
					regionResourceLimited = true;
					state.publish("THRESHOLD_PRIMAL_RESOURCE", details("P", forced,
						predictedPrimalGain, 0d, primalWork, primalScore, region, relaxation, "limited=true"));
				}
				actionsSinceCoverage = 0;
			}
			else {
				double before = state.lower;
				state.stats.add("boundActions", 1);
				NestedMiniBucketRelaxation.Work workBefore = relaxation.work();
				NestedMiniBucketRelaxation.Refinement refinement;
				long refinementStarted = System.nanoTime();
				try {
					refinement = relaxation.refine(state.options.probeCandidates(), state::expired);
				}
				finally {
					NestedMiniBucketRelaxation.Work workAfter = relaxation.work();
					long attempted = workAfter.attemptedCalls() - workBefore.attemptedCalls();
					state.stats.add("probes", attempted);
					state.stats.add("probeVariables", attempted);
					state.stats.add("nestedExactCalls", attempted);
					state.stats.add("resourceFailures", workAfter.resourceLimitedCalls()
						- workBefore.resourceLimitedCalls());
					state.stats.add("nestedExactAssignments", workAfter.eliminationAssignments()
						- workBefore.eliminationAssignments());
					state.stats.add("nestedMaterializedCells", workAfter.materializedCells()
						- workBefore.materializedCells());
					state.stats.max("maxFactorCells", workAfter.maximumFactorCells());
					state.stats.add("boundNanos", System.nanoTime() - refinementStarted);
				}
				state.stats.set("replicaVariables", relaxation.replicaVariables());
				state.stats.set("restoredEqualities", relaxation.restoredEqualities());
				state.raiseLower(relaxation.lowerBound());
				double actual = state.lower - before;
				lowerGainEstimate = smooth(lowerGainEstimate, actual);
				if(actual == 0d)
					state.stats.add("zeroGainActions", 1);
				String merge = refinement.candidate() == null ? "none"
					: refinement.candidate().originalVariable() + ":"
						+ refinement.candidate().leftReplica() + ":"
						+ refinement.candidate().rightReplica();
				String phase = !refinement.merged() && refinement.resourceLimitedProbes() > 0
					? "THRESHOLD_LOWER_RESOURCE" : "THRESHOLD_LOWER";
				state.publish(phase, details("L", false, 0d,
					predictedLowerGain, lowerWork, lowerScore, region, relaxation,
					"merge=" + merge + " merged=" + refinement.merged()
						+ " actualDeltaL=" + number(actual)));
				if(relaxation.fullyRestored())
					return closeFromRelaxation(state, relaxation);
				actionsSinceCoverage++;
			}
		}
		return state.finish(RegionalSearchOptimizer.StopReason.STEP_LIMIT);
	}

	private static NestedMiniBucketRelaxation initializeRelaxation(
		RegionalSearchOptimizer.State state) {
		long started = System.nanoTime();
		state.stats.add("boundActions", 1);
		try {
			NestedMiniBucketRelaxation relaxation = NestedMiniBucketRelaxation.create(
				state.problem.variables(), state.problem.factors(),
				state.options.common().initialWidth(), state.options.common().limits(), state::expired);
			state.stats.add("nestedExactCalls", 1);
			state.stats.add("nestedExactAssignments", relaxation.statistics().eliminationAssignments());
			state.stats.set("replicaVariables", relaxation.replicaVariables());
			state.stats.set("restoredEqualities", 0);
			state.stats.max("maxFactorCells", relaxation.statistics().maximumFactorCells());
			state.raiseLower(relaxation.lowerBound());
			state.stats.add("boundNanos", System.nanoTime() - started);
			state.publish("THRESHOLD_RELAXATION", "partition=" + relaxation.partitionIdentity()
				+ " replicas=" + relaxation.replicaVariables() + " equalities=0");
			return relaxation;
		}
		catch(CancellationException cancelled) {
			state.stats.add("boundNanos", System.nanoTime() - started);
			throw cancelled;
		}
		catch(MiniBucketLowerBound.ResourceLimitException limited) {
			state.stats.add("resourceFailures", 1);
			state.stats.add("boundNanos", System.nanoTime() - started);
			state.publish("THRESHOLD_RELAXATION_RESOURCE", "kind=mbe");
			return null;
		}
		catch(IllegalArgumentException limited) {
			if(!RegionalSearchProblem.isResourceLimit(limited))
				throw limited;
			state.stats.add("resourceFailures", 1);
			state.stats.add("boundNanos", System.nanoTime() - started);
			state.publish("THRESHOLD_RELAXATION_RESOURCE", "kind=exact");
			return null;
		}
	}

	private static RegionalSearchOptimizer.Result closeFromRelaxation(
		RegionalSearchOptimizer.State state, NestedMiniBucketRelaxation relaxation) {
		List<Integer> exactAssignment = relaxation.originalAssignment(state.problem.decisionCount());
		RegionalSearchProblem.Solution exact = new RegionalSearchProblem.Solution(true,
			relaxation.solvedObjective(), exactAssignment, relaxation.statistics());
		state.accept(exact);
		state.raiseLower(state.upper);
		state.publish("THRESHOLD_REPLICA_EXACT", "partition=" + relaxation.partitionIdentity()
			+ " equalities=" + relaxation.restoredEqualities());
		return state.finish(RegionalSearchOptimizer.StopReason.GLOBAL_EXACT);
	}

	private static double initialPrimalGain(RegionalSearchOptimizer.State state, int maximumRegion) {
		int growth = Math.max(1, state.options.common().regionGrowth());
		int actions = Math.max(1, (maximumRegion + growth - 1) / growth);
		return Math.max(Math.ulp(state.upper), RegionalSearchOptimizer.gap(state.lower, state.upper) / actions);
	}

	private static double initialLowerGain(RegionalSearchOptimizer.State state,
		NestedMiniBucketRelaxation relaxation) {
		int actions = relaxation == null ? 1 : Math.max(1, relaxation.candidates().size());
		return Math.max(Math.ulp(state.upper), RegionalSearchOptimizer.gap(state.lower, state.upper) / actions);
	}

	private static Set<Integer> proposedRegion(RegionalSearchOptimizer.State state,
		Set<Integer> current, int maximumRegion, NestedMiniBucketRelaxation relaxation) {
		Set<Integer> proposed = new LinkedHashSet<>(current);
		if(relaxation != null)
			for(int variable : relaxation.rankedOriginalVariables(state.problem.decisionCount()))
				if(!proposed.contains(variable)) {
					proposed.add(variable);
					break;
				}
		int target = Math.min(maximumRegion, Math.max(current.size() + 1,
			current.size() + state.options.common().regionGrowth()));
		state.problem.growRegion(proposed, target, state.initialBound.conflicts());
		return proposed;
	}

	private static long predictedPrimalWork(RegionalSearchOptimizer.State state,
		Set<Integer> proposedRegion) {
		long work = 1L;
		for(int index : proposedRegion) {
			int domain = state.problem.domainSize(index);
			if(work > Long.MAX_VALUE / domain)
				return Long.MAX_VALUE;
			work *= domain;
		}
		return Math.max(1L, work);
	}

	private static long predictedLowerWork(NestedMiniBucketRelaxation relaxation, int probes) {
		long work = Math.max(1L, relaxation.statistics().eliminationAssignments());
		return work > Long.MAX_VALUE / probes ? Long.MAX_VALUE : work * probes;
	}

	private static double smooth(double previous, double actual) {
		return Math.max(0d, GAIN_SMOOTHING * previous + (1d - GAIN_SMOOTHING) * actual);
	}

	private static String details(String action, boolean forced, double predictedDeltaU,
		double predictedDeltaL, long predictedWork, double score, Set<Integer> region,
		NestedMiniBucketRelaxation relaxation, String outcome) {
		return "chosenAction=" + action + " forced=" + forced
			+ " predictedDeltaU=" + number(predictedDeltaU)
			+ " predictedDeltaL=" + number(predictedDeltaL)
			+ " predictedTimeUnits=" + predictedWork + " score=" + number(score)
			+ " region=" + join(region) + " partition="
			+ (relaxation == null ? "none" : relaxation.partitionIdentity()) + ' ' + outcome;
	}

	private static String join(Set<Integer> values) {
		StringBuilder result = new StringBuilder();
		for(int value : values) {
			if(result.length() > 0)
				result.append(':');
			result.append(value);
		}
		return result.length() == 0 ? "empty" : result.toString();
	}

	private static String number(double value) {
		return String.format(Locale.ROOT, "%.17g", value);
	}
}
