/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Incremental global relaxation tightening with independent component reuse. */
final class IncrementalAnytimeOptimizer {
	private IncrementalAnytimeOptimizer() { }

	record Initialization(ExactPhysicalReducedSolver.CompactModel compact,
		IncrementalReplicaBound bound, long reductionNanos, long initialBoundNanos,
		long projectionNanos, List<Integer> projectedSeed, double projectedCost,
		Map<List<Integer>,Double> candidateCosts) {
		Initialization {
			projectedSeed = List.copyOf(projectedSeed);
			candidateCosts = new LinkedHashMap<>(candidateCosts);
		}
		boolean hasProjectedSeed() { return Double.isFinite(projectedCost); }
	}

	static Initialization initialize(RegionalSearchProblem problem, RegionalSearchOptimizer.Options options) {
		long started = System.nanoTime();
		ExactPhysicalReducedSolver.CompactModel compact = problem.compactRoot(options.common().limits());
		long reductionNanos = System.nanoTime() - started;
		started = System.nanoTime();
		IncrementalReplicaBound bound = IncrementalReplicaBound.create(compact.variables(), compact.factors(),
			options.common().initialWidth(), options.common().limits(), options.regionWorkLimit(),
			() -> Thread.currentThread().isInterrupted());
		long initialBoundNanos = System.nanoTime() - started;
		started = System.nanoTime();
		List<Integer> best = List.of();
		double bestCost = Double.POSITIVE_INFINITY;
		Map<List<Integer>,Double> costs = new LinkedHashMap<>();
		for(boolean majority : new boolean[] {false, true}) {
			List<Integer> candidate = expandDecisions(problem, compact, bound.suggestedAssignment(majority));
			if(candidate.equals(best))
				continue;
			double cost = candidateCost(problem, candidate);
			costs.put(candidate, cost);
			if(cost < bestCost) {
				best = candidate;
				bestCost = cost;
			}
		}
		return new Initialization(compact, bound, reductionNanos, initialBoundNanos,
			System.nanoTime() - started, best, bestCost, costs);
	}

	static RegionalSearchOptimizer.Result optimize(RegionalSearchProblem problem, List<Integer> seed,
		RegionalSearchOptimizer.Options options, Consumer<RegionalSearchOptimizer.Checkpoint> observer,
		Initialization initialized) {
		return optimize(problem, seed, options, observer, initialized, 0L);
	}

	static RegionalSearchOptimizer.Result optimize(RegionalSearchProblem problem, List<Integer> seed,
		RegionalSearchOptimizer.Options options, Consumer<RegionalSearchOptimizer.Checkpoint> observer,
		Initialization initialized, long orderedSeedNanos) {
		RegionalSearchOptimizer.State state = new RegionalSearchOptimizer.State(problem, seed, options, observer);
		state.stats.set("orderedSeedNanos", orderedSeedNanos);
		state.publish("INITIAL");
		return run(state, initialized);
	}

	static RegionalSearchOptimizer.Result run(RegionalSearchOptimizer.State state, Initialization initialized) {
		IncrementalReplicaBound bound = initialized.bound();
		state.stats.add("diagnosticNanos", initialized.reductionNanos());
		state.stats.add("compactReductionNanos", initialized.reductionNanos());
		state.stats.add("boundNanos", initialized.initialBoundNanos());
		state.stats.add("boundCalls", 1);
		state.stats.add("projectionNanos", initialized.projectionNanos());
		state.stats.set("compactVariables", initialized.compact().variables().size());
		state.stats.set("initialComponents", bound.componentCount());
		state.stats.set("projectionSeedAvailable", initialized.hasProjectedSeed() ? 1 : 0);
		state.raiseLower(bound.lowerBound());
		recordWork(state, bound);
		trySuggestions(state, initialized);
		state.publish("INITIAL_BOUND", details(bound));
		if(state.reached())
			return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);

