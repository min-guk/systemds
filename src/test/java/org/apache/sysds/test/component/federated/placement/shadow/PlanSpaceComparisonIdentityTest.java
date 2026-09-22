/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

public class PlanSpaceComparisonIdentityTest {
	@Test public void logicalFactsHaveCompleteStructuralEndpoints() throws Exception {
		for(String fixture : List.of("B-02", "B-21")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
			Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
			for(var block : program.getStatementBlocks())
				if(block.getHops() != null)
					for(Hop root : block.getHops()) registerSources(root, fixture, sources);
			var catalog = PlanSpaceComparisonIdentity.from(
				new NeutralPlacementGraphBuilder().buildAnalysis(program),
				PrebuilderSnapshot.capture(program, sources));
			Assert.assertEquals(fixture, fixture.equals("B-02") ? 5 : 4,
				catalog.physicalLogicalInputs().size());
			for(Map<String,Object> fact : catalog.physicalLogicalInputs()) {
				Assert.assertTrue(List.of("TRANSIENT", "INLINED_FUNCTION_INPUT")
					.contains(fact.get("kind")));
				Assert.assertEquals("TRANSIENT".equals(fact.get("kind")) ? 4 : 11,
					fact.size());
				if("INLINED_FUNCTION_INPUT".equals(fact.get("kind")))
					Assert.assertEquals("B-21 function input boundary is nonemitted",
						false, fact.get("targetEmitted"));
				for(String endpoint : List.of("source", "target")) {
					@SuppressWarnings("unchecked")
					Map<String,Object> occurrence = (Map<String,Object>) fact.get(endpoint);
					Assert.assertEquals(6, occurrence.size());
					Assert.assertTrue(((String) occurrence.get("sourceOrigin")).startsWith("block/"));
				}
			}
		}
	}

