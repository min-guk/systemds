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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationObligation;
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
		return emitInternal(program, result, failureInjector, false);
	}

	/** Atomically replace one complete full-program authority during dynamic recompilation. */
	public static PlacementEmissionReceipt replaceCompleteProgram(DMLProgram program,
		NormalizedPlannerResult result, FailureInjector failureInjector) {
		return emitInternal(program, result, failureInjector, true);
	}

	private static PlacementEmissionReceipt emitInternal(DMLProgram program, NormalizedPlannerResult result,
		FailureInjector failureInjector, boolean replaceExisting) {
		Objects.requireNonNull(failureInjector, "failureInjector");
		synchronized(LOCK) {
			String candidatePlanHash = validateAuthorityAndHash(program, result);
			CommittedPlan existing = COMMITTED.get(program);
			if(existing != null) {
				if(existing.receipt().planHash().equals(candidatePlanHash))
					return new PlacementEmissionReceipt(candidatePlanHash, false, true, 0, 0);
				if(!replaceExisting)
					throw new PlacementEmissionException("A different placement plan was already emitted");
			}
			PreparedEmission prepared = prevalidate(program, result);

			Map<Hop, HopSnapshot> currentHopSnapshots = snapshotHops(prepared.hopWrites());
			if(existing != null)
				existing.baselineHops().keySet().forEach(hop -> currentHopSnapshots.putIfAbsent(hop,
					HopSnapshot.capture(hop)));
			RegistrySnapshots currentRegistrySnapshots = RegistrySnapshots.capture();
			Map<DMLProgram, CommittedPlan> receiptSnapshot = new IdentityHashMap<>(COMMITTED);
			long fallbackSnapshot = runtimeFallbackCount;
			long repairSnapshot = runtimeRepairCount;
			Map<Hop, HopSnapshot> baselineHops = existing == null
				? Collections.unmodifiableMap(new IdentityHashMap<>(currentHopSnapshots)) : existing.baselineHops();
			RegistrySnapshots baselineRegistries = existing == null
				? currentRegistrySnapshots : existing.baselineRegistries();
			try {
				if(existing != null) {
					baselineRegistries.restore();
					restoreHops(baselineHops);
				}
				applyHops(prepared.hopWrites(), failureInjector);
				applyRegistries(prepared.registryWrites(), failureInjector);
				PlacementEmissionReceipt receipt = new PlacementEmissionReceipt(prepared.planHash(), true, false,
					prepared.hopWrites().size(), prepared.registryWrites().size());
				COMMITTED.put(program, new CommittedPlan(receipt, baselineHops, baselineRegistries));
				return receipt;
			}
			catch(RuntimeException | Error failure) {
				currentRegistrySnapshots.restore();
				restoreHops(currentHopSnapshots);
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
		Map<CompiledHopKey, PlacementEmissionState> selected = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(result.selectedEmissionStates(), "selectedEmissionStates")));
		List<RelocationActionKey> relocations = List.copyOf(Objects.requireNonNull(result.selectedRelocations(),
			"selectedRelocations"));
		List<LocalMaterializationActionKey> locals = typedLocalMaterializations(result);
		return canonicalPlanHash(plannerId, analysisFingerprint, selected, relocations, locals, objective);
	}

	private static String canonicalPlanHash(String plannerId, String analysisFingerprint,
		Map<CompiledHopKey, PlacementEmissionState> selected, List<RelocationActionKey> relocations,
		List<LocalMaterializationActionKey> locals, String objective) {
		StringBuilder canonical = new StringBuilder().append(plannerId).append('\n')
			.append(analysisFingerprint).append('\n');
		selected.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> canonical
			.append(Objects.requireNonNull(entry.getKey(), "selected state key").normalizedSignature())
			.append('=').append(Objects.requireNonNull(entry.getValue(), "selected emission state")
				.placementState().normalizedSignature())
			.append(entry.getValue().derivedFedFout() ? "|derivedFedFout=true" : "")
			.append('\n'));
		relocations.stream().map(key -> Objects.requireNonNull(key, "selected relocation"))
			.sorted(Comparator.comparing(RelocationActionKey::normalizedSignature))
			.forEach(relocation -> canonical.append(relocation.normalizedSignature()).append('\n'));
		locals.stream().map(key -> Objects.requireNonNull(key, "selected local materialization"))
			.sorted(Comparator.comparing(LocalMaterializationActionKey::normalizedSignature))
			.forEach(local -> canonical.append("LOCAL=").append(local.normalizedSignature()).append('\n'));
		canonical.append(objective);
		return sha256(canonical.toString());
	}

	private static PreparedEmission prevalidate(DMLProgram program, NormalizedPlannerResult result) {
		String planHash = validateAuthorityAndHash(program, result);
		PlacementAnalysis analysis = result.analysis();
		analysis.assertProgramStructureUnchanged();
		String analysisFingerprint = result.analysisFingerprint();
		String plannerId = result.plannerId();
		String objective = result.objectiveCertificate();
		Map<CompiledHopKey, PlacementEmissionState> selected = Collections.unmodifiableMap(new LinkedHashMap<>(
			result.selectedEmissionStates()));
		List<RelocationActionKey> selectedRelocations = List.copyOf(result.selectedRelocations());
		List<LocalMaterializationActionKey> selectedLocals = typedLocalMaterializations(result);
		if(!planHash.equals(canonicalPlanHash(plannerId, analysisFingerprint, selected,
			selectedRelocations, selectedLocals, objective)))
			throw new PlacementEmissionException("Normalized plan changed during prevalidation");

		List<Node> decisionNodes = analysis.graph().decisionNodes();
		if(selected.size() != decisionNodes.size())
			throw new PlacementEmissionException("Selected placement does not cover every decision node");
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences = occurrenceIndex(analysis);
		Set<CompiledHopKey> selectedIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<Hop, HopWrite> writesByHop = new IdentityHashMap<>();
		for(Node node : decisionNodes) {
			PlacementEmissionState emissionState = exactEmissionState(selected, node.key());
			if(emissionState == null)
				throw new PlacementEmissionException("Selected placement is missing an analysis-owned decision");
			PlacementState state = emissionState.placementState();
			if(!node.legalAlternatives().contains(state))
				throw new PlacementEmissionException("Selected placement contains an illegal state");
			if(emissionState.derivedFedFout() && (state.execType() != ExecType.FED
				|| state.output() != FederatedOutput.FOUT))
				throw new PlacementEmissionException("Derived FED/FOUT authority requires FED/FOUT placement");
			if("recompile".equals(node.key().recompileContext()) && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				throw new PlacementEmissionException("Recompile regions cannot emit CP/FOUT");
			HopOccurrenceProjection occurrence = occurrences.get(node.key());
			if(occurrence == null)
				throw new PlacementEmissionException("Emitted Hop ownership is missing");
			HopWrite prior = writesByHop.get(occurrence.hop());
			if(prior != null && (prior.state() != state
				|| prior.derivedFedFout() != emissionState.derivedFedFout()))
				throw new PlacementEmissionException("One concrete Hop has conflicting occurrence authority");
			selectedIdentities.add(node.key());
			writesByHop.putIfAbsent(occurrence.hop(), new HopWrite(occurrence.hop(), state,
				emissionState.derivedFedFout()));
		}
		for(CompiledHopKey key : selected.keySet())
			if(!selectedIdentities.contains(key))
				throw new PlacementEmissionException("Selected placement contains a foreign decision key");

		List<RelocationAction> relocations = exactRelocations(analysis, selectedRelocations);
		List<LocalMaterializationActionKey> locals = exactLocalMaterializations(analysis, occurrences,
			selected, selectedLocals);
		List<RegistryWrite> registryWrites = prepareRegistryWrites(analysis, occurrences, relocations, locals);
		return new PreparedEmission(planHash, List.copyOf(writesByHop.values()), List.copyOf(registryWrites));
	}

	private static String validateAuthorityAndHash(DMLProgram program, NormalizedPlannerResult result) {
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(result, "result");
		PlacementAnalysis analysis = Objects.requireNonNull(result.analysis(), "result.analysis");
		analysis.assertCanonicalProgramAuthority(program);
		String analysisFingerprint = requireText(result.analysisFingerprint(), "analysisFingerprint");
		if(!analysis.analysisFingerprint().equals(analysisFingerprint))
			throw new PlacementEmissionException("Placement result has a stale analysis fingerprint");
		String plannerId = requireText(result.plannerId(), "plannerId");
		String objective = requireText(result.objectiveCertificate(), "objectiveCertificate");
		String planHash = requireCanonicalHash(result.normalizedPlanFingerprint());
		Map<CompiledHopKey, PlacementEmissionState> selected = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(result.selectedEmissionStates(), "selectedEmissionStates")));
		List<RelocationActionKey> selectedRelocations = List.copyOf(Objects.requireNonNull(
			result.selectedRelocations(), "selectedRelocations"));
		List<LocalMaterializationActionKey> selectedLocals = typedLocalMaterializations(result);
		if(!planHash.equals(canonicalPlanHash(plannerId, analysisFingerprint, selected,
			selectedRelocations, selectedLocals, objective)))
			throw new PlacementEmissionException("Normalized plan fingerprint does not match canonical content");
		return planHash;
	}

	private static List<LocalMaterializationActionKey> typedLocalMaterializations(
		NormalizedPlannerResult result) {
		List<?> source = Objects.requireNonNull(result.selectedLocalMaterializations(),
			"selectedLocalMaterializations");
		List<LocalMaterializationActionKey> typed = new ArrayList<>(source.size());
		for(Object value : source) {
			if(!(value instanceof LocalMaterializationActionKey action))
				throw new PlacementEmissionException("Selected local materialization has a foreign authority type");
			typed.add(action);
		}
		return List.copyOf(typed);
	}

	private static Map<CompiledHopKey, HopOccurrenceProjection> occurrenceIndex(PlacementAnalysis analysis) {
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			if(occurrences.put(occurrence.key(), occurrence) != null)
				throw new PlacementEmissionException("Analysis contains duplicate occurrence identity");
		return occurrences;
	}

	private static PlacementEmissionState exactEmissionState(Map<CompiledHopKey, PlacementEmissionState> selected,
		CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return Objects.requireNonNull(entry.getValue(), "selected state");
		return null;
	}

	private static List<LocalMaterializationActionKey> exactLocalMaterializations(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences,
		Map<CompiledHopKey, PlacementEmissionState> selected,
		List<LocalMaterializationActionKey> actions) {
		Set<String> seen = new LinkedHashSet<>();
		List<LocalMaterializationActionKey> resolved = new ArrayList<>();
		for(LocalMaterializationActionKey action : actions) {
			Objects.requireNonNull(action, "selected local materialization");
			if(!seen.add(action.normalizedSignature()))
				throw new PlacementEmissionException("Selected local materialization is duplicated");
			HopOccurrenceProjection source = exactOccurrence(occurrences, action.sourceOccurrence());
			if(source == null || !analysis.isCompiledHopOccurrence(action.sourceOccurrence()))
				throw new PlacementEmissionException("LOCAL source occurrence is foreign or virtual");
			Node sourceNode = analysis.graph().node(action.sourceOccurrence()).orElseThrow();
			if(!sourceNode.valueVersion().equals(action.sourceValueVersion()))
				throw new PlacementEmissionException("LOCAL source value version differs");
			PlacementEmissionState sourceState = exactEmissionState(selected, action.sourceOccurrence());
			if(sourceState == null || sourceState.placementState() != action.producerPlacement()
				|| action.producerPlacement().execType() != ExecType.FED
				|| action.producerPlacement().output() != FederatedOutput.FOUT
				|| action.producerPlacement().fType() == null)
				throw new PlacementEmissionException("LOCAL producer authority is not exact FED/FOUT");
			String expectedScope = source.scopeId() + ":" + action.sourceOccurrence().functionNamespace();
			if(action.statementBlockScope() == null || action.statementBlockScope().isBlank()
				|| !expectedScope.equals(action.statementBlockScope()))
				throw new PlacementEmissionException("LOCAL statement-block scope differs");
			String expectedProvenance = "fed-init:" + action.sourceValueVersion().lexicalVariable();
			if(action.durableProvenance() == null || action.durableProvenance().isBlank()
				|| !expectedProvenance.equals(action.durableProvenance()))
				throw new PlacementEmissionException("LOCAL durable provenance differs");
			List<LocalMaterializationObligation> expected = analysis.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.producer() == action.sourceOccurrence())
				.filter(edge -> {
					PlacementEmissionState consumer = exactEmissionState(selected, edge.consumer());
					return consumer != null && consumer.placementState().execType() == ExecType.CP
						&& consumer.placementState().output() == FederatedOutput.LOUT;
				})
				.map(edge -> new LocalMaterializationObligation(edge.consumer(), edge.inputPosition(),
					exactEmissionState(selected, edge.consumer()).placementState())).sorted().toList();
			List<LocalMaterializationObligation> actual = action.obligations();
			if(actual.isEmpty() || !actual.equals(actual.stream().sorted().toList())
				|| actual.stream().distinct().count() != actual.size() || !actual.equals(expected))
				throw new PlacementEmissionException("LOCAL obligations are not exact, sorted, unique, and complete");
			for(LocalMaterializationObligation obligation : actual) {
				if(obligation.inputPosition() < 0
					|| exactOccurrence(occurrences, obligation.consumerOccurrence()) == null)
					throw new PlacementEmissionException("LOCAL consumer occurrence is foreign");
				analysis.requireExactCompiledInputEdge(action.sourceOccurrence(), obligation.consumerOccurrence(),
					obligation.inputPosition());
				PlacementEmissionState consumer = exactEmissionState(selected, obligation.consumerOccurrence());
				if(consumer == null || consumer.placementState() != obligation.requiredPlacement()
					|| obligation.requiredPlacement().execType() != ExecType.CP
					|| obligation.requiredPlacement().output() != FederatedOutput.LOUT)
					throw new PlacementEmissionException("LOCAL consumer placement authority differs");
			}
			resolved.add(action);
		}
		return resolved;
	}

	private static HopOccurrenceProjection exactOccurrence(
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences, CompiledHopKey key) {
		for(Map.Entry<CompiledHopKey, HopOccurrenceProjection> entry : occurrences.entrySet())
			if(entry.getKey() == key) return entry.getValue();
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
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences, List<RelocationAction> relocations,
		List<LocalMaterializationActionKey> locals) {
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
		for(LocalMaterializationActionKey local : locals) {
			HopOccurrenceProjection source = exactOccurrence(occurrences, local.sourceOccurrence());
			List<Long> consumerIds = local.obligations().stream()
				.map(obligation -> exactOccurrence(occurrences, obligation.consumerOccurrence()).hop().getHopID())
				.distinct().sorted().toList();
			RegistryWrite write = RegistryWrite.local(source.scopeId(), source.hop().getHopID(), consumerIds,
				local.producerPlacement().fType().name(), local.durableProvenance());
			if(!slots.add(write.slot()))
				throw new PlacementEmissionException("Multiple actions target one registry slot");
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
			write.hop().setFederatedOutputDerived(write.derivedFedFout());
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

	private record HopWrite(Hop hop, PlacementState state, boolean derivedFedFout) { }

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
	private record CommittedPlan(PlacementEmissionReceipt receipt, Map<Hop, HopSnapshot> baselineHops,
		RegistrySnapshots baselineRegistries) { }

	private static final class PlacementEmissionException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		private PlacementEmissionException(String message) {
			super(message);
		}
	}
}
