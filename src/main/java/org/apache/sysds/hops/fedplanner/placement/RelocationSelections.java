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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact selection and validation of input-materialization alternatives. */
public final class RelocationSelections {
	private RelocationSelections() { }

	/**
	 * Stable identity of one physical relocation emission. Consumer-specific REFED
	 * receipts that publish the same source into the same durable placement share one
	 * upload, while every other relocation keeps its full action identity.
	 */
	public static String physicalEmissionIdentity(RelocationActionKey action) {
		Objects.requireNonNull(action, "action");
		if(action.targetPlacement().execType() == ExecType.FED
			&& action.targetPlacement().output() == FederatedOutput.FOUT)
			return "REFED|" + action.sourceValueVersion().normalizedSignature()
				+ '|' + action.materializationFType().name()
				+ '|' + action.durableAnchor().normalizedSignature()
				+ '|' + action.statementBlockScope();
		return "ACTION|" + action.normalizedSignature();
	}

	/** Number of distinct physical emissions represented by exact action receipts. */
	public static int physicalEmissionCount(Collection<RelocationActionKey> actions) {
		return (int) Objects.requireNonNull(actions, "actions").stream()
			.map(RelocationSelections::physicalEmissionIdentity).distinct().count();
	}

	public record ResolvedChoice(RelocationChoiceReceipt receipt, RelocationAction action,
		ObligationKey obligation, boolean requiresEmission) { }

	/** Costed exact choice projection used by planners whose objective includes relocation. */
	public record Selection(List<RelocationChoiceReceipt> choices,
		List<RelocationActionKey> emittedActions, double cost) {
		public Selection {
			choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
			emittedActions = List.copyOf(Objects.requireNonNull(emittedActions, "emittedActions"));
			if(!Double.isFinite(cost) || cost < 0.0)
				throw new IllegalArgumentException("Relocation selection cost must be finite and non-negative");
		}
	}

