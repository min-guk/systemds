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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;

public final class OracleUtils {
	private static final List<FType> MATRIX_FTYPE_CANDIDATES = List.of(
			FType.ROW, FType.COL, FType.FULL, FType.PART, FType.BROADCAST);

	public static final class OracleDecision {
		private final List<FType> alignedInputFTypes;
		private final OpCaps caps;
		private final FType logicalFType;

		private OracleDecision(List<FType> alignedInputFTypes, OpCaps caps, FType logicalFType) {
			this.alignedInputFTypes = alignedInputFTypes;
			this.caps = caps;
			this.logicalFType = logicalFType;
		}

		public List<FType> alignedInputFTypes() {
			return alignedInputFTypes;
		}

		public OpCaps caps() {
			return caps;
		}

		public FType logicalFType() {
			return logicalFType;
		}
	}

	private OracleUtils() {
		// utility class
	}

	public static OracleDecision decideWithOracle(Hop hop, Privacy privacy, List<Hop> collectedHops,
			List<FType> collectedFTypes, OracleFacade oracleFacade, Map<List<FType>, OpCaps> oracleCache,
			Map<Long, List<Hop>> rewireTable) {
		List<FType> alignedInputFTypes = alignInputFTypes(hop, collectedHops, collectedFTypes);
		OpCaps caps = null;

		if (oracleFacade != null) {
			if (oracleCache != null) {
				caps = oracleCache.computeIfAbsent(alignedInputFTypes, k -> {
					OpCaps decision = oracleFacade.decide(hop, k);
					FederatedPlannerLogger.logOracleDecision(hop, privacy, k, decision, rewireTable);
					return decision;
				});
			} else {
				caps = oracleFacade.decide(hop, alignedInputFTypes);
				if (caps != null) {
					FederatedPlannerLogger.logOracleDecision(hop, privacy, alignedInputFTypes, caps, rewireTable);
				}
			}
		}

		FType logicalFType = null;
		if (caps != null && caps.foutFType().isPresent()) {
			logicalFType = caps.foutFType().get();
		}
		if (logicalFType == null) {
			logicalFType = inferFallbackFType(hop, alignedInputFTypes, oracleFacade, rewireTable);
		}
		if (FederatedPlannerUtils.isScalarLikeMatrix(hop)) {
			logicalFType = FType.BROADCAST;
		}
		if (FederatedPlannerUtils.isVectorShape(hop)
				&& !hasFederatedInput(alignedInputFTypes)) {
			// Local vectors uploaded for FED consumption should default to BROADCAST to avoid
			// invalid ROW/COL slicing against unrelated anchors.
			logicalFType = FType.BROADCAST;
		}
		logicalFType = preferConcreteTransientReadSourceLayout(hop, alignedInputFTypes, logicalFType);
		if ((logicalFType == FType.ROW || logicalFType == FType.COL)
				&& hasConsumerAxisLengthMismatch(hop, logicalFType, rewireTable)) {
			logicalFType = FType.BROADCAST;
		}

		return new OracleDecision(alignedInputFTypes, caps, logicalFType);
	}

	private static FType preferConcreteTransientReadSourceLayout(Hop hop, List<FType> alignedInputFTypes,
			FType logicalFType) {
		if (logicalFType != FType.BROADCAST || !(hop instanceof DataOp)
				|| ((DataOp) hop).getOp() != Types.OpOpData.TRANSIENTREAD
				|| alignedInputFTypes == null || alignedInputFTypes.isEmpty()) {
			return logicalFType;
		}

		FType concreteSourceType = null;
		for (FType inputType : alignedInputFTypes) {
			if (inputType == null)
				continue;
			if (inputType == FType.BROADCAST)
				return logicalFType;
			if (concreteSourceType == null)
				concreteSourceType = inputType;
			else if (concreteSourceType != inputType)
				return logicalFType;
		}

		// Transient reads backed by a concrete mapped federated source should preserve that source
		// layout instead of degrading to BROADCAST. Runtime refed reuses the source worker mapping,
		// and forcing BROADCAST here suppresses valid FED/FOUT variants for downstream consumers.
		return concreteSourceType != null ? concreteSourceType : logicalFType;
	}

	public static FType adjustCpFoutFTypeForConsumerAxisMismatch(Hop hop, FType logicalFType,
			Map<Long, List<Hop>> rewireTable) {
		return adjustCpFoutFTypeForConsumerAxisMismatch(hop, logicalFType, rewireTable, 0);
	}

