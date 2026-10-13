/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/** Candidate-family parity for matrix-to-scalar collection. */
public class CastAsScalarCpFamilyParityTest {
	private static final long[][] INPUT_SHAPES = {{-1, -1}, {1, 1}, {37, 19}};

	@Test
	public void aggregateReleaseDerivesPublicAndAuthorizesExhaustiveFamilyParity() throws Exception {
		// The federated source starts PRIVATE_AGGREGATE. A full aggregate is the
		// production privacy release that makes the matrix and cast output PUBLIC.
		PlacementAnalysis analysis = analyze(Privacy.PRIVATE_AGGREGATE);
		var occurrence = analysis.compiledHopOccurrences().stream()
			.filter(candidate -> candidate.hop() instanceof UnaryOp unary
				&& unary.getOp() == OpOp1.CAST_AS_SCALAR).findFirst().orElseThrow();
		Assert.assertEquals(DataType.MATRIX, occurrence.hop().getInput().get(0).getDataType());
		Assert.assertEquals(Privacy.PUBLIC, analysis.requirePrivacy(occurrence.key()));
		Assert.assertTrue("the concrete analyzed input has a singleton FType domain",
			analysis.candidateRuleFacts().cpFamiliesForParent(occurrence.key()).isEmpty());
		Assert.assertEquals(1, analysis.candidateRuleFacts().orderedFactsForParent(occurrence.key()).size());

		PlacementCandidateGenerator.GenerationPrivacy authority =
			new PlacementCandidateGenerator.GenerationPrivacy(
				analysis.requirePrivacy(occurrence.key()), Set.of());
		for(long[] shape : INPUT_SHAPES)
			assertExhaustiveFamilyParity(cast(shape[0], shape[1]), authority);
	}

