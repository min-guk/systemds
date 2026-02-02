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

package org.apache.sysds.hops.fedplanner.fedAll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedTypePropagator;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.HopUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
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
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Single-pass planner that maximizes FED/FOUT without DP enumeration.
 */
public class FederatedPlannerFedAllMaxFedFoutSinglePass extends AFederatedPlanner {
	private static final int NO_UNROLL = 0;

	private static final class HopPlan {
		private ExecType execType;
		private FederatedOutput output;
		private FederatedOutput logicalOutput;
		private FType fType;

		private HopPlan(ExecType execType, FederatedOutput output,
			FederatedOutput logicalOutput, FType fType) {
			this.execType = execType;
			this.output = output;
			this.logicalOutput = logicalOutput;
			this.fType = fType;
		}

		private boolean isLogicalFout() {
			return logicalOutput == FederatedOutput.FOUT;
		}

		private boolean needsMaterialize() {
			return output == FederatedOutput.LOUT && logicalOutput == FederatedOutput.FOUT;
		}
	}

	private static final class MaterializeRequest {
		private final Hop hop;
		private final long sbId;

		private MaterializeRequest(Hop hop, long sbId) {
			this.hop = hop;
			this.sbId = sbId;
		}
	}

	private final OracleFacade _oracle;
	private final Map<Long, Map<List<FType>, OpCaps>> _oracleCache = new HashMap<>();
	private final FederatedTypePropagator _typePropagator = new FederatedTypePropagator();

	private Map<Long, HopPlan> _planMap;
	private Map<Long, FType> _fTypeMap;
	private Map<Long, FType> _fTypeHints;
	private Map<Long, List<Hop>> _rewireTable;
	private Map<Long, MaterializeRequest> _materializeRequests;
	private Map<Long, Long> _hopSbIds;
	private Map<String, FType> _fedInitVars;
	private int _numWorkers;
	private DMLProgram _program;

	public FederatedPlannerFedAllMaxFedFoutSinglePass() {
		RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
		_oracle = new OracleFacade(registry);
	}

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		resetState();
		_program = prog;
		buildRewireTable(prog);

		Map<String, FType> fedVars = new HashMap<>();
		for (StatementBlock sb : prog.getStatementBlocks()) {
			planStatementBlock(sb, fedVars, true, new HashSet<>());
		}

		FederatedRefedPolicy.registerFromProgram(prog, _fTypeMap);
		registerMaterializeRequests();
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		resetState();
		_program = function.getDMLProg();
		buildRewireTable(function);

		Map<String, FType> fedVars = new HashMap<>();
		for (Map.Entry<String, Data> varName : funcArgs.entrySet()) {
			Data data = varName.getValue();
			FType fType = null;
			if (data instanceof CacheableData<?> && ((CacheableData<?>) data).isFederated()) {
				fType = ((CacheableData<?>) data).getFedMapping().getType();
			}
			fedVars.put(varName.getKey(), fType);
		}

