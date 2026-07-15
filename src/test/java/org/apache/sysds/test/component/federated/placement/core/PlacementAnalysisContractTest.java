/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Pre-cutover contract for one immutable graph plus its total compiled-Hop projection. */
public class PlacementAnalysisContractTest {
	@Test
	public void analysisOwnsOneGraphAndATotalStableProjection() throws Exception {
		for(String fixture : List.of("B-01", "B-07", "B-17", "B-20", "B-21")) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			String before = PlacementGraphFingerprint.capture(program);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			Assert.assertSame(fixture + " rebuilt its graph", analysis.graph(), analysis.graph());
			Assert.assertEquals(fixture + " projection/node cardinality", analysis.graph().nodes().size(),
				analysis.occurrences().size());
			Assert.assertEquals(fixture + " duplicate projection key", analysis.occurrences().size(),
				analysis.occurrences().stream().map(HopOccurrenceProjection::key).distinct().count());
			Assert.assertEquals(fixture + " ordinals are not normalized", normalizedOrdinals(analysis),
				analysis.occurrences().stream().map(HopOccurrenceProjection::normalizedOrdinal)
					.collect(Collectors.toList()));
			for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
				Assert.assertTrue(fixture + " projection key absent from graph: " + occurrence.key(),
					analysis.graph().node(occurrence.key()).isPresent());
				Assert.assertSame(fixture + " lookup changed Hop identity", occurrence.hop(),
					analysis.hop(occurrence.key()).orElseThrow());
			}
			Assert.assertEquals(fixture + " analysis mutated the compiled program", before,
				PlacementGraphFingerprint.capture(program));
			Assert.assertEquals(fixture + " graph changed while read", analysis.graph().normalizedSignature(),
				analysis.graph().normalizedSignature());
			Assert.assertEquals(fixture + " analysis fingerprint changed while read", analysis.analysisFingerprint(),
				analysis.analysisFingerprint());
		}
	}

	@Test
	public void independentCompilationsHaveTheSameNormalizedProjectionAndAnalysisFingerprint() throws Exception {
		for(String fixture : ProductionShadowFixtureFactory.ids()) {
			PlacementAnalysis left = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
			PlacementAnalysis right = new NeutralPlacementGraphBuilder()
				.buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
			Assert.assertEquals(fixture, signatures(left), signatures(right));
			Assert.assertEquals(fixture, left.analysisFingerprint(), right.analysisFingerprint());
		}
	}

	@Test
	public void projectionIsImmutableAndEveryEntryReferencesAConcreteCompiledHop() throws Exception {
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-21"));
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> analysis.occurrences().add(analysis.occurrences().get(0)));
		Set<Hop> identities = new HashSet<>();
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			Assert.assertNotNull(occurrence.hop());
			Assert.assertFalse(occurrence.normalizedSignature().isBlank());
			identities.add(occurrence.hop());
		}
		Assert.assertTrue("function/context expansion must not invent Hop objects",
			identities.size() <= analysis.occurrences().size());
	}

	private static List<Integer> normalizedOrdinals(PlacementAnalysis analysis) {
		List<Integer> values = new ArrayList<>();
		for(int i = 0; i < analysis.occurrences().size(); i++)
			values.add(i);
		return values;
	}

	private static List<String> signatures(PlacementAnalysis analysis) {
		return analysis.occurrences().stream().map(HopOccurrenceProjection::normalizedSignature)
			.collect(Collectors.toList());
	}
}