	@Test
	public void privateOrPayloadMaskedCastDoesNotPublishCpFamily() {
		for(long[] shape : INPUT_SHAPES)
			for(PlacementCandidateGenerator.GenerationPrivacy privacy : List.of(
				new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE, Set.of()),
				new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE_AGGREGATE, Set.of()),
				new PlacementCandidateGenerator.GenerationPrivacy(
					Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, Set.of()),
				new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE_AGGREGATE, Set.of(0)),
				new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PUBLIC, Set.of(0))))
				Assert.assertTrue(build(cast(shape[0], shape[1]), allRuntimeTypes(), privacy,
					new SearchSpaceMetrics()).families().isEmpty());
	}

	@Test
	public void nonMatrixInputOrNonScalarOutputStaysOnResidualPath() {
		for(UnaryOp hop : List.of(
			cast(DataType.SCALAR, DataType.SCALAR),
			cast(DataType.FRAME, DataType.SCALAR),
			cast(DataType.MATRIX, DataType.MATRIX)))
			Assert.assertTrue(build(hop, allRuntimeTypes(), publicOutput(),
				new SearchSpaceMetrics()).families().isEmpty());
	}

	@Test
	public void frozenMl10FootprintIsExactlySeventySevenOccurrences() throws Exception {
		Map<String,Long> workloads = new TreeMap<>();
		Map<String,Long> phases = new TreeMap<>();
		try(InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"fedplanner/training-operations/OPERATION_OCCURRENCES.tsv")) {
			Assert.assertNotNull(stream);
			for(String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).toList()) {
				String[] fields = line.split("\\t", -1);
				if(fields.length == 9 && "UnaryOp".equals(fields[6]) && "u(castdts)".equals(fields[7])) {
					workloads.merge(fields[0], 1L, Long::sum);
					phases.merge(fields[1], 1L, Long::sum);
				}
			}
		}
		Map<String,Long> expected = new LinkedHashMap<>();
		expected.put("als", 2L);
		expected.put("glm", 29L);
		expected.put("gmm-vvi", 5L);
		expected.put("kmeans", 10L);
		expected.put("l2svm", 8L);
		expected.put("lm", 3L);
		expected.put("logreg", 13L);
		expected.put("steplm", 7L);
		Assert.assertEquals(expected, workloads);
		Assert.assertEquals(Map.of("constructed", 18L, "rewritten", 59L), phases);
	}

	@Ignore("Repository privacy contract forbids direct PUBLIC fixture cases; capability-only audit")
	@Test
	public void publicCapabilityFamilyMatchesEverySingletonAndReversedDomain() {
		for(long[] shape : INPUT_SHAPES)
			assertExhaustiveFamilyParity(cast(shape[0], shape[1]), publicOutput());
	}

	private static void assertExhaustiveFamilyParity(UnaryOp hop,
		PlacementCandidateGenerator.GenerationPrivacy privacy) {
		List<FType> forward = allRuntimeTypes();
		List<FType> reverse = new ArrayList<>(forward);
		Collections.reverse(reverse);
		for(List<FType> domain : List.of(forward, reverse)) {
			BuildResult unmeasured = build(hop, domain, privacy, null);
			BuildResult measured = build(hop, domain, privacy, new SearchSpaceMetrics());
			Assert.assertEquals(unmeasured.node(), measured.node());
			Assert.assertEquals(unmeasured.facts(), measured.facts());
			Assert.assertEquals(unmeasured.familySignatures(), measured.familySignatures());
			Assert.assertEquals(1, measured.families().size());
			CpRuleFamily family = measured.families().get(0);
			Assert.assertEquals(0, family.materializedMemberCount());
			Assert.assertEquals(domain.size(), family.axes().get(0).size());
			for(FType type : domain) {
				List<CandidateInputState> input = List.of(type == null
					? CandidateInputState.absentLocal() : CandidateInputState.present(type));
				CandidateRuleFact compact = family.requireExact(input);
				BuildResult singleton = build(hop,
					new ArrayList<>(java.util.Collections.singletonList(type)), privacy, null);
				Assert.assertTrue(singleton.families().isEmpty());
				CandidateRuleFact expanded = singleton.facts().stream()
					.filter(fact -> fact.key().orderedInputs().equals(input))
					.findFirst().orElseThrow();
				Assert.assertEquals(expanded.capability(), compact.capability());
				Assert.assertEquals(expanded.shapeProof(), compact.shapeProof());
				Assert.assertEquals(expanded.profile(), compact.profile());
				Assert.assertEquals(expanded.allowedEmissionFacts(), compact.allowedEmissionFacts());
				Assert.assertEquals(singleton.node().legalAlternatives(), measured.node().legalAlternatives());
			}
			Assert.assertEquals(domain.size(), family.materializedMemberCount());
			Assert.assertEquals(1, routeCalls(measured.metrics(), hop,
				SearchSpaceMetrics.CandidateRoute.EXECUTION_RELATION));
			Assert.assertEquals(1, routeCalls(measured.metrics(), hop,
				SearchSpaceMetrics.CandidateRoute.CP_FAMILY));
			Assert.assertEquals(0, routeCalls(measured.metrics(), hop,
				SearchSpaceMetrics.CandidateRoute.EXACT_RULE_RESIDUAL));
		}
	}

	private static BuildResult build(UnaryOp hop, List<FType> domain,
		PlacementCandidateGenerator.GenerationPrivacy privacy, SearchSpaceMetrics metrics) {
		String id = "cast-as-scalar-family-" + hop.getHopID();
		ControlRegionKey region = new ControlRegionKey(id, "main", List.of("root"),
			"root", "compiled");
		CompiledHopKey key = new CompiledHopKey(id, "main", "root", "compiled",
			region, hop.getOpString(), hop.getOpString());
		ValueVersionKey value = new ValueVersionKey(id, "value", region, 0,
			VersionKind.ORDINARY, List.of());
		NodeShapeFact shape = new NodeShapeFact(hop.getDataType(), hop.getDim1(), hop.getDim2());
		List<NodeShapeFact> inputShapes = hop.getInput().stream()
			.map(input -> new NodeShapeFact(input.getDataType(), input.getDim1(), input.getDim2())).toList();
		SinglePartitionFacts partitions = new SinglePartitionFacts(hop.getInput(), Map.of(), Set.of());
		List<CandidateRuleKey> keys = new ArrayList<>();
		List<CandidateRuleFact> facts = new ArrayList<>();
		PlacementCandidateGenerator generator = new PlacementCandidateGenerator(
			new OracleFacade(RulesCore.RulesModule.createDefaultRegistry()), metrics);
		Node node = generator.buildNode(hop, key, value, List.of(),
			new ArrayList<>(Collections.nCopies(1, null)),
			new ArrayList<>(Collections.nCopies(1, null)), shape,
			AbstractShapeFact.fromConcrete(shape), partitions, inputShapes,
			List.of(domain), keys, facts, privacy);
		return new BuildResult(node, List.copyOf(facts), List.copyOf(generator.cpRuleFamilies()), metrics);
	}

	private static long routeCalls(SearchSpaceMetrics metrics, Hop hop,
		SearchSpaceMetrics.CandidateRoute route) {
		return metrics.candidateRouteSnapshot().stream()
			.filter(count -> count.opcode().equals(hop.getOpString()) && count.route() == route)
			.mapToLong(SearchSpaceMetrics.CandidateRouteCount::calls).sum();
	}

	private static PlacementCandidateGenerator.GenerationPrivacy publicOutput() {
		return new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PUBLIC, Set.of());
	}

	private static List<FType> allRuntimeTypes() {
		List<FType> result = new ArrayList<>();
		result.add(null);
		result.addAll(Arrays.asList(FType.values()));
		return result;
	}

	private static UnaryOp cast(long rows, long columns) {
		return cast(DataType.MATRIX, DataType.SCALAR, rows, columns);
	}

	private static UnaryOp cast(DataType inputType, DataType outputType) {
		return cast(inputType, outputType, 1, 1);
	}

	private static UnaryOp cast(DataType inputType, DataType outputType, long rows, long columns) {
		DataOp input = new DataOp("X", inputType, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "X", rows, columns,
			rows > 0 && columns > 0 ? rows * columns : -1, 1024);
		return new UnaryOp(OpOp1.CAST_AS_SCALAR.toString(), outputType,
			ValueType.FP64, OpOp1.CAST_AS_SCALAR, input);
	}

	private static PlacementAnalysis analyze(Privacy sourcePrivacy) throws Exception {
		String source = "A=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
			+ "ranges=list(list(0,0),list(2,2),list(2,0),list(4,2)));";
		DMLProgram program = ParserFactory.createParser().parse(DMLScript.DML_FILE_PATH_ANTLR_PARSER,
			source + "B=matrix(sum(A),rows=1,cols=1);s=as.scalar(B);print(s);", new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, sourcePrivacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private record BuildResult(Node node, List<CandidateRuleFact> facts,
		List<CpRuleFamily> families, SearchSpaceMetrics metrics) {
		List<String> familySignatures() {
			return families.stream().map(CpRuleFamily::normalizedSignature).toList();
		}
	}
}
