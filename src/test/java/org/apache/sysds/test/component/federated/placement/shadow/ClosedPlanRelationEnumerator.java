/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;

/**
 * Test-only P-native exact relation enumerator. It removes only candidate combinations that
 * {@link CandidateSelections#resolveAndValidate} must reject for a complete placement assignment.
 * Every surviving candidate/relocation leaf is checked by all three production validators.
 * The compression proof is the validator's exact membership-and-coverage contract: each active
 * consumer must select exactly one row from feasibleVariants, and each inactive consumer must
 * select none. Thus all other raw candidate digits are rejected before relocation validation.
 * This proves equivalence to the P-native raw model, not completeness of runtime feasibility.
 */
public final class ClosedPlanRelationEnumerator {
	/**
	 * Ordered native acceptance path. The offline verifier can replay decision graph constraints,
	 * candidate privacy closure, candidate derived-FOUT ownership, and classification of captured
	 * non-decision candidate owners. Candidate, relocation, and non-decision-owner rows remain
	 * universes whose completeness is not established by the model artifact alone.
	 */
	static List<String> acceptancePredicateIds() {
		return List.of("DECISION_GRAPH_CONSTRAINTS",
			"CANDIDATE_RULE_STATUS_AND_PRIVACY_FILTERING",
			"CANDIDATE_FEASIBLE_VARIANTS_AND_SOURCE_REACHABILITY",
			"CANDIDATE_SELECTION_COVERAGE_AND_REALIZATION_COMPATIBILITY",
			"RELOCATION_ACTIVE_DEMAND_CONSTRUCTION",
			"RELOCATION_SELECTION_COVERAGE_AND_WORKER_POOL_COMPATIBILITY",
			"CANDIDATE_RELOCATION_REALIZATION_ALIGNMENT",
			"DERIVED_FOUT_GRAPH_OWNERSHIP",
			"UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
			"JAVA_VALIDATOR_EXCEPTION_CLASSIFICATION");
	}

	public record StateInspection(BigInteger stateOrdinal, BigInteger rawCandidateAssignments,
		BigInteger survivingCandidateAssignments, BigInteger rawRelocationAssignments,
		FullProductionJointPlanExport.Verdict earlyVerdict, String reason) { }

	public record Summary(BigInteger raw, BigInteger accepted, BigInteger rejected,
		BigInteger unknown, BigInteger candidatePruned) {
		public Summary {
			if(!raw.equals(accepted.add(rejected).add(unknown)))
				throw new IllegalArgumentException("Raw cardinality does not balance");
		}
	}

	/** Counts state assignments rejected by a lossless binary-constraint frontier. */
	public record FrontierResult(Summary summary, BigInteger prunedStates) { }

	private static final BigInteger ONE = BigInteger.ONE;
	private static final BigInteger ZERO = BigInteger.ZERO;
	private final PlacementAnalysis analysis;
	private final NeutralPlacementGraph graph;
	private final List<NeutralPlacementGraph.Node> nodes;
	private final List<IndexedConstraint> indexedConstraints;
	private final List<CompiledHopKey> candidateOwners;
	private final List<List<CandidateSelectionReceipt>> candidateDomains;
	private final List<List<RelocationChoiceReceipt>> relocationDomains;
	private final List<RelocationDemandKey> relocationDemandKeys;
	private final BigInteger stateCount;
	private final BigInteger candidateCount;
	private final BigInteger relocationCount;
	private final BigInteger rawCount;
	private final boolean unresolvedOwner;

	private record IndexedConstraint(NeutralPlacementGraph.Constraint constraint, int left, int right) { }

