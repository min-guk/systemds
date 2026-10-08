/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeSupportMaterializationOptimizationTest {
	@Test
	public void supportEntryCanonicalizesUtf16DelimiterAndPrefixTemplatesOnce() throws Exception {
		CandidateRealizationReference root = reference("root|[]=,\ud83d\ude80", "root-realization");
		List<Object> shuffled = new ArrayList<>(List.of(
			template(anchor("pool|[]=,\ud83d\ude80"), false, bindings(root, 2)),
			template(anchor("pool|[]=,\ud83d"), true, bindings(root, 1)),
			template(anchor("pool|[]=,\ud83d\ude80"), true, bindings(root, 0)),
			template(anchor("pool|[]=,\ud83d\ude80x"), true, bindings(root, 3))));
		Object entry = entry(root, shuffled, identitySet(root.rule().parentOccurrence()), 1_000L, true);

		List<Object> retained = entryTemplates(entry);
		Assert.assertNotSame(shuffled, retained);
		List<String> actual = new ArrayList<>();
		for(Object value : retained)
			actual.add(templateSuffix(value));
		List<String> expected = new ArrayList<>(actual);
		expected.sort(String::compareTo);
		Assert.assertEquals("memo admission owns one canonical UTF-16 template order", expected, actual);
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> retained.add(retained.get(0)));
	}

	@Test
	public void trustedSameRootLengthStaysTextLazyThroughAdmissionEstimate() throws Exception {
		PlacementIdentity.beginAnalysisScope(null);
		try {
			CandidateRealizationReference root = reference("lazy-root-\ud83d\ude80|[]=,", "lazy-realization");
			Object entry = entry(root,
				List.of(template(anchor("lazy-pool-\ud83d\ude80|[]=,"), false, bindings(root, 7))),
				identitySet(root.rule().parentOccurrence()), 1_000L, true);
			NativeContinuityProof proof = instantiate(entry, root, anchor("external-seed-\ud83d\ude80|[]=,")).get(0);
			assertNoRetainedProofText(proof);
			int trustedLength = signatureLength(proof);
			Assert.assertTrue(estimatedProofBytes(List.of(proof)) > 0);
			assertNoRetainedProofText(proof);

			String materialized = proof.normalizedSignature();
			Assert.assertEquals(materialized.length(), trustedLength);
			Assert.assertSame(materialized, proof.normalizedSignature());
			Assert.assertNotNull(field(NativeContinuityProof.class, "normalizedSignatureText").get(proof));
			Assert.assertNull(field(NativeContinuityProof.class, "canonicalOrderingSuffixText").get(proof));
			Assert.assertNull(field(NativeContinuityProof.class, "canonicalRangeBindingText").get(proof));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void reboundTemplatesSortThenRemoveOnlyProofEqualCollapses() throws Exception {
		CompiledHopKey owner = owner("rebind-owner");
		CandidateRealizationReference cachedRoot = reference(owner, "a-cached");
		CandidateRealizationReference requestedRoot = reference(owner, "z-requested");
		Object replaceable = template(anchor("same-pool"), true, bindings(cachedRoot, 0));
		Object alreadyRequested = template(anchor("same-pool"), true, bindings(requestedRoot, 0));
		Object entry = entry(cachedRoot, List.of(alreadyRequested, replaceable),
			identitySet(owner), 1_000L, true);

		List<NativeContinuityProof> rebound = instantiate(entry, requestedRoot, anchor("rebind-seed"));
		Assert.assertEquals("rebinding may collapse formerly distinct templates", 1, rebound.size());
		Assert.assertSame(requestedRoot, rebound.get(0).immediateBindings().get(0).source());
	}

	@Test
	public void stableTwoWayMergeMatchesFrozenDistinctThenSort() throws Exception {
		Random random = new Random(734921L);
		Comparator<NativeContinuityProof> comparator = proofComparator();
		for(int trial = 0; trial < 200; trial++) {
			List<NativeContinuityProof> exact = distinctProofs(random, random.nextInt(8), "exact");
			List<NativeContinuityProof> dynamic = distinctProofs(random, random.nextInt(8), "dynamic");
			exact.sort(comparator);
			dynamic.sort(comparator);
			CompiledHopKey exactOccurrence = owner("exact-occurrence-" + trial);
			CompiledHopKey dynamicOccurrence = owner("dynamic-occurrence-" + trial);
			Object merged = merge(computed(exact, identitySet(exactOccurrence)),
				computed(dynamic, identitySet(dynamicOccurrence)), dynamic);
			List<NativeContinuityProof> actual = computedProofs(merged);
			List<NativeContinuityProof> expected = Stream.concat(exact.stream(), dynamic.stream())
				.distinct().sorted(comparator).toList();
			Assert.assertEquals(expected, actual);
			Assert.assertEquals(expected.size(), actual.size());
			for(int index = 0; index < expected.size(); index++)
				Assert.assertSame("the old stream pipeline retains the first equal representative",
					expected.get(index), actual.get(index));
			assertContainsIdentity(computedOccurrences(merged), exactOccurrence);
			assertContainsIdentity(computedOccurrences(merged), dynamicOccurrence);
		}
	}

	@Test
	public void emptyDynamicProofsStillUnionTheCompleteQueryFootprint() throws Exception {
		NativeContinuityProof exactProof = proof("footprint", true, 0);
		CompiledHopKey exactOccurrence = owner("exact-footprint");
		CompiledHopKey dynamicOccurrence = owner("dynamic-footprint");
		Object merged = merge(computed(List.of(exactProof), identitySet(exactOccurrence)),
			computed(List.of(proof("filtered", false, 1)), identitySet(dynamicOccurrence)), List.of());
		Assert.assertEquals(1, computedProofs(merged).size());
		Assert.assertSame(exactProof, computedProofs(merged).get(0));
		assertContainsIdentity(computedOccurrences(merged), exactOccurrence);
		assertContainsIdentity(computedOccurrences(merged), dynamicOccurrence);
	}

	@Test
	public void alreadyDynamicWitnessHasNoSecondSupportQueryWitness() throws Exception {
		Object dynamic = witness(false);
		Object exact = witness(true);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"distinctDynamicPartitionWitness", nested("NativePoolWitness"));
		method.setAccessible(true);
		Assert.assertNull("an already-dynamic query must not repeat the identical support query",
			method.invoke(null, dynamic));
		Object derived = method.invoke(null, exact);
		Assert.assertNotNull(derived);
		Assert.assertNotSame(exact, derived);
	}

	@Test
	public void supportEntriesRetainOnlyCanonicalTemplatesAndScalarLengthMetadata() throws Exception {
		CandidateRealizationReference root = reference("retention-root", "retention-realization");
		List<CandidateRealizationInputBinding> retainedBindings = bindings(root, 0);
		Object template = template(anchor("retention-pool"), true, retainedBindings);
		Object entry = entry(root, List.of(template), identitySet(root.rule().parentOccurrence()), 1_000L, true);
		for(Object retained : List.of(template, entry))
			for(Field candidate : retained.getClass().getDeclaredFields()) {
				if(Modifier.isStatic(candidate.getModifiers()))
					continue;
				Assert.assertNotEquals(NormalizedText.class, candidate.getType());
				Assert.assertNotEquals(PlacementAnalysis.NormalizedTextContext.class, candidate.getType());
				Assert.assertFalse("support memo entries must not retain text-builder seed maps",
					Map.class.isAssignableFrom(candidate.getType()));
			}
		long legacyEstimate = 80L + 32L * retainedBindings.size();
		Method estimate = NativePlacementContinuity.class.getDeclaredMethod("estimatedSupportBytes", List.class);
		estimate.setAccessible(true);
		Assert.assertTrue("the trusted-length scalar must remain covered by cache admission",
			(long)estimate.invoke(null, List.of(template)) > legacyEstimate);
	}

	@Test
	public void supportMemoKeepsOriginalAccessOrderEvictionSemantics() throws Exception {
		String property = "sysds.fedplanner.continuitySupportMemo.maxEntries";
		String prior = System.getProperty(property);
		System.setProperty(property, "2");
		try {
			NativePlacementContinuity continuity = continuity();
			Object witness = witness(true);
			CandidateRealizationReference first = reference("evict-first", "first");
			CandidateRealizationReference second = reference("evict-second", "second");
			CandidateRealizationReference third = reference("evict-third", "third");
			Object firstQuery = supportQuery(first, witness);
			Object secondQuery = supportQuery(second, witness);
			Object thirdQuery = supportQuery(third, witness);
			cacheSupport(continuity, firstQuery, entry(first,
				List.of(template(anchor("evict-pool-first"), true, bindings(first, 0))),
				identitySet(first.rule().parentOccurrence()), 128L, true));
			cacheSupport(continuity, secondQuery, entry(second,
				List.of(template(anchor("evict-pool-second"), true, bindings(second, 0))),
				identitySet(second.rule().parentOccurrence()), 128L, true));
			Map<?,?> memo = supportMemo(continuity);
			Assert.assertNotNull(memo.get(firstQuery));
			cacheSupport(continuity, thirdQuery, entry(third,
				List.of(template(anchor("evict-pool-third"), true, bindings(third, 0))),
				identitySet(third.rule().parentOccurrence()), 128L, true));
			Assert.assertTrue(memo.containsKey(firstQuery));
			Assert.assertFalse("the untouched oldest support entry must still be evicted",
				memo.containsKey(secondQuery));
			Assert.assertTrue(memo.containsKey(thirdQuery));
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	private static List<NativeContinuityProof> distinctProofs(Random random, int count, String side) {
		LinkedHashSet<NativeContinuityProof> distinct = new LinkedHashSet<>();
		while(distinct.size() < count) {
			int value = random.nextInt(12);
			distinct.add(proof("merge-" + value, (value & 1) == 0, value % 4));
		}
		List<NativeContinuityProof> proofs = new ArrayList<>(distinct);
		if(side.equals("dynamic") && random.nextBoolean())
			Collections.reverse(proofs);
		return proofs;
	}

	private static NativeContinuityProof proof(String id, boolean exact, int position) {
		CandidateRealizationReference reference = reference("owner-" + id, "realization-" + id);
		return new NativeContinuityProof(anchor("seed-" + id), anchor("pool-" + id), exact,
			bindings(reference, position));
	}

	@SuppressWarnings("unchecked")
	private static List<NativeContinuityProof> instantiate(Object entry,
		CandidateRealizationReference source, DurableAnchorKey seed) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("instantiateSupportTemplates",
			nested("SupportMemoEntry"), CandidateRealizationReference.class, DurableAnchorKey.class);
		method.setAccessible(true);
		return (List<NativeContinuityProof>)method.invoke(continuity(), entry, source, seed);
	}

	private static Object template(DurableAnchorKey pool, boolean exact,
		List<CandidateRealizationInputBinding> bindings) throws Exception {
		Constructor<?> constructor = nested("CandidateSupportTemplate").getDeclaredConstructor(
			DurableAnchorKey.class, boolean.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(pool, exact, bindings);
	}

	private static Object entry(CandidateRealizationReference root, List<?> templates,
		Set<CompiledHopKey> occurrences, long estimatedBytes, boolean rootIndependent) throws Exception {
		Constructor<?> constructor = nested("SupportMemoEntry").getDeclaredConstructor(
			CandidateRealizationReference.class, List.class, Set.class, long.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(root, templates, occurrences, estimatedBytes, rootIndependent);
	}

	@SuppressWarnings("unchecked")
	private static List<Object> entryTemplates(Object entry) throws Exception {
		Method method = entry.getClass().getDeclaredMethod("templates");
		method.setAccessible(true);
		return (List<Object>)method.invoke(entry);
	}

	private static String templateSuffix(Object template) throws Exception {
		Method output = template.getClass().getDeclaredMethod("outputWorkerPoolWitness");
		Method exact = template.getClass().getDeclaredMethod("exactPartitionRanges");
		Method bindings = template.getClass().getDeclaredMethod("immediateBindings");
		output.setAccessible(true);
		exact.setAccessible(true);
		bindings.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<CandidateRealizationInputBinding> values =
			(List<CandidateRealizationInputBinding>)bindings.invoke(template);
		return ((DurableAnchorKey)output.invoke(template)).normalizedSignature()
			+ "|partitionRanges=" + ((boolean)exact.invoke(template) ? "exact" : "dynamic")
			+ "|bindings=" + values.stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList();
	}

	private static Object computed(List<NativeContinuityProof> proofs,
		Set<CompiledHopKey> occurrences) throws Exception {
		Constructor<?> constructor = nested("ComputedPublicProof").getDeclaredConstructor(List.class, Set.class);
		constructor.setAccessible(true);
		return constructor.newInstance(proofs, occurrences);
	}

	private static Object supportQuery(CandidateRealizationReference source, Object witness)
		throws Exception {
		Constructor<?> constructor = nested("CandidateSupportQueryKey").getDeclaredConstructor(
			CandidateRealizationReference.class, int.class, nested("NativePoolWitness"),
			boolean.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(source, -1, witness, true, false);
	}

	private static void cacheSupport(NativePlacementContinuity continuity, Object query, Object entry)
		throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("cacheCompletedSupports",
			nested("CandidateSupportQueryKey"), nested("SupportMemoEntry"));
		method.setAccessible(true);
		method.invoke(continuity, query, entry);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> supportMemo(NativePlacementContinuity continuity) throws Exception {
		return (Map<Object,Object>)field(NativePlacementContinuity.class,
			"completedSupportMemo").get(continuity);
	}

	private static Object merge(Object exact, Object dynamic,
		List<NativeContinuityProof> filteredDynamic) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"mergeExactAndDynamicAlternatives", nested("ComputedPublicProof"),
			nested("ComputedPublicProof"), List.class);
		method.setAccessible(true);
		return method.invoke(null, exact, dynamic, filteredDynamic);
	}

	@SuppressWarnings("unchecked")
	private static List<NativeContinuityProof> computedProofs(Object computed) throws Exception {
		Method method = computed.getClass().getDeclaredMethod("proofs");
		method.setAccessible(true);
		return (List<NativeContinuityProof>)method.invoke(computed);
	}

	@SuppressWarnings("unchecked")
	private static Set<CompiledHopKey> computedOccurrences(Object computed) throws Exception {
		Method method = computed.getClass().getDeclaredMethod("occurrences");
		method.setAccessible(true);
		return (Set<CompiledHopKey>)method.invoke(computed);
	}

	@SuppressWarnings("unchecked")
	private static Comparator<NativeContinuityProof> proofComparator() throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("nativeProofSignatureComparator");
		method.setAccessible(true);
		return (Comparator<NativeContinuityProof>)method.invoke(null);
	}

	private static long estimatedProofBytes(List<NativeContinuityProof> proofs) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("estimatedProofBytes", List.class);
		method.setAccessible(true);
		return (long)method.invoke(null, proofs);
	}

	private static int signatureLength(NativeContinuityProof proof) throws Exception {
		Method method = NativeContinuityProof.class.getDeclaredMethod("normalizedSignatureLength");
		method.setAccessible(true);
		return (int)method.invoke(proof);
	}

	private static void assertNoRetainedProofText(NativeContinuityProof proof) throws Exception {
		Assert.assertNull(field(NativeContinuityProof.class, "normalizedSignature").get(proof));
		Assert.assertNull(field(NativeContinuityProof.class, "normalizedSignatureText").get(proof));
		Assert.assertNull(field(NativeContinuityProof.class, "canonicalOrderingSuffixText").get(proof));
		Assert.assertNull(field(NativeContinuityProof.class, "canonicalRangeBindingText").get(proof));
	}

	private static Field field(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static Object witness(boolean exact) throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.ROW, List.of("worker:8001"), List.of(), exact);
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + "$" + name);
	}

	private static NativePlacementContinuity continuity() {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of());
	}

	private static CandidateRealizationReference reference(String ownerId, String lineage) {
		return reference(owner(ownerId), lineage);
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String lineage) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.nativeLineage(emission, lineage, List.of(), List.of()));
	}

	private static List<CandidateRealizationInputBinding> bindings(
		CandidateRealizationReference reference, int position) {
		return List.of(CandidateRealizationInputBinding.direct(position, reference));
	}

	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey("support-materialization", "main",
			List.of("root|[]=,\ud83d\ude80"), "call", "compiled");
		return new CompiledHopKey("support-materialization", "main", "call", "compiled",
			region, id, "origin|[]=,\ud83d\ude80");
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(4L, 2L))));
	}

	@SafeVarargs
	private static <T> Set<T> identitySet(T... values) {
		Set<T> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, values);
		return result;
	}

	private static void assertContainsIdentity(Set<CompiledHopKey> set, CompiledHopKey expected) {
		Assert.assertTrue(set.stream().anyMatch(value -> value == expected));
	}
}
