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
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/**
 * Deterministic SCC schedule for placement dependencies.
 *
 * <p>Owners are occurrences, so all owner lookup and edge de-duplication uses object identity.
 * Semantic dependencies are directed producer-to-consumer edges and alone define SCCs. Alias
 * adjacency is deliberately separate: it widens invalidation, but cannot turn otherwise acyclic
 * owners into a semantic cycle. The owners {@link List} is also the stable occurrence registry:
 * its position breaks ties between structurally equal occurrence identities.</p>
 */
public final class PlacementDependencyComponents {
	/** Identity-valued edge. Deliberately retains Object identity equality. */
	public static final class SemanticDependency {
		private final CompiledHopKey producer;
		private final CompiledHopKey consumer;

		public SemanticDependency(CompiledHopKey producer, CompiledHopKey consumer) {
			this.producer = Objects.requireNonNull(producer, "producer");
			this.consumer = Objects.requireNonNull(consumer, "consumer");
		}

		public CompiledHopKey producer() {
			return producer;
		}

		public CompiledHopKey consumer() {
			return consumer;
		}
	}

	/** Identity-valued adjacency. Deliberately retains Object identity equality. */
	public static final class InvalidationAdjacency {
		private final CompiledHopKey left;
		private final CompiledHopKey right;

		public InvalidationAdjacency(CompiledHopKey left, CompiledHopKey right) {
			this.left = Objects.requireNonNull(left, "left");
			this.right = Objects.requireNonNull(right, "right");
		}

		public CompiledHopKey left() {
			return left;
		}

		public CompiledHopKey right() {
			return right;
		}
	}

	/** A semantic SCC in producer-before-consumer schedule order. */
	public static final class Component {
		private final int topologicalIndex;
		private final List<CompiledHopKey> owners;
		private final boolean cyclic;

		private Component(int topologicalIndex, List<CompiledHopKey> owners, boolean cyclic) {
			this.topologicalIndex = topologicalIndex;
			this.owners = List.copyOf(owners);
			this.cyclic = cyclic;
		}

		public int topologicalIndex() {
			return topologicalIndex;
		}

		public List<CompiledHopKey> owners() {
			return owners;
		}

		public boolean cyclic() {
			return cyclic;
		}
	}

	private static final class DfsFrame {
		private final CompiledHopKey owner;
		private int next;

		private DfsFrame(CompiledHopKey owner) {
			this.owner = owner;
		}
	}

	private final IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> semanticConsumers;
	private final IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> invalidationPeers;
	private final IdentityHashMap<CompiledHopKey,Component> componentByOwner;
	private final IdentityHashMap<Component,List<Component>> immediateSuccessors;
	private final List<Component> topologicalOrder;

