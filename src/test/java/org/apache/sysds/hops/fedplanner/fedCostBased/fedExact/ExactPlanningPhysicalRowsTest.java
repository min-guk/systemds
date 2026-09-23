/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

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

public class ExactPlanningPhysicalRowsTest {
	@Test public void componentEnumerationMatchesOriginalExhaustively() throws Exception {
		for(String fixture : new String[] {"B-01", "B-02", "B-21"}) {
			Fixture prepared = fixture(fixture);
			StringWriter originalText = new StringWriter();
			var original = new ExactPlanningPhysicalRows(prepared.model(), prepared.identity(), fixture,
				new BufferedWriter(originalText), null);
			original.walkOriginal();
			original.writerFlushForTest();

			StringWriter componentText = new StringWriter();
			var component = new ExactPlanningPhysicalRows(prepared.model(), prepared.identity(), fixture,
				new BufferedWriter(componentText), null);
			component.walkComponents();
			component.writerFlushForTest();

			Assert.assertEquals(fixture, original.summary(), component.summary());
			Assert.assertEquals(fixture, originalText.toString(), componentText.toString());
		}
	}

	private record Fixture(ExactPhysicalModel model, PlanSpaceComparisonIdentity identity) { }

	private static Fixture fixture(String name) throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile(name);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PRIVATE_AGGREGATE);
		Map<Long,PrebuilderSnapshot.ExternalSource> sources = new HashMap<>();
		for(var block : program.getStatementBlocks())
			if(block.getHops() != null)
				for(Hop root : block.getHops()) registerSources(root, name, sources);
		var snapshot = PrebuilderSnapshot.capture(program, sources);
		var analysis = new NeutralPlacementGraphBuilder().buildDetachedAnalysis(program);
		return new Fixture(ExactPhysicalModel.build(analysis),
			PlanSpaceComparisonIdentity.from(analysis, snapshot));
	}

	private static void registerSources(Hop hop, String fixture,
		Map<Long,PrebuilderSnapshot.ExternalSource> sources) {
		if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			sources.put(hop.getHopID(), new PrebuilderSnapshot.ExternalSource(
				"fixture:" + fixture, "PRIVATE_AGGREGATE", "ROW"));
		for(Hop child : hop.getInput()) registerSources(child, fixture, sources);
	}
}
