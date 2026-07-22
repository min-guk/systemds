/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityFact;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Graph-free, fail-closed projection from exact MinST facts/selection into the placement carrier. */
public final class MinStExactPlacementProjector {
	private static final String EXACT_NEUTRAL_CAPABILITY = "proven by exact MinST transfer authority";
	private static final String UPLOAD_REASON = "CP/LOUT child has active FED consumers";
	private static final String DOWNLOAD_REASON = "FED/FOUT child has active LOCAL consumers";

	private MinStExactPlacementProjector() {
		// utility class
	}

	public static MinStPlacementInput project(MinStExactCostFacts facts,
		MinStExactSelection selection) {
		Objects.requireNonNull(facts, "facts");
		Objects.requireNonNull(selection, "selection");
		PlacementAnalysis analysis = facts.analysis();
		analysis.assertProgramStructureUnchanged();
		validateExactFacts(facts, analysis);
		validateSelection(facts, selection);

		IdentityHashMap<CompiledHopKey,PlacementState> selectedStates = selectedStatesByIdentity(facts,
			selection);
		List<MinStPlacementInput.OccurrenceReceipt> occurrences = occurrenceReceipts(facts, analysis,
			selectedStates);
		List<MinStPlacementInput.ObligationReceipt> obligations = obligationReceipts(facts,
			selection);
		MinStPlacementInput.ProducerReceipt producer = new MinStPlacementInput.ProducerReceipt(
			facts.analysisFingerprint(), selection.objectiveBits(), selection.sourcePartitionNodeIds());
		MinStPlacementInput input = MinStPlacementInput.create(analysis, producer, occurrences,
			obligations);
		analysis.assertProgramStructureUnchanged();
		if(!facts.analysisFingerprint().equals(analysis.analysisFingerprint()))
			throw new IllegalArgumentException("MINST_PROJECTOR_ANALYSIS_FINGERPRINT_STALE");
		return input;
	}

	private static void validateExactFacts(MinStExactCostFacts facts, PlacementAnalysis analysis) {
		if(analysis == null)
			throw new IllegalArgumentException("MINST_PROJECTOR_ANALYSIS_MISSING");
		if(!facts.analysisFingerprint().equals(analysis.analysisFingerprint()))
			throw new IllegalArgumentException("MINST_PROJECTOR_ANALYSIS_FINGERPRINT_STALE");
		MinStExactCostFactsProducer.validate(analysis, facts.analysisFingerprint(), facts.orderedScope(),
			facts.decisionFactsInScopeOrder(), facts.directedEdgesInDerivationOrder(),
			facts.auxiliaryGroupsInCanonicalOrder(), facts.transferAuthoritiesInCanonicalOrder(),
			facts.obligationFactsInCanonicalOrder(),
			facts.derivationFingerprint());
	}

	private static void validateSelection(MinStExactCostFacts facts, MinStExactSelection selection) {
		if(!MinStExactSelection.UNIQUE.equals(selection.tieCertificate()))
			throw new IllegalArgumentException("MINST_PROJECTOR_NON_UNIQUE_MINIMUM");
		if(selection.minimaCertificates().size() != 1)
			throw new IllegalArgumentException("MINST_PROJECTOR_MINIMA_CERTIFICATE_CARDINALITY");
		List<Long> source = selection.sourcePartitionNodeIds();
		if(!source.equals(source.stream().sorted().toList())
			|| new LinkedHashSet<>(source).size() != source.size())
			throw new IllegalArgumentException("MINST_PROJECTOR_SOURCE_CERTIFICATE_NOT_CANONICAL");
		if(!selection.minimaCertificates().get(0).equals(source))
			throw new IllegalArgumentException("MINST_PROJECTOR_MINIMA_CERTIFICATE_SOURCE_MISMATCH");
		if(selection.objectiveBits() != cutObjectiveBits(facts, source))
			throw new IllegalArgumentException("MINST_PROJECTOR_OBJECTIVE_MISMATCH");
	}

