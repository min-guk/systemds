/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.placement.DerivedFoutAnchorCompatibility;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofEdge;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofQuery;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofStep;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.DerivedFoutMaterializationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact bounded-arity circuit for one derived-FOUT fixed-pool legality predicate. */
final class ExactDerivedFoutAnchorEncoding {
	/** Exact authority key: structurally equal compiled owners are still distinct decisions. */
	private static final class QueryKey {
		private final ProofQuery query;
		private QueryKey(ProofQuery query) { this.query = Objects.requireNonNull(query); }
		@Override public boolean equals(Object other) {
			if(this == other) return true;
			if(!(other instanceof QueryKey that)) return false;
			return query.reference().rule() == that.query.reference().rule()
				&& query.reference().realization().equals(that.query.reference().realization())
				&& query.supplier() == that.query.supplier()
				&& query.origin() == that.query.origin()
				&& query.projectedTarget() == that.query.projectedTarget();
		}
		@Override public int hashCode() {
			int result = System.identityHashCode(query.reference().rule());
			result = 31 * result + query.reference().realization().hashCode();
			result = 31 * result + System.identityHashCode(query.supplier());
			result = 31 * result + System.identityHashCode(query.origin());
			return 31 * result + System.identityHashCode(query.projectedTarget());
		}
	}

	private static final class EdgeKey {
		private final QueryKey child;
		private final PlacementIdentity.CandidateRealizationReference guard;
		private EdgeKey(ProofQuery child,
			PlacementIdentity.CandidateRealizationReference guard) {
			this.child = new QueryKey(child);
			this.guard = guard;
		}
		QueryKey child() { return child; }
		PlacementIdentity.CandidateRealizationReference guard() { return guard; }
		@Override public boolean equals(Object other) {
			if(this == other) return true;
			if(!(other instanceof EdgeKey that) || !child.equals(that.child)) return false;
			return guard == null ? that.guard == null : that.guard != null
				&& guard.rule() == that.guard.rule()
				&& guard.realization().equals(that.guard.realization());
		}
		@Override public int hashCode() {
			return 31 * child.hashCode() + (guard == null ? 0
				: 31 * System.identityHashCode(guard.rule()) + guard.realization().hashCode());
		}
		String signature() {
			return child.query.normalizedSignature() + "|guard="
				+ (guard == null ? "-" : guard.normalizedSignature());
		}
	}
	private record QueryPlan(QueryKey key, DecisionDomain decision,
		CandidateSelectionReceipt[] receipts, List<ProofStep> steps, List<EdgeKey> edges) { }
	private record EdgeVariables(EdgeKey edge, ExactCategoricalSolver.Variable enabled,
		ExactCategoricalSolver.Variable chosen) { }

	private static final class Circuit {
		private final String key;
		private final List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		private final List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		private BigInteger cells = BigInteger.ZERO;

		private Circuit(String key) { this.key = key; }
		private ExactCategoricalSolver.Variable variable(String suffix, int domain) {
			var result = new ExactCategoricalSolver.Variable(
				"exact-derived-fout-anchor|" + key + '|' + suffix, domain);
			variables.add(result);
			return result;
		}
		private void factor(List<ExactCategoricalSolver.Variable> scope,
			ExactCategoricalSolver.CostFunction evaluator) {
			factor(ExactCategoricalSolver.Factor.lazy(scope, evaluator));
		}
		private void factor(ExactCategoricalSolver.Factor factor) {
			List<ExactCategoricalSolver.Variable> scope = factor.scope();
			if(new HashSet<>(scope).size() != scope.size())
				throw new IllegalArgumentException("EXACT_DERIVED_FOUT_DUPLICATE_FACTOR_VARIABLE");
			BigInteger factorCells = BigInteger.ONE;
			for(var variable : scope)
				factorCells = factorCells.multiply(BigInteger.valueOf(variable.domainSize()));
			if(factorCells.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)
				throw new IllegalArgumentException("EXACT_DERIVED_FOUT_ENCODED_FACTOR_OVERFLOW|scope="
					+ scope.stream().map(ExactCategoricalSolver.Variable::key).toList()
					+ "|cells=" + factorCells);
			factors.add(factor);
			cells = cells.add(factorCells);
		}
	}

