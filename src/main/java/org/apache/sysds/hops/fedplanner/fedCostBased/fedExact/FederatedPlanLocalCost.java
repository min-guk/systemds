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
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
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
		Objects.requireNonNull(prog, "prog");
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertCanonicalProgramAuthority(prog);
		analysis.assertProgramStructureUnchanged();

		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		CertifiedRegionalOptimizer.Options options = CertifiedRegionalOptimizer.Options.configured();
		if(options != null && FederatedPlannerTrace.isEnabled())
			FederatedPlannerTrace.logGlobal("DP-RegionalCertificate", String.format(Locale.ROOT,
				"phase=CONFIG policy=%s expandRegions=%s refineBound=%s width=%d maxWidth=%d rounds=%d "
					+ "regionGrowth=%d maxRegion=%d timeMillis=%d factorCells=%d totalCells=%d seed=%d",
				options.policy(), options.expandRegions(), options.refineBound(), options.initialWidth(),
				options.maximumWidth(), options.rounds(), options.regionGrowth(), options.maximumRegionVariables(),
				options.timeBudgetMillis(), options.limits().maximumFactorCells(),
				options.limits().maximumMaterializedCells(), options.seed()));
		LocalPhysicalOptimizer.Result optimized = LocalPhysicalOptimizer.optimize(model, surface,
			options, FederatedPlanLocalCost::traceCheckpoint);
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(
			model, optimized.physicalResult());
		trace(selection, model, surface, optimized.localStatistics());
		if(optimized.certificate() != null && FederatedPlannerTrace.isEnabled()) {
			CertifiedRegionalOptimizer.Result certificate = optimized.certificate();
			FederatedPlannerTrace.logGlobal("DP-RegionalCertificate", String.format(Locale.ROOT,
				"phase=FINAL lower=%.17g upper=%.17g gap=%.17g relativeGap=%.17g stop=%s "
					+ "scope=encoded-model costFingerprint=%s analysis=%s",
				certificate.lowerBound(), certificate.upperBound(), certificate.absoluteGap(),
				certificate.relativeGap(), certificate.stopReason(), selection.costSurfaceFingerprint(),
				selection.analysisFingerprint()));
		}
		ExactPlacementInput input = ExactPhysicalPlacementProjector.project(
			selection, "DP-LocalConflict", "local-conflict");
		adapter.select(analysis, input);
		NormalizedPlannerResult normalized = Objects.requireNonNull(input.normalizedResult(),
			"local physical projector normalized result");
		return input.withEmissionReceipt(PlacementEmissionTransaction.emit(prog, normalized,
			PlacementEmissionTransaction.FailureInjector.none()));
	}

	private static void traceCheckpoint(CertifiedRegionalOptimizer.Checkpoint checkpoint) {
		if(!FederatedPlannerTrace.isEnabled())
			return;
		FederatedPlannerTrace.logGlobal("DP-RegionalCertificate", String.format(Locale.ROOT,
			"iteration=%d phase=%s width=%d regionVariables=%d rawLower=%.17g lower=%.17g "
				+ "upper=%.17g gap=%.17g relativeGap=%.17g elapsedMs=%.6f boundMs=%.6f "
				+ "regionMs=%.6f splitBuckets=%d disagreements=%d maxFactorCells=%d "
				+ "boundMaterializedCells=%d boundAssignments=%d regionAssignments=%d improved=%s",
			checkpoint.iteration(), checkpoint.phase(), checkpoint.width(), checkpoint.regionVariables(),
			checkpoint.rawLowerBound(), checkpoint.lowerBound(), checkpoint.upperBound(),
			checkpoint.absoluteGap(), checkpoint.relativeGap(), checkpoint.elapsedNanos() / 1e6,
			checkpoint.boundNanos() / 1e6, checkpoint.regionNanos() / 1e6, checkpoint.splitBuckets(),
			checkpoint.disagreements(), checkpoint.maximumFactorCells(), checkpoint.boundMaterializedCells(),
			checkpoint.boundAssignments(), checkpoint.regionAssignments(), checkpoint.improved()));
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
				+ "factorizedCompilations=%d factorizedSolves=%d factorwiseSkips=%d "
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
