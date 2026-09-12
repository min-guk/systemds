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

import java.util.List;

/** Initial global replica bound followed by exact completion of its unresolved couplings. */
final class RemainingExactOptimizer {
	private RemainingExactOptimizer() { }

	static RegionalSearchOptimizer.Result run(RegionalSearchOptimizer.State state) {
		if(state.expired())
			return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
		if(state.options.exactClosureAssignments() <= 0)
			throw new MiniBucketLowerBound.ResourceLimitException("REMAINING_EXACT_DISABLED_BY_WORK_LIMIT");
		boolean reusedRoot = state.problem.hasReducedRoot();
		long started = System.nanoTime();
		ExactPhysicalReducedSolver.CompactModel reduced;
		try {
			// Same domain reduction and quotient as Global compact=false. Every
			// singleton remains a variable; this does not invoke compactModel().
			reduced = state.problem.reducedRoot(state.options.common().limits());
		}
		finally { state.stats.add("rootReductionNanos", System.nanoTime() - started); }
		state.stats.set("rootReductionReused", reusedRoot ? 1 : 0);
		state.stats.set("sharedRootPreparationNanos", state.problem.reducedRootNanos());
		state.stats.set("sharedRootRequests", state.problem.reducedRootRequests());
		state.stats.set("rootEncodedVariables", state.problem.variables().size());
		state.stats.set("rootRetainedVariables", reduced.variables().size());
		state.stats.set("rootSingletonCompaction", 0);
		if(reduced.variables().size() != state.problem.variables().size())
			throw new IllegalStateException("REMAINING_EXACT_UNEXPECTED_VARIABLE_REMOVAL");
		RegionalBoundModelExport.configured(state, reduced);
		String initialBound = System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "initialBound", "replica");
		if(!initialBound.equals("replica") && !initialBound.equals("region-dual"))
			throw new IllegalArgumentException("REGIONAL_INITIAL_BOUND_INVALID|value=" + initialBound);
		if(initialBound.equals("region-dual")) {
			initialRegionDual(state, reduced);
			if(state.reached())
				return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
			if(state.expired())
				return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
		}
		started = System.nanoTime();
		IncrementalReplicaBound bound;
		state.stats.add("boundCalls", 1);
		try {
			bound = IncrementalReplicaBound.create(reduced.variables(), reduced.factors(),
				state.options.common().initialWidth(), state.options.common().limits(),
				state.options.exactClosureAssignments(), false, state.options.lbPlanning(), state::expired);
		}
		finally { state.stats.add("boundNanos", System.nanoTime() - started); }
		state.stats.set("initialComponents", bound.componentCount());
		state.stats.set("partitionPlanningNanos", bound.partitionPlanningNanos());
		state.stats.set("boundAssignments", bound.workStats().assignments());
		state.stats.set("boundMaterializedCells", bound.workStats().materializedCells());
		state.raiseLower(bound.lowerBound());
		recordWork(state, bound);
		state.publish(initialBound.equals("region-dual") ? "FALLBACK_REPLICA_BOUND" : "INITIAL_BOUND",
			"compact=false exactReduction=true partition=" + bound.partitionIdentity()
			+ " lbOrder=" + state.options.lbPlanning().eliminationOrder()
			+ " lbPartition=" + state.options.lbPlanning().partitionStrategy()
			+ " eliminationOrder=" + bound.eliminationOrder().toString().replace(" ", ""));
		if(state.reached())
			return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
		if(state.expired())
			return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);

		if(state.options.costShiftMillis() > 0) {
			tightenCosts(state, reduced);
			if(state.reached())
				return state.finish(RegionalSearchOptimizer.StopReason.TARGET_REACHED);
			if(state.expired())
				return state.finish(RegionalSearchOptimizer.StopReason.TIME_BUDGET);
		}

		IncrementalReplicaBound.Work before = bound.workStats();
		started = System.nanoTime();
		state.stats.add("remainingClosureAttempts", 1);
		try {
			IncrementalReplicaBound.Closure closure = bound.closeRemaining(state::expired);
			state.stats.set("remainingUnresolvedVariables", closure.unresolvedVariables());
			state.stats.set("remainingAffectedComponents", closure.affectedComponents());
			state.stats.set("remainingSolvedComponents", closure.solvedComponents());
			state.stats.set("remainingReusedComponents", closure.reusedComponents());
			state.stats.set("remainingReusedOriginalVariables", closure.reusedOriginalVariables());
			state.stats.set("remainingReusedAssignments", closure.reusedAssignments());
			state.stats.set("remainingLargestOriginalVariables", closure.largestOriginalVariables());
		}
		finally {
			state.stats.add("exactNanos", System.nanoTime() - started);
			recordWork(state, bound);
			state.stats.add("exactCalls", bound.workStats().calls() - before.calls());
			state.stats.add("exactAssignments", bound.workStats().assignments() - before.assignments());
			// Completed component groups remain valid even if a later group is
			// interrupted. No unfinished component is omitted from this bound.
			state.raiseLower(bound.lowerBound());
		}
		if(!bound.fullyRestored())
			throw new IllegalStateException("REMAINING_EXACT_INCOMPLETE_CLOSURE");
		List<Integer> encoded = reduced.expandAssignment(bound.suggestedAssignment(false));
		List<Integer> decisions = List.copyOf(encoded.subList(0, state.problem.decisionCount()));
		double exact = bound.exactObjective();
		double original = CertifiedRegionalOptimizer.evaluate(
			state.problem.variables(), state.problem.factors(), encoded);
		double canonical = state.problem.evaluate(decisions);
		if(!Double.isFinite(exact)
			|| Double.doubleToRawLongBits(exact) != Double.doubleToRawLongBits(original)
			|| Double.doubleToRawLongBits(exact) != Double.doubleToRawLongBits(canonical))
			throw new IllegalStateException("REMAINING_EXACT_CANONICAL_MISMATCH|exact=" + exact
				+ "|encoded=" + original + "|canonical=" + canonical);
		if(exact > state.upper)
			throw new IllegalStateException("REMAINING_EXACT_INFERIOR_TO_INCUMBENT");
		state.accept(new RegionalSearchProblem.Solution(true, canonical, decisions, null));
		state.raiseLower(state.upper);
		state.stats.set("remainingClosureCompleted", 1);
		state.publish("REMAINING_EXACT", "compact=false allReplicaEqualitiesRestored=true");
		return state.finish(RegionalSearchOptimizer.StopReason.GLOBAL_EXACT);
	}

	private static void initialRegionDual(RegionalSearchOptimizer.State state,
		ExactPhysicalReducedSolver.CompactModel reduced) {
		String prefix = CertifiedRegionalOptimizer.PROPERTY_PREFIX;
		long millis = Long.parseLong(System.getProperty(prefix + "regionDualMillis", "100"));
		long cells = Long.parseLong(System.getProperty(prefix + "regionDualCells", "1024"));
		int sweeps = Integer.parseInt(System.getProperty(prefix + "regionDualSweeps", "1000"));
		String merge = System.getProperty(prefix + "regionDualMerge", "true");
		if(millis < 0 || (!merge.equals("true") && !merge.equals("false")))
			throw new IllegalArgumentException("REGIONAL_DUAL_OPTIONS_INVALID");
		long nanos = millis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
		RegionDualLowerBound.Options options = new RegionDualLowerBound.Options(Boolean.parseBoolean(merge),
			cells, state.options.common().limits().maximumMaterializedCells(), cells, sweeps,
			Long.MAX_VALUE, nanos);
		long started = System.nanoTime();
		RegionDualLowerBound.Result result;
		state.stats.add("boundCalls", 1);
		try {
			result = RegionDualLowerBound.compute(reduced.variables(), reduced.factors(), options, state::expired,
				candidate -> RegionalSearchOptimizer.gap(Math.max(state.lower, candidate), state.upper)
					<= state.options.common().absoluteTolerance()
					|| RegionalSearchOptimizer.relative(Math.max(state.lower, candidate), state.upper)
					<= state.options.common().relativeTolerance(), state::raiseLower);
		}
		finally {
			long elapsed = System.nanoTime() - started;
			state.stats.add("boundNanos", elapsed);
			state.stats.add("regionDualNanos", elapsed);
		}
		state.raiseLower(result.lowerBound());
		RegionDualLowerBound.Statistics work = result.statistics();
		state.stats.set("regionDualPreparationNanos", work.preparationNanos());
		state.stats.set("regionDualUpdateNanos", work.updateNanos());
		state.stats.set("regionDualUpdates", work.updates());
		state.stats.set("regionDualSweeps", work.sweeps());
		state.stats.set("regionDualScannedCells", work.scannedCells());
		state.stats.set("regionDualMaterializedCells", work.materializedCells());
		state.stats.set("regionDualIterativeCells", work.iterativeCells());
		state.stats.set("regionDualMaxIterativeClusterCells", work.maximumIterativeClusterCells());
		state.publish("INITIAL_BOUND", "initialBound=region-dual compact=false originalFactorCover=true"
			+ " regionDualReason=" + result.reason() + " regionDualMillis=" + millis
			+ " regionDualCells=" + cells + " regionDualMerge=" + merge
			+ " regionDualClusters=" + (result.prepared() == null ? 0 : result.prepared().clusters().size())
			+ " regionDualSeparators=" + (result.prepared() == null ? 0 : result.prepared().separators().size()));
	}

	private static void tightenCosts(RegionalSearchOptimizer.State state,
		ExactPhysicalReducedSolver.CompactModel reduced) {
		long millis = state.options.costShiftMillis();
		long budget = millis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
		long started = System.nanoTime();
		state.stats.add("costShiftCalls", 1);
		CostShiftLowerBound.Result result;
		try {
			result = CostShiftLowerBound.compute(reduced.variables(), reduced.factors(),
				state.options.common().initialWidth(), state.options.common().limits(), budget,
				state.options.costShiftMaxSweeps(), Long.MAX_VALUE, state::expired,
				candidate -> RegionalSearchOptimizer.gap(Math.max(state.lower, candidate), state.upper)
					<= state.options.common().absoluteTolerance()
					|| RegionalSearchOptimizer.relative(Math.max(state.lower, candidate), state.upper)
					<= state.options.common().relativeTolerance(),
				state::raiseLower);
		}
		finally { state.stats.add("costShiftNanos", System.nanoTime() - started); }
		state.raiseLower(result.lowerBound());
		CostShiftLowerBound.Statistics work = result.statistics();
		state.stats.set("costShiftPreparationNanos", work.preparationNanos());
		state.stats.set("costShiftUpdateNanos", work.updateNanos());
		state.stats.set("costShiftScannedCells", work.scannedCells());
		state.stats.set("costShiftUpdates", work.updates());
		state.stats.set("costShiftSweeps", work.sweeps());
		state.stats.set("costShiftMaterializedCells", work.materializedCells());
		state.stats.set("costShiftMaxFactorCells", work.maxFactorCells());
		state.stats.set("costShiftCancelled", work.cancelled() ? 1 : 0);
		state.stats.set("costShiftResourceLimited", work.resourceLimited() ? 1 : 0);
		state.publish("COST_SHIFT", "costShiftReason=" + result.reason()
			+ " costShiftBudgetMillis=" + millis + " fixedScopes=true");
	}

	private static void recordWork(RegionalSearchOptimizer.State state, IncrementalReplicaBound bound) {
		IncrementalReplicaBound.Work work = bound.workStats();
		state.stats.set("replicaVariables", bound.replicaVariables());
		state.stats.set("components", bound.componentCount());
		state.stats.set("fullyRestored", bound.fullyRestored() ? 1 : 0);
		state.stats.set("restoredEqualities", bound.restoredEqualities());
		state.stats.set("componentSolveCalls", work.calls());
		state.stats.set("componentAssignments", work.assignments());
		state.stats.set("componentMaterializedCells", work.materializedCells());
		state.stats.set("componentPreparationNanos", work.preparationNanos());
		state.stats.set("componentSolveNanos", work.solveNanos());
		state.stats.max("maxFactorCells", work.maximumFactorCells());
	}
}
