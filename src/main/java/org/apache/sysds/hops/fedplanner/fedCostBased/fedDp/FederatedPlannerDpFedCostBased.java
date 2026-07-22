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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.CandidateNormalizationFixture;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationObserver;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpCostEnumerator.DpEnumerationResult;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.ExactPlacementRegistration;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.ConstructionDisposition;
import org.apache.sysds.hops.fedplanner.placement.adapter.DpPlacementAdapter.PreSelectionSemanticBlock;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Cost-based DP federated planner.
 *
 * <p>This rewrite stage must not apply cost-ignorant conflict resolution such as
 * "if any CP variant exists, force CP". Instead, it resolves LOUT/FOUT placement
 * conflicts by comparing the accumulated forwarding costs (upload/refed vs.
 * download) under loop-aware weights, and then applies the chosen representation
 * consistently.</p>
 */
public class FederatedPlannerDpFedCostBased extends AFederatedPlanner {
	public record AppliedPlanReceipt(int ordinal, boolean additionalRoot, long planningHopId,
		FederatedOutput output, FederatedPlannerDpMemoTable.FedPlan plan, Hop planningHop,
		long executableHopId, Hop executableHop) {
		public AppliedPlanReceipt {
			Objects.requireNonNull(output, "output");
			Objects.requireNonNull(plan, "plan");
			Objects.requireNonNull(planningHop, "planningHop");
			Objects.requireNonNull(executableHop, "executableHop");
			if(ordinal < 0 || plan.getHopRef() != planningHop || plan.getHopID() != planningHopId
				|| planningHop.getHopID() != planningHopId || plan.getFedOutType() != output)
				throw new IllegalArgumentException("Applied plan identity differs");
			if(executableHop.getHopID() != executableHopId)
				throw new IllegalArgumentException("Applied executable identity differs");
		}
	}

	public enum AdditionalRootDisposition { APPLIED, ALREADY_VISITED }

	public record AdditionalRootInvocationReceipt(int ordinal, long planningHopId, FederatedOutput output,
		FederatedPlannerDpMemoTable.FedPlan plan, Hop planningHop, long executableHopId,
		Hop executableHop, AdditionalRootDisposition disposition) {
		public AdditionalRootInvocationReceipt {
			Objects.requireNonNull(output, "output");
			Objects.requireNonNull(plan, "plan");
			Objects.requireNonNull(planningHop, "planningHop");
			Objects.requireNonNull(executableHop, "executableHop");
			Objects.requireNonNull(disposition, "disposition");
			if(ordinal < 0 || plan.getHopRef() != planningHop || plan.getHopID() != planningHopId
				|| planningHop.getHopID() != planningHopId || plan.getFedOutType() != output)
				throw new IllegalArgumentException("Additional-root invocation identity differs");
			if(executableHop.getHopID() != executableHopId)
				throw new IllegalArgumentException("Additional-root executable identity differs");
		}
	}

	public record InvocationCounters(int enumerationCount, int exactSelectionCount,
		int applicationPhaseCount, int appliedPlanCount, int additionalRootInvocationCount,
		int additionalRootNoOpCount, int internalAnalysisBuildCount,
		int oldOverloadCount, int reenumerationCount, int repairCount, int fallbackCount,
		int doubleApplicationCount) { }

	public enum SemanticConsumptionState { NOT_IMPLEMENTED, CONSUMED }

	/**
	 * Invocation-local proof that the final DP boundary consumed the exact semantic
	 * evidence published by the one enumeration performed for this invocation.
	 *
	 * <p>The public seven-argument constructor intentionally cannot authenticate a
	 * terminal {@link SemanticConsumptionState#CONSUMED} claim: those fields alone
	 * cannot distinguish the production block from a value-equal post-hoc copy.
	 * Production uses {@link #consumed(DpEnumerationResult, PlacementAnalysis,
	 * DpPlacementAdapter.ExactSelection, String, String)}, which additionally binds
	 * the receipt to the exact invocation-local enumeration result without global
	 * state, caches, or reconstructed evidence.</p>
	 */
	public static final class DpSemanticConsumptionReceipt {
		private final PlacementAnalysis analysis;
		private final RewireOccurrenceSnapshot rewireSnapshot;
		private final PreSelectionSemanticBlock semanticBlock;
		private final DpPlacementAdapter.ExactSelection exactSelection;
		private final SemanticConsumptionState state;
		private final String analysisFingerprintBefore;
		private final String analysisFingerprintAfter;

		public DpSemanticConsumptionReceipt(PlacementAnalysis analysis,
			RewireOccurrenceSnapshot rewireSnapshot, PreSelectionSemanticBlock semanticBlock,
			DpPlacementAdapter.ExactSelection exactSelection, SemanticConsumptionState state,
			String analysisFingerprintBefore, String analysisFingerprintAfter) {
			this(analysis, rewireSnapshot, semanticBlock, exactSelection, state,
				analysisFingerprintBefore, analysisFingerprintAfter, null);
		}

		private DpSemanticConsumptionReceipt(PlacementAnalysis analysis,
			RewireOccurrenceSnapshot rewireSnapshot, PreSelectionSemanticBlock semanticBlock,
			DpPlacementAdapter.ExactSelection exactSelection, SemanticConsumptionState state,
			String analysisFingerprintBefore, String analysisFingerprintAfter,
			DpEnumerationResult productionResult) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.rewireSnapshot = Objects.requireNonNull(rewireSnapshot, "rewireSnapshot");
			this.semanticBlock = Objects.requireNonNull(semanticBlock, "semanticBlock");
			this.exactSelection = Objects.requireNonNull(exactSelection, "exactSelection");
			this.state = Objects.requireNonNull(state, "state");
			this.analysisFingerprintBefore = Objects.requireNonNull(
				analysisFingerprintBefore, "analysisFingerprintBefore");
			this.analysisFingerprintAfter = Objects.requireNonNull(
				analysisFingerprintAfter, "analysisFingerprintAfter");

			if(state == SemanticConsumptionState.CONSUMED && productionResult == null)
				throw new IllegalArgumentException("CONSUMED requires exact production enumeration evidence");
			if(productionResult != null && (productionResult.rewireSnapshot() != rewireSnapshot
				|| productionResult.semanticBlock() != semanticBlock
				|| productionResult.optimalPlan() != exactSelection.legacyOptimalPlan()))
				throw new IllegalArgumentException("Semantic evidence differs from the production enumeration result");
			if(rewireSnapshot.analysis() != analysis || semanticBlock.context().analysis() != analysis
				|| semanticBlock.context().rewireSnapshot() != rewireSnapshot
				|| exactSelection.analysis() != analysis)
				throw new IllegalArgumentException("Semantic consumption producer identities differ");
			if(!analysis.analysisFingerprint().equals(analysisFingerprintBefore)
				|| !analysisFingerprintBefore.equals(analysisFingerprintAfter)
				|| !rewireSnapshot.analysisFingerprint().equals(analysisFingerprintBefore)
				|| !semanticBlock.context().analysisFingerprint().equals(analysisFingerprintBefore))
				throw new IllegalArgumentException("Semantic consumption analysis fingerprint differs");
			if(state == SemanticConsumptionState.CONSUMED) {
				if(semanticBlock.rawCandidateCount() != semanticBlock.capturedCandidateCount()
					|| !semanticBlock.zeroDifference())
					throw new IllegalArgumentException("Semantic candidate capture is not zero-difference");
				for(DpPlacementAdapter.CandidateOccurrenceSnapshot snapshot : semanticBlock.candidateSnapshots())
					if(snapshot.disposition() != ConstructionDisposition.AVAILABLE)
						throw new IllegalArgumentException("Non-available semantic candidate was consumed");
			}
		}

		private static DpSemanticConsumptionReceipt consumed(DpEnumerationResult productionResult,
			PlacementAnalysis analysis, DpPlacementAdapter.ExactSelection exactSelection,
			String analysisFingerprintBefore, String analysisFingerprintAfter) {
			Objects.requireNonNull(productionResult, "productionResult");
			return new DpSemanticConsumptionReceipt(analysis, productionResult.rewireSnapshot(),
				productionResult.semanticBlock(), exactSelection, SemanticConsumptionState.CONSUMED,
				analysisFingerprintBefore, analysisFingerprintAfter, productionResult);
		}