	/**
	 * Selects exactly one legal alternative for every active exact demand. The supplied
	 * cost is charged once per distinct emitted action; direct compatible sources cost
	 * zero because no physical relocation is emitted.
	 */
	public static Selection selectMinimumCost(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		ToDoubleFunction<RelocationActionKey> emittedActionCost) {
		Objects.requireNonNull(emittedActionCost, "emittedActionCost");
		Problem problem = problem(graph, actionUniverse, assignment);
		WeightedSearch search = new WeightedSearch(problem.demands(), emittedActionCost);
		search.solve(0, 0.0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice cost search has no solution");
		return new Selection(search.best, search.bestEmitted, search.bestCost);
	}

	public static Selection selectMinimumCost(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		ToDoubleFunction<RelocationActionKey> emittedActionCost) {
		Objects.requireNonNull(emittedActionCost, "emittedActionCost");
		Problem problem = problem(analysis, actionUniverse, assignment, candidateSelections);
		WeightedSearch search = new WeightedSearch(problem.demands(), emittedActionCost);
		search.solve(0, 0.0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice cost search has no solution");
		return new Selection(search.best, search.bestEmitted, search.bestCost);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment) {
		return selectCanonical(graph, graph.relocationActions(), assignment,
			(demand, action) -> true);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		Objects.requireNonNull(allowed, "allowed");
		Problem problem = problem(analysis, actionUniverse, assignment, candidateSelections);
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> allowed.test(demand.demand(), option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalStateException("No selected materialization alternative for exact demand: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		Search search = new Search(filtered, null);
		search.solve(0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice search has no solution");
		return search.best;
	}

	/**
	 * Internal exact completion for rows already selected from
	 * {@link CandidateSelections#feasibleVariants}. This avoids reconstructing and
	 * revalidating the complete feasible-row universe at every leaf of the exact
	 * candidate search. The relocation problem and tie-breaking are identical to
	 * {@link #selectCanonical(PlacementAnalysis, Collection, Map, Collection, BiPredicate)}.
	 */
	static Selection selectCanonicalPrevalidated(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		return selectCanonicalPrevalidated(analysis, analysis.graph(), actionUniverse, assignment,
			candidateSelections, allowed);
	}

	static Selection selectCanonicalPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		Objects.requireNonNull(allowed, "allowed");
		Problem problem = problemPrevalidated(analysis, authorityGraph, actionUniverse, assignment,
			candidateSelections);
		List<DemandOptions> filtered = filtered(problem, allowed);
		Search search = new Search(filtered, null);
		search.solve(0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice search has no solution");
		return new Selection(search.best, search.bestEmitted, search.bestEmissionCount);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		return selectCanonical(graph, graph.relocationActions(), assignment, allowed);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		Objects.requireNonNull(allowed, "allowed");
		Problem problem = problem(graph, actionUniverse, assignment);
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> allowed.test(demand.demand(), option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalStateException("No selected materialization alternative for exact demand: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		Search search = new Search(filtered, null);
		search.solve(0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice search has no solution");
		return search.best;
	}

	/**
	 * Completes a legacy emitted-action projection with the unique canonical direct/non-emitted
	 * choices. This deliberately rejects an emitted action set that cannot explain every demand.
	 */
	public static List<RelocationChoiceReceipt> completeFromSelectedRelocations(
		NeutralPlacementGraph graph, Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationActionKey> emittedActions) {
		Set<RelocationActionKey> expected = new LinkedHashSet<>(
			Objects.requireNonNull(emittedActions, "emittedActions"));
		Problem problem = problem(graph, graph.relocationActions(), assignment);
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> !option.requiresEmission() || expected.contains(option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalArgumentException("Selected relocation set leaves an exact demand unresolved: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		Search search = new Search(filtered, expected);
		search.solve(0);
		if(search.best == null)
			throw new IllegalArgumentException("Selected relocation set has no exact demand assignment");
		return search.best;
	}

	public static List<ResolvedChoice> resolveAndValidate(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		return resolveAndValidate(graph, graph.relocationActions(), assignment, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		return resolveAndValidate(analysis, analysis.graph(), actionUniverse, assignment,
			candidateSelections, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		Problem problem = problem(analysis, authorityGraph, actionUniverse, assignment, candidateSelections);
		return resolveAndValidate(problem, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		return resolveAndValidate(analysis, analysis.graph().relocationActions(), assignment,
			candidateSelections, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		Problem problem = problem(graph, actionUniverse, assignment);
		return resolveAndValidate(problem, choices);
	}

	private static List<ResolvedChoice> resolveAndValidate(Problem problem,
		Collection<RelocationChoiceReceipt> choices) {
		Map<RelocationDemandKey,DemandOptions> demands = new LinkedHashMap<>();
		for(DemandOptions demand : problem.demands())
			demands.put(demand.demand(), demand);
		Map<RelocationDemandKey,RelocationChoiceReceipt> selected = new LinkedHashMap<>();
		for(RelocationChoiceReceipt choice : Objects.requireNonNull(choices, "choices")) {
			DemandOptions demand = demands.get(Objects.requireNonNull(choice, "choice").demand());
			if(demand == null)
				throw new IllegalArgumentException("Relocation choice is inactive or foreign: "
					+ choice.normalizedSignature());
			if(selected.putIfAbsent(choice.demand(), choice) != null)
				throw new IllegalArgumentException("Relocation demand has multiple selected alternatives: "
					+ choice.demand().normalizedSignature());
			if(demand.options().stream().noneMatch(option -> option.action().key().equals(choice.action())))
				throw new IllegalArgumentException("Relocation choice is not an exact graph-owned alternative: "
					+ choice.normalizedSignature());
		}
		if(selected.size() != demands.size())
			throw new IllegalArgumentException("Relocation choices do not cover every active exact demand");
		List<ResolvedChoice> resolved = new ArrayList<>();
		Map<CompiledHopKey,DurableAnchorKey> anchorsByConsumer = new LinkedHashMap<>();
		for(DemandOptions demand : problem.demands()) {
			RelocationChoiceReceipt choice = selected.get(demand.demand());
			Option option = demand.options().stream()
				.filter(candidate -> candidate.action().key().equals(choice.action()))
				.findFirst().orElseThrow();
			DurableAnchorKey prior = anchorsByConsumer.putIfAbsent(
				option.obligation().consumer(), option.action().key().durableAnchor());
			if(prior != null && !prior.equals(option.action().key().durableAnchor()))
				throw new IllegalArgumentException(
					"One exact consumer cannot mix input receipts from different durable anchors: consumer="
						+ option.obligation().consumer().normalizedSignature() + " first="
						+ prior.normalizedSignature() + " current="
						+ option.action().key().durableAnchor().normalizedSignature());
			resolved.add(new ResolvedChoice(choice, option.action(), option.obligation(),
				option.requiresEmission()));
		}
		return List.copyOf(resolved);
	}

	public static List<RelocationActionKey> emittedActions(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		return emittedActions(graph, graph.relocationActions(), assignment, choices);
	}

	public static List<RelocationActionKey> emittedActions(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		for(ResolvedChoice choice : resolveAndValidate(analysis, actionUniverse, assignment,
			candidateSelections, choices))
			if(choice.requiresEmission())
				emitted.add(choice.action().key());
		List<RelocationActionKey> ordered = new ArrayList<>(emitted);
		Collections.sort(ordered);
		return List.copyOf(ordered);
	}

	public static List<RelocationActionKey> emittedActions(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		return emittedActions(analysis, analysis.graph().relocationActions(), assignment,
			candidateSelections, choices);
	}

	public static List<RelocationActionKey> emittedActions(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		for(ResolvedChoice choice : resolveAndValidate(graph, actionUniverse, assignment, choices))
			if(choice.requiresEmission())
				emitted.add(choice.action().key());
		List<RelocationActionKey> ordered = new ArrayList<>(emitted);
		Collections.sort(ordered);
		return List.copyOf(ordered);
	}

	private static Problem problem(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment) {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		Map<RelocationDemandKey,List<Option>> options = new LinkedHashMap<>();
		for(RelocationAction action : actionUniverse) {
			boolean requiresEmission = graph.isRelocationActive(action, assignment);
			for(ObligationKey obligation : action.obligations()) {
				if(!obligation.requiredPlacement().equals(assignment.get(obligation.consumer())))
					continue;
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				options.computeIfAbsent(demand, ignored -> new ArrayList<>())
					.add(new Option(action, obligation, requiresEmission));
			}
		}
		List<DemandOptions> demands = new ArrayList<>();
		for(Map.Entry<RelocationDemandKey,List<Option>> entry : options.entrySet()) {
			List<Option> sorted = entry.getValue().stream()
				.sorted(Comparator.comparing(option -> option.action().key())).toList();
			if(sorted.stream().map(option -> option.action().key()).distinct().count() != sorted.size())
				throw new IllegalStateException("Graph contains duplicate alternatives for exact demand: "
					+ entry.getKey().normalizedSignature());
			demands.add(new DemandOptions(entry.getKey(), sorted));
		}
		Collections.sort(demands);
		return new Problem(List.copyOf(demands));
	}

	private static Problem problem(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		return problem(analysis, analysis.graph(), actionUniverse, assignment, candidateSelections);
	}

	private static Problem problem(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		List<CandidateSelectionReceipt> validated = CandidateSelections.resolveAndValidatePartial(
			analysis, authorityGraph, actionUniverse, assignment, candidateSelections);
		return problemPrevalidated(analysis, authorityGraph, actionUniverse, assignment, validated);
	}

	private static Problem problemPrevalidated(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		return problemPrevalidated(analysis, analysis.graph(), actionUniverse, assignment,
			candidateSelections);
	}

	private static Problem problemPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		// A graph-only analysis (the exact selector seam and its independent
		// exhaustive fixtures) has no runtime candidate-row universe.  In that
		// representation the graph-owned obligations are already the complete
		// authority and must not disappear merely because there are no candidate
		// receipts to filter them through.  Real compiler analyses carry candidate
		// facts and continue through the stricter row-aware path below.
		if(analysis.candidateRuleFacts().orderedFacts().isEmpty())
			return problem(authorityGraph, actionUniverse, assignment);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected =
			CandidateSelections.indexByConsumer(candidateSelections);
		Map<RelocationDemandKey,List<Option>> options = new LinkedHashMap<>();
		for(RelocationAction action : actionUniverse) {
			boolean requiresEmission = authorityGraph.isRelocationActive(
				action, assignment, candidateSelections);
			for(ObligationKey obligation : action.obligations()) {
				if(!obligation.requiredPlacement().equals(assignment.get(obligation.consumer()))
					|| !CandidateSelections.actionMatchesSelectedCandidate(action, obligation, selected))
					continue;
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				options.computeIfAbsent(demand, ignored -> new ArrayList<>())
					.add(new Option(action, obligation, requiresEmission));
			}
		}
		List<DemandOptions> demands = new ArrayList<>();
		for(Map.Entry<RelocationDemandKey,List<Option>> entry : options.entrySet()) {
			List<Option> sorted = entry.getValue().stream()
				.sorted(Comparator.comparing(option -> option.action().key())).toList();
			if(sorted.stream().map(option -> option.action().key()).distinct().count() != sorted.size())
				throw new IllegalStateException("Graph contains duplicate alternatives for exact candidate demand: "
					+ entry.getKey().normalizedSignature());
			demands.add(new DemandOptions(entry.getKey(), sorted));
		}
		Collections.sort(demands);
		return new Problem(List.copyOf(demands));
	}

	private static List<DemandOptions> filtered(Problem problem,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> allowed.test(demand.demand(), option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalStateException("No selected materialization alternative for exact demand: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		return List.copyOf(filtered);
	}

	private record Option(RelocationAction action, ObligationKey obligation,
		boolean requiresEmission) { }

	private record DemandOptions(RelocationDemandKey demand, List<Option> options)
		implements Comparable<DemandOptions> {
		private DemandOptions {
			options = List.copyOf(options);
		}
		@Override public int compareTo(DemandOptions that) {
			int cardinality = Integer.compare(options.size(), that.options.size());
			return cardinality != 0 ? cardinality : demand.compareTo(that.demand);
		}
	}

	private record Problem(List<DemandOptions> demands) { }

	private static final class Search {
		private final List<DemandOptions> demands;
		private final Set<RelocationActionKey> requiredEmitted;
		private final List<RelocationChoiceReceipt> current = new ArrayList<>();
		private final Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		private final Map<String,Integer> physicalEmissionRefs = new LinkedHashMap<>();
		private final AnchorBindings anchorBindings = new AnchorBindings();
		private List<RelocationChoiceReceipt> best;
		private List<RelocationActionKey> bestEmitted = List.of();
		private int bestEmissionCount = Integer.MAX_VALUE;
		private String bestSignature;

		private Search(List<DemandOptions> demands, Set<RelocationActionKey> requiredEmitted) {
			this.demands = List.copyOf(demands);
			this.requiredEmitted = requiredEmitted == null ? null : Set.copyOf(requiredEmitted);
		}

		private void solve(int index) {
			if(physicalEmissionRefs.size() > bestEmissionCount)
				return;
			if(index == demands.size()) {
				if(requiredEmitted != null && !emitted.equals(requiredEmitted))
					return;
				List<RelocationChoiceReceipt> candidate = current.stream().sorted().toList();
				String signature = candidate.stream()
					.map(RelocationChoiceReceipt::normalizedSignature)
					.reduce((left, right) -> left + "|" + right).orElse("");
				if(physicalEmissionRefs.size() < bestEmissionCount
					|| physicalEmissionRefs.size() == bestEmissionCount
					&& (bestSignature == null || signature.compareTo(bestSignature) < 0)) {
					bestEmissionCount = physicalEmissionRefs.size();
					bestSignature = signature;
					best = List.copyOf(candidate);
					bestEmitted = emitted.stream().sorted().toList();
				}
				return;
			}
			DemandOptions demand = demands.get(index);
			for(Option option : demand.options()) {
				if(!anchorBindings.acquire(option))
					continue;
				RelocationActionKey action = option.action().key();
				boolean added = option.requiresEmission() && emitted.add(action);
				String physical = added ? physicalEmissionIdentity(action) : null;
				if(added)
					physicalEmissionRefs.merge(physical, 1, Integer::sum);
				current.add(new RelocationChoiceReceipt(demand.demand(), action));
				solve(index + 1);
				current.remove(current.size() - 1);
				if(added) {
					emitted.remove(action);
					decrementPhysicalRef(physicalEmissionRefs, physical);
				}
				anchorBindings.release(option);
			}
		}
	}

	private static final class WeightedSearch {
		private final List<DemandOptions> demands;
		private final ToDoubleFunction<RelocationActionKey> emittedActionCost;
		private final List<RelocationChoiceReceipt> current = new ArrayList<>();
		private final Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		private final Map<String,Integer> physicalEmissionRefs = new LinkedHashMap<>();
		private final Map<String,Double> physicalEmissionCosts = new LinkedHashMap<>();
		private final AnchorBindings anchorBindings = new AnchorBindings();
		private List<RelocationChoiceReceipt> best;
		private List<RelocationActionKey> bestEmitted = List.of();
		private double bestCost = Double.POSITIVE_INFINITY;
		private int bestEmissionCount = Integer.MAX_VALUE;
		private String bestSignature;

		private WeightedSearch(List<DemandOptions> demands,
			ToDoubleFunction<RelocationActionKey> emittedActionCost) {
			this.demands = List.copyOf(demands);
			this.emittedActionCost = emittedActionCost;
		}

		private void solve(int index, double cost) {
			if(Double.compare(cost, bestCost) > 0)
				return;
			if(index == demands.size()) {
				List<RelocationChoiceReceipt> candidate = current.stream().sorted().toList();
				String signature = candidate.stream()
					.map(RelocationChoiceReceipt::normalizedSignature)
					.reduce((left, right) -> left + "|" + right).orElse("");
				int costOrder = Double.compare(cost, bestCost);
				if(costOrder < 0 || costOrder == 0
					&& (physicalEmissionRefs.size() < bestEmissionCount
					|| physicalEmissionRefs.size() == bestEmissionCount
						&& (bestSignature == null || signature.compareTo(bestSignature) < 0))) {
					bestCost = cost;
					bestEmissionCount = physicalEmissionRefs.size();
					bestSignature = signature;
					best = List.copyOf(candidate);
					bestEmitted = emitted.stream().sorted().toList();
				}
				return;
			}
			DemandOptions demand = demands.get(index);
			for(Option option : demand.options()) {
				if(!anchorBindings.acquire(option))
					continue;
				RelocationActionKey action = option.action().key();
				boolean added = option.requiresEmission() && emitted.add(action);
				String physical = added ? physicalEmissionIdentity(action) : null;
				double incremental = 0.0;
				if(added) {
					double actionCost = emittedActionCost.applyAsDouble(action);
					if(!Double.isFinite(actionCost) || actionCost < 0.0)
						throw new IllegalArgumentException("Relocation action cost must be finite and non-negative: "
							+ action.normalizedSignature() + " cost=" + actionCost);
					Double physicalCost = physicalEmissionCosts.get(physical);
					if(physicalCost == null) {
						physicalEmissionCosts.put(physical, actionCost);
						incremental = actionCost;
					}
					else {
						double tolerance = 1e-9 * Math.max(1.0, Math.max(physicalCost, actionCost));
						if(Math.abs(physicalCost - actionCost) > tolerance)
							throw new IllegalArgumentException(
								"Consumer-specific receipts for one physical relocation have different costs: "
									+ physical + " first=" + physicalCost + " current=" + actionCost);
					}
					physicalEmissionRefs.merge(physical, 1, Integer::sum);
				}
				current.add(new RelocationChoiceReceipt(demand.demand(), action));
				solve(index + 1, cost + incremental);
				current.remove(current.size() - 1);
				if(added) {
					emitted.remove(action);
					if(decrementPhysicalRef(physicalEmissionRefs, physical))
						physicalEmissionCosts.remove(physical);
				}
				anchorBindings.release(option);
			}
		}
	}

	/**
	 * A FED instruction executes against one worker/range placement.  Candidate
	 * rows may offer several exact anchors (for example, retain the left input's
	 * pool and REFED the right input, or vice versa), but receipts selected for
	 * one consumer must all choose the same durable anchor.  Tracking reference
	 * counts keeps this constraint exact even though MRV ordering can interleave
	 * demands from different consumers.
	 */
	private static final class AnchorBindings {
		private final Map<CompiledHopKey,DurableAnchorKey> anchors = new LinkedHashMap<>();
		private final Map<CompiledHopKey,Integer> refs = new LinkedHashMap<>();

		private boolean acquire(Option option) {
			CompiledHopKey consumer = option.obligation().consumer();
			DurableAnchorKey anchor = option.action().key().durableAnchor();
			DurableAnchorKey selected = anchors.get(consumer);
			if(selected != null && !selected.equals(anchor))
				return false;
			anchors.putIfAbsent(consumer, anchor);
			refs.merge(consumer, 1, Integer::sum);
			return true;
		}

		private void release(Option option) {
			CompiledHopKey consumer = option.obligation().consumer();
			Integer count = refs.get(consumer);
			if(count == null || count <= 0)
				throw new IllegalStateException("Durable-anchor reference count is missing: "
					+ consumer.normalizedSignature());
			if(count == 1) {
				refs.remove(consumer);
				anchors.remove(consumer);
			}
			else
				refs.put(consumer, count - 1);
		}
	}

	/** Returns true when the physical identity was removed completely. */
	private static boolean decrementPhysicalRef(Map<String,Integer> refs, String identity) {
		Integer count = refs.get(identity);
		if(count == null || count <= 0)
			throw new IllegalStateException("Physical relocation reference count is missing: " + identity);
		if(count == 1) {
			refs.remove(identity);
			return true;
		}
		refs.put(identity, count - 1);
		return false;
	}
}
