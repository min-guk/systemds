/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactPhysicalModel.InputAuthority;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;

/**
 * Evaluates another planner's exact normalized authority on the immutable MinST
 * physical universe and canonical cost surface.
 *
 * <p>This is not a wall-clock claim.  It proves that the supplied baseline is a
 * represented feasible point of the same finite physical optimization problem and
 * reports its objective under exactly the factors minimized by production MinST.</p>
 */
final class MinStExactPhysicalBaselineEvaluator {
	record Evaluation(long canonicalObjectiveBits, List<Integer> assignmentInDecisionOrder,
		MinStExactPhysicalSelection physicalSelection, NormalizedPlannerResult projectedBaseline) {
		Evaluation { assignmentInDecisionOrder = List.copyOf(assignmentInDecisionOrder); }
	}

	private MinStExactPhysicalBaselineEvaluator() { }

	static Evaluation evaluate(MinStExactPhysicalModel model,
		MinStExactCostFactsProducer.PhysicalCostSurface surface,
		NormalizedPlannerResult baseline, MinStExactCategoricalSolver.Limits limits) {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(surface, "surface");
		Objects.requireNonNull(baseline, "baseline");
		if(baseline.analysis() != model.analysis() || surface.owner() != model.analysis())
			throw new IllegalArgumentException("MINST_BASELINE_OWNER_MISMATCH");
		model.analysis().assertProgramStructureUnchanged();

		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : baseline.selectedCandidateSelections())
			if(candidates.put(candidate.rule().parentOccurrence(), candidate) != null)
				throw new IllegalArgumentException("MINST_BASELINE_CANDIDATE_DUPLICATE");
		Map<RelocationDemandKey,RelocationChoiceReceipt> choices = new LinkedHashMap<>();
		for(RelocationChoiceReceipt choice : baseline.selectedRelocationChoices())
			if(choices.put(choice.demand(), choice) != null)
				throw new IllegalArgumentException("MINST_BASELINE_RELOCATION_DUPLICATE");
		Set<RelocationActionKey> emitted = new LinkedHashSet<>(baseline.selectedRelocations());

		List<MinStExactCategoricalSolver.Factor> factors = new ArrayList<>(model.hardFactors());
		factors.addAll(surface.factors());
		List<Integer> firstAdmittedAssignment = new ArrayList<>(model.domains().size());
		for(var domain : model.domains()) {
			boolean[] admitted = new boolean[domain.alternatives().size()];
			for(int value = 0; value < admitted.length; value++)
				admitted[value] = matchesBaseline(model, domain.alternatives().get(value), baseline,
					candidates, choices, emitted);
			int firstAdmitted = java.util.stream.IntStream.range(0, admitted.length)
				.filter(value -> admitted[value]).findFirst().orElse(-1);
			if(firstAdmitted < 0)
				throw new IllegalArgumentException("MINST_BASELINE_ALTERNATIVE_MISSING|decision="
					+ domain.node().key().normalizedSignature() + "|planner=" + baseline.plannerId()
					+ "|state=" + requireEmission(baseline, domain.node().key()).normalizedSignature()
					+ "|candidateInputs=" + (candidates.get(domain.node().key()) == null ? "-"
						: candidates.get(domain.node().key()).rule().orderedInputs())
					+ "|candidateDerived=" + (candidates.get(domain.node().key()) != null
						&& candidates.get(domain.node().key()).emission().emissionState().derivedFedFout())
					+ "|baselineChoices=" + choices.values().stream()
						.filter(choice -> choice.demand().consumer() == domain.node().key())
						.map(choice -> choice.demand().inputPosition() + ":"
							+ choice.action().materializationFType() + ":anchor="
							+ Integer.toHexString(choice.action().durableAnchor().hashCode())
							+ ":active=" + emitted.contains(choice.action())).toList()
					+ "|sameStateAlternatives=" + domain.alternatives().stream()
						.filter(alternative -> alternative.state().equals(requireEmission(
							baseline, domain.node().key()).placementState()))
						.map(alternative -> alternative.authorityKind() + ":captured="
							+ alternative.captured() + ":derived="
							+ (alternative.candidateEmission() != null && alternative
								.candidateEmission().emissionState().derivedFedFout())
							+ ":ruleEq=" + (candidates.get(domain.node().key()) != null
								&& alternative.candidateRule() != null
								&& candidates.get(domain.node().key()).rule().equals(
									alternative.candidateRule().key()))
							+ ":ruleId=" + (candidates.get(domain.node().key()) != null
								&& alternative.candidateRule() != null
								&& candidates.get(domain.node().key()).rule()
									== alternative.candidateRule().key())
							+ ":emissionEq=" + (candidates.get(domain.node().key()) != null
								&& alternative.candidateEmission() != null
								&& candidates.get(domain.node().key()).emission().equals(
									alternative.candidateEmission()))
							+ ":emissionId=" + (candidates.get(domain.node().key()) != null
								&& candidates.get(domain.node().key()).emission()
									== alternative.candidateEmission())
							+ ":inputsMatch=" + matchesInputAuthorities(alternative, choices, emitted)
							+ ":derivedFoutAction=" + (alternative.derivedFoutAction() != null)
							+ ":inputs=" + alternative.inputAuthorities().stream()
								.map(authority -> authority.inputPosition() + ":" + authority.kind()
									+ ":action=" + (authority.relocationAction() != null)).toList())
						.toList());
			firstAdmittedAssignment.add(firstAdmitted);
			factors.add(MinStExactCategoricalSolver.Factor.lazy(List.of(domain.variable()),
				values -> admitted[values[0]] ? 0.0 : Double.POSITIVE_INFINITY));
		}
		assertBaselineSatisfiesHardFactors(model, firstAdmittedAssignment, baseline.plannerId());

