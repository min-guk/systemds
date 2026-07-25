/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.AuxiliaryGroupFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DecisionFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.DirectedEdgeFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.Direction;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.EndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.MembershipRepresentative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.TransferAuthorityFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection.ObligationReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Public shadow-mode exact selector over immutable {@link MinStExactCostFacts}. */
public final class MinStExactSelector {
	public MinStExactSelector() { }

	public static MinStExactSelection select(MinStExactCostFacts facts) {
		Objects.requireNonNull(facts, "facts");
		MinStExactCostFactsProducer.validateMembershipRepresentatives(facts);
		MinStExactCutSolver.Result solved = MinStExactCutSolver.solve(facts.sourceNodeId(),
			facts.sinkNodeId(), decisions(facts), freeNonDecisionNodes(facts), edges(facts));
		List<List<Long>> rawMinima = solved.minima().stream()
			.map(MinStExactCutSolver.Minimum::sourceNodeIds)
			.sorted(lexicographicLongLists()).toList();
		List<SemanticMinimum> semanticMinima = semanticMinima(facts, solved.objectiveBits(), rawMinima);
		List<List<Long>> semanticCertificates = semanticMinima.stream()
			.map(SemanticMinimum::representativeSourceNodeIds).toList();
		if(semanticMinima.size() != 1)
			return new MinStExactSelection(solved.objectiveBits(), List.of(), List.of(), List.of(),
				MinStExactSelection.TIE_UNSPECIFIED, semanticCertificates, rawMinima);
		SemanticMinimum selected = semanticMinima.get(0);
		return new MinStExactSelection(solved.objectiveBits(), selected.representativeSourceNodeIds(),
			selected.selectedStatesInScopeOrder(), selected.obligationReceiptsInOrder(),
			MinStExactSelection.UNIQUE, semanticCertificates, rawMinima);
	}

	private static List<SemanticMinimum> semanticMinima(MinStExactCostFacts facts, long objectiveBits,
		List<List<Long>> rawMinima) {
		Map<SemanticKey, SemanticMinimum> semantic = new LinkedHashMap<>();
		for(List<Long> raw : rawMinima) {
			List<PlacementState> states = selectedStates(facts, raw);
			List<ObligationReceipt> receipts = selectedObligations(facts, raw);
			SemanticKey key = new SemanticKey(objectiveBits, stateKeys(states), receiptKeys(receipts));
			semantic.computeIfAbsent(key, ignored -> new SemanticMinimum(raw, states, receipts));
		}
		return List.copyOf(semantic.values());
	}

	private static List<StateKey> stateKeys(List<PlacementState> states) {
		return states.stream().map(StateKey::new).toList();
	}

	private static List<ReceiptKey> receiptKeys(List<ObligationReceipt> receipts) {
		return receipts.stream().map(receipt -> new ReceiptKey(receipt.direction(), receipt.producerKey(),
			receipt.consumerKey(), receipt.inputPosition(), receipt.requiredPlacement(),
			receipt.actionSignature())).toList();
	}

	private static List<MinStExactCutSolver.Decision> decisions(MinStExactCostFacts facts) {
		List<MinStExactCutSolver.Decision> result = new ArrayList<>();
		for(DecisionFact decision : facts.decisionFactsInScopeOrder()) {
			List<MinStExactCutSolver.Choice> choices = new ArrayList<>();
			for(MembershipRepresentative representative : representatives(facts, decision)) {
				PlacementState state = representative.state();
				List<Long> nodes = new ArrayList<>(2);
				if(state.execType() == ExecType.FED)
					nodes.add(decision.computeNodeId());
				if(state.output() == FederatedOutput.FOUT)
					nodes.add(decision.placementNodeId());
				choices.add(new MinStExactCutSolver.Choice(nodes));
			}
			result.add(new MinStExactCutSolver.Decision(choices));
		}
		return List.copyOf(result);
	}

