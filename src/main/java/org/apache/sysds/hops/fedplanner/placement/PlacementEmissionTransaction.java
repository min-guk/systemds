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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Applies one normalized placement result as a fail-closed transaction.
 *
 * <p>Planning and emission deliberately remain separate. This class does not
 * repair a plan or choose a fallback: it validates the entire supplied result,
 * snapshots every surface it owns, and either commits that exact result or
 * restores the original state.</p>
 */
public final class PlacementEmissionTransaction {
	public enum FailurePoint {
		AFTER_FIRST_HOP_MUTATION,
		AFTER_FIRST_REGISTRY_WRITE
	}

	@FunctionalInterface
	public interface FailureInjector {
		void inject(FailurePoint point);

		static FailureInjector none() {
			return point -> { };
		}

		static FailureInjector failAt(FailurePoint failurePoint) {
			Objects.requireNonNull(failurePoint, "failurePoint");
			return point -> {
				if(point == failurePoint)
					throw new PlacementEmissionException("Injected placement emission failure: " + point);
			};
		}
	}

	public record PlacementEmissionReceipt(String planHash, boolean applied, boolean noOp,
		int hopMutations, int registryWrites) {
		public PlacementEmissionReceipt {
			if(planHash == null || planHash.isBlank())
				throw new IllegalArgumentException("planHash must not be blank");
			if(applied == noOp)
				throw new IllegalArgumentException("receipt must describe either an application or a no-op");
			if(hopMutations < 0 || registryWrites < 0)
				throw new IllegalArgumentException("receipt mutation counts must be non-negative");
		}
	}

	public record ObservabilitySnapshot(long runtimeFallbackCount, long runtimeRepairCount) { }

	private static final Object LOCK = new Object();
	private static final Map<DMLProgram, CommittedPlan> COMMITTED = new IdentityHashMap<>();
	private static long runtimeFallbackCount;
	private static long runtimeRepairCount;

	private PlacementEmissionTransaction() { }

	public static PlacementEmissionReceipt emit(DMLProgram program, NormalizedPlannerResult result,
		FailureInjector failureInjector) {
		Objects.requireNonNull(failureInjector, "failureInjector");
		synchronized(LOCK) {
			PreparedEmission prepared = prevalidate(program, result);
			CommittedPlan existing = COMMITTED.get(program);
			if(existing != null) {
				if(!existing.receipt().planHash().equals(prepared.planHash()))
					throw new PlacementEmissionException("A different placement plan was already emitted");
				return new PlacementEmissionReceipt(prepared.planHash(), false, true, 0, 0);
			}

			Map<Hop, HopSnapshot> hopSnapshots = snapshotHops(prepared.hopWrites());
			RegistrySnapshots registrySnapshots = RegistrySnapshots.capture();
			Map<DMLProgram, CommittedPlan> receiptSnapshot = new IdentityHashMap<>(COMMITTED);
			long fallbackSnapshot = runtimeFallbackCount;
			long repairSnapshot = runtimeRepairCount;
			try {
				applyHops(prepared.hopWrites(), failureInjector);
				applyRegistries(prepared.registryWrites(), failureInjector);
				PlacementEmissionReceipt receipt = new PlacementEmissionReceipt(prepared.planHash(), true, false,
					prepared.hopWrites().size(), prepared.registryWrites().size());
				COMMITTED.put(program, new CommittedPlan(receipt));
				return receipt;
			}
			catch(RuntimeException | Error failure) {
				registrySnapshots.restore();
				restoreHops(hopSnapshots);
				COMMITTED.clear();
				COMMITTED.putAll(receiptSnapshot);
				runtimeFallbackCount = fallbackSnapshot;
				runtimeRepairCount = repairSnapshot;
				throw failure;
			}
		}
	}

	public static void resetForTesting() {
		synchronized(LOCK) {
			COMMITTED.clear();
			runtimeFallbackCount = 0;
			runtimeRepairCount = 0;
		}
	}

	public static Map<DMLProgram, PlacementEmissionReceipt> receiptSnapshotForTesting() {
		synchronized(LOCK) {
			Map<DMLProgram, PlacementEmissionReceipt> snapshot = new IdentityHashMap<>();
			COMMITTED.forEach((program, committed) -> snapshot.put(program, committed.receipt()));
			return Collections.unmodifiableMap(snapshot);
		}
	}

	public static ObservabilitySnapshot observabilitySnapshot() {
		synchronized(LOCK) {
			return new ObservabilitySnapshot(runtimeFallbackCount, runtimeRepairCount);
		}
	}

