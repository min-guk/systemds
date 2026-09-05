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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.AggUnaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
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

	SinglePartitionFacts(List<Hop> hops, Map<Hop,List<Hop>> valueSources, Set<Hop> incompleteSources) {
		Map<Hop,String> facts = new IdentityHashMap<>();
		Map<Hop,List<Hop>> dependencies = new IdentityHashMap<>();
		Set<Hop> fullResultMatmuls = Collections.newSetFromMap(new IdentityHashMap<>());
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
			else if(preservesSingleEndpoint(hop)) {
				if(hop instanceof AggBinaryOp mm && mm.isMatrixMultiply()
					&& mm.checkTransposeSelf() == MMTSJType.NONE)
					fullResultMatmuls.add(hop);
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
		close(facts, dependencies, fullResultMatmuls);
		// An ungrounded dependency cycle cannot borrow another branch's source.
		for(Hop hop : hops)
			facts.putIfAbsent(hop, UNKNOWN);
		close(facts, dependencies, fullResultMatmuls);
		endpoints = Collections.unmodifiableMap(facts);
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

	boolean isSinglePartition(Hop hop) {
		return !endpoints.getOrDefault(hop, UNKNOWN).isEmpty();
	}

	private static boolean preservesSingleEndpoint(Hop hop) {
		if(hop instanceof DataOp data)
			return data.getOp() == OpOpData.TRANSIENTWRITE;
		// These native kernels copy/filter the input map; aligned append modifies
		// its ranges in place. All matrix inputs must ultimately name the SAME
		// endpoint, so map-binding across different workers is not certified here.
		return hop instanceof BinaryOp || hop instanceof UnaryOp || hop instanceof AggUnaryOp || hop instanceof AggBinaryOp
			|| hop instanceof IndexingOp
			|| hop instanceof ReorgOp reorg && reorg.getOp() == ReOrgOp.TRANS;
	}

	private static void close(Map<Hop,String> facts, Map<Hop,List<Hop>> dependencies,
		Set<Hop> fullResultMatmuls) {
		boolean changed;
		do {
			changed = false;
			// Read one complete round: recursive MM proofs must not depend on
			// IdentityHashMap traversal order. Facts only widen in the finite lattice.
			Map<Hop,String> previous = new IdentityHashMap<>(facts);
			for(var entry : dependencies.entrySet()) {
				String next = previous.get(entry.getKey());
				if(fullResultMatmuls.contains(entry.getKey()))
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

	private static String fullResultTransfer(List<Hop> sources, Map<Hop,String> facts) {
		// Ordinary MM can broadcast a local matrix to a single FULL provider.
		// Its legal FULL/FOUT result copies that provider's one range; derived
		// FULL likewise requires an exact one-range target. This implication
		// grants no availability/placement authority: fullInputHint still checks
		// EVERY selected FULL operand, and the other candidate guards still apply.
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

	private static String join(String left, String right) {
		if(left == null) return right;
		if(right == null) return left;
		return left.equals(right) ? left : UNKNOWN;
	}
}
