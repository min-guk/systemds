/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementLayoutKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact, factorized native-pool support across compiler-declared function value boundaries. */
public final class LogicalBoundaryRealizations {
	public record Relation(CompiledHopKey source, CompiledHopKey target) { }
	private record Option(CandidateRealizationReference reference,
		CandidateEmissionRealization realization, CandidateRealizationSupportClause clause, PlacementState state,
		DurableAnchorKey pool, boolean exactLayout) { }
	private record SupportedPool(DurableAnchorKey pool, boolean exactLayout) { }
	private final Map<CompiledHopKey,List<CompiledHopKey>> sources = new IdentityHashMap<>();
	private final Set<CompiledHopKey> declared = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<CompiledHopKey> encoded = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<CompiledHopKey,List<Option>> options = new IdentityHashMap<>();
	private final List<Relation> relations;

	/** Invocation-local incremental closure; value-map carrier changes rebuild its source projection. */
	static final class Session {
		record ClosureResult(List<CandidateRuleFact> facts, Set<CompiledHopKey> changedOwners) { }
		record Work(long topologyBuilds, long optionFactSlotsVisited, long boundaryFactSlotsVisited) {
			Work minus(Work before) {
				return new Work(topologyBuilds - before.topologyBuilds,
					optionFactSlotsVisited - before.optionFactSlotsVisited,
					boundaryFactSlotsVisited - before.boundaryFactSlotsVisited);
			}
		}

		private LogicalBoundaryRealizations boundary;
		private final List<Node> nodes;
		private final Collection<Constraint> constraints;
		private final Map<CompiledHopKey,Hop> origins;
		private final Set<CompiledHopKey> carrierOwners = Collections.newSetFromMap(new IdentityHashMap<>());
		private Set<CompiledHopKey> valueMapCarriers;
		private final Map<CompiledHopKey,List<Integer>> slots = new IdentityHashMap<>();
		private final Map<CompiledHopKey,Set<CompiledHopKey>> targetsBySource = new IdentityHashMap<>();
		private final int factCount;
		private final int maxPasses;
		private boolean firstClose = true;
		private long optionFactSlotsVisited;
		private long boundaryFactSlotsVisited;
		private long topologyBuilds = 1;

		Session(List<Node> nodes, Collection<Constraint> constraints,
			Map<CompiledHopKey,Hop> origins, List<CandidateRuleFact> facts) {
			this.nodes = nodes;
			this.constraints = constraints;
			this.origins = origins;
			for(Node node : nodes)
				if(node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT)
					carrierOwners.add(node.key());
			valueMapCarriers = valueMapCarriers(nodes, facts);
			boundary = new LogicalBoundaryRealizations(
				nodes, constraints, origins, facts, valueMapCarriers);
			factCount = facts.size();
			maxPasses = nodes.size();
			for(int slot = 0; slot < facts.size(); slot++)
				slots.computeIfAbsent(facts.get(slot).key().parentOccurrence(), ignored -> new ArrayList<>()).add(slot);
			indexTargets();
			// The cold construction classifies every row once. Subsequent revisions
			// count only explicitly changed owner slots below.
			optionFactSlotsVisited = facts.size();
		}

		ClosureResult close(List<CandidateRuleFact> facts, Set<CompiledHopKey> completeChangedOwners) {
			if(facts.size() != factCount)
				throw new IllegalStateException("Logical boundary session changed candidate fact count");
			// After the initial closure, a complete empty delta also proves that
			// value-map carriers and all boundary inputs are unchanged.
			if(!firstClose && completeChangedOwners.isEmpty())
				return new ClosureResult(facts, Set.of());
			List<CandidateRuleFact> input = facts;
			if(refreshTopology(facts))
				firstClose = true;
			for(CompiledHopKey owner : completeChangedOwners)
				refreshOptions(owner, facts);
			Set<CompiledHopKey> affected = firstClose
				? identityCopy(boundary.declared) : affectedTargets(completeChangedOwners, true);
			firstClose = false;
			List<CandidateRuleFact> current = facts;
			for(int pass = 0; pass <= maxPasses; pass++) {
				if(affected.isEmpty())
					return new ClosureResult(current, changedOwners(input, current));
				List<CandidateRuleFact> next = boundary.bind(current, affected, slots, this);
				Set<CompiledHopKey> changed = changedOwners(current, next);
				if(changed.isEmpty())
					return new ClosureResult(next, changedOwners(input, next));
				// A newly created/withdrawn VALUE_MAP on a formal/result carrier changes
				// source flattening. Rebuild before the next synchronous round.
				if(changed.stream().anyMatch(carrierOwners::contains) && refreshTopology(next))
					affected = identityCopy(boundary.declared);
				else {
					for(CompiledHopKey owner : changed)
						refreshOptions(owner, next);
					affected = affectedTargets(changed, false);
				}
				current = next;
			}
			throw new IllegalStateException("Logical boundary realization closure did not converge");
		}

