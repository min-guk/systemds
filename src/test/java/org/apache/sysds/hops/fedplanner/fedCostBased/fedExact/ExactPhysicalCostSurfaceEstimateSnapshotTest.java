/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

public class ExactPhysicalCostSurfaceEstimateSnapshotTest {
	@Test
	public void runtimeHopEstimateRefreshDoesNotChangeCanonicalCostSurface() throws Exception {
		var fixture = ExactCompiledSupplySharingTest.fixture();
		var analysis = fixture.analysis();
		var before = fixture.surface();

		Set<Hop> mutated = mutateOutputEstimates(
			analysis.occurrences(), OpOpData.TRANSIENTREAD, "X", 2.0);
		Assert.assertFalse(mutated.isEmpty());

		var after = ExactPhysicalCostModel.physicalCostSurface(
			analysis, ExactPhysicalModel.build(analysis));
		Assert.assertEquals(before.contributionFingerprint(), after.contributionFingerprint());
		Assert.assertEquals(before.contributions().size(), after.contributions().size());
		Assert.assertEquals(before.transferKeys(), after.transferKeys());
		Assert.assertEquals(before.supplySharingGroups().stream()
			.map(ExactPhysicalCostModel.SupplySharingGroup::semanticDescriptor).toList(),
			after.supplySharingGroups().stream()
				.map(ExactPhysicalCostModel.SupplySharingGroup::semanticDescriptor).toList());
	}

	@Test
	public void distinctInitialEstimatesRemainDistinctCostSurfaces() throws Exception {
		DMLProgram program = compileSmallProgram();
		var initialAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var initial = ExactPhysicalCostModel.physicalCostSurface(initialAnalysis,
			ExactPhysicalModel.build(initialAnalysis));
		mutateOutputEstimates(initialAnalysis.occurrences(), OpOpData.TRANSIENTREAD, null, 3.0);
		var changedAnalysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		var changed = ExactPhysicalCostModel.physicalCostSurface(changedAnalysis,
			ExactPhysicalModel.build(changedAnalysis));
		Assert.assertEquals(initialAnalysis.analysisFingerprint(), changedAnalysis.analysisFingerprint());
		Assert.assertNotEquals(initial.transferKeys(), changed.transferKeys());
		Assert.assertNotEquals(initial.contributionFingerprint(), changed.contributionFingerprint());
	}

	private static DMLProgram compileSmallProgram() throws Exception {
		String script = String.join("\n",
			"X=federated(addresses=list(\"localhost:1234/X1\",\"localhost:1235/X2\"),"
				+ "ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));",
			"stable=matrix(1,rows=3,cols=1);",
			"for(i in 1:3) { A=X%*%stable; print(sum(A)); }") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, Privacy.PUBLIC);
		return program;
	}

	private static Set<Hop> mutateOutputEstimates(
		java.util.List<org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection> occurrences,
		OpOpData operation, String name, double scale) throws Exception {
		Field outputEstimate = Hop.class.getDeclaredField("_outputMemEstimate");
		outputEstimate.setAccessible(true);
		Set<Hop> mutated = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var occurrence : occurrences) {
			Hop hop = occurrence.hop();
			if(!(hop instanceof DataOp data) || data.getOp() != operation
				|| name != null && !name.equals(hop.getName())
				|| !mutated.add(hop))
				continue;
			double original = hop.getOutputMemEstimate();
			double replacement = Double.isFinite(original) && original > 0.0
				? original * scale + 1.0 : 4097.0;
			outputEstimate.setDouble(hop, replacement);
		}
		return mutated;
	}
}
