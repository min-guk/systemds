/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerTrace;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationDemandKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact selection and validation of input-materialization alternatives. */
public final class RelocationSelections {
	private static final AtomicLong EXACT_SEARCH_IDS = new AtomicLong();

	private RelocationSelections() { }

	/**
	 * Canonical ranks for one immutable relocation-action universe. Exact placement
	 * search reuses this index across complete assignments instead of repeatedly
	 * expanding the same deeply nested structural identities into strings.
	 */
	public static final class CanonicalOrderIndex {
		private final Map<RelocationDemandKey,Integer> demandRanks;
		private final Map<RelocationActionKey,Integer> actionRanks;
		private final Map<ObligationKey,Integer> choiceRanks;
		private final Map<ObligationKey,RelocationDemandKey> demandsByObligation;
		private final Map<ObligationKey,Integer> demandRanksByObligation;
		private final Map<RelocationActionKey,Integer> physicalEmissionIds;
		private final Map<CompiledHopKey,List<ActionObligation>> actionObligationsByConsumer;
		private final Map<CompiledHopKey,Integer> consumerIds;
		private final Map<DurableAnchorKey,Integer> anchorIds;
		private final int physicalEmissionCount;

		private CanonicalOrderIndex(Map<RelocationDemandKey,Integer> demandRanks,
			Map<RelocationActionKey,Integer> actionRanks,
			Map<ObligationKey,Integer> choiceRanks,
			Map<ObligationKey,RelocationDemandKey> demandsByObligation,
			Map<ObligationKey,Integer> demandRanksByObligation,
			Map<RelocationActionKey,Integer> physicalEmissionIds,
			Map<CompiledHopKey,List<ActionObligation>> actionObligationsByConsumer,
			Map<CompiledHopKey,Integer> consumerIds,
			Map<DurableAnchorKey,Integer> anchorIds) {
			this.demandRanks = Map.copyOf(demandRanks);
			this.actionRanks = Collections.unmodifiableMap(new IdentityHashMap<>(actionRanks));
			this.choiceRanks = Collections.unmodifiableMap(new IdentityHashMap<>(choiceRanks));
			this.demandsByObligation = Collections.unmodifiableMap(
				new IdentityHashMap<>(demandsByObligation));
			this.demandRanksByObligation = Collections.unmodifiableMap(
				new IdentityHashMap<>(demandRanksByObligation));
			this.physicalEmissionIds = Collections.unmodifiableMap(
				new IdentityHashMap<>(physicalEmissionIds));
			Map<CompiledHopKey,List<ActionObligation>> obligations = new IdentityHashMap<>();
			for(Map.Entry<CompiledHopKey,List<ActionObligation>> entry :
				actionObligationsByConsumer.entrySet())
				obligations.put(entry.getKey(), List.copyOf(entry.getValue()));
			this.actionObligationsByConsumer = Collections.unmodifiableMap(obligations);
			this.consumerIds = Collections.unmodifiableMap(new IdentityHashMap<>(consumerIds));
			this.anchorIds = Map.copyOf(anchorIds);
			this.physicalEmissionCount = physicalEmissionIds.values().stream()
				.mapToInt(Integer::intValue).max().orElse(-1) + 1;
		}

		private int demandRank(RelocationDemandKey demand) {
			Integer rank = demandRanks.get(demand);
			if(rank == null)
				throw new IllegalStateException("Relocation demand is outside its canonical action universe: "
					+ demand.normalizedSignature());
			return rank;
		}

		private int actionRank(RelocationActionKey action) {
			Integer rank = actionRanks.get(action);
			if(rank == null)
				throw new IllegalStateException("Relocation action is outside its canonical action universe: "
					+ action.normalizedSignature());
			return rank;
		}

		private int choiceRank(ObligationKey obligation) {
			Integer rank = choiceRanks.get(obligation);
			if(rank == null)
				throw new IllegalStateException(
					"Relocation choice is outside its canonical action universe: "
						+ obligation.normalizedSignature());
			return rank;
		}

		private RelocationDemandKey demand(ObligationKey obligation) {
			RelocationDemandKey demand = demandsByObligation.get(obligation);
			if(demand == null)
				throw new IllegalStateException(
					"Relocation obligation is outside its canonical action universe: "
						+ obligation.normalizedSignature());
			return demand;
		}

		private int demandRank(ObligationKey obligation) {
			Integer rank = demandRanksByObligation.get(obligation);
			if(rank == null)
				throw new IllegalStateException(
					"Relocation obligation is outside its canonical demand universe: "
						+ obligation.normalizedSignature());
			return rank;
		}

		private int physicalEmissionId(RelocationActionKey action) {
			Integer id = physicalEmissionIds.get(action);
			if(id == null)
				throw new IllegalStateException(
					"Relocation action is outside its physical-emission index: "
						+ action.normalizedSignature());
			return id;
		}

		private int physicalEmissionCount() {
			return physicalEmissionCount;
		}

		private List<ActionObligation> actionObligations(CompiledHopKey consumer) {
			return actionObligationsByConsumer.getOrDefault(consumer, List.of());
		}

		private int consumerId(CompiledHopKey consumer) {
			Integer id = consumerIds.get(consumer);
			if(id == null)
				throw new IllegalStateException(
					"Relocation consumer is outside its canonical action universe");
			return id;
		}

		private int anchorId(DurableAnchorKey anchor) {
			Integer id = anchorIds.get(anchor);
			if(id == null)
				throw new IllegalStateException(
					"Relocation anchor is outside its canonical action universe");
			return id;
		}

		private int consumerCount() {
			return consumerIds.size();
		}

		private int anchorCount() {
			return anchorIds.size();
		}
	}

	/**
	 * Immutable exact relocation projection for one complete placement assignment.
	 * Candidate search changes only the selected row receipts; action ownership,
	 * direct-source activity, canonical ranks, and each row's demand alternatives
	 * are invariant. Hoisting those joins avoids rebuilding the full relocation
	 * graph for every row-product leaf without removing any exact alternative.
	 */
	static final class CandidateProblemIndex {
		private final Map<CandidateSelectionReceipt,List<IndexedDemand>> demandsByReceipt;
		private final Map<CandidateSelectionReceipt,Integer> receiptIds;
		private final Map<RelocationAction,Boolean> baseRequiresEmission;
		private final Map<RelocationAction,Set<CandidateSelectionReceipt>> suppressors;
		private final Map<RelocationAction,int[]> suppressorIds;
		private final Map<CandidateSelectionReceipt,ScoredReceipt> scoredReceipts;
		private final Map<CandidateSelectionReceipt,Object> exactScoringEffects;
		private final ScoredAction[] scoredActions;
		private final int scoredConsumerCount;
		private final int scoredAnchorCount;
		private final int maximumScoredDemandCount;
		private final CanonicalOrderIndex order;

