/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class EarlyNativeCoverageOptimizationTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"early-native", "main", List.of("root"), "call", "compiled");
	private static final PlacementEmissionState NATIVE_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
	private static final PlacementEmissionState LOCAL_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);

	@Test
	public void typedFactoryPreservesPublicContractAndSnapshotsOnlyNativeDescriptor() throws Exception {
		CompiledHopKey owner = key("owner");
		CandidateRealizationInputBinding binding = directBinding(key("source"));
		NativeContinuityProof proof = proof("seed", "pool", true, List.of(binding));
		PlacementProofKey typed = nativeProofKey(owner, proof);
		PlacementProofKey generic = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, proof.normalizedSignature());

		Assert.assertEquals(generic, typed);
		Assert.assertEquals(generic.hashCode(), typed.hashCode());
		Assert.assertEquals(generic.toString(), typed.toString());
		Assert.assertSame("factory must retain the proof's one normalized String",
			proof.normalizedSignature(), typed.authoritySignature());
		Assert.assertNull("public proof construction must remain unmarked",
			nativeDescriptor(generic));
		Object descriptor = nativeDescriptor(typed);
		Assert.assertNotNull(descriptor);
		Assert.assertEquals(anchor("seed"), component(descriptor, "externalSeed"));
		Assert.assertEquals(anchor("pool"), component(descriptor, "outputWorkerPoolWitness"));
		Assert.assertEquals(true, component(descriptor, "exactPartitionRanges"));
		Assert.assertEquals(List.of(binding), component(descriptor, "immediateBindings"));
		Assert.assertSame("typed descriptor must retain the proof's immutable canonical list",
			proof.immediateBindings(), component(descriptor, "immediateBindings"));
		for(Field field : descriptor.getClass().getDeclaredFields()) {
			Assert.assertNotEquals(NativeContinuityProof.class, field.getType());
			Assert.assertNotEquals(PlacementAnalysis.NormalizedText.class, field.getType());
		}
	}

	@Test
	public void typedFactoryKeepsUtf16DelimiterParityWhileNormalizedTextFactoryStaysUnmarked()
		throws Exception {
		CompiledHopKey owner = key("owner|=;한글🚀");
		NativeContinuityProof proof = proof("seed|=;한글🚀", "pool\u0000|=;🚀", false,
			List.of(directBinding(key("source|=;읽기"))));
		PlacementProofKey typed = nativeProofKey(owner, proof);
		PlacementProofKey generic = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, proof.normalizedSignature());
		Assert.assertEquals(generic, typed);
		Assert.assertEquals(0, generic.compareTo(typed));

		Method normalizedFactory = PlacementProofKey.class.getDeclaredMethod("fromNormalizedText",
			PlacementProofKind.class, CompiledHopKey.class,
			PlacementAnalysis.NormalizedText.class);
		normalizedFactory.setAccessible(true);
		PlacementProofKey normalized = (PlacementProofKey)normalizedFactory.invoke(null,
			PlacementProofKind.NATIVE_CONTINUITY, owner,
			PlacementAnalysis.NormalizedText.literal(proof.normalizedSignature()));
		Assert.assertEquals(generic, normalized);
		Assert.assertNull("generic normalized text is not trusted typed-native authority",
			nativeDescriptor(normalized));
	}

	@Test
	public void typedFactoryReusesWarmStructuralSignatureWithoutRebuildingProofRope()
		throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		try {
			CompiledHopKey owner = key("warm-owner");
			NativeContinuityProof first = proof("warm-seed", "warm-pool", true,
				List.of(directBinding(key("warm-source"))));
			NativeContinuityProof equal = proof("warm-seed", "warm-pool", true,
				List.of(directBinding(key("warm-source"))));
			Assert.assertNull(proofString(first));
			Assert.assertNull(proofString(equal));
			PlacementProofKey firstKey = nativeProofKey(owner, first);
			PlacementProofKey equalKey = nativeProofKey(owner, equal);
			Assert.assertEquals(firstKey, equalKey);
			Assert.assertSame("structural cache hit must retain the existing normalized String",
				firstKey.authoritySignature(), equalKey.authoritySignature());
		}
		finally {
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void exactTypedCoverageHitLeavesEqualCandidateProofLazy() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		try {
			CompiledHopKey owner = key("owner"), source = key("source");
			List<CandidateRealizationInputBinding> bindings = List.of(directBinding(source));
			NativeContinuityProof retainedProof = proof("seed", "pool", true, bindings);
			NativeContinuityProof candidateProof = proof("seed", "pool", true, bindings);
			CandidateEmissionRealization retained = retained(owner, retainedProof, "lineage", bindings);
			Object preparation = prepare(new CandidateEmissionFact(
				NATIVE_EMISSION, FType.ROW, null, List.of(retained)));

			Assert.assertNull("fixture candidate must start without a flattened signature",
				proofString(candidateProof));
			Assert.assertTrue(covers(preparation, candidateProof, owner, directOutput(retained)));
			Assert.assertNull("typed descriptor matching must not flatten the candidate proof",
				proofString(candidateProof));
		}
		finally {
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void coverageRejectsHashCollisionForeignOwnerBindingAndOutputDifferences() throws Exception {
		CompiledHopKey owner = key("owner"), foreignOwner = key("owner");
		CompiledHopKey source = key("source"), foreignSource = key("source");
		List<CandidateRealizationInputBinding> bindings = List.of(directBinding(source));
		NativeContinuityProof retainedProof = proof("Aa", "Aa", true, bindings);
		CandidateEmissionRealization retained = retained(owner, retainedProof, "lineage", bindings);
		Object preparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(retained)));

		NativeContinuityProof collision = proof("BB", "BB", true, bindings);
		Assert.assertEquals("fixture must exercise one structural-hash collision bucket",
			retainedProof.hashCode(), collision.hashCode());
		Assert.assertFalse(covers(preparation, collision, owner, directOutput(retained)));
		NativeContinuityProof foreignBinding = proof("Aa", "Aa", true,
			List.of(directBinding(foreignSource)));
		Assert.assertFalse("equal source values cannot replace exact owner identity",
			covers(preparation, foreignBinding, owner, directOutput(retained)));
		Assert.assertFalse("equal proof owners cannot replace exact owner identity",
			covers(preparation, retainedProof, foreignOwner, directOutput(retained)));
		Assert.assertFalse(covers(preparation, retainedProof, owner,
			directOutput(PlacementRealizationKey.nativeLineage(NATIVE_EMISSION, "other-lineage"),
				anchor("pool"), true)));
		Assert.assertFalse(covers(preparation, retainedProof, owner,
			directOutput(retained.key(), anchor("changed-pool"), true)));
		Assert.assertFalse(covers(preparation, retainedProof, owner,
			directOutput(retained.key(), anchor("pool"), false)));
	}

	@Test
	public void unmarkedStagingAndForeignRelocationConsumersNeverAuthorizeCoverage() throws Exception {
		CompiledHopKey owner = key("owner"), consumer = key("consumer");
		CompiledHopKey foreignConsumer = key("consumer"), source = key("source");
		CandidateRealizationInputBinding binding = relocationBinding(source, consumer);
		NativeContinuityProof proof = proof("seed", "pool", true, List.of(binding));
		CandidateEmissionRealization retained = retained(owner, proof, "lineage", List.of(binding));
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, "staging", List.of(), List.of());
		Object stagingPreparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(staging, retained)));
		Assert.assertFalse(covers(stagingPreparation, proof, owner, directOutput(retained)));

		PlacementProofKey generic = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, proof.normalizedSignature());
		CandidateEmissionRealization unmarked = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, "lineage", anchor("pool"), List.of(generic), List.of(binding));
		Object unmarkedPreparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(unmarked)));
		Assert.assertFalse(covers(unmarkedPreparation, proof, owner, directOutput(unmarked)));

		NativeContinuityProof foreignRelocation = proof("seed", "pool", true,
			List.of(relocationBinding(source, foreignConsumer)));
		Object exactPreparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(retained)));
		Assert.assertFalse("relocation consumer identity remains authority",
			covers(exactPreparation, foreignRelocation, owner, directOutput(retained)));
	}

	@Test
	public void transplantedBindingsAndMultiProofClausesAreNotIndexed() throws Exception {
		CompiledHopKey owner = key("owner"), source = key("source"), other = key("other-source");
		List<CandidateRealizationInputBinding> proofBindings = List.of(directBinding(source));
		NativeContinuityProof proof = proof("seed", "pool", true, proofBindings);
		PlacementProofKey marker = nativeProofKey(owner, proof);
		CandidateEmissionRealization transplanted = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, "lineage", anchor("pool"), List.of(marker),
			List.of(directBinding(other)));
		Object transplantedPreparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(transplanted)));
		Assert.assertFalse("marker authority cannot be transplanted onto different clause bindings",
			covers(transplantedPreparation, proof, owner, directOutput(transplanted)));

		PlacementProofKey extra = new PlacementProofKey(
			PlacementProofKind.SHAPE, owner, "extra-proof");
		PlacementAnalysis.CandidateRealizationSupportClause multiClause =
			new PlacementAnalysis.CandidateRealizationSupportClause(
				List.of(marker, extra).stream().sorted().toList(), proofBindings,
				anchor("pool"), true);
		CandidateEmissionRealization multiProof = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(transplanted.key(), List.of(multiClause));
		Object multiPreparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(multiProof)));
		Assert.assertFalse("only singleton typed native proof clauses enter the early index",
			covers(multiPreparation, proof, owner, directOutput(multiProof)));
	}

	@Test
	public void publicationOverloadReturnsNullOnlyForCoveredCandidatesAndPreservesScheduleOrder()
		throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		try {
			CompiledHopKey owner = key("owner"), source = key("source");
			List<CandidateRealizationInputBinding> bindings = List.of(directBinding(source));
			NativeContinuityProof retainedProof = proof("seed", "pool", true, bindings);
			NativeContinuityProof coveredProof = proof("seed", "pool", true, bindings);
			CandidateEmissionRealization retained = referencePublication(
				new PlacementRelationClosure(null, null, null, false,
					NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false),
				retainedProof, owner, "lineage");
			Object preparation = prepare(new CandidateEmissionFact(
				NATIVE_EMISSION, FType.ROW, null, List.of(retained)));
			PlacementRelationClosure closure = new PlacementRelationClosure(null, null,
				new SearchSpaceMetrics(), false,
				NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);

			CandidateEmissionRealization covered = publication(
				closure, coveredProof, owner, "lineage", preparation);
			Assert.assertNull("covered publication is represented by the exact retained prefix", covered);
			Assert.assertNull("early coverage must run before candidate authority materialization",
				proofString(coveredProof));
			List<CandidateEmissionRealization> allCovered = new java.util.ArrayList<>(
				preparedRetained(preparation));
			if(covered != null)
				allCovered.add(covered);
			Assert.assertEquals(1, allCovered.size());
			Assert.assertSame(retained, allCovered.get(0));

			NativeContinuityProof uncoveredProof = proof("new-seed", "new-pool", true, bindings);
			CandidateEmissionRealization uncovered = publication(
				closure, uncoveredProof, owner, "new-lineage", preparation);
			Assert.assertNotNull(uncovered);
			List<CandidateEmissionRealization> mixed = new java.util.ArrayList<>(
				preparedRetained(preparation));
			mixed.add(uncovered);
			Assert.assertSame("prior exact object remains the ordered prefix", retained, mixed.get(0));
			Assert.assertSame(uncovered, mixed.get(1));
		}
		finally {
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void earlyCoverageMatchesEveryDirectPublicationLayoutVariant() throws Exception {
		CompiledHopKey owner = key("matrix-owner"), source = key("matrix-source");
		List<CandidateRealizationInputBinding> bindings = List.of(directBinding(source));
		DurableAnchorKey outputAnchor = anchor("matrix-output");
		for(boolean exact : List.of(false, true))
			for(boolean directInputsExact : List.of(false, true)) {
				PlacementIdentity.resetNormalizedSignatureCache();
				NativeContinuityProof retainedProof = proof("matrix-seed", "matrix-pool",
					exact, bindings);
				NativeContinuityProof candidateProof = proof("matrix-seed", "matrix-pool",
					exact, bindings);
				Assert.assertNull(proofString(candidateProof));
				PlacementRelationClosure reference = new PlacementRelationClosure(null, null,
					null, false, NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
				CandidateEmissionRealization retained = referencePublication(reference,
					retainedProof, owner, NATIVE_EMISSION, outputAnchor, "matrix-lineage",
					directInputsExact);
				Object preparation = prepare(new CandidateEmissionFact(
					NATIVE_EMISSION, FType.ROW, null, List.of(retained)));
				PlacementRelationClosure optimized = new PlacementRelationClosure(null, null,
					new SearchSpaceMetrics(), false,
					NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
				Assert.assertNull("exact retained output must cover its publication variant",
					publication(optimized, candidateProof, owner, NATIVE_EMISSION,
						outputAnchor, "matrix-lineage", directInputsExact, preparation));
				Assert.assertNull("coverage must avoid flattening each matrix candidate",
					proofString(candidateProof));
			}
		PlacementIdentity.resetNormalizedSignatureCache();
	}

	@Test
	public void collisionBucketScansPastRejectedEntryToExactTypedMatch() throws Exception {
		CompiledHopKey owner = key("collision-owner"), source = key("collision-source");
		List<CandidateRealizationInputBinding> bindings = List.of(directBinding(source));
		NativeContinuityProof collision = proof("Aa", "Aa", true, bindings);
		NativeContinuityProof match = proof("BB", "BB", true, bindings);
		Assert.assertEquals(collision.hashCode(), match.hashCode());
		CandidateEmissionRealization first = retained(owner, collision,
			"collision-lineage", bindings);
		CandidateEmissionRealization second = retained(owner, match,
			"collision-lineage", bindings);
		Object preparation = prepare(new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(first, second)));
		Assert.assertTrue(covers(preparation, match, owner, directOutput(second)));
	}

	@Test
	public void liveBinderAllCoveredPathKeepsExactEmissionWithoutAddingStaging() throws Exception {
		assertLiveBinderAllCoveredPath(1);
	}

	@Test
	public void liveBinderPublishesMultiMemberDurableProductsWithoutExpansion() throws Exception {
		assertLiveBinderAllCoveredPath(3);
	}

	private void assertLiveBinderAllCoveredPath(int sourceOptions) throws Exception {
		CompiledHopKey sourceOwner = (CompiledHopKey)seedFixture("key",
			new Class<?>[] {String.class}, "live-source");
		CompiledHopKey consumerOwner = (CompiledHopKey)seedFixture("key",
			new Class<?>[] {String.class}, "live-consumer");
		DurableAnchorKey pool = anchor("live-pool");
		CandidateRuleFact source = (CandidateRuleFact)seedFixture("nativeFact",
			new Class<?>[] {CompiledHopKey.class, String.class, DurableAnchorKey.class, int.class},
			sourceOwner, "live-source", pool, 1);
		source = withSourceOptions(source, "live-source", sourceOptions);
		CandidateRuleKey consumerRule = new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, "live-consumer", List.of(), List.of());
		CandidateRuleFact consumer = (CandidateRuleFact)seedFixture("fact",
			new Class<?>[] {CandidateRuleKey.class, CandidateEmissionRealization.class},
			consumerRule, staging);
		List<CandidateRuleFact> inventory = List.of(source, consumer);
		List<Object> nodes = List.of(
			seedFixture("node", new Class<?>[] {CompiledHopKey.class, List.class}, sourceOwner, List.of(pool)),
			seedFixture("node", new Class<?>[] {CompiledHopKey.class, List.class}, consumerOwner, List.of()));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(sourceOwner, consumerOwner, 0));
		DataOp sourceHop = new DataOp("live-source", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "live-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("live-consumer", DataType.MATRIX, ValueType.FP64,
			OpOp1.LOG, sourceHop);
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(sourceOwner, sourceHop);
		origins.put(consumerOwner, consumerHop);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(sourceHop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(consumerHop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, inventory, nodes, edges, inventory, origins, shapes);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,NeutralPlacementGraph.Node> nodesByKey = (Map<CompiledHopKey,
			NeutralPlacementGraph.Node>)nodes.stream().map(NeutralPlacementGraph.Node.class::cast)
			.collect(java.util.stream.Collectors.toMap(NeutralPlacementGraph.Node::key,
				node -> node, (left, right) -> right, IdentityHashMap::new));
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, inventory, edges, Map.of());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, metrics, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", index.getClass(),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Set<CompiledHopKey> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
		dirty.add(consumerOwner);
		CandidateRuleFact first = boundFacts(bind.invoke(closure, index, List.of(consumer),
			origins, shapes, continuity, dirty)).get(0);
		CandidateEmissionFact grounded = first.allowedEmissionFacts().get(0);
		CandidateEmissionRealization durableProduct = grounded.realizations().get(0);
		Assert.assertEquals(PlacementIdentity.PlacementLayoutKind.DURABLE_MAP,
			durableProduct.key().layoutKind());
		Assert.assertEquals("only multiple members benefit from a durable product wrapper",
			sourceOptions > 1, durableProduct.nativeContinuitySupportProduct().isPresent());
		Assert.assertEquals(sourceOptions, durableProduct.supportClauses().size());
		Assert.assertEquals("singletons keep their exact clause; products stay lazy",
			sourceOptions > 1 ? 0 : 1, durableProduct.fullyMaterializedSupportClauseCount());

		List<CandidateRuleFact> nextInventory = List.of(source, first);
		Object nextIndex = indexBuilder.invoke(null, nextInventory, nodes, edges,
			nextInventory, origins, shapes);
		NativePlacementContinuity nextContinuity = new NativePlacementContinuity(
			nodesByKey, origins, nextInventory, edges, Map.of());
		clearPublicationMemo(closure); // Force early retained coverage, not publication memo reuse.
		Object allCoveredResult = bind.invoke(closure, nextIndex, List.of(first),
			origins, shapes, nextContinuity, dirty);
		CandidateRuleFact second = boundFacts(allCoveredResult).get(0);
		Assert.assertSame("all-covered caller must retain the exact emission and add no staging",
			grounded, second.allowedEmissionFacts().get(0));
		Assert.assertTrue("fixture must prove that the live caller took early retained coverage",
			directMetric(metrics, "EARLY_NATIVE_COVERAGE_HITS") > 0);
		CandidateRuleFact coldFirst = withoutTypedNativeMarkers(first);
		List<CandidateRuleFact> coldInventory = List.of(source, coldFirst);
		Object coldIndex = indexBuilder.invoke(null, coldInventory, nodes, edges,
			coldInventory, origins, shapes);
		Object coldAllCoveredResult = bind.invoke(new PlacementRelationClosure(null, null,
			new SearchSpaceMetrics(), false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false), coldIndex,
			List.of(coldFirst), origins, shapes, new NativePlacementContinuity(
				nodesByKey, origins, coldInventory, edges, Map.of()), dirty);
		assertBindingResultParity(allCoveredResult, coldAllCoveredResult);

		if(sourceOptions > 1) {
			NativeContinuitySupportClauses retainedRelation =
				(NativeContinuitySupportClauses)durableProduct.supportClauses();
			CandidateRealizationSupportClause retainedMember =
				retainedRelation.get(retainedRelation.size() - 1);
			CandidateRuleFact expandedSource = withSourceOptions(
				source, "live-source", sourceOptions + 1);
			List<CandidateRuleFact> expandedInventory = List.of(expandedSource, second);
			Object expandedIndex = indexBuilder.invoke(null, expandedInventory, nodes, edges,
				expandedInventory, origins, shapes);
			clearPublicationMemo(closure);
			Object expandedResult = bind.invoke(closure, expandedIndex, List.of(second),
				origins, shapes, new NativePlacementContinuity(nodesByKey, origins,
					expandedInventory, edges, Map.of()), dirty);
			CandidateEmissionFact expandedEmission = boundFacts(expandedResult).get(0)
				.allowedEmissionFacts().get(0);
			Assert.assertEquals("same-output axis growth remains one realization", 1,
				expandedEmission.realizations().size());
			NativeContinuitySupportClauses expandedRelation =
				(NativeContinuitySupportClauses)expandedEmission.realizations().get(0).supportClauses();
			Assert.assertEquals(sourceOptions + 1, expandedRelation.size());
			Assert.assertEquals("binder must publish the union without creating scalar handles",
				0, expandedRelation.materializedHandleCount());
			int retainedOrdinal = expandedRelation.product().ordinalOfExactAuthorityBindings(
				retainedMember.inputBindings());
			Assert.assertTrue(retainedOrdinal >= 0);
			Assert.assertSame("selected retained member keeps first clause authority",
				retainedMember, expandedRelation.get(retainedOrdinal));
			CandidateRuleFact explicitSecond = withoutTypedNativeMarkers(second);
			List<CandidateRuleFact> explicitInventory = List.of(expandedSource, explicitSecond);
			Object explicitIndex = indexBuilder.invoke(null, explicitInventory, nodes, edges,
				explicitInventory, origins, shapes);
			Object explicitResult = bind.invoke(new PlacementRelationClosure(null, null,
				new SearchSpaceMetrics(), false,
				NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false), explicitIndex,
				List.of(explicitSecond), origins, shapes, new NativePlacementContinuity(
					nodesByKey, origins, explicitInventory, edges, Map.of()), dirty);
			assertBindingResultParity(expandedResult, explicitResult);
		}

		DurableAnchorKey addedPool = new DurableAnchorKey("zz-live-pool", FType.ROW,
			List.of(new AnchorPartition("worker-added", List.of(0L, 0L), List.of(4L, 2L))));
		CandidateRuleFact addedSource = (CandidateRuleFact)seedFixture("nativeFact",
			new Class<?>[] {CompiledHopKey.class, String.class, DurableAnchorKey.class, int.class},
			sourceOwner, "zz-live-source", addedPool, 1);
		addedSource = withSourceOptions(addedSource, "zz-live-source", sourceOptions);
		CandidateEmissionFact combinedSourceEmission = new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, java.util.stream.Stream.concat(
				source.allowedEmissionFacts().get(0).realizations().stream(),
				addedSource.allowedEmissionFacts().get(0).realizations().stream()).toList());
		CandidateRuleFact combinedSource = new CandidateRuleFact(source.key(), source.status(),
			source.capability(), source.shapeProof(), source.profile(),
			List.of(combinedSourceEmission), source.failureCode());
		List<CandidateRuleFact> mixedInventory = List.of(combinedSource, second);
		List<Object> mixedNodes = List.of(
			seedFixture("node", new Class<?>[] {CompiledHopKey.class, List.class},
				sourceOwner, List.of(pool, addedPool)),
			seedFixture("node", new Class<?>[] {CompiledHopKey.class, List.class},
				consumerOwner, List.of()));
		Object mixedIndex = indexBuilder.invoke(null, mixedInventory, mixedNodes, edges,
			mixedInventory, origins, shapes);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,NeutralPlacementGraph.Node> mixedNodesByKey =
			(Map<CompiledHopKey,NeutralPlacementGraph.Node>)mixedNodes.stream()
				.map(NeutralPlacementGraph.Node.class::cast)
				.collect(java.util.stream.Collectors.toMap(NeutralPlacementGraph.Node::key,
					node -> node, (left, right) -> right, IdentityHashMap::new));
		NativePlacementContinuity mixedContinuity = new NativePlacementContinuity(
			mixedNodesByKey, origins, mixedInventory, edges, Map.of());
		clearPublicationMemo(closure);
		Object mixedResult = bind.invoke(closure, mixedIndex, List.of(second),
			origins, shapes, mixedContinuity, dirty);
		CandidateRuleFact mixed = boundFacts(mixedResult).get(0);
		List<CandidateEmissionRealization> mixedRealizations =
			mixed.allowedEmissionFacts().get(0).realizations();
		Assert.assertTrue("mixed caller keeps prior authority and appends newly proved output",
			mixedRealizations.size() > grounded.realizations().size());
		Assert.assertTrue("disjoint seed outputs keep their appropriate exact representation",
			mixedRealizations.stream().allMatch(realization ->
				realization.nativeContinuitySupportProduct().isPresent() == (sourceOptions > 1)));
		if(sourceOptions > 1)
			Assert.assertTrue("the newly appended seed product remains unmaterialized",
				mixedRealizations.stream().anyMatch(realization ->
					realization.fullyMaterializedSupportClauseCount() == 0));
		Assert.assertEquals("disjoint seed outputs keep distinct realization authority",
			mixedRealizations.size(), mixedRealizations.stream()
				.map(CandidateEmissionRealization::key).distinct().count());
		for(CandidateEmissionRealization prior : grounded.realizations())
			Assert.assertTrue("mixed caller must retain each prior exact object",
				mixedRealizations.stream().anyMatch(candidate -> candidate == prior));
		CandidateRuleFact coldSecond = withoutTypedNativeMarkers(second);
		List<CandidateRuleFact> coldMixedInventory = List.of(combinedSource, coldSecond);
		Object coldMixedIndex = indexBuilder.invoke(null, coldMixedInventory, mixedNodes, edges,
			coldMixedInventory, origins, shapes);
		Object coldMixedResult = bind.invoke(new PlacementRelationClosure(null, null,
			new SearchSpaceMetrics(), false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false), coldMixedIndex,
			List.of(coldSecond), origins, shapes, new NativePlacementContinuity(
				mixedNodesByKey, origins, coldMixedInventory, edges, Map.of()), dirty);
		assertBindingResultParity(mixedResult, coldMixedResult);

		// Removing and restoring one source route changes the proof frontier, but the
		// binder intentionally retains prior grounded clauses until the support fixed point.
		// Compare that exact revision behavior with markers stripped, including read sets.
		List<CandidateRuleFact> withdrawnInventory = List.of(source, mixed);
		Object withdrawnIndex = indexBuilder.invoke(null, withdrawnInventory, nodes, edges,
			withdrawnInventory, origins, shapes);
		clearPublicationMemo(closure);
		Object withdrawnResult = bind.invoke(closure, withdrawnIndex, List.of(mixed),
			origins, shapes, new NativePlacementContinuity(nodesByKey, origins,
				withdrawnInventory, edges, Map.of()), dirty);
		CandidateRuleFact coldMixed = withoutTypedNativeMarkers(mixed);
		List<CandidateRuleFact> coldWithdrawnInventory = List.of(source, coldMixed);
		Object coldWithdrawnIndex = indexBuilder.invoke(null, coldWithdrawnInventory, nodes, edges,
			coldWithdrawnInventory, origins, shapes);
		Object coldWithdrawnResult = bind.invoke(new PlacementRelationClosure(null, null,
			new SearchSpaceMetrics(), false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false), coldWithdrawnIndex,
			List.of(coldMixed), origins, shapes, new NativePlacementContinuity(nodesByKey,
				origins, coldWithdrawnInventory, edges, Map.of()), dirty);
		assertBindingResultParity(withdrawnResult, coldWithdrawnResult);

		CandidateRuleFact withdrawn = boundFacts(withdrawnResult).get(0);
		List<CandidateRuleFact> prunedWithdrawal = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPoint(
				List.of(source, withdrawn), null, List.of(), Map.of());
		List<CandidateEmissionRealization> survivingProducts = prunedWithdrawal.stream()
			.filter(candidate -> candidate.key().parentOccurrence() == consumerOwner)
			.flatMap(candidate -> candidate.allowedEmissionFacts().stream())
			.flatMap(candidate -> candidate.realizations().stream())
			.filter(candidate -> candidate.key().layoutKind()
				== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP).toList();
		Assert.assertEquals("withdrawing one source removes only its disjoint seed output",
			1, survivingProducts.size());
		Assert.assertEquals(durableProduct.key(), survivingProducts.get(0).key());
		List<CandidateRuleFact> restoredInventory = List.of(combinedSource, withdrawn);
		Object restoredIndex = indexBuilder.invoke(null, restoredInventory, mixedNodes, edges,
			restoredInventory, origins, shapes);
		clearPublicationMemo(closure);
		Object restoredResult = bind.invoke(closure, restoredIndex, List.of(withdrawn),
			origins, shapes, new NativePlacementContinuity(mixedNodesByKey, origins,
				restoredInventory, edges, Map.of()), dirty);
		CandidateRuleFact coldWithdrawn = withoutTypedNativeMarkers(withdrawn);
		List<CandidateRuleFact> coldRestoredInventory = List.of(combinedSource, coldWithdrawn);
		Object coldRestoredIndex = indexBuilder.invoke(null, coldRestoredInventory, mixedNodes,
			edges, coldRestoredInventory, origins, shapes);
		Object coldRestoredResult = bind.invoke(new PlacementRelationClosure(null, null,
			new SearchSpaceMetrics(), false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false), coldRestoredIndex,
			List.of(coldWithdrawn), origins, shapes, new NativePlacementContinuity(
				mixedNodesByKey, origins, coldRestoredInventory, edges, Map.of()), dirty);
		assertBindingResultParity(restoredResult, coldRestoredResult);
	}


	private static CandidateRuleFact withSourceOptions(CandidateRuleFact source,
		String lineage, int options) {
		CandidateEmissionFact emission = source.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		for(int option = 0; option < options; option++)
			realizations.add(new CandidateEmissionRealization(
				PlacementIdentity.PlacementRealizationKey.nativeLineage(
					emission.emissionState(), lineage + "-option-" + option),
				emission.realizations().get(0).supportClauses()));
		return new CandidateRuleFact(source.key(), source.status(), source.capability(),
			source.shapeProof(), source.profile(), List.of(new CandidateEmissionFact(
				emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
				realizations)), source.failureCode());
	}

	@Test
	public void exactProofWithUnknownOutputAdmitsMixedSourceExactness() throws Exception {
		MixedProductFixture fixture = mixedProductFixture();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		CandidateEmissionRealization publication = productPublication(closure,
			fixture.exactProduct(), fixture.owner(), null, "mixed-unknown-output", fixture.sources());
		assertLazyProductMatchesScalarReference(publication, fixture.exactProduct(), fixture.owner(),
			null, "mixed-unknown-output", fixture.exactBinding(), fixture.dynamicBinding());
	}

	@Test
	public void dynamicProofWithKnownOutputAdmitsMixedSourceExactness() throws Exception {
		MixedProductFixture fixture = mixedProductFixture();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		CandidateEmissionRealization publication = productPublication(closure,
			fixture.dynamicProduct(), fixture.owner(), fixture.knownOutput(),
			"mixed-dynamic-known-output", fixture.sources());
		assertLazyProductMatchesScalarReference(publication, fixture.dynamicProduct(), fixture.owner(),
			fixture.knownOutput(), "mixed-dynamic-known-output",
			fixture.exactBinding(), fixture.dynamicBinding());
	}

	@Test
	public void exactProofWithKnownOutputPartitionsMixedSourceExactness() throws Exception {
		MixedProductFixture fixture = mixedProductFixture();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		List<CandidateEmissionRealization> parts = invokeProductPublication(closure,
			fixture.exactProduct(), fixture.owner(), fixture.knownOutput(),
			"mixed-exact-known-output", fixture.sources(), null);
		Assert.assertEquals(2, parts.size());
		Assert.assertEquals(PlacementIdentity.PlacementLayoutKind.DURABLE_MAP, parts.get(0).key().layoutKind());
		Assert.assertEquals(PlacementIdentity.PlacementLayoutKind.NATIVE_LINEAGE, parts.get(1).key().layoutKind());
		for(CandidateEmissionRealization part : parts) {
			NativeContinuitySupportClauses clauses = (NativeContinuitySupportClauses)part.supportClauses();
			Assert.assertTrue(clauses.product().exactPartitionRanges());
			Assert.assertTrue(clauses.clauseLayoutExact());
			Assert.assertEquals(1, clauses.size());
			Assert.assertEquals(0, clauses.materializedHandleCount());
		}

		NativePlacementContinuity.NativeSupportProduct allExact =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(fixture.seed(), fixture.outputPool(),
				true, List.of(List.of(fixture.exactBinding())));
		NativePlacementContinuity.NativeSupportProduct allInexact =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(fixture.seed(), fixture.outputPool(),
				true, List.of(List.of(fixture.dynamicBinding())));
		Assert.assertNotNull("uniform exact publication must remain admitted",
			productPublication(closure, allExact, fixture.owner(), fixture.knownOutput(),
				"all-exact", fixture.sources()));
		Assert.assertNotNull("uniform inexact publication must remain admitted",
			productPublication(closure, allInexact, fixture.owner(), fixture.knownOutput(),
				"all-inexact", fixture.sources()));
	}

	@Test
	public void exactDurableMixedSingleAxisHasSpecificTraceOutcome() throws Exception {
		CompiledHopKey owner = key("single-mixed-owner");
		MixedAxis mixed = mixedAxis(key("single-mixed-source"), "single-mixed", 0);
		MixedAxis uniform = mixedAxis(key("single-uniform-source"), "single-uniform", 1);
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("single-mixed-seed"), anchor("single-mixed-output"), true,
				List.of(mixed.bindings(), List.of(uniform.exactBinding())));
		Assert.assertNotNull(product);
		Assert.assertEquals(2, product.axes().size());
		Assert.assertEquals(2, product.size());
		Assert.assertNotSame(mixed.owner(), uniform.owner());
		assertMixedAxis(product.axes().get(0), 0,
			mixed.exactBinding(), mixed.dynamicBinding());
		Assert.assertEquals(List.of(uniform.exactBinding()), product.axes().get(1));
		Object sources = directSources(List.of(mixed.fact(), uniform.fact()));
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		TracedPublication traced = productPublicationWithTrace(closure, product, owner,
			anchor("single-known-output"), "single-mixed-axis", sources);
		Assert.assertEquals(2, traced.publication().size());
		// Only binder admission records PARTITIONED; two singleton parts still use
		// the original scalar query and its mixed-source fallback classification.
		Assert.assertEquals("MIXED_EXACTNESS_SINGLE_AXIS", traced.outcome());
	}

	@Test
	public void exactDurableMixedMultipleAxesHasSpecificTraceOutcome() throws Exception {
		CompiledHopKey owner = key("multiple-mixed-owner");
		MixedAxis first = mixedAxis(key("multiple-source-a"), "axis-a", 0);
		MixedAxis second = mixedAxis(key("multiple-source-b"), "axis-b", 1);
		DurableAnchorKey seed = anchor("multiple-mixed-seed");
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed,
				anchor("multiple-mixed-output"), true,
				List.of(first.bindings(), second.bindings()));
		Assert.assertNotNull(product);
		Assert.assertEquals(4, product.size());
		Assert.assertNotSame(first.owner(), second.owner());
		assertMixedAxis(product.axes().get(0), 0,
			first.exactBinding(), first.dynamicBinding());
		assertMixedAxis(product.axes().get(1), 1,
			second.exactBinding(), second.dynamicBinding());
		Object sources = directSources(List.of(first.fact(), second.fact()));
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		TracedPublication traced = productPublicationWithTrace(closure, product, owner,
			anchor("multiple-known-output"), "multiple-mixed-axes", sources);
		Assert.assertNotNull(traced.publication());
		Assert.assertEquals(2, traced.publication().size());
		Assert.assertEquals(4, traced.publication().stream()
			.mapToInt(realization -> realization.supportClauses().size()).sum());
		Assert.assertEquals("PARTITIONED", traced.outcome());
	}

	@Test
	public void collidingDurableSeedOutputsKeepTheExactUnionWithoutMemberExpansion() throws Exception {
		CompiledHopKey sourceOwner = (CompiledHopKey)seedFixture("key",
			new Class<?>[] {String.class}, "collision-source");
		CompiledHopKey consumerOwner = (CompiledHopKey)seedFixture("key",
			new Class<?>[] {String.class}, "collision-consumer");
		DurableAnchorKey firstPool = new DurableAnchorKey("collision-seed-a", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 1L))));
		DurableAnchorKey secondPool = new DurableAnchorKey("collision-seed-b", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
		CandidateRuleFact firstSource = (CandidateRuleFact)seedFixture("nativeFact",
			new Class<?>[] {CompiledHopKey.class, String.class, DurableAnchorKey.class, int.class},
			sourceOwner, "collision-source-a", firstPool, 1);
		CandidateRuleFact secondSource = (CandidateRuleFact)seedFixture("nativeFact",
			new Class<?>[] {CompiledHopKey.class, String.class, DurableAnchorKey.class, int.class},
			sourceOwner, "collision-source-b", secondPool, 1);
		CandidateEmissionFact sourceEmission = new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(
				firstSource.allowedEmissionFacts().get(0).realizations().get(0),
				secondSource.allowedEmissionFacts().get(0).realizations().get(0)));
		CandidateRuleFact source = new CandidateRuleFact(firstSource.key(), firstSource.status(),
			firstSource.capability(), firstSource.shapeProof(), firstSource.profile(),
			List.of(sourceEmission), firstSource.failureCode());
		CandidateRuleKey consumerRule = new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, "collision-consumer", List.of(), List.of());
		CandidateRuleFact consumer = (CandidateRuleFact)seedFixture("fact",
			new Class<?>[] {CandidateRuleKey.class, CandidateEmissionRealization.class},
			consumerRule, staging);
		List<CandidateRuleFact> inventory = List.of(source, consumer);
		List<Object> nodes = List.of(
			seedFixture("node", new Class<?>[] {CompiledHopKey.class, List.class},
				sourceOwner, List.of()),
			seedFixture("node", new Class<?>[] {CompiledHopKey.class, List.class},
				consumerOwner, List.of()));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(sourceOwner, consumerOwner, 0));
		DataOp sourceHop = new DataOp("collision-source", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "collision-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("collision-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(sourceOwner, sourceHop);
		origins.put(consumerOwner, consumerHop);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(sourceHop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(consumerHop, new NodeShapeFact(DataType.MATRIX, 8, 2));
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, inventory, nodes, edges,
			inventory, origins, shapes);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,NeutralPlacementGraph.Node> nodesByKey =
			(Map<CompiledHopKey,NeutralPlacementGraph.Node>)nodes.stream()
				.map(NeutralPlacementGraph.Node.class::cast)
				.collect(java.util.stream.Collectors.toMap(NeutralPlacementGraph.Node::key,
					node -> node, (left, right) -> right, IdentityHashMap::new));
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, inventory, edges, Map.of());
		DurableAnchorKey collisionOutput = new DurableAnchorKey(
			"native-output:" + consumerOwner.normalizedSignature(), FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
		CandidateEmissionFact baseEmission = consumer.allowedEmissionFacts().get(0);
		CandidateRealizationReference queryOutput = new CandidateRealizationReference(
			consumer.key(), PlacementIdentity.PlacementRealizationKey.durable(
				baseEmission.emissionState(), collisionOutput));
		for(DurableAnchorKey seed : List.of(firstPool, secondPool)) {
			NativePlacementContinuity.CandidateSupportResult query =
				continuity.proveGeneratedCandidateSupport(
					consumer, baseEmission, queryOutput, seed);
			Assert.assertNotNull("colliding seed query must retain its factored product",
				query.supportProduct());
			Assert.assertEquals(2, query.supportProduct().size());
		}
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(null, null, metrics, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", index.getClass(),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Set<CompiledHopKey> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
		dirty.add(consumerOwner);
		CandidateRuleFact actualFact = boundFacts(bind.invoke(closure, index, List.of(consumer),
			origins, shapes, continuity, dirty)).get(0);
		CandidateEmissionFact actual = actualFact.allowedEmissionFacts().get(0);
		Assert.assertTrue("common-axis colliding headers retain their exact native relation",
			actual.realizations().stream().allMatch(realization ->
				realization.nativeContinuitySupportProduct().isPresent()));
		Assert.assertEquals(1, actual.realizations().size());
		CandidateEmissionRealization actualRealization = actual.realizations().get(0);
		Assert.assertEquals(PlacementIdentity.PlacementLayoutKind.DURABLE_MAP,
			actualRealization.key().layoutKind());
		Assert.assertEquals(4, actualRealization.supportClauses().size());
		Assert.assertEquals(2, actualRealization.nativeContinuitySupportProduct().orElseThrow().headerCount());
		Assert.assertEquals(0, actualRealization.fullyMaterializedSupportClauseCount());
		Assert.assertEquals("compressed publication must not visit exact proof members", 0,
			directMetric(metrics, "PROOFS_CONSUMED"));
		SearchSpaceMetrics.NativePublicationCount collision = metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome() == SearchSpaceMetrics.NativePublicationOutcome.PUBLISHED)
			.findFirst().orElseThrow();
		Assert.assertEquals(2, collision.queries());
		Assert.assertEquals(4, collision.logicalProofs());
		Assert.assertEquals(0, collision.consumedProofs());

		CandidateRealizationReference output = new CandidateRealizationReference(
			consumer.key(), actualRealization.key());
		PlacementRelationClosure referenceClosure = new PlacementRelationClosure(null, null,
			new SearchSpaceMetrics(), false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		List<CandidateEmissionRealization> explicit = new java.util.ArrayList<>();
		for(DurableAnchorKey seed : List.of(firstPool, secondPool))
			for(NativeContinuityProof proof : continuity.proveGeneratedCandidateAlternatives(
				consumer, baseEmission, output, seed))
				explicit.add(referencePublication(referenceClosure, proof, consumerOwner,
					baseEmission.emissionState(), actualRealization.anchor(),
					"explicit-collision-reference", true));
		CandidateEmissionFact reference = new CandidateEmissionFact(baseEmission.emissionState(),
			baseEmission.executionFType(), baseEmission.derivedFoutAction(), explicit);
		Assert.assertEquals("compressed collision union preserves canonical proof/source authority",
			reference.realizations(), actual.realizations());
	}

	private static CandidateEmissionRealization retained(CompiledHopKey owner,
		NativeContinuityProof proof, String lineage,
		List<CandidateRealizationInputBinding> bindings) throws Exception {
		return CandidateEmissionRealization.nativeLineage(NATIVE_EMISSION, lineage,
			proof.outputWorkerPoolWitness(), List.of(nativeProofKey(owner, proof)), bindings);
	}

	private static PlacementProofKey nativeProofKey(CompiledHopKey owner,
		NativeContinuityProof proof) throws Exception {
		Method method = PlacementProofKey.class.getDeclaredMethod(
			"fromNativeContinuity", CompiledHopKey.class, NativeContinuityProof.class);
		method.setAccessible(true);
		return (PlacementProofKey)method.invoke(null, owner, proof);
	}

	private static Object nativeDescriptor(PlacementProofKey key) throws Exception {
		Field field = PlacementProofKey.class.getDeclaredField("nativeContinuityDescriptor");
		field.setAccessible(true);
		return field.get(key);
	}

	private static Object component(Object record, String name) throws Exception {
		Method method = record.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(record);
	}

	private static Object prepare(CandidateEmissionFact emission) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"prepareGroundedNativeSupport", CandidateEmissionFact.class);
		method.setAccessible(true);
		return method.invoke(null, emission);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> preparedRetained(Object preparation)
		throws Exception {
		Method method = preparation.getClass().getDeclaredMethod("retained");
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(preparation);
	}

	private static boolean covers(Object preparation, NativeContinuityProof proof,
		CompiledHopKey owner, Object output) throws Exception {
		Method method = preparation.getClass().getDeclaredMethod("coversRetainedNative",
			NativeContinuityProof.class, CompiledHopKey.class, output.getClass());
		method.setAccessible(true);
		return (boolean)method.invoke(preparation, proof, owner, output);
	}

	private static Object directOutput(CandidateEmissionRealization realization) throws Exception {
		PlacementAnalysis.CandidateRealizationSupportClause clause =
			realization.requireSingletonSupportClause();
		return directOutput(realization.key(), clause.nativeWorkerPoolWitness(),
			clause.nativeWorkerPoolLayoutExact());
	}

	private static Object directOutput(PlacementRealizationKey key,
		DurableAnchorKey pool, boolean exact) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectNativeOutput");
		Constructor<?> constructor = type.getDeclaredConstructor(
			PlacementRealizationKey.class, DurableAnchorKey.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(key, pool, exact);
	}

	private static CandidateEmissionRealization publication(PlacementRelationClosure closure,
		NativeContinuityProof proof, CompiledHopKey owner, String lineage, Object preparation)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class, preparation.getClass());
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			NATIVE_EMISSION, null, lineage, true, preparation);
	}

	private static CandidateEmissionRealization publication(PlacementRelationClosure closure,
		NativeContinuityProof proof, CompiledHopKey owner, PlacementEmissionState emission,
		DurableAnchorKey outputAnchor, String lineage, boolean directInputsExact,
		Object preparation) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class, preparation.getClass());
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			emission, outputAnchor, lineage, directInputsExact, preparation);
	}

	private static CandidateEmissionRealization referencePublication(PlacementRelationClosure closure,
		NativeContinuityProof proof, CompiledHopKey owner, String lineage) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			NATIVE_EMISSION, null, lineage, true);
	}

	private static CandidateEmissionRealization referencePublication(PlacementRelationClosure closure,
		NativeContinuityProof proof, CompiledHopKey owner, PlacementEmissionState emission,
		DurableAnchorKey outputAnchor, String lineage, boolean directInputsExact) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			emission, outputAnchor, lineage, directInputsExact);
	}

	private static CandidateRuleFact sourceFact(CompiledHopKey owner,
		CandidateEmissionRealization realization) throws Exception {
		return (CandidateRuleFact)seedFixture("fact",
			new Class<?>[] {CandidateRuleKey.class, CandidateEmissionRealization.class},
			new CandidateRuleKey(owner, List.of()), realization);
	}

	private static MixedProductFixture mixedProductFixture() throws Exception {
		CompiledHopKey owner = key("mixed-product-owner");
		CompiledHopKey sourceOwner = key("mixed-source");
		DurableAnchorKey seed = anchor("mixed-seed");
		DurableAnchorKey outputPool = anchor("mixed-proof-output");
		DurableAnchorKey knownOutput = anchor("mixed-known-output");
		CandidateEmissionRealization exactSource = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, "mixed-exact", seed, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, sourceOwner, "exact-source")), List.of());
		CandidateEmissionRealization dynamicSource =
			CandidateEmissionRealization.nativeLineageDynamicLayout(
				NATIVE_EMISSION, "mixed-dynamic", seed, List.of(new PlacementProofKey(
					PlacementProofKind.NATIVE_CONTINUITY, sourceOwner, "dynamic-source")), List.of());
		CandidateRuleFact source = sourceFact(sourceOwner, exactSource);
		CandidateEmissionFact emission = source.allowedEmissionFacts().get(0);
		source = new CandidateRuleFact(source.key(), source.status(), source.capability(),
			source.shapeProof(), source.profile(), List.of(new CandidateEmissionFact(
				emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
				List.of(exactSource, dynamicSource))), source.failureCode());
		CandidateRealizationInputBinding exactBinding = CandidateRealizationInputBinding.direct(0,
			CandidateRealizationReference.of(source.key(), exactSource));
		CandidateRealizationInputBinding dynamicBinding = CandidateRealizationInputBinding.direct(0,
			CandidateRealizationReference.of(source.key(), dynamicSource));
		Assert.assertTrue(exactSource.allOwnedSupportClausesHaveExactNativeLayout());
		Assert.assertFalse(dynamicSource.allOwnedSupportClausesHaveExactNativeLayout());
		NativePlacementContinuity.NativeSupportProduct exactProduct =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, outputPool, true,
				List.of(List.of(exactBinding, dynamicBinding)));
		NativePlacementContinuity.NativeSupportProduct dynamicProduct =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, outputPool, false,
				List.of(List.of(exactBinding, dynamicBinding)));
		Assert.assertNotNull(exactProduct);
		Assert.assertNotNull(dynamicProduct);
		Assert.assertEquals(2, exactProduct.size());
		Assert.assertEquals(2, dynamicProduct.size());
		return new MixedProductFixture(owner, seed, outputPool, knownOutput,
			exactBinding, dynamicBinding, exactProduct, dynamicProduct,
			directSources(List.of(source)));
	}

	private record MixedProductFixture(CompiledHopKey owner, DurableAnchorKey seed,
		DurableAnchorKey outputPool, DurableAnchorKey knownOutput,
		CandidateRealizationInputBinding exactBinding,
		CandidateRealizationInputBinding dynamicBinding,
		NativePlacementContinuity.NativeSupportProduct exactProduct,
		NativePlacementContinuity.NativeSupportProduct dynamicProduct, Object sources) { }

	private static MixedAxis mixedAxis(CompiledHopKey owner, String lineage, int position)
		throws Exception {
		DurableAnchorKey pool = anchor(lineage + "-pool");
		CandidateEmissionRealization exact = CandidateEmissionRealization.nativeLineage(
			NATIVE_EMISSION, lineage + "-exact", pool, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, owner, lineage + "-exact-proof")), List.of());
		CandidateEmissionRealization dynamic =
			CandidateEmissionRealization.nativeLineageDynamicLayout(
				NATIVE_EMISSION, lineage + "-dynamic", pool, List.of(new PlacementProofKey(
					PlacementProofKind.NATIVE_CONTINUITY, owner,
					lineage + "-dynamic-proof")), List.of());
		CandidateRuleFact fact = sourceFact(owner, exact);
		CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
		fact = new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
			fact.profile(), List.of(new CandidateEmissionFact(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), List.of(exact, dynamic))),
			fact.failureCode());
		CandidateRealizationInputBinding exactBinding = CandidateRealizationInputBinding.direct(
			position, CandidateRealizationReference.of(fact.key(), exact));
		CandidateRealizationInputBinding dynamicBinding = CandidateRealizationInputBinding.direct(
			position, CandidateRealizationReference.of(fact.key(), dynamic));
		Assert.assertTrue(exact.allOwnedSupportClausesHaveExactNativeLayout());
		Assert.assertFalse(dynamic.allOwnedSupportClausesHaveExactNativeLayout());
		return new MixedAxis(owner, fact, exactBinding, dynamicBinding,
			List.of(exactBinding, dynamicBinding));
	}

	private static void assertMixedAxis(List<CandidateRealizationInputBinding> axis,
		int position, CandidateRealizationInputBinding exact,
		CandidateRealizationInputBinding dynamic) {
		Assert.assertEquals(2, axis.size());
		Assert.assertTrue(axis.stream().allMatch(binding -> binding.inputPosition() == position));
		Assert.assertEquals(Set.of(exact.source().realization(), dynamic.source().realization()),
			axis.stream().map(binding -> binding.source().realization())
				.collect(java.util.stream.Collectors.toSet()));
	}

	private record MixedAxis(CompiledHopKey owner, CandidateRuleFact fact,
		CandidateRealizationInputBinding exactBinding,
		CandidateRealizationInputBinding dynamicBinding,
		List<CandidateRealizationInputBinding> bindings) { }

	private static Object directSources(List<CandidateRuleFact> sources) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectSourceIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(sources);
	}

	private static CandidateEmissionRealization productPublication(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey outputAnchor, String lineage, Object sources)
		throws Exception {
		List<CandidateEmissionRealization> result = invokeProductPublication(closure, product,
			owner, outputAnchor, lineage, sources, null);
		if(result == null)
			return null;
		Assert.assertEquals("uniform helper expects one product", 1, result.size());
		return result.get(0);
	}

	private static TracedPublication productPublicationWithTrace(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey outputAnchor, String lineage, Object sources)
		throws Exception {
		Class<?> traceType = Class.forName(
			PlacementRelationClosure.class.getName() + "$NativePublicationTrace");
		Constructor<?> constructor = traceType.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object trace = constructor.newInstance();
		List<CandidateEmissionRealization> publication = invokeProductPublication(
			closure, product, owner, outputAnchor, lineage, sources, trace);
		Field outcome = traceType.getDeclaredField("outcome");
		outcome.setAccessible(true);
		return new TracedPublication(publication, ((Enum<?>)outcome.get(trace)).name());
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> invokeProductPublication(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey outputAnchor, String lineage, Object sources,
		Object trace) throws Exception {
		Class<?> traceType = Class.forName(
			PlacementRelationClosure.class.getName() + "$NativePublicationTrace");
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativeProductPublication", product.getClass(), CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, List.class,
			sources.getClass(), Map.class, traceType);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(closure, product, owner,
			NATIVE_EMISSION, outputAnchor, lineage, List.of(), sources,
			new IdentityHashMap<CandidateEmissionRealization,Boolean>(), trace);
	}

	private record TracedPublication(List<CandidateEmissionRealization> publication, String outcome) { }

	private static void assertLazyProductMatchesScalarReference(
		CandidateEmissionRealization publication,
		NativePlacementContinuity.NativeSupportProduct product, CompiledHopKey owner,
		DurableAnchorKey outputAnchor, String lineage,
		CandidateRealizationInputBinding exactBinding,
		CandidateRealizationInputBinding dynamicBinding) throws Exception {
		Assert.assertNotNull(publication);
		Assert.assertTrue(publication.supportClauses() instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses lazy =
			(NativeContinuitySupportClauses)publication.supportClauses();
		Assert.assertEquals("admission must not materialize a product member", 0,
			lazy.materializedHandleCount());
		PlacementRelationClosure referenceClosure = new PlacementRelationClosure(
			null, null, null, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
		List<CandidateEmissionRealization> explicit = new ArrayList<>();
		for(CandidateRealizationInputBinding binding : List.of(exactBinding, dynamicBinding)) {
			NativeContinuityProof proof = new NativeContinuityProof(product.externalSeed(),
				product.outputWorkerPoolWitness(), product.exactPartitionRanges(), List.of(binding));
			explicit.add(referencePublication(referenceClosure, proof, owner, NATIVE_EMISSION,
				outputAnchor, lineage, binding == exactBinding));
		}
		CandidateEmissionFact expected = new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, explicit);
		CandidateEmissionFact actual = new CandidateEmissionFact(
			NATIVE_EMISSION, FType.ROW, null, List.of(publication));
		Assert.assertEquals("factored publication must preserve ordered proof/source/owner metadata",
			expected.realizations(), actual.realizations());
		Assert.assertEquals(2, lazy.materializedHandleCount());
	}

	private static Object seedFixture(String name, Class<?>[] parameters, Object... arguments)
		throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method.invoke(null, arguments);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> boundFacts(Object result) throws Exception {
		Method method = result.getClass().getDeclaredMethod("facts");
		method.setAccessible(true);
		return (List<CandidateRuleFact>)method.invoke(result);
	}

	private static long directMetric(SearchSpaceMetrics metrics, String name) {
		return metrics.directBindingSnapshot().entrySet().stream()
			.filter(entry -> entry.getKey().name().equals(name))
			.mapToLong(Map.Entry::getValue).findFirst().orElse(0);
	}

	private static CandidateRuleFact withoutTypedNativeMarkers(CandidateRuleFact fact) {
		List<CandidateEmissionFact> emissions = fact.allowedEmissionFacts().stream().map(emission ->
			new CandidateEmissionFact(emission.emissionState(), emission.executionFType(),
				emission.derivedFoutAction(), emission.realizations().stream().map(realization -> {
					List<PlacementAnalysis.CandidateRealizationSupportClause> clauses =
						realization.supportClauses().stream().map(clause ->
							new PlacementAnalysis.CandidateRealizationSupportClause(
								clause.proofDependencies().stream().map(proof ->
									proof.kind() == PlacementProofKind.NATIVE_CONTINUITY
										? new PlacementProofKey(proof.kind(), proof.owner(),
											proof.authoritySignature()) : proof).toList(),
								clause.inputBindings(), clause.nativeWorkerPoolWitness(),
								clause.nativeWorkerPoolLayoutExact())).toList();
					return CandidateEmissionRealization.fromAlreadyCanonicalSupportClauses(
						realization.key(), clauses);
				}).toList())).toList();
		return new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
			fact.profile(), emissions, fact.failureCode());
	}

	private static void assertBindingResultParity(Object optimized, Object cold) throws Exception {
		Assert.assertEquals("typed coverage must preserve ordered emitted facts",
			bindingResultComponent(cold, "facts"), bindingResultComponent(optimized, "facts"));
		assertIdentityDependencyMapEquals(
			bindingResultComponent(cold, "dependencyOccurrences"),
			bindingResultComponent(optimized, "dependencyOccurrences"));
		assertIdentitySetEquals(
			bindingResultComponent(cold, "incompleteDependencyOccurrences"),
			bindingResultComponent(optimized, "incompleteDependencyOccurrences"));
	}

	@SuppressWarnings("unchecked")
	private static void assertIdentityDependencyMapEquals(Object expectedObject,
		Object actualObject) {
		Map<CompiledHopKey,Set<CompiledHopKey>> expected =
			(Map<CompiledHopKey,Set<CompiledHopKey>>)expectedObject;
		Map<CompiledHopKey,Set<CompiledHopKey>> actual =
			(Map<CompiledHopKey,Set<CompiledHopKey>>)actualObject;
		Assert.assertEquals(expected.size(), actual.size());
		for(Map.Entry<CompiledHopKey,Set<CompiledHopKey>> entry : expected.entrySet()) {
			Set<CompiledHopKey> actualDependencies = actual.entrySet().stream()
				.filter(candidate -> candidate.getKey() == entry.getKey())
				.map(Map.Entry::getValue).findFirst().orElse(null);
			Assert.assertNotNull("dependency owner identity must be preserved", actualDependencies);
			assertIdentitySetEquals(entry.getValue(), actualDependencies);
		}
	}

	@SuppressWarnings("unchecked")
	private static void assertIdentitySetEquals(Object expectedObject, Object actualObject) {
		Set<CompiledHopKey> expected = (Set<CompiledHopKey>)expectedObject;
		Set<CompiledHopKey> actual = (Set<CompiledHopKey>)actualObject;
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey key : expected)
			Assert.assertTrue("dependency identity must be preserved",
				actual.stream().anyMatch(candidate -> candidate == key));
	}

	private static Object bindingResultComponent(Object result, String name) throws Exception {
		Method method = result.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(result);
	}

	private static void clearPublicationMemo(PlacementRelationClosure closure) throws Exception {
		Field field = PlacementRelationClosure.class.getDeclaredField("directNativePublicationMemo");
		field.setAccessible(true);
		((Map<?,?>)field.get(closure)).clear();
	}

	private static NativeContinuityProof proof(String seed, String pool, boolean exact,
		List<CandidateRealizationInputBinding> bindings) {
		return new NativeContinuityProof(anchor(seed), anchor(pool), exact, bindings);
	}

	private static String proofString(NativeContinuityProof proof) throws Exception {
		Field field = NativeContinuityProof.class.getDeclaredField("normalizedSignature");
		field.setAccessible(true);
		return (String)field.get(proof);
	}

	private static CandidateRealizationInputBinding directBinding(CompiledHopKey source) {
		CandidateRuleKey rule = new CandidateRuleKey(source,
			List.of(CandidateInputState.present(FType.ROW)));
		return CandidateRealizationInputBinding.direct(0,
			new CandidateRealizationReference(rule, PlacementRealizationKey.local(LOCAL_EMISSION)));
	}

	private static CandidateRealizationInputBinding relocationBinding(
		CompiledHopKey source, CompiledHopKey consumer) {
		CandidateRuleKey rule = new CandidateRuleKey(source,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateRealizationReference reference = new CandidateRealizationReference(
			rule, PlacementRealizationKey.local(LOCAL_EMISSION));
		ValueVersionKey version = new ValueVersionKey("early-native", "source", REGION,
			0, VersionKind.ORDINARY, List.of());
		RelocationActionKey action = new RelocationActionKey(version,
			NATIVE_EMISSION.placementState(), FType.ROW, anchor("relocation"),
			REGION.normalizedSignature(), List.of(consumer));
		return CandidateRealizationInputBinding.relocation(0, reference, action);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("early-native", "main", "call", "compiled", REGION, id, id);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
	}
}
