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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
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
		NeutralPlacementGraph base = analysis.graph();
		List<CompiledHopKey> markerKeys = markerKeys(base, demotionMarkers);
		Set<CompiledHopKey> descendants = new LinkedHashSet<>();
		for(CompiledHopKey marker : markerKeys) descendants.addAll(closure(base, Set.of(marker)));
		Set<ValueVersionKey> descendantValues = new LinkedHashSet<>();
		for(Node node : base.nodes())
			if(descendants.contains(node.key())) descendantValues.add(node.valueVersion());
		List<String> exclusions = policyExclusions(analysis, markerKeys);
		List<NeutralPlacementGraph.RelocationAction> legalRelocations = base.relocationActions().stream()
			.filter(action -> !descendantValues.contains(action.key().sourceValueVersion())).toList();
		NeutralPlacementGraph filtered = new NeutralPlacementGraph(
			filteredNodes(analysis, markerKeys, exclusions), base.constraints(), legalRelocations);
		List<String> candidates = filtered.normalizedCandidateUniverse();
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
		String assignmentHash = demotionMarkers.isEmpty() ? commonAssignmentHash(assignment)
			: assignmentHash(assignment);
		String policyFingerprint = sha256("NO_REFED_POLICY_V1|" + analysis.analysisFingerprint() + '|'
			+ markerSignature(demotionMarkers) + '|' + candidates + '|' + exclusions);
		String incumbent = selection.score().normalizedSignature();
		Score score = new Score(selection.score().emittedFedCount(), selection.score().foutCount(),
			relocations.size(), incumbent);
		List<Bound> boundComponents = componentBounds(filtered);
		Certificate certificate = new Certificate(analysis.analysisFingerprint(), policyFingerprint,
			assignmentHash, candidates.size(), candidates.size(), 0, List.of("complete"), incumbent,
			incumbent, "EXHAUSTED", false, sha256(filtered.normalizedSignature()), score, score,
			boundComponents, filtered.nodes().size(), filtered.constraints().size(), boundComponents.size(),
			"complete-cartesian-enumeration-with-partial-legality-pruning");
		Result partial = new Result(analysis, analysis.analysisFingerprint(), filtered, assignment, candidates, exclusions,
			relocations, obligations, anchors, List.of(), List.of(), List.of(), objective, ties, relationships,
			boundaries, clones, structural, facts, certificate, score, "");
		return partial.withNormalizedPlanFingerprint(PlacementEmissionTransaction.canonicalPlanHash(partial));
	}

	private static List<CompiledHopKey> markerKeys(NeutralPlacementGraph graph, Set<ValueVersionKey> markers) {
		List<CompiledHopKey> keys = new ArrayList<>();
		for(ValueVersionKey marker : markers.stream().sorted().toList()) {
			List<CompiledHopKey> matches = graph.nodes().stream().filter(n -> n.valueVersion().equals(marker))
				.map(Node::key).toList();
			if(matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous demotion marker");
			keys.add(matches.get(0));
		}
		if(keys.size() != markers.size()) throw new IllegalArgumentException("Unknown or ambiguous demotion marker");
		return List.copyOf(keys);
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

	private static List<String> policyExclusions(PlacementAnalysis analysis, List<CompiledHopKey> markerKeys) {
		List<String> exclusions = new ArrayList<>();
		Set<CompiledHopKey> typedMarkers = analysis.heuristicPolicyFacts().demotions().stream()
			.map(fact -> fact.producer()).collect(java.util.stream.Collectors.toSet());
		for(CompiledHopKey marker : markerKeys) {
			Set<CompiledHopKey> descendants = closure(analysis.graph(), Set.of(marker));
			DurableAnchorKey anchor = policyAnchor(analysis.graph(), Set.of(marker));
			ValueVersionKey markerValue = analysis.graph().node(marker).orElseThrow().valueVersion();
			for(Node node : analysis.graph().nodes()) {
				addPolicyExclusion(exclusions, node, markerValue,
					candidateProof(analysis, node, anchor, marker, descendants, null), false);
				if(typedMarkers.contains(marker))
					for(PlacementState state : node.legalAlternatives())
						if(state.output() == FederatedOutput.FOUT)
							addPolicyExclusion(exclusions, node, markerValue,
								candidateProof(analysis, node, anchor, marker, descendants, state), true);
			}
		}
		return exclusions.stream().distinct().sorted().toList();
	}

	private static void addPolicyExclusion(List<String> exclusions, Node node, ValueVersionKey markerValue,
		String proof, boolean graphNormalizedCandidate) {
		if(proof == null) return;
		String state = proof.substring(0, proof.indexOf('|'));
		String candidate = graphNormalizedCandidate ? normalizedCandidate(node.key().normalizedSignature(), state)
			: node.key().normalizedSignature() + '=' + state;
		exclusions.add("NO_REFED|" + candidate + "|proof=" + proof.substring(proof.indexOf('|') + 1)
			+ "|marker=" + markerValue.normalizedSignature());
	}

	private static String normalizedCandidate(String key, String state) {
		return key.length() + ":" + key + '|' + state.length() + ":" + state;
	}

	private static List<Node> filteredNodes(PlacementAnalysis analysis, List<CompiledHopKey> markerKeys,
		List<String> policyExclusions) {
		Set<String> removedCandidates = new LinkedHashSet<>();
		for(String exclusion : policyExclusions) {
			int proof = exclusion.indexOf("|proof=");
			if(!exclusion.startsWith("NO_REFED|") || proof < 0)
				throw new HeuristicPolicySafetyException();
			removedCandidates.add(exclusion.substring("NO_REFED|".length(), proof));
		}
		Set<CompiledHopKey> typedMarkers = new LinkedHashSet<>();
		for(var fact : analysis.heuristicPolicyFacts().demotions())
			if(markerKeys.contains(fact.producer())) typedMarkers.add(fact.producer());
		List<Node> nodes = new ArrayList<>(analysis.graph().nodes().size());
		for(Node node : analysis.graph().nodes()) {
			List<PlacementState> legal = node.legalAlternatives().stream().filter(state ->
				!removedCandidates.contains(node.key().normalizedSignature() + '=' + state.normalizedSignature())
					&& !removedCandidates.contains(normalizedCandidate(node.key().normalizedSignature(),
						state.normalizedSignature()))).toList();
			if(typedMarkers.contains(node.key()))
				legal = legal.stream().filter(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.LOUT && state.fType() != null
					&& state.shapeDependent()).toList();
			if(node.emittedWork() && legal.isEmpty()) throw new HeuristicPolicySafetyException();
			nodes.add(new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				legal, node.exclusions(), node.anchors()));
		}
		return List.copyOf(nodes);
	}

	private static String candidateProof(PlacementAnalysis analysis, Node node, DurableAnchorKey anchor,
		CompiledHopKey marker, Set<CompiledHopKey> descendants, PlacementState requestedState) {
		// Anchor extents are useful only after the neutral graph has positively ruled out unknown or
		// unsupported node metadata.  They never substitute for missing node-shape evidence.
		if(!descendants.contains(node.key()) || !node.emittedWork() || !node.anchors().isEmpty()) return null;
		if(analysis.hop(node.key()).isEmpty()) throw new HeuristicPolicySafetyException();
		if(!concreteAnchor(anchor) || selfOrVariableAnchor(anchor, node) || !supported(anchor.fType())) return null;
		long[] shape = compatibleShape(analysis.shapeFact(node.key()).orElse(null), anchor);
		if(shape == null) return null;
		boolean boundary = node.kind() == NodeKind.TRANSIENT_READ || node.kind() == NodeKind.TRANSIENT_WRITE
			|| "recompile".equals(node.key().recompileContext());
		PlacementState state = requestedState == null ? new PlacementState(boundary ? ExecType.FED : ExecType.CP,
			FederatedOutput.FOUT, anchor.fType(), anchor.fType() != FType.BROADCAST) : requestedState;
		if(state.output() != FederatedOutput.FOUT || state.fType() != anchor.fType()) return null;
		if(node.exclusions().stream().anyMatch(exclusion -> exclusion.state().equals(state)
			&& hasUnsupportedShapeMetadata(exclusion))) return null;
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
		return exclusion.reasonCode() == ReasonCode.UNSUPPORTED_OPERATION_SHAPE
			|| exclusion.reasonCode() == ReasonCode.UNKNOWN_METADATA;
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
	private static long[] compatibleShape(PlacementAnalysis.NodeShapeFact shape, DurableAnchorKey anchor) {
		if(shape == null || !shape.knownPositiveMatrix()) return null;
		long rows = shape.rows(), cols = shape.cols();
		return validGeometry(anchor, rows, cols) ? new long[] {rows, cols} : null;
	}
	private static boolean validGeometry(DurableAnchorKey anchor, long rows, long cols) {
		if(!concreteAnchor(anchor)) return false;
		List<long[]> spans = new ArrayList<>();
		List<long[]> broadcastRectangles = new ArrayList<>();
		for(var p : anchor.partitions()) {
			long r0=p.begin().get(0), c0=p.begin().get(1), r1=p.end().get(0), c1=p.end().get(1);
			if(r0<0 || c0<0 || r1<=r0 || c1<=c0 || r1>rows || c1>cols) return false;
			if(anchor.fType()==FType.ROW) { if(c0!=0 || c1!=cols) return false; spans.add(new long[]{r0,r1}); }
			else if(anchor.fType()==FType.COL) { if(r0!=0 || r1!=rows) return false; spans.add(new long[]{c0,c1}); }
			else if(anchor.fType()==FType.FULL) { if(r0!=0 || c0!=0 || r1!=rows || c1!=cols) return false; }
			else if(anchor.fType()==FType.BROADCAST) broadcastRectangles.add(new long[]{r0,c0,r1,c1});
			else return false;
		}
		if(anchor.fType()==FType.BROADCAST) return completeNonOverlappingCover(broadcastRectangles, rows, cols);
		if(anchor.fType()==FType.FULL) return true;
		spans.sort(java.util.Comparator.comparingLong(x->x[0])); long cursor=0;
		for(long[] span:spans) { if(span[0]!=cursor) return false; cursor=span[1]; }
		return cursor==(anchor.fType()==FType.ROW?rows:cols);
	}
	private static boolean completeNonOverlappingCover(List<long[]> rectangles, long rows, long cols) {
		try {
			long covered = 0;
			for(int i=0; i<rectangles.size(); i++) {
				long[] current = rectangles.get(i);
				covered = Math.addExact(covered, Math.multiplyExact(current[2]-current[0], current[3]-current[1]));
				for(int j=0; j<i; j++) {
					long[] prior = rectangles.get(j);
					if(Math.max(current[0], prior[0]) < Math.min(current[2], prior[2])
						&& Math.max(current[1], prior[1]) < Math.min(current[3], prior[3])) return false;
				}
			}
			return covered == Math.multiplyExact(rows, cols);
		}
		catch(ArithmeticException overflow) {
			return false;
		}
	}

	private static boolean isTransient(NeutralPlacementGraph graph, CompiledHopKey key) {
		NodeKind kind = graph.node(key).orElseThrow().kind();
		return kind == NodeKind.TRANSIENT_READ || kind == NodeKind.TRANSIENT_WRITE;
	}
	private static void validateProjection(PlacementAnalysis analysis, NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment) {
		Set<CompiledHopKey> decisionKeys = graph.decisionNodes().stream().map(Node::key)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if(!assignment.keySet().equals(decisionKeys)) throw new IllegalStateException("Incomplete Heuristic assignment");
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
		return String.join(",", markers.stream().map(ValueVersionKey::normalizedSignature).sorted().toList());
	}
	private static String assignmentHash(Map<CompiledHopKey, PlacementState> assignment) {
		Map<String,String> normalized = new TreeMap<>(); assignment.forEach((k,v)->normalized.put(k.normalizedSignature(),v.normalizedSignature()));
		return sha256(normalized.toString());
	}
	private static String commonAssignmentHash(Map<CompiledHopKey, PlacementState> assignment) {
		List<String> lines = assignment.entrySet().stream().map(entry -> entry.getKey().normalizedSignature()
			+ '=' + entry.getValue().normalizedSignature()).sorted().toList();
		return sha256(String.join("\n", lines));
	}
	private static List<Bound> componentBounds(NeutralPlacementGraph graph) {
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency = new TreeMap<>();
		for(Node node : graph.nodes()) adjacency.put(node.key(), new LinkedHashSet<>());
		for(var constraint : graph.constraints()) {
			adjacency.get(constraint.left()).add(constraint.right());
			adjacency.get(constraint.right()).add(constraint.left());
		}
		Set<CompiledHopKey> seen = new LinkedHashSet<>();
		List<Bound> bounds = new ArrayList<>();
		for(CompiledHopKey start : adjacency.keySet()) {
			if(!seen.add(start)) continue;
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
			List<CompiledHopKey> nodes = new ArrayList<>();
			pending.add(start);
			while(!pending.isEmpty()) {
				CompiledHopKey key = pending.removeFirst();
				nodes.add(key);
				for(CompiledHopKey neighbor : adjacency.get(key))
					if(seen.add(neighbor)) pending.addLast(neighbor);
			}
			nodes.sort(Comparator.naturalOrder());
			int upperFed = 0, upperFout = 0;
			for(CompiledHopKey key : nodes) {
				List<PlacementState> states = graph.node(key).orElseThrow().legalAlternatives();
				if(states.stream().anyMatch(state -> state.execType() == ExecType.FED)) upperFed++;
				if(states.stream().anyMatch(state -> state.output() == FederatedOutput.FOUT)) upperFout++;
			}
			String id = Integer.toHexString(nodes.stream().map(CompiledHopKey::normalizedSignature)
				.toList().hashCode());
			bounds.add(new Bound(id, nodes, upperFed, upperFout, 0, "independent-component-envelope"));
		}
		bounds.sort(Comparator.comparing(Bound::componentId));
		return List.copyOf(bounds);
	}
	private static String sha256(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch(Exception e) { throw new IllegalStateException("JVM must provide SHA-256", e); }
	}

	public record Score(int fedCount, int foutCount, int relocationCount, String normalizedSignature) {
		public Score { Objects.requireNonNull(normalizedSignature, "normalizedSignature"); }
	}
	public record Bound(String componentId, List<CompiledHopKey> nodeKeys, int upperFed, int upperFout,
		int lowerRelocations, String derivation) {
		public Bound {
			nodeKeys = List.copyOf(nodeKeys);
			Objects.requireNonNull(componentId, "componentId");
			Objects.requireNonNull(derivation, "derivation");
		}
	}
	public record Certificate(String baseGraphFingerprint, String policyViewFingerprint, String assignmentHash,
		long legalUniverseSize, long exploredCount, long prunedCount, List<String> bounds,
		String incumbentSignature, String finalUpperBoundSignature, String terminationReason, boolean fallbackUsed,
		String graphFingerprint, Score incumbentScore, Score finalUpperBound, List<Bound> boundComponents,
		int graphNodeCount, int graphConstraintCount, int graphComponentCount, String boundDerivation) {
		public Certificate {
			bounds=List.copyOf(bounds);
			boundComponents=List.copyOf(boundComponents);
		}
	}
	public record Result(PlacementAnalysis analysis, String analysisFingerprint,
		NeutralPlacementGraph selectorGraph, Map<CompiledHopKey,PlacementState> assignment,
		List<String> filteredCandidateUniverse,
		List<String> policyExclusions, List<RelocationActionKey> selectedRelocations,
		List<ObligationKey> selectedObligations, List<DurableAnchorKey> durableAnchors,
		List<String> registryRefed, List<String> registryFoutMaterialize, List<String> registryLocalMaterialize,
		List<String> objectiveComponents, List<String> orderedTieBreaks, List<String> transientRelationships,
		List<String> controlBoundaryFacts, List<String> cloneRecompileMultiplicities,
		List<String> structuralExclusions, Map<String,String> plannerFacts, Certificate certificate, Score score,
		String normalizedPlanFingerprint) implements NormalizedPlannerResult {
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
		Result withNormalizedPlanFingerprint(String value) { return new Result(analysis,analysisFingerprint,selectorGraph,assignment,
			filteredCandidateUniverse,policyExclusions,selectedRelocations,selectedObligations,durableAnchors,
			registryRefed,registryFoutMaterialize,registryLocalMaterialize,objectiveComponents,orderedTieBreaks,
			transientRelationships,controlBoundaryFacts,cloneRecompileMultiplicities,structuralExclusions,
			plannerFacts,certificate,score,value); }
		@Override public String plannerId() { return "FED_HEURISTIC"; }
		@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return assignment; }
		@Override public String objectiveCertificate() { return certificate.toString(); }
	}
}