		planStatementBlock(function, fedVars, false, new HashSet<>());
		FederatedRefedPolicy.registerFromFunction(function, _fTypeMap);
		registerMaterializeRequests();
	}

	private void resetState() {
		_oracleCache.clear();
		_planMap = new HashMap<>();
		_fTypeMap = new HashMap<>();
		_fTypeHints = new HashMap<>();
		_rewireTable = new HashMap<>();
		_materializeRequests = new HashMap<>();
		_hopSbIds = new HashMap<>();
		_fedInitVars = new HashMap<>();
		_numWorkers = 0;
	}

	private void buildRewireTable(DMLProgram prog) {
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable = new HashMap<>();
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx =
			new FederatedPlannerDpRewireTransTable.UnrollContext();

		FederatedPlannerDpRewireTransTable.rewireProgram(prog, _rewireTable, hopCommonTable,
			privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, unrollCtx, NO_UNROLL);
	}

	private void buildRewireTable(FunctionStatementBlock function) {
		Map<Long, FederatedPlannerDpMemoTable.HopCommon> hopCommonTable = new HashMap<>();
		Map<Long, org.apache.sysds.hops.fedplanner.FTypes.Privacy> privacyConstraintMap = new HashMap<>();
		List<Pair<FederatedRange, FederatedData>> fedMap = new ArrayList<>();
		Set<Long> unRefTwriteSet = new HashSet<>();
		Set<Long> unRefSet = new HashSet<>();
		Set<Hop> progRootHopSet = new HashSet<>();
		FederatedPlannerDpRewireTransTable.UnrollContext unrollCtx =
			new FederatedPlannerDpRewireTransTable.UnrollContext();

		FederatedPlannerDpRewireTransTable.rewireFunctionDynamic(function, function.getDMLProg(), _rewireTable,
			hopCommonTable, privacyConstraintMap, fedMap, unRefTwriteSet, unRefSet, progRootHopSet, unrollCtx, NO_UNROLL);
	}

	private void planStatementBlock(StatementBlock sb, Map<String, FType> fedVars, boolean allowCpFout,
			Set<String> fnStack) {
		if (sb == null)
			return;

		if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			for (StatementBlock csb : fstmt.getBody()) {
				planStatementBlock(csb, fedVars, allowCpFout, fnStack);
			}
		}
		else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			planHop(wsb.getPredicateHops(), fedVars, allowCpFout, sb.getSBID(), fnStack);
			for (StatementBlock csb : wstmt.getBody()) {
				planStatementBlock(csb, fedVars, allowCpFout, fnStack);
			}
		}
		else if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			planHop(isb.getPredicateHops(), fedVars, allowCpFout, sb.getSBID(), fnStack);
			for (StatementBlock csb : istmt.getIfBody()) {
				planStatementBlock(csb, fedVars, allowCpFout, fnStack);
			}
			for (StatementBlock csb : istmt.getElseBody()) {
				planStatementBlock(csb, fedVars, allowCpFout, fnStack);
			}
		}
		else if (sb instanceof ForStatementBlock) {
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			planHop(fsb.getFromHops(), fedVars, allowCpFout, sb.getSBID(), fnStack);
			planHop(fsb.getToHops(), fedVars, allowCpFout, sb.getSBID(), fnStack);
			planHop(fsb.getIncrementHops(), fedVars, allowCpFout, sb.getSBID(), fnStack);
			for (StatementBlock csb : fstmt.getBody()) {
				planStatementBlock(csb, fedVars, allowCpFout, fnStack);
			}
		}
		else {
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops()) {
					planHop(c, fedVars, allowCpFout, sb.getSBID(), fnStack);
				}
			}
			// propagate fedVars across DAGs using transient writes
			if (sb.getHops() != null) {
				for (Hop c : sb.getHops()) {
					if (HopRewriteUtils.isData(c, OpOpData.TRANSIENTWRITE)) {
						HopPlan plan = _planMap.get(c.getHopID());
						if (plan != null && plan.isLogicalFout() && plan.fType != null) {
							fedVars.put(c.getName(), plan.fType);
						}
						else {
							fedVars.remove(c.getName());
						}
					}
				}
			}
		}
	}

	private HopPlan planHop(Hop hop, Map<String, FType> fedVars, boolean allowCpFout, long sbId,
			Set<String> fnStack) {
		if (hop == null)
			return null;
		HopPlan cached = _planMap.get(hop.getHopID());
		if (cached != null)
			return cached;

		// Function calls are handled by planning their bodies and mapping outputs.
		if (hop instanceof FunctionOp && ((FunctionOp) hop).getFunctionType() == FunctionType.DML) {
			planFunctionOp((FunctionOp) hop, fedVars, allowCpFout, fnStack);
			HopPlan plan = new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, null);
			_planMap.put(hop.getHopID(), plan);
			_hopSbIds.putIfAbsent(hop.getHopID(), sbId);
			return plan;
		}

		// Process inputs first (including rewired TRead connections).
		for (Hop input : hop.getInput()) {
			planHop(input, fedVars, allowCpFout, sbId, fnStack);
		}
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.TRANSIENTREAD) {
			for (Hop linked : getConnectedTWriteHops((DataOp) hop)) {
				planHop(linked, fedVars, allowCpFout, sbId, fnStack);
			}
		}

		HopPlan plan;
		if (hop instanceof DataOp) {
			plan = planDataOp((DataOp) hop, fedVars, allowCpFout, sbId);
		}
		else {
			plan = planGenericOp(hop, fedVars, allowCpFout, sbId);
		}

		plan = finalizeFoutPlan(hop, plan);

		_planMap.put(hop.getHopID(), plan);
		_hopSbIds.putIfAbsent(hop.getHopID(), sbId);
		applyPlanToHop(hop, plan);
		if (plan != null && plan.fType != null) {
			_fTypeHints.put(hop.getHopID(), plan.fType);
		}
		else {
			_fTypeHints.remove(hop.getHopID());
		}
		if (plan != null && plan.isLogicalFout() && plan.fType != null) {
			_fTypeMap.put(hop.getHopID(), plan.fType);
		}
		else {
			_fTypeMap.remove(hop.getHopID());
		}
		if (plan != null && plan.needsMaterialize()) {
			recordMaterializeRequest(hop, sbId);
		}
		return plan;
	}

	private void planFunctionOp(FunctionOp fop, Map<String, FType> callFedVars,
			boolean allowCpFout, Set<String> fnStack) {
		String fkey = fop.getFunctionKey();
		if (!fnStack.add(fkey))
			return;
		try {
			FunctionStatementBlock fsb = (_program != null)
				? _program.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName())
				: null;
			if (fsb == null) {
				FederatedPlannerLogger.logWarnMessage(
					"[SinglePassPlanner] Function " + fkey + " not found; skipping nested planning");
				return;
			}
			FunctionStatement funcStatement = (FunctionStatement) fsb.getStatement(0);

			Map<String, FType> funcFedVars = createFunctionFedVarTable(fop);
			for (StatementBlock csb : funcStatement.getBody()) {
				planStatementBlock(csb, funcFedVars, allowCpFout, fnStack);
			}
			mapFunctionOutputs(fop, funcStatement, funcFedVars, callFedVars);
		}
		finally {
			fnStack.remove(fkey);
		}
	}

	private Map<String, FType> createFunctionFedVarTable(FunctionOp hop) {
		Map<String, Hop> funcParamMap = FederatedPlannerUtils.getParamMap(hop);
		Map<String, FType> funcFedVars = new HashMap<>();
		funcParamMap.forEach((key, value) -> {
			HopPlan plan = _planMap.get(value.getHopID());
			FType fType = (plan != null && plan.isLogicalFout()) ? plan.fType : null;
			funcFedVars.put(key, fType);
		});
		return funcFedVars;
	}

	private void mapFunctionOutputs(FunctionOp callHop, FunctionStatement funcStatement,
		Map<String, FType> funcFedVars, Map<String, FType> callFedVars) {
		String[] outVars = callHop.getOutputVariableNames();
		if (outVars == null)
			return;
		int limit = Math.min(outVars.length, funcStatement.getOutputParams().size());
		for (int i = 0; i < limit; i++) {
			FType outputFType = funcFedVars.get(funcStatement.getOutputParams().get(i).getName());
			if (outputFType != null)
				callFedVars.put(outVars[i], outputFType);
			else
				callFedVars.remove(outVars[i]);
		}
	}

	private HopPlan planDataOp(DataOp hop, Map<String, FType> fedVars, boolean allowCpFout, long sbId) {
		OpOpData opType = hop.getOp();
		if (opType == OpOpData.FEDERATED) {
			FType fType = FederatedPlannerUtils.deriveFedInitFType(hop);
			if (hop.getName() != null)
				_fedInitVars.put(hop.getName(), fType);
			_numWorkers = Math.max(_numWorkers, countFedInitWorkers(hop));
			HopPlan plan = new HopPlan(ExecType.FED, FederatedOutput.FOUT, FederatedOutput.FOUT, fType);
			return enforceFoutConstraints(hop, plan, sbId);
		}
		if (opType == OpOpData.TRANSIENTREAD) {
			return planTransientRead(hop, fedVars, sbId);
		}
		if (opType == OpOpData.TRANSIENTWRITE) {
			return planTransientWrite(hop);
		}

		// Default: CP LOUT for other DataOps.
		return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, null);
	}

	private HopPlan planTransientWrite(DataOp hop) {
		Hop input = hop.getInput().isEmpty() ? null : hop.getInput(0);
		HopPlan inputPlan = (input != null) ? _planMap.get(input.getHopID()) : null;
		ExecType inputExec = null;
		if (input != null)
			inputExec = input.getForcedExecType() != null ? input.getForcedExecType() : input.getExecType();
		boolean inputRuntimeFed = input != null && inputExec == ExecType.FED && !input.hasLocalOutput();
		if (inputRuntimeFed && inputPlan != null && inputPlan.isLogicalFout()
				&& inputPlan.fType != null && isFoutSupported(hop)) {
			return new HopPlan(ExecType.FED, FederatedOutput.FOUT, FederatedOutput.FOUT, inputPlan.fType);
		}
		if (inputPlan != null && inputPlan.isLogicalFout()) {
			traceDecision("TW_LOCALIZED", hop,
				"input=" + formatHopBrief(input)
					+ " inputExec=" + inputExec
					+ " inputRuntimeFed=" + inputRuntimeFed
					+ " inputPlan=" + formatPlan(inputPlan)
					+ " foutSupported=" + isFoutSupported(hop));
		}
		return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, null);
	}

	private HopPlan planTransientRead(DataOp hop, Map<String, FType> fedVars, long sbId) {
		List<Hop> connectedWrites = getConnectedTWriteHops(hop);
		if (!connectedWrites.isEmpty()) {
			FType commonFoutType = null;
			FType commonLoutType = null;
			boolean foutPossible = true;
			boolean loutPossible = true;

			for (Hop writeHop : connectedWrites) {
				HopPlan plan = _planMap.get(writeHop.getHopID());
				if (plan == null) {
					foutPossible = false;
					loutPossible = false;
					break;
				}
				if (plan.isLogicalFout()) {
					if (plan.fType == null) {
						foutPossible = false;
					}
					else if (commonFoutType == null) {
						commonFoutType = plan.fType;
					}
					else if (commonFoutType != plan.fType) {
						foutPossible = false;
					}
					loutPossible = false;
				}
				else {
					if (plan.fType != null) {
						if (commonLoutType == null)
							commonLoutType = plan.fType;
						else if (commonLoutType != plan.fType)
							loutPossible = false;
					}
					foutPossible = false;
				}
			}

			if (foutPossible && commonFoutType != null && isFoutSupported(hop)) {
				HopPlan plan = new HopPlan(ExecType.FED, FederatedOutput.FOUT, FederatedOutput.FOUT, commonFoutType);
				return enforceFoutConstraints(hop, plan, sbId);
			}
			if (loutPossible) {
				traceDecision("TR_LOUT", hop,
					"connectedWrites=" + formatWritePlans(connectedWrites)
						+ " foutPossible=" + foutPossible
						+ " loutPossible=" + loutPossible);
				return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, commonLoutType);
			}
			traceDecision("TR_CONFLICT", hop,
				"connectedWrites=" + formatWritePlans(connectedWrites)
					+ " foutPossible=" + foutPossible
					+ " loutPossible=" + loutPossible);
			throw new DMLRuntimeException("TRead placement conflict for hop " + hop.getHopID()
				+ " (" + hop.getName() + "): connected TWrite placements are inconsistent");
		}

		FType fallback = fedVars.get(hop.getName());
		if (fallback == null)
			fallback = _fedInitVars.get(hop.getName());
		if (fallback == null) {
			FederationMap anchorMap = FederationUtils.getAnchorMap(hop.getName());
			if (anchorMap != null)
				fallback = anchorMap.getType();
		}

		if (fallback != null && isFoutSupported(hop)) {
			HopPlan plan = new HopPlan(ExecType.FED, FederatedOutput.FOUT, FederatedOutput.FOUT, fallback);
			return enforceFoutConstraints(hop, plan, sbId);
		}
		traceDecision("TR_FALLBACK_NULL", hop,
			"fedVars=" + fedVars.get(hop.getName())
				+ " fedInit=" + _fedInitVars.get(hop.getName())
				+ " anchor=" + (FederationUtils.getAnchorMap(hop.getName()) != null));
		return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, null);
	}

	private HopPlan planGenericOp(Hop hop, Map<String, FType> fedVars, boolean allowCpFout, long sbId) {
		if (HopUtils.isPrintOrPWrite(hop)) {
			return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, null);
		}

		List<FType> inputFTypes = collectInputFTypes(hop, false, allowCpFout);
		OpCaps caps = getOracleCaps(hop, inputFTypes);

		boolean fedAllowed = false;
		boolean oracleFed = caps != null && caps.exec() == ExecType.FED;
		if (oracleFed) {
			forceFedForAnchorChecks(hop);
			fedAllowed = FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(hop, _fTypeMap);
		}
		List<Hop> upgradeTargets = Collections.emptyList();
		if (!fedAllowed) {
			forceFedForAnchorChecks(hop);
			List<FType> candidateFTypes = collectInputFTypes(hop, true, allowCpFout);
			OpCaps candidateCaps = getOracleCaps(hop, candidateFTypes);
			if (candidateCaps != null && candidateCaps.exec() == ExecType.FED) {
				upgradeTargets = findUpgradableInputs(hop, allowCpFout, sbId);
				performUpgrades(upgradeTargets, allowCpFout, sbId);
				if (FederatedRefedPolicy.canSatisfyFederatedInputsFromFTypes(hop, _fTypeMap)) {
					caps = candidateCaps;
					fedAllowed = true;
				}
			}
			if (!fedAllowed && oracleFed) {
				traceDecision("FED_BLOCKED", hop,
					"oracleExec=FED placement=" + caps.placement()
						+ " reason=" + caps.reason()
						+ " inputs=" + inputFTypes
						+ " plannedInputs=" + formatInputPlans(hop)
						+ " upgrades=" + formatHopIdList(upgradeTargets));
			}
		}

		if (fedAllowed) {
			return planFederatedOp(hop, caps, allowCpFout, sbId);
		}
		return planCpOp(hop, allowCpFout, sbId);
	}

	private HopPlan planFederatedOp(Hop hop, OpCaps caps, boolean allowCpFout, long sbId) {
		FType fType = inferFType(hop, caps);
		FederatedOutput placement = (caps != null) ? caps.placement() : FederatedOutput.LOUT;
		HopPlan plan;

		if (!isFoutSupported(hop)) {
			plan = new HopPlan(ExecType.FED, FederatedOutput.LOUT, FederatedOutput.LOUT, fType);
			return plan;
		}

		if (placement == FederatedOutput.FOUT && fType != null) {
			plan = new HopPlan(ExecType.FED, FederatedOutput.FOUT, FederatedOutput.FOUT, fType);
		}
		else if (fType != null && canMaterializeFout(hop)) {
			plan = new HopPlan(ExecType.FED, FederatedOutput.LOUT, FederatedOutput.FOUT, fType);
		}
		else {
			plan = new HopPlan(ExecType.FED, FederatedOutput.LOUT, FederatedOutput.LOUT, fType);
		}
		return plan;
	}

	private HopPlan planCpOp(Hop hop, boolean allowCpFout, long sbId) {
		if (!allowCpFout || !isFoutSupported(hop)) {
			return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, null);
		}
		FType fType = inferFType(hop, null);
		FType adjusted = OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(hop, fType, _rewireTable, _numWorkers);
		if (adjusted != null && canMaterializeFout(hop)) {
			return new HopPlan(ExecType.CP, FederatedOutput.FOUT, FederatedOutput.FOUT, adjusted);
		}
		return new HopPlan(ExecType.CP, FederatedOutput.LOUT, FederatedOutput.LOUT, adjusted);
	}

	private HopPlan enforceFoutConstraints(Hop hop, HopPlan plan, long sbId) {
		if (!plan.isLogicalFout())
			return plan;
		if (!isFoutSupported(hop) || plan.fType == null) {
			return new HopPlan(plan.execType, FederatedOutput.LOUT, FederatedOutput.LOUT, plan.fType);
		}
		if (plan.needsMaterialize() && !canMaterializeFout(hop)) {
			return new HopPlan(plan.execType, FederatedOutput.LOUT, FederatedOutput.LOUT, plan.fType);
		}
		return plan;
	}

	private HopPlan finalizeFoutPlan(Hop hop, HopPlan plan) {
		if (plan == null)
			return null;
		if (plan.isLogicalFout()) {
			if (!canConfirmLogicalFout(hop, plan)) {
				traceDecision("FOUT_DROP", hop,
					"plan=" + formatPlan(plan)
						+ " foutSupported=" + isFoutSupported(hop)
						+ " canMaterialize=" + canMaterializeFout(hop));
				plan.output = FederatedOutput.LOUT;
				plan.logicalOutput = FederatedOutput.LOUT;
			}
		}
		return plan;
	}

	private boolean canConfirmLogicalFout(Hop hop, HopPlan plan) {
		if (plan == null || !plan.isLogicalFout())
			return false;
		if (plan.fType == null || !isFoutSupported(hop))
			return false;
		if (plan.execType == ExecType.FED && plan.output == FederatedOutput.FOUT)
			return true;
		return canMaterializeFout(hop);
	}

	private void traceDecision(String tag, Hop hop, String detail) {
		if (hop == null)
			return;
		System.out.println("[SinglePassTrace] " + tag
			+ " hop=" + hop.getHopID()
			+ " op=" + hop.getOpString()
			+ (detail == null || detail.isEmpty() ? "" : " " + detail));
	}

	private String formatPlan(HopPlan plan) {
		if (plan == null)
			return "null";
		return "exec=" + plan.execType
			+ " out=" + plan.output
			+ " logical=" + plan.logicalOutput
			+ " fType=" + plan.fType;
	}

	private String formatHopBrief(Hop hop) {
		if (hop == null)
			return "null";
		return hop.getHopID() + "(" + hop.getOpString() + ")";
	}

	private String formatInputPlans(Hop hop) {
		if (hop == null || hop.getInput() == null || hop.getInput().isEmpty())
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < hop.getInput().size(); i++) {
			Hop input = hop.getInput().get(i);
			if (input == null)
				continue;
			HopPlan plan = _planMap.get(input.getHopID());
			if (i > 0)
				sb.append("; ");
			sb.append(formatHopBrief(input))
				.append(" plan=").append(formatPlan(plan))
				.append(" plannedFType=").append(_fTypeMap.get(input.getHopID()))
				.append(" canMat=").append(canMaterializeFout(input));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatWritePlans(List<Hop> writes) {
		if (writes == null || writes.isEmpty())
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < writes.size(); i++) {
			Hop write = writes.get(i);
			HopPlan plan = _planMap.get(write.getHopID());
			if (i > 0)
				sb.append("; ");
			sb.append(formatHopBrief(write)).append(" plan=").append(formatPlan(plan));
		}
		sb.append("]");
		return sb.toString();
	}

	private String formatHopIdList(List<Hop> hops) {
		if (hops == null || hops.isEmpty())
			return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < hops.size(); i++) {
			if (i > 0)
				sb.append(",");
			sb.append(hops.get(i).getHopID());
		}
		sb.append("]");
		return sb.toString();
	}

	private List<FType> collectInputFTypes(Hop hop, boolean allowUpgrades, boolean allowCpFout) {
		if (hop == null || hop.getInput() == null || hop.getInput().isEmpty())
			return Collections.emptyList();
		List<FType> types = new ArrayList<>(hop.getInput().size());
		for (Hop input : hop.getInput()) {
			HopPlan plan = _planMap.get(input.getHopID());
			if (plan != null && plan.isLogicalFout()) {
				types.add(plan.fType);
			}
			else if (allowUpgrades && canUpgradeInput(input, plan, allowCpFout)) {
				types.add(plan != null ? plan.fType : null);
			}
			else {
				types.add(null);
			}
		}
		return types;
	}

	private List<Hop> findUpgradableInputs(Hop hop, boolean allowCpFout, long sbId) {
		if (hop == null || hop.getInput() == null || hop.getInput().isEmpty())
			return Collections.emptyList();
		List<Hop> targets = new ArrayList<>();
		for (Hop input : hop.getInput()) {
			HopPlan plan = _planMap.get(input.getHopID());
			if (canUpgradeInput(input, plan, allowCpFout))
				targets.add(input);
		}
		return targets;
	}

	private void performUpgrades(List<Hop> upgradeTargets, boolean allowCpFout, long sbId) {
		if (upgradeTargets == null || upgradeTargets.isEmpty())
			return;
		for (Hop input : upgradeTargets) {
			HopPlan plan = _planMap.get(input.getHopID());
			if (plan == null || plan.isLogicalFout())
				continue;
			if (!canUpgradeInput(input, plan, allowCpFout))
				continue;
			if (plan.execType == ExecType.CP) {
				if (!allowCpFout)
					continue;
				plan.output = FederatedOutput.FOUT;
				plan.logicalOutput = FederatedOutput.FOUT;
			}
			else if (plan.execType == ExecType.FED) {
				plan.logicalOutput = FederatedOutput.FOUT;
			}
			plan = finalizeFoutPlan(input, plan);
			applyPlanToHop(input, plan);
			if (plan != null && plan.isLogicalFout() && plan.fType != null) {
				_fTypeMap.put(input.getHopID(), plan.fType);
			}
			else {
				_fTypeMap.remove(input.getHopID());
			}
			if (plan.needsMaterialize()) {
				long inputSbId = _hopSbIds.getOrDefault(input.getHopID(), sbId);
				recordMaterializeRequest(input, inputSbId);
			}
		}
	}

	private boolean canUpgradeInput(Hop input, HopPlan plan, boolean allowCpFout) {
		if (input == null || plan == null || plan.fType == null)
			return false;
		if (!isFoutSupported(input))
			return false;
		if (!canMaterializeFout(input))
			return false;
		if (plan.execType == ExecType.CP)
			return allowCpFout;
		return plan.execType == ExecType.FED;
	}

	private List<Hop> getConnectedTWriteHops(DataOp hop) {
		if (hop == null || _rewireTable == null)
			return Collections.emptyList();
		List<Hop> linked = _rewireTable.get(hop.getHopID());
		if (linked == null || linked.isEmpty())
			return Collections.emptyList();
		List<Hop> result = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (Hop child : linked) {
			if (!(child instanceof DataOp))
				continue;
			DataOp dataChild = (DataOp) child;
			if (dataChild.getOp() != OpOpData.TRANSIENTWRITE)
				continue;
			if (seen.add(dataChild.getHopID()))
				result.add(dataChild);
		}
		return result;
	}

	private boolean isFoutSupported(Hop hop) {
		return hop != null && hop.getDataType() != null
			&& hop.getDataType().isMatrix() && !hop.getDataType().isFrame();
	}

	private FType inferFType(Hop hop, OpCaps caps) {
		if (hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED) {
			return FederatedPlannerUtils.deriveFedInitFType((DataOp) hop);
		}
		if (caps != null && caps.foutFType().isPresent()) {
			return caps.foutFType().get();
		}
		return _typePropagator.getFederatedTypeDebug(hop, _fTypeHints);
	}

	private boolean canMaterializeFout(Hop hop) {
		return hop != null && FederatedRefedPolicy.canGenerateCpfoutCandidate(hop, _fTypeMap);
	}

	private void forceFedForAnchorChecks(Hop hop) {
		if (hop == null)
			return;
		if (hop.getForcedExecType() != ExecType.FED)
			hop.setForcedExecType(ExecType.FED);
	}

	private static int countFedInitWorkers(DataOp fedInit) {
		if (fedInit == null || fedInit.getOp() != OpOpData.FEDERATED)
			return 0;
		int addrIx = fedInit.getParameterIndex(org.apache.sysds.parser.DataExpression.FED_ADDRESSES);
		if (addrIx < 0)
			return 0;
		Hop addrHop = fedInit.getInput(addrIx);
		if (addrHop == null || addrHop.getInput() == null)
			return 0;
		return addrHop.getInput().size();
	}

	private OpCaps getOracleCaps(Hop hop, List<FType> inputFTypes) {
		if (hop == null)
			return null;
		List<FType> key = (inputFTypes == null) ? Collections.emptyList() : new ArrayList<>(inputFTypes);
		Map<List<FType>, OpCaps> byInputs =
			_oracleCache.computeIfAbsent(hop.getHopID(), k -> new HashMap<>());
		OpCaps cached = byInputs.get(key);
		if (cached != null)
			return cached;
		OpCaps caps = _oracle.decide(hop, key);
		byInputs.put(key, caps);
		if (caps != null) {
			FederatedPlannerLogger.logOracleDecision(hop, null, key, caps, _rewireTable);
		}
		return caps;
	}

	private void applyPlanToHop(Hop hop, HopPlan plan) {
		if (hop == null || plan == null)
			return;
		hop.setForcedExecType(plan.execType);
		hop.setFederatedOutput(plan.output);
	}

	private void recordMaterializeRequest(Hop hop, long sbId) {
		if (hop == null)
			return;
		_materializeRequests.putIfAbsent(hop.getHopID(), new MaterializeRequest(hop, sbId));
	}

	private void registerMaterializeRequests() {
		if (_materializeRequests == null || _materializeRequests.isEmpty())
			return;
		for (MaterializeRequest req : _materializeRequests.values()) {
			FederatedRefedPolicy.registerFoutMaterializeCandidate(req.hop, _fTypeMap, req.sbId);
		}
	}
}
