/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Transparent identity receipts for exclusions already produced by the neutral placement graph. */
public final class DpPlacementAdapter {
	private static final long ABSENT_ARM_COST_BITS = Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);

	public enum TieDecision {
		LOUT_ONLY, FOUT_ONLY, LOUT_LESS, LOUT_EQUAL, FOUT_LESS
	}

	public record TieReceipt(long rootHopId, FedPlan loutPlan, FedPlan foutPlan, FedPlan selectedPlan,
		long loutCostBits, long foutCostBits, TieDecision decision) {
		public TieReceipt {
			Objects.requireNonNull(selectedPlan, "selectedPlan");
			Objects.requireNonNull(decision, "decision");
		}
	}

	public record ExactSelection(PlacementAnalysis analysis, FederatedPlannerDpMemoTable memo,
		FedPlan legacyOptimalPlan, List<Pair<Long, FederatedOutput>> aggregateChildEdges,
		List<FedPlan> selectedRootPlans, List<Hop> selectedRootHops, long objectiveCostBits,
		List<TieReceipt> tieReceipts, List<GraphExclusionReceipt> graphExclusionReceipts,
		String analysisFingerprint) {
		public ExactSelection {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(memo, "memo");
			Objects.requireNonNull(legacyOptimalPlan, "legacyOptimalPlan");
			aggregateChildEdges = List.copyOf(aggregateChildEdges);
			selectedRootPlans = List.copyOf(selectedRootPlans);
			selectedRootHops = List.copyOf(selectedRootHops);
			tieReceipts = List.copyOf(tieReceipts);
			graphExclusionReceipts = List.copyOf(graphExclusionReceipts);
			if(!analysis.analysisFingerprint().equals(analysisFingerprint))
				throw new IllegalArgumentException("Analysis fingerprint differs");
			int size = aggregateChildEdges.size();
			if(selectedRootPlans.size() != size || selectedRootHops.size() != size || tieReceipts.size() != size)
				throw new IllegalArgumentException("Aggregate receipt sizes differ");
			for(int i = 0; i < size; i++) {
				Pair<Long, FederatedOutput> edge = aggregateChildEdges.get(i);
				FedPlan selected = memo.getFedPlanAfterPrune(edge);
				if(selected == null || selected != selectedRootPlans.get(i))
					throw new IllegalArgumentException("Selected root plan is not owned by the memo");
				if(selected.getHopRef() != selectedRootHops.get(i))
					throw new IllegalArgumentException("Selected root Hop identity differs");
				TieReceipt tie = tieReceipts.get(i);
				if(tie.rootHopId() != edge.getLeft() || tie.selectedPlan() != selected)
					throw new IllegalArgumentException("Tie receipt does not bind the aggregate edge");
			}
			for(GraphExclusionReceipt receipt : graphExclusionReceipts)
				if(receipt.analysis() != analysis)
					throw new IllegalArgumentException("Graph exclusion belongs to a different analysis");
		}
	}

	public record GraphExclusionReceipt(PlacementAnalysis analysis,
		PlacementAnalysis.HopOccurrenceProjection occurrence, NeutralPlacementGraph.Node node,
		NeutralPlacementGraph.Exclusion exclusion) {
		public GraphExclusionReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(node, "node");
			Objects.requireNonNull(exclusion, "exclusion");
			if(analysis.occurrences().stream().noneMatch(candidate -> candidate == occurrence))
				throw new IllegalArgumentException("Occurrence is not owned by the analysis");
			if(!occurrence.key().equals(node.key()))
				throw new IllegalArgumentException("Occurrence and node keys differ");
			if(analysis.graph().node(node.key()).orElseThrow() != node)
				throw new IllegalArgumentException("Node is not owned by the analysis graph");
			if(analysis.hop(node.key()).orElseThrow() != occurrence.hop())
				throw new IllegalArgumentException("Occurrence Hop is not owned by the analysis");
			if(node.exclusions().stream().noneMatch(candidate -> candidate == exclusion))
				throw new IllegalArgumentException("Exclusion is not owned by the node");
		}
	}

	public record Result(PlacementAnalysis analysis, List<GraphExclusionReceipt> certificateReceipts,
		String analysisFingerprint) {
		public Result {
			Objects.requireNonNull(analysis, "analysis");
			certificateReceipts = List.copyOf(certificateReceipts);
			if(!analysis.analysisFingerprint().equals(analysisFingerprint))
				throw new IllegalArgumentException("Analysis fingerprint differs");
			for(GraphExclusionReceipt receipt : certificateReceipts)
				if(receipt.analysis() != analysis)
					throw new IllegalArgumentException("Receipt belongs to a different analysis");
			int receiptIndex = 0;
			for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
				for(NeutralPlacementGraph.Exclusion exclusion : node.exclusions()) {
					if(receiptIndex >= certificateReceipts.size())
						throw new IllegalArgumentException("Missing graph exclusion receipt");
					GraphExclusionReceipt receipt = certificateReceipts.get(receiptIndex++);
					if(receipt.node() != node || receipt.exclusion() != exclusion)
						throw new IllegalArgumentException("Graph exclusion receipt order or identity differs");
				}
			if(receiptIndex != certificateReceipts.size())
				throw new IllegalArgumentException("Unexpected graph exclusion receipt");
		}
		public PlacementAnalysis producer() { return analysis; }
	}

	public Result select(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		List<GraphExclusionReceipt> receipts = new ArrayList<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes()) {
			List<PlacementAnalysis.HopOccurrenceProjection> occurrences = analysis.occurrences().stream()
				.filter(candidate -> candidate.key().equals(node.key())).toList();
			if(occurrences.size() != 1)
				throw new IllegalStateException("Neutral graph node must have one exact occurrence: " + node.key());
			for(NeutralPlacementGraph.Exclusion exclusion : node.exclusions())
				receipts.add(new GraphExclusionReceipt(analysis, occurrences.get(0), node, exclusion));
		}
		return new Result(analysis, receipts, analysis.analysisFingerprint());
	}

	public ExactSelection selectExact(PlacementAnalysis analysis, FederatedPlannerDpMemoTable memo,
		FedPlan legacyOptimalPlan) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(memo, "memo");
		Objects.requireNonNull(legacyOptimalPlan, "legacyOptimalPlan");
		List<Pair<Long, FederatedOutput>> rawEdges = Objects.requireNonNull(
			legacyOptimalPlan.getChildFedPlans(), "legacyOptimalPlan.childFedPlans");
		// Enumerator-owned aggregate edges are built as a fresh ordered mutable list.
		// An immutable copied carrier has no producer provenance and must not cross this seam.
		if(List.copyOf(rawEdges) == rawEdges)
			throw new IllegalArgumentException("Aggregate is not the enumerator-owned carrier");

		List<FedPlan> selectedPlans = new ArrayList<>();
		List<Hop> selectedHops = new ArrayList<>();
		List<TieReceipt> ties = new ArrayList<>();
		double objective = 0;
		for(Pair<Long, FederatedOutput> edge : rawEdges) {
			if(edge == null || edge.getLeft() == null || edge.getRight() == null)
				throw new IllegalArgumentException("Aggregate edge is incomplete");
			FedPlan selected = memo.getFedPlanAfterPrune(edge);
			if(selected == null || selected.getFedOutType() != edge.getRight())
				throw new IllegalArgumentException("Aggregate edge is not selected by the supplied memo");
			Hop hop = Objects.requireNonNull(selected.getHopRef(), "selectedPlan.hopRef");
			long executableHopId = memo.resolveOriginalHopId(edge.getLeft());
			Hop executableHop = memo.resolveOriginalHop(edge.getLeft());
			if(executableHop == null)
				throw new IllegalArgumentException("Aggregate edge has no executable Hop association");
			if(executableHop.getHopID() != executableHopId)
				throw new IllegalArgumentException("Aggregate executable Hop identity differs");
			Hop ownedCandidate = executableHop;
			boolean ownedHop = analysis.occurrences().stream()
				.anyMatch(occurrence -> occurrence.hop() == ownedCandidate);
			if(!ownedHop)
				throw new IllegalArgumentException("Selected root Hop is foreign to the supplied analysis");
			FedPlan lout = memo.getFedPlanAfterPrune(edge.getLeft(), FederatedOutput.LOUT);
			FedPlan fout = memo.getFedPlanAfterPrune(edge.getLeft(), FederatedOutput.FOUT);
			TieDecision decision;
			FedPlan expected;
			if(lout == null) {
				if(fout == null) throw new IllegalArgumentException("Aggregate edge has no memo arm");
				decision = TieDecision.FOUT_ONLY; expected = fout;
			}
			else if(fout == null) {
				decision = TieDecision.LOUT_ONLY; expected = lout;
			}
			else if(lout.getCumulativeCost() == fout.getCumulativeCost()) {
				decision = TieDecision.LOUT_EQUAL; expected = lout;
			}
			else if(lout.getCumulativeCost() < fout.getCumulativeCost()) {
				decision = TieDecision.LOUT_LESS; expected = lout;
			}
			else {
				decision = TieDecision.FOUT_LESS; expected = fout;
			}
			if(selected != expected)
				throw new IllegalArgumentException("Aggregate edge conflicts with memo cost selection");
			selectedPlans.add(selected);
			selectedHops.add(hop);
			objective += selected.getCumulativeCost();
			ties.add(new TieReceipt(edge.getLeft(), lout, fout, selected,
				lout == null ? ABSENT_ARM_COST_BITS : Double.doubleToRawLongBits(lout.getCumulativeCost()),
				fout == null ? ABSENT_ARM_COST_BITS : Double.doubleToRawLongBits(fout.getCumulativeCost()), decision));
		}
		long objectiveBits = Double.doubleToRawLongBits(legacyOptimalPlan.getCumulativeCost());
		if(Double.doubleToRawLongBits(objective) != objectiveBits)
			throw new IllegalArgumentException("Aggregate objective bits differ from selected memo plans");
		Result exclusions = select(analysis);
		return new ExactSelection(analysis, memo, legacyOptimalPlan, rawEdges, selectedPlans, selectedHops,
			objectiveBits, ties, exclusions.certificateReceipts(), analysis.analysisFingerprint());
	}
}
