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
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class NativeClausePinProjectionTest {
	@After
	public void clearCaches() {
		PlacementIdentity.setActiveMetrics(null);
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		PlacementIdentity.resetNormalizedSignatureCache();
	}

	@Test
	public void measuredTopologyTrustsItsIteratedOwnedClauseWithoutGenericOwnershipScan()
		throws Exception {
		FixtureAccess fixture = FixtureAccess.create();
		DurableAnchorKey pool = anchor("worker1:8001");
		Object left = fixture.source("topology-left", pool);
		Object right = fixture.source("topology-right", pool);
		Object root = fixture.binary("topology-root", left, right);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRealizationSupportClause clause =
			new CandidateRealizationSupportClause(List.of(), List.of());
		CandidateRealizationReference reference = fixture.withClauses(root, inputs, List.of(clause));
		CandidateRuleFact fact = fixture.fact(root, inputs);
		NativePlacementContinuity resolver = fixture.resolver();

		long scans = counter(resolver, "dependencySkeletonOwnerFactScans");
		Assert.assertFalse(ownedClauses(resolver).containsKey(fact));
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(reference, pool);
		Assert.assertFalse("the actual topology path must produce a complete proof", actual.isEmpty());
		Assert.assertEquals("the iterated fact/clause identity is already owned authority",
			scans, counter(resolver, "dependencySkeletonOwnerFactScans"));
		Assert.assertFalse("the trusted topology path must not construct the generic ownership map",
			ownedClauses(resolver).containsKey(fact));

		NativePlacementContinuity cold = fixture.resolver();
		Assert.assertEquals("trusted preparation must preserve ordered full proofs",
			cold.proveCandidateAlternatives(reference, pool), actual);

		Assert.assertNotNull(skeletons(resolver, fact, clause, fixture.hop(root), pool));
		Assert.assertEquals("the four-argument generic wrapper remains checked",
			scans + 1, counter(resolver, "dependencySkeletonOwnerFactScans"));
		Assert.assertTrue(ownedClauses(resolver).get(fact).contains(clause));
		CandidateRealizationSupportClause unowned =
			new CandidateRealizationSupportClause(List.of(), List.of());
		Assert.assertNotSame(clause, unowned);
		long builds = counter(resolver, "dependencySkeletonBuilds");
		Assert.assertNotNull(skeletons(resolver, fact, unowned, fixture.hop(root), pool));
		Assert.assertEquals("an equal unowned clause remains on the checked cold path",
			builds + 1, counter(resolver, "dependencySkeletonBuilds"));
	}

	@Test
	public void exactRevisionReusesClauseSupportAfterThreadCacheReset() throws Exception {
		FixtureAccess fixture = FixtureAccess.create();
		Object left = fixture.source("left", anchor("worker1:8001"));
		Object right = fixture.source("right", anchor("worker1:8001"));
		Object root = fixture.binary("root", left, right);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRealizationReference leftReference = reference(fixture.key(left), "left-support");
		CandidateRealizationReference rightReference = reference(fixture.key(right), "right-support");
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(List.of(), List.of(
			CandidateRealizationInputBinding.direct(0, leftReference),
			CandidateRealizationInputBinding.direct(1, rightReference)));
		fixture.withClauses(root, inputs, List.of(clause));
		CandidateRuleFact fact = fixture.fact(root, inputs);
		NativePlacementContinuity parent = fixture.resolver();
		List<?> first = skeletons(parent, fact, clause, fixture.hop(root), anchor("worker1:8001"));
		Assert.assertNotNull(first);
		Object projection = clausePinProjection(parent, fact, clause);
		Assert.assertNotNull(projection);

		NativePlacementContinuity exact = parent.nextRevision(fixture.facts());
		Assert.assertSame(projection, clausePinProjection(exact, fact, clause));
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		long serializations = metrics.snapshot().signatureSerializations();
		List<?> second;
		try(PlacementAnalysis.CanonicalTextScope ignored = PlacementAnalysis.beginCanonicalTextScope(0, 0)) {
			second = skeletons(exact, fact, clause, fixture.hop(root), anchor("worker2:8002"));
		}
		Assert.assertNotNull(second);
		Assert.assertEquals("an exact carried clause must not rebuild canonical support",
			serializations, metrics.snapshot().signatureSerializations());
		Assert.assertTrue("the exact support list must bypass the revision-local fallback map",
			requiredSupportMap(exact).isEmpty());
		Assert.assertSame("a new witness must reuse the exact immutable pin projection",
			projection, clausePinProjection(exact, fact, clause));

		PlacementIdentity.resetNormalizedSignatureCache();
		NativePlacementContinuity cold = fixture.resolver();
		Assert.assertEquals(skeletons(cold, fact, clause, fixture.hop(root), anchor("worker2:8002")), second);
	}

	@Test
	public void freshAndOwnerRevisionRemainColdWhileSiblingExactForksShareSupport() throws Exception {
		Scenario scenario = scenario();
		NativePlacementContinuity parent = scenario.fixture.resolver();
		skeletons(parent, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker1:8001"));
		Object parentSupport = clauseSupport(parent, scenario.fact, scenario.clause);
		Object parentProjection = clausePinProjection(parent, scenario.fact, scenario.clause);
		Assert.assertNotNull(parentSupport);
		Assert.assertNotNull(parentProjection);

		NativePlacementContinuity left = parent.nextRevision(scenario.fixture.facts());
		NativePlacementContinuity right = parent.nextRevision(scenario.fixture.facts());
		Assert.assertSame(parentSupport, clauseSupport(left, scenario.fact, scenario.clause));
		Assert.assertSame(parentSupport, clauseSupport(right, scenario.fact, scenario.clause));
		Assert.assertSame(parentProjection, clausePinProjection(left, scenario.fact, scenario.clause));
		Assert.assertSame(parentProjection, clausePinProjection(right, scenario.fact, scenario.clause));
		skeletons(left, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker2:8002"));
		skeletons(right, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker3:8003"));
		Assert.assertSame(parentSupport, clauseSupport(left, scenario.fact, scenario.clause));
		Assert.assertSame(parentSupport, clauseSupport(right, scenario.fact, scenario.clause));
		Assert.assertSame(parentProjection, clausePinProjection(left, scenario.fact, scenario.clause));
		Assert.assertSame(parentProjection, clausePinProjection(right, scenario.fact, scenario.clause));

		NativePlacementContinuity fresh = parent.freshQueryState();
		assertColdSupportBuild(fresh, scenario, "worker4:8004");
		NativePlacementContinuity ownerRevision = parent.nextOwnerRevision(
			scenario.fixture.key(scenario.root), List.of(scenario.fact));
		assertColdSupportBuild(ownerRevision, scenario, "worker5:8005");
	}

	@Test
	public void structuralDonorRehydratesOnlyCurrentClauseReferences() throws Exception {
		Scenario scenario = scenario();
		NativePlacementContinuity parent = scenario.fixture.resolver();
		List<?> baseline = skeletons(parent, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker1:8001"));
		Object donorSupport = clauseSupport(parent, scenario.fact, scenario.clause);
		Object donorProjection = clausePinProjection(parent, scenario.fact, scenario.clause);

		CandidateRealizationReference currentLeft = new CandidateRealizationReference(
			scenario.leftReference.rule(), scenario.leftReference.realization());
		CandidateRealizationReference currentRight = new CandidateRealizationReference(
			scenario.rightReference.rule(), scenario.rightReference.realization());
		CandidateRealizationSupportClause currentClause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, currentLeft),
				CandidateRealizationInputBinding.direct(1, currentRight)));
		Assert.assertEquals(scenario.clause, currentClause);
		Assert.assertNotSame(scenario.clause, currentClause);
		CandidateRuleFact currentFact = rebuiltFact(scenario.fact, currentClause);
		List<CandidateRuleFact> currentFacts = new ArrayList<>(scenario.fixture.facts());
		currentFacts.set(currentFacts.indexOf(scenario.fact), currentFact);
		NativePlacementContinuity revised = parent.nextRevision(currentFacts);
		Assert.assertNull("a distinct donor clause must not carry the old support list",
			clauseSupport(revised, currentFact, currentClause));
		Assert.assertNull("a distinct donor clause must not carry the old pin projection",
			clausePinProjection(revised, currentFact, currentClause));

		PlacementIdentity.resetNormalizedSignatureCache();
		List<?> actual = skeletons(revised, currentFact, currentClause,
			scenario.fixture.hop(scenario.root), anchor("worker1:8001"));
		Object currentSupport = clauseSupport(revised, currentFact, currentClause);
		Assert.assertNotNull(currentSupport);
		Assert.assertNotSame(donorSupport, currentSupport);
		Assert.assertNotSame(donorProjection,
			clausePinProjection(revised, currentFact, currentClause));
		Assert.assertSame(currentLeft, ((List<?>)currentSupport).get(0));
		Assert.assertSame(currentRight, ((List<?>)currentSupport).get(1));
		Assert.assertSame(currentLeft, pinned(actual, scenario.fixture.key(scenario.left)));
		Assert.assertSame(currentRight, pinned(actual, scenario.fixture.key(scenario.right)));
		NativePlacementContinuity cold = revised.freshQueryState();
		Assert.assertEquals(skeletons(cold, currentFact, currentClause,
			scenario.fixture.hop(scenario.root), anchor("worker1:8001")), actual);
		Assert.assertEquals(baseline, actual);
	}

	@Test
	public void unownedClauseAndEqualForeignOwnersStayCold() throws Exception {
		Scenario scenario = scenario();
		NativePlacementContinuity resolver = scenario.fixture.resolver();
		skeletons(resolver, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker1:8001"));
		CandidateRealizationSupportClause temporary = new CandidateRealizationSupportClause(
			List.of(), scenario.clause.inputBindings());
		Assert.assertEquals(scenario.clause, temporary);
		Assert.assertNotSame(scenario.clause, temporary);
		long builds = counter(resolver, "dependencySkeletonBuilds");
		List<?> first = skeletons(resolver, scenario.fact, temporary,
			scenario.fixture.hop(scenario.root), anchor("worker2:8002"));
		List<?> second = skeletons(resolver, scenario.fact, temporary,
			scenario.fixture.hop(scenario.root), anchor("worker2:8002"));
		Assert.assertEquals(first, second);
		Assert.assertEquals("an unowned clause must remain outside the skeleton memo",
			builds + 2, counter(resolver, "dependencySkeletonBuilds"));
		Assert.assertNull(clauseSupport(resolver, scenario.fact, temporary));

		CompiledHopKey left = scenario.fixture.key(scenario.left);
		CompiledHopKey foreign = new CompiledHopKey(left.programFingerprint(), left.functionNamespace(),
			left.callSitePath(), left.recompileContext(), left.controlRegion(),
			left.emittedHopInstance(), left.canonicalSourceOrigin());
		Assert.assertEquals(left, foreign);
		Assert.assertNotSame(left, foreign);
		CandidateRealizationReference foreignReference = new CandidateRealizationReference(
			new CandidateRuleKey(foreign, scenario.leftReference.rule().orderedInputs()),
			scenario.leftReference.realization());
		CandidateRealizationSupportClause foreignClause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, foreignReference),
				CandidateRealizationInputBinding.direct(1, scenario.rightReference)));
		CandidateRuleFact foreignFact = rebuiltFact(scenario.fact, foreignClause);
		List<CandidateRuleFact> facts = new ArrayList<>(scenario.fixture.facts());
		facts.set(facts.indexOf(scenario.fact), foreignFact);
		NativePlacementContinuity foreignRevision = resolver.nextRevision(facts);
		Assert.assertNull("equal foreign source ownership must reject donor clause authority",
			clauseSupport(foreignRevision, foreignFact, foreignClause));
		Assert.assertNull(clausePinProjection(foreignRevision, foreignFact, foreignClause));
	}

	@Test
	public void preparedProjectionRemovesPerWitnessPinMapAllocation() throws Exception {
		java.lang.management.ThreadMXBean platform = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(platform instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)platform;
		Assume.assumeTrue(bean.isThreadAllocatedMemorySupported());
		boolean enabled = bean.isThreadAllocatedMemoryEnabled();
		if(!enabled)
			bean.setThreadAllocatedMemoryEnabled(true);
		try {
			FixtureAccess fixture = FixtureAccess.create();
			Object left = fixture.source("allocation-left", anchor("worker1:8001"));
			Object right = fixture.source("allocation-right", anchor("worker1:8001"));
			Object root = fixture.binary("allocation-root", left, right);
			List<CandidateInputState> inputs = List.of(
				CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
			List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
			bindings.add(CandidateRealizationInputBinding.direct(0,
				reference(fixture.key(left), "allocation-left")));
			bindings.add(CandidateRealizationInputBinding.direct(1,
				reference(fixture.key(right), "allocation-right")));
			CompiledHopKey base = fixture.key(left);
			for(int index = 2; index < 128; index++) {
				CompiledHopKey extra = new CompiledHopKey(base.programFingerprint(),
					base.functionNamespace(), base.callSitePath(), base.recompileContext(),
					base.controlRegion(), "allocation-extra-" + index,
					"allocation-extra-" + index);
				bindings.add(CandidateRealizationInputBinding.direct(index,
					reference(extra, "allocation-extra-" + index)));
			}
			CandidateRealizationSupportClause clause =
				new CandidateRealizationSupportClause(List.of(), bindings);
			fixture.withClauses(root, inputs, List.of(clause));
			CandidateRuleFact fact = fixture.fact(root, inputs);
			NativePlacementContinuity resolver = fixture.resolver();
			Assert.assertNotNull(skeletons(resolver, fact, clause, fixture.hop(root),
				anchor("allocation-warm:8100")));
			CandidateRealizationSupportClause unowned = new CandidateRealizationSupportClause(
				clause.proofDependencies(), clause.inputBindings());
			Assert.assertEquals(clause, unowned);
			Assert.assertNotSame(clause, unowned);

			List<Object> ownedWitnesses = new ArrayList<>();
			List<Object> coldWitnesses = new ArrayList<>();
			for(int index = 0; index < 160; index++) {
				ownedWitnesses.add(nativeWitness("allocation-owned-" + index));
				coldWitnesses.add(nativeWitness("allocation-cold-" + index));
			}
			long beforeOwned = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
			for(Object witness : ownedWitnesses)
				Assert.assertNotNull(skeletonsWithWitness(
					resolver, fact, clause, fixture.hop(root), witness));
			long ownedBytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeOwned;
			long beforeCold = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
			for(Object witness : coldWitnesses)
				Assert.assertNotNull(skeletonsWithWitness(
					resolver, fact, unowned, fixture.hop(root), witness));
			long coldBytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeCold;

			Assert.assertTrue("unowned cold preparation must allocate at least 512 KiB more than the "
				+ "same exact clause across new witnesses; owned=" + ownedBytes + " cold=" + coldBytes,
				coldBytes - ownedBytes > 512L * 1024);
		}
		finally {
			if(!enabled)
				bean.setThreadAllocatedMemoryEnabled(false);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void warmedProjectionDoesNotRetainWithdrawnSourceAuthority() throws Exception {
		Method fixtureMethod = NativeValueMapFixedPoolContinuityTest.class
			.getDeclaredMethod("fixture", boolean.class);
		fixtureMethod.setAccessible(true);
		Object fixture = fixtureMethod.invoke(null, false);
		NativePlacementContinuity initial = (NativePlacementContinuity)fixtureValue(fixture, "continuity");
		CandidateRealizationReference reference =
			(CandidateRealizationReference)fixtureValue(fixture, "valueMap");
		DurableAnchorKey pool = (DurableAnchorKey)fixtureValue(fixture, "expectedPool");
		CompiledHopKey withdrawnOwner = (CompiledHopKey)fixtureValue(fixture, "secondLeaf");
		List<CandidateRuleFact> facts = (List<CandidateRuleFact>)fixtureValue(fixture, "facts");
		CandidateRuleFact ownerFact = facts.stream()
			.filter(fact -> fact.key().parentOccurrence() == reference.rule().parentOccurrence())
			.findFirst().orElseThrow();
		CandidateRealizationSupportClause clause = ownerFact.allowedEmissionFacts().stream()
			.flatMap(emission -> emission.realizations().stream())
			.flatMap(realization -> realization.supportClauses().stream())
			.filter(value -> value.inputBindings().stream().anyMatch(binding ->
				binding.source().rule().parentOccurrence() == withdrawnOwner))
			.findFirst().orElseThrow();
		Assert.assertNotNull(skeletons(initial, ownerFact, clause,
			origin(initial, ownerFact.key().parentOccurrence()), pool));
		Assert.assertNotNull(initial.proveCandidate(reference, pool));
		Object projection = clausePinProjection(initial, ownerFact, clause);
		Assert.assertNotNull("the proof must warm the exact clause projection", projection);

		NativePlacementContinuity withdrawn = initial.nextRevision(facts.stream()
			.filter(fact -> fact.key().parentOccurrence() != withdrawnOwner).toList());
		Assert.assertSame("the pure projection may survive an exact revision",
			projection, clausePinProjection(withdrawn, ownerFact, clause));
		Assert.assertNull(withdrawn.fixedValueMapPool(reference));
		Assert.assertNull("current source authority must still reject the warm projection",
			withdrawn.proveCandidate(reference, pool));
		NativePlacementContinuity cold = withdrawn.freshQueryState();
		Assert.assertNull(cold.fixedValueMapPool(reference));
		Assert.assertEquals(cold.proveCandidateAlternatives(reference, pool),
			withdrawn.proveCandidateAlternatives(reference, pool));
	}

	private static Object fixtureValue(Object fixture, String name) throws Exception {
		Method getter = fixture.getClass().getDeclaredMethod(name);
		getter.setAccessible(true);
		return getter.invoke(fixture);
	}

	@SuppressWarnings("unchecked")
	private static Hop origin(NativePlacementContinuity resolver, CompiledHopKey owner)
		throws Exception {
		Field contextField = NativePlacementContinuity.class.getDeclaredField("structuralContext");
		contextField.setAccessible(true);
		Object context = contextField.get(resolver);
		Field origins = context.getClass().getDeclaredField("originsByKey");
		origins.setAccessible(true);
		return ((Map<CompiledHopKey,Hop>)origins.get(context)).get(owner);
	}

	@Test
	public void contradictoryOwnerIsRejectedAndCountedOnEveryAttempt() throws Exception {
		FixtureAccess fixture = FixtureAccess.create();
		Object left = fixture.source("contradictory-left", anchor("worker1:8001"));
		Object right = fixture.source("contradictory-right", anchor("worker1:8001"));
		Object root = fixture.binary("contradictory-root", left, right);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRealizationReference first = reference(fixture.key(left), "contradictory-a");
		CandidateRealizationReference second = reference(fixture.key(left), "contradictory-b");
		Assert.assertFalse(first.equals(second));
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, first),
				CandidateRealizationInputBinding.direct(1, second)));
		fixture.withClauses(root, inputs, List.of(clause));
		CandidateRuleFact fact = fixture.fact(root, inputs);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		NativePlacementContinuity resolver = fixture.resolver(metrics);

		Assert.assertNull(skeletons(resolver, fact, clause, fixture.hop(root),
			anchor("worker1:8001")));
		Assert.assertEquals(1, metrics.snapshot().contradictoryClausePins());
		Assert.assertNull(skeletons(resolver, fact, clause, fixture.hop(root),
			anchor("worker1:8001")));
		Assert.assertEquals(2, metrics.snapshot().contradictoryClausePins());
		Assert.assertNull("a failed clause must not infer successful memo authority",
			clausePinProjection(resolver, fact, clause));
	}

	private static void assertColdSupportBuild(NativePlacementContinuity resolver,
		Scenario scenario, String endpoint) throws Exception {
		PlacementIdentity.resetNormalizedSignatureCache();
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(0L);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.setActiveMetrics(metrics);
		try(PlacementAnalysis.CanonicalTextScope ignored = PlacementAnalysis.beginCanonicalTextScope(0, 0)) {
			skeletons(resolver, scenario.fact, scenario.clause,
				scenario.fixture.hop(scenario.root), anchor(endpoint));
		}
		Assert.assertTrue("cold resolver must rebuild canonical support",
			metrics.snapshot().signatureSerializations() > 0);
		Assert.assertEquals(1, requiredSupportMap(resolver).size());
	}

	private static CandidateRuleFact rebuiltFact(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause) {
		CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		CandidateEmissionRealization current = new CandidateEmissionRealization(
			realization.key(), List.of(clause));
		CandidateEmissionFact currentEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(current));
		return new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
			fact.profile(), List.of(currentEmission), fact.failureCode());
	}

	private static Object clauseSupport(NativePlacementContinuity resolver, CandidateRuleFact fact,
		CandidateRealizationSupportClause clause) throws Exception {
		Field memos = NativePlacementContinuity.class.getDeclaredField("dependencySkeletonMemo");
		memos.setAccessible(true);
		Object factMemo = ((Map<?,?>)memos.get(resolver)).get(fact);
		if(factMemo == null)
			return null;
		Field clauses = factMemo.getClass().getDeclaredField("clauses");
		clauses.setAccessible(true);
		Object clauseMemo = ((Map<?,?>)clauses.get(factMemo)).get(clause);
		if(clauseMemo == null)
			return null;
		Field support = clauseMemo.getClass().getDeclaredField("support");
		support.setAccessible(true);
		return support.get(clauseMemo);
	}

	private static Object clausePinProjection(NativePlacementContinuity resolver,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause) throws Exception {
		Field memos = NativePlacementContinuity.class.getDeclaredField("dependencySkeletonMemo");
		memos.setAccessible(true);
		Object factMemo = ((Map<?,?>)memos.get(resolver)).get(fact);
		if(factMemo == null)
			return null;
		Field clauses = factMemo.getClass().getDeclaredField("clauses");
		clauses.setAccessible(true);
		Object clauseMemo = ((Map<?,?>)clauses.get(factMemo)).get(clause);
		if(clauseMemo == null)
			return null;
		Field projection = clauseMemo.getClass().getDeclaredField("pinProjection");
		projection.setAccessible(true);
		return projection.get(clauseMemo);
	}

	private static long counter(NativePlacementContinuity resolver, String name) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(resolver);
	}

	@SuppressWarnings("unchecked")
	private static Map<CandidateRuleFact,java.util.Set<CandidateRealizationSupportClause>>
		ownedClauses(NativePlacementContinuity resolver) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("ownedCandidateClausesByFact");
		field.setAccessible(true);
		return (Map<CandidateRuleFact,java.util.Set<CandidateRealizationSupportClause>>)field.get(resolver);
	}

	private static Object pinned(List<?> skeletons, CompiledHopKey owner) throws Exception {
		for(Object skeleton : skeletons) {
			Field key = skeleton.getClass().getDeclaredField("key");
			key.setAccessible(true);
			if(key.get(skeleton) != owner)
				continue;
			Field pinned = skeleton.getClass().getDeclaredField("clausePinned");
			pinned.setAccessible(true);
			return pinned.get(skeleton);
		}
		return null;
	}

	private static Scenario scenario() throws Exception {
		FixtureAccess fixture = FixtureAccess.create();
		Object left = fixture.source("left", anchor("worker1:8001"));
		Object right = fixture.source("right", anchor("worker1:8001"));
		Object root = fixture.binary("root", left, right);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRealizationReference leftReference = reference(fixture.key(left), "left-support");
		CandidateRealizationReference rightReference = reference(fixture.key(right), "right-support");
		CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(List.of(), List.of(
			CandidateRealizationInputBinding.direct(0, leftReference),
			CandidateRealizationInputBinding.direct(1, rightReference)));
		fixture.withClauses(root, inputs, List.of(clause));
		return new Scenario(fixture, left, right, root, fixture.fact(root, inputs), clause,
			leftReference, rightReference);
	}

	private record Scenario(FixtureAccess fixture, Object left, Object right, Object root,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause,
		CandidateRealizationReference leftReference,
		CandidateRealizationReference rightReference) { }

	@SuppressWarnings("unchecked")
	private static Map<?,?> requiredSupportMap(NativePlacementContinuity resolver) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField("requiredInputSupportByClause");
		field.setAccessible(true);
		return (Map<?,?>)field.get(resolver);
	}

	@SuppressWarnings("unchecked")
	private static List<?> skeletons(NativePlacementContinuity resolver, CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, DurableAnchorKey anchor) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, anchor);
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateDependencySkeletons", CandidateRuleFact.class,
			CandidateRealizationSupportClause.class, Hop.class, witness.getClass());
		method.setAccessible(true);
		return (List<?>)method.invoke(resolver, fact, clause, owner, witness);
	}

	@SuppressWarnings("unchecked")
	private static List<?> skeletonsWithWitness(NativePlacementContinuity resolver,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause, Hop owner,
		Object witness) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateDependencySkeletons", CandidateRuleFact.class,
			CandidateRealizationSupportClause.class, Hop.class, witness.getClass());
		method.setAccessible(true);
		return (List<?>)method.invoke(resolver, fact, clause, owner, witness);
	}

	private static Object nativeWitness(String endpoint) throws Exception {
		Class<?> type = Class.forName(NativePlacementContinuity.class.getName() + "$NativePoolWitness");
		Constructor<?> constructor = type.getDeclaredConstructor(
			FType.class, List.class, List.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(FType.FULL, List.of(endpoint), List.of(), true);
	}

	private static CandidateRealizationReference reference(CompiledHopKey owner, String name) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.FULL)));
		PlacementEmissionState state = new PlacementEmissionState(
			new PlacementState(org.apache.sysds.common.Types.ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, false), false);
		return CandidateRealizationReference.of(rule,
			PlacementAnalysis.CandidateEmissionRealization.durable(
				state, anchor(name + ":9000"), List.of(), List.of()));
	}

	private static DurableAnchorKey anchor(String endpoint) {
		return new DurableAnchorKey("owned-clause-" + endpoint, FType.FULL,
			List.of(new AnchorPartition(endpoint, List.of(0L, 0L), List.of(4L, 2L))));
	}

	private static final class FixtureAccess {
		private final Object fixture;
		private final Class<?> type;
		private final Class<?> refType;

		private FixtureAccess(Object fixture, Class<?> type, Class<?> refType) {
			this.fixture = fixture;
			this.type = type;
			this.refType = refType;
		}

		private static FixtureAccess create() throws Exception {
			Class<?> type = Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuityTest$Fixture");
			Class<?> ref = Class.forName(
				"org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuityTest$Ref");
			Constructor<?> constructor = type.getDeclaredConstructor(FType.class);
			constructor.setAccessible(true);
			return new FixtureAccess(constructor.newInstance(FType.FULL), type, ref);
		}

		private Object source(String name, DurableAnchorKey anchor) throws Exception {
			return invoke("source", new Class<?>[] {String.class, DurableAnchorKey.class}, name, anchor);
		}

		private Object binary(String name, Object left, Object right) throws Exception {
			return invoke("binary", new Class<?>[] {String.class, OpOp2.class, refType, refType,
				boolean.class}, name, OpOp2.PLUS, left, right, false);
		}

		private CandidateRealizationReference withClauses(Object owner, List<CandidateInputState> inputs,
			List<CandidateRealizationSupportClause> clauses) throws Exception {
			return (CandidateRealizationReference)invoke("withClauses",
				new Class<?>[] {refType, List.class, List.class}, owner, inputs, clauses);
		}

		private CandidateRuleFact fact(Object owner, List<CandidateInputState> inputs) throws Exception {
			return (CandidateRuleFact)invoke("fact", new Class<?>[] {refType, List.class}, owner, inputs);
		}

		private NativePlacementContinuity resolver() throws Exception {
			return (NativePlacementContinuity)invoke("resolver", new Class<?>[0]);
		}

		private NativePlacementContinuity resolver(SearchSpaceMetrics metrics) throws Exception {
			return (NativePlacementContinuity)invoke("resolver",
				new Class<?>[] {SearchSpaceMetrics.class, int.class, long.class}, metrics, 0, 0L);
		}

		@SuppressWarnings("unchecked")
		private List<CandidateRuleFact> facts() throws Exception {
			Field field = type.getDeclaredField("candidates");
			field.setAccessible(true);
			return List.copyOf((List<CandidateRuleFact>)field.get(fixture));
		}

		private CompiledHopKey key(Object ref) throws Exception {
			return (CompiledHopKey)invokeRef(ref, "key");
		}

		private Hop hop(Object ref) throws Exception {
			return (Hop)invokeRef(ref, "hop");
		}

		private Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
			Method method = type.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method.invoke(fixture, arguments);
		}

		private Object invokeRef(Object ref, String name) throws Exception {
			Method method = refType.getDeclaredMethod(name);
			method.setAccessible(true);
			return method.invoke(ref);
		}
	}
}
