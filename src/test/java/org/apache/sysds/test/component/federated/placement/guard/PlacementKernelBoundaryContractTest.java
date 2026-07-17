/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;

/** Verification-only source dependency boundary for the neutral placement kernel. */
public class PlacementKernelBoundaryContractTest {
	private static final String PREFIX = "org.apache.sysds.hops.fedplanner.";
	private static final List<String> ROOTS = List.of(
		PREFIX + "placement.PlacementState",
		PREFIX + "placement.PlacementIdentity",
		PREFIX + "placement.NeutralPlacementGraph",
		PREFIX + "placement.PlacementGraphFingerprint",
		PREFIX + "placement.PlacementShapeFacts",
		PREFIX + "placement.PlacementAnalysis",
		PREFIX + "placement.PlacementShadowComparator");
	private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

	@Test
	public void neutralRootsHaveNoForbiddenReachability() throws Exception {
		Path repository = Paths.get("").toAbsolutePath().normalize();
		Index index = index(repository.resolve("src/main/java/org/apache/sysds/hops/fedplanner"));
		List<String> defects = new ArrayList<>();
		for(String root : ROOTS)
			if(!index.units.containsKey(root))
				defects.add("A4_SCANNER_UNRESOLVED|root=" + root);
		Assert.assertTrue(String.join("\n", defects), defects.isEmpty());

		List<String> violations = new ArrayList<>();
		for(String root : ROOTS)
			violations.addAll(boundaryPaths(root, index, repository));
		Collections.sort(violations);
		Assert.assertTrue(String.join("\n", violations), violations.isEmpty());
	}

	@Test
	public void scannerIgnoresCommentsStringsCharsAndRetainsRealLocations() {
		String source = "// FederatedPlannerUtils\n"
			+ "String text = \"FederatedCostModel NeutralPlacementGraphBuilder\";\n"
			+ "char quote = '\\''; /* FederatedPlanMinSTGraph */\n"
			+ "FederatedPlannerTrace real;\n";
		List<JavaSourceTokenScanner.Token> tokens = JavaSourceTokenScanner.tokens(source);
		List<String> identifiers = tokens.stream().map(JavaSourceTokenScanner.Token::text).toList();
		Assert.assertFalse(identifiers.contains("FederatedPlannerUtils"));
		Assert.assertFalse(identifiers.contains("FederatedCostModel"));
		Assert.assertFalse(identifiers.contains("NeutralPlacementGraphBuilder"));
		Assert.assertFalse(identifiers.contains("FederatedPlanMinSTGraph"));
		JavaSourceTokenScanner.Token real = tokens.stream()
			.filter(token -> token.text().equals("FederatedPlannerTrace")).findFirst().orElseThrow();
		Assert.assertEquals(4, real.line());
	}

	private static Index index(Path plannerRoot) throws IOException {
		Map<String, Unit> units = new TreeMap<>();
		Map<String, String> typeOwners = new TreeMap<>();
		Map<String, Set<String>> simpleOwners = new TreeMap<>();
		try(var paths = Files.walk(plannerRoot)) {
			for(Path path : paths.filter(Files::isRegularFile)
				.filter(value -> value.toString().endsWith(".java")).sorted().toList()) {
				String source = Files.readString(path);
				List<JavaSourceTokenScanner.Token> tokens = JavaSourceTokenScanner.tokens(source);
				String pkg = declaredPackage(tokens);
				if(pkg == null || !pkg.startsWith(PREFIX.substring(0, PREFIX.length() - 1)))
					continue;
				String primary = path.getFileName().toString().replaceFirst("\\.java$", "");
				String fqcn = pkg + '.' + primary;
				Set<String> declarations = new TreeSet<>();
				Matcher matcher = TYPE.matcher(tokensAsSource(tokens));
				while(matcher.find())
					declarations.add(matcher.group(1));
				Unit unit = new Unit(fqcn, pkg, path, tokens, declarations);
				if(units.put(fqcn, unit) != null)
					throw new AssertionError("A4_SCANNER_AMBIGUOUS|unit=" + fqcn);
			}
		}
		for(Unit unit : units.values()) {
			typeOwners.put(unit.fqcn, unit.fqcn);
			for(String declaration : unit.declarations) {
				typeOwners.put(unit.fqcn + '.' + declaration, unit.fqcn);
				simpleOwners.computeIfAbsent(declaration, ignored -> new TreeSet<>()).add(unit.fqcn);
			}
		}
		return new Index(Map.copyOf(units), Map.copyOf(typeOwners), copySets(simpleOwners));
	}

