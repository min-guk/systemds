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
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds exact finite activation classes when control-flow events form a proven tree. */
final class ExactMaterializationActivation {
	private ExactMaterializationActivation() { }

	record Event(double weight, List<ExactPhysicalCostModel.BranchLiteral> conditions) {
		Event {
			if(!Double.isFinite(weight) || weight < 0d
				|| Double.doubleToRawLongBits(weight) == Double.doubleToRawLongBits(-0d))
				throw new IllegalArgumentException("EXACT_MATERIALIZATION_EVENT_WEIGHT_INVALID");
			List<ExactPhysicalCostModel.BranchLiteral> sorted = new ArrayList<>(
				Objects.requireNonNull(conditions, "conditions"));
			sorted.sort(Comparator.naturalOrder());
			List<ExactPhysicalCostModel.BranchLiteral> normalized = new ArrayList<>();
			for(ExactPhysicalCostModel.BranchLiteral literal : sorted) {
				Objects.requireNonNull(literal, "condition");
				if(!normalized.isEmpty()) {
					ExactPhysicalCostModel.BranchLiteral previous =
						normalized.get(normalized.size() - 1);
					if(previous.decisionPath().equals(literal.decisionPath())) {
						if(previous.ifArm() != literal.ifArm())
							throw new IllegalArgumentException(
								"EXACT_MATERIALIZATION_EVENT_CONTRADICTORY");
						continue;
					}
				}
				normalized.add(literal);
			}
			conditions = List.copyOf(normalized);
		}
	}

	record ActivationClass(double multiplicity, List<Integer> demandIndexes) {
		ActivationClass {
			if(!Double.isFinite(multiplicity) || multiplicity <= 0d)
				throw new IllegalArgumentException(
					"EXACT_MATERIALIZATION_CLASS_MULTIPLICITY_INVALID");
			demandIndexes = List.copyOf(demandIndexes);
		}
	}

	record Partition(List<ActivationClass> classes, boolean resolved,
		String semanticDescriptor) {
		Partition {
			classes = List.copyOf(classes);
			if(semanticDescriptor == null || semanticDescriptor.isBlank())
				throw new IllegalArgumentException(
					"EXACT_MATERIALIZATION_PARTITION_DESCRIPTOR_INVALID");
			if(!resolved && !classes.isEmpty())
				throw new IllegalArgumentException(
					"EXACT_MATERIALIZATION_UNRESOLVED_CLASSES_INVALID");
		}
	}

	static Partition partition(List<Event> inputEvents, double scopeWeight) {
		List<Event> events = validatedEvents(inputEvents, scopeWeight);
		String inputDescriptor = inputDescriptor(events, scopeWeight);
		Map<EventKey,Node> unique = new LinkedHashMap<>();
		for(int index = 0; index < events.size(); index++) {
			Event event = events.get(index);
			if(event.weight == 0d)
				continue;
			EventKey key = new EventKey(event.weight, event.conditions);
			Node node = unique.get(key);
			if(node == null) {
				node = new Node(event.weight, event.conditions);
				unique.put(key, node);
			}
			node.directDemandIndexes.add(index);
		}
		List<Node> nodes = new ArrayList<>(unique.values());
		nodes.sort(NODE_ORDER);
		for(int left = 0; left < nodes.size(); left++)
			for(int right = left + 1; right < nodes.size(); right++) {
				Node first = nodes.get(left);
				Node second = nodes.get(right);
				if(!contains(first, second) && !contains(second, first)
					&& !mutuallyExclusive(first.conditions, second.conditions))
					return unresolved(inputDescriptor, "UNPROVEN_OVERLAP");
			}

		Node root = new Node(scopeWeight, List.of());
		for(Node node : nodes) {
			List<Node> candidates = nodes.stream()
				.filter(candidate -> candidate != node && contains(candidate, node)).toList();
			Node parent = root;
			for(Node candidate : candidates) {
				boolean closest = candidates.stream().noneMatch(other -> other != candidate
					&& contains(candidate, other) && contains(other, node));
				if(!closest)
					continue;
				if(parent != root)
					return unresolved(inputDescriptor, "AMBIGUOUS_PARENT");
				parent = candidate;
			}
			parent.children.add(node);
		}
		sortChildren(root);
		for(Node node : allNodes(root)) {
			double childWeight = node.children.stream().mapToDouble(child -> child.weight).sum();
			if(childWeight > node.weight)
				return unresolved(inputDescriptor, "CHILD_WEIGHT_EXCEEDS_PARENT");
		}

		List<ActivationClass> classes = new ArrayList<>();
		appendClasses(root, List.of(), classes);
		String descriptor = "EXACT_ACTIVATION_CLASS_PARTITION_V1|" + inputDescriptor
			+ "|classes=" + classes.stream().map(activationClass ->
				rawBits(activationClass.multiplicity) + ':' + activationClass.demandIndexes)
				.toList();
		return new Partition(classes, true, descriptor);
	}

	static double conservativeUnion(List<Event> inputEvents, double scopeWeight,
		boolean[] active) {
		List<Event> events = validatedEvents(inputEvents, scopeWeight);
		boolean[] selected = Objects.requireNonNull(active, "active").clone();
		if(selected.length != events.size())
			throw new IllegalArgumentException(
				"EXACT_MATERIALIZATION_ACTIVE_SIZE_MISMATCH");
		Map<EventKey,Node> merged = new LinkedHashMap<>();
		for(int index = 0; index < events.size(); index++) {
			Event event = events.get(index);
			if(selected[index] && event.weight > 0d) {
				EventKey key = new EventKey(event.weight, event.conditions);
				merged.putIfAbsent(key, new Node(event.weight, event.conditions));
			}
		}
		List<Node> ordered = new ArrayList<>(merged.values());
		ordered.sort(CONSERVATIVE_EVENT_ORDER);
		List<Node> minimal = new ArrayList<>();
		for(Node event : ordered) {
			boolean subsumed = minimal.stream().anyMatch(broader ->
				event.conditions.size() > broader.conditions.size()
					&& event.conditions.containsAll(broader.conditions)
					&& broader.weight >= event.weight);
			if(!subsumed)
				minimal.add(event);
		}
		return Math.min(scopeWeight, minimal.stream().mapToDouble(node -> node.weight).sum());
	}

