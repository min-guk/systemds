/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Variable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact policy root over the canonical immutable placement analysis. */
public class FederatedPlanExact extends AFederatedPlanner {
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
		ExactPhysicalOptimizer.Result optimized = ExactPhysicalOptimizer.optimize(
			model, surface, ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		ExactPhysicalSelection selection = ExactPhysicalSelection.create(model, optimized);
		tracePhysicalSelection(model, surface, selection);
		ExactPlacementInput input = ExactPhysicalPlacementProjector.project(selection);
		adapter.select(analysis, input);
		NormalizedPlannerResult normalized = Objects.requireNonNull(input.normalizedResult(),
			"Exact projector normalized result");
		input = input.withEmissionReceipt(PlacementEmissionTransaction.emit(prog, normalized,
			PlacementEmissionTransaction.FailureInjector.none()));

		return input;
	}

	/**
	 * Emits a bounded certificate for the production categorical optimizer.  The
	 * per-alternative value is the exact change in factors incident to this variable
	 * while every other selected variable remains fixed; it is deliberately not
	 * presented as a separately re-optimized global objective.
	 */
	private static void tracePhysicalSelection(ExactPhysicalModel model,
		ExactPhysicalCostModel.PhysicalCostSurface surface,
		ExactPhysicalSelection selection) {
		if(!FederatedPlannerTrace.isEnabled())
			return;
		List<Integer> assignment = selection.assignmentInDecisionOrder();
		ExactCategoricalSolver.Statistics statistics = selection.statistics();
		FederatedPlannerTrace.logGlobal("Exact-PhysicalOptimize", String.format(Locale.ROOT,
			"objective=%.12f objectiveBits=%s variables=%d hardFactors=%d costFactors=%d transfers=%d "
				+ "inducedWidth=%d maximumFactorCells=%d materializedFactorCells=%d "
				+ "maximumEliminationAssignments=%d eliminationAssignments=%d costFingerprint=%s analysis=%s",
			selection.solverObjective(), Long.toUnsignedString(selection.objectiveBits()),
			model.variables().size(), model.hardFactors().size(), surface.factors().size(),
			surface.transferKeys().size(), statistics.inducedWidth(), statistics.maximumFactorCells(),
			statistics.materializedFactorCells(), statistics.maximumEliminationAssignments(),
			statistics.eliminationAssignments(), selection.costSurfaceFingerprint(),
			selection.analysisFingerprint()));

		List<Factor> factors = new ArrayList<>(model.hardFactors());
		factors.addAll(surface.factors());
		List<Variable> variables = model.variables();
		IdentityHashMap<Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);

		for(int index = 0; index < model.domains().size(); index++) {
			DecisionDomain domain = model.domains().get(index);
			Hop hop = selection.analysis().hop(domain.node().key()).orElse(null);
			if(!FederatedPlannerTrace.shouldTrace(hop))
				continue;
			int selectedIndex = assignment.get(index);
			Alternative selected = domain.alternatives().get(selectedIndex);
			PlacementEmissionState emission = selection.selectedEmissionStates().get(selected.decision());
			double selectedIncident = fixedOthersIncidentCost(
				factors, domain.variable(), positions, assignment, selectedIndex);
			FederatedPlannerTrace.log(hop, "Exact-PhysicalSelect", String.format(Locale.ROOT,
				"variable=%d selectedIndex=%d domainSize=%d state=%s derivedFedFout=%s authority=%s "
					+ "fixedOthersIncident=%.12f inputs=%s signature=%s",
				index, selectedIndex, domain.alternatives().size(), selected.state().normalizedSignature(),
				emission != null && emission.derivedFedFout(), selected.authorityKind(), selectedIncident,
				selected.inputAuthorities().stream().map(authority -> authority.signature()).toList(),
				selected.signature()));

			int detailBudget = FederatedPlannerTrace.getMaxEdgeLogsPerHop();
			int logged = 0;
			for(int alternativeIndex = 0; alternativeIndex < domain.alternatives().size(); alternativeIndex++) {
				if(logged >= detailBudget)
					break;
				Alternative alternative = domain.alternatives().get(alternativeIndex);
				double incident = fixedOthersIncidentCost(
					factors, domain.variable(), positions, assignment, alternativeIndex);
				double delta = Double.isInfinite(incident) ? Double.POSITIVE_INFINITY
					: incident - selectedIncident;
				FederatedPlannerTrace.log(hop, "Exact-PhysicalAlternative", String.format(Locale.ROOT,
					"variable=%d alternativeIndex=%d selected=%s state=%s authority=%s "
						+ "fixedOthersIncident=%.12f fixedOthersDelta=%.12f feasibleWithFixedOthers=%s signature=%s",
					index, alternativeIndex, alternativeIndex == selectedIndex,
					alternative.state().normalizedSignature(), alternative.authorityKind(), incident, delta,
					Double.isFinite(incident), alternative.signature()));
				logged++;
			}
			int omitted = domain.alternatives().size() - logged;
			if(omitted > 0)
				FederatedPlannerTrace.log(hop, "Exact-PhysicalAlternativeSummary",
					"variable=" + index + " logged=" + logged + " omitted=" + omitted);
		}

		long selectedFed = selection.selectedStates().values().stream()
			.filter(state -> state.execType() == ExecType.FED).count();
		long selectedFout = selection.selectedStates().values().stream()
			.filter(state -> state.output() == FederatedOutput.FOUT).count();
		long selectedDerived = selection.selectedEmissionStates().values().stream()
			.filter(PlacementEmissionState::derivedFedFout).count();
		FederatedPlannerTrace.logGlobal("Exact-PhysicalComplete", String.format(Locale.ROOT,
			"decisions=%d selectedFED=%d selectedFOUT=%d selectedDerivedFOUT=%d candidates=%d "
				+ "relocationChoices=%d emittedRelocations=%d analysis=%s",
			selection.selectedStates().size(), selectedFed, selectedFout, selectedDerived,
			selection.candidateReceipts().size(), selection.relocationChoices().size(),
			selection.emittedRelocations().size(), selection.analysisFingerprint()));
	}

	private static double fixedOthersIncidentCost(List<Factor> factors, Variable variable,
		Map<Variable,Integer> positions, List<Integer> selectedAssignment, int variableValue) {
		ExactCompensatedCostSum sum = new ExactCompensatedCostSum();
		for(Factor factor : factors) {
			if(!factor.scope().contains(variable))
				continue;
			int[] local = new int[factor.scope().size()];
			for(int localIndex = 0; localIndex < local.length; localIndex++) {
				Variable scoped = factor.scope().get(localIndex);
				Integer globalIndex = positions.get(scoped);
				if(globalIndex == null)
					throw new IllegalArgumentException("EXACT_TRACE_FOREIGN_VARIABLE");
				local[localIndex] = scoped == variable ? variableValue : selectedAssignment.get(globalIndex);
			}
			double cost = factor.cost(local);
			if(cost == Double.POSITIVE_INFINITY)
				return cost;
			sum.addBits(Double.doubleToRawLongBits(cost), "EXACT_TRACE_INCIDENT_COST_INVALID",
				"EXACT_TRACE_INCIDENT_TOTAL_INVALID");
		}
		return Double.longBitsToDouble(sum.totalBits("EXACT_TRACE_INCIDENT_TOTAL_INVALID"));
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
		Objects.requireNonNull(analysis, "analysis");
		DMLProgram program = Objects.requireNonNull(function.getDMLProg(), "function program");
		return rewriteProgram(program, null, null, analysis);
	}
}
