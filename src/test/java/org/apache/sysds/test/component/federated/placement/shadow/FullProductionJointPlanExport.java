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
import java.util.Collections;
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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;

/** Test-only raw product exporter. No selector, objective, or feasibility pruning is used to index rows. */
public final class FullProductionJointPlanExport {
	public enum Verdict { ACCEPTED, REJECTED, UNKNOWN }
	public enum NonDecisionOwnerRole { INERT_FUNCTION_TEMPLATE, UNRESOLVED }

	/** Available rule facts on a non-emitting function template are not emitted plan choices. */
	public record NonDecisionCandidateOwner(CompiledHopKey key, NonDecisionOwnerRole role,
		NeutralPlacementGraph.NodeKind nodeKind) { }

	public record Audit(BigInteger ordinal, Verdict verdict, String reason,
		Map<CompiledHopKey,PlacementState> assignment,
		List<CandidateSelectionReceipt> candidates, List<RelocationChoiceReceipt> relocations) {
		public Audit {
			assignment = Collections.unmodifiableMap(new LinkedHashMap<>(assignment));
			candidates = List.copyOf(candidates);
			relocations = List.copyOf(relocations);
		}

		/** Output materializations are coupled to selected candidate receipts, not free actions. */
		public List<DerivedFoutMaterializationActionKey> derivedFoutActions() {
			return candidates.stream().map(receipt -> receipt.emission().derivedFoutAction())
				.filter(Objects::nonNull).distinct().sorted().toList();
		}
	}

	private final PlacementAnalysis analysis;
	private final NeutralPlacementGraph graph;
	private final List<NeutralPlacementGraph.Node> nodes;
	private final List<List<CandidateSelectionReceipt>> candidateDomains;
	private final List<NonDecisionCandidateOwner> nonDecisionCandidateOwners;
	private final boolean hasUnresolvedCandidateOwner;
	private final List<RelocationDemandKey> demands;
	private final List<List<RelocationChoiceReceipt>> relocationDomains;
	private final BigInteger rawCount;

