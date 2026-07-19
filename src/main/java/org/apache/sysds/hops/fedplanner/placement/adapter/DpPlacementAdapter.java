/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Transparent identity receipts for exclusions already produced by the neutral placement graph. */
public final class DpPlacementAdapter {
	private static final long ABSENT_ARM_COST_BITS = Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);

	public enum MapEntryState {
		ABSENT_LOCAL,
		PRESENT_NULL,
		PRESENT_ROW,
		PRESENT_COL,
		PRESENT_FULL,
		PRESENT_BROADCAST,
		PRESENT_PART,
		PRESENT_OTHER
	}

	public enum OracleInputState {
		ABSENT_LOCAL,
		ROW,
		COL,
		FULL,
		BROADCAST,
		PART,
		OTHER
	}

	public enum ConstructionDisposition {
		AVAILABLE,
		ANCHOR_METADATA_INCOMPLETE,
		UNSUPPORTED_ANCHOR_METADATA,
		FOREIGN_CONTEXT,
		STALE_CONTEXT,
		DUPLICATE_OCCURRENCE,
		REORDERED_EDGE,
		UNMAPPABLE_OCCURRENCE
	}

	public record NeutralEnumerationContext(PlacementAnalysis analysis,
		RewireOccurrenceSnapshot rewireSnapshot, String analysisFingerprint) {
		public NeutralEnumerationContext {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(rewireSnapshot, "rewireSnapshot");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			if(rewireSnapshot.analysis() != analysis)
				throw new IllegalArgumentException("Rewire snapshot belongs to a different analysis");
			if(!analysis.analysisFingerprint().equals(analysisFingerprint)
				|| !analysisFingerprint.equals(rewireSnapshot.analysisFingerprint()))
				throw new IllegalArgumentException("Analysis fingerprint differs");
		}
	}

	public record CandidateMapEntry(CompiledHopKey occurrence, int edgePosition, boolean mapContainsKey,
		FType rawFType, MapEntryState mapEntryState, OracleInputState oracleInputState) {
		public CandidateMapEntry {
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(mapEntryState, "mapEntryState");
			if(edgePosition < 0)
				throw new IllegalArgumentException("edgePosition must be non-negative");
			MapEntryState expectedMapState = mapState(mapContainsKey, rawFType);
			OracleInputState expectedOracleState = oracleState(mapContainsKey, rawFType);
			if(mapEntryState != expectedMapState || oracleInputState != expectedOracleState)
				throw new IllegalArgumentException("Candidate map-entry projection differs");
		}
	}

	public record CandidateOccurrenceSnapshot(NeutralEnumerationContext context,
		CompiledHopKey parentOccurrence, List<CandidateMapEntry> rawEntries,
		List<CandidateMapEntry> promotedEntries, List<OracleInputState> orderedOracleInputs,
		ConstructionDisposition disposition, String reasonCode) {
		public CandidateOccurrenceSnapshot {
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			rawEntries = List.copyOf(rawEntries);
			promotedEntries = List.copyOf(promotedEntries);
			orderedOracleInputs = List.copyOf(orderedOracleInputs);
			Objects.requireNonNull(disposition, "disposition");
			Objects.requireNonNull(reasonCode, "reasonCode");
			if(disposition != ConstructionDisposition.AVAILABLE || !"AVAILABLE".equals(reasonCode))
				throw new IllegalArgumentException("Non-available construction cannot publish a snapshot");
			if(!ownsKey(context.analysis(), parentOccurrence))
				throw new IllegalArgumentException("Parent occurrence is not owned by the analysis");
			if(rawEntries.size() != promotedEntries.size() || promotedEntries.size() != orderedOracleInputs.size())
				throw new IllegalArgumentException("Candidate entry counts differ");
			for(int i = 0; i < rawEntries.size(); i++) {
				CandidateMapEntry raw = rawEntries.get(i);
				CandidateMapEntry promoted = promotedEntries.get(i);
				if(raw.edgePosition() != i || promoted.edgePosition() != i || raw.occurrence() != promoted.occurrence())
					throw new IllegalArgumentException("Candidate edge order or identity differs");
				if(!ownsKey(context.analysis(), raw.occurrence()))
					throw new IllegalArgumentException("Candidate occurrence is not owned by the analysis");
				if(promoted.oracleInputState() != orderedOracleInputs.get(i))
					throw new IllegalArgumentException("Ordered oracle projection differs");
			}
		}
	}

	public record PreSelectionSemanticBlock(NeutralEnumerationContext context,
		List<CandidateOccurrenceSnapshot> candidateSnapshots, int rawCandidateCount,
		int capturedCandidateCount, boolean zeroDifference) {
		public PreSelectionSemanticBlock {
			Objects.requireNonNull(context, "context");
			candidateSnapshots = List.copyOf(candidateSnapshots);
			if(rawCandidateCount < 0 || capturedCandidateCount < 0)
				throw new IllegalArgumentException("Candidate counts must be non-negative");
			if(rawCandidateCount != capturedCandidateCount || capturedCandidateCount != candidateSnapshots.size()
				|| !zeroDifference)
				throw new IllegalArgumentException("Successful candidate capture must be zero-difference");
			for(CandidateOccurrenceSnapshot snapshot : candidateSnapshots)
				if(snapshot.context() != context)
					throw new IllegalArgumentException("Candidate snapshot belongs to a different context");
		}
	}

	public record NormalizedCandidateInputs(CandidateOccurrenceSnapshot snapshot,
		Map<Long, FType> effectiveNonNullFTypeMap, List<FType> effectiveCollectedFTypes,
		List<Hop> exactCollectedHops) {
		public NormalizedCandidateInputs {
			Objects.requireNonNull(snapshot, "snapshot");
			Objects.requireNonNull(effectiveNonNullFTypeMap, "effectiveNonNullFTypeMap");
			LinkedHashMap<Long, FType> copiedMap = new LinkedHashMap<>();
			for(Map.Entry<Long, FType> entry : effectiveNonNullFTypeMap.entrySet()) {
				if(entry.getKey() == null || entry.getValue() == null)
					throw new IllegalArgumentException("Effective FType map must contain only non-null entries");
				copiedMap.put(entry.getKey(), entry.getValue());
			}
			effectiveNonNullFTypeMap = Collections.unmodifiableMap(copiedMap);
			effectiveCollectedFTypes = Collections.unmodifiableList(new ArrayList<>(effectiveCollectedFTypes));
			exactCollectedHops = List.copyOf(exactCollectedHops);
			if(effectiveCollectedFTypes.size() != exactCollectedHops.size()
				|| exactCollectedHops.size() != snapshot.promotedEntries().size())
				throw new IllegalArgumentException("Normalized candidate carrier sizes differ");
			for(int i = 0; i < exactCollectedHops.size(); i++) {
				Hop hop = Objects.requireNonNull(exactCollectedHops.get(i), "exactCollectedHops[" + i + "]");
				CandidateMapEntry promoted = snapshot.promotedEntries().get(i);
				FType effective = effectiveCollectedFTypes.get(i);
				if(hop.getHopID() != promoted.occurrence().hopId())
					throw new IllegalArgumentException("Normalized Hop and occurrence differ");
				if(effective != promoted.rawFType())
					throw new IllegalArgumentException("Normalized collected FType differs from promoted entry");
			}
		}
	}

	public static final class DpSemanticConstructionException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final ConstructionDisposition disposition;
		private final String analysisFingerprint;
		private final CompiledHopKey parentOccurrence;
		private final String reasonCode;

		public DpSemanticConstructionException(ConstructionDisposition disposition, String analysisFingerprint,
			CompiledHopKey parentOccurrence, String reasonCode) {
			super(reasonCode);
			this.disposition = Objects.requireNonNull(disposition, "disposition");
			this.analysisFingerprint = Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			this.parentOccurrence = Objects.requireNonNull(parentOccurrence, "parentOccurrence");
			this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
			if(disposition == ConstructionDisposition.AVAILABLE)
				throw new IllegalArgumentException("AVAILABLE is not a construction failure");
		}

		public ConstructionDisposition disposition() { return disposition; }
		public String analysisFingerprint() { return analysisFingerprint; }
		public CompiledHopKey parentOccurrence() { return parentOccurrence; }
		public String reasonCode() { return reasonCode; }
	}

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
