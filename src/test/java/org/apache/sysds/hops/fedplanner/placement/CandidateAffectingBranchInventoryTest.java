/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.Assert;
import org.junit.Test;

/** Locks the complete control/filter/merge/projection surface used by the G009 branch audit. */
public class CandidateAffectingBranchInventoryTest {
	private static final List<String> SOURCES = List.of(
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java",
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/NativePlacementContinuity.java",
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java",
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/RelocationSelections.java",
		"src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementIdentity.java");
	private static final Path MANIFEST = Path.of(
		"src/test/resources/org/apache/sysds/hops/fedplanner/placement/candidate-affecting-branches.tsv");

	@Test
	public void everyCandidateAffectingBranchMatchesTheReviewedManifest() throws Exception {
		List<Row> actual = scan(Path.of("."));
		List<String> expected = Files.readAllLines(MANIFEST, StandardCharsets.UTF_8).stream()
			.filter(line -> !line.isBlank() && !line.startsWith("#"))
			.map(CandidateAffectingBranchInventoryTest::manifestKey).toList();
		List<String> observed = actual.stream().map(Row::manifestKey).toList();
		if(!expected.equals(observed)) {
			List<String> removed = expected.stream().filter(row -> !observed.contains(row)).limit(20).toList();
			List<String> added = observed.stream().filter(row -> !expected.contains(row)).limit(20).toList();
			Assert.fail("Candidate-affecting branch inventory changed; regenerate the manifest and classify every "
				+ "added/removed branch before accepting the change"
				+ "|expected=" + expected.size() + "|observed=" + observed.size()
				+ "|removed(first20)=" + removed + "|added(first20)=" + added);
		}
	}

	public static void main(String[] args) throws Exception {
		Path root = Path.of(".");
		Path output = args.length == 0 ? MANIFEST : Path.of(args[0]);
		Files.createDirectories(output.getParent());
		List<String> lines = new ArrayList<>();
		lines.add("# id\tfile\tline_start\tline_end\tkind\tmethod\tsnippet_sha256");
		scan(root).stream().map(Row::tsv).forEach(lines::add);
		Files.write(output, lines, StandardCharsets.UTF_8);
	}