	public FullProductionJointPlanExport(PlacementAnalysis analysis) {
		this.analysis = Objects.requireNonNull(analysis, "analysis");
		graph = analysis.graph();
		nodes = List.copyOf(graph.decisionNodes());
		Map<CompiledHopKey,Set<CandidateSelectionReceipt>> byOwner = new LinkedHashMap<>();
		Map<CompiledHopKey,NonDecisionCandidateOwner> nonDecisionOwners = new LinkedHashMap<>();
		for(NeutralPlacementGraph.Node node : nodes)
			byOwner.put(node.key(), new LinkedHashSet<>());
		analysis.candidateRuleFacts().orderedFacts().forEach(fact -> {
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE) {
				CompiledHopKey owner = fact.key().parentOccurrence();
				Set<CandidateSelectionReceipt> rows = byOwner.get(owner);
				if(rows == null) {
					nonDecisionOwners.computeIfAbsent(owner, ignored -> classifyNonDecisionOwner(owner));
					return;
				}
				Set<CandidateSelectionReceipt> destination = rows;
				fact.allowedEmissionFacts().forEach(emission ->
					destination.addAll(analysis.canonicalCandidateReceipts(fact.key(), emission)));
			}
		});
		nonDecisionCandidateOwners = List.copyOf(nonDecisionOwners.values());
		hasUnresolvedCandidateOwner = nonDecisionCandidateOwners.stream()
			.anyMatch(owner -> owner.role() == NonDecisionOwnerRole.UNRESOLVED);
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
		demands = byDemand.keySet().stream().sorted().toList();
		relocationDomains = demands.stream().map(demand ->
			byDemand.get(demand).stream().sorted().toList()).toList();
		BigInteger count = BigInteger.ONE;
		for(NeutralPlacementGraph.Node node : nodes)
			count = count.multiply(BigInteger.valueOf(node.legalAlternatives().size()));
		for(List<CandidateSelectionReceipt> domain : candidateDomains)
			count = count.multiply(BigInteger.valueOf(domain.size() + 1L)); // absent is an explicit raw choice
		for(List<RelocationChoiceReceipt> domain : relocationDomains)
			count = count.multiply(BigInteger.valueOf(domain.size() + 1L));
		rawCount = count;
	}

	public BigInteger rawCount() { return rawCount; }
	public NeutralPlacementGraph graph() { return graph; }
	public List<NonDecisionCandidateOwner> nonDecisionCandidateOwners() {
		return nonDecisionCandidateOwners;
	}

	private NonDecisionCandidateOwner classifyNonDecisionOwner(CompiledHopKey owner) {
		NeutralPlacementGraph.Node node = graph.node(owner).orElse(null);
		if(node != null && node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_BODY_NON_EMITTED
			&& !node.emittedWork() && node.legalAlternatives().isEmpty())
			return new NonDecisionCandidateOwner(owner, NonDecisionOwnerRole.INERT_FUNCTION_TEMPLATE,
				node.kind());
		return new NonDecisionCandidateOwner(owner, NonDecisionOwnerRole.UNRESOLVED,
			node == null ? null : node.kind());
	}

	/** A stable half-open range; each raw index emits exactly one audit row, including rejected combinations. */
	public void stream(BigInteger begin, BigInteger end, Consumer<Audit> sink) {
		Objects.requireNonNull(begin, "begin");
		Objects.requireNonNull(end, "end");
		Objects.requireNonNull(sink, "sink");
		if(begin.signum() < 0 || end.compareTo(begin) < 0 || end.compareTo(rawCount) > 0)
			throw new IllegalArgumentException("Invalid half-open raw ordinal range");
		for(BigInteger index = begin; index.compareTo(end) < 0; index = index.add(BigInteger.ONE))
			sink.accept(evaluate(index));
	}

	private Audit evaluate(BigInteger index) {
		BigInteger remainder = index;
		Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
		List<CandidateSelectionReceipt> candidates = new ArrayList<>();
		List<RelocationChoiceReceipt> relocations = new ArrayList<>();
		for(NeutralPlacementGraph.Node node : nodes) {
			BigInteger[] qr = remainder.divideAndRemainder(
				BigInteger.valueOf(node.legalAlternatives().size()));
			assignment.put(node.key(), node.legalAlternatives().get(qr[1].intValueExact()));
			remainder = qr[0];
		}
		for(List<CandidateSelectionReceipt> domain : candidateDomains) {
			BigInteger[] qr = remainder.divideAndRemainder(BigInteger.valueOf(domain.size() + 1L));
			if(qr[1].signum() > 0)
				candidates.add(domain.get(qr[1].intValueExact() - 1));
			remainder = qr[0];
		}
		for(List<RelocationChoiceReceipt> domain : relocationDomains) {
			BigInteger[] qr = remainder.divideAndRemainder(BigInteger.valueOf(domain.size() + 1L));
			if(qr[1].signum() > 0)
				relocations.add(domain.get(qr[1].intValueExact() - 1));
			remainder = qr[0];
		}
		if(remainder.signum() != 0)
			throw new IllegalStateException("Raw ordinal decoding left a nonzero remainder");
		if(hasUnresolvedCandidateOwner)
			return new Audit(index, Verdict.UNKNOWN,
				"Candidate owner outside decision graph has unresolved physical role",
				assignment, candidates, relocations);
		for(NeutralPlacementGraph.Constraint constraint : graph.constraints())
			if(assignment.containsKey(constraint.left()) && assignment.containsKey(constraint.right())
				&& !NeutralPlacementGraph.constraintSatisfied(constraint,
					assignment.get(constraint.left()), assignment.get(constraint.right())))
				return new Audit(index, Verdict.REJECTED, "graph constraint", assignment, candidates, relocations);
		try {
			CandidateSelections.resolveAndValidate(analysis, graph.relocationActions(), assignment, candidates);
			RelocationSelections.resolveAndValidate(analysis, assignment, candidates, relocations);
			CandidateSelections.validateRealizationSelections(analysis, assignment, candidates, relocations);
		}
		catch(IllegalArgumentException ex) {
			return new Audit(index, Verdict.REJECTED,
				ex.getClass().getSimpleName() + ": " + ex.getMessage(), assignment, candidates, relocations);
		}
		catch(IllegalStateException ex) {
			if(ex.getMessage() != null && ex.getMessage().startsWith(
				"Active exact candidate has no source-reachable row:"))
				return new Audit(index, Verdict.REJECTED, ex.getMessage(),
					assignment, candidates, relocations);
			return new Audit(index, Verdict.UNKNOWN,
				"Production validator could not classify row: " + ex.getMessage(),
				assignment, candidates, relocations);
		}
		Audit accepted = new Audit(index, Verdict.ACCEPTED, "production validators accepted", assignment,
			candidates, relocations);
		for(DerivedFoutMaterializationActionKey action : accepted.derivedFoutActions())
			if(graph.derivedFoutMaterializationActions().stream().noneMatch(row -> row.key() == action))
				return new Audit(index, Verdict.UNKNOWN,
					"Selected derived-FOUT action is not graph-owned", assignment, candidates, relocations);
		return accepted;
	}
}
