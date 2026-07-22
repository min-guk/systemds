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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationObligation;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
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
		List<RelocationActionKey> relocations = analysis.graph().relocationActions().stream()
			.filter(action -> analysis.graph().isRelocationActive(action, selectedStates))
			.map(action -> action.key()).toList();
		List<LocalMaterializationActionKey> locals = deriveLocalMaterializations(analysis, selectedStates);
		NormalizedPlannerResult draft = new Draft(analysis, plannerId, analysis.analysisFingerprint(),
			Map.copyOf(selectedStates), Map.copyOf(selectedEmissionStates), relocations, locals,
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
				"fed-init:" + node.valueVersion().lexicalVariable()));
		}
		return result.stream().sorted().toList();
	}

	private record Draft(PlacementAnalysis analysis, String plannerId, String analysisFingerprint,
		Map<CompiledHopKey, PlacementState> selectedStates,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<RelocationActionKey> selectedRelocations,
		List<LocalMaterializationActionKey> selectedLocalMaterializations,
		String objectiveCertificate) implements NormalizedPlannerResult {
		@Override public String normalizedPlanFingerprint() { return "canonicalized-at-boundary"; }
	}
}
