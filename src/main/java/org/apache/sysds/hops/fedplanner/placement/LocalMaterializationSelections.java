/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

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
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationObligation;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Canonical selection and lowering authority for exact FOUT-to-local materializations. */
public final class LocalMaterializationSelections {
	private LocalMaterializationSelections() { }

	/**
	 * Counts the physical local materializations implied by one exact placement and
	 * candidate-row selection. This is the same projection used during lowering.
	 */
	public static int physicalEmissionCount(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(selected, "selected");
		Objects.requireNonNull(selectedCandidates, "selectedCandidates");
		Map<CompiledHopKey,CandidateSelectionReceipt> candidatesByConsumer = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : selectedCandidates) {
			CompiledHopKey consumer = candidate.rule().parentOccurrence();
			if(candidatesByConsumer.put(consumer, candidate) != null)
				throw new IllegalArgumentException("Selected candidate is duplicated for one consumer");
			PlacementState state = selected.get(consumer);
			if(state == null || !candidate.emission().emissionState().placementState().equals(state))
				throw new IllegalArgumentException("Candidate emission differs from selected placement");
		}
		Set<CompiledHopKey> eligible = Collections.newSetFromMap(new IdentityHashMap<>());
		for(var node : analysis.graph().decisionNodes()) {
			PlacementState producer = selected.get(node.key());
			CandidateSelectionReceipt candidate = candidatesByConsumer.get(node.key());
			boolean derivedFout = candidate != null
				&& candidate.emission().emissionState().derivedFedFout();
			if(producer != null && producer.execType() == ExecType.FED
				&& producer.output() == FederatedOutput.FOUT && producer.fType() != null
				&& !derivedFout)
				eligible.add(node.key());
		}
		Set<CompiledHopKey> materialized = Collections.newSetFromMap(new IdentityHashMap<>());
		for(PlacementAnalysis.CompiledInputEdgeFact edge :
			analysis.compiledInputEdgesInCanonicalOrder()) {
			if(!eligible.contains(edge.producer()) || analysis.isDmlFunctionCallBoundary(edge.consumer()))
				continue;
			if(requiresLocalInput(analysis, edge, selected.get(edge.consumer()),
				candidatesByConsumer.get(edge.consumer())))
				materialized.add(edge.producer());
		}
		for(PlacementAnalysis.LogicalFunctionInputFact fact :
			analysis.logicalFunctionInputsInCanonicalOrder()) {
			if(!eligible.contains(fact.sourceArgument()))
				continue;
			PlacementState formal = selected.get(fact.targetRead());
			if(formal == null || formal.execType() != ExecType.CP
				|| formal.output() != FederatedOutput.LOUT)
				continue;
			CompiledHopKey call = analysis.requireExactPhysicalFunctionInputConsumer(fact);
			if(selected.get(call) == null)
				throw new IllegalArgumentException(
					"Selected local function formal has no selected physical call owner");
			materialized.add(fact.sourceArgument());
		}
		return materialized.size();
	}

	/**
	 * Compiles the exact local-materialization projection for one complete placement
	 * assignment. Candidate row choice is the only remaining variable, so the hot
	 * exact search can score a row product without rebuilding identity maps or
	 * rescanning every graph edge at every leaf. The public method above remains the
	 * canonical lowering-aligned implementation and is used to verify each incumbent.
	 */
	static ExactPhysicalEmissionScorer exactPhysicalEmissionScorer(
		PlacementAnalysis analysis, Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> candidateUniverse) {
		return new ExactPhysicalEmissionScorer(new ExactProjectionIndex(analysis),
			selected, candidateUniverse);
	}

	static ExactPhysicalEmissionScorer exactPhysicalEmissionScorer(
		ExactProjectionIndex index, Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> candidateUniverse) {
		return new ExactPhysicalEmissionScorer(index, selected, candidateUniverse);
	}

	/** Immutable graph projection shared by every complete leaf of one exact search. */
	static final class ExactProjectionIndex {
		private final PlacementAnalysis analysis;
		private final List<NeutralPlacementGraph.Node> decisionNodes;
		private final Map<CompiledHopKey,List<PlacementAnalysis.CompiledInputEdgeFact>>
			edgesByProducer;
		private final Map<CompiledHopKey,List<IndexedFunctionInput>> functionInputsBySource;

		ExactProjectionIndex(PlacementAnalysis analysis) {
			this.analysis = Objects.requireNonNull(analysis, "analysis");
			this.decisionNodes = analysis.graph().decisionNodes();
			Map<CompiledHopKey,List<PlacementAnalysis.CompiledInputEdgeFact>> edges =
				new IdentityHashMap<>();
			for(PlacementAnalysis.CompiledInputEdgeFact edge :
				analysis.compiledInputEdgesInCanonicalOrder())
				if(!analysis.isDmlFunctionCallBoundary(edge.consumer()))
					edges.computeIfAbsent(edge.producer(), ignored -> new ArrayList<>()).add(edge);
			this.edgesByProducer = immutableIdentityLists(edges);
			Map<CompiledHopKey,List<IndexedFunctionInput>> functions = new IdentityHashMap<>();
			for(PlacementAnalysis.LogicalFunctionInputFact fact :
				analysis.logicalFunctionInputsInCanonicalOrder())
				functions.computeIfAbsent(fact.sourceArgument(), ignored -> new ArrayList<>())
					.add(new IndexedFunctionInput(fact,
						analysis.requireExactPhysicalFunctionInputConsumer(fact)));
			this.functionInputsBySource = immutableIdentityLists(functions);
		}

		private static <T> Map<CompiledHopKey,List<T>> immutableIdentityLists(
			Map<CompiledHopKey,List<T>> source) {
			Map<CompiledHopKey,List<T>> result = new IdentityHashMap<>();
			for(Map.Entry<CompiledHopKey,List<T>> entry : source.entrySet())
				result.put(entry.getKey(), List.copyOf(entry.getValue()));
			return Collections.unmodifiableMap(result);
		}
	}

	private record IndexedFunctionInput(
		PlacementAnalysis.LogicalFunctionInputFact fact, CompiledHopKey call) { }

	static final class ExactPhysicalEmissionScorer {
		private final Map<CandidateSelectionReceipt,Integer> receiptIds;
		private final boolean[] selectedReceipts;
		private final ScoredProducer[] producers;
		private final int[][] affectedProducerIdsByReceipt;
		private int physicalEmissionCount;

		private ExactPhysicalEmissionScorer(ExactProjectionIndex index,
			Map<CompiledHopKey, PlacementState> selected,
			List<CandidateSelectionReceipt> candidateUniverse) {
			Objects.requireNonNull(index, "index");
			Objects.requireNonNull(selected, "selected");
			Objects.requireNonNull(candidateUniverse, "candidateUniverse");
			Map<CandidateSelectionReceipt,Integer> ids = new IdentityHashMap<>();
			Map<CompiledHopKey,List<CandidateSelectionReceipt>> byConsumer =
				new IdentityHashMap<>();
			for(CandidateSelectionReceipt receipt : candidateUniverse) {
				if(ids.putIfAbsent(receipt, ids.size()) != null)
					continue;
				CompiledHopKey consumer = receipt.rule().parentOccurrence();
				PlacementState consumerState = selected.get(consumer);
				if(consumerState == null
					|| !receipt.emission().emissionState().placementState().equals(consumerState))
					throw new IllegalArgumentException(
						"Candidate emission differs from selected placement");
				byConsumer.computeIfAbsent(consumer, ignored -> new ArrayList<>()).add(receipt);
			}
			this.receiptIds = Collections.unmodifiableMap(ids);
			this.selectedReceipts = new boolean[ids.size()];

			List<ScoredProducer> scored = new ArrayList<>();
			for(var node : index.decisionNodes) {
				PlacementState producer = selected.get(node.key());
				if(producer == null || producer.execType() != ExecType.FED
					|| producer.output() != FederatedOutput.FOUT || producer.fType() == null)
					continue;
				List<Integer> derived = new ArrayList<>();
				for(CandidateSelectionReceipt receipt :
					byConsumer.getOrDefault(node.key(), List.of()))
					if(receipt.emission().emissionState().derivedFedFout())
						derived.add(ids.get(receipt));
				boolean baseRequired = false;
				List<Integer> rowRequired = new ArrayList<>();
				for(PlacementAnalysis.CompiledInputEdgeFact edge :
					index.edgesByProducer.getOrDefault(node.key(), List.of())) {
					PlacementState consumer = selected.get(edge.consumer());
					if(consumer == null)
						continue;
					if(consumer.execType() == ExecType.CP
						&& consumer.output() == FederatedOutput.LOUT
						&& !index.analysis.isCoordinatorMetadataOnlyInput(edge)) {
						baseRequired = true;
						continue;
					}
					if(consumer.execType() != ExecType.FED)
						continue;
					List<CandidateSelectionReceipt> rows =
						byConsumer.getOrDefault(edge.consumer(), List.of());
					if(rows.isEmpty())
						throw new IllegalArgumentException(
							"Federated consumer is missing its exact candidate authority");
					for(CandidateSelectionReceipt receipt : rows) {
						int position = edge.inputPosition();
						if(position < 0 || position >= receipt.rule().orderedInputs().size())
							throw new IllegalArgumentException(
								"Candidate does not cover an exact compiled input edge");
						if(!receipt.rule().orderedInputs().get(position).present())
							rowRequired.add(ids.get(receipt));
					}
				}
				for(IndexedFunctionInput indexedFunction :
					index.functionInputsBySource.getOrDefault(node.key(), List.of())) {
					PlacementAnalysis.LogicalFunctionInputFact fact = indexedFunction.fact();
					PlacementState formal = selected.get(fact.targetRead());
					if(formal == null || formal.execType() != ExecType.CP
						|| formal.output() != FederatedOutput.LOUT)
						continue;
					CompiledHopKey call = indexedFunction.call();
					if(selected.get(call) == null)
						throw new IllegalArgumentException(
							"Selected local function formal has no selected physical call owner");
					baseRequired = true;
				}
				scored.add(new ScoredProducer(baseRequired,
					derived.stream().mapToInt(Integer::intValue).toArray(),
					rowRequired.stream().distinct().mapToInt(Integer::intValue).toArray()));
			}
			this.producers = scored.toArray(ScoredProducer[]::new);
			List<Set<Integer>> affected = new ArrayList<>(ids.size());
			for(int receiptId = 0; receiptId < ids.size(); receiptId++)
				affected.add(new LinkedHashSet<>());
			for(int producerId = 0; producerId < producers.length; producerId++) {
				ScoredProducer producer = producers[producerId];
				for(int receiptId : producer.derivedReceiptIds())
					affected.get(receiptId).add(producerId);
				for(int receiptId : producer.localRequirementReceiptIds())
					affected.get(receiptId).add(producerId);
				if(producer.baseRequired())
					physicalEmissionCount++;
			}
			this.affectedProducerIdsByReceipt = new int[ids.size()][];
			for(int receiptId = 0; receiptId < ids.size(); receiptId++)
				this.affectedProducerIdsByReceipt[receiptId] = affected.get(receiptId).stream()
					.mapToInt(Integer::intValue).toArray();
		}

		void selectReceipt(CandidateSelectionReceipt receipt) {
			Integer id = receiptIds.get(receipt);
			if(id == null || selectedReceipts[id])
				throw new IllegalStateException(
					"Exact local scorer receipt selection is inconsistent");
			updateReceiptSelection(id, true);
		}

		void deselectReceipt(CandidateSelectionReceipt receipt) {
			Integer id = receiptIds.get(receipt);
			if(id == null || !selectedReceipts[id])
				throw new IllegalStateException(
					"Exact local scorer receipt deselection is inconsistent");
			updateReceiptSelection(id, false);
		}

		int physicalEmissionCount() {
			return physicalEmissionCount;
		}

		private void updateReceiptSelection(int receiptId, boolean selected) {
			for(int producerId : affectedProducerIdsByReceipt[receiptId])
				if(emissionRequired(producers[producerId]))
					physicalEmissionCount--;
			selectedReceipts[receiptId] = selected;
			for(int producerId : affectedProducerIdsByReceipt[receiptId])
				if(emissionRequired(producers[producerId]))
					physicalEmissionCount++;
		}

		private boolean emissionRequired(ScoredProducer producer) {
			return !anySelected(producer.derivedReceiptIds())
				&& (producer.baseRequired()
					|| anySelected(producer.localRequirementReceiptIds()));
		}

		private boolean anySelected(int[] receiptIds) {
			for(int id : receiptIds)
				if(selectedReceipts[id])
					return true;
			return false;
		}
	}

	private record ScoredProducer(boolean baseRequired, int[] derivedReceiptIds,
		int[] localRequirementReceiptIds) { }

	/** Canonical lowering authority derived from exact selected placements and candidate rows. */
	public static List<LocalMaterializationActionKey> derive(PlacementAnalysis analysis,
		Map<CompiledHopKey, PlacementState> selected,
		Map<CompiledHopKey, PlacementEmissionState> selectedEmissionStates,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(selected, "selected");
		Objects.requireNonNull(selectedEmissionStates, "selectedEmissionStates");
		Objects.requireNonNull(selectedCandidates, "selectedCandidates");
		Map<CompiledHopKey,CandidateSelectionReceipt> candidatesByConsumer = new IdentityHashMap<>();
		for(CandidateSelectionReceipt candidate : selectedCandidates)
			if(candidatesByConsumer.put(candidate.rule().parentOccurrence(), candidate) != null)
				throw new IllegalArgumentException("Selected candidate is duplicated for one consumer");
		List<LocalMaterializationActionKey> result = new ArrayList<>();
		for(var node : analysis.graph().decisionNodes()) {
			PlacementState producer = selected.get(node.key());
			PlacementEmissionState producerEmission = exactEmissionState(
				selectedEmissionStates, node.key());
			if(producer == null || producer.execType() != ExecType.FED
				|| producer.output() != FederatedOutput.FOUT
				|| producer.fType() == null || producerEmission == null)
				continue;
			// A derived FOUT producer physically computes FED/LOUT and uploads that
			// coordinator-local value. Its local consumers already use the base LOUT.
			if(producerEmission.derivedFedFout())
				continue;
			List<LocalMaterializationObligation> obligations = new ArrayList<>(analysis
				.compiledInputEdgesInCanonicalOrder().stream()
				.filter(edge -> edge.producer() == node.key())
				// FunctionOp placement is a call placeholder. Its local-input demand is
				// determined by the selected formal below, not by the call's own state.
				.filter(edge -> !analysis.isDmlFunctionCallBoundary(edge.consumer()))
				.filter(edge -> requiresLocalInput(analysis, edge,
					selected.get(edge.consumer()), candidatesByConsumer.get(edge.consumer())))
				.map(edge -> new LocalMaterializationObligation(edge.consumer(), edge.inputPosition(),
					selected.get(edge.consumer()))).toList());
			for(PlacementAnalysis.LogicalFunctionInputFact fact :
				analysis.logicalFunctionInputsInCanonicalOrder()) {
				if(fact.sourceArgument() != node.key())
					continue;
				PlacementState formal = selected.get(fact.targetRead());
				if(formal == null || formal.execType() != ExecType.CP
					|| formal.output() != FederatedOutput.LOUT)
					continue;
				CompiledHopKey call = analysis.requireExactPhysicalFunctionInputConsumer(fact);
				PlacementState callState = selected.get(call);
				if(callState == null)
					throw new IllegalArgumentException(
						"Selected local function formal has no selected physical call owner");
				obligations.add(new LocalMaterializationObligation(
					call, fact.callInputPosition(), callState));
			}
			obligations = obligations.stream().distinct().sorted().toList();
			if(obligations.isEmpty())
				continue;
			var occurrence = analysis.occurrences().stream()
				.filter(candidate -> candidate.key() == node.key()).findFirst().orElseThrow();
			result.add(new LocalMaterializationActionKey(node.key(), node.valueVersion(), producer,
				obligations, occurrence.scopeId() + ":" + node.key().functionNamespace(),
				durableLocalProvenance(node, producer)));
		}
		return result.stream().sorted().toList();
	}

	private static Map<CompiledHopKey, PlacementEmissionState> nativeEmissionStates(
		Map<CompiledHopKey, PlacementState> selected,
		List<CandidateSelectionReceipt> selectedCandidates) {
		Map<CompiledHopKey, PlacementEmissionState> result = new LinkedHashMap<>();
		selected.forEach((key, state) -> result.put(key, new PlacementEmissionState(state, false)));
		for(CandidateSelectionReceipt candidate : selectedCandidates) {
			CompiledHopKey consumer = candidate.rule().parentOccurrence();
			PlacementState state = selected.get(consumer);
			if(state == null)
				throw new IllegalArgumentException("Candidate selection has no selected consumer placement");
			PlacementEmissionState emission = candidate.emission().emissionState();
			if(!emission.placementState().equals(state))
				throw new IllegalArgumentException("Candidate emission differs from selected placement");
			result.put(consumer, emission);
		}
		return Map.copyOf(result);
	}

	private static boolean requiresLocalInput(PlacementAnalysis analysis,
		PlacementAnalysis.CompiledInputEdgeFact edge, PlacementState consumer,
		CandidateSelectionReceipt candidate) {
		if(consumer == null)
			return false;
		if(consumer.execType() == ExecType.CP && consumer.output() == FederatedOutput.LOUT)
			return !analysis.isCoordinatorMetadataOnlyInput(edge);
		if(consumer.execType() != ExecType.FED)
			return false;
		if(candidate == null || !candidate.emission().emissionState().placementState().equals(consumer))
			throw new IllegalArgumentException(
				"Federated consumer is missing its exact selected candidate authority");
		int inputPosition = edge.inputPosition();
		if(inputPosition < 0 || inputPosition >= candidate.rule().orderedInputs().size())
			throw new IllegalArgumentException(
				"Selected candidate does not cover an exact compiled input edge");
		return !candidate.rule().orderedInputs().get(inputPosition).present();
	}

	/** Exact analysis-owned provenance for one selected FED/FOUT source. */
	public static String durableLocalProvenance(NeutralPlacementGraph.Node node,
		PlacementState producer) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(producer, "producer");
		List<DurableAnchorKey> compatible = node.anchors().stream()
			.filter(anchor -> anchor.fType() == producer.fType()).toList();
		if(compatible.size() > 1)
			throw new IllegalStateException(
				"LOCAL source has ambiguous compatible durable anchors: " + node.key());
		if(compatible.size() == 1)
			return compatible.get(0).placementId();
		return "selected-source:" + node.valueVersion().normalizedSignature()
			+ ":occurrence:" + node.key().normalizedSignature();
	}

	private static PlacementEmissionState exactEmissionState(
		Map<CompiledHopKey, PlacementEmissionState> selected, CompiledHopKey expected) {
		for(Map.Entry<CompiledHopKey, PlacementEmissionState> entry : selected.entrySet())
			if(entry.getKey() == expected)
				return entry.getValue();
		return null;
	}
}
