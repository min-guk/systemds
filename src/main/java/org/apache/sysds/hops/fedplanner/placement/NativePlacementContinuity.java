/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.Direction;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.wink.json4j.JSONObject;
import org.apache.wink.json4j.JSONException;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Conservative proof that native FED/FOUT execution preserves one physical worker pool. */
final class NativePlacementContinuity {
	private static final Set<String> NATIVE_UNARY_ELEMWISE_OPCODES =
		Set.copyOf(new Rulesets.UnaryElemwiseRule().opcodes());
	private static final Set<String> NATIVE_BINARY_ELEMWISE_OPCODES =
		Set.copyOf(new Rulesets.BinaryElemwiseRule().opcodes());
	private final Map<CompiledHopKey,Node> nodesByKey;
	private final Map<CompiledHopKey,Hop> originsByKey;
	private final Map<CompiledHopKey,List<CandidateRuleFact>> candidateFactsByKey;
	private final Map<CompiledHopKey,Map<Integer,CompiledInputEdgeFact>> edgesByConsumer;
	private final Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions;
	private final Set<CompiledHopKey> incompleteSources;
	private final Map<CompiledHopKey,Privacy> privacyByKey;

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions, Set.of());
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources) {
		this(nodesByKey, originsByKey, candidateFacts, compiledEdges, reachingDefinitions,
			incompleteSources, Map.of());
	}

	NativePlacementContinuity(Map<CompiledHopKey,Node> nodesByKey,
		Map<CompiledHopKey,Hop> originsByKey, List<CandidateRuleFact> candidateFacts,
		List<CompiledInputEdgeFact> compiledEdges,
		Map<CompiledHopKey,List<CompiledHopKey>> reachingDefinitions,
		Set<CompiledHopKey> incompleteSources, Map<CompiledHopKey,Privacy> privacyByKey) {
		this.nodesByKey = copyIdentityMap(nodesByKey, "nodesByKey");
		this.originsByKey = copyIdentityMap(originsByKey, "originsByKey");
		this.privacyByKey = copyIdentityMap(privacyByKey, "privacyByKey");
		this.reachingDefinitions = copyIdentityLists(reachingDefinitions, "reachingDefinitions");
		Set<CompiledHopKey> incomplete = Collections.newSetFromMap(new IdentityHashMap<>());
		incomplete.addAll(Objects.requireNonNull(incompleteSources, "incompleteSources"));
		this.incompleteSources = Collections.unmodifiableSet(incomplete);
		candidateFactsByKey = new IdentityHashMap<>();
		for(CandidateRuleFact fact : List.copyOf(Objects.requireNonNull(candidateFacts, "candidateFacts")))
			candidateFactsByKey.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(fact);
		candidateFactsByKey.replaceAll((ignored, facts) -> List.copyOf(facts));
		edgesByConsumer = new IdentityHashMap<>();
		for(CompiledInputEdgeFact edge : List.copyOf(Objects.requireNonNull(compiledEdges, "compiledEdges"))) {
			Map<Integer,CompiledInputEdgeFact> positions = edgesByConsumer.computeIfAbsent(
				edge.consumer(), ignored -> new java.util.LinkedHashMap<>());
			if(positions.put(edge.inputPosition(), edge) != null)
				throw new IllegalArgumentException("Duplicate compiled input edge position");
		}
	}

	boolean proves(List<CompiledHopKey> sources, DurableAnchorKey externalSeed) {
		Objects.requireNonNull(sources, "sources");
		NativePoolWitness witness = NativePoolWitness.from(
			Objects.requireNonNull(externalSeed, "externalSeed"));
		if(sources.isEmpty() || witness == null)
			return false;
		Map<CompiledHopKey,ProofNode> proof = new IdentityHashMap<>();
		for(CompiledHopKey source : sources)
			buildProof(Objects.requireNonNull(source, "source"), witness, proof);
		if(proof.values().stream().anyMatch(node -> !node.valid))
			return false;

		Map<CompiledHopKey,Boolean> grounded = new IdentityHashMap<>();
		proof.forEach((key, node) -> grounded.put(key, node.directGround));
		boolean changed;
		do {
			changed = false;
			for(var entry : proof.entrySet()) {
				if(grounded.get(entry.getKey()))
					continue;
				if(entry.getValue().dependencies.stream().anyMatch(key -> grounded.getOrDefault(key, false))) {
					grounded.put(entry.getKey(), true);
					changed = true;
				}
			}
		}
		while(changed);
		return proof.keySet().stream().allMatch(key -> grounded.getOrDefault(key, false));
	}

	NativeContinuityProof proveCandidate(CandidateRealizationReference source,
		DurableAnchorKey externalSeed) {
		List<NativeContinuityProof> alternatives = proveCandidateAlternatives(source, externalSeed);
		return alternatives.isEmpty() ? null : alternatives.get(0);
	}

	List<NativeContinuityProof> proveCandidateAlternatives(CandidateRealizationReference source,
		DurableAnchorKey externalSeed) {
		Objects.requireNonNull(source, "source");
		NativePoolWitness seedWitness = NativePoolWitness.from(
			Objects.requireNonNull(externalSeed, "externalSeed"));
		if(seedWitness == null)
			return List.of();
		FType outputType = source.realization().emissionState().placementState().fType();
		NativePoolWitness witness = seedWitness.retyped(outputType);
		if(witness == null)
			return List.of();
		CandidateProofState root = new CandidateProofState(source.rule().parentOccurrence(), source, witness, true);
		Map<CandidateProofState,List<SelectedCandidateProof>> graph = new java.util.LinkedHashMap<>();
		Map<CompiledHopKey,CandidateRealizationReference> fixed = new IdentityHashMap<>();
		fixed.put(source.rule().parentOccurrence(), source);
		buildCandidateProofGraph(root, graph, new java.util.HashSet<>(), fixed);
		Map<CandidateProofState,List<SelectedCandidateProof>> viable = new java.util.LinkedHashMap<>(graph);
		boolean changed;
		do {
			changed = false;
			for(var entry : new ArrayList<>(viable.entrySet())) {
				List<SelectedCandidateProof> retained = entry.getValue().stream()
					.filter(alternative -> alternative.dependencies.stream().allMatch(dependency ->
						!viable.getOrDefault(dependency.state(), List.of()).isEmpty()))
					.toList();
				if(!retained.equals(entry.getValue())) {
					viable.put(entry.getKey(), retained);
					changed = true;
				}
			}
		}
		while(changed);
		Set<CandidateProofState> grounded = groundedCandidateStates(viable);
		List<NativeContinuityProof> proofs = new ArrayList<>();
		if(grounded.contains(root))
			for(SelectedCandidateProof alternative : viable.getOrDefault(root, List.of())) {
				if((!alternative.directGround && alternative.dependencies.isEmpty())
					|| !alternative.dependencies.stream().allMatch(dependency -> grounded.contains(dependency.state())))
					continue;
				List<List<CandidateRealizationInputBinding>> immediateOptions = new ArrayList<>();
				boolean complete = true;
				for(CandidateProofDependency dependency : alternative.dependencies) {
					if(dependency.inputPosition() < 0)
						continue;
					List<CandidateRealizationReference> options = viable.getOrDefault(dependency.state(), List.of())
						.stream().filter(option -> option.realization != null)
						.filter(option -> (option.directGround || !option.dependencies.isEmpty())
							&& option.dependencies.stream().allMatch(child -> grounded.contains(child.state())))
						.map(SelectedCandidateProof::realization).distinct().sorted().toList();
					if(options.isEmpty()) {
						if(viable.getOrDefault(dependency.state(), List.of()).stream()
							.noneMatch(option -> option.directGround)) {
							complete = false;
							break;
						}
						continue;
					}
					immediateOptions.add(options.stream().map(reference ->
						CandidateRealizationInputBinding.direct(dependency.inputPosition(), reference)).toList());
				}
					if(complete)
						enumerateImmediateSupports(immediateOptions, 0, new ArrayList<>(), support ->
							proofs.add(new NativeContinuityProof(externalSeed,
								support.stream().distinct().sorted().toList())));
				}
		return proofs.stream().distinct().sorted(java.util.Comparator.comparing(
			NativeContinuityProof::normalizedSignature)).toList();
	}

	private static void enumerateImmediateSupports(List<List<CandidateRealizationInputBinding>> options,
		int ordinal, List<CandidateRealizationInputBinding> current,
		java.util.function.Consumer<List<CandidateRealizationInputBinding>> consumer) {
		if(ordinal == options.size()) {
			consumer.accept(List.copyOf(current));
			return;
		}
		for(CandidateRealizationInputBinding binding : options.get(ordinal)) {
			current.add(binding);
			enumerateImmediateSupports(options, ordinal + 1, current, consumer);
			current.remove(current.size() - 1);
		}
	}

	private static Set<CandidateProofState> groundedCandidateStates(
		Map<CandidateProofState,List<SelectedCandidateProof>> viable) {
		Map<CandidateProofState,Set<CandidateProofState>> reachability = new java.util.LinkedHashMap<>();
		for(CandidateProofState state : viable.keySet()) {
			Set<CandidateProofState> reached = new java.util.HashSet<>();
			java.util.ArrayDeque<CandidateProofState> pending = new java.util.ArrayDeque<>();
			pending.add(state);
			while(!pending.isEmpty()) {
				CandidateProofState next = pending.removeFirst();
				if(!reached.add(next))
					continue;
				for(SelectedCandidateProof alternative : viable.getOrDefault(next, List.of()))
					for(CandidateProofDependency dependency : alternative.dependencies)
						if(viable.containsKey(dependency.state()))
							pending.addLast(dependency.state());
			}
			reachability.put(state, reached);
		}
		List<Set<CandidateProofState>> components = new ArrayList<>();
		Set<CandidateProofState> assigned = new java.util.HashSet<>();
		for(CandidateProofState state : viable.keySet()) {
			if(assigned.contains(state))
				continue;
			Set<CandidateProofState> component = new java.util.HashSet<>();
			for(CandidateProofState candidate : viable.keySet())
				if(reachability.get(state).contains(candidate) && reachability.get(candidate).contains(state))
					component.add(candidate);
			assigned.addAll(component);
			components.add(component);
		}
		Set<CandidateProofState> grounded = new java.util.HashSet<>();
		boolean changed;
		do {
			changed = false;
			for(Set<CandidateProofState> component : components) {
				if(grounded.containsAll(component))
					continue;
				boolean everyStateSupported = component.stream().allMatch(state ->
					viable.getOrDefault(state, List.of()).stream().anyMatch(alternative ->
						isGroundableAlternative(alternative, component, grounded)));
				boolean hasGroundPath = component.stream().flatMap(state ->
					viable.getOrDefault(state, List.of()).stream())
					.filter(alternative -> isGroundableAlternative(alternative, component, grounded))
					.anyMatch(alternative ->
					alternative.directGround || alternative.dependencies.stream().anyMatch(dependency ->
						!component.contains(dependency.state()) && grounded.contains(dependency.state())));
				if(everyStateSupported && hasGroundPath) {
					grounded.addAll(component);
					changed = true;
				}
			}
		}
		while(changed);
		return grounded;
	}

	private static boolean isGroundableAlternative(SelectedCandidateProof alternative,
		Set<CandidateProofState> component, Set<CandidateProofState> grounded) {
		return (alternative.directGround || !alternative.dependencies.isEmpty())
			&& alternative.dependencies.stream().allMatch(dependency ->
				component.contains(dependency.state()) || grounded.contains(dependency.state()));
	}

	private void buildCandidateProofGraph(CandidateProofState state,
		Map<CandidateProofState,List<SelectedCandidateProof>> graph, Set<CandidateProofState> active,
		Map<CompiledHopKey,CandidateRealizationReference> fixed) {
		if(graph.containsKey(state) || !active.add(state))
			return;
		List<SelectedCandidateProof> alternatives = candidateProofAlternatives(
			state.key(), state.realization(), state.witness(), state.templateRoot(), fixed);
		graph.put(state, alternatives);
		for(SelectedCandidateProof alternative : alternatives)
			for(CandidateProofDependency dependency : alternative.dependencies)
				buildCandidateProofGraph(dependency.state(), graph, active, fixed);
		active.remove(state);
	}

	private List<SelectedCandidateProof> candidateProofAlternatives(CompiledHopKey key,
		CandidateRealizationReference pinned, NativePoolWitness witness, boolean allowPinnedTemplate,
		Map<CompiledHopKey,CandidateRealizationReference> fixed) {
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		if(node == null || hop == null || incompleteSources.contains(key)
			|| node.legalAlternatives().stream().noneMatch(state -> state.execType() == ExecType.FED
				&& state.output() == FederatedOutput.FOUT && state.fType() == witness.fType))
			return List.of();
		boolean nodeDirectGround = node.anchors().stream().map(NativePoolWitness::from)
			.filter(Objects::nonNull).anyMatch(witness::equals);
		List<SelectedCandidateProof> alternatives = new ArrayList<>();
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE
				|| pinned != null && !fact.key().equals(pinned.rule())
				|| isBroadcastRowProvablyUnselectable(fact)
				|| !operationPreservesWitness(hop, witness, fact))
				continue;
			for(var emission : fact.allowedEmissionFacts()) {
				var state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType || emission.executionFType() != witness.fType
					|| emission.derivedFoutAction() != null || emission.emissionState().derivedFedFout())
					continue;
				for(CandidateEmissionRealization realization : emission.realizations()) {
					CandidateRealizationReference reference = CandidateRealizationReference.of(
						fact.key(), realization);
					if(pinned != null && !reference.equals(pinned))
						continue;
					for(CandidateRealizationSupportClause clause : realization.supportClauses()) {
						if(pinned == null && realization.key().layoutKind()
							== PlacementIdentity.PlacementLayoutKind.NATIVE_LINEAGE
							&& clause.inputBindings().isEmpty()
							&& !(hop instanceof DataOp data && (data.getOp() == OpOpData.TRANSIENTREAD
								|| data.getOp() == OpOpData.TRANSIENTWRITE))
							&& fact.key().orderedInputs().stream().anyMatch(input -> input.present()))
							continue;
						List<CandidateProofDependency> dependencies = candidateDependencies(
							fact, clause, hop, witness, fixed);
						if(dependencies != null) {
							boolean realizationGround = realization.key().layoutKind()
								== PlacementIdentity.PlacementLayoutKind.DURABLE_MAP
								&& witness.equals(NativePoolWitness.from(realization.anchor()))
								|| clause.nativeWorkerPoolWitness() != null
									&& witness.equals(NativePoolWitness.from(clause.nativeWorkerPoolWitness()));
							alternatives.add(new SelectedCandidateProof(reference, clause, dependencies,
								realizationGround, witness));
						}
					}
				}
			}
		}
		if(alternatives.isEmpty() && allowPinnedTemplate && pinned != null)
			for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
				if(!fact.key().equals(pinned.rule()) || fact.status() != CandidateEvaluationStatus.AVAILABLE
					|| !operationPreservesWitness(hop, witness, fact))
					continue;
				boolean matchingEmission = fact.allowedEmissionFacts().stream().anyMatch(emission ->
					emission.emissionState().equals(pinned.realization().emissionState())
						&& emission.executionFType() == witness.fType && emission.derivedFoutAction() == null);
				if(!matchingEmission)
					continue;
				CandidateRealizationSupportClause templateClause =
					new CandidateRealizationSupportClause(List.of(), List.of());
				List<CandidateProofDependency> dependencies = candidateDependencies(
					fact, templateClause, hop, witness, fixed);
				if(dependencies != null)
					alternatives.add(new SelectedCandidateProof(pinned, templateClause, dependencies,
						false, witness));
			}
		if(pinned == null && nodeDirectGround)
			alternatives.add(new SelectedCandidateProof(null, null, List.of(), true, witness));
		return alternatives.stream().sorted((left, right) -> {
			if(left.realization == null) return right.realization == null ? 0 : -1;
			if(right.realization == null) return 1;
			return left.realization.compareTo(right.realization);
		}).toList();
	}

	private List<CandidateProofDependency> candidateDependencies(CandidateRuleFact fact,
		CandidateRealizationSupportClause clause, Hop owner, NativePoolWitness witness,
		Map<CompiledHopKey,CandidateRealizationReference> fixed) {
		Map<CompiledHopKey,CandidateRealizationReference> pinned = new IdentityHashMap<>();
		for(CandidateRealizationReference support : clause.requiredInputSupport())
			pinned.put(support.rule().parentOccurrence(), support);
		fixed.forEach(pinned::put);
		List<CandidateProofDependency> dependencies = new ArrayList<>();
		for(CompiledHopKey source : reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()))
			dependencies.add(new CandidateProofDependency(source, pinned.get(source), witness, -1));
		if(owner instanceof DataOp data && data.getOp() == OpOpData.FEDERATED)
			return dependencies.isEmpty() ? dependencies : null;
		if(owner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD)
			return (fact.key().orderedInputs().stream().anyMatch(input -> input.present())
				|| fact.key().orderedInputs().isEmpty() && clause.nativeWorkerPoolWitness() != null
					&& witness.equals(NativePoolWitness.from(clause.nativeWorkerPoolWitness())))
				&& fact.key().orderedInputs().stream().noneMatch(input -> input.present()
					&& input.fType() != witness.fType) ? dependencies : null;
		Map<Integer,CompiledInputEdgeFact> edges = edgesByConsumer.getOrDefault(
			fact.key().parentOccurrence(), Map.of());
		boolean presentPlacementData = false;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			boolean placementData = edge != null && isPlacementDataOrigin(edge.producer())
				|| edge == null && position < owner.getInput().size()
					&& isPlacementData(owner.getInput(position));
			if(!placementData)
				continue;
			presentPlacementData = true;
			NativePoolWitness dependencyWitness = owner instanceof ReorgOp reorg
				&& reorg.getOp() == ReOrgOp.TRANS ? witness.transposed() : witness;
			// ROW matmul preserves the LHS partition axis, while its replicated RHS
			// must be present on precisely the same canonical host:port pool. Do not
			// reinterpret the RHS as ROW or discard the LHS interval witness.
			if(owner instanceof AggBinaryOp && witness.fType == FType.ROW
				&& position == 1 && input.fType() == FType.BROADCAST)
				dependencyWitness = new NativePoolWitness(FType.BROADCAST, witness.endpoints, List.of());
			if(dependencyWitness == null || input.fType() != dependencyWitness.fType || edge == null)
				return null;
			dependencies.add(new CandidateProofDependency(edge.producer(), pinned.get(edge.producer()),
				dependencyWitness, position));
		}
		if(!presentPlacementData && !(transformEncodePreservesPool(owner, witness.fType)
			&& !reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()).isEmpty()))
			return null;
		return dependencies.stream().distinct().toList();
	}

	record NativeContinuityProof(DurableAnchorKey externalSeed,
		List<CandidateRealizationInputBinding> immediateBindings) {
		NativeContinuityProof {
			Objects.requireNonNull(externalSeed, "externalSeed");
			immediateBindings = immediateBindings.stream().distinct().sorted().toList();
		}
		String normalizedSignature() {
			return externalSeed.normalizedSignature() + "|bindings=" + immediateBindings.stream()
				.map(CandidateRealizationInputBinding::normalizedSignature).toList();
		}
	}

	private record CandidateProofDependency(CompiledHopKey key,
		CandidateRealizationReference realization, NativePoolWitness witness, int inputPosition) {
		private CandidateProofState state() { return new CandidateProofState(key, realization, witness, false); }
	}
	private record CandidateProofState(CompiledHopKey key,
		CandidateRealizationReference realization, NativePoolWitness witness, boolean templateRoot) { }
	private record SelectedCandidateProof(CandidateRealizationReference realization,
		CandidateRealizationSupportClause clause, List<CandidateProofDependency> dependencies,
		boolean directGround, NativePoolWitness witness) { }

	private void buildProof(CompiledHopKey key, NativePoolWitness witness,
		Map<CompiledHopKey,ProofNode> proof) {
		if(proof.containsKey(key))
			return;
		Node node = nodesByKey.get(key);
		Hop hop = originsByKey.get(key);
		ProofNode current = new ProofNode();
		proof.put(key, current);
		if(node == null || hop == null) {
			current.valid = false;
			return;
		}
		if(node.legalAlternatives().stream().noneMatch(state -> state.execType() == ExecType.FED
			&& state.output() == FederatedOutput.FOUT && state.fType() == witness.fType))
			current.valid = false;
		current.directGround = node.anchors().stream()
			.map(NativePoolWitness::from).filter(Objects::nonNull).anyMatch(witness::equals);

		if(incompleteSources.contains(key))
			current.valid = false;
		for(CompiledHopKey source : reachingDefinitions.getOrDefault(key, List.of()))
			addDependency(current, source);

		boolean matchedNativeRow = false;
		for(CandidateRuleFact fact : candidateFactsByKey.getOrDefault(key, List.of())) {
			if(fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			if(isBroadcastRowProvablyUnselectable(fact))
				continue;
			boolean matchingNativeRow = false;
			for(var emission : fact.allowedEmissionFacts()) {
				var state = emission.emissionState().placementState();
				if(state.execType() != ExecType.FED || state.output() != FederatedOutput.FOUT
					|| state.fType() != witness.fType)
					continue;
				if(emission.executionFType() == witness.fType && emission.derivedFoutAction() == null
					&& !emission.emissionState().derivedFedFout())
					matchingNativeRow = true;
			}
			if(!matchingNativeRow)
				continue;
			matchedNativeRow = true;
			if(!operationPreservesWitness(hop, witness, fact))
				current.valid = false;
			collectCandidateDependencies(fact, hop, witness, current);
		}

		boolean transientWrite = hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTWRITE;
		boolean computedValue = hop instanceof AggUnaryOp || hop instanceof UnaryOp || hop instanceof BinaryOp
			|| hop instanceof ReorgOp || hop instanceof TernaryOp || hop instanceof NaryOp
			|| hop instanceof ParameterizedBuiltinOp || hop instanceof LeftIndexingOp;
		if(!matchedNativeRow && (transientWrite || requiresExactNativeRow(hop)
			|| computedValue && !current.directGround))
			current.valid = false;
		for(CompiledHopKey dependency : current.dependencies)
			buildProof(dependency, witness, proof);
	}

	private boolean isBroadcastRowProvablyUnselectable(CandidateRuleFact fact) {
		Map<Integer,CompiledInputEdgeFact> edges = edgesByConsumer
			.getOrDefault(fact.key().parentOccurrence(), Map.of());
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present() || input.fType() != FType.BROADCAST)
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			Node source = edge == null ? null : nodesByKey.get(edge.producer());
			if(source == null)
				return false;
			boolean direct = nodesByKey.values().stream()
				.filter(alias -> alias.valueVersion().equals(source.valueVersion()))
				.anyMatch(alias -> alias.legalAlternatives().stream().anyMatch(state ->
					state.output() == FederatedOutput.FOUT && state.fType() == FType.BROADCAST));
			Privacy privacy = privacyByKey.get(source.key());
			if(!direct && privacy != null && ExecPlacementPolicy.requiresOriginResidency(privacy))
				return true;
		}
		return false;
	}

	private void collectCandidateDependencies(CandidateRuleFact fact, Hop owner,
		NativePoolWitness witness, ProofNode proof) {
		if(owner instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
			if(!proof.directGround)
				proof.valid = false;
			return;
		}
		if(owner instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD) {
			if(fact.key().orderedInputs().stream().noneMatch(input -> input.present()))
				proof.valid = false;
			else if(fact.key().orderedInputs().stream()
				.anyMatch(input -> input.present() && input.fType() != witness.fType))
				proof.valid = false;
			return;
		}
		Map<Integer,CompiledInputEdgeFact> edges = edgesByConsumer.getOrDefault(
			fact.key().parentOccurrence(), Map.of());
		boolean presentPlacementData = false;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			boolean placementData = edge != null && isPlacementDataOrigin(edge.producer())
				|| edge == null && position < owner.getInput().size()
					&& isPlacementData(owner.getInput(position));
			if(!placementData)
				continue;
			presentPlacementData = true;
			if(input.fType() != witness.fType || edge == null) {
				proof.valid = false;
				continue;
			}
			addDependency(proof, edge.producer());
		}
		if(!presentPlacementData && !(transformEncodePreservesPool(owner, witness.fType)
			&& !reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()).isEmpty()))
			proof.valid = false;
	}

	private boolean isPlacementDataOrigin(CompiledHopKey key) {
		return isPlacementData(originsByKey.get(key));
	}

	private static boolean isPlacementData(Hop hop) {
		return hop != null && (hop.getDataType().isMatrix() || hop.getDataType().isFrame());
	}

	private static boolean requiresExactNativeRow(Hop hop) {
		if(hop instanceof AggUnaryOp || hop instanceof LeftIndexingOp || hop instanceof UnaryOp)
			return true;
		return hop instanceof BinaryOp binary && binary.getInput().size() == 2
			&& binary.getInput(0).getDataType().isMatrix()
			&& binary.getInput(1).getDataType().isMatrix();
	}

	private static boolean operationPreservesWitness(Hop hop, NativePoolWitness witness, CandidateRuleFact fact) {
		if(transformEncodePreservesPool(hop, witness.fType)
			|| hop instanceof FunctionOp call && call.getOutputs() != null && !call.getOutputs().isEmpty()
				&& FederatedPlannerUtils.getMultiReturnFunctionOutputParent(call.getOutputs().get(0)) == call
				&& transformEncodePreservesPool(call.getOutputs().get(0), witness.fType))
			return true;
		if(hop instanceof AggUnaryOp aggregate && aggregate.getInput().size() == 1) {
			var inputs = fact.key().orderedInputs();
			if(inputs.size() != 1 || !inputs.get(0).present() || inputs.get(0).fType() != witness.fType)
				return false;
			return switch(witness.fType) {
				case BROADCAST -> true;
				case FULL -> witness.singleEndpoint();
				case ROW -> aggregate.getDirection() == Direction.Row;
				case COL -> aggregate.getDirection() == Direction.Col;
				default -> false;
			};
		}
		if(hop instanceof AggBinaryOp aggregate && aggregate.getInput().size() == 2) {
			var inputs = fact.key().orderedInputs();
			if(inputs.size() != 2)
				return false;
			boolean leftMatrix = aggregate.getInput(0).getDataType().isMatrix();
			boolean rightMatrix = aggregate.getInput(1).getDataType().isMatrix();
			if(!leftMatrix || !rightMatrix)
				return false;
			// The existing FULL kernel executes the complete product on one worker.
			// Exact input dependencies still prove the chosen FULL operands; local
			// companions use the oracle-authorized native broadcast, not a new map.
			if(witness.fType == FType.FULL && witness.singleEndpoint())
				return inputs.stream().anyMatch(input -> input.present() && input.fType() == FType.FULL)
					&& inputs.stream().allMatch(input -> !input.present() || input.fType() == FType.FULL);
			return witness.fType == FType.ROW
				&& inputs.get(0).present() && inputs.get(0).fType() == FType.ROW
				&& (!inputs.get(1).present() || inputs.get(1).fType() == FType.BROADCAST)
				|| witness.fType == FType.BROADCAST && !inputs.get(0).present()
					&& inputs.get(1).present() && inputs.get(1).fType() == FType.BROADCAST;
		}
		if(hop instanceof LeftIndexingOp) {
			var inputs = fact.key().orderedInputs();
			if(witness.fType != FType.FULL || !witness.singleEndpoint() || inputs.size() != hop.getInput().size()
				|| inputs.size() < 2 || inputs.subList(2, inputs.size()).stream().anyMatch(input -> input.present()))
				return false;
			var lhs = inputs.get(0);
			var rhs = inputs.get(1);
			return hop.getInput(1).getDataType().isMatrix()
				? rhs.present() && rhs.fType() == FType.FULL && (!lhs.present() || lhs.fType() == FType.FULL)
				: hop.getInput(1).getDataType().isScalar() && !rhs.present()
					&& lhs.present() && lhs.fType() == FType.FULL;
		}
		if(hop instanceof DataOp data)
			return data.getOp() == OpOpData.FEDERATED || data.getOp() == OpOpData.TRANSIENTREAD
				|| data.getOp() == OpOpData.TRANSIENTWRITE;
		if(hop instanceof UnaryOp unary && unary.getOp() == OpOp1.CAST_AS_FRAME
			&& unary.getDataType().isFrame() && unary.getInput().size() == 1
			&& unary.getInput(0).getDataType().isMatrix())
			return true;
		if(hop instanceof UnaryOp unary) {
			var inputs = fact.key().orderedInputs();
			return unary.getDataType().isMatrix() && unary.getInput().size() == 1
				&& unary.getInput(0).getDataType().isMatrix()
				&& NATIVE_UNARY_ELEMWISE_OPCODES.contains(unary.getOp().toString())
				&& inputs.size() == 1 && inputs.get(0).present()
				&& inputs.get(0).fType() == witness.fType;
		}
		if(hop instanceof BinaryOp binary && (binary.getOp() == OpOp2.CBIND || binary.getOp() == OpOp2.RBIND)) {
			if(witness.fType == FType.ROW)
				return binary.getOp() == OpOp2.CBIND;
			if(witness.fType == FType.COL)
				return binary.getOp() == OpOp2.RBIND;
			return witness.fType == FType.FULL && witness.singleEndpoint();
		}
		if(hop instanceof BinaryOp binary && binary.getInput().size() == 2) {
			boolean leftMatrix = binary.getInput(0).getDataType().isMatrix();
			boolean rightMatrix = binary.getInput(1).getDataType().isMatrix();
			if(leftMatrix != rightMatrix)
				return true;
			var inputs = fact.key().orderedInputs();
			if(leftMatrix && rightMatrix)
				return witness.fType == FType.FULL && witness.singleEndpoint()
					&& NATIVE_BINARY_ELEMWISE_OPCODES.contains(binary.getOp().toString())
					&& inputs.size() == 2
					&& inputs.stream().anyMatch(input -> input.present() && input.fType() == FType.FULL)
					&& inputs.stream().allMatch(input -> !input.present() || input.fType() == FType.FULL);
		}
		if(hop instanceof NaryOp nary)
			return nary.getOp() == OpOpN.PLUS || nary.getOp() == OpOpN.MULT
				|| nary.getOp() == OpOpN.MIN || nary.getOp() == OpOpN.MAX;
		if(hop instanceof TernaryOp ternary)
			return ternary.getOp() == OpOp3.PLUS_MULT || ternary.getOp() == OpOp3.MINUS_MULT
				|| ternary.getOp() == OpOp3.IFELSE;
		if(hop instanceof ParameterizedBuiltinOp parameterized)
			return parameterized.getOp() == ParamBuiltinOp.REPLACE;
		if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS) {
			if(witness.fType == FType.FULL)
				return witness.singleEndpoint();
			if(witness.fType == FType.BROADCAST)
				return true;
			NativePoolWitness inputWitness = witness.transposed();
			return inputWitness != null && fact.key().orderedInputs().size() == 1
				&& fact.key().orderedInputs().get(0).present()
				&& fact.key().orderedInputs().get(0).fType() == inputWitness.fType;
		}
		if(hop instanceof IndexingOp && hop.getDataType().isMatrix()
			&& !hop.getInput().isEmpty() && hop.getInput(0).getDataType().isMatrix())
			return witness.fType == FType.FULL && witness.singleEndpoint();
		return false;
	}

	static boolean transformEncodePreservesPool(Hop hop, FType type) {
		if(type != FType.ROW && type != FType.FULL || !(hop instanceof DataOp data)
			|| data.getOp() != OpOpData.FUNCTIONOUTPUT || !hop.getDataType().isMatrix()
			|| hop.getInput().size() != 1 || !hop.getInput(0).getDataType().isFrame())
			return false;
		FunctionOp call = FederatedPlannerUtils.getMultiReturnFunctionOutputParent(hop);
		if(call == null || call.getFunctionType() != FunctionOp.FunctionType.MULTIRETURN_BUILTIN
			|| !"transformencode".equalsIgnoreCase(call.getFunctionName())
			|| call.getInput().size() != 2 || call.getOutputs().size() != 2
			|| call.getOutputs().get(0) != hop)
			return false;
		if(type == FType.FULL)
			return true;
		if(!(call.getInput(1) instanceof LiteralOp literal))
			return false;
		try {
			JSONObject spec = new JSONObject(literal.getStringValue());
			for(Object key : spec.keySet())
				if(!Set.of("ids", "recode", "dummycode", "cofeePublicRecodeMetadata").contains(key))
					return false;
			return spec.containsKey("recode") || spec.containsKey("dummycode");
		}
		catch(JSONException ex) {
			return false;
		}
	}

	private static void addDependency(ProofNode proof, CompiledHopKey dependency) {
		if(dependency != null && proof.dependencies.stream().noneMatch(existing -> existing == dependency))
			proof.dependencies.add(dependency);
	}

	private static final class ProofNode {
		private final List<CompiledHopKey> dependencies = new ArrayList<>();
		private boolean valid = true;
		private boolean directGround;
	}

	private record AxisInterval(String endpoint, long begin, long end)
		implements Comparable<AxisInterval> {
		@Override public int compareTo(AxisInterval that) {
			int endpointOrder = endpoint.compareTo(that.endpoint);
			if(endpointOrder != 0)
				return endpointOrder;
			int beginOrder = Long.compare(begin, that.begin);
			return beginOrder != 0 ? beginOrder : Long.compare(end, that.end);
		}
	}

	private record NativePoolWitness(FType fType, List<String> endpoints,
		List<AxisInterval> partitionAxisIntervals) {
		private NativePoolWitness {
			endpoints = List.copyOf(endpoints);
			partitionAxisIntervals = List.copyOf(partitionAxisIntervals);
		}

		private static NativePoolWitness from(DurableAnchorKey anchor) {
			if(anchor == null || anchor.fType() == FType.PART || anchor.fType() == FType.OTHER
				|| anchor.partitions().isEmpty())
				return null;
			if(anchor.fType() == FType.FULL && anchor.partitions().size() != 1)
				return null;
			List<String> endpoints = new ArrayList<>();
			List<AxisInterval> intervals = new ArrayList<>();
			int axis = anchor.fType() == FType.ROW ? 0 : anchor.fType() == FType.COL ? 1 : -1;
			for(AnchorPartition partition : anchor.partitions()) {
				String endpoint = FederationUtils.canonicalFederatedWorkerAddress(partition.workerId());
				if(endpoint == null || endpoint.isBlank())
					return null;
				endpoints.add(endpoint);
				if(axis >= 0) {
					if(partition.begin().size() <= axis || partition.end().size() <= axis)
						return null;
					intervals.add(new AxisInterval(endpoint, partition.begin().get(axis), partition.end().get(axis)));
				}
			}
			Collections.sort(endpoints);
			Collections.sort(intervals);
			return new NativePoolWitness(anchor.fType(), endpoints, intervals);
		}

		private boolean singleEndpoint() {
			return endpoints.size() == 1;
		}

		private NativePoolWitness retyped(FType target) {
			if(target == null || target == FType.PART || target == FType.OTHER)
				return null;
			if(target == fType)
				return this;
			if(target == FType.BROADCAST || fType == FType.BROADCAST)
				return null;
			if((fType == FType.ROW && target == FType.COL)
				|| (fType == FType.COL && target == FType.ROW))
				return new NativePoolWitness(target, endpoints, partitionAxisIntervals);
			return null;
		}

		private NativePoolWitness transposed() {
			return switch(fType) {
				case ROW -> retyped(FType.COL);
				case COL -> retyped(FType.ROW);
				case FULL, BROADCAST -> this;
				default -> null;
			};
		}
	}

	private static <K,V> Map<K,V> copyIdentityMap(Map<K,V> source, String name) {
		Objects.requireNonNull(source, name);
		Map<K,V> copy = new IdentityHashMap<>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"),
			Objects.requireNonNull(value, name + " value")));
		return copy;
	}

	private static <K,V> Map<K,List<V>> copyIdentityLists(Map<K,List<V>> source, String name) {
		Objects.requireNonNull(source, name);
		Map<K,List<V>> copy = new IdentityHashMap<>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"),
			List.copyOf(Objects.requireNonNull(value, name + " value"))));
		return copy;
	}
}