		Set<Integer> lastRegion = Set.of();
		long primalAttempts = 0;
		try {
			for(int step = 0; step < state.options.maxSteps(); step++) {
				state.stats.set("steps", step + 1L);
				if(state.expired())
					return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
				long started = System.nanoTime();
				state.stats.add("boundActions", 1);
				IncrementalReplicaBound.Refinement refined;
				try {
					refined = bound.refine(state.options.probeCandidates(), state::expired);
				}
				finally {
					state.stats.add("boundNanos", System.nanoTime() - started);
					recordWork(state, bound);
				}
				double previousLower = state.lower;
				double previousUpper = state.upper;
				state.raiseLower(bound.lowerBound());
				trySuggestions(state, initialized);
				IncrementalReplicaBound.Selection selected = refined.selection();
				state.stats.set("selectedMinorityGroups", selected.modalMinorityGroups());
				state.stats.set("selectedReplicaGroups", selected.representativeGroups());
				state.stats.set("selectedTouchedComponents", selected.touchedComponents());
				state.stats.set("selectedCachedAssignments", selected.cachedAssignments());
				state.stats.set("selectedPlannedAssignments", selected.plannedAssignments());
				state.stats.set("selectedTrialNanos", selected.measuredNanos());
				if(refined.changed() && state.lower == previousLower) {
					state.stats.add("zeroBoundGainActions", 1);
					if(state.upper == previousUpper)
						state.stats.add("zeroGainActions", 1);
				}
				state.publish("INCREMENTAL_LOWER", details(bound)
					+ " changed=" + refined.changed() + " variable=" + refined.originalVariable()
					+ " deltaL=" + (state.lower - previousLower)
					+ " selectionPriority=" + ((double) selected.modalMinorityGroups()
						/ Math.max(1L, selected.cachedAssignments())));
				if(state.reached())
					return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
				if(state.expired())
					return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);

				// A restricted primal solve changes only U. It is never used as a
				// global bound. Its footprint comes from the same restored coupling.
				boolean repairDue = step == 0 || !refined.changed()
					|| (step + 1) % state.options.coveragePeriod() == 0;
				if(repairDue && primalAttempts < state.options.incumbentRescueAttempts()) {
					Set<Integer> region = primalRegion(state, initialized.compact(), refined);
					if(!region.isEmpty() && !region.equals(lastRegion)) {
						lastRegion = Set.copyOf(region);
						primalAttempts++;
						state.stats.add("regionActions", 1);
						double before = state.upper;
						try {
							state.accept(state.region(state.problem.unconstrained(), region, state.assignment));
						}
						catch(IllegalArgumentException limited) {
							if(!RegionalSearchProblem.isResourceLimit(limited))
								throw limited;
							state.stats.add("resourceFailures", 1);
						}
						state.publish("INCREMENTAL_PRIMAL", "region=" + region.size()
							+ " deltaU=" + (before - state.upper) + " lowerUnchanged=true");
						if(state.reached())
							return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
					}
				}
				if(!refined.changed() && !bound.hasRefinementCandidates())
					return state.finish(RegionalSearchOptimizer.StopReason.RESOURCE_LIMIT);
			}
			return state.finish(RegionalSearchOptimizer.StopReason.STEP_LIMIT);
		}
		catch(CancellationException cancelled) {
			return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
		}
	}

	private static void trySuggestions(RegionalSearchOptimizer.State state, Initialization initialized) {
		long started = System.nanoTime();
		try {
			for(boolean majority : new boolean[] {false, true}) {
				List<Integer> candidate = expandDecisions(state.problem, initialized.compact(),
					initialized.bound().suggestedAssignment(majority));
				if(candidate.equals(state.assignment))
					continue;
				state.stats.add("projectionAttempts", 1);
				Double cached = initialized.candidateCosts().get(candidate);
				double cost;
				if(cached != null) {
					state.stats.add("projectionCacheHits", 1);
					cost = cached;
				}
				else {
					cost = candidateCost(state.problem, candidate);
					// Cache entries are only original assignments and canonical costs;
					// no solver tables or unresolved search coverage are discarded.
					if(initialized.candidateCosts().size() >= 256)
						initialized.candidateCosts().remove(initialized.candidateCosts().keySet().iterator().next());
					initialized.candidateCosts().put(candidate, cost);
				}
				if(Double.isFinite(cost) && state.accept(new RegionalSearchProblem.Solution(true, cost, candidate, null)))
					state.stats.add("projectionImprovements", 1);
			}
		}
		finally { state.stats.add("projectionNanos", System.nanoTime() - started); }
	}

	private static List<Integer> expandDecisions(RegionalSearchProblem problem,
		ExactPhysicalReducedSolver.CompactModel compact, List<Integer> assignment) {
		return List.copyOf(compact.expandAssignment(assignment).subList(0, problem.decisionCount()));
	}

	private static double candidateCost(RegionalSearchProblem problem, List<Integer> candidate) {
		try { return problem.evaluate(candidate); }
		catch(IllegalArgumentException failure) {
			if(failure.getMessage() != null && failure.getMessage().startsWith("REGIONAL_SEARCH_PLAN_NOT_FEASIBLE|"))
				return Double.POSITIVE_INFINITY;
			throw failure;
		}
	}

	private static Set<Integer> primalRegion(RegionalSearchOptimizer.State state,
		ExactPhysicalReducedSolver.CompactModel compact, IncrementalReplicaBound.Refinement refined) {
		Set<Integer> active = new LinkedHashSet<>();
		for(ExactCategoricalSolver.Variable source : compact.sourceVariables()) {
			int index = state.problem.indexOf(source);
			if(index >= 0 && index < state.problem.decisionCount())
				active.add(index);
		}
		int maximum = Math.min(state.options.common().maximumRegionVariables(),
			Math.min(state.options.common().regionGrowth(), Math.max(0, active.size() - 1)));
		Set<Integer> region = new LinkedHashSet<>();
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		if(refined.originalVariable() >= 0)
			queue.add(refined.originalVariable());
		Set<Integer> visited = new LinkedHashSet<>();
		while(!queue.isEmpty() && region.size() < maximum) {
			int index = queue.removeFirst();
			if(!visited.add(index))
				continue;
			int source = state.problem.indexOf(compact.sourceVariables().get(index));
			if(active.contains(source))
				region.add(source);
			ExactCategoricalSolver.Variable variable = compact.variables().get(index);
			for(ExactCategoricalSolver.Factor factor : compact.factors())
				if(factor.scope().contains(variable))
					for(ExactCategoricalSolver.Variable neighbor : factor.scope()) {
						int next = compact.variables().indexOf(neighbor);
						if(!visited.contains(next))
							queue.addLast(next);
					}
		}
		return region;
	}

	private static void recordWork(RegionalSearchOptimizer.State state, IncrementalReplicaBound bound) {
		state.stats.set("replicaVariables", bound.replicaVariables());
		state.stats.set("components", bound.componentCount());
		state.stats.max("maximumComponentVariables", bound.maximumComponentVariables());
		state.stats.set("restoredEqualities", bound.restoredEqualities());
		state.stats.set("fullyRestored", bound.fullyRestored() ? 1 : 0);
		IncrementalReplicaBound.Work work = bound.workStats();
		state.stats.set("componentSolveCalls", work.calls());
		state.stats.set("componentAssignments", work.assignments());
		state.stats.set("componentMaterializedCells", work.materializedCells());
		state.stats.set("componentPreparationNanos", work.preparationNanos());
		state.stats.set("componentSolveNanos", work.solveNanos());
		state.stats.set("componentProbes", work.probes());
		state.stats.set("reusedComponents", work.reusedComponents());
		state.stats.set("componentResourceSkips", work.resourceSkips());
		state.stats.set("boundAssignments", work.assignments());
		state.stats.set("boundMaterializedCells", work.materializedCells());
		state.stats.max("maxFactorCells", work.maximumFactorCells());
	}

	private static String details(IncrementalReplicaBound bound) {
		return "partition=" + bound.partitionIdentity() + " components=" + bound.componentCount()
			+ " maximumComponentVariables=" + bound.maximumComponentVariables()
			+ " equalities=" + bound.restoredEqualities() + " fullyRestored=" + bound.fullyRestored();
	}
}
