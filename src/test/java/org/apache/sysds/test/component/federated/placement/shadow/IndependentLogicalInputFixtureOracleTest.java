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

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.junit.Assert;
import org.junit.Test;

/** Literal source-flow oracle; expectations are independent of placement analysis. */
public class IndependentLogicalInputFixtureOracleTest {
	@Test public void branchJoinHasExactlyTheSourceRequiredTransientRelations() throws Exception {
		var catalog = catalog("B-02");
		Set<Map<String,Object>> expected = Set.of(
			transientInput("block/main/0/body/0", "block/main/1/if/0/body/0/in/0/in/0"),
			transientInput("block/main/0/body/0", "block/main/1/else/0/body/0/in/0/in/0"),
			transientInput("block/main/0/body/1", "block/main/1/predicate/0/in/0"),
			transientInput("block/main/1/if/0/body/0", "block/main/2/body/0/in/0/in/0"),
			transientInput("block/main/1/else/0/body/0", "block/main/2/body/0/in/0/in/0"));
		Assert.assertEquals("B-02 source DML has two definitions feeding the joined X read",
			expected, Set.copyOf(catalog.logicalInputs()));
		Assert.assertEquals("B-02 has no duplicate logical relation", expected.size(),
			catalog.logicalInputs().size());
	}

	@Test public void inlinedCallKeepsSourceFlowAndItsFunctionInputBoundary() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		var snapshot = PrebuilderSnapshot.capture(program, sources(program, "B-21"));
		Assert.assertEquals("B-21 source contains one f(A) call", 1, snapshot.inlinedCalls().size());
		var call = snapshot.inlinedCalls().get(0);
		Assert.assertEquals("main/1", call.blockPath());
		Assert.assertEquals(1, call.inputs().size());
		Assert.assertEquals("A", call.inputs().get(0).actual());
		Assert.assertEquals("X", call.inputs().get(0).formal());
		Assert.assertEquals(0, call.inputs().get(0).position());

