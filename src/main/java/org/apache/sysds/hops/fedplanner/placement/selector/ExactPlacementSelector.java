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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.ComponentBound;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exhaustive exact selector for the planner-neutral FedAll objective. */
public final class ExactPlacementSelector implements PlacementSelector {
	private static final int BRANCH_AND_BOUND_THRESHOLD = 16;

	@Override
	public PlacementSelection select(NeutralPlacementGraph graph) {
		Objects.requireNonNull(graph, "graph");
		validateRelocationSources(graph);
		List<Node> decisions = new ArrayList<>(graph.decisionNodes());
		validateDecisionAlternatives(decisions);
		boolean branchAndBound = decisions.stream()
			.filter(node -> node.legalAlternatives().size() > 1).count() > BRANCH_AND_BOUND_THRESHOLD;
		SearchResult result = branchAndBound
			? solveIndependentComponents(graph, decisions)
			: solve(graph, decisions, graph.constraints(), graph.relocationActions(), false);
		if(result.assignment() == null)
			throw new IllegalStateException("neutral placement graph has no legal total assignment");
		if(result.assignment().size() != decisions.size() || !canStillBeLegal(graph.constraints(), result.assignment()))
			throw new IllegalStateException("exact component solver produced an incomplete or illegal assignment");
		List<ComponentBound> componentBounds = componentBounds(graph);
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
		return new PlacementSelection(result.assignment(), selectedRelocations(graph, result.assignment()),
			result.score(), certificate);
	}

