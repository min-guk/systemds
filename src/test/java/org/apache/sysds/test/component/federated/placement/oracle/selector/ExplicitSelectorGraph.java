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

package org.apache.sysds.test.component.federated.placement.oracle.selector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A deliberately small, test-only selector input.  This model has no dependency on either a
 * production placement-graph builder or a production selector.  Tests spell out every node,
 * choice, edge, and legality relation directly.
 */
public final class ExplicitSelectorGraph {
	public enum Execution {
		CP,
		FED
	}

	public enum Output {
		LOUT,
		FOUT
	}

	public static final class Choice {
		private final String id;
		private final Execution execution;
		private final Output output;
		private final Set<String> relocationActions;

		private Choice(String id, Execution execution, Output output, Collection<String> relocationActions) {
			this.id = requireIdentifier(id, "choice");
			this.execution = Objects.requireNonNull(execution);
			this.output = Objects.requireNonNull(output);
			this.relocationActions = Collections.unmodifiableSet(new LinkedHashSet<>(relocationActions));
		}

		public static Choice of(String id, Execution execution, Output output, String... relocationActions) {
			List<String> actions = new ArrayList<>();
			Collections.addAll(actions, relocationActions);
			return new Choice(id, execution, output, actions);
		}

		public String getId() {
			return id;
		}

		public Execution getExecution() {
			return execution;
		}

		public Output getOutput() {
			return output;
		}

		public Set<String> getRelocationActions() {
			return relocationActions;
		}
	}

	public static final class Node {
		private final String id;
		private final List<Choice> choices;
		private final Set<String> heuristicAllowedChoiceIds;

		private Node(String id, Collection<Choice> choices, Collection<String> heuristicAllowedChoiceIds) {
			this.id = requireIdentifier(id, "node");
			if (choices.isEmpty())
				throw new IllegalArgumentException("node must have at least one choice: " + id);
			List<Choice> sorted = new ArrayList<>(choices);
			sorted.sort(Comparator.comparing(Choice::getId));
			Set<String> ids = new HashSet<>();
			for (Choice choice : sorted) {
				if (!ids.add(choice.getId()))
					throw new IllegalArgumentException("duplicate choice " + choice.getId() + " for " + id);
			}
			this.choices = Collections.unmodifiableList(sorted);
			this.heuristicAllowedChoiceIds = Collections.unmodifiableSet(new LinkedHashSet<>(heuristicAllowedChoiceIds));
			if (!ids.containsAll(this.heuristicAllowedChoiceIds))
				throw new IllegalArgumentException("heuristic policy references an unknown choice for " + id);
		}

		public static Node of(String id, Choice... choices) {
			List<Choice> values = List.of(choices);
			List<String> allChoiceIds = new ArrayList<>();
			for (Choice choice : values)
				allChoiceIds.add(choice.getId());
			return new Node(id, values, allChoiceIds);
		}

		public static Node heuristicRestricted(String id, Collection<String> allowedChoiceIds, Choice... choices) {
			return new Node(id, List.of(choices), allowedChoiceIds);
		}

		public String getId() {
			return id;
		}

		public List<Choice> getChoices() {
			return choices;
		}

		public boolean isHeuristicAllowed(Choice choice) {
			return heuristicAllowedChoiceIds.contains(choice.getId());
		}
	}

	public interface Constraint {
		boolean isSatisfied(Map<String, Choice> assignment);

		boolean canStillBeSatisfied(Map<String, Choice> partialAssignment);
	}

	public static final class Builder {
		private final String sizeClass;
		private final long seed;
		private final Map<String, Node> nodes = new LinkedHashMap<>();
		private final Set<Edge> edges = new LinkedHashSet<>();
		private final List<Constraint> constraints = new ArrayList<>();

		public Builder(String sizeClass, long seed) {
			this.sizeClass = requireIdentifier(sizeClass, "size class");
			this.seed = seed;
		}

		public Builder addNode(Node node) {
			if (nodes.putIfAbsent(node.getId(), node) != null)
				throw new IllegalArgumentException("duplicate node: " + node.getId());
			return this;
		}

		public Builder addEdge(String from, String to) {
			edges.add(new Edge(from, to));
			return this;
		}

		public Builder addConstraint(Constraint constraint) {
			constraints.add(Objects.requireNonNull(constraint));
			return this;
		}

		public ExplicitSelectorGraph build() {
			for (Edge edge : edges) {
				if (!nodes.containsKey(edge.from) || !nodes.containsKey(edge.to))
					throw new IllegalArgumentException("edge references unknown node: " + edge);
			}
			return new ExplicitSelectorGraph(sizeClass, seed, nodes.values(), edges, constraints);
		}
	}

	private static final class Edge {
		private final String from;
		private final String to;

