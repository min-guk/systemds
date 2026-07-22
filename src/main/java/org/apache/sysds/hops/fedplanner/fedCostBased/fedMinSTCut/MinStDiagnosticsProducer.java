/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics.ChildNetworkCost;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics.HopFacts;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStDiagnostics.OptimalSummary;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ContributionKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EdgeContribution;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CompiledInputEdgeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Graph-free, fail-closed projection of exact MinST facts and selection into diagnostics. */
public final class MinStDiagnosticsProducer {
	private static final long ZERO_BITS = Double.doubleToRawLongBits(0.0);

	private MinStDiagnosticsProducer() {
		// utility class
	}

	public static MinStDiagnostics project(PlacementAnalysis analysis, MinStExactCostFacts facts,
		MinStExactSelection selection) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(facts, "facts");
		Objects.requireNonNull(selection, "selection");
		validateFactsOwnerAndFreshness(analysis, facts);
		ValidatedSelection validated = validateSelection(facts, selection);
		CostProjection costs = projectCrossingCosts(facts, validated.source(), selection.objectiveBits());
		List<OptimalSummary> summaries = optimalSummaries(facts, validated.states());
		List<HopFacts> hops = hopFacts(analysis, facts, validated.states(), costs);
		return new MinStDiagnostics(selection.objectiveBits(), selection.sourcePartitionNodeIds(),
			summaries, hops);
	}

	private static void validateFactsOwnerAndFreshness(PlacementAnalysis analysis,
		MinStExactCostFacts facts) {
		if(analysis != facts.analysis())
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_FOREIGN_ANALYSIS");
		if(!analysis.analysisFingerprint().equals(facts.analysisFingerprint()))
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_FINGERPRINT_MISMATCH");
		validateScopeIdentity(analysis, facts);
		MinStExactCostFactsProducer.validate(analysis, facts.analysisFingerprint(), facts.orderedScope(),
			facts.decisionFactsInScopeOrder(), facts.directedEdgesInDerivationOrder(),
			facts.auxiliaryGroupsInCanonicalOrder(), facts.obligationFactsInCanonicalOrder(),
			facts.derivationFingerprint());
	}

	private static void validateScopeIdentity(PlacementAnalysis analysis, MinStExactCostFacts facts) {
		List<CompiledHopKey> expected = analysis.compiledHopOccurrences().stream()
			.map(PlacementAnalysis.HopOccurrenceProjection::key).toList();
		if(facts.orderedScope().size() != expected.size())
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_SCOPE_CARDINALITY");
		Set<CompiledHopKey> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<CompiledHopKey> emitted = new ArrayList<>();
		for(int index = 0; index < expected.size(); index++) {
			CompiledHopKey key = facts.orderedScope().get(index);
			if(key != expected.get(index) || !seen.add(key))
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_SCOPE_IDENTITY_OR_ORDER");
			if(analysis.graph().node(key).orElseThrow().emittedWork())
				emitted.add(key);
		}
		if(facts.decisionFactsInScopeOrder().size() != emitted.size())
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_DECISION_CARDINALITY");
		for(int index = 0; index < emitted.size(); index++)
			if(facts.decisionFactsInScopeOrder().get(index).key() != emitted.get(index))
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_SCOPE_IDENTITY_OR_ORDER");
	}

	private static ValidatedSelection validateSelection(MinStExactCostFacts facts,
		MinStExactSelection selection) {
		if(!MinStExactSelection.UNIQUE.equals(selection.tieCertificate()))
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_SELECTION_NOT_UNIQUE");
		if(selection.minimumSourcePartitionCertificates().size() != 1
			|| !selection.minimumSourcePartitionCertificates().get(0).equals(selection.sourcePartitionNodeIds()))
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_MINIMUM_CERTIFICATE_CARDINALITY");
		List<PlacementState> states = selection.selectedStatesInScopeOrder();
		if(states.size() != facts.decisionFactsInScopeOrder().size())
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_SELECTED_STATE_CARDINALITY");
		Set<Long> source = new LinkedHashSet<>(selection.sourcePartitionNodeIds());
		if(source.size() != selection.sourcePartitionNodeIds().size()
			|| !selection.sourcePartitionNodeIds().equals(selection.sourcePartitionNodeIds().stream().sorted().toList()))
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_SOURCE_CERTIFICATE_NOT_CANONICAL");
		for(int index = 0; index < states.size(); index++) {
			DecisionFact decision = facts.decisionFactsInScopeOrder().get(index);
			PlacementState state = Objects.requireNonNull(states.get(index), "selected state");
			boolean computeSource = source.contains(decision.computeNodeId());
			boolean placementSource = source.contains(decision.placementNodeId());
			if((state.execType() == ExecType.FED) != computeSource
				|| (state.output() == FederatedOutput.FOUT) != placementSource)
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_SELECTED_STATE_SOURCE_MISMATCH|key="
					+ decision.key().normalizedSignature());
			List<PlacementState> matches = decision.legalStatesInCanonicalOrder().stream()
				.filter(candidate -> candidate.execType() == state.execType()
					&& candidate.output() == state.output()).toList();
			if(matches.size() != 1 || !matches.get(0).equals(state))
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_SELECTED_STATE_NOT_LEGAL|key="
					+ decision.key().normalizedSignature());
		}
		return new ValidatedSelection(Set.copyOf(source), List.copyOf(states));
	}

	private static CostProjection projectCrossingCosts(MinStExactCostFacts facts, Set<Long> source,
		long selectedObjectiveBits) {
		IdentityHashMap<CompiledHopKey, CostBucket> buckets = new IdentityHashMap<>();
		for(CompiledHopKey key : facts.orderedScope())
			buckets.put(key, new CostBucket());
		double global = 0.0;
		for(DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder()) {
			boolean fromSource = edge.fromNodeId() == facts.sourceNodeId()
				|| source.contains(edge.fromNodeId());
			boolean toSource = edge.toNodeId() != facts.sinkNodeId() && source.contains(edge.toNodeId());
			if(!fromSource || toSource)
				continue;
			for(EdgeContribution contribution : edge.contributionsInDerivationOrder()) {
				CostBucket bucket = buckets.get(contribution.ownerKey());
				if(bucket == null)
					throw new IllegalArgumentException("MINST_DIAGNOSTICS_FOREIGN_CONTRIBUTION_OWNER");
				double value = canonicalCost(contribution.costBits(), "MINST_DIAGNOSTICS_CONTRIBUTION_COST");
				global = canonicalCost(Double.doubleToRawLongBits(global + value),
					"MINST_DIAGNOSTICS_GLOBAL_TOTAL");
				if(isNetwork(contribution.kind()))
					bucket.addNetwork(contribution, value);
				else
					bucket.addSelf(value);
			}
		}
		if(Double.doubleToRawLongBits(global) != selectedObjectiveBits)
			throw new IllegalArgumentException("MINST_DIAGNOSTICS_OBJECTIVE_REPLAY_MISMATCH");
		return new CostProjection(buckets);
	}

	private static boolean isNetwork(ContributionKind kind) {
		return kind == ContributionKind.UPLOAD || kind == ContributionKind.DOWNLOAD
			|| kind == ContributionKind.HARD_UPLOAD_OR || kind == ContributionKind.HARD_DOWNLOAD_OR
			|| kind == ContributionKind.PRICE_UPLOAD_OR || kind == ContributionKind.PRICE_DOWNLOAD_OR;
	}

	private static List<OptimalSummary> optimalSummaries(MinStExactCostFacts facts,
		List<PlacementState> states) {
		List<OptimalSummary> result = new ArrayList<>(facts.decisionFactsInScopeOrder().size());
		for(int index = 0; index < facts.decisionFactsInScopeOrder().size(); index++) {
			DecisionFact decision = facts.decisionFactsInScopeOrder().get(index);
			Hop hop = facts.analysis().hop(decision.key()).orElseThrow();
			PlacementState selected = states.get(index);
			result.add(new OptimalSummary(hop.getHopID(), hop.getOpString(),
				selected.execType().name(), selected.output().name(), null,
				selected.fType() == null ? null : selected.fType().name(),
				hasState(decision, ExecType.CP, FederatedOutput.LOUT),
				hasState(decision, ExecType.CP, FederatedOutput.FOUT),
				hasState(decision, ExecType.FED, FederatedOutput.LOUT),
				hasState(decision, ExecType.FED, FederatedOutput.FOUT)));
		}
		return List.copyOf(result);
	}

	private static List<HopFacts> hopFacts(PlacementAnalysis analysis, MinStExactCostFacts facts,
		List<PlacementState> states, CostProjection costs) {
		List<HopProjection> projections = new ArrayList<>(facts.decisionFactsInScopeOrder().size());
		for(int index = 0; index < facts.decisionFactsInScopeOrder().size(); index++) {
			DecisionFact decision = facts.decisionFactsInScopeOrder().get(index);
			Hop hop = analysis.hop(decision.key()).orElseThrow();
			PlacementState selected = states.get(index);
			CostBucket bucket = costs.byOwner().get(decision.key());
			if(bucket == null)
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_MISSING_COST_BUCKET");
			projections.add(new HopProjection(decision.key(), hop, new HopFacts(hop.getHopID(),
				hop.getClass().getSimpleName(), hop.getOpString(), nameOrNull(hop.getDataType()),
				selected.execType().name(), selected.execType().name(), selected.output().name(), null,
				selected.fType() == null ? null : selected.fType().name(), hopIds(hop.getInput()),
				hopIds(hop.getParent()), missingParentHopIds(hop, analysis), bucket.selfBits(),
				bucket.networkBits(), bucket.totalBits(), ZERO_BITS, ZERO_BITS,
				positiveChildNetworkCosts(analysis, decision.key(), bucket), hop.getDim1(), hop.getDim2(),
				hop.getBlocksize(), hop.getNnz(), rawBits(hop.getInputMemEstimate()),
				rawBits(hop.getOutputMemEstimate()), rawBits(hop.getInputMemEstimate()),
				rawBits(hop.getOutputMemEstimate()), nameOrNull(hop.getUpdateType()))));
		}
		return projections.stream().sorted(Comparator.comparingLong((HopProjection projection) ->
			projection.hop().getHopID()).thenComparing(projection -> projection.key().normalizedSignature()))
			.map(HopProjection::facts).toList();
	}

	private static List<ChildNetworkCost> positiveChildNetworkCosts(PlacementAnalysis analysis,
		CompiledHopKey owner, CostBucket bucket) {
		Map<Integer, Double> byInput = new LinkedHashMap<>();
		Map<Integer, Long> childHopIdByInput = new LinkedHashMap<>();
		for(PeerContribution contribution : bucket.peerContributions()) {
			if(contribution.peer() == null || contribution.inputPosition() < 0 || contribution.cost() <= 0.0)
				continue;
			CompiledInputEdgeFact edge;
			try {
				edge = analysis.requireExactCompiledInputEdge(contribution.peer(), owner,
					contribution.inputPosition());
			}
			catch(IllegalArgumentException notAChild) {
				continue;
			}
			if(edge.producer() != contribution.peer() || edge.consumer() != owner)
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_CHILD_EDGE_IDENTITY_MISMATCH");
			long hopId = analysis.hop(edge.producer()).orElseThrow().getHopID();
			Long previousHopId = childHopIdByInput.putIfAbsent(contribution.inputPosition(), hopId);
			if(previousHopId != null && previousHopId.longValue() != hopId)
				throw new IllegalArgumentException("MINST_DIAGNOSTICS_CHILD_NETWORK_EDGE_AMBIGUOUS");
			byInput.merge(contribution.inputPosition(), contribution.cost(), (left, right) ->
				canonicalCost(Double.doubleToRawLongBits(left + right),
					"MINST_DIAGNOSTICS_CHILD_NETWORK_TOTAL"));
		}
		List<ChildNetworkCost> result = new ArrayList<>();
		for(Map.Entry<Integer, Double> entry : byInput.entrySet())
			result.add(new ChildNetworkCost(childHopIdByInput.get(entry.getKey()),
				Double.doubleToRawLongBits(entry.getValue())));
		return List.copyOf(result);
	}

	private static boolean hasState(DecisionFact decision, ExecType exec, FederatedOutput output) {
		return decision.legalStatesInCanonicalOrder().stream()
			.anyMatch(state -> state.execType() == exec && state.output() == output);
	}

	private static List<Long> hopIds(List<Hop> hops) {
		return hops.stream().filter(Objects::nonNull).map(Hop::getHopID).toList();
	}

	private static List<Long> missingParentHopIds(Hop hop, PlacementAnalysis analysis) {
		Set<Hop> owned = Collections.newSetFromMap(new IdentityHashMap<>());
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.compiledHopOccurrences())
			owned.add(occurrence.hop());
		return hop.getParent().stream().filter(parent -> parent != null && !owned.contains(parent))
			.map(Hop::getHopID).sorted().distinct().toList();
	}

	private static long rawBits(double value) {
		return Double.doubleToRawLongBits(value);
	}

	private static String nameOrNull(Object value) {
		return value == null ? null : value.toString();
	}

	private static double canonicalCost(long bits, String reason) {
		double value = Double.longBitsToDouble(bits);
		if(!Double.isFinite(value) || value < 0.0 || bits == Double.doubleToRawLongBits(-0.0))
			throw new IllegalArgumentException(reason + "|value=" + value);
		return value;
	}

	private record ValidatedSelection(Set<Long> source, List<PlacementState> states) { }
	private record CostProjection(IdentityHashMap<CompiledHopKey, CostBucket> byOwner) { }
	private record PeerContribution(CompiledHopKey peer, int inputPosition, double cost) { }
	private record HopProjection(CompiledHopKey key, Hop hop, HopFacts facts) { }

	private static final class CostBucket {
		private double self;
		private double network;
		private final List<PeerContribution> peerContributions = new ArrayList<>();
		void addSelf(double value) {
			self = canonicalCost(Double.doubleToRawLongBits(self + value), "MINST_DIAGNOSTICS_SELF_TOTAL");
		}

		void addNetwork(EdgeContribution contribution, double value) {
			network = canonicalCost(Double.doubleToRawLongBits(network + value),
				"MINST_DIAGNOSTICS_NETWORK_TOTAL");
			peerContributions.add(new PeerContribution(contribution.peerKeyOrNull(),
				contribution.inputPosition(), value));
		}

		long selfBits() { return Double.doubleToRawLongBits(self); }
		long networkBits() { return Double.doubleToRawLongBits(network); }
		long totalBits() {
			return Double.doubleToRawLongBits(canonicalCost(Double.doubleToRawLongBits(self + network),
				"MINST_DIAGNOSTICS_OWNER_TOTAL"));
		}
		List<PeerContribution> peerContributions() { return peerContributions; }
	}
}
