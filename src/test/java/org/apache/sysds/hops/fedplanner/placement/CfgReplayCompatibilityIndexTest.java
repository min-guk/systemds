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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientCompatibilityProof;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.TransientPlacementCompatibility;
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

/** Exact-equivalence contract for the CFG replay realization membership index. */
public class CfgReplayCompatibilityIndexTest {
	private static final PlacementState FED = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState FED_EMISSION = new PlacementEmissionState(FED, false);
	private static final PlacementState LOCAL = new PlacementState(
		ExecType.CP, FederatedOutput.LOUT, null, false);
	private static final PlacementEmissionState LOCAL_EMISSION = new PlacementEmissionState(LOCAL, false);

	@Test
	public void indexMembershipExactlyMatchesCandidateRealizationOracle() throws Exception {
		CandidateRuleKey sourceRule = rule("source", CandidateInputState.present(FType.FULL));
		CandidateRuleKey readerRule = rule("reader", CandidateInputState.absentLocal());
		CandidateEmissionRealization source = durable("source");
		CandidateEmissionRealization localReader = CandidateEmissionRealization.local(LOCAL_EMISSION);
		CandidateRuleKey unavailableRule = rule("unavailable", CandidateInputState.present(FType.FULL));
		List<CandidateRuleFact> facts = List.of(
			fact(sourceRule, emission(FED_EMISSION, FType.FULL, source)),
			fact(readerRule, emission(LOCAL_EMISSION, null, localReader)),
			privacyExcluded(unavailableRule));
		CandidateRealizationReference sourceReference = ref(sourceRule, source);
		CandidateRealizationReference readerReference = ref(readerRule, localReader);
		CandidateRealizationReference structurallyEqualSource = ref(
			rule("source", CandidateInputState.present(FType.FULL)), durable("source"));
		CandidateRealizationReference missing = ref(
			rule("missing", CandidateInputState.present(FType.FULL)), durable("missing"));

		Set<CandidateRealizationReference> indexed = references(facts);
		for(CandidateRealizationReference probe : List.of(
			sourceReference, readerReference, structurallyEqualSource, missing))
			Assert.assertEquals("probe=" + probe, oracleContains(facts, probe), indexed.contains(probe));
		Assert.assertTrue("LOCAL is deliberately not executable source output, but membership is structural",
			indexed.contains(readerReference));
		Assert.assertNotSame(sourceReference, structurallyEqualSource);
		Assert.assertEquals(sourceReference, structurallyEqualSource);
		Assert.assertEquals(2, indexed.size());
	}

	@Test
	public void baselineOrReplacementMembershipPreservesEveryLiveReplayEndpoint() throws Exception {
		CandidateRuleKey sourceRule = rule("baseline-source", CandidateInputState.present(FType.FULL));
		CompiledHopKey replayOwner = owner("replay-reader");
		CandidateRuleKey priorReaderRule = rule(replayOwner, CandidateInputState.absentLocal());
		CandidateRuleKey replacementReaderRule = rule(replayOwner, CandidateInputState.present(FType.FULL));
		CandidateEmissionRealization source = durable("baseline-source");
		CandidateEmissionRealization priorReader = CandidateEmissionRealization.local(LOCAL_EMISSION);
		CandidateEmissionRealization replacementReader = durable("replacement-reader");
		List<CandidateRuleFact> baseline = List.of(
			fact(sourceRule, emission(FED_EMISSION, FType.FULL, source)),
			fact(priorReaderRule, emission(LOCAL_EMISSION, null, priorReader)));
		List<CandidateRuleFact> replacement = List.of(
			fact(replacementReaderRule, emission(FED_EMISSION, FType.FULL, replacementReader)));
		Set<CandidateRealizationReference> baselineIndex = references(baseline);
		Set<CandidateRealizationReference> replacementIndex = references(replacement);
		CandidateRealizationReference sourceReference = ref(sourceRule, source);
		CandidateRealizationReference priorReaderReference = ref(priorReaderRule, priorReader);
		CandidateRealizationReference replacementReaderReference = ref(replacementReaderRule, replacementReader);
		CandidateRealizationReference missing = ref(
			rule("absent-reader", CandidateInputState.absentLocal()), priorReader);

		Assert.assertSame(priorReaderRule.parentOccurrence(), replacementReaderRule.parentOccurrence());
		Assert.assertNotEquals(priorReaderReference, replacementReaderReference);
		Assert.assertTrue(live(baselineIndex, replacementIndex, sourceReference));
		Assert.assertTrue("a prior realization of the same replay owner remains live",
			live(baselineIndex, replacementIndex, priorReaderReference));
		Assert.assertTrue("a newly rebuilt realization is live from the replacement index",
			live(baselineIndex, replacementIndex, replacementReaderReference));
		Assert.assertFalse(live(baselineIndex, replacementIndex, missing));
		for(CandidateRealizationReference probe : List.of(sourceReference, priorReaderReference,
			replacementReaderReference, missing)) {
			boolean oracle = oracleContains(baseline, probe) || oracleContains(replacement, probe);
			Assert.assertEquals("probe=" + probe, oracle, live(baselineIndex, replacementIndex, probe));
		}
	}

