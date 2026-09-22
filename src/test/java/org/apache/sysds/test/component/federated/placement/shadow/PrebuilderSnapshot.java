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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
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

/** Test-owned, pre-builder projection of the compiled parser/HOP graph. No placement API is consulted. */
public final class PrebuilderSnapshot {
	public record ExternalSource(String origin, String privacy, String partition) {
		public ExternalSource {
			Objects.requireNonNull(origin);
			Objects.requireNonNull(privacy);
			Objects.requireNonNull(partition);
		}
	}
	public record Location(String file, int beginLine, int beginColumn, int endLine, int endColumn) { }
	public record Block(String path, long id, String kind, boolean recompileOnce, Location location) { }
	public record Root(String blockPath, String role, int index, long hopId) { }
	public record Node(long hopId, String className, String operation, String name, String dataType,
		String valueType, long rows, long columns, boolean requiresRecompile, Location location,
		ExternalSource externalSource) { }
	/** The index is an occurrence index; duplicate inputs must remain duplicate edges. */
	public record Edge(long parentId, int inputIndex, long childId) { }
	public record Function(String key, String name, List<String> inputs, List<String> outputs,
		String blockPath) { }
	public record Call(long hopId, String functionKey, List<String> inputNames, List<String> outputNames) { }
	private static List<String> callNames(String[] names) {
		return names == null ? null : Collections.unmodifiableList(Arrays.asList(names.clone()));
	}
	public record InputBoundary(int position, int statementPosition, String formal,
		String bound, String actual) { }
	public record OutputBoundary(int position, int statementPosition, String formal,
		String bound, String target) { }
	public record InlinedCall(String blockPath, String functionKey, int statementPosition,
		List<InputBoundary> inputs, List<OutputBoundary> outputs) { }

	private final List<Block> blocks;
	private final List<Root> roots;
	private final Map<Long, Node> nodes;
	private final List<Edge> edges;
	private final List<Function> functions;
	private final List<Call> calls;
	private final List<InlinedCall> inlinedCalls;

