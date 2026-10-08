/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataGenOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ForStatement;
import org.apache.sysds.parser.ForStatementBlock;
import org.apache.sysds.parser.FunctionStatement;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.parser.IfStatement;
import org.apache.sysds.parser.IfStatementBlock;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.parser.WhileStatement;
import org.apache.sysds.parser.WhileStatementBlock;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Permanent compiler inventory for the ten frozen ML training programs used by
 * the planning study. This test deliberately stops after Hop rewriting: it does
 * not construct placement closure, start a runtime, or execute a workload.
 */
public class TrainingWorkloadOperationCoverageTest {
	private static final String ROOT = "fedplanner/training-operations/";
	private static final Map<String,String> FIXTURES = Map.ofEntries(
		Map.entry("als", "ddb1ef62238821f579c366c71b81f8b4df49d002f2d7ce36ac87f20ba4315f12"),
		Map.entry("glm", "ca5ede0c62be882d66d9925ba8fd684960e5057550298317d1394f32784f9678"),
		Map.entry("gmm-vvi", "18a5ff9006984e8d3cc430cb153571cda398844b4e73fdf025622dc9eb1ffdee"),
		Map.entry("gnmf", "d6f5ad7189a8afb45f6cc1a5dc3cb369c73f2382ed01c309062f19fbc9d9e6e4"),
		Map.entry("kmeans", "8e8e3c92bbb1a4e603eca5d71150ee74813eb563663b0792616beed8df4fb4d6"),
		Map.entry("l2svm", "7a7b1b3d48883640e300f9014776ab3a4707864340e26d1d9d293b2ca64a7411"),
		Map.entry("lm", "c25f17ca5ae76c6e73750f1dbbac760b00889f15515a58bed42a431a068b8af8"),
		Map.entry("logreg", "cba5475e4bd4488a6f8229740d5c8114bfd9f1299e8672cd32ae76ddf8ea80a6"),
		Map.entry("pca", "486274b587590be8b63582b038381fc05e4dd3e290e3f9a9904f78f6a7d09c81"),
		Map.entry("steplm", "94ef02c2872c5927a4dc2986aa21539425260d1efbe8834300aaea12b6435a4b"));
	private static final List<String> CAMPAIGN_ORDER =
		List.of("pca", "als", "kmeans", "lm", "logreg", "l2svm", "steplm", "glm", "gnmf", "gmm-vvi");

	private static Map<String,CompiledFixture> compiled;
	private static String inventory;
	private static Map<String,Set<String>> oracleClassifications;
	private static Map<String,Boolean> observedCpOnlySamples;

	@BeforeClass
	public static void compileFrozenTrainingPrograms() throws Exception {
		compiled = new LinkedHashMap<>();
		StringBuilder rows = new StringBuilder(
			"workload\tphase\tordinal\tnamespace\tpath\ttopology\thop-class\top\tsemantic-origin\n");
		for(String workload : CAMPAIGN_ORDER) {
			DMLProgram beforeProgram = construct(workload);
			List<PlacementGraphFingerprint.HopOccurrence> before =
				List.copyOf(PlacementGraphFingerprint.orderedOccurrences(beforeProgram));
			append(rows, workload, "constructed", before);
			Set<Hop> predicatesBefore = predicateHops(beforeProgram);
			DMLProgram afterProgram = construct(workload);
			new DMLTranslator(afterProgram).rewriteHopsDAG(afterProgram);
			List<PlacementGraphFingerprint.HopOccurrence> after =
				List.copyOf(PlacementGraphFingerprint.orderedOccurrences(afterProgram));
			append(rows, workload, "rewritten", after);
			Set<Hop> predicatesAfter = predicateHops(afterProgram);
			compiled.put(workload, new CompiledFixture(before, after,
				predicatesBefore, predicatesAfter));
		}
		inventory = rows.toString();
	}

	@Test
	public void exactFrozenSourcesRetainDeclaredProvenance() throws Exception {
		Assert.assertEquals("campaign authority must remain exactly ten workloads", 10, FIXTURES.size());
		for(Map.Entry<String,String> fixture : FIXTURES.entrySet())
			Assert.assertEquals(fixture.getKey() + " fixture drift", fixture.getValue(),
				sha256(resource(fixture.getKey() + ".dml")));
		String provenance = new String(resource("SOURCE_SHA256SUMS.txt"), StandardCharsets.UTF_8);
		Assert.assertTrue("2100-column sources must be declared distinct from 128-column measurements",
			provenance.contains("50000x2100") && provenance.contains("50000x128")
				&& provenance.contains("distinct authoritative studies"));
		Assert.assertTrue("campaign inventory source hash must remain declared",
			provenance.contains("82206cf1b982ec35d532411f6ee97ab58185ffbff6da66fa0fde96525d0680f9"));
		for(Map.Entry<String,String> fixture : FIXTURES.entrySet())
			Assert.assertTrue("provenance manifest misses " + fixture.getKey(),
				provenance.contains(fixture.getValue() + "  " + fixture.getKey() + ".dml"));
	}

