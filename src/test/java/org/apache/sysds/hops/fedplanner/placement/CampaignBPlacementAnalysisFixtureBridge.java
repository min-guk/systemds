/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;

/** Test-owned analysis construction seam with a counter used to detect any visible rebuild. */
public final class CampaignBPlacementAnalysisFixtureBridge {
	public enum ProjectionOrder { NORMAL, REVERSED }
	private static final AtomicLong CONSTRUCTIONS = new AtomicLong();

	public static PlacementAnalysis build(org.apache.sysds.parser.DMLProgram program) {
		CONSTRUCTIONS.incrementAndGet();
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	public static PlacementAnalysis fromSelectorGraph(NeutralPlacementGraph graph) {
		return fromSelectorGraph(graph, ProjectionOrder.NORMAL);
	}

	public static PlacementAnalysis fromSelectorGraph(NeutralPlacementGraph graph, ProjectionOrder order) {
		CONSTRUCTIONS.incrementAndGet();
		List<HopOccurrenceProjection> projections = new ArrayList<>();
		int ordinal = 0;
		for(NeutralPlacementGraph.Node node : graph.nodes()) {
			LiteralOp hop = new LiteralOp(ordinal + 1L);
			projections.add(new HopOccurrenceProjection(node.key(), hop, ordinal++, node.key().normalizedSignature()));
		}
		if(order == ProjectionOrder.REVERSED) Collections.reverse(projections);
		return new PlacementAnalysis(graph, projections);
	}

	public static long constructionCount() { return CONSTRUCTIONS.get(); }

	/** Reuses one actual compiled B-17 Hop across its distinct emitted context keys. */
	public static PlacementAnalysis sameHopContextTrap(PlacementAnalysis source) {
		CONSTRUCTIONS.incrementAndGet();
		Map<String,org.apache.sysds.hops.Hop> representative = new LinkedHashMap<>();
		List<HopOccurrenceProjection> projections = new ArrayList<>(); boolean duplicate = false;
		for(HopOccurrenceProjection p : source.occurrences()) {
			String group = p.key().emittedHopInstance();
			org.apache.sysds.hops.Hop hop = representative.putIfAbsent(group, p.hop());
			if(hop == null) hop = p.hop(); else duplicate = true;
			projections.add(new HopOccurrenceProjection(p.key(), hop, p.normalizedOrdinal(), p.normalizedSignature()));
		}
		if(!duplicate) {
			representative.clear(); projections.clear();
			for(HopOccurrenceProjection p : source.occurrences()) {
				String group = p.key().canonicalSourceOrigin();
				org.apache.sysds.hops.Hop hop = representative.putIfAbsent(group, p.hop());
				if(hop == null) hop = p.hop(); else duplicate = true;
				projections.add(new HopOccurrenceProjection(p.key(), hop, p.normalizedOrdinal(), p.normalizedSignature()));
			}
		}
		if(!duplicate) throw new AssertionError("R4_SAME_HOP_CONTEXT|no-repeated-B17-origin");
		return new PlacementAnalysis(source.graph(), projections);
	}

	public static List<String> fullSnapshot(PlacementAnalysis analysis) {
		List<String> out = new ArrayList<>();
		for(HopOccurrenceProjection p : analysis.occurrences()) out.add("P|" + p.key().normalizedSignature()
			+ '|' + p.normalizedOrdinal() + '|' + p.normalizedSignature() + '|' + System.identityHashCode(p.hop()));
		for(NeutralPlacementGraph.Node n : analysis.graph().nodes()) out.add("N|" + n.key().normalizedSignature()
			+ '|' + n.kind() + '|' + n.valueVersion().normalizedSignature() + '|' + n.legalAlternatives()
			+ '|' + n.exclusions() + '|' + n.anchors());
		for(NeutralPlacementGraph.Constraint c : analysis.graph().constraints()) out.add("C|" + c.normalizedSignature());
		for(NeutralPlacementGraph.RelocationAction a : analysis.graph().relocationActions()) {
			out.add("R|" + a.key().normalizedSignature());
			for(var o : a.obligations()) out.add("O|" + o.normalizedSignature());
		}
		return List.copyOf(out);
	}

	private CampaignBPlacementAnalysisFixtureBridge() { }
}