	private static SearchResult solveIndependentComponents(NeutralPlacementGraph graph, List<Node> decisions) {
		List<SearchComponent> components = searchComponents(graph, decisions);
		Map<CompiledHopKey, PlacementState> assignment = new LinkedHashMap<>();
		long explored = 0;
		long pruned = 0;
		for(SearchComponent component : components) {
			SearchResult result = solve(graph, component.nodes(), component.constraints(),
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
		PlacementScore score = score(graph, decisions, graph.relocationActions(), assignment);
		return new SearchResult(Map.copyOf(assignment), score, explored, pruned);
	}

	private static SearchResult solve(NeutralPlacementGraph graph, List<Node> decisions,
		List<Constraint> constraints, List<RelocationAction> relocationActions, boolean branchAndBound) {
		Search search = new Search(graph, decisions, constraints, relocationActions, branchAndBound);
		search.enumerate(0);
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
		private final NeutralPlacementGraph graph;
		private final List<Node> decisions;
		private final List<Constraint> constraints;
		private final List<RelocationAction> relocationActions;
		private final List<Node> nodes;
		private final Map<CompiledHopKey, PlacementState> current = new LinkedHashMap<>();
		private final boolean branchAndBound;
		private Map<CompiledHopKey, PlacementState> bestAssignment;
		private PlacementScore bestScore;
		private long explored;
		private long pruned;

		private Search(NeutralPlacementGraph graph, List<Node> decisions, List<Constraint> constraints,
			List<RelocationAction> relocationActions, boolean branchAndBound) {
			this.graph = graph;
			this.decisions = List.copyOf(decisions);
			this.constraints = List.copyOf(constraints);
			this.relocationActions = List.copyOf(relocationActions);
			this.branchAndBound = branchAndBound;
			if(branchAndBound) {
				for(Node node : decisions)
					if(node.legalAlternatives().size() == 1)
						current.put(node.key(), node.legalAlternatives().get(0));
				if(!canStillBeLegal(constraints, current))
					throw new IllegalStateException("neutral placement graph has incompatible fixed states");
				nodes = decisions.stream().filter(node -> node.legalAlternatives().size() > 1)
					.sorted((left, right) -> {
						int degree = Integer.compare(constraintDegree(constraints, right.key()),
							constraintDegree(constraints, left.key()));
						return degree != 0 ? degree : left.compareTo(right);
					}).toList();
			}
			else {
				Collections.sort(decisions);
				nodes = List.copyOf(decisions);
			}
		}

		private void enumerate(int index) {
			if(branchAndBound && bestScore != null && cannotBeatIncumbent(index)) {
				pruned++;
				return;
			}
			if(index == nodes.size()) {
				explored++;
				PlacementScore candidate = score(graph, decisions, relocationActions, current);
				if(bestScore == null || candidate.compareTo(bestScore) > 0) {
					bestScore = candidate;
					bestAssignment = new LinkedHashMap<>(current);
				}
				return;
			}
			Node node = nodes.get(index);
			List<PlacementState> alternatives = new ArrayList<>(node.legalAlternatives());
			if(branchAndBound)
				alternatives.sort((left, right) -> {
					int fed = Boolean.compare(right.execType() == ExecType.FED, left.execType() == ExecType.FED);
					if(fed != 0) return fed;
					int fout = Boolean.compare(right.output() == FederatedOutput.FOUT,
						left.output() == FederatedOutput.FOUT);
					return fout != 0 ? fout : left.compareTo(right);
				});
			else
				Collections.sort(alternatives);
			for(PlacementState state : alternatives) {
				current.put(node.key(), state);
				if(canStillBeLegal(constraints, current))
					enumerate(index + 1);
				else
					pruned++;
			}
			current.remove(node.key());
		}

		private boolean cannotBeatIncumbent(int index) {
			int fed = 0;
			int fout = 0;
			for(PlacementState state : current.values()) {
				if(state.execType() == ExecType.FED) fed++;
				if(state.output() == FederatedOutput.FOUT) fout++;
			}
			for(int i = index; i < nodes.size(); i++) {
				List<PlacementState> alternatives = nodes.get(i).legalAlternatives();
				if(alternatives.stream().anyMatch(state -> state.execType() == ExecType.FED)) fed++;
				if(alternatives.stream().anyMatch(state -> state.output() == FederatedOutput.FOUT)) fout++;
			}
			if(fed < bestScore.emittedFedCount())
				return true;
			if(fed > bestScore.emittedFedCount())
				return false;
			if(fout < bestScore.foutCount())
				return true;
			if(fout > bestScore.foutCount())
				return false;
			int relocationLowerBound = unavoidableRelocationCount(graph, decisions, relocationActions, current);
			if(relocationLowerBound > bestScore.distinctRelocationCount())
				return true;
			if(relocationLowerBound < bestScore.distinctRelocationCount())
				return false;
			return optimisticSignature(index).compareTo(bestScore.normalizedSignature()) >= 0;
		}

		/**
		 * Returns a lexicographic lower bound for every completion of the current
		 * partial assignment. Candidate legality and relocation actions are omitted
		 * deliberately: choosing the smallest state for each remaining node and an
		 * empty relocation suffix can only make the signature smaller.
		 */
		private String optimisticSignature(int index) {
			Map<CompiledHopKey, PlacementState> optimistic = new LinkedHashMap<>(current);
			for(int i = index; i < nodes.size(); i++)
				optimistic.put(nodes.get(i).key(), Collections.min(nodes.get(i).legalAlternatives()));
			return normalizedSignature(optimistic, Set.of());
		}
	}

	/**
	 * Counts relocation actions that every completion of {@code partial} must
	 * select. This is an admissible lower bound: an action is counted only after
	 * a consumer requirement is unavoidable and no selected or remaining source
	 * placement can satisfy the same durable anchor directly.
	 */
	private static int unavoidableRelocationCount(NeutralPlacementGraph graph, List<Node> decisions,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> partial) {
		int count = 0;
		for(RelocationAction action : relocationActions)
			if(relocationRequirementIsUnavoidable(graph, action, partial)
				&& !sourceCanAvoidRelocation(decisions, action, partial))
				count++;
		return count;
	}

	private static boolean relocationRequirementIsUnavoidable(NeutralPlacementGraph graph,
		RelocationAction action, Map<CompiledHopKey, PlacementState> partial) {
		for(var obligation : action.obligations()) {
			Node consumer = graph.node(obligation.consumer()).orElseThrow();
			if(!consumer.emittedWork())
				continue;
			PlacementState selected = partial.get(consumer.key());
			if(selected != null) {
				if(selected.equals(obligation.requiredPlacement()))
					return true;
				continue;
			}
			Set<PlacementState> triggeringStates = new LinkedHashSet<>();
			for(var candidate : action.obligations())
				if(candidate.consumer().equals(consumer.key()))
					triggeringStates.add(candidate.requiredPlacement());
			if(triggeringStates.containsAll(consumer.legalAlternatives()))
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

	private static PlacementScore score(NeutralPlacementGraph graph, List<Node> decisions,
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
		Set<RelocationActionKey> relocations = selectedRelocations(graph, relocationActions, assignment);
		return new PlacementScore(fed, fout, relocations.size(), normalizedSignature(assignment, relocations));
	}

	private static Set<RelocationActionKey> selectedRelocations(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment) {
		return selectedRelocations(graph, graph.relocationActions(), assignment);
	}

	private static Set<RelocationActionKey> selectedRelocations(NeutralPlacementGraph graph,
		List<RelocationAction> relocationActions, Map<CompiledHopKey, PlacementState> assignment) {
		Set<RelocationActionKey> selected = new TreeSet<>();
		for(RelocationAction action : relocationActions)
			if(graph.isRelocationActive(action, assignment))
				selected.add(action.key());
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
	private static List<SearchComponent> searchComponents(NeutralPlacementGraph graph, List<Node> decisions) {
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

	private static String normalizedSignature(Map<CompiledHopKey, PlacementState> assignment,
		Set<RelocationActionKey> relocations) {
		List<String> entries = new ArrayList<>();
		assignment.forEach((key, state) -> entries.add(key.normalizedSignature() + '=' + state.normalizedSignature()));
		Collections.sort(entries);
		List<String> actions = relocations.stream().map(RelocationActionKey::normalizedSignature).sorted().toList();
		return String.join("|", entries) + "#" + String.join("|", actions);
	}

	private static List<ComponentBound> componentBounds(NeutralPlacementGraph graph) {
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node node : graph.nodes())
			adjacency.put(node.key(), new LinkedHashSet<>());
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
			result.add(componentBound(graph, members, owners));
		}
		Collections.sort(result);
		return List.copyOf(result);
	}

	private static ComponentBound componentBound(NeutralPlacementGraph graph, Set<CompiledHopKey> members,
		Map<ValueVersionKey, CompiledHopKey> owners) {
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