	public PlacementDependencyComponents(List<CompiledHopKey> owners,
		List<SemanticDependency> semanticDependencies,
		List<InvalidationAdjacency> invalidationAdjacencies) {
		Objects.requireNonNull(owners, "owners");
		Objects.requireNonNull(semanticDependencies, "semanticDependencies");
		Objects.requireNonNull(invalidationAdjacencies, "invalidationAdjacencies");

		IdentityHashMap<CompiledHopKey,Integer> ordinal = new IdentityHashMap<>();
		List<CompiledHopKey> orderedOwners = new ArrayList<>();
		for(CompiledHopKey owner : owners) {
			Objects.requireNonNull(owner, "owner");
			if(ordinal.put(owner, orderedOwners.size()) != null)
				throw new IllegalArgumentException("owner identity registered more than once");
			orderedOwners.add(owner);
		}
		Comparator<CompiledHopKey> ownerOrder = Comparator.comparing(CompiledHopKey::normalizedSignature)
			.thenComparingInt(ordinal::get);
		orderedOwners.sort(ownerOrder);

		IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>> consumerSets = emptyAdjacencySets(orderedOwners);
		IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>> producerSets = emptyAdjacencySets(orderedOwners);
		IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>> peerSets = emptyAdjacencySets(orderedOwners);
		Set<CompiledHopKey> selfEdges = identitySet();
		for(SemanticDependency dependency : semanticDependencies) {
			Objects.requireNonNull(dependency, "semanticDependency");
			requireOwner(ordinal, dependency.producer());
			requireOwner(ordinal, dependency.consumer());
			consumerSets.get(dependency.producer()).add(dependency.consumer());
			producerSets.get(dependency.consumer()).add(dependency.producer());
			if(dependency.producer() == dependency.consumer())
				selfEdges.add(dependency.producer());
		}
		for(InvalidationAdjacency adjacency : invalidationAdjacencies) {
			Objects.requireNonNull(adjacency, "invalidationAdjacency");
			requireOwner(ordinal, adjacency.left());
			requireOwner(ordinal, adjacency.right());
			peerSets.get(adjacency.left()).add(adjacency.right());
			peerSets.get(adjacency.right()).add(adjacency.left());
		}
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> consumers =
			sortedAdjacency(consumerSets, ownerOrder);
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> producers =
			sortedAdjacency(producerSets, ownerOrder);
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> peers =
			sortedAdjacency(peerSets, ownerOrder);

		List<List<CompiledHopKey>> rawComponents = stronglyConnectedComponents(
			orderedOwners, consumers, producers, ownerOrder);
		IdentityHashMap<CompiledHopKey,Integer> rawComponentByOwner = new IdentityHashMap<>(orderedOwners.size());
		for(int i = 0; i < rawComponents.size(); i++)
			for(CompiledHopKey owner : rawComponents.get(i))
				rawComponentByOwner.put(owner, i);

		List<Set<Integer>> componentConsumers = new ArrayList<>(rawComponents.size());
		int[] indegree = new int[rawComponents.size()];
		for(int i = 0; i < rawComponents.size(); i++)
			componentConsumers.add(new HashSet<>());
		for(CompiledHopKey producer : orderedOwners) {
			int from = rawComponentByOwner.get(producer);
			for(CompiledHopKey consumer : consumers.get(producer)) {
				int to = rawComponentByOwner.get(consumer);
				if(from != to && componentConsumers.get(from).add(to))
					indegree[to]++;
			}
		}
		Comparator<Integer> componentOrder = (left, right) -> ownerOrder.compare(
			rawComponents.get(left).get(0), rawComponents.get(right).get(0));
		PriorityQueue<Integer> ready = new PriorityQueue<>(componentOrder);
		for(int i = 0; i < indegree.length; i++)
			if(indegree[i] == 0)
				ready.add(i);
		List<Integer> scheduled = new ArrayList<>(rawComponents.size());
		while(!ready.isEmpty()) {
			int current = ready.remove();
			scheduled.add(current);
			List<Integer> next = new ArrayList<>(componentConsumers.get(current));
			next.sort(componentOrder);
			for(int consumer : next)
				if(--indegree[consumer] == 0)
					ready.add(consumer);
		}
		if(scheduled.size() != rawComponents.size())
			throw new IllegalStateException("component condensation graph must be acyclic");

		IdentityHashMap<CompiledHopKey,Component> indexed = new IdentityHashMap<>(orderedOwners.size());
		List<Component> schedule = new ArrayList<>(scheduled.size());
		Component[] componentByRawIndex = new Component[rawComponents.size()];
		for(int i = 0; i < scheduled.size(); i++) {
			int rawIndex = scheduled.get(i);
			List<CompiledHopKey> members = rawComponents.get(rawIndex);
			boolean cyclic = members.size() > 1 || selfEdges.contains(members.get(0));
			Component component = new Component(i, members, cyclic);
			schedule.add(component);
			componentByRawIndex[rawIndex] = component;
			for(CompiledHopKey member : members)
				indexed.put(member, component);
		}
		IdentityHashMap<Component,List<Component>> successors = new IdentityHashMap<>(rawComponents.size());
		for(int rawIndex = 0; rawIndex < rawComponents.size(); rawIndex++) {
			List<Component> ordered = new ArrayList<>();
			for(int consumer : componentConsumers.get(rawIndex))
				ordered.add(componentByRawIndex[consumer]);
			ordered.sort(Comparator.comparingInt(Component::topologicalIndex));
			successors.put(componentByRawIndex[rawIndex], List.copyOf(ordered));
		}

		semanticConsumers = immutableIdentityMap(consumers);
		invalidationPeers = immutableIdentityMap(peers);
		componentByOwner = indexed;
		immediateSuccessors = successors;
		topologicalOrder = List.copyOf(schedule);
	}