	@Test
	public void constructedAndRewrittenOccurrenceInventoryIsExact() throws Exception {
		Assert.assertEquals("compiler occurrence inventory drift; review every added/removed Hop",
			new String(resource("OPERATION_OCCURRENCES.tsv"), StandardCharsets.UTF_8), inventory);
	}

	@Test
	public void orderedInventoryCoversNestedFunctionsAndControlPredicates() {
		for(Map.Entry<String,CompiledFixture> entry : compiled.entrySet()) {
			CompiledFixture fixture = entry.getValue();
			assertPredicatesCovered(entry.getKey(), "constructed", fixture.before(), fixture.predicatesBefore());
			assertPredicatesCovered(entry.getKey(), "rewritten", fixture.after(), fixture.predicatesAfter());
			Assert.assertTrue(entry.getKey() + " must inventory a nested builtin function before rewriting",
				fixture.before().stream().anyMatch(occurrence -> !"main".equals(occurrence.namespace())));
			Assert.assertTrue(entry.getKey() + " must inventory a nested builtin function after rewriting",
				fixture.after().stream().anyMatch(occurrence -> !"main".equals(occurrence.namespace())));
		}
	}

	@Test
	public void everyObservedOperationFamilyIsPermanentlyClassified() throws Exception {
		classifyAndVerifyOracleRoutes();
		List<String> lines = new String(resource("OPERATION_FAMILIES.tsv"), StandardCharsets.UTF_8)
			.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
		Map<String,String> classified = new TreeMap<>();
		for(String line : lines) {
			String[] fields = line.split("\\t", -1);
			Assert.assertEquals("classification rows are family<TAB>disposition", 2, fields.length);
			Assert.assertNull("duplicate family " + fields[0], classified.put(fields[0], fields[1]));
		}
		Assert.assertEquals("every compiled family needs an explicit oracle route",
			observedFamilies(), classified.keySet());
		Map<String,String> actual = new TreeMap<>();
		oracleClassifications.forEach((family, routes) -> actual.put(family, String.join(",", routes)));
		Assert.assertEquals("operation-family oracle route drift", classified, actual);
		Assert.assertTrue("sample diagnostics retain CP-only observations without promoting them to routes",
			observedCpOnlySamples.values().stream().anyMatch(Boolean::booleanValue));
		Assert.assertFalse("bounded samples cannot prove a universal CP-only route",
			actual.values().stream().anyMatch(routes -> routes.contains("CP_ONLY")));
	}

	/** Emits reviewable evidence without invoking Maven or writing shared target/. */
	public static void main(String[] args) throws Exception {
		if(args.length != 1)
			throw new IllegalArgumentException("usage: TrainingWorkloadOperationCoverageTest <artifact-directory>");
		compileFrozenTrainingPrograms();
		Path output = Path.of(args[0]);
		Files.createDirectories(output);
		Files.writeString(output.resolve("operation-occurrences.tsv"), inventory, StandardCharsets.UTF_8);
		Files.writeString(output.resolve("observed-operation-families.txt"),
			String.join("\n", observedFamilies()) + "\n", StandardCharsets.UTF_8);
		classifyAndVerifyOracleRoutes();
		StringBuilder classifications = new StringBuilder();
		oracleClassifications.forEach((family, routes) -> classifications.append(family).append('\t')
			.append(String.join(",", routes)).append('\n'));
		Files.writeString(output.resolve("operation-family-classifications.tsv"),
			classifications.toString(), StandardCharsets.UTF_8);
		StringBuilder cpSamples = new StringBuilder(
			"# Observed only: bounded sampled tuples returned CP; not a universal CP-only proof.\n");
		observedCpOnlySamples.forEach((family, cpOnly) -> {
			if(cpOnly)
				cpSamples.append(family).append("\tOBSERVED_CP_ONLY_SAMPLE\n");
		});
		Files.writeString(output.resolve("observed-cp-only-samples.tsv"), cpSamples.toString(),
			StandardCharsets.UTF_8);
		Files.writeString(output.resolve("inventory-summary.json"), summaryJson(), StandardCharsets.UTF_8);
	}

