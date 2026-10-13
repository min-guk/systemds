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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuity.CandidateSupportResult;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeOrdinaryAcyclicRootReuseTest {
	@Test
	public void equalOrdinaryRootRelationsShareAcyclicSupportWithoutExpandingNativeSibling()
		throws Exception {
		Object scenario = scenario();
		install(scenario, true);
		NativePlacementContinuity eagerResolver = resolver(fixture(scenario), null);
		CandidateSupportResult eagerA = query(scenario, "a-choice", eagerResolver);
		CandidateSupportResult eagerZ = query(scenario, "z-choice", eagerResolver);

		NativeContinuitySupportClauses lazy = install(scenario, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = resolver(fixture(scenario), metrics);
		CandidateSupportResult actualA = query(scenario, "a-choice", resolver);
		long graphs = metrics.snapshot().proofGraphsBuilt();
		long hits = metrics.snapshot().supportMemoHits();
		CandidateSupportResult actualZ = query(scenario, "z-choice", resolver);

		assertAuthority(eagerA, actualA);
		assertAuthority(eagerZ, actualZ);
		Assert.assertEquals("the exact ordinary root relation reuses one of the two pool witnesses",
			graphs + 1, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue("the second source is served by bounded acyclic-root support",
			metrics.snapshot().supportMemoHits() > hits);
		Assert.assertEquals("root sharing must not enumerate the native sibling relation",
			1, lazy.materializedHandleCount());
		Assert.assertTrue("the root relation and templates are charged to one aggregate unit budget",
			longField(resolver, "acyclicRootRetainedUnits") > 0);
		Assert.assertTrue("the root relation and templates are charged to one aggregate byte budget",
			longField(resolver, "acyclicRootRetainedEstimatedBytes") > 0);
	}

	@Test
	public void aggregateRootBudgetRejectsSharingWithoutRepeatingTopologyConstruction()
		throws Exception {
		synchronized(NativeOrdinaryAcyclicRootReuseTest.class) {
			String key = "sysds.fedplanner.continuitySupportMemo.maxTemplates";
			String prior = System.getProperty(key);
			try {
				System.setProperty(key, "1");
				Object scenario = scenario();
				NativeContinuitySupportClauses lazy = install(scenario, false);
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity resolver = resolver(fixture(scenario), metrics);
				query(scenario, "a-choice", resolver);
				long graphs = metrics.snapshot().proofGraphsBuilt();
				long topologyBuilds = metrics.snapshot().topologyExpansionBuilds();
				query(scenario, "z-choice", resolver);

				Assert.assertEquals("the aggregate cap rejects the shared root entry",
					graphs + 2, metrics.snapshot().proofGraphsBuilt());
				Assert.assertEquals("the rejected root memo does not rebuild resident ordinary coverage",
					topologyBuilds, metrics.snapshot().topologyExpansionBuilds());
				Assert.assertTrue(((Map<?,?>)field(resolver, "acyclicRootSupportMemo")).isEmpty());
				Assert.assertEquals(0, longField(resolver, "acyclicRootRetainedUnits"));
				Assert.assertEquals(0, longField(resolver,
					"acyclicRootRetainedEstimatedBytes"));
				Assert.assertEquals(1, lazy.materializedHandleCount());
			}
			finally {
				if(prior == null)
					System.clearProperty(key);
				else
					System.setProperty(key, prior);
			}
		}
	}

	@Test
	public void emptyTemplateEntryStillChargesItsRetainedOccurrenceFootprint()
		throws Exception {
		synchronized(NativeOrdinaryAcyclicRootReuseTest.class) {
			String key = "sysds.fedplanner.continuitySupportMemo.maxEstimatedBytes";
			String prior = System.getProperty(key);
			try {
				System.setProperty(key, "256");
				Object scenario = scenario();
				install(scenario, false);
				NativePlacementContinuity resolver = resolver(fixture(scenario),
					new SearchSpaceMetrics());
				CandidateRealizationReference root = (CandidateRealizationReference)invoke(
					scenario, "installedReference", "a-choice");
				Object witness = invoke(resolver, "nativeWitness", invoke(scenario, "pool"));
				Class<?> keyType = nested("AcyclicRootSupportKey");
				Constructor<?> keyConstructor = keyType.getDeclaredConstructor(
					CompiledHopKey.class, nested("NativePoolWitness"), List.class);
				keyConstructor.setAccessible(true);
				Object rootKey = keyConstructor.newInstance(
					root.rule().parentOccurrence(), witness, List.of());

				Set<CompiledHopKey> occurrences = Collections.newSetFromMap(
					new IdentityHashMap<>());
				for(CandidateRuleFact fact : candidateFacts(fixture(scenario)))
					occurrences.add(fact.key().parentOccurrence());
				Assert.assertTrue("the fixture must retain a nontrivial invalidation footprint",
					occurrences.size() >= 3);
				Class<?> entryType = nested("SupportMemoEntry");
				Constructor<?> entryConstructor = entryType.getDeclaredConstructor(
					CandidateRealizationReference.class, List.class, Set.class,
					long.class, boolean.class);
				entryConstructor.setAccessible(true);
				Object entry = entryConstructor.newInstance(root, List.of(),
					Collections.unmodifiableSet(occurrences), 0L, true);
				Method cache = NativePlacementContinuity.class.getDeclaredMethod(
					"cacheAcyclicRootSupport", keyType, entryType);
				cache.setAccessible(true);
				cache.invoke(resolver, rootKey, entry);

				Assert.assertTrue("empty templates cannot hide a retained owner footprint",
					((Map<?,?>)field(resolver, "acyclicRootSupportMemo")).isEmpty());
				Assert.assertEquals(0, longField(resolver,
					"acyclicRootRetainedEstimatedBytes"));
			}
			finally {
				if(prior == null)
					System.clearProperty(key);
				else
					System.setProperty(key, prior);
			}
		}
	}

	private static Object scenario() throws Exception {
		Method method = NativeHybridUnpinnedTopologyTest.class.getDeclaredMethod(
			"scenario", String.class, List.class);
		method.setAccessible(true);
		return method.invoke(null, "m-choice", List.of("a-choice", "z-choice"));
	}

	private static Object fixture(Object scenario) throws Exception {
		return invoke(scenario, "fixture");
	}

	private static NativeContinuitySupportClauses install(Object scenario, boolean explicit)
		throws Exception {
		return (NativeContinuitySupportClauses)invoke(scenario, "install", explicit);
	}

	private static CandidateSupportResult query(Object scenario, String lineage,
		NativePlacementContinuity resolver) throws Exception {
		return (CandidateSupportResult)invoke(scenario, "queryInstalled", lineage, resolver);
	}

	private static NativePlacementContinuity resolver(Object fixture, SearchSpaceMetrics metrics)
		throws Exception {
		return (NativePlacementContinuity)invoke(fixture, "resolver", metrics, 128, 2048L);
	}

	private static Object invoke(Object receiver, String name, Object... arguments)
		throws Exception {
		for(Method method : receiver.getClass().getDeclaredMethods()) {
			if(!method.getName().equals(name) || method.getParameterCount() != arguments.length)
				continue;
			method.setAccessible(true);
			return method.invoke(receiver, arguments);
		}
		throw new NoSuchMethodException(receiver.getClass().getName() + '.' + name);
	}

	private static Object field(Object receiver, String name) throws Exception {
		Field field = receiver.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(receiver);
	}

	private static long longField(Object receiver, String name) throws Exception {
		return ((Number)field(receiver, name)).longValue();
	}

	@SuppressWarnings("unchecked")
	private static List<CandidateRuleFact> candidateFacts(Object fixture) throws Exception {
		return (List<CandidateRuleFact>)field(fixture, "candidates");
	}

	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(NativePlacementContinuity.class.getName() + '$' + name);
	}

	private static void assertAuthority(CandidateSupportResult expected,
		CandidateSupportResult actual) {
		Assert.assertEquals(expected.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			actual.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		Assert.assertEquals(expected.proofs().size(), actual.proofs().size());
		for(int proof = 0; proof < expected.proofs().size(); proof++) {
			List<CandidateRealizationInputBinding> left =
				expected.proofs().get(proof).immediateBindings();
			List<CandidateRealizationInputBinding> right =
				actual.proofs().get(proof).immediateBindings();
			Assert.assertEquals(left.size(), right.size());
			for(int binding = 0; binding < left.size(); binding++) {
				CandidateRealizationReference leftSource = left.get(binding).source();
				CandidateRealizationReference rightSource = right.get(binding).source();
				Assert.assertEquals(leftSource, rightSource);
				Assert.assertSame(leftSource.rule().parentOccurrence(),
					rightSource.rule().parentOccurrence());
			}
		}
		assertIdentitySetEquals(expected.dependencyOccurrences(), actual.dependencyOccurrences());
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey owner : expected)
			Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == owner));
	}
}