	@Test
	public void canonicalComparatorPreservesNaturalOrderingAndExactDeduplication() {
		CandidateRuleKey sourceRule = rule("compat-source", CandidateInputState.absentLocal());
		CandidateRuleKey readerRule = rule("compat-reader", CandidateInputState.absentLocal());
		CandidateEmissionRealization local = CandidateEmissionRealization.local(LOCAL_EMISSION);
		CandidateRealizationReference source = ref(sourceRule, local);
		CandidateRealizationReference reader = ref(readerRule, local);
		TransientPlacementCompatibility first = compatibility(source, reader, "proof-a");
		TransientPlacementCompatibility second = compatibility(source, reader, "proof-b");
		TransientPlacementCompatibility duplicate = compatibility(
			ref(rule("compat-source", CandidateInputState.absentLocal()),
				CandidateEmissionRealization.local(LOCAL_EMISSION)),
			ref(rule("compat-reader", CandidateInputState.absentLocal()),
				CandidateEmissionRealization.local(LOCAL_EMISSION)), "proof-a");
		List<TransientPlacementCompatibility> input = List.of(second, duplicate, first);
		TreeSet<TransientPlacementCompatibility> natural = new TreeSet<>(input);
		TreeSet<TransientPlacementCompatibility> canonical =
			new TreeSet<>(PlacementAnalysis.<TransientPlacementCompatibility>canonicalComparator());
		canonical.addAll(input);

		Assert.assertEquals(2, canonical.size());
		Assert.assertEquals(new ArrayList<>(natural), new ArrayList<>(canonical));
		Assert.assertEquals(first, duplicate);
	}

	@Test
	public void segmentedTransientOrderingPreservesEveryLegacyUtf16Byte() throws Exception {
		List<Object> values = new ArrayList<>();
		List<String> signatures = new ArrayList<>();
		CandidateRuleKey sourceRule = rule("ordering-source", CandidateInputState.present(FType.FULL));
		CandidateRuleKey readerRule = rule("ordering-reader", CandidateInputState.present(FType.FULL));
		CandidateEmissionRealization source = durable("ordering-source");
		CandidateEmissionRealization reader = durable("ordering-reader");
		DurableAnchorKey anchor = source.key().durableAnchor();
		for(String suffix : List.of("", "a", "aa", "|:[, ]", "é", "😀", "\ud800", "\udc00", "x".repeat(100))) {
			List<PlacementProofKey> dependencies = List.of(
				new PlacementProofKey(PlacementProofKind.SHAPE, null, "proof-" + suffix),
				new PlacementProofKey(PlacementProofKind.VALUE_IDENTITY, sourceRule.parentOccurrence(),
					"shared-prefix-".repeat(30) + suffix));
			for(TransientCompatibilityProof proof : List.of(
				new TransientCompatibilityProof(null, null, List.of()),
				new TransientCompatibilityProof(null, null, dependencies),
				new TransientCompatibilityProof(anchor, anchor, dependencies),
				new TransientCompatibilityProof(null, null, anchor, true, dependencies),
				new TransientCompatibilityProof(null, null, anchor, false, dependencies))) {
				TransientPlacementCompatibility compatibility = new TransientPlacementCompatibility(
					ref(sourceRule, source), ref(readerRule, reader), CandidateInputState.present(FType.FULL),
					CandidateInputState.present(FType.FULL), proof);
				values.addAll(List.of(proof, compatibility));
				signatures.addAll(List.of(proof.normalizedSignature(), compatibility.normalizedSignature()));
			}
		}
		Class<?> contextClass = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalTextContext");
		var contextConstructor = contextClass.getDeclaredConstructor();
		contextConstructor.setAccessible(true);
		Object context = contextConstructor.newInstance();
		Method key = PlacementAnalysis.class.getDeclaredMethod("canonicalOrderingKey", Object.class, contextClass);
		key.setAccessible(true);
		Class<?> textClass = Class.forName(PlacementAnalysis.class.getName() + "$CanonicalText");
		var normalizedConstructor = PlacementAnalysis.NormalizedText.class.getDeclaredConstructor(textClass);
		normalizedConstructor.setAccessible(true);
		for(int index = 0; index < values.size(); index++) {
			PlacementAnalysis.NormalizedText text = (PlacementAnalysis.NormalizedText)normalizedConstructor.newInstance(
				key.invoke(null, values.get(index), context));
			Assert.assertEquals(signatures.get(index), text.materialize());
		}
		java.util.Comparator<Object> comparator = PlacementAnalysis.canonicalComparator();
		for(int left = 0; left < values.size(); left++)
			for(int right = 0; right < values.size(); right++)
				Assert.assertEquals(Integer.signum(signatures.get(left).compareTo(signatures.get(right))),
					Integer.signum(comparator.compare(values.get(left), values.get(right))));
	}

