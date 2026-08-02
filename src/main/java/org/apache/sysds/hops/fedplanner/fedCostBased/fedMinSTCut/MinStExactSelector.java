/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
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
	/**
	 * Exhaustive enumeration is retained only for bounded complete tie certificates.
	 * The production objective is always the same directed min-cut; larger certificate
	 * spaces use Push-Relabel and publish the inclusion-minimal/maximal exact minima.
	 */
	private static final long MAX_EXHAUSTIVE_CERTIFICATE_PARTITIONS = 1_048_576L;

	public MinStExactSelector() { }

	public static MinStExactSelection select(MinStExactCostFacts facts) {
		Objects.requireNonNull(facts, "facts");
		MinStExactCostFactsProducer.validateMembershipRepresentatives(facts);
		List<MinStExactCutSolver.Decision> decisions = decisions(facts);
		List<Long> freeNodes = freeNonDecisionNodes(facts);
		List<MinStExactCutSolver.Edge> edges = edges(facts);
		MinStExactCutSolver.Result solved = usesPolynomialSolver(decisions, freeNodes)
			? MinStPolynomialCutSolver.solve(facts.sourceNodeId(), facts.sinkNodeId(), edges)
			: MinStExactCutSolver.solve(facts.sourceNodeId(), facts.sinkNodeId(), decisions,
				freeNodes, edges);
		List<List<Long>> rawMinima = solved.minima().stream()
			.map(MinStExactCutSolver.Minimum::sourceNodeIds)
			.sorted(lexicographicLongLists()).toList();
		List<Long> sourceReachableMinimum = sourceReachableMinimum(rawMinima);
		List<PlacementState> selectedStates = selectedStates(facts, sourceReachableMinimum);
		SemanticMinimum selected = new SemanticMinimum(sourceReachableMinimum, selectedStates,
			selectedObligations(facts, sourceReachableMinimum, selectedStates));
		traceSelection(facts, selected, solved.objectiveBits(),
			usesPolynomialSolver(decisions, freeNodes));
		return new MinStExactSelection(solved.objectiveBits(), selected.representativeSourceNodeIds(),
			selected.selectedStatesInScopeOrder(), selected.obligationReceiptsInOrder(),
			MinStExactSelection.UNIQUE, List.of(sourceReachableMinimum), rawMinima);
	}

	/**
	 * The intersection of all minimum s-t source partitions is the unique
	 * inclusion-minimal minimum cut. This is exactly the residual source-reachable
	 * partition returned by the legacy Push-Relabel MinST implementation. The
	 * polynomial solver publishes the minimum/maximum extrema, so the same
	 * intersection rule applies to both solver paths without changing the objective.
	 */
	private static List<Long> sourceReachableMinimum(List<List<Long>> rawMinima) {
		if(rawMinima.isEmpty())
			throw new IllegalArgumentException("MINST_EXACT_NO_MINIMUM_CERTIFICATE");
		Set<Long> intersection = new LinkedHashSet<>(rawMinima.get(0));
		for(int index = 1; index < rawMinima.size(); index++)
			intersection.retainAll(rawMinima.get(index));
		List<Long> canonical = intersection.stream().sorted().toList();
		if(!rawMinima.contains(canonical))
			throw new IllegalArgumentException(
				"MINST_EXACT_SOURCE_REACHABLE_MINIMUM_NOT_REPRESENTED|intersection=" + canonical);
		return canonical;
	}

	private static void traceSelection(MinStExactCostFacts facts, SemanticMinimum selected,
		long objectiveBits, boolean polynomial) {
		if(!FederatedPlannerTrace.isEnabled())
			return;
		long fed = selected.selectedStatesInScopeOrder().stream()
			.filter(state -> state.execType() == ExecType.FED).count();
		long fout = selected.selectedStatesInScopeOrder().stream()
			.filter(state -> state.output() == FederatedOutput.FOUT).count();
		FederatedPlannerTrace.logGlobal("MinST-ExactCut", "solver="
			+ (polynomial ? "PUSH_RELABEL" : "BOUNDED_ENUMERATION")
			+ ", objective=" + Double.longBitsToDouble(objectiveBits)
			+ ", decisions=" + facts.decisionFactsInScopeOrder().size()
			+ ", selectedFed=" + fed + ", selectedFout=" + fout
			+ ", sourcePartitionSize=" + selected.representativeSourceNodeIds().size());
		Set<Long> source = new LinkedHashSet<>(selected.representativeSourceNodeIds());
		for(int index = 0; index < facts.decisionFactsInScopeOrder().size(); index++) {
			DecisionFact decision = facts.decisionFactsInScopeOrder().get(index);
			Hop hop = facts.analysis().hop(decision.key()).orElse(null);
			if(!FederatedPlannerTrace.shouldTrace(hop))
				continue;
			PlacementState state = selected.selectedStatesInScopeOrder().get(index);
			FederatedPlannerTrace.log(hop, "MinST-ExactSelect", "selected="
				+ state.execType() + '/' + state.output()
				+ " side[c=" + (source.contains(decision.computeNodeId()) ? 'S' : 'T')
				+ ",p=" + (source.contains(decision.placementNodeId()) ? 'S' : 'T') + ']'
				+ " unary[CP=" + edgeCapacity(facts, facts.sourceNodeId(), decision.computeNodeId())
				+ ",FED=" + edgeCapacity(facts, decision.computeNodeId(), facts.sinkNodeId()) + ']'
				+ " conv[p->c=" + edgeCapacity(facts, decision.placementNodeId(), decision.computeNodeId())
				+ ",p->t=" + edgeCapacity(facts, decision.placementNodeId(), facts.sinkNodeId())
				+ ",c->p=" + edgeCapacity(facts, decision.computeNodeId(), decision.placementNodeId()) + ']'
				+ " legal=" + decision.legalStatesInCanonicalOrder().stream()
					.map(candidate -> candidate.execType() + "/" + candidate.output())
					.distinct().toList());
			FederatedPlannerTrace.log(hop, "MinST-ExactRules", "facts="
				+ facts.analysis().candidateRuleFacts().orderedFacts().stream()
					.filter(fact -> fact.key().parentOccurrence() == decision.key())
					.map(fact -> "inputs=" + fact.key().orderedInputs()
						+ ",status=" + fact.status()
						+ ",cap=" + (fact.capability() == null ? "-"
							: fact.capability().nativeExec() + "/" + fact.capability().nativeOutput())
						+ ",emissions=" + fact.allowedEmissionStates().stream()
							.map(emission -> emission.placementState().execType() + "/"
								+ emission.placementState().output() + ':'
								+ emission.placementState().fType())
							.toList())
					.toList());
		}
	}

	private static double edgeCapacity(MinStExactCostFacts facts, long from, long to) {
		return facts.directedEdgesInDerivationOrder().stream()
			.filter(edge -> edge.fromNodeId() == from && edge.toNodeId() == to)
			.mapToDouble(edge -> Double.longBitsToDouble(edge.capacityBits())).findFirst().orElse(0.0);
	}

	static boolean usesPolynomialSolver(MinStExactCostFacts facts) {
		Objects.requireNonNull(facts, "facts");
		return usesPolynomialSolver(decisions(facts), freeNonDecisionNodes(facts));
	}

	private static boolean usesPolynomialSolver(List<MinStExactCutSolver.Decision> decisions,
		List<Long> freeNodes) {
		long partitions = 1L;
		for(MinStExactCutSolver.Decision decision : decisions) {
			partitions = saturatingMultiply(partitions,
				decision.legalChoicesInCanonicalOrder().size());
			if(partitions > MAX_EXHAUSTIVE_CERTIFICATE_PARTITIONS)
				return true;
		}
		for(int index = 0; index < freeNodes.size(); index++) {
			partitions = saturatingMultiply(partitions, 2L);
			if(partitions > MAX_EXHAUSTIVE_CERTIFICATE_PARTITIONS)
				return true;
		}
		return false;
	}

	private static long saturatingMultiply(long left, long right) {
		if(left == 0L || right == 0L)
			return 0L;
		if(left > MAX_EXHAUSTIVE_CERTIFICATE_PARTITIONS / right)
			return MAX_EXHAUSTIVE_CERTIFICATE_PARTITIONS + 1L;
		return left * right;
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
		List<Long> sourceNodeIds, List<PlacementState> selectedStates) {
		Set<Long> source = new LinkedHashSet<>(sourceNodeIds);
		List<ObligationReceipt> receipts = new ArrayList<>();
		for(AuxiliaryGroupFact group : facts.auxiliaryGroupsInCanonicalOrder()) {
			boolean auxSource = source.contains(group.auxiliaryNodeId());
			boolean producerPlacementSource = source.contains(group.producerPlacementNodeId());
			boolean compatibleProducerSource = group.direction() == Direction.UPLOAD
				&& producerPlacementSource
				&& MinStExactCostFactsProducer.uploadPriceTargetsProducerPlacement(
					facts.analysis(), group);
			if(group.direction() == Direction.UPLOAD && auxSource && !compatibleProducerSource)
				addGroupReceipts(receipts, facts, group, selectedStates);
			if(group.direction() == Direction.DOWNLOAD && producerPlacementSource && !auxSource)
				addGroupReceipts(receipts, facts, group, selectedStates);
		}
		return receipts.stream().sorted(receiptComparator()).toList();
	}

	private static void addGroupReceipts(List<ObligationReceipt> receipts,
		MinStExactCostFacts facts, AuxiliaryGroupFact group,
		List<PlacementState> selectedStates) {
		PlacementState selectedProducer = selectedState(facts, selectedStates, group.producerKey());
		for(EndpointFact endpoint : group.endpointsInCanonicalOrder()) {
			PlacementState selectedConsumer = selectedState(facts, selectedStates, endpoint.consumerKey());
			List<TransferAuthorityFact> matches = facts.transferAuthoritiesInCanonicalOrder().stream()
				.filter(authority -> authority.group() == group && authority.endpoint() == endpoint)
				// One local input can have exact relocation actions for FED/LOUT and
				// FED/FOUT. The cut has now selected the consumer's full two-bit state,
				// so retain only the action whose obligation is active for that state.
					.filter(authority -> group.direction() != Direction.UPLOAD
						|| authority.actionOrNull() == null
						|| authority.requiredPlacement().equals(selectedConsumer))
					// A producer may own both CP/FOUT and FED/FOUT memberships backed by the
					// same durable anchor.  A DOWNLOAD receipt must retain the authority for
					// the exact execution membership selected by the cut, not both aliases.
					.filter(authority -> group.direction() != Direction.DOWNLOAD
						|| authority.requiredPlacement().equals(selectedProducer))
					.toList();
			if(matches.size() != 1)
				throw new IllegalArgumentException("MINST_EXACT_OBLIGATION_AUTHORITY_"
					+ (matches.isEmpty() ? "MISSING" : "AMBIGUOUS")
					+ "|direction=" + group.direction()
					+ "|producer=" + group.producerKey().normalizedSignature()
					+ "|consumer=" + endpoint.consumerKey().normalizedSignature()
					+ "|input=" + endpoint.inputPosition()
					+ "|selectedProducer=" + selectedProducer.normalizedSignature()
					+ "|selectedConsumer=" + selectedConsumer.normalizedSignature()
					+ "|available=" + facts.transferAuthoritiesInCanonicalOrder().stream()
						.filter(authority -> authority.group() == group && authority.endpoint() == endpoint)
						.map(authority -> authority.requiredPlacement().normalizedSignature()
							+ ':' + authority.authoritySignature()).toList());
			TransferAuthorityFact authority = matches.get(0);
			receipts.add(new ObligationReceipt(group.direction(), group.producerKey(),
				endpoint.consumerKey(), endpoint.inputPosition(), authority.requiredPlacement(),
				authority.authoritySignature()));
		}
	}

	private static PlacementState selectedState(MinStExactCostFacts facts,
		List<PlacementState> selectedStates, CompiledHopKey key) {
		List<DecisionFact> decisions = facts.decisionFactsInScopeOrder();
		if(decisions.size() != selectedStates.size())
			throw new IllegalArgumentException("MINST_EXACT_SELECTED_STATE_CARDINALITY_MISMATCH");
		for(int index = 0; index < decisions.size(); index++)
			if(decisions.get(index).key() == key)
				return selectedStates.get(index);
		throw new IllegalArgumentException("MINST_EXACT_SELECTED_STATE_MISSING|key="
			+ key.normalizedSignature());
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

}