	public ClosedPlanRelationEnumerator(PlacementAnalysis analysis) {
		this.analysis = Objects.requireNonNull(analysis, "analysis");
		graph = analysis.graph();
		nodes = List.copyOf(graph.decisionNodes());
		Map<CompiledHopKey,Integer> nodePositions = new LinkedHashMap<>();
		for(int i = 0; i < nodes.size(); i++)
			nodePositions.put(nodes.get(i).key(), i);
		List<IndexedConstraint> indexed = new ArrayList<>();
		for(NeutralPlacementGraph.Constraint constraint : graph.constraints()) {
			Integer left = nodePositions.get(constraint.left());
			Integer right = nodePositions.get(constraint.right());
			if(left != null && right != null)
				indexed.add(new IndexedConstraint(constraint, left, right));
		}
		indexedConstraints = List.copyOf(indexed);
		Map<CompiledHopKey,Set<CandidateSelectionReceipt>> byOwner = new LinkedHashMap<>();
		for(NeutralPlacementGraph.Node node : nodes)
			byOwner.put(node.key(), new LinkedHashSet<>());
		Set<CompiledHopKey> foreignOwners = new LinkedHashSet<>();
		analysis.candidateRuleFacts().orderedFacts().forEach(fact -> {
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE) {
				CompiledHopKey owner = fact.key().parentOccurrence();
				Set<CandidateSelectionReceipt> rows = byOwner.get(owner);
				if(rows == null)
					foreignOwners.add(owner);
				else
					fact.allowedEmissionFacts().forEach(emission ->
						rows.addAll(analysis.canonicalCandidateReceipts(fact.key(), emission)));
			}
		});
		unresolvedOwner = foreignOwners.stream().anyMatch(owner -> {
			NeutralPlacementGraph.Node node = graph.node(owner).orElse(null);
			return node == null || node.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED
				|| node.emittedWork() || !node.legalAlternatives().isEmpty();
		});
		candidateOwners = List.copyOf(byOwner.keySet());
		candidateDomains = byOwner.values().stream().map(rows ->
			rows.stream().sorted(Comparator.comparing(CandidateSelectionReceipt::normalizedSignature)).toList())
			.toList();
		Map<RelocationDemandKey,Set<RelocationChoiceReceipt>> byDemand = new LinkedHashMap<>();
		for(NeutralPlacementGraph.RelocationAction action : graph.relocationActions())
			for(var obligation : action.obligations()) {
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				byDemand.computeIfAbsent(demand, ignored -> new LinkedHashSet<>())
					.add(new RelocationChoiceReceipt(demand, action.key()));
			}
		relocationDemandKeys = byDemand.keySet().stream().sorted().toList();
		relocationDomains = relocationDemandKeys.stream().map(demand ->
			byDemand.get(demand).stream().sorted().toList()).toList();
		stateCount = product(nodes.stream().map(node -> node.legalAlternatives().size()).toList());
		candidateCount = product(candidateDomains.stream().map(rows -> rows.size() + 1).toList());
		relocationCount = product(relocationDomains.stream().map(rows -> rows.size() + 1).toList());
		rawCount = stateCount.multiply(candidateCount).multiply(relocationCount);
	}

	private static BigInteger product(List<Integer> factors) {
		BigInteger result = ONE;
		for(int factor : factors)
			result = result.multiply(BigInteger.valueOf(factor));
		return result;
	}

	public BigInteger stateCount() { return stateCount; }
	public BigInteger rawCount() { return rawCount; }

	public StateInspection inspectState(BigInteger ordinal) {
		return prepare(ordinal).inspection;
	}

	/** Enumerates whole state cells; a caller can checkpoint at the next state ordinal. */
	public Summary enumerateStates(BigInteger begin, BigInteger end,
		Consumer<FullProductionJointPlanExport.Audit> acceptedSink) {
		return enumerateConstraintFrontier(begin, end, acceptedSink).summary();
	}

	public FrontierResult enumerateConstraintFrontier(BigInteger begin, BigInteger end,
		Consumer<FullProductionJointPlanExport.Audit> acceptedSink) {
		return enumerateRange(begin, end, acceptedSink, true);
	}

	// Reference path for differential tests: visit every state, including graph-invalid ones.
	FrontierResult enumerateWithoutConstraintFrontier(BigInteger begin, BigInteger end,
		Consumer<FullProductionJointPlanExport.Audit> acceptedSink) {
		return enumerateRange(begin, end, acceptedSink, false);
	}

	private FrontierResult enumerateRange(BigInteger begin, BigInteger end,
		Consumer<FullProductionJointPlanExport.Audit> acceptedSink, boolean frontier) {
		Objects.requireNonNull(acceptedSink, "acceptedSink");
		if(begin.signum() < 0 || end.compareTo(begin) < 0 || end.compareTo(stateCount) > 0)
			throw new IllegalArgumentException("Invalid half-open state ordinal range");
		Counter count = new Counter();
		if(frontier && !unresolvedOwner && !indexedConstraints.isEmpty()) {
			int[] fixed = new int[nodes.size()];
			Arrays.fill(fixed, -1);
			visitFrontier(nodes.size() - 1, ZERO, stateCount, begin, end, fixed, count, acceptedSink);
		}
		else
			for(BigInteger ordinal = begin; ordinal.compareTo(end) < 0; ordinal = ordinal.add(ONE))
				visitState(ordinal, count, acceptedSink);
		return new FrontierResult(new Summary(count.raw, count.accepted, count.rejected,
			count.unknown, count.candidatePruned), count.constraintPrunedStates);
	}

	private void visitFrontier(int position, BigInteger start, BigInteger width,
		BigInteger begin, BigInteger end, int[] fixed, Counter count,
		Consumer<FullProductionJointPlanExport.Audit> sink) {
		BigInteger clippedBegin = start.max(begin);
		BigInteger clippedEnd = start.add(width).min(end);
		if(clippedBegin.compareTo(clippedEnd) >= 0)
			return;
		if(!hasConstraintSupport(fixed)) {
			BigInteger states = clippedEnd.subtract(clippedBegin);
			count.constraintPrunedStates = count.constraintPrunedStates.add(states);
			BigInteger raw = states.multiply(candidateCount).multiply(relocationCount);
			count.raw = count.raw.add(raw);
			count.rejected = count.rejected.add(raw);
			return;
		}
		if(position < 0) {
			visitState(start, count, sink);
			return;
		}
		int radix = nodes.get(position).legalAlternatives().size();
		BigInteger childWidth = width.divide(BigInteger.valueOf(radix));
		for(int digit = 0; digit < radix; digit++) {
			fixed[position] = digit;
			visitFrontier(position - 1, start.add(childWidth.multiply(BigInteger.valueOf(digit))),
				childWidth, begin, end, fixed, count, sink);
		}
		fixed[position] = -1;
	}

	/** Arc consistency can prove impossibility, but never asserts that a supported tuple is feasible. */
	private boolean hasConstraintSupport(int[] fixed) {
		boolean[][] domain = new boolean[nodes.size()][];
		for(int i = 0; i < nodes.size(); i++) {
			domain[i] = new boolean[nodes.get(i).legalAlternatives().size()];
			for(int digit = 0; digit < domain[i].length; digit++)
				domain[i][digit] = fixed[i] < 0 || fixed[i] == digit;
		}
		boolean changed;
		do {
			changed = false;
			for(IndexedConstraint edge : indexedConstraints) {
				for(int side = 0; side < 2; side++) {
					int owner = side == 0 ? edge.left() : edge.right();
					int other = side == 0 ? edge.right() : edge.left();
					for(int digit = 0; digit < domain[owner].length; digit++) {
						if(!domain[owner][digit])
							continue;
						boolean supported = false;
						for(int counterpart = 0; counterpart < domain[other].length; counterpart++) {
							if(!domain[other][counterpart] || owner == other && digit != counterpart)
								continue;
							PlacementState left = nodes.get(edge.left()).legalAlternatives().get(
								side == 0 ? digit : counterpart);
							PlacementState right = nodes.get(edge.right()).legalAlternatives().get(
								side == 0 ? counterpart : digit);
							if(NeutralPlacementGraph.constraintSatisfied(edge.constraint(), left, right)) {
								supported = true;
								break;
							}
						}
						if(!supported) {
							domain[owner][digit] = false;
							changed = true;
						}
					}
					if(!any(domain[owner]))
						return false;
				}
			}
		}
		while(changed);
		return true;
	}

	private static boolean any(boolean[] values) {
		for(boolean value : values)
			if(value)
				return true;
		return false;
	}

	private void visitState(BigInteger ordinal, Counter count,
		Consumer<FullProductionJointPlanExport.Audit> acceptedSink) {
		Prepared state = prepare(ordinal);
		BigInteger rawPerState = candidateCount.multiply(relocationCount);
		count.raw = count.raw.add(rawPerState);
		if(state.inspection.earlyVerdict() != null) {
			count.add(state.inspection.earlyVerdict(), rawPerState);
			return;
		}
		BigInteger surviving = state.inspection.survivingCandidateAssignments();
		BigInteger pruned = candidateCount.subtract(surviving).multiply(relocationCount);
		count.rejected = count.rejected.add(pruned);
		count.candidatePruned = count.candidatePruned.add(pruned);
		selectCandidates(state, 0, ZERO, ONE, new ArrayList<>(), count, acceptedSink);
	}

	private record Prepared(BigInteger ordinal, Map<CompiledHopKey,PlacementState> assignment,
		List<List<CandidateSelectionReceipt>> survivingDomains, StateInspection inspection) { }

	private Prepared prepare(BigInteger ordinal) {
		if(ordinal.signum() < 0 || ordinal.compareTo(stateCount) >= 0)
			throw new IllegalArgumentException("Invalid state ordinal");
		BigInteger remaining = ordinal;
		Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
		for(NeutralPlacementGraph.Node node : nodes) {
			BigInteger[] qr = remaining.divideAndRemainder(BigInteger.valueOf(node.legalAlternatives().size()));
			assignment.put(node.key(), node.legalAlternatives().get(qr[1].intValueExact()));
			remaining = qr[0];
		}
		if(remaining.signum() != 0)
			throw new IllegalStateException("State ordinal did not decode completely");
		FullProductionJointPlanExport.Verdict early = null;
		String reason = null;
		if(unresolvedOwner) {
			early = FullProductionJointPlanExport.Verdict.UNKNOWN;
			reason = "Candidate owner outside decision graph has unresolved physical role";
		}
		else
			for(NeutralPlacementGraph.Constraint constraint : graph.constraints())
				if(assignment.containsKey(constraint.left()) && assignment.containsKey(constraint.right())
					&& !NeutralPlacementGraph.constraintSatisfied(constraint,
						assignment.get(constraint.left()), assignment.get(constraint.right()))) {
					early = FullProductionJointPlanExport.Verdict.REJECTED;
					reason = "graph constraint";
					break;
				}
		List<List<CandidateSelectionReceipt>> domains = new ArrayList<>();
		BigInteger surviving = ZERO;
		if(early == null) {
			try {
				Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible = CandidateSelections.feasibleVariants(
					analysis, graph, graph.relocationActions(), assignment);
				for(int i = 0; i < candidateOwners.size(); i++) {
					List<CandidateSelectionReceipt> variants = feasible.getOrDefault(candidateOwners.get(i), List.of());
					for(CandidateSelectionReceipt variant : variants)
						if(rawIndex(candidateDomains.get(i), variant) < 0)
							throw new IllegalStateException("Feasible receipt absent from raw candidate domain");
					domains.add(variants);
				}
				surviving = product(domains.stream().map(rows -> Math.max(1, rows.size())).toList());
			}
			catch(IllegalStateException ex) {
				early = ex.getMessage() != null && ex.getMessage().startsWith(
					"Active exact candidate has no source-reachable row:")
						? FullProductionJointPlanExport.Verdict.REJECTED
						: FullProductionJointPlanExport.Verdict.UNKNOWN;
				reason = ex.getMessage();
			}
		}
		StateInspection inspection = new StateInspection(ordinal, candidateCount, surviving,
			relocationCount, early, reason);
		return new Prepared(ordinal, assignment, domains, inspection);
	}

	private static int rawIndex(List<CandidateSelectionReceipt> rows, CandidateSelectionReceipt target) {
		for(int i = 0; i < rows.size(); i++) {
			CandidateSelectionReceipt row = rows.get(i);
			if(row.rule() == target.rule() && row.emission() == target.emission()
				&& row.realization() == target.realization()
				&& row.supportClause() == target.supportClause())
				return i;
		}
		return -1;
	}

	private void selectCandidates(Prepared state, int position, BigInteger digitValue,
		BigInteger stride, List<CandidateSelectionReceipt> selected, Counter count,
		Consumer<FullProductionJointPlanExport.Audit> sink) {
		if(position == candidateDomains.size()) {
			List<List<RelocationChoiceReceipt>> active;
			try {
				Map<RelocationDemandKey,List<RelocationChoiceReceipt>> byDemand = new LinkedHashMap<>();
				for(var demand : RelocationSelections.auditDemandOptions(analysis, state.assignment, selected)) {
					if(byDemand.putIfAbsent(demand.demand(), demand.choices()) != null)
						throw new IllegalStateException("Duplicate exact active relocation demand");
				}
				active = new ArrayList<>(relocationDomains.size());
				for(int i = 0; i < relocationDomains.size(); i++) {
					List<RelocationChoiceReceipt> choices = byDemand.remove(relocationDemandKeys.get(i));
					if(choices != null && choices.isEmpty()) {
						count.rejected = count.rejected.add(relocationCount);
						return;
					}
					List<RelocationChoiceReceipt> allowed = choices == null ? List.of() : choices;
					for(RelocationChoiceReceipt choice : allowed)
						if(!relocationDomains.get(i).contains(choice))
							throw new IllegalStateException("Active relocation choice absent from raw domain");
					active.add(allowed);
				}
				if(!byDemand.isEmpty())
					throw new IllegalStateException("Active relocation demand absent from raw domain");
			}
			catch(IllegalArgumentException error) {
				count.rejected = count.rejected.add(relocationCount);
				return;
			}
			catch(IllegalStateException error) {
				count.add(error.getMessage() != null && error.getMessage().startsWith(
					"Active exact candidate has no source-reachable row:")
					? FullProductionJointPlanExport.Verdict.REJECTED
					: FullProductionJointPlanExport.Verdict.UNKNOWN, relocationCount);
				return;
			}
			BigInteger surviving = product(active.stream().map(rows -> Math.max(1, rows.size())).toList());
			count.rejected = count.rejected.add(relocationCount.subtract(surviving));
			selectRelocations(state, active, 0,
				state.ordinal.add(stateCount.multiply(digitValue)), stateCount.multiply(candidateCount),
				new ArrayList<>(), selected, count, sink);
			return;
		}
		List<CandidateSelectionReceipt> variants = state.survivingDomains.get(position);
		BigInteger nextStride = stride.multiply(BigInteger.valueOf(candidateDomains.get(position).size() + 1L));
		if(variants.isEmpty())
			selectCandidates(state, position + 1, digitValue, nextStride, selected, count, sink);
		else
			for(CandidateSelectionReceipt receipt : variants) {
				selected.add(receipt);
				int digit = rawIndex(candidateDomains.get(position), receipt) + 1;
				selectCandidates(state, position + 1,
					digitValue.add(stride.multiply(BigInteger.valueOf(digit))), nextStride,
					selected, count, sink);
				selected.remove(selected.size() - 1);
			}
	}

	private void selectRelocations(Prepared state, List<List<RelocationChoiceReceipt>> active,
		int position, BigInteger candidateValue,
		BigInteger stride, List<RelocationChoiceReceipt> selectedRelocations,
		List<CandidateSelectionReceipt> candidates, Counter count,
		Consumer<FullProductionJointPlanExport.Audit> sink) {
		if(position == relocationDomains.size()) {
			FullProductionJointPlanExport.Audit audit = validate(candidateValue, state.assignment,
				candidates, selectedRelocations);
			count.add(audit.verdict(), ONE);
			if(audit.verdict() == FullProductionJointPlanExport.Verdict.ACCEPTED)
				sink.accept(audit);
			return;
		}
		BigInteger nextStride = stride.multiply(BigInteger.valueOf(relocationDomains.get(position).size() + 1L));
		if(active.get(position).isEmpty())
			selectRelocations(state, active, position + 1, candidateValue, nextStride,
				selectedRelocations, candidates, count, sink);
		else for(RelocationChoiceReceipt choice : active.get(position)) {
			int index = relocationDomains.get(position).indexOf(choice);
			if(index < 0) throw new IllegalStateException("Active relocation choice lost raw position");
			selectedRelocations.add(choice);
			selectRelocations(state, active, position + 1,
				candidateValue.add(stride.multiply(BigInteger.valueOf(index + 1L))), nextStride,
				selectedRelocations, candidates, count, sink);
			selectedRelocations.remove(selectedRelocations.size() - 1);
		}
	}

	private FullProductionJointPlanExport.Audit validate(BigInteger ordinal,
		Map<CompiledHopKey,PlacementState> assignment, List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> relocations) {
		try {
			CandidateSelections.resolveAndValidate(analysis, graph.relocationActions(), assignment, candidates);
			RelocationSelections.resolveAndValidate(analysis, assignment, candidates, relocations);
			CandidateSelections.validateRealizationSelections(analysis, assignment, candidates, relocations);
		}
		catch(IllegalArgumentException ex) {
			return new FullProductionJointPlanExport.Audit(ordinal, FullProductionJointPlanExport.Verdict.REJECTED,
				ex.getClass().getSimpleName() + ": " + ex.getMessage(), assignment, candidates, relocations);
		}
		catch(IllegalStateException ex) {
			FullProductionJointPlanExport.Verdict verdict = ex.getMessage() != null && ex.getMessage().startsWith(
				"Active exact candidate has no source-reachable row:")
					? FullProductionJointPlanExport.Verdict.REJECTED
					: FullProductionJointPlanExport.Verdict.UNKNOWN;
			return new FullProductionJointPlanExport.Audit(ordinal, verdict, ex.getMessage(),
				assignment, candidates, relocations);
		}
		FullProductionJointPlanExport.Audit accepted = new FullProductionJointPlanExport.Audit(ordinal,
			FullProductionJointPlanExport.Verdict.ACCEPTED, "production validators accepted",
			assignment, candidates, relocations);
		for(var action : accepted.derivedFoutActions())
			if(graph.derivedFoutMaterializationActions().stream().noneMatch(row -> row.key() == action))
				return new FullProductionJointPlanExport.Audit(ordinal, FullProductionJointPlanExport.Verdict.UNKNOWN,
					"Selected derived-FOUT action is not graph-owned", assignment, candidates, relocations);
		return accepted;
	}

	private static final class Counter {
		private BigInteger raw = ZERO, accepted = ZERO, rejected = ZERO, unknown = ZERO,
			candidatePruned = ZERO, constraintPrunedStates = ZERO;
		private void add(FullProductionJointPlanExport.Verdict verdict, BigInteger amount) {
			switch(verdict) {
				case ACCEPTED -> accepted = accepted.add(amount);
				case REJECTED -> rejected = rejected.add(amount);
				case UNKNOWN -> unknown = unknown.add(amount);
			}
		}
	}
}
