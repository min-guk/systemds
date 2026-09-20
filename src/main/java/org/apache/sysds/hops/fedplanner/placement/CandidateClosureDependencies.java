/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;

/** Analysis-local exact dependency index for change-driven direct candidate closure. */
final class CandidateClosureDependencies {
	enum ChangeKind {
		NONE,
		POSITIVE_RELATION,
		EXACT_REPLACEMENT,
		DOMAIN_CHANGE,
		AUTHORITY_CHANGE,
		TOPOLOGY_CHANGE
	}

	record Revision(Set<CompiledHopKey> directOwners, Set<CompiledHopKey> invalidationOwners,
		Map<CompiledHopKey,ChangeKind> changes, boolean directed) { }
	record IndexStats(int directedEdges, int aliasGroups, int aliasMemberships,
		int materializedAliasEdges) { }
	private record DependencyIndex(Map<CompiledHopKey,Set<CompiledHopKey>> directed,
		Map<CompiledHopKey,List<CompiledHopKey>> aliasesByMember, IndexStats stats) { }

	private CandidateClosureDependencies() { }

	static ChangeKind classifyForTesting(List<CandidateRuleFact> before,
		List<CandidateRuleFact> after) {
		return classify(before, after);
	}

	static Revision scheduleForTesting(Map<CompiledHopKey,ChangeKind> changes,
		Map<CompiledHopKey,? extends Collection<CompiledHopKey>> directedEdges) {
		Set<CompiledHopKey> changed = identitySet();
		for(var entry : changes.entrySet())
			if(entry.getValue() != ChangeKind.NONE) changed.add(entry.getKey());
		Map<CompiledHopKey,Set<CompiledHopKey>> directed = new IdentityHashMap<>();
		for(var entry : directedEdges.entrySet())
			for(CompiledHopKey dependent : entry.getValue()) add(directed, entry.getKey(), dependent);
		if(changed.isEmpty())
			return new Revision(Set.of(), Set.of(), Map.copyOf(changes), true);
		Map<CompiledHopKey,List<CompiledHopKey>> noAliases = new IdentityHashMap<>();
		Set<CompiledHopKey> conservative = closure(changed, undirected(directed), noAliases);
		Set<CompiledHopKey> dependentCone = closure(changed, directed, noAliases);
		boolean positive = changes.values().stream()
			.filter(kind -> kind != ChangeKind.NONE)
			.allMatch(kind -> kind == ChangeKind.POSITIVE_RELATION);
		boolean eligible = positive && !hasCycle(dependentCone, directed);
		return new Revision(immutableIdentitySet(eligible ? dependentCone : conservative),
			immutableIdentitySet(conservative), Map.copyOf(changes), eligible);
	}

	static IndexStats aliasIndexStatsForTesting(Map<CompiledHopKey,ValueVersionKey> aliases) {
		return aliasIndex(new IdentityHashMap<>(), aliases).stats();
	}

	static Revision revision(List<CandidateRuleFact> before, List<CandidateRuleFact> after,
		List<Node> nodes, List<CompiledInputEdgeFact> physicalEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingSources, Collection<Constraint> boundaries) {
		Map<CompiledHopKey,List<CandidateRuleFact>> oldFacts = factsByOwner(before);
		Map<CompiledHopKey,List<CandidateRuleFact>> newFacts = factsByOwner(after);
		Set<CompiledHopKey> owners = identitySet();
		owners.addAll(oldFacts.keySet());
		owners.addAll(newFacts.keySet());
		Map<CompiledHopKey,ChangeKind> changes = new IdentityHashMap<>();
		Set<CompiledHopKey> changed = identitySet();
		for(CompiledHopKey owner : owners) {
			ChangeKind kind = classify(oldFacts.getOrDefault(owner, List.of()),
				newFacts.getOrDefault(owner, List.of()));
			if(kind != ChangeKind.NONE) {
				changes.put(owner, kind);
				changed.add(owner);
			}
		}
		if(changed.isEmpty())
			return new Revision(Set.of(), Set.of(), Collections.unmodifiableMap(changes), true);

		DependencyIndex index = directedDependencies(nodes, physicalEdges,
			reachingSources, before, after, boundaries);
		Map<CompiledHopKey,Set<CompiledHopKey>> undirected = undirected(index.directed());
		Set<CompiledHopKey> conservative = closure(changed, undirected, index.aliasesByMember());
		boolean positive = changes.values().stream().allMatch(kind -> kind == ChangeKind.POSITIVE_RELATION);
		Set<CompiledHopKey> dependentCone = closure(changed, index.directed(), index.aliasesByMember());
		boolean aliasesStable = dependentCone.stream()
			.noneMatch(owner -> index.aliasesByMember().getOrDefault(owner, List.of()).size() > 1);
		boolean acyclic = aliasesStable && !hasCycle(dependentCone, index.directed());
		boolean authorityStable = Collections.disjoint(dependentCone, unsafeAuthorityOwners(nodes, boundaries));
		Set<CompiledHopKey> scheduled = positive && acyclic && authorityStable ? dependentCone : conservative;
		return new Revision(immutableIdentitySet(scheduled), immutableIdentitySet(conservative),
			Collections.unmodifiableMap(changes), positive && acyclic && authorityStable);
	}

