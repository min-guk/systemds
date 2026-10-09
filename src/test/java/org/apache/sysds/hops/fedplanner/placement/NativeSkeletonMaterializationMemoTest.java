/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
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

public class NativeSkeletonMaterializationMemoTest {
	@Test
	public void publicQueriesReuseMaterializedSkeletonsWhenResultAndTopologyMemosAreDisabled()
		throws Exception {
		synchronized(NativeSkeletonMaterializationMemoTest.class) {
			String entriesKey = "sysds.fedplanner.continuityTopology.maxEntries";
			String oldEntries = System.getProperty(entriesKey);
			try {
				System.setProperty(entriesKey, "0");
				Object fixture = fixture();
				DurableAnchorKey pool = new DurableAnchorKey("integration-pool", FType.FULL,
					List.of(new AnchorPartition("worker:8001", List.of(0L, 0L),
						List.of(8L, 2L))));
				Object seed = invokeNamed(fixture, "federatedSource", "integration-seed", pool);
				Object root = invokeNamed(fixture, "unary", "integration-root", OpOp1.LOG,
					seed, false);
				List<CandidateInputState> seedInputs = List.of(
					CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
				CandidateRealizationReference seedReference =
					(CandidateRealizationReference)invokeNamed(fixture, "reference", seed, seedInputs);
				List<CandidateInputState> rootInputs =
					List.of(CandidateInputState.present(FType.FULL));
				invokeNamed(fixture, "withClauses", root, rootInputs,
					List.of(new CandidateRealizationSupportClause(List.of(), List.of(
						CandidateRealizationInputBinding.direct(0, seedReference)))));
				CandidateRuleFact fact = (CandidateRuleFact)invokeNamed(
					fixture, "fact", root, rootInputs);
				CandidateRealizationReference rootReference =
					(CandidateRealizationReference)invokeNamed(fixture, "reference", root, rootInputs);
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity resolver = (NativePlacementContinuity)invokeNamed(
					fixture, "resolver", metrics, 0, 0L);

				NativePlacementContinuity.CandidateSupportResult first =
					resolver.proveCandidateSupport(rootReference, pool);
				long requests = directWork(metrics, "SKELETON_MATERIALIZATION_REQUESTS");
				long reuses = directWork(metrics, "SKELETON_MATERIALIZATION_REUSES");
				NativePlacementContinuity.CandidateSupportResult second =
					resolver.proveCandidateSupport(rootReference, pool);
				Assert.assertEquals(first.proofs(), second.proofs());
				Assert.assertEquals(first.dependencyOccurrences(), second.dependencyOccurrences());
				Assert.assertTrue("the second uncached public query reaches skeleton materialization",
					directWork(metrics, "SKELETON_MATERIALIZATION_REQUESTS") > requests);
				Assert.assertTrue("the exact prepared support-list identity is reused",
					directWork(metrics, "SKELETON_MATERIALIZATION_REUSES") > reuses);
				Assert.assertTrue("the public fixture must install a real owned candidate fact",
					fact.allowedEmissionFacts().stream().anyMatch(emission ->
						!emission.realizations().isEmpty()));
			}
			finally {
				restoreProperty(entriesKey, oldEntries);
			}
		}
	}
	@Test
	public void exactTemplateAndSupportIdentityReuseOneImmutableMaterialization()
		throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			NativePlacementContinuity resolver = empty(metrics);
			Object witness = witness();
			CompiledHopKey leftOwner = owner("left");
			CompiledHopKey rightOwner = owner("right");
			CandidateRealizationReference left = reference(leftOwner, "left-reference");
			CandidateRealizationReference right = reference(rightOwner, "right-reference");
			List<CandidateRealizationReference> support = List.of(left, right);
			Object template = template(List.of(
				templateDependency(leftOwner, 0, witness, 0),
				templateDependency(rightOwner, 1, witness, 1)));

			long before = metrics.snapshot().structuralHandleLookups();
			List<?> first = materialize(resolver, template, support);
			long afterFirst = metrics.snapshot().structuralHandleLookups();
			List<?> second = materialize(resolver, template, support);
			Assert.assertSame("the exact template/support pair reuses its immutable dependency list",
				first, second);
			Assert.assertEquals(2, afterFirst - before);
			Assert.assertEquals("a memo hit performs no candidate-handle lookups", 0,
				metrics.snapshot().structuralHandleLookups() - afterFirst);
			assertPinned(first, 0, leftOwner, left);
			assertPinned(first, 1, rightOwner, right);
			assertImmutable(first);

			List<CandidateRealizationReference> equalDistinct =
				Collections.unmodifiableList(new ArrayList<>(support));
			Assert.assertEquals(support, equalDistinct);
			Assert.assertNotSame(support, equalDistinct);
			List<?> rebound = materialize(resolver, template, equalDistinct);
			Assert.assertNotSame("equal support with distinct list identity must rebind", first, rebound);
			assertPinned(rebound, 0, leftOwner, left);
			assertPinned(rebound, 1, rightOwner, right);

			CandidateRealizationReference copiedLeft = new CandidateRealizationReference(
				left.rule(), left.realization());
			CandidateRealizationReference copiedRight = new CandidateRealizationReference(
				right.rule(), right.realization());
			Assert.assertEquals(left, copiedLeft);
			Assert.assertNotSame(left, copiedLeft);
			List<CandidateRealizationReference> rebuiltSupport =
				Collections.unmodifiableList(List.of(copiedLeft, copiedRight));
			List<?> rebuilt = materialize(resolver, template, rebuiltSupport);
			assertPinned(rebuilt, 0, leftOwner, copiedLeft);
			assertPinned(rebuilt, 1, rightOwner, copiedRight);
			Assert.assertNotSame("rebinding must retain current source identity, not an interned copy",
				left, field(rebuilt.get(0), "clausePinned"));
			List<?> restored = materialize(resolver, template, support);
			Assert.assertNotSame("one template retains only its current support-identity binding",
				first, restored);
			assertPinned(restored, 0, leftOwner, left);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void cachedSkeletonRemainsIndependentOfQuerySpecificFixedOverlays()
		throws Exception {
		NativePlacementContinuity resolver = empty(null);
		Object witness = witness();
		CompiledHopKey fixedOwner = owner("fixed-owner");
		CandidateRealizationReference clausePinned = reference(fixedOwner, "clause-pinned");
		List<CandidateRealizationReference> support = List.of(clausePinned);
		Object template = template(List.of(templateDependency(fixedOwner, 0, witness, 0)));
		List<?> skeletons = materialize(resolver, template, support);
		CandidateRealizationReference firstPin = reference(fixedOwner, "query-first");
		CandidateRealizationReference secondPin = reference(fixedOwner, "query-second");

		List<?> first = overlay(resolver, skeletons,
			fixedBoundary(fixedOwner, firstPin, candidateHandle(resolver, firstPin)));
		List<?> second = overlay(resolver, skeletons,
			fixedBoundary(fixedOwner, secondPin, candidateHandle(resolver, secondPin)));
		Assert.assertNotSame(first, second);
		Assert.assertSame(firstPin, field(first.get(0), "realization"));
		Assert.assertSame(secondPin, field(second.get(0), "realization"));
		Assert.assertSame("fixed overlays cannot mutate the cached clause authority",
			clausePinned, field(skeletons.get(0), "clausePinned"));
		Assert.assertSame("the next materialization still reuses the unoverlaid skeleton list",
			skeletons, materialize(resolver, template, support));
	}

	@Test
	public void memoIsResolverLocalAcrossArenaEndAndOverflow() throws Exception {
		String capacityKey = "sysds.fedplanner.structuralArena.maxEntries";
		String oldCapacity = System.getProperty(capacityKey);
		try {
			System.setProperty(capacityKey, "1024");
			PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
			Object witness = witness();
			CompiledHopKey sourceOwner = owner("resolver-local-source");
			CandidateRealizationReference source = reference(sourceOwner, "resolver-local-reference");
			List<CandidateRealizationReference> support = List.of(source);
			Object template = template(List.of(templateDependency(sourceOwner, 0, witness, 0)));
			NativePlacementContinuity firstResolver = empty(null);
			List<?> first = materialize(firstResolver, template, support);
			Assert.assertSame(first, materialize(firstResolver, template, support));
			PlacementIdentity.endAnalysisScope();
			Assert.assertSame("arena lifetime cannot invalidate resolver-owned materialization",
				first, materialize(firstResolver, template, support));

			System.setProperty(capacityKey, "0");
			PlacementIdentity.beginAnalysisScope(new SearchSpaceMetrics());
			NativePlacementContinuity secondResolver = empty(null);
			List<?> second = materialize(secondResolver, template, support);
			Assert.assertNotSame("a fresh resolver cannot inherit materialized handles", first, second);
			Assert.assertNotEquals("an exhausted fresh arena must rebind instead of retaining the old handle",
				field(first.get(0), "clausePinnedHandle"),
				field(second.get(0), "clausePinnedHandle"));
			Assert.assertSame(source, field(first.get(0), "clausePinned"));
			Assert.assertSame(source, field(second.get(0), "clausePinned"));
			Assert.assertSame(second, materialize(secondResolver, template, support));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
			restoreProperty(capacityKey, oldCapacity);
		}
	}

	@Test
	public void failedMaterializationsAreNotCached() throws Exception {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			NativePlacementContinuity resolver = empty(metrics);
			Object witness = witness();
			CompiledHopKey exactOwner = owner("exact-owner");
			CandidateRealizationReference exact = reference(exactOwner, "exact-reference");
			List<CandidateRealizationReference> support = List.of(exact);
			Object valid = template(List.of(
				templateDependency(exactOwner, 0, witness, 0)));
			List<?> retained = materialize(resolver, valid, support);
			Assert.assertNull(materialize(resolver, valid, List.of()));
			Assert.assertSame("failed replacement validation retains the prior exact binding",
				retained, materialize(resolver, valid, support));
			Object invalidIndex = template(List.of(
				templateDependency(exactOwner, 1, witness, 0)));
			Object foreignOwner = template(List.of(
				templateDependency(owner("exact-owner"), 0, witness, 0)));

			Assert.assertNull(materialize(resolver, invalidIndex, support));
			Assert.assertNull(materialize(resolver, invalidIndex, support));
			Assert.assertNull(materialize(resolver, foreignOwner, support));
			Assert.assertNull(materialize(resolver, foreignOwner, support));
			Assert.assertEquals("failed results are never installed in the memo", 1,
				memoSize(resolver));
			Assert.assertEquals(1, dependencySlots(resolver));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void unpinnedDependenciesAlsoReuseByExactTemplateAndSupportIdentity()
		throws Exception {
		NativePlacementContinuity resolver = empty(null);
		Object witness = witness();
		CompiledHopKey dependencyOwner = owner("unpinned");
		Object template = template(List.of(
			templateDependency(dependencyOwner, -1, witness, 3)));
		List<CandidateRealizationReference> emptySupport = List.of();
		List<?> first = materialize(resolver, template, emptySupport);
		Assert.assertSame(first, materialize(resolver, template, emptySupport));
		Assert.assertSame(dependencyOwner, field(first.get(0), "key"));
		Assert.assertNull(field(first.get(0), "clausePinned"));
		assertImmutable(first);
	}

	@Test
	public void revisionStartsWithNoMaterializedSkeletonBindings() throws Exception {
		NativePlacementContinuity resolver = empty(null);
		Object witness = witness();
		CompiledHopKey sourceOwner = owner("revision-source");
		CandidateRealizationReference source = reference(sourceOwner, "revision-reference");
		List<CandidateRealizationReference> support = List.of(source);
		Object template = template(List.of(templateDependency(sourceOwner, 0, witness, 0)));
		List<?> first = materialize(resolver, template, support);
		NativePlacementContinuity revision = resolver.nextRevision(List.of());
		Assert.assertEquals(0, memoSize(revision));
		Assert.assertEquals(0, dependencySlots(revision));
		List<?> rebound = materialize(revision, template, support);
		Assert.assertNotSame("materialized resolver handles are never copied into a revision",
			first, rebound);
		Assert.assertSame(source, field(rebound.get(0), "clausePinned"));
	}

	@Test
	public void entryCapacityAdmits4096TemplatesAndBypassesLaterTemplates()
		throws Exception {
		Assert.assertEquals(4096, staticInt("MATERIALIZED_SKELETON_MAX_ENTRIES"));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = empty(metrics);
		Object witness = witness();
		List<CandidateRealizationReference> support = List.of();
		List<Object> admitted = new ArrayList<>();
		for(int index = 0; index < 4096; index++) {
			Object template = template(List.of(templateDependency(
				owner("entry-" + index), -1, witness, index)));
			admitted.add(template);
			List<?> first = materialize(resolver, template, support);
			Assert.assertSame(first, materialize(resolver, template, support));
		}
		Assert.assertEquals(4096, memoSize(resolver));
		Assert.assertEquals(4096, dependencySlots(resolver));
		List<CandidateRealizationReference> distinctEmpty =
			Collections.unmodifiableList(new ArrayList<>());
		Assert.assertNotSame(support, distinctEmpty);
		List<?> retained = materialize(resolver, admitted.get(0), support);
		List<?> replacement = materialize(resolver, admitted.get(0), distinctEmpty);
		Assert.assertNotSame("an existing template can replace its binding at the entry ceiling",
			retained, replacement);
		Assert.assertEquals(4096, memoSize(resolver));
		Assert.assertEquals(4096, dependencySlots(resolver));
		Object overflow = template(List.of(templateDependency(
			owner("entry-overflow"), -1, witness, 4096)));
		List<?> firstOverflow = materialize(resolver, overflow, support);
		List<?> secondOverflow = materialize(resolver, overflow, support);
		Assert.assertNotSame("a valid result beyond the entry capacity remains uncached",
			firstOverflow, secondOverflow);
		Assert.assertEquals(4096, memoSize(resolver));
		Assert.assertEquals(4096, dependencySlots(resolver));
		Assert.assertTrue(directWork(metrics, "SKELETON_MATERIALIZATION_ADMISSIONS") >= 4097);
		Assert.assertTrue(directWork(metrics, "SKELETON_MATERIALIZATION_BYPASSES") >= 2);
	}

	@Test
	public void dependencyCapacityAllowsZeroSlotEntriesAndRejectsOversizedResults()
		throws Exception {
		Assert.assertEquals(16384, staticInt("MATERIALIZED_SKELETON_MAX_DEPENDENCIES"));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = empty(metrics);
		Object witness = witness();
		List<CandidateRealizationReference> support = List.of();
		Object full = template(unpinnedDependencies(witness, 16384, "full"));
		List<?> admitted = materialize(resolver, full, support);
		Assert.assertSame(admitted, materialize(resolver, full, support));
		Assert.assertEquals(1, memoSize(resolver));
		Assert.assertEquals(16384, dependencySlots(resolver));
		List<CandidateRealizationReference> distinctEmpty =
			Collections.unmodifiableList(new ArrayList<>());
		Assert.assertNotSame(support, distinctEmpty);
		List<?> replacement = materialize(resolver, full, distinctEmpty);
		Assert.assertNotSame("same-slot replacement remains admissible at the slot ceiling",
			admitted, replacement);
		Assert.assertEquals(1, memoSize(resolver));
		Assert.assertEquals(16384, dependencySlots(resolver));
		admitted = replacement;

		Object zero = template(List.of());
		List<?> empty = materialize(resolver, zero, support);
		Assert.assertSame("a zero-slot result remains admissible at the slot ceiling",
			empty, materialize(resolver, zero, support));
		Assert.assertEquals(2, memoSize(resolver));
		Assert.assertEquals(16384, dependencySlots(resolver));

		Object oneMore = template(List.of(templateDependency(
			owner("one-more"), -1, witness, 0)));
		Assert.assertNotSame(materialize(resolver, oneMore, support),
			materialize(resolver, oneMore, support));
		Object oversized = template(unpinnedDependencies(witness, 16385, "oversized"));
		Assert.assertNotSame("an individually oversized valid result is returned but not retained",
			materialize(resolver, oversized, support),
			materialize(resolver, oversized, support));
		Assert.assertEquals(2, memoSize(resolver));
		Assert.assertEquals(16384, dependencySlots(resolver));
		Assert.assertSame("capacity bypass cannot displace an existing exact binding",
			admitted, materialize(resolver, full, distinctEmpty));
		Assert.assertTrue(directWork(metrics, "SKELETON_MATERIALIZATION_BYPASSES") >= 4);
	}

	@Test
	public void replacingOneTemplateBindingAdjustsSlotsWithoutGrowingEntries()
		throws Exception {
		NativePlacementContinuity resolver = empty(null);
		Object witness = witness();
		CompiledHopKey leftOwner = owner("replacement-left");
		CompiledHopKey rightOwner = owner("replacement-right");
		Object template = template(List.of(
			templateDependency(leftOwner, 0, witness, 0),
			templateDependency(rightOwner, 1, witness, 1)));
		List<CandidateRealizationReference> firstSupport = List.of(
			reference(leftOwner, "first-left"), reference(rightOwner, "first-right"));
		List<CandidateRealizationReference> secondSupport = List.of(
			reference(leftOwner, "second-left"), reference(rightOwner, "second-right"));
		List<?> first = materialize(resolver, template, firstSupport);
		List<?> second = materialize(resolver, template, secondSupport);
		Assert.assertNotSame(first, second);
		Assert.assertEquals(1, memoSize(resolver));
		Assert.assertEquals("replacement subtracts the old slots before admitting the new binding",
			2, dependencySlots(resolver));
		assertPinned(second, 0, leftOwner, secondSupport.get(0));
		assertPinned(second, 1, rightOwner, secondSupport.get(1));
		Assert.assertSame(second, materialize(resolver, template, secondSupport));
	}

	@Test
	public void metricsAreOptionalAndResetDoesNotClearResolverMemo() throws Exception {
		Object witness = witness();
		CompiledHopKey sourceOwner = owner("metrics-source");
		CandidateRealizationReference source = reference(sourceOwner, "metrics-reference");
		List<CandidateRealizationReference> support = List.of(source);
		Object template = template(List.of(templateDependency(sourceOwner, 0, witness, 0)));
		NativePlacementContinuity off = empty(null);
		List<?> offFirst = materialize(off, template, support);
		Assert.assertSame(offFirst, materialize(off, template, support));

		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity on = empty(metrics);
		List<?> first = materialize(on, template, support);
		Assert.assertEquals("metrics cannot change dependency field order or authority",
			offFirst, first);
		Assert.assertSame(sourceOwner, field(first.get(0), "key"));
		Assert.assertSame(source, field(first.get(0), "clausePinned"));
		Assert.assertNotEquals("the real resolver-local pinned handle is valid", 0,
			((Number)field(first.get(0), "clausePinnedHandle")).intValue());
		Assert.assertSame(first, materialize(on, template, support));
		Assert.assertEquals(2, directWork(metrics, "SKELETON_MATERIALIZATION_REQUESTS"));
		Assert.assertEquals(1, directWork(metrics, "SKELETON_MATERIALIZATION_REUSES"));
		Assert.assertEquals(1, directWork(metrics, "SKELETON_MATERIALIZATION_DEPENDENCIES_BUILT"));
		Assert.assertEquals(1, directWork(metrics, "SKELETON_MATERIALIZATION_ADMISSIONS"));
		Assert.assertEquals(0, directWork(metrics, "SKELETON_MATERIALIZATION_BYPASSES"));
		metrics.reset();
		Assert.assertSame("metric reset must not clear resolver-owned reusable work",
			first, materialize(on, template, support));
		Assert.assertEquals(1, directWork(metrics, "SKELETON_MATERIALIZATION_REQUESTS"));
		Assert.assertEquals(1, directWork(metrics, "SKELETON_MATERIALIZATION_REUSES"));
		Assert.assertEquals(0,
			directWork(metrics, "SKELETON_MATERIALIZATION_DEPENDENCIES_BUILT"));
	}

	private static void assertPinned(List<?> dependencies, int index,
		CompiledHopKey owner, CandidateRealizationReference expected) throws Exception {
		Object dependency = dependencies.get(index);
		Assert.assertSame(owner, field(dependency, "key"));
		Assert.assertSame(expected, field(dependency, "clausePinned"));
	}

	private static List<Object> unpinnedDependencies(Object witness, int count, String prefix)
		throws Exception {
		List<Object> dependencies = new ArrayList<>(count);
		for(int index = 0; index < count; index++)
			dependencies.add(templateDependency(owner(prefix + '-' + index), -1,
				witness, index));
		return dependencies;
	}

	@SuppressWarnings("unchecked")
	private static int memoSize(NativePlacementContinuity resolver) throws Exception {
		return ((Map<Object,Object>)field(resolver, "materializedSkeletons")).size();
	}

	private static int dependencySlots(NativePlacementContinuity resolver) throws Exception {
		return ((Number)field(resolver, "materializedSkeletonDependencies")).intValue();
	}

	private static int staticInt(String name) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(null);
	}

	private static long directWork(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(SearchSpaceMetrics.DirectWork.valueOf(name));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void assertImmutable(List<?> dependencies) {
		try {
			((List)dependencies).add(null);
			Assert.fail("materialized dependencies must be immutable");
		}
		catch(UnsupportedOperationException expected) {
			// Expected immutable materialization.
		}
	}

	private static List<?> materialize(NativePlacementContinuity resolver,
		Object template, List<CandidateRealizationReference> support) throws Exception {
		return (List<?>)method(NativePlacementContinuity.class, "materializeSkeletonTemplate",
			nested("SkeletonTemplate"), List.class).invoke(resolver, template, support);
	}

	private static Object fixture() throws Exception {
		Class<?> fixture = Class.forName(NativePlacementContinuityTest.class.getName() + "$Fixture");
		Constructor<?> constructor = fixture.getDeclaredConstructor(FType.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.FULL);
	}

	private static Object invokeNamed(Object target, String name, Object... arguments)
		throws Exception {
		for(Method method : target.getClass().getDeclaredMethods()) {
			if(!method.getName().equals(name) || method.getParameterCount() != arguments.length)
				continue;
			Class<?>[] parameters = method.getParameterTypes();
			boolean compatible = true;
			for(int index = 0; index < parameters.length; index++) {
				Class<?> parameter = boxed(parameters[index]);
				if(arguments[index] != null && !parameter.isInstance(arguments[index])) {
					compatible = false;
					break;
				}
			}
			if(!compatible)
				continue;
			method.setAccessible(true);
			return method.invoke(target, arguments);
		}
		throw new NoSuchMethodException(target.getClass().getName() + '.' + name);
	}

	private static Class<?> boxed(Class<?> type) {
		if(type == boolean.class)
			return Boolean.class;
		if(type == int.class)
			return Integer.class;
		if(type == long.class)
			return Long.class;
		return type;
	}

	private static List<?> overlay(NativePlacementContinuity resolver, List<?> skeletons,
		Object fixed) throws Exception {
		return (List<?>)method(NativePlacementContinuity.class, "overlayDependencies",
			List.class, nested("FixedCandidateBoundary")).invoke(resolver, skeletons, fixed);
	}

	private static Object template(List<?> dependencies) throws Exception {
		Constructor<?> constructor = nested("SkeletonTemplate").getDeclaredConstructor(
			Hop.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(new DataOp("skeleton-owner", DataType.MATRIX,
			ValueType.FP64, OpOpData.TRANSIENTREAD, "skeleton-owner", 8, 2, 16, 1000),
			dependencies);
	}

	private static Object templateDependency(CompiledHopKey owner, int supportIndex,
		Object witness, int inputPosition) throws Exception {
		Constructor<?> constructor = nested("SkeletonTemplateDependency").getDeclaredConstructor(
			CompiledHopKey.class, int.class, nested("NativePoolWitness"), int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner, supportIndex, witness, inputPosition);
	}

	private static Object fixedBoundary(CompiledHopKey owner,
		CandidateRealizationReference reference, int handle) throws Exception {
		Constructor<?> constructor = nested("FixedCandidateBoundary").getDeclaredConstructor(
			CompiledHopKey.class, CandidateRealizationReference.class, int.class);
		constructor.setAccessible(true);
		return constructor.newInstance(owner, reference, handle);
	}

	private static int candidateHandle(NativePlacementContinuity resolver,
		CandidateRealizationReference reference) throws Exception {
		return (int)method(NativePlacementContinuity.class, "candidateHandle",
			CandidateRealizationReference.class).invoke(resolver, reference);
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
			emission, "skeleton:" + id, List.of(), List.of()));
	}

	private static CompiledHopKey owner(String id) {
		ControlRegionKey region = new ControlRegionKey(
			"skeleton-materialization", "main", List.of("root"), "call", "compiled");
		return new CompiledHopKey("skeleton-materialization", "main", "call", "compiled",
			region, id, "origin-" + id);
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

	private static void restoreProperty(String key, String value) {
		if(value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}
}
