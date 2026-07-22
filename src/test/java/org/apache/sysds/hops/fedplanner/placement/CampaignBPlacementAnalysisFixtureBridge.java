/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.hops.fedplanner.placement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

/** Test-owned analysis construction seam with a counter used to detect any visible rebuild. */
public final class CampaignBPlacementAnalysisFixtureBridge {
	public enum ProjectionOrder { NORMAL, REVERSED }
	private static final AtomicLong CONSTRUCTIONS = new AtomicLong();

	public static PlacementAnalysis.CompiledInputEdgeFact compiledInputEdge(CompiledHopKey producer, CompiledHopKey consumer, int position) { return new PlacementAnalysis.CompiledInputEdgeFact(producer, consumer, position); }

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
			projections.add(new HopOccurrenceProjection(node.key(), hop, -1L, ordinal++, node.key().normalizedSignature()));
		}
		if(order == ProjectionOrder.REVERSED) Collections.reverse(projections);
		PlacementShapeFacts shapeFacts = syntheticShapeFacts(graph, projections);
		return new PlacementAnalysis(graph, projections, null, shapeFacts,
			testFingerprint(graph, projections), new HeuristicPolicyFacts(List.of()));
	}

	public static long constructionCount() { return CONSTRUCTIONS.get(); }

	/** Reorders only the immutable occurrence projection while retaining the exact owner, graph, and policy facts. */
	public static PlacementAnalysis withProjectionOrder(PlacementAnalysis source,
		org.apache.sysds.parser.DMLProgram programOwner, ProjectionOrder order) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(programOwner, "programOwner");
		Objects.requireNonNull(order, "order");
		CONSTRUCTIONS.incrementAndGet();
		List<HopOccurrenceProjection> projections = new ArrayList<>(source.occurrences());
		if(order == ProjectionOrder.REVERSED) Collections.reverse(projections);
		return new PlacementAnalysis(source.graph(), projections, programOwner,
			copiedShapeFacts(source, projections), source.analysisFingerprint(), source.heuristicPolicyFacts());
	}

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
		PlacementAnalysis result = new PlacementAnalysis(projected, projections, null, shapeFacts,
			testFingerprint(projected, projections), new HeuristicPolicyFacts(List.of()));
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
			projections.add(new HopOccurrenceProjection(p.key(), hop, p.scopeId(), p.normalizedOrdinal(),
				p.normalizedSignature()));
		}
		if(!duplicate) {
			representative.clear(); projections.clear();
			for(HopOccurrenceProjection p : source.occurrences()) {
				String group = p.key().canonicalSourceOrigin();
				org.apache.sysds.hops.Hop hop = representative.putIfAbsent(group, p.hop());
				if(hop == null) hop = p.hop(); else duplicate = true;
				projections.add(new HopOccurrenceProjection(p.key(), hop, p.scopeId(), p.normalizedOrdinal(),
					p.normalizedSignature()));
			}
		}
		if(!duplicate) throw new AssertionError("R4_SAME_HOP_CONTEXT|no-repeated-B17-origin");
		PlacementShapeFacts shapeFacts = copiedShapeFacts(source, projections);
		return new PlacementAnalysis(source.graph(), projections, null, shapeFacts,
			testFingerprint(source.graph(), projections), new HeuristicPolicyFacts(List.of()));
	}

	public static PlacementAnalysis replaceGraph(PlacementAnalysis source, NeutralPlacementGraph graph) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(graph, "graph");
		CONSTRUCTIONS.incrementAndGet();
		return analysis(graph, source.occurrences(), copiedShapeFacts(source, source.occurrences()));
	}

	public static PlacementAnalysis replaceHop(PlacementAnalysis source, CompiledHopKey key, Hop hop) {
		return replaceHop(source, key, hop, null);
	}

	public static PlacementAnalysis replaceHopAndShapeFact(PlacementAnalysis source, CompiledHopKey key, Hop hop,
		NodeShapeFact shapeFact) {
		Objects.requireNonNull(shapeFact, "shapeFact");
		return replaceHop(source, key, hop, shapeFact);
	}

	private static PlacementAnalysis replaceHop(PlacementAnalysis source, CompiledHopKey key, Hop hop,
		NodeShapeFact replacementShapeFact) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(hop, "hop");
		CONSTRUCTIONS.incrementAndGet();
		boolean[] found = {false};
		List<HopOccurrenceProjection> projections = source.occurrences().stream().map(occurrence -> {
			if(!occurrence.key().equals(key))
				return occurrence;
			found[0] = true;
			return new HopOccurrenceProjection(occurrence.key(), hop, occurrence.scopeId(),
				occurrence.normalizedOrdinal(), occurrence.normalizedSignature());
		}).toList();
		if(!found[0])
			throw new AssertionError("R4_REPLACE_HOP_KEY_MISSING|key=" + key.normalizedSignature());
		return analysis(source.graph(), projections,
			copiedShapeFacts(source, projections, key, replacementShapeFact));
	}

	public static PlacementAnalysis missingHopProjectionTrap(PlacementAnalysis source, CompiledHopKey key) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(key, "key");
		CONSTRUCTIONS.incrementAndGet();
		CompiledHopKey dummyKey = new CompiledHopKey(key.programFingerprint(), key.functionNamespace(),
			key.callSitePath(), key.recompileContext(), key.controlRegion(),
			key.emittedHopInstance() + "|missing-hop-trap", key.canonicalSourceOrigin() + "|missing-hop-trap");
		List<HopOccurrenceProjection> projections = new ArrayList<>(source.occurrences().size());
		Map<CompiledHopKey, NodeShapeFact> facts = new LinkedHashMap<>();
		LinkedHashSet<CompiledHopKey> expectedKeys = new LinkedHashSet<>();
		boolean replaced = false;
		for(HopOccurrenceProjection occurrence : source.occurrences()) {
			CompiledHopKey sourceKey = occurrence.key();
			CompiledHopKey occurrenceKey = sourceKey;
			NodeShapeFact shapeFact = source.shapeFact(sourceKey).orElseThrow(
				() -> new IllegalArgumentException("Source analysis lacks shape fact for " + sourceKey));
			if(occurrenceKey.equals(key)) {
				if(replaced)
					throw new AssertionError("R4_MISSING_HOP_KEY_DUPLICATE|key=" + key.normalizedSignature());
				replaced = true;
				occurrenceKey = dummyKey;
				projections.add(new HopOccurrenceProjection(dummyKey, occurrence.hop(), occurrence.scopeId(),
					occurrence.normalizedOrdinal(), occurrence.normalizedSignature()));
			}
			else
				projections.add(occurrence);
			if(!expectedKeys.add(occurrenceKey))
				throw new AssertionError("R4_MISSING_HOP_DUMMY_KEY_COLLISION|key=" + occurrenceKey.normalizedSignature());
			facts.put(occurrenceKey, shapeFact);
		}
		if(!replaced)
			throw new AssertionError("R4_MISSING_HOP_KEY_ABSENT_BEFORE_TRAP|key=" + key.normalizedSignature());
		PlacementShapeFacts shapeFacts = new PlacementShapeFacts(facts, expectedKeys);
		PlacementAnalysis trap = analysis(source.graph(), projections, shapeFacts);
		if(trap.hop(key).isPresent())
			throw new AssertionError("R4_MISSING_HOP_TRAP_NOT_ARMED|key=" + key.normalizedSignature());
		if(trap.graph() != source.graph())
			throw new AssertionError("R4_MISSING_HOP_TRAP_REPLACED_GRAPH|key=" + key.normalizedSignature());
		if(trap.hop(dummyKey).isEmpty())
			throw new AssertionError("R4_MISSING_HOP_TRAP_LOST_DUMMY|key=" + dummyKey.normalizedSignature());
		if(source.hop(key).isEmpty())
			throw new AssertionError("R4_MISSING_HOP_TRAP_MUTATED_SOURCE|key=" + key.normalizedSignature());
		return trap;
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
		return copiedShapeFacts(source, projections, null, null);
	}

	private static PlacementShapeFacts copiedShapeFacts(PlacementAnalysis source,
		List<HopOccurrenceProjection> projections, CompiledHopKey replacementKey, NodeShapeFact replacementFact) {
		Map<PlacementIdentity.CompiledHopKey, NodeShapeFact> facts = new LinkedHashMap<>();
		LinkedHashSet<PlacementIdentity.CompiledHopKey> expectedKeys = new LinkedHashSet<>();
		for(HopOccurrenceProjection projection : projections) {
			expectedKeys.add(projection.key());
			NodeShapeFact fact = replacementKey != null && replacementFact != null
				&& replacementKey.equals(projection.key()) ? replacementFact :
				source.shapeFact(projection.key()).orElseThrow(
					() -> new IllegalArgumentException("Source analysis lacks shape fact for " + projection.key()));
			facts.put(projection.key(), fact);
		}
		return new PlacementShapeFacts(facts, expectedKeys);
	}

	private static PlacementAnalysis analysis(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections, PlacementShapeFacts shapeFacts) {
		return new PlacementAnalysis(graph, projections, null, shapeFacts,
			testFingerprint(graph, projections), new HeuristicPolicyFacts(List.of()));
	}

	private static String testFingerprint(NeutralPlacementGraph graph,
		List<HopOccurrenceProjection> projections) {
		List<String> signatures = projections.stream()
			.map(projection -> projection.scopeId() + "|" + projection.normalizedSignature()).sorted().toList();
		String canonical = graph.normalizedSignature() + '\n' + String.join("\n", signatures);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8));
			StringBuilder fingerprint = new StringBuilder(digest.length * 2);
			for(byte value : digest)
				fingerprint.append(String.format("%02x", value));
			return fingerprint.toString();
		}
		catch(NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	public static List<String> fullSnapshot(PlacementAnalysis analysis) {
		List<String> out = new ArrayList<>();
		for(HopOccurrenceProjection p : analysis.occurrences()) out.add("P|" + p.key().normalizedSignature()
			+ '|' + p.scopeId() + '|' + p.normalizedOrdinal() + '|' + p.normalizedSignature() + '|'
			+ System.identityHashCode(p.hop()));
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