	public Component componentOf(CompiledHopKey owner) {
		Objects.requireNonNull(owner, "owner");
		Component component = componentByOwner.get(owner);
		if(component == null)
			throw new IllegalArgumentException("unknown owner identity");
		return component;
	}

	public List<Component> topologicalOrder() {
		return topologicalOrder;
	}

	public List<CompiledHopKey> semanticConsumers(CompiledHopKey owner) {
		return lookup(semanticConsumers, owner);
	}

	public List<CompiledHopKey> invalidationPeers(CompiledHopKey owner) {
		return lookup(invalidationPeers, owner);
	}

	/** Returns direct semantic consumers, deduplicated and ordered by the global schedule. */
	public List<Component> immediateSuccessors(Component component) {
		return requireComponent(component);
	}

	/**
	 * Returns the initial dirty components in global schedule order. Only undirected alias adjacency
	 * is closed here. Semantic descendants are intentionally excluded: after an actual export change,
	 * a caller must enqueue {@link #exportChangeFrontier(Component)}.
	 */
	public List<Component> initialDirtyComponents(List<CompiledHopKey> changedOwners) {
		Objects.requireNonNull(changedOwners, "changedOwners");
		for(CompiledHopKey owner : changedOwners)
			componentOf(Objects.requireNonNull(owner, "changedOwner"));
		return orderedOwnerComponents(aliasClosure(changedOwners));
	}

	/**
	 * Returns the next worklist wave after this component's export actually changed. Alias peers are
	 * dirtied now, including aliases first encountered after a semantic hop, while only immediate
	 * semantic successors are exposed. Call this after every changed export; do not call it for an
	 * unchanged recomputation. Because alias adjacency is symmetric, the caller owns revision-aware
	 * worklist de-duplication and may revisit a peer only when its inputs changed after it was settled.
	 */
	public List<Component> exportChangeFrontier(Component changedComponent) {
		List<Component> semanticFrontier = requireComponent(changedComponent);
		Set<Component> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
		dirty.addAll(semanticFrontier);
		for(CompiledHopKey owner : aliasClosure(changedComponent.owners())) {
			Component aliasComponent = componentByOwner.get(owner);
			if(aliasComponent != changedComponent)
				dirty.add(aliasComponent);
		}
		return orderedComponents(dirty);
	}

	private List<Component> requireComponent(Component component) {
		Objects.requireNonNull(component, "component");
		List<Component> result = immediateSuccessors.get(component);
		if(result == null)
			throw new IllegalArgumentException("unknown component identity");
		return result;
	}

	private Set<CompiledHopKey> aliasClosure(List<CompiledHopKey> seeds) {
		Set<CompiledHopKey> affectedOwners = identitySet();
		ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
		for(CompiledHopKey owner : seeds) {
			if(affectedOwners.add(owner))
				pending.addLast(owner);
		}
		while(!pending.isEmpty()) {
			CompiledHopKey current = pending.removeFirst();
			for(CompiledHopKey peer : invalidationPeers.get(current))
				if(affectedOwners.add(peer))
					pending.addLast(peer);
		}
		return affectedOwners;
	}

