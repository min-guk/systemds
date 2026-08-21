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
 * of factor-local interaction blocks.</p>
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
		LocalPhysicalOptimizer.Result optimized = LocalPhysicalOptimizer.optimize(model, surface);
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(
			model, optimized.physicalResult());
		trace(selection, model, surface, optimized.localStatistics());
		ExactPlacementInput input = ExactPhysicalPlacementProjector.project(
			selection, "DP-LocalConflict", "local-conflict");
		adapter.select(analysis, input);
		NormalizedPlannerResult normalized = Objects.requireNonNull(input.normalizedResult(),
			"local physical projector normalized result");
		return input.withEmissionReceipt(PlacementEmissionTransaction.emit(prog, normalized,
			PlacementEmissionTransaction.FailureInjector.none()));
	}

	private static void trace(ExactPhysicalSelection selection, ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		LocalCategoricalOptimizer.Statistics statistics) {
		if(!FederatedPlannerTrace.isEnabled())
			return;
		FederatedPlannerTrace.logGlobal("DP-LocalConflict", String.format(Locale.ROOT,
			"objective=%.12f variables=%d hardFactors=%d costFactors=%d transfers=%d "
				+ "rawStates=%d retainedStates=%d prunedRepresentatives=%d "
				+ "initialConflicts=%d conflictBlocks=%d blockExpansions=%d "
				+ "localBlocks=%d localImprovements=%d localRevisits=%d maxBlockVariables=%d "
				+ "maxBlockAssignments=%d blockAssignments=%d costFingerprint=%s analysis=%s",
			selection.solverObjective(), model.variables().size(), model.hardFactors().size(),
			surface.factors().size(), surface.transferKeys().size(),
			statistics.rawLocalAlternatives(), statistics.retainedLocalStates(),
			statistics.prunedLocalRepresentatives(), statistics.initialHardViolations(),
			statistics.conflictBlocksSolved(), statistics.conflictBlockExpansions(),
			statistics.localBlocks(), statistics.localBlockImprovements(),
			statistics.localBlockRevisits(), statistics.maximumBlockVariables(),
			statistics.maximumBlockAssignments(),
			statistics.blockAssignments(), selection.costSurfaceFingerprint(),
			selection.analysisFingerprint()));
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
