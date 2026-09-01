/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateLookupFailure;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleLookupException;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Focused Slice A locks for canonical builder facts, exact identity lookup, and deep immutability. */
public class CampaignBG014PlacementCandidateRuleFactsSliceATest {
	private static final List<String> FIXTURES = List.of("B-02", "B-05", "B-07", "B-09", "B-15");

	@Test
	public void canonicalBuilderPublishesOneOrderedImmutableFactPerCandidateKey() throws Exception {
		for(String fixture : FIXTURES) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			String before = PlacementGraphFingerprint.capture(program);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			List<CandidateRuleKey> keys = analysis.candidateRuleDomain().orderedRuleKeys();
			List<CandidateRuleFact> facts = analysis.candidateRuleFacts().orderedFacts();
			Assert.assertFalse(fixture + " must publish canonical candidate facts", facts.isEmpty());
			Assert.assertEquals(fixture + " candidate domain/fact multiplicity", keys.size(), facts.size());
			Assert.assertEquals(fixture + " builder-owned domain/fact sequence",
				keys, facts.stream().map(CandidateRuleFact::key).toList());
			Assert.assertEquals(fixture, analysis.analysisFingerprint(),
				analysis.candidateRuleDomain().analysisFingerprint());
			assertExactCombinationUniverse(fixture, analysis);

			for(int i = 0; i < facts.size(); i++) {
				CandidateRuleKey expected = keys.get(i);
				CandidateRuleFact fact = facts.get(i);
				Assert.assertSame(fixture + " parent identity at fact " + i,
					expected.parentOccurrence(), fact.key().parentOccurrence());
				Assert.assertEquals(fixture + " ordered input vector at fact " + i,
					expected.orderedInputs(), fact.key().orderedInputs());
				Assert.assertSame(fixture + " exact lookup at fact " + i, fact,
					analysis.candidateRuleFacts().requireExact(expected.parentOccurrence(),
						new ArrayList<>(expected.orderedInputs())));
				Assert.assertFalse(fixture + " synthetic function boundary became candidate-bearing",
					expected.parentOccurrence().canonicalSourceOrigin().startsWith("function-boundary:"));
			}

			assertDeeplyImmutable(fixture, analysis);
			Assert.assertEquals(fixture + " builder/fact inspection mutated the program", before,
				PlacementGraphFingerprint.capture(program));
		}
		assertExplicitFunctionCarrierCandidateFacts();
	}

	private static void assertExplicitFunctionCarrierCandidateFacts() {
		FunctionOp call = PlacementIdentityKnownEqualityContractTest.functionCall(
			new String[] {"X"}, new String[] {"Y"});
		DMLProgram program = PlacementIdentityKnownEqualityContractTest.program(call);
		String before = PlacementGraphFingerprint.capture(program);
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);

		List<HopOccurrenceProjection> originals = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == call)
			.filter(occurrence -> !isFunctionBoundary(occurrence)).toList();
		Assert.assertEquals("explicit fixture must retain exactly one original FunctionOp", 1, originals.size());
		CompiledHopKey original = originals.get(0).key();
		List<CandidateRuleKey> originalKeys = analysis.candidateRuleDomain().orderedRuleKeys().stream()
			.filter(key -> key.parentOccurrence() == original).toList();
		Assert.assertFalse("original FunctionOp must remain candidate-bearing", originalKeys.isEmpty());
		for(CandidateRuleKey key : originalKeys)
			Assert.assertSame("original FunctionOp fact must retain exact parent identity", original,
				analysis.candidateRuleFacts().requireExact(original, key.orderedInputs()).key().parentOccurrence());

		List<HopOccurrenceProjection> boundaries = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.hop() == call).filter(
				CampaignBG014PlacementCandidateRuleFactsSliceATest::isFunctionBoundary).toList();
		Assert.assertEquals("one input and one output must create two synthetic boundaries", 2, boundaries.size());
		for(HopOccurrenceProjection boundary : boundaries)
			Assert.assertFalse("synthetic function boundary must not become candidate-bearing",
				analysis.candidateRuleDomain().containsExactParent(boundary.key()));
		Assert.assertEquals("explicit FunctionOp analysis must remain mutation-free", before,
			PlacementGraphFingerprint.capture(program));
	}

	private static boolean isFunctionBoundary(HopOccurrenceProjection occurrence) {
		return occurrence.key().canonicalSourceOrigin().startsWith("function-boundary:");
	}

	@Test
	public void copiedCapabilitiesMatchFreshOracleEvidenceWithoutRetainingLiveAuthority() throws Exception {
		OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
		for(String fixture : FIXTURES) {
			DMLProgram program = ProductionShadowFixtureFactory.compile(fixture);
			String before = PlacementGraphFingerprint.capture(program);
			PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
			Map<CompiledHopKey,Hop> hops = new IdentityHashMap<>();
			for(HopOccurrenceProjection occurrence : analysis.occurrences())
				hops.put(occurrence.key(), occurrence.hop());
			for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
				Hop hop = hops.get(fact.key().parentOccurrence());
				Assert.assertNotNull(fixture + " exact fact origin", hop);
				List<FType> inputs = toFTypes(fact.key().orderedInputs());
				var expected = oracle.decideWithEvidence(hop, inputs, null);
				CandidateCapabilityFact actual = fact.capability();
				Assert.assertNotNull(fixture + " canonical rule evidence", actual);
				Assert.assertEquals(expected.caps().category(), actual.category());
				Assert.assertEquals(expected.caps().opcode(), actual.opcode());
				Assert.assertEquals(expected.caps().exec(), actual.nativeExec());
				Assert.assertEquals(expected.caps().placement(), actual.nativeOutput());
				Assert.assertEquals(expected.caps().foutFType().orElse(null), actual.nativeFoutFType());
				Assert.assertEquals(expected.caps().reason(), actual.reasonCode());
				Assert.assertEquals(expected.caps().detail().orElse(""), actual.detail());
				Assert.assertEquals(expected.caps().notes().size(), actual.notes().size());
				for(int i = 0; i < actual.notes().size(); i++) {
					Assert.assertEquals(expected.caps().notes().get(i).code(), actual.notes().get(i).code());
					Assert.assertEquals(expected.caps().notes().get(i).message(), actual.notes().get(i).message());
				}
				CandidateShapeProofFact proof = fact.shapeProof();
				Assert.assertEquals(expected.shapeProof().consultedFacts(), proof.consultedFacts());
				Assert.assertEquals(expected.shapeProof().requiredFacts(), Set.copyOf(proof.requiredFacts()));
				Assert.assertEquals(expected.shapeProof().missingRequiredFacts(),
					Set.copyOf(proof.missingRequiredFacts()));
				assertProfileEvidence(oracle, hop, inputs, fact);
			}
			Assert.assertEquals(fixture + " oracle parity mutated the compiled graph", before,
				PlacementGraphFingerprint.capture(program));
		}
	}

	@Test
	public void exactLookupRejectsForeignCopiedReorderedMissingAndPresentNullKeys() throws Exception {
		DMLProgram program = ProductionShadowFixtureFactory.compile("B-15");
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		String before = PlacementGraphFingerprint.capture(program);
		List<CandidateRuleFact> beforeFacts = new ArrayList<>(analysis.candidateRuleFacts().orderedFacts());
		CandidateRuleFact exact = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().orderedInputs().size() >= 2)
			.findFirst().orElseThrow(() -> new AssertionError("B-15 must expose a two-input candidate"));

		CompiledHopKey copiedParent = copy(exact.key().parentOccurrence());
		Assert.assertEquals(exact.key().parentOccurrence(), copiedParent);
		Assert.assertNotSame(exact.key().parentOccurrence(), copiedParent);
		assertLookupFailure(analysis, copiedParent, exact.key().orderedInputs(), CandidateLookupFailure.NON_CANDIDATE_PARENT);

		PlacementAnalysis foreign = new NeutralPlacementGraphBuilder()
			.buildAnalysis(ProductionShadowFixtureFactory.compile("B-15"));
		CompiledHopKey foreignParent = foreign.candidateRuleDomain().orderedRuleKeys().stream()
			.map(CandidateRuleKey::parentOccurrence).filter(exact.key().parentOccurrence()::equals)
			.findFirst().orElseThrow(() -> new AssertionError("foreign analysis lacks value-equal parent"));
		assertLookupFailure(analysis, foreignParent, exact.key().orderedInputs(),
			CandidateLookupFailure.NON_CANDIDATE_PARENT);

		CandidateRuleFact reorderable = analysis.candidateRuleFacts().orderedFacts().stream()
			.filter(fact -> fact.key().orderedInputs().size() >= 2)
			.filter(fact -> !fact.key().orderedInputs().get(0).equals(fact.key().orderedInputs().get(1)))
			.findFirst().orElse(null);
		if(reorderable != null) {
			List<CandidateInputState> reordered = new ArrayList<>(reorderable.key().orderedInputs());
			Collections.swap(reordered, 0, 1);
			assertLookupFailure(analysis, reorderable.key().parentOccurrence(), reordered,
				CandidateLookupFailure.REORDERED_INPUTS);
		}

		List<CandidateInputState> wrongArity = new ArrayList<>(exact.key().orderedInputs());
		wrongArity.remove(wrongArity.size() - 1);
		assertLookupFailure(analysis, exact.key().parentOccurrence(), wrongArity,
			CandidateLookupFailure.MISSING_FACT);
		List<CandidateInputState> presentNull = new ArrayList<>(exact.key().orderedInputs());
		presentNull.set(0, null);
		assertLookupFailure(analysis, exact.key().parentOccurrence(), Arrays.asList(
			presentNull.toArray(new CandidateInputState[0])), CandidateLookupFailure.PRESENT_NULL);
		assertLookupFailure(analysis, exact.key().parentOccurrence(), null, CandidateLookupFailure.PRESENT_NULL);
		assertLookupFailure(analysis, null, exact.key().orderedInputs(), CandidateLookupFailure.NON_CANDIDATE_PARENT);

		Assert.assertEquals("hostile lookups changed fact order", beforeFacts,
			analysis.candidateRuleFacts().orderedFacts());
		for(int i = 0; i < beforeFacts.size(); i++)
			Assert.assertSame("hostile lookup replaced fact " + i, beforeFacts.get(i),
				analysis.candidateRuleFacts().orderedFacts().get(i));
		Assert.assertEquals("hostile lookups mutated the program", before, PlacementGraphFingerprint.capture(program));
	}

	private static void assertDeeplyImmutable(String fixture, PlacementAnalysis analysis) {
		CandidateRuleFact fact = analysis.candidateRuleFacts().orderedFacts().get(0);
		assertUnsupported(fixture + " domain", () -> analysis.candidateRuleDomain().orderedRuleKeys().clear());
		assertUnsupported(fixture + " facts", () -> analysis.candidateRuleFacts().orderedFacts().clear());
		assertUnsupported(fixture + " key inputs", () -> fact.key().orderedInputs().clear());
		assertUnsupported(fixture + " notes", () -> fact.capability().notes().clear());
		assertUnsupported(fixture + " consulted facts", () -> fact.shapeProof().consultedFacts().clear());
		assertUnsupported(fixture + " required facts", () -> fact.shapeProof().requiredFacts().clear());
		assertUnsupported(fixture + " missing required facts", () -> fact.shapeProof().missingRequiredFacts().clear());
		assertUnsupported(fixture + " profile outputs", () -> fact.profile().producerOutputs().clear());
	}

	private static void assertExactCombinationUniverse(String fixture, PlacementAnalysis analysis) {
		for(HopOccurrenceProjection parent : analysis.occurrences()) {
			if(parent.key().canonicalSourceOrigin().startsWith("function-boundary:"))
				continue;
			List<CandidateRuleKey> actual = analysis.candidateRuleDomain().orderedRuleKeys().stream()
				.filter(key -> key.parentOccurrence() == parent.key()).toList();
			Assert.assertFalse(fixture + " original occurrence lacks candidate facts: " + parent.key(), actual.isEmpty());
			Node parentNode = analysis.graph().node(parent.key()).orElseThrow();
			List<List<FType>> domains = parentNode.valueVersion().versionKind() == VersionKind.FUNCTION_INPUT
				? functionInputDomains(parent, analysis) : directInputDomains(parent.hop(), analysis);
			List<List<CandidateInputState>> expected = new ArrayList<>();
			enumerateDomains(domains, new ArrayList<>(), expected);
			Assert.assertEquals(fixture + " exact Cartesian domain for " + parent.key(), expected,
				actual.stream().map(CandidateRuleKey::orderedInputs).toList());
		}
	}

	private static List<List<FType>> directInputDomains(Hop parent, PlacementAnalysis analysis) {
		List<List<FType>> domains = new ArrayList<>();
		for(Hop input : parent.getInput())
			domains.add(domainForHop(input, analysis));
		return domains;
	}

	private static List<List<FType>> functionInputDomains(HopOccurrenceProjection parent,
		PlacementAnalysis analysis) {
		Set<FType> types = new java.util.LinkedHashSet<>();
		boolean local = false;
		for(HopOccurrenceProjection occurrence : analysis.occurrences()) {
			if(occurrence.key().canonicalSourceOrigin().startsWith("function-boundary:")
				|| !(occurrence.hop() instanceof FunctionOp))
				continue;
			FunctionOp call = (FunctionOp) occurrence.hop();
			if(!functionMatches(call, parent.key().functionNamespace()))
				continue;
			String[] names = call.getInputVariableNames();
			for(int i = 0; i < names.length && i < call.getInput().size(); i++) {
				if(!names[i].equals(parent.hop().getName()))
					continue;
				for(FType value : domainForHop(call.getInput(i), analysis)) {
					if(value == null)
						local = true;
					else
						types.add(value);
				}
			}
		}
		List<FType> domain = new ArrayList<>(types);
		domain.sort(java.util.Comparator.comparing(Enum::name));
		if(local || domain.isEmpty())
			domain.add(0, null);
		return List.of(Collections.unmodifiableList(domain));
	}

	private static List<FType> domainForHop(Hop hop, PlacementAnalysis analysis) {
		HopOccurrenceProjection occurrence = analysis.occurrences().stream()
			.filter(value -> value.hop() == hop)
			.filter(value -> !value.key().canonicalSourceOrigin().startsWith("function-boundary:"))
			.findFirst().orElse(null);
		if(occurrence == null)
			return Collections.singletonList(null);
		Node predecessor = analysis.graph().node(occurrence.key()).orElseThrow();
		Set<FType> types = new java.util.LinkedHashSet<>();
		boolean local = false;
		for(PlacementState state : predecessor.legalAlternatives()) {
			if(state.fType() == null)
				local = true;
			else
				types.add(state.fType());
		}
		if(types.isEmpty())
			return Collections.singletonList(null);
		List<FType> domain = new ArrayList<>(types);
		domain.sort(java.util.Comparator.comparing(Enum::name));
		if(local)
			domain.add(0, null);
		return Collections.unmodifiableList(domain);
	}

	private static void enumerateDomains(List<List<FType>> domains, List<FType> prefix,
		List<List<CandidateInputState>> result) {
		if(prefix.size() == domains.size()) {
			List<CandidateInputState> states = new ArrayList<>(prefix.size());
			for(FType value : prefix)
				states.add(value == null ? CandidateInputState.absentLocal() : CandidateInputState.present(value));
			result.add(List.copyOf(states));
			return;
		}
		for(FType value : domains.get(prefix.size())) {
			prefix.add(value);
			enumerateDomains(domains, prefix, result);
			prefix.remove(prefix.size() - 1);
		}
	}

	private static boolean functionMatches(FunctionOp call, String namespace) {
		return namespace.equals(call.getFunctionName()) || namespace.endsWith("::" + call.getFunctionName())
			|| namespace.endsWith("/" + call.getFunctionName());
	}

	private static void assertProfileEvidence(OracleFacade oracle, Hop hop, List<FType> inputs,
		CandidateRuleFact fact) {
		if(fact.status() == PlacementAnalysis.CandidateEvaluationStatus.RULE_ERROR)
			return;
		try {
			FTypeProfile profile = oracle.inferProfile(hop, profileDomains(hop, inputs), null);
			Assert.assertEquals(profile == null ? List.of() : profile.outputs(), fact.profile().producerOutputs());
			Assert.assertTrue(fact.profile().evaluationFailure(), fact.profile().available());
		}
		catch(Throwable expectedFailure) {
			Assert.assertEquals(List.of(), fact.profile().producerOutputs());
			Assert.assertEquals("PROFILE_ERROR:" + expectedFailure.getClass().getSimpleName(),
				fact.profile().evaluationFailure());
		}
	}

	private static List<List<FType>> profileDomains(Hop hop, List<FType> inputs) {
		List<List<FType>> domains = new ArrayList<>();
		for(int i = 0; i < hop.getInput().size(); i++) {
			FType known = i < inputs.size() ? inputs.get(i) : null;
			if(known != null)
				domains.add(List.of(known));
			else if(hop.getInput(i).getDataType() != null && hop.getInput(i).getDataType().isMatrix())
				domains.add(PlacementCandidateRuleResolver.matrixFTypeCandidates());
			else
				domains.add(Collections.singletonList(null));
		}
		return domains;
	}

	private static List<FType> toFTypes(List<CandidateInputState> inputs) {
		List<FType> result = new ArrayList<>(inputs.size());
		for(CandidateInputState input : inputs)
			result.add(input.present() ? input.fType() : null);
		return result;
	}

	private static CompiledHopKey copy(CompiledHopKey key) {
		return new CompiledHopKey(key.programFingerprint(), key.functionNamespace(), key.callSitePath(),
			key.recompileContext(), key.controlRegion(), key.emittedHopInstance(), key.canonicalSourceOrigin());
	}

	private static void assertLookupFailure(PlacementAnalysis analysis, CompiledHopKey parent,
		List<CandidateInputState> inputs, CandidateLookupFailure expected) {
		try {
			analysis.candidateRuleFacts().requireExact(parent, inputs);
			Assert.fail("expected " + expected);
		}
		catch(CandidateRuleLookupException actual) {
			Assert.assertEquals(expected, actual.failure());
		}
	}

	private static void assertUnsupported(String label, Runnable mutation) {
		try {
			mutation.run();
			Assert.fail(label + " must be immutable");
		}
		catch(UnsupportedOperationException expected) {
			// expected
		}
	}
}
