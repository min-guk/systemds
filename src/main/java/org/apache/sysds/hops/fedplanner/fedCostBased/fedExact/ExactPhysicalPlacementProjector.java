/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

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
import org.apache.sysds.hops.fedplanner.placement.adapter.ExactPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;

/** Projects a lossless categorical Exact selection without reselecting physical authority. */
final class ExactPhysicalPlacementProjector {
	private ExactPhysicalPlacementProjector() { }

	static ExactPlacementInput project(ExactPhysicalSelection selection) {
		Objects.requireNonNull(selection, "selection");
		PlacementAnalysis analysis = selection.analysis();
		analysis.assertProgramStructureUnchanged();
		if(!analysis.analysisFingerprint().equals(selection.analysisFingerprint()))
			throw new IllegalArgumentException("EXACT_PHYSICAL_PROJECTOR_ANALYSIS_STALE");

		Map<CompiledHopKey,PlacementState> states = selection.selectedStates();
		CandidateSelections.resolveAndValidate(analysis, analysis.graph().relocationActions(),
			states, selection.candidateReceipts());
		RelocationSelections.resolveAndValidate(analysis, analysis.graph().relocationActions(),
			states, selection.candidateReceipts(), selection.relocationChoices());
		List<RelocationActionKey> emitted = RelocationSelections.emittedActions(analysis,
			analysis.graph().relocationActions(), states, selection.candidateReceipts(),
			selection.relocationChoices());
		if(!emitted.equals(selection.emittedRelocations()))
			throw new IllegalArgumentException("EXACT_PHYSICAL_PROJECTOR_RELOCATION_SET_CHANGED");

		Map<CompiledHopKey,PlacementEmissionState> emissions = emissionStates(selection);
		String certificate = "physical-ve-objective=" + selection.objectiveBits()
			+ ";costSurface=" + selection.costSurfaceFingerprint()
			+ ";assignment=" + selection.assignmentInDecisionOrder()
			+ ";maxFactorCells=" + selection.statistics().maximumFactorCells();
		NormalizedPlannerResult normalized = NormalizedPlannerResults
			.createWithEmissionStatesAndCandidateSelections(analysis, "Exact", emissions,
				selection.candidateReceipts(), selection.relocationChoices(), certificate);
		List<ExactPlacementInput.OccurrenceReceipt> occurrences = occurrenceReceipts(analysis, states);
		ExactPlacementInput.ProducerReceipt producer = new ExactPlacementInput.ProducerReceipt(
			selection.analysisFingerprint(), selection.objectiveBits());
		return ExactPlacementInput.createSelected(analysis, producer, occurrences,
			states, normalized);
	}

	private static Map<CompiledHopKey,PlacementEmissionState> emissionStates(
		ExactPhysicalSelection selection) {
		Map<CompiledHopKey,PlacementEmissionState> result = new IdentityHashMap<>();
		result.putAll(selection.selectedEmissionStates());
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : selection.candidateReceipts())
			candidates.put(candidate.rule().parentOccurrence(), candidate);
		for(var alternative : selection.alternativesInDecisionOrder()) {
			CandidateSelectionReceipt candidate = candidates.get(alternative.decision());
			if(candidate == null)
				continue;
			PlacementEmissionState exact = candidate.emission().emissionState();
			if(exact.placementState() != alternative.state()
				|| result.get(alternative.decision()) != exact)
				throw new IllegalArgumentException("EXACT_PHYSICAL_PROJECTOR_EMISSION_STATE_CHANGED|key="
					+ alternative.decision().normalizedSignature());
		}
		return java.util.Collections.unmodifiableMap(result);
	}

	private static List<ExactPlacementInput.OccurrenceReceipt> occurrenceReceipts(
		PlacementAnalysis analysis, Map<CompiledHopKey,PlacementState> states) {
		List<ExactPlacementInput.OccurrenceReceipt> result = new ArrayList<>(analysis.occurrences().size());
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			PlacementState state = states.get(occurrence.key());
			var exec = state == null ? null : state.execType();
			var output = state == null
				? org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.NONE
				: state.output();
			if(analysis.graph().node(occurrence.key()).orElseThrow().emittedWork() && state == null)
				throw new IllegalArgumentException("EXACT_PHYSICAL_PROJECTOR_EMITTED_STATE_MISSING|key="
					+ occurrence.key().normalizedSignature());
			result.add(new ExactPlacementInput.OccurrenceReceipt(occurrence.key(), hop, hop.getHopID(),
				hop, hop.getHopID(), exec, output));
		}
		return List.copyOf(result);
	}

}
