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
import org.junit.Test;

public class NativeOwnedClauseSupportMemoTest {
	@After
	public void clearCaches() {
		PlacementIdentity.setActiveMetrics(null);
		PlacementIdentity.setNormalizedSignatureCacheMaxCharsForTest(null);
		PlacementIdentity.resetNormalizedSignatureCache();
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

		NativePlacementContinuity exact = parent.nextRevision(fixture.facts());
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
		Assert.assertNotNull(parentSupport);

		NativePlacementContinuity left = parent.nextRevision(scenario.fixture.facts());
		NativePlacementContinuity right = parent.nextRevision(scenario.fixture.facts());
		Assert.assertSame(parentSupport, clauseSupport(left, scenario.fact, scenario.clause));
		Assert.assertSame(parentSupport, clauseSupport(right, scenario.fact, scenario.clause));
		skeletons(left, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker2:8002"));
		skeletons(right, scenario.fact, scenario.clause,
			scenario.fixture.hop(scenario.root), anchor("worker3:8003"));
		Assert.assertSame(parentSupport, clauseSupport(left, scenario.fact, scenario.clause));
		Assert.assertSame(parentSupport, clauseSupport(right, scenario.fact, scenario.clause));

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

		PlacementIdentity.resetNormalizedSignatureCache();
		List<?> actual = skeletons(revised, currentFact, currentClause,
			scenario.fixture.hop(scenario.root), anchor("worker1:8001"));
		Object currentSupport = clauseSupport(revised, currentFact, currentClause);
		Assert.assertNotNull(currentSupport);
		Assert.assertNotSame(donorSupport, currentSupport);
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

	private static long counter(NativePlacementContinuity resolver, String name) throws Exception {
		Field field = NativePlacementContinuity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getLong(resolver);
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

		private void withClauses(Object owner, List<CandidateInputState> inputs,
			List<CandidateRealizationSupportClause> clauses) throws Exception {
			invoke("withClauses", new Class<?>[] {refType, List.class, List.class}, owner, inputs, clauses);
		}

		private CandidateRuleFact fact(Object owner, List<CandidateInputState> inputs) throws Exception {
			return (CandidateRuleFact)invoke("fact", new Class<?>[] {refType, List.class}, owner, inputs);
		}

		private NativePlacementContinuity resolver() throws Exception {
			return (NativePlacementContinuity)invoke("resolver", new Class<?>[0]);
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
