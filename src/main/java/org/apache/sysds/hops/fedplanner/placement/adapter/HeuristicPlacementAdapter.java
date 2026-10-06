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
import org.apache.sysds.hops.fedplanner.placement.CandidateSelections;
import org.apache.sysds.hops.fedplanner.placement.LocalMaterializationSelections;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.LocalMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementSelection;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementCertificate.TerminationReason;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementAnalysisSelector;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyPlacementSelector;

import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact local-vector continuation preference over the unchanged common placement analysis. */
public final class HeuristicPlacementAdapter {
	private final PlacementAnalysisSelector selector;

	public HeuristicPlacementAdapter() {
		this(new PolicyGreedyPlacementSelector(PolicyGreedyPlacementSelector.Policy.AGG_LOCAL));
	}

	public HeuristicPlacementAdapter(PlacementAnalysisSelector selector) {
		this.selector = Objects.requireNonNull(selector, "selector");
	}

	public Result select(PlacementAnalysis analysis, Set<ValueVersionKey> demotionMarkers) {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(demotionMarkers, "demotionMarkers");
		NeutralPlacementGraph base = analysis.graph();
		// Markers remain audited inputs, not authority to project away common candidates.
		// The greedy policy consumes analysis-owned paths and their live exact supports.
		markerKeys(base, demotionMarkers);
		NeutralPlacementGraph filtered = base;
		List<String> exclusions = List.of();
		PlacementSelection selection = selector.select(analysis, base);
		List<String> candidateUniverse = filtered.normalizedCandidateUniverse();
		Map<CompiledHopKey, PlacementState> assignment = immutableAssignment(selection.assignment());
		validateProjection(analysis, filtered, assignment, occurrenceHopIndex(analysis));
		List<CandidateSelectionReceipt> candidateReceipts = List.copyOf(selection.selectedCandidateSelections());
		List<RelocationChoiceReceipt> choices = List.copyOf(selection.selectedRelocationChoices());
		List<RelocationActionKey> relocations = selection.selectedRelocations().stream().sorted().toList();
		int explicitRelocations = RelocationSelections.physicalEmissionCount(relocations);
		int localMaterializations = LocalMaterializationSelections.physicalEmissionCount(
			analysis, assignment, candidateReceipts);
		int foutMaterializations = CandidateSelections.foutMaterializationPhysicalEmissionCount(
			candidateReceipts);
		if(selection.score().distinctRelocationCount()
			!= Math.addExact(Math.addExact(explicitRelocations, localMaterializations),
				foutMaterializations))
			throw new IllegalStateException(
				"Heuristic score differs from its canonical physical-transfer projection");
		List<ObligationKey> obligations = RelocationSelections.resolveAndValidate(analysis, filtered,
			filtered.relocationActions(), assignment, candidateReceipts, choices).stream()
			.filter(RelocationSelections.ResolvedChoice::requiresEmission)
			.map(RelocationSelections.ResolvedChoice::obligation).sorted().toList();
		List<DurableAnchorKey> anchors = base.nodes().stream().flatMap(node -> node.anchors().stream())
			.distinct().sorted().toList();
		List<String> objective = List.of("FED=" + selection.score().emittedFedCount(),
			"FOUT=" + selection.score().foutCount(),
			"RELOCATIONS=" + selection.score().distinctRelocationCount(),
			"EXPLICIT_RELOCATIONS=" + explicitRelocations,
			"LOCAL_MATERIALIZATIONS=" + localMaterializations,
			"FOUT_MATERIALIZATIONS=" + foutMaterializations,
			"CP_FOUT_MATERIALIZATIONS=" + CandidateSelections.cpFoutPhysicalEmissionCount(candidateReceipts),
			"DERIVED_FOUT_MATERIALIZATIONS="
				+ CandidateSelections.derivedFoutPhysicalEmissionCount(candidateReceipts));
		boolean greedy = selector instanceof PolicyGreedyPlacementSelector;
		String stateOrdering = greedy ? ((PolicyGreedyPlacementSelector) selector).policy().name() : "EXPLICIT_CUSTOM_SELECTOR";
		List<String> ties = List.of("LOCAL_VECTOR_CONTINUATION", "EXACT_NATIVE_CONTINUATION", "INPUT_RESIDENCY",
			"LOCAL_AGGREGATE_OUTPUT",
			"NATIVE_FOUT", "FEWER_INPUT_UPLOADS", "CANONICAL_OWNED_ROW_ORDER");
		List<String> relationships = base.constraints().stream().filter(c -> isTransient(base, c.left())
			|| isTransient(base, c.right())).map(NeutralPlacementGraph.Constraint::normalizedSignature).sorted().toList();
		List<String> boundaries = base.constraints().stream().filter(c -> c.left().controlRegion()
			.compareTo(c.right().controlRegion()) != 0).map(NeutralPlacementGraph.Constraint::normalizedSignature)
			.sorted().toList();
		List<String> clones = base.nodes().stream().filter(n -> n.kind() == NodeKind.CLONE
			|| "recompile".equals(n.key().recompileContext())).map(Node::normalizedIdentity).sorted().toList();
		List<String> structural = base.normalizedExclusions();
		if(selection.certificate().terminationReason() != TerminationReason.POLICY_FEASIBLE)
			throw new IllegalStateException("Heuristic selector must return a non-optimal policy feasibility certificate");
		Map<String, String> facts = Collections.unmodifiableMap(new TreeMap<>(Map.of(
			"policy", greedy ? "LOCAL_VECTOR_CONTINUATION_POLICY_V6" : "EXPLICIT_CUSTOM_POLICY",
			"markerCount", Integer.toString(demotionMarkers.size()),
			"localPrefixCount", Long.toString(analysis.heuristicPolicyFacts().paths().stream()
				.flatMap(path -> path.localPrefix().stream()).distinct().count()),
			"downstreamMarkerCount", Long.toString(analysis.heuristicPolicyFacts().demotions().stream()
				.filter(marker -> assignment.get(marker.producer()) != null
					&& assignment.get(marker.producer()).execType() == ExecType.CP).count()),
			"frontierEdgeCount", Long.toString(analysis.heuristicPolicyFacts().paths().stream()
				.flatMap(path -> path.reentries().stream()).distinct().count()),
			"nativeContinuationCount", Long.toString(analysis.heuristicPolicyFacts().paths().stream()
				.flatMap(path -> path.nativeContinuations().stream()).distinct().count()),
			"search", greedy ? "GREEDY_BOUNDED_REVERSIBLE_REPAIR" : "EXPLICIT_CUSTOM_SELECTOR",
			"stateOrdering", stateOrdering, "shapeProof", "COMMON_OWNED_ABSTRACT_SHAPE_AND_EXACT_ROW")));
		String assignmentHash = commonAssignmentHash(assignment);
		String policyFingerprint = sha256(facts.get("policy") + '|' + stateOrdering + '|' + analysis.analysisFingerprint()
			+ '|' + candidateUniverse);
		String incumbent = selection.score().normalizedSignature();
		Score score = new Score(selection.score().emittedFedCount(), selection.score().foutCount(),
			selection.score().distinctRelocationCount(), incumbent);
		List<Bound> boundComponents = componentBounds(filtered);
		long explored = selection.certificate().exploredCount();
		long pruned = selection.certificate().prunedCount();
		var structuralUpper = selection.certificate().finalUpperBound();
		Score upper = new Score(structuralUpper.emittedFedCount(), structuralUpper.foutCount(),
			structuralUpper.distinctRelocationCount(), structuralUpper.normalizedSignature());
		Certificate certificate = new Certificate(analysis.analysisFingerprint(), policyFingerprint,
			assignmentHash, explored + pruned, explored, pruned,
			List.of(greedy ? "greedy-bounded-reversible-repair" : "explicit-custom-selector"), incumbent,
			upper.normalizedSignature(), selection.certificate().terminationReason().name(), false,
			sha256(filtered.normalizedSignature()), score, upper,
			boundComponents, filtered.nodes().size(), filtered.constraints().size(), boundComponents.size(),
			selection.certificate().boundDerivation());
		Result partial = new Result(analysis, analysis.analysisFingerprint(), filtered, assignment,
			candidateReceipts, choices, candidateUniverse, exclusions, relocations, obligations, anchors,
			List.of(), List.of(), List.of(), objective, ties, relationships,
			boundaries, clones, structural, facts, certificate, score, "");
		return partial.withNormalizedPlanFingerprint(PlacementEmissionTransaction.canonicalPlanHash(partial));
	}