		var catalog = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(program), snapshot);
		Set<Map<String,Object>> expectedTransient = Set.of(
			transientInput("block/main/0/body/0", "block/main/1/body/0/in/0/in/0"),
			transientInput("block/main/0/body/1", "block/main/2/body/0/in/0/in/0"),
			transientInput("block/main/1/body/0", "block/main/2/body/0/in/0/in/1/in/0"));
		Assert.assertEquals("B-21 source DML transient reads",
			expectedTransient, catalog.logicalInputs().stream()
				.filter(row -> "TRANSIENT".equals(row.get("kind"))).collect(Collectors.toSet()));
		Map<String,Object> expectedFunctionInput = Map.of(
			"kind", "INLINED_FUNCTION_INPUT",
			"source", "block/main/1/body/0/in/0/in/0",
			"target", "block/main/1/inlined-call/0/.defaultNS::f/boundary/input/0",
			"callSite", "block/main/1/inlined-call/0/.defaultNS::f",
			"functionKey", ".defaultNS::f",
			"position", 0,
			"statementPosition", 0,
			"formal", "X",
			"actual", "A",
			"bound", Map.of("kind", "FORMAL_BINDING", "position", 0, "formal", "X"));
		Assert.assertEquals("The source f(A) call has exactly one physical input boundary",
			Set.of(expectedFunctionInput), catalog.logicalInputs().stream()
				.filter(row -> "INLINED_FUNCTION_INPUT".equals(row.get("kind")))
				.collect(Collectors.toSet()));
		Assert.assertEquals("B-21 source flow has no other logical input relation",
			expectedTransient.size() + 1, catalog.logicalInputs().size());
	}

	@Test public void inlinedCallKeepsItsSourceReturnBoundary() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		var snapshot = PrebuilderSnapshot.capture(program, sources(program, "B-21"));
		var output = snapshot.inlinedCalls().get(0).outputs().get(0);
		Assert.assertEquals("Y", output.formal());
		Assert.assertTrue("Inlined result must bind the formal Y",
			output.bound().endsWith("_Y"));
		Assert.assertEquals("Y", output.target());
		Assert.assertEquals(0, output.position());
		var catalog = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(program), snapshot);
		var boundary = catalog.nodes().stream().filter(node ->
			"block/main/1/inlined-call/0/.defaultNS::f/boundary/output/0"
				.equals(node.get("occurrence"))).toList();
		Assert.assertEquals("The source f(A) return has one boundary", 1, boundary.size());
		Assert.assertEquals("FUNCTION_OUTPUT", boundary.get(0).get("kind"));
		Assert.assertEquals("Y", boundary.get(0).get("name"));
		Assert.assertEquals(Boolean.TRUE, boundary.get(0).get("emittedWork"));
		Assert.assertTrue("The caller's Y write must consume its inlined result",
			catalog.orderedInputs().contains(Map.of("consumer", "block/main/1/body/0",
				"inputPosition", 0, "producer", "block/main/1/body/0/in/0")));
		Assert.assertEquals("The caller consumes the source return binding", output.bound(),
			catalog.nodes().stream().filter(node -> "block/main/1/body/0/in/0"
				.equals(node.get("occurrence"))).findFirst().orElseThrow().get("name"));
	}

	@Test public void branchPhiBindsBothAndOnlyBothSourceArms() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-02");
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis,
			PrebuilderSnapshot.capture(program, Map.of()));
		var projector = new CurrentPPhysicalPlanRows(analysis, catalog, "B-02");
		var relation = new ClosedPlanRelationEnumerator(analysis);
		Set<String> expected = Set.of(
			"block/main/1/if/0/body/0@block/main/1/branch-if/0",
			"block/main/1/else/0/body/0@block/main/1/branch-else/0");
		int[] observed = new int[1];
		var summary = relation.enumerateStates(BigInteger.ZERO, relation.stateCount(), proof -> {
			Map<String,Object> row = projector.physicalPlan(proof);
			for(Map<String,Object> binding : bindings(row)) {
				@SuppressWarnings("unchecked")
				Map<String,Object> consumer = (Map<String,Object>) binding.get("consumer");
				if(!"block/main/2/body/0/in/0/in/0".equals(consumer.get("sourceOrigin")))
					continue;
				observed[0]++;
				Assert.assertEquals("PHI", binding.get("inputAuthority"));
				Set<String> actual = new HashSet<>();
				@SuppressWarnings("unchecked")
				List<Map<String,Object>> alternatives =
					(List<Map<String,Object>>) binding.get("producerAlternatives");
				for(Map<String,Object> alternative : alternatives) {
					@SuppressWarnings("unchecked")
					Map<String,Object> producer = (Map<String,Object>) alternative.get("producer");
					actual.add(producer.get("sourceOrigin") + "@" + alternative.get("controlArm"));
				}
				Assert.assertEquals("The DML if/else defines X on exactly two arms", expected, actual);
				Assert.assertEquals("No duplicate branch alternative", expected.size(), alternatives.size());
			}
		});
		Assert.assertEquals(BigInteger.ZERO, summary.unknown());
		Assert.assertTrue("B-02 must yield accepted plans", summary.accepted().signum() > 0);
		Assert.assertEquals("Every accepted B-02 plan must carry its PHI binding",
			summary.accepted().intValueExact(), observed[0]);
	}

	@Test public void federatedAnchorPreservesLiteralWorkerPartitionRanges() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-21");
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis,
			PrebuilderSnapshot.capture(program, sources(program, "B-21")));
		var projector = new CurrentPPhysicalPlanRows(analysis, catalog, "B-21");
		var relation = new ClosedPlanRelationEnumerator(analysis);
		Map<String,Object> original = anchor(2);
		Map<String,Object> rowSums = anchor(1);
		int[] observed = new int[2];
		var summary = relation.enumerateStates(BigInteger.ZERO, relation.stateCount(), proof -> {
			Map<String,Object> row = projector.physicalPlan(proof);
			for(Map<String,Object> geometry : geometry(row)) {
				@SuppressWarnings("unchecked")
				Map<String,Object> owner = (Map<String,Object>) geometry.get("owner");
				if(!"block/main/0/body/0/in/0".equals(owner.get("sourceOrigin"))) continue;
				observed[0]++;
				Assert.assertTrue("Source A must retain a literal federated source partition",
					originalPartitions().contains(Map.of("worker", geometry.get("worker"),
						"ranges", geometry.get("ranges"), "ftype", geometry.get("ftype"))));
			}
			for(Map<String,Object> action : actions(row)) {
				observed[1]++;
				@SuppressWarnings("unchecked")
				Map<String,Object> id = (Map<String,Object>) action.get("id");
				Assert.assertTrue("Relocation anchor must have source or rowSums output shape",
					Set.of(original, rowSums).contains(id.get("anchor")));
			}
		});
		Assert.assertEquals(BigInteger.ZERO, summary.unknown());
		Assert.assertTrue("Federated source geometry was not observed", observed[0] > 0);
		Assert.assertTrue("Relocation anchor was not observed", observed[1] > 0);
	}

	@Test public void explicitRecompileCloneKeepsSeparateSourceOccurrence() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-09");
		var snapshot = PrebuilderSnapshot.capture(program, Map.of());
		var original = snapshot.roots().stream().filter(root ->
			"main/3".equals(root.blockPath())).findFirst().orElseThrow();
		var clone = snapshot.roots().stream().filter(root ->
			"main/4".equals(root.blockPath())).findFirst().orElseThrow();
		Assert.assertEquals("TWrite X", snapshot.nodes().get(original.hopId()).operation());
		Assert.assertEquals("TWrite X", snapshot.nodes().get(clone.hopId()).operation());
		Assert.assertFalse(snapshot.nodes().get(original.hopId()).requiresRecompile());
		Assert.assertTrue(snapshot.nodes().get(clone.hopId()).requiresRecompile());
		var catalog = PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(program), snapshot);
		var originalNode = catalog.nodes().stream().filter(node ->
			"block/main/3/body/0".equals(node.get("occurrence"))).findFirst().orElseThrow();
		var cloneNode = catalog.nodes().stream().filter(node ->
			"block/main/4/body/0".equals(node.get("occurrence"))).findFirst().orElseThrow();
		Assert.assertNotEquals("Clone must not alias the original source occurrence",
			originalNode.get("occurrence"), cloneNode.get("occurrence"));
		Assert.assertEquals(Boolean.TRUE, cloneNode.get("requiresRecompile"));
		@SuppressWarnings("unchecked")
		Map<String,Object> version = (Map<String,Object>) cloneNode.get("valueVersion");
		Assert.assertEquals("recompile", version.get("recompileContext"));
		Assert.assertEquals("CLONE_RECOMPILE", version.get("kind"));
	}

	private static Map<String,Object> anchor(int width) {
		return Map.of("ftype", "ROW", "partitions", List.of(
			Map.of("worker", "localhost:1234/X1", "begin", List.of(0L, 0L),
				"end", List.of(2L, (long) width)),
			Map.of("worker", "localhost:1235/X2", "begin", List.of(2L, 0L),
				"end", List.of(4L, (long) width))));
	}

	private static Set<Map<String,Object>> originalPartitions() {
		return Set.of(
			Map.of("worker", "localhost:1234/X1", "ranges", List.of(List.of(0L, 0L),
				List.of(2L, 2L)), "ftype", "ROW"),
			Map.of("worker", "localhost:1235/X2", "ranges", List.of(List.of(2L, 0L),
				List.of(4L, 2L)), "ftype", "ROW"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String,Object>> bindings(Map<String,Object> row) {
		return (List<Map<String,Object>>) row.get("bindings");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String,Object>> geometry(Map<String,Object> row) {
		return (List<Map<String,Object>>) row.get("geometry");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String,Object>> actions(Map<String,Object> row) {
		return (List<Map<String,Object>>) row.get("actions");
	}

	private static Map<String,Object> transientInput(String source, String target) {
		return Map.of("kind", "TRANSIENT", "source", source, "target", target, "position", 0);
	}

	private static PlanSpaceComparisonIdentity catalog(String fixture) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		return PlanSpaceComparisonIdentity.from(
			new NeutralPlacementGraphBuilder().buildAnalysis(program),
			PrebuilderSnapshot.capture(program, sources(program, fixture)));
	}

	private static Map<Long,PrebuilderSnapshot.ExternalSource> sources(DMLProgram program,
		String fixture) {
		Map<Long,PrebuilderSnapshot.ExternalSource> result = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) register(root, fixture, result);
		return result;
	}

	private static void register(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) register(child, fixture, sources);
	}
}