		private CandidateProblemIndex(PlacementAnalysis analysis,
			NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
			Map<CompiledHopKey,PlacementState> assignment,
			Collection<CandidateSelectionReceipt> receipts, CanonicalOrderIndex order) {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(authorityGraph, "authorityGraph");
			List<RelocationAction> actions = List.copyOf(actionUniverse);
			Objects.requireNonNull(assignment, "assignment");
			this.order = Objects.requireNonNull(order, "order");
			Set<CandidateSelectionReceipt> seenReceipts =
				Collections.newSetFromMap(new IdentityHashMap<>());
			List<CandidateSelectionReceipt> exactReceipts = receipts.stream()
				.filter(seenReceipts::add).toList();
			Map<CandidateSelectionReceipt,Integer> ids = new IdentityHashMap<>();
			for(int id = 0; id < exactReceipts.size(); id++)
				ids.put(exactReceipts.get(id), id);
			this.receiptIds = Collections.unmodifiableMap(ids);
			Map<CandidateSelectionReceipt,List<IndexedDemand>> indexed = new IdentityHashMap<>();
			for(CandidateSelectionReceipt receipt : exactReceipts) {
				Map<Integer,RelocationDemandKey> demandsByRank = new HashMap<>();
				Map<Integer,List<IndexedOption>> optionsByRank = new HashMap<>();
				for(ActionObligation actionObligation : order.actionObligations(
					receipt.rule().parentOccurrence())) {
					RelocationAction action = actionObligation.action();
					ObligationKey obligation = actionObligation.obligation();
					int position = obligation.inputPosition();
					if(!obligation.requiredPlacement().equals(
						assignment.get(obligation.consumer()))
						|| position >= receipt.rule().orderedInputs().size())
						continue;
					var input = receipt.rule().orderedInputs().get(position);
					if(!input.present() || input.fType() != action.key().materializationFType())
						continue;
					int demandRank = order.demandRank(obligation);
					demandsByRank.putIfAbsent(demandRank, order.demand(obligation));
					optionsByRank.computeIfAbsent(demandRank, ignored -> new ArrayList<>()).add(
						new IndexedOption(action, obligation, order.choiceRank(obligation)));
				}
				List<IndexedDemand> demands = new ArrayList<>();
				for(Map.Entry<Integer,List<IndexedOption>> entry : optionsByRank.entrySet()) {
					List<IndexedOption> alternatives = entry.getValue().stream()
						.sorted(Comparator.comparingInt(IndexedOption::choiceRank)).toList();
					if(alternatives.size() > 1 && alternatives.stream()
						.map(option -> option.action().key()).distinct().count() != alternatives.size())
						throw new IllegalStateException(
							"Graph contains duplicate alternatives for exact candidate demand: "
								+ demandsByRank.get(entry.getKey()).normalizedSignature());
					demands.add(new IndexedDemand(demandsByRank.get(entry.getKey()),
						alternatives, entry.getKey()));
				}
				demands.sort(Comparator.comparingInt((IndexedDemand demand) -> demand.options().size())
					.thenComparingInt(IndexedDemand::canonicalRank));
				indexed.put(receipt, List.copyOf(demands));
			}
			this.demandsByReceipt = Collections.unmodifiableMap(indexed);
			Map<RelocationAction,Boolean> base = new IdentityHashMap<>();
			Map<RelocationAction,Set<CandidateSelectionReceipt>> suppressed = new IdentityHashMap<>();
			Map<ValueVersionKey,List<CandidateSelectionReceipt>> derivedReceiptsByValue =
				new HashMap<>();
			for(CandidateSelectionReceipt receipt : exactReceipts) {
				DerivedFoutMaterializationActionKey derived =
					receipt.emission().derivedFoutAction();
				if(derived != null)
					derivedReceiptsByValue.computeIfAbsent(derived.producerValueVersion(),
						ignored -> new ArrayList<>()).add(receipt);
			}
			for(RelocationAction action : actions) {
				boolean required = authorityGraph.isRelocationActive(action, assignment, List.of());
				base.put(action, required);
				if(!required)
					continue;
				Set<CandidateSelectionReceipt> actionSuppressors =
					Collections.newSetFromMap(new IdentityHashMap<>());
				// A candidate can suppress an active relocation only by publishing the
				// exact source value-version as a graph-owned derived FOUT. Retain the
				// authoritative graph predicate, but do not test unrelated rows.
				for(CandidateSelectionReceipt receipt : derivedReceiptsByValue.getOrDefault(
					action.key().sourceValueVersion(), List.of()))
					if(!authorityGraph.isRelocationActive(action, assignment, List.of(receipt)))
						actionSuppressors.add(receipt);
				if(!actionSuppressors.isEmpty())
					suppressed.put(action, actionSuppressors);
			}
			this.baseRequiresEmission = Collections.unmodifiableMap(base);
			this.suppressors = Collections.unmodifiableMap(suppressed);
			Map<RelocationAction,int[]> indexedSuppressors = new IdentityHashMap<>();
			for(Map.Entry<RelocationAction,Set<CandidateSelectionReceipt>> entry :
				suppressed.entrySet())
				indexedSuppressors.put(entry.getKey(), entry.getValue().stream()
					.mapToInt(receipt -> ids.get(receipt)).toArray());
			this.suppressorIds = Collections.unmodifiableMap(indexedSuppressors);
			ScoredAction[] scoredActionArray = new ScoredAction[actions.size()];
			for(RelocationAction action : actions) {
				int actionId = order.actionRank(action.key());
				scoredActionArray[actionId] = new ScoredAction(
					order.physicalEmissionId(action.key()), base.get(action),
					indexedSuppressors.getOrDefault(action, new int[0]));
			}
			Map<CandidateSelectionReceipt,ScoredReceipt> scored = new IdentityHashMap<>();
			int maximumDemands = 0;
			for(CandidateSelectionReceipt receipt : exactReceipts) {
				List<IndexedDemand> receiptDemands = indexed.getOrDefault(receipt, List.of());
				List<ScoredDemand> scoredDemands = new ArrayList<>(receiptDemands.size());
				for(IndexedDemand demand : receiptDemands) {
					ScoredOption[] scoredOptions = new ScoredOption[demand.options().size()];
					for(int optionIndex = 0; optionIndex < demand.options().size(); optionIndex++) {
						IndexedOption option = demand.options().get(optionIndex);
						CompiledHopKey consumer = option.obligation().consumer();
					int consumerId = order.consumerId(consumer);
					int anchorId = order.anchorId(option.action().key().durableAnchor());
					scoredOptions[optionIndex] = new ScoredOption(consumerId, anchorId,
						order.actionRank(option.action().key()));
					}
					int consumerId = scoredOptions[0].consumerId();
					if(Arrays.stream(scoredOptions).anyMatch(option -> option.consumerId() != consumerId))
						throw new IllegalStateException(
							"One relocation demand spans multiple physical consumers");
					int[] demandAnchorIds = Arrays.stream(scoredOptions)
						.mapToInt(ScoredOption::anchorId).distinct().toArray();
					scoredDemands.add(new ScoredDemand(consumerId, demandAnchorIds, scoredOptions));
				}
				maximumDemands = Math.addExact(maximumDemands, scoredDemands.size());
				scored.put(receipt, new ScoredReceipt(
					scoredDemands.toArray(ScoredDemand[]::new)));
			}
			this.scoredReceipts = Collections.unmodifiableMap(scored);
			this.scoredActions = scoredActionArray;
			this.scoredConsumerCount = order.consumerCount();
			this.scoredAnchorCount = order.anchorCount();
			this.maximumScoredDemandCount = maximumDemands;
			List<List<Integer>> suppressedActionsByReceipt = new ArrayList<>(exactReceipts.size());
			for(int receiptId = 0; receiptId < exactReceipts.size(); receiptId++)
				suppressedActionsByReceipt.add(new ArrayList<>());
			for(int actionId = 0; actionId < scoredActionArray.length; actionId++)
				for(int suppressorId : scoredActionArray[actionId].suppressorIds())
					suppressedActionsByReceipt.get(suppressorId).add(actionId);
			Map<CandidateSelectionReceipt,Object> effects = new IdentityHashMap<>();
			for(CandidateSelectionReceipt receipt : exactReceipts) {
				List<List<ScoredOption>> demandEffects = new ArrayList<>();
				for(ScoredDemand demand : scored.get(receipt).demands())
					demandEffects.add(List.copyOf(Arrays.asList(demand.options())));
				effects.put(receipt, new ExactReceiptScoringEffect(List.copyOf(demandEffects),
					List.copyOf(suppressedActionsByReceipt.get(ids.get(receipt)))));
			}
			this.exactScoringEffects = Collections.unmodifiableMap(effects);
		}

		Selection select(Collection<CandidateSelectionReceipt> selectedReceipts) {
			Set<CandidateSelectionReceipt> selected =
				Collections.newSetFromMap(new IdentityHashMap<>());
			selected.addAll(selectedReceipts);
			List<RankedDemandOptions> rankedDemands = new ArrayList<>();
			for(CandidateSelectionReceipt receipt : selectedReceipts) {
				List<IndexedDemand> indexed = demandsByReceipt.get(receipt);
				if(indexed == null)
					throw new IllegalArgumentException(
						"Candidate receipt is outside its exact relocation index");
				for(IndexedDemand demand : indexed) {
					List<Option> options = new ArrayList<>(demand.options().size());
					for(IndexedOption option : demand.options()) {
						boolean requiresEmission = baseRequiresEmission.get(option.action())
							&& suppressors.getOrDefault(option.action(), Set.of()).stream()
								.noneMatch(selected::contains);
						options.add(new Option(option.action(), option.obligation(),
							requiresEmission, option.choiceRank()));
					}
					rankedDemands.add(new RankedDemandOptions(
						new DemandOptions(demand.demand(), options), demand.canonicalRank()));
				}
			}
			rankedDemands.sort(Comparator.comparingInt(
				(RankedDemandOptions demand) -> demand.demand().options().size())
				.thenComparingInt(RankedDemandOptions::canonicalRank));
			List<DemandOptions> demands = rankedDemands.stream()
				.map(RankedDemandOptions::demand).toList();
			Search search = new Search(demands, null, order);
			search.solve(0);
			if(search.best == null)
				throw new IllegalStateException("Exact indexed relocation-choice search has no solution");
			return new Selection(search.best, search.bestEmitted, search.bestEmissionCount);
		}

		ExactEmissionScorer newExactEmissionScorer() {
			return new ExactEmissionScorer(this);
		}

		Object exactScoringEffect(CandidateSelectionReceipt receipt) {
			Object effect = exactScoringEffects.get(receipt);
			if(effect == null)
				throw new IllegalArgumentException(
					"Candidate receipt is outside its exact relocation index");
			return effect;
		}

