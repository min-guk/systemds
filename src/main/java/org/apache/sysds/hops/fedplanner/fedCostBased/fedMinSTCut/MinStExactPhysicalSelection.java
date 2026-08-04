/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;

/**
 * Lossless physical MinST certificate.
 *
 * <p>The legacy cut selection stores only execution/output bits.  Those bits cannot
 * distinguish candidate rows with the same placement, nor the exact relocation
 * action selected for a PRESENT input.  This carrier therefore retains the selected
 * categorical alternative and exact compiler-owned receipts as first-class authority.</p>
 */
final class MinStExactPhysicalSelection {
	private final PlacementAnalysis analysis;
	private final String analysisFingerprint;
	private final String costSurfaceFingerprint;
	private final long objectiveBits;
	private final double solverObjective;
	private final List<Integer> assignmentInDecisionOrder;
	private final List<Alternative> alternativesInDecisionOrder;
	private final Map<CompiledHopKey,PlacementState> selectedStates;
	private final Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates;
	private final List<CandidateSelectionReceipt> candidateReceipts;
	private final List<RelocationChoiceReceipt> relocationChoices;
	private final List<RelocationActionKey> emittedRelocations;
	private final MinStExactCategoricalSolver.Statistics statistics;

	private MinStExactPhysicalSelection(PlacementAnalysis analysis, String costSurfaceFingerprint,
		long objectiveBits,
		double solverObjective, List<Integer> assignmentInDecisionOrder,
		List<Alternative> alternativesInDecisionOrder,
		Map<CompiledHopKey,PlacementState> selectedStates,
		Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> candidateReceipts,
		List<RelocationChoiceReceipt> relocationChoices,
		List<RelocationActionKey> emittedRelocations,
		MinStExactCategoricalSolver.Statistics statistics) {
		this.analysis = Objects.requireNonNull(analysis, "analysis");
		this.analysisFingerprint = analysis.analysisFingerprint();
		if(costSurfaceFingerprint == null || costSurfaceFingerprint.isBlank())
			throw new IllegalArgumentException("MINST_PHYSICAL_COST_FINGERPRINT_INVALID");
		this.costSurfaceFingerprint = costSurfaceFingerprint;
		double canonicalObjective = requireObjective(Double.longBitsToDouble(objectiveBits));
		this.objectiveBits = Double.doubleToRawLongBits(canonicalObjective);
		this.solverObjective = requireObjective(solverObjective);
		this.assignmentInDecisionOrder = List.copyOf(assignmentInDecisionOrder);
		this.alternativesInDecisionOrder = List.copyOf(alternativesInDecisionOrder);
		IdentityHashMap<CompiledHopKey,PlacementState> states = new IdentityHashMap<>();
		states.putAll(selectedStates);
		this.selectedStates = Collections.unmodifiableMap(states);
		IdentityHashMap<CompiledHopKey,PlacementEmissionState> emissions = new IdentityHashMap<>();
		emissions.putAll(selectedEmissionStates);
		if(emissions.size() != states.size() || emissions.entrySet().stream().anyMatch(entry ->
			states.get(entry.getKey()) != entry.getValue().placementState()))
			throw new IllegalArgumentException("MINST_PHYSICAL_SELECTION_EMISSION_AUTHORITY_MISMATCH");
		this.selectedEmissionStates = Collections.unmodifiableMap(emissions);
		this.candidateReceipts = List.copyOf(candidateReceipts);
		this.relocationChoices = List.copyOf(relocationChoices);
		this.emittedRelocations = List.copyOf(emittedRelocations);
		this.statistics = Objects.requireNonNull(statistics, "statistics");
	}

	static MinStExactPhysicalSelection create(MinStExactPhysicalModel model,
		MinStExactPhysicalOptimizer.Result optimized) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(optimized, "optimized");
		MinStExactCategoricalSolver.Result result = optimized.solverResult();
		PlacementAnalysis analysis = model.analysis();
		analysis.assertProgramStructureUnchanged();
		MinStExactPhysicalModel.PhysicalSelection physical = model.physicalSelection(result);
		if(physical.alternativesInDecisionOrder().size() != model.domains().size())
			throw new IllegalArgumentException("MINST_PHYSICAL_SELECTION_DECISION_CARDINALITY");

		IdentityHashMap<CompiledHopKey,PlacementState> selected = new IdentityHashMap<>();
		IdentityHashMap<CompiledHopKey,PlacementEmissionState> selectedEmissions = new IdentityHashMap<>();
		for(int index = 0; index < model.domains().size(); index++) {
			var domain = model.domains().get(index);
			Alternative alternative = physical.alternativesInDecisionOrder().get(index);
			if(alternative.decision() != domain.node().key()
				|| domain.alternatives().get(result.assignmentInVariableOrder().get(index)) != alternative
				|| domain.node().legalAlternatives().stream()
					.noneMatch(state -> state == alternative.state())
				|| selected.put(alternative.decision(), alternative.state()) != null)
				throw new IllegalArgumentException("MINST_PHYSICAL_SELECTION_ALTERNATIVE_IDENTITY");
			PlacementEmissionState emission = alternative.captured()
				? alternative.candidateEmission().emissionState()
				: alternative.executionEmission() != null
					? alternative.executionEmission().emissionState()
					: new PlacementEmissionState(alternative.state(), false);
			if(emission.placementState() != alternative.state()
				|| selectedEmissions.put(alternative.decision(), emission) != null)
				throw new IllegalArgumentException("MINST_PHYSICAL_SELECTION_EMISSION_IDENTITY");
		}
		completeSyntheticBoundaryStates(analysis, selected, selectedEmissions);

