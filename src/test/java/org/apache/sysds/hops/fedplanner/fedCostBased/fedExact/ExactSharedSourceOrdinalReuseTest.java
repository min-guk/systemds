/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** Work and semantic oracles for preparation-owned, sparse reference-ordinal rows. */
public class ExactSharedSourceOrdinalReuseTest {

	@Test
	public void repeatedChecksDoNotRereadRawReferencesAndReleaseTheirMaps() throws Exception {
		Fixture fixture = fixture(false);
		freezeIfAvailable(fixture);
		fixture.rawReads().set(0);
		for(int repeat = 0; repeat < 8; repeat++) {
			assertFunctional(fixture, new int[] {0, 1, 1, 0, 2, 2, 3});
			method("axisPlan").invoke(null, 0, fixture.view(), new boolean[] {true, true},
				new int[] {0, 1, 1, 0, 2, 2, 3}, fixture.references());
			assertEquals(1, findRow(fixture, 0, fixture.values()[1][0], fixture.values()[1][1]));
			assertEquals(5, findRow(fixture, 1, fixture.values()[5][0], 0));
			assertEquals(6, findRow(fixture, 2, 0, 0));
		}
		assertEquals("reference equality/ordinal resolution is performed only at freeze", 0,
			fixture.rawReads().get());
		assertNull("raw reference maps must not be retained alongside the ordinal maps",
			field(fixture.view(), "refsByRow"));
	}

	@Test
	public void functionalTruthMatchesIndependentLegacyProjectionForEverySourceSubset()
		throws Exception {
		Fixture fixture = fixture(false);
		freezeIfAvailable(fixture);
		Random random = new Random(48150924L);
		for(int example = 0; example < 200; example++) {
			int[] classes = new int[fixture.headers().length];
			for(int row = 0; row < classes.length; row++)
				classes[row] = random.nextInt(4);
			assertFunctional(fixture, classes);
		}
	}

	@Test
	public void wideOrdinalsMinusOneAndHashCollisionsRemainDistinct() throws Exception {
		Object collision = ordinalView(new int[] {0, 1}, new int[][] {{31}, {0}});
		assertTrue("[0,31] and [1,0] collide by List hash but are not equal",
			functional(collision, new int[] {0, 1}, new boolean[] {true}));

		Object absentAndZero = ordinalView(new int[] {0, 0}, new int[][] {{-1}, {0}});
		assertTrue("unbound -1 is distinct from ordinal zero",
			functional(absentAndZero, new int[] {0, 1}, new boolean[] {true}));

		Object wide = ordinalView(new int[] {0, 0, 1, 1},
			new int[][] {{127}, {128}, {139}, {139}});
		assertTrue("ordinals above signed-byte range stay distinct",
			functional(wide, new int[] {0, 1, 2, 2}, new boolean[] {true}));
		assertFalse("same projected tuple cannot map to conflicting classes",
			functional(wide, new int[] {0, 1, 2, 3}, new boolean[] {true}));
	}

	@Test
	public void classDomainMismatchFailsBeforeDependencyRowsAreRead() throws Exception {
		Object view = ordinalView(new int[] {0, 0}, new int[][] {{0}, {1}});
		InvocationTargetException failure = org.junit.Assert.assertThrows(
			InvocationTargetException.class, () -> method("functional").invoke(null, view,
				new IdentityHashMap<>(), new int[] {0}, new boolean[] {true, true, true}));
		assertTrue(failure.getCause().getMessage().contains("RAW_FACTOR_AXIS_DOMAIN_MISMATCH"));
	}

	@Test
	public void freshReferenceOrderAndDomainCannotReuseAnotherPreparationsOrdinals() throws Exception {
		Fixture first = fixture(false);
		Fixture second = fixture(true);
		freezeIfAvailable(first);
		freezeIfAvailable(second);
		for(Fixture fixture : List.of(first, second)) {
			assertFunctional(fixture, new int[] {0, 0, 1, 1, 2, 3, 4});
			assertEquals(0, findRow(fixture, 0, fixture.values()[0][0], fixture.values()[0][1]));
			assertEquals(3, findRow(fixture, 0, fixture.values()[3][0], fixture.values()[3][1]));
		}
	}

