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
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.NativeContinuityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativePoolWitnessInterningTest {
	@Test
	public void equalAnchorsShareWitnessAcrossQueryAndFactRevisions() throws Exception {
		NativePlacementContinuity continuity = empty();
		DurableAnchorKey first = rowAnchor("first", "localhost:8701", 0, 40);
		DurableAnchorKey equal = rowAnchor("second", "localhost:8701", 0, 40);
		Assert.assertNotEquals("anchor publication identity remains distinct", first, equal);
		Assert.assertNotSame(first, equal);

		Object canonical = nativeWitness(continuity, first);
		Assert.assertSame("equal reconstructed anchors use one structural witness",
			canonical, nativeWitness(continuity, equal));
		Assert.assertSame("a fresh query state retains structural witness identities",
			canonical, nativeWitness(continuity.freshQueryState(), equal));
		Assert.assertSame("an exact fact revision retains structural witness identities",
			canonical, nativeWitness(continuity.nextRevision(List.of()), equal));
	}

	@Test
	public void everyTransformPathConvergesWithoutCollapsingExactness() throws Exception {
		NativePlacementContinuity continuity = empty();
		Object row = nativeWitness(continuity, rowAnchor("row", "localhost:8702", 0, 50));
		Object col = nativeWitness(continuity, colAnchor("col", "localhost:8702", 0, 50));
		Method retyped = method(row.getClass(), "retyped", FType.class);
		Method dynamic = method(row.getClass(), "withDynamicPartitionRanges");
		Method exact = method(row.getClass(), "withExactPartitionRanges");

		Object retypedCol = retyped.invoke(row, FType.COL);
		Assert.assertSame("direct and transformed exact witnesses are canonical", col, retypedCol);
		Object dynamicCol = dynamic.invoke(col);
		Assert.assertSame("relax-then-retype and retype-then-relax converge", dynamicCol,
			retyped.invoke(dynamic.invoke(row), FType.COL));
		Assert.assertSame(col, exact.invoke(dynamicCol));
		Assert.assertNotSame("exact and dynamic range authority remains distinct", col, dynamicCol);
		Assert.assertNotEquals(col, dynamicCol);
	}

	@Test
	public void cachedHashRejectsDifferentWitnessesBeforeWalkingStructuralLists() throws Exception {
		Class<?> arenaType = nested("NativeWitnessArena");
		Constructor<?> constructor = arenaType.getDeclaredConstructor(int.class);
		constructor.setAccessible(true);
		Object arena = constructor.newInstance(0);
		Method intern = method(arenaType, "intern", FType.class, List.class, List.class, boolean.class);
		Object left = intern.invoke(arena, FType.FULL, List.of("worker-left"), List.of(), true);
		Object right = intern.invoke(arena, FType.FULL, List.of("worker-right"), List.of(), true);
		CountingList<String> countedEndpoints = new CountingList<>(List.of("worker-left"));
		setField(left, "endpoints", countedEndpoints);

		Assert.assertNotEquals(left.hashCode(), right.hashCode());
		Assert.assertNotEquals(left, right);
		Assert.assertEquals("unequal cached hashes must reject before deep endpoint equality", 0,
			countedEndpoints.reads);
	}

	@Test
	public void hashCollisionsAndRangeAuthorityStillUseCompleteWitnessEquality() throws Exception {
		Assert.assertEquals("fixture requires a genuine String hash collision",
			"FB".hashCode(), "Ea".hashCode());
		Class<?> arenaType = nested("NativeWitnessArena");
		Constructor<?> constructor = arenaType.getDeclaredConstructor(int.class);
		constructor.setAccessible(true);
		Method intern = method(arenaType, "intern", FType.class, List.class, List.class, boolean.class);
		Object firstArena = constructor.newInstance(0);
		Object secondArena = constructor.newInstance(0);
		Object collisionLeft = intern.invoke(firstArena, FType.FULL, List.of("FB"), List.of(), true);
		Object collisionRight = intern.invoke(secondArena, FType.FULL, List.of("Ea"), List.of(), true);
		Assert.assertEquals(collisionLeft.hashCode(), collisionRight.hashCode());
		Assert.assertNotEquals("a cached-hash collision cannot collapse distinct endpoints",
			collisionLeft, collisionRight);
		Object equalAcrossArena = intern.invoke(secondArena,
			FType.FULL, List.of("FB"), List.of(), true);
		Assert.assertEquals(collisionLeft, equalAcrossArena);
		Assert.assertNotSame(collisionLeft, equalAcrossArena);

		NativePlacementContinuity continuity = empty();
		Object exact = nativeWitness(continuity,
			rowAnchor("range-a", "localhost:8731", 0, 40));
		Object otherRange = nativeWitness(continuity,
			rowAnchor("range-b", "localhost:8731", 1, 40));
		Assert.assertNotEquals(exact, otherRange);
		Object dynamic = method(exact.getClass(), "withDynamicPartitionRanges").invoke(exact);
		Assert.assertNotEquals("exactness remains part of complete witness authority", exact, dynamic);
	}

	@Test
	public void structuralArenaStopsRetainingNewShapesAtItsFixedBound() throws Exception {
		Class<?> arenaType = nested("NativeWitnessArena");
		Constructor<?> constructor = arenaType.getDeclaredConstructor(int.class);
		constructor.setAccessible(true);
		Object arena = constructor.newInstance(1);
		Method intern = method(arenaType, "intern", FType.class, List.class, List.class, boolean.class);
		Object retained = intern.invoke(arena, FType.FULL, List.of("worker:8711"), List.of(), true);
		Object overflow = intern.invoke(arena, FType.FULL, List.of("worker:8712"), List.of(), true);
		Object equalOverflow = intern.invoke(arena, FType.FULL, List.of("worker:8712"), List.of(), true);
		Assert.assertNotSame("overflow is exact but is not retained beyond the fixed bound",
			overflow, equalOverflow);
		Assert.assertEquals(overflow, equalOverflow);
		Assert.assertSame("already retained hot shapes remain canonical", retained,
			intern.invoke(arena, FType.FULL, List.of("worker:8711"), List.of(), true));

		Method residency = method(overflow.getClass(), "retypedForResidency", FType.class);
		Method exact = method(overflow.getClass(), "withExactPartitionRanges");
		Object overflowRow = residency.invoke(overflow, FType.ROW);
		Object secondOverflowRow = residency.invoke(equalOverflow, FType.ROW);
		Assert.assertSame("one overflow family still memoizes its own transform", overflowRow,
			residency.invoke(overflow, FType.ROW));
		Assert.assertNotSame("separate overflow families cannot borrow mutable transform siblings",
			overflowRow, secondOverflowRow);
		Assert.assertEquals(overflowRow, secondOverflowRow);
		Assert.assertNotSame(exact.invoke(overflowRow), exact.invoke(secondOverflowRow));
		Assert.assertEquals(exact.invoke(overflowRow), exact.invoke(secondOverflowRow));
		Map<?,?> retainedWitnesses = (Map<?,?>)field(arena, "canonicalWitnesses");
		Assert.assertEquals(1, retainedWitnesses.size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void optimizedProofComparatorRetainsCompleteSignatureOrder() throws Exception {
		Method factory = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeProofSignatureComparator");
		factory.setAccessible(true);
		Comparator<NativeContinuityProof> comparator =
			(Comparator<NativeContinuityProof>)factory.invoke(null);
		DurableAnchorKey commonSeed = rowAnchor("seed", "localhost:8721", 0, 100);
		List<NativeContinuityProof> proofs = new ArrayList<>(List.of(
			new NativeContinuityProof(commonSeed, rowAnchor("z", "localhost:8722", 0, 30), true, List.of()),
			new NativeContinuityProof(commonSeed, rowAnchor("a", "localhost:8722", 0, 20), false, List.of()),
			new NativeContinuityProof(commonSeed, rowAnchor("a", "localhost:8722", 0, 20), true, List.of()),
			new NativeContinuityProof(rowAnchor("different", "localhost:8723", 0, 100),
				rowAnchor("out", "localhost:8722", 0, 20), true, List.of())));
		for(NativeContinuityProof left : proofs)
			for(NativeContinuityProof right : proofs)
				Assert.assertEquals(Integer.signum(expectedSignature(left).compareTo(
					expectedSignature(right))), Integer.signum(comparator.compare(left, right)));
		Assert.assertNotNull("the cold comparator retains its seed-free ordering suffix",
			field(proofs.get(0), "canonicalOrderingSuffixText"));
		Assert.assertNotNull("equal outputs can compare only range and binding authority",
			field(proofs.get(1), "canonicalRangeBindingText"));
		List<NativeContinuityProof> expected = new ArrayList<>(proofs);
		expected.sort(Comparator.comparing(NativePoolWitnessInterningTest::expectedSignature));
		proofs.sort(comparator);
		Assert.assertEquals(expected, proofs);
	}

	private static String expectedSignature(NativeContinuityProof proof) {
		return proof.externalSeed().normalizedSignature() + "|outputPool="
			+ proof.outputWorkerPoolWitness().normalizedSignature() + "|partitionRanges="
			+ (proof.exactPartitionRanges() ? "exact" : "dynamic") + "|bindings="
			+ proof.immediateBindings().stream()
				.map(PlacementIdentity.CandidateRealizationInputBinding::normalizedSignature).toList();
	}

	private static NativePlacementContinuity empty() {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of());
	}

	private static Object nativeWitness(NativePlacementContinuity continuity,
		DurableAnchorKey anchor) throws Exception {
		Method method = method(NativePlacementContinuity.class, "nativeWitness", DurableAnchorKey.class);
		return method.invoke(continuity, anchor);
	}

	private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
		Method method = owner.getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method;
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void setField(Object owner, String name, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}

	private static Class<?> nested(String simpleName) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + simpleName);
	}

	private static DurableAnchorKey rowAnchor(String id, String endpoint, long begin, long end) {
		return new DurableAnchorKey(id, FType.ROW,
			List.of(new AnchorPartition(endpoint, List.of(begin, 0L), List.of(end, 1L))));
	}

	private static DurableAnchorKey colAnchor(String id, String endpoint, long begin, long end) {
		return new DurableAnchorKey(id, FType.COL,
			List.of(new AnchorPartition(endpoint, List.of(0L, begin), List.of(1L, end))));
	}

	private static DurableAnchorKey fullAnchor(String id, String endpoint) {
		return new DurableAnchorKey(id, FType.FULL,
			List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(1L, 1L))));
	}

	private static final class CountingList<T> extends AbstractList<T> {
		private final List<T> values;
		private int reads;
		private CountingList(List<T> values) { this.values = List.copyOf(values); }
		@Override public T get(int index) { reads++; return values.get(index); }
		@Override public int size() { return values.size(); }
	}
}