		MinStExactCategoricalSolver.Result solved = MinStExactCategoricalSolver.solve(
			model.variables(), factors, limits);
		long objectiveBits = surface.evaluateCanonical(solved.assignmentInVariableOrder());
		if(Double.doubleToRawLongBits(solved.objective()) != objectiveBits)
			throw new IllegalArgumentException("MINST_BASELINE_CANONICAL_OBJECTIVE_MISMATCH");
		MinStExactPhysicalOptimizer.Result locked = new MinStExactPhysicalOptimizer.Result(
			solved, objectiveBits, surface.contributionFingerprint());
		MinStExactPhysicalSelection selection = MinStExactPhysicalSelection.create(model, locked);
		NormalizedPlannerResult projected = MinStExactPhysicalPlacementProjector.project(selection)
			.normalizedResult();
		assertSamePhysicalAuthority(baseline, projected);
		return new Evaluation(objectiveBits, solved.assignmentInVariableOrder(), selection, projected);
	}

	private static void assertBaselineSatisfiesHardFactors(MinStExactPhysicalModel model,
		List<Integer> assignment, String plannerId) {
		Map<MinStExactCategoricalSolver.Variable,Integer> values = new LinkedHashMap<>();
		for(int index = 0; index < model.variables().size(); index++)
			values.put(model.variables().get(index), assignment.get(index));
		for(int factorIndex = 0; factorIndex < model.hardFactors().size(); factorIndex++) {
			MinStExactCategoricalSolver.Factor factor = model.hardFactors().get(factorIndex);
			int[] local = factor.scope().stream().mapToInt(values::get).toArray();
			if(factor.cost(local) == Double.POSITIVE_INFINITY)
				throw new IllegalArgumentException("MINST_BASELINE_HARD_FACTOR_REJECTED|planner="
					+ plannerId + "|factor=" + factorIndex + "|scope="
					+ factor.scope().stream().map(MinStExactCategoricalSolver.Variable::key).toList()
					+ "|alternatives=" + factor.scope().stream().map(variable -> {
						MinStExactPhysicalModel.DecisionDomain domain = model.domains().stream()
							.filter(candidate -> candidate.variable() == variable).findFirst().orElseThrow();
						return domain.alternatives().get(values.get(variable)).signature();
					}).toList());
		}
	}

	private static boolean matchesBaseline(MinStExactPhysicalModel model, Alternative alternative,
		NormalizedPlannerResult baseline, Map<CompiledHopKey,CandidateSelectionReceipt> candidates,
		Map<RelocationDemandKey,RelocationChoiceReceipt> choices, Set<RelocationActionKey> emitted) {
		CompiledHopKey decision = alternative.decision();
		PlacementEmissionState expected = requireEmission(baseline, decision);
		if(!alternative.state().equals(expected.placementState()))
			return false;

		CandidateSelectionReceipt candidate = candidates.get(decision);
		var rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
		var emission = alternative.captured()
			? alternative.candidateEmission() : alternative.executionEmission();
		if(candidate == null ? rule != null || emission != null
			: rule == null || candidate.rule() != rule.key() || candidate.emission() != emission)
			return false;
		if(candidate == null && expected.derivedFedFout()
			|| candidate != null && candidate.emission().emissionState().derivedFedFout()
				!= expected.derivedFedFout())
			return false;

		var expectedOutput = candidate == null ? null : candidate.emission().derivedFoutAction();
		var actualOutput = alternative.derivedFoutAction() == null
			? null : alternative.derivedFoutAction().key();
		if(!java.util.Objects.equals(expectedOutput, actualOutput))
			return false;
		return matchesInputAuthorities(alternative, choices, emitted);
	}

	private static boolean matchesInputAuthorities(Alternative alternative,
		Map<RelocationDemandKey,RelocationChoiceReceipt> choices,
		Set<RelocationActionKey> emitted) {
		CompiledHopKey decision = alternative.decision();
		Map<RelocationDemandKey,RelocationActionKey> actualInputs = new LinkedHashMap<>();
		for(InputAuthority authority : alternative.inputAuthorities()) {
			if(authority.relocationAction() == null)
				continue;
			boolean actionEmitted = emitted.contains(authority.relocationAction().key());
			if((authority.kind() == MinStExactPhysicalModel.InputAuthorityKind.RELOCATION)
				!= actionEmitted)
				return false;
			var matching = authority.relocationAction().obligations().stream()
				.filter(obligation -> obligation.consumer() == decision
					&& obligation.inputPosition() == authority.inputPosition()
					&& obligation.requiredPlacement().equals(alternative.state()))
				.toList();
			if(matching.size() != 1)
				return false;
			RelocationDemandKey demand = RelocationDemandKey.from(matching.get(0));
			RelocationActionKey previous = actualInputs.put(demand,
				authority.relocationAction().key());
			if(previous != null && !previous.equals(authority.relocationAction().key()))
				return false;
		}
		Map<RelocationDemandKey,RelocationActionKey> expectedInputs = new LinkedHashMap<>();
		for(RelocationChoiceReceipt choice : choices.values())
			if(choice.demand().consumer() == decision
				&& choice.demand().requiredPlacement().equals(alternative.state()))
				expectedInputs.put(choice.demand(), choice.action());
		return actualInputs.equals(expectedInputs);
	}

	private static PlacementEmissionState requireEmission(NormalizedPlannerResult baseline,
		CompiledHopKey decision) {
		PlacementEmissionState state = baseline.selectedEmissionStates().get(decision);
		if(state == null)
			throw new IllegalArgumentException("MINST_BASELINE_DECISION_MISSING|decision="
				+ decision.normalizedSignature());
		return state;
	}

	private static void assertSamePhysicalAuthority(NormalizedPlannerResult expected,
		NormalizedPlannerResult actual) {
		if(!expected.selectedEmissionStates().equals(actual.selectedEmissionStates())
			|| !expected.selectedCandidateSelections().equals(actual.selectedCandidateSelections())
			|| !expected.selectedRelocationChoices().equals(actual.selectedRelocationChoices())
			|| !expected.selectedRelocations().equals(actual.selectedRelocations()))
			throw new IllegalArgumentException("MINST_BASELINE_ROUND_TRIP_MISMATCH"
				+ "|expectedStates=" + expected.selectedEmissionStates()
				+ "|actualStates=" + actual.selectedEmissionStates()
				+ "|expectedCandidates=" + expected.selectedCandidateSelections()
				+ "|actualCandidates=" + actual.selectedCandidateSelections()
				+ "|expectedChoices=" + expected.selectedRelocationChoices()
				+ "|actualChoices=" + actual.selectedRelocationChoices());
	}
}
