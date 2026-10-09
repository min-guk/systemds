/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeMultiSeedPublicationTest {
	private static final PlacementEmissionState ROW_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void multiSeedProductsPublishPerLayoutAndRetainedAuthorityIsKeyScoped()
		throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("multi-seed-source");
		CompiledHopKey consumerOwner = fixtureKey("multi-seed-consumer");
		DurableAnchorKey poolA = pool("pool-a", "worker-a");
		DurableAnchorKey poolB = pool("pool-b", "worker-b");
		DurableAnchorKey poolC = pool("pool-c", "worker-c");
		List<CandidateEmissionRealization> variants = new ArrayList<>(List.of(
			exactSource(sourceOwner, poolA, "a-1"),
			exactSource(sourceOwner, poolA, "a-2"),
			exactSource(sourceOwner, poolB, "b-1"),
			exactSource(sourceOwner, poolB, "b-2")));
		CandidateRuleFact source = sourceFact(sourceOwner, variants);
		CandidateRealizationReference retainedReference =
			CandidateRealizationReference.of(source.key(), variants.get(0));
		CandidateEmissionRealization unrelated = unrelatedRetainedProduct(
			consumerOwner, retainedReference, poolA);
		CandidateRuleFact consumer = consumerFact(consumerOwner, unrelated);
		DataOp sourceHop = new DataOp("multi-seed-source", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "multi-seed-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("multi-seed-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false);

		CandidateRuleFact first = bind(closure, source, consumer, sourceHop, consumerHop,
			Map.of(poolA, 2, poolB, 2));
		List<CandidateEmissionRealization> firstRealizations = realizations(first);
		Assert.assertTrue("unrelated retained authority must survive by exact identity",
			firstRealizations.stream().anyMatch(realization -> realization == unrelated));
		List<CandidateEmissionRealization> firstProducts = productsExcept(
			firstRealizations, unrelated);
		Assert.assertEquals("each distinct seed layout must retain its own lazy product", 2,
			firstProducts.size());
		assertCanonicalOrder(firstRealizations);
		assertProductMembers(firstProducts, consumerOwner, sourceOwner, Map.of(
			poolA, List.of(variants.get(0), variants.get(1)),
			poolB, List.of(variants.get(2), variants.get(3))));
		CandidateEmissionRealization sameKeyStaging = new CandidateEmissionRealization(
			productForSeed(firstProducts, poolA).key(),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact staged = bind(new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false), source,
			fact(consumer.key(), List.of(sameKeyStaging)), sourceHop, consumerHop,
			Map.of(poolA, 2, poolB, 2));
		Assert.assertTrue("an empty staging clause under the publication key must not block it",
			realizations(staged).stream().filter(realization -> realization.key().equals(
				sameKeyStaging.key())).anyMatch(realization ->
					realization.supportClauses() instanceof NativeContinuitySupportClauses));

		variants.add(exactSource(sourceOwner, poolC, "c-1"));
		variants.add(exactSource(sourceOwner, poolC, "c-2"));
		CandidateRuleFact second = bind(closure, sourceFact(sourceOwner, variants), first,
			sourceHop, consumerHop, Map.of(poolA, 2, poolB, 2, poolC, 2));
		List<CandidateEmissionRealization> secondRealizations = realizations(second);
		Assert.assertTrue(secondRealizations.stream().anyMatch(realization -> realization == unrelated));
		List<CandidateEmissionRealization> secondProducts = productsExcept(
			secondRealizations, unrelated);
		Assert.assertEquals("a later distinct layout must grow the product union", 3,
			secondProducts.size());
		for(CandidateEmissionRealization retained : firstProducts)
			Assert.assertTrue("covered products must retain their exact proof authority",
				secondProducts.stream().anyMatch(realization -> realization == retained));
		assertCanonicalOrder(secondRealizations);
		assertProductMembers(secondProducts, consumerOwner, sourceOwner, Map.of(
			poolA, List.of(variants.get(0), variants.get(1)),
			poolB, List.of(variants.get(2), variants.get(3)),
			poolC, List.of(variants.get(4), variants.get(5))));

		CandidateEmissionRealization oldA = productForSeed(secondProducts, poolA);
		List<CandidateRealizationSupportClause> oldAClauses = List.copyOf(oldA.supportClauses());
		variants.add(exactSource(sourceOwner, poolA, "a-3"));
		CandidateRuleFact third = bind(closure, sourceFact(sourceOwner, variants), second,
			sourceHop, consumerHop, Map.of(poolA, 3, poolB, 2, poolC, 2));
		CandidateEmissionRealization grownA = realizations(third).stream()
			.filter(realization -> realization.key().equals(oldA.key())).findFirst().orElseThrow();
		Assert.assertFalse("same-key unequal product growth must use the exact scalar fallback",
			grownA.supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(3, grownA.supportClauses().size());
		Assert.assertEquals(Set.of(variants.get(0).key(), variants.get(1).key(), variants.get(6).key()),
			grownA.supportClauses().stream().map(clause -> clause.inputBindings().get(0)
				.source().realization()).collect(java.util.stream.Collectors.toSet()));
		for(CandidateRealizationSupportClause retainedClause : oldAClauses)
			Assert.assertTrue("same-key growth must retain every old clause object",
				grownA.supportClauses().stream().anyMatch(clause -> clause == retainedClause));
		CandidateRuleFact fourth = bind(closure, sourceFact(sourceOwner, variants), third,
			sourceHop, consumerHop, Map.of(poolA, 3, poolB, 2, poolC, 2));
		Assert.assertSame("an unchanged nonempty scalar fallback must replay exactly", grownA,
			realizations(fourth).stream().filter(realization -> realization.key().equals(
				grownA.key())).findFirst().orElseThrow());

		List<CandidateEmissionRealization> reducedVariants = new ArrayList<>(variants);
		reducedVariants.remove(3);
		CandidateRuleFact reducedSource = sourceFact(sourceOwner, reducedVariants);
		List<CandidateRuleFact> pruned = PlacementSupportRelations.pruneUnsupportedRealizations(
			List.of(reducedSource, fourth));
		CandidateRuleFact prunedConsumer = pruned.stream().filter(fact ->
			fact.key().parentOccurrence() == consumerOwner).findFirst().orElseThrow();
		Assert.assertTrue("withdrawal must remove support before restoration",
			expandedOrderedClauses(prunedConsumer).size() < expandedOrderedClauses(fourth).size());
		Assert.assertFalse("the withdrawn source realization must be absent after pruning",
			referencesRealization(prunedConsumer, variants.get(3)));
		List<CandidateEmissionRealization> prunedProducts = productsExcept(
			realizations(prunedConsumer), unrelated);
		Assert.assertEquals("pool B must shrink from two alternatives to one", 1,
			productForSeed(prunedProducts, poolB).supportClauses().size());
		Assert.assertSame("unaffected pool C authority must survive pruning",
			productForSeed(productsExcept(realizations(fourth), unrelated), poolC),
			productForSeed(prunedProducts, poolC));
		Assert.assertSame("unaffected pool A scalar authority must survive pruning", grownA,
			realizations(prunedConsumer).stream().filter(realization ->
				realization.key().equals(grownA.key())).findFirst().orElseThrow());
		CandidateRuleFact restored = bind(closure, sourceFact(sourceOwner, variants),
			prunedConsumer, sourceHop, consumerHop, Map.of(poolA, 3, poolB, 2, poolC, 2));
		CandidateRuleFact cold = bind(new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false),
			sourceFact(sourceOwner, variants), consumerFact(consumerOwner, unrelated),
			sourceHop, consumerHop, Map.of(poolA, 3, poolB, 2, poolC, 2));
		Assert.assertEquals("withdrawal, pruning, and restoration must equal cold expanded support",
			expandedOrderedClauses(cold), expandedOrderedClauses(restored));

		long publishedProofs = metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome() == SearchSpaceMetrics.NativePublicationOutcome.PUBLISHED)
			.mapToLong(SearchSpaceMetrics.NativePublicationCount::logicalProofs).sum();
		long coveredQueries = metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome() == SearchSpaceMetrics.NativePublicationOutcome.COVERED_RETAINED)
			.mapToLong(SearchSpaceMetrics.NativePublicationCount::queries).sum();
		Assert.assertTrue("publication diagnostics must include the multi-member lazy products",
			publishedProofs >= 6);
		Assert.assertTrue("later waves must identify exact retained product authority",
			coveredQueries >= 2);
	}

	@Test
	public void knownOutputShapeKeepsExactMultiSeedsOnDurableScalarPath()
		throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("durable-collision-source");
		CompiledHopKey consumerOwner = fixtureKey("durable-collision-consumer");
		DurableAnchorKey firstPool = pool("first", "first-worker");
		DurableAnchorKey secondPool = pool("second", "second-worker");
		List<CandidateEmissionRealization> variants = List.of(
			exactSource(sourceOwner, firstPool, "first-1"),
			exactSource(sourceOwner, firstPool, "first-2"),
			exactSource(sourceOwner, secondPool, "second-1"),
			exactSource(sourceOwner, secondPool, "second-2"));
		CandidateRuleFact source = sourceFact(sourceOwner, variants);
		CandidateRuleFact consumer = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("durable-collision-source", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "durable-collision-source",
			8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("durable-collision-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false);
		CandidateRuleFact rebound = bind(closure, source, consumer, sourceHop, consumerHop,
			Map.of(firstPool, 2, secondPool, 2),
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));

		List<CandidateEmissionRealization> durable = realizations(rebound).stream()
			.filter(realization -> realization.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP).toList();
		Assert.assertEquals("each worker layout must retain a durable output", 2, durable.size());
		Assert.assertTrue(durable.stream().noneMatch(realization ->
			realization.supportClauses() instanceof NativeContinuitySupportClauses));
		Assert.assertEquals("the durable path must retain every exact seed proof", 4,
			durable.stream().mapToInt(realization -> realization.supportClauses().size()).sum());
		Assert.assertEquals(Set.copyOf(variants.stream().map(CandidateEmissionRealization::key).toList()),
			durable.stream().flatMap(realization -> realization.supportClauses().stream())
				.map(clause -> clause.inputBindings().get(0).source().realization())
				.collect(java.util.stream.Collectors.toSet()));
		Map<PlacementIdentity.PlacementRealizationKey,DurableAnchorKey> seedBySource = Map.of(
			variants.get(0).key(), firstPool, variants.get(1).key(), firstPool,
			variants.get(2).key(), secondPool, variants.get(3).key(), secondPool);
		for(CandidateEmissionRealization realization : durable)
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				CandidateRealizationInputBinding binding = clause.inputBindings().get(0);
				DurableAnchorKey seed = seedBySource.get(binding.source().realization());
				NativePlacementContinuity.NativeContinuityProof expectedProof =
					new NativePlacementContinuity.NativeContinuityProof(seed,
						durableOutput(seed, consumerOwner), true, List.of(binding));
				Assert.assertEquals("each durable receipt must retain its exact seed/source proof",
					List.of(expectedProof.continuityProofKey(consumerOwner)),
					clause.proofDependencies());
			}
		SearchSpaceMetrics.NativePublicationCount rejected = metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome() == SearchSpaceMetrics.NativePublicationOutcome.DURABLE_OUTPUT)
			.findFirst().orElseThrow();
		Assert.assertEquals(2, rejected.queries());
		Assert.assertEquals(4, rejected.logicalProofs());
		Assert.assertEquals("durable product rejection must consume every scalar proof",
			4, rejected.consumedProofs());
	}

	private static CandidateRuleFact bind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, Map<DurableAnchorKey,Integer> expectedProductWidths) throws Exception {
		return bind(closure, source, consumer, sourceHop, consumerHop, expectedProductWidths,
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, -1, -1));
	}

	private static CandidateRuleFact bind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, Map<DurableAnchorKey,Integer> expectedProductWidths,
		PlacementAnalysis.NodeShapeFact consumerShape) throws Exception {
		List<CandidateRuleFact> inventory = List.of(source, consumer);
		List<Node> nodes = List.of(fixtureNode(source.key().parentOccurrence(), List.of()),
			fixtureNode(consumer.key().parentOccurrence(), List.of()));
		List<CompiledInputEdgeFact> edges = List.of(new CompiledInputEdgeFact(
			source.key().parentOccurrence(), consumer.key().parentOccurrence(), 0));
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(source.key().parentOccurrence(), sourceHop);
		origins.put(consumer.key().parentOccurrence(), consumerHop);
		Map<Hop,PlacementAnalysis.NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(sourceHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(consumerHop, consumerShape);
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, inventory, nodes, edges, inventory, origins, shapes);
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, inventory, edges, Map.of());
		CandidateEmissionFact consumerEmission = consumer.allowedEmissionFacts().get(0);
		for(Map.Entry<DurableAnchorKey,Integer> expected : expectedProductWidths.entrySet()) {
			CandidateRealizationReference proposed = new CandidateRealizationReference(
				consumer.key(), PlacementIdentity.PlacementRealizationKey.nativeLineage(
					consumerEmission.emissionState(),
					"query-output:" + expected.getKey().normalizedSignature()));
			NativePlacementContinuity.CandidateSupportResult query =
				continuity.proveGeneratedCandidateSupport(
					consumer, consumerEmission, proposed, expected.getKey());
			Assert.assertNotNull("the exact query must retain its factored product",
				query.supportProduct());
			Assert.assertEquals("query product width differs from its source alternatives",
				expected.getValue().intValue(), query.supportProduct().size());
		}
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
		Method dependencies = result.getClass().getDeclaredMethod("dependencyOccurrences");
		dependencies.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,Set<CompiledHopKey>> footprint =
			(Map<CompiledHopKey,Set<CompiledHopKey>>)dependencies.invoke(result);
		Assert.assertTrue("the publication footprint must retain its exact source owner",
			footprint.get(consumer.key().parentOccurrence()).contains(source.key().parentOccurrence()));
		return rebound.get(0);
	}

	private static CandidateRuleFact sourceFact(CompiledHopKey owner,
		List<CandidateEmissionRealization> variants) {
		return fact(new CandidateRuleKey(owner, List.of()), variants);
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner,
		CandidateEmissionRealization unrelated) {
		CandidateRuleFact staging = consumerFact(owner);
		return fact(staging.key(), List.of(unrelated,
			staging.allowedEmissionFacts().get(0).realizations().get(0)));
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner) {
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, "multi-seed-staging", List.of(), List.of());
		return fact(new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW))), List.of(staging));
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) {
		CandidateEmissionFact emission = new CandidateEmissionFact(
			ROW_EMISSION, FType.ROW, null, realizations);
		return new CandidateRuleFact(key, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(key.orderedInputs().stream()
				.map(CandidateInputState::fType).toList(), ""), List.of(emission), "");
	}

	private static CandidateEmissionRealization exactSource(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage) {
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "source-" + lineage);
		return CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, lineage, pool, List.of(proof), List.of());
	}

	private static CandidateEmissionRealization unrelatedRetainedProduct(
		CompiledHopKey owner, CandidateRealizationReference source, DurableAnchorKey pool) {
		CandidateRealizationInputBinding binding =
			CandidateRealizationInputBinding.direct(0, source);
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				pool, pool, false, List.of(List.of(binding)));
		return new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				ROW_EMISSION, "unrelated-retained-key"),
			new NativeContinuitySupportClauses(owner, product, pool, false));
	}

	private static List<CandidateEmissionRealization> realizations(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().get(0).realizations();
	}

	private static List<String> expandedOrderedClauses(CandidateRuleFact fact) {
		List<String> expanded = new ArrayList<>();
		for(CandidateEmissionRealization realization : realizations(fact))
			for(CandidateRealizationSupportClause clause : realization.supportClauses())
				expanded.add(realization.key().normalizedSignature() + '|' + clause.normalizedSignature());
		return List.copyOf(expanded);
	}

	private static boolean referencesRealization(CandidateRuleFact fact,
		CandidateEmissionRealization source) {
		return realizations(fact).stream().flatMap(realization -> realization.supportClauses().stream())
			.flatMap(clause -> clause.inputBindings().stream())
			.anyMatch(binding -> binding.source().realization().equals(source.key()));
	}

	private static List<CandidateEmissionRealization> productsExcept(
		List<CandidateEmissionRealization> realizations, CandidateEmissionRealization excluded) {
		return realizations.stream().filter(realization -> realization != excluded)
			.filter(realization -> realization.supportClauses() instanceof NativeContinuitySupportClauses)
			.toList();
	}

	private static CandidateEmissionRealization productForSeed(
		List<CandidateEmissionRealization> products, DurableAnchorKey seed) {
		return products.stream().filter(realization ->
			((NativeContinuitySupportClauses)realization.supportClauses())
				.product().externalSeed().equals(seed)).findFirst().orElseThrow();
	}

	private static void assertProductMembers(List<CandidateEmissionRealization> publications,
		CompiledHopKey owner, CompiledHopKey sourceOwner,
		Map<DurableAnchorKey,List<CandidateEmissionRealization>> expected) {
		Assert.assertEquals(expected.keySet(), publications.stream().map(realization ->
			((NativeContinuitySupportClauses)realization.supportClauses())
				.product().externalSeed()).collect(java.util.stream.Collectors.toSet()));
		for(CandidateEmissionRealization publication : publications) {
			NativeContinuitySupportClauses relation =
				(NativeContinuitySupportClauses)publication.supportClauses();
			List<CandidateEmissionRealization> expectedMembers =
				expected.get(relation.product().externalSeed());
			Assert.assertEquals(expectedMembers.size(), relation.size());
			Assert.assertEquals(expectedMembers.stream().map(CandidateEmissionRealization::key)
				.sorted(PlacementAnalysis.canonicalComparator()).toList(),
				relation.stream().map(clause -> clause.inputBindings().get(0).source().realization()).toList());
			for(CandidateRealizationSupportClause clause : relation) {
				NativePlacementContinuity.NativeContinuityProof expectedProof =
					new NativePlacementContinuity.NativeContinuityProof(
						relation.product().externalSeed(),
						relation.product().outputWorkerPoolWitness(),
						relation.product().exactPartitionRanges(), clause.inputBindings());
				Assert.assertEquals(List.of(expectedProof.continuityProofKey(owner)),
					clause.proofDependencies());
				Assert.assertSame("every product member must retain the exact source owner",
					sourceOwner, clause.inputBindings().get(0).source().rule().parentOccurrence());
				Assert.assertEquals(0, clause.inputBindings().get(0).inputPosition());
			}
		}
	}

	private static void assertCanonicalOrder(List<CandidateEmissionRealization> realizations) {
		for(int index = 1; index < realizations.size(); index++)
			Assert.assertTrue(PlacementAnalysis.canonicalComparator().compare(
				realizations.get(index - 1), realizations.get(index)) < 0);
	}

	private static CompiledHopKey fixtureKey(String name) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod("key", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static Node fixtureNode(CompiledHopKey key, List<DurableAnchorKey> anchors)
		throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod(
			"node", CompiledHopKey.class, List.class);
		method.setAccessible(true);
		return (Node)method.invoke(null, key, anchors);
	}

	private static DurableAnchorKey pool(String id, String worker) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition(worker, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static DurableAnchorKey durableOutput(DurableAnchorKey seed, CompiledHopKey owner) {
		return new DurableAnchorKey("native-output:" + owner.normalizedSignature(), FType.ROW,
			seed.partitions());
	}
}
