/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.InvalidationAdjacency;
import org.apache.sysds.hops.fedplanner.placement.PlacementDependencyComponents.SemanticDependency;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Small invocation-local cache for exact direct dependency component schedules. */
final class DirectComponentScheduleCache {
	private static final int MAX_ENTRIES = 8;

	private record Entry(List<CompiledHopKey> owners, long[] dependencies, long[] aliases,
		PlacementDependencyComponents components) { }

	private final List<Entry> entries = new ArrayList<>(MAX_ENTRIES);

	synchronized PlacementDependencyComponents get(List<CompiledHopKey> owners,
		List<SemanticDependency> dependencies, List<InvalidationAdjacency> aliases) {
		Objects.requireNonNull(owners, "owners");
		Objects.requireNonNull(dependencies, "dependencies");
		Objects.requireNonNull(aliases, "aliases");
		IdentityHashMap<CompiledHopKey,Integer> ordinals = ownerOrdinals(owners);
		long[] dependencyKey = directedEdges(dependencies, ordinals);
		long[] aliasKey = undirectedEdges(aliases, ordinals);
		for(int index = 0; index < entries.size(); index++) {
			Entry entry = entries.get(index);
			if(sameOwners(entry.owners(), owners)
				&& Arrays.equals(entry.dependencies(), dependencyKey)
				&& Arrays.equals(entry.aliases(), aliasKey)) {
				if(index + 1 < entries.size()) {
					entries.remove(index);
					entries.add(entry);
				}
				return entry.components();
			}
		}
		PlacementDependencyComponents components = new PlacementDependencyComponents(
			owners, dependencies, aliases);
		if(entries.size() == MAX_ENTRIES)
			entries.remove(0);
		entries.add(new Entry(List.copyOf(owners), dependencyKey, aliasKey, components));
		return components;
	}

	synchronized void clear() {
		entries.clear();
	}

	private static IdentityHashMap<CompiledHopKey,Integer> ownerOrdinals(List<CompiledHopKey> owners) {
		IdentityHashMap<CompiledHopKey,Integer> ordinals = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < owners.size(); ordinal++) {
			CompiledHopKey owner = Objects.requireNonNull(owners.get(ordinal), "owner");
			if(ordinals.put(owner, ordinal) != null)
				throw new IllegalArgumentException("owner identity registered more than once");
		}
		return ordinals;
	}

	private static long[] directedEdges(List<SemanticDependency> dependencies,
		IdentityHashMap<CompiledHopKey,Integer> ordinals) {
		long[] normalized = new long[dependencies.size()];
		int index = 0;
		for(SemanticDependency dependency : dependencies) {
			Objects.requireNonNull(dependency, "semanticDependency");
			normalized[index++] = edge(ordinal(ordinals, dependency.producer()),
				ordinal(ordinals, dependency.consumer()));
		}
		return sortAndDeduplicate(normalized);
	}

	private static long[] undirectedEdges(List<InvalidationAdjacency> aliases,
		IdentityHashMap<CompiledHopKey,Integer> ordinals) {
		long[] normalized = new long[aliases.size()];
		int index = 0;
		for(InvalidationAdjacency alias : aliases) {
			Objects.requireNonNull(alias, "invalidationAdjacency");
			int left = ordinal(ordinals, alias.left());
			int right = ordinal(ordinals, alias.right());
			normalized[index++] = edge(Math.min(left, right), Math.max(left, right));
		}
		return sortAndDeduplicate(normalized);
	}

	private static long[] sortAndDeduplicate(long[] values) {
		Arrays.sort(values);
		if(values.length < 2)
			return values;
		int retained = 1;
		for(int index = 1; index < values.length; index++)
			if(values[index] != values[retained - 1])
				values[retained++] = values[index];
		return retained == values.length ? values : Arrays.copyOf(values, retained);
	}

	private static int ordinal(IdentityHashMap<CompiledHopKey,Integer> ordinals, CompiledHopKey owner) {
		Objects.requireNonNull(owner, "edge owner");
		Integer ordinal = ordinals.get(owner);
		if(ordinal == null)
			throw new IllegalArgumentException("edge endpoint is not a registered owner identity");
		return ordinal;
	}

	private static long edge(int left, int right) {
		return ((long)left << 32) | (right & 0xffffffffL);
	}

	private static boolean sameOwners(List<CompiledHopKey> cached, List<CompiledHopKey> current) {
		if(cached.size() != current.size())
			return false;
		for(int index = 0; index < cached.size(); index++)
			if(cached.get(index) != current.get(index))
				return false;
		return true;
	}
}