		Work work() {
			return new Work(topologyBuilds, optionFactSlotsVisited, boundaryFactSlotsVisited);
		}

		private boolean refreshTopology(List<CandidateRuleFact> facts) {
			Set<CompiledHopKey> revisedCarriers = valueMapCarriers(nodes, facts);
			if(revisedCarriers.equals(valueMapCarriers))
				return false;
			boundary = new LogicalBoundaryRealizations(
				nodes, constraints, origins, facts, revisedCarriers);
			valueMapCarriers = revisedCarriers;
			topologyBuilds++;
			optionFactSlotsVisited += facts.size();
			indexTargets();
			return true;
		}

		private void indexTargets() {
			targetsBySource.clear();
			for(var entry : boundary.sources.entrySet())
				for(CompiledHopKey source : entry.getValue())
					targetsBySource.computeIfAbsent(source,
						ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(entry.getKey());
		}

		private void refreshOptions(CompiledHopKey owner, List<CandidateRuleFact> facts) {
			List<Integer> ownerSlots = slots.getOrDefault(owner, List.of());
			List<Option> projected = new ArrayList<>();
			for(int slot : ownerSlots) {
				CandidateRuleFact fact = facts.get(slot);
				if(fact.key().parentOccurrence() != owner)
					throw new IllegalStateException("Logical boundary session changed candidate owner slot");
				optionFactSlotsVisited++;
				addOptions(projected, fact);
			}
			if(projected.isEmpty())
				boundary.options.remove(owner);
			else
				boundary.options.put(owner, projected.stream().distinct().toList());
		}

		private Set<CompiledHopKey> affectedTargets(Set<CompiledHopKey> changed, boolean includeChangedTargets) {
			Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
			for(CompiledHopKey owner : changed) {
				if(includeChangedTargets && boundary.declared.contains(owner))
					result.add(owner);
				result.addAll(targetsBySource.getOrDefault(owner, Set.of()));
			}
			return result;
		}

		private static Set<CompiledHopKey> changedOwners(List<CandidateRuleFact> before,
			List<CandidateRuleFact> after) {
			if(before == after)
				return Set.of();
			if(before.size() != after.size())
				throw new IllegalStateException("Logical boundary session changed candidate fact count");
			Set<CompiledHopKey> changed = Collections.newSetFromMap(new IdentityHashMap<>());
			for(int slot = 0; slot < before.size(); slot++) {
				CandidateRuleFact oldFact = before.get(slot), newFact = after.get(slot);
				if(oldFact.key().parentOccurrence() != newFact.key().parentOccurrence())
					throw new IllegalStateException("Logical boundary session changed candidate owner slot");
				if(!oldFact.equals(newFact))
					changed.add(oldFact.key().parentOccurrence());
			}
			return changed;
		}

		private static Set<CompiledHopKey> identityCopy(Collection<CompiledHopKey> values) {
			Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
			result.addAll(values);
			return result;
		}
	}

	LogicalBoundaryRealizations(List<Node> nodes, Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CandidateRuleFact> facts) {
		this(nodes, constraints, origins, facts, valueMapCarriers(nodes, facts));
	}

