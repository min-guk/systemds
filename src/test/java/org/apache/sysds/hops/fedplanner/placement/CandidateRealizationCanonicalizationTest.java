/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

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

	private static void clearSignatures() throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
	}
}