	private static void assertFunctional(Fixture fixture, int[] classes) throws Exception {
		for(int subset = 0; subset < 4; subset++) {
			boolean[] dependencies = {(subset & 1) != 0, (subset & 2) != 0};
			Map<List<Integer>,Integer> observed = new LinkedHashMap<>();
			boolean expected = true;
			for(int row = 0; row < classes.length; row++) {
				List<Integer> key = new ArrayList<>();
				key.add(fixture.headers()[row]);
				for(int owner = 0; owner < dependencies.length; owner++)
					if(dependencies[owner])
						key.add(fixture.values()[row][owner]);
				Integer previous = observed.putIfAbsent(key, classes[row]);
				if(previous != null && previous != classes[row]) {
					expected = false;
					break;
				}
			}
			assertEquals("same original row projection for subset " + subset, expected,
				method("functional").invoke(null, fixture.view(), fixture.references(), classes,
					dependencies));
		}
	}

	private static int findRow(Fixture fixture, int header, int left, int right) throws Exception {
		IdentityHashMap<Object,Integer> positions = new IdentityHashMap<>();
		positions.put(field(fixture.view(), "headerVariable"), 0);
		for(int owner = 0; owner < fixture.owners().size(); owner++)
			positions.put(field(fixture.references().get(fixture.owners().get(owner)), "variable"),
				owner + 1);
		Method find = Arrays.stream(ExactPhysicalSharedSourceEncoding.class.getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals("findRow")
				&& candidate.getParameterTypes()[0] == fixture.view().getClass())
			.findFirst().orElseThrow();
		find.setAccessible(true);
		return (int)find.invoke(null, fixture.view(), new boolean[] {true, true},
			new int[] {header, left, right}, positions, fixture.references());
	}

	private static boolean functional(Object view, int[] classes, boolean[] dependencies)
		throws Exception {
		return (boolean)method("functional").invoke(null, view, new IdentityHashMap<>(),
			classes, dependencies);
	}

	private static Object ordinalView(int[] headers, int[][] values) throws Exception {
		CompiledHopKey owner = owner("wide-owner-" + headers.length + '-' + values[0][0]);
		int headerDomain = Arrays.stream(headers).max().orElseThrow() + 1;
		Object header = sourceIndependentHeader(owner);
		List<IdentityHashMap<CompiledHopKey,CandidateRealizationReference>> raw = new ArrayList<>();
		List<IdentityHashMap<CompiledHopKey,Integer>> ordinals = new ArrayList<>();
		for(int row = 0; row < headers.length; row++) {
			raw.add(new IdentityHashMap<>());
			IdentityHashMap<CompiledHopKey,Integer> selected = new IdentityHashMap<>();
			if(values[row][0] >= 0)
				selected.put(owner, values[row][0]);
			ordinals.add(selected);
		}
		Object view = construct(nested("DomainView"), 0, null,
			java.util.Collections.nCopies(headerDomain, header), headers, List.of(owner), raw);
		Field frozen = view.getClass().getDeclaredField("sourceOrdinalsByRow");
		frozen.setAccessible(true);
		frozen.set(view, List.copyOf(ordinals));
		Field refs = view.getClass().getDeclaredField("refsByRow");
		refs.setAccessible(true);
		refs.set(view, null);
		return view;
	}

