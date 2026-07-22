/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Immutable, canonical boundary projection shared by all planner adapters. */
final class ImmutableNormalizedPlannerResult implements NormalizedPlannerResult {
	private static final Comparator<RelocationActionKey> RELOCATION_ORDER =
		Comparator.comparing(RelocationActionKey::normalizedSignature);
	private final PlacementAnalysis analysis;
	private final String plannerId, analysisFingerprint, objectiveCertificate, normalizedPlanFingerprint;
	private final Map<CompiledHopKey, PlacementState> selectedStates;
	private final Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates;
	private final List<RelocationActionKey> selectedRelocations;
	private final List<LocalMaterializationActionKey> selectedLocalMaterializations;

	private ImmutableNormalizedPlannerResult(PlannerPlacementContext context, NormalizedPlannerResult draft) {
		analysis = context.analysis();
		plannerId = Objects.requireNonNull(draft.plannerId(), "plannerId");
		analysisFingerprint = context.analysisFingerprint();
		objectiveCertificate = Objects.requireNonNull(draft.objectiveCertificate(), "objectiveCertificate");
		Map<CompiledHopKey, PlacementEmissionState> emissionStates = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> e : Objects.requireNonNull(
			draft.selectedEmissionStates(), "selectedEmissionStates").entrySet())
			emissionStates.put(Objects.requireNonNull(e.getKey(), "emission state key"),
				Objects.requireNonNull(e.getValue(), "emission state value"));
		selectedEmissionStates = Collections.unmodifiableMap(emissionStates);
		Map<CompiledHopKey, PlacementState> states = new LinkedHashMap<>();
		emissionStates.forEach((key, value) -> states.put(key, value.placementState()));
		if(!sameExactProjection(states, Objects.requireNonNull(draft.selectedStates(), "selectedStates")))
			throw new IllegalArgumentException("structural and emission placement projections differ");
		selectedStates = Collections.unmodifiableMap(states);
		List<RelocationActionKey> relocations = new ArrayList<>();
		for(RelocationActionKey key : Objects.requireNonNull(draft.selectedRelocations(), "selectedRelocations"))
			relocations.add(Objects.requireNonNull(key, "relocation"));
		relocations.sort(RELOCATION_ORDER);
		for(int i = 1; i < relocations.size(); i++)
			if(relocations.get(i-1).normalizedSignature().equals(relocations.get(i).normalizedSignature()))
				throw new IllegalArgumentException("duplicate relocation action");
		selectedRelocations = Collections.unmodifiableList(relocations);
		List<LocalMaterializationActionKey> locals = new ArrayList<>();
		for(Object value : Objects.requireNonNull(draft.selectedLocalMaterializations(),
			"selectedLocalMaterializations")) {
			if(!(value instanceof LocalMaterializationActionKey key))
				throw new IllegalArgumentException("foreign local materialization authority type");
			locals.add(key);
		}
		locals.sort(Comparator.naturalOrder());
		for(int i = 1; i < locals.size(); i++)
			if(locals.get(i - 1).normalizedSignature().equals(locals.get(i).normalizedSignature()))
				throw new IllegalArgumentException("duplicate local materialization action");
		selectedLocalMaterializations = Collections.unmodifiableList(locals);
		normalizedPlanFingerprint = PlacementEmissionTransaction.canonicalPlanHash(this);
	}

	private static boolean sameExactProjection(Map<CompiledHopKey, PlacementState> expected,
		Map<CompiledHopKey, PlacementState> actual) {
		if(expected.size() != actual.size()) return false;
		for(Map.Entry<CompiledHopKey, PlacementState> entry : expected.entrySet()) {
			boolean found = false;
			for(Map.Entry<CompiledHopKey, PlacementState> candidate : actual.entrySet())
				if(candidate.getKey() == entry.getKey()) {
					found = candidate.getValue() == entry.getValue();
					break;
				}
			if(!found) return false;
		}
		return true;
	}

	static NormalizedPlannerResult of(PlannerPlacementContext context, NormalizedPlannerResult draft) {
		return new ImmutableNormalizedPlannerResult(context, draft);
	}

	@Override public PlacementAnalysis analysis() { return analysis; }
	@Override public String plannerId() { return plannerId; }
	@Override public String analysisFingerprint() { return analysisFingerprint; }
	@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return selectedStates; }
	@Override public Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates() { return selectedEmissionStates; }
	@Override public List<RelocationActionKey> selectedRelocations() { return selectedRelocations; }
	@Override public List<LocalMaterializationActionKey> selectedLocalMaterializations() { return selectedLocalMaterializations; }
	@Override public String objectiveCertificate() { return objectiveCertificate; }
	@Override public String normalizedPlanFingerprint() { return normalizedPlanFingerprint; }
}
