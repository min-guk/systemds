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
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ParamBuiltinOp;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ParameterizedBuiltinOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.lops.MMTSJ.MMTSJType;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;

/**
 * Invocation-local cardinality evidence conditional on a legal FULL value. It
 * proves neither availability, endpoint alignment, exact value ranges, nor a
 * legal placement/movement. Candidate rules and the all-source placement proof
 * remain independent requirements.
 */
final class SinglePartitionFacts {
	// Finite lattice: absent = bottom, one endpoint = exact, empty = unknown.
	private static final String UNKNOWN = "";
	private final Map<Hop,String> endpoints;
	private final Map<CompiledHopKey,String> occurrenceEndpoints;

	SinglePartitionFacts(List<Hop> hops, Map<Hop,List<Hop>> valueSources, Set<Hop> incompleteSources) {
		Map<Hop,String> facts = new IdentityHashMap<>();
		Map<Hop,List<Hop>> dependencies = new IdentityHashMap<>();
		Set<Hop> conditionalFullResultTransfers = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Hop> owned = Collections.newSetFromMap(new IdentityHashMap<>());
		owned.addAll(hops);
		for(Hop hop : hops) {
			if(!hop.getDataType().isMatrix() || incompleteSources.contains(hop)) {
				facts.put(hop, UNKNOWN);
				continue;
			}
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
				var partitions = NeutralPlacementGraphBuilder.fedInitLiteralPartitions(data);
				String endpoint = UNKNOWN;
				if(partitions.size() == 1) {
					var part = partitions.get(0);
					if(part.begin().equals(List.of(0L, 0L))
						&& part.end().get(0) > 0 && part.end().get(1) > 0)
						endpoint = FederationUtils.canonicalFederatedWorkerAddress(part.workerId());
				}
				facts.put(hop, endpoint == null ? UNKNOWN : endpoint);
				continue;
			}
			List<Hop> sources = valueSources.get(hop);
			if(hop instanceof DataOp data && data.getOp() == OpOpData.TRANSIENTREAD
				&& sources != null && !sources.isEmpty())
				dependencies.put(hop, sources);
			else if(hop instanceof ParameterizedBuiltinOp builtin
				&& builtin.getOp() == ParamBuiltinOp.RMEMPTY)
				// Native rmempty copies only its target FederationMap. A matrix-valued
				// select can be local, broadcast, or unrelated and is not residency proof.
				dependencies.put(hop, List.of(builtin.getTargetHop()));
			else if(preservesSingleEndpoint(hop)) {
				if(hop instanceof AggBinaryOp mm && mm.isMatrixMultiply()
					&& mm.checkTransposeSelf() == MMTSJType.NONE)
					conditionalFullResultTransfers.add(hop);
				else if(hop instanceof BinaryOp binary
					&& binary.getInput().size() == 2
					&& binary.getInput(0).getDataType().isMatrix()
					&& binary.getInput(1).getDataType().isMatrix()
					&& new Rulesets.BinaryElemwiseRule().opcodes().contains(binary.getOp().toString()))
					conditionalFullResultTransfers.add(hop);
				List<Hop> inputs = hop.getInput().stream().filter(input -> input.getDataType().isMatrix()).toList();
				if(inputs.isEmpty())
					facts.put(hop, UNKNOWN);
				else
					dependencies.put(hop, inputs);
			}
			else
				facts.put(hop, UNKNOWN);
		}
		for(var entry : dependencies.entrySet())
			if(entry.getValue().stream().anyMatch(source -> !owned.contains(source)))
				facts.put(entry.getKey(), UNKNOWN);
		close(facts, dependencies, conditionalFullResultTransfers);
		// An ungrounded dependency cycle cannot borrow another branch's source.
		for(Hop hop : hops)
			facts.putIfAbsent(hop, UNKNOWN);
		close(facts, dependencies, conditionalFullResultTransfers);
		endpoints = Collections.unmodifiableMap(facts);
		occurrenceEndpoints = Map.of();
	}

	private SinglePartitionFacts(Map<Hop,String> endpoints,
		Map<CompiledHopKey,String> occurrenceEndpoints) {
		this.endpoints = endpoints;
		this.occurrenceEndpoints = Collections.unmodifiableMap(occurrenceEndpoints);
	}

	/**
	 * Rebuilds cardinality on exact physical occurrence/value edges created after
	 * function expansion. This closure consumes only program structure: it never
	 * reads candidate domains, selected placements, privacy, or worker-pool anchors.
	 */
	SinglePartitionFacts closeOccurrences(List<Node> nodes, Map<CompiledHopKey,Hop> origins,
		List<CompiledInputEdgeFact> compiledInputs, Collection<Constraint> constraints) {
		Map<CompiledHopKey,String> facts = new java.util.LinkedHashMap<>();
		Map<CompiledHopKey,List<CompiledHopKey>> dependencies = new java.util.LinkedHashMap<>();
		Set<CompiledHopKey> conditional = new java.util.LinkedHashSet<>();
		Set<CompiledHopKey> leftIndexes = new java.util.LinkedHashSet<>();
		Map<CompiledHopKey,Integer> rmemptyTargets = new java.util.LinkedHashMap<>();
		Set<CompiledHopKey> owned = new java.util.LinkedHashSet<>();
		for(Node node : nodes) {
			owned.add(node.key());
			Hop hop = origins.get(node.key());
			if(hop == null || !hop.getDataType().isMatrix()) {
				facts.put(node.key(), UNKNOWN);
				continue;
			}
			if(hop instanceof DataOp data && data.getOp() == OpOpData.FEDERATED) {
				var partitions = NeutralPlacementGraphBuilder.fedInitLiteralPartitions(data);
				String endpoint = UNKNOWN;
				if(partitions.size() == 1) {
					var part = partitions.get(0);
					if(part.begin().equals(List.of(0L, 0L))
						&& part.end().get(0) > 0 && part.end().get(1) > 0)
						endpoint = FederationUtils.canonicalFederatedWorkerAddress(part.workerId());
				}
				facts.put(node.key(), endpoint == null ? UNKNOWN : endpoint);
			}
			else if(hop instanceof ParameterizedBuiltinOp builtin
				&& builtin.getOp() == ParamBuiltinOp.RMEMPTY)
				rmemptyTargets.put(node.key(), builtin.getParamIndexMap().getOrDefault("target", -1));
			else if(!preservesSingleEndpoint(hop))
				facts.put(node.key(), UNKNOWN);
			else if(hop instanceof LeftIndexingOp update
				&& update.getInput(1).getDataType().isMatrix())
				leftIndexes.add(node.key());
			else if((hop instanceof AggBinaryOp mm && mm.isMatrixMultiply()
				&& mm.checkTransposeSelf() == MMTSJType.NONE)
				|| (hop instanceof BinaryOp binary
					&& binary.getInput().size() == 2
					&& binary.getInput(0).getDataType().isMatrix()
					&& binary.getInput(1).getDataType().isMatrix()
					&& new Rulesets.BinaryElemwiseRule().opcodes().contains(binary.getOp().toString())))
				conditional.add(node.key());
		}

		Map<CompiledHopKey,List<CompiledInputEdgeFact>> physicalByConsumer = new java.util.LinkedHashMap<>();
		for(CompiledInputEdgeFact edge : compiledInputs)
			physicalByConsumer.computeIfAbsent(edge.consumer(), ignored -> new ArrayList<>()).add(edge);
		for(var entry : physicalByConsumer.entrySet()) {
			Hop consumer = origins.get(entry.getKey());
			Integer rmemptyTarget = rmemptyTargets.get(entry.getKey());
			if(consumer == null || rmemptyTarget == null && !preservesSingleEndpoint(consumer))
				continue;
			List<CompiledInputEdgeFact> ordered = new ArrayList<>(entry.getValue());
			ordered.sort(java.util.Comparator.comparingInt(CompiledInputEdgeFact::inputPosition));
			if(rmemptyTarget != null)
				ordered.removeIf(edge -> edge.inputPosition() != rmemptyTarget);
			if(ordered.isEmpty())
				facts.put(entry.getKey(), UNKNOWN);
			else
				dependencies.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
					.addAll(ordered.stream().map(CompiledInputEdgeFact::producer).toList());
		}

		for(Constraint constraint : constraints) {
			if(!carriesCardinalityValue(constraint))
				continue;
			// Alias targets are equations, not intrinsically UNKNOWN operations. Drop
			// the provisional unsupported-origin fact so exact structural sources can
			// ground synthetic boundaries and TReads. Missing/conflicting sources still
			// close to UNKNOWN below.
			facts.remove(constraint.right());
			dependencies.computeIfAbsent(constraint.right(), ignored -> new ArrayList<>())
				.add(constraint.left());
			if(constraint.kind() == ConstraintKind.SAME_ORIGIN) {
				dependencies.computeIfAbsent(constraint.left(), ignored -> new ArrayList<>())
					.add(constraint.right());
			}
		}
		for(var entry : dependencies.entrySet())
			if(!owned.contains(entry.getKey()) || entry.getValue().isEmpty()
				|| entry.getValue().stream().anyMatch(source -> !owned.contains(source)))
				facts.put(entry.getKey(), UNKNOWN);
		closeOccurrences(facts, dependencies, conditional, leftIndexes);
		for(CompiledHopKey key : owned)
			facts.putIfAbsent(key, UNKNOWN);
		closeOccurrences(facts, dependencies, conditional, leftIndexes);
		return new SinglePartitionFacts(endpoints, facts);
	}

	private static boolean carriesCardinalityValue(Constraint constraint) {
		String evidence = constraint.evidence();
		return "function-input-binding".equals(evidence)
			|| "multi-return-output-value".equals(evidence)
			|| "logical-transient-input".equals(evidence)
			|| "function-formal-input".equals(evidence)
			|| "stable-origin".equals(evidence)
			|| evidence.startsWith("cfg-transient-value:")
			|| evidence.startsWith("cfg-function-output-value:")
			|| evidence.startsWith("function-argument:")
			|| evidence.startsWith("inlined-function-argument:")
			|| evidence.startsWith("function-result:")
			|| evidence.startsWith("inlined-function-result:");
	}

	Optional<Boolean> fullInputHint(Hop hop, List<FType> inputs) {
		boolean sawFull = false;
		for(int position = 0; position < inputs.size(); position++) {
			if(inputs.get(position) != FType.FULL)
				continue;
			sawFull = true;
			if(position >= hop.getInput().size() || !isSinglePartition(hop.getInput(position)))
				return Optional.empty();
		}
		return sawFull ? Optional.of(true) : Optional.empty();
	}

	Optional<Boolean> fullInputHint(Hop hop, List<CompiledHopKey> inputOccurrences,
		List<FType> inputs) {
		if(occurrenceEndpoints.isEmpty())
			return fullInputHint(hop, inputs);
		boolean sawFull = false;
		for(int position = 0; position < inputs.size(); position++) {
			if(inputs.get(position) != FType.FULL)
				continue;
			sawFull = true;
			if(position >= inputOccurrences.size())
				return Optional.empty();
			CompiledHopKey source = inputOccurrences.get(position);
			if(source == null || occurrenceEndpoints.getOrDefault(source, UNKNOWN).isEmpty())
				return Optional.empty();
		}
		return sawFull ? Optional.of(true) : Optional.empty();
	}

	/**
	 * Returns only physical occurrences whose candidate-facing cardinality knownness differs
	 * from the pre-expansion Hop fact. Candidate replay consumes these ordinals as
	 * changed producers; it must not rebuild unrelated rows merely because the
	 * occurrence closure was computed.
	 */
	List<Integer> changedOccurrenceOrdinals(List<Node> nodes, int originalOccurrenceCount,
		Map<CompiledHopKey,Hop> origins) {
		if(originalOccurrenceCount < 0 || originalOccurrenceCount > nodes.size())
			throw new IllegalArgumentException("Invalid original occurrence count");
		List<String> before = new ArrayList<>(originalOccurrenceCount);
		List<String> after = new ArrayList<>(originalOccurrenceCount);
		for(int ordinal = 0; ordinal < originalOccurrenceCount; ordinal++) {
			Node node = nodes.get(ordinal);
			Hop origin = origins.get(node.key());
			before.add(origin == null ? UNKNOWN : endpoints.getOrDefault(origin, UNKNOWN));
			after.add(occurrenceEndpoints.getOrDefault(node.key(), UNKNOWN));
		}
		return changedCardinalityEvidenceOrdinals(before, after, originalOccurrenceCount);
	}

	static List<Integer> changedCardinalityEvidenceOrdinals(List<String> before, List<String> after,
		int originalOccurrenceCount) {
		if(originalOccurrenceCount < 0 || originalOccurrenceCount > before.size()
			|| originalOccurrenceCount > after.size())
			throw new IllegalArgumentException("Invalid original occurrence count");
		List<Integer> changed = new ArrayList<>();
		for(int ordinal = 0; ordinal < originalOccurrenceCount; ordinal++)
			if(before.get(ordinal).isEmpty() != after.get(ordinal).isEmpty())
				changed.add(ordinal);
		return List.copyOf(changed);
	}

	boolean isSinglePartition(Hop hop) {
		return !endpoints.getOrDefault(hop, UNKNOWN).isEmpty();
	}

	private static boolean preservesSingleEndpoint(Hop hop) {
		if(hop instanceof DataOp data)
			return data.getOp() == OpOpData.TRANSIENTWRITE;
		if(hop instanceof TernaryOp ternary)
			// TernaryFEDInstruction copies its input map for these elementwise
			// kernels, including fused +*/-* produced by HOP rewrites. Other
			// ternaries (e.g. CTABLE) may change topology and need separate proof.
			return ternary.getOp() == OpOp3.PLUS_MULT || ternary.getOp() == OpOp3.MINUS_MULT
				|| ternary.getOp() == OpOp3.IFELSE;
		if(hop instanceof NaryOp nary)
			// BuiltinNaryFEDInstruction copies its selected base map; nary append
			// does not share this elementwise topology contract.
			return nary.getOp() == OpOpN.PLUS || nary.getOp() == OpOpN.MULT
				|| nary.getOp() == OpOpN.MIN || nary.getOp() == OpOpN.MAX;
		if(hop instanceof ParameterizedBuiltinOp builtin)
			// REPLACE copies the map. Native REXPAND also retains one output entry
			// per target entry: copyWithNewID/transpose changes ranges, not cardinality.
			// This conditional FULL fact proves neither output dimensions nor legality.
			return builtin.getOp() == ParamBuiltinOp.REPLACE || builtin.getOp() == ParamBuiltinOp.REXPAND;
		// These native kernels copy/filter the input map; aligned append modifies
		// its ranges in place. All matrix inputs must ultimately name the SAME
		// endpoint, so map-binding across different workers is not certified here.
		return hop instanceof BinaryOp || hop instanceof UnaryOp || hop instanceof AggUnaryOp || hop instanceof AggBinaryOp
			|| hop instanceof IndexingOp || hop instanceof LeftIndexingOp
			|| hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS;
	}

	private static void close(Map<Hop,String> facts, Map<Hop,List<Hop>> dependencies,
		Set<Hop> conditionalFullResultTransfers) {
		boolean changed;
		do {
			changed = false;
			// Read one complete round: recursive MM proofs must not depend on
			// IdentityHashMap traversal order. Facts only widen in the finite lattice.
			Map<Hop,String> previous = new IdentityHashMap<>(facts);
			for(var entry : dependencies.entrySet()) {
				String next = previous.get(entry.getKey());
				if(entry.getKey() instanceof LeftIndexingOp update && update.getInput(1).getDataType().isMatrix())
					next = join(next, fullLeftIndexTransfer(update, previous));
				else if(conditionalFullResultTransfers.contains(entry.getKey()))
					next = join(next, fullResultTransfer(entry.getValue(), previous));
				else
					for(Hop source : entry.getValue())
						next = join(next, previous.get(source));
				if(next != null && !next.equals(previous.get(entry.getKey()))) {
					facts.put(entry.getKey(), next);
					changed = true;
				}
			}
		} while(changed);
	}

	private static String fullLeftIndexTransfer(LeftIndexingOp update, Map<Hop,String> facts) {
		// Native matrix-RHS FULL left indexing executes on its single RHS worker.
		// Unlike MM, a known LHS alone cannot supply this conditional certificate.
		// Local LHS contributes no endpoint; a known remote LHS must agree. This
		// grants neither a candidate nor geometry, and fullInputHint still checks
		// every operand actually selected as FULL (including the LHS).
		String rhs = facts.get(update.getInput(1));
		if(rhs == null || rhs.isEmpty())
			return rhs;
		String lhs = facts.get(update.getInput(0));
		return lhs == null || lhs.isEmpty() ? rhs : join(lhs, rhs);
	}

	private static String fullResultTransfer(List<Hop> sources, Map<Hop,String> facts) {
		// Ordinary MM and native matrix-matrix elementwise kernels can broadcast a
		// local matrix to a single FULL provider. Their legal FULL/FOUT result copies
		// that provider's one range. BinaryElemwiseRule independently requires this
		// same fullSinglePartition proof before admitting FULL, so an UNKNOWN matrix
		// can never be silently treated as a second selected FULL input. This grants
		// no availability/placement authority: fullInputHint still checks EVERY
		// selected FULL operand, and the other candidate guards still apply.
		String result = null;
		boolean waiting = false;
		for(Hop source : sources) {
			String provider = facts.get(source);
			if(provider == null)
				waiting = true;
			else if(!provider.isEmpty())
				result = join(result, provider);
		}
		// Recompute before widening. Retaining the old fact while skipping all
		// UNKNOWN inputs would keep a stale certificate after its provider widened.
		return result != null ? result : waiting ? null : UNKNOWN;
	}

	private static void closeOccurrences(Map<CompiledHopKey,String> facts,
		Map<CompiledHopKey,List<CompiledHopKey>> dependencies, Set<CompiledHopKey> conditional,
		Set<CompiledHopKey> leftIndexes) {
		boolean changed;
		do {
			changed = false;
			Map<CompiledHopKey,String> previous = new java.util.LinkedHashMap<>(facts);
			for(var entry : dependencies.entrySet()) {
				String next = previous.get(entry.getKey());
				if(leftIndexes.contains(entry.getKey()))
					next = join(next, fullLeftIndexTransferKeys(entry.getValue(), previous));
				else if(conditional.contains(entry.getKey()))
					next = join(next, fullResultTransferKeys(entry.getValue(), previous));
				else
					for(CompiledHopKey source : entry.getValue())
						next = join(next, previous.get(source));
				if(next != null && !next.equals(previous.get(entry.getKey()))) {
					facts.put(entry.getKey(), next);
					changed = true;
				}
			}
		} while(changed);
	}

	private static String fullLeftIndexTransferKeys(List<CompiledHopKey> sources,
		Map<CompiledHopKey,String> facts) {
		if(sources.size() < 2)
			return UNKNOWN;
		String rhs = facts.get(sources.get(1));
		if(rhs == null || rhs.isEmpty())
			return rhs;
		String lhs = facts.get(sources.get(0));
		return lhs == null || lhs.isEmpty() ? rhs : join(lhs, rhs);
	}

	private static String fullResultTransferKeys(List<CompiledHopKey> sources,
		Map<CompiledHopKey,String> facts) {
		String result = null;
		boolean waiting = false;
		for(CompiledHopKey source : sources) {
			String provider = facts.get(source);
			if(provider == null)
				waiting = true;
			else if(!provider.isEmpty())
				result = join(result, provider);
		}
		return result != null ? result : waiting ? null : UNKNOWN;
	}

	private static String join(String left, String right) {
		if(left == null) return right;
		if(right == null) return left;
		return left.equals(right) ? left : UNKNOWN;
	}
}
