/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic ownership BFS over planner imports, constructors, collaborators and package helpers. */
final class CampaignBPlannerOwnershipClosure {
	record Unit(String fqcn, String pkg, Path path, List<JavaSourceTokenScanner.Token> tokens,
		Map<String,String> imports, Set<String> declaredTypes) { }
	private static final Pattern PKG = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
	private static final Pattern IMP = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");
	private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
	private static final Set<String> FORBIDDEN = Set.of("oraclefacade", "rulescore",
		"federatedplanminstgraph", "enumerateprogram", "enumeratefunctiondynamic", "exactselectororacle",
		"neutralplacementgraphbuilder", "buildanalysis", "orderedoccurrences", "placementgraphfingerprint",
		"getprogramblocks", "getstatementblocks", "executioncontext", "federatedworker", "fallback", "greedy",
		"approximate", "truncate", "timeout", "partialsuccess", "systemgetenv", "getproperty",
		"nanotime", "currenttimemillis");

	static Map<String,Unit> index(Path root) throws IOException {
		Map<String,Unit> out = new LinkedHashMap<>();
		try(var paths = Files.walk(root)) {
			for(Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
				String source = Files.readString(path); Matcher pm = PKG.matcher(source);
				if(!pm.find()) continue;
				String pkg = pm.group(1), primary = path.getFileName().toString().replaceFirst("\\.java$", "");
				Map<String,String> imports = new LinkedHashMap<>(); Matcher im = IMP.matcher(source);
				while(im.find()) { String f = im.group(1); int dot = f.lastIndexOf('.'); if(dot > 0) imports.put(f.substring(dot + 1), f); }
				Set<String> declared = new LinkedHashSet<>(); Matcher tm = TYPE.matcher(source); while(tm.find()) declared.add(tm.group(1));
				Unit unit = new Unit(pkg + '.' + primary, pkg, path, JavaSourceTokenScanner.tokens(source),
					Map.copyOf(imports), Set.copyOf(declared));
				if(out.put(unit.fqcn(), unit) != null) throw new AssertionError("R4_OWNERSHIP_AMBIGUOUS|" + unit.fqcn());
			}
		}
		return Map.copyOf(out);
	}

	static List<Unit> closure(String root, Map<String,Unit> index) {
		Map<String,List<String>> bySimple = new LinkedHashMap<>();
		for(String fqcn : index.keySet()) bySimple.computeIfAbsent(simple(fqcn), k -> new ArrayList<>()).add(fqcn);
		ArrayDeque<String> queue = new ArrayDeque<>(); Set<String> seen = new LinkedHashSet<>(); queue.add(root);
		while(!queue.isEmpty()) {
			String name = queue.removeFirst(); if(!seen.add(name)) continue; Unit unit = index.get(name);
			if(unit == null) throw new AssertionError("R4_OWNERSHIP_POSITIVE|missing=" + name);
			TreeSet<String> next = new TreeSet<>();
			for(JavaSourceTokenScanner.Token token : unit.tokens()) {
				String id = token.text(); if(id.isEmpty() || !Character.isJavaIdentifierStart(id.charAt(0))) continue;
				String imported = unit.imports().get(id); if(imported != null && index.containsKey(imported)) next.add(imported);
				String same = unit.pkg() + '.' + id; if(index.containsKey(same)) next.add(same);
				List<String> matches = bySimple.getOrDefault(id, List.of());
				if(matches.size() == 1 && matches.get(0).startsWith("org.apache.sysds.hops.fedplanner.")) next.add(matches.get(0));
				else if(matches.size() > 1 && (unit.imports().containsKey(id) || index.containsKey(same)))
					throw new AssertionError("R4_OWNERSHIP_AMBIGUOUS|type=" + id + "|owner=" + unit.fqcn());
			}
			queue.addAll(next);
		}
		List<Unit> out = new ArrayList<>(); for(String name : seen) out.add(index.get(name));
		out.sort(Comparator.comparing((Unit u) -> u.path().toString()).thenComparing(Unit::fqcn));
		return List.copyOf(out);
	}

	static List<String> violations(List<Unit> closure) {
		Set<String> unique = new TreeSet<>();
		for(Unit unit : closure) for(JavaSourceTokenScanner.Token token : unit.tokens()) {
			String n = token.text().replace("_", "").toLowerCase();
			if(FORBIDDEN.contains(n)) unique.add(unit.path() + ":" + token.line() + "|" + n);
		}
		return List.copyOf(unique);
	}

	static void assertPositiveAdapterBoundary(List<Unit> closure, String adapterSimpleName) {
		Unit adapter = closure.stream().filter(u -> u.declaredTypes().contains(adapterSimpleName)).findFirst()
			.orElseThrow(() -> new AssertionError("R4_OWNERSHIP_POSITIVE|missingAdapter=" + adapterSimpleName));
		List<String> ids = adapter.tokens().stream().map(JavaSourceTokenScanner.Token::text).toList();
		if(!ids.contains("select") || !ids.contains("PlacementAnalysis"))
			throw new AssertionError("R4_OWNERSHIP_POSITIVE|adapterInput=" + adapterSimpleName);
	}

	private static String simple(String fqcn) { return fqcn.substring(fqcn.lastIndexOf('.') + 1); }
	private CampaignBPlannerOwnershipClosure() { }
}
