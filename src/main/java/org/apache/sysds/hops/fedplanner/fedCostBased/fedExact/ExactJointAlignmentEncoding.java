/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthority;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.InputAuthorityKind;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofEdge;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofQuery;
import org.apache.sysds.hops.fedplanner.placement.JointValueMapRelations.Grounding.ProofStep;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.WorkerPoolIdentity;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Small-scope existential worker-pool proof for one canonical joint-alignment predicate. */
final class ExactJointAlignmentEncoding {
	private record QueryPlan(ProofQuery query, DecisionDomain decision,
		List<CandidateSelectionReceipt> receipts, List<ProofStep> steps) { }
	private record EdgeKey(ProofQuery child,
		PlacementIdentity.CandidateRealizationReference guard) {
		String signature() {
			return child.normalizedSignature() + "|guard="
				+ (guard == null ? "-" : guard.normalizedSignature());
		}
	}
	private static final class Circuit {
		private final String key;
		private final List<ExactCategoricalSolver.Variable> variables = new ArrayList<>();
		private final List<ExactCategoricalSolver.Factor> factors = new ArrayList<>();
		private BigInteger encodedCells = BigInteger.ZERO;

		private Circuit(String key) { this.key = key; }

		private ExactCategoricalSolver.Variable variable(String suffix, int domain) {
			var variable = new ExactCategoricalSolver.Variable(
				"exact-joint-pool-witness|" + key + '|' + suffix, domain);
			variables.add(variable);
			return variable;
		}

		private void factor(List<ExactCategoricalSolver.Variable> scope,
			ExactCategoricalSolver.CostFunction evaluator) {
			if(scope.size() > 3)
				throw new IllegalArgumentException("EXACT_JOINT_COMPACT_FACTOR_SCOPE_EXCEEDED");
			if(new HashSet<>(scope).size() != scope.size())
				throw new IllegalArgumentException("EXACT_JOINT_COMPACT_DUPLICATE_FACTOR_VARIABLE");
			factors.add(ExactCategoricalSolver.Factor.lazy(scope, evaluator));
			BigInteger cells = BigInteger.ONE;
			for(var variable : scope)
				cells = cells.multiply(BigInteger.valueOf(variable.domainSize()));
			encodedCells = encodedCells.add(cells);
		}
	}

	private ExactJointAlignmentEncoding() { }

	static ExactHardFactorObservationDecomposition.Result create(String key,
		PlacementAnalysis analysis, JointValueMapRelations.Relation relation,
		JointValueMapRelations.Grounding grounding, DecisionDomain consumer,
		Map<CompiledHopKey,DecisionDomain> domains, ExactCategoricalSolver.Factor canonical) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(relation, "relation");
		Objects.requireNonNull(grounding, "grounding");
		Objects.requireNonNull(consumer, "consumer");
		Objects.requireNonNull(domains, "domains");
		Objects.requireNonNull(canonical, "canonical");

		Set<ExactCategoricalSolver.Variable> canonicalVariables = Set.copyOf(canonical.scope());
		Set<CompiledHopKey> canonicalOwners = new HashSet<>();
		for(var entry : domains.entrySet())
			if(canonicalVariables.contains(entry.getValue().variable()))
				canonicalOwners.add(entry.getKey());
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> receiptTables = new HashMap<>();

		Set<Integer> potentiallyDirectPositions = new HashSet<>();
		for(Alternative alternative : consumer.alternatives())
			for(JointValueMapRelations.InputSource input : relation.rows().get(0).inputs())
				if(demandsDirect(alternative, input.inputPosition()))
					potentiallyDirectPositions.add(input.inputPosition());
		LinkedHashSet<ProofQuery> roots = new LinkedHashSet<>();
		for(JointValueMapRelations.Row row : relation.rows())
			for(JointValueMapRelations.InputSource input : row.inputs()) {
				if(!potentiallyDirectPositions.contains(input.inputPosition()))
					continue;
				DecisionDomain reader = requireDomain(domains, input.reader(), "reader");
				for(CandidateSelectionReceipt receipt : receiptTable(analysis, receiptTables, reader))
					if(receipt != null)
						roots.add(grounding.proofQuery(receipt, input));
			}

