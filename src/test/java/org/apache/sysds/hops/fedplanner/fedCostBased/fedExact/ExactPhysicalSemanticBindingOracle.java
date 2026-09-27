/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.PlanSpaceComparisonIdentity;
import org.apache.sysds.test.component.federated.placement.shadow.PrebuilderSnapshot;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;

/** Emits a bounded JVM oracle for an independent Python compositional-decoder check. */
public final class ExactPhysicalSemanticBindingOracle {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	private static final BigInteger MAX_RAW_ASSIGNMENTS = BigInteger.valueOf(1_000_000);
	private static final long MAX_FACTOR_CELLS = 2_000_000;
	private static final long MAX_TOTAL_FACTOR_CELLS = 8_000_000;

	private ExactPhysicalSemanticBindingOracle() { }

	public static void main(String[] args) throws Exception {
		if(args.length < 2)
			throw new IllegalArgumentException("Expected: output-directory fixture [fixture ...]");
		Path output = Path.of(args[0]);
		for(int index = 1; index < args.length; index++)
			System.out.println(JSON.writeValueAsString(exportFixture(args[index], output)));
	}

	static Map<String,Object> exportFixture(String fixture, Path output) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, fixture, sources);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		var identity = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var model = ExactPhysicalModel.build(analysis);
		BigInteger rawCount = ExactPhysicalRawSpaceExporter.size(model);
		if(rawCount.compareTo(MAX_RAW_ASSIGNMENTS) > 0)
			throw new IllegalArgumentException("semantic-binding raw assignment budget exhausted");

		String programSha = sha256(ProductionShadowFixtureFactory.scripts().get(fixture)
			.getBytes(StandardCharsets.UTF_8));
		Map<String,Object> sourceIdentity = Map.of("nodes", identity.nodes(),
			"orderedInputs", identity.orderedInputs(), "logicalInputs", identity.logicalInputs(),
			"physicalLogicalInputs", identity.physicalLogicalInputs());
		List<Map<String,Object>> domains = domains(model, identity);
		List<Map<String,Object>> factors = factors(model);
		Map<String,Object> projection = ExactPhysicalComparisonRow.compositionalProjection(
			model, identity, programSha);
		Map<String,Object> savedModel = new LinkedHashMap<>();
		savedModel.put("schema", "closed-e-native-model-artifact-v1");
		savedModel.put("cell", fixture);
		savedModel.put("source", "E_C0_SEMANTIC_BINDING_FIXTURE");
		savedModel.put("programSha256", programSha);
		savedModel.put("sourceIdentity", sourceIdentity);
		savedModel.put("domains", domains);
		savedModel.put("factors", factors);
		savedModel.put("physicalProjectionContract", "EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL");
		savedModel.put("physicalProjection", projection);
		savedModel.put("acceptance", "MATERIALIZED_FACTOR_TABLES");
		savedModel.put("claimScope", "TINY_FIXTURE_DIFFERENTIAL_INPUT_ONLY");

		Files.createDirectories(output);
		Path modelPath = output.resolve(fixture + ".e-model.json.gz");
		var modelWrite = ExactPlanningModelCapture.writeArtifact(modelPath, savedModel);
		List<Map<String,Object>> rows = new ArrayList<>(rawCount.intValueExact());
		ExactPhysicalPlanSpaceExporter.visit(model, BigInteger.ZERO, rawCount, raw -> {
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("ordinal", raw.ordinal().toString());
			row.put("assignment", raw.assignment());
			row.put("alternativeSignatures", raw.choices().stream()
				.map(ExactPhysicalPlanSpaceExporter.Choice::alternativeSignature).toList());
			row.put("status", raw.modelStatus().name());
			row.put("reason", raw.modelReason());
			row.put("physicalPlan", raw.modelStatus() == ExactPhysicalRawSpaceExporter.Status.EMITTED
				? ExactPhysicalComparisonRow.project(model, identity, raw, programSha) : null);
			rows.add(java.util.Collections.unmodifiableMap(row));
		});
		Map<String,Object> oracle = new LinkedHashMap<>();
		oracle.put("schema", "exact-e-java-physical-semantic-oracle-v1");
		oracle.put("status", "BLOCKED_PENDING_INDEPENDENT_PYTHON_VERIFICATION");
		oracle.put("claimScope", "TINY_FIXTURE_FULL_PHYSICAL_IDENTITY_DIFFERENTIAL_ONLY");
		oracle.put("fixture", fixture);
		oracle.put("modelSha256", modelWrite.sha256());
		oracle.put("programSha256", programSha);
		oracle.put("sourceIdentitySha256", digest(sourceIdentity));
		oracle.put("domainCommitmentSha256", digest(domains.stream().map(domain ->
			((List<?>) domain.get("alternatives")).stream().map(alternative ->
				((Map<?,?>) alternative).get("signature")).toList()).toList()));
		oracle.put("projectionSha256", digest(projection));
		oracle.put("rawAssignments", rawCount.toString());
		oracle.put("rows", rows);
		Path oraclePath = output.resolve(fixture + ".java-oracle.json.gz");
		var oracleWrite = ExactPlanningModelCapture.writeArtifact(oraclePath, oracle);
		return Map.of("fixture", fixture, "modelPath", modelPath.toAbsolutePath().toString(),
			"modelSha256", modelWrite.sha256(), "oraclePath", oraclePath.toAbsolutePath().toString(),
			"oracleSha256", oracleWrite.sha256(), "rawAssignments", rawCount.toString());
	}

	private static List<Map<String,Object>> domains(ExactPhysicalModel model,
		PlanSpaceComparisonIdentity identity) {
		List<Map<String,Object>> result = new ArrayList<>();
		for(int index = 0; index < model.domains().size(); index++) {
			var domain = model.domains().get(index);
			List<Map<String,Object>> alternatives = new ArrayList<>();
			for(var alternative : domain.alternatives())
				alternatives.add(Map.of("signature", alternative.signature(),
					"state", alternative.state().normalizedSignature()));
			result.add(Map.of("index", index, "occurrence", identity.occurrence(domain.node().key()),
				"alternatives", alternatives));
		}
		return List.copyOf(result);
	}

	private static List<Map<String,Object>> factors(ExactPhysicalModel model) {
		Map<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			positions.put(model.variables().get(index), index);
		List<Map<String,Object>> result = new ArrayList<>();
		long total = 0;
		for(var factor : model.hardFactors()) {
			List<Integer> scope = factor.scope().stream().map(positions::get).toList();
			long cells = 1;
			for(var variable : factor.scope()) cells = Math.multiplyExact(cells, variable.domainSize());
			total = Math.addExact(total, cells);
			if(cells > MAX_FACTOR_CELLS || total > MAX_TOTAL_FACTOR_CELLS)
				throw new IllegalArgumentException("semantic-binding factor table budget exhausted");
			List<String> truth = new ArrayList<>((int) cells);
			int[] values = new int[scope.size()];
			for(int row = 0; row < cells; row++) {
				int remainder = row;
				for(int position = scope.size() - 1; position >= 0; position--) {
					int radix = factor.scope().get(position).domainSize();
					values[position] = remainder % radix;
					remainder /= radix;
				}
				try {
					double cost = factor.cost(values);
					truth.add(cost == 0.0 && Double.doubleToRawLongBits(cost) !=
						Double.doubleToRawLongBits(-0.0d) ? "ALLOW"
						: cost == Double.POSITIVE_INFINITY ? "REJECT" : "UNKNOWN");
				}
				catch(RuntimeException error) { truth.add("UNKNOWN"); }
			}
			result.add(Map.of("scope", scope, "cells", Long.toString(cells), "truth", truth));
		}
		return List.copyOf(result);
	}

	private static void registerSources(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) registerSources(child, fixture, sources);
	}

	private static String digest(Object value) throws Exception {
		return sha256(JSON.writeValueAsBytes(value));
	}

	private static String sha256(byte[] value) throws Exception {
		return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
	}
}
