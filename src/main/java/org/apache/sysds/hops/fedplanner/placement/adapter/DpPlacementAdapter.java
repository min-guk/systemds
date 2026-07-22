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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy.CapturedPlacementRequest;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpMemoTable.FedPlan;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireOccurrenceSnapshot;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireConsumerEdge;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpRewireTransTable.RewireTransientForwardEdge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedInvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolution;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.CapturedResolutionRequest;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ConsumerEdgeEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.ConsumerNodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.InvocationEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementCandidateRuleResolver.TransientForwardEvidence;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
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
		RewireOccurrenceSnapshot rewireSnapshot, String analysisFingerprint, int numWorkers,
		Map<CompiledHopKey, CapturedInvocationEvidence> invocationEvidence,
		Map<CompiledHopKey, Privacy> privacy) {
		public NeutralEnumerationContext {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(rewireSnapshot, "rewireSnapshot");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			if(numWorkers <= 0)
				throw new IllegalArgumentException("numWorkers must be positive");
			invocationEvidence = immutableIdentityMap(invocationEvidence, "invocationEvidence");
			privacy = immutableIdentityMap(privacy, "privacy");
			if(rewireSnapshot.analysis() != analysis)
				throw new IllegalArgumentException("Rewire snapshot belongs to a different analysis");
			if(!analysis.analysisFingerprint().equals(analysisFingerprint)
				|| !analysisFingerprint.equals(rewireSnapshot.analysisFingerprint()))
				throw new IllegalArgumentException("Analysis fingerprint differs");
			for(HopOccurrenceProjection occurrence : rewireSnapshot.candidateOccurrences()) {
				if(!invocationEvidence.containsKey(occurrence.key()) || !privacy.containsKey(occurrence.key()))
					throw new IllegalArgumentException("Captured occurrence evidence is incomplete");
			}
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
			if(!ownsCandidateKey(context.analysis(), parentOccurrence))
				throw new IllegalArgumentException("Parent occurrence is not owned by the analysis");
			if(rawEntries.size() != promotedEntries.size() || promotedEntries.size() != orderedOracleInputs.size())
				throw new IllegalArgumentException("Candidate entry counts differ");
			for(int i = 0; i < rawEntries.size(); i++) {
				CandidateMapEntry raw = rawEntries.get(i);
				CandidateMapEntry promoted = promotedEntries.get(i);
				if(raw.edgePosition() != i || promoted.edgePosition() != i || raw.occurrence() != promoted.occurrence())
					throw new IllegalArgumentException("Candidate edge order or identity differs");
				if(!ownsCandidateKey(context.analysis(), raw.occurrence()))
					throw new IllegalArgumentException("Candidate occurrence is not owned by the analysis");
			}
			DpPlacementAdapter.orderedOracleInputs(context, parentOccurrence, rawEntries,
				rawEntries.stream().map(CandidateMapEntry::oracleInputState).toList());
			List<CandidateInputState> factInputs = orderedOracleInputs.stream()
				.map(input -> input == OracleInputState.ABSENT_LOCAL ? CandidateInputState.absentLocal()
					: CandidateInputState.present(FType.valueOf(input.name()))).toList();
			context.analysis().candidateRuleFacts().requireExact(parentOccurrence, factInputs);
		}
	}

	public record PreSelectionSemanticBlock(NeutralEnumerationContext context,
		List<CandidateOccurrenceSnapshot> candidateSnapshots,
		List<Long> candidateVariantOrdinals, List<CandidateDecisionReceipt> candidateDecisionReceipts, int rawCandidateCount,
		int capturedCandidateCount, boolean zeroDifference) {
		public PreSelectionSemanticBlock(NeutralEnumerationContext context,
			List<CandidateOccurrenceSnapshot> candidateSnapshots, int rawCandidateCount,
			int capturedCandidateCount, boolean zeroDifference) {
			this(context, candidateSnapshots, List.of(), List.of(), rawCandidateCount, capturedCandidateCount, zeroDifference);
		}
		public PreSelectionSemanticBlock {
			Objects.requireNonNull(context, "context");
			candidateSnapshots = List.copyOf(candidateSnapshots);
			candidateVariantOrdinals = List.copyOf(candidateVariantOrdinals);
			candidateDecisionReceipts = List.copyOf(candidateDecisionReceipts);
			if(rawCandidateCount < 0 || capturedCandidateCount < 0)
				throw new IllegalArgumentException("Candidate counts must be non-negative");
			if(rawCandidateCount != capturedCandidateCount || capturedCandidateCount != candidateSnapshots.size()
				|| !zeroDifference)
				throw new IllegalArgumentException("Successful candidate capture must be zero-difference");
			for(CandidateOccurrenceSnapshot snapshot : candidateSnapshots)
				if(snapshot.context() != context)
					throw new IllegalArgumentException("Candidate snapshot belongs to a different context");
			if(!candidateDecisionReceipts.isEmpty() || !candidateVariantOrdinals.isEmpty()) {
				if(candidateDecisionReceipts.size() != candidateSnapshots.size()
					|| candidateVariantOrdinals.size() != candidateSnapshots.size())
					throw new IllegalArgumentException("Candidate decision receipt count differs");
				for(int i = 0; i < candidateDecisionReceipts.size(); i++) {
					CandidateDecisionReceipt receipt = candidateDecisionReceipts.get(i);
					if(receipt.context() != context || receipt.candidateSnapshot() != candidateSnapshots.get(i)
						|| receipt.variantOrdinal() != candidateVariantOrdinals.get(i)
						|| !receipt.orderedOracleInputs().equals(candidateSnapshots.get(i).orderedOracleInputs()))
						throw new IllegalArgumentException("Candidate decision receipt order or identity differs");
				}
			}
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
			if(effectiveCollectedFTypes.size() != exactCollectedHops.size())
				throw new IllegalArgumentException("Normalized candidate carrier sizes differ");
			int promotedIndex = 0;
			for(int i = 0; i < exactCollectedHops.size(); i++) {
				Hop hop = Objects.requireNonNull(exactCollectedHops.get(i), "exactCollectedHops[" + i + "]");
				HopOccurrenceProjection projected = snapshot.context().rewireSnapshot().projectExactCarrier(hop);
				if(projected == null)
					throw new IllegalArgumentException("Normalized Hop occurrence is missing");
				if(promotedIndex < snapshot.promotedEntries().size()
					&& projected.key() == snapshot.promotedEntries().get(promotedIndex).occurrence()) {
					CandidateMapEntry promoted = snapshot.promotedEntries().get(promotedIndex++);
					if(effectiveCollectedFTypes.get(i) != promoted.rawFType())
						throw new IllegalArgumentException("Normalized collected FType differs from promoted entry");
				}
			}
			if(promotedIndex != snapshot.promotedEntries().size())
				throw new IllegalArgumentException("Normalized physical carrier order differs");
		}
	}

	public record CandidatePlacementArm(ExecType execType, FederatedOutput output) { }

	public record CandidateDecisionReceipt(NeutralEnumerationContext context,
		CandidateOccurrenceSnapshot candidateSnapshot, long variantOrdinal,
		List<OracleInputState> orderedOracleInputs, ExecType nativeExec,
		FederatedOutput nativeOutput, FType nativeFoutFType, FType logicalFType,
		ReasonCode reasonCode, ConstructionDisposition disposition,
		CapturedInvocationEvidence invocationEvidence, Privacy privacy,
		boolean allowCPLOUT, boolean allowCPFOUT, boolean allowFEDLOUT, boolean allowFEDFOUT,
		CandidateCapabilityFact capabilityFact,
		Map<CandidatePlacementArm, PlacementState> candidateStateCatalog) {
		public CandidateDecisionReceipt {
			Objects.requireNonNull(context, "context");
			Objects.requireNonNull(candidateSnapshot, "candidateSnapshot");
			orderedOracleInputs = List.copyOf(orderedOracleInputs);
			Objects.requireNonNull(nativeExec, "nativeExec");
			Objects.requireNonNull(nativeOutput, "nativeOutput");
			Objects.requireNonNull(reasonCode, "reasonCode");
			Objects.requireNonNull(disposition, "disposition");
			Objects.requireNonNull(invocationEvidence, "invocationEvidence");
			Objects.requireNonNull(privacy, "privacy");
			Objects.requireNonNull(capabilityFact, "capabilityFact");
			candidateStateCatalog = Map.copyOf(Objects.requireNonNull(candidateStateCatalog,
				"candidateStateCatalog"));
			if(context != candidateSnapshot.context() || variantOrdinal < 0
				|| !orderedOracleInputs.equals(candidateSnapshot.orderedOracleInputs()))
				throw new IllegalArgumentException("Candidate decision receipt identity or order differs");
		}

		public PlacementState requireExactState(ExecType execType, FederatedOutput output) {
			PlacementState state = candidateStateCatalog.get(new CandidatePlacementArm(execType, output));
			if(state == null)
				throw new IllegalArgumentException("Candidate arm has no exact analysis-owned placement state");
			return state;
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

	public static void validateCandidateInputs(PlacementAnalysis analysis, HopOccurrenceProjection parent,
		List<Pair<Long, FederatedOutput>> planChilds, List<Hop> collectedHops, List<FType> collectedFTypes,
		Map<Long, FType> fedInputTypeMap, FederatedPlannerDpMemoTable memo) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(planChilds, "planChilds");
		Objects.requireNonNull(collectedHops, "collectedHops");
		Objects.requireNonNull(collectedFTypes, "collectedFTypes");
		Objects.requireNonNull(fedInputTypeMap, "fedInputTypeMap");
		Objects.requireNonNull(memo, "memo");
		if(analysis.occurrences().stream().noneMatch(candidate -> candidate == parent)
			|| !analysis.candidateRuleDomain().containsExactParent(parent.key()))
			throw failure(analysis, parent.key(), ConstructionDisposition.FOREIGN_CONTEXT, "FOREIGN_CONTEXT");
		if(planChilds.size() != collectedHops.size() || collectedHops.size() != collectedFTypes.size())
			throw failure(analysis, parent.key(), ConstructionDisposition.REORDERED_EDGE, "REORDERED_EDGE");
		for(int i = 0; i < collectedHops.size(); i++) {
			Pair<Long, FederatedOutput> edge = planChilds.get(i);
			Hop hop = collectedHops.get(i);
			if(edge == null || edge.getLeft() == null || edge.getRight() == null || hop == null)
				throw failure(analysis, parent.key(), ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"UNMAPPABLE_OCCURRENCE");
			if(edge.getLeft() != hop.getHopID())
				throw failure(analysis, parent.key(), ConstructionDisposition.REORDERED_EDGE, "REORDERED_EDGE");
			List<HopOccurrenceProjection> owned = analysis.occurrences().stream()
				.filter(candidate -> candidate.hop() == hop
					&& analysis.candidateRuleDomain().containsExactParent(candidate.key())).toList();
			if(owned.isEmpty())
				throw failure(analysis, parent.key(), ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"UNMAPPABLE_OCCURRENCE");
			if(owned.size() != 1)
				throw failure(analysis, parent.key(), ConstructionDisposition.DUPLICATE_OCCURRENCE,
					"DUPLICATE_OCCURRENCE");
			if(fedInputTypeMap.containsKey(hop.getHopID()) && fedInputTypeMap.get(hop.getHopID()) == null)
				throw failure(analysis, parent.key(), ConstructionDisposition.ANCHOR_METADATA_INCOMPLETE,
					"PRESENT_NULL");
		}
	}

	public static NormalizedCandidateInputs normalizeCandidateInputs(NeutralEnumerationContext context,
		HopOccurrenceProjection parent, List<Pair<Long, FederatedOutput>> planChilds, List<Hop> collectedHops,
		List<FType> collectedFTypes, Map<Long, FType> fedInputTypeMap, FederatedPlannerDpMemoTable memo) {
		Objects.requireNonNull(context, "context");
		validateContextCandidateInputs(context, parent, planChilds, collectedHops, collectedFTypes,
			fedInputTypeMap, memo);

		List<CandidateMapEntry> rawEntries = new ArrayList<>(collectedHops.size());
		List<CandidateMapEntry> promotedEntries = new ArrayList<>(collectedHops.size());
		List<OracleInputState> rawOrderOracleStates = new ArrayList<>(collectedHops.size());
		List<OracleInputState> orderedOracleInputs = new ArrayList<>(collectedHops.size());
		List<FType> effectiveCollectedFTypes = new ArrayList<>(collectedFTypes);
		LinkedHashMap<Long, FType> effectiveMap = new LinkedHashMap<>();
		IdentityHashMap<CompiledHopKey, Integer> remainingPhysicalInputs = new IdentityHashMap<>();
		Hop parentHop = context.analysis().hop(parent.key()).orElseThrow(() ->
			failure(context.analysis(), parent.key(), ConstructionDisposition.STALE_CONTEXT,
				"CANDIDATE_PARENT_STALE"));
		for(Hop input : parentHop.getInput()) {
			HopOccurrenceProjection occurrence = context.rewireSnapshot().projectExactCarrier(input);
			if(occurrence == null)
				throw failure(context.analysis(), parent.key(), ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"CANDIDATE_DIRECT_INPUT_UNMAPPABLE");
			remainingPhysicalInputs.merge(occurrence.key(), 1, Integer::sum);
		}
		for(Map.Entry<Long, FType> entry : fedInputTypeMap.entrySet()) {
			if(entry.getKey() == null || entry.getValue() == null)
				throw failure(context.analysis(), parent.key(),
					ConstructionDisposition.ANCHOR_METADATA_INCOMPLETE, "PRESENT_NULL");
			effectiveMap.put(entry.getKey(), entry.getValue());
		}

		for(int i = 0; i < collectedHops.size(); i++) {
			Hop hop = collectedHops.get(i);
			Pair<Long, FederatedOutput> edge = planChilds.get(i);
			HopOccurrenceProjection occurrence = context.rewireSnapshot().projectExactCarrier(hop);
			boolean rawContainsKey = fedInputTypeMap.containsKey(hop.getHopID());
			FType rawType = rawContainsKey ? fedInputTypeMap.get(hop.getHopID()) : null;
			FType collectedType = effectiveCollectedFTypes.get(i);
			FedPlan childPlan = memo.getFedPlanAfterPrune(edge);
			if(rawContainsKey && collectedType != null && collectedType != rawType)
				throw failure(context.analysis(), parent.key(),
					ConstructionDisposition.UNSUPPORTED_ANCHOR_METADATA, "FTYPE_MISMATCH");
			FType effectiveType = rawType;
			if(!rawContainsKey) {
				if(edge.getRight() == FederatedOutput.FOUT)
					throw failure(context.analysis(), parent.key(),
						ConstructionDisposition.ANCHOR_METADATA_INCOMPLETE, "FOUT_METADATA_ABSENT");
				FType memoType = childPlan == null ? null : childPlan.getCpFoutTypeOrFType();
				if(collectedType != null && memoType != null && collectedType != memoType)
					throw failure(context.analysis(), parent.key(),
						ConstructionDisposition.UNSUPPORTED_ANCHOR_METADATA, "FTYPE_MISMATCH");
				effectiveType = memoType;
				if(effectiveType != null)
					effectiveMap.put(hop.getHopID(), effectiveType);
			}
			effectiveCollectedFTypes.set(i, effectiveType);
			int remaining = remainingPhysicalInputs.getOrDefault(occurrence.key(), 0);
			if(remaining > 0) {
				int filteredOrdinal = rawEntries.size();
				rawEntries.add(project(occurrence.key(), filteredOrdinal, rawContainsKey, rawType));
				promotedEntries.add(project(occurrence.key(), filteredOrdinal,
					effectiveType != null, effectiveType));
				boolean nativeFederatedFout = edge.getRight() == FederatedOutput.FOUT && childPlan != null
					&& childPlan.getExecType() == ExecType.FED && !childPlan.isDerivedFedFout()
					&& context.analysis().graph().node(occurrence.key()).stream()
						.flatMap(node -> node.legalAlternatives().stream())
						.anyMatch(state -> state.execType() == ExecType.FED
							&& state.output() == FederatedOutput.FOUT
							&& state.fType() == childPlan.getFType());
				rawOrderOracleStates.add(nativeFederatedFout && childPlan.getFType() != null
					? oracleState(true, childPlan.getFType()) : OracleInputState.ABSENT_LOCAL);
				if(remaining == 1)
					remainingPhysicalInputs.remove(occurrence.key());
				else
					remainingPhysicalInputs.put(occurrence.key(), remaining - 1);
			}
		}
		if(!remainingPhysicalInputs.isEmpty())
			throw failure(context.analysis(), parent.key(), ConstructionDisposition.REORDERED_EDGE,
				"CANDIDATE_DIRECT_INPUT_MISSING");
		orderedOracleInputs.addAll(orderedOracleInputs(
			context, parent.key(), rawEntries, rawOrderOracleStates));

		CandidateOccurrenceSnapshot snapshot = new CandidateOccurrenceSnapshot(context, parent.key(), rawEntries,
			promotedEntries, orderedOracleInputs, ConstructionDisposition.AVAILABLE, "AVAILABLE");
		return new NormalizedCandidateInputs(snapshot, effectiveMap, effectiveCollectedFTypes, collectedHops);
	}

	public static CandidateDecisionReceipt resolveCandidateDecision(NeutralEnumerationContext context,
		NormalizedCandidateInputs normalizedCandidateInputs, long variantOrdinal) {
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(normalizedCandidateInputs, "normalizedCandidateInputs");
		CandidateOccurrenceSnapshot snapshot = normalizedCandidateInputs.snapshot();
		if(snapshot.context() != context)
			throw failure(context.analysis(), snapshot.parentOccurrence(), ConstructionDisposition.FOREIGN_CONTEXT,
				"CANDIDATE_CONTEXT_FOREIGN");
		List<CandidateInputState> orderedInputs = new ArrayList<>(snapshot.orderedOracleInputs().size());
		for(OracleInputState input : snapshot.orderedOracleInputs())
			orderedInputs.add(input == OracleInputState.ABSENT_LOCAL ? CandidateInputState.absentLocal()
				: CandidateInputState.present(FType.valueOf(input.name())));

		Hop parentHop = context.analysis().hop(snapshot.parentOccurrence()).orElseThrow(() ->
			failure(context.analysis(), snapshot.parentOccurrence(), ConstructionDisposition.STALE_CONTEXT,
				"CANDIDATE_PARENT_STALE"));
		CapturedInvocationEvidence invocationEvidence = context.invocationEvidence().get(snapshot.parentOccurrence());
		Privacy privacy = context.privacy().get(snapshot.parentOccurrence());
		if(invocationEvidence == null || privacy == null)
			throw failure(context.analysis(), snapshot.parentOccurrence(), ConstructionDisposition.STALE_CONTEXT,
				"CANDIDATE_EVIDENCE_STALE");
		CapturedResolution resolved = PlacementCandidateRuleResolver.resolveCaptured(new CapturedResolutionRequest(
			context.analysis(), context.analysisFingerprint(), snapshot.parentOccurrence(), orderedInputs,
			invocationEvidence));
		CandidateCapabilityFact caps = resolved.fact().capability();
		ExecPlacementPolicy.Decision placement = ExecPlacementPolicy.decideCaptured(
			new CapturedPlacementRequest(parentHop, privacy, resolved.logicalFType(), caps,
				normalizedCandidateInputs.effectiveNonNullFTypeMap(), context.analysis(),
				context.analysisFingerprint(), snapshot.parentOccurrence(), orderedInputs,
				resolved.fact(), invocationEvidence, variantOrdinal));
		Map<CandidatePlacementArm, PlacementState> catalog = new LinkedHashMap<>();
		NeutralPlacementGraph.Node node = context.analysis().graph().node(snapshot.parentOccurrence()).orElseThrow();
		for(PlacementState state : node.legalAlternatives()) {
			boolean allowed = state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT
				? placement.allowCP_LOUT : state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT
				? placement.allowCP_FOUT : state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT
				? placement.allowFED_LOUT : placement.allowFED_FOUT;
			if(allowed && catalog.putIfAbsent(new CandidatePlacementArm(state.execType(), state.output()), state) != null)
				throw new IllegalArgumentException("Candidate arm maps to multiple exact legal states");
		}
		return new CandidateDecisionReceipt(context, snapshot, variantOrdinal, snapshot.orderedOracleInputs(),
			caps.nativeExec(), caps.nativeOutput(), caps.nativeFoutFType(), resolved.logicalFType(),
			caps.reasonCode(), ConstructionDisposition.AVAILABLE, invocationEvidence, privacy,
			catalog.containsKey(new CandidatePlacementArm(ExecType.CP, FederatedOutput.LOUT)),
			catalog.containsKey(new CandidatePlacementArm(ExecType.CP, FederatedOutput.FOUT)),
			catalog.containsKey(new CandidatePlacementArm(ExecType.FED, FederatedOutput.LOUT)),
			catalog.containsKey(new CandidatePlacementArm(ExecType.FED, FederatedOutput.FOUT)),
			caps, catalog);
	}

	public static NeutralEnumerationContext captureNeutralEnumerationContext(PlacementAnalysis analysis,
		RewireOccurrenceSnapshot rewireSnapshot, int numWorkers, Map<Long, Privacy> privacyByHop,
		Set<Long> terminalTransientWriteHopIds) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(rewireSnapshot, "rewireSnapshot");
		Objects.requireNonNull(privacyByHop, "privacyByHop");
		Objects.requireNonNull(terminalTransientWriteHopIds, "terminalTransientWriteHopIds");
		Set<CompiledHopKey> forwardWrites = Collections.newSetFromMap(new IdentityHashMap<>());
		for(RewireTransientForwardEdge forward : rewireSnapshot.transientForwardEdges())
			forwardWrites.add(forward.writeOccurrence());
		Set<CompiledHopKey> terminalTransientWrites = Collections.newSetFromMap(new IdentityHashMap<>());
		for(HopOccurrenceProjection occurrence : rewireSnapshot.candidateOccurrences()) {
			if(!terminalTransientWriteHopIds.contains(occurrence.hop().getHopID()))
				continue;
			if(forwardWrites.contains(occurrence.key()))
				throw failure(analysis, occurrence.key(), ConstructionDisposition.STALE_CONTEXT,
					"TERMINAL_TRANSIENT_FORWARD_CONFLICT");
			terminalTransientWrites.add(occurrence.key());
		}
		for(HopOccurrenceProjection root : rewireSnapshot.additionalRoots()) {
			if(consumerKind(analysis, root.key()) != ConsumerNodeKind.TRANSIENT_WRITE)
				continue;
			if(!forwardWrites.contains(root.key()))
				terminalTransientWrites.add(root.key());
		}
		Map<CompiledHopKey, CapturedInvocationEvidence> invocations = new IdentityHashMap<>();
		Map<CompiledHopKey, Privacy> privacy = new IdentityHashMap<>();
		for(HopOccurrenceProjection occurrence : rewireSnapshot.candidateOccurrences()) {
			Hop hop = occurrence.hop();
			InvocationEvidence projection = invocationEvidence(hop, numWorkers);
			invocations.put(occurrence.key(), occurrenceInvocationEvidence(
				analysis, rewireSnapshot, occurrence.key(), projection, terminalTransientWrites));
			privacy.put(occurrence.key(), privacyByHop.getOrDefault(hop.getHopID(), Privacy.PUBLIC));
		}
		return new NeutralEnumerationContext(analysis, rewireSnapshot, analysis.analysisFingerprint(),
			Math.max(1, numWorkers), invocations, privacy);
	}

	private static List<OracleInputState> orderedOracleInputs(NeutralEnumerationContext context,
		CompiledHopKey parentOccurrence, List<CandidateMapEntry> rawEntries,
		List<OracleInputState> rawOrderOracleStates) {
		if(rawEntries.size() != rawOrderOracleStates.size())
			throw failure(context.analysis(), parentOccurrence, ConstructionDisposition.REORDERED_EDGE,
				"CANDIDATE_ORACLE_INPUT_ARITY");
		Hop parent = context.analysis().hop(parentOccurrence).orElseThrow(() ->
			failure(context.analysis(), parentOccurrence, ConstructionDisposition.STALE_CONTEXT,
				"CANDIDATE_PARENT_STALE"));
		boolean[] consumed = new boolean[rawEntries.size()];
		List<OracleInputState> ordered = new ArrayList<>(parent.getInput().size());
		for(Hop input : parent.getInput()) {
			HopOccurrenceProjection occurrence = context.rewireSnapshot().projectExactCarrier(input);
			if(occurrence == null)
				throw failure(context.analysis(), parentOccurrence, ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"CANDIDATE_DIRECT_INPUT_UNMAPPABLE");
			int match = -1;
			for(int i = 0; i < rawEntries.size(); i++) {
				if(!consumed[i] && rawEntries.get(i).occurrence() == occurrence.key()) {
					match = i;
					break;
				}
			}
			if(match < 0)
				throw failure(context.analysis(), parentOccurrence, ConstructionDisposition.REORDERED_EDGE,
					"CANDIDATE_DIRECT_INPUT_MISSING");
			consumed[match] = true;
			ordered.add(rawOrderOracleStates.get(match));
		}
		for(int i = 0; i < rawEntries.size(); i++)
			if(!consumed[i])
				throw failure(context.analysis(), parentOccurrence, ConstructionDisposition.REORDERED_EDGE,
					"CANDIDATE_DIRECT_INPUT_EXTRA");
		return List.copyOf(ordered);
	}

	private static CapturedInvocationEvidence occurrenceInvocationEvidence(PlacementAnalysis analysis,
		RewireOccurrenceSnapshot snapshot, CompiledHopKey parentOccurrence, InvocationEvidence projection,
		Set<CompiledHopKey> terminalTransientWrites) {
		if(!analysis.graph().node(parentOccurrence).orElseThrow(() ->
			failure(analysis, parentOccurrence, ConstructionDisposition.STALE_CONTEXT,
				"CANDIDATE_PARENT_STALE")).emittedWork())
			return new CapturedInvocationEvidence(projection, List.of(), List.of());
		Set<RewireConsumerEdge> retainedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<RewireTransientForwardEdge> retainedForwards = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<CompiledHopKey> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		collectOccurrenceEvidence(analysis, snapshot, parentOccurrence, parentOccurrence,
			retainedEdges, retainedForwards, visited, terminalTransientWrites);

		List<ConsumerEdgeEvidence> edges = new ArrayList<>();
		for(RewireConsumerEdge edge : snapshot.consumerEdges()) {
			if(retainedEdges.contains(edge))
				edges.add(new ConsumerEdgeEvidence(edges.size(), edge.parentOccurrence(), edge.childOccurrence(),
					edge.inputPosition(), terminalTransientWrites.contains(edge.parentOccurrence())
						? ConsumerNodeKind.TERMINAL_TRANSIENT_WRITE
						: consumerKind(analysis, edge.parentOccurrence())));
		}
		List<TransientForwardEvidence> forwards = new ArrayList<>();
		for(RewireTransientForwardEdge edge : snapshot.transientForwardEdges()) {
			if(retainedForwards.contains(edge))
				forwards.add(new TransientForwardEvidence(forwards.size(),
					edge.writeOccurrence(), edge.readOccurrence()));
		}
		return new CapturedInvocationEvidence(projection, edges, forwards);
	}

	private static void collectOccurrenceEvidence(PlacementAnalysis analysis, RewireOccurrenceSnapshot snapshot,
		CompiledHopKey rootOccurrence, CompiledHopKey producerOccurrence,
		Set<RewireConsumerEdge> retainedEdges, Set<RewireTransientForwardEdge> retainedForwards,
		Set<CompiledHopKey> visited, Set<CompiledHopKey> terminalTransientWrites) {
		if(!visited.add(producerOccurrence))
			return;
		for(RewireConsumerEdge edge : snapshot.consumerEdges()) {
			if(edge.childOccurrence() != producerOccurrence)
				continue;
			retainedEdges.add(edge);
			ConsumerNodeKind kind = consumerKind(analysis, edge.parentOccurrence());
			if(kind == ConsumerNodeKind.NORMAL)
				continue;
			if(kind == ConsumerNodeKind.TRANSIENT_READ) {
				collectOccurrenceEvidence(analysis, snapshot, rootOccurrence, edge.parentOccurrence(),
					retainedEdges, retainedForwards, visited, terminalTransientWrites);
				continue;
			}
			if(terminalTransientWrites.contains(edge.parentOccurrence()))
				continue;
			List<RewireTransientForwardEdge> matches = snapshot.transientForwardEdges().stream()
				.filter(forward -> forward.writeOccurrence() == edge.parentOccurrence()).toList();
			if(matches.isEmpty())
				throw failure(analysis, rootOccurrence, ConstructionDisposition.STALE_CONTEXT,
					"AMBIGUOUS_TRANSIENT_FORWARD");
			for(RewireTransientForwardEdge forward : matches) {
				retainedForwards.add(forward);
				collectOccurrenceEvidence(analysis, snapshot, rootOccurrence, forward.readOccurrence(),
					retainedEdges, retainedForwards, visited, terminalTransientWrites);
			}
		}
	}

	private static InvocationEvidence invocationEvidence(Hop hop, int numWorkers) {
		boolean multiReturn = hop instanceof FunctionOp
			&& ((FunctionOp)hop).getFunctionType() == FunctionOp.FunctionType.MULTIRETURN_BUILTIN;
		FType fedInitType = null;
		if(hop instanceof DataOp && ((DataOp)hop).getOp() == Types.OpOpData.FEDERATED)
			fedInitType = FederatedPlannerUtils.deriveFedInitFType((DataOp)hop);
		return new InvocationEvidence(multiReturn, hop.getDataType() != null && hop.getDataType().isMatrix(),
			FederatedPlannerUtils.isScalarLikeMatrix(hop), FederatedPlannerUtils.isVectorShape(hop),
			hop.getDim1(), hop.getDim2(), fedInitType,
			hop instanceof DataOp && ((DataOp)hop).getOp() == Types.OpOpData.TRANSIENTREAD,
			FederatedPlannerUtils.getVectorAxis(hop) != null && hasConsumerAxisMismatch(hop),
			hasConsumerAxisLengthMismatch(hop, FType.ROW), hasConsumerAxisLengthMismatch(hop, FType.COL),
			null, Math.max(1, numWorkers));
	}

	private static boolean hasConsumerAxisMismatch(Hop hop) {
		FType axis = FederatedPlannerUtils.getVectorAxis(hop);
		if(axis == null) return false;
		for(Hop parent : hop.getParent()) {
			FType parentAxis = FederatedPlannerUtils.getVectorAxis(parent);
			if(parentAxis != null && parentAxis != axis) return true;
		}
		return false;
	}

	private static boolean hasConsumerAxisLengthMismatch(Hop hop, FType axis) {
		long length = axis == FType.ROW ? hop.getDim1() : hop.getDim2();
		if(length <= 0) return false;
		for(Hop parent : hop.getParent()) {
			long parentLength = axis == FType.ROW ? parent.getDim1() : parent.getDim2();
			if(parentLength > 0 && parentLength != length) return true;
		}
		return false;
	}

	private static <V> Map<CompiledHopKey, V> immutableIdentityMap(Map<CompiledHopKey, V> source, String name) {
		Objects.requireNonNull(source, name);
		IdentityHashMap<CompiledHopKey, V> copy = new IdentityHashMap<>();
		for(Map.Entry<CompiledHopKey, V> entry : source.entrySet())
			copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
				Objects.requireNonNull(entry.getValue(), name + " value"));
		return Collections.unmodifiableMap(copy);
	}

	private static ConsumerNodeKind consumerKind(PlacementAnalysis analysis, CompiledHopKey key) {
		Hop hop = analysis.hop(key).orElseThrow(() -> new IllegalArgumentException("Consumer occurrence is stale"));
		if(hop instanceof DataOp && ((DataOp)hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
			return ConsumerNodeKind.TRANSIENT_WRITE;
		if(hop instanceof DataOp && ((DataOp)hop).getOp() == Types.OpOpData.TRANSIENTREAD)
			return ConsumerNodeKind.TRANSIENT_READ;
		return ConsumerNodeKind.NORMAL;
	}

	private static void validateContextCandidateInputs(NeutralEnumerationContext context,
		HopOccurrenceProjection parent, List<Pair<Long, FederatedOutput>> planChilds, List<Hop> collectedHops,
		List<FType> collectedFTypes, Map<Long, FType> fedInputTypeMap, FederatedPlannerDpMemoTable memo) {
		Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(planChilds, "planChilds");
		Objects.requireNonNull(collectedHops, "collectedHops");
		Objects.requireNonNull(collectedFTypes, "collectedFTypes");
		Objects.requireNonNull(fedInputTypeMap, "fedInputTypeMap");
		Objects.requireNonNull(memo, "memo");
		PlacementAnalysis analysis = context.analysis();
		if(analysis.occurrences().stream().noneMatch(candidate -> candidate == parent)
			|| !analysis.candidateRuleDomain().containsExactParent(parent.key()))
			throw failure(analysis, parent.key(), ConstructionDisposition.FOREIGN_CONTEXT, "FOREIGN_CONTEXT");
		if(planChilds.size() != collectedHops.size() || collectedHops.size() != collectedFTypes.size())
			throw failure(analysis, parent.key(), ConstructionDisposition.REORDERED_EDGE, "REORDERED_EDGE");
		for(int i = 0; i < collectedHops.size(); i++) {
			Pair<Long, FederatedOutput> edge = planChilds.get(i);
			Hop hop = collectedHops.get(i);
			if(edge == null || edge.getLeft() == null || edge.getRight() == null || hop == null)
				throw failure(analysis, parent.key(), ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"UNMAPPABLE_OCCURRENCE");
			if(edge.getLeft() != hop.getHopID())
				throw failure(analysis, parent.key(), ConstructionDisposition.REORDERED_EDGE, "REORDERED_EDGE");
			HopOccurrenceProjection occurrence = context.rewireSnapshot().projectExactCarrier(hop);
			if(occurrence == null)
				throw failure(analysis, parent.key(), ConstructionDisposition.UNMAPPABLE_OCCURRENCE,
					"UNMAPPABLE_OCCURRENCE");
			if(analysis.occurrences().stream().noneMatch(candidate -> candidate == occurrence))
				throw failure(analysis, parent.key(), ConstructionDisposition.FOREIGN_CONTEXT, "FOREIGN_CONTEXT");
			if(fedInputTypeMap.containsKey(hop.getHopID()) && fedInputTypeMap.get(hop.getHopID()) == null)
				throw failure(analysis, parent.key(), ConstructionDisposition.ANCHOR_METADATA_INCOMPLETE,
					"PRESENT_NULL");
		}
	}

	private static CandidateMapEntry project(CompiledHopKey occurrence, int edgePosition,
		boolean mapContainsKey, FType type) {
		return new CandidateMapEntry(occurrence, edgePosition, mapContainsKey, type,
			mapState(mapContainsKey, type), oracleState(mapContainsKey, type));
	}

	private static MapEntryState mapState(boolean mapContainsKey, FType type) {
		if(!mapContainsKey)
			return MapEntryState.ABSENT_LOCAL;
		if(type == null)
			return MapEntryState.PRESENT_NULL;
		return MapEntryState.valueOf("PRESENT_" + type.name());
	}

	private static OracleInputState oracleState(boolean mapContainsKey, FType type) {
		if(!mapContainsKey)
			return OracleInputState.ABSENT_LOCAL;
		return type == null ? null : OracleInputState.valueOf(type.name());
	}

	private static boolean ownsCandidateKey(PlacementAnalysis analysis, CompiledHopKey key) {
		return analysis.candidateRuleDomain().containsExactParent(key);
	}

	private static DpSemanticConstructionException failure(PlacementAnalysis analysis,
		CompiledHopKey parentOccurrence, ConstructionDisposition disposition, String reasonCode) {
		return new DpSemanticConstructionException(disposition, analysis.analysisFingerprint(), parentOccurrence,
			reasonCode);
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
