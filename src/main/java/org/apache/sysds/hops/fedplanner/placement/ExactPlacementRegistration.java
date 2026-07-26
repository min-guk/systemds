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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
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
		String anchorLabel, String anchorKey, List<Long> consumerHopIds) {
		public RegisteredUpload {
			Objects.requireNonNull(fType, "fType");
			if(consumerHopIds == null || consumerHopIds.isEmpty())
				throw new IllegalArgumentException("Exact REFED upload requires exact consumers");
			if(consumerHopIds.stream().anyMatch(Objects::isNull))
				throw new IllegalArgumentException("Exact REFED upload consumers must not contain null");
			consumerHopIds = consumerHopIds.stream().distinct().sorted().toList();
		}
	}

	private record ExactAnchor(HopOccurrenceProjection occurrence, DurableAnchorKey durableAnchor) { }

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
		Map<CompiledHopKey,HopOccurrenceProjection> occurrencesByKey = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			occurrencesByKey.put(occurrence.key(), occurrence);
		List<RegisteredUpload> uploads = new ArrayList<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Hop hop = occurrence.hop();
			if(!requiresUpload(hop))
				continue;
			FType selectedType = selectedTypes.get(hop.getHopID());
			if(selectedType == null)
				throw new DMLRuntimeException("Selected CP/FOUT hop has no exact FType: " + hop.getHopID());
			ExactAnchor exactAnchor = uniqueAnchor(analysis, occurrence, occurrencesByKey, selectedTypes, selectedType);
			Hop anchor = exactAnchor.occurrence().hop();
			String label = anchor instanceof DataOp ? anchor.getName() : null;
			String key = runtimeAnchorKey(exactAnchor.durableAnchor());
			List<Long> consumerHopIds = exactConsumerHopIds(analysis, occurrence, occurrencesByKey);
			uploads.add(new RegisteredUpload(occurrence.scopeId(), hop.getHopID(), anchor.getHopID(), selectedType,
				label, key, consumerHopIds));
		}
		return List.copyOf(uploads);
	}

	private static ExactAnchor uniqueAnchor(PlacementAnalysis analysis, HopOccurrenceProjection target,
		Map<CompiledHopKey,HopOccurrenceProjection> occurrencesByKey, Map<Long,FType> selectedTypes,
		FType selectedType) {
		Set<CompiledHopKey> consumers = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints())
			if(constraint.kind() == ConstraintKind.DOMINATES && constraint.left() == target.key())
				consumers.add(constraint.right());
		Set<CompiledHopKey> candidateKeys = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints())
			if(constraint.kind() == ConstraintKind.DOMINATES
				&& (constraint.right() == target.key() || consumers.contains(constraint.right())))
				candidateKeys.add(constraint.left());
		candidateKeys.remove(target.key());
		List<ExactAnchor> candidates = new ArrayList<>();
		for(CompiledHopKey candidateKey : candidateKeys) {
			HopOccurrenceProjection candidate = occurrencesByKey.get(candidateKey);
			if(candidate == null || !isConcreteFederatedAnchor(candidate.hop())
				|| selectedTypes.get(candidate.hop().getHopID()) != selectedType)
				continue;
			List<DurableAnchorKey> durableAnchors = analysis.graph().node(candidateKey).orElseThrow().anchors()
				.stream().filter(anchor -> anchor.fType() == selectedType).toList();
			if(durableAnchors.size() != 1)
				throw new DMLRuntimeException("Selected federated anchor lacks one exact durable placement: hop="
					+ candidate.hop().getHopID() + " anchors=" + durableAnchors.size());
			candidates.add(new ExactAnchor(candidate, durableAnchors.get(0)));
		}
		if(candidates.size() != 1)
			throw new DMLRuntimeException("Selected CP/FOUT hop requires one exact federated anchor: hop="
				+ target.hop().getHopID() + " anchors=" + candidates.stream()
					.map(candidate -> candidate.occurrence().hop().getHopID()).sorted().toList());
		return candidates.get(0);
	}

	private static List<Long> exactConsumerHopIds(PlacementAnalysis analysis, HopOccurrenceProjection target,
		Map<CompiledHopKey,HopOccurrenceProjection> occurrencesByKey) {
		List<Long> consumerHopIds = analysis.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.producer() == target.key())
			.map(edge -> occurrencesByKey.get(edge.consumer()))
			.map(consumer -> {
				if(consumer == null)
					throw new DMLRuntimeException("Selected CP/FOUT upload has a foreign exact consumer: hop="
						+ target.hop().getHopID());
				return consumer.hop().getHopID();
			})
			.distinct()
			.sorted()
			.toList();
		if(consumerHopIds.isEmpty())
			throw new DMLRuntimeException("Selected CP/FOUT upload requires exact consumers: hop="
				+ target.hop().getHopID());
		return consumerHopIds;
	}

	private static String runtimeAnchorKey(DurableAnchorKey anchor) {
		StringBuilder key = new StringBuilder();
		for(var partition : anchor.partitions()) {
			if(partition.begin().size() != 2 || partition.end().size() != 2)
				throw new DMLRuntimeException("Runtime federated anchors require exact two-dimensional ranges");
			key.append(partition.workerId()).append(';');
		}
		key.append('|');
		for(var partition : anchor.partitions()) {
			switch(anchor.fType()) {
				case ROW:
					key.append(partition.begin().get(0)).append(',').append(partition.end().get(0));
					break;
				case COL:
					key.append(partition.begin().get(1)).append(',').append(partition.end().get(1));
					break;
				case FULL:
				case BROADCAST:
					key.append(partition.begin().get(0)).append(',').append(partition.begin().get(1))
						.append(',').append(partition.end().get(0)).append(',').append(partition.end().get(1));
					break;
				default:
					throw new DMLRuntimeException("Unsupported durable federated anchor type: " + anchor.fType());
			}
			key.append(';');
		}
		return key.append('|').append(anchor.fType().name()).toString();
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

	private static void commit(List<RegisteredUpload> uploads) {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		for(RegisteredUpload upload : uploads) {
			FederatedRefedRegistry.register(upload.scopeId(), upload.hopId(), upload.anchorHopId(),
				upload.anchorKey(), upload.consumerHopIds());
			FederatedFoutMaterializeRegistry.register(upload.scopeId(), upload.hopId(), upload.anchorHopId(),
				upload.fType().name(), upload.anchorLabel(), upload.anchorKey());
		}
	}

}
