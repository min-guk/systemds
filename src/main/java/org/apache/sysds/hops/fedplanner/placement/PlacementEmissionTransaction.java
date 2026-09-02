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
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationObligation;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry.ConsumerInputSpec;
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
			PlannerRuntimeActionRegistry.Snapshot candidateRuntimeActions =
				prepared.runtimeActionSnapshot();
			PlannerRuntimePlacementAudit.PreparedRegistration runtimeAudit =
				PlannerRuntimePlacementAudit.prepareRegistration(result, candidateRuntimeActions);
			tracePreparedEmission(result, prepared);

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
				PlannerRuntimeActionRegistry.restore(candidateRuntimeActions);
				PlacementEmissionReceipt receipt = new PlacementEmissionReceipt(prepared.planHash(), true, false,
					prepared.hopWrites().size(), prepared.registryWrites().size());
				COMMITTED.put(program, new CommittedPlan(receipt, result, baselineHops, baselineRegistries));
				runtimeAudit.commit();
				result.analysis().authorizeCommittedProgramStructure();
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
			PlannerRuntimePlacementAudit.resetForTesting();
			PlannerRuntimeActionRegistry.clear();
		}
	}

	public static Map<DMLProgram, PlacementEmissionReceipt> receiptSnapshotForTesting() {
		synchronized(LOCK) {
			Map<DMLProgram, PlacementEmissionReceipt> snapshot = new IdentityHashMap<>();
			COMMITTED.forEach((program, committed) -> snapshot.put(program, committed.receipt()));
			return Collections.unmodifiableMap(snapshot);
		}
	}

	/** Return the current complete immutable authority for planner-owned dynamic replacement. */
	public static NormalizedPlannerResult currentNormalizedResult(DMLProgram program) {
		Objects.requireNonNull(program, "program");
		synchronized(LOCK) {
			CommittedPlan committed = COMMITTED.get(program);
			if(committed == null)
				throw new PlacementEmissionException("No complete placement authority is committed");
			return committed.result();
		}
	}

	/**
	 * Proves that a planner invocation performed exactly one atomic emission before
	 * the compiler crosses the final Hop boundary.  The identity checks are
	 * intentional: a second {@link #emit} call returns a distinct no-op receipt and
	 * must not be accepted as evidence for the original commit.
	 */
	public static void verifyFinalBoundaryCommit(DMLProgram program, NormalizedPlannerResult result,
		PlacementEmissionReceipt receipt) {
		Objects.requireNonNull(program, "program");
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(receipt, "receipt");
		synchronized(LOCK) {
			CommittedPlan committed = COMMITTED.get(program);
			if(committed == null)
				throw new PlacementEmissionException(
					"Planner returned without committing a complete placement authority");
			if(committed.result() != result)
				throw new PlacementEmissionException(
					"Planner receipt does not own the committed normalized placement result");
			if(committed.receipt() != receipt)
				throw new PlacementEmissionException(
					"Planner receipt is not the receipt of the atomic placement commit");
			if(!receipt.applied() || receipt.noOp())
				throw new PlacementEmissionException(
					"Final Hop boundary requires one applied emission, not a no-op");
			if(!canonicalPlanHash(result).equals(receipt.planHash()))
				throw new PlacementEmissionException(
					"Committed placement receipt differs from the normalized result");
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
		PlacementAnalysis analysis = Objects.requireNonNull(result.analysis(), "result.analysis");
		String plannerId = requireText(result.plannerId(), "plannerId");
		String analysisFingerprint = requireText(result.analysisFingerprint(), "analysisFingerprint");
		String objective = requireText(result.objectiveCertificate(), "objectiveCertificate");
		Map<CompiledHopKey, PlacementEmissionState> selected = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(result.selectedEmissionStates(), "selectedEmissionStates")));
		List<RelocationActionKey> relocations = List.copyOf(Objects.requireNonNull(result.selectedRelocations(),
			"selectedRelocations"));
		List<CandidateSelectionReceipt> candidates = List.copyOf(Objects.requireNonNull(
			result.selectedCandidateSelections(), "selectedCandidateSelections"));
		List<RelocationChoiceReceipt> choices = List.copyOf(Objects.requireNonNull(
			result.selectedRelocationChoices(), "selectedRelocationChoices"));
		List<LocalMaterializationActionKey> locals = typedLocalMaterializations(result);
		return canonicalPlanHash(analysis, plannerId, analysisFingerprint, selected, candidates,
			choices, relocations, locals, objective);
	}

	private static String canonicalPlanHash(PlacementAnalysis analysis,
		String plannerId, String analysisFingerprint,
		Map<CompiledHopKey, PlacementEmissionState> selected,
		List<CandidateSelectionReceipt> candidates, List<RelocationChoiceReceipt> choices,
		List<RelocationActionKey> relocations,
		List<LocalMaterializationActionKey> locals, String objective) {
		StringBuilder canonical = new StringBuilder().append(plannerId).append('\n')
			.append(analysisFingerprint).append('\n');
		selected.entrySet().stream().sorted(Comparator.comparing(entry ->
			analysis.normalizedOccurrenceSignature(
				Objects.requireNonNull(entry.getKey(), "selected state key")))).forEach(entry -> canonical
			.append(analysis.normalizedOccurrenceSignature(entry.getKey()))
			.append('=').append(Objects.requireNonNull(entry.getValue(), "selected emission state")
				.placementState().normalizedSignature())
			.append(entry.getValue().derivedFedFout() ? "|derivedFedFout=true" : "")
			.append('\n'));
		analysis.canonicalCandidateReceipts(candidates).forEach(candidate -> canonical.append("CANDIDATE=")
				.append(candidate.normalizedSignature()).append('\n'));
		analysis.relocationOrder().canonicalChoices(choices).forEach(choice -> canonical.append("CHOICE=")
				.append(choice.normalizedSignature()).append('\n'));
		analysis.relocationOrder().canonicalActions(relocations).stream()
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
		Map<CompiledHopKey, PlacementState> selectedStates = new LinkedHashMap<>();
		selected.forEach((key, state) -> selectedStates.put(key, state.placementState()));
		List<CandidateSelectionReceipt> selectedCandidates = List.copyOf(
			result.selectedCandidateSelections());
		Map<CompiledHopKey,CandidateSelectionReceipt> selectedCandidatesByConsumer =
			CandidateSelections.indexByConsumer(selectedCandidates);
		List<RelocationChoiceReceipt> selectedChoices = List.copyOf(
			result.selectedRelocationChoices());
		List<RelocationActionKey> selectedRelocations = List.copyOf(result.selectedRelocations());
		List<LocalMaterializationActionKey> selectedLocals = typedLocalMaterializations(result);
		if(!planHash.equals(canonicalPlanHash(analysis, plannerId, analysisFingerprint, selected,
			selectedCandidates, selectedChoices, selectedRelocations, selectedLocals, objective)))
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
			selectedIdentities.add(node.key());
			// Synthetic function boundaries are normalized semantic authority, but do not
			// own an independent compiled Hop mutation. Their exact state is validated
			// above and is projected structurally by the planner from its source.
			if(!analysis.isCompiledHopOccurrence(node.key()))
				continue;
			Set<String> modeledRewriteKinds = modeledRewriteKinds(analysis, node.key(),
				emissionState, selectedStates, selectedCandidatesByConsumer.get(node.key()));
			HopWrite prior = writesByHop.get(occurrence.hop());
			if(prior != null && (!prior.state().equals(state)
				|| prior.derivedFedFout() != emissionState.derivedFedFout()))
				throw new PlacementEmissionException("One concrete Hop has conflicting occurrence authority");
			if(prior == null)
				writesByHop.put(occurrence.hop(), new HopWrite(occurrence.hop(), state,
					emissionState.derivedFedFout(), modeledRewriteKinds));
			else {
				Set<String> commonRewriteKinds = new LinkedHashSet<>(prior.modeledRewriteKinds());
				commonRewriteKinds.retainAll(modeledRewriteKinds);
				writesByHop.put(occurrence.hop(), new HopWrite(occurrence.hop(), state,
					emissionState.derivedFedFout(), Set.copyOf(commonRewriteKinds)));
			}
		}
		for(CompiledHopKey key : selected.keySet())
			if(!selectedIdentities.contains(key))
				throw new PlacementEmissionException("Selected placement contains a foreign decision key");
		validateExactGraphConstraints(analysis, selectedStates);

		List<SelectedRelocation> relocations = exactRelocations(
			analysis, selectedStates, selectedCandidates, selectedChoices, selectedRelocations);
		List<SelectedFoutMaterialization> foutMaterializations = exactFoutMaterializations(
			analysis, occurrences, selected, selectedCandidates);
		List<LocalMaterializationActionKey> locals = exactLocalMaterializations(analysis, occurrences,
			selected, selectedCandidates, selectedLocals);
		List<RegistryWrite> registryWrites = prepareRegistryWrites(
			analysis, occurrences, selected, selectedCandidates, relocations,
			foutMaterializations, locals);
		return new PreparedEmission(planHash, List.copyOf(writesByHop.values()), List.copyOf(registryWrites),
			runtimeActionSnapshot(registryWrites));
	}

	private static Set<String> modeledRewriteKinds(PlacementAnalysis analysis, CompiledHopKey key,
		PlacementEmissionState ownerEmission, Map<CompiledHopKey,PlacementState> selectedStates,
		CandidateSelectionReceipt selectedCandidate) {
		PlacementCostSemantics.DirectWdivmmRuntimeFact runtime =
			PlacementCostSemantics.directWdivmmRuntimeFact(analysis, key);
		if(runtime == null)
			return Set.of();
		PlacementState owner = ownerEmission.placementState();
		PlacementState weights = selectedStates.get(runtime.weights());
		FType executionFType = selectedCandidate == null ? owner.fType()
			: selectedCandidate.emission().executionFType();
		if(!PlacementCostSemantics.directWdivmmRuntimeAssignmentCompatible(runtime, owner,
			executionFType, ownerEmission.derivedFedFout(), weights)) {
			if(owner.execType() == ExecType.FED)
				throw new PlacementEmissionException(
					"Selected direct WDivMM placement violates its shared runtime-input contract");
			return Set.of();
		}
		if(owner.execType() == ExecType.CP && owner.output() != FederatedOutput.LOUT)
			return Set.of();
		return Set.of(FederatedPlannerUtils.REWRITE_DIRECT_WDIVMM_PATTERN_2);
	}

	private static void validateExactGraphConstraints(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected) {
		for(Constraint constraint : analysis.graph().constraints()) {
			Node leftNode = analysis.graph().node(constraint.left()).orElseThrow();
			Node rightNode = analysis.graph().node(constraint.right()).orElseThrow();
			// Trace-only/non-emitted nodes have no planner-owned runtime placement. Every
			// constraint whose two endpoints are actual decisions must hold at the atomic
			// emission boundary; the transaction validates but never repairs the plan.
			if(!leftNode.emittedWork() || !rightNode.emittedWork())
				continue;
			PlacementState left = selected.get(constraint.left());
			PlacementState right = selected.get(constraint.right());
			if(left == null || right == null)
				throw new PlacementEmissionException(
					"Selected placement constraint endpoint is missing: " + constraint.normalizedSignature());
			if(!NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
				throw new PlacementEmissionException("Selected placement violates exact graph constraint: "
					+ constraint.normalizedSignature() + " left=" + left.normalizedSignature()
					+ " right=" + right.normalizedSignature());
		}
	}

	private static PlannerRuntimeActionRegistry.Snapshot runtimeActionSnapshot(List<RegistryWrite> writes) {
		RegistrySnapshots current = RegistrySnapshots.capture();
		try {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
			for(RegistryWrite write : writes)
				write.apply();
			return new PlannerRuntimeActionRegistry.Snapshot(FederatedRefedRegistry.snapshotAll(),
				FederatedFoutMaterializeRegistry.snapshotAll(),
				FederatedLocalMaterializeRegistry.snapshotAll());
		}
		finally {
			current.restore();
		}
	}

	/**
	 * Exposes the exact normalized authority accepted by the emission boundary.
	 * This is observational only: the records are produced after complete
	 * prevalidation and before the first Hop or registry mutation.
	 */
	private static void tracePreparedEmission(NormalizedPlannerResult result, PreparedEmission prepared) {
		if(!FederatedPlannerTrace.isEnabled())
			return;
		PlacementAnalysis analysis = result.analysis();
		Map<CompiledHopKey, PlacementEmissionState> selected = result.selectedEmissionStates();
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences = occurrenceIndex(analysis);
		List<Node> decisions = analysis.graph().decisionNodes().stream()
			.sorted(Comparator.comparing(Node::key)).toList();
		List<CandidateSelectionReceipt> candidates = result.selectedCandidateSelections().stream()
			.sorted().toList();
		StringBuilder placementAuthority = new StringBuilder();
		long compiledOccurrences = 0;
		long emittedWork = 0;
		long fed = 0;
		long fout = 0;
		long derivedFout = 0;
		for(Node node : decisions) {
			PlacementEmissionState emission = exactEmissionState(selected, node.key());
			if(emission == null)
				throw new PlacementEmissionException("Emission trace is missing an exact decision state");
			boolean compiled = analysis.isCompiledHopOccurrence(node.key());
			if(compiled)
				compiledOccurrences++;
			if(node.emittedWork())
				emittedWork++;
			if(emission.placementState().execType() == ExecType.FED)
				fed++;
			if(emission.placementState().output() == FederatedOutput.FOUT)
				fout++;
			if(emission.derivedFedFout())
				derivedFout++;
			placementAuthority.append(node.key().normalizedSignature()).append('=')
				.append(emission.normalizedSignature()).append('\n');
			HopOccurrenceProjection occurrence = occurrences.get(node.key());
			if(occurrence == null)
				throw new PlacementEmissionException("Emission trace is missing occurrence ownership");
			FederatedPlannerTrace.logLazy(occurrence.hop(), "Emission-Select", () ->
				"planner=" + result.plannerId()
					+ " key=" + node.key().normalizedSignature()
					+ " nodeKind=" + node.kind()
					+ " emittedWork=" + node.emittedWork()
					+ " compiledOccurrence=" + compiled
					+ " selected=" + emission.placementState().normalizedSignature()
					+ " derivedFedFout=" + emission.derivedFedFout());
		}
		for(CandidateSelectionReceipt candidate : candidates) {
			HopOccurrenceProjection occurrence = exactOccurrence(occurrences,
				candidate.rule().parentOccurrence());
			if(occurrence == null)
				throw new PlacementEmissionException("Emission candidate trace is missing occurrence ownership");
			String inputs = String.join(",", candidate.rule().orderedInputs().stream()
				.map(PlacementAnalysis.CandidateInputState::normalizedSignature).toList());
			FederatedPlannerTrace.logLazy(occurrence.hop(), "Emission-Candidate", () ->
				"planner=" + result.plannerId()
					+ " key=" + candidate.rule().parentOccurrence().normalizedSignature()
					+ " inputs=" + inputs
					+ " emission=" + candidate.emission().emissionState().placementState().normalizedSignature()
					+ " executionFType=" + (candidate.emission().executionFType() == null
						? "-" : candidate.emission().executionFType().name())
					+ " derivedFedFout=" + candidate.emission().emissionState().derivedFedFout()
					+ " foutMaterializationAction=" + (candidate.emission().derivedFoutAction() == null
						? "-" : sha256(candidate.emission().derivedFoutAction().normalizedSignature())));
		}
		prepared.registryWrites().stream()
			.sorted(Comparator.comparing(write -> write.slot().kind().name()
				+ ':' + write.slot().scopeId() + ':' + write.slot().hopId()))
			.forEach(write -> FederatedPlannerTrace.logGlobal("Emission-RegistryWrite",
				"planner=" + result.plannerId()
					+ " kind=" + write.slot().kind()
					+ " scope=" + write.slot().scopeId()
					+ " producerHop=" + write.slot().hopId()
					+ " anchorHop=" + write.anchorHopId()
					+ " fType=" + write.fType()
					+ " refedInputs=" + (write.refedAuthority() == null ? List.of()
						: write.refedAuthority().getConsumerInputs())
					+ " refedLocalStages=" + (write.refedAuthority() == null ? List.of()
						: write.refedAuthority().getAuthorities().stream()
							.map(FederatedRefedRegistry.AuthoritySpec::getRequiresLocalMaterialization).toList())
					+ " foutInputs=" + (write.foutConsumerInputs() == null ? List.of()
						: write.foutConsumerInputs())
					+ " localInputs=" + (write.localConsumerInputs() == null ? List.of()
						: write.localConsumerInputs())
					+ " anchorKey=" + write.anchorKey()
					+ " reason=" + write.reason()));
		String candidateAuthority = String.join("\n", candidates.stream()
			.map(CandidateSelectionReceipt::normalizedSignature).toList());
		FederatedPlannerTrace.logGlobal("Emission-Summary", "planner=" + result.plannerId()
			+ " analysis=" + result.analysisFingerprint()
			+ " planFingerprint=" + result.normalizedPlanFingerprint()
			+ " placementFingerprint=" + sha256(placementAuthority.toString())
			+ " candidateFingerprint=" + sha256(candidateAuthority)
			+ " decisions=" + decisions.size()
			+ " compiledOccurrences=" + compiledOccurrences
			+ " syntheticDecisions=" + (decisions.size() - compiledOccurrences)
			+ " emittedWork=" + emittedWork
			+ " selectedFED=" + fed
			+ " selectedFOUT=" + fout
			+ " selectedDerivedFOUT=" + derivedFout
			+ " relocations=" + result.selectedRelocations().size()
			+ " localMaterializations=" + result.selectedLocalMaterializations().size()
			+ " cpFoutMaterializations="
				+ CandidateSelections.cpFoutPhysicalEmissionCount(candidates)
			+ " derivedFoutMaterializations="
				+ CandidateSelections.derivedFoutPhysicalEmissionCount(candidates)
			+ " selectedCandidates=" + candidates.size()
			+ " hopMutations=" + prepared.hopWrites().size()
			+ " registryWrites=" + prepared.registryWrites().size());
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
		List<CandidateSelectionReceipt> selectedCandidates = List.copyOf(Objects.requireNonNull(
			result.selectedCandidateSelections(), "selectedCandidateSelections"));
		List<RelocationChoiceReceipt> selectedChoices = List.copyOf(Objects.requireNonNull(
			result.selectedRelocationChoices(), "selectedRelocationChoices"));
		List<LocalMaterializationActionKey> selectedLocals = typedLocalMaterializations(result);
		if(!planHash.equals(canonicalPlanHash(analysis, plannerId, analysisFingerprint, selected,
			selectedCandidates, selectedChoices, selectedRelocations, selectedLocals, objective)))
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
		List<CandidateSelectionReceipt> selectedCandidates,
		List<LocalMaterializationActionKey> actions) {
		Map<CompiledHopKey,PlacementState> selectedStates = new IdentityHashMap<>();
		selected.forEach((key, state) -> selectedStates.put(key, state.placementState()));
		List<LocalMaterializationActionKey> canonical;
		try {
			canonical = LocalMaterializationSelections.derive(
				analysis, selectedStates, selected, selectedCandidates);
		}
		catch(IllegalArgumentException | IllegalStateException ex) {
			throw new PlacementEmissionException(
				"Cannot derive exact LOCAL materialization authority: " + ex.getMessage(), ex);
		}
		if(!actions.stream().sorted().toList().equals(canonical))
			throw new PlacementEmissionException(
				"Selected LOCAL materializations differ from the canonical exact projection");
		Set<String> seen = new LinkedHashSet<>();
		List<LocalMaterializationActionKey> resolved = new ArrayList<>();
		for(LocalMaterializationActionKey action : canonical) {
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
			if(sourceState == null || !sourceState.placementState().equals(action.producerPlacement())
				|| sourceState.derivedFedFout()
				|| action.producerPlacement().execType() != ExecType.FED
				|| action.producerPlacement().output() != FederatedOutput.FOUT
				|| action.producerPlacement().fType() == null)
				throw new PlacementEmissionException("LOCAL producer authority is not exact FED/FOUT");
			String expectedScope = source.scopeId() + ":" + action.sourceOccurrence().functionNamespace();
			if(action.statementBlockScope() == null || action.statementBlockScope().isBlank()
				|| !expectedScope.equals(action.statementBlockScope()))
				throw new PlacementEmissionException("LOCAL statement-block scope differs");
			String expectedProvenance;
			try {
				expectedProvenance = NormalizedPlannerResults.durableLocalProvenance(
					sourceNode, action.producerPlacement());
			}
			catch(IllegalStateException ex) {
				throw new PlacementEmissionException(ex.getMessage(), ex);
			}
			if(action.durableProvenance() == null || action.durableProvenance().isBlank()
				|| !expectedProvenance.equals(action.durableProvenance()))
				throw new PlacementEmissionException("LOCAL durable provenance differs");
			List<LocalMaterializationObligation> actual = action.obligations();
			if(actual.isEmpty() || !actual.equals(actual.stream().sorted().toList())
				|| actual.stream().distinct().count() != actual.size())
				throw new PlacementEmissionException("LOCAL obligations are not exact, sorted, unique, and complete");
			for(LocalMaterializationObligation obligation : actual) {
				if(obligation.inputPosition() < 0
					|| exactOccurrence(occurrences, obligation.consumerOccurrence()) == null)
					throw new PlacementEmissionException("LOCAL consumer occurrence is foreign");
				try {
					analysis.requireExactCompiledInputEdge(action.sourceOccurrence(),
						obligation.consumerOccurrence(), obligation.inputPosition());
				}
				catch(IllegalArgumentException noCompiledEdge) {
					throw new PlacementEmissionException(
						"LOCAL obligation has no exact compiled matrix input edge: action="
							+ action.normalizedSignature() + " obligation=" + obligation
							+ " diagnostic=" + noCompiledEdge.getMessage());
				}
				PlacementEmissionState consumer = exactEmissionState(selected, obligation.consumerOccurrence());
				if(consumer == null || !consumer.placementState().equals(obligation.requiredPlacement()))
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

	private static List<SelectedRelocation> exactRelocations(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> choices, List<RelocationActionKey> emittedActions) {
		if(analysis.graph().relocationActions().isEmpty() && choices.isEmpty() && emittedActions.isEmpty())
			return List.of();
		List<RelocationSelections.ResolvedChoice> resolved;
		try {
			resolved = RelocationSelections.resolveAndValidate(analysis, selected, candidates, choices);
		}
		catch(IllegalArgumentException | IllegalStateException ex) {
			throw new PlacementEmissionException("Invalid exact relocation choices: " + ex.getMessage());
		}
		List<RelocationActionKey> projected = resolved.stream()
			.filter(RelocationSelections.ResolvedChoice::requiresEmission)
			.map(choice -> choice.action().key()).distinct().sorted().toList();
		if(!projected.equals(emittedActions.stream().sorted().toList()))
			throw new PlacementEmissionException("Relocation choices and emitted actions differ");
		Map<RelocationActionKey,List<ObligationKey>> obligations = new LinkedHashMap<>();
		Map<RelocationActionKey,RelocationAction> actions = new LinkedHashMap<>();
		for(RelocationSelections.ResolvedChoice choice : resolved) {
			if(!choice.requiresEmission())
				continue;
			actions.putIfAbsent(choice.action().key(), choice.action());
			obligations.computeIfAbsent(choice.action().key(), ignored -> new ArrayList<>())
				.add(choice.obligation());
		}
		List<SelectedRelocation> result = new ArrayList<>();
		for(RelocationActionKey action : actions.keySet().stream().sorted().toList())
			result.add(new SelectedRelocation(actions.get(action),
				obligations.get(action).stream().sorted().toList()));
		return List.copyOf(result);
	}

	private static List<RegistryWrite> prepareRegistryWrites(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences,
		Map<CompiledHopKey, PlacementEmissionState> selected,
		List<CandidateSelectionReceipt> selectedCandidates,
		List<SelectedRelocation> relocations,
		List<SelectedFoutMaterialization> foutMaterializations,
		List<LocalMaterializationActionKey> locals) {
		Map<RegistrySlot, RegistryWrite> writesBySlot = new LinkedHashMap<>();
		for(SelectedFoutMaterialization selectedFout : foutMaterializations) {
			DerivedFoutMaterializationActionKey action = selectedFout.action();
			HopOccurrenceProjection producer = selectedFout.producer();
			HopOccurrenceProjection anchor = selectedFout.anchor();
			List<ConsumerInputSpec> directConsumers = directFoutConsumerInputs(
				analysis, occurrences, selectedCandidates, relocations,
				action.producer(), action.producerValueVersion());
			RegistryWrite write = RegistryWrite.fout(producer.scopeId(), producer.hop().getHopID(),
				anchor.hop().getHopID(), action.materializationFType().name(),
				action.durableAnchor().placementId(),
				ExactPlacementRegistration.runtimeAnchorKey(action.durableAnchor()), directConsumers,
				action.normalizedSignature());
			addRelocationRegistryWrite(writesBySlot, write);
		}
		for(SelectedRelocation selectedRelocation : relocations) {
			RelocationAction action = selectedRelocation.action();
			RelocationActionKey key = action.key();
			List<Node> sources = analysis.graph().nodes().stream()
				.filter(node -> node.valueVersion().equals(key.sourceValueVersion()))
				.filter(node -> occurrences.containsKey(node.key())).toList();
			if(sources.size() != 1)
				throw new PlacementEmissionException("Relocation source does not have one emitted owner");
			Node sourceNode = sources.get(0);
			HopOccurrenceProjection source = occurrences.get(sourceNode.key());
			PlacementEmissionState sourceState = exactEmissionState(selected, sourceNode.key());
			if(sourceState == null)
				throw new PlacementEmissionException("Relocation source has no selected placement");
			boolean requiresLocalMaterialization =
				PlacementCostSemantics.requiresRefedLocalMaterialization(
					analysis, sourceNode, selected);
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Emission-RelocationSource",
					"source=" + sourceNode.key().normalizedSignature()
						+ " nodeKind=" + sourceNode.kind()
						+ " versionKind=" + sourceNode.valueVersion().versionKind()
						+ " emittedWork=" + sourceNode.emittedWork()
						+ " anchors=" + sourceNode.anchors().size()
						+ " selected=" + sourceState.placementState().normalizedSignature()
						+ " requiresLocalMaterialization=" + requiresLocalMaterialization
						+ " logicalCallers=" + analysis.logicalFunctionInputsInCanonicalOrder().stream()
							.filter(fact -> fact.targetRead() == sourceNode.key()
								|| fact.boundary() == sourceNode.key())
							.map(fact -> {
								Node argument = analysis.graph().node(fact.sourceArgument()).orElseThrow();
								PlacementEmissionState argumentState = exactEmissionState(selected, argument.key());
								return argument.kind() + "/" + argument.valueVersion().versionKind()
									+ "/anchors=" + argument.anchors().size() + "/selected="
									+ (argumentState == null ? "-"
										: argumentState.placementState().normalizedSignature());
							}).toList()
						+ " action=" + sha256(key.normalizedSignature()));
			List<ObligationKey> activeObligations = selectedRelocation.obligations();
			if(activeObligations.isEmpty())
				throw new PlacementEmissionException("Selected relocation has no active exact obligation");
			HopOccurrenceProjection anchor = resolveAnchor(analysis, occurrences, key);
			String anchorKey = ExactPlacementRegistration.runtimeAnchorKey(key.durableAnchor());
			// A relocation action owns one exact PRESENT input of a FED consumer. Its
			// targetPlacement is the consumer's output placement, not the direction of
			// the source transfer. Consequently FED/LOUT and FED/FOUT consumers both
			// require the same input-side operation: CP->FOUT for a local source, or
			// FED->LOUT->FOUT for a source on another worker pool. FederatedRefed lowers
			// both cases from the exact selected source edge. Branching on the consumer's
			// output incorrectly turned FED/LOUT input uploads into FOUT->LOUT downloads.
			List<ConsumerInputSpec> consumerInputs = new ArrayList<>();
			for(ObligationKey obligation : activeObligations) {
				PhysicalConsumerInput physical = exactPhysicalConsumerInput(analysis, occurrences,
					sources.get(0).key(), obligation.consumer(), obligation.inputPosition(),
					"Selected REFED obligation");
				consumerInputs.add(new ConsumerInputSpec(physical.consumerHopId(), physical.inputPosition()));
			}
			RegistryWrite write = RegistryWrite.refed(source.scopeId(), source.hop().getHopID(),
				anchor.hop().getHopID(), anchorKey, key.materializationFType().name(), consumerInputs,
				key.normalizedSignature(), requiresLocalMaterialization);
			addRelocationRegistryWrite(writesBySlot, write);
		}
		for(LocalMaterializationActionKey local : locals) {
			HopOccurrenceProjection source = exactOccurrence(occurrences, local.sourceOccurrence());
			List<FederatedLocalMaterializeRegistry.ConsumerInputSpec> consumerInputs =
				local.obligations().stream().map(obligation -> {
					PhysicalConsumerInput physical = exactPhysicalConsumerInput(analysis, occurrences,
						local.sourceOccurrence(), obligation.consumerOccurrence(), obligation.inputPosition(),
						"Selected local materialization obligation");
					return new FederatedLocalMaterializeRegistry.ConsumerInputSpec(
						physical.consumerHopId(), physical.inputPosition());
				}).distinct().sorted().toList();
			RegistryWrite write = RegistryWrite.local(source.scopeId(), source.hop().getHopID(), consumerInputs,
				local.producerPlacement().fType().name(), local.durableProvenance(),
				local.normalizedSignature());
			if(writesBySlot.putIfAbsent(write.slot(), write) != null)
				throw new PlacementEmissionException("Multiple actions target one registry slot");
		}
		return List.copyOf(writesBySlot.values());
	}

	/**
	 * Projects only direct PRESENT consumers of one derived FOUT producer. An
	 * ABSENT_LOCAL input must remain wired to the producer's physical FED/LOUT
	 * result, while a PRESENT demand with an emitted relocation is owned by the
	 * exact REFED registry instead of this source-pool materialization.
	 */
	private static List<ConsumerInputSpec> directFoutConsumerInputs(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences,
		List<CandidateSelectionReceipt> selectedCandidates,
		List<SelectedRelocation> relocations, CompiledHopKey producer,
		PlacementIdentity.ValueVersionKey producerValueVersion) {
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : selectedCandidates)
			if(candidates.put(candidate.rule().parentOccurrence(), candidate) != null)
				throw new PlacementEmissionException(
					"Derived FOUT consumer has duplicate selected candidate authority");
		List<ConsumerInputSpec> direct = new ArrayList<>();
		for(PlacementAnalysis.CompiledInputEdgeFact edge :
			analysis.compiledInputEdgesInCanonicalOrder()) {
			if(edge.producer() != producer || analysis.isDmlFunctionCallBoundary(edge.consumer()))
				continue;
			CandidateSelectionReceipt candidate = candidates.get(edge.consumer());
			if(candidate == null)
				continue;
			if(edge.inputPosition() < 0
				|| edge.inputPosition() >= candidate.rule().orderedInputs().size())
				throw new PlacementEmissionException(
					"Derived FOUT candidate does not cover one exact compiled edge");
			if(!candidate.rule().orderedInputs().get(edge.inputPosition()).present())
				continue;
			boolean relocated = relocations.stream().flatMap(relocation ->
				relocation.obligations().stream()).anyMatch(obligation ->
					obligation.sourceValueVersion().equals(producerValueVersion)
						&& obligation.consumer() == edge.consumer()
						&& obligation.inputPosition() == edge.inputPosition());
			if(relocated)
				continue;
			PhysicalConsumerInput physical = exactPhysicalConsumerInput(analysis, occurrences,
				producer, edge.consumer(), edge.inputPosition(), "Derived FOUT direct consumer");
			direct.add(new ConsumerInputSpec(physical.consumerHopId(), physical.inputPosition()));
		}
		return direct.stream().distinct().sorted().toList();
	}

	private static List<SelectedFoutMaterialization> exactFoutMaterializations(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences,
		Map<CompiledHopKey, PlacementEmissionState> selected,
		List<CandidateSelectionReceipt> selectedCandidates) {
		List<SelectedFoutMaterialization> resolved = new ArrayList<>();
		for(Node node : analysis.graph().decisionNodes()) {
			PlacementEmissionState selectedState = exactEmissionState(selected, node.key());
			if(selectedState == null || !analysis.isCompiledHopOccurrence(node.key()))
				continue;
			List<CandidateSelectionReceipt> exactCandidates = selectedCandidates.stream()
				.filter(candidate -> candidate.rule().parentOccurrence() == node.key())
				.filter(candidate -> candidate.emission().emissionState().equals(selectedState))
				.filter(candidate -> candidate.emission().derivedFoutAction() != null).toList();
			boolean cpFout = selectedState.placementState().execType() == ExecType.CP
				&& selectedState.placementState().output() == FederatedOutput.FOUT;
			if(exactCandidates.isEmpty()) {
				if(selectedState.derivedFedFout() || cpFout)
					throw new PlacementEmissionException(
						"Planner-created FOUT state requires one exact selected output materialization action");
				continue;
			}
			if(exactCandidates.size() != 1)
				throw new PlacementEmissionException(
					"Compiled planner-created FOUT state requires exactly one selected materialization action");
			CandidateSelectionReceipt candidate = exactCandidates.get(0);
			PlacementAnalysis.CandidateRuleFact exactFact;
			try {
				exactFact = analysis.candidateRuleFacts().requireExact(
					candidate.rule().parentOccurrence(), candidate.rule().orderedInputs());
			}
			catch(IllegalArgumentException ex) {
				throw new PlacementEmissionException("Selected derived candidate is not analysis-owned", ex);
			}
			if(exactFact.key() != candidate.rule() || exactFact.allowedEmissionFacts().stream()
				.filter(emission -> emission == candidate.emission()).count() != 1)
				throw new PlacementEmissionException(
					"Selected derived candidate is not the exact analysis-owned emission fact");
			DerivedFoutMaterializationActionKey action = candidate.emission().derivedFoutAction();
			long graphIdentityCount = analysis.graph().derivedFoutMaterializationActions().stream()
				.filter(graphAction -> graphAction.key() == action).count();
			if(graphIdentityCount != 1 || action.producer() != node.key()
				|| action.producerValueVersion() != node.valueVersion()
				|| action.candidateRule() != candidate.rule()
				|| action.targetPlacement() != selectedState.placementState())
				throw new PlacementEmissionException(
					"Selected derived FOUT action is not the exact graph-owned producer authority: "
						+ "graphIdentityCount=" + graphIdentityCount
						+ ", producerIdentity=" + (action.producer() == node.key())
						+ ", producerValueVersionIdentity="
						+ (action.producerValueVersion() == node.valueVersion())
						+ ", producerValueVersionEqual="
						+ action.producerValueVersion().equals(node.valueVersion())
						+ ", candidateRuleIdentity=" + (action.candidateRule() == candidate.rule())
						+ ", targetPlacementIdentity="
						+ (action.targetPlacement() == selectedState.placementState())
						+ ", targetPlacementEqual="
						+ action.targetPlacement().equals(selectedState.placementState())
						+ ", producer=" + node.key().normalizedSignature());
			HopOccurrenceProjection producer = exactOccurrence(occurrences, node.key());
			if(producer == null)
				throw new PlacementEmissionException("Derived FOUT producer occurrence is not compiled");
			String expectedScope = node.key().controlRegion().normalizedSignature();
			if(!expectedScope.equals(action.statementBlockScope()))
				throw new PlacementEmissionException("Derived FOUT statement-block scope differs");
			Node anchorOwnerNode = analysis.graph().node(action.durableAnchorOwner()).orElse(null);
			HopOccurrenceProjection anchorOwner = exactOccurrence(occurrences,
				action.durableAnchorOwner());
			PlacementEmissionState selectedAnchorState = exactEmissionState(selected,
				action.durableAnchorOwner());
			if(anchorOwnerNode == null || anchorOwner == null || selectedAnchorState == null
				|| selectedAnchorState.placementState().output() != FederatedOutput.FOUT
				|| selectedAnchorState.placementState().fType() != action.durableAnchorOwnerFType())
				throw new PlacementEmissionException(
					"Derived FOUT action does not name one selected exact compiled FOUT anchor owner");
			resolved.add(new SelectedFoutMaterialization(action, producer, anchorOwner));
		}
		return List.copyOf(resolved);
	}

	/**
	 * Converts one analysis-owned logical Hop input edge into the exact physical Lop consumer input
	 * recorded by the lowering registries.
	 *
	 * <p>A {@code FUNCTIONOUTPUT} Hop owned by a multi-return builtin is an output descriptor, not
	 * a separately executed consumer. Its input Lop is physically consumed once by the owning
	 * {@link FunctionOp}/{@code FunctionCallCP}. The projection is accepted only when the descriptor
	 * has one identity-owned FunctionOp, that call consumes the same source at one exact position,
	 * and the neutral analysis already owns that compiled input edge. No parent search fallback or
	 * candidate repair is performed.</p>
	 */
	private static PhysicalConsumerInput exactPhysicalConsumerInput(PlacementAnalysis analysis,
		Map<CompiledHopKey, HopOccurrenceProjection> occurrences, CompiledHopKey sourceKey,
		CompiledHopKey logicalConsumerKey, int logicalInputPosition, String authority) {
		try {
			analysis.requireExactCompiledInputEdge(sourceKey, logicalConsumerKey, logicalInputPosition);
		}
		catch(IllegalArgumentException noCompiledEdge) {
			throw new PlacementEmissionException(authority + " has no exact compiled input edge: "
				+ noCompiledEdge.getMessage());
		}
		HopOccurrenceProjection source = exactOccurrence(occurrences, sourceKey);
		HopOccurrenceProjection logicalConsumer = exactOccurrence(occurrences, logicalConsumerKey);
		if(source == null || logicalConsumer == null)
			throw new PlacementEmissionException(authority + " is foreign to the compiled analysis");
		if(!PlacementCostSemantics.isMultiReturnFunctionOutput(logicalConsumer.hop()))
			return new PhysicalConsumerInput(logicalConsumer.hop().getHopID(), logicalInputPosition);

		List<PhysicalConsumerInput> exact = new ArrayList<>();
		for(Hop parent : source.hop().getParent()) {
			if(!(parent instanceof FunctionOp call)
				|| call.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
				|| call.getOutputs() == null || !call.getOutputs().contains(logicalConsumer.hop()))
				continue;
			List<HopOccurrenceProjection> callOccurrences = occurrences.values().stream()
				.filter(occurrence -> occurrence.hop() == call)
				.filter(occurrence -> analysis.isCompiledHopOccurrence(occurrence.key()))
				.filter(occurrence -> occurrence.scopeId() == logicalConsumer.scopeId()).toList();
			if(callOccurrences.size() != 1)
				throw new PlacementEmissionException(authority
					+ " multi-return output has no unique compiled FunctionOp owner");
			for(int inputPosition = 0; inputPosition < call.getInput().size(); inputPosition++) {
				if(call.getInput(inputPosition) != source.hop())
					continue;
				try {
					analysis.requireExactCompiledInputEdge(sourceKey,
						callOccurrences.get(0).key(), inputPosition);
				}
				catch(IllegalArgumentException noCallEdge) {
					throw new PlacementEmissionException(authority
						+ " multi-return FunctionOp owner has no exact compiled source edge: "
						+ noCallEdge.getMessage());
				}
				exact.add(new PhysicalConsumerInput(call.getHopID(), inputPosition));
			}
		}
		List<PhysicalConsumerInput> distinct = exact.stream().distinct().sorted().toList();
		if(distinct.size() != 1)
			throw new PlacementEmissionException(authority
				+ " multi-return output does not project to one exact physical FunctionOp input: matches="
				+ distinct.size());
		return distinct.get(0);
	}

	private static void addRelocationRegistryWrite(Map<RegistrySlot, RegistryWrite> writesBySlot,
		RegistryWrite incoming) {
		RegistryWrite existing = writesBySlot.putIfAbsent(incoming.slot(), incoming);
		if(existing == null)
			return;
		if(existing.equals(incoming))
			return;
		if(incoming.slot().kind() != RegistryKind.REFED)
			throw new PlacementEmissionException("Multiple relocations target one registry slot: prior="
				+ existing + ", incoming=" + incoming);
		FederatedRefedRegistry.AnchorSpec merged;
		try {
			merged = FederatedRefedRegistry.mergeConsumerSpecificAuthority(
				existing.refedAuthority(), incoming.refedAuthority(),
				incoming.slot().scopeId(), incoming.slot().hopId());
		}
		catch(IllegalArgumentException ex) {
			throw new PlacementEmissionException("Multiple REFED relocations target one registry slot with "
				+ "conflicting authority: " + ex.getMessage());
		}
		writesBySlot.put(incoming.slot(), RegistryWrite.refed(incoming.slot().scopeId(),
			incoming.slot().hopId(), merged));
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
			write.hop().setPlannerPlacementSelected(true);
			// Dynamic function/loop recompilation can rebuild Hops with new IDs. Publish the exact
			// emitted state by stable source signature so recompilation preserves planner authority.
			FederatedPlannerUtils.registerPlannerRecompileState(write.hop(),
				write.state().execType(), write.state().output(), write.modeledRewriteKinds());
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
	private record PhysicalConsumerInput(long consumerHopId, int inputPosition)
		implements Comparable<PhysicalConsumerInput> {
		private PhysicalConsumerInput {
			if(consumerHopId < 0 || inputPosition < 0)
				throw new PlacementEmissionException("Physical consumer input identity is invalid");
		}

		@Override
		public int compareTo(PhysicalConsumerInput that) {
			int hopOrder = Long.compare(consumerHopId, that.consumerHopId);
			return hopOrder != 0 ? hopOrder : Integer.compare(inputPosition, that.inputPosition);
		}
	}
	private record SelectedRelocation(RelocationAction action, List<ObligationKey> obligations) { }
	private record SelectedFoutMaterialization(DerivedFoutMaterializationActionKey action,
		HopOccurrenceProjection producer, HopOccurrenceProjection anchor) { }

	private record RegistryWrite(RegistrySlot slot, long anchorHopId, List<Long> consumerHopIds,
		String fType, String label, String anchorKey, String reason, String plannerActionKey,
		FederatedRefedRegistry.AnchorSpec refedAuthority,
		List<FederatedLocalMaterializeRegistry.ConsumerInputSpec> localConsumerInputs,
		List<ConsumerInputSpec> foutConsumerInputs) {
		private static RegistryWrite refed(long scopeId, long hopId, long anchorHopId, String anchorKey,
			String materializationFType, List<ConsumerInputSpec> consumers, String plannerActionKey,
			boolean requiresLocalMaterialization) {
			FederatedRefedRegistry.AnchorSpec authority = FederatedRefedRegistry.AnchorSpec.forConsumerInputs(
				anchorHopId, anchorKey,
				materializationFType == null ? null : FType.valueOf(materializationFType), consumers,
				plannerActionKey, requiresLocalMaterialization);
			return new RegistryWrite(new RegistrySlot(RegistryKind.REFED, scopeId, hopId), anchorHopId,
				authority.getConsumerHopIds(), materializationFType, null, anchorKey, null, null,
				authority, null, null);
		}

		private static RegistryWrite refed(long scopeId, long hopId,
			FederatedRefedRegistry.AnchorSpec authority) {
				return new RegistryWrite(new RegistrySlot(RegistryKind.REFED, scopeId, hopId),
				authority.getAnchorHopId(), authority.getConsumerHopIds(),
				authority.getMaterializationFType() == null ? null : authority.getMaterializationFType().name(),
				null, authority.getAnchorKey(), null, null, authority, null, null);
		}

		private static RegistryWrite fout(long scopeId, long hopId, long anchorHopId, String fType,
			String label, String anchorKey, List<ConsumerInputSpec> consumers, String plannerActionKey) {
			List<ConsumerInputSpec> exactConsumers = List.copyOf(
				Objects.requireNonNull(consumers, "fout consumer inputs"));
			return new RegistryWrite(new RegistrySlot(RegistryKind.FOUT, scopeId, hopId), anchorHopId,
				exactConsumers.stream().map(ConsumerInputSpec::consumerHopId).distinct().sorted().toList(),
				fType, label, anchorKey, null, plannerActionKey, null, null, exactConsumers);
		}

		private static RegistryWrite local(long scopeId, long hopId,
			List<FederatedLocalMaterializeRegistry.ConsumerInputSpec> consumers,
			String fType, String reason, String plannerActionKey) {
			return new RegistryWrite(new RegistrySlot(RegistryKind.LOCAL, scopeId, hopId), 0,
				consumers.stream().map(FederatedLocalMaterializeRegistry.ConsumerInputSpec::consumerHopId)
					.distinct().sorted().toList(),
				fType, null, null, reason, plannerActionKey, null, List.copyOf(consumers), null);
		}

		private void apply() {
			switch(slot.kind()) {
				case REFED -> {
					for(FederatedRefedRegistry.AuthoritySpec authority : refedAuthority.getAuthorities())
							FederatedRefedRegistry.registerConsumerInputs(slot.scopeId(), slot.hopId(),
								authority.getAnchorHopId(), authority.getAnchorKey(),
								authority.getMaterializationFType(), authority.getConsumerInputs(),
								authority.getPlannerActionKey(),
								authority.getRequiresLocalMaterialization());
				}
				case FOUT -> FederatedFoutMaterializeRegistry.registerConsumerInputs(
					slot.scopeId(), slot.hopId(), anchorHopId, fType, label, anchorKey,
					foutConsumerInputs, plannerActionKey);
				case LOCAL -> FederatedLocalMaterializeRegistry.registerConsumerInputs(
					slot.scopeId(), slot.hopId(), localConsumerInputs, fType, reason,
					plannerActionKey);
			}
		}
	}

	private record RegistrySnapshots(FederatedRefedRegistry.Snapshot refed,
		FederatedFoutMaterializeRegistry.Snapshot fout,
		FederatedLocalMaterializeRegistry.Snapshot local,
		Map<String, FederatedPlannerUtils.PlannerRecompileStateSnapshot> plannerRecompile,
		Set<String> ambiguousPlannerRecompile,
		Map<Long,String> plannerHopSignatures,
		PlannerRuntimeActionRegistry.Snapshot runtimeActions) {
		private static RegistrySnapshots capture() {
			return new RegistrySnapshots(FederatedRefedRegistry.snapshotAll(),
				FederatedFoutMaterializeRegistry.snapshotAll(), FederatedLocalMaterializeRegistry.snapshotAll(),
				FederatedPlannerUtils.snapshotPlannerRecompileStates(),
				FederatedPlannerUtils.snapshotAmbiguousPlannerRecompileSignatures(),
				FederatedPlannerUtils.snapshotPlannerRecompileHopSignatures(),
				PlannerRuntimeActionRegistry.snapshot());
		}

		private void restore() {
			FederatedPlannerUtils.restorePlannerRecompileStates(plannerRecompile, ambiguousPlannerRecompile);
			FederatedPlannerUtils.restorePlannerRecompileHopSignatures(plannerHopSignatures);
			PlannerRuntimeActionRegistry.restore(runtimeActions);
			FederatedLocalMaterializeRegistry.restoreAll(local);
			FederatedFoutMaterializeRegistry.restoreAll(fout);
			FederatedRefedRegistry.restoreAll(refed);
		}
	}

	private record HopWrite(Hop hop, PlacementState state, boolean derivedFedFout,
		Set<String> modeledRewriteKinds) {
		private HopWrite {
			modeledRewriteKinds = Set.copyOf(Objects.requireNonNull(modeledRewriteKinds));
		}
	}

	private record HopSnapshot(ExecType execType, ExecType forcedExecType, FederatedOutput output,
		boolean outputDerived, boolean plannerPlacementSelected) {
		private static HopSnapshot capture(Hop hop) {
			return new HopSnapshot(hop.getExecType(), hop.getForcedExecType(), hop.getFederatedOutput(),
				hop.isFederatedOutputDerived(), hop.isPlannerPlacementSelected());
		}

		private void restore(Hop hop) {
			hop.setExecType(execType);
			if(forcedExecType == null)
				hop.clearForcedExecType();
			else
				hop.setForcedExecType(forcedExecType);
			hop.setFederatedOutput(output);
			hop.setFederatedOutputDerived(outputDerived);
			hop.setPlannerPlacementSelected(plannerPlacementSelected);
		}
	}

	private record PreparedEmission(String planHash, List<HopWrite> hopWrites,
		List<RegistryWrite> registryWrites,
		PlannerRuntimeActionRegistry.Snapshot runtimeActionSnapshot) { }
	private record CommittedPlan(PlacementEmissionReceipt receipt, NormalizedPlannerResult result,
		Map<Hop, HopSnapshot> baselineHops,
		RegistrySnapshots baselineRegistries) { }

	private static final class PlacementEmissionException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		private PlacementEmissionException(String message) {
			super(message);
		}

		private PlacementEmissionException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