		private Edge(String from, String to) {
			this.from = requireIdentifier(from, "edge source");
			this.to = requireIdentifier(to, "edge target");
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof Edge))
				return false;
			Edge that = (Edge) obj;
			return from.equals(that.from) && to.equals(that.to);
		}

		@Override
		public int hashCode() {
			return Objects.hash(from, to);
		}

		@Override
		public String toString() {
			return from + "->" + to;
		}
	}

	private final String sizeClass;
	private final long seed;
	private final List<Node> nodes;
	private final List<Constraint> constraints;
	private final Set<Edge> edges;

	private ExplicitSelectorGraph(String sizeClass, long seed, Collection<Node> nodes, Collection<Edge> edges,
		Collection<Constraint> constraints) {
		List<Node> sortedNodes = new ArrayList<>(nodes);
		sortedNodes.sort(Comparator.comparing(Node::getId));
		this.nodes = Collections.unmodifiableList(sortedNodes);
		this.edges = Collections.unmodifiableSet(new LinkedHashSet<>(edges));
		this.constraints = Collections.unmodifiableList(new ArrayList<>(constraints));
		this.sizeClass = sizeClass;
		this.seed = seed;
	}

	public List<Node> getNodes() {
		return nodes;
	}

	public String getSizeClass() {
		return sizeClass;
	}

	public long getSeed() {
		return seed;
	}

	public int getEdgeCount() {
		return edges.size();
	}

	public int getComponentCount() {
		Map<String, Set<String>> adjacency = new HashMap<>();
		for (Node node : nodes)
			adjacency.put(node.getId(), new HashSet<>());
		for (Edge edge : edges) {
			adjacency.get(edge.from).add(edge.to);
			adjacency.get(edge.to).add(edge.from);
		}
		Set<String> visited = new HashSet<>();
		int components = 0;
		for (Node node : nodes) {
			if (!visited.add(node.getId()))
				continue;
			components++;
			Deque<String> pending = new ArrayDeque<>();
			pending.add(node.getId());
			while (!pending.isEmpty()) {
				for (String adjacent : adjacency.get(pending.removeFirst())) {
					if (visited.add(adjacent))
						pending.addLast(adjacent);
				}
			}
		}
		return components;
	}

	/** Normalized complete legal universe for cross-model fixture-isomorphism tests only. */
	public List<String> normalizedLegalAssignments() {
		List<String> result = new ArrayList<>();
		enumerateLegalAssignments(0, new LinkedHashMap<>(), result);
		Collections.sort(result);
		return Collections.unmodifiableList(result);
	}

	private void enumerateLegalAssignments(int index, Map<String,Choice> assignment, List<String> result) {
		if(index == nodes.size()) {
			for(Constraint constraint : constraints)
				if(!constraint.isSatisfied(assignment))
					return;
			List<String> entries = new ArrayList<>();
			assignment.forEach((node, choice) -> entries.add(node + '=' + choice.getId()));
			Collections.sort(entries);
			result.add(String.join("|", entries));
			return;
		}
		Node node = nodes.get(index);
		for(Choice choice : node.getChoices()) {
			assignment.put(node.getId(), choice);
			boolean feasible = true;
			for(Constraint constraint : constraints)
				if(!constraint.canStillBeSatisfied(assignment)) {
					feasible = false;
					break;
				}
			if(feasible)
				enumerateLegalAssignments(index + 1, assignment, result);
		}
		assignment.remove(node.getId());
	}

	public boolean canStillBeLegal(Map<String, Choice> partialAssignment) {
		for (Constraint constraint : constraints) {
			if (!constraint.canStillBeSatisfied(partialAssignment))
				return false;
		}
		return true;
	}

	public boolean isLegal(Map<String, Choice> assignment) {
		if (assignment.size() != nodes.size())
			return false;
		for (Node node : nodes) {
			Choice selected = assignment.get(node.getId());
			if (selected == null || !node.getChoices().contains(selected))
				return false;
		}
		for (Constraint constraint : constraints) {
			if (!constraint.isSatisfied(assignment))
				return false;
		}
		return true;
	}

	public static Constraint forbidPair(String leftNode, String leftChoice, String rightNode, String rightChoice) {
		return new Constraint() {
			@Override
			public boolean isSatisfied(Map<String, Choice> assignment) {
				return canStillBeSatisfied(assignment);
			}

			@Override
			public boolean canStillBeSatisfied(Map<String, Choice> assignment) {
				Choice left = assignment.get(leftNode);
				Choice right = assignment.get(rightNode);
				return left == null || right == null || !left.getId().equals(leftChoice) || !right.getId().equals(rightChoice);
			}
		};
	}

	public static Constraint requireChoiceWhen(String node, String triggeringChoice, String requiredNode,
		String requiredChoice) {
		return new Constraint() {
			@Override
			public boolean isSatisfied(Map<String, Choice> assignment) {
				Choice trigger = assignment.get(node);
				Choice required = assignment.get(requiredNode);
				return trigger == null || !trigger.getId().equals(triggeringChoice) ||
					(required != null && required.getId().equals(requiredChoice));
			}

			@Override
			public boolean canStillBeSatisfied(Map<String, Choice> assignment) {
				Choice trigger = assignment.get(node);
				Choice required = assignment.get(requiredNode);
				return trigger == null || !trigger.getId().equals(triggeringChoice) || required == null ||
					required.getId().equals(requiredChoice);
			}
		};
	}

	private static String requireIdentifier(String value, String kind) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException(kind + " identifier must not be blank");
		return value;
	}
}
