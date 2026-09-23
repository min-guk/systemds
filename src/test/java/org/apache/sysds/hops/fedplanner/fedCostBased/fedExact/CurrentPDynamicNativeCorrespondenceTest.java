/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ClosedPlanRelationEnumerator;
import org.apache.sysds.test.component.federated.placement.shadow.CurrentPPhysicalPlanRows;
import org.apache.sysds.test.component.federated.placement.shadow.PlanSpaceComparisonIdentity;
import org.apache.sysds.test.component.federated.placement.shadow.PrebuilderSnapshot;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Cross-builder regression for dynamic native worker-residency physical identity. */
public class CurrentPDynamicNativeCorrespondenceTest {
	private static final ObjectMapper JSON = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
	private static final String SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "R=rev(A);\nU=exp(R);\nprint(sum(U));\n";

	@Test public void currentPAndExactEHaveEqualDynamicNativePhysicalSets() throws Exception {
		DMLProgram pProgram = compile();
		Map<Long,PrebuilderSnapshot.ExternalSource> pSources = prepareSources(pProgram);
		PrebuilderSnapshot pSnapshot = PrebuilderSnapshot.capture(pProgram, pSources);
		var pAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(pProgram);
		var pIdentity = PlanSpaceComparisonIdentity.from(pAnalysis, pSnapshot);
		var pProjector = new CurrentPPhysicalPlanRows(pAnalysis, pIdentity, "dynamic-native");
		var pRelation = new ClosedPlanRelationEnumerator(pAnalysis);
		Set<String> p = new HashSet<>();
		var pSummary = pRelation.enumerateStates(BigInteger.ZERO, pRelation.stateCount(),
			proof -> p.add(canonical(pProjector.physicalPlan(proof))));
		Assert.assertEquals(BigInteger.ZERO, pSummary.unknown());

		DMLProgram eProgram = compile();
		Map<Long,PrebuilderSnapshot.ExternalSource> eSources = prepareSources(eProgram);
		PrebuilderSnapshot eSnapshot = PrebuilderSnapshot.capture(eProgram, eSources);
		var eAnalysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(eProgram);
		var eIdentity = PlanSpaceComparisonIdentity.from(eAnalysis, eSnapshot);
		var model = ExactPhysicalModel.build(eAnalysis);
		Set<String> e = new HashSet<>();
		ExactPhysicalPlanSpaceExporter.visit(model, BigInteger.ZERO,
			ExactPhysicalPlanSpaceExporter.size(model), raw -> {
				if(raw.modelStatus() == ExactPhysicalRawSpaceExporter.Status.EMITTED)
					e.add(canonical(ExactPhysicalComparisonRow.project(
						model, eIdentity, raw, "dynamic-native")));
			});

		Assert.assertFalse("dynamic fixture must have an accepted P plan", p.isEmpty());
		Assert.assertFalse("dynamic fixture must have an accepted E plan", e.isEmpty());
		Assert.assertEquals("P/E dynamic native physical sets", e, p);
		Assert.assertTrue("canonical set must contain endpoint-only native authority",
			p.stream().anyMatch(row -> row.contains("\\\"workerResidency\\\"")
				&& row.contains("\\\"layoutExact\\\":false")
				&& !row.contains("\\\"layout\\\":\\\"NATIVE_LINEAGE\\\",\\\"anchor\\\"")));
	}

	private static Map<Long,PrebuilderSnapshot.ExternalSource> prepareSources(DMLProgram program) {
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) register(root, sources);
		return sources;
	}

	private static void register(Hop hop, Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:dynamic-native", "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) register(child, sources);
	}

	private static DMLProgram compile() throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, SCRIPT, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}

	private static String canonical(Map<String,Object> row) {
		try {
			Map<String,Object> normalized = new HashMap<>(row);
			for(String name : List.of("nodes", "actions", "bindings", "geometry", "authority",
				"logicalInputs")) {
				@SuppressWarnings("unchecked")
				List<Map<String,Object>> entries = (List<Map<String,Object>>) row.get(name);
				List<String> encoded = new ArrayList<>();
				for(Map<String,Object> entry : entries) encoded.add(JSON.writeValueAsString(entry));
				encoded.sort(String::compareTo);
				normalized.put(name, encoded);
			}
			return JSON.writeValueAsString(normalized);
		}
		catch(Exception error) {
			throw new IllegalStateException("Cannot canonicalize dynamic physical row", error);
		}
	}
}
