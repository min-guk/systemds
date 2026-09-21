/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact selection and validation of candidate-rule rows retained by normalized plans. */
public final class CandidateSelections {
	private static final AtomicLong EXACT_SEARCH_IDS = new AtomicLong();

	private CandidateSelections() { }

	/**
	 * Admissible partial-assignment gate for exact placement search. It rejects only
	 * when a selected candidate-emitting consumer has no row whose physical PRESENT
	 * inputs can still become direct or use a graph-owned relocation action.
	 */
	public static boolean canStillBeReachable(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		return canStillBeReachable(analysis, analysis.graph(), actionUniverse, partialAssignment);
	}

	/**
	 * Variant whose graph is the exact policy projection that owns the supplied
	 * action universe.  Heuristic projections must not consult the unfiltered base
	 * graph for derived-output authority.
	 */
	public static boolean canStillBeReachable(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		return unreachableConsumers(analysis, authorityGraph, actionUniverse, partialAssignment).isEmpty();
	}

	/**
	 * Builds the immutable candidate-reachability projection used by exact search.
	 * The legacy diagnostic surface below intentionally remains the source of
	 * fail-closed error text; this index merely hoists invariant rule/edge/action
	 * joins out of the exponential assignment loop.
	 */
	public static PartialReachabilityIndex partialReachabilityIndex(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse) {
		return new PartialReachabilityIndex(analysis, authorityGraph, actionUniverse);
	}

	/**
	 * Canonical coupling required when candidate-reachability decisions are split
	 * into independent selector components. A participant may be the candidate
	 * consumer itself, a physical input producer, a derived-FOUT anchor owner, or
	 * a recursively forwarded function/transient source. Self edges are omitted.
	 */
	public record ComponentDependency(CompiledHopKey participant, CompiledHopKey consumer)
		implements Comparable<ComponentDependency> {
		public ComponentDependency {
			Objects.requireNonNull(participant, "participant");
			Objects.requireNonNull(consumer, "consumer");
		}

		@Override
		public int compareTo(ComponentDependency that) {
			int participantOrder = participant.compareTo(that.participant);
			return participantOrder != 0 ? participantOrder : consumer.compareTo(that.consumer);
		}
	}

	/** Immutable, allocation-free-on-success partial candidate reachability check. */
	public static final class PartialReachabilityIndex {
		private final PlacementAnalysis analysis;
		private final RelocationSelections.RelocationPrivacyIndex relocationPrivacy;
		private final List<IndexedConsumer> consumers;
		private final Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>>
			incomingFunctionInputs;
		private final Map<CompiledHopKey,List<IndexedConsumer>> consumersByDependency;
		private final List<ComponentDependency> componentDependencies;
		private final Map<CompiledHopKey,List<RelocationAction>> actionsByConsumer;
		private final Map<CandidateSelectionReceipt,List<IndexedCandidateAction>>
			physicalEffectsByReceipt;
		private final LocalMaterializationSelections.ExactProjectionIndex
			localMaterializationIndex;
		private final Set<CompiledHopKey> realizationDependentConsumers;

		/** Immutable affected-consumer projection for one exact-search decision group. */
		public final class ChangedNodesReachabilityProbe {
			private final List<IndexedConsumer> affectedConsumers;

			private ChangedNodesReachabilityProbe(List<IndexedConsumer> affectedConsumers) {
				this.affectedConsumers = affectedConsumers;
			}
		}

