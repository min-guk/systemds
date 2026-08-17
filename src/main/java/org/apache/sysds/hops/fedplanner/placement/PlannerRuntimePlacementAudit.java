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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.lops.Lop;
import org.apache.sysds.lops.PartialAggregate;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.runtime.controlprogram.Program;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.PlannerRuntimeAuthority;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRequest.RequestType;
import org.apache.sysds.runtime.instructions.Instruction;
import org.apache.sysds.runtime.instructions.Instruction.IType;
import org.apache.sysds.runtime.instructions.Instruction.PlannerWorkerFragment;
import org.apache.sysds.runtime.instructions.InstructionUtils;
import org.apache.sysds.runtime.instructions.cp.CPInstruction;
import org.apache.sysds.runtime.instructions.cp.CPInstruction.CPType;
import org.apache.sysds.runtime.instructions.cp.FunctionCallCPInstruction;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction;
import org.apache.sysds.runtime.instructions.cp.VariableCPInstruction.VariableOperationCode;
import org.apache.sysds.runtime.instructions.fed.FEDFoutInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.instructions.fed.FEDRefedInstruction;

/**
 * Fail-closed proof that planner authority survives Hop-to-Lop lowering and runtime preprocessing.
 *
 * <p>The audit is deliberately observational until it finds a contradiction. It never repairs an
 * instruction, changes a placement, or asks the runtime to choose a fallback. A selected physical
 * candidate that lowers or preprocesses into a different exec/output pair fails before execution.</p>
 */
public final class PlannerRuntimePlacementAudit {
	public static final String PROPERTY = "sysds.fedplanner.runtime.audit";

	/** Immutable exact occurrence projection retained independently of mutable Hop fields. */
	public record PlannedHop(long hopId, String recompileSignature, String plannerId, String keyHash,
		String opcode, NodeKind nodeKind, String sourceLocation, String valueName, String controlTarget,
		List<String> inputHops, boolean emittedWork, PlacementEmissionState selectedTarget,
		ExecType physicalExec, FederatedOutput physicalOutput, FType physicalFType,
		boolean requiresOwnInstruction, boolean requiresReblock) {
		public PlannedHop {
			if(hopId < 0)
				throw new IllegalArgumentException("Planned Hop id must be non-negative");
			plannerId = requireText(plannerId, "plannerId");
			keyHash = requireText(keyHash, "keyHash");
			opcode = opcode == null ? "" : opcode;
			Objects.requireNonNull(nodeKind, "nodeKind");
			sourceLocation = sourceLocation == null ? "-" : sourceLocation;
			valueName = valueName == null ? "-" : valueName;
			controlTarget = controlTarget == null ? "-" : controlTarget;
			if(nodeKind == NodeKind.FUNCTION_CALL && "-".equals(controlTarget))
				throw new IllegalArgumentException(
					"Planned function-call control target must be explicit");
			inputHops = List.copyOf(Objects.requireNonNull(inputHops, "inputHops"));
			Objects.requireNonNull(selectedTarget, "selectedTarget");
			Objects.requireNonNull(physicalExec, "physicalExec");
			Objects.requireNonNull(physicalOutput, "physicalOutput");
		}

		String physicalSignature() {
			return placement(physicalExec, physicalOutput, physicalFType);
		}

		String targetSignature() {
			return selectedTarget.normalizedSignature();
		}
	}

	/** Exact planner-owned synthetic boundary that must survive registry lowering unchanged. */
	public record PlannedSyntheticAction(String token, String baseActionKey, String stage,
		String opcode, ExecType physicalExec, FederatedOutput physicalOutput, FType physicalFType) {
		public PlannedSyntheticAction {
			token = requireText(token, "token");
			baseActionKey = requireText(baseActionKey, "baseActionKey");
			stage = requireText(stage, "stage");
			opcode = requireText(opcode, "opcode");
			Objects.requireNonNull(physicalExec, "physicalExec");
			Objects.requireNonNull(physicalOutput, "physicalOutput");
			if(!token.equals(syntheticActionKey(baseActionKey, stage)))
				throw new IllegalArgumentException("Synthetic action token does not match its authority");
		}

		String physicalSignature() {
			return placement(physicalExec, physicalOutput, physicalFType);
		}
	}

	/** Registration is built before Hop mutation and committed only after the emission transaction. */
	public static final class PreparedRegistration {
		private final Authority authority;
		private PreparedRegistration(Authority authority) { this.authority = authority; }
		public void commit() {
			if(!isEnabled() || authority == null)
				return;
			commitAuthority(authority);
		}
	}

	/**
	 * Lexical execution scope used only while one coordinator instruction is physically running.
	 * It lets outgoing federated requests inherit the exact already-verified parent proof.  Nested
	 * coordinator execution restores the previous parent and exceptions cannot leak stale authority
	 * to a later instruction.
	 */
	public static final class RuntimeExecutionScope implements AutoCloseable {
		private final LoweredExpectation previous;
		private final boolean changed;
		private boolean closed;

		private RuntimeExecutionScope(LoweredExpectation previous, boolean changed) {
			this.previous = previous;
			this.changed = changed;
		}

		@Override
		public void close() {
			if(closed)
				return;
			closed = true;
			if(!changed)
				return;
			if(previous == null)
				ACTIVE_FEDERATED_PARENT.remove();
			else
				ACTIVE_FEDERATED_PARENT.set(previous);
		}
	}

	/**
	 * Exact lowering authority for one planner Hop. A LOCAL materialization belongs to the
	 * selected producer-to-consumer edge, not to an independently executable consumer Hop.
	 * The consumer may therefore participate in normal same-placement Lop fusion, but only
	 * after every synthetic prefetch token for that exact edge has itself been proved.
	 */
	private record PlanEntry(PlannedHop plan, Hop hop, List<String> fusedInputBoundaryTokens) {
		private PlanEntry {
			fusedInputBoundaryTokens = List.copyOf(
				Objects.requireNonNull(fusedInputBoundaryTokens, "fusedInputBoundaryTokens"));
		}
	}
	private record LoweredExpectation(String key, String planHash, List<PlannedHop> plans,
		PlannedSyntheticAction synthetic,
		ExecType exec, FederatedOutput output, FType fType, String opcode, long hopId, long lopId,
		String recompileSignature, boolean valuePlacementControl) { }
	private record ExecutionKey(String auditKey, String actual, String opcode, long hopId,
		long lopId, String recompileSignature, String status) { }
	private record FederatedDispatchKey(String planHash, String auditKey, String requestType, String fragmentOpcode,
		String parentPhysical, long parentHopId, long parentLopId) { }
	private record WorkerFragmentExecutionKey(String planHash, String parentAuditKey,
		String requestType, String fragmentOpcode, String actual, long parentHopId,
		long parentLopId) { }
	private record FusedResolution(String runtimeAuditKey, boolean samePhysical) { }
	private record RuntimePlacement(FederatedOutput output, FType fType, String outputVariable) { }

	private static final class Authority {
		private final String planHash;
		private final List<PlannedHop> plans;
		private final Map<Long,List<PlanEntry>> byHopId;
		private final Map<String,List<PlanEntry>> bySignature;
		private final Map<String,PlanEntry> byPlanKey;
		private final Map<String,PlanEntry> byKeyHash;
		private final Map<String,PlannedSyntheticAction> syntheticByToken;

		private Authority(String planHash, List<PlanEntry> entries,
			List<PlannedSyntheticAction> syntheticActions) {
			this.planHash = requireText(planHash, "planHash");
			this.plans = entries.stream().map(PlanEntry::plan).toList();
			Map<Long,List<PlanEntry>> ids = new LinkedHashMap<>();
			Map<String,List<PlanEntry>> signatures = new LinkedHashMap<>();
			Map<String,PlanEntry> keys = new LinkedHashMap<>();
			Map<String,PlanEntry> stableKeys = new LinkedHashMap<>();
			for(PlanEntry entry : entries) {
				ids.computeIfAbsent(entry.plan().hopId(), ignored -> new ArrayList<>()).add(entry);
				if(entry.plan().recompileSignature() != null && !entry.plan().recompileSignature().isBlank())
					signatures.computeIfAbsent(entry.plan().recompileSignature(), ignored -> new ArrayList<>()).add(entry);
				String key = planKey(planHash, entry.plan());
				if(keys.putIfAbsent(key, entry) != null)
					throw new IllegalArgumentException("Duplicate planner runtime audit occurrence: " + key);
				if(stableKeys.putIfAbsent(entry.plan().keyHash(), entry) != null)
					throw new IllegalArgumentException(
						"Duplicate planner runtime audit stable occurrence: " + entry.plan().keyHash());
			}
			byHopId = immutableLists(ids);
			bySignature = immutableLists(signatures);
			byPlanKey = Collections.unmodifiableMap(keys);
			byKeyHash = Collections.unmodifiableMap(stableKeys);
			Map<String,PlannedSyntheticAction> synthetics = new LinkedHashMap<>();
			for(PlannedSyntheticAction action : syntheticActions)
				if(synthetics.putIfAbsent(action.token(), action) != null)
					throw new IllegalArgumentException(
						"Duplicate planner synthetic action authority: " + action.token());
			syntheticByToken = Collections.unmodifiableMap(synthetics);
		}
	}

	private static volatile Authority CURRENT;
	private static final Map<String,LoweredExpectation> LOWERED = new ConcurrentHashMap<>();
	private static final Map<ExecutionKey,LongAdder> EXECUTED = new ConcurrentHashMap<>();
	private static final Map<FederatedDispatchKey,LongAdder> FEDERATED_DISPATCHED = new ConcurrentHashMap<>();
	private static final Map<WorkerFragmentExecutionKey,LongAdder> WORKER_FRAGMENTS = new ConcurrentHashMap<>();
	private static final Map<String,String> LOWERING_OBSERVATIONS = new ConcurrentHashMap<>();
	private static final Set<String> LOWERED_PLAN_KEYS = ConcurrentHashMap.newKeySet();
	private static final Set<String> LOWERED_SYNTHETIC_KEYS = ConcurrentHashMap.newKeySet();
	private static final Set<String> AUTHORITY_PLAN_HASHES = ConcurrentHashMap.newKeySet();
	private static final ThreadLocal<LoweredExpectation> ACTIVE_FEDERATED_PARENT = new ThreadLocal<>();

	private PlannerRuntimePlacementAudit() { }

	public static boolean isEnabled() {
		return Boolean.parseBoolean(System.getProperty(PROPERTY, Boolean.FALSE.toString()));
	}

	/** Stable hand-off token attached to the exact planner-selected synthetic Lop and Instruction. */
	public static String syntheticActionKey(String actionKey, String stage) {
		return requireText(actionKey, "actionKey") + "|stage=" + requireText(stage, "stage");
	}

	public static PreparedRegistration prepareRegistration(NormalizedPlannerResult result) {
		Objects.requireNonNull(result, "result");
		if(!isEnabled())
			return new PreparedRegistration(null);
		return prepareRegistration(result, null);
	}

