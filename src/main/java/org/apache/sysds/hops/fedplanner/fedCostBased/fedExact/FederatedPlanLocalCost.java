/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.Locale;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementPlanApplication;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

/**
 * Production local cost planner over the canonical privacy-filtered physical model.
 *
 * <p>Exact and this planner share domains, feasibility, and cost factors. They differ
 * only in search: Exact performs global variable elimination, while this planner uses
 * one producer-before-consumer pass followed by exact, cost-decreasing optimization
 * of factor/shared-producer blocks. If the resulting plan materializes a selected
 * FOUT for a local input, it derives only that FOUT component and its direct boundary
 * fringe as a deferred conflict block. This bounded local refinement can escape a
 * materialization cost barrier without turning the search into global enumeration.</p>
 */
public final class FederatedPlanLocalCost extends AFederatedPlanner {
	private final ExactPlacementAdapter adapter = new ExactPlacementAdapter();

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes) {
		Objects.requireNonNull(prog, "prog");
		rewriteProgram(prog, fgraph, fcallSizes, prog.requirePlacementAnalysisAuthority());
	}

	@Override
	public ExactPlacementInput rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis) {
		long searchEntryStart = System.nanoTime();
		Objects.requireNonNull(prog, "prog");
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertCanonicalProgramAuthority(prog);
		analysis.assertProgramStructureUnchanged();

		long phaseStarted = System.nanoTime();
		long phaseOutputStarted = FederatedPlannerTrace.traceOutputNanos();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		CertifiedRegionalOptimizer.Options options = CertifiedRegionalOptimizer.Options.configured();
		RegionalSearchOptimizer.Options searchOptions = RegionalSearchOptimizer.Options.configured(options);
		ExactEliminationOrderPolicy.Configuration orderPolicy =
			ExactEliminationOrderPolicy.localConfigured(LocalCategoricalOptimizer.configuredCompaction());
		if(searchOptions != null && FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("DP-RegionalSearch", String.format(Locale.ROOT,
				"phase=CONFIG algorithm=%s width=%d timeMillis=%d factorCells=%d totalCells=%d "
					+ "absoluteTarget=%.17g relativeTarget=%.17g exactClosureAssignments=%d "
					+ "seedRevisitPasses=%d budgetScope=after-seed softDeadline=true "
					+ "remainingExactCompletion=true seedPolicy=regional sharedPreparation=%s "
					+ "costShiftMillis=%d costShiftMaxSweeps=%d lbOrder=%s lbPartition=%s "
					+ "initialBound=%s regionDualMillis=%s regionDualCells=%s regionDualSweeps=%s regionDualMerge=%s "
					+ "fastBlockOrder=%s fastBlockAssignments=%s "
					+ "fastOrder=%s fastOrderAssignments=%s fastOrderSource=%s",
				searchOptions.algorithm(), options.initialWidth(), options.timeBudgetMillis(),
				options.limits().maximumFactorCells(), options.limits().maximumMaterializedCells(),
				options.absoluteTolerance(), options.relativeTolerance(), searchOptions.exactClosureAssignments(),
				LocalPhysicalOptimizer.configuredSeedRevisitPasses(), SharedRegionalPreparation.configured(),
				searchOptions.costShiftMillis(), searchOptions.costShiftMaxSweeps(),
				searchOptions.lbPlanning().eliminationOrder(), searchOptions.lbPlanning().partitionStrategy(),
				System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "initialBound", "replica"),
				System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "regionDualMillis", "100"),
				System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "regionDualCells", "1024"),
				System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "regionDualSweeps", "1000"),
				System.getProperty(CertifiedRegionalOptimizer.PROPERTY_PREFIX + "regionDualMerge", "true"),
				orderPolicy.fastOrder(), orderPolicy.maximumAssignments(),
				orderPolicy.fastOrder(), orderPolicy.maximumAssignments(), orderPolicy.source()));
		FederatedPlannerTrace.logPhaseTiming("MODEL_SETUP", phaseStarted, phaseOutputStarted);
		phaseStarted = System.nanoTime();
		phaseOutputStarted = FederatedPlannerTrace.traceOutputNanos();
		LocalPhysicalOptimizer.Result optimized = LocalPhysicalOptimizer.optimize(model, surface, searchOptions,
			checkpoint -> {
				if(FederatedPlannerTrace.isEnabled())
					FederatedPlannerTrace.logGlobal("DP-RegionalSearch", RegionalSearchOptimizer.checkpointTrace(checkpoint)
						+ String.format(Locale.ROOT, " methodElapsedMs=%.6f plannerElapsedNanos=%d",
							(System.nanoTime() - searchEntryStart) / 1e6,
							FederatedPlannerTrace.plannerElapsedNanos()));
			});
		FederatedPlannerTrace.logPhaseTiming("OPTIMIZATION", phaseStarted, phaseOutputStarted);
		phaseStarted = System.nanoTime();
		phaseOutputStarted = FederatedPlannerTrace.traceOutputNanos();
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(
			model, optimized.physicalResult());
		FederatedPlannerTrace.logPhaseTiming("SELECTION", phaseStarted, phaseOutputStarted);
		return PlacementPlanApplication.complete(prog, analysis,
			() -> {
				trace(selection, model, surface, optimized.localStatistics());
				if(optimized.search() != null && FederatedPlannerTrace.isEnabled()) {
					RegionalSearchOptimizer.Result search = optimized.search();
					FederatedPlannerTrace.logGlobal("DP-RegionalSearch", String.format(Locale.ROOT,
						"phase=FINAL algorithm=%s lower=%.17g upper=%.17g seedUpper=%.17g gap=%.17g relativeGap=%.17g "
							+ "targetReached=%s stop=%s elapsedMs=%.6f scope=encoded-model costFingerprint=%s analysis=%s "
							+ "orderFingerprint=%s methodElapsedMs=%.6f%s", search.algorithm(), search.lowerBound(), search.upperBound(),
						search.seedUpperBound(), search.absoluteGap(), search.relativeGap(), search.targetReached(), search.stopReason(),
						search.elapsedNanos() / 1e6, selection.costSurfaceFingerprint(), selection.analysisFingerprint(),
						search.orderFingerprint(), (System.nanoTime() - searchEntryStart) / 1e6,
						RegionalSearchOptimizer.statisticsTrace(search.statistics())));
				}
			},
			() -> {
				ExactPlacementInput input = ExactPhysicalPlacementProjector.project(
					selection, "DP-LocalConflict", "local-conflict");
				adapter.select(analysis, input);
				return input;
			}, ExactPlacementInput::normalizedResult,
			(input, normalized, emission) -> input.withEmissionReceipt(emission));
	}

	private static void trace(ExactPhysicalSelection selection, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		LocalCategoricalOptimizer.Statistics statistics) {
		if(!FederatedPlannerTrace.isEnabled())
			return;
		FederatedPlannerTrace.logGlobal("DP-LocalConflict", String.format(Locale.ROOT,
			"objective=%.12f unit=ms variables=%d hardFactors=%d costFactors=%d transfers=%d "
				+ "rawStates=%d retainedStates=%d prunedRepresentatives=%d "
				+ "initialConflicts=%d conflictBlocks=%d blockExpansions=%d "
				+ "localBlocks=%d localImprovements=%d localRevisits=%d "
				+ "factorizedCompilations=%d factorizedSolves=%d factorwiseSkips=%d factorwiseAssignments=%d "
				+ "maxBlockVariables=%d "
				+ "maxBlockAssignments=%d blockAssignments=%d costFingerprint=%s analysis=%s",
			selection.solverObjective(), model.variables().size(), model.hardFactors().size(),
			surface.factors().size(), surface.transferKeys().size(),
			statistics.rawLocalAlternatives(), statistics.retainedLocalStates(),
			statistics.prunedLocalRepresentatives(), statistics.initialHardViolations(),
			statistics.conflictBlocksSolved(), statistics.conflictBlockExpansions(),
			statistics.localBlocks(), statistics.localBlockImprovements(),
			statistics.localBlockRevisits(), statistics.factorizedBlockCompilations(),
			statistics.factorizedBlockSolves(), statistics.factorwiseMinimumSkips(),
			statistics.factorwiseMinimumAssignments(),
			statistics.maximumBlockVariables(),
			statistics.maximumBlockAssignments(),
			statistics.blockAssignments(), selection.costSurfaceFingerprint(),
			selection.analysisFingerprint()));
		ExactPhysicalCostModel.traceCanonicalContributions("DP", model.variables(),
			surface.contributions(), selection.assignmentInDecisionOrder(), selection.objectiveBits(),
			FederatedPlannerTrace::logGlobal);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		Objects.requireNonNull(function, "function");
		DMLProgram program = Objects.requireNonNull(function.getDMLProg(), "function program");
		rewriteFunctionDynamic(function, funcArgs, program.requirePlacementAnalysisAuthority());
	}

	public ExactPlacementInput rewriteFunctionDynamic(FunctionStatementBlock function,
		LocalVariableMap funcArgs, PlacementAnalysis analysis) {
		Objects.requireNonNull(function, "function");
		DMLProgram program = Objects.requireNonNull(function.getDMLProg(), "function program");
		return rewriteProgram(program, null, null, analysis);
	}
}