	/**
	 * Compute a CP-&gt;FOUT (or local-&gt;FED forwarding) FType that is safe w.r.t. runtime behavior.
	 *
	 * <p>This method intentionally differs from {@code logicalFType} in cases where the runtime would
	 * inevitably fall back to a full broadcast (e.g., ROW/COL slicing when the sliced dimension is
	 * smaller than the worker count, or vector axis mismatch). By selecting {@code BROADCAST} in the
	 * planner already, we avoid runtime map-type flips that would invalidate downstream assumptions
	 * and also allow the cost model to correctly account for replicated uploads.</p>
	 */
	public static FType adjustCpFoutFTypeForConsumerAxisMismatch(Hop hop, FType logicalFType,
			Map<Long, List<Hop>> rewireTable, int numWorkers) {
		if (logicalFType == null)
			return null;
		FType aggBinarySharedAxis = preferAggBinarySharedDimensionFType(hop, rewireTable, numWorkers);
		if (aggBinarySharedAxis != null)
			return aggBinarySharedAxis;
		if (FederatedPlannerUtils.isScalarLikeMatrix(hop))
			return FType.BROADCAST;
		if (logicalFType == FType.BROADCAST)
			return logicalFType;
		FType vectorAxis = FederatedPlannerUtils.getVectorAxis(hop);
		if (vectorAxis != null && hasConsumerAxisMismatch(hop, vectorAxis, rewireTable))
			return FType.BROADCAST;
		if ((logicalFType == FType.ROW || logicalFType == FType.COL)
				&& hasConsumerAxisLengthMismatch(hop, logicalFType, rewireTable)) {
			return FType.BROADCAST;
		}

		if (numWorkers > 1 && hop != null && hop.dimsKnown()) {
			long rows = hop.getDim1();
			long cols = hop.getDim2();
			if (logicalFType == FType.ROW && rows > 0 && rows < numWorkers)
				return FType.BROADCAST;
			if (logicalFType == FType.COL && cols > 0 && cols < numWorkers)
				return FType.BROADCAST;
		}
		return logicalFType;
	}

	private static FType preferAggBinarySharedDimensionFType(Hop hop,
			Map<Long, List<Hop>> rewireTable, int numWorkers) {
		if (hop == null || hop.getDataType() == null || !hop.getDataType().isMatrix())
			return null;
		List<ConsumerRef> consumerRefs = resolveConsumerRefs(hop, rewireTable);
		if (consumerRefs == null || consumerRefs.isEmpty())
			return null;
		long targetId = hop.getHopID();
		for (ConsumerRef ref : consumerRefs) {
			if (ref == null || !(ref.consumer instanceof AggBinaryOp))
				continue;
			AggBinaryOp agg = (AggBinaryOp) ref.consumer;
			if (!agg.isMatrixMultiply())
				continue;
			List<Hop> inputs = agg.getInput();
			if (inputs == null || inputs.size() < 2)
				continue;
			long proxyId = ref.inputHop != null ? ref.inputHop.getHopID() : -1;
			int targetIndex = findConsumerInputIndex(inputs, targetId, proxyId);
			if (targetIndex < 0 || targetIndex > 1)
				continue;
			Hop other = inputs.get(targetIndex == 0 ? 1 : 0);
			FType otherType = inferKnownFederatedAxis(other);
			FType preferred = null;
			long preferredAxisLen = -1;
			if (targetIndex == 0 && otherType == FType.ROW
					&& dimensionsMatch(hop.getDim2(), other != null ? other.getDim1() : -1)) {
				preferred = FType.COL;
				preferredAxisLen = hop.getDim2();
			}
			else if (targetIndex == 1 && otherType == FType.COL
					&& dimensionsMatch(hop.getDim1(), other != null ? other.getDim2() : -1)) {
				preferred = FType.ROW;
				preferredAxisLen = hop.getDim1();
			}
			if (preferred != null) {
				if (numWorkers > 1 && preferredAxisLen > 0 && preferredAxisLen < numWorkers)
					return FType.BROADCAST;
				return preferred;
			}
		}
		return null;
	}

	private static int findConsumerInputIndex(List<Hop> inputs, long targetId, long proxyId) {
		for (int i = 0; i < inputs.size(); i++) {
			Hop input = inputs.get(i);
			if (input == null)
				continue;
			long inputId = input.getHopID();
			if (inputId == targetId || (proxyId >= 0 && inputId == proxyId))
				return i;
		}
		return -1;
	}