	private static boolean live(Set<CandidateRealizationReference> baseline,
		Set<CandidateRealizationReference> replacement, CandidateRealizationReference reference) {
		return baseline.contains(reference) || replacement.contains(reference);
	}

	@SuppressWarnings("unchecked")
	private static Set<CandidateRealizationReference> references(List<CandidateRuleFact> facts)
		throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"candidateRealizationReferences", List.class);
		method.setAccessible(true);
		return (Set<CandidateRealizationReference>)method.invoke(null, facts);
	}

	@SuppressWarnings("unchecked")
	private static boolean oracleContains(List<CandidateRuleFact> facts,
		CandidateRealizationReference reference) throws Exception {
		Method method = PlacementRelationClosure.class.getDeclaredMethod(
			"candidateRealization", List.class, CandidateRealizationReference.class);
		method.setAccessible(true);
		return ((Optional<CandidateEmissionRealization>)method.invoke(null, facts, reference)).isPresent();
	}

	private static TransientPlacementCompatibility compatibility(CandidateRealizationReference source,
		CandidateRealizationReference reader, String proof) {
		return new TransientPlacementCompatibility(source, reader,
			CandidateInputState.absentLocal(), CandidateInputState.absentLocal(),
			new TransientCompatibilityProof(null, null, List.of(new PlacementProofKey(
				PlacementProofKind.VALUE_IDENTITY, source.rule().parentOccurrence(), proof))));
	}

	private static CandidateEmissionFact emission(PlacementEmissionState state, FType type,
		CandidateEmissionRealization realization) {
		return new CandidateEmissionFact(state, type, null, List.of(realization));
	}

	private static CandidateRuleFact fact(CandidateRuleKey rule, CandidateEmissionFact emission) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", emission.emissionState().placementState().execType(),
				emission.emissionState().placementState().output(),
				emission.emissionState().placementState().fType(), ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(emission.executionFType() == null
				? List.of() : List.of(emission.executionFType()), ""), List.of(emission), "");
	}

	private static CandidateRuleFact privacyExcluded(CandidateRuleKey rule) {
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.PRIVACY_EXCLUDED,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.FED,
				FederatedOutput.FOUT, FType.FULL, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(FType.FULL), ""), List.of(), "fixture-privacy");
	}

	private static CandidateEmissionRealization durable(String id) {
		return CandidateEmissionRealization.durable(FED_EMISSION,
			new DurableAnchorKey(id, FType.FULL, List.of(
				new AnchorPartition("worker", List.of(0L, 0L), List.of(8L, 3L)))),
			List.of(), List.of());
	}

	private static CandidateRealizationReference ref(CandidateRuleKey rule,
		CandidateEmissionRealization realization) {
		return CandidateRealizationReference.of(rule, realization);
	}

	private static CandidateRuleKey rule(String name, CandidateInputState input) {
		return rule(owner(name), input);
	}

	private static CandidateRuleKey rule(CompiledHopKey owner, CandidateInputState input) {
		return new CandidateRuleKey(owner, List.of(input));
	}

	private static CompiledHopKey owner(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"cfg-replay-index", "main", List.of("main"), "main", "compiled");
		return new CompiledHopKey("cfg-replay-index", "main", "main",
			"compiled", region, name, name);
	}
}
