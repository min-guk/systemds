/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.ComponentBound;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exhaustive exact selector for the planner-neutral FedAll objective. */
public final class ExactPlacementSelector implements PlacementSelector, PlacementAnalysisSelector {
	private static final int BRANCH_AND_BOUND_THRESHOLD = 16;
	private static final long CARTESIAN_BRANCH_AND_BOUND_THRESHOLD = 1L << BRANCH_AND_BOUND_THRESHOLD;
	private static final long CANDIDATE_BOUND_COMPLETION_PRODUCT = 1L;

	@Override
	public PlacementSelection select(NeutralPlacementGraph graph) {
		return select(null, graph);
	}

	public PlacementSelection select(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		return select(analysis, analysis.graph());
	}

	public PlacementSelection select(PlacementAnalysis analysis, NeutralPlacementGraph graph) {
		Objects.requireNonNull(graph, "graph");
		// Synthetic graph-only analyses deliberately carry no candidate-rule rows. In
		// that representation relocation obligations are already the complete physical
		// authority, so routing them through the row-aware scorer would erase every
		// demand. Preserve the graph-owned exact path while retaining the supplied
		// analysis object in the adapter result.
		PlacementAnalysis candidateAnalysis = analysis != null
			&& !analysis.candidateRuleFacts().orderedFacts().isEmpty() ? analysis : null;
		validateRelocationSources(graph);
		List<Node> decisions = new ArrayList<>(graph.decisionNodes());
		validateDecisionAlternatives(decisions);
		CandidateSelections.PartialReachabilityIndex componentReachability =
			candidateAnalysis == null ? null : CandidateSelections.partialReachabilityIndex(
				candidateAnalysis, graph, graph.relocationActions());
		boolean branchAndBound = requiresBranchAndBound(decisions);
		SearchResult result = branchAndBound
			? solveIndependentComponents(candidateAnalysis, graph, decisions, componentReachability)
			: solve(candidateAnalysis, graph, decisions, graph.constraints(), graph.relocationActions(), false);
		if(result.assignment() == null)
			throw new IllegalStateException("neutral placement graph has no legal total assignment");
		if(result.assignment().size() != decisions.size() || !canStillBeLegal(graph.constraints(), result.assignment()))
			throw new IllegalStateException("exact component solver produced an incomplete or illegal assignment");
		validateAssignmentStateIdentity(decisions, result.assignment());
		List<ComponentBound> componentBounds = componentBounds(graph, componentReachability);
		String derivation = branchAndBound
			? "exact-independent-component-branch-and-bound-with-partial-legality-pruning"
			: "complete-cartesian-enumeration-with-partial-legality-pruning";
		TerminationReason termination = branchAndBound
			? TerminationReason.TIGHT_BOUND_EQUALITY : TerminationReason.EXHAUSTED;
		PlacementCertificate certificate = new PlacementCertificate(result.score(), result.score(),
			result.explored(), result.pruned(), sha256(result.score().normalizedSignature()),
			sha256(graph.normalizedSignature()), graph.nodes().size(), graph.constraints().size(),
			componentBounds.size(), 0, componentBounds,
			derivation, "production", -1L, termination);
		if(candidateAnalysis == null) {
			List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
				graph, graph.relocationActions(), result.assignment(), (demand, action) -> true);
			return new PlacementSelection(result.assignment(), List.of(), choices,
				selectedRelocations(graph, result.assignment()), result.score(), certificate);
		}
		CandidateSelections.Selection exact = CandidateSelections.selectMaterializationMaximal(
			analysis, graph, graph.relocationActions(), result.assignment());
		return new PlacementSelection(result.assignment(), exact.candidates(), exact.relocationChoices(),
			new LinkedHashSet<>(exact.emittedActions()), result.score(), certificate);
	}

	private static SearchResult solveIndependentComponents(PlacementAnalysis analysis,
		NeutralPlacementGraph graph, List<Node> decisions,
		CandidateSelections.PartialReachabilityIndex componentReachability) {
		List<SearchComponent> components = searchComponents(graph, decisions, componentReachability);
		Map<CompiledHopKey, PlacementState> assignment = new LinkedHashMap<>();
		long explored = 0;
		long pruned = 0;
		for(SearchComponent component : components) {
			SearchResult result = solve(analysis, graph, component.nodes(), component.constraints(),
				component.relocationActions(), true);
			if(result.assignment() == null)
				throw new IllegalStateException("neutral placement component has no legal total assignment: "
					+ component.identity());
			for(Map.Entry<CompiledHopKey, PlacementState> entry : result.assignment().entrySet())
				if(assignment.put(entry.getKey(), entry.getValue()) != null)
					throw new IllegalStateException("decision belongs to multiple exact-search components: "
						+ entry.getKey());
			explored = Math.addExact(explored, result.explored());
			pruned = Math.addExact(pruned, result.pruned());
		}
		PlacementScore score = score(analysis, graph, decisions, graph.relocationActions(), assignment);
		return new SearchResult(Map.copyOf(assignment), score, explored, pruned);
	}

	private static SearchResult solve(PlacementAnalysis analysis, NeutralPlacementGraph graph, List<Node> decisions,
		List<Constraint> constraints, List<RelocationAction> relocationActions, boolean branchAndBound) {
		Search search = new Search(analysis, graph, decisions, constraints, relocationActions, branchAndBound);
		search.solve();
		return new SearchResult(search.bestAssignment == null ? null : Map.copyOf(search.bestAssignment),
			search.bestScore, search.explored, search.pruned);
	}

	private static void validateDecisionAlternatives(List<Node> decisions) {
		for(Node node : decisions)
			if(node.legalAlternatives().isEmpty())
				throw new IllegalStateException("selectable graph node has no legal alternatives: " + node.key());
	}

	/**
	 * Selects the exact branch-and-bound implementation whenever either the
	 * original number of multi-alternative nodes or their Cartesian-domain upper bound
	 * is production-sized. Counting only multi-alternative nodes misses graphs such
	 * as 14 ternary groups (3^14 choices), even though they are substantially
	 * larger than the original 2^16 routing boundary. The saturated node-domain
	 * product is deliberately conservative: mandatory SAME_PLACEMENT quotienting
	 * can only reduce the exact domain later. This changes only the exact search
	 * strategy; it does not remove any legal placement state.
	 */
	private static boolean requiresBranchAndBound(List<Node> decisions) {
		long multiAlternativeNodes = decisions.stream()
			.filter(node -> node.legalAlternatives().size() > 1).count();
		if(multiAlternativeNodes > BRANCH_AND_BOUND_THRESHOLD)
			return true;
		long cartesianProduct = 1;
		for(Node decision : decisions) {
			int alternatives = decision.legalAlternatives().size();
			if(cartesianProduct > CARTESIAN_BRANCH_AND_BOUND_THRESHOLD / alternatives)
				return true;
			cartesianProduct *= alternatives;
		}
		return cartesianProduct > CARTESIAN_BRANCH_AND_BOUND_THRESHOLD;
	}

	private static void validateAssignmentStateIdentity(List<Node> decisions,
		Map<CompiledHopKey, PlacementState> assignment) {
		for(Node node : decisions) {
			PlacementState selected = assignment.get(node.key());
			if(selected == null || node.legalAlternatives().stream().noneMatch(state -> state == selected))
				throw new IllegalStateException(
					"exact selector assignment did not retain the target node's state identity: " + node.key());
		}
	}

	private record SearchResult(Map<CompiledHopKey, PlacementState> assignment, PlacementScore score,
		long explored, long pruned) { }
	private record AssignmentKey(List<PlacementState> states) { }

	private static Set<CompiledHopKey> dualEmissionDecisionKeys(PlacementAnalysis analysis) {
		if(analysis == null)
			return Set.of();
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			Set<PlacementState> nativeFout = new HashSet<>();
			Set<PlacementState> derivedFout = new HashSet<>();
			for(var emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(state.output() != FederatedOutput.FOUT)
					continue;
				(emission.emissionState().derivedFedFout() ? derivedFout : nativeFout).add(state);
			}
			if(nativeFout.stream().anyMatch(derivedFout::contains))
				result.add(fact.key().parentOccurrence());
		}
		return Collections.unmodifiableSet(result);
	}

	private static final class Search {
		private final PlacementAnalysis analysis;
		private final NeutralPlacementGraph graph;
		private final List<Node> decisions;
		private final List<Constraint> constraints;
		private final List<RelocationAction> relocationActions;
		private final List<DecisionGroup> groups;
		private final Map<DecisionGroup,Integer> groupCanonicalRanks;
		private final Map<CompiledHopKey,DecisionGroup> groupByDecision;
		private final CandidateSelections.PartialReachabilityIndex candidateReachability;
		private final Map<DecisionGroup,CandidateSelections.PartialReachabilityIndex.ChangedNodesReachabilityProbe>
			candidateReachabilityProbes;
		private final Map<DecisionGroup,List<Constraint>> constraintsByGroup;
		private final Set<DecisionGroup> dualEmissionGroups;
		private final Set<CompiledHopKey> dualEmissionDecisionKeys;
		private final Map<RelocationAction,Integer> physicalEmissionIds;
		private final Map<PlacementState,Integer> placementStateIds;
		private final Map<RelocationAction,List<DirectSourceDomain>> directSourceDomains;
		private final Map<CompiledHopKey,Map<Integer,List<RelocationAction>>>
			relocationOptionsByConsumer;
		private final Map<CandidateSelectionReceipt,CandidateBoundRow> candidateBoundRows =
			new IdentityHashMap<>();
		private final Map<CompiledHopKey,List<PlacementAnalysis.CompiledInputEdgeFact>>
			inputEdgesByProducer;
		private final Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>>
			functionInputsBySource;
		private final RelocationSelections.CanonicalOrderIndex relocationOrder;
		private final Map<CompiledHopKey, PlacementState> current = new IdentityHashMap<>();
		private final Map<AssignmentKey,ScoredPlan> scoreCache = new HashMap<>();
		private final Set<AssignmentKey> unavailableScoreCache = new HashSet<>();
		private final boolean branchAndBound;
		private Map<CompiledHopKey, PlacementState> bestAssignment;
		private PlacementScore bestScore;
		private long explored;
		private long pruned;
		private long visitedPrefixes;
		private List<String> firstCandidateRejection = List.of();

		private Search(PlacementAnalysis analysis, NeutralPlacementGraph graph,
			List<Node> decisions, List<Constraint> constraints,
			List<RelocationAction> relocationActions, boolean branchAndBound) {
			this.analysis = analysis;
			this.graph = graph;
			this.decisions = decisions.stream().sorted().toList();
			this.constraints = List.copyOf(constraints);
			this.relocationActions = List.copyOf(relocationActions);
			this.candidateReachability = analysis == null ? null
				: CandidateSelections.partialReachabilityIndex(analysis, graph, relocationActions);
			this.dualEmissionDecisionKeys = dualEmissionDecisionKeys(analysis);
			this.physicalEmissionIds = indexPhysicalEmissions(this.relocationActions);
			this.placementStateIds = indexPlacementStates(this.decisions);
			this.directSourceDomains = indexDirectSourceDomains(this.decisions,
				this.relocationActions, this.placementStateIds);
			this.relocationOptionsByConsumer = indexRelocationOptions(this.relocationActions);
			this.inputEdgesByProducer = indexInputEdgesByProducer(analysis);
			this.functionInputsBySource = indexFunctionInputsBySource(analysis);
			this.relocationOrder = analysis == null
				? RelocationSelections.canonicalOrderIndex(this.relocationActions)
				: analysis.relocationOrderFor(this.relocationActions);
			this.branchAndBound = branchAndBound;
			List<DecisionGroup> equalityGroups = samePlacementGroups(decisions, constraints);
			Map<DecisionGroup,Integer> canonicalRanks = new IdentityHashMap<>();
			for(int rank = 0; rank < equalityGroups.size(); rank++)
				canonicalRanks.put(equalityGroups.get(rank), rank);
			this.groupCanonicalRanks = Collections.unmodifiableMap(canonicalRanks);
			Map<CompiledHopKey,DecisionGroup> groupsByDecision = new IdentityHashMap<>();
			for(DecisionGroup group : equalityGroups)
				for(Node member : group.members())
					groupsByDecision.put(member.key(), group);
			this.groupByDecision = Collections.unmodifiableMap(groupsByDecision);
			if(branchAndBound) {
				for(DecisionGroup group : equalityGroups)
					if(group.legalAlternatives().size() == 1)
						group.assign(current, group.legalAlternatives().get(0));
				boolean structurallyLegal = ExactPlacementSelector.canStillBeLegal(constraints, current);
				boolean candidatesReachable = candidateReachability == null
					|| candidateReachability.canStillBeReachable(current);
				if(!structurallyLegal || !candidatesReachable) {
					List<String> violated = constraints.stream().filter(constraint -> {
						PlacementState left = current.get(constraint.left());
						PlacementState right = current.get(constraint.right());
						return left != null && right != null
							&& !NeutralPlacementGraph.constraintSatisfied(constraint, left, right);
					}).map(Constraint::normalizedSignature).toList();
					throw new IllegalStateException("neutral placement graph has incompatible fixed states"
						+ "|structurallyLegal=" + structurallyLegal
						+ "|candidatesReachable=" + candidatesReachable
						+ "|violated=" + violated + "|fixed=" + current.entrySet().stream()
							.map(entry -> entry.getKey().normalizedSignature() + '='
								+ entry.getValue().normalizedSignature()).sorted().toList());
				}
				groups = equalityGroups.stream().filter(group -> group.legalAlternatives().size() > 1)
					.sorted((left, right) -> {
						int degree = Integer.compare(constraintDegree(constraints, right),
							constraintDegree(constraints, left));
						return degree != 0 ? degree : compareGroupOrder(left, right);
					}).toList();
			}
			else {
				groups = List.copyOf(equalityGroups);
			}
			this.candidateReachabilityProbes = new IdentityHashMap<>();
			if(candidateReachability != null)
				for(DecisionGroup group : equalityGroups)
					candidateReachabilityProbes.put(group,
						candidateReachability.changedNodesProbe(group.members()));
			this.constraintsByGroup = new IdentityHashMap<>();
			for(DecisionGroup group : equalityGroups)
				constraintsByGroup.put(group, constraints.stream()
					.filter(constraint -> group.contains(constraint.left())
						|| group.contains(constraint.right())).toList());
			Set<DecisionGroup> dualGroups = Collections.newSetFromMap(new IdentityHashMap<>());
			for(DecisionGroup group : equalityGroups)
				for(Node member : group.members())
					if(dualEmissionDecisionKeys.contains(member.key())) {
						dualGroups.add(group);
						break;
					}
			this.dualEmissionGroups = Collections.unmodifiableSet(dualGroups);
		}

		private void solve() {
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Exact-Search-Start",
					"decisions=" + decisions.size() + " groups=" + groups.size()
						+ " fixed=" + current.size() + " constraints=" + constraints.size()
						+ " relocations=" + relocationActions.size()
						+ " branchAndBound=" + branchAndBound);
			if(branchAndBound)
				enumerateWithPropagation();
			else
				enumerateCartesian(0);
			if(bestAssignment == null && !firstCandidateRejection.isEmpty())
				throw new IllegalStateException("neutral placement graph has no candidate-reachable total assignment"
					+ "|firstCandidateRejection=" + firstCandidateRejection);
			if(bestAssignment != null)
				bestScore = score(analysis, graph, decisions, relocationActions, bestAssignment,
					relocationOrder, candidateReachability);
			if(FederatedPlannerTrace.isEnabled())
				FederatedPlannerTrace.logGlobal("Exact-Search-Complete",
					"decisions=" + decisions.size() + " groups=" + groups.size()
						+ " prefixes=" + visitedPrefixes + " explored=" + explored
						+ " pruned=" + pruned
						+ " best=" + bestScoreSummary());
		}

		private boolean candidatesReachable() {
			if(candidateReachability == null)
				return true;
			if(candidateReachability.canStillBeReachable(current))
				return true;
			List<String> unreachable = CandidateSelections.unreachableConsumers(
				analysis, graph, relocationActions, current);
			if(!unreachable.isEmpty() && firstCandidateRejection.isEmpty())
				firstCandidateRejection = unreachable;
			return false;
		}

		private boolean candidatesReachable(DecisionGroup changed) {
			if(candidateReachability == null)
				return true;
			if(candidateReachability.canStillBeReachable(current,
				candidateReachabilityProbes.get(changed)))
				return true;
			List<String> unreachable = CandidateSelections.unreachableConsumers(
				analysis, graph, relocationActions, current);
			if(!unreachable.isEmpty() && firstCandidateRejection.isEmpty())
				firstCandidateRejection = unreachable;
			return false;
		}

		private void enumerateCartesian(int index) {
			if(index == groups.size()) {
				evaluateCurrent();
				return;
			}
			DecisionGroup group = groups.get(index);
			List<PlacementState> alternatives = new ArrayList<>(group.legalAlternatives());
			Collections.sort(alternatives);
			for(PlacementState state : alternatives) {
				group.assign(current, state);
					if(canStillBeLegal(group) && candidatesReachable(group))
					enumerateCartesian(index + 1);
				else
					pruned++;
			}
			group.remove(current);
		}

		/**
		 * Exact branch-and-bound with singleton propagation and dynamic MRV ordering.
		 * Every retained state is still one of the neutral graph's legal alternatives;
		 * the propagation only proves that an alternative has no completion under the
		 * already selected equality/constraint/candidate reachability prefix.
		 */
		private void enumerateWithPropagation() {
			visitedPrefixes++;
			if(FederatedPlannerTrace.isEnabled()
				&& (visitedPrefixes & (visitedPrefixes - 1L)) == 0L)
				FederatedPlannerTrace.logGlobal("Exact-Search-Progress",
					"decisions=" + decisions.size() + " groups=" + groups.size()
						+ " prefixes=" + visitedPrefixes + " assigned=" + current.size()
						+ " explored=" + explored + " pruned=" + pruned
						+ " best=" + bestScoreSummary());
			List<DecisionGroup> propagated = new ArrayList<>();
			try {
				Map<DecisionGroup,List<PlacementState>> domains;
				while(true) {
					domains = feasibleDomains();
					if(domains == null) {
						pruned++;
						return;
					}
					DecisionGroup singleton = null;
					for(Map.Entry<DecisionGroup,List<PlacementState>> entry : domains.entrySet())
						if(entry.getValue().size() == 1 && (singleton == null
							|| compareGroupOrder(entry.getKey(), singleton) < 0))
							singleton = entry.getKey();
					if(singleton == null)
						break;
					singleton.assign(current, domains.get(singleton).get(0));
					propagated.add(singleton);
				}
				if(bestScore != null && cannotBeatIncumbent(domains)) {
					pruned++;
					return;
				}
				if(domains.isEmpty()) {
					evaluateCurrent();
					return;
				}
				Map<DecisionGroup,List<PlacementState>> remainingDomains = domains;
				DecisionGroup selected = remainingDomains.keySet().stream().min((left, right) -> {
					// MRV and constraint degree lead until the first feasible incumbent;
					// otherwise dual-emission rows can postpone the reachability-defining
					// LogReg groups for millions of prefixes. Once feasibility is known,
					// physical native-vs-derived FOUT choices become the useful first
					// discriminator and tighten the incumbent before the remaining exact
					// proof. Both phases change only traversal order, never candidates,
					// constraints, objective values, or the final exact certificate.
					if(bestScore != null) {
						int incumbentDual = Boolean.compare(groupHasDualEmission(right),
							groupHasDualEmission(left));
						if(incumbentDual != 0)
							return incumbentDual;
					}
					int domain = Integer.compare(remainingDomains.get(left).size(),
						remainingDomains.get(right).size());
					if(domain != 0)
						return domain;
					int degree = Integer.compare(constraintsByGroup.get(right).size(),
						constraintsByGroup.get(left).size());
					if(degree != 0)
						return degree;
					int dual = bestScore == null ? Boolean.compare(groupHasDualEmission(right),
						groupHasDualEmission(left)) : 0;
					return dual != 0 ? dual : compareGroupOrder(left, right);
				}).orElseThrow();
				List<PlacementState> alternatives = new ArrayList<>(remainingDomains.get(selected));
				alternatives = orderedAlternatives(selected, remainingDomains, alternatives);
				for(PlacementState state : alternatives) {
					selected.assign(current, state);
					enumerateWithPropagation();
					selected.remove(current);
				}
			}
			finally {
				for(int index = propagated.size() - 1; index >= 0; index--)
					propagated.get(index).remove(current);
			}
		}

		private String bestScoreSummary() {
			return bestScore == null ? "-" : bestScore.emittedFedCount() + "/"
				+ bestScore.foutCount() + "/" + bestScore.distinctRelocationCount();
		}

		/**
		 * Seeds branch-and-bound with a strong exact-policy incumbent.  The primary
		 * FED/FOUT order is unchanged.  Among states tied on both primary fields,
		 * visit the state with the smallest admissible physical-emission bound first.
		 * This is evaluated only until the first complete incumbent exists, so it
		 * changes enumeration order without multiplying every later prefix bound.
		 */
		private List<PlacementState> orderedAlternatives(DecisionGroup selected,
			Map<DecisionGroup,List<PlacementState>> remainingDomains,
			List<PlacementState> alternatives) {
			Map<PlacementState,Integer> physicalHints = new IdentityHashMap<>();
			if(bestScore == null && analysis != null && alternatives.size() > 1) {
				Map<DecisionGroup,List<PlacementState>> tail = new LinkedHashMap<>(remainingDomains);
				tail.remove(selected);
				for(PlacementState state : alternatives) {
					selected.assign(current, state);
					try {
						CompetitiveDomains competitive = primaryCompetitiveStateDomains(tail);
						physicalHints.put(state, competitive == null ? Integer.MAX_VALUE
							: candidateAwarePhysicalEmissionLowerBound(competitive.byNode()));
					}
					finally {
						selected.remove(current);
					}
				}
			}
			alternatives.sort((left, right) -> {
				int fed = Boolean.compare(right.execType() == ExecType.FED,
					left.execType() == ExecType.FED);
				if(fed != 0)
					return fed;
				int fout = Boolean.compare(right.output() == FederatedOutput.FOUT,
					left.output() == FederatedOutput.FOUT);
				if(fout != 0)
					return fout;
				int physical = Integer.compare(physicalHints.getOrDefault(left, Integer.MAX_VALUE),
					physicalHints.getOrDefault(right, Integer.MAX_VALUE));
				return physical != 0 ? physical : comparePlacementStateCanonical(left, right);
			});
			return alternatives;
		}

		private boolean groupHasDualEmission(DecisionGroup group) {
			return dualEmissionGroups.contains(group);
		}

		private int compareGroupOrder(DecisionGroup left, DecisionGroup right) {
			return Integer.compare(groupCanonicalRanks.get(left), groupCanonicalRanks.get(right));
		}

		/** Returns null when at least one unassigned group has no legal completion. */
		private Map<DecisionGroup,List<PlacementState>> feasibleDomains() {
			Map<DecisionGroup,List<PlacementState>> domains = new LinkedHashMap<>();
			for(DecisionGroup group : groups) {
				if(group.assigned(current))
					continue;
				List<PlacementState> feasible = new ArrayList<>();
				for(PlacementState state : group.legalAlternatives()) {
					group.assign(current, state);
					boolean completable = canStillBeLegal(group)
						&& candidatesReachable(group);
					group.remove(current);
					if(completable)
						feasible.add(state);
				}
				if(feasible.isEmpty())
					return null;
				domains.put(group, List.copyOf(feasible));
			}
			return domains;
		}

		private void evaluateCurrent() {
			AssignmentKey assignmentKey = assignmentKey();
			if(unavailableScoreCache.contains(assignmentKey)) {
				pruned++;
				return;
			}
			ScoredPlan candidate;
			candidate = scoreCache.get(assignmentKey);
			if(candidate == null) {
				try {
					candidate = scorePlan(analysis, graph, decisions, relocationActions, current,
						relocationOrder, candidateReachability);
					scoreCache.put(assignmentKey, candidate);
				}
				catch(IllegalArgumentException | IllegalStateException unavailable) {
					unavailableScoreCache.add(assignmentKey);
					pruned++;
					return;
				}
			}
			explored++;
			int objectiveOrder = bestScore == null ? 1 : compareObjective(candidate, bestScore);
			if(objectiveOrder > 0 || objectiveOrder == 0
					&& compareAssignments(current, bestAssignment, decisions) < 0) {
				bestScore = new PlacementScore(candidate.emittedFedCount(), candidate.foutCount(),
					candidate.distinctRelocationCount(), "");
				bestAssignment = new IdentityHashMap<>(current);
				if(FederatedPlannerTrace.isEnabled())
					FederatedPlannerTrace.logGlobal("Exact-Search-Incumbent",
						"decisions=" + decisions.size() + " groups=" + groups.size()
							+ " prefixes=" + visitedPrefixes + " explored=" + explored
							+ " fed=" + candidate.emittedFedCount()
							+ " fout=" + candidate.foutCount()
							+ " physical=" + candidate.distinctRelocationCount());
			}
		}

		private AssignmentKey assignmentKey() {
			List<PlacementState> states = new ArrayList<>(decisions.size());
			for(Node node : decisions) {
				PlacementState state = current.get(node.key());
				if(state == null)
					throw new IllegalStateException(
						"Cannot cache an incomplete exact placement assignment");
				states.add(state);
			}
			return new AssignmentKey(List.copyOf(states));
		}

		private static int compareObjective(ScoredPlan candidate, PlacementScore incumbent) {
			int comparison = Integer.compare(candidate.emittedFedCount(), incumbent.emittedFedCount());
			if(comparison != 0)
				return comparison;
			comparison = Integer.compare(candidate.foutCount(), incumbent.foutCount());
			if(comparison != 0)
				return comparison;
			return Integer.compare(incumbent.distinctRelocationCount(),
				candidate.distinctRelocationCount());
		}

		private boolean cannotBeatIncumbent(
			Map<DecisionGroup,List<PlacementState>> remainingDomains) {
			int fed = 0;
			int fout = 0;
			for(PlacementState state : current.values()) {
				if(state.execType() == ExecType.FED) fed++;
				if(state.output() == FederatedOutput.FOUT) fout++;
			}
			for(Map.Entry<DecisionGroup,List<PlacementState>> entry : remainingDomains.entrySet()) {
				DecisionGroup group = entry.getKey();
				List<PlacementState> alternatives = entry.getValue();
				if(alternatives.stream().anyMatch(state -> state.execType() == ExecType.FED))
					fed += group.members().size();
				if(alternatives.stream().anyMatch(state -> state.output() == FederatedOutput.FOUT))
					fout += group.members().size();
			}
			if(fed < bestScore.emittedFedCount())
				return true;
			if(fed > bestScore.emittedFedCount())
				return false;
			if(fout < bestScore.foutCount())
				return true;
			if(fout > bestScore.foutCount())
				return false;
			if(analysis != null) {
				CompetitiveDomains competitiveDomains =
					primaryCompetitiveStateDomains(remainingDomains);
				if(competitiveDomains == null)
					return true;
				int candidateAwareLowerBound = candidateAwarePhysicalEmissionLowerBound(
					competitiveDomains.byNode());
				if(candidateAwareLowerBound > bestScore.distinctRelocationCount())
					return true;
				if(candidateAwareLowerBound < bestScore.distinctRelocationCount())
					return false;
				int assignmentOrder = compareOptimisticAssignmentToBest(remainingDomains);
				// The selected candidate/relocation suffix is a deterministic function of
				// one complete assignment. If the lexicographically smallest completion
				// is the incumbent itself, every other completion is worse and replaying
				// that same suffix cannot improve the plan.
				if(assignmentOrder >= 0)
					return true;
				if(!competitiveDomains.groups().isEmpty() && competitiveDomains.product()
					<= CANDIDATE_BOUND_COMPLETION_PRODUCT) {
					evaluateCompetitiveCompletions(competitiveDomains.groups(), 0);
					return true;
				}
				return false;
			}
			int relocationLowerBound = unavoidableRelocationCount(graph, decisions, relocationActions, current);
			if(relocationLowerBound > bestScore.distinctRelocationCount())
				return true;
			if(relocationLowerBound < bestScore.distinctRelocationCount())
				return false;
			return compareOptimisticAssignmentToBest(remainingDomains) > 0;
		}

		/**
		 * States that can still tie both already-proven primary objective fields.
		 * If one group cannot attain its independent FED and FOUT maxima in the same
		 * state, no completion can tie the incumbent pair and the branch is dominated.
		 */
		private CompetitiveDomains primaryCompetitiveStateDomains(
			Map<DecisionGroup,List<PlacementState>> remainingDomains) {
			Map<CompiledHopKey,List<PlacementState>> result = new IdentityHashMap<>();
			List<CompetitiveGroup> groupDomains = new ArrayList<>();
			long product = 1;
			for(Map.Entry<DecisionGroup,List<PlacementState>> entry : remainingDomains.entrySet()) {
				boolean requireFed = entry.getValue().stream()
					.anyMatch(state -> state.execType() == ExecType.FED);
				boolean requireFout = entry.getValue().stream()
					.anyMatch(state -> state.output() == FederatedOutput.FOUT);
				List<PlacementState> competitive = entry.getValue().stream().filter(state ->
					(!requireFed || state.execType() == ExecType.FED)
						&& (!requireFout || state.output() == FederatedOutput.FOUT)).toList();
				if(competitive.isEmpty())
					return null;
				if(product > CANDIDATE_BOUND_COMPLETION_PRODUCT / competitive.size())
					product = CANDIDATE_BOUND_COMPLETION_PRODUCT + 1;
				else
					product *= competitive.size();
				groupDomains.add(new CompetitiveGroup(entry.getKey(), competitive));
				for(Node member : entry.getKey().members()) {
					List<PlacementState> owned = competitive.stream().map(state ->
						member.legalAlternatives().stream().filter(state::equals).findFirst()
							.orElseThrow(() -> new IllegalStateException(
								"Competitive group state has no node-owned identity"))).toList();
					result.put(member.key(), owned);
				}
			}
			return new CompetitiveDomains(Collections.unmodifiableMap(result),
				List.copyOf(groupDomains), product);
		}

		private void evaluateCompetitiveCompletions(List<CompetitiveGroup> remaining, int index) {
			if(index == remaining.size()) {
				DecisionGroup changed = remaining.get(Math.max(0, index - 1)).group();
				if(canStillBeLegal(changed) && candidatesReachable(changed))
					evaluateCurrent();
				else
					pruned++;
				return;
			}
			CompetitiveGroup next = remaining.get(index);
			for(PlacementState state : next.states()) {
				next.group().assign(current, state);
				try {
					if(canStillBeLegal(next.group()) && candidatesReachable(next.group()))
						evaluateCompetitiveCompletions(remaining, index + 1);
					else
						pruned++;
				}
				finally {
					next.group().remove(current);
				}
			}
		}

		private boolean canStillBeLegal(DecisionGroup changed) {
			return ExactPlacementSelector.canStillBeLegal(
				constraintsByGroup.getOrDefault(changed, List.of()), current);
		}

		/**
		 * Admissible lower bound for the complete FedAll physical-emission score.
		 * Partial feasible rows are an explicit superset of every completion. For
		 * each selected derived-FOUT/CP-FOUT consumer, the minimum unavoidable
		 * materialization is one; native alternatives lower this contribution to
		 * zero. Graph relocation demands are counted only when every still-reachable
		 * row for that consumer/input keeps the same demand active and no selected or
		 * future direct source can satisfy it. Native FOUT-to-local downloads are
		 * counted only when both the native producer emission and at least one local
		 * consumer obligation survive every reachable row. The three action classes
		 * are physically disjoint, so their lower bounds can be added.
		 */
		private int candidateAwarePhysicalEmissionLowerBound(
			Map<CompiledHopKey,List<PlacementState>> competitiveDomains) {
			// orderedAlternatives evaluates one tentative state against the tail domains
			// computed before that state was assigned.  The tentative assignment can make
			// an otherwise feasible tail candidate-incoherent (for example, a CP/LOUT
			// producer followed only by FED/FOUT rows without a relocation action). Such a
			// tail cannot seed or improve an incumbent and therefore has an infinite lower
			// bound; asking the strict row projector to score it would incorrectly turn a
			// prunable branch into a planner failure.
			if(current.size() != decisions.size()
				&& !candidateReachability.canStillBeReachable(current, competitiveDomains))
				return Integer.MAX_VALUE;
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> reachable =
				current.size() == decisions.size()
					? candidateReachability
						.materializationObjectiveVariantsForCompleteAssignment(current)
					: candidateReachability.feasibleVariantsForStateDomains(
						current, competitiveDomains);
			List<BitSet> unavoidableOptions = new ArrayList<>();
			Map<CompiledHopKey,List<SourceRelocationDemand>> demandsByDirectSource =
				new IdentityHashMap<>();
			for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : reachable.entrySet()) {
				BitSet inputPositions = candidateInputPositions(entry.getValue());
				if(inputPositions.isEmpty())
					continue;
				for(int inputPosition = inputPositions.nextSetBit(0); inputPosition >= 0;
					inputPosition = inputPositions.nextSetBit(inputPosition + 1)) {
					BitSet physicalOptions = new BitSet();
					BitSet directStateIds = new BitSet();
					CompiledHopKey directSource = null;
					boolean commonDirectSource = true;
					boolean everyRowRequiresRelocation = true;
					boolean everyRowHasAction = true;
					for(CandidateSelectionReceipt row : entry.getValue()) {
						CandidateBoundRow boundRow = candidateBoundRow(row);
						if(!boundRow.presentInputs().get(inputPosition)) {
							everyRowRequiresRelocation = false;
							break;
						}
						List<CandidateBoundRelocationOption> matchingOptions =
							boundRow.relocationOptionsByInput().get(inputPosition);
						boolean matchingAction = matchingOptions != null
							&& !matchingOptions.isEmpty();
						boolean direct = false;
						for(CandidateBoundRelocationOption option :
							matchingOptions == null ? List.<CandidateBoundRelocationOption>of()
								: matchingOptions) {
							List<DirectSourceDomain> sourceDomains = option.sourceDomains();
							if(sourceDomains.size() != 1)
								commonDirectSource = false;
							else {
								DirectSourceDomain source = sourceDomains.get(0);
								if(directSource == null)
									directSource = source.source();
								else if(directSource != source.source())
									commonDirectSource = false;
								directStateIds.or(source.directStateIds());
							}
							if(sourceCanAvoidRelocation(sourceDomains, current, competitiveDomains)) {
								direct = true;
							}
							physicalOptions.set(option.physicalEmissionId());
						}
						if(!matchingAction) {
							everyRowHasAction = false;
							everyRowRequiresRelocation = false;
							break;
						}
						if(direct)
							everyRowRequiresRelocation = false;
					}
					if(everyRowRequiresRelocation && !physicalOptions.isEmpty())
						// This set is owned by the current lower-bound invocation and is
						// never mutated after publication. Copying it at every search prefix
						// only repeats the same boxed physical-id allocation.
						unavoidableOptions.add(physicalOptions);
					if(everyRowHasAction && commonDirectSource && directSource != null
						&& !physicalOptions.isEmpty())
						demandsByDirectSource.computeIfAbsent(directSource,
								ignored -> new ArrayList<>()).add(new SourceRelocationDemand(
									physicalOptions, directStateIds));
				}
			}
			int unavoidableRelocations = Math.max(
				disjointBitSetAlternativeSetLowerBound(unavoidableOptions),
				sourceConflictRelocationLowerBound(demandsByDirectSource, competitiveDomains));
			int unavoidableOutputMaterializations = unavoidableOutputMaterializationCount(
				reachable, competitiveDomains);
			int bound = Math.addExact(unavoidableRelocations,
				unavoidableOutputMaterializations);
			return bound;
		}

		/**
		 * Hoists candidate-row/action joins out of the branch-and-bound prefix loop.
		 * The cache is identity keyed because feasibleVariants returns receipts owned by
		 * the immutable reachability index.  Only source-state reachability remains
		 * assignment dependent.
		 */
		private CandidateBoundRow candidateBoundRow(CandidateSelectionReceipt receipt) {
			CandidateBoundRow indexed = candidateBoundRows.get(receipt);
			if(indexed != null)
				return indexed;
			BitSet presentInputs = new BitSet(receipt.rule().orderedInputs().size());
			for(int position = 0; position < receipt.rule().orderedInputs().size(); position++)
				if(receipt.rule().orderedInputs().get(position).present())
					presentInputs.set(position);
			Map<Integer,List<CandidateBoundRelocationOption>> byInput = new LinkedHashMap<>();
			Map<Integer,List<RelocationAction>> actionOptions = relocationOptionsByConsumer
				.getOrDefault(receipt.rule().parentOccurrence(), Map.of());
			PlacementState selected = receipt.emission().emissionState().placementState();
			for(Map.Entry<Integer,List<RelocationAction>> entry : actionOptions.entrySet()) {
				int position = entry.getKey();
				if(!presentInputs.get(position))
					continue;
				var required = receipt.rule().orderedInputs().get(position).fType();
				List<CandidateBoundRelocationOption> matches = new ArrayList<>();
				for(RelocationAction action : entry.getValue())
					if(action.key().targetPlacement().equals(selected)
						&& action.key().materializationFType() == required)
						matches.add(new CandidateBoundRelocationOption(
							physicalEmissionIds.get(action),
							directSourceDomains.getOrDefault(action, List.of())));
				if(!matches.isEmpty())
					byInput.put(position, List.copyOf(matches));
			}
			boolean materializesFout = receipt.emission().emissionState().derivedFedFout()
				|| selected.execType() == ExecType.CP
					&& selected.output() == FederatedOutput.FOUT;
			BitSet relocationInputPositions = new BitSet();
			for(int position : byInput.keySet())
				relocationInputPositions.set(position);
			CandidateBoundRow created = new CandidateBoundRow(presentInputs,
				relocationInputPositions, Collections.unmodifiableMap(byInput),
				receipt.emission().emissionState().derivedFedFout(), materializesFout);
			candidateBoundRows.put(receipt, created);
			return created;
		}

		private BitSet candidateInputPositions(
			List<CandidateSelectionReceipt> receipts) {
			BitSet positions = new BitSet();
			for(CandidateSelectionReceipt receipt : receipts)
				positions.or(candidateBoundRow(receipt).relocationInputPositions());
			return positions;
		}

		private int sourceConflictRelocationLowerBound(
			Map<CompiledHopKey,List<SourceRelocationDemand>> demandsBySource,
			Map<CompiledHopKey,List<PlacementState>> competitiveDomains) {
			List<SourceRelocationBound> bounds = new ArrayList<>();
			for(Map.Entry<CompiledHopKey,List<SourceRelocationDemand>> entry :
				demandsBySource.entrySet()) {
				PlacementState selected = current.get(entry.getKey());
				List<PlacementState> possible = selected == null
					? competitiveDomains.get(entry.getKey()) : List.of(selected);
				if(possible == null || possible.isEmpty())
					continue;
				int minimum = Integer.MAX_VALUE;
				for(PlacementState state : possible) {
					List<BitSet> relocated = new ArrayList<>();
					int stateId = placementStateId(state);
					for(SourceRelocationDemand demand : entry.getValue())
						if(!demand.directStateIds().get(stateId))
							relocated.add(demand.physicalOptions());
					minimum = Math.min(minimum,
						disjointBitSetAlternativeSetLowerBound(relocated));
				}
				if(minimum <= 0)
					continue;
				BitSet universe = new BitSet();
				entry.getValue().forEach(demand -> universe.or(demand.physicalOptions()));
				bounds.add(new SourceRelocationBound(universe, minimum));
			}
			bounds.sort((left, right) -> Integer.compare(right.minimum(), left.minimum()));
			BitSet countedPhysical = new BitSet();
			int result = 0;
			for(SourceRelocationBound bound : bounds) {
				if(bound.physicalUniverse().intersects(countedPhysical))
					continue;
				result = Math.addExact(result, bound.minimum());
				countedPhysical.or(bound.physicalUniverse());
			}
			return result;
		}

		/**
		 * Counts producer-disjoint output materializations that every completion must
		 * emit.  Native FED/FOUT and derived/CP FOUT rows are alternatives for the
		 * same placement state: the former can force a FED-to-local download while the
		 * latter forces an output materialization.  Bounding those alternatives in
		 * separate loops loses the disjunction and incorrectly reports zero.  This
		 * producer-owned union counts one only when every remaining state/row pays at
		 * least one of the two disjoint physical actions.
		 */
		private int unavoidableOutputMaterializationCount(
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> reachable,
			Map<CompiledHopKey,List<PlacementState>> competitiveDomains) {
			int count = 0;
			for(Node producerNode : decisions) {
				List<PlacementState> producerStates = possibleStates(
					producerNode.key(), competitiveDomains);
				if(producerStates.isEmpty())
					continue;
				List<CandidateSelectionReceipt> producerRows = reachable.get(producerNode.key());
				boolean everyStateMaterializes = true;
				for(PlacementState producer : producerStates) {
					boolean nativeLocal = nativeFoutNeedsLocalMaterialization(producerNode,
						producer, reachable, competitiveDomains);
					boolean matchedRow = false;
					if(producerRows != null)
						for(CandidateSelectionReceipt row : producerRows) {
							if(!row.emission().emissionState().placementState().equals(producer))
								continue;
							matchedRow = true;
							if(!candidateBoundRow(row).materializesFout() && !nativeLocal) {
								everyStateMaterializes = false;
								break;
							}
						}
					if(!everyStateMaterializes)
						break;
					if(!matchedRow && !nativeLocal) {
						everyStateMaterializes = false;
						break;
					}
				}
				if(everyStateMaterializes)
					count++;
			}
			return count;
		}

		private boolean nativeFoutNeedsLocalMaterialization(Node producerNode,
			PlacementState producer,
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> reachable,
			Map<CompiledHopKey,List<PlacementState>> competitiveDomains) {
			if(producer.execType() != ExecType.FED
				|| producer.output() != FederatedOutput.FOUT)
				return false;
			for(var edge : inputEdgesByProducer.getOrDefault(producerNode.key(), List.of())) {
				if(analysis.isDmlFunctionCallBoundary(edge.consumer()))
					continue;
				if(everyPossibleConsumerNeedsLocalMaterialization(edge.consumer(),
					edge.inputPosition(), reachable, competitiveDomains))
					return true;
			}
			for(var fact : functionInputsBySource.getOrDefault(producerNode.key(), List.of())) {
				PlacementState formal = current.get(fact.targetRead());
				if(formal == null || formal.execType() != ExecType.CP
					|| formal.output() != FederatedOutput.LOUT)
					continue;
				CompiledHopKey call = analysis.requireExactPhysicalFunctionInputConsumer(fact);
				if(current.get(call) != null)
					return true;
			}
			return false;
		}

		/**
		 * Exact primary-competitive state domain for one decision.  A selected state
		 * is a singleton; otherwise the caller-supplied domains are the admissible
		 * superset used by the branch-and-bound lower bound.
		 */
		private List<PlacementState> possibleStates(CompiledHopKey key,
			Map<CompiledHopKey,List<PlacementState>> competitiveDomains) {
			PlacementState selected = current.get(key);
			return selected == null
				? competitiveDomains.getOrDefault(key, List.of()) : List.of(selected);
		}

		/**
		 * A native FED/FOUT producer must be materialized locally when every
		 * primary-competitive state of this consumer either executes CP/LOUT or is
		 * a FED candidate whose every still-reachable row omits the producer input.
		 * This is an admissible partial-assignment lift of the former complete-only
		 * check: one avoiding state or row is sufficient to return false.
		 */
		private boolean everyPossibleConsumerNeedsLocalMaterialization(
			CompiledHopKey consumerKey, int inputPosition,
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> reachable,
			Map<CompiledHopKey,List<PlacementState>> competitiveDomains) {
			List<PlacementState> possible = possibleStates(consumerKey, competitiveDomains);
			if(possible.isEmpty())
				return false;
			List<CandidateSelectionReceipt> rows = reachable.get(consumerKey);
			for(PlacementState consumer : possible) {
				if(consumer.execType() == ExecType.CP
					&& consumer.output() == FederatedOutput.LOUT)
					continue;
				if(consumer.execType() != ExecType.FED || rows == null || rows.isEmpty())
					return false;
				boolean hasReachableRowForState = false;
				for(CandidateSelectionReceipt row : rows) {
					if(!row.emission().emissionState().placementState().equals(consumer))
						continue;
					hasReachableRowForState = true;
					if(candidateBoundRow(row).presentInputs().get(inputPosition))
						return false;
				}
				if(!hasReachableRowForState)
					return false;
			}
			return true;
		}

		/**
		 * Returns a lexicographic lower bound for every completion of the current
		 * partial assignment. Candidate legality and relocation actions are omitted
		 * deliberately: choosing the smallest state for each remaining node and an
		 * empty relocation suffix can only make the signature smaller.
		 */
		private int compareOptimisticAssignmentToBest(
			Map<DecisionGroup,List<PlacementState>> remainingDomains) {
			if(bestAssignment == null)
				return -1;
			for(Node node : decisions) {
				PlacementState optimistic = current.get(node.key());
				if(optimistic == null) {
					DecisionGroup group = groupByDecision.get(node.key());
					List<PlacementState> domain = remainingDomains.get(group);
					if(domain == null || domain.isEmpty())
						throw new IllegalStateException(
							"Optimistic assignment has no remaining exact state domain");
					optimistic = domain.get(0);
					for(int index = 1; index < domain.size(); index++)
						if(comparePlacementStateCanonical(domain.get(index), optimistic) < 0)
							optimistic = domain.get(index);
				}
				PlacementState incumbent = bestAssignment.get(node.key());
				if(incumbent == null)
					throw new IllegalStateException(
						"Incumbent exact assignment is missing a decision state");
				int comparison = comparePlacementStateCanonical(optimistic, incumbent);
				if(comparison != 0)
					return comparison;
			}
			return 0;
		}

		private static Map<RelocationAction,Integer> indexPhysicalEmissions(
			List<RelocationAction> actions) {
			Map<String,Integer> bySignature = new HashMap<>();
			Map<RelocationAction,Integer> result = new IdentityHashMap<>();
			for(RelocationAction action : actions) {
				String signature = RelocationSelections.physicalEmissionIdentity(action.key());
				Integer id = bySignature.get(signature);
				if(id == null) {
					id = bySignature.size();
					bySignature.put(signature, id);
				}
				result.put(action, id);
			}
			return Collections.unmodifiableMap(result);
		}

		private static Map<PlacementState,Integer> indexPlacementStates(List<Node> decisions) {
			Map<PlacementState,Integer> result = new HashMap<>();
			for(Node decision : decisions)
				for(PlacementState state : decision.legalAlternatives())
					result.computeIfAbsent(state, ignored -> result.size());
			return Collections.unmodifiableMap(result);
		}

		private static Map<RelocationAction,List<DirectSourceDomain>> indexDirectSourceDomains(
			List<Node> decisions, List<RelocationAction> actions,
			Map<PlacementState,Integer> placementStateIds) {
			Map<RelocationAction,List<DirectSourceDomain>> result = new IdentityHashMap<>();
			for(RelocationAction action : actions) {
				List<DirectSourceDomain> sources = new ArrayList<>();
				for(Node source : decisions) {
					if(!source.valueVersion().equals(action.key().sourceValueVersion()))
						continue;
				List<PlacementState> direct = source.legalAlternatives().stream()
					.filter(state -> isDirectRelocationSource(source, action, state)).toList();
					if(!direct.isEmpty()) {
						BitSet directIds = new BitSet();
						for(PlacementState state : direct) {
							Integer id = placementStateIds.get(state);
							if(id == null)
								throw new IllegalStateException(
									"Direct relocation state is outside the exact state universe");
							directIds.set(id);
						}
						sources.add(new DirectSourceDomain(source.key(), direct, directIds));
					}
				}
				result.put(action, List.copyOf(sources));
			}
			return Collections.unmodifiableMap(result);
		}

		private int placementStateId(PlacementState state) {
			Integer id = placementStateIds.get(state);
			if(id == null)
				throw new IllegalStateException("Placement state is outside the exact state universe");
			return id;
		}

		private static Map<CompiledHopKey,Map<Integer,List<RelocationAction>>> indexRelocationOptions(
			List<RelocationAction> actions) {
			Map<CompiledHopKey,Map<Integer,List<RelocationAction>>> result = new IdentityHashMap<>();
			for(RelocationAction action : actions)
				for(var obligation : action.obligations())
					result.computeIfAbsent(obligation.consumer(), ignored -> new LinkedHashMap<>())
						.computeIfAbsent(obligation.inputPosition(), ignored -> new ArrayList<>())
						.add(action);
			Map<CompiledHopKey,Map<Integer,List<RelocationAction>>> copied = new IdentityHashMap<>();
			for(Map.Entry<CompiledHopKey,Map<Integer,List<RelocationAction>>> entry : result.entrySet()) {
				Map<Integer,List<RelocationAction>> byInput = new LinkedHashMap<>();
				for(Map.Entry<Integer,List<RelocationAction>> input : entry.getValue().entrySet())
					byInput.put(input.getKey(), List.copyOf(input.getValue()));
				copied.put(entry.getKey(), Collections.unmodifiableMap(byInput));
			}
			return Collections.unmodifiableMap(copied);
		}

		private static Map<CompiledHopKey,List<PlacementAnalysis.CompiledInputEdgeFact>>
			indexInputEdgesByProducer(PlacementAnalysis analysis) {
			if(analysis == null)
				return Map.of();
			Map<CompiledHopKey,List<PlacementAnalysis.CompiledInputEdgeFact>> mutable =
				new IdentityHashMap<>();
			for(var edge : analysis.compiledInputEdgesInCanonicalOrder())
				mutable.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge);
			Map<CompiledHopKey,List<PlacementAnalysis.CompiledInputEdgeFact>> result =
				new IdentityHashMap<>();
			mutable.forEach((producer, edges) -> result.put(producer, List.copyOf(edges)));
			return Collections.unmodifiableMap(result);
		}

		private static Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>>
			indexFunctionInputsBySource(PlacementAnalysis analysis) {
			if(analysis == null)
				return Map.of();
			Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>> mutable =
				new IdentityHashMap<>();
			for(var fact : analysis.logicalFunctionInputsInCanonicalOrder())
				mutable.computeIfAbsent(fact.sourceArgument(), ignored -> new ArrayList<>()).add(fact);
			Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>> result =
				new IdentityHashMap<>();
			mutable.forEach((source, facts) -> result.put(source, List.copyOf(facts)));
			return Collections.unmodifiableMap(result);
		}
	}

	private record DirectSourceDomain(CompiledHopKey source, List<PlacementState> directStates,
		BitSet directStateIds) { }
	private record SourceRelocationDemand(BitSet physicalOptions, BitSet directStateIds) { }
	private record SourceRelocationBound(BitSet physicalUniverse, int minimum) { }
	private record CandidateBoundRelocationOption(int physicalEmissionId,
		List<DirectSourceDomain> sourceDomains) { }
	private record CandidateBoundRow(BitSet presentInputs, BitSet relocationInputPositions,
		Map<Integer,List<CandidateBoundRelocationOption>> relocationOptionsByInput,
		boolean derivedFedFout, boolean materializesFout) {
		private CandidateBoundRow {
			presentInputs = (BitSet)presentInputs.clone();
			relocationInputPositions = (BitSet)relocationInputPositions.clone();
		}
	}
	private record CompetitiveGroup(DecisionGroup group, List<PlacementState> states) { }
	private record CompetitiveDomains(Map<CompiledHopKey,List<PlacementState>> byNode,
		List<CompetitiveGroup> groups, long product) { }

	/** Exact quotient variables induced by mandatory SAME_PLACEMENT constraints. */
	private record DecisionGroup(List<Node> members, List<PlacementState> legalAlternatives)
		implements Comparable<DecisionGroup> {
		private DecisionGroup {
			members = members.stream().sorted().toList();
			legalAlternatives = legalAlternatives.stream().distinct().sorted().toList();
			if(members.isEmpty() || legalAlternatives.isEmpty())
				throw new IllegalStateException("SAME_PLACEMENT group has no common legal state");
		}
		private void assign(Map<CompiledHopKey,PlacementState> assignment, PlacementState state) {
			for(Node member : members) {
				PlacementState exact = null;
				for(PlacementState candidate : member.legalAlternatives())
					if(candidate.equals(state)) {
						if(exact != null)
							throw new IllegalStateException(
								"SAME_PLACEMENT member has duplicate node-owned state identity: "
									+ member.key());
						exact = candidate;
					}
				if(exact == null)
					throw new IllegalStateException(
						"SAME_PLACEMENT member has no unique node-owned state identity: " + member.key());
				assignment.put(member.key(), exact);
			}
		}
		private void remove(Map<CompiledHopKey,PlacementState> assignment) {
			for(Node member : members)
				assignment.remove(member.key());
		}
		private boolean contains(CompiledHopKey key) {
			for(Node member : members)
				if(member.key() == key)
					return true;
			return false;
		}
		private boolean assigned(Map<CompiledHopKey,PlacementState> assignment) {
			return assignment.containsKey(members.get(0).key());
		}
		@Override public int compareTo(DecisionGroup that) {
			return members.get(0).compareTo(that.members.get(0));
		}
		@Override public boolean equals(Object that) {
			return this == that;
		}
		@Override public int hashCode() {
			return System.identityHashCode(this);
		}
	}

	private static List<DecisionGroup> samePlacementGroups(List<Node> decisions,
		List<Constraint> constraints) {
		Map<CompiledHopKey,Node> nodes = new LinkedHashMap<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node node : decisions) {
			nodes.put(node.key(), node);
			adjacency.put(node.key(), new LinkedHashSet<>());
		}
		for(Constraint constraint : constraints)
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& nodes.containsKey(constraint.left()) && nodes.containsKey(constraint.right()))
				connect(adjacency, constraint.left(), constraint.right());
		List<DecisionGroup> groups = new ArrayList<>();
		for(Set<CompiledHopKey> keys : connectedDecisionSets(adjacency)) {
			List<Node> members = keys.stream().map(nodes::get).sorted().toList();
			List<PlacementState> common = new ArrayList<>(members.get(0).legalAlternatives());
			for(int index = 1; index < members.size(); index++) {
				List<PlacementState> memberAlternatives = members.get(index).legalAlternatives();
				common.removeIf(state -> !memberAlternatives.contains(state));
			}
			groups.add(new DecisionGroup(members, common));
		}
		return groups.stream().sorted().toList();
	}

	/**
	 * Counts relocation actions that every completion of {@code partial} must
	 * select. This is an admissible lower bound: an action is counted only after
	 * a consumer requirement is unavoidable and no selected or remaining source
	 * placement can satisfy the same durable anchor directly.
	 */
	private static int unavoidableRelocationCount(NeutralPlacementGraph graph, List<Node> decisions,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> partial) {
		Map<RelocationDemandKey,Set<String>> unavoidableOptions = new LinkedHashMap<>();
		Set<RelocationDemandKey> avoidableDemands = new LinkedHashSet<>();
		for(RelocationAction action : relocationActions) {
			boolean sourceMayBeDirect = sourceCanAvoidRelocation(decisions, action, partial);
			for(var obligation : action.obligations()) {
				PlacementState selectedConsumer = partial.get(obligation.consumer());
				if(selectedConsumer == null || !selectedConsumer.equals(obligation.requiredPlacement()))
					continue;
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				if(sourceMayBeDirect) {
					avoidableDemands.add(demand);
					unavoidableOptions.remove(demand);
				}
				else if(!avoidableDemands.contains(demand))
					unavoidableOptions.computeIfAbsent(demand, ignored -> new LinkedHashSet<>())
						.add(RelocationSelections.physicalEmissionIdentity(action.key()));
			}
		}

		return disjointAlternativeSetLowerBound(unavoidableOptions.values());
	}

	/**
	 * Every exact demand needs one action, but alternatives for the same demand
	 * must not be counted independently. A greedily selected family of pairwise-
	 * disjoint option sets is an admissible lower bound on distinct emissions.
	 */
	private static <T extends Comparable<? super T>> int disjointAlternativeSetLowerBound(
		Collection<Set<T>> alternatives) {
		List<List<T>> optionSets = new ArrayList<>();
		for(Set<T> options : alternatives) {
			if(options.isEmpty())
				continue;
			List<T> ordered = new ArrayList<>(options);
			Collections.sort(ordered);
			optionSets.add(ordered);
		}
		optionSets.sort((left, right) -> {
			int size = Integer.compare(left.size(), right.size());
			if(size != 0)
				return size;
			for(int index = 0; index < left.size(); index++) {
				int order = left.get(index).compareTo(right.get(index));
				if(order != 0)
					return order;
			}
			return 0;
		});
		Set<T> alreadyCovered = new HashSet<>();
		int count = 0;
		for(List<T> options : optionSets) {
			boolean overlaps = false;
			for(T option : options)
				if(alreadyCovered.contains(option)) {
					overlaps = true;
					break;
				}
			if(overlaps)
				continue;
			count++;
			alreadyCovered.addAll(options);
		}
		return count;
	}

	/** Integer specialization for the exact-search hot path. */
	private static int disjointIntegerAlternativeSetLowerBound(
		Collection<Set<Integer>> alternatives) {
		List<int[]> optionSets = new ArrayList<>();
		for(Set<Integer> options : alternatives) {
			if(options.isEmpty())
				continue;
			int[] ordered = new int[options.size()];
			int index = 0;
			for(int option : options)
				ordered[index++] = option;
			Arrays.sort(ordered);
			optionSets.add(ordered);
		}
		optionSets.sort((left, right) -> {
			int size = Integer.compare(left.length, right.length);
			if(size != 0)
				return size;
			for(int index = 0; index < left.length; index++) {
				int order = Integer.compare(left[index], right[index]);
				if(order != 0)
					return order;
			}
			return 0;
		});
		BitSet alreadyCovered = new BitSet();
		int count = 0;
		for(int[] options : optionSets) {
			boolean overlaps = false;
			for(int option : options)
				if(alreadyCovered.get(option)) {
					overlaps = true;
					break;
				}
			if(overlaps)
				continue;
			count++;
			for(int option : options)
				alreadyCovered.set(option);
		}
		return count;
	}

	/** Allocation-light physical-id specialization for repeated prefix bounds. */
	private static int disjointBitSetAlternativeSetLowerBound(
		Collection<BitSet> alternatives) {
		List<BitSet> optionSets = new ArrayList<>(alternatives.size());
		for(BitSet options : alternatives)
			if(!options.isEmpty())
				optionSets.add(options);
		optionSets.sort((left, right) -> {
			int cardinality = Integer.compare(left.cardinality(), right.cardinality());
			if(cardinality != 0)
				return cardinality;
			int leftBit = left.nextSetBit(0);
			int rightBit = right.nextSetBit(0);
			while(leftBit >= 0 && rightBit >= 0) {
				int comparison = Integer.compare(leftBit, rightBit);
				if(comparison != 0)
					return comparison;
				leftBit = left.nextSetBit(leftBit + 1);
				rightBit = right.nextSetBit(rightBit + 1);
			}
			return Integer.compare(leftBit, rightBit);
		});
		BitSet alreadyCovered = new BitSet();
		int count = 0;
		for(BitSet options : optionSets) {
			if(options.intersects(alreadyCovered))
				continue;
			count++;
			alreadyCovered.or(options);
		}
		return count;
	}

	private static boolean sourceCanAvoidRelocation(List<DirectSourceDomain> sources,
		Map<CompiledHopKey, PlacementState> partial) {
		for(DirectSourceDomain source : sources) {
			PlacementState selected = partial.get(source.source());
			if(selected == null || source.directStates().contains(selected))
				return true;
		}
		return false;
	}

	private static boolean sourceCanAvoidRelocation(List<DirectSourceDomain> sources,
		Map<CompiledHopKey, PlacementState> partial,
		Map<CompiledHopKey,List<PlacementState>> remainingDomains) {
		for(DirectSourceDomain source : sources) {
			PlacementState selected = partial.get(source.source());
			if(selected != null) {
				if(source.directStates().contains(selected))
					return true;
				continue;
			}
			List<PlacementState> possible = remainingDomains.get(source.source());
			if(possible == null)
				return true;
			for(PlacementState candidate : possible)
				if(source.directStates().contains(candidate))
					return true;
		}
		return false;
	}

	private static boolean sourceCanAvoidRelocation(List<Node> decisions,
		RelocationAction action, Map<CompiledHopKey, PlacementState> partial) {
		for(Node source : decisions) {
			if(!source.valueVersion().equals(action.key().sourceValueVersion()))
				continue;
			PlacementState selected = partial.get(source.key());
			if(selected != null) {
				if(isDirectRelocationSource(source, action, selected))
					return true;
			}
			else if(source.legalAlternatives().stream()
				.anyMatch(state -> isDirectRelocationSource(source, action, state)))
				return true;
		}
		return false;
	}

	private static boolean isDirectRelocationSource(Node source, RelocationAction action,
		PlacementState state) {
		return action.directSourcePlacements().contains(state)
			|| state.output() == FederatedOutput.FOUT
				&& Objects.equals(state.fType(), action.key().durableAnchor().fType())
				&& source.anchors().contains(action.key().durableAnchor());
	}

	private static int constraintDegree(List<Constraint> constraints, CompiledHopKey key) {
		int degree = 0;
		for(Constraint constraint : constraints)
			if(constraint.left().equals(key) || constraint.right().equals(key))
				degree++;
		return degree;
	}

	private static int constraintDegree(List<Constraint> constraints, DecisionGroup group) {
		int degree = 0;
		for(Constraint constraint : constraints)
			if(group.contains(constraint.left()) || group.contains(constraint.right()))
				degree++;
		return degree;
	}

	private static boolean canStillBeLegal(List<Constraint> constraints,
		Map<CompiledHopKey, PlacementState> partial) {
		for(Constraint constraint : constraints) {
			PlacementState left = partial.get(constraint.left());
			PlacementState right = partial.get(constraint.right());
			if(left == null || right == null)
				continue;
			if(!NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
				return false;
		}
		return true;
	}

	/**
	 * Exact order of the assignment prefix of {@link #normalizedSignature}. Both
	 * maps contain the same canonical decision keys, so the first differing state
	 * determines the complete plan signature before relocation/candidate suffixes.
	 */
	private static int compareAssignments(Map<CompiledHopKey,PlacementState> left,
		Map<CompiledHopKey,PlacementState> right, List<Node> decisions) {
		if(right == null)
			return -1;
		for(Node node : decisions) {
			PlacementState leftState = left.get(node.key());
			PlacementState rightState = right.get(node.key());
			if(leftState == null || rightState == null)
				throw new IllegalStateException("Cannot compare incomplete exact assignments");
			int comparison = comparePlacementStateCanonical(leftState, rightState);
			if(comparison != 0)
				return comparison;
		}
		return 0;
	}

	/** Exact allocation-free order of {@link PlacementState#normalizedSignature()}. */
	private static int comparePlacementStateCanonical(PlacementState left, PlacementState right) {
		int comparison = left.execType().name().compareTo(right.execType().name());
		if(comparison != 0)
			return comparison;
		comparison = left.output().name().compareTo(right.output().name());
		if(comparison != 0)
			return comparison;
		if(left.fType() == null || right.fType() == null) {
			if(left.fType() != right.fType())
				return left.fType() == null ? -1 : 1;
		}
		else {
			comparison = left.fType().name().compareTo(right.fType().name());
			if(comparison != 0)
				return comparison;
		}
		return left.shapeDependent() == right.shapeDependent() ? 0
			: left.shapeDependent() ? -1 : 1;
	}

	private record ScoredPlan(int emittedFedCount, int foutCount,
		int distinctRelocationCount, Set<RelocationActionKey> relocations,
		List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> choices) { }

	private static PlacementScore score(PlacementAnalysis analysis, NeutralPlacementGraph graph, List<Node> decisions,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> assignment) {
		return score(analysis, graph, decisions, relocationActions, assignment,
			analysis == null ? RelocationSelections.canonicalOrderIndex(relocationActions)
				: analysis.relocationOrderFor(relocationActions),
			analysis == null ? null
				: CandidateSelections.partialReachabilityIndex(analysis, graph, relocationActions));
	}

	private static PlacementScore score(PlacementAnalysis analysis, NeutralPlacementGraph graph, List<Node> decisions,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex relocationOrder,
		CandidateSelections.PartialReachabilityIndex candidateReachability) {
		ScoredPlan plan = scorePlan(analysis, graph, decisions, relocationActions, assignment,
			relocationOrder, candidateReachability);
		return new PlacementScore(plan.emittedFedCount(), plan.foutCount(),
			plan.distinctRelocationCount(), normalizedSignature(assignment, plan.relocations(),
				plan.candidates(), plan.choices()));
	}

	private static ScoredPlan scorePlan(PlacementAnalysis analysis, NeutralPlacementGraph graph,
		List<Node> decisions, List<RelocationAction> relocationActions,
		Map<CompiledHopKey, PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex relocationOrder,
		CandidateSelections.PartialReachabilityIndex candidateReachability) {
		int fed = 0;
		int fout = 0;
		for(Node node : decisions) {
			PlacementState state = assignment.get(node.key());
			if(state == null)
				throw new IllegalStateException("exact selector assignment is missing decision " + node.key());
			if(state.execType() == ExecType.FED)
				fed++;
			if(state.output() == FederatedOutput.FOUT)
				fout++;
		}
		if(analysis == null) {
			List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
				graph, relocationActions, assignment, (demand, action) -> true);
			Set<RelocationActionKey> relocations = new LinkedHashSet<>(RelocationSelections.emittedActions(
				graph, relocationActions, assignment, choices));
			return new ScoredPlan(fed, fout,
				RelocationSelections.physicalEmissionCount(relocations), relocations,
				List.of(), List.of());
		}
		CandidateSelections.Selection exact = CandidateSelections.selectMaterializationMaximal(
			analysis, graph, relocationActions, assignment, relocationOrder,
			candidateReachability);
		Set<RelocationActionKey> relocations = new LinkedHashSet<>(exact.emittedActions());
		return new ScoredPlan(fed, fout,
			Math.addExact(Math.addExact(exact.relocationPhysicalEmissionCount(),
				exact.localMaterializationActionCount()),
				exact.foutMaterializationActionCount()), relocations,
			exact.candidates(), exact.relocationChoices());
	}

	private static Set<RelocationActionKey> selectedRelocations(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment) {
		return selectedRelocations(graph, graph.relocationActions(), assignment);
	}

	private static Set<RelocationActionKey> selectedRelocations(NeutralPlacementGraph graph,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> assignment) {
		List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
			graph, relocationActions, assignment, (demand, action) -> true);
		Set<RelocationActionKey> selected = new TreeSet<>(RelocationSelections.emittedActions(
			graph, relocationActions, assignment, choices));
		return Collections.unmodifiableSet(new LinkedHashSet<>(selected));
	}

	private static void validateRelocationSources(NeutralPlacementGraph graph) {
		Set<CompiledHopKey> decisionKeys = new LinkedHashSet<>();
		Set<ValueVersionKey> decisionValues = new LinkedHashSet<>();
		for(Node node : graph.decisionNodes()) {
			decisionKeys.add(node.key());
			decisionValues.add(node.valueVersion());
		}
		for(RelocationAction action : graph.relocationActions())
			if(action.obligations().stream().anyMatch(o -> decisionKeys.contains(o.consumer()))
				&& !decisionValues.contains(action.key().sourceValueVersion()))
				throw new IllegalStateException("decision relocation source is trace-only");
	}

	/**
	 * Partitions the production-size exact search by the state dependencies that
	 * can affect legality or relocation activation. The first three FedAll score
	 * fields are additive across these components, and every relocation action is
	 * owned by exactly one component. The stable signature remains exact because
	 * globally sorted assignment entries are a deterministic merge of the locally
	 * minimal entries; relocation signatures are compared only after assignments.
	 */
	private static List<SearchComponent> searchComponents(NeutralPlacementGraph graph,
		List<Node> decisions,
		CandidateSelections.PartialReachabilityIndex componentReachability) {
		Map<CompiledHopKey, Node> decisionByKey = new LinkedHashMap<>();
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node decision : decisions) {
			decisionByKey.put(decision.key(), decision);
			adjacency.put(decision.key(), new LinkedHashSet<>());
		}
		for(Constraint constraint : graph.constraints())
			if(isLegalityConstraint(constraint) && decisionByKey.containsKey(constraint.left())
				&& decisionByKey.containsKey(constraint.right()))
				connect(adjacency, constraint.left(), constraint.right());
		for(RelocationAction action : graph.relocationActions())
			connectAll(adjacency, relocationParticipants(decisions, decisionByKey, action));
		for(var action : graph.derivedFoutMaterializationActions())
			if(decisionByKey.containsKey(action.key().producer())
				&& decisionByKey.containsKey(action.key().durableAnchorOwner()))
				connect(adjacency, action.key().producer(), action.key().durableAnchorOwner());
		if(componentReachability != null)
			for(CandidateSelections.ComponentDependency dependency :
				componentReachability.componentDependencies())
				if(decisionByKey.containsKey(dependency.participant())
					&& decisionByKey.containsKey(dependency.consumer()))
					connect(adjacency, dependency.participant(), dependency.consumer());

		List<Set<CompiledHopKey>> memberSets = connectedDecisionSets(adjacency);
		Map<CompiledHopKey, Integer> componentByNode = new HashMap<>();
		for(int i = 0; i < memberSets.size(); i++)
			for(CompiledHopKey key : memberSets.get(i))
				componentByNode.put(key, i);
		List<List<Constraint>> constraints = new ArrayList<>();
		List<List<RelocationAction>> actions = new ArrayList<>();
		for(int i = 0; i < memberSets.size(); i++) {
			constraints.add(new ArrayList<>());
			actions.add(new ArrayList<>());
		}
		for(Constraint constraint : graph.constraints()) {
			Integer left = componentByNode.get(constraint.left());
			Integer right = componentByNode.get(constraint.right());
			if(isLegalityConstraint(constraint) && left != null && right != null) {
				if(!left.equals(right))
					throw new IllegalStateException("legality constraint crosses exact-search components");
				constraints.get(left).add(constraint);
			}
		}
		for(RelocationAction action : graph.relocationActions()) {
			Set<CompiledHopKey> participants = relocationParticipants(decisions, decisionByKey, action);
			if(participants.isEmpty())
				continue;
			Set<Integer> owners = new LinkedHashSet<>();
			for(CompiledHopKey participant : participants)
				owners.add(componentByNode.get(participant));
			if(owners.size() != 1)
				throw new IllegalStateException("relocation action crosses exact-search components: " + action.key());
			actions.get(owners.iterator().next()).add(action);
		}

		List<SearchComponent> result = new ArrayList<>();
		for(int i = 0; i < memberSets.size(); i++) {
			List<Node> componentNodes = memberSets.get(i).stream().map(decisionByKey::get).sorted().toList();
			List<Constraint> componentConstraints = new ArrayList<>(constraints.get(i));
			List<RelocationAction> componentActions = new ArrayList<>(actions.get(i));
			Collections.sort(componentConstraints);
			Collections.sort(componentActions);
			result.add(new SearchComponent(componentNodes, List.copyOf(componentConstraints),
				List.copyOf(componentActions)));
		}
		Collections.sort(result);
		return List.copyOf(result);
	}

	private static boolean isLegalityConstraint(Constraint constraint) {
		return constraint.kind() == ConstraintKind.SAME_PLACEMENT
			|| constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
			|| constraint.kind() == ConstraintKind.SAME_FTYPE
			|| constraint.kind() == ConstraintKind.CONJUNCTIVE;
	}

	private static Set<CompiledHopKey> relocationParticipants(List<Node> decisions,
		Map<CompiledHopKey, Node> decisionByKey, RelocationAction action) {
		Set<CompiledHopKey> participants = new LinkedHashSet<>();
		for(Node decision : decisions)
			if(decision.valueVersion().equals(action.key().sourceValueVersion()))
				participants.add(decision.key());
		for(var obligation : action.obligations())
			if(decisionByKey.containsKey(obligation.consumer()))
				participants.add(obligation.consumer());
		return participants;
	}

	private static void connectAll(Map<CompiledHopKey, Set<CompiledHopKey>> adjacency,
		Set<CompiledHopKey> participants) {
		if(participants.size() < 2)
			return;
		CompiledHopKey first = participants.iterator().next();
		for(CompiledHopKey participant : participants)
			connect(adjacency, first, participant);
	}

	private static List<Set<CompiledHopKey>> connectedDecisionSets(
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency) {
		Set<CompiledHopKey> visited = new HashSet<>();
		List<Set<CompiledHopKey>> result = new ArrayList<>();
		for(CompiledHopKey start : adjacency.keySet()) {
			if(!visited.add(start))
				continue;
			Set<CompiledHopKey> members = new LinkedHashSet<>();
			Deque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.add(start);
			while(!pending.isEmpty()) {
				CompiledHopKey current = pending.removeFirst();
				members.add(current);
				for(CompiledHopKey adjacent : adjacency.get(current))
					if(visited.add(adjacent))
						pending.addLast(adjacent);
			}
			result.add(Set.copyOf(members));
		}
		return result;
	}

	private record SearchComponent(List<Node> nodes, List<Constraint> constraints,
		List<RelocationAction> relocationActions) implements Comparable<SearchComponent> {
		private String identity() {
			return nodes.isEmpty() ? "empty" : nodes.get(0).key().normalizedSignature();
		}

		@Override
		public int compareTo(SearchComponent that) {
			return identity().compareTo(that.identity());
		}
	}

	private static String normalizedSignature(Map<CompiledHopKey, PlacementState> assignment,
		Set<RelocationActionKey> relocations, List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> choices) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((key, state) -> entries.add(key.normalizedSignature() + '=' + state.normalizedSignature()));
		Collections.sort(entries);
		List<String> actions = relocations.stream().map(RelocationActionKey::normalizedSignature).sorted().toList();
		List<String> rows = candidates.stream().map(CandidateSelectionReceipt::normalizedSignature).sorted().toList();
		List<String> decisions = choices.stream().map(RelocationChoiceReceipt::normalizedSignature).sorted().toList();
		String placementAndActions = String.join("|", entries) + "#" + String.join("|", actions);
		return rows.isEmpty() && decisions.isEmpty() ? placementAndActions
			: placementAndActions + "#" + String.join("|", rows)
				+ "#" + String.join("|", decisions);
	}

	private static List<ComponentBound> componentBounds(NeutralPlacementGraph graph,
		CandidateSelections.PartialReachabilityIndex componentReachability) {
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		Map<CompiledHopKey,Node> nodes = new LinkedHashMap<>();
		for(Node node : graph.nodes())
		{
			adjacency.put(node.key(), new LinkedHashSet<>());
			nodes.put(node.key(), node);
		}
		for(Constraint constraint : graph.constraints())
			connect(adjacency, constraint.left(), constraint.right());
		Map<ValueVersionKey, CompiledHopKey> owners = new HashMap<>();
		for(Node node : graph.nodes())
			owners.put(node.valueVersion(), node.key());
		for(RelocationAction action : graph.relocationActions()) {
			CompiledHopKey source = owners.get(action.key().sourceValueVersion());
			for(CompiledHopKey consumer : action.key().compatibleConsumers())
				connect(adjacency, source, consumer);
		}
		if(componentReachability != null)
			for(CandidateSelections.ComponentDependency dependency :
				componentReachability.componentDependencies())
				if(nodes.containsKey(dependency.participant())
					&& nodes.containsKey(dependency.consumer()))
					connect(adjacency, dependency.participant(), dependency.consumer());
		Set<CompiledHopKey> visited = new HashSet<>();
		List<ComponentBound> result = new ArrayList<>();
		for(CompiledHopKey start : adjacency.keySet()) {
			if(!visited.add(start))
				continue;
			Set<CompiledHopKey> members = new LinkedHashSet<>();
			Deque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.add(start);
			while(!pending.isEmpty()) {
				CompiledHopKey current = pending.removeFirst();
				members.add(current);
				for(CompiledHopKey adjacent : adjacency.get(current))
					if(visited.add(adjacent))
						pending.addLast(adjacent);
			}
			result.add(componentBound(graph, componentReachability, members, owners));
		}
		Collections.sort(result);
		return List.copyOf(result);
	}

	private static ComponentBound componentBound(NeutralPlacementGraph graph,
		CandidateSelections.PartialReachabilityIndex componentReachability,
		Set<CompiledHopKey> members, Map<ValueVersionKey, CompiledHopKey> owners) {
		Set<String> normalizedNodes = new TreeSet<>();
		members.forEach(key -> normalizedNodes.add(key.normalizedSignature()));
		Set<String> edges = new TreeSet<>();
		for(Constraint constraint : graph.constraints())
			if(members.contains(constraint.left()) && members.contains(constraint.right()))
				edges.add("constraint:" + constraint.normalizedSignature());
		for(RelocationAction action : graph.relocationActions()) {
			CompiledHopKey source = owners.get(action.key().sourceValueVersion());
			for(CompiledHopKey consumer : action.key().compatibleConsumers())
				if(members.contains(source) && members.contains(consumer))
					edges.add("relocation:" + source.normalizedSignature() + "->" + consumer.normalizedSignature()
						+ ':' + action.key().normalizedSignature());
		}
		if(componentReachability != null)
			for(CandidateSelections.ComponentDependency dependency :
				componentReachability.componentDependencies())
				if(members.contains(dependency.participant())
					&& members.contains(dependency.consumer()))
					edges.add("candidate:" + dependency.participant().normalizedSignature() + "->"
						+ dependency.consumer().normalizedSignature());
		int maxFed = 0;
		int maxFout = 0;
		for(Node node : graph.nodes())
			if(members.contains(node.key())) {
				if(node.emittedWork() && node.legalAlternatives().stream().anyMatch(state -> state.execType() == ExecType.FED))
					maxFed++;
				if(node.legalAlternatives().stream().anyMatch(state -> state.output() == FederatedOutput.FOUT))
					maxFout++;
			}
		String nodeSignature = String.join("|", normalizedNodes);
		String derivation = "nodewise-admissible:maxFed=" + maxFed + ",maxFout=" + maxFout
			+ ",minRelocations=0,nodes=" + nodeSignature;
		return new ComponentBound(sha256(nodeSignature), normalizedNodes, members.size(), edges.size(),
			new PlacementScore(maxFed, maxFout, 0, nodeSignature), derivation);
	}

	private static void connect(Map<CompiledHopKey, Set<CompiledHopKey>> adjacency,
		CompiledHopKey left, CompiledHopKey right) {
		if(left == null || right == null)
			throw new IllegalArgumentException("component coupling references an unknown graph node");
		adjacency.get(left).add(right);
		adjacency.get(right).add(left);
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for(byte value : digest)
				result.append(String.format("%02x", value));
			return result.toString();
		}
		catch(Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
