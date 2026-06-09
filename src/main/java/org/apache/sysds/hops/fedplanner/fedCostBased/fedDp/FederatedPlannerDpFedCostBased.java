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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.FederatedCostModel;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
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
	private static final int MAX_ENUM_INPUTS = 20; // guard against 2^n blowups and shift overflow
	// Candidate 1: isolate the near-tie transient FOUT bundle widening heuristic
	// without disabling seed/refine, locked transient propagation, or genuinely
	// cost-improving transient-family decisions.
	private static final boolean ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE = false;
	private static final double TRANSIENT_FOUT_BUNDLE_TIE_REL_TOL = 2.5e-3;
	private static final boolean ENABLE_TRANSIENT_FAMILY_SCORING_TRACE = false;
	private static final boolean ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION = false;
	private static final boolean ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL = false;

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = FederatedPlannerDpCostEnumerator.enumerateProgram(
			prog, memoTable, FederatedPlannerTrace.isEnabled());

		Map<Long, FederatedOutput> outputDecisions = computeOutputDecisions(memoTable, optimalPlan);
		Set<Long> visitedPlanHops = new HashSet<>();
		Map<Long, FType> fTypeMap = new HashMap<>();

		for (Pair<Long, FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
				rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap);
		}

		// Also rewrite additional non-clone roots that may not be reachable from the
		// dummy root through Hop parent links. Skip virtual clone roots because they
		// are planning-only artifacts and rewriting them can override executable-hop
		// decisions through original-id aliasing.
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
					rewriteHop(seed, memoTable, outputDecisions, visitedPlanHops, fTypeMap);
			}

		FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedPlannerUtils.clearFedInitVars();
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = FederatedPlannerDpCostEnumerator.enumerateFunctionDynamic(
			function, memoTable, FederatedPlannerTrace.isEnabled());

		Map<Long, FederatedOutput> outputDecisions = computeOutputDecisions(memoTable, optimalPlan);
		Set<Long> visitedPlanHops = new HashSet<>();
		Map<Long, FType> fTypeMap = new HashMap<>();

		for (Pair<Long, FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
				rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap);
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
					rewriteHop(seed, memoTable, outputDecisions, visitedPlanHops, fTypeMap);
			}

		FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
	}

	private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan plan,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedOutput> outputDecisions,
		Set<Long> visitedPlanHops,
		Map<Long, FType> fTypeMap) {

		long planHopId = plan.getHopRef().getHopID();
		if (visitedPlanHops != null && !visitedPlanHops.add(planHopId))
			return;

		long origHopId = memoTable.resolveOriginalHopId(planHopId);
		FederatedOutput desiredOut = outputDecisions.getOrDefault(origHopId, plan.getFedOutType());

		FederatedPlannerDpMemoTable.FedPlan effectivePlan = selectRewritePlanVariant(
			memoTable, planHopId, desiredOut, plan.getFedOutType(), plan, outputDecisions);

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
					memoTable, childHopID, childDesiredOut, childFedPlanPair.getValue(), null, outputDecisions);
				if (childPlan == null) {
					FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, effectivePlan, memoTable);
					continue;
				}
				rewriteHop(childPlan, memoTable, outputDecisions, visitedPlanHops, fTypeMap);
			}

			Hop hopRef = effectivePlan.getHopRef();
			Hop targetHop = memoTable.resolveOriginalHop(planHopId);
			if (targetHop == null)
				targetHop = hopRef;

			ExecType execType = effectivePlan.getExecType();
			if (execType == null)
				throw new DMLRuntimeException("ExecType is null in FedPlan for hop " + planHopId + " / " + hopRef.getOpString());
			FederatedOutput outType = effectivePlan.getFedOutType();

			effectivePlan.setForcedExecType(execType);
			if (targetHop != hopRef)
				targetHop.setForcedExecType(execType);

			effectivePlan.setFederatedOutput(outType);
			if (targetHop != hopRef)
				targetHop.setFederatedOutput(outType);

		boolean derivedFedFout = execType == ExecType.FED
			&& outType == FederatedOutput.FOUT
			&& effectivePlan.isDerivedFedFout();
			effectivePlan.setFederatedOutputDerived(derivedFedFout);
			if (targetHop != hopRef)
				targetHop.setFederatedOutputDerived(derivedFedFout);

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

	private static FederatedPlannerDpMemoTable.FedPlan selectRewritePlanVariant(
		FederatedPlannerDpMemoTable memoTable,
		long hopID,
		FederatedOutput desiredOut,
		FederatedOutput inheritedOut,
		FederatedPlannerDpMemoTable.FedPlan fallbackPlan,
		Map<Long, FederatedOutput> outputDecisions) {

		if (memoTable == null)
			return fallbackPlan;

		FederatedPlannerDpMemoTable.FedPlan selected = null;
		if (inheritedOut != null)
			selected = selectCompatiblePlanVariant(memoTable, hopID, inheritedOut, outputDecisions);
		if (selected == null && desiredOut != null && desiredOut != inheritedOut)
			selected = selectCompatiblePlanVariant(memoTable, hopID, desiredOut, outputDecisions);
		if (selected != null)
			return selected;

		// Rewrite must preserve an executable parent->child forest even when the
		// global decision map is temporarily inconsistent. Prefer the inherited
		// edge/requested output over an incompatible desiredOut fallback so runtime
		// does not observe FED parents wired to local children.
		if (inheritedOut != null) {
			selected = memoTable.getFedPlanAfterPrune(hopID, inheritedOut);
			if (selected != null)
				return selected;
		}
		if (desiredOut != null && desiredOut != inheritedOut) {
			selected = memoTable.getFedPlanAfterPrune(hopID, desiredOut);
			if (selected != null)
				return selected;
		}

		if (fallbackPlan != null)
			return fallbackPlan;

		FederatedPlannerDpMemoTable.FedPlan lPlan =
			memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.LOUT);
		FederatedPlannerDpMemoTable.FedPlan fPlan =
			memoTable.getFedPlanAfterPrune(hopID, FederatedOutput.FOUT);
		return (lPlan == null) ? fPlan :
			(fPlan == null) ? lPlan :
			(lPlan.getCumulativeCost() <= fPlan.getCumulativeCost()) ? lPlan : fPlan;
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
		final int maxIters = 4; // bounded fixed-point to account for parent exec-type changes after decisions

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
						for (long tReadHopID : collectTransientReadParents(memoTable, hopID, conflictCheckMap))
							nextDecisions.putIfAbsent(tReadHopID, lockedChoice);
					}
					continue;
				}

					if (isTransientWrite) {
						Map<Long, FederatedOutput> tentativeDecisions = new HashMap<>(decisions);
						tentativeDecisions.putAll(nextDecisions);
						FederatedOutput chosen;
						if (entry.canChooseLOUT && entry.canChooseFOUT) {
							chosen = resolveTransientWriteConflict(
								memoTable, hopID, entry, conflictCheckMap, tentativeDecisions, numWorkers);
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
							chosen = resolveTransientWriteConflict(
								memoTable, hopID, entry, conflictCheckMap, tentativeDecisions, numWorkers);
						}
					if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine) {
						FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Chosen",
							"iter=" + iter + " chosen=" + chosen);
					}
					if (chosen != null) {
						nextDecisions.put(hopID, chosen);
						for (long tReadHopID : collectTransientReadParents(memoTable, hopID, conflictCheckMap))
							nextDecisions.put(tReadHopID, chosen);
					}
					continue;
				}

					FederatedOutput chosen;
					Map<Long, FederatedOutput> tentativeDecisions = new HashMap<>(decisions);
					tentativeDecisions.putAll(nextDecisions);
					boolean forceTransientNeighborhoodReeval =
						ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL
							&& entry.canChooseLOUT && entry.canChooseFOUT
							&& isTransientBoundaryNeighborhood(memoTable, hopID, entry);
				boolean forceCompatibleVariantReeval =
					entry.canChooseLOUT && entry.canChooseFOUT && !decisions.isEmpty()
						&& requiresCompatibleVariantReevaluation(
							memoTable, hopID,
							entry.seenFOUT && !entry.seenLOUT ? FederatedOutput.FOUT : FederatedOutput.LOUT,
							decisions);
				boolean forceSeenOnlyReeval = forceTransientNeighborhoodReeval || forceCompatibleVariantReeval;
				if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine
					&& forceCompatibleVariantReeval) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Reeval",
						"iter=" + iter + " reason=compatible_variant_shift_or_alt_output_cheaper");
				}
					if (entry.seenLOUT && !entry.seenFOUT) {
						chosen = forceSeenOnlyReeval
							? resolveOneHopConflict(memoTable, hopID, entry, tentativeDecisions, numWorkers)
							: (entry.canChooseLOUT ? FederatedOutput.LOUT
								: (entry.canChooseFOUT ? FederatedOutput.FOUT : null));
					}
					else if (!entry.seenLOUT && entry.seenFOUT) {
						chosen = forceSeenOnlyReeval
							? resolveOneHopConflict(memoTable, hopID, entry, tentativeDecisions, numWorkers)
							: (entry.canChooseFOUT ? FederatedOutput.FOUT
								: (entry.canChooseLOUT ? FederatedOutput.LOUT : null));
					}
					else {
						chosen = resolveOneHopConflict(memoTable, hopID, entry, tentativeDecisions, numWorkers);
					}

				if (FederatedPlannerTrace.shouldTrace(hopRef) && allowTransientFamilyRefine) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Chosen",
						"iter=" + iter + " chosen=" + chosen);
				}

				if (chosen != null)
					nextDecisions.put(hopID, chosen);
			}

			if (allowTransientFamilyRefine) {
				nextDecisions = refineTransientFamilyDecisions(
					memoTable, rootPlan, conflictCheckMap, nextDecisions, iter);
				// Rewrite now resolves parent/child output mismatches with inherited edge-aware
				// variant selection. Applying an eager global "repair" here over-localizes
				// unrelated transient chains (e.g., logreg w2 wan_light 271/357) because a
				// single incompatible downstream family can force older, still-beneficial FOUT
				// decisions to LOUT before rewrite has a chance to pick a consistent forest.
				// Keep the decision map cost-driven and let rewrite enforce executable
				// compatibility locally.
				logDecisionMapScoreBreakdown(memoTable, rootPlan, conflictCheckMap, decisions, nextDecisions, iter);
			}

			if (nextDecisions.equals(decisions)) {
				decisions = nextDecisions;
				break;
			}

			decisions = nextDecisions;
			conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan, decisions);
		}

		return decisions;
	}

	private static Map<Long, FederatedOutput> refineTransientFamilyDecisions(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> nextDecisions,
		int iter) {

		if (memoTable == null || rootPlan == null || conflictCheckMap == null || conflictCheckMap.isEmpty()
			|| nextDecisions == null || nextDecisions.isEmpty())
			return nextDecisions;

		Map<Long, FederatedOutput> refinedDecisions = new HashMap<>(nextDecisions);
		DecisionMapScoreBreakdown currentScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, refinedDecisions);
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
				simulateOutputDecisionsWithLocks(
					memoTable, rootPlan, refinedDecisions, lockedAlternativeDecisions);
			DecisionMapScoreBreakdown simulatedAlternativeScore =
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, simulatedAlternativeDecisions);
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
						computeDecisionMapScoreBreakdown(memoTable, rootPlan, normalizedDecisions);
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
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions);
			boolean rawPrefersAlternative =
				alternative == FederatedOutput.FOUT
					&& familyHasCheaperRawAlternative(memoTable, altFamilyHopIDs, alternative);
			boolean keepFoutOnNearFamilyTie = false;
			Map<Long, FederatedOutput> bundleAltDecisions = null;
			DecisionMapScoreBreakdown bundleAltScore = null;
			LinkedHashSet<Long> feasibleBundleHopIDs = null;
			if (alternative == FederatedOutput.FOUT
				&& Math.abs(altScore.totalCost - currentScore.totalCost) <= 1e-9
				&& bundleHopIDs.size() > familyHopIDs.size()) {
				feasibleBundleHopIDs = collectContextuallyFeasibleTransientBundleHopIDs(
					memoTable, rootPlan, refinedDecisions, conflictCheckMap, familyHopIDs, bundleHopIDs);
				if (feasibleBundleHopIDs.size() > familyHopIDs.size()) {
					bundleAltDecisions = new HashMap<>(refinedDecisions);
					for (long bundleHopID : feasibleBundleHopIDs)
						bundleAltDecisions.put(bundleHopID, FederatedOutput.FOUT);
					bundleAltScore = computeDecisionMapScoreBreakdown(memoTable, rootPlan, bundleAltDecisions);
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
							|| rawPrefersAlternative
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
		int iter) {

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
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, currentDecisions);
		DecisionMapScoreBreakdown nextScore =
			computeDecisionMapScoreBreakdown(memoTable, rootPlan, nextDecisions);

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

		logTransientAlternativeScores(memoTable, rootPlan, conflictCheckMap, nextDecisions, nextScore, iter);
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

		return breakdown;
	}

	private static double computeDecisionMapRootContributionCost(
		FederatedPlannerDpMemoTable.FedPlan plan,
		boolean additionalRoot,
		FederatedPlannerDpMemoTable memoTable,
		Map<Long, FederatedPlannerDpMemoTable.FedPlan> selectedRootPlans,
		Map<Long, FederatedOutput> outputDecisions) {

		if (plan == null)
			return Double.NaN;

		double cost = plan.getCumulativeCost();
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
		int iter) {

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
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions);
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

		logTransientBundleAlternativeScores(memoTable, rootPlan, conflictCheckMap, nextDecisions, nextScore, iter);
	}

	private static void logTransientBundleAlternativeScores(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan rootPlan,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> nextDecisions,
		DecisionMapScoreBreakdown nextScore,
		int iter) {

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
				computeDecisionMapScoreBreakdown(memoTable, rootPlan, altDecisions);
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
					computeDecisionMapScoreBreakdown(memoTable, rootPlan, familyAltDecisions);
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
									|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(dataOp, sourceHops);
							if (!hasConcreteSource)
								continue;
						}
					}

					boolean selectedFedFout =
						selectedPlan.getExecType() == ExecType.FED
							&& selectedPlan.getFedOutType() == FederatedOutput.FOUT;
					if (selectedFedFout) {
						if (FederatedRefedPolicy.canSatisfyFederatedInputs(hopRef, contextualFTypeMap)) {
							contextualFTypeMap.put(origHopID, contextualType);
							changed = true;
						}
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
				hopRef, hopRef.getForcedExecType(), hopRef.getFederatedOutput(), hopRef.isFederatedOutputDerived()));
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
				hopRef.setForcedExecType(ExecType.FED);
				hopRef.setFederatedOutput(FederatedOutput.FOUT);
			}
			else if (selectedPlan.getExecType() == ExecType.FED
				&& selectedPlan.getFedOutType() == FederatedOutput.FOUT) {
				hopRef.setForcedExecType(ExecType.CP);
				hopRef.setFederatedOutput(FederatedOutput.LOUT);
			}
			else {
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
			if (state.forcedExecType != null)
				state.hop.setForcedExecType(state.forcedExecType);
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
	 * Prefer executable/original members for output-decision feasibility checks.
	 * If no executable members exist, fall back to all members.
	 */
	private static Set<Long> selectDecisionMembers(Set<Long> memberHopIDs,
		FederatedPlannerDpMemoTable memoTable) {

		if (memberHopIDs == null || memberHopIDs.isEmpty())
			return memberHopIDs;

		LinkedHashSet<Long> executable = new LinkedHashSet<>();
		for (long hopID : memberHopIDs) {
			if (!memoTable.isVirtualClone(hopID))
				executable.add(hopID);
		}
		return executable.isEmpty() ? memberHopIDs : executable;
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

		LinkedHashSet<Long> tReadHopIDs = new LinkedHashSet<>();
		if (memoTable == null || conflictCheckMap == null || conflictCheckMap.isEmpty())
			return tReadHopIDs;

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

		return tReadHopIDs;
	}

	private static FederatedOutput resolveTransientWriteConflict(
		FederatedPlannerDpMemoTable memoTable, long tWriteHopID, ConflictEntry tWriteEntry,
		Map<Long, ConflictEntry> conflictCheckMap,
		Map<Long, FederatedOutput> tentativeDecisions,
		int numWorkers) {

		if (memoTable == null || tWriteEntry == null)
			return null;
		final Hop hopRef = memoTable.resolveOriginalHop(tWriteHopID);
		final boolean trace = FederatedPlannerTrace.shouldTrace(hopRef);

		final boolean canChooseLOUT = tWriteEntry.canChooseLOUT;
		final boolean canChooseFOUT = tWriteEntry.canChooseFOUT;
		if (!canChooseLOUT && !canChooseFOUT)
			return null;

		LinkedHashSet<Long> tReadHopIDs = collectTransientReadParents(memoTable, tWriteHopID, conflictCheckMap);
		if (tReadHopIDs.isEmpty()) {
			// Fallback to generic per-hop resolution.
			return resolveOneHopConflict(memoTable, tWriteHopID, tWriteEntry, tentativeDecisions, numWorkers);
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
							consumerPlan, consumerIsFed, numWorkers);
					}
					if (tReadCanFOUT && canChooseFOUT && originalOut != FederatedOutput.FOUT) {
						fOutAdditionalCost += computeSwitchEdgeCostDelta(
							memoTable, childHopID, originalOut, FederatedOutput.FOUT,
							consumerPlan, consumerIsFed, numWorkers);
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
					memoTable, tWriteEntry, currentOut, FederatedOutput.LOUT, numWorkers);
				lOutAdditionalCost += producerDeltaToLOUT;
			}
			if (canChooseFOUT && currentOut != FederatedOutput.FOUT) {
				producerDeltaToFOUT = computeTransientWriteProducerDelta(
					memoTable, tWriteEntry, currentOut, FederatedOutput.FOUT, numWorkers);
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
						fromPlan, fromPlan.getExecType() == ExecType.FED, numWorkers);
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
					conflictCheckMap.put(childOrigHopID, new ConflictEntry(childOut, current, childHopID));
				}
				else {
					entry.addUsage(childOut, current, childHopID);
				}

				queue.add(childPlan);
			}
		}

		return conflictCheckMap;
	}

	private static FederatedOutput resolveOneHopConflict(
		FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry,
		Map<Long, FederatedOutput> tentativeDecisions, int numWorkers) {

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
				memoTable, hopID, entry, decisionMembers, FederatedOutput.LOUT, tentativeDecisions)
			: Double.POSITIVE_INFINITY;
		double fOutAdditionalCost = canChooseFOUT
			? computeCompatiblePlanSelectionDelta(
				memoTable, hopID, entry, decisionMembers, FederatedOutput.FOUT, tentativeDecisions)
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
						parentPlan, parentIsFed, numWorkers)
					: 0.0;
				double dF = (canChooseFOUT && originalOut != FederatedOutput.FOUT)
					? computeSwitchEdgeCostDelta(
						memoTable, childHopID, originalOut, FederatedOutput.FOUT,
						parentPlan, parentIsFed, numWorkers)
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
		Map<Long, FederatedOutput> tentativeDecisions) {

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
				selectCompatiblePlanVariant(memoTable, memberHopID, currentOut, tentativeDecisions);
			if (currentPlan == null)
				currentPlan = memoTable.getFedPlanAfterPrune(memberHopID, currentOut);

			FederatedPlannerDpMemoTable.FedPlan targetPlan =
				selectCompatiblePlanVariant(memoTable, memberHopID, targetOut, tentativeDecisions);
			if (targetPlan == null)
				targetPlan = memoTable.getFedPlanAfterPrune(memberHopID, targetOut);

			if (currentPlan == null || targetPlan == null)
				return Double.POSITIVE_INFINITY;

			double currentShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				currentPlan.getCumulativeCost(), currentPlan);
			double targetShare = FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				targetPlan.getCumulativeCost(), targetPlan);
			delta += targetShare - currentShare;
		}

		return delta;
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
			parentIsFed, fromOut, fromPlan, parentPlan, numWorkers);
		double toForwardingShare = computeParentChildForwardingCostShare(
			parentIsFed, toOut, toPlan, parentPlan, numWorkers);

		double childForwardingDelta =
			(toCumulativeShare - fromCumulativeShare) + (toForwardingShare - fromForwardingShare);
		double parentVariantDelta = computeParentVariantSwitchDelta(
			memoTable, parentPlan, childHopID, toOut);
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

		if (memoTable == null || parentPlan == null || parentPlan.getHopRef() == null)
			return Double.NaN;
		Hop childHopRef = memoTable.resolveOriginalHop(childHopID);
		boolean trace = FederatedPlannerTrace.shouldTrace(childHopRef);

		long parentHopID = parentPlan.getHopRef().getHopID();
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
		if ((variantsLOUT == null || variantsLOUT.isEmpty()) && (variantsFOUT == null || variantsFOUT.isEmpty()))
			return Double.NaN;

		// Parent/child relationships can involve cloned hop IDs across loop contexts.
		// Match edges by original hop id (not by concrete clone id) so that we can
		// correctly find compatible parent variants even when the parent references a
		// different clone of the same logical child hop.
		final long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
		long currentEdgeHopId = -1L;
		FederatedOutput currentEdgeOut = null;
		if (trace) {
			int variantCountLOUT = variantsLOUT != null ? variantsLOUT.getFedPlanVariants().size() : 0;
			int variantCountFOUT = variantsFOUT != null ? variantsFOUT.getFedPlanVariants().size() : 0;
			FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantSearch", String.format(Locale.ROOT,
				"parentHop=%d parentExec=%s parentOut=%s parentCost=%.6f childHop=%d childOrig=%d desiredOut=%s variantsLOUT=%d variantsFOUT=%d",
				parentHopID, parentPlan.getExecType(), parentPlan.getFedOutType(), parentPlan.getCumulativeCost(),
				childHopID, childOrigHopID, desiredChildOut, variantCountLOUT, variantCountFOUT));
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
		if (!parentReferencesChild)
			return Double.NaN;

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
					double childShareAdjustment = computeTransientReadChildShareAdjustment(
						memoTable, parentPlan, currentEdgeHopId, currentEdgeOut, matchedEdgeHopId, matchedEdgeOut);
					double adjustedDelta = rawDelta - childShareAdjustment;
					if (trace && candidateLogs < 6 && referencesChild) {
						FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantCandidate", String.format(Locale.ROOT,
							"parentHop=%d candExec=%s candOut=%s candCost=%.6f edgeHop=%d edgeOrig=%d matchDesired=%s delta=%.6f rawDelta=%.6f childShareAdj=%.6f",
							parentHopID, cand.getExecType(), cand.getFedOutType(), cand.getCumulativeCost(),
							matchedEdgeHopId, childOrigHopID, ok, adjustedDelta, rawDelta, childShareAdjustment));
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
			return Double.NaN;
		}
		double delta = bestDelta;
		if (trace) {
			FederatedPlannerTrace.log(childHopRef, "DP-ParentVariantResult", String.format(Locale.ROOT,
				"parentHop=%d result=compatible_variant referencedChildVariants=%d matchingDesiredVariants=%d bestDelta=%.6f",
				parentHopID, referencedChildVariants, matchingDesiredVariants, delta));
		}
		return delta;
	}

	private static double computeTransientReadChildShareAdjustment(
		FederatedPlannerDpMemoTable memoTable,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		long currentChildHopID,
		FederatedOutput currentChildOut,
		long candidateChildHopID,
		FederatedOutput candidateChildOut) {

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
		if (!(childHopRef instanceof DataOp)
			|| ((DataOp) childHopRef).getOp() != Types.OpOpData.TRANSIENTREAD) {
			return 0.0;
		}

		FederatedPlannerDpMemoTable.FedPlan currentChildPlan =
			memoTable.getFedPlanAfterPrune(currentChildHopID, currentChildOut);
		FederatedPlannerDpMemoTable.FedPlan candidateChildPlan =
			memoTable.getFedPlanAfterPrune(candidateChildHopID, candidateChildOut);
		if (currentChildPlan == null || candidateChildPlan == null)
			return 0.0;

		double currentShare = computeTransientReadPlanShareForParent(currentChildPlan, memoTable);
		double candidateShare = computeTransientReadPlanShareForParent(candidateChildPlan, memoTable);
		return candidateShare - currentShare;
	}

	private static double computeTransientReadPlanShareForParent(
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable memoTable) {

		if (childPlan == null)
			return 0.0;
		return childPlan.getFedOutType() == FederatedOutput.FOUT
			? FederatedPlannerDpCostEstimator.computeStableTransientReadFoutCumulativeShareForParent(
				childPlan, memoTable)
			: FederatedPlannerDpCostEstimator.computeCumulativeCostShareForParent(
				childPlan.getCumulativeCost(), childPlan);
	}

	private static double computeParentChildForwardingCostShare(
		boolean parentIsFed, FederatedOutput childOut,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		int numWorkers) {

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
			return FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(uploadCost, childPlan, parentPlan);
		}
		else if (parentIsFed && childOut == FederatedOutput.FOUT && childPlan.getExecType() == ExecType.CP) {
			FType uploadType = childPlan.getCpFoutTypeOrFType();
			double uploadCost = FederatedPlannerDpCostEstimator.computeUploadCostWithFallback(
				childPlan.getHopRef(), parentPlan.getHopRef(), uploadType, numWorkers);
			return FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(uploadCost, childPlan, parentPlan);
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
			return FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(refedCost, childPlan, parentPlan);
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
			return FederatedPlannerDpCostEstimator.computeFoutToCpDownloadShareForParent(
					downloadCost, childPlan, parentPlan);
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

	private static final class HopStampState {
		final Hop hop;
		final ExecType forcedExecType;
		final FederatedOutput fedOut;
		final boolean fedOutDerived;

		HopStampState(Hop hop, ExecType forcedExecType, FederatedOutput fedOut, boolean fedOutDerived) {
			this.hop = hop;
			this.forcedExecType = forcedExecType;
			this.fedOut = fedOut;
			this.fedOutDerived = fedOutDerived;
		}
	}

	private static final class DecisionMapScoreBreakdown {
		double totalCost;
		double mainRootCost;
		double additionalRootCost;
		double virtualAdditionalRootCost;
		int missingRootCount;
		final LinkedHashMap<String, RootContribution> rootContributions = new LinkedHashMap<>();

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
				plan, additionalRoot, memoTable, selectedRootPlans, outputDecisions);
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

	private static final class ConflictEntry {
		final LinkedHashSet<FederatedPlannerDpMemoTable.FedPlan> parents;
		final LinkedHashSet<Long> memberHopIDs;
		boolean seenLOUT;
		boolean seenFOUT;
		boolean canChooseLOUT;
		boolean canChooseFOUT;

		ConflictEntry(FederatedOutput out, FederatedPlannerDpMemoTable.FedPlan parent, long memberHopID) {
			this.parents = new LinkedHashSet<>();
			this.memberHopIDs = new LinkedHashSet<>();
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
	}
}
