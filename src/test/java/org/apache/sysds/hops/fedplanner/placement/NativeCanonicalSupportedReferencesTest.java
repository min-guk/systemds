/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership. */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
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

public class NativeCanonicalSupportedReferencesTest {
	@Test
	public void repeatedEligibleQueriesOnlyCheckDistinctSuccessors() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			Object left = dependency(owner("left"), 1), right = dependency(owner("right"), 2);
			List<Object> rows = new ArrayList<>();
			for(int index = 127; index >= 0; index--)
				rows.add(row(reference(owner("owner"), "r-" + index), List.of(left,right,left), false));
			rows.add(row(null, List.of(), true));
			Object defaults = defaults(rows);
			CountingSet supported = new CountingSet(Set.of(state(left),state(right)));
			List<CandidateRealizationReference> first = actual(defaults, supported);
			assertIdentityOrder(explicit(rows, supported), first);
			long comparisons = metrics.snapshot().canonicalComparisons();
			supported.containsCalls = 0;
			List<CandidateRealizationReference> second = actual(defaults, supported);
			Assert.assertEquals("hot query must visit unique successors, not repeated row edges",
				2, supported.containsCalls);
			Assert.assertSame("one immutable canonical reference list belongs to these exact rows", first, second);
			Assert.assertEquals("hot query does no canonical sorting", comparisons,
				metrics.snapshot().canonicalComparisons());
			Assert.assertThrows(UnsupportedOperationException.class, () -> second.add(first.get(0)));
		}
		finally { PlacementIdentity.endAnalysisScope(); }
	}

	@Test
	public void populatedCacheCannotAdmitAnUnsupportedOrForeignSuccessor() throws Exception {
		CompiledHopKey owner = owner("dependency");
		Object dependency = dependency(owner, 7);
		Object other = dependency(owner("other"), 8);
		CandidateRealizationReference retained = reference(owner("result"), "retained");
		CandidateRealizationReference conditional = reference(owner("result2"), "conditional");
		List<Object> rows = List.of(row(retained, List.of(other), false),
			row(conditional, List.of(dependency), false), row(null, List.of(), true));
		Object defaults = defaults(rows);
		List<CandidateRealizationReference> full = actual(defaults, Set.of(state(dependency),state(other)));
		Assert.assertEquals(2, full.size());
		Set<Object> partial = Set.of(state(other));
		assertIdentityOrder(explicit(rows, partial), actual(defaults, partial));
		Assert.assertEquals(List.of(retained), actual(defaults, partial));
		Object foreign = dependency(owner("dependency"), 7);
		Assert.assertEquals(owner, field(foreign, "key"));
		Assert.assertNotSame(owner, field(foreign, "key"));
		Set<Object> foreignSupported = Set.of(state(foreign),state(other));
		Assert.assertEquals(List.of(retained), actual(defaults, foreignSupported));
		Assert.assertSame(full, actual(defaults, Set.of(state(dependency),state(other))));
	}

	@Test
	public void emptyRowsNullGroundAndDuplicateAuthorityPreserveLegacyFiltering() throws Exception {
		Object dependency = dependency(owner("dep"), 1);
		CandidateRealizationReference first = reference(owner("same"), "same-realization");
		CandidateRealizationReference equal = reference(first.rule().parentOccurrence(), "same-realization");
		Assert.assertEquals(first, equal);
		Assert.assertNotSame(first, equal);
		List<Object> rows = List.of(row(first, List.of(dependency), false),
			row(equal, List.of(), true), row(null, List.of(), true),
			row(reference(owner("empty"), "not-grounded"), List.of(), false));
		Object defaults = defaults(rows);
		assertIdentityOrder(explicit(rows, Set.of()), actual(defaults, Set.of()));
		Assert.assertSame(equal, actual(defaults, Set.of()).get(0));
		assertIdentityOrder(explicit(rows, Set.of(state(dependency))),
			actual(defaults, Set.of(state(dependency))));
		Assert.assertSame(first, actual(defaults, Set.of(state(dependency))).get(0));
		Assert.assertTrue(actual(defaults(List.of(row(null, List.of(), true))), Set.of()).isEmpty());
		Assert.assertTrue(actual(defaults(List.of(row(first, List.of(), false))), Set.of()).isEmpty());
		// Explicit/fixed overlay lists use the unchanged query-local filtering too.
		assertIdentityOrder(explicit(rows, Set.of()), actual(rows, Set.of()));
	}

	@Test
	public void randomSupportWithdrawalsAndRestoresMatchExplicitFirstAuthorityOrder() throws Exception {
		Random random = new Random(273754L);
		for(int trial = 0; trial < 120; trial++) {
			List<Object> deps = new ArrayList<>();
			for(int index = 0; index < 4; index++)
				deps.add(dependency(owner("dep-" + trial + '-' + index), index + 1));
			List<Object> rows = new ArrayList<>();
			CompiledHopKey result = owner("result-" + trial);
			for(int index = 0; index < 18; index++) {
				List<Object> chosen = new ArrayList<>();
				for(Object dependency : deps)
					if(random.nextBoolean()) chosen.add(dependency);
				CandidateRealizationReference ref = index % 7 == 0 ? null
					: reference(result, "lineage-" + random.nextInt(7));
				rows.add(row(ref, chosen, trial % 2 == 0 || random.nextBoolean()));
			}
			Object defaults = defaults(rows);
			Set<Object> full = new HashSet<>();
			for(Object dependency : deps) full.add(state(dependency));
			assertIdentityOrder(explicit(rows, full), actual(defaults, full));
			for(int mask = 0; mask < 16; mask++) {
				Set<Object> supported = new HashSet<>();
				for(int index = 0; index < deps.size(); index++)
					if((mask & (1 << index)) != 0) supported.add(state(deps.get(index)));
				assertIdentityOrder(explicit(rows, supported), actual(defaults, supported));
			}
			assertIdentityOrder(explicit(rows, full), actual(defaults, full));
		}
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> actual(Object rows, Set<?> supported) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"canonicalSupportedReferences", List.class, Set.class);
		method.setAccessible(true);
		return (List<CandidateRealizationReference>)method.invoke(null, rows, supported);
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRealizationReference> explicit(List<Object> rows, Set<?> supported)
		throws Exception {
		List<CandidateRealizationReference> references = new ArrayList<>();
		for(Object row : rows) {
			CandidateRealizationReference ref = (CandidateRealizationReference)field(row, "realization");
			List<Object> deps = (List<Object>)field(row, "dependencies");
			boolean keep = (boolean)field(row, "directGround") || !deps.isEmpty();
			for(Object dep : deps) keep &= supported.contains(state(dep));
			if(ref != null && keep) references.add(ref);
		}
		Method canonical = NativePlacementContinuity.class.getDeclaredMethod("canonicalReferences", List.class);
		canonical.setAccessible(true);
		return (List<CandidateRealizationReference>)canonical.invoke(null, references);
	}

	private static void assertIdentityOrder(List<CandidateRealizationReference> expected,
		List<CandidateRealizationReference> actual) {
		Assert.assertEquals(expected, actual);
		for(int index = 0; index < expected.size(); index++) Assert.assertSame(expected.get(index), actual.get(index));
	}

	private static Object defaults(List<Object> rows) throws Exception {
		Constructor<?> c = nested("DefaultAlternativeList").getDeclaredConstructor(List.class);
		c.setAccessible(true); return c.newInstance(rows);
	}
	private static Object row(CandidateRealizationReference ref, List<Object> deps, boolean ground) throws Exception {
		Constructor<?> c = nested("SelectedCandidateProof").getDeclaredConstructor(
			CandidateRealizationReference.class, List.class, boolean.class, nested("NativePoolWitness"));
		c.setAccessible(true); return c.newInstance(ref, deps, ground, witness());
	}
	private static Object dependency(CompiledHopKey owner, int handle) throws Exception {
		Constructor<?> c = nested("CandidateProofDependency").getDeclaredConstructor(CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, nested("NativePoolWitness"), int.class, boolean.class);
		c.setAccessible(true); return c.newInstance(owner, reference(owner, "source-" + handle), handle, witness(), 0, false);
	}
	private static Object state(Object dep) throws Exception { return field(dep, "state"); }
	private static Object field(Object value, String name) throws Exception {
		Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
	}
	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}
	private static Object witness() throws Exception {
		Constructor<?> c = nested("NativePoolWitness").getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		c.setAccessible(true); return c.newInstance(FType.ROW, List.of("worker:8001"), List.of(), true);
	}
	private static CandidateRealizationReference reference(CompiledHopKey owner, String lineage) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		PlacementEmissionState emission = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);
		return CandidateRealizationReference.of(rule,
			CandidateEmissionRealization.nativeLineage(emission, lineage, List.of(), List.of()));
	}
	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey("canonical-support", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("canonical-support", "main", "call", "compiled", region, id, "origin");
	}
	private static final class CountingSet extends AbstractSet<Object> {
		private final Set<?> values;
		private int containsCalls;
		private CountingSet(Set<?> values) { this.values = values; }
		@Override public boolean contains(Object value) { containsCalls++; return values.contains(value); }
		@Override public Iterator<Object> iterator() { return values.stream().map(v -> (Object)v).iterator(); }
		@Override public int size() { return values.size(); }
	}
}