		private PartialReachabilityIndex(PlacementAnalysis analysis,
			NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.localMaterializationIndex =
				new LocalMaterializationSelections.ExactProjectionIndex(analysis);
			Objects.requireNonNull(authorityGraph, "authorityGraph");
			List<RelocationAction> actions = List.copyOf(
				Objects.requireNonNull(actionUniverse, "actionUniverse"));
			this.relocationPrivacy = RelocationSelections.relocationPrivacyIndex(
				analysis, authorityGraph, actions);
			Map<CompiledHopKey,List<RelocationAction>> actionsByConsumerMutable =
				new IdentityHashMap<>();
			for(RelocationAction action : actions) {
				Set<CompiledHopKey> seenConsumers =
					Collections.newSetFromMap(new IdentityHashMap<>());
				for(PlacementIdentity.ObligationKey obligation : action.obligations())
					if(seenConsumers.add(obligation.consumer()))
						actionsByConsumerMutable.computeIfAbsent(obligation.consumer(),
							ignored -> new ArrayList<>()).add(action);
			}
			this.actionsByConsumer = immutableIdentityLists(actionsByConsumerMutable);
			Map<CompiledHopKey,Map<Integer,List<PlacementAnalysis.CompiledInputEdgeFact>>> edges =
				new IdentityHashMap<>();
			for(PlacementAnalysis.CompiledInputEdgeFact edge :
				analysis.compiledInputEdgesInCanonicalOrder())
				edges.computeIfAbsent(edge.consumer(), ignored -> new LinkedHashMap<>())
					.computeIfAbsent(edge.inputPosition(), ignored -> new ArrayList<>()).add(edge);
			Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>> incoming =
				new IdentityHashMap<>();
			for(PlacementAnalysis.LogicalFunctionInputFact fact :
				analysis.logicalFunctionInputsInCanonicalOrder())
				incoming.computeIfAbsent(fact.targetRead(), ignored -> new ArrayList<>()).add(fact);
			this.incomingFunctionInputs = immutableIdentityLists(incoming);

			Map<CompiledHopKey,List<CandidateRuleFact>> factsByConsumer = new IdentityHashMap<>();
			for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts())
				if(fact.status() == CandidateEvaluationStatus.AVAILABLE)
					factsByConsumer.computeIfAbsent(fact.key().parentOccurrence(),
						ignored -> new ArrayList<>()).add(fact);
			List<IndexedConsumer> indexedConsumers = new ArrayList<>();
			Map<CandidateSelectionReceipt,List<IndexedCandidateAction>> physicalEffects =
				new IdentityHashMap<>();
			for(NeutralPlacementGraph.Node consumer : authorityGraph.decisionNodes()) {
				List<CandidateRuleFact> facts = factsByConsumer.get(consumer.key());
				if(facts == null || facts.isEmpty())
					continue;
				List<IndexedRow> rows = new ArrayList<>();
				for(CandidateRuleFact fact : facts) {
					long presentInputs = fact.key().orderedInputs().stream()
						.filter(CandidateInputState::present).count();
					long presentPhysicalInputs = java.util.stream.IntStream.range(0,
						fact.key().orderedInputs().size()).filter(position ->
							fact.key().orderedInputs().get(position).present()
								&& !edges.getOrDefault(consumer.key(), Map.of())
									.getOrDefault(position, List.of()).isEmpty()).count();
					for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(CandidateSelectionReceipt receipt : analysis.canonicalCandidateReceipts(fact.key(), emission)) {
						PlacementState selected = emission.emissionState().placementState();
						List<IndexedCandidateAction> rowActions = indexCandidatePhysicalEffects(
							authorityGraph, receipt, actionsByConsumer.getOrDefault(consumer.key(), List.of()));
						physicalEffects.put(receipt, rowActions);
						boolean emissionStructurallyReachable = foutMaterializationActionReachable(
							authorityGraph, fact, receipt, null, true);
						boolean relocationAnchorCompatible =
							RelocationSelections.candidateReceiptHasCommonPhysicalAnchor(
								actionsByConsumer.getOrDefault(consumer.key(), List.of()), receipt);
						CompiledHopKey anchorOwner = emission.derivedFoutAction() == null ? null
							: emission.derivedFoutAction().durableAnchorOwner();
						FType anchorOwnerType = emission.derivedFoutAction() == null ? null
							: emission.derivedFoutAction().durableAnchorOwnerFType();
						List<IndexedInput> inputs = new ArrayList<>();
						for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
							CandidateInputState input = fact.key().orderedInputs().get(position);
							if(!input.present())
								continue;
							List<PlacementAnalysis.CompiledInputEdgeFact> inputEdges = edges
								.getOrDefault(consumer.key(), Map.of()).getOrDefault(position, List.of());
							final int inputPosition = position;
							List<IndexedCandidateAction> supports = rowActions.stream().filter(action ->
								action.effects().stream().anyMatch(effect ->
									effect.demand().inputPosition() == inputPosition)).toList();
							CompiledHopKey exactDirectSource = receipt.supportClause().inputBindings().stream()
								.filter(binding -> binding.inputPosition() == inputPosition
									&& binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT)
								.map(binding -> binding.source().rule().parentOccurrence())
								.findFirst().orElse(null);
							CompiledHopKey possibleDirectSource = exactDirectSource != null
								? exactDirectSource : inputEdges.size() == 1 ? inputEdges.get(0).producer() : null;
							boolean directWhenUnassigned = possibleDirectSource != null
								&& (exactDirectSource != null || presentInputs == 1)
								&& analysis.graph().node(possibleDirectSource).orElseThrow()
									.legalAlternatives().stream().anyMatch(state ->
										state.output()
											== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
											&& state.fType() == input.fType());
							inputs.add(new IndexedInput(input.fType(), List.copyOf(inputEdges),
								supports, exactDirectSource, presentInputs == 1, presentPhysicalInputs == 1,
								directWhenUnassigned));
						}
						rows.add(new IndexedRow(receipt, selected, emissionStructurallyReachable,
							relocationAnchorCompatible,
							anchorOwner, anchorOwnerType,
							analysis.isDmlFunctionCallBoundary(consumer.key()),
							rowActions.stream().anyMatch(action ->
								relocationPrivacy.requiresOriginResidency(action.action())), List.copyOf(inputs)));
					}
				}
				// Canonicalize once while constructing the immutable index. Complete
				// assignment scoring is an exponential hot path and must not repeatedly
				// rebuild the same deeply nested candidate signatures merely to recover
				// the public feasibleVariants order/deduplication contract.
				rows.sort((left, right) -> left.receipt().compareTo(right.receipt()));
				Map<CandidateSelectionReceipt,IndexedRow> distinctRows = new LinkedHashMap<>();
				for(IndexedRow row : rows) {
					if(!distinctRows.containsKey(row.receipt()))
						distinctRows.put(row.receipt(), row);
				}
				List<IndexedRow> canonicalRows = List.copyOf(distinctRows.values());
				Map<PlacementState,List<IndexedRow>> rowsBySelectedStateMutable =
					new LinkedHashMap<>();
				for(IndexedRow row : canonicalRows)
					rowsBySelectedStateMutable.computeIfAbsent(row.selectedConsumer(),
						ignored -> new ArrayList<>()).add(row);
				List<IndexedStateRows> rowsBySelectedState = new ArrayList<>();
				for(Map.Entry<PlacementState,List<IndexedRow>> entry :
					rowsBySelectedStateMutable.entrySet())
					rowsBySelectedState.add(new IndexedStateRows(entry.getKey(),
						List.copyOf(entry.getValue())));
				indexedConsumers.add(new IndexedConsumer(consumer.key(), canonicalRows,
					List.copyOf(rowsBySelectedState)));
			}
			this.consumers = List.copyOf(indexedConsumers);
			this.physicalEffectsByReceipt = Collections.unmodifiableMap(physicalEffects);
			this.realizationDependentConsumers = realizationDependentConsumers(analysis);
			Map<CompiledHopKey,List<IndexedConsumer>> dependencies = new IdentityHashMap<>();
			Set<ComponentDependency> componentDependencies = new TreeSet<>();
			for(IndexedConsumer consumer : this.consumers) {
				Set<CompiledHopKey> keys = Collections.newSetFromMap(new IdentityHashMap<>());
				keys.add(consumer.key());
				for(IndexedRow row : consumer.rows()) {
					if(row.anchorOwner() != null)
						keys.add(row.anchorOwner());
					for(IndexedInput input : row.inputs()) {
						for(IndexedCandidateAction support : input.relocationSupports())
							keys.addAll(support.sources());
						for(PlacementAnalysis.CompiledInputEdgeFact edge : input.edges()) {
							keys.add(edge.producer());
							collectParametricDependencies(edge.producer(), keys,
								Collections.newSetFromMap(new IdentityHashMap<>()));
						}
					}
				}
				for(var fact : analysis.logicalTransientInputsInCanonicalOrder())
					if(fact.targetRead() == consumer.key() || fact.sourceWrite() == consumer.key()) {
						keys.add(fact.sourceWrite());
						keys.add(fact.targetRead());
					}
				for(var relation : analysis.logicalBoundaryRealizations().relations())
					if(relation.source() == consumer.key() || relation.target() == consumer.key()) {
						keys.add(relation.source());
						keys.add(relation.target());
					}
				for(IndexedRow row : consumer.rows())
					for(var support : row.receipt().supportClause().requiredInputSupport())
						keys.add(support.rule().parentOccurrence());
				for(CompiledHopKey key : keys) {
					dependencies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(consumer);
					if(!key.equals(consumer.key()))
						componentDependencies.add(new ComponentDependency(key, consumer.key()));
				}
			}
			this.consumersByDependency = immutableIdentityLists(dependencies);
			this.componentDependencies = List.copyOf(componentDependencies);
		}

		/**
		 * Returns the exact immutable dependency closure used by partial candidate
		 * reachability. Selectors must use these facts when partitioning independent
		 * components instead of reconstructing a weaker direct-input approximation.
		 */
		public List<ComponentDependency> componentDependencies() {
			return componentDependencies;
		}

		/**
		 * Computes the exact physical-effect key without repeating the invariant
		 * action/obligation/source joins for every complete branch-and-bound leaf.
		 * Only relocation activation remains assignment-dependent.  The indexed
		 * result preserves the action-universe and obligation order used by the
		 * canonical diagnostic implementation.
		 */
		private CandidateEffectKey candidateEffectKey(
			Map<CompiledHopKey,PlacementState> assignment,
			CandidateSelectionReceipt receipt) {
			List<IndexedCandidateAction> indexed = physicalEffectsByReceipt.get(receipt);
			if(indexed == null)
				throw new IllegalArgumentException(
					"Candidate receipt is outside the indexed exact-search universe");
			List<CandidateRelocationEffect> effects = new ArrayList<>();
			for(IndexedCandidateAction action : indexed) {
				boolean requiresEmission = action.requiresEmission(assignment);
				for(CandidateRelocationEffectSeed seed : action.effects())
					effects.add(new CandidateRelocationEffect(seed.demand(), seed.action(),
						requiresEmission));
			}
			return new CandidateEffectKey(receipt, receipt.rule().orderedInputs(),
				List.copyOf(effects));
		}

		private static List<IndexedCandidateAction> indexCandidatePhysicalEffects(
			NeutralPlacementGraph authorityGraph, CandidateSelectionReceipt receipt,
			List<RelocationAction> actions) {
			List<IndexedCandidateAction> result = new ArrayList<>();
			for(RelocationAction action : actions) {
				List<CandidateRelocationEffectSeed> effects = new ArrayList<>();
				for(PlacementIdentity.ObligationKey obligation : action.obligations())
					if(obligation.consumer() == receipt.rule().parentOccurrence()
						&& actionMatchesSelectedCandidate(action, obligation, receipt))
						effects.add(new CandidateRelocationEffectSeed(
							RelocationDemandKey.from(obligation), action.key()));
				if(effects.isEmpty())
					continue;

				List<CompiledHopKey> sources = authorityGraph.decisionNodes().stream()
					.filter(source -> source.valueVersion().equals(
						action.key().sourceValueVersion()))
					.map(NeutralPlacementGraph.Node::key).toList();
				List<DerivedSuppression> derivedSuppressions = new ArrayList<>();
				DerivedFoutMaterializationActionKey derived =
					receipt.emission().derivedFoutAction();
				if(derived != null && action.key().materializationFType()
					== derived.materializationFType()
					&& PlacementIdentity.samePhysicalWorkerPool(
						derived.durableAnchor(), action.key().durableAnchor())
					&& authorityGraph.derivedFoutMaterializationActions().stream()
						.filter(candidate -> candidate.key() == derived).count() == 1)
					for(NeutralPlacementGraph.Node source : authorityGraph.decisionNodes())
						if(source.valueVersion().equals(action.key().sourceValueVersion())
							&& derived.producerValueVersion() == source.valueVersion()
							&& derived.producer() == source.key())
							derivedSuppressions.add(new DerivedSuppression(
								source.key(), derived.targetPlacement()));
				result.add(new IndexedCandidateAction(action, List.copyOf(effects),
					List.copyOf(sources), List.copyOf(derivedSuppressions)));
			}
			return List.copyOf(result);
		}

		private void collectParametricDependencies(CompiledHopKey formal,
			Set<CompiledHopKey> dependencies, Set<CompiledHopKey> visiting) {
			if(!visiting.add(formal))
				return;
			try {
				for(PlacementAnalysis.LogicalFunctionInputFact input :
					incomingFunctionInputs.getOrDefault(formal, List.of())) {
					dependencies.add(input.sourceArgument());
					collectParametricDependencies(input.sourceArgument(), dependencies, visiting);
				}
			}
			finally {
				visiting.remove(formal);
			}
		}

		public boolean canStillBeReachable(Map<CompiledHopKey,PlacementState> partialAssignment) {
			Objects.requireNonNull(partialAssignment, "partialAssignment");
			for(IndexedConsumer consumer : consumers)
				if(!consumerReachable(consumer, partialAssignment))
					return false;
			return true;
		}

		/**
		 * Incremental exact gate after assigning one equality group. The prior
		 * partial assignment was already candidate-reachable, so only consumers
		 * whose row reachability reads a changed node can become invalid.
		 */
		public boolean canStillBeReachableForChangedNodes(
			Map<CompiledHopKey,PlacementState> partialAssignment,
			Collection<NeutralPlacementGraph.Node> changedNodes) {
			return canStillBeReachable(partialAssignment, changedNodesProbe(changedNodes));
		}

		/**
		 * Compiles the exact affected-consumer set once. Branch-and-bound reuses the
		 * same decision groups at every prefix, so rebuilding this identity set in the
		 * exponential hot path is unnecessary.
		 */
		public ChangedNodesReachabilityProbe changedNodesProbe(
			Collection<NeutralPlacementGraph.Node> changedNodes) {
			Objects.requireNonNull(changedNodes, "changedNodes");
			Set<IndexedConsumer> affected = Collections.newSetFromMap(new IdentityHashMap<>());
			for(NeutralPlacementGraph.Node node : changedNodes)
				affected.addAll(consumersByDependency.getOrDefault(node.key(), List.of()));
			return new ChangedNodesReachabilityProbe(List.copyOf(affected));
		}

		/** Allocation-free exact reachability check for a precompiled decision group. */
		public boolean canStillBeReachable(
			Map<CompiledHopKey,PlacementState> partialAssignment,
			ChangedNodesReachabilityProbe probe) {
			Objects.requireNonNull(partialAssignment, "partialAssignment");
			Objects.requireNonNull(probe, "probe");
			for(IndexedConsumer consumer : probe.affectedConsumers)
				if(!consumerReachable(consumer, partialAssignment))
					return false;
			return true;
		}

		/**
		 * Domain-aware generalized reachability gate for policy propagation. Unlike
		 * the admissible exact-search gate, an unassigned dependency is considered
		 * reachable only through a state that remains in its current domain.
		 */
		public boolean canStillBeReachable(
			Map<CompiledHopKey,PlacementState> partialAssignment,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains,
			ChangedNodesReachabilityProbe probe) {
			Objects.requireNonNull(partialAssignment, "partialAssignment");
			Objects.requireNonNull(remainingStateDomains, "remainingStateDomains");
			Objects.requireNonNull(probe, "probe");
			for(IndexedConsumer consumer : probe.affectedConsumers)
				if(!consumerReachable(consumer, partialAssignment, remainingStateDomains))
					return false;
			return true;
		}

		/** Full domain-aware check used after a generalized propagation fixed point. */
		public boolean canStillBeReachable(
			Map<CompiledHopKey,PlacementState> partialAssignment,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			Objects.requireNonNull(partialAssignment, "partialAssignment");
			Objects.requireNonNull(remainingStateDomains, "remainingStateDomains");
			for(IndexedConsumer consumer : consumers)
				if(!consumerReachable(consumer, partialAssignment, remainingStateDomains))
					return false;
			return true;
		}

		private boolean consumerReachable(IndexedConsumer consumer,
			Map<CompiledHopKey,PlacementState> partialAssignment) {
			PlacementState selected = partialAssignment.get(consumer.key());
			if(selected == null)
				return true;
			List<IndexedRow> activeRows = consumer.rowsFor(selected);
			for(IndexedRow row : activeRows)
				if(rowReachable(row, partialAssignment, true))
					return true;
			return activeRows.isEmpty();
		}

		private boolean consumerReachable(IndexedConsumer consumer,
			Map<CompiledHopKey,PlacementState> partialAssignment,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			PlacementState selected = partialAssignment.get(consumer.key());
			List<PlacementState> possible = selected == null
				? remainingStateDomains.get(consumer.key()) : List.of(selected);
			if(possible == null)
				return true;
			for(PlacementState state : possible) {
				List<IndexedRow> activeRows = consumer.rowsFor(state);
				if(activeRows.isEmpty())
					return true;
				for(IndexedRow row : activeRows)
					if(rowReachable(row, partialAssignment, true, remainingStateDomains))
						return true;
			}
			return false;
		}

		/**
		 * FedFirst's existing PRESENT-input preference, queried before fixing the output
		 * state. Only currently reachable rows count; this is an ordering hint, not a
		 * domain filter, materialization cost, or promise about unresolved predecessors.
		 * Reuses the immutable row/dependency index and the canonical reachability gate.
		 */
		public int maximumReachablePresentInputs(CompiledHopKey key, PlacementState state,
			Map<CompiledHopKey,PlacementState> partialAssignment,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			int maximum = 0;
			for(IndexedConsumer consumer : consumersByDependency.getOrDefault(key, List.of()))
				if(consumer.key() == key) {
					for(IndexedRow row : consumer.rowsFor(state))
						if(rowReachable(row, partialAssignment, true, remainingStateDomains))
							maximum = Math.max(maximum, presentInputCount(row.receipt()));
					break;
				}
			return maximum;
		}

		/**
		 * Strict complete-assignment row domain after the FedAll PRESENT-input
		 * objective. The immutable index reuses exact receipts and prejoined physical
		 * dependencies. Rows and consumers retain the same canonical order and receipt
		 * deduplication as the public path; physical-effect deduplication remains the
		 * responsibility of materializationMaximalVariants.
		 */
		public Map<CompiledHopKey,List<CandidateSelectionReceipt>>
			materializationObjectiveVariantsForCompleteAssignment(
				Map<CompiledHopKey,PlacementState> assignment) {
			Objects.requireNonNull(assignment, "assignment");
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new LinkedHashMap<>();
			for(IndexedConsumer consumer : consumers) {
				PlacementState selected = assignment.get(consumer.key());
				if(selected == null)
					continue;
				List<IndexedRow> activeRows = consumer.rowsFor(selected);
				List<CandidateSelectionReceipt> reachable = new ArrayList<>();
				for(IndexedRow row : activeRows)
					if(rowReachable(row, assignment, false))
						reachable.add(row.receipt());
				if(!activeRows.isEmpty() && reachable.isEmpty())
					throw new IllegalStateException(
						"Active exact candidate has no source-reachable row: "
							+ consumer.key().normalizedSignature() + " selected=" + selected
							+ " rows=" + activeRows.stream()
								.map(row -> rowReachabilityDetails(row, assignment))
								.toList());
				if(reachable.isEmpty())
					continue;
				if(realizationDependentConsumers.contains(consumer.key())) {
					result.put(consumer.key(), List.copyOf(reachable));
					continue;
				}
				boolean maximize = selected.execType() == ExecType.FED;
				int optimum = maximize ? Integer.MIN_VALUE : Integer.MAX_VALUE;
				for(CandidateSelectionReceipt receipt : reachable) {
					int present = presentInputCount(receipt);
					optimum = maximize ? Math.max(optimum, present) : Math.min(optimum, present);
				}
				List<CandidateSelectionReceipt> objective = new ArrayList<>();
				for(CandidateSelectionReceipt receipt : reachable)
					if(presentInputCount(receipt) == optimum)
						objective.add(receipt);
				result.put(consumer.key(), List.copyOf(objective));
			}
			return Collections.unmodifiableMap(result);
		}

		/**
		 * Admissible row superset for a partial assignment and explicit remaining
		 * state domains. Consumers outside the current exact-search component are
		 * omitted. No candidate-policy objective is applied because a row that is
		 * maximal before its sources are fixed may cease to be reachable later.
		 */
		public Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariantsForStateDomains(
			Map<CompiledHopKey,PlacementState> partialAssignment,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			Objects.requireNonNull(partialAssignment, "partialAssignment");
			Objects.requireNonNull(remainingStateDomains, "remainingStateDomains");
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new IdentityHashMap<>();
			for(IndexedConsumer consumer : consumers) {
				PlacementState selected = partialAssignment.get(consumer.key());
				List<PlacementState> possible = selected == null
					? remainingStateDomains.get(consumer.key()) : List.of(selected);
				if(possible == null || possible.isEmpty())
					continue;
				boolean active = false;
				List<CandidateSelectionReceipt> reachable = new ArrayList<>();
				for(PlacementState state : possible) {
					List<IndexedRow> stateRows = consumer.rowsFor(state);
					active |= !stateRows.isEmpty();
					for(IndexedRow row : stateRows)
					if(rowReachable(row, partialAssignment, true, remainingStateDomains))
						reachable.add(row.receipt());
				}
				if(active && reachable.isEmpty())
					throw new IllegalStateException(
						"Active exact candidate has no source-reachable row: "
							+ consumer.key().normalizedSignature() + " selected=" + selected
							+ " possible=" + possible + " rows="
							+ possible.stream().flatMap(state -> consumer.rowsFor(state).stream())
								.map(row -> rowReachabilityDetails(row, partialAssignment))
								.toList());
				if(!reachable.isEmpty())
					result.put(consumer.key(), List.copyOf(reachable));
			}
			return Collections.unmodifiableMap(result);
		}

		private boolean rowReachable(IndexedRow row,
			Map<CompiledHopKey,PlacementState> partialAssignment, boolean allowUnassigned) {
			return rowReachable(row, partialAssignment, allowUnassigned, Map.of());
		}

		private boolean rowReachable(IndexedRow row,
			Map<CompiledHopKey,PlacementState> partialAssignment, boolean allowUnassigned,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			if(!realizationsCanStillBeCompatible(analysis, partialAssignment,
				List.of(row.receipt()), remainingStateDomains))
				return false;
			if(!row.emissionStructurallyReachable() || !row.relocationAnchorCompatible())
				return false;
			if(row.anchorOwner() != null) {
				PlacementState owner = partialAssignment.get(row.anchorOwner());
				List<PlacementState> ownerDomain = remainingStateDomains.get(row.anchorOwner());
				if(owner == null && ownerDomain != null && ownerDomain.stream().noneMatch(state ->
					state.output()
						== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
						&& state.fType() == row.anchorOwnerType())
					|| owner == null && ownerDomain == null && !allowUnassigned
					|| owner != null && (owner.output()
					!= org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
						|| owner.fType() != row.anchorOwnerType()))
					return false;
			}
			if(row.functionBoundary() || row.selectedConsumer().execType() != ExecType.FED)
				return true;
			List<RelocationAction> safeActions = row.hasProtectedRelocations() ? new ArrayList<>() : null;
			for(IndexedInput input : row.inputs()) {
				if(input.edges().isEmpty())
					continue;
				if(input.edges().size() != 1)
					return false;
				if(!input.relocationSupports().isEmpty()) {
					boolean supported = false;
					for(IndexedCandidateAction support : input.relocationSupports())
						if(relocationCanStillBeSafe(support, partialAssignment,
							allowUnassigned, remainingStateDomains)) {
							supported = true;
							if(safeActions != null)
								safeActions.add(support.action());
						}
					// A rejected anchored demand must not fall through to mere FType
					// equality. That would hide a forbidden cross-pool materialization.
					if(!supported)
						return false;
					continue;
				}
				CompiledHopKey producer = input.edges().get(0).producer();
				boolean direct = false;
				if(input.exactDirectSource() != null || input.singlePresentInput()) {
					CompiledHopKey directSource = input.exactDirectSource() != null
						? input.exactDirectSource() : producer;
					PlacementState producerState = partialAssignment.get(directSource);
					List<PlacementState> producerDomain = remainingStateDomains.get(directSource);
					direct = producerState == null && producerDomain != null
						? producerDomain.stream().anyMatch(state -> state.output()
							== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
								&& state.fType() == input.required())
						: producerState == null ? allowUnassigned && input.directWhenUnassigned()
						: producerState.output()
							== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
								&& producerState.fType() == input.required();
				}
				boolean formal = input.singlePresentPhysicalInput()
					&& parametricFormalChainFoutCompatible(producer, input.required(),
						partialAssignment, allowUnassigned, remainingStateDomains,
						Collections.newSetFromMap(
							new IdentityHashMap<CompiledHopKey,Boolean>()));
				if(!direct && !formal)
					return false;
			}
			return safeActions == null || RelocationSelections.candidateReceiptHasCommonPhysicalAnchor(
				safeActions, row.receipt());
		}

		private boolean relocationCanStillBeSafe(IndexedCandidateAction support,
			Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			// The indexed support already matches the proposed consumer row. Unlike
			// graph.isRelocationActive, this does not interpret an unassigned consumer
			// as absence of demand. Only an exact source/suppression can make it direct.
			if(relocationPrivacy.isPrivacySafe(support.action(), support.requiresEmission(assignment)))
				return true;
			if(!allowUnassigned)
				return false;
			for(CompiledHopKey source : support.sources()) {
				if(assignment.get(source) != null)
					continue;
				List<PlacementState> domain = remainingStateDomains.get(source);
				if(domain == null)
					domain = analysis.graph().node(source).orElseThrow().legalAlternatives();
				for(PlacementState state : domain)
					if(support.suppressesEmission(source, state))
						return true;
			}
			return false;
		}

		private String rowReachabilityDetails(IndexedRow row,
			Map<CompiledHopKey,PlacementState> assignment) {
			List<String> inputs = new ArrayList<>();
			for(IndexedInput input : row.inputs())
				inputs.add("required=" + input.required() + ",receipted=" + !input.relocationSupports().isEmpty()
					+ ",edges=" + input.edges().stream().map(edge -> edge.producer().normalizedSignature()
						+ "=>" + assignment.get(edge.producer())).toList());
			return row.receipt().normalizedSignature()
				+ "{structural=" + row.emissionStructurallyReachable()
				+ ",anchorCompatible=" + row.relocationAnchorCompatible()
				+ ",anchorOwner=" + (row.anchorOwner() == null ? "none"
					: row.anchorOwner().normalizedSignature() + "=>" + assignment.get(row.anchorOwner()))
				+ ",anchorOwnerType=" + row.anchorOwnerType()
				+ ",inputs=" + inputs + '}';
		}

		private boolean parametricFormalChainFoutCompatible(CompiledHopKey formal, FType required,
			Map<CompiledHopKey,PlacementState> partialAssignment, boolean allowUnassigned,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains,
			Set<CompiledHopKey> visiting) {
			List<PlacementAnalysis.LogicalFunctionInputFact> incoming = incomingFunctionInputs.get(formal);
			if(incoming == null || incoming.isEmpty())
				return false;
			if(!foutCompatible(formal, required, partialAssignment, allowUnassigned,
				remainingStateDomains))
				return false;
			if(!visiting.add(formal))
				return true;
			try {
				for(PlacementAnalysis.LogicalFunctionInputFact input : incoming) {
					if(!foutCompatible(input.sourceArgument(), required, partialAssignment,
						allowUnassigned, remainingStateDomains))
						return false;
					if(incomingFunctionInputs.containsKey(input.sourceArgument())
						&& !parametricFormalChainFoutCompatible(input.sourceArgument(), required,
							partialAssignment, allowUnassigned, remainingStateDomains, visiting))
						return false;
				}
				return true;
			}
			finally {
				visiting.remove(formal);
			}
		}

		private static boolean foutCompatible(CompiledHopKey key, FType required,
			Map<CompiledHopKey,PlacementState> partialAssignment, boolean allowUnassigned,
			Map<CompiledHopKey,List<PlacementState>> remainingStateDomains) {
			PlacementState selected = partialAssignment.get(key);
			if(selected != null)
				return selected.output()
					== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
						&& selected.fType() == required;
			List<PlacementState> domain = remainingStateDomains.get(key);
			return domain == null ? allowUnassigned : domain.stream().anyMatch(state ->
				state.output()
					== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
					&& state.fType() == required);
		}
	}

	private record IndexedConsumer(CompiledHopKey key, List<IndexedRow> rows,
		List<IndexedStateRows> rowsBySelectedState) {
		private List<IndexedRow> rowsFor(PlacementState state) {
			for(IndexedStateRows candidate : rowsBySelectedState)
				if(candidate.state().equals(state))
					return candidate.rows();
			return List.of();
		}
	}
	private record IndexedStateRows(PlacementState state, List<IndexedRow> rows) { }
	private record IndexedRow(CandidateSelectionReceipt receipt, PlacementState selectedConsumer,
		boolean emissionStructurallyReachable, boolean relocationAnchorCompatible,
		CompiledHopKey anchorOwner, FType anchorOwnerType, boolean functionBoundary,
		boolean hasProtectedRelocations,
		List<IndexedInput> inputs) { }
	private record IndexedInput(FType required,
		List<PlacementAnalysis.CompiledInputEdgeFact> edges, List<IndexedCandidateAction> relocationSupports,
		CompiledHopKey exactDirectSource,
		boolean singlePresentInput, boolean singlePresentPhysicalInput,
		boolean directWhenUnassigned) { }
	private record CandidateRelocationEffectSeed(RelocationDemandKey demand,
		RelocationActionKey action) { }
	private record DerivedSuppression(CompiledHopKey source, PlacementState target) { }
	private record IndexedCandidateAction(RelocationAction action,
		List<CandidateRelocationEffectSeed> effects, List<CompiledHopKey> sources,
		List<DerivedSuppression> derivedSuppressions) {
		private boolean requiresEmission(Map<CompiledHopKey,PlacementState> assignment) {
			for(CompiledHopKey source : sources) {
				PlacementState selected = assignment.get(source);
				if(selected != null && suppressesEmission(source, selected))
					return false;
			}
			return true;
		}

		private boolean suppressesEmission(CompiledHopKey source, PlacementState selected) {
			if(action.directSourcePlacements().contains(selected))
				return true;
			for(DerivedSuppression derived : derivedSuppressions)
				if(derived.source() == source && selected == derived.target())
					return true;
			return false;
		}
	}

	private static <T> Map<CompiledHopKey,List<T>> immutableIdentityLists(
		Map<CompiledHopKey,List<T>> source) {
		Map<CompiledHopKey,List<T>> copied = new IdentityHashMap<>();
		for(Map.Entry<CompiledHopKey,List<T>> entry : source.entrySet())
			copied.put(entry.getKey(), List.copyOf(entry.getValue()));
		return Collections.unmodifiableMap(copied);
	}

	/** Deterministic fail-closed diagnostics for exact candidate-reachability pruning. */
	public static List<String> unreachableConsumers(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(partialAssignment, "partialAssignment");
		List<String> unreachable = new ArrayList<>();
		for(NeutralPlacementGraph.Node consumer : authorityGraph.decisionNodes()) {
			PlacementState selectedConsumer = partialAssignment.get(consumer.key());
			if(selectedConsumer == null)
				continue;
			List<CandidateRuleFact> active = analysis.candidateRuleFacts().orderedFacts().stream()
				.filter(fact -> fact.key().parentOccurrence() == consumer.key())
				.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE)
				.filter(fact -> fact.allowedEmissionFacts().stream().anyMatch(emission ->
					emission.emissionState().placementState().equals(selectedConsumer)))
				.toList();
			if(active.isEmpty())
				continue;
			boolean reachable = active.stream().anyMatch(fact -> candidateRowCanStillBeReachable(
				analysis, authorityGraph, actionUniverse, partialAssignment, selectedConsumer, fact));
			if(!reachable)
				unreachable.add(consumer.key().normalizedSignature() + '='
					+ selectedConsumer.normalizedSignature() + "|activeRows=" + active.stream()
						.map(fact -> fact.key().normalizedSignature() + candidateReachabilityDiagnostic(
							analysis, authorityGraph, actionUniverse, partialAssignment,
							selectedConsumer, fact)).sorted().toList());
		}
		return List.copyOf(unreachable);
	}

	private static String candidateReachabilityDiagnostic(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actions,
		Map<CompiledHopKey,PlacementState> partial, PlacementState selectedConsumer,
		CandidateRuleFact fact) {
		List<String> inputs = new ArrayList<>();
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			CandidateInputState input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == fact.key().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			if(edges.size() != 1) {
				inputs.add(position + ":edges=" + edges.size());
				continue;
			}
			CompiledHopKey producer = edges.get(0).producer();
			NeutralPlacementGraph.Node source = authorityGraph.node(producer).orElse(null);
			List<String> receipts = actions.stream().filter(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().targetPlacement().equals(selectedConsumer)
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == fact.key().parentOccurrence()
							&& obligation.inputPosition() == inputPosition))
				.map(action -> action.key().normalizedSignature() + "|direct="
					+ action.directSourcePlacements().stream()
						.map(PlacementState::normalizedSignature).toList()).toList();
			inputs.add(position + ":producer=" + producer.normalizedSignature()
				+ "|selected=" + (partial.get(producer) == null ? "-"
					: partial.get(producer).normalizedSignature())
				+ "|legal=" + (source == null ? List.of() : source.legalAlternatives().stream()
					.map(PlacementState::normalizedSignature).toList())
				+ "|receipts=" + receipts);
		}
		return "|reachabilityInputs=" + inputs;
	}

	private static boolean candidateRowCanStillBeReachable(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actions,
		Map<CompiledHopKey,PlacementState> partial,
		PlacementState selectedConsumer, CandidateRuleFact fact) {
		boolean exactEmissionReachable = fact.allowedEmissionFacts().stream()
			.filter(emission -> emission.emissionState().placementState().equals(selectedConsumer))
			.flatMap(emission -> analysis.canonicalCandidateReceipts(fact.key(), emission).stream())
			.anyMatch(receipt -> foutMaterializationActionReachable(
				authorityGraph, fact, receipt, partial, true));
		if(!exactEmissionReachable)
			return false;
		// A DML FunctionOp is only a coordinator-side forwarding placeholder. Its
		// actual/formal placement contract is validated by the explicit function
		// boundary facts; requiring a physical receipt action here would invent a
		// caller-side matrix consumption that the runtime never performs.
		if(analysis.isDmlFunctionCallBoundary(fact.key().parentOccurrence()))
			return true;
		// Receipt/worker-pool coherence is a precondition for executing the
		// consumer on federated workers. A CP consumer does not consume a matrix
		// FederationMap directly; its FED-to-local boundary is modeled elsewhere.
		if(selectedConsumer.execType() != org.apache.sysds.common.Types.ExecType.FED)
			return true;
		for(int position = 0; position < fact.key().orderedInputs().size(); position++) {
			CandidateInputState input = fact.key().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == fact.key().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			// Logical function/transient links are governed by their explicit compiler
			// forwarding constraints rather than a compiled physical edge.
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				return false;
			boolean receipted = actions.stream().anyMatch(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().targetPlacement().equals(selectedConsumer)
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == fact.key().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			boolean direct = singlePhysicalInputDirectReachable(
				analysis, fact.key(), inputPosition, input.fType(), partial, true);
			if(!receipted && !direct && !singleParametricFormalReceiptReachable(
				analysis, fact.key(), inputPosition, input.fType(), partial, true))
				return false;
		}
		return true;
	}

	public record Selection(List<CandidateSelectionReceipt> candidates,
		List<RelocationChoiceReceipt> relocationChoices,
		List<RelocationActionKey> emittedActions, int materializedInputCount,
		int relocationPhysicalEmissionCount, int localMaterializationActionCount,
		int foutMaterializationActionCount) {
		public Selection {
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
			relocationChoices = List.copyOf(Objects.requireNonNull(relocationChoices, "relocationChoices"));
			emittedActions = List.copyOf(Objects.requireNonNull(emittedActions, "emittedActions"));
			if(materializedInputCount < 0)
				throw new IllegalArgumentException("Materialized input count must be non-negative");
			if(relocationPhysicalEmissionCount < 0)
				throw new IllegalArgumentException(
					"Relocation physical emission count must be non-negative");
			if(localMaterializationActionCount < 0)
				throw new IllegalArgumentException(
					"Local materialization action count must be non-negative");
			if(foutMaterializationActionCount < 0)
				throw new IllegalArgumentException(
					"FOUT materialization action count must be non-negative");
		}
	}

	/** Returns all exact, source-reachable candidate rows for the selected placement assignment. */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return feasibleVariants(analysis, analysis.graph(), actionUniverse, assignment);
	}

	/** Exact candidate rows under a policy-projected graph authority. */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return feasibleVariants(analysis, authorityGraph, actionUniverse, assignment, false);
	}

	/**
	 * Returns every row that can still be reached by a partial exact-search
	 * assignment. Unassigned physical sources, formal chains, and derived-FOUT
	 * anchor owners remain possible; assigned incompatible sources still fail
	 * closed. This is an admissible superset used only for lower bounds and does
	 * not authorize lowering an incomplete plan.
	 */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariantsForPartial(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> partialAssignment) {
		return feasibleVariants(analysis, authorityGraph, actionUniverse, partialAssignment, true);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		boolean allowUnassignedDerivedFoutOwner) {
		return feasibleVariants(analysis, authorityGraph, actionUniverse, assignment,
			allowUnassignedDerivedFoutOwner, true);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasibleVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		boolean allowUnassignedDerivedFoutOwner, boolean canonicalize) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new IdentityHashMap<>();
		Map<CompiledHopKey,Boolean> activeConsumers = new IdentityHashMap<>();
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> activeRows = new IdentityHashMap<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			PlacementState selected = assignment.get(fact.key().parentOccurrence());
			if(selected == null || fact.status() != CandidateEvaluationStatus.AVAILABLE)
				continue;
			if(fact.allowedEmissionFacts().stream()
				.anyMatch(emission -> emission.emissionState().placementState().equals(selected)))
				activeConsumers.put(fact.key().parentOccurrence(), Boolean.TRUE);
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
			for(CandidateSelectionReceipt base : analysis.canonicalCandidateReceipts(fact.key(), emission)) {
				if(!emission.emissionState().placementState().equals(selected))
					continue;
				activeRows.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(base);
				if(foutMaterializationActionReachable(authorityGraph, fact, base, assignment,
					allowUnassignedDerivedFoutOwner)
					&& receiptReachable(analysis, actionUniverse, assignment, base,
						allowUnassignedDerivedFoutOwner))
					result.computeIfAbsent(fact.key().parentOccurrence(), ignored -> new ArrayList<>()).add(base);
			}
		}
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> ordered = canonicalize
			? new LinkedHashMap<>() : new IdentityHashMap<>();
		authorityGraph.decisionNodes().stream().map(NeutralPlacementGraph.Node::key).forEach(key -> {
			List<CandidateSelectionReceipt> raw = result.getOrDefault(key, List.of());
			List<CandidateSelectionReceipt> variants = !canonicalize
				? List.copyOf(raw) : analysis.canonicalCandidateReceipts(raw);
			if(activeConsumers.containsKey(key) && variants.isEmpty())
				throw new IllegalStateException("Active exact candidate has no source-reachable row: "
					+ key.normalizedSignature() + " rows=" + activeRows.getOrDefault(key, List.of()).stream()
						.map(row -> row.normalizedSignature() + " => "
							+ reachabilityDetails(analysis, actionUniverse, assignment, row)).toList());
			if(!variants.isEmpty())
				ordered.put(key, variants);
		});
		return Collections.unmodifiableMap(ordered);
	}

	/** FedAll/Heuristic candidate policy: maximize explicit federated inputs, then share relocations. */
	public static Selection selectMaterializationMaximal(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return selectMaterializationMaximal(analysis, analysis.graph(), actionUniverse, assignment);
	}

	/** FedAll/Heuristic candidate policy under an exact projected graph. */
	public static Selection selectMaterializationMaximal(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		return selectMaterializationMaximal(analysis, authorityGraph, actionUniverse, assignment,
			analysis.relocationOrderFor(actionUniverse));
	}

	/** Exact FedAll candidate policy with a reusable relocation canonical-order index. */
	public static Selection selectMaterializationMaximal(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex relocationOrder) {
		return selectMaterializationMaximal(analysis, authorityGraph, actionUniverse, assignment,
			relocationOrder, null);
	}

	/**
	 * Exact FedAll candidate policy with reusable immutable search indexes. The
	 * reachability index is optional so non-search callers retain the canonical
	 * public implementation as their source of truth.
	 */
	public static Selection selectMaterializationMaximal(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex relocationOrder,
		PartialReachabilityIndex reachabilityIndex) {
		List<RelocationAction> actions = List.copyOf(actionUniverse);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
			reachabilityIndex == null
				? materializationMaximalVariantsForCompleteAssignment(
					analysis, authorityGraph, actions, assignment)
				: materializationMaximalVariants(analysis, authorityGraph, actions,
					assignment, reachabilityIndex
						.materializationObjectiveVariantsForCompleteAssignment(assignment),
					reachabilityIndex);
		return selectMaterializationMaximalPrevalidated(analysis, authorityGraph, actions,
			assignment, relocationOrder, reachabilityIndex, byConsumer);
	}

	/**
	 * Completes an already validated materialization-maximal row domain. Keeping this
	 * boundary separate makes the exact factor solver independently testable without
	 * changing candidate feasibility or pruning.
	 */
	static Selection selectMaterializationMaximalPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, List<RelocationAction> actions,
		Map<CompiledHopKey,PlacementState> assignment,
		RelocationSelections.CanonicalOrderIndex relocationOrder,
		PartialReachabilityIndex reachabilityIndex,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actions, "actions");
		Objects.requireNonNull(assignment, "assignment");
		Objects.requireNonNull(relocationOrder, "relocationOrder");
		Objects.requireNonNull(byConsumer, "byConsumer");
		// feasibleVariants projects the graph's constructor-canonical decision-node
		// order into a LinkedHashMap, and materializationMaximalVariants preserves it.
		// Reuse that order instead of repeatedly rebuilding deeply nested key signatures
		// for every complete exact-placement assignment.
		List<CompiledHopKey> consumers = new ArrayList<>(byConsumer.keySet());
		// Public planner results retain the historical canonical receipt order. The
		// reusable exact-search index already has a stable graph order and deliberately
		// avoids reconstructing large normalized key strings in the hot score loop.
		if(reachabilityIndex == null)
			Collections.sort(consumers);
		consumers = List.copyOf(consumers);
		Search search = new Search(analysis, authorityGraph, actions, assignment,
			consumers, byConsumer, relocationOrder, reachabilityIndex);
		search.solve();
		Selection result = search.requireBest();
		if(reachabilityIndex != null)
			return result;
		return new Selection(analysis.canonicalCandidateReceipts(result.candidates()),
			result.relocationChoices(), result.emittedActions(), result.materializedInputCount(),
			result.relocationPhysicalEmissionCount(), result.localMaterializationActionCount(),
			result.foutMaterializationActionCount());
	}

	/**
	 * Exact FedAll candidate-row domain after its per-consumer PRESENT-input
	 * objective and physical-effect canonicalization. The assignment must be
	 * complete for every active physical dependency; partial exact search uses
	 * {@link #feasibleVariantsForPartial} instead.
	 */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>>
		materializationMaximalVariantsForCompleteAssignment(PlacementAnalysis analysis,
			NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
			Map<CompiledHopKey,PlacementState> assignment) {
		return materializationMaximalVariants(analysis, authorityGraph, actionUniverse, assignment,
			feasibleVariants(analysis, authorityGraph, actionUniverse, assignment));
	}

	/**
	 * Superset of the final FedAll row domain after applying only its primary,
	 * per-consumer PRESENT-input objective. Physical-effect deduplication and the
	 * anchor-aligned deterministic tie-break can only remove rows from this set,
	 * making it suitable for admissible exact-search lower bounds without building
	 * recursively large canonical signatures.
	 */
	public static Map<CompiledHopKey,List<CandidateSelectionReceipt>>
		materializationObjectiveVariantsForCompleteAssignment(PlacementAnalysis analysis,
			NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
			Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible =
			feasibleVariants(analysis, authorityGraph, actionUniverse, assignment, false, false);
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> result = new IdentityHashMap<>();
		Set<CompiledHopKey> realizationDependent = realizationDependentConsumers(analysis);
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : feasible.entrySet()) {
			if(realizationDependent.contains(entry.getKey())) {
				result.put(entry.getKey(), entry.getValue());
				continue;
			}
			PlacementState consumer = assignment.get(entry.getKey());
			if(consumer == null)
				throw new IllegalStateException("Candidate consumer has no selected placement");
			boolean maximize = consumer.execType() == ExecType.FED;
			java.util.stream.IntStream counts = entry.getValue().stream()
				.mapToInt(CandidateSelections::presentInputCount);
			int optimum = maximize ? counts.max().orElseThrow() : counts.min().orElseThrow();
			result.put(entry.getKey(), entry.getValue().stream()
				.filter(row -> presentInputCount(row) == optimum).toList());
		}
		return Collections.unmodifiableMap(result);
	}

	private static boolean hasRealizationDependencies(PlacementAnalysis analysis) {
		return !realizationDependentConsumers(analysis).isEmpty();
	}

	private static boolean consumerHasRealizationDependencies(PlacementAnalysis analysis,
		CompiledHopKey consumer) {
		return realizationDependentConsumers(analysis).contains(consumer);
	}

	private static Set<CompiledHopKey> realizationDependentConsumers(PlacementAnalysis analysis) {
		Set<CompiledHopKey> dependent = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var fact : analysis.logicalTransientInputsInCanonicalOrder()) {
			dependent.add(fact.sourceWrite());
			dependent.add(fact.targetRead());
		}
		for(var relation : analysis.logicalBoundaryRealizations().relations()) {
			dependent.add(relation.source());
			dependent.add(relation.target());
		}
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts())
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
				for(var realization : emission.realizations())
					for(var clause : realization.supportClauses()) {
						if(clause.inputBindings().isEmpty())
							continue;
						dependent.add(fact.key().parentOccurrence());
						for(CandidateRealizationReference support : clause.requiredInputSupport())
							dependent.add(support.rule().parentOccurrence());
					}
		return Collections.unmodifiableSet(dependent);
	}

	/**
	 * For uncoupled candidates, row preference is consumer-separable. A FED execution retains the
	 * legacy FedAll policy of maximizing explicit federated materializations; a CP
	 * execution minimizes them because uploading a LOUT input only to execute the
	 * consumer locally contradicts that selected placement. Restricting the exact
	 * secondary relocation search to each consumer's directional optimum is an
	 * exact lexicographic reduction only in that uncoupled case. Coupled support
	 * clauses retain all feasible rows for the component-level search.
	 */
	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> materializationMaximalVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible) {
		return materializationMaximalVariants(analysis, authorityGraph, actionUniverse,
			assignment, feasible, null);
	}

	private static Map<CompiledHopKey,List<CandidateSelectionReceipt>> materializationMaximalVariants(
		PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible,
		PartialReachabilityIndex reachabilityIndex) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> maximal = new LinkedHashMap<>();
		Set<CompiledHopKey> realizationDependent = reachabilityIndex == null
			? realizationDependentConsumers(analysis) : reachabilityIndex.realizationDependentConsumers;
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : feasible.entrySet()) {
			if(realizationDependent.contains(entry.getKey())) {
				maximal.put(entry.getKey(), entry.getValue());
				continue;
			}
			PlacementState consumerState = assignment.get(entry.getKey());
			if(consumerState == null)
				throw new IllegalStateException("Candidate consumer has no selected placement");
			boolean maximize = consumerState.execType()
				== org.apache.sysds.common.Types.ExecType.FED;
			java.util.stream.IntStream materializations = entry.getValue().stream()
				.mapToInt(CandidateSelections::presentInputCount);
			int optimum = maximize ? materializations.max().orElseThrow()
				: materializations.min().orElseThrow();
			List<CandidateSelectionReceipt> optimumRaw = entry.getValue().stream()
				.filter(receipt -> presentInputCount(receipt) == optimum).toList();
			// feasibleVariants already emits rows in canonical receipt order. Filtering
			// by the separable materialization objective preserves that exact order.
			List<CandidateSelectionReceipt> optimumReceipts = optimumRaw;
			List<CandidateSelectionReceipt> effects;
			if(optimumReceipts.size() == 1)
				effects = optimumReceipts;
			else {
				Map<CandidateEffectKey,CandidateSelectionReceipt> byPhysicalEffect = new LinkedHashMap<>();
				optimumReceipts.forEach(receipt -> byPhysicalEffect.putIfAbsent(candidateEffectKey(
					analysis, authorityGraph, actionUniverse, assignment, receipt,
					reachabilityIndex), receipt));
				effects = List.copyOf(byPhysicalEffect.values());
			}
			if(maximize && effects.size() > 1) {
				List<CandidateSelectionReceipt> anchorAligned = effects.stream()
					.filter(receipt -> allPresentRelocationsAnchorAligned(
						analysis, actionUniverse, assignment, receipt)).toList();
				if(!anchorAligned.isEmpty())
					effects = anchorAligned;
			}
			maximal.put(entry.getKey(), effects);
		}
		return Collections.unmodifiableMap(maximal);
	}

	/**
	 * FedAll/Heuristic tie-break for equal-materialization candidate rows. If every
	 * PRESENT matrix input has an exact relocation whose upload layout equals the
	 * durable anchor layout, that row cannot replicate or reshape more data than a
	 * competing cross-layout row reaching the same selected consumer placement.
	 * Cost-based planners never use this projection and retain every exact row.
	 */
	private static boolean allPresentRelocationsAnchorAligned(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt receipt) {
		if(receipt.emission().emissionState().placementState().execType() != ExecType.FED)
			return true;
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.consumer() == receipt.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				return false;
			CompiledHopKey producer = edges.get(0).producer();
			PlacementState source = assignment.get(producer);
			if(source != null && source.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& source.fType() == input.fType())
				continue;
			boolean aligned = actions.stream().anyMatch(action ->
				action.key().materializationFType() == input.fType()
					&& action.key().materializationFType() == action.key().durableAnchor().fType()
					&& action.key().targetPlacement().equals(
						receipt.emission().emissionState().placementState())
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == receipt.rule().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			if(!aligned)
				return false;
		}
		return true;
	}

	/**
	 * Rows can be collapsed only when their exact emission, ordered PRESENT/ABSENT
	 * pattern, and explicit relocation alternatives are all identical. Including
	 * the emission and input pattern preserves every possible local-download effect,
	 * including whether this row publishes a native or derived FOUT producer.
	 */
	private record CandidateRelocationEffect(RelocationDemandKey demand,
		RelocationActionKey action, boolean requiresEmission) { }

	private record CandidateEffectKey(CandidateSelectionReceipt receipt,
		List<CandidateInputState> orderedInputs, List<CandidateRelocationEffect> relocations) { }

	private static CandidateEffectKey candidateEffectKey(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment, CandidateSelectionReceipt receipt,
		PartialReachabilityIndex reachabilityIndex) {
		if(reachabilityIndex != null)
			return reachabilityIndex.candidateEffectKey(assignment, receipt);
		List<CandidateRelocationEffect> options = new ArrayList<>();
		for(RelocationAction action : actionUniverse) {
			boolean requiresEmission = authorityGraph.isRelocationActive(
				action, assignment, List.of(receipt));
			for(PlacementIdentity.ObligationKey obligation : action.obligations()) {
				if(!obligation.requiredPlacement().equals(assignment.get(obligation.consumer()))
					|| obligation.consumer() != receipt.rule().parentOccurrence()
					|| !actionMatchesSelectedCandidate(action, obligation, receipt))
					continue;
				options.add(new CandidateRelocationEffect(RelocationDemandKey.from(obligation),
					action.key(), requiresEmission));
			}
		}
		return new CandidateEffectKey(receipt, receipt.rule().orderedInputs(),
			List.copyOf(options));
	}

	static boolean actionMatchesSelectedCandidate(RelocationAction action,
		PlacementIdentity.ObligationKey obligation, CandidateSelectionReceipt selected) {
		if(!selected.emission().emissionState().placementState()
			.equals(obligation.requiredPlacement())
			|| obligation.inputPosition() >= selected.rule().orderedInputs().size())
			return false;
		CandidateInputState input = selected.rule().orderedInputs().get(obligation.inputPosition());
		if(!input.present() || input.fType() != action.key().materializationFType())
			return false;
		List<PlacementIdentity.CandidateRealizationInputBinding> bindings = selected.supportClause()
			.inputBindings().stream()
			.filter(binding -> binding.inputPosition() == obligation.inputPosition()).toList();
		// Older realizations without exact input bindings retain the graph-owned
		// relocation alternatives. Once a clause carries an exact binding, however,
		// only the named RELOCATION action is a demand. DIRECT and
		// LOGICAL_TRANSIENT bindings are authority that this input is already
		// available without that physical movement; treating their empty relocation
		// subset as allMatch=true creates a demand that realizationActionAllowed must
		// reject and can make an otherwise legal candidate component infeasible.
		return bindings.isEmpty() || bindings.stream().allMatch(binding ->
			binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
				&& binding.relocationAction().equals(action.key()));
	}


	/**
	 * Shared logical-relation gate. Edges within one writer/read slot are alternatives;
	 * reaching writers are conjunctive and refer to the same selected reader realization.
	 * Unselected receipts retain existential support, never an invented map or upload.
	 */
	public static boolean realizationsCanStillBeCompatible(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> receipts) {
		return realizationsCanStillBeCompatible(analysis, assignment, receipts, Map.of());
	}

	private static boolean realizationsCanStillBeCompatible(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> receipts,
		Map<CompiledHopKey,List<PlacementState>> remaining) {
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : receipts) {
			if(selected.put(receipt.rule().parentOccurrence(), receipt) != null)
				return false;
		}
		for(CandidateSelectionReceipt receipt : receipts)
			for(CandidateRealizationReference support : receipt.supportClause().requiredInputSupport())
				if(!realizationReferencePossible(analysis, support, assignment, selected, remaining))
					return false;
		for(var fact : analysis.logicalTransientInputsInCanonicalOrder()) {
			boolean supported = fact.compatibility().stream().anyMatch(edge ->
				realizationReferencePossible(analysis, edge.sourceRealization(), assignment, selected, remaining)
					&& realizationReferencePossible(analysis, edge.readerRealization(), assignment, selected, remaining));
			if(!supported)
				return false;
		}
		return analysis.logicalBoundaryRealizations().canStillBeCompatible(assignment, selected, remaining);
	}

	public static boolean matchesRealization(CandidateRealizationReference reference,
		CandidateSelectionReceipt receipt) {
		return receipt != null && reference.equals(
			CandidateRealizationReference.of(receipt.rule(), receipt.realization()));
	}

	private static boolean realizationReferencePossible(PlacementAnalysis analysis,
		CandidateRealizationReference reference, Map<CompiledHopKey,PlacementState> assignment,
		Map<CompiledHopKey,CandidateSelectionReceipt> selected,
		Map<CompiledHopKey,List<PlacementState>> remaining) {
		CompiledHopKey owner = reference.rule().parentOccurrence();
		CandidateSelectionReceipt receipt = selected.get(owner);
		if(receipt != null)
			return matchesRealization(reference, receipt);
		PlacementState state = reference.realization().emissionState().placementState();
		PlacementState assigned = assignment.get(owner);
		if(assigned != null && !assigned.equals(state))
			return false;
		List<PlacementState> domain = remaining.get(owner);
		if(assigned == null && domain != null && !domain.contains(state))
			return false;
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFactsForParent(owner))
			if(fact.status() == CandidateEvaluationStatus.AVAILABLE && fact.key().equals(reference.rule()))
				for(CandidateEmissionFact emission : fact.allowedEmissionFacts())
					for(var realization : emission.realizations())
						if(reference.equals(CandidateRealizationReference.of(fact.key(), realization)))
							return true;
		return false;
	}

	/** Validate after both candidate and relocation receipts have been resolved. */
	public static void validateRealizationSelections(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment, Collection<CandidateSelectionReceipt> receipts,
		Collection<RelocationChoiceReceipt> choices) {
		if(!realizationsCanStillBeCompatible(analysis, assignment, receipts))
			throw new IllegalArgumentException("Selected candidate realizations violate transient compatibility");
		// This verifies the exact chosen actions as well as origin-residency/privacy;
		// relation support alone is not authority to emit an upload.
		RelocationSelections.resolveAndValidate(analysis, assignment, receipts, choices);
		if(!realizationActionsMatch(analysis, assignment, receipts, choices))
			throw new IllegalArgumentException("Selected relocation contradicts candidate realization input proof");
	}

	private static boolean realizationActionsMatch(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment, Collection<CandidateSelectionReceipt> receipts,
		Collection<RelocationChoiceReceipt> choices) {
		for(CandidateSelectionReceipt receipt : receipts)
			for(var binding : receipt.supportClause().inputBindings()) {
				if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.LOGICAL_TRANSIENT)
					continue;
				List<RelocationChoiceReceipt> matching = choices.stream().filter(choice ->
					choice.demand().consumer() == receipt.rule().parentOccurrence()
						&& choice.demand().inputPosition() == binding.inputPosition()).toList();
				if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION) {
					if(matching.stream().noneMatch(choice -> choice.action().equals(binding.relocationAction())))
						return false;
				}
				else for(RelocationChoiceReceipt choice : matching)
					for(RelocationAction action : analysis.graph().relocationActions())
						if(action.key().equals(choice.action())
							&& analysis.graph().isRelocationActive(action, assignment, receipts))
							return false;
			}
		return true;
	}

	static boolean realizationActionAllowed(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment, Collection<CandidateSelectionReceipt> receipts,
		RelocationDemandKey demand, RelocationActionKey actionKey) {
		for(CandidateSelectionReceipt receipt : receipts)
			if(receipt.rule().parentOccurrence() == demand.consumer())
				for(var binding : receipt.supportClause().inputBindings())
					if(binding.inputPosition() == demand.inputPosition()) {
						if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.RELOCATION
							&& !binding.relocationAction().equals(actionKey))
							return false;
						if(binding.kind() == PlacementIdentity.CandidateInputBindingKind.DIRECT)
							for(RelocationAction action : analysis.graph().relocationActions())
								if(action.key().equals(actionKey)
									&& analysis.graph().isRelocationActive(action, assignment, receipts))
									return false;
					}
		return true;
	}

	/** Canonical native-first completion used only when a planner supplies no explicit row receipt. */
	public static Selection selectNativeCanonical(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
			feasibleVariants(analysis, actionUniverse, assignment);
		List<List<CandidateSelectionReceipt>> domains = new ArrayList<>();
		for(var entry : byConsumer.entrySet()) {
			List<CandidateSelectionReceipt> ordered = new ArrayList<>(entry.getValue());
			ordered.sort((left, right) -> {
				int materialization = Integer.compare(presentInputCount(left), presentInputCount(right));
				return materialization != 0 ? materialization
					: Integer.compare(analysis.candidateReceiptRank(left), analysis.candidateReceiptRank(right));
			});
			domains.add(List.copyOf(ordered));
		}
		List<CandidateSelectionReceipt> selected = new ArrayList<>();
		if(!completeCompatibleRealizations(analysis, actionUniverse, assignment, domains, 0, selected))
			throw new IllegalStateException("No globally compatible native candidate realizations");
		selected.sort(java.util.Comparator.comparingInt(analysis::candidateReceiptRank));
		List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
			analysis, actionUniverse, assignment, selected,
			(demand, action) -> realizationActionAllowed(analysis, assignment, selected, demand, action));
		List<RelocationActionKey> emitted = RelocationSelections.emittedActions(
			analysis, actionUniverse, assignment, selected, choices);
		int materialized = selected.stream().mapToInt(CandidateSelections::presentInputCount).sum();
		int localMaterializations = LocalMaterializationSelections.physicalEmissionCount(
			analysis, assignment, selected);
		return new Selection(selected, choices, emitted, materialized,
			RelocationSelections.physicalEmissionCount(emitted), localMaterializations,
			foutMaterializationPhysicalEmissionCount(selected));
	}

	private static boolean completeCompatibleRealizations(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment, List<List<CandidateSelectionReceipt>> domains,
		int position, List<CandidateSelectionReceipt> selected) {
		if(!realizationsCanStillBeCompatible(analysis, assignment, selected))
			return false;
		if(position == domains.size()) {
			try {
				List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
					analysis, actions, assignment, selected,
					(demand, action) -> realizationActionAllowed(analysis, assignment, selected, demand, action));
				return realizationActionsMatch(analysis, assignment, selected, choices);
			}
			catch(RelocationSelections.InfeasibleRelocationSelectionException ex) {
				return false;
			}
		}
		for(CandidateSelectionReceipt receipt : domains.get(position)) {
			selected.add(receipt);
			if(completeCompatibleRealizations(analysis, actions, assignment, domains, position + 1, selected))
				return true;
			selected.remove(selected.size() - 1);
		}
		return false;
	}

	public static List<CandidateSelectionReceipt> resolveAndValidate(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse, Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		return resolveAndValidate(analysis, analysis.graph(), actionUniverse, assignment, selections);
	}

	public static List<CandidateSelectionReceipt> resolveAndValidate(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> feasible =
			feasibleVariants(analysis, authorityGraph, actionUniverse, assignment);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : Objects.requireNonNull(selections, "selections")) {
			Objects.requireNonNull(receipt, "candidate selection");
			CompiledHopKey consumer = receipt.rule().parentOccurrence();
			if(selected.put(consumer, receipt) != null)
				throw new IllegalArgumentException("Candidate consumer has multiple selected rows: "
					+ consumer.normalizedSignature());
			if(feasible.getOrDefault(consumer, List.of()).stream().noneMatch(candidate ->
				candidate.rule() == receipt.rule() && candidate.emission() == receipt.emission()
					&& candidate.realization() == receipt.realization()
					&& candidate.supportClause() == receipt.supportClause()))
				throw new IllegalArgumentException("Candidate selection is foreign, inactive, or unreachable: "
					+ receipt.normalizedSignature() + " feasible=" + feasible.getOrDefault(consumer, List.of())
						.stream().map(CandidateSelectionReceipt::normalizedSignature).toList()
					+ " reachability=" + reachabilityDetails(analysis, actionUniverse, assignment, receipt));
		}
		if(selected.size() != feasible.size() || !selected.keySet().containsAll(feasible.keySet()))
			throw new IllegalArgumentException("Candidate selections do not cover every active consumer: expected="
				+ feasible.size() + " selected=" + selected.size());
		if(!realizationsCanStillBeCompatible(analysis, assignment, selected.values()))
			throw new IllegalArgumentException("Candidate selection violates exact transient realization support");
		return analysis.canonicalCandidateReceipts(selected.values());
	}

	static List<CandidateSelectionReceipt> resolveAndValidatePartial(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse, Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		return resolveAndValidatePartial(analysis, analysis.graph(), actionUniverse, assignment, selections);
	}

	static List<CandidateSelectionReceipt> resolveAndValidatePartial(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		// A DP recurrence owns only its current parent/child closure. Validate its
		// selected rows directly against the immutable analysis-owned candidate domain;
		// constructing every feasible row for every sibling consumer here rebuilt the
		// same receipt/signature universe once per DP arm.
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : Objects.requireNonNull(selections, "selections")) {
			Objects.requireNonNull(receipt, "candidate selection");
			CompiledHopKey consumer = receipt.rule().parentOccurrence();
			if(selected.put(consumer, receipt) != null)
				throw new IllegalArgumentException("Candidate consumer has multiple selected rows");
			CandidateRuleFact fact = analysis.candidateRuleFacts().requireExact(
				consumer, receipt.rule().orderedInputs());
			PlacementState selectedState = assignment.get(consumer);
			boolean exactOwnedRow = fact.key() == receipt.rule()
				&& fact.status() == CandidateEvaluationStatus.AVAILABLE
				&& fact.allowedEmissionFacts().stream().anyMatch(emission -> emission == receipt.emission()
					&& emission.realizations().stream().anyMatch(realization -> realization == receipt.realization()
						&& realization.supportClauses().stream().anyMatch(clause -> clause == receipt.supportClause())))
				&& selectedState != null
				&& receipt.emission().emissionState().placementState().equals(selectedState);
			boolean reachable = exactOwnedRow
				&& foutMaterializationActionReachable(authorityGraph, fact, receipt, assignment, true)
				&& receiptReachable(analysis, actionUniverse, assignment, receipt, true);
			if(!reachable)
				throw new IllegalArgumentException("Candidate selection is foreign, inactive, or unreachable: "
					+ receipt.normalizedSignature()
					+ " reachability=" + reachabilityDetails(analysis, actionUniverse, assignment, receipt));
		}
		if(!realizationsCanStillBeCompatible(analysis, assignment, selected.values()))
			throw new IllegalArgumentException("Candidate selection violates exact transient realization support");
		return analysis.canonicalCandidateReceipts(selected.values());
	}

	public static List<CandidateSelectionReceipt> resolveAndValidate(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> selections) {
		return resolveAndValidate(analysis, analysis.graph().relocationActions(), assignment, selections);
	}

	/** True only when the exact selected candidate row requires this action alternative. */
	static boolean actionMatchesSelectedCandidate(RelocationAction action,
		PlacementIdentity.ObligationKey obligation,
		Map<CompiledHopKey,CandidateSelectionReceipt> selections) {
		CandidateSelectionReceipt selected = selections.get(obligation.consumer());
		return selected != null && actionMatchesSelectedCandidate(action, obligation, selected);
	}

	static Map<CompiledHopKey,CandidateSelectionReceipt> indexByConsumer(
		Collection<CandidateSelectionReceipt> selections) {
		Map<CompiledHopKey,CandidateSelectionReceipt> result = new IdentityHashMap<>();
		for(CandidateSelectionReceipt receipt : selections)
			if(result.put(receipt.rule().parentOccurrence(), receipt) != null)
				throw new IllegalArgumentException("Candidate consumer has multiple selected rows");
		return result;
	}

	private static boolean receiptReachable(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt receipt, boolean allowUnassigned) {
		// Exact relocation selection binds every materialized input of one FED
		// consumer to one physical worker-pool layout. Reject a row before any
		// selector can commit it when its relocation demands have no common pool.
		if(!realizationsCanStillBeCompatible(analysis, assignment, List.of(receipt)))
			return false;
		if(!RelocationSelections.candidateReceiptHasCommonPhysicalAnchor(actions, receipt))
			return false;
		// See candidateRowCanStillBeReachable: function arguments are forwarded by
		// the compiler-owned actual/formal boundary, not consumed by this Hop.
		if(analysis.isDmlFunctionCallBoundary(receipt.rule().parentOccurrence()))
			return true;
		if(receipt.emission().emissionState().placementState().execType()
			!= org.apache.sysds.common.Types.ExecType.FED)
			return true;
		PlacementCostSemantics.LatentWdivmmTransposePairFact latentWdivmm =
			PlacementCostSemantics.latentWdivmmTransposePairFact(
				analysis, receipt.rule().parentOccurrence());
		if(latentWdivmm != null && latentWdivmm.partitionedInputFType() != null
			&& !latentWdivmmRuntimeInputReachable(analysis, latentWdivmm,
				assignment, allowUnassigned))
			return false;
		PlacementCostSemantics.DirectWdivmmRuntimeFact directWdivmm =
			PlacementCostSemantics.directWdivmmRuntimeFact(
				analysis, receipt.rule().parentOccurrence());
		if(directWdivmm != null && !directWdivmmRuntimeInputReachable(analysis,
			directWdivmm, receipt.emission(), assignment,
			allowUnassigned))
			return false;
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			final int inputPosition = position;
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			FType required = input.fType();
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder()
				.stream().filter(edge -> edge.consumer() == receipt.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			// Logical function/transient inputs are validated by their exact candidate facts and
			// compiler-owned forwarding constraints rather than physical relocation actions.
			if(edges.isEmpty())
				continue;
			if(edges.size() != 1)
				throw new IllegalStateException("Candidate physical input edge is ambiguous");
			// The dynamic rewrite replaces this source-level inner-MM edge with the
			// exact fused weights FederationMap checked above. It cannot require a
			// receipt or relocation for an intermediate that no runtime instruction
			// consumes.
			if(PlacementCostSemantics.isLatentWdivmmTransposePairBoundary(analysis,
				edges.get(0).producer(), edges.get(0).consumer(), inputPosition))
				continue;
			// A projected action universe may retain another anchor for the same
			// coarse input state. It cannot discharge this receipt's exact binding.
			boolean receipted = actions.stream().anyMatch(action ->
				action.obligations().stream().anyMatch(obligation ->
					obligation.consumer() == receipt.rule().parentOccurrence()
						&& obligation.inputPosition() == inputPosition
						&& actionMatchesSelectedCandidate(action, obligation, receipt)));
			boolean direct = exactDirectBindingReachable(
				analysis, receipt, inputPosition, required, assignment, allowUnassigned)
				|| singlePhysicalInputDirectReachable(
					analysis, receipt.rule(), inputPosition, required, assignment, allowUnassigned);
			if(!receipted && !direct && !singleParametricFormalReceiptReachable(
				analysis, receipt.rule(), inputPosition, required, assignment, allowUnassigned))
				return false;
		}
		return true;
	}

	private static boolean latentWdivmmRuntimeInputReachable(PlacementAnalysis analysis,
		PlacementCostSemantics.LatentWdivmmTransposePairFact runtime,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		PlacementState selected = assignment.get(runtime.weights());
		if(selected != null)
			return selected.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& selected.fType() == runtime.partitionedInputFType();
		if(!allowUnassigned)
			return false;
		return analysis.graph().node(runtime.weights()).orElseThrow().legalAlternatives().stream()
			.anyMatch(state -> state.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& state.fType() == runtime.partitionedInputFType());
	}

	private static boolean directWdivmmRuntimeInputReachable(PlacementAnalysis analysis,
		PlacementCostSemantics.DirectWdivmmRuntimeFact runtime,
		PlacementAnalysis.CandidateEmissionFact ownerEmission,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		PlacementState owner = ownerEmission.emissionState().placementState();
		PlacementState selected = assignment.get(runtime.weights());
		if(selected != null)
			return PlacementCostSemantics.directWdivmmRuntimeAssignmentCompatible(
				runtime, owner, ownerEmission.executionFType(),
				ownerEmission.emissionState().derivedFedFout(), selected);
		if(owner.execType() != ExecType.FED)
			return owner.execType() == ExecType.CP;
		if(!allowUnassigned)
			return false;
		return analysis.graph().node(runtime.weights()).orElseThrow().legalAlternatives().stream()
			.anyMatch(state -> PlacementCostSemantics.directWdivmmRuntimeAssignmentCompatible(
				runtime, owner, ownerEmission.executionFType(),
				ownerEmission.emissionState().derivedFedFout(), state));
	}

	static boolean derivedFoutActionReachable(NeutralPlacementGraph graph,
		CandidateSelectionReceipt receipt) {
		return foutMaterializationActionReachable(graph, null, receipt, null, true);
	}

	private static boolean foutMaterializationActionReachable(NeutralPlacementGraph graph,
		CandidateRuleFact exactRule, CandidateSelectionReceipt receipt,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassignedOwner) {
		PlacementState selected = receipt.emission().emissionState().placementState();
		boolean cpFout = selected.execType() == org.apache.sysds.common.Types.ExecType.CP
			&& selected.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT;
		boolean requiresAction = receipt.emission().emissionState().derivedFedFout() || cpFout;
		if(!requiresAction)
			return receipt.emission().derivedFoutAction() == null;
		var expected = receipt.emission().derivedFoutAction();
		if(expected == null || expected.candidateRule() != receipt.rule()
			|| expected.producer() != receipt.rule().parentOccurrence()
			|| expected.targetPlacement() != selected)
			return false;
		if(exactRule != null && (exactRule.key() != receipt.rule()
			|| exactRule.allowedEmissionFacts().stream().noneMatch(source ->
				source.derivedFoutAction() == null
					&& source.emissionState().placementState() == expected.sourcePlacement())))
			return false;
		if(graph.derivedFoutMaterializationActions().stream()
			.filter(action -> action.key() == expected).count() != 1)
			return false;
		if(assignment == null)
			return true;
		PlacementState owner = assignment.get(expected.durableAnchorOwner());
		return owner == null ? allowUnassignedOwner : owner.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
			&& owner.fType() == expected.durableAnchorOwnerFType();
	}

	/**
	 * A compiled consumer inside a DML function may receive its only PRESENT matrix
	 * input from a formal read.  That FederationMap is runtime-parametric: the exact
	 * worker/range identity is supplied by each selected caller actual, so there is no
	 * single static relocation action to receipt.  This is legal only for one physical
	 * PRESENT input (there is no second pool with which it could disagree), and every
	 * formal/actual in the forwarding chain must already be FOUT with the required
	 * layout.  Multiple-input consumers still require an exact common-anchor action.
	 */
	private static boolean singleParametricFormalReceiptReachable(PlacementAnalysis analysis,
		CandidateRuleKey rule, int inputPosition, FType required,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		long presentPhysicalInputs = java.util.stream.IntStream.range(0,
			rule.orderedInputs().size()).filter(position -> {
				if(!rule.orderedInputs().get(position).present())
					return false;
				return analysis.compiledInputEdgesInCanonicalOrder().stream().anyMatch(edge ->
					edge.consumer() == rule.parentOccurrence()
						&& edge.inputPosition() == position);
			}).count();
		if(presentPhysicalInputs != 1)
			return false;
		List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
			.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == rule.parentOccurrence()
				&& edge.inputPosition() == inputPosition).toList();
		if(edges.size() != 1)
			return false;
		return parametricFormalChainFoutCompatible(analysis, edges.get(0).producer(), required,
			assignment, allowUnassigned,
			Collections.newSetFromMap(new IdentityHashMap<CompiledHopKey,Boolean>()));
	}

	/**
	 * A unary FED consumer can execute directly on its sole physical FOUT input; no upload/refed
	 * action exists or is needed in that case. Multi-input rows deliberately remain action-backed
	 * because matching FType alone does not prove that independent FederationMaps share a pool.
	 */
	private static boolean singlePhysicalInputDirectReachable(PlacementAnalysis analysis,
		CandidateRuleKey rule, int inputPosition, FType required,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		if(rule.orderedInputs().stream().filter(CandidateInputState::present).count() != 1)
			return false;
		List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis
			.compiledInputEdgesInCanonicalOrder().stream()
			.filter(edge -> edge.consumer() == rule.parentOccurrence()
				&& edge.inputPosition() == inputPosition).toList();
		if(edges.size() != 1)
			return false;
		CompiledHopKey producer = edges.get(0).producer();
		PlacementState selected = assignment.get(producer);
		if(selected != null)
			return selected.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& selected.fType() == required;
		if(!allowUnassigned)
			return false;
		return analysis.graph().node(producer).orElseThrow().legalAlternatives().stream().anyMatch(state ->
			state.output()
				== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& state.fType() == required);
	}

	/** Exact builder-owned DIRECT bindings retain multi-input common-pool authority. */
	private static boolean exactDirectBindingReachable(PlacementAnalysis analysis,
		CandidateSelectionReceipt receipt, int inputPosition, FType required,
		Map<CompiledHopKey,PlacementState> assignment, boolean allowUnassigned) {
		for(var binding : receipt.supportClause().inputBindings()) {
			if(binding.inputPosition() != inputPosition
				|| binding.kind() != PlacementIdentity.CandidateInputBindingKind.DIRECT
				|| binding.source().realization().emissionState().placementState().fType() != required)
				continue;
			CompiledHopKey producer = binding.source().rule().parentOccurrence();
			PlacementState selected = assignment.get(producer);
			if(selected != null)
				return selected.output() == FederatedOutput.FOUT && selected.fType() == required;
			if(allowUnassigned && analysis.graph().node(producer).stream()
				.flatMap(node -> node.legalAlternatives().stream())
				.anyMatch(state -> state.output() == FederatedOutput.FOUT
					&& state.fType() == required))
				return true;
		}
		return false;
	}

	private static boolean parametricFormalChainFoutCompatible(PlacementAnalysis analysis,
		CompiledHopKey formal, FType required, Map<CompiledHopKey,PlacementState> assignment,
		boolean allowUnassigned, Set<CompiledHopKey> visiting) {
		List<PlacementAnalysis.LogicalFunctionInputFact> incoming = analysis
			.logicalFunctionInputsInCanonicalOrder().stream()
			.filter(input -> input.targetRead() == formal).toList();
		if(incoming.isEmpty())
			return false;
		PlacementState formalState = assignment.get(formal);
		if(!foutCompatible(formalState, required, allowUnassigned))
			return false;
		if(!visiting.add(formal))
			return true;
		try {
			for(PlacementAnalysis.LogicalFunctionInputFact input : incoming) {
				PlacementState actualState = assignment.get(input.sourceArgument());
				if(!foutCompatible(actualState, required, allowUnassigned))
					return false;
				boolean nestedFormal = analysis.logicalFunctionInputsInCanonicalOrder().stream()
					.anyMatch(nested -> nested.targetRead() == input.sourceArgument());
				if(nestedFormal && !parametricFormalChainFoutCompatible(analysis,
					input.sourceArgument(), required, assignment, allowUnassigned, visiting))
					return false;
			}
			return true;
		}
		finally {
			visiting.remove(formal);
		}
	}

	private static boolean foutCompatible(PlacementState state, FType required,
		boolean allowUnassigned) {
		return state == null ? allowUnassigned
			: state.output() == org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
				&& state.fType() == required;
	}

	private static List<String> reachabilityDetails(PlacementAnalysis analysis,
		Collection<RelocationAction> actions, Map<CompiledHopKey,PlacementState> assignment,
		CandidateSelectionReceipt receipt) {
		List<String> details = new ArrayList<>();
		for(int position = 0; position < receipt.rule().orderedInputs().size(); position++) {
			CandidateInputState input = receipt.rule().orderedInputs().get(position);
			if(!input.present())
				continue;
			FType required = input.fType();
			final int inputPosition = position;
			List<PlacementAnalysis.CompiledInputEdgeFact> edges = analysis.compiledInputEdgesInCanonicalOrder()
				.stream().filter(edge -> edge.consumer() == receipt.rule().parentOccurrence()
					&& edge.inputPosition() == inputPosition).toList();
			List<String> actionTypes = actions.stream().filter(action -> action.obligations().stream()
				.anyMatch(obligation -> obligation.consumer() == receipt.rule().parentOccurrence()
					&& obligation.inputPosition() == inputPosition))
				.map(action -> action.key().materializationFType() + "->"
					+ action.key().targetPlacement().normalizedSignature()).sorted().toList();
			CompiledHopKey sourceKey = edges.size() == 1 ? edges.get(0).producer() : null;
			Hop sourceHop = sourceKey == null ? null : analysis.hop(sourceKey).orElse(null);
			List<String> sourceOccurrenceSelections = sourceHop == null ? List.of()
				: analysis.occurrences().stream()
					.filter(occurrence -> occurrence.hop() == sourceHop)
					.map(occurrence -> {
						NeutralPlacementGraph.Node node = analysis.graph().node(occurrence.key()).orElseThrow();
						return occurrence.key().emittedHopInstance() + "=" + assignment.get(occurrence.key())
							+ "[kind=" + node.kind() + ",emitted=" + node.emittedWork() + ']';
					}).sorted().toList();
			details.add("input=" + position + ",required=" + required + ",edges=" + edges.size()
				+ ",consumer=" + receipt.rule().parentOccurrence().normalizedSignature()
				+ ",sourceKey=" + (sourceKey == null ? "-" : sourceKey.normalizedSignature())
				+ ",source=" + (edges.size() == 1 ? assignment.get(edges.get(0).producer()) : "-")
				+ ",sourceOccurrences=" + sourceOccurrenceSelections
				+ ",actions=" + actionTypes);
		}
		return List.copyOf(details);
	}

	private static int presentInputCount(CandidateSelectionReceipt receipt) {
		return (int)receipt.rule().orderedInputs().stream().filter(CandidateInputState::present).count();
	}

	private static final class Search {
		private final long searchId;
		private final PlacementAnalysis analysis;
		private final Map<CompiledHopKey,PlacementState> assignment;
		private final List<CompiledHopKey> consumers;
		private final Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants;
		private final RelocationSelections.CandidateProblemIndex relocationProblems;
		private final RelocationSelections.ExactEmissionScorer relocationScorer;
		private final LocalMaterializationSelections.ExactPhysicalEmissionScorer
			localMaterializationScorer;
		private final int physicalEmissionLowerBound;
		private final List<CompiledHopKey> fixedConsumers;
		private final List<CompiledHopKey> variableConsumers;
		private final List<List<CompiledHopKey>> interactionComponents;
		private final Map<CompiledHopKey,CandidateSelectionReceipt> selectedByConsumer =
			new IdentityHashMap<>();
		private final Set<CandidateSelectionReceipt> selectedReceipts =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<CandidateSelectionReceipt,Integer> foutEmissionIds =
			new IdentityHashMap<>();
		private final int[] foutEmissionRefs;
		private int foutEmissionCount;
		private int selectedRelocationDemandRows;
		private final Map<CandidateSelectionReceipt,Integer> candidateRanks = new IdentityHashMap<>();
		private final Map<CandidateSelectionReceipt,Integer> presentInputCounts =
			new IdentityHashMap<>();
		private final Map<CandidateSelectionReceipt,Integer> inputPreferences =
			new IdentityHashMap<>();
		private final Map<CandidateSelectionReceipt,Integer> alignedPreferences =
			new IdentityHashMap<>();
		private final Map<CandidateSelectionReceipt,List<CandidateRealizationReference>> requiredSupports =
			new IdentityHashMap<>();
		private Selection best;
		private boolean bestCanonicalized;
		private int bestPhysicalEmissionCount = Integer.MAX_VALUE;
		private long evaluatedLeaves;
		private long incumbentMaterializations;

		private Search(PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
			List<RelocationAction> actions,
			Map<CompiledHopKey,PlacementState> assignment, List<CompiledHopKey> consumers,
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants,
			RelocationSelections.CanonicalOrderIndex relocationOrder,
			PartialReachabilityIndex reachabilityIndex) {
			this.analysis = analysis;
			this.searchId = EXACT_SEARCH_IDS.incrementAndGet();
			this.assignment = assignment;
			this.consumers = consumers;
			this.variants = variants;
			Objects.requireNonNull(relocationOrder, "relocationOrder");
			List<CandidateSelectionReceipt> candidateUniverse = variants.values().stream()
				.flatMap(Collection::stream).toList();
			this.relocationProblems = RelocationSelections.candidateProblemIndex(
				analysis, authorityGraph, actions, assignment,
				candidateUniverse, relocationOrder);
			this.relocationScorer = relocationProblems.newExactEmissionScorer();
			this.localMaterializationScorer = reachabilityIndex == null
				? LocalMaterializationSelections.exactPhysicalEmissionScorer(
					analysis, assignment, candidateUniverse)
				: LocalMaterializationSelections.exactPhysicalEmissionScorer(
					reachabilityIndex.localMaterializationIndex,
					assignment, candidateUniverse);
			this.physicalEmissionLowerBound =
				relocationProblems.unavoidableCombinedPhysicalEmissionCount(variants);
			for(CompiledHopKey consumer : consumers) {
				List<CandidateSelectionReceipt> ordered = variants.get(consumer);
				for(int rank = 0; rank < ordered.size(); rank++) {
					CandidateSelectionReceipt receipt = ordered.get(rank);
					candidateRanks.put(receipt, rank);
					int presentInputs = CandidateSelections.presentInputCount(receipt);
					presentInputCounts.put(receipt, presentInputs);
					inputPreferences.put(receipt,
						(assignment.get(consumer).execType() == ExecType.FED ? 1 : -1) * presentInputs);
					alignedPreferences.put(receipt,
						allPresentRelocationsAnchorAligned(analysis,
							authorityGraph.relocationActions(), assignment, receipt) ? 1 : 0);
					requiredSupports.put(receipt, receipt.supportClause().requiredInputSupport());
				}
			}
			Map<DerivedFoutMaterializationActionKey,Integer> physicalFoutIds = new HashMap<>();
			for(List<CandidateSelectionReceipt> ordered : variants.values())
				for(CandidateSelectionReceipt receipt : ordered) {
					DerivedFoutMaterializationActionKey action =
						receipt.emission().derivedFoutAction();
					if(action != null)
						foutEmissionIds.put(receipt, physicalFoutIds.computeIfAbsent(action,
							ignored -> physicalFoutIds.size()));
				}
			this.foutEmissionRefs = new int[physicalFoutIds.size()];
			List<CompiledHopKey> fixed = new ArrayList<>();
			List<CompiledHopKey> variable = new ArrayList<>();
			for(CompiledHopKey consumer : consumers) {
				List<CandidateSelectionReceipt> rows = variants.get(consumer);
				(rows.size() == 1 ? fixed : variable).add(consumer);
			}
			this.fixedConsumers = List.copyOf(fixed);
			this.variableConsumers = List.copyOf(variable);
			this.interactionComponents = exactInteractionComponents();
			if(FederatedPlannerTrace.isEnabled()
				&& (searchId <= 4 || (searchId & (searchId - 1L)) == 0L)) {
				long product = 1L;
				List<Integer> domainSizes = new ArrayList<>(consumers.size());
				for(CompiledHopKey consumer : consumers) {
					int size = variants.get(consumer).size();
					domainSizes.add(size);
					product = product > Long.MAX_VALUE / size ? Long.MAX_VALUE : product * size;
				}
				FederatedPlannerTrace.logGlobal("Candidate-Search-Start",
					"id=" + searchId + " consumers=" + consumers.size()
						+ " product=" + product + " lowerBound=" + physicalEmissionLowerBound
						+ " domains=" + domainSizes + " components="
						+ interactionComponents.stream().map(this::componentProduct).toList());
			}
		}

		private long componentProduct(List<CompiledHopKey> component) {
			long product = 1;
			for(CompiledHopKey consumer : component)
				product = saturatedProduct(product, variants.get(consumer).size());
			return product;
		}

		/** Maximum-cardinality order over exact interaction factors for fast CSP incumbents. */
		private List<CompiledHopKey> incumbentSearchOrder(List<CompiledHopKey> component) {
			Map<CompiledHopKey,Set<CompiledHopKey>> neighbors = new IdentityHashMap<>();
			for(CompiledHopKey consumer : component)
				neighbors.put(consumer, Collections.newSetFromMap(new IdentityHashMap<>()));
			Map<Long,CompiledHopKey> relocationOwners = new HashMap<>();
			Map<Integer,CompiledHopKey> localOwners = new HashMap<>();
			Map<Integer,CompiledHopKey> foutOwners = new HashMap<>();
			for(CompiledHopKey consumer : component) {
				for(CandidateSelectionReceipt receipt : variants.get(consumer)) {
					for(CandidateRealizationReference support : requiredSupports.get(receipt))
						addComponentNeighbor(neighbors, consumer,
							support.rule().parentOccurrence());
					for(long token : relocationProblems.exactInteractionTokens(receipt))
						addComponentFactorNeighbor(neighbors, relocationOwners, token, consumer);
					for(int producer : localMaterializationScorer.exactInteractionProducerIds(receipt))
						addComponentFactorNeighbor(neighbors, localOwners, producer, consumer);
					Integer fout = foutEmissionIds.get(receipt);
					if(fout != null)
						addComponentFactorNeighbor(neighbors, foutOwners, fout, consumer);
				}
			}
			for(var relation : analysis.logicalBoundaryRealizations().relations())
				addComponentNeighbor(neighbors, relation.source(), relation.target());
			List<CompiledHopKey> order = new ArrayList<>(component.size());
			Set<CompiledHopKey> remaining = Collections.newSetFromMap(new IdentityHashMap<>());
			remaining.addAll(component);
			while(!remaining.isEmpty()) {
				CompiledHopKey best = null;
				int bestSelectedNeighbors = -1;
				int bestDegree = -1;
				int bestDomain = Integer.MAX_VALUE;
				for(CompiledHopKey candidate : component) {
					if(!remaining.contains(candidate))
						continue;
					int selectedNeighbors = (int)neighbors.get(candidate).stream()
						.filter(neighbor -> !remaining.contains(neighbor)).count();
					int degree = neighbors.get(candidate).size();
					int domain = variants.get(candidate).size();
					if(selectedNeighbors > bestSelectedNeighbors
						|| selectedNeighbors == bestSelectedNeighbors && (degree > bestDegree
							|| degree == bestDegree && domain < bestDomain)) {
						best = candidate;
						bestSelectedNeighbors = selectedNeighbors;
						bestDegree = degree;
						bestDomain = domain;
					}
				}
				order.add(best);
				remaining.remove(best);
			}
			return List.copyOf(order);
		}

		private static void addComponentNeighbor(
			Map<CompiledHopKey,Set<CompiledHopKey>> neighbors,
			CompiledHopKey left, CompiledHopKey right) {
			if(left == right || !neighbors.containsKey(left) || !neighbors.containsKey(right))
				return;
			neighbors.get(left).add(right);
			neighbors.get(right).add(left);
		}

		private static <T> void addComponentFactorNeighbor(
			Map<CompiledHopKey,Set<CompiledHopKey>> neighbors, Map<T,CompiledHopKey> owners,
			T factor, CompiledHopKey consumer) {
			CompiledHopKey owner = owners.putIfAbsent(factor, consumer);
			if(owner != null)
				addComponentNeighbor(neighbors, owner, consumer);
		}

		private void solve() {
			for(CompiledHopKey consumer : fixedConsumers)
				push(consumer, variants.get(consumer).get(0));
			List<CompiledHopKey> selectedVariables = new ArrayList<>(variableConsumers.size());
			try {
				for(List<CompiledHopKey> component : interactionComponents) {
					List<CompiledHopKey> factorOrder = incumbentSearchOrder(component);
					ComponentSearch probe = new ComponentSearch(component, factorOrder, true);
					probe.solve(0);
					probe.requireBest();
					List<CandidateSelectionReceipt> winner;
					if(probe.provesZeroEmissionPreferenceOptimum())
						winner = canonicalizeProvenZeroEmissionOptimum(component, factorOrder, probe);
					else {
						ComponentSearch componentSearch = new ComponentSearch(component, factorOrder, false);
						componentSearch.seedFrom(probe);
						componentSearch.solve(0);
						winner = componentSearch.requireBest();
					}
					for(int index = 0; index < component.size(); index++) {
						CompiledHopKey consumer = component.get(index);
						push(consumer, winner.get(index));
						selectedVariables.add(consumer);
					}
				}
				materializeBest();
			}
			finally {
				for(int index = selectedVariables.size() - 1; index >= 0; index--) {
					CompiledHopKey consumer = selectedVariables.get(index);
					pop(consumer, selectedByConsumer.get(consumer));
				}
				for(int index = fixedConsumers.size() - 1; index >= 0; index--) {
					CompiledHopKey consumer = fixedConsumers.get(index);
					pop(consumer, variants.get(consumer).get(0));
				}
			}
		}

		/**
		 * Lexicographic self-reduction for a proven zero-emission optimum. Each
		 * canonical variable tries only rows smaller than the current exact witness;
		 * a factor-ordered target search proves whether that prefix has an optimal
		 * completion. The retained prefix is therefore the canonical optimum without
		 * enumerating the full canonical Cartesian product.
		 */
		private List<CandidateSelectionReceipt> canonicalizeProvenZeroEmissionOptimum(
			List<CompiledHopKey> component, List<CompiledHopKey> factorOrder,
			ComponentSearch optimum) {
			List<CandidateSelectionReceipt> witness = optimum.requireBest();
			int targetInput = optimum.bestInputPreference;
			int targetAligned = optimum.bestAlignedPreference;
			int fixedInput = 0;
			int fixedAligned = 0;
			List<CompiledHopKey> fixed = new ArrayList<>(component.size());
			try {
				for(int canonicalIndex = 0; canonicalIndex < component.size(); canonicalIndex++) {
					CompiledHopKey consumer = component.get(canonicalIndex);
					CandidateSelectionReceipt incumbent = witness.get(canonicalIndex);
					int incumbentRank = candidateRanks.get(incumbent);
					CandidateSelectionReceipt chosen = incumbent;
					for(int rank = 0; rank < incumbentRank; rank++) {
						CandidateSelectionReceipt candidate = variants.get(consumer).get(rank);
						push(consumer, candidate);
						List<CompiledHopKey> remainingOrder = factorOrder.stream()
							.filter(key -> !selectedByConsumer.containsKey(key)).toList();
						ComponentSearch target = new ComponentSearch(component, remainingOrder,
							false, targetInput, targetAligned, 0);
						target.solveWithBase(Math.addExact(fixedInput, inputPreferences.get(candidate)),
							Math.addExact(fixedAligned, alignedPreferences.get(candidate)));
						pop(consumer, candidate);
						if(target.foundTarget()) {
							witness = target.requireBest();
							chosen = candidate;
							break;
						}
					}
					push(consumer, chosen);
					fixed.add(consumer);
					fixedInput = Math.addExact(fixedInput, inputPreferences.get(chosen));
					fixedAligned = Math.addExact(fixedAligned, alignedPreferences.get(chosen));
				}
				return selectedComponentRows(component);
			}
			finally {
				for(int index = fixed.size() - 1; index >= 0; index--) {
					CompiledHopKey consumer = fixed.get(index);
					pop(consumer, selectedByConsumer.get(consumer));
				}
			}
		}

		private void materializeBest() {
			if(!realizationsCanStillBeCompatible(analysis, assignment, selectedByConsumer.values()))
				throw new IllegalStateException("Candidate components lost transient compatibility");
			if(relocationScorer.hasAnchorConflict())
				throw new IllegalStateException(
					"Exact component candidate search retained incompatible relocation anchors");
			int relocationMaterializations = relocationScorer.minimumPhysicalEmissionCount();
			if(relocationMaterializations == Integer.MAX_VALUE)
				throw new IllegalStateException(
					"Exact component candidate search has no relocation completion");
			int localMaterializations = localMaterializationScorer.physicalEmissionCount();
			bestPhysicalEmissionCount = Math.addExact(Math.addExact(relocationMaterializations,
				localMaterializations), foutEmissionCount);
			best = new Selection(selectedInConsumerOrder(), List.of(), List.of(),
				selectedByConsumer.values().stream().mapToInt(CandidateSelections::presentInputCount).sum(),
				relocationMaterializations, localMaterializations,
				foutEmissionCount);
		}

		/**
		 * Solves one connected factor component exactly. Components remain selected
		 * after they are solved; because no later component touches any of their
		 * physical-emission or feasibility factors, those contributions are constant
		 * while every later component is optimized.
		 */
		private final class ComponentSearch {
			private final List<CompiledHopKey> component;
			private final List<CompiledHopKey> searchOrder;
			private final boolean stopAfterFirstFeasible;
			private final boolean canonicalTraversal;
			private final Integer targetInputPreference;
			private final Integer targetAlignedPreference;
			private final Integer targetPhysicalEmissionCount;
			private final int[] maximumInputPreferenceSuffix;
			private final int[] maximumAlignedPreferenceAtMaximumInputSuffix;
			private final Map<CompiledHopKey,List<CandidateSelectionReceipt>> lowerBoundVariants;
			private final List<CandidateSelectionReceipt> lowerBoundCandidateUniverse;
			private List<CandidateSelectionReceipt> bestRows;
			private final int[] rejected = new int[4];
			private int bestPhysicalEmissionCount = Integer.MAX_VALUE;
			private int bestInputPreference = Integer.MIN_VALUE;
			private int bestAlignedPreference = Integer.MIN_VALUE;
			private boolean provenOptimal;

			private ComponentSearch(List<CompiledHopKey> component,
				List<CompiledHopKey> searchOrder, boolean stopAfterFirstFeasible) {
				this(component, searchOrder, stopAfterFirstFeasible, null, null, null);
			}

			private ComponentSearch(List<CompiledHopKey> component,
				List<CompiledHopKey> searchOrder, boolean stopAfterFirstFeasible,
				Integer targetInputPreference, Integer targetAlignedPreference,
				Integer targetPhysicalEmissionCount) {
				this.component = component;
				this.searchOrder = searchOrder;
				this.stopAfterFirstFeasible = stopAfterFirstFeasible;
				this.canonicalTraversal = component.equals(searchOrder)
					&& component.stream().noneMatch(selectedByConsumer::containsKey);
				this.targetInputPreference = targetInputPreference;
				this.targetAlignedPreference = targetAlignedPreference;
				this.targetPhysicalEmissionCount = targetPhysicalEmissionCount;
				Set<CompiledHopKey> orderedKeys =
					Collections.newSetFromMap(new IdentityHashMap<>());
				orderedKeys.addAll(searchOrder);
				long unselected = component.stream()
					.filter(key -> !selectedByConsumer.containsKey(key)).count();
				if(searchOrder.size() != unselected || orderedKeys.size() != unselected
					|| component.stream().filter(key -> !selectedByConsumer.containsKey(key))
						.anyMatch(key -> !orderedKeys.contains(key)))
					throw new IllegalArgumentException("Component search order is not a permutation");
				Map<CompiledHopKey,List<CandidateSelectionReceipt>> bounded = new LinkedHashMap<>();
				selectedByConsumer.forEach((consumer, receipt) -> bounded.put(consumer, List.of(receipt)));
				for(CompiledHopKey consumer : component)
					bounded.putIfAbsent(consumer, variants.get(consumer));
				this.lowerBoundVariants = Collections.unmodifiableMap(bounded);
				this.lowerBoundCandidateUniverse = bounded.values().stream()
					.flatMap(Collection::stream).toList();
				maximumInputPreferenceSuffix = new int[searchOrder.size() + 1];
				maximumAlignedPreferenceAtMaximumInputSuffix = new int[searchOrder.size() + 1];
				for(int index = searchOrder.size() - 1; index >= 0; index--) {
					List<CandidateSelectionReceipt> rows = variants.get(searchOrder.get(index));
					int maximumInput = rows.stream().mapToInt(inputPreferences::get).max().orElseThrow();
					int maximumAligned = rows.stream()
						.filter(receipt -> inputPreferences.get(receipt) == maximumInput)
						.mapToInt(alignedPreferences::get).max().orElseThrow();
					maximumInputPreferenceSuffix[index] = Math.addExact(maximumInput,
						maximumInputPreferenceSuffix[index + 1]);
					maximumAlignedPreferenceAtMaximumInputSuffix[index] = Math.addExact(maximumAligned,
						maximumAlignedPreferenceAtMaximumInputSuffix[index + 1]);
				}
			}

			private void seedFrom(ComponentSearch seed) {
				this.bestRows = seed.requireBest();
				this.bestPhysicalEmissionCount = seed.bestPhysicalEmissionCount;
				this.bestInputPreference = seed.bestInputPreference;
				this.bestAlignedPreference = seed.bestAlignedPreference;
			}

			private void solve(int index) {
				solve(index, 0, 0);
			}

			private void solveWithBase(int inputPreference, int alignedPreference) {
				solve(0, inputPreference, alignedPreference);
			}

			private boolean provesZeroEmissionPreferenceOptimum() {
				return bestRows != null && bestPhysicalEmissionCount == 0
					&& bestInputPreference == maximumInputPreferenceSuffix[0]
					&& bestAlignedPreference == maximumAlignedPreferenceAtMaximumInputSuffix[0];
			}

			private boolean foundTarget() {
				return bestRows != null && targetInputPreference != null
					&& bestInputPreference == targetInputPreference
					&& bestAlignedPreference == targetAlignedPreference
					&& bestPhysicalEmissionCount == targetPhysicalEmissionCount;
			}

			private void solve(int index, int inputPreference, int alignedPreference) {
				if(provenOptimal)
					return;
				// An exact RELOCATION binding forces its named action active, and a
				// derived FOUT owns a physical emission. Neither can complete a proven
				// zero-emission target, regardless of the unselected suffix.
				if(targetPhysicalEmissionCount != null && targetPhysicalEmissionCount == 0
					&& (selectedRelocationDemandRows != 0 || foutEmissionCount != 0))
					return;
				if(bestRows != null) {
					int maximumInput = Math.addExact(inputPreference,
						maximumInputPreferenceSuffix[index]);
					if(maximumInput < bestInputPreference
						|| maximumInput == bestInputPreference && Math.addExact(alignedPreference,
							maximumAlignedPreferenceAtMaximumInputSuffix[index]) < bestAlignedPreference) {
						return;
					}
				}
				if(relocationScorer.hasAnchorConflict()) { rejected[0]++; return; }
				if(!realizationsCanStillBeCompatible(analysis, assignment, selectedByConsumer.values())) {
					rejected[1]++; return;
				}
				if(!candidateDomainsCanStillSupportSelectedRows()) {
					rejected[2]++; return;
				}
				if(bestRows != null) {
					int maximumInput = Math.addExact(inputPreference,
						maximumInputPreferenceSuffix[index]);
					int maximumAligned = Math.addExact(alignedPreference,
						maximumAlignedPreferenceAtMaximumInputSuffix[index]);
					if(maximumInput == bestInputPreference && maximumAligned == bestAlignedPreference) {
						int lowerBound = Math.addExact(
							relocationProblems.unavoidableCombinedPhysicalEmissionCount(
								lowerBoundVariants, selectedByConsumer),
							localMaterializationScorer.minimumPossiblePhysicalEmissionCount(
								lowerBoundCandidateUniverse));
						if(lowerBound > bestPhysicalEmissionCount
							|| lowerBound == bestPhysicalEmissionCount
								&& !canonicalCompletionCanBeatBest()) {
							return;
						}
					}
				}
				if(index < searchOrder.size()) {
					CompiledHopKey consumer = searchOrder.get(index);
					for(CandidateSelectionReceipt receipt : variants.get(consumer)) {
						push(consumer, receipt);
						solve(index + 1,
							Math.addExact(inputPreference, inputPreferences.get(receipt)),
							Math.addExact(alignedPreference, alignedPreferences.get(receipt)));
						pop(consumer, receipt);
						if(provenOptimal)
							break;
					}
					return;
				}
				evaluatedLeaves++;
				int relocationMaterializations = relocationScorer.minimumPhysicalEmissionCount();
				if(relocationMaterializations == Integer.MAX_VALUE) {
					rejected[3]++;
					if(FederatedPlannerTrace.isEnabled() && rejected[3] == 1)
						FederatedPlannerTrace.logGlobal("Candidate-RelocationReject", analysis.graph().relocationActions().stream()
							.filter(action -> action.obligations().stream().anyMatch(ob -> selectedByConsumer.containsKey(ob.consumer())))
							.map(action -> action.key().sourceValueVersion().lexicalVariable() + ":" + action.key().materializationFType()
								+ "|direct=" + action.directSourcePlacements() + "|active="
								+ analysis.graph().isRelocationActive(action, assignment, selectedByConsumer.values())).toList().toString());
					return;
				}
				int localMaterializations = localMaterializationScorer.physicalEmissionCount();
				int physicalEmissions = Math.addExact(Math.addExact(relocationMaterializations,
					localMaterializations), foutEmissionCount);
				List<CandidateSelectionReceipt> rows = selectedComponentRows(component);
				if(inputPreference > bestInputPreference
					|| inputPreference == bestInputPreference && (alignedPreference > bestAlignedPreference
						|| alignedPreference == bestAlignedPreference && (physicalEmissions < bestPhysicalEmissionCount
							|| physicalEmissions == bestPhysicalEmissionCount
								&& compareComponentRows(rows, bestRows) < 0))) {
					bestInputPreference = inputPreference;
					bestAlignedPreference = alignedPreference;
					bestPhysicalEmissionCount = physicalEmissions;
					bestRows = rows;
					incumbentMaterializations++;
					// Probe and target searches stop only at their requested proof point.
					// A genuinely canonical traversal may also stop at the first leaf that
					// attains both preference upper bounds and the zero-emission lower bound.
					provenOptimal = stopAfterFirstFeasible || foundTarget() || canonicalTraversal
						&& inputPreference == maximumInputPreferenceSuffix[0]
						&& alignedPreference == maximumAlignedPreferenceAtMaximumInputSuffix[0]
						&& physicalEmissions == 0;
				}
			}

			/**
			 * Necessary arc-consistency check over the actual exact candidate domains.
			 * The generic realization validator intentionally admits any realization in
			 * the analysis while its owner is unselected. Exact candidate selection is
			 * narrower: a required reference must still have a row in the owner's
			 * remaining domain, and every unselected row variable must retain at least
			 * one row compatible with the already selected owners.
			 */
			private boolean candidateDomainsCanStillSupportSelectedRows() {
				for(CandidateSelectionReceipt selected : selectedByConsumer.values())
					if(!rowSupportCompatibleWithCandidateDomains(selected))
						return false;
				for(CompiledHopKey consumer : component)
					if(!selectedByConsumer.containsKey(consumer)
						&& variants.get(consumer).stream()
							.noneMatch(this::rowSupportCompatibleWithCandidateDomains))
						return false;
				return true;
			}

			private boolean rowSupportCompatibleWithCandidateDomains(
				CandidateSelectionReceipt receipt) {
				for(CandidateRealizationReference support : requiredSupports.get(receipt)) {
					CompiledHopKey owner = support.rule().parentOccurrence();
					CandidateSelectionReceipt selected = selectedByConsumer.get(owner);
					if(selected != null) {
						if(!matchesRealization(support, selected))
							return false;
						continue;
					}
					List<CandidateSelectionReceipt> domain = variants.get(owner);
					if(domain != null && domain.stream().noneMatch(candidate ->
						matchesRealization(support, candidate)))
						return false;
				}
				return true;
			}

			/**
			 * Optimistic canonical tuple for an arbitrary factor-order prefix. Every
			 * unselected variable is assigned its rank-zero row. If even this tuple is
			 * not smaller than the incumbent, no completion can improve the exact
			 * canonical tie-break at an equal objective and physical lower bound.
			 */
			private boolean canonicalCompletionCanBeatBest() {
				if(bestRows == null)
					return true;
				for(int index = 0; index < component.size(); index++) {
					CandidateSelectionReceipt selected = selectedByConsumer.get(component.get(index));
					int optimisticRank = selected == null ? 0 : candidateRanks.get(selected);
					int incumbentRank = candidateRanks.get(bestRows.get(index));
					if(optimisticRank != incumbentRank)
						return optimisticRank < incumbentRank;
				}
				return false;
			}

			private List<CandidateSelectionReceipt> requireBest() {
				if(bestRows == null)
					throw new IllegalStateException(
						"Candidate interaction component has no exact legal assignment|rejected="
							+ java.util.Arrays.toString(rejected) + "|component=" + component.stream()
							.map(key -> analysis.hop(key).map(hop -> hop.getOpString() + ":" + hop.getName()).orElse("synthetic")
								+ "=" + assignment.get(key) + "|rows=" + variants.get(key).stream().map(receipt ->
									receipt.rule().orderedInputs() + ":" + receipt.realization().key().hashCode()
									+ ":" + receipt.supportClause().requiredInputSupport().stream().map(ref ->
										analysis.hop(ref.rule().parentOccurrence()).map(hop -> hop.getOpString() + ":" + hop.getName()).orElse("synthetic")
										+ ":" + ref.realization().hashCode()).toList()).toList()).toList()
							+ "|fixedInvalid=" + selectedByConsumer.values().stream()
								.flatMap(receipt -> receipt.supportClause().requiredInputSupport().stream())
								.filter(ref -> !realizationReferencePossible(analysis, ref, assignment, selectedByConsumer, Map.of()))
								.map(ref -> ref.normalizedSignature()).toList());
				return bestRows;
			}
		}

		private List<List<CompiledHopKey>> exactInteractionComponents() {
			int size = variableConsumers.size();
			int[] parent = new int[size];
			for(int index = 0; index < size; index++)
				parent[index] = index;
			Map<Long,Integer> relocationOwners = new HashMap<>();
			Map<Integer,Integer> localOwners = new HashMap<>();
			Map<Integer,Integer> foutOwners = new HashMap<>();
			Map<CompiledHopKey,Integer> realizationOwners = new IdentityHashMap<>();
			for(int consumerIndex = 0; consumerIndex < size; consumerIndex++) {
				CompiledHopKey consumer = variableConsumers.get(consumerIndex);
				unionWithOwner(parent, realizationOwners, consumer, consumerIndex);
				for(var fact : analysis.logicalTransientInputsInCanonicalOrder())
					if(fact.sourceWrite() == consumer || fact.targetRead() == consumer) {
						unionWithOwner(parent, realizationOwners, fact.sourceWrite(), consumerIndex);
						unionWithOwner(parent, realizationOwners, fact.targetRead(), consumerIndex);
					}
				for(var relation : analysis.logicalBoundaryRealizations().relations())
					if(relation.source() == consumer || relation.target() == consumer) {
						unionWithOwner(parent, realizationOwners, relation.source(), consumerIndex);
						unionWithOwner(parent, realizationOwners, relation.target(), consumerIndex);
					}
				for(CandidateSelectionReceipt receipt : variants.get(consumer)) {
					for(var support : receipt.supportClause().requiredInputSupport())
						unionWithOwner(parent, realizationOwners, support.rule().parentOccurrence(), consumerIndex);
					for(long token : relocationProblems.exactInteractionTokens(receipt))
						unionWithOwner(parent, relocationOwners, token, consumerIndex);
					for(int producer : localMaterializationScorer
						.exactInteractionProducerIds(receipt))
						unionWithOwner(parent, localOwners, producer, consumerIndex);
					Integer fout = foutEmissionIds.get(receipt);
					if(fout != null)
						unionWithOwner(parent, foutOwners, fout, consumerIndex);
				}
			}
			Map<Integer,List<CompiledHopKey>> byRoot = new LinkedHashMap<>();
			for(int index = 0; index < size; index++)
				byRoot.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>())
					.add(variableConsumers.get(index));
			return byRoot.values().stream().map(List::copyOf).toList();
		}

		private static <T> void unionWithOwner(int[] parent, Map<T,Integer> owners,
			T factor, int consumer) {
			Integer owner = owners.putIfAbsent(factor, consumer);
			if(owner != null)
				union(parent, owner, consumer);
		}

		private static int find(int[] parent, int node) {
			int root = node;
			while(parent[root] != root)
				root = parent[root];
			while(parent[node] != node) {
				int next = parent[node];
				parent[node] = root;
				node = next;
			}
			return root;
		}

		private static void union(int[] parent, int left, int right) {
			int leftRoot = find(parent, left);
			int rightRoot = find(parent, right);
			if(leftRoot == rightRoot)
				return;
			if(leftRoot < rightRoot)
				parent[rightRoot] = leftRoot;
			else
				parent[leftRoot] = rightRoot;
		}

		private List<CandidateSelectionReceipt> selectedComponentRows(
			List<CompiledHopKey> component) {
			List<CandidateSelectionReceipt> selected = new ArrayList<>(component.size());
			for(CompiledHopKey consumer : component) {
				CandidateSelectionReceipt receipt = selectedByConsumer.get(consumer);
				if(receipt == null)
					throw new IllegalStateException(
						"Candidate component search has an unselected consumer");
				selected.add(receipt);
			}
			return List.copyOf(selected);
		}

		private int compareComponentRows(List<CandidateSelectionReceipt> left,
			List<CandidateSelectionReceipt> right) {
			if(right == null)
				return -1;
			if(left.size() != right.size())
				throw new IllegalStateException(
					"Candidate component selections cover different consumers");
			for(int index = 0; index < left.size(); index++) {
				CandidateSelectionReceipt leftReceipt = left.get(index);
				CandidateSelectionReceipt rightReceipt = right.get(index);
				if(leftReceipt == rightReceipt)
					continue;
				int comparison = Integer.compare(candidateRanks.get(leftReceipt),
					candidateRanks.get(rightReceipt));
				if(comparison != 0)
					return comparison;
			}
			return 0;
		}

		private void push(CompiledHopKey consumer, CandidateSelectionReceipt receipt) {
			if(selectedByConsumer.put(consumer, receipt) != null || !selectedReceipts.add(receipt))
				throw new IllegalStateException("Candidate row search selected a duplicate consumer/receipt");
			relocationScorer.selectReceipt(receipt);
			localMaterializationScorer.selectReceipt(receipt);
			if(relocationProblems.hasExactRelocationDemand(receipt))
				selectedRelocationDemandRows++;
			Integer action = foutEmissionIds.get(receipt);
			if(action != null && foutEmissionRefs[action]++ == 0)
				foutEmissionCount++;
		}

		private void pop(CompiledHopKey consumer, CandidateSelectionReceipt receipt) {
			relocationScorer.deselectReceipt(receipt);
			localMaterializationScorer.deselectReceipt(receipt);
			if(relocationProblems.hasExactRelocationDemand(receipt)) {
				if(selectedRelocationDemandRows <= 0)
					throw new IllegalStateException(
						"Exact relocation-demand row stack is inconsistent");
				selectedRelocationDemandRows--;
			}
			CandidateSelectionReceipt removed = selectedByConsumer.remove(consumer);
			if(removed != receipt || !selectedReceipts.remove(receipt))
				throw new IllegalStateException("Candidate row search stack is inconsistent");
			Integer action = foutEmissionIds.get(receipt);
			if(action == null)
				return;
			if(foutEmissionRefs[action] <= 0)
				throw new IllegalStateException("Candidate FOUT emission reference is missing");
			if(--foutEmissionRefs[action] == 0)
				foutEmissionCount--;
		}

		private int presentInputCount(CandidateSelectionReceipt receipt) {
			Integer count = presentInputCounts.get(receipt);
			if(count == null)
				throw new IllegalStateException("Candidate receipt has no indexed PRESENT-input count");
			return count;
		}

		private static long saturatedProduct(long left, int right) {
			return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
		}

		private List<CandidateSelectionReceipt> selectedInConsumerOrder() {
			List<CandidateSelectionReceipt> selected = new ArrayList<>(consumers.size());
			for(CompiledHopKey consumer : consumers) {
				CandidateSelectionReceipt receipt = selectedByConsumer.get(consumer);
				if(receipt == null)
					throw new IllegalStateException("Candidate row search has an unselected consumer");
				selected.add(receipt);
			}
			return List.copyOf(selected);
		}

		private Selection requireBest() {
			if(best == null)
				throw new IllegalStateException("Selected placement assignment has no exact candidate-row plan");
			if(!bestCanonicalized) {
				int canonicalLocalMaterializations =
					LocalMaterializationSelections.physicalEmissionCount(
						analysis, assignment, best.candidates());
				if(canonicalLocalMaterializations != best.localMaterializationActionCount())
					throw new IllegalStateException(
						"Indexed local-materialization scorer differs from canonical exact selection: score="
							+ best.localMaterializationActionCount() + " canonical="
							+ canonicalLocalMaterializations);
				RelocationSelections.Selection relocationSelection =
					relocationProblems.select(best.candidates());
				if(Double.compare(relocationSelection.cost(),
					best.relocationPhysicalEmissionCount()) != 0)
					throw new IllegalStateException(
						"Indexed relocation scorer differs from canonical exact selection: score="
							+ best.relocationPhysicalEmissionCount() + " canonical="
							+ relocationSelection.cost());
				best = new Selection(best.candidates(), relocationSelection.choices(),
					relocationSelection.emittedActions(), best.materializedInputCount(),
					best.relocationPhysicalEmissionCount(),
					best.localMaterializationActionCount(),
					best.foutMaterializationActionCount());
				bestCanonicalized = true;
			}
			if(FederatedPlannerTrace.isEnabled()
				&& (searchId <= 4 || (searchId & (searchId - 1L)) == 0L))
				FederatedPlannerTrace.logGlobal("Candidate-Search-Complete",
					"id=" + searchId + " leaves=" + evaluatedLeaves
						+ " physical=" + bestPhysicalEmissionCount
						+ " lowerBound=" + physicalEmissionLowerBound
						+ " incumbents=" + incumbentMaterializations);
			return best;
		}
	}

	/** Number of exact planner-created FOUT uploads selected by candidate receipts. */
	public static int foutMaterializationPhysicalEmissionCount(
		Collection<CandidateSelectionReceipt> selectedCandidates) {
		return (int) Objects.requireNonNull(selectedCandidates, "selectedCandidates").stream()
			.map(candidate -> candidate.emission().derivedFoutAction())
			.filter(Objects::nonNull).distinct().count();
	}

	/** Number of exact FED/LOUT-to-FOUT uploads selected by candidate receipts. */
	public static int derivedFoutPhysicalEmissionCount(
		Collection<CandidateSelectionReceipt> selectedCandidates) {
		return (int) Objects.requireNonNull(selectedCandidates, "selectedCandidates").stream()
			.filter(candidate -> candidate.emission().emissionState().derivedFedFout())
			.map(candidate -> candidate.emission().derivedFoutAction())
			.filter(Objects::nonNull).distinct().count();
	}

	/** Number of exact CP/LOUT-to-CP/FOUT uploads selected by candidate receipts. */
	public static int cpFoutPhysicalEmissionCount(
		Collection<CandidateSelectionReceipt> selectedCandidates) {
		return foutMaterializationPhysicalEmissionCount(selectedCandidates)
			- derivedFoutPhysicalEmissionCount(selectedCandidates);
	}
}
