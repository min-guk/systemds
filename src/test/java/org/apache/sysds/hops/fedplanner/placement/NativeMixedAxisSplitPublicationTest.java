/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
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
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;

public class NativeMixedAxisSplitPublicationTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"native-mixed-split", "main", List.of("root"), "call", "compiled");
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void oneMixedAxisSplitsIntoExactDisjointProductsWithScalarAuthorityParity()
		throws Exception {
		CompiledHopKey consumer = key("consumer");
		Axis mixed = axis(key("mixed-source"), 0, "mixed", 2, 2);
		Axis uniform = axis(key("uniform-source"), 1, "uniform", 2, 0);
		DurableAnchorKey seed = anchor("seed");
		DurableAnchorKey proofOutput = anchor("proof-output");
		DurableAnchorKey durableOutput = anchor("durable-output");
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			mixed.bindings(), uniform.exactBindings());
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, proofOutput, true, axes);
		Assert.assertNotNull(product);
		Assert.assertEquals(8, product.size());

		PlacementRelationClosure closure = closure();
		List<CandidateEmissionRealization> publications = pluralPublication(closure, product,
			consumer, durableOutput, "mixed-split", directSources(List.of(mixed.fact(), uniform.fact())));
		Assert.assertNotNull(publications);
		Assert.assertEquals(2, publications.size());
		Assert.assertEquals(Set.of(PlacementLayoutKind.DURABLE_MAP, PlacementLayoutKind.NATIVE_LINEAGE),
			publications.stream().map(value -> value.key().layoutKind())
				.collect(java.util.stream.Collectors.toSet()));
		Assert.assertEquals(product.size(), publications.stream()
			.mapToInt(value -> value.supportClauses().size()).sum());
		for(CandidateEmissionRealization publication : publications) {
			Assert.assertTrue(publication.supportClauses() instanceof NativeContinuitySupportClauses);
			Assert.assertEquals(0, publication.fullyMaterializedSupportClauseCount());
		}

		List<CandidateEmissionRealization> explicit = new ArrayList<>();
		for(int ordinal = 0; ordinal < product.size(); ordinal++) {
			List<CandidateRealizationInputBinding> bindings = product.bindingsAt(ordinal);
			boolean exact = mixed.exactBindings().stream().anyMatch(
				binding -> binding == bindings.get(0));
			NativeContinuityProof proof = new NativeContinuityProof(seed, proofOutput, true, bindings);
			explicit.add(scalarPublication(closure, proof, consumer, durableOutput,
				"mixed-split", exact));
		}
		CandidateEmissionFact expected = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, explicit);
		CandidateEmissionFact actual = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, publications);
		Assert.assertEquals(expected.realizations(), actual.realizations());
		for(CandidateEmissionRealization realization : actual.realizations())
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				Assert.assertSame(consumer, clause.proofDependencies().get(0).owner());
				for(CandidateRealizationInputBinding binding : clause.inputBindings())
					Assert.assertTrue(binding.source().rule().parentOccurrence() == mixed.owner()
						|| binding.source().rule().parentOccurrence() == uniform.owner());
			}
	}

	@Test
	public void twoMixedAxesUseExactRectangleComplementAndSingletonDescriptorsRemainDisjoint() throws Exception {
		CompiledHopKey consumer = key("fallback-consumer");
		Axis first = axis(key("fallback-first"), 0, "first", 1, 1);
		Axis second = axis(key("fallback-second"), 1, "second", 1, 1);
		NativePlacementContinuity.NativeSupportProduct twoMixed =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("fallback-seed"), anchor("fallback-proof"), true,
				List.of(first.bindings(), second.bindings()));
		Assert.assertNotNull(twoMixed);
		List<CandidateEmissionRealization> multiple = pluralPublication(closure(), twoMixed, consumer,
			anchor("fallback-output"), "two-mixed",
			directSources(List.of(first.fact(), second.fact())));
		Assert.assertNotNull(multiple);
		Assert.assertEquals(2, multiple.size());
		Assert.assertEquals(4, multiple.stream().mapToInt(value -> value.supportClauses().size()).sum());
		CandidateEmissionRealization nativePart = multiple.stream().filter(value ->
			value.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE).findFirst().orElseThrow();
		Assert.assertTrue(nativePart.supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertTrue(((NativeContinuitySupportClauses)nativePart.supportClauses())
			.conditionalComplement());
		Assert.assertEquals(0, nativePart.fullyMaterializedSupportClauseCount());
		Assert.assertTrue(multiple.stream().allMatch(value ->
			value.supportClauses() instanceof NativeContinuitySupportClauses
				&& value.fullyMaterializedSupportClauseCount() == 0));

		List<CandidateEmissionRealization> scalar = new ArrayList<>();
		for(int ordinal = 0; ordinal < twoMixed.size(); ordinal++) {
			List<CandidateRealizationInputBinding> bindings = twoMixed.bindingsAt(ordinal);
			boolean exact = first.exactBindings().stream().anyMatch(value -> value == bindings.get(0))
				&& second.exactBindings().stream().anyMatch(value -> value == bindings.get(1));
			scalar.add(scalarPublication(closure(), new NativeContinuityProof(
				twoMixed.externalSeed(), twoMixed.outputWorkerPoolWitness(),
				twoMixed.exactPartitionRanges(), bindings), consumer,
				anchor("fallback-output"), "two-mixed", exact));
		}
		CandidateEmissionFact expected = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, scalar);
		CandidateEmissionFact actual = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, multiple);
		Assert.assertEquals("two disjoint lazy regions must equal every ordered scalar authority",
			expected.realizations(), actual.realizations());
		for(CandidateEmissionRealization realization : actual.realizations())
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				Assert.assertSame(consumer, clause.proofDependencies().get(0).owner());
				Assert.assertTrue(clause.inputBindings().stream().anyMatch(binding ->
					first.bindings().stream().anyMatch(option -> option == binding)));
				Assert.assertTrue(clause.inputBindings().stream().anyMatch(binding ->
					second.bindings().stream().anyMatch(option -> option == binding)));
			}

		Axis singletonMixed = axis(key("singleton-source"), 0, "singleton", 1, 1);
		NativePlacementContinuity.NativeSupportProduct singletonRegions =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("singleton-seed"), anchor("singleton-proof"), true,
				List.of(singletonMixed.bindings()));
		Assert.assertNotNull(singletonRegions);
		List<CandidateEmissionRealization> descriptors = pluralPublication(closure(),
			singletonRegions, consumer, anchor("singleton-output"), "singleton-regions",
			directSources(List.of(singletonMixed.fact())));
		Assert.assertEquals(2, descriptors.size());
		Assert.assertTrue(descriptors.stream().allMatch(value -> value.supportClauses().size() == 1));
		Assert.assertNotEquals(descriptors.get(0).key(), descriptors.get(1).key());
		// Actual publication rejects both singleton descriptors; that complete
		// scalar fallback is exercised by NativeMixedExactnessPartitionTest.
	}

	@Test
	public void actualTwoMixedAxisBinderMatchesScalarAuthorityAcrossReplayAndWithdrawal()
		throws Exception {
		CompiledHopKey leftOwner = fixtureKey("two-mixed-left");
		CompiledHopKey rightOwner = fixtureKey("two-mixed-right");
		CompiledHopKey consumerOwner = fixtureKey("two-mixed-consumer");
		DurableAnchorKey pool = anchor("two-mixed-pool");
		CandidateEmissionRealization leftExact = sourceRealization(
			leftOwner, pool, "two-left-exact", true);
		CandidateRuleFact left = fact(new CandidateRuleKey(leftOwner, List.of()), List.of(
			leftExact, sourceRealization(leftOwner, pool, "two-left-other-exact", true),
			valueMapSource("two-left-map",
				new CandidateRuleKey(leftOwner, List.of()), leftExact)));
		CandidateEmissionRealization rightExact = sourceRealization(
			rightOwner, pool, "two-right-exact", true);
		CandidateEmissionRealization rightMap = valueMapSource("two-right-map",
			new CandidateRuleKey(rightOwner, List.of()), rightExact);
		CandidateRuleFact right = fact(new CandidateRuleKey(rightOwner, List.of()),
			List.of(rightExact, rightMap));
		CandidateEmissionRealization stagingRealization = CandidateEmissionRealization.nativeLineage(
			EMISSION, "two-mixed-staging", List.of(), List.of());
		CandidateRuleFact staging = fact(new CandidateRuleKey(consumerOwner, List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW))),
			List.of(stagingRealization));
		DataOp leftHop = new DataOp("two-mixed-left", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "two-mixed-left", 8, 2, 16, 1000);
		DataOp rightHop = new DataOp("two-mixed-right", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "two-mixed-right", 8, 2, 16, 1000);
		BinaryOp consumerHop = new BinaryOp("two-mixed-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp2.PLUS, leftHop, rightHop);

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		CandidateRuleFact first = bindBinary(closure(metrics), left, right, staging,
			leftHop, rightHop, consumerHop, pool, 6);
		List<CandidateEmissionRealization> firstProducts = products(first);
		Assert.assertEquals(2, firstProducts.size());
		Assert.assertEquals(6, firstProducts.stream()
			.mapToInt(value -> value.supportClauses().size()).sum());
		Assert.assertTrue(firstProducts.stream().allMatch(value ->
			value.supportClauses() instanceof NativeContinuitySupportClauses
				&& value.fullyMaterializedSupportClauseCount() == 0));
		SearchSpaceMetrics.NativePublicationCount partitioned = metrics.nativePublicationSnapshot()
			.stream().filter(value -> value.outcome()
				== SearchSpaceMetrics.NativePublicationOutcome.PARTITIONED)
			.findFirst().orElseThrow();
		Assert.assertEquals(1, partitioned.queries());
		Assert.assertEquals(6, partitioned.logicalProofs());
		Assert.assertEquals(0, partitioned.consumedProofs());

		CandidateRuleFact metricsOff = bindBinary(closure(), left, right, staging,
			leftHop, rightHop, consumerHop, pool, 6);
		Assert.assertEquals(exactMemberSignatures(metricsOff), exactMemberSignatures(first));
		CandidateRuleFact replay = bindBinary(closure(metrics), left, right, first,
			leftHop, rightHop, consumerHop, pool, 6);
		for(CandidateEmissionRealization retained : firstProducts)
			Assert.assertTrue("unchanged conditional regions retain exact realization authority",
				products(replay).stream().anyMatch(candidate -> candidate == retained));

		CandidateRuleFact reducedRight = fact(new CandidateRuleKey(rightOwner, List.of()),
			List.of(rightExact));
		CandidateRuleFact rebound = bindBinary(closure(), left, reducedRight, replay,
			leftHop, rightHop, consumerHop, pool, 3);
		CandidateRuleFact pruned = PlacementSupportRelations.pruneUnsupportedRealizations(
			List.of(left, reducedRight, rebound)).stream().filter(value ->
				value.key().parentOccurrence() == consumerOwner).findFirst().orElseThrow();
		CandidateRuleFact cold = bindBinary(closure(), left, reducedRight, staging,
			leftHop, rightHop, consumerHop, pool, 3);
		Assert.assertEquals("partial withdrawal must equal a cold scalar-authority rebuild",
			expandedSignatures(cold), expandedSignatures(pruned));
		Assert.assertFalse(expandedSignatures(pruned).stream().anyMatch(signature ->
			signature.contains("two-right-map")));
	}

	@Test
	public void structurallyEqualForeignSourceOwnerCannotEnterSplitAuthority() throws Exception {
		CompiledHopKey consumer = key("identity-consumer");
		Axis canonical = axis(key("identity-source"), 0, "identity", 2, 2);
		CompiledHopKey foreignOwner = key("identity-source");
		Assert.assertEquals(canonical.owner(), foreignOwner);
		Assert.assertNotSame(canonical.owner(), foreignOwner);
		List<CandidateRealizationInputBinding> foreignBindings = canonical.bindings().stream()
			.map(binding -> CandidateRealizationInputBinding.direct(binding.inputPosition(),
				new CandidateRealizationReference(new CandidateRuleKey(foreignOwner,
					binding.source().rule().orderedInputs()), binding.source().realization())))
			.toList();
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				anchor("identity-seed"), anchor("identity-proof"), true,
				List.of(foreignBindings));
		Assert.assertNotNull(product);
		Assert.assertNull("source executability is owner-identity scoped",
			pluralPublication(closure(), product, consumer, anchor("identity-output"),
				"identity", directSources(List.of(canonical.fact()))));
	}

	@Test
	public void actualBinderRetainsEachRegionAndWithdrawalMatchesColdAuthority() throws Exception {
		CompiledHopKey sourceOwner = fixtureKey("binder-source");
		CompiledHopKey consumerOwner = fixtureKey("binder-consumer");
		DurableAnchorKey pool = anchor("binder-pool");
		CandidateRuleKey sourceKey = new CandidateRuleKey(sourceOwner, List.of());
		CandidateEmissionRealization exact0 = sourceRealization(
			sourceOwner, pool, "binder-e0", true);
		CandidateEmissionRealization exact1 = sourceRealization(
			sourceOwner, pool, "binder-e1", true);
		CandidateEmissionRealization mapped0 = valueMapSource(
			"binder-d0", sourceKey, exact0);
		CandidateEmissionRealization mapped1 = valueMapSource(
			"binder-d1", sourceKey, exact1);
		List<CandidateEmissionRealization> variants = List.of(exact0, exact1, mapped0, mapped1);
		CandidateRuleFact source = fact(sourceKey, variants);
		CandidateRuleFact staging = consumerFact(consumerOwner);
		DataOp sourceHop = new DataOp("binder-source", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "binder-source", 8, 2, 16, 1000);
		UnaryOp consumerHop = new UnaryOp("binder-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = closure(metrics);

		CandidateRuleFact first = bind(closure, source, staging, sourceHop, consumerHop, 4);
		List<CandidateEmissionRealization> firstProducts = products(first);
		Assert.assertEquals(2, firstProducts.size());
		Assert.assertEquals(Set.of(PlacementLayoutKind.DURABLE_MAP, PlacementLayoutKind.NATIVE_LINEAGE),
			firstProducts.stream().map(value -> value.key().layoutKind())
				.collect(java.util.stream.Collectors.toSet()));
		Assert.assertEquals(4, firstProducts.stream().mapToInt(value -> value.supportClauses().size()).sum());
		Assert.assertTrue(firstProducts.stream().allMatch(value ->
			value.supportClauses() instanceof NativeContinuitySupportClauses
				&& value.fullyMaterializedSupportClauseCount() == 0));
		List<CandidateEmissionRealization> explicitMembers = firstProducts.stream()
			.map(value -> new CandidateEmissionRealization(value.key(), List.copyOf(value.supportClauses())))
			.toList();
		Assert.assertEquals("the two lazy regions preserve every explicit proof/source member",
			new CandidateEmissionFact(EMISSION, FType.ROW, null, explicitMembers).realizations(),
			new CandidateEmissionFact(EMISSION, FType.ROW, null, firstProducts).realizations());
		Assert.assertEquals(Set.copyOf(variants.stream().map(CandidateEmissionRealization::key).toList()),
			firstProducts.stream().flatMap(value -> value.supportClauses().stream())
				.map(clause -> clause.inputBindings().get(0).source().realization())
				.collect(java.util.stream.Collectors.toSet()));
		SearchSpaceMetrics.NativePublicationCount split = metrics.nativePublicationSnapshot().stream()
			.filter(value -> value.outcome() == SearchSpaceMetrics.NativePublicationOutcome.PARTITIONED)
			.findFirst().orElseThrow();
		Assert.assertEquals(1, split.queries());
		Assert.assertEquals(4, split.logicalProofs());
		Assert.assertEquals(0, split.consumedProofs());
		CandidateRuleFact metricsOff = bind(
			closure(), source, staging, sourceHop, consumerHop, 4);
		Assert.assertEquals("metrics must not change exact realization, proof, or source authority",
			exactMemberSignatures(first), exactMemberSignatures(metricsOff));

		CandidateRuleFact second = bind(closure, source, first, sourceHop, consumerHop, 4);
		for(CandidateEmissionRealization retained : firstProducts)
			Assert.assertTrue("unchanged durable and native regions retain exact authority objects",
				products(second).stream().anyMatch(candidate -> candidate == retained));

		CandidateEmissionRealization durable = firstProducts.stream().filter(value ->
			value.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP).findFirst().orElseThrow();
		CandidateEmissionRealization nativeRegion = firstProducts.stream().filter(value ->
			value.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE).findFirst().orElseThrow();
		CandidateEmissionRealization stagingRealization = staging.allowedEmissionFacts().get(0)
			.realizations().get(0);
		for(CandidateEmissionRealization retained : List.of(durable, nativeRegion)) {
			CandidateRuleFact retainedOne = fact(staging.key(), List.of(retained, stagingRealization));
			CandidateRuleFact rebound = bind(closure(), source, retainedOne, sourceHop, consumerHop, 4);
			Assert.assertTrue("covered region retains the original object",
				products(rebound).stream().anyMatch(candidate -> candidate == retained));
			Assert.assertEquals("the disjoint region remains relation-native", 2, products(rebound).size());
			Assert.assertTrue(products(rebound).stream().allMatch(value ->
				value.supportClauses() instanceof NativeContinuitySupportClauses));
		}

		List<CandidateEmissionRealization> reducedVariants = variants.subList(0, 3);
		CandidateRuleFact reducedSource = fact(sourceKey, reducedVariants);
		CandidateRuleFact rebound = bind(closure, reducedSource, second, sourceHop, consumerHop, 3);
		CandidateRuleFact pruned = PlacementSupportRelations.pruneUnsupportedRealizations(
			List.of(reducedSource, rebound)).stream().filter(value ->
				value.key().parentOccurrence() == consumerOwner).findFirst().orElseThrow();
		CandidateRuleFact cold = bind(closure(), reducedSource, staging, sourceHop, consumerHop, 3);
		Assert.assertEquals("withdrawal after retained split authority equals a cold exact rebuild",
			expandedSignatures(cold), expandedSignatures(pruned));
		Assert.assertFalse(expandedSignatures(pruned).stream().anyMatch(signature ->
			signature.contains("binder-d1")));
	}

	@Test
	public void retainedNativeRectangleConsumesOnlyTheExactResidual() throws Exception {
		CompiledHopKey leftOwner = fixtureKey("residual-left");
		CompiledHopKey rightOwner = fixtureKey("residual-right");
		CompiledHopKey consumerOwner = fixtureKey("residual-consumer");
		BinderAxis left = binderAxis(leftOwner, "residual-left", 2, 1);
		BinderAxis right = binderAxis(rightOwner, "residual-right", 2, 0);
		CandidateRuleFact staging = binaryConsumerFact(consumerOwner);
		BinaryOp consumerHop = new BinaryOp("residual-consumer", DataType.MATRIX,
			ValueType.FP64, OpOp2.PLUS, left.sourceHop(), right.sourceHop());

		CandidateRuleFact cold = bindBinary(closure(), left, right, staging, consumerHop);
		CandidateEmissionRealization durable = cold.allowedEmissionFacts().get(0).realizations()
			.stream().filter(value -> value.key().layoutKind() == PlacementLayoutKind.DURABLE_MAP)
			.findFirst().orElseThrow();
		CandidateEmissionRealization nativePart = cold.allowedEmissionFacts().get(0).realizations()
			.stream().filter(value -> value.key().layoutKind() == PlacementLayoutKind.NATIVE_LINEAGE)
			.findFirst().orElseThrow(() -> new AssertionError(cold.allowedEmissionFacts().get(0)
				.realizations().stream().map(value -> value.key().layoutKind()).toList()));
		NativeContinuitySupportClauses durableRelation =
			(NativeContinuitySupportClauses)durable.supportClauses();
		List<CandidateRealizationInputBinding> retainedBindings = List.of(
			durableRelation.commonAxes().get(0).get(0),
			durableRelation.commonAxes().get(1).get(0));
		NativeContinuitySupportClauses retainedRelation = durableRelation.restrictBindings(
			retainedBindings::contains).orElseThrow();
		Assert.assertEquals(1, retainedRelation.size());
		CandidateRealizationSupportClause retainedClause = retainedRelation.get(0);
		CandidateEmissionRealization partialDurable = new CandidateEmissionRealization(
			durable.key(), retainedRelation);
		CandidateRuleFact retained = fact(staging.key(), List.of(partialDurable, nativePart));

		SearchSpaceMetrics metrics = new SearchSpaceMetrics().enableDuplicateMergeDiagnostics(0);
		CandidateRuleFact rebound = bindBinary(closure(metrics), left, right, retained, consumerHop);
		Assert.assertEquals("residual publication must preserve every cold source/proof member",
			exactMemberSignatures(cold), exactMemberSignatures(rebound));
		CandidateEmissionRealization reboundDurable = rebound.allowedEmissionFacts().get(0)
			.realizations().stream().filter(value -> value.key().equals(durable.key()))
			.findFirst().orElseThrow();
		Assert.assertTrue("the retained exact clause remains the first donor authority",
			reboundDurable.supportClauses().stream().anyMatch(clause -> clause == retainedClause));
		for(CandidateRealizationSupportClause clause : reboundDurable.supportClauses()) {
			Assert.assertSame(consumerOwner, clause.proofDependencies().get(0).owner());
			Assert.assertTrue(clause.inputBindings().stream().anyMatch(binding ->
				binding.source().rule().parentOccurrence() == leftOwner));
			Assert.assertTrue(clause.inputBindings().stream().anyMatch(binding ->
				binding.source().rule().parentOccurrence() == rightOwner));
		}
		SearchSpaceMetrics.NativePublicationCount partitioned = metrics.nativePublicationSnapshot()
			.stream().filter(value -> value.outcome()
				== SearchSpaceMetrics.NativePublicationOutcome.PARTITIONED)
			.findFirst().orElseThrow();
		Assert.assertEquals(6, partitioned.logicalProofs());
		Assert.assertEquals("four exact tuples minus the retained 1x1 rectangle; retained="
			+ metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.PARTITIONED_RETAINED_PROOFS)
				+ ":collision=" + metrics.directWorkCount(
					SearchSpaceMetrics.DirectWork.PARTITIONED_COLLISION_PROOFS)
				+ ":singleton=" + metrics.directWorkCount(
					SearchSpaceMetrics.DirectWork.PARTITIONED_SINGLETON_PROOFS), 3,
			partitioned.consumedProofs());
		Assert.assertEquals(3L, metrics.directWorkCount(
			SearchSpaceMetrics.DirectWork.PARTITIONED_RETAINED_PROOFS));
	}

	@Test
	public void exactAuthorityResidualMatchesCanonicalExplicitSubsequenceAcrossSeededDomains()
		throws Exception {
		java.util.Random random = new java.util.Random(731_991L);
		for(int trial = 0; trial < 24; trial++) {
			int dimensions = 1 + random.nextInt(4);
			List<List<CandidateRealizationInputBinding>> axes = new ArrayList<>();
			for(int axis = 0; axis < dimensions; axis++) {
				int width = 2 + random.nextInt(3);
				axes.add(axis(key("residual-seeded-owner-" + trial + '-' + axis), axis,
					"residual-seeded-" + trial + '-' + axis, width, 0).bindings());
			}
			DurableAnchorKey seed = anchor("residual-seeded-seed-" + trial);
			DurableAnchorKey witness = anchor("residual-seeded-witness-" + trial);
			NativePlacementContinuity.NativeSupportProduct product =
				NativePlacementContinuity.NativeSupportProduct.tryCreate(seed, witness, true, axes);
			Assert.assertNotNull(product);
			List<List<CandidateRealizationInputBinding>> coveredAxes = new ArrayList<>();
			for(int axis = 0; axis < axes.size(); axis++) {
				int retained = axis == 0 ? axes.get(axis).size() - 1
					: 1 + random.nextInt(axes.get(axis).size());
				coveredAxes.add(List.copyOf(axes.get(axis).subList(0, retained)));
			}
			NativePlacementContinuity.NativeSupportProduct covered =
				NativePlacementContinuity.NativeSupportProduct.tryCreate(
					seed, witness, true, coveredAxes);
			Assert.assertNotNull(covered);
			CompiledHopKey owner = key("residual-seeded-consumer-" + trial);
			NativeContinuitySupportClauses full = new NativeContinuitySupportClauses(
				owner, product, witness, true);
			NativeContinuitySupportClauses prior = new NativeContinuitySupportClauses(
				owner, covered, witness, true);
			NativeContinuitySupportClauses.ExactAuthorityResidual residual =
				full.exactAuthorityResidualAfter(prior);
			Assert.assertNotNull(residual);
			Assert.assertEquals(covered.size(), residual.coveredMembers());
			NativeContinuitySupportClauses actual = new NativeContinuitySupportClauses(
				owner, residual.product(), witness, true);
			List<CandidateRealizationSupportClause> expected = new ArrayList<>();
			for(int ordinal = 0; ordinal < full.size(); ordinal++) {
				CandidateRealizationSupportClause clause = full.get(ordinal);
				boolean excluded = true;
				for(int axis = 0; axis < clause.inputBindings().size(); axis++) {
					CandidateRealizationInputBinding binding = clause.inputBindings().get(axis);
					excluded &= coveredAxes.get(axis).stream().anyMatch(option -> option == binding);
				}
				if(!excluded)
					expected.add(clause);
			}
			Assert.assertEquals(expected.stream()
				.map(CandidateRealizationSupportClause::normalizedSignature).toList(),
				actual.stream().map(CandidateRealizationSupportClause::normalizedSignature).toList());
			for(int ordinal = 0; ordinal < actual.size(); ordinal++) {
				Assert.assertSame(owner, actual.get(ordinal).proofDependencies().get(0).owner());
				for(int axis = 0; axis < dimensions; axis++)
					Assert.assertSame(axes.get(axis).get(0).source().rule().parentOccurrence(),
						actual.get(ordinal).inputBindings().get(axis).source().rule().parentOccurrence());
			}
		}
	}

	@Test
	public void residualMatchesRebuiltBindingsButRejectsForeignOwnerAuthority() throws Exception {
		CompiledHopKey sourceOwner = key("residual-authority-source");
		Axis source = axis(sourceOwner, 0, "residual-authority", 2, 0);
		DurableAnchorKey seed = anchor("residual-authority-seed");
		DurableAnchorKey witness = anchor("residual-authority-witness");
		NativePlacementContinuity.NativeSupportProduct candidate =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, witness, true, List.of(source.bindings()));
		CompiledHopKey consumer = key("residual-authority-consumer");
		NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
			consumer, candidate, witness, true);
		CandidateRealizationInputBinding original = source.bindings().get(0);
		CandidateRealizationInputBinding rebuilt = CandidateRealizationInputBinding.direct(
			original.inputPosition(), new CandidateRealizationReference(
				original.source().rule(), original.source().realization()));
		NativePlacementContinuity.NativeSupportProduct rebuiltProduct =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, witness, true, List.of(List.of(rebuilt)));
		Assert.assertNotNull(relation.exactAuthorityResidualAfter(
			new NativeContinuitySupportClauses(consumer, rebuiltProduct, witness, true)));

		CompiledHopKey foreignOwner = key("residual-authority-source");
		Assert.assertEquals(sourceOwner, foreignOwner);
		Assert.assertNotSame(sourceOwner, foreignOwner);
		CandidateRealizationInputBinding foreign = CandidateRealizationInputBinding.direct(
			original.inputPosition(), new CandidateRealizationReference(
				new CandidateRuleKey(foreignOwner, original.source().rule().orderedInputs()),
				original.source().realization()));
		NativePlacementContinuity.NativeSupportProduct foreignProduct =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				seed, witness, true, List.of(List.of(foreign)));
		Assert.assertNull("structural equality never borrows source-owner authority",
			relation.exactAuthorityResidualAfter(
				new NativeContinuitySupportClauses(consumer, foreignProduct, witness, true)));
	}

	private static Axis axis(CompiledHopKey owner, int position, String prefix,
		int exactCount, int dynamicCount) throws Exception {
		DurableAnchorKey pool = anchor(prefix + "-pool");
		List<CandidateEmissionRealization> realizations = new ArrayList<>();
		List<CandidateRealizationInputBinding> exact = new ArrayList<>();
		List<CandidateRealizationInputBinding> dynamic = new ArrayList<>();
		for(int index = 0; index < exactCount + dynamicCount; index++) {
			boolean isExact = index < exactCount;
			String lineage = prefix + '-' + index;
			PlacementProofKey proof = new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, owner, lineage + "-proof");
			CandidateEmissionRealization realization = isExact
				? CandidateEmissionRealization.nativeLineage(
					EMISSION, lineage, pool, List.of(proof), List.of())
				: CandidateEmissionRealization.nativeLineageDynamicLayout(
					EMISSION, lineage, pool, List.of(proof), List.of());
			realizations.add(realization);
		}
		CandidateRuleFact fact = sourceFact(owner, realizations);
		for(int index = 0; index < realizations.size(); index++) {
			CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(
				position, CandidateRealizationReference.of(fact.key(), realizations.get(index)));
			(isExact(realizations.get(index)) ? exact : dynamic).add(binding);
		}
		List<CandidateRealizationInputBinding> all = new ArrayList<>(exact);
		all.addAll(dynamic);
		all.sort(PlacementAnalysis.canonicalComparator());
		return new Axis(owner, fact, List.copyOf(exact), List.copyOf(dynamic), List.copyOf(all));
	}

	private static boolean isExact(CandidateEmissionRealization realization) {
		return realization.allOwnedSupportClausesHaveExactNativeLayout();
	}

	private static CandidateRuleFact sourceFact(CompiledHopKey owner,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null,
			new CandidateRuleKey(owner, List.of()), realizations);
	}

	private static CandidateRuleFact consumerFact(CompiledHopKey owner) throws Exception {
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			EMISSION, "binder-staging", List.of(), List.of());
		return fact(new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW))), List.of(staging));
	}

	private static CandidateRuleFact binaryConsumerFact(CompiledHopKey owner) throws Exception {
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			EMISSION, "binary-binder-staging", List.of(), List.of());
		return fact(new CandidateRuleKey(owner, List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW))),
			List.of(staging));
	}

	private static BinderAxis binderAxis(CompiledHopKey owner, String id,
		int exactCount, int valueCount) throws Exception {
		CompiledHopKey leafOwner = fixtureKey(id + "-leaf");
		DurableAnchorKey pool = anchor(id + "-pool");
		CandidateEmissionRealization leafRealization = sourceRealization(
			leafOwner, pool, id + "-leaf", true);
		CandidateRuleFact leaf = fact(new CandidateRuleKey(leafOwner, List.of()),
			List.of(leafRealization));
		List<CandidateRealizationInputBinding> leafBinding = List.of(
			CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(leaf.key(), leafRealization)));
		List<CandidateEmissionRealization> alternatives = new ArrayList<>();
		for(int index = 0; index < exactCount; index++)
			alternatives.add(CandidateEmissionRealization.nativeLineage(EMISSION,
				id + "-exact-" + index, pool, List.of(new PlacementProofKey(
					PlacementProofKind.NATIVE_CONTINUITY, owner, id + "-exact-" + index)),
				leafBinding));
		for(int index = 0; index < valueCount; index++)
			alternatives.add(CandidateEmissionRealization.valueMap(EMISSION,
				id + "-value-" + index,
				List.of(new CandidateRealizationSupportClause(List.of(), leafBinding))));
		CandidateRuleFact source = fact(new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW))), alternatives);
		DataOp leafHop = dataHop(id + "-leaf");
		UnaryOp sourceHop = new UnaryOp(id, DataType.MATRIX, ValueType.FP64,
			OpOp1.LOG, leafHop);
		return new BinderAxis(leafOwner, owner, leaf, source, leafHop, sourceHop);
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, key, realizations);
	}

	private static CandidateEmissionRealization sourceRealization(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage, boolean exact) {
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, lineage + "-proof");
		return exact
			? CandidateEmissionRealization.nativeLineage(EMISSION, lineage, pool, List.of(proof), List.of())
			: CandidateEmissionRealization.nativeLineageDynamicLayout(
				EMISSION, lineage, pool, List.of(proof), List.of());
	}

	private static CandidateEmissionRealization valueMapSource(String relation,
		CandidateRuleKey rule, CandidateEmissionRealization exactSource) {
		return CandidateEmissionRealization.valueMap(EMISSION, relation,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.logicalTransient(0,
					CandidateRealizationReference.of(rule, exactSource))))));
	}

	private static CandidateRuleFact bind(PlacementRelationClosure closure,
		CandidateRuleFact source, CandidateRuleFact consumer, DataOp sourceHop,
		UnaryOp consumerHop, int width) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("bind",
			PlacementRelationClosure.class, CandidateRuleFact.class, CandidateRuleFact.class,
			DataOp.class, UnaryOp.class, Map.class, PlacementAnalysis.NodeShapeFact.class, Map.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, closure, source, consumer, sourceHop,
			consumerHop, Map.of(anchor("binder-pool"), width),
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2), null);
	}

	private static CandidateRuleFact bindBinary(PlacementRelationClosure closure,
		BinderAxis left, BinderAxis right, CandidateRuleFact consumer,
		BinaryOp consumerHop) throws Exception {
		List<CandidateRuleFact> inventory = List.of(
			left.leaf(), left.source(), right.leaf(), right.source(), consumer);
		List<Node> nodes = List.of(fixtureNode(left.leafOwner()), fixtureNode(left.owner()),
			fixtureNode(right.leafOwner()), fixtureNode(right.owner()),
			fixtureNode(consumer.key().parentOccurrence()));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(left.leafOwner(), left.owner(), 0),
			new CompiledInputEdgeFact(right.leafOwner(), right.owner(), 0),
			new CompiledInputEdgeFact(left.owner(),
				consumer.key().parentOccurrence(), 0),
			new CompiledInputEdgeFact(right.owner(),
				consumer.key().parentOccurrence(), 1));
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(left.leafOwner(), left.leafHop());
		origins.put(left.owner(), left.sourceHop());
		origins.put(right.leafOwner(), right.leafHop());
		origins.put(right.owner(), right.sourceHop());
		origins.put(consumer.key().parentOccurrence(), consumerHop);
		Map<Hop,PlacementAnalysis.NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(left.leafHop(), new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(left.sourceHop(), new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(right.leafHop(), new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(right.sourceHop(), new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(consumerHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, inventory, edges, Map.of());
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, inventory, nodes, edges, inventory, origins, shapes);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", index.getClass(),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Set<CompiledHopKey> dirty = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		dirty.add(consumer.key().parentOccurrence());
		Object result = bind.invoke(closure, index, List.of(consumer), origins, shapes,
			continuity, dirty);
		Method facts = result.getClass().getDeclaredMethod("facts");
		facts.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> rebound = (List<CandidateRuleFact>)facts.invoke(result);
		return rebound.get(0);
	}

	private static CandidateRuleFact bindBinary(PlacementRelationClosure closure,
		CandidateRuleFact left, CandidateRuleFact right, CandidateRuleFact consumer,
		DataOp leftHop, DataOp rightHop, BinaryOp consumerHop,
		DurableAnchorKey pool, int expectedWidth) throws Exception {
		List<CandidateRuleFact> inventory = List.of(left, right, consumer);
		List<Node> nodes = List.of(fixtureNode(left.key().parentOccurrence()),
			fixtureNode(right.key().parentOccurrence()), fixtureNode(consumer.key().parentOccurrence()));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(left.key().parentOccurrence(),
				consumer.key().parentOccurrence(), 0),
			new CompiledInputEdgeFact(right.key().parentOccurrence(),
				consumer.key().parentOccurrence(), 1));
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(left.key().parentOccurrence(), leftHop);
		origins.put(right.key().parentOccurrence(), rightHop);
		origins.put(consumer.key().parentOccurrence(), consumerHop);
		Map<Hop,PlacementAnalysis.NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(leftHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(rightHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(consumerHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, inventory, edges, Map.of());
		CandidateEmissionFact emission = consumer.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = new CandidateRealizationReference(
			consumer.key(), PlacementIdentity.PlacementRealizationKey.nativeLineage(
				emission.emissionState(), "two-mixed-query"));
		NativePlacementContinuity.CandidateSupportResult query =
			continuity.proveGeneratedCandidateSupport(consumer, emission, proposed, pool);
		Assert.assertNotNull(query.supportProduct());
		Assert.assertEquals(expectedWidth, query.supportProduct().size());

		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, inventory, nodes, edges, inventory, origins, shapes);
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
		Set<CompiledHopKey> owners = footprint.get(consumer.key().parentOccurrence());
		Assert.assertTrue(owners.stream().anyMatch(owner -> owner == left.key().parentOccurrence()));
		Assert.assertTrue(owners.stream().anyMatch(owner -> owner == right.key().parentOccurrence()));
		return rebound.get(0);
	}

	private static Node fixtureNode(CompiledHopKey key) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod(
			"node", CompiledHopKey.class, List.class);
		method.setAccessible(true);
		return (Node)method.invoke(null, key, List.of());
	}

	private static DataOp dataHop(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, 8, 2, 16, 1000);
	}

	private static List<CandidateEmissionRealization> products(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(value -> value.realizations().stream())
			.filter(value -> value.supportClauses() instanceof NativeContinuitySupportClauses).toList();
	}

	private static List<String> expandedSignatures(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(value -> value.realizations().stream())
			.flatMap(value -> value.supportClauses().stream())
			.map(CandidateRealizationSupportClause::normalizedSignature).sorted().toList();
	}

	private static List<String> exactMemberSignatures(CandidateRuleFact fact) {
		return fact.allowedEmissionFacts().stream().flatMap(value -> value.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream().map(clause ->
				realization.key().normalizedSignature() + "|" + clause.normalizedSignature()))
			.sorted().toList();
	}

	private static Object directSources(List<CandidateRuleFact> facts) throws Exception {
		Class<?> type = Class.forName(
			PlacementRelationClosure.class.getName() + "$DirectSourceIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> pluralPublication(
		PlacementRelationClosure closure,
		NativePlacementContinuity.NativeSupportProduct product, CompiledHopKey owner,
		DurableAnchorKey output, String lineage, Object sources) throws Exception {
		Class<?> trace = Class.forName(
			PlacementRelationClosure.class.getName() + "$NativePublicationTrace");
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativeProductPublication", product.getClass(), CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, List.class,
			sources.getClass(), Map.class, trace);
		method.setAccessible(true);
		return (List<CandidateEmissionRealization>)method.invoke(closure, product, owner,
			EMISSION, output, lineage, List.of(), sources,
			new IdentityHashMap<CandidateEmissionRealization,Boolean>(), null);
	}

	private static CandidateEmissionRealization scalarPublication(
		PlacementRelationClosure closure, NativeContinuityProof proof, CompiledHopKey owner,
		DurableAnchorKey output, String lineage, boolean exact) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativeContinuityProof.class, CompiledHopKey.class, PlacementEmissionState.class,
			DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			EMISSION, output, lineage, exact);
	}

	private static PlacementRelationClosure closure() {
		return closure(null);
	}

	private static PlacementRelationClosure closure(SearchSpaceMetrics metrics) {
		return new PlacementRelationClosure(null, null, metrics, false,
			NeutralPlacementGraphBuilder.PrivacyEvidenceMode.NONE, false);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("native-mixed-split", "main", "call",
			"compiled", REGION, id, id);
	}

	private static CompiledHopKey fixtureKey(String id) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod("fixtureKey", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, id);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private record Axis(CompiledHopKey owner, CandidateRuleFact fact,
		List<CandidateRealizationInputBinding> exactBindings,
		List<CandidateRealizationInputBinding> dynamicBindings,
		List<CandidateRealizationInputBinding> bindings) { }
	private record BinderAxis(CompiledHopKey leafOwner, CompiledHopKey owner,
		CandidateRuleFact leaf, CandidateRuleFact source, DataOp leafHop, UnaryOp sourceHop) { }
}
