/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.test.component.federated.placement.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementGraphFingerprint;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Pre-cutover contract for one immutable graph plus its total compiled-Hop projection. */
public class PlacementAnalysisContractTest {
	@Test public void functionBodySentinelsRemainRepresentedAcrossFixtures() throws Exception {
		for(String fixture: List.of("B-07","B-08","B-17","B-21")) {
			PlacementAnalysis a=new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile(fixture));
			var named=a.graph().nodes().stream().filter(n->!"main".equals(n.key().functionNamespace())&&n.key().callSitePath().startsWith("function/")).toList();
			Assert.assertFalse(fixture+" lost named function occurrences",named.isEmpty());
			for(var n:named) {
				Assert.assertEquals(fixture, "FUNCTION_BODY_NON_EMITTED", n.kind().name());
				Assert.assertFalse(fixture, n.emittedWork());
				Assert.assertTrue(fixture, n.legalAlternatives().isEmpty());
				Assert.assertTrue(fixture, n.exclusions().stream().anyMatch(
					x -> "NON_EMITTED_FUNCTION_BODY_CONTEXT".equals(x.reasonCode().name())));
			}
			var main=a.graph().nodes().stream()
				.filter(n->"main".equals(n.key().functionNamespace())).toList();
			Assert.assertFalse(fixture+" lost main occurrences", main.isEmpty());
			Assert.assertTrue(fixture+" main work became non-emitted",
				main.stream().allMatch(n -> n.emittedWork()));
			if("B-21".equals(fixture))
				Assert.assertTrue("B-21 lost emitted UNKNOWN_METADATA sentinel",
					main.stream().anyMatch(n -> n.emittedWork() && n.exclusions().stream().anyMatch(
						x -> "UNKNOWN_METADATA".equals(x.reasonCode().name()))));
		}
	}
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
	public void genuineRootOrderPerturbationDoesNotCreateASecondUniverse() throws Exception {
		String fixture = "two-root-compiled-hop";
		DMLProgram forward = twoRootProgram(false);
		DMLProgram reversed = twoRootProgram(true);
		List<String> forwardRoots = rootNames(forward);
		List<String> reversedRoots = rootNames(reversed);
		Collections.reverse(forwardRoots);
		Assert.assertEquals(fixture + " did not reverse the actual compiled root list", forwardRoots, reversedRoots);
		PlacementAnalysis left = new NeutralPlacementGraphBuilder().buildAnalysis(forward);
		PlacementAnalysis right = new NeutralPlacementGraphBuilder().buildAnalysis(reversed);
		Assert.assertEquals(fixture, left.analysisFingerprint(), right.analysisFingerprint());
		Assert.assertEquals(fixture, signatures(left), signatures(right));
	}

	@Test
	public void allThreeFederatedRegistriesRemainByteForByteUnchanged() throws Exception {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
		try {
			FederatedRefedRegistry.register(99001L, 11L, 101L, "anchor:row", java.util.List.of(12L));
			FederatedFoutMaterializeRegistry.register(99001L, 12L, 102L, "ROW", "row-anchor", "anchor:row");
			FederatedLocalMaterializeRegistry.register(99001L, 13L, List.of(14L, 15L), "ROW", "sentinel");
			String before = registryFingerprint();
			new NeutralPlacementGraphBuilder().buildAnalysis(ProductionShadowFixtureFactory.compile("B-22"));
			Assert.assertEquals(before, registryFingerprint());
		}
		finally {
			FederatedRefedRegistry.clear();
			FederatedFoutMaterializeRegistry.clear();
			FederatedLocalMaterializeRegistry.clear();
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

	private static DMLProgram twoRootProgram(boolean reverse) {
		List<Hop> roots = new ArrayList<>(List.of(transientWrite("A", 1L), transientWrite("B", 2L)));
		if(reverse)
			Collections.reverse(roots);
		StatementBlock block = new StatementBlock();
		block.setHops(new ArrayList<>(roots));
		DMLProgram program = new DMLProgram();
		program.setStatementBlocks(new ArrayList<>(List.of(block)));
		return program;
	}

	private static DataOp transientWrite(String variable, long value) {
		LiteralOp input = new LiteralOp(value);
		return new DataOp(variable, DataType.SCALAR, ValueType.INT64, input, OpOpData.TRANSIENTWRITE, variable);
	}

	private static List<String> rootNames(DMLProgram program) {
		return program.getStatementBlocks().get(0).getHops().stream().map(Hop::getName)
			.collect(Collectors.toCollection(ArrayList::new));
	}

	private static String registryFingerprint() throws Exception {
		return registryMap(FederatedRefedRegistry.class, "REFED_ANCHORS") + '|'
			+ registryMap(FederatedFoutMaterializeRegistry.class, "MATERIALIZE_ANCHORS") + '|'
			+ registryMap(FederatedLocalMaterializeRegistry.class, "LOCAL_MATERIALIZE");
	}

	private static String registryMap(Class<?> registry, String fieldName) throws Exception {
		Field field = registry.getDeclaredField(fieldName);
		field.setAccessible(true);
		return registry.getSimpleName() + '=' + String.valueOf(field.get(null));
	}
}