		/**
		 * Admissible combined relocation/FOUT lower bound over a candidate-row product.
		 * A demand is retained only when every row of its consumer contains that exact
		 * demand. A possible derived-source suppressor is represented by its own FOUT
		 * action cost rather than treated as free. Ignoring row correlations and anchor
		 * consistency can only lower the result, so equality proves optimality.
		 */
		int unavoidableCombinedPhysicalEmissionCount(
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants) {
			return unavoidableCombinedPhysicalEmissionCount(variants, Map.of());
		}

		int unavoidableCombinedPhysicalEmissionCount(
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> variants,
			Map<CompiledHopKey,CandidateSelectionReceipt> selectedByConsumer) {
			Set<CandidateSelectionReceipt> possible =
				Collections.newSetFromMap(new IdentityHashMap<>());
			for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : variants.entrySet()) {
				CandidateSelectionReceipt selected = selectedByConsumer.get(entry.getKey());
				if(selected == null)
					possible.addAll(entry.getValue());
				else
					possible.add(selected);
			}
			List<Set<LowerBoundEmission>> unavoidable = new ArrayList<>();
			// A consumer whose every exact row publishes a planner-created FOUT must
			// pay for one of those actions. Keep alternative action identities explicit
			// so a source-side FOUT can also satisfy relocation demands below.
			for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : variants.entrySet()) {
				CandidateSelectionReceipt selected = selectedByConsumer.get(entry.getKey());
				List<CandidateSelectionReceipt> rows = selected == null
					? entry.getValue() : List.of(selected);
				Set<LowerBoundEmission> actions = new LinkedHashSet<>();
				boolean avoidable = false;
				for(CandidateSelectionReceipt row : rows) {
					DerivedFoutMaterializationActionKey action = row.emission().derivedFoutAction();
					if(action == null) {
						avoidable = true;
						break;
					}
					actions.add(LowerBoundEmission.fout(action));
				}
				if(!avoidable && !actions.isEmpty())
					// Per-call lower-bound sets are immutable by ownership after this
					// point; avoid copying structural action keys at every exact leaf.
					unavoidable.add(actions);
			}
			for(Map.Entry<CompiledHopKey,List<CandidateSelectionReceipt>> entry : variants.entrySet()) {
				CandidateSelectionReceipt selected = selectedByConsumer.get(entry.getKey());
				List<CandidateSelectionReceipt> rows = selected == null
					? entry.getValue() : List.of(selected);
				if(rows.isEmpty())
					continue;
				Set<RelocationDemandKey> common = new LinkedHashSet<>();
				demandsByReceipt.getOrDefault(rows.get(0), List.of()).stream()
					.map(IndexedDemand::demand).forEach(common::add);
				for(int rowIndex = 1; rowIndex < rows.size() && !common.isEmpty(); rowIndex++) {
					Set<RelocationDemandKey> present = new HashSet<>();
					demandsByReceipt.getOrDefault(rows.get(rowIndex), List.of()).stream()
						.map(IndexedDemand::demand).forEach(present::add);
					common.retainAll(present);
				}
				for(RelocationDemandKey demand : common) {
					Set<LowerBoundEmission> physical = new LinkedHashSet<>();
					boolean avoidable = false;
					for(CandidateSelectionReceipt row : rows) {
						IndexedDemand indexedDemand = demandsByReceipt.getOrDefault(row, List.of())
							.stream().filter(candidate -> candidate.demand().equals(demand))
							.findFirst().orElseThrow();
						for(IndexedOption option : indexedDemand.options()) {
							if(!baseRequiresEmission.get(option.action())) {
								avoidable = true;
								break;
							}
							physical.add(LowerBoundEmission.relocation(
								order.physicalEmissionId(option.action().key())));
							for(CandidateSelectionReceipt suppressor :
								suppressors.getOrDefault(option.action(), Set.of()))
								if(possible.contains(suppressor)) {
									DerivedFoutMaterializationActionKey action =
										suppressor.emission().derivedFoutAction();
									if(action == null)
										throw new IllegalStateException(
											"Relocation suppressor has no FOUT action authority");
									physical.add(LowerBoundEmission.fout(action));
								}
						}
						if(avoidable)
							break;
					}
					if(!avoidable && !physical.isEmpty())
						unavoidable.add(physical);
				}
			}
			unavoidable.sort(Comparator.comparingInt(Set::size));
			Set<LowerBoundEmission> covered = new HashSet<>();
			int count = 0;
			for(Set<LowerBoundEmission> options : unavoidable) {
				if(options.stream().anyMatch(covered::contains))
					continue;
				count++;
				covered.addAll(options);
			}
			return count;
		}
	}

	/**
	 * Allocation-free exact relocation score for the hot candidate-row product.
	 * Candidate selection needs only the minimum physical-emission count at every
	 * leaf; canonical receipts are materialized later, and only for a new incumbent.
	 * Selected singleton actions, alternative demands, and common-anchor feasibility
	 * are maintained incrementally with candidate-row push/pop. A leaf therefore does
	 * not rescan every fixed demand. This preserves the generic {@link Search} plan
	 * space exactly and merely hoists invariant work out of the Cartesian row loop.
	 */
	static final class ExactEmissionScorer {
		private final CandidateProblemIndex problem;
		private final int[] physicalRefs;
		private final boolean[] selectedReceipts;
		private final int[] anchors;
		private final int[] singletonActionRefs;
		private final int[] activeSingletonActions;
		private final int[] activeSingletonActionPositions;
		private final ScoredDemand[] selectedAlternatives;
		private final int[] selectedDemandCounts;
		private final int[][] allowedAnchorDemandRefs;
		private final int[] feasibleAnchorCounts;
		private int activeSingletonActionCount;
		private int alternativeCount;
		private int anchorConflictCount;
		private int physicalEmissionCount;
		private int best;

		private ExactEmissionScorer(CandidateProblemIndex problem) {
			this.problem = Objects.requireNonNull(problem, "problem");
			this.physicalRefs = new int[problem.order.physicalEmissionCount()];
			this.selectedReceipts = new boolean[problem.receiptIds.size()];
			this.anchors = new int[problem.scoredConsumerCount];
			Arrays.fill(anchors, -1);
			this.singletonActionRefs = new int[problem.scoredActions.length];
			this.activeSingletonActions = new int[problem.scoredActions.length];
			this.activeSingletonActionPositions = new int[problem.scoredActions.length];
			Arrays.fill(activeSingletonActionPositions, -1);
			this.selectedAlternatives = new ScoredDemand[problem.maximumScoredDemandCount];
			this.selectedDemandCounts = new int[problem.scoredConsumerCount];
			this.allowedAnchorDemandRefs = new int[problem.scoredConsumerCount]
				[problem.scoredAnchorCount];
			this.feasibleAnchorCounts = new int[problem.scoredConsumerCount];
		}

		void selectReceipt(CandidateSelectionReceipt receipt) {
			Integer id = problem.receiptIds.get(receipt);
			if(id == null || selectedReceipts[id])
				throw new IllegalStateException(
					"Exact relocation scorer receipt selection is inconsistent");
			selectedReceipts[id] = true;
			for(ScoredDemand demand : problem.scoredReceipts.get(receipt).demands()) {
				updateAnchorDemand(demand, 1);
				if(demand.options().length == 1)
					selectSingletonAction(demand.options()[0].actionId());
				else
					selectedAlternatives[alternativeCount++] = demand;
			}
		}

		void deselectReceipt(CandidateSelectionReceipt receipt) {
			Integer id = problem.receiptIds.get(receipt);
			if(id == null || !selectedReceipts[id])
				throw new IllegalStateException(
					"Exact relocation scorer receipt deselection is inconsistent");
			ScoredDemand[] demands = problem.scoredReceipts.get(receipt).demands();
			for(int index = demands.length - 1; index >= 0; index--) {
				ScoredDemand demand = demands[index];
				if(demand.options().length == 1)
					deselectSingletonAction(demand.options()[0].actionId());
				else if(alternativeCount <= 0
					|| selectedAlternatives[--alternativeCount] != demand)
					throw new IllegalStateException(
						"Exact relocation scorer alternative-demand stack is inconsistent");
				updateAnchorDemand(demand, -1);
			}
			selectedReceipts[id] = false;
		}

		boolean hasAnchorConflict() {
			return anchorConflictCount != 0;
		}

		int minimumPhysicalEmissionCount() {
			if(hasAnchorConflict())
				return Integer.MAX_VALUE;
			Arrays.fill(physicalRefs, 0);
			physicalEmissionCount = 0;
			best = Integer.MAX_VALUE;
			for(int index = 0; index < activeSingletonActionCount; index++)
				acquirePhysical(problem.scoredActions[activeSingletonActions[index]]);
			solve(0);
			return best;
		}

		private void solve(int index) {
			if(physicalEmissionCount > best)
				return;
			if(index == alternativeCount) {
				best = Math.min(best, physicalEmissionCount);
				return;
			}
			for(ScoredOption option : selectedAlternatives[index].options()) {
				int previous = anchors[option.consumerId()];
				if(previous >= 0 && previous != option.anchorId())
					continue;
				if(previous < 0)
					anchors[option.consumerId()] = option.anchorId();
				int physical = acquirePhysical(problem.scoredActions[option.actionId()]);
				solve(index + 1);
				if(physical >= 0)
					releasePhysical(physical);
				if(previous < 0)
					anchors[option.consumerId()] = -1;
			}
		}

		private void selectSingletonAction(int action) {
			if(singletonActionRefs[action]++ != 0)
				return;
			if(activeSingletonActionPositions[action] >= 0)
				throw new IllegalStateException(
					"Exact relocation scorer singleton action is already active");
			activeSingletonActionPositions[action] = activeSingletonActionCount;
			activeSingletonActions[activeSingletonActionCount++] = action;
		}

		private void deselectSingletonAction(int action) {
			if(singletonActionRefs[action] <= 0)
				throw new IllegalStateException(
					"Exact relocation scorer singleton action is missing");
			if(--singletonActionRefs[action] != 0)
				return;
			int position = activeSingletonActionPositions[action];
			if(position < 0 || activeSingletonActionCount <= 0)
				throw new IllegalStateException(
					"Exact relocation scorer active singleton action is missing");
			int replacement = activeSingletonActions[--activeSingletonActionCount];
			activeSingletonActions[position] = replacement;
			activeSingletonActionPositions[replacement] = position;
			activeSingletonActionPositions[action] = -1;
		}

		private void updateAnchorDemand(ScoredDemand demand, int delta) {
			int consumer = demand.consumerId();
			boolean wasConflict = selectedDemandCounts[consumer] > 0
				&& feasibleAnchorCounts[consumer] == 0;
			selectedDemandCounts[consumer] += delta;
			if(selectedDemandCounts[consumer] < 0)
				throw new IllegalStateException(
					"Exact relocation scorer selected-demand count is negative");
			for(int anchor : demand.anchorIds()) {
				allowedAnchorDemandRefs[consumer][anchor] += delta;
				if(allowedAnchorDemandRefs[consumer][anchor] < 0)
					throw new IllegalStateException(
						"Exact relocation scorer anchor-demand count is negative");
			}
			int feasible = 0;
			int sole = -1;
			if(selectedDemandCounts[consumer] > 0)
				for(int anchor = 0; anchor < problem.scoredAnchorCount; anchor++)
					if(allowedAnchorDemandRefs[consumer][anchor]
						== selectedDemandCounts[consumer]) {
						feasible++;
						sole = anchor;
					}
			feasibleAnchorCounts[consumer] = feasible;
			anchors[consumer] = feasible == 1 ? sole : -1;
			boolean isConflict = selectedDemandCounts[consumer] > 0 && feasible == 0;
			if(wasConflict != isConflict)
				anchorConflictCount += isConflict ? 1 : -1;
		}

		private int acquirePhysical(ScoredAction action) {
			if(!requiresEmission(action))
				return -1;
			int physical = action.physicalId();
			if(physicalRefs[physical]++ == 0)
				physicalEmissionCount++;
			return physical;
		}

		private void releasePhysical(int physical) {
			if(physicalRefs[physical] <= 0)
				throw new IllegalStateException(
					"Exact relocation scorer physical reference is missing");
			if(--physicalRefs[physical] == 0)
				physicalEmissionCount--;
		}

		private boolean requiresEmission(ScoredAction action) {
			if(!action.baseRequiresEmission())
				return false;
			for(int suppressor : action.suppressorIds())
				if(selectedReceipts[suppressor])
					return false;
			return true;
		}
	}

	private record ScoredReceipt(ScoredDemand[] demands) { }
	private record ScoredDemand(int consumerId, int[] anchorIds,
		ScoredOption[] options) { }
	private record ScoredOption(int consumerId, int anchorId, int actionId) { }
	private record ScoredAction(int physicalId, boolean baseRequiresEmission,
		int[] suppressorIds) { }
	private record ExactReceiptScoringEffect(List<List<ScoredOption>> demands,
		List<Integer> suppressedActionIds) { }

	private record LowerBoundEmission(Integer relocationId,
		DerivedFoutMaterializationActionKey foutAction) {
		private LowerBoundEmission {
			if((relocationId == null) == (foutAction == null))
				throw new IllegalArgumentException(
					"Lower-bound emission must identify exactly one physical action kind");
		}
		private static LowerBoundEmission relocation(int id) {
			return new LowerBoundEmission(id, null);
		}
		private static LowerBoundEmission fout(DerivedFoutMaterializationActionKey action) {
			return new LowerBoundEmission(null, Objects.requireNonNull(action, "action"));
		}
	}

	private record ActionObligation(RelocationAction action, ObligationKey obligation) { }
	private record IndexedOption(RelocationAction action, ObligationKey obligation,
		int choiceRank) { }
	private record IndexedDemand(RelocationDemandKey demand, List<IndexedOption> options,
		int canonicalRank) {
		private IndexedDemand {
			options = List.copyOf(options);
		}
	}
	private record RankedDemandOptions(DemandOptions demand, int canonicalRank) { }

	static CandidateProblemIndex candidateProblemIndex(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey,PlacementState> assignment,
		Collection<CandidateSelectionReceipt> receipts, CanonicalOrderIndex order) {
		return new CandidateProblemIndex(analysis, authorityGraph, actionUniverse,
			assignment, receipts, order);
	}

	private record PhysicalEmissionKey(RelocationActionKey directAction,
		ValueVersionKey sourceValueVersion, FType materializationFType,
		DurableAnchorKey durableAnchor, String statementBlockScope) { }

	private static PhysicalEmissionKey physicalEmissionKey(RelocationActionKey action) {
		if(action.targetPlacement().execType() == ExecType.FED
			&& action.targetPlacement().output() == FederatedOutput.FOUT)
			return new PhysicalEmissionKey(null, action.sourceValueVersion(),
				action.materializationFType(), action.durableAnchor(),
				action.statementBlockScope());
		return new PhysicalEmissionKey(action, null, null, null, null);
	}

	public static CanonicalOrderIndex canonicalOrderIndex(
		Collection<RelocationAction> actionUniverse) {
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		List<RelocationAction> actions = List.copyOf(actionUniverse);
		Map<RelocationActionKey,String> actionSignatures = new LinkedHashMap<>();
		for(RelocationAction action : actions)
			if(actionSignatures.put(action.key(), action.key().normalizedSignature()) != null)
				throw new IllegalArgumentException("Relocation action universe contains duplicates");
		List<Map.Entry<RelocationActionKey,String>> orderedActions =
			new ArrayList<>(actionSignatures.entrySet());
		orderedActions.sort(Map.Entry.comparingByValue());
		// Every option below retains the action key owned by this immutable action
		// universe.  Identity lookup avoids recursively hashing large compiled-hop /
		// control-region records in the exact-search hot loop while preserving the
		// same canonical ranks.
		Map<RelocationActionKey,Integer> actionRanks = new IdentityHashMap<>();
		for(int rank = 0; rank < orderedActions.size(); rank++)
			actionRanks.put(orderedActions.get(rank).getKey(), rank);
		Map<PhysicalEmissionKey,Integer> physicalRanks = new LinkedHashMap<>();
		Map<RelocationActionKey,Integer> physicalEmissionIds = new IdentityHashMap<>();
		for(RelocationAction action : actions) {
			PhysicalEmissionKey physical = physicalEmissionKey(action.key());
			int id = physicalRanks.computeIfAbsent(physical, ignored -> physicalRanks.size());
			physicalEmissionIds.put(action.key(), id);
		}
		Map<RelocationDemandKey,String> signatures = new LinkedHashMap<>();
		Map<ObligationKey,RelocationDemandKey> demandsByObligation = new IdentityHashMap<>();
		Map<RelocationChoiceReceipt,String> choiceSignatures = new LinkedHashMap<>();
		for(RelocationAction action : actions)
			for(ObligationKey obligation : action.obligations()) {
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				demandsByObligation.put(obligation, demand);
				signatures.computeIfAbsent(demand, key -> key.normalizedSignature());
				RelocationChoiceReceipt choice = new RelocationChoiceReceipt(demand, action.key());
				choiceSignatures.computeIfAbsent(choice, key -> key.normalizedSignature());
			}
		List<Map.Entry<RelocationDemandKey,String>> ordered = new ArrayList<>(signatures.entrySet());
		ordered.sort(Map.Entry.comparingByValue());
		Map<RelocationDemandKey,Integer> demandRanks = new HashMap<>();
		for(int rank = 0; rank < ordered.size(); rank++)
			demandRanks.put(ordered.get(rank).getKey(), rank);
		List<Map.Entry<RelocationChoiceReceipt,String>> orderedChoices =
			new ArrayList<>(choiceSignatures.entrySet());
		orderedChoices.sort(Map.Entry.comparingByValue());
		Map<RelocationChoiceReceipt,Integer> structuralChoiceRanks = new HashMap<>();
		for(int rank = 0; rank < orderedChoices.size(); rank++)
			structuralChoiceRanks.put(orderedChoices.get(rank).getKey(), rank);
		Map<ObligationKey,Integer> choiceRanks = new IdentityHashMap<>();
		Map<ObligationKey,Integer> demandRanksByObligation = new IdentityHashMap<>();
		Map<CompiledHopKey,List<ActionObligation>> actionObligationsByConsumer =
			new IdentityHashMap<>();
		Map<CompiledHopKey,Integer> consumerIds = new IdentityHashMap<>();
		Map<DurableAnchorKey,Integer> anchorIds = new HashMap<>();
		for(RelocationAction action : actions)
			for(ObligationKey obligation : action.obligations()) {
				RelocationDemandKey demand = demandsByObligation.get(obligation);
				RelocationChoiceReceipt choice = new RelocationChoiceReceipt(demand, action.key());
				choiceRanks.put(obligation, structuralChoiceRanks.get(choice));
				demandRanksByObligation.put(obligation, demandRanks.get(demand));
				actionObligationsByConsumer.computeIfAbsent(obligation.consumer(),
					ignored -> new ArrayList<>()).add(new ActionObligation(action, obligation));
				consumerIds.computeIfAbsent(obligation.consumer(), ignored -> consumerIds.size());
				anchorIds.computeIfAbsent(action.key().durableAnchor(), ignored -> anchorIds.size());
			}
		return new CanonicalOrderIndex(demandRanks, actionRanks, choiceRanks,
			demandsByObligation, demandRanksByObligation,
			physicalEmissionIds, actionObligationsByConsumer, consumerIds, anchorIds);
	}

	/**
	 * Stable identity of one physical relocation emission. Consumer-specific REFED
	 * receipts that publish the same source into the same durable placement share one
	 * upload, while every other relocation keeps its full action identity.
	 */
	public static String physicalEmissionIdentity(RelocationActionKey action) {
		Objects.requireNonNull(action, "action");
		if(action.targetPlacement().execType() == ExecType.FED
			&& action.targetPlacement().output() == FederatedOutput.FOUT)
			return "REFED|" + action.sourceValueVersion().normalizedSignature()
				+ '|' + action.materializationFType().name()
				+ '|' + action.durableAnchor().normalizedSignature()
				+ '|' + action.statementBlockScope();
		return "ACTION|" + action.normalizedSignature();
	}

	/** Number of distinct physical emissions represented by exact action receipts. */
	public static int physicalEmissionCount(Collection<RelocationActionKey> actions) {
		return (int) Objects.requireNonNull(actions, "actions").stream()
			.map(RelocationSelections::physicalEmissionKey).distinct().count();
	}

	public record ResolvedChoice(RelocationChoiceReceipt receipt, RelocationAction action,
		ObligationKey obligation, boolean requiresEmission) { }

	/** Costed exact choice projection used by planners whose objective includes relocation. */
	public record Selection(List<RelocationChoiceReceipt> choices,
		List<RelocationActionKey> emittedActions, double cost) {
		public Selection {
			choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
			emittedActions = List.copyOf(Objects.requireNonNull(emittedActions, "emittedActions"));
			if(!Double.isFinite(cost) || cost < 0.0)
				throw new IllegalArgumentException("Relocation selection cost must be finite and non-negative");
		}
	}

	/**
	 * Selects exactly one legal alternative for every active exact demand. The supplied
	 * cost is charged once per distinct emitted action; direct compatible sources cost
	 * zero because no physical relocation is emitted.
	 */
	public static Selection selectMinimumCost(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		ToDoubleFunction<RelocationActionKey> emittedActionCost) {
		Objects.requireNonNull(emittedActionCost, "emittedActionCost");
		CanonicalOrderIndex order = canonicalOrderIndex(actionUniverse);
		Problem problem = problem(graph, actionUniverse, assignment, order);
		WeightedSearch search = new WeightedSearch(problem.demands(), emittedActionCost, order);
		search.solve(0, 0.0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice cost search has no solution");
		return new Selection(search.best, search.bestEmitted, search.bestCost);
	}

	public static Selection selectMinimumCost(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		ToDoubleFunction<RelocationActionKey> emittedActionCost) {
		return selectMinimumCost(analysis, actionUniverse, assignment, candidateSelections,
			canonicalOrderIndex(actionUniverse), emittedActionCost);
	}

	/**
	 * Exact weighted relocation selection with a caller-owned index for one immutable
	 * action universe. This is semantically identical to the convenience overload and
	 * allows iterative planners to avoid rebuilding deeply nested canonical identities
	 * for every candidate row.
	 */
	public static Selection selectMinimumCost(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		CanonicalOrderIndex order,
		ToDoubleFunction<RelocationActionKey> emittedActionCost) {
		Objects.requireNonNull(emittedActionCost, "emittedActionCost");
		Objects.requireNonNull(order, "order");
		Problem problem = problem(analysis, actionUniverse, assignment, candidateSelections, order);
		WeightedSearch search = new WeightedSearch(problem.demands(), emittedActionCost, order);
		search.solve(0, 0.0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice cost search has no solution");
		return new Selection(search.best, search.bestEmitted, search.bestCost);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment) {
		return selectCanonical(graph, graph.relocationActions(), assignment,
			(demand, action) -> true);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		Objects.requireNonNull(allowed, "allowed");
		CanonicalOrderIndex order = canonicalOrderIndex(actionUniverse);
		Problem problem = problem(analysis, actionUniverse, assignment, candidateSelections, order);
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> allowed.test(demand.demand(), option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalStateException("No selected materialization alternative for exact demand: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		Search search = new Search(filtered, null, order);
		search.solve(0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice search has no solution");
		return search.best;
	}

	/**
	 * Internal exact completion for rows already selected from
	 * {@link CandidateSelections#feasibleVariants}. This avoids reconstructing and
	 * revalidating the complete feasible-row universe at every leaf of the exact
	 * candidate search. The relocation problem and tie-breaking are identical to
	 * {@link #selectCanonical(PlacementAnalysis, Collection, Map, Collection, BiPredicate)}.
	 */
	static Selection selectCanonicalPrevalidated(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		return selectCanonicalPrevalidated(analysis, analysis.graph(), actionUniverse, assignment,
			candidateSelections, canonicalOrderIndex(actionUniverse), allowed);
	}

	static Selection selectCanonicalPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		return selectCanonicalPrevalidated(analysis, authorityGraph, actionUniverse, assignment,
			candidateSelections, canonicalOrderIndex(actionUniverse), allowed);
	}

	static Selection selectCanonicalPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections, CanonicalOrderIndex order,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		Objects.requireNonNull(allowed, "allowed");
		Problem problem = problemPrevalidated(analysis, authorityGraph, actionUniverse, assignment,
			candidateSelections, order);
		List<DemandOptions> filtered = filtered(problem, allowed);
		Search search = new Search(filtered, null, order);
		search.solve(0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice search has no solution");
		return new Selection(search.best, search.bestEmitted, search.bestEmissionCount);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		return selectCanonical(graph, graph.relocationActions(), assignment, allowed);
	}

	public static List<RelocationChoiceReceipt> selectCanonical(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		Objects.requireNonNull(allowed, "allowed");
		CanonicalOrderIndex order = canonicalOrderIndex(actionUniverse);
		Problem problem = problem(graph, actionUniverse, assignment, order);
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> allowed.test(demand.demand(), option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalStateException("No selected materialization alternative for exact demand: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		Search search = new Search(filtered, null, order);
		search.solve(0);
		if(search.best == null)
			throw new IllegalStateException("Exact relocation-choice search has no solution");
		return search.best;
	}

	/**
	 * Completes a legacy emitted-action projection with the unique canonical direct/non-emitted
	 * choices. This deliberately rejects an emitted action set that cannot explain every demand.
	 */
	public static List<RelocationChoiceReceipt> completeFromSelectedRelocations(
		NeutralPlacementGraph graph, Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationActionKey> emittedActions) {
		Set<RelocationActionKey> expected = new LinkedHashSet<>(
			Objects.requireNonNull(emittedActions, "emittedActions"));
		CanonicalOrderIndex order = canonicalOrderIndex(graph.relocationActions());
		Problem problem = problem(graph, graph.relocationActions(), assignment, order);
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> !option.requiresEmission() || expected.contains(option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalArgumentException("Selected relocation set leaves an exact demand unresolved: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		Search search = new Search(filtered, expected, order);
		search.solve(0);
		if(search.best == null)
			throw new IllegalArgumentException("Selected relocation set has no exact demand assignment");
		return search.best;
	}

	public static List<ResolvedChoice> resolveAndValidate(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		return resolveAndValidate(graph, graph.relocationActions(), assignment, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		return resolveAndValidate(analysis, analysis.graph(), actionUniverse, assignment,
			candidateSelections, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		Problem problem = problem(analysis, authorityGraph, actionUniverse, assignment, candidateSelections);
		return resolveAndValidate(problem, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		return resolveAndValidate(analysis, analysis.graph().relocationActions(), assignment,
			candidateSelections, choices);
	}

	public static List<ResolvedChoice> resolveAndValidate(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		Problem problem = problem(graph, actionUniverse, assignment);
		return resolveAndValidate(problem, choices);
	}

	private static List<ResolvedChoice> resolveAndValidate(Problem problem,
		Collection<RelocationChoiceReceipt> choices) {
		Map<RelocationDemandKey,DemandOptions> demands = new LinkedHashMap<>();
		for(DemandOptions demand : problem.demands())
			demands.put(demand.demand(), demand);
		Map<RelocationDemandKey,RelocationChoiceReceipt> selected = new LinkedHashMap<>();
		for(RelocationChoiceReceipt choice : Objects.requireNonNull(choices, "choices")) {
			DemandOptions demand = demands.get(Objects.requireNonNull(choice, "choice").demand());
			if(demand == null)
				throw new IllegalArgumentException("Relocation choice is inactive or foreign: "
					+ choice.normalizedSignature());
			if(selected.putIfAbsent(choice.demand(), choice) != null)
				throw new IllegalArgumentException("Relocation demand has multiple selected alternatives: "
					+ choice.demand().normalizedSignature());
			if(demand.options().stream().noneMatch(option -> option.action().key().equals(choice.action())))
				throw new IllegalArgumentException("Relocation choice is not an exact graph-owned alternative: "
					+ choice.normalizedSignature());
		}
		if(selected.size() != demands.size())
			throw new IllegalArgumentException("Relocation choices do not cover every active exact demand");
		List<ResolvedChoice> resolved = new ArrayList<>();
		Map<CompiledHopKey,DurableAnchorKey> anchorsByConsumer = new LinkedHashMap<>();
		for(DemandOptions demand : problem.demands()) {
			RelocationChoiceReceipt choice = selected.get(demand.demand());
			Option option = demand.options().stream()
				.filter(candidate -> candidate.action().key().equals(choice.action()))
				.findFirst().orElseThrow();
			DurableAnchorKey prior = anchorsByConsumer.putIfAbsent(
				option.obligation().consumer(), option.action().key().durableAnchor());
			if(prior != null && !prior.equals(option.action().key().durableAnchor()))
				throw new IllegalArgumentException(
					"One exact consumer cannot mix input receipts from different durable anchors: consumer="
						+ option.obligation().consumer().normalizedSignature() + " first="
						+ prior.normalizedSignature() + " current="
						+ option.action().key().durableAnchor().normalizedSignature());
			resolved.add(new ResolvedChoice(choice, option.action(), option.obligation(),
				option.requiresEmission()));
		}
		return List.copyOf(resolved);
	}

	public static List<RelocationActionKey> emittedActions(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		return emittedActions(graph, graph.relocationActions(), assignment, choices);
	}

	public static List<RelocationActionKey> emittedActions(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		for(ResolvedChoice choice : resolveAndValidate(analysis, actionUniverse, assignment,
			candidateSelections, choices))
			if(choice.requiresEmission())
				emitted.add(choice.action().key());
		List<RelocationActionKey> ordered = new ArrayList<>(emitted);
		Collections.sort(ordered);
		return List.copyOf(ordered);
	}

	public static List<RelocationActionKey> emittedActions(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections,
		Collection<RelocationChoiceReceipt> choices) {
		return emittedActions(analysis, analysis.graph().relocationActions(), assignment,
			candidateSelections, choices);
	}

	public static List<RelocationActionKey> emittedActions(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<RelocationChoiceReceipt> choices) {
		Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		for(ResolvedChoice choice : resolveAndValidate(graph, actionUniverse, assignment, choices))
			if(choice.requiresEmission())
				emitted.add(choice.action().key());
		List<RelocationActionKey> ordered = new ArrayList<>(emitted);
		Collections.sort(ordered);
		return List.copyOf(ordered);
	}

	private static Problem problem(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment) {
		return problem(graph, actionUniverse, assignment, canonicalOrderIndex(actionUniverse));
	}

	private static Problem problem(NeutralPlacementGraph graph,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment, CanonicalOrderIndex order) {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		Map<RelocationDemandKey,List<Option>> options = new LinkedHashMap<>();
		for(RelocationAction action : actionUniverse) {
			boolean requiresEmission = graph.isRelocationActive(action, assignment);
			for(ObligationKey obligation : action.obligations()) {
				if(!obligation.requiredPlacement().equals(assignment.get(obligation.consumer())))
					continue;
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				options.computeIfAbsent(demand, ignored -> new ArrayList<>())
					.add(new Option(action, obligation, requiresEmission));
			}
		}
		List<DemandOptions> demands = new ArrayList<>();
		for(Map.Entry<RelocationDemandKey,List<Option>> entry : options.entrySet()) {
			List<Option> raw = entry.getValue();
			List<Option> sorted = canonicalOptions(raw, order);
			if(sorted.size() > 1 && sorted.stream().map(option -> option.action().key())
				.distinct().count() != sorted.size())
				throw new IllegalStateException("Graph contains duplicate alternatives for exact demand: "
					+ entry.getKey().normalizedSignature());
			demands.add(new DemandOptions(entry.getKey(), sorted));
		}
		return new Problem(canonicalDemands(demands, order));
	}

	private static Problem problem(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		return problem(analysis, analysis.graph(), actionUniverse, assignment, candidateSelections,
			canonicalOrderIndex(actionUniverse));
	}

	private static Problem problem(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections, CanonicalOrderIndex order) {
		return problem(analysis, analysis.graph(), actionUniverse, assignment, candidateSelections, order);
	}

	private static Problem problem(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		return problem(analysis, authorityGraph, actionUniverse, assignment, candidateSelections,
			canonicalOrderIndex(actionUniverse));
	}

	private static Problem problem(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections, CanonicalOrderIndex order) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		List<CandidateSelectionReceipt> validated = CandidateSelections.resolveAndValidatePartial(
			analysis, authorityGraph, actionUniverse, assignment, candidateSelections);
		return problemPrevalidated(analysis, authorityGraph, actionUniverse, assignment, validated, order);
	}

	private static Problem problemPrevalidated(PlacementAnalysis analysis,
		Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		return problemPrevalidated(analysis, analysis.graph(), actionUniverse, assignment,
			candidateSelections, canonicalOrderIndex(actionUniverse));
	}

	private static Problem problemPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections) {
		return problemPrevalidated(analysis, authorityGraph, actionUniverse, assignment,
			candidateSelections, canonicalOrderIndex(actionUniverse));
	}

	private static Problem problemPrevalidated(PlacementAnalysis analysis,
		NeutralPlacementGraph authorityGraph, Collection<RelocationAction> actionUniverse,
		Map<CompiledHopKey, PlacementState> assignment,
		Collection<CandidateSelectionReceipt> candidateSelections, CanonicalOrderIndex order) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(authorityGraph, "authorityGraph");
		Objects.requireNonNull(actionUniverse, "actionUniverse");
		Objects.requireNonNull(assignment, "assignment");
		// A graph-only analysis (the exact selector seam and its independent
		// exhaustive fixtures) has no runtime candidate-row universe.  In that
		// representation the graph-owned obligations are already the complete
		// authority and must not disappear merely because there are no candidate
		// receipts to filter them through.  Real compiler analyses carry candidate
		// facts and continue through the stricter row-aware path below.
		if(analysis.candidateRuleFacts().orderedFacts().isEmpty())
			return problem(authorityGraph, actionUniverse, assignment, order);
		Map<CompiledHopKey,CandidateSelectionReceipt> selected =
			CandidateSelections.indexByConsumer(candidateSelections);
		Map<RelocationDemandKey,List<Option>> options = new LinkedHashMap<>();
		for(RelocationAction action : actionUniverse) {
			boolean requiresEmission = authorityGraph.isRelocationActive(
				action, assignment, candidateSelections);
			for(ObligationKey obligation : action.obligations()) {
				if(!obligation.requiredPlacement().equals(assignment.get(obligation.consumer()))
					|| !CandidateSelections.actionMatchesSelectedCandidate(action, obligation, selected))
					continue;
				RelocationDemandKey demand = RelocationDemandKey.from(obligation);
				options.computeIfAbsent(demand, ignored -> new ArrayList<>())
					.add(new Option(action, obligation, requiresEmission));
			}
		}
		List<DemandOptions> demands = new ArrayList<>();
		for(Map.Entry<RelocationDemandKey,List<Option>> entry : options.entrySet()) {
			List<Option> raw = entry.getValue();
			List<Option> sorted = canonicalOptions(raw, order);
			if(sorted.size() > 1 && sorted.stream().map(option -> option.action().key())
				.distinct().count() != sorted.size())
				throw new IllegalStateException("Graph contains duplicate alternatives for exact candidate demand: "
					+ entry.getKey().normalizedSignature());
			demands.add(new DemandOptions(entry.getKey(), sorted));
		}
		return new Problem(canonicalDemands(demands, order));
	}

	private static List<DemandOptions> filtered(Problem problem,
		BiPredicate<RelocationDemandKey, RelocationActionKey> allowed) {
		List<DemandOptions> filtered = new ArrayList<>();
		for(DemandOptions demand : problem.demands()) {
			List<Option> options = demand.options().stream()
				.filter(option -> allowed.test(demand.demand(), option.action().key()))
				.toList();
			if(options.isEmpty())
				throw new IllegalStateException("No selected materialization alternative for exact demand: "
					+ demand.demand().normalizedSignature());
			filtered.add(new DemandOptions(demand.demand(), options));
		}
		return List.copyOf(filtered);
	}

	private record Option(RelocationAction action, ObligationKey obligation,
		boolean requiresEmission, int choiceRank) {
		private Option(RelocationAction action, ObligationKey obligation,
			boolean requiresEmission) {
			this(action, obligation, requiresEmission, -1);
		}

		private Option withChoiceRank(int rank) {
			return new Option(action, obligation, requiresEmission, rank);
		}
	}

	private record RankedChoice(RelocationChoiceReceipt receipt, int rank) { }

	private static List<Option> canonicalOptions(List<Option> options,
		CanonicalOrderIndex order) {
		List<Option> ranked = options.stream()
			.map(option -> option.withChoiceRank(order.choiceRank(option.obligation()))).toList();
		return ranked.size() < 2 ? ranked
			: ranked.stream().sorted(Comparator.comparingInt(Option::choiceRank)).toList();
	}

	private record DemandOptions(RelocationDemandKey demand, List<Option> options)
		implements Comparable<DemandOptions> {
		private DemandOptions {
			options = List.copyOf(options);
		}
		@Override public int compareTo(DemandOptions that) {
			int cardinality = Integer.compare(options.size(), that.options.size());
			return cardinality != 0 ? cardinality : demand.compareTo(that.demand);
		}
	}

	private static List<DemandOptions> canonicalDemands(List<DemandOptions> demands,
		CanonicalOrderIndex order) {
		if(demands.size() < 2)
			return List.copyOf(demands);
		return demands.stream()
			.sorted(Comparator.comparingInt((DemandOptions demand) -> demand.options().size())
				.thenComparingInt(demand -> order.demandRank(demand.demand())))
			.toList();
	}

	private record Problem(List<DemandOptions> demands) { }

	private static final class Search {
		private final long searchId;
		private final List<DemandOptions> demands;
		private final Set<RelocationActionKey> requiredEmitted;
		private final List<RankedChoice> current = new ArrayList<>();
		private final Set<RelocationActionKey> emitted =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final Map<Integer,Integer> physicalEmissionRefs = new LinkedHashMap<>();
		private final AnchorBindings anchorBindings = new AnchorBindings();
		private final CanonicalOrderIndex order;
		private final boolean hasAlternative;
		private List<RelocationChoiceReceipt> best;
		private List<RelocationActionKey> bestEmitted = List.of();
		private int bestEmissionCount = Integer.MAX_VALUE;
		private List<Integer> bestChoiceRanks;

		private Search(List<DemandOptions> demands, Set<RelocationActionKey> requiredEmitted,
			CanonicalOrderIndex order) {
			this.searchId = EXACT_SEARCH_IDS.incrementAndGet();
			this.demands = List.copyOf(demands);
			this.requiredEmitted = requiredEmitted == null ? null : Set.copyOf(requiredEmitted);
			this.order = Objects.requireNonNull(order, "order");
			this.hasAlternative = demands.stream().anyMatch(demand -> demand.options().size() > 1);
			if(FederatedPlannerTrace.isEnabled()
				&& (searchId <= 4 || (searchId & (searchId - 1L)) == 0L))
				FederatedPlannerTrace.logGlobal("Relocation-Search-Start",
					"id=" + searchId + " demands=" + demands.size()
						+ " alternatives=" + demands.stream()
							.filter(demand -> demand.options().size() > 1).count()
						+ " maxDomain=" + demands.stream().mapToInt(
							demand -> demand.options().size()).max().orElse(0));
		}

		private void solve(int index) {
			if(index == 0 && !hasAlternative) {
				solveDeterministic();
				return;
			}
			if(physicalEmissionRefs.size() > bestEmissionCount
				|| bestEmissionCount != Integer.MAX_VALUE
					&& Math.addExact(physicalEmissionRefs.size(),
						additionalPhysicalEmissionLowerBound(index)) > bestEmissionCount)
				return;
			if(index == demands.size()) {
				if(requiredEmitted != null && !emitted.equals(requiredEmitted))
					return;
				// Exact normalized signatures are expanded once while constructing the
				// immutable order index. Sorting and comparing their integer ranks here is
				// byte-for-byte equivalent to the historical canonical string order without
				// rebuilding deeply nested identities at every relocation-search leaf.
				List<RankedChoice> ordered = current.stream()
					.sorted(Comparator.comparingInt(RankedChoice::rank)).toList();
				List<RelocationChoiceReceipt> candidate = ordered.stream()
					.map(RankedChoice::receipt).toList();
				List<Integer> choiceRanks = ordered.stream().map(RankedChoice::rank).toList();
				if(physicalEmissionRefs.size() < bestEmissionCount
					|| physicalEmissionRefs.size() == bestEmissionCount
					&& (bestChoiceRanks == null
						|| compareRanks(choiceRanks, bestChoiceRanks) < 0)) {
					bestEmissionCount = physicalEmissionRefs.size();
					bestChoiceRanks = choiceRanks;
					best = List.copyOf(candidate);
					// With one option per canonical demand there is one deterministic
					// action vector and therefore no action-order tie to resolve. Reusing
					// insertion order avoids repeatedly expanding deeply nested identities.
					bestEmitted = !hasAlternative ? List.copyOf(emitted)
						: canonicalEmittedActions(emitted, order);
				}
				return;
			}
			DemandOptions demand = demands.get(index);
			// Find a low-emission incumbent first.  Canonical choice order remains the
			// exact leaf tie-break; changing traversal order removes no solution.
			for(int incrementalClass = 0; incrementalClass <= 1; incrementalClass++)
				for(Option option : demand.options()) {
					if(!anchorBindings.compatible(option)
						|| incrementalPhysicalEmission(option) != incrementalClass)
						continue;
					if(!anchorBindings.acquire(option))
						throw new IllegalStateException("Compatible relocation option was not acquirable");
					RelocationActionKey action = option.action().key();
					boolean added = option.requiresEmission() && emitted.add(action);
					Integer physical = added ? order.physicalEmissionId(action) : null;
					if(added)
						physicalEmissionRefs.merge(physical, 1, Integer::sum);
					current.add(new RankedChoice(
						new RelocationChoiceReceipt(demand.demand(), action), option.choiceRank()));
					solve(index + 1);
					current.remove(current.size() - 1);
					if(added) {
						emitted.remove(action);
						decrementPhysicalRef(physicalEmissionRefs, physical);
					}
					anchorBindings.release(option);
				}
		}

		private int incrementalPhysicalEmission(Option option) {
			if(!option.requiresEmission())
				return 0;
			int physical = order.physicalEmissionId(option.action().key());
			return physicalEmissionRefs.containsKey(physical) ? 0 : 1;
		}

		/**
		 * Admissible packing bound for the still-unresolved exact demands.  A demand
		 * contributes no new physical emission when it has a compatible direct option
		 * or can reuse an already selected physical upload.  Otherwise its compatible
		 * physical IDs form an alternative set.  Every pairwise-disjoint set in a
		 * greedy packing needs a distinct future emission, so the packing cardinality
		 * is a safe lower bound (not a candidate-space restriction).
		 */
		private int additionalPhysicalEmissionLowerBound(int index) {
			List<Set<Integer>> alternatives = new ArrayList<>();
			for(int demandIndex = index; demandIndex < demands.size(); demandIndex++) {
				Set<Integer> physicalOptions = new LinkedHashSet<>();
				boolean alreadyCovered = false;
				boolean compatible = false;
				for(Option option : demands.get(demandIndex).options()) {
					if(!anchorBindings.compatible(option))
						continue;
					compatible = true;
					if(!option.requiresEmission()) {
						alreadyCovered = true;
						break;
					}
					int physical = order.physicalEmissionId(option.action().key());
					if(physicalEmissionRefs.containsKey(physical)) {
						alreadyCovered = true;
						break;
					}
					physicalOptions.add(physical);
				}
				if(!compatible)
					return Integer.MAX_VALUE - physicalEmissionRefs.size();
				if(!alreadyCovered && !physicalOptions.isEmpty())
					alternatives.add(Set.copyOf(physicalOptions));
			}
			alternatives.sort(Comparator.comparingInt(Set::size));
			Set<Integer> covered = new HashSet<>();
			int bound = 0;
			for(Set<Integer> optionSet : alternatives) {
				if(optionSet.stream().anyMatch(covered::contains))
					continue;
				bound++;
				covered.addAll(optionSet);
			}
			return bound;
		}

		private void solveDeterministic() {
			List<RankedChoice> choices = new ArrayList<>(demands.size());
			Set<RelocationActionKey> selectedEmitted = new LinkedHashSet<>();
			Set<Integer> physical = new LinkedHashSet<>();
			AnchorBindings bindings = new AnchorBindings();
			for(DemandOptions demand : demands) {
				Option option = demand.options().get(0);
				if(!bindings.acquire(option))
					return;
				RelocationActionKey action = option.action().key();
				choices.add(new RankedChoice(
					new RelocationChoiceReceipt(demand.demand(), action), option.choiceRank()));
				if(option.requiresEmission() && selectedEmitted.add(action))
					physical.add(order.physicalEmissionId(action));
			}
			if(requiredEmitted != null && !selectedEmitted.equals(requiredEmitted))
				return;
			best = choices.stream().sorted(Comparator.comparingInt(RankedChoice::rank))
				.map(RankedChoice::receipt).toList();
			bestEmitted = List.copyOf(selectedEmitted);
			bestEmissionCount = physical.size();
		}
	}

	private static final class WeightedSearch {
		private final List<DemandOptions> demands;
		private final ToDoubleFunction<RelocationActionKey> emittedActionCost;
		private final List<RankedChoice> current = new ArrayList<>();
		private final Set<RelocationActionKey> emitted = new LinkedHashSet<>();
		private final Map<Integer,Integer> physicalEmissionRefs = new LinkedHashMap<>();
		private final Map<Integer,Double> physicalEmissionCosts = new LinkedHashMap<>();
		private final AnchorBindings anchorBindings = new AnchorBindings();
		private final CanonicalOrderIndex order;
		private final boolean hasAlternative;
		private List<RelocationChoiceReceipt> best;
		private List<RelocationActionKey> bestEmitted = List.of();
		private double bestCost = Double.POSITIVE_INFINITY;
		private int bestEmissionCount = Integer.MAX_VALUE;
		private List<Integer> bestChoiceRanks;

		private WeightedSearch(List<DemandOptions> demands,
			ToDoubleFunction<RelocationActionKey> emittedActionCost, CanonicalOrderIndex order) {
			this.demands = List.copyOf(demands);
			this.emittedActionCost = emittedActionCost;
			this.order = Objects.requireNonNull(order, "order");
			this.hasAlternative = demands.stream().anyMatch(demand -> demand.options().size() > 1);
		}

		private void solve(int index, double cost) {
			if(Double.compare(cost, bestCost) > 0)
				return;
			if(index == demands.size()) {
				List<RankedChoice> ordered = current.stream()
					.sorted(Comparator.comparingInt(RankedChoice::rank)).toList();
				List<RelocationChoiceReceipt> candidate = ordered.stream()
					.map(RankedChoice::receipt).toList();
				List<Integer> choiceRanks = ordered.stream().map(RankedChoice::rank).toList();
				int costOrder = Double.compare(cost, bestCost);
				if(costOrder < 0 || costOrder == 0
					&& (physicalEmissionRefs.size() < bestEmissionCount
					|| physicalEmissionRefs.size() == bestEmissionCount
						&& (bestChoiceRanks == null
							|| compareRanks(choiceRanks, bestChoiceRanks) < 0))) {
					bestCost = cost;
					bestEmissionCount = physicalEmissionRefs.size();
					bestChoiceRanks = choiceRanks;
					best = List.copyOf(candidate);
					bestEmitted = !hasAlternative ? List.copyOf(emitted)
						: canonicalEmittedActions(emitted, order);
				}
				return;
			}
			DemandOptions demand = demands.get(index);
			for(Option option : demand.options()) {
				if(!anchorBindings.acquire(option))
					continue;
				RelocationActionKey action = option.action().key();
				boolean added = option.requiresEmission() && emitted.add(action);
				Integer physical = added ? order.physicalEmissionId(action) : null;
				double incremental = 0.0;
				if(added) {
					double actionCost = emittedActionCost.applyAsDouble(action);
					if(!Double.isFinite(actionCost) || actionCost < 0.0)
						throw new IllegalArgumentException("Relocation action cost must be finite and non-negative: "
							+ action.normalizedSignature() + " cost=" + actionCost);
					Double physicalCost = physicalEmissionCosts.get(physical);
					if(physicalCost == null) {
						physicalEmissionCosts.put(physical, actionCost);
						incremental = actionCost;
					}
					else {
						double tolerance = 1e-9 * Math.max(1.0, Math.max(physicalCost, actionCost));
						if(Math.abs(physicalCost - actionCost) > tolerance)
							throw new IllegalArgumentException(
								"Consumer-specific receipts for one physical relocation have different costs: "
									+ physical + " first=" + physicalCost + " current=" + actionCost);
					}
					physicalEmissionRefs.merge(physical, 1, Integer::sum);
				}
				current.add(new RankedChoice(
					new RelocationChoiceReceipt(demand.demand(), action), option.choiceRank()));
				solve(index + 1, cost + incremental);
				current.remove(current.size() - 1);
				if(added) {
					emitted.remove(action);
					if(decrementPhysicalRef(physicalEmissionRefs, physical))
						physicalEmissionCosts.remove(physical);
				}
				anchorBindings.release(option);
			}
		}
	}

	private static List<RelocationActionKey> canonicalEmittedActions(
		Collection<RelocationActionKey> actions, CanonicalOrderIndex order) {
		return actions.stream()
			.sorted(Comparator.comparingInt(order::actionRank)).toList();
	}

	private static int compareRanks(List<Integer> left, List<Integer> right) {
		int size = Math.min(left.size(), right.size());
		for(int index = 0; index < size; index++) {
			int comparison = Integer.compare(left.get(index), right.get(index));
			if(comparison != 0)
				return comparison;
		}
		return Integer.compare(left.size(), right.size());
	}

	/**
	 * A FED instruction executes against one worker/range placement.  Candidate
	 * rows may offer several exact anchors (for example, retain the left input's
	 * pool and REFED the right input, or vice versa), but receipts selected for
	 * one consumer must all choose the same durable anchor.  Tracking reference
	 * counts keeps this constraint exact even though MRV ordering can interleave
	 * demands from different consumers.
	 */
	private static final class AnchorBindings {
		private final Map<CompiledHopKey,DurableAnchorKey> anchors = new LinkedHashMap<>();
		private final Map<CompiledHopKey,Integer> refs = new LinkedHashMap<>();

		private boolean compatible(Option option) {
			DurableAnchorKey selected = anchors.get(option.obligation().consumer());
			return selected == null || selected.equals(option.action().key().durableAnchor());
		}

		private boolean acquire(Option option) {
			CompiledHopKey consumer = option.obligation().consumer();
			DurableAnchorKey anchor = option.action().key().durableAnchor();
			DurableAnchorKey selected = anchors.get(consumer);
			if(selected != null && !selected.equals(anchor))
				return false;
			anchors.putIfAbsent(consumer, anchor);
			refs.merge(consumer, 1, Integer::sum);
			return true;
		}

		private void release(Option option) {
			CompiledHopKey consumer = option.obligation().consumer();
			Integer count = refs.get(consumer);
			if(count == null || count <= 0)
				throw new IllegalStateException("Durable-anchor reference count is missing: "
					+ consumer.normalizedSignature());
			if(count == 1) {
				refs.remove(consumer);
				anchors.remove(consumer);
			}
			else
				refs.put(consumer, count - 1);
		}
	}

	/** Returns true when the physical identity was removed completely. */
	private static <T> boolean decrementPhysicalRef(Map<T,Integer> refs, T identity) {
		Integer count = refs.get(identity);
		if(count == null || count <= 0)
			throw new IllegalStateException("Physical relocation reference count is missing: " + identity);
		if(count == 1) {
			refs.remove(identity);
			return true;
		}
		refs.put(identity, count - 1);
		return false;
	}
}