	private List<Component> orderedOwnerComponents(Set<CompiledHopKey> affectedOwners) {
		Set<Component> affectedComponents = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CompiledHopKey owner : affectedOwners)
			affectedComponents.add(componentByOwner.get(owner));
		return orderedComponents(affectedComponents);
	}

	private List<Component> orderedComponents(Set<Component> affectedComponents) {
		List<Component> result = new ArrayList<>();
		for(Component component : topologicalOrder)
			if(affectedComponents.contains(component))
				result.add(component);
		return List.copyOf(result);
	}

	private List<CompiledHopKey> lookup(Map<CompiledHopKey,List<CompiledHopKey>> adjacency,
		CompiledHopKey owner) {
		Objects.requireNonNull(owner, "owner");
		List<CompiledHopKey> result = adjacency.get(owner);
		if(result == null)
			throw new IllegalArgumentException("unknown owner identity");
		return result;
	}

	private static List<List<CompiledHopKey>> stronglyConnectedComponents(List<CompiledHopKey> owners,
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> consumers,
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> producers,
		Comparator<CompiledHopKey> ownerOrder) {
		Set<CompiledHopKey> visited = identitySet();
		List<CompiledHopKey> finishOrder = new ArrayList<>(owners.size());
		for(CompiledHopKey root : owners) {
			if(!visited.add(root))
				continue;
			ArrayDeque<DfsFrame> stack = new ArrayDeque<>();
			stack.push(new DfsFrame(root));
			while(!stack.isEmpty()) {
				DfsFrame frame = stack.peek();
				List<CompiledHopKey> nextOwners = consumers.get(frame.owner);
				if(frame.next < nextOwners.size()) {
					CompiledHopKey next = nextOwners.get(frame.next++);
					if(visited.add(next))
						stack.push(new DfsFrame(next));
				}
				else {
					finishOrder.add(frame.owner);
					stack.pop();
				}
			}
		}

		visited.clear();
		List<List<CompiledHopKey>> components = new ArrayList<>();
		for(int i = finishOrder.size() - 1; i >= 0; i--) {
			CompiledHopKey root = finishOrder.get(i);
			if(!visited.add(root))
				continue;
			List<CompiledHopKey> members = new ArrayList<>();
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.push(root);
			while(!pending.isEmpty()) {
				CompiledHopKey current = pending.pop();
				members.add(current);
				for(CompiledHopKey producer : producers.get(current))
					if(visited.add(producer))
						pending.push(producer);
			}
			members.sort(ownerOrder);
			components.add(members);
		}
		return components;
	}

	private static IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>> emptyAdjacencySets(
		List<CompiledHopKey> owners) {
		IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>(owners.size());
		for(CompiledHopKey owner : owners)
			result.put(owner, identitySet());
		return result;
	}

	private static IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> sortedAdjacency(
		IdentityHashMap<CompiledHopKey,Set<CompiledHopKey>> adjacency,
		Comparator<CompiledHopKey> order) {
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> result = new IdentityHashMap<>(adjacency.size());
		for(Map.Entry<CompiledHopKey,Set<CompiledHopKey>> entry : adjacency.entrySet()) {
			List<CompiledHopKey> connected = new ArrayList<>(entry.getValue());
			connected.sort(order);
			result.put(entry.getKey(), List.copyOf(connected));
		}
		return result;
	}

	private static IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> immutableIdentityMap(
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> mutable) {
		IdentityHashMap<CompiledHopKey,List<CompiledHopKey>> result = new IdentityHashMap<>(mutable.size());
		for(Map.Entry<CompiledHopKey,List<CompiledHopKey>> entry : mutable.entrySet())
			result.put(entry.getKey(), List.copyOf(entry.getValue()));
		return result;
	}

	private static void requireOwner(IdentityHashMap<CompiledHopKey,Integer> owners,
		CompiledHopKey owner) {
		if(!owners.containsKey(owner))
			throw new IllegalArgumentException("edge endpoint is not a registered owner identity");
	}

	private static <T> Set<T> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}
}
