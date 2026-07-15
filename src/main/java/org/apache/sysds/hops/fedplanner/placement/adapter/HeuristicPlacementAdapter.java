/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.ExactPlacementSelector;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementSelection;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Provenance-scoped, exact Heuristic policy over one immutable placement analysis. */
public final class HeuristicPlacementAdapter {
	private static final Set<ConstraintKind> PROPAGATING = Set.of(ConstraintKind.DOMINATES,
		ConstraintKind.SAME_ORIGIN, ConstraintKind.SAME_PLACEMENT, ConstraintKind.CONJUNCTIVE);

	public Result select(PlacementAnalysis analysis, Set<ValueVersionKey> demotionMarkers) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(demotionMarkers, "demotionMarkers");
		if(demotionMarkers.isEmpty())
			throw new IllegalArgumentException("Heuristic selection requires a provenance marker");
		NeutralPlacementGraph base = analysis.graph();
		Set<CompiledHopKey> markerKeys = markerKeys(base, demotionMarkers);
		Set<CompiledHopKey> descendants = closure(base, markerKeys);
		Set<ValueVersionKey> descendantValues = new LinkedHashSet<>();
		for(Node node : base.nodes())
			if(descendants.contains(node.key())) descendantValues.add(node.valueVersion());
		DurableAnchorKey policyAnchor = policyAnchor(base, markerKeys);
		List<String> candidates = candidateUniverse(base);
		List<String> exclusions = policyExclusions(analysis, descendants, markerKeys, policyAnchor);
		List<NeutralPlacementGraph.RelocationAction> legalRelocations = base.relocationActions().stream()
			.filter(action -> !descendantValues.contains(action.key().sourceValueVersion())).toList();
		NeutralPlacementGraph filtered = new NeutralPlacementGraph(base.nodes(), base.constraints(), legalRelocations);
		PlacementSelection selection = new ExactPlacementSelector().select(filtered);
		Map<CompiledHopKey, PlacementState> assignment = immutableAssignment(selection.assignment());
		validateProjection(analysis, filtered, assignment);
		List<RelocationActionKey> relocations = selection.selectedRelocations().stream().sorted().toList();
		List<ObligationKey> obligations = filtered.relocationActions().stream()
			.filter(action -> selection.selectedRelocations().contains(action.key()))
			.flatMap(action -> action.obligations().stream()).sorted().toList();
		List<DurableAnchorKey> anchors = base.nodes().stream().flatMap(node -> node.anchors().stream())
			.distinct().sorted().toList();
		List<String> objective = List.of("FED=" + selection.score().emittedFedCount(),
			"FOUT=" + selection.score().foutCount(), "RELOCATIONS=" + relocations.size());
		List<String> ties = List.of("MAX_FED", "MAX_FOUT", "MIN_RELOCATIONS", "NORMALIZED_ASSIGNMENT");
		List<String> relationships = base.constraints().stream().filter(c -> isTransient(base, c.left())
			|| isTransient(base, c.right())).map(NeutralPlacementGraph.Constraint::normalizedSignature).sorted().toList();
		List<String> boundaries = base.constraints().stream().filter(c -> c.left().controlRegion()
			.compareTo(c.right().controlRegion()) != 0).map(NeutralPlacementGraph.Constraint::normalizedSignature)
			.sorted().toList();
		List<String> clones = base.nodes().stream().filter(n -> n.kind() == NodeKind.CLONE
			|| "recompile".equals(n.key().recompileContext())).map(Node::normalizedIdentity).sorted().toList();
		List<String> structural = base.normalizedExclusions();
		Map<String, String> facts = Collections.unmodifiableMap(new TreeMap<>(Map.of(
			"policy", "NO_REFED_POLICY_V1", "markerCount", Integer.toString(markerKeys.size()),
			"descendantCount", Integer.toString(descendants.size()), "search", "EXHAUSTIVE",
			"shapeProof", "NEUTRAL_EXCLUSIONS_PLUS_DURABLE_ANCHOR_GEOMETRY")));
		String assignmentHash = assignmentHash(assignment);
		String policyFingerprint = sha256("NO_REFED_POLICY_V1|" + analysis.analysisFingerprint() + '|'
			+ markerSignature(demotionMarkers) + '|' + candidates + '|' + exclusions);
		String incumbent = selection.score().normalizedSignature();
		Certificate certificate = new Certificate(analysis.analysisFingerprint(), policyFingerprint,
			assignmentHash, candidates.size(), candidates.size(), 0, List.of("complete"), incumbent,
			incumbent, "EXHAUSTED", false);
		Result partial = new Result(analysis, analysis.analysisFingerprint(), assignment, candidates, exclusions,
			relocations, obligations, anchors, List.of(), List.of(), List.of(), objective, ties, relationships,
			boundaries, clones, structural, facts, certificate, "");
		return partial.withNormalizedPlanFingerprint(planFingerprint(partial));
	}

	private static Set<CompiledHopKey> markerKeys(NeutralPlacementGraph graph, Set<ValueVersionKey> markers) {
		Set<CompiledHopKey> keys = new LinkedHashSet<>();
		for(Node node : graph.nodes()) if(markers.contains(node.valueVersion())) keys.add(node.key());
		if(keys.size() != markers.size()) throw new IllegalArgumentException("Unknown or ambiguous demotion marker");
		return Set.copyOf(keys);
	}

	private static Set<CompiledHopKey> closure(NeutralPlacementGraph graph, Set<CompiledHopKey> starts) {
		Set<CompiledHopKey> seen = new LinkedHashSet<>();
		ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>(starts);
		while(!pending.isEmpty()) {
			CompiledHopKey key = pending.removeFirst();
			if(!seen.add(key)) continue;
			for(var constraint : graph.constraints())
				if(constraint.left().equals(key) && PROPAGATING.contains(constraint.kind()))
					pending.addLast(constraint.right());
		}
		return Set.copyOf(seen);
	}

	private static DurableAnchorKey policyAnchor(NeutralPlacementGraph graph, Set<CompiledHopKey> markers) {
		List<DurableAnchorKey> all = graph.nodes().stream().flatMap(n -> n.anchors().stream()).distinct().toList();
		if(all.size() == 1) return all.get(0);
		List<DurableAnchorKey> upstream = new ArrayList<>();
		for(Node node : graph.nodes()) if(!node.anchors().isEmpty()) {
			Set<CompiledHopKey> lineage = closure(graph, Set.of(node.key()));
			if(lineage.stream().anyMatch(markers::contains)) upstream.addAll(node.anchors());
		}
		return upstream.stream().distinct().reduce((a, b) -> {
			throw new IllegalArgumentException("Ambiguous provenance anchor");
		}).orElseThrow(() -> new IllegalArgumentException("Missing provenance anchor"));
	}

	private static List<String> policyExclusions(PlacementAnalysis analysis, Set<CompiledHopKey> descendants,
		Set<CompiledHopKey> markerKeys, DurableAnchorKey anchor) {
		if(markerKeys.size() != 1) throw new IllegalArgumentException("One marker is required per exact policy view");
		CompiledHopKey marker = markerKeys.iterator().next();
		ValueVersionKey markerValue = analysis.graph().node(marker).orElseThrow().valueVersion();
		List<String> exclusions = new ArrayList<>();
		for(Node node : analysis.graph().nodes()) {
			String proof = candidateProof(analysis, node, anchor, marker, descendants);
			if(proof != null) exclusions.add("NO_REFED|" + node.key().normalizedSignature() + '='
				+ proof.substring(0, proof.indexOf('|')) + "|proof=" + proof.substring(proof.indexOf('|') + 1)
				+ "|marker=" + markerValue.normalizedSignature());
		}
		Collections.sort(exclusions);
		if(exclusions.isEmpty()) throw new IllegalStateException("Heuristic policy exclusion is vacuous");
		return List.copyOf(exclusions);
	}
	private static List<String> candidateUniverse(NeutralPlacementGraph graph) {
		List<String> candidates = new ArrayList<>();
		for(Node node : graph.nodes())
			for(PlacementState state : node.legalAlternatives())
				candidates.add(node.key().normalizedSignature() + '=' + state.normalizedSignature());
		Collections.sort(candidates);
		return List.copyOf(candidates);
	}

	private static String candidateProof(PlacementAnalysis analysis, Node node, DurableAnchorKey anchor,
		CompiledHopKey marker, Set<CompiledHopKey> descendants) {
		// Anchor extents are useful only after the neutral graph has positively ruled out unknown or
		// unsupported node metadata.  They never substitute for missing node-shape evidence.
		if(!descendants.contains(node.key()) || !node.emittedWork() || !node.anchors().isEmpty()
			|| node.exclusions().stream().anyMatch(HeuristicPlacementAdapter::hasUnsupportedShapeMetadata)
			|| !concreteAnchor(anchor) || selfOrVariableAnchor(anchor, node) || !supported(anchor.fType())) return null;
		Hop hop = analysis.hop(node.key()).orElse(null);
		long[] shape = compatibleShape(hop, anchor);
		if(shape == null) return null;
		boolean boundary = node.kind() == NodeKind.TRANSIENT_READ || node.kind() == NodeKind.TRANSIENT_WRITE
			|| "recompile".equals(node.key().recompileContext());
		PlacementState state = new PlacementState(boundary ? ExecType.FED : ExecType.CP,
			FederatedOutput.FOUT, anchor.fType(), anchor.fType() != FType.BROADCAST);
		RelocationActionKey relocation = new RelocationActionKey(node.valueVersion(), state, anchor,
			node.key().controlRegion().normalizedSignature(), List.of(node.key()));
		ObligationKey obligation = new ObligationKey(node.key(), 0, node.valueVersion(), state, relocation,
			node.key().recompileContext());
		String shapeBasis = anchor.fType() == FType.BROADCAST ? "SHAPE_INDEPENDENT_BROADCAST"
			: "KNOWN_COMPATIBLE_DIMENSIONS";
		String signature = node.key().normalizedSignature() + ';' + anchor.normalizedSignature() + ';'
			+ anchor.fType() + ';' + shape[0] + 'x' + shape[1] + ';' + shapeBasis
			+ ";LOCAL_UPLOAD_EXISTING_DURABLE_ANCHOR;" + relocation.normalizedSignature() + ';'
			+ obligation.normalizedSignature() + ";EXISTING_FEDERATION_MAP_COMPATIBLE;"
			+ marker.normalizedSignature() + ';' + node.valueVersion().normalizedSignature();
		return state.normalizedSignature() + '|' + signature;
	}
	private static boolean hasUnsupportedShapeMetadata(NeutralPlacementGraph.Exclusion exclusion) {
		if(exclusion.reasonCode() == ReasonCode.UNSUPPORTED_OPERATION_SHAPE) return true;
		return exclusion.reasonCode() == ReasonCode.UNKNOWN_METADATA
			&& (exclusion.detail().contains("rows=UNKNOWN") || exclusion.detail().contains("cols=UNKNOWN"));
	}

	private static boolean supported(FType type) {
		return Set.of(FType.ROW, FType.COL, FType.FULL, FType.BROADCAST).contains(type);
	}
	private static boolean selfOrVariableAnchor(DurableAnchorKey anchor, Node node) {
		return anchor.placementId().startsWith("var:") || anchor.placementId().startsWith("self:")
			|| anchor.placementId().equals(node.valueVersion().lexicalVariable());
	}
	private static boolean concreteAnchor(DurableAnchorKey anchor) {
		if(anchor == null || anchor.placementId() == null || anchor.placementId().isBlank()
			|| anchor.partitions() == null || anchor.partitions().isEmpty()) return false;
		Set<String> partitions = new LinkedHashSet<>();
		for(var partition : anchor.partitions()) {
			if(partition.workerId() == null || partition.workerId().isBlank() || partition.begin() == null
				|| partition.end() == null || partition.begin().size() != 2 || partition.end().size() != 2) return false;
			if(!partitions.add(partition.workerId() + "|" + partition.begin() + "|" + partition.end())) return false;
		}
		return true;
	}
	private static long[] compatibleShape(Hop hop, DurableAnchorKey anchor) {
		if(anchor.fType() == FType.BROADCAST) return new long[] {-1, -1};
		if(hop == null || hop.getDataType() != DataType.MATRIX) return null;
		long rows = anchor.partitions().stream().mapToLong(p -> p.end().get(0)).max().orElse(-1);
		long cols = anchor.partitions().stream().mapToLong(p -> p.end().get(1)).max().orElse(-1);
		return validGeometry(anchor, rows, cols) ? new long[] {rows, cols} : null;
	}
	private static boolean validGeometry(DurableAnchorKey anchor, long rows, long cols) {
		if(!concreteAnchor(anchor)) return false;
		List<long[]> spans = new ArrayList<>();
		for(var p : anchor.partitions()) {
			long r0=p.begin().get(0), c0=p.begin().get(1), r1=p.end().get(0), c1=p.end().get(1);
			if(r0<0 || c0<0 || r1<=r0 || c1<=c0 || r1>rows || c1>cols) return false;
			if(anchor.fType()==FType.ROW) { if(c0!=0 || c1!=cols) return false; spans.add(new long[]{r0,r1}); }
			else if(anchor.fType()==FType.COL) { if(r0!=0 || r1!=rows) return false; spans.add(new long[]{c0,c1}); }
			else if(anchor.fType()==FType.FULL) { if(r0!=0 || c0!=0 || r1!=rows || c1!=cols) return false; }
			else return false;
		}
		if(anchor.fType()==FType.FULL) return true;
		spans.sort(java.util.Comparator.comparingLong(x->x[0])); long cursor=0;
		for(long[] span:spans) { if(span[0]!=cursor) return false; cursor=span[1]; }
		return cursor==(anchor.fType()==FType.ROW?rows:cols);
	}

	private static boolean isTransient(NeutralPlacementGraph graph, CompiledHopKey key) {
		NodeKind kind = graph.node(key).orElseThrow().kind();
		return kind == NodeKind.TRANSIENT_READ || kind == NodeKind.TRANSIENT_WRITE;
	}
	private static void validateProjection(PlacementAnalysis analysis, NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment) {
		if(assignment.size() != graph.nodes().size()) throw new IllegalStateException("Incomplete Heuristic assignment");
		for(var entry : assignment.entrySet()) {
			if(!graph.node(entry.getKey()).orElseThrow().legalAlternatives().contains(entry.getValue()))
				throw new IllegalStateException("State outside filtered universe");
			Hop hop = analysis.hop(entry.getKey()).orElseThrow();
			if(analysis.occurrences().stream().noneMatch(o -> o.key().equals(entry.getKey()) && o.hop() == hop))
				throw new IllegalStateException("Concrete Hop alias lost");
		}
	}
	private static Map<CompiledHopKey, PlacementState> immutableAssignment(Map<CompiledHopKey, PlacementState> source) {
		Map<CompiledHopKey, PlacementState> result = new TreeMap<>(); result.putAll(source);
		return Collections.unmodifiableMap(result);
	}
	private static String markerSignature(Set<ValueVersionKey> markers) {
		return markers.stream().map(ValueVersionKey::normalizedSignature).sorted().findFirst().orElseThrow();
	}
	private static String assignmentHash(Map<CompiledHopKey, PlacementState> assignment) {
		Map<String,String> normalized = new TreeMap<>(); assignment.forEach((k,v)->normalized.put(k.normalizedSignature(),v.normalizedSignature()));
		return sha256(normalized.toString());
	}
	private static String planFingerprint(Result r) {
		Map<String,String> assignment = new TreeMap<>(); r.assignment().forEach((k,v)->assignment.put(k.normalizedSignature(),v.normalizedSignature()));
		return sha256(r.analysisFingerprint()+'|'+assignment+'|'+signatures(r.filteredCandidateUniverse())+'|'
			+signatures(r.policyExclusions())+'|'+signatures(r.selectedRelocations())+'|'+signatures(r.selectedObligations())+'|'
			+signatures(r.durableAnchors())+'|'+r.registryRefed()+'|'+r.registryFoutMaterialize()+'|'
			+r.registryLocalMaterialize()+'|'+r.objectiveComponents()+'|'+r.orderedTieBreaks()+'|'
			+signatures(r.transientRelationships())+'|'+signatures(r.controlBoundaryFacts())+'|'
			+signatures(r.cloneRecompileMultiplicities())+'|'+signatures(r.structuralExclusions())+'|'
			+new TreeMap<>(r.plannerFacts()));
	}
	private static String signatures(Iterable<?> values) {
		List<String> result=new ArrayList<>(); for(Object value:values) result.add(normalized(value)); Collections.sort(result); return result.toString();
	}
	private static String normalized(Object value) {
		if(value instanceof String) return (String)value;
		try { return String.valueOf(value.getClass().getMethod("normalizedSignature").invoke(value)); }
		catch(Exception e) { throw new IllegalStateException("Missing normalized signature", e); }
	}
	private static String sha256(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch(Exception e) { throw new IllegalStateException("JVM must provide SHA-256", e); }
	}

	public record Certificate(String baseGraphFingerprint, String policyViewFingerprint, String assignmentHash,
		long legalUniverseSize, long exploredCount, long prunedCount, List<String> bounds,
		String incumbentSignature, String finalUpperBoundSignature, String terminationReason, boolean fallbackUsed) {
		public Certificate { bounds=List.copyOf(bounds); }
	}
	public record Result(PlacementAnalysis analysis, String analysisFingerprint,
		Map<CompiledHopKey,PlacementState> assignment, List<String> filteredCandidateUniverse,
		List<String> policyExclusions, List<RelocationActionKey> selectedRelocations,
		List<ObligationKey> selectedObligations, List<DurableAnchorKey> durableAnchors,
		List<String> registryRefed, List<String> registryFoutMaterialize, List<String> registryLocalMaterialize,
		List<String> objectiveComponents, List<String> orderedTieBreaks, List<String> transientRelationships,
		List<String> controlBoundaryFacts, List<String> cloneRecompileMultiplicities,
		List<String> structuralExclusions, Map<String,String> plannerFacts, Certificate certificate,
		String normalizedPlanFingerprint) {
		public Result {
			assignment=immutableAssignment(assignment); filteredCandidateUniverse=List.copyOf(filteredCandidateUniverse);
			policyExclusions=List.copyOf(policyExclusions); selectedRelocations=List.copyOf(selectedRelocations);
			selectedObligations=List.copyOf(selectedObligations); durableAnchors=List.copyOf(durableAnchors);
			registryRefed=List.copyOf(registryRefed); registryFoutMaterialize=List.copyOf(registryFoutMaterialize);
			registryLocalMaterialize=List.copyOf(registryLocalMaterialize); objectiveComponents=List.copyOf(objectiveComponents);
			orderedTieBreaks=List.copyOf(orderedTieBreaks); transientRelationships=List.copyOf(transientRelationships);
			controlBoundaryFacts=List.copyOf(controlBoundaryFacts); cloneRecompileMultiplicities=List.copyOf(cloneRecompileMultiplicities);
			structuralExclusions=List.copyOf(structuralExclusions); plannerFacts=Collections.unmodifiableMap(new TreeMap<>(plannerFacts));
		}
		Result withNormalizedPlanFingerprint(String value) { return new Result(analysis,analysisFingerprint,assignment,
			filteredCandidateUniverse,policyExclusions,selectedRelocations,selectedObligations,durableAnchors,
			registryRefed,registryFoutMaterialize,registryLocalMaterialize,objectiveComponents,orderedTieBreaks,
			transientRelationships,controlBoundaryFacts,cloneRecompileMultiplicities,structuralExclusions,
			plannerFacts,certificate,value); }
	}
}
