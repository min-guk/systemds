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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
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

		// Also rewrite any additional executed roots (e.g., loop-unrolled iter1 roots)
		// that may not be reachable from the dummy root through Hop parent links.
		for (long rootHopID : memoTable.getAdditionalRootHopIDs()) {
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

		FederatedPlannerDpMemoTable.FedPlan effectivePlan = selectCompatiblePlanVariant(
			memoTable, planHopId, desiredOut, outputDecisions);
		if (effectivePlan == null) {
			// Fall back to the incoming plan (should be rare; happens if some clone lacks a plan variant).
			effectivePlan = plan;
		}

		for (Pair<Long, FederatedOutput> childFedPlanPair : effectivePlan.getChildFedPlans()) {
			long childHopID = childFedPlanPair.getKey();
			long childOrigHopID = memoTable.resolveOriginalHopId(childHopID);
			FederatedOutput childDesiredOut = outputDecisions.getOrDefault(childOrigHopID, childFedPlanPair.getValue());
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childHopID, childDesiredOut);
			if (childPlan == null) {
				// fall back to edge-specified out type for logging/debugging
				childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			}
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
		boolean isTransient = targetHop instanceof DataOp
			&& (((DataOp) targetHop).getOp() == Types.OpOpData.TRANSIENTREAD
				|| ((DataOp) targetHop).getOp() == Types.OpOpData.TRANSIENTWRITE);
		// Keep non-transient local-output FType hints so refed-policy can preserve
		// planner CP->FOUT decisions (e.g., BROADCAST) even when dims are unknown.
		if (fType != null && (outType == FederatedOutput.FOUT || !isTransient))
			fTypeMap.put(origHopId, fType);
		else
			fTypeMap.remove(origHopId);
	}

	private static Map<Long, FederatedOutput> computeOutputDecisions(
		FederatedPlannerDpMemoTable memoTable, FederatedPlannerDpMemoTable.FedPlan rootPlan) {

		Map<Long, FederatedOutput> decisions = new HashMap<>();
		if (rootPlan == null)
			return decisions;

		final int numWorkers = Math.max(1, memoTable.getNumWorkers());
		final int maxIters = 4; // bounded fixed-point to account for parent exec-type changes after decisions

		Map<Long, ConflictEntry> conflictCheckMap = collectConflictsSingleBFS(memoTable, rootPlan, decisions);
		for (int iter = 0; iter < maxIters; iter++) {
			// Pre-compute feasibility of choosing LOUT/FOUT across clone sets.
			for (ConflictEntry entry : conflictCheckMap.values()) {
				boolean canChooseLOUT = true;
				boolean canChooseFOUT = true;
				for (long memberHopID : entry.memberHopIDs) {
					canChooseLOUT &= (memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT) != null);
					canChooseFOUT &= (memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.FOUT) != null);
				}
				entry.canChooseLOUT = canChooseLOUT;
				entry.canChooseFOUT = canChooseFOUT;
			}

			Map<Long, FederatedOutput> nextDecisions = new HashMap<>();

			for (Map.Entry<Long, ConflictEntry> e : conflictCheckMap.entrySet()) {
				long hopID = e.getKey();
				ConflictEntry entry = e.getValue();

				if (!entry.seenLOUT && !entry.seenFOUT)
					continue;

				Hop hopRef = memoTable.resolveOriginalHop(hopID);
				if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Entry", String.format(Locale.ROOT,
						"iter=%d seenLOUT=%s seenFOUT=%s canLOUT=%s canFOUT=%s members=%d parents=%d",
						iter, entry.seenLOUT, entry.seenFOUT, entry.canChooseLOUT, entry.canChooseFOUT,
						entry.memberHopIDs != null ? entry.memberHopIDs.size() : 0,
						entry.parents != null ? entry.parents.size() : 0));
				}

				boolean isTransientWrite = hopRef instanceof DataOp
					&& ((DataOp) hopRef).getOp() == Types.OpOpData.TRANSIENTWRITE;
				if (isTransientWrite) {
					FederatedOutput chosen = resolveTransientWriteConflict(
						memoTable, hopID, entry, conflictCheckMap, numWorkers);
					if (chosen != null) {
						nextDecisions.put(hopID, chosen);
						for (long tReadHopID : collectTransientReadParents(memoTable, hopID, conflictCheckMap))
							nextDecisions.put(tReadHopID, chosen);
					}
					continue;
				}

				FederatedOutput chosen;
				// If no conflict is observed for this hop under the currently-selected plan forest,
				// keep the observed representation. This prevents over-eager "optimization" based on
				// local best-variant costs that can become inconsistent once other decisions demote
				// parent exec types (e.g., kmeans WAN-mid: deciding D local should allow local ewise).
				if (entry.seenLOUT && !entry.seenFOUT) {
					chosen = entry.canChooseLOUT ? FederatedOutput.LOUT : (entry.canChooseFOUT ? FederatedOutput.FOUT : null);
				}
				else if (!entry.seenLOUT && entry.seenFOUT) {
					chosen = entry.canChooseFOUT ? FederatedOutput.FOUT : (entry.canChooseLOUT ? FederatedOutput.LOUT : null);
				}
				else {
					chosen = resolveOneHopConflict(memoTable, hopID, entry, numWorkers);
				}

				if (FederatedPlannerTrace.shouldTrace(hopRef)) {
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Chosen",
						"iter=" + iter + " chosen=" + chosen);
				}

				if (chosen != null)
					nextDecisions.put(hopID, chosen);
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
			for (long memberHopID : entry.memberHopIDs) {
				FederatedPlannerDpMemoTable.FedPlan plan = memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.LOUT);
				if (plan == null)
					plan = memoTable.getFedPlanAfterPrune(memberHopID, FederatedOutput.FOUT);
				if (plan == null)
					continue;

				for (Pair<Long, FederatedOutput> childEdge : plan.getChildFedPlans()) {
					long childOrigId = memoTable.resolveOriginalHopId(childEdge.getKey());
					if (childOrigId == tWriteOrigHopID) {
						readsFromThisWrite = true;
						break;
					}
				}
				if (readsFromThisWrite)
					break;
			}

			if (readsFromThisWrite)
				tReadHopIDs.add(hopID);
		}

		return tReadHopIDs;
	}

	private static FederatedOutput resolveTransientWriteConflict(
		FederatedPlannerDpMemoTable memoTable, long tWriteHopID, ConflictEntry tWriteEntry,
		Map<Long, ConflictEntry> conflictCheckMap, int numWorkers) {

		if (memoTable == null || tWriteEntry == null)
			return null;

		final boolean canChooseLOUT = tWriteEntry.canChooseLOUT;
		final boolean canChooseFOUT = tWriteEntry.canChooseFOUT;
		if (!canChooseLOUT && !canChooseFOUT)
			return null;

		LinkedHashSet<Long> tReadHopIDs = collectTransientReadParents(memoTable, tWriteHopID, conflictCheckMap);
		if (tReadHopIDs.isEmpty()) {
			// Fallback to generic per-hop resolution.
			return resolveOneHopConflict(memoTable, tWriteHopID, tWriteEntry, numWorkers);
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

			for (FederatedPlannerDpMemoTable.FedPlan consumerPlan : tReadEntry.parents) {
				if (consumerPlan == null)
					continue;
				boolean consumerIsFed = consumerPlan.getExecType() == ExecType.FED;
				for (Pair<Long, FederatedOutput> edge : consumerPlan.getChildFedPlans()) {
					if (memoTable.resolveOriginalHopId(edge.getKey()) != tReadHopID)
						continue;
					long childHopID = edge.getKey();
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

		if (!canChooseFOUT || !Double.isFinite(fOutAdditionalCost)
			|| (canChooseLOUT && Double.isFinite(lOutAdditionalCost) && lOutAdditionalCost <= fOutAdditionalCost))
			return FederatedOutput.LOUT;
		return FederatedOutput.FOUT;
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

		for (FederatedPlannerDpMemoTable.FedPlan candidate : variants.getFedPlanVariants()) {
			if (candidate == null)
				continue;
			if (isCompatibleWithChildDecisions(memoTable, candidate, outputDecisions))
				return candidate;
		}

		// Fallback: best available variant even if it triggers conversions.
		return variants.getFedPlanVariants().get(0);
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
				// Compatibility relaxation: if a child is decided as FOUT, but this parent
				// plan consumes it as LOUT, it can still be compatible when the child's FOUT
				// variant executes in CP (CP/FOUT). In that case, the local materialization
				// already exists and the parent can safely consume the local copy without
				// forcing a FED instruction.
				if (desiredChild == FederatedOutput.FOUT && childEdge.getValue() == FederatedOutput.LOUT) {
					long childHopID = childEdge.getKey();
					FederatedPlannerDpMemoTable.FedPlanVariants foutVariants =
						memoTable.getFedPlanVariants(Pair.of(childHopID, FederatedOutput.FOUT));
					boolean hasCpfoutMaterialization = false;
					if (foutVariants != null) {
						for (FederatedPlannerDpMemoTable.FedPlan v : foutVariants.getFedPlanVariants()) {
							if (v != null && v.getExecType() == ExecType.CP) {
								hasCpfoutMaterialization = true;
								break;
							}
						}
					}
					if (hasCpfoutMaterialization)
						continue;
				}
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

		// Include additional executed roots such as loop-unrolled "iter1" roots. These
		// hops can carry large loop multiplicities, and omitting them would cause
		// placement conflict decisions to ignore repeated forwarding costs (e.g.,
		// refed/PUT inside loops).
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
		FederatedPlannerDpMemoTable memoTable, long hopID, ConflictEntry entry, int numWorkers) {

		final boolean canChooseLOUT = entry.canChooseLOUT;
		final boolean canChooseFOUT = entry.canChooseFOUT;
		if (!canChooseLOUT && !canChooseFOUT)
			return null;

		final Hop hopRef = memoTable.resolveOriginalHop(hopID);
		final boolean trace = FederatedPlannerTrace.shouldTrace(hopRef);
		final int maxEdgeLogs = FederatedPlannerTrace.getMaxEdgeLogsPerHop();
		int edgeLogs = 0;

		double lOutAdditionalCost = canChooseLOUT ? 0.0 : Double.POSITIVE_INFINITY;
		double fOutAdditionalCost = canChooseFOUT ? 0.0 : Double.POSITIVE_INFINITY;

		for (FederatedPlannerDpMemoTable.FedPlan parentPlan : entry.parents) {
			ExecType parentExec = parentPlan.getExecType();
			boolean parentIsFed = parentExec == ExecType.FED;

			for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
				if (memoTable.resolveOriginalHopId(edge.getKey()) != hopID)
					continue;
				long childHopID = edge.getKey();
				FederatedOutput originalOut = edge.getValue();

				if (trace && edgeLogs < maxEdgeLogs) {
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
					FederatedPlannerTrace.log(hopRef, "DP-OutputDecision-Edge", String.format(Locale.ROOT,
						"parentHop=%d parentExec=%s origOut=%s deltaToLOUT=%.6f deltaToFOUT=%.6f",
						parentPlan.getHopRef() != null ? parentPlan.getHopRef().getHopID() : -1,
						parentExec, originalOut, dL, dF));
					edgeLogs++;
				}

				if (canChooseLOUT && originalOut != FederatedOutput.LOUT) {
					lOutAdditionalCost += computeSwitchEdgeCostDelta(
						memoTable, childHopID, originalOut, FederatedOutput.LOUT,
						parentPlan, parentIsFed, numWorkers);
				}
				if (canChooseFOUT && originalOut != FederatedOutput.FOUT) {
					fOutAdditionalCost += computeSwitchEdgeCostDelta(
						memoTable, childHopID, originalOut, FederatedOutput.FOUT,
						parentPlan, parentIsFed, numWorkers);
				}
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

	private static double computeSwitchEdgeCostDelta(
		FederatedPlannerDpMemoTable memoTable, long childHopID,
		FederatedOutput fromOut, FederatedOutput toOut,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		boolean parentIsFed, int numWorkers) {

		// Prefer a parent-variant switch delta if possible.
		//
		// The output-decision phase may change child representations (LOUT/FOUT), which can
		// invalidate the currently-selected parent exec type. Estimating deltas purely from
		// child costs (upload/download boundary) over-penalizes LOUT when the parent could
		// instead switch to a CP variant. This was observed to force DP into FED elementwise
		// chains in kmeans WAN-mid even though a cheaper CP-compatible parent variant exists.
		double parentVariantDelta = computeParentVariantSwitchDelta(
			memoTable, parentPlan, childHopID, toOut);
		if (Double.isFinite(parentVariantDelta))
			return parentVariantDelta;

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

		return (toCumulativeShare - fromCumulativeShare) + (toForwardingShare - fromForwardingShare);
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

		// Prefer the cheapest parent variant that consumes this child with the desired
		// representation, regardless of other child-edge outputs. This intentionally
		// allows the parent to "self-heal" by also switching other child edges if that
		// yields a lower-cost compatible plan (important when demoting FED parents to CP).
		boolean parentReferencesChild = false;
		for (Pair<Long, FederatedOutput> edge : parentPlan.getChildFedPlans()) {
			if (edge != null && edge.getKey() == childHopID) {
				parentReferencesChild = true;
				break;
			}
		}
		if (!parentReferencesChild)
			return Double.NaN;

		double best = Double.POSITIVE_INFINITY;
		for (FederatedPlannerDpMemoTable.FedPlanVariants variants : new FederatedPlannerDpMemoTable.FedPlanVariants[] {variantsLOUT, variantsFOUT}) {
			if (variants == null || variants.isEmpty())
				continue;
			for (FederatedPlannerDpMemoTable.FedPlan cand : variants.getFedPlanVariants()) {
				if (cand == null)
					continue;
				if (cand.getChildFedPlans() == null || cand.getChildFedPlans().isEmpty())
					continue;
				boolean ok = false;
				for (Pair<Long, FederatedOutput> edge : cand.getChildFedPlans()) {
					if (edge == null)
						continue;
					if (edge.getKey() == childHopID && edge.getValue() == desiredChildOut) {
						ok = true;
						break;
					}
				}
				if (!ok)
					continue;
				best = Math.min(best, cand.getCumulativeCost());
			}
		}

		if (!Double.isFinite(best))
			return Double.NaN;
		return best - parentPlan.getCumulativeCost();
	}

	private static double computeParentChildForwardingCostShare(
		boolean parentIsFed, FederatedOutput childOut,
		FederatedPlannerDpMemoTable.FedPlan childPlan,
		FederatedPlannerDpMemoTable.FedPlan parentPlan,
		int numWorkers) {

		if (childPlan == null || parentPlan == null)
			return 0.0;

		if (parentIsFed && childOut == FederatedOutput.LOUT) {
			double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childPlan.getHopRef());
			double uploadCost = FederatedPlannerDpCostEstimator.computeUploadNetworkCost(
				transferMem, childPlan.getFType(), numWorkers);
			uploadCost += FederatedCostModel.computeLocalToFedForwardingPenalty(childPlan.getFType(), numWorkers);
			return FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(uploadCost, childPlan, parentPlan);
		}
		else if (!parentIsFed && childOut == FederatedOutput.FOUT) {
			double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childPlan.getHopRef());
			double downloadCost = FederatedPlannerDpCostEstimator.computeDownloadNetworkCost(transferMem);
			return FederatedPlannerDpCostEstimator.computeForwardingCostShareForParent(downloadCost, childPlan, parentPlan);
		}
		return 0.0;
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