	private static FType inferKnownFederatedAxis(Hop hop) {
		FType axis = inferFedInitType(hop);
		if (axis == null && hop instanceof DataOp
				&& ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
			axis = FederatedPlannerUtils.deriveFedInitFType((DataOp) hop);
		}
		return axis;
	}

	private static boolean dimensionsMatch(long left, long right) {
		return left > 0 && right > 0 && left == right;
	}

	public static FType inferFallbackFType(Hop hop, List<FType> alignedInputFTypes,
			OracleFacade oracleFacade, Map<Long, List<Hop>> rewireTable) {
		if (hop instanceof FunctionOp) {
			FunctionOp.FunctionType type = ((FunctionOp) hop).getFunctionType();
			if (type == FunctionOp.FunctionType.MULTIRETURN_BUILTIN) {
				return null;
			}
		}
		if (!isMatrixHop(hop)) {
			return null;
		}

		boolean preferBroadcast = FederatedPlannerUtils.isVectorShape(hop)
				&& hasFederatedInput(alignedInputFTypes)
				&& inferFedInitType(hop) == null;

		List<ConsumerRef> consumerRefs = resolveConsumerRefs(hop, rewireTable);
		Set<FType> producerCandidates = inferFromProducer(hop, alignedInputFTypes, oracleFacade);
		ConsumerConstraints consumerConstraints = inferFromConsumers(consumerRefs, oracleFacade);
		Set<FType> consumerCandidates = consumerConstraints.candidates;
		Set<FType> merged = mergeCandidates(producerCandidates, consumerCandidates,
				consumerConstraints.constrained);

		if (!merged.isEmpty()) {
			if (preferBroadcast && merged.contains(FType.BROADCAST))
				return FType.BROADCAST;
			return pickPreferredAxis(merged, hop);
		}

		FederatedPlannerLogger.logWarnMessage(
				"[FTypeFallback] No rule candidates for hop " + hop.getHopID()
						+ " (" + hop.getOpString() + ") inputs=" + alignedInputFTypes
						+ " producer=" + producerCandidates + " consumer=" + consumerCandidates
						+ " consumerConstrained=" + consumerConstraints.constrained
						+ " consumers=" + describeConsumerRefs(consumerRefs)
						+ "; fallback to fed-init/input FTypes.");

		FType fedInitType = inferFedInitType(hop);
		if (fedInitType != null) {
			return fedInitType;
		}

		Set<FType> inputCandidates = collectInputCandidates(alignedInputFTypes);
		if (preferBroadcast && inputCandidates.contains(FType.BROADCAST))
			return FType.BROADCAST;
		return pickPreferredAxis(inputCandidates, hop);
	}

