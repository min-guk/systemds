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
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder.PrivacyEvidenceMode;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeMixedExactnessPartitionTest {
	private static final PlacementEmissionState ROW_EMISSION = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void oneMixedAxisPartitionsIntoExactDurableAndNativeProductsWithoutInventingMembers()
		throws Exception {
		Fixture fixture = fixture();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false);
		List<CandidateEmissionRealization> parts = productPublications(
			closure, fixture.product(), fixture.owner(), fixture.outputAnchor(),
			"mixed-partition", fixture.sources());

		Assert.assertEquals("one mixed source axis must split into exactly two products", 2,
			parts.size());
		CandidateEmissionRealization durable = part(parts, PlacementLayoutKind.DURABLE_MAP);
		CandidateEmissionRealization nativePart = part(parts, PlacementLayoutKind.NATIVE_LINEAGE);
		Set<PlacementIdentity.PlacementRealizationKey> exactKeys = fixture.exactSources().stream()
			.map(CandidateEmissionRealization::key).collect(java.util.stream.Collectors.toSet());
		Set<PlacementIdentity.PlacementRealizationKey> inexactKeys = fixture.inexactSources().stream()
			.map(CandidateEmissionRealization::key).collect(java.util.stream.Collectors.toSet());
		assertLazyExactPart(durable, exactKeys);
		assertLazyExactPart(nativePart, inexactKeys);
		Assert.assertNotEquals(durable.key(), nativePart.key());

		CandidateEmissionFact expected = scalarReference(fixture);
		CandidateEmissionFact actual = new CandidateEmissionFact(
			ROW_EMISSION, FType.ROW, null, parts);
		Assert.assertEquals("partitioning must preserve the full ordered scalar receipt union",
			expected.realizations(), actual.realizations());
		for(CandidateEmissionRealization part : parts) {
			NativeContinuitySupportClauses clauses =
				(NativeContinuitySupportClauses)part.supportClauses();
			Assert.assertEquals(2, clauses.materializedHandleCount());
			for(CandidateRealizationSupportClause clause : clauses) {
				Assert.assertSame(fixture.owner(), clause.proofDependencies().get(0).owner());
				Assert.assertSame(fixture.sourceOwner(),
					clause.inputBindings().get(0).source().rule().parentOccurrence());
			}
		}
	}

	@Test
	public void splitRefusesToPublishASubsetWhenANonRequiredOptionIsNotExecutable()
		throws Exception {
		Fixture fixture = fixture();
		CandidateEmissionRealization dead = CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, "dead-non-required", List.of(), List.of());
		List<CandidateEmissionRealization> sources = new ArrayList<>(fixture.exactSources());
		sources.addAll(fixture.inexactSources());
		sources.add(dead);
		CandidateRuleFact sourceFact = fact(new CandidateRuleKey(
			fixture.sourceOwner(), List.of()), sources);
		List<CandidateRealizationInputBinding> axis = sources.stream().map(realization ->
			CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(sourceFact.key(), realization))).toList();
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				fixture.product().externalSeed(), fixture.product().outputWorkerPoolWitness(),
				true, List.of(axis));
		Assert.assertNotNull(product);
		Assert.assertEquals(5, product.size());
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false);
		Assert.assertTrue("partitioning must fall back instead of publishing a live subset",
			productPublications(closure, product, fixture.owner(), fixture.outputAnchor(),
				"mixed-dead-non-required", directSources(List.of(sourceFact))).isEmpty());
	}

	@Test
	public void requiredAxisMayFilterADeadOptionWithoutLosingRequiredAuthority()
		throws Exception {
		Fixture fixture = fixture();
		CandidateEmissionRealization dead = CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, "dead-required", List.of(), List.of());
		List<CandidateEmissionRealization> sources = new ArrayList<>(fixture.exactSources());
		sources.addAll(fixture.inexactSources());
		sources.add(dead);
		CandidateRuleFact sourceFact = fact(new CandidateRuleKey(
			fixture.sourceOwner(), List.of()), sources);
		List<CandidateRealizationInputBinding> axis = sources.stream().map(realization ->
			CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(sourceFact.key(), realization))).toList();
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				fixture.product().externalSeed(), fixture.product().outputWorkerPoolWitness(),
				true, List.of(axis));
		Assert.assertNotNull(product);
		Class<?> inputType = Class.forName(
			PlacementRelationClosure.class.getName() + "$DirectInputBinding");
		Constructor<?> constructor = inputType.getDeclaredConstructor(
			int.class, FType.class, CompiledHopKey.class);
		constructor.setAccessible(true);
		Object required = constructor.newInstance(0, FType.ROW, fixture.sourceOwner());
		List<CandidateEmissionRealization> publications = productPublications(
			new PlacementRelationClosure(null, null, null, false, PrivacyEvidenceMode.NONE, false),
			product, fixture.owner(), fixture.outputAnchor(), "mixed-dead-required",
			directSources(List.of(sourceFact)), List.of(required));
		Assert.assertEquals(2, publications.size());
		Assert.assertEquals(4, publications.stream()
			.mapToInt(publication -> publication.supportClauses().size()).sum());
		Assert.assertFalse(publications.stream().flatMap(publication ->
			publication.supportClauses().stream()).flatMap(clause ->
				clause.inputBindings().stream()).anyMatch(binding ->
					binding.source().realization().equals(dead.key())));
	}

	@Test
	public void singletonMixedBinderFallsBackToTheCompleteScalarUnion()
		throws Exception {
		BinderFixture fixture = binderFixture(1, 1, "singletons");
		NativePlacementContinuity.CandidateSupportResult query = fixture.continuity()
			.proveGeneratedCandidateSupport(fixture.consumer(), fixture.emission(),
				fixture.proposed(), fixture.pool());
		Assert.assertNotNull(query.supportProduct());
		Assert.assertEquals(2, query.supportProduct().size());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		CandidateRuleFact published = bind(fixture, new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false)).fact();
		Assert.assertEquals(2, published.allowedEmissionFacts().get(0).realizations().size());
		Assert.assertTrue(published.allowedEmissionFacts().get(0).realizations().stream()
			.noneMatch(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses));
		SearchSpaceMetrics.NativePublicationCount fallback = metrics.nativePublicationSnapshot()
			.stream().filter(row -> row.outcome().name().equals(
				"MIXED_EXACTNESS_SINGLE_AXIS")).findFirst().orElseThrow();
		Assert.assertEquals(1, fallback.queries());
		Assert.assertEquals(2, fallback.logicalProofs());
		Assert.assertEquals(2, fallback.consumedProofs());
		Assert.assertEquals(0, metrics.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome().name().equals("PARTITIONED"))
			.mapToLong(SearchSpaceMetrics.NativePublicationCount::queries).sum());
	}

	@Test
	public void singletonAndMultiMemberPartsChooseTheirEncodingIndependently()
		throws Exception {
		for(int[] widths : List.of(new int[] {1, 2}, new int[] {2, 1})) {
			BinderFixture fixture = binderFixture(widths[0], widths[1],
				"asymmetric-" + widths[0] + '-' + widths[1]);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			CandidateRuleFact published = bind(fixture, new PlacementRelationClosure(
				null, null, metrics, false, PrivacyEvidenceMode.NONE, false)).fact();
			List<CandidateEmissionRealization> realizations =
				published.allowedEmissionFacts().get(0).realizations();
			Assert.assertEquals(2, realizations.size());
			Assert.assertEquals(1, realizations.stream().filter(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses).count());
			CandidateEmissionRealization lazy = realizations.stream().filter(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses)
				.findFirst().orElseThrow();
			Assert.assertEquals(2, lazy.supportClauses().size());
			SearchSpaceMetrics.NativePublicationCount partitioned = metrics
				.nativePublicationSnapshot().stream()
				.filter(row -> row.outcome().name().equals("PARTITIONED"))
				.findFirst().orElseThrow();
			Assert.assertEquals(3, partitioned.logicalProofs());
			Assert.assertEquals(1, partitioned.consumedProofs());
			Assert.assertEquals(1L, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.PARTITIONED_SINGLETON_PROOFS));
			Assert.assertEquals(0L, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.PARTITIONED_COLLISION_PROOFS));
			Assert.assertEquals(0L, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.PARTITIONED_RETAINED_PROOFS));
		}
	}

	@Test
	public void generatedValueMapMixturePublishesTwoLazyPartsWithScalarParity()
		throws Exception {
		BinderFixture fixture = binderFixture();
		NativePlacementContinuity.CandidateSupportResult query = fixture.continuity()
			.proveGeneratedCandidateSupport(fixture.consumer(), fixture.emission(),
				fixture.proposed(), fixture.pool());
		Assert.assertNotNull("the generated query must expose its mixed source axis",
			query.supportProduct());
		Assert.assertTrue(query.supportProduct().exactPartitionRanges());
		Assert.assertEquals(4, query.supportProduct().size());
		Assert.assertEquals(1, query.supportProduct().axes().size());
		Assert.assertEquals(2, query.supportProduct().axes().get(0).stream()
			.filter(binding -> binding.source().realization().layoutKind()
				== PlacementLayoutKind.VALUE_MAP).count());
		Assert.assertEquals(2, query.supportProduct().axes().get(0).stream()
			.filter(binding -> binding.source().realization().layoutKind()
				== PlacementLayoutKind.NATIVE_LINEAGE).count());

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, metrics, false, PrivacyEvidenceMode.NONE, false);
		BoundResult bound = bind(fixture, closure);
		CandidateRuleFact rebound = bound.fact();
		List<CandidateEmissionRealization> products = rebound.allowedEmissionFacts().get(0)
			.realizations().stream().filter(realization ->
				realization.supportClauses() instanceof NativeContinuitySupportClauses).toList();
		Assert.assertEquals("one mixed generated axis must publish both lazy parts", 2,
			products.size());
		Assert.assertEquals(Set.of(PlacementLayoutKind.DURABLE_MAP,
			PlacementLayoutKind.NATIVE_LINEAGE), products.stream()
			.map(realization -> realization.key().layoutKind())
			.collect(java.util.stream.Collectors.toSet()));
		for(CandidateEmissionRealization product : products) {
			NativeContinuitySupportClauses clauses =
				(NativeContinuitySupportClauses)product.supportClauses();
			Assert.assertEquals(2, clauses.size());
			Assert.assertTrue(clauses.product().exactPartitionRanges());
			Assert.assertTrue(clauses.clauseLayoutExact());
			Assert.assertEquals(0, clauses.materializedHandleCount());
		}
		Assert.assertTrue(bound.footprint().get(fixture.consumer().key().parentOccurrence())
			.contains(fixture.sourceOwner()));
		Assert.assertTrue("VALUE_MAP support must retain its transitive leaf dependency",
			bound.footprint().get(fixture.consumer().key().parentOccurrence())
				.contains(fixture.leafOwner()));
		String lineage = derivedLineage(fixture.consumer().key().parentOccurrence(), fixture.pool());
		DurableAnchorKey outputAnchor = fixture.proposed().realization().durableAnchor();
		List<CandidateEmissionRealization> explicit = new ArrayList<>();
		PlacementRelationClosure referenceClosure = new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false);
		for(NativePlacementContinuity.NativeContinuityProof proof : query.proofs()) {
			boolean exact = proof.immediateBindings().get(0).source().realization().layoutKind()
				== PlacementLayoutKind.NATIVE_LINEAGE;
			explicit.add(referencePublication(referenceClosure, proof,
				fixture.consumer().key().parentOccurrence(), outputAnchor, lineage, exact));
		}
		CandidateEmissionFact expectedFact = new CandidateEmissionFact(
			ROW_EMISSION, FType.ROW, null, explicit);
		Assert.assertEquals("lazy parts must equal the complete ordered scalar realization union",
			expectedFact.realizations(), rebound.allowedEmissionFacts().get(0).realizations());
		List<String> expected = query.proofs().stream().map(proof ->
			proof.continuityProofKey(fixture.consumer().key().parentOccurrence()).normalizedSignature()
				+ '|' + proof.immediateBindings().get(0).source().normalizedSignature())
			.sorted().toList();
		List<String> actual = products.stream().flatMap(realization ->
			realization.supportClauses().stream()).map(clause ->
			clause.proofDependencies().get(0).normalizedSignature() + '|'
				+ clause.inputBindings().get(0).source().normalizedSignature()).sorted().toList();
		Assert.assertEquals("partitioning must preserve the independent scalar proof union",
			expected, actual);
		for(CandidateEmissionRealization product : products)
			for(CandidateRealizationSupportClause clause : product.supportClauses())
				Assert.assertSame(fixture.sourceOwner(),
					clause.inputBindings().get(0).source().rule().parentOccurrence());
		SearchSpaceMetrics.NativePublicationCount partitioned = metrics.nativePublicationSnapshot()
			.stream().filter(row -> row.outcome().name().equals("PARTITIONED"))
			.findFirst().orElseThrow();
		Assert.assertEquals(1, partitioned.queries());
		Assert.assertEquals(4, partitioned.logicalProofs());
		Assert.assertEquals(0, partitioned.consumedProofs());
		BoundResult metricsOff = bind(fixture, new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false));
		Assert.assertEquals(rebound.allowedEmissionFacts(),
			metricsOff.fact().allowedEmissionFacts());
		Assert.assertEquals(footprintSignature(bound.footprint()),
			footprintSignature(metricsOff.footprint()));
	}

	@Test
	public void replayAndPerPartFallbackPreserveTheCompleteMixedAuthority()
		throws Exception {
		BinderFixture coldFixture = binderFixture();
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, new SearchSpaceMetrics(), false, PrivacyEvidenceMode.NONE, false);
		CandidateRuleFact first = bind(coldFixture, closure).fact();
		BinderFixture replayFixture = withConsumer(coldFixture, first);
		CandidateRuleFact replay = bind(replayFixture, closure).fact();
		Assert.assertEquals(first.allowedEmissionFacts(), replay.allowedEmissionFacts());
		for(CandidateEmissionRealization retained : first.allowedEmissionFacts().get(0).realizations())
			Assert.assertSame("exact replay must preserve each admitted part",
				retained, replay.allowedEmissionFacts().get(0).realizations().stream()
					.filter(candidate -> candidate.key().equals(retained.key()))
					.findFirst().orElseThrow());

		List<CandidateEmissionRealization> firstParts = first.allowedEmissionFacts().get(0)
			.realizations();
		CandidateEmissionRealization durable = part(firstParts, PlacementLayoutKind.DURABLE_MAP);
		CandidateEmissionRealization nativePart = part(firstParts, PlacementLayoutKind.NATIVE_LINEAGE);
		CandidateEmissionRealization partialDurable = new CandidateEmissionRealization(
			durable.key(), List.of(durable.supportClauses().get(0)));
		CandidateRuleFact partial = fact(first.key(), List.of(partialDurable, nativePart));
		SearchSpaceMetrics fallbackMetrics = new SearchSpaceMetrics();
		CandidateRuleFact fallback = bind(withConsumer(coldFixture, partial),
			new PlacementRelationClosure(null, null, fallbackMetrics, false,
				PrivacyEvidenceMode.NONE, false)).fact();
		CandidateEmissionRealization durableFallback = part(
			fallback.allowedEmissionFacts().get(0).realizations(), PlacementLayoutKind.DURABLE_MAP);
		CandidateEmissionRealization nativeReplay = part(
			fallback.allowedEmissionFacts().get(0).realizations(), PlacementLayoutKind.NATIVE_LINEAGE);
		Assert.assertFalse("only the conflicting durable part must use scalar fallback",
			durableFallback.supportClauses() instanceof NativeContinuitySupportClauses);
		Assert.assertEquals(2, durableFallback.supportClauses().size());
		Assert.assertSame("the independently covered native part must remain lazy",
			nativePart, nativeReplay);
		Assert.assertEquals(first.allowedEmissionFacts(), fallback.allowedEmissionFacts());
		SearchSpaceMetrics.NativePublicationCount partitioned = fallbackMetrics
			.nativePublicationSnapshot().stream()
			.filter(row -> row.outcome().name().equals("PARTITIONED"))
			.findFirst().orElseThrow();
		Assert.assertEquals(1, partitioned.queries());
		Assert.assertEquals(4, partitioned.logicalProofs());
		Assert.assertEquals("only the rejected two-member part may consume scalar proofs",
			2, partitioned.consumedProofs());
		Assert.assertEquals(2L, fallbackMetrics.directWorkCount(
			SearchSpaceMetrics.DirectWork.PARTITIONED_RETAINED_PROOFS));
		Assert.assertEquals(0L, fallbackMetrics.directWorkCount(
			SearchSpaceMetrics.DirectWork.PARTITIONED_COLLISION_PROOFS));
		Assert.assertEquals(0L, fallbackMetrics.directWorkCount(
			SearchSpaceMetrics.DirectWork.PARTITIONED_SINGLETON_PROOFS));
	}

	private static void assertLazyExactPart(CandidateEmissionRealization part,
		Set<PlacementIdentity.PlacementRealizationKey> expectedSources) {
		Assert.assertTrue(part.supportClauses() instanceof NativeContinuitySupportClauses);
		NativeContinuitySupportClauses clauses =
			(NativeContinuitySupportClauses)part.supportClauses();
		Assert.assertTrue("both partition products retain exact output-range authority",
			clauses.product().exactPartitionRanges());
		Assert.assertTrue(clauses.clauseLayoutExact());
		Assert.assertEquals(2, clauses.size());
		Assert.assertEquals("admission must not materialize either partition", 0,
			clauses.materializedHandleCount());
		Assert.assertEquals(expectedSources, clauses.product().axes().get(0).stream()
			.map(binding -> binding.source().realization())
			.collect(java.util.stream.Collectors.toSet()));
	}

	private static CandidateEmissionRealization part(List<CandidateEmissionRealization> parts,
		PlacementLayoutKind kind) {
		return parts.stream().filter(part -> part.key().layoutKind() == kind)
			.findFirst().orElseThrow();
	}

	private static CandidateEmissionFact scalarReference(Fixture fixture) throws Exception {
		PlacementRelationClosure closure = new PlacementRelationClosure(
			null, null, null, false, PrivacyEvidenceMode.NONE, false);
		List<CandidateEmissionRealization> explicit = new ArrayList<>();
		for(CandidateRealizationInputBinding binding : fixture.product().axes().get(0)) {
			NativePlacementContinuity.NativeContinuityProof proof =
				new NativePlacementContinuity.NativeContinuityProof(
					fixture.product().externalSeed(),
					fixture.product().outputWorkerPoolWitness(), true, List.of(binding));
			boolean exact = fixture.exactSources().stream().anyMatch(source ->
				source.key().equals(binding.source().realization()));
			explicit.add(referencePublication(closure, proof, fixture.owner(),
				fixture.outputAnchor(), "mixed-partition", exact));
		}
		return new CandidateEmissionFact(ROW_EMISSION, FType.ROW, null, explicit);
	}

	private static Fixture fixture() throws Exception {
		CompiledHopKey owner = fixtureKey("mixed-partition-owner");
		CompiledHopKey sourceOwner = fixtureKey("mixed-partition-source");
		DurableAnchorKey pool = pool("mixed-partition-pool");
		DurableAnchorKey output = pool("mixed-partition-output");
		List<CandidateEmissionRealization> exact = List.of(
			source(sourceOwner, pool, "exact-1", true),
			source(sourceOwner, pool, "exact-2", true));
		List<CandidateEmissionRealization> inexact = List.of(
			source(sourceOwner, pool, "inexact-1", false),
			source(sourceOwner, pool, "inexact-2", false));
		List<CandidateEmissionRealization> all = new ArrayList<>(exact);
		all.addAll(inexact);
		CandidateRuleFact sourceFact = fact(new CandidateRuleKey(sourceOwner, List.of()), all);
		List<CandidateRealizationInputBinding> axis = all.stream().map(realization ->
			CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(sourceFact.key(), realization))).toList();
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(pool, pool, true,
				List.of(axis));
		Assert.assertNotNull(product);
		Assert.assertEquals(4, product.size());
		Assert.assertEquals(1, product.axes().size());
		Assert.assertEquals(Set.of(sourceOwner), product.axes().get(0).stream()
			.map(binding -> binding.source().rule().parentOccurrence())
			.collect(java.util.stream.Collectors.toSet()));
		return new Fixture(owner, sourceOwner, output, exact, inexact, product,
			directSources(List.of(sourceFact)));
	}

	private static CandidateEmissionRealization source(CompiledHopKey owner,
		DurableAnchorKey pool, String lineage, boolean exact) {
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, owner, "source-" + lineage);
		return exact ? CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, lineage, pool, List.of(proof), List.of())
			: CandidateEmissionRealization.nativeLineageDynamicLayout(
				ROW_EMISSION, lineage, pool, List.of(proof), List.of());
	}

	private static BinderFixture binderFixture() throws Exception {
		return binderFixture(2, 2, "full");
	}

	private static BinderFixture binderFixture(int exactCount, int valueCount, String id)
		throws Exception {
		CompiledHopKey leafOwner = fixtureKey("mixed-binder-leaf-" + id);
		CompiledHopKey sourceOwner = fixtureKey("mixed-binder-source-" + id);
		CompiledHopKey consumerOwner = fixtureKey("mixed-binder-consumer-" + id);
		DurableAnchorKey pool = pool("mixed-binder-pool-" + id);
		CandidateEmissionRealization leafRealization = source(leafOwner, pool, "leaf", true);
		CandidateRuleFact leaf = fact(new CandidateRuleKey(leafOwner, List.of()),
			List.of(leafRealization));
		CandidateRealizationReference leafReference = CandidateRealizationReference.of(
			leaf.key(), leafRealization);
		List<CandidateRealizationInputBinding> leafBinding = List.of(
			CandidateRealizationInputBinding.direct(0, leafReference));
		List<CandidateEmissionRealization> alternatives = new ArrayList<>();
		for(int index = 0; index < exactCount; index++)
			alternatives.add(CandidateEmissionRealization.nativeLineage(ROW_EMISSION,
				"exact-" + id + '-' + index, pool, List.of(new PlacementProofKey(
					PlacementProofKind.NATIVE_CONTINUITY, sourceOwner,
					"exact-" + id + '-' + index)), leafBinding));
		for(int index = 0; index < valueCount; index++)
			alternatives.add(CandidateEmissionRealization.valueMap(ROW_EMISSION,
				"value-" + id + '-' + index, List.of(
					new CandidateRealizationSupportClause(List.of(), leafBinding))));
		CandidateRuleFact source = fact(new CandidateRuleKey(sourceOwner,
			List.of(CandidateInputState.present(FType.ROW))), alternatives);
		CandidateEmissionRealization staging = CandidateEmissionRealization.nativeLineage(
			ROW_EMISSION, "mixed-binder-staging", List.of(), List.of());
		CandidateRuleFact consumer = fact(new CandidateRuleKey(consumerOwner,
			List.of(CandidateInputState.present(FType.ROW))), List.of(staging));
		DataOp leafHop = new DataOp("mixed-binder-leaf-" + id, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, "mixed-binder-leaf-" + id, 8, 2, 16, 1000);
		UnaryOp sourceHop = new UnaryOp("mixed-binder-source-" + id, DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, leafHop);
		UnaryOp consumerHop = new UnaryOp("mixed-binder-consumer-" + id, DataType.MATRIX,
			ValueType.FP64, OpOp1.LOG, sourceHop);
		List<CandidateRuleFact> inventory = List.of(leaf, source, consumer);
		List<Node> nodes = List.of(fixtureNode(leafOwner), fixtureNode(sourceOwner),
			fixtureNode(consumerOwner));
		List<CompiledInputEdgeFact> edges = List.of(
			new CompiledInputEdgeFact(leafOwner, sourceOwner, 0),
			new CompiledInputEdgeFact(sourceOwner, consumerOwner, 0));
		Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		origins.put(leafOwner, leafHop);
		origins.put(sourceOwner, sourceHop);
		origins.put(consumerOwner, consumerHop);
		Map<Hop,PlacementAnalysis.NodeShapeFact> shapes = new IdentityHashMap<>();
		shapes.put(leafHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(sourceHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		shapes.put(consumerHop, new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2));
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : nodes)
			nodesByKey.put(node.key(), node);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			nodesByKey, origins, inventory, edges, Map.of());
		CandidateEmissionFact emission = consumer.allowedEmissionFacts().get(0);
		DurableAnchorKey outputAnchor = nativeOutputAnchor(pool,
			new PlacementAnalysis.NodeShapeFact(DataType.MATRIX, 8, 2), consumerOwner);
		CandidateRealizationReference proposed = new CandidateRealizationReference(
			consumer.key(), PlacementIdentity.PlacementRealizationKey.durable(
				emission.emissionState(), outputAnchor));
		return new BinderFixture(leafOwner, sourceOwner, pool, consumer, emission, proposed,
			continuity, inventory, nodes, edges, origins, shapes);
	}

	private static BoundResult bind(BinderFixture fixture, PlacementRelationClosure closure)
		throws Exception {
		Method indexBuilder = PlacementRelationClosure.class.getDeclaredMethod("directBindingIndex",
			List.class, List.class, List.class, List.class, Map.class, Map.class);
		indexBuilder.setAccessible(true);
		Object index = indexBuilder.invoke(null, fixture.inventory(), fixture.nodes(), fixture.edges(),
			fixture.inventory(), fixture.origins(), fixture.shapes());
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsWithDependenciesMeasured", index.getClass(),
			List.class, Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Set<CompiledHopKey> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
		dirty.add(fixture.consumer().key().parentOccurrence());
		Object result = bind.invoke(closure, index, List.of(fixture.consumer()),
			fixture.origins(), fixture.shapes(), fixture.continuity(), dirty);
		Method facts = result.getClass().getDeclaredMethod("facts");
		facts.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> rebound = (List<CandidateRuleFact>)facts.invoke(result);
		Method dependencies = result.getClass().getDeclaredMethod("dependencyOccurrences");
		dependencies.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,Set<CompiledHopKey>> footprint =
			(Map<CompiledHopKey,Set<CompiledHopKey>>)dependencies.invoke(result);
		return new BoundResult(rebound.get(0), footprint);
	}

	private static BinderFixture withConsumer(BinderFixture fixture,
		CandidateRuleFact consumer) {
		List<CandidateRuleFact> inventory = new ArrayList<>(fixture.inventory());
		inventory.set(inventory.size() - 1, consumer);
		Map<CompiledHopKey,Node> nodesByKey = new IdentityHashMap<>();
		for(Node node : fixture.nodes())
			nodesByKey.put(node.key(), node);
		NativePlacementContinuity continuity = new NativePlacementContinuity(nodesByKey,
			fixture.origins(), inventory, fixture.edges(), Map.of());
		CandidateEmissionFact emission = consumer.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = new CandidateRealizationReference(
			consumer.key(), fixture.proposed().realization());
		return new BinderFixture(fixture.leafOwner(), fixture.sourceOwner(), fixture.pool(),
			consumer, emission, proposed, continuity, List.copyOf(inventory), fixture.nodes(),
			fixture.edges(), fixture.origins(), fixture.shapes());
	}

	private static List<String> footprintSignature(
		Map<CompiledHopKey,Set<CompiledHopKey>> footprint) {
		return footprint.entrySet().stream().map(entry ->
			entry.getKey().normalizedSignature() + "->" + entry.getValue().stream()
				.map(CompiledHopKey::normalizedSignature).sorted().toList())
			.sorted().toList();
	}

	private static DurableAnchorKey nativeOutputAnchor(DurableAnchorKey seed,
		PlacementAnalysis.NodeShapeFact shape, CompiledHopKey owner) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("nativeOutputAnchor",
			DurableAnchorKey.class, FType.class, PlacementAnalysis.NodeShapeFact.class,
			CompiledHopKey.class);
		method.setAccessible(true);
		return (DurableAnchorKey)method.invoke(null, seed, FType.ROW, shape, owner);
	}

	private static String derivedLineage(CompiledHopKey owner, DurableAnchorKey seed)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativeDerivedLineage", CompiledHopKey.class, DurableAnchorKey.class);
		method.setAccessible(true);
		return (String)method.invoke(null, owner, seed);
	}

	private static Node fixtureNode(CompiledHopKey key) throws Exception {
		Method method = DirectSourceSeedProjectionTest.class.getDeclaredMethod(
			"node", CompiledHopKey.class, List.class);
		method.setAccessible(true);
		return (Node)method.invoke(null, key, List.of());
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> productPublications(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey outputAnchor, String lineage, Object sources)
		throws Exception {
		return productPublications(closure, product, owner, outputAnchor, lineage,
			sources, List.of());
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateEmissionRealization> productPublications(
		PlacementRelationClosure closure, NativePlacementContinuity.NativeSupportProduct product,
		CompiledHopKey owner, DurableAnchorKey outputAnchor, String lineage, Object sources,
		List<?> requiredInputs) throws Exception {
		Class<?> trace = Class.forName(
			PlacementRelationClosure.class.getName() + "$NativePublicationTrace");
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"directNativeProductPublication", product.getClass(), CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, List.class,
			sources.getClass(), Map.class, trace);
		method.setAccessible(true);
		Object result = method.invoke(closure, product, owner, ROW_EMISSION, outputAnchor,
			lineage, requiredInputs, sources,
			new IdentityHashMap<CandidateEmissionRealization,Boolean>(), null);
		if(result == null)
			return List.of();
		return result instanceof List<?> list
			? (List<CandidateEmissionRealization>)list
			: List.of((CandidateEmissionRealization)result);
	}

	private static CandidateEmissionRealization referencePublication(
		PlacementRelationClosure closure,
		NativePlacementContinuity.NativeContinuityProof proof, CompiledHopKey owner,
		DurableAnchorKey outputAnchor, String lineage, boolean directInputsExact)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod("directNativePublication",
			NativePlacementContinuity.NativeContinuityProof.class, CompiledHopKey.class,
			PlacementEmissionState.class, DurableAnchorKey.class, String.class, boolean.class);
		method.setAccessible(true);
		return (CandidateEmissionRealization)method.invoke(closure, proof, owner,
			ROW_EMISSION, outputAnchor, lineage, directInputsExact);
	}

	private static Object directSources(List<CandidateRuleFact> facts) throws Exception {
		Class<?> type = Class.forName(PlacementRelationClosure.class.getName() + "$DirectSourceIndex");
		Constructor<?> constructor = type.getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	private static CandidateRuleFact fact(CandidateRuleKey key,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fact", CandidateRuleKey.class, List.class);
		method.setAccessible(true);
		return (CandidateRuleFact)method.invoke(null, key, realizations);
	}

	private static CompiledHopKey fixtureKey(String name) throws Exception {
		Method method = NativeMultiSeedPublicationTest.class.getDeclaredMethod(
			"fixtureKey", String.class);
		method.setAccessible(true);
		return (CompiledHopKey)method.invoke(null, name);
	}

	private static DurableAnchorKey pool(String id) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 2L))));
	}

	private record Fixture(CompiledHopKey owner, CompiledHopKey sourceOwner,
		DurableAnchorKey outputAnchor, List<CandidateEmissionRealization> exactSources,
		List<CandidateEmissionRealization> inexactSources,
		NativePlacementContinuity.NativeSupportProduct product, Object sources) { }

	private record BinderFixture(CompiledHopKey leafOwner, CompiledHopKey sourceOwner,
		DurableAnchorKey pool,
		CandidateRuleFact consumer, CandidateEmissionFact emission,
		CandidateRealizationReference proposed, NativePlacementContinuity continuity,
		List<CandidateRuleFact> inventory, List<Node> nodes,
		List<CompiledInputEdgeFact> edges, Map<CompiledHopKey,Hop> origins,
		Map<Hop,PlacementAnalysis.NodeShapeFact> shapes) { }

	private record BoundResult(CandidateRuleFact fact,
		Map<CompiledHopKey,Set<CompiledHopKey>> footprint) { }
}
