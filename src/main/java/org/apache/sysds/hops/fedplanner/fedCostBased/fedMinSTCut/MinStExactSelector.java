/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationEndpointFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactCostFacts.ObligationFact;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStExactSelection.ObligationReceipt;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Public shadow-mode exact selector over immutable {@link MinStExactCostFacts}. */
public final class MinStExactSelector {
	public MinStExactSelector() { }

	public static MinStExactSelection select(MinStExactCostFacts facts) {
		Objects.requireNonNull(facts, "facts");
		MinStExactCutSolver.Result solved = MinStExactCutSolver.solve(facts.sourceNodeId(),
			facts.sinkNodeId(), decisions(facts), freeNonDecisionNodes(facts), edges(facts));
		List<List<Long>> minima = solved.minima().stream()
			.map(MinStExactCutSolver.Minimum::sourceNodeIds)
			.sorted(lexicographicLongLists()).toList();
		if(!solved.unique())
			return new MinStExactSelection(solved.objectiveBits(), List.of(), List.of(), List.of(),
				MinStExactSelection.TIE_UNSPECIFIED, minima);
		List<Long> source = solved.minima().get(0).sourceNodeIds();
		return new MinStExactSelection(solved.objectiveBits(), source, selectedStates(facts, source),
			selectedObligations(facts, source), MinStExactSelection.UNIQUE, minima);
	}

	private static List<MinStExactCutSolver.Decision> decisions(MinStExactCostFacts facts) {
		List<MinStExactCutSolver.Decision> result = new ArrayList<>();
		for(DecisionFact decision : facts.decisionFactsInScopeOrder()) {
			List<MinStExactCutSolver.Choice> choices = new ArrayList<>();
			for(PlacementState state : uniqueStatesByCutMembership(decision)) {
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

	private static List<PlacementState> uniqueStatesByCutMembership(DecisionFact decision) {
		Map<String,PlacementState> statesByMembership = new HashMap<>();
		for(PlacementState state : decision.legalStatesInCanonicalOrder()) {
			String membership = state.execType().name() + '/' + state.output().name();
			PlacementState previous = statesByMembership.get(membership);
			if(previous == null)
				statesByMembership.put(membership, state);
			else if(!previous.equals(state))
				throw new IllegalArgumentException("MINST_EXACT_STATE_MEMBERSHIP_AMBIGUOUS|key="
					+ decision.key().normalizedSignature() + "|membership=" + membership);
		}
		return decision.legalStatesInCanonicalOrder().stream().distinct().toList();
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
			List<PlacementState> matches = uniqueStatesByCutMembership(decision).stream()
				.filter(state -> state.execType() == exec && state.output() == output).toList();
			if(matches.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_SELECTED_STATE_NOT_LEGAL|key="
					+ decision.key().normalizedSignature() + "|exec=" + exec + "|output=" + output);
			states.add(matches.get(0));
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
			if(group.direction() == Direction.UPLOAD && auxSource && !producerPlacementSource)
				addGroupReceipts(receipts, facts, group);
			if(group.direction() == Direction.DOWNLOAD && producerPlacementSource && !auxSource)
				addGroupReceipts(receipts, facts, group);
		}
		return receipts.stream().sorted(receiptComparator()).toList();
	}

	private static void addGroupReceipts(List<ObligationReceipt> receipts,
		MinStExactCostFacts facts, AuxiliaryGroupFact group) {
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			List<ObligationReceipt> matches = new ArrayList<>();
			for(ObligationFact obligation : facts.obligationFactsInCanonicalOrder())
				for(ObligationEndpointFact candidate : obligation.endpointsInCanonicalOrder()) {
					if(!candidate.consumerKey().equals(endpoint.consumerKey())
						|| candidate.inputPosition() != endpoint.inputPosition())
						continue;
					NeutralPlacementGraph.RelocationAction action = exactActionForSignature(facts, group,
						obligation.actionSignature());
					if(authorizesExactEndpoint(action, group, endpoint, candidate))
						matches.add(new ObligationReceipt(group.direction(), group.producerKey(),
							endpoint.consumerKey(), endpoint.inputPosition(), candidate.requiredPlacement(),
							obligation.actionSignature()));
				}
			if(matches.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_OBLIGATION_AUTHORITY_"
					+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS")
					+ "|direction=" + group.direction()
					+ "|producer=" + group.producerKey().normalizedSignature()
					+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
					+ "|input=" + endpoint.inputPosition());
			receipts.add(matches.get(0));
		}
	}

	private static NeutralPlacementGraph.RelocationAction exactActionForSignature(
		MinStExactCostFacts facts, AuxiliaryGroupFact group, String actionSignature) {
		List<NeutralPlacementGraph.RelocationAction> actions = facts.analysis().graph()
			.relocationActions().stream()
			.filter(action -> action.normalizedSignature().equals(actionSignature))
			.toList();
		if(actions.size() != 1)
			throw new IllegalArgumentException("MINST_EXACT_AUTHORITY_ACTION_"
				+ (actions.isEmpty() ? "MISSING" : "AMBIGUOUS")
				+ "|producer=" + group.producerKey().normalizedSignature()
				+ "|signature=" + actionSignature);
		NeutralPlacementGraph.RelocationAction action = actions.get(0);
		if(!facts.analysis().graph().node(group.producerKey()).orElseThrow().valueVersion()
			.equals(action.key().sourceValueVersion()))
			throw new IllegalArgumentException("MINST_EXACT_AUTHORITY_ACTION_SOURCE_MISMATCH|producer="
				+ group.producerKey().normalizedSignature() + "|signature=" + actionSignature);
		return action;
	}

	private static boolean authorizesExactEndpoint(NeutralPlacementGraph.RelocationAction action,
		AuxiliaryGroupFact group, EndpointFact endpoint, ObligationEndpointFact candidate) {
		if(!candidate.consumerKey().equals(endpoint.consumerKey())
			|| candidate.inputPosition() != endpoint.inputPosition())
			return false;
		for(ObligationKey obligation : action.obligations())
			if(obligation.sourceValueVersion().equals(action.key().sourceValueVersion())
				&& obligation.relocationAction().equals(action.key())
				&& obligation.consumer().equals(endpoint.consumerKey())
				&& obligation.inputPosition() == endpoint.inputPosition()
				&& obligation.requiredPlacement().equals(candidate.requiredPlacement())
				&& obligation.requiredPlacement().equals(action.key().targetPlacement())
				&& group.producerKey().equals(endpoint.producerKey()))
				return true;
		return false;
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
}