	/**
	 * Build the runtime proof from the exact prevalidated registry writes that will be committed.
	 * Relocation selection receipts and emitted registry actions must agree, but the latter are the
	 * immutable lowering authority and avoid independently re-deriving REFED_LOCAL stage semantics.
	 */
	static PreparedRegistration prepareRegistration(NormalizedPlannerResult result,
		PlannerRuntimeActionRegistry.Snapshot committedActions) {
		Objects.requireNonNull(result, "result");
		if(!isEnabled())
			return new PreparedRegistration(null);
		PlacementAnalysis analysis = result.analysis();
		Map<CompiledHopKey,PlacementEmissionState> selected = result.selectedEmissionStates();
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : result.selectedCandidateSelections())
			if(candidates.put(candidate.rule().parentOccurrence(), candidate) != null)
				throw new IllegalArgumentException("Duplicate selected candidate occurrence");
		Set<CompiledHopKey> relocationConsumers = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<PlacementIdentity.ValueVersionKey> relocationSources = new LinkedHashSet<>();
		for(RelocationChoiceReceipt choice : result.selectedRelocationChoices()) {
			relocationConsumers.add(choice.demand().consumer());
			relocationSources.add(choice.demand().sourceValueVersion());
		}
		List<LocalMaterializationActionKey> selectedLocals = new ArrayList<>();
		Set<CompiledHopKey> localMaterializationSources =
			Collections.newSetFromMap(new IdentityHashMap<>());
		Map<CompiledHopKey,List<String>> localInputBoundaryTokens = new IdentityHashMap<>();
		for(Object raw : result.selectedLocalMaterializations()) {
			if(!(raw instanceof LocalMaterializationActionKey action))
				throw new IllegalArgumentException("Planner runtime audit received an untyped local action");
			selectedLocals.add(action);
			localMaterializationSources.add(action.sourceOccurrence());
			String token = syntheticActionKey(action.normalizedSignature(), "LOCAL");
			action.obligations().forEach(obligation -> localInputBoundaryTokens
				.computeIfAbsent(obligation.consumerOccurrence(), ignored -> new ArrayList<>()).add(token));
		}
		Map<CompiledHopKey,HopOccurrenceProjection> occurrences = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences())
			occurrences.put(occurrence.key(), occurrence);

		List<PlanEntry> entries = new ArrayList<>();
		for(Node node : analysis.graph().decisionNodes().stream().sorted().toList()) {
			if(!analysis.isCompiledHopOccurrence(node.key()))
				continue;
			HopOccurrenceProjection occurrence = occurrences.get(node.key());
			if(occurrence == null)
				throw new IllegalArgumentException("Planner runtime audit occurrence is missing");
			PlacementEmissionState target = exact(selected, node.key());
			if(target == null)
				throw new IllegalArgumentException("Planner runtime audit target is missing");
			CandidateSelectionReceipt candidate = candidates.get(node.key());
			ExecType physicalExec = target.placementState().execType();
			FederatedOutput physicalOutput = target.placementState().output();
			FType physicalFType = target.placementState().fType();
			String opcode = occurrence.hop().getOpString();
			boolean materializationBoundary = target.derivedFedFout();
			if(candidate != null) {
				CandidateRuleFact exactFact = analysis.candidateRuleFacts().requireExact(
					candidate.rule().parentOccurrence(), candidate.rule().orderedInputs());
				if(exactFact.capability() == null)
					throw new IllegalArgumentException("Selected candidate has no runtime capability");
				if(exactFact.allowedEmissionFacts().stream().noneMatch(emission ->
					emission == candidate.emission()))
					throw new IllegalArgumentException(
						"Selected candidate emission is not owned by the exact analysis rule");
				// Cost-based planners may project the selected state into a distinct immutable
				// PlacementEmissionState value. Candidate ownership is identity-based above;
				// agreement with that planner projection is structural, as at the normalized
				// plan boundary. Requiring object identity here rejects valid DP projections.
				if(!candidate.emission().emissionState().equals(target))
					throw new IllegalArgumentException(
						"Selected candidate and selected placement describe different emissions");
				// The candidate capability describes the oracle's native result, not necessarily
				// the selected physical output. For example, an operation whose native result is
				// FED/FOUT may legally be lowered with forced FED/LOUT. The selected emission is
				// therefore authoritative unless it explicitly carries a two-step materialization;
				// in that case the operation itself must match the action's exact source placement
				// and the synthetic materializer publishes the selected target placement.
				PlacementState physical = candidate.emission().derivedFoutAction() == null
					? target.placementState()
					: candidate.emission().derivedFoutAction().sourcePlacement();
				physicalExec = physical.execType();
				physicalOutput = physical.output();
				physicalFType = physical.fType();
				if(!exactFact.capability().opcode().isBlank())
					opcode = exactFact.capability().opcode();
				materializationBoundary |= candidate.emission().derivedFoutAction() != null;
			}
			opcode = exactPhysicalOpcode(occurrence.hop(), opcode);
			materializationBoundary |= target.placementState().output() == FederatedOutput.FOUT
				|| target.placementState().execType() != physicalExec
				|| target.placementState().output() != physicalOutput
				|| relocationConsumers.contains(node.key())
				|| relocationSources.contains(node.valueVersion())
				|| localMaterializationSources.contains(node.key());
			boolean physicalOperation = node.kind() == NodeKind.OPERATION || node.kind() == NodeKind.CLONE;
			boolean requiresOwnInstruction = physicalOperation && materializationBoundary;
			List<String> fusedInputBoundaryTokens = localInputBoundaryTokens
				.getOrDefault(node.key(), List.of()).stream().distinct().sorted().toList();
			String signature = FederatedPlannerUtils.plannerRecompileSignature(occurrence.hop());
			PlannedHop plan = new PlannedHop(occurrence.hop().getHopID(), signature,
				result.plannerId(), shortHash(node.key().normalizedSignature()), opcode, node.kind(),
				sourceLocation(occurrence.hop()), occurrence.hop().getName(),
				functionControlTarget(occurrence.hop(), node.kind()),
				occurrence.hop().getInput().stream().map(PlannerRuntimePlacementAudit::describeHop).toList(),
				node.emittedWork(), target, physicalExec, physicalOutput, physicalFType,
				requiresOwnInstruction, occurrence.hop().requiresReblock());
			entries.add(new PlanEntry(plan, occurrence.hop(), fusedInputBoundaryTokens));
		}
		List<PlannedSyntheticAction> syntheticActions = new ArrayList<>();
		for(RelocationActionKey action : result.selectedRelocations().stream().distinct().sorted().toList()) {
			String base = action.normalizedSignature();
			syntheticActions.add(new PlannedSyntheticAction(syntheticActionKey(base, "REFED"), base,
				"REFED", "fed_refed", ExecType.FED, FederatedOutput.FOUT,
				action.materializationFType()));
		}
		if(committedActions == null) {
			for(RelocationActionKey action : result.selectedRelocations().stream().distinct().sorted().toList()) {
				PlacementEmissionState source = selectedSourcePlacement(
					analysis, selected, action.sourceValueVersion());
				if(source.placementState().output() == FederatedOutput.FOUT) {
					String base = action.normalizedSignature();
					syntheticActions.add(new PlannedSyntheticAction(syntheticActionKey(base, "REFED_LOCAL"), base,
						"REFED_LOCAL", "prefetch", ExecType.CP, FederatedOutput.LOUT, null));
				}
			}
		}
		else {
			Map<String,RelocationActionKey> selectedRelocationKeys = result.selectedRelocations().stream()
				.collect(java.util.stream.Collectors.toMap(RelocationActionKey::normalizedSignature,
					action -> action));
			for(Map<Long,FederatedRefedRegistry.AnchorSpec> scope : committedActions.refed().scopes().values())
				for(FederatedRefedRegistry.AnchorSpec spec : scope.values())
					for(FederatedRefedRegistry.AuthoritySpec authority : spec.getAuthorities()) {
						String base = authority.getPlannerActionKey();
						if(base == null || !selectedRelocationKeys.containsKey(base))
							throw new IllegalArgumentException(
								"Committed REFED registry contains no selected relocation authority");
						Boolean localStage = authority.getRequiresLocalMaterialization();
						if(localStage == null)
							throw new IllegalArgumentException(
								"Committed planner REFED authority omits its local materialization stage");
						if(localStage)
							syntheticActions.add(new PlannedSyntheticAction(
								syntheticActionKey(base, "REFED_LOCAL"), base, "REFED_LOCAL",
								"prefetch", ExecType.CP, FederatedOutput.LOUT, null));
					}
		}
		result.selectedCandidateSelections().stream()
			.map(candidate -> candidate.emission().derivedFoutAction())
			.filter(Objects::nonNull).distinct().sorted()
			.forEach(action -> {
				String base = action.normalizedSignature();
				syntheticActions.add(new PlannedSyntheticAction(syntheticActionKey(base, "FOUT"), base,
					"FOUT", "fed_fout", ExecType.FED, FederatedOutput.FOUT,
					action.materializationFType()));
			});
		selectedLocals.stream().distinct().sorted().forEach(action -> {
			String base = action.normalizedSignature();
			syntheticActions.add(new PlannedSyntheticAction(syntheticActionKey(base, "LOCAL"), base,
				"LOCAL", "prefetch", ExecType.CP, FederatedOutput.LOUT, null));
		});
		return new PreparedRegistration(new Authority(result.normalizedPlanFingerprint(), entries,
			syntheticActions));
	}

	private static String exactPhysicalOpcode(Hop hop, String oracleOpcode) {
		// MULTIRETURN_BUILTIN FunctionOps are physical compiler operations, not DML
		// function-call control. FunctionOp#getOpString reports "fcall <ns> <name>",
		// while its Lop emits the builtin name itself (for example EIGEN -> eigen).
		// Resolve that exact Hop type here instead of admitting a general fcall alias.
		if(hop instanceof FunctionOp function
			&& function.getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN)
			return function.getFunctionName();
		// Oracle capabilities name NaryOp by its logical enum (for example MULT),
		// while the exact Nary Lop emits a closed physical opcode (for example n*).
		// Resolve only this concrete Hop type rather than admitting a global alias
		// that could hide an unrelated lowering mismatch.
		if(hop instanceof NaryOp nary)
			switch(nary.getOp()) {
				case PRINTF:
				case CBIND:
				case RBIND:
				case EVAL:
				case LIST:
					return nary.getOp().name().toLowerCase(java.util.Locale.ROOT);
				case MIN:
				case MAX:
					return "n" + nary.getOp().name().toLowerCase(java.util.Locale.ROOT);
				case PLUS:
					return org.apache.sysds.common.Opcodes.NP.toString();
				case MULT:
					return org.apache.sysds.common.Opcodes.NM.toString();
				default:
					throw new IllegalArgumentException(
						"Planner runtime audit has no exact Nary physical opcode for " + nary.getOp());
			}
		return oracleOpcode;
	}

	private static String functionControlTarget(Hop hop, NodeKind nodeKind) {
		if(nodeKind != NodeKind.FUNCTION_CALL)
			return "-";
		if(!(hop instanceof FunctionOp function))
			throw new IllegalArgumentException(
				"Planner function-call node is not backed by a FunctionOp: " + describeHop(hop));
		return requireText(function.getFunctionKey(), "functionControlTarget");
	}

	/** Test-only exact authority installation without a complete DML program fixture. */
	static void installForTesting(List<PlannedHop> plans) {
		installForTesting(plans, List.of());
	}

	/** Test-only exact Hop and synthetic authority installation. */
	static void installForTesting(List<PlannedHop> plans,
		List<PlannedSyntheticAction> syntheticActions) {
		installForTesting("test-plan", plans, syntheticActions);
	}

	/** Test-only authority replacement with an explicit generation identity. */
	static void installForTesting(String planHash, List<PlannedHop> plans,
		List<PlannedSyntheticAction> syntheticActions) {
		List<PlanEntry> entries = plans.stream().map(plan -> new PlanEntry(plan, null, List.of())).toList();
		commitAuthority(new Authority(planHash, entries, syntheticActions));
	}

	/**
	 * Install one complete planner generation and retain only exact, unchanged lowering
	 * proofs from the previous generation.  Runtime recompilation replaces a complete
	 * planner authority while already-compiled instructions outside the rewritten slice
	 * remain live.  Carrying an unchanged occurrence is therefore required; carrying a
	 * changed placement would let stale runtime code escape the planner.
	 */
	private static synchronized void commitAuthority(Authority next) {
		Objects.requireNonNull(next, "next");
		Authority previous = CURRENT;
		if(previous != null && !previous.planHash.equals(next.planHash))
			carryForwardUnchangedLowering(previous, next);
		CURRENT = next;
		AUTHORITY_PLAN_HASHES.add(next.planHash);
	}

	private static void carryForwardUnchangedLowering(Authority previous, Authority next) {
		for(PlanEntry current : next.byPlanKey.values()) {
			PlanEntry prior = previous.byKeyHash.get(current.plan().keyHash());
			if(prior == null || !samePlanAuthority(prior.plan(), current.plan())
				|| !prior.fusedInputBoundaryTokens().equals(current.fusedInputBoundaryTokens()))
				continue;
			String oldKey = planKey(previous.planHash, prior.plan());
			if(!LOWERED_PLAN_KEYS.contains(oldKey))
				continue;
			String newKey = planKey(next.planHash, current.plan());
			observeLowering(next.planHash, newKey, current.plan(), null,
				"AUTHORITY_CARRY_FORWARD_MATCH", "previousPlan=" + previous.planHash);
		}
		for(PlannedSyntheticAction current : next.syntheticByToken.values()) {
			PlannedSyntheticAction prior = previous.syntheticByToken.get(current.token());
			if(!current.equals(prior) || !LOWERED_SYNTHETIC_KEYS.contains(
				syntheticPlanKey(previous.planHash, prior.token())))
				continue;
			LOWERED_SYNTHETIC_KEYS.add(syntheticPlanKey(next.planHash, current.token()));
			String observationKey = "synthetic:" + next.planHash + ':' + current.token()
				+ ":authority-carry-forward";
			LOWERING_OBSERVATIONS.put(observationKey,
				"[PlannerRuntimeAudit][Lowering-Synthetic] status=AUTHORITY_CARRY_FORWARD_MATCH plan="
					+ next.planHash + " action=" + shortHash(current.baseActionKey())
					+ " stage=" + current.stage() + " token=" + shortHash(current.token())
					+ " opcode=" + current.opcode() + " plannedPhysical="
					+ current.physicalSignature() + " actual=CARRIED previousPlan=" + previous.planHash);
		}
		// Provisional observations belong only to the superseded generation.  Definitive
		// matches and execution records remain as historical evidence.
		LOWERING_OBSERVATIONS.entrySet().removeIf(entry ->
			entry.getKey().startsWith(previous.planHash + ':')
				&& (entry.getValue().contains(" status=NOT_EMITTED ")
					|| entry.getValue().contains(" status=DEFERRED ")));
	}

	/**
	 * Verify every physical instruction generated for this Lop DAG and attach immutable runtime proof tokens.
	 */
	public static ArrayList<Instruction> verifyLowering(List<Hop> logicalHopRoots,
		ArrayList<Instruction> instructions) {
		return verifyLowering(logicalHopRoots, null, instructions);
	}

	public static ArrayList<Instruction> verifyLowering(List<Hop> logicalHopRoots,
		List<Lop> loweredLops, ArrayList<Instruction> instructions) {
		Objects.requireNonNull(instructions, "instructions");
		if(!isEnabled() || CURRENT == null)
			return instructions;
		Authority authority = CURRENT;
		boolean limitToCurrentDag = logicalHopRoots != null && !logicalHopRoots.isEmpty()
			&& loweredLops != null;
		Set<Hop> currentHops = limitToCurrentDag
			? collectLoweredHopClosure(logicalHopRoots, loweredLops)
			: collectHopClosure(logicalHopRoots);
		Set<String> covered = new LinkedHashSet<>();
		Map<Hop,String> coveredHopObjects = new IdentityHashMap<>();
		for(Instruction instruction : instructions) {
			if(instruction == null)
				continue;
			String opcode = safeOpcode(instruction);
			if(isLifecycleAuxiliary(instruction)) {
				observeAuxiliary(authority, instruction, "AUXILIARY_LIFECYCLE");
				continue;
			}
			if(instruction.getPlannerSyntheticActionKey() != null
				|| "fed_refed".equals(opcode) || "fed_fout".equals(opcode)) {
				verifySyntheticLowering(authority, instruction);
				continue;
			}
			if(isAuxiliary(instruction)) {
				observeAuxiliary(authority, instruction, "AUXILIARY_LIFECYCLE");
				continue;
			}
			List<PlanEntry> entries = resolve(authority, instruction);
			if(limitToCurrentDag) {
				List<PlanEntry> currentEntries = entries.stream()
					.filter(entry -> entry.hop() != null && currentHops.contains(entry.hop())).toList();
				if(!currentEntries.isEmpty())
					entries = currentEntries;
			}
			if(entries.isEmpty()) {
				if(isAuxiliary(instruction)) {
					observeAuxiliary(authority, instruction, "AUXILIARY");
					continue;
				}
				throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_UNPLANNED plan="
					+ authority.planHash + " instruction=" + describeInstruction(instruction)
					+ " syntheticAction="
					+ Objects.toString(instruction.getPlannerSyntheticActionKey(), "-"));
			}
			if(instruction.getPlannerLoweringAuxiliaryKind() != null) {
				verifyLoweringAuxiliary(authority, entries, instruction);
				continue;
			}
			if(instruction.getPlannerRewriteReplacementKind() != null) {
				String auditKey = verifyRewriteReplacement(authority, entries, instruction);
				for(PlanEntry entry : entries) {
					String key = planKey(authority.planHash, entry.plan());
					covered.add(key);
					if(entry.hop() != null)
						coveredHopObjects.put(entry.hop(), auditKey);
				}
				continue;
			}
			if(isFunctionCallControl(entries, instruction)) {
				List<PlannedHop> plans = entries.stream().map(PlanEntry::plan)
					.sorted(Comparator.comparing(PlannedHop::keyHash)).toList();
				String auditKey = runtimeKey(authority.planHash, plans, instruction);
				LoweredExpectation expected = new LoweredExpectation(auditKey, authority.planHash, plans, null,
					ExecType.CP, FederatedOutput.LOUT, null, opcode, instruction.getHopID(),
					instruction.getLopID(), instruction.getPlannerRecompileSignature(), false);
				LoweredExpectation prior = LOWERED.putIfAbsent(auditKey, expected);
				if(prior != null && !sameExpectation(prior, expected))
					throw new IllegalStateException(
						"[PlannerRuntimeAudit] conflicting function-call proof key=" + auditKey);
				instruction.setPlannerAuditKey(auditKey);
				for(PlanEntry entry : entries) {
					String key = planKey(authority.planHash, entry.plan());
					covered.add(key);
					if(entry.hop() != null)
						coveredHopObjects.put(entry.hop(), auditKey);
					observeLowering(authority.planHash, key, entry.plan(), instruction, "CONTROL_MATCH",
						"DML FunctionOp placement is a logical actual/formal contract; "
							+ "the call instruction itself must remain coordinator CP/LOUT");
				}
				continue;
			}
			if(isVariablePlacementControl(entries, instruction)) {
				assertOnePhysicalPlacement(entries, instruction);
				PlanEntry first = entries.get(0);
				String destination = instruction.getOutputVariableName();
				if(destination == null || entries.stream().anyMatch(entry ->
					!destination.equals(entry.plan().valueName())))
					throw new IllegalStateException(
						"[PlannerRuntimeAudit] LOWERING_VALUE_NAME_MISMATCH planned="
							+ entries.stream().map(PlanEntry::plan)
								.map(PlannerRuntimePlacementAudit::describePlan).toList()
							+ " actualDestination=" + Objects.toString(destination, "-")
							+ " instruction=" + quotedInstruction(instruction));
				ExecType actualExec = actualExec(instruction);
				FederatedOutput actualOutput = actualOutput(instruction);
				if(actualExec != ExecType.CP || actualOutput != FederatedOutput.LOUT)
					throw mismatch("LOWERING_VALUE_CONTROL_MISMATCH", first.plan(), instruction,
						actualExec, actualOutput);
				List<PlannedHop> plans = entries.stream().map(PlanEntry::plan)
					.sorted(Comparator.comparing(PlannedHop::keyHash)).toList();
				String auditKey = runtimeKey(authority.planHash, plans, instruction);
				LoweredExpectation expected = new LoweredExpectation(auditKey, authority.planHash,
					plans, null, ExecType.CP, FederatedOutput.LOUT, null, opcode,
					instruction.getHopID(), instruction.getLopID(),
					instruction.getPlannerRecompileSignature(), true);
				LoweredExpectation prior = LOWERED.putIfAbsent(auditKey, expected);
				if(prior != null && !sameExpectation(prior, expected))
					throw new IllegalStateException(
						"[PlannerRuntimeAudit] conflicting variable-control proof key=" + auditKey);
				instruction.setPlannerAuditKey(auditKey);
				for(PlanEntry entry : entries) {
					String key = planKey(authority.planHash, entry.plan());
					covered.add(key);
					if(entry.hop() != null)
						coveredHopObjects.put(entry.hop(), auditKey);
					observeLowering(authority.planHash, key, entry.plan(), instruction, "VALUE_CONTROL_MATCH",
						"coordinator variable binding; runtime value placement is verified after execution");
				}
				continue;
			}
			assertOnePhysicalPlacement(entries, instruction);
			PlanEntry first = entries.get(0);
			verifyOrdinaryOpcode(entries, instruction);
			ExecType actualExec = actualExec(instruction);
			FederatedOutput actualOutput = actualOutput(instruction);
			if(actualExec != first.plan().physicalExec() || actualOutput != first.plan().physicalOutput())
				throw mismatch("LOWERING_MISMATCH", first.plan(), instruction, actualExec, actualOutput);
			List<PlannedHop> plans = entries.stream().map(PlanEntry::plan)
				.sorted(Comparator.comparing(PlannedHop::keyHash)).toList();
			String auditKey = runtimeKey(authority.planHash, plans, instruction);
			LoweredExpectation expected = new LoweredExpectation(auditKey, authority.planHash, plans, null,
				first.plan().physicalExec(), first.plan().physicalOutput(), first.plan().physicalFType(),
				opcode, instruction.getHopID(), instruction.getLopID(),
				instruction.getPlannerRecompileSignature(), false);
			LoweredExpectation prior = LOWERED.putIfAbsent(auditKey, expected);
			if(prior != null && !sameExpectation(prior, expected))
				throw new IllegalStateException("[PlannerRuntimeAudit] conflicting lowering proof key=" + auditKey);
			instruction.setPlannerAuditKey(auditKey);
			for(PlanEntry entry : entries) {
				String key = planKey(authority.planHash, entry.plan());
				covered.add(key);
				if(entry.hop() != null)
					coveredHopObjects.put(entry.hop(), auditKey);
				observeLowering(authority.planHash, key, entry.plan(), instruction, "MATCH", null);
			}
		}

		for(PlanEntry entry : authority.byPlanKey.values()) {
			if(limitToCurrentDag && (entry.hop() == null || !currentHops.contains(entry.hop())))
				continue;
			String key = planKey(authority.planHash, entry.plan());
			if(covered.contains(key))
				continue;
			// A full-program authority is lowered one statement block at a time. Shared
			// occurrences already proved by an earlier Dag invocation are not missing.
			if(LOWERED_PLAN_KEYS.contains(key))
				continue;
			if(!isPhysicalOperation(entry)) {
				observeLowering(authority.planHash, key, entry.plan(), null, "STRUCTURAL",
					"no independent runtime operation");
				continue;
			}
			FusedResolution fused = coveredAncestor(authority, entry, coveredHopObjects);
			boolean fusedInputBoundariesProved = entry.fusedInputBoundaryTokens().stream().allMatch(token ->
				LOWERED_SYNTHETIC_KEYS.contains(syntheticPlanKey(authority.planHash, token)));
			if(!entry.plan().requiresOwnInstruction() && fused != null && fused.samePhysical()
				&& fusedInputBoundariesProved) {
				observeLowering(authority.planHash, key, entry.plan(), null, "FUSED_MATCH",
					"runtimeAuditKey=" + fused.runtimeAuditKey() + " inputBoundaries="
						+ entry.fusedInputBoundaryTokens().stream()
							.map(PlannerRuntimePlacementAudit::shortHash).toList());
				continue;
			}
			if(fused != null && fused.samePhysical() && !fusedInputBoundariesProved)
				throw new IllegalStateException(
					"[PlannerRuntimeAudit] LOWERING_FUSION_INPUT_BOUNDARY_MISSING planner="
						+ entry.plan().plannerId() + " hop=" + entry.plan().hopId() + " key="
						+ entry.plan().keyHash() + " requiredSynthetic="
						+ entry.fusedInputBoundaryTokens().stream()
							.map(PlannerRuntimePlacementAudit::shortHash).toList()
						+ " loweredSynthetic=" + entry.fusedInputBoundaryTokens().stream()
							.filter(token -> LOWERED_SYNTHETIC_KEYS.contains(
								syntheticPlanKey(authority.planHash, token)))
							.map(PlannerRuntimePlacementAudit::shortHash).toList());
			if(fused != null)
				throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_FUSION_MISMATCH planner="
					+ entry.plan().plannerId() + " hop=" + entry.plan().hopId() + " key="
					+ entry.plan().keyHash() + " plannedPhysical=" + entry.plan().physicalSignature()
					+ " fusedRuntimeAuditKey=" + fused.runtimeAuditKey()
					+ " samePhysical=" + fused.samePhysical()
					+ " requiresOwnInstruction=" + entry.plan().requiresOwnInstruction());
			if(!entry.plan().requiresOwnInstruction()) {
				// The compiler may retain non-executable or previously materialized Lops in a
				// Dag linearization. This is not an executed runtime Hop. Keep it explicit in
				// the audit instead of pretending it matched or forcing a synthetic operation.
				observeLowering(authority.planHash, key, entry.plan(), null, "NOT_EMITTED",
					"no runtime instruction in this Dag invocation");
				continue;
			}
			if(entry.hop() != null) {
				observeLowering(authority.planHash, key, entry.plan(), null, "DEFERRED",
					"selected boundary is outside this Dag's emitted instruction slice");
				continue;
			}
			throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_MISSING planner="
				+ entry.plan().plannerId() + " hop=" + entry.plan().hopId() + " key="
				+ entry.plan().keyHash() + " opcode=" + entry.plan().opcode()
				+ " nodeKind=" + entry.plan().nodeKind()
				+ " hopClass=" + (entry.hop() == null ? "-" : entry.hop().getClass().getSimpleName())
				+ " lop=" + (entry.hop() == null || entry.hop().getLops() == null ? "-"
					: entry.hop().getLops().getClass().getSimpleName() + '#' + entry.hop().getLops().getID())
				+ " parents=" + (entry.hop() == null ? List.of() : entry.hop().getParent().stream()
					.map(parent -> parent.getHopID() + ":" + parent.getOpString()).toList())
				+ " plannedTarget=" + entry.plan().targetSignature()
				+ " plannedPhysical=" + entry.plan().physicalSignature()
				+ " requiresOwnInstruction=" + entry.plan().requiresOwnInstruction()
				+ " actualInstructions=" + instructions.stream().map(PlannerRuntimePlacementAudit::describeInstruction)
					.toList());
		}
		return instructions;
	}

	/**
	 * Prove an explicitly tagged compiler helper that belongs to a planner Hop but is not an
	 * independent placement decision. The contract is deliberately closed: only explicitly tagged
	 * physical data-flow wrappers, the scalar nrow/ncol offset emitted by CP append lowering, the
	 * scalar cast inserted by the dynamic matrix-scalar/dot-product rewrites, and the temporary
	 * result binding used by constant folding are currently admitted. New helper kinds
	 * must define their exact owner opcode and physical placement here instead of being treated
	 * as generic runtime auxiliaries.
	 */
	private static void verifyLoweringAuxiliary(Authority authority, List<PlanEntry> entries,
		Instruction instruction) {
		assertOnePhysicalPlacement(entries, instruction);
		String kind = instruction.getPlannerLoweringAuxiliaryKind();
		String opcode = safeOpcode(instruction);
		String requiredOpcode = switch(kind) {
			case "PHYSICAL_REBLOCK" -> "rblk";
			case "PHYSICAL_CSV_REBLOCK" -> "csvrblk";
			case "APPEND_OFFSET_NCOL" -> "ncol";
			case "APPEND_OFFSET_NROW" -> "nrow";
			case "DYNAMIC_BINARY_SCALAR_CAST" -> "castdts";
			case "DYNAMIC_SCALAR_MM_CAST", "DYNAMIC_DOT_PRODUCT_SCALAR_CAST" -> "castdts";
			case "DYNAMIC_DOT_PRODUCT_TRANSPOSE" -> "r'";
			case "CONSTANT_FOLD_RESULT_BIND" -> "mvvar";
			default -> throw new IllegalStateException(
				"[PlannerRuntimeAudit] LOWERING_AUXILIARY_UNKNOWN kind=" + kind
					+ " instruction=" + describeInstruction(instruction));
		};
		boolean validOwner = switch(kind) {
			case "PHYSICAL_REBLOCK", "PHYSICAL_CSV_REBLOCK" -> entries.stream().allMatch(entry ->
				entry.plan().nodeKind() == NodeKind.OPERATION && entry.plan().requiresReblock());
			case "APPEND_OFFSET_NCOL", "APPEND_OFFSET_NROW" -> entries.stream().allMatch(entry ->
				"cbind".equals(entry.plan().opcode()) || "rbind".equals(entry.plan().opcode())
					|| "append".equals(entry.plan().opcode()));
			case "DYNAMIC_BINARY_SCALAR_CAST" -> entries.stream().allMatch(entry ->
				entry.plan().nodeKind() == NodeKind.OPERATION
					&& isMatrixScalarBinaryOpcode(entry.plan().opcode()));
			case "DYNAMIC_SCALAR_MM_CAST" -> entries.stream().allMatch(entry ->
				entry.plan().nodeKind() == NodeKind.OPERATION
					&& isAggregateBinaryOpcode(entry.plan().opcode()));
			case "DYNAMIC_DOT_PRODUCT_SCALAR_CAST", "DYNAMIC_DOT_PRODUCT_TRANSPOSE" ->
				entries.stream().allMatch(entry -> entry.plan().nodeKind() == NodeKind.OPERATION
					&& isFullSumAggregateOpcode(entry.plan().opcode()));
			case "CONSTANT_FOLD_RESULT_BIND" -> entries.stream().allMatch(entry ->
				entry.plan().nodeKind() == NodeKind.OPERATION
					&& isConstantFoldablePlannerOpcode(entry.plan().opcode()));
			default -> false;
		};
		boolean cpLocalOwner = entries.stream().allMatch(entry ->
			entry.plan().physicalExec() == ExecType.CP
				&& entry.plan().physicalOutput() == FederatedOutput.LOUT);
		boolean fedFoutOwner = entries.stream().allMatch(entry ->
			entry.plan().physicalExec() == ExecType.FED
				&& entry.plan().physicalOutput() == FederatedOutput.FOUT);
		boolean physicalReblock = "PHYSICAL_REBLOCK".equals(kind);
		boolean physicalCsvReblock = "PHYSICAL_CSV_REBLOCK".equals(kind);
		ExecType helperExec = physicalReblock && fedFoutOwner ? ExecType.FED
			: physicalReblock || physicalCsvReblock ? ExecType.SPARK : ExecType.CP;
		FederatedOutput helperOutput = physicalReblock && fedFoutOwner
			? FederatedOutput.FOUT : FederatedOutput.LOUT;
		FType helperFType = helperOutput == FederatedOutput.FOUT
			? entries.get(0).plan().physicalFType() : null;
		// Append offsets are scalar metadata queries executed at the coordinator even
		// when the append owner itself is FED/FOUT. They neither consume nor publish
		// the owner's matrix value, so requiring a CP/LOUT owner would incorrectly
		// reject the compiler's native FED append lowering. Other rewrite helpers do
		// consume/replace their owner's value and retain the stricter CP-local proof.
		boolean ownerPlacementCompatible = kind.startsWith("APPEND_OFFSET_") || cpLocalOwner;
		if(physicalReblock)
			ownerPlacementCompatible = cpLocalOwner || fedFoutOwner;
		else if(physicalCsvReblock)
			ownerPlacementCompatible = cpLocalOwner;
		if(!requiredOpcode.equals(opcode) || !validOwner || !ownerPlacementCompatible
			|| actualExec(instruction) != helperExec
			|| actualOutput(instruction) != helperOutput)
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] LOWERING_AUXILIARY_MISMATCH kind=" + kind
					+ " owner=" + entries.stream().map(PlanEntry::plan)
						.map(PlannerRuntimePlacementAudit::describePlan).toList()
					+ " requiredOpcode=" + requiredOpcode + " actualOpcode=" + opcode
					+ " expected=" + placement(helperExec, helperOutput, helperFType)
					+ " actual=" + placement(actualExec(instruction), actualOutput(instruction), null)
					+ " instruction=" + quotedInstruction(instruction));

		List<PlannedHop> plans = entries.stream().map(PlanEntry::plan)
			.sorted(Comparator.comparing(PlannedHop::keyHash)).toList();
		String auditKey = runtimeKey(authority.planHash, plans, instruction);
		LoweredExpectation expected = new LoweredExpectation(auditKey, authority.planHash,
			plans, null, helperExec, helperOutput, helperFType, opcode,
			instruction.getHopID(), instruction.getLopID(),
			instruction.getPlannerRecompileSignature(), false);
		LoweredExpectation prior = LOWERED.putIfAbsent(auditKey, expected);
		if(prior != null && !sameExpectation(prior, expected))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] conflicting lowering auxiliary proof key=" + auditKey);
		instruction.setPlannerAuditKey(auditKey);
		for(PlanEntry entry : entries)
			observeLowering(authority.planHash,
				planKey(authority.planHash, entry.plan()) + ":auxiliary:"
				+ kind + ':' + instruction.getLopID(), entry.plan(), instruction,
				"LOWERING_HELPER_MATCH", "kind=" + kind + " helperOpcode=" + opcode);
	}

	private static boolean isMatrixScalarBinaryOpcode(String opcode) {
		return switch(opcode) {
			case "+", "-", "*", "/", "%%", "%/%", "<", "<=", ">", ">=", "==", "!=",
				"min", "max", "log", "^", "&&", "||", "xor", "bitwAnd", "bitwOr",
				"bitwXor", "bitwShiftL", "bitwShiftR" -> true;
			default -> false;
		};
	}

	/** Closed opcode family admitted by RewriteConstantFolding.evalScalarOperation. */
	private static boolean isConstantFoldablePlannerOpcode(String opcode) {
		if(isMatrixScalarBinaryOpcode(opcode))
			return true;
		return switch(opcode) {
			case "+*", "-*", "ifelse", "min", "max", "+",
				"abs", "acos", "asin", "atan", "ceil", "cos", "exp", "floor",
				"log", "round", "sign", "sin", "sqrt", "tan", "castdts",
				"castdti", "castdtb", "not" -> true;
			default -> false;
		};
	}

	/**
	 * Check semantic Hop-to-instruction opcode lowering independently of placement. This list is
	 * intentionally closed: equivalent physical opcode names are admitted explicitly, while
	 * dynamic rewrite substitutions must use the separate replacement contract above.
	 */
	private static void verifyOrdinaryOpcode(List<PlanEntry> entries, Instruction instruction) {
		String actual = safeOpcode(instruction);
		if(entries.stream().allMatch(entry -> isOrdinaryOpcodeCompatible(entry.plan().opcode(), actual)))
			return;
		throw new IllegalStateException(
			"[PlannerRuntimeAudit] LOWERING_OPCODE_MISMATCH planned="
				+ entries.stream().map(PlanEntry::plan)
					.map(PlannerRuntimePlacementAudit::describePlan).toList()
				+ " plannedOpcodes=" + entries.stream().map(PlanEntry::plan)
					.map(PlannedHop::opcode).toList()
				+ " actualOpcode=" + actual + " instruction=" + quotedInstruction(instruction));
	}

	private static boolean isOrdinaryOpcodeCompatible(String planned, String actual) {
		if(planned.equalsIgnoreCase(actual))
			return true;
		if(isAggregateUnaryOpcodeCompatible(planned, actual))
			return true;
		if(planned.startsWith("Fed ") && "fedinit".equals(actual))
			return true;
		if(planned.startsWith("dg(") && planned.endsWith(")")
			&& planned.substring(3, planned.length() - 1).equalsIgnoreCase(actual))
			return true;
		return switch(planned) {
			case "cbind", "rbind" -> "append".equals(actual);
			case "sort" -> "rsort".equals(actual);
			// Ctable's sequence-input specialization is selected by Ctable Lop lowering;
			// it remains the same planner-owned CTABLE operation and placement.
			case "ctable" -> "ctableexpand".equals(actual);
			case "mapLeftIndex" -> "leftIndex".equals(actual);
			case "ua(+rc)" -> "uak+".equals(actual);
			case "ua(minindexr)" -> "uarimin".equals(actual);
			case "ba+*" -> "tsmm".equals(actual);
			case "^" -> "^2".equals(actual);
			case "PRead" -> "read".equals(actual);
			case "PWrite" -> "write".equals(actual);
			default -> false;
		};
	}

	/** Exact Hop {@code ua(op,direction)} to PartialAggregate physical-opcode contract. */
	private static boolean isAggregateUnaryOpcodeCompatible(String planned, String actual) {
		for(AggOp operation : AggOp.values())
			for(Direction direction : Direction.values()) {
				String logical = "ua(" + operation + direction + ")";
				if(!logical.equalsIgnoreCase(planned))
					continue;
				try {
					return PartialAggregate.getOpcode(operation, direction).equalsIgnoreCase(actual);
				}
				catch(UnsupportedOperationException unsupported) {
					return false;
				}
			}
		return false;
	}

	private static void verifySyntheticLowering(Authority authority, Instruction instruction) {
		String token = instruction.getPlannerSyntheticActionKey();
		if(token == null)
			throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_SYNTHETIC_UNPROVEN plan="
				+ authority.planHash + " instruction=" + describeInstruction(instruction));
		PlannedSyntheticAction action = authority.syntheticByToken.get(token);
		if(action == null)
			throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_SYNTHETIC_UNSELECTED plan="
				+ authority.planHash + " action=" + shortHash(token)
				+ " token=" + token
				+ " selected=" + authority.syntheticByToken.keySet().stream()
					.map(PlannerRuntimePlacementAudit::shortHash).sorted().toList()
				+ " instruction=" + describeInstruction(instruction));
		ExecType actualExec = actualExec(instruction);
		FederatedOutput actualOutput = actualOutput(instruction);
		FType actualFType = actualSyntheticFType(instruction);
		if(!action.opcode().equals(safeOpcode(instruction))
			|| action.physicalExec() != actualExec || action.physicalOutput() != actualOutput
			|| action.physicalFType() != actualFType)
			throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_SYNTHETIC_MISMATCH plan="
				+ authority.planHash + " action=" + shortHash(action.baseActionKey())
				+ " stage=" + action.stage() + " token=" + shortHash(action.token())
				+ " plannedOpcode=" + action.opcode() + " actualOpcode=" + safeOpcode(instruction)
				+ " plannedPhysical=" + action.physicalSignature() + " actual="
				+ placement(actualExec, actualOutput, actualFType));
		String auditKey = shortHash(authority.planHash + "|synthetic|" + token + '|'
			+ runtimeIdentity(instruction));
		LoweredExpectation expected = new LoweredExpectation(auditKey, authority.planHash, List.of(), action,
			action.physicalExec(), action.physicalOutput(), action.physicalFType(), action.opcode(),
			instruction.getHopID(), instruction.getLopID(), instruction.getPlannerRecompileSignature(), false);
		LoweredExpectation prior = LOWERED.putIfAbsent(auditKey, expected);
		if(prior != null && !sameExpectation(prior, expected))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] conflicting synthetic lowering proof key=" + auditKey);
		instruction.setPlannerAuditKey(auditKey);
		LOWERED_SYNTHETIC_KEYS.add(syntheticPlanKey(authority.planHash, token));
		String observationKey = "synthetic:" + authority.planHash + ':' + token + ':'
			+ runtimeIdentity(instruction);
		String line = "[PlannerRuntimeAudit][Lowering-Synthetic] status=MATCH plan="
			+ authority.planHash + " action=" + shortHash(action.baseActionKey())
			+ " stage=" + action.stage() + " token=" + shortHash(action.token())
			+ " opcode=" + action.opcode() + " plannedPhysical=" + action.physicalSignature()
			+ " actual=" + placement(actualExec, actualOutput, actualFType)
			+ " auditKey=" + auditKey;
		LOWERING_OBSERVATIONS.putIfAbsent(observationKey, line);
	}

	/**
	 * Verify a dynamic-rewrite operation that is the primary physical replacement of one exact
	 * planner Hop.  Opcode substitution is deliberately closed; merely copying a Hop id or source
	 * location cannot authorize an arbitrary runtime instruction.
	 */
	private static String verifyRewriteReplacement(Authority authority, List<PlanEntry> entries,
		Instruction instruction) {
		assertOnePhysicalPlacement(entries, instruction);
		String kind = instruction.getPlannerRewriteReplacementKind();
		String actualOpcode = safeOpcode(instruction);
		boolean validOpcode = entries.stream().allMatch(entry -> switch(kind) {
			case "PERSISTENT_READ_REBLOCK" -> "PRead".equals(entry.plan().opcode())
				&& entry.plan().requiresReblock() && "rblk".equals(actualOpcode);
			case "PERSISTENT_READ_CSV_REBLOCK" -> "PRead".equals(entry.plan().opcode())
				&& entry.plan().requiresReblock() && "csvrblk".equals(actualOpcode);
			case "DYNAMIC_SCALAR_MATRIX_MULT" ->
				isAggregateBinaryOpcode(entry.plan().opcode()) && "*".equals(actualOpcode);
			case "DYNAMIC_AXPY_PLUS_MULT" ->
				"+".equals(entry.plan().opcode()) && "+*".equals(actualOpcode);
			case "DYNAMIC_AXPY_MINUS_MULT" ->
				"-".equals(entry.plan().opcode()) && "-*".equals(actualOpcode);
			case "DYNAMIC_DOT_PRODUCT" -> isFullSumAggregateOpcode(entry.plan().opcode())
				&& ("ba+*".equals(actualOpcode) || "tsmm".equals(actualOpcode));
			case "DYNAMIC_WEIGHTED_DIV_MM" ->
				(isAggregateBinaryOpcode(entry.plan().opcode())
					|| "*".equals(entry.plan().opcode()))
					&& "wdivmm".equals(actualOpcode);
			case "DYNAMIC_WEIGHTED_DIV_MM_TRANSPOSE_PAIR" ->
				"r'".equals(entry.plan().opcode()) && "wdivmm".equals(actualOpcode);
			case "PHYSICAL_TERNARY_AGGREGATE_FUSION" ->
				isExactTernaryAggregateFusion(entry.plan().opcode(), actualOpcode);
			case "DYNAMIC_TABLE_SEQ_REXPAND" ->
				"ctable".equalsIgnoreCase(entry.plan().opcode()) && "rexpand".equals(actualOpcode);
			default -> false;
		});
		if(!validOpcode)
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] LOWERING_REWRITE_OPCODE_MISMATCH kind=" + kind
					+ " owner=" + entries.stream().map(PlanEntry::plan)
						.map(PlannerRuntimePlacementAudit::describePlan).toList()
					+ " actualOpcode=" + actualOpcode
					+ " instruction=" + quotedInstruction(instruction));

		PlanEntry first = entries.get(0);
		ExecType actualExec = actualExec(instruction);
		FederatedOutput actualOutput = actualOutput(instruction);
		boolean persistentReadReblock = "PERSISTENT_READ_REBLOCK".equals(kind)
			|| "PERSISTENT_READ_CSV_REBLOCK".equals(kind);
		ExecType expectedExec = persistentReadReblock ? ExecType.SPARK : first.plan().physicalExec();
		FederatedOutput expectedOutput = persistentReadReblock
			? FederatedOutput.LOUT : first.plan().physicalOutput();
		FType expectedFType = persistentReadReblock ? null : first.plan().physicalFType();
		boolean readOwnerPlacement = first.plan().physicalExec() == ExecType.CP
			&& first.plan().physicalOutput() == FederatedOutput.LOUT;
		if(persistentReadReblock && !readOwnerPlacement)
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] LOWERING_REWRITE_OWNER_PLACEMENT_MISMATCH kind=" + kind
					+ " owner=" + describePlan(first.plan())
					+ " instruction=" + quotedInstruction(instruction));
		if(actualExec != expectedExec || actualOutput != expectedOutput)
			throw mismatch("LOWERING_REWRITE_PLACEMENT_MISMATCH", first.plan(), instruction,
				actualExec, actualOutput);

		List<PlannedHop> plans = entries.stream().map(PlanEntry::plan)
			.sorted(Comparator.comparing(PlannedHop::keyHash)).toList();
		String auditKey = runtimeKey(authority.planHash, plans, instruction);
		LoweredExpectation expected = new LoweredExpectation(auditKey, authority.planHash, plans, null,
			expectedExec, expectedOutput, expectedFType,
			actualOpcode, instruction.getHopID(), instruction.getLopID(),
			instruction.getPlannerRecompileSignature(), false);
		LoweredExpectation prior = LOWERED.putIfAbsent(auditKey, expected);
		if(prior != null && !sameExpectation(prior, expected))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] conflicting rewrite replacement proof key=" + auditKey);
		instruction.setPlannerAuditKey(auditKey);
		for(PlanEntry entry : entries)
			observeLowering(authority.planHash, planKey(authority.planHash, entry.plan()),
				entry.plan(), instruction,
				"REWRITE_MATCH", "kind=" + kind + " replacementOpcode=" + actualOpcode);
		return auditKey;
	}

	private static boolean isAggregateBinaryOpcode(String opcode) {
		return "ba+*".equalsIgnoreCase(opcode) || "mapmm".equalsIgnoreCase(opcode)
			|| "cpmm".equalsIgnoreCase(opcode) || "rmm".equalsIgnoreCase(opcode);
	}

	private static boolean isFullSumAggregateOpcode(String opcode) {
		return "ua(+rc)".equalsIgnoreCase(opcode) || "uak+rc".equalsIgnoreCase(opcode)
			|| "sum".equalsIgnoreCase(opcode);
	}

	private static boolean isExactTernaryAggregateFusion(String planned, String actual) {
		return "ua(+rc)".equalsIgnoreCase(planned) && "tak+*".equals(actual)
			|| "ua(+c)".equalsIgnoreCase(planned) && "tack+*".equals(actual);
	}

	/**
	 * Open the exact coordinator FED parent scope for requests created by this instruction.
	 * Non-FED instructions do not manufacture authority.  If they are nested inside the runtime
	 * implementation of a proved FED instruction, the outer authority remains active until the
	 * outer scope closes.
	 */
	public static RuntimeExecutionScope beginRuntimeExecution(Instruction instruction) {
		if(!isEnabled() || instruction == null || CURRENT == null
			|| instruction.getPlannerWorkerFragment() != null)
			return new RuntimeExecutionScope(null, false);
		String auditKey = instruction.getPlannerAuditKey();
		LoweredExpectation expected = auditKey == null ? null : LOWERED.get(auditKey);
		if(auditKey != null && expected == null)
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] RUNTIME_UNKNOWN_PROOF key=" + auditKey);
		if(expected != null)
			validateActiveAuthority(expected, instruction);
		if(expected == null || expected.exec() != ExecType.FED)
			return new RuntimeExecutionScope(null, false);
		LoweredExpectation previous = ACTIVE_FEDERATED_PARENT.get();
		ACTIVE_FEDERATED_PARENT.set(expected);
		return new RuntimeExecutionScope(previous, true);
	}

	/**
	 * Attach and validate the exact coordinator parent on every request sent to a federated
	 * worker.  Compute requests without parent authority fail closed; only worker-side rmvar
	 * lifecycle cleanup may legally exist outside a physical planner Hop.
	 */
	public static void validateFederatedRequestDispatch(FederatedRequest... requests) {
		if(requests == null)
			return;
		for(FederatedRequest request : requests) {
			if(request == null)
				continue;
			PlannerRuntimeAuthority parent = request.getPlannerRuntimeAuthority();
			LoweredExpectation active = ACTIVE_FEDERATED_PARENT.get();
			if(parent == null && active != null) {
				parent = authorityFor(active);
				request.setPlannerRuntimeAuthority(parent);
			}
			boolean compute = request.getType() == RequestType.EXEC_INST
				|| request.getType() == RequestType.EXEC_UDF;
			if(parent == null) {
				if(isEnabled() && CURRENT != null && compute && !isWorkerLifecycleRequest(request))
					throw new IllegalStateException(
						"[PlannerRuntimeAudit] FEDERATED_REQUEST_UNPLANNED plan=" + CURRENT.planHash
							+ " requestType=" + request.getType() + " fragmentOpcode="
							+ requestFragmentOpcode(request));
				continue;
			}
			validateParentAuthority(parent);
			FederatedDispatchKey key = new FederatedDispatchKey(parent.getPlanHash(), parent.getParentAuditKey(),
				request.getType().name(), requestFragmentOpcode(request), parent.getParentPhysical(),
				parent.getParentHopId(), parent.getParentLopId());
			FEDERATED_DISPATCHED.computeIfAbsent(key, ignored -> new LongAdder()).increment();
		}
	}

	/** Attach serialized coordinator authority to the exact instruction parsed by a worker. */
	public static void attachWorkerFragment(FederatedRequest request, Instruction instruction) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(instruction, "instruction");
		if(request.getType() != RequestType.EXEC_INST)
			throw new IllegalArgumentException("Worker instruction proof requires EXEC_INST");
		PlannerRuntimeAuthority parent = request.getPlannerRuntimeAuthority();
		if(parent == null)
			return;
		validateParentAuthority(parent);
		String requestOpcode = requestFragmentOpcode(request);
		String actualOpcode = safeOpcode(instruction);
		if(!requestOpcode.equals(actualOpcode))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] WORKER_FRAGMENT_OPCODE_MISMATCH parent="
					+ parent.getParentAuditKey() + " requestOpcode=" + requestOpcode
					+ " actualOpcode=" + actualOpcode);
		instruction.setPlannerWorkerFragment(new PlannerWorkerFragment(parent.getPlanHash(),
			parent.getParentAuditKey(), parent.getParentOpcode(), parent.getParentPhysical(),
			parent.getParentHopId(), parent.getParentLopId(),
			parent.getParentRecompileSignature(), request.getType().name(), actualOpcode));
	}

	/** Validate one worker UDF dispatch against its exact coordinator FED parent. */
	public static void validateWorkerUdf(FederatedRequest request) {
		Objects.requireNonNull(request, "request");
		if(request.getType() != RequestType.EXEC_UDF)
			throw new IllegalArgumentException("Worker UDF proof requires EXEC_UDF");
		PlannerRuntimeAuthority parent = request.getPlannerRuntimeAuthority();
		if(parent == null) {
			if(isEnabled() && CURRENT != null)
				throw new IllegalStateException(
					"[PlannerRuntimeAudit] WORKER_UDF_UNPLANNED plan=" + CURRENT.planHash);
			return;
		}
		validateParentAuthority(parent);
	}

	/** Record a worker UDF only after the UDF returned successfully. */
	public static void recordSuccessfulWorkerUdf(FederatedRequest request) {
		PlannerRuntimeAuthority parent = request == null ? null : request.getPlannerRuntimeAuthority();
		if(parent == null)
			return;
		validateWorkerUdf(request);
		WorkerFragmentExecutionKey key = new WorkerFragmentExecutionKey(parent.getPlanHash(),
			parent.getParentAuditKey(), request.getType().name(), requestFragmentOpcode(request),
			"UDF/INTERNAL", parent.getParentHopId(), parent.getParentLopId());
		WORKER_FRAGMENTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
	}

	/** Validate the post-preprocess instruction immediately before runtime execution. */
	public static void validateExecution(Instruction instruction) {
		if(instruction == null)
			return;
		PlannerWorkerFragment fragment = instruction.getPlannerWorkerFragment();
		if(fragment != null) {
			validateWorkerFragment(fragment, instruction);
			return;
		}
		if(!isEnabled() || CURRENT == null)
			return;
		String auditKey = instruction.getPlannerAuditKey();
		if(auditKey == null) {
			if(isAuxiliary(instruction))
				return;
			throw new IllegalStateException("[PlannerRuntimeAudit] RUNTIME_UNPLANNED plan="
				+ CURRENT.planHash + " instruction=" + describeInstruction(instruction)
				+ " syntheticAction="
				+ Objects.toString(instruction.getPlannerSyntheticActionKey(), "-"));
		}
		LoweredExpectation expected = LOWERED.get(auditKey);
		if(expected == null)
			throw new IllegalStateException("[PlannerRuntimeAudit] RUNTIME_UNKNOWN_PROOF key=" + auditKey);
		validateActiveAuthority(expected, instruction);
		ExecType actualExec = actualExec(instruction);
		FederatedOutput actualOutput = actualOutput(instruction);
		FType encodedFType = actualSyntheticFType(instruction);
		String actualSynthetic = instruction.getPlannerSyntheticActionKey();
		String expectedSynthetic = expected.synthetic() == null ? null : expected.synthetic().token();
		if(actualExec != expected.exec() || actualOutput != expected.output()
			|| !expected.opcode().equals(safeOpcode(instruction))
			|| !Objects.equals(expectedSynthetic, actualSynthetic)
			|| expected.synthetic() != null && expected.fType() != encodedFType)
			throw executionMismatch("RUNTIME_MISMATCH", expected, instruction,
				actualExec, actualOutput, encodedFType);
	}

	/** Count only instructions whose processInstruction completed successfully. */
	public static void recordSuccessfulExecution(Instruction instruction) {
		recordSuccessfulExecution(instruction, null);
	}

	/** Prove the materialized runtime value after execution, including its actual FederationMap type. */
	public static void recordSuccessfulExecution(Instruction instruction, ExecutionContext ec) {
		if(instruction == null)
			return;
		PlannerWorkerFragment fragment = instruction.getPlannerWorkerFragment();
		if(fragment != null) {
			validateWorkerFragment(fragment, instruction);
			WorkerFragmentExecutionKey key = new WorkerFragmentExecutionKey(fragment.planHash(),
				fragment.parentAuditKey(), fragment.requestType(), fragment.fragmentOpcode(),
				placement(actualExec(instruction), actualOutput(instruction), null),
				fragment.parentHopId(), fragment.parentLopId());
			WORKER_FRAGMENTS.computeIfAbsent(key, ignored -> new LongAdder()).increment();
			return;
		}
		if(!isEnabled() || CURRENT == null)
			return;
		validateExecution(instruction);
		String auditKey = instruction.getPlannerAuditKey();
		if(auditKey == null) {
			ExecutionKey auxiliary = new ExecutionKey("-",
				placement(actualExec(instruction), actualOutput(instruction), null), safeOpcode(instruction),
				instruction.getHopID(), instruction.getLopID(),
				instruction.getPlannerRecompileSignature(), "AUXILIARY");
			EXECUTED.computeIfAbsent(auxiliary, ignored -> new LongAdder()).increment();
			return;
		}
		LoweredExpectation expected = LOWERED.get(auditKey);
		RuntimePlacement materialized = materializedPlacement(instruction, ec);
		FederatedOutput expectedValueOutput = expectedValueOutput(expected);
		FType expectedValueFType = expectedValueFType(expected);
		boolean outputMismatch = materialized.output() != null
			&& materialized.output() != expectedValueOutput;
		boolean typeMismatch = expectedValueFType != null && materialized.fType() != null
			&& materialized.fType() != expectedValueFType;
		boolean missingFederationMap = expectedValueOutput == FederatedOutput.FOUT
			&& expectedValueFType != null && ec != null && materialized.fType() == null;
		if(outputMismatch || typeMismatch || missingFederationMap)
			throw executionMismatch("RUNTIME_VALUE_MISMATCH", expected, instruction,
				actualExec(instruction), materialized.output() == null
					? actualOutput(instruction) : materialized.output(), materialized.fType());
		FType actualFType = materialized.fType() != null
			? materialized.fType() : actualSyntheticFType(instruction);
		ExecutionKey key = new ExecutionKey(auditKey == null ? "-" : auditKey,
			placement(actualExec(instruction), materialized.output() == null
				? actualOutput(instruction) : materialized.output(), actualFType), safeOpcode(instruction),
			instruction.getHopID(), instruction.getLopID(), instruction.getPlannerRecompileSignature(), "MATCH");
		EXECUTED.computeIfAbsent(key, ignored -> new LongAdder()).increment();
	}

	public static String display() {
		if(!isEnabled())
			return "";
		Authority authority = CURRENT;
		List<PlanEntry> plannedPhysical = authority == null ? List.of()
			: authority.byPlanKey.values().stream().filter(PlannerRuntimePlacementAudit::isPhysicalOperation)
				.sorted(Comparator.comparing(entry -> entry.plan().keyHash())).toList();
		List<PlanEntry> missingPhysical = authority == null ? List.of()
			: plannedPhysical.stream().filter(entry -> !LOWERED_PLAN_KEYS.contains(
				planKey(authority.planHash, entry.plan()))).toList();
		int loweredPhysical = plannedPhysical.size() - missingPhysical.size();
		List<PlannedSyntheticAction> missingSynthetics = authority == null ? List.of()
			: authority.syntheticByToken.values().stream()
				.filter(action -> !LOWERED_SYNTHETIC_KEYS.contains(
					syntheticPlanKey(authority.planHash, action.token())))
				.sorted(Comparator.comparing(PlannedSyntheticAction::token)).toList();
		StringBuilder out = new StringBuilder();
		out.append("[PlannerRuntimeAudit][Summary] plan=")
			.append(authority == null ? "-" : authority.planHash)
			.append(" authorityGenerations=").append(AUTHORITY_PLAN_HASHES.size())
			.append(" plannedHops=").append(authority == null ? 0 : authority.plans.size())
			.append(" plannedPhysicalHops=").append(plannedPhysical.size())
			.append(" loweredPhysicalHops=").append(loweredPhysical)
			.append(" missingPhysicalHops=").append(missingPhysical.size())
			.append(" plannedSynthetic=").append(authority == null ? 0 : authority.syntheticByToken.size())
			.append(" missingSynthetic=").append(missingSynthetics.size())
			.append(" loweringRecords=").append(LOWERING_OBSERVATIONS.size())
			.append(" runtimeInstructionKinds=").append(EXECUTED.size())
			.append(" federatedDispatchKinds=").append(FEDERATED_DISPATCHED.size())
			.append(" workerFragmentKinds=").append(WORKER_FRAGMENTS.size())
			.append(" mismatches=").append(missingSynthetics.size() + missingPhysical.size()).append('\n');
		for(PlanEntry entry : missingPhysical)
			out.append("[PlannerRuntimeAudit][Lowering] status=MISSING plan=")
				.append(authority.planHash).append(" planner=").append(entry.plan().plannerId())
				.append(" hop=").append(entry.plan().hopId()).append(" key=")
				.append(entry.plan().keyHash()).append(" signature=")
				.append(signatureToken(entry.plan().recompileSignature())).append(" opcode=")
				.append(entry.plan().opcode()).append(" nodeKind=").append(entry.plan().nodeKind())
				.append(" plannedTarget=").append(entry.plan().targetSignature())
				.append(" plannedPhysical=").append(entry.plan().physicalSignature())
				.append(" actual=- auditKey=- actualIdentity=- instruction=-\n");
		for(PlannedSyntheticAction action : missingSynthetics)
			out.append("[PlannerRuntimeAudit][Lowering-Synthetic] status=MISSING plan=")
				.append(authority.planHash).append(" action=").append(shortHash(action.baseActionKey()))
				.append(" stage=").append(action.stage()).append(" token=").append(shortHash(action.token()))
				.append(" opcode=").append(action.opcode()).append(" plannedPhysical=")
				.append(action.physicalSignature()).append(" actual=-\n");
		LOWERING_OBSERVATIONS.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(entry -> out.append(entry.getValue()).append('\n'));
		EXECUTED.entrySet().stream().sorted(Comparator.comparing(entry -> executionSortKey(entry.getKey())))
			.forEach(entry -> {
				ExecutionKey key = entry.getKey();
				LoweredExpectation expected = "-".equals(key.auditKey()) ? null : LOWERED.get(key.auditKey());
				out.append("[PlannerRuntimeAudit][Execution] status=").append(key.status())
					.append(" plan=").append(expected == null ? "-" : expected.planHash())
					.append(" auditKey=").append(key.auditKey())
					.append(" hop=").append(key.hopId()).append(" lop=").append(key.lopId())
					.append(" signature=").append(signatureToken(key.recompileSignature()))
					.append(" opcode=").append(key.opcode())
					.append(" plannedTarget=").append(expected == null ? "-" : expectedTarget(expected))
					.append(" plannedPhysical=").append(expected == null ? "-"
						: placement(expected.exec(), expected.output(), expected.fType()))
					.append(" plannedValue=").append(expected == null ? "-"
						: placement(null, expectedValueOutput(expected), expectedValueFType(expected)))
					.append(" syntheticAction=").append(expected == null || expected.synthetic() == null
						? "-" : shortHash(expected.synthetic().token()))
					.append(" actual=").append(key.actual())
						.append(" count=").append(entry.getValue().sum()).append('\n');
			});
		FEDERATED_DISPATCHED.entrySet().stream()
			.sorted(Comparator.comparing(entry -> federatedDispatchSortKey(entry.getKey())))
			.forEach(entry -> {
				FederatedDispatchKey key = entry.getKey();
				out.append("[PlannerRuntimeAudit][Federated-Dispatch] status=MATCH")
					.append(" plan=").append(key.planHash())
					.append(" parentAuditKey=").append(key.auditKey())
					.append(" parentHop=").append(key.parentHopId())
					.append(" parentLop=").append(key.parentLopId())
					.append(" parentPhysical=").append(key.parentPhysical())
					.append(" requestType=").append(key.requestType())
					.append(" fragmentOpcode=").append(key.fragmentOpcode())
					.append(" count=").append(entry.getValue().sum()).append('\n');
			});
		WORKER_FRAGMENTS.entrySet().stream()
			.sorted(Comparator.comparing(entry -> workerFragmentSortKey(entry.getKey())))
			.forEach(entry -> {
				WorkerFragmentExecutionKey key = entry.getKey();
				out.append("[PlannerRuntimeAudit][Worker-Fragment] status=MATCH")
					.append(" plan=").append(key.planHash())
					.append(" parentAuditKey=").append(key.parentAuditKey())
					.append(" parentHop=").append(key.parentHopId())
					.append(" parentLop=").append(key.parentLopId())
					.append(" requestType=").append(key.requestType())
					.append(" fragmentOpcode=").append(key.fragmentOpcode())
					.append(" actual=").append(key.actual())
					.append(" count=").append(entry.getValue().sum()).append('\n');
			});
		return out.toString();
	}

	public static void resetForTesting() {
		CURRENT = null;
		LOWERED.clear();
		EXECUTED.clear();
		FEDERATED_DISPATCHED.clear();
		WORKER_FRAGMENTS.clear();
		LOWERING_OBSERVATIONS.clear();
		LOWERED_PLAN_KEYS.clear();
		LOWERED_SYNTHETIC_KEYS.clear();
		AUTHORITY_PLAN_HASHES.clear();
		ACTIVE_FEDERATED_PARENT.remove();
	}

	private static PlannerRuntimeAuthority authorityFor(LoweredExpectation expected) {
		return new PlannerRuntimeAuthority(expected.planHash(), expected.key(), expected.opcode(),
			placement(expected.exec(), expected.output(), expected.fType()), expected.hopId(),
			expected.lopId(), expected.recompileSignature());
	}

	private static void validateParentAuthority(PlannerRuntimeAuthority parent) {
		if(parent.getPlanHash() == null || parent.getPlanHash().isBlank()
			|| parent.getParentAuditKey() == null || parent.getParentAuditKey().isBlank()
			|| parent.getParentOpcode() == null || parent.getParentOpcode().isBlank()
			|| parent.getParentPhysical() == null
			|| !parent.getParentPhysical().startsWith(ExecType.FED + "/"))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] WORKER_FRAGMENT_INVALID_PARENT plan="
					+ Objects.toString(parent.getPlanHash(), "-") + " auditKey="
					+ Objects.toString(parent.getParentAuditKey(), "-") + " opcode="
					+ Objects.toString(parent.getParentOpcode(), "-") + " physical="
					+ Objects.toString(parent.getParentPhysical(), "-"));
		Authority authority = CURRENT;
		if(authority == null)
			return; // remote worker: the immutable proof was serialized by the coordinator
		LoweredExpectation expected = LOWERED.get(parent.getParentAuditKey());
		if(expected == null)
			throw new IllegalStateException(
					"[PlannerRuntimeAudit] WORKER_FRAGMENT_UNKNOWN_PARENT key="
						+ parent.getParentAuditKey());
		if(!expected.planHash().equals(parent.getPlanHash()))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] WORKER_FRAGMENT_PLAN_MISMATCH proof="
					+ expected.planHash() + " actual=" + parent.getPlanHash());
		validateActiveAuthority(expected, null);
		String expectedPhysical = placement(expected.exec(), expected.output(), expected.fType());
		if(expected.exec() != ExecType.FED || !expected.opcode().equals(parent.getParentOpcode())
			|| !expectedPhysical.equals(parent.getParentPhysical())
			|| expected.hopId() != parent.getParentHopId()
			|| expected.lopId() != parent.getParentLopId()
			|| !Objects.equals(expected.recompileSignature(), parent.getParentRecompileSignature()))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] WORKER_FRAGMENT_PARENT_MISMATCH key="
					+ parent.getParentAuditKey() + " expectedOpcode=" + expected.opcode()
					+ " actualOpcode=" + parent.getParentOpcode() + " expectedPhysical="
					+ expectedPhysical + " actualPhysical=" + parent.getParentPhysical());
	}

	private static void validateWorkerFragment(PlannerWorkerFragment fragment,
		Instruction instruction) {
		PlannerRuntimeAuthority parent = new PlannerRuntimeAuthority(fragment.planHash(),
			fragment.parentAuditKey(), fragment.parentOpcode(), fragment.parentPhysical(),
			fragment.parentHopId(), fragment.parentLopId(), fragment.parentRecompileSignature());
		validateParentAuthority(parent);
		if(!RequestType.EXEC_INST.name().equals(fragment.requestType())
			|| !safeOpcode(instruction).equals(fragment.fragmentOpcode()))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] WORKER_FRAGMENT_MISMATCH parent="
					+ fragment.parentAuditKey() + " requestType=" + fragment.requestType()
					+ " fragmentOpcode=" + fragment.fragmentOpcode() + " actualOpcode="
					+ safeOpcode(instruction));
	}

	private static boolean isWorkerLifecycleRequest(FederatedRequest request) {
		if(request == null || request.getType() != RequestType.EXEC_INST)
			return false;
		String opcode = requestFragmentOpcode(request);
		return "rmvar".equals(opcode) || "rmfilevar".equals(opcode);
	}

	private static String requestFragmentOpcode(FederatedRequest request) {
		if(request == null || request.getNumParams() == 0)
			return "-";
		Object payload = request.getParam(0);
		if(request.getType() == RequestType.EXEC_INST && payload instanceof String instruction) {
			try {
				return InstructionUtils.getOpCode(instruction);
			}
			catch(RuntimeException ex) {
				return "INVALID_INSTRUCTION";
			}
		}
		return payload == null ? "null" : payload.getClass().getSimpleName();
	}

	private static void observeAuxiliary(Authority authority, Instruction instruction, String status) {
		String key = "actual:" + authority.planHash + ':' + runtimeIdentity(instruction);
		LOWERING_OBSERVATIONS.putIfAbsent(key, "[PlannerRuntimeAudit][Lowering] status=" + status
			+ " plan=" + authority.planHash + " hop=" + instruction.getHopID()
			+ " lop=" + instruction.getLopID() + " signature="
			+ signatureToken(instruction.getPlannerRecompileSignature()) + " opcode="
			+ safeOpcode(instruction) + " plannedTarget=- plannedPhysical=- actual="
			+ placement(actualExec(instruction), actualOutput(instruction), null));
	}

	private static void observeLowering(String planHash, String key, PlannedHop plan,
		Instruction instruction, String status, String detail) {
		String actual = instruction == null ? "-" : placement(actualExec(instruction), actualOutput(instruction), null);
		String line = "[PlannerRuntimeAudit][Lowering] status=" + status + " plan=" + planHash
			+ " planner=" + plan.plannerId()
			+ " hop=" + plan.hopId() + " key=" + plan.keyHash() + " signature="
			+ signatureToken(plan.recompileSignature()) + " opcode=" + plan.opcode()
			+ " nodeKind=" + plan.nodeKind() + " plannedTarget=" + plan.targetSignature()
			+ " plannedPhysical=" + plan.physicalSignature() + " actual=" + actual
			+ " auditKey=" + (instruction == null ? "-" : Objects.toString(instruction.getPlannerAuditKey(), "-"))
			+ " actualIdentity=" + (instruction == null ? "-" : describeInstruction(instruction))
			+ " instruction=" + (instruction == null ? "-" : quotedInstruction(instruction))
			+ (detail == null ? "" : " detail=" + detail);
		if(isDefinitiveLoweringStatus(status)) {
			// A plan occurrence can be encountered as an input ancestor before the Dag
			// invocation that emits it.  Its later exact proof must replace the provisional
			// NOT_EMITTED/DEFERRED observation in the final report.
			LOWERING_OBSERVATIONS.put(key, line);
			LOWERED_PLAN_KEYS.add(key);
		}
		else
			LOWERING_OBSERVATIONS.putIfAbsent(key, line);
	}

	private static boolean isDefinitiveLoweringStatus(String status) {
		return "MATCH".equals(status) || "REWRITE_MATCH".equals(status)
			|| "CONTROL_MATCH".equals(status) || "VALUE_CONTROL_MATCH".equals(status)
			|| "FUSED_MATCH".equals(status) || "AUTHORITY_CARRY_FORWARD_MATCH".equals(status);
	}

	private static List<PlanEntry> resolve(Authority authority, Instruction instruction) {
		List<PlanEntry> byId = instruction.getHopID() < 0 ? List.of()
			: authority.byHopId.getOrDefault(instruction.getHopID(), List.of());
		List<PlanEntry> byOrigin = instruction.getPlannerOriginHopID() < 0 ? List.of()
			: authority.byHopId.getOrDefault(instruction.getPlannerOriginHopID(), List.of());
		List<PlanEntry> byIdentity = !byId.isEmpty() ? byId : byOrigin;
		String signature = instruction.getPlannerRecompileSignature();
		List<PlanEntry> bySignature = signature == null ? List.of()
			: authority.bySignature.getOrDefault(signature, List.of());
		if(!byIdentity.isEmpty() && !bySignature.isEmpty()) {
			List<PlanEntry> intersection = byIdentity.stream().filter(bySignature::contains).toList();
			if(!intersection.isEmpty())
				return intersection;
		}
		return !byIdentity.isEmpty() ? byIdentity : unambiguousPhysical(bySignature);
	}

	private static List<PlanEntry> unambiguousPhysical(List<PlanEntry> entries) {
		if(entries.isEmpty())
			return entries;
		String state = entries.get(0).plan().physicalSignature();
		return entries.stream().allMatch(entry -> entry.plan().physicalSignature().equals(state))
			? entries : List.of();
	}

	private static void assertOnePhysicalPlacement(List<PlanEntry> entries, Instruction instruction) {
		String expected = entries.get(0).plan().physicalSignature();
		for(PlanEntry entry : entries)
			if(!entry.plan().physicalSignature().equals(expected))
				throw new IllegalStateException("[PlannerRuntimeAudit] LOWERING_AMBIGUOUS hop="
					+ instruction.getHopID() + " signature="
					+ signatureToken(instruction.getPlannerRecompileSignature()));
	}

	private static FusedResolution coveredAncestor(Authority authority, PlanEntry missing,
		Map<Hop,String> coveredHopObjects) {
		if(missing.hop() == null)
			return null;
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<Hop> pending = new ArrayDeque<>(missing.hop().getParent());
		while(!pending.isEmpty()) {
			Hop parent = pending.removeFirst();
			if(parent == null || !visited.add(parent))
				continue;
			String covered = coveredHopObjects.get(parent);
			if(covered != null) {
				LoweredExpectation expected = LOWERED.get(covered);
				return new FusedResolution(covered, expected != null
					&& expected.exec() == missing.plan().physicalExec()
					&& expected.output() == missing.plan().physicalOutput());
			}
			PlanEntry parentPlan = authority.byHopId.getOrDefault(parent.getHopID(), List.of()).stream()
				.filter(entry -> entry.hop() == parent).findFirst().orElse(null);
			if(parentPlan != null && (!parentPlan.plan().physicalSignature()
				.equals(missing.plan().physicalSignature()) || parentPlan.plan().requiresOwnInstruction()))
				return null;
			pending.addAll(parent.getParent());
		}
		return null;
	}

	private static Set<Hop> collectHopClosure(List<Hop> roots) {
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		if(roots == null || roots.isEmpty())
			return visited;
		ArrayDeque<Hop> pending = new ArrayDeque<>(roots);
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(hop == null || !visited.add(hop))
				continue;
			pending.addAll(hop.getInput());
		}
		return visited;
	}

	private static Set<Hop> collectLoweredHopClosure(List<Hop> roots, List<Lop> loweredLops) {
		Set<Hop> all = collectHopClosure(roots);
		Set<Lop> physical = Collections.newSetFromMap(new IdentityHashMap<>());
		physical.addAll(loweredLops);
		Set<Long> physicalHopIds = new LinkedHashSet<>();
		for(Lop lop : loweredLops) {
			if(lop.getHopID() >= 0)
				physicalHopIds.add(lop.getHopID());
		}
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(Hop hop : all) {
			Lop lop = hop.getLops();
			if(lop != null && physical.contains(lop) || physicalHopIds.contains(hop.getHopID()))
				pending.add(hop);
		}
		Set<Hop> relevant = Collections.newSetFromMap(new IdentityHashMap<>());
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(hop == null || !relevant.add(hop))
				continue;
			pending.addAll(hop.getInput());
		}
		return relevant;
	}

	private static boolean isPhysicalOperation(PlanEntry entry) {
		PlannedHop plan = entry.plan();
		return !(entry.hop() instanceof LiteralOp) && plan.emittedWork()
			&& (plan.nodeKind() == NodeKind.OPERATION || plan.nodeKind() == NodeKind.CLONE);
	}

	private static boolean isAuxiliary(Instruction instruction) {
		if(isLifecycleAuxiliary(instruction))
			return true;
		if(instruction.getPlannerSyntheticActionKey() != null)
			return false;
		if(!(instruction instanceof CPInstruction cp) || cp.getCPInstructionType() != CPType.Variable)
			return false;
		if(instruction instanceof VariableCPInstruction variable) {
			VariableOperationCode opcode = variable.getVariableOpcode();
			// createvar/rmvar are lifecycle side instructions emitted around the real
			// compute instruction and intentionally inherit that Lop/Hop provenance.
			// Comparing their coordinator execution type with the value-producing Hop
			// would falsely report every FED result as CP/LOUT. The corresponding real
			// instruction must still be observed, so a missing FED operation fails later.
				if(opcode == VariableOperationCode.CreateVariable
					|| opcode == VariableOperationCode.RemoveVariable
					|| opcode == VariableOperationCode.RemoveVariableAndFile
					|| opcode == VariableOperationCode.SetFileName)
					return true;
			}
			// Assign/copy/move variable instructions can alter the materialized placement and
			// therefore require exact Hop provenance.  Treating every provenance-free Variable
			// instruction as lifecycle would let an implicit runtime placement repair escape the
			// audit.  Only the explicit lifecycle opcodes above are auxiliary.
			return false;
	}

	private static boolean isLifecycleAuxiliary(Instruction instruction) {
		String opcode = safeOpcode(instruction);
		return "createvar".equals(opcode) || "rmvar".equals(opcode)
			|| "rmfilevar".equals(opcode) || "setfilename".equals(opcode);
	}

	private static boolean isFunctionCallControl(List<PlanEntry> entries, Instruction instruction) {
		if(!(instruction instanceof FunctionCallCPInstruction call))
			return false;
		if(entries.isEmpty() || entries.stream().anyMatch(entry ->
			entry.plan().nodeKind() != NodeKind.FUNCTION_CALL))
			return false;
		if(actualExec(instruction) != ExecType.CP || actualOutput(instruction) != FederatedOutput.LOUT)
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] FUNCTION_CONTROL_MISMATCH instruction="
					+ describeInstruction(instruction));
		String actualFunction = call.getNamespace() + Program.KEY_DELIM + call.getFunctionName();
		if(entries.stream().anyMatch(entry -> !actualFunction.equals(entry.plan().controlTarget())))
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] FUNCTION_CONTROL_TARGET_MISMATCH planned="
					+ entries.stream().map(PlanEntry::plan)
						.map(PlannerRuntimePlacementAudit::describePlan).toList()
					+ " actualFunction=" + actualFunction
					+ " instruction=" + quotedInstruction(instruction));
		return true;
	}

	private static boolean isVariablePlacementControl(List<PlanEntry> entries, Instruction instruction) {
		if(!(instruction instanceof VariableCPInstruction variable) || entries.isEmpty()
			|| entries.stream().anyMatch(entry -> !isValueBindingNode(entry.plan().nodeKind())))
			return false;
		VariableOperationCode opcode = variable.getVariableOpcode();
		return opcode == VariableOperationCode.AssignVariable
			|| opcode == VariableOperationCode.CopyVariable
			|| opcode == VariableOperationCode.MoveVariable;
	}

	private static boolean isValueBindingNode(NodeKind kind) {
		return kind == NodeKind.TRANSIENT_WRITE || kind == NodeKind.LOOP_PHI
			|| kind == NodeKind.FUNCTION_OUTPUT || kind == NodeKind.FUNCTION_INPUT;
	}

	private static ExecType actualExec(Instruction instruction) {
		IType type = instruction.getType();
		if(type == IType.FEDERATED)
			return ExecType.FED;
		if(type == IType.SPARK)
			return ExecType.SPARK;
		return ExecType.CP;
	}

	private static FederatedOutput actualOutput(Instruction instruction) {
		return instruction instanceof FEDInstruction fed ? fed.getFederatedOutput() : FederatedOutput.LOUT;
	}

	private static FType actualSyntheticFType(Instruction instruction) {
		if(instruction instanceof FEDFoutInstruction fout)
			return fout.getMaterializationFType();
		if(instruction instanceof FEDRefedInstruction refed)
			return refed.getMaterializationFType();
		return null;
	}

	private static RuntimePlacement materializedPlacement(Instruction instruction, ExecutionContext ec) {
		String outputVariable = instruction.getOutputVariableName();
		if(ec == null || outputVariable == null || outputVariable.isBlank()
			|| !ec.containsVariable(outputVariable))
			return new RuntimePlacement(null, actualSyntheticFType(instruction), outputVariable);
		Object value = ec.getVariable(outputVariable);
		if(value instanceof CacheableData<?> cacheable) {
			if(cacheable.isFederated() && cacheable.getFedMapping() != null)
				return new RuntimePlacement(FederatedOutput.FOUT,
					cacheable.getFedMapping().getType(), outputVariable);
			return new RuntimePlacement(FederatedOutput.LOUT, null, outputVariable);
		}
		return new RuntimePlacement(FederatedOutput.LOUT, null, outputVariable);
	}

	private static IllegalStateException mismatch(String code, PlannedHop plan, Instruction instruction,
		ExecType actualExec, FederatedOutput actualOutput) {
		return new IllegalStateException("[PlannerRuntimeAudit] " + code + " planner=" + plan.plannerId()
			+ " hop=" + plan.hopId() + " key=" + plan.keyHash() + " opcode=" + safeOpcode(instruction)
			+ " plannedOpcode=" + plan.opcode() + " nodeKind=" + plan.nodeKind()
			+ " plannedTarget=" + plan.targetSignature() + " plannedPhysical="
			+ plan.physicalSignature() + " actual=" + placement(actualExec, actualOutput, null));
	}

	private static IllegalStateException executionMismatch(String code, LoweredExpectation expected,
		Instruction instruction, ExecType actualExec, FederatedOutput actualOutput, FType actualFType) {
		return new IllegalStateException("[PlannerRuntimeAudit] " + code + " plan=" + expected.planHash()
			+ " auditKey=" + expected.key() + " opcode=" + safeOpcode(instruction)
			+ " plannedOccurrences=" + expected.plans().stream()
				.map(PlannerRuntimePlacementAudit::describePlan).toList()
			+ " plannedOpcode=" + expected.opcode() + " plannedTarget=" + expectedTarget(expected)
			+ " plannedPhysical=" + placement(expected.exec(), expected.output(), expected.fType())
			+ " actual=" + placement(actualExec, actualOutput, actualFType)
			+ " plannedSynthetic=" + (expected.synthetic() == null ? "-"
				: shortHash(expected.synthetic().token()))
			+ " actualSynthetic=" + (instruction.getPlannerSyntheticActionKey() == null ? "-"
				: shortHash(instruction.getPlannerSyntheticActionKey()))
			+ " outputVariable=" + Objects.toString(instruction.getOutputVariableName(), "-")
			+ " instruction=" + quotedInstruction(instruction));
	}

	private static String describePlan(PlannedHop plan) {
		return plan.hopId() + ":" + plan.keyHash() + ":" + plan.nodeKind() + ":"
			+ signatureToken(plan.recompileSignature()) + ":" + plan.targetSignature()
			+ ":name=" + plan.valueName() + ":controlTarget=" + plan.controlTarget()
			+ ":source=" + plan.sourceLocation()
			+ ":inputs=" + plan.inputHops();
	}

	private static String sourceLocation(Hop hop) {
		return Objects.toString(hop.getFilename(), "-") + ':' + hop.getBeginLine() + ':'
			+ hop.getBeginColumn() + '-' + hop.getEndLine() + ':' + hop.getEndColumn();
	}

	private static String describeHop(Hop hop) {
		return hop.getHopID() + ":" + hop.getClass().getSimpleName() + ':'
			+ hop.getOpString() + ":" + Objects.toString(hop.getName(), "-") + ':'
			+ sourceLocation(hop);
	}

	private static String quotedInstruction(Instruction instruction) {
		String raw = instruction == null ? "-" : Objects.toString(instruction.toString(), "-");
		return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static boolean sameExpectation(LoweredExpectation left, LoweredExpectation right) {
		return left.planHash().equals(right.planHash()) && left.plans().equals(right.plans())
			&& Objects.equals(left.synthetic(), right.synthetic())
			&& left.exec() == right.exec() && left.output() == right.output()
			&& left.fType() == right.fType() && left.opcode().equals(right.opcode())
			&& left.valuePlacementControl() == right.valuePlacementControl();
	}

	private static boolean samePlanAuthority(PlannedHop left, PlannedHop right) {
		return left.keyHash().equals(right.keyHash())
			&& left.plannerId().equals(right.plannerId())
			&& Objects.equals(left.recompileSignature(), right.recompileSignature())
			&& left.opcode().equals(right.opcode()) && left.nodeKind() == right.nodeKind()
			&& left.controlTarget().equals(right.controlTarget())
			&& left.emittedWork() == right.emittedWork()
			&& left.selectedTarget().equals(right.selectedTarget())
			&& left.physicalExec() == right.physicalExec()
			&& left.physicalOutput() == right.physicalOutput()
			&& left.physicalFType() == right.physicalFType()
			&& left.requiresOwnInstruction() == right.requiresOwnInstruction()
			&& left.requiresReblock() == right.requiresReblock();
	}

	/** Reject an already-lowered instruction when a later complete replan changed its occurrence. */
	private static void validateActiveAuthority(LoweredExpectation expected, Instruction instruction) {
		Authority active = CURRENT;
		if(active == null || active.planHash.equals(expected.planHash()))
			return;
		if(expected.synthetic() != null) {
			PlannedSyntheticAction selected = active.syntheticByToken.get(expected.synthetic().token());
			if(expected.synthetic().equals(selected))
				return;
			throw new IllegalStateException(
				"[PlannerRuntimeAudit] RUNTIME_STALE_SYNTHETIC_PLAN loweredPlan="
					+ expected.planHash() + " activePlan=" + active.planHash
					+ " action=" + shortHash(expected.synthetic().token())
					+ " instruction=" + (instruction == null ? "-" : describeInstruction(instruction)));
		}
		for(PlannedHop prior : expected.plans()) {
			PlanEntry selected = active.byKeyHash.get(prior.keyHash());
			if(selected == null || !samePlanAuthority(prior, selected.plan()))
				throw new IllegalStateException(
					"[PlannerRuntimeAudit] RUNTIME_STALE_PLAN loweredPlan=" + expected.planHash()
						+ " activePlan=" + active.planHash + " key=" + prior.keyHash()
						+ " lowered=" + describePlan(prior) + " active="
						+ (selected == null ? "-" : describePlan(selected.plan()))
						+ " instruction=" + (instruction == null ? "-" : describeInstruction(instruction)));
		}
	}

	private static FederatedOutput expectedValueOutput(LoweredExpectation expected) {
		return expected.valuePlacementControl() && !expected.plans().isEmpty()
			? expected.plans().get(0).physicalOutput() : expected.output();
	}

	private static FType expectedValueFType(LoweredExpectation expected) {
		return expected.valuePlacementControl() && !expected.plans().isEmpty()
			? expected.plans().get(0).physicalFType() : expected.fType();
	}

	private static String runtimeKey(String planHash, List<PlannedHop> plans, Instruction instruction) {
		return shortHash(planHash + '|' + plans.stream().map(PlannedHop::keyHash).sorted().toList()
			+ '|' + runtimeIdentity(instruction));
	}

	private static String runtimeIdentity(Instruction instruction) {
		return instruction.getHopID() + "|" + instruction.getPlannerOriginHopID() + "|"
			+ instruction.getLopID() + "|"
			+ signatureToken(instruction.getPlannerRecompileSignature()) + "|" + safeOpcode(instruction);
	}

	private static String describeInstruction(Instruction instruction) {
		return instruction.getHopID() + "/origin=" + instruction.getPlannerOriginHopID() + "/"
			+ instruction.getLopID() + "/"
			+ signatureToken(instruction.getPlannerRecompileSignature()) + "/"
			+ safeOpcode(instruction) + "/"
			+ placement(actualExec(instruction), actualOutput(instruction), null);
	}

	private static String planKey(String planHash, PlannedHop plan) {
		return planHash + ':' + plan.keyHash();
	}

	private static String syntheticPlanKey(String planHash, String token) {
		return planHash + ":synthetic:" + token;
	}

	private static String expectedTarget(LoweredExpectation expected) {
		return expected.synthetic() == null ? joinedTargets(expected.plans())
			: "SYNTHETIC/" + expected.synthetic().stage();
	}

	private static String joinedTargets(List<PlannedHop> plans) {
		return String.join(",", plans.stream().map(PlannedHop::targetSignature).distinct().sorted().toList());
	}

	private static String executionSortKey(ExecutionKey key) {
		return key.auditKey() + '|' + key.hopId() + '|' + key.lopId() + '|' + key.opcode();
	}

	private static String federatedDispatchSortKey(FederatedDispatchKey key) {
		return key.auditKey() + '|' + key.parentHopId() + '|' + key.parentLopId() + '|'
			+ key.requestType() + '|' + key.fragmentOpcode();
	}

	private static String workerFragmentSortKey(WorkerFragmentExecutionKey key) {
		return key.parentAuditKey() + '|' + key.parentHopId() + '|' + key.parentLopId() + '|'
			+ key.requestType() + '|' + key.fragmentOpcode() + '|' + key.actual();
	}

	private static String signatureToken(String signature) {
		return signature == null || signature.isBlank() ? "-" : shortHash(signature);
	}

	private static String safeOpcode(Instruction instruction) {
		String opcode = instruction.getOpcode();
		return opcode == null ? "" : opcode;
	}

	private static String placement(ExecType exec, FederatedOutput output, FType fType) {
		return exec + "/" + output + (fType == null ? "" : "/" + fType);
	}

	private static <K,V> Map<K,List<V>> immutableLists(Map<K,List<V>> values) {
		Map<K,List<V>> copy = new LinkedHashMap<>();
		values.forEach((key, value) -> copy.put(key, List.copyOf(value)));
		return Collections.unmodifiableMap(copy);
	}

	private static <T> T exact(Map<CompiledHopKey,T> values, CompiledHopKey key) {
		for(Map.Entry<CompiledHopKey,T> entry : values.entrySet())
			if(entry.getKey() == key)
				return entry.getValue();
		return null;
	}

	private static PlacementEmissionState selectedSourcePlacement(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementEmissionState> selected,
		PlacementIdentity.ValueVersionKey sourceValueVersion) {
		List<PlacementEmissionState> matches = analysis.graph().nodes().stream()
			.filter(node -> node.valueVersion().equals(sourceValueVersion))
			.filter(node -> analysis.isCompiledHopOccurrence(node.key()))
			.map(node -> exact(selected, node.key())).filter(Objects::nonNull).toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("Planner runtime audit relocation source has "
				+ matches.size() + " selected emitted owners");
		return matches.get(0);
	}

	private static String shortHash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(16);
			for(int i = 0; i < 8; i++)
				out.append(String.format("%02x", digest[i]));
			return out.toString();
		}
		catch(NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static String requireText(String value, String name) {
		if(value == null || value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
