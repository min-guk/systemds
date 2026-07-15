/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Independent compiled-occurrence-to-origin identity oracle for the production analysis projection. */
public class PlacementAnalysisOriginProjectionTest {
	@Test
	public void everyGraphKeyProjectsToItsExactCompiledOriginIncludingSyntheticContexts() throws Exception {
		for(String fixture : List.of("B-07", "B-17", "B-21")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			List<PlacementGraphFingerprint.HopOccurrence> compiled =
				PlacementGraphFingerprint.orderedOccurrences(program);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			Map<Hop,Integer> multiplicity = new IdentityHashMap<>();
			for(HopOccurrenceProjection projection : analysis.occurrences()) {
				Hop expected = independentlyResolve(projection, compiled);
				Assert.assertSame(fixture + " wrong origin for " + projection.key(), expected, projection.hop());
				multiplicity.merge(projection.hop(), 1, Integer::sum);
			}
			Assert.assertEquals(fixture, analysis.graph().nodes().size(), analysis.occurrences().size());
			Assert.assertTrue(fixture + " did not retain synthetic/context keys",
				analysis.occurrences().size() > compiled.size());
			Assert.assertTrue(fixture + " synthetic keys did not preserve the originating Hop identity",
				multiplicity.values().stream().anyMatch(count -> count > 1));
		}
	}

	private static Hop independentlyResolve(HopOccurrenceProjection projection,
		List<PlacementGraphFingerprint.HopOccurrence> compiled) {
		String origin = projection.key().canonicalSourceOrigin();
		if(origin.startsWith("function-boundary:")) {
			return compiled.stream().filter(value -> value.hop() instanceof FunctionOp)
				.filter(value -> origin.startsWith("function-boundary:"
					+ ((FunctionOp) value.hop()).getFunctionKey() + ':'))
				.filter(value -> projection.key().callSitePath().startsWith(value.path() + "->"))
				.map(PlacementGraphFingerprint.HopOccurrence::hop).findFirst().orElseThrow();
		}
		return compiled.stream()
			.filter(value -> projection.key().callSitePath().equals(value.path()))
			.filter(value -> projection.key().emittedHopInstance().equals(value.topology()))
			.filter(value -> origin.equals(PlacementGraphFingerprint.semanticStructuralKey(value.hop())))
			.map(PlacementGraphFingerprint.HopOccurrence::hop).findFirst().orElseThrow();
	}
}