	private static List<CompiledHopKey> markerKeys(NeutralPlacementGraph graph, Set<ValueVersionKey> markers) {
		if(markers.isEmpty()) return List.of();
		Map<ValueVersionKey,List<CompiledHopKey>> keysByValue = new LinkedHashMap<>();
		for(Node node : graph.nodes())
			keysByValue.computeIfAbsent(node.valueVersion(), ignored -> new ArrayList<>()).add(node.key());
		List<CompiledHopKey> keys = new ArrayList<>(markers.size());
		for(ValueVersionKey marker : markers.stream().sorted().toList()) {
			List<CompiledHopKey> matches = keysByValue.getOrDefault(marker, List.of());
			if(matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous demotion marker");
			keys.add(matches.get(0));
		}
		if(keys.size() != markers.size()) throw new IllegalArgumentException("Unknown or ambiguous demotion marker");
		return List.copyOf(keys);
	}

	private static boolean isTransient(NeutralPlacementGraph graph, CompiledHopKey key) {
		NodeKind kind = graph.node(key).orElseThrow().kind();
		return kind == NodeKind.TRANSIENT_READ || kind == NodeKind.TRANSIENT_WRITE;
	}
	private static Map<CompiledHopKey,Hop> occurrenceHopIndex(PlacementAnalysis analysis) {
		Map<CompiledHopKey,Hop> indexed = new LinkedHashMap<>();
		for(var occurrence : analysis.occurrences())
			if(indexed.put(occurrence.key(), occurrence.hop()) != null)
				throw new IllegalStateException("Ambiguous concrete Hop occurrence identity");
		return indexed;
	}

	private static void validateProjection(PlacementAnalysis analysis, NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment,
		Map<CompiledHopKey,Hop> occurrenceHops) {
		Set<CompiledHopKey> decisionKeys = graph.decisionNodes().stream().map(Node::key)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if(!assignment.keySet().equals(decisionKeys)) throw new IllegalStateException("Incomplete Heuristic assignment");
		for(var entry : assignment.entrySet()) {
			if(graph.node(entry.getKey()).orElseThrow().legalAlternatives().stream()
				.noneMatch(state -> state == entry.getValue()))
				throw new IllegalStateException("State outside exact filtered node-owned universe");
			Hop hop = analysis.hop(entry.getKey()).orElseThrow();
			if(occurrenceHops.get(entry.getKey()) != hop)
				throw new IllegalStateException("Concrete Hop alias lost");
		}
	}
	private static Map<CompiledHopKey, PlacementState> immutableAssignment(Map<CompiledHopKey, PlacementState> source) {
		Map<CompiledHopKey, PlacementState> result = new TreeMap<>(); result.putAll(source);
		return Collections.unmodifiableMap(result);
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
		List<CandidateSelectionReceipt> selectedCandidateSelections,
		List<RelocationChoiceReceipt> selectedRelocationChoices,
		List<String> filteredCandidateUniverse,
		List<String> policyExclusions, List<RelocationActionKey> selectedRelocations,
		List<ObligationKey> selectedObligations, List<DurableAnchorKey> durableAnchors,
		List<String> registryRefed, List<String> registryFoutMaterialize, List<String> registryLocalMaterialize,
		List<String> objectiveComponents, List<String> orderedTieBreaks, List<String> transientRelationships,
		List<String> controlBoundaryFacts, List<String> cloneRecompileMultiplicities,
		List<String> structuralExclusions, Map<String,String> plannerFacts, Certificate certificate, Score score,
		String normalizedPlanFingerprint) implements NormalizedPlannerResult {
		public Result {
			assignment=immutableAssignment(assignment);
			selectedCandidateSelections=List.copyOf(selectedCandidateSelections);
			selectedRelocationChoices=List.copyOf(selectedRelocationChoices);
			filteredCandidateUniverse=List.copyOf(filteredCandidateUniverse);
			policyExclusions=List.copyOf(policyExclusions); selectedRelocations=List.copyOf(selectedRelocations);
			selectedObligations=List.copyOf(selectedObligations); durableAnchors=List.copyOf(durableAnchors);
			registryRefed=List.copyOf(registryRefed); registryFoutMaterialize=List.copyOf(registryFoutMaterialize);
			registryLocalMaterialize=List.copyOf(registryLocalMaterialize); objectiveComponents=List.copyOf(objectiveComponents);
			orderedTieBreaks=List.copyOf(orderedTieBreaks); transientRelationships=List.copyOf(transientRelationships);
			controlBoundaryFacts=List.copyOf(controlBoundaryFacts); cloneRecompileMultiplicities=List.copyOf(cloneRecompileMultiplicities);
			structuralExclusions=List.copyOf(structuralExclusions); plannerFacts=Collections.unmodifiableMap(new TreeMap<>(plannerFacts));
		}
		Result withNormalizedPlanFingerprint(String value) { return new Result(analysis,analysisFingerprint,selectorGraph,assignment,
			selectedCandidateSelections,selectedRelocationChoices,filteredCandidateUniverse,policyExclusions,
			selectedRelocations,selectedObligations,durableAnchors,
			registryRefed,registryFoutMaterialize,registryLocalMaterialize,objectiveComponents,orderedTieBreaks,
			transientRelationships,controlBoundaryFacts,cloneRecompileMultiplicities,structuralExclusions,
			plannerFacts,certificate,score,value); }
		@Override public String plannerId() { return "FED_HEURISTIC"; }
		@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return assignment; }
		@Override public List<LocalMaterializationActionKey> selectedLocalMaterializations() {
			return LocalMaterializationSelections.derive(analysis, assignment,
				NormalizedPlannerResults.exactEmissionStates(
					analysis, assignment, selectedCandidateSelections),
				selectedCandidateSelections);
		}
		@Override public String objectiveCertificate() { return certificate.toString(); }
	}
}
