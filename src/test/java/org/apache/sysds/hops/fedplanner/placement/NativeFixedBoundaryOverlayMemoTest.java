/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class NativeFixedBoundaryOverlayMemoTest {
	@Test
	public void exactBoundaryReusesOverlayAndTraversalScheduleWithReferenceParity()
		throws Exception {
		Object witness = witness();
		CompiledHopKey root = owner("root");
		CompiledHopKey fixedOwner = owner("fixed");
		CompiledHopKey metadataOwner = owner("metadata");
		Object row0 = topologyRow(reference(root, "row-0"),
			List.of(skeleton(fixedOwner, witness, 0), skeleton(owner("shared"), witness, 1)), witness);
		Object row1 = topologyRow(reference(root, "row-1"),
			List.of(skeleton(fixedOwner, witness, 0), skeleton(owner("shared"), witness, 1)), witness);
		Object topology = topology(List.of(row0, row1), Map.of(), Set.of(metadataOwner));
		CandidateRealizationReference fixedReference = reference(fixedOwner, "fixed-reference");
		Object boundary = fixedBoundary(fixedOwner, fixedReference, 17);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity cached = empty(metrics);
		installTopology(cached, topologyKey(root, witness), topology);

		Object firstTraversal = traversal();
		List<?> first = alternatives(cached, root, null, 0, witness, false, boundary,
			firstTraversal);
		Object firstSchedule = schedule(first, metrics);
		Object secondTraversal = traversal();
		List<?> second = alternatives(cached, root, null, 0, witness, false, boundary,
			secondTraversal);
		Object secondSchedule = schedule(second, metrics);

		Assert.assertSame("the exact overlay list is revision-local reusable state", first, second);
		Assert.assertSame("the reusable list owns one lazy traversal schedule",
			firstSchedule, secondSchedule);
		Assert.assertEquals(nested("DefaultAlternativeList"), first.getClass());
		Assert.assertEquals(1L, longField(cached, "fixedBoundaryOverlayBuilds"));
		Assert.assertEquals(1L, longField(cached, "fixedBoundaryOverlayHits"));
		Assert.assertEquals(2L, longField(cached, "fixedBoundaryOverlayRetainedRows"));
		Assert.assertEquals("the hit must not revisit either topology row", 2L,
			longField(cached, "fixedBoundaryOverlayRowsVisited"));
		Assert.assertEquals(1L, metrics.snapshot().proofDefaultScheduleBuilds());
		Assert.assertEquals(1L, metrics.snapshot().proofDefaultScheduleHits());
		assertMetadataOwner(firstTraversal, metadataOwner);
		assertMetadataOwner(secondTraversal, metadataOwner);
		for(Object alternative : first) {
			Object dependency = ((List<?>)field(alternative, "dependencies")).get(0);
			Assert.assertSame(fixedOwner, field(dependency, "key"));
			Assert.assertSame(fixedReference, field(dependency, "realization"));
			Assert.assertEquals(17, field(dependency, "realizationHandle"));
		}

		NativePlacementContinuity reference = empty(null);
		installTopology(reference, topologyKey(root, witness), topology);
		List<?> uncachedReference = alternatives(reference, root, null, 0, witness, false,
			boundary, traversal());
		Assert.assertEquals("memoization cannot change canonical rows or exact sources",
			uncachedReference, first);
	}

	@Test
	public void exactIdentityKeySeparatesOwnersHandlesPinsAndRevisions() throws Exception {
		Object witness = witness();
		CompiledHopKey root = owner("key-root");
		CompiledHopKey fixedOwner = owner("fixed-owner");
		CompiledHopKey equalForeignOwner = owner("fixed-owner");
		Assert.assertEquals(fixedOwner, equalForeignOwner);
		Assert.assertNotSame(fixedOwner, equalForeignOwner);
		CandidateRealizationReference pinned = reference(root, "pinned");
		Object row = topologyRow(pinned, List.of(skeleton(fixedOwner, witness, 0)), witness);
		Object topology = topology(List.of(row), Map.of(31, List.of(row), 32, List.of(row)), Set.of());
		NativePlacementContinuity continuity = empty(null);
		installTopology(continuity, topologyKey(root, witness), topology);

		Object fixed17 = fixedBoundary(fixedOwner, reference(fixedOwner, "fixed-17"), 17);
		Object fixed18 = fixedBoundary(fixedOwner, reference(fixedOwner, "fixed-18"), 18);
		Object foreign17 = fixedBoundary(equalForeignOwner,
			reference(equalForeignOwner, "foreign-17"), 17);
		List<?> unpinned17 = alternatives(continuity, root, null, 0, witness, false,
			fixed17, traversal());
		Assert.assertSame(unpinned17, alternatives(continuity, root, null, 0, witness,
			false, fixed17, traversal()));
		List<?> unpinned18 = alternatives(continuity, root, null, 0, witness, false,
			fixed18, traversal());
		List<?> foreign = alternatives(continuity, root, null, 0, witness, false,
			foreign17, traversal());
		List<?> pinned31 = alternatives(continuity, root, pinned, 31, witness, false,
			fixed17, traversal());
		List<?> pinned32 = alternatives(continuity, root, pinned, 32, witness, false,
			fixed17, traversal());
		List<?> template = alternatives(continuity, root, pinned, 31, witness, true,
			fixed17, traversal());
		Assert.assertNotSame(unpinned17, unpinned18);
		Assert.assertNotSame(unpinned17, foreign);
		Assert.assertNull("an equal foreign owner cannot overlay the exact dependency",
			field(((List<?>)field(foreign.get(0), "dependencies")).get(0), "realization"));
		Assert.assertNotSame(pinned31, pinned32);
		Assert.assertNotSame(pinned31, template);
		Assert.assertEquals(5L, longField(continuity, "fixedBoundaryOverlayBuilds"));
		Assert.assertEquals(1L, longField(continuity, "fixedBoundaryOverlayHits"));

		Object negativeBoundary = fixedBoundary(fixedOwner,
			reference(fixedOwner, "negative"), -1);
		List<?> negativeFirst = alternatives(continuity, root, null, 0, witness, false,
			negativeBoundary, traversal());
		List<?> negativeSecond = alternatives(continuity, root, null, 0, witness, false,
			negativeBoundary, traversal());
		Assert.assertNotSame("resolver-local negative handles stay on the reference path",
			negativeFirst, negativeSecond);
		Assert.assertEquals(5L, longField(continuity, "fixedBoundaryOverlayBuilds"));

		NativePlacementContinuity revision = continuity.nextRevision(List.of());
		Assert.assertTrue("overlay authority and schedules never cross a candidate revision",
			((Map<?,?>)field(revision, "fixedBoundaryOverlays")).isEmpty());
	}

	@Test
	public void smallBoundaryMatrixMatchesColdCanonicalDedupAndSourceIdentity()
		throws Exception {
		Object witness = witness();
		CompiledHopKey root = owner("matrix-root");
		CompiledHopKey fixedOwner = owner("matrix-fixed");
		CandidateRealizationReference selected = reference(root, "matrix-selected");
		CandidateRealizationReference oldLeft = reference(fixedOwner, "old-left");
		CandidateRealizationReference oldRight = reference(fixedOwner, "old-right");
		Object left = topologyRow(selected,
			List.of(skeleton(fixedOwner, oldLeft, 71, witness, 0)), witness);
		Object right = topologyRow(selected,
			List.of(skeleton(fixedOwner, oldRight, 72, witness, 0)), witness);
		Object topology = topology(List.of(left, right), Map.of(31, List.of(left, right)), Set.of());
		NativePlacementContinuity cached = empty(null);
		installTopology(cached, topologyKey(root, witness), topology);

		for(int fixedHandle : List.of(41, 42)) {
			CandidateRealizationReference exact = reference(fixedOwner, "exact-" + fixedHandle);
			Object boundary = fixedBoundary(fixedOwner, exact, fixedHandle);
			for(int pinnedHandle : List.of(0, 31))
				for(boolean templateRoot : List.of(false, true)) {
					CandidateRealizationReference pinned = pinnedHandle == 0 ? null : selected;
					List<?> actual = alternatives(cached, root, pinned, pinnedHandle, witness,
						templateRoot, boundary, traversal());
					List<?> repeated = alternatives(cached, root, pinned, pinnedHandle, witness,
						templateRoot, boundary, traversal());
					NativePlacementContinuity cold = empty(null);
					installTopology(cold, topologyKey(root, witness), topology);
					List<?> reference = alternatives(cold, root, pinned, pinnedHandle, witness,
						templateRoot, boundary, traversal());
					Assert.assertEquals("effective-edge dedup keeps the first canonical row", 1,
						actual.size());
					Assert.assertEquals(reference, actual);
					Assert.assertSame(actual, repeated);
					Object dependency = ((List<?>)field(actual.get(0), "dependencies")).get(0);
					Assert.assertSame(exact, field(dependency, "realization"));
					Assert.assertEquals(fixedHandle, field(dependency, "realizationHandle"));
				}
		}
		Assert.assertEquals(8L, longField(cached, "fixedBoundaryOverlayBuilds"));
		Assert.assertEquals(8L, longField(cached, "fixedBoundaryOverlayHits"));
		Assert.assertEquals("each two-row relation is visited only on its eight misses",
			16L, longField(cached, "fixedBoundaryOverlayRowsVisited"));
	}

	@Test
	public void boundedOverlayMemoEvictsLeastRecentlyUsedAuthorityWithoutChangingRows()
		throws Exception {
		String entriesKey = "sysds.fedplanner.continuityTopology.maxEntries";
		String rowsKey = "sysds.fedplanner.continuityTopology.maxRows";
		String oldEntries = System.getProperty(entriesKey), oldRows = System.getProperty(rowsKey);
		try {
			System.setProperty(entriesKey, "2");
			System.setProperty(rowsKey, "4");
			Object witness = witness();
			CompiledHopKey root = owner("bounded-root"), fixedOwner = owner("bounded-fixed");
			Object row0 = topologyRow(reference(root, "bounded-0"),
				List.of(skeleton(fixedOwner, witness, 0)), witness);
			Object row1 = topologyRow(reference(root, "bounded-1"),
				List.of(skeleton(fixedOwner, witness, 0)), witness);
			NativePlacementContinuity continuity = empty(null);
			installTopology(continuity, topologyKey(root, witness),
				topology(List.of(row0, row1), Map.of(), Set.of()));
			Object first = fixedBoundary(fixedOwner, reference(fixedOwner, "bounded-first"), 11);
			Object second = fixedBoundary(fixedOwner, reference(fixedOwner, "bounded-second"), 12);
			Object third = fixedBoundary(fixedOwner, reference(fixedOwner, "bounded-third"), 13);
			List<?> firstRows = alternatives(continuity, root, null, 0, witness, false, first, traversal());
			List<?> secondRows = alternatives(continuity, root, null, 0, witness, false, second, traversal());
			Assert.assertSame(firstRows,
				alternatives(continuity, root, null, 0, witness, false, first, traversal()));
			alternatives(continuity, root, null, 0, witness, false, third, traversal());
			Assert.assertEquals(2, ((Map<?,?>)field(continuity, "fixedBoundaryOverlays")).size());
			Assert.assertEquals(4L, longField(continuity, "fixedBoundaryOverlayRetainedRows"));
			Assert.assertSame("touching the first authority keeps it resident", firstRows,
				alternatives(continuity, root, null, 0, witness, false, first, traversal()));
			List<?> rebuiltSecond = alternatives(continuity, root, null, 0, witness,
				false, second, traversal());
			Assert.assertNotSame(secondRows, rebuiltSecond);
			Assert.assertEquals("eviction changes storage, not canonical authority", secondRows, rebuiltSecond);
			Assert.assertEquals(4L, longField(continuity, "fixedBoundaryOverlayBuilds"));
			Assert.assertEquals(4L, longField(continuity, "fixedBoundaryOverlayRetainedRows"));
		}
		finally {
			if(oldEntries == null) System.clearProperty(entriesKey);
			else System.setProperty(entriesKey, oldEntries);
			if(oldRows == null) System.clearProperty(rowsKey);
			else System.setProperty(rowsKey, oldRows);
		}
	}

	@Test
	public void emptyAndTemplateFallbacksAreNotAdmitted() throws Exception {
		Object witness = witness();
		CompiledHopKey root = owner("fallback-root");
		CompiledHopKey fixedOwner = owner("fallback-fixed");
		NativePlacementContinuity continuity = empty(null);
		installTopology(continuity, topologyKey(root, witness),
			topology(List.of(), Map.of(), Set.of()));
		Object boundary = fixedBoundary(fixedOwner, reference(fixedOwner, "fallback"), 19);
		List<?> first = alternatives(continuity, root, reference(root, "template"), 23,
			witness, true, boundary, traversal());
		List<?> second = alternatives(continuity, root, reference(root, "template"), 23,
			witness, true, boundary, traversal());
		Assert.assertTrue(first.isEmpty());
		Assert.assertTrue(second.isEmpty());
		Assert.assertEquals(0L, longField(continuity, "fixedBoundaryOverlayBuilds"));
		Assert.assertTrue(((Map<?,?>)field(continuity, "fixedBoundaryOverlays")).isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static void assertMetadataOwner(Object traversal, CompiledHopKey owner)
		throws Exception {
		Map<Object,Set<CompiledHopKey>> reads =
			(Map<Object,Set<CompiledHopKey>>)field(traversal, "hiddenOwnerReadsByState");
		Assert.assertEquals(1, reads.size());
		Assert.assertTrue(reads.values().iterator().next().stream().anyMatch(read -> read == owner));
	}

	private static List<?> alternatives(NativePlacementContinuity continuity,
		CompiledHopKey owner, CandidateRealizationReference pinned, int pinnedHandle,
		Object witness, boolean templateRoot, Object fixed, Object traversal) throws Exception {
		Method method = method(NativePlacementContinuity.class, "candidateProofAlternatives",
			CompiledHopKey.class, CandidateRealizationReference.class, int.class,
			nested("NativePoolWitness"), boolean.class, nested("FixedCandidateBoundary"),
			nested("GenerationRoot"), nested("CandidateProofTraversal"));
		return (List<?>)method.invoke(continuity, owner, pinned, pinnedHandle, witness,
			templateRoot, fixed, null, traversal);
	}

	private static Object traversal() throws Exception {
		Constructor<?> constructor = nested("CandidateProofTraversal").getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	private static Object schedule(List<?> alternatives, SearchSpaceMetrics metrics)
		throws Exception {
		Method method = method(nested("DefaultAlternativeList"), "traversalSchedule",
			SearchSpaceMetrics.class);
		return method.invoke(alternatives, metrics);
	}

	private static Object topologyRow(CandidateRealizationReference reference,
		List<?> dependencies, Object witness) throws Exception {
		return method(nested("CandidateTopologyRow"), "create",
			CandidateRealizationReference.class, List.class, boolean.class,
			nested("NativePoolWitness")).invoke(null, reference, dependencies, false, witness);
	}

	private static Object skeleton(CompiledHopKey owner, Object witness, int position)
		throws Exception {
		return skeleton(owner, null, 0, witness, position);
	}

	private static Object skeleton(CompiledHopKey owner,
		CandidateRealizationReference pinned, int pinnedHandle, Object witness, int position)
		throws Exception {
		Constructor<?> constructor = nested("CandidateDependencySkeleton").getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return constructor.newInstance(owner, pinned, pinnedHandle, witness, position);
	}

	private static Object topology(List<?> rows, Map<Integer,? extends List<?>> rowsByHandle,
		Set<CompiledHopKey> metadataOwnerReads) throws Exception {
		Constructor<?> constructor = nested("CandidateTopology").getDeclaredConstructor(
			boolean.class, boolean.class, List.class, Map.class, Set.class);
		constructor.setAccessible(true);
		return constructor.newInstance(true, false, rows, rowsByHandle, metadataOwnerReads);
	}

	private static Object topologyKey(CompiledHopKey owner, Object witness) throws Exception {
		Constructor<?> constructor = nested("CandidateTopologyKey").getDeclaredConstructor(
			CompiledHopKey.class, nested("NativePoolWitness"));
		constructor.setAccessible(true);
		return constructor.newInstance(owner, witness);
	}

	private static void installTopology(NativePlacementContinuity continuity, Object key,
		Object topology) throws Exception {
		Assert.assertEquals(true, method(NativePlacementContinuity.class, "cacheTopology",
			nested("CandidateTopologyKey"), nested("CandidateTopology"))
			.invoke(continuity, key, topology));
	}

	private static Object fixedBoundary(CompiledHopKey owner,
		CandidateRealizationReference reference, int handle) throws Exception {
		Constructor<?> constructor = nested("FixedCandidateBoundary").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner, reference, handle);
	}

	private static Object witness() throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.FULL, List.of("worker:8001"), List.of(), true);
	}

	private static NativePlacementContinuity empty(SearchSpaceMetrics metrics) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(),
			Map.of(), metrics);
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		return CandidateRealizationReference.of(rule, CandidateEmissionRealization.nativeLineage(
			emission, "overlay:" + id, List.of(), List.of()));
	}

	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"overlay-memo", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("overlay-memo", "main", "call", "compiled",
			region, id, "origin-" + id);
	}

	private static long longField(Object owner, String name) throws Exception {
		return ((Number)field(owner, name)).longValue();
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static Method method(Class<?> owner, String name, Class<?>... parameters)
		throws Exception {
		Method method = owner.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}

	private static Class<?> nested(String name) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}
}
