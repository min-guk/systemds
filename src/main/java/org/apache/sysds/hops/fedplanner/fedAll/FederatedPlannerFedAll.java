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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.OracleUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.RuleRegistry;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.parser.DataExpression;
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
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;
import org.apache.sysds.runtime.controlprogram.caching.CacheableData;
import org.apache.sysds.runtime.instructions.cp.Data;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Baseline federated planner that compiles all hops
 * that support federated execution on federated inputs to
 * forced federated operations.
 */
public class FederatedPlannerFedAll extends AFederatedPlanner {
	private static final int MAX_LOOP_REWRITE_PASSES = 4;

	private final OracleFacade _oracle;
	private final Map<Long, Map<List<FType>, OpCaps>> _oracleCache = new HashMap<>();
	private int _numWorkers = 0;

	public FederatedPlannerFedAll() {
		RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
		_oracle = new OracleFacade(registry);
	}
	
	@Override
	public void rewriteProgram( DMLProgram prog,
		FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes )
	{
		FederatedPlannerUtils.clearFedInitVars();
		_oracleCache.clear();
		_numWorkers = 0;
		// handle main program
		Map<String, FType> fedVars = new HashMap<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
		Map<Long, List<Hop>> globalRewireTable = buildGlobalTransientRewireTable(prog);
		for(StatementBlock sb : prog.getStatementBlocks())
			rRewriteStatementBlock(sb, fedVars, fTypeMap, globalRewireTable);
		FederatedRefedPolicy.registerFromProgram(prog, fTypeMap);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedPlannerUtils.clearFedInitVars();
		_oracleCache.clear();
		_numWorkers = 0;
		Map<String, FType> fedVars = new HashMap<>();
		Map<Long, FType> fTypeMap = new HashMap<>();
		for(Map.Entry<String, Data> varName : funcArgs.entrySet()) {
			Data data = varName.getValue();
			FType fType = null;
			if(data instanceof CacheableData<?> && ((CacheableData<?>) data).isFederated()) {
				fType = ((CacheableData<?>) data).getFedMapping().getType();
				_numWorkers = Math.max(_numWorkers, ((CacheableData<?>) data).getFedMapping().getSize());
			}
			fedVars.put(varName.getKey(), fType);
		}
		Map<Long, List<Hop>> globalRewireTable = buildGlobalTransientRewireTable(function);
		rRewriteStatementBlock(function, fedVars, fTypeMap, globalRewireTable);
		FederatedRefedPolicy.registerFromFunction(function, fTypeMap);
	}

