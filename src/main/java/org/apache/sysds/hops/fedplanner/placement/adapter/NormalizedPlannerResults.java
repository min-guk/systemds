/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.LocalMaterializationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Public normalization bridge for planner roots whose native result is not a common adapter result. */
public final class NormalizedPlannerResults {
	private NormalizedPlannerResults() { }

	/**
	 * Reconstructs the exact per-occurrence emission state retained by selected
	 * candidate receipts.  PlacementState alone cannot distinguish a native
	 * FED/FOUT result from FED/LOUT followed by a planner-selected refederation.
	 */
	public static Map<CompiledHopKey, PlacementEmissionState> exactEmissionStates(
		PlacementAnalysis analysis, Map<CompiledHopKey, PlacementState> selectedStates,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(selectedStates, "selectedStates");
		List<CandidateSelectionReceipt> candidates = List.copyOf(Objects.requireNonNull(
			selectedCandidates, "selectedCandidates"));
		Map<CompiledHopKey, PlacementEmissionState> result = new java.util.LinkedHashMap<>();
		for(var node : analysis.graph().decisionNodes()) {
			PlacementState selected = exactSelectedState(selectedStates, node.key());
			if(selected == null)
				throw new IllegalArgumentException("Selected placement is missing a decision node");
			if(node.kind() != org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
				&& node.kind() != org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
				result.put(node.key(), new PlacementEmissionState(selected, false));
		}
		for(CandidateSelectionReceipt candidate : candidates) {
			CompiledHopKey consumer = candidate.rule().parentOccurrence();
			PlacementEmissionState selected = exactEmissionState(result, consumer);
			if(selected == null)
				throw new IllegalArgumentException("Candidate selection has a foreign consumer");
			PlacementEmissionState candidateEmission = candidate.emission().emissionState();
			if(!candidateEmission.placementState().equals(selected.placementState()))
				throw new IllegalArgumentException(
					"Candidate emission differs from the exact selected placement");
			result.put(consumer, candidateEmission);
		}
		boolean progressed;
		do {
			progressed = false;
			for(var node : analysis.graph().decisionNodes()) {
				if(result.containsKey(node.key())
					|| node.kind() != org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
						&& node.kind() != org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
					continue;
				DpPlacementAdapter.SyntheticBoundaryReceipt projection =
					DpPlacementAdapter.projectSyntheticBoundary(analysis, node, result,
						exactSelectedState(selectedStates, node.key()));
				if(projection == null)
					continue;
				result.put(node.key(), projection.selectedEmissionState());
				progressed = true;
			}
		}
		while(progressed);
		if(result.size() != analysis.graph().decisionNodes().size())
			throw new IllegalArgumentException("Selected emission projection omitted a synthetic boundary");
		return java.util.Collections.unmodifiableMap(result);
	}

	private static PlacementState exactSelectedState(Map<CompiledHopKey, PlacementState> selected,
		CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return Objects.requireNonNull(entry.getValue(), "selected placement");
		return null;
	}

	private static PlacementEmissionState exactEmissionState(
		Map<CompiledHopKey, PlacementEmissionState> selected, CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return entry.getValue();
		return null;
	}

	public static NormalizedPlannerResult create(PlacementAnalysis analysis, String plannerId,
		Map<CompiledHopKey, PlacementState> selectedStates, String objectiveCertificate) {
		Map<CompiledHopKey, PlacementEmissionState> emission = new java.util.LinkedHashMap<>();
		selectedStates.forEach((key, state) -> emission.put(key, new PlacementEmissionState(state, false)));
		return createWithEmissionStates(analysis, plannerId, emission, objectiveCertificate);
	}

	public static NormalizedPlannerResult createWithEmissionStates(PlacementAnalysis analysis, String plannerId,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates, String objectiveCertificate) {
		Objects.requireNonNull(analysis, "analysis");
		Map<CompiledHopKey, PlacementState> selectedStates = new java.util.LinkedHashMap<>();
		selectedEmissionStates.forEach((key, state) -> selectedStates.put(key, state.placementState()));
		CandidateSelections.Selection selection = CandidateSelections.selectNativeCanonical(
			analysis, analysis.graph().relocationActions(), selectedStates);
		return createWithEmissionStatesAndCandidateSelections(analysis, plannerId, selectedEmissionStates,
			selection.candidates(), selection.relocationChoices(), objectiveCertificate);
	}

	public static NormalizedPlannerResult createWithEmissionStatesAndRelocationChoices(
		PlacementAnalysis analysis, String plannerId,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<RelocationChoiceReceipt> selectedRelocationChoices, String objectiveCertificate) {
		Objects.requireNonNull(analysis, "analysis");
		Map<CompiledHopKey, PlacementState> selectedStates = new java.util.LinkedHashMap<>();
		selectedEmissionStates.forEach((key, state) -> selectedStates.put(key, state.placementState()));
		CandidateSelections.Selection candidateSelection = CandidateSelections.selectNativeCanonical(
			analysis, analysis.graph().relocationActions(), selectedStates);
		return createWithEmissionStatesAndCandidateSelections(analysis, plannerId, selectedEmissionStates,
			candidateSelection.candidates(), selectedRelocationChoices, objectiveCertificate);
	}

	public static NormalizedPlannerResult createWithEmissionStatesAndCandidateSelections(
		PlacementAnalysis analysis, String plannerId,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> selectedCandidateSelections,
		List<RelocationChoiceReceipt> selectedRelocationChoices, String objectiveCertificate) {
		Objects.requireNonNull(analysis, "analysis");
		Map<CompiledHopKey, PlacementState> selectedStates = new java.util.LinkedHashMap<>();
		selectedEmissionStates.forEach((key, state) -> selectedStates.put(key, state.placementState()));
		List<CandidateSelectionReceipt> candidates = List.copyOf(Objects.requireNonNull(
			selectedCandidateSelections, "selectedCandidateSelections"));
		CandidateSelections.resolveAndValidate(analysis, selectedStates, candidates);
		List<RelocationChoiceReceipt> choices = List.copyOf(Objects.requireNonNull(
			selectedRelocationChoices, "selectedRelocationChoices"));
		List<RelocationActionKey> relocations = RelocationSelections.emittedActions(
			analysis, selectedStates, candidates, choices);
		List<LocalMaterializationActionKey> locals = deriveLocalMaterializations(
			analysis, selectedStates, selectedEmissionStates, candidates);
		NormalizedPlannerResult draft = new Draft(analysis, plannerId, analysis.analysisFingerprint(),
			Map.copyOf(selectedStates), Map.copyOf(selectedEmissionStates), candidates, choices, relocations, locals,
			objectiveCertificate);
		return PlacementPlannerAdapter.normalize(analysis, draft);
	}

	/** Canonical lowering authority derived from exact selected placements and candidate rows. */
	public static List<LocalMaterializationActionKey> deriveLocalMaterializations(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> selectedCandidates) {
		return LocalMaterializationSelections.derive(analysis, selected,
			selectedEmissionStates, selectedCandidates);
	}

	/** Exact analysis-owned provenance for one selected FED/FOUT source. */
	public static String durableLocalProvenance(
		org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node node,
		PlacementState producer) {
		return LocalMaterializationSelections.durableLocalProvenance(node, producer);
	}

	private record Draft(PlacementAnalysis analysis, String plannerId, String analysisFingerprint,
		Map<CompiledHopKey, PlacementState> selectedStates,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> selectedCandidateSelections,
		List<RelocationChoiceReceipt> selectedRelocationChoices,
		List<RelocationActionKey> selectedRelocations,
		List<LocalMaterializationActionKey> selectedLocalMaterializations,
		String objectiveCertificate) implements NormalizedPlannerResult {
		@Override public List<CandidateSelectionReceipt> selectedCandidateSelections() {
			return selectedCandidateSelections;
		}
		@Override public List<RelocationChoiceReceipt> selectedRelocationChoices() {
			return selectedRelocationChoices;
		}
		@Override public String normalizedPlanFingerprint() { return "canonicalized-at-boundary"; }
	}
}
