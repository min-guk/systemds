/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact final registry owner for plans already selected from one canonical placement analysis. */
public final class ExactPlacementRegistration {
	private ExactPlacementRegistration() {
	}

	public record Receipt(PlacementAnalysis analysis, List<RegisteredUpload> uploads) {
		public Receipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(uploads, "uploads");
			uploads = List.copyOf(uploads);
		}
	}

	public record RegisteredUpload(long scopeId, long hopId, long anchorHopId, FType fType,
		String anchorLabel, String anchorKey) {
		public RegisteredUpload {
			Objects.requireNonNull(fType, "fType");
		}
	}

	public static Receipt registerProgram(DMLProgram program, Map<Long, FType> selectedTypes,
		PlacementAnalysis analysis) {
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(selectedTypes, "selectedTypes");
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertProgramOwner(program);
		program.requirePlacementAnalysisAuthority(analysis);

		List<RegisteredUpload> uploads = exactUploads(analysis, selectedTypes);
		commit(uploads);
		return new Receipt(analysis, uploads);
	}

	private static List<RegisteredUpload> exactUploads(PlacementAnalysis analysis,
		Map<Long, FType> selectedTypes) {
		Map<Hop, List<Hop>> parents = parents(analysis.occurrences());
		List<RegisteredUpload> uploads = new ArrayList<>();
		Set<Long> registered = new LinkedHashSet<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			if(!requiresUpload(hop) || !registered.add(hop.getHopID()))
				continue;
			FType selectedType = selectedTypes.get(hop.getHopID());
			if(selectedType == null)
				throw new DMLRuntimeException("Selected CP/FOUT hop has no exact FType: " + hop.getHopID());
			Hop anchor = uniqueAnchor(hop, parents.getOrDefault(hop, List.of()), selectedTypes, selectedType);
			String label = anchor instanceof DataOp ? anchor.getName() : null;
			String key = label == null ? null : FederatedPlannerUtils.getFedAnchorKey(label);
			uploads.add(new RegisteredUpload(occurrence.scopeId(), hop.getHopID(), anchor.getHopID(), selectedType,
				label, key));
		}
		return List.copyOf(uploads);
	}

	private static Hop uniqueAnchor(Hop target, List<Hop> parents, Map<Long, FType> selectedTypes,
		FType selectedType) {
		Set<Hop> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
		addCompatibleAnchors(candidates, target.getInput(), selectedTypes, selectedType);
		for(Hop parent : parents)
			addCompatibleAnchors(candidates, parent.getInput(), selectedTypes, selectedType);
		candidates.remove(target);
		if(candidates.size() != 1)
			throw new DMLRuntimeException("Selected CP/FOUT hop requires one exact federated anchor: hop="
				+ target.getHopID() + " anchors=" + candidates.stream().map(Hop::getHopID).sorted().toList());
		return candidates.iterator().next();
	}

	private static void addCompatibleAnchors(Set<Hop> candidates, List<Hop> hops,
		Map<Long, FType> selectedTypes, FType selectedType) {
		if(hops == null)
			return;
		for(Hop hop : hops) {
			if(hop == null || !isConcreteFederatedAnchor(hop))
				continue;
			FType type = selectedTypes.get(hop.getHopID());
			if(type == selectedType)
				candidates.add(hop);
		}
	}

	private static boolean requiresUpload(Hop hop) {
		return selectedExec(hop) == ExecType.CP && hop.getFederatedOutput() == FederatedOutput.FOUT;
	}

	private static boolean isConcreteFederatedAnchor(Hop hop) {
		if(selectedExec(hop) == ExecType.FED && hop.getFederatedOutput() == FederatedOutput.FOUT)
			return true;
		return hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED;
	}

	private static ExecType selectedExec(Hop hop) {
		return hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
	}

	private static Map<Hop, List<Hop>> parents(List<PlacementAnalysis.HopOccurrenceProjection> occurrences) {
		Map<Hop, List<Hop>> parents = new IdentityHashMap<>();
		Set<Hop> owned = Collections.newSetFromMap(new IdentityHashMap<>());
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : occurrences)
			owned.add(occurrence.hop());
		for(Hop parent : owned) {
			if(parent.getInput() == null)
				continue;
			for(Hop child : parent.getInput())
				if(child != null && owned.contains(child))
					parents.computeIfAbsent(child, ignored -> new ArrayList<>()).add(parent);
		}
		return parents;
	}

	private static void commit(List<RegisteredUpload> uploads) {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		for(RegisteredUpload upload : uploads) {
			FederatedRefedRegistry.register(upload.scopeId(), upload.hopId(), upload.anchorHopId(),
				upload.anchorKey());
			FederatedFoutMaterializeRegistry.register(upload.scopeId(), upload.hopId(), upload.anchorHopId(),
				upload.fType().name(), upload.anchorLabel(), upload.anchorKey());
		}
	}

}