	private static ChangeKind classify(List<CandidateRuleFact> before, List<CandidateRuleFact> after) {
		if(before.equals(after))
			return ChangeKind.NONE;
		Map<Object,CandidateRuleFact> oldByKey = new LinkedHashMap<>();
		Map<Object,CandidateRuleFact> newByKey = new LinkedHashMap<>();
		before.forEach(fact -> oldByKey.put(fact.key(), fact));
		after.forEach(fact -> newByKey.put(fact.key(), fact));
		if(!oldByKey.keySet().equals(newByKey.keySet()))
			return ChangeKind.DOMAIN_CHANGE;
		for(Object key : oldByKey.keySet()) {
			CandidateRuleFact oldFact = oldByKey.get(key);
			CandidateRuleFact newFact = newByKey.get(key);
			if(oldFact.status() != newFact.status()
				|| !Objects.equals(oldFact.capability(), newFact.capability())
				|| !oldFact.shapeProof().equals(newFact.shapeProof())
				|| !oldFact.profile().equals(newFact.profile())
				|| !oldFact.failureCode().equals(newFact.failureCode()))
				return ChangeKind.AUTHORITY_CHANGE;
			if(!supportSources(oldFact).equals(supportSources(newFact)))
				return ChangeKind.TOPOLOGY_CHANGE;
			if(!emissionsSubset(oldFact.allowedEmissionFacts(), newFact.allowedEmissionFacts()))
				return ChangeKind.EXACT_REPLACEMENT;
		}
		return ChangeKind.POSITIVE_RELATION;
	}

	private static boolean emissionsSubset(List<CandidateEmissionFact> before,
		List<CandidateEmissionFact> after) {
		for(CandidateEmissionFact oldEmission : before) {
			CandidateEmissionFact newEmission = after.stream()
				.filter(candidate -> candidate.emissionState().equals(oldEmission.emissionState())
					&& Objects.equals(candidate.executionFType(), oldEmission.executionFType())
					&& Objects.equals(candidate.derivedFoutAction(), oldEmission.derivedFoutAction()))
				.findFirst().orElse(null);
			if(newEmission == null || !realizationsSubset(oldEmission, newEmission))
				return false;
		}
		return true;
	}

	private static boolean realizationsSubset(CandidateEmissionFact before,
		CandidateEmissionFact after) {
		for(var oldRealization : before.realizations()) {
			var newRealization = after.realizations().stream()
				.filter(candidate -> candidate.key().equals(oldRealization.key()))
				.findFirst().orElse(null);
			if(newRealization == null
				|| !newRealization.supportClauses().containsAll(oldRealization.supportClauses()))
				return false;
		}
		return true;
	}

	private static Set<CompiledHopKey> supportSources(CandidateRuleFact fact) {
		Set<CompiledHopKey> sources = identitySet();
		fact.allowedEmissionFacts().forEach(emission -> emission.realizations().forEach(realization ->
			realization.supportClauses().forEach(clause -> clause.inputBindings().forEach(binding ->
				sources.add(binding.source().rule().parentOccurrence())))));
		return sources;
	}

