/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
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

public class NativePlacementGroundingAndSignatureTest {
	private static final ControlRegionKey REGION = new ControlRegionKey(
		"native-r18", "main", List.of("root"), "root", "compiled");

	@Test
	public void normalizedSignatureMatchesLegacyFormattingAndCachesExactString() {
		DurableAnchorKey seed = anchor("seed|,[😀]", 4);
		DurableAnchorKey output = anchor("out=é, ] |", 4);
		NativeContinuityProof empty = new NativeContinuityProof(seed, output, true, List.of());
		assertLegacySignature(empty);

		List<CandidateRealizationInputBinding> bindings = List.of(
			CandidateRealizationInputBinding.direct(0, source("α|[,]")),
			CandidateRealizationInputBinding.direct(1, source("β=😀")));
		NativeContinuityProof multiple = new NativeContinuityProof(seed, output, false, bindings);
		assertLegacySignature(multiple);
		NativeContinuityProof one = new NativeContinuityProof(
			anchor("single:" + "長|,".repeat(512), 4), output, true,
			List.of(CandidateRealizationInputBinding.direct(0,
				source("binding:" + "é😀[]".repeat(512)))));
		assertLegacySignature(one);
		Assert.assertTrue(multiple.normalizedSignature().contains("|partitionRanges=dynamic|bindings=["));
		Assert.assertTrue(empty.normalizedSignature().endsWith("|partitionRanges=exact|bindings=[]"));
	}

	@Test
	public void dynamicOverlayPreservesDefaultsBeforeAndAfterItAndSyntheticGroundFirst() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		DurableAnchorKey anchor = anchor("overlay", 4);
		Object witness = nativeWitness(continuity, anchor);
		CompiledHopKey owner = key("overlay-owner");
		CompiledHopKey firstDependency = key("default-before");
		CompiledHopKey dynamicDependency = key("dynamic");
		CompiledHopKey lastDependency = key("default-after");
		CandidateRealizationReference firstReference = source("row-first");
		CandidateRealizationReference dynamicReference = source("row-dynamic");
		CandidateRealizationReference lastReference = source("row-last");
		CandidateRealizationReference firstSource = source("source-first");
		CandidateRealizationReference dynamicSource = source("source-dynamic");
		CandidateRealizationReference lastSource = source("source-last");
		CandidateRealizationReference overlaidSource = source(dynamicDependency,"source-overlaid");
		Object firstRow = topologyRow(firstReference,
			List.of(skeleton(firstDependency, firstSource, 11, witness)), witness);
		Object dynamicRow = topologyRow(dynamicReference,
			List.of(skeleton(dynamicDependency, dynamicSource, 12, witness)), witness);
		Object lastRow = topologyRow(lastReference,
			List.of(skeleton(lastDependency, lastSource, 13, witness)), witness);
		List<Object> rows = List.of(firstRow, dynamicRow, lastRow);
		int pinnedHandle = 29;
		installTopology(continuity, owner, witness, rows, pinnedHandle);
		Object fixed = fixedBoundary(dynamicDependency, overlaidSource, 17);

		List<?> full = candidateAlternatives(
			continuity, owner, null, 0, witness, fixed);
		Assert.assertEquals(4, full.size());
		Assert.assertNull("the synthetic direct ground remains first", field(full.get(0), "realization"));
		Assert.assertSame(field(firstRow, "defaultAlternative"), full.get(1));
		Assert.assertNotSame(field(dynamicRow, "defaultAlternative"), full.get(2));
		Assert.assertSame(field(lastRow, "defaultAlternative"), full.get(3));

