/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DataIdentifier;
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

/**
 * Exposes an ordinary compiled transfer input edge at each branch exit, before
 * the placement analysis takes its immutable program snapshot. These are
 * identity sites, not unconditional GETs: LOCAL/REFED actions are enumerated,
 * priced, selected and lowered by the same machinery as other compiled inputs.
 * The final TW remains an alias of the selected physical input. A missing else
 * is represented by a pass-through arm, so a transfer of an unchanged entry
 * definition has the correct execution scope, including inside loops/calls.
 */
public final class BranchPlacementNormalization {
	private BranchPlacementNormalization() { }

	public static void prepare(DMLProgram program) {
		program.requirePlacementAnalysisUnboundForHopRewrite();
		Map<IfStatementBlock,Set<String>> liveExits = new IdentityHashMap<>();
		liveBefore(program.getStatementBlocks(), Set.of(), liveExits);
		for(FunctionStatementBlock block : program.getNamedNSFunctionStatementBlocks().values()) {
			FunctionStatement function = (FunctionStatement)block.getStatement(0);
			liveBefore(function.getBody(), new HashSet<>(function.getOutputParams().stream()
				.map(DataIdentifier::getName).toList()), liveExits);
		}
		prepare(program, program.getStatementBlocks(), liveExits);
		for(FunctionStatementBlock block : program.getNamedNSFunctionStatementBlocks().values())
			prepare(program, List.of(block), liveExits);
	}

	public static boolean isPlacementAlias(Hop hop) {
		return hop instanceof UnaryOp unary && unary.getOp() == OpOp1._PLACEMENT;
	}

	private static void prepare(DMLProgram program, List<StatementBlock> blocks,
		Map<IfStatementBlock,Set<String>> liveExits) {
		for(StatementBlock block : blocks) {
			if(block instanceof IfStatementBlock branch) {
				IfStatement statement = (IfStatement)branch.getStatement(0);
				prepare(program, statement.getIfBody(), liveExits);
				prepare(program, statement.getElseBody(), liveExits);
				VariableSet live = branch.liveOut();
				VariableSet updated = branch.variablesUpdated();
				if(live == null || updated == null)
					continue;
				List<DataIdentifier> values = live.getVariables().entrySet().stream()
					.filter(entry -> updated.containsVariable(entry.getKey())
						&& liveExits.getOrDefault(branch, Set.of()).contains(entry.getKey())
						&& entry.getValue().getDataType().isMatrix())
					.sorted(java.util.Map.Entry.comparingByKey())
					.map(java.util.Map.Entry::getValue).toList();
				if(!values.isEmpty()) {
					appendExit(program, branch, statement.getIfBody(), values);
					appendExit(program, branch, statement.getElseBody(), values);
				}
			}
			else if(block instanceof WhileStatementBlock)
				prepare(program, ((WhileStatement)block.getStatement(0)).getBody(), liveExits);
			else if(block instanceof ForStatementBlock)
				prepare(program, ((ForStatement)block.getStatement(0)).getBody(), liveExits);
			else if(block instanceof FunctionStatementBlock)
				prepare(program, ((FunctionStatement)block.getStatement(0)).getBody(), liveExits);
		}
	}