	private ExactDerivedFoutAnchorEncoding() { }

	static ExactHardFactorObservationDecomposition.Result create(String key,
		PlacementAnalysis analysis, DerivedFoutMaterializationAction action,
		DerivedFoutAnchorCompatibility.Prepared compatibility, DecisionDomain producer,
		Map<CompiledHopKey,DecisionDomain> domains, ExactCategoricalSolver.Factor canonical) {
		return create(key, analysis, action, compatibility, producer, domains, canonical, true);
	}

	static ExactHardFactorObservationDecomposition.Result create(String key,
		PlacementAnalysis analysis, DerivedFoutMaterializationAction action,
		DerivedFoutAnchorCompatibility.Prepared compatibility, DecisionDomain producer,
		Map<CompiledHopKey,DecisionDomain> domains, ExactCategoricalSolver.Factor canonical,
		boolean requireProfitability) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(compatibility, "compatibility");
		Objects.requireNonNull(producer, "producer");
		Objects.requireNonNull(domains, "domains");
		Objects.requireNonNull(canonical, "canonical");
		BigInteger canonicalCells = BigInteger.ONE;
		for(var variable : canonical.scope())
			canonicalCells = canonicalCells.multiply(BigInteger.valueOf(variable.domainSize()));
		BigInteger producerActiveLowerBound = BigInteger.valueOf(producer.variable().domainSize())
			.multiply(BigInteger.TWO);
		if(requireProfitability && canonicalCells.compareTo(producerActiveLowerBound) <= 0)
			return null;

		DecisionDomain owner = Objects.requireNonNull(domains.get(action.key().durableAnchorOwner()),
			"Derived-FOUT owner has no exact decision domain");
		Map<DecisionDomain,CandidateSelectionReceipt[]> receiptTables = new IdentityHashMap<>();
		CandidateSelectionReceipt[] ownerReceipts = receipts(analysis, owner, receiptTables);
		LinkedHashSet<QueryKey> roots = new LinkedHashSet<>();
		for(CandidateSelectionReceipt receipt : ownerReceipts)
			if(receipt != null)
				roots.add(new QueryKey(compatibility.proofQuery(receipt)));
		if(roots.isEmpty())
			return null;

		Set<CompiledHopKey> canonicalOwners = Collections.newSetFromMap(new IdentityHashMap<>());
		for(DecisionDomain domain : domains.values())
			if(canonical.scope().stream().anyMatch(variable -> variable == domain.variable()))
				canonicalOwners.add(domain.node().key());
		Map<QueryKey,QueryPlan> discovered = new LinkedHashMap<>();
		Deque<QueryKey> pending = new ArrayDeque<>(roots);
		while(!pending.isEmpty()) {
			QueryKey queryKey = pending.removeFirst();
			if(discovered.containsKey(queryKey))
				continue;
			ProofQuery query = queryKey.query;
			if(!canonicalOwners.contains(query.decisionOwner()))
				return null;
			DecisionDomain decision = domains.get(query.decisionOwner());
			if(decision == null)
				throw new IllegalArgumentException("EXACT_DERIVED_FOUT_PROOF_DOMAIN_MISSING|owner="
					+ query.decisionOwner().normalizedSignature());
			CandidateSelectionReceipt[] selected = receipts(analysis, decision, receiptTables);
			List<ProofStep> steps = new ArrayList<>(selected.length);
			LinkedHashSet<EdgeKey> edges = new LinkedHashSet<>();
			for(CandidateSelectionReceipt receipt : selected) {
				ProofStep step = compatibility.proofStep(query, receipt);
				steps.add(step);
				for(ProofEdge edge : step.edges()) {
					EdgeKey edgeKey = new EdgeKey(edge.child(), edge.selectedReferenceGuard());
					edges.add(edgeKey);
					pending.addLast(edgeKey.child());
				}
			}
			discovered.put(queryKey, new QueryPlan(queryKey, decision, selected, List.copyOf(steps),
				edges.stream().sorted(Comparator.comparing(EdgeKey::signature)).toList()));
		}
		List<QueryPlan> plans = discovered.values().stream()
			.sorted(Comparator.comparing(plan -> plan.key().query.normalizedSignature())).toList();
		Map<QueryKey,Integer> planIndex = new HashMap<>();
		for(int index = 0; index < plans.size(); index++)
			planIndex.put(plans.get(index).key(), index);
		List<List<Integer>> adjacency = adjacency(plans, planIndex);
		List<List<Integer>> components = stronglyConnectedComponents(adjacency);
		int[] componentOf = new int[plans.size()];
		Arrays.fill(componentOf, -1);
		Set<Integer> cyclic = new HashSet<>();
		for(int component = 0; component < components.size(); component++) {
			List<Integer> members = components.get(component);
			boolean cycle = members.size() > 1
				|| adjacency.get(members.get(0)).contains(members.get(0));
			for(int member : members)
				componentOf[member] = component;
			if(cycle)
				cyclic.add(component);
		}

