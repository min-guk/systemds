/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
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

/** Independently checks cardinality and raw AST/HOP incidence against a captured snapshot. */
public final class PrebuilderSnapshotVerifier {
	private PrebuilderSnapshotVerifier() { }

	public static void assertMatches(DMLProgram program, PrebuilderSnapshot snapshot) {
		List<String> blockPaths = new ArrayList<>();
		List<PrebuilderSnapshot.Root> roots = new ArrayList<>();
		List<PrebuilderSnapshot.InlinedCall> inlinedCalls = new ArrayList<>();
		Set<Hop> hops = Collections.newSetFromMap(new IdentityHashMap<>());
		checkBlocks(program.getStatementBlocks(), "main", blockPaths, roots, inlinedCalls, hops);
		List<String> functionKeys = new ArrayList<>(program.getNamedNSFunctionStatementBlocks().keySet());
		Collections.sort(functionKeys);
		for(String key : functionKeys)
			checkBlock(program.getNamedNSFunctionStatementBlocks().get(key), "function/" + key,
				blockPaths, roots, inlinedCalls, hops);
		if(!blockPaths.equals(snapshot.blocks().stream().map(PrebuilderSnapshot.Block::path).toList()))
			throw new AssertionError("Snapshot block paths differ from raw AST");
		for(PrebuilderSnapshot.Block captured : snapshot.blocks()) {
			StatementBlock raw = findBlock(program, captured.path());
			if(raw.getSBID() != captured.id()
				|| !raw.getClass().getSimpleName().equals(captured.kind())
				|| raw.isRecompileOnce() != captured.recompileOnce()
				|| !captured.location().equals(new PrebuilderSnapshot.Location(raw.getFilename(),
					raw.getBeginLine(), raw.getBeginColumn(), raw.getEndLine(), raw.getEndColumn())))
				throw new AssertionError("Snapshot block metadata differs at " + captured.path());
		}
		if(!roots.equals(snapshot.roots()))
			throw new AssertionError("Snapshot roots differ from raw AST");
		if(!inlinedCalls.equals(snapshot.inlinedCalls()))
			throw new AssertionError("Snapshot inlined call boundaries differ from raw AST");
		if(!functionKeys.equals(snapshot.functions().stream().map(PrebuilderSnapshot.Function::key).toList()))
			throw new AssertionError("Snapshot function declarations differ from raw AST");
		for(PrebuilderSnapshot.Function function : snapshot.functions()) {
			FunctionStatement raw = (FunctionStatement) program.getNamedNSFunctionStatementBlocks()
				.get(function.key()).getStatement(0);
			if(!function.name().equals(raw.getName())
				|| !function.inputs().equals(raw.getInputParams().stream().map(p -> p.getName()).toList())
				|| !function.outputs().equals(raw.getOutputParams().stream().map(p -> p.getName()).toList()))
				throw new AssertionError("Snapshot function signature differs from raw AST: " + function.key());
		}
		Set<Long> ids = new HashSet<>();
		List<PrebuilderSnapshot.Edge> edges = new ArrayList<>();
		List<PrebuilderSnapshot.Call> calls = new ArrayList<>();
		for(Hop hop : hops) {
			if(!ids.add(hop.getHopID())) throw new AssertionError("Duplicate raw AST HOP ID " + hop.getHopID());
			for(int i = 0; i < hop.getInput().size(); i++)
				edges.add(new PrebuilderSnapshot.Edge(hop.getHopID(), i, hop.getInput(i).getHopID()));
			if(hop instanceof FunctionOp call)
				calls.add(new PrebuilderSnapshot.Call(hop.getHopID(), call.getFunctionKey(),
					call.getInputVariableNames() == null ? null : Arrays.asList(call.getInputVariableNames().clone()),
					call.getOutputVariableNames() == null ? null : Arrays.asList(call.getOutputVariableNames().clone())));
		}
		if(!ids.equals(snapshot.nodes().keySet()))
			throw new AssertionError("Snapshot HOP IDs differ from raw AST");
		if(!new HashSet<>(edges).equals(new HashSet<>(snapshot.edges())) || edges.size() != snapshot.edges().size())
			throw new AssertionError("Snapshot ordered input occurrences differ from raw HOP graph");
		if(!new HashSet<>(calls).equals(new HashSet<>(snapshot.calls())) || calls.size() != snapshot.calls().size())
			throw new AssertionError("Snapshot function calls differ from raw HOP graph");
		for(Hop hop : hops) {
			PrebuilderSnapshot.Node node = snapshot.nodes().get(hop.getHopID());
			if(node.rows() != hop.getDim1() || node.columns() != hop.getDim2()
				|| node.requiresRecompile() != hop.requiresRecompile()
				|| !node.valueType().equals(String.valueOf(hop.getValueType()))
				|| !node.dataType().equals(String.valueOf(hop.getDataType()))
				|| !node.className().equals(hop.getClass().getSimpleName())
				|| !node.operation().equals(hop.getOpString())
				|| !node.location().equals(new PrebuilderSnapshot.Location(hop.getFilename(),
					hop.getBeginLine(), hop.getBeginColumn(), hop.getEndLine(), hop.getEndColumn())))
				throw new AssertionError("Snapshot HOP metadata differs for " + hop.getHopID());
		}
	}

