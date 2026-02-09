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
import java.util.Queue;
import java.util.Set;
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
			double[] childForwardingCostToFED, List<Hop> lOUTOnlyinputHops,
			List<Double> lOUTOnlychildCumulativeCost, List<Double> lOUTOnlychildForwardingCostToFED,
			List<Hop> fOUTOnlyinputHops, List<Double> fOUTOnlychildCumulativeCost,
			List<Double> fOUTOnlychildForwardingCostToCP, int numOfWorkers) {

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
			childCumulativeCost[currentIndex][1] = computeCumulativeCostShareForParent(
					childFOutFedPlan.getCumulativeCost(), childFOutFedPlan);
			double outputMem = childHop.getOutputMemEstimate();
			double downloadCost = computeDownloadNetworkCost(outputMem);
			double uploadCost = computeUploadNetworkCost(outputMem, childLOutFedPlan.getFType(), numOfWorkers);
			childForwardingCostToCP[currentIndex] = computeForwardingCostShareForParent(
					downloadCost, childFOutFedPlan, hopCommon);
			childForwardingCostToFED[currentIndex] = computeForwardingCostShareForParent(
					uploadCost, childLOutFedPlan, hopCommon);
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
			double outputMem = childHop.getOutputMemEstimate();
			double uploadCost = computeUploadNetworkCost(outputMem, childLOutFedPlan.getFType(), numOfWorkers);
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
			fOUTOnlychildCumulativeCost.add(computeCumulativeCostShareForParent(
					childFOutFedPlan.getCumulativeCost(), childFOutFedPlan));
			double outputMem = childHop.getOutputMemEstimate();
			double downloadCost = computeDownloadNetworkCost(outputMem);
			fOUTOnlychildForwardingCostToCP.add(computeForwardingCostShareForParent(
					downloadCost, childFOutFedPlan, hopCommon));
		}
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
				hopCommon.setForwardingCost(computeDownloadNetworkCost(hopCommon.hopRef.getOutputMemEstimate()));
				return 0;
			}
		}

		double selfCost = hopCommon.getComputeWeight() * hopCommon.getMultiplicity()
				* FederatedCostModel.computeOpCost(hopCommon.hopRef);
		double forwardingCost = computeDownloadNetworkCost(hopCommon.hopRef.getOutputMemEstimate());

		hopCommon.setSelfCost(selfCost);
		hopCommon.setForwardingCost(forwardingCost);

		return selfCost;
	}

	static double computeDownloadNetworkCost(double memSize) {
		return FederatedCostModel.computeDownloadNetworkCost(memSize);
	}

	static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		return FederatedCostModel.computeUploadNetworkCost(memSize, fType, numWorkers);
	}

	public static double computeRefedNetworkCost(double memSize, FType fType, int numWorkers) {
		return FederatedCostModel.computeRefedNetworkCost(memSize, fType, numWorkers);
	}

	static double computeCumulativeCostShareForParent(double totalCost,
			FederatedPlannerDpMemoTable.FedPlan childPlan) {
		if (childPlan == null || totalCost == 0.0)
			return 0.0;
		int numParents = Math.max(1, childPlan.getNumOfParents());
		return totalCost / numParents;
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
		return (totalCost / numParents) * parentWeight;
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
		return (totalCost / numParents) * parentWeight;
	}

}