	private void rRewriteStatementBlock(StatementBlock sb, Map<String, FType> fedVars,
		Map<Long, FType> fTypeMap, Map<Long, List<Hop>> globalRewireTable) {
		
		if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock)sb;
			FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
			for (StatementBlock csb : fstmt.getBody())
				rRewriteStatementBlock(csb, fedVars, fTypeMap, globalRewireTable);
		}
		else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			Map<Long, List<Hop>> rewireTable = mergeRewireTables(
				buildTransientRewireTable(Collections.singletonList(wsb.getPredicateHops())),
				globalRewireTable);
			rRewriteHop(wsb.getPredicateHops(), new HashMap<>(), new HashMap<>(),
				fTypeMap, rewireTable, globalRewireTable, sb.getDMLProg());
			rewriteLoopBody(wstmt.getBody(), fedVars, fTypeMap, globalRewireTable);
		}
		else if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			Map<Long, List<Hop>> rewireTable = mergeRewireTables(
				buildTransientRewireTable(Collections.singletonList(isb.getPredicateHops())),
				globalRewireTable);
			rRewriteHop(isb.getPredicateHops(), new HashMap<>(), new HashMap<>(),
				fTypeMap, rewireTable, globalRewireTable, sb.getDMLProg());
			Hop pred = isb.getPredicateHops();
			if (pred != null && HopRewriteUtils.isLiteralOfValue(pred, true)) {
				for (StatementBlock csb : istmt.getIfBody())
					rRewriteStatementBlock(csb, fedVars, fTypeMap, globalRewireTable);
			}
			else if (pred != null && HopRewriteUtils.isLiteralOfValue(pred, false)) {
				for (StatementBlock csb : istmt.getElseBody())
					rRewriteStatementBlock(csb, fedVars, fTypeMap, globalRewireTable);
			}
			else {
				Map<String, FType> baseFedVars = new HashMap<>(fedVars);
				Map<String, FType> ifFedVars = new HashMap<>(baseFedVars);
				for (StatementBlock csb : istmt.getIfBody())
					rRewriteStatementBlock(csb, ifFedVars, fTypeMap, globalRewireTable);
				Map<String, FType> elseFedVars = new HashMap<>(baseFedVars);
				for (StatementBlock csb : istmt.getElseBody())
					rRewriteStatementBlock(csb, elseFedVars, fTypeMap, globalRewireTable);
				Map<String, FType> merged = mergeConditionalFedVars(ifFedVars, elseFedVars);
				fedVars.clear();
				fedVars.putAll(merged);
			}
		}
		else if (sb instanceof ForStatementBlock) { //incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			Map<Long, List<Hop>> rewireFrom = mergeRewireTables(
				buildTransientRewireTable(Collections.singletonList(fsb.getFromHops())),
				globalRewireTable);
			Map<Long, List<Hop>> rewireTo = mergeRewireTables(
				buildTransientRewireTable(Collections.singletonList(fsb.getToHops())),
				globalRewireTable);
			Map<Long, List<Hop>> rewireIncr = mergeRewireTables(
				buildTransientRewireTable(Collections.singletonList(fsb.getIncrementHops())),
				globalRewireTable);
			rRewriteHop(fsb.getFromHops(), new HashMap<>(), new HashMap<>(),
				fTypeMap, rewireFrom, globalRewireTable, sb.getDMLProg());
			rRewriteHop(fsb.getToHops(), new HashMap<>(), new HashMap<>(),
				fTypeMap, rewireTo, globalRewireTable, sb.getDMLProg());
			rRewriteHop(fsb.getIncrementHops(), new HashMap<>(), new HashMap<>(),
				fTypeMap, rewireIncr, globalRewireTable, sb.getDMLProg());
			rewriteLoopBody(fstmt.getBody(), fedVars, fTypeMap, globalRewireTable);
		}
			else //generic (last-level)
			{
				//process entire hop DAGs with memoization
				Map<Long, FType> fedHops = new HashMap<>();
				List<Hop> roots = sb.getHops();
			Map<Long, List<Hop>> rewireTable = mergeRewireTables(
				buildTransientRewireTable(roots), globalRewireTable);
			if( roots != null )
				for( Hop c : roots )
					rRewriteHop(c, fedHops, fedVars, fTypeMap, rewireTable, globalRewireTable, sb.getDMLProg());
				
				//propagate federated outputs across DAGs
				if( sb.getHops() != null )
					for( Hop c : sb.getHops() )
						if( HopRewriteUtils.isData(c, OpOpData.TRANSIENTWRITE) ) {
							FType twType = fedHops.get(c.getHopID());
							if( twType == null ) {
								boolean twFed = (c.getFederatedOutput() == FederatedOutput.FOUT)
									&& (c.getForcedExecType() == ExecType.FED || c.getExecType() == ExecType.FED);
								if( twFed && c.getInput() != null && !c.getInput().isEmpty() )
									twType = fedHops.get(c.getInput(0).getHopID());
							}
							fedVars.put(c.getName(), twType);
						}
			}
		}
	
	private void rRewriteHop(Hop hop, Map<Long, FType> memo, Map<String, FType> fedVars,
		Map<Long, FType> fTypeMap, Map<Long, List<Hop>> rewireTable,
		Map<Long, List<Hop>> globalRewireTable, DMLProgram program) {
		if( hop == null || memo.containsKey(hop.getHopID()) )
			return; //already processed
		
		//process children first
		for( Hop c : hop.getInput() )
			rRewriteHop(c, memo, fedVars, fTypeMap, rewireTable, globalRewireTable, program);
		
		FType outFType = null;
		boolean logDecision = true;

		//handle specific operators (except transient writes)
		if(hop instanceof FunctionOp && ((FunctionOp) hop).getFunctionType() == FunctionType.DML) {
			FunctionOp fop = (FunctionOp) hop;
			FunctionStatementBlock sbFuncBlock = program != null ?
				program.getFunctionStatementBlock(fop.getFunctionNamespace(), fop.getFunctionName()) : null;
			if( sbFuncBlock != null ) {
				FunctionStatement funcStatement = (FunctionStatement) sbFuncBlock.getStatement(0);
				Map<String, FType> funcFedVars = createFunctionFedVarTable(fop, memo);
				rRewriteStatementBlock(sbFuncBlock, funcFedVars, fTypeMap, globalRewireTable);
				mapFunctionOutputs(fop, funcStatement, funcFedVars, fedVars);
				logDecision = false;
			}
			else {
				memo.put(hop.getHopID(), null);
				updateFTypeMap(fTypeMap, hop.getHopID(), null);
			}
		}
		else if( HopRewriteUtils.isData(hop, OpOpData.FEDERATED) ) {
			outFType = deriveFType((DataOp)hop);
			FederatedPlannerUtils.registerFedInitVar(hop.getName(), outFType,
				FederatedPlannerUtils.deriveFedInitSignature((DataOp) hop));
			_numWorkers = Math.max(_numWorkers, countFedInitWorkers((DataOp) hop));
			hop.setForcedExecType(ExecType.FED);
			hop.setFederatedOutput(FederatedOutput.FOUT);
			memo.put(hop.getHopID(), outFType);
			updateFTypeMap(fTypeMap, hop.getHopID(), outFType);
		}
		else if( HopRewriteUtils.isData(hop, OpOpData.TRANSIENTREAD) ) {
			outFType = fedVars.get(hop.getName());
			hop.setForcedExecType(outFType != null ? ExecType.FED : ExecType.CP);
			hop.setFederatedOutput(outFType != null ? FederatedOutput.FOUT : FederatedOutput.LOUT);
			memo.put(hop.getHopID(), outFType);
			updateFTypeMap(fTypeMap, hop.getHopID(), outFType);
		}
			else if( HopRewriteUtils.isData(hop, OpOpData.TRANSIENTWRITE) ) {
				Hop input = hop.getInput(0);
				FType inputFType = input != null ? memo.get(input.getHopID()) : null;
				ExecType inputExec = null;
				if (input != null)
					inputExec = input.getForcedExecType() != null ? input.getForcedExecType() : input.getExecType();
				// Only treat the write as federated if the input is truly FED/FOUT at runtime.
				boolean inputRuntimeFed = input != null && inputExec == ExecType.FED && !input.hasLocalOutput();
				outFType = inputRuntimeFed ? inputFType : null;
				hop.setForcedExecType(outFType != null ? ExecType.FED : ExecType.CP);
				hop.setFederatedOutput(outFType != null ? FederatedOutput.FOUT : FederatedOutput.LOUT);
				memo.put(hop.getHopID(), outFType);
				fedVars.put(hop.getName(), outFType);
				updateFTypeMap(fTypeMap, hop.getHopID(), outFType);
			}
			else if( allowsFederated(hop, memo) ) {
				hop.setForcedExecType(ExecType.FED);
				outFType = getFederatedOut(hop, memo, rewireTable);
			FType propagated = getPropagatedFType(hop, outFType);
			memo.put(hop.getHopID(), propagated);
			updateFTypeMap(fTypeMap, hop.getHopID(), propagated);
			if( outFType != null ) {
				hop.setFederatedOutput(FederatedOutput.FOUT);
			}
			else {
				hop.setFederatedOutput(FederatedOutput.LOUT);
			}
		}
		else { // memoization as processed, but not federated
			// Clear any stale FED markings from previous rewrite passes.
			if (hop.getForcedExecType() == ExecType.FED)
				hop.setForcedExecType(ExecType.CP);
			if (hop.getFederatedOutput() != FederatedOutput.LOUT)
				hop.setFederatedOutput(FederatedOutput.LOUT);
			if( shouldGenerateCpfoutCandidate(hop, memo) ) {
				outFType = inferCpfoutFType(hop, memo, rewireTable);
				if( outFType != null ) {
					hop.setFederatedOutput(FederatedOutput.FOUT);
					FType propagated = getPropagatedFType(hop, outFType);
					memo.put(hop.getHopID(), propagated);
					updateFTypeMap(fTypeMap, hop.getHopID(), propagated);
				}
				else {
					memo.put(hop.getHopID(), null);
					updateFTypeMap(fTypeMap, hop.getHopID(), null);
				}
			}
			else {
				memo.put(hop.getHopID(), null);
				updateFTypeMap(fTypeMap, hop.getHopID(), null);
			}
		}

			if( logDecision )
				logPlannerDecision(hop, memo, outFType);
		}

	private static Map<Long, List<Hop>> buildGlobalTransientRewireTable(DMLProgram prog) {
		if (prog == null)
			return null;
		Map<Long, List<Hop>> global = null;
		List<Hop> roots = new ArrayList<>();
		for (StatementBlock sb : prog.getStatementBlocks())
			collectRootsFromStatementBlock(sb, roots);
		global = buildTransientRewireTable(roots);
		for (FunctionStatementBlock fsb : prog.getFunctionStatementBlocks()) {
			Map<Long, List<Hop>> funcGlobal = buildGlobalTransientRewireTable(fsb);
			global = mergeRewireTables(global, funcGlobal);
		}
		return global;
	}

	private static Map<Long, List<Hop>> buildGlobalTransientRewireTable(FunctionStatementBlock function) {
		if (function == null)
			return null;
		List<Hop> roots = new ArrayList<>();
		collectRootsFromStatementBlock(function, roots);
		return buildTransientRewireTable(roots);
	}

	private static void collectRootsFromStatementBlock(StatementBlock sb, List<Hop> roots) {
		if (sb == null || roots == null)
			return;
		if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock) sb;
			FunctionStatement fstmt = (FunctionStatement) fsb.getStatement(0);
			if (fstmt == null)
				return;
			for (StatementBlock inner : fstmt.getBody())
				collectRootsFromStatementBlock(inner, roots);
		}
		else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			addRoots(wsb.getPredicateHops(), roots);
			WhileStatement wstmt = (WhileStatement) wsb.getStatement(0);
			if (wstmt != null) {
				for (StatementBlock inner : wstmt.getBody())
					collectRootsFromStatementBlock(inner, roots);
			}
		}
		else if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			addRoots(isb.getPredicateHops(), roots);
			IfStatement istmt = (IfStatement) isb.getStatement(0);
			if (istmt != null) {
				for (StatementBlock inner : istmt.getIfBody())
					collectRootsFromStatementBlock(inner, roots);
				for (StatementBlock inner : istmt.getElseBody())
					collectRootsFromStatementBlock(inner, roots);
			}
		}
		else if (sb instanceof ForStatementBlock) { // incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			addRoots(fsb.getFromHops(), roots);
			addRoots(fsb.getToHops(), roots);
			if (fsb.getIncrementHops() != null)
				addRoots(fsb.getIncrementHops(), roots);
			ForStatement fstmt = (ForStatement) fsb.getStatement(0);
			if (fstmt != null) {
				for (StatementBlock inner : fstmt.getBody())
					collectRootsFromStatementBlock(inner, roots);
			}
		}
		else {
			addRoots(sb.getHops(), roots);
		}
	}

	private static void addRoots(List<Hop> source, List<Hop> roots) {
		if (source == null || roots == null)
			return;
		for (Hop hop : source) {
			if (hop != null)
				roots.add(hop);
		}
	}

	private static void addRoots(Hop source, List<Hop> roots) {
		if (source == null || roots == null)
			return;
		roots.add(source);
	}

	private static Map<Long, List<Hop>> mergeRewireTables(Map<Long, List<Hop>> local,
		Map<Long, List<Hop>> global) {
		if (global == null || global.isEmpty())
			return local;
		if (local == null || local.isEmpty())
			return global;
		for (Map.Entry<Long, List<Hop>> entry : global.entrySet()) {
			List<Hop> globalList = entry.getValue();
			if (globalList == null || globalList.isEmpty())
				continue;
			List<Hop> localList = local.get(entry.getKey());
			if (localList == null) {
				local.put(entry.getKey(), new ArrayList<>(globalList));
				continue;
			}
			addMissingHops(localList, globalList);
		}
		return local;
	}

	private static void addMissingHops(List<Hop> target, List<Hop> additions) {
		if (target == null || additions == null || additions.isEmpty())
			return;
		Set<Long> seen = new HashSet<>(target.size());
		for (Hop hop : target) {
			if (hop != null)
				seen.add(hop.getHopID());
		}
		for (Hop hop : additions) {
			if (hop == null)
				continue;
			long hopId = hop.getHopID();
			if (!seen.contains(hopId)) {
				target.add(hop);
				seen.add(hopId);
			}
		}
	}

	@Override
	protected boolean allowsFederated(Hop hop, Map<Long, FType> fedHops) {
		if (!FederatedRefedPolicy.canSatisfyFederatedInputs(hop, fedHops))
			return false;
		OpCaps caps = getOracleCaps(hop, fedHops);
		return caps != null && caps.exec() == ExecType.FED;
	}

	@Override
	protected FType getFederatedOut(Hop hop, Map<Long, FType> fedHops) {
		return getFederatedOut(hop, fedHops, null);
	}

	protected FType getFederatedOut(Hop hop, Map<Long, FType> fedHops,
		Map<Long, List<Hop>> rewireTable) {
		OpCaps caps = getOracleCaps(hop, fedHops);
		if( caps == null )
			return null;
		if( caps.foutFType().isPresent() )
			return caps.foutFType().get();
		if( caps.placement() != FederatedOutput.FOUT )
			return null;
		List<FType> inputFTypes = collectInputFTypes(hop, fedHops);
		FType inferred = OracleUtils.inferFallbackFType(hop, inputFTypes, _oracle, rewireTable);
		return inferred != null ? inferred : FederatedTypePropagator.getFederatedType(hop, fedHops);
	}

	protected FType getPropagatedFType(Hop hop, FType outFType) {
		return outFType;
	}

	private void logPlannerDecision(Hop hop, Map<Long, FType> memo, FType outFType) {
		List<FType> inputFTypes = collectInputFTypes(hop, memo);
		ExecType execType = resolveExecType(hop, outFType);
		OpCaps caps = getOracleCaps(hop, inputFTypes);
		ReasonCode reason = caps != null ? caps.reason()
			: execType == ExecType.FED ? ReasonCode.OK : ReasonCode.NO_RULE;
		OpCaps.Builder builder = OpCaps.builder().exec(execType).reason(reason);
		if( outFType != null )
			builder.fout(true, outFType);
		else
			builder.fout(false);
		FederatedPlannerLogger.logOracleDecision(hop, null, inputFTypes, builder.build(), null);
	}

	private OpCaps getOracleCaps(Hop hop, Map<Long, FType> fedHops) {
		return getOracleCaps(hop, collectInputFTypes(hop, fedHops));
	}

	private OpCaps getOracleCaps(Hop hop, List<FType> inputFTypes) {
		if( hop == null )
			return null;
		List<FType> key = inputFTypes == null ? Collections.emptyList() : new ArrayList<>(inputFTypes);
		Map<List<FType>, OpCaps> byInputs =
			_oracleCache.computeIfAbsent(hop.getHopID(), k -> new HashMap<>());
		OpCaps cached = byInputs.get(key);
		if( cached != null )
			return cached;
		OpCaps caps = _oracle.decide(hop, key);
		byInputs.put(key, caps);
		return caps;
	}

	private static List<FType> collectInputFTypes(Hop hop, Map<Long, FType> memo) {
		if( hop.getInput() == null || hop.getInput().isEmpty() )
			return Collections.emptyList();
		List<FType> types = new ArrayList<>(hop.getInput().size());
		for( Hop input : hop.getInput() )
			types.add(memo.get(input.getHopID()));
		return types;
	}

	private static ExecType resolveExecType(Hop hop, FType outFType) {
		ExecType forced = hop.getForcedExecType();
		if( forced != null )
			return forced;
		ExecType planned = hop.getExecType();
		return planned != null ? planned : ExecType.CP;
	}

	private static void updateFTypeMap(Map<Long, FType> fTypeMap, long hopId, FType fType) {
		if( fTypeMap == null )
			return;
		if( fType != null )
			fTypeMap.put(hopId, fType);
		else
			fTypeMap.remove(hopId);
	}

	private boolean shouldGenerateCpfoutCandidate(Hop hop, Map<Long, FType> memo) {
		if( hop == null || !hop.getDataType().isMatrix() )
			return false;
		if( isRecompileRegion(hop) )
			return false;
		if( hop instanceof DataOp ) {
			OpOpData op = ((DataOp) hop).getOp();
			if( op == OpOpData.TRANSIENTREAD || op == OpOpData.TRANSIENTWRITE )
				return false;
		}
		return FederatedRefedPolicy.canGenerateCpfoutCandidate(hop, memo);
	}

	private static boolean isRecompileRegion(Hop hop) {
		if( hop == null )
			return false;
		if( hop.requiresRecompile() )
			return true;
		List<Hop> inputs = hop.getInput();
		if( inputs == null )
			return false;
		for( Hop in : inputs ) {
			if( in != null && in.requiresRecompile() )
				return true;
		}
		return false;
	}

	private static boolean hasFederatedInput(Hop hop, Map<Long, FType> memo) {
		if (hop == null || memo == null || hop.getInput() == null)
			return false;
		for (Hop input : hop.getInput()) {
			if (input != null && memo.get(input.getHopID()) != null)
				return true;
		}
		return false;
	}

	private void rewriteLoopBody(List<StatementBlock> body, Map<String, FType> fedVars,
		Map<Long, FType> fTypeMap, Map<Long, List<Hop>> globalRewireTable) {
		if (body == null || body.isEmpty())
			return;
		Map<String, FType> loopFedVars = new HashMap<>(fedVars);
		boolean changed;
		int passes = 0;
		do {
			Map<String, FType> snapshot = new HashMap<>(loopFedVars);
			for (StatementBlock csb : body)
				rRewriteStatementBlock(csb, loopFedVars, fTypeMap, globalRewireTable);
			changed = reconcileFedVars(loopFedVars, snapshot);
			passes++;
		} while (changed && passes < MAX_LOOP_REWRITE_PASSES);
		fedVars.clear();
		fedVars.putAll(loopFedVars);
	}

	private static boolean reconcileFedVars(Map<String, FType> current, Map<String, FType> previous) {
		boolean changed = false;
		for (Map.Entry<String, FType> entry : previous.entrySet()) {
			FType prev = entry.getValue();
			if (prev == null)
				continue;
			String name = entry.getKey();
			if (!current.containsKey(name)) {
				current.put(name, prev);
				changed = true;
				continue;
			}
			FType cur = current.get(name);
			if (cur == null) {
				// Explicit local assignment in the current pass; do not revive a prior federated state.
				if (prev != null)
					changed = true;
			}
			else if (!cur.equals(prev)) {
				changed = true;
			}
		}
		return changed;
	}

	private static Map<String, FType> mergeConditionalFedVars(Map<String, FType> ifFedVars,
		Map<String, FType> elseFedVars) {
		Map<String, FType> merged = new HashMap<>();
		Set<String> keys = new HashSet<>();
		if (ifFedVars != null)
			keys.addAll(ifFedVars.keySet());
		if (elseFedVars != null)
			keys.addAll(elseFedVars.keySet());
		for (String key : keys) {
			FType ifVal = ifFedVars != null ? ifFedVars.get(key) : null;
			FType elseVal = elseFedVars != null ? elseFedVars.get(key) : null;
			if (ifVal != null && ifVal.equals(elseVal)) {
				merged.put(key, ifVal);
			}
			else if (ifVal == null && elseVal == null) {
				merged.put(key, null);
			}
			else {
				// Divergent assignments across branches; treat as local/unknown after the if.
				merged.put(key, null);
			}
		}
		return merged;
	}

	private FType inferCpfoutFType(Hop hop, Map<Long, FType> memo, Map<Long, List<Hop>> rewireTable) {
		List<FType> inputFTypes = collectInputFTypes(hop, memo);
		FType inferred = OracleUtils.inferFallbackFType(hop, inputFTypes, _oracle, rewireTable);
		FType logical = inferred != null ? inferred : FederatedTypePropagator.getFederatedType(hop, memo);
		return OracleUtils.adjustCpFoutFTypeForConsumerAxisMismatch(hop, logical, rewireTable, _numWorkers);
	}

	private static int countFedInitWorkers(DataOp fedInit) {
		if (fedInit == null || fedInit.getOp() != OpOpData.FEDERATED)
			return 0;
		int addrIx = fedInit.getParameterIndex(DataExpression.FED_ADDRESSES);
		if (addrIx < 0)
			return 0;
		Hop addrHop = fedInit.getInput(addrIx);
		if (addrHop == null || addrHop.getInput() == null)
			return 0;
		return addrHop.getInput().size();
	}

	private static Map<Long, List<Hop>> buildTransientRewireTable(List<Hop> roots) {
		if( roots == null || roots.isEmpty() )
			return null;
		Map<String, List<Hop>> tReads = new HashMap<>();
		Map<String, List<Hop>> tWrites = new HashMap<>();
		Set<Hop> visited = new HashSet<>();
		Deque<Hop> queue = new ArrayDeque<>();
		for( Hop root : roots )
			if( root != null )
				queue.add(root);
		while( !queue.isEmpty() ) {
			Hop hop = queue.poll();
			if( hop == null || !visited.add(hop) )
				continue;
			if( hop instanceof DataOp ) {
				DataOp dataOp = (DataOp) hop;
				String name = dataOp.getName();
				if( name != null ) {
					if( dataOp.getOp() == OpOpData.TRANSIENTREAD ) {
						tReads.computeIfAbsent(name, k -> new ArrayList<>()).add(hop);
					}
					else if( dataOp.getOp() == OpOpData.TRANSIENTWRITE ) {
						tWrites.computeIfAbsent(name, k -> new ArrayList<>()).add(hop);
					}
				}
			}
			for( Hop in : hop.getInput() )
				queue.add(in);
		}
		if( tReads.isEmpty() || tWrites.isEmpty() )
			return null;
		Map<Long, List<Hop>> rewire = new HashMap<>();
		for( Map.Entry<String, List<Hop>> entry : tWrites.entrySet() ) {
			List<Hop> writes = entry.getValue();
			if( writes == null || writes.size() != 1 )
				continue;
			List<Hop> reads = tReads.get(entry.getKey());
			if( reads == null || reads.isEmpty() )
				continue;
			Hop tw = writes.get(0);
			List<Hop> filteredReads = filterTransientReadsForWrite(tw, reads);
			if( filteredReads.isEmpty() )
				continue;
			rewire.put(tw.getHopID(), new ArrayList<>(filteredReads));
			for( Hop tr : filteredReads )
				rewire.put(tr.getHopID(), new ArrayList<>(writes));
		}
		return rewire;
	}

	private static List<Hop> filterTransientReadsForWrite(Hop tw, List<Hop> reads) {
		if( tw == null || reads == null || reads.isEmpty() )
			return Collections.emptyList();
		List<Hop> filtered = new ArrayList<>(reads.size());
		for( Hop tr : reads ) {
			if( tr == null )
				continue;
			if( !isInInputDag(tw, tr) )
				filtered.add(tr);
		}
		return filtered;
	}

	private static boolean isInInputDag(Hop root, Hop target) {
		if( root == null || target == null )
			return false;
		if( root.getInput() == null || root.getInput().isEmpty() )
			return false;
		long targetId = target.getHopID();
		Deque<Hop> queue = new ArrayDeque<>(root.getInput());
		Set<Long> visited = new HashSet<>();
		while( !queue.isEmpty() ) {
			Hop cur = queue.poll();
			if( cur == null )
				continue;
			long curId = cur.getHopID();
			if( !visited.add(curId) )
				continue;
			if( curId == targetId )
				return true;
			if( cur.getInput() != null )
				queue.addAll(cur.getInput());
		}
		return false;
	}
	
	static private Map<String, FType> createFunctionFedVarTable(FunctionOp hop, Map<Long, FType> memo) {
		Map<String, Hop> funcParamMap = FederatedPlannerUtils.getParamMap(hop);
		Map<String, FType> funcFedVars = new HashMap<>();
		funcParamMap.forEach((key, value) -> {
			funcFedVars.put(key, memo.get(value.getHopID()));
		});
		return funcFedVars;
	}

	private void mapFunctionOutputs(FunctionOp sbHop, FunctionStatement funcStatement,
		Map<String, FType> funcFedVars, Map<String, FType> callFedVars) {
		for(int i = 0; i < sbHop.getOutputVariableNames().length; ++i) {
			FType outputFType = funcFedVars.get(funcStatement.getOutputParams().get(i).getName());
			callFedVars.put(sbHop.getOutputVariableNames()[i], outputFType);
		}
	}
}
