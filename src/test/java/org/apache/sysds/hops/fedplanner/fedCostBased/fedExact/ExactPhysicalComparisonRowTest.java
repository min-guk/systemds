/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.PlanSpaceComparisonIdentity;
import org.apache.sysds.test.component.federated.placement.shadow.PrebuilderSnapshot;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalComparisonRowTest {
	private static final String DYNAMIC_NATIVE_SCRIPT =
		"A=federated(addresses=list(\"localhost:4234/A1\",\"localhost:4235/A2\"),"
			+ "ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n"
			+ "R=rev(A);\nU=exp(R);\nprint(sum(U));\n";
	@Test public void b01StructuralPhysicalRows() throws Exception { check("B-01", 1); }
	@Test public void b21StructuralPhysicalRows() throws Exception { check("B-21", 192); }
	@Test public void logicalInputsArePresentForBranchAndFunctionFixtures() throws Exception {
		for(String fixture : new String[] {"B-02", "B-21"}) {
			AtomicReference<java.util.List<?>> first = new AtomicReference<>();
			long emitted = ExactPhysicalComparisonRow.streamFixture(fixture, plan -> {
				Assert.assertTrue(plan.containsKey("logicalInputs"));
				java.util.List<?> facts = (java.util.List<?>) plan.get("logicalInputs");
				Assert.assertFalse(fixture, facts.isEmpty());
				if(first.get() == null) first.set(facts);
				else Assert.assertEquals(fixture, first.get(), facts);
				for(Object raw : facts) {
					@SuppressWarnings("unchecked") Map<String,Object> fact = (Map<String,Object>) raw;
					Assert.assertTrue(fact.get("source") instanceof Map<?,?>);
					Assert.assertTrue(fact.get("target") instanceof Map<?,?>);
					Assert.assertTrue(fact.get("position") instanceof Integer);
				}
			});
			Assert.assertTrue(fixture, emitted > 0);
		}
	}
	@Test public void allOtherViableProtectedFixturesProject() throws Exception {
		for(int number = 1; number <= 22; number++) {
			if(number == 1 || number == 13 || number == 21) continue;
			String fixture = String.format("B-%02d", number);
			long emitted = ExactPhysicalComparisonRow.streamFixture(fixture, plan -> {
				Assert.assertEquals("physical-plan-v1", plan.get("schema"));
				Assert.assertEquals(Map.of("logical", fixture), plan.get("context"));
				String export = System.getProperty("g009.ePhysicalRows");
				if(export != null) try {
					Files.writeString(Path.of(export), new ObjectMapper().writeValueAsString(plan) + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
				catch(Exception error) { throw new IllegalStateException("Cannot write E physical row", error); }
			});
			Assert.assertTrue(fixture, emitted > 0);
		}
	}
	@Test public void b13HasNoPrivacySafeModel() throws Exception {
		try {
			ExactPhysicalComparisonRow.streamFixture("B-13", plan -> Assert.fail("Unexpected B-13 plan"));
			Assert.fail("B-13 should fail during protected model construction");
		}
		catch(org.apache.sysds.runtime.DMLRuntimeException expected) {
			Assert.assertFalse(expected.getMessage().isBlank());
		}
	}

	@Test public void directAssignmentProjectionExactlyMatchesOriginalExporter() throws Exception {
		ObjectMapper json = new ObjectMapper();
		for(String fixture : new String[] {"B-01", "B-02", "B-21"}) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
				program, Privacy.PRIVATE_AGGREGATE);
			Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
			for(var block : program.getStatementBlocks())
				if(block.getHops() != null)
					for(Hop root : block.getHops()) registerSources(root, fixture, sources);
			PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
			var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
			var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
			var model = ExactPhysicalModel.build(analysis);
			AtomicInteger checked = new AtomicInteger();
			ExactPhysicalPlanSpaceExporter.visit(model, java.math.BigInteger.ZERO,
				ExactPhysicalPlanSpaceExporter.size(model), raw -> {
					if(raw.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED) return;
					int[] assignment = raw.assignment().stream().mapToInt(Integer::intValue).toArray();
					try {
						Assert.assertArrayEquals(fixture,
							json.writeValueAsBytes(ExactPhysicalComparisonRow.project(
								model, catalog, raw, fixture)),
							json.writeValueAsBytes(ExactPhysicalComparisonRow.project(
								model, catalog, assignment, fixture)));
					}
					catch(java.io.IOException error) { throw new IllegalStateException(error); }
					checked.incrementAndGet();
				});
			Assert.assertTrue(fixture, checked.get() > 0);
		}
	}

	@Test public void compositionalProjectionExactlyReplaysSmallExhaustiveFixtures() throws Exception {
		for(String fixture : new String[] {"B-01", "B-02", "B-21"}) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
				program, Privacy.PRIVATE_AGGREGATE);
			Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
			for(var block : program.getStatementBlocks())
				if(block.getHops() != null)
					for(Hop root : block.getHops()) registerSources(root, fixture, sources);
			PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
			var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
			var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
			var model = ExactPhysicalModel.build(analysis);
			Map<String,Object> projection = ExactPhysicalComparisonRow.compositionalProjection(
				model, catalog, fixture);
			AtomicInteger checked = new AtomicInteger();
			ExactPhysicalPlanSpaceExporter.visit(model, java.math.BigInteger.ZERO,
				ExactPhysicalPlanSpaceExporter.size(model), raw -> {
					if(raw.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED) return;
					int[] assignment = raw.assignment().stream().mapToInt(Integer::intValue).toArray();
					Assert.assertEquals(ExactPhysicalComparisonRow.project(model, catalog, raw, fixture),
						compose(projection, assignment));
					checked.incrementAndGet();
				});
			Assert.assertTrue(fixture, checked.get() > 0);
		}
	}

	@Test public void dynamicNativeLayoutPublishesEndpointAuthorityWithoutInventedRanges()
		throws Exception {
		DMLProgram program = compileScript(DYNAMIC_NATIVE_SCRIPT);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, "dynamic-native", sources);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var model = ExactPhysicalModel.build(analysis);
		Map<String,Object> projection = ExactPhysicalComparisonRow.compositionalProjection(
			model, catalog, "dynamic-native");
		@SuppressWarnings("unchecked")
		List<Map<String,Object>> variables = (List<Map<String,Object>>) projection.get("variables");
		List<Map<?,?>> dynamic = new java.util.ArrayList<>();
		for(Map<String,Object> variable : variables)
			for(Map<String,Object> fragment :
				(List<Map<String,Object>>) variable.get("alternatives")) {
				Map<?,?> authority = (Map<?,?>) fragment.get("authority");
				Map<?,?> id = (Map<?,?>) authority.get("id");
				if("NATIVE_LINEAGE".equals(id.get("layout")) && id.containsKey("workerResidency"))
					dynamic.add(id);
			}
		Assert.assertFalse("fixture must retain dynamic native alternatives", dynamic.isEmpty());
		for(Map<?,?> id : dynamic) {
			Assert.assertFalse("dynamic authority must not invent exact ranges", id.containsKey("anchor"));
			Map<?,?> residency = (Map<?,?>) id.get("workerResidency");
			Assert.assertEquals(Boolean.FALSE, residency.get("layoutExact"));
			Assert.assertFalse(((List<?>) residency.get("endpoints")).isEmpty());
		}
		AtomicInteger accepted = new AtomicInteger();
		AtomicInteger selectedDynamic = new AtomicInteger();
		ExactPhysicalPlanSpaceExporter.visit(model, java.math.BigInteger.ZERO,
			ExactPhysicalPlanSpaceExporter.size(model), raw -> {
				if(raw.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED) return;
				int[] assignment = raw.assignment().stream().mapToInt(Integer::intValue).toArray();
				Map<String,Object> exact = ExactPhysicalComparisonRow.project(
					model, catalog, raw, "dynamic-native");
				Assert.assertEquals(exact, compose(projection, assignment));
				for(Object value : (List<?>) exact.get("authority")) {
					Map<?,?> authority = (Map<?,?>) value;
					Map<?,?> id = (Map<?,?>) authority.get("id");
					if("NATIVE_LINEAGE".equals(id.get("layout"))
						&& id.containsKey("workerResidency")) selectedDynamic.incrementAndGet();
				}
				accepted.incrementAndGet();
			});
		Assert.assertTrue("fixture must have accepted exact assignments", accepted.get() > 0);
		Assert.assertTrue("accepted exact rows must exercise dynamic endpoint authority",
			selectedDynamic.get() > 0);
	}

	@Test public void cfgDefinitionResolutionRemainsTotalAcrossLoop() throws Exception {
		String script = "X=matrix(1,2,2);\ni=1;\n"
			+ "while(i<=2){\n"
			+ " if(sum(X)>0){norm_R2=X; old_norm_R2=norm_R2;}\n"
			+ " else{norm_R2=X+1; old_norm_R2=norm_R2;}\n"
			+ " X=old_norm_R2; i=i+1;\n}\nprint(sum(X));\n";
		DMLProgram program = compileScript(script);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, Map.of());
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var model = ExactPhysicalModel.build(analysis);
		Assert.assertNotNull(ExactPhysicalComparisonRow.compositionalProjection(
			model, catalog, "cfg-definition-writer"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> compose(Map<String,Object> projection, int[] assignment) {
		List<Map<String,Object>> variables = (List<Map<String,Object>>) projection.get("variables");
		List<Map<String,Object>> selected = new java.util.ArrayList<>();
		for(int index = 0; index < assignment.length; index++)
			selected.add(((List<Map<String,Object>>) variables.get(index).get("alternatives"))
				.get(assignment[index]));
		List<Map<String,Object>> nodes = ((List<Integer>) projection.get("nodeOrder")).stream()
			.map(index -> (Map<String,Object>) selected.get(index).get("node")).toList();
		List<Map<String,Object>> authorities = selected.stream()
			.map(fragment -> (Map<String,Object>) fragment.get("authority")).toList();
		LinkedHashSet<Map<String,Object>> actions = new LinkedHashSet<>();
		LinkedHashSet<Map<String,Object>> geometry = new LinkedHashSet<>();
		for(Map<String,Object> fragment : selected) {
			actions.addAll((List<Map<String,Object>>) fragment.get("actions"));
			geometry.addAll((List<Map<String,Object>>) fragment.get("geometry"));
		}
		List<Map<String,Object>> bindings = new java.util.ArrayList<>();
		for(int domain : (List<Integer>) projection.get("bindingDomainOrder"))
			for(Map<String,Object> template :
				(List<Map<String,Object>>) selected.get(domain).get("bindings")) {
				Map<String,Object> row = new java.util.LinkedHashMap<>();
				row.put("consumer", template.get("consumer"));
				row.put("inputPosition", template.get("inputPosition"));
				row.put("presence", template.get("presence"));
				String mode = (String) template.get("mode");
				if(mode.equals("PHI")) {
					row.put("ftype", template.get("ftype"));
					row.put("producer", Map.of("kind", "PHI_JOIN_PORT",
						"owner", template.get("consumer")));
					row.put("inputAuthority", "PHI");
					List<Map<String,Object>> alternatives = new java.util.ArrayList<>();
					for(Map<String,Object> item :
						(List<Map<String,Object>>) template.get("producerAlternatives")) {
						Map<String,Object> source = selected.get((Integer) item.get("producerDomain"));
						alternatives.add(Map.of("producer",
							((Map<String,Object>) source.get("node")).get("occurrence"),
							"controlArm", item.get("controlArm"), "sourceAuthorityRef",
							((Map<String,Object>) source.get("authority")).get("id")));
					}
					row.put("producerAlternatives", alternatives);
				}
				else {
					Map<String,Object> source = selected.get((Integer) template.get("producerDomain"));
					Map<String,Object> sourceNode = (Map<String,Object>) source.get("node");
					row.put("ftype", template.containsKey("ftype") ? template.get("ftype")
						: sourceNode.get("ftype"));
					row.put("producer", sourceNode.get("occurrence"));
					row.put("sourceAuthorityRef",
						((Map<String,Object>) source.get("authority")).get("id"));
					row.put("inputAuthority", mode.equals("DIRECT_OR_FOUT")
						? (sourceNode.get("output").equals("FOUT") ? "DIRECT_FOUT" : "DIRECT") : mode);
					if(mode.equals("RELOCATION")) row.put("actionRef", template.get("actionRef"));
				}
				bindings.add(Map.copyOf(row));
			}
		return Map.of("schema", "physical-plan-v1",
			"context", Map.of("logical", projection.get("logicalProgram")),
			"nodes", nodes, "authority", authorities, "actions", List.copyOf(actions),
			"bindings", bindings, "logicalInputs", projection.get("logicalInputs"),
			"geometry", List.copyOf(geometry));
	}

	private static void check(String fixture, int expected) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, fixture, sources);
		PrebuilderSnapshot snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		var catalog = PlanSpaceComparisonIdentity.from(analysis, snapshot);
		var model = ExactPhysicalModel.build(analysis);
		AtomicInteger count = new AtomicInteger();
		ExactPhysicalPlanSpaceExporter.visit(model, java.math.BigInteger.ZERO,
			ExactPhysicalPlanSpaceExporter.size(model), raw -> {
				if(raw.modelStatus() != ExactPhysicalRawSpaceExporter.Status.EMITTED) return;
				Map<String,Object> plan = ExactPhysicalComparisonRow.project(model, catalog, raw, fixture);
				Assert.assertEquals(Map.of("logical", fixture), plan.get("context"));
				Assert.assertFalse(((java.util.List<?>) plan.get("nodes")).isEmpty());
				Assert.assertFalse(plan.toString().contains("programFingerprint"));
				Assert.assertFalse(plan.toString().contains("normalizedSignature"));
				String export = System.getProperty("g009.ePhysicalRows");
				if(export != null) try {
					Files.writeString(Path.of(export), new ObjectMapper().writeValueAsString(plan) + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
				catch(Exception error) { throw new IllegalStateException("Cannot write E physical row", error); }
				count.incrementAndGet();
			});
		Assert.assertEquals(fixture, expected, count.get());
	}

	private static void registerSources(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) registerSources(child, fixture, sources);
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
