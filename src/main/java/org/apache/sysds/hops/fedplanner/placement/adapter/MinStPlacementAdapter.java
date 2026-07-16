/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Observational adapter for an already selected, owner-bound MinST graph. */
public final class MinStPlacementAdapter {
	public record SelectedReceipt(CompiledHopKey planningKey, Hop planningHop, long planningHopId,
		Hop executableHop, long executableHopId, ExecType execType, FederatedOutput output) {
		public SelectedReceipt {
			Objects.requireNonNull(planningKey, "planningKey");
			Objects.requireNonNull(planningHop, "planningHop");
			Objects.requireNonNull(executableHop, "executableHop");
			Objects.requireNonNull(execType, "execType");
			Objects.requireNonNull(output, "output");
		}
	}

	public record Selection(PlacementAnalysis analysis, FederatedPlanMinSTGraph producer,
		String analysisFingerprint, long cutObjectiveBits, List<Long> sourcePartitionNodeIds,
		List<SelectedReceipt> selectedReceipts, List<SelectedObligation> selectedObligations) {
		public Selection {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(producer, "producer");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			sourcePartitionNodeIds = List.copyOf(sourcePartitionNodeIds);
			selectedReceipts = List.copyOf(selectedReceipts);
			selectedObligations = List.copyOf(selectedObligations);
		}
	}

	public Selection selectExact(PlacementAnalysis requested, MinStPlacementInput ownerBound) {
		Objects.requireNonNull(requested, "requested");
		Objects.requireNonNull(ownerBound, "ownerBound");
		if (requested != ownerBound.owner())
			throw new IllegalArgumentException("Requested analysis is not the exact bound owner");
		ownerBound.validateUnchanged();
		List<SelectedReceipt> receipts = new ArrayList<>();
		for (MinStPlacementInput.OccurrenceBinding binding : ownerBound.bindings()) {
			Hop hop = binding.hop;
			ExecType exec = hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
			FederatedOutput output = hop.getFederatedOutput();
			if (exec == null || output == null)
				throw new IllegalArgumentException("Selected occurrence has incomplete executable state");
			receipts.add(new SelectedReceipt(binding.occurrence.key(), hop, hop.getHopID(),
				hop, hop.getHopID(), exec, output));
		}
		return new Selection(requested, ownerBound.selectedGraph(), requested.analysisFingerprint(),
			ownerBound.cutObjectiveBits(), ownerBound.sourcePartitionNodeIds(), receipts,
			ownerBound.selectedObligations());
	}
}
