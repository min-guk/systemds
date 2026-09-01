/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement.selector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.OccurrenceExecutionFrequencyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.ComponentBound;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/**
 * Deterministic FedAll-style policy selection that stops at the first complete legal assignment.
 *
 * <p>The selector quotients mandatory SAME_PLACEMENT decisions, applies binary arc consistency,
 * and commits policy-ranked states with localized propagation. It backtracks only when a local
 * choice has no complete candidate-reachable continuation; unlike {@link ExactPlacementSelector},
 * it never continues after finding one feasible plan to prove a global policy optimum.</p>
 */
public final class PolicyFirstFeasiblePlacementSelector
	implements PlacementSelector, PlacementAnalysisSelector {
	private static final Comparator<PlacementState> POLICY_ORDER = Comparator
		.comparingInt(PolicyFirstFeasiblePlacementSelector::policyRank)
		.thenComparing(PlacementState::normalizedSignature);
	private final ToDoubleFunction<CompiledHopKey> executionWeightOverride;

	public PolicyFirstFeasiblePlacementSelector() {
		this(null);
	}

	/** Package-private deterministic frequency seam for selector contract tests. */
	PolicyFirstFeasiblePlacementSelector(ToDoubleFunction<CompiledHopKey> executionWeightOverride) {
		this.executionWeightOverride = executionWeightOverride;
	}

	@Override
	public PlacementSelection select(NeutralPlacementGraph graph) {
		return select(null, graph);
	}

	@Override
	public PlacementSelection select(PlacementAnalysis analysis, NeutralPlacementGraph graph) {
		Objects.requireNonNull(graph, "graph");
		PlacementAnalysis candidateAnalysis = analysis != null
			&& !analysis.candidateRuleFacts().orderedFacts().isEmpty() ? analysis : null;
		CandidateSelections.PartialReachabilityIndex reachability = candidateAnalysis == null
			? null : CandidateSelections.partialReachabilityIndex(candidateAnalysis, graph,
				graph.relocationActions());
		RelocationSelections.CanonicalOrderIndex relocationOrder = candidateAnalysis == null
			? RelocationSelections.canonicalOrderIndex(graph.relocationActions())
			: candidateAnalysis.relocationOrderFor(graph.relocationActions());
		Map<CompiledHopKey,PlacementState> assignment = new IdentityHashMap<>();
		long pruned = 0;
		int maxDepth = 0;
		for(PolicyComponent component : policyComponents(candidateAnalysis, graph)) {
			Solver solver = new Solver(candidateAnalysis, graph, component.nodes(),
				component.constraints(), component.relocationActions(), reachability,
				executionWeightOverride);
			Map<CompiledHopKey,PlacementState> selected = solver.solve();
			for(Map.Entry<CompiledHopKey,PlacementState> entry : selected.entrySet())
				if(assignment.put(entry.getKey(), entry.getValue()) != null)
					throw new IllegalStateException(
						"placement decision belongs to multiple policy components");
			pruned = Math.addExact(pruned, solver.pruned);
			maxDepth = Math.max(maxDepth, solver.maxDepth);
		}
		ScoredPlan plan = scoreComplete(candidateAnalysis, graph, assignment,
			relocationOrder, reachability);
		PlacementScore score = new PlacementScore(plan.fedCount(), plan.foutCount(),
			plan.physicalMovementCount(), normalizedSignature(plan));
		List<ComponentBound> bounds = policyBounds(graph, score);
		PlacementCertificate certificate = new PlacementCertificate(score, score,
			1, pruned, sha256(score.normalizedSignature()),
			sha256(graph.normalizedSignature()), graph.nodes().size(), graph.constraints().size(),
			bounds.size(), maxDepth, bounds,
			"deterministic-component-first-feasible-with-localized-arc-consistency",
			"policy", -1L, TerminationReason.POLICY_FEASIBLE);
		return new PlacementSelection(plan.assignment(), plan.candidates(), plan.choices(),
			new LinkedHashSet<>(plan.relocations()), score, certificate);
	}

	private static int policyRank(PlacementState state) {
		if(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT)
			return 0;
		if(state.execType() == ExecType.FED)
			return 1;
		if(state.output() == FederatedOutput.FOUT)
			return 2;
		return 3;
	}

	private static ScoredPlan scoreComplete(PlacementAnalysis analysis,
		NeutralPlacementGraph graph, Map<CompiledHopKey,PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex relocationOrder,
		CandidateSelections.PartialReachabilityIndex reachability) {
		if(assignment.size() != graph.decisionNodes().size())
			throw new IllegalStateException("policy component merge produced an incomplete assignment");
		for(Constraint constraint : graph.constraints()) {
			PlacementState left = assignment.get(constraint.left());
			PlacementState right = assignment.get(constraint.right());
			if(left != null && right != null
				&& !NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
				throw new IllegalStateException("policy component merge violated a graph constraint");
		}
		List<CandidateSelectionReceipt> candidates;
		List<RelocationChoiceReceipt> choices;
		List<RelocationActionKey> relocations;
		int movement;
		if(analysis == null) {
			candidates = List.of();
			choices = RelocationSelections.selectCanonical(graph, graph.relocationActions(),
				assignment, (demand, action) -> true);
			relocations = RelocationSelections.emittedActions(graph, graph.relocationActions(),
				assignment, choices);
			movement = RelocationSelections.physicalEmissionCount(relocations);
		}
		else {
			CandidateSelections.Selection selected = CandidateSelections.selectMaterializationMaximal(
				analysis, graph, graph.relocationActions(), assignment, relocationOrder, reachability);
			candidates = selected.candidates();
			choices = selected.relocationChoices();
			relocations = selected.emittedActions();
			movement = Math.addExact(Math.addExact(selected.relocationPhysicalEmissionCount(),
				selected.localMaterializationActionCount()),
				selected.foutMaterializationActionCount());
		}
		int fed = 0;
		int fout = 0;
		for(Node node : graph.decisionNodes()) {
			PlacementState state = assignment.get(node.key());
			if(state.execType() == ExecType.FED)
				fed++;
			if(state.output() == FederatedOutput.FOUT)
				fout++;
		}
		return new ScoredPlan(Map.copyOf(assignment), List.copyOf(candidates),
			List.copyOf(choices), List.copyOf(relocations), fed, fout, movement);
	}

	/**
	 * Splits the policy CSP at every exact physical-independence boundary before
	 * committing policy states. Legality edges, candidate input dependencies,
	 * relocation sharing, and derived-FOUT anchor ownership all keep their
	 * participants in one component. Consequently, accepting the first feasible
	 * assignment of one component cannot invalidate another component and avoids
	 * constructing their Cartesian product.
	 */
	private static List<PolicyComponent> policyComponents(PlacementAnalysis analysis,
		NeutralPlacementGraph graph) {
		List<Node> decisions = graph.decisionNodes().stream().sorted().toList();
		Map<CompiledHopKey,Node> decisionByKey = new LinkedHashMap<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node decision : decisions) {
			decisionByKey.put(decision.key(), decision);
			adjacency.put(decision.key(), new LinkedHashSet<>());
		}
		for(Constraint constraint : graph.constraints())
			if(isLegalityConstraint(constraint)
				&& decisionByKey.containsKey(constraint.left())
				&& decisionByKey.containsKey(constraint.right()))
				connect(adjacency, constraint.left(), constraint.right());
		for(RelocationAction action : graph.relocationActions())
			connectAll(adjacency, relocationParticipants(decisions, decisionByKey, action));
		for(DerivedFoutMaterializationAction action : graph.derivedFoutMaterializationActions())
			if(decisionByKey.containsKey(action.key().producer())
				&& decisionByKey.containsKey(action.key().durableAnchorOwner()))
				connect(adjacency, action.key().producer(), action.key().durableAnchorOwner());
		for(CandidateDependency dependency : candidateDependencies(analysis, decisionByKey))
			connect(adjacency, dependency.producer(), dependency.consumer());

		List<Set<CompiledHopKey>> members = connectedDecisionSets(adjacency);
		Map<CompiledHopKey,Integer> componentByNode = new LinkedHashMap<>();
		for(int index = 0; index < members.size(); index++)
			for(CompiledHopKey key : members.get(index))
				componentByNode.put(key, index);
		List<List<Constraint>> constraints = new ArrayList<>();
		List<List<RelocationAction>> actions = new ArrayList<>();
		for(int index = 0; index < members.size(); index++) {
			constraints.add(new ArrayList<>());
			actions.add(new ArrayList<>());
		}
		for(Constraint constraint : graph.constraints()) {
			Integer left = componentByNode.get(constraint.left());
			Integer right = componentByNode.get(constraint.right());
			if(isLegalityConstraint(constraint) && left != null && right != null) {
				if(!left.equals(right))
					throw new IllegalStateException(
						"legality constraint crosses policy components");
				constraints.get(left).add(constraint);
			}
		}
		for(RelocationAction action : graph.relocationActions()) {
			Set<CompiledHopKey> participants = relocationParticipants(
				decisions, decisionByKey, action);
			if(participants.isEmpty())
				continue;
			Set<Integer> owners = new LinkedHashSet<>();
			for(CompiledHopKey participant : participants)
				owners.add(componentByNode.get(participant));
			if(owners.size() != 1)
				throw new IllegalStateException("relocation action crosses policy components");
			actions.get(owners.iterator().next()).add(action);
		}

		List<PolicyComponent> result = new ArrayList<>();
		for(int index = 0; index < members.size(); index++) {
			List<Node> nodes = members.get(index).stream().map(decisionByKey::get).sorted().toList();
			List<Constraint> componentConstraints = constraints.get(index).stream().sorted().toList();
			List<RelocationAction> componentActions = actions.get(index).stream().sorted().toList();
			result.add(new PolicyComponent(nodes, componentConstraints, componentActions));
		}
		result.sort(Comparator.naturalOrder());
		return List.copyOf(result);
	}

	private static boolean isLegalityConstraint(Constraint constraint) {
		return constraint.kind() == ConstraintKind.SAME_PLACEMENT
			|| constraint.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
			|| constraint.kind() == ConstraintKind.SAME_FTYPE
			|| constraint.kind() == ConstraintKind.CONJUNCTIVE;
	}

	private static Set<CompiledHopKey> relocationParticipants(List<Node> decisions,
		Map<CompiledHopKey,Node> decisionByKey, RelocationAction action) {
		Set<CompiledHopKey> participants = new LinkedHashSet<>();
		for(Node decision : decisions)
			if(decision.valueVersion().equals(action.key().sourceValueVersion()))
				participants.add(decision.key());
		for(var obligation : action.obligations())
			if(decisionByKey.containsKey(obligation.consumer()))
				participants.add(obligation.consumer());
		return participants;
	}

	private static void connectAll(Map<CompiledHopKey,Set<CompiledHopKey>> adjacency,
		Set<CompiledHopKey> participants) {
		if(participants.size() < 2)
			return;
		CompiledHopKey first = participants.iterator().next();
		for(CompiledHopKey participant : participants)
			connect(adjacency, first, participant);
	}

	private static void connect(Map<CompiledHopKey,Set<CompiledHopKey>> adjacency,
		CompiledHopKey left, CompiledHopKey right) {
		if(left == right)
			return;
		Set<CompiledHopKey> leftEdges = adjacency.get(left);
		Set<CompiledHopKey> rightEdges = adjacency.get(right);
		if(leftEdges == null || rightEdges == null)
			return;
		leftEdges.add(right);
		rightEdges.add(left);
	}

	private static List<Set<CompiledHopKey>> connectedDecisionSets(
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency) {
		Set<CompiledHopKey> visited = new LinkedHashSet<>();
		List<Set<CompiledHopKey>> result = new ArrayList<>();
		for(CompiledHopKey start : adjacency.keySet()) {
			if(!visited.add(start))
				continue;
			Set<CompiledHopKey> members = new LinkedHashSet<>();
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
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
		return List.copyOf(result);
	}

	private static List<CandidateDependency> candidateDependencies(PlacementAnalysis analysis,
		Map<CompiledHopKey,Node> nodes) {
		if(analysis == null)
			return List.of();
		Map<CompiledHopKey,Map<Integer,List<CompiledHopKey>>> producers = new IdentityHashMap<>();
		for(PlacementAnalysis.CompiledInputEdgeFact edge :
			analysis.compiledInputEdgesInCanonicalOrder())
			producers.computeIfAbsent(edge.consumer(), ignored -> new LinkedHashMap<>())
				.computeIfAbsent(edge.inputPosition(), ignored -> new ArrayList<>())
				.add(edge.producer());
		Set<CandidateDependency> dependencies = new java.util.TreeSet<>();
		for(PlacementAnalysis.CandidateRuleFact fact :
			analysis.candidateRuleFacts().orderedFacts()) {
			Node consumer = nodes.get(fact.key().parentOccurrence());
			if(consumer == null
				|| fact.status() != PlacementAnalysis.CandidateEvaluationStatus.AVAILABLE
				|| fact.allowedEmissionFacts().stream().noneMatch(emission ->
					consumer.legalAlternatives().stream().anyMatch(state ->
						state == emission.emissionState().placementState())))
				continue;
			for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
				List<CompiledHopKey> edges = producers.getOrDefault(consumer.key(), Map.of())
					.getOrDefault(position, List.of());
				if(edges.size() > 1)
					throw new IllegalStateException(
						"candidate input edge is ambiguous while constructing policy components");
				if(edges.size() == 1 && nodes.containsKey(edges.get(0)))
					dependencies.add(new CandidateDependency(edges.get(0), consumer.key(), position));
			}
		}
		return List.copyOf(dependencies);
	}

	private static final class Solver {
		private final PlacementAnalysis analysis;
		private final NeutralPlacementGraph graph;
		private final List<Constraint> constraints;
		private final List<RelocationAction> relocationActions;
		private final List<Node> decisions;
		private final List<DecisionGroup> groups;
		private final Map<CompiledHopKey,Integer> groupsByKey;
		private final Map<ValueVersionKey,List<Integer>> sourceGroupsByValue;
		private final List<Relation> relations;
		private final List<List<Relation>> relationsByGroup;
		private final List<List<RelocationAction>> relocationsByGroup;
		private final List<List<DerivedFoutMaterializationAction>> derivedActionsByGroup;
		private final CandidateSelections.PartialReachabilityIndex reachability;
		private final Map<DecisionGroup,
			CandidateSelections.PartialReachabilityIndex.ChangedNodesReachabilityProbe>
			reachabilityProbes;
		private final OccurrenceExecutionFrequencyFacts frequencyFacts;
		private final ToDoubleFunction<CompiledHopKey> executionWeightOverride;
		private final Map<CompiledHopKey,Double> executionWeights = new IdentityHashMap<>();
		private final Map<RelocationAction,Double> relocationWeights = new IdentityHashMap<>();
		private final Map<CompiledHopKey,PlacementState> current = new IdentityHashMap<>();
		private long explored;
		private long pruned;
		private int maxDepth;

		private Map<CompiledHopKey,PlacementState> solution;

		private Solver(PlacementAnalysis analysis, NeutralPlacementGraph graph,
			List<Node> decisions, List<Constraint> constraints,
			List<RelocationAction> relocationActions,
			CandidateSelections.PartialReachabilityIndex reachability,
			ToDoubleFunction<CompiledHopKey> executionWeightOverride) {
			this.analysis = analysis != null && !analysis.candidateRuleFacts().orderedFacts().isEmpty()
				? analysis : null;
			this.graph = graph;
			this.constraints = List.copyOf(constraints);
			this.relocationActions = List.copyOf(relocationActions);
			this.frequencyFacts = analysis == null ? null : analysis.executionFrequencyFacts();
			this.executionWeightOverride = executionWeightOverride;
			this.decisions = decisions.stream().sorted().toList();
			this.groups = samePlacementGroups(this.decisions, this.constraints);
			this.groupsByKey = groupsByKey(groups);
			this.sourceGroupsByValue = sourceGroupsByValue(groups);
			this.relations = relations(groups, this.constraints);
			this.relationsByGroup = relationsByGroup(groups.size(), relations);
			this.relocationsByGroup = relocationsByGroup(groups.size(), this.relocationActions);
			this.derivedActionsByGroup = derivedActionsByGroup(groups.size(),
				graph.derivedFoutMaterializationActions());
			this.reachability = reachability;
			this.reachabilityProbes = new IdentityHashMap<>();
			if(this.reachability != null)
				for(DecisionGroup group : groups)
					reachabilityProbes.put(group,
						this.reachability.changedNodesProbe(group.members()));
			for(RelocationAction action : this.relocationActions)
				relocationWeights.put(action, computeRelocationWeight(action));
		}

		private Map<CompiledHopKey,PlacementState> solve() {
			List<List<PlacementState>> domains = initialDomains(groups);
			if(!propagate(domains, allArcs()))
				throw new IllegalStateException("placement policy graph has no arc-consistent legal assignment");
			if(!propagateCandidateDomains(domains, null, null))
				throw new IllegalStateException(
					"placement policy graph has no candidate-consistent legal assignment");
			List<DecisionGroup> fixed = assignInitialSingletons(domains);
			if(reachability != null
				&& !reachability.canStillBeReachable(current, domainsByNode(domains))) {
				unassign(fixed);
				throw new IllegalStateException(
					"placement policy graph has no candidate-reachable fixed assignment");
			}
			boolean found = choose(domains, 0);
			unassign(fixed);
			if(!found || solution == null)
				throw new IllegalStateException("placement policy graph has no candidate-reachable legal assignment");
			return solution;
		}

		private boolean choose(List<List<PlacementState>> domains, int depth) {
			maxDepth = Math.max(maxDepth, depth);
			DecisionGroup unresolved = nextGroup(domains);
			if(unresolved == null) {
				explored++;
				if(!constraintsSatisfied(current))
					return false;
				solution = Map.copyOf(current);
				return true;
			}
			for(PlacementState state : orderedAlternatives(unresolved, domains)) {
				List<DomainChange> trail = new ArrayList<>();
				setDomain(domains, unresolved.index(), List.of(state), trail);
				Set<Integer> singletonGroups = new LinkedHashSet<>();
				singletonGroups.add(unresolved.index());
				if(!propagate(domains, arcsFor(unresolved.index()), singletonGroups, trail)) {
					pruned++;
					restoreDomains(domains, trail);
					continue;
				}
				if(!propagateCandidateDomains(domains, singletonGroups, trail)) {
					pruned++;
					restoreDomains(domains, trail);
					continue;
				}
				List<DecisionGroup> assigned = assignNewSingletons(domains, singletonGroups);
				boolean reachable = candidatesReachable(assigned, domains);
				boolean result = reachable && choose(domains, depth + 1);
				if(!reachable)
					pruned++;
				unassign(assigned);
				restoreDomains(domains, trail);
				if(result)
					return true;
			}
			return false;
		}

		/**
		 * Preserve the FedAll FED/FOUT policy order, but break equal-policy layout ties
		 * with only the movement actions incident to this equality group.  This is a
		 * greedy ordering hint, not a global objective proof: the selector still accepts
		 * the first candidate-reachable complete assignment.
		 */
		private List<PlacementState> orderedAlternatives(DecisionGroup group,
			List<List<PlacementState>> domains) {
			List<PlacementState> ordered = new ArrayList<>(domains.get(group.index()));
			Map<PlacementState,MovementHint> hints = new IdentityHashMap<>();
			for(PlacementState state : ordered)
				hints.put(state, movementHint(group, state, domains));
			ordered.sort((left, right) -> {
				int policy = Integer.compare(policyRank(left), policyRank(right));
				if(policy != 0)
					return policy;
				int movement = hints.get(left).compareTo(hints.get(right));
				return movement != 0 ? movement
					: left.normalizedSignature().compareTo(right.normalizedSignature());
			});
			return List.copyOf(ordered);
		}

		private MovementHint movementHint(DecisionGroup selected, PlacementState state,
			List<List<PlacementState>> domains) {
			Map<String,Double> unavoidable = new LinkedHashMap<>();
			Map<String,Double> exposed = new LinkedHashMap<>();
			Map<String,Double> sourcePreparation = new LinkedHashMap<>();
			for(RelocationAction action : relocationsByGroup.get(selected.index())) {
				Requirement requirement = requirement(action, selected, state, domains);
				if(!requirement.possible())
					continue;
				DirectSource direct = directSource(action, selected, state, domains);
				String physical = RelocationSelections.physicalEmissionIdentity(action.key());
				double actionWeight = relocationWeight(action);
				if(requirement.definite() && !direct.possible())
					unavoidable.merge(physical, actionWeight, Math::max);
				else if(!direct.definite())
					exposed.merge(physical, actionWeight, Math::max);
				double preparation = minimumDirectSourcePreparation(
					action, selected, state, domains);
				if(Double.isFinite(preparation) && preparation > 0.0)
					sourcePreparation.merge(physical,
						Math.min(actionWeight, preparation), Math::max);
			}
			Map<String,Double> derived = new LinkedHashMap<>();
			for(DerivedFoutMaterializationAction action : derivedActionsByGroup.get(selected.index())) {
				if(action.key().targetPlacement().equals(state)) {
					String identity = action.key().producer().normalizedSignature() + '|'
						+ action.key().targetPlacement().normalizedSignature() + '|'
						+ action.key().durableAnchor().normalizedSignature();
					derived.merge(identity, executionWeight(action.key().producer()), Math::max);
				}
			}
			return new MovementHint(sum(unavoidable), sum(exposed), sum(derived),
				sum(sourcePreparation), anchorAffinityPenalty(selected, state));
		}

		private List<List<RelocationAction>> relocationsByGroup(int groupCount,
			List<RelocationAction> actions) {
			List<LinkedHashSet<RelocationAction>> indexed = new ArrayList<>();
			for(int group = 0; group < groupCount; group++)
				indexed.add(new LinkedHashSet<>());
			for(RelocationAction action : actions) {
				for(Integer sourceGroup : sourceGroupsByValue.getOrDefault(
					action.key().sourceValueVersion(), List.of()))
					indexed.get(sourceGroup).add(action);
				for(var obligation : action.obligations()) {
					Integer consumerGroup = groupsByKey.get(obligation.consumer());
					if(consumerGroup != null)
						indexed.get(consumerGroup).add(action);
				}
			}
			return indexed.stream().map(actionsForGroup -> actionsForGroup.stream()
				.sorted().toList()).toList();
		}

		private List<List<DerivedFoutMaterializationAction>> derivedActionsByGroup(
			int groupCount, List<DerivedFoutMaterializationAction> actions) {
			List<List<DerivedFoutMaterializationAction>> indexed = new ArrayList<>();
			for(int group = 0; group < groupCount; group++)
				indexed.add(new ArrayList<>());
			for(DerivedFoutMaterializationAction action : actions) {
				Integer producerGroup = groupsByKey.get(action.key().producer());
				if(producerGroup != null)
					indexed.get(producerGroup).add(action);
			}
			return indexed.stream().map(actionsForGroup -> actionsForGroup.stream()
				.sorted().toList()).toList();
		}

		/**
		 * Lower bound for realizing a direct source state one edge away.  A direct BROADCAST
		 * alternative is not free when it can only be obtained through a derived FOUT
		 * materialization; the selector compares that cost with emitting the relocation itself.
		 */
		private double minimumDirectSourcePreparation(RelocationAction action,
			DecisionGroup selected, PlacementState selectedState,
			List<List<PlacementState>> domains) {
			double minimum = Double.POSITIVE_INFINITY;
			for(Integer sourceGroup : sourceGroupsByValue.getOrDefault(
				action.key().sourceValueVersion(), List.of())) {
				for(PlacementState sourceState : domain(sourceGroup, selected, selectedState, domains)) {
					if(!action.directSourcePlacements().contains(sourceState))
						continue;
					if(hasNativeDirectSourceEmission(sourceGroup, sourceState)) {
						minimum = 0.0;
						continue;
					}
					double preparation = Double.POSITIVE_INFINITY;
					for(DerivedFoutMaterializationAction derived :
						derivedActionsByGroup.get(sourceGroup))
						if(derived.key().targetPlacement().equals(sourceState))
							preparation = Math.min(preparation,
								executionWeight(derived.key().producer()));
					if(!Double.isFinite(preparation))
						preparation = 0.0;
					minimum = Math.min(minimum, preparation);
				}
			}
			return minimum;
		}

		private boolean hasNativeDirectSourceEmission(int sourceGroup, PlacementState sourceState) {
			if(analysis == null)
				return false;
			for(Node source : groups.get(sourceGroup).members())
				for(var fact : analysis.candidateRuleFacts().orderedFactsForParent(source.key()))
					for(var emission : fact.allowedEmissionFacts())
						if(emission.emissionState().placementState().equals(sourceState)
							&& emission.derivedFoutAction() == null)
							return true;
			return false;
		}

		private double relocationWeight(RelocationAction action) {
			return relocationWeights.getOrDefault(action, 1.0);
		}

		private double computeRelocationWeight(RelocationAction action) {
			double weight = 0.0;
			for(var obligation : action.obligations()) {
				double obligationWeight = executionWeight(obligation.consumer());
				if(frequencyFacts != null && executionWeightOverride == null) {
					for(Integer sourceGroup : sourceGroupsByValue.getOrDefault(
						action.key().sourceValueVersion(), List.of()))
						for(Node source : groups.get(sourceGroup).members())
							obligationWeight = Math.min(obligationWeight,
								frequencyFacts.forwardingWeightOrDefault(
									obligation.consumer(), source.key(), obligationWeight));
				}
				// One physical action can serve compatible consumers, so reuse is modeled by
				// the maximum dynamic demand rather than summing duplicate obligations.
				weight = Math.max(weight, obligationWeight);
			}
			return weight > 0.0 ? weight : 1.0;
		}

		private double executionWeight(CompiledHopKey key) {
			Double cached = executionWeights.get(key);
			if(cached != null)
				return cached;
			double weight;
			if(executionWeightOverride != null) {
				weight = executionWeightOverride.applyAsDouble(key);
				if(!Double.isFinite(weight) || weight <= 0.0)
					throw new IllegalArgumentException("selector execution weight must be positive");
			}
			else
				weight = frequencyFacts == null ? 1.0
					: frequencyFacts.executionWeightOrDefault(key, 1.0);
			executionWeights.put(key, weight);
			return weight;
		}

		private int anchorAffinityPenalty(DecisionGroup group, PlacementState state) {
			if(state.fType() == null)
				return 0;
			boolean hasLayoutAnchor = false;
			for(Node member : group.members())
				for(var anchor : member.anchors()) {
					hasLayoutAnchor = true;
					if(anchor.fType() == state.fType())
						return 0;
				}
			return hasLayoutAnchor ? 1 : 0;
		}

		private static double sum(Map<String,Double> weights) {
			double total = 0.0;
			for(double weight : weights.values())
				total += weight;
			return total;
		}

		private Requirement requirement(RelocationAction action, DecisionGroup selected,
			PlacementState state, List<List<PlacementState>> domains) {
			boolean possible = false;
			boolean definite = false;
			for(var obligation : action.obligations()) {
				Integer group = groupsByKey.get(obligation.consumer());
				if(group == null)
					continue;
				List<PlacementState> domain = domain(group, selected, state, domains);
				if(domain.contains(obligation.requiredPlacement()))
					possible = true;
				if(domain.size() == 1 && domain.get(0).equals(obligation.requiredPlacement()))
					definite = true;
			}
			return new Requirement(possible, definite);
		}

		private DirectSource directSource(RelocationAction action, DecisionGroup selected,
			PlacementState state, List<List<PlacementState>> domains) {
			boolean possible = false;
			boolean definite = false;
			for(Integer group : sourceGroupsByValue.getOrDefault(
				action.key().sourceValueVersion(), List.of())) {
				List<PlacementState> domain = domain(group, selected, state, domains);
				if(domain.stream().anyMatch(action.directSourcePlacements()::contains))
					possible = true;
				if(domain.size() == 1 && action.directSourcePlacements().contains(domain.get(0)))
					definite = true;
			}
			return new DirectSource(possible, definite);
		}

		private static List<PlacementState> domain(int group, DecisionGroup selected,
			PlacementState state, List<List<PlacementState>> domains) {
			return group == selected.index() ? List.of(state) : domains.get(group);
		}

		private boolean constraintsSatisfied(Map<CompiledHopKey,PlacementState> assignment) {
			if(assignment.size() != decisions.size())
				return false;
			for(Constraint constraint : constraints) {
				PlacementState left = assignment.get(constraint.left());
				PlacementState right = assignment.get(constraint.right());
				if(left != null && right != null
					&& !NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
					return false;
			}
			return reachability == null || reachability.canStillBeReachable(assignment);
		}

		private DecisionGroup nextGroup(List<List<PlacementState>> domains) {
			DecisionGroup selected = null;
			for(DecisionGroup group : groups) {
				int size = domains.get(group.index()).size();
				if(size <= 1)
					continue;
				if(selected == null || size < domains.get(selected.index()).size()
					|| size == domains.get(selected.index()).size()
						&& relationsByGroup.get(group.index()).size()
							> relationsByGroup.get(selected.index()).size()
					|| size == domains.get(selected.index()).size()
						&& relationsByGroup.get(group.index()).size()
							== relationsByGroup.get(selected.index()).size()
						&& group.compareTo(selected) < 0)
					selected = group;
			}
			return selected;
		}

		private List<DecisionGroup> assignInitialSingletons(List<List<PlacementState>> domains) {
			Set<Integer> singletonGroups = new LinkedHashSet<>();
			for(DecisionGroup group : groups)
				if(domains.get(group.index()).size() == 1)
					singletonGroups.add(group.index());
			return assignNewSingletons(domains, singletonGroups);
		}

		private List<DecisionGroup> assignNewSingletons(List<List<PlacementState>> domains,
			Collection<Integer> singletonGroups) {
			List<DecisionGroup> assigned = new ArrayList<>();
			for(int index : singletonGroups.stream().sorted().toList()) {
				DecisionGroup group = groups.get(index);
				if(current.containsKey(group.members().get(0).key()))
					continue;
				List<PlacementState> domain = domains.get(index);
				if(domain.size() != 1)
					throw new IllegalStateException("propagated singleton group is not singleton");
				group.assign(current, domain.get(0));
				assigned.add(group);
			}
			return assigned;
		}

		private boolean candidatesReachable(List<DecisionGroup> changed,
			List<List<PlacementState>> domains) {
			if(reachability == null)
				return true;
			Map<CompiledHopKey,List<PlacementState>> byNode = domainsByNode(domains);
			for(DecisionGroup group : changed)
				if(!reachability.canStillBeReachable(
					current, byNode, reachabilityProbes.get(group)))
					return false;
			return true;
		}

		/**
		 * Generalized arc consistency for candidate rows. Each retained placement
		 * state must leave at least one exact runtime candidate row reachable under
		 * the current source/anchor domains. This turns late whole-assignment
		 * candidate failures into local domain pruning before greedy commitment.
		 */
		private boolean propagateCandidateDomains(List<List<PlacementState>> domains,
			Set<Integer> singletonGroups, List<DomainChange> trail) {
			if(reachability == null)
				return true;
			boolean changed;
			do {
				changed = false;
				for(DecisionGroup group : groups) {
					List<PlacementState> domain = domains.get(group.index());
					if(domain.isEmpty())
						return false;
					if(current.containsKey(group.members().get(0).key()))
						continue;
					Map<CompiledHopKey,List<PlacementState>> byNode = domainsByNode(domains);
					List<PlacementState> retained = new ArrayList<>();
					for(PlacementState state : domain) {
						group.assign(current, state);
						boolean reachable;
						try {
							reachable = reachability.canStillBeReachable(current, byNode,
								reachabilityProbes.get(group));
						}
						finally {
							group.remove(current);
						}
						if(reachable)
							retained.add(state);
					}
					if(retained.size() == domain.size())
						continue;
					pruned += domain.size() - retained.size();
					setDomain(domains, group.index(), List.copyOf(retained), trail);
					if(retained.isEmpty())
						return false;
					if(singletonGroups != null && domain.size() > 1 && retained.size() == 1)
						singletonGroups.add(group.index());
					if(!propagate(domains, arcsFor(group.index()), singletonGroups, trail))
						return false;
					changed = true;
				}
			}
			while(changed);
			return reachability.canStillBeReachable(current, domainsByNode(domains));
		}

		private Map<CompiledHopKey,List<PlacementState>> domainsByNode(
			List<List<PlacementState>> domains) {
			Map<CompiledHopKey,List<PlacementState>> result = new IdentityHashMap<>();
			for(DecisionGroup group : groups)
				for(Node member : group.members()) {
					List<PlacementState> owned = new ArrayList<>();
					for(PlacementState state : domains.get(group.index()))
						for(PlacementState candidate : member.legalAlternatives())
							if(candidate.equals(state)) {
								owned.add(candidate);
								break;
							}
					result.put(member.key(), List.copyOf(owned));
				}
			return result;
		}

		private void unassign(List<DecisionGroup> assigned) {
			for(int index = assigned.size() - 1; index >= 0; index--)
				assigned.get(index).remove(current);
		}

		private boolean propagate(List<List<PlacementState>> domains, Collection<Arc> initial) {
			return propagate(domains, initial, null, null);
		}

		private boolean propagate(List<List<PlacementState>> domains, Collection<Arc> initial,
			Set<Integer> singletonGroups, List<DomainChange> trail) {
			ArrayDeque<Arc> pending = new ArrayDeque<>(initial);
			while(!pending.isEmpty()) {
				Arc arc = pending.removeFirst();
				int priorSize = domains.get(arc.target()).size();
				if(!revise(domains, arc, trail))
					continue;
				List<PlacementState> revised = domains.get(arc.target());
				if(revised.isEmpty())
					return false;
				if(singletonGroups != null && priorSize > 1 && revised.size() == 1)
					singletonGroups.add(arc.target());
				for(Relation neighbor : relationsByGroup.get(arc.target())) {
					int other = neighbor.other(arc.target());
					if(other != arc.target())
						pending.addLast(new Arc(neighbor, other, arc.target()));
				}
			}
			return true;
		}

		private boolean revise(List<List<PlacementState>> domains, Arc arc,
			List<DomainChange> trail) {
			List<PlacementState> target = domains.get(arc.target());
			List<PlacementState> support = domains.get(arc.support());
			List<PlacementState> retained = target.stream().filter(candidate -> support.stream()
				.anyMatch(other -> arc.relation().satisfied(arc.target(), candidate, other))).toList();
			if(retained.size() == target.size())
				return false;
			pruned += target.size() - retained.size();
			setDomain(domains, arc.target(), retained, trail);
			return true;
		}

		private static void setDomain(List<List<PlacementState>> domains, int group,
			List<PlacementState> replacement, List<DomainChange> trail) {
			List<PlacementState> previous = domains.get(group);
			if(trail != null)
				trail.add(new DomainChange(group, previous));
			domains.set(group, replacement);
		}

		private static void restoreDomains(List<List<PlacementState>> domains,
			List<DomainChange> trail) {
			for(int index = trail.size() - 1; index >= 0; index--) {
				DomainChange change = trail.get(index);
				domains.set(change.group(), change.previous());
			}
		}

		private List<Arc> allArcs() {
			List<Arc> result = new ArrayList<>();
			for(Relation relation : relations) {
				result.add(new Arc(relation, relation.first(), relation.second()));
				if(relation.first() != relation.second())
					result.add(new Arc(relation, relation.second(), relation.first()));
			}
			return result;
		}

		private List<Arc> arcsFor(int group) {
			List<Arc> result = new ArrayList<>();
			for(Relation relation : relationsByGroup.get(group)) {
				int other = relation.other(group);
				result.add(new Arc(relation, other, group));
				if(other == group)
					result.add(new Arc(relation, group, group));
			}
			return result;
		}
	}

	private record Requirement(boolean possible, boolean definite) { }
	private record DirectSource(boolean possible, boolean definite) { }
	private record PolicyComponent(List<Node> nodes, List<Constraint> constraints,
		List<RelocationAction> relocationActions) implements Comparable<PolicyComponent> {
		@Override
		public int compareTo(PolicyComponent that) {
			if(nodes.isEmpty())
				return that.nodes.isEmpty() ? 0 : -1;
			if(that.nodes.isEmpty())
				return 1;
			return nodes.get(0).compareTo(that.nodes.get(0));
		}
	}
	private record CandidateDependency(CompiledHopKey producer, CompiledHopKey consumer,
		int inputPosition) implements Comparable<CandidateDependency> {
		@Override
		public int compareTo(CandidateDependency that) {
			int producerOrder = producer.compareTo(that.producer);
			if(producerOrder != 0)
				return producerOrder;
			int consumerOrder = consumer.compareTo(that.consumer);
			return consumerOrder != 0 ? consumerOrder
				: Integer.compare(inputPosition, that.inputPosition);
		}
	}
	private record MovementHint(double unavoidable, double exposed, double derived,
		double sourcePreparation, int anchorPenalty)
		implements Comparable<MovementHint> {
		@Override
		public int compareTo(MovementHint that) {
			int comparison = Double.compare(
				unavoidable + derived, that.unavoidable + that.derived);
			if(comparison != 0)
				return comparison;
			comparison = Double.compare(sourcePreparation, that.sourcePreparation);
			if(comparison != 0)
				return comparison;
			comparison = Double.compare(exposed, that.exposed);
			if(comparison != 0)
				return comparison;
			comparison = Double.compare(derived, that.derived);
			return comparison != 0 ? comparison : Integer.compare(anchorPenalty, that.anchorPenalty);
		}
	}

	private record ScoredPlan(Map<CompiledHopKey,PlacementState> assignment,
		List<CandidateSelectionReceipt> candidates, List<RelocationChoiceReceipt> choices,
		List<RelocationActionKey> relocations, int fedCount, int foutCount,
		int physicalMovementCount) { }

	private static final class DecisionGroup implements Comparable<DecisionGroup> {
		private int index;
		private final List<Node> members;
		private final List<PlacementState> alternatives;

		private DecisionGroup(List<Node> members, List<PlacementState> alternatives) {
			this.members = members.stream().sorted().toList();
			this.alternatives = alternatives.stream().distinct().sorted(POLICY_ORDER).toList();
			if(this.members.isEmpty() || this.alternatives.isEmpty())
				throw new IllegalStateException("SAME_PLACEMENT component has no common legal state");
		}

		private int index() { return index; }
		private List<Node> members() { return members; }
		private List<PlacementState> alternatives() { return alternatives; }

		private void assign(Map<CompiledHopKey,PlacementState> assignment, PlacementState state) {
			for(Node member : members) {
				PlacementState owned = null;
				for(PlacementState candidate : member.legalAlternatives())
					if(candidate.equals(state)) {
						if(owned != null)
							throw new IllegalStateException("duplicate node-owned placement state");
						owned = candidate;
					}
				if(owned == null)
					throw new IllegalStateException("SAME_PLACEMENT member has no node-owned state");
					assignment.put(member.key(), owned);
				}
			}

			private void remove(Map<CompiledHopKey,PlacementState> assignment) {
				for(Node member : members)
					assignment.remove(member.key());
			}

		@Override
		public int compareTo(DecisionGroup that) {
			return members.get(0).compareTo(that.members.get(0));
		}
	}

	private record Relation(int first, int second, List<Constraint> constraints,
		Map<CompiledHopKey,Integer> groupsByKey) {
		private Relation {
			constraints = List.copyOf(constraints);
		}

		private int other(int group) {
			if(group == first)
				return second;
			if(group == second)
				return first;
			throw new IllegalArgumentException("group is outside relation");
		}

		private boolean satisfied(int targetGroup, PlacementState target, PlacementState support) {
			for(Constraint constraint : constraints) {
				int leftGroup = groupsByKey.get(constraint.left());
				PlacementState left = leftGroup == targetGroup ? target : support;
				PlacementState right = leftGroup == targetGroup ? support : target;
				if(first == second) {
					left = target;
					right = target;
				}
				if(!NeutralPlacementGraph.constraintSatisfied(constraint, left, right))
					return false;
			}
			return true;
		}
	}

	private record Arc(Relation relation, int target, int support) { }
	private record DomainChange(int group, List<PlacementState> previous) { }

	private static List<DecisionGroup> samePlacementGroups(List<Node> decisions,
		List<Constraint> constraints) {
		Map<CompiledHopKey,Node> nodes = new IdentityHashMap<>();
		Map<CompiledHopKey,Set<CompiledHopKey>> adjacency = new IdentityHashMap<>();
		for(Node node : decisions) {
			nodes.put(node.key(), node);
			adjacency.put(node.key(), Collections.newSetFromMap(new IdentityHashMap<>()));
		}
		for(Constraint constraint : constraints)
			if(constraint.kind() == ConstraintKind.SAME_PLACEMENT
				&& nodes.containsKey(constraint.left()) && nodes.containsKey(constraint.right())) {
				adjacency.get(constraint.left()).add(constraint.right());
				adjacency.get(constraint.right()).add(constraint.left());
			}
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<DecisionGroup> groups = new ArrayList<>();
		for(Node start : decisions) {
			if(!seen.add(start.key()))
				continue;
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
			List<Node> members = new ArrayList<>();
			pending.add(start.key());
			while(!pending.isEmpty()) {
				CompiledHopKey key = pending.removeFirst();
				members.add(nodes.get(key));
				for(CompiledHopKey neighbor : adjacency.get(key))
					if(seen.add(neighbor))
						pending.addLast(neighbor);
			}
			members.sort(Comparator.naturalOrder());
			List<PlacementState> common = new ArrayList<>(members.get(0).legalAlternatives());
			for(int index = 1; index < members.size(); index++) {
				List<PlacementState> memberAlternatives = members.get(index).legalAlternatives();
				common.removeIf(state -> !memberAlternatives.contains(state));
			}
			groups.add(new DecisionGroup(members, common));
		}
		groups.sort(Comparator.naturalOrder());
		for(int index = 0; index < groups.size(); index++)
			groups.get(index).index = index;
		return List.copyOf(groups);
	}

	private static List<Relation> relations(List<DecisionGroup> groups,
		List<Constraint> constraints) {
		Map<CompiledHopKey,Integer> groupsByKey = new IdentityHashMap<>();
		for(DecisionGroup group : groups)
			for(Node member : group.members())
				groupsByKey.put(member.key(), group.index());
		Map<Long,List<Constraint>> byPair = new LinkedHashMap<>();
		for(Constraint constraint : constraints) {
			Integer left = groupsByKey.get(constraint.left());
			Integer right = groupsByKey.get(constraint.right());
			if(left == null || right == null)
				continue;
			int first = Math.min(left, right);
			int second = Math.max(left, right);
			long pair = ((long)first << 32) | (second & 0xffffffffL);
			byPair.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(constraint);
		}
		List<Relation> result = new ArrayList<>();
		for(Map.Entry<Long,List<Constraint>> entry : byPair.entrySet()) {
			int first = (int)(entry.getKey() >>> 32);
			int second = entry.getKey().intValue();
			result.add(new Relation(first, second,
				entry.getValue().stream().sorted().toList(), groupsByKey));
		}
		result.sort(Comparator.comparingInt(Relation::first).thenComparingInt(Relation::second));
		return List.copyOf(result);
	}

	private static Map<CompiledHopKey,Integer> groupsByKey(List<DecisionGroup> groups) {
		Map<CompiledHopKey,Integer> result = new IdentityHashMap<>();
		for(DecisionGroup group : groups)
			for(Node member : group.members())
				result.put(member.key(), group.index());
		return Collections.unmodifiableMap(result);
	}

	private static Map<ValueVersionKey,List<Integer>> sourceGroupsByValue(
		List<DecisionGroup> groups) {
		Map<ValueVersionKey,LinkedHashSet<Integer>> mutable = new LinkedHashMap<>();
		for(DecisionGroup group : groups)
			for(Node member : group.members())
				mutable.computeIfAbsent(member.valueVersion(), ignored -> new LinkedHashSet<>())
					.add(group.index());
		Map<ValueVersionKey,List<Integer>> result = new LinkedHashMap<>();
		for(Map.Entry<ValueVersionKey,LinkedHashSet<Integer>> entry : mutable.entrySet())
			result.put(entry.getKey(), List.copyOf(entry.getValue()));
		return Collections.unmodifiableMap(result);
	}

	private static List<List<Relation>> relationsByGroup(int size, List<Relation> relations) {
		List<List<Relation>> mutable = new ArrayList<>(size);
		for(int index = 0; index < size; index++)
			mutable.add(new ArrayList<>());
		for(Relation relation : relations) {
			mutable.get(relation.first()).add(relation);
			if(relation.second() != relation.first())
				mutable.get(relation.second()).add(relation);
		}
		return mutable.stream().map(List::copyOf).toList();
	}

	private static List<List<PlacementState>> initialDomains(List<DecisionGroup> groups) {
		List<List<PlacementState>> result = new ArrayList<>(groups.size());
		for(DecisionGroup group : groups)
			result.add(group.alternatives());
		return result;
	}

	private static String normalizedSignature(ScoredPlan plan) {
		List<String> rows = new ArrayList<>();
		plan.assignment().entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(entry -> rows.add("STATE=" + entry.getKey().normalizedSignature()
				+ '=' + entry.getValue().normalizedSignature()));
		plan.candidates().stream().sorted()
			.forEach(value -> rows.add("CANDIDATE=" + value.normalizedSignature()));
		plan.choices().stream().sorted()
			.forEach(value -> rows.add("CHOICE=" + value.normalizedSignature()));
		plan.relocations().stream().sorted()
			.forEach(value -> rows.add("RELOCATION=" + value.normalizedSignature()));
		return String.join("\n", rows);
	}

	private static List<ComponentBound> policyBounds(NeutralPlacementGraph graph,
		PlacementScore score) {
		if(graph.decisionNodes().isEmpty())
			return List.of();
		Set<String> nodes = new LinkedHashSet<>();
		graph.decisionNodes().stream().map(node -> node.key().normalizedSignature()).sorted()
			.forEach(nodes::add);
		return List.of(new ComponentBound("policy-graph", nodes, graph.decisionNodes().size(),
			graph.constraints().size(), score, "selected-policy-feasible-envelope"));
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch(Exception exception) {
			throw new IllegalStateException("JVM must provide SHA-256", exception);
		}
	}
}