	private LogicalBoundaryRealizations(List<Node> nodes, Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CandidateRuleFact> facts,
		Set<CompiledHopKey> valueMapCarriers) {
		Map<CompiledHopKey,Node> byKey = new IdentityHashMap<>();
		nodes.forEach(node -> byKey.put(node.key(), node));
		Map<CompiledHopKey,List<CompiledHopKey>> incoming = new IdentityHashMap<>();
		for(Constraint edge : constraints) {
			Node target = byKey.get(edge.right());
			Hop hop = origins.get(edge.right());
			boolean valueRead = hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD
				&& (hop.getDataType().isMatrix() || hop.getDataType().isFrame());
			boolean result = edge.kind() == ConstraintKind.SAME_VALUE_PLACEMENT
				&& (edge.evidence().startsWith("function-result:")
					|| edge.evidence().startsWith("inlined-function-result:"));
			boolean read = valueRead && edge.kind() == ConstraintKind.SAME_PLACEMENT
				&& (edge.evidence().startsWith("cfg-function-output-value:")
					|| "function-formal-input".equals(edge.evidence()));
			boolean argument = target != null && target.kind() == NodeKind.FUNCTION_INPUT
				&& (FunctionInputTransfer.isArgumentConstraint(edge)
					|| edge.kind() == ConstraintKind.CONJUNCTIVE
						&& edge.evidence().startsWith("inlined-function-argument:"));
			boolean primary = edge.kind() == ConstraintKind.DOMINATES && edge.inputPosition() == 0
				&& "multi-return-output-value".equals(edge.evidence())
				&& NativePlacementContinuity.transformEncodePreservesPool(hop, FType.ROW)
				&& !hop.getInput().isEmpty() && origins.get(edge.left()) == hop.getInput(0);
			if(result || read || argument || primary)
				incoming.computeIfAbsent(edge.right(), ignored -> new ArrayList<>()).add(edge.left());
			if((read || primary) && target != null && target.emittedWork())
				declared.add(edge.right());
			if(primary)
				encoded.add(edge.right());
		}
		// A formal/result read may also have ordinary writers reaching it on another branch.
		// Those sources are conjunctive; a function edge cannot mask a missing CFG source.
		for(Constraint edge : constraints)
			if(declared.contains(edge.right()) && edge.evidence().startsWith("cfg-transient-value:")
				&& (edge.kind() == ConstraintKind.SAME_PLACEMENT
					|| edge.kind() == ConstraintKind.SAME_VALUE_PLACEMENT))
				incoming.computeIfAbsent(edge.right(), ignored -> new ArrayList<>()).add(edge.left());
		for(CompiledHopKey target : declared) {
			Set<CompiledHopKey> leaves = new TreeSet<>();
			boolean complete = true;
			Node targetNode = byKey.get(target);
			for(CompiledHopKey source : incoming.getOrDefault(target, List.of())) {
				Node sourceNode = byKey.get(source);
				if(targetNode != null && targetNode.kind() != NodeKind.FUNCTION_INPUT
					&& targetNode.kind() != NodeKind.FUNCTION_OUTPUT && sourceNode != null
					&& (sourceNode.kind() == NodeKind.FUNCTION_INPUT
						|| sourceNode.kind() == NodeKind.FUNCTION_OUTPUT)
					&& valueMapCarriers.contains(source))
					leaves.add(source); // Preserve the call-site carrier before flattening its own map.
				else
					complete &= collectSources(source, byKey, incoming, new HashSet<>(), leaves);
			}
			if(complete && !leaves.isEmpty())
				sources.put(target, List.copyOf(leaves));
		}
		// Only boundary targets and their flattened source leaves are queried below.
		// Do not expand unrelated candidate clauses again in every direct wave.
		Set<CompiledHopKey> relevant = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Map.Entry<CompiledHopKey,List<CompiledHopKey>> entry : sources.entrySet()) {
			relevant.add(entry.getKey());
			relevant.addAll(entry.getValue());
		}
		for(CandidateRuleFact fact : facts)
			if(relevant.contains(fact.key().parentOccurrence()))
				addOptions(options.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()), fact);
		options.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		options.replaceAll((key, values) -> values.stream().distinct().toList());
		relations = sources.entrySet().stream()
			.filter(entry -> options.getOrDefault(entry.getKey(), List.of()).stream()
				.anyMatch(option -> requiresNativeBoundaryProof(option.reference().realization().emissionState())))
			.flatMap(entry -> entry.getValue().stream().map(source -> new Relation(source, entry.getKey())))
			.sorted(java.util.Comparator.comparing((Relation relation) -> relation.target().normalizedSignature())
				.thenComparing(relation -> relation.source().normalizedSignature())).toList();
	}

	private static Set<CompiledHopKey> valueMapCarriers(List<Node> nodes, List<CandidateRuleFact> facts) {
		Set<CompiledHopKey> carriers = Collections.newSetFromMap(new IdentityHashMap<>());
		for(Node node : nodes)
			if(node.kind() == NodeKind.FUNCTION_INPUT || node.kind() == NodeKind.FUNCTION_OUTPUT)
				carriers.add(node.key());
		Set<CompiledHopKey> result = Collections.newSetFromMap(new IdentityHashMap<>());
		for(CandidateRuleFact fact : facts)
			if(carriers.contains(fact.key().parentOccurrence())
				&& fact.status() == CandidateEvaluationStatus.AVAILABLE
				&& fact.allowedEmissionFacts().stream().flatMap(emission -> emission.realizations().stream())
					.anyMatch(realization -> realization.key().layoutKind() == PlacementLayoutKind.VALUE_MAP))
				result.add(fact.key().parentOccurrence());
		return result;
	}

	private static void addOptions(List<Option> result, CandidateRuleFact fact) {
		if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
			return;
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
			for(CandidateEmissionRealization realization : emission.realizations())
				for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
					PlacementState state = realization.key().emissionState().placementState();
					DurableAnchorKey pool = realization.nativeWorkerPoolResidencyForOwnedClause(clause);
					if(state.output() == FederatedOutput.FOUT && pool == null
						&& realization.key().layoutKind() != PlacementLayoutKind.VALUE_MAP)
						continue; // Staging lineage is not native execution authority.
					result.add(new Option(CandidateRealizationReference.of(fact.key(), realization), realization, clause,
						state, pool, realization.nativeWorkerPoolLayoutExactForOwnedClause(clause)));
				}
	}

	private static boolean collectSources(CompiledHopKey key, Map<CompiledHopKey,Node> nodes,
		Map<CompiledHopKey,List<CompiledHopKey>> incoming, Set<CompiledHopKey> active,
		Set<CompiledHopKey> leaves) {
		Node node = nodes.get(key);
		if(node == null || !active.add(key))
			return false;
		if(node.kind() != NodeKind.FUNCTION_INPUT && node.kind() != NodeKind.FUNCTION_OUTPUT) {
			leaves.add(key);
			active.remove(key);
			return true;
		}
		List<CompiledHopKey> predecessors = incoming.getOrDefault(key, List.of());
		boolean complete = !predecessors.isEmpty();
		for(CompiledHopKey source : predecessors)
			complete &= collectSources(source, nodes, incoming, active, leaves);
		active.remove(key);
		return complete;
	}

	public boolean hasCompleteBoundary(CompiledHopKey target) { return sources.containsKey(target); }
	public List<CompiledHopKey> sources(CompiledHopKey target) {
		return sources.getOrDefault(target, List.of());
	}
	public List<Relation> relations() {
		return relations;
	}

	private List<SupportedPool> supportedPools(CompiledHopKey target, FType type) {
		List<CompiledHopKey> exactSources = sources(target);
		if(exactSources.isEmpty() || type == null || type == FType.PART || type == FType.OTHER
			|| encoded.contains(target) && type != FType.ROW && type != FType.FULL)
			return List.of();
		List<SupportedPool> supported = new ArrayList<>();
		for(Option candidate : options.getOrDefault(exactSources.get(0), List.of())) {
			DurableAnchorKey pool = candidate.pool();
			if(pool == null || pool.fType() != type
				|| encoded.contains(target) && type == FType.FULL && pool.partitions().size() != 1
				|| exactSources.stream().anyMatch(source -> !supportsPoolEndpoints(source, pool)))
				continue;
			boolean exactLayout = exactSources.stream().allMatch(source -> supportsExactPool(source, pool));
			SupportedPool replacement = new SupportedPool(pool, exactLayout);
			int existing = -1;
			for(int index = 0; index < supported.size(); index++)
				if(PlacementIdentity.samePhysicalWorkerEndpoints(supported.get(index).pool(), pool)) {
					existing = index;
					break;
				}
			if(existing < 0)
				supported.add(replacement);
			else if(exactLayout && !supported.get(existing).exactLayout())
				supported.set(existing, replacement);
		}
		supported.sort(java.util.Comparator.comparing(entry -> entry.pool().normalizedSignature()));
		return List.copyOf(supported);
	}

	private boolean supportsPoolEndpoints(CompiledHopKey source, DurableAnchorKey pool) {
		return options.getOrDefault(source, List.of()).stream().anyMatch(option -> option.pool() != null
			&& option.state().output() == FederatedOutput.FOUT && option.state().fType() == pool.fType()
			&& PlacementIdentity.samePhysicalWorkerEndpoints(option.pool(), pool));
	}

	private boolean supportsExactPool(CompiledHopKey source, DurableAnchorKey pool) {
		return options.getOrDefault(source, List.of()).stream().anyMatch(option -> option.pool() != null
			&& option.exactLayout() && option.state().output() == FederatedOutput.FOUT
			&& option.state().fType() == pool.fType()
			&& PlacementIdentity.samePhysicalWorkerPool(option.pool(), pool));
	}

	/** Close consecutive formal/result carriers before the physical pass rebuilds their templates. */
	static List<CandidateRuleFact> close(List<Node> nodes, Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CandidateRuleFact> facts) {
		Session session = new Session(nodes, constraints, origins, facts);
		List<CandidateRuleFact> current = facts;
		Set<CompiledHopKey> changed = Set.of();
		for(int pass = 0; pass <= nodes.size(); pass++) {
			Session.ClosureResult result = session.close(current, changed);
			if(result.facts().equals(current))
				return result.facts();
			current = result.facts();
			changed = result.changedOwners();
		}
		throw new IllegalStateException("Logical boundary realization closure did not converge");
	}

	List<CandidateRuleFact> bind(List<CandidateRuleFact> facts) {
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts)
			result.add(bind(fact));
		return List.copyOf(result);
	}

	private List<CandidateRuleFact> bind(List<CandidateRuleFact> facts, Set<CompiledHopKey> targets,
		Map<CompiledHopKey,List<Integer>> slots, Session session) {
		List<CandidateRuleFact> result = new ArrayList<>(facts);
		for(CompiledHopKey target : targets)
			for(int slot : slots.getOrDefault(target, List.of())) {
				CandidateRuleFact fact = facts.get(slot);
				if(fact.key().parentOccurrence() != target)
					throw new IllegalStateException("Logical boundary session changed candidate owner slot");
				session.boundaryFactSlotsVisited++;
				result.set(slot, bind(fact));
			}
		return List.copyOf(result);
	}

	private CandidateRuleFact bind(CandidateRuleFact fact) {
		CompiledHopKey target = fact.key().parentOccurrence();
		if(!declared.contains(target) || fact.status() != CandidateEvaluationStatus.AVAILABLE)
			return fact;
		List<CandidateEmissionFact> emissions = new ArrayList<>();
		boolean unchanged = true;
		for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| emission.derivedFoutAction() != null || emission.emissionState().derivedFedFout()) {
					emissions.add(emission);
					continue;
				}
				unchanged = false;
				List<CandidateEmissionRealization> realizations = new ArrayList<>();
				for(SupportedPool supported : supportedPools(target, state.fType())) {
					DurableAnchorKey pool = supported.pool();
					PlacementProofKey proof = new PlacementProofKey(PlacementProofKind.NATIVE_CONTINUITY,
						target, "logical-boundary-sources=" + sources(target).stream()
							.map(CompiledHopKey::normalizedSignature).toList());
					String lineage = "logical-boundary:" + target.normalizedSignature()
						+ "|pool=" + pool.normalizedSignature();
					realizations.add(supported.exactLayout()
						? CandidateEmissionRealization.nativeLineage(emission.emissionState(),
							lineage, pool, List.of(proof), List.of())
						: CandidateEmissionRealization.nativeLineageDynamicLayout(emission.emissionState(),
							lineage, pool, List.of(proof), List.of()));
				}
				CandidateEmissionRealization valueMap = valueMapRealization(target, emission.emissionState(),
					state.fType());
				if(valueMap != null)
					realizations.add(valueMap);
				// No pool is a staging result, not permission to use an arbitrary anchor.
				emissions.add(realizations.isEmpty()
					? new CandidateEmissionFact(emission.emissionState(), emission.executionFType())
					: new CandidateEmissionFact(emission.emissionState(), emission.executionFType(), null, realizations));
		}
		return unchanged ? fact : new CandidateRuleFact(fact.key(), fact.status(),
			fact.capability(), fact.shapeProof(), fact.profile(), emissions, fact.failureCode());
	}

	private CandidateEmissionRealization valueMapRealization(CompiledHopKey target,
		PlacementEmissionState emission, FType type) {
		if(type == null || type == FType.PART || type == FType.OTHER || sources(target).isEmpty())
			return null;
		List<List<Option>> choices = new ArrayList<>();
		for(CompiledHopKey source : sources(target)) {
			List<Option> candidates = options.getOrDefault(source, List.of()).stream()
				.filter(option -> option.state().output() == FederatedOutput.FOUT
					&& option.state().fType() == type
					&& (option.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP
						|| option.pool() != null && option.exactLayout()))
				.toList();
			// A binding selects a source realization; its clause remains that source
			// decision's choice. Do not duplicate products for identical references.
			Map<CandidateRealizationReference,Option> unique = new java.util.LinkedHashMap<>();
			for(Option candidate : candidates)
				unique.putIfAbsent(candidate.reference(), candidate);
			List<Option> exact = List.copyOf(unique.values());
			if(exact.isEmpty())
				return null;
			choices.add(exact);
		}
		List<List<Option>> products = new ArrayList<>();
		enumerateOptions(choices, 0, new ArrayList<>(), products);
		List<CandidateRealizationSupportClause> clauses = new ArrayList<>(products.size());
		for(List<Option> product : products) {
			// A shared fixed map already has a native realization. Retain every
			// heterogeneous product even if another product has a common map.
			DurableAnchorKey first = product.get(0).pool();
			if(first != null && product.stream().allMatch(option -> option.pool() != null
				&& PlacementIdentity.samePhysicalWorkerPool(first, option.pool())))
				continue;
			List<CandidateRealizationInputBinding> bindings = new ArrayList<>(product.size());
			for(int position = 0; position < product.size(); position++)
				bindings.add(CandidateRealizationInputBinding.logicalTransient(0,
					product.get(position).reference()));
			clauses.add(new CandidateRealizationSupportClause(List.of(new PlacementProofKey(
				PlacementProofKind.CONTROL_FLOW, target, "logical-boundary-value-map")), bindings));
		}
		if(clauses.isEmpty())
			return null;
		return CandidateEmissionRealization.valueMap(emission,
			"logical-boundary-map:" + target.normalizedSignature(), clauses);
	}

	private static void enumerateOptions(List<List<Option>> choices, int source,
		List<Option> product, List<List<Option>> products) {
		if(source == choices.size()) {
			products.add(List.copyOf(product));
			return;
		}
		for(Option option : choices.get(source)) {
			product.add(option);
			enumerateOptions(choices, source + 1, product, products);
			product.remove(product.size() - 1);
		}
	}

	void validate(List<CandidateRuleFact> facts) {
		for(CandidateRuleFact fact : facts)
			if(declared.contains(fact.key().parentOccurrence()) && fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					if(requiresNativeBoundaryProof(emission.emissionState()) && emission.derivedFoutAction() == null)
						for(CandidateEmissionRealization realization : emission.realizations())
						for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
							if(realization.key().layoutKind() == PlacementLayoutKind.VALUE_MAP) {
								Set<CompiledHopKey> bound = Collections.newSetFromMap(new IdentityHashMap<>());
								for(CandidateRealizationInputBinding binding : clause.inputBindings()) {
									if(binding.kind() != PlacementIdentity.CandidateInputBindingKind.LOGICAL_TRANSIENT)
										throw new IllegalArgumentException("Function value-map binding is not logical");
									bound.add(binding.source().rule().parentOccurrence());
								}
								if(!hasCompleteBoundary(fact.key().parentOccurrence())
									|| !bound.containsAll(sources(fact.key().parentOccurrence())))
									throw new IllegalArgumentException("Function value-map lacks all-source support: "
										+ fact.key().normalizedSignature());
								continue;
							}
								DurableAnchorKey pool = realization.nativeWorkerPoolResidencyForOwnedClause(clause);
								boolean exactLayout = realization.nativeWorkerPoolLayoutExactForOwnedClause(clause);
								if(pool == null || !hasCompleteBoundary(fact.key().parentOccurrence())
									|| supportedPools(fact.key().parentOccurrence(), pool.fType()).stream()
										.noneMatch(candidate -> exactLayout
											? candidate.exactLayout() && PlacementIdentity.samePhysicalWorkerPool(
												candidate.pool(), pool)
											: PlacementIdentity.samePhysicalWorkerEndpoints(candidate.pool(), pool)))
									throw new IllegalArgumentException("Function value realization lacks all-source support: "
										+ fact.key().normalizedSignature());
							}
	}

	private static boolean requiresNativeBoundaryProof(PlacementEmissionState emission) {
		// Explicit CP/FOUT or derived-FOUT materialization has its own action authority.
		// Function value continuity must not constrain it as an implicit native alias.
		PlacementState state = emission.placementState();
		return state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT
			&& !emission.derivedFedFout();
	}

	public static boolean compatible(CandidateEmissionRealization target,
		CandidateRealizationSupportClause targetClause, CandidateEmissionRealization source,
		CandidateRealizationSupportClause sourceClause) {
		if(target == null || source == null)
			return false;
		if(!requiresNativeBoundaryProof(target.key().emissionState()))
			return true;
		if(target.key().layoutKind() == PlacementLayoutKind.VALUE_MAP)
			return target.key().emissionState().placementState().fType()
				== source.key().emissionState().placementState().fType()
				&& targetClause.inputBindings().stream()
					.anyMatch(binding -> binding.source().realization().equals(source.key()));
		DurableAnchorKey targetPool = target.nativeWorkerPoolResidencyWitness(targetClause);
		DurableAnchorKey sourcePool = source.nativeWorkerPoolResidencyWitness(sourceClause);
		return targetPool != null && sourcePool != null
			&& compatiblePools(targetPool, target.nativeWorkerPoolLayoutExact(targetClause),
				sourcePool, source.nativeWorkerPoolLayoutExact(sourceClause));
	}

	private static boolean compatiblePools(DurableAnchorKey left, boolean leftExact,
		DurableAnchorKey right, boolean rightExact) {
		return leftExact && rightExact
			? PlacementIdentity.samePhysicalWorkerPool(left, right)
			: PlacementIdentity.samePhysicalWorkerEndpoints(left, right);
	}

	boolean canStillBeCompatible(Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		Map<CompiledHopKey,List<PlacementState>> remaining) {
		for(Relation relation : relations()) {
			List<Option> targets = possible(relation.target(), assignment, selected, remaining);
			if(targets.stream().anyMatch(option -> !requiresNativeBoundaryProof(option.reference().realization().emissionState())))
				continue; // Existing value/call-boundary constraints still own local legality.
			List<Option> inputs = possible(relation.source(), assignment, selected, remaining);
			if(targets.stream().noneMatch(target -> inputs.stream().anyMatch(source ->
				target.realization().key().layoutKind() == PlacementLayoutKind.VALUE_MAP
					? target.clause().requiredInputSupport().contains(source.reference())
					: target.pool() != null && source.pool() != null
						&& compatiblePools(target.pool(), target.exactLayout(),
							source.pool(), source.exactLayout()))))
				return false;
		}
		return true;
	}

	private List<Option> possible(CompiledHopKey key, Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		Map<CompiledHopKey,List<PlacementState>> remaining) {
		CandidateSelectionReceipt receipt = selected.get(key);
		if(receipt != null)
			return List.of(new Option(CandidateRealizationReference.of(receipt.rule(), receipt.realization()),
				receipt.realization(), receipt.supportClause(), receipt.realization().key().emissionState().placementState(),
				receipt.realization().nativeWorkerPoolResidencyWitness(receipt.supportClause()),
				receipt.realization().nativeWorkerPoolLayoutExact(receipt.supportClause())));
		PlacementState state = assignment.get(key);
		List<PlacementState> domain = remaining.get(key);
		return options.getOrDefault(key, List.of()).stream()
			.filter(option -> state == null || state.equals(option.state()))
			.filter(option -> state != null || domain == null || domain.contains(option.state())).toList();
	}
}
