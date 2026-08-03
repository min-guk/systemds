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
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipAuthorityKind;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityFact;
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
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
		completeSyntheticBoundaryStates(analysis, selectedStates);
		List<MinStPlacementInput.OccurrenceReceipt> occurrences = occurrenceReceipts(facts, analysis,
			selectedStates);
		List<MinStPlacementInput.ObligationReceipt> obligations = obligationReceipts(facts,
			selection);
		MinStPlacementInput.ProducerReceipt producer = new MinStPlacementInput.ProducerReceipt(
			facts.analysisFingerprint(), selection.objectiveBits(), completeSourcePartition(facts, selection));
		NormalizedPlannerResult normalized = normalizeExactSelection(facts, selection, selectedStates,
			producer);
		MinStPlacementInput input = MinStPlacementInput.createSelected(analysis, producer, occurrences,
			obligations, selectedStates, normalized);
		analysis.assertProgramStructureUnchanged();
		if(!facts.analysisFingerprint().equals(analysis.analysisFingerprint()))
			throw new IllegalArgumentException("MINST_PROJECTOR_ANALYSIS_FINGERPRINT_STALE");
		return input;
	}

	private static NormalizedPlannerResult normalizeExactSelection(MinStExactCostFacts facts,
		MinStExactSelection selection, IdentityHashMap<CompiledHopKey,PlacementState> selectedStates,
		MinStPlacementInput.ProducerReceipt producer) {
		PlacementAnalysis analysis = facts.analysis();
		Map<CompiledHopKey,PlacementEmissionState> emissions = new IdentityHashMap<>();
		selectedStates.forEach((key, state) -> emissions.put(key, new PlacementEmissionState(state, false)));

		Map<CompiledHopKey,MembershipRepresentative> selectedRepresentatives =
			selectedRepresentatives(facts, selectedStates);
		CandidateSelections.Selection canonical = CandidateSelections.selectNativeCanonical(
			analysis, analysis.graph().relocationActions(), selectedStates);
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : canonical.candidates())
			candidates.put(receipt.rule().parentOccurrence(), receipt);
		for(MembershipRepresentative representative : selectedRepresentatives.values()) {
			MembershipRepresentative candidateRepresentative = representative;
			CandidateEmissionFact exactEmission;
			if(representative.authorityKind() == MembershipAuthorityKind.CAPTURED_RULE)
				exactEmission = exactCandidateEmission(representative);
			else if(representative.authorityKind() == MembershipAuthorityKind.RELOCATION_SOURCE
				&& representative.execType() == org.apache.sysds.common.Types.ExecType.FED) {
				DecisionFact decision = facts.decisionFactsInScopeOrder().stream()
					.filter(candidate -> candidate.key() == representative.decisionKey())
					.findFirst().orElseThrow(() -> new IllegalArgumentException(
						"MINST_PROJECTOR_DERIVED_FOUT_DECISION_MISSING"));
				MinStExactCostFactsProducer.SelectedFedAuthority authority =
					MinStExactCostFactsProducer.selectedFedAuthority(analysis, decision,
						facts.membershipRepresentativesInCanonicalOrder(),
						facts.representativePreferences());
				if(authority.outputRepresentative() != representative
					|| !authority.derivedFedFout()
					|| authority.normalizedEmissionOrNull() == null)
					throw new IllegalArgumentException(
						"MINST_PROJECTOR_DERIVED_FOUT_AUTHORITY_IDENTITY_MISMATCH");
				candidateRepresentative = authority.executionRepresentative();
				exactEmission = authority.normalizedEmissionOrNull();
			}
			else
				continue;
			emissions.put(representative.decisionKey(), exactEmission.emissionState());
			candidates.put(representative.decisionKey(), new CandidateSelectionReceipt(
				candidateRepresentative.candidateRuleFactOrNull().key(), exactEmission, List.of()));
		}
		List<CandidateSelectionReceipt> candidateReceipts = candidates.values().stream().sorted().toList();
		CandidateSelections.resolveAndValidate(analysis, selectedStates, candidateReceipts);

		Map<RelocationDemandKey,RelocationActionKey> exactActions = exactSelectedRelocationActions(
			facts, selection);
		List<RelocationChoiceReceipt> relocationChoices = RelocationSelections.selectCanonical(
			analysis, analysis.graph().relocationActions(), selectedStates, candidateReceipts,
			(demand, action) -> !exactActions.containsKey(demand) || exactActions.get(demand).equals(action));
		Map<RelocationDemandKey,RelocationChoiceReceipt> choicesByDemand = new LinkedHashMap<>();
		for(RelocationChoiceReceipt choice : relocationChoices)
			choicesByDemand.put(choice.demand(), choice);
		for(Map.Entry<RelocationDemandKey,RelocationActionKey> exact : exactActions.entrySet()) {
			RelocationChoiceReceipt choice = choicesByDemand.get(exact.getKey());
			if(choice == null || !choice.action().equals(exact.getValue()))
				throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_RELOCATION_AUTHORITY_LOST|demand="
					+ exact.getKey().normalizedSignature());
		}
		return NormalizedPlannerResults.createWithEmissionStatesAndCandidateSelections(
			analysis, "MinST", emissions, candidateReceipts, relocationChoices,
			"cut=" + producer.cutObjectiveBits() + ";source=" + producer.sourcePartitionNodeIds());
	}

	private static Map<CompiledHopKey,MembershipRepresentative> selectedRepresentatives(
		MinStExactCostFacts facts, Map<CompiledHopKey,PlacementState> selectedStates) {
		Map<CompiledHopKey,MembershipRepresentative> result = new IdentityHashMap<>();
		for(MembershipRepresentative representative : facts.membershipRepresentativesInCanonicalOrder()) {
			if(selectedStates.get(representative.decisionKey()) != representative.state())
				continue;
			if(result.put(representative.decisionKey(), representative) != null)
				throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_REPRESENTATIVE_AMBIGUOUS|key="
					+ representative.decisionKey().normalizedSignature());
		}
		for(MinStExactCostFacts.DecisionFact decision : facts.decisionFactsInScopeOrder())
			if(!result.containsKey(decision.key()))
				throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_REPRESENTATIVE_MISSING|key="
					+ decision.key().normalizedSignature());
		return result;
	}

	private static CandidateEmissionFact exactCandidateEmission(MembershipRepresentative representative) {
		CandidateEmissionFact exact = representative.candidateEmissionFactOrNull();
		if(exact == null)
			throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_CANDIDATE_EMISSION_MISSING|key="
				+ representative.decisionKey().normalizedSignature());
		return exact;
	}

	private static Map<RelocationDemandKey,RelocationActionKey> exactSelectedRelocationActions(
		MinStExactCostFacts facts, MinStExactSelection selection) {
		Map<RelocationDemandKey,RelocationActionKey> result = new LinkedHashMap<>();
		for(MinStExactSelection.ObligationReceipt receipt : selection.obligationReceiptsInOrder()) {
			TransferAuthorityFact authority = exactSelectedTransferAuthority(facts,
				selection.sourcePartitionNodeIds(), receipt);
			if(authority.actionOrNull() == null)
				continue;
			RelocationDemandKey demand = RelocationDemandKey.from(authority.obligationOrNull());
			RelocationActionKey prior = result.putIfAbsent(demand, authority.actionOrNull().key());
			if(prior != null && !prior.equals(authority.actionOrNull().key()))
				throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_RELOCATION_AUTHORITY_AMBIGUOUS|demand="
					+ demand.normalizedSignature());
		}
		return result;
	}

	private static TransferAuthorityFact exactSelectedTransferAuthority(MinStExactCostFacts facts,
		List<Long> sourceNodeIds, MinStExactSelection.ObligationReceipt receipt) {
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		List<TransferAuthorityFact> matches = facts.transferAuthoritiesInCanonicalOrder().stream()
			.filter(authority -> authority.direction() == receipt.direction())
			.filter(authority -> authority.group().producerKey() == receipt.producerKey())
			.filter(authority -> authority.endpoint().consumerKey() == receipt.consumerKey())
			.filter(authority -> authority.endpoint().inputPosition() == receipt.inputPosition())
			.filter(authority -> authority.requiredPlacement() == receipt.requiredPlacement())
			.filter(authority -> authority.authoritySignature().equals(receipt.actionSignature()))
			.filter(authority -> selectedGroup(facts, source, authority.group()))
			.toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("MINST_PROJECTOR_SELECTED_TRANSFER_AUTHORITY_"
				+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS") + "|producer="
				+ receipt.producerKey().normalizedSignature() + "|consumer="
				+ receipt.consumerKey().normalizedSignature() + "|input=" + receipt.inputPosition());
		return matches.get(0);
	}

	private static List<Long> completeSourcePartition(MinStExactCostFacts facts,
		MinStExactSelection selection) {
		List<Long> selected = selection.sourcePartitionNodeIds();
		if(selected.contains(facts.sourceNodeId()) || selected.contains(facts.sinkNodeId()))
			throw new IllegalArgumentException("MINST_PROJECTOR_SELECTION_CONTAINS_TERMINAL");
		List<Long> complete = new ArrayList<>(selected.size() + 1);
		complete.add(facts.sourceNodeId());
		complete.addAll(selected);
		return complete.stream().sorted().toList();
	}

	private static void validateExactFacts(MinStExactCostFacts facts, PlacementAnalysis analysis) {
		if(analysis == null)
			throw new IllegalArgumentException("MINST_PROJECTOR_ANALYSIS_MISSING");
		if(!facts.analysisFingerprint().equals(analysis.analysisFingerprint()))
			throw new IllegalArgumentException("MINST_PROJECTOR_ANALYSIS_FINGERPRINT_STALE");
		MinStExactCostFactsProducer.validate(analysis, facts.analysisFingerprint(), facts.orderedScope(),
			facts.representativePreferences(),
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

	private static void completeSyntheticBoundaryStates(PlacementAnalysis analysis,
		IdentityHashMap<CompiledHopKey, PlacementState> selectedStates) {
		boolean progressed;
		do {
			progressed = false;
			for(NeutralPlacementGraph.Node node : analysis.graph().decisionNodes()) {
				if(selectedStates.containsKey(node.key()) || node.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
					&& node.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
					continue;
				List<CompiledHopKey> authorities = analysis.graph().constraints().stream()
					.filter(constraint -> constraint.kind() == NeutralPlacementGraph.ConstraintKind.CONJUNCTIVE
						&& constraint.right() == node.key())
					.map(constraint -> constraint.left()).toList();
				if(authorities.size() != 1)
					throw new IllegalArgumentException("MINST_PROJECTOR_BOUNDARY_AUTHORITY_CARDINALITY|key="
						+ node.key().normalizedSignature());
				PlacementState source = selectedStates.get(authorities.get(0));
				if(source == null)
					continue;
				if(node.legalAlternatives().stream().noneMatch(state -> state == source))
					throw new IllegalArgumentException("MINST_PROJECTOR_BOUNDARY_EXACT_STATE_IDENTITY|key="
						+ node.key().normalizedSignature());
				selectedStates.put(node.key(), source);
				progressed = true;
			}
		}
		while(progressed);
		for(NeutralPlacementGraph.Node node : analysis.graph().decisionNodes())
			if(!selectedStates.containsKey(node.key()))
				throw new IllegalArgumentException("MINST_PROJECTOR_TOTAL_DECISION_AUTHORITY|key="
					+ node.key().normalizedSignature());
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
				boolean semanticBoundary = node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
					|| node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT;
				if(!node.emittedWork() || !exactScope.contains(occurrence.key()) && !semanticBoundary)
					throw new IllegalArgumentException("MINST_PROJECTOR_UNSCOPED_STATE_PRESENT|key="
						+ occurrence.key().normalizedSignature());
				exec = state.execType();
				output = state.output();
			}
			else if(node.emittedWork())
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
				receipt.requiredPlacement, receipt.conversionType, receipt.actionSignature);
			groups.computeIfAbsent(key, ObligationGroup::new).add(receipt.consumerKey, receipt.consumerHopId);
		}
		List<MinStPlacementInput.ObligationReceipt> projected = new ArrayList<>(groups.size());
		for(ObligationGroup group : groups.values()) {
			Hop producer = facts.analysis().hop(group.key.producerKey).orElseThrow();
			FType fType = group.key.conversionType;
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
			if(!selectedGroup(facts, source, group))
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
					receipt.requiredPlacement(), group.conversionType(), receipt.actionSignature()));
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

	private static boolean selectedGroup(MinStExactCostFacts facts, Set<Long> source,
		AuxiliaryGroupFact group) {
		boolean auxSource = source.contains(group.auxiliaryNodeId());
		boolean producerPlacementSource = source.contains(group.producerPlacementNodeId());
		boolean compatibleProducerSource = group.direction() == Direction.UPLOAD
			&& MinStExactCostFactsProducer.isUploadReuseSelected(group, source);
		return group.direction() == Direction.UPLOAD && auxSource && !compatibleProducerSource
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
		MinStCompensatedCostSum total = new MinStCompensatedCostSum();
		for(MinStExactCostFacts.DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder())
			if(source.contains(edge.fromNodeId()) && !source.contains(edge.toNodeId()))
				total.addBits(edge.capacityBits(), "MINST_PROJECTOR_EDGE_CAPACITY_NOT_CANONICAL",
					"MINST_PROJECTOR_CUT_TOTAL_NOT_CANONICAL");
		return total.totalBits("MINST_PROJECTOR_CUT_TOTAL_NOT_CANONICAL");
	}

	private static String kind(Direction direction) {
		return direction == Direction.UPLOAD ? "U" : "D";
	}

	private static String reason(Direction direction) {
		return direction == Direction.UPLOAD ? UPLOAD_REASON : DOWNLOAD_REASON;
	}

	private record ValidatedObligation(Direction direction, CompiledHopKey producerKey,
		CompiledHopKey consumerKey, long consumerHopId, PlacementState requiredPlacement,
		FType conversionType, String actionSignature) { }

	private record ObligationGroupKey(Direction direction, CompiledHopKey producerKey,
		PlacementState requiredPlacement, FType conversionType, String actionSignature) {
		private ObligationGroupKey {
			Objects.requireNonNull(direction, "direction");
			Objects.requireNonNull(producerKey, "producerKey");
			Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			Objects.requireNonNull(conversionType, "conversionType");
			Objects.requireNonNull(actionSignature, "actionSignature");
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ObligationGroupKey that && direction == that.direction
				&& producerKey == that.producerKey && requiredPlacement == that.requiredPlacement
				&& conversionType == that.conversionType
				&& actionSignature.equals(that.actionSignature);
		}

		@Override
		public int hashCode() {
			return Objects.hash(direction, System.identityHashCode(producerKey),
				System.identityHashCode(requiredPlacement), conversionType, actionSignature);
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
