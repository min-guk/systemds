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
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
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
	public Result select(PlacementAnalysis analysis, Set<ValueVersionKey> demotionMarkers) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(demotionMarkers, "demotionMarkers");
		NeutralPlacementGraph base = analysis.graph();
		List<CompiledHopKey> markerKeys = markerKeys(base, demotionMarkers);
		PolicyView policy = policyView(analysis, markerKeys);
		List<String> exclusions = policy.exclusions();
		NeutralPlacementGraph filtered = new NeutralPlacementGraph(
			filteredNodes(analysis, policy), policy.constraints(), base.relocationActions());
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
			"policy", "PATHWISE_REENTRY_POLICY_V2", "markerCount", Integer.toString(policy.markers().size()),
			"localPrefixCount", Integer.toString(policy.localPrefix().size()),
			"frontierEdgeCount", Integer.toString(policy.frontiers().size()), "search", "EXHAUSTIVE",
			"shapeProof", "COMMON_ANALYSIS_EXACT_EDGE_AND_RELOCATION_FACTS")));
		String assignmentHash = demotionMarkers.isEmpty() ? commonAssignmentHash(assignment)
			: assignmentHash(assignment);
		String policyFingerprint = sha256("PATHWISE_REENTRY_POLICY_V2|" + analysis.analysisFingerprint() + '|'
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

	private record FrontierEdge(ValueVersionKey sourceValue, CompiledHopKey consumer, int inputPosition,
		RelocationActionKey relocation) implements Comparable<FrontierEdge> {
		@Override public int compareTo(FrontierEdge that) {
			int consumerOrder = consumer.compareTo(that.consumer);
			if(consumerOrder != 0) return consumerOrder;
			int positionOrder = Integer.compare(inputPosition, that.inputPosition);
			return positionOrder != 0 ? positionOrder : relocation.compareTo(that.relocation);
		}
	}

	private record PolicyView(Set<CompiledHopKey> markers, Set<CompiledHopKey> localPrefix,
		Set<FrontierEdge> frontiers, List<Constraint> constraints, List<String> exclusions) { }

	private static PolicyView policyView(PlacementAnalysis analysis, List<CompiledHopKey> requestedMarkers) {
		Set<CompiledHopKey> typedMarkers = new LinkedHashSet<>();
		for(var fact : analysis.heuristicPolicyFacts().demotions())
			if(requestedMarkers.contains(fact.producer())) typedMarkers.add(fact.producer());
		Set<CompiledHopKey> local = new LinkedHashSet<>();
		Set<FrontierEdge> frontiers = new java.util.TreeSet<>();
		List<Constraint> constraints = new ArrayList<>(analysis.graph().constraints());
		for(var path : analysis.heuristicPolicyFacts().paths()) {
			if(!typedMarkers.contains(path.demotion().producer()))
				continue;
			local.addAll(path.localPrefix());
			for(var fact : path.reentries()) {
				frontiers.add(new FrontierEdge(fact.sourceValueVersion(), fact.consumer(), fact.inputPosition(),
					fact.relocationAction()));
				constraints.add(new Constraint(ConstraintKind.CONJUNCTIVE, fact.siblingProducer(), fact.consumer(),
					fact.siblingInputPosition(), "pathwise-reentry-sibling"));
			}
		}
		List<String> exclusions = new ArrayList<>();
		for(CompiledHopKey key : local) {
			Node node = analysis.graph().node(key).orElseThrow();
			for(PlacementState state : node.legalAlternatives())
				if(state.output() == FederatedOutput.FOUT)
					exclusions.add("PATH_LOCAL|" + normalizedCandidate(key.normalizedSignature(),
						state.normalizedSignature()) + "|value=" + node.valueVersion().normalizedSignature());
		}
		for(FrontierEdge frontier : frontiers)
			exclusions.add("REENTRY_FRONTIER|consumer=" + frontier.consumer().normalizedSignature()
				+ "|input=" + frontier.inputPosition() + "|value=" + frontier.sourceValue().normalizedSignature()
				+ "|relocation=" + frontier.relocation().normalizedSignature());
		return new PolicyView(Set.copyOf(typedMarkers), Set.copyOf(local), Set.copyOf(frontiers),
			constraints.stream().distinct().sorted().toList(),
			exclusions.stream().sorted().toList());
	}

	private static String normalizedCandidate(String key, String state) {
		return key.length() + ":" + key + '|' + state.length() + ":" + state;
	}

	private static List<Node> filteredNodes(PlacementAnalysis analysis, PolicyView policy) {
		List<Node> nodes = new ArrayList<>(analysis.graph().nodes().size());
		for(Node node : analysis.graph().nodes()) {
			List<PlacementState> legal = node.legalAlternatives();
			if(policy.markers().contains(node.key()))
				legal = legal.stream().filter(state -> state.execType() == ExecType.FED
					&& state.output() == FederatedOutput.LOUT && state.fType() != null
					&& state.shapeDependent()).toList();
			else if(policy.localPrefix().contains(node.key()))
				legal = legal.stream().filter(state -> state.output() == FederatedOutput.LOUT).toList();
			if(node.emittedWork() && legal.isEmpty()) throw new HeuristicPolicySafetyException();
			nodes.add(new Node(node.key(), node.kind(), node.valueVersion(), node.emittedWork(),
				legal, node.exclusions(), node.anchors()));
		}
		return List.copyOf(nodes);
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