	@Test public void predecessorUsesItsDefiningRegionRatherThanReadOwner() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, "B-21", sources);
		var catalog = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(program),
			PrebuilderSnapshot.capture(program, sources));
		Map<String,Object> read = catalog.nodes().stream()
			.filter(node -> node.get("occurrence").equals("block/main/1/body/0/in/0/in/0"))
			.findFirst().orElseThrow();
		@SuppressWarnings("unchecked")
		Map<String,Object> version = (Map<String,Object>) read.get("valueVersion");
		Assert.assertEquals("block/main/1", version.get("callSitePath"));
		@SuppressWarnings("unchecked")
		List<Map<String,Object>> predecessors = (List<Map<String,Object>>) version.get("predecessors");
		@SuppressWarnings("unchecked")
		Map<String,Object> definition = (Map<String,Object>) predecessors.get(0).get("value");
		Assert.assertEquals("block/main/0", definition.get("callSitePath"));
		Assert.assertEquals("compiled", definition.get("recompileContext"));
	}

	@Test public void inlinedBoundaryEmissionAndExpressionSourceAreExplicit() throws Exception {
		DMLProgram nonEmitted = ProductionShadowFixtureFactory.compile("B-07");
		var nonEmittedCatalog = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(nonEmitted),
			PrebuilderSnapshot.capture(nonEmitted, Map.of()));
		Map<String,Object> b07 = nonEmittedCatalog.physicalLogicalInputs().stream()
			.filter(fact -> "INLINED_FUNCTION_INPUT".equals(fact.get("kind")))
			.findFirst().orElseThrow();
		Assert.assertEquals(false, b07.get("targetEmitted"));

		DMLProgram expression = ProductionShadowFixtureFactory.compile("B-17");
		var expressionCatalog = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(expression),
			PrebuilderSnapshot.capture(expression, Map.of()));
		List<Map<String,Object>> inputs = expressionCatalog.physicalLogicalInputs().stream()
			.filter(fact -> "INLINED_FUNCTION_INPUT".equals(fact.get("kind"))).toList();
		Assert.assertEquals(2, inputs.size());
		Map<String,Object> computed = inputs.stream()
			.filter(fact -> fact.get("actual") instanceof Map<?,?>).findFirst().orElseThrow();
		@SuppressWarnings("unchecked")
		Map<String,Object> actual = (Map<String,Object>) computed.get("actual");
		Assert.assertEquals("EXPRESSION", actual.get("kind"));
		Assert.assertEquals(computed.get("source"), actual.get("source"));
	}
	@Test public void sourceCatalogContainsAllOrderedInputsAndNoHopIds() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-14");
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, Map.of());
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PlanSpaceComparisonIdentity identity = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		Assert.assertEquals(snapshot.edges().size(), identity.orderedInputs().size());
		Assert.assertEquals(analysis.graph().nodes().size(), identity.nodes().size());
		for(var projection : analysis.compiledHopOccurrences())
			Assert.assertTrue(identity.occurrence(projection.key()).startsWith("block/"));
		for(Map<String,Object> edge : identity.orderedInputs()) {
			Assert.assertEquals(3, edge.size());
			Assert.assertTrue(((String) edge.get("consumer")).startsWith("block/"));
			Assert.assertTrue(((String) edge.get("producer")).startsWith("block/"));
		}
		for(Map<String,Object> node : identity.nodes()) {
			Assert.assertFalse(node.containsKey("hopId"));
			Assert.assertFalse(node.containsKey("normalizedSignature"));
			Assert.assertFalse(node.containsKey("programFingerprint"));
		}
	}

	@Test public void unrelatedSnapshotFailsClosed() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-14");
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		PrebuilderSnapshot different = PrebuilderSnapshot.capture(
			ProductionShadowFixtureFactory.compile("B-10"), Map.of());
		try {
			PlanSpaceComparisonIdentity.from(analysis, different);
			Assert.fail("Unmatched source snapshot must fail");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("source")
				|| expected.getMessage().contains("Snapshot"));
		}
	}

	@Test public void callAndRecompileCoordinatesAreResolvable() throws Exception {
		for(String fixture : new String[] {"B-01", "B-02", "B-03", "B-04", "B-05", "B-06",
			"B-07", "B-08", "B-09", "B-10", "B-12", "B-14", "B-15", "B-16", "B-17", "B-18", "B-20"}) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, Map.of());
			var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			PlanSpaceComparisonIdentity identity = PlanSpaceComparisonIdentity.from(analysis, snapshot);
			Assert.assertEquals(fixture, snapshot.edges().size(), identity.orderedInputs().size());
			Assert.assertEquals(fixture,
				analysis.logicalTransientInputsInCanonicalOrder().size()
					+ analysis.logicalFunctionInputsInCanonicalOrder().size()
					+ snapshot.inlinedCalls().stream().mapToInt(call -> call.inputs().size()).sum(),
				identity.logicalInputs().size());
			if(fixture.equals("B-02"))
				Assert.assertTrue("B-02 transient relation missing", identity.logicalInputs().stream()
					.anyMatch(row -> "TRANSIENT".equals(row.get("kind"))));
		}
	}

	@Test public void protectedSourceOriginAndFunctionBoundaryAreRetained() throws Exception {
		for(String fixture : new String[] {"B-11", "B-21", "B-22"}) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
			Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
			for(var block : program.getStatementBlocks())
				if(block.getHops() != null)
					for(Hop root : block.getHops()) registerSources(root, fixture, sources);
			PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
			var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			PlanSpaceComparisonIdentity identity = PlanSpaceComparisonIdentity.from(analysis, snapshot);
			Assert.assertEquals(fixture, snapshot.edges().size(), identity.orderedInputs().size());
			Assert.assertEquals(fixture,
				analysis.logicalTransientInputsInCanonicalOrder().size()
					+ analysis.logicalFunctionInputsInCanonicalOrder().size()
					+ snapshot.inlinedCalls().stream().mapToInt(call -> call.inputs().size()).sum(),
				identity.logicalInputs().size());
			if(fixture.equals("B-21"))
				Assert.assertTrue("B-21 transient relation missing", identity.logicalInputs().stream()
					.anyMatch(row -> "TRANSIENT".equals(row.get("kind"))));
			Assert.assertTrue(fixture, identity.nodes().stream()
				.anyMatch(node -> node.containsKey("externalSource")));
			for(var projection : analysis.compiledHopOccurrences()) {
				Assert.assertEquals(5, identity.occurrenceDetails(projection.key()).size());
				Assert.assertFalse(identity.occurrenceDetails(projection.key())
					.containsKey("programFingerprint"));
				Assert.assertFalse(identity.occurrenceDetails(projection.key())
					.containsKey("normalizedSignature"));
				Assert.assertEquals(identity.occurrence(projection.key()),
					identity.occurrenceDetails(projection.key()).get("sourcePath"));
				Assert.assertEquals(identity.nodeFor(projection.key()).get("occurrence"),
					identity.occurrence(projection.key()));
			}
		}
	}

	@Test public void predecessorValuesKeepFullStructuralControlContext() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, "B-21", sources);
		PlanSpaceComparisonIdentity identity = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(program),
			PrebuilderSnapshot.capture(program, sources));
		int references = 0;
		for(Map<String,Object> node : identity.nodes()) {
			@SuppressWarnings("unchecked")
			Map<String,Object> version = (Map<String,Object>) node.get("valueVersion");
			@SuppressWarnings("unchecked")
			java.util.List<Map<String,Object>> predecessors =
				(java.util.List<Map<String,Object>>) version.get("predecessors");
			for(Map<String,Object> predecessor : predecessors)
				if(predecessor.get("value") instanceof Map<?,?> value) {
					references++;
					for(String field : new String[] {"functionNamespace", "callSitePath", "recompileContext"})
						Assert.assertTrue("Predecessor lacks " + field, value.containsKey(field));
				}
		}
		Assert.assertTrue("No value predecessor was exercised", references > 0);
	}

	private static void registerSources(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) registerSources(child, fixture, sources);
	}
}
