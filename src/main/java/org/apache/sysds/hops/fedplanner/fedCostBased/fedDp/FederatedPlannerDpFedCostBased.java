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
import java.util.Comparator;
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
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
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

	public record DeferredOutputDecisionReceipt(int ordinal, long decisionHopId, long originalHopId,
		FederatedOutput desiredOutput, PlacementAnalysis.HopOccurrenceProjection occurrence,
		CompiledHopKey key, FederatedPlannerDpMemoTable.FedPlan plan, Hop planningHop,
		PlacementState state, boolean derivedFedFout) {
		public DeferredOutputDecisionReceipt {
			Objects.requireNonNull(desiredOutput, "desiredOutput");
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(plan, "plan");
			Objects.requireNonNull(planningHop, "planningHop");
			Objects.requireNonNull(state, "state");
			if(ordinal < 0 || decisionHopId < 0 || originalHopId < 0)
				throw new IllegalArgumentException("Deferred output-decision ordinal or Hop ID is negative");
			if(occurrence.key() != key || occurrence.hop() != planningHop
				|| plan.getHopRef() != planningHop || plan.getHopID() != planningHop.getHopID())
				throw new IllegalArgumentException("Deferred output-decision plan carrier identity differs");
			if(plan.getSelectedPlacementState() != state || plan.getFedOutType() != desiredOutput
				|| state.execType() != plan.getExecType() || state.output() != desiredOutput
				|| state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
					&& state.fType() != plan.getFType()
				|| derivedFedFout != plan.isDerivedFedFout())
				throw new IllegalArgumentException("Deferred output-decision selected state differs");
		}
	}

	public record DisconnectedCompletionReceipt(int ordinal, int appliedPlanOrdinal,
		AppliedPlanReceipt appliedPlan, int componentOrdinal, String analysisFingerprint,
		List<CompiledHopKey> componentMembers, CompiledHopKey sinkRoot,
		PlacementAnalysis.HopOccurrenceProjection sinkRootOccurrence) {
		public DisconnectedCompletionReceipt {
			Objects.requireNonNull(appliedPlan, "appliedPlan");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			Objects.requireNonNull(componentMembers, "componentMembers");
			Objects.requireNonNull(sinkRoot, "sinkRoot");
			Objects.requireNonNull(sinkRootOccurrence, "sinkRootOccurrence");
			componentMembers = List.copyOf(componentMembers);
			if(ordinal < 0 || appliedPlanOrdinal < 0 || componentOrdinal < 0
				|| analysisFingerprint.isBlank())
				throw new IllegalArgumentException("Disconnected completion ordinal or fingerprint differs");
			// The applied plan may be carried by an unrolled/recompile clone.  The
			// enclosing invocation receipt, which owns the memo authority, verifies
			// that this physical carrier maps to sinkRootOccurrence exactly.
			if(appliedPlan.ordinal() != appliedPlanOrdinal || !appliedPlan.additionalRoot()
				|| sinkRootOccurrence.key() != sinkRoot)
				throw new IllegalArgumentException("Disconnected completion applied-plan identity differs");
			if(componentMembers.isEmpty()
				|| componentMembers.stream().noneMatch(member -> member == sinkRoot))
				throw new IllegalArgumentException("Disconnected completion root is outside its component");
			for(int i = 0; i < componentMembers.size(); i++) {
				CompiledHopKey member = Objects.requireNonNull(componentMembers.get(i), "componentMember");
				if(i > 0 && componentMembers.get(i - 1).compareTo(member) >= 0)
					throw new IllegalArgumentException(
						"Disconnected completion component members are not sorted and unique");
			}
		}
	}

	public record InvocationCounters(int enumerationCount, int exactSelectionCount,
		int applicationPhaseCount, int appliedPlanCount, int additionalRootInvocationCount,
		int additionalRootNoOpCount, int internalAnalysisBuildCount,
		int oldOverloadCount, int reenumerationCount, int repairCount, int fallbackCount,
		int doubleApplicationCount) { }

	/** Exact, order-independent cost certificate for the placement forest that is emitted. */
	public record FinalPlanTerm(CompiledHopKey occurrence,
		FederatedPlannerDpMemoTable.FedPlan retainedPlan, long cumulativeCostBits,
		long exclusiveRecurrenceCostBits,
		List<FederatedPlannerDpCostEstimator.ChildCostReceipt> childContributions) {
		public FinalPlanTerm {
			Objects.requireNonNull(occurrence, "occurrence");
			childContributions = List.copyOf(childContributions);
			if(retainedPlan == null && (cumulativeCostBits != Double.doubleToRawLongBits(0d)
				|| exclusiveRecurrenceCostBits != Double.doubleToRawLongBits(0d)
				|| !childContributions.isEmpty()))
				throw new IllegalArgumentException("Synthetic DP certificate term must be zero-cost");
		}
	}

	public record FinalPlanCertificate(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, List<FinalPlanTerm> terms,
		long objectiveCostBits, String canonicalSignature) {
		public FinalPlanCertificate {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(memo, "memo");
			terms = List.copyOf(terms);
			Objects.requireNonNull(canonicalSignature, "canonicalSignature");
			if(memo.analysis() != analysis)
				throw new IllegalArgumentException("Final DP certificate producer identities differ");
			CompiledHopKey previous = null;
			for(FinalPlanTerm term : terms) {
				if(previous != null && previous.compareTo(term.occurrence()) >= 0)
					throw new IllegalArgumentException("Final DP certificate terms are not canonical");
				previous = term.occurrence();
				if(term.retainedPlan() == null) {
					NodeKind kind = analysis.graph().node(term.occurrence()).orElseThrow().kind();
					if(kind != NodeKind.FUNCTION_INPUT && kind != NodeKind.FUNCTION_OUTPUT)
						throw new IllegalArgumentException(
							"Only synthetic function boundaries may publish a zero-cost DP certificate term");
				}
			}
		}
	}

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
		FinalPlanCertificate finalPlanCertificate,
		DpSemanticConsumptionReceipt semanticConsumption,
		List<AppliedPlanReceipt> appliedPlans, List<AdditionalRootInvocationReceipt> additionalRootInvocations,
		List<CompiledHopKey> appliedTraversalKeys,
		List<DeferredOutputDecisionReceipt> deferredOutputDecisionReceipts,
		List<CompiledHopKey> supersededPreCompletionKeys,
		List<DisconnectedCompletionReceipt> disconnectedCompletionReceipts,
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
			Objects.requireNonNull(finalPlanCertificate, "finalPlanCertificate");
			Objects.requireNonNull(semanticConsumption, "semanticConsumption");
			Objects.requireNonNull(counters, "counters");
			Objects.requireNonNull(analysisFingerprintBefore, "analysisFingerprintBefore");
			Objects.requireNonNull(analysisFingerprintAfter, "analysisFingerprintAfter");
			Objects.requireNonNull(normalizedResult, "normalizedResult");
			Objects.requireNonNull(emissionReceipt, "emissionReceipt");
			if(normalizedResult.analysis() != analysis
				|| !PlacementEmissionTransaction.canonicalPlanHash(normalizedResult).equals(emissionReceipt.planHash()))
				throw new IllegalArgumentException("DP normalized result and emission receipt differ");
			appliedPlans = List.copyOf(appliedPlans);
			additionalRootInvocations = List.copyOf(additionalRootInvocations);
			appliedTraversalKeys = List.copyOf(appliedTraversalKeys);
			deferredOutputDecisionReceipts = List.copyOf(deferredOutputDecisionReceipts);
			supersededPreCompletionKeys = List.copyOf(supersededPreCompletionKeys);
			disconnectedCompletionReceipts = List.copyOf(disconnectedCompletionReceipts);
			if(memo.analysis() != analysis || analysis != exactSelection.analysis() || memo != exactSelection.memo()
				|| legacyOptimalPlan != exactSelection.legacyOptimalPlan()
				|| finalPlanCertificate.analysis() != analysis || finalPlanCertificate.memo() != memo)
				throw new IllegalArgumentException("DP receipt producer identities differ");
			if(!normalizedResult.objectiveCertificate().equals(
				"objectiveBits=" + finalPlanCertificate.objectiveCostBits()))
				throw new IllegalArgumentException("DP normalized result is not certified by the final plan forest");
			if(finalPlanCertificate.terms().size() != normalizedResult.selectedStates().size()
				|| finalPlanCertificate.terms().stream().anyMatch(term ->
					identityMapValue(normalizedResult.selectedStates(), term.occurrence()) == null))
				throw new IllegalArgumentException("DP final certificate does not cover every emitted decision");
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
			for(int i = 0; i < appliedPlans.size(); i++) {
				AppliedPlanReceipt applied = appliedPlans.get(i);
				boolean uniquePlan = plans.add(applied.plan());
				if(applied.ordinal() != i || !uniquePlan)
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
			Set<CompiledHopKey> aggregateExplicitClosure =
				Collections.newSetFromMap(new IdentityHashMap<>());
			CompiledHopKey previousAppliedKey = null;
			for(CompiledHopKey key : appliedTraversalKeys) {
				if(key == null || !containsCompiledKeyIdentity(analysis, key)
					|| !aggregateExplicitClosure.add(key))
					throw new IllegalArgumentException(
						"Applied traversal contains foreign or duplicate exact authority");
				if(previousAppliedKey != null && previousAppliedKey.compareTo(key) >= 0)
					throw new IllegalArgumentException("Applied traversal keys are not in canonical order");
				if(identityMapValue(normalizedResult.selectedEmissionStates(), key) == null)
					throw new IllegalArgumentException(
						"Applied traversal key is absent from the normalized result");
				previousAppliedKey = key;
			}
			Set<CompiledHopKey> deferredKeys = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<PlacementAnalysis.HopOccurrenceProjection> deferredOccurrences =
				Collections.newSetFromMap(new IdentityHashMap<>());
			Set<FederatedPlannerDpMemoTable.FedPlan> deferredPlans =
				Collections.newSetFromMap(new IdentityHashMap<>());
			Set<Long> deferredDecisionHopIds = new HashSet<>();
			for(int i = 0; i < deferredOutputDecisionReceipts.size(); i++) {
				DeferredOutputDecisionReceipt receipt = deferredOutputDecisionReceipts.get(i);
				if(receipt.ordinal() != i || !deferredKeys.add(receipt.key())
					|| !deferredOccurrences.add(receipt.occurrence())
					|| !deferredPlans.add(receipt.plan())
					|| !deferredDecisionHopIds.add(receipt.decisionHopId()))
					throw new IllegalArgumentException(
						"Deferred output-decision order or identity is duplicated at ordinal=" + i);
				if(!containsOccurrenceIdentity(analysis, receipt.occurrence())
					|| memo.requirePlanCarrierOccurrence(receipt.plan().getHopRef()) != receipt.occurrence()
					|| memo.resolveOriginalHopId(receipt.decisionHopId()) != receipt.originalHopId()
					|| memo.resolveOriginalHopId(receipt.plan().getHopID()) != receipt.originalHopId())
					throw new IllegalArgumentException("Deferred output-decision producer authority differs");
				PlacementState normalizedState =
					identityMapValue(normalizedResult.selectedStates(), receipt.key());
				PlacementEmissionState normalizedEmission =
					identityMapValue(normalizedResult.selectedEmissionStates(), receipt.key());
				if(normalizedState != receipt.state() || normalizedEmission == null
					|| normalizedEmission.placementState() != receipt.state()
					|| normalizedEmission.derivedFedFout() != receipt.derivedFedFout())
					throw new IllegalArgumentException("Deferred output-decision normalized state differs");
				if(aggregateExplicitClosure.contains(receipt.key()))
					throw new IllegalArgumentException(
						"Deferred output-decision overlaps aggregate/explicit plan closure");
			}
			Set<CompiledHopKey> supersededKeys = Collections.newSetFromMap(new IdentityHashMap<>());
			CompiledHopKey previousSupersededKey = null;
			for(CompiledHopKey key : supersededPreCompletionKeys) {
				if(key == null || !containsCompiledKeyIdentity(analysis, key)
					|| !supersededKeys.add(key))
					throw new IllegalArgumentException(
						"Superseded pre-completion authority contains foreign or duplicate identity");
				if(previousSupersededKey != null && previousSupersededKey.compareTo(key) >= 0)
					throw new IllegalArgumentException(
						"Superseded pre-completion keys are not in canonical order");
				if(aggregateExplicitClosure.contains(key) || deferredKeys.contains(key))
					throw new IllegalArgumentException(
						"Superseded pre-completion authority remains in an active receipt category");
				if(identityMapValue(normalizedResult.selectedEmissionStates(), key) == null)
					throw new IllegalArgumentException(
						"Superseded pre-completion key is absent from the normalized result");
				previousSupersededKey = key;
			}
			if(disconnectedCompletionReceipts.size() != appliedPlans.size() - expectedAppliedOrdinal)
				throw new IllegalArgumentException(
					"Disconnected completion receipts differ from trailing applied-plan suffix");
			Set<AppliedPlanReceipt> disconnectedApplied =
				Collections.newSetFromMap(new IdentityHashMap<>());
			Set<CompiledHopKey> disconnectedRoots =
				Collections.newSetFromMap(new IdentityHashMap<>());
			Set<PlacementAnalysis.HopOccurrenceProjection> disconnectedOccurrences =
				Collections.newSetFromMap(new IdentityHashMap<>());
			int previousComponentOrdinal = -1;
			for(int i = 0; i < disconnectedCompletionReceipts.size(); i++) {
				DisconnectedCompletionReceipt receipt = disconnectedCompletionReceipts.get(i);
				AppliedPlanReceipt applied = appliedPlans.get(expectedAppliedOrdinal + i);
				if(receipt.ordinal() != i || receipt.appliedPlanOrdinal() != expectedAppliedOrdinal + i
					|| receipt.appliedPlan() != applied || !applied.additionalRoot()
					|| !disconnectedApplied.add(applied) || !disconnectedRoots.add(receipt.sinkRoot())
					|| !disconnectedOccurrences.add(receipt.sinkRootOccurrence())
					|| receipt.componentOrdinal() < previousComponentOrdinal)
					throw new IllegalArgumentException(
						"Disconnected completion order or identity differs at ordinal=" + i);
				previousComponentOrdinal = receipt.componentOrdinal();
				if(!analysis.analysisFingerprint().equals(receipt.analysisFingerprint())
					|| !containsOccurrenceIdentity(analysis, receipt.sinkRootOccurrence())
					|| memo.requirePlanCarrierOccurrence(applied.plan().getHopRef())
						!= receipt.sinkRootOccurrence())
					throw new IllegalArgumentException("Disconnected completion producer authority differs");
				for(CompiledHopKey member : receipt.componentMembers())
					if(!containsCompiledKeyIdentity(analysis, member))
						throw new IllegalArgumentException(
							"Disconnected completion component contains foreign authority");
				PlacementState normalizedState =
					identityMapValue(normalizedResult.selectedStates(), receipt.sinkRoot());
				PlacementEmissionState normalizedEmission =
					identityMapValue(normalizedResult.selectedEmissionStates(), receipt.sinkRoot());
				if(normalizedState != applied.plan().getSelectedPlacementState()
					|| normalizedEmission == null
					|| normalizedEmission.placementState() != applied.plan().getSelectedPlacementState()
					|| normalizedEmission.derivedFedFout() != applied.plan().isDerivedFedFout())
					throw new IllegalArgumentException("Disconnected completion normalized state differs");
				if(aggregateExplicitClosure.contains(receipt.sinkRoot())
					|| deferredKeys.contains(receipt.sinkRoot()))
					throw new IllegalArgumentException(
						"Disconnected completion overlaps a pre-completion receipt category: sink="
							+ receipt.sinkRoot().normalizedSignature()
							+ " aggregateClosure=" + aggregateExplicitClosure.contains(receipt.sinkRoot())
							+ " deferred=" + deferredKeys.contains(receipt.sinkRoot())
							+ " component=" + receipt.componentOrdinal()
							+ " appliedPlanOrdinal=" + receipt.appliedPlanOrdinal()
							+ " planningHopId=" + applied.planningHopId());
			}
			for(CompiledHopKey key : supersededKeys)
				if(disconnectedCompletionReceipts.stream().noneMatch(receipt ->
					receipt.componentMembers().stream().anyMatch(member -> member == key)))
					throw new IllegalArgumentException(
						"Superseded pre-completion key lacks final disconnected-component authority");
			validateAppliedPlanningIdentityReuse(appliedPlans, expectedAppliedOrdinal,
				supersededKeys, disconnectedCompletionReceipts, memo);
			for(int i = 0; i < expectedAppliedOrdinal; i++) {
				CompiledHopKey rootKey =
					memo.requirePlanCarrierOccurrence(appliedPlans.get(i).plan().getHopRef()).key();
				if(!aggregateExplicitClosure.contains(rootKey) && !supersededKeys.contains(rootKey))
					throw new IllegalArgumentException(
						"Applied root is absent from active or superseded traversal authority");
			}
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

	/**
	 * Applied-plan receipts normally have a unique planning Hop and Hop ID. The sole
	 * exception is a pre-completion root whose exact authority was explicitly
	 * superseded by legality repair and then replaced by the final disconnected
	 * component sink. Selection is still emitted transactionally once from the final
	 * normalized result; this pair records preference replacement, not runtime double
	 * application. Every other partial, cross-carrier, or repeated collision remains
	 * fail-closed.
	 */
	private static void validateAppliedPlanningIdentityReuse(
		List<AppliedPlanReceipt> appliedPlans, int preCompletionCount,
		Set<CompiledHopKey> supersededKeys,
		List<DisconnectedCompletionReceipt> disconnectedCompletionReceipts,
		FederatedPlannerDpMemoTable memo) {
		Map<Hop, AppliedPlanReceipt> firstByHop = new IdentityHashMap<>();
		Map<Long, AppliedPlanReceipt> firstById = new HashMap<>();
		Set<AppliedPlanReceipt> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
		for(AppliedPlanReceipt applied : appliedPlans) {
			AppliedPlanReceipt priorHop = firstByHop.putIfAbsent(applied.planningHop(), applied);
			AppliedPlanReceipt priorId = firstById.putIfAbsent(applied.planningHopId(), applied);
			if(priorHop == null && priorId == null)
				continue;
			boolean samePrior = priorHop != null && priorHop == priorId;
			PlacementAnalysis.HopOccurrenceProjection priorOccurrence = samePrior
				? memo.requirePlanCarrierOccurrence(priorHop.planningHop()) : null;
			PlacementAnalysis.HopOccurrenceProjection replacementOccurrence = samePrior
				? memo.requirePlanCarrierOccurrence(applied.planningHop()) : null;
			CompiledHopKey replacementKey = replacementOccurrence == null
				? null : replacementOccurrence.key();
			long replacementReceipts = disconnectedCompletionReceipts.stream()
				.filter(receipt -> receipt.appliedPlan() == applied
					&& receipt.sinkRoot() == replacementKey).count();
			boolean validSupersededReplacement = samePrior
				&& priorHop.ordinal() < preCompletionCount
				&& applied.ordinal() >= preCompletionCount
				&& applied.additionalRoot()
				&& priorHop.plan() != applied.plan()
				&& priorOccurrence == replacementOccurrence
				&& supersededKeys.contains(replacementKey)
				&& replacementReceipts == 1
				&& replaced.add(priorHop);
			if(!validSupersededReplacement)
				throw new IllegalArgumentException(
					"Applied planning Hop/ID reuse is not an exact superseded-root replacement: prior="
						+ (priorHop == null ? "-" : priorHop.ordinal() + ":" + priorHop.planningHopId())
						+ " priorById=" + (priorId == null ? "-" : priorId.ordinal() + ":" + priorId.planningHopId())
						+ " replacement=" + applied.ordinal() + ":" + applied.planningHopId()
						+ " replacementReceipts=" + replacementReceipts);
		}
	}

	/**
	 * Complete DP selection authority captured before the single transactional emission.
	 *
	 * <p>The separation is intentional: cross-planner certificates must compare DP and
	 * Exact on the same immutable {@link PlacementAnalysis}.  Reconstructing an Exact
	 * cost surface after DP has rewired the HOP DAG is not a valid comparison basis.</p>
	 */
	public record DpPreEmissionSelection(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo,
		FederatedPlannerDpMemoTable.FedPlan legacyOptimalPlan,
		DpPlacementAdapter.ExactSelection exactSelection,
		FinalPlanCertificate finalPlanCertificate,
		DpSemanticConsumptionReceipt semanticConsumption,
		List<AppliedPlanReceipt> appliedPlans,
		List<AdditionalRootInvocationReceipt> additionalRootInvocations,
		List<CompiledHopKey> appliedTraversalKeys,
		List<DeferredOutputDecisionReceipt> deferredOutputDecisionReceipts,
		List<CompiledHopKey> supersededPreCompletionKeys,
		List<DisconnectedCompletionReceipt> disconnectedCompletionReceipts,
		String analysisFingerprintBefore, String analysisFingerprintAfterSelection,
		NormalizedPlannerResult normalizedResult) {
		public DpPreEmissionSelection {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(memo, "memo");
			Objects.requireNonNull(legacyOptimalPlan, "legacyOptimalPlan");
			Objects.requireNonNull(exactSelection, "exactSelection");
			Objects.requireNonNull(finalPlanCertificate, "finalPlanCertificate");
			Objects.requireNonNull(semanticConsumption, "semanticConsumption");
			Objects.requireNonNull(analysisFingerprintBefore, "analysisFingerprintBefore");
			Objects.requireNonNull(analysisFingerprintAfterSelection,
				"analysisFingerprintAfterSelection");
			Objects.requireNonNull(normalizedResult, "normalizedResult");
			appliedPlans = List.copyOf(appliedPlans);
			additionalRootInvocations = List.copyOf(additionalRootInvocations);
			appliedTraversalKeys = List.copyOf(appliedTraversalKeys);
			deferredOutputDecisionReceipts = List.copyOf(deferredOutputDecisionReceipts);
			supersededPreCompletionKeys = List.copyOf(supersededPreCompletionKeys);
			disconnectedCompletionReceipts = List.copyOf(disconnectedCompletionReceipts);
			if(memo.analysis() != analysis || exactSelection.analysis() != analysis
				|| finalPlanCertificate.analysis() != analysis || normalizedResult.analysis() != analysis)
				throw new IllegalArgumentException("DP pre-emission producer identities differ");
			if(!analysis.analysisFingerprint().equals(analysisFingerprintBefore)
				|| !analysisFingerprintBefore.equals(analysisFingerprintAfterSelection))
				throw new IllegalArgumentException("DP selection changed immutable analysis authority");
			analysis.assertProgramStructureUnchanged();
		}
	}

	private static boolean containsOccurrenceIdentity(PlacementAnalysis analysis,
		PlacementAnalysis.HopOccurrenceProjection occurrence) {
		return analysis.occurrences().stream().anyMatch(candidate -> candidate == occurrence);
	}

	private static boolean containsCompiledKeyIdentity(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.graph().decisionNodes().stream().anyMatch(node -> node.key() == key);
	}

	private static <T> T identityMapValue(Map<CompiledHopKey, T> map, CompiledHopKey key) {
		for(Map.Entry<CompiledHopKey, T> entry : map.entrySet())
			if(entry.getKey() == key)
				return entry.getValue();
		return null;
	}

	public record DpDynamicInvocationReceipt(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, DpEnumerationResult enumerationResult,
		FinalPlanCertificate finalPlanCertificate,
		String fingerprintBefore, String fingerprintAfter, NormalizedPlannerResult normalizedResult,
		PlacementEmissionReceipt emissionReceipt) {
		public DpDynamicInvocationReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(memoTable, "memoTable");
			Objects.requireNonNull(enumerationResult, "enumerationResult");
			Objects.requireNonNull(finalPlanCertificate, "finalPlanCertificate");
			Objects.requireNonNull(fingerprintBefore, "fingerprintBefore");
			Objects.requireNonNull(fingerprintAfter, "fingerprintAfter");
			Objects.requireNonNull(normalizedResult, "normalizedResult");
			Objects.requireNonNull(emissionReceipt, "emissionReceipt");
			if(normalizedResult.analysis() != analysis
				|| !PlacementEmissionTransaction.canonicalPlanHash(normalizedResult).equals(emissionReceipt.planHash()))
				throw new IllegalArgumentException("Dynamic DP normalized result and emission receipt differ");
			if(memoTable.analysis() != analysis
				|| finalPlanCertificate.analysis() != analysis
				|| finalPlanCertificate.memo() != memoTable
				|| enumerationResult.rewireSnapshot().analysis() != analysis
				|| enumerationResult.semanticBlock().context().analysis() != analysis)
				throw new IllegalArgumentException("Dynamic DP receipt producer identities differ");
			if(!normalizedResult.objectiveCertificate().equals(
				"objectiveBits=" + finalPlanCertificate.objectiveCostBits())
				|| finalPlanCertificate.terms().size() != normalizedResult.selectedStates().size())
				throw new IllegalArgumentException("Dynamic DP final forest certificate differs from emission");
			if(!fingerprintBefore.equals(fingerprintAfter)
				|| !analysis.analysisFingerprint().equals(fingerprintBefore))
				throw new IllegalArgumentException("Supplied dynamic analysis changed during planning");
		}
	}
	private static final int MAX_ENUM_INPUTS = 20; // guard against 2^n blowups and shift overflow
	private static final boolean ENABLE_TRANSIENT_FAMILY_SCORING_TRACE = false;
	private record SelectedDpState(ExecType execType, FederatedOutput output, FType fType,
		boolean derivedFedFout, PlacementState exactState,
		FederatedPlannerDpMemoTable.FedPlan retainedPlan,
		CandidateSelectionReceipt directCandidateSelection,
		List<RelocationChoiceReceipt> directRelocationChoices) {
		private SelectedDpState {
			directRelocationChoices = List.copyOf(directRelocationChoices);
		}
		private SelectedDpState(ExecType execType, FederatedOutput output, FType fType,
			boolean derivedFedFout, PlacementState exactState) {
			this(execType, output, fType, derivedFedFout, exactState, null, null, List.of());
		}
	}
	private enum RewriteMutationMode { APPLY, CAPTURE_ONLY }

	private static final class OrdinaryComponentId {
		private final PlacementAnalysis analysis;
		private final String fingerprint;
		private final int ordinal;
		private final List<CompiledHopKey> members;

		private OrdinaryComponentId(PlacementAnalysis analysis, String fingerprint, int ordinal,
			List<CompiledHopKey> members) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
			this.ordinal = ordinal;
			this.members = List.copyOf(members);
		}

		@Override public String toString() { return ordinal + ":" + members; }
	}

	private record OrdinaryComponentTopology(
		Map<CompiledHopKey, Set<CompiledHopKey>> outgoing,
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected) { }

	/**
	 * Exact-join search observes a child plan only through the value boundary that
	 * the parent consumes.  Keep occurrence identity (not value equality) because
	 * the join maps are identity-authoritative over one frozen PlacementAnalysis.
	 */
	private record ExactJoinChildBoundary(CompiledHopKey occurrence,
		FederatedOutput output, FType fType) {
		@Override
		public boolean equals(Object obj) {
			return obj instanceof ExactJoinChildBoundary other
				&& occurrence == other.occurrence && output == other.output && fType == other.fType;
		}

		@Override
		public int hashCode() {
			return 31 * (31 * System.identityHashCode(occurrence) + output.hashCode())
				+ Objects.hashCode(fType);
		}
	}

	/**
	 * Search-behavior identity for raw/recompile variants of one exact occurrence.
	 * Carrier Hop identity and captured cost are deliberately absent: domains are
	 * already ordered by the original DP preference/cost, and neither value changes
	 * legality once direct authority and ordered child boundaries are identical.
	 */
	private record ExactJoinSemanticKey(PlacementState state, boolean derivedFedFout,
		CandidateSelectionReceipt candidate, List<RelocationChoiceReceipt> relocations,
		List<ExactJoinChildBoundary> children) {
		private ExactJoinSemanticKey {
			relocations = List.copyOf(relocations);
			children = List.copyOf(children);
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ExactJoinSemanticKey other
				&& state == other.state && derivedFedFout == other.derivedFedFout
				&& Objects.equals(candidate, other.candidate)
				&& relocations.equals(other.relocations) && children.equals(other.children);
		}

		@Override
		public int hashCode() {
			int result = System.identityHashCode(state);
			result = 31 * result + Boolean.hashCode(derivedFedFout);
			result = 31 * result + Objects.hashCode(candidate);
			result = 31 * result + relocations.hashCode();
			return 31 * result + children.hashCode();
		}
	}

	private static final class OwnerComponentIndex {
		private final PlacementAnalysis analysis;
		private final String fingerprint;
		private final Map<CompiledHopKey, OrdinaryComponentId> owners = new IdentityHashMap<>();
		private final Map<CompiledHopKey, PlacementAnalysis.HopOccurrenceProjection> occurrences =
			new IdentityHashMap<>();

		private OwnerComponentIndex(PlacementAnalysis analysis, List<OrdinaryComponentId> components) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.fingerprint = analysis.analysisFingerprint();
			for(var occurrence : analysis.occurrences())
				occurrences.put(occurrence.key(), occurrence);
			for(OrdinaryComponentId component : components) {
				if(component.analysis != analysis || !component.fingerprint.equals(fingerprint))
					throw new IllegalStateException("DP component authority belongs to another analysis");
				for(CompiledHopKey key : component.members) {
					if(occurrences.get(key) == null || analysis.hop(key).orElse(null) != occurrences.get(key).hop())
						throw new IllegalStateException("DP component member lacks exact analysis occurrence: " + key);
					if(owners.put(key, component) != null)
						throw new IllegalStateException("DP component member has multiple owners: " + key);
				}
			}
		}

		private OrdinaryComponentId owner(CompiledHopKey key) { return owners.get(key); }
		private PlacementAnalysis.HopOccurrenceProjection occurrence(CompiledHopKey key) {
			return occurrences.get(key);
		}
	}

	private record SelectedChildResolution(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable.FedPlan canonicalOwnerPlan,
		PlacementAnalysis.HopOccurrenceProjection occurrence, CompiledHopKey key,
		PlacementState state, boolean derivedFedFout, FederatedOutput selectedOutput) { }

	private record ExactComponentJoin(List<FederatedPlannerDpMemoTable.FedPlan> roots,
		Map<CompiledHopKey,SelectedDpState> selections, double objective, String signature) {
		private ExactComponentJoin {
			roots = List.copyOf(roots);
			selections = Collections.unmodifiableMap(new IdentityHashMap<>(selections));
			if(!Double.isFinite(objective) || objective < 0)
				throw new IllegalArgumentException("DP exact component join objective is invalid");
		}
	}

	/** Bounded proof trail for an unsatisfiable exact-component join. */
	private static final class ExactJoinSearchDiagnostics {
		private int deepestIndex = -1;
		private CompiledHopKey deepestKey;
		private long visitedPrefixes;
		private final Map<String,Long> rejectionCounts = new LinkedHashMap<>();
		private final Map<String,List<String>> rejectionExamples = new LinkedHashMap<>();
		private final List<String> deepestExamples = new ArrayList<>();

		private void visit(int index, CompiledHopKey key) {
			visitedPrefixes++;
			if(index > deepestIndex) {
				deepestIndex = index;
				deepestKey = key;
				deepestExamples.clear();
			}
		}

		private void reject(int index, CompiledHopKey key, String category, String detail) {
			reject(index, key, category, () -> detail);
		}

		private void reject(int index, CompiledHopKey key, String category,
			java.util.function.Supplier<String> detailSupplier) {
			rejectionCounts.merge(category, 1L, Long::sum);
			List<String> categoryExamples = rejectionExamples.computeIfAbsent(
				category, ignored -> new ArrayList<>());
			boolean retainCategoryExample = categoryExamples.size() < 2;
			boolean retainDeepestExample = index >= deepestIndex && deepestExamples.size() < 8;
			if(!retainCategoryExample && !retainDeepestExample)
				return;
			String detail = Objects.requireNonNull(detailSupplier, "detailSupplier").get();
			if(retainCategoryExample)
				categoryExamples.add(index + ":" + compactOccurrence(key) + ':' + detail);
			if(!retainDeepestExample)
				return;
			if(index > deepestIndex) {
				deepestIndex = index;
				deepestKey = key;
				deepestExamples.clear();
			}
			deepestExamples.add(category + ':' + detail);
		}

		private String summary(
			Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
			return "visited=" + visitedPrefixes + ",deepest=" + deepestIndex + ':'
				+ compactOccurrence(deepestKey) + ",counts=" + rejectionCounts
				+ ",examples=" + deepestExamples + ",categoryExamples=" + rejectionExamples
				+ ",domain="
				+ (deepestKey == null || domains.get(deepestKey) == null ? "-"
					: domains.get(deepestKey).stream().map(
						FederatedPlannerDpFedCostBased::compactPlanArm).toList());
		}
	}

	private static final class ExactTraversalEdge {
		private final PlacementAnalysis analysis;
		private final String planningFingerprint;
		private final OrdinaryComponentId source;
		private final FederatedPlannerDpMemoTable.FedPlan parentPlan;
		private final PlacementAnalysis.HopOccurrenceProjection parentOccurrence;
		private final int childOrdinal;
		private final Pair<Long, FederatedOutput> declaration;
		private final SelectedChildResolution child;
		private final OrdinaryComponentId owner;
		private boolean consumed;

		private ExactTraversalEdge(OrdinaryComponentId source,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			PlacementAnalysis.HopOccurrenceProjection parentOccurrence, int childOrdinal,
			Pair<Long, FederatedOutput> declaration, SelectedChildResolution child,
			OrdinaryComponentId owner) {
			this.analysis = source.analysis;
			this.planningFingerprint = source.fingerprint;
			this.source = source;
			this.parentPlan = parentPlan;
			this.parentOccurrence = parentOccurrence;
			this.childOrdinal = childOrdinal;
			this.declaration = declaration;
			this.child = child;
			this.owner = owner;
		}
	}

	private static final class ExactTraversalRoot {
		private final PlacementAnalysis analysis;
		private final String planningFingerprint;
		private final OrdinaryComponentId source;
		private final FederatedPlannerDpMemoTable.FedPlan seedPlan;
		private final FederatedPlannerDpMemoTable.FedPlan effectivePlan;
		private final PlacementAnalysis.HopOccurrenceProjection occurrence;
		private final PlacementState state;
		private final boolean derivedFedFout;
		private boolean consumed;

		private ExactTraversalRoot(OrdinaryComponentId source,
			FederatedPlannerDpMemoTable.FedPlan seedPlan,
			FederatedPlannerDpMemoTable.FedPlan effectivePlan,
			PlacementAnalysis.HopOccurrenceProjection occurrence) {
			this.analysis = source.analysis;
			this.planningFingerprint = source.fingerprint;
			this.source = source;
			this.seedPlan = seedPlan;
			this.effectivePlan = effectivePlan;
			this.occurrence = occurrence;
			this.state = Objects.requireNonNull(effectivePlan.getSelectedPlacementState(),
				"DP scheduled root has no exact state");
			this.derivedFedFout = effectivePlan.isDerivedFedFout();
		}
	}

	private static final class OwnerCaptureReceipt {
		private final PlacementAnalysis analysis;
		private final String fingerprint;
		private final OrdinaryComponentId owner;
		private final PlacementAnalysis.HopOccurrenceProjection occurrence;
		private final FederatedPlannerDpMemoTable.FedPlan plan;
		private final PlacementState state;
		private final boolean derivedFedFout;
		private final ExactTraversalRoot root;
		private final ExactTraversalEdge edge;
		private OwnerCaptureReceipt(OrdinaryComponentId owner, PlacementAnalysis.HopOccurrenceProjection occurrence,
			FederatedPlannerDpMemoTable.FedPlan plan, ExactTraversalRoot root, ExactTraversalEdge edge) {
			this.analysis = owner.analysis; this.fingerprint = owner.fingerprint; this.owner = owner;
			this.occurrence = occurrence; this.plan = plan; this.state = plan.getSelectedPlacementState();
			this.derivedFedFout = plan.isDerivedFedFout(); this.root = root; this.edge = edge;
		}
	}

	private static final class TraversalDependencyLedger {
		private final OwnerComponentIndex ownerIndex;
		private final Map<CompiledHopKey, SelectedDpState> boundaryLocks;
		private final List<ExactTraversalEdge> scheduled = new ArrayList<>();
		private final Set<CompiledHopKey> dependencies = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Set<CompiledHopKey> ownerCaptures = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<CompiledHopKey, OwnerCaptureReceipt> ownerReceipts = new IdentityHashMap<>();
		private final Set<CompiledHopKey> dependencyCaptures =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<CompiledHopKey, SelectedChildResolution> dependencySelections =
			new IdentityHashMap<>();
		private final Map<CompiledHopKey, SelectedDpState> componentJoinLocks =
			new IdentityHashMap<>();
		private final List<ExactTraversalRoot> roots = new ArrayList<>();
		private final List<String> scheduledOrder = new ArrayList<>();
		private final List<String> consumedOrder = new ArrayList<>();

		private TraversalDependencyLedger(OwnerComponentIndex ownerIndex,
			Map<CompiledHopKey, SelectedDpState> boundaryLocks) {
			this.ownerIndex = ownerIndex;
			// Keep the invocation's committed selection map as a live authority.  A
			// disconnected component completed earlier in this pass is a hard runtime
			// boundary for every component completed later.  Snapshotting this map let a
			// later TRead/function component re-simulate an already captured TWrite with
			// the opposite output, so the decision map and exact executable arm diverged.
			this.boundaryLocks = Objects.requireNonNull(boundaryLocks, "boundaryLocks");
		}

		private SelectedDpState boundaryLock(CompiledHopKey key) {
			return boundaryLocks.get(key);
		}

		private SelectedDpState selectionLock(CompiledHopKey key) {
			SelectedDpState boundary = boundaryLock(key);
			if(boundary != null)
				return boundary;
			SelectedDpState joined = componentJoinLocks.get(key);
			if(joined != null)
				return joined;
			SelectedChildResolution dependency = dependencySelections.get(key);
			FederatedPlannerDpMemoTable.FedPlan ownerPlan = dependency == null
				? null : dependency.canonicalOwnerPlan();
			return ownerPlan == null ? null : new SelectedDpState(
				dependency.state().execType(), dependency.state().output(), dependency.state().fType(),
				dependency.derivedFedFout(), dependency.state(), ownerPlan,
				ownerPlan.getDirectCandidateSelection(), ownerPlan.getDirectRelocationChoices());
		}

		private Map<CompiledHopKey,SelectedDpState> exactJoinLocks(OrdinaryComponentId component) {
			Map<CompiledHopKey,SelectedDpState> locks = new IdentityHashMap<>(boundaryLocks);
			// A foreign owner component may already have been solved before this
			// component is visited.  Its exact arm is then a physical child boundary,
			// even though it is not an externally supplied invocation lock.  Omitting
			// these committed joins let a parent select (for example) a FED/FOUT child
			// while the already-solved owner remained CP/LOUT; scheduling detected the
			// contradiction only after optimization.
			locks.putAll(componentJoinLocks);
			for(Map.Entry<CompiledHopKey,SelectedChildResolution> entry : dependencySelections.entrySet()) {
				// A parent component owns only the value boundary consumed from its foreign
				// child. The child owner must remain free to select its own internal exact
				// arm, so do not turn an incoming boundary preference into an exact self-lock.
				if(ownerIndex.owner(entry.getKey()) == component)
					continue;
				SelectedChildResolution dependency = entry.getValue();
				FederatedPlannerDpMemoTable.FedPlan ownerPlan = dependency.canonicalOwnerPlan();
				locks.put(entry.getKey(), new SelectedDpState(dependency.state().execType(),
					dependency.state().output(), dependency.state().fType(), dependency.derivedFedFout(),
					dependency.state(), ownerPlan, ownerPlan.getDirectCandidateSelection(),
					ownerPlan.getDirectRelocationChoices()));
			}
			return locks;
		}

		private void installExactJoin(OrdinaryComponentId component,
			Map<CompiledHopKey,SelectedDpState> selections) {
			for(Map.Entry<CompiledHopKey,SelectedDpState> entry : selections.entrySet()) {
				if(ownerIndex.owner(entry.getKey()) != component)
					continue;
				SelectedDpState previous = componentJoinLocks.putIfAbsent(entry.getKey(), entry.getValue());
				if(previous != null && !sameExactAuthority(previous, entry.getValue()))
					throw new IllegalStateException("DP component join lock changed exact authority: "
						+ entry.getKey());
			}
		}

		private Map<Long,FederatedOutput> componentOutputLocks(OrdinaryComponentId component,
			FederatedPlannerDpMemoTable memoTable, Map<Long,FederatedOutput> existingLocks) {
			Map<Long,FederatedOutput> locks = new LinkedHashMap<>();
			// The global decision map is a cost-search proposal, not an execution lock for
			// occurrences that have not been captured yet.  Copying it wholesale made a
			// disconnected component retain an internally chosen FED/FOUT parent even when
			// an already-captured physical child was locked to CP/LOUT.  The traversal then
			// silently replaced the declared FOUT child with the boundary lock, leaving the
			// parent's exact candidate row inconsistent with the executable input.
			//
			// Only exact, already-captured occurrence selections are hard boundaries here.
			// Interior/global output proposals are passed as the simulation base below and
			// may be revised to form a coherent parent/child forest.
			for(Map.Entry<CompiledHopKey,SelectedDpState> entry : boundaryLocks.entrySet()) {
				PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(entry.getKey());
				if(occurrence == null)
					throw new IllegalStateException("DP boundary lock lacks exact occurrence authority: "
						+ entry.getKey());
				long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
				FederatedOutput required = entry.getValue().output();
				FederatedOutput previous = locks.putIfAbsent(originalHopId, required);
				if(previous != null && previous != required)
					throw new IllegalStateException("DP exact boundary occurrences disagree on output: hop="
						+ originalHopId + " previous=" + previous + " required=" + required);
				FederatedOutput proposed = existingLocks.get(originalHopId);
				if(proposed != null && proposed != required)
					throw new IllegalStateException("DP global output proposal differs from captured boundary: hop="
						+ originalHopId + " proposed=" + proposed + " required=" + required);
			}
			for(Map.Entry<CompiledHopKey,SelectedChildResolution> entry : dependencySelections.entrySet()) {
				if(ownerIndex.owner(entry.getKey()) != component)
					continue;
				long originalHopId = memoTable.resolveOriginalHopId(entry.getValue().occurrence().hop().getHopID());
				FederatedOutput required = entry.getValue().state().output();
				FederatedOutput previous = locks.putIfAbsent(originalHopId, required);
				if(previous != null && previous != required)
					throw new IllegalStateException("DP component dependency output lock conflicts: "
						+ entry.getKey());
			}
			for(Map.Entry<CompiledHopKey,SelectedDpState> entry : componentJoinLocks.entrySet()) {
				if(ownerIndex.owner(entry.getKey()) != component)
					continue;
				PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(entry.getKey());
				long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
				FederatedOutput previous = locks.putIfAbsent(originalHopId, entry.getValue().output());
				if(previous != null && previous != entry.getValue().output())
					throw new IllegalStateException("DP component join output locks conflict: " + entry.getKey());
			}
			return locks;
		}

		private Map<Long,FederatedOutput> committedOutputLocks(
			FederatedPlannerDpMemoTable memoTable) {
			Map<Long,FederatedOutput> locks = new LinkedHashMap<>();
			for(Map.Entry<CompiledHopKey,SelectedDpState> entry : boundaryLocks.entrySet())
				putCommittedOutputLock(locks, memoTable, entry.getKey(), entry.getValue().output());
			for(Map.Entry<CompiledHopKey,SelectedDpState> entry : componentJoinLocks.entrySet())
				putCommittedOutputLock(locks, memoTable, entry.getKey(), entry.getValue().output());
			for(Map.Entry<CompiledHopKey,SelectedChildResolution> entry : dependencySelections.entrySet())
				putCommittedOutputLock(locks, memoTable, entry.getKey(), entry.getValue().state().output());
			return locks;
		}

		private void putCommittedOutputLock(Map<Long,FederatedOutput> locks,
			FederatedPlannerDpMemoTable memoTable, CompiledHopKey key, FederatedOutput output) {
			PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(key);
			if(occurrence == null)
				throw new IllegalStateException("DP committed output lock lacks exact occurrence: " + key);
			long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
			FederatedOutput previous = locks.putIfAbsent(originalHopId, output);
			if(previous != null && previous != output)
				throw new IllegalStateException("DP committed occurrences disagree on output: hop="
					+ originalHopId + " previous=" + previous + " required=" + output);
		}

		private FederatedPlannerDpMemoTable.FedPlan exactBoundaryPlan(
			FederatedPlannerDpMemoTable memoTable, PlacementAnalysis.HopOccurrenceProjection occurrence,
			long preferredHopId, Map<Long, FederatedOutput> outputDecisions) {
			if(occurrence == null || occurrence != ownerIndex.occurrence(occurrence.key()))
				throw new IllegalStateException("DP boundary lock lookup lacks exact occurrence authority");
			SelectedDpState lock = selectionLock(occurrence.key());
			if(lock == null)
				return null;
			// The component join already retained the exact production arm. Prefer that
			// object-owned authority before looking it up again through a carrier Hop ID:
			// function/recompile clones can share IDs while only one carrier owns the
			// selected occurrence. This is not a fallback; all state, candidate,
			// relocation, occurrence, and child-decision invariants remain exact.
			FederatedPlannerDpMemoTable.FedPlan retained = lock.retainedPlan();
			if(retained != null
				&& memoTable.requirePlanCarrierOccurrence(retained.getHopRef()) == occurrence
				&& retained.getSelectedPlacementState() == lock.exactState()
				&& retained.isDerivedFedFout() == lock.derivedFedFout()
				&& matchesDirectAuthority(retained, lock.directCandidateSelection(),
					lock.directRelocationChoices())
				&& isCompatibleWithChildDecisions(memoTable, retained, outputDecisions))
				return retained;
			FederatedPlannerDpMemoTable.FedPlan best = exactBoundaryPlanForHop(
				memoTable, occurrence, preferredHopId, lock, outputDecisions);
			if(best != null)
				return best;
			for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm :
				memoTable.getAllExactPlanVariantsForOccurrence(occurrence)) {
				FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
				if(candidate.getSelectedPlacementState() != lock.exactState()
					|| candidate.isDerivedFedFout() != lock.derivedFedFout()
					|| !matchesDirectAuthority(candidate, lock.directCandidateSelection(),
						lock.directRelocationChoices())
					|| !isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
					continue;
				if(best == null || candidate.getCumulativeCost() < best.getCumulativeCost()
					|| candidate.getCumulativeCost() == best.getCumulativeCost()
						&& candidate.getFedOutType() == FederatedOutput.LOUT
						&& best.getFedOutType() == FederatedOutput.FOUT)
					best = candidate;
			}
			if(best == null)
				throw new IllegalStateException("DP boundary lock has no exact memo arm: "
					+ occurrence.key() + " lock=" + exactStateDiagnostic(lock)
					+ " retained=" + compactPlanArm(retained)
					+ " retainedOccurrence=" + (retained == null ? "-"
						: memoTable.requirePlanCarrierOccurrence(retained.getHopRef()).key())
					+ " retainedChildCompatible=" + (retained != null
						&& isCompatibleWithChildDecisions(memoTable, retained, outputDecisions))
					+ " outputDecisions=" + outputDecisions
					+ " arms=" + memoTable.getAllExactPlanVariantsForOccurrence(occurrence).stream()
						.map(arm -> compactPlanArm(arm.plan())).toList());
			return best;
		}

		private FederatedPlannerDpMemoTable.FedPlan exactBoundaryPlanForHop(
			FederatedPlannerDpMemoTable memoTable, PlacementAnalysis.HopOccurrenceProjection occurrence,
			long hopId, SelectedDpState lock, Map<Long, FederatedOutput> outputDecisions) {
			FederatedPlannerDpMemoTable.FedPlan best = null;
			for(FederatedOutput output : List.of(lock.output(), FederatedOutput.LOUT, FederatedOutput.FOUT)) {
				FederatedPlannerDpMemoTable.FedPlanVariants variants =
					memoTable.getFedPlanVariants(Pair.of(hopId, output));
				if(variants == null || variants.isEmpty())
					continue;
				for(FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
					if(candidate == null || candidate.getSelectedPlacementState() != lock.exactState()
						|| candidate.isDerivedFedFout() != lock.derivedFedFout()
						|| !matchesDirectAuthority(candidate, lock.directCandidateSelection(),
							lock.directRelocationChoices())
						|| memoTable.requirePlanCarrierOccurrence(candidate.getHopRef()) != occurrence
						|| !isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
						continue;
					if(best == null || candidate.getCumulativeCost() < best.getCumulativeCost()
						|| candidate.getCumulativeCost() == best.getCumulativeCost()
							&& candidate.getFedOutType() == FederatedOutput.LOUT
							&& best.getFedOutType() == FederatedOutput.FOUT)
						best = candidate;
				}
			}
			return best;
		}

		private void verifyBoundaryLock(SelectedChildResolution child) {
			SelectedDpState lock = selectionLock(child.key());
			if(lock == null)
				return;
			if(child.state() != lock.exactState() || child.derivedFedFout() != lock.derivedFedFout())
				throw new IllegalStateException("DP boundary lock selected incompatible child arm: "
					+ child.key());
		}

		private void validateAuthority(OrdinaryComponentId source) {
			if(source == null || source.analysis != ownerIndex.analysis
				|| !source.fingerprint.equals(ownerIndex.fingerprint)
				|| !ownerIndex.fingerprint.equals(ownerIndex.analysis.analysisFingerprint()))
				throw new IllegalStateException("DP traversal ledger authority is stale or foreign");
		}

		private ExactTraversalRoot scheduleRoot(OrdinaryComponentId source,
			FederatedPlannerDpMemoTable.FedPlan seed,
			FederatedPlannerDpMemoTable.FedPlan effective,
			PlacementAnalysis.HopOccurrenceProjection occurrence) {
			validateAuthority(source);
			if(occurrence == null || occurrence != ownerIndex.occurrence(occurrence.key())
				|| ownerIndex.owner(occurrence.key()) != source)
				throw new IllegalStateException("DP scheduled root lacks exact owner authority");
			ExactTraversalRoot root = new ExactTraversalRoot(source, seed, effective, occurrence);
			roots.add(root);
			scheduledOrder.add(canonicalRoot(root));
			return root;
		}

		private void consumeRoot(ExactTraversalRoot root,
			FederatedPlannerDpMemoTable.FedPlan seed,
			FederatedPlannerDpMemoTable.FedPlan effective,
			PlacementAnalysis.HopOccurrenceProjection occurrence) {
			if(root == null || !roots.contains(root))
				throw new IllegalStateException("DP traversal used an unscheduled exact root");
			validateAuthority(root.source);
			if(root.seedPlan != seed || root.effectivePlan != effective || root.occurrence != occurrence
				|| root.analysis != ownerIndex.analysis
				|| !root.planningFingerprint.equals(ownerIndex.fingerprint)
				|| root.state != effective.getSelectedPlacementState()
				|| root.derivedFedFout != effective.isDerivedFedFout())
				throw new IllegalStateException("DP exact root authority differs: " + occurrence.key());
			if(root.consumed)
				throw new IllegalStateException("DP exact root was over-consumed: " + occurrence.key());
			root.consumed = true;
			consumedOrder.add(canonicalRoot(root));
		}

		private ExactTraversalEdge schedule(OrdinaryComponentId source,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			PlacementAnalysis.HopOccurrenceProjection parentOccurrence, int childOrdinal,
			Pair<Long, FederatedOutput> declaration, SelectedChildResolution child) {
			OrdinaryComponentId owner = ownerIndex.owner(child.key());
			validateAuthority(source);
			if(parentOccurrence == null || child.occurrence() == null
				|| parentOccurrence != ownerIndex.occurrence(parentOccurrence.key())
				|| child.occurrence() != ownerIndex.occurrence(child.key()))
				throw new IllegalStateException("DP scheduled edge contains foreign exact occurrence authority");
			ExactTraversalEdge edge = new ExactTraversalEdge(source, parentPlan, parentOccurrence,
				childOrdinal, declaration, child, owner);
			for(ExactTraversalEdge existing : scheduled)
				if(existing.source == source && existing.parentPlan == parentPlan
					&& existing.childOrdinal == childOrdinal)
					throw new IllegalStateException("DP duplicate exact traversal edge: " + source
						+ " parent=" + parentOccurrence.key() + " ordinal=" + childOrdinal);
			scheduled.add(edge);
			scheduledOrder.add(canonicalEdge(edge));
			if(owner != null && owner != source) {
				dependencies.add(child.key());
				SelectedChildResolution previous = dependencySelections.putIfAbsent(child.key(), child);
				if(previous != null && (previous.occurrence() != child.occurrence()
					|| !sameConsumableBoundary(selectedState(previous.canonicalOwnerPlan()),
						selectedState(child.canonicalOwnerPlan()))))
					throw new IllegalStateException("DP dependency has disagreeing exact selected arms: "
						+ child.key());
				matchDependencyReceipt(child.key(), child);
			}
			return edge;
		}

		private FederatedPlannerDpMemoTable.FedPlan dependencyPlan(OrdinaryComponentId owner,
			CompiledHopKey key) {
			SelectedChildResolution selection = dependencySelections.get(key);
			// The incoming edge retains the raw/recompiled carrier in ExactTraversalEdge so
			// its declaration can be consumed exactly.  Component search, however, chooses
			// the occurrence's executable owner/lowering arm.  Treating a zero-child virtual
			// TRead alias as the owner root drops the real TWrite dependency and makes the
			// selected forest differ from the plan that lowering actually consumes.
			return selection != null && ownerIndex.owner(key) == owner
				? selection.canonicalOwnerPlan() : null;
		}

		private SelectedChildResolution declareForeignDependency(OrdinaryComponentId source,
			SelectedChildResolution root, FederatedPlannerDpMemoTable memoTable,
			Map<Long,FederatedOutput> outputDecisions) {
			SelectedChildResolution current = root;
			if(current == null)
				return null;
			current = refineDependencyOwnerPlan(current, memoTable, outputDecisions);
			FederatedPlannerDpMemoTable.FedPlan plan = current.canonicalOwnerPlan();
			// The source component owns the direct child boundary selected by its local
			// parent/child recurrence. It does not own the foreign child's descendants:
			// recursively registering that subtree hard-locked TRead/TWrite and function
			// families before their owner component could form a legal exact forest.
			// Resolve and refine only this boundary root. The child owner will traverse
			// and select its internal descendants under the declared boundary state.
			Map<Long,FederatedOutput> resolved = resolveOutputDecisionsWithLocks(
				memoTable, plan, outputDecisions, committedOutputLocks(memoTable));
			outputDecisions.putAll(resolved);
			// Child-output closure can select a different retained candidate for the
			// same producer state. Rebind only that local arm; the occurrence output and
			// physical placement remain fixed by the incoming boundary/committed lock.
			current = refineDependencyOwnerPlan(current, memoTable, outputDecisions);
			plan = current.canonicalOwnerPlan();
			OrdinaryComponentId currentOwner = ownerIndex.owner(current.key());
			if(currentOwner != null && currentOwner != source) {
				dependencies.add(current.key());
				SelectedChildResolution previous = dependencySelections.putIfAbsent(current.key(), current);
				if(previous != null && (previous.occurrence() != current.occurrence()
					|| !sameConsumableBoundary(selectedState(previous.canonicalOwnerPlan()),
						selectedState(current.canonicalOwnerPlan()))))
					throw new IllegalStateException("DP direct dependency has disagreeing value boundaries: "
						+ current.key() + " previous=" + describePlanArm(previous.canonicalOwnerPlan())
						+ " required=" + describePlanArm(current.canonicalOwnerPlan()));
				matchDependencyReceipt(current.key(), previous == null ? current : previous);
			}
			return current;
		}

		private SelectedChildResolution refineDependencyOwnerPlan(SelectedChildResolution requested,
			FederatedPlannerDpMemoTable memoTable, Map<Long,FederatedOutput> outputDecisions) {
			SelectedDpState boundary = boundaryLock(requested.key());
			SelectedDpState committed = componentJoinLocks.get(requested.key());
			SelectedDpState requestedAuthority = boundary != null ? boundary
				: committed != null ? committed : selectedState(requested.canonicalOwnerPlan());
			FederatedPlannerDpMemoTable.FedPlan best = null;
			for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm :
				memoTable.getAllExactPlanVariantsForOccurrence(requested.occurrence())) {
				FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
				// A foreign parent owns only the child's physical boundary state. The
				// child's owner is free to choose the locally cheapest candidate/relocation
				// arm with that same state whose own children satisfy already committed
				// boundaries. Locking the foreign parent's captured candidate identity here
				// incorrectly rejected legal CP/LOUT variants that consume a FOUT child.
				if(boundary != null
					? !sameExactAuthority(requestedAuthority, selectedState(candidate))
					: !sameConsumableBoundary(requestedAuthority, selectedState(candidate)))
					continue;
				if(!isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
					continue;
				boolean compatible = true;
				for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge :
					candidate.getExactChildPlanEdges()) {
					SelectedDpState childLock = selectionLock(edge.occurrence());
					if(childLock != null && !sameConsumableBoundary(
						childLock, selectedState(edge.selectedPlan()))) {
						compatible = false;
						break;
					}
				}
				if(compatible && (best == null
					|| candidate.getCumulativeCost() < best.getCumulativeCost()
					|| candidate.getCumulativeCost() == best.getCumulativeCost()
						&& exactPlanStableSignature(candidate)
							.compareTo(exactPlanStableSignature(best)) < 0))
					best = candidate;
			}
			if(best == null)
				return requested;
			if(boundary == null && committed != null)
				componentJoinLocks.put(requested.key(), selectedState(best));
			return new SelectedChildResolution(requested.plan(), best, requested.occurrence(),
				requested.key(), best.getSelectedPlacementState(), best.isDerivedFedFout(),
				requested.selectedOutput());
		}

		private ExactTraversalEdge consume(OrdinaryComponentId source,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			PlacementAnalysis.HopOccurrenceProjection parentOccurrence, int childOrdinal,
			Pair<Long, FederatedOutput> declaration, SelectedChildResolution actual) {
			for(ExactTraversalEdge edge : scheduled) {
				if(edge.source != source || edge.parentPlan != parentPlan || edge.childOrdinal != childOrdinal)
					continue;
				if(edge.parentOccurrence != parentOccurrence || edge.declaration != declaration
					|| edge.analysis != ownerIndex.analysis
					|| !edge.planningFingerprint.equals(ownerIndex.fingerprint)
					|| edge.child.plan() != actual.plan() || edge.child.occurrence() != actual.occurrence()
					|| edge.child.key() != actual.key() || edge.child.state() != actual.state()
					|| edge.child.derivedFedFout() != actual.derivedFedFout()
					|| edge.child.selectedOutput() != actual.selectedOutput())
					throw new IllegalStateException("DP exact traversal edge authority differs: source="
						+ source + " parent=" + parentOccurrence.key() + " ordinal=" + childOrdinal
						+ " scheduledPlan={" + describePlanArm(edge.child.plan()) + "}"
						+ " actualPlan={" + describePlanArm(actual.plan()) + "}"
						+ " scheduledOwner={" + describePlanArm(edge.child.canonicalOwnerPlan()) + "}"
						+ " actualOwner={" + describePlanArm(actual.canonicalOwnerPlan()) + "}"
						+ " scheduledOccurrence=" + edge.child.occurrence().key()
						+ " actualOccurrence=" + actual.occurrence().key());
				if(edge.consumed)
					throw new IllegalStateException("DP exact traversal edge was over-consumed: source="
						+ source + " parent=" + parentOccurrence.key() + " ordinal=" + childOrdinal);
				edge.consumed = true;
				consumedOrder.add(canonicalEdge(edge));
				return edge;
			}
			throw new IllegalStateException("DP traversal used an unscheduled exact edge: source="
				+ source + " parent=" + parentOccurrence.key() + " ordinal=" + childOrdinal);
		}

		private SelectedChildResolution scheduledChild(OrdinaryComponentId source,
			FederatedPlannerDpMemoTable.FedPlan parentPlan, int childOrdinal,
			Pair<Long,FederatedOutput> declaration) {
			for(ExactTraversalEdge edge : scheduled)
				if(edge.source == source && edge.parentPlan == parentPlan
					&& edge.childOrdinal == childOrdinal && edge.declaration == declaration) {
					if(edge.consumed)
						throw new IllegalStateException("DP exact traversal requested an already consumed child edge");
					return edge.child;
				}
			throw new IllegalStateException("DP exact traversal requested an unscheduled child edge: source="
				+ source + " parent=" + parentPlan.getHopID() + " ordinal=" + childOrdinal);
		}

		private boolean accountOwner(OrdinaryComponentId current, CompiledHopKey key,
			PlacementAnalysis.HopOccurrenceProjection occurrence, FederatedPlannerDpMemoTable.FedPlan plan,
			ExactTraversalRoot root, ExactTraversalEdge edge, boolean exactVisitedRevisit) {
			validateAuthority(current);
			if(ownerIndex.owner(key) != current || occurrence != ownerIndex.occurrence(key))
				throw new IllegalStateException("DP occurrence captured by a non-owner component: " + key);
			if(root == null && edge == null || (root != null && edge != null) ||
				(root != null && !root.consumed) || (edge != null && !edge.consumed))
				throw new IllegalStateException("DP owner entry lacks exact consumed incoming authority: " + key);
			OwnerCaptureReceipt prior = ownerReceipts.get(key);
			if(prior != null) {
				if(!exactVisitedRevisit || !ownerCaptures.contains(key) || prior.owner != current
					|| prior.analysis != ownerIndex.analysis
					|| !prior.fingerprint.equals(ownerIndex.fingerprint)
					|| prior.occurrence != occurrence || prior.plan != plan
					|| prior.state != plan.getSelectedPlacementState()
					|| prior.derivedFedFout != plan.isDerivedFedFout())
					throw new IllegalStateException("DP occurrence has illegal duplicate owner capture: " + key
						+ "|exactVisited=" + exactVisitedRevisit
						+ "|priorPlan=" + describePlanArm(prior.plan)
						+ "|currentPlan=" + describePlanArm(plan)
						+ "|priorIncoming=" + describeIncoming(prior.root, prior.edge)
						+ "|currentIncoming=" + describeIncoming(root, edge));
				return false;
			}
			OwnerCaptureReceipt receipt = new OwnerCaptureReceipt(current, occurrence, plan, root, edge);
			ownerReceipts.put(key, receipt); ownerCaptures.add(key);
			if(dependencies.contains(key)) {
				SelectedChildResolution dependency = dependencySelections.get(key);
				if(dependency == null)
					throw new IllegalStateException("DP dependency lacks exact selected arm: " + key);
				matchDependencyReceipt(key, dependency);
			}
			return true;
		}

		private static String describePlanArm(FederatedPlannerDpMemoTable.FedPlan plan) {
			if(plan == null)
				return "null";
			CandidateSelectionReceipt candidate = plan.getDirectCandidateSelection();
			return "id=" + System.identityHashCode(plan)
				+ ",carrier=" + plan.getHopID() + ':' + plan.getHopRef().getOpString()
				+ ",cost=" + plan.getCumulativeCost()
				+ ",state=" + plan.getSelectedPlacementState()
				+ ",derived=" + plan.isDerivedFedFout()
				+ ",children=" + plan.getChildFedPlans()
				+ ",candidate=" + (candidate == null ? "null" : candidate.normalizedSignature())
				+ ",relocations=" + plan.getDirectRelocationChoices().stream()
					.map(RelocationChoiceReceipt::normalizedSignature).toList();
		}

		private static String describeIncoming(ExactTraversalRoot root, ExactTraversalEdge edge) {
			if(root != null)
				return "root:" + root.occurrence.key();
			if(edge != null)
				return "edge:" + edge.parentOccurrence.key() + '#' + edge.childOrdinal
					+ "->" + edge.child.key();
			return "none";
		}

		private void matchDependencyReceipt(CompiledHopKey key, SelectedChildResolution dependency) {
			OwnerCaptureReceipt receipt = ownerReceipts.get(key);
			if(receipt == null)
				return;
			OrdinaryComponentId owner = ownerIndex.owner(key);
			if(receipt.analysis != ownerIndex.analysis
				|| !receipt.fingerprint.equals(ownerIndex.fingerprint)
				|| receipt.owner != owner || receipt.occurrence != dependency.occurrence()
				|| !sameConsumableBoundary(selectedState(receipt.plan),
					selectedState(dependency.canonicalOwnerPlan())))
				throw new IllegalStateException("DP dependency owner receipt differs: " + key
					+ "|receiptOwner=" + receipt.owner + "|expectedOwner=" + owner
					+ "|receiptPlan=" + describePlanArm(receipt.plan)
					+ "|dependencyPlan=" + describePlanArm(dependency.canonicalOwnerPlan())
					+ "|receiptState=" + receipt.state + "|dependencyState=" + dependency.state()
					+ "|receiptDerived=" + receipt.derivedFedFout
					+ "|dependencyDerived=" + dependency.derivedFedFout()
					+ "|receiptIncoming=" + describeIncoming(receipt.root, receipt.edge));
			dependencyCaptures.add(key);
		}

		private void requireComponentClosed(OrdinaryComponentId component) {
			validateAuthority(component);
			for(ExactTraversalRoot root : roots)
				if(root.source == component && !root.consumed)
					throw new IllegalStateException("DP scheduled exact root was not consumed: "
						+ root.occurrence.key());
			for(ExactTraversalEdge edge : scheduled)
				if(edge.source == component && !edge.consumed)
					throw new IllegalStateException("DP scheduled exact traversal edge was not consumed: source="
						+ component + " parent=" + edge.parentOccurrence.key()
						+ " ordinal=" + edge.childOrdinal);
			String rootPrefix = "R|" + component.ordinal + "|";
			String edgePrefix = "E|" + component.ordinal + "|";
			List<String> expected = scheduledOrder.stream()
				.filter(value -> value.startsWith(rootPrefix) || value.startsWith(edgePrefix)).toList();
			List<String> actual = consumedOrder.stream()
				.filter(value -> value.startsWith(rootPrefix) || value.startsWith(edgePrefix)).toList();
			if(!expected.equals(actual))
				throw new IllegalStateException("DP component exact traversal multiset differs: expected="
					+ expected + " actual=" + actual);
		}

		private void requireInvocationClosed() {
			for(ExactTraversalRoot root : roots)
				if(!root.consumed)
					throw new IllegalStateException("DP exact traversal ledger contains an unconsumed root");
			for(ExactTraversalEdge edge : scheduled)
				if(!edge.consumed)
					throw new IllegalStateException("DP exact traversal ledger contains an unconsumed edge");
			if(!scheduledOrder.equals(consumedOrder))
				throw new IllegalStateException("DP scheduled and consumed exact traversal multisets differ: scheduled="
					+ scheduledOrder + " consumed=" + consumedOrder);
			if(!dependencyCaptures.equals(dependencies))
				throw new IllegalStateException("DP traversal dependency lacks exact owner receipt: expected="
					+ dependencies + " actual=" + dependencyCaptures);
		}

		private static String canonicalRoot(ExactTraversalRoot root) {
			return "R|" + root.source.ordinal + "|" + root.occurrence.key() + "|"
				+ root.state + "|" + root.derivedFedFout;
		}

		private static String canonicalEdge(ExactTraversalEdge edge) {
			return "E|" + edge.source.ordinal + "|" + edge.parentOccurrence.key() + "|"
				+ edge.childOrdinal + "|" + edge.declaration.getKey() + ":" + edge.declaration.getValue()
				+ "|" + edge.child.key() + "|" + edge.child.selectedOutput() + "|"
				+ edge.child.state() + "|" + edge.child.derivedFedFout();
		}
	}

	private record CaptureTraversalContext(OrdinaryComponentId component,
		TraversalDependencyLedger ledger, ExactTraversalRoot incomingRoot,
		ExactTraversalEdge incomingEdge) { }

	private static SelectedDpState selectedState(FederatedPlannerDpMemoTable.FedPlan plan) {
		PlacementState exact = Objects.requireNonNull(plan.getSelectedPlacementState(),
			"DP FedPlan has no exact analysis-owned placement-state carrier");
		if(exact.execType() != plan.getExecType() || exact.output() != plan.getFedOutType())
			throw new IllegalStateException("DP FedPlan tuple differs from its exact placement-state carrier");
		return new SelectedDpState(exact.execType(), exact.output(), exact.fType(),
			plan.isDerivedFedFout(), exact, plan, plan.getDirectCandidateSelection(),
			plan.getDirectRelocationChoices());
	}

	private static String exactStateDiagnostic(SelectedDpState state) {
		if(state == null)
			return "-";
		return state.exactState().normalizedSignature() + ":derived=" + state.derivedFedFout()
			+ ":candidate=" + (state.directCandidateSelection() == null ? "-"
				: state.directCandidateSelection().normalizedSignature())
			+ ":relocations=" + state.directRelocationChoices().stream()
				.map(RelocationChoiceReceipt::normalizedSignature).toList();
	}

	private static void coalesceSelectedState(Map<CompiledHopKey, SelectedDpState> selected,
		CompiledHopKey key, SelectedDpState proposed) {
		SelectedDpState previous = selected.putIfAbsent(key, proposed);
		if(previous != null && (previous.exactState() != proposed.exactState()
			|| previous.derivedFedFout() != proposed.derivedFedFout()
			|| previous.directCandidateSelection() != null
				&& proposed.directCandidateSelection() != null
				&& !previous.directCandidateSelection().equals(proposed.directCandidateSelection())
			|| !previous.directRelocationChoices().isEmpty()
				&& !proposed.directRelocationChoices().isEmpty()
				&& !previous.directRelocationChoices().equals(proposed.directRelocationChoices())))
			throw new IllegalStateException("DP occurrence has disagreeing exact selections: " + key
				+ "|previous=" + previous + "|proposed=" + proposed);
		if(previous != null && previous.directRelocationChoices().isEmpty()
			&& !proposed.directRelocationChoices().isEmpty())
			selected.put(key, proposed);
		else if(previous != null && previous.directCandidateSelection() == null
			&& proposed.directCandidateSelection() != null)
			selected.put(key, proposed);
		else if(previous != null && previous.retainedPlan() != proposed.retainedPlan()
			&& sameExactPlanBoundaryAuthority(previous.retainedPlan(), proposed.retainedPlan())
			&& preferRetainedArm(proposed.retainedPlan(), previous.retainedPlan()))
			selected.put(key, proposed);
	}

	private static boolean sameExactPlanBoundaryAuthority(
		FederatedPlannerDpMemoTable.FedPlan left,
		FederatedPlannerDpMemoTable.FedPlan right) {
		if(left == null || right == null)
			return left == right;
		List<FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> leftEdges =
			left.getExactChildPlanEdges();
		List<FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> rightEdges =
			right.getExactChildPlanEdges();
		if(leftEdges.size() != rightEdges.size())
			return false;
		if(leftEdges.isEmpty())
			return left.getChildFedPlans().equals(right.getChildFedPlans());
		for(int i = 0; i < leftEdges.size(); i++) {
			FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge leftEdge = leftEdges.get(i);
			FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge rightEdge = rightEdges.get(i);
			if(leftEdge.occurrence() != rightEdge.occurrence()
				|| leftEdge.output() != rightEdge.output()
				|| !samePhysicalAuthority(selectedState(leftEdge.selectedPlan()),
					selectedState(rightEdge.selectedPlan())))
				return false;
		}
		return true;
	}

	private static boolean preferRetainedArm(FederatedPlannerDpMemoTable.FedPlan candidate,
		FederatedPlannerDpMemoTable.FedPlan incumbent) {
		if(candidate == null)
			return false;
		if(incumbent == null)
			return true;
		int byCost = Double.compare(candidate.getCumulativeCost(), incumbent.getCumulativeCost());
		if(byCost != 0)
			return byCost < 0;
		String candidateSignature = candidate.getChildFedPlans().stream()
			.map(edge -> edge.getValue().name()).reduce((a, b) -> a + ',' + b).orElse("");
		String incumbentSignature = incumbent.getChildFedPlans().stream()
			.map(edge -> edge.getValue().name()).reduce((a, b) -> a + ',' + b).orElse("");
		return candidateSignature.compareTo(incumbentSignature) < 0;
	}

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		PlacementAnalysis analysis = prog.requirePlacementAnalysisAuthority();
		rewriteProgram(prog, fgraph, fcallSizes, analysis);
	}

	@Override
	public DpInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis) {
		return emitProgram(prog, selectProgram(prog, fgraph, fcallSizes, analysis));
	}

	/** Selects the complete normalized DP plan without mutating the supplied program. */
	public DpPreEmissionSelection selectProgram(DMLProgram prog, FunctionCallGraph fgraph,
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
		return selectProgramWithEnumeration(prog, analysis, memoTable, enumerationResult, fingerprintBefore);
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
		return emitProgram(prog,
			selectProgramWithEnumeration(prog, analysis, memoTable, enumerationResult, fingerprintBefore));
	}

	private DpPreEmissionSelection selectProgramWithEnumeration(DMLProgram prog, PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, DpEnumerationResult enumerationResult,
		String fingerprintBefore) {
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = enumerationResult.optimalPlan();
		DpPlacementAdapter.ExactSelection exactSelection =
			new DpPlacementAdapter().selectExact(analysis, memoTable, optimalPlan);

		Map<Long, FederatedOutput> outputDecisions = computeOutputDecisions(memoTable, optimalPlan);
		Map<Long, ConflictEntry> rewriteConflictCheckMap =
			collectConflictsSingleBFS(memoTable, optimalPlan, outputDecisions);
		Set<CompiledHopKey> visitedPlanHops = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<Long, LocalMaterializeRequest> localMaterializeRequests = new LinkedHashMap<>();
		Map<CompiledHopKey, SelectedDpState> selectedStates = new IdentityHashMap<>();
		List<AppliedPlanReceipt> appliedPlans = new ArrayList<>();
		List<AdditionalRootInvocationReceipt> additionalRootInvocations = new ArrayList<>();
		List<DeferredOutputDecisionReceipt> deferredOutputDecisionReceipts = new ArrayList<>();
		List<DisconnectedCompletionReceipt> disconnectedCompletionReceipts = new ArrayList<>();

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
			CompiledHopKey rootKey = memoTable.requirePlanCarrierOccurrence(seed.getHopRef()).key();
			boolean alreadyVisited = visitedPlanHops.contains(rootKey);
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

		List<CompiledHopKey> initialAppliedTraversalKeys =
			selectedStates.keySet().stream().sorted().toList();
		Set<CompiledHopKey> aggregateExplicitClosure =
			Collections.newSetFromMap(new IdentityHashMap<>());
		aggregateExplicitClosure.addAll(initialAppliedTraversalKeys);
		applyDeferredOutputDecisionStates(
			memoTable, outputDecisions, rewriteConflictCheckMap, localMaterializeRequests, selectedStates,
			Collections.unmodifiableSet(aggregateExplicitClosure), deferredOutputDecisionReceipts);
		Set<CompiledHopKey> supersededPreCompletionSet = completeDisconnectedDecisionAuthority(
			analysis, memoTable, outputDecisions, visitedPlanHops,
			fTypeMap, rewriteConflictCheckMap, localMaterializeRequests, selectedStates, appliedPlans,
			disconnectedCompletionReceipts);
		List<CompiledHopKey> supersededPreCompletionKeys =
			reclassifySupersededPreCompletionAuthority(initialAppliedTraversalKeys,
				deferredOutputDecisionReceipts, supersededPreCompletionSet, selectedStates);
		List<CompiledHopKey> appliedTraversalKeys = initialAppliedTraversalKeys.stream()
			.filter(key -> !supersededPreCompletionSet.contains(key)).toList();
		reconcileDeferredOutputDecisionReceipts(
			memoTable, selectedStates, deferredOutputDecisionReceipts);
		FinalPlanCertificate finalPlanCertificate = certifyFinalPlanForest(analysis, memoTable, selectedStates);
		NormalizedPlannerResult normalized = normalizeDpSelection(
			analysis, selectedStates, exactSelection, finalPlanCertificate, null);
		String fingerprintAfter = analysis.analysisFingerprint();
		DpSemanticConsumptionReceipt semanticConsumption = DpSemanticConsumptionReceipt.consumed(
			enumerationResult, analysis, exactSelection, fingerprintBefore, fingerprintAfter);
		return new DpPreEmissionSelection(analysis, memoTable, optimalPlan, exactSelection,
			finalPlanCertificate, semanticConsumption, appliedPlans,
			additionalRootInvocations, appliedTraversalKeys, deferredOutputDecisionReceipts,
			supersededPreCompletionKeys,
			disconnectedCompletionReceipts, fingerprintBefore, fingerprintAfter, normalized);
	}

	private static DpInvocationReceipt emitProgram(DMLProgram prog, DpPreEmissionSelection selection) {
		PlacementEmissionReceipt emission = PlacementEmissionTransaction.emit(prog,
			selection.normalizedResult(), PlacementEmissionTransaction.FailureInjector.none());
		int noOps = (int) selection.additionalRootInvocations().stream()
			.filter(invocation -> invocation.disposition() == AdditionalRootDisposition.ALREADY_VISITED).count();
		InvocationCounters counters = new InvocationCounters(1, 1, 1, selection.appliedPlans().size(),
			selection.additionalRootInvocations().size(), noOps, 0, 0, 0, 0, 0, 0);
		String fingerprintAfter = selection.analysis().analysisFingerprint();
		if(!selection.analysisFingerprintAfterSelection().equals(fingerprintAfter))
			throw new IllegalStateException("DP emission changed immutable analysis authority");
		return new DpInvocationReceipt(selection.analysis(), selection.memo(),
			selection.legacyOptimalPlan(), selection.exactSelection(), selection.finalPlanCertificate(),
			selection.semanticConsumption(), selection.appliedPlans(), selection.additionalRootInvocations(),
			selection.appliedTraversalKeys(), selection.deferredOutputDecisionReceipts(),
			selection.supersededPreCompletionKeys(),
			selection.disconnectedCompletionReceipts(), counters, selection.analysisFingerprintBefore(),
			fingerprintAfter, selection.normalizedResult(), emission);
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
		Set<CompiledHopKey> visitedPlanHops = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<Long, LocalMaterializeRequest> localMaterializeRequests = new LinkedHashMap<>();
		Map<CompiledHopKey, SelectedDpState> selectedStates = new IdentityHashMap<>();

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
			memoTable, outputDecisions, rewriteConflictCheckMap, localMaterializeRequests, selectedStates, null, null);
		completeDisconnectedDecisionAuthority(analysis, memoTable, outputDecisions, visitedPlanHops,
			fTypeMap, rewriteConflictCheckMap, localMaterializeRequests, selectedStates, null, null);
		DpPlacementAdapter.ExactSelection exactSelection =
			new DpPlacementAdapter().selectExact(analysis, memoTable, optimalPlan);
		NormalizedPlannerResult previous = PlacementEmissionTransaction.currentNormalizedResult(prog);
		FinalPlanCertificate finalPlanCertificate = certifyFinalPlanForest(analysis, memoTable, selectedStates);
		NormalizedPlannerResult normalized = normalizeDpSelection(
			analysis, selectedStates, exactSelection, finalPlanCertificate, previous);
		if(finalPlanCertificate.terms().size() != normalized.selectedStates().size()
			|| finalPlanCertificate.terms().stream().anyMatch(term ->
				identityMapValue(normalized.selectedStates(), term.occurrence()) == null))
			throw new IllegalStateException(
				"Dynamic DP final certificate does not cover the complete emitted decision forest");
		PlacementEmissionReceipt emission = PlacementEmissionTransaction.replaceCompleteProgram(prog,
			normalized, PlacementEmissionTransaction.FailureInjector.none());
		String fingerprintAfter = analysis.analysisFingerprint();
		return new DpDynamicInvocationReceipt(
			analysis, memoTable, enumerationResult, finalPlanCertificate,
			fingerprintBefore, fingerprintAfter, normalized, emission);
	}

	private static NormalizedPlannerResult normalizeDpSelection(PlacementAnalysis analysis,
		Map<CompiledHopKey, SelectedDpState> selected, DpPlacementAdapter.ExactSelection exactSelection) {
		return normalizeDpSelection(analysis, selected, exactSelection,
			certifyFinalPlanForest(analysis, exactSelection.memo(), selected), null);
	}

	private static List<CompiledHopKey> ordinaryDecisionKeys(PlacementAnalysis analysis) {
		return analysis.graph().decisionNodes().stream()
			.filter(node -> node.kind() != NodeKind.FUNCTION_INPUT
				&& node.kind() != NodeKind.FUNCTION_OUTPUT)
			.map(Node::key)
			.sorted()
			.toList();
	}

	private static OrdinaryComponentTopology ordinaryComponentTopology(PlacementAnalysis analysis,
		List<CompiledHopKey> ordinaryKeys) {
		Map<CompiledHopKey, Set<CompiledHopKey>> outgoing = new LinkedHashMap<>();
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected = new LinkedHashMap<>();
		Map<CompiledHopKey, CompiledHopKey> canonicalKeys = new HashMap<>();
		for(CompiledHopKey key : ordinaryKeys) {
			CompiledHopKey previous = canonicalKeys.putIfAbsent(key, key);
			if(previous != null && previous != key)
				throw new IllegalStateException(
					"DP ordinary decision universe contains duplicate key identities: " + key);
			outgoing.put(key, Collections.newSetFromMap(new IdentityHashMap<>()));
			undirected.put(key, Collections.newSetFromMap(new IdentityHashMap<>()));
		}
		// The analysis-owned compiled graph is the stable component topology, except
		// for FEDERATED-source constructor arguments: those inputs build the source's
		// FederationMap but are deliberately not executable children of its fixed leaf
		// plan. Keeping such an edge makes an owner component impossible to traverse.
		for(PlacementAnalysis.CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			CompiledHopKey producerKey = canonicalKeys.get(edge.producer());
			CompiledHopKey consumerKey = canonicalKeys.get(edge.consumer());
			if(producerKey == null || consumerKey == null)
				continue;
			Hop consumer = analysis.hop(consumerKey).orElseThrow();
			if(consumer instanceof DataOp
				&& ((DataOp) consumer).getOp() == Types.OpOpData.FEDERATED)
				continue;
			addComponentEdge(producerKey, consumerKey, outgoing, undirected);
		}
		// Logical transient and function-input facts are frozen execution dependencies,
		// not merely diagnostics. A legality repair must reopen the same weak component
		// that disconnected completion will subsequently solve.
		for(PlacementAnalysis.LogicalTransientInputFact edge :
			analysis.logicalTransientInputsInCanonicalOrder()) {
			CompiledHopKey sourceKey = canonicalKeys.get(edge.sourceWrite());
			CompiledHopKey targetKey = canonicalKeys.get(edge.targetRead());
			if(sourceKey != null && targetKey != null)
				addComponentEdge(sourceKey, targetKey, outgoing, undirected);
		}
		for(PlacementAnalysis.LogicalFunctionInputFact edge :
			analysis.logicalFunctionInputsInCanonicalOrder()) {
			CompiledHopKey sourceKey = canonicalKeys.get(edge.sourceArgument());
			CompiledHopKey targetKey = canonicalKeys.get(edge.targetRead());
			if(sourceKey != null && targetKey != null)
				addComponentEdge(sourceKey, targetKey, outgoing, undirected);
		}
		addComponentOwnershipEdges(analysis, undirected, canonicalKeys);
		return new OrdinaryComponentTopology(outgoing, undirected);
	}

	private Set<CompiledHopKey> completeDisconnectedDecisionAuthority(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, Map<Long, FederatedOutput> outputDecisions,
		Set<CompiledHopKey> visitedPlanHops, Map<Long, FType> fTypeMap,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests,
		Map<CompiledHopKey, SelectedDpState> selectedStates,
		List<AppliedPlanReceipt> appliedPlans,
		List<DisconnectedCompletionReceipt> disconnectedCompletionReceipts) {
		Set<CompiledHopKey> legalityRepairKeys = exactLegalityRepairClosure(
			analysis, memoTable, selectedStates);
		Set<CompiledHopKey> supersededPreCompletionKeys =
			Collections.newSetFromMap(new IdentityHashMap<>());
		if(!legalityRepairKeys.isEmpty()) {
			// The aggregate/local DP traversal is a preference, not authority to emit an
			// illegal exact tuple. Re-open the violated family's complete canonical owner
			// component; unaffected components remain hard physical boundary locks.
			for(CompiledHopKey key : legalityRepairKeys) {
				if(selectedStates.containsKey(key))
					supersededPreCompletionKeys.add(key);
				PlacementAnalysis.HopOccurrenceProjection occurrence = analysis.occurrences().stream()
					.filter(candidate -> candidate.key() == key).findFirst().orElseThrow();
				long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
				selectedStates.remove(key);
				visitedPlanHops.remove(key);
				fTypeMap.remove(originalHopId);
			}
			FederatedPlannerTrace.logGlobal("DP-ExactLegality-Reopen",
				"componentCount=" + legalityRepairKeys.size()
					+ " supersededCount=" + supersededPreCompletionKeys.size()
					+ " members=" + boundedSummary(
					legalityRepairKeys.stream().sorted()
						.map(FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 24));
		}
		List<CompiledHopKey> ordinaryKeys = ordinaryDecisionKeys(analysis).stream()
			.filter(key -> !selectedStates.containsKey(key)).toList();
		OrdinaryComponentTopology topology = ordinaryComponentTopology(analysis, ordinaryKeys);
		Map<CompiledHopKey, Set<CompiledHopKey>> outgoing = topology.outgoing();
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected = topology.undirected();
		Set<CompiledHopKey> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
		List<OrdinaryComponentId> components = new ArrayList<>();
		for(int componentIndex = ordinaryKeys.size() - 1; componentIndex >= 0; componentIndex--) {
			CompiledHopKey first = ordinaryKeys.get(componentIndex);
			if(assigned.contains(first))
				continue;
			List<CompiledHopKey> component = collectWeakComponent(first, undirected);
			assigned.addAll(component);
			components.add(new OrdinaryComponentId(analysis, analysis.analysisFingerprint(),
				components.size(), component));
		}
		if(assigned.size() != ordinaryKeys.size())
			throw new IllegalStateException("DP frozen ordinary-component partition is incomplete");
		components = orderOrdinaryComponentsByExactDependencies(analysis, memoTable, components);
		OwnerComponentIndex ownerIndex = new OwnerComponentIndex(analysis, List.copyOf(components));
		if(ownerIndex.owners.size() != ordinaryKeys.size())
			throw new IllegalStateException("DP frozen owner index differs from the ordinary-decision universe");
		// The aggregate and explicit-root traversals above are the DP decision authority.
		// Completion only fills occurrences that those locally selected forests did not
		// reach; it must never clear and globally reselect already chosen occurrences.
		Set<CompiledHopKey> preCompletionKeys = Collections.newSetFromMap(new IdentityHashMap<>());
		preCompletionKeys.addAll(selectedStates.keySet());
		TraversalDependencyLedger ledger = new TraversalDependencyLedger(ownerIndex, selectedStates);
		for(OrdinaryComponentId component : components)
			completeDisconnectedComponent(analysis, memoTable, component,
				componentSinkRoots(component.members, outgoing), outputDecisions, visitedPlanHops, fTypeMap,
				rewriteConflictCheckMap, localMaterializeRequests, selectedStates, ownerIndex, ledger,
				preCompletionKeys, appliedPlans, disconnectedCompletionReceipts);
		ledger.requireInvocationClosed();
		boolean progressed;
		do {
			progressed = false;
			Map<CompiledHopKey, PlacementEmissionState> emissionStates = new IdentityHashMap<>();
			selectedStates.forEach((key, value) -> emissionStates.put(key,
				new PlacementEmissionState(value.exactState(), value.derivedFedFout())));
			for(var node : analysis.graph().decisionNodes()) {
				if(selectedStates.containsKey(node.key())
					|| node.kind() != NodeKind.FUNCTION_INPUT && node.kind() != NodeKind.FUNCTION_OUTPUT)
					continue;
				DpPlacementAdapter.SyntheticBoundaryReceipt receipt =
					DpPlacementAdapter.projectSyntheticBoundary(analysis, node, emissionStates);
				if(receipt == null)
					continue;
				PlacementEmissionState selected = receipt.selectedEmissionState();
				PlacementState exact = selected.placementState();
				coalesceSelectedState(selectedStates, node.key(), new SelectedDpState(
					exact.execType(), exact.output(), exact.fType(),
					selected.derivedFedFout(), exact, null, null, List.of()));
				progressed = true;
			}
		}
		while(progressed);
		return supersededPreCompletionKeys;
	}

	private static Set<CompiledHopKey> exactLegalityRepairClosure(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable,
		Map<CompiledHopKey, SelectedDpState> selectedStates) {
		Set<CompiledHopKey> repair = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints()) {
			if(!isExactComponentLegalityConstraint(constraint))
				continue;
			SelectedDpState left = selectedStates.get(constraint.left());
			SelectedDpState right = selectedStates.get(constraint.right());
			if(left != null && right != null && !NeutralPlacementGraph.constraintSatisfied(
				constraint, left.exactState(), right.exactState())) {
				boolean ordinaryOwner = false;
				if(!isSyntheticFunctionBoundary(analysis, constraint.left())) {
					repair.add(constraint.left());
					ordinaryOwner = true;
				}
				if(!isSyntheticFunctionBoundary(analysis, constraint.right())) {
					repair.add(constraint.right());
					ordinaryOwner = true;
				}
				if(!ordinaryOwner)
					throw new IllegalStateException(
						"DP exact legality violation has no ordinary function-boundary owner");
			}
		}
		Map<CompiledHopKey,List<SyntheticBoundaryIncident>> selectedSyntheticIncidents =
			new IdentityHashMap<>();
		for(Constraint constraint : analysis.graph().constraints()) {
			if(!isExactComponentLegalityConstraint(constraint))
				continue;
			SelectedDpState right = selectedStates.get(constraint.right());
			if(right != null && isSyntheticFunctionBoundary(analysis, constraint.left()))
				selectedSyntheticIncidents.computeIfAbsent(constraint.left(), ignored -> new ArrayList<>())
					.add(new SyntheticBoundaryIncident(constraint, right.exactState()));
			SelectedDpState left = selectedStates.get(constraint.left());
			if(left != null && isSyntheticFunctionBoundary(analysis, constraint.right()))
				selectedSyntheticIncidents.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
					.add(new SyntheticBoundaryIncident(constraint, left.exactState()));
		}
		for(Map.Entry<CompiledHopKey,List<SyntheticBoundaryIncident>> entry :
			selectedSyntheticIncidents.entrySet()) {
			Node boundary = analysis.graph().node(entry.getKey()).orElseThrow();
			if(syntheticBoundaryHasFeasibleState(entry.getKey(), boundary, entry.getValue()))
				continue;
			for(SyntheticBoundaryIncident incident : entry.getValue()) {
				Constraint constraint = incident.constraint();
				CompiledHopKey neighbor = constraint.left() == entry.getKey()
					? constraint.right() : constraint.left();
				if(selectedStates.containsKey(neighbor))
					repair.add(neighbor);
			}
		}
		if(repair.isEmpty())
			return repair;

		boolean progressed;
		do {
			progressed = false;
			for(Constraint constraint : analysis.graph().constraints()) {
				if(!isExactComponentLegalityConstraint(constraint))
					continue;
				if(repair.contains(constraint.left()) && selectedStates.containsKey(constraint.right()))
					if(!isSyntheticFunctionBoundary(analysis, constraint.right()))
						progressed |= repair.add(constraint.right());
				if(repair.contains(constraint.right()) && selectedStates.containsKey(constraint.left()))
					if(!isSyntheticFunctionBoundary(analysis, constraint.left()))
						progressed |= repair.add(constraint.left());
			}
			for(PlacementAnalysis.CompiledInputEdgeFact edge :
				analysis.compiledInputEdgesInCanonicalOrder())
				if(repair.contains(edge.producer()) && selectedStates.containsKey(edge.consumer()))
					progressed |= repair.add(edge.consumer());
			for(PlacementAnalysis.LogicalTransientInputFact edge :
				analysis.logicalTransientInputsInCanonicalOrder())
				if(repair.contains(edge.sourceWrite()) && selectedStates.containsKey(edge.targetRead()))
					progressed |= repair.add(edge.targetRead());
			for(PlacementAnalysis.LogicalFunctionInputFact edge :
				analysis.logicalFunctionInputsInCanonicalOrder())
				if(repair.contains(edge.sourceArgument()) && selectedStates.containsKey(edge.targetRead()))
					progressed |= repair.add(edge.targetRead());

			Set<Long> repairOrigins = repair.stream().map(key -> analysis.hop(key).orElseThrow())
				.map(Hop::getHopID).map(memoTable::resolveOriginalHopId)
				.collect(java.util.stream.Collectors.toSet());
			for(CompiledHopKey key : selectedStates.keySet()) {
				long originalHopId = memoTable.resolveOriginalHopId(
					analysis.hop(key).orElseThrow().getHopID());
				if(repairOrigins.contains(originalHopId))
					progressed |= repair.add(key);
			}
		}
		while(progressed);
		// Reopening only the violated endpoints leaves their already-selected producers,
		// consumers, transient family, or function family as hard boundary locks. The
		// exact join then has no freedom to form a coherent forest. Expand through the
		// exact topology used by component completion so every member of an affected
		// owner component is a candidate again; foreign components remain immutable.
		List<CompiledHopKey> ordinaryKeys = ordinaryDecisionKeys(analysis);
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected =
			ordinaryComponentTopology(analysis, ordinaryKeys).undirected();
		Set<CompiledHopKey> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey seed : repair) {
			if(!undirected.containsKey(seed))
				throw new IllegalStateException(
					"DP exact legality violation has no ordinary component owner: " + seed);
			if(!expanded.contains(seed))
				expanded.addAll(collectWeakComponent(seed, undirected));
		}
		return expanded;
	}

	/**
	 * Execute component joins from consumers to their exact physical children.  A
	 * parent component declares the selected child arm in the traversal ledger; the
	 * child's owner component can then optimize under that requirement.  The former
	 * source-key order sometimes solved a caller source first and later discovered
	 * that a function-call component required the opposite LOUT/FOUT arm.
	 *
	 * <p>The dependency graph is formed from every retained exact arm, so ordering is
	 * independent of the eventual variant choice.  Cyclic component dependencies are
	 * kept in their stable discovery order here; transient/loop legality edges already
	 * co-locate ordinary cycles in one component.  A remaining cross-component cycle
	 * will still be rejected by the exact ledger rather than repaired at runtime.</p>
	 */
	private static List<OrdinaryComponentId> orderOrdinaryComponentsByExactDependencies(
		PlacementAnalysis analysis, FederatedPlannerDpMemoTable memoTable,
		List<OrdinaryComponentId> discovered) {
		Map<CompiledHopKey,OrdinaryComponentId> owner = new IdentityHashMap<>();
		Map<CompiledHopKey,PlacementAnalysis.HopOccurrenceProjection> occurrences =
			new IdentityHashMap<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences())
			occurrences.put(occurrence.key(), occurrence);
		Map<OrdinaryComponentId,Set<OrdinaryComponentId>> outgoing = new IdentityHashMap<>();
		Map<OrdinaryComponentId,Integer> indegree = new IdentityHashMap<>();
		for(OrdinaryComponentId component : discovered) {
			outgoing.put(component, Collections.newSetFromMap(new IdentityHashMap<>()));
			indegree.put(component, 0);
			for(CompiledHopKey member : component.members)
				owner.put(member, component);
		}
		for(OrdinaryComponentId component : discovered)
			for(CompiledHopKey member : component.members) {
				PlacementAnalysis.HopOccurrenceProjection occurrence = occurrences.get(member);
				if(occurrence == null)
					throw new IllegalStateException(
						"DP component dependency member lacks exact occurrence: " + member);
				for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm :
					memoTable.getAllExactPlanVariantsForOccurrence(occurrence))
					for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge :
						arm.plan().getExactChildPlanEdges()) {
						OrdinaryComponentId child = owner.get(edge.occurrence());
						if(child != null && child != component && outgoing.get(component).add(child))
							indegree.put(child, indegree.get(child) + 1);
					}
			}
		Comparator<OrdinaryComponentId> canonical = Comparator.comparing(
			component -> component.members.get(0));
		java.util.PriorityQueue<OrdinaryComponentId> ready = new java.util.PriorityQueue<>(canonical);
		for(OrdinaryComponentId component : discovered)
			if(indegree.get(component) == 0)
				ready.add(component);
		List<OrdinaryComponentId> ordered = new ArrayList<>();
		while(!ready.isEmpty()) {
			OrdinaryComponentId next = ready.remove();
			ordered.add(next);
			List<OrdinaryComponentId> children = new ArrayList<>(outgoing.get(next));
			children.sort(canonical);
			for(OrdinaryComponentId child : children) {
				int remaining = indegree.get(child) - 1;
				indegree.put(child, remaining);
				if(remaining == 0)
					ready.add(child);
			}
		}
		if(ordered.size() != discovered.size()) {
			Set<OrdinaryComponentId> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
			emitted.addAll(ordered);
			List<OrdinaryComponentId> cyclic = discovered.stream()
				.filter(component -> !emitted.contains(component)).sorted(canonical).toList();
			ordered.addAll(cyclic);
		}
		List<OrdinaryComponentId> renumbered = new ArrayList<>(ordered.size());
		for(int ordinal = 0; ordinal < ordered.size(); ordinal++)
			renumbered.add(new OrdinaryComponentId(analysis, analysis.analysisFingerprint(), ordinal,
				ordered.get(ordinal).members));
		return List.copyOf(renumbered);
	}

	private static void addComponentEdge(CompiledHopKey producer, CompiledHopKey consumer,
		Map<CompiledHopKey, Set<CompiledHopKey>> outgoing,
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected) {
		if(!outgoing.containsKey(producer) || !outgoing.containsKey(consumer))
			return;
		outgoing.get(producer).add(consumer);
		undirected.get(producer).add(consumer);
		undirected.get(consumer).add(producer);
	}

	private static boolean isExactComponentLegalityConstraint(Constraint constraint) {
		return constraint.kind() == ConstraintKind.SAME_PLACEMENT
			|| constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
			|| constraint.kind() == ConstraintKind.SAME_FTYPE
			|| constraint.kind() == ConstraintKind.CONJUNCTIVE;
	}

	private static void addComponentOwnershipEdge(CompiledHopKey left, CompiledHopKey right,
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected) {
		if(!undirected.containsKey(left) || !undirected.containsKey(right))
			return;
		undirected.get(left).add(right);
		undirected.get(right).add(left);
	}

	/**
	 * Project graph-declared legality through non-emitting function-boundary variables.
	 * Every exact formal occurrence attached to one synthetic boundary participates in
	 * one legality hyperedge even though the boundary itself has no ordinary DP memo arm.
	 * This only co-locates a legality join; local DP recurrence costs and domain order
	 * remain the selection authority inside the resulting component.
	 */
	private static void addComponentOwnershipEdges(PlacementAnalysis analysis,
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected,
		Map<CompiledHopKey, CompiledHopKey> canonicalKeys) {
		Map<CompiledHopKey, Set<CompiledHopKey>> ordinaryMembersBySynthetic = new IdentityHashMap<>();
		for(Constraint constraint : analysis.graph().constraints()) {
			if(!isExactComponentLegalityConstraint(constraint))
				continue;
			CompiledHopKey leftKey = canonicalKeys.get(constraint.left());
			CompiledHopKey rightKey = canonicalKeys.get(constraint.right());
			boolean leftOrdinary = leftKey != null;
			boolean rightOrdinary = rightKey != null;
			if(leftOrdinary && rightOrdinary) {
				addComponentOwnershipEdge(leftKey, rightKey, undirected);
				continue;
			}
			if(leftOrdinary && isSyntheticFunctionBoundary(analysis, constraint.right()))
				ordinaryMembersBySynthetic.computeIfAbsent(constraint.right(), ignored ->
					Collections.newSetFromMap(new IdentityHashMap<>())).add(leftKey);
			if(rightOrdinary && isSyntheticFunctionBoundary(analysis, constraint.left()))
				ordinaryMembersBySynthetic.computeIfAbsent(constraint.left(), ignored ->
					Collections.newSetFromMap(new IdentityHashMap<>())).add(rightKey);
		}
		for(Set<CompiledHopKey> members : ordinaryMembersBySynthetic.values()) {
			CompiledHopKey first = members.stream().sorted().findFirst().orElse(null);
			if(first == null)
				continue;
			for(CompiledHopKey member : members)
				if(member != first)
					addComponentOwnershipEdge(first, member, undirected);
		}
	}

	private static boolean isSyntheticFunctionBoundary(PlacementAnalysis analysis, CompiledHopKey key) {
		Node node = analysis.graph().node(key).orElse(null);
		// Unknown builtin signatures create diagnostic-only <ABSENT> boundary nodes.
		// They intentionally have no legal alternatives and no emitted authority, so
		// requiring the exact join to assign them would reject every otherwise legal
		// caller plan.  Only graph-owned, selectable boundaries can couple ordinary
		// occurrences into a global legality component.
		return node != null && node.emittedWork() && !node.legalAlternatives().isEmpty()
			&& (node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT);
	}

	private static List<CompiledHopKey> collectWeakComponent(CompiledHopKey first,
		Map<CompiledHopKey, Set<CompiledHopKey>> undirected) {
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Queue<CompiledHopKey> queue = new ArrayDeque<>();
		queue.add(first);
		while(!queue.isEmpty()) {
			CompiledHopKey key = queue.remove();
			if(!seen.add(key))
				continue;
			List<CompiledHopKey> neighbors = new ArrayList<>(undirected.getOrDefault(key, Set.of()));
			Collections.sort(neighbors);
			queue.addAll(neighbors);
		}
		List<CompiledHopKey> component = new ArrayList<>(seen);
		Collections.sort(component);
		return component;
	}

	private static List<CompiledHopKey> componentSinkRoots(List<CompiledHopKey> component,
		Map<CompiledHopKey, Set<CompiledHopKey>> outgoing) {
		Set<CompiledHopKey> universe = new HashSet<>();
		universe.addAll(component);
		Map<CompiledHopKey, Integer> index = new HashMap<>();
		Map<CompiledHopKey, Integer> lowLink = new HashMap<>();
		Set<CompiledHopKey> onStack = new HashSet<>();
		ArrayDeque<CompiledHopKey> stack = new ArrayDeque<>();
		List<List<CompiledHopKey>> sccs = new ArrayList<>();
		int[] nextIndex = {0};
		for(CompiledHopKey key : component)
			if(!index.containsKey(key))
				collectStrongComponent(key, universe, outgoing, index, lowLink, onStack, stack, nextIndex, sccs);
		Map<CompiledHopKey, Integer> owner = new HashMap<>();
		for(int i = 0; i < sccs.size(); i++)
			for(CompiledHopKey key : sccs.get(i))
				owner.put(key, i);
		boolean[] sink = new boolean[sccs.size()];
		java.util.Arrays.fill(sink, true);
		for(CompiledHopKey producer : component)
			for(CompiledHopKey consumer : outgoing.getOrDefault(producer, Set.of()))
				if(universe.contains(consumer) && !owner.get(producer).equals(owner.get(consumer)))
					sink[owner.get(producer)] = false;
		List<CompiledHopKey> roots = new ArrayList<>();
		for(int i = 0; i < sccs.size(); i++)
			if(sink[i])
				roots.addAll(sccs.get(i));
		Collections.sort(roots);
		if(roots.isEmpty())
			throw new IllegalStateException("DP disconnected component has no sink roots: " + component);
		return roots;
	}

	private static void collectStrongComponent(CompiledHopKey key, Set<CompiledHopKey> universe,
		Map<CompiledHopKey, Set<CompiledHopKey>> outgoing, Map<CompiledHopKey, Integer> index,
		Map<CompiledHopKey, Integer> lowLink, Set<CompiledHopKey> onStack, ArrayDeque<CompiledHopKey> stack,
		int[] nextIndex, List<List<CompiledHopKey>> sccs) {
		index.put(key, nextIndex[0]);
		lowLink.put(key, nextIndex[0]++);
		stack.push(key);
		onStack.add(key);
		List<CompiledHopKey> children = new ArrayList<>(outgoing.getOrDefault(key, Set.of()));
		Collections.sort(children);
		for(CompiledHopKey child : children) {
			if(!universe.contains(child))
				continue;
			if(!index.containsKey(child)) {
				collectStrongComponent(child, universe, outgoing, index, lowLink, onStack, stack, nextIndex, sccs);
				lowLink.put(key, Math.min(lowLink.get(key), lowLink.get(child)));
			}
			else if(onStack.contains(child))
				lowLink.put(key, Math.min(lowLink.get(key), index.get(child)));
		}
		if(!lowLink.get(key).equals(index.get(key)))
			return;
		List<CompiledHopKey> scc = new ArrayList<>();
		CompiledHopKey member;
		do {
			member = stack.pop();
			onStack.remove(member);
			scc.add(member);
		} while(member != key);
		Collections.sort(scc);
		sccs.add(scc);
	}

	private static ExactComponentJoin selectMinimumCostCoherentComponent(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, OrdinaryComponentId componentId,
		List<CompiledHopKey> roots, OwnerComponentIndex ownerIndex,
		TraversalDependencyLedger ledger, Map<Long,FederatedOutput> outputDecisions) {
		Map<CompiledHopKey,SelectedDpState> fixed = ledger.exactJoinLocks(componentId);
		List<CompiledHopKey> ownBoundary = componentId.members.stream()
			.filter(ledger.boundaryLocks::containsKey).toList();
		List<CompiledHopKey> ownJoined = componentId.members.stream()
			.filter(ledger.componentJoinLocks::containsKey).toList();
		if(!ownBoundary.isEmpty() || !ownJoined.isEmpty())
			throw new IllegalStateException("DP exact join component is already hard-locked: boundary="
				+ boundedSummary(ownBoundary.stream().map(
					FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 16)
				+ " joined=" + boundedSummary(ownJoined.stream().map(
					FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 16));
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> preferredDomains =
			new IdentityHashMap<>();
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> legalityDomains =
			new IdentityHashMap<>();
		boolean preferredDomainsComplete = true;
		for(CompiledHopKey key : componentId.members) {
			PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(key);
			if(occurrence == null)
				throw new IllegalStateException("DP component member lacks exact occurrence: " + key);
			FederatedPlannerDpMemoTable.FedPlan dependency = ledger.dependencyPlan(componentId, key);
			SelectedDpState global = fixed.get(key);
			long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
			FederatedOutput preferredOutput = outputDecisions.get(originalHopId);
			Comparator<FederatedPlannerDpMemoTable.FedPlan> localDpOrder = Comparator
				.comparingInt((FederatedPlannerDpMemoTable.FedPlan plan) ->
					preferredOutput == null || plan.getFedOutType() == preferredOutput ? 0 : 1)
				.thenComparingDouble(FederatedPlannerDpMemoTable.FedPlan::getCumulativeCost)
				.thenComparing(exactPlanTieOrder());
			List<FederatedPlannerDpMemoTable.FedPlan> allOccurrenceArms =
				exactJoinDomainRepresentatives(memoTable
				.getAllExactPlanVariantsForOccurrence(occurrence).stream()
				.map(FederatedPlannerDpMemoTable.OccurrencePlanArm::plan)
				.filter(plan -> plan != null && plan.getSelectedPlacementState() != null)
				.sorted(localDpOrder)
				.toList());
			List<FederatedPlannerDpMemoTable.FedPlan> allArms = dependency != null
				? allOccurrenceArms.stream().filter(plan -> sameConsumableBoundary(
					selectedState(dependency), selectedState(plan))).toList()
				: global != null && global.retainedPlan() != null
					? List.of(global.retainedPlan()) : allOccurrenceArms;
			if(allArms.isEmpty())
				throw new IllegalStateException("DP memo omitted component member " + key
					+ " preferred=" + preferredOutput
					+ " dependency=" + compactPlanArm(dependency)
					+ " global=" + exactStateDiagnostic(global)
					+ " carriers=" + memoTable.describePlanCarriers(occurrence)
					+ " rawArms=" + memoTable.getAllExactPlanVariantsForOccurrence(occurrence).stream()
						.map(arm -> compactPlanArm(arm.plan()) + ":childrenCompatible="
							+ isCompatibleWithChildDecisions(memoTable, arm.plan(), outputDecisions))
						.toList());
			legalityDomains.put(key, allArms);
			List<FederatedPlannerDpMemoTable.FedPlan> preferredArms =
				dependency != null || global != null && global.retainedPlan() != null ? allArms : allArms.stream()
					// The existing DP conflict resolver remains the output-decision authority
					// whenever its decisions admit a graph-legal exact forest.
					.filter(plan -> preferredOutput == null || plan.getFedOutType() == preferredOutput)
					.filter(plan -> isCompatibleWithChildDecisions(memoTable, plan, outputDecisions))
					.toList();
			preferredDomains.put(key, preferredArms);
			preferredDomainsComplete &= !preferredArms.isEmpty();
		}
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains =
			preferredDomainsComplete ? preferredDomains : legalityDomains;
		boolean usedLegalityDomains = !preferredDomainsComplete;
		String legalityOverrideReason = usedLegalityDomains ? "empty-preferred-domain" : null;
		List<CompiledHopKey> order = prioritizeExactJoinRoots(roots,
			exactJoinVariableOrder(componentId, domains));
		ExactComponentJoin[] best = {null};
		ExactJoinSearchDiagnostics searchDiagnostics = new ExactJoinSearchDiagnostics();
		searchExactComponentAssignment(analysis, memoTable, componentId, roots, order, domains,
			fixed, 0, new IdentityHashMap<>(), new IdentityHashMap<>(), 0d,
			new HashMap<>(), best, searchDiagnostics);
		if(best[0] == null && domains != legalityDomains) {
			if(FederatedPlannerTrace.isEnabled()) {
				String preferredFailure = "component=" + componentId.ordinal
					+ " search=" + searchDiagnostics.summary(domains)
					+ " constraints=" + describeExactJoinConstraints(analysis, domains)
					+ " syntheticBoundaryLocks="
						+ describeSyntheticBoundaryLocks(analysis, fixed, domains);
				FederatedPlannerTrace.logGlobal(
					"DP-ComponentPreferredForestInfeasible", preferredFailure);
			}
			// A graph-declared global legality constraint (notably exact TWrite/TRead
			// coherence) may make every locally preferred arm infeasible. Reopen only the
			// already-enumerated legal component domain and minimize the shared DP cost
			// objective over that local separator; this does not expand DP into the Exact
			// planner's whole-program search space.
			domains = legalityDomains;
			usedLegalityDomains = true;
			legalityOverrideReason = "no-coherent-preferred-output-forest";
			order = prioritizeExactJoinRoots(roots,
				exactJoinVariableOrder(componentId, domains));
			searchDiagnostics = new ExactJoinSearchDiagnostics();
			searchExactComponentAssignment(analysis, memoTable, componentId, roots, order, domains,
				fixed, 0, new IdentityHashMap<>(), new IdentityHashMap<>(), 0d,
				new HashMap<>(), best, searchDiagnostics);
		}
		if(best[0] != null && usedLegalityDomains && FederatedPlannerTrace.isEnabled())
			for(CompiledHopKey member : componentId.members) {
				PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(member);
				if(occurrence != null && FederatedPlannerTrace.shouldTrace(occurrence.hop()))
					FederatedPlannerTrace.log(occurrence.hop(), "DP-ComponentLegalityOverride",
						"component=" + componentId.ordinal + " reason=" + legalityOverrideReason);
			}
		if(best[0] == null)
			throw new IllegalStateException("DP component has no locally ranked coherent exact root-plan forest: "
				+ boundedSummary(componentId.members.stream()
					.map(FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 12)
				+ " roots=" + boundedSummary(roots.stream()
					.map(FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 12)
				+ " order=" + boundedSummary(order.stream()
					.map(FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 12)
				+ " fixed=" + describeExactJoinSelections(fixed, domains)
				+ " foreignFixed=" + describeExactJoinForeignSelections(fixed, domains)
				+ " constraints=" + describeExactJoinConstraints(analysis, domains)
				+ " constraintLocks=" + describeExactJoinConstraintLocks(analysis, fixed, domains)
				+ " syntheticBoundaryLocks="
					+ describeSyntheticBoundaryLocks(analysis, fixed, domains)
				+ " search=" + searchDiagnostics.summary(domains)
				+ " domains=" + describeExactJoinDomains(domains)
				+ " allCarriers=" + boundedSummary(componentId.members.stream().map(key -> {
					PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(key);
					return compactOccurrence(key) + "=>" + memoTable.getAllExactPlanVariantsForOccurrence(occurrence)
						.stream().map(arm -> compactPlanArm(arm.plan())).toList();
				}).toList(), 12));
		return best[0];
	}

	/**
	 * Collapse only variants that are interchangeable to the exact legality join.
	 * The input order is the DP output preference, cumulative cost, and stable tie
	 * order, so retaining the first member preserves DP philosophy while avoiding a
	 * Cartesian product over duplicate raw/recompile carriers.  Ordered child edges
	 * remain in the key because operand order is executable semantics.
	 */
	private static List<FederatedPlannerDpMemoTable.FedPlan> exactJoinDomainRepresentatives(
		List<FederatedPlannerDpMemoTable.FedPlan> orderedArms) {
		Map<ExactJoinSemanticKey,FederatedPlannerDpMemoTable.FedPlan> unique = new LinkedHashMap<>();
		for(FederatedPlannerDpMemoTable.FedPlan plan : orderedArms)
			unique.putIfAbsent(exactJoinSemanticKey(plan), plan);
		return List.copyOf(unique.values());
	}

	private static ExactJoinSemanticKey exactJoinSemanticKey(
		FederatedPlannerDpMemoTable.FedPlan plan) {
		SelectedDpState state = selectedState(plan);
		List<ExactJoinChildBoundary> children = new ArrayList<>();
		for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge :
			plan.getExactChildPlanEdges()) {
			SelectedDpState child = selectedState(edge.selectedPlan());
			if(edge.output() != child.output())
				throw new IllegalStateException("DP exact join child declaration differs from selected arm: "
					+ edge.occurrence().normalizedSignature() + " declared=" + edge.output()
					+ " selected=" + child.output());
			children.add(new ExactJoinChildBoundary(edge.occurrence(), child.output(),
				child.output() == FederatedOutput.FOUT ? child.fType() : null));
		}
		return new ExactJoinSemanticKey(state.exactState(), state.derivedFedFout(),
			state.directCandidateSelection(), state.directRelocationChoices(), children);
	}

	private static void reconcileExactJoinOutputDecisions(FederatedPlannerDpMemoTable memoTable,
		OrdinaryComponentId componentId, ExactComponentJoin join,
		Map<Long,FederatedOutput> componentLocks, Map<Long,FederatedOutput> outputDecisions) {
		Map<Long,FederatedOutput> joined = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey,SelectedDpState> entry : join.selections().entrySet()) {
			PlacementAnalysis.HopOccurrenceProjection occurrence =
				memoTable.requirePlanCarrierOccurrence(entry.getValue().retainedPlan().getHopRef());
			if(occurrence.key() != entry.getKey())
				throw new IllegalStateException("DP exact join output reconciliation has foreign authority: "
					+ entry.getKey());
			long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
			FederatedOutput selected = entry.getValue().output();
			FederatedOutput lock = componentLocks.get(originalHopId);
			if(lock != null && lock != selected)
				throw new IllegalStateException("DP exact join changed a committed component output: hop="
					+ originalHopId + " locked=" + lock + " selected=" + selected);
			FederatedOutput previous = joined.putIfAbsent(originalHopId, selected);
			if(previous != null && previous != selected)
				throw new IllegalStateException("DP exact join selected conflicting clone-family outputs: hop="
					+ originalHopId + " first=" + previous + " second=" + selected
					+ " component=" + componentId.ordinal);
		}
		outputDecisions.putAll(joined);
	}

	private static List<String> describeExactJoinConstraints(PlacementAnalysis analysis,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		return boundedSummary(analysis.graph().constraints().stream()
			.filter(FederatedPlannerDpFedCostBased::isExactComponentLegalityConstraint)
			.filter(constraint -> domains.containsKey(constraint.left())
				|| domains.containsKey(constraint.right()))
			.map(Constraint::normalizedSignature).toList(), 24);
	}

	private static List<String> describeExactJoinConstraintLocks(PlacementAnalysis analysis,
		Map<CompiledHopKey,SelectedDpState> fixed,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		return boundedSummary(analysis.graph().constraints().stream()
			.filter(FederatedPlannerDpFedCostBased::isExactComponentLegalityConstraint)
			.filter(constraint -> domains.containsKey(constraint.left())
				|| domains.containsKey(constraint.right()))
			.map(constraint -> compactOccurrence(constraint.left()) + '='
				+ exactStateDiagnostic(fixed.get(constraint.left())) + " -> "
				+ compactOccurrence(constraint.right()) + '='
				+ exactStateDiagnostic(fixed.get(constraint.right())))
			.toList(), 24);
	}

	private static List<String> describeSyntheticBoundaryLocks(PlacementAnalysis analysis,
		Map<CompiledHopKey,SelectedDpState> fixed,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		Set<CompiledHopKey> boundaries = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints()) {
			if(domains.containsKey(constraint.left())
				&& isSyntheticFunctionBoundary(analysis, constraint.right()))
				boundaries.add(constraint.right());
			if(domains.containsKey(constraint.right())
				&& isSyntheticFunctionBoundary(analysis, constraint.left()))
				boundaries.add(constraint.left());
		}
		return boundedSummary(boundaries.stream().sorted().flatMap(boundary ->
			analysis.graph().constraints().stream()
				.filter(FederatedPlannerDpFedCostBased::isExactComponentLegalityConstraint)
				.filter(constraint -> constraint.left() == boundary || constraint.right() == boundary)
				.map(constraint -> constraint.normalizedSignature() + "|leftLock="
					+ exactStateDiagnostic(fixed.get(constraint.left())) + "|rightLock="
					+ exactStateDiagnostic(fixed.get(constraint.right()))))
			.toList(), 24);
	}

	private static List<CompiledHopKey> prioritizeExactJoinRoots(List<CompiledHopKey> roots,
		List<CompiledHopKey> topologicalOrder) {
		Set<CompiledHopKey> added = Collections.newSetFromMap(new IdentityHashMap<>());
		List<CompiledHopKey> result = new ArrayList<>(topologicalOrder.size());
		roots.stream().sorted().forEach(key -> {
			if(added.add(key))
				result.add(key);
		});
		for(CompiledHopKey key : topologicalOrder)
			if(added.add(key))
				result.add(key);
		return List.copyOf(result);
	}

	private static List<String> describeExactJoinForeignSelections(
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		Set<CompiledHopKey> referenced = Collections.newSetFromMap(new IdentityHashMap<>());
		for(List<FederatedPlannerDpMemoTable.FedPlan> plans : domains.values())
			for(FederatedPlannerDpMemoTable.FedPlan plan : plans)
				for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge : plan.getExactChildPlanEdges())
					if(edge.occurrence() != null && !domains.containsKey(edge.occurrence()))
						referenced.add(edge.occurrence());
		return boundedSummary(selections.entrySet().stream()
			.filter(entry -> referenced.contains(entry.getKey()))
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> compactOccurrence(entry.getKey()) + "=>"
				+ compactPlanArm(entry.getValue().retainedPlan()))
			.toList(), 16);
	}

	private static List<String> describeExactJoinSelections(
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		Set<CompiledHopKey> relevant = Collections.newSetFromMap(new IdentityHashMap<>());
		relevant.addAll(domains.keySet());
		List<String> descriptions = selections.entrySet().stream().filter(entry -> relevant.contains(entry.getKey()))
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> compactOccurrence(entry.getKey()) + "=>"
				+ compactPlanArm(entry.getValue().retainedPlan()))
			.toList();
		return boundedSummary(descriptions, 12);
	}

	private static List<String> describeExactJoinDomains(
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		List<String> descriptions = domains.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.map(entry -> compactOccurrence(entry.getKey()) + "=>" + boundedSummary(entry.getValue().stream()
				.map(plan -> compactPlanArm(plan)
					+ ",edgeOwners=" + boundedSummary(plan.getExactChildPlanEdges().stream()
					.map(edge -> edge.output() + "->" + compactOccurrence(edge.occurrence())
						+ "{" + compactPlanArm(edge.selectedPlan()) + "}").toList(), 6))
				.toList(), 4))
			.toList();
		return boundedSummary(descriptions, 12);
	}

	private static List<String> boundedSummary(List<String> values, int limit) {
		if(values.size() <= limit)
			return values;
		List<String> bounded = new ArrayList<>(values.subList(0, limit));
		bounded.add("...(+" + (values.size() - limit) + ")");
		return List.copyOf(bounded);
	}

	private static String compactPlanArm(FederatedPlannerDpMemoTable.FedPlan plan) {
		if(plan == null)
			return "synthetic";
		return plan.getHopID() + ":" + plan.getSelectedPlacementState().normalizedSignature()
			+ "#state@" + Integer.toHexString(System.identityHashCode(plan.getSelectedPlacementState()))
			+ ":derived=" + plan.isDerivedFedFout() + ":children=" + plan.getChildFedPlans()
			+ ":candidate=" + (plan.getDirectCandidateSelection() == null ? "-" : "present")
			+ ":relocations=" + plan.getDirectRelocationChoices().size();
	}

	private static String compactPlanArmWithExactChildren(
		FederatedPlannerDpMemoTable.FedPlan plan) {
		return compactPlanArm(plan) + ",edgeOwners=" + boundedSummary(
			plan.getExactChildPlanEdges().stream().map(edge -> edge.output() + "->"
				+ compactOccurrence(edge.occurrence()) + '{'
				+ compactPlanArm(edge.selectedPlan()) + '}').toList(), 6);
	}

	private static String compactOccurrence(CompiledHopKey key) {
		return key == null ? "external" : key.canonicalSourceOrigin() + '@' + key.emittedHopInstance();
	}

	private static void searchExactComponentAssignment(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, OrdinaryComponentId componentId, List<CompiledHopKey> roots,
		List<CompiledHopKey> order, Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains,
		Map<CompiledHopKey,SelectedDpState> fixed, int index,
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> requirements,
		double cost, Map<ExactJoinMemoKey,Double> prefixMemo, ExactComponentJoin[] best) {
		searchExactComponentAssignment(analysis, memo, componentId, roots, order, domains, fixed,
			index, selections, requirements, cost, prefixMemo, best, new ExactJoinSearchDiagnostics());
	}

	private static void searchExactComponentAssignment(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, OrdinaryComponentId componentId, List<CompiledHopKey> roots,
		List<CompiledHopKey> order, Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains,
		Map<CompiledHopKey,SelectedDpState> fixed, int index,
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> requirements,
		double cost, Map<ExactJoinMemoKey,Double> prefixMemo, ExactComponentJoin[] best,
		ExactJoinSearchDiagnostics diagnostics) {
		if(index == order.size()) {
			String signature = exactComponentJoinSignature(componentId, selections);
			List<FederatedPlannerDpMemoTable.FedPlan> selectedRoots = roots.stream()
				.map(key -> selections.get(key).retainedPlan()).toList();
			if(best[0] == null || cost < best[0].objective())
				best[0] = new ExactComponentJoin(selectedRoots, selections, cost, signature);
			return;
		}
		if(best[0] != null && cost >= best[0].objective()) {
			diagnostics.reject(index, order.get(index), "objective-bound",
				"prefix=" + cost + " incumbent=" + best[0].objective());
			return;
		}
		ExactJoinMemoKey memoKey = exactJoinSearchState(analysis, order, domains,
			selections, requirements, index);
		CompiledHopKey key = order.get(index);
		diagnostics.visit(index, key);
		Double previousCost = prefixMemo.get(memoKey);
		if(previousCost != null && previousCost <= cost) {
			diagnostics.reject(index, key, "memo", "equivalent-future-state previous="
				+ previousCost + " current=" + cost);
			return;
		}
		prefixMemo.put(memoKey, cost);

		FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge required = requirements.get(key);
		SelectedDpState fixedState = fixed.get(key);
		for(FederatedPlannerDpMemoTable.FedPlan plan : domains.get(key)) {
			SelectedDpState proposed = selectedState(plan);
			if(required != null && !sameConsumableBoundary(proposed, selectedState(required.selectedPlan()))) {
				diagnostics.reject(index, key, "required-boundary", () -> compactPlanArm(plan)
					+ " required=" + compactPlanArm(required.selectedPlan())
					+ " sources=" + exactRequirementSources(key, selections)
					+ " sourceDomains=" + exactRequirementSourceDomains(key, selections, domains));
				continue;
			}
			if(fixedState != null && !sameExactAuthority(fixedState, proposed)) {
				diagnostics.reject(index, key, "fixed-authority", () -> compactPlanArm(plan)
					+ " fixed=" + exactStateDiagnostic(fixedState));
				continue;
			}
			Map<CompiledHopKey,FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> nextRequirements =
				new IdentityHashMap<>(requirements);
			boolean compatible = true;
			for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge : plan.getExactChildPlanEdges()) {
				CompiledHopKey childKey = edge.occurrence();
				if(childKey == null)
					continue;
				SelectedDpState edgeChild = selectedState(edge.selectedPlan());
				SelectedDpState assignedChild = selections.get(childKey);
				SelectedDpState boundaryChild = fixed.get(childKey);
				FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge existingRequirement =
					nextRequirements.get(childKey);
				if(assignedChild != null && !sameConsumableBoundary(
						assignedChild, edgeChild)
					|| boundaryChild != null && !sameConsumableBoundary(boundaryChild, edgeChild)
					|| existingRequirement != null && !sameConsumableBoundary(
						selectedState(existingRequirement.selectedPlan()),
						edgeChild)) {
					diagnostics.reject(index, key, "child-boundary", () -> compactPlanArm(plan)
						+ " child=" + compactOccurrence(childKey)
						+ " required=" + exactStateDiagnostic(edgeChild)
						+ " assigned=" + exactStateDiagnostic(assignedChild)
						+ " fixed=" + exactStateDiagnostic(boundaryChild));
					compatible = false;
					break;
				}
				if(assignedChild == null && boundaryChild == null && domains.containsKey(childKey)
					&& domains.get(childKey).stream().map(FederatedPlannerDpFedCostBased::selectedState)
						.noneMatch(candidate -> sameConsumableBoundary(candidate, edgeChild))) {
					diagnostics.reject(index, key, "child-domain", () -> compactPlanArm(plan)
						+ " child=" + compactOccurrence(childKey)
						+ " required=" + exactStateDiagnostic(edgeChild));
					compatible = false;
					break;
				}
				// Foreign child occurrences are not members of this ordinary component,
				// but every parent arm in the component must still agree on their
				// physical boundary state.  Dropping these requirements allowed the
				// exact join to choose mutually incompatible function-boundary arms and
				// fail only later while scheduling traversal dependencies.
				nextRequirements.putIfAbsent(childKey, edge);
			}
			if(!compatible)
				continue;
			Map<CompiledHopKey,SelectedDpState> nextSelections = new IdentityHashMap<>(selections);
			nextSelections.put(key, proposed);
			ExactJoinLegalityViolation legalityViolation =
				exactJoinLegalityViolation(analysis, nextSelections, fixed, domains);
			if(legalityViolation != null) {
				diagnostics.reject(index, key, "graph-legality", () -> legalityViolation.detail()
					+ " plan=" + compactPlanArm(plan) + " requirementSources="
					+ exactRequirementSources(key, selections) + " endpointDomains="
					+ exactViolationEndpointDomains(legalityViolation.constraint(), domains));
				continue;
			}
			searchExactComponentAssignment(analysis, memo, componentId, roots, order, domains, fixed,
				index + 1, nextSelections, nextRequirements, cost + exactPlanLocalCost(plan),
				prefixMemo, best, diagnostics);
		}
	}

	private static List<String> exactRequirementSources(CompiledHopKey requiredKey,
		Map<CompiledHopKey,SelectedDpState> selections) {
		return boundedSummary(selections.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.flatMap(entry -> entry.getValue().retainedPlan().getExactChildPlanEdges().stream()
				.filter(edge -> edge.occurrence() == requiredKey)
				.map(edge -> compactOccurrence(entry.getKey()) + "=>" + edge.output() + '/'
					+ selectedState(edge.selectedPlan()).exactState().normalizedSignature()))
			.toList(), 8);
	}

	private static List<String> exactRequirementSourceDomains(CompiledHopKey requiredKey,
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		return boundedSummary(selections.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.filter(entry -> entry.getValue().retainedPlan().getExactChildPlanEdges().stream()
				.anyMatch(edge -> edge.occurrence() == requiredKey))
			.map(entry -> compactOccurrence(entry.getKey()) + "=>" + domains.getOrDefault(
				entry.getKey(), List.of()).stream().map(FederatedPlannerDpFedCostBased::compactPlanArm).toList())
			.toList(), 8);
	}

	private static List<String> exactViolationEndpointDomains(Constraint constraint,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		if(constraint == null)
			return List.of("synthetic");
		return List.of(constraint.left(), constraint.right()).stream().map(key -> {
			List<FederatedPlannerDpMemoTable.FedPlan> endpoint = domains.get(key);
			return compactOccurrence(key) + "=>" + (endpoint == null ? "fixed/external"
				: boundedSummary(endpoint.stream().map(plan -> compactPlanArm(plan)
					+ ",edgeOwners=" + boundedSummary(plan.getExactChildPlanEdges().stream()
						.map(edge -> edge.output() + "->" + compactOccurrence(edge.occurrence())
							+ "{" + compactPlanArm(edge.selectedPlan()) + "}").toList(), 6))
					.toList(), 8));
		}).toList();
	}

	private static ExactJoinLegalityViolation exactJoinLegalityViolation(PlacementAnalysis analysis,
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,SelectedDpState> fixed,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		Map<CompiledHopKey,List<SyntheticBoundaryIncident>> unresolvedSynthetic = new IdentityHashMap<>();
		Set<CompiledHopKey> touchedSynthetic = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints()) {
			if(!isExactComponentLegalityConstraint(constraint))
				continue;
			SelectedDpState currentLeft = selections.get(constraint.left());
			SelectedDpState currentRight = selections.get(constraint.right());
			SelectedDpState leftSelection = currentLeft;
			if(leftSelection == null)
				leftSelection = fixed.get(constraint.left());
			SelectedDpState rightSelection = currentRight;
			if(rightSelection == null)
				rightSelection = fixed.get(constraint.right());
			if(leftSelection == null || rightSelection == null) {
				if(leftSelection == null && rightSelection != null
					&& isSyntheticFunctionBoundary(analysis, constraint.left())) {
					unresolvedSynthetic.computeIfAbsent(constraint.left(), ignored -> new ArrayList<>())
						.add(new SyntheticBoundaryIncident(constraint, rightSelection.exactState()));
					if(currentRight != null)
						touchedSynthetic.add(constraint.left());
				}
				if(rightSelection == null && leftSelection != null
					&& isSyntheticFunctionBoundary(analysis, constraint.right())) {
					unresolvedSynthetic.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
						.add(new SyntheticBoundaryIncident(constraint, leftSelection.exactState()));
					if(currentLeft != null)
						touchedSynthetic.add(constraint.right());
				}
				// Fixed-only incidents are deliberately collected above: once this component
				// touches the same latent boundary they constrain its shared exact state. They
				// must not, however, reject an unrelated component on their own.
				if(currentLeft == null && currentRight == null)
					continue;
				if(leftSelection != null && rightSelection == null
					&& domains.containsKey(constraint.right())
					&& !isSyntheticFunctionBoundary(analysis, constraint.right())
					&& !exactJoinConstraintHasSupport(constraint, leftSelection, true,
						domains.get(constraint.right())))
					return new ExactJoinLegalityViolation(constraint,
						"forward-domain:" + constraint.kind() + "[evidence="
							+ constraint.evidence() + "]:" + compactOccurrence(constraint.left()) + '='
							+ leftSelection.exactState().normalizedSignature() + " -> "
							+ compactOccurrence(constraint.right()) + " has no supported state");
				if(rightSelection != null && leftSelection == null
					&& domains.containsKey(constraint.left())
					&& !isSyntheticFunctionBoundary(analysis, constraint.left())
					&& !exactJoinConstraintHasSupport(constraint, rightSelection, false,
						domains.get(constraint.left())))
					return new ExactJoinLegalityViolation(constraint,
						"forward-domain:" + constraint.kind() + "[evidence="
							+ constraint.evidence() + "]:" + compactOccurrence(constraint.left())
							+ " has no supported state -> "
							+ compactOccurrence(constraint.right()) + '='
							+ rightSelection.exactState().normalizedSignature());
				continue;
			}
			// Validate the component prefix being extended, not unrelated exact locks
			// captured by an earlier component. A pre-existing violation must be handled
			// by its owner component; allowing it to reject an independent literal makes
			// every later component unsatisfiable without changing the offending state.
			if(currentLeft == null && currentRight == null)
				continue;
			if(!NeutralPlacementGraph.constraintSatisfied(constraint,
				leftSelection.exactState(), rightSelection.exactState()))
				return new ExactJoinLegalityViolation(constraint,
					constraint.kind() + "[evidence=" + constraint.evidence() + "]:"
					+ compactOccurrence(constraint.left()) + '='
					+ leftSelection.exactState().normalizedSignature() + " -> "
					+ compactOccurrence(constraint.right()) + '='
					+ rightSelection.exactState().normalizedSignature());
		}
		for(Map.Entry<CompiledHopKey,List<SyntheticBoundaryIncident>> entry :
			unresolvedSynthetic.entrySet()) {
			if(!touchedSynthetic.contains(entry.getKey()))
				continue;
			Node boundary = analysis.graph().node(entry.getKey()).orElseThrow();
			boolean feasible = syntheticBoundaryHasFeasibleState(
				entry.getKey(), boundary, entry.getValue());
			if(!feasible)
				return new ExactJoinLegalityViolation(null,
					"synthetic:" + compactOccurrence(entry.getKey()) + " alternatives="
					+ boundary.legalAlternatives().stream().map(PlacementState::normalizedSignature).toList()
					+ " neighbors=" + entry.getValue().stream()
						.map(incident -> {
							Constraint constraint = incident.constraint();
							CompiledHopKey neighbor = constraint.left() == entry.getKey()
								? constraint.right() : constraint.left();
							return compactOccurrence(neighbor) + '='
								+ incident.neighborState().normalizedSignature()
								+ "[" + constraint.kind() + ":" + constraint.evidence() + ']'
								+ " domain=" + boundedSummary(domains.getOrDefault(neighbor, List.of())
									.stream().map(FederatedPlannerDpFedCostBased::compactPlanArmWithExactChildren)
									.toList(), 6);
						}).toList());
		}
		return null;
	}

	private static boolean syntheticBoundaryHasFeasibleState(CompiledHopKey boundaryKey,
		Node boundary, List<SyntheticBoundaryIncident> incidents) {
		return boundary.legalAlternatives().stream().anyMatch(boundaryState ->
			incidents.stream().allMatch(incident -> {
				Constraint constraint = incident.constraint();
				PlacementState left = constraint.left() == boundaryKey
					? boundaryState : incident.neighborState();
				PlacementState right = constraint.right() == boundaryKey
					? boundaryState : incident.neighborState();
				return NeutralPlacementGraph.constraintSatisfied(constraint, left, right);
			}));
	}

	private static boolean exactJoinConstraintHasSupport(Constraint constraint,
		SelectedDpState selected, boolean selectedIsLeft,
		List<FederatedPlannerDpMemoTable.FedPlan> unselectedDomain) {
		for(FederatedPlannerDpMemoTable.FedPlan candidatePlan : unselectedDomain) {
			SelectedDpState candidate = selectedState(candidatePlan);
			PlacementState left = selectedIsLeft ? selected.exactState() : candidate.exactState();
			PlacementState right = selectedIsLeft ? candidate.exactState() : selected.exactState();
			if(NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
				return true;
		}
		return false;
	}

	private record ExactJoinLegalityViolation(Constraint constraint, String detail) { }

	private record SyntheticBoundaryIncident(Constraint constraint, PlacementState neighborState) { }

	private record ExactAuthorityMemoKey(PlacementState exactState, boolean derivedFedFout,
		CandidateSelectionReceipt directCandidateSelection,
		List<RelocationChoiceReceipt> directRelocationChoices) {
		private ExactAuthorityMemoKey {
			Objects.requireNonNull(exactState, "exactState");
			directRelocationChoices = List.copyOf(directRelocationChoices);
		}
	}

	private record ExactBoundaryMemoKey(FederatedOutput output, FType fType) {
		private ExactBoundaryMemoKey {
			Objects.requireNonNull(output, "output");
			if(output == FederatedOutput.FOUT && fType == null || output == FederatedOutput.LOUT && fType != null)
				throw new IllegalArgumentException("Exact boundary memo placement differs");
		}
	}

	private record ExactJoinMemoKey(int index,
		List<ExactAuthorityMemoKey> relevantSelections,
		List<ExactBoundaryMemoKey> orderedRequirements,
		Map<CompiledHopKey,ExactBoundaryMemoKey> foreignRequirements) {
		private ExactJoinMemoKey {
			relevantSelections = Collections.unmodifiableList(new ArrayList<>(relevantSelections));
			orderedRequirements = Collections.unmodifiableList(new ArrayList<>(orderedRequirements));
			foreignRequirements = Map.copyOf(foreignRequirements);
		}
	}

	private static ExactJoinMemoKey exactJoinSearchState(PlacementAnalysis analysis,
		List<CompiledHopKey> order,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains,
		Map<CompiledHopKey,SelectedDpState> selections,
		Map<CompiledHopKey,FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> requirements,
		int index) {
		Set<CompiledHopKey> backwardReferenced = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int i = index; i < order.size(); i++)
			for(FederatedPlannerDpMemoTable.FedPlan plan : domains.get(order.get(i)))
				for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge : plan.getExactChildPlanEdges()) {
					CompiledHopKey child = edge.occurrence();
					if(child != null && selections.containsKey(child))
						backwardReferenced.add(child);
				}
		for(Constraint constraint : analysis.graph().constraints()) {
			if(!isExactComponentLegalityConstraint(constraint))
				continue;
			if(selections.containsKey(constraint.left()) && domains.containsKey(constraint.right())
				&& !selections.containsKey(constraint.right()))
				backwardReferenced.add(constraint.left());
			if(selections.containsKey(constraint.right()) && domains.containsKey(constraint.left())
				&& !selections.containsKey(constraint.left()))
				backwardReferenced.add(constraint.right());
		}
		// A synthetic function boundary is an unassigned latent variable shared by
		// every formal-input occurrence.  Until its last incident occurrence has been
		// selected, the memo state must retain all already selected neighbors: their
		// intersection determines which boundary alternatives remain feasible.  The
		// former key omitted these neighbors because the synthetic node is not in the
		// component domain, merging (for example) an earlier CP/LOUT Y read with an
		// earlier FED/FOUT Y read and incorrectly pruning the coherent L2SVM forest.
		Set<CompiledHopKey> pendingSynthetic = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Constraint constraint : analysis.graph().constraints()) {
			if(!isExactComponentLegalityConstraint(constraint))
				continue;
			if(isSyntheticFunctionBoundary(analysis, constraint.left())
				&& domains.containsKey(constraint.right()) && !selections.containsKey(constraint.right()))
				pendingSynthetic.add(constraint.left());
			if(isSyntheticFunctionBoundary(analysis, constraint.right())
				&& domains.containsKey(constraint.left()) && !selections.containsKey(constraint.left()))
				pendingSynthetic.add(constraint.right());
		}
		if(!pendingSynthetic.isEmpty())
			for(Constraint constraint : analysis.graph().constraints()) {
				if(!isExactComponentLegalityConstraint(constraint))
					continue;
				if(pendingSynthetic.contains(constraint.left())
					&& selections.containsKey(constraint.right()))
					backwardReferenced.add(constraint.right());
				if(pendingSynthetic.contains(constraint.right())
					&& selections.containsKey(constraint.left()))
					backwardReferenced.add(constraint.left());
			}
		List<ExactAuthorityMemoKey> relevantSelections = new ArrayList<>(order.size());
		List<ExactBoundaryMemoKey> orderedRequirements = new ArrayList<>(order.size());
		for(int i = 0; i < order.size(); i++) {
			CompiledHopKey key = order.get(i);
			SelectedDpState selected = selections.get(key);
			boolean relevant = i >= index || backwardReferenced.contains(key);
			relevantSelections.add(relevant && selected != null ? exactAuthorityMemoKey(selected) : null);
			orderedRequirements.add(relevant ? exactBoundaryMemoKey(requirements.get(key)) : null);
		}
		Map<CompiledHopKey,ExactBoundaryMemoKey> foreignRequirements = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey,FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> entry :
			requirements.entrySet())
			if(!domains.containsKey(entry.getKey()))
				foreignRequirements.put(entry.getKey(), exactBoundaryMemoKey(entry.getValue()));
		return new ExactJoinMemoKey(index, relevantSelections, orderedRequirements, foreignRequirements);
	}

	private static ExactAuthorityMemoKey exactAuthorityMemoKey(SelectedDpState state) {
		return new ExactAuthorityMemoKey(state.exactState(), state.derivedFedFout(),
			state.directCandidateSelection(), state.directRelocationChoices());
	}

	private static ExactBoundaryMemoKey exactBoundaryMemoKey(
		FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge requirement) {
		if(requirement == null)
			return null;
		SelectedDpState selected = selectedState(requirement.selectedPlan());
		return new ExactBoundaryMemoKey(requirement.output(),
			selected.output() == FederatedOutput.FOUT ? selected.fType() : null);
	}

	private static List<CompiledHopKey> exactJoinVariableOrder(OrdinaryComponentId componentId,
		Map<CompiledHopKey,List<FederatedPlannerDpMemoTable.FedPlan>> domains) {
		Map<CompiledHopKey,Integer> indegree = new IdentityHashMap<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> outgoing = new IdentityHashMap<>();
		for(CompiledHopKey key : componentId.members) {
			indegree.put(key, 0);
			outgoing.put(key, Collections.newSetFromMap(new IdentityHashMap<>()));
		}
		for(CompiledHopKey parent : componentId.members)
			for(FederatedPlannerDpMemoTable.FedPlan plan : domains.get(parent))
				for(FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge : plan.getExactChildPlanEdges()) {
					CompiledHopKey child = edge.occurrence();
					if(child != null && indegree.containsKey(child) && outgoing.get(parent).add(child))
						indegree.put(child, indegree.get(child) + 1);
				}
		java.util.PriorityQueue<CompiledHopKey> ready = new java.util.PriorityQueue<>();
		indegree.forEach((key, degree) -> { if(degree == 0) ready.add(key); });
		List<CompiledHopKey> order = new ArrayList<>();
		while(!ready.isEmpty()) {
			CompiledHopKey key = ready.remove();
			order.add(key);
			for(CompiledHopKey child : outgoing.get(key))
				if(indegree.compute(child, (ignored, degree) -> degree - 1) == 0)
					ready.add(child);
		}
		componentId.members.stream().filter(key -> !order.contains(key)).sorted().forEach(order::add);
		return List.copyOf(order);
	}

	private static double exactPlanLocalCost(FederatedPlannerDpMemoTable.FedPlan plan) {
		return FederatedPlannerDpCostEstimator.exactForestObjective(List.of(
			FederatedPlannerDpCostEstimator.exactPlanRecurrenceTerm(plan)));
	}

	private static double exactPlanExclusiveCost(FederatedPlannerDpMemoTable.FedPlan plan) {
		FederatedPlannerDpCostEstimator.ExactRecurrenceTerm term =
			FederatedPlannerDpCostEstimator.exactPlanRecurrenceTerm(plan);
		return Double.longBitsToDouble(term.exclusiveCostBits());
	}

	private static double exactPlanBoundaryCost(FederatedPlannerDpMemoTable.FedPlan plan) {
		FederatedPlannerDpCostEstimator.ExactRecurrenceTerm term =
			FederatedPlannerDpCostEstimator.exactPlanRecurrenceTerm(plan);
		double boundary = 0d;
		for(long bits : term.edgeForwardingCostBits())
			boundary += Double.longBitsToDouble(bits);
		return boundary;
	}

	private static Comparator<FederatedPlannerDpMemoTable.FedPlan> exactPlanTieOrder() {
		return Comparator
			.comparingInt((FederatedPlannerDpMemoTable.FedPlan plan) ->
				plan.getFedOutType() == FederatedOutput.LOUT ? 0 : 1)
			.thenComparingInt(plan -> plan.getSelectedPlacementState().execType() == ExecType.CP ? 0 : 1)
			.thenComparing(FederatedPlannerDpFedCostBased::exactPlanStableSignature);
	}

	private static String exactPlanStableSignature(FederatedPlannerDpMemoTable.FedPlan plan) {
		SelectedDpState state = selectedState(plan);
		return state.exactState().normalizedSignature() + '|' + state.derivedFedFout() + '|'
			+ (state.directCandidateSelection() == null ? "-"
				: state.directCandidateSelection().normalizedSignature()) + '|'
			+ state.directRelocationChoices().stream()
				.map(RelocationChoiceReceipt::normalizedSignature).toList() + '|'
			+ plan.getHopID() + '|' + plan.getExactChildPlanEdges().stream()
				.map(edge -> edge.occurrence().normalizedSignature() + ':' + edge.output() + ':'
					+ exactEdgeAuthoritySignature(edge.selectedPlan())).toList() + '|'
			+ plan.getDirectRelocationActionCosts().entrySet().stream()
				.map(entry -> entry.getKey().normalizedSignature() + '='
					+ Double.doubleToRawLongBits(entry.getValue())).toList() + '|'
			+ Double.doubleToRawLongBits(plan.getCumulativeCost()) + '|'
			+ Double.doubleToRawLongBits(plan.getEmbeddedChildRecurrenceCost()) + '|'
			+ Double.doubleToRawLongBits(plan.getPhysicalChildBoundaryCost());
	}

	private static String exactAuthoritySignature(SelectedDpState state) {
		return state.exactState().normalizedSignature() + '|' + state.derivedFedFout() + '|'
			+ (state.directCandidateSelection() == null ? "-"
				: state.directCandidateSelection().normalizedSignature()) + '|'
			+ state.directRelocationChoices().stream()
				.map(RelocationChoiceReceipt::normalizedSignature).toList();
	}

	private static String exactEdgeAuthoritySignature(FederatedPlannerDpMemoTable.FedPlan plan) {
		SelectedDpState state = selectedState(plan);
		return state.exactState().normalizedSignature() + '|' + state.derivedFedFout() + '|'
			+ (state.directCandidateSelection() == null ? "-"
				: state.directCandidateSelection().normalizedSignature()) + '|'
			+ state.directRelocationChoices().stream()
				.map(RelocationChoiceReceipt::normalizedSignature).toList();
	}

	private static boolean sameExactAuthority(SelectedDpState left, SelectedDpState right) {
		return left != null && right != null && left.exactState() == right.exactState()
			&& left.derivedFedFout() == right.derivedFedFout()
			&& Objects.equals(left.directCandidateSelection(), right.directCandidateSelection())
			&& left.directRelocationChoices().equals(right.directRelocationChoices());
	}

	private static boolean samePhysicalAuthority(SelectedDpState left, SelectedDpState right) {
		return left != null && right != null && left.exactState() == right.exactState()
			&& left.derivedFedFout() == right.derivedFedFout();
	}

	/**
	 * Compare the runtime value boundary consumed by a parent, not the producer's
	 * internal execution choice.  LOUT is local regardless of whether the producer
	 * computed it in CP or FED.  FOUT additionally carries an FType contract.  The
	 * producer occurrence still receives one exact state from the component join;
	 * this predicate merely prevents a stale enumerated child arm from overriding
	 * the DP conflict resolver's selected producer state.
	 */
	private static boolean sameConsumableBoundary(SelectedDpState left, SelectedDpState right) {
		if(left == null || right == null || left.output() != right.output())
			return false;
		return left.output() == FederatedOutput.LOUT || left.fType() == right.fType();
	}

	private static FederatedPlannerDpCostEstimator.EstimatorReceipt exactSelectedEstimate(
		PlacementAnalysis analysis, FederatedPlannerDpMemoTable memo,
		SelectedDpState selectedState, Map<CompiledHopKey,SelectedDpState> selected) {
		FederatedPlannerDpMemoTable.FedPlan plan = Objects.requireNonNull(
			selectedState.retainedPlan(), "selected retained plan");
		PlacementAnalysis.HopOccurrenceProjection occurrence =
			memo.requirePlanCarrierOccurrence(plan.getHopRef());
		return FederatedPlannerDpCostEstimator.estimateExact(
			new FederatedPlannerDpCostEstimator.EstimatorRequest(analysis, occurrence, memo, plan),
			edge -> exactSelectedChildPlan(memo, selected, edge));
	}

	private static FederatedPlannerDpMemoTable.FedPlan exactSelectedChildPlan(
		FederatedPlannerDpMemoTable memo, Map<CompiledHopKey,SelectedDpState> selected,
		FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge) {
		SelectedDpState captured = identityMapValue(selected, edge.occurrence());
		if(captured == null || captured.retainedPlan() == null || captured.output() != edge.output())
			throw new IllegalStateException("DP exact selected edge lacks joined child authority: "
				+ edge.occurrence().normalizedSignature() + ':' + edge.output());
		FederatedPlannerDpMemoTable.FedPlan resolved = captured.retainedPlan();
		if(memo.requirePlanCarrierOccurrence(resolved.getHopRef()).key() != edge.occurrence()
			|| !sameConsumableBoundary(captured, selectedState(edge.selectedPlan())))
			throw new IllegalStateException("DP exact selected edge resolved a different plan authority: edge="
				+ edge.occurrence().normalizedSignature() + " expected="
				+ exactStateDiagnostic(selectedState(edge.selectedPlan())) + " resolvedOccurrence="
				+ memo.requirePlanCarrierOccurrence(resolved.getHopRef()).key().normalizedSignature()
				+ " resolved=" + exactStateDiagnostic(captured));
		return resolved;
	}

	private static String exactComponentJoinSignature(OrdinaryComponentId componentId,
		Map<CompiledHopKey,SelectedDpState> selections) {
		StringBuilder signature = new StringBuilder();
		for(CompiledHopKey key : componentId.members) {
			SelectedDpState selected = selections.get(key);
			signature.append(key.normalizedSignature()).append('|')
				.append(selected.exactState().normalizedSignature()).append('|')
				.append(selected.derivedFedFout()).append('|')
				.append(selected.directCandidateSelection() == null ? "-"
					: selected.directCandidateSelection().normalizedSignature()).append('|')
				.append(selected.directRelocationChoices().stream()
					.map(RelocationChoiceReceipt::normalizedSignature).toList()).append('|')
				.append(exactPlanStableSignature(selected.retainedPlan())).append(';');
		}
		return signature.toString();
	}

	private void completeDisconnectedComponent(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memoTable, OrdinaryComponentId componentId, List<CompiledHopKey> roots,
		Map<Long, FederatedOutput> outputDecisions, Set<CompiledHopKey> visitedPlanHops,
		Map<Long, FType> fTypeMap, Map<Long, ConflictEntry> rewriteConflictCheckMap,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests,
		Map<CompiledHopKey, SelectedDpState> selectedStates, OwnerComponentIndex ownerIndex,
		TraversalDependencyLedger ledger, Set<CompiledHopKey> preCompletionKeys,
		List<AppliedPlanReceipt> appliedPlans,
		List<DisconnectedCompletionReceipt> disconnectedCompletionReceipts) {
		List<CompiledHopKey> component = componentId.members;
		List<FederatedPlannerDpMemoTable.FedPlan> seedRootPlans = new ArrayList<>();
		for(CompiledHopKey root : roots) {
			PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(root);
			if(occurrence == null)
				throw new IllegalStateException("DP component sink lacks exact occurrence: " + root);
			FederatedPlannerDpMemoTable.FedPlan seed = ledger.dependencyPlan(componentId, root);
			if(seed == null)
				seed = exactOccurrencePlanForCurrentDecision(memoTable, occurrence, outputDecisions);
			if(seed == null)
				throw new IllegalStateException("DP memo omitted component sink " + root
					+ " carriers=" + memoTable.describePlanCarriers(occurrence));
			seedRootPlans.add(seed);
		}
		Map<Long, FederatedOutput> componentLocks = ledger.componentOutputLocks(
			componentId, memoTable, outputDecisions);
		Map<Long, FederatedOutput> localDecisions = new LinkedHashMap<>(componentLocks);
		// Reuse DP's original conflict detector/cost resolver before exact arm
		// materialization.  It owns LOUT/FOUT decisions; component completion only
		// makes those decisions executable and occurrence-coherent.  Function arguments
		// are intentionally not forced to the same output as their formals because a
		// priced FOUT->LOUT boundary is legal.  TRead/TWrite alignment remains inside
		// the conflict resolver.  Resolve all component sinks as one DP root forest:
		// resolving them sequentially allowed a later sink to change a shared TRead
		// after the earlier sink's parent-output compatibility had already been checked.
		List<Pair<Long,FederatedOutput>> componentRootEdges = seedRootPlans.stream()
			.map(seed -> Pair.of(seed.getHopID(), seed.getFedOutType()))
			.toList();
		FederatedPlannerDpMemoTable.FedPlan componentResolutionRoot =
			new FederatedPlannerDpMemoTable.FedPlan(
				seedRootPlans.stream().mapToDouble(
					FederatedPlannerDpMemoTable.FedPlan::getCumulativeCost).sum(),
				null, componentRootEdges);
		componentResolutionRoot.bindExactChildPlanEdges(
			seedRootPlans.stream().map(FederatedPlannerDpMemoTable.FedPlan::getHopRef).toList(),
			seedRootPlans, memoTable);
		localDecisions = resolveOutputDecisionsWithLocks(
			memoTable, componentResolutionRoot, localDecisions, componentLocks);
		if(FederatedPlannerTrace.isEnabled()) {
			DecisionMapScoreBreakdown componentScore =
				computeDecisionMapScoreBreakdown(memoTable, componentResolutionRoot, localDecisions);
			for(CompiledHopKey member : component) {
				PlacementAnalysis.HopOccurrenceProjection occurrence = ownerIndex.occurrence(member);
				if(occurrence == null || !FederatedPlannerTrace.shouldTrace(occurrence.hop()))
					continue;
				long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
				FederatedPlannerTrace.log(occurrence.hop(), "DP-ComponentDecision",
					"component=" + componentId.ordinal + " decision=" + localDecisions.get(originalHopId)
						+ " lock=" + componentLocks.get(originalHopId)
						+ " incompatible=" + componentScore.incompatiblePlanCount
						+ " conflicts=" + componentScore.exactSelectionConflictHopIDs);
			}
		}
		for(Map.Entry<Long,FederatedOutput> lock : componentLocks.entrySet())
			if(localDecisions.get(lock.getKey()) != lock.getValue())
				throw new IllegalStateException("DP conflict resolution changed exact component lock: hop="
					+ lock.getKey() + " required=" + lock.getValue()
					+ " actual=" + localDecisions.get(lock.getKey()));
		ExactComponentJoin join = selectMinimumCostCoherentComponent(analysis, memoTable, componentId, roots,
			ownerIndex, ledger, localDecisions);
		reconcileExactJoinOutputDecisions(
			memoTable, componentId, join, componentLocks, localDecisions);
		List<FederatedPlannerDpMemoTable.FedPlan> rootPlans = join.roots();
		ledger.installExactJoin(componentId, join.selections());
		Map<Long, ConflictEntry> localConflicts = copyConflictEntries(rewriteConflictCheckMap);
		for(FederatedPlannerDpMemoTable.FedPlan rootPlan : rootPlans)
			mergeConflictEntries(localConflicts, collectConflictsSingleBFS(memoTable, rootPlan, localDecisions));
		Map<CompiledHopKey, SelectedDpState> localSelected = new IdentityHashMap<>(selectedStates);
		Set<CompiledHopKey> localVisited = Collections.newSetFromMap(new IdentityHashMap<>());
		localVisited.addAll(visitedPlanHops);
		Map<Long, FType> localFTypes = new LinkedHashMap<>(fTypeMap);
		Map<Long, LocalMaterializeRequest> localRequests = copyLocalMaterializeRequests(localMaterializeRequests);
		Set<CompiledHopKey> scheduleVisited = Collections.newSetFromMap(new IdentityHashMap<>());
		scheduleVisited.addAll(localVisited);
		List<FederatedPlannerDpMemoTable.FedPlan> effectiveRoots = new ArrayList<>();
		List<ExactTraversalRoot> scheduledRoots = new ArrayList<>();
		for(FederatedPlannerDpMemoTable.FedPlan rootPlan : rootPlans) {
			// The locally ranked legality search already chose the exact retained root arm
			// and its candidate/relocation authority. Re-running the legacy Hop-id variant
			// selector here can only drift away from that selected forest.
			FederatedPlannerDpMemoTable.FedPlan effectiveRoot = rootPlan;
			PlacementAnalysis.HopOccurrenceProjection rootOccurrence =
				memoTable.requirePlanCarrierOccurrence(effectiveRoot.getHopRef());
			SelectedDpState rootLock = ledger.selectionLock(rootOccurrence.key());
			if(rootLock != null && !sameExactAuthority(rootLock, selectedState(effectiveRoot))) {
				effectiveRoot = ledger.exactBoundaryPlan(memoTable, rootOccurrence,
					rootPlan.getHopID(), localDecisions);
				rootOccurrence = memoTable.requirePlanCarrierOccurrence(effectiveRoot.getHopRef());
			}
			effectiveRoots.add(effectiveRoot);
			scheduledRoots.add(ledger.scheduleRoot(componentId, rootPlan, effectiveRoot, rootOccurrence));
			scheduleTraversalEdges(componentId, ledger, ownerIndex, effectiveRoot, memoTable,
				localDecisions, localConflicts, scheduleVisited);
		}
		for(int rootOrdinal = 0; rootOrdinal < rootPlans.size(); rootOrdinal++) {
			FederatedPlannerDpMemoTable.FedPlan rootPlan = rootPlans.get(rootOrdinal);
			FederatedPlannerDpMemoTable.FedPlan effectiveRoot = effectiveRoots.get(rootOrdinal);
			PlacementAnalysis.HopOccurrenceProjection effectiveRootOccurrence =
				memoTable.requirePlanCarrierOccurrence(effectiveRoot.getHopRef());
			if(appliedPlans != null && !preCompletionKeys.contains(effectiveRootOccurrence.key())
				&& appliedPlans.stream().noneMatch(value -> value.plan() == effectiveRoot)) {
				long planningHopId = effectiveRoot.getHopID();
				long executableHopId = memoTable.resolveOriginalHopId(planningHopId);
				Hop executableHop = Objects.requireNonNull(memoTable.resolveOriginalHop(planningHopId),
					"componentCompletion.executableHop");
				AppliedPlanReceipt applied = new AppliedPlanReceipt(appliedPlans.size(), true, planningHopId,
					effectiveRoot.getFedOutType(), effectiveRoot, effectiveRoot.getHopRef(),
					executableHopId, executableHop);
				appliedPlans.add(applied);
				if(disconnectedCompletionReceipts != null) {
					disconnectedCompletionReceipts.add(new DisconnectedCompletionReceipt(
						disconnectedCompletionReceipts.size(), applied.ordinal(), applied,
						componentId.ordinal, componentId.fingerprint, componentId.members,
						effectiveRootOccurrence.key(), effectiveRootOccurrence));
				}
			}
			// Scheduling, receipts, and capture must consume the same exact arm. A
			// boundary lock may replace the seed with an arm that has the same output
			// state but different candidate, relocation, and input contracts.
			rewriteHop(effectiveRoot, memoTable, localDecisions, localVisited, localFTypes,
				localConflicts, true, localRequests, localSelected,
				new CaptureTraversalContext(componentId, ledger,
					scheduledRoots.get(rootOrdinal), null));
		}
		ledger.requireComponentClosed(componentId);

		Set<CompiledHopKey> delta = Collections.newSetFromMap(new IdentityHashMap<>());
		delta.addAll(localSelected.keySet());
		delta.removeAll(selectedStates.keySet());
		Set<CompiledHopKey> expected = Collections.newSetFromMap(new IdentityHashMap<>());
		expected.addAll(component);
		if(!delta.equals(expected)) {
			List<CompiledHopKey> missing = component.stream().filter(key -> !delta.contains(key)).sorted().toList();
			List<CompiledHopKey> unexpected = delta.stream().filter(key -> !expected.contains(key)).sorted().toList();
			throw new IllegalStateException("DP disconnected component coverage differs: expectedCount="
				+ expected.size() + " actualCount=" + delta.size() + " missing=" + missing
				+ " unexpected=" + unexpected);
		}
		List<Map.Entry<CompiledHopKey,SelectedDpState>> enrichedBoundaryLocks = new ArrayList<>();
		for(Map.Entry<CompiledHopKey, SelectedDpState> lock : selectedStates.entrySet()) {
			SelectedDpState local = localSelected.get(lock.getKey());
			if(!compatibleBoundarySelection(lock.getValue(), local))
				throw new IllegalStateException("DP component changed boundary lock " + lock.getKey());
			if(lock.getValue().directCandidateSelection() == null
				&& local.directCandidateSelection() != null
				|| lock.getValue().directRelocationChoices().isEmpty()
					&& !local.directRelocationChoices().isEmpty())
				enrichedBoundaryLocks.add(Map.entry(lock.getKey(), local));
		}
		for(Map.Entry<Long, FederatedOutput> lock : componentLocks.entrySet())
			if(localDecisions.get(lock.getKey()) != lock.getValue())
				throw new IllegalStateException("DP component changed output boundary lock " + lock.getKey()
					+ " required=" + lock.getValue() + " actual=" + localDecisions.get(lock.getKey())
					+ " component=" + boundedSummary(component.stream()
						.map(FederatedPlannerDpFedCostBased::compactOccurrence).toList(), 12)
					+ " join=" + describeExactJoinSelections(join.selections(),
						join.selections().entrySet().stream().collect(java.util.stream.Collectors.toMap(
							Map.Entry::getKey, entry -> List.of(entry.getValue().retainedPlan()),
							(left, right) -> left, IdentityHashMap::new)))
					+ " before=" + outputDecisions + " after=" + localDecisions);

		for(CompiledHopKey key : component)
			selectedStates.put(key, localSelected.get(key));
		for(Map.Entry<CompiledHopKey,SelectedDpState> enrichment : enrichedBoundaryLocks)
			selectedStates.put(enrichment.getKey(), enrichment.getValue());
		visitedPlanHops.addAll(localVisited);
		fTypeMap.clear();
		fTypeMap.putAll(localFTypes);
		// Preserve resolver decisions for other already-enumerated forests while this
		// disconnected component contributes or revises only its own conflict closure.
		outputDecisions.putAll(localDecisions);
		rewriteConflictCheckMap.clear();
		rewriteConflictCheckMap.putAll(localConflicts);
		localMaterializeRequests.clear();
		localMaterializeRequests.putAll(localRequests);
	}

	private static boolean compatibleBoundarySelection(SelectedDpState locked, SelectedDpState local) {
		return locked != null && local != null && locked.exactState() == local.exactState()
			&& locked.derivedFedFout() == local.derivedFedFout()
			&& (locked.directCandidateSelection() == null
				|| local.directCandidateSelection() == null
				|| locked.directCandidateSelection().equals(local.directCandidateSelection()))
			&& (locked.directRelocationChoices().isEmpty()
				|| local.directRelocationChoices().isEmpty()
				|| locked.directRelocationChoices().equals(local.directRelocationChoices()));
	}

	private static FederatedPlannerDpMemoTable.FedPlan cheapestExactOccurrencePlan(
		FederatedPlannerDpMemoTable memoTable,
		PlacementAnalysis.HopOccurrenceProjection occurrence) {
		FederatedPlannerDpMemoTable.FedPlan lout =
			memoTable.getFedPlanAfterPrune(occurrence, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlan fout =
			memoTable.getFedPlanAfterPrune(occurrence, FederatedOutput.FOUT);
		if(lout != null && memoTable.requirePlanCarrierOccurrence(lout.getHopRef()) != occurrence)
			throw new IllegalStateException("DP LOUT root plan has a foreign exact occurrence: "
				+ occurrence.key());
		if(fout != null && memoTable.requirePlanCarrierOccurrence(fout.getHopRef()) != occurrence)
			throw new IllegalStateException("DP FOUT root plan has a foreign exact occurrence: "
				+ occurrence.key());
		if(lout == null)
			return fout;
		if(fout == null)
			return lout;
		return lout.getCumulativeCost() <= fout.getCumulativeCost() ? lout : fout;
	}

	private static FederatedPlannerDpMemoTable.FedPlan exactOccurrencePlanForCurrentDecision(
		FederatedPlannerDpMemoTable memoTable,
		PlacementAnalysis.HopOccurrenceProjection occurrence,
		Map<Long,FederatedOutput> outputDecisions) {
		long originalHopId = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
		FederatedOutput desired = outputDecisions.get(originalHopId);
		FederatedPlannerDpMemoTable.FedPlan best = null;
		if(desired != null)
			for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm :
				memoTable.getAllExactPlanVariantsForOccurrence(occurrence)) {
				FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
				if(candidate.getFedOutType() != desired
					|| !isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
					continue;
				if(best == null || candidate.getCumulativeCost() < best.getCumulativeCost())
					best = candidate;
			}
		return best != null ? best : cheapestExactOccurrencePlan(memoTable, occurrence);
	}

	private static Map<Long, ConflictEntry> copyConflictEntries(Map<Long, ConflictEntry> source) {
		Map<Long, ConflictEntry> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(key, new ConflictEntry(value)));
		return copy;
	}

	/**
	 * Feasibility refresh changes only the two canChoose flags. The selected forest
	 * topology is immutable after BFS publication, so share its parent/member maps
	 * instead of cloning every collection for every decision-map score key.
	 */
	private static Map<Long, ConflictEntry> copyConflictFeasibilityEntries(
		Map<Long, ConflictEntry> source) {
		Map<Long, ConflictEntry> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(key, new ConflictEntry(value, true)));
		return copy;
	}

	private static void mergeConflictEntries(Map<Long, ConflictEntry> target, Map<Long, ConflictEntry> additions) {
		additions.forEach((key, value) -> {
			ConflictEntry existing = target.get(key);
			if(existing == null)
				target.put(key, new ConflictEntry(value));
			else
				existing.merge(value);
		});
	}

	private static FinalPlanCertificate certifyFinalPlanForest(PlacementAnalysis analysis,
		FederatedPlannerDpMemoTable memo, Map<CompiledHopKey, SelectedDpState> selected) {
		List<Map.Entry<CompiledHopKey, SelectedDpState>> ordered = selected.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList();
		List<FinalPlanTerm> terms = new ArrayList<>(ordered.size());
		List<FederatedPlannerDpCostEstimator.ExactRecurrenceTerm> recurrenceTerms = new ArrayList<>();
		StringBuilder signature = new StringBuilder();
		for(Map.Entry<CompiledHopKey, SelectedDpState> entry : ordered) {
			CompiledHopKey key = entry.getKey();
			SelectedDpState selectedState = entry.getValue();
			FederatedPlannerDpMemoTable.FedPlan plan = selectedState.retainedPlan();
			if(plan == null) {
				terms.add(new FinalPlanTerm(key, null, Double.doubleToRawLongBits(0d),
					Double.doubleToRawLongBits(0d), List.of()));
				signature.append(key.normalizedSignature()).append('|')
					.append(selectedState.exactState().normalizedSignature()).append("|synthetic;");
				continue;
			}
			PlacementAnalysis.HopOccurrenceProjection occurrence =
				memo.requirePlanCarrierOccurrence(plan.getHopRef());
			if(occurrence.key() != key || plan.getSelectedPlacementState() != selectedState.exactState())
				throw new IllegalStateException("DP final certificate plan differs from captured occurrence: "
					+ key.normalizedSignature());
			FederatedPlannerDpCostEstimator.EstimatorReceipt estimate =
				exactSelectedEstimate(analysis, memo, selectedState, selected);
			for(FederatedPlannerDpCostEstimator.ChildCostReceipt child : estimate.childCosts()) {
				SelectedDpState capturedChild = identityMapValue(selected, child.key());
				if(capturedChild != null && capturedChild.output() != child.output())
					throw new IllegalStateException("DP final certificate edge differs from captured child: parent="
						+ key.normalizedSignature() + " child=" + child.key().normalizedSignature());
			}
			FederatedPlannerDpCostEstimator.ExactRecurrenceTerm recurrenceTerm =
				FederatedPlannerDpCostEstimator.exactRecurrenceTerm(estimate);
			long exclusiveBits = recurrenceTerm.exclusiveCostBits();
			terms.add(new FinalPlanTerm(key, plan, estimate.cumulativeCostBits(),
				exclusiveBits, estimate.childCosts()));
			recurrenceTerms.add(recurrenceTerm);
			signature.append(key.normalizedSignature()).append('|')
				.append(selectedState.exactState().normalizedSignature()).append('|')
				.append(estimate.cumulativeCostBits()).append('|').append(exclusiveBits).append(';');
		}
		double objective = FederatedPlannerDpCostEstimator.exactForestObjective(recurrenceTerms);
		return new FinalPlanCertificate(analysis, memo, terms,
			Double.doubleToRawLongBits(objective), signature.toString());
	}

	private static Map<Long, LocalMaterializeRequest> copyLocalMaterializeRequests(
		Map<Long, LocalMaterializeRequest> source) {
		Map<Long, LocalMaterializeRequest> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(key, new LocalMaterializeRequest(value)));
		return copy;
	}

	private static NormalizedPlannerResult normalizeDpSelection(PlacementAnalysis analysis,
		Map<CompiledHopKey, SelectedDpState> selected, DpPlacementAdapter.ExactSelection exactSelection,
		FinalPlanCertificate finalPlanCertificate, NormalizedPlannerResult completeBase) {
		Map<CompiledHopKey, PlacementEmissionState> assignment = new LinkedHashMap<>();
		if(completeBase != null) {
			if(completeBase.analysis() != analysis)
				throw new IllegalArgumentException("Dynamic base authority belongs to a different analysis");
			assignment.putAll(completeBase.selectedEmissionStates());
		}
		Set<CompiledHopKey> decisionKeys = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var node : analysis.graph().decisionNodes()) {
			decisionKeys.add(node.key());
			SelectedDpState choice = selected.get(node.key());
			if(choice == null && completeBase == null)
				throw new IllegalStateException("DP selection omitted " + node.key());
			if(choice == null)
				continue;
			PlacementState exact = Objects.requireNonNull(choice.exactState(),
				"DP FedPlan omitted its exact analysis-owned placement state for " + node.key());
			if(node.legalAlternatives().stream().noneMatch(state -> state == exact))
				throw new IllegalStateException("DP exact state is foreign to neutral node: " + node.key());
			if(exact.execType() != choice.execType() || exact.output() != choice.output()
				|| exact.fType() != choice.fType())
				throw new IllegalStateException("DP selection is not an exact neutral state: " + node.key()
					+ " choice=" + choice + " legal=" + node.legalAlternatives());
			assignment.put(node.key(), new PlacementEmissionState(exact, choice.derivedFedFout()));
		}
		if(completeBase == null && !selected.keySet().equals(decisionKeys))
			throw new IllegalStateException("DP selection key set differs from neutral decision authority");
		if(completeBase != null && !decisionKeys.containsAll(selected.keySet()))
			throw new IllegalStateException("Dynamic DP selection contains foreign neutral decision keys");
		DpCandidateAndRelocationSelection exactCandidateSelection = selectDpCandidateAndRelocationChoices(
			analysis, selected, assignment, completeBase);
		return NormalizedPlannerResults.createWithEmissionStatesAndCandidateSelections(
			analysis, "DP", assignment, exactCandidateSelection.candidates(),
			exactCandidateSelection.relocationChoices(),
			"objectiveBits=" + finalPlanCertificate.objectiveCostBits());
	}

	private record DpCandidateAndRelocationSelection(List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> relocationChoices) { }

	private static DpCandidateAndRelocationSelection selectDpCandidateAndRelocationChoices(PlacementAnalysis analysis,
		Map<CompiledHopKey, SelectedDpState> selected,
		Map<CompiledHopKey, PlacementEmissionState> emissionStates,
		NormalizedPlannerResult completeBase) {
		Map<CompiledHopKey, PlacementState> assignment = new LinkedHashMap<>();
		emissionStates.forEach((key, state) -> assignment.put(key, state.placementState()));
		CandidateSelections.Selection nativeCompletion = CandidateSelections.selectNativeCanonical(
			analysis, analysis.graph().relocationActions(), assignment);
		Map<CompiledHopKey,CandidateSelectionReceipt> candidatesByConsumer = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : nativeCompletion.candidates())
			candidatesByConsumer.put(candidate.rule().parentOccurrence(), candidate);
		if(completeBase != null)
			for(CandidateSelectionReceipt candidate : completeBase.selectedCandidateSelections())
				candidatesByConsumer.put(candidate.rule().parentOccurrence(), candidate);
		for(Map.Entry<CompiledHopKey,SelectedDpState> entry : selected.entrySet()) {
			CandidateSelectionReceipt candidate = entry.getValue().directCandidateSelection();
			if(candidate != null) {
				if(candidate.rule().parentOccurrence() != entry.getKey()
					|| !candidate.emission().emissionState().placementState()
						.equals(entry.getValue().exactState()))
					throw new IllegalStateException("DP plan retained a candidate row for a different consumer state");
				candidatesByConsumer.put(entry.getKey(), candidate);
			}
		}
		List<CandidateSelectionReceipt> candidates =
			analysis.canonicalCandidateReceipts(candidatesByConsumer.values());
		CandidateSelections.resolveAndValidate(analysis, assignment, candidates);
		Map<RelocationDemandKey,RelocationChoiceReceipt> preferred = new LinkedHashMap<>();
		if(completeBase != null)
			for(RelocationChoiceReceipt choice : completeBase.selectedRelocationChoices())
				preferred.put(choice.demand(), choice);
		Set<RelocationDemandKey> selectedDemands = new LinkedHashSet<>();
		for(Map.Entry<CompiledHopKey,SelectedDpState> entry : selected.entrySet())
			for(RelocationChoiceReceipt choice : entry.getValue().directRelocationChoices()) {
				if(choice.demand().consumer() != entry.getKey()
					|| !choice.demand().requiredPlacement().equals(entry.getValue().exactState()))
					throw new IllegalStateException("DP plan retained a relocation choice for a different exact consumer state: "
						+ choice.normalizedSignature());
				if(!selectedDemands.add(choice.demand()))
					throw new IllegalStateException("DP selected the same exact relocation demand more than once: "
						+ choice.demand().normalizedSignature());
				preferred.put(choice.demand(), choice);
			}

		List<RelocationChoiceReceipt> active = RelocationSelections.selectCanonical(
			analysis, analysis.graph().relocationActions(), assignment, candidates,
			(demand, action) -> true);
		for(RelocationChoiceReceipt choice : active)
			if(selected.containsKey(choice.demand().consumer())
				&& !selectedDemands.contains(choice.demand()))
				throw new IllegalStateException("DP selected consumer has no enumerated exact relocation choice: "
					+ choice.demand().normalizedSignature());
		List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
			analysis, analysis.graph().relocationActions(), assignment, candidates, (demand, action) -> {
			RelocationChoiceReceipt choice = preferred.get(demand);
			return choice == null || choice.action().equals(action);
		});
		return new DpCandidateAndRelocationSelection(candidates, choices);
	}

	private static FederatedPlannerDpMemoTable.FedPlan resolveEffectiveRewritePlan(
		FederatedPlannerDpMemoTable.FedPlan plan, FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions, Map<Long, ConflictEntry> conflicts,
		boolean allowOutputDecisionOverride) {
		long planHopId = plan.getHopRef().getHopID();
		long originalHopId = memoTable.resolveOriginalHopId(planHopId);
		FederatedOutput desired = outputDecisions.getOrDefault(originalHopId, plan.getFedOutType());
		FederatedPlannerDpMemoTable.FedPlan selected = selectRewritePlanVariant(
			memoTable, planHopId, desired, plan.getFedOutType(), plan,
			outputDecisions, conflicts, allowOutputDecisionOverride, RewriteMutationMode.CAPTURE_ONLY);
		PlacementAnalysis.HopOccurrenceProjection occurrence =
			memoTable.requirePlanCarrierOccurrence(plan.getHopRef());
		if(selected != null && memoTable.requirePlanCarrierOccurrence(selected.getHopRef()) == occurrence)
			return selected;
		FederatedPlannerDpMemoTable.FedPlan exact = null;
		for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm :
			memoTable.getAllExactPlanVariantsForOccurrence(occurrence)) {
			FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
			if(candidate.getFedOutType() != desired
				|| !isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
				continue;
			if(exact == null || preferRetainedArm(candidate, exact))
				exact = candidate;
		}
		if(exact == null)
			throw new IllegalStateException("DP effective rewrite has no retained arm for exact occurrence: "
				+ occurrence.key().normalizedSignature() + " desired=" + desired);
		return exact;
	}

	private static SelectedChildResolution resolveSelectedChild(
		FederatedPlannerDpMemoTable memoTable, Pair<Long, FederatedOutput> declaration,
		FederatedOutput selectionInput, Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> conflicts, RewriteMutationMode mutationMode) {
		return resolveSelectedChild(memoTable, declaration, selectionInput, outputDecisions, conflicts,
			mutationMode, null);
	}

	private static SelectedChildResolution resolveSelectedChild(
		FederatedPlannerDpMemoTable memoTable, Pair<Long, FederatedOutput> declaration,
		FederatedOutput selectionInput, Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> conflicts, RewriteMutationMode mutationMode,
		TraversalDependencyLedger ledger) {
		FederatedPlannerDpMemoTable.FedPlan selected = selectRewritePlanVariant(memoTable,
			declaration.getKey(), selectionInput, declaration.getValue(), null, outputDecisions,
			conflicts, false, mutationMode);
		if(selected == null)
			return null;
		PlacementAnalysis.HopOccurrenceProjection occurrence =
			memoTable.requirePlanCarrierOccurrence(selected.getHopRef());
		if(ledger != null) {
			SelectedDpState lock = ledger.selectionLock(occurrence.key());
			// One occurrence can retain multiple candidate/relocation arms with the
			// same FED/FOUT state. Rebind the raw or recompile carrier to the exact
			// component-join authority before scheduling and capture.
			if(lock != null && !sameExactAuthority(lock, selectedState(selected))) {
				FederatedPlannerDpMemoTable.FedPlan locked = ledger.exactBoundaryPlan(
					memoTable, occurrence, declaration.getKey(), outputDecisions);
				selected = locked;
				occurrence = memoTable.requirePlanCarrierOccurrence(selected.getHopRef());
			}
		}
		PlacementState state = Objects.requireNonNull(selected.getSelectedPlacementState(),
			"DP selected child has no exact placement state");
		// Virtual/recompile carriers keep their exact raw edge plan, while component ownership
		// is receipted by one canonical carrier arm for the same exact occurrence, state,
		// candidate row, relocation choices, and child-output decisions.
		FederatedPlannerDpMemoTable.FedPlan canonicalOwnerPlan = exactCanonicalOwnerPlan(
			memoTable, occurrence, selected);
		if(canonicalOwnerPlan == null
			|| memoTable.requirePlanCarrierOccurrence(canonicalOwnerPlan.getHopRef()) != occurrence
			|| canonicalOwnerPlan.getSelectedPlacementState() != state
			|| canonicalOwnerPlan.isDerivedFedFout() != selected.isDerivedFedFout())
			throw new IllegalStateException("DP selected child lacks an exact canonical owner plan: "
				+ occurrence.key() + " selectedHop=" + selected.getHopRef().getHopID()
				+ " selectedState=" + state.normalizedSignature()
				+ " selectedDerived=" + selected.isDerivedFedFout()
				+ " owner=" + (canonicalOwnerPlan == null ? "null"
					: "hop=" + canonicalOwnerPlan.getHopRef().getHopID()
						+ ",sameHop=" + (canonicalOwnerPlan.getHopRef() == occurrence.hop())
						+ ",state=" + (canonicalOwnerPlan.getSelectedPlacementState() == null ? "null"
							: canonicalOwnerPlan.getSelectedPlacementState().normalizedSignature())
						+ ",sameState=" + (canonicalOwnerPlan.getSelectedPlacementState() == state)
						+ ",derived=" + canonicalOwnerPlan.isDerivedFedFout())
				+ " arms=" + memoTable.getAllExactPlanVariantsForOccurrence(occurrence).stream()
					.map(arm -> arm.plan().getHopID() + ":virtual="
						+ memoTable.isVirtualClone(arm.plan().getHopID()) + ":state="
						+ arm.plan().getSelectedPlacementState().normalizedSignature() + ":derived="
						+ arm.plan().isDerivedFedFout()).toList());
		return new SelectedChildResolution(selected, canonicalOwnerPlan, occurrence, occurrence.key(), state,
			selected.isDerivedFedFout(), selected.getFedOutType());
	}

	/**
	 * Materialize the child selected by DP's conflict/output resolver for the exact
	 * occurrence retained by the parent edge.  The enumerated exact edge proves the
	 * occurrence and consumable boundary, but its producer ExecType is not a parent
	 * requirement (for example FED/LOUT and CP/LOUT are the same local input value).
	 */
	private static SelectedChildResolution resolveExactTraversalChild(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan, int childOrdinal,
		TraversalDependencyLedger ledger, Map<Long,FederatedOutput> outputDecisions,
		Map<Long,ConflictEntry> conflicts) {
		List<Pair<Long,FederatedOutput>> declarations = parentPlan.getChildFedPlans();
		List<FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge> exactEdges =
			parentPlan.getExactChildPlanEdges();
		if(childOrdinal < 0 || childOrdinal >= declarations.size()
			|| exactEdges.size() != declarations.size())
			throw new IllegalArgumentException("DP exact child ordinal is outside the retained parent arm");
		Pair<Long,FederatedOutput> declaration = declarations.get(childOrdinal);
		FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge exact = exactEdges.get(childOrdinal);
		if(exact.carrier().getHopID() != declaration.getKey() || exact.output() != declaration.getValue())
			throw new IllegalStateException("DP exact child coordinate differs from its legacy declaration");
		PlacementAnalysis.HopOccurrenceProjection occurrence =
			memoTable.requirePlanCarrierOccurrence(exact.carrier());
		if(occurrence.key() != exact.occurrence())
			throw new IllegalStateException("DP exact child edge changed occurrence authority");
		long originalHopId = memoTable.resolveOriginalHopId(declaration.getKey());
		FederatedOutput desired = outputDecisions == null
			? declaration.getValue()
			: outputDecisions.getOrDefault(originalHopId, declaration.getValue());
		if(desired != declaration.getValue())
			return null;
		SelectedChildResolution selected = resolveSelectedChild(memoTable, declaration, desired,
			outputDecisions, conflicts, RewriteMutationMode.CAPTURE_ONLY, ledger);
		if(selected == null || selected.occurrence() != occurrence
			|| selected.key() != exact.occurrence()
			|| selected.selectedOutput() != exact.output()
			|| !sameConsumableBoundary(
				selectedState(selected.canonicalOwnerPlan()), selectedState(exact.selectedPlan())))
			return null;
		return selected;
	}

	/**
	 * Resolve a virtual/recompile child to the physical analysis-owned memo arm with the same exact placement state.
	 * Output alone is insufficient because one LOUT bucket may retain both CP/LOUT and FED/LOUT variants.
	 */
	private static FederatedPlannerDpMemoTable.FedPlan exactCanonicalOwnerPlan(
		FederatedPlannerDpMemoTable memoTable, PlacementAnalysis.HopOccurrenceProjection occurrence,
		FederatedPlannerDpMemoTable.FedPlan selected) {
		PlacementState state = Objects.requireNonNull(selected.getSelectedPlacementState(),
			"DP selected virtual child has no exact placement state");
		List<FederatedPlannerDpMemoTable.OccurrencePlanArm> retainedArms =
			memoTable.getAllExactPlanVariantsForOccurrence(occurrence);
		FederatedPlannerDpMemoTable.FedPlan bestPhysical = null;
		FederatedPlannerDpMemoTable.FedPlan bestDirect = null;
		FederatedPlannerDpMemoTable.FedPlan bestConcrete = null;
		for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm : retainedArms) {
			FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
			if(memoTable.requirePlanCarrierOccurrence(candidate.getHopRef()) != occurrence
				|| candidate.getSelectedPlacementState() != state
				|| candidate.isDerivedFedFout() != selected.isDerivedFedFout()
				|| !matchesDirectAuthority(candidate, selected.getDirectCandidateSelection(),
					selected.getDirectRelocationChoices()))
				continue;
			// Canonical owner identity is shared by every raw/recompiled carrier of this
			// exact occurrence. The provisional global output map must not split that owner:
			// raw carrier traversal keeps its own executable child edges, while this arm is
			// only the deterministic occurrence-level ownership receipt.
			// RewireOccurrenceSnapshot calls these carriers "physical clones": they are
			// the concrete executable replacement for the analysis occurrence, not a
			// speculative/virtual DP arm.  Prefer that unique carrier whenever present;
			// the direct analysis carrier is only the fallback for an unreplaced Hop.
			boolean physicalReplacement = !memoTable.isVirtualClone(candidate.getHopID())
				&& candidate.getHopRef() != occurrence.hop()
				&& memoTable.resolveOriginalHopId(candidate.getHopID()) == occurrence.hop().getHopID();
			if(physicalReplacement) {
				if(bestPhysical == null || preferRetainedArm(candidate, bestPhysical))
					bestPhysical = candidate;
			}
			else if(candidate.getHopRef() == occurrence.hop()
				&& (bestDirect == null || preferRetainedArm(candidate, bestDirect)))
				bestDirect = candidate;
			// Recompiled TRead carriers can be the sole concrete executable arm for the
			// exact occurrence without being object-identical to the frozen analysis Hop
			// or registered as an original-id replacement.  Exact occurrence ownership,
			// matching state/authority, and non-virtual carrier status are sufficient for
			// the final forest certificate; do not discard that only legal owner arm.
			else if(!memoTable.isVirtualClone(candidate.getHopID())
				&& (bestConcrete == null || preferRetainedArm(candidate, bestConcrete)))
				bestConcrete = candidate;
		}
		FederatedPlannerDpMemoTable.FedPlan strict = bestPhysical != null
			? bestPhysical : bestDirect != null ? bestDirect : bestConcrete;
		if(strict != null)
			return strict;
		return exactStructuralTReadOwnerAlias(memoTable, occurrence, selected, retainedArms);
	}

	/**
	 * A recompile traversal may expose a zero-child virtual TRead carrier with a synthetic
	 * zero-input candidate receipt.  That carrier is edge-scheduling authority only: the
	 * analysis-owned TRead arm, with its logical TWrite child, is the plan actually lowered.
	 *
	 * <p>This is deliberately narrower than candidate-authority equivalence.  It does not
	 * wildcard a missing candidate or relocation for executable FED/FOUT arms; it only
	 * recognizes a non-emitting CP/LOUT transient-read alias whose receipt restates the
	 * already-selected local emission and has no inputs, relocations, or executable children.</p>
	 */
	private static FederatedPlannerDpMemoTable.FedPlan exactStructuralTReadOwnerAlias(
		FederatedPlannerDpMemoTable memoTable, PlacementAnalysis.HopOccurrenceProjection occurrence,
		FederatedPlannerDpMemoTable.FedPlan selected,
		List<FederatedPlannerDpMemoTable.OccurrencePlanArm> retainedArms) {
		PlacementState state = selected.getSelectedPlacementState();
		CandidateSelectionReceipt aliasReceipt = selected.getDirectCandidateSelection();
		if(!memoTable.isVirtualClone(selected.getHopID()) || !isTransientReadPlan(selected)
			|| state.execType() != ExecType.CP || state.output() != FederatedOutput.LOUT
			|| state.fType() != null || selected.isDerivedFedFout()
			|| !selected.getChildFedPlans().isEmpty()
			|| aliasReceipt == null || !selected.getDirectRelocationChoices().isEmpty()
			|| aliasReceipt.rule().parentOccurrence() != occurrence.key()
			|| !aliasReceipt.rule().orderedInputs().isEmpty()
			|| aliasReceipt.emission().emissionState().placementState() != state
			|| aliasReceipt.emission().emissionState().derivedFedFout()
			|| aliasReceipt.emission().executionFType() != null)
			return null;

		FederatedPlannerDpMemoTable.FedPlan bestPhysical = null;
		FederatedPlannerDpMemoTable.FedPlan bestDirect = null;
		FederatedPlannerDpMemoTable.FedPlan bestConcrete = null;
		for(FederatedPlannerDpMemoTable.OccurrencePlanArm arm : retainedArms) {
			FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
			if(candidate == selected || memoTable.isVirtualClone(candidate.getHopID())
				|| !isTransientReadPlan(candidate)
				|| memoTable.requirePlanCarrierOccurrence(candidate.getHopRef()) != occurrence
				|| candidate.getSelectedPlacementState() != state
				|| candidate.isDerivedFedFout() || candidate.getFedOutType() != FederatedOutput.LOUT
				|| candidate.getDirectCandidateSelection() != null
				|| !candidate.getDirectRelocationChoices().isEmpty()
				|| candidate.getChildFedPlans().isEmpty()
				|| candidate.getChildFedPlans().stream().anyMatch(edge -> edge.getRight() != FederatedOutput.LOUT))
				continue;
			boolean physicalReplacement = candidate.getHopRef() != occurrence.hop()
				&& memoTable.resolveOriginalHopId(candidate.getHopID()) == occurrence.hop().getHopID();
			if(physicalReplacement) {
				if(bestPhysical == null || preferRetainedArm(candidate, bestPhysical))
					bestPhysical = candidate;
			}
			else if(candidate.getHopRef() == occurrence.hop()) {
				if(bestDirect == null || preferRetainedArm(candidate, bestDirect))
					bestDirect = candidate;
			}
			else if(bestConcrete == null || preferRetainedArm(candidate, bestConcrete))
				bestConcrete = candidate;
		}
		return bestPhysical != null ? bestPhysical : bestDirect != null ? bestDirect : bestConcrete;
	}

	private static boolean matchesDirectAuthority(FederatedPlannerDpMemoTable.FedPlan candidate,
		CandidateSelectionReceipt expectedCandidate, List<RelocationChoiceReceipt> expectedRelocations) {
		return exactDirectAuthority(candidate.getDirectCandidateSelection(),
			candidate.getDirectRelocationChoices(), expectedCandidate, expectedRelocations);
	}

	static boolean exactDirectAuthority(CandidateSelectionReceipt actualCandidate,
		List<RelocationChoiceReceipt> actualRelocations,
		CandidateSelectionReceipt expectedCandidate,
		List<RelocationChoiceReceipt> expectedRelocations) {
		return Objects.equals(actualCandidate, expectedCandidate)
			&& Objects.equals(actualRelocations, expectedRelocations);
	}

	private static void scheduleTraversalEdges(OrdinaryComponentId component,
		TraversalDependencyLedger ledger, OwnerComponentIndex ownerIndex,
		FederatedPlannerDpMemoTable.FedPlan effectivePlan, FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions, Map<Long, ConflictEntry> conflicts,
		Set<CompiledHopKey> scheduleVisited) {
		PlacementAnalysis.HopOccurrenceProjection parentOccurrence =
			memoTable.requirePlanCarrierOccurrence(effectivePlan.getHopRef());
		if(parentOccurrence != ownerIndex.occurrence(parentOccurrence.key()))
			throw new IllegalStateException("DP traversal parent is not the exact analysis occurrence: "
				+ parentOccurrence.key());
		if(!scheduleVisited.add(parentOccurrence.key()))
			return;
		List<Pair<Long, FederatedOutput>> children = effectivePlan.getChildFedPlans();
		for(int childOrdinal = 0; childOrdinal < children.size(); childOrdinal++) {
			Pair<Long, FederatedOutput> declaration = children.get(childOrdinal);
			long childOriginalId = memoTable.resolveOriginalHopId(declaration.getKey());
			FederatedOutput selectionInput = outputDecisions.getOrDefault(childOriginalId,
				declaration.getValue());
			SelectedChildResolution child = resolveExactTraversalChild(
				memoTable, effectivePlan, childOrdinal, ledger, outputDecisions, conflicts);
			if(child == null)
				throw new IllegalStateException("DP exact traversal schedule has no selected child plan: parent="
					+ parentOccurrence.key() + " ordinal=" + childOrdinal
					+ " declaration=" + declaration + " selectionInput=" + selectionInput
					+ " exactEdge=" + effectivePlan.getExactChildPlanEdges().get(childOrdinal)
					+ " resolution=" + exactTraversalResolutionDiagnostic(memoTable,
						effectivePlan.getExactChildPlanEdges().get(childOrdinal), ledger)
					+ " variants=" + memoTable.getFedPlanVariants(
						effectivePlan.getExactChildPlanEdges().get(childOrdinal).carrier(),
						effectivePlan.getExactChildPlanEdges().get(childOrdinal).output()));
			OrdinaryComponentId childOwner = ownerIndex.owner(child.key());
			if(childOwner != null && childOwner != component)
				child = ledger.declareForeignDependency(
					component, child, memoTable, outputDecisions);
			ledger.verifyBoundaryLock(child);
			ExactTraversalEdge scheduled = ledger.schedule(
				component, effectivePlan, parentOccurrence, childOrdinal, declaration, child);
			// A non-null foreign owner is an exact component boundary. An ownerless child
			// is an already-selected boundary lock, not a foreign component: rewrite still
			// traverses through it to complete any unselected descendants, so scheduling
			// must mirror that traversal.
			if(scheduled.owner != null && scheduled.owner != component) {
				continue;
			}
			scheduleTraversalEdges(component, ledger, ownerIndex, child.plan(), memoTable,
				outputDecisions, conflicts, scheduleVisited);
		}
	}

	private static String exactTraversalResolutionDiagnostic(FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan.ExactChildPlanEdge edge,
		TraversalDependencyLedger ledger) {
		SelectedDpState expected = selectedState(edge.selectedPlan());
		SelectedDpState lock = ledger == null ? null : ledger.selectionLock(edge.occurrence());
		PlacementAnalysis.HopOccurrenceProjection occurrence =
			memoTable.requirePlanCarrierOccurrence(edge.carrier());
		return "expected={" + exactStateDiagnostic(expected) + "},lock={"
			+ (lock == null ? "-" : exactStateDiagnostic(lock)) + "},arms="
			+ memoTable.getAllExactPlanVariantsForOccurrence(occurrence).stream()
				.map(arm -> compactPlanArm(arm.plan())).toList();
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<CompiledHopKey> visitedPlanHops,
		Map<Long, FType> fTypeMap) {

		rewriteHop(plan, memoTable, outputDecisions, visitedPlanHops, fTypeMap, null, false, null, null);
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<CompiledHopKey> visitedPlanHops,
		Map<Long, FType> fTypeMap,
		Map<Long, ConflictEntry> rewriteConflictCheckMap) {

		rewriteHop(plan, memoTable, outputDecisions, visitedPlanHops, fTypeMap, rewriteConflictCheckMap, false, null, null);
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<CompiledHopKey> visitedPlanHops,
		Map<Long, FType> fTypeMap,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		boolean allowOutputDecisionOverride,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests,
		Map<CompiledHopKey, SelectedDpState> selectedStates) {
		rewriteHop(plan, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
			rewriteConflictCheckMap, allowOutputDecisionOverride, localMaterializeRequests,
			selectedStates, null);
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<CompiledHopKey> visitedPlanHops,
		Map<Long, FType> fTypeMap,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		boolean allowOutputDecisionOverride,
		Map<Long, LocalMaterializeRequest> localMaterializeRequests,
		Map<CompiledHopKey, SelectedDpState> selectedStates,
		CaptureTraversalContext traversalContext) {

		long planHopId = plan.getHopRef().getHopID();

		long origHopId = memoTable.resolveOriginalHopId(planHopId);
		FederatedOutput desiredOut = outputDecisions.getOrDefault(origHopId, plan.getFedOutType());

		FederatedPlannerDpMemoTable.FedPlan effectivePlan;
		if(traversalContext != null) {
			// Scheduling already resolved this exact occurrence arm from the component
			// assignment.  Do not invoke the legacy Hop-id selector a second time.
			PlacementAnalysis.HopOccurrenceProjection scheduledOccurrence =
				memoTable.requirePlanCarrierOccurrence(plan.getHopRef());
			SelectedDpState scheduledLock = traversalContext.ledger.selectionLock(
				scheduledOccurrence.key());
			boolean foreignBoundary = traversalContext.incomingEdge != null
				&& traversalContext.incomingEdge.owner != null
				&& traversalContext.incomingEdge.owner != traversalContext.component;
			// A parent component consumes only the foreign child's value boundary.  In
			// particular, CP/LOUT and FED/LOUT are the same local input contract even
			// though the foreign owner must later capture exactly one of those producer
			// states.  The consumed edge above retains the raw parent arm, while the owner
			// receipt independently verifies the canonical producer authority.  Comparing
			// producer ExecType here therefore rejects a coherent cross-component forest.
			if(scheduledLock != null && !(foreignBoundary
				? sameConsumableBoundary(scheduledLock, selectedState(plan))
				: sameExactAuthority(scheduledLock, selectedState(plan))))
				throw new IllegalStateException("DP scheduled exact arm drifted before capture: "
					+ scheduledOccurrence.key() + " foreignBoundary=" + foreignBoundary
					+ " scheduled={" + exactStateDiagnostic(scheduledLock) + "} actual={"
					+ exactStateDiagnostic(selectedState(plan)) + "}");
			effectivePlan = plan;
		}
		else
			effectivePlan = selectRewritePlanVariant(
				memoTable, planHopId, desiredOut, plan.getFedOutType(), plan, outputDecisions,
				rewriteConflictCheckMap, allowOutputDecisionOverride,
				selectedStates == null ? RewriteMutationMode.APPLY : RewriteMutationMode.CAPTURE_ONLY);
		PlacementAnalysis.HopOccurrenceProjection effectiveOccurrence =
			memoTable.requirePlanCarrierOccurrence(effectivePlan.getHopRef());
		CompiledHopKey occurrenceKey = effectiveOccurrence.key();
		boolean differentOwnerBoundary = false;
		if(selectedStates != null) {
			if(traversalContext == null)
				coalesceSelectedState(selectedStates, occurrenceKey, selectedState(effectivePlan));
			else {
				if(traversalContext.incomingRoot != null)
					traversalContext.ledger.consumeRoot(traversalContext.incomingRoot,
						traversalContext.incomingRoot.seedPlan,
						effectivePlan, effectiveOccurrence);
				OrdinaryComponentId owner = traversalContext.ledger.ownerIndex.owner(occurrenceKey);
				boolean exactVisitedRevisit = visitedPlanHops != null && visitedPlanHops.contains(occurrenceKey);
				if(owner == traversalContext.component) {
					SelectedDpState ownerLock = traversalContext.ledger.selectionLock(occurrenceKey);
					FederatedPlannerDpMemoTable.FedPlan ownerPlan = ownerLock == null
						? null : ownerLock.retainedPlan();
					if(ownerPlan == null
						|| memoTable.requirePlanCarrierOccurrence(ownerPlan.getHopRef()) != effectiveOccurrence
						|| !sameExactAuthority(ownerLock, selectedState(ownerPlan))
						|| !samePhysicalAuthority(ownerLock, selectedState(effectivePlan)))
						throw new IllegalStateException("DP exact traversal lacks canonical owner arm: "
							+ occurrenceKey);
					coalesceSelectedState(selectedStates, occurrenceKey, selectedState(ownerPlan));
					traversalContext.ledger.accountOwner(traversalContext.component, occurrenceKey, effectiveOccurrence,
						ownerPlan, traversalContext.incomingRoot, traversalContext.incomingEdge, exactVisitedRevisit);
				}
				else if(owner != null) {
					if(traversalContext.incomingEdge == null
						|| traversalContext.incomingEdge.owner != owner
						|| traversalContext.incomingEdge.child.key() != occurrenceKey
						|| !traversalContext.incomingEdge.consumed)
						throw new IllegalStateException("DP different-owner occurrence lacks exact incoming edge: "
							+ occurrenceKey);
					differentOwnerBoundary = true;
				}
				else if(traversalContext.ledger.boundaryLock(occurrenceKey) != null)
					coalesceSelectedState(selectedStates, occurrenceKey, selectedState(effectivePlan));
				else
					throw new IllegalStateException("DP capture encountered an unowned occurrence: " + occurrenceKey);
			}
		}
		if(differentOwnerBoundary)
			return;
		if (visitedPlanHops != null && !visitedPlanHops.add(occurrenceKey))
			return;

		if (FederatedPlannerTrace.shouldTrace(memoTable.resolveOriginalHop(planHopId))) {
			FederatedPlannerTrace.log(memoTable.resolveOriginalHop(planHopId), "DP-Rewrite-Plan",
				String.format(Locale.ROOT,
					"planHop=%d origHop=%d desiredOut=%s effectiveExec=%s effectiveOut=%s effectiveCost=%.6f derivedFedFout=%s childEdges=%s",
					planHopId, origHopId, desiredOut,
					effectivePlan.getExecType(), effectivePlan.getFedOutType(),
					effectivePlan.getCumulativeCost(), effectivePlan.isDerivedFedFout(),
					effectivePlan.getChildFedPlans()));
		}

		PlacementAnalysis.HopOccurrenceProjection parentOccurrence =
			memoTable.requirePlanCarrierOccurrence(effectivePlan.getHopRef());
		for (int childOrdinal = 0; childOrdinal < effectivePlan.getChildFedPlans().size(); childOrdinal++) {
			Pair<Long, FederatedOutput> childFedPlanPair = effectivePlan.getChildFedPlans().get(childOrdinal);
			long childHopID = childFedPlanPair.getKey();
			long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
			FederatedOutput childDesiredOut = outputDecisions.getOrDefault(childOrigHopID,
				childFedPlanPair.getValue());
			SelectedChildResolution childResolution = traversalContext == null
				? resolveSelectedChild(memoTable, childFedPlanPair,
					childDesiredOut, outputDecisions, rewriteConflictCheckMap,
					selectedStates == null ? RewriteMutationMode.APPLY : RewriteMutationMode.CAPTURE_ONLY,
					null)
				: traversalContext.ledger.scheduledChild(traversalContext.component,
					effectivePlan, childOrdinal, childFedPlanPair);
			FederatedPlannerDpMemoTable.FedPlan childPlan = childResolution == null ? null : childResolution.plan();
				if (childPlan == null) {
					if(traversalContext != null)
						throw new IllegalStateException("DP exact traversal edge has no selected child plan: parent="
							+ parentOccurrence.key() + " ordinal=" + childOrdinal);
					FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, effectivePlan, memoTable);
					continue;
				}
				ExactTraversalEdge incoming = traversalContext == null ? null
					: traversalContext.ledger.consume(traversalContext.component, effectivePlan,
						parentOccurrence, childOrdinal, childFedPlanPair, childResolution);
					collectDpLocalMaterializeRequest(
						memoTable, effectivePlan, childFedPlanPair, childPlan, localMaterializeRequests);
					rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap,
						rewriteConflictCheckMap, false, localMaterializeRequests, selectedStates,
						traversalContext == null ? null
							: new CaptureTraversalContext(traversalContext.component,
								traversalContext.ledger, null, incoming));
			}

			Hop hopRef = effectivePlan.getHopRef();
			Hop targetHop = memoTable.resolveOriginalHop(planHopId);
			if (targetHop == null)
				targetHop = hopRef;

			ExecType execType = effectivePlan.getExecType();
			if (execType == null)
				throw new DMLRuntimeException("ExecType is null in FedPlan for hop " + planHopId + " / " + hopRef.getOpString());
			FederatedOutput outType = effectivePlan.getFedOutType();
			boolean derivedFedFout = execType == ExecType.FED
				&& outType == FederatedOutput.FOUT
				&& effectivePlan.isDerivedFedFout();
			boolean applyStateToTargetHop = shouldApplyRewriteStateToTargetHop(
				memoTable, planHopId, hopRef, targetHop, execType, outType, derivedFedFout);
				if(selectedStates == null) {
					applyPlannedHopState(hopRef, execType, outType, derivedFedFout);
					if(targetHop != hopRef && applyStateToTargetHop)
						applyPlannedHopState(targetHop, execType, outType, derivedFedFout);
				}
				else if(applyStateToTargetHop) {
					// Exact selection was recorded before traversal using the analysis-owned carrier occurrence.
				}
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
		Map<CompiledHopKey, SelectedDpState> selectedStates,
		Set<CompiledHopKey> aggregateExplicitClosure,
		List<DeferredOutputDecisionReceipt> deferredOutputDecisionReceipts) {

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

				FederatedPlannerDpMemoTable.FedPlan selectedPlan =
					selectCompatiblePlanVariant(memoTable, origHopID, desiredOut, outputDecisions);
				if (selectedPlan == null)
					selectedPlan = memoTable.getFedPlanAfterPrune(origHopID, desiredOut);
				if (selectedPlan != null) {
					selectedPlan = selectLoopAwareCloneFamilyRewritePlan(
						memoTable, origHopID, selectedPlan, outputDecisions, rewriteConflictCheckMap,
						selectedStates == null ? RewriteMutationMode.APPLY : RewriteMutationMode.CAPTURE_ONLY);
				}
				if (selectedPlan == null) {
					String msg = "Missing deferred output-decision plan for hop " + origHopID
						+ " desiredOut=" + desiredOut;
				if (OptimizerUtils.isStrictFederatedConflictCheck())
					throw new DMLRuntimeException(msg);
				FederatedPlannerLogger.logNullFedPlanError(origHopID, msg);
				continue;
			}
			CompiledHopKey selectedKey = memoTable.requirePlanCarrierOccurrence(selectedPlan.getHopRef()).key();
			SelectedDpState proposed = selectedState(selectedPlan);
			SelectedDpState existing = selectedStates == null ? null : selectedStates.get(selectedKey);
			if(existing != null) {
				coalesceSelectedState(selectedStates, selectedKey, proposed);
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
			else {
				coalesceSelectedState(selectedStates, selectedKey, proposed);
				if(deferredOutputDecisionReceipts != null
					&& !aggregateExplicitClosure.contains(selectedKey)) {
					PlacementAnalysis.HopOccurrenceProjection occurrence =
						memoTable.requirePlanCarrierOccurrence(selectedPlan.getHopRef());
					deferredOutputDecisionReceipts.add(new DeferredOutputDecisionReceipt(
						deferredOutputDecisionReceipts.size(), decisionHopID, origHopID, desiredOut,
						occurrence, selectedKey, selectedPlan, selectedPlan.getHopRef(),
						proposed.exactState(), proposed.derivedFedFout()));
				}
			}
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

	private static List<CompiledHopKey> reclassifySupersededPreCompletionAuthority(
		List<CompiledHopKey> initialAppliedTraversalKeys,
		List<DeferredOutputDecisionReceipt> deferredReceipts,
		Set<CompiledHopKey> supersededKeys,
		Map<CompiledHopKey, SelectedDpState> finalSelectedStates) {
		if(supersededKeys.isEmpty())
			return List.of();

		Set<CompiledHopKey> appliedKeys = Collections.newSetFromMap(new IdentityHashMap<>());
		appliedKeys.addAll(initialAppliedTraversalKeys);
		Set<CompiledHopKey> deferredKeys = Collections.newSetFromMap(new IdentityHashMap<>());
		for(DeferredOutputDecisionReceipt receipt : deferredReceipts)
			deferredKeys.add(receipt.key());
		for(CompiledHopKey key : supersededKeys) {
			if(!appliedKeys.contains(key) && !deferredKeys.contains(key))
				throw new IllegalStateException(
					"DP exact-legality repair reopened a key without pre-completion authority: " + key);
			SelectedDpState selected = identityMapValue(finalSelectedStates, key);
			if(selected == null)
				throw new IllegalStateException(
					"DP exact-legality repair did not replace a superseded authority: " + key);
			FederatedPlannerTrace.logGlobal("DP-PreCompletion-LegalitySuperseded",
				"key=" + compactOccurrence(key)
					+ " category=" + (appliedKeys.contains(key) ? "APPLIED_TRAVERSAL" : "DEFERRED")
					+ " final=" + selected.exactState().normalizedSignature());
		}

		List<DeferredOutputDecisionReceipt> retained = new ArrayList<>();
		for(DeferredOutputDecisionReceipt receipt : deferredReceipts) {
			if(supersededKeys.contains(receipt.key()))
				continue;
			retained.add(new DeferredOutputDecisionReceipt(
				retained.size(), receipt.decisionHopId(), receipt.originalHopId(),
				receipt.desiredOutput(), receipt.occurrence(), receipt.key(), receipt.plan(),
				receipt.planningHop(), receipt.state(), receipt.derivedFedFout()));
		}
		deferredReceipts.clear();
		deferredReceipts.addAll(retained);
		return supersededKeys.stream().sorted().toList();
	}

	private static void reconcileDeferredOutputDecisionReceipts(
		FederatedPlannerDpMemoTable memoTable,
		Map<CompiledHopKey, SelectedDpState> selectedStates,
		List<DeferredOutputDecisionReceipt> receipts) {
		for(int ordinal = 0; ordinal < receipts.size(); ordinal++) {
			DeferredOutputDecisionReceipt preliminary = receipts.get(ordinal);
			SelectedDpState selected = identityMapValue(selectedStates, preliminary.key());
			if(selected == null || selected.retainedPlan() == null)
				throw new IllegalStateException(
					"DP final exact forest omitted a deferred output-decision authority: "
						+ preliminary.key());
			FederatedPlannerDpMemoTable.FedPlan plan = selected.retainedPlan();
			PlacementAnalysis.HopOccurrenceProjection occurrence =
				memoTable.requirePlanCarrierOccurrence(plan.getHopRef());
			if(occurrence.key() != preliminary.key()
				|| selected.output() != preliminary.desiredOutput()
				|| memoTable.resolveOriginalHopId(plan.getHopID()) != preliminary.originalHopId())
				throw new IllegalStateException(
					"DP final exact forest changed a deferred output-decision contract: key="
						+ preliminary.key() + " desired=" + preliminary.desiredOutput()
						+ " selected=" + selected.output());
			receipts.set(ordinal, new DeferredOutputDecisionReceipt(
				ordinal, preliminary.decisionHopId(), preliminary.originalHopId(),
				preliminary.desiredOutput(), occurrence, preliminary.key(), plan,
				plan.getHopRef(), selected.exactState(), selected.derivedFedFout()));
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
		FederatedOutput outType,
		boolean derivedFedFout) {

		if (targetHop == null || targetHop == hopRef)
			return true;
		if (memoTable == null || !memoTable.isVirtualClone(planHopId))
			return true;
		FederatedPlannerUtils.PlannerRecompileState existing =
			FederatedPlannerUtils.getPlannerRecompileState(targetHop);
		if (existing == null)
			return true;
		return existing.getExecType() == execType && existing.getFederatedOutput() == outType
			&& existing.isFederatedOutputDerived() == derivedFedFout;
	}

	private static String formatPlannerRecompileState(
		FederatedPlannerUtils.PlannerRecompileState state) {
		if (state == null)
			return "null";
		return state.getExecType() + "/" + state.getFederatedOutput()
			+ "/derived=" + state.isFederatedOutputDerived();
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
		return selectRewritePlanVariant(memoTable, hopID, desiredOut, inheritedOut, fallbackPlan,
			outputDecisions, rewriteConflictCheckMap, allowOutputDecisionOverride, RewriteMutationMode.APPLY);
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectRewritePlanVariant(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedOutput desiredOut,
		FederatedOutput inheritedOut,
		FederatedPlannerDpMemoTable.FedPlan fallbackPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		boolean allowOutputDecisionOverride,
		RewriteMutationMode mutationMode) {

		Objects.requireNonNull(mutationMode, "mutationMode");

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
					memoTable, hopID, selected, outputDecisions, rewriteConflictCheckMap, mutationMode);
		}
		if (inheritedOut != null)
			selected = selectCompatiblePlanVariant(memoTable, hopID, inheritedOut, outputDecisions);
		if (selected == null && desiredOut != null && desiredOut != inheritedOut)
			selected = selectCompatiblePlanVariant(memoTable, hopID, desiredOut, outputDecisions);
		if (selected != null)
			return selectLoopAwareCloneFamilyRewritePlan(
				memoTable, hopID, selected, outputDecisions, rewriteConflictCheckMap, mutationMode);

		FederatedOutput requiredOut = inheritedOut != null ? inheritedOut : desiredOut;
		if (fallbackPlan != null && fallbackPlan.getHopID() == hopID
			&& (requiredOut == null || fallbackPlan.getFedOutType() == requiredOut)
			&& isCompatibleWithChildDecisions(memoTable, fallbackPlan, outputDecisions))
			return selectLoopAwareCloneFamilyRewritePlan(
				memoTable, hopID, fallbackPlan, outputDecisions, rewriteConflictCheckMap, mutationMode);
		return null;
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectLoopAwareCloneFamilyRewritePlan(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedPlannerDpMemoTable.FedPlan selectedPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap) {
		return selectLoopAwareCloneFamilyRewritePlan(memoTable, hopID, selectedPlan, outputDecisions,
			rewriteConflictCheckMap, RewriteMutationMode.APPLY);
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectLoopAwareCloneFamilyRewritePlan(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedPlannerDpMemoTable.FedPlan selectedPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> rewriteConflictCheckMap,
		RewriteMutationMode mutationMode) {

		Objects.requireNonNull(mutationMode, "mutationMode");

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

		if(mutationMode == RewriteMutationMode.APPLY)
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

		DecisionResolutionContext context = new DecisionResolutionContext(memoTable, rootPlan);
		Map<Long, FederatedOutput> decisions = computeOutputDecisionsInternal(
			memoTable, rootPlan, new HashMap<>(), Collections.emptyMap(), true, context);
		DecisionMapScoreBreakdown score = context.scoreCache.get(decisions);
		if (!isExecutableDecisionMapScore(score))
			throw new IllegalStateException("DP output decisions do not form an executable plan forest: "
				+ "missingRoots=" + score.missingRootCount
				+ " incompatiblePlans=" + score.incompatiblePlanCount
				+ " totalCost=" + score.totalCost
				+ " decisions=" + new java.util.TreeMap<>(decisions)
				+ " conflicts=" + new java.util.TreeSet<>(score.exactSelectionConflictHopIDs));
		return decisions;
	}

	private static Map<Long, FederatedOutput> simulateOutputDecisionsWithLocks(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> baseDecisions,
		Map<Long, FederatedOutput> lockedDecisions) {

		return computeOutputDecisionsInternal(memoTable, rootPlan, baseDecisions, lockedDecisions,
			false, new DecisionResolutionContext(memoTable, rootPlan));
	}

	private static Map<Long, FederatedOutput> resolveOutputDecisionsWithLocks(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> baseDecisions,
		Map<Long, FederatedOutput> lockedDecisions) {

		return computeOutputDecisionsInternal(memoTable, rootPlan, baseDecisions, lockedDecisions,
			true, new DecisionResolutionContext(memoTable, rootPlan));
	}

	private static Map<Long, FederatedOutput> simulateOutputDecisionsWithLocksCached(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> baseDecisions,
		Map<Long, FederatedOutput> lockedDecisions,
		SimulationDecisionCache simulationDecisionCache) {

		// Tracing is observational. It must not switch off the same production cache
		// used by performance runs, otherwise planning-only audits exercise a different
		// algorithmic path and can multiply an already expensive decision simulation.
		if (simulationDecisionCache == null)
			return simulateOutputDecisionsWithLocks(memoTable, rootPlan, baseDecisions, lockedDecisions);

		SimulationDecisionKey key = new SimulationDecisionKey(baseDecisions, lockedDecisions);
		Map<Long, FederatedOutput> cached = simulationDecisionCache.get(key);
		if (cached != null)
			return new HashMap<>(cached);

		Map<Long, FederatedOutput> computed = computeOutputDecisionsInternal(
			memoTable, rootPlan, baseDecisions, lockedDecisions, false, simulationDecisionCache.context);
		simulationDecisionCache.put(key, computed);
		return new HashMap<>(computed);
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
		boolean allowTransientFamilyRefine,
		DecisionResolutionContext context) {

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
		DecisionResolutionContext resolutionContext = context != null
			? context.requireOwner(memoTable, rootPlan)
			: new DecisionResolutionContext(memoTable, rootPlan);
		ParentVariantDeltaCache parentVariantDeltaCache = new ParentVariantDeltaCache();
		TransientReadParentsCache transientReadParentsCache = resolutionContext.transientReadParentsCache;
		SimulationDecisionCache simulationDecisionCache = resolutionContext.simulationDecisionCache;
		DecisionMapScoreCache decisionMapScoreCache = resolutionContext.scoreCache;
		// Initial and locked maps may be partial. Keep an executable initial map only as
		// a fallback: an executable refinement still takes precedence because it may add
		// required virtual-root/family closure decisions. ALS exposed the fallback need
		// when a refinement moved one exact conflict from a predicate to its TWrite
		// without reducing the conflict count.
		Map<Long, FederatedOutput> initialExecutableDecisions =
			isExecutableDecisionMapScore(computeDecisionMapScoreBreakdown(
				memoTable, rootPlan, decisions, decisionMapScoreCache))
				? new HashMap<>(decisions) : null;
		Map<Long, FederatedOutput> bestDecisions = null;

		Map<Long, ConflictEntry> conflictCheckMap = resolutionContext.conflictMapCache.getFeasible(decisions);
		for (int iter = 0; iter < maxIters; iter++) {
			Map<Long, FederatedOutput> nextDecisions = new HashMap<>();
			if (lockedDecisions != null && !lockedDecisions.isEmpty())
				nextDecisions.putAll(lockedDecisions);

			for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
				long hopID = e.getKey();
				ConflictEntry entry = e.getValue();

				if (!entry.seenLOUT && !entry.seenFOUT)
					continue;

				FederatedOutput lockedChoice = lockedDecisions != null ? lockedDecisions.get(hopID) : null;
				Hop hopRef = memoTable.resolveOriginalHop(hopID);
				if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine) {
					final int traceIter = iter;
					FederatedPlannerTrace.logLazy(hopRef, "DP-OutputDecision-Entry", () -> String.format(Locale.ROOT,
						"iter=%d seenLOUT=%s seenFOUT=%s canLOUT=%s canFOUT=%s members=%d parents=%d",
						traceIter, entry.seenLOUT, entry.seenFOUT, entry.canChooseLOUT, entry.canChooseFOUT,
						entry.memberHopIDs != null ? entry.memberHopIDs.size() : 0,
						entry.parents != null ? entry.parents.size() : 0));
				}

				boolean isTransientWrite = hopRef instanceof DataOp
					&& ((DataOp) hopRef).getOp() == Types.OpOpData.TRANSIENTWRITE;
					if (lockedChoice != null) {
						nextDecisions.put(hopID, lockedChoice);
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
						chosen = enforceLockedOutputClosureFeasibility(
							memoTable, hopID, entry, chosen, conflictCheckMap, lockedDecisions);
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
					boolean forceSeenOnlyReeval =
						forceCompatibleVariantReeval || forceCheaperAlternativeReeval;
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
				chosen = enforceLockedOutputClosureFeasibility(
					memoTable, hopID, entry, chosen, conflictCheckMap, lockedDecisions);

				if (chosen != null)
					nextDecisions.put(hopID, chosen);
			}

			if (allowTransientFamilyRefine) {
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, conflictCheckMap, nextDecisions, lockedDecisions,
					transientReadParentsCache, iter);
				nextDecisions = refineTransientFamilyDecisions(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, lockedDecisions, iter,
					simulationDecisionCache, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, conflictCheckMap, nextDecisions, lockedDecisions,
					transientReadParentsCache, iter);
				nextDecisions = refineRequiredOutputClosureDecisions(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, lockedDecisions,
					iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, conflictCheckMap, nextDecisions, lockedDecisions,
					transientReadParentsCache, iter);
				nextDecisions = normalizeMultiWriteTransientVariableFamilies(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, lockedDecisions, iter,
					simulationDecisionCache, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				// Multi-write normalization may change a producer after the first closure pass.
				// Re-close the resulting map before scoring it: rewrite cannot assign one exact
				// compiled occurrence both the inherited edge state and a deferred global state.
				nextDecisions = refineRequiredOutputClosureDecisions(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, lockedDecisions,
					iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = refineExactOccurrenceSelectionDecisions(
					memoTable, rootPlan, nextDecisions, lockedDecisions, iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				// Required-output and exact-occurrence closure can revisit a member after the
				// transient-family pass. Rebuild the actually selected forest and let DP's
				// existing conflict resolver make the final local family decision. Merely
				// realigning TReads is insufficient because dependent consumers can still
				// retain the output contract selected by the superseded family state.
				Map<Long, ConflictEntry> finalConflictCheckMap =
					resolutionContext.conflictMapCache.getFeasible(nextDecisions);
				nextDecisions = refineTransientFamilyDecisions(
					memoTable, rootPlan, finalConflictCheckMap, nextDecisions, lockedDecisions, iter,
					simulationDecisionCache, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, finalConflictCheckMap, nextDecisions, lockedDecisions,
					transientReadParentsCache, iter);
				Map<Long, ConflictEntry> alignedConflictCheckMap =
					resolutionContext.conflictMapCache.getFeasible(nextDecisions);
				nextDecisions = refineRequiredOutputClosureDecisions(
					memoTable, rootPlan, alignedConflictCheckMap, nextDecisions, lockedDecisions,
					iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				// The family alignment above can expose a parent whose previously selected
				// exact arm requires the superseded TRead output. Feed that newly visible
				// incompatibility back through DP's existing exact-conflict detector/resolver
				// instead of overriding the transient-family decision or pruning the arm.
				nextDecisions = refineExactOccurrenceSelectionDecisions(
					memoTable, rootPlan, nextDecisions, lockedDecisions, iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = alignTransientReadsWithProducerDecisions(
					memoTable, alignedConflictCheckMap, nextDecisions, lockedDecisions,
					transientReadParentsCache, iter);
				Map<Long, ConflictEntry> finalAlignedConflictCheckMap =
					resolutionContext.conflictMapCache.getFeasible(nextDecisions);
				nextDecisions = refineRequiredOutputClosureDecisions(
					memoTable, rootPlan, finalAlignedConflictCheckMap, nextDecisions, lockedDecisions,
					iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				nextDecisions = refineExactOccurrenceSelectionDecisions(
					memoTable, rootPlan, nextDecisions, lockedDecisions, iter, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
				// A synthetic function-input boundary may feed multiple exact formal
				// TRead occurrences that are not one transient TWrite/TRead family. The
				// neutral graph nevertheless proves that those formals are aliases of one
				// runtime value. Resolve that graph-owned legality family with the same
				// executable-forest score used by the existing DP conflict refinements;
				// do not force the caller argument to match because the conjunctive
				// argument edge may legally materialize FOUT to LOUT.
				nextDecisions = alignFunctionFormalInputDecisions(
					memoTable, rootPlan, nextDecisions, lockedDecisions, iter,
					simulationDecisionCache, decisionMapScoreCache);
				nextDecisions = applyLockedOutputDecisions(nextDecisions, lockedDecisions);
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
			conflictCheckMap = resolutionContext.conflictMapCache.getFeasible(decisions);
			parentVariantDeltaCache.clear();
		}

		return bestDecisions != null ? bestDecisions
			: (initialExecutableDecisions != null ? initialExecutableDecisions : decisions);
	}

	/**
	 * Align every exact formal occurrence owned by one synthetic function-input
	 * boundary to one output placement. This is a graph-declared global legality
	 * closure, not a replacement optimizer: both LOUT and FOUT remain candidates,
	 * recursive DP simulation retains the local recurrence choices, and the existing
	 * complete-forest cost/structure score selects the feasible candidate.
	 */
	private static Map<Long, FederatedOutput> alignFunctionFormalInputDecisions(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions,
		int iter,
		SimulationDecisionCache simulationDecisionCache,
		DecisionMapScoreCache scoreCache) {

		if(memoTable == null || memoTable.analysis() == null || decisions == null)
			return decisions;

		Map<Long, FederatedOutput> aligned = new HashMap<>(decisions);
		for(FederatedPlannerDpMemoTable.FunctionFormalDecisionFamily family :
			memoTable.functionFormalDecisionFamilies()) {
			LinkedHashSet<Long> familyHopIDs = family.formals().stream()
				.map(formal -> memoTable.resolveOriginalHopId(formal.hop().getHopID())).sorted()
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
			if(familyHopIDs.size() <= 1)
				continue;
			List<PlacementState> selectedFormalStates = selectedFunctionFormalInputStates(
				memoTable, family, aligned);
			// A missing output-map entry is not itself a conflict: the selected parent
			// arm can already give every formal the same exact state. Only intervene
			// when the currently selected exact occurrences actually disagree.
			if(selectedFormalStates.size() <= 1)
				continue;

			FederatedOutput coherentCurrent = null;
			boolean currentComplete = true;
			for(long familyHopID : familyHopIDs) {
				FederatedOutput current = aligned.get(familyHopID);
				if(current == null) {
					currentComplete = false;
					break;
				}
				if(coherentCurrent == null)
					coherentCurrent = current;
				else if(coherentCurrent != current) {
					coherentCurrent = null;
					currentComplete = false;
					break;
				}
			}
			if(currentComplete)
				continue;

			Map<Long, FederatedOutput> bestCandidate = null;
			DecisionMapScoreBreakdown bestScore = null;
			FederatedOutput bestOutput = null;
			for(FederatedOutput targetOutput : new FederatedOutput[] {
				FederatedOutput.LOUT, FederatedOutput.FOUT}) {
				Map<Long, FederatedOutput> candidateLocks = lockedDecisions != null
					? new HashMap<>(lockedDecisions) : new HashMap<>();
				boolean feasible = true;
				for(long familyHopID : familyHopIDs) {
					FederatedOutput locked = candidateLocks.get(familyHopID);
					if(locked != null && locked != targetOutput) {
						feasible = false;
						break;
					}
					if(memoTable.getFedPlanAfterPrune(familyHopID, targetOutput) == null) {
						feasible = false;
						break;
					}
					candidateLocks.put(familyHopID, targetOutput);
				}
				if(!feasible)
					continue;

				Map<Long, FederatedOutput> candidate = simulateOutputDecisionsWithLocksCached(
					memoTable, rootPlan, aligned, candidateLocks, simulationDecisionCache);
				for(long familyHopID : familyHopIDs)
					if(candidate.get(familyHopID) != targetOutput) {
						feasible = false;
						break;
					}
				if(!feasible)
					continue;

				DecisionMapScoreBreakdown candidateScore =
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidate, scoreCache);
				List<PlacementState> candidateFormalStates = selectedFunctionFormalInputStates(
					memoTable, family, candidate);
				if(!isExecutableDecisionMapScore(candidateScore)
					|| !isDecisionMapClosureResolved(candidateScore, familyHopIDs)
					|| candidateFormalStates.size() != 1
					|| candidateFormalStates.get(0).output() != targetOutput)
					continue;
				if(bestScore == null || isBetterDecisionMapScore(candidateScore, bestScore)
					|| (hasSameDecisionMapStructure(candidateScore, bestScore)
						&& Math.abs(candidateScore.totalCost - bestScore.totalCost) <= 1e-9
						&& targetOutput == coherentCurrent)) {
					bestCandidate = candidate;
					bestScore = candidateScore;
					bestOutput = targetOutput;
				}
			}

			FederatedPlannerTrace.logGlobal("DP-FunctionFormalInputAlign",
				"iter=" + iter + " boundary=" + family.boundary().normalizedSignature()
					+ " family=" + familyHopIDs + " selected=" + bestOutput
					+ " total=" + (bestScore != null ? bestScore.totalCost : Double.NaN)
					+ " apply=" + (bestCandidate != null));
			if(bestCandidate != null)
				aligned = bestCandidate;
		}
		return aligned;
	}

	private static List<PlacementState> selectedFunctionFormalInputStates(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FunctionFormalDecisionFamily family,
		Map<Long, FederatedOutput> decisions) {

		Set<PlacementState> selected = new LinkedHashSet<>();
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : family.formals()) {
			FederatedPlannerDpMemoTable.FedPlan plan =
				selectLogicalTransientOccurrencePlan(memoTable, occurrence, decisions);
			if(plan != null && plan.getSelectedPlacementState() != null)
				selected.add(plan.getSelectedPlacementState());
		}
		return selected.stream().sorted(Comparator.comparing(PlacementState::normalizedSignature)).toList();
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
		boolean candidateValid = isExecutableDecisionMapScore(candidateScore);
		if (incumbent == null)
			return candidateValid ? new HashMap<>(candidate) : null;

		DecisionMapScoreBreakdown incumbentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, incumbent, scoreCache);
		boolean incumbentValid = isExecutableDecisionMapScore(incumbentScore);
		if (!candidateValid || (incumbentValid
			&& !isBetterDecisionMapScore(candidateScore, incumbentScore)))
			return new HashMap<>(incumbent);
		return new HashMap<>(candidate);
	}

	private static boolean isScorableDecisionMapScore(DecisionMapScoreBreakdown score) {
		return score != null && Double.isFinite(score.totalCost) && score.missingRootCount == 0;
	}

	private static boolean isExecutableDecisionMapScore(DecisionMapScoreBreakdown score) {
		return isScorableDecisionMapScore(score) && score.incompatiblePlanCount == 0;
	}

	/**
	 * Materialize one output decision for every exact compiled occurrence reached
	 * with disagreeing selected states. A missing decision is not a third placement:
	 * rewrite emits one runtime value for the occurrence, so independently inheriting
	 * LOUT and FOUT from two parents is not executable. Both legal output alternatives
	 * remain in the candidate space and the existing structural/cost score chooses the
	 * better complete forest.
	 */
	private static Map<Long, FederatedOutput> refineExactOccurrenceSelectionDecisions(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions,
		int iter,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null)
			return decisions;

		Map<Long, FederatedOutput> refinedDecisions =
			copyOutputDecisionsAndApplyLocks(decisions, lockedDecisions);
		for (int refinement = 0; ; refinement++) {
			DecisionMapScoreBreakdown currentScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, refinedDecisions, scoreCache);
			if (currentScore.exactSelectionConflictHopIDs.isEmpty())
				break;

			Map<Long, FederatedOutput> bestCandidate = null;
			DecisionMapScoreBreakdown bestScore = currentScore;
			List<Long> selectedHopIDs = Collections.emptyList();
			FederatedOutput selectedOutput = null;
			LinkedHashSet<Long> occurrenceFamilyHopIDs = new LinkedHashSet<>();
			for (ExactOccurrenceConflict conflict : currentScore.exactOccurrenceConflicts) {
				List<Long> familyHopIDs = conflict.decisionHopIDs();
				occurrenceFamilyHopIDs.addAll(familyHopIDs);
				for (FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
					Map<Long, FederatedOutput> candidate = applyExactOccurrenceFamilyOutput(
						refinedDecisions, lockedDecisions, familyHopIDs, output);
					if (candidate == null)
						continue;
					DecisionMapScoreBreakdown candidateScore =
						computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidate, scoreCache);
					Hop hopRef = familyHopIDs.isEmpty() ? null : memoTable.resolveOriginalHop(familyHopIDs.get(0));
					if (FederatedPlannerTrace.shouldTrace(hopRef))
						FederatedPlannerTrace.log(hopRef, "DP-ExactOccurrenceFamilyDecision-Candidate",
							"iter=" + iter + " refinement=" + refinement + " occurrence="
								+ conflict.occurrenceKey() + " family=" + familyHopIDs + " output=" + output
								+ " incompatibleBefore=" + currentScore.incompatiblePlanCount
								+ " incompatibleAfter=" + candidateScore.incompatiblePlanCount
								+ " conflictsBefore=" + currentScore.exactSelectionConflictHopIDs
								+ " conflictsAfter=" + candidateScore.exactSelectionConflictHopIDs
								+ " totalBefore=" + currentScore.totalCost
								+ " totalAfter=" + candidateScore.totalCost);
					if (!isScorableDecisionMapScore(candidateScore)
						|| !hasBetterDecisionMapStructure(candidateScore, currentScore))
						continue;
					if (bestCandidate == null || isBetterDecisionMapScore(candidateScore, bestScore)) {
						bestCandidate = candidate;
						bestScore = candidateScore;
						selectedHopIDs = familyHopIDs;
						selectedOutput = output;
					}
				}
			}
			for (long hopID : currentScore.exactSelectionConflictHopIDs) {
				if (occurrenceFamilyHopIDs.contains(hopID))
					continue;
				FederatedOutput lockedOutput = lockedDecisions != null ? lockedDecisions.get(hopID) : null;
				for (FederatedOutput output : List.of(FederatedOutput.LOUT, FederatedOutput.FOUT)) {
					if (lockedOutput != null && lockedOutput != output)
						continue;
					Map<Long, FederatedOutput> candidate = new HashMap<>(refinedDecisions);
					candidate.put(hopID, output);
					applyLockedOutputDecisionsInPlace(candidate, lockedDecisions);
					DecisionMapScoreBreakdown candidateScore =
						computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidate, scoreCache);
					Hop hopRef = memoTable.resolveOriginalHop(hopID);
					if (FederatedPlannerTrace.shouldTrace(hopRef))
						FederatedPlannerTrace.log(hopRef, "DP-ExactOccurrenceDecision-Candidate",
							"iter=" + iter + " refinement=" + refinement + " output=" + output
								+ " incompatibleBefore=" + currentScore.incompatiblePlanCount
								+ " incompatibleAfter=" + candidateScore.incompatiblePlanCount
								+ " conflictsBefore=" + currentScore.exactSelectionConflictHopIDs
								+ " conflictsAfter=" + candidateScore.exactSelectionConflictHopIDs
								+ " totalBefore=" + currentScore.totalCost
								+ " totalAfter=" + candidateScore.totalCost);
					if (!isScorableDecisionMapScore(candidateScore)
						|| !hasBetterDecisionMapStructure(candidateScore, currentScore))
						continue;
					if (bestCandidate == null || isBetterDecisionMapScore(candidateScore, bestScore)) {
						bestCandidate = candidate;
						bestScore = candidateScore;
						selectedHopIDs = List.of(hopID);
						selectedOutput = output;
					}
				}
			}

			if (bestCandidate == null)
				break;
			FederatedPlannerTrace.logGlobal("DP-ExactOccurrenceDecision-Selected",
				"iter=" + iter + " refinement=" + refinement + " hops=" + selectedHopIDs
					+ " output=" + selectedOutput + " incompatibleBefore="
					+ currentScore.incompatiblePlanCount + " incompatibleAfter="
					+ bestScore.incompatiblePlanCount + " totalBefore=" + currentScore.totalCost
					+ " totalAfter=" + bestScore.totalCost);
			refinedDecisions = bestCandidate;
		}
		return refinedDecisions;
	}

	private static Map<Long, FederatedOutput> applyExactOccurrenceFamilyOutput(
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions,
		List<Long> familyHopIDs,
		FederatedOutput output) {

		if (familyHopIDs == null || familyHopIDs.isEmpty() || output == null)
			return null;
		for (long hopID : familyHopIDs) {
			FederatedOutput lockedOutput = lockedDecisions != null ? lockedDecisions.get(hopID) : null;
			if (lockedOutput != null && lockedOutput != output)
				return null;
		}
		Map<Long, FederatedOutput> candidate =
			decisions != null ? new HashMap<>(decisions) : new HashMap<>();
		for (long hopID : familyHopIDs)
			candidate.put(hopID, output);
		applyLockedOutputDecisionsInPlace(candidate, lockedDecisions);
		return candidate;
	}

	private static boolean hasBetterDecisionMapStructure(
		DecisionMapScoreBreakdown candidate, DecisionMapScoreBreakdown incumbent) {
		if (candidate == null)
			return false;
		if (incumbent == null)
			return true;
		if (candidate.missingRootCount != incumbent.missingRootCount)
			return candidate.missingRootCount < incumbent.missingRootCount;
		return candidate.incompatiblePlanCount < incumbent.incompatiblePlanCount;
	}

	private static boolean hasSameDecisionMapStructure(
		DecisionMapScoreBreakdown left, DecisionMapScoreBreakdown right) {
		return left != null && right != null
			&& left.missingRootCount == right.missingRootCount
			&& left.incompatiblePlanCount == right.incompatiblePlanCount;
	}

	private static boolean isDecisionMapClosureResolved(
		DecisionMapScoreBreakdown score, Set<Long> closureHopIDs) {
		return score != null && (closureHopIDs == null || closureHopIDs.isEmpty()
			|| Collections.disjoint(score.exactSelectionConflictHopIDs, closureHopIDs));
	}

	private static boolean isBetterDecisionMapScore(
		DecisionMapScoreBreakdown candidate, DecisionMapScoreBreakdown incumbent) {
		if (candidate == null)
			return false;
		if (incumbent == null)
			return true;
		if (hasBetterDecisionMapStructure(candidate, incumbent))
			return true;
		if (!hasSameDecisionMapStructure(candidate, incumbent))
			return false;
		if (Double.isFinite(candidate.totalCost) != Double.isFinite(incumbent.totalCost))
			return Double.isFinite(candidate.totalCost);
		return candidate.totalCost + 1e-9 < incumbent.totalCost;
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

	private static Map<Long, FederatedOutput> copyOutputDecisionsAndApplyLocks(
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions) {

		Map<Long, FederatedOutput> copy =
			decisions != null ? new HashMap<>(decisions) : new HashMap<>();
		applyLockedOutputDecisionsInPlace(copy, lockedDecisions);
		return copy;
	}

	private static void applyLockedOutputDecisionsInPlace(
		Map<Long, FederatedOutput> ownedDecisions,
		Map<Long, FederatedOutput> lockedDecisions) {

		Objects.requireNonNull(ownedDecisions, "ownedDecisions");
		if(lockedDecisions != null && !lockedDecisions.isEmpty())
			ownedDecisions.putAll(lockedDecisions);
	}

	/**
	 * Conflict refinements are stateful: an accepted candidate becomes the input
	 * to the next candidate.  HashMap bucket order changes when otherwise
	 * identical HOP IDs receive a different absolute offset, which previously
	 * changed the selected plan.  HOPs are allocated producer-first, so ascending
	 * IDs provide a stable producer-to-consumer order without removing candidates.
	 */
	private static List<Map.Entry<Long, ConflictEntry>> sortedConflictEntries(
		Map<Long, ConflictEntry> conflictCheckMap) {
		if(conflictCheckMap == null || conflictCheckMap.isEmpty())
			return List.of();
		return conflictCheckMap.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList();
	}

	private static Map<Long, FederatedOutput> alignTransientReadsWithProducerDecisions(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		Map<Long, FederatedOutput> lockedDecisions,
		TransientReadParentsCache transientReadParentsCache,
		int iter) {

		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| decisions == null || decisions.isEmpty())
			return decisions;

		Map<Long, FederatedOutput> alignedDecisions = new HashMap<>(decisions);
		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
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
				FederatedOutput lockedReadOutput = lockedDecisions != null
					? lockedDecisions.get(tReadHopID) : null;
				if (lockedReadOutput != null && lockedReadOutput != producerDecision) {
					if (FederatedPlannerTrace.shouldTrace(hopRef)) {
						FederatedPlannerTrace.log(hopRef, "DP-TransientReadProducerAlign-Locked",
							String.format(Locale.ROOT,
								"iter=%d tRead=%d producer=%s lockedRead=%s action=preserve-lock",
								iter, tReadHopID, producerDecision, lockedReadOutput));
					}
					continue;
				}
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
	 * <p>Families are proven either by concrete selected-output child edges or by
	 * analysis-owned logical transient reaching-definition facts. The latter are
	 * required for initial/branch definitions that are executable CFG predecessors
	 * but are not children of the currently selected root forest. Both legal
	 * all-LOUT and all-FOUT plans are re-simulated, and the cheaper complete plan
	 * wins. This avoids workload, hop-id, worker-count, and row-shape heuristics.</p>
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

		Map<Long, FederatedOutput> normalizedDecisions =
			copyOutputDecisionsAndApplyLocks(decisions, lockedDecisions);
		// Earlier refinements in the same decision pass can switch a TRead output
		// after conflictCheckMap was collected. Rebuild the selected traversal before
		// discovering concrete producers; otherwise a stale LOUT traversal cannot
		// expose the FOUT clone plan (or vice versa) and a later loop write escapes the
		// multi-write family normalization.
		Map<Long, ConflictEntry> normalizationConflictCheckMap =
			scoreCache.conflicts(normalizedDecisions);
		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(normalizationConflictCheckMap)) {
			long tReadHopID = e.getKey();
			Hop hopRef = memoTable.resolveOriginalHop(tReadHopID);
			if (!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;

			FederatedOutput selectedReadOut = normalizedDecisions.get(tReadHopID);
			if (selectedReadOut == null)
				selectedReadOut = preferredUnobservedConflictOutput(e.getValue());
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
			if (complete && !inconsistent)
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
					candidateConflictCheckMap = scoreCache.conflicts(selectionDecisions);
					// getFeasible has already rebuilt the selected forest and derived exact
					// LOUT/FOUT availability for CFG-only members under this candidate.
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
				if (!isScorableDecisionMapScore(candidateScore)
					|| !isDecisionMapClosureResolved(candidateScore, candidateFamilyHopIDs))
					continue;
				if (bestScore == null || isBetterDecisionMapScore(candidateScore, bestScore)
					|| (hasSameDecisionMapStructure(candidateScore, bestScore)
						&& Math.abs(candidateScore.totalCost - bestScore.totalCost) <= 1e-9
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

	private static FederatedOutput preferredUnobservedConflictOutput(ConflictEntry entry) {
		if (entry == null || entry.selectedMemberPlans == null || entry.selectedMemberPlans.isEmpty())
			return null;
		FederatedPlannerDpMemoTable.FedPlan best = null;
		for (FederatedPlannerDpMemoTable.FedPlan candidate : entry.selectedMemberPlans.values()) {
			if (candidate == null)
				continue;
			if (best == null || candidate.getCumulativeCost() < best.getCumulativeCost()
				|| candidate.getCumulativeCost() == best.getCumulativeCost()
					&& candidate.getFedOutType() == FederatedOutput.LOUT
					&& best.getFedOutType() == FederatedOutput.FOUT)
				best = candidate;
		}
		return best != null ? best.getFedOutType() : null;
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
		producerHopIDs.addAll(tReadEntry.logicalTransientProducerHopIDs);

		for (long memberHopID : selectDecisionMembers(tReadEntry.memberHopIDs, memoTable)) {
			FederatedPlannerDpMemoTable.FedPlan plan = tReadEntry.selectedMemberPlans.get(
				Pair.of(memberHopID, selectedReadOut));
			if (plan == null)
				plan = findStrictCompatiblePlanVariant(
					memoTable, memberHopID, selectedReadOut, outputDecisions);
			if (plan == null && (outputDecisions == null || outputDecisions.isEmpty()))
				plan = memoTable.getFedPlanAfterPrune(memberHopID, selectedReadOut);
			if (plan == null || plan.getChildFedPlans() == null) {
				if (producerHopIDs.isEmpty())
					return new LinkedHashSet<>();
				continue;
			}
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
		Map<Long, FederatedOutput> lockedDecisions,
		int iter,
		DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| nextDecisions == null || nextDecisions.isEmpty())
			return nextDecisions;

		Map<Long, FederatedOutput> refinedDecisions =
			copyOutputDecisionsAndApplyLocks(nextDecisions, lockedDecisions);
		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, refinedDecisions, scoreCache);
		final int numWorkers = Math.max(1, memoTable.getNumWorkers());
		List<Map.Entry<Long, ConflictEntry>> conflictEntries = sortedConflictEntries(conflictCheckMap);
		RequiredOutputClosureSearch closureSearch =
			new RequiredOutputClosureSearch(memoTable, conflictCheckMap, lockedDecisions);
		LinkedHashSet<Long> closureHopIDs = new LinkedHashSet<>();
		Set<RequiredOutputStateKey> visitedClosureStates = new HashSet<>();
		LinkedHashSet<Long> loutClosureHopIDs = new LinkedHashSet<>();
		Set<RequiredOutputStateKey> visitedLoutClosureStates = new HashSet<>();

		for (Map.Entry<Long, ConflictEntry> e : conflictEntries) {
			long hopID = e.getKey();
			ConflictEntry entry = e.getValue();
			if (entry == null || !entry.canChooseFOUT)
				continue;

			FederatedOutput chosen = refinedDecisions.get(hopID);
			if (chosen != FederatedOutput.FOUT)
				continue;

			closureHopIDs.clear();
			visitedClosureStates.clear();
			DecisionMapScoreBreakdown candidateScore;
			try(DecisionMapTransaction candidateDecisions =
				new DecisionMapTransaction(refinedDecisions)) {
				applyRequiredOutputDecisionClosure(
					memoTable, hopID, chosen, conflictCheckMap,
					candidateDecisions, closureHopIDs, visitedClosureStates,
					lockedDecisions, closureSearch);
				applyLockedOutputDecisionsInPlace(candidateDecisions, lockedDecisions);

				if (closureHopIDs.isEmpty() || !candidateDecisions.hasEffectiveChanges())
					continue;

				candidateScore =
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidateDecisions, scoreCache);
				// A closure refinement must resolve every exact HOP it rewrites. Equal
				// aggregate conflict counts can otherwise hide that an impossible arm
				// survived (or that the conflict merely moved) and let a cost tie-break undo
				// the existing local conflict resolver's executable decision.
				boolean closureResolved =
					isDecisionMapClosureResolved(candidateScore, closureHopIDs);
				boolean keepClosure =
					isScorableDecisionMapScore(candidateScore)
						&& closureResolved
						&& isBetterDecisionMapScore(candidateScore, currentScore);

				Hop hopRef = memoTable.resolveOriginalHop(hopID);
				if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-RequiredOutputClosure-Selected", String.format(Locale.ROOT,
						"iter=%d chosen=%s closure=%s currentTotal=%.6f closureTotal=%.6f "
							+ "currentMissing=%d closureMissing=%d currentIncompatible=%d closureIncompatible=%d "
							+ "currentConflicts=%s closureConflicts=%s closureResolved=%s apply=%s",
						iter, chosen, closureHopIDs,
						currentScore.totalCost, candidateScore.totalCost,
						currentScore.missingRootCount, candidateScore.missingRootCount,
						currentScore.incompatiblePlanCount, candidateScore.incompatiblePlanCount,
						currentScore.exactSelectionConflictHopIDs, candidateScore.exactSelectionConflictHopIDs,
						closureResolved, keepClosure));
				}

				if (keepClosure) {
					candidateDecisions.commit();
					currentScore = candidateScore;
					continue;
				}
			}

			if (entry.canChooseLOUT) {
				loutClosureHopIDs.clear();
				visitedLoutClosureStates.clear();
				try(DecisionMapTransaction loutDecisions =
					new DecisionMapTransaction(refinedDecisions)) {
					applyRequiredOutputDecisionClosure(
						memoTable, hopID, FederatedOutput.LOUT, conflictCheckMap,
						loutDecisions, loutClosureHopIDs, visitedLoutClosureStates,
						lockedDecisions, closureSearch);
					applyDirectChildOutputDecisionClosure(
						memoTable, hopID, FederatedOutput.LOUT, conflictCheckMap,
						loutDecisions, loutClosureHopIDs, lockedDecisions, closureSearch);
					applyLockedOutputDecisionsInPlace(loutDecisions, lockedDecisions);
					DecisionMapScoreBreakdown loutScore =
						computeDecisionMapScoreBreakdown(memoTable, rootPlan, loutDecisions, scoreCache);
					boolean loutClosureResolved =
						isDecisionMapClosureResolved(loutScore, loutClosureHopIDs);
					boolean keepLout =
						isScorableDecisionMapScore(loutScore)
							&& loutClosureResolved
							&& isBetterDecisionMapScore(loutScore, candidateScore);

					Hop hopRef = memoTable.resolveOriginalHop(hopID);
					if (FederatedPlannerTrace.shouldTrace(hopRef)) {
						FederatedPlannerTrace.log(hopRef, "DP-RequiredOutputClosure-Demote", String.format(Locale.ROOT,
							"iter=%d chosen=%s closure=%s loutClosure=%s closureTotal=%.6f loutTotal=%.6f "
								+ "closureMissing=%d loutMissing=%d closureIncompatible=%d loutIncompatible=%d "
								+ "closureConflicts=%s loutConflicts=%s loutClosureResolved=%s apply=%s",
							iter, chosen, closureHopIDs, loutClosureHopIDs,
							candidateScore.totalCost, loutScore.totalCost,
							candidateScore.missingRootCount, loutScore.missingRootCount,
							candidateScore.incompatiblePlanCount, loutScore.incompatiblePlanCount,
							candidateScore.exactSelectionConflictHopIDs, loutScore.exactSelectionConflictHopIDs,
							loutClosureResolved, keepLout));
					}

					if (keepLout) {
						loutDecisions.commit();
						currentScore = loutScore;
						continue;
					}
				}
			}
		}

		for (Map.Entry<Long, ConflictEntry> e : conflictEntries) {
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

			closureHopIDs.clear();
			visitedClosureStates.clear();
			try(DecisionMapTransaction candidateDecisions =
				new DecisionMapTransaction(refinedDecisions)) {
				applyRequiredOutputDecisionClosure(
					memoTable, hopID, alternative, conflictCheckMap,
					candidateDecisions, closureHopIDs, visitedClosureStates,
					lockedDecisions, closureSearch);
				applyDirectChildOutputDecisionClosure(
					memoTable, hopID, alternative, conflictCheckMap,
					candidateDecisions, closureHopIDs, lockedDecisions, closureSearch);
				applyLockedOutputDecisionsInPlace(candidateDecisions, lockedDecisions);

				if (closureHopIDs.isEmpty() || !candidateDecisions.hasEffectiveChanges())
					continue;

				DecisionMapScoreBreakdown candidateScore =
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, candidateDecisions, scoreCache);
				// Tie preferences only rank executable alternatives for the complete local
				// closure; they do not authorize a child-incompatible exact arm.
				boolean closureResolved =
					isDecisionMapClosureResolved(candidateScore, closureHopIDs);
				boolean costTie = Math.abs(candidateScore.totalCost - currentScore.totalCost) <= 1e-9;
				Map<Long, FederatedOutput> incumbentDecisions = candidateDecisions.beforeView();
				boolean transientTiePrefersAlternative =
					costTie
						&& shouldPreferTransientWriteAlternativeOnClosureTie(
							memoTable, hopID, entry, conflictCheckMap,
							incumbentDecisions, alternative, numWorkers);
				boolean directChildTiePrefersAlternative =
					costTie
						&& shouldPreferDirectChildAlternativeOnClosureTie(
							memoTable, hopID, conflictCheckMap,
							incumbentDecisions, candidateDecisions, alternative, numWorkers);
				boolean cloneFamilyPrefersCurrent =
					shouldKeepCloneFamilyPreferredOutput(
						memoTable, hopID, entry, incumbentDecisions, chosen, alternative, numWorkers,
						conflictCheckMap);
					boolean structureImproved =
						hasBetterDecisionMapStructure(candidateScore, currentScore);
					boolean currentClosureUnresolved =
						!isDecisionMapClosureResolved(currentScore, closureHopIDs);
					boolean keepAlternative =
						isScorableDecisionMapScore(candidateScore)
							&& closureResolved
							&& (structureImproved
								// Required-output closure repairs an inconsistent exact forest; it
								// must not turn DP's local hop/children choice into a global hill
								// climb when the incumbent closure is already executable.
								|| (currentClosureUnresolved
									&& hasSameDecisionMapStructure(candidateScore, currentScore)
									&& !cloneFamilyPrefersCurrent
									&& (candidateScore.totalCost + 1e-9 < currentScore.totalCost
										|| transientTiePrefersAlternative
										|| directChildTiePrefersAlternative)));

				Hop hopRef = memoTable.resolveOriginalHop(hopID);
				if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-RequiredOutputClosure", String.format(Locale.ROOT,
							"iter=%d chosen=%s alternative=%s closure=%s currentTotal=%.6f altTotal=%.6f "
								+ "currentMissing=%d altMissing=%d currentIncompatible=%d altIncompatible=%d "
								+ "currentConflicts=%s altConflicts=%s closureResolved=%s "
								+ "transientTiePrefersAlt=%s directChildTiePrefersAlt=%s cloneFamilyPrefersCurrent=%s apply=%s",
							iter, chosen, alternative, closureHopIDs,
							currentScore.totalCost, candidateScore.totalCost,
							currentScore.missingRootCount, candidateScore.missingRootCount,
							currentScore.incompatiblePlanCount, candidateScore.incompatiblePlanCount,
							currentScore.exactSelectionConflictHopIDs, candidateScore.exactSelectionConflictHopIDs,
							closureResolved,
							transientTiePrefersAlternative, directChildTiePrefersAlternative,
							cloneFamilyPrefersCurrent, keepAlternative));
				}

				if (keepAlternative) {
					candidateDecisions.commit();
					currentScore = candidateScore;
				}
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

		applyDirectChildOutputDecisionClosure(
			memoTable, concreteHopID, desiredOut, conflictCheckMap,
			decisions, closureHopIDs, Collections.emptyMap(), null);
	}

	private static void applyDirectChildOutputDecisionClosure(
		FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		LinkedHashSet<Long> closureHopIDs,
		Map<Long, FederatedOutput> lockedDecisions) {

		applyDirectChildOutputDecisionClosure(
			memoTable, concreteHopID, desiredOut, conflictCheckMap,
			decisions, closureHopIDs, lockedDecisions, null);
	}

	private static void applyDirectChildOutputDecisionClosure(
		FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		LinkedHashSet<Long> closureHopIDs,
		Map<Long, FederatedOutput> lockedDecisions,
		RequiredOutputClosureSearch closureSearch) {

		if (memoTable == null || desiredOut == null || conflictCheckMap == null
			|| decisions == null || closureHopIDs == null)
			return;

		FederatedPlannerDpMemoTable.FedPlan selectedPlan =
			findStrictCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null)
			selectedPlan = closureSearch != null
				? closureSearch.select(concreteHopID, desiredOut).plan
				: selectRequiredOutputClosurePlanVariant(
					memoTable, concreteHopID, desiredOut, conflictCheckMap, lockedDecisions);
		if (selectedPlan == null)
			selectedPlan = selectCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null && (lockedDecisions == null || lockedDecisions.isEmpty()))
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
			FederatedOutput lockedChildOutput = lockedDecisions != null
				? lockedDecisions.get(childOrigHopID) : null;
			if (lockedChildOutput != null && lockedChildOutput != childEdge.getValue())
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
		Set<RequiredOutputStateKey> visitedStates) {

		applyRequiredOutputDecisionClosure(
			memoTable, concreteHopID, desiredOut, conflictCheckMap,
			decisions, closureHopIDs, visitedStates, Collections.emptyMap());
	}

	private static void applyRequiredOutputDecisionClosure(
		FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		LinkedHashSet<Long> closureHopIDs,
		Set<RequiredOutputStateKey> visitedStates,
		Map<Long, FederatedOutput> lockedDecisions) {

		RequiredOutputClosureSearch search =
			new RequiredOutputClosureSearch(memoTable, conflictCheckMap, lockedDecisions);
		applyRequiredOutputDecisionClosure(
			memoTable, concreteHopID, desiredOut, conflictCheckMap,
			decisions, closureHopIDs, visitedStates, lockedDecisions, search);
	}

	private static void applyRequiredOutputDecisionClosure(
		FederatedPlannerDpMemoTable memoTable,
		long concreteHopID,
		FederatedOutput desiredOut,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> decisions,
		LinkedHashSet<Long> closureHopIDs,
		Set<RequiredOutputStateKey> visitedStates,
		Map<Long, FederatedOutput> lockedDecisions,
		RequiredOutputClosureSearch search) {

		if (memoTable == null || desiredOut == null || conflictCheckMap == null
			|| decisions == null || closureHopIDs == null || visitedStates == null)
			return;

		RequiredOutputStateKey stateKey =
			new RequiredOutputStateKey(concreteHopID, desiredOut);
		if (!visitedStates.add(stateKey))
			return;

		long origHopID = memoTable.resolveOriginalHopId(concreteHopID);
		FederatedOutput lockedOutput = lockedDecisions != null
			? lockedDecisions.get(origHopID) : null;
		if (lockedOutput != null && lockedOutput != desiredOut)
			return;
		ConflictEntry entry = conflictCheckMap.get(origHopID);

		FederatedPlannerDpMemoTable.FedPlan selectedPlan =
			findStrictCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null && search != null)
			selectedPlan = search.select(concreteHopID, desiredOut).plan;
		if (selectedPlan == null)
			selectedPlan = selectCompatiblePlanVariant(memoTable, concreteHopID, desiredOut, decisions);
		if (selectedPlan == null && (lockedDecisions == null || lockedDecisions.isEmpty()))
			selectedPlan = memoTable.getFedPlanAfterPrune(concreteHopID, desiredOut);
		if (selectedPlan == null || selectedPlan.getChildFedPlans() == null)
			return;

		if (entry != null && canChooseOutput(entry, desiredOut)) {
			decisions.put(origHopID, desiredOut);
			closureHopIDs.add(origHopID);
		}

		for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
			if (childEdge == null || childEdge.getValue() == null)
				continue;
			if (!shouldPropagateRequiredChildOutput(desiredOut, childEdge.getValue()))
				continue;
			long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
			ConflictEntry childEntry = conflictCheckMap.get(childOrigHopID);
			if (childEntry == null || !canChooseOutput(childEntry, childEdge.getValue()))
				continue;
			FederatedOutput lockedChildOutput = lockedDecisions != null
				? lockedDecisions.get(childOrigHopID) : null;
			if (lockedChildOutput != null && lockedChildOutput != childEdge.getValue())
				return;
			decisions.put(childOrigHopID, childEdge.getValue());
			closureHopIDs.add(childOrigHopID);
			applyRequiredOutputDecisionClosure(
				memoTable, childEdge.getKey(), childEdge.getValue(), conflictCheckMap,
				decisions, closureHopIDs, visitedStates, lockedDecisions, search);
			}
		}

		private static FederatedPlannerDpMemoTable.FedPlan selectRequiredOutputClosurePlanVariant(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			FederatedOutput desiredOut,
			Map<Long, ConflictEntry> conflictCheckMap) {

			return new RequiredOutputClosureSearch(memoTable, conflictCheckMap).select(hopID, desiredOut).plan;
		}

		private static FederatedPlannerDpMemoTable.FedPlan selectRequiredOutputClosurePlanVariant(
			FederatedPlannerDpMemoTable memoTable,
			long hopID,
			FederatedOutput desiredOut,
			Map<Long, ConflictEntry> conflictCheckMap,
			Map<Long, FederatedOutput> lockedDecisions) {

			return new RequiredOutputClosureSearch(
				memoTable, conflictCheckMap, lockedDecisions).select(hopID, desiredOut).plan;
		}

		private static final class RequiredOutputClosureSearch {
			private final FederatedPlannerDpMemoTable memoTable;
			private final Map<Long, ConflictEntry> conflictCheckMap;
			private final Map<Long, FederatedOutput> lockedDecisions;
			private final Map<RequiredOutputStateKey, RequiredOutputSelection> resolved = new HashMap<>();
			private final Set<RequiredOutputStateKey> active = new HashSet<>();

			RequiredOutputClosureSearch(
				FederatedPlannerDpMemoTable memoTable,
				Map<Long, ConflictEntry> conflictCheckMap) {
				this(memoTable, conflictCheckMap, Collections.emptyMap());
			}

			RequiredOutputClosureSearch(
				FederatedPlannerDpMemoTable memoTable,
				Map<Long, ConflictEntry> conflictCheckMap,
				Map<Long, FederatedOutput> lockedDecisions) {
				this.memoTable = memoTable;
				this.conflictCheckMap = conflictCheckMap;
				this.lockedDecisions = lockedDecisions != null
					? lockedDecisions : Collections.emptyMap();
			}

			RequiredOutputSelection select(long hopID, FederatedOutput desiredOut) {
				if (memoTable == null || desiredOut == null)
					return RequiredOutputSelection.INFEASIBLE;
				FederatedOutput lockedOutput =
					lockedDecisions.get(memoTable.resolveOriginalHopId(hopID));
				if (lockedOutput != null && lockedOutput != desiredOut)
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
					long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
					FederatedOutput lockedChildOutput = lockedDecisions.get(childOrigHopID);
					if (lockedChildOutput != null && lockedChildOutput != childEdge.getValue())
						return RequiredOutputSelection.INFEASIBLE;
					if (!shouldPropagateRequiredChildOutput(desiredOut, childEdge.getValue()))
						continue;

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

	private static FederatedOutput enforceLockedOutputClosureFeasibility(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		ConflictEntry entry,
		FederatedOutput chosen,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> lockedDecisions) {

		if (chosen == null || lockedDecisions == null || lockedDecisions.isEmpty())
			return chosen;
		RequiredOutputClosureSearch search =
			new RequiredOutputClosureSearch(memoTable, conflictCheckMap, lockedDecisions);
		if (search.select(hopID, chosen).feasible)
			return chosen;

		FederatedOutput alternative = chosen == FederatedOutput.FOUT
			? FederatedOutput.LOUT : FederatedOutput.FOUT;
		FederatedOutput resolved = canChooseOutput(entry, alternative)
			&& search.select(hopID, alternative).feasible ? alternative : null;
		Hop hopRef = memoTable != null ? memoTable.resolveOriginalHop(hopID) : null;
		if (FederatedPlannerTrace.shouldTrace(hopRef))
			FederatedPlannerTrace.log(hopRef, "DP-LockedOutputClosure",
				"chosen=" + chosen + " feasible=false alternative=" + alternative
					+ " resolved=" + resolved + " lockCount=" + lockedDecisions.size());
		return resolved;
	}

	private static boolean hasLockedOutputConflict(
		Map<Long, FederatedOutput> lockedDecisions,
		Set<Long> hopIDs,
		FederatedOutput targetOutput) {

		if (lockedDecisions == null || lockedDecisions.isEmpty()
			|| hopIDs == null || hopIDs.isEmpty() || targetOutput == null)
			return false;
		for (long hopID : hopIDs) {
			FederatedOutput lockedOutput = lockedDecisions.get(hopID);
			if (lockedOutput != null && lockedOutput != targetOutput)
				return true;
		}
		return false;
	}

	private static Map<Long, FederatedOutput> refineTransientFamilyDecisions(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan,
			Map<Long, ConflictEntry> conflictCheckMap,
			Map<Long, FederatedOutput> nextDecisions,
			Map<Long, FederatedOutput> lockedDecisions,
			int iter,
			SimulationDecisionCache simulationDecisionCache,
			DecisionMapScoreCache scoreCache) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| nextDecisions == null || nextDecisions.isEmpty())
			return nextDecisions;

		Map<Long, FederatedOutput> refinedDecisions =
			copyOutputDecisionsAndApplyLocks(nextDecisions, lockedDecisions);
		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, refinedDecisions, scoreCache);
		Map<Long, LinkedHashSet<Long>> parentGraph = buildConflictParentGraph(memoTable, conflictCheckMap);

		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
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
			if (lockedDecisions != null)
				lockedAlternativeDecisions.putAll(lockedDecisions);
			boolean alternativeConflictsWithLock = hasLockedOutputConflict(
				lockedAlternativeDecisions, Collections.singleton(hopID), alternative);
			if (!alternativeConflictsWithLock)
				lockedAlternativeDecisions.put(hopID, alternative);
			Map<Long, FederatedOutput> simulatedAlternativeDecisions = alternativeConflictsWithLock
				? Collections.emptyMap()
				: simulateOutputDecisionsWithLocksCached(
					memoTable, rootPlan, refinedDecisions, lockedAlternativeDecisions, simulationDecisionCache);
			DecisionMapScoreBreakdown simulatedAlternativeScore =
				alternativeConflictsWithLock ? null
					: computeDecisionMapScoreBreakdown(
						memoTable, rootPlan, simulatedAlternativeDecisions, scoreCache);
			boolean keepSimulatedAlternative =
				isScorableDecisionMapScore(simulatedAlternativeScore)
					&& isDecisionMapClosureResolved(simulatedAlternativeScore, familyHopIDs)
					&& isBetterDecisionMapScore(simulatedAlternativeScore, currentScore);
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-TransientFamilySeed", String.format(Locale.ROOT,
					"iter=%d chosen=%s alt=%s lockCount=%d lockConflict=%s currentTotal=%.6f altTotal=%.6f "
						+ "currentMissing=%d altMissing=%d apply=%s",
					iter,
					chosen,
					alternative,
					lockedAlternativeDecisions.size(),
					alternativeConflictsWithLock,
					currentScore.totalCost,
					simulatedAlternativeScore != null ? simulatedAlternativeScore.totalCost : Double.NaN,
					currentScore.missingRootCount,
					simulatedAlternativeScore != null ? simulatedAlternativeScore.missingRootCount : -1,
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

			if (chosen == FederatedOutput.FOUT
				&& !hasLockedOutputConflict(lockedDecisions, familyHopIDs, FederatedOutput.FOUT)) {
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
				if (hasLockedOutputConflict(
					lockedDecisions, normalizedFamilyHopIDs, FederatedOutput.FOUT)) {
					// The expansion discovered another committed family member. Discard only
					// this FOUT normalization candidate; the opposite family output remains
					// available to the existing local resolver below.
					normalizedChanged = 0;
				}
				else {
					for (long familyHopID : normalizedFamilyHopIDs) {
						ConflictEntry familyEntry = conflictCheckMap.get(familyHopID);
						if (familyEntry == null || !familyEntry.canChooseFOUT)
							continue;
						FederatedOutput old = normalizedDecisions.put(familyHopID, FederatedOutput.FOUT);
						if (old != FederatedOutput.FOUT)
							normalizedChanged++;
					}
				}
				applyLockedOutputDecisionsInPlace(normalizedDecisions, lockedDecisions);
				if (normalizedChanged > 0) {
					DecisionMapScoreBreakdown normalizedScore =
						computeDecisionMapScoreBreakdown(memoTable, rootPlan, normalizedDecisions, scoreCache);
					boolean rawPrefersFout =
						familyHasCheaperRawAlternative(memoTable, familyHopIDs, FederatedOutput.FOUT);
					boolean keepNormalizedFout = isScorableDecisionMapScore(normalizedScore)
						&& isDecisionMapClosureResolved(normalizedScore, normalizedFamilyHopIDs)
						&& (isBetterDecisionMapScore(normalizedScore, currentScore)
							|| (rawPrefersFout
								&& hasSameDecisionMapStructure(normalizedScore, currentScore)
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
			if (hasLockedOutputConflict(lockedDecisions, altFamilyHopIDs, alternative))
				continue;
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
			if (hasLockedOutputConflict(lockedDecisions, altFamilyHopIDs, alternative))
				continue;
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
			applyLockedOutputDecisionsInPlace(altDecisions, lockedDecisions);
			if (changed == 0)
				continue;

			DecisionMapScoreBreakdown altScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions, scoreCache);
			boolean rawPrefersAlternative =
				alternative == FederatedOutput.FOUT
					&& familyHasCheaperRawAlternative(memoTable, altFamilyHopIDs, alternative);
			Map<Long, FederatedOutput> bundleAltDecisions = null;
			DecisionMapScoreBreakdown bundleAltScore = null;
			LinkedHashSet<Long> feasibleBundleHopIDs = null;
			// A family-only representation switch can temporarily introduce a
			// materialization boundary that disappears only after dependent consumers
			// switch with it. Evaluate the contextually feasible bundle while keeping
			// acceptance strictly lower-cost.
			if (alternative == FederatedOutput.FOUT
				&& bundleHopIDs.size() > familyHopIDs.size()) {
				feasibleBundleHopIDs = collectContextuallyFeasibleTransientBundleHopIDs(
					memoTable, rootPlan, refinedDecisions, conflictCheckMap, familyHopIDs, bundleHopIDs);
				if (feasibleBundleHopIDs.size() > familyHopIDs.size()) {
					bundleAltDecisions = new HashMap<>(refinedDecisions);
					if (!hasLockedOutputConflict(
						lockedDecisions, feasibleBundleHopIDs, FederatedOutput.FOUT)) {
						for (long bundleHopID : feasibleBundleHopIDs)
							bundleAltDecisions.put(bundleHopID, FederatedOutput.FOUT);
						applyLockedOutputDecisionsInPlace(bundleAltDecisions, lockedDecisions);
						bundleAltScore = computeDecisionMapScoreBreakdown(
							memoTable, rootPlan, bundleAltDecisions, scoreCache);
					}
				}
			}
			Map<Long, FederatedOutput> candidateDecisions = altDecisions;
			DecisionMapScoreBreakdown candidateScore = altScore;
			LinkedHashSet<Long> candidateHopIDs = altFamilyHopIDs;
			boolean applyBundle = false;
			if (bundleAltDecisions != null && isScorableDecisionMapScore(bundleAltScore)
				&& (!isScorableDecisionMapScore(altScore)
					|| isBetterDecisionMapScore(bundleAltScore, altScore))) {
				candidateDecisions = bundleAltDecisions;
				candidateScore = bundleAltScore;
				candidateHopIDs = feasibleBundleHopIDs != null ? feasibleBundleHopIDs : altFamilyHopIDs;
				applyBundle = true;
			}
				boolean keepAlternative = isScorableDecisionMapScore(candidateScore)
					&& isDecisionMapClosureResolved(candidateScore, candidateHopIDs)
					&& (isBetterDecisionMapScore(candidateScore, currentScore)
						|| (rawPrefersAlternative
							&& hasSameDecisionMapStructure(candidateScore, currentScore)
							&& Math.abs(candidateScore.totalCost - currentScore.totalCost) <= 1e-9));
			if (FederatedPlannerTrace.shouldTrace(hopRef)) {
				FederatedPlannerTrace.log(hopRef, "DP-TransientFamilyRefine", String.format(Locale.ROOT,
					"iter=%d chosen=%s alt=%s family=%s bundle=%s changed=%d skipped=%d currentTotal=%.6f altTotal=%.6f "
						+ "bundleAltTotal=%.6f candidate=%s currentMissing=%d altMissing=%d rawPrefersAlt=%s apply=%s "
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
		// With no explicit hop filter every conflict hop would repeat the exact same
		// root-score decomposition. Keep one deterministic representative; explicit
		// operator-selected hop filters retain their requested targets.
		if (!FederatedPlannerTrace.hasExplicitHopFilter() && traceTargets.size() > 1) {
			Hop representative = traceTargets.iterator().next();
			traceTargets.clear();
			traceTargets.add(representative);
		}

		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, currentDecisions, scoreCache);
		DecisionMapScoreBreakdown nextScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, nextDecisions, scoreCache);

		for (Hop traceHop : traceTargets) {
			FederatedPlannerTrace.log(traceHop, "DP-DecisionMap-Score", String.format(Locale.ROOT,
				"iter=%d currentTotal=%.6f nextTotal=%.6f currentMain=%.6f nextMain=%.6f "
					+ "currentAdditional=%.6f nextAdditional=%.6f currentVirtual=%.6f nextVirtual=%.6f "
					+ "currentMissing=%d nextMissing=%d currentIncompatible=%d nextIncompatible=%d "
					+ "currentConflicts=%s nextConflicts=%s currentRoots=%d nextRoots=%d",
				iter,
				currentScore.totalCost, nextScore.totalCost,
				currentScore.mainRootCost, nextScore.mainRootCost,
				currentScore.additionalRootCost, nextScore.additionalRootCost,
				currentScore.virtualAdditionalRootCost, nextScore.virtualAdditionalRootCost,
				currentScore.missingRootCount, nextScore.missingRootCount,
				currentScore.incompatiblePlanCount, nextScore.incompatiblePlanCount,
				currentScore.exactSelectionConflictHopIDs, nextScore.exactSelectionConflictHopIDs,
				currentScore.rootContributions.size(), nextScore.rootContributions.size()));
			int rootDetailBudget = FederatedPlannerTrace.getMaxEdgeLogsPerHop();
			int rootDetails = 0;
			int rootDetailsOmitted = 0;
			for (RootContribution contribution : nextScore.rootContributions.values()) {
				if (!contribution.additionalRoot && !contribution.virtualClone)
					continue;
				if (rootDetails >= rootDetailBudget) {
					rootDetailsOmitted++;
					continue;
				}
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
				rootDetails++;
			}
			if (rootDetailsOmitted > 0)
				FederatedPlannerTrace.log(traceHop, "DP-DecisionMap-RootSummary",
					"iter=" + iter + " logged=" + rootDetails + " omitted=" + rootDetailsOmitted);
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
		return computeDecisionMapScoreBreakdownWithConflicts(
			memoTable, rootPlan, outputDecisions, null);
	}

	private static DecisionMapScoreBreakdown computeDecisionMapScoreBreakdownWithConflicts(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Map<Long, ConflictEntry> precomputedConflictMap) {

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

		breakdown.incompatiblePlanCount = countIncompatibleDecisionMapPlans(
			memoTable, rootPlan, outputDecisions, breakdown.exactSelectionConflictHopIDs,
			breakdown.exactOccurrenceConflicts);

			for (Map.Entry<Long, FederatedPlannerDpMemoTable.FedPlan> entry : selectedRootPlans.entrySet()) {
				long rootHopID = entry.getKey();
				breakdown.addContribution(entry.getValue(), rootHopID, additionalRootHopIDs.contains(rootHopID),
					memoTable, selectedRootPlans, outputDecisions);
			}
			Map<Long, ConflictEntry> conflictCheckMap = outputDecisions == null || outputDecisions.isEmpty()
				? Collections.emptyMap()
				: precomputedConflictMap != null ? precomputedConflictMap
					: collectConflictsSingleBFS(memoTable, rootPlan, outputDecisions);
			double transientLocalCost = computeDecisionMapTransientReadLoutMaterializationCost(
				memoTable, outputDecisions, conflictCheckMap);
			double mixedFoutToCpLocalEdgeCost = computeDecisionMapMixedFoutToCpLocalEdgeCost(
				memoTable, rootPlan, outputDecisions, conflictCheckMap);
			double cloneFamilyOverridePenalty = computeDecisionMapCloneFamilyOutputOverridePenalty(
				memoTable, outputDecisions, conflictCheckMap);
			breakdown.totalCost += transientLocalCost + mixedFoutToCpLocalEdgeCost + cloneFamilyOverridePenalty;
			breakdown.additionalRootCost += transientLocalCost + mixedFoutToCpLocalEdgeCost
				+ cloneFamilyOverridePenalty;
			breakdown.virtualAdditionalRootCost += cloneFamilyOverridePenalty;

			return breakdown;
		}

	private static int countIncompatibleDecisionMapPlans(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, FederatedOutput> outputDecisions,
		Set<Long> exactSelectionConflictHopIDs,
		List<ExactOccurrenceConflict> exactOccurrenceConflicts) {

		if (memoTable == null || rootPlan == null)
			return 0;

		Queue<Pair<Long, FederatedOutput>> queue = new ArrayDeque<>();
		for (Pair<Long, FederatedOutput> rootChild : rootPlan.getChildFedPlans()) {
			if (rootChild == null || rootChild.getValue() == null)
				continue;
			long rootOrigHopID = memoTable.resolveOriginalHopId(rootChild.getKey());
			FederatedOutput desiredOut = outputDecisions != null
				? outputDecisions.get(rootOrigHopID) : null;
			queue.add(Pair.of(rootChild.getKey(), desiredOut != null ? desiredOut : rootChild.getValue()));
		}
		for (long rootHopID : memoTable.getAdditionalRootHopIDs()) {
			long rootOrigHopID = memoTable.resolveOriginalHopId(rootHopID);
			FederatedOutput desiredOut = outputDecisions != null
				? outputDecisions.get(rootOrigHopID) : null;
			if (desiredOut == null) {
				FederatedPlannerDpMemoTable.FedPlan lPlan =
					memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.LOUT);
				FederatedPlannerDpMemoTable.FedPlan fPlan =
					memoTable.getFedPlanAfterPrune(rootHopID, FederatedOutput.FOUT);
				FederatedPlannerDpMemoTable.FedPlan seed = lPlan == null ? fPlan : fPlan == null ? lPlan
					: lPlan.getCumulativeCost() <= fPlan.getCumulativeCost() ? lPlan : fPlan;
				if (seed == null)
					continue;
				desiredOut = seed.getFedOutType();
			}
			queue.add(Pair.of(rootHopID, desiredOut));
		}

		Set<RequiredOutputStateKey> visited = new HashSet<>();
		Map<CompiledHopKey, SelectedDpState> requiredSelections = new IdentityHashMap<>();
		Set<CompiledHopKey> disagreeingSelections =
			Collections.newSetFromMap(new IdentityHashMap<>());
		int incompatiblePlans = 0;
		while (!queue.isEmpty()) {
			Pair<Long, FederatedOutput> state = queue.poll();
			if (state == null || state.getValue() == null
				|| !visited.add(new RequiredOutputStateKey(state.getKey(), state.getValue())))
				continue;

			FederatedPlannerDpMemoTable.FedPlan selectedPlan = findStrictCompatiblePlanVariant(
				memoTable, state.getKey(), state.getValue(), outputDecisions);
			if (selectedPlan == null) {
				incompatiblePlans++;
				// A requested output with no child-compatible exact arm is itself a
				// refinement conflict. Previously only two-parent exact-state disagreements
				// populated this set, so the existing resolver could detect the impossible
				// plan but had no HOP to revisit (for example an FOUT predicate whose formal
				// input had already been fixed to LOUT).
				if (exactSelectionConflictHopIDs != null)
					exactSelectionConflictHopIDs.add(
						memoTable.resolveOriginalHopId(state.getKey()));
				selectedPlan = memoTable.getFedPlanAfterPrune(state.getKey(), state.getValue());
			}
			if (selectedPlan == null)
				continue;

			// A compiled occurrence is one emitted runtime value and therefore cannot
			// inherit different exact placements from separate roots/parents.  An absent
			// output-map entry used to leave each incoming edge independently valid here,
			// even though rewrite correctly rejected the resulting CP/LOUT vs FED/FOUT
			// double selection.  Score that forest as structurally incompatible so the
			// cost-based refinement must choose one explicit, executable occurrence state.
			if (memoTable.analysis() != null) {
				PlacementAnalysis.HopOccurrenceProjection occurrence =
					memoTable.requirePlanCarrierOccurrence(selectedPlan.getHopRef());
				CompiledHopKey occurrenceKey = occurrence.key();
				SelectedDpState proposed = selectedState(selectedPlan);
				SelectedDpState previous = requiredSelections.putIfAbsent(occurrenceKey, proposed);
				if (previous != null && (previous.exactState() != proposed.exactState()
					|| previous.derivedFedFout() != proposed.derivedFedFout())
					&& disagreeingSelections.add(occurrenceKey)) {
					incompatiblePlans++;
					LinkedHashSet<Long> familyHopIDs = new LinkedHashSet<>(
						memoTable.getOriginalDecisionHopIdsForOccurrence(occurrence));
					familyHopIDs.add(memoTable.resolveOriginalHopId(previous.retainedPlan().getHopID()));
					familyHopIDs.add(memoTable.resolveOriginalHopId(proposed.retainedPlan().getHopID()));
					familyHopIDs.add(memoTable.resolveOriginalHopId(selectedPlan.getHopID()));
					familyHopIDs.add(memoTable.resolveOriginalHopId(state.getKey()));
					List<Long> exactFamily = familyHopIDs.stream().sorted().toList();
					if (exactSelectionConflictHopIDs != null)
						exactSelectionConflictHopIDs.addAll(exactFamily);
					if (exactOccurrenceConflicts != null)
						exactOccurrenceConflicts.add(new ExactOccurrenceConflict(occurrenceKey, exactFamily));
					FederatedPlannerTrace.logGlobal("DP-DecisionMap-ExactSelectionConflict",
						"occurrence=" + occurrenceKey + " previous=" + previous + " proposed=" + proposed
							+ " family=" + exactFamily
							+ " stateHop=" + state.getKey() + " stateOut=" + state.getValue()
							+ " explicitDecision=" + (outputDecisions != null ? outputDecisions.get(
								memoTable.resolveOriginalHopId(state.getKey())) : null));
				}
			}
			if (selectedPlan.getChildFedPlans() == null)
				continue;

			for (Pair<Long, FederatedOutput> childEdge : selectedPlan.getChildFedPlans()) {
				if (childEdge == null || childEdge.getValue() == null)
					continue;
				long childOrigHopID = memoTable.resolveOriginalHopId(childEdge.getKey());
				FederatedOutput desiredChildOut = outputDecisions != null
					? outputDecisions.get(childOrigHopID) : null;
				queue.add(Pair.of(childEdge.getKey(),
					desiredChildOut != null ? desiredChildOut : childEdge.getValue()));
			}
		}

		// The exact join still owns full (exec, output, FType) legality. One strictly
		// earlier fact is already decidable here, however: a runtime transient value
		// cannot be written as FOUT and read as LOUT (or vice versa). No later choice of
		// exec type or FType can repair that output mismatch. Score these analysis-owned
		// reaching-definition conflicts structurally so a final local refinement cannot
		// undo multi-write normalization and force the whole component into the generic
		// legality fallback. This does not close either output candidate; it makes the
		// existing DP conflict resolver compare the two complete legal families.
		incompatiblePlans += countSelectedTransientOutputConflicts(
			memoTable, requiredSelections, exactSelectionConflictHopIDs);
		return incompatiblePlans;
	}

	private static int countSelectedTransientOutputConflicts(
		FederatedPlannerDpMemoTable memoTable,
		Map<CompiledHopKey,SelectedDpState> selected,
		Set<Long> exactSelectionConflictHopIDs) {

		if(memoTable == null || memoTable.analysis() == null || selected == null
			|| selected.isEmpty())
			return 0;
		PlacementAnalysis analysis = memoTable.analysis();
		Set<String> observed = new LinkedHashSet<>();
		int conflicts = 0;
		String firstConflict = null;
		for(PlacementAnalysis.HopOccurrenceProjection target : analysis.compiledHopOccurrences()) {
			Hop targetHop = target.hop();
			if(!(targetHop instanceof DataOp)
				|| ((DataOp)targetHop).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;
			SelectedDpState targetState = selected.get(target.key());
			if(targetState == null)
				continue;
			for(CompiledHopKey sourceKey :
				analysis.cfgDefinitionSourcesInCanonicalOrder(target.key())) {
				Hop sourceHop = analysis.hop(sourceKey).orElse(null);
				SelectedDpState sourceState = selected.get(sourceKey);
				if(!(sourceHop instanceof DataOp)
					|| ((DataOp)sourceHop).getOp() != Types.OpOpData.TRANSIENTWRITE
					|| sourceState == null || sourceState.output() == targetState.output())
					continue;
				String relation = sourceKey.normalizedSignature() + "->"
					+ target.key().normalizedSignature();
				if(!observed.add(relation))
					continue;
				conflicts++;
				long sourceHopID = memoTable.resolveOriginalHopId(sourceHop.getHopID());
				long targetHopID = memoTable.resolveOriginalHopId(targetHop.getHopID());
				if(exactSelectionConflictHopIDs != null) {
					exactSelectionConflictHopIDs.add(sourceHopID);
					exactSelectionConflictHopIDs.add(targetHopID);
				}
				if(firstConflict == null)
					firstConflict = "source=" + compactOccurrence(sourceKey) + ':' + sourceState.output()
						+ " target=" + compactOccurrence(target.key()) + ':' + targetState.output()
						+ " sourceHop=" + sourceHopID + " targetHop=" + targetHopID;
			}
		}
		if(conflicts > 0)
			FederatedPlannerTrace.logGlobal("DP-DecisionMap-TransientOutputConflict",
				"count=" + conflicts + " first={" + firstConflict + '}');
		return conflicts;
	}

		private static double computeDecisionMapCloneFamilyOutputOverridePenalty(
			FederatedPlannerDpMemoTable memoTable,
			Map<Long, FederatedOutput> outputDecisions,
			Map<Long, ConflictEntry> conflictCheckMap) {

			if (memoTable == null || outputDecisions == null || outputDecisions.isEmpty())
				return 0.0;
			if (conflictCheckMap == null || conflictCheckMap.isEmpty())
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
				memoTable, hopID, entry, decisionMembers, targetOut, tentativeDecisions,
				conflictCheckMap, parentVariantDeltaCache);
			if (!Double.isFinite(cost))
				return Double.POSITIVE_INFINITY;

			Set<ConflictCostEdgeKey> seenCostEdges = new LinkedHashSet<>();
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
					ConflictCostEdgeKey edgeKey = buildConflictCostEdgeKey(
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
			Map<Long, FederatedOutput> outputDecisions,
			Map<Long, ConflictEntry> conflictCheckMap) {

			if (memoTable == null || outputDecisions == null || outputDecisions.isEmpty())
				return 0.0;
			double cost = 0.0;
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
				Map<Long, FederatedOutput> outputDecisions,
				Map<Long, ConflictEntry> conflictCheckMap) {

			if (memoTable == null || rootPlan == null || outputDecisions == null || outputDecisions.isEmpty())
				return 0.0;
				double cost = 0.0;
				int numWorkers = Math.max(1, memoTable.getNumWorkers());
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

		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
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
			int rootDetailBudget = FederatedPlannerTrace.getMaxEdgeLogsPerHop();
			int rootDetails = 0;
			int rootDetailsOmitted = 0;
			for (RootContribution contribution : altScore.rootContributions.values()) {
				if (!contribution.additionalRoot && !contribution.virtualClone)
					continue;
				if (rootDetails >= rootDetailBudget) {
					rootDetailsOmitted++;
					continue;
				}
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
				rootDetails++;
			}
			if (rootDetailsOmitted > 0)
				FederatedPlannerTrace.log(hopRef, "DP-DecisionMap-AltRootSummary",
					"iter=" + iter + " alt=" + alternative + " logged=" + rootDetails
						+ " omitted=" + rootDetailsOmitted);
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
		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
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
			logDecisionMapRootDifferences(hopRef, "DP-DecisionMap-BundleRoot",
				"DP-DecisionMap-BundleRootSummary", iter, alternative, nextScore, altScore, logAllRoots);

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
				logDecisionMapRootDifferences(hopRef, "DP-DecisionMap-FamilyRoot",
					"DP-DecisionMap-FamilyRootSummary", iter, alternative, nextScore,
					familyAltScore, logFamilyRoots);
			}
		}
	}

	private static void logDecisionMapRootDifferences(Hop hopRef, String traceTag, String summaryTraceTag, int iter,
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
		int rootDetailBudget = FederatedPlannerTrace.getMaxEdgeLogsPerHop();
		int rootDetails = 0;
		int rootDetailsOmitted = 0;
		for (long rootHopID : rootHopIDs) {
			RootContribution chosenContribution = chosenByRootHopID.get(rootHopID);
			RootContribution altContribution = altByRootHopID.get(rootHopID);
			if (!hasMeaningfulRootDifference(chosenContribution, altContribution))
				continue;
			if (rootDetails >= rootDetailBudget) {
				rootDetailsOmitted++;
				continue;
			}
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
			rootDetails++;
		}
		if (rootDetailsOmitted > 0)
			FederatedPlannerTrace.log(hopRef, summaryTraceTag,
				"iter=" + iter + " alt=" + alternative + " logged=" + rootDetails
					+ " omitted=" + rootDetailsOmitted);
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
		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
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
	 * and DP memo entries can be cloned across loop contexts. Therefore, we first consume the
	 * exact analysis-owned CFG transient relation captured on the conflict entries, then scan
	 * traced plan variants for legacy/concrete edges. A read with multiple reaching writers is
	 * deliberately excluded here and handled atomically by multi-write family normalization.</p>
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

		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty())
			return new LinkedHashSet<>();
		if (transientReadParentsCache != null)
			return transientReadParentsCache.get(conflictCheckMap, tWriteOrigHopID);
		return collectTransientReadParentsForWrite(memoTable, tWriteOrigHopID, conflictCheckMap);
	}

	private static LinkedHashSet<Long> collectTransientReadParentsForWrite(
		FederatedPlannerDpMemoTable memoTable, long tWriteOrigHopID,
		Map<Long, ConflictEntry> conflictCheckMap) {
		LinkedHashSet<Long> tReadHopIDs = new LinkedHashSet<>();

		ConflictEntry logicalSourceEntry = conflictCheckMap.get(tWriteOrigHopID);
		if (logicalSourceEntry != null) {
			for (long logicalReadHopID : logicalSourceEntry.logicalTransientReadHopIDs) {
				ConflictEntry logicalReadEntry = conflictCheckMap.get(logicalReadHopID);
				if (logicalReadEntry != null
					&& logicalReadEntry.logicalTransientProducerHopIDs.size() == 1
					&& logicalReadEntry.logicalTransientProducerHopIDs.contains(tWriteOrigHopID))
					tReadHopIDs.add(logicalReadHopID);
			}
		}

		for (Map.Entry<Long, ConflictEntry> e : sortedConflictEntries(conflictCheckMap)) {
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
			boolean logicalRelationAllowsSingleWriterPropagation =
				entry.logicalTransientProducerHopIDs.isEmpty()
					|| (entry.logicalTransientProducerHopIDs.size() == 1
						&& entry.logicalTransientProducerHopIDs.contains(tWriteOrigHopID));
			if (readsFromThisWrite && referencedTWriteOrigHopIDs.size() == 1
				&& logicalRelationAllowsSingleWriterPropagation)
				tReadHopIDs.add(hopID);
		}

		return tReadHopIDs;
	}

	/**
	 * Builds the inverse TWrite-to-TRead relation once for one selected conflict
	 * forest. The previous per-write scan repeated the same TRead/member/child walk
	 * for every write in that forest.
	 */
	private static Map<Long,LinkedHashSet<Long>> indexTransientReadParents(
		FederatedPlannerDpMemoTable memoTable, Map<Long,ConflictEntry> conflictCheckMap) {
		Map<Long,LinkedHashSet<Long>> indexed = new LinkedHashMap<>();
		for(Map.Entry<Long,ConflictEntry> source : sortedConflictEntries(conflictCheckMap)) {
			ConflictEntry sourceEntry = source.getValue();
			if(sourceEntry == null)
				continue;
			for(long readHopID : sourceEntry.logicalTransientReadHopIDs) {
				ConflictEntry readEntry = conflictCheckMap.get(readHopID);
				if(readEntry != null && readEntry.logicalTransientProducerHopIDs.size() == 1
					&& readEntry.logicalTransientProducerHopIDs.contains(source.getKey()))
					indexed.computeIfAbsent(source.getKey(), ignored -> new LinkedHashSet<>())
						.add(readHopID);
			}
		}

		for(Map.Entry<Long,ConflictEntry> read : sortedConflictEntries(conflictCheckMap)) {
			long readHopID = read.getKey();
			ConflictEntry entry = read.getValue();
			if(entry == null || !entry.canChooseLOUT || entry.memberHopIDs == null
				|| entry.memberHopIDs.isEmpty())
				continue;
			Hop hopRef = memoTable.resolveOriginalHop(readHopID);
			if(!(hopRef instanceof DataOp)
				|| ((DataOp) hopRef).getOp() != Types.OpOpData.TRANSIENTREAD)
				continue;

			LinkedHashSet<Long> referencedWrites = new LinkedHashSet<>();
			for(long memberHopID : selectDecisionMembers(entry.memberHopIDs, memoTable)) {
				FederatedPlannerDpMemoTable.FedPlan plan =
					memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT);
				if(plan == null)
					continue;
				for(Pair<Long,FederatedOutput> childEdge : plan.getChildFedPlans()) {
					Hop childRef = memoTable.resolveOriginalHop(childEdge.getKey());
					if(childRef instanceof DataOp
						&& ((DataOp) childRef).getOp() == Types.OpOpData.TRANSIENTWRITE)
						referencedWrites.add(memoTable.resolveOriginalHopId(childEdge.getKey()));
				}
			}
			if(referencedWrites.size() != 1)
				continue;
			long writeHopID = referencedWrites.iterator().next();
			boolean logicalRelationAllowsSingleWriterPropagation =
				entry.logicalTransientProducerHopIDs.isEmpty()
					|| entry.logicalTransientProducerHopIDs.size() == 1
						&& entry.logicalTransientProducerHopIDs.contains(writeHopID);
			if(logicalRelationAllowsSingleWriterPropagation)
				indexed.computeIfAbsent(writeHopID, ignored -> new LinkedHashSet<>()).add(readHopID);
		}
		return indexed;
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

				Set<ConflictCostEdgeKey> seenCostEdges = new LinkedHashSet<>();
				for (FederatedPlannerDpMemoTable.FedPlan consumerPlan : tReadEntry.parents) {
				if (consumerPlan == null)
					continue;
				boolean consumerIsFed = consumerPlan.getExecType() == ExecType.FED;
				for (Pair<Long, FederatedOutput> edge : consumerPlan.getChildFedPlans()) {
					if (memoTable.resolveOriginalHopId(edge.getKey()) != tReadHopID)
						continue;
					long childHopID = edge.getKey();
					ConflictCostEdgeKey edgeKey = buildConflictCostEdgeKey(
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
			memoTable, hopID, entry, decisionMembers, observedOut, tentativeDecisions, null, null);
		double alternativeDelta = computeCompatiblePlanSelectionDelta(
			memoTable, hopID, entry, decisionMembers, alternativeOut, tentativeDecisions, null, null);
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

		return null;
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
			if (desiredChild != null && desiredChild != childEdge.getValue())
				return false;
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
				FederatedPlannerDpMemoTable.FedPlan cheapestSeed =
					(lPlan == null) ? fPlan :
					(fPlan == null) ? lPlan :
					(lPlan.getCumulativeCost() <= fPlan.getCumulativeCost()) ? lPlan : fPlan;
				seed = cheapestSeed;
				if (cheapestSeed != null) {
					FederatedPlannerDpMemoTable.FedPlan compatibleSeed = selectCompatiblePlanVariant(
						memoTable, rootHopID, cheapestSeed.getFedOutType(), outputDecisions);
					// Keep the concrete cheapest root when its child contract is incompatible.
					// The conflict entry below must see that exact failed demand in order to
					// select a compatible alternative output for a parentless additional root.
					// Replacing it with null silently removed the root from the decision map.
					if(compatibleSeed != null)
						seed = compatibleSeed;
				}
			}
			boolean requiresRootClosureDecision = seed != null
				&& outputDecisions != null && !outputDecisions.isEmpty()
				&& findStrictCompatiblePlanVariant(
					memoTable, rootHopID, seed.getFedOutType(), outputDecisions) == null;
			if (seed != null && (desiredOut != null || requiresRootClosureDecision)) {
				// Additional roots have no parent edge through which conflict discovery
				// can register them. Promote only roots already governed by a decision,
				// or roots whose cheapest seed is incompatible with child decisions;
				// neutral traversal-only roots remain outside the decision map.
				recordConflictUsage(
					conflictCheckMap, rootOrigId, seed.getFedOutType(), null, rootHopID, seed);
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

				// The selected parent edge proves that this concrete occurrence is part
				// of the plan forest even when the current family-wide decision has no
				// variant for the occurrence. Retain the member before traversal stops,
				// so the next feasibility refresh rejects that impossible family choice.
				// This records planner evidence only; it does not create or execute a
				// fallback plan.
				recordConflictUsage(
					conflictCheckMap, childOrigHopID, childOut, current, childHopID, childPlan);

				if (childPlan == null) {
					String msg = "NULL FedPlan for hop " + childHopID
						+ " as child of hop " + (currentHop != null ? currentHop.getHopID() : -1)
						+ " (" + (currentHop != null ? currentHop.getOpString() : "null") + ")";
					if (OptimizerUtils.isStrictFederatedConflictCheck())
						throw new DMLRuntimeException(msg);
					FederatedPlannerLogger.logNullFedPlanError(childHopID, msg);
					continue;
				}

				queue.add(childPlan);
			}
		}

		augmentLogicalTransientConflictUsages(memoTable, conflictCheckMap, outputDecisions);

		return conflictCheckMap;
	}

	/**
	 * Add exact CFG reaching-definition relations that are not necessarily present
	 * as physical children of the selected DP root forest. This extends the input
	 * of the existing local conflict resolver; it does not introduce a second/global
	 * optimizer and it does not manufacture a plan arm.
	 */
	private static void augmentLogicalTransientConflictUsages(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null || memoTable.analysis() == null || conflictCheckMap == null)
			return;
		for(FederatedPlannerDpMemoTable.TransientConflictRelation relation :
			memoTable.transientConflictRelations())
			recordAnalysisTransientRelation(memoTable, conflictCheckMap, outputDecisions,
				relation.source(), relation.target());
	}

	private static void recordAnalysisTransientRelation(
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> outputDecisions,
		PlacementAnalysis.HopOccurrenceProjection sourceOccurrence,
		PlacementAnalysis.HopOccurrenceProjection targetOccurrence) {

		FederatedPlannerDpMemoTable.FedPlan sourcePlan =
			selectLogicalTransientOccurrencePlan(memoTable, sourceOccurrence, outputDecisions);
		FederatedPlannerDpMemoTable.FedPlan targetPlan =
			selectLogicalTransientOccurrencePlan(memoTable, targetOccurrence, outputDecisions);
		if (sourcePlan == null || targetPlan == null)
			return;

		long sourceOrigHopID = memoTable.resolveOriginalHopId(sourcePlan.getHopID());
		long targetOrigHopID = memoTable.resolveOriginalHopId(targetPlan.getHopID());
		recordConflictMember(conflictCheckMap, sourceOrigHopID, sourcePlan.getHopID(), sourcePlan);
		recordConflictMember(conflictCheckMap, targetOrigHopID, targetPlan.getHopID(), targetPlan);
		ConflictEntry sourceEntry = conflictCheckMap.get(sourceOrigHopID);
		ConflictEntry targetEntry = conflictCheckMap.get(targetOrigHopID);
		sourceEntry.logicalTransientReadHopIDs.add(targetOrigHopID);
		targetEntry.logicalTransientProducerHopIDs.add(sourceOrigHopID);
	}

	private static void recordConflictMember(
		Map<Long, ConflictEntry> conflictCheckMap,
		long originalHopID,
		long memberHopID,
		FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {

		ConflictEntry entry = conflictCheckMap.get(originalHopID);
		if (entry == null)
			conflictCheckMap.put(originalHopID, new ConflictEntry(memberHopID, selectedMemberPlan));
		else
			entry.addMember(memberHopID, selectedMemberPlan);
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectLogicalTransientOccurrencePlan(
		FederatedPlannerDpMemoTable memoTable,
		PlacementAnalysis.HopOccurrenceProjection occurrence,
		Map<Long, FederatedOutput> outputDecisions) {

		long originalHopID = memoTable.resolveOriginalHopId(occurrence.hop().getHopID());
		FederatedOutput desired = outputDecisions != null ? outputDecisions.get(originalHopID) : null;
		FederatedPlannerDpMemoTable.FedPlan best = selectCheapestLogicalTransientOccurrencePlan(
			memoTable, occurrence, desired, outputDecisions, true);
		if (best == null)
			best = selectCheapestLogicalTransientOccurrencePlan(
				memoTable, occurrence, desired, outputDecisions, false);
		if (best == null && desired != null)
			best = selectCheapestLogicalTransientOccurrencePlan(
				memoTable, occurrence, null, outputDecisions, true);
		if (best == null)
			best = selectCheapestLogicalTransientOccurrencePlan(
				memoTable, occurrence, null, outputDecisions, false);
		return best;
	}

	private static FederatedPlannerDpMemoTable.FedPlan selectCheapestLogicalTransientOccurrencePlan(
		FederatedPlannerDpMemoTable memoTable,
		PlacementAnalysis.HopOccurrenceProjection occurrence,
		FederatedOutput requiredOutput,
		Map<Long, FederatedOutput> outputDecisions,
		boolean requireChildCompatibility) {

		FederatedPlannerDpMemoTable.FedPlan best = null;
		for (FederatedPlannerDpMemoTable.OccurrencePlanArm arm :
			memoTable.getAllExactPlanVariantsForOccurrence(occurrence)) {
			FederatedPlannerDpMemoTable.FedPlan candidate = arm.plan();
			if (requiredOutput != null && candidate.getFedOutType() != requiredOutput)
				continue;
			if (requireChildCompatibility
				&& !isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
				continue;
			if (best == null || candidate.getCumulativeCost() < best.getCumulativeCost()
				|| candidate.getCumulativeCost() == best.getCumulativeCost()
					&& (candidate.getFedOutType() == FederatedOutput.LOUT
						&& best.getFedOutType() == FederatedOutput.FOUT
						|| candidate.getFedOutType() == best.getFedOutType()
							&& candidate.getHopID() < best.getHopID()))
				best = candidate;
		}
		return best;
	}

	private static void recordConflictUsage(
		Map<Long, ConflictEntry> conflictCheckMap,
		long originalHopID,
		FederatedOutput observedOut,
		FederatedPlannerDpMemoTable.FedPlan parent,
		long memberHopID,
		FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {

		if (conflictCheckMap == null || observedOut == null)
			return;
		ConflictEntry entry = conflictCheckMap.get(originalHopID);
		if (entry == null)
			conflictCheckMap.put(originalHopID,
				new ConflictEntry(observedOut, parent, memberHopID, selectedMemberPlan));
		else
			entry.addUsage(observedOut, parent, memberHopID, selectedMemberPlan);
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
					tentativeDecisions, conflictCheckMap, parentVariantDeltaCache)
				: Double.POSITIVE_INFINITY;
			double fOutAdditionalCost = canChooseFOUT
				? computeCompatiblePlanSelectionDelta(
					memoTable, hopID, entry, decisionMembers, FederatedOutput.FOUT,
					tentativeDecisions, conflictCheckMap, parentVariantDeltaCache)
				: Double.POSITIVE_INFINITY;
		Set<ConflictCostEdgeKey> seenCostEdges = new LinkedHashSet<>();
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
					ConflictCostEdgeKey edgeKey = buildConflictCostEdgeKey(
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
		Map<Long, ConflictEntry> conflictCheckMap,
		ParentVariantDeltaCache parentVariantDeltaCache) {

		if (memoTable == null || entry == null || targetOut == null)
			return 0.0;

		Map<Long, FederatedOutput> observedMemberOutputs = parentVariantDeltaCache == null
			? collectObservedMemberOutputs(memoTable, hopID, entry, decisionMembers)
			: parentVariantDeltaCache.observedMemberOutputs.computeIfAbsent(entry,
				ignored -> Collections.unmodifiableMap(collectObservedMemberOutputs(
					memoTable, hopID, entry, decisionMembers)));
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
				final FederatedPlannerDpMemoTable.FedPlan currentPlanForTrace = currentPlan;
				final FederatedPlannerDpMemoTable.FedPlan targetPlanForTrace = targetPlan;
				FederatedPlannerTrace.logLazy(memberHopRef, "DP-OutputDecision-Member", () ->
					String.format(Locale.ROOT,
						"orig=%d member=%d virtual=%s current=%s target=%s currentExec=%s targetExec=%s currentCost=%.6f targetCost=%.6f delta=%.6f multiplicity=%.6f loop=%s",
						hopID, memberHopID, memoTable.isVirtualClone(memberHopID), currentOut, targetOut,
						currentPlanForTrace.getExecType(), targetPlanForTrace.getExecType(), currentShare, targetShare,
						memberDelta, targetPlanForTrace.getMultiplicity(),
						formatLoopContext(targetPlanForTrace.getLoopContext())));
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

		// Additional roots have no downstream parent edge, but their selected seed
		// is still an executed plan-forest occurrence. Use the recorded selection as
		// the current output only when no parent edge already supplied that evidence.
		for (Pair<Long, FederatedOutput> selected : entry.selectedMemberPlans.keySet()) {
			long memberHopID = selected.getLeft();
			if (decisionMembers != null && !decisionMembers.isEmpty()
				&& !decisionMembers.contains(memberHopID))
				continue;
			if (!observed.containsKey(memberHopID))
				observed.put(memberHopID, selected.getRight());
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
				FederatedPlannerTrace.logLazy(childHopRef, "DP-ParentVariantDelta", () -> String.format(Locale.ROOT,
					"parentHop=%d parentExec=%s fromOut=%s toOut=%s delta=%.6f mode=%s rawParentVariant=%.6f childForwarding=%.6f",
					parentPlan != null && parentPlan.getHopRef() != null ? parentPlan.getHopRef().getHopID() : -1L,
					parentPlan != null ? parentPlan.getExecType() : null,
					fromOut, toOut, parentVariantDelta, "parent_variant", parentVariantDelta, childForwardingDelta));
			}
			return parentVariantDelta;
		}

		if (trace) {
			FederatedPlannerTrace.logLazy(childHopRef, "DP-ParentVariantDelta", () -> String.format(Locale.ROOT,
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
		if (parentVariantDeltaCache != null) {
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
			FederatedPlannerTrace.logLazy(childHopRef, "DP-ParentVariantSearch", () -> String.format(Locale.ROOT,
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
		for (FederatedPlannerDpMemoTable.FedPlanVariants variants :
			new FederatedPlannerDpMemoTable.FedPlanVariants[] {variantsLOUT, variantsFOUT}) {
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
					final long traceMatchedEdgeHopId = matchedEdgeHopId;
					final boolean traceMatchesDesired = ok;
					FederatedPlannerTrace.logLazy(childHopRef, "DP-ParentVariantCandidate", () -> String.format(Locale.ROOT,
						"parentHop=%d candExec=%s candOut=%s candCost=%.6f edgeHop=%d edgeOrig=%d matchDesired=%s delta=%.6f rawDelta=%.6f childShareAdj=%.6f overlapParentShareAdj=%.6f downstreamForwardingDelta=%.6f",
						parentHopID, cand.getExecType(), cand.getFedOutType(), cand.getCumulativeCost(),
						traceMatchedEdgeHopId, childOrigHopID, traceMatchesDesired, adjustedDelta, rawDelta,
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
				final int traceReferencedChildVariants = referencedChildVariants;
				final int traceMatchingDesiredVariants = matchingDesiredVariants;
				FederatedPlannerTrace.logLazy(childHopRef, "DP-ParentVariantResult", () -> String.format(Locale.ROOT,
					"parentHop=%d result=no_compatible_variant referencedChildVariants=%d matchingDesiredVariants=%d",
					parentHopID, traceReferencedChildVariants, traceMatchingDesiredVariants));
			}
			if (cacheKey != null)
				parentVariantDeltaCache.put(cacheKey, Double.NaN);
			return Double.NaN;
		}
		double delta = bestDelta;
		if (trace) {
			final int traceReferencedChildVariants = referencedChildVariants;
			final int traceMatchingDesiredVariants = matchingDesiredVariants;
			FederatedPlannerTrace.logLazy(childHopRef, "DP-ParentVariantResult", () -> String.format(Locale.ROOT,
				"parentHop=%d result=compatible_variant referencedChildVariants=%d matchingDesiredVariants=%d bestDelta=%.6f",
				parentHopID, traceReferencedChildVariants, traceMatchingDesiredVariants, delta));
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
		Set<ConflictCostEdgeKey> seenCostEdges = new LinkedHashSet<>();
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
				ConflictCostEdgeKey edgeKey = buildConflictCostEdgeKey(
					memoTable, downstreamPlan, edge.getKey(), edge.getValue());
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

		double adjustment = 0.0;
		List<Pair<Long,FederatedOutput>> currentEdges = currentParentPlan.getChildFedPlans();
		for (int edgeIndex = 0; edgeIndex < currentEdges.size(); edgeIndex++) {
			Pair<Long, FederatedOutput> currentEdge = currentEdges.get(edgeIndex);
			if (currentEdge == null || currentEdge.getValue() == null)
				continue;
			long edgeOrigHopID = memoTable.resolveOriginalHopId(currentEdge.getKey());
			if (edgeOrigHopID == targetChildOrigHopID
				|| !isObservedDirectParent(memoTable, targetEntry, edgeOrigHopID)
				|| earlierEligibleEdgeHasOriginal(memoTable, currentEdges, edgeIndex,
					edgeOrigHopID, targetChildOrigHopID, targetEntry)) {
				continue;
			}
			Pair<Long, FederatedOutput> candidateEdge = firstChildEdgeForOriginal(
				memoTable, candidateParentPlan.getChildFedPlans(), edgeOrigHopID,
				targetChildOrigHopID);
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

	private static boolean isObservedDirectParent(FederatedPlannerDpMemoTable memoTable,
		ConflictEntry targetEntry, long parentOrigHopID) {
		for(FederatedPlannerDpMemoTable.FedPlan parent : targetEntry.parents)
			if(parent != null && parent.getHopRef() != null
				&& memoTable.resolveOriginalHopId(parent.getHopRef().getHopID()) == parentOrigHopID)
				return true;
		return false;
	}

	private static boolean earlierEligibleEdgeHasOriginal(FederatedPlannerDpMemoTable memoTable,
		List<Pair<Long,FederatedOutput>> edges, int exclusiveEnd, long edgeOrigHopID,
		long targetChildOrigHopID, ConflictEntry targetEntry) {
		for(int index = 0; index < exclusiveEnd; index++) {
			Pair<Long,FederatedOutput> edge = edges.get(index);
			if(edge == null || edge.getValue() == null)
				continue;
			long candidateOrig = memoTable.resolveOriginalHopId(edge.getKey());
			if(candidateOrig == edgeOrigHopID && candidateOrig != targetChildOrigHopID
				&& isObservedDirectParent(memoTable, targetEntry, candidateOrig))
				return true;
		}
		return false;
	}

	private static Pair<Long,FederatedOutput> firstChildEdgeForOriginal(
		FederatedPlannerDpMemoTable memoTable, List<Pair<Long,FederatedOutput>> edges,
		long edgeOrigHopID, long excludedOrigHopID) {
		for(Pair<Long,FederatedOutput> edge : edges)
			if(edge != null && edge.getValue() != null) {
				long candidateOrig = memoTable.resolveOriginalHopId(edge.getKey());
				if(candidateOrig != excludedOrigHopID && candidateOrig == edgeOrigHopID)
					return edge;
			}
		return null;
	}

	private static double computeTransientReadPlanShareForParent(
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable memoTable,
		TransientReadPlanShareCache transientReadPlanShareCache) {

		if (childPlan == null)
			return 0.0;
		// Keep trace-on and trace-off planning on the same cached computation path.
		if (transientReadPlanShareCache != null) {
			Double cached = transientReadPlanShareCache.get(childPlan);
			if (cached != null)
				return cached;
		}
		double share = childPlan.getFedOutType() == FederatedOutput.FOUT
			? FederatedPlannerDpCostEstimator.computeStableTransientReadFoutCumulativeShareForParent(
				childPlan, memoTable)
			: FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				childPlan.getCumulativeCost(), childPlan);
		if (transientReadPlanShareCache != null)
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
		Double exactRelocationShare = exactRelocationForwardingCostShare(
			memoTable, parentIsFed, childOut, childPlan, parentPlan);
		if(exactRelocationShare != null)
			return exactRelocationShare;

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

	/**
	 * Returns the exact relocation cost already selected and embedded in a FED
	 * parent variant, or {@code null} when the caller is evaluating a different
	 * hypothetical child arm and must use the generic estimate.
	 */
	private static Double exactRelocationForwardingCostShare(
		FederatedPlannerDpMemoTable memoTable, boolean parentIsFed,
		FederatedOutput childOut, FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		if(memoTable == null || memoTable.analysis() == null || !parentIsFed
			|| parentPlan.getExecType() != ExecType.FED
			|| childOut != childPlan.getFedOutType()
			|| parentPlan.getDirectRelocationChoices().isEmpty()
			|| parentPlan.getChildFedPlans() == null
			|| parentPlan.getChildFedPlans().stream().noneMatch(edge -> edge != null
				&& edge.getKey() == childPlan.getHopID() && edge.getValue() == childOut))
			return null;

		PlacementAnalysis.HopOccurrenceProjection childOccurrence =
			memoTable.requirePlanCarrierOccurrence(childPlan.getHopRef());
		ValueVersionKey sourceValue = memoTable.analysis().graph().node(childOccurrence.key())
			.orElseThrow(() -> new IllegalStateException(
				"DP relocation source occurrence is absent from its analysis graph"))
			.valueVersion();
		boolean covered = parentPlan.getDirectRelocationChoices().stream()
			.anyMatch(choice -> choice.demand().sourceValueVersion().equals(sourceValue));
		if(!covered)
			return null;

		double total = 0.0;
		for(Map.Entry<RelocationActionKey,Double> entry :
			parentPlan.getDirectRelocationActionCosts().entrySet())
			if(entry.getKey().sourceValueVersion().equals(sourceValue))
				total += entry.getValue();
		return total;
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

	private static ConflictCostEdgeKey buildConflictCostEdgeKey(
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
		return new ConflictCostEdgeKey(parentOrigHopID, childOrigHopID, parentExec,
			originalOut, Double.doubleToLongBits(multiplicity),
			parentPlan != null ? parentPlan.getLoopContext() : null);
	}

	/** Structural de-duplication key without recursive String construction. */
	private static final class ConflictCostEdgeKey {
		final long parentOrigHopID;
		final long childOrigHopID;
		final ExecType parentExec;
		final FederatedOutput originalOut;
		final long multiplicityBits;
		final List<Pair<Long,Double>> loopContext;
		final int hash;

		ConflictCostEdgeKey(long parentOrigHopID, long childOrigHopID, ExecType parentExec,
			FederatedOutput originalOut, long multiplicityBits,
			List<Pair<Long,Double>> loopContext) {
			this.parentOrigHopID = parentOrigHopID;
			this.childOrigHopID = childOrigHopID;
			this.parentExec = parentExec;
			this.originalOut = originalOut;
			this.multiplicityBits = multiplicityBits;
			this.loopContext = loopContext;
			int value = Long.hashCode(parentOrigHopID);
			value = 31 * value + Long.hashCode(childOrigHopID);
			value = 31 * value + (parentExec == null ? 0 : parentExec.hashCode());
			value = 31 * value + (originalOut == null ? 0 : originalOut.hashCode());
			value = 31 * value + Long.hashCode(multiplicityBits);
			if(loopContext != null)
				for(Pair<Long,Double> part : loopContext) {
					long loopID = part != null && part.getLeft() != null ? part.getLeft() : -1L;
					long weight = Double.doubleToLongBits(
						part != null && part.getRight() != null ? part.getRight() : 0.0);
					value = 31 * value + Long.hashCode(loopID);
					value = 31 * value + Long.hashCode(weight);
				}
			this.hash = value;
		}

		@Override public int hashCode() { return hash; }

		@Override
		public boolean equals(Object value) {
			if(this == value)
				return true;
			if(!(value instanceof ConflictCostEdgeKey that)
				|| parentOrigHopID != that.parentOrigHopID
				|| childOrigHopID != that.childOrigHopID
				|| parentExec != that.parentExec || originalOut != that.originalOut
				|| multiplicityBits != that.multiplicityBits)
				return false;
			int size = loopContext == null ? 0 : loopContext.size();
			if(size != (that.loopContext == null ? 0 : that.loopContext.size()))
				return false;
			for(int index = 0; index < size; index++) {
				Pair<Long,Double> left = loopContext.get(index);
				Pair<Long,Double> right = that.loopContext.get(index);
				long leftID = left != null && left.getLeft() != null ? left.getLeft() : -1L;
				long rightID = right != null && right.getLeft() != null ? right.getLeft() : -1L;
				long leftWeight = Double.doubleToLongBits(
					left != null && left.getRight() != null ? left.getRight() : 0.0);
				long rightWeight = Double.doubleToLongBits(
					right != null && right.getRight() != null ? right.getRight() : 0.0);
				if(leftID != rightID || leftWeight != rightWeight)
					return false;
			}
			return true;
		}
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

	/**
	 * One top-level output-reconciliation context. Recursive lock simulations explore
	 * the same immutable memo frontier, so rebuilding occurrence closure, conflict
	 * forests, transient-parent indexes, and sharing-aware score decompositions inside
	 * every simulation is redundant. Per-invocation mutable delta state remains local.
	 */
	private static final class DecisionResolutionContext {
		final FederatedPlannerDpMemoTable memoTable;
		final FederatedPlannerDpMemoTable.FedPlan rootPlan;
		final ConflictMapCache conflictMapCache;
		final TransientReadParentsCache transientReadParentsCache;
		final SimulationDecisionCache simulationDecisionCache;
		final DecisionMapScoreCache scoreCache;

		DecisionResolutionContext(FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan) {
			this.memoTable = Objects.requireNonNull(memoTable, "memoTable");
			this.rootPlan = rootPlan;
			this.conflictMapCache = new ConflictMapCache(memoTable, rootPlan);
			this.transientReadParentsCache =
				new TransientReadParentsCache(memoTable);
			this.simulationDecisionCache = new SimulationDecisionCache(this);
			this.scoreCache = new DecisionMapScoreCache(memoTable, rootPlan, conflictMapCache);
		}

		DecisionResolutionContext requireOwner(FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan) {
			if(this.memoTable != memoTable || this.rootPlan != rootPlan)
				throw new IllegalArgumentException("DP decision context has a different memo/root owner");
			return this;
		}
	}

	/** Cached selected conflict forests keyed by the complete output-decision map. */
	private static final class ConflictMapCache {
		private final FederatedPlannerDpMemoTable memoTable;
		private final FederatedPlannerDpMemoTable.FedPlan rootPlan;
		private final Map<DecisionMapScoreKey, ConflictForestSnapshot> values = new HashMap<>();

		ConflictMapCache(FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan) {
			this.memoTable = Objects.requireNonNull(memoTable, "memoTable");
			this.rootPlan = rootPlan;
		}

		Map<Long,ConflictEntry> getFeasible(Map<Long,FederatedOutput> decisions) {
			ConflictForestSnapshot snapshot = snapshot(decisions);
			if(snapshot.feasible == null) {
				snapshot.feasible = copyConflictFeasibilityEntries(snapshot.unrefreshed);
				refreshConflictChoiceFeasibility(snapshot.feasible, memoTable);
			}
			return snapshot.feasible;
		}

		private ConflictForestSnapshot snapshot(Map<Long,FederatedOutput> decisions) {
			return snapshot(new DecisionMapScoreKey(decisions));
		}

		private ConflictForestSnapshot snapshot(DecisionMapScoreKey key) {
			ConflictForestSnapshot cached = values.get(key);
			if(cached != null)
				return cached;
			Map<Long,ConflictEntry> selected = rootPlan == null
				? Collections.emptyMap()
				: collectConflictsSingleBFS(memoTable, rootPlan, key.outputDecisions);
			ConflictForestSnapshot created = new ConflictForestSnapshot(selected);
			values.put(key, created);
			return created;
		}
	}

	private static final class ConflictForestSnapshot {
		final Map<Long,ConflictEntry> unrefreshed;
		Map<Long,ConflictEntry> feasible;
		DecisionMapScoreBreakdown score;

		ConflictForestSnapshot(Map<Long,ConflictEntry> unrefreshed) {
			this.unrefreshed = Objects.requireNonNull(unrefreshed, "unrefreshed");
		}
	}

	private static final class ParentVariantDeltaCache {
		private final Map<ParentVariantDeltaKey, Double> values = new HashMap<>();
		private final Map<ConflictEntry,Map<Long,FederatedOutput>> observedMemberOutputs =
			new IdentityHashMap<>();
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
		private final FederatedPlannerDpMemoTable memoTable;
		private final Map<Map<Long,ConflictEntry>,Map<Long,LinkedHashSet<Long>>> values =
			new IdentityHashMap<>();

		TransientReadParentsCache(FederatedPlannerDpMemoTable memoTable) {
			this.memoTable = Objects.requireNonNull(memoTable, "memoTable");
		}

		LinkedHashSet<Long> get(Map<Long,ConflictEntry> conflictCheckMap,
			long tWriteOrigHopID) {
			Map<Long,LinkedHashSet<Long>> byWrite = values.computeIfAbsent(conflictCheckMap,
				map -> indexTransientReadParents(memoTable, map));
			return new LinkedHashSet<>(byWrite.getOrDefault(tWriteOrigHopID,
				new LinkedHashSet<>()));
		}
	}

	private static final class SimulationDecisionCache {
		private final DecisionResolutionContext context;
		private final Map<SimulationDecisionKey, Map<Long, FederatedOutput>> values = new HashMap<>();

		SimulationDecisionCache(DecisionResolutionContext context) {
			this.context = Objects.requireNonNull(context, "context");
		}

		Map<Long, FederatedOutput> get(SimulationDecisionKey key) {
			return values.get(key);
		}

		void put(SimulationDecisionKey key, Map<Long, FederatedOutput> value) {
			values.put(key, value != null
				? Collections.unmodifiableMap(new HashMap<>(value)) : Collections.emptyMap());
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
			this.hash = 31 * unorderedDecisionMapHash(this.baseDecisions)
				+ unorderedDecisionMapHash(this.lockedDecisions);
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
		private final ConflictMapCache conflictMapCache;

		DecisionMapScoreCache(
			FederatedPlannerDpMemoTable memoTable,
			FederatedPlannerDpMemoTable.FedPlan rootPlan,
			ConflictMapCache conflictMapCache) {
			this.memoTable = memoTable;
			this.rootPlan = rootPlan;
			this.conflictMapCache = Objects.requireNonNull(conflictMapCache, "conflictMapCache");
		}

		DecisionMapScoreBreakdown get(Map<Long, FederatedOutput> outputDecisions) {
			DecisionMapScoreKey key = new DecisionMapScoreKey(outputDecisions);
			ConflictForestSnapshot snapshot = conflictMapCache.snapshot(key);
			if(snapshot.score == null)
				snapshot.score = computeDecisionMapScoreBreakdownWithConflicts(
					memoTable, rootPlan, key.outputDecisions, snapshot.unrefreshed);
			return snapshot.score;
		}

		Map<Long,ConflictEntry> conflicts(Map<Long,FederatedOutput> decisions) {
			return conflictMapCache.getFeasible(decisions);
		}
	}

	private static final class DecisionMapScoreKey {
		final Map<Long, FederatedOutput> outputDecisions;
		final int hash;

		DecisionMapScoreKey(Map<Long, FederatedOutput> outputDecisions) {
			this.outputDecisions = outputDecisions != null ? new HashMap<>(outputDecisions) : Collections.emptyMap();
			this.hash = unorderedDecisionMapHash(this.outputDecisions);
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

	private static int unorderedDecisionMapHash(Map<Long, FederatedOutput> decisions) {
		if (decisions == null || decisions.isEmpty())
			return 0;
		long sum = 0L;
		long xor = 0L;
		for (Map.Entry<Long, FederatedOutput> entry : decisions.entrySet()) {
			long hopID = entry.getKey() != null ? entry.getKey() : 0L;
			long output = entry.getValue() != null ? entry.getValue().ordinal() + 1L : 0L;
			long mixed = mixDecisionMapHash(hopID ^ output * 0x9E3779B97F4A7C15L);
			sum += mixed;
			xor ^= Long.rotateLeft(mixed, (int) (hopID & 63L));
		}
		long mixed = mixDecisionMapHash(sum ^ Long.rotateLeft(xor, 23)
			^ decisions.size() * 0xD6E8FEB86659FD93L);
		return (int) (mixed ^ mixed >>> 32);
	}

	private static long mixDecisionMapHash(long value) {
		value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
		value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
		return value ^ value >>> 31;
	}

	/**
	 * Applies one closure candidate directly to its owned decision map while
	 * retaining only the touched-key delta needed for rollback.  Score-cache keys
	 * still snapshot the complete candidate, but rejected candidates no longer
	 * allocate a second full decision map before scoring.
	 */
	private static final class DecisionMapTransaction
		extends AbstractMap<Long, FederatedOutput> implements AutoCloseable {
		private final Map<Long, FederatedOutput> decisions;
		private final LinkedHashMap<Long, PreviousDecision> previous = new LinkedHashMap<>();
		private boolean completed;

		DecisionMapTransaction(Map<Long, FederatedOutput> decisions) {
			this.decisions = Objects.requireNonNull(decisions, "decisions");
		}

		private void capture(Long key) {
			if(!previous.containsKey(key))
				previous.put(key, new PreviousDecision(
					decisions.containsKey(key), decisions.get(key)));
		}

		@Override
		public FederatedOutput put(Long key, FederatedOutput value) {
			FederatedOutput current = decisions.get(key);
			if(decisions.containsKey(key) && Objects.equals(current, value))
				return current;
			capture(key);
			return decisions.put(key, value);
		}

		@Override
		public void putAll(Map<? extends Long, ? extends FederatedOutput> values) {
			for(Map.Entry<? extends Long, ? extends FederatedOutput> entry : values.entrySet())
				put(entry.getKey(), entry.getValue());
		}

		@Override
		public FederatedOutput remove(Object key) {
			if(!(key instanceof Long) || !decisions.containsKey(key))
				return null;
			capture((Long) key);
			return decisions.remove(key);
		}

		@Override
		public void clear() {
			for(Long key : new ArrayList<>(decisions.keySet()))
				capture(key);
			decisions.clear();
		}

		@Override
		public FederatedOutput get(Object key) {
			return decisions.get(key);
		}

		@Override
		public boolean containsKey(Object key) {
			return decisions.containsKey(key);
		}

		@Override
		public int size() {
			return decisions.size();
		}

		@Override
		public boolean isEmpty() {
			return decisions.isEmpty();
		}

		@Override
		public Set<Map.Entry<Long, FederatedOutput>> entrySet() {
			return decisions.entrySet();
		}

		boolean hasEffectiveChanges() {
			for(Map.Entry<Long, PreviousDecision> entry : previous.entrySet()) {
				PreviousDecision prior = entry.getValue();
				boolean present = decisions.containsKey(entry.getKey());
				if(present != prior.present
					|| present && !Objects.equals(decisions.get(entry.getKey()), prior.output))
					return true;
			}
			return false;
		}

		Map<Long, FederatedOutput> beforeView() {
			return previous.isEmpty()
				? decisions : new PreviousDecisionMap(decisions, previous);
		}

		void commit() {
			completed = true;
		}

		private void rollback() {
			for(Map.Entry<Long, PreviousDecision> entry : previous.entrySet()) {
				PreviousDecision prior = entry.getValue();
				if(prior.present)
					decisions.put(entry.getKey(), prior.output);
				else
					decisions.remove(entry.getKey());
			}
			completed = true;
		}

		@Override
		public void close() {
			if(!completed)
				rollback();
		}
	}

	private static final class PreviousDecision {
		final boolean present;
		final FederatedOutput output;

		PreviousDecision(boolean present, FederatedOutput output) {
			this.present = present;
			this.output = output;
		}
	}

	/** Read-only view of the decision map immediately before a transaction. */
	private static final class PreviousDecisionMap extends AbstractMap<Long, FederatedOutput> {
		private final Map<Long, FederatedOutput> current;
		private final Map<Long, PreviousDecision> previous;

		PreviousDecisionMap(Map<Long, FederatedOutput> current,
			Map<Long, PreviousDecision> previous) {
			this.current = current;
			this.previous = previous;
		}

		@Override
		public FederatedOutput get(Object key) {
			PreviousDecision prior = previous.get(key);
			return prior != null ? prior.output : current.get(key);
		}

		@Override
		public boolean containsKey(Object key) {
			PreviousDecision prior = previous.get(key);
			return prior != null ? prior.present : current.containsKey(key);
		}

		@Override
		public int size() {
			int size = current.size();
			for(Map.Entry<Long, PreviousDecision> entry : previous.entrySet()) {
				boolean currentPresent = current.containsKey(entry.getKey());
				if(entry.getValue().present && !currentPresent)
					size++;
				else if(!entry.getValue().present && currentPresent)
					size--;
			}
			return size;
		}

		@Override
		public Set<Map.Entry<Long, FederatedOutput>> entrySet() {
			LinkedHashMap<Long, FederatedOutput> restored = new LinkedHashMap<>(current);
			for(Map.Entry<Long, PreviousDecision> entry : previous.entrySet()) {
				PreviousDecision prior = entry.getValue();
				if(prior.present)
					restored.put(entry.getKey(), prior.output);
				else
					restored.remove(entry.getKey());
			}
			return Collections.unmodifiableMap(restored).entrySet();
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

		private LocalMaterializeRequest(LocalMaterializeRequest source) {
			this(source.producerHopID, source.producerHop);
			this.consumerHops.putAll(source.consumerHops);
			this.consumerOutputs.putAll(source.consumerOutputs);
			this.fTypeHint = source.fTypeHint;
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
		int incompatiblePlanCount;
		final LinkedHashSet<Long> exactSelectionConflictHopIDs = new LinkedHashSet<>();
		final List<ExactOccurrenceConflict> exactOccurrenceConflicts = new ArrayList<>();
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

	private record ExactOccurrenceConflict(CompiledHopKey occurrenceKey, List<Long> decisionHopIDs) {
		private ExactOccurrenceConflict {
			Objects.requireNonNull(occurrenceKey, "occurrenceKey");
			decisionHopIDs = List.copyOf(Objects.requireNonNull(decisionHopIDs, "decisionHopIDs"));
			if (decisionHopIDs.isEmpty())
				throw new IllegalArgumentException("Exact occurrence conflict has no decision coordinates");
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
		final LinkedHashSet<Long> logicalTransientProducerHopIDs;
		final LinkedHashSet<Long> logicalTransientReadHopIDs;
		boolean seenLOUT;
		boolean seenFOUT;
		boolean canChooseLOUT;
		boolean canChooseFOUT;

		ConflictEntry(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID,
			FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {
			this.parents = new LinkedHashSet<>();
			this.memberHopIDs = new LinkedHashSet<>();
			this.selectedMemberPlans = new LinkedHashMap<>();
			this.logicalTransientProducerHopIDs = new LinkedHashSet<>();
			this.logicalTransientReadHopIDs = new LinkedHashSet<>();
			if (parent != null)
				this.parents.add(parent);
			this.memberHopIDs.add(memberHopID);
			if (selectedMemberPlan != null)
				this.selectedMemberPlans.put(Pair.of(memberHopID, out), selectedMemberPlan);
			this.seenLOUT = (out == FederatedOutput.LOUT);
			this.seenFOUT = (out == FederatedOutput.FOUT);
			this.canChooseLOUT = true;
			this.canChooseFOUT = true;
		}

		ConflictEntry(long memberHopID, FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {
			this.parents = new LinkedHashSet<>();
			this.memberHopIDs = new LinkedHashSet<>();
			this.selectedMemberPlans = new LinkedHashMap<>();
			this.logicalTransientProducerHopIDs = new LinkedHashSet<>();
			this.logicalTransientReadHopIDs = new LinkedHashSet<>();
			addMember(memberHopID, selectedMemberPlan);
			this.canChooseLOUT = true;
			this.canChooseFOUT = true;
		}

		ConflictEntry(ConflictEntry source) {
			this.parents = new LinkedHashSet<>(source.parents);
			this.memberHopIDs = new LinkedHashSet<>(source.memberHopIDs);
			this.selectedMemberPlans = new LinkedHashMap<>(source.selectedMemberPlans);
			this.logicalTransientProducerHopIDs =
				new LinkedHashSet<>(source.logicalTransientProducerHopIDs);
			this.logicalTransientReadHopIDs = new LinkedHashSet<>(source.logicalTransientReadHopIDs);
			this.seenLOUT = source.seenLOUT;
			this.seenFOUT = source.seenFOUT;
			this.canChooseLOUT = source.canChooseLOUT;
			this.canChooseFOUT = source.canChooseFOUT;
		}

		private ConflictEntry(ConflictEntry source, boolean shareImmutableTopology) {
			if(!shareImmutableTopology)
				throw new IllegalArgumentException("Conflict feasibility view must share immutable topology");
			this.parents = source.parents;
			this.memberHopIDs = source.memberHopIDs;
			this.selectedMemberPlans = source.selectedMemberPlans;
			this.logicalTransientProducerHopIDs = source.logicalTransientProducerHopIDs;
			this.logicalTransientReadHopIDs = source.logicalTransientReadHopIDs;
			this.seenLOUT = source.seenLOUT;
			this.seenFOUT = source.seenFOUT;
			this.canChooseLOUT = source.canChooseLOUT;
			this.canChooseFOUT = source.canChooseFOUT;
		}

		void merge(ConflictEntry source) {
			this.parents.addAll(source.parents);
			this.memberHopIDs.addAll(source.memberHopIDs);
			source.selectedMemberPlans.forEach(this.selectedMemberPlans::putIfAbsent);
			this.logicalTransientProducerHopIDs.addAll(source.logicalTransientProducerHopIDs);
			this.logicalTransientReadHopIDs.addAll(source.logicalTransientReadHopIDs);
			this.seenLOUT |= source.seenLOUT;
			this.seenFOUT |= source.seenFOUT;
			this.canChooseLOUT &= source.canChooseLOUT;
			this.canChooseFOUT &= source.canChooseFOUT;
		}

		void addUsage(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID,
			FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {
			if (parent != null)
				this.parents.add(parent);
			this.memberHopIDs.add(memberHopID);
			if (selectedMemberPlan != null)
				this.selectedMemberPlans.putIfAbsent(Pair.of(memberHopID, out), selectedMemberPlan);
			if (out == FederatedOutput.LOUT)
				this.seenLOUT = true;
			else if (out == FederatedOutput.FOUT)
				this.seenFOUT = true;
		}

		void addMember(long memberHopID,
			FederatedPlannerDpMemoTable.FedPlan selectedMemberPlan) {
			this.memberHopIDs.add(memberHopID);
			if (selectedMemberPlan != null)
				this.selectedMemberPlans.putIfAbsent(
					Pair.of(memberHopID, selectedMemberPlan.getFedOutType()), selectedMemberPlan);
		}
	}
}
