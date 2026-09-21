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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.PlacementProofKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact, factorized native-pool support across compiler-declared function value boundaries. */
public final class LogicalBoundaryRealizations {
	public record Relation(CompiledHopKey source, CompiledHopKey target) { }
	private record Option(CandidateRealizationReference reference, PlacementState state,
		DurableAnchorKey pool, boolean exactLayout) { }
	private record SupportedPool(DurableAnchorKey pool, boolean exactLayout) { }
	private final Map<CompiledHopKey,List<CompiledHopKey>> sources = new IdentityHashMap<>();
	private final Set<CompiledHopKey> declared = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<CompiledHopKey> encoded = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<CompiledHopKey,List<Option>> options = new IdentityHashMap<>();
	private final List<Relation> relations;

	LogicalBoundaryRealizations(List<Node> nodes, Collection<Constraint> constraints,
		Map<CompiledHopKey,Hop> origins, List<CandidateRuleFact> facts) {
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
				&& edge.kind() == ConstraintKind.CONJUNCTIVE
				&& (edge.evidence().startsWith("function-argument:")
					|| edge.evidence().startsWith("inlined-function-argument:"));
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
			for(CompiledHopKey source : incoming.getOrDefault(target, List.of()))
				complete &= collectSources(source, byKey, incoming, new HashSet<>(), leaves);
			if(complete && !leaves.isEmpty())
				sources.put(target, List.copyOf(leaves));
		}
		for(CandidateRuleFact fact : facts)
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateEmissionRealization realization : emission.realizations())
						for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
							PlacementState state = realization.key().emissionState().placementState();
							DurableAnchorKey pool = realization.nativeWorkerPoolResidencyForOwnedClause(clause);
							if(state.output() == FederatedOutput.FOUT && pool == null)
								continue; // Staging lineage is not native execution authority.
							options.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>())
								.add(new Option(CandidateRealizationReference.of(fact.key(), realization), state,
									pool, realization.nativeWorkerPoolLayoutExactForOwnedClause(clause)));
						}
		options.replaceAll((key, values) -> values.stream().distinct().toList());
		relations = sources.entrySet().stream()
			.filter(entry -> options.getOrDefault(entry.getKey(), List.of()).stream()
				.anyMatch(option -> requiresNativeBoundaryProof(option.reference().realization().emissionState())))
			.flatMap(entry -> entry.getValue().stream().map(source -> new Relation(source, entry.getKey())))
			.sorted(java.util.Comparator.comparing((Relation relation) -> relation.target().normalizedSignature())
				.thenComparing(relation -> relation.source().normalizedSignature())).toList();
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
		List<CandidateRuleFact> current = facts;
		for(int pass = 0; pass <= nodes.size(); pass++) {
			List<CandidateRuleFact> next = new LogicalBoundaryRealizations(nodes, constraints, origins, current)
				.bind(current);
			if(next.equals(current))
				return next;
			current = next;
		}
		throw new IllegalStateException("Logical boundary realization closure did not converge");
	}

	List<CandidateRuleFact> bind(List<CandidateRuleFact> facts) {
		List<CandidateRuleFact> result = new ArrayList<>(facts.size());
		for(CandidateRuleFact fact : facts) {
			CompiledHopKey target = fact.key().parentOccurrence();
			if(!declared.contains(target) || fact.status() != CandidateEvaluationStatus.AVAILABLE) {
				result.add(fact);
				continue;
			}
			List<CandidateEmissionFact> emissions = new ArrayList<>();
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				PlacementState state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| emission.derivedFoutAction() != null || emission.emissionState().derivedFedFout()) {
					emissions.add(emission);
					continue;
				}
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
				// No pool is a staging result, not permission to use an arbitrary anchor.
				emissions.add(realizations.isEmpty()
					? new CandidateEmissionFact(emission.emissionState(), emission.executionFType())
					: new CandidateEmissionFact(emission.emissionState(), emission.executionFType(), null, realizations));
			}
			result.add(new CandidateRuleFact(fact.key(), fact.status(), fact.capability(), fact.shapeProof(),
				fact.profile(), emissions, fact.failureCode()));
		}
		return List.copyOf(result);
	}

	void validate(List<CandidateRuleFact> facts) {
		for(CandidateRuleFact fact : facts)
			if(declared.contains(fact.key().parentOccurrence()) && fact.status() == CandidateEvaluationStatus.AVAILABLE)
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					if(requiresNativeBoundaryProof(emission.emissionState()) && emission.derivedFoutAction() == null)
						for(CandidateEmissionRealization realization : emission.realizations())
							for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
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
			if(targets.stream().noneMatch(target -> target.pool() != null && inputs.stream().anyMatch(source ->
				source.pool() != null && compatiblePools(target.pool(), target.exactLayout(),
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
				receipt.realization().key().emissionState().placementState(),
				receipt.realization().nativeWorkerPoolResidencyWitness(receipt.supportClause()),
				receipt.realization().nativeWorkerPoolLayoutExact(receipt.supportClause())));
		PlacementState state = assignment.get(key);
		List<PlacementState> domain = remaining.get(key);
		return options.getOrDefault(key, List.of()).stream()
			.filter(option -> state == null || state.equals(option.state()))
			.filter(option -> state != null || domain == null || domain.contains(option.state())).toList();
	}
}
