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
import java.util.List;
import java.util.Map;

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

public class CandidateTopologyStructuralHandleAuthorityTest {
	@Test
	public void stableValidationChecksEachReferenceIdentityOnce() throws Exception {
		Object witness = witness();
		CandidateRealizationReference rowReference = reference("row");
		CandidateRealizationReference pinned = reference("pinned");
		CandidateRealizationReference equalFirst = reference("equal");
		CandidateRealizationReference equalSecond = new CandidateRealizationReference(
			equalFirst.rule(), equalFirst.realization());
		Assert.assertEquals(equalFirst, equalSecond);
		Assert.assertNotSame(equalFirst, equalSecond);
		List<Object> dependencies = List.of(
			skeleton(pinned.rule().parentOccurrence(), pinned, 8, witness, 0),
			skeleton(equalFirst.rule().parentOccurrence(), equalFirst, 9, witness, 1),
			skeleton(equalSecond.rule().parentOccurrence(), equalSecond, 9, witness, 2),
			skeleton(owner("unpinned"), null, 0, witness, 3));
		Object first = row(rowReference, dependencies, witness);
		Object second = row(rowReference, dependencies, witness);
		Object topology = topology(List.of(first, second), Map.of(7, List.of(first, second)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity destination = destination(metrics);
		IdentityHashMap<CandidateRealizationReference,Integer> handles = new IdentityHashMap<>();
		handles.put(rowReference, 7);
		handles.put(pinned, 8);
		handles.put(equalFirst, 9);
		handles.put(equalSecond, 9);
		installHandles(destination, handles);

		long before = metrics.snapshot().structuralHandleLookups();
		Assert.assertTrue(stable(topology, destination));
		Assert.assertEquals("equal-but-distinct references retain separate authority checks",
			4, metrics.snapshot().structuralHandleLookups() - before);
	}

	@Test
	public void malformedOrMismatchedAuthorityFailsClosed() throws Exception {
		Object witness = witness();
		CandidateRealizationReference reference = reference("conflict");
		Object ordinary = row(reference, List.of(), witness);
		NativePlacementContinuity matching = destination(new SearchSpaceMetrics());
		installHandles(matching, Map.of(reference, 7));

		Assert.assertFalse("one identity cannot authorize two expected handles",
			stable(topology(List.of(ordinary, ordinary),
				Map.of(7, List.of(ordinary), 8, List.of(ordinary))), matching));
		Assert.assertFalse("nonpositive row handles are never shared authority",
			stable(topology(List.of(ordinary), Map.of(0, List.of(ordinary))), matching));
		Object invalidNullPin = row(reference,
			List.of(skeleton(owner("null-pin"), null, 1, witness, 0)), witness);
		Assert.assertFalse("a null pin must retain the zero-handle sentinel",
			stable(topology(List.of(invalidNullPin), Map.of(7, List.of(invalidNullPin))), matching));
		CandidateRealizationReference negativePin = reference("negative-pin");
		Object negative = row(reference, List.of(skeleton(
			negativePin.rule().parentOccurrence(), negativePin, -1, witness, 0)), witness);
		Assert.assertFalse("negative resolver-local handles cannot cross revisions",
			stable(topology(List.of(negative), Map.of(7, List.of(negative))), matching));

		NativePlacementContinuity mismatched = destination(new SearchSpaceMetrics());
		installHandles(mismatched, Map.of(reference, 8));
		Assert.assertFalse("a changed destination handle forces reindexing",
			stable(topology(List.of(ordinary), Map.of(7, List.of(ordinary))), mismatched));
	}

	private static NativePlacementContinuity destination(SearchSpaceMetrics metrics) {
		return new NativePlacementContinuity(Map.of(), Map.of(), List.of(), List.of(), Map.of(), metrics);
	}

	@SuppressWarnings("unchecked")
	private static void installHandles(NativePlacementContinuity destination,
		Map<CandidateRealizationReference,Integer> handles) throws Exception {
		IdentityHashMap<CandidateRealizationReference,Integer> identityHandles =
			(IdentityHashMap<CandidateRealizationReference,Integer>)field(
				destination, "candidateHandleByReference");
		identityHandles.putAll(handles);
	}

	private static boolean stable(Object topology, NativePlacementContinuity destination)
		throws Exception {
		Method method = nested("CandidateTopology").getDeclaredMethod(
			"hasStableStructuralHandles", NativePlacementContinuity.class);
		method.setAccessible(true);
		return (boolean)method.invoke(topology, destination);
	}

	private static Object topology(List<?> rows, Map<Integer,? extends List<?>> rowsByHandle)
		throws Exception {
		Constructor<?> constructor = nested("CandidateTopology").getDeclaredConstructor(
			boolean.class, boolean.class, List.class, Map.class);
		constructor.setAccessible(true);
		return constructor.newInstance(true, false, rows, rowsByHandle);
	}

	private static Object row(CandidateRealizationReference reference,
		List<?> dependencies, Object witness) throws Exception {
		Method create = nested("CandidateTopologyRow").getDeclaredMethod("create",
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		create.setAccessible(true);
		return create.invoke(null, reference, dependencies, false, witness);
	}

	private static Object skeleton(CompiledHopKey owner, CandidateRealizationReference pinned,
		int handle, Object witness, int position) throws Exception {
		Constructor<?> constructor = nested("CandidateDependencySkeleton").getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		return constructor.newInstance(owner, pinned, handle, witness, position);
	}

	private static Object witness() throws Exception {
		Constructor<?> constructor = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.FULL, List.of("worker:8001"), List.of(), true);
	}

	private static CandidateRealizationReference reference(String id) {
		CandidateRuleKey rule = new CandidateRuleKey(owner(id), List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.FULL, false), false);
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.nativeLineage(
				emission, "structural-authority:" + id, List.of(), List.of()));
	}

	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"structural-authority", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("structural-authority", "main", "call", "compiled",
			region, id, "origin-" + id);
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static Class<?> nested(String simpleName) throws Exception {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + simpleName);
	}
}
