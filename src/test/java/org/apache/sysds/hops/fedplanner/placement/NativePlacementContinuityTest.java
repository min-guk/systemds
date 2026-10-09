/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

/** Typed, coinductive native worker-pool continuity proofs. */
public class NativePlacementContinuityTest {
	@Test
	public void directProofTemplateDoesNotConsumePreviouslyPublishedRootReceipt() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey firstAnchor = anchor(FType.FULL, "worker1:8001", 0, 50);
		DurableAnchorKey secondAnchor = new DurableAnchorKey("same-layout-second-value", FType.FULL,
			firstAnchor.partitions());
		Ref source = full.federatedSource("source", firstAnchor);
		List<CandidateInputState> sourceInputs = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
		full.samePoolRealizations(source, sourceInputs, firstAnchor, secondAnchor);
		Ref owner = full.unary("owner", OpOp1.LOG, source, false);
		full.privacy(source, Privacy.PRIVATE_AGGREGATE);
		full.privacy(owner, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact ownerFact = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == owner.key).findFirst().orElseThrow();
		CandidateEmissionFact ownerEmission = ownerFact.allowedEmissionFacts().get(0);
		CandidateRuleFact sourceFact = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == source.key).findFirst().orElseThrow();
		List<CandidateRealizationReference> sources = sourceFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationReference.of(
				sourceFact.key(), realization)).toList();

		CandidateRealizationReference firstQuery = publish(
			full, ownerFact, ownerEmission, sources.get(0), firstAnchor);
		NativePlacementContinuity publishedFirst = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> firstPublished =
			publishedFirst.proveCandidateAlternatives(firstQuery, firstAnchor);
		List<NativePlacementContinuity.NativeContinuityProof> firstTemplate =
			publishedFirst.provePrimitiveCandidateAlternatives(firstQuery, firstAnchor);
		NativePlacementContinuity primitiveFirst = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> firstTemplateReverse =
			primitiveFirst.provePrimitiveCandidateAlternatives(firstQuery, firstAnchor);
		List<NativePlacementContinuity.NativeContinuityProof> firstPublishedReverse =
			primitiveFirst.proveCandidateAlternatives(firstQuery, firstAnchor);
		Assert.assertEquals("public query cache must be independent of proof-only query order",
			firstPublished, firstPublishedReverse);
		Assert.assertEquals("proof-only query cache must be independent of public-query order",
			firstTemplate, firstTemplateReverse);
		Assert.assertTrue("the proof-only root must never escape as an executable input receipt",
			firstTemplate.stream().flatMap(proof -> proof.immediateBindings().stream())
				.noneMatch(binding -> binding.source().equals(firstQuery)));
		CandidateRealizationReference collidingQuery = publishNativeLineage(
			full, ownerFact, ownerEmission, sources.get(0), firstAnchor,
			"native-continuity:primitive-query-root");
		Assert.assertEquals("an ordinary lineage spelling cannot alter generated-root semantics",
			firstTemplate, full.resolver().provePrimitiveCandidateAlternatives(
				collidingQuery, firstAnchor));
		List<NativePlacementContinuity.NativeContinuityProof> secondPublished = publishAndProve(
			full, ownerFact, ownerEmission, sources.get(1), firstAnchor, false);
		List<NativePlacementContinuity.NativeContinuityProof> secondTemplate = publishAndProve(
			full, ownerFact, ownerEmission, sources.get(1), firstAnchor, true);

		Assert.assertNotEquals("fixture must expose root-receipt feedback",
			firstPublished, secondPublished);
		Assert.assertEquals("the proof-only direct rule relation is revision invariant",
			firstTemplate, secondTemplate);
		Assert.assertEquals("both executable same-pool source variants remain feasible",
			2, firstTemplate.size());
	}

	@Test
	public void primitiveProofAdapterRejectsMissingOrAmbiguousCurrentBase() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref owner = full.unary("owner", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(owner, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact ownerFact = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == owner.key).findFirst().orElseThrow();
		CandidateEmissionFact emission = ownerFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference matching = new CandidateRealizationReference(ownerFact.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(emission.emissionState(), "proof-only-query"));
		Assert.assertFalse(full.resolver().provePrimitiveCandidateAlternatives(
			matching, seed.anchor).isEmpty());

		PlacementEmissionState missingState = new PlacementEmissionState(state(FType.ROW), false);
		CandidateRealizationReference missing = new CandidateRealizationReference(ownerFact.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(missingState, "missing-base-query"));
		Assert.assertTrue(full.resolver().provePrimitiveCandidateAlternatives(
			missing, seed.anchor).isEmpty());

		full.candidates.add(ownerFact);
		Assert.assertTrue("two current matching rows are not an arbitrary proof authority",
			full.resolver().provePrimitiveCandidateAlternatives(matching, seed.anchor).isEmpty());
	}

	private static CandidateRuleFact replaceRealizations(Fixture fixture,
		CandidateRuleFact prior, CandidateEmissionFact priorEmission,
		List<CandidateEmissionRealization> replacements) {
		CandidateEmissionFact emission = new CandidateEmissionFact(priorEmission.emissionState(),
			priorEmission.executionFType(), priorEmission.derivedFoutAction(), replacements);
		CandidateRuleFact updated = new CandidateRuleFact(prior.key(), prior.status(), prior.capability(),
			prior.shapeProof(), prior.profile(), List.of(emission), prior.failureCode());
		fixture.candidates.set(fixture.candidates.indexOf(prior), updated);
		return updated;
	}


	private static List<NativePlacementContinuity.NativeContinuityProof> publishAndProve(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateRealizationReference selectedSource, DurableAnchorKey witness,
		boolean templateQuery) {
		CandidateRealizationReference query = publish(
			fixture, original, emission, selectedSource, witness);
		return templateQuery
			? fixture.resolver().provePrimitiveCandidateAlternatives(query, witness)
			: fixture.resolver().proveCandidateAlternatives(query, witness);
	}

	private static CandidateRealizationReference publish(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateRealizationReference selectedSource, DurableAnchorKey witness) {
		CandidateEmissionRealization published = CandidateEmissionRealization.durable(
			emission.emissionState(), witness, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, selectedSource)));
		return replacePublished(fixture, original, emission, published);
	}

	private static CandidateRealizationReference publishNativeLineage(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateRealizationReference selectedSource, DurableAnchorKey witness, String lineage) {
		CandidateEmissionRealization published = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), lineage, List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, selectedSource)));
		return replacePublished(fixture, original, emission, published);
	}

	private static CandidateRealizationReference replacePublished(
		Fixture fixture, CandidateRuleFact original, CandidateEmissionFact emission,
		CandidateEmissionRealization published) {
		CandidateEmissionFact replacementEmission = new CandidateEmissionFact(
			emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
			List.of(published));
		CandidateRuleFact replacement = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(),
			List.of(replacementEmission), original.failureCode());
		int index = java.util.stream.IntStream.range(0, fixture.candidates.size())
			.filter(candidate -> fixture.candidates.get(candidate).key().equals(original.key()))
			.findFirst().orElseThrow();
		fixture.candidates.set(index, replacement);
		return CandidateRealizationReference.of(replacement.key(), published);
	}
	@Test
	public void dynamicPartitionRangeWitnessIsMemoizedPerExactWitness() throws Exception {
		Class<?> witnessClass = Class.forName(NativePlacementContinuity.class.getName() + "$NativePoolWitness");
		Class<?> intervalClass = Class.forName(NativePlacementContinuity.class.getName() + "$AxisInterval");
		Constructor<?> constructor = witnessClass.getDeclaredConstructor(FType.class, List.class, List.class,
			boolean.class);
		constructor.setAccessible(true);
		Constructor<?> intervalConstructor = intervalClass.getDeclaredConstructor(String.class, long.class,
			long.class);
		intervalConstructor.setAccessible(true);
		Method withDynamicPartitionRanges = witnessClass.getDeclaredMethod("withDynamicPartitionRanges");
		withDynamicPartitionRanges.setAccessible(true);
		Method withExactPartitionRanges = witnessClass.getDeclaredMethod("withExactPartitionRanges");
		withExactPartitionRanges.setAccessible(true);
		Method retyped = witnessClass.getDeclaredMethod("retyped", FType.class);
		retyped.setAccessible(true);
		Method retypedForResidency = witnessClass.getDeclaredMethod("retypedForResidency", FType.class);
		retypedForResidency.setAccessible(true);
		Method matches = witnessClass.getDeclaredMethod("matches", witnessClass, boolean.class);
		matches.setAccessible(true);
		Field fTypeField = accessibleField(witnessClass, "fType");
		Field endpointsField = accessibleField(witnessClass, "endpoints");
		Field intervalsField = accessibleField(witnessClass, "partitionAxisIntervals");
		Field exactField = accessibleField(witnessClass, "exactPartitionRanges");

		for(FType fType : List.of(FType.ROW, FType.COL, FType.FULL)) {
			List<String> endpoints = List.of("worker1:8001", "worker2:8002");
			List<Object> intervals = List.of(intervalConstructor.newInstance("worker1:8001", 0L, 5L),
				intervalConstructor.newInstance("worker2:8002", 5L, 10L));
			Object exact = constructor.newInstance(fType, endpoints, intervals, true);
			Object equalExact = constructor.newInstance(fType, endpoints, intervals, true);
			int exactHash = exact.hashCode();

			Object firstDynamic = withDynamicPartitionRanges.invoke(exact);
			Object repeatedDynamic = withDynamicPartitionRanges.invoke(exact);
			Object expectedDynamic = constructor.newInstance(fType, endpoints, intervals, false);

			Assert.assertSame("Repeated conversion must reuse the exact witness's dynamic sibling",
				firstDynamic, repeatedDynamic);
			Assert.assertSame("A dynamic witness conversion must be idempotent", firstDynamic,
				withDynamicPartitionRanges.invoke(firstDynamic));
			Assert.assertSame("Exact and dynamic siblings must round-trip without allocation", exact,
				withExactPartitionRanges.invoke(firstDynamic));
			Assert.assertSame("Repeated exact conversion must reuse the dynamic witness's exact sibling", exact,
				withExactPartitionRanges.invoke(firstDynamic));
			Assert.assertEquals(fType, fTypeField.get(firstDynamic));
			Assert.assertEquals(endpoints, endpointsField.get(firstDynamic));
			Assert.assertEquals(intervals, intervalsField.get(firstDynamic));
			Assert.assertFalse(exactField.getBoolean(firstDynamic));
			Assert.assertEquals(expectedDynamic, firstDynamic);
			Assert.assertEquals(expectedDynamic.hashCode(), firstDynamic.hashCode());
			Assert.assertNotEquals(exact, firstDynamic);
			Assert.assertEquals(equalExact, exact);
			Assert.assertEquals(exactHash, exact.hashCode());
			for(Object candidate : List.of(exact, firstDynamic,
				constructor.newInstance(fType, List.of("other:9000"), List.of(), false))) {
				for(boolean anchorLayoutExact : List.of(false, true))
					Assert.assertEquals(matches.invoke(expectedDynamic, candidate, anchorLayoutExact),
						matches.invoke(firstDynamic, candidate, anchorLayoutExact));
			}
		}
		Object exactRow = constructor.newInstance(FType.ROW, List.of("worker1:8001"),
			List.of(intervalConstructor.newInstance("worker1:8001", 0L, 10L)), true);
		Object firstCol = retyped.invoke(exactRow, FType.COL);
		Assert.assertSame("Repeated exact retyping must reuse its equal transformed witness", firstCol,
			retyped.invoke(exactRow, FType.COL));
		Assert.assertSame("Exact ROW/COL retyping must preserve a symmetric sibling", exactRow,
			retyped.invoke(firstCol, FType.ROW));
		Assert.assertSame("Range relaxation and ROW/COL retyping must share the same transformed witness",
			withDynamicPartitionRanges.invoke(firstCol),
			retyped.invoke(withDynamicPartitionRanges.invoke(exactRow), FType.COL));
		for(FType dynamicSourceType : List.of(FType.ROW, FType.COL)) {
			FType dynamicTargetType = dynamicSourceType == FType.ROW ? FType.COL : FType.ROW;
			Object dynamicSource = constructor.newInstance(dynamicSourceType, List.of("worker1:8001"),
				List.of(intervalConstructor.newInstance("worker1:8001", 0L, 10L)), false);
			Object dynamicTarget = retyped.invoke(dynamicSource, dynamicTargetType);
			Object exactTargetFirst = withExactPartitionRanges.invoke(dynamicTarget);
			Object exactSourceSecond = withExactPartitionRanges.invoke(dynamicSource);
			Assert.assertSame("Dynamic-first exact/retype permutations must converge on one exact witness",
				exactTargetFirst, retyped.invoke(exactSourceSecond, dynamicTargetType));
			Assert.assertSame("Dynamic-first links must retain the original exact sibling",
				exactTargetFirst, withExactPartitionRanges.invoke(dynamicTarget));
			Assert.assertSame("Dynamic-first reverse retyping must converge on the exact source sibling",
				exactSourceSecond, retyped.invoke(exactTargetFirst, dynamicSourceType));
			Assert.assertSame("Dynamic-first exact siblings must retain their original dynamic witnesses",
				dynamicSource, withDynamicPartitionRanges.invoke(exactSourceSecond));
		}
		Object exactFull = constructor.newInstance(FType.FULL, List.of("worker1:8001"), List.of(), true);
		Object residentRow = retypedForResidency.invoke(exactFull, FType.ROW);
		Assert.assertSame("Repeated residency retyping must reuse its equal transformed witness", residentRow,
			retypedForResidency.invoke(exactFull, FType.ROW));
		Object fullRoundTrip = retypedForResidency.invoke(residentRow, FType.FULL);
		Assert.assertEquals("FULL residency round-trip must preserve value semantics", exactFull, fullRoundTrip);
		Assert.assertNotSame("Residency conversion is not generally invertible and must not install reverse aliases",
			exactFull, fullRoundTrip);
		Object fullFromExactRow = retypedForResidency.invoke(exactRow, FType.FULL);
		Object rowAfterLossyRoundTrip = retypedForResidency.invoke(fullFromExactRow, FType.ROW);
		Object expectedRowAfterLoss = constructor.newInstance(
			FType.ROW, List.of("worker1:8001"), List.of(), false);
		Assert.assertEquals("ROW -> FULL -> ROW must retain the documented lossy residency shape",
			expectedRowAfterLoss, rowAfterLossyRoundTrip);
		Assert.assertNotEquals("A lossy residency round-trip must not resurrect exact partition intervals",
			exactRow, rowAfterLossyRoundTrip);
		for(FType sourceType : List.of(FType.ROW, FType.COL, FType.FULL))
			for(boolean exactRanges : List.of(false, true)) {
				List<Object> sourceIntervals = sourceType == FType.FULL ? List.of()
					: List.of(intervalConstructor.newInstance("worker1:8001", 2L, 9L));
				Object source = constructor.newInstance(
					sourceType, List.of("worker1:8001"), sourceIntervals, exactRanges);
				for(FType target : FType.values()) {
					Object expected = referenceResidency(constructor, fTypeField, endpointsField,
						intervalsField, exactField, source, target);
					Object actual = retypedForResidency.invoke(source, target);
					Assert.assertEquals("one-step residency parity for " + sourceType + '/' + exactRanges
						+ " -> " + target, expected, actual);
					if(actual == null)
						continue;
					Assert.assertSame("directional residency memo must reuse only the exact same query",
						actual, retypedForResidency.invoke(source, target));
					for(FType secondTarget : FType.values())
						Assert.assertEquals("two-step residency parity for " + sourceType + '/' + exactRanges
							+ " -> " + target + " -> " + secondTarget,
							referenceResidency(constructor, fTypeField, endpointsField,
								intervalsField, exactField, actual, secondTarget),
							retypedForResidency.invoke(actual, secondTarget));
				}
			}
		for(FType unsupported : List.of(FType.BROADCAST, FType.PART, FType.OTHER)) {
			Object witness = constructor.newInstance(unsupported, List.of("worker1:8001"), List.of(), true);
			Assert.assertSame("Unsupported witness types must not be transformed", witness,
				withDynamicPartitionRanges.invoke(witness));
		}
	}

	private static Field accessibleField(Class<?> owner, String name) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static Object acyclicSummaryRowKey(NativePlacementContinuity resolver,
		CandidateRealizationReference reference, DurableAnchorKey pool) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, pool);
		Class<?> selectedType = Class.forName(
			NativePlacementContinuity.class.getName() + "$SelectedCandidateProof");
		Constructor<?> selectedConstructor = selectedType.getDeclaredConstructors()[0];
		selectedConstructor.setAccessible(true);
		Object selected = selectedConstructor.newInstance(reference, List.of(), true, witness);
		Class<?> keyType = Class.forName(
			NativePlacementContinuity.class.getName() + "$AcyclicSummaryRowKey");
		Constructor<?> keyConstructor = keyType.getDeclaredConstructor(selectedType);
		keyConstructor.setAccessible(true);
		return keyConstructor.newInstance(selected);
	}

	@SuppressWarnings("unchecked")
	private static Set<ValueVersionKey> broadcastCapableValueVersions(
		NativePlacementContinuity continuity) throws ReflectiveOperationException {
		return (Set<ValueVersionKey>)accessibleField(
			NativePlacementContinuity.class, "broadcastCapableValueVersions").get(continuity);
	}

	private static Set<ValueVersionKey> coldBroadcastCapableValueVersions(
		Map<CompiledHopKey,Node> nodes) {
		Set<ValueVersionKey> result = new java.util.HashSet<>();
		for(Node node : nodes.values())
			if(node.legalAlternatives().stream().anyMatch(state ->
				state.output() == FederatedOutput.FOUT && state.fType() == FType.BROADCAST))
				result.add(node.valueVersion());
		return Set.copyOf(result);
	}

	@SuppressWarnings("unchecked")
	private static List<?> dependencySkeletons(NativePlacementContinuity resolver,
		CandidateRuleFact fact, CandidateRealizationSupportClause clause, Hop owner,
		DurableAnchorKey anchor) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, anchor);
		Method skeletons = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateDependencySkeletons", CandidateRuleFact.class,
			CandidateRealizationSupportClause.class, Hop.class, witness.getClass());
		skeletons.setAccessible(true);
		return (List<?>)skeletons.invoke(resolver, fact, clause, owner, witness);
	}

	private static long skeletonCounter(NativePlacementContinuity resolver, String name)
		throws Exception {
		return accessibleField(NativePlacementContinuity.class, name).getLong(resolver);
	}

	private static Object replayProof(NativePlacementContinuity resolver,
		CandidateRealizationReference reference, DurableAnchorKey seed) throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"proveCandidateReplay", CandidateRealizationReference.class, DurableAnchorKey.class);
		method.setAccessible(true);
		return method.invoke(resolver, reference, seed);
	}

	@SuppressWarnings("unchecked")
	private static List<NativePlacementContinuity.NativeContinuityProof> replayProofs(Object result)
		throws Exception {
		Method method = result.getClass().getDeclaredMethod("proofs");
		method.setAccessible(true);
		return (List<NativePlacementContinuity.NativeContinuityProof>)method.invoke(result);
	}

	private static Object replayReceipt(Object result) throws Exception {
		Method method = result.getClass().getDeclaredMethod("receipt");
		method.setAccessible(true);
		return method.invoke(result);
	}

	private static boolean matchesReplayReceipt(NativePlacementContinuity resolver,
		Object receipt, CandidateRealizationReference reference, DurableAnchorKey seed)
		throws Exception {
		Method method = NativePlacementContinuity.class.getDeclaredMethod(
			"matchesReplayProofReceipt", receipt.getClass(),
			CandidateRealizationReference.class, DurableAnchorKey.class);
		method.setAccessible(true);
		return (boolean)method.invoke(resolver, receipt, reference, seed);
	}

	private static long replayReceiptCounter(NativePlacementContinuity resolver, String name)
		throws Exception {
		return accessibleField(NativePlacementContinuity.class, name).getLong(resolver);
	}

	@SuppressWarnings("unchecked")
	private static Object referenceResidency(Constructor<?> constructor, Field fTypeField,
		Field endpointsField, Field intervalsField, Field exactField, Object source, FType target)
		throws ReflectiveOperationException {
		FType sourceType = (FType) fTypeField.get(source);
		List<String> endpoints = (List<String>) endpointsField.get(source);
		List<Object> intervals = (List<Object>) intervalsField.get(source);
		boolean exact = exactField.getBoolean(source);
		if(target == null || target == FType.PART || target == FType.OTHER)
			return null;
		if(target == sourceType)
			return source;
		if(target == FType.BROADCAST || sourceType == FType.BROADCAST)
			return null;
		if((sourceType == FType.ROW && target == FType.COL)
			|| (sourceType == FType.COL && target == FType.ROW))
			return constructor.newInstance(target, endpoints, intervals, exact);
		if(endpoints.size() != 1)
			return null;
		if(sourceType == FType.FULL && (target == FType.ROW || target == FType.COL))
			return constructor.newInstance(target, endpoints, List.of(), false);
		if(target == FType.FULL && (sourceType == FType.ROW || sourceType == FType.COL))
			return constructor.newInstance(FType.FULL, endpoints, List.of(), true);
		return null;
	}

	@Test
	public void nativeFullLeftIndexChainKeepsOnlyItsGroundedRhsWorker() {
		Fixture f = new Fixture(FType.FULL);
		Ref local = f.read("localZeros");
		Ref rhs = f.source("rhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref first = f.leftIndex("first", local, rhs, false, true, false);
		Ref write = f.write("probabilities", first, NodeKind.TRANSIENT_WRITE, false);
		Ref alias = f.logicalRead("probabilities");
		f.reaching.put(alias.key, List.of(write.key));
		Ref second = f.leftIndex("second", alias, rhs, true, true, false);
		Assert.assertTrue("Only PRESENT FULL operands ground a native LIX result",
			f.resolver().proves(List.of(first.key, alias.key, second.key), rhs.anchor));
		Assert.assertTrue("Continuity is not a fabricated output FederationMap", f.nodes.get(first.key).anchors().isEmpty());
	}

	@Test
	public void leftIndexRequiresAnExactNativeRowAndEveryProtectedInput() {
		Fixture f = new Fixture(FType.FULL);
		Ref local = f.read("local");
		Ref rhs = f.source("rhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref other = f.source("other", anchor(FType.FULL, "worker2:8002", 0, 4));
		Ref foreign = f.leftIndex("foreign", other, rhs, true, true, false);
		Ref unknown = f.leftIndex("unknown", rhs, local, true, true, false);
		Ref forged = f.leftIndex("forgedLocalRhs", rhs, local, true, false, false);
		Ref derived = f.leftIndex("derived", local, rhs, false, true, true);
		Ref missing = f.leftIndex("missing", local, rhs, false, true, false);
		f.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == missing.key);
		for(Ref invalid : List.of(foreign, unknown, forged, derived, missing))
			Assert.assertFalse("Invalid native LIX proof: " + invalid.hop.getName(),
				f.resolver().proves(List.of(invalid.key), rhs.anchor));
	}

	@Test
	public void inheritedAnchorDoesNotCertifyDerivedOnlyLeftIndex() {
		Fixture f = new Fixture(FType.FULL);
		Ref local = f.read("local");
		Ref rhs = f.source("rhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref derived = f.leftIndex("derived", local, rhs, false, true, true);
		Node node = f.nodes.get(derived.key);
		f.nodes.put(derived.key, new Node(derived.key, NodeKind.OPERATION, node.valueVersion(),
			true, List.of(state(FType.FULL)), List.of(), List.of(rhs.anchor)));
		Assert.assertFalse("An inherited endpoint is not a native instruction row",
			f.resolver().proves(List.of(derived.key), rhs.anchor));
	}

	@Test
	public void scalarLeftIndexRetainsOnlyAProvenFullLhs() {
		Fixture f = new Fixture(FType.FULL);
		Ref lhs = f.source("lhs", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref scalar = f.add("scalar", new LiteralOp(1L), NodeKind.OPERATION, VersionKind.ORDINARY, null);
		Ref remote = f.leftIndex("remote", lhs, scalar, true, false, false);
		Assert.assertTrue(f.resolver().proves(List.of(remote.key), lhs.anchor));
		Ref local = f.leftIndex("local", f.read("unknown"), scalar, false, false, false);
		Assert.assertFalse(f.resolver().proves(List.of(local.key), lhs.anchor));
		DurableAnchorKey ranges = new DurableAnchorKey("multi", FType.FULL, List.of(
			partition("worker1:8001", 0, 2), partition("worker1:8001", 2, 4)));
		Ref multi = f.source("multi", ranges);
		Ref matrix = f.leftIndex("multiRhs", lhs, multi, true, true, false);
		Assert.assertFalse("One endpoint with two ranges cannot run the single-FULL kernel",
			f.resolver().proves(List.of(matrix.key), lhs.anchor));
	}

	@Test
	public void provesGrowingFullRowAndColIncludingLoopCycle() {
		Fixture typedRow = new Fixture(FType.ROW);
		DurableAnchorKey externalRow = new DurableAnchorKey("external-value", FType.ROW,
			List.of(new AnchorPartition("worker1:8001", List.of(0L, 0L), List.of(4L, 2L))));
		DurableAnchorKey renamedRow = new DurableAnchorKey("different-value", FType.ROW,
			List.of(new AnchorPartition("worker1:8001", List.of(0L, 50L), List.of(4L, 99L))));
		Ref renamedRowSource = typedRow.source("renamed", renamedRow);
		Assert.assertTrue("ROW witnesses exclude placement id and the non-partitioned axis",
			typedRow.resolver().proves(List.of(renamedRowSource.key), externalRow));
		Ref nativeFederatedSource = typedRow.federatedSource("nativeFed", externalRow);
		Assert.assertTrue("An anchored FEDERATED source may have only ABSENT_LOCAL metadata inputs",
			typedRow.resolver().proves(List.of(nativeFederatedSource.key), externalRow));

		Fixture row = new Fixture(FType.ROW);
		Ref rowSeed = row.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref rowRead = row.logicalRead("B");
		Ref rowAppend = row.binary("cbind", OpOp2.CBIND, rowRead, rowSeed, false);
		Ref rowWrite = row.write("Bwrite", rowAppend, NodeKind.LOOP_PHI, false);
		row.reaching.put(rowRead.key, List.of(rowSeed.key, rowWrite.key));
		Assert.assertTrue(row.resolver().proves(List.of(rowRead.key), rowSeed.anchor));
		Ref rowScalar = row.matrixScalar("B+1", OpOp2.PLUS, rowSeed);
		Assert.assertTrue("Native matrix-scalar execution copies the complete federated map",
			row.resolver().proves(List.of(rowScalar.key), rowSeed.anchor));

		Fixture col = new Fixture(FType.COL);
		Ref colSeed = col.source("A", anchor(FType.COL, "worker1:8001", 0, 3));
		Ref colAppend = col.binary("rbind", OpOp2.RBIND, colSeed, colSeed, false);
		Assert.assertTrue(col.resolver().proves(List.of(colAppend.key), colSeed.anchor));

		Fixture full = new Fixture(FType.FULL);
		Ref fullSeed = full.source("A", anchor(FType.FULL, "worker1:8001", 0, 4));
		DurableAnchorKey renamedFull = new DurableAnchorKey("different-full-value", FType.FULL,
			List.of(new AnchorPartition("worker1:8001", List.of(90L, 80L), List.of(100L, 99L))));
		Ref renamedFullSource = full.source("renamedFull", renamedFull);
		Assert.assertTrue("Single-range FULL witnesses contain only the canonical endpoint",
			full.resolver().proves(List.of(renamedFullSource.key), fullSeed.anchor));
		Ref fullAppend = full.binary("cbind", OpOp2.CBIND, fullSeed, fullSeed, false);
		Ref fullTranspose = full.transpose("transpose", fullAppend, false);
		Assert.assertTrue(full.resolver().proves(List.of(fullAppend.key, fullTranspose.key), fullSeed.anchor));
	}

	@Test
	public void fullAppendEverySelectableRowRetainsTheSameOutputPool() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref append = full.binary("append", OpOp2.CBIND, loopRead, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal()));
		full.additionalCandidate(append, List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL)));
		Ref write = full.write("loopWrite", append, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));

		Assert.assertTrue("Every local/FULL append row retains the same FULL worker pool",
			full.resolver().proves(List.of(loopRead.key), seed.anchor));
	}

	@Test
	@Ignore("PUBLIC-only privacy fixture is excluded by the repository test policy")
	public void publicSourceWithoutDirectBroadcastKeepsRelocationRowActive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("A public source may still reach BROADCAST through explicit relocation",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void protectedSourceWithoutDirectBroadcastMakesRelocationRowInactive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertTrue("Origin residency makes the unsupported BROADCAST relocation row inactive",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void releasedAggregateSourceKeepsBroadcastRelocationRowActive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("Released aggregate data may still use explicit BROADCAST relocation",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void protectedSourceWithSelectableBroadcastAliasKeepsRowActive() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.broadcastAlias("seed-alias", seed);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("Any same-value BROADCAST alias keeps the exact row selectable",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void protectedSourceCannotUseBroadcastAliasForAnotherValueVersion() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		Ref other = full.source("other", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.broadcastAlias("other-alias", other);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertTrue("A BROADCAST alias for a different value version cannot authorize the row",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void broadcastCapabilityRefreshesWithNodeAuthorityAndSharesFactRevisions() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		Ref alias = full.broadcastAlias("seed-alias", seed);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));
		NativePlacementContinuity initial = full.resolver();
		Assert.assertFalse(initial.proves(List.of(append.key), seed.anchor));

		Field index = accessibleField(initial.getClass(), "broadcastCapableValueVersions");
		Assert.assertSame("candidate-only revisions retain the exact structural membership index",
			index.get(initial), index.get(initial.nextRevision(List.copyOf(full.candidates))));

		Node prior = full.nodes.get(alias.key);
		Node withoutBroadcast = new Node(prior.key(), prior.kind(), prior.valueVersion(),
			prior.emittedWork(), List.of(state(FType.FULL)), prior.exclusions(), prior.anchors());
		NativePlacementContinuity revised = initial.nextNodeAuthorityRevision(withoutBroadcast, List.of());
		Assert.assertNotSame("node-authority changes rebuild the structural membership index",
			index.get(initial), index.get(revised));
		Assert.assertTrue("removing the last same-version BROADCAST alternative disables the row",
			revised.proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void fullAppendWithBroadcastOnAnotherPoolIsNotNativeFullContinuity() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref broadcast = full.source("broadcast",
			anchor(FType.BROADCAST, "worker2:8002", 0, 50));
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, seed, broadcast);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));

		Assert.assertFalse("A native or unbound BROADCAST emission has no materialization authority",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void fullAppendWithBroadcastLeftOperandCannotClaimFullOutputContinuity() {
		Fixture full = new Fixture(FType.FULL);
		Ref broadcast = full.source("broadcast",
			anchor(FType.BROADCAST, "worker1:8001", 0, 50));
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, broadcast, seed);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.BROADCAST),
			CandidateInputState.present(FType.FULL)));

		Assert.assertFalse("Aligned append copies the left BROADCAST map, not a FULL map",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void fullAppendOneSelectableRowOnAnotherPoolInvalidatesTheProof() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = full.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, loopRead, other);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal()));
		full.additionalCandidate(append, List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL)));
		Ref write = full.write("loopWrite", append, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));

		Assert.assertFalse(full.resolver().proves(List.of(loopRead.key), seed.anchor));
	}

	@Test
	public void candidateSpecificProofKeepsGoodSiblingAndRejectsBadSibling() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = full.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref append = full.binaryWithoutCandidate("append", OpOp2.CBIND, loopRead, other);
		List<CandidateInputState> good = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.absentLocal());
		List<CandidateInputState> bad = List.of(CandidateInputState.absentLocal(),
			CandidateInputState.present(FType.FULL));
		full.additionalCandidate(append, good);
		full.additionalCandidate(append, bad);
		Ref write = full.write("loopWrite", append, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));

		NativePlacementContinuity resolver = full.resolver();
		Assert.assertNotNull("A candidate grounded on the seed pool remains executable",
			resolver.proveCandidate(full.reference(append, good), seed.anchor));
		Assert.assertNull("An incompatible sibling candidate cannot borrow the seed proof",
			resolver.proveCandidate(full.reference(append, bad), seed.anchor));
	}

	@Test
	public void completedProofMemoIsContextExactBoundedAndSemanticallyTransparent() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 2, 16);

		List<NativePlacementContinuity.NativeContinuityProof> first =
			resolver.proveCandidateAlternatives(reference, seed.anchor);
		long builtAfterFirst = metrics.snapshot().proofGraphsBuilt();
		long topologyBuiltAfterFirst = metrics.snapshot().topologyExpansionBuilds();
		List<NativePlacementContinuity.NativeContinuityProof> second =
			resolver.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertSame("an immutable completed result may be reused in one snapshot", first, second);
		Assert.assertEquals(builtAfterFirst, metrics.snapshot().proofGraphsBuilt());
		Assert.assertEquals(1, metrics.snapshot().memoHits());
		Assert.assertEquals(1, metrics.snapshot().memoMisses());

		DurableAnchorKey differentProvenance = new DurableAnchorKey("different-provenance", FType.FULL,
			seed.anchor.partitions());
		List<NativePlacementContinuity.NativeContinuityProof> distinctSeed =
			resolver.proveCandidateAlternatives(reference, differentProvenance);
		Assert.assertEquals(2, metrics.snapshot().memoMisses());
		Assert.assertEquals("physical support is independent of seed provenance",
			builtAfterFirst, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue("the support solution is reused before attaching provenance",
			metrics.snapshot().supportMemoHits() > 0);
		Assert.assertEquals("seed provenance stays in the query overlay, not shared topology",
			topologyBuiltAfterFirst, metrics.snapshot().topologyExpansionBuilds());
		Assert.assertTrue("the second seed reuses root-independent occurrence expansion",
			metrics.snapshot().topologyExpansionHits() > 0);
		Assert.assertTrue(distinctSeed.stream().allMatch(proof ->
			proof.externalSeed().equals(differentProvenance)));

		SearchSpaceMetrics zeroMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity zeroBudget = full.resolver(zeroMetrics, 0, 0);
		Assert.assertEquals(first, zeroBudget.proveCandidateAlternatives(reference, seed.anchor));
		long zeroTopologyBuilds = zeroMetrics.snapshot().topologyExpansionBuilds();
		Assert.assertEquals(first, zeroBudget.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(0, zeroMetrics.snapshot().memoHits());
		Assert.assertEquals(2, zeroMetrics.snapshot().memoMisses());
		Assert.assertTrue(zeroMetrics.snapshot().proofGraphsBuilt() > builtAfterFirst);
		Assert.assertEquals("result-cache eviction may rebuild overlays, never unchanged topology",
			zeroTopologyBuilds, zeroMetrics.snapshot().topologyExpansionBuilds());

		SearchSpaceMetrics evictionMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity oneEntry = full.resolver(evictionMetrics, 1, 16);
		Assert.assertEquals(first, oneEntry.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(distinctSeed,
			oneEntry.proveCandidateAlternatives(reference, differentProvenance));
		Assert.assertEquals(first, oneEntry.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("eviction must only cause exact recomputation", 2,
			evictionMetrics.snapshot().memoEvictions());
		Assert.assertEquals(0, evictionMetrics.snapshot().memoHits());
		Assert.assertEquals(3, evictionMetrics.snapshot().memoMisses());
		Assert.assertEquals(1, evictionMetrics.snapshot().memoEntries());
		Assert.assertTrue(evictionMetrics.snapshot().memoRetainedEstimatedBytes() > 0);

		SearchSpaceMetrics byteMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity byteBudget = full.resolver(byteMetrics, 2, 16, 1);
		Assert.assertEquals(first, byteBudget.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(first, byteBudget.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("an oversized completed result is recomputed, never truncated", 0,
			byteMetrics.snapshot().memoHits());
		Assert.assertEquals(0, byteMetrics.snapshot().memoEntries());
	}

	@Test
	public void freshQueryStateSharesImmutableSnapshotButNoQueryHistory() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(source, Privacy.PRIVATE_AGGREGATE);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		List<CandidateRuleFact> expectedFacts = List.copyOf(full.candidates);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity populated = full.resolver(metrics, 8, 128, 1024 * 1024);
		NativePlacementContinuity cold = full.resolver(new SearchSpaceMetrics(), 8, 128, 1024 * 1024);
		full.candidates.add(expectedFacts.get(0));
		List<NativePlacementContinuity.NativeContinuityProof> populatedProofs =
			populated.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertFalse(populatedProofs.isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"candidateTopologies").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"completedProofMemo").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"completedSupportMemo").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"candidateHandleByReference").get(populated)).isEmpty());
		Assert.assertFalse(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"nativeWitnessByAnchor").get(populated)).isEmpty());
		for(String fieldName : List.of("requiredInputSupportByClause", "canonicalEndpointByWorker",
			"candidateHandleByStructure", "acyclicRootSupportMemo", "acyclicComponentMemo"))
			Assert.assertFalse(fieldName, ((Map<?,?>)accessibleField(
				NativePlacementContinuity.class, fieldName).get(populated)).isEmpty());
		Assert.assertTrue(accessibleField(NativePlacementContinuity.class,
			"nextCandidateHandle").getInt(populated) < -1);
		for(String fieldName : List.of("topologyRetainedRows", "memoRetainedProofs",
			"memoRetainedEstimatedBytes", "supportMemoRetainedTemplates",
			"supportMemoRetainedEstimatedBytes", "acyclicComponentRetainedStates",
			"acyclicComponentRetainedAlternatives"))
			Assert.assertTrue(fieldName, accessibleField(NativePlacementContinuity.class,
				fieldName).getLong(populated) > 0);
		Object populatedQueryObserver = accessibleField(NativePlacementContinuity.class,
			"observedQueries").get(populated);
		Assert.assertTrue(accessibleField(populatedQueryObserver.getClass(),
			"size").getInt(populatedQueryObserver) > 0);

		NativePlacementContinuity fresh = populated.freshQueryState();
		Assert.assertNotSame(populated, fresh);
		Assert.assertSame(accessibleField(NativePlacementContinuity.class, "structuralContext").get(populated),
			accessibleField(NativePlacementContinuity.class, "structuralContext").get(fresh));
		Assert.assertSame(metrics, accessibleField(NativePlacementContinuity.class, "metrics").get(fresh));

		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,List<CandidateRuleFact>> retainedFacts =
			(Map<CompiledHopKey,List<CandidateRuleFact>>)accessibleField(
				NativePlacementContinuity.class, "candidateFactsByKey").get(fresh);
		Map<CompiledHopKey,List<CandidateRuleFact>> expectedByOwner = new IdentityHashMap<>();
		for(CandidateRuleFact fact : expectedFacts)
			expectedByOwner.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
		Assert.assertEquals(expectedByOwner.size(), retainedFacts.size());
		for(var entry : expectedByOwner.entrySet()) {
			List<CandidateRuleFact> retained = retainedFacts.get(entry.getKey());
			Assert.assertEquals(entry.getValue().size(), retained.size());
			for(int index = 0; index < retained.size(); index++)
				Assert.assertSame("owner-slice order and authority must be retained",
					entry.getValue().get(index), retained.get(index));
			Assert.assertThrows(UnsupportedOperationException.class,
				() -> retained.add(entry.getValue().get(0)));
		}
		Assert.assertSame("cold query states share the immutable candidate snapshot",
			accessibleField(NativePlacementContinuity.class,
			"candidateFactsByKey").get(populated), accessibleField(NativePlacementContinuity.class,
			"candidateFactsByKey").get(fresh));
		Assert.assertThrows(UnsupportedOperationException.class,
			() -> retainedFacts.remove(source.key));

		NativePlacementContinuity revised = populated.nextOwnerRevision(source.key, List.of());
		@SuppressWarnings("unchecked")
		Map<CompiledHopKey,List<CandidateRuleFact>> revisedFacts =
			(Map<CompiledHopKey,List<CandidateRuleFact>>)accessibleField(
				NativePlacementContinuity.class, "candidateFactsByKey").get(revised);
		Assert.assertNotSame("a candidate revision must fork immutable authority",
			retainedFacts, revisedFacts);
		Assert.assertTrue(retainedFacts.containsKey(source.key));
		Assert.assertFalse(revisedFacts.containsKey(source.key));
		Assert.assertSame("the rebased snapshot is reusable by its own cold queries",
			revisedFacts, accessibleField(NativePlacementContinuity.class,
				"candidateFactsByKey").get(revised.freshQueryState()));

		for(String fieldName : List.of("memoMaxEntries", "memoMaxProofs", "memoMaxEstimatedBytes",
			"supportMemoMaxEntries", "supportMemoMaxTemplates", "supportMemoMaxEstimatedBytes",
			"acyclicComponentMaxEntries", "acyclicComponentMaxStates",
			"acyclicComponentMaxAlternatives", "topologyMaxEntries", "topologyMaxRows"))
			Assert.assertEquals(fieldName, accessibleField(NativePlacementContinuity.class, fieldName).get(populated),
				accessibleField(NativePlacementContinuity.class, fieldName).get(fresh));

		for(String fieldName : List.of("requiredInputSupportByClause", "nativeWitnessByAnchor",
			"canonicalEndpointByWorker", "candidateHandleByReference", "candidateHandleByStructure",
			"candidateTopologies", "completedProofMemo", "completedSupportMemo",
			"acyclicRootSupportMemo", "acyclicComponentMemo")) {
			Object before = accessibleField(NativePlacementContinuity.class, fieldName).get(populated);
			Object after = accessibleField(NativePlacementContinuity.class, fieldName).get(fresh);
			Assert.assertNotSame(fieldName, before, after);
			Assert.assertTrue(fieldName, ((Map<?,?>)after).isEmpty());
		}
		for(String fieldName : List.of("topologyRetainedRows", "memoRetainedProofs",
			"memoRetainedEstimatedBytes", "supportMemoRetainedTemplates",
			"supportMemoRetainedEstimatedBytes", "acyclicComponentRetainedStates",
			"acyclicComponentRetainedAlternatives"))
			Assert.assertEquals(fieldName, 0L,
				accessibleField(NativePlacementContinuity.class, fieldName).getLong(fresh));
		Assert.assertEquals(-1, accessibleField(NativePlacementContinuity.class,
			"nextCandidateHandle").getInt(fresh));

		Object populatedObserver = accessibleField(NativePlacementContinuity.class,
			"observedQueries").get(populated);
		Object freshObserver = accessibleField(NativePlacementContinuity.class,
			"observedQueries").get(fresh);
		Assert.assertNotSame(populatedObserver, freshObserver);
		Assert.assertEquals(0, accessibleField(freshObserver.getClass(), "size").getInt(freshObserver));
		Assert.assertTrue(((Map<?,?>)accessibleField(freshObserver.getClass(),
			"buckets").get(freshObserver)).isEmpty());

		List<NativePlacementContinuity.NativeContinuityProof> expectedCold =
			cold.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertEquals(expectedCold, fresh.proveCandidateAlternatives(reference, seed.anchor));
	}

	@Test
	public void exactCandidateFactIdentityDetectsOnlyOwnerLocalAuthorityChanges() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref left = full.source("left", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref right = full.source("right", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref product = full.binary("product", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> alternateInputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.absentLocal());
		full.additionalCandidate(product, alternateInputs);
		List<CandidateRuleFact> facts = List.copyOf(full.candidates);
		NativePlacementContinuity continuity = full.resolver();

		Assert.assertTrue(continuity.hasSameCandidateFactObjects(facts));
		Assert.assertTrue("a copied inventory retains exact fact identity",
			continuity.hasSameCandidateFactObjects(List.copyOf(facts)));

		List<CandidateRuleFact> productFacts = facts.stream()
			.filter(fact -> fact.key().parentOccurrence() == product.key).toList();
		Assert.assertTrue(productFacts.size() >= 2);
		List<CandidateRuleFact> ownerGroupReordered = new ArrayList<>(productFacts);
		for(CandidateRuleFact fact : facts)
			if(fact.key().parentOccurrence() != product.key)
				ownerGroupReordered.add(fact);
		Assert.assertTrue("global owner order is outside continuity semantics",
			continuity.hasSameCandidateFactObjects(ownerGroupReordered));

		List<CandidateRuleFact> withinOwnerReordered = new ArrayList<>(facts);
		int first = withinOwnerReordered.indexOf(productFacts.get(0));
		int second = withinOwnerReordered.indexOf(productFacts.get(1));
		withinOwnerReordered.set(first, productFacts.get(1));
		withinOwnerReordered.set(second, productFacts.get(0));
		Assert.assertFalse("owner-local order is canonical authority",
			continuity.hasSameCandidateFactObjects(withinOwnerReordered));

		CandidateRuleFact original = facts.get(0);
		CandidateRuleFact equalForeignFact = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(),
			original.allowedEmissionFacts(), original.failureCode());
		Assert.assertEquals(original, equalForeignFact);
		Assert.assertNotSame(original, equalForeignFact);
		List<CandidateRuleFact> factReplaced = new ArrayList<>(facts);
		factReplaced.set(0, equalForeignFact);
		Assert.assertFalse("structural equality cannot replace exact fact authority",
			continuity.hasSameCandidateFactObjects(factReplaced));

		CompiledHopKey owner = original.key().parentOccurrence();
		CompiledHopKey foreignOwner = new CompiledHopKey(owner.programFingerprint(),
			owner.functionNamespace(), owner.callSitePath(), owner.recompileContext(),
			owner.controlRegion(), owner.emittedHopInstance(), owner.canonicalSourceOrigin());
		Assert.assertEquals(owner, foreignOwner);
		Assert.assertNotSame(owner, foreignOwner);
		CandidateRuleFact foreignOwnerFact = new CandidateRuleFact(new CandidateRuleKey(
			foreignOwner, original.key().orderedInputs()), original.status(), original.capability(),
			original.shapeProof(), original.profile(), original.allowedEmissionFacts(),
			original.failureCode());
		List<CandidateRuleFact> ownerReplaced = new ArrayList<>(facts);
		ownerReplaced.set(0, foreignOwnerFact);
		Assert.assertFalse("an equal foreign owner has no authority",
			continuity.hasSameCandidateFactObjects(ownerReplaced));

		Assert.assertFalse(continuity.hasSameCandidateFactObjects(facts.subList(1, facts.size())));
		List<CandidateRuleFact> added = new ArrayList<>(facts);
		added.add(original);
		Assert.assertFalse(continuity.hasSameCandidateFactObjects(added));

		CandidateRealizationReference reference = full.reference(product,
			List.of(CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)));
		continuity.proveCandidateAlternatives(reference, left.anchor);
		((BinaryOp)product.hop).setOp(OpOp2.MINUS);
		product.hop.setDim1(17);
		product.hop.setDim2(23);
		Assert.assertTrue("mutable Hop operation and shape do not change exact fact authority",
			continuity.hasSameCandidateFactObjects(facts));
		NativePlacementContinuity fresh = continuity.freshQueryState();
		NativePlacementContinuity revision = continuity.nextRevision(facts);
		List<CandidateRuleFact> equalCopies = facts.stream().map(fact -> new CandidateRuleFact(
			fact.key(), fact.status(), fact.capability(), fact.shapeProof(), fact.profile(),
			fact.allowedEmissionFacts(), fact.failureCode())).toList();
		NativePlacementContinuity copiedRevision = continuity.nextRevision(equalCopies);
		Field snapshot = accessibleField(NativePlacementContinuity.class, "candidateFactsSnapshot");
		Assert.assertSame("an exact inventory must retain its immutable candidate index",
			snapshot.get(continuity), snapshot.get(revision));
		Assert.assertNotSame("new fact authority must receive a new immutable candidate index",
			snapshot.get(continuity), snapshot.get(copiedRevision));
		Assert.assertEquals(0, revision.revisionComparisonSnapshot().ownersCompared());
		Assert.assertTrue("exact identity must bypass every populated owner comparison",
			revision.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);
		List<NativePlacementContinuity.NativeContinuityProof> cold = full.resolver()
			.proveCandidateAlternatives(reference, left.anchor);
		Assert.assertEquals("snapshot reuse must still start a cold query after Hop mutation", cold,
			fresh.proveCandidateAlternatives(reference, left.anchor));
		List<NativePlacementContinuity.NativeContinuityProof> revisedProofs =
			revision.proveCandidateAlternatives(reference, left.anchor);
		Assert.assertEquals(cold, revisedProofs);
		Assert.assertEquals("snapshot reuse must preserve ordinary revision memo migration",
			copiedRevision.proveCandidateAlternatives(reference, left.anchor), revisedProofs);
		for(String fieldName : List.of("candidateTopologies", "completedProofMemo",
			"completedSupportMemo", "acyclicRootSupportMemo", "acyclicComponentMemo"))
			Assert.assertEquals(fieldName, ((Map<?,?>)accessibleField(
				NativePlacementContinuity.class, fieldName).get(copiedRevision)).size(),
				((Map<?,?>)accessibleField(
					NativePlacementContinuity.class, fieldName).get(revision)).size());
	}

	@Test
	public void templateSupportMemoRebindsFallbackRootsWithoutChangingProofs() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference staged = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateRealizationReference firstRoot = new CandidateRealizationReference(staged.rule(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				staged.realization().emissionState(), "template-root:first"));
		CandidateRealizationReference secondRoot = new CandidateRealizationReference(staged.rule(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				staged.realization().emissionState(), "template-root:second"));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		Assert.assertFalse(resolver.proveCandidateAlternatives(firstRoot, seed.anchor).isEmpty());
		long graphBuilds = metrics.snapshot().proofGraphsBuilt();

		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(secondRoot, seed.anchor);
		NativePlacementContinuity uncached = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertEquals(uncached.proveCandidateAlternatives(secondRoot, seed.anchor), actual);
		Assert.assertEquals("fallback roots with the same rule/emission share one graph solution",
			graphBuilds, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(metrics.snapshot().supportMemoHits() > 0);
	}

	@Test
	public void siblingRealizationQueriesShareOneAcyclicRootRelation() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(root, inputs,
			new DurableAnchorKey("root-a", FType.FULL, seed.anchor.partitions()),
			new DurableAnchorKey("root-b", FType.FULL, seed.anchor.partitions()));
		CandidateRuleFact fact = full.fact(root, inputs);
		List<CandidateRealizationReference> references = fact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationReference.of(
				fact.key(), realization)).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		NativePlacementContinuity.CandidateSupportResult first =
			resolver.proveCandidateSupport(references.get(0), seed.anchor);
		Assert.assertFalse(first.proofs().isEmpty());
		Assert.assertTrue(first.dependencyOccurrences().contains(root.key));
		Assert.assertTrue(first.dependencyOccurrences().contains(seed.key));
		long graphs = metrics.snapshot().proofGraphsBuilt();
		Assert.assertFalse(resolver.proveCandidateAlternatives(references.get(1), seed.anchor).isEmpty());
		Assert.assertEquals("one acyclic root relation must serve every exact realization view",
			graphs, metrics.snapshot().proofGraphsBuilt());
	}

	@Test
	public void siblingRealizationsWithDifferentDependencyTuplesDoNotShareRootRelation() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.candidateLogicalRead(seed);
		CandidateRuleFact originalSeedFact = full.candidates.stream()
			.filter(candidate -> candidate.key().parentOccurrence() == seed.key).findFirst().orElseThrow();
		List<CandidateInputState> seedInputs = originalSeedFact.key().orderedInputs();
		full.samePoolRealizations(seed, seedInputs,
			new DurableAnchorKey("seed-a", FType.FULL, seed.anchor.partitions()),
			new DurableAnchorKey("seed-b", FType.FULL, seed.anchor.partitions()));
		CandidateRuleFact seedFact = full.fact(seed, seedInputs);
		List<CandidateRealizationReference> seedReferences = seedFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationReference.of(
				seedFact.key(), realization)).toList();
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact rootFact = full.fact(root, inputs);
		CandidateEmissionFact emission = rootFact.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> rootRealizations = java.util.stream.IntStream.range(0, 2)
			.mapToObj(index -> CandidateEmissionRealization.durable(emission.emissionState(),
				new DurableAnchorKey("root-" + index, FType.FULL, seed.anchor.partitions()), List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, seedReferences.get(index)))))
			.toList();
		CandidateEmissionFact replacement = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), rootRealizations);
		full.candidates.set(full.candidates.indexOf(rootFact), new CandidateRuleFact(rootFact.key(),
			rootFact.status(), rootFact.capability(), rootFact.shapeProof(), rootFact.profile(),
			List.of(replacement), rootFact.failureCode()));
		List<CandidateRealizationReference> roots = rootRealizations.stream()
			.map(realization -> CandidateRealizationReference.of(rootFact.key(), realization)).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		resolver.proveCandidateAlternatives(roots.get(0), seed.anchor);
		long graphs = metrics.snapshot().proofGraphsBuilt();
		resolver.proveCandidateAlternatives(roots.get(1), seed.anchor);
		Assert.assertTrue("different exact AND tuples require different pinned root relations",
			metrics.snapshot().proofGraphsBuilt() > graphs);
	}

	@Test
	public void distinctRootsReuseAcyclicGroundedChildComponent() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref sharedFirst = full.unary("sharedFirst", OpOp1.LOG, seed, false);
		Ref sharedSecond = full.unary("sharedSecond", OpOp1.ABS, sharedFirst, false);
		Ref firstRoot = full.unary("firstRoot", OpOp1.EXP, sharedSecond, false);
		Ref secondRoot = full.unary("secondRoot", OpOp1.SQRT, sharedSecond, false);
		CandidateRealizationReference firstReference = full.reference(firstRoot,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateRealizationReference secondReference = full.reference(secondRoot,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);

		List<NativePlacementContinuity.NativeContinuityProof> first =
			resolver.proveCandidateAlternatives(firstReference, seed.anchor);
		long firstStates = metrics.snapshot().proofStatesBuilt();
		long firstOverlays = metrics.snapshot().topologyOverlayEvaluations();
		Assert.assertFalse(first.isEmpty());

		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(secondReference, seed.anchor);
		long secondStates = metrics.snapshot().proofStatesBuilt() - firstStates;
		long secondOverlays = metrics.snapshot().topologyOverlayEvaluations() - firstOverlays;
		NativePlacementContinuity uncached = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertEquals("component reuse must preserve the ordered proof result",
			uncached.proveCandidateAlternatives(secondReference, seed.anchor), actual);
		Assert.assertTrue("a shared child is not rebuilt for a second query root",
			secondStates < firstStates);
		Assert.assertTrue("the second root must consume the child's grounded summary",
			secondOverlays < firstOverlays);

	}

	@Test
	public void distinctRootsReuseGroundedCyclicChildOutsidePinnedComponent() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loopRead = full.logicalRead("loopRead");
		Ref body = full.unary("body", OpOp1.LOG, loopRead, false);
		Ref write = full.write("loopWrite", body, NodeKind.LOOP_PHI, false);
		full.reaching.put(loopRead.key, List.of(seed.key, write.key));
		Ref firstRoot = full.unary("firstRoot", OpOp1.EXP, loopRead, false);
		Ref secondRoot = full.unary("secondRoot", OpOp1.SQRT, loopRead, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference firstReference = full.reference(firstRoot, inputs);
		CandidateRealizationReference secondReference = full.reference(secondRoot, inputs);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
		List<NativePlacementContinuity.NativeContinuityProof> first =
			resolver.proveCandidateAlternatives(firstReference, seed.anchor);
		Assert.assertFalse(first.isEmpty());
		long overlays = metrics.snapshot().topologyOverlayEvaluations();
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			resolver.proveCandidateAlternatives(secondReference, seed.anchor);
		Assert.assertEquals(full.resolver(new SearchSpaceMetrics(), 0, 0)
			.proveCandidateAlternatives(secondReference, seed.anchor), actual);
		Assert.assertTrue("the second root must consume the already grounded cyclic child",
			metrics.snapshot().topologyOverlayEvaluations() - overlays < overlays);
	}

	@Test
	public void acyclicComponentAlternativeBudgetBypassesWideBoundary() throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";
		String previous = System.getProperty(property);
		try {
			System.setProperty(property, "1");
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref shared = full.unary("shared", OpOp1.LOG, seed, false);
			List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
			full.samePoolRealizations(shared, inputs,
				new DurableAnchorKey("wide-1", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))),
				new DurableAnchorKey("wide-2", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))));
			Ref first = full.unary("first", OpOp1.EXP, shared, false);
			Ref second = full.unary("second", OpOp1.SQRT, shared, false);
			NativePlacementContinuity cached = full.resolver(new SearchSpaceMetrics(), 8, 128);
			cached.proveCandidateAlternatives(full.reference(first, inputs), seed.anchor);
			Field cacheField = accessibleField(NativePlacementContinuity.class, "acyclicComponentMemo");
			Map<?,?> summaries = (Map<?,?>) cacheField.get(cached);
			for(Object state : summaries.keySet()) {
				Field keyField = accessibleField(state.getClass(), "key");
				Assert.assertNotSame("oversized child must not be retained", shared.key,
					keyField.get(state));
			}
			CandidateRealizationReference secondReference = full.reference(second, inputs);
			Assert.assertEquals("budget fallback must preserve ordered proof results",
				full.resolver(new SearchSpaceMetrics(), 0, 0)
					.proveCandidateAlternatives(secondReference, seed.anchor),
				cached.proveCandidateAlternatives(secondReference, seed.anchor));
		}
		finally {
			if(previous == null)
				System.clearProperty(property);
			else
				System.setProperty(property, previous);
		}
	}

	@Test
	public void acyclicComponentBudgetCountsDistinctGroundedRowsAfterViability() throws Exception {
		String property = "sysds.fedplanner.continuityAcyclicComponent.maxAlternatives";
		String previous = System.getProperty(property);
		try {
			System.setProperty(property, "1");
			Fixture full = new Fixture(FType.BROADCAST);
			DurableAnchorKey pool = anchor(FType.BROADCAST, "worker1:8001", 0, 50);
			Ref seed = full.source("seed", pool);
			Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
			List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.BROADCAST));
			DurableAnchorKey[] equivalentPools = new DurableAnchorKey[64];
			for(int index = 0; index < equivalentPools.length; index++)
				equivalentPools[index] = new DurableAnchorKey("equivalent-pool-" + index,
					FType.BROADCAST, pool.partitions());
			full.samePoolRealizations(producer, inputs, equivalentPools);
			CandidateRuleFact producerFact = full.fact(producer, inputs);
			List<CandidateRealizationSupportClause> clauses = producerFact.allowedEmissionFacts().get(0)
				.realizations().stream().map(realization -> new CandidateRealizationSupportClause(
					List.of(), List.of(CandidateRealizationInputBinding.direct(0,
						CandidateRealizationReference.of(producerFact.key(), realization))))).toList();
			Ref shared = full.unary("shared", OpOp1.ABS, producer, false);
			full.withClauses(shared, inputs, clauses);
			Ref firstRoot = full.unary("firstRoot", OpOp1.EXP, shared, false);
			Ref secondRoot = full.unary("secondRoot", OpOp1.SQRT, shared, false);
			CandidateRealizationReference firstReference = full.reference(firstRoot, inputs);
			CandidateRealizationReference secondReference = full.reference(secondRoot, inputs);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity cached = full.resolver(metrics, 8, 128);

			NativePlacementContinuity.CandidateSupportResult first =
				cached.proveCandidateSupport(firstReference, pool);
			long firstStates = metrics.snapshot().proofStatesBuilt();
			Assert.assertFalse(first.proofs().isEmpty());
			Map<?,?> summaries = (Map<?,?>) accessibleField(
				NativePlacementContinuity.class, "acyclicComponentMemo").get(cached);
			Object sharedSummary = summaries.entrySet().stream().filter(entry -> {
				try {
					return accessibleField(entry.getKey().getClass(), "key").get(entry.getKey()) == shared.key;
				}
				catch(ReflectiveOperationException failure) {
					throw new AssertionError(failure);
				}
			}).map(Map.Entry::getValue).findFirst().orElseThrow(() ->
				new AssertionError("the distinct-row budget must retain the shared component"));
			@SuppressWarnings("unchecked")
			List<Object> retained = (List<Object>) accessibleField(
				sharedSummary.getClass(), "supportedAlternatives").get(sharedSummary);
			Assert.assertEquals("64 supported clauses collapse to one grounded summary row", 1,
				retained.size());
			Assert.assertEquals(64, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_SUPPORTED_ROWS_EXAMINED));
			Assert.assertEquals(63, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_DUPLICATE_ROWS_COLLAPSED));
			Assert.assertEquals(0, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_DISTINCT_BUDGET_BYPASSES));

			NativePlacementContinuity.CandidateSupportResult actual =
				cached.proveCandidateSupport(secondReference, pool);
			long secondStates = metrics.snapshot().proofStatesBuilt() - firstStates;
			NativePlacementContinuity.CandidateSupportResult cold =
				full.resolver(null, 0, 0).proveCandidateSupport(secondReference, pool);
			Assert.assertEquals("summary quotienting preserves ordered proofs", cold.proofs(), actual.proofs());
			assertIdentitySetEquals(cold.dependencyOccurrences(), actual.dependencyOccurrences());
			Assert.assertTrue("the second root must reuse the retained one-row child summary",
				secondStates < firstStates);
			Assert.assertTrue(metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSE_HITS) > 0);
			Assert.assertTrue(metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSED_ROWS) > 0);

			List<CandidateRuleFact> originalFacts = List.copyOf(full.candidates);
			CandidateRuleFact sharedFact = full.fact(shared, inputs);
			CandidateEmissionFact sharedEmission = sharedFact.allowedEmissionFacts().get(0);
			CandidateEmissionRealization sharedRealization = sharedEmission.realizations().get(0);
			CandidateRealizationReference deadReference = new CandidateRealizationReference(
				producerFact.key(), PlacementIdentity.PlacementRealizationKey.durable(
					producerFact.allowedEmissionFacts().get(0).emissionState(),
					new DurableAnchorKey("absent-pool-00000", FType.BROADCAST, pool.partitions())));
			List<CandidateRealizationSupportClause> mixedClauses = new ArrayList<>();
			mixedClauses.add(new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, deadReference))));
			mixedClauses.addAll(sharedRealization.supportClauses());
			CandidateEmissionRealization mixedRealization = new CandidateEmissionRealization(
				sharedRealization.key(), mixedClauses);
			Assert.assertSame("the canonical first clause must be the dead pinned alternative",
				deadReference, mixedRealization.supportClauses().get(0).inputBindings().get(0).source());
			CandidateEmissionFact mixedEmission = new CandidateEmissionFact(
				sharedEmission.emissionState(), sharedEmission.executionFType(),
				sharedEmission.derivedFoutAction(), List.of(mixedRealization));
			CandidateRuleFact mixedShared = new CandidateRuleFact(sharedFact.key(),
				sharedFact.status(), sharedFact.capability(), sharedFact.shapeProof(),
				sharedFact.profile(), List.of(mixedEmission), sharedFact.failureCode());
			List<CandidateRuleFact> mixedFacts = replaceFact(originalFacts, sharedFact, mixedShared);
			NativePlacementContinuity mixed = cached.nextRevisionWithCompleteCandidateDelta(
				mixedFacts, identitySet(shared.key));
			NativePlacementContinuity.CandidateSupportResult mixedWarm =
				mixed.proveCandidateSupport(firstReference, pool);
			Assert.assertFalse(mixedWarm.proofs().isEmpty());
			long mixedReuseBefore = metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSE_HITS);
			NativePlacementContinuity.CandidateSupportResult mixedActual =
				mixed.proveCandidateSupport(secondReference, pool);
			Assert.assertTrue("the second root must reuse the mixed live/dead summary",
				metrics.directWorkCount(SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSE_HITS)
					> mixedReuseBefore);
			NativePlacementContinuity.CandidateSupportResult mixedCold = new NativePlacementContinuity(
				full.nodes, full.origins, mixedFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateSupport(secondReference, pool);
			Assert.assertFalse("a dead first clause must not hide later supported duplicates",
				mixedActual.proofs().isEmpty());
			Assert.assertEquals(mixedCold.proofs(), mixedActual.proofs());
			assertIdentitySetEquals(
				mixedCold.dependencyOccurrences(), mixedActual.dependencyOccurrences());

			List<CandidateRuleFact> withdrawnFacts = mixedFacts.stream()
				.filter(fact -> fact != producerFact).toList();
			NativePlacementContinuity withdrawn = mixed.nextRevisionWithCompleteCandidateDelta(
				withdrawnFacts, identitySet(producer.key));
			NativePlacementContinuity.CandidateSupportResult negative =
				withdrawn.proveCandidateSupport(secondReference, pool);
			NativePlacementContinuity.CandidateSupportResult negativeCold = new NativePlacementContinuity(
				full.nodes, full.origins, withdrawnFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateSupport(secondReference, pool);
			Assert.assertTrue("withdrawing every producer alternative must invalidate the summary",
				negative.proofs().isEmpty());
			Assert.assertEquals(negativeCold.proofs(), negative.proofs());
			assertIdentitySetEquals(
				negativeCold.dependencyOccurrences(), negative.dependencyOccurrences());

			NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
				originalFacts, identitySet(producer.key, shared.key));
			NativePlacementContinuity.CandidateSupportResult restoredActual =
				restored.proveCandidateSupport(secondReference, pool);
			NativePlacementContinuity.CandidateSupportResult restoredCold = new NativePlacementContinuity(
				full.nodes, full.origins, originalFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateSupport(secondReference, pool);
			Assert.assertFalse(restoredActual.proofs().isEmpty());
			Assert.assertEquals(restoredCold.proofs(), restoredActual.proofs());
			assertIdentitySetEquals(
				restoredCold.dependencyOccurrences(), restoredActual.dependencyOccurrences());
		}
		finally {
			if(previous == null)
				System.clearProperty(property);
			else
				System.setProperty(property, previous);
		}
	}

	@Test
	public void acyclicComponentSummaryRowKeyUsesOwnerIdentityWitnessAndNullMarker()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey firstPool = anchor(FType.FULL, "worker1:8001", 0, 50);
		DurableAnchorKey secondPool = anchor(FType.FULL, "worker2:8002", 0, 50);
		Ref seed = full.source("seed", firstPool);
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = full.reference(root, inputs);
		CandidateRealizationReference equalReference = new CandidateRealizationReference(
			reference.rule(), reference.realization());
		CompiledHopKey foreignOwner = new CompiledHopKey(root.key.programFingerprint(),
			root.key.functionNamespace(), root.key.callSitePath(), root.key.recompileContext(),
			root.key.controlRegion(), root.key.emittedHopInstance(), root.key.canonicalSourceOrigin());
		Assert.assertEquals(root.key, foreignOwner);
		Assert.assertNotSame(root.key, foreignOwner);
		CandidateRealizationReference foreignReference = new CandidateRealizationReference(
			new CandidateRuleKey(foreignOwner, reference.rule().orderedInputs()), reference.realization());
		NativePlacementContinuity resolver = full.resolver();

		Object first = acyclicSummaryRowKey(resolver, reference, firstPool);
		Object equal = acyclicSummaryRowKey(resolver, equalReference, firstPool);
		Object differentWitness = acyclicSummaryRowKey(resolver, reference, secondPool);
		Object foreign = acyclicSummaryRowKey(resolver, foreignReference, firstPool);
		Assert.assertEquals(first, equal);
		Assert.assertNotEquals(first, differentWitness);
		Assert.assertNotEquals(first, foreign);
		Assert.assertNotEquals(acyclicSummaryRowKey(resolver, null, firstPool),
			acyclicSummaryRowKey(resolver, null, secondPool));
	}

	@Test
	public void unchangedFactRevisionReusesTopologyAndCompletedSupportSolution() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity firstRevision = full.resolver(metrics, 8, 128);
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			firstRevision.proveCandidateAlternatives(reference, seed.anchor);
		long graphBuilds = metrics.snapshot().proofGraphsBuilt();
		long topologyBuilds = metrics.snapshot().topologyExpansionBuilds();

		List<CandidateRuleFact> equalNewFacts = full.candidates.stream().map(fact ->
			new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), fact.allowedEmissionFacts(), fact.failureCode())).toList();
		NativePlacementContinuity conservativeRevision = firstRevision.nextRevision(equalNewFacts);
		NativePlacementContinuity nextRevision = firstRevision
			.nextRevisionWithCompleteCandidateDelta(List.copyOf(full.candidates), Set.of());
		Assert.assertSame("unchanged semantic and seed authority reuse the published proof",
			expected, nextRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals("hinted and conservative revisions must publish identical ordered proofs",
			conservativeRevision.proveCandidateAlternatives(reference, seed.anchor),
			nextRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertTrue("the complete unchanged-owner hint must bypass deep owner comparison",
			nextRevision.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);
		Assert.assertEquals(0, nextRevision.revisionComparisonSnapshot().ownersCompared());
		Assert.assertTrue("the conservative reference must still compare cached owners",
			conservativeRevision.revisionComparisonSnapshot().ownersCompared() > 0);
		Assert.assertEquals("the complete hint must replace every conservative owner comparison",
			conservativeRevision.revisionComparisonSnapshot().ownersCompared(),
			nextRevision.revisionComparisonSnapshot().hintedOwnersBypassed());
		Assert.assertEquals("the complete support relation is reused across the revision",
			graphBuilds, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(metrics.snapshot().memoHits() > 0);
		Assert.assertEquals("unchanged occurrence expansion crosses the revision",
			topologyBuilds, metrics.snapshot().topologyExpansionBuilds());
		Assert.assertTrue(metrics.snapshot().topologyRevisionEntriesReused() > 0);
		Assert.assertTrue(metrics.snapshot().supportMemoRevisionEntriesReused() > 0);

		// Exact row comparison invalidates every cached support that uses a changed
		// private execution projection.
		List<CandidateRuleFact> changedFacts = new ArrayList<>(full.candidates);
		int sourceIndex = -1;
		for(int i = 0; i < changedFacts.size(); i++)
			if(changedFacts.get(i).key().parentOccurrence() == source.key) {
				sourceIndex = i;
				break;
			}
		Assert.assertTrue(sourceIndex >= 0);
		CandidateRuleFact original = changedFacts.get(sourceIndex);
		CandidateEmissionFact emission = original.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> realizations = new ArrayList<>(emission.realizations());
		realizations.add(CandidateEmissionRealization.nativeLineage(emission.emissionState(),
			"revision-added-alternative", List.of(), List.of()));
		CandidateEmissionFact changedEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), realizations);
		changedFacts.set(sourceIndex, new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(), List.of(changedEmission),
			original.failureCode()));
		long beforeChangedRevision = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity changedRevision = firstRevision.nextRevisionWithCompleteCandidateDelta(
			changedFacts, identitySet(source.key));
		List<NativePlacementContinuity.NativeContinuityProof> changedActual =
			changedRevision.proveCandidateAlternatives(reference, seed.anchor);
		List<NativePlacementContinuity.NativeContinuityProof> changedConservative =
			firstRevision.nextRevision(changedFacts)
				.proveCandidateAlternatives(reference, seed.anchor);
		List<NativePlacementContinuity.NativeContinuityProof> changedFresh =
			new NativePlacementContinuity(full.nodes, full.origins, changedFacts,
				full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertEquals(changedConservative, changedActual);
		Assert.assertEquals(changedFresh, changedActual);
		Assert.assertTrue(changedRevision.revisionComparisonSnapshot().ownersCompared() > 0);
		Assert.assertTrue("unchanged owners in the affected proof footprint still bypass comparison",
			changedRevision.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);
		Assert.assertTrue(changedRevision.revisionComparisonSnapshot()
			.continuityProjectionsCompared() > 0);
		Assert.assertTrue("changed rows cannot reuse a stale support",
			metrics.snapshot().proofGraphsBuilt() > beforeChangedRevision);
	}

	@Test
	public void dependencySkeletonTemplateRebindsCurrentClauseAuthorityAcrossRevision()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.federatedSource("seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> seedInputs = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
		CandidateRealizationReference seedReference = full.reference(seed, seedInputs);
		List<CandidateInputState> rootInputs =
			List.of(CandidateInputState.present(FType.FULL));
		full.withClauses(root, rootInputs, List.of(new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, seedReference)))));
		CandidateRuleFact original = full.fact(root, rootInputs);
		CandidateRealizationSupportClause originalClause = original.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		NativePlacementContinuity first = full.resolver(new SearchSpaceMetrics(), 0, 0);
		List<?> originalSkeletons = dependencySkeletons(
			first, original, originalClause, root.hop, seed.anchor);
		long firstBuilds = skeletonCounter(first, "dependencySkeletonBuilds");
		Assert.assertFalse(originalSkeletons.isEmpty());

		CandidateRealizationReference currentSeedReference = new CandidateRealizationReference(
			new CandidateRuleKey(seed.key, seedInputs), seedReference.realization());
		Assert.assertEquals(seedReference, currentSeedReference);
		Assert.assertNotSame(seedReference, currentSeedReference);
		CandidateRealizationSupportClause currentClause = new CandidateRealizationSupportClause(
			originalClause.proofDependencies(),
			List.of(CandidateRealizationInputBinding.direct(0, currentSeedReference)),
			originalClause.nativeWorkerPoolWitness(), originalClause.nativeWorkerPoolLayoutExact());
		CandidateEmissionRealization currentRealization = new CandidateEmissionRealization(
			original.allowedEmissionFacts().get(0).realizations().get(0).key(), List.of(currentClause));
		CandidateEmissionFact currentEmission = new CandidateEmissionFact(
			original.allowedEmissionFacts().get(0).emissionState(),
			original.allowedEmissionFacts().get(0).executionFType(),
			original.allowedEmissionFacts().get(0).derivedFoutAction(), List.of(currentRealization));
		CandidateRuleFact currentFact = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(), List.of(currentEmission),
			original.failureCode());
		List<CandidateRuleFact> revisedFacts = new ArrayList<>(full.candidates);
		revisedFacts.set(revisedFacts.indexOf(original), currentFact);
		NativePlacementContinuity revised = first.nextRevision(revisedFacts);
		List<?> actual = dependencySkeletons(
			revised, currentFact, currentClause, root.hop, seed.anchor);

		Assert.assertEquals(originalSkeletons, actual);
		Assert.assertEquals(1, firstBuilds);
		Assert.assertEquals("an exact donor must avoid rebuilding the dependency relation", 0,
			skeletonCounter(revised, "dependencySkeletonBuilds"));
		Assert.assertEquals(1, skeletonCounter(revised, "dependencySkeletonReuses"));
		Assert.assertEquals(1, skeletonCounter(revised, "dependencySkeletonTemplatesCarried"));
		Object pinned = accessibleField(actual.get(0).getClass(), "clausePinned").get(actual.get(0));
		Assert.assertSame("the template must resolve the current clause's reference",
			currentSeedReference, pinned);

		NativePlacementContinuity cold = new NativePlacementContinuity(full.nodes, full.origins,
			revisedFacts, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(dependencySkeletons(
			cold, currentFact, currentClause, root.hop, seed.anchor), actual);
		Assert.assertEquals("the cold oracle must perform the eliminated builder work", 1,
			skeletonCounter(cold, "dependencySkeletonBuilds"));
		CandidateRealizationReference currentRoot = CandidateRealizationReference.of(
			currentFact.key(), currentRealization);
		Assert.assertEquals("warm and cold resolvers must publish identical full proofs",
			cold.proveCandidateAlternatives(currentRoot, seed.anchor),
			revised.proveCandidateAlternatives(currentRoot, seed.anchor));
	}

	@Test
	public void temporaryAndQueryResetClausesNeverEnterDependencySkeletonRevisionState()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs =
			List.of(CandidateInputState.present(FType.FULL));
		full.withClauses(root, inputs,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact fact = full.fact(root, inputs);
		CandidateRealizationSupportClause owned = fact.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		CandidateRealizationSupportClause temporary =
			new CandidateRealizationSupportClause(List.of(), List.of());
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertNotNull(dependencySkeletons(resolver, fact, owned, root.hop, seed.anchor));
		Map<?,?> retained = (Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(resolver);
		Assert.assertFalse(retained.isEmpty());
		int retainedFacts = retained.size();
		long beforeTemporary = skeletonCounter(resolver, "dependencySkeletonBuilds");

		Assert.assertEquals(dependencySkeletons(
			resolver, fact, temporary, root.hop, seed.anchor), dependencySkeletons(
				resolver, fact, temporary, root.hop, seed.anchor));
		Assert.assertEquals("an unowned generated clause must stay cold", beforeTemporary + 2,
			skeletonCounter(resolver, "dependencySkeletonBuilds"));
		Assert.assertEquals(0, skeletonCounter(resolver, "dependencySkeletonReuses"));
		Assert.assertEquals("temporary clauses cannot enlarge revision state", retainedFacts,
			((Map<?,?>)accessibleField(NativePlacementContinuity.class,
				"dependencySkeletonMemo").get(resolver)).size());

		NativePlacementContinuity fresh = resolver.freshQueryState();
		Assert.assertEquals(0, skeletonCounter(fresh, "dependencySkeletonBuilds"));
		Assert.assertTrue(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(fresh)).isEmpty());
		NativePlacementContinuity ownerRevision = resolver.nextOwnerRevision(
			root.key, List.of(fact));
		Assert.assertEquals(0, skeletonCounter(ownerRevision, "dependencySkeletonTemplatesCarried"));
		Assert.assertTrue(((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(ownerRevision)).isEmpty());
	}

	@Test
	public void dependencySkeletonQueryWitnessMismatchBuildsColdThenReusesExactWitness()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.withClauses(root, inputs,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact fact = full.fact(root, inputs);
		CandidateRealizationSupportClause clause = fact.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);
		DurableAnchorKey other = anchor(FType.FULL, "worker2:8002", 0, 50);

		dependencySkeletons(resolver, fact, clause, root.hop, seed.anchor);
		List<?> otherFirst = dependencySkeletons(resolver, fact, clause, root.hop, other);
		List<?> otherSecond = dependencySkeletons(resolver, fact, clause, root.hop, other);
		NativePlacementContinuity cold = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertEquals(dependencySkeletons(cold, fact, clause, root.hop, other), otherFirst);
		Assert.assertEquals(otherFirst, otherSecond);
		Assert.assertEquals("different witnesses require independent exact templates", 2,
			skeletonCounter(resolver, "dependencySkeletonBuilds"));
		Assert.assertEquals(1, skeletonCounter(resolver, "dependencySkeletonReuses"));
	}

	@Test
	public void exactSkeletonMemoCarriesWithoutCopiesAndForksOnFirstNewWitness()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("copy-seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("copy-root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.withClauses(root, inputs,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact fact = full.fact(root, inputs);
		CandidateRealizationSupportClause clause = fact.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		NativePlacementContinuity parent = full.resolver(new SearchSpaceMetrics(), 0, 0);
		List<?> baseline = dependencySkeletons(parent, fact, clause, root.hop, seed.anchor);
		Object parentMemo = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(parent)).get(fact);
		Object parentOwned = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"ownedCandidateClausesByFact").get(parent)).get(fact);
		Assert.assertNotNull(parentMemo);
		Assert.assertNotNull(parentOwned);
		Assert.assertThrows("the clause-ownership proof must be safe to share",
			UnsupportedOperationException.class, () -> ((Set<?>)parentOwned).clear());

		NativePlacementContinuity left = parent.nextRevision(List.copyOf(full.candidates));
		NativePlacementContinuity right = parent.nextRevision(List.copyOf(full.candidates));
		Object leftMemo = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(left)).get(fact);
		Object rightMemo = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(right)).get(fact);
		Assert.assertSame("exact immutable facts share the handle-free descriptor", parentMemo, leftMemo);
		Assert.assertSame(parentMemo, rightMemo);
		Assert.assertSame("exact owned-clause identity proof is immutable", parentOwned,
			((Map<?,?>)accessibleField(NativePlacementContinuity.class,
				"ownedCandidateClausesByFact").get(left)).get(fact));
		Assert.assertEquals(1, skeletonCounter(left, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(baseline, dependencySkeletons(left, fact, clause, root.hop, seed.anchor));
		Assert.assertEquals("the carried exact clause proof avoids the owner-fact scan", 0,
			skeletonCounter(left, "dependencySkeletonOwnerFactScans"));

		DurableAnchorKey leftWitness = anchor(FType.FULL, "worker2:8002", 0, 50);
		DurableAnchorKey rightWitness = anchor(FType.FULL, "worker3:8003", 0, 50);
		List<?> leftAdded = dependencySkeletons(left, fact, clause, root.hop, leftWitness);
		Object leftAfter = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(left)).get(fact);
		Assert.assertNotSame("the first write detaches the left fork", parentMemo, leftAfter);
		Assert.assertSame("the sibling remains on the shared read-only descriptor", parentMemo,
			((Map<?,?>)accessibleField(NativePlacementContinuity.class,
				"dependencySkeletonMemo").get(right)).get(fact));
		List<?> rightAdded = dependencySkeletons(right, fact, clause, root.hop, rightWitness);
		Object rightAfter = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(right)).get(fact);
		Assert.assertNotSame(parentMemo, rightAfter);
		Assert.assertNotSame(leftAfter, rightAfter);

		NativePlacementContinuity coldLeft = full.resolver(new SearchSpaceMetrics(), 0, 0);
		NativePlacementContinuity coldRight = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Assert.assertEquals(dependencySkeletons(
			coldLeft, fact, clause, root.hop, leftWitness), leftAdded);
		Assert.assertEquals(dependencySkeletons(
			coldRight, fact, clause, root.hop, rightWitness), rightAdded);
		Assert.assertEquals("the parent shared descriptor does not receive fork writes", 1,
			((Number)accessibleField(parentMemo.getClass(), "templateCount").get(parentMemo)).longValue());
		Assert.assertEquals(2,
			((Number)accessibleField(leftAfter.getClass(), "templateCount").get(leftAfter)).longValue());
		Assert.assertEquals(2,
			((Number)accessibleField(rightAfter.getClass(), "templateCount").get(rightAfter)).longValue());

		CandidateRealizationSupportClause rebuiltClause =
			new CandidateRealizationSupportClause(List.of(), List.of());
		CandidateEmissionFact originalEmission = fact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization rebuiltRealization = new CandidateEmissionRealization(
			originalEmission.realizations().get(0).key(), List.of(rebuiltClause));
		CandidateEmissionFact rebuiltEmission = new CandidateEmissionFact(
			originalEmission.emissionState(), originalEmission.executionFType(),
			originalEmission.derivedFoutAction(), List.of(rebuiltRealization));
		CandidateRuleFact rebuiltFact = new CandidateRuleFact(fact.key(), fact.status(), fact.capability(),
			fact.shapeProof(), fact.profile(), List.of(rebuiltEmission), fact.failureCode());
		List<CandidateRuleFact> rebuiltFacts = full.candidates.stream()
			.map(candidate -> candidate == fact ? rebuiltFact : candidate).toList();
		NativePlacementContinuity rebuilt = left.nextRevision(rebuiltFacts);
		List<?> rebuiltResult = dependencySkeletons(
			rebuilt, rebuiltFact, rebuiltClause, root.hop, leftWitness);
		Assert.assertEquals("the detached fork must rekey its current structural donor", leftAdded,
			rebuiltResult);
		Assert.assertEquals(0, skeletonCounter(rebuilt, "dependencySkeletonBuilds"));
		Assert.assertEquals(1, skeletonCounter(rebuilt, "dependencySkeletonReuses"));
		Assert.assertEquals(2, skeletonCounter(rebuilt, "dependencySkeletonTemplatesCarried"));

		DurableAnchorKey parentWitness = anchor(FType.FULL, "worker4:8004", 0, 50);
		dependencySkeletons(parent, fact, clause, root.hop, parentWitness);
		Object parentAfter = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(parent)).get(fact);
		Assert.assertNotSame("the donor also detaches before a later write", parentMemo, parentAfter);
		Assert.assertEquals(2,
			((Number)accessibleField(parentAfter.getClass(), "templateCount").get(parentAfter)).longValue());
		Assert.assertEquals("a donor write cannot leak into an already detached child", 2,
			((Number)accessibleField(leftAfter.getClass(), "templateCount").get(leftAfter)).longValue());

		NativePlacementContinuity grandchild = left.nextRevision(List.copyOf(full.candidates));
		Object grandchildShared = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(grandchild)).get(fact);
		Assert.assertSame(leftAfter, grandchildShared);
		Assert.assertEquals(2, skeletonCounter(grandchild, "dependencySkeletonTemplatesCarried"));
		DurableAnchorKey grandchildWitness = anchor(FType.FULL, "worker5:8005", 0, 50);
		dependencySkeletons(grandchild, fact, clause, root.hop, grandchildWitness);
		Object grandchildAfter = ((Map<?,?>)accessibleField(NativePlacementContinuity.class,
			"dependencySkeletonMemo").get(grandchild)).get(fact);
		Assert.assertNotSame(leftAfter, grandchildAfter);
		Assert.assertEquals(3,
			((Number)accessibleField(grandchildAfter.getClass(), "templateCount")
				.get(grandchildAfter)).longValue());
		Assert.assertEquals("later revisions cannot mutate the earlier fork", 2,
			((Number)accessibleField(leftAfter.getClass(), "templateCount").get(leftAfter)).longValue());
	}

	@Test
	public void dependencySkeletonRejectsProofOwnerNativePoolAndOwnerHopChanges()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.federatedSource("seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		Ref other = full.unary("other", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference seedReference = full.reference(seed,
			List.of(CandidateInputState.absentLocal(), CandidateInputState.absentLocal()));
		PlacementProofKey proof = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, seed.key, "proof");
		ValueVersionKey version = new ValueVersionKey(seed.key.programFingerprint(), "value",
			seed.key.controlRegion(),
			0, VersionKind.ORDINARY, List.of());
		PlacementState relocated = new PlacementState(
			ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
		RelocationActionKey action = new RelocationActionKey(
			version, relocated, seed.anchor, "scope", List.of(root.key));
		CandidateRealizationSupportClause initialClause = new CandidateRealizationSupportClause(
			List.of(proof), List.of(CandidateRealizationInputBinding.relocation(
				0, seedReference, action)),
			seed.anchor, true);
		full.withClauses(root, inputs, List.of(initialClause));
		CandidateRuleFact original = full.fact(root, inputs);
		CandidateRealizationSupportClause owned = original.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		NativePlacementContinuity first = full.resolver(new SearchSpaceMetrics(), 0, 0);
		List<?> baseline = dependencySkeletons(first, original, owned, root.hop, seed.anchor);

		CompiledHopKey foreignProofOwner = new CompiledHopKey(seed.key.programFingerprint(),
			seed.key.functionNamespace(), seed.key.callSitePath(), seed.key.recompileContext(),
			seed.key.controlRegion(), seed.key.emittedHopInstance(), seed.key.canonicalSourceOrigin());
		Assert.assertEquals(seed.key, foreignProofOwner);
		Assert.assertNotSame(seed.key, foreignProofOwner);
		CandidateRealizationSupportClause changed = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, foreignProofOwner, "proof")),
			owned.inputBindings(), owned.nativeWorkerPoolWitness(), owned.nativeWorkerPoolLayoutExact());
		CandidateRuleFact changedFact = replaceOnlyClause(original, changed);
		List<CandidateRuleFact> facts = new ArrayList<>(full.candidates);
		facts.set(facts.indexOf(original), changedFact);
		NativePlacementContinuity revised = first.nextRevision(facts);
		List<?> actual = dependencySkeletons(revised, changedFact, changed, root.hop, seed.anchor);
		NativePlacementContinuity cold = new NativePlacementContinuity(full.nodes, full.origins,
			facts, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(dependencySkeletons(cold, changedFact, changed, root.hop, seed.anchor), actual);
		Assert.assertEquals(0, skeletonCounter(revised, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(1, skeletonCounter(revised, "dependencySkeletonBuilds"));

		CompiledHopKey foreignConsumer = new CompiledHopKey(root.key.programFingerprint(),
			root.key.functionNamespace(), root.key.callSitePath(), root.key.recompileContext(),
			root.key.controlRegion(), root.key.emittedHopInstance(), root.key.canonicalSourceOrigin());
		RelocationActionKey foreignAction = new RelocationActionKey(
			version, relocated, seed.anchor, "scope", List.of(foreignConsumer));
		CandidateRealizationSupportClause actionClause = new CandidateRealizationSupportClause(
			owned.proofDependencies(), List.of(CandidateRealizationInputBinding.relocation(
				0, seedReference, foreignAction)), owned.nativeWorkerPoolWitness(),
			owned.nativeWorkerPoolLayoutExact());
		CandidateRuleFact actionFact = replaceOnlyClause(original, actionClause);
		List<CandidateRuleFact> actionFacts = new ArrayList<>(full.candidates);
		actionFacts.set(actionFacts.indexOf(original), actionFact);
		NativePlacementContinuity actionRevision = first.nextRevision(actionFacts);
		List<?> actionActual = dependencySkeletons(
			actionRevision, actionFact, actionClause, root.hop, seed.anchor);
		NativePlacementContinuity actionCold = new NativePlacementContinuity(full.nodes, full.origins,
			actionFacts, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(dependencySkeletons(
			actionCold, actionFact, actionClause, root.hop, seed.anchor), actionActual);
		Assert.assertEquals(0, skeletonCounter(actionRevision, "dependencySkeletonTemplatesCarried"));

		Assert.assertEquals("a different owner Hop must preserve the cold result", baseline,
			dependencySkeletons(first, original, owned, other.hop, seed.anchor));
		Assert.assertEquals("owner Hop identity mismatch must not reuse", 2,
			skeletonCounter(first, "dependencySkeletonBuilds"));

		DurableAnchorKey otherPool = anchor(FType.FULL, "worker2:8002", 0, 50);
		CandidateRealizationSupportClause poolClause = new CandidateRealizationSupportClause(
			owned.proofDependencies(), owned.inputBindings(), otherPool, false);
		CandidateRuleFact poolFact = replaceOnlyClause(original, poolClause);
		List<CandidateRuleFact> poolFacts = new ArrayList<>(full.candidates);
		poolFacts.set(poolFacts.indexOf(original), poolFact);
		NativePlacementContinuity poolRevision = first.nextRevision(poolFacts);
		List<?> poolActual = dependencySkeletons(
			poolRevision, poolFact, poolClause, root.hop, seed.anchor);
		NativePlacementContinuity poolCold = new NativePlacementContinuity(full.nodes, full.origins,
			poolFacts, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(dependencySkeletons(
			poolCold, poolFact, poolClause, root.hop, seed.anchor), poolActual);
		Assert.assertEquals(0, skeletonCounter(poolRevision, "dependencySkeletonTemplatesCarried"));
	}

	private static CandidateRuleFact replaceOnlyClause(CandidateRuleFact source,
		CandidateRealizationSupportClause clause) {
		CandidateEmissionFact emission = source.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		CandidateEmissionRealization replacementRealization = new CandidateEmissionRealization(
			realization.key(), List.of(clause));
		CandidateEmissionFact replacementEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(replacementRealization));
		return new CandidateRuleFact(source.key(), source.status(), source.capability(),
			source.shapeProof(), source.profile(), List.of(replacementEmission), source.failureCode());
	}

	@Test
	public void dependencySkeletonAmbiguousEqualDonorsFailCold() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.federatedSource("seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.withClauses(root, inputs,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact firstFact = full.fact(root, inputs);
		CandidateRealizationSupportClause firstClause = firstFact.allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses().get(0);
		CandidateRealizationSupportClause secondClause = new CandidateRealizationSupportClause(
			firstClause.proofDependencies(), firstClause.inputBindings(),
			firstClause.nativeWorkerPoolWitness(), firstClause.nativeWorkerPoolLayoutExact());
		CandidateRuleFact secondFact = replaceOnlyClause(firstFact, secondClause);
		List<CandidateRuleFact> donors = new ArrayList<>(full.candidates);
		donors.add(secondFact);
		NativePlacementContinuity resolver = new NativePlacementContinuity(full.nodes, full.origins,
			donors, full.edges, full.reaching, Set.of(), full.privacy);
		dependencySkeletons(resolver, firstFact, firstClause, root.hop, seed.anchor);
		dependencySkeletons(resolver, secondFact, secondClause, root.hop, seed.anchor);

		CandidateRealizationSupportClause currentClause = new CandidateRealizationSupportClause(
			firstClause.proofDependencies(), firstClause.inputBindings(),
			firstClause.nativeWorkerPoolWitness(), firstClause.nativeWorkerPoolLayoutExact());
		CandidateRuleFact currentFact = replaceOnlyClause(firstFact, currentClause);
		List<CandidateRuleFact> currentFacts = full.candidates.stream()
			.map(fact -> fact == firstFact ? currentFact : fact).toList();
		NativePlacementContinuity revised = resolver.nextRevision(currentFacts);
		List<?> actual = dependencySkeletons(
			revised, currentFact, currentClause, root.hop, seed.anchor);
		NativePlacementContinuity cold = new NativePlacementContinuity(full.nodes, full.origins,
			currentFacts, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(dependencySkeletons(
			cold, currentFact, currentClause, root.hop, seed.anchor), actual);
		Assert.assertEquals(0, skeletonCounter(revised, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(1, skeletonCounter(revised, "dependencySkeletonBuilds"));
	}

	@Test
	public void dependencySkeletonDonorRejectsForeignOwnerAndDoesNotSurviveWithdrawal()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.federatedSource("seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> seedInputs = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
		List<CandidateInputState> rootInputs =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference seedReference = full.reference(seed, seedInputs);
		full.withClauses(root, rootInputs, List.of(new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, seedReference)))));
		CandidateRuleFact original = full.fact(root, rootInputs);
		CandidateEmissionFact originalEmission = original.allowedEmissionFacts().get(0);
		CandidateEmissionRealization originalRealization = originalEmission.realizations().get(0);
		CandidateRealizationSupportClause originalClause = originalRealization.supportClauses().get(0);
		NativePlacementContinuity first = full.resolver(new SearchSpaceMetrics(), 0, 0);
		dependencySkeletons(first, original, originalClause, root.hop, seed.anchor);

		CompiledHopKey foreignSeed = new CompiledHopKey(seed.key.programFingerprint(),
			seed.key.functionNamespace(), seed.key.callSitePath(), seed.key.recompileContext(),
			seed.key.controlRegion(), seed.key.emittedHopInstance(), seed.key.canonicalSourceOrigin());
		Assert.assertEquals(seed.key, foreignSeed);
		Assert.assertNotSame(seed.key, foreignSeed);
		CandidateRealizationReference foreignReference = new CandidateRealizationReference(
			new CandidateRuleKey(foreignSeed, seedInputs), seedReference.realization());
		CandidateRealizationSupportClause foreignClause = new CandidateRealizationSupportClause(
			originalClause.proofDependencies(),
			List.of(CandidateRealizationInputBinding.direct(0, foreignReference)),
			originalClause.nativeWorkerPoolWitness(), originalClause.nativeWorkerPoolLayoutExact());
		CandidateEmissionRealization foreignRealization = new CandidateEmissionRealization(
			originalRealization.key(), List.of(foreignClause));
		CandidateEmissionFact foreignEmission = new CandidateEmissionFact(
			originalEmission.emissionState(), originalEmission.executionFType(),
			originalEmission.derivedFoutAction(), List.of(foreignRealization));
		CandidateRuleFact foreignFact = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(), List.of(foreignEmission),
			original.failureCode());
		List<CandidateRuleFact> foreignFacts = new ArrayList<>(full.candidates);
		foreignFacts.set(foreignFacts.indexOf(original), foreignFact);
		NativePlacementContinuity foreignRevision = first.nextRevision(foreignFacts);
		List<?> foreignActual = dependencySkeletons(
			foreignRevision, foreignFact, foreignClause, root.hop, seed.anchor);
		NativePlacementContinuity foreignCold = new NativePlacementContinuity(full.nodes, full.origins,
			foreignFacts, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(dependencySkeletons(
			foreignCold, foreignFact, foreignClause, root.hop, seed.anchor), foreignActual);
		Assert.assertEquals("equal-but-foreign source authority must miss", 0,
			skeletonCounter(foreignRevision, "dependencySkeletonReuses"));
		Assert.assertEquals(1, skeletonCounter(foreignRevision, "dependencySkeletonBuilds"));
		Assert.assertEquals(0, skeletonCounter(foreignRevision,
			"dependencySkeletonTemplatesCarried"));

		List<CandidateRuleFact> withdrawnFacts = full.candidates.stream()
			.filter(fact -> fact != original).toList();
		NativePlacementContinuity withdrawn = first.nextRevision(withdrawnFacts);
		NativePlacementContinuity restored = withdrawn.nextRevision(List.copyOf(full.candidates));
		dependencySkeletons(restored, original, originalClause, root.hop, seed.anchor);
		Assert.assertEquals("withdrawal must sever the template lineage", 0,
			skeletonCounter(restored, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(1, skeletonCounter(restored, "dependencySkeletonBuilds"));

		NativePlacementContinuity structural = first.structuralRevision(full.nodes, full.origins,
			List.copyOf(full.candidates), full.edges, full.reaching, Set.of(), full.privacy);
		dependencySkeletons(structural, original, originalClause, root.hop, seed.anchor);
		Assert.assertEquals("a structural revision must start cold", 0,
			skeletonCounter(structural, "dependencySkeletonTemplatesCarried"));
		Assert.assertEquals(1, skeletonCounter(structural, "dependencySkeletonBuilds"));
	}

	@Test
	public void replayProofReceiptCarriesOnlyAcrossExactUnchangedCandidateRevision()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("receipt-seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("receipt-root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact rootFact = full.fact(root, inputs);
		CandidateRealizationReference reference = full.reference(root, inputs);
		NativePlacementContinuity first = full.resolver(new SearchSpaceMetrics(), 8, 128);
		Object result = replayProof(first, reference, seed.anchor);
		Object receipt = replayReceipt(result);
		Assert.assertFalse(replayProofs(result).isEmpty());
		Assert.assertNotNull(receipt);

		NativePlacementContinuity unknown = first.nextRevision(List.copyOf(full.candidates));
		Assert.assertTrue(matchesReplayReceipt(unknown, receipt, reference, seed.anchor));
		Assert.assertEquals(1, replayReceiptCounter(unknown, "replayReceiptHits"));
		Assert.assertEquals(0, replayReceiptCounter(unknown, "replayReceiptMisses"));
		Assert.assertEquals("shared fact objects retain the allocation-free identity fast path", 0,
			unknown.revisionComparisonSnapshot().continuityProjectionsCompared());
		NativePlacementContinuity hinted = first.nextRevisionWithCompleteCandidateDelta(
			List.copyOf(full.candidates), Set.of());
		Assert.assertTrue(matchesReplayReceipt(hinted, receipt, reference, seed.anchor));
		Assert.assertTrue(hinted.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);

		List<CandidateRuleFact> withdrawnFacts = full.candidates.stream()
			.filter(fact -> fact != rootFact).toList();
		NativePlacementContinuity withdrawn = first.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, identitySet(root.key));
		Assert.assertFalse(matchesReplayReceipt(withdrawn, receipt, reference, seed.anchor));
		NativePlacementContinuity restored = withdrawn.nextRevision(List.copyOf(full.candidates));
		Assert.assertFalse("restoration cannot revive a historical revision token",
			matchesReplayReceipt(restored, receipt, reference, seed.anchor));

		CompiledHopKey foreignOwner = new CompiledHopKey(root.key.programFingerprint(),
			root.key.functionNamespace(), root.key.callSitePath(), root.key.recompileContext(),
			root.key.controlRegion(), root.key.emittedHopInstance(), root.key.canonicalSourceOrigin());
		CandidateRealizationReference foreignReference = new CandidateRealizationReference(
			new CandidateRuleKey(foreignOwner, reference.rule().orderedInputs()), reference.realization());
		Assert.assertEquals(reference, foreignReference);
		Assert.assertNotSame(reference.rule().parentOccurrence(),
			foreignReference.rule().parentOccurrence());
		Assert.assertFalse(matchesReplayReceipt(unknown, receipt, foreignReference, seed.anchor));
		Assert.assertFalse(matchesReplayReceipt(unknown, receipt, reference,
			anchor(FType.FULL, "worker2:8002", 0, 50)));

		Assert.assertFalse(matchesReplayReceipt(first.freshQueryState(),
			receipt, reference, seed.anchor));
		Assert.assertFalse(matchesReplayReceipt(first.nextOwnerRevision(root.key, List.of(rootFact)),
			receipt, reference, seed.anchor));
		Assert.assertFalse(matchesReplayReceipt(first.structuralRevision(full.nodes, full.origins,
			List.copyOf(full.candidates), full.edges, full.reaching, Set.of(), full.privacy),
			receipt, reference, seed.anchor));
	}

	@Test
	public void completeEmptyReplayReceiptInvalidatesWhenNativeSupportAppears()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey seed = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref late = full.logicalRead("receipt-late");
		List<CandidateInputState> inputs =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact staging = full.fact(late, inputs);
		CandidateEmissionFact stagingEmission = staging.allowedEmissionFacts().get(0);
		PlacementProofKey nativeAuthority = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, late.key, "receipt-native");
		CandidateRealizationSupportClause groundedClause = new CandidateRealizationSupportClause(
			List.of(nativeAuthority), List.of(), seed);
		CandidateEmissionRealization grounded = CandidateEmissionRealization.nativeLineage(
			stagingEmission.emissionState(), "receipt-grounded", seed,
			List.of(nativeAuthority), List.of());
		CandidateEmissionFact groundedEmission = new CandidateEmissionFact(
			stagingEmission.emissionState(), stagingEmission.executionFType(),
			stagingEmission.derivedFoutAction(), List.of(new CandidateEmissionRealization(
				grounded.key(), List.of(groundedClause))));
		CandidateRuleFact available = new CandidateRuleFact(staging.key(), staging.status(),
			staging.capability(), staging.shapeProof(), staging.profile(), List.of(groundedEmission),
			staging.failureCode());
		CandidateRealizationReference reference = CandidateRealizationReference.of(
			available.key(), groundedEmission.realizations().get(0));
		full.candidates.remove(staging);
		NativePlacementContinuity absent = full.resolver(new SearchSpaceMetrics(), 8, 128);
		Object empty = replayProof(absent, reference, seed);
		Object emptyReceipt = replayReceipt(empty);
		Assert.assertTrue(replayProofs(empty).isEmpty());
		Assert.assertNotNull("a root-bearing empty proof has a complete negative footprint",
			emptyReceipt);
		Assert.assertTrue(matchesReplayReceipt(absent.nextRevision(List.of()),
			emptyReceipt, reference, seed));

		NativePlacementContinuity added = absent.nextRevisionWithCompleteCandidateDelta(
			List.of(available), identitySet(late.key));
		Assert.assertFalse("new support must invalidate the negative receipt",
			matchesReplayReceipt(added, emptyReceipt, reference, seed));
		Object positive = replayProof(added, reference, seed);
		Assert.assertFalse(replayProofs(positive).isEmpty());
		Object positiveReceipt = replayReceipt(positive);
		Assert.assertNotNull(positiveReceipt);
		NativePlacementContinuity removed = added.nextRevisionWithCompleteCandidateDelta(
			List.of(), identitySet(late.key));
		Assert.assertFalse("withdrawal must invalidate the positive receipt",
			matchesReplayReceipt(removed, positiveReceipt, reference, seed));
		Assert.assertTrue(replayProofs(replayProof(removed, reference, seed)).isEmpty());
	}

	@Test
	public void negativeReplayReceiptTracksLateBoundSourceWithAllNormalMemosDisabled()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey seed = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref boundSource = full.logicalRead("receipt-bound-source");
		Ref formal = full.logicalRead("receipt-formal");
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact sourceFact = full.fact(boundSource, inputs);
		CandidateRealizationReference sourceReference = full.reference(boundSource, inputs);
		CandidateRuleFact originalFormalFact = full.fact(formal, inputs);
		CandidateEmissionFact formalEmission = originalFormalFact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization formalValueMap = CandidateEmissionRealization.valueMap(
			formalEmission.emissionState(), "receipt-formal-binding",
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, sourceReference)))));
		CandidateEmissionFact boundFormalEmission = new CandidateEmissionFact(
			formalEmission.emissionState(), formalEmission.executionFType(),
			formalEmission.derivedFoutAction(), List.of(formalValueMap));
		CandidateRuleFact formalFact = new CandidateRuleFact(originalFormalFact.key(),
			originalFormalFact.status(), originalFormalFact.capability(), originalFormalFact.shapeProof(),
			originalFormalFact.profile(), List.of(boundFormalEmission), originalFormalFact.failureCode());
		full.candidates.set(full.candidates.indexOf(originalFormalFact), formalFact);
		CandidateRealizationReference formalReference = CandidateRealizationReference.of(
			formalFact.key(), formalValueMap);
		full.candidates.remove(sourceFact);

		NativePlacementContinuity absent = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Object negative = replayProof(absent, formalReference, seed);
		Object receipt = replayReceipt(negative);
		Assert.assertTrue(replayProofs(negative).isEmpty());
		Assert.assertNotNull("the formal root supplies the complete negative query footprint", receipt);
		for(String cache : List.of("candidateTopologies", "completedSupportMemo",
			"acyclicRootSupportMemo", "completedProofMemo", "acyclicComponentMemo"))
			((Map<?,?>)accessibleField(NativePlacementContinuity.class, cache).get(absent)).clear();

		PlacementProofKey nativeAuthority = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, boundSource.key, "receipt-late-bound-native");
		CandidateEmissionFact sourceEmission = sourceFact.allowedEmissionFacts().get(0);
		CandidateRealizationSupportClause groundedClause = new CandidateRealizationSupportClause(
			List.of(nativeAuthority), List.of(), seed);
		CandidateEmissionRealization grounded = new CandidateEmissionRealization(
			sourceEmission.realizations().get(0).key(), List.of(groundedClause));
		CandidateEmissionFact groundedEmission = new CandidateEmissionFact(
			sourceEmission.emissionState(), sourceEmission.executionFType(),
			sourceEmission.derivedFoutAction(), List.of(grounded));
		CandidateRuleFact groundedSource = new CandidateRuleFact(sourceFact.key(), sourceFact.status(),
			sourceFact.capability(), sourceFact.shapeProof(), sourceFact.profile(),
			List.of(groundedEmission), sourceFact.failureCode());
		NativePlacementContinuity added = absent.nextRevisionWithCompleteCandidateDelta(
			List.of(groundedSource, formalFact), identitySet(boundSource.key));

		Assert.assertFalse("the reverse bound-owner cone invalidates the unchanged formal root token",
			matchesReplayReceipt(added, receipt, formalReference, seed));
		Assert.assertFalse("the cold fallback observes the newly grounded bound source",
			replayProofs(replayProof(added, formalReference, seed)).isEmpty());
		Assert.assertTrue("receipt-only state must activate exact revision comparison",
			added.revisionComparisonSnapshot().ownersCompared() > 0
				|| added.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);
	}

	@Test
	public void replayReceiptIncludesEveryOwnerInAGroundedLoopScc() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref entry = full.source("receipt-loop-entry",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref first = full.logicalRead("receipt-loop-first");
		Ref second = full.logicalRead("receipt-loop-second");
		full.reaching.put(first.key, List.of(entry.key, second.key));
		full.reaching.put(second.key, List.of(first.key));
		Ref root = full.unary("receipt-loop-root", OpOp1.ABS, first, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = full.reference(root, inputs);
		CandidateRuleFact secondFact = full.fact(second, inputs);
		NativePlacementContinuity initial = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Object proof = replayProof(initial, reference, entry.anchor);
		Object receipt = replayReceipt(proof);

		Assert.assertFalse("the loop SCC is grounded by its entry", replayProofs(proof).isEmpty());
		Assert.assertNotNull(receipt);
		List<CandidateRuleFact> withdrawnFacts = full.candidates.stream()
			.filter(fact -> fact != secondFact).toList();
		NativePlacementContinuity withdrawn = initial.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, identitySet(second.key));
		Assert.assertFalse("withdrawing any owner in the SCC invalidates the complete proof receipt",
			matchesReplayReceipt(withdrawn, receipt, reference, entry.anchor));
		Assert.assertTrue("the cold oracle also rejects the broken loop SCC",
			replayProofs(replayProof(withdrawn, reference, entry.anchor)).isEmpty());
	}

	@Test
	public void replayReceiptRejectsStructurallyEqualForeignBoundOwnerInNestedValueMaps()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey seed = anchor(FType.FULL, "worker1:8001", 0, 50);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		Ref source = full.logicalRead("receipt-identity-source");
		CandidateRuleFact originalSource = full.fact(source, inputs);
		CandidateEmissionFact sourceEmission = originalSource.allowedEmissionFacts().get(0);
		PlacementProofKey nativeAuthority = new PlacementProofKey(
			PlacementProofKind.NATIVE_CONTINUITY, source.key, "receipt-identity-native");
		CandidateRealizationSupportClause sourceClause = new CandidateRealizationSupportClause(
			List.of(nativeAuthority), List.of(), seed);
		CandidateEmissionRealization sourceRealization = new CandidateEmissionRealization(
			sourceEmission.realizations().get(0).key(), List.of(sourceClause));
		CandidateEmissionFact groundedSourceEmission = new CandidateEmissionFact(
			sourceEmission.emissionState(), sourceEmission.executionFType(),
			sourceEmission.derivedFoutAction(), List.of(sourceRealization));
		CandidateRuleFact sourceFact = new CandidateRuleFact(originalSource.key(),
			originalSource.status(), originalSource.capability(), originalSource.shapeProof(),
			originalSource.profile(), List.of(groundedSourceEmission), originalSource.failureCode());
		full.candidates.set(full.candidates.indexOf(originalSource), sourceFact);
		CandidateRealizationReference sourceReference = CandidateRealizationReference.of(
			sourceFact.key(), sourceRealization);

		Ref middle = full.logicalRead("receipt-identity-middle");
		CandidateRuleFact originalMiddle = full.fact(middle, inputs);
		CandidateEmissionFact middleEmission = originalMiddle.allowedEmissionFacts().get(0);
		CandidateEmissionRealization middleValueMap = CandidateEmissionRealization.valueMap(
			middleEmission.emissionState(), "receipt-identity-middle-map",
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, sourceReference)))));
		CandidateEmissionFact mappedMiddleEmission = new CandidateEmissionFact(
			middleEmission.emissionState(), middleEmission.executionFType(),
			middleEmission.derivedFoutAction(), List.of(middleValueMap));
		CandidateRuleFact middleFact = new CandidateRuleFact(originalMiddle.key(),
			originalMiddle.status(), originalMiddle.capability(), originalMiddle.shapeProof(),
			originalMiddle.profile(), List.of(mappedMiddleEmission), originalMiddle.failureCode());
		full.candidates.set(full.candidates.indexOf(originalMiddle), middleFact);
		CandidateRealizationReference middleReference = CandidateRealizationReference.of(
			middleFact.key(), middleValueMap);

		Ref root = full.logicalRead("receipt-identity-root");
		CandidateRuleFact originalRoot = full.fact(root, inputs);
		CandidateEmissionFact rootEmission = originalRoot.allowedEmissionFacts().get(0);
		CandidateEmissionRealization rootValueMap = CandidateEmissionRealization.valueMap(
			rootEmission.emissionState(), "receipt-identity-root-map",
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, middleReference)))));
		CandidateEmissionFact mappedRootEmission = new CandidateEmissionFact(
			rootEmission.emissionState(), rootEmission.executionFType(),
			rootEmission.derivedFoutAction(), List.of(rootValueMap));
		CandidateRuleFact rootFact = new CandidateRuleFact(originalRoot.key(), originalRoot.status(),
			originalRoot.capability(), originalRoot.shapeProof(), originalRoot.profile(),
			List.of(mappedRootEmission), originalRoot.failureCode());
		full.candidates.set(full.candidates.indexOf(originalRoot), rootFact);
		CandidateRealizationReference rootReference = CandidateRealizationReference.of(
			rootFact.key(), rootValueMap);

		NativePlacementContinuity initial = full.resolver(new SearchSpaceMetrics(), 0, 0);
		Object initialProof = replayProof(initial, rootReference, seed);
		Object receipt = replayReceipt(initialProof);
		Assert.assertFalse(replayProofs(initialProof).isEmpty());
		Assert.assertNotNull(receipt);
		for(String cache : List.of("candidateTopologies", "completedSupportMemo",
			"acyclicRootSupportMemo", "completedProofMemo", "acyclicComponentMemo"))
			((Map<?,?>)accessibleField(NativePlacementContinuity.class, cache).get(initial)).clear();

		CompiledHopKey foreignSource = new CompiledHopKey(source.key.programFingerprint(),
			source.key.functionNamespace(), source.key.callSitePath(), source.key.recompileContext(),
			source.key.controlRegion(), source.key.emittedHopInstance(), source.key.canonicalSourceOrigin());
		Assert.assertEquals(source.key, foreignSource);
		Assert.assertNotSame(source.key, foreignSource);
		CandidateRealizationReference foreignReference = CandidateRealizationReference.of(
			new CandidateRuleKey(foreignSource, sourceReference.rule().orderedInputs()), sourceRealization);
		CandidateRealizationSupportClause foreignClause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, foreignReference)));
		CandidateEmissionRealization foreignMiddleValueMap = CandidateEmissionRealization.valueMap(
			middleEmission.emissionState(), "receipt-identity-middle-map", List.of(foreignClause));
		CandidateEmissionFact foreignMiddleEmission = new CandidateEmissionFact(
			middleEmission.emissionState(), middleEmission.executionFType(),
			middleEmission.derivedFoutAction(), List.of(foreignMiddleValueMap));
		CandidateRuleFact changedMiddle = new CandidateRuleFact(middleFact.key(), middleFact.status(),
			middleFact.capability(), middleFact.shapeProof(), middleFact.profile(),
			List.of(foreignMiddleEmission), middleFact.failureCode());
		Assert.assertEquals("record equality deliberately omits nested owner identity",
			middleFact, changedMiddle);
		List<CandidateRuleFact> revisedFacts = full.candidates.stream()
			.map(fact -> fact == middleFact ? changedMiddle : fact).toList();
		NativePlacementContinuity revised = initial.nextRevisionWithCompleteCandidateDelta(
			revisedFacts, identitySet(middle.key));
		NativePlacementContinuity cold = new NativePlacementContinuity(full.nodes, full.origins,
			revisedFacts, full.edges, full.reaching, Set.of(), full.privacy,
			new SearchSpaceMetrics(), 0, 0);

		Assert.assertTrue("fresh identity lookup rejects the foreign source owner",
			replayProofs(replayProof(cold, rootReference, seed)).isEmpty());
		Assert.assertFalse("revision-carried receipt must match the fresh cold oracle",
			matchesReplayReceipt(revised, receipt, rootReference, seed));
	}

	@Test
	public void completeCandidateDeltaRejectsNullAndForeignOwnerIdentities() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		NativePlacementContinuity first = full.resolver();
		List<CandidateRuleFact> facts = List.copyOf(full.candidates);

		Assert.assertThrows(NullPointerException.class,
			() -> first.nextRevisionWithCompleteCandidateDelta(facts, null));
		Set<CompiledHopKey> nullOwner = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		nullOwner.add(null);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> first.nextRevisionWithCompleteCandidateDelta(facts, nullOwner));
		CompiledHopKey foreign = new CompiledHopKey(source.key.programFingerprint(),
			source.key.functionNamespace(), source.key.callSitePath(), source.key.recompileContext(),
			source.key.controlRegion(), source.key.emittedHopInstance(), source.key.canonicalSourceOrigin());
		Assert.assertEquals(source.key, foreign);
		Assert.assertNotSame(source.key, foreign);
		Assert.assertThrows(IllegalArgumentException.class,
			() -> first.nextRevisionWithCompleteCandidateDelta(facts, Set.of(foreign)));
	}

	@Test
	public void exactRevisionSharesTopologyWithStableStructuralHandles() throws Exception {
		PlacementIdentity.beginAnalysisScope(null);
		try {
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref source = full.unary("source", OpOp1.LOG, seed, false);
			CandidateRealizationReference reference = full.reference(source,
				List.of(CandidateInputState.present(FType.FULL)));
			NativePlacementContinuity first = full.resolver();
			Assert.assertFalse(first.proveCandidateAlternatives(reference, seed.anchor).isEmpty());
			Method candidateHandle = NativePlacementContinuity.class.getDeclaredMethod(
				"candidateHandle", CandidateRealizationReference.class);
			candidateHandle.setAccessible(true);
			Assert.assertTrue("fixture must use an analysis-scope structural handle",
				(int)candidateHandle.invoke(first, reference) > 0);
			@SuppressWarnings("unchecked")
			Map<Object,Object> before = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(first);
			Assert.assertFalse(before.isEmpty());

			NativePlacementContinuity revised = first.nextRevision(List.copyOf(full.candidates));
			@SuppressWarnings("unchecked")
			Map<Object,Object> after = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(revised);

			Assert.assertEquals(before.size(), after.size());
			for(var entry : before.entrySet())
				Assert.assertSame("stable structural handles retain the immutable topology",
					entry.getValue(), after.get(entry.getKey()));
			Assert.assertEquals(first.proveCandidateAlternatives(reference, seed.anchor),
				revised.proveCandidateAlternatives(reference, seed.anchor));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void equalNewFactRevisionSharesTopologyWithStableDestinationHandles() throws Exception {
		PlacementIdentity.beginAnalysisScope(null);
		try {
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref source = full.unary("source", OpOp1.LOG, seed, false);
			CandidateRealizationReference reference = full.reference(source,
				List.of(CandidateInputState.present(FType.FULL)));
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity first = full.resolver(metrics, 128, 1024);
			NativePlacementContinuity.CandidateSupportResult initial =
				first.proveCandidateSupport(reference, seed.anchor);
			Assert.assertFalse(initial.proofs().isEmpty());
			@SuppressWarnings("unchecked")
			Map<Object,Object> before = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(first);
			Assert.assertFalse(before.isEmpty());

			List<CandidateRuleFact> equalNewFacts = full.candidates.stream().map(fact ->
				new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
					fact.profile(), fact.allowedEmissionFacts(), fact.failureCode())).toList();
			NativePlacementContinuity revised = first.nextRevision(equalNewFacts);
			Field snapshot = accessibleField(NativePlacementContinuity.class, "candidateFactsSnapshot");
			Assert.assertNotSame("equal new facts must create distinct snapshot authority",
				snapshot.get(first), snapshot.get(revised));
			@SuppressWarnings("unchecked")
			Map<Object,Object> after = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(revised);

			Assert.assertEquals(before.size(), after.size());
			Assert.assertTrue(metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.TOPOLOGY_REVISION_SHARED_ROWS) > 0);
			Assert.assertEquals(0, metrics.directWorkCount(
				SearchSpaceMetrics.DirectWork.TOPOLOGY_REVISION_REINDEXED_ROWS));
			for(var entry : before.entrySet())
				Assert.assertSame("semantic equality and destination-stable handles retain topology",
					entry.getValue(), after.get(entry.getKey()));
			NativePlacementContinuity.CandidateSupportResult actual =
				revised.proveCandidateSupport(reference, seed.anchor);
			NativePlacementContinuity.CandidateSupportResult fresh = new NativePlacementContinuity(
				full.nodes, full.origins, equalNewFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateSupport(reference, seed.anchor);
			Assert.assertEquals(fresh.proofs(), actual.proofs());
			assertIdentitySetEquals(fresh.dependencyOccurrences(), actual.dependencyOccurrences());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void factRevisionReindexesTopologyWhenStructuralArenaHandlesShift() throws Exception {
		assertRevisionTopologyHandleDrift(false);
		assertRevisionTopologyHandleDrift(true);
	}

	@Test
	public void stableHandleCheckVisitsOneWideReferenceBucketLinearly() throws Exception {
		PlacementIdentity.beginAnalysisScope(null);
		try {
			Fixture full = new Fixture(FType.FULL);
			DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
			Ref source = full.federatedSource("source", pool);
			List<CandidateInputState> sourceInputs = List.of(
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
			DurableAnchorKey[] alternatives = new DurableAnchorKey[64];
			for(int option = 0; option < alternatives.length; option++)
				alternatives[option] = new DurableAnchorKey(
					String.format("source-%03d", option), FType.FULL, pool.partitions());
			full.samePoolRealizations(source, sourceInputs, alternatives);
			Ref root = full.unary("root", OpOp1.LOG, source, false);
			List<CandidateInputState> rootInputs =
				List.of(CandidateInputState.present(FType.FULL));
			CandidateRuleFact sourceFact = full.fact(source, sourceInputs);
			List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
			for(CandidateEmissionRealization realization :
				sourceFact.allowedEmissionFacts().get(0).realizations()) {
				CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.direct(0,
					CandidateRealizationReference.of(sourceFact.key(), realization));
				NativePlacementContinuity.NativeContinuityProof proof =
					new NativePlacementContinuity.NativeContinuityProof(
						pool, pool, true, List.of(binding));
				clauses.add(new CandidateRealizationSupportClause(
					List.of(proof.continuityProofKey(root.key)),
					proof.immediateBindings(), pool, true));
			}
			CandidateRealizationReference reference =
				full.withClauses(root, rootInputs, clauses);
			NativePlacementContinuity first = full.resolver();
			Assert.assertFalse(first.proveCandidateAlternatives(reference, pool).isEmpty());

			@SuppressWarnings("unchecked")
			Map<Object,Object> topologies = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(first);
			Object topologyKey = topologies.keySet().stream().filter(key -> {
				try {
					return accessibleField(key.getClass(), "occurrence").get(key) == root.key;
				}
				catch(ReflectiveOperationException exception) {
					throw new AssertionError(exception);
				}
			}).findFirst().orElseThrow();
			Object topology = topologies.get(topologyKey);
			@SuppressWarnings("unchecked")
			List<Object> rows = (List<Object>)accessibleField(
				topology.getClass(), "rows").get(topology);
			@SuppressWarnings("unchecked")
			Map<Integer,List<Object>> indexed = (Map<Integer,List<Object>>)accessibleField(
				topology.getClass(), "rowsByHandle").get(topology);
			Assert.assertEquals(64, rows.size());
			Assert.assertEquals(1, indexed.size());
			CountingList<Object> countedBucket =
				new CountingList<>(indexed.values().iterator().next());
			Map<Integer,List<Object>> countedIndex = new java.util.HashMap<>(indexed);
			countedIndex.put(indexed.keySet().iterator().next(), countedBucket);
			Constructor<?> topologyConstructor = null;
			for(Constructor<?> candidate : topology.getClass().getDeclaredConstructors())
				if(candidate.getParameterCount() == 5) {
					topologyConstructor = candidate;
					break;
				}
			Assert.assertNotNull(topologyConstructor);
			topologyConstructor.setAccessible(true);
			Object countedTopology = topologyConstructor.newInstance(
				accessibleField(topology.getClass(), "eligible").getBoolean(topology),
				accessibleField(topology.getClass(), "nodeDirectGround").getBoolean(topology),
				rows, countedIndex,
				accessibleField(topology.getClass(), "metadataOwnerReads").get(topology));
			topologies.put(topologyKey, countedTopology);
			countedBucket.reset();

			List<CandidateRuleFact> equalNewFacts = full.candidates.stream().map(fact ->
				new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
					fact.profile(), fact.allowedEmissionFacts(), fact.failureCode())).toList();
			NativePlacementContinuity revised = first.nextRevision(equalNewFacts);
			Assert.assertEquals("each indexed row must be checked exactly once",
				rows.size(), countedBucket.gets());
			Assert.assertSame("the wide topology must actually be shared", countedTopology,
				((Map<?,?>)accessibleField(NativePlacementContinuity.class,
					"candidateTopologies").get(revised)).get(topologyKey));
			NativePlacementContinuity.CandidateSupportResult actual =
				revised.proveCandidateSupport(reference, pool);
			NativePlacementContinuity.CandidateSupportResult fresh = new NativePlacementContinuity(
				full.nodes, full.origins, equalNewFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateSupport(reference, pool);
			Assert.assertEquals(fresh.proofs(), actual.proofs());
			assertIdentitySetEquals(fresh.dependencyOccurrences(), actual.dependencyOccurrences());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	private static void assertRevisionTopologyHandleDrift(boolean preserveRootHandle) throws Exception {
		Fixture full;
		Ref seed;
		Ref source;
		CandidateRealizationReference reference;
		CandidateRealizationReference producerReference;
		NativePlacementContinuity first;
		Map<Object,Object> before;
		Object rootTopologyKey;
		int priorRootHandle;
		int priorProducerHandle;
		PlacementIdentity.beginAnalysisScope(null);
		try {
			full = new Fixture(FType.FULL);
			seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
			List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
			producerReference = full.withClauses(producer, inputs,
				List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
			source = full.unary("source", OpOp1.EXP, producer, false);
			reference = full.withClauses(source, inputs,
				List.of(new CandidateRealizationSupportClause(List.of(),
					List.of(CandidateRealizationInputBinding.direct(0, producerReference)))));
			first = full.resolver();
			Assert.assertFalse(first.proveCandidateAlternatives(reference, seed.anchor).isEmpty());
			Method candidateHandle = NativePlacementContinuity.class.getDeclaredMethod(
				"candidateHandle", CandidateRealizationReference.class);
			candidateHandle.setAccessible(true);
			priorRootHandle = (int)candidateHandle.invoke(first, reference);
			priorProducerHandle = (int)candidateHandle.invoke(first, producerReference);
			Assert.assertTrue(priorRootHandle > 0);
			@SuppressWarnings("unchecked")
			Map<Object,Object> cached = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(first);
			before = cached;
			Field occurrence = accessibleField(cached.keySet().iterator().next().getClass(), "occurrence");
			rootTopologyKey = cached.keySet().stream().filter(key -> {
				try {
					return occurrence.get(key) == source.key;
				}
				catch(IllegalAccessException exception) {
					throw new AssertionError(exception);
				}
			}).findFirst().orElseThrow();
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}

		PlacementIdentity.beginAnalysisScope(null);
		try {
			// Force either row-and-pin drift or pin-only drift with an identical row ID.
			int prefix = preserveRootHandle ? priorRootHandle - 1
				: Math.max(priorRootHandle, priorProducerHandle);
			for(int handle = 0; handle < prefix; handle++)
				Assert.assertNotNull(PlacementIdentity.structuralHandle(new Object()));
			if(preserveRootHandle) {
				Assert.assertEquals(priorRootHandle,
					PlacementIdentity.structuralHandle(reference).intValue());
				for(int handle = 0; handle <= priorProducerHandle; handle++)
					Assert.assertNotNull(PlacementIdentity.structuralHandle(new Object()));
			}
			Assert.assertNotEquals(priorProducerHandle,
				PlacementIdentity.structuralHandle(producerReference).intValue());
			List<CandidateRuleFact> equalNewFacts = full.candidates.stream().map(fact ->
				new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
					fact.profile(), fact.allowedEmissionFacts(), fact.failureCode())).toList();
			NativePlacementContinuity revised = first.nextRevision(equalNewFacts);
			@SuppressWarnings("unchecked")
			Map<Object,Object> after = (Map<Object,Object>)accessibleField(
				NativePlacementContinuity.class, "candidateTopologies").get(revised);
			Assert.assertNotSame("cross-arena handle drift requires destination reindexing",
				before.get(rootTopologyKey), after.get(rootTopologyKey));

			NativePlacementContinuity.CandidateSupportResult actual =
				revised.proveCandidateSupport(reference, seed.anchor);
			NativePlacementContinuity.CandidateSupportResult fresh = new NativePlacementContinuity(
				full.nodes, full.origins, equalNewFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveCandidateSupport(reference, seed.anchor);
			Assert.assertEquals(fresh.proofs(), actual.proofs());
			assertIdentitySetEquals(fresh.dependencyOccurrences(), actual.dependencyOccurrences());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	@Test
	public void revisionReindexesClausePinnedFallbackHandlesWithTopologyRows() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference producerReference = full.withClauses(producer, oneInput,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		Ref root = full.unary("root", OpOp1.EXP, producer, false);
		CandidateRealizationReference rootReference = full.withClauses(root, oneInput,
			List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, producerReference)))));
		Ref unrelated = full.unary("unrelated", OpOp1.ABS, seed, false);
		CandidateRealizationReference unrelatedReference = full.reference(unrelated, oneInput);
		Ref other = full.unary("other", OpOp1.SQRT, seed, false);
		CandidateRealizationReference otherReference = full.reference(other, oneInput);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		// A zero result budget still retains topology rows, while preventing a
		// completed proof from hiding revision-local fallback handle rebinding.
		NativePlacementContinuity first = full.resolver(metrics, 0, 0);
		Assert.assertFalse(first.proveCandidateAlternatives(rootReference, seed.anchor).isEmpty());
		Assert.assertFalse(first.proveCandidateAlternatives(unrelatedReference, seed.anchor).isEmpty());
		Assert.assertFalse(first.proveCandidateAlternatives(otherReference, seed.anchor).isEmpty());
		// Change only the access order of unchanged cached topologies. The destination
		// revision now allocates the unrelated row first, so resolver-local negative
		// handles cannot accidentally retain their old numeric meaning.
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(first, seed.anchor);
		Method candidateTopology = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateTopology", CompiledHopKey.class, witness.getClass());
		candidateTopology.setAccessible(true);
		candidateTopology.invoke(first, producer.key, witness);
		candidateTopology.invoke(first, root.key, witness);
		@SuppressWarnings("unchecked")
		Map<Object,Object> topologies = (Map<Object,Object>)accessibleField(
			NativePlacementContinuity.class, "candidateTopologies").get(first);
		Field topologyOccurrence = accessibleField(
			topologies.keySet().iterator().next().getClass(), "occurrence");
		for(CompiledHopKey owner : List.of(unrelated.key, other.key, producer.key, root.key)) {
			Object topologyKey = topologies.keySet().stream().filter(key -> {
				try {
					return topologyOccurrence.get(key) == owner;
				}
				catch(IllegalAccessException exception) {
					throw new AssertionError(exception);
				}
			}).findFirst().orElseThrow();
			topologies.get(topologyKey);
		}
		Method candidateHandle = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		candidateHandle.setAccessible(true);
		int oldProducerHandle = (int)candidateHandle.invoke(first, producerReference);

		NativePlacementContinuity revised = first.nextRevision(List.copyOf(full.candidates));
		int revisedProducerHandle = (int)candidateHandle.invoke(revised, producerReference);
		Assert.assertNotEquals("the fixture must change the resolver-local handle namespace",
			oldProducerHandle, revisedProducerHandle);
		@SuppressWarnings("unchecked")
		Map<Object,Object> revisedTopologies = (Map<Object,Object>)accessibleField(
			NativePlacementContinuity.class, "candidateTopologies").get(revised);
		Field revisedOccurrence = accessibleField(
			revisedTopologies.keySet().iterator().next().getClass(), "occurrence");
		Object rootTopology = revisedTopologies.entrySet().stream().filter(entry -> {
			try {
				return revisedOccurrence.get(entry.getKey()) == root.key;
			}
			catch(IllegalAccessException exception) {
				throw new AssertionError(exception);
			}
		}).map(Map.Entry::getValue).findFirst().orElseThrow();
		Object priorRootTopology = topologies.entrySet().stream().filter(entry -> {
			try {
				return topologyOccurrence.get(entry.getKey()) == root.key;
			}
			catch(IllegalAccessException exception) {
				throw new AssertionError(exception);
			}
		}).map(Map.Entry::getValue).findFirst().orElseThrow();
		Assert.assertNotSame("query-local fallback handles require immutable topology rebinding",
			priorRootTopology, rootTopology);
		@SuppressWarnings("unchecked")
		List<Object> rootRows = (List<Object>)accessibleField(
			rootTopology.getClass(), "rows").get(rootTopology);
		Object defaultAlternative = accessibleField(
			rootRows.get(0).getClass(), "defaultAlternative").get(rootRows.get(0));
		@SuppressWarnings("unchecked")
		List<Object> defaultDependencies = (List<Object>)accessibleField(
			defaultAlternative.getClass(), "dependencies").get(defaultAlternative);
		Field dependencyKey = accessibleField(defaultDependencies.get(0).getClass(), "key");
		Object producerDependency = defaultDependencies.stream().filter(dependency -> {
			try {
				return dependencyKey.get(dependency) == producer.key;
			}
			catch(IllegalAccessException exception) {
				throw new AssertionError(exception);
			}
		}).findFirst().orElseThrow();
		Assert.assertEquals("reindexed defaults must own destination-revision fallback handles",
			revisedProducerHandle,
			accessibleField(producerDependency.getClass(), "realizationHandle")
				.getInt(producerDependency));
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			revised.proveCandidateAlternatives(rootReference, seed.anchor);
		List<NativePlacementContinuity.NativeContinuityProof> fresh =
			full.resolver(new SearchSpaceMetrics(), 0, 0)
				.proveCandidateAlternatives(rootReference, seed.anchor);

		Assert.assertFalse("a retained exact pin must not become an empty fallback-handle state",
			actual.isEmpty());
		Assert.assertEquals("reindexed topology must match a fresh resolver", fresh, actual);
		Assert.assertTrue("the unchanged topology crossed the fact revision",
			metrics.snapshot().topologyRevisionEntriesReused() > 0);
	}

	@Test
	public void factRevisionSharesImmutableProgramStructure() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.unary("source", OpOp1.LOG, seed, false);
		NativePlacementContinuity first = full.resolver();
		NativePlacementContinuity next = first.nextRevision(List.copyOf(full.candidates));

		for(String fieldName : List.of("structuralContext", "nodesByKey", "originsByKey",
			"edgesByConsumer", "reachingDefinitions", "occurrenceComponents",
			"incompleteSources", "privacyByKey")) {
			Field field = accessibleField(NativePlacementContinuity.class, fieldName);
			Assert.assertSame(fieldName + " must be shared rather than rebuilt for a fact revision",
				field.get(first), field.get(next));
		}
	}

	@Test
	public void broadcastAliasIndexMatchesColdScanAndSharesOnlyImmutableStructure() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref alias = full.broadcastAlias("seed-alias", seed);
		Node aliasNode = full.nodes.get(alias.key);
		ValueVersionKey value = aliasNode.valueVersion();
		ValueVersionKey equalValue = new ValueVersionKey(value.programFingerprint(),
			value.lexicalVariable(), value.definingControlRegion(), value.definitionOrdinal(),
			value.versionKind(), value.predecessorVersions());
		Assert.assertNotSame(value, equalValue);
		Assert.assertEquals(value, equalValue);
		full.nodes.put(alias.key, new Node(aliasNode.key(), aliasNode.kind(), equalValue,
			aliasNode.emittedWork(), aliasNode.legalAlternatives(), aliasNode.exclusions(),
			aliasNode.anchors()));

		NativePlacementContinuity continuity = full.resolver();
		Set<ValueVersionKey> indexed = broadcastCapableValueVersions(continuity);
		Set<ValueVersionKey> expected = coldBroadcastCapableValueVersions(full.nodes);
		Assert.assertEquals("indexed value membership must match the independent scan",
			expected, indexed);
		Assert.assertTrue("structurally equal value versions must join the alias class",
			indexed.contains(value));

		ValueVersionKey different = new ValueVersionKey(value.programFingerprint(),
			value.lexicalVariable() + "-other", value.definingControlRegion(), value.definitionOrdinal(),
			value.versionKind(), value.predecessorVersions());
		Assert.assertFalse("a different value version must not gain BROADCAST capability",
			indexed.contains(different));

		NativePlacementContinuity factRevision = continuity.nextRevision(List.copyOf(full.candidates));
		NativePlacementContinuity fresh = continuity.freshQueryState();
		Assert.assertSame(indexed, broadcastCapableValueVersions(factRevision));
		Assert.assertSame(indexed, broadcastCapableValueVersions(fresh));
		seed.hop.setName("renamed-after-index");
		Assert.assertSame("mutable Hop metadata is outside the immutable node index",
			indexed, broadcastCapableValueVersions(continuity.freshQueryState()));
	}

	@Test
	public void broadcastAliasIndexInvalidatesOnNodeAuthorityToggle() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		Ref alias = full.broadcastAlias("seed-alias", seed);
		Ref append = full.binary("append", OpOp2.CBIND, seed, seed, false);
		full.additionalCandidate(append, List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.BROADCAST)));
		NativePlacementContinuity initial = full.resolver();
		ValueVersionKey seedValue = full.nodes.get(seed.key).valueVersion();
		Set<ValueVersionKey> initialIndex = broadcastCapableValueVersions(initial);
		Assert.assertTrue(initialIndex.contains(seedValue));
		Assert.assertFalse("the selectable alias keeps the protected relocation row active",
			initial.proves(List.of(append.key), seed.anchor));

		Node prior = full.nodes.get(alias.key);
		Node unavailable = new Node(prior.key(), prior.kind(), prior.valueVersion(), prior.emittedWork(),
			List.of(state(FType.FULL)), prior.exclusions(), prior.anchors());
		NativePlacementContinuity removed = initial.nextNodeAuthorityRevision(unavailable, List.of());
		Assert.assertNotSame("node authority changes must rebuild the structural index",
			initialIndex, broadcastCapableValueVersions(removed));
		Assert.assertFalse(broadcastCapableValueVersions(removed).contains(seedValue));
		Assert.assertTrue("without a selectable alias the protected relocation row is inactive",
			removed.proves(List.of(append.key), seed.anchor));

		Node restored = new Node(prior.key(), prior.kind(), prior.valueVersion(), prior.emittedWork(),
			prior.legalAlternatives(), prior.exclusions(), prior.anchors());
		NativePlacementContinuity restoredContinuity =
			removed.nextNodeAuthorityRevision(restored, List.of());
		Assert.assertTrue(broadcastCapableValueVersions(restoredContinuity).contains(seedValue));
		Assert.assertFalse(restoredContinuity.proves(List.of(append.key), seed.anchor));
		Assert.assertSame("the prior immutable context remains valid after a later authority revision",
			initialIndex, broadcastCapableValueVersions(initial));
	}

	@Test
	public void occurrenceComponentsStayLazyAndShareOneRealizedIndexAcrossRevisions() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		NativePlacementContinuity first = full.resolver();
		Object holder = accessibleField(NativePlacementContinuity.class, "occurrenceComponents").get(first);
		Field realized = accessibleField(holder.getClass(), "components");
		Assert.assertNull("construction must not build the SCC index", realized.get(holder));

		Assert.assertTrue("legacy physical continuity does not consume occurrence SCCs",
			first.proves(List.of(source.key), seed.anchor));
		Assert.assertNull("legacy proves must keep the SCC index lazy", realized.get(holder));

		CandidateRealizationReference reference = full.reference(
			source, List.of(CandidateInputState.present(FType.FULL)));
		NativePlacementContinuity.NativeContinuityProof actual =
			first.proveCandidate(reference, seed.anchor);
		Object built = realized.get(holder);
		Assert.assertNotNull("the first SCC-aware candidate query must realize the index", built);
		Assert.assertEquals("lazy construction must preserve cold proof semantics",
			full.resolver().proveCandidate(reference, seed.anchor), actual);

		NativePlacementContinuity fresh = first.freshQueryState();
		Assert.assertSame(holder,
			accessibleField(NativePlacementContinuity.class, "occurrenceComponents").get(fresh));
		Node prior = full.nodes.get(source.key);
		Node replacement = new Node(prior.key(), prior.kind(), prior.valueVersion(), prior.emittedWork(),
			prior.legalAlternatives(), prior.exclusions(), prior.anchors());
		List<CandidateRuleFact> sourceFacts = full.candidates.stream()
			.filter(fact -> fact.key().parentOccurrence() == source.key).toList();
		NativePlacementContinuity nodeRevision = first.nextNodeAuthorityRevision(replacement, sourceFacts);
		Object revisedHolder = accessibleField(
			NativePlacementContinuity.class, "occurrenceComponents").get(nodeRevision);
		Assert.assertSame("node-only revisions must share the exact SCC holder", holder, revisedHolder);
		Assert.assertSame("all shared revisions must observe the same realized index",
			built, realized.get(revisedHolder));
	}

	@Test
	public void occurrenceComponentsRealizeOnceUnderConcurrentFirstUse() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		full.unary("source", OpOp1.LOG, seed, false);
		NativePlacementContinuity continuity = full.resolver();
		Object holder = accessibleField(
			NativePlacementContinuity.class, "occurrenceComponents").get(continuity);
		Method componentsMethod = holder.getClass().getDeclaredMethod("components");
		componentsMethod.setAccessible(true);
		java.util.concurrent.ExecutorService workers =
			java.util.concurrent.Executors.newFixedThreadPool(4);
		try {
			List<java.util.concurrent.Callable<Object>> tasks = java.util.stream.IntStream.range(0, 16)
				.mapToObj(ignored -> (java.util.concurrent.Callable<Object>)() ->
					componentsMethod.invoke(holder)).toList();
			List<java.util.concurrent.Future<Object>> results = workers.invokeAll(tasks);
			Object first = results.get(0).get();
			for(java.util.concurrent.Future<Object> result : results)
				Assert.assertSame("concurrent first use must publish one immutable SCC index",
					first, result.get());
		}
		finally {
			workers.shutdownNow();
		}
	}

	@Test
	public void structuralContextMatcherRequiresEveryExactOwnedInventory() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		full.reaching.put(source.key, List.of(seed.key));
		full.privacy.put(source.key, Privacy.PRIVATE_AGGREGATE);
		Set<CompiledHopKey> incomplete = identitySet(seed.key);
		NativePlacementContinuity continuity = new NativePlacementContinuity(
			full.nodes, full.origins, full.candidates, full.edges, full.reaching,
			incomplete, full.privacy);

		Map<CompiledHopKey,Node> equalNodes = new IdentityHashMap<>();
		full.nodes.forEach((key, node) -> equalNodes.put(key, new Node(node.key(), node.kind(),
			node.valueVersion(), node.emittedWork(), node.legalAlternatives(), node.exclusions(), node.anchors())));
		Map<CompiledHopKey,Hop> equalOrigins = new IdentityHashMap<>(full.origins);
		List<CompiledInputEdgeFact> equalEdges = full.edges.stream()
			.map(edge -> new CompiledInputEdgeFact(edge.producer(), edge.consumer(), edge.inputPosition()))
			.toList();
		Map<CompiledHopKey,List<CompiledHopKey>> equalReaching = new IdentityHashMap<>();
		full.reaching.forEach((key, values) -> equalReaching.put(key, List.copyOf(values)));
		Map<CompiledHopKey,Privacy> equalPrivacy = new IdentityHashMap<>(full.privacy);
		Assert.assertTrue("equal new Node and edge values with the same owned identities must match",
			continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
				equalReaching, identitySet(seed.key), equalPrivacy));

		Map<CompiledHopKey,Node> changedNodes = new IdentityHashMap<>(equalNodes);
		Node priorSource = changedNodes.get(source.key);
		changedNodes.put(source.key, new Node(priorSource.key(), priorSource.kind(),
			priorSource.valueVersion(), priorSource.emittedWork(), priorSource.legalAlternatives(),
			priorSource.exclusions(), List.of(seed.anchor)));
		Assert.assertFalse(continuity.matchesStructuralContext(changedNodes, equalOrigins, equalEdges,
			equalReaching, incomplete, equalPrivacy));

		Map<CompiledHopKey,Node> missingNode = new IdentityHashMap<>(equalNodes);
		missingNode.remove(source.key);
		Assert.assertFalse(continuity.matchesStructuralContext(missingNode, equalOrigins, equalEdges,
			equalReaching, incomplete, equalPrivacy));

		Map<CompiledHopKey,Hop> changedOrigins = new IdentityHashMap<>(equalOrigins);
		changedOrigins.put(source.key, seed.hop);
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, changedOrigins, equalEdges,
			equalReaching, incomplete, equalPrivacy));

		List<CompiledInputEdgeFact> changedEdges = List.of(
			new CompiledInputEdgeFact(seed.key, source.key, 1));
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, equalOrigins, changedEdges,
			equalReaching, incomplete, equalPrivacy));

		Map<CompiledHopKey,List<CompiledHopKey>> provisionalReaching = new IdentityHashMap<>();
		provisionalReaching.put(source.key, List.of());
		Assert.assertFalse("a provisional seed context cannot replace the full reaching context",
			continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
				provisionalReaching, incomplete, equalPrivacy));
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
			equalReaching, Set.of(), equalPrivacy));

		Map<CompiledHopKey,Privacy> changedPrivacy = new IdentityHashMap<>(equalPrivacy);
		changedPrivacy.put(source.key, Privacy.PUBLIC);
		Assert.assertFalse(continuity.matchesStructuralContext(equalNodes, equalOrigins, equalEdges,
			equalReaching, incomplete, changedPrivacy));
	}

	@Test
	public void structuralRevisionReusesOnlyTheExactOrderedComponentReadSet() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		full.reaching.put(source.key, List.of(seed.key));
		NativePlacementContinuity initial = full.resolver();

		// The certificate includes owner iteration order. IdentityHashMap's
		// size-specific copy can reorder colliding identities; change only payload.
		Map<CompiledHopKey,Node> changedNodes = new java.util.LinkedHashMap<>();
		full.nodes.forEach(changedNodes::put);
		Node prior = changedNodes.get(source.key);
		changedNodes.put(source.key, new Node(prior.key(), prior.kind(), prior.valueVersion(),
			prior.emittedWork(), prior.legalAlternatives(), prior.exclusions(), List.of(seed.anchor)));
		NativePlacementContinuity nodeRevision = initial.structuralRevision(changedNodes,
			full.origins, full.candidates, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertTrue("node payload changes do not alter the SCC read set",
			initial.sharesOccurrenceComponentsWith(nodeRevision));
		Assert.assertEquals("SCC reuse must match a forced fresh resolver",
			new NativePlacementContinuity(changedNodes, full.origins, full.candidates, full.edges,
				full.reaching, Set.of(), full.privacy).proves(List.of(source.key), seed.anchor),
			nodeRevision.proves(List.of(source.key), seed.anchor));

		List<CompiledInputEdgeFact> reversedEdges = List.of(
			new CompiledInputEdgeFact(source.key, seed.key, 0));
		NativePlacementContinuity edgeRevision = initial.structuralRevision(full.nodes,
			full.origins, full.candidates, reversedEdges, full.reaching, Set.of(), full.privacy);
		Assert.assertFalse("a changed directed dependency must rebuild the SCC index",
			initial.sharesOccurrenceComponentsWith(edgeRevision));

		Map<CompiledHopKey,List<CompiledHopKey>> changedReaching = new IdentityHashMap<>();
		changedReaching.put(source.key, List.of(source.key));
		NativePlacementContinuity reachingRevision = initial.structuralRevision(full.nodes,
			full.origins, full.candidates, full.edges, changedReaching, Set.of(), full.privacy);
		Assert.assertFalse("a changed reaching dependency must rebuild the SCC index",
			initial.sharesOccurrenceComponentsWith(reachingRevision));

		CompiledHopKey equalButDistinct = new CompiledHopKey(seed.key.programFingerprint(),
			seed.key.functionNamespace(), seed.key.callSitePath(), seed.key.recompileContext(),
			seed.key.controlRegion(), seed.key.emittedHopInstance(), seed.key.canonicalSourceOrigin());
		Map<CompiledHopKey,Node> replacedIdentityNodes = new IdentityHashMap<>(full.nodes);
		Node seedNode = replacedIdentityNodes.remove(seed.key);
		replacedIdentityNodes.put(equalButDistinct, new Node(equalButDistinct, seedNode.kind(),
			seedNode.valueVersion(), seedNode.emittedWork(), seedNode.legalAlternatives(),
			seedNode.exclusions(), seedNode.anchors()));
		NativePlacementContinuity identityRevision = initial.structuralRevision(replacedIdentityNodes,
			full.origins, full.candidates, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertFalse("value-equal owner identities are not the same SCC authority",
			initial.sharesOccurrenceComponentsWith(identityRevision));
	}

	@Test
	public void equalNewFactObjectsUseRevisionGeneratedApiAndMatchFreshResolver() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact original = full.fact(root, inputs);
		CandidateEmissionFact originalEmission = original.allowedEmissionFacts().get(0);
		CandidateEmissionRealization proposedRealization = CandidateEmissionRealization.nativeLineage(
			originalEmission.emissionState(), "equal-new-revision", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			original.key(), proposedRealization);
		NativePlacementContinuity first = full.resolver();
		Assert.assertFalse(first.proveGeneratedCandidateAlternatives(
			original, originalEmission, proposed, seed.anchor).isEmpty());

		CandidateEmissionFact copiedEmission = new CandidateEmissionFact(
			originalEmission.emissionState(), originalEmission.executionFType(),
			originalEmission.derivedFoutAction(), new ArrayList<>(originalEmission.realizations()));
		CandidateRuleFact copied = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(),
			List.of(copiedEmission), original.failureCode());
		List<CandidateRuleFact> revisedFacts = new ArrayList<>(full.candidates);
		revisedFacts.set(revisedFacts.indexOf(original), copied);
		NativePlacementContinuity conservative = first.nextRevision(revisedFacts);
		NativePlacementContinuity revised = first
			.nextRevisionWithCompleteCandidateDelta(revisedFacts, Set.of());
		List<NativePlacementContinuity.NativeContinuityProof> actual = revised
			.proveGeneratedCandidateAlternatives(copied, copiedEmission, proposed, seed.anchor);
		List<NativePlacementContinuity.NativeContinuityProof> conservativeResult = conservative
			.proveGeneratedCandidateAlternatives(copied, copiedEmission, proposed, seed.anchor);
		NativePlacementContinuity fresh = new NativePlacementContinuity(full.nodes, full.origins,
			revisedFacts, full.edges, full.reaching, Set.of(), full.privacy);
		List<NativePlacementContinuity.NativeContinuityProof> expected = fresh
			.proveGeneratedCandidateAlternatives(copied, copiedEmission, proposed, seed.anchor);
		Assert.assertFalse("equal new current fact/emission identities remain valid", actual.isEmpty());
		Assert.assertEquals("hinted revision must retain exact current generated-root authority",
			conservativeResult, actual);
		Assert.assertEquals("revision reuse must agree with a fresh exact-context resolver", expected, actual);
		Assert.assertTrue(revised.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);
		Assert.assertEquals(0, revised.revisionComparisonSnapshot().ownersCompared());
	}

	@Test
	public void cachedTopologyReusesDefaultOverlayForUnrelatedQueryPins() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref firstRoot = full.unary("first-root", OpOp1.LOG, seed, false);
		Ref secondRoot = full.unary("second-root", OpOp1.EXP, seed, false);
		Ref child = full.unary("child", OpOp1.SQRT, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference childReference = full.reference(child, inputs);
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);

		List<?> first = candidateAlternatives(resolver, childReference, seed.anchor,
			Map.of(firstRoot.key, full.reference(firstRoot, inputs)));
		List<?> second = candidateAlternatives(resolver, childReference, seed.anchor,
			Map.of(secondRoot.key, full.reference(secondRoot, inputs)));
		Assert.assertEquals(1, first.size());
		Assert.assertEquals(first, second);
		Assert.assertSame("a row untouched by either exact root pin must reuse its immutable default overlay",
			first.get(0), second.get(0));
	}

	@Test
	public void rootOverlayStillCollapsesRowsThatConvergeAfterPinning() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, inputs,
			anchor(FType.FULL, "worker1:8001", 0, 50),
			anchor(FType.FULL, "worker1:8001", 100, 150));
		CandidateRuleFact producerFact = full.fact(producer, inputs);
		List<CandidateRealizationReference> producerReferences = producerFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization ->
				CandidateRealizationReference.of(producerFact.key(), realization)).toList();
		Ref dependent = full.unary("dependent", OpOp1.EXP, producer, false);
		CandidateRealizationReference dependentReference = full.withClauses(dependent, inputs,
			producerReferences.stream().map(reference -> new CandidateRealizationSupportClause(
				List.of(), List.of(CandidateRealizationInputBinding.direct(0, reference)))).toList());
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);

		List<?> alternatives = candidateAlternatives(resolver, dependentReference, seed.anchor,
			Map.of(producer.key, producerReferences.get(0)));
		Assert.assertEquals("two clause pins forced to the same exact root realization remain one edge",
			1, alternatives.size());
	}

	@Test
	public void recursiveRootDependencyNeverUsesDefaultOverlay() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loop = full.unary("loop", OpOp1.LOG, seed, false);
		full.edges.removeIf(edge -> edge.consumer() == loop.key);
		full.edges.add(new CompiledInputEdgeFact(loop.key, loop.key, 0));
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference loopReference = full.reference(loop, inputs);
		NativePlacementContinuity resolver = full.resolver(new SearchSpaceMetrics(), 0, 0);

		List<?> alternatives = candidateAlternatives(resolver, loopReference, seed.anchor,
			Map.of(loop.key, loopReference));
		Assert.assertEquals(1, alternatives.size());
		@SuppressWarnings("unchecked")
		List<Object> dependencies = (List<Object>)accessibleField(
			alternatives.get(0).getClass(), "dependencies").get(alternatives.get(0));
		Assert.assertEquals(1, dependencies.size());
		Object state = accessibleField(dependencies.get(0).getClass(), "state").get(dependencies.get(0));
		Assert.assertTrue("a root re-entry must remain query-pinned/template-root",
			accessibleField(state.getClass(), "templateRoot").getBoolean(state));
	}

	@Test
	public void initialStructuralContextRejectsDuplicateCompiledEdgePosition() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		List<CompiledInputEdgeFact> malformed = new ArrayList<>(full.edges);
		malformed.add(new CompiledInputEdgeFact(seed.key, source.key, 0));

		try {
			new NativePlacementContinuity(full.nodes, full.origins, full.candidates,
				malformed, full.reaching, Set.of(), full.privacy);
			Assert.fail("duplicate compiled input positions must fail at initial structure construction");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertEquals("Duplicate compiled input edge position", expected.getMessage());
		}
	}

	@Test
	public void metricsCollectionDoesNotChangeProofResults() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));

		Assert.assertEquals(full.resolver().proveCandidateAlternatives(reference, seed.anchor),
			full.resolver(new SearchSpaceMetrics(), 0, 0)
				.proveCandidateAlternatives(reference, seed.anchor));
	}

	@Test
	public void directBindingReusesCanonicalImmutableNativeProofBindings() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref leftSource = full.source("left-source", pool);
		Ref rightSource = full.source("right-source", pool);
		Ref left = full.logicalRead("left");
		Ref right = full.logicalRead("right");
		full.reaching.put(left.key, List.of(leftSource.key));
		full.reaching.put(right.key, List.of(rightSource.key));
		List<CandidateInputState> unaryInput = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(left, unaryInput, pool);
		full.samePoolRealizations(right, unaryInput, pool);
		Ref sum = full.binary("sum", OpOp2.PLUS, left, right, false);
		for(Ref ref : List.of(leftSource, rightSource, left, right, sum))
			full.privacy(ref, Privacy.PRIVATE_AGGREGATE);
		List<CandidateRuleFact> facts = List.copyOf(full.candidates);
		List<Node> nodes = List.copyOf(full.nodes.values());
		NativePlacementContinuity continuity = full.resolver();

		Method indexMethod = PlacementRelationClosure.class.getDeclaredMethod(
			"directBindingIndex", List.class, List.class, List.class, List.class);
		indexMethod.setAccessible(true);
		Object index = indexMethod.invoke(null, facts, nodes, full.edges, facts);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsMeasured", index.getClass(), List.class,
			Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		for(Hop hop : full.origins.values())
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 4, 2));
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> rebound = (List<CandidateRuleFact>)bind.invoke(
			PlacementBuilderTestAccess.relationClosure(new NeutralPlacementGraphBuilder()),
			index, facts, full.origins, shapes,
			continuity, Set.of(sum.key));

		Field memoField = accessibleField(NativePlacementContinuity.class, "completedProofMemo");
		@SuppressWarnings("unchecked")
		Map<Object,Object> memo = (Map<Object,Object>)memoField.get(continuity);
		List<NativePlacementContinuity.NativeContinuityProof> memoProofs = new ArrayList<>();
		for(Object entry : memo.values()) {
			@SuppressWarnings("unchecked")
			List<NativePlacementContinuity.NativeContinuityProof> proofs =
				(List<NativePlacementContinuity.NativeContinuityProof>)accessibleField(
					entry.getClass(), "proofs").get(entry);
			memoProofs.addAll(proofs);
		}
		CandidateRuleFact reboundSum = rebound.stream()
			.filter(fact -> fact.key().parentOccurrence() == sum.key).findFirst().orElseThrow();
		Set<CompiledHopKey> expectedSources = Set.of(left.key, right.key);
		int matchedClauses = 0;
		for(CandidateEmissionFact emission : reboundSum.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations())
				for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
					PlacementProofKey authority = clause.proofDependencies().stream()
						.filter(proof -> proof.kind() == PlacementProofKind.NATIVE_CONTINUITY
							&& proof.owner() == sum.key).findFirst().orElse(null);
					if(authority == null)
						continue;
					NativePlacementContinuity.NativeContinuityProof proof = memoProofs.stream()
						.filter(candidate -> candidate.normalizedSignature().equals(
							authority.authoritySignature())).findFirst().orElse(null);
					if(proof != null) {
						Assert.assertEquals(proof.immediateBindings(), clause.inputBindings());
						Assert.assertSame("explicit direct binding must retain the proof's canonical list marker",
							proof.immediateBindings(), clause.inputBindings());
					}
					else
						Assert.assertTrue("relation-native members retain canonical binding order",
							clause.inputBindings().stream().sorted().toList().equals(clause.inputBindings()));
					Assert.assertEquals(expectedSources, clause.inputBindings().stream()
						.map(binding -> binding.source().rule().parentOccurrence())
						.collect(java.util.stream.Collectors.toSet()));
					Assert.assertEquals(List.of(0, 1), clause.inputBindings().stream()
						.map(CandidateRealizationInputBinding::inputPosition).toList());
					Assert.assertThrows(UnsupportedOperationException.class, () ->
						clause.inputBindings().add(clause.inputBindings().get(0)));
					matchedClauses++;
				}
		Assert.assertTrue("the two-input direct native output must be fully grounded", matchedClauses > 0);
	}

	@Test
	public void unchangedNativeRelationSurvivesNextClosureConsumersWithoutMemberHandles() throws Exception {
		Fixture full = new Fixture(FType.ROW);
		DurableAnchorKey pool = anchor(FType.ROW, "worker1:8001", 0, 50);
		Ref source = full.source("source", pool);
		Ref input = full.logicalRead("input");
		full.reaching.put(input.key, List.of(source.key));
		List<CandidateInputState> unaryInputs = List.of(CandidateInputState.present(FType.ROW));
		CandidateRuleFact inputFact = full.fact(input, unaryInputs);
		CandidateEmissionFact inputEmission = inputFact.allowedEmissionFacts().get(0);
		List<CandidateEmissionRealization> inputChoices = List.of(
			CandidateEmissionRealization.nativeLineage(
				inputEmission.emissionState(), "input-option-a", pool, List.of(new PlacementProofKey(
					PlacementProofKind.NATIVE_CONTINUITY, input.key, "input-option-a")), List.of()),
			CandidateEmissionRealization.nativeLineage(
				inputEmission.emissionState(), "input-option-b", pool, List.of(new PlacementProofKey(
					PlacementProofKind.NATIVE_CONTINUITY, input.key, "input-option-b")), List.of()));
		CandidateEmissionFact expandedInput = new CandidateEmissionFact(inputEmission.emissionState(),
			inputEmission.executionFType(), inputEmission.derivedFoutAction(), inputChoices);
		full.candidates.set(full.candidates.indexOf(inputFact), new CandidateRuleFact(inputFact.key(),
			inputFact.status(), inputFact.capability(), inputFact.shapeProof(), inputFact.profile(),
			List.of(expandedInput), inputFact.failureCode()));
		Ref reverse = full.unary("consumer", OpOp1.LOG, input, false);
		CandidateRuleFact reverseFact = full.fact(reverse,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionFact reverseEmission = reverseFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = new CandidateRealizationReference(reverseFact.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				reverseEmission.emissionState(), "generated-product-probe"));
		NativePlacementContinuity.CandidateSupportResult generated = full.resolver()
			.proveGeneratedCandidateSupport(reverseFact, reverseEmission, proposed, pool);
		Assert.assertNotNull("generated proofs=" + generated.proofs().stream()
			.map(NativePlacementContinuity.NativeContinuityProof::immediateBindings).toList(),
			generated.supportProduct());
		Assert.assertEquals(2, generated.supportProduct().size());
		Ref dead = full.unary("dead", OpOp1.LOG, source, false);
		CandidateRuleFact deadFact = full.fact(dead, unaryInputs);
		CandidateEmissionFact deadEmission = deadFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference missingSource = new CandidateRealizationReference(
			inputFact.key(), PlacementIdentity.PlacementRealizationKey.nativeLineage(
				inputEmission.emissionState(), "missing-source"));
		CandidateEmissionRealization deadRealization = CandidateEmissionRealization.nativeLineage(
			deadEmission.emissionState(), "dead", pool, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, dead.key, "dead")),
			List.of(CandidateRealizationInputBinding.direct(0, missingSource)));
		CandidateEmissionFact groundedDead = new CandidateEmissionFact(deadEmission.emissionState(),
			deadEmission.executionFType(), deadEmission.derivedFoutAction(), List.of(deadRealization));
		full.candidates.set(full.candidates.indexOf(deadFact), new CandidateRuleFact(deadFact.key(),
			deadFact.status(), deadFact.capability(), deadFact.shapeProof(), deadFact.profile(),
			List.of(groundedDead), deadFact.failureCode()));
		List<CandidateRuleFact> facts = List.copyOf(full.candidates);
		List<Node> nodes = List.copyOf(full.nodes.values());
		Map<Hop,NodeShapeFact> shapes = new IdentityHashMap<>();
		for(Hop hop : full.origins.values())
			shapes.put(hop, new NodeShapeFact(DataType.MATRIX, 4, 2));
		shapes.put(reverse.hop, new NodeShapeFact(DataType.MATRIX, -1, -1));
		Method indexMethod = PlacementRelationClosure.class.getDeclaredMethod(
			"directBindingIndex", List.class, List.class, List.class, List.class);
		indexMethod.setAccessible(true);
		Object index = indexMethod.invoke(null, facts, nodes, full.edges, facts);
		Method bind = PlacementRelationClosure.class.getDeclaredMethod(
			"bindDirectNativeCandidateRealizationsMeasured", index.getClass(), List.class,
			Map.class, Map.class, NativePlacementContinuity.class, Set.class);
		bind.setAccessible(true);
		PlacementRelationClosure closure =
			PlacementBuilderTestAccess.relationClosure(new NeutralPlacementGraphBuilder());
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> first = (List<CandidateRuleFact>)bind.invoke(closure,
			index, facts, full.origins, shapes, full.resolver(), Set.of(reverse.key));
		CandidateEmissionRealization relation = first.stream()
			.filter(fact -> fact.key().parentOccurrence() == reverse.key)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.nativeContinuitySupportProduct().isPresent())
			.findFirst().orElseThrow(() -> new AssertionError("missing lazy native relation: " + first));
		Assert.assertEquals(2,
			relation.nativeContinuitySupportProduct().orElseThrow().logicalClauseCount());
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());
		Assert.assertTrue(PlacementSupportRelations.executableSourceRealization(
			reverseFact.key(), relation));
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());

		Method dependencies = PlacementRelationClosure.class.getDeclaredMethod(
			"addDirectSupportDependencies", Map.class, List.class);
		dependencies.setAccessible(true);
		dependencies.invoke(null, new IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>>(), first);
		Method single = PlacementRelationClosure.class.getDeclaredMethod(
			"singlePartitionPossibilities", CandidateEmissionRealization.class, Map.class, Set.class);
		single.setAccessible(true);
		single.invoke(null, relation, Map.of(), Set.of());
		Method replaySeeds = PlacementRelationClosure.class.getDeclaredMethod(
			"nativeResidencyWitnesses", CandidateEmissionRealization.class);
		replaySeeds.setAccessible(true);
		try(@SuppressWarnings("unchecked") java.util.stream.Stream<DurableAnchorKey> witnesses =
			(java.util.stream.Stream<DurableAnchorKey>)replaySeeds.invoke(null, relation)) {
			Assert.assertEquals(1, witnesses.count());
		}
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());

		PlacementSupportRelations.WorklistResult pruned =
			PlacementSupportRelations.pruneUnsupportedRealizationsToFixedPointWithWork(
				first, null, List.of(), Map.of());
		Assert.assertTrue("the unrelated staging realization forces the real worklist",
			pruned.work().deletedRealizations() > 0 && pruned.work().queueVisits() > 0);
		CandidateEmissionRealization prunedRelation = pruned.facts().stream()
			.filter(fact -> fact.key().parentOccurrence() == reverse.key)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.nativeContinuitySupportProduct().isPresent())
			.findFirst().orElseThrow();
		Assert.assertSame(relation, prunedRelation);
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());

		List<CandidateRuleFact> oneSourceFacts = pruned.facts().stream().map(fact -> {
			if(fact.key().parentOccurrence() != input.key)
				return fact;
			CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
			CandidateEmissionFact one = new CandidateEmissionFact(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), List.of(inputChoices.get(0)));
			return new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), List.of(one), fact.failureCode());
		}).toList();
		List<CandidateRuleFact> restrictedFacts = PlacementSupportRelations
			.pruneUnsupportedRealizationsToFixedPoint(oneSourceFacts, null, List.of(), Map.of());
		CandidateEmissionRealization restricted = restrictedFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() == reverse.key)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.nativeContinuitySupportProduct().isPresent())
			.findFirst().orElseThrow();
		Assert.assertEquals(1,
			restricted.nativeContinuitySupportProduct().orElseThrow().logicalClauseCount());
		Assert.assertEquals(0, restricted.fullyMaterializedSupportClauseCount());
		CandidateRuleFact originalConsumer = pruned.facts().stream()
			.filter(fact -> fact.key().parentOccurrence() == reverse.key).findFirst().orElseThrow();
		CandidateRuleFact restrictedConsumer = restrictedFacts.stream()
			.filter(fact -> fact.key().parentOccurrence() == reverse.key).findFirst().orElseThrow();
		Assert.assertNotEquals(originalConsumer, restrictedConsumer);
		Assert.assertEquals(0, relation.fullyMaterializedSupportClauseCount());
		Assert.assertEquals(0, restricted.fullyMaterializedSupportClauseCount());

		Object nextIndex = indexMethod.invoke(null, pruned.facts(), nodes, full.edges, facts);
		Assert.assertEquals("direct index must consume native metadata", 0,
			relation.fullyMaterializedSupportClauseCount());
		NativePlacementContinuity nextContinuity = new NativePlacementContinuity(
			full.nodes, full.origins, pruned.facts(), full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals("revision index must retain the native relation", 0,
			relation.fullyMaterializedSupportClauseCount());
		Set<CompiledHopKey> metadataOwners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		metadataOwners.add(reverse.key);
		Assert.assertTrue(nextContinuity.expandValueMapMetadataDependencies(metadataOwners));
		Assert.assertEquals("VALUE_MAP metadata projection skips native-lineage members", 0,
			relation.fullyMaterializedSupportClauseCount());
		Method readers = NativePlacementContinuity.class.getDeclaredMethod(
			"indexBoundCandidateReaders", Map.class);
		readers.setAccessible(true);
		readers.invoke(null, Map.of(reverse.key, List.of(originalConsumer)));
		Assert.assertEquals("VALUE_MAP reader projection skips native-lineage members", 0,
			relation.fullyMaterializedSupportClauseCount());
		@SuppressWarnings("unchecked")
		List<CandidateRuleFact> second = (List<CandidateRuleFact>)bind.invoke(closure,
			nextIndex, pruned.facts(), full.origins, shapes, nextContinuity, Set.of(reverse.key));
		CandidateEmissionRealization retained = second.stream()
			.filter(fact -> fact.key().parentOccurrence() == reverse.key)
			.flatMap(fact -> fact.allowedEmissionFacts().stream())
			.flatMap(emission -> emission.realizations().stream())
			.filter(realization -> realization.nativeContinuitySupportProduct().isPresent())
			.findFirst().orElseThrow();
		Assert.assertSame("unchanged relation authority is retained across the next wave",
			relation, retained);
		Assert.assertEquals(0, retained.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void proofHistoryOnlyRevisionReusesRelationButChangedBindingRebuilds() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = full.reference(source, inputs);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity first = full.resolver(metrics, 8, 128);
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			first.proveCandidateAlternatives(reference, seed.anchor);
		long built = metrics.snapshot().proofGraphsBuilt();
		CandidateRuleFact original = full.fact(source, inputs);
		CandidateEmissionFact emission = original.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		CandidateRealizationSupportClause clause = realization.supportClauses().get(0);
		CandidateRealizationSupportClause renamed = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.SHAPE, source.key, "history-only")),
			clause.inputBindings(), clause.nativeWorkerPoolWitness(), clause.nativeWorkerPoolLayoutExact());
		CandidateEmissionRealization changed = new CandidateEmissionRealization(realization.key(),
			List.of(renamed));
		CandidateEmissionFact changedEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(changed));
		CandidateRuleFact changedFact = new CandidateRuleFact(original.key(), original.status(),
			original.capability(), original.shapeProof(), original.profile(), List.of(changedEmission),
			original.failureCode());
		List<CandidateRuleFact> historyFacts = new ArrayList<>(full.candidates);
		historyFacts.set(historyFacts.indexOf(original), changedFact);
		Assert.assertNotEquals(original, changedFact);
		NativePlacementContinuity conservativeHistory = first.nextRevision(historyFacts);
		NativePlacementContinuity historyRevision = first.nextRevisionWithCompleteCandidateDelta(
			historyFacts, identitySet(source.key));
		Assert.assertSame("proof history does not change the private support relation", expected,
			historyRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(conservativeHistory.proveCandidateAlternatives(reference, seed.anchor),
			historyRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertEquals(new NativePlacementContinuity(full.nodes, full.origins,
			historyFacts, full.edges, full.reaching, Set.of(), full.privacy)
			.proveCandidateAlternatives(reference, seed.anchor),
			historyRevision.proveCandidateAlternatives(reference, seed.anchor));
		Assert.assertTrue(historyRevision.revisionComparisonSnapshot().ownersCompared() > 0);
		Assert.assertTrue("proof-only row replacement must compare its execution projection",
			historyRevision.revisionComparisonSnapshot().continuityProjectionsCompared() > 0);
		Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());

		CandidateRealizationReference seedReference = new CandidateRealizationReference(
			new CandidateRuleKey(seed.key, List.of()),
			PlacementIdentity.PlacementRealizationKey.durable(emission.emissionState(), seed.anchor));
		CandidateRealizationSupportClause rebound = new CandidateRealizationSupportClause(
			renamed.proofDependencies(),
			List.of(CandidateRealizationInputBinding.direct(0, seedReference)),
			renamed.nativeWorkerPoolWitness(), renamed.nativeWorkerPoolLayoutExact());
		CandidateEmissionRealization reboundRealization = new CandidateEmissionRealization(
			realization.key(), List.of(rebound));
		CandidateEmissionFact reboundEmission = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(reboundRealization));
		List<CandidateRuleFact> changedBindingFacts = new ArrayList<>(historyFacts);
		changedBindingFacts.set(historyFacts.indexOf(changedFact), new CandidateRuleFact(
			original.key(), original.status(), original.capability(), original.shapeProof(),
			original.profile(), List.of(reboundEmission), original.failureCode()));
		NativePlacementContinuity changedBinding = historyRevision.nextRevisionWithCompleteCandidateDelta(
			changedBindingFacts, identitySet(source.key));
		List<NativePlacementContinuity.NativeContinuityProof> changedActual =
			changedBinding.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertEquals(historyRevision.nextRevision(changedBindingFacts)
			.proveCandidateAlternatives(reference, seed.anchor), changedActual);
		Assert.assertEquals(new NativePlacementContinuity(full.nodes, full.origins,
			changedBindingFacts, full.edges, full.reaching, Set.of(), full.privacy)
			.proveCandidateAlternatives(reference, seed.anchor), changedActual);
		Assert.assertTrue("an exact input binding changes the private relation",
			metrics.snapshot().proofGraphsBuilt() > built);
	}

	@Test
	public void nativeProductRevisionProjectionIsExactWithoutEnumeratingDuringProjection()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref left = full.federatedSource("left", pool);
		Ref right = full.federatedSource("right", pool);
		List<CandidateInputState> sourceInputs = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
		full.samePoolRealizations(left, sourceInputs, pool,
			new DurableAnchorKey("left-second", FType.FULL, pool.partitions()));
		full.samePoolRealizations(right, sourceInputs, pool,
			new DurableAnchorKey("right-second", FType.FULL, pool.partitions()));
		Ref owner = full.binary("owner", OpOp2.PLUS, left, right, false);
		CandidateRuleFact template = full.fact(owner, List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = template.allowedEmissionFacts().get(0);

		List<CandidateRealizationInputBinding> leftAxis = productAxis(full, left, sourceInputs, 0);
		List<CandidateRealizationInputBinding> rightAxis = productAxis(full, right, sourceInputs, 1);
		List<List<CandidateRealizationInputBinding>> axes = List.of(leftAxis, rightAxis);
		NativeContinuitySupportClauses firstRelation = productClauses(
			owner.key, pool, axes, pool, true);
		NativeContinuitySupportClauses equalRelation = productClauses(
			owner.key, pool, axes.stream().map(List::copyOf).toList(), pool, true);
		CandidateRuleFact firstFact = productFact(template, emission, firstRelation);
		CandidateRuleFact equalFact = productFact(template, emission, equalRelation);

		Object firstProjection = continuityProjection(List.of(firstFact));
		Object equalProjection = continuityProjection(List.of(equalFact));
		Assert.assertEquals("distinct exact products have one structural projection",
			firstProjection, equalProjection);
		Assert.assertEquals(firstProjection.hashCode(), equalProjection.hashCode());
		Assert.assertEquals("projection and hashing must not enumerate Cartesian members",
			0, firstRelation.materializedHandleCount());
		Assert.assertEquals(0, equalRelation.materializedHandleCount());

		GeneratedHiddenRootFixture warm = generatedHiddenRootFixture(false);
		CandidateRuleFact warmTemplate = warm.activeRoot();
		CandidateEmissionFact warmEmission = warmTemplate.allowedEmissionFacts().get(0);
		List<List<CandidateRealizationInputBinding>> warmAxes = List.of(List.of(
			CandidateRealizationInputBinding.direct(0, warm.proposed())));
		NativeContinuitySupportClauses warmFirstRelation = productClauses(
			warm.root().key, warm.seed().anchor, warmAxes, warm.seed().anchor, true);
		NativeContinuitySupportClauses warmEqualRelation = productClauses(
			warm.root().key, warm.seed().anchor, warmAxes, warm.seed().anchor, true);
		CandidateRuleFact warmFirstFact = productFact(
			warmTemplate, warmEmission, warmFirstRelation);
		CandidateRuleFact warmEqualFact = productFact(
			warmTemplate, warmEmission, warmEqualRelation);
		List<CandidateRuleFact> warmFirstFacts = replaceFact(
			warm.activeFacts(), warmTemplate, warmFirstFact);
		NativePlacementContinuity first = new NativePlacementContinuity(
			warm.full().nodes, warm.full().origins, warmFirstFacts, warm.full().edges,
			warm.full().reaching, Set.of(), warm.full().privacy);
		NativePlacementContinuity.CandidateSupportResult warmed = first
			.proveGeneratedCandidateSupport(warmFirstFact,
				warmFirstFact.allowedEmissionFacts().get(0), warm.proposed(), warm.seed().anchor);
		Assert.assertFalse(warmed.proofs().isEmpty());
		assertGeneratedRootCertificate(first, warm.proposed(), warm.hidden().key, false);
		int handlesAtKnownMetadataBoundary = warmFirstRelation.materializedHandleCount();
		Assert.assertEquals("native metadata lookup must not materialize members",
			0, handlesAtKnownMetadataBoundary);
		List<CandidateRuleFact> warmEqualFacts = replaceFact(
			warmFirstFacts, warmFirstFact, warmEqualFact);
		NativePlacementContinuity equalRevision = first.nextRevision(warmEqualFacts);
		NativePlacementContinuity secondEqualRevision =
			equalRevision.nextRevision(warmFirstFacts);
		Assert.assertTrue(equalRevision.revisionComparisonSnapshot()
			.continuityProjectionsCompared() > 0);
		Assert.assertTrue(secondEqualRevision.revisionComparisonSnapshot()
			.continuityProjectionsCompared() > 0);
		Assert.assertEquals("revision projection must not materialize another member",
			handlesAtKnownMetadataBoundary,
			warmFirstRelation.materializedHandleCount());
		Assert.assertEquals(0, warmEqualRelation.materializedHandleCount());
		NativePlacementContinuity.CandidateSupportResult equalSupport = equalRevision
			.proveGeneratedCandidateSupport(warmEqualFact,
				warmEqualFact.allowedEmissionFacts().get(0), warm.proposed(), warm.seed().anchor);
		NativePlacementContinuity.CandidateSupportResult coldEqual = new NativePlacementContinuity(
			warm.full().nodes, warm.full().origins, warmEqualFacts, warm.full().edges,
			warm.full().reaching, Set.of(), warm.full().privacy)
			.proveGeneratedCandidateSupport(warmEqualFact,
				warmEqualFact.allowedEmissionFacts().get(0), warm.proposed(), warm.seed().anchor);
		Assert.assertEquals(coldEqual.proofs(), equalSupport.proofs());
		assertIdentitySetEquals(coldEqual.dependencyOccurrences(), equalSupport.dependencyOccurrences());
		int replacementHandlesAfterSupportQuery = warmEqualRelation.materializedHandleCount();
		Assert.assertEquals("uniform native metadata preserves the support query without members",
			0, replacementHandlesAfterSupportQuery);

		CandidateRuleFact withdrawnRoot = warm.withdrawnFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == warm.root().key).findFirst().orElseThrow();
		List<CandidateRuleFact> withdrawnFacts = replaceFact(
			warmEqualFacts, warmEqualFact, withdrawnRoot);
		NativePlacementContinuity withdrawn = equalRevision.nextRevision(withdrawnFacts);
		NativePlacementContinuity.CandidateSupportResult withdrawnSupport = withdrawn
			.proveGeneratedCandidateSupport(withdrawnRoot,
				withdrawnRoot.allowedEmissionFacts().get(0), warm.proposed(), warm.seed().anchor);
		NativePlacementContinuity.CandidateSupportResult coldWithdrawn = new NativePlacementContinuity(
			warm.full().nodes, warm.full().origins, withdrawnFacts, warm.full().edges,
			warm.full().reaching, Set.of(), warm.full().privacy)
			.proveGeneratedCandidateSupport(withdrawnRoot,
				withdrawnRoot.allowedEmissionFacts().get(0), warm.proposed(), warm.seed().anchor);
		Assert.assertEquals(coldWithdrawn.proofs(), withdrawnSupport.proofs());
		assertIdentitySetEquals(
			coldWithdrawn.dependencyOccurrences(), withdrawnSupport.dependencyOccurrences());
		NativePlacementContinuity restored = withdrawn.nextRevision(warmFirstFacts);
		NativePlacementContinuity.CandidateSupportResult restoredSupport = restored
			.proveGeneratedCandidateSupport(warmFirstFact,
				warmFirstFact.allowedEmissionFacts().get(0), warm.proposed(), warm.seed().anchor);
		Assert.assertEquals(warmed.proofs(), restoredSupport.proofs());
		assertIdentitySetEquals(warmed.dependencyOccurrences(), restoredSupport.dependencyOccurrences());
		Assert.assertEquals(handlesAtKnownMetadataBoundary,
			warmFirstRelation.materializedHandleCount());
		Assert.assertEquals(replacementHandlesAfterSupportQuery,
			warmEqualRelation.materializedHandleCount());

		NativePlacementContinuity.NativeContinuityProof explicitProof =
			new NativePlacementContinuity.NativeContinuityProof(pool, pool, true,
				List.of(leftAxis.get(0), rightAxis.get(0)));
		CandidateRealizationSupportClause explicitClause = new CandidateRealizationSupportClause(
			List.of(explicitProof.continuityProofKey(owner.key)),
			explicitProof.immediateBindings(), pool, true);
		CandidateRuleFact explicitFact = productFact(template, emission, List.of(explicitClause));
		Assert.assertNotEquals("an explicit subset cannot equal its complete product",
			firstProjection, continuityProjection(List.of(explicitFact)));
		Assert.assertNotEquals("comparison must remain symmetric",
			continuityProjection(List.of(explicitFact)), firstProjection);
		NativeContinuitySupportClauses explicitOracle = productClauses(
			owner.key, pool, axes, pool, true);
		List<CandidateRealizationSupportClause> completeExplicit = new ArrayList<>(explicitOracle);
		CandidateRuleFact completeExplicitFact = productFact(
			template, emission, completeExplicit);
		Assert.assertEquals(firstRelation.size(), completeExplicit.size());
		Assert.assertNotEquals("cross-representation equality is deliberately conservative",
			firstProjection, continuityProjection(List.of(completeExplicitFact)));
		Assert.assertEquals("only the isolated explicit oracle may materialize", 0,
			firstRelation.materializedHandleCount());
		Assert.assertEquals(explicitOracle.size(), explicitOracle.materializedHandleCount());
		Assert.assertEquals(0, firstRelation.materializedHandleCount());
	}

	@Test
	public void nativeProductSeedHistoryRevisionMatchesColdCurrentProofAuthority() throws Exception {
		GeneratedHiddenRootFixture warm = generatedHiddenRootFixture(false);
		CandidateRuleFact template = warm.activeRoot();
		CandidateEmissionFact emission = template.allowedEmissionFacts().get(0);
		DurableAnchorKey pool = warm.seed().anchor;
		List<List<CandidateRealizationInputBinding>> axes = List.of(List.of(
			CandidateRealizationInputBinding.direct(0, warm.proposed())));
		var firstRelation = productClauses(warm.root().key, pool, axes, pool, true);
		var firstFact = productFact(template, emission, firstRelation);
		List<CandidateRuleFact> firstFacts = replaceFact(warm.activeFacts(), template, firstFact);
		var first = new NativePlacementContinuity(warm.full().nodes, warm.full().origins,
			firstFacts, warm.full().edges, warm.full().reaching, Set.of(), warm.full().privacy);
		Assert.assertFalse(first.proveGeneratedCandidateSupport(firstFact,
			firstFact.allowedEmissionFacts().get(0), warm.proposed(), pool).proofs().isEmpty());

		DurableAnchorKey nextSeed = new DurableAnchorKey("changed-seed-history", pool.fType(), pool.partitions());
		var nextProduct = NativePlacementContinuity.NativeSupportProduct.tryCreate(nextSeed, pool, true, axes);
		Assert.assertNotNull(nextProduct);
		var nextRelation = new NativeContinuitySupportClauses(warm.root().key, nextProduct, pool, true);
		var nextFact = productFact(template, emission, nextRelation);
		List<CandidateRuleFact> nextFacts = replaceFact(firstFacts, firstFact, nextFact);
		Assert.assertEquals(continuityProjection(List.of(firstFact)), continuityProjection(List.of(nextFact)));
		Assert.assertEquals(0, nextRelation.materializedHandleCount());
		var reused = first.nextRevision(nextFacts);
		var cold = new NativePlacementContinuity(warm.full().nodes, warm.full().origins,
			nextFacts, warm.full().edges, warm.full().reaching, Set.of(), warm.full().privacy);
		for(DurableAnchorKey querySeed : List.of(pool, nextSeed)) {
			var expected = cold.proveGeneratedCandidateSupport(nextFact,
				nextFact.allowedEmissionFacts().get(0), warm.proposed(), querySeed);
			var actual = reused.proveGeneratedCandidateSupport(nextFact,
				nextFact.allowedEmissionFacts().get(0), warm.proposed(), querySeed);
			Assert.assertFalse(expected.proofs().isEmpty());
			Assert.assertEquals(expected.proofs(), actual.proofs());
			assertIdentitySetEquals(expected.dependencyOccurrences(), actual.dependencyOccurrences());
			for(var proof : actual.proofs())
				Assert.assertEquals(querySeed, proof.externalSeed());
		}
		Assert.assertEquals(0, nextRelation.materializedHandleCount());
		Assert.assertNotEquals("stored member authority retains its current seed history",
			firstRelation.get(0).proofDependencies(), nextRelation.get(0).proofDependencies());
	}

	@Test
	public void nativeProductRevisionProjectionDistinguishesEveryStructuralAxis() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref left = full.federatedSource("left", pool);
		Ref right = full.federatedSource("right", pool);
		Ref foreign = full.federatedSource("foreign", pool);
		List<CandidateInputState> sourceInputs = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal());
		int sourceOrdinal = 0;
		for(Ref source : List.of(left, right, foreign))
			full.samePoolRealizations(source, sourceInputs, pool,
				new DurableAnchorKey("source-second-" + sourceOrdinal++, FType.FULL, pool.partitions()));
		Ref owner = full.binary("owner", OpOp2.PLUS, left, right, false);
		CandidateRuleFact template = full.fact(owner, List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = template.allowedEmissionFacts().get(0);
		List<CandidateRealizationInputBinding> leftAxis = productAxis(full, left, sourceInputs, 0);
		List<CandidateRealizationInputBinding> rightAxis = productAxis(full, right, sourceInputs, 1);
		List<List<CandidateRealizationInputBinding>> axes = List.of(leftAxis, rightAxis);
		Object baseline = continuityProjection(List.of(productFact(template, emission,
			productClauses(owner.key, pool, axes, pool, true))));

		List<List<List<CandidateRealizationInputBinding>>> distinctAxes = List.of(
			List.of(List.of(leftAxis.get(0)), rightAxis),
			List.of(leftAxis, List.of(rightAxis.get(1))),
			List.of(repositionAxis(leftAxis, 1), repositionAxis(rightAxis, 2)),
			List.of(productAxis(full, foreign, sourceInputs, 0), rightAxis));
		for(List<List<CandidateRealizationInputBinding>> candidateAxes : distinctAxes)
			Assert.assertNotEquals(baseline, continuityProjection(List.of(productFact(template, emission,
				productClauses(owner.key, pool, candidateAxes, pool, true)))));

		CompiledHopKey twin = new CompiledHopKey(left.key.programFingerprint(),
			left.key.functionNamespace(), left.key.callSitePath(), left.key.recompileContext(),
			left.key.controlRegion(), left.key.emittedHopInstance(), left.key.canonicalSourceOrigin());
		Assert.assertEquals(left.key, twin);
		Assert.assertNotSame(left.key, twin);
		List<CandidateRealizationInputBinding> twinAxis = leftAxis.stream().map(binding ->
			CandidateRealizationInputBinding.direct(binding.inputPosition(),
				new CandidateRealizationReference(new CandidateRuleKey(twin, sourceInputs),
					binding.source().realization()))).toList();
		Assert.assertNotEquals("equal-but-foreign source identity is not projection authority", baseline,
			continuityProjection(List.of(productFact(template, emission,
				productClauses(owner.key, pool, List.of(twinAxis, rightAxis), pool, true)))));

		DurableAnchorKey otherPool = anchor(FType.FULL, "worker2:8002", 0, 50);
		Assert.assertNotEquals("clause witness is projection authority", baseline,
			continuityProjection(List.of(productFact(template, emission,
				productClauses(owner.key, pool, axes, otherPool, true)))));
		Assert.assertNotEquals("clause exactness is projection authority", baseline,
			continuityProjection(List.of(productFact(template, emission,
				productClauses(owner.key, pool, axes, pool, false)))));

		CandidateEmissionRealization baselineRealization = productFact(template, emission,
			productClauses(owner.key, pool, axes, pool, true)).allowedEmissionFacts().get(0)
			.realizations().get(0);
		CandidateRuleFact differentKeyFact = productFact(template, emission,
			new CandidateEmissionRealization(PlacementIdentity.PlacementRealizationKey.nativeLineage(
				emission.emissionState(), "different-product-key"),
				baselineRealization.supportClauses()));
		Assert.assertNotEquals("realization identity remains projection authority", baseline,
			continuityProjection(List.of(differentKeyFact)));
		for(CandidateRuleFact fact : List.of(template, differentKeyFact))
			for(CandidateEmissionRealization realization : fact.allowedEmissionFacts().get(0).realizations())
				if(realization.supportClauses() instanceof NativeContinuitySupportClauses relation)
					Assert.assertEquals(0, relation.materializedHandleCount());
	}

	@Test
	public void acyclicPruningRetainsDeadBranchInRevisionInvalidationFootprint() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref dead = full.read("dead");
		Ref choice = full.logicalRead("choice");
		full.inheritAnchor(choice, ground.anchor);
		full.reaching.put(choice.key, List.of(dead.key));
		Ref root = full.unary("root", OpOp1.ABS, choice, false);
		CandidateRealizationReference reference = full.reference(root,
			List.of(CandidateInputState.present(FType.FULL)));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity firstRevision = full.resolver(metrics, 8, 128);

		List<NativePlacementContinuity.NativeContinuityProof> expected =
			firstRevision.proveCandidateAlternatives(reference, ground.anchor);
		Assert.assertFalse("the grounded sibling keeps the root viable: " + metrics.snapshot(),
			expected.isEmpty());
		Assert.assertTrue("the dead sibling must be removed by the acyclic pass",
			metrics.snapshot().acyclicAlternativesRemoved() > 0);
		long graphBuilds = metrics.snapshot().proofGraphsBuilt();

		full.candidateLogicalRead(dead);
		List<CandidateRuleFact> changedFacts = List.copyOf(full.candidates);
		NativePlacementContinuity invalidated = firstRevision.nextRevisionWithCompleteCandidateDelta(
			changedFacts, identitySet(dead.key));
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			invalidated.proveCandidateAlternatives(reference, ground.anchor);
		Assert.assertEquals("dead side-branch invalidation cannot change the surviving proof",
			expected, actual);
		Assert.assertEquals(firstRevision.nextRevision(changedFacts)
			.proveCandidateAlternatives(reference, ground.anchor), actual);
		Assert.assertEquals(new NativePlacementContinuity(full.nodes, full.origins,
			changedFacts, full.edges, full.reaching, Set.of(), full.privacy)
			.proveCandidateAlternatives(reference, ground.anchor), actual);
		Assert.assertTrue("pruning must retain the dead occurrence in the memo footprint",
			metrics.snapshot().proofGraphsBuilt() > graphBuilds);
	}

	@Test
	public void zeroTopologyBudgetRecomputesWithoutChangingProofs() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref source = full.unary("source", OpOp1.LOG, seed, false);
		CandidateRealizationReference reference = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			full.resolver().proveCandidateAlternatives(reference, seed.anchor);
		String entriesProperty = "sysds.fedplanner.continuityTopology.maxEntries";
		String priorEntries = System.getProperty(entriesProperty);
		try {
			System.setProperty(entriesProperty, "0");
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = full.resolver(metrics, 0, 0);
			Assert.assertEquals(expected, resolver.proveCandidateAlternatives(reference, seed.anchor));
			Assert.assertEquals(expected, resolver.proveCandidateAlternatives(reference, seed.anchor));
			Assert.assertTrue(metrics.snapshot().topologyCacheBypasses() > 0);
			Assert.assertEquals(0, metrics.snapshot().topologyCacheEntries());
			Assert.assertEquals(0, metrics.snapshot().topologyExpansionHits());
		}
		finally {
			if(priorEntries == null)
				System.clearProperty(entriesProperty);
			else
				System.setProperty(entriesProperty, priorEntries);
		}
	}

	@Test
	public void candidateSpecificProofRequiresEveryAndDependencyToBeCompatible() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = full.logicalRead("cycleA");
		Ref cycleB = full.logicalRead("cycleB");
		Ref otherEntry = full.source("otherEntry", anchor(FType.FULL, "worker2:8002", 0, 50));
		full.reaching.put(cycleA.key, List.of(otherEntry.key, cycleB.key));
		full.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref product = full.binaryWithoutCandidate("product", OpOp2.PLUS, seed, cycleA);
		List<CandidateInputState> selected = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		full.additionalCandidate(product, selected);

		Assert.assertNull("one compatible sibling cannot discharge an incompatible loop entry",
			full.resolver().proveCandidate(full.reference(product, selected), seed.anchor));
	}

	@Test
	public void unchangedOwnerHintPreservesIncompatibleLoopEntryRejectionAgainstBothReferences() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = full.logicalRead("cycleA");
		Ref cycleB = full.logicalRead("cycleB");
		Ref otherEntry = full.source("otherEntry", anchor(FType.FULL, "worker2:8002", 0, 50));
		full.reaching.put(cycleA.key, List.of(otherEntry.key, cycleB.key));
		full.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref product = full.binaryWithoutCandidate("product", OpOp2.PLUS, seed, cycleA);
		List<CandidateInputState> selected = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		full.additionalCandidate(product, selected);
		CandidateRealizationReference reference = full.reference(product, selected);
		NativePlacementContinuity first = full.resolver();
		Assert.assertTrue(first.proveCandidateAlternatives(reference, seed.anchor).isEmpty());

		List<CandidateRuleFact> equalNewFacts = List.copyOf(full.candidates);
		NativePlacementContinuity hinted = first
			.nextRevisionWithCompleteCandidateDelta(equalNewFacts, Set.of());
		NativePlacementContinuity conservative = first.nextRevision(equalNewFacts);
		NativePlacementContinuity fresh = new NativePlacementContinuity(full.nodes, full.origins,
			equalNewFacts, full.edges, full.reaching, Set.of(), full.privacy);
		List<NativePlacementContinuity.NativeContinuityProof> actual =
			hinted.proveCandidateAlternatives(reference, seed.anchor);
		Assert.assertEquals(conservative.proveCandidateAlternatives(reference, seed.anchor), actual);
		Assert.assertEquals(fresh.proveCandidateAlternatives(reference, seed.anchor), actual);
		Assert.assertTrue(actual.isEmpty());
		Assert.assertTrue(hinted.revisionComparisonSnapshot().hintedOwnersBypassed() > 0);
		Assert.assertEquals(0, hinted.revisionComparisonSnapshot().ownersCompared());
	}

	@Test
	public void transientReplayPreservesReceiptProofsWithStableEndpointCertificate() {
		Fixture full = new Fixture(FType.FULL);
		Ref left = full.source("left", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref right = full.source("right", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.binary("producer", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> producerInputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		DurableAnchorKey producerA = new DurableAnchorKey("producer-a", FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		DurableAnchorKey producerB = new DurableAnchorKey("producer-b", FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		DurableAnchorKey producerC = new DurableAnchorKey("producer-c", FType.FULL,
			List.of(partition("worker1:8001", 0, 50)));
		full.samePoolRealizations(producer, producerInputs, producerA, producerB);
		Ref source = full.unary("source", OpOp1.LOG, producer, false);
		CandidateRealizationReference sourceRealization = full.reference(source,
			List.of(CandidateInputState.present(FType.FULL)));
		List<CandidateRuleFact> twoFacts = List.copyOf(full.candidates);
		List<NativePlacementContinuity.NativeContinuityProof> twoNative = full.resolver()
			.proveCandidateAlternatives(sourceRealization, left.anchor);
		assertDistinctImmediateSources(twoNative, 2);
		Map<CandidateRealizationReference,String> twoReceiptProofs = proofSignaturesBySelectedInput(twoNative);
		PlacementProofKey valueEvidence = new PlacementProofKey(
			PlacementProofKind.VALUE_IDENTITY, source.key, "source-value-evidence");
		List<PlacementProofKey> commonProofs = List.of(valueEvidence);
		List<PlacementAnalysis.TransientCompatibilityProof> twoCertificates =
			transientCertificates(full, source, sourceRealization, left.anchor, commonProofs);
		Assert.assertEquals("equivalent selected-input proofs share one endpoint certificate", 1,
			twoCertificates.size());
		Assert.assertTrue(twoCertificates.stream().allMatch(
			certificate -> certificate.provesNativeContinuity(source.key, source.key)));
		Assert.assertTrue("each exact certificate retains the actual seed witness",
			twoCertificates.stream().allMatch(
				certificate -> certificate.nativeWorkerPoolWitness() == left.anchor));
		Assert.assertTrue("common value evidence remains on every projected certificate",
			twoCertificates.stream().allMatch(
				certificate -> certificate.dependencies().contains(valueEvidence)));
		Assert.assertEquals("projection keeps every typed witness/precision class",
			proofClasses(twoNative, left.anchor), certificateClasses(twoCertificates));
		String stableCertificate = twoCertificates.get(0).normalizedSignature();
		assertCertificateIdentities(twoCertificates, stableCertificate);
		Assert.assertEquals("Native proof and certificate projection must not mutate candidate supports",
			twoFacts, full.candidates);

		DurableAnchorKey differentGeometry = new DurableAnchorKey("same-worker-different-range",
			FType.FULL, List.of(partition("worker1:8001", 100, 150)));
		List<PlacementAnalysis.TransientCompatibilityProof> geometryCertificates =
			transientCertificates(full, source, sourceRealization, differentGeometry, commonProofs);
		Assert.assertEquals(1, geometryCertificates.size());
		Assert.assertNotEquals("complete seed geometry remains part of the certificate",
			stableCertificate, geometryCertificates.get(0).normalizedSignature());
		assertCertificateIdentities(geometryCertificates,
			geometryCertificates.get(0).normalizedSignature());
		DurableAnchorKey differentWorker = new DurableAnchorKey("different-worker", FType.FULL,
			List.of(partition("worker2:8002", 0, 50)));
		Assert.assertTrue("a certificate cannot merge an unproved worker pool",
			transientCertificates(full, source, sourceRealization, differentWorker, commonProofs).isEmpty());

		full.samePoolRealizations(producer, producerInputs, producerA, producerB, producerC);
		List<CandidateRuleFact> threeFacts = List.copyOf(full.candidates);
		List<NativePlacementContinuity.NativeContinuityProof> threeNative = full.resolver()
			.proveCandidateAlternatives(sourceRealization, left.anchor);
		assertDistinctImmediateSources(threeNative, 3);
		Map<CandidateRealizationReference,String> threeReceiptProofs = proofSignaturesBySelectedInput(threeNative);
		Assert.assertTrue("adding a receipt must preserve the prior selected-input proof identities",
			threeReceiptProofs.entrySet().containsAll(twoReceiptProofs.entrySet()));
		assertCertificateIdentities(transientCertificates(
			full, source, sourceRealization, left.anchor, commonProofs), stableCertificate);
		Assert.assertEquals(threeFacts, full.candidates);

		full.samePoolRealizations(producer, producerInputs, producerB, producerC);
		List<CandidateRuleFact> remainingFacts = List.copyOf(full.candidates);
		List<NativePlacementContinuity.NativeContinuityProof> remainingNative = full.resolver()
			.proveCandidateAlternatives(sourceRealization, left.anchor);
		assertDistinctImmediateSources(remainingNative, 2);
		Map<CandidateRealizationReference,String> remainingReceiptProofs =
			proofSignaturesBySelectedInput(remainingNative);
		Assert.assertTrue("withdrawal must preserve every surviving selected-input proof identity",
			threeReceiptProofs.entrySet().containsAll(remainingReceiptProofs.entrySet()));
		Assert.assertFalse("withdrawal must remove one selected input receipt",
			remainingReceiptProofs.keySet().containsAll(twoReceiptProofs.keySet()));
		assertCertificateIdentities(transientCertificates(
			full, source, sourceRealization, left.anchor, commonProofs), stableCertificate);
		Assert.assertEquals(remainingFacts, full.candidates);

		CandidateRuleFact remainingProducer = full.fact(producer, producerInputs);
		full.candidates.remove(remainingProducer);
		Assert.assertTrue("withdrawing every execution path withdraws the certificate",
			transientCertificates(full, source, sourceRealization, left.anchor, commonProofs).isEmpty());
		full.candidates.add(remainingProducer);
		Assert.assertEquals("restoring paths restores the same selected input proofs",
			remainingReceiptProofs, proofSignaturesBySelectedInput(full.resolver()
				.proveCandidateAlternatives(sourceRealization, left.anchor)));
		assertCertificateIdentities(transientCertificates(
			full, source, sourceRealization, left.anchor, commonProofs), stableCertificate);

		Fixture dynamic = new Fixture(FType.ROW);
		Ref dynamicSeed = dynamic.source("dynamicSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref reverse = dynamic.reorg("reverse", ReOrgOp.REV, dynamicSeed, false);
		CandidateRealizationReference reverseReference = dynamic.reference(reverse,
			List.of(CandidateInputState.present(FType.ROW)));
		List<NativePlacementContinuity.NativeContinuityProof> dynamicNative = dynamic.resolver()
			.proveCandidateAlternatives(reverseReference, dynamicSeed.anchor);
		List<PlacementAnalysis.TransientCompatibilityProof> dynamicCertificates =
			transientCertificates(dynamic, reverse, reverseReference, dynamicSeed.anchor, List.of());
		Assert.assertFalse(dynamicNative.isEmpty());
		Assert.assertTrue(dynamicNative.stream().noneMatch(
			NativePlacementContinuity.NativeContinuityProof::exactPartitionRanges));
		Assert.assertEquals("dynamic projection keeps its typed witness/precision classes",
			proofClasses(dynamicNative, dynamicSeed.anchor), certificateClasses(dynamicCertificates));

		List<CandidateInputState> sourceInputs =
			List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(source, sourceInputs,
			new DurableAnchorKey("source-a", FType.FULL,
				List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("source-b", FType.FULL,
				List.of(partition("worker1:8001", 0, 50))));
		CandidateRuleFact sourceFact = full.fact(source, sourceInputs);
		List<CandidateEmissionRealization> sourceAlternatives =
			sourceFact.allowedEmissionFacts().get(0).realizations();
		CandidateRealizationReference sourceA = CandidateRealizationReference.of(
			sourceFact.key(), sourceAlternatives.get(0));
		CandidateRealizationReference sourceB = CandidateRealizationReference.of(
			sourceFact.key(), sourceAlternatives.get(1));
		String sourceACertificate = transientCertificates(
			full, source, sourceA, left.anchor, commonProofs).get(0).normalizedSignature();
		String sourceBCertificate = transientCertificates(
			full, source, sourceB, left.anchor, commonProofs).get(0).normalizedSignature();
		Assert.assertNotEquals("distinct exact source references remain distinct relations",
			sourceACertificate, sourceBCertificate);
	}

	private static void assertDistinctImmediateSources(
		List<NativePlacementContinuity.NativeContinuityProof> proofs, int expected) {
		Assert.assertEquals("Native continuity keeps every complete execution receipt", expected,
			proofs.size());
		Assert.assertTrue(proofs.stream().allMatch(proof -> proof.immediateBindings().size() == 1));
		Assert.assertEquals(expected, proofs.stream()
			.map(proof -> proof.immediateBindings().get(0).source()).distinct().count());
	}

	private static Map<CandidateRealizationReference,String> proofSignaturesBySelectedInput(
		List<NativePlacementContinuity.NativeContinuityProof> proofs) {
		return proofs.stream().collect(java.util.stream.Collectors.toMap(
			proof -> proof.immediateBindings().get(0).source(),
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature));
	}

	private static void assertCertificateIdentities(
		List<PlacementAnalysis.TransientCompatibilityProof> certificates,
		String expectedIdentity) {
		Assert.assertEquals("equivalent input proofs must coalesce to one endpoint certificate",
			1, certificates.size());
		Assert.assertEquals("same endpoint proofs must retain their stable certificate identity",
			Set.of(expectedIdentity), certificates.stream()
				.map(PlacementAnalysis.TransientCompatibilityProof::normalizedSignature)
				.collect(java.util.stream.Collectors.toSet()));
	}

	private static List<PlacementAnalysis.TransientCompatibilityProof> transientCertificates(
		Fixture fixture, Ref source, CandidateRealizationReference sourceRealization,
		DurableAnchorKey seed, List<PlacementProofKey> commonProofs) {
		return PlacementRelationClosure.nativeTransientCompatibilityProofs(source.key,
			sourceRealization, seed, fixture.resolver(), commonProofs);
	}

	private static Set<String> proofClasses(
		List<NativePlacementContinuity.NativeContinuityProof> proofs, DurableAnchorKey seed) {
		return proofs.stream().map(proof -> witnessClass(
			proof.exactPartitionRanges() ? seed : proof.outputWorkerPoolWitness(),
			proof.exactPartitionRanges()))
			.collect(java.util.stream.Collectors.toSet());
	}

	private static Set<String> certificateClasses(
		List<PlacementAnalysis.TransientCompatibilityProof> certificates) {
		return certificates.stream().map(proof -> witnessClass(
			proof.nativeWorkerPoolWitness(), proof.nativeWorkerPoolLayoutExact()))
			.collect(java.util.stream.Collectors.toSet());
	}

	private static String witnessClass(DurableAnchorKey witness, boolean exact) {
		return witness.fType() + "|" + witness.partitions() + "|exact=" + exact;
	}

	@Test
	public void repeatedProducerInputChoosesOneExactRealizationAtBothPositions() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, oneInput,
			new DurableAnchorKey("producer-a", FType.FULL, List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("producer-b", FType.FULL, List.of(partition("worker1:8001", 0, 50))));
		Ref sum = full.binary("sum", OpOp2.PLUS, producer, producer, false);
		List<CandidateInputState> twoInputs = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<NativePlacementContinuity.NativeContinuityProof> proofs =
			full.resolver(metrics, 0, 0).proveCandidateAlternatives(full.reference(sum, twoInputs), seed.anchor);

		Assert.assertEquals("one owner cannot choose A and B simultaneously", 2, proofs.size());
		for(var proof : proofs) {
			Assert.assertEquals(2, proof.immediateBindings().size());
			Assert.assertEquals(0, proof.immediateBindings().get(0).inputPosition());
			Assert.assertEquals(1, proof.immediateBindings().get(1).inputPosition());
			Assert.assertEquals(proof.immediateBindings().get(0).source(),
				proof.immediateBindings().get(1).source());
		}
		Assert.assertEquals("inconsistent product leaves must never be materialized", 2,
			metrics.snapshot().supportLeaves());
		Assert.assertEquals(2, metrics.snapshot().supportConflictPrefixes());
	}

	@Test
	public void nativeRectangularTopologyUsesAxisGatesWithoutChangingExactProofs() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("gate-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Ref left = full.unary("gate-left", OpOp1.LOG, seed, false);
		Ref right = full.unary("gate-right", OpOp1.LOG, seed, false);
		DurableAnchorKey leftA = new DurableAnchorKey(
			"gate-left-a", FType.FULL, pool.partitions());
		DurableAnchorKey leftB = new DurableAnchorKey(
			"gate-left-b", FType.FULL, pool.partitions());
		DurableAnchorKey leftC = new DurableAnchorKey(
			"gate-left-c", FType.FULL, pool.partitions());
		DurableAnchorKey leftD = new DurableAnchorKey(
			"gate-left-d", FType.FULL, pool.partitions());
		full.samePoolRealizations(left, unary, leftA, leftB, leftC, leftD);
		full.samePoolRealizations(right, unary,
			new DurableAnchorKey("gate-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("gate-right-b", FType.FULL, pool.partitions()),
			new DurableAnchorKey("gate-right-c", FType.FULL, pool.partitions()),
			new DurableAnchorKey("gate-right-d", FType.FULL, pool.partitions()),
			new DurableAnchorKey("gate-right-e", FType.FULL, pool.partitions()));
		Ref consumer = full.binary("gate-consumer", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		CandidateRealizationReference staging = full.reference(consumer, binary);
		NativePlacementContinuity.CandidateSupportResult generated =
			full.resolver(new SearchSpaceMetrics(), 0, 0).proveCandidateSupport(staging, pool);
		Assert.assertNotNull(generated.supportProduct());
		Assert.assertEquals(20, generated.proofs().size());
		NativeContinuitySupportClauses explicitRelation = new NativeContinuitySupportClauses(
			consumer.key, generated.supportProduct(), pool, true);
		CandidateRealizationReference explicitPublished = full.withClauses(
			consumer, binary, List.copyOf(explicitRelation));
		SearchSpaceMetrics explicitMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult explicit =
			full.resolver(explicitMetrics, 0, 0).proveCandidateSupport(explicitPublished, pool);
		NativeContinuitySupportClauses ambiguousRelation = new NativeContinuitySupportClauses(
			consumer.key, generated.supportProduct(), pool, true);
		CandidateRealizationReference ambiguousPublished = full.withClauses(
			consumer, binary, ambiguousRelation);
		CandidateRuleFact duplicatedAuthority = full.fact(consumer, binary);
		full.candidates.add(duplicatedAuthority);
		NativePlacementContinuity.CandidateSupportResult ambiguous =
			full.resolver(new SearchSpaceMetrics(), 0, 0)
				.proveCandidateSupport(ambiguousPublished, pool);
		Assert.assertEquals("ambiguous matching rows use the exact legacy topology",
			explicit.proofs().stream()
				.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			ambiguous.proofs().stream()
				.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		Assert.assertEquals(20, ambiguousRelation.materializedHandleCount());
		full.candidates.remove(full.candidates.size() - 1);

		NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
			consumer.key, generated.supportProduct(), pool, true);
		CandidateRealizationReference published = full.withClauses(consumer, binary, relation);
		Assert.assertEquals(PlacementIdentity.PlacementLayoutKind.NATIVE_LINEAGE,
			published.realization().layoutKind());
		Assert.assertSame(relation, full.fact(consumer, binary).allowedEmissionFacts().get(0)
			.realizations().get(0).supportClauses());
		Assert.assertEquals(0, relation.materializedHandleCount());
		SearchSpaceMetrics factoredMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult factored =
			full.resolver(factoredMetrics, 0, 0).proveCandidateSupport(published, pool);
		Assert.assertEquals("proof graph builds from one representative member",
			1, relation.materializedHandleCount());

		Assert.assertEquals(explicit.proofs().stream()
			.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			factored.proofs().stream()
				.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		Assert.assertEquals("topology construction needs one authoritative member only: "
			+ factoredMetrics.snapshot(),
			1, relation.materializedHandleCount());
		Assert.assertEquals("logical support cardinality is unchanged", 20, relation.size());
		Assert.assertTrue("axis gates visit O(sum of domains) graph alternatives",
			factoredMetrics.snapshot().proofAlternativesBuilt()
				< explicitMetrics.snapshot().proofAlternativesBuilt());
		Assert.assertTrue("axis gates visit fewer graph dependency edges",
			factoredMetrics.snapshot().proofDependencyEdgesBuilt()
				< explicitMetrics.snapshot().proofDependencyEdgesBuilt());
		Assert.assertTrue("the native root never enters the flat topology row cache",
			factoredMetrics.snapshot().topologyRowsBuilt()
				< explicitMetrics.snapshot().topologyRowsBuilt());
		Assert.assertEquals(Set.of(consumer.key, left.key, right.key, seed.key),
			factored.dependencyOccurrences());

		full.samePoolRealizations(left, unary, leftA);
		NativePlacementContinuity.CandidateSupportResult oneDeadOption =
			full.resolver(new SearchSpaceMetrics(), 0, 0).proveCandidateSupport(published, pool);
		Assert.assertEquals("one dead source removes only its gate option", 5,
			oneDeadOption.proofs().size());
		Assert.assertEquals(1, relation.materializedHandleCount());
		full.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == left.key);
		Assert.assertTrue("an empty source axis kills the consumer AND gate",
			full.resolver(new SearchSpaceMetrics(), 0, 0)
				.proveCandidateSupport(published, pool).proofs().isEmpty());
		Assert.assertEquals(1, relation.materializedHandleCount());
	}

	@Test
	public void randomizedNativeAxisGatesMatchExplicitRectanglesAfterSourceWithdrawal() {
		java.util.Random random = new java.util.Random(0x6e61746976654cL);
		for(int trial = 0; trial < 20; trial++) {
			Fixture full = new Fixture(FType.FULL);
			DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
			Ref seed = full.source(String.format("random-gate-seed-%02d", trial), pool);
			List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
			int axisCount = 1 + random.nextInt(4);
			Ref[] producers = new Ref[axisCount];
			DurableAnchorKey[][] choices = new DurableAnchorKey[axisCount][];
			int logicalSize = 1;
			for(int axis = 0; axis < axisCount; axis++) {
				producers[axis] = full.unary(String.format("random-gate-%02d-axis-%02d",
					trial, axis), OpOp1.LOG, seed, false);
				int width = 1 + random.nextInt(4);
				logicalSize *= width;
				choices[axis] = new DurableAnchorKey[width];
				for(int option = 0; option < width; option++)
					choices[axis][option] = new DurableAnchorKey(String.format(
						"random-gate-%02d-axis-%02d-option-%02d", trial, axis, option),
						FType.FULL, pool.partitions());
				full.samePoolRealizations(producers[axis], unary, choices[axis]);
			}

			Ref consumer = full.nary(String.format("random-gate-%02d-consumer", trial),
				OpOpN.MULT, false, producers);
			List<CandidateInputState> inputs = java.util.Collections.nCopies(
				axisCount, CandidateInputState.present(FType.FULL));
			NativePlacementContinuity.CandidateSupportResult generated = full.resolver()
				.proveCandidateSupport(full.reference(consumer, inputs), pool);
			Assert.assertNotNull("trial " + trial, generated.supportProduct());
			boolean exact = (trial & 1) == 0;
			NativePlacementContinuity.NativeSupportProduct product = exact
				? generated.supportProduct()
				: NativePlacementContinuity.NativeSupportProduct.tryCreate(
					pool, pool, false, generated.supportProduct().axes());
			Assert.assertNotNull("dynamic trial " + trial, product);
			Assert.assertEquals(logicalSize, product.size());

			NativeContinuitySupportClauses explicitRelation =
				new NativeContinuitySupportClauses(consumer.key, product, pool, exact);
			CandidateRealizationReference explicitPublished = full.withClauses(
				consumer, inputs, List.copyOf(explicitRelation));

			boolean empty = false;
			for(int axis = 0; axis < axisCount; axis++) {
				int keep = trial % 5 == 0 && axis == axisCount - 1
					? 0 : random.nextInt(choices[axis].length + 1);
				if(keep == 0) {
					empty = true;
					CompiledHopKey owner = producers[axis].key;
					full.candidates.removeIf(candidate ->
						candidate.key().parentOccurrence() == owner);
				}
				else
					full.samePoolRealizations(producers[axis], unary,
						java.util.Arrays.copyOf(choices[axis], keep));
			}

			Ref outer = full.unary("random-unpinned-" + trial, OpOp1.LOG, consumer, false);
			CandidateRuleFact outerFact = full.fact(outer, unary);
			CandidateEmissionFact outerEmission = outerFact.allowedEmissionFacts().get(0);
			CandidateRealizationReference proposed = CandidateRealizationReference.of(outerFact.key(),
				CandidateEmissionRealization.nativeLineage(outerEmission.emissionState(),
					"random-proposed-" + trial, List.of(), List.of()));
			NativePlacementContinuity.CandidateSupportResult explicitOuter = full.resolver()
				.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
			NativePlacementContinuity.CandidateSupportResult explicit = full.resolver()
				.proveCandidateSupport(explicitPublished, pool);
			NativeContinuitySupportClauses factoredRelation =
				new NativeContinuitySupportClauses(consumer.key, product, pool, exact);
			CandidateRealizationReference factoredPublished = full.withClauses(
				consumer, inputs, factoredRelation);
			NativePlacementContinuity.CandidateSupportResult factored = full.resolver()
				.proveCandidateSupport(factoredPublished, pool);
			NativePlacementContinuity.CandidateSupportResult factoredOuter = full.resolver()
				.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
			Assert.assertEquals("unpinned proof parity at trial " + trial,
				explicitOuter.proofs().stream().map(
					NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
				factoredOuter.proofs().stream().map(
					NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
			assertIdentitySetEquals(explicitOuter.dependencyOccurrences(), factoredOuter.dependencyOccurrences());

			Assert.assertEquals("ordered proof parity at trial " + trial,
				explicit.proofs().stream().map(
					NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
				factored.proofs().stream().map(
					NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
			assertIdentitySetEquals(explicit.dependencyOccurrences(),
				factored.dependencyOccurrences());
			Assert.assertEquals("empty-axis parity at trial " + trial,
				empty, factored.proofs().isEmpty());
			Assert.assertTrue("a factored proof graph materializes at most one member at trial "
				+ trial, factoredRelation.materializedHandleCount() <= 1);
			Assert.assertEquals("partition-range proof mode is preserved at trial " + trial,
				explicit.proofs().stream().map(
					NativePlacementContinuity.NativeContinuityProof::exactPartitionRanges).toList(),
				factored.proofs().stream().map(
					NativePlacementContinuity.NativeContinuityProof::exactPartitionRanges).toList());
		}
	}

	@Test
	public void generatedRootFactorsAnUnpinnedNativeChildWithoutChangingProofs() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("unpinned-gate-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Ref left = full.unary("unpinned-gate-left", OpOp1.LOG, seed, false);
		Ref right = full.unary("unpinned-gate-right", OpOp1.LOG, seed, false);
		DurableAnchorKey[] leftChoices = java.util.stream.IntStream.range(0, 4)
			.mapToObj(option -> new DurableAnchorKey("unpinned-gate-left-" + option,
				FType.FULL, pool.partitions())).toArray(DurableAnchorKey[]::new);
		DurableAnchorKey[] rightChoices = java.util.stream.IntStream.range(0, 5)
			.mapToObj(option -> new DurableAnchorKey("unpinned-gate-right-" + option,
				FType.FULL, pool.partitions())).toArray(DurableAnchorKey[]::new);
		full.samePoolRealizations(left, unary, leftChoices);
		full.samePoolRealizations(right, unary, rightChoices);
		Ref child = full.binary("unpinned-gate-child", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		NativePlacementContinuity.CandidateSupportResult generatedChild = full.resolver()
			.proveCandidateSupport(full.reference(child, binary), pool);
		Assert.assertNotNull(generatedChild.supportProduct());

		Ref outer = full.unary("unpinned-gate-outer", OpOp1.LOG, child, false);
		CandidateRuleFact outerFact = full.fact(outer, unary);
		CandidateEmissionFact outerEmission = outerFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(outerFact.key(),
			CandidateEmissionRealization.nativeLineage(
				outerEmission.emissionState(), "unpinned-generated-root", List.of(), List.of()));

		NativeContinuitySupportClauses explicitRelation = new NativeContinuitySupportClauses(
			child.key, generatedChild.supportProduct(), pool, true);
		full.withClauses(child, binary, List.copyOf(explicitRelation));
		SearchSpaceMetrics explicitMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult explicit =
			full.resolver(explicitMetrics, 0, 0).proveGeneratedCandidateSupport(
				outerFact, outerEmission, proposed, pool);

		NativeContinuitySupportClauses factoredRelation = new NativeContinuitySupportClauses(
			child.key, generatedChild.supportProduct(), pool, true);
		full.withClauses(child, binary, factoredRelation);
		SearchSpaceMetrics factoredMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult factored =
			full.resolver(factoredMetrics, 0, 0).proveGeneratedCandidateSupport(
				outerFact, outerEmission, proposed, pool);

		Assert.assertEquals(explicit.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			factored.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		assertIdentitySetEquals(explicit.dependencyOccurrences(), factored.dependencyOccurrences());
		Assert.assertEquals(Set.of(outer.key, child.key, left.key, right.key, seed.key),
			factored.dependencyOccurrences());
		Assert.assertEquals("the unpinned native child uses one authoritative member",
			1, factoredRelation.materializedHandleCount());
		Assert.assertTrue(factoredMetrics.snapshot().proofAlternativesBuilt()
			< explicitMetrics.snapshot().proofAlternativesBuilt());
		Assert.assertTrue(factoredMetrics.snapshot().proofDependencyEdgesBuilt()
			< explicitMetrics.snapshot().proofDependencyEdgesBuilt());

		full.samePoolRealizations(left, unary, leftChoices[0]);
		full.withClauses(child, binary, List.copyOf(explicitRelation));
		NativePlacementContinuity.CandidateSupportResult narrowedExplicit = full.resolver()
			.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
		NativeContinuitySupportClauses narrowedRelation = new NativeContinuitySupportClauses(
			child.key, generatedChild.supportProduct(), pool, true);
		full.withClauses(child, binary, narrowedRelation);
		NativePlacementContinuity.CandidateSupportResult narrowed = full.resolver()
			.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
		Assert.assertEquals(narrowedExplicit.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			narrowed.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		Assert.assertEquals(1, narrowedRelation.materializedHandleCount());
		full.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == right.key);
		Assert.assertTrue(full.resolver().proveGeneratedCandidateSupport(
			outerFact, outerEmission, proposed, pool).proofs().isEmpty());
		Assert.assertEquals(1, narrowedRelation.materializedHandleCount());
	}

	@Test
	public void unpinnedNativeChildSummaryInvalidatesOnDeepAxisWithdrawalAndRestoration() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("summary-gate-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Ref left = full.unary("summary-gate-left", OpOp1.LOG, seed, false);
		Ref right = full.unary("summary-gate-right", OpOp1.LOG, seed, false);
		full.samePoolRealizations(left, unary,
			new DurableAnchorKey("summary-left-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("summary-left-b", FType.FULL, pool.partitions()));
		full.samePoolRealizations(right, unary,
			new DurableAnchorKey("summary-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("summary-right-b", FType.FULL, pool.partitions()));
		CandidateRuleFact leftFact = full.fact(left, unary);
		Ref child = full.binary("summary-gate-child", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		NativePlacementContinuity.NativeSupportProduct product = full.resolver(null, 0, 0)
			.proveCandidateSupport(full.reference(child, binary), pool).supportProduct();
		Assert.assertNotNull(product);
		Assert.assertEquals(4, product.size());
		full.withClauses(child, binary,
			new NativeContinuitySupportClauses(child.key, product, pool, true));
		Ref firstRoot = full.unary("summary-gate-first", OpOp1.EXP, child, false);
		Ref secondRoot = full.unary("summary-gate-second", OpOp1.SQRT, child, false);
		CandidateRuleFact firstFact = full.fact(firstRoot, unary);
		CandidateRuleFact secondFact = full.fact(secondRoot, unary);
		CandidateEmissionFact firstEmission = firstFact.allowedEmissionFacts().get(0);
		CandidateEmissionFact secondEmission = secondFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference firstProposal = new CandidateRealizationReference(
			firstFact.key(), PlacementIdentity.PlacementRealizationKey.nativeLineage(
				firstEmission.emissionState(), "summary-first-proposal"));
		CandidateRealizationReference secondProposal = new CandidateRealizationReference(
			secondFact.key(), PlacementIdentity.PlacementRealizationKey.nativeLineage(
				secondEmission.emissionState(), "summary-second-proposal"));
		List<CandidateRuleFact> originalFacts = List.copyOf(full.candidates);
		java.util.function.Function<List<CandidateRuleFact>,NativePlacementContinuity> cold =
			facts -> new NativePlacementContinuity(full.nodes, full.origins, facts,
				full.edges, full.reaching, Set.of(), full.privacy, null, 0, 0);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity cached = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.CandidateSupportResult first = cached
			.proveGeneratedCandidateSupport(firstFact, firstEmission, firstProposal, pool);
		Assert.assertFalse(first.proofs().isEmpty());
		NativePlacementContinuity.CandidateSupportResult firstCold = cold.apply(originalFacts)
			.proveGeneratedCandidateSupport(firstFact, firstEmission, firstProposal, pool);
		Assert.assertEquals(firstCold.proofs(), first.proofs());
		assertIdentitySetEquals(firstCold.dependencyOccurrences(), first.dependencyOccurrences());
		Assert.assertTrue(first.dependencyOccurrences().stream().anyMatch(owner -> owner == left.key));
		long firstStates = metrics.snapshot().proofStatesBuilt();
		long reuseBefore = metrics.directWorkCount(
			SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSE_HITS);
		NativePlacementContinuity.CandidateSupportResult second = cached
			.proveGeneratedCandidateSupport(secondFact, secondEmission, secondProposal, pool);
		NativePlacementContinuity.CandidateSupportResult secondCold = cold.apply(originalFacts)
			.proveGeneratedCandidateSupport(secondFact, secondEmission, secondProposal, pool);
		Assert.assertFalse(second.proofs().isEmpty());
		Assert.assertEquals(secondCold.proofs(), second.proofs());
		assertIdentitySetEquals(secondCold.dependencyOccurrences(), second.dependencyOccurrences());
		Assert.assertTrue("a sibling generated root must reuse the unpinned child summary",
			metrics.directWorkCount(SearchSpaceMetrics.DirectWork.COMPONENT_SUMMARY_REUSE_HITS)
				> reuseBefore);
		Assert.assertTrue("summary reuse must avoid rebuilding transitive gate states",
			metrics.snapshot().proofStatesBuilt() - firstStates < firstStates);
		List<CandidateRuleFact> withdrawnFacts = originalFacts.stream()
			.filter(fact -> fact != leftFact).toList();
		long builtBeforeWithdrawal = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity withdrawn = cached.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, identitySet(left.key));
		NativePlacementContinuity.CandidateSupportResult negative = withdrawn
			.proveGeneratedCandidateSupport(secondFact, secondEmission, secondProposal, pool);
		NativePlacementContinuity.CandidateSupportResult negativeCold = cold.apply(withdrawnFacts)
			.proveGeneratedCandidateSupport(secondFact, secondEmission, secondProposal, pool);
		Assert.assertTrue("withdrawing the deep axis must invalidate the positive summary",
			negative.proofs().isEmpty());
		Assert.assertEquals(negativeCold.proofs(), negative.proofs());
		assertIdentitySetEquals(negativeCold.dependencyOccurrences(), negative.dependencyOccurrences());
		Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > builtBeforeWithdrawal);
		long builtBeforeRestoration = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			originalFacts, identitySet(left.key));
		NativePlacementContinuity.CandidateSupportResult restoredActual = restored
			.proveGeneratedCandidateSupport(secondFact, secondEmission, secondProposal, pool);
		NativePlacementContinuity.CandidateSupportResult restoredCold = cold.apply(originalFacts)
			.proveGeneratedCandidateSupport(secondFact, secondEmission, secondProposal, pool);
		Assert.assertFalse("restoration must invalidate the negative summary",
			restoredActual.proofs().isEmpty());
		Assert.assertEquals(restoredCold.proofs(), restoredActual.proofs());
		Assert.assertEquals(second.proofs(), restoredActual.proofs());
		assertIdentitySetEquals(restoredCold.dependencyOccurrences(), restoredActual.dependencyOccurrences());
		Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > builtBeforeRestoration);
	}

	@Test
	public void unpinnedNativeFamiliesKeepCanonicalOrsAndMixedAuthorityFallsBack() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("unpinned-family-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Ref left = full.unary("unpinned-family-left", OpOp1.LOG, seed, false);
		Ref right = full.unary("unpinned-family-right", OpOp1.LOG, seed, false);
		full.samePoolRealizations(left, unary,
			new DurableAnchorKey("unpinned-family-left-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("unpinned-family-left-b", FType.FULL, pool.partitions()));
		full.samePoolRealizations(right, unary,
			new DurableAnchorKey("unpinned-family-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("unpinned-family-right-b", FType.FULL, pool.partitions()),
			new DurableAnchorKey("unpinned-family-right-c", FType.FULL, pool.partitions()));
		Ref child = full.binary("unpinned-family-child", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		NativePlacementContinuity.NativeSupportProduct product = full.resolver()
			.proveCandidateSupport(full.reference(child, binary), pool).supportProduct();
		Assert.assertNotNull(product);
		CandidateRuleFact childFact = full.fact(child, binary);
		CandidateEmissionFact childEmission = childFact.allowedEmissionFacts().get(0);

		NativeContinuitySupportClauses firstRelation = new NativeContinuitySupportClauses(
			child.key, product, pool, true);
		NativeContinuitySupportClauses secondRelation = new NativeContinuitySupportClauses(
			child.key, product, pool, true);
		CandidateEmissionRealization first = new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				childEmission.emissionState(), "unpinned-family-first"), firstRelation);
		CandidateEmissionRealization second = new CandidateEmissionRealization(
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				childEmission.emissionState(), "unpinned-family-second"), secondRelation);

		Ref outer = full.unary("unpinned-family-outer", OpOp1.LOG, child, false);
		CandidateRuleFact outerFact = full.fact(outer, unary);
		CandidateEmissionFact outerEmission = outerFact.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(outerFact.key(),
			CandidateEmissionRealization.nativeLineage(
				outerEmission.emissionState(), "unpinned-family-output", List.of(), List.of()));

		replaceRealizations(full, childFact, childEmission, List.of(
			new CandidateEmissionRealization(first.key(), List.copyOf(firstRelation)),
			new CandidateEmissionRealization(second.key(), List.copyOf(secondRelation))));
		NativePlacementContinuity.CandidateSupportResult explicit = full.resolver()
			.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
		// The explicit reference materialized its own lists above. Fresh lazy relations
		// are required to measure work in the factorized query independently.
		firstRelation = new NativeContinuitySupportClauses(child.key, product, pool, true);
		secondRelation = new NativeContinuitySupportClauses(child.key, product, pool, true);
		first = new CandidateEmissionRealization(first.key(), firstRelation);
		second = new CandidateEmissionRealization(second.key(), secondRelation);
		replaceRealizations(full, full.fact(child, binary), childEmission, List.of(first, second));
		NativePlacementContinuity.CandidateSupportResult factored = full.resolver()
			.proveGeneratedCandidateSupport(outerFact, outerEmission, proposed, pool);
		Assert.assertEquals("distinct native realizations retain canonical OR order",
			explicit.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			factored.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		assertIdentitySetEquals(explicit.dependencyOccurrences(), factored.dependencyOccurrences());
		Assert.assertEquals(1, firstRelation.materializedHandleCount());
		Assert.assertEquals(1, secondRelation.materializedHandleCount());

		NativeContinuitySupportClauses duplicateRelation = new NativeContinuitySupportClauses(
			child.key, product, pool, true);
		CandidateEmissionRealization duplicate = new CandidateEmissionRealization(
			first.key(), duplicateRelation);
		CandidateRuleFact duplicated = replaceRealizations(full, full.fact(child, binary),
			childEmission, List.of(duplicate));
		full.candidates.add(duplicated);
		full.resolver().proveGeneratedCandidateSupport(
			outerFact, outerEmission, proposed, pool);
		Assert.assertEquals("ambiguous structural authority uses the full legacy topology",
			product.size(), duplicateRelation.materializedHandleCount());
		full.candidates.remove(full.candidates.size() - 1);

		NativeContinuitySupportClauses mixedRelation = new NativeContinuitySupportClauses(
			child.key, product, pool, true);
		CandidateEmissionRealization nativeMixed = new CandidateEmissionRealization(
			first.key(), mixedRelation);
		CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(
			childEmission.emissionState(), "unpinned-family-value-map",
			List.of(new CandidateRealizationSupportClause(List.of(), product.bindingsAt(0))));
		replaceRealizations(full, full.fact(child, binary), childEmission,
			List.of(nativeMixed, valueMap));
		full.resolver().proveGeneratedCandidateSupport(
			outerFact, outerEmission, proposed, pool);
		Assert.assertEquals("mixed native and VALUE_MAP authority uses the full legacy topology",
			product.size(), mixedRelation.materializedHandleCount());
	}

	@Test
	public void nativeRootOverlayPreservesGroundedRecurrenceWithDifferentPublishedPin() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("overlay-seed", pool);
		Ref consumer = full.unary("overlay-root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference current = full.reference(consumer, inputs);
		CandidateRealizationReference historical = new CandidateRealizationReference(current.rule(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				current.realization().emissionState(), "historical-root-pin"));
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(pool, pool, true,
				List.of(List.of(CandidateRealizationInputBinding.direct(0, historical))));
		Assert.assertNotNull(product);
		full.edges.removeIf(edge -> edge.consumer() == consumer.key);
		full.edges.add(new CompiledInputEdgeFact(consumer.key, consumer.key, 0));
		NativeContinuitySupportClauses explicitRelation = new NativeContinuitySupportClauses(
			consumer.key, product, pool, true);
		CandidateRealizationReference explicitPublished = full.withClauses(
			consumer, inputs, List.copyOf(explicitRelation));
		Assert.assertNotEquals(historical, explicitPublished);
		NativePlacementContinuity.CandidateSupportResult explicit = full.resolver()
			.proveCandidateSupport(explicitPublished, pool);
		Assert.assertFalse("the legacy root overlay grounds this recurrence", explicit.proofs().isEmpty());
		NativeContinuitySupportClauses nativeRelation = new NativeContinuitySupportClauses(
			consumer.key, product, pool, true);
		CandidateRealizationReference nativePublished = full.withClauses(consumer, inputs, nativeRelation);
		NativePlacementContinuity.CandidateSupportResult actual = full.resolver()
			.proveCandidateSupport(nativePublished, pool);
		Assert.assertEquals(explicit.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList(),
			actual.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		assertIdentitySetEquals(explicit.dependencyOccurrences(), actual.dependencyOccurrences());
	}

	@Test
	public void nativeAxisGateKeepsPinnedRootRecurrenceInsideCycleAnalysis() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("gate-cycle-seed", pool);
		Ref consumer = full.unary("gate-cycle", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference staging = full.reference(consumer, inputs);
		List<List<CandidateRealizationInputBinding>> axes = List.of(List.of(
			CandidateRealizationInputBinding.direct(0, staging)));
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(pool, pool, true, axes);
		Assert.assertNotNull(product);
		NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
			consumer.key, product, null, true);
		CandidateRealizationReference published = full.withClauses(consumer, inputs, relation);
		full.edges.removeIf(edge -> edge.consumer() == consumer.key);
		full.edges.add(new CompiledInputEdgeFact(consumer.key, consumer.key, 0));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<String> factored = full.resolver(metrics, 0, 0)
			.proveCandidateSupport(published, pool).proofs().stream()
			.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList();
		Assert.assertTrue("synthetic gates participate in the existing SCC traversal",
			metrics.snapshot().cyclicProofGraphs() > 0);
		Assert.assertEquals("cycle analysis needs only the exact representative",
			1, relation.materializedHandleCount());
		CandidateRealizationReference explicit = full.withClauses(
			consumer, inputs, List.copyOf(relation));
		SearchSpaceMetrics explicitMetrics = new SearchSpaceMetrics();
		List<String> reference = full.resolver(explicitMetrics, 0, 0)
			.proveCandidateSupport(explicit, pool).proofs().stream()
			.map(NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList();
		Assert.assertEquals("axis gates preserve the legacy SCC fixed-point semantics",
			reference, factored);
		Assert.assertTrue(explicitMetrics.snapshot().cyclicProofGraphs() > 0);
	}

	@Test
	public void nativeAxisGatePreservesAnOverriddenRootRealizationPin() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("override-seed", pool);
		Ref consumer = full.unary("override-cycle", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference selected = full.reference(consumer, inputs);
		CandidateRealizationReference other = new CandidateRealizationReference(selected.rule(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(
				selected.realization().emissionState(), "different-root-pin"));
		Assert.assertNotEquals(selected, other);
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(pool, pool, true,
				List.of(List.of(CandidateRealizationInputBinding.direct(0, other))));
		Assert.assertNotNull(product);
		NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
			consumer.key, product, null, true);
		CandidateRealizationReference factoredReference = full.withClauses(consumer, inputs, relation);
		full.edges.removeIf(edge -> edge.consumer() == consumer.key);
		full.edges.add(new CompiledInputEdgeFact(consumer.key, consumer.key, 0));
		SearchSpaceMetrics factoredMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult factored = full.resolver(factoredMetrics, 0, 0)
			.proveCandidateSupport(factoredReference, pool);
		CandidateRealizationReference explicitReference = full.withClauses(
			consumer, inputs, List.copyOf(relation));
		SearchSpaceMetrics explicitMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity.CandidateSupportResult explicit = full.resolver(explicitMetrics, 0, 0)
			.proveCandidateSupport(explicitReference, pool);
		Assert.assertTrue("the legacy overlay retains the selected root recurrence",
			explicitMetrics.snapshot().cyclicProofGraphs() > 0);
		Assert.assertEquals("factorization must override a different published root pin",
			explicit.proofs(), factored.proofs());
		assertIdentitySetEquals(explicit.dependencyOccurrences(), factored.dependencyOccurrences());
		Assert.assertTrue("the factored representation must preserve the same recurrence",
			factoredMetrics.snapshot().cyclicProofGraphs() > 0);
	}

	@Test
	public void nativeAxisGatePreservesEveryRepeatedProducerInputPosition() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("repeated-gate-seed", pool);
		Ref producer = full.unary("repeated-gate-producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, unary,
			new DurableAnchorKey("repeated-producer-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("repeated-producer-b", FType.FULL, pool.partitions()));
		CandidateRuleFact producerFact = full.fact(producer, unary);
		List<CandidateRealizationInputBinding> axis = producerFact.allowedEmissionFacts().get(0)
			.realizations().stream().map(realization -> CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(producerFact.key(), realization))).toList();
		Ref sum = full.binary("repeated-gate-sum", OpOp2.PLUS, producer, producer, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(pool, pool, true, List.of(axis));
		Assert.assertNotNull(product);
		NativeContinuitySupportClauses relation = new NativeContinuitySupportClauses(
			sum.key, product, pool, true);
		CandidateRealizationReference factoredReference = full.withClauses(sum, binary, relation);
		NativePlacementContinuity.CandidateSupportResult factored = full.resolver(null, 0, 0)
			.proveCandidateSupport(factoredReference, pool);
		CandidateRealizationReference explicitReference = full.withClauses(sum, binary, List.copyOf(relation));
		NativePlacementContinuity.CandidateSupportResult explicit = full.resolver(null, 0, 0)
			.proveCandidateSupport(explicitReference, pool);
		Assert.assertFalse(explicit.proofs().isEmpty());
		for(var proof : explicit.proofs())
			Assert.assertEquals(List.of(0, 1), proof.immediateBindings().stream()
				.map(CandidateRealizationInputBinding::inputPosition).toList());
		Assert.assertEquals("one owner axis must not hide another compiled input position",
			explicit.proofs(), factored.proofs());
		assertIdentitySetEquals(explicit.dependencyOccurrences(), factored.dependencyOccurrences());
	}

	@Test
	public void nestedNativeAxisGateRetainsEveryRealOwnerInItsInvalidationFootprint() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("nested-gate-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Ref left = full.unary("nested-gate-left", OpOp1.LOG, seed, false);
		Ref right = full.unary("nested-gate-right", OpOp1.LOG, seed, false);
		DurableAnchorKey leftA = new DurableAnchorKey(
			"nested-gate-left-a", FType.FULL, pool.partitions());
		DurableAnchorKey leftB = new DurableAnchorKey(
			"nested-gate-left-b", FType.FULL, pool.partitions());
		full.samePoolRealizations(left, unary, leftA, leftB);
		full.samePoolRealizations(right, unary,
			new DurableAnchorKey("nested-gate-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("nested-gate-right-b", FType.FULL, pool.partitions()));
		Ref child = full.binary("nested-gate-child", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> binary = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		NativePlacementContinuity.CandidateSupportResult generatedChild = full.resolver()
			.proveCandidateSupport(full.reference(child, binary), pool);
		Assert.assertNotNull(generatedChild.supportProduct());
		NativeContinuitySupportClauses explicitChild = new NativeContinuitySupportClauses(
			child.key, generatedChild.supportProduct(), pool, true);
		full.withClauses(child, binary, List.copyOf(explicitChild));

		Ref outer = full.unary("nested-gate-outer", OpOp1.LOG, child, false);
		NativePlacementContinuity.CandidateSupportResult generatedOuter = full.resolver()
			.proveCandidateSupport(full.reference(outer, unary), pool);
		Assert.assertNotNull(generatedOuter.supportProduct());
		NativeContinuitySupportClauses explicitOuter = new NativeContinuitySupportClauses(
			outer.key, generatedOuter.supportProduct(), pool, true);
		CandidateRealizationReference explicitPublished = full.withClauses(
			outer, unary, List.copyOf(explicitOuter));
		List<String> reference = full.resolver().proveCandidateSupport(explicitPublished, pool)
			.proofs().stream().map(
				NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList();
		NativeContinuitySupportClauses childRelation = new NativeContinuitySupportClauses(
			child.key, generatedChild.supportProduct(), pool, true);
		full.withClauses(child, binary, childRelation);
		NativeContinuitySupportClauses outerRelation = new NativeContinuitySupportClauses(
			outer.key, generatedOuter.supportProduct(), pool, true);
		CandidateRealizationReference published = full.withClauses(outer, unary, outerRelation);
		NativePlacementContinuity.CandidateSupportResult nested =
			full.resolver().proveCandidateSupport(published, pool);
		Assert.assertEquals(reference, nested.proofs().stream().map(
			NativePlacementContinuity.NativeContinuityProof::normalizedSignature).toList());
		Assert.assertEquals(Set.of(outer.key, child.key, left.key, right.key, seed.key),
			nested.dependencyOccurrences());
		Assert.assertEquals(1, childRelation.materializedHandleCount());
		Assert.assertEquals(1, outerRelation.materializedHandleCount());
		full.samePoolRealizations(left, unary, leftA);
		Assert.assertFalse(full.resolver().proveCandidateSupport(published, pool).proofs().isEmpty());
		full.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == left.key);
		Assert.assertTrue("a changed nested gate owner invalidates the outer proof",
			full.resolver().proveCandidateSupport(published, pool).proofs().isEmpty());
	}

	@Test
	public void conflictingClausePinsForOneProducerAreRejectedBeforeGrounding() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput = List.of(CandidateInputState.present(FType.FULL));
		full.samePoolRealizations(producer, oneInput,
			new DurableAnchorKey("producer-a", FType.FULL, List.of(partition("worker1:8001", 0, 50))),
			new DurableAnchorKey("producer-b", FType.FULL, List.of(partition("worker1:8001", 0, 50))));
		CandidateRuleFact producerFact = full.fact(producer, oneInput);
		List<CandidateEmissionRealization> realizations =
			producerFact.allowedEmissionFacts().get(0).realizations();
		CandidateRealizationReference a = CandidateRealizationReference.of(producerFact.key(),
			realizations.get(0));
		CandidateRealizationReference b = CandidateRealizationReference.of(producerFact.key(),
			realizations.get(1));
		Ref sum = full.binary("sum", OpOp2.PLUS, producer, producer, false);
		List<CandidateInputState> twoInputs = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		CandidateRealizationReference root = full.withClauses(sum, twoInputs, List.of(
			new CandidateRealizationSupportClause(List.of(), List.of(
				CandidateRealizationInputBinding.direct(0, a),
				CandidateRealizationInputBinding.direct(1, b)))));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 0, 0);
		Class<?> witnessClass = Class.forName(NativePlacementContinuity.class.getName() + "$NativePoolWitness");
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod("nativeWitness",
			DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Method candidateTopology = NativePlacementContinuity.class.getDeclaredMethod("candidateTopology",
			CompiledHopKey.class, witnessClass);
		candidateTopology.setAccessible(true);
		Object topology = candidateTopology.invoke(resolver, sum.key,
			nativeWitness.invoke(resolver, seed.anchor));
		Assert.assertTrue("a contradictory public clause cannot become a private executable row",
			((List<?>)accessibleField(topology.getClass(), "rows").get(topology)).isEmpty());
		Assert.assertEquals(1, metrics.snapshot().contradictoryClausePins());
		Assert.assertTrue("a declared but contradictory realization cannot use a staging fallback",
			resolver.proveCandidateAlternatives(root, seed.anchor).isEmpty());
	}

	@Test
	public void generatedSupportKeyDoesNotExpandPublishedRootTopology() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(root, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact base = full.fact(root, List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference declared = CandidateRealizationReference.of(base.key(),
			emission.realizations().get(0));
		CandidateRealizationReference proposed = new CandidateRealizationReference(base.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(emission.emissionState(),
				"unpublished-generated-root"));
		String property = "sysds.fedplanner.continuityTopology.maxEntries";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, "0");
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = full.resolver(metrics, 8, 128);
			for(CandidateRealizationReference source : List.of(declared, proposed))
				for(int query = 0; query < 3; query++)
					supportKey(resolver, source, seed.anchor, true);
			Assert.assertEquals("generated lookup must not expand a published topology it does not use",
				0, metrics.snapshot().topologyExpansionBuilds());
			supportKey(resolver, declared, seed.anchor, false);
			Assert.assertEquals("declared validation keeps the original exact-row classification",
				1, metrics.snapshot().topologyExpansionBuilds());
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	@Test
	public void generatedSupportKeyRefinesProspectiveRootGroups() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(root, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact base = full.fact(root, List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference first = new CandidateRealizationReference(base.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(emission.emissionState(), "first"));
		CandidateRealizationReference second = new CandidateRealizationReference(base.key(),
			PlacementIdentity.PlacementRealizationKey.nativeLineage(emission.emissionState(), "second"));
		NativePlacementContinuity resolver = full.resolver();
		Object firstKey = supportKey(resolver, first, seed.anchor, true);
		Assert.assertNotEquals("skipping classification must refine, not coarsen, source identity",
			firstKey, supportKey(resolver, second, seed.anchor, true));
		CandidateRealizationReference equalClone = new CandidateRealizationReference(
			new CandidateRuleKey(root.key, base.key().orderedInputs()), first.realization());
		Object cloneKey = supportKey(resolver, equalClone, seed.anchor, true);
		Assert.assertEquals(firstKey, cloneKey);
		Assert.assertEquals(firstKey.hashCode(), cloneKey.hashCode());
		Assert.assertNotEquals("validation and generation must never share a memo entry", firstKey,
			supportKey(resolver, first, seed.anchor, false));
		Assert.assertNotEquals("a different worker witness must remain distinct", firstKey,
			supportKey(resolver, first, anchor(FType.FULL, "worker2:8001", 0, 50), true));
		Fixture foreign = new Fixture(FType.FULL);
		Ref foreignSeed = foreign.source("seed", seed.anchor);
		Ref foreignRoot = foreign.unary("root", OpOp1.LOG, foreignSeed, false);
		CandidateRuleFact foreignBase = foreign.fact(foreignRoot,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateRealizationReference foreignSource = new CandidateRealizationReference(
			foreignBase.key(), first.realization());
		Assert.assertNotEquals("equal-looking foreign owner authority cannot collide", firstKey,
			supportKey(resolver, foreignSource, seed.anchor, true));
		NativePlacementContinuity.CandidateSupportResult rejected =
			resolver.proveGeneratedCandidateSupport(foreignBase,
				foreignBase.allowedEmissionFacts().get(0), foreignSource, seed.anchor);
		Assert.assertTrue(rejected.proofs().isEmpty());
		Assert.assertEquals("an authority rejection still has a complete local invalidation footprint",
			Set.of(foreignRoot.key), rejected.dependencyOccurrences());
		Assert.assertEquals("a refined memo must preserve exact generated proofs", full.resolver(null, 0, 0)
			.proveGeneratedCandidateAlternatives(base, emission, first, seed.anchor),
			resolver.proveGeneratedCandidateAlternatives(base, emission, first, seed.anchor));
		Assert.assertEquals(full.resolver(null, 0, 0)
			.proveGeneratedCandidateAlternatives(base, emission, second, seed.anchor),
			resolver.proveGeneratedCandidateAlternatives(base, emission, second, seed.anchor));
		NativePlacementContinuity.CandidateSupportResult cached =
			resolver.proveGeneratedCandidateSupport(base, emission, first, seed.anchor);
		NativePlacementContinuity.CandidateSupportResult cold = full.resolver(null, 0, 0)
			.proveGeneratedCandidateSupport(base, emission, first, seed.anchor);
		Assert.assertEquals(cold.proofs(), cached.proofs());
		Assert.assertEquals("memo hits and zero-budget recomputation must expose the same complete footprint",
			cold.dependencyOccurrences(), cached.dependencyOccurrences());
	}

	private static Object supportKey(NativePlacementContinuity resolver,
		CandidateRealizationReference source, DurableAnchorKey anchor, boolean generated) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, anchor);
		Method queryKey = NativePlacementContinuity.class.getDeclaredMethod("candidateSupportQueryKey",
			CandidateRealizationReference.class, witness.getClass(), boolean.class);
		queryKey.setAccessible(true);
		return queryKey.invoke(resolver, source, witness, generated);
	}

	@Test
	public void generatedRootProofIsIndependentOfDeclaredSupportHistory() {
		Fixture full = new Fixture(FType.FULL);
		Ref leftSeed = full.source("leftSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref rightSeed = full.source("rightSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref left = full.unary("left", OpOp1.LOG, leftSeed, false);
		Ref right = full.unary("right", OpOp1.EXP, rightSeed, false);
		CandidateRealizationReference leftReference = full.withClauses(left,
			List.of(CandidateInputState.present(FType.FULL)),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		full.withClauses(right, List.of(CandidateInputState.present(FType.FULL)),
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		Ref root = full.binary("root", OpOp2.PLUS, left, right, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL),
			CandidateInputState.present(FType.FULL));
		CandidateRuleFact absentBase = full.fact(root, inputs);
		CandidateEmissionFact absentEmission = absentBase.allowedEmissionFacts().get(0);
		CandidateEmissionRealization proposedRealization = CandidateEmissionRealization.nativeLineage(
			absentEmission.emissionState(), "generated-root", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			absentBase.key(), proposedRealization);
		for(Ref protectedRef : List.of(leftSeed, rightSeed, left, right, root))
			full.privacy(protectedRef, Privacy.PRIVATE_AGGREGATE);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity absentResolver = full.resolver(metrics, 128, 1024);
		List<NativePlacementContinuity.NativeContinuityProof> absent = absentResolver
			.proveGeneratedCandidateAlternatives(absentBase, absentEmission, proposed, leftSeed.anchor);
		Assert.assertFalse(absent.isEmpty());
		Assert.assertTrue(absent.stream().allMatch(proof -> proof.immediateBindings().size() == 2));

		long built = metrics.snapshot().proofGraphsBuilt();
		Assert.assertEquals(full.resolver(null, 0, 0).proveGeneratedCandidateAlternatives(
			absentBase, absentEmission, proposed, leftSeed.anchor), absent);

		CandidateRuleFact stagingBase = replaceRootRealization(
			full, absentBase, absentEmission, proposedRealization);
		NativePlacementContinuity stagingResolver = absentResolver.nextRevision(List.copyOf(full.candidates));
		CandidateEmissionFact stagingEmission = stagingBase.allowedEmissionFacts().get(0);
		List<NativePlacementContinuity.NativeContinuityProof> staging = stagingResolver
			.proveGeneratedCandidateAlternatives(stagingBase, stagingEmission, proposed, leftSeed.anchor);

		Assert.assertEquals("root publication must reuse private generated support templates",
			built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue("old base identity remains unauthorized after support reuse", stagingResolver
			.proveGeneratedCandidateAlternatives(absentBase, absentEmission, proposed, leftSeed.anchor).isEmpty());
		Assert.assertEquals(full.resolver(null, 0, 0).proveGeneratedCandidateAlternatives(
			stagingBase, stagingEmission, proposed, leftSeed.anchor), staging);

		CandidateRealizationSupportClause partialClause = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, leftReference)));
		CandidateEmissionRealization partial = new CandidateEmissionRealization(
			proposedRealization.key(), List.of(partialClause));
		CandidateRuleFact partialBase = replaceRootRealization(
			full, stagingBase, stagingEmission, partial);
		NativePlacementContinuity partialResolver = stagingResolver.nextRevision(List.copyOf(full.candidates));
		List<NativePlacementContinuity.NativeContinuityProof> partialProofs = partialResolver
			.proveGeneratedCandidateAlternatives(partialBase,
				partialBase.allowedEmissionFacts().get(0), proposed, leftSeed.anchor);

		Assert.assertEquals("partial old root support must not rebuild generated proof graphs",
			built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertEquals(full.resolver(null, 0, 0).proveGeneratedCandidateAlternatives(
			partialBase, partialBase.allowedEmissionFacts().get(0), proposed, leftSeed.anchor), partialProofs);

		Assert.assertEquals("staging history must not change the generator-root relation", absent, staging);
		Assert.assertEquals("an old exact support subset must not constrain generation", absent, partialProofs);
	}

	@Test
	public void generatedBatchReusesResidentCertifiedSupportWithoutAliasInsertion() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization outputA = CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "batch-a", List.of(), List.of());
		CandidateEmissionRealization outputB = CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "batch-b", List.of(), List.of());
		CandidateRealizationReference proposedA = CandidateRealizationReference.of(base.key(), outputA);
		CandidateRealizationReference proposedB = CandidateRealizationReference.of(base.key(), outputB);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch batch =
			resolver.generatedSupportBatch(base, emission);
		Assert.assertNotNull(batch);
		NativePlacementContinuity.CandidateSupportResult first = resolver
			.proveGeneratedCandidateSupport(base, emission, proposedA, seed.anchor, batch);
		long built = metrics.snapshot().proofGraphsBuilt();
		List<?> supportKeys = supportMemoKeys(resolver);
		Assert.assertEquals("FULL evaluates separate exact and dynamic support witnesses", 2, built);
		Assert.assertEquals("both exactness variants must remain resident", 2, supportKeys.size());
		DurableAnchorKey equivalentSeed = new DurableAnchorKey(
			"equivalent-seed", FType.FULL, seed.anchor.partitions());
		NativePlacementContinuity.CandidateSupportResult second = resolver
			.proveGeneratedCandidateSupport(base, emission, proposedB, equivalentSeed, batch);
		Assert.assertEquals("a certified B return must not build another proof graph",
			built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_REUSE_HITS") > 0);
		Assert.assertEquals("reuse must not insert a support-cache alias under B",
			supportKeys, supportMemoKeys(resolver));
		Assert.assertEquals(0, batchWork(metrics,
			"GENERATED_BATCH_ROOT_HISTORY_REJECTIONS"));
		Assert.assertEquals(0, batchWork(metrics,
			"GENERATED_BATCH_ROOT_BINDING_REJECTIONS"));

		NativePlacementContinuity cold = full.resolver(null, 0, 0);
		NativePlacementContinuity.CandidateSupportResult coldFirst = cold
			.proveGeneratedCandidateSupport(base, emission, proposedA, seed.anchor);
		NativePlacementContinuity.CandidateSupportResult coldSecond = cold
			.proveGeneratedCandidateSupport(base, emission, proposedB, equivalentSeed);
		Assert.assertEquals(coldFirst.proofs(), first.proofs());
		Assert.assertEquals(coldSecond.proofs(), second.proofs());
		assertIdentitySetEquals(coldFirst.dependencyOccurrences(), first.dependencyOccurrences());
		assertIdentitySetEquals(coldSecond.dependencyOccurrences(), second.dependencyOccurrences());

		SearchSpaceMetrics reverseMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity reverse = full.resolver(reverseMetrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch reverseBatch =
			reverse.generatedSupportBatch(base, emission);
		NativePlacementContinuity.CandidateSupportResult reverseFirst = reverse
			.proveGeneratedCandidateSupport(base, emission, proposedB, equivalentSeed, reverseBatch);
		long reverseBuilt = reverseMetrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity.CandidateSupportResult reverseSecond = reverse
			.proveGeneratedCandidateSupport(base, emission, proposedA, seed.anchor, reverseBatch);
		Assert.assertEquals(reverseBuilt, reverseMetrics.snapshot().proofGraphsBuilt());
		Assert.assertEquals(coldSecond.proofs(), reverseFirst.proofs());
		Assert.assertEquals(coldFirst.proofs(), reverseSecond.proofs());
		Assert.assertTrue(batchWork(reverseMetrics, "GENERATED_BATCH_REUSE_HITS") > 0);
	}

	@Test
	public void generatedBatchRequiresPositiveBudgetsAndExactCurrentScope() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposedA = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "zero-budget-a", List.of(), List.of()));
		CandidateRealizationReference proposedB = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "zero-budget-b", List.of(), List.of()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		Assert.assertNull(full.resolver(metrics, 0, 2048)
			.generatedSupportBatch(base, emission));
		Assert.assertNull(full.resolver(metrics, 128, 0)
			.generatedSupportBatch(base, emission));
		Assert.assertNull(full.resolver(metrics, 128, 2048, 0)
			.generatedSupportBatch(base, emission));
		for(String property : List.of(
			"sysds.fedplanner.continuitySupportMemo.maxEntries",
			"sysds.fedplanner.continuitySupportMemo.maxTemplates",
			"sysds.fedplanner.continuitySupportMemo.maxEstimatedBytes")) {
			String prior = System.getProperty(property);
			try {
				System.setProperty(property, "0");
				Assert.assertNull(property, full.resolver(metrics, 128, 2048)
					.generatedSupportBatch(base, emission));
			}
			finally {
				if(prior == null)
					System.clearProperty(property);
				else
					System.setProperty(property, prior);
			}
		}
		NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch batch =
			resolver.generatedSupportBatch(base, emission);
		Assert.assertNotNull(batch);

		CandidateEmissionRealization twinOutput = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "owner-twin", List.of(), List.of());
		CompiledHopKey twinOwner = new CompiledHopKey(root.key.programFingerprint(),
			root.key.functionNamespace(), root.key.callSitePath(), root.key.recompileContext(),
			root.key.controlRegion(), root.key.emittedHopInstance(), root.key.canonicalSourceOrigin());
		Assert.assertNotSame(root.key, twinOwner);
		Assert.assertEquals(root.key, twinOwner);
		CandidateRuleKey twinRule = new CandidateRuleKey(twinOwner, base.key().orderedInputs());
		CandidateRealizationReference twin = new CandidateRealizationReference(
			twinRule, twinOutput.key());
		resolver.proveGeneratedCandidateSupport(base, emission, proposedA, seed.anchor, batch);
		long reuseHits = batchWork(metrics, "GENERATED_BATCH_REUSE_HITS");
		long built = metrics.snapshot().proofGraphsBuilt();
		resolver.proveGeneratedCandidateSupport(base, emission, twin, seed.anchor, batch);
		Assert.assertTrue("equal foreign owner identity must fall back", metrics.snapshot().proofGraphsBuilt() > built);
		Assert.assertEquals(reuseHits, batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));
		CandidateRuleFact equalForeignFact = new CandidateRuleFact(base.key(), base.status(),
			base.capability(), base.shapeProof(), base.profile(), base.allowedEmissionFacts(),
			base.failureCode());
		CandidateEmissionFact equalForeignEmission = new CandidateEmissionFact(
			emission.emissionState(), emission.executionFType(), emission.derivedFoutAction(),
			emission.realizations());
		resolver.proveGeneratedCandidateSupport(
			equalForeignFact, emission, proposedB, seed.anchor, batch);
		resolver.proveGeneratedCandidateSupport(
			base, equalForeignEmission, proposedB, seed.anchor, batch);
		Assert.assertEquals("fact and emission identity delimit one batch", reuseHits,
			batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));

		CandidateRealizationReference crossResolverOutput = CandidateRealizationReference.of(
			base.key(), CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "cross-resolver", List.of(), List.of()));
		SearchSpaceMetrics otherMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity otherResolver = full.resolver(otherMetrics, 128, 2048);
		NativePlacementContinuity.CandidateSupportResult crossResolver = otherResolver
			.proveGeneratedCandidateSupport(
				base, emission, crossResolverOutput, seed.anchor, batch);
		Assert.assertEquals("a batch cannot read or mutate another resolver's memo", reuseHits,
			batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));
		Assert.assertEquals(0, batchWork(otherMetrics, "GENERATED_BATCH_REUSE_HITS"));
		NativePlacementContinuity.CandidateSupportResult crossResolverFresh =
			full.resolver(null, 0, 0).proveGeneratedCandidateSupport(
				base, emission, crossResolverOutput, seed.anchor);
		Assert.assertEquals(crossResolverFresh.proofs(), crossResolver.proofs());
		assertIdentitySetEquals(crossResolverFresh.dependencyOccurrences(),
			crossResolver.dependencyOccurrences());
	}

	@Test
	public void generatedBatchDoesNotIndexOversizedSupport() {
		String property = "sysds.fedplanner.continuitySupportMemo.maxEstimatedBytes";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, "1");
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("oversized-batch-seed",
				anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref root = full.unary("oversized-batch-root", OpOp1.LOG, seed, false);
			CandidateRuleFact base = full.fact(root,
				List.of(CandidateInputState.present(FType.FULL)));
			CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
			CandidateRealizationReference first = CandidateRealizationReference.of(base.key(),
				CandidateEmissionRealization.nativeLineage(
					emission.emissionState(), "oversized-batch-a", List.of(), List.of()));
			CandidateRealizationReference second = CandidateRealizationReference.of(base.key(),
				CandidateEmissionRealization.nativeLineage(
					emission.emissionState(), "oversized-batch-b", List.of(), List.of()));
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
			NativePlacementContinuity.GeneratedSupportBatch batch =
				resolver.generatedSupportBatch(base, emission);
			Assert.assertNotNull(batch);
			resolver.proveGeneratedCandidateSupport(base, emission, first, seed.anchor, batch);
			long built = metrics.snapshot().proofGraphsBuilt();
			resolver.proveGeneratedCandidateSupport(base, emission, second, seed.anchor, batch);
			Assert.assertTrue("a support rejected by the byte budget cannot seed reuse",
				metrics.snapshot().proofGraphsBuilt() > built);
			Assert.assertEquals(0, batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	@Test
	public void generatedBatchRejectsValueMapAndDerivedFoutRootHistory() {
		for(boolean derived : List.of(false, true)) {
			GeneratedHiddenRootFixture fixture = generatedHiddenRootFixture(derived);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = fixture.full().resolver(metrics, 128, 2048);
			NativePlacementContinuity.GeneratedSupportBatch batch =
				resolver.generatedSupportBatch(
					fixture.activeRoot(), fixture.activeEmission());
			CandidateEmissionRealization secondOutput = CandidateEmissionRealization.nativeLineage(
				fixture.activeEmission().emissionState(), "hidden-history-second", List.of(), List.of());
			CandidateRealizationReference second =
				CandidateRealizationReference.of(fixture.activeRoot().key(), secondOutput);
			NativePlacementContinuity.CandidateSupportResult first = resolver.proveGeneratedCandidateSupport(
				fixture.activeRoot(), fixture.activeEmission(),
				fixture.proposed(), fixture.seed().anchor, batch);
			long built = metrics.snapshot().proofGraphsBuilt();
			NativePlacementContinuity.CandidateSupportResult actual = resolver.proveGeneratedCandidateSupport(
				fixture.activeRoot(), fixture.activeEmission(),
				second, fixture.seed().anchor, batch);
			NativePlacementContinuity cold = fixture.full().resolver(null, 0, 0);
			NativePlacementContinuity.CandidateSupportResult coldFirst = cold.proveGeneratedCandidateSupport(
				fixture.activeRoot(), fixture.activeEmission(), fixture.proposed(), fixture.seed().anchor);
			NativePlacementContinuity.CandidateSupportResult expected = cold.proveGeneratedCandidateSupport(
				fixture.activeRoot(), fixture.activeEmission(), second, fixture.seed().anchor);
			Assert.assertEquals(coldFirst.proofs(), first.proofs());
			Assert.assertEquals(expected.proofs(), actual.proofs());
			assertIdentitySetEquals(coldFirst.dependencyOccurrences(), first.dependencyOccurrences());
			assertIdentitySetEquals(expected.dependencyOccurrences(), actual.dependencyOccurrences());
			Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > built);
			Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_ROOT_HISTORY_REJECTIONS") > 0);
			Assert.assertEquals(0, batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));
		}
	}

	private static long batchWork(SearchSpaceMetrics metrics, String name) {
		return metrics.directWorkCount(Enum.valueOf(SearchSpaceMetrics.DirectWork.class, name));
	}

	private static List<?> supportMemoKeys(NativePlacementContinuity continuity) throws Exception {
		Map<?,?> memo = (Map<?,?>)accessibleField(
			NativePlacementContinuity.class, "completedSupportMemo").get(continuity);
		return List.copyOf(memo.keySet());
	}

	@Test
	public void generatedBatchWorksWithoutMetricsAndReportsIndexSaturation() throws Exception {
		Fixture disabled = new Fixture(FType.FULL);
		Ref disabledSeed = disabled.source(
			"seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref disabledRoot = disabled.unary("root", OpOp1.LOG, disabledSeed, false);
		CandidateRuleFact disabledFact = disabled.fact(disabledRoot,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact disabledEmission = disabledFact.allowedEmissionFacts().get(0);
		NativePlacementContinuity disabledResolver = disabled.resolver(null, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch disabledBatch =
			disabledResolver.generatedSupportBatch(disabledFact, disabledEmission);
		Assert.assertNotNull("actual reuse is independent of metrics", disabledBatch);
		CandidateRealizationReference disabledA = CandidateRealizationReference.of(disabledFact.key(),
			CandidateEmissionRealization.nativeLineage(
				disabledEmission.emissionState(), "metrics-off-a", List.of(), List.of()));
		CandidateRealizationReference disabledB = CandidateRealizationReference.of(disabledFact.key(),
			CandidateEmissionRealization.nativeLineage(
				disabledEmission.emissionState(), "metrics-off-b", List.of(), List.of()));
		NativePlacementContinuity.CandidateSupportResult metricsOffA =
			disabledResolver.proveGeneratedCandidateSupport(
			disabledFact, disabledEmission, disabledA, disabledSeed.anchor, disabledBatch);
		Assert.assertFalse(metricsOffA.proofs().isEmpty());
		long skeletonsAfterA = skeletonCounter(disabledResolver, "dependencySkeletonBuilds");
		Assert.assertTrue(skeletonsAfterA > 0);
		List<?> residentAfterA = supportMemoKeys(disabledResolver);
		Assert.assertFalse(residentAfterA.isEmpty());
		NativePlacementContinuity.CandidateSupportResult metricsOffB =
			disabledResolver.proveGeneratedCandidateSupport(
			disabledFact, disabledEmission, disabledB, disabledSeed.anchor, disabledBatch);
		Assert.assertEquals("metrics-off reuse must not add a B support alias",
			residentAfterA, supportMemoKeys(disabledResolver));
		Assert.assertEquals("metrics-off B must skip graph skeleton construction",
			skeletonsAfterA, skeletonCounter(disabledResolver, "dependencySkeletonBuilds"));
		NativePlacementContinuity.CandidateSupportResult metricsOffCold =
			disabled.resolver(null, 0, 0).proveGeneratedCandidateSupport(
				disabledFact, disabledEmission, disabledB, disabledSeed.anchor);
		Assert.assertEquals(metricsOffCold.proofs(), metricsOffB.proofs());
		assertIdentitySetEquals(metricsOffCold.dependencyOccurrences(),
			metricsOffB.dependencyOccurrences());
		CandidateRealizationReference disabledC = CandidateRealizationReference.of(disabledFact.key(),
			CandidateEmissionRealization.nativeLineage(
				disabledEmission.emissionState(), "metrics-off-control", List.of(), List.of()));
		disabledResolver.proveGeneratedCandidateSupport(
			disabledFact, disabledEmission, disabledC, disabledSeed.anchor, null);
		Assert.assertTrue("a null-batch control must rebuild graph skeletons",
			skeletonCounter(disabledResolver, "dependencySkeletonBuilds") > skeletonsAfterA);

		String property = "sysds.fedplanner.continuitySupportMemo.maxEntries";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, "1");
			Fixture full = new Fixture(FType.FULL);
			DurableAnchorKey firstSeed = anchor(FType.FULL, "worker1:8001", 0, 50);
			DurableAnchorKey secondSeed = anchor(FType.FULL, "worker2:8002", 0, 50);
			Ref seed = full.source("seed", firstSeed);
			Ref root = full.unary("root", OpOp1.LOG, seed, false);
			CandidateRuleFact base = full.fact(root,
				List.of(CandidateInputState.present(FType.FULL)));
			CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
			NativePlacementContinuity.GeneratedSupportBatch batch =
				resolver.generatedSupportBatch(base, emission);
			List<NativePlacementContinuity.CandidateSupportResult> actual = new ArrayList<>();
			int ordinal = 0;
			for(DurableAnchorKey querySeed : List.of(firstSeed, secondSeed, firstSeed)) {
				CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
					CandidateEmissionRealization.nativeLineage(emission.emissionState(),
						"saturation-" + ordinal++, List.of(), List.of()));
				actual.add(resolver.proveGeneratedCandidateSupport(
					base, emission, proposed, querySeed, batch));
				NativePlacementContinuity.CandidateSupportResult fresh =
					full.resolver(null, 0, 0).proveGeneratedCandidateSupport(
						base, emission, proposed, querySeed);
				Assert.assertEquals(fresh.proofs(), actual.get(actual.size() - 1).proofs());
				assertIdentitySetEquals(fresh.dependencyOccurrences(),
					actual.get(actual.size() - 1).dependencyOccurrences());
			}
			Assert.assertTrue("a second resident witness exceeds the one-entry batch index",
				batchWork(metrics, "GENERATED_BATCH_INDEX_SATURATION") > 0);
			Assert.assertEquals("an evicted witness cannot be returned as reusable", 0,
				batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	@Test
	public void generatedBatchResidentGetRefreshesLruWithoutAddingAlias() throws Exception {
		String property = "sysds.fedplanner.continuitySupportMemo.maxEntries";
		String prior = System.getProperty(property);
		try {
			System.setProperty(property, "2");
			Fixture full = new Fixture(FType.BROADCAST);
			DurableAnchorKey witnessX = anchor(FType.BROADCAST, "worker1:8001", 0, 50);
			DurableAnchorKey witnessY = anchor(FType.BROADCAST, "worker2:8002", 0, 50);
			Ref seed = full.source("seed", witnessX);
			Ref root = full.unary("root", OpOp1.LOG, seed, false);
			CandidateRuleFact base = full.fact(root,
				List.of(CandidateInputState.present(FType.BROADCAST)));
			CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
			SearchSpaceMetrics observedMetrics = new SearchSpaceMetrics();
			NativePlacementContinuity observed = full.resolver(observedMetrics, 128, 2048);
			NativePlacementContinuity control = full.resolver(new SearchSpaceMetrics(), 128, 2048);
			var batch = observed.generatedSupportBatch(base, emission);
			List<DurableAnchorKey> seeds = List.of(witnessX, witnessY);
			for(int query = 0; query < seeds.size(); query++) {
				CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
					CandidateEmissionRealization.nativeLineage(emission.emissionState(),
						"lru-" + query, List.of(), List.of()));
				NativePlacementContinuity.CandidateSupportResult actual = observed
					.proveGeneratedCandidateSupport(
						base, emission, proposed, seeds.get(query), batch);
				NativePlacementContinuity.CandidateSupportResult expected = control
					.proveGeneratedCandidateSupport(
						base, emission, proposed, seeds.get(query), null);
				Assert.assertEquals(expected.proofs(), actual.proofs());
				assertIdentitySetEquals(
					expected.dependencyOccurrences(), actual.dependencyOccurrences());
			}
			List<?> beforeReuse = supportMemoKeys(observed);
			Assert.assertEquals(2, beforeReuse.size());
			CandidateRealizationReference alias = CandidateRealizationReference.of(base.key(),
				CandidateEmissionRealization.nativeLineage(
					emission.emissionState(), "lru-alias", List.of(), List.of()));
			observed.proveGeneratedCandidateSupport(base, emission, alias, witnessX, batch);
			List<?> afterReuse = supportMemoKeys(observed);
			Assert.assertEquals("resident reuse refreshes the source key instead of inserting alias B",
				List.of(beforeReuse.get(1), beforeReuse.get(0)), afterReuse);
			Assert.assertTrue(batchWork(observedMetrics, "GENERATED_BATCH_REUSE_HITS") > 0);
		}
		finally {
			if(prior == null)
				System.clearProperty(property);
			else
				System.setProperty(property, prior);
		}
	}

	@Test
	public void generatedBatchRootBindingCertificationInspectsLazyProductAxes()
		throws Exception {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("seed", pool);
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "root-binding", List.of(), List.of()));
		CandidateRealizationInputBinding rootBinding =
			CandidateRealizationInputBinding.direct(0, proposed);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch batch =
			resolver.generatedSupportBatch(base, emission);

		Class<?> templateClass = Class.forName(
			NativePlacementContinuity.class.getName() + "$CandidateSupportTemplate");
		Constructor<?> templateConstructor = java.util.Arrays.stream(
			templateClass.getDeclaredConstructors())
			.filter(candidate -> candidate.getParameterCount() == 3).findFirst().orElseThrow();
		templateConstructor.setAccessible(true);
		Object rootTemplate = templateConstructor.newInstance(
			pool, true, List.of(rootBinding));
		Object rootFreeTemplate = templateConstructor.newInstance(pool, true, List.of());
		Class<?> entryClass = Class.forName(
			NativePlacementContinuity.class.getName() + "$SupportMemoEntry");
		Constructor<?> entryConstructor = entryClass.getDeclaredConstructors()[0];
		entryConstructor.setAccessible(true);
		Object rootEntry = entryConstructor.newInstance(
			proposed, List.of(rootTemplate), Set.of(root.key), 32L, true);
		Object rootFreeEntry = entryConstructor.newInstance(
			proposed, List.of(rootFreeTemplate), Set.of(root.key), 32L, true);
		Method classifier = batch.getClass().getDeclaredMethod(
			"hasReturnedRootBinding", entryClass);
		classifier.setAccessible(true);
		Assert.assertTrue((boolean)classifier.invoke(batch, rootEntry));
		Assert.assertFalse((boolean)classifier.invoke(batch, rootFreeEntry));

		Class<?> productClass = Class.forName(
			NativePlacementContinuity.class.getName() + "$CandidateSupportTemplateProduct");
		Method productFactory = productClass.getDeclaredMethod(
			"tryCreate", DurableAnchorKey.class, boolean.class, List.class);
		productFactory.setAccessible(true);
		Object product = productFactory.invoke(null, pool, true,
			List.of(List.of(rootBinding)));
		Assert.assertNotNull(product);
		Object productEntry = entryConstructor.newInstance(
			proposed, product, Set.of(root.key), 32L, true);
		Assert.assertTrue("product certification reads its factor axis, not Cartesian members",
			(boolean)classifier.invoke(batch, productEntry));

		Object query = supportKey(resolver, proposed, pool, true);
		@SuppressWarnings("unchecked")
		Map<Object,Object> memo = (Map<Object,Object>)accessibleField(
			NativePlacementContinuity.class, "completedSupportMemo").get(resolver);
		memo.put(query, rootEntry);
		Object witness = accessibleField(query.getClass(), "witness").get(query);
		Method admit = batch.getClass().getDeclaredMethod(
			"admit", witness.getClass(), query.getClass(), entryClass);
		admit.setAccessible(true);
		admit.invoke(batch, witness, query, rootEntry);
		Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_ROOT_BINDING_REJECTIONS") > 0);
		// Inject a replacement under an indexed resident key: reuse must recheck
		// its certificate, not trust the earlier root-free admission (ABA defense).
		memo.put(query, rootFreeEntry);
		admit.invoke(batch, witness, query, rootFreeEntry);
		Map<?,?> indexed = (Map<?,?>)accessibleField(batch.getClass(), "certifiedByWitness").get(batch);
		Assert.assertTrue(indexed.containsValue(query));
		memo.put(query, rootEntry);
		CandidateRealizationReference changed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "root-binding-changed", List.of(), List.of()));
		long built = metrics.snapshot().proofGraphsBuilt();
		long reuseHits = batchWork(metrics, "GENERATED_BATCH_REUSE_HITS");
		NativePlacementContinuity.CandidateSupportResult actual = resolver
			.proveGeneratedCandidateSupport(base, emission, changed, pool, batch);
		Assert.assertTrue("a root-binding entry cannot be returned for a changed proposal",
			metrics.snapshot().proofGraphsBuilt() > built);
		Assert.assertEquals(reuseHits, batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));
		NativePlacementContinuity.CandidateSupportResult cold = full.resolver(null, 0, 0)
			.proveGeneratedCandidateSupport(base, emission, changed, pool);
		Assert.assertEquals(cold.proofs(), actual.proofs());
		assertIdentitySetEquals(cold.dependencyOccurrences(), actual.dependencyOccurrences());
	}

	@Test
	public void generatedBatchReusesNegativeSupportInEitherProposalOrder() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("missing-root", OpOp1.LOG, seed, false);
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference first = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "negative-a", List.of(), List.of()));
		CandidateRealizationReference second = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "negative-b", List.of(), List.of()));
		full.nodes.remove(root.key);
		full.origins.remove(root.key);
		full.edges.clear();
		SearchSpaceMetrics forwardMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity forward = full.resolver(forwardMetrics, 128, 2048);
		var forwardBatch = forward.generatedSupportBatch(base, emission);
		NativePlacementContinuity.CandidateSupportResult forwardA = forward
			.proveGeneratedCandidateSupport(base, emission, first, seed.anchor, forwardBatch);
		long forwardBuilt = forwardMetrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity.CandidateSupportResult forwardB = forward
			.proveGeneratedCandidateSupport(base, emission, second, seed.anchor, forwardBatch);
		Assert.assertEquals(forwardBuilt, forwardMetrics.snapshot().proofGraphsBuilt());
		SearchSpaceMetrics reverseMetrics = new SearchSpaceMetrics();
		NativePlacementContinuity reverse = full.resolver(reverseMetrics, 128, 2048);
		var reverseBatch = reverse.generatedSupportBatch(base, emission);
		NativePlacementContinuity.CandidateSupportResult reverseB = reverse
			.proveGeneratedCandidateSupport(base, emission, second, seed.anchor, reverseBatch);
		long reverseBuilt = reverseMetrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity.CandidateSupportResult reverseA = reverse
			.proveGeneratedCandidateSupport(base, emission, first, seed.anchor, reverseBatch);
		Assert.assertEquals(reverseBuilt, reverseMetrics.snapshot().proofGraphsBuilt());
		for(NativePlacementContinuity.CandidateSupportResult result :
			List.of(forwardA, forwardB, reverseA, reverseB))
			Assert.assertTrue(result.proofs().isEmpty());
		assertIdentitySetEquals(forwardA.dependencyOccurrences(), reverseA.dependencyOccurrences());
		assertIdentitySetEquals(forwardB.dependencyOccurrences(), reverseB.dependencyOccurrences());
		Assert.assertTrue(batchWork(forwardMetrics, "GENERATED_BATCH_REUSE_HITS") > 0);
		Assert.assertEquals(batchWork(forwardMetrics, "GENERATED_BATCH_REUSE_HITS"),
			batchWork(reverseMetrics, "GENERATED_BATCH_REUSE_HITS"));
	}

	@Test
	public void generatedBatchDoesNotCrossResolverRevision() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("revision-batch-seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("revision-batch-root", OpOp1.LOG, seed, false);
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference first = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "revision-batch-a", List.of(), List.of()));
		CandidateRealizationReference second = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "revision-batch-b", List.of(), List.of()));
		CandidateRealizationReference third = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "revision-batch-c", List.of(), List.of()));
		CandidateRealizationReference fourth = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "revision-batch-d", List.of(), List.of()));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity initial = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch oldBatch =
			initial.generatedSupportBatch(base, emission);
		initial.proveGeneratedCandidateSupport(base, emission, first, seed.anchor, oldBatch);

		NativePlacementContinuity revised = initial.nextRevision(List.copyOf(full.candidates));
		long built = metrics.snapshot().proofGraphsBuilt();
		long reuseHits = batchWork(metrics, "GENERATED_BATCH_REUSE_HITS");
		NativePlacementContinuity.CandidateSupportResult actual = revised
			.proveGeneratedCandidateSupport(base, emission, second, seed.anchor, oldBatch);
		NativePlacementContinuity.CandidateSupportResult expected = full.resolver(null, 0, 0)
			.proveGeneratedCandidateSupport(base, emission, second, seed.anchor);
		Assert.assertEquals(expected.proofs(), actual.proofs());
		assertIdentitySetEquals(expected.dependencyOccurrences(), actual.dependencyOccurrences());
		Assert.assertTrue("an old batch must fall back after revision",
			metrics.snapshot().proofGraphsBuilt() > built);
		Assert.assertEquals(reuseHits, batchWork(metrics, "GENERATED_BATCH_REUSE_HITS"));

		NativePlacementContinuity.GeneratedSupportBatch currentBatch =
			revised.generatedSupportBatch(base, emission);
		revised.proveGeneratedCandidateSupport(base, emission, third, seed.anchor, currentBatch);
		long currentBuilt = metrics.snapshot().proofGraphsBuilt();
		revised.proveGeneratedCandidateSupport(base, emission, fourth, seed.anchor, currentBatch);
		Assert.assertEquals(currentBuilt, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_REUSE_HITS") > reuseHits);
	}

	@Test
	public void generatedBatchRequiresTheFullNativeWitness() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey firstPool = anchor(FType.FULL, "worker1:8001", 0, 50);
		DurableAnchorKey differentEndpoint = anchor(FType.FULL, "worker2:8002", 0, 50);
		Ref seed = full.source("witness-batch-seed", firstPool);
		Ref root = full.unary("witness-batch-root", OpOp1.LOG, seed, false);
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch batch =
			resolver.generatedSupportBatch(base, emission);
		CandidateRealizationReference first = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "witness-batch-a", List.of(), List.of()));
		CandidateRealizationReference different = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "witness-batch-different", List.of(), List.of()));
		CandidateRealizationReference repeated = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "witness-batch-repeated", List.of(), List.of()));
		resolver.proveGeneratedCandidateSupport(base, emission, first, firstPool, batch);
		long firstBuilt = metrics.snapshot().proofGraphsBuilt();
		resolver.proveGeneratedCandidateSupport(base, emission, different, differentEndpoint, batch);
		long differentBuilt = metrics.snapshot().proofGraphsBuilt();
		Assert.assertTrue("endpoint differences must not reuse", differentBuilt > firstBuilt);
		long reuseHits = batchWork(metrics, "GENERATED_BATCH_REUSE_HITS");
		resolver.proveGeneratedCandidateSupport(base, emission, repeated,
			new DurableAnchorKey("same-witness", FType.FULL, firstPool.partitions()), batch);
		Assert.assertEquals("the exact prior witness must reuse", differentBuilt,
			metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_REUSE_HITS") > reuseHits);
	}

	@Test
	public void generatedBatchKeepsLazyProductAndRebindsExternalSeed() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey pool = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("product-batch-seed", pool);
		List<CandidateInputState> unary = List.of(CandidateInputState.present(FType.FULL));
		Ref left = full.unary("product-batch-left", OpOp1.LOG, seed, false);
		Ref right = full.unary("product-batch-right", OpOp1.EXP, seed, false);
		full.samePoolRealizations(left, unary,
			new DurableAnchorKey("product-left-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("product-left-b", FType.FULL, pool.partitions()));
		full.samePoolRealizations(right, unary,
			new DurableAnchorKey("product-right-a", FType.FULL, pool.partitions()),
			new DurableAnchorKey("product-right-b", FType.FULL, pool.partitions()));
		Ref root = full.binary("product-batch-root", OpOp2.PLUS, left, right, false);
		CandidateRuleFact base = full.fact(root, List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference first = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "product-batch-a", List.of(), List.of()));
		CandidateRealizationReference second = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "product-batch-b", List.of(), List.of()));
		DurableAnchorKey secondSeed = new DurableAnchorKey(
			"product-batch-second-seed", FType.FULL, pool.partitions());
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch batch =
			resolver.generatedSupportBatch(base, emission);
		NativePlacementContinuity.CandidateSupportResult a = resolver
			.proveGeneratedCandidateSupport(base, emission, first, pool, batch);
		Assert.assertNotNull(a.supportProduct());
		long built = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity.CandidateSupportResult b = resolver
			.proveGeneratedCandidateSupport(base, emission, second, secondSeed, batch);
		Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertNotNull("reuse must remain a lazy product", b.supportProduct());
		Assert.assertEquals(4, b.supportProduct().size());
		Assert.assertFalse(b.supportProduct().axes().isEmpty());
		Assert.assertEquals(a.supportProduct().axes(), b.supportProduct().axes());
		for(int axis = 0; axis < a.supportProduct().axes().size(); axis++)
			for(int option = 0; option < a.supportProduct().axes().get(axis).size(); option++)
				Assert.assertSame(a.supportProduct().axes().get(axis).get(option).source(),
					b.supportProduct().axes().get(axis).get(option).source());
		Assert.assertEquals(secondSeed, b.supportProduct().externalSeed());
		Assert.assertEquals(a.supportProduct().size(), b.supportProduct().size());
		NativePlacementContinuity.CandidateSupportResult coldB = full.resolver(null, 0, 0)
			.proveGeneratedCandidateSupport(base, emission, second, secondSeed);
		Assert.assertEquals(coldB.proofs(), b.proofs());
		assertIdentitySetEquals(coldB.dependencyOccurrences(), b.dependencyOccurrences());
	}

	@Test
	public void generatedBatchReusesPositiveAndNegativeProofCyclesInEitherOrder() {
		for(boolean negative : List.of(false, true))
			for(boolean reverse : List.of(false, true)) {
				Fixture full = new Fixture(FType.FULL);
				Ref seed = full.source("cycle-batch-seed-" + negative + '-' + reverse,
					anchor(FType.FULL, "worker1:8001", 0, 50));
				Ref read = full.logicalRead("cycle-batch-read-" + negative + '-' + reverse);
				Ref root = full.unary("cycle-batch-root-" + negative + '-' + reverse,
					OpOp1.LOG, read, false);
				full.reaching.put(read.key, List.of(seed.key, root.key));
				CandidateRuleFact base = full.fact(root,
					List.of(CandidateInputState.present(FType.FULL)));
				CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
				CandidateRealizationReference a = CandidateRealizationReference.of(base.key(),
					CandidateEmissionRealization.nativeLineage(emission.emissionState(),
						"cycle-batch-a", List.of(), List.of()));
				CandidateRealizationReference b = CandidateRealizationReference.of(base.key(),
					CandidateEmissionRealization.nativeLineage(emission.emissionState(),
						"cycle-batch-b", List.of(), List.of()));
				CandidateRealizationReference first = reverse ? b : a;
				CandidateRealizationReference second = reverse ? a : b;
				DurableAnchorKey witness = negative
					? anchor(FType.FULL, "worker2:8002", 0, 50) : seed.anchor;
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
				NativePlacementContinuity.GeneratedSupportBatch batch =
					resolver.generatedSupportBatch(base, emission);
				NativePlacementContinuity.CandidateSupportResult firstResult = resolver
					.proveGeneratedCandidateSupport(base, emission, first, witness, batch);
				long built = metrics.snapshot().proofGraphsBuilt();
				NativePlacementContinuity.CandidateSupportResult secondResult = resolver
					.proveGeneratedCandidateSupport(base, emission, second, witness, batch);
				Assert.assertEquals("a certified cyclic relation must reuse in either proposal order",
					built, metrics.snapshot().proofGraphsBuilt());
				Assert.assertEquals(negative, firstResult.proofs().isEmpty());
				Assert.assertEquals(negative, secondResult.proofs().isEmpty());
				NativePlacementContinuity.CandidateSupportResult cold = full.resolver(null, 0, 0)
					.proveGeneratedCandidateSupport(base, emission, second, witness);
				Assert.assertEquals(cold.proofs(), secondResult.proofs());
				assertIdentitySetEquals(cold.dependencyOccurrences(),
					secondResult.dependencyOccurrences());
				Assert.assertTrue("the fixture must execute the real proof-cycle fixed point",
					metrics.snapshot().cyclicProofGraphs() > 0);
				Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_REUSE_HITS") > 0);
			}
	}

	@Test
	public void generatedBatchIgnoresPublishedRootClausesPinnedToProposalBInEitherOrder() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("published-batch-seed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("published-batch-root", OpOp1.LOG, seed, false);
		CandidateRuleFact initial = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact initialEmission = initial.allowedEmissionFacts().get(0);
		CandidateRealizationReference pinnedB = CandidateRealizationReference.of(initial.key(),
			CandidateEmissionRealization.nativeLineage(initialEmission.emissionState(),
				"published-batch-b", List.of(), List.of()));
		CandidateRealizationSupportClause selfPinned = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0, pinnedB)));
		CandidateEmissionRealization publication = new CandidateEmissionRealization(
			pinnedB.realization(), List.of(selfPinned));
		CandidateRealizationReference publishedB = replacePublished(
			full, initial, initialEmission, publication);
		Assert.assertEquals(pinnedB, publishedB);
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposedA = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "published-batch-a", List.of(), List.of()));
		for(boolean reverse : List.of(false, true)) {
			CandidateRealizationReference first = reverse ? publishedB : proposedA;
			CandidateRealizationReference second = reverse ? proposedA : publishedB;
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
			NativePlacementContinuity.GeneratedSupportBatch batch =
				resolver.generatedSupportBatch(base, emission);
			resolver.proveGeneratedCandidateSupport(base, emission, first, seed.anchor, batch);
			long built = metrics.snapshot().proofGraphsBuilt();
			NativePlacementContinuity.CandidateSupportResult actual = resolver
				.proveGeneratedCandidateSupport(base, emission, second, seed.anchor, batch);
			Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());
			NativePlacementContinuity.CandidateSupportResult cold = full.resolver(null, 0, 0)
				.proveGeneratedCandidateSupport(base, emission, second, seed.anchor);
			Assert.assertEquals(cold.proofs(), actual.proofs());
			assertIdentitySetEquals(cold.dependencyOccurrences(), actual.dependencyOccurrences());
			Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_REUSE_HITS") > 0);
		}
	}

	@Test
	public void generatedBatchReusesOrdinaryDescendantPinToPublishedBInEitherOrder() {
		for(boolean factorized : List.of(false, true)) {
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("descendant-pin-seed-" + factorized,
				anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref read = full.logicalRead("descendant-pin-read-" + factorized);
			Ref root = full.unary("descendant-pin-root-" + factorized, OpOp1.LOG, read, false);
			full.reaching.put(read.key, List.of(seed.key, root.key));
			CandidateRuleFact initial = full.fact(root,
				List.of(CandidateInputState.present(FType.FULL)));
			CandidateEmissionFact initialEmission = initial.allowedEmissionFacts().get(0);
			CandidateEmissionRealization publication = CandidateEmissionRealization.nativeLineage(
				initialEmission.emissionState(), "descendant-published-b", List.of(), List.of());
			CandidateRealizationReference publishedB = replacePublished(
				full, initial, initialEmission, publication);
			CandidateRealizationInputBinding pinned =
				CandidateRealizationInputBinding.direct(0, publishedB);
			if(factorized) {
				NativePlacementContinuity.NativeSupportProduct product =
					NativePlacementContinuity.NativeSupportProduct.tryCreate(
						seed.anchor, seed.anchor, true, List.of(List.of(pinned)));
				Assert.assertNotNull(product);
				full.withClauses(read, List.of(CandidateInputState.present(FType.FULL)),
					new NativeContinuitySupportClauses(read.key, product, null, true));
			}
			else
				full.withClauses(read, List.of(CandidateInputState.present(FType.FULL)),
					List.of(new CandidateRealizationSupportClause(List.of(), List.of(pinned))));
			CandidateRuleFact base = full.fact(root,
				List.of(CandidateInputState.present(FType.FULL)));
			CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
			CandidateRealizationReference proposedA = CandidateRealizationReference.of(base.key(),
				CandidateEmissionRealization.nativeLineage(
					emission.emissionState(), "descendant-proposed-a", List.of(), List.of()));
			for(boolean reverse : List.of(false, true)) {
				CandidateRealizationReference first = reverse ? publishedB : proposedA;
				CandidateRealizationReference second = reverse ? proposedA : publishedB;
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity resolver = full.resolver(metrics, 128, 2048);
				NativePlacementContinuity.GeneratedSupportBatch batch =
					resolver.generatedSupportBatch(base, emission);
				NativePlacementContinuity.CandidateSupportResult firstResult = resolver
					.proveGeneratedCandidateSupport(base, emission, first, seed.anchor, batch);
				long built = metrics.snapshot().proofGraphsBuilt();
				NativePlacementContinuity.CandidateSupportResult actual = resolver
					.proveGeneratedCandidateSupport(base, emission, second, seed.anchor, batch);
				Assert.assertEquals("ordinary descendant pins are alpha-renamed with the generated root",
					built, metrics.snapshot().proofGraphsBuilt());
				NativePlacementContinuity coldResolver = full.resolver(null, 0, 0);
				NativePlacementContinuity.CandidateSupportResult coldFirst = coldResolver
					.proveGeneratedCandidateSupport(base, emission, first, seed.anchor);
				NativePlacementContinuity.CandidateSupportResult cold = coldResolver
					.proveGeneratedCandidateSupport(base, emission, second, seed.anchor);
				Assert.assertEquals(coldFirst.proofs(), firstResult.proofs());
				Assert.assertEquals(cold.proofs(), actual.proofs());
				Assert.assertFalse(firstResult.proofs().isEmpty());
				Assert.assertFalse(actual.proofs().isEmpty());
				assertIdentitySetEquals(coldFirst.dependencyOccurrences(),
					firstResult.dependencyOccurrences());
				assertIdentitySetEquals(cold.dependencyOccurrences(), actual.dependencyOccurrences());
				Assert.assertTrue(actual.dependencyOccurrences().stream()
					.anyMatch(owner -> owner == read.key));
				Assert.assertTrue(batchWork(metrics, "GENERATED_BATCH_REUSE_HITS") > 0);
				Assert.assertEquals(0, batchWork(metrics, "GENERATED_BATCH_ROOT_HISTORY_REJECTIONS"));
				Assert.assertEquals(0, batchWork(metrics, "GENERATED_BATCH_ROOT_BINDING_REJECTIONS"));
				Assert.assertTrue("the descendant pin must be read inside the proof-cycle traversal",
					metrics.snapshot().cyclicProofGraphs() > 0);
			}
		}
	}

	@Test
	public void generatedBatchDoesNotCrossExactRowRanges() {
		Fixture row = new Fixture(FType.ROW);
		DurableAnchorKey firstRange = anchor(FType.ROW, "worker1:8001", 0, 50);
		DurableAnchorKey changedRange = anchor(FType.ROW, "worker1:8001", 0, 60);
		Ref seed = row.source("range-batch-seed", firstRange);
		Ref root = row.unary("range-batch-root", OpOp1.LOG, seed, false);
		CandidateRuleFact base = row.fact(root,
			List.of(CandidateInputState.present(FType.ROW)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = row.resolver(metrics, 128, 2048);
		NativePlacementContinuity.GeneratedSupportBatch batch =
			resolver.generatedSupportBatch(base, emission);
		CandidateRealizationReference first = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "range-batch-a", List.of(), List.of()));
		CandidateRealizationReference changed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "range-batch-b", List.of(), List.of()));
		resolver.proveGeneratedCandidateSupport(base, emission, first, firstRange, batch);
		long built = metrics.snapshot().proofGraphsBuilt();
		NativePlacementContinuity.CandidateSupportResult actual = resolver
			.proveGeneratedCandidateSupport(base, emission, changed, changedRange, batch);
		Assert.assertTrue("ROW exact range changes must not use the prior exact support",
			metrics.snapshot().proofGraphsBuilt() > built);
		NativePlacementContinuity.CandidateSupportResult cold = row.resolver(null, 0, 0)
			.proveGeneratedCandidateSupport(base, emission, changed, changedRange);
		Assert.assertEquals(cold.proofs(), actual.proofs());
		assertIdentitySetEquals(cold.dependencyOccurrences(), actual.dependencyOccurrences());
	}

	@Test
	public void generatedRootHistoryReuseUsesExactTraversalInsideConservativeCycle() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		// The extra structural edge makes the conservative occurrence component cyclic,
		// but it is outside the candidate row's declared input positions and is never read.
		full.edges.add(new CompiledInputEdgeFact(root.key, root.key, 1));
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization publication = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "conservative-cycle-publication", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			base.key(), publication);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity first = full.resolver(metrics, 128, 2048);
		Object holder = accessibleField(
			NativePlacementContinuity.class, "occurrenceComponents").get(first);
		Method componentsMethod = holder.getClass().getDeclaredMethod("components");
		componentsMethod.setAccessible(true);
		PlacementDependencyComponents components =
			(PlacementDependencyComponents)componentsMethod.invoke(holder);
		Assert.assertTrue("the fixture must exercise a conservative cyclic component",
			components.componentOf(root.key).cyclic());
		List<NativePlacementContinuity.NativeContinuityProof> expected = first
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertFalse(expected.isEmpty());
		long built = metrics.snapshot().proofGraphsBuilt();
		long reused = metrics.snapshot().supportMemoRevisionEntriesReused();

		CandidateRuleFact published = replaceRootRealization(full, base, emission, publication);
		List<CandidateRuleFact> publishedFacts = List.copyOf(full.candidates);
		NativePlacementContinuity revised = first.nextRevisionWithCompleteCandidateDelta(
			publishedFacts, identitySet(root.key));
		List<NativePlacementContinuity.NativeContinuityProof> actual = revised
			.proveGeneratedCandidateAlternatives(published,
				published.allowedEmissionFacts().get(0), proposed, seed.anchor);

		Assert.assertEquals(new NativePlacementContinuity(full.nodes, full.origins,
			publishedFacts, full.edges, full.reaching, Set.of(), full.privacy)
			.proveGeneratedCandidateAlternatives(published,
				published.allowedEmissionFacts().get(0), proposed, seed.anchor), actual);
		Assert.assertEquals("an unread publication-only root change must reuse exact support",
			built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue("the exact support entry must cross the candidate revision",
			metrics.snapshot().supportMemoRevisionEntriesReused() > reused);
	}

	@Test
	public void generatedRootHistoryReusesRecipeCyclesButKeepsZeroBudgetCold() throws Exception {
		for(boolean cyclic : List.of(false, true))
			for(int budget : List.of(0, 128)) {
				Fixture full = new Fixture(FType.FULL);
				Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
				Ref input = cyclic ? full.logicalRead("loop") : seed;
				Ref root = full.unary("root", OpOp1.LOG, input, false);
				if(cyclic)
					full.reaching.put(input.key, List.of(seed.key, root.key));
				for(Ref protectedRef : List.of(seed, input, root))
					full.privacy(protectedRef, Privacy.PRIVATE_AGGREGATE);
				CandidateRuleFact base = full.fact(root, List.of(CandidateInputState.present(FType.FULL)));
				CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
				CandidateEmissionRealization publication = CandidateEmissionRealization.nativeLineage(
					emission.emissionState(), "publication", List.of(), List.of());
				CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(), publication);
				SearchSpaceMetrics metrics = new SearchSpaceMetrics();
				NativePlacementContinuity first = full.resolver(metrics, budget, budget * 16L);
				Object holder = accessibleField(
					NativePlacementContinuity.class, "occurrenceComponents").get(first);
				Method componentsMethod = holder.getClass().getDeclaredMethod("components");
				componentsMethod.setAccessible(true);
				PlacementDependencyComponents components =
					(PlacementDependencyComponents)componentsMethod.invoke(holder);
				Assert.assertEquals(cyclic, components.componentOf(root.key).cyclic());
				first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
				long built = metrics.snapshot().proofGraphsBuilt();
				CandidateRuleFact published = replaceRootRealization(full, base, emission, publication);
				NativePlacementContinuity next = first.nextRevisionWithCompleteCandidateDelta(
					List.copyOf(full.candidates), identitySet(root.key));
				var actual = next.proveGeneratedCandidateAlternatives(published,
					published.allowedEmissionFacts().get(0), proposed, seed.anchor);
				Assert.assertEquals(full.resolver(null, 0, 0).proveGeneratedCandidateAlternatives(
					published, published.allowedEmissionFacts().get(0), proposed, seed.anchor), actual);
				if(budget == 0)
					Assert.assertTrue("disabled caches must keep the cold path",
						metrics.snapshot().proofGraphsBuilt() > built);
				else
					Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());
			}
	}

	@Test
	public void generatedRecurrenceDoesNotReadPublishedRootHistory() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref read = full.logicalRead("loop-read");
		Ref root = full.unary("root", OpOp1.LOG, read, false);
		full.reaching.put(read.key, List.of(seed.key, root.key));
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization publication = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "generated-recurrence", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(), publication);
		for(DurableAnchorKey witness : List.of(seed.anchor,
			anchor(FType.FULL, "worker2:8001", 0, 50))) {
			SearchSpaceMetrics metrics = new SearchSpaceMetrics();
			NativePlacementContinuity first = full.resolver(metrics, 128, 2048);
			NativePlacementContinuity.CandidateSupportResult expected = first
				.proveGeneratedCandidateSupport(base, emission, proposed, witness);
			Assert.assertEquals(witness != seed.anchor, expected.proofs().isEmpty());
			assertGeneratedRootCertificate(first, proposed, root.key, true);
			long built = metrics.snapshot().proofGraphsBuilt();
			long reused = metrics.snapshot().supportMemoRevisionEntriesReused();

			CandidateRuleFact published = replaceRootRealization(full, base, emission, publication);
			List<CandidateRuleFact> publishedFacts = List.copyOf(full.candidates);
			NativePlacementContinuity revised = first.nextRevisionWithCompleteCandidateDelta(
				publishedFacts, identitySet(root.key));
			NativePlacementContinuity.CandidateSupportResult actual = revised
				.proveGeneratedCandidateSupport(published,
					published.allowedEmissionFacts().get(0), proposed, witness);
			NativePlacementContinuity.CandidateSupportResult cold = new NativePlacementContinuity(
				full.nodes, full.origins, publishedFacts, full.edges, full.reaching, Set.of(), full.privacy)
				.proveGeneratedCandidateSupport(published,
					published.allowedEmissionFacts().get(0), proposed, witness);

			Assert.assertEquals(cold.proofs(), actual.proofs());
			Assert.assertEquals(expected.proofs(), actual.proofs());
			assertIdentitySetEquals(cold.dependencyOccurrences(), actual.dependencyOccurrences());
			Assert.assertEquals("a prospective generated-root recurrence does not read published history",
				built, metrics.snapshot().proofGraphsBuilt());
			Assert.assertTrue(metrics.snapshot().supportMemoRevisionEntriesReused() > reused);
			full.candidates.set(full.candidates.indexOf(published), base);
		}
	}

	@Test
	public void generatedDerivedRootMetadataReadForcesWithdrawalAndRestorationRebuild() {
		assertGeneratedHiddenRootMetadataLifecycle(generatedHiddenRootFixture(true));
	}

	@Test
	public void generatedValueMapRootMetadataReadForcesWithdrawalAndRestorationRebuild() {
		assertGeneratedHiddenRootMetadataLifecycle(generatedHiddenRootFixture(false));
	}

	@Test
	public void generatedRootPrimitiveInputChangeDoesNotReusePublishedHistorySupport() throws Exception {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(root, Privacy.PRIVATE_AGGREGATE);
		CandidateRuleFact base = full.fact(root, List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization publication = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "primitive-change", List.of(), List.of());
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(), publication);
		NativePlacementContinuity first = full.resolver();
		Assert.assertFalse(first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor).isEmpty());
		CandidateRuleKey changedKey = new CandidateRuleKey(root.key, List.of(CandidateInputState.absentLocal()));
		CandidateRuleFact changed = new CandidateRuleFact(changedKey, base.status(), base.capability(),
			base.shapeProof(), base.profile(), base.allowedEmissionFacts(), base.failureCode());
		full.candidates.set(full.candidates.indexOf(base), changed);
		NativePlacementContinuity next = first.nextRevision(List.copyOf(full.candidates));
		Assert.assertTrue("changing the primitive input inventory must invalidate generated supports",
			((Map<?,?>)accessibleField(NativePlacementContinuity.class, "completedSupportMemo").get(next)).isEmpty());
		CandidateRealizationReference changedProposal = CandidateRealizationReference.of(changedKey, publication);
		Assert.assertEquals(full.resolver(null, 0, 0).proveGeneratedCandidateAlternatives(
			changed, emission, changedProposal, seed.anchor), next.proveGeneratedCandidateAlternatives(
			changed, emission, changedProposal, seed.anchor));
	}

	@Test
	public void generatedMissingNodeNegativeSupportStillMigratesWithoutSccLookup() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("missing", OpOp1.LOG, seed, false);
		CandidateRuleFact base = full.fact(root, List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(emission.emissionState(), "missing", List.of(), List.of()));
		full.privacy(seed, Privacy.PRIVATE_AGGREGATE);
		full.privacy(root, Privacy.PRIVATE_AGGREGATE);
		full.nodes.remove(root.key);
		full.origins.remove(root.key);
		full.edges.clear();
		NativePlacementContinuity first = full.resolver();
		Assert.assertTrue(first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor).isEmpty());
		NativePlacementContinuity next = first.nextRevision(List.copyOf(full.candidates));
		Assert.assertEquals(full.resolver(null, 0, 0).proveGeneratedCandidateAlternatives(
			base, emission, proposed, seed.anchor), next.proveGeneratedCandidateAlternatives(
			base, emission, proposed, seed.anchor));
	}

	@Test
	public void generatedRootRejectsStaleAndPrivateAuthority() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "generated-private", List.of(), List.of()));
		Fixture foreign = new Fixture(FType.FULL);
		Ref foreignSeed = foreign.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref foreignRoot = foreign.unary("root", OpOp1.LOG, foreignSeed, false);
		CandidateRuleFact foreignBase = foreign.fact(foreignRoot, inputs);
		Assert.assertTrue("a non-current trusted row must be rejected", full.resolver()
			.proveGeneratedCandidateAlternatives(foreignBase,
				foreignBase.allowedEmissionFacts().get(0), proposed, seed.anchor).isEmpty());
		CandidateRuleFact unsupported = new CandidateRuleFact(base.key(),
			CandidateEvaluationStatus.PRIVACY_EXCLUDED, base.capability(), base.shapeProof(), base.profile(),
			List.of(), "privacy-excluded-generator-base");
		int baseIndex = full.candidates.indexOf(base);
		full.candidates.set(baseIndex, unsupported);
		Assert.assertTrue("a privacy-excluded current row cannot authorize generation", full.resolver()
			.proveGeneratedCandidateAlternatives(unsupported, emission, proposed, seed.anchor).isEmpty());
		full.candidates.set(baseIndex, base);

		full.privacy(root, Privacy.PRIVATE);
		Assert.assertFalse("origin-resident private native operations remain legal", full.resolver()
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor).isEmpty());

	}

	@Test
	public void generatedRootAcceptsLoopCarriedAliasOnlyWithItsEntrySeed() {
		Fixture full = new Fixture(FType.FULL);
		Ref entry = full.source("X-entry", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref read = full.logicalRead("X-loop-read");
		Ref root = full.unary("loop-body", OpOp1.LOG, read, false);
		Ref carried = full.write("X-loop-write", root, NodeKind.LOOP_PHI, false);
		full.reaching.put(read.key, List.of(entry.key, carried.key));
		CandidateRuleFact base = full.fact(root,
			List.of(CandidateInputState.present(FType.FULL)));
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			CandidateEmissionRealization.nativeLineage(
				emission.emissionState(), "generated-loop-root", List.of(), List.of()));

		List<NativePlacementContinuity.NativeContinuityProof> proofs = full.resolver()
			.proveGeneratedCandidateAlternatives(base, emission, proposed, entry.anchor);

		Assert.assertFalse("entry FED authority must ground the complete loop-carried alias SCC",
			proofs.isEmpty());
		Assert.assertTrue("the generated root must bind the exact logical read realization",
			proofs.stream().allMatch(proof -> proof.immediateBindings().stream().anyMatch(binding ->
				binding.inputPosition() == 0
					&& binding.source().rule().parentOccurrence() == read.key)));
	}

	@Test
	public void generatedRootMemoIsModeLocalAndSourceRevisionInvalidatesIt() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact base = full.fact(root, inputs);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization proposedRealization = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "generated-memo", List.of(), List.of());
		CandidateRealizationReference unsupportedSeed = new CandidateRealizationReference(
			new CandidateRuleKey(seed.key, List.of()),
			PlacementIdentity.PlacementRealizationKey.durable(
				emission.emissionState(), seed.anchor));
		CandidateEmissionRealization declaredInvalid = new CandidateEmissionRealization(
			proposedRealization.key(), List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, unsupportedSeed)))));
		base = replaceRootRealization(full, base, emission, declaredInvalid);
		emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(base.key(),
			proposedRealization);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity first = full.resolver(metrics, 8, 128);
		Assert.assertTrue("declared empty support must remain invalid in validation mode",
			first.proveCandidateAlternatives(proposed, seed.anchor).isEmpty());
		List<NativePlacementContinuity.NativeContinuityProof> expected =
			first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertFalse(expected.isEmpty());
		Assert.assertTrue("generation must not contaminate validation caches",
			first.proveCandidateAlternatives(proposed, seed.anchor).isEmpty());
		long built = metrics.snapshot().proofGraphsBuilt();
		Assert.assertSame("same-revision generated query must reuse its exact result", expected,
			first.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor));
		Assert.assertEquals(built, metrics.snapshot().proofGraphsBuilt());
		long supportHits = metrics.snapshot().supportMemoHits();
		DurableAnchorKey equivalentWitness = anchor(
			FType.FULL, "worker1:8001", 100, 150);
		Assert.assertFalse(first.proveGeneratedCandidateAlternatives(
			base, emission, proposed, equivalentWitness).isEmpty());
		Assert.assertEquals("a new public seed with the same native witness reuses generated support",
			built, metrics.snapshot().proofGraphsBuilt());
		Assert.assertTrue(metrics.snapshot().supportMemoHits() > supportHits);
		full.candidateLogicalRead(seed);
		NativePlacementContinuity revised = first.nextRevision(List.copyOf(full.candidates));
		revised.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertTrue("a changed source row must invalidate generated proof cache entries",
			metrics.snapshot().proofGraphsBuilt() > built);
	}

	@Test
	public void generatedRootWithdrawsAndRestoresExactSourceSupportAcrossRevisions() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref producer = full.unary("producer", OpOp1.LOG, seed, false);
		List<CandidateInputState> oneInput =
			List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference producerReference = full.withClauses(producer, oneInput,
			List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRuleFact producerFact = full.fact(producer, oneInput);
		Ref root = full.unary("root", OpOp1.EXP, producer, false);
		CandidateRuleFact base = full.fact(root, oneInput);
		CandidateEmissionFact emission = base.allowedEmissionFacts().get(0);
		CandidateEmissionRealization oldSupportedRoot = CandidateEmissionRealization.nativeLineage(
			emission.emissionState(), "old-root-support", List.of(),
			List.of(CandidateRealizationInputBinding.direct(0, producerReference)));
		base = replaceRootRealization(full, base, emission, oldSupportedRoot);
		emission = base.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			base.key(), oldSupportedRoot);
		NativePlacementContinuity initial = full.resolver();
		List<NativePlacementContinuity.NativeContinuityProof> expected = initial
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertFalse(expected.isEmpty());

		full.candidates.remove(producerFact);
		List<CandidateRuleFact> withdrawnFacts = List.copyOf(full.candidates);
		NativePlacementContinuity withdrawn = initial.nextRevisionWithCompleteCandidateDelta(
			withdrawnFacts, identitySet(producer.key));
		List<NativePlacementContinuity.NativeContinuityProof> withdrawnActual = withdrawn
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertTrue("old root support cannot survive withdrawal of its exact source row",
			withdrawnActual.isEmpty());
		Assert.assertEquals(initial.nextRevision(withdrawnFacts)
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor), withdrawnActual);
		Assert.assertEquals(new NativePlacementContinuity(full.nodes, full.origins,
			withdrawnFacts, full.edges, full.reaching, Set.of(), full.privacy)
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor), withdrawnActual);

		full.candidates.add(producerFact);
		List<CandidateRuleFact> restoredFacts = List.copyOf(full.candidates);
		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			restoredFacts, identitySet(producer.key));
		List<NativePlacementContinuity.NativeContinuityProof> restoredActual = restored
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor);
		Assert.assertEquals("restoring the exact source row restores the generated proof relation",
			expected, restoredActual);
		Assert.assertEquals(withdrawn.nextRevision(restoredFacts)
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor), restoredActual);
		Assert.assertEquals(new NativePlacementContinuity(full.nodes, full.origins,
			restoredFacts, full.edges, full.reaching, Set.of(), full.privacy)
			.proveGeneratedCandidateAlternatives(base, emission, proposed, seed.anchor), restoredActual);
	}

	private static CandidateRuleFact replaceRootRealization(Fixture fixture,
		CandidateRuleFact prior, CandidateEmissionFact priorEmission,
		CandidateEmissionRealization replacement) {
		CandidateEmissionFact emission = new CandidateEmissionFact(priorEmission.emissionState(),
			priorEmission.executionFType(), priorEmission.derivedFoutAction(), List.of(replacement));
		CandidateRuleFact updated = new CandidateRuleFact(prior.key(), prior.status(), prior.capability(),
			prior.shapeProof(), prior.profile(), List.of(emission), prior.failureCode());
		fixture.candidates.set(fixture.candidates.indexOf(prior), updated);
		return updated;
	}

	private static GeneratedHiddenRootFixture generatedHiddenRootFixture(boolean derived) {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("hidden-seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("hidden-root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact initialRoot = full.fact(root, inputs);
		CandidateEmissionFact initialRootEmission = initialRoot.allowedEmissionFacts().get(0);
		CandidateEmissionRealization certifiedRealization = CandidateEmissionRealization.nativeLineage(
			initialRootEmission.emissionState(), "hidden-root-authority", seed.anchor,
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
				root.key, "hidden-root-authority")), List.of());
		CandidateRuleFact certifiedRoot = replaceRootRealization(
			full, initialRoot, initialRootEmission, certifiedRealization);
		CandidateEmissionFact certifiedRootEmission = certifiedRoot.allowedEmissionFacts().get(0);
		CandidateRealizationReference proposed = CandidateRealizationReference.of(
			certifiedRoot.key(), certifiedRealization);

		Ref hidden = full.read(derived ? "hidden-derived" : "hidden-value-map");
		CandidateRuleKey hiddenRule = new CandidateRuleKey(hidden.key, List.of());
		PlacementState resident = state(FType.FULL);
		PlacementEmissionState residentEmission = new PlacementEmissionState(resident, false);
		CandidateRuleFact hiddenFact;
		if(derived) {
			PlacementState local = new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false);
			PlacementState materializedState = new PlacementState(
				ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
			Node hiddenNode = full.nodes.get(hidden.key);
			full.nodes.put(hidden.key, new Node(hiddenNode.key(), hiddenNode.kind(),
				hiddenNode.valueVersion(), hiddenNode.emittedWork(), List.of(materializedState),
				hiddenNode.exclusions(), hiddenNode.anchors()));
			PlacementEmissionState localEmissionState = new PlacementEmissionState(local, false);
			DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
				hidden.key, full.nodes.get(hidden.key).valueVersion(), hiddenRule, local, materializedState,
				seed.anchor, root.key, FType.FULL, FType.FULL,
				hidden.key.controlRegion().normalizedSignature());
			CandidateEmissionFact localEmission = new CandidateEmissionFact(localEmissionState,
				null, null, List.of(CandidateEmissionRealization.local(localEmissionState)));
			CandidateEmissionFact materialized = new CandidateEmissionFact(
				new PlacementEmissionState(materializedState, true), FType.FULL, action,
				List.of(CandidateEmissionRealization.durable(
					new PlacementEmissionState(materializedState, true), seed.anchor,
					List.of(new PlacementProofKey(PlacementProofKind.DURABLE_ANCHOR,
						hidden.key, "derived-fout:" + action.normalizedSignature())), List.of())));
			hiddenFact = candidateFact(hiddenRule, List.of(localEmission, materialized), ExecType.FED);
		}
		else {
			CandidateRealizationSupportClause clause = new CandidateRealizationSupportClause(
				List.of(), List.of(CandidateRealizationInputBinding.direct(0, proposed)));
			CandidateEmissionRealization valueMap = CandidateEmissionRealization.valueMap(
				residentEmission, "hidden-root-value-map", List.of(clause));
			hiddenFact = candidateFact(hiddenRule, List.of(new CandidateEmissionFact(
				residentEmission, FType.FULL, null, List.of(valueMap))), ExecType.FED);
		}
		full.candidates.add(hiddenFact);
		full.edges.removeIf(edge -> edge.consumer() == root.key);
		full.edges.add(new CompiledInputEdgeFact(hidden.key, root.key, 0));

		CandidateEmissionRealization uncertifiedRealization = new CandidateEmissionRealization(
			certifiedRealization.key(), List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionFact uncertifiedEmission = new CandidateEmissionFact(
			certifiedRootEmission.emissionState(), certifiedRootEmission.executionFType(),
			certifiedRootEmission.derivedFoutAction(), List.of(uncertifiedRealization));
		CandidateRuleFact uncertifiedRoot = new CandidateRuleFact(certifiedRoot.key(),
			certifiedRoot.status(), certifiedRoot.capability(), certifiedRoot.shapeProof(),
			certifiedRoot.profile(), List.of(uncertifiedEmission), certifiedRoot.failureCode());
		List<CandidateRuleFact> activeFacts = List.copyOf(full.candidates);
		List<CandidateRuleFact> withdrawnFacts = activeFacts.stream()
			.map(fact -> fact == certifiedRoot ? uncertifiedRoot : fact).toList();
		return new GeneratedHiddenRootFixture(full, seed, root, hidden, certifiedRoot,
			certifiedRootEmission, proposed, activeFacts, withdrawnFacts);
	}

	private static CandidateRuleFact candidateFact(CandidateRuleKey rule,
		List<CandidateEmissionFact> emissions, ExecType execType) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "hidden-root", execType,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "hidden-root", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emissions, "");
	}

	private static void assertGeneratedHiddenRootMetadataLifecycle(GeneratedHiddenRootFixture fixture) {
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity initial = fixture.full().resolver(metrics, 128, 2048);
		NativePlacementContinuity.CandidateSupportResult positive = initial
			.proveGeneratedCandidateSupport(fixture.activeRoot(), fixture.activeEmission(),
				fixture.proposed(), fixture.seed().anchor);
		Assert.assertFalse(positive.proofs().isEmpty());
		Assert.assertTrue(positive.dependencyOccurrences().stream()
			.anyMatch(owner -> owner == fixture.root().key));
		assertGeneratedRootCertificate(initial, fixture.proposed(), fixture.hidden().key, false);
		long built = metrics.snapshot().proofGraphsBuilt();

		NativePlacementContinuity withdrawn = initial.nextRevisionWithCompleteCandidateDelta(
			fixture.withdrawnFacts(), identitySet(fixture.root().key));
		CandidateRuleFact withdrawnRoot = fixture.withdrawnFacts().stream()
			.filter(fact -> fact.key().parentOccurrence() == fixture.root().key).findFirst().orElseThrow();
		NativePlacementContinuity.CandidateSupportResult negative = withdrawn
			.proveGeneratedCandidateSupport(withdrawnRoot,
				withdrawnRoot.allowedEmissionFacts().get(0), fixture.proposed(), fixture.seed().anchor);
		NativePlacementContinuity.CandidateSupportResult coldNegative = new NativePlacementContinuity(
			fixture.full().nodes, fixture.full().origins, fixture.withdrawnFacts(), fixture.full().edges,
			fixture.full().reaching, Set.of(), fixture.full().privacy)
			.proveGeneratedCandidateSupport(withdrawnRoot,
				withdrawnRoot.allowedEmissionFacts().get(0), fixture.proposed(), fixture.seed().anchor);
		Assert.assertTrue(negative.proofs().isEmpty());
		Assert.assertEquals(coldNegative.proofs(), negative.proofs());
		assertIdentitySetEquals(coldNegative.dependencyOccurrences(), negative.dependencyOccurrences());
		Assert.assertTrue("a hidden published-root metadata read must invalidate the generated memo",
			metrics.snapshot().proofGraphsBuilt() > built);
		assertGeneratedRootCertificate(withdrawn, fixture.proposed(), fixture.hidden().key, false);
		long withdrawnBuilt = metrics.snapshot().proofGraphsBuilt();

		NativePlacementContinuity restored = withdrawn.nextRevisionWithCompleteCandidateDelta(
			fixture.activeFacts(), identitySet(fixture.root().key));
		NativePlacementContinuity.CandidateSupportResult restoredSupport = restored
			.proveGeneratedCandidateSupport(fixture.activeRoot(), fixture.activeEmission(),
				fixture.proposed(), fixture.seed().anchor);
		NativePlacementContinuity.CandidateSupportResult coldRestored = new NativePlacementContinuity(
			fixture.full().nodes, fixture.full().origins, fixture.activeFacts(), fixture.full().edges,
			fixture.full().reaching, Set.of(), fixture.full().privacy)
			.proveGeneratedCandidateSupport(fixture.activeRoot(), fixture.activeEmission(),
				fixture.proposed(), fixture.seed().anchor);
		Assert.assertFalse(restoredSupport.proofs().isEmpty());
		Assert.assertEquals(coldRestored.proofs(), restoredSupport.proofs());
		assertIdentitySetEquals(coldRestored.dependencyOccurrences(), restoredSupport.dependencyOccurrences());
		Assert.assertTrue(metrics.snapshot().proofGraphsBuilt() > withdrawnBuilt);
	}

	@SuppressWarnings("unchecked")
	private static void assertGeneratedRootCertificate(NativePlacementContinuity continuity,
		CandidateRealizationReference root, CompiledHopKey visitedOwner, boolean independent) {
		try {
			Map<?,?> memo = (Map<?,?>)accessibleField(
				NativePlacementContinuity.class, "completedSupportMemo").get(continuity);
			int visited = 0;
			for(var entry : memo.entrySet()) {
				Object query = entry.getKey(), support = entry.getValue();
				if(!(boolean)accessibleField(query.getClass(), "generated").get(query)
					|| !root.equals(accessibleField(support.getClass(), "root").get(support)))
					continue;
				Set<CompiledHopKey> footprint = (Set<CompiledHopKey>)accessibleField(
					support.getClass(), "occurrences").get(support);
				if(footprint.stream().noneMatch(owner -> owner == visitedOwner))
					continue;
				visited++;
				Assert.assertEquals("certify the actual traversal, not just revision invalidation",
					independent, accessibleField(support.getClass(), "rootIndependent").get(support));
			}
			Assert.assertTrue("the generated query must have visited the selected owner", visited > 0);
		}
		catch(ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

	private record GeneratedHiddenRootFixture(Fixture full, Ref seed, Ref root, Ref hidden,
		CandidateRuleFact activeRoot, CandidateEmissionFact activeEmission,
		CandidateRealizationReference proposed, List<CandidateRuleFact> activeFacts,
		List<CandidateRuleFact> withdrawnFacts) { }

	@SuppressWarnings("unchecked")
	private static List<?> candidateAlternatives(NativePlacementContinuity resolver,
		CandidateRealizationReference pinned, DurableAnchorKey seed,
		Map<CompiledHopKey,CandidateRealizationReference> fixed) throws Exception {
		Method nativeWitness = NativePlacementContinuity.class.getDeclaredMethod(
			"nativeWitness", DurableAnchorKey.class);
		nativeWitness.setAccessible(true);
		Object witness = nativeWitness.invoke(resolver, seed);
		Method candidateHandle = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateHandle", CandidateRealizationReference.class);
		candidateHandle.setAccessible(true);
		Map<CompiledHopKey,CandidateRealizationReference> exactFixed = new IdentityHashMap<>();
		exactFixed.putAll(fixed);
		Map<CompiledHopKey,Integer> handles = new IdentityHashMap<>();
		for(var entry : exactFixed.entrySet())
			handles.put(entry.getKey(), (int)candidateHandle.invoke(resolver, entry.getValue()));
		Class<?> generationRoot = Class.forName(
			NativePlacementContinuity.class.getName() + "$GenerationRoot");
		Method alternatives = NativePlacementContinuity.class.getDeclaredMethod(
			"candidateProofAlternatives", CompiledHopKey.class,
			CandidateRealizationReference.class, int.class, witness.getClass(), boolean.class,
			Map.class, Map.class, generationRoot);
		alternatives.setAccessible(true);
		return (List<?>)alternatives.invoke(resolver, pinned.rule().parentOccurrence(), pinned,
			(int)candidateHandle.invoke(resolver, pinned), witness, true, exactFixed, handles, null);
	}

	@Test
	public void proofHistoryDoesNotDuplicatePrivateTopologyRows() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRealizationReference reference = full.withClauses(root, inputs, List.of(
			new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, root.key, "route-a")), List.of()),
			new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, root.key, "route-b")), List.of())));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<NativePlacementContinuity.NativeContinuityProof> proofs =
			full.resolver(metrics, 0, 0).proveCandidateAlternatives(reference, seed.anchor);
		Fixture control = new Fixture(FType.FULL);
		Ref controlSeed = control.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref controlRoot = control.unary("root", OpOp1.LOG, controlSeed, false);
		CandidateRealizationReference controlReference = control.withClauses(controlRoot, inputs,
			List.of(new CandidateRealizationSupportClause(List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, controlRoot.key, "route-a")), List.of())));
		SearchSpaceMetrics controlMetrics = new SearchSpaceMetrics();
		Assert.assertEquals(1, control.resolver(controlMetrics, 0, 0)
			.proveCandidateAlternatives(controlReference, controlSeed.anchor).size());

		Assert.assertEquals(1, proofs.size());
		Assert.assertEquals("proof history must not add a private topology edge",
			controlMetrics.snapshot().topologyRowsBuilt(), metrics.snapshot().topologyRowsBuilt());
		Assert.assertTrue("each history-only duplicate must be collapsed before graph expansion",
			metrics.snapshot().topologyRowsCollapsed() > 0);
		Assert.assertEquals("public proof alternatives retain both authorities", 2,
			full.fact(root, inputs).allowedEmissionFacts().get(0).realizations().get(0)
				.supportClauses().size());
	}

	@Test
	public void rootPinCollapsesOnlyEffectivePrivateBackedgeDuplicates() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref root = full.unary("root", OpOp1.LOG, seed, false);
		full.edges.clear();
		full.edges.add(new CompiledInputEdgeFact(root.key, root.key, 0));
		List<CandidateInputState> inputs = List.of(CandidateInputState.present(FType.FULL));
		CandidateRuleFact fact = full.fact(root, inputs);
		var emission = fact.allowedEmissionFacts().get(0).emissionState();
		CandidateRealizationReference a = CandidateRealizationReference.of(fact.key(),
			CandidateEmissionRealization.durable(emission,
				new DurableAnchorKey("backedge-a", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))), List.of(), List.of()));
		CandidateRealizationReference b = CandidateRealizationReference.of(fact.key(),
			CandidateEmissionRealization.durable(emission,
				new DurableAnchorKey("backedge-b", FType.FULL,
					List.of(partition("worker1:8001", 0, 50))), List.of(), List.of()));
		CandidateRealizationReference selected = full.withClauses(root, inputs, List.of(
			new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, a))),
			new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, b)))));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		// This synthetic overlay tests root-pin deduplication, not program validity.
		full.resolver(metrics, 0, 0).proveCandidateAlternatives(selected, seed.anchor);
		Assert.assertEquals("different static clause pins must remain distinct", 0,
			metrics.snapshot().topologyRowsCollapsed());
		Assert.assertTrue("root pin makes the two effective private backedges identical",
			metrics.snapshot().topologyOverlayRowsCollapsed() > 0);
	}

	@Test
	public void alignedRowAndColElementwiseInputsPreserveBothExactBindings() {
		for(FType type : List.of(FType.ROW, FType.COL)) {
			Fixture fixture = new Fixture(type);
			DurableAnchorKey pool = anchor(type, "worker1:8001", 0, 50);
			Ref leftSeed = fixture.source("leftSeed", pool);
			Ref rightSeed = fixture.source("rightSeed", pool);
			Ref left = fixture.logicalRead("left");
			Ref right = fixture.logicalRead("right");
			fixture.reaching.put(left.key, List.of(leftSeed.key));
			fixture.reaching.put(right.key, List.of(rightSeed.key));
			Ref sum = fixture.binary("sum", OpOp2.PLUS, left, right, false);
			List<CandidateInputState> inputs = List.of(
				CandidateInputState.present(type), CandidateInputState.present(type));

			NativePlacementContinuity.NativeContinuityProof proof = fixture.resolver().proveCandidate(
				fixture.reference(sum, inputs), pool);
			Assert.assertNotNull(type + " elementwise inputs on one exact pool retain native continuity", proof);
			Assert.assertEquals("Both aligned operands must remain distinct AND dependencies for " + type,
				Set.of(0, 1), proof.immediateBindings().stream()
					.map(binding -> binding.inputPosition()).collect(java.util.stream.Collectors.toSet()));
			Assert.assertEquals("Repeated geometry must not collapse the two operand positions for " + type,
				2, proof.immediateBindings().size());
		}
	}

	@Test
	public void alignedElementwiseInputsRejectMixedTypeDifferentPoolAndUngroundedOperands() {
		Fixture mixed = new Fixture(FType.ROW);
		DurableAnchorKey rowPool = anchor(FType.ROW, "worker1:8001", 0, 50);
		Ref row = mixed.federatedSource("row", rowPool);
		Ref col = mixed.source("col", anchor(FType.COL, "worker1:8001", 0, 50));
		Ref mixedSum = mixed.binaryWithoutCandidate("mixedSum", OpOp2.PLUS, row, col);
		List<CandidateInputState> mixedInputs = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.COL));
		mixed.additionalCandidate(mixedSum, mixedInputs);
		Assert.assertNull("Every PRESENT elementwise input must have the output witness FType",
			mixed.resolver().proveCandidate(mixed.reference(mixedSum, mixedInputs), rowPool));

		Fixture differentPool = new Fixture(FType.ROW);
		Ref first = differentPool.federatedSource("first", rowPool);
		Ref other = differentPool.federatedSource("other",
			anchor(FType.ROW, "worker2:8002", 0, 50));
		Ref crossPool = differentPool.binary("crossPool", OpOp2.PLUS, first, other, false);
		List<CandidateInputState> rowInputs = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.ROW));
		Assert.assertNull("Matching FTypes do not replace exact common-pool grounding",
			differentPool.resolver().proveCandidate(differentPool.reference(crossPool, rowInputs), rowPool));

		Fixture ungrounded = new Fixture(FType.COL);
		DurableAnchorKey colPool = anchor(FType.COL, "worker1:8001", 0, 50);
		Ref grounded = ungrounded.federatedSource("grounded", colPool);
		Ref unknown = ungrounded.read("unknown");
		Ref incomplete = ungrounded.binary("incomplete", OpOp2.PLUS, grounded, unknown, false);
		List<CandidateInputState> colInputs = List.of(
			CandidateInputState.present(FType.COL), CandidateInputState.present(FType.COL));
		Assert.assertNull("Every aligned input still requires an exact grounded realization",
			ungrounded.resolver().proveCandidate(ungrounded.reference(incomplete, colInputs), colPool));
	}

	@Test
	public void fixedLoopEntryMakesCandidateGroundingRedundant() {
		Fixture full = new Fixture(FType.FULL);
		Ref entry = full.source("entry", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref read = full.logicalRead("read");
		Ref body = full.unary("body", OpOp1.LOG, read, false);
		Ref write = full.write("write", body, NodeKind.LOOP_PHI, false);
		full.reaching.put(read.key, List.of(entry.key, write.key));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		NativePlacementContinuity resolver = full.resolver(metrics, 0, 0);

		Assert.assertNotNull(resolver.proveCandidate(full.reference(body,
			List.of(CandidateInputState.present(FType.FULL))), entry.anchor));
		Assert.assertTrue("the recurrence must really be present", metrics.snapshot().cyclicProofGraphs() > 0);
		Assert.assertEquals("fixed entry/input relations make candidate SCC grounding redundant", 0,
			metrics.snapshot().sccInvocations());
		Assert.assertEquals("no source-grounding phase on the initialized loop", 0,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.PROOF_GROUNDING).calls());
	}

	@Test
	public void nestedLoopCandidateSupportsAreIndependentOfMemoization() throws Exception {
		for(FType type : List.of(FType.ROW, FType.COL, FType.FULL)) {
			Fixture fixture = new Fixture(type);
			Ref entry = fixture.source("entry", anchor(type, "worker1:8001", 0, 50));
			Ref outer = fixture.logicalRead("outer");
			Ref inner = fixture.logicalRead("inner");
			Ref body = fixture.unary("body", OpOp1.LOG, inner, false);
			Ref innerWrite = fixture.write("innerWrite", body, NodeKind.LOOP_PHI, false);
			Ref outerWrite = fixture.write("outerWrite", innerWrite, NodeKind.LOOP_PHI, false);
			fixture.reaching.put(outer.key, List.of(entry.key, outerWrite.key));
			fixture.reaching.put(inner.key, List.of(outer.key, innerWrite.key));
			SearchSpaceMetrics fastMetrics = new SearchSpaceMetrics();
			SearchSpaceMetrics referenceMetrics = new SearchSpaceMetrics();
			NativePlacementContinuity fast = fixture.resolver(fastMetrics, 256, 4096);
			NativePlacementContinuity reference = fixture.resolver(referenceMetrics, 0, 0);
			int checked = 0;
			for(CandidateRuleFact fact : fixture.candidates)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations()) {
						CandidateRealizationReference candidate = CandidateRealizationReference.of(fact.key(), realization);
						Assert.assertEquals("complete support relation for " + type + ':' + fact.key(),
							reference.proveCandidateAlternatives(candidate, entry.anchor),
							fast.proveCandidateAlternatives(candidate, entry.anchor));
						checked++;
					}
			Assert.assertTrue(checked > 0);
			Assert.assertTrue("the fixture must exercise loop recurrences",
				referenceMetrics.snapshot().cyclicProofGraphs() > 0);
			Assert.assertEquals(0, referenceMetrics.snapshot().sccInvocations());
			Assert.assertEquals(0, fastMetrics.snapshot().sccInvocations());
			Assert.assertEquals(0,
				fastMetrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.PROOF_GROUNDING).calls());
		}
	}

	@Test
	public void structuralRevisionCannotReuseAnIncompatibleLoopEntry() {
		Fixture full = new Fixture(FType.FULL);
		Ref entry = full.source("entry", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref read = full.logicalRead("read");
		Ref body = full.unary("body", OpOp1.LOG, read, false);
		Ref write = full.write("write", body, NodeKind.LOOP_PHI, false);
		full.reaching.put(read.key, List.of(entry.key, write.key));
		CandidateRealizationReference candidate = full.reference(body,
			List.of(CandidateInputState.present(FType.FULL)));
		NativePlacementContinuity first = full.resolver();
		Assert.assertNotNull(first.proveCandidate(candidate, entry.anchor));
		Ref incompatibleEntry = full.source("incompatibleEntry", anchor(FType.FULL, "worker2:8002", 0, 50));
		full.reaching.put(read.key, List.of(incompatibleEntry.key, write.key));
		NativePlacementContinuity revised = first.structuralRevision(full.nodes, full.origins,
			full.candidates, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertNull(revised.proveCandidate(candidate, entry.anchor));
		Assert.assertFalse(revised.proves(List.of(read.key), entry.anchor));
		full.reaching.put(read.key, List.of(entry.key, write.key));
		NativePlacementContinuity restored = revised.structuralRevision(full.nodes, full.origins,
			full.candidates, full.edges, full.reaching, Set.of(), full.privacy);
		Assert.assertEquals(first.proveCandidateAlternatives(candidate, entry.anchor),
			restored.proveCandidateAlternatives(candidate, entry.anchor));
	}

	@Test
	public void candidatePhysicalViabilityPreservesInitializedLoop() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref loop = full.logicalRead("loop");
		full.reaching.put(loop.key, List.of(loop.key, ground.key));
		Ref root = full.unary("root", OpOp1.ABS, loop, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNotNull("A cycle remains grounded when one complete AND alternative reaches direct ground",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(root,
				List.of(CandidateInputState.present(FType.FULL))), ground.anchor));
		Assert.assertTrue("the self-loop remains in the candidate graph",
			metrics.snapshot().cyclicProofGraphs() > 0);
		Assert.assertEquals("the mandatory entry makes every SCC grounding scan redundant", 0,
			metrics.snapshot().sccInvocations());
	}

	@Test
	public void candidateDeadPruningRejectsIncompleteAnd() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknown = full.read("unknown");
		Ref product = full.binaryWithoutCandidate("product", OpOp2.PLUS, ground, unknown);
		full.edges.clear();
		full.edges.add(new CompiledInputEdgeFact(product.key, product.key, 0));
		full.edges.add(new CompiledInputEdgeFact(unknown.key, product.key, 1));
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		full.additionalCandidate(product, inputs);
		full.inheritAnchor(product, ground.anchor);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNull("direct ground cannot discharge an incomplete AND alternative",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(product, inputs), ground.anchor));
		Assert.assertEquals("physical dead pruning requires no grounding scan", 0,
			metrics.snapshot().sccInvocations());
	}

	@Test
	public void candidateAcyclicChainGroundsDependenciesBeforeTheirOwners() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref dependency = full.logicalRead("dependency");
		Ref owner = full.logicalRead("owner");
		full.reaching.put(dependency.key, List.of(ground.key));
		full.reaching.put(owner.key, List.of(dependency.key));
		Ref root = full.unary("root", OpOp1.ABS, owner, false);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNotNull("an acyclic chain must ground every dependency before its owner",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(root,
				List.of(CandidateInputState.present(FType.FULL))), ground.anchor));
		Assert.assertTrue("the chain must use at least one acyclic proof graph",
			metrics.snapshot().acyclicProofGraphs() > 0);
		Assert.assertTrue("a dependency-closed grounded DAG needs no dead-seed scan",
			metrics.snapshot().proofNoEmptyDagPruningSkips() > 0);
		Assert.assertEquals("the acyclic chain must not invoke the SCC fallback", 0,
			metrics.snapshot().cyclicProofGraphs());
		Assert.assertEquals("dead pruning already establishes acyclic source support", 0,
			metrics.attributionSnapshot().phase(SearchSpaceMetrics.Phase.PROOF_GROUNDING).calls());
	}

	@Test
	public void candidateAcyclicDeadDependencyIsPrunedWithoutCyclicFallback() {
		Fixture full = new Fixture(FType.FULL);
		Ref ground = full.source("ground", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknown = full.read("unknown");
		Ref product = full.binary("product", OpOp2.PLUS, ground, unknown, false);
		List<CandidateInputState> inputs = List.of(
			CandidateInputState.present(FType.FULL), CandidateInputState.present(FType.FULL));
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();

		Assert.assertNull("an empty dependency state must remove its acyclic owner alternative",
			full.resolver(metrics, 0, 0).proveCandidate(full.reference(product, inputs), ground.anchor));
		Assert.assertEquals("the dead acyclic dependency must not invoke the SCC fallback", 0,
			metrics.snapshot().cyclicProofGraphs());
		Assert.assertTrue("the acyclic pruning pass must remove the dependent alternative",
			metrics.snapshot().acyclicAlternativesRemoved() > 0);
		Assert.assertEquals("an empty dependency forbids the no-dead-seed shortcut", 0,
			metrics.snapshot().proofNoEmptyDagPruningSkips());
	}

	@Test
	public void rejectsChangedPartitionAxisTransposeAndDifferentPool() {
		Fixture rowAppend = new Fixture(FType.ROW);
		Ref rowSeed = rowAppend.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref rbind = rowAppend.binary("rbind", OpOp2.RBIND, rowSeed, rowSeed, false);
		Assert.assertFalse(rowAppend.resolver().proves(List.of(rbind.key), rowSeed.anchor));

		Fixture rowTranspose = new Fixture(FType.ROW);
		Ref transposeSeed = rowTranspose.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref transpose = rowTranspose.transpose("transpose", transposeSeed, false);
		Assert.assertFalse(rowTranspose.resolver().proves(List.of(transpose.key), transposeSeed.anchor));
		Ref reverse = rowTranspose.reorg("reverse", ReOrgOp.REV, transposeSeed, false);
		Assert.assertFalse("Unspecified map-changing operations fail closed",
			rowTranspose.resolver().proves(List.of(reverse.key), transposeSeed.anchor));

		Fixture pools = new Fixture(FType.FULL);
		Ref first = pools.source("A", anchor(FType.FULL, "worker1:8001", 0, 4));
		Ref second = pools.source("C", anchor(FType.FULL, "worker2:8002", 0, 4));
		Ref mixed = pools.binary("mixed", OpOp2.CBIND, first, second, false);
		Assert.assertFalse(pools.resolver().proves(List.of(mixed.key), first.anchor));
	}

	@Test
	public void rejectsDerivedEmissionUnanchoredBranchesAndMultiRangeFull() {
		Fixture derived = new Fixture(FType.ROW);
		Ref seed = derived.source("A", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref derivedOnly = derived.binary("derived", OpOp2.CBIND, seed, seed, true);
		Assert.assertFalse(derived.resolver().proves(List.of(derivedOnly.key), seed.anchor));

		Fixture unanchored = new Fixture(FType.ROW);
		Ref good = unanchored.source("good", anchor(FType.ROW, "worker1:8001", 0, 4));
		Ref join = unanchored.read("join");
		Ref cycleA = unanchored.read("cycleA");
		Ref cycleB = unanchored.read("cycleB");
		unanchored.reaching.put(join.key, List.of(good.key, cycleA.key));
		Ref otherEntry = unanchored.source("otherEntry", anchor(FType.ROW, "worker2:8002", 0, 4));
		unanchored.reaching.put(cycleA.key, List.of(otherEntry.key, cycleB.key));
		unanchored.reaching.put(cycleB.key, List.of(cycleA.key));
		Assert.assertFalse("Every reaching source must have a compatible worker pool",
			unanchored.resolver().proves(List.of(join.key), good.anchor));

		Fixture multi = new Fixture(FType.FULL);
		DurableAnchorKey multiRange = new DurableAnchorKey("multi", FType.FULL, List.of(
			partition("worker1:8001", 0, 2), partition("worker1:8001", 2, 4)));
		Ref multiSource = multi.source("multi", multiRange);
		Assert.assertFalse(multi.resolver().proves(List.of(multiSource.key), multiRange));
	}

	@Test
	public void provesRightIndexOnlyForSingleEndpointFullInput() {
		Fixture full = new Fixture(FType.FULL);
		Ref functionInput = full.source("X_orig", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref selectedColumn = full.rightIndex("X_orig[,column]", functionInput);
		Ref initialWrite = full.write("X_global", selectedColumn, NodeKind.TRANSIENT_WRITE, false);
		Assert.assertTrue("FULL right indexing filters and resizes the map but retains its sole worker endpoint",
			full.resolver().proves(List.of(initialWrite.key), functionInput.anchor));

		Fixture singleRow = new Fixture(FType.ROW);
		Ref singleRowInput = singleRow.source("single", anchor(FType.ROW, "worker1:8001", 0, 50));
		singleRowInput.hop.setDim1(50);
		Ref singleRowSlice = singleRow.rightIndex("single[,column]", singleRowInput);
		Assert.assertFalse("runtime filter retypes a single ROW partition to FULL",
			singleRow.resolver().proves(List.of(singleRowSlice.key), singleRowInput.anchor));

		Fixture row = new Fixture(FType.ROW);
		DurableAnchorKey partitioned = new DurableAnchorKey("two-row-workers", FType.ROW, List.of(
			partition("worker1:8001", 0, 25), partition("worker2:8002", 25, 50)));
		Ref rowInput = row.source("X", partitioned);
		rowInput.hop.setDim1(50);
		Ref rowSlice = row.rightIndex("X[,column]", rowInput);
		Assert.assertTrue("a proven full-row column slice preserves every ROW worker and row interval",
			row.resolver().proves(List.of(rowSlice.key), rowInput.anchor));
		Ref partialRowSlice = row.rightIndex("X[2:50,column]", rowInput, new LiteralOp(2L));
		Assert.assertFalse("partial ROW indexing can filter or resize the partition axis",
			row.resolver().proves(List.of(partialRowSlice.key), rowInput.anchor));
		Ref dynamicRowSlice = row.rightIndex("X[unknown:50,column]", rowInput,
			new DataOp("unknown", DataType.SCALAR, ValueType.INT64, OpOpData.TRANSIENTREAD,
				"unknown", -1, -1, -1, 1000));
		Assert.assertFalse("unknown ROW bounds cannot prove that every worker survives",
			row.resolver().proves(List.of(dynamicRowSlice.key), rowInput.anchor));
	}

	@Test
	public void protectedDynamicColumnSliceKeepsExactRowPoolThroughCbind() {
		Fixture row = new Fixture(FType.ROW);
		DurableAnchorKey partitioned = new DurableAnchorKey("two-row-workers", FType.ROW, List.of(
			partition("worker1:8001", 0, 25), partition("worker2:8002", 25, 50)));
		Ref source = row.source("X_orig", partitioned);
		source.hop.setDim1(50);
		row.privacy(source, Privacy.PRIVATE_AGGREGATE);
		Hop selectedColumn = new DataOp("column_best", DataType.SCALAR, ValueType.INT64,
			OpOpData.TRANSIENTREAD, "column_best", -1, -1, -1, 1000);
		Ref column = row.rightIndex("X_orig[,column_best]", source,
			new LiteralOp(1L), new UnaryOp("nrow", DataType.SCALAR, ValueType.INT64,
				OpOp1.NROW, source.hop), selectedColumn, selectedColumn);
		Ref appended = row.binary("X_global=cbind", OpOp2.CBIND, column, column, false);
		Assert.assertTrue("full-row dynamic column selection keeps the exact ROW axis",
			row.resolver().proves(List.of(column.key), partitioned));
		Assert.assertTrue("aligned ROW cbind keeps every worker and row interval",
			row.resolver().proves(List.of(appended.key), partitioned));
	}

	@Test
	public void provesNativeElementwiseChainThroughLogicalRead() {
		for(OpOp3 ternaryOp : List.of(OpOp3.PLUS_MULT, OpOp3.MINUS_MULT, OpOp3.IFELSE)) {
			Fixture full = new Fixture(FType.FULL);
			Ref seed = full.source("X", anchor(FType.FULL, "worker1:8001", 0, 50));
			Ref product = full.nary("weights", OpOpN.MULT, false, seed, seed);
			Ref ternary = full.ternary("updated", ternaryOp, product, seed, seed);
			Ref replaced = full.replace("finite", ternary);
			Ref write = full.write("X_global", replaced, NodeKind.TRANSIENT_WRITE, false);
			Ref read = full.logicalRead("X_global");
			full.reaching.put(read.key, List.of(write.key));

			Assert.assertTrue("Native nary/ternary/replace kernels copy the selected FULL input map: "
				+ ternaryOp, full.resolver().proves(List.of(read.key), seed.anchor));
		}
	}

	@Test
	public void provesSelectiveUnaryAndSameEndpointFullBinaryContinuity() {
		Fixture full = new Fixture(FType.FULL);
		DurableAnchorKey seedAnchor = anchor(FType.FULL, "worker1:8001", 0, 50);
		Ref seed = full.source("seed", seedAnchor);
		Ref sameWorker = full.source("sameWorker",
			anchor(FType.FULL, "worker1:8001", 70, 90));
		Ref logged = full.unary("logged", OpOp1.LOG, seed, false);
		Ref sum = full.binary("sum", OpOp2.PLUS, logged, sameWorker, false);

		Assert.assertTrue("UnaryElemwiseRule kernels copy the selected native map",
			full.resolver().proves(List.of(logged.key), seedAnchor));
		Assert.assertTrue("Two exact PRESENT FULL operands on one worker retain that worker",
			full.resolver().proves(List.of(sum.key), seedAnchor));
	}

	@Test
	public void selectiveUnaryAndFullBinaryContinuityFailClosed() {
		Fixture missingUnaryRow = new Fixture(FType.FULL);
		Ref missingUnarySeed = missingUnaryRow.source("missingUnarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref missingLog = missingUnaryRow.unary("missingLog", OpOp1.LOG, missingUnarySeed, false);
		missingUnaryRow.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == missingLog.key);
		missingUnaryRow.inheritAnchor(missingLog, missingUnarySeed.anchor);
		Assert.assertFalse("An inherited anchor cannot replace an AVAILABLE native unary row",
			missingUnaryRow.resolver().proves(List.of(missingLog.key), missingUnarySeed.anchor));

		Fixture missingBinaryRow = new Fixture(FType.FULL);
		Ref missingBinarySeed = missingBinaryRow.source("missingBinarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref missingPlus = missingBinaryRow.binary("missingPlus", OpOp2.PLUS,
			missingBinarySeed, missingBinarySeed, false);
		missingBinaryRow.candidates.removeIf(candidate -> candidate.key().parentOccurrence() == missingPlus.key);
		missingBinaryRow.inheritAnchor(missingPlus, missingBinarySeed.anchor);
		Assert.assertFalse("An inherited anchor cannot replace an AVAILABLE native two-matrix row",
			missingBinaryRow.resolver().proves(List.of(missingPlus.key), missingBinarySeed.anchor));

		Fixture unsupportedUnary = new Fixture(FType.FULL);
		Ref unarySeed = unsupportedUnary.source("unarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cumulative = unsupportedUnary.unary("cumulative", OpOp1.CUMSUM, unarySeed, false);
		Assert.assertFalse("Cumulative unary kernels do not use the non-cumulative map-copy proof",
			unsupportedUnary.resolver().proves(List.of(cumulative.key), unarySeed.anchor));

		Fixture endpoint = new Fixture(FType.FULL);
		Ref first = endpoint.source("first", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = endpoint.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref different = endpoint.binary("different", OpOp2.PLUS, first, other, false);
		Assert.assertFalse("FULL operands on different workers cannot share native continuity",
			endpoint.resolver().proves(List.of(different.key), first.anchor));

		Fixture unknown = new Fixture(FType.FULL);
		Ref known = unknown.source("known", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknownRead = unknown.read("unknown");
		Ref unknownBinary = unknown.binary("unknownBinary", OpOp2.PLUS, known, unknownRead, false);
		Assert.assertFalse("Every PRESENT FULL operand requires exact endpoint authority",
			unknown.resolver().proves(List.of(unknownBinary.key), known.anchor));

		Fixture multi = new Fixture(FType.FULL);
		Ref single = multi.source("single", anchor(FType.FULL, "worker1:8001", 0, 50));
		DurableAnchorKey multiRange = new DurableAnchorKey("multi", FType.FULL, List.of(
			partition("worker1:8001", 0, 25), partition("worker1:8001", 25, 50)));
		Ref ranges = multi.source("ranges", multiRange);
		Ref multiBinary = multi.binary("multiBinary", OpOp2.PLUS, single, ranges, false);
		Assert.assertFalse("The direct FULL/FULL runtime requires one range per operand",
			multi.resolver().proves(List.of(multiBinary.key), single.anchor));

		Fixture unsupportedBinary = new Fixture(FType.FULL);
		Ref binarySeed = unsupportedBinary.source("binarySeed",
			anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref solve = unsupportedBinary.binary("solve", OpOp2.SOLVE, binarySeed, binarySeed, false);
		Assert.assertFalse("Only BinaryElemwiseRule kernels use the two-matrix map-copy proof",
			unsupportedBinary.resolver().proves(List.of(solve.key), binarySeed.anchor));

		Fixture cycle = new Fixture(FType.FULL);
		Ref cycleSeed = cycle.source("cycleSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = cycle.read("cycleA");
		Ref cycleB = cycle.read("cycleB");
		Ref otherEntry = cycle.source("otherEntry", anchor(FType.FULL, "worker2:8002", 0, 50));
		cycle.reaching.put(cycleA.key, List.of(otherEntry.key, cycleB.key));
		cycle.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref cyclicBinary = cycle.binary("cyclicBinary", OpOp2.PLUS, cycleSeed, cycleA, false);
		Assert.assertFalse("A matching branch cannot make another loop entry physically compatible",
			cycle.resolver().proves(List.of(cyclicBinary.key), cycleSeed.anchor));
	}

	@Test
	public void rejectsElementwiseChainWithoutOneGroundedNativePool() {
		Fixture unknown = new Fixture(FType.FULL);
		Ref unknownSeed = unknown.source("seed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref unknownRead = unknown.read("unknown");
		Ref unknownProduct = unknown.nary("unknownProduct", OpOpN.MULT, false, unknownSeed, unknownRead);
		Assert.assertFalse("An unknown selected matrix input cannot borrow another input's native map",
			unknown.resolver().proves(List.of(unknownProduct.key), unknownSeed.anchor));

		Fixture endpoint = new Fixture(FType.FULL);
		Ref first = endpoint.source("first", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref other = endpoint.source("other", anchor(FType.FULL, "worker2:8002", 0, 50));
		Ref mixed = endpoint.nary("mixed", OpOpN.MULT, false, first, other);
		Assert.assertFalse("Every selected matrix input must have the exact witnessed endpoint",
			endpoint.resolver().proves(List.of(mixed.key), first.anchor));

		Fixture cycle = new Fixture(FType.FULL);
		Ref cycleSeed = cycle.source("cycleSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref cycleA = cycle.read("cycleA");
		Ref cycleB = cycle.read("cycleB");
		Ref otherEntry = cycle.source("otherEntry", anchor(FType.FULL, "worker2:8002", 0, 50));
		cycle.reaching.put(cycleA.key, List.of(otherEntry.key, cycleB.key));
		cycle.reaching.put(cycleB.key, List.of(cycleA.key));
		Ref cyclicProduct = cycle.nary("cyclicProduct", OpOpN.MULT, false, cycleSeed, cycleA);
		Assert.assertFalse("Every loop entry must have a compatible pool, including other AND inputs",
			cycle.resolver().proves(List.of(cyclicProduct.key), cycleSeed.anchor));

		Fixture evidence = new Fixture(FType.FULL);
		Ref evidenceSeed = evidence.source("evidenceSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref derivedOnly = evidence.nary("derivedOnly", OpOpN.MULT, true, evidenceSeed, evidenceSeed);
		Assert.assertFalse("Derived FED/FOUT is not native map-copy evidence",
			evidence.resolver().proves(List.of(derivedOnly.key), evidenceSeed.anchor));
		Ref noNativeRow = evidence.naryWithoutCandidate("noNativeRow", OpOpN.MULT,
			evidenceSeed, evidenceSeed);
		Assert.assertFalse("A supported Hop opcode still requires an actual native FED/FOUT candidate row",
			evidence.resolver().proves(List.of(noNativeRow.key), evidenceSeed.anchor));
	}

	@Test
	public void rejectsUnsupportedMembersOfTernaryAndNaryClasses() {
		Fixture full = new Fixture(FType.FULL);
		Ref seed = full.source("X", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref ctable = full.ternary("ctable", OpOp3.CTABLE, seed, seed, seed);
		Assert.assertFalse("CTABLE changes topology and must not inherit elementwise ternary authority",
			full.resolver().proves(List.of(ctable.key), seed.anchor));
		Ref append = full.nary("append", OpOpN.CBIND, false, seed, seed);
		Assert.assertFalse("Nary append requires a topology proof and is not a BuiltinNary map copy",
			full.resolver().proves(List.of(append.key), seed.anchor));
	}

	@Test
	public void candidateProofDistinguishesDynamicReorgResidencyFromExactAxisContinuity() {
		Fixture row = new Fixture(FType.ROW);
		Ref rowSeed = row.source("rowSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref reverse = row.reorg("reverse", ReOrgOp.REV, rowSeed, false);
		NativePlacementContinuity.NativeContinuityProof reverseProof = row.resolver().proveCandidate(
			row.reference(reverse, List.of(CandidateInputState.present(FType.ROW))), rowSeed.anchor);
		Assert.assertNotNull("ROW reverse keeps worker residency even though endpoint-to-range ownership changes",
			reverseProof);
		Assert.assertFalse("ROW reverse must not publish exact partition ranges",
			reverseProof.exactPartitionRanges());

		Ref diag = row.reorg("diag", ReOrgOp.DIAG, rowSeed, false);
		NativePlacementContinuity.NativeContinuityProof diagProof = row.resolver().proveCandidate(
			row.reference(diag, List.of(CandidateInputState.present(FType.ROW))), rowSeed.anchor);
		Assert.assertNotNull("DIAG keeps native worker residency", diagProof);
		Assert.assertFalse("DIAG recomputes partition ranges", diagProof.exactPartitionRanges());

		Ref roll = row.reorg("roll", ReOrgOp.ROLL, rowSeed, false);
		NativePlacementContinuity.NativeContinuityProof rollProof = row.resolver().proveCandidate(
			row.reference(roll, List.of(CandidateInputState.present(FType.ROW))), rowSeed.anchor);
		Assert.assertNotNull("ROW ROLL retains native worker residency when ranges split", rollProof);
		Assert.assertFalse("ROW ROLL publishes dynamic ranges computed from the runtime shift",
			rollProof.exactPartitionRanges());

		Fixture full = new Fixture(FType.FULL);
		Ref fullSeed = full.source("fullSeed", anchor(FType.FULL, "worker1:8001", 0, 50));
		Ref fullRoll = full.reorg("fullRoll", ReOrgOp.ROLL, fullSeed, false);
		NativePlacementContinuity.NativeContinuityProof fullRollProof = full.resolver().proveCandidate(
			full.reference(fullRoll, List.of(CandidateInputState.present(FType.FULL))), fullSeed.anchor);
		Assert.assertNotNull("FULL ROLL retains its single-worker native residency", fullRollProof);
		Assert.assertFalse("FULL ROLL may split the runtime map and therefore cannot publish exact ranges",
			fullRollProof.exactPartitionRanges());

		Fixture col = new Fixture(FType.COL);
		Ref colSeed = col.source("colSeed", anchor(FType.COL, "worker1:8001", 0, 50));
		Ref colReverse = col.reorg("colReverse", ReOrgOp.REV, colSeed, false);
		NativePlacementContinuity.NativeContinuityProof colReverseProof = col.resolver().proveCandidate(
			col.reference(colReverse, List.of(CandidateInputState.present(FType.COL))), colSeed.anchor);
		Assert.assertNotNull("COL reverse does not reverse partition ownership", colReverseProof);
		Assert.assertTrue("COL reverse preserves its partition-axis intervals",
			colReverseProof.exactPartitionRanges());
		Ref colRoll = col.reorg("colRoll", ReOrgOp.ROLL, colSeed, false);
		NativePlacementContinuity.NativeContinuityProof colRollProof = col.resolver().proveCandidate(
			col.reference(colRoll, List.of(CandidateInputState.present(FType.COL))), colSeed.anchor);
		Assert.assertNotNull("COL ROLL retains native worker residency while runtime ranges split", colRollProof);
		Assert.assertFalse("COL ROLL must not publish the pre-roll durable geometry",
			colRollProof.exactPartitionRanges());
	}

	@Test
	public void candidateProofSupportsDynamicReshapeAndDeterministicRexpandAxisChanges() {
		Fixture reshapeFixture = new Fixture(FType.ROW);
		Ref reshapeSeed = reshapeFixture.source("reshapeSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref reshape = reshapeFixture.reshape("reshape", reshapeSeed, false, FType.COL);
		NativePlacementContinuity.NativeContinuityProof reshapeProof = reshapeFixture.resolver().proveCandidate(
			reshapeFixture.reference(reshape, List.of(CandidateInputState.present(FType.ROW),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal())), reshapeSeed.anchor);
		Assert.assertNotNull("RESHAPE retains the same native endpoints", reshapeProof);
		Assert.assertFalse("RESHAPE recomputes partition extents", reshapeProof.exactPartitionRanges());
		Assert.assertEquals(FType.COL, reshapeProof.outputWorkerPoolWitness().fType());

		Fixture rexpandFixture = new Fixture(FType.ROW);
		Ref expandSeed = rexpandFixture.source("expandSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref expandRows = rexpandFixture.rexpand("expandRows", expandSeed, "rows", FType.COL);
		NativePlacementContinuity.NativeContinuityProof rowsProof = rexpandFixture.resolver().proveCandidate(
			rexpandFixture.reference(expandRows, rexpandFixture.inputStates(expandRows, 0, FType.ROW)),
			expandSeed.anchor);
		Assert.assertNotNull("REXPAND rows transposes the native ROW axis into COL", rowsProof);
		Assert.assertTrue("REXPAND rows preserves the exact partition-axis intervals under the transpose",
			rowsProof.exactPartitionRanges());
		Assert.assertEquals(FType.COL, rowsProof.outputWorkerPoolWitness().fType());

		Ref expandCols = rexpandFixture.rexpand("expandCols", expandSeed, "cols", FType.ROW);
		NativePlacementContinuity.NativeContinuityProof colsProof = rexpandFixture.resolver().proveCandidate(
			rexpandFixture.reference(expandCols, rexpandFixture.inputStates(expandCols, 0, FType.ROW)),
			expandSeed.anchor);
		Assert.assertNotNull("REXPAND cols preserves the native ROW axis", colsProof);
		Assert.assertTrue(colsProof.exactPartitionRanges());
	}

	@Test
	public void candidateProofSupportsCumulativeCastsAndFrameMapCopy() {
		Fixture row = new Fixture(FType.ROW);
		Ref seed = row.source("X", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref cumulative = row.unary("cumulative", OpOp1.CUMSUM, seed, false);
		Assert.assertNotNull("ROW cumulative kernels preserve their native partition axis",
			row.resolver().proveCandidate(
				row.reference(cumulative, List.of(CandidateInputState.present(FType.ROW))), seed.anchor));

		Ref frame = row.cast("frame", seed, DataType.FRAME, ValueType.STRING, OpOp1.CAST_AS_FRAME);
		NativePlacementContinuity.NativeContinuityProof frameProof = row.resolver().proveCandidate(
			row.reference(frame, List.of(CandidateInputState.present(FType.ROW))), seed.anchor);
		Assert.assertNotNull("Matrix-to-frame cast copies the native map", frameProof);
		Assert.assertTrue(frameProof.exactPartitionRanges());

		Ref frameSeed = row.frameSource("frameSeed", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref mapped = row.frameMap("mapped", frameSeed);
		NativePlacementContinuity.NativeContinuityProof mapProof = row.resolver().proveCandidate(
			row.reference(mapped, row.inputStates(mapped, 0, FType.ROW)), frameSeed.anchor);
		Assert.assertNotNull("Frame MAP copies the selected frame FederationMap", mapProof);
		Assert.assertTrue(mapProof.exactPartitionRanges());

		Ref matrix = row.cast("matrix", frameSeed, DataType.MATRIX, ValueType.FP64, OpOp1.CAST_AS_MATRIX);
		NativePlacementContinuity.NativeContinuityProof matrixProof = row.resolver().proveCandidate(
			row.reference(matrix, List.of(CandidateInputState.present(FType.ROW))), frameSeed.anchor);
		Assert.assertNotNull("Frame-to-matrix cast copies the native map", matrixProof);
		Assert.assertTrue(matrixProof.exactPartitionRanges());
	}

	@Test
	public void candidateProofSupportsWeightedQuaternaryNativeOutputFamilies() {
		Fixture row = new Fixture(FType.ROW);
		Ref xRow = row.source("Xrow", anchor(FType.ROW, "worker1:8001", 0, 50));
		Ref uRow = row.read("Urow");
		Ref vRow = row.read("Vrow");
		for(Ref weighted : List.of(row.wsigmoid("wsigmoid", xRow, uRow, vRow),
			row.wumm("wumm", xRow, uRow, vRow), row.wdivmm("wdivmmBasic", xRow, uRow, vRow, 0, FType.ROW),
			row.wdivmm("wdivmmRight", xRow, uRow, vRow, 2, FType.ROW))) {
			NativePlacementContinuity.NativeContinuityProof proof = row.resolver().proveCandidate(
				row.reference(weighted, row.inputStates(weighted, 0, FType.ROW)), xRow.anchor);
			Assert.assertNotNull(weighted.hop.getName() + " preserves X's native worker pool", proof);
			Assert.assertTrue(weighted.hop.getName() + " preserves X's partition-axis intervals",
				proof.exactPartitionRanges());
		}

		Fixture col = new Fixture(FType.COL);
		Ref xCol = col.source("Xcol", anchor(FType.COL, "worker1:8001", 0, 50));
		Ref uCol = col.read("Ucol");
		Ref vCol = col.read("Vcol");
		Ref left = col.wdivmm("wdivmmLeft", xCol, uCol, vCol, 1, FType.ROW);
		NativePlacementContinuity.NativeContinuityProof leftProof = col.resolver().proveCandidate(
			col.reference(left, col.inputStates(left, 0, FType.COL)), xCol.anchor);
		Assert.assertNotNull("WDIVMM LEFT transposes the exact COL partition axis into output ROW", leftProof);
		Assert.assertTrue(leftProof.exactPartitionRanges());
		Assert.assertEquals(FType.ROW, leftProof.outputWorkerPoolWitness().fType());
	}

	private static List<CandidateRealizationInputBinding> productAxis(Fixture fixture,
		Ref source, List<CandidateInputState> inputs, int inputPosition) {
		CandidateRuleFact fact = fixture.fact(source, inputs);
		return fact.allowedEmissionFacts().get(0).realizations().stream()
			.map(realization -> CandidateRealizationInputBinding.direct(inputPosition,
				CandidateRealizationReference.of(fact.key(), realization)))
			.sorted().toList();
	}

	private static List<CandidateRealizationInputBinding> repositionAxis(
		List<CandidateRealizationInputBinding> axis, int inputPosition) {
		return axis.stream().map(binding -> CandidateRealizationInputBinding.direct(
			inputPosition, binding.source())).sorted().toList();
	}

	private static NativeContinuitySupportClauses productClauses(CompiledHopKey owner,
		DurableAnchorKey outputPool, List<List<CandidateRealizationInputBinding>> axes,
		DurableAnchorKey clauseWitness, boolean clauseLayoutExact) {
		NativePlacementContinuity.NativeSupportProduct product =
			NativePlacementContinuity.NativeSupportProduct.tryCreate(
				outputPool, outputPool, true, axes);
		Assert.assertNotNull("fixture must remain an admissible independent product", product);
		return new NativeContinuitySupportClauses(
			owner, product, clauseWitness, clauseLayoutExact);
	}

	private static CandidateRuleFact productFact(CandidateRuleFact template,
		CandidateEmissionFact emission, List<CandidateRealizationSupportClause> clauses) {
		return productFact(template, emission,
			new CandidateEmissionRealization(emission.realizations().get(0).key(), clauses));
	}

	private static CandidateRuleFact productFact(CandidateRuleFact template,
		CandidateEmissionFact emission, CandidateEmissionRealization realization) {
		CandidateEmissionFact replacement = new CandidateEmissionFact(emission.emissionState(),
			emission.executionFType(), emission.derivedFoutAction(), List.of(realization));
		return new CandidateRuleFact(template.key(), template.status(), template.capability(),
			template.shapeProof(), template.profile(), List.of(replacement), template.failureCode());
	}

	private static Object continuityProjection(List<CandidateRuleFact> facts) throws Exception {
		Method projection = NativePlacementContinuity.class.getDeclaredMethod(
			"continuityProjection", List.class);
		projection.setAccessible(true);
		return projection.invoke(null, facts);
	}

	private static List<CandidateRuleFact> replaceFact(List<CandidateRuleFact> facts,
		CandidateRuleFact before, CandidateRuleFact after) {
		List<CandidateRuleFact> replaced = new ArrayList<>(facts);
		int index = replaced.indexOf(before);
		Assert.assertTrue("fixture fact must be present", index >= 0);
		replaced.set(index, after);
		return List.copyOf(replaced);
	}

	private static final class CountingList<E> extends java.util.AbstractList<E> {
		private final List<E> values;
		private int gets;

		private CountingList(List<E> values) {
			this.values = List.copyOf(values);
		}

		@Override public E get(int index) {
			gets++;
			return values.get(index);
		}
		@Override public int size() { return values.size(); }
		private int gets() { return gets; }
		private void reset() { gets = 0; }
	}

	private static final class Fixture {
		private final String fingerprint = "native-continuity-" + System.identityHashCode(this);
		private final FType fType;
		private final Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Hop> origins = new IdentityHashMap<>();
		private final List<CandidateRuleFact> candidates = new ArrayList<>();
		private final List<CompiledInputEdgeFact> edges = new ArrayList<>();
		private final Map<CompiledHopKey,List<CompiledHopKey>> reaching = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Privacy> privacy = new IdentityHashMap<>();
		private int ordinal;

		private Fixture(FType fType) {
			this.fType = fType;
		}

		private Ref source(String name, DurableAnchorKey anchor) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, 4, 2, 8, 1000);
			return add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, anchor);
		}

		private Ref frameSource(String name, DurableAnchorKey anchor) {
			DataOp hop = new DataOp(name, DataType.FRAME, ValueType.STRING, OpOpData.TRANSIENTREAD,
				name, 4, 2, 8, 1000);
			return add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, anchor);
		}

		private Ref federatedSource(String name, DurableAnchorKey anchor) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED,
				name, 4, 2, 8, 1000);
			Ref source = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, anchor);
			candidateFederatedSource(source);
			return source;
		}

		private Ref read(String name) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, -1, -1, -1, 1000);
			return add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY, null);
		}

		private Ref logicalRead(String name) {
			Ref read = read(name);
			candidateLogicalRead(read);
			return read;
		}

		private Ref write(String name, Ref input, NodeKind kind, boolean derivedEmission) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, input.hop,
				OpOpData.TRANSIENTWRITE, name);
			Ref result = add(name, hop, kind, VersionKind.LOOP_BACKEDGE, null);
			candidate(result, List.of(input), derivedEmission);
			return result;
		}

		private Ref binary(String name, OpOp2 op, Ref left, Ref right, boolean derivedEmission) {
			Ref result = add(name, new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, left.hop, right.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(left, right), derivedEmission);
			return result;
		}

		private Ref binaryWithoutCandidate(String name, OpOp2 op, Ref left, Ref right) {
			Ref result = add(name, new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, left.hop, right.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			edges.add(new CompiledInputEdgeFact(left.key, result.key, 0));
			edges.add(new CompiledInputEdgeFact(right.key, result.key, 1));
			return result;
		}

		private Ref unary(String name, OpOp1 op, Ref input, boolean derivedEmission) {
			Ref result = add(name, new UnaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, input.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(input), derivedEmission);
			return result;
		}

		private Ref cast(String name, Ref input, DataType outputType, ValueType valueType, OpOp1 op) {
			Ref result = add(name, new UnaryOp(name, outputType, valueType, op, input.hop),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(input), false);
			return result;
		}

		private void privacy(Ref ref, Privacy value) {
			privacy.put(ref.key, value);
		}

		private Ref broadcastAlias(String name, Ref source) {
			DataOp hop = new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
				name, 4, 2, 8, 1000);
			Ref alias = add(name, hop, NodeKind.TRANSIENT_READ, VersionKind.ORDINARY,
				anchor(FType.BROADCAST, "worker1:8001", 0, 50));
			Node node = nodes.get(alias.key);
			PlacementState broadcast = state(FType.BROADCAST);
			nodes.put(alias.key, new Node(node.key(), node.kind(), nodes.get(source.key).valueVersion(),
				node.emittedWork(), List.of(state(FType.FULL), broadcast), node.exclusions(), node.anchors()));
			return alias;
		}

		private Ref matrixScalar(String name, OpOp2 op, Ref matrix) {
			BinaryOp hop = new BinaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, matrix.hop, new LiteralOp(1L));
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			CandidateRuleKey rule = new CandidateRuleKey(result.key, List.of(
				CandidateInputState.present(fType), CandidateInputState.absentLocal()));
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.BINARY_EWISE, "binary", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
			edges.add(new CompiledInputEdgeFact(matrix.key, result.key, 0));
			return result;
		}

		private Ref leftIndex(String name, Ref lhs, Ref rhs, boolean remoteLhs,
			boolean remoteRhs, boolean derived) {
			Hop hop = new LeftIndexingOp(name, DataType.MATRIX, ValueType.FP64, lhs.hop, rhs.hop,
				new LiteralOp(1L), new LiteralOp(4L), new LiteralOp(1L), new LiteralOp(1L), false, true);
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			Map<Integer,Ref> remote = new LinkedHashMap<>();
			if(remoteLhs) remote.put(0, lhs);
			if(remoteRhs) remote.put(1, rhs);
			candidateAtPositions(result, remote, derived);
			return result;
		}

		private Ref nary(String name, OpOpN op, boolean derivedEmission, Ref... inputs) {
			Hop[] inputHops = java.util.Arrays.stream(inputs).map(Ref::hop).toArray(Hop[]::new);
			Ref result = add(name, new NaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, inputHops), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			Map<Integer,Ref> matrixInputs = new java.util.LinkedHashMap<>();
			for(int position = 0; position < inputs.length; position++)
				matrixInputs.put(position, inputs[position]);
			candidateAtPositions(result, matrixInputs, derivedEmission);
			return result;
		}

		private Ref naryWithoutCandidate(String name, OpOpN op, Ref... inputs) {
			Hop[] inputHops = java.util.Arrays.stream(inputs).map(Ref::hop).toArray(Hop[]::new);
			return add(name, new NaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, inputHops), NodeKind.OPERATION, VersionKind.ORDINARY, null);
		}

		private Ref ternary(String name, OpOp3 op, Ref first, Ref second, Ref third) {
			Ref result = add(name, new TernaryOp(name, DataType.MATRIX, ValueType.FP64,
				op, first.hop, second.hop, third.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, first, 1, second, 2, third), false);
			return result;
		}

		private Ref frameMap(String name, Ref frame) {
			Ref result = add(name, new TernaryOp(name, DataType.FRAME, ValueType.STRING, OpOp3.MAP,
				frame.hop, new LiteralOp("fun"), new LiteralOp(1L)),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, frame), false);
			return result;
		}

		private Ref rexpand(String name, Ref target, String direction, FType outputType) {
			LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
			params.put("target", target.hop);
			params.put("max", new LiteralOp(64L));
			params.put("dir", new LiteralOp(direction));
			params.put("cast", new LiteralOp(true));
			params.put("ignore", new LiteralOp(true));
			ParameterizedBuiltinOp hop = new ParameterizedBuiltinOp(name, DataType.MATRIX,
				ValueType.FP64, ParamBuiltinOp.REXPAND, params);
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(hop.getParamIndexMap().get("target"), target), false, outputType);
			return result;
		}

		private Ref reshape(String name, Ref input, boolean byRow, FType outputType) {
			ArrayList<Hop> inputs = new ArrayList<>();
			inputs.add(input.hop);
			inputs.add(new LiteralOp(4L));
			inputs.add(new LiteralOp(2L));
			inputs.add(new LiteralOp(byRow));
			Ref result = add(name, new ReorgOp(name, DataType.MATRIX, ValueType.FP64,
				ReOrgOp.RESHAPE, inputs), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, input), false, outputType);
			return result;
		}

		private Ref wsigmoid(String name, Ref x, Ref u, Ref v) {
			Ref result = add(name, new QuaternaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp4.WSIGMOID, x.hop, u.hop, v.hop, false, false),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, x), false);
			return result;
		}

		private Ref wumm(String name, Ref x, Ref u, Ref v) {
			Ref result = add(name, new QuaternaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp4.WUMM, x.hop, u.hop, v.hop, true, OpOp1.MULT2, null),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, x), false);
			return result;
		}

		private Ref wdivmm(String name, Ref x, Ref u, Ref v, int baseType, FType outputType) {
			Ref result = add(name, new QuaternaryOp(name, DataType.MATRIX, ValueType.FP64,
				OpOp4.WDIVMM, x.hop, u.hop, v.hop, new LiteralOp(-1L), baseType, false, false),
				NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, x), false, outputType);
			return result;
		}

		private Ref replace(String name, Ref target) {
			LinkedHashMap<String,Hop> params = new LinkedHashMap<>();
			params.put("target", target.hop);
			params.put("pattern", new LiteralOp(Double.POSITIVE_INFINITY));
			params.put("replacement", new LiteralOp(0.0));
			Ref result = add(name, new ParameterizedBuiltinOp(name, DataType.MATRIX,
				ValueType.FP64, ParamBuiltinOp.REPLACE, params), NodeKind.OPERATION,
				VersionKind.ORDINARY, null);
			candidateAtPositions(result, Map.of(0, target), false);
			return result;
		}

		private Ref transpose(String name, Ref input, boolean derivedEmission) {
			return reorg(name, ReOrgOp.TRANS, input, derivedEmission);
		}

		private Ref reorg(String name, ReOrgOp operation, Ref input, boolean derivedEmission) {
			Ref result = add(name, new ReorgOp(name, DataType.MATRIX, ValueType.FP64,
				operation, input.hop), NodeKind.OPERATION, VersionKind.ORDINARY, null);
			candidate(result, List.of(input), derivedEmission);
			return result;
		}

		private Ref rightIndex(String name, Ref input) {
			return rightIndex(name, input, new LiteralOp(1L));
		}

		private Ref rightIndex(String name, Ref input, Hop rowLower) {
			return rightIndex(name, input, rowLower, new LiteralOp(50L),
				new LiteralOp(1L), new LiteralOp(1L));
		}

		private Ref rightIndex(String name, Ref input, Hop rowLower, Hop rowUpper,
			Hop colLower, Hop colUpper) {
			IndexingOp hop = new IndexingOp(name, DataType.MATRIX, ValueType.FP64, input.hop,
				rowLower, rowUpper, colLower, colUpper, false, true);
			Ref result = add(name, hop, NodeKind.OPERATION, VersionKind.ORDINARY, null);
			CandidateRuleKey rule = new CandidateRuleKey(result.key, List.of(
				CandidateInputState.present(fType), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
				CandidateInputState.absentLocal()));
			PlacementState target = state(fType);
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(target, false), fType);
			DerivedFoutMaterializationActionKey materializationAction = new DerivedFoutMaterializationActionKey(
				result.key, nodes.get(result.key).valueVersion(), rule,
				new PlacementState(ExecType.FED, FederatedOutput.LOUT, fType, false), target,
				input.anchor, input.key, fType, fType, result.key.controlRegion().normalizedSignature());
			CandidateEmissionFact derived = new CandidateEmissionFact(
				new PlacementEmissionState(target, true), fType, materializationAction);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.INDEXING, "rightIndex", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission, derived), ""));
			edges.add(new CompiledInputEdgeFact(input.key, result.key, 0));
			return result;
		}

		private Ref add(String name, Hop hop, NodeKind kind, VersionKind versionKind,
			DurableAnchorKey anchor) {
			ControlRegionKey region = new ControlRegionKey(fingerprint, "main",
				List.of("main/" + ordinal), "main", "compiled");
			CompiledHopKey key = new CompiledHopKey(fingerprint, "main", "main", "compiled",
				region, name + '@' + ordinal, name);
			ValueVersionKey value = new ValueVersionKey(fingerprint, name, region, ordinal++,
				versionKind, List.of());
			PlacementState state = state(fType);
			Node node = new Node(key, kind, value, true, List.of(state), List.of(),
				anchor == null ? List.of() : List.of(anchor));
			nodes.put(key, node);
			origins.put(key, hop);
			return new Ref(key, hop, anchor);
		}

		private void inheritAnchor(Ref target, DurableAnchorKey anchor) {
			Node node = nodes.get(target.key);
			nodes.put(target.key, new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				node.legalAlternatives(), node.exclusions(), List.of(anchor)));
		}

		private void candidate(Ref owner, List<Ref> inputs, boolean includeDerived) {
			Map<Integer,Ref> matrixInputs = new java.util.LinkedHashMap<>();
			for(int position = 0; position < inputs.size(); position++)
				matrixInputs.put(position, inputs.get(position));
			candidateAtPositions(owner, matrixInputs, includeDerived);
		}

		private void candidateAtPositions(Ref owner, Map<Integer,Ref> matrixInputs,
			boolean includeDerived) {
			candidateAtPositions(owner, matrixInputs, includeDerived, fType);
		}

		private void candidateAtPositions(Ref owner, Map<Integer,Ref> matrixInputs,
			boolean includeDerived, FType outputType) {
			List<CandidateInputState> inputStates = new ArrayList<>();
			for(int position = 0; position < owner.hop.getInput().size(); position++)
				inputStates.add(matrixInputs.containsKey(position)
					? CandidateInputState.present(fType) : CandidateInputState.absentLocal());
			CandidateRuleKey rule = new CandidateRuleKey(owner.key, inputStates);
			PlacementState target = state(outputType);
			if(outputType != fType) {
				Node node = nodes.get(owner.key);
				nodes.put(owner.key, new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
					List.of(target), node.exclusions(), node.anchors()));
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			if(includeDerived) {
				Ref firstInput = matrixInputs.values().iterator().next();
				DurableAnchorKey anchor = firstInput.anchor;
				DerivedFoutMaterializationActionKey action = new DerivedFoutMaterializationActionKey(
					owner.key, nodes.get(owner.key).valueVersion(), rule,
					new PlacementState(ExecType.FED, FederatedOutput.LOUT, null, false), target,
					anchor, firstInput.key, fType, outputType, owner.key.controlRegion().normalizedSignature());
				emissions.add(new CandidateEmissionFact(
					new PlacementEmissionState(target, true), outputType, action));
			}
			else
				emissions.add(new CandidateEmissionFact(new PlacementEmissionState(target, false), outputType));
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
					FederatedOutput.FOUT, outputType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(outputType), ""), emissions, ""));
			matrixInputs.forEach((position, input) ->
				edges.add(new CompiledInputEdgeFact(input.key, owner.key, position)));
		}

		private List<CandidateInputState> inputStates(Ref owner, int remotePosition, FType remoteType) {
			List<CandidateInputState> states = new ArrayList<>();
			for(int position = 0; position < owner.hop.getInput().size(); position++)
				states.add(position == remotePosition
					? CandidateInputState.present(remoteType) : CandidateInputState.absentLocal());
			return states;
		}

		private void additionalCandidate(Ref owner, List<CandidateInputState> inputs) {
			CandidateRuleKey rule = new CandidateRuleKey(owner.key, inputs);
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
		}

		private void samePoolRealizations(Ref owner, List<CandidateInputState> inputs,
			DurableAnchorKey... anchors) {
			CandidateRuleFact fact = candidates.stream().filter(candidate ->
				candidate.key().parentOccurrence() == owner.key
					&& candidate.key().orderedInputs().equals(inputs)).findFirst().orElseThrow();
			CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
			List<CandidateEmissionRealization> realizations = java.util.Arrays.stream(anchors)
				.map(anchor -> CandidateEmissionRealization.durable(
					emission.emissionState(), anchor, List.of(), List.of()))
				.toList();
			CandidateEmissionFact replacement = new CandidateEmissionFact(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), realizations);
			candidates.set(candidates.indexOf(fact), new CandidateRuleFact(fact.key(), fact.status(),
				fact.capability(), fact.shapeProof(), fact.profile(), List.of(replacement), fact.failureCode()));
		}

		private CandidateRealizationReference reference(Ref owner, List<CandidateInputState> inputs) {
			CandidateRuleFact fact = fact(owner, inputs);
			return CandidateRealizationReference.of(fact.key(),
				fact.allowedEmissionFacts().get(0).realizations().get(0));
		}

		private CandidateRuleFact fact(Ref owner, List<CandidateInputState> inputs) {
			return candidates.stream().filter(candidate ->
				candidate.key().parentOccurrence() == owner.key
					&& candidate.key().orderedInputs().equals(inputs)).findFirst().orElseThrow();
		}

		private CandidateRealizationReference withClauses(Ref owner, List<CandidateInputState> inputs,
			List<CandidateRealizationSupportClause> clauses) {
			CandidateRuleFact fact = fact(owner, inputs);
			CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
			CandidateEmissionRealization original = emission.realizations().get(0);
			CandidateEmissionRealization replacement = new CandidateEmissionRealization(original.key(), clauses);
			CandidateEmissionFact updated = new CandidateEmissionFact(emission.emissionState(),
				emission.executionFType(), emission.derivedFoutAction(), List.of(replacement));
			candidates.set(candidates.indexOf(fact), new CandidateRuleFact(fact.key(), fact.status(),
				fact.capability(), fact.shapeProof(), fact.profile(), List.of(updated), fact.failureCode()));
			return CandidateRealizationReference.of(fact.key(), replacement);
		}

		private void candidateLogicalRead(Ref owner) {
			CandidateRuleKey rule = new CandidateRuleKey(owner.key,
				List.of(CandidateInputState.present(fType)));
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "logical-read", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
		}

		private void candidateFederatedSource(Ref owner) {
			CandidateRuleKey rule = new CandidateRuleKey(owner.key,
				List.of(CandidateInputState.absentLocal(), CandidateInputState.absentLocal()));
			CandidateEmissionFact emission = new CandidateEmissionFact(
				new PlacementEmissionState(state(fType), false), fType);
			candidates.add(new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
				new CandidateCapabilityFact(OpCategory.OTHER, "federated-source", ExecType.FED,
					FederatedOutput.FOUT, fType, ReasonCode.OK, "fixture", List.of()),
				new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
				new CandidateProfileFact(List.of(fType), ""), List.of(emission), ""));
		}

		private NativePlacementContinuity resolver() {
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching,
				Set.of(), privacy);
		}

		private NativePlacementContinuity resolver(SearchSpaceMetrics metrics,
			int memoMaxEntries, long memoMaxProofs) {
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching,
				Set.of(), privacy, metrics, memoMaxEntries, memoMaxProofs);
		}

		private NativePlacementContinuity resolver(SearchSpaceMetrics metrics,
			int memoMaxEntries, long memoMaxProofs, long memoMaxEstimatedBytes) {
			return new NativePlacementContinuity(nodes, origins, candidates, edges, reaching,
				Set.of(), privacy, metrics, memoMaxEntries, memoMaxProofs, memoMaxEstimatedBytes);
		}
	}

	private record Ref(CompiledHopKey key, Hop hop, DurableAnchorKey anchor) { }

	private static PlacementState state(FType fType) {
		return new PlacementState(ExecType.FED, FederatedOutput.FOUT, fType, false);
	}

	private static DurableAnchorKey anchor(FType fType, String worker, long start, long end) {
		return new DurableAnchorKey("seed-" + fType + '-' + worker, fType,
			List.of(partition(worker, start, end)));
	}

	private static AnchorPartition partition(String worker, long start, long end) {
		return new AnchorPartition(worker, List.of(start, 0L), List.of(end, 2L));
	}

	private static Set<CompiledHopKey> identitySet(CompiledHopKey... keys) {
		Set<CompiledHopKey> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		result.addAll(List.of(keys));
		return result;
	}

	private static void assertIdentitySetEquals(Set<CompiledHopKey> expected,
		Set<CompiledHopKey> actual) {
		Assert.assertEquals(expected.size(), actual.size());
		for(CompiledHopKey owner : expected)
			Assert.assertTrue(actual.stream().anyMatch(candidate -> candidate == owner));
	}
}