	private static IdentityHashMap<CompiledHopKey,PlacementState> selectedStatesByIdentity(
		MinStExactCostFacts facts, MinStExactSelection selection) {
		List<DecisionFact> decisions = facts.decisionFactsInScopeOrder();
		List<PlacementState> states = selection.selectedStatesInScopeOrder();
		if(states.size() != decisions.size())
			throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_STATE_CARDINALITY");
		List<CompiledHopKey> emittedScope = emittedScope(facts);
		if(decisions.size() != emittedScope.size())
			throw new IllegalArgumentException("MINST_PROJECTOR_EMITTED_DECISION_CARDINALITY");
		Set<Long> source = new LinkedHashSet<>(selection.sourcePartitionNodeIds());
		IdentityHashMap<CompiledHopKey,PlacementState> result = new IdentityHashMap<>();
		for(int i = 0; i < decisions.size(); i++) {
			DecisionFact decision = decisions.get(i);
			if(decision.key() != emittedScope.get(i))
				throw new IllegalArgumentException("MINST_PROJECTOR_SCOPE_DECISION_IDENTITY_MISMATCH");
			PlacementState selected = Objects.requireNonNull(states.get(i),
				"selectedStatesInScopeOrder[" + i + "]");
			long identityMatches = decision.legalStatesInCanonicalOrder().stream()
				.filter(candidate -> candidate == selected).count();
			if(identityMatches != 1)
				throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_STATE_NOT_EXACT_MEMBER|key="
					+ decision.key().normalizedSignature());
			ExecType expectedExec = source.contains(decision.computeNodeId()) ? ExecType.FED : ExecType.CP;
			FederatedOutput expectedOutput = source.contains(decision.placementNodeId())
				? FederatedOutput.FOUT : FederatedOutput.LOUT;
			if(selected.execType() != expectedExec || selected.output() != expectedOutput)
				throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_STATE_SOURCE_MISMATCH|key="
					+ decision.key().normalizedSignature());
			if(result.put(decision.key(), selected) != null)
				throw new IllegalArgumentException("MINST_PROJECTOR_DUPLICATE_DECISION_KEY");
		}
		return result;
	}

	private static List<CompiledHopKey> emittedScope(MinStExactCostFacts facts) {
		List<CompiledHopKey> emitted = new ArrayList<>();
		for(CompiledHopKey key : facts.orderedScope()) {
			NeutralPlacementGraph.Node node = facts.analysis().graph().node(key).orElseThrow();
			if(node.emittedWork())
				emitted.add(key);
		}
		return List.copyOf(emitted);
	}

	private static List<MinStPlacementInput.OccurrenceReceipt> occurrenceReceipts(
		MinStExactCostFacts facts, PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey,PlacementState> selectedStates) {
		Set<CompiledHopKey> exactScope = Collections.newSetFromMap(new IdentityHashMap<>());
		exactScope.addAll(facts.orderedScope());
		List<MinStPlacementInput.OccurrenceReceipt> receipts = new ArrayList<>(analysis.occurrences().size());
		for(PlacementAnalysis.HopOccurrenceProjection occurrence : analysis.occurrences()) {
			NeutralPlacementGraph.Node node = analysis.graph().node(occurrence.key()).orElseThrow();
			Hop hop = occurrence.hop();
			if(analysis.hop(occurrence.key()).orElseThrow() != hop)
				throw new IllegalArgumentException("MINST_PROJECTOR_OCCURRENCE_OWNER_MISMATCH");
			PlacementState state = selectedStates.get(occurrence.key());
			ExecType exec = null;
			FederatedOutput output = FederatedOutput.NONE;
			if(state != null) {
				if(!node.emittedWork() || !exactScope.contains(occurrence.key()))
					throw new IllegalArgumentException("MINST_PROJECTOR_UNSCOPED_STATE_PRESENT|key="
						+ occurrence.key().normalizedSignature());
				exec = state.execType();
				output = state.output();
			}
			else if(node.emittedWork() && exactScope.contains(occurrence.key()))
				throw new IllegalArgumentException("MINST_PROJECTOR_EMITTED_STATE_MISSING|key="
					+ occurrence.key().normalizedSignature());
			receipts.add(new MinStPlacementInput.OccurrenceReceipt(occurrence.key(), hop,
				hop.getHopID(), hop, hop.getHopID(), exec, output));
		}
		return List.copyOf(receipts);
	}

	private static List<MinStPlacementInput.ObligationReceipt> obligationReceipts(
		MinStExactCostFacts facts, MinStExactSelection selection) {
		List<ValidatedObligation> validated = new ArrayList<>();
		for(MinStExactSelection.ObligationReceipt receipt : selection.obligationReceiptsInOrder())
			validated.add(validateObligation(facts, selection.sourcePartitionNodeIds(), receipt));
		Map<ObligationGroupKey,ObligationGroup> groups = new LinkedHashMap<>();
		for(ValidatedObligation receipt : validated) {
			ObligationGroupKey key = new ObligationGroupKey(receipt.direction, receipt.producerKey,
				receipt.requiredPlacement, receipt.actionSignature);
			groups.computeIfAbsent(key, ObligationGroup::new).add(receipt.consumerKey, receipt.consumerHopId);
		}
		List<MinStPlacementInput.ObligationReceipt> projected = new ArrayList<>(groups.size());
		for(ObligationGroup group : groups.values()) {
			Hop producer = facts.analysis().hop(group.key.producerKey).orElseThrow();
			FType fType = group.key.requiredPlacement.fType();
			if(fType == null)
				throw new IllegalArgumentException("MINST_PROJECTOR_NONCONCRETE_FTYPE|producer="
					+ group.key.producerKey.normalizedSignature());
			projected.add(new MinStPlacementInput.ObligationReceipt(
				kind(group.key.direction), producer.getHopID(), producer.getHopID(),
				group.key.actionSignature, group.consumerHopIds(), fType, true,
				EXACT_NEUTRAL_CAPABILITY, reason(group.key.direction)));
		}
		return List.copyOf(projected);
	}

	private static ValidatedObligation validateObligation(MinStExactCostFacts facts,
		List<Long> sourceNodeIds, MinStExactSelection.ObligationReceipt receipt) {
		Objects.requireNonNull(receipt, "obligation receipt");
		if(receipt.requiredPlacement().fType() == null)
			throw new IllegalArgumentException("MINST_PROJECTOR_NONCONCRETE_FTYPE");
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		List<ValidatedObligation> matches = new ArrayList<>();
		for(AuxiliaryGroupFact group : facts.auxiliaryGroupsInCanonicalOrder()) {
			if(group.direction() != receipt.direction() || group.producerKey() != receipt.producerKey())
				continue;
			if(!selectedGroup(source, group))
				continue;
			for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
				if(endpoint.consumerKey() != receipt.consumerKey()
					|| endpoint.inputPosition() != receipt.inputPosition()
					|| endpoint.producerKey() != receipt.producerKey())
					continue;
				if(!authorizesTransferFact(facts, group, endpoint,
					receipt.requiredPlacement(), receipt.actionSignature()))
					continue;
				matches.add(new ValidatedObligation(receipt.direction(), receipt.producerKey(),
					receipt.consumerKey(), facts.analysis().hop(receipt.consumerKey()).orElseThrow().getHopID(),
					receipt.requiredPlacement(), receipt.actionSignature()));
			}
		}
		if(matches.size() != 1)
			throw new IllegalArgumentException("MINST_PROJECTOR_OBLIGATION_AUTHORITY_"
				+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS")
				+ "|producer=" + receipt.producerKey().normalizedSignature()
				+ "|consumer=" + receipt.consumerKey().normalizedSignature()
				+ "|input=" + receipt.inputPosition());
		return matches.get(0);
	}

	private static boolean selectedGroup(Set<Long> source, AuxiliaryGroupFact group) {
		boolean auxSource = source.contains(group.auxiliaryNodeId());
		boolean producerPlacementSource = source.contains(group.producerPlacementNodeId());
		return group.direction() == Direction.UPLOAD && auxSource && !producerPlacementSource
			|| group.direction() == Direction.DOWNLOAD && producerPlacementSource && !auxSource;
	}

	private static boolean authorizesTransferFact(MinStExactCostFacts facts,
		AuxiliaryGroupFact group, EndpointFact endpoint, PlacementState requiredPlacement,
		String authoritySignature) {
		List<TransferAuthorityFact> matches = facts.transferAuthoritiesInCanonicalOrder().stream()
			.filter(authority -> authority.group() == group && authority.endpoint() == endpoint)
			.filter(authority -> authority.requiredPlacement() == requiredPlacement)
			.filter(authority -> authority.authoritySignature().equals(authoritySignature))
			.toList();
		if(matches.size() > 1)
			throw new IllegalArgumentException("MINST_PROJECTOR_TRANSFER_AUTHORITY_AMBIGUOUS|producer="
				+ group.producerKey().normalizedSignature() + "|consumer="
				+ endpoint.consumerKey().normalizedSignature() + "|input=" + endpoint.inputPosition());
		return matches.size() == 1;
	}

	private static long cutObjectiveBits(MinStExactCostFacts facts, List<Long> sourceNodeIds) {
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		source.add(facts.sourceNodeId());
		double total = 0.0;
		for(MinStExactCostFacts.DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder())
			if(source.contains(edge.fromNodeId()) && !source.contains(edge.toNodeId()))
				total += Double.longBitsToDouble(edge.capacityBits());
		return Double.doubleToRawLongBits(total);
	}

	private static String kind(Direction direction) {
		return direction == Direction.UPLOAD ? "U" : "D";
	}

	private static String reason(Direction direction) {
		return direction == Direction.UPLOAD ? UPLOAD_REASON : DOWNLOAD_REASON;
	}

	private record ValidatedObligation(Direction direction, CompiledHopKey producerKey,
		CompiledHopKey consumerKey, long consumerHopId, PlacementState requiredPlacement,
		String actionSignature) { }

	private record ObligationGroupKey(Direction direction, CompiledHopKey producerKey,
		PlacementState requiredPlacement, String actionSignature) {
		private ObligationGroupKey {
			Objects.requireNonNull(direction, "direction");
			Objects.requireNonNull(producerKey, "producerKey");
			Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			Objects.requireNonNull(actionSignature, "actionSignature");
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ObligationGroupKey that && direction == that.direction
				&& producerKey == that.producerKey && requiredPlacement == that.requiredPlacement
				&& actionSignature.equals(that.actionSignature);
		}

		@Override
		public int hashCode() {
			return Objects.hash(direction, System.identityHashCode(producerKey),
				System.identityHashCode(requiredPlacement), actionSignature);
		}
	}

	private static final class ObligationGroup {
		private final ObligationGroupKey key;
		private final Set<CompiledHopKey> consumerKeys = Collections.newSetFromMap(new IdentityHashMap<>());
		private final LinkedHashSet<Long> consumerHopIds = new LinkedHashSet<>();

		private ObligationGroup(ObligationGroupKey key) {
			this.key = key;
		}

		private void add(CompiledHopKey consumerKey, long consumerHopId) {
			if(!consumerKeys.add(consumerKey)) {
				if(!consumerHopIds.contains(consumerHopId))
					throw new IllegalArgumentException("MINST_PROJECTOR_CONSUMER_IDENTITY_COLLISION");
				throw new IllegalArgumentException("MINST_PROJECTOR_DUPLICATE_OBLIGATION_ENDPOINT|consumer="
					+ consumerKey.normalizedSignature());
			}
			consumerHopIds.add(consumerHopId);
		}

		private List<Long> consumerHopIds() {
			return List.copyOf(consumerHopIds);
		}
	}
}