		public PlacementAnalysis analysis() { return analysis; }
		public RewireOccurrenceSnapshot rewireSnapshot() { return rewireSnapshot; }
		public PreSelectionSemanticBlock semanticBlock() { return semanticBlock; }
		public DpPlacementAdapter.ExactSelection exactSelection() { return exactSelection; }
		public SemanticConsumptionState state() { return state; }
		public String analysisFingerprintBefore() { return analysisFingerprintBefore; }
		public String analysisFingerprintAfter() { return analysisFingerprintAfter; }
	}

	public record DpInvocationReceipt(PlacementAnalysis analysis, FederatedPlannerDpMemoTable memo,
		FederatedPlannerDpMemoTable.FedPlan legacyOptimalPlan, DpPlacementAdapter.ExactSelection exactSelection,
		DpSemanticConsumptionReceipt semanticConsumption,
		List<AppliedPlanReceipt> appliedPlans, List<AdditionalRootInvocationReceipt> additionalRootInvocations,
		InvocationCounters counters,
		String analysisFingerprintBefore, String analysisFingerprintAfter,
		NormalizedPlannerResult normalizedResult,
		PlacementEmissionReceipt emissionReceipt)
		implements AFederatedPlanner.PlannerInvocationReceipt {
		public DpInvocationReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(memo, "memo");
			Objects.requireNonNull(legacyOptimalPlan, "legacyOptimalPlan");
			Objects.requireNonNull(exactSelection, "exactSelection");
			Objects.requireNonNull(semanticConsumption, "semanticConsumption");
			Objects.requireNonNull(counters, "counters");
			Objects.requireNonNull(normalizedResult, "normalizedResult");
			Objects.requireNonNull(emissionReceipt, "emissionReceipt");
			if(normalizedResult.analysis() != analysis
				|| !normalizedResult.normalizedPlanFingerprint().equals(emissionReceipt.planHash()))
				throw new IllegalArgumentException("DP normalized result and emission receipt differ");
			appliedPlans = List.copyOf(appliedPlans);
			additionalRootInvocations = List.copyOf(additionalRootInvocations);
			if(analysis != exactSelection.analysis() || memo != exactSelection.memo()
				|| legacyOptimalPlan != exactSelection.legacyOptimalPlan())
				throw new IllegalArgumentException("DP receipt producer identities differ");
			if(semanticConsumption.analysis() != analysis
				|| semanticConsumption.exactSelection() != exactSelection
				|| semanticConsumption.state() != SemanticConsumptionState.CONSUMED
				|| !semanticConsumption.analysisFingerprintBefore().equals(analysisFingerprintBefore)
				|| !semanticConsumption.analysisFingerprintAfter().equals(analysisFingerprintAfter))
				throw new IllegalArgumentException("DP semantic consumption receipt differs");
			if(!analysis.analysisFingerprint().equals(analysisFingerprintBefore)
				|| !analysisFingerprintBefore.equals(analysisFingerprintAfter))
				throw new IllegalArgumentException("Supplied analysis changed during planning");
			Set<FederatedPlannerDpMemoTable.FedPlan> plans = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<Hop> planningHops = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<Long> planningIds = new HashSet<>();
			for(int i = 0; i < appliedPlans.size(); i++) {
				AppliedPlanReceipt applied = appliedPlans.get(i);
				if(applied.ordinal() != i || !plans.add(applied.plan()) || !planningHops.add(applied.planningHop())
					|| !planningIds.add(applied.planningHopId()))
					throw new IllegalArgumentException("Applied plan order or identity is duplicated at ordinal=" + i
						+ " planningHopId=" + applied.planningHopId() + " additional=" + applied.additionalRoot()
						+ " sequence=" + appliedPlans.stream().map(value -> value.planningHopId() + ":" + value.additionalRoot()).toList());
				if(i < exactSelection.selectedRootPlans().size()) {
					if(applied.additionalRoot() || applied.plan() != exactSelection.selectedRootPlans().get(i)
						|| applied.planningHopId() != exactSelection.aggregateChildEdges().get(i).getLeft())
						throw new IllegalArgumentException("Aggregate application receipt differs");
				}
				else if(!applied.additionalRoot())
					throw new IllegalArgumentException("Additional-root application receipt is unmarked");
			}
			int expectedAppliedOrdinal = exactSelection.selectedRootPlans().size();
			int noOps = 0;
			for(int i = 0; i < additionalRootInvocations.size(); i++) {
				AdditionalRootInvocationReceipt invocation = additionalRootInvocations.get(i);
				if(invocation.ordinal() != i)
					throw new IllegalArgumentException("Additional-root invocation order differs");
				if(invocation.disposition() == AdditionalRootDisposition.APPLIED) {
					if(expectedAppliedOrdinal >= appliedPlans.size())
						throw new IllegalArgumentException("Applied additional root is missing");
					AppliedPlanReceipt applied = appliedPlans.get(expectedAppliedOrdinal++);
					if(!applied.additionalRoot() || applied.plan() != invocation.plan()
						|| applied.planningHop() != invocation.planningHop()
						|| applied.planningHopId() != invocation.planningHopId()
						|| applied.executableHop() != invocation.executableHop()
						|| applied.executableHopId() != invocation.executableHopId()
						|| applied.output() != invocation.output())
						throw new IllegalArgumentException("Applied additional-root receipt differs");
				}
				else noOps++;
			}
			if(expectedAppliedOrdinal != appliedPlans.size())
				throw new IllegalArgumentException("Unexpected effective additional application");
			if(counters.enumerationCount() != 1 || counters.exactSelectionCount() != 1
				|| counters.applicationPhaseCount() != 1 || counters.appliedPlanCount() != appliedPlans.size()
				|| counters.additionalRootInvocationCount() != additionalRootInvocations.size()
				|| counters.additionalRootNoOpCount() != noOps
				|| counters.internalAnalysisBuildCount() != 0 || counters.oldOverloadCount() != 0
				|| counters.reenumerationCount() != 0 || counters.repairCount() != 0
				|| counters.fallbackCount() != 0 || counters.doubleApplicationCount() != 0)
				throw new IllegalArgumentException("DP invocation counters differ");
		}
	}

	public record DpDynamicInvocationReceipt(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, DpEnumerationResult enumerationResult,
		String fingerprintBefore, String fingerprintAfter, NormalizedPlannerResult normalizedResult,
		PlacementEmissionReceipt emissionReceipt) {
		public DpDynamicInvocationReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(memoTable, "memoTable");
			Objects.requireNonNull(enumerationResult, "enumerationResult");
			Objects.requireNonNull(fingerprintBefore, "fingerprintBefore");
			Objects.requireNonNull(fingerprintAfter, "fingerprintAfter");
			Objects.requireNonNull(normalizedResult, "normalizedResult");
			Objects.requireNonNull(emissionReceipt, "emissionReceipt");
			if(normalizedResult.analysis() != analysis
				|| !normalizedResult.normalizedPlanFingerprint().equals(emissionReceipt.planHash()))
				throw new IllegalArgumentException("Dynamic DP normalized result and emission receipt differ");
			if(memoTable.analysis() != analysis
				|| enumerationResult.rewireSnapshot().analysis() != analysis
				|| enumerationResult.semanticBlock().context().analysis() != analysis)
				throw new IllegalArgumentException("Dynamic DP receipt producer identities differ");
			if(!fingerprintBefore.equals(fingerprintAfter)
				|| !analysis.analysisFingerprint().equals(fingerprintBefore))
				throw new IllegalArgumentException("Supplied dynamic analysis changed during planning");
		}
	}
	private static final int MAX_ENUM_INPUTS = 20; // guard against 2^n blowups and shift overflow
	// Candidate 1: isolate the near-tie transient FOUT bundle widening heuristic
	// without disabling seed/refine, locked transient propagation, or genuinely
	// cost-improving transient-family decisions.
	private static final boolean ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE = false;
	private static final double TRANSIENT_FOUT_BUNDLE_TIE_REL_TOL = 2.5e-3;
	private static final boolean ENABLE_TRANSIENT_FAMILY_SCORING_TRACE = false;
	private static final boolean ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION = false;
	private static final boolean ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL = false;
	private record SelectedDpState(ExecType execType, FederatedOutput output, FType fType,
		boolean derivedFedFout, PlacementState exactState) { }

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		PlacementAnalysis analysis = prog.requirePlacementAnalysisAuthority();
		rewriteProgram(prog, fgraph, fcallSizes, analysis);
	}

	@Override
	public DpInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis) {
		Objects.requireNonNull(prog, "prog");
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertProgramOwner(prog);
		prog.requirePlacementAnalysisAuthority(analysis);
		String fingerprintBefore = analysis.analysisFingerprint();

		FederatedPlannerUtils.resetFederatedPlannerRunState();
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable(analysis);
		DpEnumerationResult enumerationResult = FederatedPlannerDpCostEnumerator.enumerateProgramWithReceipts(
			prog, memoTable, FederatedPlannerTrace.isEnabled(), analysis);
		return rewriteProgramWithEnumeration(prog, analysis, memoTable, enumerationResult, fingerprintBefore);
	}

	public DpInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis, FederatedPlannerDpMemoTable memoTable,
		CandidateNormalizationFixture normalizationFixture, DpEnumerationObserver observer) {
		Objects.requireNonNull(prog, "prog");
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(memoTable, "memoTable");
		analysis.assertProgramOwner(prog);
		prog.requirePlacementAnalysisAuthority(analysis);
		String fingerprintBefore = analysis.analysisFingerprint();
		DpEnumerationResult enumerationResult = FederatedPlannerDpCostEnumerator.enumerateProgramWithReceipts(
			prog, memoTable, FederatedPlannerTrace.isEnabled(), analysis, normalizationFixture, observer);
		return rewriteProgramWithEnumeration(prog, analysis, memoTable, enumerationResult, fingerprintBefore);
	}

	private DpInvocationReceipt rewriteProgramWithEnumeration(DMLProgram prog, PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, DpEnumerationResult enumerationResult,
		String fingerprintBefore) {
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = enumerationResult.optimalPlan();
		DpPlacementAdapter.ExactSelection exactSelection =
			new DpPlacementAdapter().selectExact(analysis, memoTable, optimalPlan);

		Map<Long, FederatedOutput> outputDecisions = computeOutputDecisions(memoTable, optimalPlan);
		Map<Long, ConflictEntry> rewriteConflictCheckMap =
			collectConflictsSingleBFS(memoTable, optimalPlan, outputDecisions);
		Set<Long> visitedPlanHops = new HashSet<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<Long, LocalMaterializeRequest> localMaterializeRequests = new LinkedHashMap<>();
		Map<Long, SelectedDpState> selectedStates = new LinkedHashMap<>();
		List<AppliedPlanReceipt> appliedPlans = new ArrayList<>();
		List<AdditionalRootInvocationReceipt> additionalRootInvocations = new ArrayList<>();

		for(int i = 0; i < exactSelection.selectedRootPlans().size(); i++) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = exactSelection.selectedRootPlans().get(i);
			Pair<Long, FederatedOutput> edge = exactSelection.aggregateChildEdges().get(i);
			long planningHopId = edge.getLeft();
			long executableHopId = memoTable.resolveOriginalHopId(planningHopId);
			Hop executableHop = Objects.requireNonNull(memoTable.resolveOriginalHop(planningHopId),
				"aggregate.executableHop");
			appliedPlans.add(new AppliedPlanReceipt(appliedPlans.size(), false, edge.getLeft(),
				edge.getRight(), childPlan, childPlan.getHopRef(), executableHopId, executableHop));
			rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
				rewriteConflictCheckMap, true, localMaterializeRequests, selectedStates);
		}

		for(long rootHopID : memoTable.getAdditionalRootHopIDs()) {
			if(memoTable.isVirtualClone(rootHopID))
				continue;
			FederatedPlannerDpMemoTable.FedPlan lPlan =
				memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan fPlan =
				memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan seed = lPlan == null ? fPlan : fPlan == null ? lPlan
				: lPlan.getCumulativeCost() <= fPlan.getCumulativeCost() ? lPlan : fPlan;
			if(seed == null)
				throw new IllegalArgumentException("Additional root has no selected seed: " + rootHopID);
			Hop seedHop = Objects.requireNonNull(seed.getHopRef(), "additionalRoot.seedHop");
			if(seed.getHopID() != rootHopID || seedHop.getHopID() != rootHopID)
				throw new IllegalArgumentException("Additional root seed identity differs: " + rootHopID);
			long executableHopId = memoTable.resolveOriginalHopId(rootHopID);
			Hop executableHop = Objects.requireNonNull(memoTable.resolveOriginalHop(rootHopID),
				"additionalRoot.executableHop");
			boolean alreadyVisited = visitedPlanHops.contains(rootHopID);
			rewriteHop(seed, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
				rewriteConflictCheckMap, true, localMaterializeRequests, selectedStates);
			AdditionalRootDisposition disposition = alreadyVisited
				? AdditionalRootDisposition.ALREADY_VISITED : AdditionalRootDisposition.APPLIED;
			additionalRootInvocations.add(new AdditionalRootInvocationReceipt(
				additionalRootInvocations.size(), rootHopID, seed.getFedOutType(), seed, seedHop,
				executableHopId, executableHop, disposition));
			if(disposition == AdditionalRootDisposition.APPLIED)
				appliedPlans.add(new AppliedPlanReceipt(appliedPlans.size(), true, rootHopID,
					seed.getFedOutType(), seed, seedHop, executableHopId, executableHop));
		}

		applyDeferredOutputDecisionStates(
			memoTable, outputDecisions, rewriteConflictCheckMap, localMaterializeRequests, selectedStates);
		NormalizedPlannerResult normalized = normalizeDpSelection(analysis, selectedStates, exactSelection);
		PlacementEmissionReceipt emission = PlacementEmissionTransaction.emit(prog, normalized,
			PlacementEmissionTransaction.FailureInjector.none());
		int noOps = (int) additionalRootInvocations.stream()
			.filter(invocation -> invocation.disposition() == AdditionalRootDisposition.ALREADY_VISITED).count();
		InvocationCounters counters = new InvocationCounters(1, 1, 1, appliedPlans.size(),
			additionalRootInvocations.size(), noOps, 0, 0, 0, 0, 0, 0);
		String fingerprintAfter = analysis.analysisFingerprint();
		DpSemanticConsumptionReceipt semanticConsumption = DpSemanticConsumptionReceipt.consumed(
			enumerationResult, analysis, exactSelection, fingerprintBefore, fingerprintAfter);
		return new DpInvocationReceipt(analysis, memoTable, optimalPlan, exactSelection, semanticConsumption, appliedPlans,
			additionalRootInvocations, counters,
			fingerprintBefore, fingerprintAfter, normalized, emission);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		DMLProgram prog = function.getDMLProg();
		PlacementAnalysis analysis = prog.requirePlacementAnalysisAuthority();
		rewriteFunctionDynamic(function, funcArgs, analysis);
	}

	public DpDynamicInvocationReceipt rewriteFunctionDynamic(FunctionStatementBlock function,
		LocalVariableMap funcArgs, PlacementAnalysis analysis) {
		Objects.requireNonNull(function, "function");
		Objects.requireNonNull(analysis, "analysis");
		DMLProgram prog = function.getDMLProg();
		analysis.assertProgramOwner(prog);
		prog.requirePlacementAnalysisAuthority(analysis);
		String fingerprintBefore = analysis.analysisFingerprint();
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable(analysis);
		DpEnumerationResult enumerationResult =
			FederatedPlannerDpCostEnumerator.enumerateFunctionDynamicWithReceipts(
				function, memoTable, FederatedPlannerTrace.isEnabled(), analysis);
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = enumerationResult.optimalPlan();

		Map<Long, FederatedOutput> outputDecisions = computeOutputDecisions(memoTable, optimalPlan);
		Map<Long, ConflictEntry> rewriteConflictCheckMap =
			collectConflictsSingleBFS(memoTable, optimalPlan, outputDecisions);
		Set<Long> visitedPlanHops = new HashSet<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<Long, LocalMaterializeRequest> localMaterializeRequests = new LinkedHashMap<>();
		Map<Long, SelectedDpState> selectedStates = new LinkedHashMap<>();

		for (Pair<Long, FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
					rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
						rewriteConflictCheckMap, true, localMaterializeRequests, selectedStates);
		}

		for (long rootHopID : memoTable.getAdditionalRootHopIDs()) {
			if (memoTable.isVirtualClone(rootHopID))
				continue;
			FederatedPlannerDpMemoTable.FedPlan lPlan =
				memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan fPlan =
				memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan seed =
				(lPlan == null) ? fPlan :
				(fPlan == null) ? lPlan :
				(lPlan.getCumulativeCost() <= fPlan.getCumulativeCost()) ? lPlan : fPlan;
					if (seed != null)
						rewriteHop(seed, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
							rewriteConflictCheckMap, true, localMaterializeRequests, selectedStates);
			}

		applyDeferredOutputDecisionStates(
			memoTable, outputDecisions, rewriteConflictCheckMap, localMaterializeRequests, selectedStates);
		DpPlacementAdapter.ExactSelection exactSelection =
			new DpPlacementAdapter().selectExact(analysis, memoTable, optimalPlan);
		NormalizedPlannerResult previous = PlacementEmissionTransaction.currentNormalizedResult(prog);
		NormalizedPlannerResult normalized = normalizeDpSelection(analysis, selectedStates, exactSelection, previous);
		PlacementEmissionReceipt emission = PlacementEmissionTransaction.replaceCompleteProgram(prog,
			normalized, PlacementEmissionTransaction.FailureInjector.none());
		String fingerprintAfter = analysis.analysisFingerprint();
		return new DpDynamicInvocationReceipt(
			analysis, memoTable, enumerationResult, fingerprintBefore, fingerprintAfter, normalized, emission);
	}

	private static NormalizedPlannerResult normalizeDpSelection(PlacementAnalysis analysis,
		Map<Long, SelectedDpState> selected, DpPlacementAdapter.ExactSelection exactSelection) {
		return normalizeDpSelection(analysis, selected, exactSelection, null);
	}

	private static NormalizedPlannerResult normalizeDpSelection(PlacementAnalysis analysis,
		Map<Long, SelectedDpState> selected, DpPlacementAdapter.ExactSelection exactSelection,
		NormalizedPlannerResult completeBase) {
		Map<CompiledHopKey, PlacementEmissionState> assignment = new LinkedHashMap<>();
		if(completeBase != null) {
			if(completeBase.analysis() != analysis)
				throw new IllegalArgumentException("Dynamic base authority belongs to a different analysis");
			assignment.putAll(completeBase.selectedEmissionStates());
		}
		for(var node : analysis.graph().decisionNodes()) {
			Hop hop = analysis.hop(node.key()).orElseThrow();
			SelectedDpState choice = selected.get(hop.getHopID());
			if(choice == null && completeBase == null)
				throw new IllegalStateException("DP selection omitted " + node.key());
			if(choice == null)
				continue;
			PlacementState exact = choice.exactState();
			if(exact != null && node.legalAlternatives().stream().noneMatch(state -> state == exact))
				throw new IllegalStateException("DP exact state is foreign to neutral node: " + node.key());
			List<PlacementState> matches = exact == null ? node.legalAlternatives().stream()
				.filter(state -> state.execType() == choice.execType() && state.output() == choice.output())
				.filter(state -> state.fType() == choice.fType()).toList() : List.of(exact);
			if(matches.size() != 1)
				throw new IllegalStateException("DP selection is not an exact neutral state: " + node.key()
					+ " choice=" + choice + " legal=" + node.legalAlternatives());
			assignment.put(node.key(), new PlacementEmissionState(matches.get(0), choice.derivedFedFout()));
		}
		return NormalizedPlannerResults.createWithEmissionStates(analysis, "DP", assignment,
			"objectiveBits=" + exactSelection.objectiveCostBits());
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<Long> visitedPlanHops,
		Map<Long, FType> fTypeMap) {

		rewriteHop(plan, memoTable, outputDecisions, visitedPlanHops, fTypeMap, null, false, null, null);
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<Long> visitedPlanHops,
		Map<Long, FType> fTypeMap,
		Map<Long, ConflictEntry> rewriteConflictCheckMap) {

		rewriteHop(plan, memoTable, outputDecisions, visitedPlanHops, fTypeMap, rewriteConflictCheckMap, false, null, null);
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<Long> visitedPlanHops,
		Map<Long, FType> fTypeMap,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		boolean allowOutputDecisionOverride,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests,
		Map<Long, SelectedDpState> selectedStates) {

		long planHopId = plan.getHopRef().getHopID();
		if (visitedPlanHops != null && !visitedPlanHops.add(planHopId))
			return;

		long origHopId = memoTable.resolveOriginalHopId(planHopId);
		FederatedOutput desiredOut = outputDecisions.getOrDefault(origHopId, plan.getFedOutType());

		FederatedPlannerDpMemoTable.FedPlan effectivePlan = selectRewritePlanVariant(
			memoTable, planHopId, desiredOut, plan.getFedOutType(), plan, outputDecisions,
			rewriteConflictCheckMap, allowOutputDecisionOverride);

		if (FederatedPlannerTrace.shouldTrace(memoTable.resolveOriginalHop(planHopId))) {
			FederatedPlannerTrace.log(memoTable.resolveOriginalHop(planHopId), "DP-Rewrite-Plan",
				String.format(Locale.ROOT,
					"planHop=%d origHop=%d desiredOut=%s effectiveExec=%s effectiveOut=%s effectiveCost=%.6f derivedFedFout=%s childEdges=%s",
					planHopId, origHopId, desiredOut,
					effectivePlan.getExecType(), effectivePlan.getFedOutType(),
					effectivePlan.getCumulativeCost(), effectivePlan.isDerivedFedFout(),
					effectivePlan.getChildFedPlans()));
		}

		for (Pair<Long, FederatedOutput> childFedPlanPair : effectivePlan.getChildFedPlans()) {
			long childHopID = childFedPlanPair.getKey();
			long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
					FederatedOutput childDesiredOut = outputDecisions.getOrDefault(childOrigHopID, childFedPlanPair.getValue());
						FederatedPlannerDpMemoTable.FedPlan childPlan = selectRewritePlanVariant(
							memoTable, childHopID, childDesiredOut, childFedPlanPair.getValue(), null, outputDecisions,
							rewriteConflictCheckMap, false);
				if (childPlan == null) {
					FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, effectivePlan, memoTable);
					continue;
				}
					collectDpLocalMaterializeRequest(
						memoTable, effectivePlan, childFedPlanPair, childPlan, localMaterializeRequests);
					rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
						rewriteConflictCheckMap, false, localMaterializeRequests, selectedStates);
			}

			Hop hopRef = effectivePlan.getHopRef();
			Hop targetHop = memoTable.resolveOriginalHop(planHopId);
			if (targetHop == null)
				targetHop = hopRef;

			ExecType execType = effectivePlan.getExecType();
			if (execType == null)
				throw new DMLRuntimeException("ExecType is null in FedPlan for hop " + planHopId + " / " + hopRef.getOpString());
			FederatedOutput outType = effectivePlan.getFedOutType();
			boolean applyStateToTargetHop = shouldApplyRewriteStateToTargetHop(
				memoTable, planHopId, hopRef, targetHop, execType, outType);

			boolean derivedFedFout = execType == ExecType.FED
				&& outType == FederatedOutput.FOUT
				&& effectivePlan.isDerivedFedFout();
				if(selectedStates == null) {
					applyPlannedHopState(hopRef, execType, outType, derivedFedFout);
					if(targetHop != hopRef && applyStateToTargetHop)
						applyPlannedHopState(targetHop, execType, outType, derivedFedFout);
				}
				else if(applyStateToTargetHop)
					selectedStates.put(origHopId, new SelectedDpState(execType, outType,
						outType == FederatedOutput.FOUT ? effectivePlan.getCpFoutTypeOrFType() : null,
						derivedFedFout, effectivePlan.getSelectedPlacementState()));
				if (!applyStateToTargetHop && FederatedPlannerTrace.shouldTrace(targetHop)) {
					FederatedPlannerTrace.log(targetHop, "DP-Rewrite-SkipVirtualCloneTargetState",
						String.format(Locale.ROOT,
						"planHop=%d targetHop=%d cloneExec=%s cloneOut=%s existing=%s",
						planHopId, targetHop.getHopID(), execType, outType,
						formatPlannerRecompileState(FederatedPlannerUtils.getPlannerRecompileState(targetHop))));
			}
		if(selectedStates == null)
			registerPlannerRecompileState(hopRef, applyStateToTargetHop ? targetHop : null, execType, outType);

		FType fType = effectivePlan.getFType();
		FType forwardingFType = effectivePlan.getCpFoutTypeOrFType();
		boolean isTransient = targetHop instanceof DataOp
			&& (((DataOp) targetHop).getOp() == Types.OpOpData.TRANSIENTREAD
				|| ((DataOp) targetHop).getOp() == Types.OpOpData.TRANSIENTWRITE);
		// Keep non-transient local-output FType hints so refed-policy can preserve
		// planner CP->FOUT decisions (e.g., BROADCAST) even when dims are unknown.
		FType registeredType =
			(outType == FederatedOutput.FOUT) ? fType : (!isTransient ? forwardingFType : null);
			if (registeredType != null)
				fTypeMap.put(origHopId, registeredType);
			else
				fTypeMap.remove(origHopId);
		}

	private static void applyDeferredOutputDecisionStates(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests,
		Map<Long, SelectedDpState> selectedStates) {

		if (memoTable == null || outputDecisions == null || outputDecisions.isEmpty())
			return;

		List<Long> decisionHopIDs = new ArrayList<>(outputDecisions.keySet());
		Collections.sort(decisionHopIDs);
		for (long decisionHopID : decisionHopIDs) {
			long origHopID = memoTable.resolveOriginalHopId(decisionHopID);
			FederatedOutput desiredOut = outputDecisions.get(decisionHopID);
			if (desiredOut == null)
				continue;

			Hop targetHop = memoTable.resolveOriginalHop(origHopID);
			if (targetHop == null)
				continue;

			// This pass is deliberately conservative: it only fills gaps left by the
			// selected-root rewrite traversal. If a concrete traversal already registered
			// a state for this executable hop, keep that earlier selected state instead
			// of letting an unvisited clone/side-branch decision overwrite it.
			SelectedDpState existing = selectedStates == null ? null : selectedStates.get(origHopID);
			if (existing != null) {
				if (FederatedPlannerTrace.shouldTrace(targetHop)) {
					FederatedPlannerTrace.log(targetHop, "DP-Rewrite-DeferredOutputDecisionSkip",
						String.format(Locale.ROOT,
							"decisionHop=%d origHop=%d desiredOut=%s existing=%s",
							decisionHopID, origHopID, desiredOut,
							existing.execType() + "/" + existing.output()));
				}
				continue;
			}

				FederatedPlannerDpMemoTable.FedPlan selectedPlan =
					selectCompatiblePlanVariant(memoTable, origHopID, desiredOut, outputDecisions);
				if (selectedPlan == null)
					selectedPlan = memoTable.getFedPlanAfterPrune(origHopID, desiredOut);
				if (selectedPlan != null) {
					selectedPlan = selectLoopAwareCloneFamilyRewritePlan(
						memoTable, origHopID, selectedPlan, outputDecisions, rewriteConflictCheckMap);
				}
				if (selectedPlan == null) {
					String msg = "Missing deferred output-decision plan for hop " + origHopID
						+ " desiredOut=" + desiredOut;
				if (OptimizerUtils.isStrictFederatedConflictCheck())
					throw new DMLRuntimeException(msg);
				FederatedPlannerLogger.logNullFedPlanError(origHopID, msg);
				continue;
			}

			ExecType execType = selectedPlan.getExecType();
			FederatedOutput outType = selectedPlan.getFedOutType();
			if (execType == null || outType == null)
				continue;

			boolean derivedFedFout = execType == ExecType.FED
				&& outType == FederatedOutput.FOUT
				&& selectedPlan.isDerivedFedFout();
			if(selectedStates == null) {
				applyPlannedHopState(targetHop, execType, outType, derivedFedFout);
				registerPlannerRecompileState(selectedPlan.getHopRef(), targetHop, execType, outType);
			}
			else selectedStates.put(origHopID, new SelectedDpState(execType, outType,
				outType == FederatedOutput.FOUT ? selectedPlan.getCpFoutTypeOrFType() : null,
				derivedFedFout, selectedPlan.getSelectedPlacementState()));
			collectDeferredLocalMaterializeRequests(
				memoTable, selectedPlan, outputDecisions, localMaterializeRequests);

			if (FederatedPlannerTrace.shouldTrace(targetHop)) {
				FederatedPlannerTrace.log(targetHop, "DP-Rewrite-DeferredOutputDecisionState",
					String.format(Locale.ROOT,
						"decisionHop=%d origHop=%d exec=%s out=%s cost=%.6f childEdges=%s",
						decisionHopID, origHopID, execType, outType,
						selectedPlan.getCumulativeCost(), selectedPlan.getChildFedPlans()));
			}
		}
	}

	private static void collectDeferredLocalMaterializeRequests(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests) {

		if (memoTable == null || parentPlan == null || parentPlan.getChildFedPlans() == null
			|| localMaterializeRequests == null)
			return;
		for (Pair<Long, FederatedOutput> childEdge : parentPlan.getChildFedPlans()) {
			if (childEdge == null)
				continue;
			long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
			FederatedOutput childDesiredOut =
				outputDecisions != null ? outputDecisions.get(childOrigHopID) : null;
			if (childDesiredOut == null)
				childDesiredOut = childEdge.getValue();
			FederatedPlannerDpMemoTable.FedPlan childPlan =
				selectCompatiblePlanVariant(memoTable, childEdge.getKey(), childDesiredOut, outputDecisions);
			if (childPlan == null)
				childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childDesiredOut);
			if (childPlan == null)
				childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
			if (childPlan == null)
				continue;
			collectDpLocalMaterializeRequest(
				memoTable, parentPlan, childEdge, childPlan, localMaterializeRequests);
		}
	}

	private static boolean shouldApplyRewriteStateToTargetHop(
		FederatedPlannerDpMemoTable memoTable,
		long planHopId,
		Hop hopRef,
		Hop targetHop,
		ExecType execType,
		FederatedOutput outType) {

		if (targetHop == null || targetHop == hopRef)
			return true;
		if (memoTable == null || !memoTable.isVirtualClone(planHopId))
			return true;
		FederatedPlannerUtils.PlannerRecompileState existing =
			FederatedPlannerUtils.getPlannerRecompileState(targetHop);
		if (existing == null)
			return true;
		return existing.getExecType() == execType && existing.getFederatedOutput() == outType;
	}

	private static String formatPlannerRecompileState(
		FederatedPlannerUtils.PlannerRecompileState state) {
		if (state == null)
			return "null";
		return state.getExecType() + "/" + state.getFederatedOutput();
	}

		private static void registerPlannerRecompileState(
			Hop hopRef, Hop targetHop, ExecType execType, FederatedOutput outType) {
			if (execType == null || outType == null)
				return;
			if (hopRef != null)
				FederatedPlannerUtils.registerPlannerRecompileState(hopRef, execType, outType);
			if (targetHop != null && targetHop != hopRef)
				FederatedPlannerUtils.registerPlannerRecompileState(targetHop, execType, outType);
		}

		private static void applyPlannedHopState(
			Hop hop, ExecType execType, FederatedOutput outType, boolean derivedFedFout) {

			if (hop == null || execType == null || outType == null)
				return;
			hop.setExecType(execType);
			hop.setForcedExecType(execType);
			hop.setFederatedOutput(outType);
			hop.setFederatedOutputDerived(derivedFedFout);
		}

		private static void collectDpLocalMaterializeRequest(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			Pair<Long, FederatedOutput> childEdge,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			Map<Long, LocalMaterializeRequest> localMaterializeRequests) {

			if (memoTable == null || parentPlan == null || childEdge == null || childPlan == null
				|| localMaterializeRequests == null)
				return;
			if (parentPlan.getExecType() != ExecType.CP || childEdge.getValue() != FederatedOutput.FOUT)
				return;
			if (!isDecisionMapFedFoutMatrixProducer(childPlan))
				return;

			long producerHopID = memoTable.resolveOriginalHopId(childPlan.getHopID());
			long consumerHopID = memoTable.resolveOriginalHopId(parentPlan.getHopID());
			if (producerHopID < 0 || consumerHopID < 0 || producerHopID == consumerHopID)
				return;

			Hop producerHop = memoTable.resolveOriginalHop(producerHopID);
			if (producerHop == null)
				producerHop = childPlan.getHopRef();
			Hop consumerHop = memoTable.resolveOriginalHop(consumerHopID);
			if (consumerHop == null)
				consumerHop = parentPlan.getHopRef();
			final Hop requestProducerHop = producerHop;

			LocalMaterializeRequest request = localMaterializeRequests.computeIfAbsent(
				producerHopID, id -> new LocalMaterializeRequest(id, requestProducerHop));
			request.addConsumer(consumerHopID, consumerHop, parentPlan.getFedOutType(), childPlan.getFType());
		}

		private static void registerDpLocalMaterializeRequests(
			Map<Long, LocalMaterializeRequest> localMaterializeRequests) {

			if (localMaterializeRequests == null || localMaterializeRequests.isEmpty())
				return;
			for (LocalMaterializeRequest request : localMaterializeRequests.values()) {
				if (request == null || !isLocalMaterializeProducerActive(request.producerHop))
					continue;
				List<Long> consumerHopIDs = new ArrayList<>();
				for (Map.Entry<Long, Hop> consumer : request.consumerHops.entrySet()) {
					if (consumer == null || consumer.getKey() == null)
						continue;
					// Requests are accumulated while multiple clone/output variants are
					// traversed. A later finalized FED variant can supersede an earlier
					// CP candidate for the same executable consumer. Check that final
					// state before restamping; otherwise this registration pass revives
					// a stale CP request and inserts an unnecessary federated prefetch.
					if (!isLocalMaterializeConsumerActive(consumer.getValue()))
						continue;
					restampLocalMaterializeConsumer(consumer.getValue(),
						request.consumerOutputs.get(consumer.getKey()));
					consumerHopIDs.add(consumer.getKey());
				}
				if (consumerHopIDs.isEmpty())
					continue;

				FederatedLocalMaterializeRegistry.register(
					-1L, request.producerHopID, consumerHopIDs, request.fTypeHint,
					"dp-selected-fout-to-cp-local-consumer");
				if (FederatedPlannerTrace.isEnabled()) {
					FederatedPlannerTrace.logGlobal("DP-LocalMaterialize",
						String.format(Locale.ROOT,
							"producer=%d consumers=%s ftype=%s reason=dp-selected-fout-to-cp-local-consumer",
							request.producerHopID, consumerHopIDs, request.fTypeHint));
				}
			}
		}

		private static boolean isLocalMaterializeProducerActive(Hop producerHop) {
			if (producerHop == null)
				return true;
			if (producerHop.getFederatedOutput() == FederatedOutput.LOUT)
				return false;
			ExecType forcedExec = producerHop.getForcedExecType();
			ExecType exec = producerHop.getExecType();
			return forcedExec == ExecType.FED || exec == ExecType.FED
				|| producerHop.getFederatedOutput() == FederatedOutput.FOUT;
		}

		private static boolean isLocalMaterializeConsumerActive(Hop consumerHop) {
			if (consumerHop == null)
				return true;
			ExecType forcedExec = consumerHop.getForcedExecType();
			if (forcedExec != null)
				return forcedExec == ExecType.CP;
			ExecType exec = consumerHop.getExecType();
			return exec == null || exec == ExecType.CP;
		}

		private static void restampLocalMaterializeConsumer(Hop consumerHop, FederatedOutput selectedOutput) {
			if (consumerHop == null)
				return;
			FederatedOutput out = selectedOutput != null ? selectedOutput : FederatedOutput.LOUT;
			consumerHop.setExecType(ExecType.CP);
			consumerHop.setForcedExecType(ExecType.CP);
			consumerHop.setFederatedOutput(out);
			consumerHop.setFederatedOutputDerived(false);
			FederatedPlannerUtils.registerPlannerRecompileState(consumerHop, ExecType.CP, out);
		}

		private static FederatedPlannerDpMemoTable.FedPlan selectRewritePlanVariant(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedOutput desiredOut,
		FederatedOutput inheritedOut,
		FederatedPlannerDpMemoTable.FedPlan fallbackPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		boolean allowOutputDecisionOverride) {

		if (memoTable == null)
			return fallbackPlan;

		FederatedPlannerDpMemoTable.FedPlan selected = null;
		// The output-decision map is the DP global cost/state choice. Honor it
		// before inherited-edge fallback when an executable child-compatible
		// variant already exists; otherwise keep the inherited edge below to
		// preserve a valid parent->child forest.
		if (allowOutputDecisionOverride && desiredOut != null && desiredOut != inheritedOut) {
			selected = findStrictCompatiblePlanVariant(memoTable, hopID, desiredOut, outputDecisions);
			if (selected != null)
				return selectLoopAwareCloneFamilyRewritePlan(
					memoTable, hopID, selected, outputDecisions, rewriteConflictCheckMap);
		}
		if (inheritedOut != null)
			selected = selectCompatiblePlanVariant(memoTable, hopID, inheritedOut, outputDecisions);
		if (selected == null && desiredOut != null && desiredOut != inheritedOut)
			selected = selectCompatiblePlanVariant(memoTable, hopID, desiredOut, outputDecisions);
		if (selected != null)
			return selectLoopAwareCloneFamilyRewritePlan(
				memoTable, hopID, selected, outputDecisions, rewriteConflictCheckMap);

		// Rewrite must preserve an executable parent->child forest even when the
		// global decision map is temporarily inconsistent. Prefer the inherited
		// edge/requested output over an incompatible desiredOut fallback so runtime
		// does not observe FED parents wired to local children.
		if (inheritedOut != null) {
			selected = memoTable.getFedPlanAfterPrune(hopID, inheritedOut);
			if (selected != null)
				return selectLoopAwareCloneFamilyRewritePlan(
					memoTable, hopID, selected, outputDecisions, rewriteConflictCheckMap);
		}
		if (desiredOut != null && desiredOut != inheritedOut) {
			selected = memoTable.getFedPlanAfterPrune(hopID, desiredOut);
			if (selected != null)
				return selectLoopAwareCloneFamilyRewritePlan(
					memoTable, hopID, selected, outputDecisions, rewriteConflictCheckMap);
		}

		if (fallbackPlan != null)
			return selectLoopAwareCloneFamilyRewritePlan(
				memoTable, hopID, fallbackPlan, outputDecisions, rewriteConflictCheckMap);

		FederatedPlannerDpMemoTable.FedPlan lPlan =
			memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlan fPlan =
			memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);
		FederatedPlannerDpMemoTable.FedPlan selectedPlan = (lPlan == null) ? fPlan :
			(fPlan == null) ? lPlan :
			(lPlan.getCumulativeCost() <= fPlan.getCumulativeCost()) ? lPlan : fPlan;
		return selectLoopAwareCloneFamilyRewritePlan(
			memoTable, hopID, selectedPlan, outputDecisions, rewriteConflictCheckMap);
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectLoopAwareCloneFamilyRewritePlan(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedPlannerDpMemoTable.FedPlan selectedPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap) {

		if (memoTable == null || selectedPlan == null || rewriteConflictCheckMap == null
			|| memoTable.isVirtualClone(hopID)) {
			return selectedPlan;
		}

		long origHopID = memoTable.resolveOriginalHopId(hopID);
		ConflictEntry entry = rewriteConflictCheckMap.get(origHopID);
		if (entry == null || entry.memberHopIDs == null || entry.memberHopIDs.isEmpty())
			return selectedPlan;

		LinkedHashSet<Long> familyMemberIDs = new LinkedHashSet<>();
		boolean hasVirtualClone = false;
		for (long memberHopID : selectDecisionMembers(entry.memberHopIDs, memoTable)) {
			if (memoTable.resolveOriginalHopId(memberHopID) != origHopID)
				continue;
			familyMemberIDs.add(memberHopID);
			hasVirtualClone |= memoTable.isVirtualClone(memberHopID);
		}
		familyMemberIDs.add(hopID);
		if (!hasVirtualClone || familyMemberIDs.size() <= 1)
			return selectedPlan;

		FederatedPlannerDpMemoTable.FedPlanVariants variants =
			memoTable.getFedPlanVariants(Pair.of(hopID, selectedPlan.getFedOutType()));
		if (variants == null || variants.isEmpty())
			return selectedPlan;

		double selectedFamilyCost = computeCloneFamilyRewriteCost(
			memoTable, familyMemberIDs, selectedPlan, outputDecisions, rewriteConflictCheckMap);
		FederatedPlannerDpMemoTable.FedPlan bestPlan = selectedPlan;
		double bestFamilyCost = selectedFamilyCost;

		for (FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
			if (candidate == null)
				continue;
			boolean compatible = outputDecisions == null || outputDecisions.isEmpty()
				|| isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions);
			if (!compatible) {
				if (FederatedPlannerTrace.shouldTrace(memoTable.resolveOriginalHop(hopID))) {
					FederatedPlannerTrace.log(memoTable.resolveOriginalHop(hopID),
						"DP-Rewrite-CloneFamilyCandidate", String.format(Locale.ROOT,
							"candidateExec=%s candidateOut=%s candidateCost=%.6f skipped=incompatible_child_decision",
							candidate.getExecType(), candidate.getFedOutType(), candidate.getCumulativeCost()));
				}
				continue;
			}
				double familyCost = computeCloneFamilyRewriteCost(
					memoTable, familyMemberIDs, candidate, outputDecisions, rewriteConflictCheckMap);
			if (FederatedPlannerTrace.shouldTrace(memoTable.resolveOriginalHop(hopID))) {
				FederatedPlannerTrace.log(memoTable.resolveOriginalHop(hopID),
					"DP-Rewrite-CloneFamilyCandidate", String.format(Locale.ROOT,
						"candidateExec=%s candidateOut=%s candidateCost=%.6f familyCost=%.6f",
						candidate.getExecType(), candidate.getFedOutType(), candidate.getCumulativeCost(), familyCost));
			}
			if (Double.isFinite(familyCost) && (!Double.isFinite(bestFamilyCost)
				|| familyCost + 1e-9 < bestFamilyCost)) {
				bestPlan = candidate;
				bestFamilyCost = familyCost;
			}
		}

		Hop hopRef = memoTable.resolveOriginalHop(hopID);
		if (FederatedPlannerTrace.shouldTrace(hopRef)) {
			FederatedPlannerTrace.log(hopRef, "DP-Rewrite-CloneFamily", String.format(Locale.ROOT,
				"members=%s selectedExec=%s selectedOut=%s selectedCost=%.6f selectedFamilyCost=%.6f "
					+ "effectiveExec=%s effectiveOut=%s effectiveCost=%.6f effectiveFamilyCost=%.6f changed=%s",
				familyMemberIDs,
				selectedPlan.getExecType(), selectedPlan.getFedOutType(), selectedPlan.getCumulativeCost(),
				selectedFamilyCost,
				bestPlan.getExecType(), bestPlan.getFedOutType(), bestPlan.getCumulativeCost(),
				bestFamilyCost,
				bestPlan != selectedPlan));
		}

		applyCloneFamilyRewriteStateToMembers(
			memoTable, familyMemberIDs, bestPlan, outputDecisions);

		return bestPlan;
	}

	private static void applyCloneFamilyRewriteStateToMembers(
		FederatedPlannerDpMemoTable memoTable,
		Set<Long> familyMemberIDs,
		FederatedPlannerDpMemoTable.FedPlan selectedFamilyPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null || familyMemberIDs == null || familyMemberIDs.isEmpty()
			|| selectedFamilyPlan == null)
			return;

		for (long memberHopID : familyMemberIDs) {
			FederatedPlannerDpMemoTable.FedPlan memberPlan = selectCloneFamilyMemberPlan(
				memoTable, memberHopID, selectedFamilyPlan, outputDecisions);
			if (memberPlan == null || memberPlan.getHopRef() == null)
				continue;

			ExecType execType = memberPlan.getExecType();
			FederatedOutput outType = memberPlan.getFedOutType();
				if (execType == null || outType == null)
					continue;

				Hop memberHop = memberPlan.getHopRef();
				applyPlannedHopState(memberHop, execType, outType,
					execType == ExecType.FED && outType == FederatedOutput.FOUT && memberPlan.isDerivedFedFout());
				FederatedPlannerUtils.registerPlannerRecompileState(memberHop, execType, outType);

			if (FederatedPlannerTrace.shouldTrace(memoTable.resolveOriginalHop(memberHopID))) {
				FederatedPlannerTrace.log(memoTable.resolveOriginalHop(memberHopID),
					"DP-Rewrite-CloneFamilyMemberState", String.format(Locale.ROOT,
						"member=%d virtual=%s exec=%s out=%s cost=%.6f loop=%s",
						memberHopID, memoTable.isVirtualClone(memberHopID), execType, outType,
						memberPlan.getCumulativeCost(), formatLoopContext(memberPlan.getLoopContext())));
			}
		}
	}

	private static double computeCloneFamilyRewriteCost(
		FederatedPlannerDpMemoTable memoTable,
		Set<Long> familyMemberIDs,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		Map<Long, FederatedOutput> outputDecisions) {

		return computeCloneFamilyRewriteCost(
			memoTable, familyMemberIDs, referencePlan, outputDecisions, null);
	}

	private static double computeCloneFamilyRewriteCost(
		FederatedPlannerDpMemoTable memoTable,
		Set<Long> familyMemberIDs,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap) {

		if (memoTable == null || familyMemberIDs == null || familyMemberIDs.isEmpty() || referencePlan == null)
			return Double.POSITIVE_INFINITY;

		double familyCost = 0.0;
		int matched = 0;
		for (long memberHopID : familyMemberIDs) {
			FederatedPlannerDpMemoTable.FedPlan memberPlan = selectCloneFamilyMemberPlan(
				memoTable, memberHopID, referencePlan, outputDecisions);
			if (memberPlan == null)
				return Double.POSITIVE_INFINITY;
			if (FederatedPlannerTrace.shouldTrace(referencePlan.getHopRef())) {
				FederatedPlannerTrace.log(referencePlan.getHopRef(),
					"DP-Rewrite-CloneFamilyCostMember", String.format(Locale.ROOT,
						"referenceExec=%s referenceOut=%s member=%d virtual=%s exec=%s out=%s "
							+ "cumulative=%.6f self=%.6f children=%s",
						referencePlan.getExecType(), referencePlan.getFedOutType(), memberHopID,
						memoTable.isVirtualClone(memberHopID), memberPlan.getExecType(),
						memberPlan.getFedOutType(), memberPlan.getCumulativeCost(),
						memberPlan.getSelfCost(), memberPlan.getChildFedPlans()));
			}
			// A FedPlan cumulative cost already represents the concrete candidate variant,
			// including the child-edge transfer/local-access terms chosen by DP candidate
			// enumeration. Clone-family rewrite compares the total execution cost of all
			// original/virtual family members, not a single parent-edge use of a member.
			// Therefore, do not divide by parent count here and do not add a separate
			// decision-map mixed-edge surcharge: the former underweights loop clone families,
			// while the latter charges FED/FOUT -> CP-local edges a second time.
			familyCost += memberPlan.getCumulativeCost();
			matched++;
		}
		if (matched <= 0)
			return Double.POSITIVE_INFINITY;
		if (rewriteConflictCheckMap == null || rewriteConflictCheckMap.isEmpty())
			return familyCost;

		double finalizedMaterializationCorrection =
			computeCloneFamilyFinalizedMaterializationCorrection(
				memoTable, familyMemberIDs, referencePlan, outputDecisions);
		if (finalizedMaterializationCorrection > 1e-9) {
			Hop hopRef = referencePlan.getHopRef();
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-Rewrite-FinalizedMaterializationCorrection",
					String.format(Locale.ROOT,
						"rawFamilyCost=%.6f correction=%.6f adjustedFamilyCost=%.6f",
						familyCost, finalizedMaterializationCorrection,
						familyCost + finalizedMaterializationCorrection));
			}
			familyCost += finalizedMaterializationCorrection;
		}

		SharedMaterializationCredit credit = computeCloneFamilySharedMaterializationCredit(
			memoTable, familyMemberIDs, referencePlan, outputDecisions, rewriteConflictCheckMap);
		if (credit.credit > 1e-9) {
			Hop hopRef = referencePlan.getHopRef();
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-Rewrite-SharedMaterializationCredit",
					String.format(Locale.ROOT,
						"rawFamilyCost=%.6f credit=%.6f adjustedFamilyCost=%.6f keys=%s ambientKeys=%s",
						familyCost, credit.credit, Math.max(0.0, familyCost - credit.credit),
						credit.familyKeys, credit.ambientKeys));
			}
			familyCost = Math.max(0.0, familyCost - credit.credit);
		}
		return familyCost;
	}

	private static double computeCloneFamilyFinalizedMaterializationCorrection(
		FederatedPlannerDpMemoTable memoTable,
		Set<Long> familyMemberIDs,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null || familyMemberIDs == null || familyMemberIDs.isEmpty()
			|| referencePlan == null)
			return 0.0;
		int numWorkers = Math.max(1, memoTable.getNumWorkers());
		double correction = 0.0;
		for (long memberHopID : familyMemberIDs) {
			FederatedPlannerDpMemoTable.FedPlan parentPlan = selectCloneFamilyMemberPlan(
				memoTable, memberHopID, referencePlan, outputDecisions);
			if (parentPlan == null || parentPlan.getExecType() != ExecType.CP
				|| parentPlan.getChildFedPlans() == null)
				continue;
			Hop parentHop = parentPlan.getHopRef();
			for (Pair<Long, FederatedOutput> childEdge : parentPlan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() != FederatedOutput.FOUT)
					continue;
				FederatedPlannerDpMemoTable.FedPlan childPlan = selectCompatiblePlanVariant(
					memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions);
				if (childPlan == null)
					childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
				if (!isDecisionMapFedFoutMatrixProducer(childPlan))
					continue;
				// Stable federated reads/transient families have their own selected-scope
				// cache-credit model below. This correction is only for computed FOUT
				// producers regenerated in the clone/loop family.
				if (childPlan.getHopRef() instanceof DataOp)
					continue;
				double downloadCost = computeDecisionMapFoutToCpDownloadCost(
					childPlan, childPlan.getHopRef(), numWorkers);
				double embeddedShare = FederatedPlannerDpCostEstimator
					.computeParentChildFoutToCpDownloadShare(
						parentHop, downloadCost, childPlan, parentPlan, memoTable);
				double finalizedShare = FederatedPlannerDpCostEstimator
					.computeFinalizedLocalMaterializationShareForParent(
						downloadCost, childPlan, parentPlan, memoTable);
				if (Double.isFinite(embeddedShare) && Double.isFinite(finalizedShare)
					&& finalizedShare > embeddedShare + 1e-9)
					correction += finalizedShare - embeddedShare;
			}
		}
		return correction;
	}

	private static SharedMaterializationCredit computeCloneFamilySharedMaterializationCredit(
		FederatedPlannerDpMemoTable memoTable,
		Set<Long> familyMemberIDs,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap) {

		if (memoTable == null || familyMemberIDs == null || familyMemberIDs.isEmpty()
			|| referencePlan == null || outputDecisions == null || outputDecisions.isEmpty())
			return SharedMaterializationCredit.empty();

		int numWorkers = Math.max(1, memoTable.getNumWorkers());
		LinkedHashMap<String, Double> familyEdgeCostSums = new LinkedHashMap<>();
		for (long memberHopID : familyMemberIDs) {
			// An ambient concrete materializer proves cache reuse only for its executable
			// occurrence. A virtual clone represents another recompiled runtime occurrence;
			// keep that member's materialization in the family cumulative cost unless
			// matching virtual-scope provenance is explicitly available.
			if (memoTable.isVirtualClone(memberHopID))
				continue;
			FederatedPlannerDpMemoTable.FedPlan memberPlan = selectCloneFamilyMemberPlan(
				memoTable, memberHopID, referencePlan, outputDecisions);
			Map<String, Double> memberEdges = collectStableFoutToCpMaterializationEdges(
				memoTable, memberPlan, outputDecisions, numWorkers);
			for (Map.Entry<String, Double> edge : memberEdges.entrySet()) {
				String key = edge.getKey();
				double cost = edge.getValue();
				if (key == null || !Double.isFinite(cost) || cost <= 0.0)
					continue;
				familyEdgeCostSums.merge(key, cost, Double::sum);
			}
		}
		if (familyEdgeCostSums.isEmpty())
			return SharedMaterializationCredit.empty();

		Set<String> ambientKeys = collectAmbientStableFoutToCpMaterializationKeys(
			memoTable, familyMemberIDs, outputDecisions, rewriteConflictCheckMap, numWorkers);

		double credit = 0.0;
		for (Map.Entry<String, Double> edge : familyEdgeCostSums.entrySet()) {
			double sum = edge.getValue();
			if (ambientKeys.contains(edge.getKey()))
				credit += sum;
		}
		return new SharedMaterializationCredit(
			credit,
			new LinkedHashSet<>(familyEdgeCostSums.keySet()),
			new LinkedHashSet<>(ambientKeys));
	}

	private static Set<String> collectAmbientStableFoutToCpMaterializationKeys(
		FederatedPlannerDpMemoTable memoTable,
		Set<Long> familyMemberIDs,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		int numWorkers) {

		LinkedHashSet<String> keys = new LinkedHashSet<>();
		if (memoTable == null || outputDecisions == null || outputDecisions.isEmpty()
			|| rewriteConflictCheckMap == null || rewriteConflictCheckMap.isEmpty())
			return keys;

		LinkedHashSet<Long> excludedHopIDs = new LinkedHashSet<>();
		for (Long familyMemberID : familyMemberIDs) {
			if (familyMemberID == null)
				continue;
			excludedHopIDs.add(familyMemberID);
			excludedHopIDs.add(memoTable.resolveOriginalHopId(familyMemberID));
		}

		for (Map.Entry<Long, FederatedOutput> decision : outputDecisions.entrySet()) {
			if (decision == null || decision.getKey() == null || decision.getValue() == null)
				continue;
			long parentOrigHopID = memoTable.resolveOriginalHopId(decision.getKey());
			if (excludedHopIDs.contains(decision.getKey()) || excludedHopIDs.contains(parentOrigHopID))
				continue;

			LinkedHashSet<Long> parentMemberHopIDs = new LinkedHashSet<>();
			ConflictEntry conflictEntry = rewriteConflictCheckMap.get(parentOrigHopID);
			if (conflictEntry != null && conflictEntry.memberHopIDs != null)
				parentMemberHopIDs.addAll(selectDecisionMembers(conflictEntry.memberHopIDs, memoTable));
			if (parentMemberHopIDs.isEmpty())
				parentMemberHopIDs.add(decision.getKey());

			for (long parentHopID : parentMemberHopIDs) {
				long parentMemberOrigID = memoTable.resolveOriginalHopId(parentHopID);
				if (excludedHopIDs.contains(parentHopID) || excludedHopIDs.contains(parentMemberOrigID))
					continue;
				FederatedPlannerDpMemoTable.FedPlan parentPlan =
					selectCompatiblePlanVariant(memoTable, parentHopID, decision.getValue(), outputDecisions);
				if (parentPlan == null)
					parentPlan = memoTable.getFedPlanAfterPrune(parentHopID, decision.getValue());
				keys.addAll(collectStableFoutToCpMaterializationEdges(
					memoTable, parentPlan, outputDecisions, numWorkers).keySet());
			}
		}
		for (ConflictEntry conflictEntry : rewriteConflictCheckMap.values()) {
			if (conflictEntry == null || conflictEntry.parents == null)
				continue;
			for (FederatedPlannerDpMemoTable.FedPlan parentPlan : conflictEntry.parents) {
				if (parentPlan == null)
					continue;
				long parentHopID = parentPlan.getHopID();
				long parentOrigHopID = memoTable.resolveOriginalHopId(parentHopID);
				if (excludedHopIDs.contains(parentHopID) || excludedHopIDs.contains(parentOrigHopID))
					continue;
				keys.addAll(collectStableFoutToCpMaterializationEdges(
					memoTable, parentPlan, outputDecisions, numWorkers).keySet());
			}
		}
		return keys;
	}

	private static Map<String, Double> collectStableFoutToCpMaterializationEdges(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		Map<Long, FederatedOutput> outputDecisions,
		int numWorkers) {

		LinkedHashMap<String, Double> edges = new LinkedHashMap<>();
		if (memoTable == null || parentPlan == null || parentPlan.getExecType() != ExecType.CP
			|| parentPlan.getChildFedPlans() == null || parentPlan.getChildFedPlans().isEmpty())
			return edges;
		Hop parentHop = parentPlan.getHopRef();
		if (parentHop == null || parentHop instanceof DataOp
			|| parentHop.getDataType() == null || !parentHop.getDataType().isMatrix())
			return edges;

		for (Pair<Long, FederatedOutput> childEdge : parentPlan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() != FederatedOutput.FOUT)
				continue;
			FederatedPlannerDpMemoTable.FedPlan childPlan =
				selectCompatiblePlanVariant(memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions);
			if (childPlan == null)
				childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
			if (!isDecisionMapFedFoutMatrixProducer(childPlan))
				continue;

			Hop childHop = childPlan.getHopRef();
			String materializationKey = buildStableFoutToCpMaterializationKey(
				memoTable, childPlan, outputDecisions);
			if (materializationKey == null)
				continue;

			double downloadCost = computeDecisionMapFoutToCpDownloadCost(childPlan, childHop, numWorkers);
			double edgeCost = computeSelectedScopeFoutToCpMaterializationCost(downloadCost, childPlan, parentPlan);
			if (Double.isFinite(edgeCost) && edgeCost > 0.0)
				edges.merge(materializationKey, edgeCost, Double::sum);
		}
		return edges;
	}

	private static double computeSelectedScopeFoutToCpMaterializationCost(
		double downloadCost,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan) {

		if (!Double.isFinite(downloadCost) || downloadCost <= 0.0 || childPlan == null || parentPlan == null)
			return 0.0;
		double parentDemand = parentPlan.computeForwardingWeightOfChild(
			childPlan.getLoopContext(), parentPlan.getMultiplicity());
		if (!Double.isFinite(parentDemand) || parentDemand <= 0.0)
			parentDemand = 1.0;
		return downloadCost * Math.min(1.0, parentDemand);
	}

	private static String buildStableFoutToCpMaterializationKey(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null || !isDecisionMapFedFoutMatrixProducer(childPlan))
			return null;
		Hop childHop = childPlan.getHopRef();
		if (!(childHop instanceof DataOp))
			return null;
		DataOp childData = (DataOp) childHop;
		Types.OpOpData childOp = childData.getOp();
		if (childOp == Types.OpOpData.FEDERATED || FederatedPlannerUtils.isFedInitVar(childData.getName()))
			return buildConcreteFederatedMaterializationKey(memoTable, childPlan);
		if (childOp != Types.OpOpData.TRANSIENTREAD)
			return null;

		String sourceKey = null;
		if (childPlan.getChildFedPlans() != null) {
			for (Pair<Long, FederatedOutput> producerEdge : childPlan.getChildFedPlans()) {
				if (producerEdge == null || producerEdge.getValue() != FederatedOutput.FOUT)
					continue;
				FederatedPlannerDpMemoTable.FedPlan producerPlan =
					selectCompatiblePlanVariant(memoTable, producerEdge.getKey(), producerEdge.getValue(), outputDecisions);
				if (producerPlan == null)
					producerPlan = memoTable.getFedPlanAfterPrune(producerEdge.getKey(), producerEdge.getValue());
				sourceKey = buildConcreteFederatedMaterializationKey(memoTable, producerPlan);
				if (sourceKey != null)
					break;
			}
		}
		if (sourceKey == null && FederatedPlannerUtils.isFedInitVar(childData.getName()))
			sourceKey = buildFedInitVarMaterializationKey(childData.getName());
		if (sourceKey == null)
			return null;

		return sourceKey
			+ "|read=" + nullToEmpty(childData.getName())
			+ "|ftype=" + childPlan.getFType()
			+ "|shape=" + formatMaterializationShape(childHop);
	}

	private static String buildConcreteFederatedMaterializationKey(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan plan) {

		if (memoTable == null || plan == null || plan.getExecType() != ExecType.FED
			|| plan.getFedOutType() != FederatedOutput.FOUT)
			return null;
		Hop hop = plan.getHopRef();
		if (!(hop instanceof DataOp))
			return null;
		DataOp dataOp = (DataOp) hop;
		if (dataOp.getOp() == Types.OpOpData.FEDERATED)
			return "FEDHOP:" + memoTable.resolveOriginalHopId(plan.getHopID())
				+ "|name=" + nullToEmpty(dataOp.getName())
				+ "|shape=" + formatMaterializationShape(dataOp);
		if (dataOp.getOp() == Types.OpOpData.TRANSIENTREAD
			&& FederatedPlannerUtils.isFedInitVar(dataOp.getName()))
			return buildFedInitVarMaterializationKey(dataOp.getName())
				+ "|shape=" + formatMaterializationShape(dataOp);
		return null;
	}

	private static String buildFedInitVarMaterializationKey(String varName) {
		String signature = FederatedPlannerUtils.getFedInitSignature(varName);
		return signature != null && !signature.isEmpty()
			? "FEDSIG:" + signature
			: "FEDVAR:" + nullToEmpty(varName);
	}

	private static String formatMaterializationShape(Hop hop) {
		if (hop == null)
			return "?x?";
		return hop.getDim1() + "x" + hop.getDim2();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectCloneFamilyMemberPlan(
		FederatedPlannerDpMemoTable memoTable,
		long memberHopID,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		Map<Long, FederatedOutput> outputDecisions) {

		FederatedPlannerDpMemoTable.FedPlanVariants variants =
			memoTable.getFedPlanVariants(Pair.of(memberHopID, referencePlan.getFedOutType()));
		if (variants == null || variants.isEmpty())
			return null;

		FederatedPlannerDpMemoTable.FedPlan bestSameExec = null;
		for (FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
			if (!isCloneFamilyRewriteCandidate(memoTable, candidate, referencePlan, outputDecisions))
				continue;
			if (hasSameChildOutputSignature(memoTable, referencePlan, candidate))
				return candidate;
			if (bestSameExec == null)
				bestSameExec = candidate;
		}
		if (bestSameExec != null)
			return bestSameExec;

		for (FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
			if (candidate == null || candidate.getExecType() != referencePlan.getExecType())
				continue;
			return candidate;
		}
		return null;
	}

	private static boolean isCloneFamilyRewriteCandidate(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan candidate,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		Map<Long, FederatedOutput> outputDecisions) {

		if (candidate == null || referencePlan == null || candidate.getExecType() != referencePlan.getExecType())
			return false;
		if (outputDecisions != null && !outputDecisions.isEmpty()
			&& !isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
			return false;
		return true;
	}

	private static boolean hasSameChildOutputSignature(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan referencePlan,
		FederatedPlannerDpMemoTable.FedPlan candidate) {

		return buildChildOutputSignature(memoTable, referencePlan)
			.equals(buildChildOutputSignature(memoTable, candidate));
	}

	private static Map<Long, FederatedOutput> buildChildOutputSignature(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan plan) {

		LinkedHashMap<Long, FederatedOutput> signature = new LinkedHashMap<>();
		if (memoTable == null || plan == null || plan.getChildFedPlans() == null)
			return signature;
		for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
			if (childEdge == null)
				continue;
			long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
			FederatedOutput previousOut = signature.get(childOrigHopID);
			if (previousOut != null && previousOut != childEdge.getValue())
				signature.put(childOrigHopID, null);
			else if (!signature.containsKey(childOrigHopID))
				signature.put(childOrigHopID, childEdge.getValue());
		}
		return signature;
	}

	private static Map<Long, FederatedOutput> computeOutputDecisions(
		FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan) {

		return computeOutputDecisionsInternal(
			memoTable, rootPlan, new HashMap<>(), Collections.emptyMap(), true);
	}

	private static Map<Long, FederatedOutput> simulateOutputDecisionsWithLocks(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> baseDecisions,
		Map<Long, FederatedOutput> lockedDecisions) {

		return computeOutputDecisionsInternal(
			memoTable, rootPlan, baseDecisions, lockedDecisions, false);
	}

	private static Map<Long, FederatedOutput> simulateOutputDecisionsWithLocksCached(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> baseDecisions,
		Map<Long, FederatedOutput> lockedDecisions,
		SimulationDecisionCache simulationDecisionCache) {

		if (simulationDecisionCache == null || FederatedPlannerTrace.isEnabled())
			return simulateOutputDecisionsWithLocks(memoTable, rootPlan, baseDecisions, lockedDecisions);

		SimulationDecisionKey key = new SimulationDecisionKey(baseDecisions, lockedDecisions);
		Map<Long, FederatedOutput> cached = simulationDecisionCache.get(key);
		if (cached != null)
			return new HashMap<>(cached);

		Map<Long, FederatedOutput> computed =
			simulateOutputDecisionsWithLocks(memoTable, rootPlan, baseDecisions, lockedDecisions);
		simulationDecisionCache.put(key, computed);
		return computed;
	}

	private static Map<Long, FederatedOutput> buildTentativeDecisionSnapshot(
		Map<Long, FederatedOutput> currentDecisions,
		Map<Long, FederatedOutput> nextDecisions) {

		if (currentDecisions == null || currentDecisions.isEmpty())
			return nextDecisions != null ? nextDecisions : Collections.emptyMap();
		if (nextDecisions == null || nextDecisions.isEmpty())
			return currentDecisions;
		return new OverlayDecisionMap(currentDecisions, nextDecisions);
	}

	private static Map<Long, FederatedOutput> computeOutputDecisionsInternal(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> initialDecisions,
		Map<Long, FederatedOutput> lockedDecisions,
		boolean allowTransientFamilyRefine) {

		Map<Long, FederatedOutput> decisions = new HashMap<>();
		if (initialDecisions != null)
			decisions.putAll(initialDecisions);
		if (lockedDecisions != null && !lockedDecisions.isEmpty())
			decisions.putAll(lockedDecisions);
		if (rootPlan == null)
			return decisions;

		final int numWorkers = Math.max(1, memoTable.getNumWorkers());
		// Rewrite resolves parent/child output mismatches with inherited edge-aware
		// variant selection. A second pass is required when the first pass changes
		// output states that can expose new parent/child costs for the next decision
		// refresh. Keep non-refining callers single-pass.
		final int maxIters = allowTransientFamilyRefine ? 2 : 1;
		ParentVariantDeltaCache parentVariantDeltaCache = new ParentVariantDeltaCache();
		TransientReadParentsCache transientReadParentsCache = new TransientReadParentsCache();
		SimulationDecisionCache simulationDecisionCache = new SimulationDecisionCache();
		DecisionMapScoreCache decisionMapScoreCache = new DecisionMapScoreCache(memoTable, rootPlan);
		// Initial and locked maps may be partial. Only complete refinement candidates
		// are eligible to become the incumbent returned by this search.
		Map<Long, FederatedOutput> bestDecisions = null;

		Map<Long, ConflictEntry> conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan, decisions);
		for (int iter = 0; iter < maxIters; iter++) {
			refreshConflictChoiceFeasibility(conflictCheckMap, memoTable);

			Map<Long, FederatedOutput> nextDecisions = new HashMap<>();
			if (lockedDecisions != null && !lockedDecisions.isEmpty())
				nextDecisions.putAll(lockedDecisions);

			for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
				long hopID = e.getKey();
				ConflictEntry entry = e.getValue();

				if (!entry.seenLOUT && !entry.seenFOUT)
					continue;

				FederatedOutput lockedChoice = lockedDecisions != null ? lockedDecisions.get(hopID) : null;
				Hop hopRef = memoTable.resolveOriginalHop(hopID);
				if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Entry", String.format(Locale.ROOT,
						"iter=%d seenLOUT=%s seenFOUT=%s canLOUT=%s canFOUT=%s members=%d parents=%d",
						iter, entry.seenLOUT, entry.seenFOUT, entry.canChooseLOUT, entry.canChooseFOUT,
						entry.memberHopIDs != null ? entry.memberHopIDs.size() : 0,
						entry.parents != null ? entry.parents.size() : 0));
				}

				boolean isTransientWrite = hopRef instanceof DataOp
					&& ((DataOp) hopRef).getOp() == Types.OpOpData.TRANSIENTWRITE;
					if (lockedChoice != null) {
						nextDecisions.put(hopID, lockedChoice);
						if (ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION && isTransientWrite) {
							for (long tReadHopID : collectTransientReadParents(
								memoTable, hopID, conflictCheckMap, transientReadParentsCache))
								nextDecisions.putIfAbsent(tReadHopID, lockedChoice);
						}
						continue;
				}

					if (isTransientWrite) {
						Map<Long, FederatedOutput> tentativeDecisions = null;
						FederatedOutput chosen;
							if (entry.canChooseLOUT && entry.canChooseFOUT) {
								tentativeDecisions = buildTentativeDecisionSnapshot(decisions, nextDecisions);
								chosen = resolveTransientWriteConflict(
									memoTable, hopID, entry, conflictCheckMap, tentativeDecisions, numWorkers,
									parentVariantDeltaCache, transientReadParentsCache);
							}
						else if (entry.seenLOUT && !entry.seenFOUT) {
							chosen = entry.canChooseLOUT
								? FederatedOutput.LOUT
								: (entry.canChooseFOUT ? FederatedOutput.FOUT : null);
						}
						else if (!entry.seenLOUT && entry.seenFOUT) {
							chosen = entry.canChooseFOUT
								? FederatedOutput.FOUT
								: (entry.canChooseLOUT ? FederatedOutput.LOUT : null);
							}
								else {
									tentativeDecisions = buildTentativeDecisionSnapshot(decisions, nextDecisions);
									chosen = resolveTransientWriteConflict(
										memoTable, hopID, entry, conflictCheckMap, tentativeDecisions, numWorkers,
										parentVariantDeltaCache, transientReadParentsCache);
							}
						if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine) {
							FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Chosen",
								"iter=" + iter + " chosen=" + chosen);
						}
							if (chosen != null) {
								nextDecisions.put(hopID, chosen);
								for (long tReadHopID : collectTransientReadParents(
									memoTable, hopID, conflictCheckMap, transientReadParentsCache))
									nextDecisions.put(tReadHopID, chosen);
							}
						continue;
					}

					FederatedOutput chosen;
					Map<Long, FederatedOutput> tentativeDecisions = null;
					boolean forceTransientNeighborhoodReeval =
						ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL
							&& entry.canChooseLOUT && entry.canChooseFOUT
							&& isTransientBoundaryNeighborhood(memoTable, hopID, entry);
					FederatedOutput observedOnlyOut =
						entry.seenFOUT && !entry.seenLOUT ? FederatedOutput.FOUT :
						entry.seenLOUT && !entry.seenFOUT ? FederatedOutput.LOUT : null;
					boolean forceCompatibleVariantReeval =
						entry.canChooseLOUT && entry.canChooseFOUT && !decisions.isEmpty()
							&& requiresCompatibleVariantReevaluation(
								memoTable, hopID,
								observedOnlyOut != null ? observedOnlyOut : FederatedOutput.LOUT,
								decisions);
					boolean forceCheaperAlternativeReeval = false;
					if (entry.canChooseLOUT && entry.canChooseFOUT && observedOnlyOut != null) {
						tentativeDecisions = buildTentativeDecisionSnapshot(decisions, nextDecisions);
						forceCheaperAlternativeReeval = hasCheaperAlternativeObservedChoice(
							memoTable, hopID, entry, observedOnlyOut, tentativeDecisions);
					}
					boolean forceSeenOnlyReeval = forceTransientNeighborhoodReeval
						|| forceCompatibleVariantReeval || forceCheaperAlternativeReeval;
					if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine
						&& (forceCompatibleVariantReeval || forceCheaperAlternativeReeval)) {
						FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Reeval",
							"iter=" + iter
								+ " reason="
								+ (forceCheaperAlternativeReeval ? "cheaper_alternative_output"
									: "compatible_variant_shift_or_alt_output_cheaper"));
					}
						if (entry.seenLOUT && !entry.seenFOUT) {
							chosen = forceSeenOnlyReeval
								? resolveOneHopConflict(
									memoTable, hopID, entry,
									tentativeDecisions != null ? tentativeDecisions
										: buildTentativeDecisionSnapshot(decisions, nextDecisions),
									numWorkers, conflictCheckMap, parentVariantDeltaCache)
								: (entry.canChooseLOUT ? FederatedOutput.LOUT
									: (entry.canChooseFOUT ? FederatedOutput.FOUT : null));
						}
						else if (!entry.seenLOUT && entry.seenFOUT) {
							chosen = forceSeenOnlyReeval
								? resolveOneHopConflict(
									memoTable, hopID, entry,
									tentativeDecisions != null ? tentativeDecisions
										: buildTentativeDecisionSnapshot(decisions, nextDecisions),
									numWorkers, conflictCheckMap, parentVariantDeltaCache)
								: (entry.canChooseFOUT ? FederatedOutput.FOUT
									: (entry.canChooseLOUT ? FederatedOutput.LOUT : null));
						}
						else {
							if (tentativeDecisions == null)
								tentativeDecisions = buildTentativeDecisionSnapshot(decisions, nextDecisions);
							chosen = resolveOneHopConflict(
								memoTable, hopID, entry, tentativeDecisions, numWorkers, conflictCheckMap,
								parentVariantDeltaCache);
						}

				if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Chosen",
						"iter=" + iter + " chosen=" + chosen);
				}

				if (chosen != null)
					nextDecisions.put(hopID, chosen);
			}

			if (allowTransientFamilyRefine) {
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, conflictCheckMap, nextDecisions, transientReadParentsCache, iter);
				nextDecisions = refineTransientFamilyDecisions(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, iter,
					simulationDecisionCache, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, conflictCheckMap, nextDecisions, transientReadParentsCache, iter);
				nextDecisions = refineRequiredOutputClosureDecisions(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, conflictCheckMap, nextDecisions, transientReadParentsCache, iter);
				nextDecisions = normalizeMultiWriteTransientVariableFamilies(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, lockedDecisions, iter,
					simulationDecisionCache, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				// Rewrite resolves parent/child output mismatches with inherited edge-aware
				// variant selection. An eager global repair can over-localize unrelated
				// transient chains when one incompatible downstream family forces older,
				// still-beneficial FOUT decisions to LOUT before rewrite can choose a
				// consistent forest. Keep the decision map cost-driven and let rewrite
				// enforce executable compatibility locally.
				logDecisionMapScoreBreakdown(
					memoTable, rootPlan, conflictCheckMap, decisions, nextDecisions, iter, decisionMapScoreCache);
			}

			bestDecisions = selectLowerCostDecisionMap(
				memoTable, rootPlan, bestDecisions, nextDecisions, decisionMapScoreCache);

			if (nextDecisions.equals(decisions)) {
				decisions = nextDecisions;
				break;
			}

			decisions = nextDecisions;
			conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan, decisions);
			parentVariantDeltaCache.clear();
		}

			return bestDecisions != null ? bestDecisions : decisions;
		}

	private static Map<Long, FederatedOutput> selectLowerCostDecisionMap(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> incumbent,
		Map<Long, FederatedOutput> candidate,
		DecisionMapScoreCache scoreCache) {

		if (candidate == null)
			return incumbent != null ? new HashMap<>(incumbent) : null;

		DecisionMapScoreBreakdown candidateScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidate, scoreCache);
		boolean candidateValid = Double.isFinite(candidateScore.totalCost)
			&& candidateScore.missingRootCount == 0;
		if (incumbent == null)
			return candidateValid ? new HashMap<>(candidate) : null;

		DecisionMapScoreBreakdown incumbentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, incumbent, scoreCache);
		boolean incumbentValid = Double.isFinite(incumbentScore.totalCost)
			&& incumbentScore.missingRootCount == 0;
		if (!candidateValid || (incumbentValid
			&& candidateScore.totalCost + 1e-9 >= incumbentScore.totalCost))
			return new HashMap<>(incumbent);
		return new HashMap<>(candidate);
	}

	private static Map<Long, FederatedOutput> applyLockedOutputDecisions(
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions) {

		if (lockedDecisions == null || lockedDecisions.isEmpty())
			return decisions;
		Map<Long, FederatedOutput> mergedDecisions =
			decisions != null ? new HashMap<>(decisions) : new HashMap<>();
		mergedDecisions.putAll(lockedDecisions);
		return mergedDecisions;
	}

	private static Map<Long, FederatedOutput> alignTransientReadsWithProducerDecisions(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		TransientReadParentsCache transientReadParentsCache,
		int iter) {

		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| decisions == null || decisions.isEmpty())
			return decisions;

		Map<Long, FederatedOutput> alignedDecisions = new HashMap<>(decisions);
		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long tWriteHopID = e.getKey();
			Hop hopRef = memoTable.resolveOriginalHop(tWriteHopID);
			if (!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;

			FederatedOutput producerDecision = alignedDecisions.get(tWriteHopID);
			if (producerDecision == null)
				continue;

			for (long tReadHopID : collectTransientReadParents(
				memoTable, tWriteHopID, conflictCheckMap, transientReadParentsCache)) {
				ConflictEntry tReadEntry = conflictCheckMap.get(tReadHopID);
				if (!canChooseOutput(tReadEntry, producerDecision))
					continue;
				FederatedOutput oldDecision = alignedDecisions.put(tReadHopID, producerDecision);
				if (oldDecision != producerDecision && FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-TransientReadProducerAlign",
						String.format(Locale.ROOT,
							"iter=%d tRead=%d old=%s producer=%s",
							iter, tReadHopID, oldDecision, producerDecision));
				}
			}
		}
		return alignedDecisions;
	}

	/**
	 * Keep all writes that can feed one executable transient-read clone family in
	 * a single runtime representation. A merged TRead decision can contain loop
	 * clones whose concrete plans point to different TWrites of the same variable.
	 * Choosing each write independently is not executable across iterations: a
	 * later local write can replace a federated value while the static read path
	 * remains FED/FOUT.
	 *
	 * <p>Only families proven by concrete selected-output child edges are
	 * normalized. Both legal all-LOUT and all-FOUT plans are re-simulated, and the
	 * cheaper complete plan wins. This avoids workload, hop-id, worker-count, and
	 * row-shape heuristics.</p>
	 */
	private static Map<Long, FederatedOutput> normalizeMultiWriteTransientVariableFamilies(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions,
		int iter,
		SimulationDecisionCache simulationDecisionCache,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| decisions == null || decisions.isEmpty())
			return decisions;

		Map<Long, FederatedOutput> normalizedDecisions = new HashMap<>(decisions);
		// Earlier refinements in the same decision pass can switch a TRead output
		// after conflictCheckMap was collected. Rebuild the selected traversal before
		// discovering concrete producers; otherwise a stale LOUT traversal cannot
		// expose the FOUT clone plan (or vice versa) and a later loop write escapes the
		// multi-write family normalization.
		Map<Long, ConflictEntry> normalizationConflictCheckMap =
			collectConflictsSingleBFS(memoTable, rootPlan, normalizedDecisions);
		refreshConflictChoiceFeasibility(normalizationConflictCheckMap, memoTable);
		for (Map.Entry<Long, ConflictEntry> e : normalizationConflictCheckMap.entrySet()) {
			long tReadHopID = e.getKey();
			Hop hopRef = memoTable.resolveOriginalHop(tReadHopID);
			if (!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;

			FederatedOutput selectedReadOut = normalizedDecisions.get(tReadHopID);
			if (selectedReadOut == null)
				continue;
			LinkedHashSet<Long> producerHopIDs = collectSelectedTransientReadProducerHopIDs(
				memoTable, e.getValue(), selectedReadOut, normalizedDecisions, ((DataOp) hopRef).getName());
			if (producerHopIDs.size() <= 1)
				continue;

			LinkedHashSet<Long> familyHopIDs = new LinkedHashSet<>(producerHopIDs);
			familyHopIDs.add(tReadHopID);
			FederatedOutput firstOut = null;
			boolean inconsistent = false;
			boolean complete = true;
			for (long familyHopID : familyHopIDs) {
				FederatedOutput familyOut = normalizedDecisions.get(familyHopID);
				if (familyOut == null) {
					complete = false;
					break;
				}
				if (firstOut == null)
					firstOut = familyOut;
				else if (firstOut != familyOut)
					inconsistent = true;
			}
			if (!complete || !inconsistent)
				continue;

			Map<Long, FederatedOutput> bestCandidate = null;
			DecisionMapScoreBreakdown bestScore = null;
			FederatedOutput bestOut = null;
			LinkedHashSet<Long> bestFamilyHopIDs = null;
			for (FederatedOutput targetOut : new FederatedOutput[] {
				FederatedOutput.LOUT, FederatedOutput.FOUT}) {
				LinkedHashSet<Long> candidateFamilyHopIDs = new LinkedHashSet<>(familyHopIDs);
				Map<Long, ConflictEntry> candidateConflictCheckMap = conflictCheckMap;
				boolean feasible = true;
				boolean familyExpanded;
				do {
					Map<Long, FederatedOutput> selectionDecisions = new HashMap<>(normalizedDecisions);
					for (long familyHopID : candidateFamilyHopIDs)
						selectionDecisions.put(familyHopID, targetOut);
					candidateConflictCheckMap = collectConflictsSingleBFS(
						memoTable, rootPlan, selectionDecisions);
					ConflictEntry candidateTReadEntry = candidateConflictCheckMap.get(tReadHopID);
					LinkedHashSet<Long> selectedTargetProducerHopIDs =
						collectSelectedTransientReadProducerHopIDs(
							memoTable, candidateTReadEntry, targetOut, selectionDecisions,
							((DataOp) hopRef).getName());
					if (selectedTargetProducerHopIDs.isEmpty()) {
						feasible = false;
						break;
					}
					familyExpanded = candidateFamilyHopIDs.addAll(selectedTargetProducerHopIDs);
				} while (familyExpanded);
				if (!feasible)
					continue;

				Map<Long, FederatedOutput> candidateLocks = new HashMap<>();
				if (lockedDecisions != null)
					candidateLocks.putAll(lockedDecisions);
				for (long familyHopID : candidateFamilyHopIDs) {
					ConflictEntry familyEntry = candidateConflictCheckMap.get(familyHopID);
					FederatedOutput lockedOut = candidateLocks.get(familyHopID);
					boolean canChooseTarget = familyEntry != null
						? canChooseOutput(familyEntry, targetOut)
						: memoTable.getFedPlanAfterPrune(familyHopID, targetOut) != null;
					if (!canChooseTarget
						|| (lockedOut != null && lockedOut != targetOut)) {
						feasible = false;
						break;
					}
					candidateLocks.put(familyHopID, targetOut);
				}
				if (!feasible)
					continue;

				Map<Long, FederatedOutput> candidate = simulateOutputDecisionsWithLocksCached(
					memoTable, rootPlan, normalizedDecisions, candidateLocks, simulationDecisionCache);
				for (long familyHopID : candidateFamilyHopIDs) {
					if (candidate.get(familyHopID) != targetOut) {
						feasible = false;
						break;
					}
				}
				if (!feasible)
					continue;

				DecisionMapScoreBreakdown candidateScore =
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidate, scoreCache);
				if (!Double.isFinite(candidateScore.totalCost) || candidateScore.missingRootCount != 0)
					continue;
				if (bestScore == null || candidateScore.totalCost + 1e-9 < bestScore.totalCost
					|| (Math.abs(candidateScore.totalCost - bestScore.totalCost) <= 1e-9
						&& targetOut == selectedReadOut)) {
					bestCandidate = candidate;
					bestScore = candidateScore;
					bestOut = targetOut;
					bestFamilyHopIDs = candidateFamilyHopIDs;
				}
			}

			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-MultiWriteTransientNormalize", String.format(Locale.ROOT,
					"iter=%d readOut=%s producers=%s family=%s selected=%s total=%.6f apply=%s",
					iter, selectedReadOut, producerHopIDs,
					bestFamilyHopIDs != null ? bestFamilyHopIDs : familyHopIDs, bestOut,
					bestScore != null ? bestScore.totalCost : Double.NaN, bestCandidate != null));
			}
			if (bestCandidate != null)
				normalizedDecisions = bestCandidate;
		}
		return normalizedDecisions;
	}

	private static LinkedHashSet<Long> collectSelectedTransientReadProducerHopIDs(
		FederatedPlannerDpMemoTable memoTable,
		ConflictEntry tReadEntry,
		FederatedOutput selectedReadOut,
		Map<Long, FederatedOutput> outputDecisions,
		String transientVarName) {

		LinkedHashSet<Long> producerHopIDs = new LinkedHashSet<>();
		if (memoTable == null || tReadEntry == null || tReadEntry.memberHopIDs == null
			|| selectedReadOut == null || transientVarName == null)
			return producerHopIDs;

		for (long memberHopID : selectDecisionMembers(tReadEntry.memberHopIDs, memoTable)) {
			FederatedPlannerDpMemoTable.FedPlan plan = tReadEntry.selectedMemberPlans.get(
				Pair.of(memberHopID, selectedReadOut));
			if (plan == null)
				plan = findStrictCompatiblePlanVariant(
					memoTable, memberHopID, selectedReadOut, outputDecisions);
			if (plan == null && (outputDecisions == null || outputDecisions.isEmpty()))
				plan = memoTable.getFedPlanAfterPrune(memberHopID, selectedReadOut);
			if (plan == null || plan.getChildFedPlans() == null)
				return new LinkedHashSet<>();
			for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
				if (childEdge == null)
					continue;
				long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
				Hop childRef = memoTable.resolveOriginalHop(childEdge.getKey());
				if (!(childRef instanceof DataOp))
					continue;
				DataOp childDataOp = (DataOp) childRef;
				if (childDataOp.getOp() == Types.OpOpData.TRANSIENTWRITE
					&& transientVarName.equals(childDataOp.getName()))
					producerHopIDs.add(childOrigHopID);
			}
		}
		return producerHopIDs;
	}

	private static Map<Long, FederatedOutput> refineRequiredOutputClosureDecisions(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> nextDecisions,
		int iter,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| nextDecisions == null || nextDecisions.isEmpty())
			return nextDecisions;

		Map<Long, FederatedOutput> refinedDecisions = new HashMap<>(nextDecisions);
		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, refinedDecisions, scoreCache);
		final int numWorkers = Math.max(1, memoTable.getNumWorkers());

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();
			if (entry == null || !entry.canChooseFOUT)
				continue;

			FederatedOutput chosen = refinedDecisions.get(hopID);
			if (chosen != FederatedOutput.FOUT)
				continue;

			Map<Long, FederatedOutput> candidateDecisions = new HashMap<>(refinedDecisions);
			LinkedHashSet<Long> closureHopIDs = new LinkedHashSet<>();
			applyRequiredOutputDecisionClosure(
				memoTable, hopID, chosen, conflictCheckMap,
				candidateDecisions, closureHopIDs, new HashSet<>());

			if (closureHopIDs.isEmpty() || candidateDecisions.equals(refinedDecisions))
				continue;

			DecisionMapScoreBreakdown candidateScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidateDecisions, scoreCache);
			boolean keepClosure =
				Double.isFinite(candidateScore.totalCost)
					&& candidateScore.missingRootCount == 0
					&& (currentScore.missingRootCount > 0
						|| candidateScore.totalCost + 1e-9 < currentScore.totalCost);

			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-RequiredOutputClosure-Selected", String.format(Locale.ROOT,
					"iter=%d chosen=%s closure=%s currentTotal=%.6f closureTotal=%.6f "
						+ "currentMissing=%d closureMissing=%d apply=%s",
					iter, chosen, closureHopIDs,
					currentScore.totalCost, candidateScore.totalCost,
					currentScore.missingRootCount, candidateScore.missingRootCount,
					keepClosure));
			}

			if (keepClosure) {
				refinedDecisions = candidateDecisions;
				currentScore = candidateScore;
				continue;
			}

			if (entry.canChooseLOUT) {
				Map<Long, FederatedOutput> loutDecisions = new HashMap<>(refinedDecisions);
				LinkedHashSet<Long> loutClosureHopIDs = new LinkedHashSet<>();
				applyRequiredOutputDecisionClosure(
					memoTable, hopID, FederatedOutput.LOUT, conflictCheckMap,
					loutDecisions, loutClosureHopIDs, new HashSet<>());
				applyDirectChildOutputDecisionClosure(
					memoTable, hopID, FederatedOutput.LOUT, conflictCheckMap,
					loutDecisions, loutClosureHopIDs);
				DecisionMapScoreBreakdown loutScore =
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, loutDecisions, scoreCache);
				boolean keepLout =
					Double.isFinite(loutScore.totalCost)
						&& loutScore.missingRootCount == 0
						&& (!Double.isFinite(candidateScore.totalCost)
							|| candidateScore.missingRootCount != 0
							|| loutScore.totalCost + 1e-9 < candidateScore.totalCost);

				if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-RequiredOutputClosure-Demote", String.format(Locale.ROOT,
						"iter=%d chosen=%s closure=%s loutClosure=%s closureTotal=%.6f loutTotal=%.6f "
							+ "closureMissing=%d loutMissing=%d apply=%s",
						iter, chosen, closureHopIDs, loutClosureHopIDs,
						candidateScore.totalCost, loutScore.totalCost,
						candidateScore.missingRootCount, loutScore.missingRootCount, keepLout));
				}

				if (keepLout) {
					refinedDecisions = loutDecisions;
					currentScore = loutScore;
					continue;
				}
			}

			if (Double.isFinite(candidateScore.totalCost) && candidateScore.missingRootCount == 0) {
				refinedDecisions = candidateDecisions;
				currentScore = candidateScore;
			}
		}

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();
			if (entry == null || !entry.canChooseLOUT || !entry.canChooseFOUT)
				continue;

			FederatedOutput chosen = refinedDecisions.get(hopID);
			if (chosen == null)
				continue;
			FederatedOutput alternative =
				chosen == FederatedOutput.FOUT ? FederatedOutput.LOUT : FederatedOutput.FOUT;
			if (!canChooseOutput(entry, alternative))
				continue;

			Map<Long, FederatedOutput> candidateDecisions = new HashMap<>(refinedDecisions);
			LinkedHashSet<Long> closureHopIDs = new LinkedHashSet<>();
			applyRequiredOutputDecisionClosure(
				memoTable, hopID, alternative, conflictCheckMap,
				candidateDecisions, closureHopIDs, new HashSet<>());
			applyDirectChildOutputDecisionClosure(
				memoTable, hopID, alternative, conflictCheckMap,
				candidateDecisions, closureHopIDs);

			if (closureHopIDs.isEmpty() || candidateDecisions.equals(refinedDecisions))
				continue;

			DecisionMapScoreBreakdown candidateScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidateDecisions, scoreCache);
				boolean transientTiePrefersAlternative =
					Math.abs(candidateScore.totalCost - currentScore.totalCost) <= 1e-9
						&& shouldPreferTransientWriteAlternativeOnClosureTie(
							memoTable, hopID, entry, conflictCheckMap,
							refinedDecisions, alternative, numWorkers);
					boolean directChildTiePrefersAlternative =
						Math.abs(candidateScore.totalCost - currentScore.totalCost) <= 1e-9
							&& shouldPreferDirectChildAlternativeOnClosureTie(
								memoTable, hopID, conflictCheckMap,
								refinedDecisions, candidateDecisions, alternative, numWorkers);
					boolean cloneFamilyPrefersCurrent =
						shouldKeepCloneFamilyPreferredOutput(
							memoTable, hopID, entry, refinedDecisions, chosen, alternative, numWorkers,
							conflictCheckMap);
					boolean keepAlternative =
						Double.isFinite(candidateScore.totalCost)
							&& candidateScore.missingRootCount == 0
							&& !cloneFamilyPrefersCurrent
							&& (candidateScore.totalCost + 1e-9 < currentScore.totalCost
								|| transientTiePrefersAlternative
								|| directChildTiePrefersAlternative);

			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-RequiredOutputClosure", String.format(Locale.ROOT,
							"iter=%d chosen=%s alternative=%s closure=%s currentTotal=%.6f altTotal=%.6f "
								+ "currentMissing=%d altMissing=%d transientTiePrefersAlt=%s directChildTiePrefersAlt=%s "
								+ "cloneFamilyPrefersCurrent=%s apply=%s",
							iter, chosen, alternative, closureHopIDs,
							currentScore.totalCost, candidateScore.totalCost,
							currentScore.missingRootCount, candidateScore.missingRootCount,
							transientTiePrefersAlternative, directChildTiePrefersAlternative,
							cloneFamilyPrefersCurrent, keepAlternative));
					}

			if (keepAlternative) {
				refinedDecisions = candidateDecisions;
				currentScore = candidateScore;
			}
		}

			return refinedDecisions;
		}

		private static boolean shouldKeepCloneFamilyPreferredOutput(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			ConflictEntry entry,
			Map<Long, FederatedOutput> refinedDecisions,
			FederatedOutput chosen,
			FederatedOutput alternative,
			int numWorkers,
			Map<Long, ConflictEntry> conflictCheckMap) {

			if (memoTable == null || entry == null || refinedDecisions == null
				|| chosen == null || alternative == null || chosen == alternative)
				return false;
			if (!hasVirtualCloneDecisionMember(memoTable, entry))
				return false;

			FederatedOutput cloneFamilyPreferred = resolveOneHopConflict(
				memoTable, hopID, entry, refinedDecisions, numWorkers,
				conflictCheckMap, new ParentVariantDeltaCache());
			return cloneFamilyPreferred == chosen;
		}

		private static void applyDirectChildOutputDecisionClosure(
			FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		LinkedHashSet<Long> closureHopIDs) {

		if (memoTable == null || desiredOut == null || conflictCheckMap == null
			|| decisions == null || closureHopIDs == null)
			return;

		FederatedPlannerDpMemoTable.FedPlan selectedPlan =
			findStrictCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null)
			selectedPlan = selectRequiredOutputClosurePlanVariant(memoTable, concreteHopID, desiredOut, conflictCheckMap);
		if (selectedPlan == null)
			selectedPlan = selectCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null)
			selectedPlan = memoTable.getFedPlanAfterPrune(concreteHopID, desiredOut);
		if (selectedPlan == null || selectedPlan.getChildFedPlans() == null)
			return;

		for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() == null)
				continue;
			long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
			ConflictEntry childEntry = conflictCheckMap.get(childOrigHopID);
			if (childEntry == null || !canChooseOutput(childEntry, childEdge.getValue()))
				continue;
			decisions.put(childOrigHopID, childEdge.getValue());
			closureHopIDs.add(childOrigHopID);
		}
	}

	private static boolean shouldPreferDirectChildAlternativeOnClosureTie(
		FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> refinedDecisions,
		Map<Long, FederatedOutput> candidateDecisions,
		FederatedOutput alternative,
		int numWorkers) {

		if (memoTable == null || alternative == null || conflictCheckMap == null
			|| refinedDecisions == null || candidateDecisions == null)
			return false;

		FederatedPlannerDpMemoTable.FedPlan selectedPlan =
			findStrictCompatiblePlanVariant(memoTable, concreteHopID, alternative, candidateDecisions);
		if (selectedPlan == null)
			selectedPlan = selectRequiredOutputClosurePlanVariant(memoTable, concreteHopID, alternative, conflictCheckMap);
		if (selectedPlan == null)
			selectedPlan = selectCompatiblePlanVariant(memoTable, concreteHopID, alternative, candidateDecisions);
		if (selectedPlan == null)
			selectedPlan = memoTable.getFedPlanAfterPrune(concreteHopID, alternative);
		if (selectedPlan == null || selectedPlan.getChildFedPlans() == null)
			return false;

			for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() == null)
					continue;
				long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
				FederatedOutput currentChildOut = refinedDecisions.get(childOrigHopID);
				if (currentChildOut == childEdge.getValue())
					continue;
				ConflictEntry childEntry = conflictCheckMap.get(childOrigHopID);
				if (childEntry == null || !canChooseOutput(childEntry, childEdge.getValue()))
					continue;
				FederatedOutput childPreferred = resolveOneHopConflict(
					memoTable, childOrigHopID, childEntry, candidateDecisions, numWorkers, conflictCheckMap);
				if (childPreferred == childEdge.getValue())
					return true;
			}
			return false;
		}

		private static boolean shouldPreferTransientWriteAlternativeOnClosureTie(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		ConflictEntry entry,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> refinedDecisions,
		FederatedOutput alternative,
		int numWorkers) {

		if (memoTable == null || entry == null || alternative == null)
			return false;
		Hop hopRef = memoTable.resolveOriginalHop(hopID);
		if (!(hopRef instanceof DataOp)
			|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE)
			return false;
			if (!canChooseOutput(entry, alternative))
				return false;
			if (hasConflictingTransientReadDecision(
				memoTable, hopID, conflictCheckMap, refinedDecisions, alternative)) {
				return false;
			}

			Map<Long, FederatedOutput> tentativeDecisions = new HashMap<>();
			if (refinedDecisions != null)
				tentativeDecisions.putAll(refinedDecisions);
		tentativeDecisions.put(hopID, alternative);
			FederatedOutput transientChoice = resolveTransientWriteConflict(
				memoTable, hopID, entry, conflictCheckMap, tentativeDecisions, numWorkers);
			return transientChoice == alternative;
		}

		private static boolean hasConflictingTransientReadDecision(
			FederatedPlannerDpMemoTable memoTable,
			long tWriteHopID,
			Map<Long, ConflictEntry> conflictCheckMap,
			Map<Long, FederatedOutput> refinedDecisions,
			FederatedOutput tWriteAlternative) {

			if (memoTable == null || refinedDecisions == null || tWriteAlternative == null)
				return false;
			for (long tReadHopID : collectTransientReadParents(memoTable, tWriteHopID, conflictCheckMap)) {
				long tReadOrigHopID = memoTable.resolveOriginalHopId(tReadHopID);
				FederatedOutput tReadDecision = refinedDecisions.get(tReadOrigHopID);
				if (tReadDecision != null && tReadDecision != tWriteAlternative)
					return true;
			}
			return false;
		}

		private static void applyRequiredOutputDecisionClosure(
			FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		LinkedHashSet<Long> closureHopIDs,
		Set<String> visitedStates) {

		if (memoTable == null || desiredOut == null || conflictCheckMap == null
			|| decisions == null || closureHopIDs == null || visitedStates == null)
			return;

		String stateKey = concreteHopID + "|" + desiredOut;
		if (!visitedStates.add(stateKey))
			return;

		long origHopID = memoTable.resolveOriginalHopId(concreteHopID);
		ConflictEntry entry = conflictCheckMap.get(origHopID);
		if (entry != null && canChooseOutput(entry, desiredOut)) {
			decisions.put(origHopID, desiredOut);
			closureHopIDs.add(origHopID);
		}

		FederatedPlannerDpMemoTable.FedPlan selectedPlan =
			findStrictCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null)
			selectedPlan = selectRequiredOutputClosurePlanVariant(memoTable, concreteHopID, desiredOut, conflictCheckMap);
		if (selectedPlan == null)
			selectedPlan = selectCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null)
			selectedPlan = memoTable.getFedPlanAfterPrune(concreteHopID, desiredOut);
		if (selectedPlan == null || selectedPlan.getChildFedPlans() == null)
			return;

		for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() == null)
				continue;
			if (!shouldPropagateRequiredChildOutput(desiredOut, childEdge.getValue()))
				continue;
			long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
			ConflictEntry childEntry = conflictCheckMap.get(childOrigHopID);
			if (childEntry == null || !canChooseOutput(childEntry, childEdge.getValue()))
				continue;
			decisions.put(childOrigHopID, childEdge.getValue());
			closureHopIDs.add(childOrigHopID);
			applyRequiredOutputDecisionClosure(
				memoTable, childEdge.getKey(), childEdge.getValue(), conflictCheckMap,
				decisions, closureHopIDs, visitedStates);
			}
		}

		private static FederatedPlannerDpMemoTable.FedPlan selectRequiredOutputClosurePlanVariant(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			FederatedOutput desiredOut,
			Map<Long, ConflictEntry> conflictCheckMap) {

			return new RequiredOutputClosureSearch(memoTable, conflictCheckMap).select(hopID, desiredOut).plan;
		}

		private static final class RequiredOutputClosureSearch {
			private final FederatedPlannerDpMemoTable memoTable;
			private final Map<Long, ConflictEntry> conflictCheckMap;
			private final Map<RequiredOutputStateKey, RequiredOutputSelection> resolved = new HashMap<>();
			private final Set<RequiredOutputStateKey> active = new HashSet<>();

			RequiredOutputClosureSearch(
				FederatedPlannerDpMemoTable memoTable,
				Map<Long, ConflictEntry> conflictCheckMap) {
				this.memoTable = memoTable;
				this.conflictCheckMap = conflictCheckMap;
			}

			RequiredOutputSelection select(long hopID, FederatedOutput desiredOut) {
				if (memoTable == null || desiredOut == null)
					return RequiredOutputSelection.INFEASIBLE;

				RequiredOutputStateKey stateKey = new RequiredOutputStateKey(hopID, desiredOut);
				RequiredOutputSelection cached = resolved.get(stateKey);
				if (cached != null)
					return cached;
				if (!active.add(stateKey))
					return RequiredOutputSelection.CYCLE;

				FederatedPlannerDpMemoTable.FedPlanVariants variants =
					memoTable.getFedPlanVariants(Pair.of(hopID, desiredOut));
				if (variants != null && !variants.isEmpty()) {
					for (FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
						RequiredOutputSelection candidateSelection = isCandidateFeasible(candidate, desiredOut);
						if (!candidateSelection.feasible)
							continue;
						active.remove(stateKey);
						RequiredOutputSelection selected = new RequiredOutputSelection(
							true, candidateSelection.cycleDependent, candidate);
						if (!selected.cycleDependent)
							resolved.put(stateKey, selected);
						return selected;
					}
				}

				active.remove(stateKey);
				resolved.put(stateKey, RequiredOutputSelection.INFEASIBLE);
				return RequiredOutputSelection.INFEASIBLE;
			}

			private RequiredOutputSelection isCandidateFeasible(
				FederatedPlannerDpMemoTable.FedPlan plan,
				FederatedOutput desiredOut) {

				if (plan == null || desiredOut == null)
					return RequiredOutputSelection.INFEASIBLE;
				if (plan.getChildFedPlans() == null)
					return RequiredOutputSelection.FEASIBLE;

				boolean cycleDependent = false;
				for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
					if (childEdge == null || childEdge.getValue() == null)
						continue;
					if (!shouldPropagateRequiredChildOutput(desiredOut, childEdge.getValue()))
						continue;

					long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
					ConflictEntry childEntry =
						conflictCheckMap != null ? conflictCheckMap.get(childOrigHopID) : null;
					if (childEntry != null && !canChooseOutput(childEntry, childEdge.getValue()))
						return RequiredOutputSelection.INFEASIBLE;
					if (childEntry == null
						&& memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue()) == null)
						return RequiredOutputSelection.INFEASIBLE;

					RequiredOutputSelection childSelection = select(childEdge.getKey(), childEdge.getValue());
					if (!childSelection.feasible)
						return RequiredOutputSelection.INFEASIBLE;
					cycleDependent |= childSelection.cycleDependent;
				}
				return cycleDependent ? RequiredOutputSelection.CYCLE : RequiredOutputSelection.FEASIBLE;
			}
		}

		private static final class RequiredOutputSelection {
			static final RequiredOutputSelection INFEASIBLE =
				new RequiredOutputSelection(false, false, null);
			static final RequiredOutputSelection FEASIBLE =
				new RequiredOutputSelection(true, false, null);
			static final RequiredOutputSelection CYCLE =
				new RequiredOutputSelection(true, true, null);

			final boolean feasible;
			final boolean cycleDependent;
			final FederatedPlannerDpMemoTable.FedPlan plan;

			RequiredOutputSelection(
				boolean feasible,
				boolean cycleDependent,
				FederatedPlannerDpMemoTable.FedPlan plan) {
				this.feasible = feasible;
				this.cycleDependent = cycleDependent;
				this.plan = plan;
			}
		}

		private static final class RequiredOutputStateKey {
			final long hopID;
			final FederatedOutput desiredOut;
			final int hash;

			RequiredOutputStateKey(long hopID, FederatedOutput desiredOut) {
				this.hopID = hopID;
				this.desiredOut = desiredOut;
				this.hash = 31 * Long.hashCode(hopID) + desiredOut.hashCode();
			}

			@Override
			public boolean equals(Object obj) {
				if (this == obj)
					return true;
				if (!(obj instanceof RequiredOutputStateKey))
					return false;
				RequiredOutputStateKey that = (RequiredOutputStateKey) obj;
				return this.hopID == that.hopID && this.desiredOut == that.desiredOut;
			}

			@Override
			public int hashCode() {
				return hash;
			}
		}

		private static boolean shouldPropagateRequiredChildOutput(
			FederatedOutput parentDesiredOut,
			FederatedOutput childOut) {
			return parentDesiredOut == FederatedOutput.FOUT && childOut == FederatedOutput.FOUT;
		}

		private static boolean canChooseOutput(ConflictEntry entry, FederatedOutput out) {
			if (entry == null || out == null)
				return false;
		return out == FederatedOutput.FOUT ? entry.canChooseFOUT : entry.canChooseLOUT;
	}

	private static Map<Long, FederatedOutput> refineTransientFamilyDecisions(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> nextDecisions,
		int iter,
		SimulationDecisionCache simulationDecisionCache,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| nextDecisions == null || nextDecisions.isEmpty())
			return nextDecisions;

		Map<Long, FederatedOutput> refinedDecisions = new HashMap<>(nextDecisions);
		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, refinedDecisions, scoreCache);
		Map<Long, LinkedHashSet<Long>> parentGraph = buildConflictParentGraph(memoTable, conflictCheckMap);

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;

			ConflictEntry entry = e.getValue();
			if (entry == null || !entry.canChooseLOUT || !entry.canChooseFOUT)
				continue;

			FederatedOutput chosen = refinedDecisions.get(hopID);
			if (chosen == null)
				continue;
			FederatedOutput alternative = chosen == FederatedOutput.FOUT ? FederatedOutput.LOUT : FederatedOutput.FOUT;
			LinkedHashSet<Long> familyHopIDs =
				collectTransientFamilyDecisionHopIDs(memoTable, hopID, conflictCheckMap, parentGraph);

			Map<Long, FederatedOutput> lockedAlternativeDecisions = new HashMap<>();
			lockedAlternativeDecisions.put(hopID, alternative);
			if (ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION) {
				for (long tReadHopID : collectTransientReadParents(memoTable, hopID, conflictCheckMap))
					lockedAlternativeDecisions.put(tReadHopID, alternative);
				}
				Map<Long, FederatedOutput> simulatedAlternativeDecisions =
					simulateOutputDecisionsWithLocksCached(
						memoTable, rootPlan, refinedDecisions, lockedAlternativeDecisions, simulationDecisionCache);
			DecisionMapScoreBreakdown simulatedAlternativeScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, simulatedAlternativeDecisions, scoreCache);
			boolean keepSimulatedAlternative =
				Double.isFinite(simulatedAlternativeScore.totalCost)
					&& simulatedAlternativeScore.missingRootCount == 0
					&& simulatedAlternativeScore.totalCost + 1e-9 < currentScore.totalCost;
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-TransientFamilySeed", String.format(Locale.ROOT,
					"iter=%d chosen=%s alt=%s locked=%s currentTotal=%.6f altTotal=%.6f "
						+ "currentMissing=%d altMissing=%d apply=%s",
					iter,
					chosen,
					alternative,
					lockedAlternativeDecisions.keySet(),
					currentScore.totalCost,
					simulatedAlternativeScore.totalCost,
					currentScore.missingRootCount,
					simulatedAlternativeScore.missingRootCount,
					keepSimulatedAlternative));
			}
			if (keepSimulatedAlternative) {
				refinedDecisions = simulatedAlternativeDecisions;
				currentScore = simulatedAlternativeScore;
				continue;
			}

			if (familyHopIDs.size() <= 1)
				continue;
			LinkedHashSet<Long> bundleHopIDs =
				collectTransientFamilyBundleHopIDs(memoTable, hopID, conflictCheckMap, parentGraph, 4);

			if (chosen == FederatedOutput.FOUT) {
				Map<Long, FederatedOutput> normalizedDecisions = new HashMap<>(refinedDecisions);
				LinkedHashSet<Long> normalizedFamilyHopIDs = new LinkedHashSet<>(familyHopIDs);
				int normalizedChanged = 0;
				int normalizedSkipped = 0;
				for (long familyHopID : normalizedFamilyHopIDs) {
					ConflictEntry familyEntry = conflictCheckMap.get(familyHopID);
					if (familyEntry == null || !familyEntry.canChooseFOUT) {
						normalizedSkipped++;
						continue;
					}
					FederatedOutput old = normalizedDecisions.put(familyHopID, FederatedOutput.FOUT);
					if (old != FederatedOutput.FOUT)
						normalizedChanged++;
				}
				normalizedFamilyHopIDs = expandTransientFamilyDecisionHopIDsForOutput(
					memoTable, hopID, normalizedFamilyHopIDs, conflictCheckMap,
					normalizedDecisions, FederatedOutput.FOUT);
				for (long familyHopID : normalizedFamilyHopIDs) {
					ConflictEntry familyEntry = conflictCheckMap.get(familyHopID);
					if (familyEntry == null || !familyEntry.canChooseFOUT)
						continue;
					FederatedOutput old = normalizedDecisions.put(familyHopID, FederatedOutput.FOUT);
					if (old != FederatedOutput.FOUT)
						normalizedChanged++;
				}
				if (normalizedChanged > 0) {
					DecisionMapScoreBreakdown normalizedScore =
						computeDecisionMapScoreBreakdown(memoTable, rootPlan, normalizedDecisions, scoreCache);
					boolean rawPrefersFout =
						familyHasCheaperRawAlternative(memoTable, familyHopIDs, FederatedOutput.FOUT);
					boolean keepNormalizedFout =
						Double.isFinite(normalizedScore.totalCost)
							&& normalizedScore.missingRootCount == 0
							&& (normalizedScore.totalCost + 1e-9 < currentScore.totalCost
								|| (rawPrefersFout
									&& Math.abs(normalizedScore.totalCost - currentScore.totalCost) <= 1e-9));
					if (FederatedPlannerTrace.shouldTrace(hopRef)) {
						FederatedPlannerTrace.log(hopRef, "DP-TransientFamilyNormalize", String.format(Locale.ROOT,
							"iter=%d chosen=%s family=%s changed=%d skipped=%d currentTotal=%.6f normTotal=%.6f "
								+ "currentMissing=%d normMissing=%d rawPrefersFout=%s apply=%s",
							iter,
							chosen,
							familyHopIDs,
							normalizedChanged,
							normalizedSkipped,
							currentScore.totalCost,
							normalizedScore.totalCost,
							currentScore.missingRootCount,
							normalizedScore.missingRootCount,
								rawPrefersFout,
								keepNormalizedFout));
					}
					if (keepNormalizedFout) {
						refinedDecisions = normalizedDecisions;
						currentScore = normalizedScore;
					}
				}
			}

			Map<Long, FederatedOutput> altDecisions = new HashMap<>(refinedDecisions);
			LinkedHashSet<Long> altFamilyHopIDs = new LinkedHashSet<>(familyHopIDs);
			int changed = 0;
			int skipped = 0;
			for (long familyHopID : altFamilyHopIDs) {
				ConflictEntry familyEntry = conflictCheckMap.get(familyHopID);
				if (familyEntry == null) {
					skipped++;
					continue;
				}
				boolean canChooseAlternative = alternative == FederatedOutput.FOUT
					? familyEntry.canChooseFOUT
					: familyEntry.canChooseLOUT;
				if (!canChooseAlternative) {
					skipped++;
					continue;
				}
				FederatedOutput old = altDecisions.put(familyHopID, alternative);
				if (old != alternative)
					changed++;
			}
			altFamilyHopIDs = expandTransientFamilyDecisionHopIDsForOutput(
				memoTable, hopID, altFamilyHopIDs, conflictCheckMap, altDecisions, alternative);
			for (long familyHopID : altFamilyHopIDs) {
				ConflictEntry familyEntry = conflictCheckMap.get(familyHopID);
				if (familyEntry == null)
					continue;
				boolean canChooseAlternative = alternative == FederatedOutput.FOUT
					? familyEntry.canChooseFOUT
					: familyEntry.canChooseLOUT;
				if (!canChooseAlternative)
					continue;
				FederatedOutput old = altDecisions.put(familyHopID, alternative);
				if (old != alternative)
					changed++;
			}
			if (changed == 0)
				continue;

			DecisionMapScoreBreakdown altScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions, scoreCache);
			boolean rawPrefersAlternative =
				alternative == FederatedOutput.FOUT
					&& familyHasCheaperRawAlternative(memoTable, altFamilyHopIDs, alternative);
			boolean keepFoutOnNearFamilyTie = false;
			Map<Long, FederatedOutput> bundleAltDecisions = null;
			DecisionMapScoreBreakdown bundleAltScore = null;
			LinkedHashSet<Long> feasibleBundleHopIDs = null;
			// A family-only representation switch can temporarily introduce a
			// materialization boundary that disappears only after dependent consumers
			// switch with it. Evaluate the contextually feasible bundle regardless of
			// whether that partial family state is an exact tie; the final score gate
			// below still accepts only a complete lower-cost state (or the existing
			// explicitly enabled near-tie policy).
			if (alternative == FederatedOutput.FOUT
				&& bundleHopIDs.size() > familyHopIDs.size()) {
				feasibleBundleHopIDs = collectContextuallyFeasibleTransientBundleHopIDs(
					memoTable, rootPlan, refinedDecisions, conflictCheckMap, familyHopIDs, bundleHopIDs);
				if (feasibleBundleHopIDs.size() > familyHopIDs.size()) {
					bundleAltDecisions = new HashMap<>(refinedDecisions);
					for (long bundleHopID : feasibleBundleHopIDs)
						bundleAltDecisions.put(bundleHopID, FederatedOutput.FOUT);
					bundleAltScore = computeDecisionMapScoreBreakdown(
						memoTable, rootPlan, bundleAltDecisions, scoreCache);
					double relPenalty = Math.abs(currentScore.totalCost) > 1e-9
						? (bundleAltScore.totalCost - currentScore.totalCost) / Math.abs(currentScore.totalCost)
						: Double.POSITIVE_INFINITY;
					keepFoutOnNearFamilyTie =
						ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE
							&& Double.isFinite(bundleAltScore.totalCost)
							&& bundleAltScore.missingRootCount == 0
							&& relPenalty >= -1e-9
							&& relPenalty <= TRANSIENT_FOUT_BUNDLE_TIE_REL_TOL;
				}
			}
			Map<Long, FederatedOutput> candidateDecisions = altDecisions;
			DecisionMapScoreBreakdown candidateScore = altScore;
			LinkedHashSet<Long> candidateHopIDs = altFamilyHopIDs;
			boolean applyBundle = false;
			if (bundleAltDecisions != null && bundleAltScore != null
				&& Double.isFinite(bundleAltScore.totalCost)
				&& bundleAltScore.missingRootCount == 0
				&& (keepFoutOnNearFamilyTie
					|| !Double.isFinite(altScore.totalCost)
					|| altScore.missingRootCount != 0
					|| bundleAltScore.totalCost + 1e-9 < altScore.totalCost)) {
				candidateDecisions = bundleAltDecisions;
				candidateScore = bundleAltScore;
				candidateHopIDs = feasibleBundleHopIDs != null ? feasibleBundleHopIDs : altFamilyHopIDs;
				applyBundle = true;
			}
				boolean keepAlternative =
					Double.isFinite(candidateScore.totalCost)
						&& candidateScore.missingRootCount == 0
						&& (candidateScore.totalCost + 1e-9 < currentScore.totalCost
							|| (rawPrefersAlternative
								&& Math.abs(candidateScore.totalCost - currentScore.totalCost) <= 1e-9)
							|| (applyBundle && keepFoutOnNearFamilyTie));
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-TransientFamilyRefine", String.format(Locale.ROOT,
					"iter=%d chosen=%s alt=%s family=%s bundle=%s changed=%d skipped=%d currentTotal=%.6f altTotal=%.6f "
						+ "bundleAltTotal=%.6f candidate=%s currentMissing=%d altMissing=%d rawPrefersAlt=%s keepNearTie=%s apply=%s "
						+ "applyBundle=%s feasibleBundle=%s",
					iter,
					chosen,
					alternative,
					altFamilyHopIDs,
					bundleHopIDs,
					changed,
					skipped,
					currentScore.totalCost,
					altScore.totalCost,
					bundleAltScore != null ? bundleAltScore.totalCost : Double.NaN,
					candidateHopIDs,
					currentScore.missingRootCount,
					altScore.missingRootCount,
					rawPrefersAlternative,
					keepFoutOnNearFamilyTie,
					keepAlternative,
					applyBundle,
					feasibleBundleHopIDs != null ? feasibleBundleHopIDs : familyHopIDs));
			}

			if (keepAlternative) {
				refinedDecisions = candidateDecisions;
				currentScore = candidateScore;
			}
		}

		return refinedDecisions;
	}

	private static void logDecisionMapScoreBreakdown(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> currentDecisions,
		Map<Long, FederatedOutput> nextDecisions,
		int iter,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty())
			return;

		LinkedHashSet<Hop> traceTargets = new LinkedHashSet<>();
		for (long hopID : conflictCheckMap.keySet()) {
			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (FederatedPlannerTrace.shouldTrace(hopRef))
				traceTargets.add(hopRef);
		}
		if (traceTargets.isEmpty())
			return;

		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, currentDecisions, scoreCache);
		DecisionMapScoreBreakdown nextScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, nextDecisions, scoreCache);

		for (Hop traceHop : traceTargets) {
			FederatedPlannerTrace.log(traceHop, "DP-DecisionMap-Score", String.format(Locale.ROOT,
				"iter=%d currentTotal=%.6f nextTotal=%.6f currentMain=%.6f nextMain=%.6f "
					+ "currentAdditional=%.6f nextAdditional=%.6f currentVirtual=%.6f nextVirtual=%.6f "
					+ "currentMissing=%d nextMissing=%d currentRoots=%d nextRoots=%d",
				iter,
				currentScore.totalCost, nextScore.totalCost,
				currentScore.mainRootCost, nextScore.mainRootCost,
				currentScore.additionalRootCost, nextScore.additionalRootCost,
				currentScore.virtualAdditionalRootCost, nextScore.virtualAdditionalRootCost,
				currentScore.missingRootCount, nextScore.missingRootCount,
				currentScore.rootContributions.size(), nextScore.rootContributions.size()));
			for (RootContribution contribution : nextScore.rootContributions.values()) {
				if (!contribution.additionalRoot && !contribution.virtualClone)
					continue;
				FederatedPlannerTrace.log(traceHop, "DP-DecisionMap-Root", String.format(Locale.ROOT,
					"iter=%d rootHop=%d rootOrig=%d additional=%s virtual=%s exec=%s out=%s "
						+ "cost=%.6f mult=%.6f loop=%s",
					iter,
					contribution.rootHopID,
					contribution.rootOrigHopID,
					contribution.additionalRoot,
					contribution.virtualClone,
					contribution.execType,
					contribution.fedOut,
					contribution.cost,
					contribution.multiplicity,
					contribution.loopContext));
			}
		}

		logTransientAlternativeScores(
			memoTable, rootPlan, conflictCheckMap, nextDecisions, nextScore, iter, scoreCache);
	}

	private static DecisionMapScoreBreakdown computeDecisionMapScoreBreakdown(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions,
		DecisionMapScoreCache scoreCache) {

		if (scoreCache != null)
			return scoreCache.get(outputDecisions);
		return computeDecisionMapScoreBreakdown(memoTable, rootPlan, outputDecisions);
	}

	private static DecisionMapScoreBreakdown computeDecisionMapScoreBreakdown(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		DecisionMapScoreBreakdown breakdown = new DecisionMapScoreBreakdown();
		if (memoTable == null || rootPlan == null)
			return breakdown;

		LinkedHashMap<Long, FederatedPlannerDpMemoTable.FedPlan> selectedRootPlans = new LinkedHashMap<>();
		LinkedHashSet<Long> additionalRootHopIDs = new LinkedHashSet<>();

		for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan selectedPlan = selectDecisionMapRootSeedPlan(
				memoTable, rootChild.getKey(), rootChild.getValue(), outputDecisions);
			selectedRootPlans.put(rootChild.getKey(), selectedPlan);
		}

		for (long rootHopID : memoTable.getAdditionalRootHopIDs()) {
			FederatedPlannerDpMemoTable.FedPlan selectedPlan = selectDecisionMapRootSeedPlan(
				memoTable, rootHopID, null, outputDecisions);
			selectedRootPlans.put(rootHopID, selectedPlan);
			additionalRootHopIDs.add(rootHopID);
		}

			for (Map.Entry<Long, FederatedPlannerDpMemoTable.FedPlan> entry : selectedRootPlans.entrySet()) {
				long rootHopID = entry.getKey();
				breakdown.addContribution(entry.getValue(), rootHopID, additionalRootHopIDs.contains(rootHopID),
					memoTable, selectedRootPlans, outputDecisions);
			}
			double transientLocalCost =
				computeDecisionMapTransientReadLoutMaterializationCost(memoTable, rootPlan, outputDecisions);
			double mixedFoutToCpLocalEdgeCost =
				computeDecisionMapMixedFoutToCpLocalEdgeCost(memoTable, rootPlan, outputDecisions);
			double cloneFamilyOverridePenalty =
				computeDecisionMapCloneFamilyOutputOverridePenalty(memoTable, rootPlan, outputDecisions);
			breakdown.totalCost += transientLocalCost + mixedFoutToCpLocalEdgeCost + cloneFamilyOverridePenalty;
			breakdown.additionalRootCost += transientLocalCost + mixedFoutToCpLocalEdgeCost
				+ cloneFamilyOverridePenalty;
			breakdown.virtualAdditionalRootCost += cloneFamilyOverridePenalty;

			return breakdown;
		}

		private static double computeDecisionMapCloneFamilyOutputOverridePenalty(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan,
			Map<Long, FederatedOutput> outputDecisions) {

			if (memoTable == null || rootPlan == null || outputDecisions == null || outputDecisions.isEmpty())
				return 0.0;

			Map<Long, ConflictEntry> conflictCheckMap =
				collectConflictsSingleBFS(memoTable, rootPlan, outputDecisions);
			if (conflictCheckMap.isEmpty())
				return 0.0;

			double penalty = 0.0;
			for (Map.Entry<Long, FederatedOutput> decision : outputDecisions.entrySet()) {
				if (decision == null || decision.getValue() == null)
					continue;
				long hopID = memoTable.resolveOriginalHopId(decision.getKey());
				ConflictEntry entry = conflictCheckMap.get(hopID);
				if (!hasVirtualCloneDecisionMember(memoTable, entry))
					continue;

				FederatedOutput chosenOut = decision.getValue();
				if (!canChooseOutput(entry, chosenOut))
					continue;
				FederatedOutput alternativeOut =
					chosenOut == FederatedOutput.FOUT ? FederatedOutput.LOUT : FederatedOutput.FOUT;
				if (!canChooseOutput(entry, alternativeOut))
					continue;

				ParentVariantDeltaCache parentVariantDeltaCache = new ParentVariantDeltaCache();
				double chosenCost = computeOneHopConflictAdditionalCost(
					memoTable, hopID, entry, chosenOut, outputDecisions, conflictCheckMap,
					parentVariantDeltaCache);
				double alternativeCost = computeOneHopConflictAdditionalCost(
					memoTable, hopID, entry, alternativeOut, outputDecisions, conflictCheckMap,
					parentVariantDeltaCache);
				if (!Double.isFinite(chosenCost) || !Double.isFinite(alternativeCost)
					|| chosenCost <= alternativeCost + 1e-9) {
					continue;
				}

				double decisionPenalty = chosenCost - alternativeCost;
				penalty += decisionPenalty;
				Hop hopRef = memoTable.resolveOriginalHop(hopID);
				if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-DecisionMap-CloneFamilyOverridePenalty",
						String.format(Locale.ROOT,
							"chosen=%s chosenCost=%.6f alternative=%s alternativeCost=%.6f penalty=%.6f members=%s",
							chosenOut, chosenCost, alternativeOut, alternativeCost, decisionPenalty,
							entry.memberHopIDs));
				}
			}
			return penalty;
		}

		private static boolean hasVirtualCloneDecisionMember(
			FederatedPlannerDpMemoTable memoTable,
			ConflictEntry entry) {

			if (memoTable == null || entry == null || entry.memberHopIDs == null
				|| entry.memberHopIDs.size() <= 1) {
				return false;
			}
			for (long memberHopID : selectDecisionMembers(entry.memberHopIDs, memoTable)) {
				if (memoTable.isVirtualClone(memberHopID))
					return true;
			}
			return false;
		}

		private static double computeOneHopConflictAdditionalCost(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			ConflictEntry entry,
			FederatedOutput targetOut,
			Map<Long, FederatedOutput> tentativeDecisions,
			Map<Long, ConflictEntry> conflictCheckMap,
			ParentVariantDeltaCache parentVariantDeltaCache) {

			if (memoTable == null || entry == null || targetOut == null)
				return Double.POSITIVE_INFINITY;
			if (!canChooseOutput(entry, targetOut))
				return Double.POSITIVE_INFINITY;

			Set<Long> decisionMembers = selectDecisionMembers(entry.memberHopIDs, memoTable);
			double cost = computeCompatiblePlanSelectionDelta(
				memoTable, hopID, entry, decisionMembers, targetOut, tentativeDecisions, conflictCheckMap);
			if (!Double.isFinite(cost))
				return Double.POSITIVE_INFINITY;

			Set<String> seenCostEdges = new LinkedHashSet<>();
			int numWorkers = Math.max(1, memoTable.getNumWorkers());
			for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
				if (parentPlan == null || parentPlan.getChildFedPlans() == null)
					continue;
				ExecType parentExec = parentPlan.getExecType();
				boolean parentIsFed = parentExec == ExecType.FED;
				for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
					if (edge == null || memoTable.resolveOriginalHopId(edge.getKey()) != hopID)
						continue;
					long childHopID = edge.getKey();
					if (decisionMembers != null && !decisionMembers.isEmpty()
						&& !decisionMembers.contains(childHopID))
						continue;
					FederatedOutput originalOut = edge.getValue();
					if (originalOut == targetOut)
						continue;
					String edgeKey = buildConflictCostEdgeKey(
						memoTable, parentPlan, childHopID, originalOut);
					if (!seenCostEdges.add(edgeKey))
						continue;
					double delta = computeSwitchEdgeCostDelta(
						memoTable, childHopID, originalOut, targetOut, parentPlan, parentIsFed, numWorkers,
						conflictCheckMap, parentVariantDeltaCache);
					if (!Double.isFinite(delta))
						return Double.POSITIVE_INFINITY;
					cost += delta;
				}
			}
			return cost;
		}

		private static double computeDecisionMapTransientReadLoutMaterializationCost(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan,
			Map<Long, FederatedOutput> outputDecisions) {

			if (memoTable == null || rootPlan == null || outputDecisions == null || outputDecisions.isEmpty())
				return 0.0;
			double cost = 0.0;
			Map<Long, ConflictEntry> conflictCheckMap =
				collectConflictsSingleBFS(memoTable, rootPlan, outputDecisions);
			LinkedHashSet<String> chargedMembers = new LinkedHashSet<>();
			for (Map.Entry<Long, FederatedOutput> decision : outputDecisions.entrySet()) {
				if (decision == null || decision.getValue() != FederatedOutput.LOUT)
					continue;
				long hopID = decision.getKey();
				long origHopID = memoTable.resolveOriginalHopId(hopID);
				Hop hopRef = memoTable.resolveOriginalHop(origHopID);
				if (!(hopRef instanceof DataOp)
					|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTREAD
					|| hopRef.getDataType() == null || !hopRef.getDataType().isMatrix()) {
					continue;
				}
				LinkedHashSet<Long> memberHopIDs = new LinkedHashSet<>();
				ConflictEntry conflictEntry = conflictCheckMap.get(origHopID);
				if (conflictEntry != null && conflictEntry.memberHopIDs != null)
					memberHopIDs.addAll(selectDecisionMembers(conflictEntry.memberHopIDs, memoTable));
				if (memberHopIDs.isEmpty())
					memberHopIDs.add(origHopID);
				for (long memberHopID : memberHopIDs) {
					String memberKey = origHopID + "|" + memberHopID;
					if (!chargedMembers.add(memberKey))
						continue;
					FederatedPlannerDpMemoTable.FedPlan lOutPlan =
						memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT);
					FederatedPlannerDpMemoTable.FedPlan fOutPlan =
						memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.FOUT);
					if (!transientReadLoutRequiresProducerMaterialization(memoTable, lOutPlan, outputDecisions))
						continue;
					double unitCost = computeTransientReadLoutMaterializationCostForPlan(
						lOutPlan, fOutPlan, memoTable.getNumWorkers());
					double multiplicity = computeTransientReadLoutMaterializationMultiplicity(lOutPlan);
					double memberCost = unitCost * multiplicity;
					if (memberCost > 0.0) {
						cost += memberCost;
						if (FederatedPlannerTrace.shouldTrace(hopRef)) {
							FederatedPlannerTrace.log(hopRef, "DP-TransientLoutMaterialization",
								String.format(Locale.ROOT,
									"scoreCost=%.6f member=%d memberOrig=%d multiplicity=%.6f unitCost=%.6f",
									memberCost, memberHopID, origHopID, multiplicity, unitCost));
						}
					}
				}
				}
				return cost;
			}

			private static boolean transientReadLoutRequiresProducerMaterialization(
				FederatedPlannerDpMemoTable memoTable,
				FederatedPlannerDpMemoTable.FedPlan lOutPlan,
				Map<Long, FederatedOutput> outputDecisions) {

				if (memoTable == null || !isTransientReadPlan(lOutPlan)
					|| lOutPlan.getFedOutType() != FederatedOutput.LOUT
					|| lOutPlan.getChildFedPlans() == null || lOutPlan.getChildFedPlans().isEmpty()) {
					return false;
				}

				for (Pair<Long, FederatedOutput> producerEdge : lOutPlan.getChildFedPlans()) {
					if (producerEdge == null)
						continue;
					long producerOrigHopID = memoTable.resolveOriginalHopId(producerEdge.getKey());
					FederatedOutput selectedProducerOut =
						outputDecisions != null ? outputDecisions.get(producerOrigHopID) : null;
					if (selectedProducerOut == null)
						selectedProducerOut = producerEdge.getValue();
					if (selectedProducerOut == FederatedOutput.FOUT)
						return true;
				}
				return false;
			}

			private static double computeDecisionMapMixedFoutToCpLocalEdgeCost(
				FederatedPlannerDpMemoTable memoTable,
				FederatedPlannerDpMemoTable.FedPlan rootPlan,
			Map<Long, FederatedOutput> outputDecisions) {

			if (memoTable == null || rootPlan == null || outputDecisions == null || outputDecisions.isEmpty())
				return 0.0;
				double cost = 0.0;
				int numWorkers = Math.max(1, memoTable.getNumWorkers());
				Map<Long, ConflictEntry> conflictCheckMap =
					collectConflictsSingleBFS(memoTable, rootPlan, outputDecisions);
				LinkedHashSet<String> chargedEdges = new LinkedHashSet<>();
				Set<FederatedPlannerDpMemoTable.FedPlan> visitedPlans =
					Collections.newSetFromMap(new IdentityHashMap<>());
				for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
					FederatedPlannerDpMemoTable.FedPlan selectedRootPlan = selectDecisionMapRootSeedPlan(
						memoTable, rootChild.getKey(), rootChild.getValue(), outputDecisions);
					cost += computeDecisionMapMixedFoutToCpLocalEdgeCostForSelectedPlan(
						memoTable, selectedRootPlan, outputDecisions, numWorkers, chargedEdges, visitedPlans);
				}
				for (long additionalRootHopID : memoTable.getAdditionalRootHopIDs()) {
					FederatedPlannerDpMemoTable.FedPlan selectedRootPlan = selectDecisionMapRootSeedPlan(
						memoTable, additionalRootHopID, null, outputDecisions);
					cost += computeDecisionMapMixedFoutToCpLocalEdgeCostForSelectedPlan(
						memoTable, selectedRootPlan, outputDecisions, numWorkers, chargedEdges, visitedPlans);
				}
				for (Map.Entry<Long, FederatedOutput> decision : outputDecisions.entrySet()) {
				if (decision == null || decision.getValue() == null)
					continue;
				long parentOrigHopID = memoTable.resolveOriginalHopId(decision.getKey());
				LinkedHashSet<Long> parentMemberHopIDs = new LinkedHashSet<>();
				ConflictEntry conflictEntry = conflictCheckMap.get(parentOrigHopID);
				if (conflictEntry != null && conflictEntry.memberHopIDs != null)
					parentMemberHopIDs.addAll(selectDecisionMembers(conflictEntry.memberHopIDs, memoTable));
					if (parentMemberHopIDs.isEmpty())
						parentMemberHopIDs.add(parentOrigHopID);

				for (long parentHopID : parentMemberHopIDs) {
					FederatedPlannerDpMemoTable.FedPlan parentPlan =
						selectCompatiblePlanVariant(memoTable, parentHopID, decision.getValue(), outputDecisions);
						if (parentPlan == null)
							parentPlan = memoTable.getFedPlanAfterPrune(parentHopID, decision.getValue());
						cost += computeDecisionMapMixedFoutToCpLocalEdgeCostForSelectedPlan(
							memoTable, parentPlan, outputDecisions, numWorkers, chargedEdges, visitedPlans);
					}
				}
				return cost;
			}

		private static double computeDecisionMapMixedFoutToCpLocalEdgeCostForSelectedPlan(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			Map<Long, FederatedOutput> outputDecisions,
			int numWorkers,
			Set<String> chargedEdges,
			Set<FederatedPlannerDpMemoTable.FedPlan> visitedPlans) {

			if (memoTable == null || parentPlan == null || visitedPlans == null
				|| !visitedPlans.add(parentPlan))
				return 0.0;

			double cost = computeDecisionMapMixedFoutToCpLocalEdgeCostForParentPlan(
				memoTable, parentPlan, outputDecisions, numWorkers, chargedEdges);
			if (parentPlan.getChildFedPlans() == null)
				return cost;
			for (Pair<Long, FederatedOutput> childEdge : parentPlan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() == null)
					continue;
				FederatedPlannerDpMemoTable.FedPlan childPlan = selectCompatiblePlanVariant(
					memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions);
				if (childPlan == null)
					childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
				cost += computeDecisionMapMixedFoutToCpLocalEdgeCostForSelectedPlan(
					memoTable, childPlan, outputDecisions, numWorkers, chargedEdges, visitedPlans);
			}
			return cost;
		}

		private static double computeDecisionMapMixedFoutToCpLocalEdgeCostForParentPlan(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			Map<Long, FederatedOutput> outputDecisions,
			int numWorkers,
			Set<String> chargedEdges) {

			if (memoTable == null || parentPlan == null || parentPlan.getExecType() != ExecType.CP
				|| parentPlan.getChildFedPlans() == null || parentPlan.getChildFedPlans().isEmpty())
				return 0.0;
			Hop parentHop = parentPlan.getHopRef();
			if (parentHop == null || parentHop instanceof DataOp
				|| parentHop.getDataType() == null || !parentHop.getDataType().isMatrix())
				return 0.0;

			double cost = 0.0;
			long parentOrigHopID = memoTable.resolveOriginalHopId(parentHop.getHopID());
			for (Pair<Long, FederatedOutput> childEdge : parentPlan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() != FederatedOutput.FOUT)
					continue;
				FederatedPlannerDpMemoTable.FedPlan childPlan =
					selectCompatiblePlanVariant(memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions);
				if (childPlan == null)
					childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
				if (!isDecisionMapFedFoutMatrixProducer(childPlan))
					continue;
				Hop childHop = childPlan.getHopRef();
				String edgeKey = parentHop.getHopID() + "|" + parentOrigHopID + "|"
					+ childPlan.getHopID() + "|" + memoTable.resolveOriginalHopId(childPlan.getHopID())
					+ "|" + childEdge.getValue();
				if (chargedEdges != null && !chargedEdges.add(edgeKey))
					continue;
				double downloadCost = computeDecisionMapFoutToCpDownloadCost(childPlan, childHop, numWorkers);
				double edgeCost = FederatedPlannerDpCostEstimator.computeParentChildFoutToCpDownloadShare(
					parentHop, downloadCost, childPlan, parentPlan, memoTable);
				if (Double.isFinite(edgeCost) && edgeCost > 0.0) {
					cost += edgeCost;
					if (FederatedPlannerTrace.shouldTrace(parentHop) || FederatedPlannerTrace.shouldTrace(childHop)) {
						FederatedPlannerTrace.log(parentHop, "DP-DecisionMap-MixedFoutCpLocalEdge",
							String.format(Locale.ROOT,
								"scoreCost=%.6f parentHop=%d parentOrig=%d childHop=%d childOrig=%d "
									+ "parentExec=%s parentOut=%s childExec=%s childOut=%s",
								edgeCost,
								parentHop.getHopID(), parentOrigHopID,
								childPlan.getHopID(), memoTable.resolveOriginalHopId(childPlan.getHopID()),
								parentPlan.getExecType(), parentPlan.getFedOutType(),
								childPlan.getExecType(), childPlan.getFedOutType()));
					}
				}
			}
			return cost;
		}

		private static boolean isDecisionMapFedFoutMatrixProducer(
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
			if (childPlan == null || childPlan.getExecType() != ExecType.FED
				|| childPlan.getFedOutType() != FederatedOutput.FOUT)
				return false;
			Hop childHop = childPlan.getHopRef();
			return childHop != null && childHop.getDataType() != null && childHop.getDataType().isMatrix();
		}

		private static double computeDecisionMapFoutToCpDownloadCost(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			Hop childHop,
			int numWorkers) {

			if (childPlan == null || childHop == null)
				return 0.0;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childHop))
				return 0.0;
			if (childPlan.getExecType() == ExecType.CP)
				return 0.0;
			return FederatedPlannerDpCostEstimator.computeDownloadNetworkCost(
				FederatedCostModel.getEffectiveOutputMemEstimate(childHop),
				childPlan.getFType(), numWorkers);
		}

		private static double computeTransientReadLoutMaterializationMultiplicity(
			FederatedPlannerDpMemoTable.FedPlan lOutPlan) {

			if (lOutPlan == null)
				return 1.0;
			double multiplicity = lOutPlan.getMultiplicity();
			return Double.isFinite(multiplicity) && multiplicity > 0.0 ? multiplicity : 1.0;
		}

		private static double computeTransientReadLoutMaterializationCostForPlan(
			FederatedPlannerDpMemoTable.FedPlan lOutPlan,
			FederatedPlannerDpMemoTable.FedPlan fOutPlan,
			int numWorkers) {

			if (lOutPlan == null || fOutPlan == null || !isTransientReadPlan(lOutPlan)
				|| lOutPlan.getFedOutType() != FederatedOutput.LOUT)
				return 0.0;
			double memEstimate = FederatedCostModel.getEffectiveOutputMemEstimate(lOutPlan.getHopRef());
			if (memEstimate <= 0.0)
				memEstimate = FederatedCostModel.getEffectiveUploadMemEstimate(lOutPlan.getHopRef());
			if (memEstimate <= 0.0)
				return 0.0;
			FType transferType = lOutPlan.getCpFoutTypeOrFType();
			if (transferType == null)
				transferType = fOutPlan.getCpFoutTypeOrFType();
			double cost = FederatedPlannerDpCostEstimator.computeDownloadNetworkCost(
				memEstimate, transferType, numWorkers);
			return Double.isFinite(cost) && cost > 0.0 ? cost : 0.0;
		}

	private static double computeDecisionMapRootContributionCost(
		FederatedPlannerDpMemoTable.FedPlan plan,
		boolean additionalRoot,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedRootPlans,
		Map<Long, FederatedOutput> outputDecisions,
		IdentityHashMap<FederatedPlannerDpMemoTable.FedPlan, Double> compatibleChildAdjustmentCache) {

		if (plan == null)
			return Double.NaN;

		IdentityHashMap<FederatedPlannerDpMemoTable.FedPlan, Double> adjustmentCache =
			compatibleChildAdjustmentCache != null ? compatibleChildAdjustmentCache : new IdentityHashMap<>();
		double cost = plan.getCumulativeCost()
			+ computeDecisionMapCompatibleChildVariantAdjustment(
					memoTable, plan, outputDecisions,
					Collections.newSetFromMap(new IdentityHashMap<>()), adjustmentCache);
		if (!additionalRoot || memoTable == null || selectedRootPlans == null || selectedRootPlans.isEmpty()
			|| plan.getChildFedPlans() == null || plan.getChildFedPlans().isEmpty()) {
			return cost;
		}

		LinkedHashSet<Long> normalizedProducerRoots = new LinkedHashSet<>();
		for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
			if (childEdge == null)
				continue;
			FederatedPlannerDpMemoTable.FedPlan childPlan =
				selectCompatiblePlanVariant(memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions);
			if (childPlan == null)
				childPlan = memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
			if (!isTransientReadPlan(childPlan) || childPlan.getChildFedPlans() == null)
				continue;

			for (Pair<Long, FederatedOutput> producerEdge : childPlan.getChildFedPlans()) {
				if (producerEdge == null || !normalizedProducerRoots.add(producerEdge.getKey()))
					continue;
				FederatedPlannerDpMemoTable.FedPlan producerRootPlan = selectedRootPlans.get(producerEdge.getKey());
				if (producerRootPlan == null || producerRootPlan == plan || !isTransientWritePlan(producerRootPlan))
					continue;
				cost -= producerRootPlan.getCumulativeCostPerParents();
			}
		}

		return Math.max(0.0, cost);
	}

	private static double computeDecisionMapCompatibleChildVariantAdjustment(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		return computeDecisionMapCompatibleChildVariantAdjustment(
			memoTable, parentPlan, outputDecisions,
			Collections.newSetFromMap(new IdentityHashMap<>()), new IdentityHashMap<>());
	}

	private static double computeDecisionMapCompatibleChildVariantAdjustment(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Set<FederatedPlannerDpMemoTable.FedPlan> activePlans,
		IdentityHashMap<FederatedPlannerDpMemoTable.FedPlan, Double> adjustmentCache) {

		if (memoTable == null || parentPlan == null || outputDecisions == null || outputDecisions.isEmpty()
			|| parentPlan.getChildFedPlans() == null || parentPlan.getChildFedPlans().isEmpty()
			|| activePlans == null || adjustmentCache == null)
			return 0.0;

		Double cachedAdjustment = adjustmentCache.get(parentPlan);
		if (cachedAdjustment != null)
			return cachedAdjustment;
		if (!activePlans.add(parentPlan))
			return 0.0;

		double adjustment = 0.0;
		boolean parentIsFed = parentPlan.getExecType() == ExecType.FED;
		int numWorkers = Math.max(1, memoTable.getNumWorkers());

		for (Pair<Long, FederatedOutput> childEdge : parentPlan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() == null)
				continue;

			FederatedPlannerDpMemoTable.FedPlan baseChildPlan =
				memoTable.getFedPlanAfterPrune(childEdge.getKey(), childEdge.getValue());
			FederatedPlannerDpMemoTable.FedPlan compatibleChildPlan =
				findStrictCompatiblePlanVariant(
					memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions);
			if (baseChildPlan == null)
				continue;
			if (compatibleChildPlan == null)
				compatibleChildPlan = baseChildPlan;

			double compatibleNestedAdjustment = computeDecisionMapCompatibleChildVariantAdjustment(
				memoTable, compatibleChildPlan, outputDecisions, activePlans, adjustmentCache);
			double baseShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				baseChildPlan.getCumulativeCost(), baseChildPlan);
			double compatibleShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				compatibleChildPlan.getCumulativeCost() + compatibleNestedAdjustment, compatibleChildPlan);
			double baseForwarding = computeParentChildForwardingCostShareWithoutTrace(
				memoTable, parentIsFed, childEdge.getValue(), baseChildPlan, parentPlan, numWorkers);
			double compatibleForwarding = computeParentChildForwardingCostShareWithoutTrace(
				memoTable, parentIsFed, childEdge.getValue(), compatibleChildPlan, parentPlan, numWorkers);
			double childAdjustment = (compatibleShare + compatibleForwarding) - (baseShare + baseForwarding);
			if (Math.abs(childAdjustment) <= 1e-12)
				continue;
			adjustment += childAdjustment;
		}

		activePlans.remove(parentPlan);
		adjustmentCache.put(parentPlan, adjustment);
		return adjustment;
	}

	private static boolean isTransientReadPlan(FederatedPlannerDpMemoTable.FedPlan plan) {
		if (plan == null || !(plan.getHopRef() instanceof DataOp))
			return false;
		return ((DataOp) plan.getHopRef()).getOp() == Types.OpOpData.TRANSIENTREAD;
	}

	private static boolean isTransientWritePlan(FederatedPlannerDpMemoTable.FedPlan plan) {
		if (plan == null || !(plan.getHopRef() instanceof DataOp))
			return false;
		return ((DataOp) plan.getHopRef()).getOp() == Types.OpOpData.TRANSIENTWRITE;
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectDecisionMapRootSeedPlan(
		FederatedPlannerDpMemoTable memoTable,
		long rootHopID,
		FederatedOutput defaultOut,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null)
			return null;
		long rootOrigId = memoTable.resolveOriginalHopId(rootHopID);
		FederatedOutput desiredOut =
			outputDecisions != null ? outputDecisions.get(rootOrigId) : null;
		if (desiredOut == null)
			desiredOut = defaultOut;

		FederatedPlannerDpMemoTable.FedPlan lPlan =
			memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlan fPlan =
			memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.FOUT);

		if (desiredOut != null) {
			FederatedPlannerDpMemoTable.FedPlan selected =
				selectCompatiblePlanVariant(memoTable, rootHopID, desiredOut, outputDecisions);
			if (selected == null)
				selected = memoTable.getFedPlanAfterPrune(rootHopID, desiredOut);
			return selected;
		}

		FederatedPlannerDpMemoTable.FedPlan seed =
			(lPlan == null) ? fPlan :
			(fPlan == null) ? lPlan :
			(lPlan.getCumulativeCost() <= fPlan.getCumulativeCost()) ? lPlan : fPlan;
		if (seed == null)
			return null;
		FederatedPlannerDpMemoTable.FedPlan selected =
			selectCompatiblePlanVariant(memoTable, rootHopID, seed.getFedOutType(), outputDecisions);
		return selected != null ? selected : seed;
	}

	private static void logTransientAlternativeScores(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> nextDecisions,
		DecisionMapScoreBreakdown nextScore,
		int iter,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || nextDecisions == null)
			return;

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE
				|| !FederatedPlannerTrace.shouldTrace(hopRef))
				continue;

			ConflictEntry entry = e.getValue();
			if (entry == null || !entry.canChooseLOUT || !entry.canChooseFOUT)
				continue;

			FederatedOutput chosen = nextDecisions.get(hopID);
			if (chosen == null)
				continue;
			FederatedOutput alternative = chosen == FederatedOutput.FOUT ? FederatedOutput.LOUT : FederatedOutput.FOUT;

			Map<Long, FederatedOutput> altDecisions = new HashMap<>(nextDecisions);
			altDecisions.put(hopID, alternative);
			for (long tReadHopID : collectTransientReadParents(memoTable, hopID, conflictCheckMap))
				altDecisions.put(tReadHopID, alternative);

			DecisionMapScoreBreakdown altScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions, scoreCache);
			FederatedPlannerTrace.log(hopRef, "DP-DecisionMap-AltScore", String.format(Locale.ROOT,
				"iter=%d chosen=%s chosenTotal=%.6f alt=%s altTotal=%.6f "
					+ "chosenAdditional=%.6f altAdditional=%.6f chosenVirtual=%.6f altVirtual=%.6f",
				iter,
				chosen,
				nextScore.totalCost,
				alternative,
				altScore.totalCost,
				nextScore.additionalRootCost,
				altScore.additionalRootCost,
				nextScore.virtualAdditionalRootCost,
				altScore.virtualAdditionalRootCost));
			for (RootContribution contribution : altScore.rootContributions.values()) {
				if (!contribution.additionalRoot && !contribution.virtualClone)
					continue;
				FederatedPlannerTrace.log(hopRef, "DP-DecisionMap-AltRoot", String.format(Locale.ROOT,
					"iter=%d alt=%s rootHop=%d rootOrig=%d additional=%s virtual=%s exec=%s out=%s "
						+ "cost=%.6f mult=%.6f loop=%s",
					iter,
					alternative,
					contribution.rootHopID,
					contribution.rootOrigHopID,
					contribution.additionalRoot,
					contribution.virtualClone,
					contribution.execType,
					contribution.fedOut,
					contribution.cost,
					contribution.multiplicity,
					contribution.loopContext));
			}
		}

		logTransientBundleAlternativeScores(
			memoTable, rootPlan, conflictCheckMap, nextDecisions, nextScore, iter, scoreCache);
	}

	private static void logTransientBundleAlternativeScores(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> nextDecisions,
		DecisionMapScoreBreakdown nextScore,
		int iter,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || nextDecisions == null)
			return;

		Map<Long, LinkedHashSet<Long>> parentGraph = buildConflictParentGraph(memoTable, conflictCheckMap);
		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTWRITE
				|| !FederatedPlannerTrace.shouldTrace(hopRef))
				continue;

			ConflictEntry entry = e.getValue();
			if (entry == null || !entry.canChooseLOUT || !entry.canChooseFOUT)
				continue;
			FederatedOutput chosen = nextDecisions.get(hopID);
			if (chosen == null)
				continue;
			FederatedOutput alternative = chosen == FederatedOutput.FOUT ? FederatedOutput.LOUT : FederatedOutput.FOUT;

			LinkedHashSet<Long> bundleHopIDs =
				collectTransientFamilyBundleHopIDs(memoTable, hopID, conflictCheckMap, parentGraph, 4);
			if (bundleHopIDs.size() <= 1)
				continue;
			LinkedHashSet<Long> familyHopIDs =
				collectTransientFamilyDecisionHopIDs(memoTable, hopID, conflictCheckMap, parentGraph);

			Map<Long, FederatedOutput> altDecisions = new HashMap<>(nextDecisions);
			int changed = 0;
			int skipped = 0;
			for (long bundleHopID : bundleHopIDs) {
				ConflictEntry bundleEntry = conflictCheckMap.get(bundleHopID);
				if (bundleEntry == null) {
					skipped++;
					continue;
				}
				boolean canChooseAlternative = alternative == FederatedOutput.FOUT
					? bundleEntry.canChooseFOUT
					: bundleEntry.canChooseLOUT;
				if (!canChooseAlternative) {
					skipped++;
					continue;
				}
				FederatedOutput old = altDecisions.put(bundleHopID, alternative);
				if (old != alternative)
					changed++;
			}
			DecisionMapScoreBreakdown altScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions, scoreCache);
			boolean logAllRoots = Math.abs(altScore.totalCost - nextScore.totalCost) > 1e-9;
			FederatedPlannerTrace.log(hopRef, "DP-DecisionMap-BundleScore", String.format(Locale.ROOT,
				"iter=%d chosen=%s alt=%s bundleSize=%d changed=%d skipped=%d chosenTotal=%.6f altTotal=%.6f "
					+ "chosenAdditional=%.6f altAdditional=%.6f chosenVirtual=%.6f altVirtual=%.6f bundle=%s",
				iter,
				chosen,
				alternative,
				bundleHopIDs.size(),
				changed,
				skipped,
				nextScore.totalCost,
				altScore.totalCost,
				nextScore.additionalRootCost,
				altScore.additionalRootCost,
				nextScore.virtualAdditionalRootCost,
				altScore.virtualAdditionalRootCost,
				bundleHopIDs));
			logDecisionMapRootDifferences(hopRef, "DP-DecisionMap-BundleRoot", iter, alternative, nextScore, altScore,
				logAllRoots);

			if (ENABLE_TRANSIENT_FAMILY_SCORING_TRACE && familyHopIDs.size() > 1) {
				Map<Long, FederatedOutput> familyAltDecisions = new HashMap<>(nextDecisions);
				int familyChanged = 0;
				int familySkipped = 0;
				for (long familyHopID : familyHopIDs) {
					ConflictEntry familyEntry = conflictCheckMap.get(familyHopID);
					if (familyEntry == null) {
						familySkipped++;
						continue;
					}
					boolean canChooseAlternative = alternative == FederatedOutput.FOUT
						? familyEntry.canChooseFOUT
						: familyEntry.canChooseLOUT;
					if (!canChooseAlternative) {
						familySkipped++;
						continue;
					}
					FederatedOutput old = familyAltDecisions.put(familyHopID, alternative);
					if (old != alternative)
						familyChanged++;
				}
				DecisionMapScoreBreakdown familyAltScore =
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, familyAltDecisions, scoreCache);
				boolean logFamilyRoots = Math.abs(familyAltScore.totalCost - nextScore.totalCost) > 1e-9;
				FederatedPlannerTrace.log(hopRef, "DP-DecisionMap-FamilyScore", String.format(Locale.ROOT,
					"iter=%d chosen=%s alt=%s familySize=%d changed=%d skipped=%d chosenTotal=%.6f altTotal=%.6f "
						+ "chosenAdditional=%.6f altAdditional=%.6f chosenVirtual=%.6f altVirtual=%.6f family=%s",
					iter,
					chosen,
					alternative,
					familyHopIDs.size(),
					familyChanged,
					familySkipped,
					nextScore.totalCost,
					familyAltScore.totalCost,
					nextScore.additionalRootCost,
					familyAltScore.additionalRootCost,
					nextScore.virtualAdditionalRootCost,
					familyAltScore.virtualAdditionalRootCost,
					familyHopIDs));
				logDecisionMapRootDifferences(hopRef, "DP-DecisionMap-FamilyRoot", iter, alternative, nextScore,
					familyAltScore, logFamilyRoots);
			}
		}
	}

	private static void logDecisionMapRootDifferences(Hop hopRef, String traceTag, int iter,
		FederatedOutput alternative, DecisionMapScoreBreakdown chosenScore, DecisionMapScoreBreakdown altScore,
		boolean logAllRoots) {

		if (hopRef == null || chosenScore == null || altScore == null)
			return;
		if (!logAllRoots)
			return;

		Map<Long, RootContribution> chosenByRootHopID = indexRootContributionsByRootHop(chosenScore);
		Map<Long, RootContribution> altByRootHopID = indexRootContributionsByRootHop(altScore);
		LinkedHashSet<Long> rootHopIDs = new LinkedHashSet<>();
		rootHopIDs.addAll(chosenByRootHopID.keySet());
		rootHopIDs.addAll(altByRootHopID.keySet());
		for (long rootHopID : rootHopIDs) {
			RootContribution chosenContribution = chosenByRootHopID.get(rootHopID);
			RootContribution altContribution = altByRootHopID.get(rootHopID);
			if (!hasMeaningfulRootDifference(chosenContribution, altContribution))
				continue;
			FederatedPlannerTrace.log(hopRef, traceTag, String.format(Locale.ROOT,
				"iter=%d alt=%s rootHop=%d rootOrig=%d additional=%s virtual=%s "
					+ "chosenExec=%s chosenOut=%s chosenCost=%.6f chosenMult=%.6f chosenLoop=%s "
					+ "altExec=%s altOut=%s altCost=%.6f altMult=%.6f altLoop=%s",
				iter,
				alternative,
				rootHopID,
				resolveRootOrigHopID(chosenContribution, altContribution),
				resolveAdditionalRoot(chosenContribution, altContribution),
				resolveVirtualClone(chosenContribution, altContribution),
				formatRootExecType(chosenContribution),
				formatRootFedOut(chosenContribution),
				formatRootCost(chosenContribution),
				formatRootMultiplicity(chosenContribution),
				formatRootLoop(chosenContribution),
				formatRootExecType(altContribution),
				formatRootFedOut(altContribution),
				formatRootCost(altContribution),
				formatRootMultiplicity(altContribution),
				formatRootLoop(altContribution)));
		}
	}

	private static Map<Long, RootContribution> indexRootContributionsByRootHop(
		DecisionMapScoreBreakdown breakdown) {

		LinkedHashMap<Long, RootContribution> byRootHop = new LinkedHashMap<>();
		if (breakdown == null)
			return byRootHop;
		for (RootContribution contribution : breakdown.rootContributions.values()) {
			if (contribution == null)
				continue;
			byRootHop.put(contribution.rootHopID, contribution);
		}
		return byRootHop;
	}

	private static boolean hasMeaningfulRootDifference(RootContribution chosenContribution,
		RootContribution altContribution) {

		if (chosenContribution == null || altContribution == null)
			return true;
		if (chosenContribution.additionalRoot != altContribution.additionalRoot)
			return true;
		if (chosenContribution.virtualClone != altContribution.virtualClone)
			return true;
		if (chosenContribution.execType != altContribution.execType)
			return true;
		if (chosenContribution.fedOut != altContribution.fedOut)
			return true;
		if (Math.abs(chosenContribution.cost - altContribution.cost) > 1e-9)
			return true;
		if (Math.abs(chosenContribution.multiplicity - altContribution.multiplicity) > 1e-9)
			return true;
		return !Objects.equals(chosenContribution.loopContext, altContribution.loopContext);
	}

	private static long resolveRootOrigHopID(RootContribution chosenContribution, RootContribution altContribution) {
		if (chosenContribution != null)
			return chosenContribution.rootOrigHopID;
		return altContribution != null ? altContribution.rootOrigHopID : -1L;
	}

	private static boolean resolveAdditionalRoot(RootContribution chosenContribution,
		RootContribution altContribution) {
		if (chosenContribution != null)
			return chosenContribution.additionalRoot;
		return altContribution != null && altContribution.additionalRoot;
	}

	private static boolean resolveVirtualClone(RootContribution chosenContribution,
		RootContribution altContribution) {
		if (chosenContribution != null)
			return chosenContribution.virtualClone;
		return altContribution != null && altContribution.virtualClone;
	}

	private static String formatRootExecType(RootContribution contribution) {
		return contribution != null && contribution.execType != null ? contribution.execType.toString() : "null";
	}

	private static String formatRootFedOut(RootContribution contribution) {
		return contribution != null && contribution.fedOut != null ? contribution.fedOut.toString() : "null";
	}

	private static double formatRootCost(RootContribution contribution) {
		return contribution != null ? contribution.cost : Double.NaN;
	}

	private static double formatRootMultiplicity(RootContribution contribution) {
		return contribution != null ? contribution.multiplicity : 0.0;
	}

	private static String formatRootLoop(RootContribution contribution) {
		return contribution != null ? contribution.loopContext : "[]";
	}

	private static Map<Long, LinkedHashSet<Long>> buildConflictParentGraph(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, ConflictEntry> conflictCheckMap) {

		LinkedHashMap<Long, LinkedHashSet<Long>> parentGraph = new LinkedHashMap<>();
		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty())
			return parentGraph;
		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long childHopID = e.getKey();
			ConflictEntry entry = e.getValue();
			if (entry == null || entry.parents == null || entry.parents.isEmpty())
				continue;
			LinkedHashSet<Long> parents = parentGraph.computeIfAbsent(childHopID, k -> new LinkedHashSet<>());
			for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
				if (parentPlan == null || parentPlan.getHopRef() == null)
					continue;
				parents.add(memoTable.resolveOriginalHopId(parentPlan.getHopRef().getHopID()));
			}
		}
		return parentGraph;
	}

	private static LinkedHashSet<Long> collectTransientFamilyBundleHopIDs(
		FederatedPlannerDpMemoTable memoTable,
		long tWriteHopID,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, LinkedHashSet<Long>> parentGraph,
		int maxDepth) {

		LinkedHashSet<Long> bundleHopIDs = new LinkedHashSet<>();
		if (memoTable == null || conflictCheckMap == null)
			return bundleHopIDs;

		ArrayDeque<Pair<Long, Integer>> queue = new ArrayDeque<>();
		bundleHopIDs.add(tWriteHopID);
		queue.add(Pair.of(tWriteHopID, 0));

		for (long tReadHopID : collectTransientReadParents(memoTable, tWriteHopID, conflictCheckMap)) {
			if (bundleHopIDs.add(tReadHopID))
				queue.add(Pair.of(tReadHopID, 0));
		}

		while (!queue.isEmpty()) {
			Pair<Long, Integer> item = queue.poll();
			long currentHopID = item.getLeft();
			int depth = item.getRight();
			if (depth >= maxDepth)
				continue;
			for (long parentHopID : parentGraph.getOrDefault(currentHopID, new LinkedHashSet<>())) {
				if (bundleHopIDs.add(parentHopID))
					queue.add(Pair.of(parentHopID, depth + 1));
			}
		}

		return bundleHopIDs;
	}

	private static LinkedHashSet<Long> collectTransientFamilyDecisionHopIDs(
		FederatedPlannerDpMemoTable memoTable,
		long tWriteHopID,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, LinkedHashSet<Long>> parentGraph) {

		LinkedHashSet<Long> familyHopIDs = new LinkedHashSet<>();
		if (memoTable == null || conflictCheckMap == null)
			return familyHopIDs;

		familyHopIDs.add(tWriteHopID);
		LinkedHashSet<Long> tReadHopIDs = collectTransientReadParents(memoTable, tWriteHopID, conflictCheckMap);
		familyHopIDs.addAll(tReadHopIDs);
		return familyHopIDs;
	}

	private static LinkedHashSet<Long> expandTransientFamilyDecisionHopIDsForOutput(
		FederatedPlannerDpMemoTable memoTable,
		long tWriteHopID,
		LinkedHashSet<Long> familyHopIDs,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> tentativeDecisions,
		FederatedOutput targetOut) {

		LinkedHashSet<Long> expandedFamilyHopIDs = new LinkedHashSet<>();
		if (familyHopIDs != null)
			expandedFamilyHopIDs.addAll(familyHopIDs);
		if (memoTable == null || conflictCheckMap == null || tentativeDecisions == null || targetOut == null)
			return expandedFamilyHopIDs;

		LinkedHashSet<String> visitedStates = new LinkedHashSet<>();
		collectRequiredTransientDecisionClosure(memoTable, tWriteHopID, targetOut, conflictCheckMap,
			tentativeDecisions, targetOut, expandedFamilyHopIDs, visitedStates);
		return expandedFamilyHopIDs;
	}

	private static void collectRequiredTransientDecisionClosure(
		FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> tentativeDecisions,
		FederatedOutput targetOut,
		LinkedHashSet<Long> familyHopIDs,
		Set<String> visitedStates) {

		if (memoTable == null || conflictCheckMap == null || tentativeDecisions == null
			|| familyHopIDs == null || visitedStates == null || desiredOut == null || targetOut == null) {
			return;
		}

		String stateKey = concreteHopID + "|" + desiredOut;
		if (!visitedStates.add(stateKey))
			return;

		FederatedPlannerDpMemoTable.FedPlan plan =
			selectCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, tentativeDecisions);
		if (plan == null)
			plan = memoTable.getFedPlanAfterPrune(concreteHopID, desiredOut);
		if (plan == null || plan.getChildFedPlans() == null || plan.getChildFedPlans().isEmpty())
			return;

		for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() != targetOut)
				continue;
			long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
			ConflictEntry childEntry = conflictCheckMap.get(childOrigHopID);
			if (childEntry == null)
				continue;
			boolean canChooseTarget = targetOut == FederatedOutput.FOUT
				? childEntry.canChooseFOUT
				: childEntry.canChooseLOUT;
			if (!canChooseTarget)
				continue;
			familyHopIDs.add(childOrigHopID);
			tentativeDecisions.put(childOrigHopID, targetOut);
			collectRequiredTransientDecisionClosure(memoTable, childEdge.getKey(), targetOut, conflictCheckMap,
				tentativeDecisions, targetOut, familyHopIDs, visitedStates);
		}
	}

	private static LinkedHashSet<Long> collectContextuallyFeasibleTransientBundleHopIDs(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> baseDecisions,
		Map<Long, ConflictEntry> conflictCheckMap,
		LinkedHashSet<Long> familyHopIDs,
		LinkedHashSet<Long> bundleHopIDs) {

		LinkedHashSet<Long> feasibleHopIDs = new LinkedHashSet<>();
		if (familyHopIDs != null)
			feasibleHopIDs.addAll(familyHopIDs);
		if (memoTable == null || rootPlan == null || bundleHopIDs == null || bundleHopIDs.isEmpty())
			return feasibleHopIDs;

		Map<Long, FederatedOutput> tentativeDecisions = new HashMap<>();
		if (baseDecisions != null)
			tentativeDecisions.putAll(baseDecisions);
		for (long bundleHopID : bundleHopIDs) {
			ConflictEntry bundleEntry = conflictCheckMap != null ? conflictCheckMap.get(bundleHopID) : null;
			if (bundleEntry != null && bundleEntry.canChooseFOUT)
				tentativeDecisions.put(bundleHopID, FederatedOutput.FOUT);
		}

		Map<Long, FType> contextualFTypeMap =
			buildContextuallyFeasibleDecisionFTypeMap(memoTable, rootPlan, tentativeDecisions);
		for (long bundleHopID : bundleHopIDs) {
			if (familyHopIDs != null && familyHopIDs.contains(bundleHopID))
				continue;
			if (contextualFTypeMap.containsKey(bundleHopID))
				feasibleHopIDs.add(bundleHopID);
		}

		return feasibleHopIDs;
	}

	private static Map<Long, FType> buildContextuallyFeasibleDecisionFTypeMap(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		LinkedHashMap<Long, FType> contextualFTypeMap = new LinkedHashMap<>();
		if (memoTable == null || rootPlan == null)
			return contextualFTypeMap;

		LinkedHashMap<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap =
			buildDecisionMapSelectedPlanMap(memoTable, rootPlan, outputDecisions);
		if (selectedPlanMap.isEmpty())
			return contextualFTypeMap;

		LinkedHashMap<Long, HopStampState> stampStateMap = snapshotDecisionMapHopState(selectedPlanMap);
		try {
			boolean changed;
			do {
				applyContextualDecisionStamps(selectedPlanMap, contextualFTypeMap);
				changed = false;
				for (Map.Entry<Long, FederatedPlannerDpMemoTable.FedPlan> e : selectedPlanMap.entrySet()) {
					long origHopID = e.getKey();
					if (contextualFTypeMap.containsKey(origHopID))
						continue;
					FederatedPlannerDpMemoTable.FedPlan selectedPlan = e.getValue();
					if (selectedPlan == null || selectedPlan.getHopRef() == null) {
						continue;
					}

					Hop hopRef = selectedPlan.getHopRef();
					FType contextualType = resolveContextualPlanningFType(selectedPlan, hopRef);
					if (contextualType == null || contextualType == FType.BROADCAST)
						continue;
					if (hopRef instanceof DataOp) {
						DataOp dataOp = (DataOp) hopRef;
						if (selectedPlan.getExecType() == ExecType.FED
							&& selectedPlan.getFedOutType() == FederatedOutput.FOUT
							&& dataOp.getOp() == Types.OpOpData.TRANSIENTREAD) {
							List<Hop> sourceHops = collectSelectedSourceHops(memoTable, selectedPlan);
							boolean hasConcreteSource =
								FederatedPlannerDpCostEstimator.isStableFedInitTransientRead(selectedPlan)
									|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(dataOp, sourceHops)
									|| hasContextuallyConcreteTransientWriteSource(
										memoTable, selectedPlanMap, contextualFTypeMap, sourceHops);
							if (!hasConcreteSource)
								continue;
						}
					}

					boolean selectedFedFout =
						selectedPlan.getExecType() == ExecType.FED
							&& selectedPlan.getFedOutType() == FederatedOutput.FOUT;
					if (selectedFedFout) {
						if (!hasExactSelectedChildReceipt(memoTable, selectedPlanMap, selectedPlan))
							continue;
						contextualFTypeMap.put(origHopID, contextualType);
						changed = true;
					}
					else {
						// CP/LOUT (or FED/LOUT) selected plans can still provide a downstream-safe
						// upload/refed layout through cpFoutType. Do not require the producing hop
						// itself to already satisfy FED-input feasibility here: the consumer-side
						// feasibility check will validate anchor/materialization legality when this
						// local result is considered as an upload candidate.
						contextualFTypeMap.put(origHopID, contextualType);
						changed = true;
					}
				}
			}
			while (changed);
			return contextualFTypeMap;
		}
		finally {
			restoreDecisionMapHopState(stampStateMap);
		}
	}

	private static boolean hasContextuallyConcreteTransientWriteSource(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap,
		Map<Long, FType> contextualFTypeMap,
		List<Hop> sourceHops) {

		if (memoTable == null || selectedPlanMap == null || contextualFTypeMap == null
			|| sourceHops == null || sourceHops.isEmpty()) {
			return false;
		}
		for (Hop sourceHop : sourceHops) {
			if (!(sourceHop instanceof DataOp)
				|| ((DataOp) sourceHop).getOp() != Types.OpOpData.TRANSIENTWRITE) {
				continue;
			}
			long sourceOrigHopID = memoTable.resolveOriginalHopId(sourceHop.getHopID());
			FederatedPlannerDpMemoTable.FedPlan sourcePlan = selectedPlanMap.get(sourceOrigHopID);
			if (sourcePlan == null || sourcePlan.getExecType() != ExecType.FED
				|| sourcePlan.getFedOutType() != FederatedOutput.FOUT
				|| !contextualFTypeMap.containsKey(sourceOrigHopID)
				|| sourcePlan.getChildFedPlans() == null) {
				continue;
			}
			for (Pair<Long, FederatedOutput> childEdge : sourcePlan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() != FederatedOutput.FOUT)
					continue;
				long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
				FederatedPlannerDpMemoTable.FedPlan childPlan = selectedPlanMap.get(childOrigHopID);
				if (childPlan != null && childPlan.getFedOutType() == FederatedOutput.FOUT
					&& contextualFTypeMap.containsKey(childOrigHopID)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasExactSelectedChildReceipt(FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap,
		FederatedPlannerDpMemoTable.FedPlan selectedPlan) {
		if (memoTable == null || selectedPlanMap == null || selectedPlan == null
			|| selectedPlan.getChildFedPlans() == null)
			return false;
		for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() == null)
				return false;
			FederatedPlannerDpMemoTable.FedPlan child =
				selectedPlanMap.get(memoTable.resolveOriginalHopId(childEdge.getKey()));
			if (child == null || child.getFedOutType() != childEdge.getValue())
				return false;
		}
		return true;
	}

	private static List<Hop> collectSelectedSourceHops(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan selectedPlan) {

		ArrayDeque<Hop> sourceHops = new ArrayDeque<>();
		if (memoTable == null || selectedPlan == null || selectedPlan.getChildFedPlans() == null)
			return List.of();
		for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
			if (childEdge == null)
				continue;
			Hop sourceHop = memoTable.resolveOriginalHop(childEdge.getKey());
			if (sourceHop != null)
				sourceHops.add(sourceHop);
		}
		return List.copyOf(sourceHops);
	}

	private static LinkedHashMap<Long, FederatedPlannerDpMemoTable.FedPlan> buildDecisionMapSelectedPlanMap(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		LinkedHashMap<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap = new LinkedHashMap<>();
		if (memoTable == null || rootPlan == null)
			return selectedPlanMap;

		HashSet<Long> visited = new HashSet<>();
		for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
			collectDecisionMapSelectedPlan(
				memoTable, rootChild.getKey(), rootChild.getValue(), outputDecisions, selectedPlanMap, visited);
		}
		for (long rootHopID : memoTable.getAdditionalRootHopIDs()) {
			collectDecisionMapSelectedPlan(
				memoTable, rootHopID, null, outputDecisions, selectedPlanMap, visited);
		}
		return selectedPlanMap;
	}

	private static void collectDecisionMapSelectedPlan(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedOutput defaultOut,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap,
		Set<Long> visited) {

		if (memoTable == null || selectedPlanMap == null || visited == null || !visited.add(hopID))
			return;
		FederatedPlannerDpMemoTable.FedPlan selectedPlan =
			selectDecisionMapRootSeedPlan(memoTable, hopID, defaultOut, outputDecisions);
		if (selectedPlan == null || selectedPlan.getHopRef() == null)
			return;
		long origHopID = memoTable.resolveOriginalHopId(hopID);
		selectedPlanMap.putIfAbsent(origHopID, selectedPlan);
		if (selectedPlan.getChildFedPlans() == null)
			return;
		for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
			if (childEdge == null)
				continue;
			collectDecisionMapSelectedPlan(
				memoTable, childEdge.getKey(), childEdge.getValue(), outputDecisions, selectedPlanMap, visited);
		}
	}

	private static LinkedHashMap<Long, HopStampState> snapshotDecisionMapHopState(
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap) {

		LinkedHashMap<Long, HopStampState> stampStateMap = new LinkedHashMap<>();
		if (selectedPlanMap == null || selectedPlanMap.isEmpty())
			return stampStateMap;
		for (Map.Entry<Long, FederatedPlannerDpMemoTable.FedPlan> e : selectedPlanMap.entrySet()) {
			FederatedPlannerDpMemoTable.FedPlan selectedPlan = e.getValue();
				if (selectedPlan == null || selectedPlan.getHopRef() == null)
					continue;
				Hop hopRef = selectedPlan.getHopRef();
				stampStateMap.put(e.getKey(), new HopStampState(
					hopRef, hopRef.getExecType(), hopRef.getForcedExecType(),
					hopRef.getFederatedOutput(), hopRef.isFederatedOutputDerived()));
			}
			return stampStateMap;
		}

	private static void applyContextualDecisionStamps(
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedPlanMap,
		Map<Long, FType> contextualFTypeMap) {

		if (selectedPlanMap == null || selectedPlanMap.isEmpty())
			return;
		for (Map.Entry<Long, FederatedPlannerDpMemoTable.FedPlan> e : selectedPlanMap.entrySet()) {
			long origHopID = e.getKey();
			FederatedPlannerDpMemoTable.FedPlan selectedPlan = e.getValue();
			if (selectedPlan == null || selectedPlan.getHopRef() == null)
				continue;
			Hop hopRef = selectedPlan.getHopRef();
				if (contextualFTypeMap != null && contextualFTypeMap.containsKey(origHopID)
					&& selectedPlan.getExecType() == ExecType.FED
					&& selectedPlan.getFedOutType() == FederatedOutput.FOUT) {
					hopRef.setExecType(ExecType.FED);
					hopRef.setForcedExecType(ExecType.FED);
					hopRef.setFederatedOutput(FederatedOutput.FOUT);
				}
				else if (selectedPlan.getExecType() == ExecType.FED
					&& selectedPlan.getFedOutType() == FederatedOutput.FOUT) {
					hopRef.setExecType(ExecType.CP);
					hopRef.setForcedExecType(ExecType.CP);
					hopRef.setFederatedOutput(FederatedOutput.LOUT);
				}
				else {
					hopRef.setExecType(selectedPlan.getExecType());
					hopRef.setForcedExecType(selectedPlan.getExecType());
					hopRef.setFederatedOutput(selectedPlan.getFedOutType());
				}
		}
	}

	private static void restoreDecisionMapHopState(Map<Long, HopStampState> stampStateMap) {
		if (stampStateMap == null || stampStateMap.isEmpty())
			return;
			for (HopStampState state : stampStateMap.values()) {
				if (state == null || state.hop == null)
					continue;
				state.hop.setExecType(state.execType);
				if (state.forcedExecType != null)
					state.hop.setForcedExecType(state.forcedExecType);
				else
					state.hop.clearForcedExecType();
			state.hop.setFederatedOutput(state.fedOut);
			state.hop.setFederatedOutputDerived(state.fedOutDerived);
		}
	}

	private static boolean familyHasCheaperRawAlternative(
		FederatedPlannerDpMemoTable memoTable,
		LinkedHashSet<Long> familyHopIDs,
		FederatedOutput alternative) {

		if (memoTable == null || familyHopIDs == null || familyHopIDs.isEmpty())
			return false;
		FederatedOutput other = alternative == FederatedOutput.FOUT ? FederatedOutput.LOUT : FederatedOutput.FOUT;
		for (long familyHopID : familyHopIDs) {
			FederatedPlannerDpMemoTable.FedPlan altPlan =
				memoTable.getFedPlanAfterPrune(familyHopID, alternative);
			FederatedPlannerDpMemoTable.FedPlan otherPlan =
				memoTable.getFedPlanAfterPrune(familyHopID, other);
			if (altPlan == null || otherPlan == null)
				continue;
			if (altPlan.getCumulativeCost() + 1e-9 < otherPlan.getCumulativeCost())
				return true;
		}
		return false;
	}

	private static void refreshConflictChoiceFeasibility(
		Map<Long, ConflictEntry> conflictCheckMap, FederatedPlannerDpMemoTable memoTable) {

		if (conflictCheckMap == null || conflictCheckMap.isEmpty() || memoTable == null)
			return;
		for (ConflictEntry entry : conflictCheckMap.values()) {
			boolean canChooseLOUT = true;
			boolean canChooseFOUT = true;
			Set<Long> decisionMembers = selectDecisionMembers(entry.memberHopIDs, memoTable);
			for (long memberHopID : decisionMembers) {
				canChooseLOUT &= (memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT) != null);
				canChooseFOUT &= (memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.FOUT) != null);
			}
			entry.canChooseLOUT = canChooseLOUT;
			entry.canChooseFOUT = canChooseFOUT;
		}
	}

	/**
	 * Return all concrete members that contributed to a logical-hop output decision.
	 *
	 * <p>Loop-unrolled virtual clones carry the multiplicity-weighted cost of the
	 * repeated iterations. Dropping them here makes the decision depend only on the
	 * executable original hop and reintroduces a heuristic original-over-clone bias.
	 * Keep every observed member so LOUT/FOUT feasibility and delta comparisons are
	 * based on the aggregate cost of the full original+clone family.</p>
	 */
	private static Set<Long> selectDecisionMembers(Set<Long> memberHopIDs,
		FederatedPlannerDpMemoTable memoTable) {

		return memberHopIDs;
	}

	/**
	 * Collect all TRANSIENTREAD hops that read from the given TRANSIENTWRITE hop.
	 *
	 * <p>Note that DataOp.TRANSIENTWRITE does not reliably maintain parent links in the Hop DAG,
	 * and DP memo entries can be cloned across loop contexts. Therefore, we identify TR parents
	 * by scanning all traced conflict entries and checking if any TR plan variant has a child
	 * edge pointing to the given TW (by original hop id).</p>
	 */
	private static LinkedHashSet<Long> collectTransientReadParents(
		FederatedPlannerDpMemoTable memoTable, long tWriteOrigHopID,
		Map<Long, ConflictEntry> conflictCheckMap) {

		return collectTransientReadParents(memoTable, tWriteOrigHopID, conflictCheckMap, null);
	}

	private static LinkedHashSet<Long> collectTransientReadParents(
		FederatedPlannerDpMemoTable memoTable, long tWriteOrigHopID,
		Map<Long, ConflictEntry> conflictCheckMap,
		TransientReadParentsCache transientReadParentsCache) {

		LinkedHashSet<Long> tReadHopIDs = new LinkedHashSet<>();
		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty())
			return tReadHopIDs;
		TransientReadParentsKey cacheKey = null;
		if (transientReadParentsCache != null) {
			cacheKey = new TransientReadParentsKey(conflictCheckMap, tWriteOrigHopID);
			LinkedHashSet<Long> cached = transientReadParentsCache.get(cacheKey);
			if (cached != null)
				return cached;
		}

		for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();
			if (entry == null || entry.memberHopIDs == null || entry.memberHopIDs.isEmpty())
				continue;

			Hop hopRef = memoTable.resolveOriginalHop(hopID);
			if (!(hopRef instanceof DataOp) || ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;

			boolean readsFromThisWrite = false;
			LinkedHashSet<Long> referencedTWriteOrigHopIDs = new LinkedHashSet<>();
			for (long memberHopID : selectDecisionMembers(entry.memberHopIDs, memoTable)) {
				// Only propagate transient-write decisions through transient-read variants that
				// have a concrete local competitor in the memo. FOUT-only transient reads tend to
				// form a self-reinforcing cycle where the current FED/FOUT bias of the read path
				// forces the write to stay FED/FOUT as well, even though a legal CP/LOUT recompile
				// exists once the write is materialized locally.
				if (!entry.canChooseLOUT)
					continue;
				FederatedPlannerDpMemoTable.FedPlan plan =
					memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT);
				if (plan == null)
					continue;

				for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
					long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
					Hop childRef = memoTable.resolveOriginalHop(childEdge.getKey());
					if (childRef instanceof DataOp
						&& ((DataOp) childRef).getOp() == Types.OpOpData.TRANSIENTWRITE) {
						referencedTWriteOrigHopIDs.add(childOrigHopID);
					}
					if (childOrigHopID == tWriteOrigHopID) {
						readsFromThisWrite = true;
					}
				}
			}

			// A merged TRead conflict entry can contain executable clone members that read from
			// different transient writes of the same variable family (observed in logreg w2: P
			// members mapped to distinct loop-stage TWrites 271 vs 775). Propagating a single
			// TWrite decision onto the entire merged TRead entry makes the later member overwrite
			// the earlier one and can leave rewrite/state propagation inconsistent.
			if (readsFromThisWrite && referencedTWriteOrigHopIDs.size() == 1)
				tReadHopIDs.add(hopID);
		}

			if (cacheKey != null)
				transientReadParentsCache.put(cacheKey, tReadHopIDs);
			return tReadHopIDs;
		}

	private static FederatedOutput resolveTransientWriteConflict(
		FederatedPlannerDpMemoTable memoTable, long tWriteHopID, ConflictEntry tWriteEntry,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> tentativeDecisions,
		int numWorkers) {

		return resolveTransientWriteConflict(
			memoTable, tWriteHopID, tWriteEntry, conflictCheckMap, tentativeDecisions, numWorkers, null);
	}

	private static FederatedOutput resolveTransientWriteConflict(
		FederatedPlannerDpMemoTable memoTable, long tWriteHopID, ConflictEntry tWriteEntry,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> tentativeDecisions,
		int numWorkers,
		ParentVariantDeltaCache parentVariantDeltaCache) {

		return resolveTransientWriteConflict(
			memoTable, tWriteHopID, tWriteEntry, conflictCheckMap, tentativeDecisions, numWorkers,
			parentVariantDeltaCache, null);
	}

	private static FederatedOutput resolveTransientWriteConflict(
		FederatedPlannerDpMemoTable memoTable, long tWriteHopID, ConflictEntry tWriteEntry,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> tentativeDecisions,
		int numWorkers,
		ParentVariantDeltaCache parentVariantDeltaCache,
		TransientReadParentsCache transientReadParentsCache) {

		if (memoTable == null || tWriteEntry == null)
			return null;
		final Hop hopRef = memoTable.resolveOriginalHop(tWriteHopID);
		final boolean trace = FederatedPlannerTrace.shouldTrace(hopRef);

		final boolean canChooseLOUT = tWriteEntry.canChooseLOUT;
		final boolean canChooseFOUT = tWriteEntry.canChooseFOUT;
		if (!canChooseLOUT && !canChooseFOUT)
			return null;

		LinkedHashSet<Long> tReadHopIDs = collectTransientReadParents(
			memoTable, tWriteHopID, conflictCheckMap, transientReadParentsCache);
		if (tReadHopIDs.isEmpty()) {
			// Fallback to generic per-hop resolution.
			return resolveOneHopConflict(
				memoTable, tWriteHopID, tWriteEntry, tentativeDecisions, numWorkers, conflictCheckMap,
				parentVariantDeltaCache);
		}

		double lOutAdditionalCost = canChooseLOUT ? 0.0 : Double.POSITIVE_INFINITY;
		double fOutAdditionalCost = canChooseFOUT ? 0.0 : Double.POSITIVE_INFINITY;

		// Conflict semantics for transient variables:
		// A TRANSIENTWRITE's representation (LOUT/FOUT) must be consistent across all
		// TRANSIENTREAD parents. The true tradeoff is driven by the downstream
		// consumers of those reads (FED consumers pay upload/refed, CP consumers pay
		// download). Therefore, estimate costs by switching the placements of the
		// TRANSIENTREAD hops (and their consumer edges), not by charging a download on
		// the TW->TR edge (which would disappear if TR switches to FED/FOUT).
			for (long tReadHopID : tReadHopIDs) {
				ConflictEntry tReadEntry = conflictCheckMap.get(tReadHopID);
				if (tReadEntry == null || tReadEntry.parents == null || tReadEntry.parents.isEmpty())
					continue;

			// If any TRead clone lacks a variant, restrict feasible choice.
			boolean tReadCanLOUT = tReadEntry.canChooseLOUT;
			boolean tReadCanFOUT = tReadEntry.canChooseFOUT;
			if (!tReadCanLOUT)
				lOutAdditionalCost = Double.POSITIVE_INFINITY;
			if (!tReadCanFOUT)
				fOutAdditionalCost = Double.POSITIVE_INFINITY;
				if (!Double.isFinite(lOutAdditionalCost) && !Double.isFinite(fOutAdditionalCost))
					return null;

				Set<String> seenCostEdges = new LinkedHashSet<>();
				for (FederatedPlannerDpMemoTable.FedPlan consumerPlan : tReadEntry.parents) {
				if (consumerPlan == null)
					continue;
				boolean consumerIsFed = consumerPlan.getExecType() == ExecType.FED;
				for (Pair<Long, FederatedOutput> edge : consumerPlan.getChildFedPlans()) {
					if (memoTable.resolveOriginalHopId(edge.getKey()) != tReadHopID)
						continue;
					long childHopID = edge.getKey();
					String edgeKey = buildConflictCostEdgeKey(
						memoTable, consumerPlan, childHopID, edge.getValue());
					if (!seenCostEdges.add(edgeKey))
						continue;
					FederatedOutput originalOut = edge.getValue();

						if (tReadCanLOUT && canChooseLOUT && originalOut != FederatedOutput.LOUT) {
							lOutAdditionalCost += computeSwitchEdgeCostDelta(
								memoTable, childHopID, originalOut, FederatedOutput.LOUT,
								consumerPlan, consumerIsFed, numWorkers, conflictCheckMap,
								parentVariantDeltaCache);
						}
						if (tReadCanFOUT && canChooseFOUT && originalOut != FederatedOutput.FOUT) {
							fOutAdditionalCost += computeSwitchEdgeCostDelta(
								memoTable, childHopID, originalOut, FederatedOutput.FOUT,
								consumerPlan, consumerIsFed, numWorkers, conflictCheckMap,
								parentVariantDeltaCache);
						}
				}
			}
			}

		FederatedOutput currentOut = tWriteEntry.seenFOUT && !tWriteEntry.seenLOUT ? FederatedOutput.FOUT :
			(tWriteEntry.seenLOUT && !tWriteEntry.seenFOUT ? FederatedOutput.LOUT : null);
		if (currentOut != null) {
			double producerDeltaToLOUT = 0.0;
			double producerDeltaToFOUT = 0.0;
				if (canChooseLOUT && currentOut != FederatedOutput.LOUT) {
					producerDeltaToLOUT = computeTransientWriteProducerDelta(
						memoTable, tWriteEntry, currentOut, FederatedOutput.LOUT, numWorkers, conflictCheckMap,
						parentVariantDeltaCache);
					lOutAdditionalCost += producerDeltaToLOUT;
				}
				if (canChooseFOUT && currentOut != FederatedOutput.FOUT) {
					producerDeltaToFOUT = computeTransientWriteProducerDelta(
						memoTable, tWriteEntry, currentOut, FederatedOutput.FOUT, numWorkers, conflictCheckMap,
						parentVariantDeltaCache);
					fOutAdditionalCost += producerDeltaToFOUT;
				}
			if (trace) {
				FederatedPlannerTrace.log(hopRef, "DP-TransientInputDelta", String.format(Locale.ROOT,
					"current=%s deltaToLOUT=%.6f deltaToFOUT=%.6f",
					currentOut, producerDeltaToLOUT, producerDeltaToFOUT));
			}
		}

		FederatedOutput chosen;
		if (!canChooseFOUT || !Double.isFinite(fOutAdditionalCost)
			|| (canChooseLOUT && Double.isFinite(lOutAdditionalCost) && lOutAdditionalCost <= fOutAdditionalCost))
			chosen = FederatedOutput.LOUT;
		else
			chosen = FederatedOutput.FOUT;
		if (trace) {
			FederatedPlannerTrace.log(hopRef, "DP-TransientDecision", String.format(Locale.ROOT,
				"tReads=%d lOutAdditional=%.6f fOutAdditional=%.6f chosen=%s",
				tReadHopIDs.size(), lOutAdditionalCost, fOutAdditionalCost, chosen));
		}
			return chosen;
		}

		private static double computeTransientWriteProducerDelta(
			FederatedPlannerDpMemoTable memoTable, ConflictEntry tWriteEntry,
		FederatedOutput fromOut, FederatedOutput toOut, int numWorkers) {

		return computeTransientWriteProducerDelta(
			memoTable, tWriteEntry, fromOut, toOut, numWorkers, null);
	}

	private static double computeTransientWriteProducerDelta(
		FederatedPlannerDpMemoTable memoTable, ConflictEntry tWriteEntry,
		FederatedOutput fromOut, FederatedOutput toOut, int numWorkers,
		Map<Long, ConflictEntry> conflictCheckMap) {

		return computeTransientWriteProducerDelta(
			memoTable, tWriteEntry, fromOut, toOut, numWorkers, conflictCheckMap, null);
	}

	private static double computeTransientWriteProducerDelta(
		FederatedPlannerDpMemoTable memoTable, ConflictEntry tWriteEntry,
		FederatedOutput fromOut, FederatedOutput toOut, int numWorkers,
		Map<Long, ConflictEntry> conflictCheckMap,
		ParentVariantDeltaCache parentVariantDeltaCache) {

		if (memoTable == null || tWriteEntry == null || fromOut == toOut)
			return 0.0;

		double delta = 0.0;
		Set<Long> decisionMembers = selectDecisionMembers(tWriteEntry.memberHopIDs, memoTable);
		for (long memberHopID : decisionMembers) {
			FederatedPlannerDpMemoTable.FedPlan fromPlan = memoTable.getFedPlanAfterPrune(memberHopID, fromOut);
			FederatedPlannerDpMemoTable.FedPlan toPlan = memoTable.getFedPlanAfterPrune(memberHopID, toOut);
			if (fromPlan == null || toPlan == null)
				return Double.POSITIVE_INFINITY;

			Map<Long, Pair<Long, FederatedOutput>> fromEdges = new LinkedHashMap<>();
			for (Pair<Long, FederatedOutput> edge : fromPlan.getChildFedPlans()) {
				if (edge == null)
					continue;
				fromEdges.put(memoTable.resolveOriginalHopId(edge.getKey()), Pair.of(edge.getKey(), edge.getValue()));
			}
			Map<Long, Pair<Long, FederatedOutput>> toEdges = new LinkedHashMap<>();
			for (Pair<Long, FederatedOutput> edge : toPlan.getChildFedPlans()) {
				if (edge == null)
					continue;
				toEdges.put(memoTable.resolveOriginalHopId(edge.getKey()), Pair.of(edge.getKey(), edge.getValue()));
			}

			LinkedHashSet<Long> producerOrigHopIDs = new LinkedHashSet<>();
			producerOrigHopIDs.addAll(fromEdges.keySet());
			producerOrigHopIDs.addAll(toEdges.keySet());
			for (long producerOrigHopID : producerOrigHopIDs) {
				Pair<Long, FederatedOutput> fromEdge = fromEdges.get(producerOrigHopID);
				Pair<Long, FederatedOutput> toEdge = toEdges.get(producerOrigHopID);
				if (fromEdge == null || toEdge == null || fromEdge.getValue() == toEdge.getValue())
					continue;
					delta += computeSwitchEdgeCostDelta(
						memoTable, fromEdge.getKey(), fromEdge.getValue(), toEdge.getValue(),
						fromPlan, fromPlan.getExecType() == ExecType.FED, numWorkers, conflictCheckMap,
						parentVariantDeltaCache);
				}
		}

		return delta;
	}

	private static boolean isTransientBoundaryNeighborhood(
		FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry) {

		Hop hopRef = memoTable != null ? memoTable.resolveOriginalHop(hopID) : null;
		if (hopRef == null)
			return false;
		if (isTransientBoundaryHop(hopRef))
			return true;

		if (entry != null && entry.parents != null) {
			for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
				if (parentPlan != null && isTransientBoundaryHop(parentPlan.getHopRef()))
					return true;
			}
		}

		if (memoTable == null || entry == null || entry.memberHopIDs == null)
			return false;
		for (long memberHopID : selectDecisionMembers(entry.memberHopIDs, memoTable)) {
			for (FederatedOutput out : new FederatedOutput[] {FederatedOutput.LOUT, FederatedOutput.FOUT}) {
				FederatedPlannerDpMemoTable.FedPlan plan = memoTable.getFedPlanAfterPrune(memberHopID, out);
				if (plan == null || plan.getChildFedPlans() == null)
					continue;
				for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
					if (childEdge == null)
						continue;
					Hop childHop = memoTable.resolveOriginalHop(childEdge.getKey());
					if (isTransientBoundaryHop(childHop))
						return true;
				}
			}
		}
		return false;
	}

	private static boolean requiresCompatibleVariantReevaluation(
		FederatedPlannerDpMemoTable memoTable, long hopID, FederatedOutput observedOut,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null || outputDecisions == null || outputDecisions.isEmpty())
			return false;
		FederatedPlannerDpMemoTable.FedPlan observedPlan = memoTable.getFedPlanAfterPrune(hopID, observedOut);
		FederatedPlannerDpMemoTable.FedPlan compatibleObservedPlan =
			findStrictCompatiblePlanVariant(memoTable, hopID, observedOut, outputDecisions);
		if (observedPlan == null)
			return false;
		if (compatibleObservedPlan == null) {
			FederatedOutput alternativeOut = observedOut == FederatedOutput.FOUT
				? FederatedOutput.LOUT
				: FederatedOutput.FOUT;
			return findStrictCompatiblePlanVariant(memoTable, hopID, alternativeOut, outputDecisions) != null;
		}
		if (compatibleObservedPlan != observedPlan) {
			if (compatibleObservedPlan.getExecType() != observedPlan.getExecType())
				return true;
			if (compatibleObservedPlan.getFedOutType() != observedPlan.getFedOutType())
				return true;
			if (compatibleObservedPlan.isDerivedFedFout() != observedPlan.isDerivedFedFout())
				return true;
			if (Math.abs(compatibleObservedPlan.getCumulativeCost() - observedPlan.getCumulativeCost()) > 1e-9)
				return true;
			if (compatibleObservedPlan.getChildFedPlans() != null
				&& !compatibleObservedPlan.getChildFedPlans().equals(observedPlan.getChildFedPlans()))
				return true;
		}

		FederatedOutput alternativeOut = observedOut == FederatedOutput.FOUT
			? FederatedOutput.LOUT
			: FederatedOutput.FOUT;
		FederatedPlannerDpMemoTable.FedPlan compatibleAlternativePlan =
			findStrictCompatiblePlanVariant(memoTable, hopID, alternativeOut, outputDecisions);
		if (compatibleAlternativePlan == null)
			return false;
		if (compatibleAlternativePlan.getCumulativeCost() + 1e-9 < compatibleObservedPlan.getCumulativeCost())
			return true;
		return false;
	}

	private static boolean hasCheaperAlternativeObservedChoice(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		ConflictEntry entry,
		FederatedOutput observedOut,
		Map<Long, FederatedOutput> tentativeDecisions) {

		if (memoTable == null || entry == null || observedOut == null)
			return false;
		FederatedOutput alternativeOut = observedOut == FederatedOutput.FOUT
			? FederatedOutput.LOUT
			: FederatedOutput.FOUT;
		boolean canChooseAlternative = alternativeOut == FederatedOutput.FOUT
			? entry.canChooseFOUT
			: entry.canChooseLOUT;
		if (!canChooseAlternative)
			return false;

		Set<Long> decisionMembers = selectDecisionMembers(entry.memberHopIDs, memoTable);
		double observedDelta = computeCompatiblePlanSelectionDelta(
			memoTable, hopID, entry, decisionMembers, observedOut, tentativeDecisions, null);
		double alternativeDelta = computeCompatiblePlanSelectionDelta(
			memoTable, hopID, entry, decisionMembers, alternativeOut, tentativeDecisions, null);
		return Double.isFinite(alternativeDelta)
			&& alternativeDelta + 1e-9 < observedDelta;
	}

	private static boolean isTransientBoundaryHop(Hop hopRef) {
		if (!(hopRef instanceof DataOp))
			return false;
		Types.OpOpData op = ((DataOp) hopRef).getOp();
		return op == Types.OpOpData.TRANSIENTREAD || op == Types.OpOpData.TRANSIENTWRITE;
	}

	private static FType resolveContextualPlanningFType(
		FederatedPlannerDpMemoTable.FedPlan selectedPlan, Hop hopRef) {

		if (selectedPlan == null || hopRef == null)
			return null;
		if (selectedPlan.getExecType() == ExecType.FED
			&& selectedPlan.getFedOutType() == FederatedOutput.FOUT) {
			return selectedPlan.getFType();
		}
		if (isTransientBoundaryHop(hopRef))
			return null;
		return selectedPlan.getCpFoutTypeOrFType();
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectCompatiblePlanVariant(
		FederatedPlannerDpMemoTable memoTable, long hopID, FederatedOutput desiredOut,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null)
			return null;

		FederatedPlannerDpMemoTable.FedPlanVariants variants = memoTable.getFedPlanVariants(Pair.of(hopID, desiredOut));
		if (variants == null || variants.isEmpty())
			return null;

		// If no decisions exist, keep the cheapest variant (index 0 after pruning).
		if (outputDecisions == null || outputDecisions.isEmpty())
			return variants.getFedPlanVariants().get(0);

		FederatedPlannerDpMemoTable.FedPlan compatible =
			findStrictCompatiblePlanVariant(memoTable, hopID, desiredOut, outputDecisions);
		if (compatible != null)
			return compatible;

		// Fallback: best available variant even if it triggers conversions.
		return variants.getFedPlanVariants().get(0);
	}

	private static FederatedPlannerDpMemoTable.FedPlan findStrictCompatiblePlanVariant(
		FederatedPlannerDpMemoTable memoTable, long hopID, FederatedOutput desiredOut,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null)
			return null;

		FederatedPlannerDpMemoTable.FedPlanVariants variants = memoTable.getFedPlanVariants(Pair.of(hopID, desiredOut));
		if (variants == null || variants.isEmpty())
			return null;

		if (outputDecisions == null || outputDecisions.isEmpty())
			return variants.getFedPlanVariants().get(0);

		for (FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
			if (candidate == null)
				continue;
			if (isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
				return candidate;
		}

		return null;
	}

	private static boolean isCompatibleWithChildDecisions(
		FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan plan,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null || plan == null || outputDecisions == null || outputDecisions.isEmpty())
			return true;

		for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
			long childOrigId = memoTable.resolveOriginalHopId(childEdge.getKey());
			FederatedOutput desiredChild = outputDecisions.get(childOrigId);
			if (desiredChild != null && desiredChild != childEdge.getValue()) {
				return false;
			}
		}
		return true;
	}

	private static Map<Long, ConflictEntry> collectConflictsSingleBFS(
		FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		Map<Long, ConflictEntry> conflictCheckMap = new HashMap<>();
		Queue<FederatedPlannerDpMemoTable.FedPlan> queue = new ArrayDeque<>();
		Set<FederatedPlannerDpMemoTable.FedPlan> visited = new HashSet<>();

		for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
			long childHopID = rootChild.getKey();
			long childOrigID = memoTable.resolveOriginalHopId(childHopID);
			FederatedOutput desiredOut = (outputDecisions != null) ? outputDecisions.get(childOrigID) : null;
			if (desiredOut == null)
				desiredOut = rootChild.getValue();
			FederatedPlannerDpMemoTable.FedPlan childPlan = selectCompatiblePlanVariant(
				memoTable, childHopID, desiredOut, outputDecisions);
			if (childPlan == null)
				childPlan = memoTable.getFedPlanAfterPrune(childHopID, desiredOut);
			if (childPlan == null) {
				String msg = "NULL FedPlan for root child hop " + rootChild.getKey();
				if (OptimizerUtils.isStrictFederatedConflictCheck())
					throw new DMLRuntimeException(msg);
				FederatedPlannerLogger.logNullFedPlanError(rootChild.getKey(), msg);
				continue;
			}
			queue.add(childPlan);
		}

		// Include additional executed roots, including loop-unrolled clone roots.
		// The clone roots preserve loop-context / multiplicity information that the
		// original roots lose. Cost aggregation later de-duplicates logical edges by
		// parent/child/original-out + loop-context so we do not blindly reintroduce
		// the old clone overcount.
		for (long rootHopID : memoTable.getAdditionalRootHopIDs()) {
			long rootOrigId = memoTable.resolveOriginalHopId(rootHopID);
			FederatedOutput desiredOut = (outputDecisions != null) ? outputDecisions.get(rootOrigId) : null;
			FederatedPlannerDpMemoTable.FedPlan lPlan =
				memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.LOUT);
			FederatedPlannerDpMemoTable.FedPlan fPlan =
				memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.FOUT);
			FederatedPlannerDpMemoTable.FedPlan seed;
			if (desiredOut != null) {
				seed = selectCompatiblePlanVariant(memoTable, rootHopID, desiredOut, outputDecisions);
				if (seed == null)
					seed = memoTable.getFedPlanAfterPrune(rootHopID, desiredOut);
			}
			else {
				seed =
					(lPlan == null) ? fPlan :
					(fPlan == null) ? lPlan :
					(lPlan.getCumulativeCost() <= fPlan.getCumulativeCost()) ? lPlan : fPlan;
				if (seed != null) {
					seed = selectCompatiblePlanVariant(memoTable, rootHopID, seed.getFedOutType(), outputDecisions);
				}
			}
			if (seed != null)
				queue.add(seed);
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
				FederatedOutput desiredChildOut = (outputDecisions != null) ? outputDecisions.get(childOrigHopID) : null;
				if (desiredChildOut == null)
					desiredChildOut = childOut;

				FederatedPlannerDpMemoTable.FedPlan childPlan = selectCompatiblePlanVariant(
					memoTable, childHopID, desiredChildOut, outputDecisions);
				if (childPlan == null)
					childPlan = memoTable.getFedPlanAfterPrune(childHopID, desiredChildOut);
				if (childPlan == null) {
					String msg = "NULL FedPlan for hop " + childHopID
						+ " as child of hop " + (currentHop != null ? currentHop.getHopID() : -1)
						+ " (" + (currentHop != null ? currentHop.getOpString() : "null") + ")";
					if (OptimizerUtils.isStrictFederatedConflictCheck())
						throw new DMLRuntimeException(msg);
					FederatedPlannerLogger.logNullFedPlanError(childHopID, msg);
					continue;
				}

				ConflictEntry entry = conflictCheckMap.get(childOrigHopID);
				if (entry == null) {
					conflictCheckMap.put(childOrigHopID,
						new ConflictEntry(childOut, current, childHopID, childPlan));
				}
				else {
					entry.addUsage(childOut, current, childHopID, childPlan);
				}

				queue.add(childPlan);
			}
		}

		return conflictCheckMap;
	}

	private static FederatedOutput resolveOneHopConflict(
		FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry,
		Map<Long, FederatedOutput> tentativeDecisions, int numWorkers) {

		return resolveOneHopConflict(
			memoTable, hopID, entry, tentativeDecisions, numWorkers, null);
	}

	private static FederatedOutput resolveOneHopConflict(
		FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry,
		Map<Long, FederatedOutput> tentativeDecisions, int numWorkers,
		Map<Long, ConflictEntry> conflictCheckMap) {

		return resolveOneHopConflict(
			memoTable, hopID, entry, tentativeDecisions, numWorkers, conflictCheckMap, null);
	}

	private static FederatedOutput resolveOneHopConflict(
		FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry,
		Map<Long, FederatedOutput> tentativeDecisions, int numWorkers,
		Map<Long, ConflictEntry> conflictCheckMap,
		ParentVariantDeltaCache parentVariantDeltaCache) {

		final boolean canChooseLOUT = entry.canChooseLOUT;
		final boolean canChooseFOUT = entry.canChooseFOUT;
		if (!canChooseLOUT && !canChooseFOUT)
			return null;

		final Hop hopRef = memoTable.resolveOriginalHop(hopID);
		final boolean trace = FederatedPlannerTrace.shouldTrace(hopRef);
		final int maxEdgeLogs = FederatedPlannerTrace.getMaxEdgeLogsPerHop();
		int edgeLogs = 0;
		Set<Long> decisionMembers = selectDecisionMembers(entry.memberHopIDs, memoTable);

			double lOutAdditionalCost = canChooseLOUT
				? computeCompatiblePlanSelectionDelta(
					memoTable, hopID, entry, decisionMembers, FederatedOutput.LOUT,
					tentativeDecisions, conflictCheckMap)
				: Double.POSITIVE_INFINITY;
			double fOutAdditionalCost = canChooseFOUT
				? computeCompatiblePlanSelectionDelta(
					memoTable, hopID, entry, decisionMembers, FederatedOutput.FOUT,
					tentativeDecisions, conflictCheckMap)
				: Double.POSITIVE_INFINITY;
		Set<String> seenCostEdges = new LinkedHashSet<>();
		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
			ExecType parentExec = parentPlan.getExecType();
			boolean parentIsFed = parentExec == ExecType.FED;

				for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
					if (memoTable.resolveOriginalHopId(edge.getKey()) != hopID)
						continue;
					long childHopID = edge.getKey();
					if (decisionMembers != null && !decisionMembers.isEmpty()
						&& !decisionMembers.contains(childHopID))
						continue;
					String edgeKey = buildConflictCostEdgeKey(
						memoTable, parentPlan, childHopID, edge.getValue());
					if (!seenCostEdges.add(edgeKey))
						continue;
					FederatedOutput originalOut = edge.getValue();

						double dL = (canChooseLOUT && originalOut != FederatedOutput.LOUT)
							? computeSwitchEdgeCostDelta(
								memoTable, childHopID, originalOut, FederatedOutput.LOUT,
								parentPlan, parentIsFed, numWorkers, conflictCheckMap,
								parentVariantDeltaCache)
							: 0.0;
						double dF = (canChooseFOUT && originalOut != FederatedOutput.FOUT)
							? computeSwitchEdgeCostDelta(
								memoTable, childHopID, originalOut, FederatedOutput.FOUT,
								parentPlan, parentIsFed, numWorkers, conflictCheckMap,
								parentVariantDeltaCache)
							: 0.0;

				if (trace && edgeLogs < maxEdgeLogs) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Edge", String.format(Locale.ROOT,
						"parentHop=%d parentExec=%s origOut=%s deltaToLOUT=%.6f deltaToFOUT=%.6f",
						parentPlan.getHopRef() != null ? parentPlan.getHopRef().getHopID() : -1,
						parentExec, originalOut, dL, dF));
					edgeLogs++;
				}

					if (canChooseLOUT && originalOut != FederatedOutput.LOUT)
						lOutAdditionalCost += dL;
					if (canChooseFOUT && originalOut != FederatedOutput.FOUT)
						fOutAdditionalCost += dF;
				}
			}

		FederatedOutput chosen;
		if (!canChooseFOUT || (canChooseLOUT && lOutAdditionalCost <= fOutAdditionalCost))
			chosen = FederatedOutput.LOUT;
		else
			chosen = FederatedOutput.FOUT;

		if (trace) {
			FederatedPlannerTrace.log(hopRef, "DP-OutputDecision", String.format(Locale.ROOT,
				"lOutAdditional=%.6f fOutAdditional=%.6f chosen=%s",
				lOutAdditionalCost, fOutAdditionalCost, chosen));
		}

		return chosen;
	}

	private static double computeCompatiblePlanSelectionDelta(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		ConflictEntry entry,
			Set<Long> decisionMembers,
			FederatedOutput targetOut,
			Map<Long, FederatedOutput> tentativeDecisions,
			Map<Long, ConflictEntry> conflictCheckMap) {

		if (memoTable == null || entry == null || targetOut == null)
			return 0.0;

		Map<Long, FederatedOutput> observedMemberOutputs =
			collectObservedMemberOutputs(memoTable, hopID, entry, decisionMembers);
		if (observedMemberOutputs.isEmpty())
			return 0.0;

		double delta = 0.0;
		for (Map.Entry<Long, FederatedOutput> e : observedMemberOutputs.entrySet()) {
			FederatedOutput currentOut = e.getValue();
			if (currentOut == null)
				continue;

			long memberHopID = e.getKey();
				FederatedPlannerDpMemoTable.FedPlan currentPlan =
					selectOutputDecisionCostPlanVariant(
						memoTable, memberHopID, currentOut, tentativeDecisions, conflictCheckMap);
				if (currentPlan == null)
					currentPlan = memoTable.getFedPlanAfterPrune(memberHopID, currentOut);

				FederatedPlannerDpMemoTable.FedPlan targetPlan =
					selectOutputDecisionCostPlanVariant(
						memoTable, memberHopID, targetOut, tentativeDecisions, conflictCheckMap);
				if (targetPlan == null)
					targetPlan = memoTable.getFedPlanAfterPrune(memberHopID, targetOut);

			if (currentPlan == null || targetPlan == null)
				return Double.POSITIVE_INFINITY;

			double currentShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				currentPlan.getCumulativeCost(), currentPlan);
			double targetShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				targetPlan.getCumulativeCost(), targetPlan);
			double memberDelta = targetShare - currentShare;
			Hop memberHopRef = memoTable.resolveOriginalHop(memberHopID);
			if (FederatedPlannerTrace.shouldTrace(memberHopRef)) {
				FederatedPlannerTrace.log(memberHopRef, "DP-OutputDecision-Member",
					String.format(Locale.ROOT,
						"orig=%d member=%d virtual=%s current=%s target=%s currentExec=%s targetExec=%s currentCost=%.6f targetCost=%.6f delta=%.6f multiplicity=%.6f loop=%s",
						hopID, memberHopID, memoTable.isVirtualClone(memberHopID), currentOut, targetOut,
						currentPlan.getExecType(), targetPlan.getExecType(), currentShare, targetShare,
						memberDelta, targetPlan.getMultiplicity(), formatLoopContext(targetPlan.getLoopContext())));
			}
			delta += memberDelta;
		}

			return delta;
		}

		private static FederatedPlannerDpMemoTable.FedPlan selectOutputDecisionCostPlanVariant(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			FederatedOutput targetOut,
			Map<Long, FederatedOutput> tentativeDecisions,
			Map<Long, ConflictEntry> conflictCheckMap) {

			if (targetOut == FederatedOutput.FOUT) {
				FederatedPlannerDpMemoTable.FedPlan compatiblePlan =
					findStrictCompatiblePlanVariant(memoTable, hopID, targetOut, tentativeDecisions);
				if (compatiblePlan != null)
					return compatiblePlan;
				FederatedPlannerDpMemoTable.FedPlan requiredClosurePlan =
					selectRequiredOutputClosurePlanVariant(memoTable, hopID, targetOut, conflictCheckMap);
				if (requiredClosurePlan != null)
					return requiredClosurePlan;
			}
			return selectCompatiblePlanVariant(memoTable, hopID, targetOut, tentativeDecisions);
		}

		private static Map<Long, FederatedOutput> collectObservedMemberOutputs(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		ConflictEntry entry,
		Set<Long> decisionMembers) {

		LinkedHashMap<Long, FederatedOutput> observed = new LinkedHashMap<>();
		if (memoTable == null || entry == null || entry.parents == null)
			return observed;

		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
			if (parentPlan == null || parentPlan.getChildFedPlans() == null)
				continue;
			for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
				if (edge == null || memoTable.resolveOriginalHopId(edge.getKey()) != hopID)
					continue;
				long memberHopID = edge.getKey();
				if (decisionMembers != null && !decisionMembers.isEmpty()
					&& !decisionMembers.contains(memberHopID))
					continue;

				if (!observed.containsKey(memberHopID)) {
					observed.put(memberHopID, edge.getValue());
					continue;
				}

				FederatedOutput previousOut = observed.get(memberHopID);
				if (previousOut != edge.getValue())
					observed.put(memberHopID, null);
			}
		}

		return observed;
	}

	private static double computeSwitchEdgeCostDelta(
		FederatedPlannerDpMemoTable memoTable, long childHopID,
		FederatedOutput fromOut, FederatedOutput toOut,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		boolean parentIsFed, int numWorkers) {

		return computeSwitchEdgeCostDelta(
			memoTable, childHopID, fromOut, toOut, parentPlan, parentIsFed, numWorkers, null);
	}

	private static double computeSwitchEdgeCostDelta(
		FederatedPlannerDpMemoTable memoTable, long childHopID,
		FederatedOutput fromOut, FederatedOutput toOut,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		boolean parentIsFed, int numWorkers,
		Map<Long, ConflictEntry> conflictCheckMap) {

		return computeSwitchEdgeCostDelta(
			memoTable, childHopID, fromOut, toOut, parentPlan, parentIsFed, numWorkers,
			conflictCheckMap, null);
	}

	private static double computeSwitchEdgeCostDelta(
		FederatedPlannerDpMemoTable memoTable, long childHopID,
		FederatedOutput fromOut, FederatedOutput toOut,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		boolean parentIsFed, int numWorkers,
		Map<Long, ConflictEntry> conflictCheckMap,
		ParentVariantDeltaCache parentVariantDeltaCache) {

		Hop childHopRef = memoTable != null ? memoTable.resolveOriginalHop(childHopID) : null;
		boolean trace = FederatedPlannerTrace.shouldTrace(childHopRef);

		// Prefer a parent-variant switch delta if possible.
		//
		// The output-decision phase may change child representations (LOUT/FOUT), which can
		// invalidate the currently-selected parent exec type. Estimating deltas purely from
		// child costs (upload/download boundary) over-penalizes LOUT when the parent could
		// instead switch to a CP variant. This was observed to force DP into FED elementwise
		// chains in kmeans WAN-mid even though a cheaper CP-compatible parent variant exists.
		FederatedPlannerDpMemoTable.FedPlan fromPlan = memoTable.getFedPlanAfterPrune(childHopID, fromOut);
		FederatedPlannerDpMemoTable.FedPlan toPlan = memoTable.getFedPlanAfterPrune(childHopID, toOut);
		if (fromPlan == null || toPlan == null)
			return Double.POSITIVE_INFINITY;

		double fromCumulativeShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
			fromPlan.getCumulativeCost(), fromPlan);
		double toCumulativeShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
			toPlan.getCumulativeCost(), toPlan);

		double fromForwardingShare = computeParentChildForwardingCostShare(
			memoTable, parentIsFed, fromOut, fromPlan, parentPlan, numWorkers);
		double toForwardingShare = computeParentChildForwardingCostShare(
			memoTable, parentIsFed, toOut, toPlan, parentPlan, numWorkers);

		double childForwardingDelta =
			(toCumulativeShare - fromCumulativeShare) + (toForwardingShare - fromForwardingShare);
		double parentVariantDelta = computeParentVariantSwitchDelta(
			memoTable, parentPlan, childHopID, toOut, conflictCheckMap, numWorkers,
			parentVariantDeltaCache);
		if (Double.isFinite(parentVariantDelta)) {
			if (trace) {
				FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantDelta", String.format(Locale.ROOT,
					"parentHop=%d parentExec=%s fromOut=%s toOut=%s delta=%.6f mode=%s rawParentVariant=%.6f childForwarding=%.6f",
					parentPlan != null && parentPlan.getHopRef() != null ? parentPlan.getHopRef().getHopID() : -1L,
					parentPlan != null ? parentPlan.getExecType() : null,
					fromOut, toOut, parentVariantDelta, "parent_variant", parentVariantDelta, childForwardingDelta));
			}
			return parentVariantDelta;
		}

		if (trace) {
			FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantDelta", String.format(Locale.ROOT,
				"parentHop=%d parentExec=%s fromOut=%s toOut=%s delta=%.6f mode=child_forwarding fromCum=%.6f toCum=%.6f fromForward=%.6f toForward=%.6f",
				parentPlan != null && parentPlan.getHopRef() != null ? parentPlan.getHopRef().getHopID() : -1L,
				parentPlan != null ? parentPlan.getExecType() : null,
				fromOut, toOut, childForwardingDelta, fromCumulativeShare, toCumulativeShare, fromForwardingShare, toForwardingShare));
		}
		return childForwardingDelta;
	}

	/**
	 * Estimate the cost delta of switching a single parent->child edge by selecting an
	 * alternative parent plan variant that matches the desired child output.
	 *
	 * <p>This captures cases where switching the child from FOUT->LOUT (or vice versa) is
	 * best addressed by changing the parent's exec type (FED->CP) rather than paying an
	 * upload/refed boundary into FED execution.</p>
	 *
	 * @return The delta in parent cumulative cost if a compatible variant exists; NaN otherwise.
	 */
	private static double computeParentVariantSwitchDelta(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		long childHopID,
		FederatedOutput desiredChildOut) {

		return computeParentVariantSwitchDelta(
			memoTable, parentPlan, childHopID, desiredChildOut, null, 1);
	}

	private static double computeParentVariantSwitchDelta(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		long childHopID,
		FederatedOutput desiredChildOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		int numWorkers) {

		return computeParentVariantSwitchDelta(
			memoTable, parentPlan, childHopID, desiredChildOut, conflictCheckMap, numWorkers, null);
	}

	private static double computeParentVariantSwitchDelta(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		long childHopID,
		FederatedOutput desiredChildOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		int numWorkers,
		ParentVariantDeltaCache parentVariantDeltaCache) {

		if (memoTable == null || parentPlan == null || parentPlan.getHopRef() == null)
			return Double.NaN;
		Hop childHopRef = memoTable.resolveOriginalHop(childHopID);
		boolean trace = FederatedPlannerTrace.shouldTrace(childHopRef);

		long parentHopID = parentPlan.getHopRef().getHopID();
		FederatedOutput downstreamDemandOut =
			resolveObservedDownstreamOutputDemand(memoTable, parentHopID, conflictCheckMap);

		// Parent/child relationships can involve cloned hop IDs across loop contexts.
		// Match edges by original hop id (not by concrete clone id) so that we can
		// correctly find compatible parent variants even when the parent references a
		// different clone of the same logical child hop.
		final long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
		ParentVariantDeltaKey cacheKey = null;
		if (!trace && parentVariantDeltaCache != null) {
			cacheKey = new ParentVariantDeltaKey(
				parentPlan, childOrigHopID, desiredChildOut, downstreamDemandOut, numWorkers);
			Double cachedDelta = parentVariantDeltaCache.get(cacheKey);
			if (cachedDelta != null)
				return cachedDelta;
		}
		// Consider parent variants across *both* output representations.
		//
		// When the output-decision phase switches a child edge (LOUT<->FOUT), the cheapest
		// compatible parent plan can also require switching the parent's own output type
		// (e.g., FED/FOUT -> CP/LOUT). Restricting the search to the parent's current
		// output type over-penalizes such switches and can lock DP into suboptimal FED
		// chains (observed in kmeans WAN-mid around hop 358/359).
		FederatedPlannerDpMemoTable.FedPlanVariants variantsLOUT =
			memoTable.getFedPlanVariants(Pair.of(parentHopID, FederatedOutput.LOUT));
		FederatedPlannerDpMemoTable.FedPlanVariants variantsFOUT =
			memoTable.getFedPlanVariants(Pair.of(parentHopID, FederatedOutput.FOUT));
		if ((variantsLOUT == null || variantsLOUT.isEmpty()) && (variantsFOUT == null || variantsFOUT.isEmpty())) {
			if (cacheKey != null)
				parentVariantDeltaCache.put(cacheKey, Double.NaN);
			return Double.NaN;
		}
		long currentEdgeHopId = -1L;
		FederatedOutput currentEdgeOut = null;
		if (trace) {
			int variantCountLOUT = variantsLOUT != null ? variantsLOUT.getFedPlanVariants().size() : 0;
			int variantCountFOUT = variantsFOUT != null ? variantsFOUT.getFedPlanVariants().size() : 0;
			FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantSearch", String.format(Locale.ROOT,
				"parentHop=%d parentExec=%s parentOut=%s parentCost=%.6f childHop=%d childOrig=%d desiredOut=%s downstreamDemandOut=%s variantsLOUT=%d variantsFOUT=%d",
				parentHopID, parentPlan.getExecType(), parentPlan.getFedOutType(), parentPlan.getCumulativeCost(),
				childHopID, childOrigHopID, desiredChildOut, downstreamDemandOut, variantCountLOUT, variantCountFOUT));
		}

		boolean parentReferencesChild = false;
		for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
			if (edge == null)
				continue;
			long edgeOrigHopID = memoTable.resolveOriginalHopId(edge.getKey());
			if (edgeOrigHopID == childOrigHopID) {
				parentReferencesChild = true;
				currentEdgeHopId = edge.getKey();
				currentEdgeOut = edge.getValue();
			}
			}
			if (!parentReferencesChild) {
				if (cacheKey != null)
					parentVariantDeltaCache.put(cacheKey, Double.NaN);
				return Double.NaN;
			}

		double bestDelta = Double.POSITIVE_INFINITY;
		int candidateLogs = 0;
		int referencedChildVariants = 0;
		int matchingDesiredVariants = 0;
		for (FederatedPlannerDpMemoTable.FedPlanVariants variants : new FederatedPlannerDpMemoTable.FedPlanVariants[] {variantsLOUT, variantsFOUT}) {
			if (variants == null || variants.isEmpty())
				continue;
			for (FederatedPlannerDpMemoTable.FedPlan cand : variants.getFedPlanVariants()) {
				if (cand == null)
					continue;
				if (downstreamDemandOut != null && cand.getFedOutType() != downstreamDemandOut)
					continue;
				if (cand.getChildFedPlans() == null || cand.getChildFedPlans().isEmpty())
					continue;
				boolean referencesChild = false;
				boolean ok = false;
				long matchedEdgeHopId = -1L;
				FederatedOutput matchedEdgeOut = null;
				for (Pair<Long, FederatedOutput> edge : cand.getChildFedPlans()) {
					if (edge == null)
						continue;
					long edgeOrigHopID = memoTable.resolveOriginalHopId(edge.getKey());
					if (edgeOrigHopID != childOrigHopID)
						continue;
					referencesChild = true;
					matchedEdgeHopId = edge.getKey();
					matchedEdgeOut = edge.getValue();
					if (matchedEdgeOut == desiredChildOut)
						ok = true;
				}
				if (referencesChild)
					referencedChildVariants++;
				if (ok)
					matchingDesiredVariants++;
				double rawDelta = cand.getCumulativeCost() - parentPlan.getCumulativeCost();
				double childShareAdjustment = computeParentVariantChildShareAdjustment(
					memoTable, parentPlan, currentEdgeHopId, currentEdgeOut, matchedEdgeHopId, matchedEdgeOut,
					parentVariantDeltaCache != null ? parentVariantDeltaCache.transientReadPlanShareCache : null);
				double overlappingParentShareAdjustment =
					computeOverlappingDirectParentChildShareAdjustment(
						memoTable, conflictCheckMap, childOrigHopID, parentPlan, cand,
						parentVariantDeltaCache != null
							? parentVariantDeltaCache.transientReadPlanShareCache : null);
				double downstreamForwardingDelta = computeParentVariantDownstreamForwardingDelta(
					memoTable, parentPlan, cand, conflictCheckMap, numWorkers);
				double adjustedDelta = rawDelta - childShareAdjustment
					- overlappingParentShareAdjustment + downstreamForwardingDelta;
				if (trace && candidateLogs < 6 && referencesChild) {
					FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantCandidate", String.format(Locale.ROOT,
						"parentHop=%d candExec=%s candOut=%s candCost=%.6f edgeHop=%d edgeOrig=%d matchDesired=%s delta=%.6f rawDelta=%.6f childShareAdj=%.6f overlapParentShareAdj=%.6f downstreamForwardingDelta=%.6f",
						parentHopID, cand.getExecType(), cand.getFedOutType(), cand.getCumulativeCost(),
						matchedEdgeHopId, childOrigHopID, ok, adjustedDelta, rawDelta,
						childShareAdjustment, overlappingParentShareAdjustment, downstreamForwardingDelta));
					candidateLogs++;
				}
				if (!ok)
					continue;
				bestDelta = Math.min(bestDelta, adjustedDelta);
			}
		}

			if (!Double.isFinite(bestDelta)) {
				if (trace) {
					FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantResult", String.format(Locale.ROOT,
						"parentHop=%d result=no_compatible_variant referencedChildVariants=%d matchingDesiredVariants=%d",
						parentHopID, referencedChildVariants, matchingDesiredVariants));
				}
				if (cacheKey != null)
					parentVariantDeltaCache.put(cacheKey, Double.NaN);
				return Double.NaN;
			}
		double delta = bestDelta;
		if (trace) {
				FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantResult", String.format(Locale.ROOT,
					"parentHop=%d result=compatible_variant referencedChildVariants=%d matchingDesiredVariants=%d bestDelta=%.6f",
					parentHopID, referencedChildVariants, matchingDesiredVariants, delta));
			}
			if (cacheKey != null)
				parentVariantDeltaCache.put(cacheKey, delta);
			return delta;
		}

	private static FederatedOutput resolveObservedDownstreamOutputDemand(
		FederatedPlannerDpMemoTable memoTable,
		long parentHopID,
		Map<Long, ConflictEntry> conflictCheckMap) {

		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty())
			return null;
		ConflictEntry parentEntry = conflictCheckMap.get(memoTable.resolveOriginalHopId(parentHopID));
		if (parentEntry == null)
			return null;
		if (parentEntry.seenFOUT && !parentEntry.seenLOUT)
			return FederatedOutput.FOUT;
		// A FOUT-only downstream observation is a hard requirement for this local
		// edge: switching the parent to LOUT would make the already-selected
		// downstream FED/FOUT chain incompatible unless that downstream chain is
		// explicitly re-costed.
		//
		// A LOUT-only observation is different. If the parent has a feasible FOUT
		// variant, LOUT is just the currently observed local state, not a semantic
		// demand. Treating it as hard filters out joint FOUT parent-chain variants
		// before cost comparison and under-models coordinator materialization/acquire
		// in local chains. Keep LOUT hard only when FOUT is not feasible.
		if (parentEntry.seenLOUT && !parentEntry.seenFOUT && !parentEntry.canChooseFOUT)
			return FederatedOutput.LOUT;
		return null;
	}

	private static double computeParentVariantDownstreamForwardingDelta(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan fromParentPlan,
		FederatedPlannerDpMemoTable.FedPlan candidateParentPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		int numWorkers) {

		if (memoTable == null || fromParentPlan == null || candidateParentPlan == null
			|| fromParentPlan.getHopRef() == null || conflictCheckMap == null || conflictCheckMap.isEmpty()) {
			return 0.0;
		}

		long parentOrigHopID = memoTable.resolveOriginalHopId(fromParentPlan.getHopRef().getHopID());
		long parentConcreteHopID = fromParentPlan.getHopRef().getHopID();
		ConflictEntry parentEntry = conflictCheckMap.get(parentOrigHopID);
		if (parentEntry == null || parentEntry.parents == null || parentEntry.parents.isEmpty())
			return 0.0;

		// Prefer the concrete member's selected downstream edge when it exists. A
		// candidate parent variant can, however, belong to a virtual clone whose
		// selected traversal is represented only by another member of the logical
		// family. In that case the family edge is the only proof of downstream
		// forwarding demand and must not be discarded.
		boolean hasCompatibleConcreteEdge = false;
		for (FederatedPlannerDpMemoTable.FedPlan downstreamPlan : parentEntry.parents) {
			if (downstreamPlan == null || downstreamPlan.getChildFedPlans() == null)
				continue;
			for (Pair<Long, FederatedOutput> edge : downstreamPlan.getChildFedPlans()) {
				if (edge != null && edge.getKey() == parentConcreteHopID
					&& edge.getValue() == candidateParentPlan.getFedOutType()) {
					hasCompatibleConcreteEdge = true;
					break;
				}
			}
			if (hasCompatibleConcreteEdge)
				break;
		}

		double delta = 0.0;
		Set<String> seenCostEdges = new LinkedHashSet<>();
		for (FederatedPlannerDpMemoTable.FedPlan downstreamPlan : parentEntry.parents) {
			if (downstreamPlan == null || downstreamPlan.getChildFedPlans() == null)
				continue;
			boolean downstreamIsFed = downstreamPlan.getExecType() == ExecType.FED;
			for (Pair<Long, FederatedOutput> edge : downstreamPlan.getChildFedPlans()) {
				if (edge == null || memoTable.resolveOriginalHopId(edge.getKey()) != parentOrigHopID)
					continue;
				if (hasCompatibleConcreteEdge && edge.getKey() != parentConcreteHopID)
					continue;
				if (candidateParentPlan.getFedOutType() != edge.getValue())
					continue;
				String edgeKey = buildConflictCostEdgeKey(memoTable, downstreamPlan, edge.getKey(), edge.getValue());
				if (!seenCostEdges.add(edgeKey))
					continue;
				double fromForwardingShare = computeParentChildForwardingCostShare(
					memoTable, downstreamIsFed, edge.getValue(), fromParentPlan, downstreamPlan, numWorkers);
				double candidateForwardingShare = computeParentChildForwardingCostShare(
					memoTable, downstreamIsFed, edge.getValue(), candidateParentPlan, downstreamPlan, numWorkers);
				delta += candidateForwardingShare - fromForwardingShare;
			}
		}
		return delta;
	}

	private static double computeParentVariantChildShareAdjustment(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		long currentChildHopID,
		FederatedOutput currentChildOut,
		long candidateChildHopID,
		FederatedOutput candidateChildOut,
		TransientReadPlanShareCache transientReadPlanShareCache) {

		if (memoTable == null || currentChildHopID < 0 || candidateChildHopID < 0
			|| currentChildOut == null || candidateChildOut == null) {
			return 0.0;
		}
		if (parentPlan == null || parentPlan.getHopRef() == null
			|| parentPlan.getHopRef().getDataType() == null
			|| !parentPlan.getHopRef().getDataType().isMatrix()) {
			return 0.0;
		}
		Hop childHopRef = memoTable.resolveOriginalHop(candidateChildHopID);
		if (childHopRef == null || childHopRef.getDataType() == null
			|| !childHopRef.getDataType().isMatrix()) {
			return 0.0;
		}

		FederatedPlannerDpMemoTable.FedPlan currentChildPlan =
			memoTable.getFedPlanAfterPrune(currentChildHopID, currentChildOut);
		FederatedPlannerDpMemoTable.FedPlan candidateChildPlan =
			memoTable.getFedPlanAfterPrune(candidateChildHopID, candidateChildOut);
		if (currentChildPlan == null || candidateChildPlan == null)
			return 0.0;

		boolean transientRead = childHopRef instanceof DataOp
			&& ((DataOp) childHopRef).getOp() == Types.OpOpData.TRANSIENTREAD;
		double currentShare = transientRead
			? computeTransientReadPlanShareForParent(
				currentChildPlan, memoTable, transientReadPlanShareCache)
			: FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				currentChildPlan.getCumulativeCost(), currentChildPlan);
		double candidateShare = transientRead
			? computeTransientReadPlanShareForParent(
				candidateChildPlan, memoTable, transientReadPlanShareCache)
			: FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				candidateChildPlan.getCumulativeCost(), candidateChildPlan);
		return candidateShare - currentShare;
	}

	private static double computeOverlappingDirectParentChildShareAdjustment(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, ConflictEntry> conflictCheckMap,
		long targetChildOrigHopID,
		FederatedPlannerDpMemoTable.FedPlan currentParentPlan,
		FederatedPlannerDpMemoTable.FedPlan candidateParentPlan,
		TransientReadPlanShareCache transientReadPlanShareCache) {

		if (memoTable == null || conflictCheckMap == null || currentParentPlan == null
			|| candidateParentPlan == null || currentParentPlan.getChildFedPlans() == null
			|| candidateParentPlan.getChildFedPlans() == null) {
			return 0.0;
		}
		ConflictEntry targetEntry = conflictCheckMap.get(targetChildOrigHopID);
		if (targetEntry == null || targetEntry.parents == null || targetEntry.parents.size() < 2)
			return 0.0;

		Set<Long> directParentOrigHopIDs = new LinkedHashSet<>();
		for (FederatedPlannerDpMemoTable.FedPlan directParent : targetEntry.parents) {
			if (directParent == null || directParent.getHopRef() == null)
				continue;
			directParentOrigHopIDs.add(
				memoTable.resolveOriginalHopId(directParent.getHopRef().getHopID()));
		}

		Map<Long, Pair<Long, FederatedOutput>> candidateEdges = new LinkedHashMap<>();
		for (Pair<Long, FederatedOutput> edge : candidateParentPlan.getChildFedPlans()) {
			if (edge == null || edge.getValue() == null)
				continue;
			long edgeOrigHopID = memoTable.resolveOriginalHopId(edge.getKey());
			if (edgeOrigHopID != targetChildOrigHopID)
				candidateEdges.putIfAbsent(edgeOrigHopID, edge);
		}

		double adjustment = 0.0;
		Set<Long> adjustedOrigHopIDs = new LinkedHashSet<>();
		for (Pair<Long, FederatedOutput> currentEdge : currentParentPlan.getChildFedPlans()) {
			if (currentEdge == null || currentEdge.getValue() == null)
				continue;
			long edgeOrigHopID = memoTable.resolveOriginalHopId(currentEdge.getKey());
			if (edgeOrigHopID == targetChildOrigHopID
				|| !directParentOrigHopIDs.contains(edgeOrigHopID)
				|| !adjustedOrigHopIDs.add(edgeOrigHopID)) {
				continue;
			}
			Pair<Long, FederatedOutput> candidateEdge = candidateEdges.get(edgeOrigHopID);
			if (candidateEdge == null)
				continue;
			adjustment += computeParentVariantChildShareAdjustment(
				memoTable, currentParentPlan,
				currentEdge.getKey(), currentEdge.getValue(),
				candidateEdge.getKey(), candidateEdge.getValue(),
				transientReadPlanShareCache);
		}
		return adjustment;
	}

	private static double computeTransientReadPlanShareForParent(
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable memoTable,
		TransientReadPlanShareCache transientReadPlanShareCache) {

		if (childPlan == null)
			return 0.0;
		if (transientReadPlanShareCache != null && !FederatedPlannerTrace.isEnabled()) {
			Double cached = transientReadPlanShareCache.get(childPlan);
			if (cached != null)
				return cached;
		}
		double share = childPlan.getFedOutType() == FederatedOutput.FOUT
			? FederatedPlannerDpCostEstimator.computeStableTransientReadFoutCumulativeShareForParent(
				childPlan, memoTable)
			: FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				childPlan.getCumulativeCost(), childPlan);
		if (transientReadPlanShareCache != null && !FederatedPlannerTrace.isEnabled())
			transientReadPlanShareCache.put(childPlan, share);
		return share;
	}

	private static double computeParentChildForwardingCostShare(
		boolean parentIsFed, FederatedOutput childOut,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		int numWorkers) {

		return computeParentChildForwardingCostShare(
			null, parentIsFed, childOut, childPlan, parentPlan, numWorkers, true);
	}

	private static double computeParentChildForwardingCostShare(
		FederatedPlannerDpMemoTable memoTable,
		boolean parentIsFed, FederatedOutput childOut,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		int numWorkers) {

		return computeParentChildForwardingCostShare(
			memoTable, parentIsFed, childOut, childPlan, parentPlan, numWorkers, true);
	}

	private static double computeParentChildForwardingCostShareWithoutTrace(
		FederatedPlannerDpMemoTable memoTable,
		boolean parentIsFed, FederatedOutput childOut,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		int numWorkers) {

		return computeParentChildForwardingCostShare(
			memoTable, parentIsFed, childOut, childPlan, parentPlan, numWorkers, false);
	}

	private static double computeParentChildForwardingCostShare(
		FederatedPlannerDpMemoTable memoTable,
		boolean parentIsFed, FederatedOutput childOut,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		int numWorkers,
		boolean traceBoundaryShare) {

		if (childPlan == null || parentPlan == null)
			return 0.0;
		if (isTransientWriteFoutMetadataPassThrough(parentPlan, childOut))
			return 0.0;

		if (parentIsFed && childOut == FederatedOutput.LOUT) {
			double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childPlan.getHopRef());
			FType uploadType = childPlan.getCpFoutTypeOrFType();
			double uploadCost = FederatedPlannerDpCostEstimator.computeUploadNetworkCost(
				transferMem, uploadType, numWorkers);
			uploadCost += FederatedCostModel.computeLocalToFedForwardingPenalty(uploadType, numWorkers);
			return computeForwardingCostShareForParent(uploadCost, childPlan, parentPlan, traceBoundaryShare);
		}
			else if (parentIsFed && childOut == FederatedOutput.FOUT && childPlan.getExecType() == ExecType.CP) {
				if (childPlan.isFoutMaterializationAccounted())
					return 0.0;
				FType uploadType = childPlan.getCpFoutTypeOrFType();
				double uploadCost = FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(
					childPlan.getHopRef(), parentPlan.getHopRef(), uploadType, numWorkers);
				return computeForwardingCostShareForParent(uploadCost, childPlan, parentPlan, traceBoundaryShare);
			}
		else if (parentIsFed && childOut == FederatedOutput.FOUT
			&& isDirectFederatedInputToTransientReadBoundary(childPlan, parentPlan)) {
			return 0.0;
		}
		else if (parentIsFed && childOut == FederatedOutput.FOUT
			&& (isTransientFedBoundary(childPlan) || isTransientFedBoundary(parentPlan))) {
			// A FED parent consuming a transient FOUT child, or a transient FED boundary
			// consuming a FOUT child, can still pay a real refed/upload cost at runtime when
			// the transient variable is rebound into a new federated instruction chain
			// (observed in kmeans WAN-mid around X_samples/X_samples_sq_norms -> fed_refed).
			// Charge the shared refed-network estimate here so output-decision reconciliation can
			// compare FED/FOUT against local alternatives instead of assuming zero forwarding.
			double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childPlan.getHopRef());
			double refedCost = FederatedPlannerDpCostEstimator.computeRefedNetworkCost(
				transferMem, childPlan.getFType(), numWorkers);
			return traceBoundaryShare
				? FederatedPlannerDpCostEstimator.computeFoutToFedForwardingShareForParent(
					refedCost, childPlan, parentPlan, memoTable)
				: FederatedPlannerDpCostEstimator.computeFoutToFedForwardingShareForParentWithoutTrace(
					refedCost, childPlan, parentPlan, memoTable);
		}
		else if (!parentIsFed && childOut == FederatedOutput.FOUT) {
			double downloadCost;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childPlan.getHopRef())) {
				downloadCost = 0.0;
			}
			else if (childPlan.getExecType() == ExecType.CP) {
				// Keep output-decision / root-seed reconciliation consistent with the base DP
				// child-cost estimator: CP/FOUT children already materialize their local output as
				// part of CP execution, so a CP parent must not pay an additional synthetic
				// FOUT->CP network download here.
				downloadCost = 0.0;
			}
			else
				downloadCost = FederatedPlannerDpCostEstimator.computeDownloadNetworkCost(
					FederatedCostModel.getEffectiveOutputMemEstimate(childPlan.getHopRef()),
					childPlan.getFType(), numWorkers);
			return memoTable != null
				? FederatedPlannerDpCostEstimator.computeParentChildFoutToCpDownloadShare(
					parentPlan.getHopRef(), downloadCost, childPlan, parentPlan, memoTable)
				: FederatedPlannerDpCostEstimator.computeParentChildFoutToCpDownloadShare(
					parentPlan.getHopRef(), downloadCost, childPlan, parentPlan);
		}
		return 0.0;
	}

	private static boolean isTransientWriteFoutMetadataPassThrough(
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		FederatedOutput childOut) {
		if (parentPlan == null || parentPlan.getFedOutType() != FederatedOutput.FOUT
			|| childOut != FederatedOutput.FOUT || !(parentPlan.getHopRef() instanceof DataOp)) {
			return false;
		}
		return ((DataOp) parentPlan.getHopRef()).getOp() == Types.OpOpData.TRANSIENTWRITE;
	}

	private static double computeForwardingCostShareForParent(
		double totalCost,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		boolean traceBoundaryShare) {

		return traceBoundaryShare
			? FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(totalCost, childPlan, parentPlan)
			: FederatedPlannerDpCostEstimator.computeForwardingCostShareForParentWithoutTrace(
				totalCost, childPlan, parentPlan);
	}

	private static boolean isDirectFederatedInputToTransientReadBoundary(
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		if (childPlan == null || parentPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		Hop parentHop = parentPlan.getHopRef();
		if (childHop == null || !(parentHop instanceof DataOp))
			return false;
		if (((DataOp) parentHop).getOp() != Types.OpOpData.TRANSIENTREAD
			|| childPlan.getExecType() != ExecType.FED
			|| childPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		if (childHop.isFederatedDataOp())
			return true;
		if (!(childHop instanceof DataOp))
			return false;
		DataOp childDataOp = (DataOp) childHop;
		return childDataOp.getOp() == Types.OpOpData.TRANSIENTREAD
			&& (FederatedPlannerDpCostEstimator.isStableFedInitTransientRead(childPlan)
				|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(childDataOp, null));
	}

	private static String buildConflictCostEdgeKey(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		long childHopID,
		FederatedOutput originalOut) {

		long parentOrigHopID = (memoTable != null && parentPlan != null && parentPlan.getHopRef() != null)
			? memoTable.resolveOriginalHopId(parentPlan.getHopRef().getHopID())
			: -1L;
		long childOrigHopID = (memoTable != null)
			? memoTable.resolveOriginalHopId(childHopID)
			: childHopID;
		ExecType parentExec = (parentPlan != null) ? parentPlan.getExecType() : null;
		double multiplicity = (parentPlan != null) ? parentPlan.getMultiplicity() : 1.0;
		String loopContext = formatLoopContext(parentPlan != null ? parentPlan.getLoopContext() : null);
		return parentOrigHopID + "|" + childOrigHopID + "|" + parentExec + "|"
			+ originalOut + "|" + multiplicity + "|" + loopContext;
	}


	private static String formatLoopContext(List<Pair<Long, Double>> loopContext) {
		if (loopContext == null || loopContext.isEmpty())
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < loopContext.size(); i++) {
			Pair<Long, Double> ctx = loopContext.get(i);
			if (i > 0)
				sb.append(',');
			long loopId = (ctx != null && ctx.getLeft() != null) ? ctx.getLeft() : -1L;
			double weight = (ctx != null && ctx.getRight() != null) ? ctx.getRight() : 0.0;
			sb.append(loopId).append(':').append(weight);
		}
		sb.append(']');
		return sb.toString();
	}

	private static boolean isTransientFedBoundary(FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null || childPlan.getHopRef() == null)
			return false;
		if (!(childPlan.getHopRef() instanceof DataOp))
			return false;
		Types.OpOpData op = ((DataOp) childPlan.getHopRef()).getOp();
		return op == Types.OpOpData.TRANSIENTREAD || op == Types.OpOpData.TRANSIENTWRITE;
	}

	private static final class ParentVariantDeltaCache {
		private final Map<ParentVariantDeltaKey, Double> values = new HashMap<>();
		private final TransientReadPlanShareCache transientReadPlanShareCache =
			new TransientReadPlanShareCache();

		Double get(ParentVariantDeltaKey key) {
			return values.get(key);
		}

		void put(ParentVariantDeltaKey key, double value) {
			values.put(key, value);
		}

		void clear() {
			values.clear();
		}
	}

	private static final class TransientReadPlanShareCache {
		private final Map<FederatedPlannerDpMemoTable.FedPlan, Double> values =
			new IdentityHashMap<>();

		Double get(FederatedPlannerDpMemoTable.FedPlan plan) {
			return values.get(plan);
		}

		void put(FederatedPlannerDpMemoTable.FedPlan plan, double value) {
			values.put(plan, value);
		}
	}

	private static final class ParentVariantDeltaKey {
		final FederatedPlannerDpMemoTable.FedPlan parentPlan;
		final long childOrigHopID;
		final FederatedOutput desiredChildOut;
		final FederatedOutput downstreamDemandOut;
		final int numWorkers;
		final int hash;

		ParentVariantDeltaKey(
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			long childOrigHopID,
			FederatedOutput desiredChildOut,
			FederatedOutput downstreamDemandOut,
			int numWorkers) {
			this.parentPlan = parentPlan;
			this.childOrigHopID = childOrigHopID;
			this.desiredChildOut = desiredChildOut;
			this.downstreamDemandOut = downstreamDemandOut;
			this.numWorkers = numWorkers;
			this.hash = Objects.hash(
				System.identityHashCode(parentPlan), childOrigHopID, desiredChildOut, downstreamDemandOut, numWorkers);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof ParentVariantDeltaKey))
				return false;
			ParentVariantDeltaKey that = (ParentVariantDeltaKey) obj;
			return this.parentPlan == that.parentPlan
				&& this.childOrigHopID == that.childOrigHopID
				&& this.desiredChildOut == that.desiredChildOut
				&& this.downstreamDemandOut == that.downstreamDemandOut
				&& this.numWorkers == that.numWorkers;
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class TransientReadParentsCache {
		private final Map<TransientReadParentsKey, LinkedHashSet<Long>> values = new HashMap<>();

		LinkedHashSet<Long> get(TransientReadParentsKey key) {
			return values.get(key);
		}

		void put(TransientReadParentsKey key, LinkedHashSet<Long> value) {
			values.put(key, value);
		}
	}

	private static final class TransientReadParentsKey {
		final Map<Long, ConflictEntry> conflictCheckMap;
		final long tWriteOrigHopID;
		final int hash;

		TransientReadParentsKey(Map<Long, ConflictEntry> conflictCheckMap, long tWriteOrigHopID) {
			this.conflictCheckMap = conflictCheckMap;
			this.tWriteOrigHopID = tWriteOrigHopID;
			this.hash = 31 * System.identityHashCode(conflictCheckMap) + Long.hashCode(tWriteOrigHopID);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof TransientReadParentsKey))
				return false;
			TransientReadParentsKey that = (TransientReadParentsKey) obj;
			return this.conflictCheckMap == that.conflictCheckMap
				&& this.tWriteOrigHopID == that.tWriteOrigHopID;
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class SimulationDecisionCache {
		private final Map<SimulationDecisionKey, Map<Long, FederatedOutput>> values = new HashMap<>();

		Map<Long, FederatedOutput> get(SimulationDecisionKey key) {
			return values.get(key);
		}

		void put(SimulationDecisionKey key, Map<Long, FederatedOutput> value) {
			values.put(key, value != null ? value : Collections.emptyMap());
		}
	}

	private static final class SimulationDecisionKey {
		final Map<Long, FederatedOutput> baseDecisions;
		final Map<Long, FederatedOutput> lockedDecisions;
		final int hash;

		SimulationDecisionKey(
			Map<Long, FederatedOutput> baseDecisions,
			Map<Long, FederatedOutput> lockedDecisions) {
			this.baseDecisions = baseDecisions != null ? new HashMap<>(baseDecisions) : Collections.emptyMap();
			this.lockedDecisions = lockedDecisions != null ? new HashMap<>(lockedDecisions) : Collections.emptyMap();
			this.hash = 31 * this.baseDecisions.hashCode() + this.lockedDecisions.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof SimulationDecisionKey))
				return false;
			SimulationDecisionKey that = (SimulationDecisionKey) obj;
			return Objects.equals(this.baseDecisions, that.baseDecisions)
				&& Objects.equals(this.lockedDecisions, that.lockedDecisions);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class DecisionMapScoreCache {
		private final FederatedPlannerDpMemoTable memoTable;
		private final FederatedPlannerDpMemoTable.FedPlan rootPlan;
		private final Map<DecisionMapScoreKey, DecisionMapScoreBreakdown> values = new HashMap<>();

		DecisionMapScoreCache(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan) {
			this.memoTable = memoTable;
			this.rootPlan = rootPlan;
		}

		DecisionMapScoreBreakdown get(Map<Long, FederatedOutput> outputDecisions) {
			DecisionMapScoreKey key = new DecisionMapScoreKey(outputDecisions);
			DecisionMapScoreBreakdown cached = values.get(key);
			if (cached != null)
				return cached;
			DecisionMapScoreBreakdown computed =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, key.outputDecisions);
			values.put(key, computed);
			return computed;
		}
	}

	private static final class DecisionMapScoreKey {
		final Map<Long, FederatedOutput> outputDecisions;
		final int hash;

		DecisionMapScoreKey(Map<Long, FederatedOutput> outputDecisions) {
			this.outputDecisions = outputDecisions != null ? new HashMap<>(outputDecisions) : Collections.emptyMap();
			this.hash = this.outputDecisions.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof DecisionMapScoreKey))
				return false;
			DecisionMapScoreKey that = (DecisionMapScoreKey) obj;
			return Objects.equals(this.outputDecisions, that.outputDecisions);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class OverlayDecisionMap extends AbstractMap<Long, FederatedOutput> {
		private final Map<Long, FederatedOutput> baseDecisions;
		private final Map<Long, FederatedOutput> overlayDecisions;

		OverlayDecisionMap(
			Map<Long, FederatedOutput> baseDecisions,
			Map<Long, FederatedOutput> overlayDecisions) {
			this.baseDecisions = baseDecisions != null ? baseDecisions : Collections.emptyMap();
			this.overlayDecisions = overlayDecisions != null ? overlayDecisions : Collections.emptyMap();
		}

		@Override
		public FederatedOutput get(Object key) {
			return overlayDecisions.containsKey(key) ? overlayDecisions.get(key) : baseDecisions.get(key);
		}

		@Override
		public boolean containsKey(Object key) {
			return overlayDecisions.containsKey(key) || baseDecisions.containsKey(key);
		}

		@Override
		public boolean isEmpty() {
			return baseDecisions.isEmpty() && overlayDecisions.isEmpty();
		}

		@Override
		public int size() {
			if (baseDecisions.isEmpty())
				return overlayDecisions.size();
			if (overlayDecisions.isEmpty())
				return baseDecisions.size();
			LinkedHashSet<Long> keys = new LinkedHashSet<>(baseDecisions.keySet());
			keys.addAll(overlayDecisions.keySet());
			return keys.size();
		}

		@Override
		public Set<Map.Entry<Long, FederatedOutput>> entrySet() {
			LinkedHashMap<Long, FederatedOutput> merged = new LinkedHashMap<>();
			merged.putAll(baseDecisions);
			merged.putAll(overlayDecisions);
			return Collections.unmodifiableMap(merged).entrySet();
		}
	}

	private static final class HopStampState {
		final Hop hop;
		final ExecType execType;
		final ExecType forcedExecType;
		final FederatedOutput fedOut;
		final boolean fedOutDerived;

		HopStampState(Hop hop, ExecType execType, ExecType forcedExecType,
				FederatedOutput fedOut, boolean fedOutDerived) {
			this.hop = hop;
			this.execType = execType;
			this.forcedExecType = forcedExecType;
			this.fedOut = fedOut;
			this.fedOutDerived = fedOutDerived;
		}
	}

	private static final class LocalMaterializeRequest {
		private final long producerHopID;
		private final Hop producerHop;
		private final LinkedHashMap<Long, Hop> consumerHops = new LinkedHashMap<>();
		private final LinkedHashMap<Long, FederatedOutput> consumerOutputs = new LinkedHashMap<>();
		private String fTypeHint;

		private LocalMaterializeRequest(long producerHopID, Hop producerHop) {
			this.producerHopID = producerHopID;
			this.producerHop = producerHop;
		}

		private void addConsumer(long consumerHopID, Hop consumerHop, FederatedOutput consumerOutput, FType fType) {
			consumerHops.put(consumerHopID, consumerHop);
			consumerOutputs.put(consumerHopID, consumerOutput);
			if (fTypeHint == null && fType != null)
				fTypeHint = fType.name();
		}
	}

	private static final class DecisionMapScoreBreakdown {
		double totalCost;
		double mainRootCost;
		double additionalRootCost;
		double virtualAdditionalRootCost;
		int missingRootCount;
		final LinkedHashMap<String, RootContribution> rootContributions = new LinkedHashMap<>();
		final IdentityHashMap<FederatedPlannerDpMemoTable.FedPlan, Double> compatibleChildAdjustmentCache =
			new IdentityHashMap<>();

		void addContribution(FederatedPlannerDpMemoTable.FedPlan plan, long rootHopID, boolean additionalRoot,
			FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedRootPlans,
			Map<Long, FederatedOutput> outputDecisions) {
			if (memoTable == null) {
				missingRootCount++;
				return;
			}
			boolean virtualClone = memoTable.isVirtualClone(rootHopID);
			long rootOrigHopID = memoTable.resolveOriginalHopId(rootHopID);
			if (plan == null || plan.getHopRef() == null) {
				missingRootCount++;
				rootContributions.put(rootHopID + "|missing",
					new RootContribution(rootHopID, rootOrigHopID, additionalRoot, virtualClone,
						null, null, Double.NaN, 0.0, "[]"));
				return;
			}

			double cost = computeDecisionMapRootContributionCost(
				plan, additionalRoot, memoTable, selectedRootPlans, outputDecisions, compatibleChildAdjustmentCache);
			totalCost += cost;
			if (additionalRoot) {
				additionalRootCost += cost;
				if (virtualClone)
					virtualAdditionalRootCost += cost;
			}
			else {
				mainRootCost += cost;
			}

			rootContributions.put(rootHopID + "|" + plan.getFedOutType(),
				new RootContribution(
					rootHopID,
					rootOrigHopID,
					additionalRoot,
					virtualClone,
					plan.getExecType(),
					plan.getFedOutType(),
					cost,
					plan.getMultiplicity(),
					formatLoopContext(plan.getLoopContext())));
		}
	}

	private static final class RootContribution {
		final long rootHopID;
		final long rootOrigHopID;
		final boolean additionalRoot;
		final boolean virtualClone;
		final ExecType execType;
		final FederatedOutput fedOut;
		final double cost;
		final double multiplicity;
		final String loopContext;

		RootContribution(long rootHopID, long rootOrigHopID, boolean additionalRoot, boolean virtualClone,
			ExecType execType, FederatedOutput fedOut, double cost, double multiplicity, String loopContext) {
			this.rootHopID = rootHopID;
			this.rootOrigHopID = rootOrigHopID;
			this.additionalRoot = additionalRoot;
			this.virtualClone = virtualClone;
			this.execType = execType;
			this.fedOut = fedOut;
			this.cost = cost;
			this.multiplicity = multiplicity;
			this.loopContext = loopContext;
		}
	}

	private static final class SharedMaterializationCredit {
		final double credit;
		final Set<String> familyKeys;
		final Set<String> ambientKeys;

		SharedMaterializationCredit(double credit, Set<String> familyKeys, Set<String> ambientKeys) {
			this.credit = Double.isFinite(credit) && credit > 0.0 ? credit : 0.0;
			this.familyKeys = familyKeys != null ? familyKeys : Collections.emptySet();
			this.ambientKeys = ambientKeys != null ? ambientKeys : Collections.emptySet();
		}

		static SharedMaterializationCredit empty() {
			return new SharedMaterializationCredit(0.0, Collections.emptySet(), Collections.emptySet());
		}
	}

	private static final class ConflictEntry {
		final LinkedHashSet<FederatedPlannerDpMemoTable.FedPlan> parents;
		final LinkedHashSet<Long> memberHopIDs;
		final Map<Pair<Long, FederatedOutput>, FederatedPlannerDpMemoTable.FedPlan> selectedMemberPlans;
		boolean seenLOUT;
		boolean seenFOUT;
		boolean canChooseLOUT;
		boolean canChooseFOUT;

		ConflictEntry(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID,
			FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {
			this.parents = new LinkedHashSet<>();
			this.memberHopIDs = new LinkedHashSet<>();
			this.selectedMemberPlans = new LinkedHashMap<>();
			this.parents.add(parent);
			this.memberHopIDs.add(memberHopID);
			if (selectedMemberPlan != null)
				this.selectedMemberPlans.put(Pair.of(memberHopID, out), selectedMemberPlan);
			this.seenLOUT = (out == FederatedOutput.LOUT);
			this.seenFOUT = (out == FederatedOutput.FOUT);
			this.canChooseLOUT = true;
			this.canChooseFOUT = true;
		}

		void addUsage(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID,
			FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {
			this.parents.add(parent);
			this.memberHopIDs.add(memberHopID);
			if (selectedMemberPlan != null)
				this.selectedMemberPlans.putIfAbsent(Pair.of(memberHopID, out), selectedMemberPlan);
			if (out == FederatedOutput.LOUT)
				this.seenLOUT = true;
			else if (out == FederatedOutput.FOUT)
				this.seenFOUT = true;
		}
	}
}
