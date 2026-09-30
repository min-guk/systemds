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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthority;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;

/** Test-only exact projection of a complete shared-planner witness into the DP domain. */
final class ExactPhysicalWitnessEncoding {
	private ExactPhysicalWitnessEncoding() { }

	record EncodedWitness(List<Integer> assignment, List<Alternative> alternatives) {
		EncodedWitness {
			assignment = List.copyOf(assignment);
			alternatives = List.copyOf(alternatives);
		}
	}

	record HardFactorFailure(int ordinal, List<String> scope, double value) {
		HardFactorFailure {
			scope = List.copyOf(scope);
		}
	}

	record Evaluation(EncodedWitness witness, HardFactorFailure firstHardFailure,
		Long canonicalCostBits) { }

	static EncodedWitness encode(ExactPhysicalModel model, NormalizedPlannerResult selected) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(selected, "selected");
		if(selected.analysis() != model.analysis())
			throw new IllegalArgumentException("EXACT_WITNESS_FOREIGN_ANALYSIS");
		Map<CompiledHopKey,CandidateSelectionReceipt> receipts = receiptsByDecision(
			model.analysis(), selected);
		List<Integer> assignment = new ArrayList<>(model.domains().size());
		List<Alternative> alternatives = new ArrayList<>(model.domains().size());
		for(DecisionDomain domain : model.domains()) {
			CompiledHopKey decision = domain.node().key();
			var selectedState = selected.selectedStates().get(decision);
			if(selectedState == null)
				throw new IllegalArgumentException("EXACT_WITNESS_STATE_MISSING|decision="
					+ decision.normalizedSignature());
			if(domain.node().legalAlternatives().stream().noneMatch(state -> state == selectedState))
				throw new IllegalArgumentException("EXACT_WITNESS_STALE_STATE_AUTHORITY|decision="
					+ decision.normalizedSignature());
			CandidateSelectionReceipt receipt = receipts.get(decision);
			List<Integer> matches = new ArrayList<>();
			for(int value = 0; value < domain.alternatives().size(); value++) {
				Alternative alternative = domain.alternatives().get(value);
				if(alternative.state() == selectedState
					&& matchesReceipt(alternative, receipt)
					&& matchesInputAuthorities(alternative, selected.selectedRelocationChoices())
					&& matchesProducerRelocation(alternative, selected))
					matches.add(value);
			}
			int value = requireUnique(decision, receipt, domain, matches);
			assignment.add(value);
			alternatives.add(domain.alternatives().get(value));
		}
		if(assignment.size() != selected.selectedStates().size())
			throw new IllegalArgumentException("EXACT_WITNESS_STATE_COVERAGE_MISMATCH|dp="
				+ assignment.size() + "|selected=" + selected.selectedStates().size());
		return new EncodedWitness(assignment, alternatives);
	}

	static Evaluation evaluate(ExactPhysicalModel model, NormalizedPlannerResult selected,
		LongSupplier canonicalCost) {
		EncodedWitness witness = encode(model, selected);
		return evaluateHardThenCost(witness, model.variables(), model.hardFactors(), canonicalCost);
	}

	static Evaluation evaluateHardThenCost(EncodedWitness witness,
		List<ExactCategoricalSolver.Variable> variables,
		List<ExactCategoricalSolver.Factor> hardFactors, LongSupplier canonicalCost) {
		Objects.requireNonNull(witness, "witness");
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(hardFactors, "hardFactors");
		IdentityHashMap<ExactCategoricalSolver.Variable,Integer> positions = new IdentityHashMap<>();
		for(int index = 0; index < variables.size(); index++)
			positions.put(variables.get(index), index);
		for(int ordinal = 0; ordinal < hardFactors.size(); ordinal++) {
			ExactCategoricalSolver.Factor factor = hardFactors.get(ordinal);
			int[] local = new int[factor.scope().size()];
			List<String> scope = new ArrayList<>(local.length);
			for(int index = 0; index < local.length; index++) {
				Integer global = positions.get(factor.scope().get(index));
				if(global == null)
					throw new IllegalArgumentException("EXACT_WITNESS_HARD_FACTOR_FOREIGN_VARIABLE");
				local[index] = witness.assignment().get(global);
				scope.add(factor.scope().get(index).key() + '=' + local[index]
					+ "|alternative=" + witness.alternatives().get(global).signature());
			}
			double value = factor.cost(local);
			if(!Double.isFinite(value))
				return new Evaluation(witness, new HardFactorFailure(ordinal, scope, value), null);
		}
		return new Evaluation(witness, null, canonicalCost == null ? null : canonicalCost.getAsLong());
	}

	private static Map<CompiledHopKey,CandidateSelectionReceipt> receiptsByDecision(
		org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis analysis,
		NormalizedPlannerResult selected) {
		Map<CompiledHopKey,CandidateSelectionReceipt> receipts = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : selected.selectedCandidateSelections()) {
			CompiledHopKey owner = receipt.rule().parentOccurrence();
			if(analysis.graph().decisionNodes().stream().noneMatch(node -> node.key() == owner))
				throw new IllegalArgumentException("EXACT_WITNESS_STALE_CANDIDATE_AUTHORITY|receipt="
					+ receipt.normalizedSignature());
			var ownedRule = analysis.candidateRuleFacts().requireExact(
				owner, receipt.rule().orderedInputs());
			boolean owned = ownedRule.key() == receipt.rule()
				&& ownedRule.allowedEmissionFacts().stream().anyMatch(emission ->
					emission == receipt.emission()
						&& emission.realizations().stream().anyMatch(realization ->
							realization == receipt.realization()
								&& realization.supportClauses().stream().anyMatch(clause ->
									clause == receipt.supportClause())));
			if(!owned)
				throw new IllegalArgumentException("EXACT_WITNESS_STALE_CANDIDATE_AUTHORITY|receipt="
					+ receipt.normalizedSignature());
			if(receipts.put(owner, receipt) != null)
				throw new IllegalArgumentException("EXACT_WITNESS_MULTIPLE_RECEIPTS|decision="
					+ owner.normalizedSignature());
		}
		return receipts;
	}

	private static boolean matchesReceipt(Alternative alternative,
		CandidateSelectionReceipt receipt) {
		var rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
		var emission = alternative.captured()
			? alternative.candidateEmission() : alternative.executionEmission();
		if(receipt == null)
			return rule == null && emission == null && alternative.realization() == null
				&& alternative.supportClause() == null;
		return rule != null && emission != null
			&& rule.key() == receipt.rule()
			&& emission == receipt.emission()
			&& alternative.realization() == receipt.realization()
			&& alternative.supportClause() == receipt.supportClause();
	}

	private static boolean matchesInputAuthorities(Alternative alternative,
		List<RelocationChoiceReceipt> choices) {
		for(InputAuthority authority : alternative.inputAuthorities()) {
			List<RelocationChoiceReceipt> atInput = choices.stream().filter(choice ->
				choice.demand().consumer() == alternative.decision()
					&& choice.demand().inputPosition() == authority.inputPosition()).toList();
			if(authority.kind() == InputAuthorityKind.RELOCATION) {
				if(atInput.size() != 1 || authority.relocationAction() == null
					|| !atInput.get(0).action().equals(authority.relocationAction().key()))
					return false;
			}
			else if(!atInput.isEmpty())
				return false;
		}
		return true;
	}

	private static boolean matchesProducerRelocation(Alternative alternative,
		NormalizedPlannerResult selected) {
		boolean selectedAction = alternative.relocationAction() != null
			&& selected.selectedRelocations().contains(alternative.relocationAction().key());
		boolean anySelectedForValue = selected.selectedRelocations().stream().anyMatch(key ->
			key.sourceValueVersion().equals(alternative.relocationAction() == null
				? selected.analysis().graph().node(alternative.decision()).orElseThrow().valueVersion()
				: alternative.relocationAction().key().sourceValueVersion())
				&& key.targetPlacement().equals(alternative.state()));
		return alternative.relocationAction() == null ? !anySelectedForValue : selectedAction;
	}

	static int requireUnique(CompiledHopKey decision, CandidateSelectionReceipt receipt,
		DecisionDomain domain, List<Integer> matches) {
		if(matches.size() == 1)
			return matches.get(0);
		Map<Integer,String> inventory = new LinkedHashMap<>();
		for(int value = 0; value < domain.alternatives().size(); value++)
			inventory.put(value, domain.alternatives().get(value).signature());
		throw new IllegalArgumentException((matches.isEmpty()
			? "EXACT_WITNESS_ALTERNATIVE_MISSING" : "EXACT_WITNESS_ALTERNATIVE_AMBIGUOUS")
			+ "|decision=" + decision.normalizedSignature()
			+ "|receipt=" + (receipt == null ? "-" : receipt.normalizedSignature())
			+ "|matches=" + matches + "|alternatives=" + inventory);
	}
}
