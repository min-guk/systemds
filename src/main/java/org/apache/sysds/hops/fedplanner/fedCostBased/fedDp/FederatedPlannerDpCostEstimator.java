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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.HopsException;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
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
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireDagWalker;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.RewireConstants;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.TransTableRewireUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
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

public class FederatedPlannerDpCostEstimator {
	// Track upload-cost fallback warnings to avoid log spam (one per child hop).
	private static final Set<Long> parentChildUploadCostFallbackLogged =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * Retrieves the cumulative and forwarding costs of the child hops and stores
	 * them in arrays.
	 * Note: this method mutates {@code inputHops} in place, removing children that
	 * have only
	 * FOUT or only LOUT plans and putting them into the respective lists so that
	 * {@code inputHops}
	 * retains only children with both plan variants. The caller must pre-size the
	 * cost arrays to
	 * the original {@code inputHops.size()}, but only the prefix matching the
	 * (possibly smaller)
	 * mutated {@code inputHops.size()} will be populated.
	 */
	public static void getChildCosts(FederatedPlannerDpMemoTable.HopCommon hopCommon, FederatedPlannerDpMemoTable memoTable,
			List<Hop> inputHops,
			double[][] childCumulativeCost, double[] childForwardingCostToCP,
			double[] childForwardingCostToFED, double[] childForwardingCostFOutToFED, List<Hop> lOUTOnlyinputHops,
			List<Double> lOUTOnlychildCumulativeCost, List<Double> lOUTOnlychildForwardingCostToFED,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCostToCP, List<Double> fOUTOnlychildForwardingCostToFED,
			int numOfWorkers) {

		Hop parentHop = hopCommon.getHopRef();
		Iterator<Hop> iterator = inputHops.iterator();
		int currentIndex = 0;

		// Populate the cost buffers sequentially for children that retain both plan
		// variants.
		// Indices beyond the mutated inputHops.size() are intentionally left untouched.
		while (iterator.hasNext()) {
			Hop childHop = iterator.next();
			long childHopID = childHop.getHopID();

			FederatedPlannerDpMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
					FederatedOutput.FOUT);
			if (childFOutFedPlan == null) {
				lOUTOnlyinputHops.add(childHop);
				iterator.remove();
				continue;
			}

			FederatedPlannerDpMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
					FederatedOutput.LOUT);
			if (childLOutFedPlan == null) {
				fOUTOnlyinputHops.add(childHop);
				iterator.remove();
				continue;
			}