		List<CandidateSelectionReceipt> candidates = exactCandidateReceipts(
			analysis, physical, selected);
		List<RelocationChoiceReceipt> choices = exactRelocationChoices(
			analysis, physical, selected, candidates);
		List<RelocationActionKey> emitted = RelocationSelections.emittedActions(
			analysis, analysis.graph().relocationActions(), selected, candidates, choices);
		return new MinStExactPhysicalSelection(analysis, optimized.contributionFingerprint(),
			optimized.canonicalObjectiveBits(),
			result.objective(), result.assignmentInVariableOrder(),
			physical.alternativesInDecisionOrder(), selected, selectedEmissions, candidates, choices,
			emitted, result.statistics());
	}

	private static List<CandidateSelectionReceipt> exactCandidateReceipts(
		PlacementAnalysis analysis, MinStExactPhysicalModel.PhysicalSelection physical,
		Map<CompiledHopKey,PlacementState> selected) {
		Map<CompiledHopKey,CandidateSelectionReceipt> exact = new IdentityHashMap<>();
		for(var candidate : physical.candidates()) {
			PlacementState state = selected.get(candidate.decision());
			if(state == null || candidate.emission().emissionState().placementState() != state)
				throw new IllegalArgumentException(
					"MINST_PHYSICAL_CANDIDATE_EMISSION_PLACEMENT_MISMATCH|key="
						+ candidate.decision().normalizedSignature());
			CandidateSelectionReceipt receipt = new CandidateSelectionReceipt(
				candidate.rule().key(), candidate.emission(), List.of());
			if(exact.put(candidate.decision(), receipt) != null)
				throw new IllegalArgumentException("MINST_PHYSICAL_CANDIDATE_DUPLICATE|key="
					+ candidate.decision().normalizedSignature());
		}

		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible =
			CandidateSelections.feasibleVariants(analysis, analysis.graph().relocationActions(), selected);
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : feasible.entrySet()) {
			if(exact.containsKey(entry.getKey()))
				continue;
			if(entry.getValue().size() != 1)
				throw new IllegalArgumentException("MINST_PHYSICAL_UNCAPTURED_CANDIDATE_"
					+ (entry.getValue().isEmpty() ? "MISSING" : "AMBIGUOUS") + "|key="
					+ entry.getKey().normalizedSignature());
			exact.put(entry.getKey(), entry.getValue().get(0));
		}
		return CandidateSelections.resolveAndValidate(analysis,
			analysis.graph().relocationActions(), selected,
			exact.values().stream().sorted().toList());
	}

	private static List<RelocationChoiceReceipt> exactRelocationChoices(
		PlacementAnalysis analysis, MinStExactPhysicalModel.PhysicalSelection physical,
		Map<CompiledHopKey,PlacementState> selected,
		List<CandidateSelectionReceipt> candidates) {
		Map<CompiledHopKey,CandidateSelectionReceipt> candidateByConsumer = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : candidates)
			candidateByConsumer.put(candidate.rule().parentOccurrence(), candidate);
		Map<RelocationDemandKey,RelocationActionKey> exact = new LinkedHashMap<>();
		for(Alternative alternative : physical.alternativesInDecisionOrder())
			for(var authority : alternative.inputAuthorities())
				if(authority.relocationAction() != null)
					bindExactAction(exact, authority.relocationAction(), alternative.decision(),
						authority.inputPosition(), alternative.state());

		// A derived output action is also exact authority.  Bind every currently active
		// PRESENT endpoint it owns; inactive endpoints are not invented as demands.
		for(RelocationAction action : physical.relocationActions())
			for(ObligationKey obligation : action.obligations()) {
				CandidateSelectionReceipt candidate = candidateByConsumer.get(obligation.consumer());
				if(candidate == null || !obligation.requiredPlacement().equals(
					selected.get(obligation.consumer()))
					|| obligation.inputPosition() >= candidate.rule().orderedInputs().size()
					|| !candidate.rule().orderedInputs().get(obligation.inputPosition()).present()
					|| candidate.rule().orderedInputs().get(obligation.inputPosition()).fType()
						!= action.key().materializationFType())
					continue;
				putExact(exact, RelocationDemandKey.from(obligation), action.key());
			}

		Map<RelocationActionKey,RelocationAction> actions = new LinkedHashMap<>();
		for(RelocationAction action : analysis.graph().relocationActions())
			actions.put(action.key(), action);
		List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
			analysis, analysis.graph().relocationActions(), selected, candidates, (demand, actionKey) -> {
				RelocationActionKey required = exact.get(demand);
				if(required != null)
					return required.equals(actionKey);
				RelocationAction action = actions.get(actionKey);
				return action != null && !analysis.graph().isRelocationActive(action, selected);
			});
		Map<RelocationDemandKey,RelocationChoiceReceipt> byDemand = new LinkedHashMap<>();
		for(RelocationChoiceReceipt choice : choices)
			byDemand.put(choice.demand(), choice);
		for(Map.Entry<RelocationDemandKey,RelocationActionKey> required : exact.entrySet()) {
			RelocationChoiceReceipt choice = byDemand.get(required.getKey());
			if(choice == null || !choice.action().equals(required.getValue()))
				throw new IllegalArgumentException("MINST_PHYSICAL_RELOCATION_AUTHORITY_LOST|demand="
					+ required.getKey().normalizedSignature());
		}
		RelocationSelections.resolveAndValidate(analysis, analysis.graph().relocationActions(),
			selected, candidates, choices);
		return choices;
	}

	private static void bindExactAction(Map<RelocationDemandKey,RelocationActionKey> exact,
		RelocationAction action, CompiledHopKey consumer, int inputPosition,
		PlacementState requiredPlacement) {
		List<ObligationKey> matches = action.obligations().stream().filter(obligation ->
			obligation.consumer() == consumer && obligation.inputPosition() == inputPosition
				&& obligation.requiredPlacement().equals(requiredPlacement)).toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("MINST_PHYSICAL_RELOCATION_OBLIGATION_"
				+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS") + "|consumer="
				+ consumer.normalizedSignature() + "|input=" + inputPosition);
		putExact(exact, RelocationDemandKey.from(matches.get(0)), action.key());
	}

	private static void putExact(Map<RelocationDemandKey,RelocationActionKey> exact,
		RelocationDemandKey demand, RelocationActionKey action) {
		RelocationActionKey prior = exact.putIfAbsent(demand, action);
		if(prior != null && !prior.equals(action))
			throw new IllegalArgumentException("MINST_PHYSICAL_RELOCATION_AUTHORITY_CONFLICT|demand="
				+ demand.normalizedSignature());
	}

	private static void completeSyntheticBoundaryStates(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,PlacementState> selected,
		IdentityHashMap<CompiledHopKey,PlacementEmissionState> selectedEmissions) {
		boolean progressed;
			do {
			progressed = false;
			for(NeutralPlacementGraph.Node node : analysis.graph().decisionNodes()) {
				if(selected.containsKey(node.key()))
					continue;
				if(node.kind() != NodeKind.FUNCTION_INPUT && node.kind() != NodeKind.FUNCTION_OUTPUT)
					continue;
				DpPlacementAdapter.SyntheticBoundaryReceipt projection =
					DpPlacementAdapter.projectSyntheticBoundary(analysis, node, selectedEmissions);
				if(projection == null)
					continue;
				PlacementEmissionState boundaryEmission = projection.selectedEmissionState();
				selected.put(node.key(), boundaryEmission.placementState());
				selectedEmissions.put(node.key(), boundaryEmission);
				progressed = true;
			}
		}
		while(progressed);
		if(selected.size() != analysis.graph().decisionNodes().size()
			|| selectedEmissions.size() != selected.size())
			throw new IllegalArgumentException("MINST_PHYSICAL_TOTAL_DECISION_AUTHORITY|missing="
				+ analysis.graph().decisionNodes().stream().filter(node -> !selected.containsKey(node.key()))
					.map(node -> node.kind() + ":" + node.key().normalizedSignature()).toList());
	}

	private static double requireObjective(double objective) {
		if(!Double.isFinite(objective) || objective < 0.0
			|| Double.doubleToRawLongBits(objective) == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException("MINST_PHYSICAL_OBJECTIVE_INVALID|value=" + objective);
		return objective;
	}

	PlacementAnalysis analysis() { return analysis; }
	String analysisFingerprint() { return analysisFingerprint; }
	String costSurfaceFingerprint() { return costSurfaceFingerprint; }
	long objectiveBits() { return objectiveBits; }
	double solverObjective() { return solverObjective; }
	List<Integer> assignmentInDecisionOrder() { return assignmentInDecisionOrder; }
	List<Alternative> alternativesInDecisionOrder() { return alternativesInDecisionOrder; }
	Map<CompiledHopKey,PlacementState> selectedStates() { return selectedStates; }
	Map<CompiledHopKey,PlacementEmissionState> selectedEmissionStates() { return selectedEmissionStates; }
	List<CandidateSelectionReceipt> candidateReceipts() { return candidateReceipts; }
	List<RelocationChoiceReceipt> relocationChoices() { return relocationChoices; }
	List<RelocationActionKey> emittedRelocations() { return emittedRelocations; }
	MinStExactCategoricalSolver.Statistics statistics() { return statistics; }
}
