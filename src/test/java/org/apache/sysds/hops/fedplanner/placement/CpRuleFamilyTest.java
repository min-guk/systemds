/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.math.BigInteger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class CpRuleFamilyTest {
	private static final String FINGERPRINT = "cp-rule-family";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");

	@Test
	public void keepsRectangleSymbolicAndInternsOnlyRequestedExactMembers() {
		CpRuleFamily family = family(state("original"));
		assertEquals(BigInteger.valueOf(6), family.logicalSize());
		assertEquals(0, family.materializedMemberCount());

		List<CandidateInputState> arbitrary = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.BROADCAST));
		CandidateRuleFact exact = family.requireExact(arbitrary);
		assertSame(exact, family.requireExact(arbitrary));
		assertEquals(arbitrary, exact.key().orderedInputs());
		assertEquals(1, family.materializedMemberCount());

		CandidateRuleFact canonical = family.canonicalMember();
		assertEquals(family.canonicalInputs(), canonical.key().orderedInputs());
		assertEquals(2, family.materializedMemberCount());
		assertThrows(IllegalArgumentException.class, () -> family.requireExact(List.of(
			CandidateInputState.present(FType.COL), CandidateInputState.present(FType.FULL))));
	}

	@Test
	public void analysisLookupRestoresArbitraryMemberWithoutExpandingSiblings() {
		CpRuleFamily family = family(state("original"));
		CandidateRuleDomain domain = new CandidateRuleDomain(FINGERPRINT, List.of(), List.of(),
			List.of(), List.of(family));
		CandidateRuleFacts facts = new CandidateRuleFacts(domain, List.of(), List.of(family));
		List<CandidateInputState> requested = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.present(FType.ROW));

		assertSame(family.requireExact(requested), facts.requireExact(family.parent(), requested));
		assertEquals(1, family.materializedMemberCount());
		CandidateEmissionRealization realization = family.requireExact(requested)
			.allowedEmissionFacts().get(0).realizations().get(0);
		assertSame(realization, facts.requireExactRealization(
			CandidateRealizationReference.of(family.requireExact(requested).key(), realization)));
	}

	@Test
	public void rejectsExplicitRowsThatOverlapFamilyMembership() {
		CpRuleFamily family = family(state("overlap"));
		CandidateRuleFact explicit = family.requireExact(family.canonicalInputs());
		CandidateRuleDomain domain = new CandidateRuleDomain(FINGERPRINT,
			List.of(explicit.key()), List.of(), List.of(), List.of(family));
		assertThrows(IllegalArgumentException.class,
			() -> new CandidateRuleFacts(domain, List.of(explicit), List.of(family)));
	}

	@Test
	public void bindsGraphOwnedCpStateWithoutMaterializingAnyTuple() {
		CpRuleFamily original = family(state("original"));
		PlacementState graphOwned = state("graph-owned");
		CpRuleFamily rebound = original.withEmissionState(graphOwned);

		assertNotSame(original, rebound);
		assertSame(graphOwned, rebound.emission().emissionState().placementState());
		assertEquals(original.axes(), rebound.axes());
		assertEquals(0, original.materializedMemberCount());
		assertEquals(0, rebound.materializedMemberCount());
		assertSame(rebound, rebound.withEmissionState(graphOwned));
	}

	@Test
	public void receiptRanksConvergeToTheSameOrderRegardlessOfFamilyLookupOrder() throws Exception {
		List<CandidateInputState> first = List.of(
			CandidateInputState.present(FType.ROW), CandidateInputState.present(FType.BROADCAST));
		List<CandidateInputState> second = List.of(
			CandidateInputState.absentLocal(), CandidateInputState.present(FType.ROW));
		int[] forward = finalRanks(first, second);
		int[] reverse = finalRanks(second, first);
		assertEquals(forward[0], reverse[1]);
		assertEquals(forward[1], reverse[0]);
	}

	@Test
	public void canonicalMemberFollowsReceiptLengthPrefixAcrossDecimalBoundary() {
		CandidateInputState shortState = CandidateInputState.present(FType.FULL);
		CandidateInputState longState = CandidateInputState.present(FType.BROADCAST);
		CompiledHopKey boundaryParent = null;
		List<List<CandidateInputState>> combinations = List.of(
			List.of(shortState, shortState), List.of(shortState, longState),
			List.of(longState, shortState), List.of(longState, longState));
		for(int length = 1; length < 5000; length++) {
			CompiledHopKey candidate = key("x".repeat(length));
			List<Integer> lengths = combinations.stream().map(inputs ->
				new PlacementAnalysis.CandidateRuleKey(candidate, inputs).normalizedSignature().length()).toList();
			int shortest = lengths.stream().mapToInt(Integer::intValue).min().orElseThrow();
			int prefixBest = lengths.stream().min((left, right) ->
				(Integer.toString(left) + ':').compareTo(Integer.toString(right) + ':')).orElseThrow();
			if(prefixBest != shortest) {
				boundaryParent = candidate;
				break;
			}
		}
		assertNotNull("fixture must straddle a decimal length-prefix boundary", boundaryParent);
		CpRuleFamily family = family(boundaryParent,
			List.of(List.of(shortState, longState), List.of(shortState, longState)), state("boundary"));
		List<CandidateRuleFact> members = combinations.stream().map(family::requireExact).toList();
		CandidateRuleFact expected = members.stream().min((left, right) -> {
			String leftRule = left.key().normalizedSignature();
			String rightRule = right.key().normalizedSignature();
			int order = (Integer.toString(leftRule.length()) + ':')
				.compareTo(Integer.toString(rightRule.length()) + ':');
			return order != 0 ? order : leftRule.compareTo(rightRule);
		}).orElseThrow();
		assertSame(expected, family.canonicalMember());

		List<CandidateSelectionReceipt> receipts = members.stream().map(fact ->
			new CandidateSelectionReceipt(fact.key(), fact.allowedEmissionFacts().get(0), List.of())).toList();
		CandidateSelectionReceipt expectedReceipt = receipts.stream().min(CandidateSelectionReceipt::compareTo)
			.orElseThrow();
		assertEquals(expected.key(), expectedReceipt.rule());
	}

	private static int[] finalRanks(List<CandidateInputState> first,
		List<CandidateInputState> second) throws Exception {
		CpRuleFamily family = family(state("rank"));
		CandidateRuleDomain domain = new CandidateRuleDomain(FINGERPRINT, List.of(), List.of(),
			List.of(), List.of(family));
		CandidateRuleFacts facts = new CandidateRuleFacts(domain, List.of(), List.of(family));
		Class<?> receiptType = java.util.Arrays.stream(PlacementAnalysis.class.getDeclaredClasses())
			.filter(type -> type.getSimpleName().equals("CandidateReceiptDomain")).findFirst().orElseThrow();
		Constructor<?> constructor = receiptType.getDeclaredConstructor(CandidateRuleFacts.class);
		constructor.setAccessible(true);
		Object receipts = constructor.newInstance(facts);
		CandidateRuleFact firstFact = facts.requireExact(family.parent(), first);
		CandidateSelectionReceipt firstReceipt = new CandidateSelectionReceipt(firstFact.key(),
			firstFact.allowedEmissionFacts().get(0), List.of());
		rank(receipts, firstReceipt);
		CandidateSelectionReceipt secondReceipt = receipt(receipts, facts.requireExact(family.parent(), second));
		rank(receipts, secondReceipt);
		return new int[] {rank(receipts, firstReceipt), rank(receipts, secondReceipt)};
	}

	private static CandidateSelectionReceipt receipt(Object domain, CandidateRuleFact fact) throws Exception {
		CandidateEmissionFact emission = fact.allowedEmissionFacts().get(0);
		CandidateEmissionRealization realization = emission.realizations().get(0);
		CandidateRealizationSupportClause clause = realization.supportClauses().get(0);
		Method require = domain.getClass().getDeclaredMethod("require",
			PlacementAnalysis.CandidateRuleKey.class, CandidateEmissionFact.class,
			CandidateEmissionRealization.class, CandidateRealizationSupportClause.class);
		require.setAccessible(true);
		return (CandidateSelectionReceipt)require.invoke(domain, fact.key(), emission, realization, clause);
	}

	private static int rank(Object domain, CandidateSelectionReceipt receipt) throws Exception {
		Method rank = domain.getClass().getDeclaredMethod("rank", CandidateSelectionReceipt.class);
		rank.setAccessible(true);
		return (int)rank.invoke(domain, receipt);
	}

	private static CpRuleFamily family(PlacementState state) {
		CompiledHopKey parent = key("weighted");
		List<List<CandidateInputState>> axes = List.of(
			List.of(CandidateInputState.present(FType.ROW), CandidateInputState.absentLocal()),
			List.of(CandidateInputState.present(FType.BROADCAST),
				CandidateInputState.present(FType.ROW), CandidateInputState.absentLocal()));
		return family(parent, axes, state);
	}

	private static CpRuleFamily family(CompiledHopKey parent,
		List<List<CandidateInputState>> axes, PlacementState state) {
		CandidateCapabilityFact capability = new CandidateCapabilityFact(OpCategory.QUATERNARY,
			"WSLOSS", ExecType.CP, FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of());
		CandidateEmissionFact emission = new CandidateEmissionFact(
			new PlacementEmissionState(state, false), null);
		return new CpRuleFamily(parent, axes, capability,
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), emission);
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private static PlacementState state(String ignored) {
		return new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
	}
}
