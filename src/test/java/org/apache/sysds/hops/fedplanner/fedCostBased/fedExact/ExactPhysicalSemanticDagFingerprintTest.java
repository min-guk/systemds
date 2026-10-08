/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.AuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleNote;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedText;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NormalizedTextBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class ExactPhysicalSemanticDagFingerprintTest {
	@Test
	public void factorizedFingerprintBindsEveryAxisWithoutMaterializingProduct() throws Exception {
		CandidateEmissionRealization product = factorizedFingerprintFixture(100, "source");
		CandidateEmissionFact emission = new CandidateEmissionFact(
			product.key().emissionState(), null, null, List.of(product));
		Assert.assertEquals(10_000, product.supportClauses().size());
		Assert.assertEquals(0, materializedClauses(product));
		String fingerprint = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(fact("factorized", List.of(emission))));
		Assert.assertEquals("fingerprinting must consume axes, not Cartesian support clauses",
			0, materializedClauses(product));
		CandidateEmissionRealization equal = factorizedFingerprintFixture(100, "source");
		CandidateEmissionRealization changed = factorizedFingerprintFixture(100, "changed-source");
		Assert.assertEquals(fingerprint, ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(fact("factorized", List.of(new CandidateEmissionFact(
				equal.key().emissionState(), null, null, List.of(equal)))))));
		Assert.assertNotEquals(fingerprint, ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(fact("factorized", List.of(new CandidateEmissionFact(
				changed.key().emissionState(), null, null, List.of(changed)))))));
		Assert.assertEquals(0, materializedClauses(equal));
		Assert.assertEquals(0, materializedClauses(changed));
	}

	@Test
	public void indexedFingerprintMatchesExplicitBytesWithoutCreatingRowHandles() {
		CandidateEmissionFact explicitEmission = deepEmission(true, "indexed-proof", "indexed-scope");
		CandidateEmissionRealization explicit = explicitEmission.realizations().get(0);
		CandidateEmissionRealization indexed = explicit.withIndexedSupport();
		CandidateEmissionFact indexedEmission = new CandidateEmissionFact(
			explicitEmission.emissionState(), explicitEmission.executionFType(),
			explicitEmission.derivedFoutAction(), List.of(indexed));
		Assert.assertEquals(0, indexed.fullyMaterializedSupportClauseCount());

		String expected = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(fact("indexed", List.of(explicitEmission))));
		String actual = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(fact("indexed", List.of(indexedEmission))));

		Assert.assertEquals(expected, actual);
		Assert.assertEquals("fingerprinting must read indexed row dictionaries directly",
			0, indexed.fullyMaterializedSupportClauseCount());
		indexed.supportClauses().get(1);
		Assert.assertEquals("only an explicitly selected row creates a handle",
			1, indexed.fullyMaterializedSupportClauseCount());
	}

	private static CandidateEmissionRealization factorizedFingerprintFixture(int width, String prefix)
		throws Exception {
		PlacementEmissionState emission = new PlacementEmissionState(
			new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);
		var local = CandidateEmissionRealization.local(emission);
		List<List<CandidateRealizationInputBinding>> factors = new ArrayList<>();
		for(int input = 0; input < 2; input++) {
			List<CandidateRealizationInputBinding> options = new ArrayList<>();
			for(int option = 0; option < width; option++) {
				var source = CandidateRealizationReference.of(new CandidateRuleKey(
					key(prefix + '-' + input + '-' + option), List.of()), local);
				options.add(CandidateRealizationInputBinding.direct(input, source));
			}
			factors.add(options);
		}
		var factory = CandidateEmissionRealization.class.getDeclaredMethod("factorized",
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey.class,
			List.class, List.class, DurableAnchorKey.class, boolean.class);
		factory.setAccessible(true);
		return (CandidateEmissionRealization)factory.invoke(null, local.key(), List.of(), factors, null, true);
	}

	private static int materializedClauses(CandidateEmissionRealization realization) throws Exception {
		var method = realization.supportClauses().getClass().getDeclaredMethod("materializedClauseCount");
		method.setAccessible(true);
		return (int)method.invoke(realization.supportClauses());
	}

	@Test
	public void schemaMatchesIndependentOracleAndFieldsRemainBound() {
		CandidateRuleFact first = fact("detail-Aa", emissions());
		CandidateRuleFact equalCopy = fact("detail-Aa", emissions());
		String shared = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(first, first));
		String recomputedNoMemo = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(first, equalCopy));
		Assert.assertTrue(shared, shared.startsWith("physical-semantic-dag-v3:"));
		Assert.assertEquals("identity memo must not enter semantic bytes", shared, recomputedNoMemo);
		Assert.assertEquals("independent traversal must define the same schema bytes", shared,
			IndependentPhysicalSemanticDagOracle.candidates(List.of(first, equalCopy)));
		Assert.assertNotEquals(shared,
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(List.of(first)));
		Assert.assertNotEquals("same String hash must not hide changed content", shared,
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
				List.of(fact("detail-BB", emissions()), equalCopy)));
		Assert.assertNotEquals("malformed UTF-16 code units must remain semantic data",
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
				List.of(fact("detail-\ud800", emissions()))),
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
				List.of(fact("detail-?", emissions()))));

		List<CandidateEmissionFact> reversed = new ArrayList<>(emissions());
		java.util.Collections.reverse(reversed);
		Assert.assertNotEquals(
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(List.of(first)),
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
				List.of(fact("detail-Aa", reversed))));
	}

	@Test
	public void flatAndRopeSignatureContentAgreeAndCustomContentRemainsBound() {
		CompiledHopKey decision = key("alternative");
		PlacementState state = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		String signature = "prefix\ud800tail";
		Alternative flat = alternative(decision, state, AuthorityKind.LEGAL_SINGLETON,
			NormalizedText.literal(signature));
		Alternative rope = alternative(decision, state, AuthorityKind.LEGAL_SINGLETON,
			new NormalizedTextBuilder().append("prefix").append("\ud800").append("tail").build());
		Assert.assertEquals(
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(flat),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rope));
		Assert.assertEquals("independent traversal must include the complete alternative",
			IndependentPhysicalSemanticDagOracle.alternativeValue(rope),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rope));
		Alternative custom = alternative(decision, state, AuthorityKind.LEGAL_SINGLETON,
			NormalizedText.literal("custom\ud800signature"));
		Assert.assertNotEquals("accepted caller-supplied signature content is semantic",
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(flat),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(custom));
		Alternative replacement = alternative(decision, state, AuthorityKind.LEGAL_SINGLETON,
			NormalizedText.literal("custom?signature"));
		Assert.assertNotEquals("unpaired surrogate code units must not be replaced",
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(custom),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(replacement));

		Alternative differentAuthority = alternative(decision, state, AuthorityKind.SYNTHETIC_BOUNDARY,
			NormalizedText.literal(signature));
		Assert.assertNotEquals("direct semantic field cannot be hidden by an unchanged derived string",
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(flat),
			ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(differentAuthority));
	}

	@Test
	public void nullableShapeValuesRemainAcceptedAndDistinctFromLiteralNull() {
		Map<String,String> absent = new LinkedHashMap<>();
		absent.put("unknown", null);
		Map<String,String> literal = new LinkedHashMap<>();
		literal.put("unknown", "null");
		CandidateRuleFact nullValue = fact("nullable", emissions(), absent);
		CandidateRuleFact literalValue = fact("nullable", emissions(), literal);
		String actual = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(nullValue));
		Assert.assertEquals(IndependentPhysicalSemanticDagOracle.candidates(List.of(nullValue)), actual);
		Assert.assertNotEquals("absent and literal null must have different framed encodings", actual,
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(List.of(literalValue)));
	}

	@Test
	public void deepProofBindingNativePoolAndEveryOrClauseAreBound() {
		CandidateRuleFact bothClauses = fact("deep", List.of(deepEmission(true, "proof-A", "scope\ud800")));
		CandidateRuleFact oneClause = fact("deep", List.of(deepEmission(false, "proof-A", "scope\ud800")));
		CandidateRuleFact changedProof = fact("deep", List.of(deepEmission(true, "proof-B", "scope\ud800")));
		CandidateRuleFact changedAction = fact("deep", List.of(deepEmission(true, "proof-A", "scope?")));
		String complete = ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(bothClauses));
		Assert.assertNotEquals("dropping one OR clause must change authority", complete,
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(List.of(oneClause)));
		Assert.assertNotEquals("proof owner/signature authority must be bound", complete,
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(List.of(changedProof)));
		Assert.assertNotEquals("relocation action text must be bound", complete,
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(List.of(changedAction)));
	}

	@Test
	public void longSegmentedTextAndNestedDigestsMatchIndependentOracle() {
		String prefix = "x".repeat(511);
		String middle = "y".repeat(1031);
		String text = prefix + "\ud83d\ude00" + middle + "\ud800";
		CompiledHopKey decision = key("long-alternative");
		PlacementState state = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		Alternative flat = alternative(decision, state, AuthorityKind.LEGAL_SINGLETON,
			NormalizedText.literal(text));
		Alternative rope = alternative(decision, state, AuthorityKind.LEGAL_SINGLETON,
			new NormalizedTextBuilder().append(prefix).append("\ud83d").append("\ude00")
				.append(middle).append("\ud800").build());
		String expected = IndependentPhysicalSemanticDagOracle.alternativeValue(flat);
		Assert.assertEquals(expected, ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(flat));
		Assert.assertEquals(expected, ExactPhysicalCostModel.physicalAlternativeDagFingerprintForTest(rope));
		CandidateRuleFact deep = fact(text, List.of(deepEmission(true, text, "scope-" + text)));
		List<CandidateRuleFact> shared = List.of(deep, deep);
		Assert.assertEquals(IndependentPhysicalSemanticDagOracle.candidates(shared),
			ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(shared));
	}

	@Test
	public void sharedNullableTypedTextUsesLosslessByteMemo() {
		String value = new String("typed-\ud800-" + "x".repeat(4096));
		Map<String,String> firstFields = new LinkedHashMap<>();
		firstFields.put("shared", value);
		firstFields.put("absent", null);
		List<CandidateRuleFact> facts = List.of(
			fact("first", emissions(), firstFields),
			fact("second", emissions(), firstFields));
		PhysicalSemanticDagFingerprint cached = new PhysicalSemanticDagFingerprint(null, 1L << 20);
		PhysicalSemanticDagFingerprint uncached = new PhysicalSemanticDagFingerprint(null, 0L);
		String expected = IndependentPhysicalSemanticDagOracle.candidates(facts);
		Assert.assertEquals(expected, cached.candidateFactsForTest(facts));
		Assert.assertEquals(expected, uncached.candidateFactsForTest(facts));
		Assert.assertTrue("nullable typed payload should reuse its exact UTF-16 bytes",
			cached.literalByteMemoSnapshotForTest().convertedUtf16Units()
				< uncached.literalByteMemoSnapshotForTest().convertedUtf16Units());
		Assert.assertTrue(cached.literalByteMemoSnapshotForTest().hits() > 0);
		cached.clearLiteralByteMemo();
		Assert.assertEquals(0, cached.literalByteMemoSnapshotForTest().retainedEstimatedBytes());
	}

	@Test
	public void repeatedCandidateIdentityDoesNotReplayLargeSemanticText() {
		java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(base instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)base;
		Assume.assumeTrue(bean.isThreadAllocatedMemorySupported());
		boolean wasEnabled = bean.isThreadAllocatedMemoryEnabled();
		try {
			if(!wasEnabled)
				bean.setThreadAllocatedMemoryEnabled(true);
			String detail = "semantic-detail-" + "x".repeat(64 * 1024);
			CandidateRuleFact repeated = fact(detail, emissions());
			List<CandidateRuleFact> occurrences = new ArrayList<>();
			for(int index = 0; index < 256; index++)
				occurrences.add(repeated);
			for(int warmup = 0; warmup < 3; warmup++)
				ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(occurrences);
			long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
			for(int repeat = 0; repeat < 3; repeat++)
				ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(occurrences);
			long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
			Assert.assertTrue("shared candidate semantic text was replayed: " + allocated,
				allocated < 4_000_000L);
		}
		finally {
			if(!wasEnabled)
				bean.setThreadAllocatedMemoryEnabled(false);
		}
	}

	public static void main(String[] args) {
		CandidateRuleFact fact = fact("fresh-jvm", emissions());
		System.out.println(ExactPhysicalCostModel.physicalCandidateFactsDagFingerprintForTest(
			List.of(fact, fact)));
	}

	private static Alternative alternative(CompiledHopKey decision, PlacementState state,
		AuthorityKind kind, NormalizedText signature) {
		return new Alternative(decision, state, kind, null, null, null, null, null, null, null,
			List.of(), List.of(), null, null, signature);
	}

	private static CandidateRuleFact fact(String detail, List<CandidateEmissionFact> emissions) {
		Map<String,String> consulted = new LinkedHashMap<>();
		consulted.put("cols", "2");
		consulted.put("rows", "4");
		return fact(detail, emissions, consulted);
	}

	private static CandidateRuleFact fact(String detail, List<CandidateEmissionFact> emissions,
		Map<String,String> consulted) {
		CandidateCapabilityFact capability = new CandidateCapabilityFact(OpCategory.BINARY_EWISE,
			"+", ExecType.CP, FederatedOutput.LOUT, null, ReasonCode.OK, detail,
			List.of(new CandidateRuleNote(ReasonCode.INFO, "note\ud800")));
		return new CandidateRuleFact(new CandidateRuleKey(key("candidate"),
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.absentLocal())),
			CandidateEvaluationStatus.AVAILABLE, capability,
			new CandidateShapeProofFact(consulted, List.of("cols", "rows"), List.of()),
			new CandidateProfileFact(List.of(FType.ROW), ""), emissions, "");
	}

	private static List<CandidateEmissionFact> emissions() {
		PlacementState local = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementState uploaded = new PlacementState(ExecType.CP, FederatedOutput.FOUT, FType.ROW, false);
		return List.of(new CandidateEmissionFact(new PlacementEmissionState(local, false), null),
			new CandidateEmissionFact(new PlacementEmissionState(uploaded, false), null));
	}

	private static CandidateEmissionFact deepEmission(boolean includeSecondClause,
		String proofAuthority, String actionScope) {
		PlacementState sourceState = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		PlacementEmissionState sourceEmission = new PlacementEmissionState(sourceState, false);
		CandidateRuleKey sourceRule = new CandidateRuleKey(key("source-rule"), List.of());
		CandidateEmissionRealization sourceRealization = CandidateEmissionRealization.local(sourceEmission);
		CandidateRealizationReference source = CandidateRealizationReference.of(sourceRule, sourceRealization);
		PlacementState outputState = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.ROW, false);
		PlacementEmissionState outputEmission = new PlacementEmissionState(outputState, false);
		DurableAnchorKey pool = new DurableAnchorKey("native-pool", FType.ROW,
			List.of(new AnchorPartition("worker", List.of(0L, 0L), List.of(4L, 2L))));
		ValueVersionKey version = new ValueVersionKey("program", "value", sourceRule.parentOccurrence()
			.controlRegion(), 0, VersionKind.ORDINARY, List.of());
		RelocationActionKey action = new RelocationActionKey(version, outputState, pool,
			actionScope, List.of(key("consumer")));
		CandidateRealizationInputBinding binding = CandidateRealizationInputBinding.relocation(
			1, source, action);
		CandidateRealizationSupportClause first = new CandidateRealizationSupportClause(
			List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
				key("proof-owner"), proofAuthority)), List.of(binding), pool, false);
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>();
		clauses.add(first);
		if(includeSecondClause)
			clauses.add(new CandidateRealizationSupportClause(
				List.of(new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
					key("proof-owner-2"), "second-proof")), List.of(binding), pool, false));
		CandidateEmissionRealization realization = new CandidateEmissionRealization(
			org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey
				.nativeLineage(outputEmission, "native-lineage"), clauses);
		return new CandidateEmissionFact(outputEmission, FType.ROW, null, List.of(realization));
	}

	private static CompiledHopKey key(String suffix) {
		ControlRegionKey region = new ControlRegionKey("program", "main", List.of("root"),
			"call", "compile");
		return new CompiledHopKey("program", "main", "call", "compile", region,
			"hop-" + suffix, "source-" + suffix);
	}
}