		Map<ProofQuery,QueryPlan> discovered = new HashMap<>();
		Deque<ProofQuery> pending = new ArrayDeque<>(roots);
		while(!pending.isEmpty()) {
			ProofQuery query = pending.removeFirst();
			if(discovered.containsKey(query))
				continue;
			if(!canonicalOwners.contains(query.decisionOwner()))
				throw new IllegalStateException("EXACT_JOINT_COMPACT_QUERY_OUTSIDE_CANONICAL_SCOPE");
			DecisionDomain decision = requireDomain(domains, query.decisionOwner(), "proof owner");
			List<CandidateSelectionReceipt> receipts = receiptTable(analysis, receiptTables, decision);
			List<ProofStep> steps = new ArrayList<>(receipts.size());
			for(CandidateSelectionReceipt receipt : receipts) {
				ProofStep step = grounding.proofStep(query, receipt);
				// The canonical evaluator's selected-receipt map contains only its
				// original scope. A projected target alternative that reaches a new
				// owner therefore fails at that child rather than extending the factor.
				if(step.edges().stream().anyMatch(edge ->
					!canonicalOwners.contains(edge.child().decisionOwner())))
					step = new ProofStep(true, null, List.of(), List.of());
				steps.add(step);
				for(ProofEdge edge : step.edges())
					pending.addLast(edge.child());
			}
			discovered.put(query, new QueryPlan(query, decision, receipts, List.copyOf(steps)));
		}

		List<QueryPlan> queries = discovered.values().stream()
			.sorted(Comparator.comparing(plan -> plan.query().normalizedSignature())).toList();
		Map<ProofQuery,Integer> queryIndex = new HashMap<>();
		for(int index = 0; index < queries.size(); index++)
			queryIndex.put(queries.get(index).query(), index);

		Set<WorkerPoolIdentity> poolSet = new HashSet<>();
		for(QueryPlan plan : queries) for(ProofStep step : plan.steps()) {
			addPool(poolSet, step.exactPool());
			step.invariantPools().forEach(pool -> addPool(poolSet, pool));
		}
		for(Alternative alternative : consumer.alternatives())
			for(InputAuthority authority : alternative.inputAuthorities())
				if(authority.kind() == InputAuthorityKind.RELOCATION)
					addPool(poolSet, authority.relocationAction().key().durableAnchor());
		List<WorkerPoolIdentity> pools = poolSet.stream()
			.sorted(Comparator.comparing(ExactJointAlignmentEncoding::poolSignature)).toList();
		Map<WorkerPoolIdentity,Integer> poolCategory = new HashMap<>();
		for(int index = 0; index < pools.size(); index++)
			poolCategory.put(pools.get(index), index + 1); // zero is inactive

		Circuit circuit = new Circuit(key);
		List<ExactCategoricalSolver.Variable> queryPools = new ArrayList<>(queries.size());
		for(int index = 0; index < queries.size(); index++)
			queryPools.add(circuit.variable("query=" + index + "|pool", pools.size() + 1));

		List<List<EdgeKey>> planEdges = new ArrayList<>(queries.size());
		for(int query = 0; query < queries.size(); query++) {
			QueryPlan plan = queries.get(query);
			ExactCategoricalSolver.Variable pool = queryPools.get(query);
			List<EdgeKey> edges = plan.steps().stream().flatMap(step -> step.edges().stream())
				.map(edge -> new EdgeKey(edge.child(), edge.selectedReferenceGuard())).distinct()
				.sorted(Comparator.comparing(EdgeKey::signature)).toList();
			planEdges.add(edges);
			circuit.factor(List.of(plan.decision().variable(), pool), values -> {
				if(values[1] == 0)
					return 0.0;
				ProofStep step = plan.steps().get(values[0]);
				if(step.invalid())
					return Double.POSITIVE_INFINITY;
				if(step.exactPool() != null)
					return values[1] == category(poolCategory, step.exactPool())
						? 0.0 : Double.POSITIVE_INFINITY;
				for(DurableAnchorKey invariant : step.invariantPools())
					if(values[1] != category(poolCategory, invariant))
						return Double.POSITIVE_INFINITY;
				return 0.0;
			});
		}