	private static StatementBlock findBlock(DMLProgram program, String path) {
		String[] parts;
		int index;
		StatementBlock block;
		if(path.startsWith("main/")) {
			parts = path.substring("main/".length()).split("/");
			block = program.getStatementBlocks().get(Integer.parseInt(parts[0]));
			index = 1;
		}
		else if(path.startsWith("function/")) {
			String key = program.getNamedNSFunctionStatementBlocks().keySet().stream()
				.filter(candidate -> path.equals("function/" + candidate)
					|| path.startsWith("function/" + candidate + "/"))
				.max(java.util.Comparator.comparingInt(String::length))
				.orElseThrow(() -> new AssertionError("Unknown function path: " + path));
			block = program.getNamedNSFunctionStatementBlocks().get(key);
			String suffix = path.substring(("function/" + key).length());
			parts = suffix.isEmpty() ? new String[0] : suffix.substring(1).split("/");
			index = 0;
		}
		else throw new AssertionError("Unknown snapshot block path: " + path);
		while(index < parts.length) {
			String branch = parts[index++];
			List<StatementBlock> body;
			if(block instanceof IfStatementBlock) {
				IfStatement statement = (IfStatement) block.getStatement(0);
				body = branch.equals("if") ? statement.getIfBody() : statement.getElseBody();
			}
			else if(block instanceof WhileStatementBlock)
				body = ((WhileStatement) block.getStatement(0)).getBody();
			else if(block instanceof ForStatementBlock)
				body = ((ForStatement) block.getStatement(0)).getBody();
			else
				body = ((FunctionStatement) block.getStatement(0)).getBody();
			block = body.get(Integer.parseInt(parts[index++]));
		}
		return block;
	}

	private static void checkBlocks(List<StatementBlock> blocks, String prefix, List<String> paths,
		List<PrebuilderSnapshot.Root> roots, List<PrebuilderSnapshot.InlinedCall> calls, Set<Hop> hops) {
		if(blocks == null) return;
		for(int i = 0; i < blocks.size(); i++) checkBlock(blocks.get(i), prefix + "/" + i, paths, roots, calls, hops);
	}
	private static void checkBlock(StatementBlock block, String path, List<String> paths,
		List<PrebuilderSnapshot.Root> roots, List<PrebuilderSnapshot.InlinedCall> calls, Set<Hop> hops) {
		paths.add(path);
		for(StatementBlock.InlinedFunctionCallBoundary raw : block.getInlinedFunctionCallBoundaries())
			calls.add(new PrebuilderSnapshot.InlinedCall(path, raw.functionKey(), raw.callStatementPosition(),
				raw.inputs().stream().map(input -> new PrebuilderSnapshot.InputBoundary(
					input.position(), input.statementPosition(), input.formalVariable(),
					input.boundVariable(), input.actualVariable())).toList(),
				raw.outputs().stream().map(output -> new PrebuilderSnapshot.OutputBoundary(
					output.position(), output.statementPosition(), output.formalVariable(),
					output.boundVariable(), output.targetVariable())).toList()));
		addRoots(block.getHops(), path, "body", roots, hops);
		if(block instanceof IfStatementBlock control) {
			addRoot(control.getPredicateHops(), path, "predicate", 0, roots, hops);
			IfStatement statement = (IfStatement) block.getStatement(0);
			checkBlocks(statement.getIfBody(), path + "/if", paths, roots, calls, hops);
			checkBlocks(statement.getElseBody(), path + "/else", paths, roots, calls, hops);
		}
		else if(block instanceof WhileStatementBlock control) {
			addRoot(control.getPredicateHops(), path, "predicate", 0, roots, hops);
			checkBlocks(((WhileStatement) block.getStatement(0)).getBody(), path + "/while", paths, roots, calls, hops);
		}
		else if(block instanceof ForStatementBlock control) {
			addRoot(control.getFromHops(), path, "from", 0, roots, hops);
			addRoot(control.getToHops(), path, "to", 0, roots, hops);
			addRoot(control.getIncrementHops(), path, "increment", 0, roots, hops);
			checkBlocks(((ForStatement) block.getStatement(0)).getBody(), path + "/for", paths, roots, calls, hops);
		}
		else if(block instanceof FunctionStatementBlock)
			checkBlocks(((FunctionStatement) block.getStatement(0)).getBody(), path + "/body", paths, roots, calls, hops);
	}
	private static void addRoots(List<Hop> values, String path, String role,
		List<PrebuilderSnapshot.Root> roots, Set<Hop> hops) {
		if(values == null) return;
		for(int i = 0; i < values.size(); i++) addRoot(values.get(i), path, role, i, roots, hops);
	}
	private static void addRoot(Hop hop, String path, String role, int index,
		List<PrebuilderSnapshot.Root> roots, Set<Hop> hops) {
		if(hop == null) return;
		roots.add(new PrebuilderSnapshot.Root(path, role, index, hop.getHopID()));
		collect(hop, hops, roots);
	}
	private static void collect(Hop hop, Set<Hop> hops, List<PrebuilderSnapshot.Root> roots) {
		if(!hops.add(hop)) return;
		for(Hop child : hop.getInput()) collect(child, hops, roots);
		if(hop instanceof FunctionOp call && call.getOutputs() != null)
			for(int i = 0; i < call.getOutputs().size(); i++)
				addRoot(call.getOutputs().get(i), "call/" + hop.getHopID(), "output", i, roots, hops);
	}
}