	private static DMLProgram parse(String workload) throws Exception {
		String resource = ROOT + workload + ".dml";
		return ParserFactory.createParser().parse(resource,
			new String(TrainingWorkloadOperationCoverageTest.resource(workload + ".dml"), StandardCharsets.UTF_8),
			new HashMap<>());
	}

	private static DMLProgram construct(String workload) throws Exception {
		DMLProgram program = parse(workload);
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		return program;
	}

	private static void append(StringBuilder rows, String workload, String phase,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences) {
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			Hop hop = occurrence.hop();
			rows.append(workload).append('\t').append(phase).append('\t').append(ordinal).append('\t')
				.append(clean(occurrence.namespace())).append('\t').append(clean(occurrence.path())).append('\t')
				.append(clean(occurrence.topology())).append('\t').append(hop.getClass().getSimpleName()).append('\t')
				.append(normalizeCompilerCounters(clean(hop.getOpString()))).append('\t')
				.append(clean(PlacementGraphFingerprint.semanticStructuralKey(hop))).append('\n');
		}
	}

	private static String clean(String value) {
		return value == null ? "<null>" : value.replace('\t', ' ').replace('\n', ' ');
	}

	private static String normalizeCompilerCounters(String value) {
		return value.replaceFirst("^[0-9]+_", "compiler-id_")
			.replaceAll("parsertemp[0-9]+", "compiler-temp")
			.replaceAll("__(tmp|pred)[0-9]+", "__$1");
	}

	private static String family(Hop hop) {
		if(hop instanceof AggBinaryOp operation)
			return "AggBinaryOp/" + clean(operation.getOpString());
		if(hop instanceof AggUnaryOp operation)
			return "AggUnaryOp/" + operation.getOp() + '/' + operation.getDirection();
		if(hop instanceof BinaryOp operation)
			return "BinaryOp/" + operation.getOp();
		if(hop instanceof DataGenOp operation)
			return "DataGenOp/" + operation.getOp();
		if(hop instanceof DataOp operation)
			return "DataOp/" + operation.getOp();
		if(hop instanceof FunctionOp operation)
			return "FunctionOp/" + operation.getFunctionType();
		if(hop instanceof IndexingOp)
			return "IndexingOp/RIGHT_INDEX";
		if(hop instanceof LeftIndexingOp)
			return "LeftIndexingOp/LEFT_INDEX";
		if(hop instanceof LiteralOp)
			return "LiteralOp/LITERAL";
		if(hop instanceof NaryOp operation)
			return "NaryOp/" + operation.getOp();
		if(hop instanceof ParameterizedBuiltinOp operation)
			return "ParameterizedBuiltinOp/" + operation.getOp();
		if(hop instanceof QuaternaryOp operation)
			return "QuaternaryOp/" + operation.getOp();
		if(hop instanceof ReorgOp operation)
			return "ReorgOp/" + operation.getOp();
		if(hop instanceof TernaryOp operation)
			return "TernaryOp/" + operation.getOp();
		if(hop instanceof UnaryOp operation)
			return "UnaryOp/" + operation.getOp();
		throw new AssertionError("unclassified Hop subclass " + hop.getClass().getName());
	}

	private static Set<String> observedFamilies() {
		Set<String> observed = new java.util.TreeSet<>();
		for(CompiledFixture fixture : compiled.values()) {
			fixture.before().forEach(occurrence -> observed.add(family(occurrence.hop())));
			fixture.after().forEach(occurrence -> observed.add(family(occurrence.hop())));
		}
		return observed;
	}

	private static void classifyAndVerifyOracleRoutes() {
		if(oracleClassifications != null)
			return;
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		Map<String,Set<String>> routes = new TreeMap<>();
		Map<String,Boolean> cpSamples = new TreeMap<>();
		for(CompiledFixture fixture : compiled.values()) {
			verifyOccurrences(oracle, fixture.before(), routes, cpSamples);
			verifyOccurrences(oracle, fixture.after(), routes, cpSamples);
		}
		oracleClassifications = routes;
		observedCpOnlySamples = cpSamples;
	}

	private static void verifyOccurrences(OracleFacade oracle,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, Map<String,Set<String>> routes,
		Map<String,Boolean> cpSamples) {
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
			Hop hop = occurrence.hop();
			OracleFacade.PreparedDecision prepared = oracle.prepareDecision(hop);
			List<List<FType>> domains = new ArrayList<>(hop.getInput().size());
			for(int input = 0; input < hop.getInput().size(); input++) {
				List<FType> domain = new ArrayList<>(java.util.Arrays.asList(FType.values()));
				domain.add(null); // ABSENT_LOCAL at the rules boundary.
				domains.add(java.util.Collections.unmodifiableList(domain));
			}
			var relation = prepared.prepareExecutionRelation(domains);
			List<List<FType>> tuples = representativeTuples(hop.getInput().size());
			boolean cpOnly = true;
			for(Boolean fullSingle : new Boolean[] {Boolean.TRUE, Boolean.FALSE, null}) {
				if(relation.isPresent()) {
					for(List<FType> tuple : tuples) {
						OracleFacade.DecisionEvidence expected = prepared.decideWithEvidence(tuple,
							freshHint(hop, fullSingle));
						OracleFacade.DecisionEvidence actual = relation.orElseThrow().evidenceFor(tuple);
						assertEvidence(hop, tuple, fullSingle, expected, actual);
						cpOnly &= actual.caps().exec() == ExecType.CP;
					}
				}
				else {
					var exact = prepared.prepareExactRuleDecision(
						tuple -> freshHint(hop, fullSingle));
					for(List<FType> tuple : tuples) {
						OracleFacade.DecisionEvidence expected = prepared.decideWithEvidence(tuple,
							freshHint(hop, fullSingle));
						OracleFacade.DecisionEvidence actual = exact.decide(tuple);
						assertEvidence(hop, tuple, fullSingle, expected, actual);
						cpOnly &= actual.caps().exec() == ExecType.CP;
					}
					if(!tuples.isEmpty()) {
						OracleFacade.DecisionEvidence repeated = exact.decide(tuples.get(0));
						assertEvidence(hop, tuples.get(0), fullSingle,
							prepared.decideWithEvidence(tuples.get(0), freshHint(hop, fullSingle)), repeated);
						Assert.assertTrue("exact residual must report reused structural evidence",
							exact.diagnostics().reusedEvidence() > 0);
					}
				}
			}
			String family = family(hop);
			String route = relation.isPresent() ? "SHAPE_INDEPENDENT_RELATION" : "EXACT_RULE_RESIDUAL";
			routes.computeIfAbsent(family, ignored -> new java.util.TreeSet<>()).add(route);
			cpSamples.merge(family, cpOnly, (left, right) -> left && right);
		}
	}

	private static List<List<FType>> representativeTuples(int arity) {
		if(arity == 0)
			return List.of(List.of());
		List<FType> alphabet = new ArrayList<>(java.util.Arrays.asList(FType.values()));
		alphabet.add(null);
		List<List<FType>> tuples = new ArrayList<>();
		if(arity == 1) {
			for(FType value : alphabet) {
				List<FType> tuple = new ArrayList<>(1);
				tuple.add(value);
				tuples.add(java.util.Collections.unmodifiableList(tuple));
			}
			return tuples;
		}
		// First two positions exercise all 7x7 combinations, including ABSENT_LOCAL.
		for(FType left : alphabet)
			for(FType right : alphabet) {
				List<FType> tuple = nullTuple(arity);
				tuple.set(0, left);
				tuple.set(1, right);
				tuples.add(java.util.Collections.unmodifiableList(tuple));
			}
		// Higher arities remain bounded by perturbing one additional axis at a time.
		for(int position = 2; position < arity; position++)
			for(FType value : java.util.Arrays.asList(FType.ROW, FType.COL, FType.BROADCAST, null)) {
				List<FType> tuple = nullTuple(arity);
				tuple.set(position, value);
				tuples.add(java.util.Collections.unmodifiableList(tuple));
			}
		return tuples;
	}

	private static List<FType> nullTuple(int arity) {
		return new ArrayList<>(java.util.Collections.nCopies(arity, (FType) null));
	}

	private static ShapeHint freshHint(Hop hop, Boolean fullSingle) {
		Hop left = hop.getInput().isEmpty() ? null : hop.getInput(0);
		Hop right = hop.getInput().size() < 2 ? null : hop.getInput(1);
		return new ShapeHint(hop.getDim1(), hop.getDim2(), hop.getBlocksize(),
			java.util.Optional.ofNullable(fullSingle),
			left == null ? -1 : left.getDim1(), left == null ? -1 : left.getDim2(),
			right == null ? -1 : right.getDim1(), right == null ? -1 : right.getDim2());
	}

	private static void assertEvidence(Hop hop, List<FType> tuple, Boolean fullSingle,
		OracleFacade.DecisionEvidence expected, OracleFacade.DecisionEvidence actual) {
		String context = family(hop) + " tuple=" + tuple + " fullSingle=" + fullSingle;
		Assert.assertEquals(context, expected.caps().category(), actual.caps().category());
		Assert.assertEquals(context, expected.caps().opcode(), actual.caps().opcode());
		Assert.assertEquals(context, expected.caps().exec(), actual.caps().exec());
		Assert.assertEquals(context, expected.caps().placement(), actual.caps().placement());
		Assert.assertEquals(context, expected.caps().foutFType(), actual.caps().foutFType());
		Assert.assertEquals(context, expected.caps().reason(), actual.caps().reason());
		Assert.assertEquals(context, expected.caps().detail(), actual.caps().detail());
		Assert.assertEquals(context,
			expected.caps().notes().stream().map(note -> note.code() + ":" + note.message()).toList(),
			actual.caps().notes().stream().map(note -> note.code() + ":" + note.message()).toList());
		Assert.assertEquals(context, expected.shapeProof(), actual.shapeProof());
	}

	private static void assertPredicatesCovered(String workload, String phase,
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, Set<Hop> predicates) {
		Set<Hop> observed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		occurrences.forEach(occurrence -> observed.add(occurrence.hop()));
		Assert.assertFalse(workload + ' ' + phase + " fixture must contain a control predicate", predicates.isEmpty());
		Assert.assertTrue(workload + ' ' + phase + " ordered occurrences omitted a control predicate",
			observed.containsAll(predicates));
	}

	private static Set<Hop> predicateHops(DMLProgram program) {
		Set<Hop> predicates = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		collectPredicates(program.getStatementBlocks(), predicates);
		for(FunctionStatementBlock function : program.getNamedNSFunctionStatementBlocks().values())
			collectPredicates(List.of(function), predicates);
		return predicates;
	}

	private static void collectPredicates(List<StatementBlock> blocks, Set<Hop> predicates) {
		for(StatementBlock block : blocks) {
			if(block instanceof IfStatementBlock conditional) {
				collectHop(conditional.getPredicateHops(), predicates);
				IfStatement statement = (IfStatement) block.getStatement(0);
				collectPredicates(statement.getIfBody(), predicates);
				collectPredicates(statement.getElseBody(), predicates);
			}
			else if(block instanceof WhileStatementBlock loop) {
				collectHop(loop.getPredicateHops(), predicates);
				collectPredicates(((WhileStatement) block.getStatement(0)).getBody(), predicates);
			}
			else if(block instanceof ForStatementBlock loop) {
				collectHop(loop.getFromHops(), predicates);
				collectHop(loop.getToHops(), predicates);
				collectHop(loop.getIncrementHops(), predicates);
				collectPredicates(((ForStatement) block.getStatement(0)).getBody(), predicates);
			}
			else if(block instanceof FunctionStatementBlock)
				collectPredicates(((FunctionStatement) block.getStatement(0)).getBody(), predicates);
		}
	}

	private static void collectHop(Hop hop, Set<Hop> found) {
		if(hop != null && found.add(hop))
			for(Hop input : hop.getInput())
				collectHop(input, found);
	}

	private static byte[] resource(String name) throws IOException {
		try(InputStream stream = TrainingWorkloadOperationCoverageTest.class.getClassLoader()
			.getResourceAsStream(ROOT + name)) {
			if(stream == null)
				throw new IOException("missing test resource " + ROOT + name);
			return stream.readAllBytes();
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String summaryJson() {
		StringBuilder out = new StringBuilder("{\n  \"schema\": \"training-operation-inventory/v1\",\n"
			+ "  \"workloads\": [\n");
		for(int i = 0; i < CAMPAIGN_ORDER.size(); i++) {
			String name = CAMPAIGN_ORDER.get(i);
			CompiledFixture fixture = compiled.get(name);
			Set<String> functions = new java.util.TreeSet<>();
			fixture.after().stream().map(PlacementGraphFingerprint.HopOccurrence::namespace)
				.filter(namespace -> !"main".equals(namespace)).forEach(functions::add);
			Set<String> families = new java.util.TreeSet<>();
			fixture.after().forEach(occurrence -> families.add(family(occurrence.hop())));
			out.append("    {\"name\":\"").append(name).append("\",\"constructedOccurrences\":")
				.append(fixture.before().size()).append(",\"rewrittenOccurrences\":")
				.append(fixture.after().size()).append(",\"rewrittenFunctions\":")
				.append(functions.size()).append(",\"rewrittenFamilies\":")
				.append(families.size()).append('}').append(i + 1 == CAMPAIGN_ORDER.size() ? "\n" : ",\n");
		}
		return out.append("  ]\n}\n").toString();
	}

	private record CompiledFixture(List<PlacementGraphFingerprint.HopOccurrence> before,
		List<PlacementGraphFingerprint.HopOccurrence> after,
		Set<Hop> predicatesBefore, Set<Hop> predicatesAfter) { }
}