		List<List<Integer>> adjacency = new ArrayList<>(queries.size());
		for(int query = 0; query < queries.size(); query++) {
			Set<Integer> children = new HashSet<>();
			for(EdgeKey edge : planEdges.get(query))
				children.add(requireQueryIndex(queryIndex, edge.child()));
			adjacency.add(children.stream().sorted().toList());
		}
		List<List<Integer>> components = stronglyConnectedComponents(adjacency);
		int[] componentOf = new int[queries.size()];
		Arrays.fill(componentOf, -1);
		List<ExactCategoricalSolver.Variable> ranks = new ArrayList<>(java.util.Collections.nCopies(
			queries.size(), null));
		Set<Integer> cyclicComponents = new HashSet<>();
		for(int component = 0; component < components.size(); component++) {
			List<Integer> members = components.get(component);
			boolean cyclic = members.size() > 1 || adjacency.get(members.get(0)).contains(members.get(0));
			for(int member : members)
				componentOf[member] = component;
			if(cyclic) {
				cyclicComponents.add(component);
				for(int member : members)
					ranks.set(member, circuit.variable("query=" + member + "|rank", members.size()));
			}
		}

		List<String> edgeDescriptor = new ArrayList<>();
		for(int parent = 0; parent < queries.size(); parent++) {
			QueryPlan plan = queries.get(parent);
			for(int edgeIndex = 0; edgeIndex < planEdges.get(parent).size(); edgeIndex++) {
				EdgeKey edge = planEdges.get(parent).get(edgeIndex);
				int child = requireQueryIndex(queryIndex, edge.child());
				var enabled = circuit.variable("query=" + parent + "|edge=" + edgeIndex + "|enabled", 2);
				circuit.factor(List.of(plan.decision().variable(), queryPools.get(parent), enabled), values -> {
					ProofStep step = plan.steps().get(values[0]);
					boolean selected = values[1] != 0 && step.edges().stream().anyMatch(candidate ->
						candidate.child().equals(edge.child())
							&& Objects.equals(candidate.selectedReferenceGuard(), edge.guard()));
					return values[2] == (selected ? 1 : 0) ? 0.0 : Double.POSITIVE_INFINITY;
				});
				if(parent == child)
					circuit.factor(List.of(enabled, queryPools.get(parent)), values ->
						values[0] == 0 || values[1] != 0 ? 0.0 : Double.POSITIVE_INFINITY);
				else
					circuit.factor(List.of(enabled, queryPools.get(parent), queryPools.get(child)), values ->
						values[0] == 0 || values[1] != 0 && values[1] == values[2]
							? 0.0 : Double.POSITIVE_INFINITY);
				if(edge.guard() != null) {
					QueryPlan childPlan = queries.get(child);
					circuit.factor(List.of(enabled, childPlan.decision().variable()), values ->
						values[0] == 0 || CandidateSelections.matchesRealization(
							edge.guard(), childPlan.receipts().get(values[1]))
							? 0.0 : Double.POSITIVE_INFINITY);
				}
				if(componentOf[parent] == componentOf[child]
					&& cyclicComponents.contains(componentOf[parent])) {
					if(parent == child)
						circuit.factor(List.of(enabled, ranks.get(parent)), values ->
							values[0] == 0 ? 0.0 : Double.POSITIVE_INFINITY);
					else
						circuit.factor(List.of(enabled, ranks.get(parent), ranks.get(child)), values ->
							values[0] == 0 || values[1] > values[2]
								? 0.0 : Double.POSITIVE_INFINITY);
				}
				edgeDescriptor.add(parent + ":" + edgeIndex + "->" + child + ':' + edge.signature());
			}
		}

		List<ExactCategoricalSolver.Variable> activationPrefixes = new ArrayList<>();
		for(int readerIndex = 0; readerIndex < relation.readers().size(); readerIndex++) {
			CompiledHopKey readerKey = relation.readers().get(readerIndex);
			DecisionDomain reader = requireDomain(domains, readerKey, "reader activation");
			List<CandidateSelectionReceipt> receipts = receiptTable(analysis, receiptTables, reader);
			var prefix = circuit.variable("activation-prefix=" + readerIndex, 2);
			activationPrefixes.add(prefix);
			if(readerIndex == 0)
				circuit.factor(List.of(reader.variable(), prefix), values ->
					values[1] == (valueMapped(receipts.get(values[0])) ? 1 : 0)
						? 0.0 : Double.POSITIVE_INFINITY);
			else {
				var previous = activationPrefixes.get(readerIndex - 1);
				circuit.factor(List.of(previous, reader.variable(), prefix), values -> {
					boolean active = values[0] != 0 || valueMapped(receipts.get(values[1]));
					return values[2] == (active ? 1 : 0) ? 0.0 : Double.POSITIVE_INFINITY;
				});
			}
		}
		ExactCategoricalSolver.Variable active = activationPrefixes.get(activationPrefixes.size() - 1);