	private static List<Event> validatedEvents(List<Event> inputEvents, double scopeWeight) {
		if(!Double.isFinite(scopeWeight) || scopeWeight < 0d
			|| Double.doubleToRawLongBits(scopeWeight) == Double.doubleToRawLongBits(-0d))
			throw new IllegalArgumentException("EXACT_MATERIALIZATION_SCOPE_WEIGHT_INVALID");
		List<Event> events = List.copyOf(Objects.requireNonNull(inputEvents, "events"));
		for(Event event : events) {
			Objects.requireNonNull(event, "event");
			if(event.weight > scopeWeight)
				throw new IllegalArgumentException(
					"EXACT_MATERIALIZATION_EVENT_EXCEEDS_SCOPE");
		}
		return events;
	}

	private static Partition unresolved(String inputDescriptor, String reason) {
		return new Partition(List.of(), false, "CONSERVATIVE_UNION_BOUND_V1|"
			+ inputDescriptor + "|reason=" + reason + "|independenceAssumed=false");
	}

	private static boolean contains(Node broader, Node narrower) {
		// Equal literal sets carry no witness for why frequencies differ. A proper
		// condition subset is the explicit control-flow implication used as proof.
		if(broader.conditions.equals(narrower.conditions))
			return false;
		return narrower.conditions.size() > broader.conditions.size()
			&& narrower.conditions.containsAll(broader.conditions)
			&& broader.weight >= narrower.weight;
	}

	private static boolean mutuallyExclusive(
		List<ExactPhysicalCostModel.BranchLiteral> left,
		List<ExactPhysicalCostModel.BranchLiteral> right) {
		for(ExactPhysicalCostModel.BranchLiteral literal : left)
			for(ExactPhysicalCostModel.BranchLiteral candidate : right)
				if(candidate.decisionPath().equals(literal.decisionPath())
					&& candidate.ifArm() != literal.ifArm())
					return true;
		return false;
	}

	private static void appendClasses(Node node, List<Integer> ancestorIndexes,
		List<ActivationClass> classes) {
		List<Integer> activeIndexes = new ArrayList<>(ancestorIndexes);
		activeIndexes.addAll(node.directDemandIndexes);
		activeIndexes.sort(Integer::compareTo);
		double childWeight = node.children.stream().mapToDouble(child -> child.weight).sum();
		double residual = node.weight - childWeight;
		if(residual > 0d && !activeIndexes.isEmpty())
			classes.add(new ActivationClass(residual, activeIndexes));
		for(Node child : node.children)
			appendClasses(child, activeIndexes, classes);
	}

	private static void sortChildren(Node node) {
		node.children.sort(NODE_ORDER);
		for(Node child : node.children)
			sortChildren(child);
	}

	private static List<Node> allNodes(Node root) {
		List<Node> result = new ArrayList<>();
		result.add(root);
		for(int index = 0; index < result.size(); index++)
			result.addAll(result.get(index).children);
		return result;
	}

	private static String inputDescriptor(List<Event> events, double scopeWeight) {
		StringBuilder descriptor = new StringBuilder("scope=").append(rawBits(scopeWeight));
		for(int index = 0; index < events.size(); index++) {
			Event event = events.get(index);
			descriptor.append("|event=").append(index).append(":weight=")
				.append(rawBits(event.weight)).append(":conditions=")
				.append(event.conditions.stream().map(literal -> literal.decisionPath()
					+ '=' + (literal.ifArm() ? "if" : "else")).toList());
		}
		return descriptor.toString();
	}

	private static String rawBits(double value) {
		return Long.toUnsignedString(Double.doubleToRawLongBits(value), 16);
	}

	private static final Comparator<Node> NODE_ORDER = Comparator
		.comparing((Node node) -> node.conditions, ExactMaterializationActivation::compareConditions)
		.thenComparing((Node node) -> node.weight, Comparator.reverseOrder())
		.thenComparing(node -> node.directDemandIndexes.toString());

	private static final Comparator<Node> CONSERVATIVE_EVENT_ORDER = Comparator
			.comparingInt((Node node) -> node.conditions.size())
			.thenComparing(node -> node.conditions,
				ExactMaterializationActivation::compareConditions)
			.thenComparing((Node node) -> node.weight, Comparator.reverseOrder());

	private static int compareConditions(
		List<ExactPhysicalCostModel.BranchLiteral> left,
		List<ExactPhysicalCostModel.BranchLiteral> right) {
		for(int index = 0; index < Math.min(left.size(), right.size()); index++) {
			int compared = left.get(index).compareTo(right.get(index));
			if(compared != 0)
				return compared;
		}
		return Integer.compare(left.size(), right.size());
	}

	private record EventKey(double weight,
		List<ExactPhysicalCostModel.BranchLiteral> conditions) { }

	private static final class Node {
		private final double weight;
		private final List<ExactPhysicalCostModel.BranchLiteral> conditions;
		private final List<Integer> directDemandIndexes = new ArrayList<>();
		private final List<Node> children = new ArrayList<>();
		private Node(double weight,
			List<ExactPhysicalCostModel.BranchLiteral> conditions) {
			this.weight = weight;
			this.conditions = conditions;
		}
	}
}