		Circuit circuit = new Circuit(key);
		List<ExactCategoricalSolver.Variable> active = new ArrayList<>(plans.size());
		List<ExactCategoricalSolver.Variable> terminal = new ArrayList<>(plans.size());
		List<List<EdgeVariables>> edgeVariables = new ArrayList<>(plans.size());
		List<List<ExactCategoricalSolver.Variable>> incoming = new ArrayList<>(plans.size());
		for(int index = 0; index < plans.size(); index++) {
			active.add(circuit.variable("query=" + index + "|active", 2));
			terminal.add(circuit.variable("query=" + index + "|terminal", 2));
			edgeVariables.add(new ArrayList<>());
			incoming.add(new ArrayList<>());
		}

		var producerActive = circuit.variable("producer-active", 2);
		circuit.factor(List.of(producer.variable(), producerActive), values ->
			values[1] == (producer.alternatives().get(values[0]).derivedFoutAction() == action ? 1 : 0)
				? 0d : Double.POSITIVE_INFINITY);
		List<ExactCategoricalSolver.Variable> rootSignals = new ArrayList<>();
		for(QueryKey root : roots.stream().sorted(
			Comparator.comparing(value -> value.query.normalizedSignature())).toList()) {
			int query = requireIndex(planIndex, root);
			var signal = circuit.variable("root=" + query, 2);
			rootSignals.add(signal);
			incoming.get(query).add(signal);
			if(producer == owner)
				circuit.factor(List.of(producer.variable(), signal), values -> {
					Alternative selected = producer.alternatives().get(values[0]);
					CandidateSelectionReceipt receipt = ownerReceipts[values[0]];
					boolean expected = selected.derivedFoutAction() == action && receipt != null
						&& new QueryKey(compatibility.proofQuery(receipt)).equals(root)
						&& ownerSatisfied(action, selected);
					return values[1] == (expected ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY;
				});
			else
				circuit.factor(List.of(producerActive, owner.variable(), signal), values -> {
					Alternative selectedOwner = owner.alternatives().get(values[1]);
					CandidateSelectionReceipt receipt = ownerReceipts[values[1]];
					boolean expected = values[0] != 0 && receipt != null
						&& new QueryKey(compatibility.proofQuery(receipt)).equals(root)
						&& ownerSatisfied(action, selectedOwner);
					return values[2] == (expected ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY;
				});
		}
		var anyRoot = or(circuit, "root-any", rootSignals);
		circuit.factor(List.of(producerActive, anyRoot), values ->
			values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);

		for(int query = 0; query < plans.size(); query++) {
			QueryPlan plan = plans.get(query);
			for(int edgeOrdinal = 0; edgeOrdinal < plan.edges().size(); edgeOrdinal++) {
				EdgeKey edge = plan.edges().get(edgeOrdinal);
				int child = requireIndex(planIndex, edge.child());
				var enabled = circuit.variable("query=" + query + "|edge=" + edgeOrdinal + "|enabled", 2);
				var chosen = circuit.variable("query=" + query + "|edge=" + edgeOrdinal + "|chosen", 2);
				edgeVariables.get(query).add(new EdgeVariables(edge, enabled, chosen));
				incoming.get(child).add(enabled);
				circuit.factor(List.of(plan.decision().variable(), active.get(query), enabled), values -> {
					boolean expected = values[1] != 0 && plan.steps().get(values[0]).edges().stream()
						.anyMatch(candidate -> new EdgeKey(candidate.child(), candidate.selectedReferenceGuard()).equals(edge));
					return values[2] == (expected ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY;
				});
				circuit.factor(List.of(chosen, enabled), values ->
					values[0] == 0 || values[1] != 0 ? 0d : Double.POSITIVE_INFINITY);
				if(edge.guard() != null) {
					QueryPlan childPlan = plans.get(child);
					circuit.factor(List.of(enabled, childPlan.decision().variable()), values ->
						values[0] == 0 || exactReference(analysis,
							edge.guard(), childPlan.receipts()[values[1]])
							? 0d : Double.POSITIVE_INFINITY);
				}
			}
		}

		for(int query = 0; query < plans.size(); query++) {
			var demanded = or(circuit, "query=" + query + "|demand", incoming.get(query));
			circuit.factor(List.of(demanded, active.get(query)), values ->
				values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);
			circuit.factor(List.of(active.get(query), producerActive), values ->
				values[0] == 0 || values[1] != 0 ? 0d : Double.POSITIVE_INFINITY);
			QueryPlan plan = plans.get(query);
			circuit.factor(List.of(plan.decision().variable(), active.get(query)), values -> {
				if(values[1] == 0)
					return 0d;
				ProofStep step = plan.steps().get(values[0]);
				return locallyValid(step, action.key().durableAnchor())
					? 0d : Double.POSITIVE_INFINITY;
			});
			circuit.factor(List.of(plan.decision().variable(), active.get(query), terminal.get(query)), values -> {
				ProofStep step = plan.steps().get(values[0]);
				boolean expected = values[1] != 0 && locallyTerminal(step);
				return values[2] == (expected ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY;
			});
			List<ExactCategoricalSolver.Variable> chosen = edgeVariables.get(query).stream()
				.map(EdgeVariables::chosen).toList();
			exactlyOneUnlessTerminal(circuit, "query=" + query, active.get(query),
				terminal.get(query), chosen);
		}

		for(int component : cyclic) {
			List<Integer> members = components.get(component);
			int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(members.size()));
			Map<Integer,List<ExactCategoricalSolver.Variable>> ranks = new HashMap<>();
			for(int query : members) {
				List<ExactCategoricalSolver.Variable> rank = new ArrayList<>(bits);
				for(int bit = 0; bit < bits; bit++) {
					var value = circuit.variable("component=" + component + "|query=" + query + "|rank=" + bit, 2);
					rank.add(value);
					circuit.factor(List.of(active.get(query), terminal.get(query), value), values ->
						(values[0] == 0 || values[1] != 0) && values[2] != 0
							? Double.POSITIVE_INFINITY : 0d);
				}
				ranks.put(query, rank);
			}
			for(int parent : members)
				for(int edgeOrdinal = 0; edgeOrdinal < edgeVariables.get(parent).size(); edgeOrdinal++) {
					EdgeVariables edge = edgeVariables.get(parent).get(edgeOrdinal);
					int child = requireIndex(planIndex, edge.edge().child());
					if(componentOf[child] == component) {
						if(parent == child)
							circuit.factor(List.of(edge.chosen()), values ->
								values[0] == 0 ? 0d : Double.POSITIVE_INFINITY);
						else
							strictGreater(circuit, "component=" + component + "|parent=" + parent
								+ "|child=" + child + "|edgeOrdinal=" + edgeOrdinal,
								edge.chosen(), ranks.get(parent), ranks.get(child));
					}
				}
		}

		if(requireProfitability && circuit.cells.compareTo(canonicalCells) >= 0)
			return null;
		String descriptor = key + "|canonicalScope=" + canonical.scope().stream()
			.map(ExactCategoricalSolver.Variable::key).toList() + "|canonicalCells=" + canonicalCells
			+ "|encodedCells=" + circuit.cells + "|queries=" + plans.size()
			+ "|edges=" + edgeVariables.stream().mapToInt(List::size).sum()
			+ "|components=" + components.size() + "|cyclicComponents=" + cyclic.size();
		return new ExactHardFactorObservationDecomposition.Result(circuit.variables, circuit.factors,
			saturatedLong(canonicalCells), saturatedLong(circuit.cells), List.of(), descriptor);
	}

	/** Package-local adversarial oracle for the SCC grounding circuit, without planner fixtures. */
	static boolean groundingCircuitFeasibleForTest(boolean[] terminalNodes, int[][] children) {
		if(terminalNodes.length != children.length)
			throw new IllegalArgumentException("EXACT_DERIVED_FOUT_TEST_GRAPH_SIZE_MISMATCH");
		Circuit circuit = new Circuit("grounding-fixture");
		List<ExactCategoricalSolver.Variable> active = new ArrayList<>();
		List<ExactCategoricalSolver.Variable> terminal = new ArrayList<>();
		List<List<ExactCategoricalSolver.Variable>> chosen = new ArrayList<>();
		List<List<Integer>> adjacency = new ArrayList<>();
		for(int node = 0; node < children.length; node++) {
			var activeNode = circuit.variable("node=" + node + "|active", 2);
			var terminalNode = circuit.variable("node=" + node + "|terminal", 2);
			active.add(activeNode);
			terminal.add(terminalNode);
			circuit.factor(List.of(activeNode), values ->
				values[0] == 1 ? 0d : Double.POSITIVE_INFINITY);
			final boolean expectedTerminal = terminalNodes[node];
			circuit.factor(List.of(terminalNode), values ->
				values[0] == (expectedTerminal ? 1 : 0) ? 0d : Double.POSITIVE_INFINITY);
			List<ExactCategoricalSolver.Variable> nodeChoices = new ArrayList<>();
			List<Integer> nodeChildren = new ArrayList<>();
			for(int edge = 0; edge < children[node].length; edge++) {
				int child = children[node][edge];
				if(child < 0 || child >= children.length)
					throw new IllegalArgumentException("EXACT_DERIVED_FOUT_TEST_GRAPH_CHILD_INVALID");
				nodeChildren.add(child);
				nodeChoices.add(circuit.variable("node=" + node + "|edge=" + edge + "|chosen", 2));
			}
			chosen.add(nodeChoices);
			adjacency.add(List.copyOf(nodeChildren));
			exactlyOneUnlessTerminal(circuit, "node=" + node,
				activeNode, terminalNode, nodeChoices);
		}
		List<List<Integer>> components = stronglyConnectedComponents(adjacency);
		int[] componentOf = new int[children.length];
		for(int component = 0; component < components.size(); component++)
			for(int node : components.get(component)) componentOf[node] = component;
		for(int component = 0; component < components.size(); component++) {
			List<Integer> members = components.get(component);
			boolean cyclic = members.size() > 1
				|| adjacency.get(members.get(0)).contains(members.get(0));
			if(!cyclic) continue;
			int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(members.size()));
			Map<Integer,List<ExactCategoricalSolver.Variable>> ranks = new HashMap<>();
			for(int node : members) {
				List<ExactCategoricalSolver.Variable> rank = new ArrayList<>();
				for(int bit = 0; bit < bits; bit++)
					rank.add(circuit.variable("component=" + component + "|node=" + node
						+ "|rank=" + bit, 2));
				ranks.put(node, rank);
			}
			for(int parent : members)
				for(int edge = 0; edge < adjacency.get(parent).size(); edge++) {
					int child = adjacency.get(parent).get(edge);
					if(componentOf[child] != component) continue;
					if(parent == child)
						circuit.factor(List.of(chosen.get(parent).get(edge)), values ->
							values[0] == 0 ? 0d : Double.POSITIVE_INFINITY);
					else
						strictGreater(circuit, "component=" + component + "|parent=" + parent
							+ "|child=" + child + "|edgeOrdinal=" + edge,
							chosen.get(parent).get(edge), ranks.get(parent), ranks.get(child));
				}
		}
		try {
			return Double.isFinite(ExactCategoricalSolver.solve(circuit.variables, circuit.factors,
				new ExactCategoricalSolver.Limits(1_000_000, 10_000_000),
				(variable, value) -> 0L).objective());
		}
		catch(IllegalArgumentException failure) {
			String message = String.valueOf(failure.getMessage());
			if(message.contains("INFEASIBLE") || message.contains("NO_FEASIBLE_ASSIGNMENT"))
				return false;
			throw failure;
		}
	}

	private static CandidateSelectionReceipt[] receipts(PlacementAnalysis analysis,
		DecisionDomain domain, Map<DecisionDomain,CandidateSelectionReceipt[]> cache) {
		return cache.computeIfAbsent(domain, ignored -> {
			CandidateSelectionReceipt[] result = new CandidateSelectionReceipt[domain.alternatives().size()];
			for(int value = 0; value < result.length; value++) {
				result[value] = ExactPhysicalModel.candidateReceipt(
					analysis, domain.alternatives().get(value));
			}
			return result;
		});
	}

	private static boolean ownerSatisfied(DerivedFoutMaterializationAction action,
		Alternative owner) {
		return owner.decision() == action.key().durableAnchorOwner()
			&& owner.state().output() == FederatedOutput.FOUT
			&& owner.state().fType() == action.key().durableAnchorOwnerFType();
	}

	private static boolean exactReference(PlacementAnalysis analysis,
		PlacementIdentity.CandidateRealizationReference reference,
		CandidateSelectionReceipt selected) {
		return selected != null && reference.rule() == selected.rule()
			&& analysis.requireExactCandidateRealization(reference) == selected.realization();
	}

	private static boolean locallyValid(ProofStep step, DurableAnchorKey expected) {
		if(step.invalid())
			return false;
		if(step.exactPool() != null
			&& !PlacementIdentity.samePhysicalWorkerPool(step.exactPool(), expected))
			return false;
		return step.invariantPools().stream()
			.allMatch(pool -> PlacementIdentity.samePhysicalWorkerPool(pool, expected));
	}

	private static boolean locallyTerminal(ProofStep step) {
		return !step.invalid() && (step.exactPool() != null || !step.invariantPools().isEmpty());
	}

	private static ExactCategoricalSolver.Variable or(Circuit circuit, String key,
		List<ExactCategoricalSolver.Variable> inputs) {
		var result = circuit.variable(key + "|or", 2);
		if(inputs.isEmpty()) {
			circuit.factor(List.of(result), values -> values[0] == 0 ? 0d : Double.POSITIVE_INFINITY);
			return result;
		}
		ExactCategoricalSolver.Variable accumulated = inputs.get(0);
		for(int index = 1; index < inputs.size(); index++) {
			var next = circuit.variable(key + "|prefix=" + index, 2);
			circuit.factor(List.of(accumulated, inputs.get(index), next), values ->
				values[2] == (values[0] != 0 || values[1] != 0 ? 1 : 0)
					? 0d : Double.POSITIVE_INFINITY);
			accumulated = next;
		}
		circuit.factor(List.of(accumulated, result), values ->
			values[0] == values[1] ? 0d : Double.POSITIVE_INFINITY);
		return result;
	}

	private static void exactlyOneUnlessTerminal(Circuit circuit, String key,
		ExactCategoricalSolver.Variable active, ExactCategoricalSolver.Variable terminal,
		List<ExactCategoricalSolver.Variable> choices) {
		if(choices.isEmpty()) {
			circuit.factor(List.of(active, terminal), values ->
				values[0] == 0 || values[1] != 0 ? 0d : Double.POSITIVE_INFINITY);
			return;
		}
		var count = circuit.variable(key + "|chosen-count=0", 3);
		circuit.factor(List.of(choices.get(0), count), values ->
			values[1] == values[0] ? 0d : Double.POSITIVE_INFINITY);
		for(int index = 1; index < choices.size(); index++) {
			var next = circuit.variable(key + "|chosen-count=" + index, 3);
			circuit.factor(List.of(count, choices.get(index), next), values ->
				values[2] == Math.min(2, values[0] + values[1]) ? 0d : Double.POSITIVE_INFINITY);
			count = next;
		}
		circuit.factor(List.of(active, terminal, count), values -> {
			int expected = values[0] != 0 && values[1] == 0 ? 1 : 0;
			return values[2] == expected ? 0d : Double.POSITIVE_INFINITY;
		});
	}

	private static void strictGreater(Circuit circuit, String key,
		ExactCategoricalSolver.Variable enabled, List<ExactCategoricalSolver.Variable> left,
		List<ExactCategoricalSolver.Variable> right) {
		ExactCategoricalSolver.Variable state = null; // 0 equal, 1 greater, 2 less
		for(int bit = 0; bit < left.size(); bit++) {
			var pair = circuit.variable(key + "|bit=" + bit + "|pair", 4);
			circuit.factor(ExactCategoricalSolver.Factor.finiteSupport(
				List.of(left.get(bit), right.get(bit), pair), 0, 5, 10, 15));
			var next = circuit.variable(key + "|bit=" + bit + "|state", 3);
			if(state == null)
				circuit.factor(ExactCategoricalSolver.Factor.functionalMap(
					pair, next, new int[] {0, 2, 1, 0}));
			else
				circuit.factor(ExactCategoricalSolver.Factor.finiteSupport(
					List.of(state, pair, next), COMPARATOR_TRANSITIONS));
			state = next;
		}
		ExactCategoricalSolver.Variable terminalState = state;
		circuit.factor(List.of(enabled, terminalState), values ->
			values[0] == 0 || values[1] == 1 ? 0d : Double.POSITIVE_INFINITY);
	}

	// Row-major [previous state, bit pair, next state], with states equal/greater/less.
	// Equal compares the bit pair; a prior strict comparison remains unchanged.
	private static final int[] COMPARATOR_TRANSITIONS = {
		0, 5, 7, 9, 13, 16, 19, 22, 26, 29, 32, 35
	};

	private static List<List<Integer>> adjacency(List<QueryPlan> plans,
		Map<QueryKey,Integer> indexes) {
		List<List<Integer>> result = new ArrayList<>(plans.size());
		for(QueryPlan plan : plans) {
			Set<Integer> children = new LinkedHashSet<>();
			for(EdgeKey edge : plan.edges())
				children.add(requireIndex(indexes, edge.child()));
			result.add(List.copyOf(children));
		}
		return result;
	}

	private static List<List<Integer>> stronglyConnectedComponents(List<List<Integer>> adjacency) {
		int size = adjacency.size();
		int[] index = new int[size];
		int[] low = new int[size];
		Arrays.fill(index, -1);
		boolean[] onStack = new boolean[size];
		Deque<Integer> stack = new ArrayDeque<>();
		List<List<Integer>> result = new ArrayList<>();
		int[] next = {0};
		for(int node = 0; node < size; node++)
			if(index[node] < 0)
				strongConnect(node, adjacency, index, low, onStack, stack, next, result);
		return result;
	}

	private static void strongConnect(int node, List<List<Integer>> adjacency,
		int[] index, int[] low, boolean[] onStack, Deque<Integer> stack, int[] next,
		List<List<Integer>> result) {
		index[node] = low[node] = next[0]++;
		stack.push(node);
		onStack[node] = true;
		for(int child : adjacency.get(node)) {
			if(index[child] < 0) {
				strongConnect(child, adjacency, index, low, onStack, stack, next, result);
				low[node] = Math.min(low[node], low[child]);
			}
			else if(onStack[child])
				low[node] = Math.min(low[node], index[child]);
		}
		if(low[node] != index[node])
			return;
		List<Integer> component = new ArrayList<>();
		int member;
		do {
			member = stack.pop();
			onStack[member] = false;
			component.add(member);
		}
		while(member != node);
		Collections.sort(component);
		result.add(List.copyOf(component));
	}

	private static int requireIndex(Map<QueryKey,Integer> indexes, QueryKey query) {
		Integer result = indexes.get(query);
		if(result == null)
			throw new IllegalStateException("EXACT_DERIVED_FOUT_QUERY_MISSING");
		return result;
	}

	private static long saturatedLong(BigInteger value) {
		return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
			? Long.MAX_VALUE : value.longValueExact();
	}
}