		List<?> pinned = candidateAlternatives(
			continuity, owner, firstReference, pinnedHandle, witness, fixed);
		Assert.assertEquals(3, pinned.size());
		Assert.assertSame(field(firstRow, "defaultAlternative"), pinned.get(0));
		Assert.assertNotSame(field(dynamicRow, "defaultAlternative"), pinned.get(1));
		Assert.assertSame(field(lastRow, "defaultAlternative"), pinned.get(2));
		Assert.assertEquals(0, metrics.snapshot().topologyOverlayRowsCollapsed());
	}

	@Test
	public void unrelatedPinsShareImmutableDefaultAlternativeLists() throws Exception {
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of());
		Object witness = nativeWitness(continuity, anchor("default-list", 4));
		CompiledHopKey owner = key("default-list-owner");
		CandidateRealizationReference firstReference = source("default-list-first");
		CandidateRealizationReference secondReference = source("default-list-second");
		Object first = topologyRow(firstReference,
			List.of(skeleton(key("default-list-dependency-first"), source("first-source"), 11, witness)),
			witness);
		Object second = topologyRow(secondReference,
			List.of(skeleton(key("default-list-dependency-second"), source("second-source"), 12, witness)),
			witness);
		List<Object> rows = List.of(first, second);
		installTopology(continuity, owner, witness, rows, false, Map.of(31, rows));
		CandidateRealizationReference firstFixedSource = source("unrelated-first-source");
		CandidateRealizationReference secondFixedSource = source("unrelated-second-source");
		CompiledHopKey firstRoot = firstFixedSource.rule().parentOccurrence();
		CompiledHopKey secondRoot = secondFixedSource.rule().parentOccurrence();
		Object firstFixed = fixedBoundary(firstRoot, firstFixedSource, 21);
		Object secondFixed = fixedBoundary(secondRoot, secondFixedSource, 22);

		List<?> unpinned = candidateAlternatives(continuity, owner, null, 0, witness,
			firstFixed);
		List<?> again = candidateAlternatives(continuity, owner, null, 0, witness,
			secondFixed);
		Assert.assertEquals(2, unpinned.size());
		Assert.assertSame(field(first, "defaultAlternative"), unpinned.get(0));
		Assert.assertSame(field(second, "defaultAlternative"), unpinned.get(1));
		Assert.assertSame("unrelated query pins reuse the enclosing canonical list", unpinned, again);
		Assert.assertThrows(UnsupportedOperationException.class, unpinned::clear);

		List<?> pinned = candidateAlternatives(continuity, owner, firstReference, 31, witness,
			firstFixed);
		List<?> pinnedAgain = candidateAlternatives(continuity, owner, firstReference, 31, witness,
			secondFixed);
		Assert.assertEquals(unpinned, pinned);
		Assert.assertSame("handle-filtered default lists are also immutable shared authority", pinned, pinnedAgain);
		Assert.assertThrows(UnsupportedOperationException.class, pinned::clear);
		Assert.assertTrue("a missing pinned row does not borrow unrelated default authority",
			candidateAlternatives(continuity, owner, firstReference, 99, witness,
					firstFixed).isEmpty());
	}

	private static void assertLegacySignature(NativeContinuityProof proof) {
		String expected = proof.externalSeed().normalizedSignature() + "|outputPool="
			+ proof.outputWorkerPoolWitness().normalizedSignature() + "|partitionRanges="
			+ (proof.exactPartitionRanges() ? "exact" : "dynamic") + "|bindings="
			+ proof.immediateBindings().stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList();
		String first = proof.normalizedSignature();
		Assert.assertEquals(expected, first);
		Assert.assertSame("the lazily materialized signature is cached by identity",
			first, proof.normalizedSignature());
	}

	@SuppressWarnings("unchecked")
	private static void installTopology(NativePlacementContinuity continuity,
		CompiledHopKey owner, Object witness, List<Object> rows, int pinnedHandle) throws Exception {
		installTopology(continuity, owner, witness, rows, true, Map.of(pinnedHandle, rows));
	}

	@SuppressWarnings("unchecked")
	private static void installTopology(NativePlacementContinuity continuity,
		CompiledHopKey owner, Object witness, List<Object> rows, boolean nodeDirectGround,
		Map<Integer,List<Object>> rowsByHandle) throws Exception {
		Class<?> topologyType = nested("CandidateTopology");
		Constructor<?> topologyConstructor = topologyType.getDeclaredConstructor(
			boolean.class, boolean.class, List.class, Map.class);
		topologyConstructor.setAccessible(true);
		Object topology = topologyConstructor.newInstance(true, nodeDirectGround, rows, rowsByHandle);
		Class<?> keyType = nested("CandidateTopologyKey");
		Constructor<?> keyConstructor = keyType.getDeclaredConstructor(
			CompiledHopKey.class, nested("NativePoolWitness"));
		keyConstructor.setAccessible(true);
		Object topologyKey = keyConstructor.newInstance(owner, witness);
		Method cache = NativePlacementContinuity.class.getDeclaredMethod("cacheTopology",
			keyType, topologyType);
		cache.setAccessible(true);
		Assert.assertEquals(true, cache.invoke(continuity,topologyKey,topology));
	}

	private static Object topologyRow(CandidateRealizationReference reference,
		List<Object> skeletons, Object witness) throws Exception {
		Method create = nested("CandidateTopologyRow").getDeclaredMethod("create",
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		create.setAccessible(true);
		return create.invoke(null, reference, skeletons, false, witness);
	}

	private static Object skeleton(CompiledHopKey key, CandidateRealizationReference pinned,
		int handle, Object witness) throws Exception {
		Constructor<?> constructor = nested("CandidateDependencySkeleton").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(key, pinned, handle, witness, 0);
	}

	@SuppressWarnings("unchecked")
	private static List<?> candidateAlternatives(NativePlacementContinuity continuity,
		CompiledHopKey owner, CandidateRealizationReference pinned, int pinnedHandle,
		Object witness, Object fixed) throws Exception {
		Method alternatives = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateProofAlternatives", CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, nested("NativePoolWitness"),
			boolean.class, nested("FixedCandidateBoundary"), nested("GenerationRoot"));
		alternatives.setAccessible(true);
		return (List<?>)alternatives.invoke(continuity, owner, pinned, pinnedHandle,
			witness, true, fixed, null);
	}

	private static Object fixedBoundary(CompiledHopKey owner,
		CandidateRealizationReference reference, int handle) throws Exception {
		Constructor<?> constructor = nested("FixedCandidateBoundary").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner,reference,handle);
	}

	private static Object nativeWitness(NativePlacementContinuity continuity,
		DurableAnchorKey anchor) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		method.setAccessible(true);
		return method.invoke(continuity, anchor);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	@SuppressWarnings("unchecked")
	private static CandidateRealizationReference source(String id) {
		CompiledHopKey owner = key(id);
		return source(owner,id);
	}

	private static CandidateRealizationReference source(CompiledHopKey owner, String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementState state = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(
			new PlacementEmissionState(state, false));
		return CandidateRealizationReference.of(rule, realization);
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey("native-r18", "main", "root", "compiled", REGION, id, id);
	}

	private static DurableAnchorKey anchor(String id, long split) {
		return new DurableAnchorKey(id, FType.ROW, List.of(
			new AnchorPartition("localhost:1234", List.of(0L, 0L), List.of(split, 2L)),
			new AnchorPartition("localhost:1235", List.of(split, 0L), List.of(8L, 2L))));
	}

}
