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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;

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

	/** Immutable, allocation-free-on-success partial candidate reachability check. */
	public static final class PartialReachabilityIndex {
		private final PlacementAnalysis analysis;
		private final List<IndexedConsumer> consumers;
		private final Map<CompiledHopKey,List<PlacementAnalysis.LogicalFunctionInputFact>>
			incomingFunctionInputs;
		private final Map<CompiledHopKey,List<IndexedConsumer>> consumersByDependency;
		private final Map<CompiledHopKey,List<RelocationAction>> actionsByConsumer;
		private final Map<CandidateSelectionReceipt,List<IndexedCandidateAction>>
			physicalEffectsByReceipt;
		private final LocalMaterializationSelections.ExactProjectionIndex
			localMaterializationIndex;

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
					for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
						PlacementState selected = emission.emissionState().placementState();
						CandidateSelectionReceipt receipt = analysis.canonicalCandidateReceipt(
							fact.key(), emission);
						boolean emissionStructurallyReachable = foutMaterializationActionReachable(
							authorityGraph, fact, receipt, null, true);
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
							boolean receipted = actions.stream().anyMatch(action ->
								action.key().materializationFType() == input.fType()
									&& action.key().targetPlacement().equals(selected)
									&& action.obligations().stream().anyMatch(obligation ->
										obligation.consumer() == consumer.key()
											&& obligation.inputPosition() == inputPosition));
							boolean directWhenUnassigned = presentInputs == 1 && inputEdges.size() == 1
								&& analysis.graph().node(inputEdges.get(0).producer()).orElseThrow()
									.legalAlternatives().stream().anyMatch(state ->
										state.output()
											== org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
											&& state.fType() == input.fType());
							inputs.add(new IndexedInput(input.fType(), List.copyOf(inputEdges),
								receipted, presentInputs == 1, presentPhysicalInputs == 1,
								directWhenUnassigned));
						}
						rows.add(new IndexedRow(receipt, selected, emissionStructurallyReachable,
							anchorOwner, anchorOwnerType,
							analysis.isDmlFunctionCallBoundary(consumer.key()), List.copyOf(inputs)));
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
			Map<CandidateSelectionReceipt,List<IndexedCandidateAction>> physicalEffects =
				new IdentityHashMap<>();
			for(IndexedConsumer consumer : this.consumers)
				for(IndexedRow row : consumer.rows())
					physicalEffects.put(row.receipt(), indexCandidatePhysicalEffects(
						authorityGraph, row.receipt(),
						actionsByConsumer.getOrDefault(consumer.key(), List.of())));
			this.physicalEffectsByReceipt = Collections.unmodifiableMap(physicalEffects);
			Map<CompiledHopKey,List<IndexedConsumer>> dependencies = new IdentityHashMap<>();
			for(IndexedConsumer consumer : this.consumers) {
				Set<CompiledHopKey> keys = Collections.newSetFromMap(new IdentityHashMap<>());
				keys.add(consumer.key());
				for(IndexedRow row : consumer.rows()) {
					if(row.anchorOwner() != null)
						keys.add(row.anchorOwner());
					for(IndexedInput input : row.inputs())
						for(PlacementAnalysis.CompiledInputEdgeFact edge : input.edges()) {
							keys.add(edge.producer());
							collectParametricDependencies(edge.producer(), keys,
								Collections.newSetFromMap(new IdentityHashMap<>()));
						}
				}
				for(CompiledHopKey key : keys)
					dependencies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(consumer);
			}
			this.consumersByDependency = immutableIdentityLists(dependencies);
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
			return new CandidateEffectKey(receipt.emission(), receipt.rule().orderedInputs(),
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
						"Active exact candidate has no source-reachable row");
				if(reachable.isEmpty())
					continue;
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
						"Active exact candidate has no source-reachable row");
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
			if(!row.emissionStructurallyReachable())
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
			for(IndexedInput input : row.inputs()) {
				if(input.edges().isEmpty())
					continue;
				if(input.edges().size() != 1)
					return false;
				if(input.receipted())
					continue;
				CompiledHopKey producer = input.edges().get(0).producer();
				boolean direct = false;
				if(input.singlePresentInput()) {
					PlacementState producerState = partialAssignment.get(producer);
					List<PlacementState> producerDomain = remainingStateDomains.get(producer);
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
			return true;
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
		boolean emissionStructurallyReachable,
		CompiledHopKey anchorOwner, FType anchorOwnerType, boolean functionBoundary,
		List<IndexedInput> inputs) { }
	private record IndexedInput(FType required,
		List<PlacementAnalysis.CompiledInputEdgeFact> edges, boolean receipted,
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
				if(selected != null && action.directSourcePlacements().contains(selected))
					return false;
				if(selected == null)
					continue;
				for(DerivedSuppression derived : derivedSuppressions)
					if(derived.source() == source && selected == derived.target())
						return false;
			}
			return true;
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
			.map(emission -> analysis.canonicalCandidateReceipt(fact.key(), emission))
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
			for(CandidateEmissionFact emission : fact.allowedEmissionFacts()) {
				if(!emission.emissionState().placementState().equals(selected))
					continue;
				CandidateSelectionReceipt base = analysis.canonicalCandidateReceipt(
					fact.key(), emission);
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
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
			reachabilityIndex == null
				? materializationMaximalVariantsForCompleteAssignment(
					analysis, authorityGraph, actionUniverse, assignment)
				: materializationMaximalVariants(analysis, authorityGraph, actionUniverse,
					assignment, reachabilityIndex
						.materializationObjectiveVariantsForCompleteAssignment(assignment),
					reachabilityIndex);
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
		Search search = new Search(analysis, authorityGraph, List.copyOf(actionUniverse), assignment,
			consumers, byConsumer, true, relocationOrder, reachabilityIndex);
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
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : feasible.entrySet()) {
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

	/**
	 * Candidate-row preference is consumer-separable. A FED execution retains the
	 * legacy FedAll policy of maximizing explicit federated materializations; a CP
	 * execution minimizes them because uploading a LOUT input only to execute the
	 * consumer locally contradicts that selected placement. Restricting the exact
	 * secondary relocation search to each consumer's directional optimum is an
	 * exact lexicographic reduction, not a runtime-capability gate.
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
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : feasible.entrySet()) {
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

	private record CandidateEffectKey(CandidateEmissionFact emission,
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
		return new CandidateEffectKey(receipt.emission(), receipt.rule().orderedInputs(),
			List.copyOf(options));
	}

	private static boolean actionMatchesSelectedCandidate(RelocationAction action,
		PlacementIdentity.ObligationKey obligation, CandidateSelectionReceipt selected) {
		if(!selected.emission().emissionState().placementState()
			.equals(obligation.requiredPlacement())
			|| obligation.inputPosition() >= selected.rule().orderedInputs().size())
			return false;
		CandidateInputState input = selected.rule().orderedInputs().get(obligation.inputPosition());
		return input.present() && input.fType() == action.key().materializationFType();
	}

	/** Canonical native-first completion used only when a planner supplies no explicit row receipt. */
	public static Selection selectNativeCanonical(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment) {
		Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
			feasibleVariants(analysis, actionUniverse, assignment);
		List<CandidateSelectionReceipt> selected = new ArrayList<>();
		for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : byConsumer.entrySet())
			selected.add(entry.getValue().stream().min((left, right) -> {
				int materialization = Integer.compare(presentInputCount(left), presentInputCount(right));
				return materialization != 0 ? materialization
					: Integer.compare(analysis.candidateReceiptRank(left),
						analysis.candidateReceiptRank(right));
			}).orElseThrow());
		selected = analysis.canonicalCandidateReceipts(selected);
		List<RelocationChoiceReceipt> choices = RelocationSelections.selectCanonical(
			analysis, actionUniverse, assignment, selected, (demand, action) -> true);
		List<RelocationActionKey> emitted = RelocationSelections.emittedActions(
			analysis, actionUniverse, assignment, selected, choices);
		int materialized = selected.stream().mapToInt(CandidateSelections::presentInputCount).sum();
		int localMaterializations = LocalMaterializationSelections.physicalEmissionCount(
			analysis, assignment, selected);
		return new Selection(selected, choices, emitted, materialized,
			RelocationSelections.physicalEmissionCount(emitted), localMaterializations,
			foutMaterializationPhysicalEmissionCount(selected));
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
				candidate.rule() == receipt.rule() && candidate.emission() == receipt.emission()))
				throw new IllegalArgumentException("Candidate selection is foreign, inactive, or unreachable: "
					+ receipt.normalizedSignature() + " feasible=" + feasible.getOrDefault(consumer, List.of())
						.stream().map(CandidateSelectionReceipt::normalizedSignature).toList()
					+ " reachability=" + reachabilityDetails(analysis, actionUniverse, assignment, receipt));
		}
		if(selected.size() != feasible.size() || !selected.keySet().containsAll(feasible.keySet()))
			throw new IllegalArgumentException("Candidate selections do not cover every active consumer: expected="
				+ feasible.size() + " selected=" + selected.size());
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
				&& fact.allowedEmissionFacts().stream().anyMatch(emission -> emission == receipt.emission())
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
		if(selected == null || !selected.emission().emissionState().placementState()
			.equals(obligation.requiredPlacement())
			|| obligation.inputPosition() >= selected.rule().orderedInputs().size())
			return false;
		CandidateInputState input = selected.rule().orderedInputs().get(obligation.inputPosition());
		return input.present() && input.fType() == action.key().materializationFType();
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
			boolean receipted = actions.stream().anyMatch(action ->
				action.key().materializationFType() == required
					&& action.key().targetPlacement().equals(receipt.emission().emissionState().placementState())
					&& action.obligations().stream().anyMatch(obligation ->
						obligation.consumer() == receipt.rule().parentOccurrence()
							&& obligation.inputPosition() == inputPosition));
			boolean direct = singlePhysicalInputDirectReachable(
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
		private final NeutralPlacementGraph authorityGraph;
		private final List<RelocationAction> actions;
		private final Map<CompiledHopKey,PlacementState> assignment;
		private final List<CompiledHopKey> consumers;
		private final Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants;
		private final boolean maximizeMaterialization;
		private final RelocationSelections.CanonicalOrderIndex relocationOrder;
		private final RelocationSelections.CandidateProblemIndex relocationProblems;
		private final RelocationSelections.ExactEmissionScorer relocationScorer;
		private final LocalMaterializationSelections.ExactPhysicalEmissionScorer
			localMaterializationScorer;
		private final int physicalEmissionLowerBound;
		private final List<CompiledHopKey> fixedConsumers;
		private final List<CompiledHopKey> variableConsumers;
		private final int materializedInputCount;
		private final Map<CompiledHopKey,CandidateSelectionReceipt> selectedByConsumer =
			new IdentityHashMap<>();
		private final Set<CandidateSelectionReceipt> selectedReceipts =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<CandidateSelectionReceipt,Integer> foutEmissionIds =
			new IdentityHashMap<>();
		private final int[] foutEmissionRefs;
		private int foutEmissionCount;
		private final Map<CandidateSelectionReceipt,Integer> candidateRanks = new IdentityHashMap<>();
		private final Map<CandidateSelectionReceipt,Integer> presentInputCounts =
			new IdentityHashMap<>();
		private final Map<CandidateSelectionReceipt,Integer> relocationEffectRanks =
			new IdentityHashMap<>();
		private final Map<CompiledHopKey,Long> relocationEffectMultipliers =
			new IdentityHashMap<>();
		private final Map<Long,Integer> relocationScoreCache;
		private long currentRelocationEffectKey;
		private long relocationScoreCacheHits;
		private Selection best;
		private boolean bestCanonicalized;
		private int bestPhysicalEmissionCount = Integer.MAX_VALUE;
		private boolean optimumReached;
		private long evaluatedLeaves;
		private long incumbentMaterializations;

		private Search(PlacementAnalysis analysis, NeutralPlacementGraph authorityGraph,
			List<RelocationAction> actions,
			Map<CompiledHopKey,PlacementState> assignment, List<CompiledHopKey> consumers,
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants,
			boolean maximizeMaterialization,
			RelocationSelections.CanonicalOrderIndex relocationOrder,
			PartialReachabilityIndex reachabilityIndex) {
			this.analysis = analysis;
			this.searchId = EXACT_SEARCH_IDS.incrementAndGet();
			this.authorityGraph = authorityGraph;
			this.actions = actions;
			this.assignment = assignment;
			this.consumers = consumers;
			this.variants = variants;
			this.maximizeMaterialization = maximizeMaterialization;
			this.relocationOrder = Objects.requireNonNull(relocationOrder, "relocationOrder");
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
					candidateRanks.put(ordered.get(rank), rank);
					presentInputCounts.put(ordered.get(rank),
						CandidateSelections.presentInputCount(ordered.get(rank)));
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
			int materialized = 0;
			for(CompiledHopKey consumer : consumers) {
				List<CandidateSelectionReceipt> rows = variants.get(consumer);
				int count = presentInputCount(rows.get(0));
				if(rows.stream().anyMatch(row -> presentInputCount(row) != count))
					throw new IllegalStateException(
						"Materialization-optimal candidate domain has inconsistent PRESENT counts");
				materialized = Math.addExact(materialized, count);
				(rows.size() == 1 ? fixed : variable).add(consumer);
			}
			this.fixedConsumers = List.copyOf(fixed);
			this.variableConsumers = List.copyOf(variable);
			this.materializedInputCount = materialized;
			long rawProduct = 1;
			long effectProduct = 1;
			long multiplier = 1;
			boolean encodable = true;
			for(CompiledHopKey consumer : variableConsumers) {
				List<CandidateSelectionReceipt> rows = variants.get(consumer);
				rawProduct = saturatedProduct(rawProduct, rows.size());
				Map<Object,Integer> effects = new LinkedHashMap<>();
				for(CandidateSelectionReceipt receipt : rows)
					relocationEffectRanks.put(receipt, effects.computeIfAbsent(
						relocationProblems.exactScoringEffect(receipt), ignored -> effects.size()));
				effectProduct = saturatedProduct(effectProduct, effects.size());
				if(encodable) {
					relocationEffectMultipliers.put(consumer, multiplier);
					if(multiplier > Long.MAX_VALUE / effects.size())
						encodable = false;
					else
						multiplier *= effects.size();
				}
			}
			this.relocationScoreCache = encodable && effectProduct < rawProduct
				? new HashMap<>() : null;
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
						+ " domains=" + domainSizes);
			}
		}

		private void solve() {
			for(CompiledHopKey consumer : fixedConsumers)
				push(consumer, variants.get(consumer).get(0));
			try {
				solveVariable(0);
			}
			finally {
				for(int index = fixedConsumers.size() - 1; index >= 0; index--) {
					CompiledHopKey consumer = fixedConsumers.get(index);
					pop(consumer, variants.get(consumer).get(0));
				}
			}
		}

		private void solveVariable(int index) {
			if(optimumReached || relocationScorer.hasAnchorConflict())
				return;
			if(index == variableConsumers.size()) {
				// Search order is deterministic but is not necessarily the public receipt
				// order because normalized identities use length-prefixed fields. Preserve
				// this allocation-free internal order; the non-indexed public boundary
				// canonicalizes the single winning result once.
				evaluatedLeaves++;
				Integer cachedRelocation = relocationScoreCache == null ? null
					: relocationScoreCache.get(currentRelocationEffectKey);
				int relocationMaterializations;
				if(cachedRelocation == null) {
					relocationMaterializations = relocationScorer.minimumPhysicalEmissionCount();
					if(relocationScoreCache != null)
						relocationScoreCache.put(currentRelocationEffectKey,
							relocationMaterializations);
				}
				else {
					relocationScoreCacheHits++;
					relocationMaterializations = cachedRelocation;
				}
				if(relocationMaterializations == Integer.MAX_VALUE)
					return;
				boolean potentiallyImproving = canImprove(materializedInputCount,
					Math.addExact(relocationMaterializations, foutEmissionCount));
				if(!potentiallyImproving)
					return;
				int localMaterializations = localMaterializationScorer.physicalEmissionCount();
				int physicalEmissions = Math.addExact(Math.addExact(relocationMaterializations,
					localMaterializations), foutEmissionCount);
				potentiallyImproving = canImprove(materializedInputCount, physicalEmissions);
				if(!potentiallyImproving)
					return;
				incumbentMaterializations++;
				List<CandidateSelectionReceipt> selected = selectedInConsumerOrder();
				// Candidate ordering and the objective require only exact physical counts.
				// Reconstructing canonical choice/action receipts for every improving leaf
				// repeats the same relocation sort/search inside the outer exponential
				// placement search.  Defer that independent certificate to the one final
				// winner in requireBest(); no candidate or objective value is removed.
				Selection candidate = new Selection(selected, List.of(), List.of(), materializedInputCount,
					relocationMaterializations, localMaterializations,
					foutEmissionCount);
				consider(candidate, physicalEmissions);
				return;
			}
			CompiledHopKey consumer = variableConsumers.get(index);
			for(CandidateSelectionReceipt receipt : variants.get(consumer)) {
				push(consumer, receipt);
				solveVariable(index + 1);
				pop(consumer, receipt);
			}
		}

		private void push(CompiledHopKey consumer, CandidateSelectionReceipt receipt) {
			if(selectedByConsumer.put(consumer, receipt) != null || !selectedReceipts.add(receipt))
				throw new IllegalStateException("Candidate row search selected a duplicate consumer/receipt");
			relocationScorer.selectReceipt(receipt);
			localMaterializationScorer.selectReceipt(receipt);
			Integer action = foutEmissionIds.get(receipt);
			if(action != null && foutEmissionRefs[action]++ == 0)
				foutEmissionCount++;
			Long effectMultiplier = relocationEffectMultipliers.get(consumer);
			if(effectMultiplier != null)
				currentRelocationEffectKey = Math.addExact(currentRelocationEffectKey,
					Math.multiplyExact(effectMultiplier, relocationEffectRanks.get(receipt)));
		}

		private void pop(CompiledHopKey consumer, CandidateSelectionReceipt receipt) {
			Long effectMultiplier = relocationEffectMultipliers.get(consumer);
			if(effectMultiplier != null)
				currentRelocationEffectKey = Math.subtractExact(currentRelocationEffectKey,
					Math.multiplyExact(effectMultiplier, relocationEffectRanks.get(receipt)));
			relocationScorer.deselectReceipt(receipt);
			localMaterializationScorer.deselectReceipt(receipt);
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

		private boolean canImprove(int materialized, int physicalEmissions) {
			if(best == null)
				return true;
			if(maximizeMaterialization) {
				int materialization = Integer.compare(materialized, best.materializedInputCount());
				if(materialization != 0)
					return materialization > 0;
			}
			int emitted = Integer.compare(physicalEmissions, bestPhysicalEmissionCount);
			if(emitted != 0)
				return emitted < 0;
			// Before local materialization is counted, equality is a lower bound, but
			// local cost is non-negative. Therefore an equal, canonical-worse row cannot
			// improve the incumbent even if its exact local cost is zero.
			return compareCurrentRowsToBest() < 0;
		}

		private int compareCurrentRowsToBest() {
			if(best == null)
				throw new IllegalStateException("Candidate row comparison requires an incumbent");
			List<CandidateSelectionReceipt> incumbent = best.candidates();
			if(incumbent.size() != consumers.size())
				throw new IllegalStateException("Incumbent candidate rows do not cover every consumer");
			for(int index = 0; index < consumers.size(); index++) {
				CandidateSelectionReceipt selected = selectedByConsumer.get(consumers.get(index));
				if(selected == null)
					throw new IllegalStateException("Candidate row search has an unselected consumer");
				CandidateSelectionReceipt previous = incumbent.get(index);
				if(selected == previous)
					continue;
				int comparison = Integer.compare(candidateRanks.get(selected),
					candidateRanks.get(previous));
				if(comparison != 0)
					return comparison;
			}
			return 0;
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

		private void consider(Selection candidate, int physicalEmissionCount) {
			if(best == null) {
				best = candidate;
				bestPhysicalEmissionCount = physicalEmissionCount;
				if(FederatedPlannerTrace.isEnabled()
					&& (searchId <= 4 || (searchId & (searchId - 1L)) == 0L))
					FederatedPlannerTrace.logGlobal("Candidate-Search-First",
						"id=" + searchId + " physical=" + physicalEmissionCount
							+ " relocation=" + candidate.relocationPhysicalEmissionCount()
							+ " local=" + candidate.localMaterializationActionCount()
							+ " fout=" + candidate.foutMaterializationActionCount());
				optimumReached = physicalEmissionCount == physicalEmissionLowerBound;
				return;
			}
			if(maximizeMaterialization) {
				int materialization = Integer.compare(candidate.materializedInputCount(),
					best.materializedInputCount());
				if(materialization != 0) {
					if(materialization > 0) {
						best = candidate;
						bestPhysicalEmissionCount = physicalEmissionCount;
						optimumReached = physicalEmissionCount == physicalEmissionLowerBound;
					}
					return;
				}
			}
			int emitted = Integer.compare(physicalEmissionCount, bestPhysicalEmissionCount);
			if(emitted != 0) {
				if(emitted < 0) {
					best = candidate;
					bestPhysicalEmissionCount = physicalEmissionCount;
					optimumReached = physicalEmissionCount == physicalEmissionLowerBound;
				}
				return;
			}
			if(compareCandidateRows(candidate.candidates(), best.candidates()) < 0) {
				best = candidate;
				bestPhysicalEmissionCount = physicalEmissionCount;
			}
			optimumReached = bestPhysicalEmissionCount == physicalEmissionLowerBound;
		}

		private int compareCandidateRows(List<CandidateSelectionReceipt> left,
			List<CandidateSelectionReceipt> right) {
			if(left.size() != right.size())
				throw new IllegalStateException("Candidate selections cover different consumers");
			for(int index = 0; index < left.size(); index++) {
				CandidateSelectionReceipt leftReceipt = left.get(index);
				CandidateSelectionReceipt rightReceipt = right.get(index);
				if(leftReceipt == rightReceipt)
					continue;
				Integer leftRank = candidateRanks.get(leftReceipt);
				Integer rightRank = candidateRanks.get(rightReceipt);
				if(leftRank == null || rightRank == null)
					throw new IllegalStateException("Candidate selection is outside its canonical row domain");
				int comparison = Integer.compare(leftRank, rightRank);
				if(comparison != 0)
					return comparison;
			}
			return 0;
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
						+ " relocationCache=" + (relocationScoreCache == null ? "disabled"
							: relocationScoreCache.size() + "/" + relocationScoreCacheHits)
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