	private static List<String> boundaryPaths(String root, Index index, Path repository) {
		ArrayDeque<String> queue = new ArrayDeque<>();
		Map<String, Step> reached = new LinkedHashMap<>();
		reached.put(root, null);
		queue.add(root);
		List<String> violations = new ArrayList<>();
		while(!queue.isEmpty()) {
			String owner = queue.removeFirst();
			Unit unit = index.units.get(owner);
			if(unit == null)
				throw new AssertionError("A4_SCANNER_UNRESOLVED|owner=" + owner);
			for(Edge edge : dependencies(unit, index)) {
				if(reached.containsKey(edge.target))
					continue;
				reached.put(edge.target, new Step(owner, edge));
				queue.addLast(edge.target);
				String category = forbiddenCategory(edge.target);
				if(category != null)
					violations.add(formatViolation(root, edge.target, category, reached, repository));
			}
		}
		return violations;
	}

	private static List<Edge> dependencies(Unit unit, Index index) {
		Map<String, Edge> edges = new TreeMap<>();
		List<JavaSourceTokenScanner.Token> tokens = unit.tokens;
		Map<String, String> imports = importedOwners(tokens, index);
		for(Map.Entry<String, String> entry : imports.entrySet())
			addEdge(edges, unit, entry.getValue(), lineOf(tokens, entry.getKey()));

		for(int i = 0; i < tokens.size(); i++) {
			JavaSourceTokenScanner.Token token = tokens.get(i);
			if(!identifier(token.text()))
				continue;
			if(token.text().equals("org")) {
				StringBuilder qualified = new StringBuilder(token.text());
				for(int j = i + 1; j + 1 < tokens.size() && tokens.get(j).text().equals(".")
					&& identifier(tokens.get(j + 1).text()); j += 2) {
					qualified.append('.').append(tokens.get(j + 1).text());
					String target = owningUnit(qualified.toString(), index);
					if(target != null)
						addEdge(edges, unit, target, token.line());
				}
			}
			String imported = imports.get(token.text());
			if(imported != null)
				addEdge(edges, unit, imported, token.line());
			String samePackage = owningUnit(unit.pkg + '.' + token.text(), index);
			if(samePackage != null)
				addEdge(edges, unit, samePackage, token.line());
			Set<String> owners = index.simpleOwners.getOrDefault(token.text(), Set.of());
			if(owners.size() == 1)
				addEdge(edges, unit, owners.iterator().next(), token.line());
			else if(owners.size() > 1 && (imported != null || samePackage != null))
				throw new AssertionError("A4_SCANNER_AMBIGUOUS|owner=" + unit.fqcn + "|type="
					+ token.text() + "|line=" + token.line());
		}
		edges.remove(unit.fqcn);
		return List.copyOf(edges.values());
	}

	private static Map<String, String> importedOwners(List<JavaSourceTokenScanner.Token> tokens, Index index) {
		Map<String, String> imports = new TreeMap<>();
		for(int i = 0; i < tokens.size(); i++) {
			if(!tokens.get(i).text().equals("import"))
				continue;
			int start = i;
			if(i + 1 < tokens.size() && tokens.get(i + 1).text().equals("static"))
				i++;
			StringBuilder name = new StringBuilder();
			while(++i < tokens.size() && !tokens.get(i).text().equals(";")) {
				String text = tokens.get(i).text();
				if(identifier(text)) {
					if(name.length() > 0)
						name.append('.');
					name.append(text);
				}
			}
			String qualified = name.toString();
			String owner = owningUnit(qualified, index);
			if(owner == null && qualified.startsWith(PREFIX))
				throw new AssertionError("A4_SCANNER_UNRESOLVED|ownerImport=" + qualified
					+ "|line=" + tokens.get(start).line());
			if(owner != null) {
				String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
				imports.put(simple, owner);
				imports.put(owner.substring(owner.lastIndexOf('.') + 1), owner);
			}
		}
		return imports;
	}