	/** AST liveOut can mention values removed by final HOP dead-code rewrites. */
	private static Set<String> liveBefore(List<StatementBlock> blocks, Set<String> after,
		Map<IfStatementBlock,Set<String>> liveExits) {
		Set<String> live = new HashSet<>(after);
		for(int index = blocks.size() - 1; index >= 0; index--) {
			StatementBlock block = blocks.get(index);
			if(block instanceof IfStatementBlock branch) {
				liveExits.computeIfAbsent(branch, ignored -> new HashSet<>()).addAll(live);
				IfStatement statement = (IfStatement)branch.getStatement(0);
				Set<String> before = liveBefore(statement.getIfBody(), live, liveExits);
				before.addAll(liveBefore(statement.getElseBody(), live, liveExits));
				collectReads(branch.getPredicateHops(), before, new IdentityHashMap<>());
				live = before;
			}
			else if(block instanceof WhileStatementBlock || block instanceof ForStatementBlock) {
				List<StatementBlock> body;
				if(block instanceof WhileStatementBlock loop) {
					body = ((WhileStatement)loop.getStatement(0)).getBody();
					collectReads(loop.getPredicateHops(), live, new IdentityHashMap<>());
				}
				else {
					ForStatementBlock loop = (ForStatementBlock)block;
					body = ((ForStatement)loop.getStatement(0)).getBody();
					for(Hop bound : new Hop[] {loop.getFromHops(), loop.getToHops(), loop.getIncrementHops()})
						collectReads(bound, live, new IdentityHashMap<>());
				}
				boolean changed;
				do {
					changed = live.addAll(liveBefore(body, live, liveExits));
				} while(changed);
			}
			else if(block instanceof FunctionStatementBlock function)
				live = liveBefore(((FunctionStatement)function.getStatement(0)).getBody(), live, liveExits);
			else if(block.getHops() != null) {
				for(Hop root : block.getHops()) {
					if(root instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE)
						live.remove(root.getName());
					if(root instanceof FunctionOp call && call.getOutputVariableNames() != null)
						live.removeAll(java.util.Arrays.asList(call.getOutputVariableNames()));
				}
				Map<Hop,Boolean> visited = new IdentityHashMap<>();
				for(Hop root : block.getHops())
					collectReads(root, live, visited);
			}
		}
		return live;
	}

	private static void collectReads(Hop hop, Set<String> reads, Map<Hop,Boolean> visited) {
		if(hop == null || visited.put(hop, Boolean.TRUE) != null)
			return;
		if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD)
			reads.add(hop.getName());
		for(Hop input : hop.getInput())
			collectReads(input, reads, visited);
	}

	private static void appendExit(DMLProgram program, IfStatementBlock branch,
		ArrayList<StatementBlock> body, List<DataIdentifier> values) {
		if(!body.isEmpty() && isExitSite(body.get(body.size() - 1)))
			return; // search-space diagnostics and production may share preparation
		StatementBlock exit = new StatementBlock();
		exit.setDMLProg(program);
		exit.setParseInfo(branch);
		exit.setLiveIn(new VariableSet(branch.liveOut()));
		exit.setLiveOut(new VariableSet(branch.liveOut()));
		VariableSet touched = new VariableSet();
		ArrayList<Hop> roots = new ArrayList<>();
		for(DataIdentifier value : values) {
			String name = value.getName();
			DataOp read = new DataOp(name, value.getDataType(), value.getValueType(),
				OpOpData.TRANSIENTREAD, null, value.getDim1(), value.getDim2(),
				value.getNnz(), value.getBlocksize());
			UnaryOp transfer = new UnaryOp(name, value.getDataType(), value.getValueType(),
				OpOp1._PLACEMENT, read);
			DataOp write = new DataOp(name, value.getDataType(), value.getValueType(), transfer,
				OpOpData.TRANSIENTWRITE, null);
			for(Hop hop : List.of(read, transfer, write)) {
				hop.setParseInfo(branch);
				hop.setBlocksize(value.getBlocksize());
			}
			write.refreshSizeInformation();
			roots.add(write);
			touched.addVariable(name, value);
		}
		exit.setHops(roots);
		exit.setReadVariables(new VariableSet(touched));
		exit.setUpdatedVariables(new VariableSet(touched));
		exit.setGen(new VariableSet(touched));
		exit.setKill(new VariableSet(touched));
		body.add(exit);
	}

	private static boolean isExitSite(StatementBlock block) {
		return block.getHops() != null && !block.getHops().isEmpty()
			&& block.getHops().stream().allMatch(root -> root instanceof DataOp data
				&& data.getOp() == OpOpData.TRANSIENTWRITE && root.getInput().size() == 1
				&& isPlacementAlias(root.getInput(0)));
	}
}