	private PrebuilderSnapshot(Builder builder) {
		blocks = List.copyOf(builder.blocks);
		roots = List.copyOf(builder.roots);
		nodes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.nodes));
		edges = List.copyOf(builder.edges);
		functions = List.copyOf(builder.functions);
		calls = List.copyOf(builder.calls);
		inlinedCalls = List.copyOf(builder.inlinedCalls);
	}

	public List<Block> blocks() { return blocks; }
	public List<Root> roots() { return roots; }
	public Map<Long, Node> nodes() { return nodes; }
	public List<Edge> edges() { return edges; }
	public List<Function> functions() { return functions; }
	public List<Call> calls() { return calls; }
	public List<InlinedCall> inlinedCalls() { return inlinedCalls; }

	static InlinedCall inlinedCall(String path, StatementBlock.InlinedFunctionCallBoundary raw) {
		return new InlinedCall(path, raw.functionKey(), raw.callStatementPosition(),
			raw.inputs().stream().map(input -> new InputBoundary(input.position(),
				input.statementPosition(), input.formalVariable(), input.boundVariable(),
				input.actualVariable())).toList(),
			raw.outputs().stream().map(output -> new OutputBoundary(output.position(),
				output.statementPosition(), output.formalVariable(), output.boundVariable(),
				output.targetVariable())).toList());
	}

	/** Source metadata is frozen by the caller; a missing federated source fails closed. */
	public static PrebuilderSnapshot capture(DMLProgram program, Map<Long, ExternalSource> sources) {
		Objects.requireNonNull(program);
		Objects.requireNonNull(sources);
		Builder builder = new Builder(Map.copyOf(sources));
		builder.blocks(program.getStatementBlocks(), "main");
		program.getNamedNSFunctionStatementBlocks().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				String path = "function/" + entry.getKey();
				FunctionStatement statement = (FunctionStatement) entry.getValue().getStatement(0);
				builder.functions.add(new Function(entry.getKey(), statement.getName(),
					statement.getInputParams().stream().map(p -> p.getName()).toList(),
					statement.getOutputParams().stream().map(p -> p.getName()).toList(), path));
				builder.block(entry.getValue(), path);
			});
		if(!builder.seenSources.equals(sources.keySet()))
			throw new IllegalArgumentException("External source metadata has unknown HOP IDs: " + sources.keySet());
		return new PrebuilderSnapshot(builder);
	}

	private static Location location(StatementBlock block) {
		return new Location(block.getFilename(), block.getBeginLine(), block.getBeginColumn(),
			block.getEndLine(), block.getEndColumn());
	}
	private static Location location(Hop hop) {
		return new Location(hop.getFilename(), hop.getBeginLine(), hop.getBeginColumn(),
			hop.getEndLine(), hop.getEndColumn());
	}

	private static final class Builder {
		private final Map<Long, ExternalSource> sources;
		private final List<Block> blocks = new ArrayList<>();
		private final List<Root> roots = new ArrayList<>();
		private final Map<Long, Node> nodes = new LinkedHashMap<>();
		private final List<Edge> edges = new ArrayList<>();
		private final List<Function> functions = new ArrayList<>();
		private final List<Call> calls = new ArrayList<>();
		private final List<InlinedCall> inlinedCalls = new ArrayList<>();
		private final Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Set<Long> seenSources = new java.util.HashSet<>();

		private Builder(Map<Long, ExternalSource> sources) { this.sources = sources; }

		private void blocks(List<StatementBlock> sequence, String path) {
			if(sequence == null) return;
			for(int i = 0; i < sequence.size(); i++) block(sequence.get(i), path + "/" + i);
		}

		private void block(StatementBlock block, String path) {
			blocks.add(new Block(path, block.getSBID(), block.getClass().getSimpleName(),
				block.isRecompileOnce(), location(block)));
			for(StatementBlock.InlinedFunctionCallBoundary call : block.getInlinedFunctionCallBoundaries())
				inlinedCalls.add(inlinedCall(path, call));
			addRoots(block.getHops(), path, "body");
			if(block instanceof IfStatementBlock ifBlock) {
				addRoot(ifBlock.getPredicateHops(), path, "predicate", 0);
				IfStatement statement = (IfStatement) block.getStatement(0);
				blocks(statement.getIfBody(), path + "/if");
				blocks(statement.getElseBody(), path + "/else");
			}
			else if(block instanceof WhileStatementBlock whileBlock) {
				addRoot(whileBlock.getPredicateHops(), path, "predicate", 0);
				blocks(((WhileStatement) block.getStatement(0)).getBody(), path + "/while");
			}
			else if(block instanceof ForStatementBlock forBlock) {
				addRoot(forBlock.getFromHops(), path, "from", 0);
				addRoot(forBlock.getToHops(), path, "to", 0);
				addRoot(forBlock.getIncrementHops(), path, "increment", 0);
				blocks(((ForStatement) block.getStatement(0)).getBody(), path + "/for");
			}
			else if(block instanceof FunctionStatementBlock functionBlock)
				blocks(((FunctionStatement) functionBlock.getStatement(0)).getBody(), path + "/body");
		}

		private void addRoots(List<Hop> values, String path, String role) {
			if(values == null) return;
			for(int i = 0; i < values.size(); i++) addRoot(values.get(i), path, role, i);
		}
		private void addRoot(Hop hop, String path, String role, int index) {
			if(hop == null) return;
			roots.add(new Root(path, role, index, hop.getHopID()));
			walk(hop);
		}
		private void walk(Hop hop) {
			if(!visited.add(hop)) return;
			long id = hop.getHopID();
			ExternalSource source = sources.get(id);
			boolean federatedSource = hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED;
			if(federatedSource && source == null)
				throw new IllegalArgumentException("Missing explicit federated source metadata for HOP " + id);
			if(source != null) seenSources.add(id);
			Node node = new Node(id, hop.getClass().getSimpleName(), hop.getOpString(), hop.getName(),
				String.valueOf(hop.getDataType()), String.valueOf(hop.getValueType()), hop.getDim1(),
				hop.getDim2(), hop.requiresRecompile(), location(hop), source);
			if(nodes.putIfAbsent(id, node) != null)
				throw new IllegalArgumentException("Distinct HOP objects share ID " + id);
			if(hop instanceof FunctionOp call) {
				calls.add(new Call(id, call.getFunctionKey(), callNames(call.getInputVariableNames()),
					callNames(call.getOutputVariableNames())));
			}
			for(int i = 0; i < hop.getInput().size(); i++) {
				Hop child = hop.getInput(i);
				edges.add(new Edge(id, i, child.getHopID()));
				walk(child);
			}
			if(hop instanceof FunctionOp call && call.getOutputs() != null)
				for(int i = 0; i < call.getOutputs().size(); i++)
					addRoot(call.getOutputs().get(i), "call/" + id, "output", i);
		}
	}
}