		List<String> rootDescriptor = new ArrayList<>();
		for(int rowIndex = 0; rowIndex < relation.rows().size(); rowIndex++) {
			JointValueMapRelations.Row row = relation.rows().get(rowIndex);
			var commonPool = circuit.variable("row=" + rowIndex + "|common-pool", pools.size() + 1);
			circuit.factor(List.of(consumer.variable(), active, commonPool), values ->
				rowPoolCost(consumer.alternatives().get(values[0]), values[1] != 0,
					values[2], poolCategory));
			for(JointValueMapRelations.InputSource input : row.inputs()) {
				int position = input.inputPosition();
				boolean potentiallyDirect = consumer.alternatives().stream()
					.anyMatch(alternative -> demandsDirect(alternative, position));
				if(!potentiallyDirect)
					continue;
				var demanded = circuit.variable("row=" + rowIndex + "|position=" + position + "|demanded", 2);
				circuit.factor(List.of(consumer.variable(), active, demanded), values -> {
					boolean expected = values[1] != 0
						&& demandsDirect(consumer.alternatives().get(values[0]), position);
					return values[2] == (expected ? 1 : 0) ? 0.0 : Double.POSITIVE_INFINITY;
				});
				DecisionDomain reader = requireDomain(domains, input.reader(), "row reader");
				List<CandidateSelectionReceipt> receipts = receiptTable(analysis, receiptTables, reader);
				circuit.factor(List.of(demanded, reader.variable()), values ->
					values[0] == 0 || receipts.get(values[1]) != null
						? 0.0 : Double.POSITIVE_INFINITY);
				Map<ProofQuery,List<Integer>> alternativesByRoot = new HashMap<>();
				for(int alternative = 0; alternative < receipts.size(); alternative++) {
					CandidateSelectionReceipt receipt = receipts.get(alternative);
					if(receipt != null)
						alternativesByRoot.computeIfAbsent(grounding.proofQuery(receipt, input), ignored ->
							new ArrayList<>()).add(alternative);
				}
				List<ProofQuery> positionRoots = alternativesByRoot.keySet().stream()
					.sorted(Comparator.comparing(ProofQuery::normalizedSignature)).toList();
				for(int rootIndex = 0; rootIndex < positionRoots.size(); rootIndex++) {
					ProofQuery root = positionRoots.get(rootIndex);
					Set<Integer> selecting = Set.copyOf(alternativesByRoot.get(root));
					var enabled = circuit.variable("row=" + rowIndex + "|position=" + position
						+ "|root=" + rootIndex + "|enabled", 2);
					circuit.factor(List.of(demanded, reader.variable(), enabled), values -> {
						boolean expected = values[0] != 0 && selecting.contains(values[1]);
						return values[2] == (expected ? 1 : 0) ? 0.0 : Double.POSITIVE_INFINITY;
					});
					int query = requireQueryIndex(queryIndex, root);
					circuit.factor(List.of(enabled, commonPool, queryPools.get(query)), values ->
						values[0] == 0 || values[1] != 0 && values[1] == values[2]
							? 0.0 : Double.POSITIVE_INFINITY);
					rootDescriptor.add("row=" + rowIndex + "|position=" + position + "|root="
						+ root.normalizedSignature() + "|query=" + query
						+ "|alternatives=" + selecting.stream().sorted().toList());
				}
			}
		}

