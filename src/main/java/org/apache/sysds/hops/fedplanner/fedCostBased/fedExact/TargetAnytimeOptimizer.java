/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.LinkedHashSet;
import java.util.Set;

/** Threshold-directed variant of the original cumulative-region Anytime policy. */
final class TargetAnytimeOptimizer {
	static final double MINIMUM_RESIDUAL_REDUCTION = 0.001d;

	private TargetAnytimeOptimizer() { }

	static RegionalSearchOptimizer.Result run(RegionalSearchOptimizer.State state) {
		int[] unconstrained = state.problem.unconstrained();
		RegionalSearchProblem.RegionalWork wholeWork = state.preflightWhole(unconstrained);
		state.publish(wholeWork.admitted() ? "ANYTIME_TARGET_EXACT_ADMITTED" : "ANYTIME_TARGET_EXACT_SKIPPED",
			workDetails(wholeWork));
		if(state.reached())
			return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
		if(state.expired())
			return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
		if(wholeWork.admitted())
			return solveWhole(state, unconstrained, wholeWork);

		Set<Integer> region = new LinkedHashSet<>();
		int maximumRegion = Math.min(state.problem.decisionCount(),
			state.options.common().maximumRegionVariables());
		int baseGrowth = state.options.common().regionGrowth();
		int nextGrowth = baseGrowth;
		int nextWidth = state.options.common().initialWidth() + 1;
		boolean boundAvailable = state.options.common().refineBound()
			&& nextWidth <= state.options.common().maximumWidth();
		boolean deferredWidths = false;
		MiniBucketLowerBound.Result guidance = state.initialBound;
		long step = 0;

		while(true) {
			state.stats.set("steps", ++step);
			if(state.reached())
				return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
			if(state.expired())
				return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);

			if(boundAvailable) {
				double before = state.lower;
				double residual = relativeResidual(state.upper, before,
					state.options.common().relativeTolerance());
				state.stats.add("boundActions", 1);
				state.stats.add("boundWidthPasses", 1);
				try {
					MiniBucketLowerBound.Result strengthened = state.bound(unconstrained, nextWidth);
					state.raiseLower(strengthened.lowerBound());
					state.stats.add("widthStrengthenings", 1);
					guidance = strengthened;
					double gain = state.lower - before;
					boolean useful = meaningfulBoundGain(gain, residual,
						state.options.common().relativeTolerance());
					state.publish("ANYTIME_TARGET_BOUND", "width=" + nextWidth
						+ " rawLower=" + strengthened.lowerBound() + " deltaL=" + gain
						+ " residualBefore=" + residual + " continueWidths=" + useful);
					nextWidth++;
					boundAvailable = useful && nextWidth <= state.options.common().maximumWidth();
					if(!useful) {
						deferredWidths = nextWidth <= state.options.common().maximumWidth();
						state.stats.add("boundWidthSuspensions", 1);
						if(gain == 0d)
							state.stats.add("zeroGainActions", 1);
					}
				}
				catch(MiniBucketLowerBound.ResourceLimitException limited) {
					state.stats.add("resourceFailures", 1);
					state.stats.add("boundWidthSuspensions", 1);
					boundAvailable = false;
					state.publish("ANYTIME_TARGET_BOUND_RESOURCE", "width=" + nextWidth);
				}
				if(state.reached())
					return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
				if(state.expired())
					return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
			}

			if(region.size() < maximumRegion) {
				int target = saturatedTarget(region.size(), nextGrowth, maximumRegion);
				state.problem.growRegion(region, target,
					guidance == null ? java.util.List.of() : guidance.conflicts());
				if(region.size() < target)
					throw new IllegalStateException("ANYTIME_TARGET_COVERAGE_STALLED");
				state.stats.add("coverageAttempts", 1);
				state.stats.set("coverageVariables", region.size());
				state.stats.add("regionActions", 1);
				double before = state.upper;
				try {
					RegionalSearchProblem.Solution solution = state.region(unconstrained, region, state.assignment);
					if(region.size() == state.problem.decisionCount()) {
						if(!solution.feasible() || solution.objective() > before)
							throw new IllegalStateException("ANYTIME_TARGET_GLOBAL_RESULT_INVALID");
						state.accept(solution);
						state.raiseLower(state.upper);
						state.publish("ANYTIME_TARGET_GLOBAL_EXACT", "region=" + region.size()
							+ " work=" + solution.statistics().eliminationAssignments());
						return state.finish(RegionalSearchOptimizer.StopReason.GLOBAL_EXACT);
					}
					boolean improved = state.accept(solution);
					double gain = before - state.upper;
					if(improved)
						nextGrowth = baseGrowth;
					else {
						nextGrowth = acceleratedGrowth(nextGrowth, maximumRegion - region.size());
						state.stats.add("zeroGainActions", 1);
						state.stats.add("zeroUbGrowthAccelerations", 1);
					}
					state.publish("ANYTIME_TARGET_REGION", "region=" + region.size()
						+ " improved=" + improved + " deltaU=" + gain + " nextGrowth=" + nextGrowth);
				}
				catch(IllegalArgumentException failure) {
					if(!RegionalSearchProblem.isResourceLimit(failure))
						throw failure;
					state.stats.add("resourceFailures", 1);
					nextGrowth = acceleratedGrowth(nextGrowth, maximumRegion - region.size());
					state.stats.add("zeroUbGrowthAccelerations", 1);
					state.publish("ANYTIME_TARGET_REGION_SKIPPED", "region=" + region.size()
						+ " nextGrowth=" + nextGrowth + " reason=" + failure.getMessage());
				}
				if(state.reached())
					return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
				if(state.expired())
					return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
			}

			if(region.size() >= maximumRegion && !boundAvailable && deferredWidths) {
				// A flat width does not prove that every larger relaxation is flat.
				// Defer the remaining finite width schedule while improving U, then
				// resume it before declaring that both progress paths are exhausted.
				boundAvailable = true;
				deferredWidths = false;
				state.stats.add("boundWidthResumptions", 1);
				state.publish("ANYTIME_TARGET_BOUND_RESUMED", "nextWidth=" + nextWidth);
			}
			if(region.size() >= maximumRegion && !boundAvailable)
				return state.finish(maximumRegion < state.problem.decisionCount()
					? RegionalSearchOptimizer.StopReason.REGION_LIMIT
					: RegionalSearchOptimizer.StopReason.RESOURCE_LIMIT);
		}
	}

	private static RegionalSearchOptimizer.Result solveWhole(RegionalSearchOptimizer.State state,
		int[] unconstrained, RegionalSearchProblem.RegionalWork work) {
		state.stats.add("wholeClosureAttempts", 1);
		RegionalSearchProblem.Solution solution = state.whole(unconstrained);
		if(!solution.feasible() || solution.objective() > state.upper)
			throw new IllegalStateException("ANYTIME_TARGET_GLOBAL_RESULT_INVALID");
		state.accept(solution);
		state.raiseLower(state.upper);
		state.stats.add("wholeClosureCompleted", 1);
		state.publish("ANYTIME_TARGET_GLOBAL_EXACT", workDetails(work));
		return state.finish(RegionalSearchOptimizer.StopReason.GLOBAL_EXACT);
	}

	static boolean meaningfulBoundGain(double deltaLower, double residual, double relativeTolerance) {
		return deltaLower > 0d && (1d + relativeTolerance) * deltaLower
			>= MINIMUM_RESIDUAL_REDUCTION * residual;
	}

	private static double relativeResidual(double upper, double lower, double relativeTolerance) {
		return Math.max(0d, upper - (1d + relativeTolerance) * lower);
	}

	private static int saturatedTarget(int current, int growth, int maximum) {
		return (int) Math.min(maximum, Math.min((long) Integer.MAX_VALUE, (long) current + growth));
	}

	private static int acceleratedGrowth(int current, int remaining) {
		if(remaining <= 0)
			return 1;
		return (int) Math.min(remaining, Math.min((long) Integer.MAX_VALUE, Math.max(1L, 2L * current)));
	}

	private static String workDetails(RegionalSearchProblem.RegionalWork work) {
		return "admitted=" + work.admitted() + " hardResource=" + work.hardResourceLimited()
			+ " assignments=" + work.eliminationAssignments() + " materializedCells="
			+ work.materializedFactorCells() + " maxFactorCells=" + work.maximumFactorCells();
	}
}
