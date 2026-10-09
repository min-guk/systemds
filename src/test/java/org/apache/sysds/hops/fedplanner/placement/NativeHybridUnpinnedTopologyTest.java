/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.junit.Assert;
import org.junit.Test;

public class NativeHybridUnpinnedTopologyTest {
	@Test
	public void mixedNativeAndExplicitRowsPreserveEveryCanonicalProofLazily()
		throws Exception {
		assertMixedParity("a-native", List.of("m-explicit", "z-explicit"));
		assertMixedParity("m-native", List.of("a-explicit", "z-explicit"));
		assertMixedParity("z-native", List.of("a-explicit", "m-explicit"));
	}

	private static void assertMixedParity(String nativeLineage,
		List<String> explicitLineages) throws Exception {
		Scenario scenario = scenario(nativeLineage, explicitLineages);
		NativePlacementContinuity.CandidateSupportResult explicit = scenario.query(true);
		NativeContinuitySupportClauses lazyRelation = scenario.install(false);
		NativePlacementContinuity.CandidateSupportResult hybrid = scenario.queryCurrent();

		Assert.assertEquals(3, explicit.proofs().size());
		Assert.assertEquals(signatures(explicit), signatures(hybrid));
		assertBindingSourceIdentity(explicit, hybrid);
		assertIdentitySetEquals(explicit.dependencyOccurrences(), hybrid.dependencyOccurrences());
		Assert.assertEquals("the native 2x3 family contributes one representative circuit row",
			1, lazyRelation.materializedHandleCount());
	}

	private static Scenario scenario(String nativeLineage,
		List<String> explicitLineages) throws Exception {
		Object fixture = newFixture(FType.FULL);
		DurableAnchorKey pool = pool("hybrid-worker:8001", 0, 50);
		Object seed = invoke(fixture, "source", "hybrid-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Object left = invoke(fixture, "unary", "hybrid-left", OpOp1.LOG, seed, false);
		Object right = invoke(fixture, "unary", "hybrid-right", OpOp1.LOG, seed, false);
		invoke(fixture, "samePoolRealizations", left, unary, new DurableAnchorKey[] {
			new DurableAnchorKey("hybrid-left-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("hybrid-left-b", FType.FULL, pool.partitions())});
		invoke(fixture, "samePoolRealizations", right, unary, new DurableAnchorKey[] {
			new DurableAnchorKey("hybrid-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("hybrid-right-b", FType.FULL, pool.partitions()),
			new DurableAnchorKey("hybrid-right-c", FType.FULL, pool.partitions())});
		Object child = invoke(fixture, "binary", "hybrid-child", OpOp2.PLUS,
			left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRuleFact childFact = (CandidateRuleFact)invoke(fixture, "fact", child, binary);
		CandidateEmissionFact childEmission = childFact.allowedEmissionFacts().get(0);
		CompiledHopKey childKey = (CompiledHopKey)invoke(child, "key");
		NativePlacementContinuity.NativeSupportProduct product =
			((NativePlacementContinuity)invoke(fixture, "resolver"))
				.proveCandidateSupport((CandidateRealizationReference)invoke(
					fixture, "reference", child, binary), pool).supportProduct();
		Assert.assertNotNull(product);

		Object outer = invoke(fixture, "unary", "hybrid-outer", OpOp1.EXP, child, false);
		CandidateRuleFact outerFact = (CandidateRuleFact)invoke(fixture, "fact", outer, unary);
		CandidateEmissionFact outerEmission = outerFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(outerFact.key(),
			CandidateEmissionRealization.nativeLineage(outerEmission.emissionState(),
				"hybrid-output", List.of(), List.of()));
		return new Scenario(fixture, childKey, childFact, childEmission, product, pool,
			nativeLineage, List.copyOf(explicitLineages), outerFact, outerEmission, proposed);
	}

	private record Scenario(Object fixture, CompiledHopKey childKey,
		CandidateRuleFact childFact, CandidateEmissionFact childEmission,
		NativePlacementContinuity.NativeSupportProduct product, DurableAnchorKey pool,
		String nativeLineage, List<String> explicitLineages, CandidateRuleFact outerFact,
		CandidateEmissionFact outerEmission, CandidateRealizationReference proposed) {
		private NativePlacementContinuity.CandidateSupportResult query(boolean explicit)
			throws Exception {
			install(explicit);
			return queryCurrent();
		}

		private NativePlacementContinuity.CandidateSupportResult queryCurrent() throws Exception {
			return ((NativePlacementContinuity)invoke(fixture, "resolver"))
				.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
		}

		private NativeContinuitySupportClauses install(boolean explicit) throws Exception {
			NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
				childKey, product, pool, true);
			CandidateEmissionRealization nativeRealization = new CandidateEmissionRealization(
				PlacementIdentity.PlacementRealizationKey.nativeLineage(
					childEmission.emissionState(), nativeLineage),
				explicit ? List.copyOf(relation) : relation);
			List<CandidateEmissionRealization> realizations = new ArrayList<>();
			for(String lineage : explicitLineages)
				realizations.add(new CandidateEmissionRealization(
					PlacementIdentity.PlacementRealizationKey.nativeLineage(
						childEmission.emissionState(), lineage),
					List.of(new CandidateRealizationSupportClause(
						List.of(), product.bindingsAt(0)))));
			// Encounter order intentionally differs from canonical reference order.
			realizations.add(1, nativeRealization);
			replaceChildFact(fixture, childKey, childFact, childEmission, realizations);
			return relation;
		}
	}

	@SuppressWarnings("unchecked")
	private static void replaceChildFact(Object fixture, CompiledHopKey childKey,
		CandidateRuleFact template, CandidateEmissionFact base,
		List<CandidateEmissionRealization> realizations) throws Exception {
		Field candidatesField = fixture.getClass().getDeclaredField("candidates");
		candidatesField.setAccessible(true);
		List<CandidateRuleFact> candidates = (List<CandidateRuleFact>)candidatesField.get(fixture);
		int position = -1;
		for(int index = 0; index < candidates.size(); index++)
			if(candidates.get(index).key().parentOccurrence() == childKey) {
				position = index;
				break;
			}
		Assert.assertTrue(position >= 0);
		candidates.removeIf(fact -> fact.key().parentOccurrence() == childKey);
		CandidateEmissionFact emission = new CandidateEmissionFact(base.emissionState(),
			base.executionFType(), base.derivedFoutAction(), List.copyOf(realizations));
		candidates.add(position, new CandidateRuleFact(template.key(), template.status(),
			template.capability(), template.shapeProof(), template.profile(),
			List.of(emission), template.failureCode()));
	}

	private static Object newFixture(FType type) throws Exception {
		Class<?> fixture = Class.forName(NativePlacementContinuityTest.class.getName() + "$Fixture");
		Constructor<?> constructor = fixture.getDeclaredConstructor(FType.class);
		constructor.setAccessible(true);
		return constructor.newInstance(type);
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

	private static DurableAnchorKey pool(String worker, long begin, long end) {
		return new DurableAnchorKey("hybrid-pool", FType.FULL,
			List.of(new AnchorPartition(worker, List.of(begin, 0L), List.of(end, 2L))));
	}

	private static List<String> signatures(
		NativePlacementContinuity.CandidateSupportResult result) {
		return result.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList();
	}

	private static void assertBindingSourceIdentity(
		NativePlacementContinuity.CandidateSupportResult expected,
		NativePlacementContinuity.CandidateSupportResult actual) {
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
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey key : expected)
			Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == key));
	}
}
