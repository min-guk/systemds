/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateProfileFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementRealizationKey;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class FactorizedCandidateReceiptRankTest {
	private static final String FINGERPRINT = "factorized-receipt-rank";
	private static final ControlRegionKey REGION = new ControlRegionKey(
		FINGERPRINT, "main", List.of("root"), "root", "compiled");
	private static final PlacementEmissionState LOCAL = new PlacementEmissionState(
		new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false), false);

	@Test
	public void ranksRequestedFactorizedClausesWithoutExpandingTheProduct() throws Exception {
		CandidateEmissionRealization large = factorized(100, 100, "large");
		FactorizedSupportClauses clauses = (FactorizedSupportClauses)large.supportClauses();
		CandidateRuleFact largeFact = fact(key("a-large"), large);
		CandidateRuleFact otherFact = fact(key("z-other"), CandidateEmissionRealization.local(LOCAL));
		Object domain = receiptDomain(List.of(largeFact, otherFact));

		CandidateRealizationSupportClause selected = clauses.get(4_237);
		assertEquals(1, clauses.materializedClauseCount());
		CandidateSelectionReceipt receipt = receipt(domain, largeFact, large, selected);
		int selectedRank = rank(domain, receipt);
		assertTrue(selectedRank >= 0 && selectedRank < 10_000);
		assertEquals(selectedRank, rank(domain, receipt));
		assertEquals(1, clauses.materializedClauseCount());
		Object rankIndex = factorizedRankIndex(domain);

		CandidateRealizationSupportClause second = clauses.get(8_765);
		CandidateSelectionReceipt secondReceipt = receipt(domain, largeFact, large, second);
		assertTrue(rank(domain, secondReceipt) >= 0);
		assertSame(rankIndex, factorizedRankIndex(domain));
		assertEquals(2, clauses.materializedClauseCount());

		CandidateEmissionRealization other = otherFact.allowedEmissionFacts().get(0).realizations().get(0);
		CandidateSelectionReceipt otherReceipt = receipt(domain, otherFact, other,
			other.supportClauses().get(0));
		assertEquals(10_000, rank(domain, otherReceipt));
		assertEquals(2, clauses.materializedClauseCount());
	}

	@Test
	public void factorizedOrdinalsMatchExplicitCanonicalReceiptRanks() throws Exception {
		CandidateEmissionRealization factorized = factorized(
			List.of("a", "much-longer-left-option"),
			List.of("b", "medium-right", "very-much-longer-right-option"));
		List<CandidateRealizationSupportClause> explicitClauses = new ArrayList<>();
		FactorizedSupportClauses factorizedClauses =
			(FactorizedSupportClauses)factorized.supportClauses();
		for(int ordinal = 0; ordinal < factorizedClauses.size(); ordinal++)
			explicitClauses.add(factorizedClauses.get(ordinal));
		CandidateEmissionRealization explicit = new CandidateEmissionRealization(
			PlacementRealizationKey.local(LOCAL), explicitClauses);

		CandidateRuleFact factorizedFact = fact(key("a-small"), factorized);
		CandidateRuleFact explicitFact = fact(key("a-small"), explicit);
		Object factorizedDomain = receiptDomain(List.of(factorizedFact));
		Object explicitDomain = receiptDomain(List.of(explicitFact));
		for(int ordinal = 0; ordinal < factorizedClauses.size(); ordinal++) {
			CandidateSelectionReceipt factorizedReceipt = receipt(factorizedDomain,
				factorizedFact, factorized, factorized.supportClauses().get(ordinal));
			CandidateSelectionReceipt explicitReceipt = receipt(explicitDomain,
				explicitFact, explicit, explicit.supportClauses().get(ordinal));
			assertEquals(rank(explicitDomain, explicitReceipt),
				rank(factorizedDomain, factorizedReceipt));
		}
	}

	private static CandidateEmissionRealization factorized(int leftSize, int rightSize, String prefix) {
		List<String> left = new ArrayList<>();
		List<String> right = new ArrayList<>();
		for(int option = 0; option < leftSize; option++)
			left.add(prefix + "-left-" + option);
		for(int option = 0; option < rightSize; option++)
			right.add(prefix + "-right-" + option);
		return factorized(left, right);
	}

	private static CandidateEmissionRealization factorized(List<String> leftIds, List<String> rightIds) {
		List<CandidateRealizationInputBinding> left = leftIds.stream()
			.map(id -> binding(0, id)).toList();
		List<CandidateRealizationInputBinding> right = rightIds.stream()
			.map(id -> binding(1, id)).toList();
		return CandidateEmissionRealization.factorized(PlacementRealizationKey.local(LOCAL),
			List.of(), List.of(left, right), null, true);
	}

	private static CandidateRealizationInputBinding binding(int position, String id) {
		CompiledHopKey owner = key(id);
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of(CandidateInputState.absentLocal()));
		CandidateRealizationReference reference = new CandidateRealizationReference(
			rule, PlacementRealizationKey.local(LOCAL));
		return CandidateRealizationInputBinding.direct(position, reference);
	}

	private static CandidateRuleFact fact(CompiledHopKey owner, CandidateEmissionRealization realization) {
		CandidateRuleKey rule = new CandidateRuleKey(owner, List.of());
		CandidateEmissionFact emission = new CandidateEmissionFact(
			LOCAL, null, null, List.of(realization));
		return new CandidateRuleFact(rule, CandidateEvaluationStatus.AVAILABLE,
			new CandidateCapabilityFact(OpCategory.OTHER, "fixture", ExecType.CP,
				FederatedOutput.LOUT, null, ReasonCode.OK, "fixture", List.of()),
			new CandidateShapeProofFact(Map.of(), List.of(), List.of()),
			new CandidateProfileFact(List.of(), ""), List.of(emission), "");
	}

	private static CompiledHopKey key(String id) {
		return new CompiledHopKey(FINGERPRINT, "main", "root", "compiled", REGION, id, id);
	}

	private static Object receiptDomain(List<CandidateRuleFact> facts) throws Exception {
		List<CandidateRuleKey> rules = facts.stream().map(CandidateRuleFact::key).toList();
		CandidateRuleFacts ruleFacts = new CandidateRuleFacts(
			new CandidateRuleDomain(FINGERPRINT, rules, List.of()), facts);
		Class<?> type = receiptDomainClass();
		Constructor<?> constructor = type.getDeclaredConstructor(CandidateRuleFacts.class);
		constructor.setAccessible(true);
		return constructor.newInstance(ruleFacts);
	}

	private static CandidateSelectionReceipt receipt(Object domain, CandidateRuleFact fact,
		CandidateEmissionRealization realization, CandidateRealizationSupportClause clause) throws Exception {
		Method require = domain.getClass().getDeclaredMethod("require", CandidateRuleKey.class,
			CandidateEmissionFact.class, CandidateEmissionRealization.class,
			CandidateRealizationSupportClause.class);
		require.setAccessible(true);
		return (CandidateSelectionReceipt)require.invoke(domain, fact.key(),
			fact.allowedEmissionFacts().get(0), realization, clause);
	}

	private static int rank(Object domain, CandidateSelectionReceipt receipt) throws Exception {
		Method rank = domain.getClass().getDeclaredMethod("rank", CandidateSelectionReceipt.class);
		rank.setAccessible(true);
		return (int)rank.invoke(domain, receipt);
	}

	private static Object factorizedRankIndex(Object domain) throws Exception {
		Field groupsField = domain.getClass().getDeclaredField("groups");
		groupsField.setAccessible(true);
		for(Object group : (List<?>)groupsField.get(domain)) {
			Field indexField = group.getClass().getDeclaredField("factorizedRankIndex");
			indexField.setAccessible(true);
			Object index = indexField.get(group);
			if(index != null)
				return index;
		}
		throw new AssertionError("Factorized rank index was not initialized");
	}

	private static Class<?> receiptDomainClass() {
		for(Class<?> type : PlacementAnalysis.class.getDeclaredClasses())
			if(type.getSimpleName().equals("CandidateReceiptDomain"))
				return type;
		throw new AssertionError("CandidateReceiptDomain was not found");
	}
}
