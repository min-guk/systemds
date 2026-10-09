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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.CandidateSupportResult;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeProofProductPublicMemoTest {
	@Test
	public void repeatedExactProductQueryUsesPublicMemoWithoutRebuildingGraph() throws Exception {
		Object fixture = fixture();
		DurableAnchorKey pool = anchor("repeat-pool");
		Object seed = fixtureCall(fixture, "source", new Class<?>[] {String.class, DurableAnchorKey.class},
			"repeat-seed", pool);
		Object left = fixtureCall(fixture, "unary",
			new Class<?>[] {String.class, OpOp1.class, refClass(), boolean.class},
			"repeat-left", OpOp1.LOG, seed, false);
		Object right = fixtureCall(fixture, "unary",
			new Class<?>[] {String.class, OpOp1.class, refClass(), boolean.class},
			"repeat-right", OpOp1.EXP, seed, false);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		fixtureCall(fixture, "samePoolRealizations",
			new Class<?>[] {refClass(), List.class, DurableAnchorKey[].class},
			left, unary, new DurableAnchorKey[] {anchor("left-a"), anchor("left-b")});
		fixtureCall(fixture, "samePoolRealizations",
			new Class<?>[] {refClass(), List.class, DurableAnchorKey[].class},
			right, unary, new DurableAnchorKey[] {anchor("right-a"), anchor("right-b")});
		Object root = fixtureCall(fixture, "binary",
			new Class<?>[] {String.class, OpOp2.class, refClass(), refClass(), boolean.class},
			"repeat-root", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = (CandidateRealizationReference)fixtureCall(
			fixture, "reference", new Class<?>[] {refClass(), List.class}, root, binary);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = (NativePlacementContinuity)fixtureCall(fixture,
			"resolver", new Class<?>[] {SearchSpaceMetrics.class, int.class, long.class, long.class},
			metrics, 32, 64L, 1L << 20);

		CandidateSupportResult first = continuity.proveCandidateSupport(reference, pool);
		Assert.assertNotNull(first.supportProduct());
		Assert.assertEquals(4, first.proofs().size());
		long graphs = metrics.snapshot().proofGraphsBuilt();
		long serializations = metrics.snapshot().signatureSerializations();
		CandidateSupportResult second = continuity.proveCandidateSupport(reference, pool);

		Assert.assertSame("the public memo returns the exact immutable product list",
			first.proofs(), second.proofs());
		Assert.assertEquals(1, metrics.snapshot().memoHits());
		Assert.assertEquals(1, metrics.snapshot().memoMisses());
		Assert.assertEquals(graphs, metrics.snapshot().proofGraphsBuilt());
		Assert.assertEquals("memo admission and lookup must not serialize member proofs",
			serializations, metrics.snapshot().signatureSerializations());

		DurableAnchorKey otherSeed = new DurableAnchorKey("other-seed", pool.fType(), pool.partitions());
		CandidateSupportResult other = continuity.proveCandidateSupport(reference, otherSeed);
		Assert.assertNotSame("external seed authority is part of the public key",
			first.proofs(), other.proofs());
		Assert.assertEquals(2, metrics.snapshot().memoMisses());

		fixtureCall(fixture, "samePoolRealizations",
			new Class<?>[] {refClass(), List.class, DurableAnchorKey[].class},
			left, unary, new DurableAnchorKey[] {anchor("left-a"), anchor("left-changed")});
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> changedFacts = List.copyOf(
			(List<CandidateRuleFact>)field(fixture.getClass(), "candidates").get(fixture));
		NativePlacementContinuity revised = continuity.nextRevision(changedFacts);
		CandidateRealizationReference changedReference = (CandidateRealizationReference)fixtureCall(
			fixture, "reference", new Class<?>[] {refClass(), List.class}, root, binary);
		CandidateSupportResult warm = revised.proveCandidateSupport(changedReference, pool);
		NativePlacementContinuity coldResolver = (NativePlacementContinuity)fixtureCall(fixture,
			"resolver", new Class<?>[] {SearchSpaceMetrics.class, int.class, long.class},
			null, 0, 0L);
		CandidateSupportResult cold = coldResolver.proveCandidateSupport(changedReference, pool);
		Assert.assertNotSame("an axis authority revision cannot retain the old public product",
			first.proofs(), warm.proofs());
		Assert.assertEquals(cold.proofs(), warm.proofs());
		assertProofSourceAuthority(cold.proofs(), warm.proofs());
	}

	@Test
	public void compressedEstimatePreservesEveryExistingAdmissionBudget() throws Exception {
		List<NativeContinuityProof> product = proofProduct(2, 3, "bounded");
		Assert.assertEquals(6, product.size());
		long proofBytes = estimatedProofBytes(product);
		Assert.assertTrue(proofBytes > 0 && proofBytes < Long.MAX_VALUE);
		CandidateRealizationReference source = reference(owner("query-owner"), "query-source");
		Set<CompiledHopKey> footprint = identitySet(source.rule().parentOccurrence());
		long totalBytes = Math.addExact(proofBytes, 128L);

		NativePlacementContinuity proofTooSmall = continuity(8, 5, Long.MAX_VALUE);
		cache(proofTooSmall, source, anchor("query-seed"), product, footprint);
		Assert.assertTrue(proofMemo(proofTooSmall).isEmpty());

		NativePlacementContinuity byteTooSmall = continuity(8, 6, totalBytes - 1);
		cache(byteTooSmall, source, anchor("query-seed"), product, footprint);
		Assert.assertTrue(proofMemo(byteTooSmall).isEmpty());

		NativePlacementContinuity exactBoundary = continuity(1, 6, totalBytes);
		cache(exactBoundary, source, anchor("query-seed"), product, footprint);
		Assert.assertEquals(1, proofMemo(exactBoundary).size());
		Assert.assertEquals(6L, longField(exactBoundary, "memoRetainedProofs"));
		Assert.assertEquals(totalBytes, longField(exactBoundary, "memoRetainedEstimatedBytes"));

		List<NativeContinuityProof> oversized = proofProduct(257, 256, "oversized");
		Assert.assertEquals(65_792, oversized.size());
		NativePlacementContinuity logicalLimit = continuity(8, 65_536, Long.MAX_VALUE);
		cache(logicalLimit, source, anchor("query-seed"), oversized, footprint);
		Assert.assertTrue("compressed bytes do not widen the logical proof-count budget",
			proofMemo(logicalLimit).isEmpty());
	}

	@Test
	public void estimateScansCompressedAxesWithoutSerializingCartesianMembers() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			List<NativeContinuityProof> product = proofProduct(128, 128, "metadata-only");
			long serializations = metrics.snapshot().signatureSerializations();
			long estimate = estimatedProofBytes(product);
			Assert.assertTrue(estimate > 0 && estimate < Long.MAX_VALUE);
			Assert.assertEquals(16_384, product.size());
			Assert.assertEquals("the estimate reads only axis/list sizes",
				serializations, metrics.snapshot().signatureSerializations());
			for(Field retained : product.getClass().getDeclaredFields())
				Assert.assertFalse("a proof product must not retain a canonical member index",
					retained.getType().getSimpleName().contains("CanonicalProductIndex"));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}

		CompiledHopKey firstOwner = owner("same-owner");
		CompiledHopKey equalForeignOwner = owner("same-owner");
		Assert.assertEquals(firstOwner, equalForeignOwner);
		Assert.assertNotSame(firstOwner, equalForeignOwner);
		Object firstKey = publicQuery(reference(firstOwner, "same-source"), anchor("same-seed"));
		Object foreignKey = publicQuery(reference(equalForeignOwner, "same-source"), anchor("same-seed"));
		Assert.assertNotEquals("equal foreign owners cannot borrow public proof authority",
			firstKey, foreignKey);
	}

	private static void assertProofSourceAuthority(List<NativeContinuityProof> expected,
		List<NativeContinuityProof> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(int proof = 0; proof < expected.size(); proof++) {
			var left = expected.get(proof).immediateBindings();
			var right = actual.get(proof).immediateBindings();
			Assert.assertEquals(left.size(), right.size());
			for(int binding = 0; binding < left.size(); binding++) {
				Assert.assertEquals(left.get(binding).source(), right.get(binding).source());
				Assert.assertSame(left.get(binding).source().rule().parentOccurrence(),
					right.get(binding).source().rule().parentOccurrence());
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<NativeContinuityProof> proofProduct(int leftWidth, int rightWidth,
		String prefix) throws Exception {
		List<List<CandidateRealizationInputBinding>> axes = List.of(
			axis(owner(prefix + "-left-owner"), 0, leftWidth, prefix + "-left"),
			axis(owner(prefix + "-right-owner"), 1, rightWidth, prefix + "-right"));
		Class<?> templatesClass = nested("CandidateSupportTemplateProduct");
		Method create = templatesClass.getDeclaredMethod(
			"tryCreate", DurableAnchorKey.class, boolean.class, List.class);
		create.setAccessible(true);
		Object templates = create.invoke(null, anchor(prefix + "-output"), true, axes);
		Assert.assertNotNull(templates);
		Constructor<?> constructor = nested("NativeContinuityProofProduct").getDeclaredConstructor(
			DurableAnchorKey.class, templatesClass);
		constructor.setAccessible(true);
		return (List<NativeContinuityProof>)constructor.newInstance(
			anchor(prefix + "-seed"), templates);
	}

	private static List<CandidateRealizationInputBinding> axis(CompiledHopKey owner,
		int position, int width, String prefix) {
		List<CandidateRealizationInputBinding> result = new ArrayList<>(width);
		for(int option = 0; option < width; option++)
			result.add(CandidateRealizationInputBinding.direct(position,
				reference(owner, prefix + '-' + String.format(java.util.Locale.ROOT, "%06d", option))));
		result.sort(PlacementAnalysis.canonicalComparator());
		return List.copyOf(result);
	}

	private static void cache(NativePlacementContinuity continuity,
		CandidateRealizationReference source, DurableAnchorKey seed,
		List<NativeContinuityProof> proofs, Set<CompiledHopKey> footprint) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"cacheCompletedProofs", nested("PublicCandidateQueryKey"), nested("ComputedPublicProof"));
		method.setAccessible(true);
		method.invoke(continuity, publicQuery(source, seed), computed(proofs, footprint));
	}

	private static Object publicQuery(CandidateRealizationReference source,
		DurableAnchorKey seed) throws Exception {
		Constructor<?> constructor = nested("PublicCandidateQueryKey").getDeclaredConstructor(
			CandidateRealizationReference.class, DurableAnchorKey.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(source, seed, false);
	}

	private static Object computed(List<NativeContinuityProof> proofs,
		Set<CompiledHopKey> footprint) throws Exception {
		Constructor<?> constructor = nested("ComputedPublicProof").getDeclaredConstructor(List.class, Set.class);
		constructor.setAccessible(true);
		return constructor.newInstance(proofs, footprint);
	}

	private static long estimatedProofBytes(List<NativeContinuityProof> proofs) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod("estimatedProofBytes", List.class);
		method.setAccessible(true);
		return (long)method.invoke(null, proofs);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object,Object> proofMemo(NativePlacementContinuity continuity) throws Exception {
		return (Map<Object,Object>)field(NativePlacementContinuity.class,
			"completedProofMemo").get(continuity);
	}

	private static NativePlacementContinuity continuity(int entries, long proofs, long bytes) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of(),
			Set.of(), Map.of(), null, entries, proofs, bytes);
	}

	private static Object fixture() throws Exception {
		Constructor<?> constructor = fixtureClass().getDeclaredConstructor(FType.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.FULL);
	}

	private static Object fixtureCall(Object fixture, String name, Class<?>[] types,
		Object... arguments) throws Exception {
		Method method = fixture.getClass().getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(fixture, arguments);
	}

	private static Class<?> fixtureClass() throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuityTest.class.getName() + "$Fixture");
	}

	private static Class<?> refClass() throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuityTest.class.getName() + "$Ref");
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static Field field(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static long longField(Object value, String name) throws Exception {
		return field(value.getClass(), name).getLong(value);
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String lineage) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		return CandidateRealizationReference.of(rule,
			PlacementAnalysis.CandidateEmissionRealization.nativeLineage(
				emission, lineage, List.of(), List.of()));
	}

	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"proof-product-memo", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("proof-product-memo", "main", "call", "compiled",
			region, id, id);
	}

	private static DurableAnchorKey anchor(String id) {
		return new DurableAnchorKey(id, FType.FULL, List.of(
			new AnchorPartition("worker:8001", List.of(0L, 0L), List.of(8L, 2L))));
	}

	@SafeVarargs
	private static <T> Set<T> identitySet(T... values) {
		Set<T> result = Collections.newSetFromMap(new IdentityHashMap<>());
		Collections.addAll(result, values);
		return result;
	}
}
