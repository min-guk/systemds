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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Immutable, canonical boundary projection shared by all planner adapters. */
final class ImmutableNormalizedPlannerResult implements NormalizedPlannerResult {
	private static final Comparator<RelocationActionKey> RELOCATION_ORDER =
		Comparator.comparing(RelocationActionKey::normalizedSignature);
	private final PlacementAnalysis analysis;
	private final String plannerId, analysisFingerprint, objectiveCertificate, normalizedPlanFingerprint;
	private final Map<CompiledHopKey, PlacementState> selectedStates;
	private final List<RelocationActionKey> selectedRelocations;

	private ImmutableNormalizedPlannerResult(PlannerPlacementContext context, NormalizedPlannerResult draft) {
		analysis = context.analysis();
		plannerId = Objects.requireNonNull(draft.plannerId(), "plannerId");
		analysisFingerprint = context.analysisFingerprint();
		objectiveCertificate = Objects.requireNonNull(draft.objectiveCertificate(), "objectiveCertificate");
		Map<CompiledHopKey, PlacementState> states = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey, PlacementState> e : Objects.requireNonNull(draft.selectedStates(), "selectedStates").entrySet())
			states.put(Objects.requireNonNull(e.getKey(), "state key"), Objects.requireNonNull(e.getValue(), "state value"));
		selectedStates = Collections.unmodifiableMap(states);
		List<RelocationActionKey> relocations = new ArrayList<>();
		for(RelocationActionKey key : Objects.requireNonNull(draft.selectedRelocations(), "selectedRelocations"))
			relocations.add(Objects.requireNonNull(key, "relocation"));
		relocations.sort(RELOCATION_ORDER);
		for(int i = 1; i < relocations.size(); i++)
			if(relocations.get(i-1).normalizedSignature().equals(relocations.get(i).normalizedSignature()))
				throw new IllegalArgumentException("duplicate relocation action");
		selectedRelocations = Collections.unmodifiableList(relocations);
		normalizedPlanFingerprint = fingerprint();
	}

	static NormalizedPlannerResult of(PlannerPlacementContext context, NormalizedPlannerResult draft) {
		return new ImmutableNormalizedPlannerResult(context, draft);
	}

	private String fingerprint() {
		StringBuilder canonical = new StringBuilder().append(plannerId).append('\n').append(analysisFingerprint).append('\n');
		selectedStates.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(e -> canonical.append(e.getKey().normalizedSignature()).append('=')
				.append(e.getValue().normalizedSignature()).append('\n'));
		selectedRelocations.forEach(r -> canonical.append(r.normalizedSignature()).append('\n'));
		canonical.append(objectiveCertificate);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for(byte b : digest) out.append(String.format("%02x", b));
			return out.toString();
		}
		catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}

	@Override public PlacementAnalysis analysis() { return analysis; }
	@Override public String plannerId() { return plannerId; }
	@Override public String analysisFingerprint() { return analysisFingerprint; }
	@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return selectedStates; }
	@Override public List<RelocationActionKey> selectedRelocations() { return selectedRelocations; }
	@Override public String objectiveCertificate() { return objectiveCertificate; }
	@Override public String normalizedPlanFingerprint() { return normalizedPlanFingerprint; }
}
