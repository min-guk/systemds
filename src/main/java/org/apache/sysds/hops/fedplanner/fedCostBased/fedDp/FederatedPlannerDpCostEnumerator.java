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
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedWorkerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
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

	private static final class EnumerationCapture {
		private final NeutralEnumerationContext context;
		private final DpEnumerationObserver observer;
		private final List<CapturedCandidate> candidates = new ArrayList<>();
		private int rawCandidateCount;
		private EnumerationCapture(NeutralEnumerationContext context, FederatedPlannerDpMemoTable memo,
			DpEnumerationObserver observer) { this.context = context; this.observer = observer == null ? NO_OP_OBSERVER : observer; }
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
		private PreSelectionSemanticBlock semanticBlock() {
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

		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		FederatedPlannerDpRewireTransTable.rewireProgram(analysis, prog, rewireTable, hopCommonTable, privacyConstraintMap, fedMap,
				unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx);
		RewireOccurrenceSnapshot rewireSnapshot = FederatedPlannerDpRewireTransTable.snapshotProductionRewire(
			analysis, prog, rewireTable, hopCommonTable, parentChildUploadHints, progRootHopSet, unrollCtx,
			analysis.analysisFingerprint());
		memoTable.registerHopRefs(rewireSnapshot, hopCommonTable);
		memoTable.registerCloneMapping(rewireSnapshot);
		memoTable.registerDeadFunctionOutputHopIDs(rewireSnapshot, unrollCtx.getDeadFunctionOutputHopIDs());
		memoTable.registerAdditionalRootHopIDs(rewireSnapshot, unrollCtx.getIter1Roots());
		populateParentChildUploadHintsFromRewire(parentChildUploadHints, rewireTable, hopCommonTable);

		int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);
		memoTable.setNumWorkers(numOfWorkers);
		EnumerationCapture capture = new EnumerationCapture(
			DpPlacementAdapter.captureNeutralEnumerationContext(
				analysis, rewireSnapshot, numOfWorkers, privacyConstraintMap, unRefTwriteSet), memoTable, observer);

		addUnreferencedTWriteRoots(progRootHopSet, unRefTwriteSet, hopCommonTable);
		Set<String> fnStack = new HashSet<>();
		Set<Hop> visitedHops = Collections.newSetFromMap(new IdentityHashMap<>());

		for (StatementBlock sb : analysis.topLevelStatementBlocks()) {
			enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		}
		for (Hop iter1Root : unrollCtx.getIter1Roots()) {
			if (iter1Root == null)
				continue;
			enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
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

		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();

		DMLProgram prog = function.getDMLProg();
			FederatedPlannerDpRewireTransTable.rewireFunctionDynamic(function, prog, rewireTable, hopCommonTable,
					privacyConstraintMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, parentChildUploadHints, unrollCtx);
				int numOfWorkers = FederatedWorkerUtils.countDistinctWorkers(fedMap);
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
			analysis, rewireSnapshot, numOfWorkers, privacyConstraintMap, unRefTwriteSet);
		EnumerationCapture capture = new EnumerationCapture(
			new NeutralEnumerationContext(capturedContext.analysis(), capturedContext.rewireSnapshot(),
				capturedContext.analysisFingerprint(), capturedContext.numWorkers(),
				capturedContext.invocationEvidence(), capturedContext.privacy()), memoTable, NO_OP_OBSERVER);
		enumerateStatementBlock(function, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		for (Hop iter1Root : unrollCtx.getIter1Roots()) {
			if (iter1Root == null)
				continue;
			enumerateHopDAG(iter1Root, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
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
			Map<Long, Privacy> privacyConstraintMap, Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet, Set<String> fnStack,
			int numOfWorkers, Set<Hop> visitedHops) {
		enumerateStatementBlock(sb, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
			parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, null);
	}

	private static void enumerateStatementBlock(StatementBlock sb, DMLProgram prog, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable, Map<Long, List<Hop>> rewireTable,
			Map<Long, Privacy> privacyConstraintMap, Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet, Set<String> fnStack,
			int numOfWorkers, Set<Hop> visitedHops, EnumerationCapture capture) {
		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

			enumerateHopDAG(isb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
					privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);

			for (StatementBlock innerIsb : istmt.getIfBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);

			for (StatementBlock innerIsb : istmt.getElseBody())
				enumerateStatementBlock(innerIsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			enumerateHopDAG(fsb.getFromHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			enumerateHopDAG(fsb.getToHops(), prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			if (fsb.getIncrementHops() != null) {
				enumerateHopDAG(fsb.getIncrementHops(), prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
			}

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			enumerateHopDAG(wsb.getPredicateHops(), prog, memoTable, hopCommonTable, rewireTable,
					privacyConstraintMap,
					parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);

			for (StatementBlock innerWsb : wstmt.getBody())
				enumerateStatementBlock(innerWsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
				enumerateStatementBlock(innerFsb, prog, memoTable, hopCommonTable, rewireTable,
						privacyConstraintMap,
						parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
					enumerateHopDAG(c, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
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
			Map<Long, Privacy> privacyConstraintMap, Map<Long, Set<Long>> parentChildUploadHints,
			Set<Long> unRefTwriteSet,
			Set<String> fnStack, int numOfWorkers, Set<Hop> visitedHops, EnumerationCapture capture) {
		// Process all input nodes first if not already in memo table

		List<Hop> childHops = new ArrayList<>(hop.getInput());

		// Todo: Check if is right
		if ((hop instanceof DataOp) && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTREAD) {
			List<Hop> transChildHops = rewireTable.get(hop.getHopID());
			if (transChildHops != null) {
				childHops.addAll(transChildHops);
			}
		}

		for (Hop inputHop : childHops) {
			long inputHopID = inputHop.getHopID();
			if (!memoTable.containsPlanForCarrier(inputHop, FederatedOutput.FOUT)
					&& !memoTable.containsPlanForCarrier(inputHop, FederatedOutput.LOUT)) {
				if (!visitedHops.contains(inputHop)) {
					visitedHops.add(inputHop);
					enumerateHopDAG(inputHop, prog, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
							parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers, visitedHops, capture);
				}
			}
		}

		if (hop instanceof FunctionOp) {
			// maintain counters and investigate functions if not seen so far
			FunctionOp fop = (FunctionOp) hop;
			if (fop.getFunctionType() == FunctionType.DML) {
				String fkey = fop.getFunctionKey();

				if (!fnStack.contains(fkey)) {
					fnStack.add(fkey);
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
							enumerateStatementBlock(fsb, prog, memoTable, hopCommonTable, rewireTable,
									privacyConstraintMap, parentChildUploadHints, unRefTwriteSet, fnStack, numOfWorkers,
									visitedHops, capture);
						}
					}
				}
			}
		}

		// Enumerate the federated plan for the current Hop
		enumerateHop(hop, memoTable, hopCommonTable, rewireTable, privacyConstraintMap,
				parentChildUploadHints, unRefTwriteSet, numOfWorkers, capture);

		// FederatedPlannerDpRewireTransTable.logHopInfo(hop, privacyConstraintMap,
		// "enumerateHopDAG");

	}

	/**
	 * Enumerates federated execution plans for a given Hop.
	 * This method calculates the self cost and child costs for the Hop,
	 * generates federated plan variants for both LOUT and FOUT output types,
	 * and prunes redundant plans before adding them to the memo table.
	 */
	private static void enumerateHop(Hop hop, FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable,
			Map<Long, List<Hop>> rewireTable, Map<Long, Privacy> privacyConstraintMap,
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
					if (!memoTable.contains(outputHopId, FederatedOutput.LOUT)
							&& !memoTable.contains(outputHopId, FederatedOutput.FOUT))
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
		List<Hop> inputHopsForPrivacy = new ArrayList<>(childHops);
		inputHopsForPrivacy.addAll(lOUTOnlyinputHops);
		inputHopsForPrivacy.addAll(fOUTOnlyinputHops);
		final Privacy privacyConstraint = privacyConstraintMap.getOrDefault(hopID, Privacy.PUBLIC);

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
		// Align with MinST: FED execution has a per-op coordination overhead that should
		// be modeled even when compute cost scales down with workers.
		//
		// IMPORTANT: In DP we represent unrolled-loop iteration counts via
		// HopCommon.multiplicity (see FederatedPlannerDpRewireTransTable). This overhead
		// must therefore scale with multiplicity as well.
		//
		// DP/MinST parity: use the shared control-only helper. The helper already applies
		// worker fanout semantics, so do not multiply by numWorkers again here.
		double fedExecWeight = hopNetworkWeight * hopCommon.getMultiplicity();
		double fedOverhead = (hop instanceof DataOp)
				? 0.0
				: fedExecWeight * FederatedCostModel.computeFedCoordinationCost(numOfWorkers);
		// DP/MinST parity: use the shared runtime-stage predicate for FED compute scaling.
		// The DP enumerator resolves per-input ftypes later in the variant loop; the
		// operation-family predicate still prevents blanket linear speedup for low-arithmetic
		// intensity FED stages such as cell ops, slicing, and transpose.
		double defaultFedComputeCost = FederatedCostModel.computeFederatedComputeCost(
				hop, baseSelfCost, numOfWorkers, false);
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
			// Costs from children, split by the parent's ExecType semantics.
			double childCostCPExec = 0; // Parent executes in CP; forwarding only from FOUT children.
			double childCostFEDExec = 0; // Parent executes in FED; forwarding only from LOUT children.

				for (int j = 0; j < numBothOutInputs; j++) {
					Hop inputHop = lOutfOutChildHops.get(j);
					final int bit = (i & (1L << j)) != 0 ? 1 : 0;
					selectedBits[j] = bit;
					final FederatedOutput childType = (bit == 1) ? FederatedOutput.FOUT : FederatedOutput.LOUT;
					FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
							childType);
	
					if (childPlan == null) {
						throw new DMLRuntimeException("Missing " + childType + " federated plan for child hop "
								+ inputHop.getHopID() + " (" + inputHop.getOpString()
								+ ") while enumerating parent " + hopID + " (" + hop.getOpString()
								+ "), privacy=" + privacyConstraint);
					}

					planChilds.add(Pair.of(inputHop.getHopID(), childType));
					collectedFTypes.add(childType == FederatedOutput.LOUT ? null : childPlan.getFType());
					collectedHops.add(inputHop);
					if (childType == FederatedOutput.FOUT)
						fedInputTypeMap.put(inputHop.getHopID(), childPlan.getFType());
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
				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
						FederatedOutput.LOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + inputHop.getHopID()
							+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
							+ hop.getOpString() + "), privacy=" + privacyConstraint);
					}
					planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.LOUT));
					collectedFTypes.add(null);
					collectedHops.add(inputHop);
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
				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(inputHop.getHopID(),
						FederatedOutput.FOUT);
				if (childPlan == null) {
					throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + inputHop.getHopID()
							+ " (" + inputHop.getOpString() + ") while enumerating parent " + hopID + " ("
							+ hop.getOpString() + "), privacy=" + privacyConstraint);
				}
				planChilds.add(Pair.of(inputHop.getHopID(), FederatedOutput.FOUT));
				collectedFTypes.add(childPlan.getFType());
				collectedHops.add(inputHop);
				fedInputTypeMap.put(inputHop.getHopID(), childPlan.getFType());
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

					NormalizedCandidateInputs normalizedCandidateInputs = DpPlacementAdapter.normalizeCandidateInputs(
							capture.context, findOccurrence(capture, hop),
							planChilds, collectedHops, collectedFTypes, fedInputTypeMap, memoTable);
					capture.capture(normalizedCandidateInputs.snapshot(), i);
					DpPlacementAdapter.CandidateDecisionReceipt candidateDecisionReceipt =
						DpPlacementAdapter.resolveCandidateDecision(capture.context, normalizedCandidateInputs, i);
					capture.captureDecisionReceipt(candidateDecisionReceipt, i);
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
			}
			for (int j = 0; j < numLoutOnlyInputs; j++) {
				Hop inputHop = lOUTOnlyinputHops.get(j);
				childCostCPExec += lOUTOnlychildCumulativeCost.get(j);
				double fedForwardingCost = lOUTOnlychildForwardingCostToFED.get(j);
				childCostFEDExec += lOUTOnlychildCumulativeCost.get(j) + fedForwardingCost;
			}
			for (int j = 0; j < numFoutOnlyInputs; j++) {
				childCostCPExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCostToCP.get(j);
				childCostFEDExec += fOUTOnlychildCumulativeCost.get(j) + fOUTOnlychildForwardingCostToFED.get(j);
			}

					boolean canSatisfyFedInputs = canSatisfyFederatedInputsFromFTypes(candidateDecisionReceipt);

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
						hop, oracleLogicalFType, fedExecWeight, numOfWorkers, false);
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
				// DP must not constrain its candidate space to match MinST's min-cut state encoding.
				// If MinST cannot encode a combination (e.g., due to non-submodular costs), it should
				// mark itself unsafe and fall back to DP. DP should remain cost-based over all
				// runtime-supported combinations.
					if (candidateDecisionReceipt.capabilityFact().nativeExec() == ExecType.FED
						&& candidateDecisionReceipt.capabilityFact().nativeOutput() == FederatedOutput.FOUT) {
						sawOracleFedFout = true;
					}
					sawAllowCpLout |= candidateDecisionReceipt.allowCPLOUT();
					sawAllowCpFout |= candidateDecisionReceipt.allowCPFOUT();
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

					if (allowFedFoutCandidate) {
						FederatedPlannerDpMemoTable.FedPlan fedFOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								fedFoutCost,
								fOutFedPlanVariants, planChilds);
						fedFOutPlan.setExecType(ExecType.FED);
						fedFOutPlan.setFType(derivedFedFout ? cpLogicalFType : oracleLogicalFType);
						fedFOutPlan.setCpFoutType(cpLogicalFType);
						fedFOutPlan.setDerivedFedFout(derivedFedFout);
						fedFOutPlan.setSelectedPlacementState(
							candidateDecisionReceipt.requireExactState(ExecType.FED, FederatedOutput.FOUT));
						fOutFedPlanVariants.addFedPlan(fedFOutPlan);
					}

				if (allowFedLoutCandidate) {
						FederatedPlannerDpMemoTable.FedPlan fedLOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								fedLoutCost,
								lOutFedPlanVariants, planChilds);
						fedLOutPlan.setExecType(ExecType.FED);
						fedLOutPlan.setFType(lOutLogicalFType);
						fedLOutPlan.setCpFoutType(cpLogicalFType);
						fedLOutPlan.setSelectedPlacementState(
							candidateDecisionReceipt.requireExactState(ExecType.FED, FederatedOutput.LOUT));
						lOutFedPlanVariants.addFedPlan(fedLOutPlan);
					}

				if (allowCpLoutCandidate) {
						FederatedPlannerDpMemoTable.FedPlan cpLOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								cpLoutCost,
								lOutFedPlanVariants, planChilds);
						cpLOutPlan.setExecType(ExecType.CP);
						cpLOutPlan.setFType(lOutLogicalFType);
						cpLOutPlan.setCpFoutType(cpLogicalFType);
						cpLOutPlan.setSelectedPlacementState(
							candidateDecisionReceipt.requireExactState(ExecType.CP, FederatedOutput.LOUT));
						lOutFedPlanVariants.addFedPlan(cpLOutPlan);
					}
					if (allowCpFoutCandidate) {
						FederatedPlannerDpMemoTable.FedPlan cpFOutPlan = new FederatedPlannerDpMemoTable.FedPlan(
								cpFoutCost,
								fOutFedPlanVariants, planChilds);
							cpFOutPlan.setExecType(ExecType.CP);
							cpFOutPlan.setFType(cpLogicalFType);
							cpFOutPlan.setCpFoutType(cpLogicalFType);
							cpFOutPlan.setFoutMaterializationAccounted(true);
							cpFOutPlan.setSelectedPlacementState(
								candidateDecisionReceipt.requireExactState(ExecType.CP, FederatedOutput.FOUT));
							fOutFedPlanVariants.addFedPlan(cpFOutPlan);
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

		boolean hasLOutPlan = !lOutFedPlanVariants.isEmpty();
		boolean hasFOutPlan = !fOutFedPlanVariants.isEmpty();

		if (hasLOutPlan) {
			lOutFedPlanVariants.pruneFedPlans();
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), hopOccurrence,
				FederatedOutput.LOUT, lOutFedPlanVariants);
			FederatedPlannerDpCostEstimator.estimateExact(new FederatedPlannerDpCostEstimator.EstimatorRequest(
				capture.context.analysis(), hopOccurrence, memoTable,
				memoTable.getFedPlanAfterPrune(hopOccurrence, FederatedOutput.LOUT)));
		}
		if (hasFOutPlan) {
			fOutFedPlanVariants.pruneFedPlans();
			memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), hopOccurrence,
				FederatedOutput.FOUT, fOutFedPlanVariants);
			FederatedPlannerDpCostEstimator.estimateExact(new FederatedPlannerDpCostEstimator.EstimatorRequest(
				capture.context.analysis(), hopOccurrence, memoTable,
				memoTable.getFedPlanAfterPrune(hopOccurrence, FederatedOutput.FOUT)));
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
			boolean hasLout = memoTable.contains(child.getHopID(), FederatedOutput.LOUT);
			boolean hasFout = memoTable.contains(child.getHopID(), FederatedOutput.FOUT);
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
		FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(child.getHopID(), output);
		if(childPlan == null)
			throw new DMLRuntimeException("Missing " + output + " federated plan for child hop "
				+ child.getHopID());
		planChilds.add(Pair.of(child.getHopID(), output));
		selectedChildHops.add(child);
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
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(edge);
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
		List<Hop> tWriteChildHops = collectTransientWriteChildHops(dataOp, childHops, capture);
		if (tWriteChildHops.isEmpty()) {
			return false;
		}

		FederatedPlannerDpCostEstimator.ExactEstimator exactEstimator = capture == null ? null :
			FederatedPlannerDpCostEstimator.bindExact(
				capture.context.analysis(), findOccurrence(capture, dataOp), memoTable);
		double baseSelfCost = exactEstimator == null
			? FederatedPlannerDpCostEstimator.legacyHopCost(hopCommon)
			: exactEstimator.computeHopCost(hopCommon);

		boolean allowLOUT = true;
		boolean allowFOUT = true;
		FType loutFType = null;
		FType foutFType = null;
		double loutCost = baseSelfCost;
		double loutAcquireCost = 0.0;
		boolean hasFederatedSourcePlan = false;
		List<Pair<Long, FederatedOutput>> loutChilds = new ArrayList<>();
		List<Hop> loutSelectedChildHops = new ArrayList<>();
		List<Pair<Long, FederatedOutput>> foutChilds = new ArrayList<>();
		List<Hop> foutSelectedChildHops = new ArrayList<>();
		double foutCost = baseSelfCost;

		for (Hop tWriteChildHop : tWriteChildHops) {
			Long childId = tWriteChildHop.getHopID();
			FederatedPlannerDpMemoTable.FedPlan loutPlan = memoTable.getFedPlanAfterPrune(childId,
					FederatedOutput.LOUT);
			if (loutPlan == null) {
				allowLOUT = false;
			}
			else {
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
				loutChilds.add(Pair.of(childId, FederatedOutput.LOUT));
				loutSelectedChildHops.add(tWriteChildHop);
			}

			FederatedPlannerDpMemoTable.FedPlan foutPlan = memoTable.getFedPlanAfterPrune(childId,
					FederatedOutput.FOUT);
			if (foutPlan == null || foutPlan.getFType() == null
					|| !canTransientReadReuseMatchedFoutWrite(dataOp, childId, memoTable)) {
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
				foutChilds.add(Pair.of(childId, FederatedOutput.FOUT));
				foutSelectedChildHops.add(tWriteChildHop);
			}
		}

		if (!allowLOUT && !allowFOUT) {
			throw new DMLRuntimeException("No valid federated plan for hop " + dataOp.getHopID()
					+ " (" + dataOp.getOpString() + ") based on transient write placements");
		}
		// TRANSIENTREAD with a matching TRANSIENTWRITE LOUT source is already paying the
		// local materialization/download cost in the producer cumulative cost. Charging a
		// second synthetic local-acquire download here double-counts the CP/LOUT path and
		// can incorrectly bias parent transitions toward FED/FOUT (e.g., ALS mask W).
		boolean hasLocalTransientWriteSourcePlan = allowLOUT && !loutChilds.isEmpty();
		if (!hasLocalTransientWriteSourcePlan && hasFederatedSourcePlan && dataOp.getDim1() > 0
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
				loutPlan.setExecType(ExecType.CP);
				loutPlan.setFType(loutFType);
				loutPlan.setCpFoutType(loutFType);
				loutPlan.setSelectedPlacementState(
					loutReceipt.requireExactState(ExecType.CP, FederatedOutput.LOUT));
				lOutFedPlanVariants.addFedPlan(loutPlan);
				lOutFedPlanVariants.pruneFedPlans();
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
				foutPlan.setExecType(ExecType.FED);
				foutPlan.setFType(foutFType);
				foutPlan.setCpFoutType(foutFType);
				foutPlan.setSelectedPlacementState(
					foutReceipt.requireExactState(ExecType.FED, FederatedOutput.FOUT));
				fOutFedPlanVariants.addFedPlan(foutPlan);
				fOutFedPlanVariants.pruneFedPlans();
				if(capture == null)
					memoTable.addFedPlanVariants(memoTable.requirePlanCarrierOccurrence(dataOp),
						FederatedOutput.FOUT, fOutFedPlanVariants);
				else
					memoTable.addFedPlanVariants(capture.context.rewireSnapshot(), findOccurrence(capture, dataOp),
						FederatedOutput.FOUT, fOutFedPlanVariants);
		}

		return true;
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

	private static boolean canTransientReadReuseMatchedFoutWrite(DataOp transientRead, long tWriteHopID,
			FederatedPlannerDpMemoTable memoTable) {
		if (transientRead == null || memoTable == null)
			return false;
		Hop hopRef = memoTable.resolveOriginalHop(tWriteHopID);
		if (!(hopRef instanceof DataOp) || ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE)
			return true;
		DataOp tWrite = (DataOp) hopRef;
		List<Hop> inputs = tWrite.getInput();
		Hop input = (inputs != null && !inputs.isEmpty()) ? inputs.get(0) : null;
		if (input == null)
			return true;
		if (FederatedPlannerUtils.hasConcreteMatchedWriteReuseSource(input, tWrite.getName()))
			return true;
		return !dependsOnSameTransientRead(input, tWrite.getName(), new HashSet<>());
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

		List<Hop> exactWrites = new ArrayList<>();
		for(Hop transientWrite : transientWrites)
			if(isTransientForwardCandidateCarrier(hop, transientWrite, capture))
				exactWrites.add(transientWrite);
		return exactWrites;
	}

	// Rewire forwards are scheduling dependencies, not automatic candidate inputs. Only an exact
	// neutral owner may enter adapter normalization; an unowned matrix read keeps its zero-input domain.
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
		long forwardOwners = snapshot.transientForwardEdges().stream().filter(edge ->
			edge.writeOccurrence() == write.key() && edge.readOccurrence() == read.key()).count();
		if(forwardOwners > 1)
			throw new IllegalArgumentException("Transient candidate carrier has duplicate forward ownership");
		return forwardOwners == 1 && write.hop().getDataType() != Types.DataType.MATRIX
			&& read.hop().getDataType() != Types.DataType.MATRIX;
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
		if (tWriteOut == FederatedOutput.LOUT) {
			return execType == ExecType.CP && fedOutType == FederatedOutput.LOUT;
		}
		if (tWriteOut == FederatedOutput.FOUT) {
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

	private static boolean canSatisfyFederatedInputsFromFTypes(CandidateDecisionReceipt receipt) {
		return receipt.allowFEDLOUT() || receipt.allowFEDFOUT();
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
		for (RootChoice choice : rootChoices) {
			cumulativeCost += choice.selectedPlan().getCumulativeCost();
			rootFedPlanChilds.add(Pair.of(choice.rootHop().getHopID(), choice.selectedOutput()));
		}

		return new FederatedPlannerDpMemoTable.FedPlan(cumulativeCost, null, rootFedPlanChilds);
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

			for (Pair<Long, FederatedOutput> childEdge : current.getChildFedPlans()) {
				long childHopID = childEdge.getKey();
				long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
				FederatedOutput childOut = childEdge.getValue();

				FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childEdge);
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
