/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.ComponentBound;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exhaustive exact selector for the planner-neutral FedAll objective. */
public final class ExactPlacementSelector implements PlacementSelector {
	private static final int BRANCH_AND_BOUND_THRESHOLD = 16;

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
		validateRelocationSources(graph);
		List<Node> decisions = new ArrayList<>(graph.decisionNodes());
		validateDecisionAlternatives(decisions);
		boolean branchAndBound = decisions.stream()
			.filter(node -> node.legalAlternatives().size() > 1).count() > BRANCH_AND_BOUND_THRESHOLD;
		SearchResult result = branchAndBound
			? solveIndependentComponents(analysis, graph, decisions)
			: solve(analysis, graph, decisions, graph.constraints(), graph.relocationActions(), false);
		if(result.assignment() == null)
			throw new IllegalStateException("neutral placement graph has no legal total assignment");
		if(result.assignment().size() != decisions.size() || !canStillBeLegal(graph.constraints(), result.assignment()))
			throw new IllegalStateException("exact component solver produced an incomplete or illegal assignment");
		List<ComponentBound> componentBounds = componentBounds(analysis, graph);
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
		if(analysis == null) {
			List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
				graph, graph.relocationActions(), result.assignment(), (demand, action) -> true);
			return new PlacementSelection(result.assignment(), List.of(), choices,
				selectedRelocations(graph, result.assignment()), result.score(), certificate);
		}
		CandidateSelections.Selection exact = CandidateSelections.selectMaterializationMaximal(
			analysis, graph.relocationActions(), result.assignment());
		return new PlacementSelection(result.assignment(), exact.candidates(), exact.relocationChoices(),
			new LinkedHashSet<>(exact.emittedActions()), result.score(), certificate);
	}

	private static SearchResult solveIndependentComponents(PlacementAnalysis analysis,
		NeutralPlacementGraph graph, List<Node> decisions) {
		List<SearchComponent> components = searchComponents(analysis, graph, decisions);
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

	private record SearchResult(Map<CompiledHopKey, PlacementState> assignment, PlacementScore score,
		long explored, long pruned) { }

	private static final class Search {
		private final PlacementAnalysis analysis;
		private final NeutralPlacementGraph graph;
		private final List<Node> decisions;
		private final List<Constraint> constraints;
		private final List<RelocationAction> relocationActions;
		private final List<DecisionGroup> groups;
		private final Map<CompiledHopKey, PlacementState> current = new LinkedHashMap<>();
		private final boolean branchAndBound;
		private Map<CompiledHopKey, PlacementState> bestAssignment;
		private PlacementScore bestScore;
		private long explored;
		private long pruned;

		private Search(PlacementAnalysis analysis, NeutralPlacementGraph graph,
			List<Node> decisions, List<Constraint> constraints,
			List<RelocationAction> relocationActions, boolean branchAndBound) {
			this.analysis = analysis;
			this.graph = graph;
			this.decisions = List.copyOf(decisions);
			this.constraints = List.copyOf(constraints);
			this.relocationActions = List.copyOf(relocationActions);
			this.branchAndBound = branchAndBound;
			List<DecisionGroup> equalityGroups = samePlacementGroups(decisions, constraints);
			if(branchAndBound) {
				for(DecisionGroup group : equalityGroups)
					if(group.legalAlternatives().size() == 1)
						group.assign(current, group.legalAlternatives().get(0));
				if(!canStillBeLegal(constraints, current)
					|| analysis != null && !CandidateSelections.canStillBeReachable(
						analysis, relocationActions, current))
					throw new IllegalStateException("neutral placement graph has incompatible fixed states");
				groups = equalityGroups.stream().filter(group -> group.legalAlternatives().size() > 1)
					.sorted((left, right) -> {
						int degree = Integer.compare(constraintDegree(constraints, right),
							constraintDegree(constraints, left));
						return degree != 0 ? degree : left.compareTo(right);
					}).toList();
			}
			else {
				groups = List.copyOf(equalityGroups);
			}
		}

		private void solve() {
			if(branchAndBound)
				enumerateWithPropagation();
			else
				enumerateCartesian(0);
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
				if(canStillBeLegal(constraints, current)
					&& (analysis == null || CandidateSelections.canStillBeReachable(
						analysis, relocationActions, current)))
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
			List<DecisionGroup> propagated = new ArrayList<>();
			try {
				Map<DecisionGroup,List<PlacementState>> domains;
				while(true) {
					domains = feasibleDomains();
					if(domains == null) {
						pruned++;
						return;
					}
					DecisionGroup singleton = domains.entrySet().stream()
						.filter(entry -> entry.getValue().size() == 1)
						.map(Map.Entry::getKey).min(DecisionGroup::compareTo).orElse(null);
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
					int domain = Integer.compare(remainingDomains.get(left).size(),
						remainingDomains.get(right).size());
					if(domain != 0)
						return domain;
					int degree = Integer.compare(constraintDegree(constraints, right),
						constraintDegree(constraints, left));
					return degree != 0 ? degree : left.compareTo(right);
				}).orElseThrow();
				List<PlacementState> alternatives = new ArrayList<>(remainingDomains.get(selected));
				alternatives.sort((left, right) -> {
					int fed = Boolean.compare(right.execType() == ExecType.FED,
						left.execType() == ExecType.FED);
					if(fed != 0)
						return fed;
					int fout = Boolean.compare(right.output() == FederatedOutput.FOUT,
						left.output() == FederatedOutput.FOUT);
					return fout != 0 ? fout : left.compareTo(right);
				});
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

		/** Returns null when at least one unassigned group has no legal completion. */
		private Map<DecisionGroup,List<PlacementState>> feasibleDomains() {
			Map<DecisionGroup,List<PlacementState>> domains = new LinkedHashMap<>();
			for(DecisionGroup group : groups) {
				if(group.assigned(current))
					continue;
				List<PlacementState> feasible = new ArrayList<>();
				for(PlacementState state : group.legalAlternatives()) {
					group.assign(current, state);
					boolean completable = canStillBeLegal(constraints, current)
						&& (analysis == null || CandidateSelections.canStillBeReachable(
							analysis, relocationActions, current));
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
			PlacementScore candidate;
			try {
				candidate = score(analysis, graph, decisions, relocationActions, current);
			}
			catch(IllegalArgumentException | IllegalStateException unavailable) {
				pruned++;
				return;
			}
			explored++;
			if(bestScore == null || candidate.compareTo(bestScore) > 0) {
				bestScore = candidate;
				bestAssignment = new LinkedHashMap<>(current);
			}
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
			// Candidate-aware scoring can replace a graph relocation with a native
			// ABSENT_LOCAL candidate row, so a placement-only positive relocation lower
			// bound is not generally admissible. Zero, however, is universal. Once the
			// incumbent already emits zero relocations, the assignment-prefix signature
			// is an exact safe tie bound: candidate/action suffixes cannot make a larger
			// assignment prefix lexicographically smaller. This avoids enumerating every
			// equal FED/FOUT FType combination without closing any legal alternative.
			if(analysis != null)
				return bestScore.distinctRelocationCount() == 0
					&& optimisticSignature(remainingDomains)
						.compareTo(bestScore.normalizedSignature()) >= 0;
			int relocationLowerBound = unavoidableRelocationCount(graph, decisions, relocationActions, current);
			if(relocationLowerBound > bestScore.distinctRelocationCount())
				return true;
			if(relocationLowerBound < bestScore.distinctRelocationCount())
				return false;
			return optimisticSignature(remainingDomains)
				.compareTo(bestScore.normalizedSignature()) >= 0;
		}

		/**
		 * Returns a lexicographic lower bound for every completion of the current
		 * partial assignment. Candidate legality and relocation actions are omitted
		 * deliberately: choosing the smallest state for each remaining node and an
		 * empty relocation suffix can only make the signature smaller.
		 */
		private String optimisticSignature(
			Map<DecisionGroup,List<PlacementState>> remainingDomains) {
			Map<CompiledHopKey, PlacementState> optimistic = new LinkedHashMap<>(current);
			for(Map.Entry<DecisionGroup,List<PlacementState>> entry : remainingDomains.entrySet()) {
				DecisionGroup group = entry.getKey();
				PlacementState minimum = Collections.min(entry.getValue());
				group.assign(optimistic, minimum);
			}
			return normalizedSignature(optimistic, Set.of(), List.of(), List.of());
		}
	}

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
			for(Node member : members)
				assignment.put(member.key(), state);
		}
		private void remove(Map<CompiledHopKey,PlacementState> assignment) {
			for(Node member : members)
				assignment.remove(member.key());
		}
		private boolean contains(CompiledHopKey key) {
			return members.stream().anyMatch(member -> member.key() == key);
		}
		private boolean assigned(Map<CompiledHopKey,PlacementState> assignment) {
			return assignment.containsKey(members.get(0).key());
		}
		@Override public int compareTo(DecisionGroup that) {
			return members.get(0).compareTo(that.members.get(0));
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

		// Every selected exact demand needs one action, but alternative actions for the
		// same demand must not be counted independently. A set of pairwise-disjoint
		// alternative sets is an admissible lower bound on the distinct emitted actions.
		List<Set<String>> optionSets = unavoidableOptions.values().stream()
			.filter(options -> !options.isEmpty())
			.sorted((left, right) -> {
				int size = Integer.compare(left.size(), right.size());
				if(size != 0)
					return size;
				String leftSignature = left.stream().sorted().reduce((a, b) -> a + "|" + b).orElse("");
				String rightSignature = right.stream().sorted().reduce((a, b) -> a + "|" + b).orElse("");
				return leftSignature.compareTo(rightSignature);
			}).toList();
		Set<String> alreadyCovered = new LinkedHashSet<>();
		int count = 0;
		for(Set<String> options : optionSets) {
			if(options.stream().anyMatch(alreadyCovered::contains))
				continue;
			count++;
			alreadyCovered.addAll(options);
		}
		return count;
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
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT && !left.equals(right))
				return false;
			if(constraint.kind() == ConstraintKind.SAME_FTYPE && !Objects.equals(left.fType(), right.fType()))
				return false;
			if(constraint.kind() == ConstraintKind.CONJUNCTIVE && violatesConjunctive(constraint, left, right))
				return false;
		}
		return true;
	}

	private static boolean violatesConjunctive(Constraint constraint, PlacementState left, PlacementState right) {
		String prefix = "forbid-pair:";
		if(constraint.evidence().startsWith(prefix)) {
			String[] pair = constraint.evidence().substring(prefix.length()).split("=>", -1);
			if(pair.length != 2)
				throw new IllegalArgumentException("invalid conjunctive forbid-pair evidence: " + constraint.evidence());
			return left.normalizedSignature().equals(pair[0]) && right.normalizedSignature().equals(pair[1]);
		}
		return right.output() == FederatedOutput.FOUT
			&& (left.output() != FederatedOutput.FOUT || !Objects.equals(left.fType(), right.fType()));
	}

	private static PlacementScore score(PlacementAnalysis analysis, NeutralPlacementGraph graph, List<Node> decisions,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> assignment) {
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
			return new PlacementScore(fed, fout,
				RelocationSelections.physicalEmissionCount(relocations),
				normalizedSignature(assignment, relocations, List.of(), choices));
		}
		CandidateSelections.Selection exact = CandidateSelections.selectMaterializationMaximal(
			analysis, relocationActions, assignment);
		Set<RelocationActionKey> relocations = new LinkedHashSet<>(exact.emittedActions());
		return new PlacementScore(fed, fout,
			RelocationSelections.physicalEmissionCount(relocations), normalizedSignature(
			assignment, relocations, exact.candidates(), exact.relocationChoices()));
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
	private static List<SearchComponent> searchComponents(PlacementAnalysis analysis,
		NeutralPlacementGraph graph, List<Node> decisions) {
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
		for(CandidateDependencyEdge dependency : candidateDependencyEdges(analysis, decisionByKey))
			connect(adjacency, dependency.producer(), dependency.consumer());

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

	private record CandidateDependencyEdge(CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition) implements Comparable<CandidateDependencyEdge> {
		@Override public int compareTo(CandidateDependencyEdge that) {
			int producerOrder = producer.compareTo(that.producer);
			if(producerOrder != 0)
				return producerOrder;
			int consumerOrder = consumer.compareTo(that.consumer);
			return consumerOrder != 0 ? consumerOrder : Integer.compare(inputPosition, that.inputPosition);
		}
	}

	private static List<CandidateDependencyEdge> candidateDependencyEdges(PlacementAnalysis analysis,
		Map<CompiledHopKey,Node> nodes) {
		if(analysis == null)
			return List.of();
		Set<CandidateDependencyEdge> dependencies = new TreeSet<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			Node consumer = nodes.get(fact.key().parentOccurrence());
			if(consumer == null || fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| fact.allowedEmissionFacts().stream().noneMatch(emission ->
					consumer.legalAlternatives().contains(emission.emissionState().placementState())))
				continue;
			for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
				if(!fact.key().orderedInputs().get(position).present())
					continue;
				final int inputPosition = position;
				List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
					.compiledInputEdgesInCanonicalOrder().stream()
					.filter(edge -> edge.consumer() == fact.key().parentOccurrence()
						&& edge.inputPosition() == inputPosition).toList();
				if(edges.size() > 1)
					throw new IllegalStateException("Candidate physical input edge is ambiguous while "
						+ "constructing exact-search components: consumer="
						+ fact.key().parentOccurrence().normalizedSignature() + " input=" + inputPosition);
				if(edges.size() == 1 && nodes.containsKey(edges.get(0).producer()))
					dependencies.add(new CandidateDependencyEdge(edges.get(0).producer(),
						fact.key().parentOccurrence(), inputPosition));
			}
		}
		return List.copyOf(dependencies);
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
		return String.join("|", entries) + "#" + String.join("|", actions)
			+ "#" + String.join("|", rows) + "#" + String.join("|", decisions);
	}

	private static List<ComponentBound> componentBounds(PlacementAnalysis analysis,
		NeutralPlacementGraph graph) {
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
		for(CandidateDependencyEdge dependency : candidateDependencyEdges(analysis, nodes))
			connect(adjacency, dependency.producer(), dependency.consumer());
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
			result.add(componentBound(analysis, graph, members, owners));
		}
		Collections.sort(result);
		return List.copyOf(result);
	}

	private static ComponentBound componentBound(PlacementAnalysis analysis, NeutralPlacementGraph graph,
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
		Map<CompiledHopKey,Node> nodes = new LinkedHashMap<>();
		for(Node node : graph.nodes())
			nodes.put(node.key(), node);
		for(CandidateDependencyEdge dependency : candidateDependencyEdges(analysis, nodes))
			if(members.contains(dependency.producer()) && members.contains(dependency.consumer()))
				edges.add("candidate:" + dependency.producer().normalizedSignature() + "->"
					+ dependency.consumer().normalizedSignature() + '@' + dependency.inputPosition());
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
