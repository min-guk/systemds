/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.junit.Assert;
import org.junit.Test;

public class CurrentPPhysicalPlanRowsTest {
	private static final String DYNAMIC_NATIVE_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "R=rev(A);\nU=exp(R);\nprint(sum(U));\n";

	@Test public void b01ProducesCompletePhysicalRow() throws Exception { check("B-01", 1); }
	@Test public void b21ProducesAllPhysicalRows() throws Exception { check("B-21", 192); }
	@Test public void cfgDefinitionResolutionRemainsTotalAcrossLoop() throws Exception {
		String script = "X=matrix(1,2,2);\ni=1;\n"
			+ "while(i<=2){\n"
			+ " if(sum(X)>0){norm_R2=X; old_norm_R2=norm_R2;}\n"
			+ " else{norm_R2=X+1; old_norm_R2=norm_R2;}\n"
			+ " X=old_norm_R2; i=i+1;\n}\nprint(sum(X));\n";
		DMLProgram program = compileScript(script);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, Map.of());
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var projector = new CurrentPPhysicalPlanRows(analysis, catalog, "cfg-definition-writer");
		var relation = new ClosedPlanRelationEnumerator(analysis);
		AtomicInteger projected = new AtomicInteger();
		var summary = relation.enumerateStates(BigInteger.ZERO, relation.stateCount(), proof -> {
			projector.physicalPlan(proof);
			projected.incrementAndGet();
		});
		Assert.assertEquals(BigInteger.ZERO, summary.unknown());
		Assert.assertTrue(projected.get() > 0);
	}
	@Test public void dynamicNativeLayoutPublishesEndpointAuthorityWithoutInventedRanges()
		throws Exception {
		DMLProgram program = compileScript(DYNAMIC_NATIVE_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) register(root, "dynamic-native", sources);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var projector = new CurrentPPhysicalPlanRows(analysis, catalog, "dynamic-native");
		var relation = new ClosedPlanRelationEnumerator(analysis);
		AtomicInteger dynamic = new AtomicInteger();
		AtomicInteger absentResidencyRejected = new AtomicInteger();
		var summary = relation.enumerateStates(BigInteger.ZERO, relation.stateCount(), proof -> {
			Map<String,Object> row = projector.physicalPlan(proof);
			for(Object value : (java.util.List<?>) row.get("authority")) {
				Map<?,?> authority = (Map<?,?>) value;
				Map<?,?> id = (Map<?,?>) authority.get("id");
				if(!"NATIVE_LINEAGE".equals(id.get("layout")) || !id.containsKey("workerResidency"))
					continue;
				Assert.assertFalse("dynamic authority must not invent exact ranges", id.containsKey("anchor"));
				Map<?,?> residency = (Map<?,?>) id.get("workerResidency");
				Assert.assertEquals(Boolean.FALSE, residency.get("layoutExact"));
				Assert.assertFalse(((java.util.List<?>) residency.get("endpoints")).isEmpty());
				dynamic.incrementAndGet();
			}
			for(int index = 0; index < proof.candidates().size(); index++) {
				CandidateSelectionReceipt selected = proof.candidates().get(index);
				if(!"NATIVE_LINEAGE".equals(selected.realization().key().layoutKind().name())
					|| selected.provenWorkerPool() != null
					|| selected.realization().nativeWorkerPoolResidencyWitness(
						selected.supportClause()) == null)
					continue;
				CandidateRealizationSupportClause withoutWitness =
					new CandidateRealizationSupportClause(
						selected.supportClause().proofDependencies(),
						selected.supportClause().inputBindings());
				CandidateEmissionRealization realization = new CandidateEmissionRealization(
					selected.realization().key(), java.util.List.of(withoutWitness));
				CandidateEmissionFact emission = new CandidateEmissionFact(
					selected.emission().emissionState(), selected.emission().executionFType(),
					selected.emission().derivedFoutAction(), java.util.List.of(realization));
				CandidateSelectionReceipt missing = new CandidateSelectionReceipt(
					selected.rule(), emission, realization, withoutWitness,
					selected.fallbackMaterializations());
				java.util.List<CandidateSelectionReceipt> candidates =
					new java.util.ArrayList<>(proof.candidates());
				candidates.set(index, missing);
				var malformed = new FullProductionJointPlanExport.Audit(proof.ordinal(), proof.verdict(),
					proof.reason(), proof.assignment(), candidates, proof.relocations());
				IllegalArgumentException error = Assert.assertThrows(IllegalArgumentException.class,
					() -> projector.physicalPlan(malformed));
				Assert.assertEquals("Native layout lacks structural worker authority", error.getMessage());
				absentResidencyRejected.incrementAndGet();
				break;
			}
		});
		Assert.assertEquals(BigInteger.ZERO, summary.unknown());
		Assert.assertTrue("accepted P rows must exercise dynamic endpoint authority", dynamic.get() > 0);
		Assert.assertTrue("native authority without exact anchor or residency must fail closed",
			absentResidencyRejected.get() > 0);
	}
	@Test public void allViableProtectedFixturesHaveCompleteInputAuthority() throws Exception {
		for(String fixture : ProductionShadowFixtureFactory.ids()) {
			if(fixture.equals("B-13")) continue;
			try { check(fixture, -1); }
			catch(Exception error) { throw new IllegalStateException("Incomplete P authority: " + fixture, error); }
		}
	}

	private static void check(String fixture, int expected) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) register(root, fixture, sources);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var projector = new CurrentPPhysicalPlanRows(analysis, catalog, fixture);
		var relation = new ClosedPlanRelationEnumerator(analysis);
		AtomicInteger observed = new AtomicInteger();
		AtomicInteger relocationBindings = new AtomicInteger();
		Map<Map<String,Object>,Set<Integer>> proofCountsByPhysical = new HashMap<>();
		String export = System.getProperty("g009.pPhysicalRows");
		String auditExport = System.getProperty("g009.pPhysicalAudit");
		var summary = relation.enumerateStates(BigInteger.ZERO, relation.stateCount(), proof -> {
			Map<String,Object> row = projector.physicalPlan(proof);
			Assert.assertEquals("physical-plan-v1", row.get("schema"));
			Assert.assertEquals(8, row.size());
			if(fixture.equals("B-02") || fixture.equals("B-21"))
				Assert.assertFalse(((java.util.List<?>) row.get("logicalInputs")).isEmpty());
			Assert.assertFalse(((java.util.List<?>) row.get("nodes")).isEmpty());
			if(fixture.equals("B-21")) {
				@SuppressWarnings("unchecked")
				var actions = (java.util.List<Map<String,Object>>) row.get("actions");
				Set<Object> actionIds = new HashSet<>();
				for(var action : actions) actionIds.add(action.get("id"));
				@SuppressWarnings("unchecked")
				var bindings = (java.util.List<Map<String,Object>>) row.get("bindings");
				for(var binding : bindings)
					if("RELOCATION".equals(binding.get("inputAuthority"))) {
						Assert.assertTrue("Emitted relocation input must reference an action",
							actionIds.contains(binding.get("actionRef")));
						relocationBindings.incrementAndGet();
					}
				proofCountsByPhysical.computeIfAbsent(row, ignored -> new HashSet<>())
					.add(proof.relocations().size());
			}
			if(export != null) try {
				Files.writeString(Path.of(export), new ObjectMapper().writeValueAsString(row) + "\n",
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			catch(Exception error) { throw new IllegalStateException("Cannot write physical test row", error); }
			if(auditExport != null) try {
				Map<String,Object> audit = Map.of("fixture", fixture,
					"ordinal", proof.ordinal().toString(),
					"candidateReceipts", proof.candidates().stream()
						.map(candidate -> candidate.normalizedSignature()).toList(),
					"relocationReceipts", proof.relocations().stream()
						.map(receipt -> receipt.normalizedSignature()).toList());
				Files.writeString(Path.of(auditExport), new ObjectMapper().writeValueAsString(audit) + "\n",
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			catch(Exception error) { throw new IllegalStateException("Cannot write physical audit row", error); }
			observed.incrementAndGet();
		});
		if(expected >= 0) Assert.assertEquals(BigInteger.valueOf(expected), summary.accepted());
		Assert.assertEquals(BigInteger.ZERO, summary.unknown());
		if(expected >= 0) Assert.assertEquals(expected, observed.get());
		if(fixture.equals("B-21")) {
			Assert.assertTrue("B-21 must contain emitted relocation binding", relocationBindings.get() > 0);
			Assert.assertTrue("No-op relocation and direct-FOUT proof must share one physical row",
				proofCountsByPhysical.values().stream().anyMatch(counts -> counts.contains(0)
					&& counts.contains(1)));
		}
	}

	private static void register(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) register(child, fixture, sources);
	}

	private static DMLProgram compileScript(String script) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		return program;
	}
}
