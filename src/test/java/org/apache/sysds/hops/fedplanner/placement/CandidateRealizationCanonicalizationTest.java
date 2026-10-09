/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.LogicalTransientInputFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientCompatibilityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Locks the full canonical alternative relation, not merely one minimum-cost plan. */
public class CandidateRealizationCanonicalizationTest {
	private static final CompiledHopKey OWNER = key("owner");
	private static final PlacementState FED = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(FED, false);
	private static final CandidateRuleKey RULE = new CandidateRuleKey(OWNER,
		List.of(CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW)));

	@Test
	public void workerPoolQueriesKeepExactClauseOwnershipForLargeAlternatives() {
		DurableAnchorKey anchor = pool("indexed", 1234);
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(int index = 0; index < 40; index++)
			clauses.add(new CandidateRealizationSupportClause(
				List.of(proof("indexed-" + index)), List.of()));
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.durable(EMISSION, anchor), clauses);
		for(int repeat = 0; repeat < 3; repeat++)
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				Assert.assertSame(anchor, realization.provenWorkerPool(clause));
				Assert.assertSame(anchor, realization.nativeWorkerPoolResidencyWitness(clause));
				Assert.assertTrue(realization.nativeWorkerPoolLayoutExact(clause));
				CandidateRealizationSupportClause foreign = copy(clause);
				Assert.assertEquals(clause, foreign);
				Assert.assertThrows(IllegalArgumentException.class, () -> realization.provenWorkerPool(foreign));
				Assert.assertThrows(IllegalArgumentException.class,
					() -> realization.nativeWorkerPoolResidencyWitness(foreign));
				Assert.assertThrows(IllegalArgumentException.class,
					() -> realization.nativeWorkerPoolLayoutExact(foreign));
			}
	}

	@Test
	public void realizationSerializationReusesClauseDescriptorsAndKeepsColdPaths() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		List<CandidateRealizationSupportClause> clauses = List.of(
			new CandidateRealizationSupportClause(List.of(proof("reuse-a")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("reuse-b")), List.of()));
		CandidateEmissionRealization retained = new CandidateEmissionRealization(template.key(), clauses);
		var text = new PlacementAnalysis.NormalizedTextContext().emissionRealization(retained);
		Set<Object> descendants = canonicalDescendants(text);
		for(Object descriptor : descriptorSidecar(retained.supportClauses()))
			Assert.assertTrue("reuse each descriptor already computed by clause sorting",
				descendants.contains(descriptor));
		Assert.assertEquals(retained.normalizedSignature(), text.materialize());
		CandidateEmissionRealization cold = CandidateEmissionRealization.fromAlreadyCanonicalSupportClauses(
			template.key(), new ArrayList<>(retained.supportClauses()));
		Assert.assertNull(descriptorSidecarOrNull(cold.supportClauses()));
		Assert.assertEquals(retained.normalizedSignature(),
			new PlacementAnalysis.NormalizedTextContext().emissionRealization(cold).materialize());
		Assert.assertNull("cold serialization must not mutate an immutable descriptor sidecar",
			descriptorSidecarOrNull(cold.supportClauses()));
		CandidateRealizationSupportClause singleton = clauses.get(0);
		Assert.assertNull("singleton constructor must not eagerly build a descriptor",
			descriptorSidecarOrNull(singleton.proofDependencies()));
		for(CandidateRealizationSupportClause value : List.of(singleton,
			new CandidateRealizationSupportClause(List.of(), List.of())))
			Assert.assertEquals(legacySignature(value),
				new PlacementAnalysis.NormalizedTextContext().supportClause(value).materialize());
		CandidateRealizationSupportClause dynamic = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "dynamic-a"),
				proof("dynamic-b")), List.of(), pool("dynamic", 1234), false);
		Assert.assertEquals(legacySignature(dynamic) + "|nativePoolLayout=dynamic",
			new PlacementAnalysis.NormalizedTextContext().supportClause(dynamic).materialize());
	}

	private static Set<Object> canonicalDescendants(PlacementAnalysis.NormalizedText text) throws Exception {
		Field field = PlacementAnalysis.NormalizedText.class.getDeclaredField("text");
		field.setAccessible(true);
		Set<Object> descendants = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		collectCanonicalTextDescendants(field.get(text), descendants);
		return descendants;
	}

	@Test
	public void segmentedRealizationsKeepEveryLegacySignatureByte() {
		var context = new PlacementAnalysis.NormalizedTextContext();
		for(var realization : fixture()) {
			var segmented = context.emissionRealization(realization);
			String expected = realization.normalizedSignature();
			List<String> chunks = new ArrayList<>();
			segmented.appendTo(chunks::add);
			Assert.assertEquals(expected, String.join("", chunks));
			Assert.assertEquals(expected.length(), segmented.length());
			Assert.assertEquals(expected.hashCode(), segmented.hashCode());
		}
	}

	@Test
	public void coldAndWarmSignaturesRetainLegacyBytesAndOrdering() throws Exception {
		List<CandidateRealizationSupportClause> clauses = fixture().stream()
			.flatMap(realization -> realization.supportClauses().stream()).toList();
		clearSignatures();
		for(CandidateRealizationSupportClause clause : clauses) {
			String expected = legacySignature(clause);
			Assert.assertEquals(expected, clause.normalizedSignature());
			Assert.assertEquals(expected, clause.normalizedSignature());
			Assert.assertEquals(expected, copy(clause).normalizedSignature());
		}
		for(CandidateRealizationSupportClause left : clauses)
			for(CandidateRealizationSupportClause right : clauses)
				Assert.assertEquals(Integer.signum(legacySignature(left).compareTo(legacySignature(right))),
					Integer.signum(left.compareTo(right)));
	}

	@Test
	public void repeatedSignatureReusesSerialization() throws Exception {
		CandidateRealizationSupportClause clause = fixture().get(0).supportClauses().get(0);
		clearSignatures();
		String first = clause.normalizedSignature();
		Assert.assertSame("immutable clause serialization must be reused", first, clause.normalizedSignature());
		Assert.assertSame("equal immutable clauses may reuse bytes, not authority objects",
			first, copy(clause).normalizedSignature());
	}

	@Test
	public void repeatedRealizationSignatureReusesSerialization() throws Exception {
		CandidateEmissionRealization realization = fixture().get(0);
		clearSignatures();
		String expected = realization.key().normalizedSignature() + "|support="
			+ realization.supportClauses().stream()
				.map(CandidateRealizationSupportClause::normalizedSignature).toList();
		clearSignatures();
		String first = realization.normalizedSignature();
		Assert.assertEquals(expected, first);
		Assert.assertSame("immutable realization serialization must be reused",
			first, realization.normalizedSignature());
	}

	@Test
	public void hotIdentityCacheSkipsRepeatedStructuralHashAndSerialization() {
		CandidateRealizationSupportClause clause = fixture().get(0).supportClauses().get(0);
		CandidateRealizationSupportClause equalCopy = copy(clause);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			String signature = clause.normalizedSignature();
			SearchSpaceMetrics.Snapshot cold = metrics.snapshot();
			for(int iteration = 0; iteration < 64; iteration++)
				Assert.assertSame(signature, clause.normalizedSignature());
			SearchSpaceMetrics.Snapshot hot = metrics.snapshot();
			Assert.assertEquals("hot probes must not serialize again",
				cold.signatureSerializations(), hot.signatureSerializations());
			Assert.assertTrue("same-object probes use identity hashing only",
				hot.signatureIdentityCacheHits() - cold.signatureIdentityCacheHits() >= 64);

			Assert.assertSame("the cold structural fallback preserves equal-copy sharing",
				signature, equalCopy.normalizedSignature());
			Assert.assertTrue(metrics.snapshot().signatureStructuralCacheHits() > 0);
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void duplicateLayoutMergeDoesNotResortEveryClause() {
		CandidateEmissionRealization realization = fixture().get(0);
		List<CandidateEmissionRealization> duplicates = new ArrayList<>();
		for(int index = 0; index < 64; index++)
			duplicates.add(realization);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionFact emission = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, duplicates);
			Assert.assertSame(realization, emission.realizations().get(0));
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(64, work.realizationMergeInputs());
			Assert.assertEquals(1, work.realizationMergeUniqueClauses());
			Assert.assertEquals(63, work.realizationMergeDuplicateClauses());
			Assert.assertEquals(1, work.realizationMergeReusedRealizations());
			Assert.assertEquals("a one-element publication boundary needs no sort",
				0, work.canonicalSortCalls());
			Assert.assertEquals(0, work.canonicalOrderingKeys());
			Assert.assertEquals(0, work.canonicalComparisons());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void canonicalSupportSubsetDoesNotResortUnchangedOrder() {
		CandidateEmissionRealization source = new CandidateEmissionRealization(fixture().get(0).key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof("subset-a")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("subset-b")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("subset-c")), List.of())));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization subset = CandidateEmissionRealization
				.fromAlreadyCanonicalSupportClauses(source.key(), source.supportClauses().subList(1, 3));
			Assert.assertEquals(source.supportClauses().subList(1, 3), subset.supportClauses());
			Assert.assertEquals(0, metrics.snapshot().canonicalSortCalls());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void canonicalDescriptorSidecarsSurviveSubsetAndTwoWayUnion() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("sidecar-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("sidecar-b")), List.of());
		CandidateRealizationSupportClause c =
			new CandidateRealizationSupportClause(List.of(proof("sidecar-c")), List.of());
		CandidateEmissionRealization canonical = new CandidateEmissionRealization(
			template.key(), List.of(c, a, b));
		List<?> canonicalKeys = descriptorSidecar(canonical.supportClauses());
		Assert.assertEquals(canonical.supportClauses().size(), canonicalKeys.size());

		CandidateEmissionRealization subset = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), canonical.supportClauses().subList(1, 3));
		List<?> subsetKeys = descriptorSidecar(subset.supportClauses());
		Assert.assertSame(canonicalKeys.get(1), subsetKeys.get(0));
		Assert.assertSame(canonicalKeys.get(2), subsetKeys.get(1));

		CandidateEmissionRealization left = new CandidateEmissionRealization(template.key(), List.of(c, a));
		CandidateEmissionRealization right = new CandidateEmissionRealization(
			template.key(), List.of(copy(c), b));
		List<?> leftKeys = descriptorSidecar(left.supportClauses());
		List<?> rightKeys = descriptorSidecar(right.supportClauses());
		CandidateEmissionRealization union = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(left, right)).realizations().get(0);
		Assert.assertEquals(List.of(a, b, c), union.supportClauses());
		List<?> unionKeys = descriptorSidecar(union.supportClauses());
		Assert.assertSame(leftKeys.get(left.supportClauses().indexOf(a)), unionKeys.get(0));
		Assert.assertSame(rightKeys.get(right.supportClauses().indexOf(b)), unionKeys.get(1));
		Assert.assertSame("equal support keeps the first authority descriptor",
			leftKeys.get(left.supportClauses().indexOf(c)), unionKeys.get(2));
		Assert.assertSame(a, union.supportClauses().get(0));
		Assert.assertSame(c, union.supportClauses().get(2));
	}

	@Test
	public void missingSidecarBuildsExactLocalDescriptorsAndTieFallsBack() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("missing-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("missing-b")), List.of());
		CandidateEmissionRealization withoutKeys = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(a));
		Assert.assertNull(descriptorSidecarOrNull(withoutKeys.supportClauses()));
		CandidateEmissionRealization withKeys = new CandidateEmissionRealization(template.key(), List.of(b, copy(a)));
		List<?> retainedKeys = descriptorSidecar(withKeys.supportClauses());
		CandidateEmissionRealization union = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(withoutKeys, withKeys)).realizations().get(0);
		Assert.assertEquals(List.of(a, b), union.supportClauses());
		List<?> unionKeys = descriptorSidecar(union.supportClauses());
		Assert.assertEquals(2, unionKeys.size());
		Assert.assertSame("the present sidecar descriptor must survive a one-sided miss",
			retainedKeys.get(withKeys.supportClauses().indexOf(b)),
			unionKeys.get(union.supportClauses().indexOf(b)));
		Assert.assertSame("the first authority still owns an equal clause", a, union.supportClauses().get(0));

		CandidateRealizationSupportClause distinct =
			new CandidateRealizationSupportClause(List.of(proof("tie-distinct")), List.of());
		CandidateRealizationSupportClause firstA = withKeys.supportClauses().stream()
			.filter(a::equals).findFirst().orElseThrow();
		CandidateEmissionRealization tied = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(distinct));
		setDescriptorSidecar(tied.supportClauses(),
			List.of(descriptorSidecar(withKeys.supportClauses()).get(0)));
		CandidateEmissionRealization collision = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(withKeys, tied)).realizations().get(0);
		Assert.assertTrue(collision.supportClauses().contains(distinct));
		Assert.assertTrue(collision.supportClauses().contains(a));
		Assert.assertTrue(collision.supportClauses().contains(b));
		Assert.assertSame("fallback retains the first authority", firstA,
			collision.supportClauses().stream().filter(a::equals).findFirst().orElseThrow());
	}

	@Test
	public void multiClauseTrustedListWithoutSidecarStaysValueOnlyUntilUnionConsumesIt()
		throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("trusted-missing-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("trusted-missing-b")), List.of());
		List<CandidateRealizationSupportClause> trustedWithoutKeys =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				List.of(a, b), "realization support clause");
		Assert.assertNull(descriptorSidecarOrNull(trustedWithoutKeys));
		CandidateEmissionRealization rebuilt = new CandidateEmissionRealization(
			template.key(), trustedWithoutKeys);
		Assert.assertSame(a, rebuilt.supportClauses().get(0));
		Assert.assertSame(b, rebuilt.supportClauses().get(1));
		Assert.assertNull("trusted canonical support must not eagerly materialize unused descriptors",
			descriptorSidecarOrNull(rebuilt.supportClauses()));
	}

	@Test
	public void distinctRealizationKeysReuseAuthoritiesBeforeClauseHandleWork() {
		CandidateEmissionRealization template = fixture().get(0);
		List<CandidateEmissionRealization> distinct = List.of(template,
			CandidateEmissionRealization.durable(EMISSION, pool("group-a", 1411),
				List.of(proof("group-a")), List.of()),
			CandidateEmissionRealization.durable(EMISSION, pool("group-b", 1412),
				List.of(proof("group-b")), List.of()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			CandidateEmissionFact emission = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, distinct);
			for(CandidateEmissionRealization realization : distinct)
				Assert.assertTrue("a singleton exact-key group must retain its authority object",
					emission.realizations().stream().anyMatch(candidate -> candidate == realization));
			Assert.assertEquals("distinct realization keys do not require clause structural handles",
				0, metrics.snapshot().structuralHandleLookups());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void threeSameKeyGroupsFormOneCanonicalUnionWithoutSupportResort() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("group-union-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("group-union-b")), List.of());
		CandidateRealizationSupportClause c =
			new CandidateRealizationSupportClause(List.of(proof("group-union-c")), List.of());
		CandidateRealizationSupportClause d =
			new CandidateRealizationSupportClause(List.of(proof("group-union-d")), List.of());
		CandidateEmissionRealization first = new CandidateEmissionRealization(template.key(), List.of(c, a));
		CandidateEmissionRealization second = new CandidateEmissionRealization(
			template.key(), List.of(copy(a), d));
		List<CandidateRealizationSupportClause> trusted = PlacementAnalysis.sharedAlreadyCanonicalComparableList(
			List.of(b, copy(c)), "realization support clause");
		CandidateEmissionRealization third = new CandidateEmissionRealization(template.key(), trusted);
		Assert.assertNull(descriptorSidecarOrNull(third.supportClauses()));

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, List.of(first, second, third)).realizations().get(0);
			Assert.assertEquals(List.of(a, b, c, d).stream().sorted().toList(), union.supportClauses());
			Assert.assertSame("the earliest exact clause authority wins", a,
				union.supportClauses().stream().filter(a::equals).findFirst().orElseThrow());
			Assert.assertSame("the earliest exact clause authority wins across the third group", c,
				union.supportClauses().stream().filter(c::equals).findFirst().orElseThrow());
			Assert.assertEquals("k-way canonical union uses one adaptive stable sort",
				1, metrics.snapshot().canonicalSortCalls());
			Assert.assertEquals(4, metrics.snapshot().canonicalSortElements());
			Assert.assertEquals(3, metrics.snapshot().realizationMergeInputs());
			Assert.assertEquals(4, metrics.snapshot().realizationMergeUniqueClauses());
			Assert.assertEquals(2, metrics.snapshot().realizationMergeDuplicateClauses());
			Assert.assertEquals(4, descriptorSidecar(union.supportClauses()).size());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void sharedClauseRunsDeduplicateIdentityBeforeOrdering() {
		CandidateEmissionRealization template = fixture().get(0);
		List<CandidateRealizationSupportClause> shared = new ArrayList<>();
		for(int index = 0; index < 64; index++)
			shared.add(new CandidateRealizationSupportClause(List.of(proof("shared-run-" + index)), List.of()));
		List<CandidateEmissionRealization> groups = new ArrayList<>();
		List<CandidateRealizationSupportClause> expected = new ArrayList<>(shared);
		for(int group = 0; group < 16; group++) {
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>(shared);
			CandidateRealizationSupportClause extra = new CandidateRealizationSupportClause(
				List.of(proof("extra-run-" + group)), List.of());
			clauses.add(extra);
			expected.add(extra);
			groups.add(new CandidateEmissionRealization(template.key(), clauses));
		}
		expected.sort(java.util.Comparator.comparing(CandidateRealizationCanonicalizationTest::legacySignature));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, groups).realizations().get(0);
			Assert.assertEquals(expected, union.supportClauses());
			for(int index = 0; index < expected.size(); index++)
				Assert.assertSame(expected.get(index), union.supportClauses().get(index));
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(80, work.realizationMergeUniqueClauses());
			Assert.assertEquals(960, work.realizationMergeDuplicateClauses());
			Assert.assertTrue("shared immutable clauses need only one ordering entry: "
				+ work.canonicalComparisons(), work.canonicalComparisons() < expected.size() * 6L);
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void manySameKeySingletonGroupsUseSubquadraticCanonicalComparisons() {
		CandidateEmissionRealization template = fixture().get(0);
		int groupCount = 128;
		List<CandidateEmissionRealization> alternatives = new ArrayList<>(groupCount);
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>(groupCount);
		for(int index = groupCount - 1; index >= 0; index--) {
			CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
				List.of(proof(String.format("heap-union-%03d", index))), List.of());
			clauses.add(clause);
			alternatives.add(new CandidateEmissionRealization(template.key(), List.of(clause)));
		}
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, alternatives).realizations().get(0);
			List<CandidateRealizationSupportClause> expected = clauses.stream().sorted().toList();
			Assert.assertEquals(expected, union.supportClauses());
			for(CandidateRealizationSupportClause clause : clauses)
				Assert.assertSame("every unique clause keeps its original authority", clause,
					union.supportClauses().stream().filter(clause::equals).findFirst().orElseThrow());
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(groupCount, work.realizationMergeInputs());
			Assert.assertEquals(groupCount, work.realizationMergeUniqueClauses());
			Assert.assertEquals(0, work.realizationMergeDuplicateClauses());
			Assert.assertTrue("one-head heap union must remain below a conservative O(G log G) bound: "
				+ work.canonicalComparisons(), work.canonicalComparisons() < 5000);
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void disjointSortedSupportRunsSkipBulkSortAfterBoundaryProof() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		int groupCount = 48, clausesPerGroup = 24;
		List<CandidateEmissionRealization> alternatives = new ArrayList<>(groupCount);
		List<CandidateRealizationSupportClause> expected = new ArrayList<>(groupCount * clausesPerGroup);
		Map<CandidateRealizationSupportClause,Object> expectedDescriptors = new java.util.IdentityHashMap<>();
		CandidateRealizationSupportClause priorLast = null;
		for(int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>(clausesPerGroup + 1);
			if(priorLast != null)
				clauses.add(groupIndex % 2 == 0 ? priorLast : copy(priorLast));
			List<CandidateRealizationSupportClause> originals = new ArrayList<>(clausesPerGroup);
			for(int clauseIndex = 0; clauseIndex < clausesPerGroup; clauseIndex++) {
				CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(List.of(proof(
					String.format("bulk-run-%04d", groupIndex * clausesPerGroup + clauseIndex))), List.of());
				clauses.add(clause);
				originals.add(clause);
				expected.add(clause);
			}
			CandidateEmissionRealization realization = new CandidateEmissionRealization(template.key(), clauses);
			List<?> descriptors = descriptorSidecar(realization.supportClauses());
			for(CandidateRealizationSupportClause clause : originals)
				expectedDescriptors.put(clause, descriptors.get(realization.supportClauses().indexOf(clause)));
			alternatives.add(realization);
			priorLast = originals.get(originals.size() - 1);
		}

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, alternatives).realizations().get(0);
			Assert.assertEquals(expected.stream().map(CandidateRealizationCanonicalizationTest::legacySignature)
				.toList(), union.supportClauses().stream()
					.map(CandidateRealizationCanonicalizationTest::legacySignature).toList());
			List<?> unionDescriptors = descriptorSidecar(union.supportClauses());
			for(int index = 0; index < expected.size(); index++) {
				Assert.assertSame("the first exact authority object survives bulk deduplication",
					expected.get(index), union.supportClauses().get(index));
				Assert.assertSame("the first authority descriptor stays aligned",
					expectedDescriptors.get(expected.get(index)), unionDescriptors.get(index));
			}
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(groupCount, work.realizationMergeInputs());
			Assert.assertEquals(expected.size(), work.realizationMergeUniqueClauses());
			Assert.assertEquals(groupCount - 1, work.realizationMergeDuplicateClauses());
			Assert.assertTrue("already ordered nonempty runs need at most one boundary comparison each: "
				+ work.canonicalComparisons(), work.canonicalComparisons() <= groupCount - 1L);
			Assert.assertEquals("a proven concatenation must not invoke TimSort",
				0, work.canonicalSortCalls());
			Assert.assertEquals(0, work.canonicalSortElements());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void oneSurvivingCanonicalRunSkipsSortAndKeepsFirstDonorIdentity() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a = new CandidateRealizationSupportClause(
			List.of(proof("single-run-a")), List.of());
		CandidateRealizationSupportClause b = new CandidateRealizationSupportClause(
			List.of(proof("single-run-b")), List.of());
		CandidateRealizationSupportClause c = new CandidateRealizationSupportClause(
			List.of(proof("single-run-c")), List.of());
		CandidateEmissionRealization first = new CandidateEmissionRealization(
			template.key(), List.of(c, a, b));
		List<?> firstDescriptors = descriptorSidecar(first.supportClauses());
		CandidateEmissionRealization equalDistinctSubset = new CandidateEmissionRealization(
			template.key(), List.of(copy(a), copy(c)));
		CandidateEmissionRealization sharedSubset = new CandidateEmissionRealization(
			template.key(), List.of(b));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null,
				List.of(first, equalDistinctSubset, sharedSubset)).realizations().get(0);
			Assert.assertSame("an unchanged first run must retain its realization authority",
				first, union);
			for(int index = 0; index < first.supportClauses().size(); index++) {
				Assert.assertSame(first.supportClauses().get(index), union.supportClauses().get(index));
				Assert.assertSame(firstDescriptors.get(index),
					descriptorSidecar(union.supportClauses()).get(index));
			}
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(3, work.realizationMergeUniqueClauses());
			Assert.assertEquals(3, work.realizationMergeDuplicateClauses());
			Assert.assertEquals(0, work.canonicalComparisons());
			Assert.assertEquals(0, work.canonicalSortCalls());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void overlappingRunsDeduplicateExactAuthoritiesBeforeCanonicalSort() {
		CandidateEmissionRealization template = fixture().get(0);
		int groupCount = 64, sharedCount = 80;
		List<CandidateRealizationSupportClause> shared = new ArrayList<>();
		for(int index = 0; index < sharedCount; index++)
			shared.add(new CandidateRealizationSupportClause(
				List.of(proof(String.format("shared-overlap-%03d", index))), List.of()));
		List<CandidateEmissionRealization> groups = new ArrayList<>();
		List<CandidateRealizationSupportClause> expected = new ArrayList<>(shared);
		for(int group = 0; group < groupCount; group++) {
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
			for(CandidateRealizationSupportClause clause : shared)
				clauses.add(group == 0 ? clause : copy(clause));
			CandidateRealizationSupportClause added = new CandidateRealizationSupportClause(
				List.of(proof(String.format("unique-overlap-%03d", group))), List.of());
			clauses.add(added);
			expected.add(added);
			groups.add(new CandidateEmissionRealization(template.key(), clauses));
		}
		expected.sort(java.util.Comparator.naturalOrder());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, groups).realizations().get(0);
			Assert.assertEquals(expected, union.supportClauses());
			for(int index = 0; index < expected.size(); index++)
				Assert.assertSame("keep the earliest full authority", expected.get(index),
					union.supportClauses().get(index));
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(expected.size(), work.realizationMergeUniqueClauses());
			Assert.assertEquals((groupCount - 1L) * sharedCount, work.realizationMergeDuplicateClauses());
			Assert.assertTrue("compare unique clauses, not every repeated run: " + work.canonicalComparisons(),
				work.canonicalComparisons() < expected.size() * 5L);
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void threeWayDescriptorTiePreservesStableAuthorityOrderAndExactDeduplication()
		throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause first =
			new CandidateRealizationSupportClause(List.of(proof("tie-first")), List.of());
		CandidateRealizationSupportClause second =
			new CandidateRealizationSupportClause(List.of(proof("tie-second")), List.of());
		CandidateRealizationSupportClause third =
			new CandidateRealizationSupportClause(List.of(proof("tie-third")), List.of());
		CandidateEmissionRealization firstGroup = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(first));
		CandidateEmissionRealization secondGroup = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(second));
		CandidateRealizationSupportClause duplicateFirst = copy(first);
		CandidateEmissionRealization thirdGroup = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(duplicateFirst, third));
		CandidateEmissionRealization descriptorSource = new CandidateEmissionRealization(
			template.key(), List.of(first, second));
		Object tiedDescriptor = descriptorSidecar(descriptorSource.supportClauses())
			.get(descriptorSource.supportClauses().indexOf(first));
		setDescriptorSidecar(firstGroup.supportClauses(), List.of(tiedDescriptor));
		setDescriptorSidecar(secondGroup.supportClauses(), List.of(tiedDescriptor));
		setDescriptorSidecar(thirdGroup.supportClauses(), List.of(tiedDescriptor, tiedDescriptor));

		CandidateEmissionRealization union = new CandidateEmissionFact(EMISSION, FType.ROW, null,
			List.of(firstGroup, secondGroup, thirdGroup)).realizations().get(0);
		Assert.assertEquals(List.of(first, second, third), union.supportClauses());
		Assert.assertSame("the earliest equal clause remains the exact authority object",
			first, union.supportClauses().get(0));
		Assert.assertSame("non-equal comparator ties retain stable group order",
			second, union.supportClauses().get(1));
		Assert.assertSame(third, union.supportClauses().get(2));
		Assert.assertEquals(List.of(tiedDescriptor, tiedDescriptor, tiedDescriptor),
			descriptorSidecar(union.supportClauses()));
	}


	@Test
	public void groupedComparatorResetsAcrossDeepPrefixesAndEarlyDifferences() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		PlacementProofKey shared = proof("dense-shared-😀-" + "segment".repeat(24));
		CandidateRealizationSupportClause sharedLeft = new CandidateRealizationSupportClause(
			List.of(shared, proof("dense-shared-tail")), List.of());
		CandidateRealizationSupportClause sharedAtDifferentPosition = new CandidateRealizationSupportClause(
			List.of(proof("dense-leading"), shared), List.of());
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>(List.of(
			new CandidateRealizationSupportClause(List.of(), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("dense-a")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("dense-aa")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("dense-|[, ]-é")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("dense-|[, ]-😀")), List.of()),
			sharedLeft, sharedAtDifferentPosition));
		CandidateRealizationReference deepA = CandidateRealizationReference.of(RULE,
			new CandidateEmissionRealization(PlacementIdentity.PlacementRealizationKey.sourceLineage(
				EMISSION, "deep-a"), List.of(new CandidateRealizationSupportClause(
					List.of(proof("deep-leaf-a")), List.of()))));
		CandidateRealizationReference deepAa = CandidateRealizationReference.of(RULE,
			new CandidateEmissionRealization(PlacementIdentity.PlacementRealizationKey.sourceLineage(
				EMISSION, "deep-aa"), List.of(new CandidateRealizationSupportClause(
					List.of(proof("deep-leaf-aa")), List.of()))));
		for(int depth = 0; depth < 80; depth++) {
			CandidateRealizationSupportClause left = new CandidateRealizationSupportClause(
				List.of(shared), List.of(CandidateRealizationInputBinding.direct(0, deepA)));
			CandidateRealizationSupportClause right = new CandidateRealizationSupportClause(
				List.of(shared), List.of(CandidateRealizationInputBinding.direct(0, deepAa)));
			deepA = CandidateRealizationReference.of(RULE,
				new CandidateEmissionRealization(template.key(), List.of(left)));
			deepAa = CandidateRealizationReference.of(RULE,
				new CandidateEmissionRealization(template.key(), List.of(right)));
		}
		CandidateRealizationSupportClause deepLeft = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, deepA)));
		CandidateRealizationSupportClause deepRight = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, deepAa)));
		clauses.add(deepLeft);
		clauses.add(deepRight);
		for(CandidateRealizationSupportClause left : clauses)
			for(CandidateRealizationSupportClause right : clauses)
				Assert.assertEquals(Integer.signum(legacySignature(left).compareTo(legacySignature(right))),
					Integer.signum(PlacementAnalysis.compareCanonicalOrdering(left, right)));
		Assert.assertEquals(Integer.signum(legacySignature(sharedLeft)
			.compareTo(legacySignature(sharedAtDifferentPosition))),
			Integer.signum(PlacementAnalysis.compareCanonicalOrdering(
				sharedLeft, sharedAtDifferentPosition)));

		List<CandidateEmissionRealization> groups = List.of(
			new CandidateEmissionRealization(template.key(), List.of(
				clauses.get(8), clauses.get(0), clauses.get(4))),
			new CandidateEmissionRealization(template.key(), List.of(
				clauses.get(1), clauses.get(7), copy(clauses.get(4)))),
			new CandidateEmissionRealization(template.key(), List.of(
				clauses.get(6), clauses.get(2), copy(clauses.get(0)))),
			new CandidateEmissionRealization(template.key(), List.of(
				clauses.get(5), clauses.get(3), copy(clauses.get(1)))));
		Map<CandidateRealizationSupportClause,Object> firstDescriptors = new java.util.LinkedHashMap<>();
		for(CandidateEmissionRealization group : groups) {
			List<?> descriptors = descriptorSidecar(group.supportClauses());
			for(int index = 0; index < group.supportClauses().size(); index++)
				firstDescriptors.putIfAbsent(group.supportClauses().get(index), descriptors.get(index));
		}
		List<CandidateRealizationSupportClause> expected = firstDescriptors.keySet().stream().sorted().toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, groups).realizations().get(0);
			Assert.assertEquals(expected.stream().map(CandidateRealizationCanonicalizationTest::legacySignature)
				.toList(), union.supportClauses().stream()
					.map(CandidateRealizationCanonicalizationTest::legacySignature).toList());
			List<?> unionDescriptors = descriptorSidecar(union.supportClauses());
			for(int index = 0; index < expected.size(); index++) {
				CandidateRealizationSupportClause authority = firstDescriptors.keySet().stream()
					.filter(expected.get(index)::equals).findFirst().orElseThrow();
				Assert.assertSame(authority, union.supportClauses().get(index));
				Assert.assertSame(firstDescriptors.get(authority), unionDescriptors.get(index));
			}
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(groups.size(), work.realizationMergeInputs());
			Assert.assertEquals(expected.size(), work.realizationMergeUniqueClauses());
			Assert.assertEquals(groups.stream().mapToInt(group -> group.supportClauses().size()).sum()
				- expected.size(), work.realizationMergeDuplicateClauses());
			Assert.assertEquals(1, work.canonicalSortCalls());
			Assert.assertEquals(expected.size(), work.canonicalSortElements());
			Assert.assertTrue(work.canonicalComparisons() > 0);
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void reusedCanonicalComparisonContextResetsAndReleasesDenseStacks() throws Exception {
		Class<?> textClass = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalText");
		var literal = textClass.getDeclaredMethod("literal", String.class);
		literal.setAccessible(true);
		var textConstructor = textClass.getDeclaredConstructor(List.class);
		textConstructor.setAccessible(true);
		Object empty = literal.invoke(null, "");
		Object a = literal.invoke(null, "a");
		Object aa = literal.invoke(null, "aa");
		Object z = literal.invoke(null, "z");
		Object equalFirst = literal.invoke(null, "é-😀-equal");
		Object equalSecond = literal.invoke(null, "é-😀-equal");
		Object shared = literal.invoke(null, "shared-😀-" + "segment".repeat(24));
		for(int depth = 0; depth < 80; depth++)
			shared = textConstructor.newInstance(List.of(shared));
		Object longLeft = textConstructor.newInstance(List.of(shared, "a"));
		Object longRight = textConstructor.newInstance(List.of(shared, "b"));

		Class<?> comparisonClass = Class.forName(
			PlacementAnalysis.class.getName() + "$CanonicalTextComparison");
		var comparisonConstructor = comparisonClass.getDeclaredConstructor();
		comparisonConstructor.setAccessible(true);
		Object comparison = comparisonConstructor.newInstance();
		var compare = comparisonClass.getDeclaredMethod("compare", textClass, textClass);
		compare.setAccessible(true);
		List<Object[]> sequence = List.of(
			new Object[] {longLeft, longRight, "shared-😀-" + "segment".repeat(24) + "a",
				"shared-😀-" + "segment".repeat(24) + "b"},
			new Object[] {z, a, "z", "a"},
			new Object[] {a, aa, "a", "aa"},
			new Object[] {aa, a, "aa", "a"},
			new Object[] {shared, shared, "shared-😀-" + "segment".repeat(24),
				"shared-😀-" + "segment".repeat(24)},
			new Object[] {longRight, longLeft, "shared-😀-" + "segment".repeat(24) + "b",
				"shared-😀-" + "segment".repeat(24) + "a"},
			new Object[] {equalFirst, equalSecond, "é-😀-equal", "é-😀-equal"},
			new Object[] {empty, a, "", "a"},
			new Object[] {literal.invoke(null, ""), empty, "", ""});
		for(Object[] step : sequence) {
			int actual = (int) compare.invoke(comparison, step[0], step[1]);
			int expected = ((String) step[2]).compareTo((String) step[3]);
			Assert.assertEquals(Integer.signum(expected), Integer.signum(actual));
			assertReleasedDenseCursorSlots(comparison, "leftCursor");
			assertReleasedDenseCursorSlots(comparison, "rightCursor");
		}
	}


	@Test
	public void equalDescriptorBucketKeepsUnequalClausesAndFirstExactAuthority() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("bucket-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("bucket-b")), List.of());
		CandidateEmissionRealization first = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(a));
		CandidateEmissionRealization second = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(b));
		CandidateEmissionRealization third = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(copy(a)));
		Object tiedDescriptor = descriptorSidecar(new CandidateEmissionRealization(
			template.key(), List.of(a, b)).supportClauses()).get(0);
		setDescriptorSidecar(first.supportClauses(), List.of(tiedDescriptor));
		setDescriptorSidecar(second.supportClauses(), List.of(tiedDescriptor));
		setDescriptorSidecar(third.supportClauses(), List.of(tiedDescriptor));

		CandidateEmissionRealization union = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(first, second, third)).realizations().get(0);
		Assert.assertEquals(2, union.supportClauses().size());
		Assert.assertSame(a, union.supportClauses().get(0));
		Assert.assertSame(b, union.supportClauses().get(1));
	}

	@Test
	public void unequalLengthSharedPrefixDescriptorsSkipPostSortEqualityComparison() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause shorter =
			new CandidateRealizationSupportClause(List.of(proof("length-prefix-short")), List.of());
		CandidateRealizationSupportClause longer =
			new CandidateRealizationSupportClause(List.of(proof("length-prefix-long")), List.of());
		CandidateEmissionRealization first = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(shorter));
		CandidateEmissionRealization second = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(longer));
		Class<?> textClass = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalText");
		var literal = textClass.getDeclaredMethod("literal", String.class);
		literal.setAccessible(true);
		Object prefix = literal.invoke(null, "shared-prefix");
		Object extended = literal.invoke(null, "shared-prefix-tail");
		setDescriptorSidecar(first.supportClauses(), List.of(prefix));
		setDescriptorSidecar(second.supportClauses(), List.of(extended));

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, List.of(first, second)).realizations().get(0);
			Assert.assertEquals(List.of(shorter, longer), union.supportClauses());
			Assert.assertSame(shorter, union.supportClauses().get(0));
			Assert.assertSame(longer, union.supportClauses().get(1));
			Assert.assertEquals("sorting compares the pair once; unequal lengths skip the grouping comparison",
				1, metrics.snapshot().canonicalComparisons());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void equalDescriptorBucketConsumesNewlyExposedTies() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("bucket-exposed-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("bucket-exposed-b")), List.of());
		CandidateRealizationSupportClause c =
			new CandidateRealizationSupportClause(List.of(proof("bucket-exposed-c")), List.of());
		CandidateEmissionRealization first = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(a, c));
		CandidateEmissionRealization second = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(b));
		CandidateEmissionRealization third = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(copy(a)));
		Object tiedDescriptor = descriptorSidecar(new CandidateEmissionRealization(
			template.key(), List.of(a, b)).supportClauses()).get(0);
		setDescriptorSidecar(first.supportClauses(), List.of(tiedDescriptor, tiedDescriptor));
		setDescriptorSidecar(second.supportClauses(), List.of(tiedDescriptor));
		setDescriptorSidecar(third.supportClauses(), List.of(tiedDescriptor));

		CandidateEmissionRealization union = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(first, second, third)).realizations().get(0);
		Assert.assertEquals("the complete tied bucket is consumed before advancing order",
			List.of(a, c, b), union.supportClauses());
		Assert.assertSame(a, union.supportClauses().get(0));
	}

	@Test
	public void mixedSingletonAndRepeatedGroupsAccountOnlyRepeatedClauses() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("mixed-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("mixed-b")), List.of());
		CandidateEmissionRealization first = new CandidateEmissionRealization(template.key(), List.of(a));
		CandidateEmissionRealization second = new CandidateEmissionRealization(template.key(), List.of(b));
		CandidateEmissionRealization third = new CandidateEmissionRealization(template.key(), List.of(copy(a)));
		CandidateEmissionRealization singleton = new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.durable(
				EMISSION, pool("mixed-singleton", 1413)), List.of(
				new CandidateRealizationSupportClause(List.of(proof("mixed-singleton-a")), List.of()),
				new CandidateRealizationSupportClause(List.of(proof("mixed-singleton-b")), List.of())));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionFact emission = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, List.of(first, singleton, second, third));
			Assert.assertTrue(emission.realizations().stream().anyMatch(value -> value == singleton));
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(4, work.realizationMergeInputs());
			Assert.assertEquals("singleton exact-key groups contribute no clause work",
				2, work.realizationMergeUniqueClauses());
			Assert.assertEquals(1, work.realizationMergeDuplicateClauses());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void threeWaySubsetReusesFirstOnlyWhenItAlreadyCoversUnion() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("three-subset-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("three-subset-b")), List.of());
		CandidateEmissionRealization superset = new CandidateEmissionRealization(template.key(), List.of(a, b));
		CandidateEmissionRealization onlyA = new CandidateEmissionRealization(template.key(), List.of(copy(a)));
		CandidateEmissionRealization onlyB = new CandidateEmissionRealization(template.key(), List.of(copy(b)));
		Assert.assertSame(superset, new CandidateEmissionFact(EMISSION, FType.ROW, null,
			List.of(superset, onlyA, onlyB)).realizations().get(0));
		CandidateEmissionRealization expanded = new CandidateEmissionFact(EMISSION, FType.ROW, null,
			List.of(onlyA, superset, onlyB)).realizations().get(0);
		Assert.assertNotSame(onlyA, expanded);
		Assert.assertSame("common support retains the earliest encountered authority", onlyA.supportClauses().get(0),
			expanded.supportClauses().stream().filter(a::equals).findFirst().orElseThrow());
	}

	@Test
	public void nativeWitnessExactnessAndRepeatedBindingsRemainDistinctAuthority() {
		DurableAnchorKey witness = pool("native-exact-dynamic", 1414);
		CandidateEmissionRealization template = CandidateEmissionRealization.nativeLineage(
			EMISSION, "native-exact-dynamic", witness,
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "native-owned")), List.of());
		PlacementProofKey nativeProof =
			new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "native-owned");
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(
			0, source("native-exact-dynamic", "input"));
		CandidateRealizationSupportClause exact = new CandidateRealizationSupportClause(
			List.of(nativeProof), List.of(binding), witness, true);
		CandidateRealizationSupportClause dynamic = new CandidateRealizationSupportClause(
			List.of(nativeProof), List.of(binding), witness, false);
		Assert.assertThrows("one physical realization must not mix exact and dynamic witness authority",
			IllegalArgumentException.class, () -> new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(
				new CandidateEmissionRealization(template.key(), List.of(exact)),
				new CandidateEmissionRealization(template.key(), List.of(dynamic)))));
		CandidateEmissionRealization exactUnion = new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(
			new CandidateEmissionRealization(template.key(), List.of(exact)),
			new CandidateEmissionRealization(template.key(), List.of(copy(exact))),
			new CandidateEmissionRealization(template.key(), List.of(copy(exact))))).realizations().get(0);
		Assert.assertEquals(1, exactUnion.supportClauses().size());
		Assert.assertSame(exact, exactUnion.supportClauses().get(0));
		Assert.assertEquals(List.of(binding), exactUnion.supportClauses().get(0).inputBindings());
	}

	@Test
	public void repeatedBindingPositionsRemainDistinctAcrossThreeWayUnion() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationReference source = source("binding-position", "same-source");
		CandidateRealizationSupportClause positionZero = new CandidateRealizationSupportClause(
			List.of(proof("binding-position")), List.of(CandidateRealizationInputBinding.direct(0, source)));
		CandidateRealizationSupportClause positionOne = new CandidateRealizationSupportClause(
			List.of(proof("binding-position")), List.of(CandidateRealizationInputBinding.direct(1, source)));
		CandidateEmissionRealization union = new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(
			new CandidateEmissionRealization(template.key(), List.of(positionZero)),
			new CandidateEmissionRealization(template.key(), List.of(positionOne)),
			new CandidateEmissionRealization(template.key(), List.of(copy(positionZero))))).realizations().get(0);
		Assert.assertEquals(2, union.supportClauses().size());
		Assert.assertSame(positionZero, union.supportClauses().stream()
			.filter(positionZero::equals).findFirst().orElseThrow());
		Assert.assertTrue(union.supportClauses().stream().anyMatch(positionOne::equals));
	}

	@Test
	public void publicSupportConstructorRejectsNonAdjacentExactDuplicate() {
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("nonadjacent-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("nonadjacent-b")), List.of());
		Assert.assertThrows(IllegalArgumentException.class, () -> new CandidateEmissionRealization(
			fixture().get(0).key(), List.of(a, b, copy(a))));
	}

	@Test
	public void descriptorRetentionIsLimitedToMultiClauseSupportLists() throws Exception {
		CandidateRealizationInputBinding bindingA = CandidateRealizationInputBinding.direct(
			0, source("generic-a", "a"));
		CandidateRealizationInputBinding bindingB = CandidateRealizationInputBinding.direct(
			1, source("generic-b", "b"));
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
			List.of(proof("generic-b"), proof("generic-a")), List.of(bindingB, bindingA));
		Assert.assertNull("proof lists are not descriptor consumers",
			descriptorSidecarOrNull(clause.proofDependencies()));
		Assert.assertNull("binding lists are not descriptor consumers",
			descriptorSidecarOrNull(clause.inputBindings()));

		CandidateEmissionRealization template = fixture().get(0);
		CandidateEmissionRealization support = new CandidateEmissionRealization(template.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof("support-b")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("support-a")), List.of())));
		Assert.assertEquals(2, descriptorSidecar(support.supportClauses()).size());
		CandidateEmissionFact alternatives = new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(
			support, CandidateEmissionRealization.nativeLineage(
				EMISSION, "generic-other-layout", List.of(), List.of())));
		Assert.assertNull("realization lists are not descriptor consumers",
			descriptorSidecarOrNull(alternatives.realizations()));

		CompiledHopKey writer = key("generic-writer");
		CompiledHopKey reader = key("generic-reader");
		PlacementState localState = new PlacementState(
			ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementEmissionState localEmission = new PlacementEmissionState(localState, false);
		CandidateRealizationReference sourceReference = CandidateRealizationReference.of(
			new CandidateRuleKey(writer, List.of()), CandidateEmissionRealization.local(localEmission));
		CandidateRealizationReference readerReference = CandidateRealizationReference.of(
			new CandidateRuleKey(reader, List.of(CandidateInputState.absentLocal())),
			CandidateEmissionRealization.local(localEmission));
		TransientPlacementCompatibility first = new TransientPlacementCompatibility(
			sourceReference, readerReference, CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal(), new TransientCompatibilityProof(
				null, null, List.of(proof("transient-a"))));
		TransientPlacementCompatibility second = new TransientPlacementCompatibility(
			sourceReference, readerReference, CandidateInputState.absentLocal(),
			CandidateInputState.absentLocal(), new TransientCompatibilityProof(
				null, null, List.of(proof("transient-b"))));
		ValueVersionKey sourceVersion = new ValueVersionKey("canonical-test", "generic",
			writer.controlRegion(), 0, VersionKind.ORDINARY, List.of());
		ValueVersionKey readerVersion = new ValueVersionKey("canonical-test", "generic",
			reader.controlRegion(), 1, VersionKind.ORDINARY, List.of());
		LogicalTransientInputFact transientFact = new LogicalTransientInputFact(
			writer, reader, 0, sourceVersion, readerVersion, List.of(second, first));
		Assert.assertNull("transient compatibility lists are not descriptor consumers",
			descriptorSidecarOrNull(transientFact.compatibility()));
	}

	@Test
	public void supportSublistOwnsRangesAndMissingSidesShareOneLocalRopeContext() throws Exception {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateEmissionRealization source = new CandidateEmissionRealization(template.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof("copy-a")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("copy-b")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("copy-c")), List.of())));
		CandidateEmissionRealization subset = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), source.supportClauses().subList(1, 3));
		Assert.assertFalse("a support subset must not retain its parent value array",
			backingValues(subset.supportClauses()).getClass().getName().contains("SubList"));
		Assert.assertFalse("a support subset must not retain its parent descriptor array",
			descriptorSidecar(subset.supportClauses()).getClass().getName().contains("SubList"));

		PlacementProofKey shared = proof("missing-shared");
		CandidateRealizationSupportClause leftClause = new CandidateRealizationSupportClause(
			List.of(shared, proof("missing-left")), List.of());
		CandidateRealizationSupportClause rightClause = new CandidateRealizationSupportClause(
			List.of(shared, proof("missing-right")), List.of());
		CandidateEmissionRealization left = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(leftClause));
		CandidateEmissionRealization right = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(rightClause));
		Assert.assertNull(descriptorSidecarOrNull(left.supportClauses()));
		Assert.assertNull(descriptorSidecarOrNull(right.supportClauses()));
		CandidateEmissionRealization union = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(left, right)).realizations().get(0);
		List<?> descriptors = descriptorSidecar(union.supportClauses());
		Assert.assertTrue("one merge-local context must share the common proof rope",
			sharesCanonicalTextDescendant(descriptors.get(0), descriptors.get(1)));
	}

	@Test
	public void canonicalizationPreservesTrustedMarkerAcrossConstructorBoundary() {
		CandidateRealizationSupportClause first =
			new CandidateRealizationSupportClause(List.of(proof("marker-a")), List.of());
		CandidateRealizationSupportClause second =
			new CandidateRealizationSupportClause(List.of(proof("marker-b")), List.of());
		CandidateEmissionRealization source = new CandidateEmissionRealization(
			fixture().get(0).key(), List.of(second, first));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization copy = new CandidateEmissionRealization(
				source.key(), source.supportClauses());
			Assert.assertEquals(source.supportClauses(), copy.supportClauses());
			Assert.assertEquals("a canonical list must remain trusted after its first sort",
				0, metrics.snapshot().canonicalSortCalls());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void trustedHelperPreservesCanonicalSubsetAndOneToOneMapOrder() {
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("trusted-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("trusted-b")), List.of());
		CandidateRealizationSupportClause c =
			new CandidateRealizationSupportClause(List.of(proof("trusted-c")), List.of());
		List<CandidateRealizationSupportClause> canonical = List.of(c, a, b).stream().sorted().toList();
		List<CandidateRealizationSupportClause> subset =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(canonical.subList(1, 3),
				"realization support clause");
		Assert.assertEquals(canonical.subList(1, 3), subset);
		List<CandidateRealizationSupportClause> mapped =
			PlacementAnalysis.sharedAlreadyCanonicalComparableList(
				canonical.stream().map(CandidateRealizationCanonicalizationTest::copy).toList(),
				"realization support clause");
		Assert.assertEquals(canonical, mapped);
		for(int index = 0; index < canonical.size(); index++)
			Assert.assertEquals(0, canonical.get(index).compareTo(mapped.get(index)));
	}

	@Test
	public void twoCanonicalClauseGroupsMergeLinearlyWithoutLosingOrAlternatives() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("linear-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("linear-b")), List.of());
		CandidateRealizationSupportClause c =
			new CandidateRealizationSupportClause(List.of(proof("linear-c")), List.of());
		CandidateRealizationSupportClause d =
			new CandidateRealizationSupportClause(List.of(proof("linear-d")), List.of());
		CandidateEmissionRealization left = new CandidateEmissionRealization(
			template.key(), List.of(c, a));
		CandidateEmissionRealization right = new CandidateEmissionRealization(
			template.key(), List.of(d, b, copy(c)));
		List<CandidateRealizationSupportClause> expected = List.of(a, b, c, d).stream().sorted().toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionFact emission = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, List.of(left, right));
			Assert.assertEquals("every distinct OR clause must survive in lexical order",
				expected, emission.realizations().get(0).supportClauses());
			Assert.assertEquals("the two sorted groups need no full union sort",
				0, metrics.snapshot().canonicalSortCalls());
			Assert.assertTrue("linear union comparisons remain observable",
				metrics.snapshot().canonicalComparisons() > 0);
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}

		CandidateEmissionRealization subset = new CandidateEmissionRealization(
			template.key(), List.of(copy(a), copy(c)));
		CandidateEmissionFact covered = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(left, subset));
		Assert.assertSame("an entirely covered second group must reuse the first authority",
			left, covered.realizations().get(0));
	}

	@Test
	public void unchangedTwoGroupSupportReusesFirstAuthorityWithoutCanonicalWork() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateEmissionRealization equalCopy = new CandidateEmissionRealization(
			template.key(), template.supportClauses().stream()
				.map(CandidateRealizationCanonicalizationTest::copy).toList());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionFact emission = new CandidateEmissionFact(
				EMISSION, FType.ROW, null, List.of(template, equalCopy));
			Assert.assertSame("an unchanged support set must keep its existing authority object",
				template, emission.realizations().get(0));
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals(0, work.canonicalSortCalls());
			Assert.assertEquals(0, work.canonicalOrderingKeys());
			Assert.assertEquals(0, work.canonicalComparisons());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void twoGroupProperSubsetKeepsFirstClausesAndAddsOnlyMissingSupport() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("subset-merge-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("subset-merge-b")), List.of());
		CandidateRealizationSupportClause c =
			new CandidateRealizationSupportClause(List.of(proof("subset-merge-c")), List.of());
		CandidateEmissionRealization subset = new CandidateEmissionRealization(template.key(), List.of(a, c));
		CandidateEmissionRealization superset = new CandidateEmissionRealization(
			template.key(), List.of(copy(a), b, copy(c)));
		CandidateEmissionFact emission = new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(subset, superset));
		Assert.assertEquals(List.of(a, b, c).stream().sorted().toList(),
			emission.realizations().get(0).supportClauses());
		Assert.assertSame("duplicate authority must continue to come from the first group", a,
			emission.realizations().get(0).supportClauses().stream()
				.filter(a::equals).findFirst().orElseThrow());
	}

	@Test
	public void boundaryComparatorExactlyMatchesLegacyLengthPrefixedUtf16Bytes() {
		List<String> values = List.of("", "a", "|", ":", "[x, y]", "123456789",
			"1234567890", "x".repeat(99), "x".repeat(100), "é", "😀", "a😀z");
		List<List<String>> fields = new ArrayList<>();
		for(String first : values)
			for(String second : values)
				fields.add(List.of(first, second, "tail"));
		for(List<String> left : fields)
			for(List<String> right : fields)
				Assert.assertEquals(left + " vs " + right,
					Integer.signum(legacyFields(left).compareTo(legacyFields(right))),
					Integer.signum(PlacementAnalysis.compareLengthPrefixedFieldSequences(left, right)));
	}

	@Test
	public void segmentedClauseAndRealizationOrderingExactlyMatchesMaterializedUtf16Text() {
		DurableAnchorKey pool = pool("segmented-😀|[, ]", 1240);
		CandidateEmissionRealization dynamic = CandidateEmissionRealization.nativeLineageDynamicLayout(
			EMISSION, "dynamic-|[, ]-😀", pool,
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
				OWNER, "dynamic-proof-|[, ]-😀")),
			List.of(CandidateRealizationInputBinding.direct(0, source("segmented", "😀"))));
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>(fixture().stream()
			.flatMap(realization -> realization.supportClauses().stream()).toList());
		clauses.add(dynamic.supportClauses().get(0));
		for(CandidateRealizationSupportClause left : clauses)
			for(CandidateRealizationSupportClause right : clauses)
				Assert.assertEquals(Integer.signum(left.normalizedSignature().compareTo(right.normalizedSignature())),
					Integer.signum(PlacementAnalysis.compareCanonicalOrdering(left, right)));

		List<CandidateEmissionRealization> realizations = new ArrayList<>(fixture());
		realizations.add(dynamic);
		CandidateEmissionRealization first = fixture().get(0);
		realizations.add(new CandidateEmissionRealization(first.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof("multi-9")), List.of()),
			new CandidateRealizationSupportClause(List.of(proof("multi-10")), List.of()))));
		for(CandidateEmissionRealization left : realizations)
			for(CandidateEmissionRealization right : realizations)
				Assert.assertEquals(Integer.signum(left.normalizedSignature().compareTo(right.normalizedSignature())),
					Integer.signum(PlacementAnalysis.compareCanonicalOrdering(left, right)));

		List<CandidateRealizationReference> references = realizations.stream()
			.map(realization -> CandidateRealizationReference.of(RULE, realization)).toList();
		for(CandidateRealizationReference left : references)
			for(CandidateRealizationReference right : references)
				Assert.assertEquals(Integer.signum(left.normalizedSignature().compareTo(right.normalizedSignature())),
					Integer.signum(left.compareTo(right)));
		for(CandidateEmissionRealization left : realizations)
			for(CandidateEmissionRealization right : realizations)
				Assert.assertEquals(Integer.signum(left.key().normalizedSignature()
					.compareTo(right.key().normalizedSignature())), Integer.signum(left.key().compareTo(right.key())));
		List<PlacementProofKey> proofs = clauses.stream()
			.flatMap(clause -> clause.proofDependencies().stream()).toList();
		for(PlacementProofKey left : proofs)
			for(PlacementProofKey right : proofs)
				Assert.assertEquals(Integer.signum(left.normalizedSignature().compareTo(right.normalizedSignature())),
					Integer.signum(left.compareTo(right)));
		List<CandidateRealizationInputBinding> bindings = clauses.stream()
			.flatMap(clause -> clause.inputBindings().stream()).toList();
		for(CandidateRealizationInputBinding left : bindings)
			for(CandidateRealizationInputBinding right : bindings)
				Assert.assertEquals(Integer.signum(left.normalizedSignature().compareTo(right.normalizedSignature())),
					Integer.signum(left.compareTo(right)));
	}

	@Test
	public void literalAndStructuralFieldsRetainExactUtf16OrderingAcrossLengthBoundaries() throws Exception {
		List<String> payloads = List.of("a", "123456789", "1234567890", "x".repeat(99),
			"x".repeat(100), "é", "😀", "\ud800", "\udc00", "\u0000", "|:[, ]");
		List<Object> values = new ArrayList<>();
		List<String> signatures = new ArrayList<>();
		Object context = canonicalTextContext();
		for(int index = 0; index < payloads.size(); index++) {
			String payload = payloads.get(index);
			PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.SHAPE,
				index % 2 == 0 ? null : OWNER, payload);
			CandidateEmissionRealization realization = CandidateEmissionRealization.nativeLineage(
				EMISSION, payload, List.of(proof), List.of());
			CandidateRealizationReference reference = CandidateRealizationReference.of(RULE, realization);
			DurableAnchorKey anchor = pool("field-" + payload, 1234);
			RelocationActionKey action = new RelocationActionKey(new ValueVersionKey(
				"canonical-test", "value-" + payload, OWNER.controlRegion(), 0, VersionKind.ORDINARY, List.of()),
				FED, anchor, "scope-" + payload, List.of(OWNER, key("consumer-" + payload)));
			assertCanonicalDescriptorText(action, action.normalizedSignature(), context);
			int position = new int[] {0, 9, 10, 99, 100, Integer.MAX_VALUE}[index % 6];
			CandidateRealizationInputBinding direct = CandidateRealizationInputBinding.direct(position, reference);
			CandidateRealizationInputBinding relocated = CandidateRealizationInputBinding.relocation(
				position, reference, action);
			values.addAll(List.of(proof, realization.key(), reference, direct, relocated, realization));
			signatures.addAll(List.of(proof.normalizedSignature(), realization.key().normalizedSignature(),
				reference.normalizedSignature(), direct.normalizedSignature(), relocated.normalizedSignature(),
				realization.normalizedSignature()));
		}
		CandidateRealizationSupportClause empty = new CandidateRealizationSupportClause(List.of(), List.of());
		values.add(empty);
		signatures.add(empty.normalizedSignature());
		for(int index = 0; index < values.size(); index++)
			assertCanonicalDescriptorText(values.get(index), signatures.get(index), context);
		for(int left = 0; left < values.size(); left++)
			for(int right = 0; right < values.size(); right++)
				Assert.assertEquals("literal/structural descriptor " + left + " vs " + right,
					Integer.signum(signatures.get(left).compareTo(signatures.get(right))),
					Integer.signum(PlacementAnalysis.compareCanonicalOrdering(values.get(left), values.get(right))));
	}

	@Test
	public void literalFieldDescriptorsKeepStringsAndStructuralChildIdentity() throws Exception {
		Object context = canonicalTextContext();
		PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.SHAPE, null, "literal-proof");
		List<?> proofPieces = canonicalTextPieces(canonicalDescriptor(proof, context));
		Assert.assertEquals("literal kind is not a one-piece rope", "SHAPE", proofPieces.get(1));
		Assert.assertEquals("null owner marker stays a literal", "-", proofPieces.get(3));
		Assert.assertEquals("authority text stays a literal", "literal-proof", proofPieces.get(5));
		CandidateRealizationReference reference = source("literal-fields", "literal-source");
		Object referenceText = canonicalDescriptor(reference, context);
		CandidateRealizationInputBinding direct = CandidateRealizationInputBinding.direct(10, reference);
		List<?> directPieces = canonicalTextPieces(canonicalDescriptor(direct, context));
		Assert.assertEquals("10", directPieces.get(1));
		Assert.assertSame("cached reference remains a shared structural child", referenceText, directPieces.get(3));
		Assert.assertEquals("DIRECT", directPieces.get(5));
		Assert.assertEquals("-", directPieces.get(7));
		RelocationActionKey action = fixture().get(1).supportClauses().get(0).inputBindings().get(1)
			.relocationAction();
		Object actionText = canonicalDescriptor(action, context);
		CandidateRealizationInputBinding relocated = CandidateRealizationInputBinding.relocation(99, reference, action);
		List<?> relocatedPieces = canonicalTextPieces(canonicalDescriptor(relocated, context));
		Assert.assertSame(referenceText, relocatedPieces.get(3));
		Assert.assertSame("cached action remains a shared structural child", actionText, relocatedPieces.get(7));
	}

	@Test
	public void literalFieldBuilderPreservesEmptyBytesAndRejectsUnsupportedTypes() throws Exception {
		Class<?> builderType = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalTextBuilder");
		var constructor = builderType.getDeclaredConstructor();
		constructor.setAccessible(true);
		var appendFields = builderType.getDeclaredMethod("appendFields", Object[].class);
		appendFields.setAccessible(true);
		var build = builderType.getDeclaredMethod("build");
		build.setAccessible(true);
		Class<?> textType = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalText");
		var literal = textType.getDeclaredMethod("literal", String.class);
		literal.setAccessible(true);
		for(String payload : List.of("", "|:[]", "123456789", "1234567890", "x".repeat(99),
			"x".repeat(100), "x".repeat(4095), "x".repeat(4096), "x".repeat(4097),
			"\ud800", "\udc00", "😀")) {
			Object shared = literal.invoke(null, payload);
			Object empty = literal.invoke(null, "");
			Object builder = constructor.newInstance();
			Object[] fields = {payload, shared, "", empty, "tail"};
			Assert.assertSame(builder, appendFields.invoke(builder, new Object[] {fields}));
			Object text = build.invoke(builder);
			assertCanonicalTextValue(text, legacyFields(List.of(payload, payload, "", "", "tail")));
			Assert.assertEquals("one prefix per field plus every nonempty value",
				payload.isEmpty() ? 6 : 8, canonicalTextPieces(text).size());
			if(!payload.isEmpty())
				Assert.assertSame("nonempty structural field is not flattened", shared, canonicalTextPieces(text).get(3));
		}
		Object emptyBuilder = constructor.newInstance();
		appendFields.invoke(emptyBuilder, new Object[] {new Object[0]});
		assertCanonicalTextValue(build.invoke(emptyBuilder), "");
		Object nullBuilder = constructor.newInstance();
		var nullFailure = Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,
			() -> appendFields.invoke(nullBuilder, new Object[] {new Object[] {null}}));
		Assert.assertTrue(nullFailure.getCause() instanceof NullPointerException);
		Object unsupportedBuilder = constructor.newInstance();
		var unsupportedFailure = Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,
			() -> appendFields.invoke(unsupportedBuilder, new Object[] {new Object[] {1}}));
		Assert.assertTrue(unsupportedFailure.getCause() instanceof IllegalArgumentException);
	}

	@Test
	public void fieldPrefixSharingPreservesPayloadIdentityAndDelimiter() throws Exception {
		Class<?> builderType = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalTextBuilder");
		var constructor = builderType.getDeclaredConstructor();
		constructor.setAccessible(true);
		var appendFields = builderType.getDeclaredMethod("appendFields", Object[].class);
		appendFields.setAccessible(true);
		var build = builderType.getDeclaredMethod("build");
		build.setAccessible(true);
		String first = new String("same bytes");
		String second = new String(first);
		Object left = constructor.newInstance(), right = constructor.newInstance();
		appendFields.invoke(left, new Object[] {new Object[] {first, second}});
		appendFields.invoke(right, new Object[] {new Object[] {second, first}});
		Object leftText = build.invoke(left), rightText = build.invoke(right);
		List<?> a = canonicalTextPieces(leftText), b = canonicalTextPieces(rightText);
		Assert.assertSame("first-field metadata is independent of payload authority", a.get(0), b.get(0));
		Assert.assertSame("subsequent-field metadata includes its delimiter", a.get(2), b.get(2));
		Assert.assertNotEquals(a.get(0), a.get(2));
		Assert.assertSame(first, a.get(1));
		Assert.assertSame(second, a.get(3));
		Assert.assertSame(second, b.get(1));
		Assert.assertSame(first, b.get(3));
		assertCanonicalTextValue(leftText, legacyFields(List.of(first, second)));
		assertCanonicalTextValue(rightText, legacyFields(List.of(second, first)));
	}

	private static Object canonicalTextContext() throws Exception {
		Class<?> type = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalTextContext");
		var constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static Object canonicalDescriptor(Object value, Object context) throws Exception {
		var method = value instanceof RelocationActionKey
			? PlacementAnalysis.class.getDeclaredMethod("canonicalRelocationActionOrderingText",
				RelocationActionKey.class, context.getClass())
			: PlacementAnalysis.class.getDeclaredMethod("canonicalOrderingKey", Object.class, context.getClass());
		method.setAccessible(true);
		return method.invoke(null, value, context);
	}

	private static List<?> canonicalTextPieces(Object text) throws Exception {
		Field pieces = text.getClass().getDeclaredField("pieces");
		pieces.setAccessible(true);
		return java.util.Arrays.asList((Object[]) pieces.get(text));
	}

	private static String flattenCanonicalText(Object text) throws Exception {
		StringBuilder result = new StringBuilder();
		for(Object piece : canonicalTextPieces(text))
			result.append(piece instanceof String literal ? literal : flattenCanonicalText(piece));
		return result.toString();
	}

	private static void assertCanonicalDescriptorText(Object value, String expected, Object context)
		throws Exception {
		assertCanonicalTextValue(canonicalDescriptor(value, context), expected);
	}

	private static void assertCanonicalTextValue(Object text, String expected) throws Exception {
		Assert.assertEquals("full UTF-16 descriptor text", expected, flattenCanonicalText(text));
		Field length = text.getClass().getDeclaredField("length");
		length.setAccessible(true);
		Assert.assertEquals("stored UTF-16 descriptor length", expected.length(), length.getInt(text));
	}

	@Test
	public void finalRealizationOrderingDoesNotVisitSupportForUnequalKeyText() throws Exception {
		List<String> identities = List.of("123456789", "1234567890", "é|[, ]", "😀|[, ]", "z");
		List<CandidateEmissionRealization> alternatives = new ArrayList<>();
		for(int index = identities.size() - 1; index >= 0; index--)
			alternatives.add(new CandidateEmissionRealization(
				PlacementIdentity.PlacementRealizationKey.sourceLineage(EMISSION, identities.get(index)), List.of(
					new CandidateRealizationSupportClause(List.of(proof("expensive-" + index + "-a")), List.of()),
					new CandidateRealizationSupportClause(List.of(proof("expensive-" + index + "-b")), List.of()))));
		List<String> expected = alternatives.stream().map(CandidateEmissionRealization::normalizedSignature)
			.sorted().toList();
		List<CandidateEmissionRealization> keyOnlyBaseline = alternatives.stream()
			.map(realization -> new CandidateEmissionRealization(realization.key(), List.of(), List.of())).toList();
		long keyOnlySerializations = finalOrderingSignatureSerializations(keyOnlyBaseline);
		PlacementIdentity.resetNormalizedSignatureCache();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			List<CandidateEmissionRealization> sorted =
				CandidateEmissionFact.canonicalRealizationList(alternatives);
			SearchSpaceMetrics.Snapshot work = metrics.snapshot();
			Assert.assertEquals("final ordering must inspect one key descriptor per input",
				alternatives.size(), work.canonicalOrderingKeys());
			Assert.assertEquals(1, work.canonicalSortCalls());
			Assert.assertTrue(work.canonicalComparisons() > 0);
			Assert.assertEquals("unequal key text must do no more serialization than key-only ordering",
				keyOnlySerializations, work.signatureSerializations());
			Assert.assertEquals(expected,
				sorted.stream().map(CandidateEmissionRealization::normalizedSignature).toList());
			Assert.assertNull("final realization lists remain value-only",
				descriptorSidecarOrNull(sorted));
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	@Test
	public void equalKeyTextFallsBackToCompleteSupportOrderingAndDuplicateValidation() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateEmissionRealization suffixA = new CandidateEmissionRealization(template.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof("suffix-a")), List.of())));
		CandidateEmissionRealization suffixB = new CandidateEmissionRealization(template.key(), List.of(
			new CandidateRealizationSupportClause(List.of(proof("suffix-b")), List.of())));
		List<CandidateEmissionRealization> sorted = CandidateEmissionFact.canonicalRealizationList(
			List.of(suffixB, suffixA));
		Assert.assertEquals(List.of(suffixA, suffixB).stream()
			.map(CandidateEmissionRealization::normalizedSignature).sorted().toList(), sorted.stream()
				.map(CandidateEmissionRealization::normalizedSignature).toList());
		Assert.assertSame("full suffix ordering retains the original authority object", suffixA,
			sorted.stream().filter(suffixA::equals).findFirst().orElseThrow());
		CandidateEmissionRealization equalCopy = new CandidateEmissionRealization(
			template.key(), suffixA.supportClauses());
		Assert.assertThrows(IllegalArgumentException.class, () ->
			CandidateEmissionFact.canonicalRealizationList(List.of(suffixB, suffixA, equalCopy)));
	}

	@Test
	public void finalRealizationOrderingMatchesLegacyAcrossPermutations() {
		List<CandidateEmissionRealization> alternatives = new ArrayList<>(List.of(
			new CandidateEmissionRealization(PlacementIdentity.PlacementRealizationKey.sourceLineage(
				EMISSION, "123456789"), List.of(new CandidateRealizationSupportClause(
					List.of(proof("legacy-final-z")), List.of()))),
			new CandidateEmissionRealization(PlacementIdentity.PlacementRealizationKey.sourceLineage(
				EMISSION, "1234567890"), List.of(new CandidateRealizationSupportClause(
					List.of(proof("legacy-final-a")), List.of()))),
			new CandidateEmissionRealization(PlacementIdentity.PlacementRealizationKey.sourceLineage(
				EMISSION, "é|[, ]😀"), List.of(new CandidateRealizationSupportClause(
					List.of(proof("legacy-final-unicode")), List.of()))),
			CandidateEmissionRealization.nativeLineage(EMISSION, "native-final-😀", List.of(), List.of())));
		verifyFinalOrderingPermutations(alternatives, 0);
	}

	@Test
	public void sharedNestedPrefixOrderingPreservesUtf16AndDuplicateContract() {
		PlacementProofKey shared = proof("shared-😀-" + "long".repeat(200));
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		for(String suffix : List.of("empty", "a", "aa", "é", "😀", "z"))
			clauses.add(new CandidateRealizationSupportClause(List.of(shared, proof(suffix)), List.of()));
		for(CandidateRealizationSupportClause left : clauses)
			for(CandidateRealizationSupportClause right : clauses)
				Assert.assertEquals(Integer.signum(legacySignature(left).compareTo(legacySignature(right))),
					Integer.signum(PlacementAnalysis.compareCanonicalOrdering(left, right)));
		Collections.reverse(clauses);
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			fixture().get(0).key(), clauses);
		Assert.assertEquals(clauses.stream().map(CandidateRealizationCanonicalizationTest::legacySignature)
			.sorted().toList(), realization.supportClauses().stream()
			.map(CandidateRealizationSupportClause::normalizedSignature).toList());
		List<CandidateRealizationSupportClause> duplicate = new ArrayList<>(clauses);
		duplicate.add(copy(clauses.get(0)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new CandidateEmissionRealization(fixture().get(0).key(), duplicate));
	}

	@Test
	public void normalizedSignatureCacheBudgetNeverChangesCanonicalBytes() {
		CandidateRealizationSupportClause clause = fixture().get(0).supportClauses().get(0);
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		try {
			String first = clause.normalizedSignature();
			String second = clause.normalizedSignature();
			Assert.assertEquals(legacySignature(clause), first);
			Assert.assertEquals(first, second);
			Assert.assertNotSame("budget exhaustion skips cache retention, not serialization", first, second);
			Assert.assertEquals(0, PlacementIdentity.normalizedSignatureCacheRetainedChars());
		}
		finally {
			PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		}
	}

	@Test
	public void hashCollisionsDoNotAliasDifferentProofs() throws Exception {
		CandidateRealizationSupportClause first = new CandidateRealizationSupportClause(
			List.of(proof("Aa")), List.of());
		CandidateRealizationSupportClause second = new CandidateRealizationSupportClause(
			List.of(proof("BB")), List.of());
		Assert.assertEquals(first.hashCode(), second.hashCode());
		Assert.assertNotEquals(first, second);
		clearSignatures();
		Assert.assertNotEquals(first.normalizedSignature(), second.normalizedSignature());
		Assert.assertEquals(legacySignature(first), first.normalizedSignature());
		Assert.assertEquals(legacySignature(second), second.normalizedSignature());

		CandidateEmissionRealization template = fixture().get(0);
		CandidateEmissionRealization descriptorSource = new CandidateEmissionRealization(
			template.key(), List.of(first, second));
		Object firstDescriptor = descriptorSidecar(descriptorSource.supportClauses())
			.get(descriptorSource.supportClauses().indexOf(first));
		Object secondDescriptor = descriptorSidecar(descriptorSource.supportClauses())
			.get(descriptorSource.supportClauses().indexOf(second));
		CandidateEmissionRealization firstAuthority = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(first));
		CandidateEmissionRealization collidingAuthority = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(second));
		CandidateEmissionRealization duplicateAuthority = CandidateEmissionRealization
			.fromAlreadyCanonicalSupportClauses(template.key(), List.of(copy(first)));
		setDescriptorSidecar(firstAuthority.supportClauses(), List.of(firstDescriptor));
		setDescriptorSidecar(collidingAuthority.supportClauses(), List.of(secondDescriptor));
		setDescriptorSidecar(duplicateAuthority.supportClauses(), List.of(firstDescriptor));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionRealization union = new CandidateEmissionFact(EMISSION, FType.ROW, null,
				List.of(firstAuthority, collidingAuthority, duplicateAuthority)).realizations().get(0);
			Assert.assertEquals(2, union.supportClauses().size());
			Assert.assertTrue(union.supportClauses().stream().anyMatch(clause -> clause == first));
			Assert.assertTrue(union.supportClauses().stream().anyMatch(clause -> clause == second));
			int firstIndex = union.supportClauses().indexOf(first);
			Assert.assertSame(first, union.supportClauses().get(firstIndex));
			Assert.assertSame(firstDescriptor, descriptorSidecar(union.supportClauses()).get(firstIndex));
			Assert.assertEquals(2, metrics.snapshot().realizationMergeUniqueClauses());
			Assert.assertEquals(1, metrics.snapshot().realizationMergeDuplicateClauses());
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
		}
	}

	@Test
	public void inputCopiesRemainImmutableAfterCacheWarmup() {
		List<PlacementProofKey> proofs = new ArrayList<>(List.of(proof("original")));
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>(
			List.of(CandidateRealizationInputBinding.direct(0, source("left", "zero"))));
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(proofs, bindings);
		CandidateRealizationSupportClause original = copy(clause);
		String expected = legacySignature(clause);
		int hash = clause.hashCode();
		clause.normalizedSignature();
		proofs.clear();
		bindings.clear();
		Assert.assertEquals(original, clause);
		Assert.assertEquals(hash, clause.hashCode());
		Assert.assertEquals(expected, clause.normalizedSignature());
		Assert.assertThrows(UnsupportedOperationException.class, () -> clause.inputBindings().clear());
	}

	@Test
	public void allSubsetsPermutationsAndDuplicatesPreserveCompleteReceiptRelation() throws Exception {
		List<CandidateEmissionRealization> alternatives = fixture();
		for(int mask = 1; mask < (1 << alternatives.size()); mask++) {
			List<CandidateEmissionRealization> subset = new ArrayList<>();
			for(int index = 0; index < alternatives.size(); index++)
				if((mask & (1 << index)) != 0)
					subset.add(alternatives.get(index));
			verifyPermutations(subset, 0);
		}
	}

	@Test
	public void mixedGroupPermutationsMatchLegacyRelationAndFirstAuthority() {
		CandidateEmissionRealization template = fixture().get(0);
		CandidateRealizationSupportClause a =
			new CandidateRealizationSupportClause(List.of(proof("oracle-a")), List.of());
		CandidateRealizationSupportClause b =
			new CandidateRealizationSupportClause(List.of(proof("oracle-b")), List.of());
		List<CandidateEmissionRealization> alternatives = new ArrayList<>(List.of(
			new CandidateEmissionRealization(template.key(), List.of(a)),
			new CandidateEmissionRealization(template.key(), List.of(b)),
			new CandidateEmissionRealization(template.key(), List.of(copy(a), copy(b))),
			CandidateEmissionRealization.durable(EMISSION, pool("oracle", 1420),
				List.of(proof("oracle-distinct")), List.of())));
		verifyLegacyOraclePermutations(alternatives, 0);
	}

	@Test
	public void singletonKeepsEveryOrClauseAndOriginalAuthority() {
		CandidateEmissionRealization first = fixture().get(0);
		CandidateRealizationSupportClause second = fixture().get(1).supportClauses().get(0);
		CandidateEmissionRealization combined = new CandidateEmissionRealization(first.key(),
			List.of(first.supportClauses().get(0), second));
		CandidateEmissionFact emission = new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of(combined));
		Assert.assertSame(combined, emission.realizations().get(0));
		Assert.assertEquals(2, emission.realizations().get(0).supportClauses().size());
		for(CandidateRealizationSupportClause clause : combined.supportClauses()) {
			CandidateSelectionReceipt receipt = new CandidateSelectionReceipt(
				RULE, emission, combined, clause, List.of());
			Assert.assertSame(combined, receipt.realization());
			Assert.assertSame(clause, receipt.supportClause());
		}
	}

	@Test
	public void singletonStillRejectsNullEmptyAndForeignEmissions() {
		Assert.assertThrows(NullPointerException.class,
			() -> new CandidateEmissionFact(EMISSION, FType.ROW, null, Collections.singletonList(null)));
		Assert.assertThrows(IllegalArgumentException.class,
			() -> new CandidateEmissionFact(EMISSION, FType.ROW, null, List.of()));
		PlacementEmissionState local = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		Assert.assertThrows(IllegalArgumentException.class, () -> new CandidateEmissionFact(
			EMISSION, FType.ROW, null, List.of(CandidateEmissionRealization.local(local))));
		Assert.assertThrows("foreign emission validation also applies after multi-key grouping",
			IllegalArgumentException.class, () -> new CandidateEmissionFact(
				EMISSION, FType.ROW, null, List.of(fixture().get(0),
					CandidateEmissionRealization.local(local), fixture().get(2))));
	}

	@Test
	public void compilerThreadsAndCacheResetKeepExactSignatures() throws Exception {
		List<CandidateEmissionRealization> alternatives = fixture();
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			List<Future<List<String>>> results = new ArrayList<>();
			for(int thread = 0; thread < 2; thread++)
				results.add(threads.submit(() -> {
					clearSignatures();
					List<String> signatures = alternatives.stream()
						.flatMap(realization -> realization.supportClauses().stream())
						.map(CandidateRealizationSupportClause::normalizedSignature).toList();
					clearSignatures();
					return signatures;
				}));
			List<String> expected = alternatives.stream().flatMap(realization -> realization.supportClauses().stream())
				.map(CandidateRealizationCanonicalizationTest::legacySignature).toList();
			for(Future<List<String>> result : results)
				Assert.assertEquals(expected, result.get());
		}
		finally {
			threads.shutdownNow();
		}
	}

	private static void verifyPermutations(List<CandidateEmissionRealization> alternatives, int position)
		throws Exception {
		if(position == alternatives.size()) {
			clearSignatures();
			verifyRelation(alternatives);
			List<CandidateEmissionRealization> duplicates = new ArrayList<>(alternatives);
			duplicates.addAll(alternatives);
			verifyRelation(duplicates);
			return;
		}
		for(int other = position; other < alternatives.size(); other++) {
			Collections.swap(alternatives, position, other);
			verifyPermutations(alternatives, position + 1);
			Collections.swap(alternatives, position, other);
		}
	}

	private static void verifyLegacyOraclePermutations(
		List<CandidateEmissionRealization> alternatives, int position) {
		if(position == alternatives.size()) {
			assertMatchesLegacyOracle(alternatives);
			return;
		}
		for(int other = position; other < alternatives.size(); other++) {
			Collections.swap(alternatives, position, other);
			verifyLegacyOraclePermutations(alternatives, position + 1);
			Collections.swap(alternatives, position, other);
		}
	}

	private static void verifyFinalOrderingPermutations(
		List<CandidateEmissionRealization> alternatives, int position) {
		if(position == alternatives.size()) {
			List<String> expected = alternatives.stream()
				.map(CandidateEmissionRealization::normalizedSignature).sorted().toList();
			List<CandidateEmissionRealization> actual =
				CandidateEmissionFact.canonicalRealizationList(alternatives);
			Assert.assertEquals(expected, actual.stream()
				.map(CandidateEmissionRealization::normalizedSignature).toList());
			for(CandidateEmissionRealization realization : alternatives)
				Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == realization));
			return;
		}
		for(int other = position; other < alternatives.size(); other++) {
			Collections.swap(alternatives, position, other);
			verifyFinalOrderingPermutations(alternatives, position + 1);
			Collections.swap(alternatives, position, other);
		}
	}

	private static long finalOrderingSignatureSerializations(
		List<CandidateEmissionRealization> alternatives) {
		PlacementIdentity.resetNormalizedSignatureCache();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try {
			CandidateEmissionFact.canonicalRealizationList(alternatives);
			return metrics.snapshot().signatureSerializations();
		}
		finally {
			PlacementIdentity.setActiveMetrics(null);
			PlacementIdentity.resetNormalizedSignatureCache();
		}
	}

	private static void assertMatchesLegacyOracle(List<CandidateEmissionRealization> alternatives) {
		Map<PlacementIdentity.PlacementRealizationKey,List<CandidateRealizationSupportClause>> expected =
			new java.util.LinkedHashMap<>();
		for(CandidateEmissionRealization realization : alternatives) {
			List<CandidateRealizationSupportClause> clauses = expected.computeIfAbsent(
				realization.key(), ignored -> new ArrayList<>());
			for(CandidateRealizationSupportClause clause : realization.supportClauses())
				if(clauses.stream().noneMatch(clause::equals))
					clauses.add(clause);
		}
		for(List<CandidateRealizationSupportClause> clauses : expected.values())
			clauses.sort(CandidateRealizationSupportClause::compareTo);

		CandidateEmissionFact emission = new CandidateEmissionFact(EMISSION, FType.ROW, null, alternatives);
		Assert.assertEquals(expected.size(), emission.realizations().size());
		for(CandidateEmissionRealization actual : emission.realizations()) {
			List<CandidateRealizationSupportClause> clauses = expected.get(actual.key());
			Assert.assertNotNull(clauses);
			Assert.assertEquals(clauses, actual.supportClauses());
			for(int index = 0; index < clauses.size(); index++)
				Assert.assertSame("legacy first authority must survive exact deduplication",
					clauses.get(index), actual.supportClauses().get(index));
		}
		List<String> expectedOrder = expected.entrySet().stream().map(entry ->
			entry.getKey().normalizedSignature() + "|support=" + entry.getValue().stream()
				.map(CandidateRealizationSupportClause::normalizedSignature).toList()).sorted().toList();
		Assert.assertEquals(expectedOrder, emission.realizations().stream()
			.map(CandidateEmissionRealization::normalizedSignature).toList());
	}

	private static void verifyRelation(List<CandidateEmissionRealization> alternatives) {
		Map<String,Set<String>> expected = new TreeMap<>();
		for(CandidateEmissionRealization realization : alternatives)
			for(CandidateRealizationSupportClause clause : realization.supportClauses())
				expected.computeIfAbsent(realization.key().normalizedSignature(), ignored -> new TreeSet<>())
					.add(legacySignature(clause));
		CandidateEmissionFact emission = new CandidateEmissionFact(EMISSION, FType.ROW, null, alternatives);
		Map<String,Set<String>> actual = new TreeMap<>();
		List<String> layoutOrder = new ArrayList<>();
		for(CandidateEmissionRealization realization : emission.realizations()) {
			String layout = realization.key().normalizedSignature();
			layoutOrder.add(layout);
			List<String> clauseOrder = new ArrayList<>();
			for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
				CandidateSelectionReceipt receipt = new CandidateSelectionReceipt(
					RULE, emission, realization, clause, List.of());
				Assert.assertSame(clause, receipt.supportClause());
				Assert.assertEquals(legacySignature(clause), clause.normalizedSignature());
				clauseOrder.add(clause.normalizedSignature());
				actual.computeIfAbsent(layout, ignored -> new TreeSet<>()).add(clause.normalizedSignature());
			}
			Assert.assertEquals(new ArrayList<>(expected.get(layout)), clauseOrder);
		}
		Assert.assertEquals("every exact AND clause and OR alternative must survive", expected, actual);
		Assert.assertEquals(new ArrayList<>(expected.keySet()), layoutOrder);
	}

	private static List<CandidateEmissionRealization> fixture() {
		DurableAnchorKey pool = pool("pool", 1234);
		CandidateRealizationReference left0 = source("left", "zero");
		CandidateRealizationReference left1 = source("left", "one");
		CandidateRealizationReference right0 = source("right", "zero");
		CandidateRealizationReference right1 = source("right", "one");
		RelocationActionKey action = new RelocationActionKey(new ValueVersionKey(
			"canonical-test", "right", OWNER.controlRegion(), 0, VersionKind.ORDINARY, List.of()),
			FED, pool, "scope", List.of(OWNER));
		List<CandidateRealizationInputBinding> direct = List.of(
			CandidateRealizationInputBinding.direct(0, left0), CandidateRealizationInputBinding.direct(1, right1));
		List<CandidateRealizationInputBinding> relocated = List.of(
			CandidateRealizationInputBinding.direct(0, left1),
			CandidateRealizationInputBinding.relocation(1, right0, action));
		return List.of(
			CandidateEmissionRealization.durable(EMISSION, pool, List.of(proof("route-a")), direct),
			CandidateEmissionRealization.durable(EMISSION, pool, List.of(proof("route-b")), relocated),
			CandidateEmissionRealization.durable(EMISSION, pool("other-port", 1235),
				List.of(proof("other-pool")), direct),
			CandidateEmissionRealization.nativeLineage(EMISSION, "native", pool,
				List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY, OWNER, "native-proof")), direct));
	}

	/** The original uncached serialization; never delegates to clause.normalizedSignature(). */
	private static String legacySignature(CandidateRealizationSupportClause clause) {
		return "proofs=" + clause.proofDependencies().stream().map(PlacementProofKey::normalizedSignature).toList()
			+ "|inputs=" + clause.inputBindings().stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList()
			+ "|nativePool=" + (clause.nativeWorkerPoolWitness() == null ? "-"
				: clause.nativeWorkerPoolWitness().normalizedSignature());
	}

	private static CandidateRealizationSupportClause copy(CandidateRealizationSupportClause clause) {
		return new CandidateRealizationSupportClause(clause.proofDependencies(), clause.inputBindings(),
			clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
	}

	private static String legacyFields(List<String> values) {
		StringBuilder encoded = new StringBuilder();
		for(int index = 0; index < values.size(); index++) {
			if(index > 0)
				encoded.append('|');
			String value = values.get(index);
			encoded.append(value.length()).append(':').append(value);
		}
		return encoded.toString();
	}

	private static PlacementProofKey proof(String text) {
		return new PlacementProofKey(PlacementProofKind.SHAPE, OWNER, text);
	}

	private static CandidateRealizationReference source(String name, String variant) {
		CandidateRuleKey rule = new CandidateRuleKey(key(name), List.of());
		return CandidateRealizationReference.of(rule, CandidateEmissionRealization.durable(
			EMISSION, pool(variant, 1234), List.of(), List.of()));
	}

	private static DurableAnchorKey pool(String id, int port) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:" + port, List.of(0L, 0L), List.of(8L, 2L))));
	}

	private static CompiledHopKey key(String name) {
		ControlRegionKey region = new ControlRegionKey("canonical-test", "main", List.of("root"), name, "compiled");
		return new CompiledHopKey("canonical-test", "main", name, "compiled", region, name, name);
	}

	private static List<?> descriptorSidecar(List<?> values) throws Exception {
		List<?> sidecar = descriptorSidecarOrNull(values);
		Assert.assertNotNull("canonical descriptor sidecar is missing", sidecar);
		return sidecar;
	}

	private static List<?> descriptorSidecarOrNull(List<?> values) throws Exception {
		Field field;
		try {
			field = values.getClass().getDeclaredField("orderingKeys");
		}
		catch(NoSuchFieldException ignored) {
			return null;
		}
		field.setAccessible(true);
		return (List<?>) field.get(values);
	}

	private static List<?> backingValues(List<?> values) throws Exception {
		Field field = values.getClass().getDeclaredField("values");
		field.setAccessible(true);
		return (List<?>) field.get(values);
	}

	private static boolean sharesCanonicalTextDescendant(Object left, Object right) throws Exception {
		Set<Object> leftDescendants = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Set<Object> rightDescendants = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		collectCanonicalTextDescendants(left, leftDescendants);
		collectCanonicalTextDescendants(right, rightDescendants);
		leftDescendants.remove(left);
		rightDescendants.remove(right);
		return leftDescendants.stream().anyMatch(rightDescendants::contains);
	}

	private static void collectCanonicalTextDescendants(Object value, Set<Object> result)
		throws Exception {
		if(value == null || !value.getClass().getSimpleName().equals("CanonicalText") || !result.add(value))
			return;
		Field pieces = value.getClass().getDeclaredField("pieces");
		pieces.setAccessible(true);
		for(Object piece : (Object[]) pieces.get(value))
			collectCanonicalTextDescendants(piece, result);
	}

	private static void setDescriptorSidecar(List<?> values, List<?> sidecar) throws Exception {
		Field field = values.getClass().getDeclaredField("orderingKeys");
		field.setAccessible(true);
		field.set(values, sidecar);
	}


	private static void assertReleasedDenseCursorSlots(Object comparison, String cursorFieldName)
		throws Exception {
		Field cursorField = comparison.getClass().getDeclaredField(cursorFieldName);
		cursorField.setAccessible(true);
		Object cursor = cursorField.get(comparison);
		Field nodesField = cursor.getClass().getDeclaredField("nodes");
		Field indicesField = cursor.getClass().getDeclaredField("indices");
		Field depthField = cursor.getClass().getDeclaredField("depth");
		nodesField.setAccessible(true);
		indicesField.setAccessible(true);
		depthField.setAccessible(true);
		Object[] nodes = (Object[]) nodesField.get(cursor);
		int[] indices = (int[]) indicesField.get(cursor);
		int depth = depthField.getInt(cursor);
		for(int index = 0; index < depth; index++)
			Assert.assertNotNull(nodes[index]);
		for(int index = depth; index < nodes.length; index++) {
			Assert.assertNull("released node slot " + index, nodes[index]);
			Assert.assertEquals("released index slot " + index, 0, indices[index]);
		}
	}


	private static void clearSignatures() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
	}
}
