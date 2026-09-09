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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.BinaryOp;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
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

		// Coinductive cycles are safe only when every reachable node/SCC has a path
		// to an actual matching source anchor. Requiring grounding for every reachable
		// dependency prevents one unrelated good branch from certifying a bad cycle.
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
		boolean computedValue = hop instanceof UnaryOp || hop instanceof BinaryOp || hop instanceof ReorgOp
			|| hop instanceof TernaryOp || hop instanceof NaryOp || hop instanceof ParameterizedBuiltinOp
			|| hop instanceof LeftIndexingOp;
		// An inherited output anchor is not evidence that a newly admitted native
		// runtime family can execute this exact input row; derived-only or absent
		// rows must not certify it.
		if(!matchedNativeRow && (transientWrite || requiresExactNativeRow(hop)
			|| computedValue && !current.directGround))
			current.valid = false;
		for(CompiledHopKey dependency : current.dependencies)
			buildProof(dependency, witness, proof);
	}

	private boolean isBroadcastRowProvablyUnselectable(CandidateRuleFact fact) {
		// Candidate input rows can outlive privacy exclusion of their producer's
		// BROADCAST emission. Exclude such a row from this proof only if no owner
		// of the same value can supply it and origin residency also forbids the
		// explicit relocation. Public inputs may still use a sibling anchor;
		// absent preprivacy authority therefore never makes a row unselectable.
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
		boolean presentMatrix = false;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			var input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			CompiledInputEdgeFact edge = edges.get(position);
			boolean matrix = edge != null && isMatrixOrigin(edge.producer())
				|| edge == null && position < owner.getInput().size()
					&& owner.getInput(position).getDataType().isMatrix();
			if(!matrix)
				continue;
			presentMatrix = true;
			if(input.fType() != witness.fType || edge == null) {
				proof.valid = false;
				continue;
			}
			addDependency(proof, edge.producer());
		}
		if(!presentMatrix && !(transformEncodePreservesPool(owner, witness.fType)
			&& !reachingDefinitions.getOrDefault(fact.key().parentOccurrence(), List.of()).isEmpty()))
			proof.valid = false;
	}

	private boolean isMatrixOrigin(CompiledHopKey key) {
		Hop origin = originsByKey.get(key);
		return origin != null && origin.getDataType().isMatrix();
	}

	private static boolean requiresExactNativeRow(Hop hop) {
		if(hop instanceof LeftIndexingOp || hop instanceof UnaryOp)
			return true;
		return hop instanceof BinaryOp binary && binary.getInput().size() == 2
			&& binary.getInput(0).getDataType().isMatrix()
			&& binary.getInput(1).getDataType().isMatrix();
	}

	private static boolean operationPreservesWitness(Hop hop, NativePoolWitness witness, CandidateRuleFact fact) {
		if(transformEncodePreservesPool(hop, witness.fType))
			return true;
		if(hop instanceof LeftIndexingOp) {
			var inputs = fact.key().orderedInputs();
			if(witness.fType != FType.FULL || !witness.singleEndpoint() || inputs.size() != hop.getInput().size()
				|| inputs.size() < 2 || inputs.subList(2, inputs.size()).stream().anyMatch(input -> input.present()))
				return false;
			var lhs = inputs.get(0);
			var rhs = inputs.get(1);
			// Matrix-RHS native FULL execution is grounded on the RHS, never on
			// an uploaded/collected protected matrix. Scalar updates retain the
			// proven FULL LHS. Generic dependency collection checks every PRESENT
			// matrix edge and keeps this endpoint witness separate from geometry.
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
			// CastFEDInstruction copies the same ranges/endpoints; no row filtering.
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
			// BinaryMatrixScalarFEDInstruction copies the federated matrix's complete
			// map with a new data id; the scalar operand never changes its worker pool.
			if(leftMatrix != rightMatrix)
				return true;
			var inputs = fact.key().orderedInputs();
			// BinaryMatrixMatrixFEDInstruction executes two single-range FULL values
			// directly only on their common worker. Generic dependency collection
			// separately proves both exact PRESENT inputs against this witness.
			if(leftMatrix && rightMatrix)
				return witness.fType == FType.FULL && witness.singleEndpoint()
					&& NATIVE_BINARY_ELEMWISE_OPCODES.contains(binary.getOp().toString())
					&& inputs.size() == 2 && inputs.stream()
						.allMatch(input -> input.present() && input.fType() == FType.FULL);
		}
		// These BuiltinNaryFEDInstruction cell ops publish the selected native
		// input's complete FederationMap with only a new data id.
		if(hop instanceof NaryOp nary)
			return nary.getOp() == OpOpN.PLUS || nary.getOp() == OpOpN.MULT
				|| nary.getOp() == OpOpN.MIN || nary.getOp() == OpOpN.MAX;
		// TernaryFEDInstruction performs these elementwise kernels on the selected
		// worker pool and installs a copy of that native input map on the output.
		if(hop instanceof TernaryOp ternary)
			return ternary.getOp() == OpOp3.PLUS_MULT || ternary.getOp() == OpOp3.MINUS_MULT
				|| ternary.getOp() == OpOp3.IFELSE;
		// FED replace changes cell values only and copies the target's exact map.
		if(hop instanceof ParameterizedBuiltinOp parameterized)
			return parameterized.getOp() == ParamBuiltinOp.REPLACE;
		if(hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS)
			return witness.fType == FType.FULL && witness.singleEndpoint();
		// IndexingFEDInstruction filters the input FederationMap and rebases its
		// ranges, but a nonempty slice of one FULL partition remains on that sole
		// worker. ROW/COL indexing can filter or resize the witnessed partition axis.
		if(hop instanceof IndexingOp && hop.getDataType().isMatrix()
			&& !hop.getInput().isEmpty() && hop.getInput(0).getDataType().isMatrix())
			return witness.fType == FType.FULL && witness.singleEndpoint();
		return false;
	}

	/** Pool/partition-axis proof only: encoded columns and value identity are not copied. */
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
		// Native mapParallel preserves endpoints/cardinality. ROW additionally needs
		// unchanged row intervals: legacy omit removes rows, whereas dummycode changes
		// only columns. Never reuse input COL intervals for an encoded output.
		if(type == FType.FULL)
			return true;
		if(!(call.getInput(1) instanceof LiteralOp literal))
			return false;
		try {
			JSONObject spec = new JSONObject(literal.getStringValue());
			// Keep this proof restricted to known row-preserving transforms, without
			// consulting privacy authorization or widening generic encoder support.
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

	/** Typed worker-pool evidence; value ids and full two-dimensional ranges never escape anchors. */
	private record NativePoolWitness(FType fType, List<String> endpoints,
		List<AxisInterval> partitionAxisIntervals) {
		private NativePoolWitness {
			endpoints = List.copyOf(endpoints);
			partitionAxisIntervals = List.copyOf(partitionAxisIntervals);
		}

		private static NativePoolWitness from(DurableAnchorKey anchor) {
			if(anchor == null || anchor.fType() == FType.PART || anchor.fType() == FType.OTHER
				|| anchor.fType() == FType.BROADCAST || anchor.partitions().isEmpty())
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
