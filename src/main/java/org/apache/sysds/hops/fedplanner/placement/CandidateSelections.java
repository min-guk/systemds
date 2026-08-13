/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;

/** Exact selection and validation of candidate-rule rows retained by normalized plans. */
public final class CandidateSelections {
	private CandidateSelections() { }

	/**
	 * Admissible partial-assignment gate for exact placement search. It rejects only
	 * when a selected candidate-emitting consumer has no row whose physical PRESENT
	 * inputs can still become direct or use a graph-owned relocation action.
	 */
	public static boolean canStillBeReachable(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		return canStillBeReachable(analysis, analysis.graph(), actionUniverse, partialAssignment);
	}

	/**
	 * Variant whose graph is the exact policy projection that owns the supplied
	 * action universe.  Heuristic projections must not consult the unfiltered base
	 * graph for derived-output authority.
	 */
	public static boolean canStillBeReachable(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		return unreachableConsumers(analysis, authorityGraph, actionUniverse, partialAssignment).isEmpty();
	}

	/** Deterministic fail-closed diagnostics for exact candidate-reachability pruning. */
	public static List<String> unreachableConsumers(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(partialAssignment, "partialAssignment");
		List<String> unreachable = new ArrayList<>();
		for(NeutralPlacementGraph.Node consumer : authorityGraph.decisionNodes()) {
			PlacementState selectedConsumer = partialAssignment.get(consumer.key());
			if(selectedConsumer == null)
				continue;
			List<CandidateRuleFact> active = analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == consumer.key())
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.filter(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
					emission.emissionState().placementState().equals(selectedConsumer)))
				.toList();
			if(active.isEmpty())
				continue;
			boolean reachable = active.stream().anyMatch(fact -> candidateRowCanStillBeReachable(
				analysis, authorityGraph, actionUniverse, partialAssignment, selectedConsumer, fact));
			if(!reachable)
				unreachable.add(consumer.key().normalizedSignature() + '='
					+ selectedConsumer.normalizedSignature() + "|activeRows=" + active.stream()
						.map(fact -> fact.key().normalizedSignature() + candidateReachabilityDiagnostic(
							analysis, authorityGraph, actionUniverse, partialAssignment,
							selectedConsumer, fact)).sorted().toList());
		}
		return List.copyOf(unreachable);
	}

	private static String candidateReachabilityDiagnostic(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actions,
		Map<CompiledHopKey,PlacementState> partial, PlacementState selectedConsumer,
		CandidateRuleFact fact) {
		List<String> inputs = new ArrayList<>();
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			CandidateInputState input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == fact.key().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			if(edges.size() != 1) {
				inputs.add(position + ":edges=" + edges.size());
				continue;
			}
			CompiledHopKey producer = edges.get(0).producer();
			NeutralPlacementGraph.Node source = authorityGraph.node(producer).orElse(null);
			List<String> receipts = actions.stream().filter(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().targetPlacement().equals(selectedConsumer)
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == fact.key().parentOccurrence()
							&& obligation.inputPosition() == inputPosition))
				.map(action -> action.key().normalizedSignature() + "|direct="
					+ action.directSourcePlacements().stream()
						.map(PlacementState::normalizedSignature).toList()).toList();
			inputs.add(position + ":producer=" + producer.normalizedSignature()
				+ "|selected=" + (partial.get(producer) == null ? "-"
					: partial.get(producer).normalizedSignature())
				+ "|legal=" + (source == null ? List.of() : source.legalAlternatives().stream()
					.map(PlacementState::normalizedSignature).toList())
				+ "|receipts=" + receipts);
		}
		return "|reachabilityInputs=" + inputs;
	}

	private static boolean candidateRowCanStillBeReachable(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actions,
		Map<CompiledHopKey,PlacementState> partial,
		PlacementState selectedConsumer, CandidateRuleFact fact) {
		boolean exactEmissionReachable = fact.allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState().equals(selectedConsumer))
			.map(emission -> new CandidateSelectionReceipt(fact.key(), emission, List.of()))
			.anyMatch(receipt -> foutMaterializationActionReachable(
				authorityGraph, fact, receipt, partial, true));
		if(!exactEmissionReachable)
			return false;
		// A DML FunctionOp is only a coordinator-side forwarding placeholder. Its
		// actual/formal placement contract is validated by the explicit function
		// boundary facts; requiring a physical receipt action here would invent a
		// caller-side matrix consumption that the runtime never performs.
		if(analysis.isDmlFunctionCallBoundary(fact.key().parentOccurrence()))
			return true;
		// Receipt/worker-pool coherence is a precondition for executing the
		// consumer on federated workers. A CP consumer does not consume a matrix
		// FederationMap directly; its FED-to-local boundary is modeled elsewhere.
		if(selectedConsumer.execType() != org.apache.sysds.common.Types.ExecType.FED)
			return true;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			CandidateInputState input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == fact.key().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			// Logical function/transient links are governed by their explicit compiler
			// forwarding constraints rather than a compiled physical edge.
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				return false;
			boolean receipted = actions.stream().anyMatch(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().targetPlacement().equals(selectedConsumer)
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == fact.key().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			boolean direct = singlePhysicalInputDirectReachable(
				analysis, fact.key(), inputPosition, input.fType(), partial, true);
			if(!receipted && !direct && !singleParametricFormalReceiptReachable(
				analysis, fact.key(), inputPosition, input.fType(), partial, true))
				return false;
		}
		return true;
	}

	public record Selection(List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> relocationChoices,
		List<RelocationActionKey> emittedActions, int materializedInputCount,
		int localMaterializationActionCount, int foutMaterializationActionCount) {
		public Selection {
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			relocationChoices = List.copyOf(Objects.requireNonNull(relocationChoices, "relocationChoices"));
			emittedActions = List.copyOf(Objects.requireNonNull(emittedActions, "emittedActions"));
			if(materializedInputCount < 0)
				throw new IllegalArgumentException("Materialized input count must be non-negative");
			if(localMaterializationActionCount < 0)
				throw new IllegalArgumentException(
					"Local materialization action count must be non-negative");
			if(foutMaterializationActionCount < 0)
				throw new IllegalArgumentException(
					"FOUT materialization action count must be non-negative");
		}
	}

	/** Returns all exact, source-reachable candidate rows for the selected placement assignment. */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return feasibleVariants(analysis, analysis.graph(), actionUniverse, assignment);
	}

	/** Exact candidate rows under a policy-projected graph authority. */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return feasibleVariants(analysis, authorityGraph, actionUniverse, assignment, false);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		boolean allowUnassignedDerivedFoutOwner) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> activeConsumers = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> activeRows = new IdentityHashMap<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			PlacementState selected = assignment.get(fact.key().parentOccurrence());
			if(selected == null || fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			if(fact.allowedEmissionFacts().stream()
				.anyMatch(emission -> emission.emissionState().placementState().equals(selected)))
				activeConsumers.put(fact.key().parentOccurrence(), Boolean.TRUE);
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				if(!emission.emissionState().placementState().equals(selected))
					continue;
				CandidateSelectionReceipt base = new CandidateSelectionReceipt(
					fact.key(), emission, List.of());
				activeRows.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(base);
				if(foutMaterializationActionReachable(authorityGraph, fact, base, assignment,
					allowUnassignedDerivedFoutOwner)
					&& receiptReachable(analysis, actionUniverse, assignment, base))
					result.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(base);
			}
		}
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> ordered = new LinkedHashMap<>();
		authorityGraph.decisionNodes().stream().map(NeutralPlacementGraph.Node::key).forEach(key -> {
			List<CandidateSelectionReceipt> variants = result.getOrDefault(key, List.of()).stream()
				.distinct().sorted().toList();
			if(activeConsumers.containsKey(key) && variants.isEmpty())
				throw new IllegalStateException("Active exact candidate has no source-reachable row: "
					+ key.normalizedSignature() + " rows=" + activeRows.getOrDefault(key, List.of()).stream()
						.map(row -> row.normalizedSignature() + " => "
							+ reachabilityDetails(analysis, actionUniverse, assignment, row)).toList());
			if(!variants.isEmpty())
				ordered.put(key, variants);
		});
		return Collections.unmodifiableMap(ordered);
	}

	/** FedAll/Heuristic candidate policy: maximize explicit federated inputs, then share relocations. */
	public static Selection selectMaterializationMaximal(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return selectMaterializationMaximal(analysis, analysis.graph(), actionUniverse, assignment);
	}

	/** FedAll/Heuristic candidate policy under an exact projected graph. */
	public static Selection selectMaterializationMaximal(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
			materializationMaximalVariants(analysis, authorityGraph, actionUniverse, assignment,
				feasibleVariants(analysis, authorityGraph, actionUniverse, assignment));
		List<CompiledHopKey> consumers = new ArrayList<>(byConsumer.keySet());
		Collections.sort(consumers);
		Search search = new Search(analysis, authorityGraph, List.copyOf(actionUniverse), assignment,
			consumers, byConsumer, true);
		search.solve(0, 0);
		return search.requireBest();
	}

	/**
	 * Candidate-row preference is consumer-separable. A FED execution retains the
	 * legacy FedAll policy of maximizing explicit federated materializations; a CP
	 * execution minimizes them because uploading a LOUT input only to execute the
	 * consumer locally contradicts that selected placement. Restricting the exact
	 * secondary relocation search to each consumer's directional optimum is an
	 * exact lexicographic reduction, not a runtime-capability gate.
	 */
	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> materializationMaximalVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> maximal = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : feasible.entrySet()) {
			PlacementState consumerState = assignment.get(entry.getKey());
			if(consumerState == null)
				throw new IllegalStateException("Candidate consumer has no selected placement");
			boolean maximize = consumerState.execType()
				== org.apache.sysds.common.Types.ExecType.FED;
			java.util.stream.IntStream materializations = entry.getValue().stream()
				.mapToInt(CandidateSelections::presentInputCount);
			int optimum = maximize ? materializations.max().orElseThrow()
				: materializations.min().orElseThrow();
			Map<String,CandidateSelectionReceipt> byPhysicalEffect = new LinkedHashMap<>();
			entry.getValue().stream().filter(receipt -> presentInputCount(receipt) == optimum)
				.sorted().forEach(receipt -> byPhysicalEffect.putIfAbsent(candidateEffectSignature(
					analysis, authorityGraph, actionUniverse, assignment, receipt), receipt));
			List<CandidateSelectionReceipt> effects = List.copyOf(byPhysicalEffect.values());
			if(maximize && effects.size() > 1) {
				List<CandidateSelectionReceipt> anchorAligned = effects.stream()
					.filter(receipt -> allPresentRelocationsAnchorAligned(
						analysis, actionUniverse, assignment, receipt)).toList();
				if(!anchorAligned.isEmpty())
					effects = anchorAligned;
			}
			maximal.put(entry.getKey(), effects);
		}
		return Collections.unmodifiableMap(maximal);
	}

	/**
	 * FedAll/Heuristic tie-break for equal-materialization candidate rows. If every
	 * PRESENT matrix input has an exact relocation whose upload layout equals the
	 * durable anchor layout, that row cannot replicate or reshape more data than a
	 * competing cross-layout row reaching the same selected consumer placement.
	 * Cost-based planners never use this projection and retain every exact row.
	 */
	private static boolean allPresentRelocationsAnchorAligned(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt receipt) {
		if(receipt.emission().emissionState().placementState().execType() != ExecType.FED)
			return true;
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == receipt.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				return false;
			CompiledHopKey producer = edges.get(0).producer();
			PlacementState source = assignment.get(producer);
			if(source != null && source.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& source.fType() == input.fType())
				continue;
			boolean aligned = actions.stream().anyMatch(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().materializationFType() == action.key().durableAnchor().fType()
					&& action.key().targetPlacement().equals(
						receipt.emission().emissionState().placementState())
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == receipt.rule().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			if(!aligned)
				return false;
		}
		return true;
	}

	/**
	 * Rows can be collapsed only when their exact emission, ordered PRESENT/ABSENT
	 * pattern, and explicit relocation alternatives are all identical. Including
	 * the emission and input pattern preserves every possible local-download effect,
	 * including whether this row publishes a native or derived FOUT producer.
	 */
	private static String candidateEffectSignature(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment, CandidateSelectionReceipt receipt) {
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		selected.put(receipt.rule().parentOccurrence(), receipt);
		List<String> options = new ArrayList<>();
		for(RelocationAction action : actionUniverse) {
			boolean requiresEmission = authorityGraph.isRelocationActive(
				action, assignment, selected.values());
			for(PlacementIdentity.ObligationKey obligation : action.obligations()) {
				if(!obligation.requiredPlacement().equals(assignment.get(obligation.consumer()))
					|| !actionMatchesSelectedCandidate(action, obligation, selected))
					continue;
				options.add(RelocationDemandKey.from(obligation).normalizedSignature() + "=>"
					+ action.key().normalizedSignature() + "=>" + requiresEmission);
			}
		}
		Collections.sort(options);
		return receipt.emission().normalizedSignature() + "|inputs="
			+ receipt.rule().orderedInputs().stream()
				.map(CandidateInputState::normalizedSignature).toList()
			+ "|relocations=" + String.join("|", options);
	}

	/** Canonical native-first completion used only when a planner supplies no explicit row receipt. */
	public static Selection selectNativeCanonical(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
			feasibleVariants(analysis, actionUniverse, assignment);
		List<CandidateSelectionReceipt> selected = new ArrayList<>();
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : byConsumer.entrySet())
			selected.add(entry.getValue().stream().min((left, right) -> {
				int materialization = Integer.compare(presentInputCount(left), presentInputCount(right));
				return materialization != 0 ? materialization : left.compareTo(right);
			}).orElseThrow());
		selected = selected.stream().sorted().toList();
		List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
			analysis, actionUniverse, assignment, selected, (demand, action) -> true);
		List<RelocationActionKey> emitted = RelocationSelections.emittedActions(
			analysis, actionUniverse, assignment, selected, choices);
		int materialized = selected.stream().mapToInt(CandidateSelections::presentInputCount).sum();
		int localMaterializations = LocalMaterializationSelections.physicalEmissionCount(
			analysis, assignment, selected);
		return new Selection(selected, choices, emitted, materialized, localMaterializations,
			foutMaterializationPhysicalEmissionCount(selected));
	}

	public static List<CandidateSelectionReceipt> resolveAndValidate(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse, Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		return resolveAndValidate(analysis, analysis.graph(), actionUniverse, assignment, selections);
	}

	public static List<CandidateSelectionReceipt> resolveAndValidate(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible =
			feasibleVariants(analysis, authorityGraph, actionUniverse, assignment);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : Objects.requireNonNull(selections, "selections")) {
			Objects.requireNonNull(receipt, "candidate selection");
			CompiledHopKey consumer = receipt.rule().parentOccurrence();
			if(selected.put(consumer, receipt) != null)
				throw new IllegalArgumentException("Candidate consumer has multiple selected rows: "
					+ consumer.normalizedSignature());
			if(feasible.getOrDefault(consumer, List.of()).stream().noneMatch(candidate ->
				candidate.rule() == receipt.rule() && candidate.emission() == receipt.emission()))
				throw new IllegalArgumentException("Candidate selection is foreign, inactive, or unreachable: "
					+ receipt.normalizedSignature() + " feasible=" + feasible.getOrDefault(consumer, List.of())
						.stream().map(CandidateSelectionReceipt::normalizedSignature).toList()
					+ " reachability=" + reachabilityDetails(analysis, actionUniverse, assignment, receipt));
		}
		if(selected.size() != feasible.size() || !selected.keySet().containsAll(feasible.keySet()))
			throw new IllegalArgumentException("Candidate selections do not cover every active consumer: expected="
				+ feasible.size() + " selected=" + selected.size());
		return selected.values().stream().sorted().toList();
	}

	static List<CandidateSelectionReceipt> resolveAndValidatePartial(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse, Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		return resolveAndValidatePartial(analysis, analysis.graph(), actionUniverse, assignment, selections);
	}

	static List<CandidateSelectionReceipt> resolveAndValidatePartial(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		// A DP recurrence owns only its current parent/child closure. The authority graph has
		// already proven that a derived FOUT row names a legal durable owner, so defer that
		// owner's placement only while it is genuinely absent from this partial assignment.
		// Complete-plan validation remains strict through resolveAndValidate(...).
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible =
			feasibleVariants(analysis, authorityGraph, actionUniverse, assignment, true);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : Objects.requireNonNull(selections, "selections")) {
			Objects.requireNonNull(receipt, "candidate selection");
			CompiledHopKey consumer = receipt.rule().parentOccurrence();
			if(selected.put(consumer, receipt) != null)
				throw new IllegalArgumentException("Candidate consumer has multiple selected rows");
			if(feasible.getOrDefault(consumer, List.of()).stream().noneMatch(candidate ->
				candidate.rule() == receipt.rule() && candidate.emission() == receipt.emission()))
				throw new IllegalArgumentException("Candidate selection is foreign, inactive, or unreachable: "
					+ receipt.normalizedSignature() + " feasible=" + feasible.getOrDefault(consumer, List.of())
						.stream().map(CandidateSelectionReceipt::normalizedSignature).toList()
					+ " reachability=" + reachabilityDetails(analysis, actionUniverse, assignment, receipt));
		}
		return selected.values().stream().sorted().toList();
	}

	public static List<CandidateSelectionReceipt> resolveAndValidate(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		return resolveAndValidate(analysis, analysis.graph().relocationActions(), assignment, selections);
	}

	/** True only when the exact selected candidate row requires this action alternative. */
	static boolean actionMatchesSelectedCandidate(RelocationAction action,
		PlacementIdentity.ObligationKey obligation,
		Map<CompiledHopKey,CandidateSelectionReceipt> selections) {
		CandidateSelectionReceipt selected = selections.get(obligation.consumer());
		if(selected == null || !selected.emission().emissionState().placementState()
			.equals(obligation.requiredPlacement())
			|| obligation.inputPosition() >= selected.rule().orderedInputs().size())
			return false;
		CandidateInputState input = selected.rule().orderedInputs().get(obligation.inputPosition());
		return input.present() && input.fType() == action.key().materializationFType();
	}

	static Map<CompiledHopKey,CandidateSelectionReceipt> indexByConsumer(
		Collection<CandidateSelectionReceipt> selections) {
		Map<CompiledHopKey,CandidateSelectionReceipt> result = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : selections)
			if(result.put(receipt.rule().parentOccurrence(), receipt) != null)
				throw new IllegalArgumentException("Candidate consumer has multiple selected rows");
		return result;
	}

	private static boolean receiptReachable(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt receipt) {
		// See candidateRowCanStillBeReachable: function arguments are forwarded by
		// the compiler-owned actual/formal boundary, not consumed by this Hop.
		if(analysis.isDmlFunctionCallBoundary(receipt.rule().parentOccurrence()))
			return true;
		if(receipt.emission().emissionState().placementState().execType()
			!= org.apache.sysds.common.Types.ExecType.FED)
			return true;
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			final int inputPosition = position;
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			FType required = input.fType();
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder()
				.stream().filter(edge -> edge.consumer() == receipt.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			// Logical function/transient inputs are validated by their exact candidate facts and
			// compiler-owned forwarding constraints rather than physical relocation actions.
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				throw new IllegalStateException("Candidate physical input edge is ambiguous");
			boolean receipted = actions.stream().anyMatch(action ->
				action.key().materializationFType() == required
					&& action.key().targetPlacement().equals(receipt.emission().emissionState().placementState())
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == receipt.rule().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			boolean direct = singlePhysicalInputDirectReachable(
				analysis, receipt.rule(), inputPosition, required, assignment, false);
			if(!receipted && !direct && !singleParametricFormalReceiptReachable(
				analysis, receipt.rule(), inputPosition, required, assignment, false))
				return false;
		}
		return true;
	}

	static boolean derivedFoutActionReachable(NeutralPlacementGraph graph,
		CandidateSelectionReceipt receipt) {
		return foutMaterializationActionReachable(graph, null, receipt, null, true);
	}

	private static boolean foutMaterializationActionReachable(NeutralPlacementGraph graph,
		CandidateRuleFact exactRule, CandidateSelectionReceipt receipt,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassignedOwner) {
		PlacementState selected = receipt.emission().emissionState().placementState();
		boolean cpFout = selected.execType() == org.apache.sysds.common.Types.ExecType.CP
			&& selected.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT;
		boolean requiresAction = receipt.emission().emissionState().derivedFedFout() || cpFout;
		if(!requiresAction)
			return receipt.emission().derivedFoutAction() == null;
		var expected = receipt.emission().derivedFoutAction();
		if(expected == null || expected.candidateRule() != receipt.rule()
			|| expected.producer() != receipt.rule().parentOccurrence()
			|| expected.targetPlacement() != selected)
			return false;
		if(exactRule != null && (exactRule.key() != receipt.rule()
			|| exactRule.allowedEmissionFacts().stream().noneMatch(source ->
				source.derivedFoutAction() == null
					&& source.emissionState().placementState() == expected.sourcePlacement())))
			return false;
		if(graph.derivedFoutMaterializationActions().stream()
			.filter(action -> action.key() == expected).count() != 1)
			return false;
		if(assignment == null)
			return true;
		PlacementState owner = assignment.get(expected.durableAnchorOwner());
		return owner == null ? allowUnassignedOwner : owner.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
			&& owner.fType() == expected.durableAnchorOwnerFType();
	}

	/**
	 * A compiled consumer inside a DML function may receive its only PRESENT matrix
	 * input from a formal read.  That FederationMap is runtime-parametric: the exact
	 * worker/range identity is supplied by each selected caller actual, so there is no
	 * single static relocation action to receipt.  This is legal only for one physical
	 * PRESENT input (there is no second pool with which it could disagree), and every
	 * formal/actual in the forwarding chain must already be FOUT with the required
	 * layout.  Multiple-input consumers still require an exact common-anchor action.
	 */
	private static boolean singleParametricFormalReceiptReachable(PlacementAnalysis analysis,
		CandidateRuleKey rule, int inputPosition, FType required,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		long presentPhysicalInputs = java.util.stream.IntStream.range(0,
			rule.orderedInputs().size()).filter(position -> {
				if(!rule.orderedInputs().get(position).present())
					return false;
				return analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge ->
					edge.consumer() == rule.parentOccurrence()
						&& edge.inputPosition() == position);
			}).count();
		if(presentPhysicalInputs != 1)
			return false;
		List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
			.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == rule.parentOccurrence()
				&& edge.inputPosition() == inputPosition).toList();
		if(edges.size() != 1)
			return false;
		return parametricFormalChainFoutCompatible(analysis, edges.get(0).producer(), required,
			assignment, allowUnassigned,
			Collections.newSetFromMap(new IdentityHashMap<CompiledHopKey,Boolean>()));
	}

	/**
	 * A unary FED consumer can execute directly on its sole physical FOUT input; no upload/refed
	 * action exists or is needed in that case. Multi-input rows deliberately remain action-backed
	 * because matching FType alone does not prove that independent FederationMaps share a pool.
	 */
	private static boolean singlePhysicalInputDirectReachable(PlacementAnalysis analysis,
		CandidateRuleKey rule, int inputPosition, FType required,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		if(rule.orderedInputs().stream().filter(CandidateInputState::present).count() != 1)
			return false;
		List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
			.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == rule.parentOccurrence()
				&& edge.inputPosition() == inputPosition).toList();
		if(edges.size() != 1)
			return false;
		CompiledHopKey producer = edges.get(0).producer();
		PlacementState selected = assignment.get(producer);
		if(selected != null)
			return selected.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& selected.fType() == required;
		if(!allowUnassigned)
			return false;
		return analysis.graph().node(producer).orElseThrow().legalAlternatives().stream().anyMatch(state ->
			state.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& state.fType() == required);
	}

	private static boolean parametricFormalChainFoutCompatible(PlacementAnalysis analysis,
		CompiledHopKey formal, FType required, Map<CompiledHopKey,PlacementState> assignment,
		boolean allowUnassigned, Set<CompiledHopKey> visiting) {
		List<PlacementAnalysis.LogicalFunctionInputFact> incoming = analysis
			.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(input -> input.targetRead() == formal).toList();
		if(incoming.isEmpty())
			return false;
		PlacementState formalState = assignment.get(formal);
		if(!foutCompatible(formalState, required, allowUnassigned))
			return false;
		if(!visiting.add(formal))
			return true;
		try {
			for(PlacementAnalysis.LogicalFunctionInputFact input : incoming) {
				PlacementState actualState = assignment.get(input.sourceArgument());
				if(!foutCompatible(actualState, required, allowUnassigned))
					return false;
				boolean nestedFormal = analysis.logicalFunctionInputsInCanonicalOrder().stream()
					.anyMatch(nested -> nested.targetRead() == input.sourceArgument());
				if(nestedFormal && !parametricFormalChainFoutCompatible(analysis,
					input.sourceArgument(), required, assignment, allowUnassigned, visiting))
					return false;
			}
			return true;
		}
		finally {
			visiting.remove(formal);
		}
	}

	private static boolean foutCompatible(PlacementState state, FType required,
		boolean allowUnassigned) {
		return state == null ? allowUnassigned
			: state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& state.fType() == required;
	}

	private static List<String> reachabilityDetails(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt receipt) {
		List<String> details = new ArrayList<>();
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			FType required = input.fType();
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder()
				.stream().filter(edge -> edge.consumer() == receipt.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			List<String> actionTypes = actions.stream().filter(action -> action.obligations().stream()
				.anyMatch(obligation -> obligation.consumer() == receipt.rule().parentOccurrence()
					&& obligation.inputPosition() == inputPosition))
				.map(action -> action.key().materializationFType() + "->"
					+ action.key().targetPlacement().normalizedSignature()).sorted().toList();
			long sourceHopId = edges.size() == 1 ? analysis.hop(edges.get(0).producer())
				.map(org.apache.sysds.hops.Hop::getHopID).orElse(-1L) : -1L;
			List<String> sourceOccurrenceSelections = sourceHopId < 0 ? List.of()
				: analysis.occurrences().stream()
					.filter(occurrence -> occurrence.hop().getHopID() == sourceHopId)
					.map(occurrence -> {
						NeutralPlacementGraph.Node node = analysis.graph().node(occurrence.key()).orElseThrow();
						return occurrence.key().emittedHopInstance() + "=" + assignment.get(occurrence.key())
							+ "[kind=" + node.kind() + ",emitted=" + node.emittedWork() + ']';
					}).sorted().toList();
			details.add("input=" + position + ",required=" + required + ",edges=" + edges.size()
				+ ",consumerHop=" + analysis.hop(receipt.rule().parentOccurrence())
					.map(org.apache.sysds.hops.Hop::getHopID).orElse(-1L)
				+ ",sourceKey=" + (edges.size() == 1
					? edges.get(0).producer().normalizedSignature() : "-")
				+ ",sourceHop=" + sourceHopId
				+ ",source=" + (edges.size() == 1 ? assignment.get(edges.get(0).producer()) : "-")
				+ ",sourceOccurrences=" + sourceOccurrenceSelections
				+ ",actions=" + actionTypes);
		}
		return List.copyOf(details);
	}

	private static int presentInputCount(CandidateSelectionReceipt receipt) {
		return (int)receipt.rule().orderedInputs().stream().filter(CandidateInputState::present).count();
	}

	private static final class Search {
		private final PlacementAnalysis analysis;
		private final NeutralPlacementGraph authorityGraph;
		private final List<RelocationAction> actions;
		private final Map<CompiledHopKey,PlacementState> assignment;
		private final List<CompiledHopKey> consumers;
		private final Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants;
		private final boolean maximizeMaterialization;
		private final List<CandidateSelectionReceipt> current = new ArrayList<>();
		private final int[] suffixMaximumMaterializations;
		private Selection best;
		private String bestSignature;

		private Search(PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
			List<RelocationAction> actions,
			Map<CompiledHopKey,PlacementState> assignment, List<CompiledHopKey> consumers,
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants,
			boolean maximizeMaterialization) {
			this.analysis = analysis;
			this.authorityGraph = authorityGraph;
			this.actions = actions;
			this.assignment = assignment;
			this.consumers = consumers;
			this.variants = variants;
			this.maximizeMaterialization = maximizeMaterialization;
			this.suffixMaximumMaterializations = new int[consumers.size() + 1];
			for(int index = consumers.size() - 1; index >= 0; index--) {
				int maximum = variants.get(consumers.get(index)).stream()
					.mapToInt(CandidateSelections::presentInputCount).max().orElseThrow();
				suffixMaximumMaterializations[index] = Math.addExact(
					suffixMaximumMaterializations[index + 1], maximum);
			}
		}

		private void solve(int index, int materialized) {
			// The primary candidate-row objective is the total number of explicit
			// federated inputs. Once an incumbent reaches the exact suffix upper bound,
			// this prefix cannot improve that primary score. For an equal primary score,
			// every remaining row still needs full relocation/local-materialization
			// evaluation; therefore prune only strictly smaller bounds.
			if(maximizeMaterialization && best != null
				&& Math.addExact(materialized, suffixMaximumMaterializations[index])
					< best.materializedInputCount())
				return;
			if(index == consumers.size()) {
				List<CandidateSelectionReceipt> selected = current.stream().sorted().toList();
				RelocationSelections.Selection relocationSelection;
				try {
					relocationSelection = RelocationSelections.selectCanonicalPrevalidated(
						analysis, authorityGraph, actions, assignment, selected,
						(demand, action) -> true);
				}
				catch(IllegalArgumentException | IllegalStateException unavailable) {
					return;
				}
				List<RelocationChoiceReceipt> choices = relocationSelection.choices();
				List<RelocationActionKey> emitted = relocationSelection.emittedActions();
				String signature = selected.stream().map(CandidateSelectionReceipt::normalizedSignature)
					.reduce((left, right) -> left + '|' + right).orElse("") + "#"
					+ choices.stream().map(RelocationChoiceReceipt::normalizedSignature)
						.reduce((left, right) -> left + '|' + right).orElse("");
				int localMaterializations = LocalMaterializationSelections.physicalEmissionCount(
					analysis, assignment, selected);
				Selection candidate = new Selection(selected, choices, emitted, materialized,
					localMaterializations, foutMaterializationPhysicalEmissionCount(selected));
				if(better(candidate, signature)) {
					best = candidate;
					bestSignature = signature;
				}
				return;
			}
			for(CandidateSelectionReceipt receipt : variants.get(consumers.get(index))) {
				current.add(receipt);
				solve(index + 1, materialized + presentInputCount(receipt));
				current.remove(current.size() - 1);
			}
		}

		private boolean better(Selection candidate, String signature) {
			if(best == null)
				return true;
			if(maximizeMaterialization) {
				int materialization = Integer.compare(candidate.materializedInputCount(),
					best.materializedInputCount());
				if(materialization != 0)
					return materialization > 0;
			}
			int emitted = Integer.compare(totalPhysicalEmissions(candidate),
				totalPhysicalEmissions(best));
			if(emitted != 0)
				return emitted < 0;
			return signature.compareTo(bestSignature) < 0;
		}

		private static int totalPhysicalEmissions(Selection selection) {
			return Math.addExact(Math.addExact(RelocationSelections.physicalEmissionCount(
				selection.emittedActions()), selection.localMaterializationActionCount()),
				selection.foutMaterializationActionCount());
		}

		private Selection requireBest() {
			if(best == null)
				throw new IllegalStateException("Selected placement assignment has no exact candidate-row plan");
			return best;
		}
	}

	/** Number of exact planner-created FOUT uploads selected by candidate receipts. */
	public static int foutMaterializationPhysicalEmissionCount(
		Collection<CandidateSelectionReceipt> selectedCandidates) {
		return (int) Objects.requireNonNull(selectedCandidates, "selectedCandidates").stream()
			.map(candidate -> candidate.emission().derivedFoutAction())
			.filter(Objects::nonNull).distinct().count();
	}

	/** Number of exact FED/LOUT-to-FOUT uploads selected by candidate receipts. */
	public static int derivedFoutPhysicalEmissionCount(
		Collection<CandidateSelectionReceipt> selectedCandidates) {
		return (int) Objects.requireNonNull(selectedCandidates, "selectedCandidates").stream()
			.filter(candidate -> candidate.emission().emissionState().derivedFedFout())
			.map(candidate -> candidate.emission().derivedFoutAction())
			.filter(Objects::nonNull).distinct().count();
	}

	/** Number of exact CP/LOUT-to-CP/FOUT uploads selected by candidate receipts. */
	public static int cpFoutPhysicalEmissionCount(
		Collection<CandidateSelectionReceipt> selectedCandidates) {
		return foutMaterializationPhysicalEmissionCount(selectedCandidates)
			- derivedFoutPhysicalEmissionCount(selectedCandidates);
	}
}
