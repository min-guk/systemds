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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedDp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.HopsException;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedTypePropagator;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalFunctionInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateDecisionReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.CandidateMapEntry;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.DpSemanticConstructionException;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NeutralEnumerationContext;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.NormalizedCandidateInputs;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.VariableSet;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.apache.sysds.runtime.DMLRuntimeException;

public class FederatedPlannerDpCostEnumerator {
	public record DpEnumerationResult(FederatedPlannerDpMemoTable.FedPlan optimalPlan,
		RewireOccurrenceSnapshot rewireSnapshot, PreSelectionSemanticBlock semanticBlock) {
		public DpEnumerationResult {
			Objects.requireNonNull(optimalPlan, "optimalPlan");
			Objects.requireNonNull(rewireSnapshot, "rewireSnapshot");
			Objects.requireNonNull(semanticBlock, "semanticBlock");
			if(semanticBlock.context().rewireSnapshot() != rewireSnapshot)
				throw new IllegalArgumentException("Semantic block does not retain the exact rewire snapshot");
		}
	}

	public record CandidateNormalizationFixture(HopOccurrenceProjection parentOccurrence,
		List<Pair<Long, FederatedOutput>> planChilds, List<Hop> collectedHops,
		List<FType> collectedFTypes, Map<Long, FType> fedInputTypeMap) {
		public CandidateNormalizationFixture {
			Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			planChilds = List.copyOf(planChilds);
			collectedHops = List.copyOf(collectedHops);
			collectedFTypes = Collections.unmodifiableList(new ArrayList<>(collectedFTypes));
			fedInputTypeMap = Collections.unmodifiableMap(new LinkedHashMap<>(fedInputTypeMap));
		}
	}

	public interface DpEnumerationObserver {
		default void constructionFailed(DpSemanticConstructionException failure) { }
		default void resultPublished(DpEnumerationResult result) { }
		default void oracleEvaluated() { }
		default void costEvaluated() { }
		default void placementDecided() { }
		default void candidateConstructed() { }
		default void repairAttempted() { }
		default void fallbackAttempted() { }
	}

	private static final DpEnumerationObserver NO_OP_OBSERVER = new DpEnumerationObserver() { };
	private static final int MAX_EXACT_FRONTIER_CLOSURE_PASSES = 64;

