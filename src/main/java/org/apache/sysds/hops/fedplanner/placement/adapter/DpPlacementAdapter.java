/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Transparent identity receipts for exclusions already produced by the neutral placement graph. */
public final class DpPlacementAdapter {
	public record GraphExclusionReceipt(PlacementAnalysis analysis,
		PlacementAnalysis.HopOccurrenceProjection occurrence, NeutralPlacementGraph.Node node,
		NeutralPlacementGraph.Exclusion exclusion) {
		public GraphExclusionReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(occurrence, "occurrence");
			Objects.requireNonNull(node, "node");
			Objects.requireNonNull(exclusion, "exclusion");
			if(analysis.occurrences().stream().noneMatch(candidate -> candidate == occurrence))
				throw new IllegalArgumentException("Occurrence is not owned by the analysis");
			if(!occurrence.key().equals(node.key()))
				throw new IllegalArgumentException("Occurrence and node keys differ");
			if(analysis.graph().node(node.key()).orElseThrow() != node)
				throw new IllegalArgumentException("Node is not owned by the analysis graph");
			if(analysis.hop(node.key()).orElseThrow() != occurrence.hop())
				throw new IllegalArgumentException("Occurrence Hop is not owned by the analysis");
			if(node.exclusions().stream().noneMatch(candidate -> candidate == exclusion))
				throw new IllegalArgumentException("Exclusion is not owned by the node");
		}
	}

	public record Result(PlacementAnalysis analysis, List<GraphExclusionReceipt> certificateReceipts,
		String analysisFingerprint) {
		public Result {
			Objects.requireNonNull(analysis, "analysis");
			certificateReceipts = List.copyOf(certificateReceipts);
			if(!analysis.analysisFingerprint().equals(analysisFingerprint))
				throw new IllegalArgumentException("Analysis fingerprint differs");
			for(GraphExclusionReceipt receipt : certificateReceipts)
				if(receipt.analysis() != analysis)
					throw new IllegalArgumentException("Receipt belongs to a different analysis");
			int receiptIndex = 0;
			for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
				for(NeutralPlacementGraph.Exclusion exclusion : node.exclusions()) {
					if(receiptIndex >= certificateReceipts.size())
						throw new IllegalArgumentException("Missing graph exclusion receipt");
					GraphExclusionReceipt receipt = certificateReceipts.get(receiptIndex++);
					if(receipt.node() != node || receipt.exclusion() != exclusion)
						throw new IllegalArgumentException("Graph exclusion receipt order or identity differs");
				}
			if(receiptIndex != certificateReceipts.size())
				throw new IllegalArgumentException("Unexpected graph exclusion receipt");
		}
		public PlacementAnalysis producer() { return analysis; }
	}

	public Result select(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		List<GraphExclusionReceipt> receipts = new ArrayList<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes()) {
			List<PlacementAnalysis.HopOccurrenceProjection> occurrences = analysis.occurrences().stream()
				.filter(candidate -> candidate.key().equals(node.key())).toList();
			if(occurrences.size() != 1)
				throw new IllegalStateException("Neutral graph node must have one exact occurrence: " + node.key());
			for(NeutralPlacementGraph.Exclusion exclusion : node.exclusions())
				receipts.add(new GraphExclusionReceipt(analysis, occurrences.get(0), node, exclusion));
		}
		return new Result(analysis, receipts, analysis.analysisFingerprint());
	}
}
