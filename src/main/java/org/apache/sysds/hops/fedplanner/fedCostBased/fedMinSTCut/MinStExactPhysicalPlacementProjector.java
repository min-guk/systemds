/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;

/** Projects a lossless categorical MinST selection without reselecting physical authority. */
final class MinStExactPhysicalPlacementProjector {
	private MinStExactPhysicalPlacementProjector() { }

	static MinStPlacementInput project(MinStExactPhysicalSelection selection) {
		Objects.requireNonNull(selection, "selection");
		PlacementAnalysis analysis = selection.analysis();
		analysis.assertProgramStructureUnchanged();
		if(!analysis.analysisFingerprint().equals(selection.analysisFingerprint()))
			throw new IllegalArgumentException("MINST_PHYSICAL_PROJECTOR_ANALYSIS_STALE");

		Map<CompiledHopKey,PlacementState> states = selection.selectedStates();
		CandidateSelections.resolveAndValidate(analysis, analysis.graph().relocationActions(),
			states, selection.candidateReceipts());
		RelocationSelections.resolveAndValidate(analysis, analysis.graph().relocationActions(),
			states, selection.candidateReceipts(), selection.relocationChoices());
		List<RelocationActionKey> emitted = RelocationSelections.emittedActions(analysis,
			analysis.graph().relocationActions(), states, selection.candidateReceipts(),
			selection.relocationChoices());
		if(!emitted.equals(selection.emittedRelocations()))
			throw new IllegalArgumentException("MINST_PHYSICAL_PROJECTOR_RELOCATION_SET_CHANGED");

		Map<CompiledHopKey,PlacementEmissionState> emissions = emissionStates(selection);
		String certificate = "physical-ve-objective=" + selection.objectiveBits()
			+ ";costSurface=" + selection.costSurfaceFingerprint()
			+ ";assignment=" + selection.assignmentInDecisionOrder()
			+ ";maxFactorCells=" + selection.statistics().maximumFactorCells();
		NormalizedPlannerResult normalized = NormalizedPlannerResults
			.createWithEmissionStatesAndCandidateSelections(analysis, "MinST", emissions,
				selection.candidateReceipts(), selection.relocationChoices(), certificate);
		List<MinStPlacementInput.OccurrenceReceipt> occurrences = occurrenceReceipts(analysis, states);
		MinStPlacementInput.ProducerReceipt producer = new MinStPlacementInput.ProducerReceipt(
			selection.analysisFingerprint(), selection.objectiveBits(), diagnosticSourceProjection(analysis, states));
		return MinStPlacementInput.createSelected(analysis, producer, occurrences, List.of(),
			states, normalized);
	}

	private static Map<CompiledHopKey,PlacementEmissionState> emissionStates(
		MinStExactPhysicalSelection selection) {
		Map<CompiledHopKey,PlacementEmissionState> result = new IdentityHashMap<>();
		selection.selectedStates().forEach((key, state) ->
			result.put(key, new PlacementEmissionState(state, false)));
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : selection.candidateReceipts())
			candidates.put(candidate.rule().parentOccurrence(), candidate);
		for(var alternative : selection.alternativesInDecisionOrder()) {
			CandidateSelectionReceipt candidate = candidates.get(alternative.decision());
			if(candidate == null)
				continue;
			PlacementEmissionState exact = candidate.emission().emissionState();
			if(exact.placementState() != alternative.state())
				throw new IllegalArgumentException("MINST_PHYSICAL_PROJECTOR_EMISSION_STATE_CHANGED|key="
					+ alternative.decision().normalizedSignature());
			result.put(alternative.decision(), exact);
		}
		return result;
	}

	private static List<MinStPlacementInput.OccurrenceReceipt> occurrenceReceipts(
		PlacementAnalysis analysis, Map<CompiledHopKey,PlacementState> states) {
		List<MinStPlacementInput.OccurrenceReceipt> result = new ArrayList<>(analysis.occurrences().size());
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			PlacementState state = states.get(occurrence.key());
			var exec = state == null ? null : state.execType();
			var output = state == null
				? org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE
				: state.output();
			if(analysis.graph().node(occurrence.key()).orElseThrow().emittedWork() && state == null)
				throw new IllegalArgumentException("MINST_PHYSICAL_PROJECTOR_EMITTED_STATE_MISSING|key="
					+ occurrence.key().normalizedSignature());
			result.add(new MinStPlacementInput.OccurrenceReceipt(occurrence.key(), hop, hop.getHopID(),
				hop, hop.getHopID(), exec, output));
		}
		return List.copyOf(result);
	}

	/** Compatibility-only C/P projection; physical identity remains in exact receipts. */
	private static List<Long> diagnosticSourceProjection(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> states) {
		List<Long> source = new ArrayList<>();
		int index = 0;
		for(var node : analysis.graph().decisionNodes()) {
			PlacementState state = states.get(node.key());
			if(state == null)
				throw new IllegalArgumentException("MINST_PHYSICAL_PROJECTOR_DECISION_STATE_MISSING");
			long base = 2L * index++;
			if(state.execType() == org.apache.sysds.common.Types.ExecType.FED)
				source.add(base);
			if(state.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT)
				source.add(base + 1L);
		}
		return source.stream().sorted().toList();
	}
}
