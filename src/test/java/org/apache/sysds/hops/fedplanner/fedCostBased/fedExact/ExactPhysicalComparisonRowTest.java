/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.PlanSpaceComparisonIdentity;
import org.apache.sysds.test.component.federated.placement.shadow.PrebuilderSnapshot;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalComparisonRowTest {
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
}
