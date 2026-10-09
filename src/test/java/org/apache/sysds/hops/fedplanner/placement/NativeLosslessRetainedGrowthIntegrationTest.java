/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeLosslessRetainedGrowthIntegrationTest {
	@Test
	public void completeHeaderGrowthRemainsLazy() throws Exception {
		// The production binder creates two complete headers, retains their exact
		// old member objects, and then grows their common axis without a handle.
		new NativeMultiSeedPublicationTest()
			.identicalBindingAxesAcrossCollidingSeedHeadersStayCompressed();
	}

	@Test
	public void filteredNonRequiredMemberUsesFullScalarFallback()
		throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("lossless-binder-source");
		CompiledHopKey consumerOwner = fixtureKey("lossless-binder-consumer");
		DurableAnchorKey firstPool = pool("lossless-binder-a", "shared-worker", 2);
		DurableAnchorKey secondPool = pool("lossless-binder-b", "shared-worker", 5);
		List<CandidateEmissionRealization> oldVariants = List.of(
			exactSource(sourceOwner, firstPool, "a-1"),
			exactSource(sourceOwner, firstPool, "a-2"),
			exactSource(sourceOwner, secondPool, "b-1"),
			exactSource(sourceOwner, secondPool, "b-2"),
			exactSource(sourceOwner, firstPool, "dead-non-required"));
		List<CandidateEmissionRealization> variants = List.of(
			oldVariants.get(0), oldVariants.get(1), oldVariants.get(2), oldVariants.get(3),
			CandidateEmissionRealization.nativeLineage(rowEmission(),
				"dead-non-required", List.of(), List.of()));
		CandidateRuleFact source = sourceFact(sourceOwner, variants);
		CandidateRuleFact priorSource = sourceFact(sourceOwner, oldVariants);
		CandidateRuleFact consumer = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("lossless-binder-source", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "lossless-binder-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("lossless-binder-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		BindResult result = bindWithSourceShape(new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false), source, consumer,
			sourceHop, consumerHop, new PlacementAnalysis.NodeShapeFact(DataType.SCALAR, 1, 1),
			priorSource, List.of(firstPool, secondPool));
		CandidateRuleFact rebound = result.fact();

		List<PlacementAnalysis.CandidateRealizationSupportClause> clauses = rebound
			.allowedEmissionFacts().get(0).realizations().stream()
			.flatMap(realization -> realization.supportClauses().stream()).toList();
		long dead = clauses.stream().filter(clause -> clause.inputBindings().stream()
			.anyMatch(binding -> binding.source().realization().equals(variants.get(4).key()))).count();
		Assert.assertEquals("both seed proofs must retain the filtered non-required source", 2, dead);
		Assert.assertEquals("the complete scalar path retains all five members for both seeds", 10,
			clauses.size());
		Assert.assertTrue("a filtered descriptor must not be retained as a native relation",
			rebound.allowedEmissionFacts().get(0).realizations().stream().noneMatch(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses));
		for(var clause : clauses) {
			Assert.assertSame(consumerOwner, clause.proofDependencies().get(0).owner());
			Assert.assertSame(sourceOwner,
				clause.inputBindings().get(0).source().rule().parentOccurrence());
		}
		Assert.assertEquals("scalar fallback must retain the complete ordered proof/source authority",
			result.expectedAuthority(), authority(rebound));
		Assert.assertEquals(10L,
			metrics.directWorkCount(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED));
	}

	@Test
	public void singleProspectiveOutputAndMixedSplitAlsoKeepFilteredReceipts() throws Exception {
		assertSingleFilteredFallback(false);
		assertSingleFilteredFallback(true);
	}

	private static void assertSingleFilteredFallback(boolean mixed) throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("single-filtered-source-" + mixed);
		CompiledHopKey consumerOwner = fixtureKey("single-filtered-consumer-" + mixed);
		DurableAnchorKey pool = pool("single-filtered-pool-" + mixed, "worker", 2);
		CandidateEmissionRealization live = exactSource(sourceOwner, pool, "live");
		CandidateEmissionRealization second = mixed
			? CandidateEmissionRealization.nativeLineageDynamicLayout(rowEmission(), "dynamic", pool,
				List.of(new PlacementIdentity.PlacementProofKey(
					PlacementIdentity.PlacementProofKind.NATIVE_CONTINUITY, sourceOwner, "dynamic")), List.of())
			: exactSource(sourceOwner, pool, "second");
		CandidateEmissionRealization priorDead = exactSource(sourceOwner, pool, "dead");
		CandidateEmissionRealization currentDead = CandidateEmissionRealization.nativeLineage(
			rowEmission(), "dead", List.of(), List.of());
		CandidateRuleFact prior = sourceFact(sourceOwner, List.of(live, second, priorDead));
		CandidateRuleFact current = sourceFact(sourceOwner, List.of(live, second, currentDead));
		CandidateRuleFact consumer = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("single-filtered-source", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "single-filtered-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("single-filtered-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		BindResult result = bindWithSourceShape(new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false), current, consumer,
			sourceHop, consumerHop, new PlacementAnalysis.NodeShapeFact(DataType.SCALAR, 1, 1),
			prior, List.of(pool));
		Assert.assertEquals(3, result.fact().allowedEmissionFacts().get(0).realizations().stream()
			.mapToInt(realization -> realization.supportClauses().size()).sum());
		Assert.assertEquals(result.expectedAuthority(), authority(result.fact()));
		Assert.assertTrue(authority(result.fact()).stream().anyMatch(value ->
			value.contains(currentDead.key().normalizedSignature())));
		Assert.assertEquals(3L,
			metrics.directWorkCount(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED));
	}

	@Test
	public void duplicateDiagnosticsUsesExactScalarFallback() throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("diagnostic-growth-source");
		CompiledHopKey consumerOwner = fixtureKey("diagnostic-growth-consumer");
		DurableAnchorKey firstPool = pool("diagnostic-a", "shared-worker", 2);
		DurableAnchorKey secondPool = pool("diagnostic-b", "shared-worker", 5);
		List<CandidateEmissionRealization> variants = new ArrayList<>(List.of(
			exactSource(sourceOwner, firstPool, "a-1"),
			exactSource(sourceOwner, firstPool, "a-2"),
			exactSource(sourceOwner, secondPool, "b-1"),
			exactSource(sourceOwner, secondPool, "b-2")));
		DataOp sourceHop = new DataOp("diagnostic-growth-source", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "diagnostic-growth-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("diagnostic-growth-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(8);
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false);
		CandidateRuleFact first = invokeStandardBind(closure, sourceFact(sourceOwner, variants),
			consumerFact(consumerOwner), sourceHop, consumerHop,
			Map.of(firstPool, 4, secondPool, 4));
		long consumedBeforeGrowth = metrics.directWorkCount(
			SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED);
		variants.add(exactSource(sourceOwner, firstPool, "added"));
		CandidateRuleFact grown = invokeStandardBind(closure, sourceFact(sourceOwner, variants),
			first, sourceHop, consumerHop, Map.of(firstPool, 5, secondPool, 5));
		CandidateRuleFact cold = invokeStandardBind(new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false),
			sourceFact(sourceOwner, variants), consumerFact(consumerOwner), sourceHop,
			consumerHop, Map.of(firstPool, 5, secondPool, 5));

		Assert.assertEquals("diagnostic fallback must preserve exact ordered proof/source authority",
			expanded(cold), expanded(grown));
		Assert.assertTrue(metrics.duplicateMergeDiagnosticsSnapshot().enabled());
		Assert.assertTrue("diagnostic mode must consume the exact scalar fallback",
			metrics.directWorkCount(SearchSpaceMetrics.DirectWork.PROOFS_CONSUMED)
				> consumedBeforeGrowth);
	}

	private static BindResult bindWithSourceShape(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, PlacementAnalysis.NodeShapeFact sourceShape,
		CandidateRuleFact continuitySource, List<DurableAnchorKey> seeds) throws Exception {
		List<CandidateRuleFact> inventory = List.of(source, consumer);
		List<Node> nodes = List.of(fixtureNode(source.key().parentOccurrence()),
			fixtureNode(consumer.key().parentOccurrence()));
		List<CompiledInputEdgeFact> edges = List.of(new CompiledInputEdgeFact(
			source.key().parentOccurrence(), consumer.key().parentOccurrence(), 0));
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(source.key().parentOccurrence(), sourceHop);
		origins.put(consumer.key().parentOccurrence(), consumerHop);
		Map<Hop,PlacementAnalysis.NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(sourceHop, sourceShape);
		shapes.put(consumerHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, inventory, nodes, edges, inventory, origins, shapes);
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, List.of(continuitySource, consumer), edges, Map.of());
		List<String> expectedAuthority = new ArrayList<>();
		for(DurableAnchorKey seed : seeds) {
			var emission = consumer.allowedEmissionFacts().get(0);
			var proposed = new PlacementIdentity.CandidateRealizationReference(consumer.key(),
				PlacementIdentity.PlacementRealizationKey.nativeLineage(
					emission.emissionState(), "expected:" + seed.normalizedSignature()));
			var support = continuity.proveGeneratedCandidateSupport(
				consumer, emission, proposed, seed);
			for(var proof : support.proofs())
				expectedAuthority.add(authority(proof.continuityProofKey(
					consumer.key().parentOccurrence()), proof.immediateBindings()));
		}
		expectedAuthority.sort(String::compareTo);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", index.getClass(),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Set<CompiledHopKey> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
		dirty.add(consumer.key().parentOccurrence());
		Object result = bind.invoke(closure, index, List.of(consumer), origins, shapes,
			continuity, dirty);
		Method facts = result.getClass().getDeclaredMethod("facts");
		facts.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> rebound = (List<CandidateRuleFact>)facts.invoke(result);
		return new BindResult(rebound.get(0), List.copyOf(expectedAuthority));
	}

	private static List<String> authority(CandidateRuleFact fact) {
		List<String> authority = new ArrayList<>();
		for(var realization : fact.allowedEmissionFacts().get(0).realizations())
			for(var clause : realization.supportClauses())
				authority.add(authority(clause.proofDependencies().get(0), clause.inputBindings()));
		authority.sort(String::compareTo);
		return List.copyOf(authority);
	}

	private static String authority(PlacementIdentity.PlacementProofKey proof,
		List<PlacementIdentity.CandidateRealizationInputBinding> bindings) {
		return proof.normalizedSignature() + '|' + bindings.stream()
			.map(PlacementIdentity.CandidateRealizationInputBinding::normalizedSignature)
			.collect(java.util.stream.Collectors.joining(","));
	}

	private record BindResult(CandidateRuleFact fact, List<String> expectedAuthority) { }

	private static CandidateRuleFact invokeStandardBind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, Map<DurableAnchorKey,Integer> widths) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("bind",
			PlacementRelationClosure.class, CandidateRuleFact.class, CandidateRuleFact.class,
			DataOp.class, UnaryOp.class, Map.class, PlacementAnalysis.NodeShapeFact.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, closure, source, consumer,
			sourceHop, consumerHop, widths,
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
	}

	private static CandidateRuleFact sourceFact(CompiledHopKey owner,
		List<CandidateEmissionRealization> realizations) throws Exception {
		return (CandidateRuleFact)invoke("sourceFact",
			new Class<?>[] {CompiledHopKey.class, List.class}, owner, realizations);
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner) throws Exception {
		return (CandidateRuleFact)invoke("consumerFact",
			new Class<?>[] {CompiledHopKey.class}, owner);
	}

	private static CandidateEmissionRealization exactSource(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage) throws Exception {
		return (CandidateEmissionRealization)invoke("exactSource",
			new Class<?>[] {CompiledHopKey.class, DurableAnchorKey.class, String.class},
			owner, pool, lineage);
	}

	private static CompiledHopKey fixtureKey(String name) throws Exception {
		return (CompiledHopKey)invoke("fixtureKey", new Class<?>[] {String.class}, name);
	}

	private static Node fixtureNode(CompiledHopKey key) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod(
			"node", CompiledHopKey.class, List.class);
		method.setAccessible(true);
		return (Node)method.invoke(null, key, List.of());
	}

	private static Object invoke(String name, Class<?>[] parameters, Object... arguments)
		throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method.invoke(null, arguments);
	}

	private static DurableAnchorKey pool(String id, String worker, long columns) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new PlacementIdentity.AnchorPartition(worker, List.of(0L, 0L), List.of(8L, columns))));
	}

	private static PlacementEmissionState rowEmission() throws Exception {
		var field = NativeMultiSeedPublicationTest.class.getDeclaredField("ROW_EMISSION");
		field.setAccessible(true);
		return (PlacementEmissionState)field.get(null);
	}

	private static List<String> expanded(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().get(0).realizations().stream()
			.flatMap(realization -> realization.supportClauses().stream().map(clause ->
				realization.key().normalizedSignature() + '|' + clause.normalizedSignature()))
			.toList();
	}
}
