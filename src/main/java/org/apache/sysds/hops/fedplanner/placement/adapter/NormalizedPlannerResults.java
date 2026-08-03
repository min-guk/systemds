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

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationObligation;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Public normalization bridge for planner roots whose native result is not a common adapter result. */
public final class NormalizedPlannerResults {
	private NormalizedPlannerResults() { }

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
		List<LocalMaterializationActionKey> locals = deriveLocalMaterializations(analysis, selectedStates);
		NormalizedPlannerResult draft = new Draft(analysis, plannerId, analysis.analysisFingerprint(),
			Map.copyOf(selectedStates), Map.copyOf(selectedEmissionStates), candidates, choices, relocations, locals,
			objectiveCertificate);
		return PlacementPlannerAdapter.normalize(analysis, draft);
	}

	private static List<LocalMaterializationActionKey> deriveLocalMaterializations(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected) {
		List<LocalMaterializationActionKey> result = new java.util.ArrayList<>();
		for(var node : analysis.graph().decisionNodes()) {
			PlacementState producer = selected.get(node.key());
			if(producer == null || producer.execType() != org.apache.sysds.common.Types.ExecType.FED
				|| producer.output() != org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				|| producer.fType() == null)
				continue;
			List<LocalMaterializationObligation> obligations = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.producer() == node.key())
				// DML FunctionOp is a logical forwarding boundary. The actual/formal facts carry
				// placement into the callee; treating the CP call placeholder as a local matrix
				// consumer invents a full download that the selected plan neither priced nor needs.
				.filter(edge -> !analysis.isDmlFunctionCallBoundary(edge.consumer()))
				.filter(edge -> {
					PlacementState consumer = selected.get(edge.consumer());
					return consumer != null && consumer.execType() == org.apache.sysds.common.Types.ExecType.CP
						&& consumer.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT;
				})
				.map(edge -> new LocalMaterializationObligation(edge.consumer(), edge.inputPosition(),
					selected.get(edge.consumer()))).sorted().toList();
			if(obligations.isEmpty()) continue;
			var occurrence = analysis.occurrences().stream().filter(candidate -> candidate.key() == node.key())
				.findFirst().orElseThrow();
			result.add(new LocalMaterializationActionKey(node.key(), node.valueVersion(), producer,
				obligations, occurrence.scopeId() + ":" + node.key().functionNamespace(),
				durableLocalProvenance(node, producer)));
		}
		return result.stream().sorted().toList();
	}

	/** Exact analysis-owned provenance for one selected FED/FOUT source. */
	public static String durableLocalProvenance(
		org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node node,
		PlacementState producer) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(producer, "producer");
		List<DurableAnchorKey> compatible = node.anchors().stream()
			.filter(anchor -> anchor.fType() == producer.fType()).toList();
		if(compatible.size() > 1)
			throw new IllegalStateException("LOCAL source has ambiguous compatible durable anchors: " + node.key());
		if(compatible.size() == 1)
			return compatible.get(0).placementId();
		return "selected-source:" + node.valueVersion().normalizedSignature()
			+ ":occurrence:" + node.key().normalizedSignature();
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