	static List<Row> scan(Path root) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if(compiler == null)
			throw new IllegalStateException("A JDK compiler is required for the branch inventory");
		List<Path> paths = SOURCES.stream().map(root::resolve).toList();
		try(StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(paths);
			JavacTask task = (JavacTask)compiler.getTask(null, files, null,
				List.of("-proc:none"), null, units);
			Trees trees = Trees.instance(task);
			List<Row> rows = new ArrayList<>();
			for(CompilationUnitTree unit : task.parse())
				new Scanner(root, unit, trees.getSourcePositions(), rows).scan(unit, null);
			rows.sort(Comparator.comparing(Row::file).thenComparingLong(Row::lineStart)
				.thenComparing(Row::kind).thenComparing(Row::id));
			return List.copyOf(rows);
		}
	}

	private static final class Scanner extends TreePathScanner<Void,Void> {
		private final Path root;
		private final CompilationUnitTree unit;
		private final SourcePositions positions;
		private final List<Row> rows;
		private final Deque<String> methods = new ArrayDeque<>();
		private final Map<String,Integer> ordinals = new HashMap<>();
		private final String source;
		private final String file;

		private Scanner(Path root, CompilationUnitTree unit, SourcePositions positions, List<Row> rows)
			throws IOException {
			this.root = root.toAbsolutePath().normalize();
			this.unit = unit;
			this.positions = positions;
			this.rows = rows;
			Path path = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
			file = this.root.relativize(path).toString();
			source = Files.readString(path);
		}

		@Override public Void visitMethod(MethodTree tree, Void unused) {
			methods.push(tree.getName().toString());
			try { return super.visitMethod(tree, unused); }
			finally { methods.pop(); }
		}

		@Override public Void visitIf(IfTree tree, Void unused) {
			add("IF", tree, tree.getCondition().toString()); return super.visitIf(tree, unused);
		}
		@Override public Void visitSwitch(SwitchTree tree, Void unused) {
			add("SWITCH", tree, tree.getExpression().toString()); return super.visitSwitch(tree, unused);
		}
		@Override public Void visitSwitchExpression(SwitchExpressionTree tree, Void unused) {
			add("SWITCH_EXPRESSION", tree, tree.getExpression().toString());
			return super.visitSwitchExpression(tree, unused);
		}
		@Override public Void visitConditionalExpression(ConditionalExpressionTree tree, Void unused) {
			add("TERNARY", tree, tree.getCondition().toString());
			return super.visitConditionalExpression(tree, unused);
		}
		@Override public Void visitBinary(BinaryTree tree, Void unused) {
			if(tree.getKind() == com.sun.source.tree.Tree.Kind.CONDITIONAL_AND
				|| tree.getKind() == com.sun.source.tree.Tree.Kind.CONDITIONAL_OR)
				add("SHORT_CIRCUIT", tree, tree.toString());
			return super.visitBinary(tree, unused);
		}
		@Override public Void visitForLoop(ForLoopTree tree, Void unused) {
			add("FOR", tree, tree.getCondition() == null ? "true" : tree.getCondition().toString());
			return super.visitForLoop(tree, unused);
		}
		@Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
			add("ENHANCED_FOR", tree, tree.getExpression().toString());
			return super.visitEnhancedForLoop(tree, unused);
		}
		@Override public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
			add("WHILE", tree, tree.getCondition().toString()); return super.visitWhileLoop(tree, unused);
		}
		@Override public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
			add("DO_WHILE", tree, tree.getCondition().toString()); return super.visitDoWhileLoop(tree, unused);
		}
		@Override public Void visitCase(CaseTree tree, Void unused) {
			add("CASE", tree, tree.getExpressions().toString()); return super.visitCase(tree, unused);
		}
		@Override public Void visitContinue(ContinueTree tree, Void unused) {
			add("CONTINUE", tree, tree.toString()); return super.visitContinue(tree, unused);
		}
		@Override public Void visitBreak(BreakTree tree, Void unused) {
			add("BREAK", tree, tree.toString()); return super.visitBreak(tree, unused);
		}
		@Override public Void visitReturn(ReturnTree tree, Void unused) {
			add("RETURN", tree, tree.toString()); return super.visitReturn(tree, unused);
		}
		@Override public Void visitThrow(ThrowTree tree, Void unused) {
			add("THROW", tree, tree.toString()); return super.visitThrow(tree, unused);
		}
		@Override public Void visitCatch(CatchTree tree, Void unused) {
			add("CATCH", tree, tree.getParameter().getType().toString()); return super.visitCatch(tree, unused);
		}
		@Override public Void visitTry(TryTree tree, Void unused) {
			add("TRY", tree, "resources=" + tree.getResources().size() + "|catches=" + tree.getCatches().size()
				+ "|finally=" + (tree.getFinallyBlock() != null));
			return super.visitTry(tree, unused);
		}
		@Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
			String select = tree.getMethodSelect().toString();
			String name = select.substring(select.lastIndexOf('.') + 1);
			if("filter".equals(name)) add("FILTER", tree, tree.toString());
			else if(List.of("merge", "putIfAbsent", "computeIfAbsent", "computeIfPresent", "replaceAll", "addAll")
				.contains(name)) add("MERGE", tree, tree.toString());
			else if(name.toLowerCase(Locale.ROOT).contains("project")
				|| List.of("map", "flatMap", "mapMulti").contains(name))
				add("PROJECTION", tree, tree.toString());
			return super.visitMethodInvocation(tree, unused);
		}

		private void add(String kind, com.sun.source.tree.Tree tree, String identityText) {
			long start = positions.getStartPosition(unit, tree);
			long end = positions.getEndPosition(unit, tree);
			if(start < 0 || end < start) return;
			String method = methods.isEmpty() ? "<initializer>" : methods.peek();
			String normalized = identityText.replaceAll("\\s+", " ").trim();
			String ordinalKey = method + '|' + kind + '|' + normalized;
			int ordinal = ordinals.merge(ordinalKey, 1, Integer::sum);
			String fingerprint = file + '|' + method + '|' + kind + '|' + normalized + '|' + ordinal;
			long lineStart = unit.getLineMap().getLineNumber(start);
			long lineEnd = unit.getLineMap().getLineNumber(Math.max(start, end - 1));
			rows.add(new Row("BR-" + sha256(fingerprint).substring(0, 16), file, lineStart, lineEnd,
				kind, method, sha256(source.substring((int)start, (int)end).replaceAll("\\s+", " ").trim())));
		}
	}

	private record Row(String id, String file, long lineStart, long lineEnd, String kind,
		String method, String snippetHash) {
		String tsv() {
			return String.join("\t", id, file, String.valueOf(lineStart), String.valueOf(lineEnd),
				kind, method, snippetHash);
		}
		String manifestKey() { return String.join("\t", id, file, kind, method, snippetHash); }
	}

	private static String manifestKey(String line) {
		String[] fields = line.split("\t", -1);
		if(fields.length != 7)
			throw new IllegalArgumentException("Malformed branch manifest row: " + line);
		return String.join("\t", fields[0], fields[1], fields[4], fields[5], fields[6]);
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(digest.length * 2);
			for(byte b : digest) out.append(String.format("%02x", b));
			return out.toString();
		}
		catch(Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
