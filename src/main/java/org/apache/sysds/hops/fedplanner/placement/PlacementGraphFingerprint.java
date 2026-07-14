/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

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
		walkBlocks(program.getStatementBlocks(), "main", rows);
		program.getNamedNSFunctionStatementBlocks().entrySet().stream()
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(e -> walkBlock(e.getValue(), "function/" + e.getKey(), rows));
		Collections.sort(rows);
		return sha256(String.join("\n", rows));
	}

	static List<Hop> orderedHops(DMLProgram program) {
		List<Hop> result = new ArrayList<>();
		List<String> ignored = new ArrayList<>();
		walkBlocks(program.getStatementBlocks(), "main", ignored, result);
		program.getNamedNSFunctionStatementBlocks().entrySet().stream()
			.sorted(java.util.Map.Entry.comparingByKey())
			.forEach(e -> walkBlock(e.getValue(), "function/" + e.getKey(), ignored, result));
		return result;
	}

	private static void walkBlocks(List<StatementBlock> blocks, String path, List<String> rows) {
		walkBlocks(blocks, path, rows, null);
	}

	private static void walkBlocks(List<StatementBlock> blocks, String path, List<String> rows, List<Hop> out) {
		for(int i = 0; blocks != null && i < blocks.size(); i++)
			walkBlock(blocks.get(i), path + "/" + i, rows, out);
	}

	private static void walkBlock(StatementBlock sb, String path, List<String> rows) {
		walkBlock(sb, path, rows, null);
	}

	private static void walkBlock(StatementBlock sb, String path, List<String> rows, List<Hop> out) {
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
		roots.sort(java.util.Comparator.comparing(PlacementGraphFingerprint::structuralKey));
		Set<Hop> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Hop root : roots) walkHop(root, path, rows, out, seen);
		if(sb instanceof FunctionStatementBlock)
			walkBlocks(((FunctionStatement) sb.getStatement(0)).getBody(), path + "/body", rows, out);
		else if(sb instanceof WhileStatementBlock)
			walkBlocks(((WhileStatement) sb.getStatement(0)).getBody(), path + "/body", rows, out);
		else if(sb instanceof ForStatementBlock)
			walkBlocks(((ForStatement) sb.getStatement(0)).getBody(), path + "/body", rows, out);
		else if(sb instanceof IfStatementBlock) {
			IfStatement stmt = (IfStatement) sb.getStatement(0);
			walkBlocks(stmt.getIfBody(), path + "/if", rows, out);
			walkBlocks(stmt.getElseBody(), path + "/else", rows, out);
		}
	}

	private static void walkHop(Hop hop, String path, List<String> rows, List<Hop> out, Set<Hop> seen) {
		if(!seen.add(hop)) return;
		for(Hop input : hop.getInput()) walkHop(input, path, rows, out, seen);
		rows.add(path + '|' + structuralKey(hop) + '|' + hop.getExecType() + '|' + hop.getFederatedOutput());
		if(out != null) out.add(hop);
	}

	static String structuralKey(Hop h) {
		return String.valueOf(h.getFilename()) + ':' + h.getBeginLine() + ':' + h.getBeginColumn() + ':'
			+ h.getClass().getName() + ':' + h.getOpString() + ':' + h.getName() + ':' + h.getHopID();
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