	/**
	 * Computes the sole content authority for a normalized placement result.
	 * The fingerprint covers every public selection field and is independent of
	 * map/list iteration order.
	 */
	public static String canonicalPlanHash(NormalizedPlannerResult result) {
		Objects.requireNonNull(result, "result");
		String plannerId = requireText(result.plannerId(), "plannerId");
		String analysisFingerprint = requireText(result.analysisFingerprint(), "analysisFingerprint");
		String objective = requireText(result.objectiveCertificate(), "objectiveCertificate");
		Map<CompiledHopKey, PlacementState> selected = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(result.selectedStates(), "selectedStates")));
		List<RelocationActionKey> relocations = List.copyOf(Objects.requireNonNull(result.selectedRelocations(),
			"selectedRelocations"));
		return canonicalPlanHash(plannerId, analysisFingerprint, selected, relocations, objective);
	}

	private static String canonicalPlanHash(String plannerId, String analysisFingerprint,
		Map<CompiledHopKey, PlacementState> selected, List<RelocationActionKey> relocations, String objective) {
		StringBuilder canonical = new StringBuilder().append(plannerId).append('\n')
			.append(analysisFingerprint).append('\n');
		selected.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> canonical
			.append(Objects.requireNonNull(entry.getKey(), "selected state key").normalizedSignature())
			.append('=').append(Objects.requireNonNull(entry.getValue(), "selected state").normalizedSignature())
			.append('\n'));
		relocations.stream().map(key -> Objects.requireNonNull(key, "selected relocation"))
			.sorted(Comparator.comparing(RelocationActionKey::normalizedSignature))
			.forEach(relocation -> canonical.append(relocation.normalizedSignature()).append('\n'));
		canonical.append(objective);
		return sha256(canonical.toString());
	}

	private static PreparedEmission prevalidate(DMLProgram program, NormalizedPlannerResult result) {
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(result, "result");
		PlacementAnalysis analysis = Objects.requireNonNull(result.analysis(), "result.analysis");
		analysis.assertCanonicalProgramAuthority(program);
		analysis.assertProgramStructureUnchanged();
		String analysisFingerprint = requireText(result.analysisFingerprint(), "analysisFingerprint");
		if(!analysis.analysisFingerprint().equals(analysisFingerprint))
			throw new PlacementEmissionException("Placement result has a stale analysis fingerprint");
		String plannerId = requireText(result.plannerId(), "plannerId");
		String objective = requireText(result.objectiveCertificate(), "objectiveCertificate");
		String planHash = requireCanonicalHash(result.normalizedPlanFingerprint());
		Map<CompiledHopKey, PlacementState> selected = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(result.selectedStates(), "selectedStates")));
		List<RelocationActionKey> selectedRelocations = List.copyOf(Objects.requireNonNull(
			result.selectedRelocations(), "selectedRelocations"));
		if(!planHash.equals(canonicalPlanHash(plannerId, analysisFingerprint, selected,
			selectedRelocations, objective)))
			throw new PlacementEmissionException("Normalized plan fingerprint does not match canonical content");

		List<Node> decisionNodes = analysis.graph().decisionNodes();
		if(selected.size() != decisionNodes.size())
			throw new PlacementEmissionException("Selected placement does not cover every decision node");
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences = occurrenceIndex(analysis);
		Set<CompiledHopKey> selectedIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Hop> emittedHops = Collections.newSetFromMap(new IdentityHashMap<>());
		List<HopWrite> hopWrites = new ArrayList<>(decisionNodes.size());
		for(Node node : decisionNodes) {
			PlacementState state = exactState(selected, node.key());
			if(state == null)
				throw new PlacementEmissionException("Selected placement is missing an analysis-owned decision");
			if(!node.legalAlternatives().contains(state))
				throw new PlacementEmissionException("Selected placement contains an illegal state");
			if("recompile".equals(node.key().recompileContext()) && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				throw new PlacementEmissionException("Recompile regions cannot emit CP/FOUT");
			HopOccurrenceProjection occurrence = occurrences.get(node.key());
			if(occurrence == null || !emittedHops.add(occurrence.hop()))
				throw new PlacementEmissionException("Emitted Hop ownership is missing or ambiguous");
			selectedIdentities.add(node.key());
			hopWrites.add(new HopWrite(occurrence.hop(), state));
		}
		for(CompiledHopKey key : selected.keySet())
			if(!selectedIdentities.contains(key))
				throw new PlacementEmissionException("Selected placement contains a foreign decision key");

		List<RelocationAction> relocations = exactRelocations(analysis, selectedRelocations);
		List<RegistryWrite> registryWrites = prepareRegistryWrites(analysis, occurrences, relocations);
		return new PreparedEmission(planHash, List.copyOf(hopWrites), List.copyOf(registryWrites));
	}

	private static Map<CompiledHopKey, HopOccurrenceProjection> occurrenceIndex(PlacementAnalysis analysis) {
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			if(occurrences.put(occurrence.key(), occurrence) != null)
				throw new PlacementEmissionException("Analysis contains duplicate occurrence identity");
		return occurrences;
	}

	private static PlacementState exactState(Map<CompiledHopKey, PlacementState> selected,
		CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return Objects.requireNonNull(entry.getValue(), "selected state");
		return null;
	}

	private static List<RelocationAction> exactRelocations(PlacementAnalysis analysis,
		List<RelocationActionKey> selected) {
		List<RelocationAction> available = analysis.graph().relocationActions();
		Set<String> seen = new LinkedHashSet<>();
		List<RelocationAction> resolved = new ArrayList<>(selected.size());
		for(RelocationActionKey key : selected) {
			Objects.requireNonNull(key, "selected relocation");
			if(!seen.add(key.normalizedSignature()))
				throw new PlacementEmissionException("Selected relocation is duplicated");
			RelocationAction match = available.stream().filter(action -> action.key().equals(key)).findFirst()
				.orElseThrow(() -> new PlacementEmissionException("Selected relocation is foreign or illegal"));
			resolved.add(match);
		}
		return resolved;
	}

	private static List<RegistryWrite> prepareRegistryWrites(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences, List<RelocationAction> relocations) {
		List<RegistryWrite> writes = new ArrayList<>();
		Set<RegistrySlot> slots = new LinkedHashSet<>();
		for(RelocationAction action : relocations) {
			RelocationActionKey key = action.key();
			List<Node> sources = analysis.graph().nodes().stream()
				.filter(node -> node.valueVersion().equals(key.sourceValueVersion()))
				.filter(node -> occurrences.containsKey(node.key())).toList();
			if(sources.size() != 1)
				throw new PlacementEmissionException("Relocation source does not have one emitted owner");
			HopOccurrenceProjection source = occurrences.get(sources.get(0).key());
			List<HopOccurrenceProjection> consumers = new ArrayList<>();
			for(CompiledHopKey consumerKey : key.compatibleConsumers()) {
				HopOccurrenceProjection consumer = occurrences.get(consumerKey);
				if(consumer == null)
					throw new PlacementEmissionException("Relocation consumer is foreign to the analysis");
				consumers.add(consumer);
			}
			HopOccurrenceProjection anchor = resolveAnchor(analysis, occurrences, key);
			String anchorKey = key.durableAnchor().normalizedSignature();
			String fType = key.targetPlacement().fType() == null ? null : key.targetPlacement().fType().name();
			RegistryWrite write;
			if(key.targetPlacement().output() == FederatedOutput.LOUT) {
				List<Long> consumerIds = consumers.stream().map(o -> o.hop().getHopID()).distinct().sorted().toList();
				write = RegistryWrite.local(source.scopeId(), source.hop().getHopID(), consumerIds, fType,
					"placement-transaction:" + key.statementBlockScope());
			}
			else if(key.targetPlacement().execType() == ExecType.CP) {
				write = RegistryWrite.fout(source.scopeId(), source.hop().getHopID(), anchor.hop().getHopID(),
					fType, key.durableAnchor().placementId(), anchorKey);
			}
			else {
				write = RegistryWrite.refed(source.scopeId(), source.hop().getHopID(), anchor.hop().getHopID(),
					anchorKey);
			}
			if(!slots.add(write.slot()))
				throw new PlacementEmissionException("Multiple relocations target one registry slot");
			writes.add(write);
		}
		return writes;
	}

	private static HopOccurrenceProjection resolveAnchor(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences, RelocationActionKey relocation) {
		List<HopOccurrenceProjection> anchors = analysis.graph().nodes().stream()
			.filter(node -> node.anchors().contains(relocation.durableAnchor()))
			.map(Node::key).map(occurrences::get).filter(Objects::nonNull)
			.sorted(Comparator.comparing(HopOccurrenceProjection::normalizedSignature)).toList();
		if(anchors.isEmpty())
			throw new PlacementEmissionException("Relocation has no durable analysis-owned anchor");
		return anchors.get(0);
	}

	private static Map<Hop, HopSnapshot> snapshotHops(List<HopWrite> writes) {
		Map<Hop, HopSnapshot> snapshots = new IdentityHashMap<>();
		for(HopWrite write : writes)
			snapshots.put(write.hop(), HopSnapshot.capture(write.hop()));
		return snapshots;
	}

	private static void applyHops(List<HopWrite> writes, FailureInjector injector) {
		for(int i = 0; i < writes.size(); i++) {
			HopWrite write = writes.get(i);
			write.hop().setExecType(write.state().execType());
			write.hop().setForcedExecType(write.state().execType());
			write.hop().setFederatedOutput(write.state().output());
			write.hop().setFederatedOutputDerived(false);
			if(i == 0)
				injector.inject(FailurePoint.AFTER_FIRST_HOP_MUTATION);
		}
	}

	private static void applyRegistries(List<RegistryWrite> writes, FailureInjector injector) {
		for(int i = 0; i < writes.size(); i++) {
			writes.get(i).apply();
			if(i == 0)
				injector.inject(FailurePoint.AFTER_FIRST_REGISTRY_WRITE);
		}
	}

	private static void restoreHops(Map<Hop, HopSnapshot> snapshots) {
		snapshots.forEach((hop, snapshot) -> snapshot.restore(hop));
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(64);
			for(byte valueByte : digest)
				result.append(String.format("%02x", valueByte));
			return result.toString();
		}
		catch(Exception failure) {
			throw new IllegalStateException("SHA-256 is unavailable", failure);
		}
	}

	private static String requireText(String value, String name) {
		if(value == null || value.isBlank())
			throw new PlacementEmissionException(name + " must not be blank");
		return value;
	}

	private static String requireCanonicalHash(String value) {
		if(value == null || !value.matches("[0-9a-f]{64}"))
			throw new PlacementEmissionException("normalized plan fingerprint must be a lowercase SHA-256 hash");
		return value;
	}

	private enum RegistryKind { REFED, FOUT, LOCAL }

	private record RegistrySlot(RegistryKind kind, long scopeId, long hopId) { }

	private record RegistryWrite(RegistrySlot slot, long anchorHopId, List<Long> consumerHopIds,
		String fType, String label, String anchorKey, String reason) {
		private static RegistryWrite refed(long scopeId, long hopId, long anchorHopId, String anchorKey) {
			return new RegistryWrite(new RegistrySlot(RegistryKind.REFED, scopeId, hopId), anchorHopId,
				List.of(), null, null, anchorKey, null);
		}

		private static RegistryWrite fout(long scopeId, long hopId, long anchorHopId, String fType,
			String label, String anchorKey) {
			return new RegistryWrite(new RegistrySlot(RegistryKind.FOUT, scopeId, hopId), anchorHopId,
				List.of(), fType, label, anchorKey, null);
		}

		private static RegistryWrite local(long scopeId, long hopId, List<Long> consumers, String fType,
			String reason) {
			return new RegistryWrite(new RegistrySlot(RegistryKind.LOCAL, scopeId, hopId), 0,
				List.copyOf(consumers), fType, null, null, reason);
		}

		private void apply() {
			switch(slot.kind()) {
				case REFED -> FederatedRefedRegistry.register(slot.scopeId(), slot.hopId(), anchorHopId,
					anchorKey);
				case FOUT -> FederatedFoutMaterializeRegistry.register(slot.scopeId(), slot.hopId(),
					anchorHopId, fType, label, anchorKey);
				case LOCAL -> FederatedLocalMaterializeRegistry.register(slot.scopeId(), slot.hopId(),
					consumerHopIds, fType, reason);
			}
		}
	}

	private record RegistrySnapshots(FederatedRefedRegistry.Snapshot refed,
		FederatedFoutMaterializeRegistry.Snapshot fout,
		FederatedLocalMaterializeRegistry.Snapshot local) {
		private static RegistrySnapshots capture() {
			return new RegistrySnapshots(FederatedRefedRegistry.snapshotAll(),
				FederatedFoutMaterializeRegistry.snapshotAll(), FederatedLocalMaterializeRegistry.snapshotAll());
		}

		private void restore() {
			FederatedLocalMaterializeRegistry.restoreAll(local);
			FederatedFoutMaterializeRegistry.restoreAll(fout);
			FederatedRefedRegistry.restoreAll(refed);
		}
	}

	private record HopWrite(Hop hop, PlacementState state) { }

	private record HopSnapshot(ExecType execType, ExecType forcedExecType, FederatedOutput output,
		boolean outputDerived) {
		private static HopSnapshot capture(Hop hop) {
			return new HopSnapshot(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
				hop.isFederatedOutputDerived());
		}

		private void restore(Hop hop) {
			hop.setExecType(execType);
			if(forcedExecType == null)
				hop.clearForcedExecType();
			else
				hop.setForcedExecType(forcedExecType);
			hop.setFederatedOutput(output);
			hop.setFederatedOutputDerived(outputDerived);
		}
	}

	private record PreparedEmission(String planHash, List<HopWrite> hopWrites,
		List<RegistryWrite> registryWrites) { }
	private record CommittedPlan(PlacementEmissionReceipt receipt) { }

	private static final class PlacementEmissionException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		private PlacementEmissionException(String message) {
			super(message);
		}
	}
}