	private static List<MembershipRepresentative> representatives(MinStExactCostFacts facts,
		DecisionFact decision) {
		List<MembershipRepresentative> published = facts.membershipRepresentativesInCanonicalOrder();
		List<MembershipRepresentative> matches = published.stream()
			.filter(representative -> representative.decisionKey() == decision.key()).toList();
		Set<String> memberships = new LinkedHashSet<>();
		for(MembershipRepresentative representative : matches) {
			if(decision.legalStatesInCanonicalOrder().stream().noneMatch(state -> state == representative.state()))
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_STATE_NOT_RETAINED|key="
					+ decision.key().normalizedSignature());
			if(!memberships.add(membership(representative.execType(), representative.output())))
				throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_DUPLICATE|key="
					+ decision.key().normalizedSignature());
		}
		long expected = decision.legalStatesInCanonicalOrder().stream()
			.map(state -> membership(state.execType(), state.output())).distinct().count();
		if(matches.size() != expected)
			throw new IllegalArgumentException("MINST_EXACT_MEMBERSHIP_AUTHORITY_MISSING|key="
				+ decision.key().normalizedSignature());
		return matches;
	}

	private static List<MinStExactCutSolver.Edge> edges(MinStExactCostFacts facts) {
		return facts.directedEdgesInDerivationOrder().stream()
			.map(edge -> new MinStExactCutSolver.Edge(edge.fromNodeId(), edge.toNodeId(),
				edge.capacityBits()))
			.toList();
	}

	private static List<Long> freeNonDecisionNodes(MinStExactCostFacts facts) {
		Set<Long> decisionNodes = new LinkedHashSet<>();
		for(DecisionFact decision : facts.decisionFactsInScopeOrder()) {
			decisionNodes.add(decision.computeNodeId());
			decisionNodes.add(decision.placementNodeId());
		}
		Set<Long> nodes = new LinkedHashSet<>();
		for(DirectedEdgeFact edge : facts.directedEdgesInDerivationOrder()) {
			nodes.add(edge.fromNodeId());
			nodes.add(edge.toNodeId());
		}
		nodes.remove(facts.sourceNodeId());
		nodes.remove(facts.sinkNodeId());
		nodes.removeAll(decisionNodes);
		return nodes.stream().sorted().toList();
	}

	private static List<PlacementState> selectedStates(MinStExactCostFacts facts,
		List<Long> sourceNodeIds) {
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		List<PlacementState> states = new ArrayList<>(facts.decisionFactsInScopeOrder().size());
		for(DecisionFact decision : facts.decisionFactsInScopeOrder()) {
			ExecType exec = source.contains(decision.computeNodeId()) ? ExecType.FED : ExecType.CP;
			FederatedOutput output = source.contains(decision.placementNodeId())
				? FederatedOutput.FOUT : FederatedOutput.LOUT;
			List<MembershipRepresentative> matches = representatives(facts, decision).stream()
				.filter(representative -> representative.execType() == exec
					&& representative.output() == output).toList();
			if(matches.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_SELECTED_STATE_NOT_LEGAL|key="
					+ decision.key().normalizedSignature() + "|exec=" + exec + "|output=" + output);
			states.add(matches.get(0).state());
		}
		return List.copyOf(states);
	}

	private static List<ObligationReceipt> selectedObligations(MinStExactCostFacts facts,
		List<Long> sourceNodeIds) {
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		List<ObligationReceipt> receipts = new ArrayList<>();
		for(AuxiliaryGroupFact group : facts.auxiliaryGroupsInCanonicalOrder()) {
			boolean auxSource = source.contains(group.auxiliaryNodeId());
			boolean producerPlacementSource = source.contains(group.producerPlacementNodeId());
			boolean compatibleProducerSource = producerPlacementSource
				&& MinStExactCostFactsProducer.hasExactCompatibleDurableSource(facts.analysis(), group);
			if(group.direction() == Direction.UPLOAD && auxSource && !compatibleProducerSource)
				addGroupReceipts(receipts, facts, group);
			if(group.direction() == Direction.DOWNLOAD && producerPlacementSource && !auxSource)
				addGroupReceipts(receipts, facts, group);
		}
		return receipts.stream().sorted(receiptComparator()).toList();
	}

	private static void addGroupReceipts(List<ObligationReceipt> receipts,
		MinStExactCostFacts facts, AuxiliaryGroupFact group) {
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			List<TransferAuthorityFact> matches = facts.transferAuthoritiesInCanonicalOrder().stream()
				.filter(authority -> authority.group() == group && authority.endpoint() == endpoint)
				.toList();
			if(matches.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_OBLIGATION_AUTHORITY_"
					+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS")
					+ "|direction=" + group.direction()
					+ "|producer=" + group.producerKey().normalizedSignature()
					+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
					+ "|input=" + endpoint.inputPosition());
			TransferAuthorityFact authority = matches.get(0);
			receipts.add(new ObligationReceipt(group.direction(), group.producerKey(),
				endpoint.consumerKey(), endpoint.inputPosition(), authority.requiredPlacement(),
				authority.authoritySignature()));
		}
	}

	private static String membership(ExecType execType, FederatedOutput output) {
		return execType.name() + '/' + output.name();
	}

	private static Comparator<ObligationReceipt> receiptComparator() {
		return Comparator.comparing((ObligationReceipt receipt) -> receipt.direction().name())
			.thenComparing(receipt -> receipt.producerKey().normalizedSignature())
			.thenComparing(receipt -> receipt.consumerKey().normalizedSignature())
			.thenComparingInt(ObligationReceipt::inputPosition)
			.thenComparing(receipt -> receipt.requiredPlacement().normalizedSignature())
			.thenComparing(ObligationReceipt::actionSignature);
	}

	private static Comparator<List<Long>> lexicographicLongLists() {
		return (left, right) -> {
			int limit = Math.min(left.size(), right.size());
			for(int index = 0; index < limit; index++) {
				int comparison = Long.compare(left.get(index), right.get(index));
				if(comparison != 0)
					return comparison;
			}
			return Integer.compare(left.size(), right.size());
		};
	}

	private record SemanticMinimum(List<Long> representativeSourceNodeIds,
		List<PlacementState> selectedStatesInScopeOrder,
		List<ObligationReceipt> obligationReceiptsInOrder) {
		SemanticMinimum {
			representativeSourceNodeIds = List.copyOf(representativeSourceNodeIds);
			selectedStatesInScopeOrder = List.copyOf(selectedStatesInScopeOrder);
			obligationReceiptsInOrder = List.copyOf(obligationReceiptsInOrder);
		}
	}

	private record SemanticKey(long objectiveBits, List<StateKey> selectedStatesInScopeOrder,
		List<ReceiptKey> obligationReceiptsInOrder) {
		SemanticKey {
			selectedStatesInScopeOrder = List.copyOf(selectedStatesInScopeOrder);
			obligationReceiptsInOrder = List.copyOf(obligationReceiptsInOrder);
		}
	}

	private static final class StateKey {
		private final PlacementState state;

		private StateKey(PlacementState state) {
			this.state = Objects.requireNonNull(state, "state");
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof StateKey that && state == that.state;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(state);
		}
	}

	private static final class ReceiptKey {
		private final Direction direction;
		private final CompiledHopKey producerKey;
		private final CompiledHopKey consumerKey;
		private final int inputPosition;
		private final PlacementState requiredPlacement;
		private final String actionSignature;

		private ReceiptKey(Direction direction, CompiledHopKey producerKey, CompiledHopKey consumerKey,
			int inputPosition, PlacementState requiredPlacement, String actionSignature) {
			this.direction = Objects.requireNonNull(direction, "direction");
			this.producerKey = Objects.requireNonNull(producerKey, "producerKey");
			this.consumerKey = Objects.requireNonNull(consumerKey, "consumerKey");
			this.inputPosition = inputPosition;
			this.requiredPlacement = Objects.requireNonNull(requiredPlacement, "requiredPlacement");
			this.actionSignature = Objects.requireNonNull(actionSignature, "actionSignature");
		}

		@Override
		public boolean equals(Object other) {
			if(this == other) return true;
			if(!(other instanceof ReceiptKey that)) return false;
			return direction == that.direction
				&& producerKey == that.producerKey
				&& consumerKey == that.consumerKey
				&& inputPosition == that.inputPosition
				&& requiredPlacement == that.requiredPlacement
				&& actionSignature.equals(that.actionSignature);
		}

		@Override
		public int hashCode() {
			return Objects.hash(direction, System.identityHashCode(producerKey),
				System.identityHashCode(consumerKey), inputPosition,
				System.identityHashCode(requiredPlacement), actionSignature);
		}
	}
}
