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
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput.ObligationReceipt;
import org.apache.sysds.hops.fedplanner.placement.adapter.MinStPlacementInput.ProducerReceipt;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Observational adapter for an already selected, owner-bound MinST result. */
public final class MinStPlacementAdapter {
	public record SelectedReceipt(CompiledHopKey planningKey, Hop planningHop, long planningHopId,
		Hop executableHop, long executableHopId, ExecType execType, FederatedOutput output) { }
	public record Selection(PlacementAnalysis analysis, ProducerReceipt producer,
		String analysisFingerprint, long cutObjectiveBits, List<Long> sourcePartitionNodeIds,
		List<SelectedReceipt> selectedReceipts, List<ObligationReceipt> selectedObligations) {
		public Selection {
			Objects.requireNonNull(analysis, "analysis"); Objects.requireNonNull(producer, "producer");
			analysisFingerprint = Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			sourcePartitionNodeIds = List.copyOf(sourcePartitionNodeIds);
			selectedReceipts = List.copyOf(selectedReceipts);
			selectedObligations = List.copyOf(selectedObligations);
		}
	}

	public Selection select(PlacementAnalysis requested, MinStPlacementInput ownerBound) {
		Objects.requireNonNull(requested, "requested"); Objects.requireNonNull(ownerBound, "ownerBound");
		if(requested != ownerBound.analysis())
			throw new IllegalArgumentException("Requested analysis is not the exact bound owner");
		ownerBound.validateUnchanged();
		List<SelectedReceipt> receipts = ownerBound.occurrenceReceipts().stream().map(r ->
			new SelectedReceipt(r.planningKey(), r.planningHop(), r.planningHopId(), r.executableHop(),
				r.executableHopId(), r.execType(), r.output())).toList();
		ProducerReceipt producer = ownerBound.producerReceipt();
		return new Selection(requested, producer, requested.analysisFingerprint(), producer.cutObjectiveBits(),
			producer.sourcePartitionNodeIds(), receipts, ownerBound.obligationReceipts());
	}
}
