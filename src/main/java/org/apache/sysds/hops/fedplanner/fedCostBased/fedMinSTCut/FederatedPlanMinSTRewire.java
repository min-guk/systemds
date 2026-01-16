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

package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.ExecPlacementCaps;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
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
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.*;
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
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.jgrapht.Graph;
import org.jgrapht.alg.flow.PushRelabelMFImpl;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

public class FederatedPlanMinSTRewire {
	public static final String FED_MATRIX_IDENTIFIER = "matrix";
	public static final String FED_FRAME_IDENTIFIER = "frame";

	public static void rewireProgram(DMLProgram prog, Map<Long, List<Hop>> rewireTable,
			FederatedPlanMinSTGraph graph, List<Pair<FederatedRange, FederatedData>> fedMap,
			Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet,
			OracleFacade oracleFacade) {
		// Maps HopID -> Privacy constraint
		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
		Set<Long> visitedHops = new HashSet<>();
		Set<String> fnStack = new HashSet<>();
		Set<Long> injectedIds = new HashSet<>();
		Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
		List<Pair<Long, Double>> loopStack = new ArrayList<>();

		List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);

			for (StatementBlock sb : prog.getStatementBlocks()) {
				Map<String, List<Hop>> innerTransTable = rewireStatementBlock(sb, prog, visitedHops, rewireTable,
						graph, outerTransTableList, null, privacyConstraintMap, fTypeMap,
						fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds, functionTransTableCache,
						1, 1, loopStack, oracleFacade);
				outerTransTableList.get(0).putAll(innerTransTable);
			}
	}

	public static void rewireFunctionDynamic(FunctionStatementBlock function, DMLProgram prog,
			Map<Long, List<Hop>> rewireTable,
			FederatedPlanMinSTGraph graph, List<Pair<FederatedRange, FederatedData>> fedMap,
			Set<Long> unRefTwriteSet, Set<Long> unRefSet, Set<Hop> progRootHopSet, OracleFacade oracleFacade) {
		Map<Long, Privacy> privacyConstraintMap = new HashMap<>();
			Map<Long, FType> fTypeMap = new HashMap<>();
			Set<Long> visitedHops = new HashSet<>();
			Set<String> fnStack = new HashSet<>();
			Set<Long> injectedIds = new HashSet<>();
			Map<String, Map<String, List<Hop>>> functionTransTableCache = new HashMap<>();
			List<Pair<Long, Double>> loopStack = new ArrayList<>();
			List<Map<String, List<Hop>>> outerTransTableList = new ArrayList<>();
		Map<String, List<Hop>> outerTransTable = new HashMap<>();
		outerTransTableList.add(outerTransTable);
			// Todo (Future): not tested & not used
			rewireStatementBlock(function, prog, visitedHops, rewireTable, graph, outerTransTableList, null,
					privacyConstraintMap, fTypeMap,
					fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack, injectedIds, functionTransTableCache,
					1, 1, loopStack, oracleFacade);
		}

		public static Map<String, List<Hop>> rewireStatementBlock(StatementBlock sb, DMLProgram prog,
				Set<Long> visitedHops,
				Map<Long, List<Hop>> rewireTable, FederatedPlanMinSTGraph graph,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
				Set<Hop> progRootHopSet, Set<String> fnStack,
				Set<Long> injectedIds,
				Map<String, Map<String, List<Hop>>> functionTransTableCache,
				double computeWeight, double networkWeight, List<Pair<Long, Double>> parentLoopStack,
				OracleFacade oracleFacade) {
		List<Map<String, List<Hop>>> newOuterTransTableList = new ArrayList<>();
		if (outerTransTableList != null) {
			for (Map<String, List<Hop>> outerTable : outerTransTableList) {
				if (outerTable != null && !outerTable.isEmpty()) {
					newOuterTransTableList.add(outerTable);
				}
			}
		}
		if (formerTransTable != null && !formerTransTable.isEmpty()) {
			newOuterTransTableList.add(formerTransTable);
		}

		Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
		Map<String, List<Hop>> innerTransTable = new HashMap<>();

		if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);

				rewireHopDAG(isb.getPredicateHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, parentLoopStack, oracleFacade);

			newFormerTransTable.putAll(innerTransTable);
			Map<String, List<Hop>> elseFormerTransTable = new HashMap<>();
			elseFormerTransTable.putAll(innerTransTable);
			computeWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;
			// Todo: network weight을 0.5로 안하는 이유가 있나? 잘 모르겠음. 고민해봐야함.
			// networkWeight *= RewireConstants.DEFAULT_IF_ELSE_WEIGHT;

			for (StatementBlock innerIsb : istmt.getIfBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, parentLoopStack, oracleFacade));

			for (StatementBlock innerIsb : istmt.getElseBody())
					elseFormerTransTable.putAll(rewireStatementBlock(innerIsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, elseFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, parentLoopStack, oracleFacade));

			// If there are common keys: merge elseValue list into ifValue list
			elseFormerTransTable.forEach((key, elseValue) -> {
				newFormerTransTable.merge(key, elseValue, (ifValue, newValue) -> {
					ifValue.addAll(newValue);
					return ifValue;
				});
			});
		} else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);

			// Calculate for-loop iteration count if possible
			double loopWeight = RewireConstants.DEFAULT_LOOP_WEIGHT;
			Hop from = fsb.getFromHops().getInput().get(0);
			Hop to = fsb.getToHops().getInput().get(0);
			Hop incr = (fsb.getIncrementHops() != null) ? fsb.getIncrementHops().getInput().get(0)
					: new LiteralOp(1);

			// Calculate for-loop iteration count (weight) if from, to, and incr are literal
			// ops (constant values)
			if (from instanceof LiteralOp && to instanceof LiteralOp && incr instanceof LiteralOp) {
				double dfrom = HopRewriteUtils.getDoubleValue((LiteralOp) from);
				double dto = HopRewriteUtils.getDoubleValue((LiteralOp) to);
				double dincr = HopRewriteUtils.getDoubleValue((LiteralOp) incr);
				if (dfrom > dto && dincr == 1)
					dincr = -1;
				loopWeight = UtilFunctions.getSeqLength(dfrom, dto, dincr, false);
			}
			computeWeight *= loopWeight;
			networkWeight *= loopWeight;

			// Create current loop context (copy parent context)
			List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
			currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(fsb.getFromHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, currentLoopStack, oracleFacade);
				rewireHopDAG(fsb.getToHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList, null,
						innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, currentLoopStack, oracleFacade);

			if (fsb.getIncrementHops() != null) {
					rewireHopDAG(fsb.getIncrementHops(), prog, visitedHops, rewireTable, graph,
							newOuterTransTableList, null, innerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, currentLoopStack, oracleFacade);
			}
			newFormerTransTable.putAll(innerTransTable);

			for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, currentLoopStack, oracleFacade));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap,
						injectedIds);
		} else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);

			double loopWeight = RewireConstants.estimateWhileLoopWeight(wsb);
			computeWeight *= loopWeight;
			networkWeight *= loopWeight;

			// Create current loop context (copy parent context)
			List<Pair<Long, Double>> currentLoopStack = new ArrayList<>(parentLoopStack);
			currentLoopStack.add(Pair.of(sb.getSBID(), loopWeight));

				rewireHopDAG(wsb.getPredicateHops(), prog, visitedHops, rewireTable, graph, newOuterTransTableList,
						null, innerTransTable,
						privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
						injectedIds, functionTransTableCache, computeWeight,
						networkWeight, currentLoopStack, oracleFacade);
			newFormerTransTable.putAll(innerTransTable);

			for (StatementBlock innerWsb : wstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerWsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, currentLoopStack, oracleFacade));

				// Wire UnRefTwrite to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(wsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap,
						injectedIds);
		} else if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);

			for (StatementBlock innerFsb : fstmt.getBody())
					newFormerTransTable.putAll(rewireStatementBlock(innerFsb, prog, visitedHops, rewireTable,
							graph, newOuterTransTableList, newFormerTransTable,
							privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, fnStack,
							injectedIds, functionTransTableCache, computeWeight,
							networkWeight, parentLoopStack, oracleFacade));

				// Wire fcall operation to liveOutHops
				wireUnRefTwriteToLiveOutWithTracking(fsb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap,
						injectedIds);
		} else { // generic (last-level)
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops())
						rewireHopDAG(c, prog, visitedHops, rewireTable, graph, newOuterTransTableList, null,
								innerTransTable,
								privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet,
								fnStack, injectedIds, functionTransTableCache,
								computeWeight, networkWeight, parentLoopStack, oracleFacade);
			}

			return innerTransTable;
		}
		return newFormerTransTable;
	}

	private static void rewireHopDAG(Hop hop, DMLProgram prog, Set<Long> visitedHops,
			Map<Long, List<Hop>> rewireTable,
			FederatedPlanMinSTGraph graph, List<Map<String, List<Hop>>> outerTransTableList,
			Map<String, List<Hop>> formerTransTable, Map<String, List<Hop>> innerTransTable,
			Map<Long, Privacy> privacyConstraintMap, Map<Long, FType> fTypeMap,
			List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet, Set<Long> unRefSet,
			Set<Hop> progRootHopSet,
			Set<String> fnStack, Set<Long> injectedIds, Map<String, Map<String, List<Hop>>> functionTransTableCache,
			double computeWeight, double networkWeight, List<Pair<Long, Double>> loopStack,
			OracleFacade oracleFacade) {

		RewireDagWalker.Context ctx = new RewireDagWalker.Context(
				visitedHops, rewireTable, outerTransTableList, formerTransTable, innerTransTable, true);
		RewireDagWalker.walk(hop, ctx, new RewireDagWalker.Visitor() {
			@Override
			public void afterChildren(Hop hop, RewireDagWalker.Context ctx) {
				// Identify hops to connect to the root dummy node
				// Connect TWrite pred and u(print) to the root dummy node
				if (HopUtils.isPredTWrite(hop) || HopUtils.isPrintOrPWrite(hop)) {
					progRootHopSet.add(hop);
				} else if (!(hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
						&& hop.getParent().size() == 0) {
					unRefSet.add(hop.getHopID());
				}

				if (hop instanceof FunctionOp) {
					// maintain counters and investigate functions if not seen so far
					FunctionOp fop = (FunctionOp) hop;
					unRefTwriteSet.add(fop.getHopID());

					if (fop.getFunctionType() == FunctionType.DML) {
						String fkey = fop.getFunctionKey();
						FunctionStatementBlock fsb = (prog != null)
								? prog.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName())
								: null;
						Map<String, List<Hop>> functionTransTable = functionTransTableCache.get(fkey);
						boolean pushed = false;

						if (functionTransTable == null && !fnStack.contains(fkey)) {
							fnStack.add(fkey);
							pushed = true;
							try {
								if (prog == null) {
									FederatedPlannerLogger.logWarnMessage(
											"[FederatedMinSTRewire] Skipping nested function " + fkey
													+ " because DMLProgram is unavailable in dynamic rewiring");
								} else if (fsb == null) {
									FederatedPlannerLogger.logWarnMessage(
											"[FederatedMinSTRewire] Function " + fkey
													+ " not found in DMLProgram; skipping nested rewiring");
								} else {
									Map<String, List<Hop>> newFormerTransTable = new HashMap<>();
									if (formerTransTable != null) {
										newFormerTransTable.putAll(formerTransTable);
									}
									newFormerTransTable.putAll(innerTransTable);

									String[] inputArgs = fop.getInputVariableNames();
									List<Hop> inputHops = fop.getInput();

									// Only used outside of functionTransTable.
									TransTableRewireUtils.mapFunctionInputsToFormerTransTable(
											inputArgs, inputHops, rewireTable, newFormerTransTable);

										functionTransTable = rewireStatementBlock(fsb, prog, visitedHops,
												rewireTable, graph, outerTransTableList, newFormerTransTable,
												privacyConstraintMap, fTypeMap, fedMap, unRefTwriteSet, unRefSet,
												progRootHopSet, fnStack, injectedIds, functionTransTableCache,
												computeWeight, networkWeight, loopStack, oracleFacade);
									if (functionTransTable != null)
										functionTransTableCache.put(fkey, functionTransTable);
								}
							} finally {
								if (pushed) {
									fnStack.remove(fkey);
								}
							}
						}

						TransTableRewireUtils.mapFunctionOutputs(
								fop, fsb, functionTransTable, innerTransTable,
								outputHop -> unRefTwriteSet.add(outputHop.getHopID()));
					} else if (fop.getFunctionType() == FunctionType.MULTIRETURN_BUILTIN) {
						TransTableRewireUtils.mapFunctionOutputs(
								fop, null, null, innerTransTable,
								outputHop -> unRefTwriteSet.add(outputHop.getHopID()));
					}
				}

				double hopComputeWeight = computeWeight;
				double hopNetworkWeight = networkWeight;
				List<Pair<Long, Double>> hopLoopStack = loopStack;

				Vertex passThroughVertex = null;
				Hop sourceHop = TransTableRewireUtils.resolvePassThroughSourceHop(hop, ctx.rewireTable());
				if (sourceHop != null && sourceHop != hop) {
					passThroughVertex = graph.getVertex(sourceHop.getHopID());
				}
				if (passThroughVertex == null && TransTableRewireUtils.isPassThroughTWrite(hop)) {
					List<Hop> inputs = hop.getInput();
					if (inputs != null && !inputs.isEmpty()) {
						passThroughVertex = graph.getVertex(inputs.get(0).getHopID());
					}
				}
				if (passThroughVertex != null) {
					hopComputeWeight = passThroughVertex.getOpWeight();
					hopNetworkWeight = passThroughVertex.getNetworkWeight();
					hopLoopStack = passThroughVertex.getLoopContext();
				}

					Vertex vertex = rewireHop(hop, rewireTable, outerTransTableList, formerTransTable, innerTransTable,
							privacyConstraintMap,
							graph, fTypeMap, fedMap, unRefTwriteSet, injectedIds, oracleFacade);
				if (vertex != null) {
					vertex.setMetadata(hopComputeWeight, hopNetworkWeight, hopLoopStack);
					graph.addVertex(vertex);
				}
			}
		});
	}

		private static Vertex rewireHop(Hop hop, Map<Long, List<Hop>> rewireTable,
				List<Map<String, List<Hop>>> outerTransTableList, Map<String, List<Hop>> formerTransTable,
				Map<String, List<Hop>> innerTransTable, Map<Long, Privacy> privacyConstraintMap,
				FederatedPlanMinSTGraph graph, Map<Long, FType> fTypeMap,
				List<Pair<FederatedRange, FederatedData>> fedMap, Set<Long> unRefTwriteSet,
				Set<Long> injectedIds, OracleFacade oracleFacade) {

		Privacy privacy;
		FType fType = null;
		ExecPlacementCaps caps;

		if (hop instanceof DataOp) {
			DataOp dataOp = (DataOp) hop;
			Types.OpOpData opType = dataOp.getOp();
			String hopName = dataOp.getName();

			if (opType == Types.OpOpData.FEDERATED) {
				// 2) FEDERATED DataOp: privacy + partition metadata 기반 FType
				privacy = FederatedPlannerUtils.getFedWorkerMetaData(fedMap, dataOp);
				fType = FederatedTypePropagator.deriveFType(dataOp);
				FederatedPlannerLogger.logDataOpFTypeDebug(
						hop, fType, "FEDERATED", "Derived from partition ranges");
			} else if (opType == Types.OpOpData.TRANSIENTWRITE) {
				// 3) TWrite: 입력 Hop의 FType을 그대로 복사
				innerTransTable.computeIfAbsent(hopName, k -> new ArrayList<>()).add(hop);
				unRefTwriteSet.add(hop.getHopID());
				privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
				fType = fTypeMap.get(hop.getInput(0).getHopID());
				FederatedPlannerLogger.logDataOpFTypeDebug(
						hop, fType, "TRANSIENTWRITE",
						"Propagated from single input (HopID: " + hop.getInput(0).getHopID() + ")");
				} else if (opType == Types.OpOpData.TRANSIENTREAD) {
					// 4) TRead: mapped source hops로부터 privacy/FType/caps 전파
					List<Hop> childHops = TransTableRewireUtils.resolveTransReadChildren(
							hop.getHopID(), hopName, rewireTable,
							innerTransTable, formerTransTable, outerTransTableList);

					if (childHops == null || childHops.isEmpty()) {
						FederatedPlannerLogger.logTransReadRewireDebug(
								hopName, hop.getHopID(), childHops, true, "RewireTransHop");
						privacy = Privacy.PUBLIC;
						caps = buildExecPlacementCaps(hop, privacy, null, null);
						privacyConstraintMap.put(hop.getHopID(), privacy);
						fTypeMap.put(hop.getHopID(), null);
						return new Vertex(hop, privacy, null, caps);
					}

					List<Hop> filteredChildHops = TransTableRewireUtils.filterTransReadChildren(
							hopName, childHops, injectedIds, true, false);

					FederatedPlannerLogger.logRewireHierarchy(
							hop, childHops, filteredChildHops, "RewireTransHop");

					if (filteredChildHops.isEmpty()) {
						rewireTable.remove(hop.getHopID());
						FederatedPlannerLogger.logFilteredChildHopsDebug(
								hopName, hop.getHopID(), filteredChildHops, true, "RewireTransHop");
						privacy = Privacy.PUBLIC;
						caps = buildExecPlacementCaps(hop, privacy, null, null);
						privacyConstraintMap.put(hop.getHopID(), privacy);
						fTypeMap.put(hop.getHopID(), null);
						return new Vertex(hop, privacy, null, caps);
					}

					TransTableRewireUtils.registerTransReadMapping(hop.getHopID(), filteredChildHops, rewireTable);
					TransTableRewireUtils.registerTransWriteLinks(
							hop, filteredChildHops, rewireTable, unRefTwriteSet);

					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, filteredChildHops, privacyConstraintMap);

					FType resolvedFType = null;
					ExecPlacementCaps resolvedCaps = null;
					Long transientWriteHopId = null;
					for (Hop childHop : filteredChildHops) {
						if (childHop == null) {
							continue;
						}
						if (resolvedFType == null) {
							resolvedFType = fTypeMap.get(childHop.getHopID());
						}
						if (resolvedCaps == null) {
							Vertex childVertex = graph.getVertex(childHop.getHopID());
							if (childVertex != null && childVertex.getCaps() != null) {
								resolvedCaps = new ExecPlacementCaps(childVertex.getCaps());
							}
						}
						if (transientWriteHopId == null && childHop instanceof DataOp
								&& ((DataOp) childHop).getOp() == Types.OpOpData.TRANSIENTWRITE) {
							transientWriteHopId = childHop.getHopID();
						}
						if (resolvedFType != null && resolvedCaps != null && transientWriteHopId != null) {
							break;
						}
					}
					fType = resolvedFType;
					if (resolvedCaps != null) {
						caps = resolvedCaps;
					} else {
						OpCaps policyCaps = OpCaps.allow(ExecType.FED, FederatedOutput.FOUT).build();
						caps = buildExecPlacementCaps(hop, privacy, fType, policyCaps);
					}

					privacyConstraintMap.put(hop.getHopID(), privacy);
					fTypeMap.put(hop.getHopID(), fType);

					Vertex v = new Vertex(hop, privacy, fType, caps);
					v.setTransientWriteHopId(transientWriteHopId);
					return v;
				} else {
					// 5) 기타 DataOp (PREAD, PWRITE 등): privacy만, FType은 Oracle에 맡김
					privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
				}
		} else {
			privacy = FederatedPlannerUtils.getPrivacyConstraint(hop, hop.getInput(), privacyConstraintMap);
		}

		// ==== 여기서부터는 모든 Hop(비 DataOp + DataOp 공통) 처리 ====

		// 자식 FType들에서 alignedFTypes 구성
		List<Hop> collectedHops = hop.getInput() == null ? Collections.emptyList() : hop.getInput();
		List<FType> collectedFTypes = new ArrayList<>();
		List<Hop> collectedHopList = new ArrayList<>();
		for (Hop input : collectedHops) {
			collectedHopList.add(input);
			collectedFTypes.add(fTypeMap.get(input.getHopID()));
		}

		OracleUtils.OracleDecision oracleDecision = OracleUtils.decideWithOracle(
				hop, privacy, collectedHopList, collectedFTypes,
				oracleFacade, null, rewireTable);
		OpCaps opCaps = oracleDecision.caps();

		// Oracle foutFType을 FType으로 반영 (getFederatedType 대체)
		FType oracleFType = oracleDecision.logicalFType();
		if (oracleFType != null) {
			// FEDERATED DataOp는 partition 기반 FType과 충돌할 수 있으니, 필요하면 로깅
			if (fType != null && !fType.equals(oracleFType)) {
				FederatedPlannerLogger.logInfoMessage(
						"[MinST] Oracle foutFType " + oracleFType + " overrides existing FType "
								+ fType + " for hop " + hop.getHopID() + " (" + hop.getOpString() + ")");
			}
			fType = oracleFType;
		}

		// Exec/Placement capability 결정
		caps = buildExecPlacementCaps(hop, privacy, fType, opCaps);

		// 최종 privacy/FType 저장
		privacyConstraintMap.put(hop.getHopID(), privacy);
		fTypeMap.put(hop.getHopID(), fType);

		return new Vertex(hop, privacy, fType, caps);
	}

	private static ExecPlacementCaps buildExecPlacementCaps(Hop hop, Privacy privacy, FType fType, OpCaps capsOracle) {
		ExecPlacementCaps caps = new ExecPlacementCaps();

		// 0) 처음엔 전부 false로 시작 (DP가 실제로 생성하는 조합만 켜기 위함)
		caps.allowCP_LOUT = false;
		caps.allowCP_FOUT = false;
		caps.allowFED_LOUT = false;
		caps.allowFED_FOUT = false;

		ExecPlacementPolicy.Decision policyDecision = ExecPlacementPolicy.decide(
				hop, privacy, fType, capsOracle);
		if (!policyDecision.hasAny()) {
			throw new DMLRuntimeException("Unsupported privacy level " + privacy
					+ " for hop " + hop.getHopID() + " (" + hop.getOpString() + ")");
		}

		caps.allowCP_LOUT = policyDecision.allowCP_LOUT;
		caps.allowCP_FOUT = policyDecision.allowCP_FOUT;
		caps.allowFED_LOUT = policyDecision.allowFED_LOUT;
		caps.allowFED_FOUT = policyDecision.allowFED_FOUT;

		if (!caps.hasAny()) {
			throw new DMLRuntimeException("No legal Exec/Placement combination for hop "
					+ hop.getHopID() + " (" + hop.getOpString() + ")");
		}
		return caps;
	}

	private static void wireUnRefTwriteToLiveOutWithTracking(StatementBlock sb, Set<Long> unRefTwriteSet,
			FederatedPlanMinSTGraph graph, Map<String, List<Hop>> newFormerTransTable,
			Map<Long, FType> fTypeMap, Set<Long> injectedIds) {
		if (injectedIds == null) {
			wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
			return;
		}
		Set<Long> before = new HashSet<>(unRefTwriteSet);
		wireUnRefTwriteToLiveOut(sb, unRefTwriteSet, graph, newFormerTransTable, fTypeMap);
		for (Long hopId : before) {
			if (!unRefTwriteSet.contains(hopId)) {
				injectedIds.add(hopId);
			}
		}
	}

	private static void wireUnRefTwriteToLiveOut(StatementBlock sb, Set<Long> unRefTwriteSet,
			FederatedPlanMinSTGraph graph,
			Map<String, List<Hop>> newFormerTransTable,
			Map<Long, FType> fTypeMap) {

		Function<Long, Hop> hopLookup = id -> {
			FederatedPlanMinSTGraph.Vertex v = graph.getVertex(id);
			return (v != null) ? v.getHopRef() : null;
		};

		FederatedPlannerUtils.wireUnRefTwriteToLiveOutCommon(
				sb,
				unRefTwriteSet,
				hopLookup,
				newFormerTransTable,
				// compatFn: unRefTwriteHop vs 대표 liveOutHop
					(unRefTwriteHop, liveOutHop) -> TransTableRewireUtils.calculateCompatibilityScore(
							unRefTwriteHop, liveOutHop, hopLookup),
					"[MinST]");
	}
}
