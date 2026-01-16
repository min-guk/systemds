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

/**
 * Baseline federated planner that compiles all hops
 * that support federated execution on federated inputs to
 * forced federated operations.
 */
public class FederatedPlannerDpFedCostBased extends AFederatedPlanner {
	private static final int MAX_ENUM_INPUTS = 20; // guard against 2^n blowups and shift overflow

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = FederatedPlannerDpCostEnumerator.enumerateProgram(prog, memoTable, true);

			Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited = new HashMap<>();
			Map<Long, Boolean> visitedFromClone = new HashMap<>();
			Set<Long> visitedPlanHops = new HashSet<>();
			Map<Long, FType> fTypeMap = new HashMap<>();

		List<Pair<Long, FEDInstruction.FederatedOutput>> childFedPlanPairs = optimalPlan.getChildFedPlans();
		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : childFedPlanPairs) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
				rewriteHop(childPlan, memoTable, visited, visitedFromClone, visitedPlanHops, fTypeMap);
			}
			FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedPlannerDpMemoTable memoTable = new FederatedPlannerDpMemoTable();
		FederatedPlannerDpMemoTable.FedPlan optimalPlan = FederatedPlannerDpCostEnumerator.enumerateFunctionDynamic(function,
				memoTable, true);
			Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited = new HashMap<>(); // hop ID, selected
																									// placement/exec
			Map<Long, Boolean> visitedFromClone = new HashMap<>();
			Set<Long> visitedPlanHops = new HashSet<>();
			Map<Long, FType> fTypeMap = new HashMap<>();

		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}
			// Propagate the actual selected output type of the child plan (LOUT/FOUT)
				rewriteHop(childPlan, memoTable, visited, visitedFromClone, visitedPlanHops, fTypeMap);
			}
			FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
		}

		private void rewriteHop(FederatedPlannerDpMemoTable.FedPlan optimalPlan, FederatedPlannerDpMemoTable memoTable,
				Map<Long, Pair<FEDInstruction.FederatedOutput, ExecType>> visited, Map<Long, Boolean> visitedFromClone,
				Set<Long> visitedPlanHops,
				Map<Long, FType> fTypeMap) {
			long planHopId = optimalPlan.getHopRef().getHopID();
			if (visitedPlanHops != null && !visitedPlanHops.add(planHopId))
				return;
			long hopID = memoTable.resolveOriginalHopId(planHopId);
			boolean fromClone = (hopID != planHopId);
			Hop targetHop = memoTable.resolveOriginalHop(planHopId);
			if (targetHop == null)
				targetHop = optimalPlan.getHopRef();
			ExecType execType = optimalPlan.getExecType();
			FEDInstruction.FederatedOutput thisOutType = optimalPlan.getFedOutType();

			if (execType == null) {
				throw new DMLRuntimeException("ExecType is null in FedPlan for hop " + hopID + " / "
						+ optimalPlan.getHopRef().getOpString());
			}

			Pair<FEDInstruction.FederatedOutput, ExecType> prev = visited.get(hopID);
			boolean prevFromClone = visitedFromClone != null && Boolean.TRUE.equals(visitedFromClone.get(hopID));
			ExecType resolvedExecType = execType;
			FEDInstruction.FederatedOutput resolvedOutType = thisOutType;

			if (prev != null) {
				if (prevFromClone && !fromClone) {
					resolvedExecType = prev.getRight();
					resolvedOutType = prev.getLeft();
				} else if (!prevFromClone && fromClone) {
					// Prefer clone decisions (Iter1) over originals (Iter0)
					resolvedExecType = execType;
					resolvedOutType = thisOutType;
				} else if (prev.getLeft() == thisOutType) {
					if (prev.getRight() != execType) {
						FederatedPlannerLogger.logWarnMessage(
								"[FederatedPlannerDpFedCostBased] ExecType conflict in rewriteHop for hop "
										+ hopID + " (" + optimalPlan.getHopRef().getOpString() + "): existing="
										+ prev.getRight() + ", incoming=" + execType + ", chosen="
										+ pickExecType(prev.getRight(), execType));
					}
					resolvedExecType = pickExecType(prev.getRight(), execType);
				} else {
					resolvedExecType = pickExecType(prev.getRight(), execType);
					FederatedPlannerLogger.logPlacementConflict(optimalPlan.getHopRef(), null,
							prev.getLeft(), thisOutType, "REWRITE_HOP");
				}
			} else {
				visited.put(hopID, Pair.of(thisOutType, execType));
			}

		for (Pair<Long, FEDInstruction.FederatedOutput> childFedPlanPair : optimalPlan.getChildFedPlans()) {
			FederatedPlannerDpMemoTable.FedPlan childPlan = memoTable.getFedPlanAfterPrune(childFedPlanPair);

			// Todo: Remove later
			// DEBUG: Check if getFedPlanAfterPrune returns null
			if (childPlan == null) {
				FederatedPlannerLogger.logNullChildPlanDebug(childFedPlanPair, optimalPlan, memoTable);
				continue;
			}

				rewriteHop(childPlan, memoTable, visited, visitedFromClone, visitedPlanHops, fTypeMap);
			}

			optimalPlan.setForcedExecType(resolvedExecType);
			if (targetHop != optimalPlan.getHopRef())
				targetHop.setForcedExecType(resolvedExecType);

			// Keep resolvedOutType as-is; Iter1 preference already applied when clones exist.
			optimalPlan.setFederatedOutput(resolvedOutType);
			if (targetHop != optimalPlan.getHopRef())
				targetHop.setFederatedOutput(resolvedOutType);
			visited.put(hopID, Pair.of(resolvedOutType, resolvedExecType));
			if (visitedFromClone != null) {
				visitedFromClone.put(hopID, prevFromClone || fromClone);
			}
			if (resolvedOutType == FederatedOutput.FOUT) {
				FType fType = optimalPlan.getFType();
				if (fType != null) {
					fTypeMap.put(hopID, fType);
				}
			} else {
				fTypeMap.remove(hopID);
			}

		}

	private static ExecType pickExecType(ExecType existing, ExecType incoming) {
		if (existing == null) {
			return incoming;
		}
		if (incoming == null) {
			return existing;
		}
		if (existing == incoming) {
			return existing;
		}

		int existingPriority = execTypePriority(existing);
		int incomingPriority = execTypePriority(incoming);

		return incomingPriority < existingPriority ? incoming : existing;
	}

	private static int execTypePriority(ExecType execType) {
		switch (execType) {
			case FED:
				return 0;
			case CP:
				return 1;
			case CP_FILE:
				return 2;
			case GPU:
				return 3;
			case SPARK:
				return 4;
			case OOC:
				return 5;
			default:
				return Integer.MAX_VALUE;
		}
	}

}
