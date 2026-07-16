/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.SelectedObligation;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTGraph.Vertex;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Immutable owner-provenance carrier for one already selected MinST graph. */
public final class MinStPlacementInput {
	static final class OccurrenceBinding {
		final PlacementAnalysis.HopOccurrenceProjection occurrence;
		final Vertex vertex;
		final Hop hop;

		OccurrenceBinding(PlacementAnalysis.HopOccurrenceProjection occurrence, Vertex vertex) {
			this.occurrence = occurrence;
			this.vertex = vertex;
			this.hop = occurrence.hop();
		}
	}

	private final PlacementAnalysis owner;
	private final FederatedPlanMinSTGraph selectedGraph;
	private final List<OccurrenceBinding> bindings;
	private final long cutObjectiveBits;
	private final List<Long> sourcePartitionNodeIds;
	private final List<SelectedObligation> selectedObligations;

	private MinStPlacementInput(PlacementAnalysis owner, FederatedPlanMinSTGraph selectedGraph,
		List<OccurrenceBinding> bindings, long cutObjectiveBits, List<Long> sourcePartitionNodeIds,
		List<SelectedObligation> selectedObligations) {
		this.owner = owner;
		this.selectedGraph = selectedGraph;
		this.bindings = List.copyOf(bindings);
		this.cutObjectiveBits = cutObjectiveBits;
		this.sourcePartitionNodeIds = List.copyOf(sourcePartitionNodeIds);
		this.selectedObligations = List.copyOf(selectedObligations);
	}

	public static MinStPlacementInput bind(PlacementAnalysis owner, FederatedPlanMinSTGraph selectedGraph) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(selectedGraph, "selectedGraph");
		List<OccurrenceBinding> bindings = new ArrayList<>();
		for (PlacementAnalysis.HopOccurrenceProjection occurrence : owner.occurrences()) {
			Hop ownedHop = owner.hop(occurrence.key()).orElseThrow(
				() -> new IllegalArgumentException("Missing owner occurrence: " + occurrence.key()));
			if (ownedHop != occurrence.hop())
				throw new IllegalArgumentException("Stale owner occurrence: " + occurrence.key());
			Vertex vertex = selectedGraph.getVertex(ownedHop.getHopID());
			if (vertex == null || vertex.getHopID() != ownedHop.getHopID() || vertex.getHopRef() != ownedHop)
				throw new IllegalArgumentException("MinST vertex is missing or foreign: " + occurrence.key());
			bindings.add(new OccurrenceBinding(occurrence, vertex));
		}
		if (bindings.size() != owner.occurrences().size())
			throw new IllegalArgumentException("Incomplete MinST occurrence binding");
		return new MinStPlacementInput(owner, selectedGraph, bindings,
			selectedGraph.getSelectedCutObjectiveBits(), selectedGraph.getSelectedSourcePartitionNodeIds(),
			selectedGraph.getSelectedObligations());
	}

	PlacementAnalysis owner() { return owner; }
	FederatedPlanMinSTGraph selectedGraph() { return selectedGraph; }
	List<OccurrenceBinding> bindings() { return bindings; }
	long cutObjectiveBits() { return cutObjectiveBits; }
	List<Long> sourcePartitionNodeIds() { return sourcePartitionNodeIds; }
	List<SelectedObligation> selectedObligations() { return selectedObligations; }

	void validateUnchanged() {
		if (selectedGraph.getSelectedCutObjectiveBits() != cutObjectiveBits
			|| !selectedGraph.getSelectedSourcePartitionNodeIds().equals(sourcePartitionNodeIds))
			throw new IllegalArgumentException("MinST selected snapshot is stale");
		List<SelectedObligation> current = selectedGraph.getSelectedObligations();
		if (current.size() != selectedObligations.size())
			throw new IllegalArgumentException("MinST obligation snapshot is stale");
		for (int i = 0; i < current.size(); i++)
			if (current.get(i) != selectedObligations.get(i))
				throw new IllegalArgumentException("MinST obligation identity is stale");
		for (OccurrenceBinding binding : bindings) {
			Hop hop = owner.hop(binding.occurrence.key()).orElseThrow(
				() -> new IllegalArgumentException("Owner occurrence disappeared"));
			Vertex vertex = selectedGraph.getVertex(binding.hop.getHopID());
			if (hop != binding.hop || vertex != binding.vertex || vertex.getHopRef() != binding.hop
				|| vertex.getHopID() != binding.hop.getHopID())
				throw new IllegalArgumentException("MinST occurrence binding is stale");
		}
	}
}
