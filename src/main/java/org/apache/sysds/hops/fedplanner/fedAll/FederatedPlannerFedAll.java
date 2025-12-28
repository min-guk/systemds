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
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
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
	
	@Override
	public void rewriteProgram( DMLProgram prog,
		FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes )
	{
		// handle main program
		Map<String, FType> fedVars = new HashMap<>();
		for(StatementBlock sb : prog.getStatementBlocks())
			rRewriteStatementBlock(sb, fedVars);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		Map<String, FType> fedVars = new HashMap<>();
		for(Map.Entry<String, Data> varName : funcArgs.entrySet()) {
			Data data = varName.getValue();
			FType fType = null;
			if(data instanceof CacheableData<?> && ((CacheableData<?>) data).isFederated()) {
				fType = ((CacheableData<?>) data).getFedMapping().getType();
			}
			fedVars.put(varName.getKey(), fType);
		}
		rRewriteStatementBlock(function, fedVars);
	}

	private void rRewriteStatementBlock(StatementBlock sb, Map<String, FType> fedVars) {
		//TODO currently this rewrite assumes consistent decisions in conditional control flow
		
		if (sb instanceof FunctionStatementBlock) {
			FunctionStatementBlock fsb = (FunctionStatementBlock)sb;
			FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
			for (StatementBlock csb : fstmt.getBody())
				rRewriteStatementBlock(csb, fedVars);
		}
		else if (sb instanceof WhileStatementBlock) {
			WhileStatementBlock wsb = (WhileStatementBlock) sb;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			rRewriteHop(wsb.getPredicateHops(), new HashMap<>(), new HashMap<>(), sb.getDMLProg());
			for (StatementBlock csb : wstmt.getBody())
				rRewriteStatementBlock(csb, fedVars);
		}
		else if (sb instanceof IfStatementBlock) {
			IfStatementBlock isb = (IfStatementBlock) sb;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			rRewriteHop(isb.getPredicateHops(), new HashMap<>(), new HashMap<>(), sb.getDMLProg());
			for (StatementBlock csb : istmt.getIfBody())
				rRewriteStatementBlock(csb, fedVars);
			for (StatementBlock csb : istmt.getElseBody())
				rRewriteStatementBlock(csb, fedVars);
		}
		else if (sb instanceof ForStatementBlock) { //incl parfor
			ForStatementBlock fsb = (ForStatementBlock) sb;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			rRewriteHop(fsb.getFromHops(), new HashMap<>(), new HashMap<>(), sb.getDMLProg());
			rRewriteHop(fsb.getToHops(), new HashMap<>(), new HashMap<>(), sb.getDMLProg());
			rRewriteHop(fsb.getIncrementHops(), new HashMap<>(), new HashMap<>(), sb.getDMLProg());
			for (StatementBlock csb : fstmt.getBody())
				rRewriteStatementBlock(csb, fedVars);
		}
		else //generic (last-level)
		{
			//process entire hop DAGs with memoization
			Map<Long, FType> fedHops = new HashMap<>();
			if( sb.getHops() != null )
				for( Hop c : sb.getHops() )
					rRewriteHop(c, fedHops, fedVars, sb.getDMLProg());
			
			//propagate federated outputs across DAGs
			if( sb.getHops() != null )
				for( Hop c : sb.getHops() )
					if( HopRewriteUtils.isData(c, OpOpData.TRANSIENTWRITE) )
						fedVars.put(c.getName(), fedHops.get(c.getInput(0).getHopID()));
		}
	}
	
	private void rRewriteHop(Hop hop, Map<Long, FType> memo, Map<String, FType> fedVars, DMLProgram program) {
		if( hop == null || memo.containsKey(hop.getHopID()) )
			return; //already processed
		
		//process children first
		for( Hop c : hop.getInput() )
			rRewriteHop(c, memo, fedVars, program);
		
		FType outFType = null;
		boolean logDecision = true;

		//handle specific operators (except transient writes)
		if(hop instanceof FunctionOp) {
			String funcName = ((FunctionOp) hop).getFunctionName();
			String funcNamespace = ((FunctionOp) hop).getFunctionNamespace();
			FunctionStatementBlock sbFuncBlock = program.getFunctionDictionary(funcNamespace).getFunction(funcName);
			FunctionStatement funcStatement = (FunctionStatement) sbFuncBlock.getStatement(0);

			Map<String, FType> funcFedVars = createFunctionFedVarTable((FunctionOp) hop, memo);
			rRewriteStatementBlock(sbFuncBlock, funcFedVars);
			mapFunctionOutputs((FunctionOp) hop, funcStatement, funcFedVars, fedVars);
			logDecision = false;
		}
		else if( HopRewriteUtils.isData(hop, OpOpData.FEDERATED) ) {
			outFType = deriveFType((DataOp)hop);
			memo.put(hop.getHopID(), outFType);
		}
		else if( HopRewriteUtils.isData(hop, OpOpData.TRANSIENTREAD) ) {
			outFType = fedVars.get(hop.getName());
			memo.put(hop.getHopID(), outFType);
		}
		else if( HopRewriteUtils.isData(hop, OpOpData.TRANSIENTWRITE) ) {
			fedVars.put(hop.getName(), memo.get(hop.getHopID()));
			outFType = getFederatedOut(hop, memo);
		}
		else if( allowsFederated(hop, memo) ) {
			hop.setForcedExecType(ExecType.FED);
			outFType = getFederatedOut(hop, memo);
			memo.put(hop.getHopID(), outFType);
			if( outFType != null )
				hop.setFederatedOutput(FederatedOutput.FOUT);
		}
		else // memoization as processed, but not federated
			memo.put(hop.getHopID(), null);

		if( logDecision )
			logPlannerDecision(hop, memo, outFType);
	}

	private void logPlannerDecision(Hop hop, Map<Long, FType> memo, FType outFType) {
		List<FType> inputFTypes = collectInputFTypes(hop, memo);
		ExecType execType = resolveExecType(hop, outFType);
		ReasonCode reason = execType == ExecType.FED ? ReasonCode.OK : ReasonCode.NO_RULE;
		OpCaps.Builder builder = OpCaps.builder().exec(execType).reason(reason);
		if( outFType != null )
			builder.fout(true, outFType);
		else
			builder.fout(false);
		FederatedPlannerLogger.logOracleDecision(hop, null, inputFTypes, builder.build(), null);
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
		return outFType != null ? ExecType.FED : ExecType.CP;
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