	private static Set<FType> inferFromProducer(Hop hop, List<FType> alignedInputFTypes,
			OracleFacade oracleFacade) {
		if (oracleFacade == null || hop == null) {
			return Collections.emptySet();
		}
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty()) {
			return Collections.emptySet();
		}
		List<List<FType>> inCandidates = new ArrayList<>(inputs.size());
		for (int i = 0; i < inputs.size(); i++) {
			Hop inputHop = inputs.get(i);
			FType known = (alignedInputFTypes != null && i < alignedInputFTypes.size())
					? alignedInputFTypes.get(i)
					: null;
			inCandidates.add(buildInputCandidates(inputHop, known));
		}
		FTypeProfile profile = oracleFacade.inferProfile(hop, inCandidates, null);
		if (profile == null || profile.outputs() == null || profile.outputs().isEmpty()) {
			return Collections.emptySet();
		}
		return new LinkedHashSet<>(profile.outputs());
	}

	private static ConsumerConstraints inferFromConsumers(List<ConsumerRef> consumerRefs,
			OracleFacade oracleFacade) {
		if (oracleFacade == null || consumerRefs == null || consumerRefs.isEmpty()) {
			return ConsumerConstraints.unconstrained();
		}
		Set<FType> candidates = new LinkedHashSet<>(MATRIX_FTYPE_CANDIDATES);
		boolean constrained = false;
		for (ConsumerRef ref : consumerRefs) {
			if (ref == null || ref.consumer == null) {
				continue;
			}
			Set<FType> allowed = new LinkedHashSet<>();
			for (FType candidate : MATRIX_FTYPE_CANDIDATES) {
				FTypeProfile profile = oracleFacade.inferProfile(
						ref.consumer, buildConsumerCandidates(ref.consumer, ref.inputHop, candidate), null);
				if (profile != null && profile.outputs() != null && !profile.outputs().isEmpty()) {
					allowed.add(candidate);
				}
			}
			if (!allowed.isEmpty()) {
				constrained = true;
				candidates.retainAll(allowed);
			}
		}
		if (!constrained) {
			return ConsumerConstraints.unconstrained();
		}
		return new ConsumerConstraints(candidates, true);
	}

	private static List<List<FType>> buildConsumerCandidates(Hop consumer, Hop target, FType targetCandidate) {
		List<Hop> inputs = consumer.getInput();
		if (inputs == null || inputs.isEmpty()) {
			return Collections.emptyList();
		}
		List<List<FType>> inCandidates = new ArrayList<>(inputs.size());
		for (Hop inputHop : inputs) {
			FType known = (inputHop != null && target != null && inputHop.getHopID() == target.getHopID())
					? targetCandidate
					: null;
			inCandidates.add(buildInputCandidates(inputHop, known));
		}
		return inCandidates;
	}

	private static List<FType> buildInputCandidates(Hop inputHop, FType known) {
		if (known != null) {
			return List.of(known);
		}
		if (inputHop != null && inputHop.getDataType() != null && inputHop.getDataType().isMatrix()) {
			return MATRIX_FTYPE_CANDIDATES;
		}
		List<FType> scalar = new ArrayList<>(1);
		scalar.add(null);
		return scalar;
	}

	private static Set<FType> mergeCandidates(Set<FType> producer, Set<FType> consumer,
			boolean consumerConstrained) {
		if (consumerConstrained) {
			if (consumer == null || consumer.isEmpty()) {
				return Collections.emptySet();
			}
			if (producer == null || producer.isEmpty()) {
				return new LinkedHashSet<>(consumer);
			}
			Set<FType> merged = new LinkedHashSet<>(producer);
			merged.retainAll(consumer);
			return merged;
		}
		if (producer == null || producer.isEmpty()) {
			return (consumer == null) ? Collections.emptySet() : new LinkedHashSet<>(consumer);
		}
		return new LinkedHashSet<>(producer);
	}

	private static boolean hasConsumerAxisMismatch(Hop hop, FType vectorAxis, Map<Long, List<Hop>> rewireTable) {
		if (hop == null || vectorAxis == null)
			return false;
		List<ConsumerRef> consumerRefs = resolveConsumerRefs(hop, rewireTable);
		if (consumerRefs == null || consumerRefs.isEmpty())
			return false;
		long targetId = hop.getHopID();
		for (ConsumerRef ref : consumerRefs) {
			if (ref == null || ref.consumer == null)
				continue;
			List<Hop> inputs = ref.consumer.getInput();
			if (inputs == null || inputs.isEmpty())
				continue;
			long proxyId = ref.inputHop != null ? ref.inputHop.getHopID() : -1;
			for (Hop input : inputs) {
				if (input == null)
					continue;
				long inputId = input.getHopID();
				if (inputId == targetId || (proxyId >= 0 && inputId == proxyId))
					continue;
				FType axis = inferFedInitType(input);
				if (axis == null && input instanceof DataOp
						&& ((DataOp) input).getOp() == Types.OpOpData.FEDERATED) {
					axis = FederatedPlannerUtils.deriveFedInitFType((DataOp) input);
				}
				if (axis == FType.ROW || axis == FType.COL) {
					if (axis != vectorAxis)
						return true;
				}
			}
		}
		return false;
	}

	private static boolean hasConsumerAxisLengthMismatch(Hop hop, FType axisType,
			Map<Long, List<Hop>> rewireTable) {
		if (hop == null || axisType == null || rewireTable == null)
			return false;
		if (!(axisType == FType.ROW || axisType == FType.COL))
			return false;
		long hopAxisLen = (axisType == FType.ROW) ? hop.getDim1() : hop.getDim2();
		if (hopAxisLen <= 0)
			return false;

		List<ConsumerRef> consumerRefs = resolveConsumerRefs(hop, rewireTable);
		if (consumerRefs == null || consumerRefs.isEmpty())
			return false;

		long targetId = hop.getHopID();
		for (ConsumerRef ref : consumerRefs) {
			if (ref == null || ref.consumer == null)
				continue;
			List<Hop> inputs = ref.consumer.getInput();
			if (inputs == null || inputs.isEmpty())
				continue;
			long proxyId = ref.inputHop != null ? ref.inputHop.getHopID() : -1;
			for (Hop input : inputs) {
				if (input == null)
					continue;
				long inputId = input.getHopID();
				if (inputId == targetId || (proxyId >= 0 && inputId == proxyId))
					continue;

				FType axis = inferFedInitType(input);
				if (axis == null && input instanceof DataOp
						&& ((DataOp) input).getOp() == Types.OpOpData.FEDERATED) {
					axis = FederatedPlannerUtils.deriveFedInitFType((DataOp) input);
				}
				if (axis != FType.ROW && axis != FType.COL)
					continue;
				long anchorLen = (axis == FType.ROW) ? input.getDim1() : input.getDim2();
				if (anchorLen <= 0)
					continue;
				if (anchorLen != hopAxisLen)
					return true;
			}
		}
		return false;
	}

	private static boolean hasFederatedInput(List<FType> alignedInputFTypes) {
		if (alignedInputFTypes == null || alignedInputFTypes.isEmpty())
			return false;
		for (FType fType : alignedInputFTypes) {
			if (fType == null)
				continue;
			if (fType == FType.ROW || fType == FType.COL || fType == FType.PART
					|| fType == FType.FULL || fType == FType.BROADCAST)
				return true;
		}
		return false;
	}

	private static Set<FType> collectInputCandidates(List<FType> alignedInputFTypes) {
		if (alignedInputFTypes == null || alignedInputFTypes.isEmpty()) {
			return Collections.emptySet();
		}
		Set<FType> candidates = new LinkedHashSet<>();
		for (FType fType : alignedInputFTypes) {
			if (fType != null) {
				candidates.add(fType);
			}
		}
		return candidates;
	}

	private static FType pickPreferredAxis(Set<FType> candidates, Hop hop) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		boolean hasRow = candidates.contains(FType.ROW);
		boolean hasCol = candidates.contains(FType.COL);
		if (hasRow || hasCol) {
			long rows = hop != null ? hop.getDim1() : -1;
			long cols = hop != null ? hop.getDim2() : -1;
			if (rows == 1 && hasCol) {
				return FType.COL;
			}
			if (cols == 1 && hasRow) {
				return FType.ROW;
			}
			return hasRow ? FType.ROW : FType.COL;
		}
		if (candidates.contains(FType.FULL)) {
			return FType.FULL;
		}
		if (candidates.contains(FType.PART)) {
			return FType.PART;
		}
		if (candidates.contains(FType.BROADCAST)) {
			return FType.BROADCAST;
		}
		return null;
	}

	private static FType inferFedInitType(Hop hop) {
		if (!(hop instanceof DataOp)) {
			return null;
		}
		DataOp dataOp = (DataOp) hop;
		String name = dataOp.getName();
		if (FederatedPlannerUtils.isFedInitVar(name)) {
			return FederatedPlannerUtils.getFedInitFType(name);
		}
		return null;
	}

	private static boolean isMatrixHop(Hop hop) {
		return hop != null && hop.getDataType() != null && hop.getDataType().isMatrix();
	}

	private static List<ConsumerRef> resolveConsumerRefs(Hop hop, Map<Long, List<Hop>> rewireTable) {
		if (hop == null) {
			return Collections.emptyList();
		}
		List<Hop> parents = hop.getParent();
		if (parents == null || parents.isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> visited = new HashSet<>();
		Deque<ConsumerEdge> queue = new ArrayDeque<>();
		for (Hop parent : parents) {
			queue.add(new ConsumerEdge(parent, hop));
		}
		List<ConsumerRef> consumers = new ArrayList<>();
		while (!queue.isEmpty()) {
			ConsumerEdge edge = queue.poll();
			Hop parent = edge.parent;
			Hop proxy = edge.proxy;
			if (parent == null) {
				continue;
			}
			String key = parent.getHopID() + ":" + (proxy != null ? proxy.getHopID() : -1);
			if (!visited.add(key)) {
				continue;
			}
			if (parent instanceof DataOp) {
				Types.OpOpData opType = ((DataOp) parent).getOp();
				if (opType == Types.OpOpData.TRANSIENTWRITE) {
					if (rewireTable != null) {
						List<Hop> transReads = rewireTable.get(parent.getHopID());
						if (transReads != null && !transReads.isEmpty()) {
							for (Hop tr : transReads) {
								queue.add(new ConsumerEdge(tr, tr));
							}
						}
					}
					continue;
				}
				if (opType == Types.OpOpData.TRANSIENTREAD) {
					List<Hop> grandParents = parent.getParent();
					if (grandParents != null && !grandParents.isEmpty()) {
						for (Hop gp : grandParents) {
							queue.add(new ConsumerEdge(gp, parent));
						}
					}
					continue;
				}
			}
			consumers.add(new ConsumerRef(parent, proxy));
		}
		return consumers;
	}

	private static List<String> describeConsumerRefs(List<ConsumerRef> refs) {
		if (refs == null || refs.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> desc = new ArrayList<>(refs.size());
		for (ConsumerRef ref : refs) {
			if (ref == null || ref.consumer == null) {
				continue;
			}
			String proxy = (ref.inputHop != null)
					? (ref.inputHop.getHopID() + ":" + ref.inputHop.getOpString())
					: "null";
			desc.add(ref.consumer.getHopID() + ":" + ref.consumer.getOpString() + "<-" + proxy);
		}
		return desc;
	}

	private static final class ConsumerRef {
		private final Hop consumer;
		private final Hop inputHop;

		private ConsumerRef(Hop consumer, Hop inputHop) {
			this.consumer = consumer;
			this.inputHop = inputHop;
		}
	}

	private static final class ConsumerEdge {
		private final Hop parent;
		private final Hop proxy;

		private ConsumerEdge(Hop parent, Hop proxy) {
			this.parent = parent;
			this.proxy = proxy;
		}
	}

	private static final class ConsumerConstraints {
		private final Set<FType> candidates;
		private final boolean constrained;

		private ConsumerConstraints(Set<FType> candidates, boolean constrained) {
			this.candidates = (candidates != null) ? candidates : Collections.emptySet();
			this.constrained = constrained;
		}

		private static ConsumerConstraints unconstrained() {
			return new ConsumerConstraints(Collections.emptySet(), false);
		}
	}

	public static List<FType> alignInputFTypes(Hop hop, List<Hop> collectedHops, List<FType> collectedFTypes) {
		if (hop == null) {
			return collectedFTypes;
		}
		List<Hop> parentInputs = hop.getInput();
		int numInputs = parentInputs == null ? 0 : parentInputs.size();
		List<FType> aligned = new ArrayList<>(Collections.nCopies(numInputs, null));
		if (numInputs == 0) {
			if (collectedFTypes == null) {
				return aligned;
			}
			return collectedFTypes.isEmpty() ? aligned : new ArrayList<>(collectedFTypes);
		}

		if (collectedHops == null || collectedFTypes == null) {
			return aligned;
		}

		Map<Long, Deque<Integer>> slotsByHopId = new HashMap<>();
		for (int j = 0; j < numInputs; j++) {
			Hop parent = parentInputs.get(j);
			if (parent == null) {
				continue;
			}
			slotsByHopId.computeIfAbsent(parent.getHopID(), k -> new ArrayDeque<>()).add(j);
		}

		int limit = Math.min(collectedHops.size(), collectedFTypes.size());
		Map<Long, FType> assignedByHopId = new HashMap<>();

		for (int i = 0; i < limit; i++) {
			Hop child = collectedHops.get(i);
			FType ftype = collectedFTypes.get(i);
			if (child == null) {
				FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping null child for hop "
						+ hop.getHopID());
				continue;
			}
			Deque<Integer> slots = slotsByHopId.get(child.getHopID());
			if (slots == null || slots.isEmpty()) {
				FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping unmatched child "
						+ child.getHopID() + " for hop " + hop.getHopID());
				continue;
			}
			int pos = slots.removeFirst();
			aligned.set(pos, ftype);
			assignedByHopId.putIfAbsent(child.getHopID(), ftype);
		}

		for (int j = 0; j < numInputs; j++) {
			if (aligned.get(j) != null) {
				continue;
			}
			Hop parent = parentInputs.get(j);
			if (parent == null) {
				continue;
			}
			FType fallback = assignedByHopId.get(parent.getHopID());
			if (fallback != null) {
				aligned.set(j, fallback);
			}
		}
		return aligned;
	}
}
