/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.List;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Immutable, graph-agnostic owner/provenance carrier for one already selected MinST result. */
public final class MinStPlacementInput implements AFederatedPlanner.PlannerInvocationReceipt {
	public record ProducerReceipt(String analysisFingerprint, long cutObjectiveBits,
		List<Long> sourcePartitionNodeIds) {
		public ProducerReceipt {
			analysisFingerprint = Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			sourcePartitionNodeIds = List.copyOf(sourcePartitionNodeIds);
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
	public record ObligationReceipt(String kind, long childHopId, long originalHopId, String domainId,
		List<Long> consumerHopIds, FType fType, boolean capability, String capabilityReason, String reason) {
		public ObligationReceipt {
			kind = Objects.requireNonNull(kind, "kind");
			domainId = Objects.requireNonNull(domainId, "domainId");
			consumerHopIds = List.copyOf(consumerHopIds);
			capabilityReason = Objects.requireNonNull(capabilityReason, "capabilityReason");
			reason = Objects.requireNonNull(reason, "reason");
		}
	}

	private final PlacementAnalysis owner;
	private final ProducerReceipt producer;
	private final List<OccurrenceReceipt> occurrences;
	private final List<ObligationReceipt> obligations;
	private final boolean appliedStateRequired;
	private final PlacementEmissionReceipt emissionReceipt;

	private MinStPlacementInput(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences, List<ObligationReceipt> obligations,
		boolean appliedStateRequired, PlacementEmissionReceipt emissionReceipt) {
		this.owner = Objects.requireNonNull(owner, "owner");
		this.producer = Objects.requireNonNull(producer, "producer");
		this.occurrences = List.copyOf(occurrences);
		this.obligations = List.copyOf(obligations);
		this.appliedStateRequired = appliedStateRequired;
		this.emissionReceipt = emissionReceipt;
		validateOwnerBinding();
	}

	public static MinStPlacementInput create(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences, List<ObligationReceipt> obligations) {
		return new MinStPlacementInput(owner, producer, occurrences, obligations, true, null);
	}

	public static MinStPlacementInput createSelected(PlacementAnalysis owner, ProducerReceipt producer,
		List<OccurrenceReceipt> occurrences, List<ObligationReceipt> obligations) {
		return new MinStPlacementInput(owner, producer, occurrences, obligations, false, null);
	}

	public MinStPlacementInput withEmissionReceipt(PlacementEmissionReceipt receipt) {
		return new MinStPlacementInput(owner, producer, occurrences, obligations, false,
			Objects.requireNonNull(receipt, "receipt"));
	}

	@Override public PlacementAnalysis analysis() { return owner; }
	public ProducerReceipt producerReceipt() { return producer; }
	public List<OccurrenceReceipt> occurrenceReceipts() { return occurrences; }
	public List<ObligationReceipt> obligationReceipts() { return obligations; }
	public PlacementEmissionReceipt emissionReceipt() { return emissionReceipt; }

	void validateUnchanged() {
		validateOwnerBinding();
		owner.assertProgramStructureUnchanged();
		if(!appliedStateRequired)
			return;
		for(OccurrenceReceipt receipt : occurrences) {
			Hop hop = receipt.planningHop();
			ExecType exec = receipt.execType() == null ? null
				: (hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType());
			FederatedOutput output = receipt.execType() == null ? FederatedOutput.NONE : hop.getFederatedOutput();
			if(exec != receipt.execType() || output != receipt.output())
				throw new IllegalArgumentException("MinST occurrence state is stale");
		}
	}

	private void validateOwnerBinding() {
		if(!producer.analysisFingerprint().equals(owner.analysisFingerprint()))
			throw new IllegalArgumentException("MinST producer fingerprint differs from its analysis owner");
		if(occurrences.size() != owner.occurrences().size())
			throw new IllegalArgumentException("Incomplete MinST occurrence binding");
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
	}
}