	private static DependencyIndex directedDependencies(List<Node> nodes,
		List<CompiledInputEdgeFact> physicalEdges, Map<CompiledHopKey,List<CompiledHopKey>> reachingSources,
		List<CandidateRuleFact> before, List<CandidateRuleFact> after, Collection<Constraint> boundaries) {
		Map<CompiledHopKey,Set<CompiledHopKey>> dependents = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : physicalEdges)
			add(dependents, edge.producer(), edge.consumer());
		for(var entry : reachingSources.entrySet())
			for(CompiledHopKey source : entry.getValue())
				add(dependents, source, entry.getKey());
		for(List<CandidateRuleFact> facts : List.of(before, after))
			for(CandidateRuleFact owner : facts)
				for(CompiledHopKey source : supportSources(owner))
					add(dependents, source, owner.key().parentOccurrence());
		for(Constraint boundary : boundaries) {
			add(dependents, boundary.left(), boundary.right());
			if(boundary.kind() != ConstraintKind.DOMINATES)
				add(dependents, boundary.right(), boundary.left());
		}
		Map<CompiledHopKey,ValueVersionKey> versions = new IdentityHashMap<>();
		for(Node node : nodes)
			versions.put(node.key(), node.valueVersion());
		return aliasIndex(dependents, versions);
	}

	private static DependencyIndex aliasIndex(Map<CompiledHopKey,Set<CompiledHopKey>> dependents,
		Map<CompiledHopKey,ValueVersionKey> versions) {
		Map<ValueVersionKey,List<CompiledHopKey>> groups = new LinkedHashMap<>();
		for(var entry : versions.entrySet())
			groups.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
		Map<CompiledHopKey,List<CompiledHopKey>> aliasesByMember = new IdentityHashMap<>();
		int memberships = 0;
		for(List<CompiledHopKey> mutableGroup : groups.values()) {
			List<CompiledHopKey> group = List.copyOf(mutableGroup);
			memberships += group.size();
			for(CompiledHopKey member : group)
				aliasesByMember.put(member, group);
		}
		int edgeCount = dependents.values().stream().mapToInt(Set::size).sum();
		return new DependencyIndex(dependents, aliasesByMember,
			new IndexStats(edgeCount, groups.size(), memberships, 0));
	}

	private static Set<CompiledHopKey> unsafeAuthorityOwners(List<Node> nodes,
		Collection<Constraint> boundaries) {
		Set<CompiledHopKey> boundaryNodes = identitySet();
		for(Node node : nodes)
			if(node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT
				|| node.kind() == NodeKind.FUNCTION_CALL)
				boundaryNodes.add(node.key());
		Map<CompiledHopKey,Set<CompiledHopKey>> boundaryAdjacency = new IdentityHashMap<>();
		for(Constraint boundary : boundaries) {
			add(boundaryAdjacency, boundary.left(), boundary.right());
			add(boundaryAdjacency, boundary.right(), boundary.left());
		}
		boundaryNodes.addAll(closure(boundaryNodes, boundaryAdjacency, Map.of()));
		for(Node node : nodes)
			if(node.exclusions().stream().anyMatch(exclusion -> exclusion.reasonCode() == ReasonCode.PRIVACY
				|| exclusion.reasonCode() == ReasonCode.UNKNOWN_METADATA))
				boundaryNodes.add(node.key());
		return boundaryNodes;
	}

	private static Map<CompiledHopKey,List<CandidateRuleFact>> factsByOwner(List<CandidateRuleFact> facts) {
		Map<CompiledHopKey,List<CandidateRuleFact>> grouped = new IdentityHashMap<>();
		for(CandidateRuleFact fact : facts)
			grouped.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
		return grouped;
	}

	private static Map<CompiledHopKey,Set<CompiledHopKey>> undirected(
		Map<CompiledHopKey,Set<CompiledHopKey>> directed) {
		Map<CompiledHopKey,Set<CompiledHopKey>> result = new IdentityHashMap<>();
		for(var entry : directed.entrySet())
			for(CompiledHopKey dependent : entry.getValue()) {
				add(result, entry.getKey(), dependent);
				add(result, dependent, entry.getKey());
			}
		return result;
	}

	private static Set<CompiledHopKey> closure(Set<CompiledHopKey> roots,
		Map<CompiledHopKey,Set<CompiledHopKey>> edges,
		Map<CompiledHopKey,List<CompiledHopKey>> aliasesByMember) {
		Set<CompiledHopKey> reached = identitySet();
		Set<List<CompiledHopKey>> expandedAliasGroups =
			Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
		for(CompiledHopKey root : roots)
			if(reached.add(root)) pending.add(root);
		while(!pending.isEmpty()) {
			CompiledHopKey current = pending.removeFirst();
			for(CompiledHopKey next : edges.getOrDefault(current, Set.of()))
				if(reached.add(next)) pending.addLast(next);
			List<CompiledHopKey> aliases = aliasesByMember.get(current);
			if(aliases != null && expandedAliasGroups.add(aliases))
				for(CompiledHopKey alias : aliases)
					if(reached.add(alias)) pending.addLast(alias);
		}
		return reached;
	}

	private static boolean hasCycle(Set<CompiledHopKey> region,
		Map<CompiledHopKey,Set<CompiledHopKey>> edges) {
		Map<CompiledHopKey,Integer> indegree = new IdentityHashMap<>();
		for(CompiledHopKey node : region)
			indegree.put(node, 0);
		for(CompiledHopKey source : region)
			for(CompiledHopKey dependent : edges.getOrDefault(source, Set.of()))
				if(region.contains(dependent))
					indegree.put(dependent, indegree.get(dependent) + 1);
		ArrayDeque<CompiledHopKey> ready = new ArrayDeque<>();
		for(var entry : indegree.entrySet())
			if(entry.getValue() == 0)
				ready.addLast(entry.getKey());
		int visited = 0;
		while(!ready.isEmpty()) {
			CompiledHopKey source = ready.removeFirst();
			visited++;
			for(CompiledHopKey dependent : edges.getOrDefault(source, Set.of()))
				if(region.contains(dependent)) {
					int remaining = indegree.get(dependent) - 1;
					indegree.put(dependent, remaining);
					if(remaining == 0)
						ready.addLast(dependent);
				}
		}
		return visited != region.size();
	}

	private static void add(Map<CompiledHopKey,Set<CompiledHopKey>> edges,
		CompiledHopKey source, CompiledHopKey dependent) {
		edges.computeIfAbsent(source, ignored -> identitySet()).add(dependent);
	}

	private static Set<CompiledHopKey> identitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private static Set<CompiledHopKey> immutableIdentitySet(Collection<CompiledHopKey> values) {
		Set<CompiledHopKey> result = identitySet();
		result.addAll(values);
		return Collections.unmodifiableSet(result);
	}
}