	private static String owningUnit(String qualified, Index index) {
		String candidate = qualified;
		while(candidate.startsWith(PREFIX.substring(0, PREFIX.length() - 1))) {
			String owner = index.typeOwners.get(candidate);
			if(owner != null)
				return owner;
			int dot = candidate.lastIndexOf('.');
			if(dot < 0)
				break;
			candidate = candidate.substring(0, dot);
		}
		return null;
	}

	private static void addEdge(Map<String, Edge> edges, Unit unit, String target, int line) {
		if(target != null && !target.equals(unit.fqcn))
			edges.putIfAbsent(target, new Edge(target, unit.path, line));
	}

	private static String forbiddenCategory(String fqcn) {
		if(fqcn.endsWith(".NeutralPlacementGraphBuilder") || fqcn.endsWith(".FederatedPlannerUtils")
			|| fqcn.endsWith(".FederatedRefedPolicy") || fqcn.endsWith(".OracleUtils"))
			return "G010";
		if(fqcn.endsWith(".FederatedCostModel") || fqcn.endsWith(".FederatedPlannerLogger")
			|| fqcn.endsWith(".FederatedPlannerTrace"))
			return "G011";
		if(fqcn.contains(".placement.adapter.") || fqcn.contains(".placement.selector.")
			|| fqcn.endsWith(".PlacementShadowCoordinator") || fqcn.endsWith(".AFederatedPlanner")
			|| fqcn.endsWith(".FederatedPlanMinSTGraph") || fqcn.endsWith(".FederatedPlanMinSTCut")
			|| fqcn.endsWith(".FederatedPlannerFedAll")
			|| fqcn.endsWith(".FederatedPlannerFedAllMaxFedFoutSinglePass")
			|| fqcn.endsWith(".FederatedPlannerFedHeuristic")
			|| fqcn.endsWith(".FederatedPlannerDpFedCostBased"))
			return "G012";
		return null;
	}

	private static String formatViolation(String root, String target, String category,
		Map<String, Step> reached, Path repository) {
		List<String> nodes = new ArrayList<>();
		List<String> locations = new ArrayList<>();
		String cursor = target;
		while(cursor != null) {
			nodes.add(cursor);
			Step step = reached.get(cursor);
			if(step == null)
				break;
			locations.add(repository.relativize(step.edge.path).toString() + ":" + step.edge.line);
			cursor = step.parent;
		}
		Collections.reverse(nodes);
		Collections.reverse(locations);
		return "A4_BOUNDARY|root=" + root + "|path=" + String.join("->", nodes)
			+ "|locations=" + String.join("->", locations) + "|category=" + category;
	}

	private static String declaredPackage(List<JavaSourceTokenScanner.Token> tokens) {
		for(int i = 0; i < tokens.size(); i++) {
			if(!tokens.get(i).text().equals("package"))
				continue;
			StringBuilder value = new StringBuilder();
			while(++i < tokens.size() && !tokens.get(i).text().equals(";")) {
				String text = tokens.get(i).text();
				if(identifier(text)) {
					if(value.length() > 0)
						value.append('.');
					value.append(text);
				}
			}
			return value.toString();
		}
		return null;
	}

	private static String tokensAsSource(List<JavaSourceTokenScanner.Token> tokens) {
		StringBuilder value = new StringBuilder();
		for(JavaSourceTokenScanner.Token token : tokens)
			value.append(token.text()).append(' ');
		return value.toString();
	}

	private static int lineOf(List<JavaSourceTokenScanner.Token> tokens, String simpleName) {
		return tokens.stream().filter(token -> token.text().equals(simpleName)).mapToInt(
			JavaSourceTokenScanner.Token::line).findFirst().orElse(1);
	}

	private static boolean identifier(String value) {
		return !value.isEmpty() && Character.isJavaIdentifierStart(value.charAt(0));
	}

	private static Map<String, Set<String>> copySets(Map<String, Set<String>> values) {
		Map<String, Set<String>> copy = new HashMap<>();
		for(Map.Entry<String, Set<String>> entry : values.entrySet())
			copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
		return Map.copyOf(copy);
	}

	private record Unit(String fqcn, String pkg, Path path,
		List<JavaSourceTokenScanner.Token> tokens, Set<String> declarations) { }
	private record Index(Map<String, Unit> units, Map<String, String> typeOwners,
		Map<String, Set<String>> simpleOwners) { }
	private record Edge(String target, Path path, int line) { }
	private record Step(String parent, Edge edge) { }
}
