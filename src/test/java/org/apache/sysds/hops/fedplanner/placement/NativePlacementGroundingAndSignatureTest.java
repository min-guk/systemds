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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	private static final long LEGACY_SCC_INVOCATIONS = 3;

	@Test
	public void maximalComponentsAreGroundedOnceInDependencyFirstOrder() throws Exception {
		GroundingRun failedFirst = groundingRun(false);
		GroundingRun groundedFirst = groundingRun(true);

		Assert.assertEquals(Set.of("grounded"), failedFirst.groundedIds());
		Assert.assertEquals(failedFirst.groundedIds(), groundedFirst.groundedIds());
		Assert.assertEquals("the unchanged seedless SCC needs only its initial maximal scan",
			1, failedFirst.sccInvocations());
		Assert.assertEquals(failedFirst.sccInvocations(), groundedFirst.sccInvocations());
		Assert.assertEquals("neither unchanged refinement nor its legacy fixed-point replay is needed",
			2, failedFirst.legacySccInvocations() - failedFirst.sccInvocations());
	}

	@Test
	public void eligibleRefinementCannotReviveFromLaterInsertionOrder() throws Exception {
		GroundingRun failedFirst = refinedGroundingRun(false);
		GroundingRun groundedFirst = refinedGroundingRun(true);

		Assert.assertEquals(Set.of("grounded"), failedFirst.groundedIds());
		Assert.assertEquals(failedFirst.groundedIds(), groundedFirst.groundedIds());
		Assert.assertEquals("one maximal scan plus one split-component refinement",
			2, failedFirst.sccInvocations());
		Assert.assertEquals(failedFirst.sccInvocations(), groundedFirst.sccInvocations());
		Assert.assertEquals(1,
			failedFirst.legacySccInvocations() - failedFirst.sccInvocations());
	}

	@Test
	public void unchangedComponentUsesExactExternalGroundWithoutRefinement() throws Exception {
		for(boolean groundedFirst : List.of(false, true)) {
			GroundingRun run = externallyGroundedCycleRun(groundedFirst, false);
			Assert.assertEquals(Set.of("cycle-a", "cycle-b", "grounded"), run.groundedIds());
			Assert.assertEquals("unchanged eligible alternatives preserve the original SCC", 1,
				run.sccInvocations());
		}
	}

	@Test
	public void anyRemovedAlternativeStillRequiresExactRefinement() throws Exception {
		for(boolean groundedFirst : List.of(false, true)) {
			GroundingRun run = externallyGroundedCycleRun(groundedFirst, true);
			Assert.assertEquals(Set.of("cycle-a", "cycle-b", "grounded"), run.groundedIds());
			Assert.assertEquals("even a rejected alternative duplicating the internal edge requires refinement",
				2, run.sccInvocations());
		}
	}

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
		CandidateRealizationReference overlaidSource = source("source-overlaid");
		Object firstRow = topologyRow(firstReference,
			List.of(skeleton(firstDependency, firstSource, 11, witness)), witness);
		Object dynamicRow = topologyRow(dynamicReference,
			List.of(skeleton(dynamicDependency, dynamicSource, 12, witness)), witness);
		Object lastRow = topologyRow(lastReference,
			List.of(skeleton(lastDependency, lastSource, 13, witness)), witness);
		List<Object> rows = List.of(firstRow, dynamicRow, lastRow);
		int pinnedHandle = 29;
		installTopology(continuity, owner, witness, rows, pinnedHandle);
		Map<CompiledHopKey,CandidateRealizationReference> fixed = new IdentityHashMap<>();
		fixed.put(dynamicDependency, overlaidSource);
		Map<CompiledHopKey,Integer> handles = new IdentityHashMap<>();
		handles.put(dynamicDependency, 17);

		List<?> full = candidateAlternatives(
			continuity, owner, null, 0, witness, fixed, handles);
		Assert.assertEquals(4, full.size());
		Assert.assertNull("the synthetic direct ground remains first", field(full.get(0), "realization"));
		Assert.assertSame(field(firstRow, "defaultAlternative"), full.get(1));
		Assert.assertNotSame(field(dynamicRow, "defaultAlternative"), full.get(2));
		Assert.assertSame(field(lastRow, "defaultAlternative"), full.get(3));

		List<?> pinned = candidateAlternatives(
			continuity, owner, firstReference, pinnedHandle, witness, fixed, handles);
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
		CompiledHopKey firstRoot = key("unrelated-first-root");
		CompiledHopKey secondRoot = key("unrelated-second-root");
		Map<CompiledHopKey,CandidateRealizationReference> firstFixed = new IdentityHashMap<>();
		Map<CompiledHopKey,CandidateRealizationReference> secondFixed = new IdentityHashMap<>();
		firstFixed.put(firstRoot, source("unrelated-first-source"));
		secondFixed.put(secondRoot, source("unrelated-second-source"));

		List<?> unpinned = candidateAlternatives(continuity, owner, null, 0, witness,
			firstFixed, Map.of(firstRoot, 21));
		List<?> again = candidateAlternatives(continuity, owner, null, 0, witness,
			secondFixed, Map.of(secondRoot, 22));
		Assert.assertEquals(2, unpinned.size());
		Assert.assertSame(field(first, "defaultAlternative"), unpinned.get(0));
		Assert.assertSame(field(second, "defaultAlternative"), unpinned.get(1));
		Assert.assertSame("unrelated query pins reuse the enclosing canonical list", unpinned, again);
		Assert.assertThrows(UnsupportedOperationException.class, unpinned::clear);

		List<?> pinned = candidateAlternatives(continuity, owner, firstReference, 31, witness,
			firstFixed, Map.of(firstRoot, 21));
		List<?> pinnedAgain = candidateAlternatives(continuity, owner, firstReference, 31, witness,
			secondFixed, Map.of(secondRoot, 22));
		Assert.assertEquals(unpinned, pinned);
		Assert.assertSame("handle-filtered default lists are also immutable shared authority", pinned, pinnedAgain);
		Assert.assertThrows(UnsupportedOperationException.class, pinned::clear);
		Assert.assertTrue("a missing pinned row does not borrow unrelated default authority",
			candidateAlternatives(continuity, owner, firstReference, 99, witness,
				firstFixed, Map.of(firstRoot, 21)).isEmpty());
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

	private static GroundingRun groundingRun(boolean groundedFirst) throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Object witness = witness();
		CompiledHopKey aKey = key("failed-a");
		CompiledHopKey bKey = key("failed-b");
		CompiledHopKey groundedKey = key("grounded");
		Object a = state(aKey, 1, witness);
		Object b = state(bKey, 2, witness);
		Object grounded = state(groundedKey, 3, witness);
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		if(groundedFirst)
			graph.put(grounded, List.of(alternative(List.of(), true, witness)));
		graph.put(a, List.of(alternative(List.of(dependency(bKey, 2, witness)), false, witness)));
		graph.put(b, List.of(alternative(List.of(dependency(aKey, 1, witness)), false, witness)));
		if(!groundedFirst)
			graph.put(grounded, List.of(alternative(List.of(), true, witness)));

		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"groundedCandidateStates", Map.class);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		Set<Object> result = (Set<Object>)method.invoke(continuity, graph);
		Set<String> groundedIds = result.stream().map(NativePlacementGroundingAndSignatureTest::stateId)
			.collect(java.util.stream.Collectors.toSet());
		long invocations = metrics.snapshot().sccInvocations();
		return new GroundingRun(groundedIds, invocations, LEGACY_SCC_INVOCATIONS);
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
		Field cache = NativePlacementContinuity.class.getDeclaredField("candidateTopologies");
		cache.setAccessible(true);
		((Map<Object,Object>)cache.get(continuity)).put(topologyKey, topology);
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
		Object witness, Map<CompiledHopKey,CandidateRealizationReference> fixed,
		Map<CompiledHopKey,Integer> handles) throws Exception {
		Method alternatives = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateProofAlternatives", CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, nested("NativePoolWitness"),
			boolean.class, Map.class, Map.class, nested("GenerationRoot"));
		alternatives.setAccessible(true);
		return (List<?>)alternatives.invoke(continuity, owner, pinned, pinnedHandle,
			witness, true, fixed, handles, null);
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

	private static GroundingRun externallyGroundedCycleRun(boolean groundedFirst,
		boolean includeRejectedAlternative) throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Object witness = witness();
		CompiledHopKey aKey = key("cycle-a");
		CompiledHopKey bKey = key("cycle-b");
		CompiledHopKey groundedKey = key("grounded");
		CompiledHopKey ungroundedKey = key("ungrounded");
		Object a = state(aKey, 1, witness);
		Object b = state(bKey, 2, witness);
		Object grounded = state(groundedKey, 3, witness);
		Object ungrounded = state(ungroundedKey, 4, witness);
		Object supported = alternative(List.of(dependency(bKey, 2, witness),
			dependency(groundedKey, 3, witness)), false, witness);
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		if(groundedFirst)
			graph.put(grounded, List.of(alternative(List.of(), true, witness)));
		graph.put(a, includeRejectedAlternative ? List.of(supported,
			alternative(List.of(dependency(bKey, 2, witness),
				dependency(ungroundedKey, 4, witness)), false, witness)) : List.of(supported));
		graph.put(b, List.of(alternative(List.of(dependency(aKey, 1, witness)), false, witness)));
		if(includeRejectedAlternative)
			graph.put(ungrounded, List.of(alternative(
				List.of(dependency(ungroundedKey, 4, witness)), false, witness)));
		if(!groundedFirst)
			graph.put(grounded, List.of(alternative(List.of(), true, witness)));
		Set<String> groundedIds = groundedStates(continuity, graph).stream()
			.map(NativePlacementGroundingAndSignatureTest::stateId)
			.collect(java.util.stream.Collectors.toSet());
		return new GroundingRun(groundedIds, metrics.snapshot().sccInvocations(),
			LEGACY_SCC_INVOCATIONS);
	}

	private static GroundingRun refinedGroundingRun(boolean groundedFirst) throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
		Object witness = witness();
		CompiledHopKey aKey = key("failed-a");
		CompiledHopKey bKey = key("failed-b");
		CompiledHopKey ungroundedKey = key("ungrounded");
		CompiledHopKey groundedKey = key("grounded");
		Object a = state(aKey, 1, witness);
		Object b = state(bKey, 2, witness);
		Object ungrounded = state(ungroundedKey, 3, witness);
		Object grounded = state(groundedKey, 4, witness);
		Map<Object,List<Object>> graph = new LinkedHashMap<>();
		if(groundedFirst)
			graph.put(grounded, List.of(alternative(List.of(), true, witness)));
		graph.put(a, List.of(
			alternative(List.of(dependency(aKey, 1, witness)), false, witness),
			alternative(List.of(dependency(bKey, 2, witness),
				dependency(ungroundedKey, 3, witness)), false, witness)));
		graph.put(b, List.of(alternative(List.of(
			dependency(aKey, 1, witness), dependency(groundedKey, 4, witness)), false, witness)));
		graph.put(ungrounded, List.of(alternative(List.of(
			dependency(ungroundedKey, 3, witness)), false, witness)));
		if(!groundedFirst)
			graph.put(grounded, List.of(alternative(List.of(), true, witness)));

		Set<Object> result = groundedStates(continuity, graph);
		Set<String> groundedIds = result.stream().map(NativePlacementGroundingAndSignatureTest::stateId)
			.collect(java.util.stream.Collectors.toSet());
		return new GroundingRun(groundedIds, metrics.snapshot().sccInvocations(),
			LEGACY_SCC_INVOCATIONS);
	}

	@SuppressWarnings("unchecked")
	private static Set<Object> groundedStates(NativePlacementContinuity continuity,
		Map<Object,List<Object>> graph) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"groundedCandidateStates", Map.class);
		method.setAccessible(true);
		return (Set<Object>)method.invoke(continuity, graph);
	}

	private static String stateId(Object state) {
		try {
			Method key = state.getClass().getDeclaredMethod("key");
			key.setAccessible(true);
			CompiledHopKey compiled = (CompiledHopKey)key.invoke(state);
			return compiled.emittedHopInstance();
		}
		catch(ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static CandidateRealizationReference source(String id) {
		CompiledHopKey owner = key(id);
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementState state = new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.ROW, false);
		CandidateEmissionRealization realization = CandidateEmissionRealization.local(
			new PlacementEmissionState(state, false));
		return CandidateRealizationReference.of(rule, realization);
	}

	private static Object witness() throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.ROW, List.of("localhost:1234"), List.of(), false);
	}

	private static Object state(CompiledHopKey key, int handle, Object witness) throws Exception {
		Constructor<?> constructor = nested("CandidateProofState").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(key, null, handle, witness, false);
	}

	private static Object dependency(CompiledHopKey key, int handle, Object witness) throws Exception {
		Constructor<?> constructor = nested("CandidateProofDependency").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), int.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(key, null, handle, witness, 0, false);
	}

	private static Object alternative(List<Object> dependencies,
		boolean directGround, Object witness) throws Exception {
		Constructor<?> constructor = nested("SelectedCandidateProof").getDeclaredConstructor(
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		constructor.setAccessible(true);
		return constructor.newInstance(null, dependencies, directGround, witness);
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

	private record GroundingRun(Set<String> groundedIds, long sccInvocations,
		long legacySccInvocations) { }
}
