/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;

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
		PlacementShapeFacts shapeFacts = syntheticShapeFacts(graph, projections);
		return new PlacementAnalysis(graph, projections, null, shapeFacts);
	}

	public static long constructionCount() { return CONSTRUCTIONS.get(); }

	/** Returns an exact immutable occurrence prefix retaining source graph objects. */
	public static PlacementAnalysis prefix(PlacementAnalysis source, int occurrenceCount) {
		Objects.requireNonNull(source, "source");
		if(occurrenceCount <= 0 || occurrenceCount > source.occurrences().size())
			throw new IllegalArgumentException("occurrenceCount out of range: " + occurrenceCount);
		CONSTRUCTIONS.incrementAndGet();

		List<HopOccurrenceProjection> projections = List.copyOf(source.occurrences().subList(0, occurrenceCount));
		LinkedHashSet<PlacementIdentity.CompiledHopKey> retainedKeys = new LinkedHashSet<>();
		for(HopOccurrenceProjection projection : projections)
			if(!retainedKeys.add(projection.key()))
				throw new IllegalArgumentException("duplicate occurrence key in occurrenceCount prefix");
		if(retainedKeys.size() != occurrenceCount)
			throw new IllegalArgumentException("occurrenceCount prefix cardinality mismatch");

		List<NeutralPlacementGraph.Node> nodes = new ArrayList<>(occurrenceCount);
		LinkedHashSet<PlacementIdentity.ValueVersionKey> retainedValues = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : source.graph().nodes())
			if(retainedKeys.contains(node.key())) {
				nodes.add(node);
				retainedValues.add(node.valueVersion());
			}
		if(nodes.size() != occurrenceCount)
			throw new IllegalArgumentException("occurrenceCount prefix node cardinality mismatch");
		LinkedHashSet<PlacementIdentity.CompiledHopKey> nodeKeys = new LinkedHashSet<>();
		for(NeutralPlacementGraph.Node node : nodes)
			if(!nodeKeys.add(node.key()))
				throw new IllegalArgumentException("duplicate retained node key in occurrenceCount prefix");
		if(!nodeKeys.equals(retainedKeys))
			throw new IllegalArgumentException("occurrenceCount prefix projection/node key mismatch");

		List<NeutralPlacementGraph.Constraint> constraints = new ArrayList<>();
		for(NeutralPlacementGraph.Constraint constraint : source.graph().constraints())
			if(retainedKeys.contains(constraint.left()) && retainedKeys.contains(constraint.right()))
				constraints.add(constraint);

		List<NeutralPlacementGraph.RelocationAction> actions = new ArrayList<>();
		for(NeutralPlacementGraph.RelocationAction action : source.graph().relocationActions()) {
			boolean internal = retainedValues.contains(action.key().sourceValueVersion())
				&& action.key().compatibleConsumers().stream().allMatch(retainedKeys::contains)
				&& action.obligations().stream().allMatch(obligation -> {
					if(!obligation.relocationAction().equals(action.key()))
						throw new AssertionError("obligation relocation action drift");
					return retainedKeys.contains(obligation.consumer())
						&& retainedValues.contains(obligation.sourceValueVersion());
				});
			if(internal)
				actions.add(action);
		}

		NeutralPlacementGraph projected = new NeutralPlacementGraph(nodes, constraints, actions);
		PlacementShapeFacts shapeFacts = copiedShapeFacts(source, projections);
		PlacementAnalysis result = new PlacementAnalysis(projected, projections, null, shapeFacts);
		if(result.occurrences().size() != occurrenceCount || result.graph().nodes().size() != occurrenceCount)
			throw new AssertionError("occurrenceCount prefix cardinality postcondition failed");
		for(int i = 0; i < occurrenceCount; i++) {
			HopOccurrenceProjection sourceProjection = source.occurrences().get(i);
			HopOccurrenceProjection resultProjection = result.occurrences().get(i);
			if(resultProjection != sourceProjection || resultProjection.hop() != sourceProjection.hop())
				throw new AssertionError("occurrenceCount prefix projection identity postcondition failed");
		}
		for(NeutralPlacementGraph.Node resultNode : result.graph().nodes())
			if(source.graph().nodes().stream().noneMatch(sourceNode -> sourceNode == resultNode))
				throw new AssertionError("occurrenceCount prefix node identity postcondition failed");
		for(NeutralPlacementGraph.RelocationAction resultAction : result.graph().relocationActions())
			if(source.graph().relocationActions().stream().noneMatch(sourceAction -> sourceAction == resultAction))
				throw new AssertionError("occurrenceCount prefix action identity postcondition failed");
		return result;
	}

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
		PlacementShapeFacts shapeFacts = copiedShapeFacts(source, projections);
		return new PlacementAnalysis(source.graph(), projections, null, shapeFacts);
	}

	private static PlacementShapeFacts syntheticShapeFacts(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections) {
		Map<PlacementIdentity.CompiledHopKey, NodeShapeFact> facts = new LinkedHashMap<>();
		LinkedHashSet<PlacementIdentity.CompiledHopKey> expectedKeys = new LinkedHashSet<>();
		for(HopOccurrenceProjection projection : projections) {
			expectedKeys.add(projection.key());
			facts.put(projection.key(), new NodeShapeFact(DataType.SCALAR, -1, -1));
		}
		if(graph.nodes().size() != expectedKeys.size()
			|| graph.nodes().stream().anyMatch(node -> !expectedKeys.contains(node.key())))
			throw new IllegalArgumentException("Synthetic facts do not exactly cover selector graph keys");
		return new PlacementShapeFacts(facts, expectedKeys);
	}

	private static PlacementShapeFacts copiedShapeFacts(PlacementAnalysis source,
		List<HopOccurrenceProjection> projections) {
		Map<PlacementIdentity.CompiledHopKey, NodeShapeFact> facts = new LinkedHashMap<>();
		LinkedHashSet<PlacementIdentity.CompiledHopKey> expectedKeys = new LinkedHashSet<>();
		for(HopOccurrenceProjection projection : projections) {
			expectedKeys.add(projection.key());
			facts.put(projection.key(), source.shapeFact(projection.key()).orElseThrow(
				() -> new IllegalArgumentException("Source analysis lacks shape fact for " + projection.key())));
		}
		return new PlacementShapeFacts(facts, expectedKeys);
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
