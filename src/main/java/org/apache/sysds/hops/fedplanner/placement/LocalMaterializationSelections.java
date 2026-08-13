/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationObligation;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Canonical selection and lowering authority for exact FOUT-to-local materializations. */
public final class LocalMaterializationSelections {
	private LocalMaterializationSelections() { }

	/**
	 * Counts the physical local materializations implied by one exact placement and
	 * candidate-row selection. This is the same projection used during lowering.
	 */
	public static int physicalEmissionCount(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> selectedCandidates) {
		return derive(analysis, selected,
			nativeEmissionStates(selected, selectedCandidates), selectedCandidates).size();
	}

	/** Canonical lowering authority derived from exact selected placements and candidate rows. */
	public static List<LocalMaterializationActionKey> derive(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(selected, "selected");
		Objects.requireNonNull(selectedEmissionStates, "selectedEmissionStates");
		Objects.requireNonNull(selectedCandidates, "selectedCandidates");
		Map<CompiledHopKey,CandidateSelectionReceipt> candidatesByConsumer = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : selectedCandidates)
			if(candidatesByConsumer.put(candidate.rule().parentOccurrence(), candidate) != null)
				throw new IllegalArgumentException("Selected candidate is duplicated for one consumer");
		List<LocalMaterializationActionKey> result = new ArrayList<>();
		for(var node : analysis.graph().decisionNodes()) {
			PlacementState producer = selected.get(node.key());
			PlacementEmissionState producerEmission = exactEmissionState(
				selectedEmissionStates, node.key());
			if(producer == null || producer.execType() != ExecType.FED
				|| producer.output() != FederatedOutput.FOUT
				|| producer.fType() == null || producerEmission == null)
				continue;
			// A derived FOUT producer physically computes FED/LOUT and uploads that
			// coordinator-local value. Its local consumers already use the base LOUT.
			if(producerEmission.derivedFedFout())
				continue;
			List<LocalMaterializationObligation> obligations = new ArrayList<>(analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.producer() == node.key())
				// FunctionOp placement is a call placeholder. Its local-input demand is
				// determined by the selected formal below, not by the call's own state.
				.filter(edge -> !analysis.isDmlFunctionCallBoundary(edge.consumer()))
				.filter(edge -> requiresLocalInput(selected.get(edge.consumer()),
					candidatesByConsumer.get(edge.consumer()), edge.inputPosition()))
				.map(edge -> new LocalMaterializationObligation(edge.consumer(), edge.inputPosition(),
					selected.get(edge.consumer()))).toList());
			for(PlacementAnalysis.LogicalFunctionInputFact fact :
				analysis.logicalFunctionInputsInCanonicalOrder()) {
				if(fact.sourceArgument() != node.key())
					continue;
				PlacementState formal = selected.get(fact.targetRead());
				if(formal == null || formal.execType() != ExecType.CP
					|| formal.output() != FederatedOutput.LOUT)
					continue;
				CompiledHopKey call = analysis.requireExactPhysicalFunctionInputConsumer(fact);
				PlacementState callState = selected.get(call);
				if(callState == null)
					throw new IllegalArgumentException(
						"Selected local function formal has no selected physical call owner");
				obligations.add(new LocalMaterializationObligation(
					call, fact.callInputPosition(), callState));
			}
			obligations = obligations.stream().distinct().sorted().toList();
			if(obligations.isEmpty())
				continue;
			var occurrence = analysis.occurrences().stream()
				.filter(candidate -> candidate.key() == node.key()).findFirst().orElseThrow();
			result.add(new LocalMaterializationActionKey(node.key(), node.valueVersion(), producer,
				obligations, occurrence.scopeId() + ":" + node.key().functionNamespace(),
				durableLocalProvenance(node, producer)));
		}
		return result.stream().sorted().toList();
	}

	private static Map<CompiledHopKey, PlacementEmissionState> nativeEmissionStates(
		Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Map<CompiledHopKey, PlacementEmissionState> result = new LinkedHashMap<>();
		selected.forEach((key, state) -> result.put(key, new PlacementEmissionState(state, false)));
		for(CandidateSelectionReceipt candidate : selectedCandidates) {
			CompiledHopKey consumer = candidate.rule().parentOccurrence();
			PlacementState state = selected.get(consumer);
			if(state == null)
				throw new IllegalArgumentException("Candidate selection has no selected consumer placement");
			PlacementEmissionState emission = candidate.emission().emissionState();
			if(!emission.placementState().equals(state))
				throw new IllegalArgumentException("Candidate emission differs from selected placement");
			result.put(consumer, emission);
		}
		return Map.copyOf(result);
	}

	private static boolean requiresLocalInput(PlacementState consumer,
		CandidateSelectionReceipt candidate, int inputPosition) {
		if(consumer == null)
			return false;
		if(consumer.execType() == ExecType.CP && consumer.output() == FederatedOutput.LOUT)
			return true;
		if(consumer.execType() != ExecType.FED)
			return false;
		if(candidate == null || !candidate.emission().emissionState().placementState().equals(consumer))
			throw new IllegalArgumentException(
				"Federated consumer is missing its exact selected candidate authority");
		if(inputPosition < 0 || inputPosition >= candidate.rule().orderedInputs().size())
			throw new IllegalArgumentException(
				"Selected candidate does not cover an exact compiled input edge");
		return !candidate.rule().orderedInputs().get(inputPosition).present();
	}

	/** Exact analysis-owned provenance for one selected FED/FOUT source. */
	public static String durableLocalProvenance(NeutralPlacementGraph.Node node,
		PlacementState producer) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(producer, "producer");
		List<DurableAnchorKey> compatible = node.anchors().stream()
			.filter(anchor -> anchor.fType() == producer.fType()).toList();
		if(compatible.size() > 1)
			throw new IllegalStateException(
				"LOCAL source has ambiguous compatible durable anchors: " + node.key());
		if(compatible.size() == 1)
			return compatible.get(0).placementId();
		return "selected-source:" + node.valueVersion().normalizedSignature()
			+ ":occurrence:" + node.key().normalizedSignature();
	}

	private static PlacementEmissionState exactEmissionState(
		Map<CompiledHopKey, PlacementEmissionState> selected, CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return entry.getValue();
		return null;
	}
}
