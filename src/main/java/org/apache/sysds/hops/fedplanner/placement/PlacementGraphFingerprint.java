/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
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

/** Deterministic, read-only fingerprint of the compiled Hop graph. */
public final class PlacementGraphFingerprint {
	private PlacementGraphFingerprint() { }

	public static String capture(DMLProgram program) {
		List<String> rows = new ArrayList<>();
		walkBlocks(program.getStatementBlocks(), "main", rows, null, "main");
		program.getNamedNSFunctionStatementBlocks().entrySet().stream()
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(e -> walkBlock(e.getValue(), "function/" + e.getKey(), rows, null, e.getKey()));
		Collections.sort(rows);
		return sha256(String.join("\n", rows));
	}

	static record HopOccurrence(Hop hop, String path, String namespace, StatementBlock block,
		List<String> regionPath, String topology) { }

	static List<HopOccurrence> orderedOccurrences(DMLProgram program) {
		List<HopOccurrence> result = new ArrayList<>();
		List<String> ignored = new ArrayList<>();
		walkBlocks(program.getStatementBlocks(), "main", ignored, result, "main");
		program.getNamedNSFunctionStatementBlocks().entrySet().stream()
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(e -> walkBlock(e.getValue(), "function/" + e.getKey(), ignored, result, e.getKey()));
		return result;
	}

	static List<Long> statementBlockIds(DMLProgram program) {
		Set<Long> ids = new java.util.TreeSet<>();
		// Registries use the default scope for entries created outside an assigned statement block.
		ids.add(-1L);
		collectBlockIds(program.getStatementBlocks(), ids);
		for(FunctionStatementBlock block : program.getNamedNSFunctionStatementBlocks().values())
			collectBlockIds(List.of(block), ids);
		return new ArrayList<>(ids);
	}

	private static void collectBlockIds(List<StatementBlock> blocks, Set<Long> ids) {
		for(StatementBlock sb : blocks) {
			ids.add(sb.getSBID());
			if(sb instanceof FunctionStatementBlock) collectBlockIds(((FunctionStatement) sb.getStatement(0)).getBody(), ids);
			else if(sb instanceof WhileStatementBlock) collectBlockIds(((WhileStatement) sb.getStatement(0)).getBody(), ids);
			else if(sb instanceof ForStatementBlock) collectBlockIds(((ForStatement) sb.getStatement(0)).getBody(), ids);
			else if(sb instanceof IfStatementBlock) {
				IfStatement stmt = (IfStatement) sb.getStatement(0);
				collectBlockIds(stmt.getIfBody(), ids); collectBlockIds(stmt.getElseBody(), ids);
			}
		}
	}

	private static void walkBlocks(List<StatementBlock> blocks, String path, List<String> rows,
		List<HopOccurrence> out, String namespace) {
		for(int i = 0; blocks != null && i < blocks.size(); i++)
			walkBlock(blocks.get(i), path + "/" + i, rows, out, namespace);
	}

	private static void walkBlock(StatementBlock sb, String path, List<String> rows,
		List<HopOccurrence> out, String namespace) {
		List<Hop> roots = new ArrayList<>();
		if(sb.getHops() != null) roots.addAll(sb.getHops());
		if(sb instanceof IfStatementBlock) roots.add(((IfStatementBlock) sb).getPredicateHops());
		if(sb instanceof WhileStatementBlock) roots.add(((WhileStatementBlock) sb).getPredicateHops());
		if(sb instanceof ForStatementBlock) {
			roots.add(((ForStatementBlock) sb).getFromHops());
			roots.add(((ForStatementBlock) sb).getToHops());
			roots.add(((ForStatementBlock) sb).getIncrementHops());
		}
		roots.removeIf(java.util.Objects::isNull);
		roots.sort(java.util.Comparator.comparing(PlacementGraphFingerprint::semanticStructuralKey));
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(int i = 0; i < roots.size(); i++)
			walkHop(roots.get(i), path, namespace, sb, List.of(path), "root-" + i, rows, out, seen);
		if(sb instanceof FunctionStatementBlock)
			walkBlocks(((FunctionStatement) sb.getStatement(0)).getBody(), path + "/body", rows, out, namespace);
		else if(sb instanceof WhileStatementBlock)
			walkBlocks(((WhileStatement) sb.getStatement(0)).getBody(), path + "/loop-body", rows, out, namespace);
		else if(sb instanceof ForStatementBlock)
			walkBlocks(((ForStatement) sb.getStatement(0)).getBody(), path + "/loop-body", rows, out, namespace);
		else if(sb instanceof IfStatementBlock) {
			IfStatement stmt = (IfStatement) sb.getStatement(0);
			walkBlocks(stmt.getIfBody(), path + "/branch-if", rows, out, namespace);
			walkBlocks(stmt.getElseBody(), path + "/branch-else", rows, out, namespace);
		}
	}

	private static void walkHop(Hop hop, String path, String namespace, StatementBlock block,
		List<String> regionPath, String topology, List<String> rows, List<HopOccurrence> out, Set<Hop> seen) {
		if(!seen.add(hop)) return;
		for(int i = 0; i < hop.getInput().size(); i++)
			walkHop(hop.getInput(i), path, namespace, block, regionPath, topology + "/input-" + i, rows, out, seen);
		if(hop instanceof FunctionOp) {
			List<Hop> outputs = ((FunctionOp) hop).getOutputs();
			for(int i = 0; outputs != null && i < outputs.size(); i++) {
				Hop output = outputs.get(i);
				if(output != null)
					walkHop(output, path, namespace, block, regionPath,
						topology + "/function-output-" + i, rows, out, seen);
			}
		}
		List<String> inputs = new ArrayList<>();
		for(int i = 0; i < hop.getInput().size(); i++) inputs.add(i + ":" + structuralKey(hop.getInput(i)));
		List<String> parents = new ArrayList<>();
		for(Hop parent : hop.getParent()) parents.add(structuralKey(parent));
		Collections.sort(parents);
		rows.add(path + '|' + topology + '|' + structuralKey(hop) + '|' + String.join(",", inputs) + '|'
			+ String.join(",", parents) + '|' + hop.getExecType() + '|' + hop.getForcedExecType() + '|'
			+ hop.getFederatedOutput() + '|' + hop.isFederatedOutputDerived() + '|'
			+ hop.requiresRecompile() + '|' + hop.isVisited());
		if(out != null) out.add(new HopOccurrence(hop, path, namespace, block, regionPath, topology));
	}

	static String semanticStructuralKey(Hop h) {
		String name = normalizeCompilerCounters(h.getName());
		String op = normalizeCompilerCounters(h.getOpString());
		return String.valueOf(h.getFilename()) + ':' + h.getBeginLine() + ':' + h.getBeginColumn() + ':'
			+ h.getClass().getName() + ':' + op + ':' + name;
	}

	private static String normalizeCompilerCounters(String value) {
		return value == null ? "" : value.replaceFirst("^[0-9]+_", "compiler-id_")
			.replaceAll("parsertemp[0-9]+", "compiler-temp")
			.replaceAll("__(tmp|pred)[0-9]+", "__$1");
	}

	static String structuralKey(Hop h) {
		return String.valueOf(h.getFilename()) + ':' + h.getBeginLine() + ':' + h.getBeginColumn() + ':'
			+ h.getClass().getName() + ':' + h.getOpString() + ':' + h.getName();
	}

	static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for(byte b : digest) sb.append(String.format("%02x", b));
			return sb.toString();
		}
		catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
}