		BigInteger canonicalCells = BigInteger.ONE;
		for(var variable : canonical.scope())
			canonicalCells = canonicalCells.multiply(BigInteger.valueOf(variable.domainSize()));
		String descriptor = descriptor(key, relation, queries, pools, components, edgeDescriptor,
			consumer, receiptTables, rootDescriptor, canonical, canonicalCells, circuit.encodedCells);
		return new ExactHardFactorObservationDecomposition.Result(circuit.variables, circuit.factors,
			saturatedLong(canonicalCells), saturatedLong(circuit.encodedCells), List.of(), descriptor);
	}

	private static CandidateSelectionReceipt candidateReceipt(PlacementAnalysis analysis,
		Alternative alternative) {
		var rule = alternative.captured() ? alternative.candidateRule() : alternative.executionRule();
		var emission = alternative.captured() ? alternative.candidateEmission() : alternative.executionEmission();
		return rule == null || emission == null || alternative.realization() == null ? null
			: analysis.canonicalCandidateReceipt(rule.key(), emission, alternative.realization(),
				alternative.supportClause());
	}

	private static List<CandidateSelectionReceipt> receiptTable(PlacementAnalysis analysis,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> tables, DecisionDomain domain) {
		return tables.computeIfAbsent(domain.node().key(), ignored -> domain.alternatives().stream()
			.map(alternative -> candidateReceipt(analysis, alternative)).toList());
	}

	private static DecisionDomain requireDomain(Map<CompiledHopKey,DecisionDomain> domains,
		CompiledHopKey owner, String role) {
		DecisionDomain domain = domains.get(owner);
		if(domain == null)
			throw new IllegalArgumentException("Joint " + role + " decision domain missing: "
				+ owner.normalizedSignature());
		return domain;
	}

	private static void addPool(Set<WorkerPoolIdentity> pools, DurableAnchorKey anchor) {
		if(anchor == null)
			return;
		pools.add(PlacementIdentity.physicalWorkerPoolIdentity(anchor));
	}

	private static String poolSignature(WorkerPoolIdentity pool) {
		return pool.type() + "|members=" + pool.members() + "|identity="
			+ (pool.identityOnly() == null ? "-" : pool.identityOnly().normalizedSignature());
	}

	private static int category(Map<WorkerPoolIdentity,Integer> categories, DurableAnchorKey anchor) {
		Integer category = categories.get(PlacementIdentity.physicalWorkerPoolIdentity(anchor));
		if(category == null)
			throw new IllegalStateException("EXACT_JOINT_COMPACT_POOL_CATEGORY_MISSING");
		return category;
	}

	private static int requireQueryIndex(Map<ProofQuery,Integer> indices, ProofQuery query) {
		Integer index = indices.get(query);
		if(index == null)
			throw new IllegalStateException("EXACT_JOINT_COMPACT_QUERY_MISSING");
		return index;
	}

	private static boolean valueMapped(CandidateSelectionReceipt receipt) {
		return receipt != null && receipt.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP;
	}

	private static boolean bypass(Alternative alternative) {
		return alternative.state().execType() == ExecType.CP
			&& alternative.state().output() == FederatedOutput.LOUT;
	}

	private static Set<Integer> physicalPositions(Alternative alternative) {
		Set<Integer> positions = new HashSet<>();
		for(InputAuthority authority : alternative.inputAuthorities())
			if(authority.kind() == InputAuthorityKind.DIRECT_FOUT
				|| authority.kind() == InputAuthorityKind.RELOCATION)
				positions.add(authority.inputPosition());
		return positions;
	}

	private static boolean demandsDirect(Alternative alternative, int position) {
		if(bypass(alternative) || alternative.realization() == null
			|| physicalPositions(alternative).size() <= 1)
			return false;
		return alternative.inputAuthorities().stream().anyMatch(authority ->
			authority.inputPosition() == position && authority.kind() == InputAuthorityKind.DIRECT_FOUT);
	}

	private static double rowPoolCost(Alternative alternative, boolean active, int selectedPool,
		Map<WorkerPoolIdentity,Integer> poolCategory) {
		if(!active || bypass(alternative))
			return selectedPool == 0 ? 0.0 : Double.POSITIVE_INFINITY;
		if(alternative.realization() == null)
			return Double.POSITIVE_INFINITY;
		if(physicalPositions(alternative).size() <= 1)
			return selectedPool == 0 ? 0.0 : Double.POSITIVE_INFINITY;
		if(selectedPool == 0)
			return Double.POSITIVE_INFINITY;
		for(InputAuthority authority : alternative.inputAuthorities())
			if(authority.kind() == InputAuthorityKind.RELOCATION
				&& selectedPool != category(poolCategory, authority.relocationAction().key().durableAnchor()))
				return Double.POSITIVE_INFINITY;
		return 0.0;
	}

	private static List<List<Integer>> stronglyConnectedComponents(List<List<Integer>> adjacency) {
		int size = adjacency.size();
		boolean[] visited = new boolean[size];
		List<Integer> finish = new ArrayList<>(size);
		for(int start = 0; start < size; start++) {
			if(visited[start]) continue;
			Deque<int[]> stack = new ArrayDeque<>();
			visited[start] = true;
			stack.push(new int[] {start, 0});
			while(!stack.isEmpty()) {
				int[] frame = stack.peek();
				List<Integer> children = adjacency.get(frame[0]);
				if(frame[1] < children.size()) {
					int child = children.get(frame[1]++);
					if(!visited[child]) {
						visited[child] = true;
						stack.push(new int[] {child, 0});
					}
				}
				else {
					finish.add(frame[0]);
					stack.pop();
				}
			}
		}
		List<List<Integer>> reverse = new ArrayList<>(size);
		for(int index = 0; index < size; index++) reverse.add(new ArrayList<>());
		for(int parent = 0; parent < size; parent++)
			for(int child : adjacency.get(parent)) reverse.get(child).add(parent);
		for(List<Integer> parents : reverse) parents.sort(Integer::compareTo);
		Arrays.fill(visited, false);
		List<List<Integer>> components = new ArrayList<>();
		for(int order = finish.size() - 1; order >= 0; order--) {
			int start = finish.get(order);
			if(visited[start]) continue;
			List<Integer> component = new ArrayList<>();
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(start);
			visited[start] = true;
			while(!stack.isEmpty()) {
				int node = stack.pop();
				component.add(node);
				for(int parent : reverse.get(node)) if(!visited[parent]) {
					visited[parent] = true;
					stack.push(parent);
				}
			}
			component.sort(Integer::compareTo);
			components.add(List.copyOf(component));
		}
		components.sort(Comparator.comparingInt(component -> component.get(0)));
		return List.copyOf(components);
	}

	private static String descriptor(String key, JointValueMapRelations.Relation relation,
		List<QueryPlan> queries, List<WorkerPoolIdentity> pools, List<List<Integer>> components,
		List<String> edges, DecisionDomain consumer,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> receipts, List<String> roots,
		ExactCategoricalSolver.Factor canonical, BigInteger canonicalCells, BigInteger encodedCells) {
		List<String> transitions = new ArrayList<>();
		for(int query = 0; query < queries.size(); query++) {
			QueryPlan plan = queries.get(query);
			for(int alternative = 0; alternative < plan.steps().size(); alternative++)
				transitions.add(query + ":" + alternative + ':' + plan.steps().get(alternative).normalizedSignature());
		}
		List<String> activation = relation.readers().stream().map(reader -> reader.normalizedSignature()
			+ '=' + receipts.get(reader).stream().map(receipt -> receipt == null ? "NULL"
				: receipt.normalizedSignature() + ":vm=" + valueMapped(receipt)).toList()).toList();
		List<String> consumerRows = consumer.alternatives().stream().map(alternative ->
			alternative.signature() + "|state=" + alternative.state().normalizedSignature() + "|realization="
				+ (alternative.realization() == null ? "-" : alternative.realization().key().normalizedSignature())
				+ "|authorities=" + alternative.inputAuthorities().stream().map(InputAuthority::signature).toList())
			.toList();
		return key + "|encoding=pool-witness|relation=" + relation.normalizedSignature()
			+ "|canonicalScope=" + canonical.scope().stream()
				.map(variable -> variable.key() + ':' + variable.domainSize()).toList()
			+ "|canonicalCells=" + canonicalCells + "|encodedCells=" + encodedCells
			+ "|pools=" + pools.stream().map(ExactJointAlignmentEncoding::poolSignature).toList()
			+ "|queries=" + queries.stream().map(plan -> plan.query().normalizedSignature()).toList()
			+ "|transitions=" + transitions + "|edges=" + edges + "|sccs=" + components
			+ "|activation=" + activation + "|consumer=" + consumerRows + "|roots=" + roots;
	}

	private static long saturatedLong(BigInteger value) {
		return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
			? Long.MAX_VALUE : value.longValueExact();
	}
}