	private static Fixture fixture(boolean reverse) throws Exception {
		CompiledHopKey left = owner("ordinal-left"), right = owner("ordinal-right");
		PlacementRealizationKey layout = PlacementRealizationKey.local(new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false));
		CandidateRealizationReference a0 = reference(left, false, layout);
		CandidateRealizationReference a1 = reference(left, true, layout);
		CandidateRealizationReference b0 = reference(right, false, layout);
		CandidateRealizationReference b1 = reference(right, true, layout);
		CandidateRealizationReference equalA0 = new CandidateRealizationReference(a0.rule(), a0.realization());
		assertEquals(a0, equalA0);
		int[] headers = {0, 0, 0, 0, 1, 1, 2};
		CandidateRealizationReference[][] rows = {
			{a0, b0}, {equalA0, b1}, {a1, b0}, {a1, b1}, {a0, null}, {a1, null}, {null, null}};
		AtomicInteger reads = new AtomicInteger();
		List<IdentityHashMap<CompiledHopKey,CandidateRealizationReference>> rawRows = new ArrayList<>();
		for(CandidateRealizationReference[] row : rows) {
			IdentityHashMap<CompiledHopKey,CandidateRealizationReference> raw =
				new IdentityHashMap<>() {
					@Override public CandidateRealizationReference get(Object key) {
						reads.incrementAndGet();
						return super.get(key);
					}
				};
			if(row[0] != null) raw.put(left, row[0]);
			if(row[1] != null) raw.put(right, row[1]);
			rawRows.add(raw);
		}
		Object header = sourceIndependentHeader(left);
		Object view = construct(nested("DomainView"), 0, null, List.of(header, header, header), headers,
			List.of(left, right), rawRows);
		int av0 = reverse ? 2 : 0, av1 = reverse ? 0 : 2;
		int bv0 = reverse ? 0 : 1, bv1 = reverse ? 1 : 0;
		IdentityHashMap<Object,Object> references = new IdentityHashMap<>();
		references.put(left, construct(nested("ReferenceView"), view,
			List.of(reverse ? a1 : a0, new Object(), reverse ? a0 : a1),
			Map.of(a0, av0, a1, av1), new int[] {av0, av1, 1}));
		references.put(right, construct(nested("ReferenceView"), view,
			List.of(reverse ? b0 : b1, reverse ? b1 : b0),
			Map.of(b0, bv0, b1, bv1), new int[] {bv0, bv1, bv0}));
		int[][] values = {{av0, bv0}, {av0, bv1}, {av1, bv0}, {av1, bv1},
			{av0, -1}, {av1, -1}, {-1, -1}};
		return new Fixture(view, List.of(left, right), references, headers, values, reads);
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, boolean input,
		PlacementRealizationKey layout) {
		return new CandidateRealizationReference(new CandidateRuleKey(owner,
			input ? List.of(CandidateInputState.absentLocal()) : List.of()), layout);
	}

	private static CompiledHopKey owner(String name) throws Exception {
		// Reuse the established identity fixture without opening a production test API.
		Method node = ExactPhysicalSharedSourceEncodingTest.class.getDeclaredMethod(
			"syntheticNode", String.class, List.class);
		node.setAccessible(true);
		return ((org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node)node.invoke(null,
			name, List.of(new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false)))).key();
	}

	private static void freezeIfAvailable(Fixture fixture) throws Exception {
		for(Method candidate : fixture.view().getClass().getDeclaredMethods())
			if(candidate.getName().equals("freezeSourceOrdinals")) {
				candidate.setAccessible(true);
				candidate.invoke(fixture.view(), fixture.references());
			}
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(ExactPhysicalSharedSourceEncoding.class.getName() + '$' + name);
	}

	private static Object sourceIndependentHeader(CompiledHopKey decision) throws Exception {
		// These tests isolate source-reference ordinals after the a_v/b_e header projection.
		// A valid local a_v with no b_e or binding distinction keeps that axis constant.
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		ExactPhysicalNativeSupplyRepresentation.NativeCandidate nativeCandidate =
			new ExactPhysicalNativeSupplyRepresentation.NativeCandidate(decision,
				ExactPhysicalNativeSupplyRepresentation.NativeExecutionKind.LEGAL_SINGLETON,
				null, ExecType.CP, null, List.of(), local,
				new ExactPhysicalNativeSupplyRepresentation.NativeLayout(
					PlacementLayoutKind.LOCAL, null, null, false));
		Constructor<?> constructor = nested("AlternativeHeader").getDeclaredConstructor(
			ExactPhysicalNativeSupplyRepresentation.NativeCandidate.class, List.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(nativeCandidate, List.of(), List.of());
	}

	private static Object construct(Class<?> type, Object... arguments) throws Exception {
		Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
			.filter(candidate -> candidate.getParameterCount() == arguments.length).findFirst().orElseThrow();
		constructor.setAccessible(true);
		return constructor.newInstance(arguments);
	}

	private static Method method(String name) {
		Method method = Arrays.stream(ExactPhysicalSharedSourceEncoding.class.getDeclaredMethods())
			.filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
		method.setAccessible(true);
		return method;
	}

	private static Object field(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private record Fixture(Object view, List<CompiledHopKey> owners,
		IdentityHashMap<Object,Object> references, int[] headers, int[][] values,
		AtomicInteger rawReads) { }
}