			childCumulativeCost[currentIndex][0] = computeCumulativeCostShareForParent(
					childLOutFedPlan.getCumulativeCost(), childLOutFedPlan);
			childCumulativeCost[currentIndex][1] = computeStableTransientReadFoutCumulativeShareForParent(
					childFOutFedPlan, memoTable);
			// If the child produces FOUT via CP execution (CP/FOUT), its local materialization
			// is already available for CP parents. Charging a FED->CP download here would
			// incorrectly penalize CP/FOUT candidates and can force DP to keep intermediates
			// local (LOUT) even when CP/FOUT would avoid expensive WAN refed forwarding.
			double downloadCost;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childHop)) {
				downloadCost = 0.0;
			}
			else if (childFOutFedPlan.getExecType() == ExecType.CP) {
				downloadCost = 0.0;
			}
				else
					downloadCost = computeDownloadNetworkCost(
						FederatedCostModel.getEffectiveOutputMemEstimate(childHop),
						childFOutFedPlan.getFType(), numOfWorkers);
				FType localUploadType = childLOutFedPlan.getCpFoutTypeOrFType();
				double uploadCost = computeUploadCostWithFallback(
						childHop, parentHop, localUploadType, numOfWorkers);
				childForwardingCostToCP[currentIndex] = computeParentChildFoutToCpDownloadShare(
						parentHop, downloadCost, childFOutFedPlan, hopCommon, memoTable);
				childForwardingCostToFED[currentIndex] = computeForwardingCostShareForParent(
					uploadCost, childLOutFedPlan, hopCommon);
			double refedForwardingCost = 0.0;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childHop)) {
				refedForwardingCost = 0.0;
				}
				else if (parentHop instanceof DataOp
					&& ((DataOp) parentHop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
					refedForwardingCost = 0.0;
				}
				else if (childFOutFedPlan.getExecType() == ExecType.CP) {
					FType cpUploadType = childFOutFedPlan.getCpFoutTypeOrFType();
					double cpFoutUploadCost = computeUploadCostWithFallback(
							childHop, parentHop, cpUploadType, numOfWorkers);
					refedForwardingCost = computeForwardingCostShareForParent(
							cpFoutUploadCost, childFOutFedPlan, hopCommon);
				}
				else if (isStableFederatedTransientProducerForLocalMaterialization(
						childFOutFedPlan, memoTable, new HashSet<>())) {
					refedForwardingCost = 0.0;
				}
				else if (shouldChargeTransientFoutToFedRefed(childHop, parentHop)) {
					double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childHop);
					double refedCost = computeRefedNetworkCost(transferMem, childFOutFedPlan.getFType(), numOfWorkers);
					refedForwardingCost = computeFoutToFedForwardingShareForParent(
							refedCost, childFOutFedPlan, hopCommon, memoTable);
				}
				childForwardingCostFOutToFED[currentIndex] = refedForwardingCost;
			currentIndex++;
		}

		for (int i = 0; i < lOUTOnlyinputHops.size(); i++) {
			Hop childHop = lOUTOnlyinputHops.get(i);
			long childHopID = childHop.getHopID();

			FederatedPlannerDpMemoTable.FedPlan childLOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
					FederatedOutput.LOUT);

			if (childLOutFedPlan == null) {
				throw new DMLRuntimeException("Missing LOUT federated plan for child hop " + childHopID + " ("
						+ childHop.getOpString() + ") while processing parent " + parentHop.getHopID() + " ("
						+ parentHop.getOpString() + ")");
				}
				lOUTOnlychildCumulativeCost.add(computeCumulativeCostShareForParent(
						childLOutFedPlan.getCumulativeCost(), childLOutFedPlan));
				FType localUploadType = childLOutFedPlan.getCpFoutTypeOrFType();
				double uploadCost = computeUploadCostWithFallback(
						childHop, parentHop, localUploadType, numOfWorkers);
				lOUTOnlychildForwardingCostToFED.add(computeForwardingCostShareForParent(
						uploadCost, childLOutFedPlan, hopCommon));
		}

		for (int i = 0; i < fOUTOnlyinputHops.size(); i++) {
			Hop childHop = fOUTOnlyinputHops.get(i);
			long childHopID = childHop.getHopID();

			FederatedPlannerDpMemoTable.FedPlan childFOutFedPlan = memoTable.getFedPlanAfterPrune(childHopID,
					FederatedOutput.FOUT);

			if (childFOutFedPlan == null) {
				throw new DMLRuntimeException("Missing FOUT federated plan for child hop " + childHopID + " ("
						+ childHop.getOpString() + ") while processing parent " + parentHop.getHopID() + " ("
						+ parentHop.getOpString() + ")");
			}
			fOUTOnlychildCumulativeCost.add(computeStableTransientReadFoutCumulativeShareForParent(
					childFOutFedPlan, memoTable));
			double downloadCost;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childHop)) {
				downloadCost = 0.0;
			}
			else if (childFOutFedPlan.getExecType() == ExecType.CP) {
				downloadCost = 0.0;
			}
			else
				downloadCost = computeDownloadNetworkCost(
					FederatedCostModel.getEffectiveOutputMemEstimate(childHop),
					childFOutFedPlan.getFType(), numOfWorkers);
			fOUTOnlychildForwardingCostToCP.add(computeParentChildFoutToCpDownloadShare(
					parentHop, downloadCost, childFOutFedPlan, hopCommon, memoTable));
			double refedForwardingCost = 0.0;
			if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childHop)) {
				refedForwardingCost = 0.0;
				}
				else if (childFOutFedPlan.getExecType() == ExecType.CP) {
					FType cpUploadType = childFOutFedPlan.getCpFoutTypeOrFType();
					double cpFoutUploadCost = computeUploadCostWithFallback(
							childHop, parentHop, cpUploadType, numOfWorkers);
					refedForwardingCost = computeForwardingCostShareForParent(
							cpFoutUploadCost, childFOutFedPlan, hopCommon);
				}
				else if (parentHop instanceof DataOp
					&& ((DataOp) parentHop).getOp() == Types.OpOpData.TRANSIENTWRITE
					&& isStableFederatedTransientProducerForLocalMaterialization(
						childFOutFedPlan, memoTable, new HashSet<>())) {
					refedForwardingCost = 0.0;
				}
				else if (shouldChargeTransientFoutToFedRefed(childHop, parentHop)) {
					double transferMem = FederatedCostModel.getEffectiveUploadMemEstimate(childHop);
					double refedCost = computeRefedNetworkCost(transferMem, childFOutFedPlan.getFType(), numOfWorkers);
					refedForwardingCost = computeFoutToFedForwardingShareForParent(
							refedCost, childFOutFedPlan, hopCommon, memoTable);
				}
				fOUTOnlychildForwardingCostToFED.add(refedForwardingCost);
		}
	}

	private static boolean isTransientFedBoundary(Hop hop) {
		if (!(hop instanceof DataOp))
			return false;
		Types.OpOpData op = ((DataOp) hop).getOp();
		return op == Types.OpOpData.TRANSIENTREAD || op == Types.OpOpData.TRANSIENTWRITE;
	}

	static boolean shouldChargeTransientFoutToFedRefed(Hop childHop, Hop parentHop) {
		// Charge FOUT->FED refed only on transient boundaries that represent a real
		// runtime materialization / handoff:
		//   - any transient child consumed by a FED parent,
		//   - producer -> TRANSIENTWRITE boundaries for non-anchor producers.
		//
		// Bare FEDERATED anchors flowing into TRANSIENTWRITE remain metadata pass-throughs
		// and must not pay another refed/upload share. Direct FEDERATED/FedInit source
		// -> TRANSIENTREAD edges are also metadata aliases rather than runtime refed
		// handoffs, so they stay free here; stable TRANSIENTREAD continuations can still
		// be zeroed later by computeFoutToFedForwardingShareForParent(...).
		if (isTransientFedBoundary(childHop))
			return true;
		if (!(parentHop instanceof DataOp))
			return false;
		Types.OpOpData parentOp = ((DataOp) parentHop).getOp();
		if (parentOp == Types.OpOpData.TRANSIENTWRITE)
			return childHop == null || !childHop.isFederatedDataOp();
		if (parentOp == Types.OpOpData.TRANSIENTREAD)
			return false;
		return false;
	}

	/**
	 * Computes the cost associated with a given Hop node.
	 * This method calculates both the self cost and the forwarding cost for the
	 * Hop,
	 * taking into account its type and the number of parent nodes.
	 *
	 * @param hopCommon The HopCommon object containing the Hop and its properties.
	 * @return The self cost of the Hop.
	 */
	public static double computeHopCost(FederatedPlannerDpMemoTable.HopCommon hopCommon) {
		// TWrite and TRead are meta-data operations, hence selfCost is zero
		if (hopCommon.hopRef instanceof DataOp) {
			if (((DataOp) hopCommon.hopRef).getOp() == Types.OpOpData.TRANSIENTWRITE) {
				hopCommon.setSelfCost(0);
				// Since TWrite and TRead have the same FedOutType, forwarding cost is zero
				hopCommon.setForwardingCost(0);
				return 0;
			} else if (((DataOp) hopCommon.hopRef).getOp() == Types.OpOpData.TRANSIENTREAD) {
				hopCommon.setSelfCost(0);
				// TRead may have a different FedOutType from its parent, so calculate
				// forwarding cost
				hopCommon.setForwardingCost(computeDownloadNetworkCost(
					FederatedCostModel.getEffectiveOutputMemEstimate(hopCommon.hopRef)));
				return 0;
			}
		}

		double selfCost = hopCommon.getComputeWeight() * hopCommon.getMultiplicity()
				* FederatedCostModel.computeOpCostWithFallback(hopCommon.hopRef);
		double forwardingCost = computeDownloadNetworkCost(
			FederatedCostModel.getEffectiveOutputMemEstimate(hopCommon.hopRef));

		hopCommon.setSelfCost(selfCost);
		hopCommon.setForwardingCost(forwardingCost);

		return selfCost;
	}

	static double computeDownloadNetworkCost(double memSize) {
		return FederatedCostModel.computeDownloadNetworkCost(memSize);
	}

	static double computeDownloadNetworkCost(double memSize, FType fType, int numWorkers) {
		return FederatedCostModel.computeDownloadNetworkCost(memSize, fType, numWorkers);
	}

	static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		return FederatedCostModel.computeUploadNetworkCost(memSize, fType, numWorkers);
	}

	public static double computeRefedNetworkCost(double memSize, FType fType, int numWorkers) {
		return FederatedCostModel.computeRefedNetworkCost(memSize, fType, numWorkers);
	}

	static double computeUploadCostWithFallback(Hop childHop, Hop parentHop, FType uploadType, int numWorkers) {
		if (!FederatedCostModel.requiresExplicitMatrixBoundaryTransfer(childHop))
			return 0.0;
		// Align forwarding-cost estimation with runtime CP->FOUT materialization policy.
		//
		// When the global anchor key implies ROW/COL partitioning but the forwarded local matrix's
		// axis length (or vector axis) does not match, runtime will broadcast the data even if the
		// logical FType is ROW/COL. If we do not apply the same adjustment here, the planner can
		// severely under-estimate LOUT->FED upload cost (missing the replication multiplier).
		FType adjustedUploadType = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(childHop, uploadType);
		double outputMemEstimate = FederatedCostModel.getEffectiveUploadMemEstimate(childHop);
		double uploadCost = computeUploadNetworkCost(outputMemEstimate, adjustedUploadType, numWorkers);
		FType effectiveUploadType = adjustedUploadType;
		if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0))
			return uploadCost + FederatedCostModel.computeLocalToFedForwardingPenalty(
					effectiveUploadType, numWorkers);

		final double originalUploadCost = uploadCost;
		FType fallbackUploadType = adjustedUploadType;
		double fallbackMemEstimate = outputMemEstimate;
		if (fallbackUploadType == null && childHop != null)
			fallbackUploadType = FederatedPlannerUtils.getVectorAxis(childHop);
		fallbackUploadType = FederatedRefedPolicy.adjustCpFoutFTypeForAnchorKey(childHop, fallbackUploadType);
		if (fallbackMemEstimate <= 0.0 && childHop != null)
			fallbackMemEstimate = FederatedCostModel.getEffectiveInputMemEstimate(childHop);
		if (fallbackMemEstimate > 0.0) {
			uploadCost = FederatedCostModel.computeUploadNetworkCost(
					fallbackMemEstimate, fallbackUploadType, numWorkers);
			effectiveUploadType = fallbackUploadType;
		}

		long childHopID = (childHop != null) ? childHop.getHopID() : -1;
		if ((Double.isNaN(uploadCost) || uploadCost <= 0.0)
				&& childHopID >= 0 && parentChildUploadCostFallbackLogged.add(childHopID)) {
			String childOp = (childHop != null) ? childHop.getOpString() : "null";
			String parentOp = (parentHop != null) ? parentHop.getOpString() : "null";
			long parentHopID = (parentHop != null) ? parentHop.getHopID() : -1;
			FederatedPlannerLogger.logWarnMessage(
					"[DP] Parent-child forwarding LOUT->FED upload cost missing/zero for child hop "
							+ childHopID + " (" + childOp + ") consumed by parent hop "
							+ parentHopID + " (" + parentOp + "). "
								+ "uploadCost=" + originalUploadCost
								+ ", outputMemEstimate=" + outputMemEstimate
								+ ", fallbackMemEstimate=" + fallbackMemEstimate
								+ ", uploadType=" + uploadType
								+ ", fallbackType=" + fallbackUploadType
								+ ", numWorkers=" + numWorkers
							+ "; forwarding cost may be under-estimated.");
		}
		else if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0)
				&& childHopID >= 0 && parentChildUploadCostFallbackLogged.add(childHopID)) {
			String childOp = (childHop != null) ? childHop.getOpString() : "null";
			FederatedPlannerLogger.logInfoMessage(
					"[DP] Recovered missing parent-child LOUT->FED upload cost for child hop "
							+ childHopID + " (" + childOp + "): " + originalUploadCost + " -> " + uploadCost);
		}
		if (!(Double.isNaN(uploadCost) || uploadCost <= 0.0)) {
			uploadCost += FederatedCostModel.computeLocalToFedForwardingPenalty(
					effectiveUploadType, numWorkers);
		}
		return uploadCost;
	}

	static double computeCumulativeCostShareForParent(double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null || totalCost == 0.0)
			return 0.0;
		if (shouldKeepFullCalleeCostForFunctionOutputParents(childPlan))
			return totalCost;
		int numParents = Math.max(1, childPlan.getNumOfParents());
		return totalCost / numParents;
	}

	static double computeStableTransientReadFoutCumulativeShareForParent(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null)
			return 0.0;
		Hop childHop = childPlan.getHopRef();
		boolean trace = FederatedPlannerTrace.shouldTrace(childHop);
		double share = computeCumulativeCostShareForParent(childPlan.getCumulativeCost(), childPlan);
		double materializationFactor = computeStableTransientReadLocalMaterializationFactor(childPlan, memoTable);
		if (materializationFactor <= 1) {
			if (trace) {
				FederatedPlannerTrace.log(childHop, "DP-StableTRShare", String.format(Locale.ROOT,
					"factor=%.6f share=%.6f forwardingShare=0.000000 embeddedForwardingShare=0.000000 result=%.6f",
					materializationFactor, share, share));
			}
			return share;
		}
		double forwardingShare = computeCumulativeCostShareForParent(childPlan.getForwardingCost(), childPlan);
		// Some stable FED-input TRANSIENTREAD plans do not embed their boundary-forwarding cost
		// inside cumulativeCost (the forwarding stays on the edge accounting side and is handled
		// separately via FOUT->CP materialization sharing). In those cases, subtracting the full
		// forwarding share here drives the child cumulative share negative and makes CP-local
		// parent variants appear artificially cheap on visible federated-input chains such as
		// PCA X -> b(-) -> b(/) -> TWrite X.
		//
		// Only amortize the forwarding portion that is actually represented inside the cumulative
		// share. This preserves the intended sharing when cumulativeCost includes the forwarding
		// term, while avoiding negative exact-hop costs when it does not.
		double embeddedForwardingShare = Math.min(Math.max(0.0, share), Math.max(0.0, forwardingShare));
		double result = share - embeddedForwardingShare + embeddedForwardingShare / materializationFactor;
		if (trace) {
			FederatedPlannerTrace.log(childHop, "DP-StableTRShare", String.format(Locale.ROOT,
				"factor=%.6f share=%.6f forwardingShare=%.6f embeddedForwardingShare=%.6f result=%.6f",
				materializationFactor, share, forwardingShare, embeddedForwardingShare, result));
		}
		return result;
	}

	private static boolean shouldKeepFullCalleeCostForFunctionOutputParents(
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null || childPlan.getNumOfParents() <= 1)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null)
			return false;
		List<Hop> parentHops = childHop.getParent();
		if (parentHops == null || parentHops.size() <= 1)
			return false;
		for (Hop parentHop : parentHops) {
			if (!isFunctionBoundaryParent(parentHop)) {
				return false;
			}
		}
		return true;
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

	static double computeFoutToCpDownloadShareForParent(double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.HopCommon parentHopCommon) {
		double share = computeForwardingCostShareForParent(totalCost, childPlan, parentHopCommon);
		if (!shouldAmortizeFederatedInputDownloadAcrossParents(childPlan))
			return share;
		return share / Math.max(1, childPlan.getNumOfParents());
	}

	static double computeFoutToCpDownloadShareForParent(double totalCost,
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		double share = computeForwardingCostShareForParent(totalCost, childPlan, parentPlan);
		if (!shouldAmortizeFederatedInputDownloadAcrossParents(childPlan))
			return share;
		return share / Math.max(1, childPlan.getNumOfParents());
	}

	static double computeParentChildFoutToCpDownloadShare(Hop parentHop, double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.HopCommon parentHopCommon) {
		if (shouldSkipAggregateToPublicFoutDownload(parentHop, childPlan, null))
			return 0.0;
		return computeFoutToCpDownloadShareForParent(totalCost, childPlan, parentHopCommon);
	}

	static double computeParentChildFoutToCpDownloadShare(Hop parentHop, double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.HopCommon parentHopCommon,
			FederatedPlannerDpMemoTable memoTable) {
		if (shouldSkipAggregateToPublicFoutDownload(parentHop, childPlan, memoTable))
			return 0.0;
		double share = computeForwardingCostShareForParent(totalCost, childPlan, parentHopCommon);
		double materializationFactor = computeFoutToCpLocalMaterializationFactor(childPlan, memoTable);
		materializationFactor *= computeStableTransientReadParentIterationReuseFactor(
			childPlan, parentHopCommon, memoTable);
		double result = share / materializationFactor;
		Hop childHop = childPlan != null ? childPlan.getHopRef() : null;
		if (FederatedPlannerTrace.shouldTrace(childHop)) {
			FederatedPlannerTrace.log(childHop, "DP-FoutCpShare", String.format(Locale.ROOT,
				"parentHop=%d totalCost=%.6f rawShare=%.6f factor=%.6f result=%.6f",
				parentHop != null ? parentHop.getHopID() : -1L,
				totalCost, share, materializationFactor, result));
		}
		return result;
	}

	static double computeParentChildFoutToCpDownloadShare(Hop parentHop, double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		if (shouldSkipAggregateToPublicFoutDownload(parentHop, childPlan, null))
			return 0.0;
		return computeFoutToCpDownloadShareForParent(totalCost, childPlan, parentPlan);
	}

	static double computeParentChildFoutToCpDownloadShare(Hop parentHop, double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.FedPlan parentPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (shouldSkipAggregateToPublicFoutDownload(parentHop, childPlan, memoTable))
			return 0.0;
		double share = computeForwardingCostShareForParent(totalCost, childPlan, parentPlan);
		double materializationFactor = computeFoutToCpLocalMaterializationFactor(childPlan, memoTable);
		materializationFactor *= computeStableTransientReadParentIterationReuseFactor(
			childPlan, parentPlan, memoTable);
		double result = share / materializationFactor;
		Hop childHop = childPlan != null ? childPlan.getHopRef() : null;
		if (FederatedPlannerTrace.shouldTrace(childHop)) {
			FederatedPlannerTrace.log(childHop, "DP-FoutCpShare", String.format(Locale.ROOT,
				"parentHop=%d totalCost=%.6f rawShare=%.6f factor=%.6f result=%.6f",
				parentPlan != null && parentPlan.getHopRef() != null ? parentPlan.getHopRef().getHopID() : -1L,
				totalCost, share, materializationFactor, result));
		}
		return result;
	}

	static double computeFoutToFedForwardingShareForParent(double totalCost,
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.HopCommon parentHopCommon) {
		if (isStableFederatedTransientReadForFoutToFed(childPlan))
			return 0.0;
		return computeForwardingCostShareForParent(totalCost, childPlan, parentHopCommon);
	}

	static double computeFoutToFedForwardingShareForParent(double totalCost,
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.HopCommon parentHopCommon,
				FederatedPlannerDpMemoTable memoTable) {
		if (isStableFederatedTransientReadForFoutToFed(childPlan, memoTable))
			return 0.0;
		return computeForwardingCostShareForParent(totalCost, childPlan, parentHopCommon);
	}

	static double computeFoutToFedForwardingShareForParent(double totalCost,
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		if (isStableFederatedTransientReadForFoutToFed(childPlan))
			return 0.0;
		return computeForwardingCostShareForParent(totalCost, childPlan, parentPlan);
	}

	static double computeFoutToFedForwardingShareForParent(double totalCost,
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.FedPlan parentPlan,
				FederatedPlannerDpMemoTable memoTable) {
		if (isStableFederatedTransientReadForFoutToFed(childPlan, memoTable))
			return 0.0;
		return computeForwardingCostShareForParent(totalCost, childPlan, parentPlan);
	}

	static double computeForwardingCostShareForParent(double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.HopCommon parentHopCommon) {
		if (childPlan == null || totalCost == 0.0)
			return 0.0;
		int numParents = Math.max(1, childPlan.getNumOfParents());
		if (parentHopCommon == null)
			return totalCost / numParents;

		double parentWeight = parentHopCommon.computeForwardingWeightOfChild(
				childPlan.getLoopContext(), parentHopCommon.getMultiplicity());
		parentWeight = Math.max(0.0, parentWeight);
		// Boundary forwarding is paid per parent-child edge; do not amortize by
		// child parent-count (align with MinST edge-capacity semantics).
		return totalCost * parentWeight;
	}

	static double computeForwardingCostShareForParent(double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.FedPlan parentPlan) {
		if (childPlan == null || totalCost == 0.0)
			return 0.0;
		int numParents = Math.max(1, childPlan.getNumOfParents());
		if (parentPlan == null)
			return totalCost / numParents;

		double parentWeight = parentPlan.computeForwardingWeightOfChild(
				childPlan.getLoopContext(), parentPlan.getMultiplicity());
		parentWeight = Math.max(0.0, parentWeight);
		return totalCost * parentWeight;
	}

	private static boolean shouldAmortizeFederatedInputDownloadAcrossParents(
				FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null || childPlan.getNumOfParents() <= 1)
			return false;
		return isStableFederatedInputRead(childPlan);
	}

	private static double computeStableTransientReadLocalMaterializationFactor(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null)
			return 1.0;
		if (!isStableFederatedInputReadForLocalMaterialization(childPlan, memoTable))
			return 1.0;

		double factor = Math.max(1, childPlan.getNumOfParents());
		if (memoTable == null)
			return factor;

		Hop childHop = childPlan.getHopRef();
		if (!(childHop instanceof DataOp))
			return factor;
		DataOp childDataOp = (DataOp) childHop;
		if (childDataOp.getOp() != Types.OpOpData.TRANSIENTREAD)
			return factor;

		List<Pair<Long, FederatedOutput>> producerEdges = childPlan.getChildFedPlans();
		if (producerEdges == null || producerEdges.isEmpty())
			return factor;

		Set<Long> seenSiblingOrigHopIds = new HashSet<>();
		int sharedConsumerCount = 0;
		for (Pair<Long, FederatedOutput> producerEdge : producerEdges) {
			FederatedPlannerDpMemoTable.FedPlan producerPlan = memoTable.getFedPlanAfterPrune(producerEdge);
			if (producerPlan == null || !(producerPlan.getHopRef() instanceof DataOp))
				continue;
			DataOp producerHop = (DataOp) producerPlan.getHopRef();
			if (producerHop.getOp() != Types.OpOpData.TRANSIENTWRITE)
				continue;
			long producerOrigHopId = memoTable.resolveOriginalHopId(producerPlan.getHopID());
			List<Long> siblingHopIds = memoTable.collectTransientReadSiblingHopIDs(
				producerOrigHopId, childDataOp.getName());
			if (siblingHopIds == null || siblingHopIds.isEmpty())
				continue;
			for (long siblingHopId : siblingHopIds) {
				long siblingOrigHopId = memoTable.resolveOriginalHopId(siblingHopId);
				if (!seenSiblingOrigHopIds.add(siblingOrigHopId))
					continue;
				FederatedPlannerDpMemoTable.FedPlan siblingLocalPlan =
					memoTable.getFedPlanAfterPrune(siblingHopId, FederatedOutput.LOUT);
				if (siblingLocalPlan == null)
					continue;
				sharedConsumerCount += Math.max(1, siblingLocalPlan.getNumOfParents());
			}
		}

		if (sharedConsumerCount > 0)
			factor = Math.max(factor, sharedConsumerCount);
		factor = Math.max(factor, computeStableTransientReadLoopReuseFactor(childPlan));
		return Math.max(1.0, factor);
	}

	private static double computeFoutToCpLocalMaterializationFactor(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		double stableTransientFactor = computeStableTransientReadLocalMaterializationFactor(childPlan, memoTable);
		int computedFamilyFactor = computeTransientWriteBackedComputedMaterializationFactor(childPlan, memoTable);
		return Math.max(stableTransientFactor, computedFamilyFactor);
	}

	private static double computeStableTransientReadLoopReuseFactor(
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null)
			return 1.0;
		double opWeight = childPlan.getComputeWeight();
		if (!Double.isFinite(opWeight) || opWeight <= 0.0)
			opWeight = 1.0;
		double networkWeight = childPlan.getNetworkWeight();
		if (!Double.isFinite(networkWeight) || networkWeight <= 0.0)
			networkWeight = 1.0;
		return Math.max(1.0, opWeight / networkWeight);
	}

	private static double computeStableTransientReadParentIterationReuseFactor(
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.HopCommon parentHopCommon,
				FederatedPlannerDpMemoTable memoTable) {
		if (!isStableFederatedInputReadForLocalMaterialization(childPlan, memoTable) || parentHopCommon == null)
			return 1.0;
		if (isSameLoopContext(parentHopCommon.getLoopContext(), childPlan.getLoopContext()))
			return 1.0;
		double parentMultiplicity = parentHopCommon.getMultiplicity();
		return (!Double.isFinite(parentMultiplicity) || parentMultiplicity <= 1.0) ? 1.0 : parentMultiplicity;
	}

	private static double computeStableTransientReadParentIterationReuseFactor(
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable.FedPlan parentPlan,
				FederatedPlannerDpMemoTable memoTable) {
		if (!isStableFederatedInputReadForLocalMaterialization(childPlan, memoTable) || parentPlan == null)
			return 1.0;
		if (isSameLoopContext(parentPlan.getLoopContext(), childPlan.getLoopContext()))
			return 1.0;
		double parentMultiplicity = parentPlan.getMultiplicity();
		return (!Double.isFinite(parentMultiplicity) || parentMultiplicity <= 1.0) ? 1.0 : parentMultiplicity;
	}

	private static boolean isSameLoopContext(List<Pair<Long, Double>> parentLoopContext,
			List<Pair<Long, Double>> childLoopContext) {
		if (parentLoopContext == null || parentLoopContext.isEmpty()
				|| childLoopContext == null || childLoopContext.isEmpty())
			return false;
		if (parentLoopContext.size() != childLoopContext.size())
			return false;
		for (int i = 0; i < parentLoopContext.size(); i++) {
			Pair<Long, Double> p = parentLoopContext.get(i);
			Pair<Long, Double> c = childLoopContext.get(i);
			if (p == null || c == null)
				return false;
			if (!Objects.equals(p.getLeft(), c.getLeft()))
				return false;
			double pv = p.getRight() == null ? Double.NaN : p.getRight();
			double cv = c.getRight() == null ? Double.NaN : c.getRight();
			if (Double.isNaN(pv) || Double.isNaN(cv) || Math.abs(pv - cv) > 1e-9)
				return false;
		}
		return true;
	}

	private static int computeTransientWriteBackedComputedMaterializationFactor(
				FederatedPlannerDpMemoTable.FedPlan childPlan,
				FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null || memoTable == null)
			return 1;
		if (childPlan.getExecType() != ExecType.FED || childPlan.getFedOutType() != FederatedOutput.FOUT)
			return 1;
		Hop childHop = childPlan.getHopRef();
		Hop originalChildHop = memoTable.resolveOriginalHop(childPlan.getHopID());
		Hop materializedChildHop = (originalChildHop != null) ? originalChildHop : childHop;
		if (materializedChildHop == null || materializedChildHop instanceof DataOp
				|| !materializedChildHop.getDataType().isMatrix())
			return 1;
		if (childPlan.getNumOfParents() <= 1)
			return 1;

		List<Hop> parentHops = materializedChildHop.getParent();
		if (parentHops == null || parentHops.size() <= 1)
			return 1;

		boolean hasTransientWriteParent = false;
		Set<Long> seenParentOrigHopIds = new HashSet<>();
		int reusableConsumerCount = 0;
		for (Hop parentHop : parentHops) {
			if (parentHop == null)
				continue;
			long parentOrigHopId = memoTable.resolveOriginalHopId(parentHop.getHopID());
			if (!seenParentOrigHopIds.add(parentOrigHopId))
				continue;
			reusableConsumerCount++;
			if (parentHop instanceof DataOp
					&& ((DataOp) parentHop).getOp() == Types.OpOpData.TRANSIENTWRITE)
				hasTransientWriteParent = true;
		}

		return (hasTransientWriteParent && reusableConsumerCount > 1)
			? reusableConsumerCount
			: 1;
	}

	private static boolean isStableFederatedInputReadForLocalMaterialization(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null || childPlan.getExecType() != ExecType.FED)
			return false;
		if (!(childHop instanceof DataOp)
				|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTREAD
				|| childPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		if (childHop.isFederatedDataOp())
			return true;
		if (memoTable == null)
			return isStableFederatedInputRead(childPlan);
		List<Pair<Long, FederatedOutput>> producerEdges = childPlan.getChildFedPlans();
		if (producerEdges == null || producerEdges.isEmpty())
			return isStableFedInitTransientRead(childPlan)
				|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead((DataOp) childHop, null);
		for (Pair<Long, FederatedOutput> producerEdge : producerEdges) {
			FederatedPlannerDpMemoTable.FedPlan producerPlan = memoTable.getFedPlanAfterPrune(producerEdge);
			if (producerPlan == null)
				continue;
			if (isFederatedTransientWriteProducerForLocalMaterialization(producerPlan))
				return true;
			if (isStableFederatedTransientProducerForLocalMaterialization(producerPlan, memoTable, new HashSet<>()))
				return true;
		}
		return false;
	}

	private static boolean isFederatedTransientWriteProducerForLocalMaterialization(
			FederatedPlannerDpMemoTable.FedPlan producerPlan) {
		if (producerPlan == null)
			return false;
		Hop producerHop = producerPlan.getHopRef();
		if (!(producerHop instanceof DataOp))
			return false;
		DataOp producerDataOp = (DataOp) producerHop;
		return producerPlan.getExecType() == ExecType.FED
			&& producerPlan.getFedOutType() == FederatedOutput.FOUT
			&& producerDataOp.getOp() == Types.OpOpData.TRANSIENTWRITE;
	}

	private static boolean isStableFederatedTransientProducerForLocalMaterialization(
			FederatedPlannerDpMemoTable.FedPlan producerPlan,
			FederatedPlannerDpMemoTable memoTable,
			Set<Long> visitedHopIds) {
		if (producerPlan == null || memoTable == null)
			return false;
		Hop producerHop = producerPlan.getHopRef();
		if (producerHop == null
				|| producerPlan.getExecType() != ExecType.FED
				|| producerPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		FType producerFType = producerPlan.getFType();
		if (producerFType == null)
			return false;
		long producerHopId = producerHop.getHopID();
		if (!visitedHopIds.add(producerHopId))
			return false;
		if (producerHop.isFederatedDataOp())
			return true;
		if (producerHop instanceof DataOp) {
			DataOp producerDataOp = (DataOp) producerHop;
			if (producerDataOp.getOp() == Types.OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(producerDataOp, null))
				return true;
		}
		List<Pair<Long, FederatedOutput>> childEdges = producerPlan.getChildFedPlans();
		if (childEdges == null || childEdges.isEmpty())
			return false;
		for (Pair<Long, FederatedOutput> childEdge : childEdges) {
			FederatedPlannerDpMemoTable.FedPlan upstreamPlan = memoTable.getFedPlanAfterPrune(childEdge);
			if (upstreamPlan == null)
				continue;
			if (isStableFederatedTransientProducerForLocalMaterialization(upstreamPlan, memoTable, visitedHopIds))
				return true;
		}
		return false;
	}

	static boolean shouldSkipAggregateToPublicFoutDownload(Hop parentHop,
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (!(parentHop instanceof AggBinaryOp) && !(parentHop instanceof QuaternaryOp))
			return false;
		return memoTable != null
			? isStableAggregateToPublicInputRead(childPlan, memoTable)
			: isStableAggregateToPublicInputRead(childPlan);
	}

	static boolean isStableFedInitTransientRead(FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null || childPlan.getExecType() != ExecType.FED || !(childHop instanceof DataOp))
			return false;
		String childName = childHop.getName();
		return ((DataOp) childHop).getOp() == Types.OpOpData.TRANSIENTREAD
				&& childName != null
				&& FederatedPlannerUtils.isFedInitVar(childName);
	}

	private static boolean isFederatedTransientRead(FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null || childPlan.getExecType() != ExecType.FED)
			return false;
		Hop childHop = childPlan.getHopRef();
		return childHop instanceof DataOp
				&& ((DataOp) childHop).getOp() == Types.OpOpData.TRANSIENTREAD
				&& childPlan.getFedOutType() == FederatedOutput.FOUT;
	}

	private static boolean isStableFederatedTransientReadForFoutToFed(
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
		return isFederatedTransientRead(childPlan) && isStableFederatedInputRead(childPlan);
	}

	private static boolean isStableFederatedTransientReadForFoutToFed(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		return isFederatedTransientRead(childPlan) && isStableFederatedInputRead(childPlan, memoTable);
	}

	private static boolean isStableFederatedInputRead(FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null || childPlan.getExecType() != ExecType.FED)
			return false;
		if (childHop.isFederatedDataOp())
			return true;
		return isStableFedInitTransientRead(childPlan);
	}

	private static boolean isStableAggregateToPublicInputRead(
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null || childPlan.getExecType() != ExecType.FED)
			return false;
		if (childHop.isFederatedDataOp())
			return true;
		if (!(childHop instanceof DataOp)
				|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTREAD
				|| childPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		DataOp transientRead = (DataOp) childHop;
		return isStableFedInitTransientRead(childPlan)
				|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(transientRead, null);
	}

	private static boolean isStableAggregateToPublicInputRead(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null || childPlan.getExecType() != ExecType.FED)
			return false;
		if (childHop.isFederatedDataOp())
			return true;
		if (!(childHop instanceof DataOp)
				|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTREAD
				|| childPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		DataOp transientRead = (DataOp) childHop;
		List<Pair<Long, FederatedOutput>> producerEdges = childPlan.getChildFedPlans();
		if (memoTable != null && producerEdges != null && !producerEdges.isEmpty()) {
			// Explicit producer edges describe the real runtime source for this TRANSIENTREAD.
			// Do not hide that concrete local materialization boundary behind the old
			// aggregate-to-public skip. This keeps CP-local aggregate parents from treating
			// federated-source reads as free when the runtime still has to acquire the data
			// locally before the ba(+*)/wdivmm executes.
			return false;
		}
		return isStableFedInitTransientRead(childPlan)
				|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(transientRead, null);
	}

	private static boolean isStableFederatedInputRead(FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null)
			return false;
		Hop childHop = childPlan.getHopRef();
		if (childHop == null || childPlan.getExecType() != ExecType.FED)
			return false;
		if (childHop.isFederatedDataOp())
			return true;
		if (!(childHop instanceof DataOp)
				|| ((DataOp) childHop).getOp() != Types.OpOpData.TRANSIENTREAD
				|| childPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		DataOp transientRead = (DataOp) childHop;
		FType childFType = childPlan.getFType();
		if (childFType == null)
			return false;
		List<Pair<Long, FederatedOutput>> producerEdges = childPlan.getChildFedPlans();
		if (memoTable != null && producerEdges != null && !producerEdges.isEmpty()) {
			for (Pair<Long, FederatedOutput> producerEdge : producerEdges) {
				FederatedPlannerDpMemoTable.FedPlan producerPlan = memoTable.getFedPlanAfterPrune(producerEdge);
				if (!isStableFederatedTransientProducer(childPlan, producerPlan, memoTable))
					continue;
				return true;
			}
			return false;
		}

		// Only fall back to anchor / fed-init name semantics when the selected plan has no
		// explicit producer edges. If a transient read is fed by a concrete local producer
		// (e.g., rewritten X in PCA), the producer edges describe the real runtime source and
		// must win over a stale variable-name-level fed-init anchor.
		return isStableFedInitTransientRead(childPlan)
				|| FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(transientRead, null);
	}

	private static boolean isStableFederatedTransientProducer(
			FederatedPlannerDpMemoTable.FedPlan childPlan,
			FederatedPlannerDpMemoTable.FedPlan producerPlan,
			FederatedPlannerDpMemoTable memoTable) {
		if (childPlan == null || producerPlan == null)
			return false;
		Hop producerHop = producerPlan.getHopRef();
		if (!(producerHop instanceof DataOp)
				|| producerPlan.getExecType() != ExecType.FED
				|| producerPlan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		FType childFType = childPlan.getFType();
		FType producerFType = producerPlan.getFType();
		if (childFType == null || producerFType == null || childFType != producerFType)
			return false;
		DataOp producerDataOp = (DataOp) producerHop;
		if (producerDataOp.getOp() == Types.OpOpData.FEDERATED)
			return true;
		if (producerDataOp.getOp() == Types.OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(producerDataOp, null))
			return true;
		if (producerDataOp.getOp() != Types.OpOpData.TRANSIENTWRITE)
			return false;
		if (hasStableFederatedUpstreamChain(childFType, producerPlan, memoTable, new HashSet<>()))
			return true;
		List<Hop> producerInputs = producerHop.getInput();
		if (producerInputs == null || producerInputs.size() != 1)
			return false;
		Hop producerInput = producerInputs.get(0);
		if (!(producerInput instanceof DataOp))
			return false;
		DataOp producerDataInput = (DataOp) producerInput;
		if (producerDataInput.getOp() == Types.OpOpData.FEDERATED)
			return true;
		return producerDataInput.getOp() == Types.OpOpData.TRANSIENTREAD
				&& FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(producerDataInput, null);
	}

	private static boolean hasStableFederatedUpstreamChain(FType expectedFType,
			FederatedPlannerDpMemoTable.FedPlan plan,
			FederatedPlannerDpMemoTable memoTable,
			Set<Long> visitedHopIds) {
		if (expectedFType == null || plan == null || memoTable == null)
			return false;
		Hop planHop = plan.getHopRef();
		if (planHop == null || plan.getExecType() != ExecType.FED || plan.getFedOutType() != FederatedOutput.FOUT)
			return false;
		FType planFType = plan.getFType();
		if (planFType == null || planFType != expectedFType)
			return false;
		long planHopId = planHop.getHopID();
		if (!visitedHopIds.add(planHopId))
			return false;
		if (planHop.isFederatedDataOp())
			return true;
		if (planHop instanceof DataOp) {
			DataOp dataOp = (DataOp) planHop;
			if (dataOp.getOp() == Types.OpOpData.TRANSIENTREAD
					&& FederatedPlannerUtils.hasConcreteFederatedSourceForTransientRead(dataOp, null))
				return true;
		}
		List<Pair<Long, FederatedOutput>> childEdges = plan.getChildFedPlans();
		if (childEdges == null || childEdges.isEmpty())
			return false;
		for (Pair<Long, FederatedOutput> childEdge : childEdges) {
			FederatedPlannerDpMemoTable.FedPlan upstreamPlan = memoTable.getFedPlanAfterPrune(childEdge);
			if (upstreamPlan == null)
				continue;
			if (hasStableFederatedUpstreamChain(expectedFType, upstreamPlan, memoTable, visitedHopIds))
				return true;
		}
		return false;
	}

}
