/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable, graph-agnostic owner/provenance carrier for one already selected Exact result. */
public final class ExactPlacementInput implements AFederatedPlanner.PlannerInvocationReceipt {
	public record ProducerReceipt(String analysisFingerprint, long objectiveBits) {
		public ProducerReceipt {
			analysisFingerprint = Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
		}
	}
	public record OccurrenceReceipt(CompiledHopKey planningKey, Hop planningHop, long planningHopId,
		Hop executableHop, long executableHopId, ExecType execType, FederatedOutput output) {
		public OccurrenceReceipt {
			Objects.requireNonNull(planningKey, "planningKey");
			Objects.requireNonNull(planningHop, "planningHop");
			Objects.requireNonNull(executableHop, "executableHop");
			Objects.requireNonNull(output, "output");
		}
	}
	private final PlacementAnalysis owner;
	private final ProducerReceipt producer;
	private final List<OccurrenceReceipt> occurrences;
	private final boolean appliedStateRequired;
	private final Map<CompiledHopKey, PlacementState> exactSelectedStates;
	private final NormalizedPlannerResult normalizedResult;
	private final PlacementEmissionReceipt emissionReceipt;

	private ExactPlacementInput(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences,
		boolean appliedStateRequired, Map<CompiledHopKey, PlacementState> exactSelectedStates,
		NormalizedPlannerResult normalizedResult, PlacementEmissionReceipt emissionReceipt) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.producer = Objects.requireNonNull(producer, "producer");
		this.occurrences = List.copyOf(occurrences);
		this.appliedStateRequired = appliedStateRequired;
		IdentityHashMap<CompiledHopKey, PlacementState> exact = new IdentityHashMap<>();
		exact.putAll(Objects.requireNonNull(exactSelectedStates, "exactSelectedStates"));
		this.exactSelectedStates = Collections.unmodifiableMap(exact);
		this.normalizedResult = normalizedResult;
		this.emissionReceipt = emissionReceipt;
		validateOwnerBinding();
	}

	public static ExactPlacementInput create(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences) {
		return new ExactPlacementInput(owner, producer, occurrences, true, Map.of(), null, null);
	}

	public static ExactPlacementInput createSelected(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences,
		Map<CompiledHopKey, PlacementState> exactSelectedStates) {
		NormalizedPlannerResult normalized = NormalizedPlannerResults.create(owner, "Exact", exactSelectedStates,
			"objective=" + producer.objectiveBits());
		return new ExactPlacementInput(owner, producer, occurrences, false,
			exactSelectedStates, normalized, null);
	}

	/** Builds a selected carrier from an exact, already-normalized Exact projection. */
	public static ExactPlacementInput createSelected(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences,
		Map<CompiledHopKey, PlacementState> exactSelectedStates,
		NormalizedPlannerResult exactNormalizedResult) {
		return new ExactPlacementInput(owner, producer, occurrences, false,
			exactSelectedStates, Objects.requireNonNull(exactNormalizedResult, "exactNormalizedResult"), null);
	}

	public ExactPlacementInput withEmissionReceipt(PlacementEmissionReceipt receipt) {
		return new ExactPlacementInput(owner, producer, occurrences, false,
			exactSelectedStates, normalizedResult, Objects.requireNonNull(receipt, "receipt"));
	}

	@Override public PlacementAnalysis analysis() { return owner; }
	public ProducerReceipt producerReceipt() { return producer; }
	public List<OccurrenceReceipt> occurrenceReceipts() { return occurrences; }
	public Map<CompiledHopKey, PlacementState> exactSelectedStates() { return exactSelectedStates; }
	public NormalizedPlannerResult normalizedResult() { return normalizedResult; }
	public PlacementEmissionReceipt emissionReceipt() { return emissionReceipt; }

	void validateUnchanged() {
		validateOwnerBinding();
		if(emissionReceipt != null) {
			if(normalizedResult == null || !org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction
				.canonicalPlanHash(normalizedResult).equals(emissionReceipt.planHash()))
				throw new IllegalArgumentException("Exact canonical emission receipt differs");
			return;
		}
		owner.assertProgramStructureUnchanged();
		if(!appliedStateRequired)
			return;
		for(OccurrenceReceipt receipt : occurrences) {
			Hop hop = receipt.planningHop();
			ExecType exec = receipt.execType() == null ? null
				: (hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType());
			FederatedOutput output = receipt.execType() == null ? FederatedOutput.NONE : hop.getFederatedOutput();
			if(exec != receipt.execType() || output != receipt.output())
				throw new IllegalArgumentException("Exact occurrence state is stale");
		}
	}

	private void validateOwnerBinding() {
		if(!producer.analysisFingerprint().equals(owner.analysisFingerprint()))
			throw new IllegalArgumentException("Exact producer fingerprint differs from its analysis owner");
		if(occurrences.size() != owner.occurrences().size())
			throw new IllegalArgumentException("Incomplete Exact occurrence binding");
		for(int i = 0; i < occurrences.size(); i++) {
			OccurrenceReceipt receipt = occurrences.get(i);
			PlacementAnalysis.HopOccurrenceProjection occurrence = owner.occurrences().get(i);
			Hop hop = owner.hop(receipt.planningKey()).orElseThrow(
				() -> new IllegalArgumentException("Owner occurrence disappeared"));
			if(receipt.planningKey() != occurrence.key() || hop != occurrence.hop()
				|| hop != receipt.planningHop() || hop != receipt.executableHop()
				|| hop.getHopID() != receipt.planningHopId() || hop.getHopID() != receipt.executableHopId())
				throw new IllegalArgumentException("Owner occurrence identity changed");
		}
		if(!exactSelectedStates.isEmpty()) {
			java.util.Set<CompiledHopKey> decisionKeys = Collections.newSetFromMap(new IdentityHashMap<>());
			owner.graph().decisionNodes().forEach(node -> decisionKeys.add(node.key()));
			if(!exactSelectedStates.keySet().equals(decisionKeys))
				throw new IllegalArgumentException("Exact exact selection differs from neutral decision authority");
			for(var node : owner.graph().decisionNodes())
				if(node.legalAlternatives().stream().noneMatch(state -> state == exactSelectedStates.get(node.key())))
					throw new IllegalArgumentException("Exact exact selection contains a foreign state");
			if(normalizedResult == null || normalizedResult.analysis() != owner
				|| !normalizedResult.selectedStates().keySet().equals(decisionKeys))
				throw new IllegalArgumentException("Exact normalized result differs from exact selection");
			for(var node : owner.graph().decisionNodes()) {
				PlacementState exact = exactSelectedStates.get(node.key());
				PlacementState normalized = normalizedResult.selectedStates().get(node.key());
				if(normalized != exact || normalizedResult.selectedEmissionStates().get(node.key()) == null
					|| normalizedResult.selectedEmissionStates().get(node.key()).placementState() != exact)
					throw new IllegalArgumentException(
						"Exact normalized selection changed an exact analysis-owned state");
			}
		}
	}
}
