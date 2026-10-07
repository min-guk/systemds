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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class DirectExecutableReferenceMembershipTest {
	private static final String FINGERPRINT = "typed|실행:[,]";
	private static final ControlRegionKey REGION = new ControlRegionKey(FINGERPRINT, "이름|공간",
		List.of("호출,경로", "[]:|"), "블록|[]", "compiled:문맥");
	private static final PlacementEmissionState FED = new PlacementEmissionState(
		new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false), false);

	@Test
	public void typedMembershipMatchesIndependentLegacyStringOracleAcrossRevisions() throws Exception {
		CompiledHopKey firstOwner = key("동일|owner[,]"), equalForeignOwner = key("동일|owner[,]");
		CompiledHopKey otherOwner = key("다른:owner|[]");
		Assert.assertNotSame(firstOwner, equalForeignOwner);
		Assert.assertEquals(firstOwner, equalForeignOwner);
		CandidateRuleFact duplicateFirst = fact(firstOwner, "경로|[,]😀");
		CandidateRuleFact duplicateSecond = fact(equalForeignOwner, "경로|[,]😀");
		CandidateRuleFact distinct = fact(otherOwner, "경로|[,]😃");
		CandidateRuleFact hashAa = fact(key("Aa"), "hash-collision");
		CandidateRuleFact hashBB = fact(key("BB"), "hash-collision");
		Assert.assertNotEquals(reference(hashAa), reference(hashBB));
		Assert.assertEquals("typed membership must tolerate an actual record hash collision",
			reference(hashAa).hashCode(), reference(hashBB).hashCode());
		CandidateRuleFact sourceDash = fact(key("source-dash"), "-");
		CandidateRuleFact nativeDash = nativeFact(sourceDash.key(), "-");
		Assert.assertSame(reference(sourceDash).rule(), reference(nativeDash).rule());
		Assert.assertEquals(reference(sourceDash).realization().emissionState(),
			reference(nativeDash).realization().emissionState());
		Assert.assertEquals(reference(sourceDash).realization().durableAnchor(),
			reference(nativeDash).realization().durableAnchor());
		Assert.assertEquals(reference(sourceDash).realization().nativeLineage(),
			reference(nativeDash).realization().nativeLineage());
		Assert.assertNotEquals(reference(sourceDash).realization().layoutKind(),
			reference(nativeDash).realization().layoutKind());
		Assert.assertNotEquals(reference(sourceDash), reference(nativeDash));
		Assert.assertNotEquals(reference(sourceDash).normalizedSignature(),
			reference(nativeDash).normalizedSignature());
		List<CandidateRuleFact> before = List.of(duplicateFirst, duplicateSecond, distinct,
			hashAa, hashBB, sourceDash, nativeDash);
		Object index = index(before);
		assertLegacyMembership(index, before,
			List.of(reference(duplicateFirst), reference(duplicateSecond), reference(distinct),
				reference(hashAa), reference(hashBB), reference(sourceDash), reference(nativeDash),
				new CandidateRealizationReference(duplicateFirst.key(), reference(distinct).realization())));

		CandidateRuleFact withdrawn = failed(firstOwner);
		List<CandidateRuleFact> after = List.of(withdrawn, duplicateSecond, distinct,
			hashAa, hashBB, sourceDash, nativeDash);
		revise(index, after, firstOwner);
		assertLegacyMembership(index, after,
			List.of(reference(duplicateFirst), reference(duplicateSecond), reference(distinct),
				reference(hashAa), reference(hashBB), reference(sourceDash), reference(nativeDash)));

		CandidateRuleFact restored = fact(firstOwner, "경로|[,]😀");
		List<CandidateRuleFact> restoredFacts = List.of(restored, duplicateSecond, distinct,
			hashAa, hashBB, sourceDash, nativeDash);
		revise(index, restoredFacts, firstOwner);
		assertLegacyMembership(index, restoredFacts,
			List.of(reference(restored), reference(duplicateSecond), reference(distinct),
				reference(hashAa), reference(hashBB), reference(sourceDash), reference(nativeDash)));
	}

	@Test
	public void indexBuildAndMembershipDoNotSerializeInternalReferences() throws Exception {
		List<CandidateRuleFact> facts = new ArrayList<>();
		for(int i = 0; i < 256; i++)
			facts.add(fact(key("owner|" + i + "[,]"), "lineage:" + i + "|[,]😀"));
		List<CandidateRealizationReference> references = facts.stream()
			.map(DirectExecutableReferenceMembershipTest::reference).toList();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		PlacementIdentity.beginAnalysisScope(metrics);
		try {
			Object index = index(facts);
			for(int pass = 0; pass < 20; pass++)
				for(CandidateRealizationReference reference : references)
					Assert.assertTrue(executable(index, reference));
			Assert.assertEquals("internal executable membership must not materialize stable text",
				0, metrics.snapshot().signatureSerializations());
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
	}

	private static void assertLegacyMembership(Object index, List<CandidateRuleFact> facts,
		List<CandidateRealizationReference> probes) throws Exception {
		Map<String,Integer> legacy = new HashMap<>();
		for(CandidateRuleFact fact : facts)
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						if(PlacementSupportRelations.executableSourceRealization(fact.key(), realization)) {
							CandidateRealizationReference reference =
								CandidateRealizationReference.of(fact.key(), realization);
							legacy.merge(reference.normalizedSignature(), 1, Integer::sum);
						}
		for(CandidateRealizationReference probe : probes)
			Assert.assertEquals(legacy.containsKey(probe.normalizedSignature()), executable(index, probe));
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, String lineage) {
		CandidateRuleKey rule = new CandidateRuleKey(owner,
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.absentLocal()));
		CandidateEmissionRealization realization = CandidateEmissionRealization.sourceLineage(FED, lineage);
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""),
			List.of(new CandidateEmissionFact(FED, FType.ROW, null, List.of(realization))), "");
	}

	private static CandidateRuleFact nativeFact(CandidateRuleKey rule, String lineage) {
		DurableAnchorKey pool = new DurableAnchorKey("pool|[,]😀", FType.ROW,
			List.of(new AnchorPartition("worker|[,]", List.of(0L, 0L), List.of(8L, 2L))));
		CandidateEmissionRealization realization = CandidateEmissionRealization.nativeLineage(
			FED, lineage, pool, List.of(new PlacementProofKey(
				PlacementProofKind.NATIVE_CONTINUITY, rule.parentOccurrence(), "native|proof[,]")), List.of());
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.ROW, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""),
			List.of(new CandidateEmissionFact(FED, FType.ROW, null, List.of(realization))), "");
	}

	private static CandidateRuleFact failed(CompiledHopKey owner) {
		return new CandidateRuleFact(new CandidateRuleKey(owner, List.of()),
			CandidateEvaluationStatus.RULE_ERROR, null,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), "failed"), List.of(), "failed");
	}

	private static CompiledHopKey key(String name) {
		return new CompiledHopKey(FINGERPRINT, REGION.functionNamespace(), "호출|[,]", "재컴파일:[]",
			REGION, name, "원본|" + name);
	}

	private static CandidateRealizationReference reference(CandidateRuleFact fact) {
		return CandidateRealizationReference.of(fact.key(),
			fact.allowedEmissionFacts().get(0).realizations().get(0));
	}

	private static Class<?> indexClass() {
		for(Class<?> type : PlacementRelationClosure.class.getDeclaredClasses())
			if(type.getSimpleName().equals("DirectSourceIndex"))
				return type;
		throw new AssertionError("DirectSourceIndex was not found");
	}

	private static Object index(List<CandidateRuleFact> facts) throws Exception {
		Constructor<?> constructor = indexClass().getDeclaredConstructor(List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(facts);
	}

	private static boolean executable(Object index, CandidateRealizationReference reference)
		throws Exception {
		Method method = null;
		for(Method candidate : indexClass().getDeclaredMethods())
			if(candidate.getName().equals("executable")) {
				method = candidate;
				break;
			}
		Assert.assertNotNull(method);
		method.setAccessible(true);
		Object argument = method.getParameterTypes()[0] == String.class
			? reference.normalizedSignature() : reference;
		return (boolean) method.invoke(index, argument);
	}

	private static void revise(Object index, List<CandidateRuleFact> facts, CompiledHopKey owner)
		throws Exception {
		Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		changed.add(owner);
		Method method = indexClass().getDeclaredMethod("nextRevision", List.class, Set.class);
		method.setAccessible(true);
		method.invoke(index, facts, changed);
	}
}