	/**
	 * One locally enumerated arm selected incompatible states for the same immutable
	 * occurrence/value in its exact child closure.  This is a documented global
	 * legality failure of that arm, not a missing runtime capability and not a reason
	 * to abort enumeration of the remaining legal arms.
	 */
	private static final class ExactPlanClosureConflict extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private ExactPlanClosureConflict(String message) {
			super(message);
		}
	}

	private static final class EnumerationCapture {
		private final NeutralEnumerationContext context;
		private final DpEnumerationObserver observer;
		private final RelocationSelections.CanonicalOrderIndex relocationOrder;
		private final boolean sharedFunctionInputClosure;
		private final boolean seedExactTransientFrontier;
		private final List<CapturedCandidate> candidates = new ArrayList<>();
		private final Map<String,CompiledHopKey> activeFunctionCalls = new LinkedHashMap<>();
		private int rawCandidateCount;
		private EnumerationCapture(NeutralEnumerationContext context, FederatedPlannerDpMemoTable memo,
			DpEnumerationObserver observer) {
			this(context, memo, observer, false, false);
		}
		private EnumerationCapture(NeutralEnumerationContext context, FederatedPlannerDpMemoTable memo,
			DpEnumerationObserver observer, boolean sharedFunctionInputClosure) {
			this(context, memo, observer, sharedFunctionInputClosure, false);
		}
		private EnumerationCapture(NeutralEnumerationContext context, FederatedPlannerDpMemoTable memo,
			DpEnumerationObserver observer, boolean sharedFunctionInputClosure,
			boolean seedExactTransientFrontier) {
			this.context = context;
			this.observer = observer == null ? NO_OP_OBSERVER : observer;
			this.relocationOrder = RelocationSelections.canonicalOrderIndex(
				context.analysis().graph().relocationActions());
			this.sharedFunctionInputClosure = sharedFunctionInputClosure;
			this.seedExactTransientFrontier = seedExactTransientFrontier;
			if(sharedFunctionInputClosure && seedExactTransientFrontier)
				throw new IllegalArgumentException(
					"Exact transient seeding and complete frontier closure are distinct phases");
		}
		private void capture(CandidateOccurrenceSnapshot snapshot, long variantOrdinal) {
			candidates.add(new CapturedCandidate(Objects.requireNonNull(snapshot), variantOrdinal, null));
			rawCandidateCount++;
		}
		private void captureDecisionReceipt(CandidateDecisionReceipt receipt, long variantOrdinal) {
			Objects.requireNonNull(receipt, "receipt");
			if(candidates.isEmpty())
				throw new IllegalStateException("Decision receipt has no captured candidate");
			int last = candidates.size() - 1;
			CapturedCandidate candidate = candidates.get(last);
			if(candidate.receipt() != null || candidate.variantOrdinal() != variantOrdinal
				|| candidate.snapshot() != receipt.candidateSnapshot())
				throw new IllegalArgumentException("Decision receipt differs from captured candidate");
			candidates.set(last, new CapturedCandidate(candidate.snapshot(), variantOrdinal, receipt));
		}
		private void bindActiveFunctionCall(String functionNamespace, CompiledHopKey call) {
			CompiledHopKey prior = activeFunctionCalls.putIfAbsent(functionNamespace, call);
			if(prior != null && prior != call)
				throw new IllegalArgumentException("DP function body changed its exact active call-site authority");
		}
		private CompiledHopKey activeFunctionCall(String functionNamespace) {
			return activeFunctionCalls.get(functionNamespace);
		}
		private PreSelectionSemanticBlock semanticBlock() {
			captureSchedulingOnlyTransientForwardReceipts();
			Map<CompiledHopKey, Integer> parentOrder = new IdentityHashMap<>();
			List<HopOccurrenceProjection> occurrences = context.analysis().occurrences();
			for(int i = 0; i < occurrences.size(); i++)
				parentOrder.put(occurrences.get(i).key(), i);
			List<CapturedCandidate> orderedCandidates = new ArrayList<>(candidates);
			orderedCandidates.sort(Comparator.comparingInt((CapturedCandidate candidate) -> {
				Integer ordinal = parentOrder.get(candidate.snapshot().parentOccurrence());
				if(ordinal == null)
					throw new IllegalStateException("Candidate parent is not owned by the supplied analysis");
				return ordinal;
			}).thenComparingLong(CapturedCandidate::variantOrdinal));
			List<CandidateOccurrenceSnapshot> orderedSnapshots = orderedCandidates.stream()
				.map(CapturedCandidate::snapshot).toList();
			List<Long> candidateVariantOrdinals = orderedCandidates.stream()
				.map(CapturedCandidate::variantOrdinal).toList();
			List<CandidateDecisionReceipt> orderedDecisionReceipts = orderedCandidates.stream()
				.map(CapturedCandidate::receipt).toList();
			return new PreSelectionSemanticBlock(context, orderedSnapshots, candidateVariantOrdinals,
				orderedDecisionReceipts, rawCandidateCount, orderedSnapshots.size(),
				rawCandidateCount == candidates.size());
		}

		/**
		 * A legacy transient-forward edge can be a pure scheduling dependency: it
		 * preserves program order, but it is neither a compiled input nor a logical
		 * transient value edge.  Such an edge must therefore remain absent from the
		 * executable DP child list while still being represented in the semantic
		 * receipt consumed by the exact-selection boundary.
		 */
		private void captureSchedulingOnlyTransientForwardReceipts() {
			Set<RewireTransientForwardEdge> captured = Collections.newSetFromMap(new IdentityHashMap<>());
			for(CapturedCandidate candidate : candidates)
				candidate.snapshot().transientForwardDependencies().forEach(
					dependency -> captured.add(dependency.forwardEdge()));
			for(RewireTransientForwardEdge forward : context.rewireSnapshot().transientForwardEdges()) {
				if(captured.contains(forward) || !isSchedulingOnlyTransientForward(forward))
					continue;
				// A scheduling edge is provenance, not an executable candidate input.  It can
				// share the candidate receipt only when the neutral analysis explicitly owns
				// the corresponding zero-input row.  Never fabricate an empty candidate row
				// for TReads whose exact rows have real ABSENT/PRESENT input authority.
				boolean ownsEmptyCandidateRow = context.analysis().candidateRuleFacts().orderedFacts()
					.stream().anyMatch(fact -> fact.key().parentOccurrence() == forward.readOccurrence()
						&& fact.key().orderedInputs().isEmpty()
						&& fact.status() == PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE);
				if(!ownsEmptyCandidateRow)
					continue;
				PlacementState selected = requireUniqueLocalSchedulingState(forward.writeOccurrence());
				CandidateOccurrenceSnapshot snapshot = new CandidateOccurrenceSnapshot(context,
					forward.readOccurrence(), List.of(), List.of(), List.of(),
					List.of(new DpPlacementAdapter.TransientForwardDependencyEntry(
						forward, forward.writeOccurrence(), 0, selected)),
					List.of(), List.of(), DpPlacementAdapter.ConstructionDisposition.AVAILABLE, "AVAILABLE");
				long variantOrdinal = nextVariantOrdinal(forward.readOccurrence());
				capture(snapshot, variantOrdinal);
				Hop schedulingCarrier = context.analysis().hop(forward.writeOccurrence()).orElseThrow();
				DpPlacementAdapter.NormalizedCandidateInputs normalized =
					new DpPlacementAdapter.NormalizedCandidateInputs(snapshot, Map.of(),
						java.util.Arrays.asList((FType) null), List.of(schedulingCarrier));
				captureDecisionReceipt(DpPlacementAdapter.resolveCandidateDecision(
					context, normalized, variantOrdinal), variantOrdinal);
				captured.add(forward);
			}
		}

		private boolean isSchedulingOnlyTransientForward(RewireTransientForwardEdge forward) {
			boolean compiledValueEdge = context.analysis().compiledInputEdgesInCanonicalOrder().stream()
				.anyMatch(edge -> edge.producer() == forward.writeOccurrence()
					&& edge.consumer() == forward.readOccurrence());
			boolean logicalValueEdge = context.analysis().logicalTransientInputsInCanonicalOrder().stream()
				.anyMatch(fact -> fact.sourceWrite() == forward.writeOccurrence()
					&& fact.targetRead() == forward.readOccurrence());
			return !compiledValueEdge && !logicalValueEdge;
		}

		private PlacementState requireUniqueLocalSchedulingState(CompiledHopKey source) {
			List<PlacementState> states = context.analysis().graph().node(source).orElseThrow(() ->
				new IllegalStateException("Scheduling source is absent from the neutral graph"))
				.legalAlternatives().stream()
				.filter(state -> state.execType() == ExecType.CP
					&& state.output() == FederatedOutput.LOUT && state.fType() == null)
				.toList();
			if(states.size() != 1)
				throw new IllegalStateException("Scheduling source lacks one exact CP/LOUT state: " + source);
			return states.get(0);
		}

		private long nextVariantOrdinal(CompiledHopKey parent) {
			long max = -1L;
			for(CapturedCandidate candidate : candidates)
				if(candidate.snapshot().parentOccurrence() == parent)
					max = Math.max(max, candidate.variantOrdinal());
			return Math.addExact(max, 1L);
		}
	}

	private record CapturedCandidate(CandidateOccurrenceSnapshot snapshot, long variantOrdinal,
		CandidateDecisionReceipt receipt) { }

	private record EffectiveCandidateInputs(List<Hop> collectedHops, List<FType> collectedFTypes,
		Map<Long, FType> fedInputTypeMap) {
		private EffectiveCandidateInputs {
			collectedHops = List.copyOf(collectedHops);
			// LOUT inputs are represented by null FTypes on the legacy dynamic path.
			collectedFTypes = Collections.unmodifiableList(new ArrayList<>(collectedFTypes));
			fedInputTypeMap = Collections.unmodifiableMap(new LinkedHashMap<>(fedInputTypeMap));
		}
	}

	private record ExactInputBoundary(ValueVersionKey sourceValueVersion, int inputPosition) { }

	private record SelectedRelocationSource(FederatedPlannerDpMemoTable.FedPlan plan,
		Hop hop, PlacementState state) { }

	private record ExactRelocationCost(List<RelocationChoiceReceipt> choices,
		Map<RelocationActionKey,Double> actionCosts,
		double legacyBoundaryCost, double selectedBoundaryCost) {
		private ExactRelocationCost {
			choices = List.copyOf(choices);
			actionCosts = Collections.unmodifiableMap(new LinkedHashMap<>(actionCosts));
			if(!Double.isFinite(legacyBoundaryCost) || legacyBoundaryCost < 0.0
				|| !Double.isFinite(selectedBoundaryCost) || selectedBoundaryCost < 0.0)
				throw new IllegalArgumentException("Exact relocation costs must be finite and non-negative");
		}
	}

	private record SharedLogicalInputArm(LogicalFunctionInputFact fact, Hop source,
		FederatedOutput sourceOutput, FederatedPlannerDpMemoTable.FedPlan sourcePlan,
		CandidateDecisionReceipt receipt, double cumulativeShare, double localBoundaryShare) {
		private SharedLogicalInputArm {
			Objects.requireNonNull(fact, "fact");
			Objects.requireNonNull(source, "source");
			Objects.requireNonNull(sourceOutput, "sourceOutput");
			Objects.requireNonNull(sourcePlan, "sourcePlan");
			Objects.requireNonNull(receipt, "receipt");
			if(source.getHopID() != sourcePlan.getHopID() || sourceOutput != sourcePlan.getFedOutType()
				|| !Double.isFinite(cumulativeShare) || cumulativeShare < 0.0
				|| !Double.isFinite(localBoundaryShare) || localBoundaryShare < 0.0)
				throw new IllegalArgumentException("Shared logical function-input arm is inconsistent");
		}

		private double costFor(PlacementState formalState) {
			return cumulativeShare + (formalState.execType() == ExecType.CP ? localBoundaryShare : 0.0);
		}
	}

	public static final class ParentCarrierProjectionException extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		private final DpPlacementAdapter.ConstructionDisposition disposition;
		private final String analysisFingerprint;
		private final long carrierHopId;
		private final String reasonCode;

		private ParentCarrierProjectionException(String analysisFingerprint, long carrierHopId) {
			super("UNMAPPABLE_PARENT_CARRIER: " + carrierHopId);
			disposition = DpPlacementAdapter.ConstructionDisposition.UNMAPPABLE_OCCURRENCE;
			this.analysisFingerprint = Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			this.carrierHopId = carrierHopId;
			reasonCode = "UNMAPPABLE_PARENT_CARRIER";
		}

		public DpPlacementAdapter.ConstructionDisposition disposition() { return disposition; }
		public String analysisFingerprint() { return analysisFingerprint; }
		public long carrierHopId() { return carrierHopId; }
		public String reasonCode() { return reasonCode; }
	}

	// Global privacy policy: never allow CP overrides for protected data unless
	// this flag flips.
	private static final boolean ALLOW_CP_OVERRIDE_ON_PROTECTED_DATA = false;
	// Planner option: disallow CP->FOUT in recompile regions (function/while).
	// This is treated as a global legality constraint for planner/runtime consistency,
	// not a workload-specific pruning heuristic.
	private static final boolean DISALLOW_CPFOUT_ON_RECOMPILE = true;

	public static DpEnumerationResult enumerateProgramWithReceipts(DMLProgram prog,
		FederatedPlannerDpMemoTable memoTable, boolean isPrint, PlacementAnalysis analysis) {
		return enumerateProgramWithReceipts(prog, memoTable, isPrint, analysis, null, NO_OP_OBSERVER);
	}

	public static DpEnumerationResult enumerateProgramWithReceipts(DMLProgram prog,
		FederatedPlannerDpMemoTable memoTable, boolean isPrint, PlacementAnalysis analysis,
		CandidateNormalizationFixture fixture, DpEnumerationObserver observer) {
		Objects.requireNonNull(prog, "prog");
		Objects.requireNonNull(memoTable, "memoTable");
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertProgramOwner(prog);
		DpEnumerationObserver exactObserver = observer == null ? NO_OP_OBSERVER : observer;
		try {
			if(fixture != null)
				DpPlacementAdapter.validateCandidateInputs(analysis, fixture.parentOccurrence(), fixture.planChilds(),
					fixture.collectedHops(), fixture.collectedFTypes(), fixture.fedInputTypeMap(), memoTable);
			return enumerateProgramWithReceiptsInternal(prog, memoTable, isPrint, analysis, exactObserver);
		}
		catch(DpSemanticConstructionException failure) {
			exactObserver.constructionFailed(failure);
			throw failure;
		}
	}

	private static DpEnumerationResult enumerateProgramWithReceiptsInternal(DMLProgram prog,
		FederatedPlannerDpMemoTable memoTable, boolean isPrint, PlacementAnalysis analysis,
		DpEnumerationObserver observer) {
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Map<Long, Set<Long>> parentChildUploadHints = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable = new HashMap<>();
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx = new FederatedPlannerDpRewireTransTable.UnrollContext();

		FederatedPlannerDpRewireTransTable.rewireProgram(analysis, prog, rewireTable, hopCommonTable,
				unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx);
		RewireOccurrenceSnapshot rewireSnapshot = FederatedPlannerDpRewireTransTable.snapshotProductionRewire(
			analysis, prog, rewireTable, hopCommonTable, parentChildUploadHints, progRootHopSet, unrollCtx,
			analysis.analysisFingerprint());
		memoTable.registerHopRefs(rewireSnapshot, hopCommonTable);
		memoTable.registerCloneMapping(rewireSnapshot);
		memoTable.registerDeadFunctionOutputHopIDs(rewireSnapshot, unrollCtx.getDeadFunctionOutputHopIDs());
		memoTable.registerAdditionalRootHopIDs(rewireSnapshot, unrollCtx.getIter1Roots());
		populateParentChildUploadHintsFromRewire(parentChildUploadHints, rewireTable, hopCommonTable);

		int numOfWorkers = Math.max(1, analysis.numWorkers());
		memoTable.setNumWorkers(numOfWorkers);
		NeutralEnumerationContext enumerationContext = DpPlacementAdapter.captureNeutralEnumerationContext(
			analysis, rewireSnapshot, unRefTwriteSet);

		addUnreferencedTWriteRoots(progRootHopSet, unRefTwriteSet, hopCommonTable);
		boolean closeExactFrontier = requiresExactFrontierClosure(analysis);
		if(closeExactFrontier)
			enumerateProgramPass(prog, memoTable, analysis, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, numOfWorkers,
				unrollCtx, new EnumerationCapture(
					enumerationContext, memoTable, NO_OP_OBSERVER, false, true));
		if(closeExactFrontier)
			closeExactMemoFrontier(prog, memoTable, analysis, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, numOfWorkers,
				unrollCtx, enumerationContext);
		if(closeExactFrontier)
			memoTable.assertNoExactFrontierSeeds();
		EnumerationCapture capture = new EnumerationCapture(
			enumerationContext, memoTable, observer, closeExactFrontier);
		String stableFrontier = closeExactFrontier
			? memoTable.exactSemanticFrontierFingerprint() : null;
		enumerateProgramPass(prog, memoTable, analysis, hopCommonTable, rewireTable,
			parentChildUploadHints, unRefTwriteSet, numOfWorkers,
			unrollCtx, capture);
		if(closeExactFrontier) {
			String observedFrontier = memoTable.exactSemanticFrontierFingerprint();
			if(!stableFrontier.equals(observedFrontier))
				throw new IllegalStateException("Final observed DP enumeration changed the exact memo fixed point: "
					+ "before=" + frontierDigest(stableFrontier)
					+ ", after=" + frontierDigest(observedFrontier));
			memoTable.assertNoExactFrontierSeeds();
		}
		memoTable.registerAdditionalRootHopIDs(rewireSnapshot, collectPredicateWriteRoots(hopCommonTable));

		PreSelectionSemanticBlock semanticBlock = capture.semanticBlock();
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

		// NOTE: Do not resolve placement conflicts here by mutating only parent->child
		// edge labels. Edge-only mutation cannot update parent exec types (notably for
		// TRead/TWrite consistency) and can lock the plan into cost-ignorant defaults
		// (e.g., forcing large loop inputs local and triggering repeated uploads at
		// runtime).
		//
		// Conflict resolution is performed in the rewrite stage
		// (FederatedPlannerDpFedCostBased.computeOutputDecisions) where we can
		// select the correct plan variants (exec + placement) under loop-aware costs.
		double additionalTotalCost = 0.0;

		unRefSet.addAll(unRefTwriteSet);
		// Print the federated plan tree if requested
		if (isPrint) {
			Set<Long> printableUnRefSet = new HashSet<>();
			for (long hopId : unRefSet) {
				if (memoTable.getFedPlanAfterPrune(hopId, FederatedOutput.LOUT) != null
						|| memoTable.getFedPlanAfterPrune(hopId, FederatedOutput.FOUT) != null) {
					printableUnRefSet.add(hopId);
				} else {
					FederatedPlannerLogger.logWarnMessage(
							"[Planner] Skipping unreferenced hop " + hopId
									+ " because no federated plan variants are available");
				}
			}
			FederatedPlannerLogger.printFedPlanTree(optimalPlan, printableUnRefSet, memoTable,
					additionalTotalCost);
		}

		DpEnumerationResult result = new DpEnumerationResult(optimalPlan, rewireSnapshot, semanticBlock);
		observer.resultPublished(result);
		return result;
	}

	private static boolean hasSharedLogicalFunctionInputs(PlacementAnalysis analysis) {
		Map<CompiledHopKey,Integer> counts = new IdentityHashMap<>();
		for(LogicalFunctionInputFact fact : analysis.logicalFunctionInputsInCanonicalOrder())
			if(counts.merge(fact.targetRead(), 1, Integer::sum) > 1)
				return true;
		return false;
	}

	private static boolean requiresExactFrontierClosure(PlacementAnalysis analysis) {
		// A logical transient source is enumerated as an executable TWrite -> TRead
		// dependency.  Loop-carried values can simultaneously contain a physical
		// TRead -> ... -> TWrite path, so one depth-first pass observes a mixed-generation
		// frontier.  Shared function formals have the analogous multi-caller join.
		return hasSharedLogicalFunctionInputs(analysis)
			|| !analysis.logicalTransientInputsInCanonicalOrder().isEmpty();
	}

	private static void closeExactMemoFrontier(DMLProgram prog,
		FederatedPlannerDpMemoTable memoTable, PlacementAnalysis analysis,
		Map<Long,FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
		Map<Long,List<Hop>> rewireTable,
		Map<Long,Set<Long>> parentChildUploadHints, Set<Long> unRefTwriteSet,
		int numOfWorkers, FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx,
		NeutralEnumerationContext enumerationContext) {
		String previous = memoTable.exactSemanticFrontierFingerprint();
		for(int pass = 1; pass <= MAX_EXACT_FRONTIER_CLOSURE_PASSES; pass++) {
			EnumerationCapture closure = new EnumerationCapture(
				enumerationContext, memoTable, NO_OP_OBSERVER, true);
			enumerateProgramPass(prog, memoTable, analysis, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, numOfWorkers,
				unrollCtx, closure);
			String current = memoTable.exactSemanticFrontierFingerprint();
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("DP-FrontierClosure", "pass=" + pass
					+ " previous=" + frontierDigest(previous)
					+ " current=" + frontierDigest(current)
					+ " stable=" + previous.equals(current));
			if(previous.equals(current))
				return;
			previous = current;
		}
		// This bound is an assertion against a broken/non-contractive recurrence, not
		// a fallback or a candidate-space reduction.  Publishing the last asynchronous
		// pass would violate exact TRead/TWrite authority, so fail before selection.
		throw new IllegalStateException("Exact DP memo frontier did not converge after "
			+ MAX_EXACT_FRONTIER_CLOSURE_PASSES + " closure passes: "
			+ frontierDigest(previous));
	}

	private static String frontierDigest(String fingerprint) {
		return "chars=" + fingerprint.length() + ",hash="
			+ Integer.toUnsignedString(fingerprint.hashCode(), 16);
	}

	private static void enumerateProgramPass(DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
		PlacementAnalysis analysis, Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
		Map<Long,List<Hop>> rewireTable,
		Map<Long,Set<Long>> parentChildUploadHints, Set<Long> unRefTwriteSet, int numOfWorkers,
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx, EnumerationCapture capture) {
		Set<String> fnStack = new HashSet<>();
		Set<Hop> visitedHops = Collections.newSetFromMap(new IdentityHashMap<>());
		for(StatementBlock sb : analysis.topLevelStatementBlocks())
			enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		for(Hop iter1Root : unrollCtx.getIter1Roots()) {
			if(iter1Root == null)
				continue;
			enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		}
	}

	public static DpEnumerationResult enumerateFunctionDynamicWithReceipts(FunctionStatementBlock function,
			FederatedPlannerDpMemoTable memoTable,
			boolean isPrint, PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		Map<Long, List<Hop>> rewireTable = new HashMap<>();
		Map<Long, Set<Long>> parentChildUploadHints = new HashMap<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable = new HashMap<>();
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx = new FederatedPlannerDpRewireTransTable.UnrollContext();

		DMLProgram prog = function.getDMLProg();
			FederatedPlannerDpRewireTransTable.rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable,
					unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx);
				int numOfWorkers = Math.max(1, analysis.numWorkers());
				memoTable.setNumWorkers(numOfWorkers);
				analysis.assertProgramOwner(prog);
				Set<Long> activeScopeIds = FederatedPlannerDpRewireTransTable.collectExecutableScopeIds(function);
				String dynamicScopeKey = analysis.analysisFingerprint() + "|dynamic|"
					+ activeScopeIds.stream().sorted().map(String::valueOf)
						.collect(java.util.stream.Collectors.joining(","));
				RewireOccurrenceSnapshot rewireSnapshot = FederatedPlannerDpRewireTransTable.snapshotProductionRewire(
					analysis, prog, rewireTable, hopCommonTable, parentChildUploadHints, progRootHopSet, unrollCtx,
					activeScopeIds, dynamicScopeKey);
			memoTable.registerHopRefs(rewireSnapshot, hopCommonTable);
			memoTable.registerCloneMapping(rewireSnapshot);
			memoTable.registerDeadFunctionOutputHopIDs(rewireSnapshot, unrollCtx.getDeadFunctionOutputHopIDs());
			memoTable.registerAdditionalRootHopIDs(rewireSnapshot, unrollCtx.getIter1Roots());
			memoTable.registerAdditionalRootHopIDs(rewireSnapshot, unrollCtx.getAdditionalRoots());
			memoTable.registerAdditionalRootHopIDs(rewireSnapshot,
				FederatedPlannerDpRewireTransTable.collectExecutableStatementRoots(function));
			populateParentChildUploadHintsFromRewire(parentChildUploadHints, rewireTable, hopCommonTable);
			addUnreferencedTWriteRoots(progRootHopSet, unRefTwriteSet, hopCommonTable);
			memoTable.registerAdditionalRootHopIDs(rewireSnapshot,
				collectUnreferencedExecutedRoots(unRefSet, hopCommonTable));

		Set<String> fnStack = new HashSet<>();
		Set<Hop> visitedHops = Collections.newSetFromMap(new IdentityHashMap<>());
		NeutralEnumerationContext capturedContext = DpPlacementAdapter.captureNeutralEnumerationContext(
			analysis, rewireSnapshot, unRefTwriteSet);
		EnumerationCapture capture = new EnumerationCapture(
			new NeutralEnumerationContext(capturedContext.analysis(), capturedContext.rewireSnapshot(),
				capturedContext.analysisFingerprint(), capturedContext.numWorkers(),
				capturedContext.invocationEvidence(), capturedContext.privacy()), memoTable, NO_OP_OBSERVER);
		enumerateStatementBlock(function, prog, memoTable, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		for (Hop iter1Root : unrollCtx.getIter1Roots()) {
			if (iter1Root == null)
				continue;
			enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		}
		memoTable.registerAdditionalRootHopIDs(rewireSnapshot, collectPredicateWriteRoots(hopCommonTable));
		PreSelectionSemanticBlock semanticBlock = capture.semanticBlock();

		FederatedPlannerDpMemoTable.FedPlan optimalPlan = getMinCostRootFedPlan(progRootHopSet, memoTable);

		// See enumerateProgram: conflict resolution is handled in the rewrite stage,
		// not by edge-only mutation here.
		double additionalTotalCost = 0.0;

		// Print the federated plan tree if requested
		if (isPrint) {
			FederatedPlannerLogger.printFedPlanTree(optimalPlan, unRefTwriteSet, memoTable, additionalTotalCost);
		}

		return new DpEnumerationResult(optimalPlan, rewireSnapshot, semanticBlock);
	}

	/**
	 * Enumerates the statement block and updates the transient and memoization
	 * tables.
	 * This method processes different types of statement blocks such as If, For,
	 * While, and Function blocks.
	 * It recursively enumerates the Hop DAGs within these blocks and updates the
	 * corresponding tables.
	 * The method also calculates weights recursively for if-else/loops and handles
	 * inner and outer block distinctions.
	 */
	public static void enumerateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet, Set<String> fnStack,
			int numOfWorkers, Set<Hop> visitedHops) {
		enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable,
			parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, null);
	}

	private static void enumerateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet, Set<String> fnStack,
			int numOfWorkers, Set<Hop> visitedHops, EnumerationCapture capture) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			enumerateHopDAG(isb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);

			for (StatementBlock innerIsb : istmt.getIfBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);

			for (StatementBlock innerIsb : istmt.getElseBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			enumerateHopDAG(fsb.getFromHops(), prog, memoTable, hopCommonTable, rewireTable,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			enumerateHopDAG(fsb.getToHops(), prog, memoTable, hopCommonTable, rewireTable,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			if (fsb.getIncrementHops() != null) {
				enumerateHopDAG(fsb.getIncrementHops(), prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			}

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			enumerateHopDAG(wsb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);

			for (StatementBlock innerWsb : wstmt.getBody())
				enumerateStatementBlock(innerWsb, prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					enumerateHopDAG(c, prog, memoTable, hopCommonTable, rewireTable,
							parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			}
		}
	}

	/**
	 * Rewires and enumerates federated execution plans for a given Hop.
	 * This method processes all input nodes, rewires TWrite and TRead operations,
	 * and generates federated plan variants for both inner and outer code blocks.
	 */
	private static void enumerateHopDAG(Hop hop, DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet,
			Set<String> fnStack, int numOfWorkers, Set<Hop> visitedHops, EnumerationCapture capture) {
		// Process all input nodes first if not already in memo table
		if(capture.sharedFunctionInputClosure && !visitedHops.add(hop))
			return;

		List<Hop> childHops = new ArrayList<>(hop.getInput());
		Set<Hop> physicalChildren = Collections.newSetFromMap(new IdentityHashMap<>());
		physicalChildren.addAll(childHops);
		FunctionOp dmlFunction = hop instanceof FunctionOp
			&& ((FunctionOp) hop).getFunctionType() == FunctionType.DML ? (FunctionOp) hop : null;
		boolean enumerateFunctionBody = false;
		if(capture.sharedFunctionInputClosure && dmlFunction != null
			&& !fnStack.contains(dmlFunction.getFunctionKey())) {
			fnStack.add(dmlFunction.getFunctionKey());
			enumerateFunctionBody = true;
			Set<Hop> retained = Collections.newSetFromMap(new IdentityHashMap<>());
			retained.addAll(childHops);
			for(Hop prerequisite : collectLogicalFunctionArgumentPrerequisites(dmlFunction, capture))
				if(retained.add(prerequisite))
					childHops.add(prerequisite);
		}

		// Todo: Check if is right
		if ((hop instanceof DataOp) && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			List<Hop> exactLogicalSources = collectLogicalTransientSourceChildHops((DataOp) hop, capture);
			if(capture.seedExactTransientFrontier && exactLogicalSources.size() > 1) {
				List<Hop> completeSources = exactLogicalSources;
				List<Hop> availableSources = exactLogicalSources.stream().filter(source ->
					memoTable.getFedPlanAfterPruneForOccurrence(source, FederatedOutput.LOUT) != null
						|| memoTable.getFedPlanAfterPruneForOccurrence(source, FederatedOutput.FOUT) != null)
					.toList();
				FederatedPlannerTrace.log(hop, "DP-Transient-SeedSources",
					"available=" + availableSources.stream().map(Hop::getHopID).toList()
						+ " deferred=" + completeSources.stream()
							.filter(source -> !availableSources.contains(source))
							.map(Hop::getHopID).toList());
				exactLogicalSources = availableSources;
			}
			if(!exactLogicalSources.isEmpty())
				childHops.addAll(exactLogicalSources);
			else {
				List<Hop> transChildHops = rewireTable.get(hop.getHopID());
				if (transChildHops != null)
					childHops.addAll(transChildHops);
			}
		}

		for (Hop inputHop : childHops) {
			if(capture.sharedFunctionInputClosure) {
				if(physicalChildren.contains(inputHop)
					|| !memoTable.containsPlanForCarrier(inputHop, FederatedOutput.FOUT)
						&& !memoTable.containsPlanForCarrier(inputHop, FederatedOutput.LOUT))
					enumerateHopDAG(inputHop, prog, memoTable, hopCommonTable, rewireTable,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
				continue;
			}
			if (!memoTable.containsPlanForCarrier(inputHop, FederatedOutput.FOUT)
					&& !memoTable.containsPlanForCarrier(inputHop, FederatedOutput.LOUT)) {
				if (!visitedHops.contains(inputHop)) {
					visitedHops.add(inputHop);
					enumerateHopDAG(inputHop, prog, memoTable, hopCommonTable, rewireTable,
							parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
				}
			}
		}

		if (hop instanceof FunctionOp) {
			// maintain counters and investigate functions if not seen so far
			FunctionOp fop = (FunctionOp) hop;
			if (fop.getFunctionType() == FunctionType.DML) {
				String fkey = fop.getFunctionKey();

				if (!capture.sharedFunctionInputClosure && !fnStack.contains(fkey)) {
					fnStack.add(fkey);
					enumerateFunctionBody = true;
				}
				if (enumerateFunctionBody) {
					if (prog == null) {
						FederatedPlannerLogger.logWarnMessage(
								"[FederatedCost] Skipping nested function " + fkey
										+ " because DMLProgram is unavailable in dynamic planning");
					} else {
						FunctionStatementBlock fsb = prog.getFunctionStatementBlock(fop.getFunctionNamespace(),
								fop.getFunctionName());

						if (fsb == null) {
							FederatedPlannerLogger.logWarnMessage(
									"[FederatedCost] Function " + fkey
											+ " not found in DMLProgram; skipping nested planning");
						} else {
							enumerateStatementBlock(fsb, prog, memoTable, hopCommonTable, rewireTable, parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers,
									visitedHops, capture);
						}
					}
				}
			}
		}

		// Enumerate the federated plan for the current Hop
		if(capture.sharedFunctionInputClosure)
			memoTable.removeFedPlanVariantsForCarrier(hop);
		enumerateHop(hop, memoTable, hopCommonTable, rewireTable,
				parentChildUploadHints, unRefTwriteSet, numOfWorkers, capture);

		// FederatedPlannerDpRewireTransTable.logHopInfo(hop,
		// "enumerateHopDAG");

	}

	private static List<Hop> collectLogicalFunctionArgumentPrerequisites(FunctionOp function,
		EnumerationCapture capture) {
		String functionKey = function.getFunctionKey();
		Set<CompiledHopKey> retained = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Hop> prerequisites = new ArrayList<>();
		for(LogicalFunctionInputFact fact : capture.context.analysis()
			.logicalFunctionInputsInCanonicalOrder()) {
			if(!functionKey.equals(fact.targetRead().functionNamespace())
				|| !retained.add(fact.sourceArgument()))
				continue;
			Hop source = capture.context.analysis().hop(fact.sourceArgument()).orElseThrow(() ->
				new IllegalArgumentException("Function-input DP prerequisite Hop is missing"));
			HopOccurrenceProjection projected = capture.context.rewireSnapshot().projectExactCarrier(source);
			if(projected == null || projected.key() != fact.sourceArgument())
				throw new IllegalArgumentException(
					"Function-input DP prerequisite carrier differs from analysis authority");
			if(source == function)
				throw new IllegalArgumentException("Function-input DP prerequisite is self-recursive: "
					+ functionKey);
			prerequisites.add(source);
		}
		return List.copyOf(prerequisites);
	}

	/**
	 * Enumerates federated execution plans for a given Hop.
	 * This method calculates the self cost and child costs for the Hop,
	 * generates federated plan variants for both LOUT and FOUT output types,
	 * and prunes redundant plans before adding them to the memo table.
	 */
	private static void enumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable,
			Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet, int numOfWorkers, EnumerationCapture capture) {
		long hopID = hop.getHopID();
		List<Hop> childHops = new ArrayList<>(hop.getInput());
		int numParentHops = hop.getParent().size();
		if (hop instanceof FunctionOp
				&& ((FunctionOp) hop).getFunctionType() == FunctionType.DML) {
			List<Hop> functionOutputHops = rewireTable.get(hopID);
			if (functionOutputHops != null) {
				LinkedHashSet<Long> seenChildIds = new LinkedHashSet<>();
				for (Hop inputHop : childHops)
					seenChildIds.add(inputHop.getHopID());
				for (Hop outputHop : functionOutputHops) {
					if (outputHop == null)
						continue;
					long outputHopId = outputHop.getHopID();
					if (memoTable.getFedPlanAfterPruneForOccurrence(outputHop, FederatedOutput.LOUT) == null
							&& memoTable.getFedPlanAfterPruneForOccurrence(outputHop, FederatedOutput.FOUT) == null)
						continue;
					if (seenChildIds.add(outputHopId))
						childHops.add(outputHop);
				}
			}
		}

		if (hop instanceof DataOp) {
			Types.OpOpData opType = ((DataOp) hop).getOp();
			if (opType == Types.OpOpData.TRANSIENTWRITE && !hop.getName().equals("__pred")) {
				List<Hop> transParentHops = rewireTable.get(hop.getHopID());
				if (transParentHops != null) {
					numParentHops += transParentHops.size();
				}
			} else if (opType == Types.OpOpData.TRANSIENTREAD) {
				Set<Hop> retained = Collections.newSetFromMap(new IdentityHashMap<>());
				retained.addAll(childHops);
				for(Hop logicalSource : collectLogicalTransientSourceChildHops((DataOp) hop, capture))
					if(retained.add(logicalSource))
						childHops.add(logicalSource);
				List<Hop> transChildHops = rewireTable.get(hop.getHopID());
				if (transChildHops != null) {
					for (Hop transChildHop : transChildHops) {
						if (transChildHop instanceof DataOp
								&& ((DataOp) transChildHop).getOp() == Types.OpOpData.TRANSIENTREAD
								&& hop.getName().equals(transChildHop.getName())) {
							continue;
						}
						if(isTransientForwardCandidateCarrier(hop, transChildHop, capture)
							&& childHops.stream().noneMatch(input -> input == transChildHop))
							childHops.add(transChildHop);
					}
				}
			}
		} else {
			for (Hop parentHop : hop.getParent()) {
				if (parentHop instanceof DataOp
						&& unRefTwriteSet.contains(parentHop.getHopID())) {
					numParentHops--;
				}
			}
		}
		numParentHops = adjustNumParentHopsForFunctionOutputBoundary(hop, numParentHops);

		FederatedPlannerDpMemoTable.HopCommon hopCommon = hopCommonTable.get(hopID);
		hopCommon.setNumOfParentHops(numParentHops);
		HopOccurrenceProjection hopOccurrence = findOccurrence(capture, hop);
		FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator =
			FederatedPlannerDpCostEstimator.bindExact(
				capture.context.analysis(), hopOccurrence, memoTable);
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
			if(capture != null)
				captureMemoSupportedChildSelections(hop, childHops, memoTable, capture);
			enumerateFederatedDataOp((DataOp) hop, memoTable, hopCommon, capture);
			return;
		}
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			if (enumerateTransientReadDataOp((DataOp) hop, childHops, memoTable, hopCommon, numOfWorkers, capture)) {
				return;
			}
			// The recursive DAG walk above already honored legacy rewire forwards as
			// enumeration-order dependencies. If no exact transient input fact exists,
			// do not let those scheduling-only TWrites fall through as ordinary executable
			// inputs of the generic CP-only TRead arm.
			childHops.removeIf(child -> child instanceof DataOp
				&& ((DataOp) child).getOp() == Types.OpOpData.TRANSIENTWRITE
				&& !hasExactTransientInputAuthority(hop, child, capture));
		}

		Set<Long> tWriteChildIds = collectHopIds(collectTransientWriteChildHops(hop, childHops, capture));
		final boolean enforceTReadConsistency = !tWriteChildIds.isEmpty();
		final boolean isTransientReadHop = hop instanceof DataOp
				&& ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD;
		final boolean hasConcreteTransientReadSource = !isTransientReadHop
				|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(
						(DataOp) hop, childHops);
		final Hop explicitFunctionOutputSourceHop = isTransientReadHop
				? FunctionOp.getPreferredMultiReturnFunctionOutputSourceForTransientRead(
						(DataOp) hop, childHops)
				: null;
		if (explicitFunctionOutputSourceHop != null) {
			FederatedPlannerUtils.propagateMultiReturnFunctionOutputStatsToTransientRead(
					(DataOp) hop, explicitFunctionOutputSourceHop);
		}

		double baseSelfCost = exactEstimator.computeHopCost(hopCommon);

		int initialNumInputs = childHops.size();
		// Preallocate for the original child count; getChildCosts may shrink childHops,
		// so only the first numInputs entries remain valid afterwards.
		double[][] childCumulativeCost = new double[initialNumInputs][2]; // # of child, LOUT/FOUT of child
		double[] childForwardingCostToCP = new double[initialNumInputs]; // # of child (FOUT -> CP)
		double[] childForwardingCostToFED = new double[initialNumInputs]; // # of child (LOUT -> FED)
		double[] childForwardingCostFOutToFED = new double[initialNumInputs]; // # of child (transient FOUT -> FED)
		List<Hop> lOutfOutChildHops = new ArrayList<>(childHops);

		List<Hop> lOUTOnlyinputHops = new ArrayList<>();
		List<Double> lOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> lOUTOnlychildForwardingCostToFED = new ArrayList<>();

		List<Hop> fOUTOnlyinputHops = new ArrayList<>();
		List<Double> fOUTOnlychildCumulativeCost = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToCP = new ArrayList<>();
		List<Double> fOUTOnlychildForwardingCostToFED = new ArrayList<>();

		// The self cost follows the producer weight. Boundary forwarding is shared by
		// producer occurrence and parent demand weights.
		exactEstimator.getChildCosts(hopCommon, hopCommonTable,
				lOutfOutChildHops, childCumulativeCost, childForwardingCostToCP,
				childForwardingCostToFED, childForwardingCostFOutToFED, lOUTOnlyinputHops,
				lOUTOnlychildCumulativeCost, lOUTOnlychildForwardingCostToFED, fOUTOnlyinputHops,
				fOUTOnlychildCumulativeCost, fOUTOnlychildForwardingCostToCP,
				fOUTOnlychildForwardingCostToFED, numOfWorkers);

		// childCumulativeCost/childForwardingCost arrays are treated as buffers sized
		// by
		// initialNumInputs;
		// only indices [0, numInputs) are populated after getChildCosts mutates
		// childHops.
		int numBothOutInputs = lOutfOutChildHops.size();
		int numLoutOnlyInputs = lOUTOnlyinputHops.size();
		int numFoutOnlyInputs = fOUTOnlyinputHops.size();
		// Boundary transfer accounting is based on selected execution/output combinations:
		// FED parent + LOUT child always incurs LOUT->FED upload (shared by parent count/weight).
		// CP parent + FOUT child always incurs FOUT->CP download.
		final Privacy privacyConstraint = capture.context.analysis().requirePrivacy(hopOccurrence.key());

		double hopNetworkWeight = hopCommon.getNetworkWeight();
		// Hop-local placement conversion (FED<->local result materialization) follows
		// the hop's own execution frequency, independent of parent-child forwarding.
		double hopPlacementWeight = placementTransferWeight(hopCommon);
		double outputMemEstimate = (explicitFunctionOutputSourceHop != null)
				? FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(hop, explicitFunctionOutputSourceHop)
				: FederatedCostModel.getEffectiveOutputMemEstimate(hop);
		double uploadMemEstimate = (explicitFunctionOutputSourceHop != null)
				? Math.max(outputMemEstimate,
						FederatedCostModel.getEffectiveOutputMemEstimate(explicitFunctionOutputSourceHop))
				: FederatedCostModel.getEffectiveUploadMemEstimate(hop);
		double cpSelfCost = baseSelfCost;
		// Align with Exact: FED execution has a per-op coordination overhead that should
		// be modeled even when compute cost scales down with workers.
		//
		// IMPORTANT: In DP we represent unrolled-loop iteration counts via
		// HopCommon.multiplicity (see FederatedPlannerDpRewireTransTable). This overhead
		// must therefore scale with multiplicity as well.
		//
		// DP/Exact parity: use the shared control-only helper. The helper already applies
		// worker fanout semantics, so do not multiply by numWorkers again here.
		double fedExecWeight = hopNetworkWeight * hopCommon.getMultiplicity();
		double fedOverhead = (hop instanceof DataOp)
				? 0.0
				: fedExecWeight * FederatedCostModel.computeFedCoordinationCost(numOfWorkers);
		double singleWorkerFedPenalty = FederatedCostModel.computeSingleWorkerFedExecPenalty(
				hop, fedExecWeight, numOfWorkers);
			final long enumerationLimit = 1L << numBothOutInputs;

			FederatedPlannerDpMemoTable.FedPlanVariants lOutFedPlanVariants = new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon,
					FederatedOutput.FOUT);

			boolean sawOracleFedFout = false;
			boolean sawAllowFedFout = false;
			boolean sawCanSatisfyFedInputs = false;
			boolean sawAllowCpLout = false;
			boolean sawAllowCpFout = false;
			boolean sawAllowFedLout = false;

					long nextCandidateVariantOrdinal = 0;
					for (long i = 0; i < enumerationLimit; i++) {
					List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();
					List<FType> collectedFTypes = new ArrayList<>();
					List<Hop> collectedHops = new ArrayList<>();
				Map<Long, FType> fedInputTypeMap = new HashMap<>();
				int[] selectedBits = new int[numBothOutInputs];
				ExecType tWriteExec = null;
				FederatedOutput tWriteOut = null;
				boolean tWriteConflict = false;
				boolean tWriteSeen = false;
					for (int j = 0; j < numBothOutInputs; j++) {
					Hop inputHop = lOutfOutChildHops.get(j);
					final int bit = (i & (1L << j)) != 0 ? 1 : 0;
					selectedBits[j] = bit;
					final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
					FederatedPlannerDpMemoTable.FedPlan childPlan =
						memoTable.getFedPlanAfterPruneForOccurrence(inputHop, childType);
	
					if (childPlan == null) {
						throw new DMLRuntimeException("Missing " + childType + " federated plan for child hop "
								+ inputHop.getHopID() + " (" + inputHop.getOpString()
								+ ") while enumerating parent " + hopID + " (" + hop.getOpString()
								+ "), privacy=" + privacyConstraint);
					}

					Hop selectedChildCarrier = childPlan.getHopRef();
					planChilds.add(Pair.of(selectedChildCarrier.getHopID(), childType));
					collectedFTypes.add(childType == FederatedOutput.LOUT ? null : childPlan.getFType());
					collectedHops.add(selectedChildCarrier);
					if (childType == FederatedOutput.FOUT)
						fedInputTypeMap.put(selectedChildCarrier.getHopID(), childPlan.getFType());
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=%s exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
						childType, childPlan.getExecType(), childPlan.getFType()));
				if (enforceTReadConsistency && tWriteChildIds.contains(inputHop.getHopID())) {
					tWriteSeen = true;
					if (tWriteExec == null) {
						tWriteExec = childPlan.getExecType();
						tWriteOut = childType;
					} else if (tWriteExec != childPlan.getExecType() || tWriteOut != childType) {
						tWriteConflict = true;
					}
				}
				}

			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				FederatedPlannerDpMemoTable.FedPlan childPlan =
					memoTable.getFedPlanAfterPruneForOccurrence(inputHop, FederatedOutput.LOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + inputHop.getHopID()
							+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
							+ hop.getOpString() + "), privacy=" + privacyConstraint);
					}
					Hop selectedChildCarrier = childPlan.getHopRef();
					planChilds.add(Pair.of(selectedChildCarrier.getHopID(), FederatedOutput.LOUT));
					collectedFTypes.add(null);
					collectedHops.add(selectedChildCarrier);
					FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
							"[Planner] parent=%d (%s) child=%d (%s) type=LOUT exec=%s fType=%s",
							hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
						childPlan.getExecType(), childPlan.getFType()));
				if (enforceTReadConsistency && tWriteChildIds.contains(inputHop.getHopID())) {
					tWriteSeen = true;
					if (tWriteExec == null) {
						tWriteExec = childPlan.getExecType();
						tWriteOut = FederatedOutput.LOUT;
					} else if (tWriteExec != childPlan.getExecType() || tWriteOut != FederatedOutput.LOUT) {
						tWriteConflict = true;
					}
				}
				}

				for (int j = 0; j < numFoutOnlyInputs; j++) {
				Hop inputHop = fOUTOnlyinputHops.get(j);
				FederatedPlannerDpMemoTable.FedPlan childPlan =
					memoTable.getFedPlanAfterPruneForOccurrence(inputHop, FederatedOutput.FOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + inputHop.getHopID()
							+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
							+ hop.getOpString() + "), privacy=" + privacyConstraint);
				}
				Hop selectedChildCarrier = childPlan.getHopRef();
				planChilds.add(Pair.of(selectedChildCarrier.getHopID(), FederatedOutput.FOUT));
				collectedFTypes.add(childPlan.getFType());
				collectedHops.add(selectedChildCarrier);
				fedInputTypeMap.put(selectedChildCarrier.getHopID(), childPlan.getFType());
				FederatedPlannerLogger.logInfoMessage(String.format(Locale.ROOT,
						"[Planner] parent=%d (%s) child=%d (%s) type=FOUT exec=%s fType=%s",
						hopID, hop.getOpString(), inputHop.getHopID(), inputHop.getOpString(),
						childPlan.getExecType(), childPlan.getFType()));
				if (enforceTReadConsistency && tWriteChildIds.contains(inputHop.getHopID())) {
					tWriteSeen = true;
					if (tWriteExec == null) {
						tWriteExec = childPlan.getExecType();
						tWriteOut = FederatedOutput.FOUT;
					} else if (tWriteExec != childPlan.getExecType() || tWriteOut != FederatedOutput.FOUT) {
						tWriteConflict = true;
					}
					}
				}

					List<NormalizedCandidateInputs> normalizedCandidateAlternatives =
						DpPlacementAdapter.normalizeCandidateInputAlternatives(
							capture.context, findOccurrence(capture, hop),
							planChilds, collectedHops, collectedFTypes, fedInputTypeMap, memoTable);
					for(int candidateInputVariant = 0;
						candidateInputVariant < normalizedCandidateAlternatives.size(); candidateInputVariant++) {
						final boolean literalCandidateInputs = candidateInputVariant == 0;
						final long exactVariantOrdinal = nextCandidateVariantOrdinal++;
						double childCostCPExec = 0;
						double childCostFEDExec = 0;
						double childBoundaryCPExec = 0;
						double childBoundaryFEDExec = 0;
						NormalizedCandidateInputs normalizedCandidateInputs =
							normalizedCandidateAlternatives.get(candidateInputVariant);
					capture.capture(normalizedCandidateInputs.snapshot(), exactVariantOrdinal);
					DpPlacementAdapter.CandidateDecisionReceipt candidateDecisionReceipt =
						DpPlacementAdapter.resolveCandidateDecision(capture.context, normalizedCandidateInputs,
							exactVariantOrdinal);
					capture.captureDecisionReceipt(candidateDecisionReceipt, exactVariantOrdinal);
					Privacy capturedPrivacy = candidateDecisionReceipt.privacy();
					if(capturedPrivacy != privacyConstraint)
						throw new IllegalStateException("Candidate receipt privacy differs from captured enumeration privacy");
					capture.observer.oracleEvaluated();
					EffectiveCandidateInputs effectiveInputs = new EffectiveCandidateInputs(
							normalizedCandidateInputs.exactCollectedHops(),
							normalizedCandidateInputs.effectiveCollectedFTypes(),
							normalizedCandidateInputs.effectiveNonNullFTypeMap());
				List<Hop> exactCollectedHops = effectiveInputs.collectedHops();
				List<FType> effectiveCollectedFTypes = effectiveInputs.collectedFTypes();
				Map<Long, FType> effectiveNonNullFTypeMap = effectiveInputs.fedInputTypeMap();
				boolean broadcastOnlyFedCompute = FederatedCostModel.hasOnlyBroadcastMatrixInputs(
					exactCollectedHops, effectiveCollectedFTypes);
				double defaultFedComputeCost = exactEstimator.computeFederatedHopCost(
					hop, baseSelfCost, numOfWorkers, broadcastOnlyFedCompute);

			if (enforceTReadConsistency) {
				if (!tWriteSeen) {
					continue;
				}
				if (tWriteConflict) {
					continue;
				}
			}
			final boolean hasTWriteRequirement = enforceTReadConsistency;
			for (int j = 0; j < numBothOutInputs; j++) {
				Hop inputHop = lOutfOutChildHops.get(j);
				int bit = selectedBits[j];
				childCostCPExec += childCumulativeCost[j][bit] + childForwardingCostToCP[j] * bit;
				double fedForwardingCost = childForwardingCostToFED[j] * (1 - bit)
						+ childForwardingCostFOutToFED[j] * bit;
				childCostFEDExec += childCumulativeCost[j][bit] + fedForwardingCost;
				childBoundaryCPExec += childForwardingCostToCP[j] * bit;
				childBoundaryFEDExec += fedForwardingCost;
			}
			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				childCostCPExec += lOUTOnlychildCumulativeCost.get(j);
				double fedForwardingCost = lOUTOnlychildForwardingCostToFED.get(j);
				childCostFEDExec += lOUTOnlychildCumulativeCost.get(j) + fedForwardingCost;
				childBoundaryFEDExec += fedForwardingCost;
			}
			for (int j = 0; j < numFoutOnlyInputs; j++) {
				childCostCPExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCostToCP.get(j);
				childCostFEDExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCostToFED.get(j);
				childBoundaryCPExec += fOUTOnlychildForwardingCostToCP.get(j);
				childBoundaryFEDExec += fOUTOnlychildForwardingCostToFED.get(j);
			}
			Map<ExactInputBoundary,Double> legacyFedBoundaryCosts = exactLegacyFedBoundaryCosts(
				capture, hop, lOutfOutChildHops, selectedBits, childForwardingCostToFED,
				childForwardingCostFOutToFED, lOUTOnlyinputHops, lOUTOnlychildForwardingCostToFED,
				fOUTOnlyinputHops, fOUTOnlychildForwardingCostToFED);

					boolean canSatisfyFedInputs = canSatisfyFederatedInputsFromFTypes(
						candidateDecisionReceipt, effectiveNonNullFTypeMap);

					FType oracleLogicalFType = candidateDecisionReceipt.logicalFType();
					FType lOutLogicalFType = resolveLoutLogicalFType(oracleLogicalFType);
					FType cpLogicalFType = org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver
						.projectConsumerSafeType(oracleLogicalFType,
							candidateDecisionReceipt.invocationEvidence().projection());
						double genericResultDownloadCost = exactEstimator.download(
								outputMemEstimate, lOutLogicalFType, numOfWorkers);
						double nativeAggUnaryResultDownloadCost =
								FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
										hop, oracleLogicalFType, outputMemEstimate, numOfWorkers,
										genericResultDownloadCost);
					double nativeResultDownloadCost =
							FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
									hop, oracleLogicalFType, outputMemEstimate, numOfWorkers,
									nativeAggUnaryResultDownloadCost);
					nativeResultDownloadCost = exactEstimator.nativeFederatedLoutResultCost(
						hop, outputMemEstimate, numOfWorkers, nativeResultDownloadCost);
						FederatedCostModel.MixedFedLocalCost mixedFedLocalCost =
								FederatedCostModel.computeMixedFedLocalCost(
										hop, exactCollectedHops, effectiveCollectedFTypes, oracleLogicalFType,
										baseSelfCost, outputMemEstimate, numOfWorkers);
						double nativeAggUnaryFedComputeCost =
								FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
										hop, oracleLogicalFType, defaultFedComputeCost);
						nativeAggUnaryFedComputeCost =
								FederatedCostModel.computeNativeFederatedIndexingCost(
										hop, oracleLogicalFType, nativeAggUnaryFedComputeCost);
						double mixedCoordinatorPhaseCost = mixedFedLocalCost.getCoordinatorPhaseCost();
						double fedComputeCost =
								mixedFedLocalCost.hasFederatedComputeFloor()
										? Math.max(nativeAggUnaryFedComputeCost, mixedFedLocalCost.getFederatedComputeFloor())
										: nativeAggUnaryFedComputeCost;
						double resultDownloadCost = hopPlacementWeight
								* (mixedFedLocalCost.hasCoordinatorPhase()
										? mixedCoordinatorPhaseCost
										: nativeResultDownloadCost);
					double inputPreparationCost = hopPlacementWeight
							* mixedFedLocalCost.getInputPreparationCost();
				double effectiveFedOverhead = FederatedCostModel.adjustFedCoordinationCost(
						hop, oracleLogicalFType, fedOverhead);
				double fedInstructionLatencyCost = FederatedCostModel.computeControlDominatedFederatedInstructionCost(
						hop, oracleLogicalFType, fedExecWeight, numOfWorkers, broadcastOnlyFedCompute);
				double fedSelfCost = fedComputeCost + effectiveFedOverhead + singleWorkerFedPenalty
						+ fedInstructionLatencyCost + inputPreparationCost;
				double cpUploadMemEstimate = uploadMemEstimate;
				if(Double.isNaN(cpUploadMemEstimate)) {
					double inputMemEstimate = FederatedCostModel.getEffectiveInputMemEstimate(hop);
					if(Double.isFinite(inputMemEstimate) && inputMemEstimate > 0)
						cpUploadMemEstimate = inputMemEstimate;
				}
				double cpUploadCostWithoutWeight = exactEstimator.upload(
						cpUploadMemEstimate, cpLogicalFType, numOfWorkers);
				// NOTE: Do not add local-to-fed forwarding penalty here.
				//
				// The CP/FOUT candidate already pays a full multi-worker payload upload
				// (computeUploadNetworkCost multiplies by fan-out for BROADCAST/FULL).
				// The additional forwarding penalty is intended to model repeated LOUT->FED
				// forwarding (refed) at parent boundaries. Applying it here can make DP
				// systematically under-prefer CP/FOUT even when it avoids large WAN refed
				// costs and enables faster federated downstream execution (e.g., kmeans WAN).
					double cpUploadCost = hopPlacementWeight * cpUploadCostWithoutWeight;

					boolean derivedFedFout = candidateDecisionReceipt.allowFEDFOUT()
						&& candidateDecisionReceipt.capabilityFact().nativeOutput() != FederatedOutput.FOUT;
					if (isTransientReadHop && !hasConcreteTransientReadSource) {
						canSatisfyFedInputs = false;
					}
				// DP must enumerate every runtime-supported alternative admitted by the shared
				// placement analysis. Exact consumes the same feasible domain but may reject an
				// instance when categorical factor limits are exceeded; that tractability policy
				// must never shrink DP's candidate space.
					if (candidateDecisionReceipt.capabilityFact().nativeExec() == ExecType.FED
						&& candidateDecisionReceipt.capabilityFact().nativeOutput() == FederatedOutput.FOUT) {
						sawOracleFedFout = true;
					}
					if(literalCandidateInputs) {
						sawAllowCpLout |= candidateDecisionReceipt.allowCPLOUT();
						sawAllowCpFout |= candidateDecisionReceipt.allowCPFOUT();
					}
					sawAllowFedLout |= candidateDecisionReceipt.allowFEDLOUT();
					sawAllowFedFout |= candidateDecisionReceipt.allowFEDFOUT();
				sawCanSatisfyFedInputs |= canSatisfyFedInputs;

				// CP-local materialization of a concrete federated TRANSIENTREAD is paid
				// on the hop's own execution frequency (matches runtime acquire_read path).
				double tReadAcquireCost = 0.0;
				if (isTransientReadHop && hasConcreteTransientReadSource) {
					double tReadAcquireWeight =
						exactEstimator.stableLocalMaterializationWeight(
							hop, hopPlacementWeight, true);
					tReadAcquireCost = tReadAcquireWeight
							* exactEstimator.download(
									outputMemEstimate, lOutLogicalFType, numOfWorkers);
				}
				double cpLoutCost = cpSelfCost + childCostCPExec + tReadAcquireCost;
				boolean tWriteFoutMetadataPassThrough =
					isTransientWriteFoutMetadataPassThrough(hop, planChilds);
				double cpFoutCost = cpLoutCost + (tWriteFoutMetadataPassThrough ? 0.0 : cpUploadCost);
				double fedLoutCost = fedSelfCost + childCostFEDExec + resultDownloadCost;
				double fedFoutCost = fedSelfCost + childCostFEDExec
						+ derivedFedFoutBoundaryCost(derivedFedFout, cpUploadCost, resultDownloadCost);

					boolean allowFedFoutCandidate = hasConcreteTransientReadSource
							&& canSatisfyFedInputs && candidateDecisionReceipt.allowFEDFOUT()
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.FED, FederatedOutput.FOUT, tWriteExec, tWriteOut));
				boolean allowFedLoutCandidate = hasConcreteTransientReadSource
							&& canSatisfyFedInputs && candidateDecisionReceipt.allowFEDLOUT()
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.FED, FederatedOutput.LOUT, tWriteExec, tWriteOut));
					boolean allowCpLoutCandidate = candidateDecisionReceipt.allowCPLOUT()
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.CP, FederatedOutput.LOUT, tWriteExec, tWriteOut));
					boolean allowCpFoutCandidate = candidateDecisionReceipt.allowCPFOUT()
						&& (!hasTWriteRequirement || isTReadConsistentWithTWrite(
								ExecType.CP, FederatedOutput.FOUT, tWriteExec, tWriteOut));

					for(CandidateEmissionFact emissionFact : candidateDecisionReceipt.allowedEmissionFacts()) {
						PlacementEmissionState emissionState = emissionFact.emissionState();
						PlacementState exactState = emissionState.placementState();
						if(exactState.execType() == ExecType.FED && exactState.output() == FederatedOutput.FOUT
							&& allowFedFoutCandidate) {
							CandidateSelectionReceipt candidateSelection = new CandidateSelectionReceipt(
								candidateDecisionReceipt.candidateRuleFact().key(), emissionFact, List.of());
								ExactRelocationCost relocation;
								try {
									relocation = exactRelocationCost(capture,
										candidateDecisionReceipt, candidateSelection, exactState, planChilds,
										exactCollectedHops, memoTable, hopCommon, hopCommonTable, exactEstimator,
										hop, numOfWorkers, legacyFedBoundaryCosts);
								}
								catch(ExactPlanClosureConflict conflict) {
									continue;
								}
							double exactChildCostFEDExec = replaceLegacyRelocationCost(
								childCostFEDExec, relocation);
							FedEntryCost entryCost = computeFedEntryCost(hop, exactCollectedHops,
								effectiveCollectedFTypes, exactEstimator, baseSelfCost, outputMemEstimate,
								cpUploadMemEstimate, fedOverhead, singleWorkerFedPenalty, fedExecWeight,
								hopPlacementWeight, numOfWorkers, emissionFact.executionFType(), exactState.fType());
							double entryFedFoutCost = entryCost.fedSelfCost() + exactChildCostFEDExec
								+ derivedFedFoutBoundaryCost(emissionState.derivedFedFout(),
									entryCost.uploadCost(), entryCost.resultDownloadCost());
							FederatedPlannerDpMemoTable.FedPlan fedFOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								entryFedFoutCost, fOutFedPlanVariants, planChilds);
							fedFOutPlan.bindExactChildPlanEdges(exactCollectedHops, memoTable);
							fedFOutPlan.setExecType(ExecType.FED);
							fedFOutPlan.setFType(exactState.fType());
							fedFOutPlan.setCpFoutType(cpLogicalFType);
							fedFOutPlan.setDerivedFedFout(emissionState.derivedFedFout());
							fedFOutPlan.setSelectedPlacementState(exactState);
							fedFOutPlan.setDirectCandidateSelection(candidateSelection);
							fedFOutPlan.setDirectRelocationChoices(relocation.choices());
							fedFOutPlan.setDirectRelocationActionCosts(relocation.actionCosts());
							fedFOutPlan.setExactRecurrenceCosts(
								exactChildCostFEDExec, relocation.selectedBoundaryCost());
							fOutFedPlanVariants.addFedPlan(fedFOutPlan);
						}
						else if(exactState.execType() == ExecType.FED && exactState.output() == FederatedOutput.LOUT
							&& allowFedLoutCandidate) {
							CandidateSelectionReceipt candidateSelection = new CandidateSelectionReceipt(
								candidateDecisionReceipt.candidateRuleFact().key(), emissionFact, List.of());
								ExactRelocationCost relocation;
								try {
									relocation = exactRelocationCost(capture,
										candidateDecisionReceipt, candidateSelection, exactState, planChilds,
										exactCollectedHops, memoTable, hopCommon, hopCommonTable, exactEstimator,
										hop, numOfWorkers, legacyFedBoundaryCosts);
								}
								catch(ExactPlanClosureConflict conflict) {
									continue;
								}
							double exactChildCostFEDExec = replaceLegacyRelocationCost(
								childCostFEDExec, relocation);
							FedEntryCost entryCost = computeFedEntryCost(hop, exactCollectedHops,
								effectiveCollectedFTypes, exactEstimator, baseSelfCost, outputMemEstimate,
								cpUploadMemEstimate, fedOverhead, singleWorkerFedPenalty, fedExecWeight,
								hopPlacementWeight, numOfWorkers, emissionFact.executionFType(), exactState.fType());
							FederatedPlannerDpMemoTable.FedPlan fedLOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								entryCost.fedSelfCost() + exactChildCostFEDExec + entryCost.resultDownloadCost(),
								lOutFedPlanVariants, planChilds);
							fedLOutPlan.bindExactChildPlanEdges(exactCollectedHops, memoTable);
							fedLOutPlan.setExecType(ExecType.FED);
							fedLOutPlan.setFType(exactState.fType());
							fedLOutPlan.setCpFoutType(
								candidateDecisionReceipt.allowFEDFOUT() ? cpLogicalFType : null);
							fedLOutPlan.setSelectedPlacementState(exactState);
							fedLOutPlan.setDirectCandidateSelection(candidateSelection);
							fedLOutPlan.setDirectRelocationChoices(relocation.choices());
							fedLOutPlan.setDirectRelocationActionCosts(relocation.actionCosts());
							fedLOutPlan.setExactRecurrenceCosts(
								exactChildCostFEDExec, relocation.selectedBoundaryCost());
							lOutFedPlanVariants.addFedPlan(fedLOutPlan);
						}
						else if(exactState.execType() == ExecType.CP && exactState.output() == FederatedOutput.LOUT
							&& allowCpLoutCandidate && literalCandidateInputs) {
							CandidateSelectionReceipt candidateSelection = new CandidateSelectionReceipt(
								candidateDecisionReceipt.candidateRuleFact().key(), emissionFact, List.of());
							FederatedPlannerDpMemoTable.FedPlan cpLOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								cpLoutCost, lOutFedPlanVariants, planChilds);
							cpLOutPlan.bindExactChildPlanEdges(exactCollectedHops, memoTable);
							cpLOutPlan.setExecType(ExecType.CP);
							cpLOutPlan.setFType(exactState.fType());
							cpLOutPlan.setCpFoutType(
								candidateDecisionReceipt.allowCPFOUT() ? cpLogicalFType : null);
							cpLOutPlan.setSelectedPlacementState(exactState);
							cpLOutPlan.setDirectCandidateSelection(candidateSelection);
							cpLOutPlan.setExactRecurrenceCosts(childCostCPExec, childBoundaryCPExec);
							lOutFedPlanVariants.addFedPlan(cpLOutPlan);
						}
						else if(exactState.execType() == ExecType.CP && exactState.output() == FederatedOutput.FOUT
							&& allowCpFoutCandidate && literalCandidateInputs) {
							CandidateSelectionReceipt candidateSelection = new CandidateSelectionReceipt(
								candidateDecisionReceipt.candidateRuleFact().key(), emissionFact, List.of());
							double entryCpUploadCost = hopPlacementWeight
								* exactEstimator.upload(cpUploadMemEstimate, exactState.fType(), numOfWorkers);
							double entryCpFoutCost = cpLoutCost
								+ (tWriteFoutMetadataPassThrough ? 0.0 : entryCpUploadCost);
							FederatedPlannerDpMemoTable.FedPlan cpFOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								entryCpFoutCost, fOutFedPlanVariants, planChilds);
							cpFOutPlan.bindExactChildPlanEdges(exactCollectedHops, memoTable);
							cpFOutPlan.setExecType(ExecType.CP);
							cpFOutPlan.setFType(exactState.fType());
							cpFOutPlan.setCpFoutType(exactState.fType());
							cpFOutPlan.setFoutMaterializationAccounted(true);
							cpFOutPlan.setSelectedPlacementState(exactState);
							cpFOutPlan.setDirectCandidateSelection(candidateSelection);
							cpFOutPlan.setExactRecurrenceCosts(childCostCPExec, childBoundaryCPExec);
							fOutFedPlanVariants.addFedPlan(cpFOutPlan);
						}
					}


				if (FederatedPlannerTrace.shouldTrace(hop)) {
						String childBreakdown = formatDpChildBreakdown(
								lOutfOutChildHops, childCumulativeCost, childForwardingCostToCP,
								childForwardingCostToFED, childForwardingCostFOutToFED,
								lOUTOnlyinputHops, lOUTOnlychildCumulativeCost, lOUTOnlychildForwardingCostToFED,
								fOUTOnlyinputHops, fOUTOnlychildCumulativeCost,
								fOUTOnlychildForwardingCostToCP, fOUTOnlychildForwardingCostToFED);
							FederatedPlannerTrace.log(hop, "DP-Candidate", String.format(Locale.ROOT,
									"bits=%s childCost[CP=%.6f,FED=%.6f] self[CP=%.6f,FED=%.6f] selfModel[base=%.6f,fedCompute=%.6f,nativeAggUnaryFedCompute=%.6f,fedOverhead=%.6f,fedInstructionLatency=%.6f,singleWorkerPenalty=%.6f,computeWeight=%.6f,networkWeight=%.6f,multiplicity=%.6f,placementWeight=%.6f,mixedStage=%s,mixedInputPrep=%.6f,mixedPartialDownload=%.6f,mixedCoordinatorLocal=%.6f,mixedComputeFloor=%.6f] boundary[upload=%.6f,download=%.6f,trAcquire=%.6f] allow[cpl=%s,cpf=%s,fedl=%s,fedf=%s] reasonFedInputs=%s costs[cpl=%.6f,cpf=%.6f,fedl=%.6f,fedf=%.6f] derivedFedFout=%s children=%s",
									formatSelectedBits(selectedBits), childCostCPExec, childCostFEDExec,
									cpSelfCost, fedSelfCost,
									baseSelfCost, fedComputeCost, nativeAggUnaryFedComputeCost,
									effectiveFedOverhead, fedInstructionLatencyCost,
								singleWorkerFedPenalty,
								hopCommon.getComputeWeight(), hopNetworkWeight, hopCommon.getMultiplicity(), hopPlacementWeight,
								mixedFedLocalCost.getLabel(), mixedFedLocalCost.getInputPreparationCost(),
								mixedFedLocalCost.getPartialResultDownloadCost(),
								mixedFedLocalCost.getCoordinatorLocalCost(),
								mixedFedLocalCost.getFederatedComputeFloor(),
								cpUploadCost, resultDownloadCost, tReadAcquireCost,
							allowCpLoutCandidate, allowCpFoutCandidate, allowFedLoutCandidate, allowFedFoutCandidate,
							canSatisfyFedInputs, cpLoutCost, cpFoutCost, fedLoutCost, fedFoutCost, derivedFedFout,
							childBreakdown));
					}
				}
			}

		boolean hasLOutPlan = !lOutFedPlanVariants.isEmpty();
		boolean hasFOutPlan = !fOutFedPlanVariants.isEmpty();

		if (hasLOutPlan) {
			memoTable.pruneExactFedPlanVariants(hopOccurrence, lOutFedPlanVariants);
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), hopOccurrence,
				FederatedOutput.LOUT, lOutFedPlanVariants);
			FederatedPlannerDpCostEstimator.estimateExact(new FederatedPlannerDpCostEstimator.EstimatorRequest(
				capture.context.analysis(), hopOccurrence, memoTable,
				memoTable.getFedPlanAfterPrune(hop.getHopID(), FederatedOutput.LOUT)));
		}
		if (hasFOutPlan) {
			memoTable.pruneExactFedPlanVariants(hopOccurrence, fOutFedPlanVariants);
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), hopOccurrence,
				FederatedOutput.FOUT, fOutFedPlanVariants);
			FederatedPlannerDpCostEstimator.estimateExact(new FederatedPlannerDpCostEstimator.EstimatorRequest(
				capture.context.analysis(), hopOccurrence, memoTable,
				memoTable.getFedPlanAfterPrune(hop.getHopID(), FederatedOutput.FOUT)));
		}
		logDpBestPlans(hop, lOutFedPlanVariants, fOutFedPlanVariants, hasLOutPlan, hasFOutPlan);
	
			if (!hasLOutPlan && !hasFOutPlan) {
				throw new DMLRuntimeException("No valid federated plan for hop " + hopID + " (" + hop.getOpString()
						+ ") under privacy " + privacyConstraint + " (LOUT candidates=" + hasLOutPlan
						+ ", FOUT candidates=" + hasFOutPlan
						+ ", allowCpLout=" + sawAllowCpLout
						+ ", allowCpFout=" + sawAllowCpFout
						+ ", allowFedLout=" + sawAllowFedLout
						+ ", oracleFedFout=" + sawOracleFedFout
						+ ", allowFedFout=" + sawAllowFedFout
						+ ", canSatisfyFedInputs=" + sawCanSatisfyFedInputs + ")");
			}
			}

	private static Map<ExactInputBoundary,Double> exactLegacyFedBoundaryCosts(
		EnumerationCapture capture, Hop parent,
		List<Hop> bothOutputs, int[] selectedBits, double[] loutToFed, double[] foutToFed,
		List<Hop> loutOnly, List<Double> loutOnlyToFed,
		List<Hop> foutOnly, List<Double> foutOnlyToFed) {
		Map<CompiledHopKey,ArrayDeque<Integer>> physicalPositions = new IdentityHashMap<>();
		for(int inputPosition = 0; inputPosition < parent.getInput().size(); inputPosition++) {
			CompiledHopKey source = findOccurrence(capture, parent.getInput().get(inputPosition)).key();
			physicalPositions.computeIfAbsent(source, ignored -> new ArrayDeque<>()).add(inputPosition);
		}
		Map<ExactInputBoundary,Double> result = new LinkedHashMap<>();
		for(int i = 0; i < bothOutputs.size(); i++)
			addExactLegacyBoundaryCost(capture, bothOutputs.get(i), physicalPositions,
				selectedBits[i] == 0 ? loutToFed[i] : foutToFed[i], result);
		for(int i = 0; i < loutOnly.size(); i++)
			addExactLegacyBoundaryCost(capture, loutOnly.get(i), physicalPositions,
				loutOnlyToFed.get(i), result);
		for(int i = 0; i < foutOnly.size(); i++)
			addExactLegacyBoundaryCost(capture, foutOnly.get(i), physicalPositions,
				foutOnlyToFed.get(i), result);
		return Map.copyOf(result);
	}

	private static void addExactLegacyBoundaryCost(EnumerationCapture capture, Hop sourceHop,
		Map<CompiledHopKey,ArrayDeque<Integer>> physicalPositions, double cost,
		Map<ExactInputBoundary,Double> result) {
		HopOccurrenceProjection occurrence = findOccurrence(capture, sourceHop);
		ArrayDeque<Integer> positions = physicalPositions.get(occurrence.key());
		// Rewire-only/function-output dependencies are scheduled as children but are not
		// physical consumer inputs and therefore own no exact relocation demand here.
		if(positions == null || positions.isEmpty())
			return;
		int inputPosition = positions.removeFirst();
		ValueVersionKey sourceValue = capture.context.analysis().graph().node(occurrence.key())
			.orElseThrow().valueVersion();
		ExactInputBoundary key = new ExactInputBoundary(sourceValue, inputPosition);
		if(result.putIfAbsent(key, cost) != null)
			throw new IllegalStateException("DP legacy boundary cost duplicated exact input: " + key);
	}

	private static ExactRelocationCost exactRelocationCost(EnumerationCapture capture,
		CandidateDecisionReceipt candidate, CandidateSelectionReceipt candidateSelection,
		PlacementState target,
		List<Pair<Long,FederatedOutput>> childEdges, List<Hop> selectedChildHops,
		FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.HopCommon parentCommon,
		Map<Long,FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
		FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator,
		Hop parentHop, int numWorkers, Map<ExactInputBoundary,Double> legacyBoundaryCosts) {
		if(candidate.candidateSnapshot().parentOccurrence() != findOccurrence(capture, parentHop).key())
			throw new IllegalArgumentException("DP relocation candidate belongs to a different parent occurrence");
		if(candidateSelection.rule() != candidate.candidateRuleFact().key()
			|| !candidateSelection.emission().emissionState().placementState().equals(target))
			throw new IllegalArgumentException("DP exact candidate-row receipt differs from the relocation target");
		if(childEdges.size() != selectedChildHops.size())
			throw new IllegalArgumentException("DP relocation child edge and Hop counts differ");

		Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
		assignment.put(candidate.candidateSnapshot().parentOccurrence(), target);
		Map<ValueVersionKey,SelectedRelocationSource> selectedSources = new LinkedHashMap<>();
		Map<CompiledHopKey,SelectedRelocationSource> selectedSourcesByOccurrence = new IdentityHashMap<>();
		Set<FederatedPlannerDpMemoTable.FedPlan> visitedPlans =
			Collections.newSetFromMap(new IdentityHashMap<>());
		// Candidate reachability is defined by exact compiled input positions, while the
		// legacy enumeration list may contain rewired carriers. Bind every PRESENT
		// physical input (not ABSENT_LOCAL siblings) to the selected plan's analysis
		// occurrence before asking the exact relocation selector to validate the partial
		// parent assignment.
		for(int inputPosition = 0; inputPosition < candidateSelection.rule().orderedInputs().size(); inputPosition++) {
			if(!candidateSelection.rule().orderedInputs().get(inputPosition).present())
				continue;
			final int exactInputPosition = inputPosition;
			List<PlacementAnalysis.CompiledInputEdgeFact> physicalEdges = capture.context.analysis()
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == candidate.candidateSnapshot().parentOccurrence()
					&& edge.inputPosition() == exactInputPosition).toList();
			if(physicalEdges.isEmpty())
				continue; // logical function/transient input: validated by its exact semantic fact
			if(physicalEdges.size() != 1)
				throw new IllegalStateException("DP exact relocation physical input edge is ambiguous: parent="
					+ candidate.candidateSnapshot().parentOccurrence().normalizedSignature()
					+ " input=" + inputPosition);
			CompiledHopKey producer = physicalEdges.get(0).producer();
			FederatedPlannerDpMemoTable.FedPlan producerPlan = null;
			for(int childIndex = 0; childIndex < childEdges.size(); childIndex++) {
				Pair<Long,FederatedOutput> childEdge = childEdges.get(childIndex);
				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(
					selectedChildHops.get(childIndex), childEdge.getRight());
				if(childPlan == null || childPlan.getSelectedPlacementState() == null)
					throw new IllegalStateException("DP exact relocation source has no selected plan/state: edge="
						+ childEdge);
				if(memoTable.requirePlanCarrierOccurrence(childPlan.getHopRef()).key() != producer)
					continue;
				if(producerPlan != null && producerPlan != childPlan)
					throw new IllegalStateException("DP exact relocation physical producer has duplicate selected arms: "
						+ producer.normalizedSignature());
				producerPlan = childPlan;
			}
			if(producerPlan == null)
				throw new IllegalStateException("DP exact relocation candidate input has no selected physical plan: parent="
					+ candidate.candidateSnapshot().parentOccurrence().normalizedSignature()
					+ " input=" + inputPosition + " producer=" + producer.normalizedSignature());
			addSelectedPlanClosure(capture.context.analysis(), producerPlan, memoTable, assignment,
				selectedSources, selectedSourcesByOccurrence, visitedPlans);
			SelectedRelocationSource source = selectedSourcesByOccurrence.get(producer);
			if(source == null)
				throw new IllegalStateException("DP exact relocation candidate input has no selected physical source: parent="
					+ candidate.candidateSnapshot().parentOccurrence().normalizedSignature()
					+ " input=" + inputPosition + " producer=" + producer.normalizedSignature());
			PlacementState previous = assignment.putIfAbsent(producer, source.state());
			if(previous != null && !previous.equals(source.state()))
				throw new IllegalStateException("DP exact relocation candidate input has conflicting exact state: "
					+ producer.normalizedSignature());
		}

		// CandidateSelections validates every assigned operation consumer, including an
		// immediate child whose already-costed memo arm may itself require relocation.
		// Keep the complete graph-owned universe for that reachability validation.  The
		// explicit candidate receipt below still limits the generated demands (and hence
		// the objective delta) to this parent only, so child relocations are not charged
		// twice while their selected plan remains source-reachable.
		List<RelocationAction> actionUniverse = capture.context.analysis().graph().relocationActions();

		RelocationSelections.Selection selection;
		try {
			selection = RelocationSelections.selectMinimumCost(
				capture.context.analysis(), actionUniverse, assignment, List.of(candidateSelection),
					capture.relocationOrder,
					action -> exactRelocationActionCost(action, selectedSources, exactEstimator,
						parentHop, parentCommon, hopCommonTable, memoTable, numWorkers));
		}
		catch(IllegalStateException ex) {
			throw new IllegalStateException("DP exact relocation selection failed for parent="
				+ candidate.candidateSnapshot().parentOccurrence().normalizedSignature(), ex);
		}
		Map<RelocationActionKey,Double> actionCosts = new LinkedHashMap<>();
		Set<String> costedPhysicalEmissions = new LinkedHashSet<>();
		double selectedCost = 0.0;
		for(RelocationActionKey action : selection.emittedActions()) {
			double cost = exactRelocationActionCost(action, selectedSources, exactEstimator,
				parentHop, parentCommon, hopCommonTable, memoTable, numWorkers);
			boolean firstPhysicalEmission = costedPhysicalEmissions.add(
				RelocationSelections.physicalEmissionIdentity(action));
			actionCosts.put(action, firstPhysicalEmission ? cost : 0.0);
			if(firstPhysicalEmission)
				selectedCost += cost;
		}
		double selectedTolerance = 1e-9 * Math.max(1.0, Math.abs(selection.cost()));
		if(Math.abs(selectedCost - selection.cost()) > selectedTolerance)
			throw new IllegalStateException("DP exact relocation action-cost receipt differs from selection: selected="
				+ selection.cost() + " receipt=" + selectedCost);
		double legacyCost = 0.0;
		for(RelocationChoiceReceipt choice : selection.choices()) {
			ExactInputBoundary boundary = new ExactInputBoundary(
				choice.demand().sourceValueVersion(), choice.demand().inputPosition());
			Double cost = legacyBoundaryCosts.get(boundary);
			if(cost == null)
				throw new IllegalStateException("DP exact relocation demand has no physical legacy boundary: "
					+ choice.demand().normalizedSignature());
			legacyCost += cost;
		}
		return new ExactRelocationCost(selection.choices(), actionCosts, legacyCost, selection.cost());
	}

	private static void addSelectedPlanClosure(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable.FedPlan plan, FederatedPlannerDpMemoTable memoTable,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<ValueVersionKey,SelectedRelocationSource> selectedSources,
		Map<CompiledHopKey,SelectedRelocationSource> selectedSourcesByOccurrence,
		Set<FederatedPlannerDpMemoTable.FedPlan> visitedPlans) {
		if(!visitedPlans.add(plan))
			return;
		PlacementState state = plan.getSelectedPlacementState();
		if(state == null)
			throw new IllegalStateException("DP exact relocation plan closure contains an unselected arm");
		HopOccurrenceProjection occurrence = memoTable.requirePlanCarrierOccurrence(plan.getHopRef());
		PlacementState previousState = assignment.putIfAbsent(occurrence.key(), state);
		if(previousState != null && !previousState.equals(state))
			throw new ExactPlanClosureConflict("DP exact relocation plan closure has conflicting occurrence states: "
				+ occurrence.key().normalizedSignature() + " previous=" + previousState.normalizedSignature()
				+ " proposed=" + state.normalizedSignature() + " proposedPlan=" + plan.getHopRef());
		Hop exactHop = analysis.hop(occurrence.key()).orElseThrow(() ->
			new IllegalStateException("DP exact relocation plan closure has no analysis Hop"));
		ValueVersionKey sourceValue = analysis.graph().node(occurrence.key()).orElseThrow().valueVersion();
		SelectedRelocationSource proposed = new SelectedRelocationSource(plan, exactHop, state);
		SelectedRelocationSource previousOccurrence =
			selectedSourcesByOccurrence.putIfAbsent(occurrence.key(), proposed);
		if(previousOccurrence != null && !previousOccurrence.state().equals(state))
			throw new ExactPlanClosureConflict("DP exact relocation occurrence has conflicting source states: "
				+ occurrence.key().normalizedSignature());
		SelectedRelocationSource previous = selectedSources.putIfAbsent(sourceValue, proposed);
		if(previous != null && !previous.state().equals(state))
			throw new ExactPlanClosureConflict("DP exact relocation value version has conflicting source states: "
				+ sourceValue.normalizedSignature());
		CandidateSelectionReceipt direct = plan.getDirectCandidateSelection();
		if(direct == null)
			return;
		for(int inputPosition = 0; inputPosition < direct.rule().orderedInputs().size(); inputPosition++) {
			if(!direct.rule().orderedInputs().get(inputPosition).present())
				continue;
			final int exactInputPosition = inputPosition;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == occurrence.key()
					&& edge.inputPosition() == exactInputPosition).toList();
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				throw new IllegalStateException("DP exact relocation plan closure input is ambiguous: "
					+ occurrence.key().normalizedSignature() + " input=" + inputPosition);
			FType requiredType = direct.rule().orderedInputs().get(inputPosition).fType();
			boolean hasRelocationAlternative = analysis.graph().relocationActions().stream()
				.anyMatch(action -> action.key().materializationFType() == requiredType
					&& action.key().targetPlacement().equals(state)
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == occurrence.key()
							&& obligation.inputPosition() == exactInputPosition));
			if(hasRelocationAlternative)
				continue;
			CompiledHopKey requiredProducer = edges.get(0).producer();
			FederatedPlannerDpMemoTable.FedPlan requiredChild = null;
			for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge childEdge
				: plan.getExactChildPlanEdges()) {
				FederatedPlannerDpMemoTable.FedPlan childPlan = childEdge.selectedPlan();
				if(childPlan == null)
					throw new IllegalStateException("DP exact relocation plan closure has no selected child arm: "
						+ childEdge);
				if(childEdge.occurrence() != requiredProducer)
					continue;
				if(requiredChild != null && requiredChild != childPlan)
					throw new IllegalStateException("DP exact relocation plan closure has duplicate producer arms: "
						+ requiredProducer.normalizedSignature());
				requiredChild = childPlan;
			}
			if(requiredChild == null)
				throw new IllegalStateException("DP exact relocation plan closure omits PRESENT producer: "
					+ requiredProducer.normalizedSignature());
			addSelectedPlanClosure(analysis, requiredChild, memoTable, assignment, selectedSources,
				selectedSourcesByOccurrence, visitedPlans);
		}
	}

	private static double exactRelocationActionCost(RelocationActionKey action,
		Map<ValueVersionKey,SelectedRelocationSource> selectedSources,
		FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator, Hop parentHop,
		FederatedPlannerDpMemoTable.HopCommon parentCommon,
		Map<Long,FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
		FederatedPlannerDpMemoTable memoTable, int numWorkers) {
		SelectedRelocationSource source = selectedSources.get(action.sourceValueVersion());
		if(source == null)
			throw new IllegalStateException("DP exact relocation action has no selected source plan: "
				+ action.normalizedSignature());
		if(!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(source.hop()))
			return 0.0;
		double transferCost = exactEstimator.upload(source.hop(), parentHop,
			action.materializationFType(), numWorkers);
		// A selected formal-input LOUT arm already charges its caller FOUT->formal LOUT
		// transfer in sharedLogicalFunctionInputDownloadShare. Lowering may realize that
		// same accounted transfer as REFED_LOCAL immediately before this upload, but it
		// must not be charged a second time here. Only a directly selected FOUT source
		// adds a new download leg to this relocation action.
		if(source.state().output() == FederatedOutput.FOUT) {
			FType sourceType = Objects.requireNonNull(source.state().fType(),
				"FOUT relocation source has no exact FType");
			transferCost += exactEstimator.download(
				FederatedCostModel.getEffectiveOutputMemEstimate(source.hop()), sourceType, numWorkers);
		}
		else if(source.state().output() != FederatedOutput.LOUT)
			throw new IllegalStateException("DP relocation source has no materializable value placement: "
				+ source.state().normalizedSignature());
		return FederatedPlannerDpCostEstimator.computeBoundaryTransferShareForParent(
			transferCost, source.plan(), parentCommon, hopCommonTable, memoTable);
	}

	private static double replaceLegacyRelocationCost(double childCost,
		ExactRelocationCost relocation) {
		double result = childCost - relocation.legacyBoundaryCost() + relocation.selectedBoundaryCost();
		double tolerance = 1e-9 * Math.max(1.0, Math.abs(childCost));
		if(result < -tolerance)
			throw new IllegalStateException("DP exact relocation replacement produced a negative child cost: child="
				+ childCost + " legacy=" + relocation.legacyBoundaryCost()
				+ " selected=" + relocation.selectedBoundaryCost());
		return Math.max(0.0, result);
	}

	private static int adjustNumParentHopsForFunctionOutputBoundary(Hop hop, int numParentHops) {
		if (hop == null || numParentHops <= 1)
			return numParentHops;
		List<Hop> parentHops = hop.getParent();
		if (parentHops == null || parentHops.size() <= 1)
			return numParentHops;
		for (Hop parentHop : parentHops) {
			if (!isFunctionBoundaryParent(parentHop)) {
				return numParentHops;
			}
		}
		return 1;
	}

	private static boolean isFunctionBoundaryParent(Hop parentHop) {
		if (parentHop instanceof DataOp
				&& ((DataOp) parentHop).getOp() == Types.OpOpData.FUNCTIONOUTPUT) {
			return true;
		}
		return parentHop instanceof FunctionOp
				&& ((((FunctionOp) parentHop).getFunctionType() == FunctionType.MULTIRETURN_BUILTIN)
					|| (((FunctionOp) parentHop).getFunctionType() == FunctionType.DML));
	}

	private static void captureMemoSupportedChildSelections(Hop parent, List<Hop> childHops,
			FederatedPlannerDpMemoTable memoTable, EnumerationCapture capture) {
		List<Hop> bothOutputs = new ArrayList<>();
		List<Hop> loutOnly = new ArrayList<>();
		List<Hop> foutOnly = new ArrayList<>();
		for(Hop child : childHops) {
			boolean hasLout = memoTable.getFedPlanAfterPruneForOccurrence(
				child, FederatedOutput.LOUT) != null;
			boolean hasFout = memoTable.getFedPlanAfterPruneForOccurrence(
				child, FederatedOutput.FOUT) != null;
			if(hasLout && hasFout)
				bothOutputs.add(child);
			else if(hasLout)
				loutOnly.add(child);
			else if(hasFout)
				foutOnly.add(child);
			else
				throw new DMLRuntimeException("Missing federated plan for child hop " + child.getHopID()
					+ " while capturing parent " + parent.getHopID());
		}

		long variants = 1L << bothOutputs.size();
		for(long variant = 0; variant < variants; variant++) {
			List<Pair<Long, FederatedOutput>> planChilds = new ArrayList<>();
			List<Hop> selectedChildHops = new ArrayList<>();
			for(int bit = 0; bit < bothOutputs.size(); bit++)
				appendMemoSupportedEdge(bothOutputs.get(bit),
					(variant & (1L << bit)) == 0 ? FederatedOutput.LOUT : FederatedOutput.FOUT,
					memoTable, planChilds, selectedChildHops);
			for(Hop child : loutOnly)
				appendMemoSupportedEdge(child, FederatedOutput.LOUT, memoTable, planChilds, selectedChildHops);
			for(Hop child : foutOnly)
				appendMemoSupportedEdge(child, FederatedOutput.FOUT, memoTable, planChilds, selectedChildHops);
			captureConstructedChildSelection(parent, planChilds, selectedChildHops, memoTable, capture, variant);
		}
	}

	private static void appendMemoSupportedEdge(Hop child, FederatedOutput output,
			FederatedPlannerDpMemoTable memoTable, List<Pair<Long, FederatedOutput>> planChilds,
			List<Hop> selectedChildHops) {
		FederatedPlannerDpMemoTable.FedPlan childPlan =
			memoTable.getFedPlanAfterPruneForOccurrence(child, output);
		if(childPlan == null)
			throw new DMLRuntimeException("Missing " + output + " federated plan for child hop "
				+ child.getHopID());
		Hop selectedCarrier = childPlan.getHopRef();
		planChilds.add(Pair.of(selectedCarrier.getHopID(), output));
		selectedChildHops.add(selectedCarrier);
	}

	private static CandidateDecisionReceipt captureConstructedChildSelection(Hop parent,
			List<Pair<Long, FederatedOutput>> childEdges, List<Hop> selectedChildHops,
			FederatedPlannerDpMemoTable memoTable,
			EnumerationCapture capture, long variantOrdinal) {
		List<Hop> collectedHops = new ArrayList<>();
		List<FType> collectedFTypes = new ArrayList<>();
		Map<Long, FType> fedInputTypeMap = new LinkedHashMap<>();
		HopOccurrenceProjection parentOccurrence = findOccurrence(capture, parent);
		if(childEdges.size() != selectedChildHops.size())
				throw new DpSemanticConstructionException(
					DpPlacementAdapter.ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					capture.context.analysis().analysisFingerprint(), parentOccurrence.key(),
					"UNMAPPABLE_OCCURRENCE");
		for(int edgeIndex = 0; edgeIndex < childEdges.size(); edgeIndex++) {
			Pair<Long, FederatedOutput> edge = childEdges.get(edgeIndex);
			Hop child = Objects.requireNonNull(selectedChildHops.get(edgeIndex), "selected child hop");
			FederatedPlannerDpMemoTable.FedPlan childPlan =
				memoTable.getFedPlanAfterPrune(child, edge.getRight());
			if(childPlan == null)
				throw new DMLRuntimeException("Missing " + edge.getRight() + " federated plan for child hop "
					+ edge.getLeft());
			if(child.getHopID() != edge.getLeft())
				throw new DpSemanticConstructionException(
					DpPlacementAdapter.ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					capture.context.analysis().analysisFingerprint(), parentOccurrence.key(),
					"UNMAPPABLE_OCCURRENCE");
			collectedHops.add(child);
			if(edge.getRight() == FederatedOutput.FOUT) {
				collectedFTypes.add(childPlan.getFType());
				fedInputTypeMap.put(child.getHopID(), childPlan.getFType());
			}
			else
				collectedFTypes.add(null);
		}
		NormalizedCandidateInputs normalized = DpPlacementAdapter.normalizeCandidateInputs(
			capture.context, parentOccurrence, childEdges, collectedHops,
			collectedFTypes, fedInputTypeMap, memoTable);
		capture.capture(normalized.snapshot(), variantOrdinal);
		CandidateDecisionReceipt receipt = DpPlacementAdapter.resolveCandidateDecision(
			capture.context, normalized, variantOrdinal);
		capture.captureDecisionReceipt(receipt, variantOrdinal);
		capture.observer.oracleEvaluated();
		return receipt;
	}

	private static void enumerateFederatedDataOp(DataOp dataOp, FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.HopCommon hopCommon, EnumerationCapture capture) {
		HopOccurrenceProjection sourceOccurrence = findOccurrence(capture, dataOp);
		PlacementState exactSourceState = DpPlacementAdapter.requireExactSourceState(capture.context,
			sourceOccurrence, ExecType.FED, FederatedOutput.FOUT);
		FType sourceFType = Objects.requireNonNull(exactSourceState.fType(),
			"Exact FED/FOUT source state lacks a concrete FType for " + sourceOccurrence.key());
		FederatedPlannerUtils.registerFedInitVar(dataOp.getName(), sourceFType,
			FederatedPlannerUtils.deriveFedInitSignature(dataOp));

		FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants = new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon,
				FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan fedPlan = new FederatedPlannerDpMemoTable.FedPlan(0.0, fOutFedPlanVariants,
					Collections.emptyList());
			fedPlan.setExecType(ExecType.FED);
			fedPlan.setFType(sourceFType);
			fedPlan.setCpFoutType(sourceFType);
			fedPlan.setSelectedPlacementState(exactSourceState);
			fedPlan.setExactRecurrenceCosts(0d, 0d);
			fOutFedPlanVariants.addFedPlan(fedPlan);
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), sourceOccurrence,
				FederatedOutput.FOUT, fOutFedPlanVariants);
	}

	private static boolean enumerateTransientReadDataOp(DataOp dataOp, List<Hop> childHops,
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.HopCommon hopCommon) {
		int numOfWorkers = memoTable != null ? memoTable.getNumWorkers() : 1;
		return enumerateTransientReadDataOp(dataOp, childHops, memoTable, hopCommon, numOfWorkers);
	}

	private static boolean enumerateTransientReadDataOp(DataOp dataOp, List<Hop> childHops,
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.HopCommon hopCommon,
			int numOfWorkers) {
		return enumerateTransientReadDataOp(dataOp, childHops, memoTable, hopCommon, numOfWorkers, null);
	}

	private static boolean enumerateTransientReadDataOp(DataOp dataOp, List<Hop> childHops,
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.HopCommon hopCommon,
			int numOfWorkers, EnumerationCapture capture) {
		if(capture == null)
			throw new IllegalStateException("Transient-read DP enumeration requires exact neutral capture");
		if (dataOp == null || dataOp.getOp() != Types.OpOpData.TRANSIENTREAD) {
			return false;
		}

		List<Hop> physicalTransientWrites = collectTransientWriteChildHops(dataOp, childHops);
		rejectAmbiguousTransientWriteHopIds(dataOp, physicalTransientWrites, capture);
		List<LogicalFunctionInputFact> logicalFunctionInputs = logicalFunctionInputsForFormal(dataOp, capture);
		if(capture.sharedFunctionInputClosure && logicalFunctionInputs.size() > 1)
			return enumerateSharedLogicalFunctionRead(dataOp, logicalFunctionInputs, memoTable,
				hopCommon, numOfWorkers, capture);
		// A formal function TRead is owned by the exact caller-argument boundary.
		// The legacy rewire table can also expose the argument's upstream TWrite, but
		// that is only a scheduling predecessor; choosing it directly erases the
		// logical candidate input and incorrectly produces a zero-input DP variant.
		List<Hop> sourceChildHops = collectLogicalFunctionArgumentChildHops(dataOp, childHops, capture);
		List<Hop> completeLogicalTransientSources = List.of();
		boolean incompleteTransientSeed = false;
		if(sourceChildHops.isEmpty()) {
			completeLogicalTransientSources = collectLogicalTransientSourceChildHops(dataOp, capture);
			sourceChildHops = completeLogicalTransientSources;
			if(capture.seedExactTransientFrontier && sourceChildHops.size() > 1) {
				sourceChildHops = sourceChildHops.stream().filter(source ->
					memoTable.getFedPlanAfterPruneForOccurrence(source, FederatedOutput.LOUT) != null
						|| memoTable.getFedPlanAfterPruneForOccurrence(source, FederatedOutput.FOUT) != null)
					.toList();
				incompleteTransientSeed = sourceChildHops.size() < completeLogicalTransientSources.size();
			}
		}
		if(sourceChildHops.isEmpty())
			sourceChildHops = collectTransientWriteChildHops(dataOp, childHops, capture);
		if (sourceChildHops.isEmpty()) {
			return false;
		}

		FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator = capture == null ? null :
			FederatedPlannerDpCostEstimator.bindExact(
				capture.context.analysis(), findOccurrence(capture, dataOp), memoTable);
		double baseSelfCost = exactEstimator == null
			? FederatedPlannerDpCostEstimator.legacyHopCost(hopCommon)
			: exactEstimator.computeHopCost(hopCommon);

		HopOccurrenceProjection readOccurrence = findOccurrence(capture, dataOp);
		List<PlacementState> readStates = capture.context.analysis().graph().node(readOccurrence.key())
			.orElseThrow().legalAlternatives();
		if(incompleteTransientSeed)
			return enumerateSeedExactTransientRead(dataOp, readOccurrence, sourceChildHops,
				readStates, memoTable, hopCommon, capture, baseSelfCost);
		// Rewire transient-forward edges are scheduling dependencies only. They do not
		// manufacture a logical federated input across an ambiguous CFG join. Enumerate
		// exactly the TRead/TWrite states already proven by the neutral graph; otherwise
		// a forward-only edge can incorrectly construct FED/FOUT for a CP/LOUT-only read.
		boolean allowLOUT = readStates.stream().anyMatch(state -> state.execType() == ExecType.CP
			&& state.output() == FederatedOutput.LOUT && state.fType() == null);
		boolean allowFOUT = readStates.stream().anyMatch(state -> state.execType() == ExecType.FED
			&& state.output() == FederatedOutput.FOUT && state.fType() != null);
		FType loutFType = null;
		FType foutFType = null;
		double loutCost = baseSelfCost;
		double loutAcquireCost = 0.0;
		boolean needsFoutMaterialization = false;
		boolean hasFederatedSourcePlan = false;
		List<Pair<Long, FederatedOutput>> loutChilds = new ArrayList<>();
		List<Hop> loutSelectedChildHops = new ArrayList<>();
		List<FederatedPlannerDpMemoTable.FedPlan> loutSelectedChildPlans = new ArrayList<>();
		List<Pair<Long, FederatedOutput>> foutChilds = new ArrayList<>();
		List<Hop> foutSelectedChildHops = new ArrayList<>();
		List<FederatedPlannerDpMemoTable.FedPlan> foutSelectedChildPlans = new ArrayList<>();
		double foutCost = baseSelfCost;
		List<List<FederatedPlannerDpMemoTable.FedPlan>> loutPlansBySource = new ArrayList<>();
		List<List<FederatedPlannerDpMemoTable.FedPlan>> foutPlansBySource = new ArrayList<>();
		boolean exactTransientJoin = sourceChildHops.size() > 1;

		for (Hop sourceChildHop : sourceChildHops) {
			HopOccurrenceProjection sourceOccurrence = capture.context.rewireSnapshot()
				.projectExactCarrier(sourceChildHop);
			if(sourceOccurrence == null)
				throw new IllegalStateException("Transient-read source lacks exact occurrence authority: "
					+ sourceChildHop.getHopID());
			// A recompile carrier may retain only the arm needed by its local traversal,
			// while another carrier of the same exact TWrite occurrence owns the matching
			// FED/FOUT arm. TRead/TWrite consistency is occurrence-scoped, not clone-id
			// scoped, so enumerate from the complete exact source occurrence domain and
			// retain the selected executable carrier on the child edge.
			List<FederatedPlannerDpMemoTable.FedPlan> exactLoutPlans = memoTable.getExactPlansAfterPrune(
				sourceOccurrence, FederatedOutput.LOUT);
			List<FederatedPlannerDpMemoTable.FedPlan> exactFoutPlans = memoTable.getExactPlansAfterPrune(
				sourceOccurrence, FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan loutPlan = exactLoutPlans.isEmpty()
				? null : exactLoutPlans.get(0);
			FederatedPlannerDpMemoTable.FedPlan foutPlan = exactFoutPlans.isEmpty()
				? null : exactFoutPlans.get(0);
			if(exactTransientJoin) {
				loutPlansBySource.add(exactLoutPlans);
				foutPlansBySource.add(exactFoutPlans);
			}
			boolean mayMaterializeFout = loutPlan == null && foutPlan != null
				&& foutPlan.getFType() != null
				&& isExactMaterializableTransientSource(dataOp, sourceOccurrence, capture);
			if (loutPlan != null) {
				FType childFType = loutPlan.getFType();
				if (childFType != null) {
					if (loutFType == null) {
						loutFType = childFType;
					}
					else if (loutFType != childFType) {
						allowLOUT = false;
					}
				}
				loutCost += loutPlan.getCumulativeCostPerParents();
				loutChilds.add(Pair.of(loutPlan.getHopID(), FederatedOutput.LOUT));
				loutSelectedChildHops.add(loutPlan.getHopRef());
				loutSelectedChildPlans.add(loutPlan);
			}
			else if(mayMaterializeFout) {
				// A physical initializer can be rewired at its direct consumer. A logical
				// function argument can likewise be rewired at the exact FunctionCallCP input
				// proven by LogicalFunctionInputFact. Both paths own one explicit, costed
				// FOUT->LOUT action; a logical TWrite->TRead value edge remains strict.
				hasFederatedSourcePlan = true;
				needsFoutMaterialization = true;
				if(foutFType == null)
					foutFType = foutPlan.getFType();
				else if(foutFType != foutPlan.getFType())
					allowLOUT = false;
				loutCost += foutPlan.getCumulativeCostPerParents();
				loutChilds.add(Pair.of(foutPlan.getHopID(), FederatedOutput.FOUT));
				loutSelectedChildHops.add(foutPlan.getHopRef());
				loutSelectedChildPlans.add(foutPlan);
			}
			else
				allowLOUT = false;

			if (foutPlan == null || foutPlan.getFType() == null
					|| !hasFederatedTransientInputAuthority(dataOp, sourceChildHop, capture)
					|| !canTransientReadReuseMatchedFoutWrite(
						dataOp, foutPlan, memoTable, capture)) {
				allowFOUT = false;
			}
			else {
				hasFederatedSourcePlan = true;
				FType childFType = foutPlan.getFType();
				if (foutFType == null) {
					foutFType = childFType;
				}
				else if (foutFType != childFType) {
					allowFOUT = false;
				}
				foutCost += foutPlan.getCumulativeCostPerParents();
				foutChilds.add(Pair.of(foutPlan.getHopID(), FederatedOutput.FOUT));
				foutSelectedChildHops.add(foutPlan.getHopRef());
				foutSelectedChildPlans.add(foutPlan);
			}
		}

		if (!allowLOUT && !allowFOUT) {
			throw new DMLRuntimeException("No valid federated plan for hop " + dataOp.getHopID()
					+ " (" + dataOp.getOpString() + ") based on transient write placements");
		}
		if(exactTransientJoin)
			return enumerateJoinedTransientRead(dataOp, readOccurrence, loutPlansBySource,
				foutPlansBySource, memoTable, hopCommon, capture, baseSelfCost);
		// TRANSIENTREAD with a matching TRANSIENTWRITE LOUT source is already paying the
		// local materialization/download cost in the producer cumulative cost. Charging a
		// second synthetic local-acquire download here double-counts the CP/LOUT path and
		// can incorrectly bias parent transitions toward FED/FOUT (e.g., ALS mask W).
		if (needsFoutMaterialization && hasFederatedSourcePlan && dataOp.getDim1() > 0
				&& dataOp.getDim2() > 0) {
			double hopPlacementWeight = placementTransferWeight(hopCommon);
			double outputMemEstimate = FederatedCostModel.getEffectiveOutputMemEstimate(dataOp);
			FederatedPlannerDpCostEstimator.TransientReadCostReceipt costReceipt = exactEstimator == null
				? FederatedPlannerDpCostEstimator.legacyTransientReadCosts(hopCommon, dataOp,
					hopPlacementWeight, true, outputMemEstimate, foutFType, numOfWorkers)
				: exactEstimator.transientReadCosts(hopCommon, dataOp, hopPlacementWeight, true,
					outputMemEstimate, foutFType, numOfWorkers);
			loutAcquireCost = costReceipt.localMaterializationWeight() * costReceipt.downloadCost();
		}

		CandidateDecisionReceipt loutReceipt = allowLOUT
			? captureConstructedChildSelection(dataOp, loutChilds, loutSelectedChildHops, memoTable, capture, 0L)
			: null;
		CandidateDecisionReceipt foutReceipt = allowFOUT
			? captureConstructedChildSelection(dataOp, foutChilds, foutSelectedChildHops, memoTable, capture, 1L)
			: null;

		if (allowLOUT) {
			loutCost += loutAcquireCost;
			FederatedPlannerDpMemoTable.FedPlanVariants lOutFedPlanVariants =
					new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);
				FederatedPlannerDpMemoTable.FedPlan loutPlan =
						new FederatedPlannerDpMemoTable.FedPlan(loutCost, lOutFedPlanVariants, loutChilds);
				loutPlan.bindExactChildPlanEdges(
					loutSelectedChildHops, loutSelectedChildPlans, memoTable);
				loutPlan.setExecType(ExecType.CP);
				loutPlan.setFType(loutFType);
				loutPlan.setCpFoutType(allowFOUT ? foutFType : null);
				loutPlan.setSelectedPlacementState(
					loutReceipt.requireExactState(ExecType.CP, FederatedOutput.LOUT));
				loutPlan.setExactRecurrenceCosts(loutCost - baseSelfCost - loutAcquireCost, 0d);
				lOutFedPlanVariants.addFedPlan(loutPlan);
				memoTable.pruneExactFedPlanVariants(readOccurrence, lOutFedPlanVariants);
				if(capture == null)
					memoTable.addFedPlanVariants(memoTable.requirePlanCarrierOccurrence(dataOp),
						FederatedOutput.LOUT, lOutFedPlanVariants);
				else
					memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), findOccurrence(capture, dataOp),
						FederatedOutput.LOUT, lOutFedPlanVariants);
		}

		if (allowFOUT) {
			FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants =
					new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);
				FederatedPlannerDpMemoTable.FedPlan foutPlan =
						new FederatedPlannerDpMemoTable.FedPlan(foutCost, fOutFedPlanVariants, foutChilds);
				foutPlan.bindExactChildPlanEdges(
					foutSelectedChildHops, foutSelectedChildPlans, memoTable);
				foutPlan.setExecType(ExecType.FED);
				foutPlan.setFType(foutFType);
				foutPlan.setCpFoutType(foutFType);
				foutPlan.setSelectedPlacementState(
					foutReceipt.requireExactState(ExecType.FED, FederatedOutput.FOUT));
				foutPlan.setExactRecurrenceCosts(foutCost - baseSelfCost, 0d);
				fOutFedPlanVariants.addFedPlan(foutPlan);
				memoTable.pruneExactFedPlanVariants(readOccurrence, fOutFedPlanVariants);
				if(capture == null)
					memoTable.addFedPlanVariants(memoTable.requirePlanCarrierOccurrence(dataOp),
						FederatedOutput.FOUT, fOutFedPlanVariants);
				else
					memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), findOccurrence(capture, dataOp),
						FederatedOutput.FOUT, fOutFedPlanVariants);
		}

		return true;
	}

	/**
	 * Builds a private initialization frontier for a loop-carried transient join.
	 * Only already-enumerated CFG predecessors participate. The resulting arms are
	 * explicitly marked as seeds, are replaced by complete-source closure passes,
	 * and are rejected before any planner receipt can be published.
	 */
	private static boolean enumerateSeedExactTransientRead(DataOp dataOp,
		HopOccurrenceProjection readOccurrence, List<Hop> availableSources,
		List<PlacementState> readStates, FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.HopCommon hopCommon, EnumerationCapture capture,
		double baseSelfCost) {
		if(availableSources.isEmpty())
			throw new DMLRuntimeException("Exact transient frontier has no acyclic initialization source for "
				+ readOccurrence.key().normalizedSignature());
		boolean emitted = false;
		for(FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
			FederatedPlannerDpMemoTable.FedPlanVariants variants =
				new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, output);
			for(PlacementState readState : readStates) {
				boolean matchingOutput = readState.output() == output
					&& (output == FederatedOutput.LOUT
						? readState.execType() == ExecType.CP && readState.fType() == null
						: readState.execType() == ExecType.FED && readState.fType() != null);
				if(!matchingOutput)
					continue;
				List<FederatedPlannerDpMemoTable.FedPlan> selected = new ArrayList<>();
				for(Hop source : availableSources) {
					HopOccurrenceProjection sourceOccurrence = capture.context.rewireSnapshot()
						.projectExactCarrier(source);
					if(sourceOccurrence == null)
						throw new IllegalStateException("Transient seed source lacks exact occurrence authority: "
							+ source.getHopID());
					FederatedPlannerDpMemoTable.FedPlan sourcePlan = memoTable
						.getExactPlansAfterPrune(sourceOccurrence, output).stream()
						.filter(plan -> readState.equals(plan.getSelectedPlacementState()))
						.min(Comparator.comparingDouble(
							FederatedPlannerDpMemoTable.FedPlan::getCumulativeCost))
						.orElse(null);
					if(sourcePlan == null) {
						selected.clear();
						break;
					}
					selected.add(sourcePlan);
				}
				if(selected.size() != availableSources.size())
					continue;
				List<Pair<Long,FederatedOutput>> childEdges = selected.stream()
					.map(plan -> Pair.of(plan.getHopID(), output)).toList();
				List<Hop> childCarriers = selected.stream()
					.map(FederatedPlannerDpMemoTable.FedPlan::getHopRef).toList();
				double embedded = selected.stream()
					.mapToDouble(FederatedPlannerDpMemoTable.FedPlan::getCumulativeCostPerParents).sum();
				FederatedPlannerDpMemoTable.FedPlan seed = new FederatedPlannerDpMemoTable.FedPlan(
					baseSelfCost + embedded, variants, childEdges);
				seed.bindExactChildPlanEdges(childCarriers, selected, memoTable);
				seed.setExecType(readState.execType());
				seed.setFType(readState.fType());
				seed.setCpFoutType(readState.fType());
				seed.setSelectedPlacementState(readState);
				seed.setExactRecurrenceCosts(embedded, 0d);
				seed.markExactFrontierSeed();
				variants.addFedPlan(seed);
			}
			if(variants.isEmpty())
				continue;
			memoTable.pruneExactFedPlanVariants(readOccurrence, variants);
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), readOccurrence,
				output, variants);
			emitted = true;
		}
		if(!emitted)
			throw new DMLRuntimeException("No exact placement-common initialization arm for transient read "
				+ dataOp.getHopID() + " (" + dataOp.getName() + ")");
		FederatedPlannerTrace.log(dataOp, "DP-Transient-Seed",
			"sources=" + availableSources.stream().map(Hop::getHopID).toList());
		return true;
	}

	private static boolean enumerateJoinedTransientRead(DataOp dataOp,
		HopOccurrenceProjection readOccurrence,
		List<List<FederatedPlannerDpMemoTable.FedPlan>> loutPlansBySource,
		List<List<FederatedPlannerDpMemoTable.FedPlan>> foutPlansBySource,
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.HopCommon hopCommon, EnumerationCapture capture,
		double baseSelfCost) {
		FederatedPlannerDpMemoTable.FedPlanVariants loutVariants =
			new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlanVariants foutVariants =
			new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);
		addJoinedTransientReadPlans(dataOp, readOccurrence, FederatedOutput.LOUT,
			loutPlansBySource, loutVariants, memoTable, capture, baseSelfCost);
		addJoinedTransientReadPlans(dataOp, readOccurrence, FederatedOutput.FOUT,
			foutPlansBySource, foutVariants, memoTable, capture, baseSelfCost);
		boolean hasLout = !loutVariants.isEmpty();
		boolean hasFout = !foutVariants.isEmpty();
		if(!hasLout && !hasFout)
			throw new DMLRuntimeException("No exact common TRead/TWrite placement for transient join "
				+ readOccurrence.key().normalizedSignature());
		if(hasLout) {
			loutVariants.pruneExactBoundaryRepresentatives();
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), readOccurrence,
				FederatedOutput.LOUT, loutVariants);
		}
		if(hasFout) {
			foutVariants.pruneExactBoundaryRepresentatives();
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), readOccurrence,
				FederatedOutput.FOUT, foutVariants);
		}
		return true;
	}

	private static void addJoinedTransientReadPlans(DataOp dataOp,
		HopOccurrenceProjection readOccurrence, FederatedOutput output,
		List<List<FederatedPlannerDpMemoTable.FedPlan>> plansBySource,
		FederatedPlannerDpMemoTable.FedPlanVariants variants,
		FederatedPlannerDpMemoTable memoTable, EnumerationCapture capture,
		double baseSelfCost) {
		if(plansBySource.isEmpty() || plansBySource.stream().anyMatch(List::isEmpty))
			return;
		Map<String,List<FederatedPlannerDpMemoTable.FedPlan>> uniformByState = new LinkedHashMap<>();
		for(FederatedPlannerDpMemoTable.FedPlan plan : plansBySource.get(0)) {
			PlacementState state = plan.getSelectedPlacementState();
			if(state == null || state.output() != output)
				continue;
			List<FederatedPlannerDpMemoTable.FedPlan> selected = new ArrayList<>();
			selected.add(plan);
			boolean common = true;
			for(int source = 1; source < plansBySource.size(); source++) {
				FederatedPlannerDpMemoTable.FedPlan matching = plansBySource.get(source).stream()
					.filter(candidate -> state.equals(candidate.getSelectedPlacementState()))
					.min(Comparator.comparingDouble(
						FederatedPlannerDpMemoTable.FedPlan::getCumulativeCost)).orElse(null);
				if(matching == null) {
					common = false;
					break;
				}
				selected.add(matching);
			}
			if(common)
				uniformByState.putIfAbsent(state.normalizedSignature(), List.copyOf(selected));
		}
		long ordinal = output == FederatedOutput.LOUT ? 0L : 1L;
		for(List<FederatedPlannerDpMemoTable.FedPlan> selected : uniformByState.values()) {
			List<Pair<Long,FederatedOutput>> childEdges = selected.stream()
				.map(plan -> Pair.of(plan.getHopID(), output)).toList();
			List<Hop> childCarriers = selected.stream()
				.map(FederatedPlannerDpMemoTable.FedPlan::getHopRef).toList();
			CandidateDecisionReceipt receipt = captureConstructedChildSelection(dataOp,
				childEdges, childCarriers, memoTable, capture, ordinal++);
			PlacementState exactState = receipt.requireExactState(
				output == FederatedOutput.LOUT ? ExecType.CP : ExecType.FED, output);
			if(!exactState.equals(selected.get(0).getSelectedPlacementState()))
				throw new IllegalStateException("Transient join candidate state differs from exact source state");
			// Reaching TWrite definitions are CFG alternatives, not simultaneously
			// executed physical inputs of the TRead. In particular, a loop-carried
			// definition can depend on this same TRead through the loop body. Embedding
			// every producer's cumulative subtree therefore creates the positive
			// recurrence R -> W -> ... -> R and also counts alternative definitions as
			// if they were one n-ary input list. The exact component forest already
			// charges every selected occurrence once and enforces every SAME_PLACEMENT
			// source relation. Retain the exact source edges as placement authority, but
			// expose only each definition occurrence's local recurrence term here. This
			// preserves DP's immediate-hop cost ranking without recursively re-entering
			// the CFG cycle.
			double embedded = selected.stream()
				.map(FederatedPlannerDpCostEstimator::exactPlanRecurrenceTerm)
				.mapToDouble(term -> FederatedPlannerDpCostEstimator.exactForestObjective(
					List.of(term)))
				.sum();
			FederatedPlannerDpMemoTable.FedPlan joined = new FederatedPlannerDpMemoTable.FedPlan(
				baseSelfCost + embedded, variants, childEdges);
			joined.bindExactChildPlanEdges(childCarriers, selected, memoTable);
			joined.setExecType(exactState.execType());
			joined.setFType(exactState.fType());
			joined.setCpFoutType(exactState.fType());
			joined.setSelectedPlacementState(exactState);
			joined.setExactRecurrenceCosts(embedded, 0d);
			variants.addFedPlan(joined);
		}
	}

	private static boolean isExactMaterializableTransientSource(DataOp transientRead,
		HopOccurrenceProjection source, EnumerationCapture capture) {
		HopOccurrenceProjection read = findOccurrence(capture, transientRead);
		List<LogicalFunctionInputFact> logicalFunction = capture.context.analysis()
			.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> fact.sourceArgument() == source.key()
				&& fact.targetRead() == read.key() && fact.logicalPosition() == 0).toList();
		if(!logicalFunction.isEmpty()) {
			if(logicalFunction.size() != 1)
				throw new IllegalArgumentException(
					"Function-input materialization authority is ambiguous");
			capture.context.analysis().requireExactPhysicalFunctionInputConsumer(logicalFunction.get(0));
			return true;
		}
		Hop sourceHop = capture.context.analysis().hop(source.key()).orElseThrow();
		boolean federatedInitializer = sourceHop instanceof DataOp
			&& ((DataOp) sourceHop).getOp() == Types.OpOpData.FEDERATED
			&& capture.context.analysis().compiledInputEdgesInCanonicalOrder().stream()
				.anyMatch(edge -> edge.producer() == source.key() && edge.consumer() == read.key());
		return federatedInitializer;
	}

	private static List<LogicalFunctionInputFact> logicalFunctionInputsForFormal(DataOp formalRead,
		EnumerationCapture capture) {
		HopOccurrenceProjection read = findOccurrence(capture, formalRead);
		return capture.context.analysis().logicalFunctionInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == read.key() && fact.logicalPosition() == 0)
			.toList();
	}

	private static List<Hop> collectLogicalTransientSourceChildHops(DataOp transientRead,
		EnumerationCapture capture) {
		if(transientRead == null || capture == null)
			return List.of();
		HopOccurrenceProjection read = findOccurrence(capture, transientRead);
		List<LogicalTransientInputFact> facts = capture.context.analysis()
			.logicalTransientInputsInCanonicalOrder().stream()
			.filter(fact -> fact.targetRead() == read.key() && fact.logicalPosition() == 0)
			.toList();
		if(facts.isEmpty())
			return List.of();
		List<Hop> sources = new ArrayList<>(facts.size());
		Set<Hop> retained = Collections.newSetFromMap(new IdentityHashMap<>());
		for(LogicalTransientInputFact fact : facts) {
			Hop source = capture.context.analysis().hop(fact.sourceWrite()).orElseThrow(() ->
				new IllegalArgumentException("Logical transient DP source Hop is missing"));
			HopOccurrenceProjection projected = capture.context.rewireSnapshot().projectExactCarrier(source);
			if(projected == null || projected.key() != fact.sourceWrite())
				throw new IllegalArgumentException(
					"Logical transient DP source carrier differs from analysis authority");
			if(source == transientRead)
				throw new IllegalArgumentException("Logical transient DP source is self-recursive: "
					+ read.key().normalizedSignature());
			if(retained.add(source))
				sources.add(source);
		}
		return List.copyOf(sources);
	}

	/**
	 * A compiled DML function body is shared by all of its call sites, so its formal
	 * TRead has one runtime placement rather than one placement per traversal.  The
	 * seed pass makes every caller source plan available; this closure pass then
	 * chooses one exact formal state that every caller can satisfy and charges every
	 * caller boundary explicitly.
	 */
	private static boolean enumerateSharedLogicalFunctionRead(DataOp formalRead,
		List<LogicalFunctionInputFact> facts, FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.HopCommon hopCommon, int numOfWorkers,
		EnumerationCapture capture) {
		HopOccurrenceProjection formalOccurrence = findOccurrence(capture, formalRead);
		FederatedPlannerDpCostEstimator.ExactEstimator formalEstimator =
			FederatedPlannerDpCostEstimator.bindExact(
				capture.context.analysis(), formalOccurrence, memoTable);
		double baseSelfCost = formalEstimator.computeHopCost(hopCommon);
		List<List<SharedLogicalInputArm>> armsByFact = new ArrayList<>(facts.size());

		for(int factIndex = 0; factIndex < facts.size(); factIndex++) {
			LogicalFunctionInputFact fact = facts.get(factIndex);
			Hop source = capture.context.analysis().hop(fact.sourceArgument()).orElseThrow(() ->
				new IllegalArgumentException("Shared function-input DP source Hop is missing"));
			HopOccurrenceProjection projected = capture.context.rewireSnapshot().projectExactCarrier(source);
			if(projected == null || projected.key() != fact.sourceArgument())
				throw new IllegalArgumentException(
					"Shared function-input DP source carrier differs from analysis authority");

			List<SharedLogicalInputArm> factArms = new ArrayList<>(2);
			for(FederatedOutput sourceOutput : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
				FederatedPlannerDpMemoTable.FedPlan sourcePlan =
					memoTable.getFedPlanAfterPruneForOccurrence(source, sourceOutput);
				if(sourcePlan == null)
					continue;
				if(memoTable.requirePlanCarrierOccurrence(sourcePlan.getHopRef()) != projected
					|| sourcePlan.getSelectedPlacementState() == null)
					throw new IllegalArgumentException(
						"Shared function-input DP source plan is not exact-analysis-owned");

				long variantOrdinal = Math.addExact(Math.multiplyExact((long) factIndex, 2L),
					sourceOutput == FederatedOutput.LOUT ? 0L : 1L);
				Hop sourceCarrier = sourcePlan.getHopRef();
				CandidateDecisionReceipt receipt = captureConstructedChildSelection(formalRead,
					List.of(Pair.of(sourceCarrier.getHopID(), sourceOutput)), List.of(sourceCarrier), memoTable,
					capture, variantOrdinal);
				FederatedPlannerDpCostEstimator.ExactEstimator sourceEstimator =
					FederatedPlannerDpCostEstimator.bindExact(
						capture.context.analysis(), projected, memoTable);
				double cumulativeShare = sourceOutput == FederatedOutput.LOUT
					? sourceEstimator.cumulativeShare(sourcePlan)
					: sourceEstimator.foutCumulativeShare(sourcePlan);
				double localBoundaryShare = sourceOutput == FederatedOutput.FOUT
					? sharedLogicalFunctionInputDownloadShare(formalRead, source, sourcePlan,
						hopCommon, numOfWorkers, sourceEstimator)
					: 0.0;
				factArms.add(new SharedLogicalInputArm(fact, source, sourceOutput, sourcePlan,
					receipt, cumulativeShare, localBoundaryShare));
			}
			if(factArms.isEmpty())
				throw new DMLRuntimeException("No seeded DP source plan for shared function input "
					+ formalOccurrence.key().normalizedSignature() + " from "
					+ fact.sourceArgument().normalizedSignature());
			armsByFact.add(List.copyOf(factArms));
		}

		List<PlacementState> formalStates = capture.context.analysis().graph()
			.node(formalOccurrence.key()).orElseThrow().legalAlternatives();
		LinkedHashSet<FType> formalFoutTypes = new LinkedHashSet<>();
		for(PlacementState state : formalStates)
			if(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() != null)
				formalFoutTypes.add(state.fType());
		FType cpFoutType = formalFoutTypes.size() == 1 ? formalFoutTypes.iterator().next() : null;
		FederatedPlannerDpMemoTable.FedPlanVariants loutVariants =
			new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlanVariants foutVariants =
			new FederatedPlannerDpMemoTable.FedPlanVariants(hopCommon, FederatedOutput.FOUT);

		for(PlacementState formalState : formalStates) {
			if(!isLegalTransientReadState(formalState))
				continue;
			FederatedPlannerDpMemoTable.FedPlanVariants variants =
				formalState.output() == FederatedOutput.LOUT ? loutVariants : foutVariants;
			enumerateSharedLogicalInputRepresentativePlans(armsByFact, formalState,
				variants, memoTable, cpFoutType, baseSelfCost);
		}

		boolean hasLout = loutVariants.pruneExactBoundaryRepresentatives();
		boolean hasFout = foutVariants.pruneExactBoundaryRepresentatives();
		if(!hasLout && !hasFout)
			throw new DMLRuntimeException("No common exact DP placement can satisfy every caller of shared function input "
				+ formalOccurrence.key().normalizedSignature());
		if(hasLout)
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), formalOccurrence,
				FederatedOutput.LOUT, loutVariants);
		if(hasFout)
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), formalOccurrence,
				FederatedOutput.FOUT, foutVariants);
		return true;
	}

	private static void enumerateSharedLogicalInputRepresentativePlans(
		List<List<SharedLogicalInputArm>> armsByFact, PlacementState formalState,
		FederatedPlannerDpMemoTable.FedPlanVariants variants,
		FederatedPlannerDpMemoTable memoTable, FType cpFoutType, double baseSelfCost) {
		List<List<SharedLogicalInputArm>> compatible = new ArrayList<>(armsByFact.size());
		List<SharedLogicalInputArm> baseline = new ArrayList<>(armsByFact.size());
		for(List<SharedLogicalInputArm> factArms : armsByFact) {
			List<SharedLogicalInputArm> allowed = factArms.stream()
				.filter(arm -> supportsSharedFormalState(arm, formalState))
				.sorted(Comparator.comparingDouble((SharedLogicalInputArm arm) -> arm.costFor(formalState))
					.thenComparing(arm -> arm.sourceOutput().name()))
				.toList();
			if(allowed.isEmpty())
				return;
			compatible.add(allowed);
			baseline.add(allowed.get(0));
		}

		addSharedLogicalInputPlan(baseline, formalState, variants, memoTable,
			cpFoutType, baseSelfCost);
		// Preserve the cheapest representative for every individual caller boundary
		// state.  This is the DP factorized frontier: it avoids the 2^N Cartesian
		// materialization of independent call sites while ensuring no single later
		// boundary lock loses its legal LOUT/FOUT alternative.
		for(int factIndex = 0; factIndex < compatible.size(); factIndex++)
			for(SharedLogicalInputArm arm : compatible.get(factIndex)) {
				if(arm == baseline.get(factIndex))
					continue;
				List<SharedLogicalInputArm> representative = new ArrayList<>(baseline);
				representative.set(factIndex, arm);
				addSharedLogicalInputPlan(representative, formalState, variants,
					memoTable, cpFoutType, baseSelfCost);
			}
		for(FederatedOutput uniformOutput : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
			List<SharedLogicalInputArm> uniform = new ArrayList<>(compatible.size());
			for(List<SharedLogicalInputArm> factArms : compatible) {
				SharedLogicalInputArm arm = factArms.stream()
					.filter(candidate -> candidate.sourceOutput() == uniformOutput).findFirst().orElse(null);
				if(arm == null) {
					uniform.clear();
					break;
				}
				uniform.add(arm);
			}
			if(!uniform.isEmpty())
				addSharedLogicalInputPlan(uniform, formalState, variants,
					memoTable, cpFoutType, baseSelfCost);
		}
	}

	private static void addSharedLogicalInputPlan(List<SharedLogicalInputArm> selectedArms,
		PlacementState formalState, FederatedPlannerDpMemoTable.FedPlanVariants variants,
		FederatedPlannerDpMemoTable memoTable, FType cpFoutType, double baseSelfCost) {
		List<Pair<Long,FederatedOutput>> childEdges = new ArrayList<>(selectedArms.size());
		List<Hop> childCarriers = new ArrayList<>(selectedArms.size());
		List<FederatedPlannerDpMemoTable.FedPlan> childPlans = new ArrayList<>(selectedArms.size());
		double embedded = 0d;
		double boundary = 0d;
		for(SharedLogicalInputArm arm : selectedArms) {
			double armCost = arm.costFor(formalState);
			embedded += armCost;
			if(formalState.execType() == ExecType.CP)
				boundary += arm.localBoundaryShare();
			childEdges.add(Pair.of(arm.source().getHopID(), arm.sourceOutput()));
			childCarriers.add(arm.source());
			childPlans.add(arm.sourcePlan());
		}
		FederatedPlannerDpMemoTable.FedPlan plan = new FederatedPlannerDpMemoTable.FedPlan(
			baseSelfCost + embedded, variants, childEdges);
		plan.bindExactChildPlanEdges(childCarriers, childPlans, memoTable);
		plan.setExecType(formalState.execType());
		plan.setFType(formalState.fType());
		plan.setCpFoutType(formalState.output() == FederatedOutput.FOUT
			? formalState.fType() : cpFoutType);
		plan.setSelectedPlacementState(formalState);
		plan.setExactRecurrenceCosts(embedded, boundary);
		variants.addFedPlan(plan);
	}

	private static double sharedLogicalFunctionInputDownloadShare(DataOp formalRead, Hop source,
		FederatedPlannerDpMemoTable.FedPlan sourcePlan,
		FederatedPlannerDpMemoTable.HopCommon formalCommon, int numOfWorkers,
		FederatedPlannerDpCostEstimator.ExactEstimator sourceEstimator) {
		if(!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(source)
			|| sourcePlan.getExecType() == ExecType.CP)
			return 0.0;
		double bytes = FederatedCostModel.getEffectiveTransientReadSourceMemEstimate(formalRead, source);
		double download = sourceEstimator.download(bytes, sourcePlan.getFType(), numOfWorkers);
		return sourceEstimator.foutToCpShare(formalRead, download, sourcePlan, formalCommon);
	}

	private static boolean supportsSharedFormalState(SharedLogicalInputArm arm,
		PlacementState formalState) {
		boolean exactStateAllowed = arm.receipt().allowedEmissionFacts().stream()
			.anyMatch(fact -> fact.emissionState().placementState() == formalState);
		if(!exactStateAllowed)
			return false;
		if(formalState.execType() == ExecType.CP && formalState.output() == FederatedOutput.LOUT)
			return arm.sourceOutput() == FederatedOutput.LOUT
				|| arm.sourceOutput() == FederatedOutput.FOUT;
		return formalState.execType() == ExecType.FED && formalState.output() == FederatedOutput.FOUT
			&& arm.sourceOutput() == FederatedOutput.FOUT
			&& arm.sourcePlan().getFType() == formalState.fType();
	}

	private static boolean isLegalTransientReadState(PlacementState state) {
		return state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
			&& state.fType() == null
			|| state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
				&& state.fType() != null;
	}

	private static List<Hop> collectLogicalFunctionArgumentChildHops(DataOp formalRead,
		List<Hop> currentChildHops, EnumerationCapture capture) {
		HopOccurrenceProjection read = findOccurrence(capture, formalRead);
		List<LogicalFunctionInputFact> facts = logicalFunctionInputsForFormal(formalRead, capture);
		if(facts.isEmpty())
			return List.of();
		Map<CompiledHopKey,LogicalFunctionInputFact> factsBySource = new IdentityHashMap<>();
		for(LogicalFunctionInputFact fact : facts)
			if(factsBySource.put(fact.sourceArgument(), fact) != null)
				throw new IllegalArgumentException("Function-input DP source authority is duplicated");
		List<LogicalFunctionInputFact> activeFacts = new ArrayList<>();
		Set<CompiledHopKey> retained = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop child : currentChildHops) {
			HopOccurrenceProjection projected = capture.context.rewireSnapshot().projectExactCarrier(child);
			LogicalFunctionInputFact fact = projected == null ? null
				: factsBySource.get(projected.key());
			if(fact != null && retained.add(fact.sourceArgument()))
				activeFacts.add(fact);
		}
		if(activeFacts.size() > 1)
			throw new IllegalArgumentException("Function-input DP active source authority is not unique: "
				+ read.key().normalizedSignature());
		LogicalFunctionInputFact fact;
		if(activeFacts.size() == 1) {
			fact = activeFacts.get(0);
			capture.bindActiveFunctionCall(read.key().functionNamespace(),
				requireExactFunctionCallOwner(capture.context.analysis(), fact));
		}
		else {
			CompiledHopKey activeCall = capture.activeFunctionCall(read.key().functionNamespace());
			List<LogicalFunctionInputFact> callFacts = activeCall == null ? List.of()
				: facts.stream().filter(candidate ->
					requireExactFunctionCallOwner(capture.context.analysis(), candidate) == activeCall).toList();
			if(callFacts.size() == 1)
				fact = callFacts.get(0);
			else if(facts.size() == 1) {
				fact = facts.get(0);
				capture.bindActiveFunctionCall(read.key().functionNamespace(),
					requireExactFunctionCallOwner(capture.context.analysis(), fact));
			}
			else
				throw new IllegalArgumentException("Function-input DP active source authority is missing: "
					+ read.key().normalizedSignature());
		}
		Hop source = capture.context.analysis().hop(fact.sourceArgument()).orElseThrow(() ->
			new IllegalArgumentException("Function-input DP source Hop is missing"));
		HopOccurrenceProjection projected = capture.context.rewireSnapshot().projectExactCarrier(source);
		if(projected == null || projected.key() != fact.sourceArgument())
			throw new IllegalArgumentException("Function-input DP source carrier differs from analysis authority");
		return List.of(source);
	}

	private static CompiledHopKey requireExactFunctionCallOwner(PlacementAnalysis analysis,
		LogicalFunctionInputFact fact) {
		return analysis.requireExactPhysicalFunctionInputConsumer(fact);
	}

	private static void rejectAmbiguousTransientWriteHopIds(DataOp transientRead,
		List<Hop> transientWrites, EnumerationCapture capture) {
		Map<Long, Hop> firstById = new LinkedHashMap<>();
		for(Hop candidate : transientWrites) {
			Hop previous = firstById.putIfAbsent(candidate.getHopID(), candidate);
			if(previous == null || previous == candidate)
				continue;
			if(capture == null)
				throw new DMLRuntimeException("Ambiguous transient-write child hop " + candidate.getHopID());
			HopOccurrenceProjection parent = findOccurrence(capture, transientRead);
			throw new DpSemanticConstructionException(
				DpPlacementAdapter.ConstructionDisposition.DUPLICATE_OCCURRENCE,
				capture.context.analysis().analysisFingerprint(), parent.key(), "DUPLICATE_OCCURRENCE");
		}
	}

	private static boolean canTransientReadReuseMatchedFoutWrite(DataOp transientRead,
			FederatedPlannerDpMemoTable.FedPlan tWritePlan,
			FederatedPlannerDpMemoTable memoTable, EnumerationCapture capture) {
		if (transientRead == null || tWritePlan == null || memoTable == null)
			return false;
		Hop tWriteCarrier = tWritePlan.getHopRef();
		Hop hopRef = memoTable.resolveOriginalHop(tWriteCarrier.getHopID());
		if (!(hopRef instanceof DataOp) || ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE)
			return true;
		DataOp tWrite = (DataOp) hopRef;
		List<Hop> inputs = tWrite.getInput();
		Hop input = (inputs != null && !inputs.isEmpty()) ? inputs.get(0) : null;
		if (input == null)
			return true;
		if (FederatedPlannerUtils.hasConcreteMatchedWriteReuseSource(input, tWrite.getName()))
			return true;
		// A compiler-generated formal TRead -> TWrite binding is an identity alias of
		// the caller value.  Its direct input necessarily has the same variable name,
		// so the generic self-dependency guard below would otherwise discard the exact
		// FED/FOUT arm and leave later body reads CP-only.  Admit this one case only when
		// the immutable neutral graph proves the exact carrier pair with the dedicated
		// SAME_PLACEMENT contract; an arithmetic/update TWrite has no such proof and
		// remains rejected.  This expands a graph-proven runtime capability rather than
		// introducing a fallback or weakening TRead/TWrite equality.
		if(isExactTransparentFunctionInputBinding(tWriteCarrier, input, capture))
			return true;
		// A compiler-generated TWrite(v) <- TRead(v) carry is also a pure alias,
		// but unlike the formal-input binding it can sit between loop/branch CFG
		// regions.  Accept its FOUT arm only when the selected exact write plan
		// consumes the exact read occurrence as FOUT and that read is itself backed
		// by analysis-owned logical transient sources with the same layout.  This
		// proves a grounded placement chain and does not admit an unseeded self-cycle.
		if(isExactTransparentTransientCopyBinding(tWritePlan, input, capture))
			return true;
		return !dependsOnSameTransientRead(input, tWrite.getName(), new HashSet<>());
	}

	private static boolean isExactTransparentFunctionInputBinding(Hop tWriteCarrier, Hop input,
		EnumerationCapture capture) {
		if(capture == null)
			return false;
		RewireOccurrenceSnapshot snapshot = capture.context.rewireSnapshot();
		HopOccurrenceProjection write = snapshot.projectExactCarrier(tWriteCarrier);
		HopOccurrenceProjection read = snapshot.projectExactCarrier(input);
		if(write == null || read == null)
			return false;
		return capture.context.analysis().graph().constraints().stream().anyMatch(constraint ->
			constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& "function-input-binding".equals(constraint.evidence())
				&& constraint.left() == read.key() && constraint.right() == write.key());
	}

	private static boolean isExactTransparentTransientCopyBinding(
		FederatedPlannerDpMemoTable.FedPlan writePlan, Hop input, EnumerationCapture capture) {
		if(capture == null || writePlan == null || !(writePlan.getHopRef() instanceof DataOp)
			|| !(input instanceof DataOp))
			return false;
		DataOp writeHop = (DataOp) writePlan.getHopRef();
		DataOp readHop = (DataOp) input;
		PlacementState writeState = writePlan.getSelectedPlacementState();
		if(writeHop.getOp() != Types.OpOpData.TRANSIENTWRITE
			|| readHop.getOp() != Types.OpOpData.TRANSIENTREAD
			|| writeHop.getInput().size() != 1 || writeHop.getInput(0) != readHop
			|| !Objects.equals(writeHop.getName(), readHop.getName())
			|| writeState == null || writeState.execType() != ExecType.FED
			|| writeState.output() != FederatedOutput.FOUT || writeState.fType() == null)
			return false;
		RewireOccurrenceSnapshot snapshot = capture.context.rewireSnapshot();
		HopOccurrenceProjection write = snapshot.projectExactCarrier(writeHop);
		HopOccurrenceProjection read = snapshot.projectExactCarrier(readHop);
		if(write == null || read == null)
			return false;
		PlacementAnalysis analysis = capture.context.analysis();
		boolean exactPhysicalInput = analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge ->
			edge.producer() == read.key() && edge.consumer() == write.key() && edge.inputPosition() == 0);
		if(!exactPhysicalInput)
			return false;
		List<LogicalTransientInputFact> logicalSources = analysis.logicalTransientInputsInCanonicalOrder()
			.stream().filter(fact -> fact.targetRead() == read.key() && fact.logicalPosition() == 0).toList();
		if(logicalSources.isEmpty() || logicalSources.stream().anyMatch(fact ->
			fact.federatedFType() != writeState.fType()
				|| fact.federatedSourceState().execType() != ExecType.FED
				|| fact.federatedSourceState().output() != FederatedOutput.FOUT))
			return false;
		return writePlan.getExactChildPlanEdges().stream().anyMatch(edge ->
			edge.occurrence() == read.key() && edge.carrier() == readHop
				&& edge.output() == FederatedOutput.FOUT
				&& writeState.equals(edge.selectedPlan().getSelectedPlacementState()));
	}

	private static boolean hasFederatedTransientInputAuthority(DataOp transientRead, Hop sourceHop,
		EnumerationCapture capture) {
		if(transientRead == null || sourceHop == null || capture == null)
			return false;
		HopOccurrenceProjection read = findOccurrence(capture, transientRead);
		HopOccurrenceProjection source = capture.context.rewireSnapshot().projectExactCarrier(sourceHop);
		if(source == null)
			return false;
		PlacementAnalysis analysis = capture.context.analysis();
		boolean physical = analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(fact ->
			fact.producer() == source.key() && fact.consumer() == read.key());
		boolean logicalTransient = analysis.logicalTransientInputsInCanonicalOrder().stream().anyMatch(fact ->
			fact.sourceWrite() == source.key() && fact.targetRead() == read.key()
				&& fact.logicalPosition() == 0);
		boolean logicalFunction = analysis.logicalFunctionInputsInCanonicalOrder().stream().anyMatch(fact ->
			fact.sourceArgument() == source.key() && fact.targetRead() == read.key()
				&& fact.logicalPosition() == 0);
		return physical || logicalTransient || logicalFunction;
	}

	private static boolean dependsOnSameTransientRead(Hop hop, String varName, Set<Long> visited) {
		if (hop == null || varName == null || varName.isEmpty() || visited == null || !visited.add(hop.getHopID()))
			return false;
		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			if (dataOp.getOp() == Types.OpOpData.TRANSIENTREAD && varName.equals(dataOp.getName()))
				return true;
		}
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty())
			return false;
		for (Hop input : inputs) {
			if (dependsOnSameTransientRead(input, varName, visited))
				return true;
		}
		return false;
	}


	// Keep selection object-owned: distinct physical writes may share a Hop ID, so
	// IDs are derived only after exact TRead capture no longer needs object identity.
	private static List<Hop> collectTransientWriteChildHops(Hop hop, List<Hop> childHops) {
		List<Hop> transientWrites = new ArrayList<>();
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD) {
			return transientWrites;
		}
		if (childHops == null || childHops.isEmpty()) {
			return transientWrites;
		}
		for (Hop childHop : childHops) {
			if (!(childHop instanceof DataOp)
					|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTWRITE) {
				continue;
			}
			transientWrites.add(childHop);
		}
		return transientWrites;
	}

	private static List<Hop> collectTransientWriteChildHops(Hop hop, List<Hop> childHops,
		EnumerationCapture capture) {
		List<Hop> transientWrites = collectTransientWriteChildHops(hop, childHops);
		if(capture == null || transientWrites.isEmpty())
			return transientWrites;

		// A legacy rewire forward is sufficient to enumerate the producer before the
		// read, but it is not necessarily the read's logical value source. In particular,
		// a TRead after a loop can have several reaching TWrite definitions while the
		// rewire table exposes only the last physical carrier. Turning that scheduling
		// edge into an executable plan child incorrectly binds the read to one definition
		// and can force CP/LOUT against an independently selected FED/FOUT writer.
		// Only analysis-owned physical/logical/function input facts may enter a TRead arm.
		List<Hop> exactWrites = new ArrayList<>();
		for(Hop transientWrite : transientWrites)
			if(hasExactTransientInputAuthority(hop, transientWrite, capture))
				exactWrites.add(transientWrite);
		return exactWrites;
	}

	private static boolean hasExactTransientInputAuthority(Hop readHop, Hop sourceHop,
		EnumerationCapture capture) {
		if(!(readHop instanceof DataOp) || capture == null || sourceHop == null)
			return false;
		return hasFederatedTransientInputAuthority((DataOp) readHop, sourceHop, capture);
	}

	// Rewire forwards are scheduling dependencies, not automatic candidate inputs. Only an exact
	// snapshot-owned edge may enter adapter normalization; the dependency never changes the read's
	// neutral candidate domain or manufactures a federated input.
	private static boolean isTransientForwardCandidateCarrier(Hop readHop, Hop writeHop,
		EnumerationCapture capture) {
		if(capture == null)
			throw new IllegalStateException("Transient candidate carrier requires exact neutral capture");
		RewireOccurrenceSnapshot snapshot = capture.context.rewireSnapshot();
		HopOccurrenceProjection read = findOccurrence(capture, readHop);
		HopOccurrenceProjection write = snapshot.projectExactCarrier(writeHop);
		if(write == null)
			return false;
		PlacementAnalysis analysis = capture.context.analysis();
		long physicalOwners = analysis.compiledInputEdgesInCanonicalOrder().stream().filter(fact ->
			fact.producer() == write.key() && fact.consumer() == read.key()).count();
		if(physicalOwners > 0)
			return true;
		long logicalOwners = analysis.logicalTransientInputsInCanonicalOrder().stream().filter(fact ->
			fact.sourceWrite() == write.key() && fact.targetRead() == read.key() && fact.logicalPosition() == 0).count();
		if(logicalOwners > 1)
			throw new IllegalArgumentException("Transient candidate carrier has duplicate logical ownership");
		if(logicalOwners == 1)
			return true;
		long functionOwners = analysis.logicalFunctionInputsInCanonicalOrder().stream().filter(fact ->
			fact.sourceArgument() == write.key() && fact.targetRead() == read.key()
				&& fact.logicalPosition() == 0).count();
		if(functionOwners > 1)
			throw new IllegalArgumentException("Function-input candidate carrier has duplicate logical ownership");
		if(functionOwners == 1)
			return true;
		long forwardOwners = snapshot.transientForwardEdges().stream().filter(edge ->
			edge.writeOccurrence() == write.key() && edge.readOccurrence() == read.key()).count();
		if(forwardOwners > 1)
			throw new IllegalArgumentException("Transient candidate carrier has duplicate forward ownership");
		return forwardOwners == 1;
	}

	private static Set<Long> collectHopIds(List<Hop> hops) {
		Set<Long> hopIds = new LinkedHashSet<>();
		for (Hop hop : hops)
			hopIds.add(hop.getHopID());
		return hopIds;
	}

	private static boolean isRecompileRegion(Hop hop) {
		if (hop == null)
			return false;
		if (hop.requiresRecompile())
			return true;
		List<Hop> inputs = hop.getInput();
		if (inputs == null)
			return false;
		for (Hop in : inputs) {
			if (in != null && in.requiresRecompile())
				return true;
		}
		return false;
	}

	private static boolean isTReadConsistentWithTWrite(ExecType execType, FederatedOutput fedOutType,
			ExecType tWriteExec, FederatedOutput tWriteOut) {
		if (tWriteExec == null || tWriteOut == null) {
			return false;
		}
		if (tWriteExec == ExecType.CP && tWriteOut == FederatedOutput.LOUT) {
			return execType == ExecType.CP && fedOutType == FederatedOutput.LOUT;
		}
		if (tWriteExec == ExecType.FED && tWriteOut == FederatedOutput.FOUT) {
			return execType == ExecType.FED && fedOutType == FederatedOutput.FOUT;
		}
		return false;
	}

	private static boolean isTransientWriteFoutMetadataPassThrough(Hop hop,
			List<Pair<Long, FederatedOutput>> planChilds) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTWRITE)
			return false;
		if (planChilds == null || planChilds.isEmpty())
			return false;
		for (Pair<Long, FederatedOutput> childEdge : planChilds) {
			if (childEdge == null || childEdge.getRight() != FederatedOutput.FOUT)
				return false;
		}
		return true;
	}

	private static double placementTransferWeight(FederatedPlannerDpMemoTable.HopCommon hopCommon) {
		if (hopCommon == null)
			return 1.0;
		return hopCommon.getComputeWeight() * hopCommon.getMultiplicity();
	}


	private record FedEntryCost(double fedSelfCost, double resultDownloadCost, double uploadCost) { }

	private static FedEntryCost computeFedEntryCost(Hop hop, List<Hop> exactCollectedHops,
		List<FType> effectiveCollectedFTypes, FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator,
		double baseSelfCost, double outputMemEstimate, double uploadMemEstimate, double fedOverhead,
		double singleWorkerFedPenalty, double fedExecWeight, double hopPlacementWeight, int numOfWorkers,
		FType executionFType, FType materializationFType) {
		boolean broadcastOnlyFedCompute = FederatedCostModel.hasOnlyBroadcastMatrixInputs(
			exactCollectedHops, effectiveCollectedFTypes);
		double defaultFedComputeCost = exactEstimator.computeFederatedHopCost(
			hop, baseSelfCost, numOfWorkers, broadcastOnlyFedCompute);
		double genericResultDownloadCost = exactEstimator.download(outputMemEstimate, executionFType, numOfWorkers);
		double nativeAggUnaryResultDownloadCost =
			FederatedCostModel.computeNativeFederatedAggregateUnaryLoutResultCost(
				hop, executionFType, outputMemEstimate, numOfWorkers, genericResultDownloadCost);
		double nativeResultDownloadCost = FederatedCostModel.computeNativeFederatedAggBinaryLoutResultCost(
			hop, executionFType, outputMemEstimate, numOfWorkers, nativeAggUnaryResultDownloadCost);
		nativeResultDownloadCost = exactEstimator.nativeFederatedLoutResultCost(
			hop, outputMemEstimate, numOfWorkers, nativeResultDownloadCost);
		FederatedCostModel.MixedFedLocalCost mixedFedLocalCost = FederatedCostModel.computeMixedFedLocalCost(
			hop, exactCollectedHops, effectiveCollectedFTypes, executionFType, baseSelfCost, outputMemEstimate,
			numOfWorkers);
		double nativeAggUnaryFedComputeCost = FederatedCostModel.computeNativeFederatedAggregateUnaryCost(
			hop, executionFType, defaultFedComputeCost);
		nativeAggUnaryFedComputeCost = FederatedCostModel.computeNativeFederatedIndexingCost(
			hop, executionFType, nativeAggUnaryFedComputeCost);
		double fedComputeCost = mixedFedLocalCost.hasFederatedComputeFloor()
			? Math.max(nativeAggUnaryFedComputeCost, mixedFedLocalCost.getFederatedComputeFloor())
			: nativeAggUnaryFedComputeCost;
		double resultDownloadCost = hopPlacementWeight * (mixedFedLocalCost.hasCoordinatorPhase()
			? mixedFedLocalCost.getCoordinatorPhaseCost() : nativeResultDownloadCost);
		double fedSelfCost = fedComputeCost + FederatedCostModel.adjustFedCoordinationCost(hop, executionFType,
			fedOverhead) + singleWorkerFedPenalty
			+ FederatedCostModel.computeControlDominatedFederatedInstructionCost(hop, executionFType,
				fedExecWeight, numOfWorkers, broadcastOnlyFedCompute)
			+ hopPlacementWeight * mixedFedLocalCost.getInputPreparationCost();
		double uploadCost = hopPlacementWeight
			* exactEstimator.upload(uploadMemEstimate, materializationFType, numOfWorkers);
		return new FedEntryCost(fedSelfCost, resultDownloadCost, uploadCost);
	}

	private static double derivedFedFoutBoundaryCost(boolean derivedFedFout, double cpUploadCost,
			double resultDownloadCost) {
		return derivedFedFout ? cpUploadCost + resultDownloadCost : 0.0;
	}

	private static FType resolveLoutLogicalFType(FType oracleLogicalFType) {
		return oracleLogicalFType;
	}

	private static HopOccurrenceProjection findOccurrence(EnumerationCapture capture, Hop hop) {
		if(capture == null || hop == null)
			throw new IllegalArgumentException("Missing supplied-analysis Hop occurrence");
		HopOccurrenceProjection occurrence = capture.context.rewireSnapshot().projectExactCarrier(hop);
		if(occurrence != null)
			return occurrence;
		throw new ParentCarrierProjectionException(
			capture.context.analysis().analysisFingerprint(), hop.getHopID());
	}

	private static String formatSelectedBits(int[] bits) {
		if (bits == null || bits.length == 0)
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < bits.length; i++) {
			if (i > 0)
				sb.append(",");
			sb.append(bits[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	private static boolean canSatisfyFederatedInputsFromFTypes(CandidateDecisionReceipt receipt,
		Map<Long, FType> effectiveNonNullFTypeMap) {
		if(!(receipt.allowFEDLOUT() || receipt.allowFEDFOUT()))
			return false;
		if(effectiveNonNullFTypeMap != null && !effectiveNonNullFTypeMap.isEmpty())
			return true;
		return receipt.context().analysis().graph().node(receipt.candidateSnapshot().parentOccurrence())
			.map(node -> node.anchors().size() == 1 && node.anchors().get(0).fType() != null
				&& node.anchors().get(0).fType() != FType.PART && node.anchors().get(0).fType() != FType.OTHER)
			.orElse(false);
	}

	private static void logDpBestPlans(Hop hop,
			FederatedPlannerDpMemoTable.FedPlanVariants lOutFedPlanVariants,
			FederatedPlannerDpMemoTable.FedPlanVariants fOutFedPlanVariants,
			boolean hasLOutPlan, boolean hasFOutPlan) {
		if (!FederatedPlannerTrace.shouldTrace(hop))
			return;

		String loutSummary = "none";
		if (hasLOutPlan && lOutFedPlanVariants != null
				&& !lOutFedPlanVariants.getFedPlanVariants().isEmpty()) {
			FederatedPlannerDpMemoTable.FedPlan plan = lOutFedPlanVariants.getFedPlanVariants().get(0);
			loutSummary = String.format(Locale.ROOT, "exec=%s,fType=%s,cost=%.6f",
					plan.getExecType(), plan.getFType(), plan.getCumulativeCost());
		}

		String foutSummary = "none";
		if (hasFOutPlan && fOutFedPlanVariants != null
				&& !fOutFedPlanVariants.getFedPlanVariants().isEmpty()) {
			FederatedPlannerDpMemoTable.FedPlan plan = fOutFedPlanVariants.getFedPlanVariants().get(0);
			foutSummary = String.format(Locale.ROOT, "exec=%s,fType=%s,cost=%.6f,derived=%s",
					plan.getExecType(), plan.getFType(), plan.getCumulativeCost(), plan.isDerivedFedFout());
		}

			FederatedPlannerTrace.log(hop, "DP-Selected",
					"bestLOUT={" + loutSummary + "} bestFOUT={" + foutSummary + "}");
		}

		private static void populateParentChildUploadHintsFromRewire(
				Map<Long, Set<Long>> parentChildUploadHints,
				Map<Long, List<Hop>> rewireTable,
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
			if (parentChildUploadHints == null || rewireTable == null || rewireTable.isEmpty()
					|| hopCommonTable == null || hopCommonTable.isEmpty())
				return;

		for (FederatedPlannerDpMemoTable.HopCommon hopCommon : hopCommonTable.values()) {
			if (hopCommon == null)
				continue;
			Hop parentHop = hopCommon.getHopRef();
			if (parentHop == null || parentHop.getInput() == null || parentHop.getInput().isEmpty())
				continue;

			for (Hop inputHop : parentHop.getInput()) {
				if (!(inputHop instanceof DataOp) ||
						((DataOp) inputHop).getOp() != Types.OpOpData.TRANSIENTREAD)
					continue;
				List<Hop> transReadSources = rewireTable.get(inputHop.getHopID());
				if (transReadSources == null || transReadSources.isEmpty())
					continue;

				boolean hasTransientWriteSource = false;
				for (Hop sourceHop : transReadSources) {
					if (sourceHop instanceof DataOp &&
							((DataOp) sourceHop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
						hasTransientWriteSource = true;
						break;
					}
				}
				if (!hasTransientWriteSource)
					continue;

				TransTableRewireUtils.markParentChildUploadHint(
						parentChildUploadHints, parentHop.getHopID(), inputHop.getHopID());
			}
		}
	}

	private static int[] resolveParentInputIndices(Hop parentHop, List<Hop> inputHops) {
		int[] indices = new int[inputHops == null ? 0 : inputHops.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = -1;
		}
		if (parentHop == null || parentHop.getInput() == null || inputHops == null || inputHops.isEmpty())
			return indices;

		List<Hop> parentInputs = parentHop.getInput();
		Map<Long, Integer> nextSearchStart = new HashMap<>();
		for (int i = 0; i < inputHops.size(); i++) {
			Hop inputHop = inputHops.get(i);
			if (inputHop == null)
				continue;
			long hopID = inputHop.getHopID();
			int start = nextSearchStart.getOrDefault(hopID, 0);
			int found = -1;
			for (int j = start; j < parentInputs.size(); j++) {
				Hop parentInput = parentInputs.get(j);
				if (parentInput != null && parentInput.getHopID() == hopID) {
					found = j;
					break;
				}
			}
			if (found < 0 && start > 0) {
				for (int j = 0; j < start && j < parentInputs.size(); j++) {
					Hop parentInput = parentInputs.get(j);
					if (parentInput != null && parentInput.getHopID() == hopID) {
						found = j;
						break;
					}
				}
			}
			indices[i] = found;
			if (found >= 0)
				nextSearchStart.put(hopID, found + 1);
		}
		return indices;
	}

	private static boolean shouldAddFedForwardingForParentInput(Hop parentHop, Hop inputHop, int inputIndex,
			Map<Long, FType> inputFTypes, Map<Long, Set<Long>> parentChildUploadHints) {
		if (inputHop == null || inputHop.getDataType() == null)
			return false;
		// Even OPTIONAL inputs need to be transferred to FED sites for FED execution (e.g., broadcast vectors).
		// Non-matrix inputs are treated as embedded literals and do not incur separate forwarding costs.
		return inputHop.getDataType().isMatrix();
	}

	// Creates a dummy root node (fedplan) and selects the FedPlan with the minimum
	// cost to return.
	// The dummy root node does not have LOUT or FOUT.
	private record RootChoice(Hop rootHop, FederatedPlannerDpMemoTable.FedPlan selectedPlan,
		FederatedOutput selectedOutput, String occurrenceSignature, long selectedCostBits) { }

	private static FederatedPlannerDpMemoTable.FedPlan getMinCostRootFedPlan(Set<Hop> progRootHopSet,
			FederatedPlannerDpMemoTable memoTable) {
		List<RootChoice> rootChoices = new ArrayList<>();

		// Iterate over each Hop in the progRootHopSet and preserve the existing independent LOUT/FOUT rule.
		for (Hop endHop : progRootHopSet) {
			// Retrieve the pruned FedPlan for LOUT and FOUT from the memo table
			FederatedPlannerDpMemoTable.FedPlan lOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(),
					FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan fOutFedPlan = memoTable.getFedPlanAfterPrune(endHop.getHopID(),
					FederatedOutput.FOUT);

			if (lOutFedPlan == null && fOutFedPlan == null) {
				FederatedPlannerLogger.logWarnMessage(
						"[Planner] Skipping root hop " + endHop.getHopID() + " (" + endHop.getOpString()
								+ ") because no federated plan variants are available");
				continue;
			}

			FederatedPlannerDpMemoTable.FedPlan selectedPlan;
			FederatedOutput selectedOutput;
			if (fOutFedPlan == null) {
				selectedPlan = lOutFedPlan;
				selectedOutput = FederatedOutput.LOUT;
			} else if (lOutFedPlan == null) {
				selectedPlan = fOutFedPlan;
				selectedOutput = FederatedOutput.FOUT;
			} else {
				// Compare the cumulative costs of LOUT and FOUT FedPlans
				if (lOutFedPlan.getCumulativeCost() <= fOutFedPlan.getCumulativeCost()) {
					selectedPlan = lOutFedPlan;
					selectedOutput = FederatedOutput.LOUT;
				} else {
					selectedPlan = fOutFedPlan;
					selectedOutput = FederatedOutput.FOUT;
				}
			}
			String occurrenceSignature = rootOccurrenceSignature(memoTable, selectedPlan.getHopRef());
			rootChoices.add(new RootChoice(endHop, selectedPlan, selectedOutput, occurrenceSignature,
				Double.doubleToRawLongBits(selectedPlan.getCumulativeCost())));
		}

		// HashSet root traversal can permute identical semantic roots and shift the IEEE-754 root objective by 1 ULP.
		// Key-only ordering is insufficient because B-05 has duplicate full CompiledHopKey + output roots with
		// different selected costs.  Cost bits order already-selected root terms only; they do not alter candidate
		// legality or the per-root LOUT/FOUT selection rule above.  Raw Hop ids/identity hashes remain excluded.
		rootChoices.sort(Comparator.comparing(RootChoice::occurrenceSignature)
			.thenComparing(RootChoice::selectedOutput)
			.thenComparingLong(RootChoice::selectedCostBits));

		double cumulativeCost = 0;
		List<Pair<Long, FederatedOutput>> rootFedPlanChilds = new ArrayList<>();
		List<Hop> exactRootCarriers = new ArrayList<>();
		List<FederatedPlannerDpMemoTable.FedPlan> exactRootPlans = new ArrayList<>();
		for (RootChoice choice : rootChoices) {
			cumulativeCost += choice.selectedPlan().getCumulativeCost();
			rootFedPlanChilds.add(Pair.of(choice.rootHop().getHopID(), choice.selectedOutput()));
			exactRootCarriers.add(choice.selectedPlan().getHopRef());
			exactRootPlans.add(choice.selectedPlan());
		}

		FederatedPlannerDpMemoTable.FedPlan rootPlan =
			new FederatedPlannerDpMemoTable.FedPlan(cumulativeCost, null, rootFedPlanChilds);
		rootPlan.bindExactChildPlanEdges(exactRootCarriers, exactRootPlans, memoTable);
		return rootPlan;
	}

	private static String rootOccurrenceSignature(FederatedPlannerDpMemoTable memoTable, Hop root) {
		return memoTable.analysis() == null ? ""
			: memoTable.requirePlanCarrierOccurrence(root).key().normalizedSignature();
	}

	private static void addUnreferencedTWriteRoots(Set<Hop> progRootHopSet, Set<Long> unRefTwriteSet,
				Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
		if (progRootHopSet == null || unRefTwriteSet == null || unRefTwriteSet.isEmpty() || hopCommonTable == null)
			return;
		for (long hopID : unRefTwriteSet) {
			FederatedPlannerDpMemoTable.HopCommon common = hopCommonTable.get(hopID);
			if (common == null || common.getHopRef() == null)
				continue;
			// Hidden loop-carried / transient body state still executes at runtime even when the
			// function caller does not expose it as a formal output. Treat these writers as
			// additional roots so dynamic-function call-site costs include the full body.
				progRootHopSet.add(common.getHopRef());
		}
	}

	private static List<Hop> collectUnreferencedExecutedRoots(Set<Long> unRefSet,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
		if (unRefSet == null || unRefSet.isEmpty() || hopCommonTable == null)
			return Collections.emptyList();
		List<Hop> additionalRoots = new ArrayList<>();
		LinkedHashSet<Long> seenHopIds = new LinkedHashSet<>();
		for (long hopID : unRefSet) {
			FederatedPlannerDpMemoTable.HopCommon common = hopCommonTable.get(hopID);
			if (common == null || common.getHopRef() == null)
				continue;
			Hop hopRef = common.getHopRef();
			if (hopRef instanceof LiteralOp || !seenHopIds.add(hopRef.getHopID()))
				continue;
			additionalRoots.add(hopRef);
		}
		return additionalRoots;
	}

	private static List<Hop> collectPredicateWriteRoots(
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable) {
		if (hopCommonTable == null || hopCommonTable.isEmpty())
			return Collections.emptyList();
		List<Hop> additionalRoots = new ArrayList<>();
		LinkedHashSet<Long> seenHopIds = new LinkedHashSet<>();
		for (FederatedPlannerDpMemoTable.HopCommon common : hopCommonTable.values()) {
			if (common == null || common.getHopRef() == null)
				continue;
			Hop hopRef = common.getHopRef();
			if (!HopUtils.isPredTWrite(hopRef) || !seenHopIds.add(hopRef.getHopID()))
				continue;
			additionalRoots.add(hopRef);
		}
		return additionalRoots;
	}

	/**
	 * Detects and resolves federated placement conflicts in a single BFS + single
	 * resolve pass.
	 */
	public static double detectAndResolveConflictFedPlan(
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan, int numOfWorkers) {

		if (rootPlan == null)
			return 0.0;

		Map<Long, ConflictEntry> conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan);
		Map<Long, ConflictEntry> conflictMap = filterTrueConflicts(memoTable, conflictCheckMap);
		double additionalTotalCost = resolveAllConflictsSinglePass(memoTable, conflictMap, numOfWorkers);
		return additionalTotalCost;
	}

	/**
	 * Collect all parent usages of each hop via a single BFS traversal starting at
	 * the dummy root's children.
	 * Visitation is based on FedPlan object identity; we assume one FedPlan
	 * instance per (hopID, FederatedOutput).
	 */
	private static Map<Long, ConflictEntry> collectConflictsSingleBFS(
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan) {

		Map<Long, ConflictEntry> conflictCheckMap = new HashMap<>();
		Queue<FederatedPlannerDpMemoTable.FedPlan> queue = new ArrayDeque<>();
		Set<FederatedPlannerDpMemoTable.FedPlan> visited = new HashSet<>();

		for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(rootChild);
			if (childPlan == null) {
				String msg = "NULL FedPlan for root child hop " + rootChild.getKey();
				if (OptimizerUtils.isStrictFederatedConflictCheck())
					throw new DMLRuntimeException(msg);
				else
					FederatedPlannerLogger.logNullFedPlanError(rootChild.getKey(), msg);
				continue;
			}
			queue.add(childPlan);
		}

		while (!queue.isEmpty()) {
			FederatedPlannerDpMemoTable.FedPlan current = queue.poll();
			if (!visited.add(current))
				continue;

			Hop currentHop = current.getHopRef();

			for (FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge childEdge
				: current.getExactChildPlanEdges()) {
				long childHopID = childEdge.carrier().getHopID();
				long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
				FederatedOutput childOut = childEdge.output();

				FederatedPlannerDpMemoTable.FedPlan childPlan = childEdge.selectedPlan();
				if (childPlan == null) {
					String msg = "NULL FedPlan for hop " + childHopID
							+ " as child of hop " + (currentHop != null ? currentHop.getHopID() : -1)
							+ " (" + (currentHop != null ? currentHop.getOpString() : "null") + ")";
					if (OptimizerUtils.isStrictFederatedConflictCheck())
						throw new DMLRuntimeException(msg);
					else
						FederatedPlannerLogger.logNullFedPlanError(childHopID, msg);
					continue;
				}

				ConflictEntry entry = conflictCheckMap.get(childOrigHopID);
				if (entry == null) {
					conflictCheckMap.put(childOrigHopID, new ConflictEntry(childOut, current, childHopID));
				} else {
					entry.addUsage(childOut, current, childHopID);
				}

				queue.add(childPlan);
			}
		}

		return conflictCheckMap;
	}

	private static Map<Long, ConflictEntry> filterTrueConflicts(
			FederatedPlannerDpMemoTable memoTable, Map<Long, ConflictEntry> conflictCheckMap) {

		Map<Long, ConflictEntry> conflictMap = new LinkedHashMap<>();

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();

			if (!entry.isTrulyConflicting())
				continue;

			boolean canChooseLOUT = true;
			boolean canChooseFOUT = true;
			for (long memberHopID : entry.memberHopIDs) {
				if (memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT) == null)
					canChooseLOUT = false;
				if (memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.FOUT) == null)
					canChooseFOUT = false;
			}
			entry.canChooseLOUT = canChooseLOUT;
			entry.canChooseFOUT = canChooseFOUT;

			if (!canChooseLOUT && !canChooseFOUT) {
				String msg = "Federated placement conflict on hop " + hopID
						+ " but neither LOUT nor FOUT is available across all clones (clones=" + entry.memberHopIDs + ")";
				if (OptimizerUtils.isStrictFederatedConflictCheck())
					throw new DMLRuntimeException(msg);
				else
					FederatedPlannerLogger.logWarnMessage("[Planner] " + msg);
				continue;
			}

			conflictMap.put(hopID, entry);
		}

		return conflictMap;
	}

	/**
	 * Resolves placement conflicts independently per hopID without recomputing
	 * FedPlan costs.
	 * Assumes per-hop LOUT/FOUT costs are independent across conflicts and across
	 * the DAG.
	 * Only childFedPlans edges are mutated; the returned cost is an estimate
	 * aggregated over conflicts.
	 */
	private static double resolveAllConflictsSinglePass(
			FederatedPlannerDpMemoTable memoTable, Map<Long, ConflictEntry> conflictMap, int numOfWorkers) {

		double totalAdditionalCost = 0.0;

		if (!conflictMap.isEmpty()) {
			FederatedPlannerLogger.logInfoMessage("[Planner] Resolving "
					+ conflictMap.size() + " federated placement conflicts");
		}

		for (Map.Entry<Long, ConflictEntry> e : conflictMap.entrySet()) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();
			totalAdditionalCost += resolveOneHopConflict(memoTable, hopID, entry, numOfWorkers);
		}

		return totalAdditionalCost;
	}

	private static double resolveOneHopConflict(
			FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry, int numOfWorkers) {
		final boolean canChooseLOUT = entry.canChooseLOUT;
		final boolean canChooseFOUT = entry.canChooseFOUT;

		double lOutAdditionalCost = canChooseLOUT ? 0.0 : Double.POSITIVE_INFINITY;
		double fOutAdditionalCost = canChooseFOUT ? 0.0 : Double.POSITIVE_INFINITY;

		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
			List<Pair<Integer, Pair<Long, FederatedOutput>>> childEdges = findChildEdges(memoTable, parentPlan, hopID);
			if (childEdges.isEmpty()) {
				String msg = "Parent plan for hop " + hopID + " lost its child edge.";
				if (OptimizerUtils.isStrictFederatedConflictCheck())
					throw new DMLRuntimeException(msg);
				else {
					Hop parentHop = parentPlan.getHopRef();
					FederatedPlannerLogger.logWarnMessage("[Planner] " + msg
							+ " parentHop=" + (parentHop != null ? parentHop.getHopID() : -1));
				}
				continue;
			}

			for (Pair<Integer, Pair<Long, FederatedOutput>> edgeEntry : childEdges) {
				long childHopID = edgeEntry.getValue().getKey();
				FederatedOutput originalOut = edgeEntry.getValue().getValue();
				ExecType parentExec = parentPlan.getExecType();
				boolean parentIsFed = parentExec == ExecType.FED;

				if (canChooseLOUT && originalOut != FederatedOutput.LOUT) {
					double delta = computeSwitchEdgeCostDelta(
							memoTable, childHopID, originalOut, FederatedOutput.LOUT,
							parentPlan, parentIsFed, numOfWorkers);
					lOutAdditionalCost += delta;
				}
				if (canChooseFOUT && originalOut != FederatedOutput.FOUT) {
					double delta = computeSwitchEdgeCostDelta(
							memoTable, childHopID, originalOut, FederatedOutput.FOUT,
							parentPlan, parentIsFed, numOfWorkers);
					fOutAdditionalCost += delta;
				}
			}
		}

		FederatedOutput chosen;
		double chosenCost;
		if (!canChooseFOUT || (canChooseLOUT && lOutAdditionalCost <= fOutAdditionalCost)) {
			chosen = FederatedOutput.LOUT;
			chosenCost = lOutAdditionalCost;
		} else {
			chosen = FederatedOutput.FOUT;
			chosenCost = fOutAdditionalCost;
		}

		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
			List<Pair<Long, FederatedOutput>> childs = parentPlan.getChildFedPlans();
			for (int i = 0; i < childs.size(); i++) {
				Pair<Long, FederatedOutput> edge = childs.get(i);
				if (memoTable.resolveOriginalHopId(edge.getKey()) == hopID && edge.getValue() != chosen) {
					childs.set(i, Pair.of(edge.getKey(), chosen));
				}
			}
		}

		return chosenCost;
	}

	private static double computeSwitchEdgeCostDelta(
			FederatedPlannerDpMemoTable memoTable, long childHopID,
			FederatedOutput fromOut, FederatedOutput toOut,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			boolean parentIsFed, int numOfWorkers) {

		FederatedPlannerDpMemoTable.FedPlan fromPlan = memoTable.getFedPlanAfterPrune(childHopID, fromOut);
		FederatedPlannerDpMemoTable.FedPlan toPlan = memoTable.getFedPlanAfterPrune(childHopID, toOut);
		if (fromPlan == null || toPlan == null) {
			String msg = "Missing FedPlan variant for hop " + childHopID
					+ " when switching " + fromOut + " -> " + toOut
					+ " (fromPlan=" + (fromPlan != null) + ", toPlan=" + (toPlan != null) + ")";
			if (OptimizerUtils.isStrictFederatedConflictCheck())
				throw new DMLRuntimeException(msg);
			FederatedPlannerLogger.logWarnMessage("[Planner] " + msg);
			return Double.POSITIVE_INFINITY;
		}

		FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator =
			FederatedPlannerDpCostEstimator.bindExact(memoTable.analysis(),
				memoTable.requireOccurrence(fromPlan.getHopRef()), memoTable);
		double fromCumulativeShare = exactEstimator.cumulativeShare(fromPlan);
		double toCumulativeShare = exactEstimator.cumulativeShare(toPlan);
		double fromForwardingShare = computeParentChildForwardingCostShare(
				exactEstimator, parentIsFed, fromOut, fromPlan, parentPlan, numOfWorkers);
		double toForwardingShare = computeParentChildForwardingCostShare(
				exactEstimator, parentIsFed, toOut, toPlan, parentPlan, numOfWorkers);

		double childForwardingDelta =
				(toCumulativeShare - fromCumulativeShare) + (toForwardingShare - fromForwardingShare);
		double parentVariantDelta = computeParentVariantSwitchDelta(
				memoTable, parentPlan, childHopID, toOut);
		if (Double.isFinite(parentVariantDelta))
			return parentVariantDelta;

		return childForwardingDelta;
	}

	private static double computeParentVariantSwitchDelta(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			long childHopID,
			FederatedOutput desiredChildOut) {

		if (memoTable == null || parentPlan == null || parentPlan.getHopRef() == null)
			return Double.NaN;

		long parentHopID = parentPlan.getHopRef().getHopID();
		FederatedPlannerDpMemoTable.FedPlanVariants variantsLOUT =
				memoTable.getFedPlanVariants(Pair.of(parentHopID, FederatedOutput.LOUT));
		FederatedPlannerDpMemoTable.FedPlanVariants variantsFOUT =
				memoTable.getFedPlanVariants(Pair.of(parentHopID, FederatedOutput.FOUT));
		if ((variantsLOUT == null || variantsLOUT.isEmpty()) && (variantsFOUT == null || variantsFOUT.isEmpty()))
			return Double.NaN;

		final long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
		boolean parentReferencesChild = false;
		for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
			if (edge == null)
				continue;
			long edgeOrigHopID = memoTable.resolveOriginalHopId(edge.getKey());
			if (edgeOrigHopID == childOrigHopID) {
				parentReferencesChild = true;
			}
		}
		if (!parentReferencesChild)
			return Double.NaN;

		double best = Double.POSITIVE_INFINITY;
		for (FederatedPlannerDpMemoTable.FedPlanVariants variants :
				new FederatedPlannerDpMemoTable.FedPlanVariants[] {variantsLOUT, variantsFOUT}) {
			if (variants == null || variants.isEmpty())
				continue;
			for (FederatedPlannerDpMemoTable.FedPlan cand : variants.getFedPlanVariants()) {
				if (cand == null || cand.getChildFedPlans() == null || cand.getChildFedPlans().isEmpty())
					continue;
				boolean ok = false;
				for (Pair<Long, FederatedOutput> edge : cand.getChildFedPlans()) {
					if (edge == null)
						continue;
					long edgeOrigHopID = memoTable.resolveOriginalHopId(edge.getKey());
					if (edgeOrigHopID == childOrigHopID
							&& edge.getValue() == desiredChildOut) {
						ok = true;
					}
				}
				if (!ok)
					continue;
				best = Math.min(best, cand.getCumulativeCost());
			}
		}

		return Double.isFinite(best) ? best - parentPlan.getCumulativeCost() : Double.NaN;
	}

	private static boolean isTransientWriteParentPlan(FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		if (parentPlan == null || parentPlan.getHopRef() == null || !(parentPlan.getHopRef() instanceof DataOp))
			return false;
		return ((DataOp) parentPlan.getHopRef()).getOp() == Types.OpOpData.TRANSIENTWRITE;
	}

	private static double computeParentChildForwardingCostShare(
			FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator,
			boolean parentIsFed, FederatedOutput childOut,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			int numOfWorkers) {

		if (childPlan == null || parentPlan == null)
			return 0.0;

		if (parentIsFed && childOut == FederatedOutput.LOUT) {
			double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childPlan.getHopRef());
			FType uploadType = childPlan.getCpFoutTypeOrFType();
			double uploadCost = exactEstimator.upload(transferMem, uploadType, numOfWorkers);
			return exactEstimator.forwardingShare(uploadCost, childPlan, parentPlan);
			} else if (parentIsFed && childOut == FederatedOutput.FOUT && childPlan.getExecType() == ExecType.CP) {
				if (childPlan.isFoutMaterializationAccounted())
					return 0.0;
				FType uploadType = childPlan.getCpFoutTypeOrFType();
				double uploadCost = exactEstimator.upload(
						childPlan.getHopRef(), parentPlan.getHopRef(), uploadType, numOfWorkers);
				return exactEstimator.forwardingShare(uploadCost, childPlan, parentPlan);
		} else if (!parentIsFed && childOut == FederatedOutput.FOUT) {
			double downloadCost;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childPlan.getHopRef())) {
				downloadCost = 0.0;
			}
			else if (childPlan.getExecType() == ExecType.CP) {
				downloadCost = 0.0;
			}
			else
				downloadCost = exactEstimator.download(
						FederatedCostModel.getEffectiveOutputMemEstimate(childPlan.getHopRef()),
						childPlan.getFType(), numOfWorkers);
			return exactEstimator.foutToCpShare(
					parentPlan.getHopRef(), downloadCost, childPlan, parentPlan);
		}
		return 0.0;
	}

	private static String formatDpChildBreakdown(
			List<Hop> bothOutInputs,
			double[][] childCumulativeCost,
			double[] childForwardingCostToCP,
			double[] childForwardingCostToFED,
			double[] childForwardingCostFOutToFED,
			List<Hop> lOUTOnlyinputHops,
			List<Double> lOUTOnlychildCumulativeCost,
			List<Double> lOUTOnlychildForwardingCostToFED,
			List<Hop> fOUTOnlyinputHops,
			List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCostToCP,
			List<Double> fOUTOnlychildForwardingCostToFED) {

		StringBuilder sb = new StringBuilder();
		sb.append("both=[");
		for (int i = 0; i < bothOutInputs.size(); i++) {
			if (i > 0)
				sb.append(';');
			Hop child = bothOutInputs.get(i);
			sb.append(child.getHopID()).append(':')
				.append(String.format(Locale.ROOT,
					"cumCP=%.6f,cumFED=%.6f,toCP=%.6f,toFED=%.6f,fOutToFED=%.6f",
					childCumulativeCost[i][0], childCumulativeCost[i][1],
					childForwardingCostToCP[i], childForwardingCostToFED[i],
					childForwardingCostFOutToFED[i]));
		}
		sb.append("],lOnly=[");
		for (int i = 0; i < lOUTOnlyinputHops.size(); i++) {
			if (i > 0)
				sb.append(';');
			Hop child = lOUTOnlyinputHops.get(i);
			sb.append(child.getHopID()).append(':')
				.append(String.format(Locale.ROOT, "cum=%.6f,toFED=%.6f",
					lOUTOnlychildCumulativeCost.get(i), lOUTOnlychildForwardingCostToFED.get(i)));
		}
		sb.append("],fOnly=[");
		for (int i = 0; i < fOUTOnlyinputHops.size(); i++) {
			if (i > 0)
				sb.append(';');
			Hop child = fOUTOnlyinputHops.get(i);
			sb.append(child.getHopID()).append(':')
				.append(String.format(Locale.ROOT, "cum=%.6f,toCP=%.6f,toFED=%.6f",
					fOUTOnlychildCumulativeCost.get(i), fOUTOnlychildForwardingCostToCP.get(i),
					fOUTOnlychildForwardingCostToFED.get(i)));
		}
		sb.append(']');
		return sb.toString();
	}

	// Parent-child edge uniqueness: duplicates of the same hopID in a parent's
	// childFedPlans
	// are not expected, but we return all matches so duplicates are processed
	// uniformly.
	private static List<Pair<Integer, Pair<Long, FederatedOutput>>> findChildEdges(
			FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan parentPlan, long hopID) {

		List<Pair<Integer, Pair<Long, FederatedOutput>>> matches = new ArrayList<>();
		List<Pair<Long, FederatedOutput>> childs = parentPlan.getChildFedPlans();
		for (int i = 0; i < childs.size(); i++) {
			Pair<Long, FederatedOutput> edge = childs.get(i);
			if (memoTable.resolveOriginalHopId(edge.getKey()) == hopID) {
				matches.add(Pair.of(i, edge));
			}
		}
		return matches;
	}

	private static final class ConflictEntry {
		// firstSeenOut is kept for debugging/tracing only.
		final FederatedOutput firstSeenOut;
		// LinkedHashSet keeps insertion order for debugging while avoiding duplicate
		// parents.
		final java.util.LinkedHashSet<FederatedPlannerDpMemoTable.FedPlan> parents;
		final java.util.LinkedHashSet<Long> memberHopIDs;
		boolean seenLOUT;
		boolean seenFOUT;
		boolean canChooseLOUT;
		boolean canChooseFOUT;

		ConflictEntry(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID) {
			this.firstSeenOut = out;
			this.parents = new java.util.LinkedHashSet<>();
			this.memberHopIDs = new java.util.LinkedHashSet<>();
			this.parents.add(parent);
			this.memberHopIDs.add(memberHopID);
			this.seenLOUT = (out == FederatedOutput.LOUT);
			this.seenFOUT = (out == FederatedOutput.FOUT);
			this.canChooseLOUT = true;
			this.canChooseFOUT = true;
		}

		void addUsage(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID) {
			this.parents.add(parent);
			this.memberHopIDs.add(memberHopID);
			if (out == FederatedOutput.LOUT)
				this.seenLOUT = true;
			else if (out == FederatedOutput.FOUT)
				this.seenFOUT = true;
		}

		boolean isTrulyConflicting() {
			return seenLOUT && seenFOUT;
		}
	}
}
