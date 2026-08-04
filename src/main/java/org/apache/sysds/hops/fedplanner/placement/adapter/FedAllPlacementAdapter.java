/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationChoiceReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.selector.ExactPlacementSelector;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementScore;
import org.apache.sysds.hops.fedplanner.placement.selector.PlacementSelection;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

/** Exact, mutation-free FedAll policy boundary over one supplied placement analysis. */
public final class FedAllPlacementAdapter implements PlacementPlannerAdapter<FedAllPlacementAdapter.Result> {
	private static final String COMPONENT_DERIVATION = "independent-component-envelope";
	private final ExactPlacementSelector selector = new ExactPlacementSelector();

	@Override
	public Result select(PlannerPlacementContext context) {
		Objects.requireNonNull(context, "context");
		PlacementAnalysis analysis = context.analysis();
		PlacementSelection selection = selector.select(analysis);
		validateProjection(analysis, selection);
		Map<CompiledHopKey, PlacementState> assignment = immutableAssignment(selection.assignment());
		List<CandidateSelectionReceipt> candidates = List.copyOf(selection.selectedCandidateSelections());
		List<RelocationChoiceReceipt> choices = List.copyOf(selection.selectedRelocationChoices());
		List<RelocationActionKey> relocations = immutableRelocations(selection.selectedRelocations());
		Score score = score(analysis.graph(), assignment, relocations, selection.score());
		List<Bound> bounds = componentBounds(analysis.graph());
		long explored = selection.certificate().exploredCount();
		long pruned = selection.certificate().prunedCount();
		Certificate certificate = new Certificate(sha256(analysis.graph().normalizedSignature()),
			assignmentHash(assignment), explored, pruned, explored + pruned,
			score, score, bounds, analysis.graph().nodes().size(), analysis.graph().constraints().size(),
			structuralComponentCount(analysis.graph()), selection.certificate().boundDerivation(),
			selection.certificate().terminationReason().name(), false);
		Result draft = new Result(analysis, assignment, candidates, choices, relocations, score, certificate,
			context.analysisFingerprint(), "canonicalization-pending");
		return new Result(analysis, assignment, candidates, choices, relocations, score, certificate,
			context.analysisFingerprint(), PlacementEmissionTransaction.canonicalPlanHash(draft));
	}

	@Override
	public Result select(PlacementAnalysis analysis) {
		return select(PlannerPlacementContext.of(analysis));
	}

	private static void validateProjection(PlacementAnalysis analysis, PlacementSelection selection) {
		Set<CompiledHopKey> graphKeys = new LinkedHashSet<>();
		for(Node node : analysis.graph().decisionNodes()) graphKeys.add(node.key());
		if(!selection.assignment().keySet().equals(graphKeys))
			throw new IllegalStateException("FedAll selector did not return a total graph assignment");
		for(Map.Entry<CompiledHopKey, PlacementState> entry : selection.assignment().entrySet()) {
			Node node = analysis.graph().node(entry.getKey()).orElseThrow();
			if(node.legalAlternatives().stream().noneMatch(state -> state == entry.getValue()))
				throw new IllegalStateException(
					"FedAll selector returned a state outside the exact node-owned legal universe");
			Hop hop = analysis.hop(entry.getKey()).orElseThrow(() ->
				new IllegalStateException("FedAll assignment has no concrete Hop projection"));
			boolean exactAlias = analysis.occurrences().stream().anyMatch(occurrence ->
				occurrence.key().equals(entry.getKey()) && occurrence.hop() == hop);
			if(!exactAlias)
				throw new IllegalStateException("FedAll assignment lost its concrete Hop alias");
		}
		Set<RelocationActionKey> graphRelocations = new LinkedHashSet<>();
		analysis.graph().relocationActions().forEach(action -> graphRelocations.add(action.key()));
		if(!graphRelocations.containsAll(selection.selectedRelocations()))
			throw new IllegalStateException("FedAll selector returned a foreign relocation action");
	}

	private static Map<CompiledHopKey, PlacementState> immutableAssignment(
		Map<CompiledHopKey, PlacementState> assignment) {
		List<Map.Entry<CompiledHopKey, PlacementState>> entries = new ArrayList<>(assignment.entrySet());
		entries.sort(Map.Entry.comparingByKey());
		Map<CompiledHopKey, PlacementState> ordered = new LinkedHashMap<>();
		for(Map.Entry<CompiledHopKey, PlacementState> entry : entries)
			ordered.put(entry.getKey(), entry.getValue());
		return Collections.unmodifiableMap(ordered);
	}

	private static List<RelocationActionKey> immutableRelocations(Set<RelocationActionKey> relocations) {
		List<RelocationActionKey> ordered = new ArrayList<>(relocations);
		Collections.sort(ordered);
		if(new LinkedHashSet<>(ordered).size() != ordered.size())
			throw new IllegalStateException("FedAll selector returned duplicate relocation actions");
		return List.copyOf(ordered);
	}

	private static Score score(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment, List<RelocationActionKey> relocations,
		PlacementScore score) {
		return new Score(score.emittedFedCount(), score.foutCount(), score.distinctRelocationCount(),
			policySignature(graph, assignment, relocations));
	}

	private static String policySignature(NeutralPlacementGraph graph,
		Map<CompiledHopKey, PlacementState> assignment, List<RelocationActionKey> relocations) {
		List<String> entries = new ArrayList<>();
		for(Map.Entry<CompiledHopKey, PlacementState> entry : assignment.entrySet())
			entries.add(entry.getKey().emittedHopInstance() + '=' + policyChoice(graph,
				entry.getKey(), entry.getValue(), relocations));
		Collections.sort(entries);
		return String.join("|", entries);
	}

	private static String policyChoice(NeutralPlacementGraph graph, CompiledHopKey key,
		PlacementState state, List<RelocationActionKey> relocations) {
		List<PlacementState> legal = graph.node(key).orElseThrow().legalAlternatives();
		if(state.execType() == ExecType.CP && state.output() == FederatedOutput.LOUT) return "local";
		if(legal.size() == 3) {
			if(state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT) return "fed-lout";
			if(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT) return "fed-fout";
		}
		if(state.execType() == ExecType.CP && state.output() == FederatedOutput.FOUT)
			return relocationSelectedFor(graph, key, state, relocations) ? "uploaded" : "fout";
		if(state.execType() == ExecType.FED && state.output() == FederatedOutput.LOUT) {
			if(legal.stream().anyMatch(candidate -> candidate.execType() == ExecType.CP)) return "fed";
			return state.fType() == org.apache.sysds.hops.fedplanner.FTypes.FType.COL ? "alpha" : "omega";
		}
		if(state.execType() == ExecType.FED && state.output() == FederatedOutput.FOUT) {
			if(state.fType() == org.apache.sysds.hops.fedplanner.FTypes.FType.FULL) return "full";
			if(legal.stream().anyMatch(candidate -> candidate.execType() == ExecType.CP)) return "fed";
			return selectedRelocationIsShared(graph, key, state, relocations) ? "shared" : "split";
		}
		throw new IllegalStateException("FedAll policy signature has an unclassified legal state");
	}

	private static boolean relocationSelectedFor(NeutralPlacementGraph graph, CompiledHopKey key,
		PlacementState state, List<RelocationActionKey> selected) {
		for(var action : graph.relocationActions())
			if(selected.contains(action.key()) && action.obligations().stream().anyMatch(obligation ->
				obligation.consumer().equals(key) && obligation.requiredPlacement().equals(state))) return true;
		return false;
	}

	private static boolean selectedRelocationIsShared(NeutralPlacementGraph graph, CompiledHopKey key,
		PlacementState state, List<RelocationActionKey> selected) {
		for(var action : graph.relocationActions())
			if(selected.contains(action.key()) && action.key().compatibleConsumers().size() > 1
				&& action.obligations().stream().anyMatch(obligation -> obligation.consumer().equals(key)
					&& obligation.requiredPlacement().equals(state))) return true;
		return false;
	}

	private static List<Bound> componentBounds(NeutralPlacementGraph graph) {
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node node : graph.nodes()) adjacency.put(node.key(), new LinkedHashSet<>());
		for(Constraint constraint : graph.constraints()) {
			adjacency.get(constraint.left()).add(constraint.right());
			adjacency.get(constraint.right()).add(constraint.left());
		}
		Set<CompiledHopKey> seen = new LinkedHashSet<>();
		List<Bound> bounds = new ArrayList<>();
		for(CompiledHopKey start : adjacency.keySet().stream().sorted().toList()) {
			if(!seen.add(start)) continue;
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
			List<CompiledHopKey> nodes = new ArrayList<>();
			pending.add(start);
			while(!pending.isEmpty()) {
				CompiledHopKey key = pending.removeFirst();
				nodes.add(key);
				for(CompiledHopKey adjacent : adjacency.get(key))
					if(seen.add(adjacent)) pending.addLast(adjacent);
			}
			nodes.sort(Comparator.naturalOrder());
			int upperFed = 0;
			int upperFout = 0;
			for(CompiledHopKey key : nodes) {
				List<PlacementState> states = graph.node(key).orElseThrow().legalAlternatives();
				if(states.stream().anyMatch(state -> state.execType() == ExecType.FED)) upperFed++;
				if(states.stream().anyMatch(state -> state.output() == FederatedOutput.FOUT)) upperFout++;
			}
			String componentId = Integer.toHexString(nodes.stream()
				.map(CompiledHopKey::normalizedSignature).toList().hashCode());
			bounds.add(new Bound(componentId, List.copyOf(nodes), upperFed, upperFout, 0,
				COMPONENT_DERIVATION));
		}
		bounds.sort(Comparator.comparing(Bound::componentId));
		return List.copyOf(bounds);
	}

	private static int structuralComponentCount(NeutralPlacementGraph graph) {
		Map<CompiledHopKey, Set<CompiledHopKey>> adjacency = new LinkedHashMap<>();
		for(Node node : graph.nodes()) adjacency.put(node.key(), new LinkedHashSet<>());
		for(Constraint constraint : graph.constraints())
			if(constraint.kind() == NeutralPlacementGraph.ConstraintKind.DOMINATES) {
				adjacency.get(constraint.left()).add(constraint.right());
				adjacency.get(constraint.right()).add(constraint.left());
			}
		Set<CompiledHopKey> seen = new LinkedHashSet<>();
		int components = 0;
		for(CompiledHopKey start : adjacency.keySet()) {
			if(!seen.add(start)) continue;
			components++;
			ArrayDeque<CompiledHopKey> pending = new ArrayDeque<>();
			pending.add(start);
			while(!pending.isEmpty())
				for(CompiledHopKey adjacent : adjacency.get(pending.removeFirst()))
					if(seen.add(adjacent)) pending.addLast(adjacent);
		}
		return components;
	}

	private static String assignmentHash(Map<CompiledHopKey, PlacementState> assignment) {
		List<String> lines = assignment.entrySet().stream().map(entry ->
			entry.getKey().normalizedSignature() + '=' + entry.getValue().normalizedSignature()).sorted().toList();
		return sha256(String.join("\n", lines));
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch(Exception e) {
			throw new IllegalStateException("JVM must provide SHA-256", e);
		}
	}

	/** Adapter-facing exact objective view. */
	public record Score(int fedCount, int foutCount, int relocationCount, String normalizedSignature) {
		public Score { Objects.requireNonNull(normalizedSignature, "normalizedSignature"); }
	}

	/** Adapter-facing independently recomputable component envelope. */
	public record Bound(String componentId, List<CompiledHopKey> nodeKeys, int upperFed, int upperFout,
		int lowerRelocations, String derivation) {
		public Bound {
			nodeKeys = List.copyOf(nodeKeys);
			Objects.requireNonNull(componentId, "componentId");
			Objects.requireNonNull(derivation, "derivation");
		}
	}

	/** Adapter-facing complete exact-search certificate. */
	public record Certificate(String graphFingerprint, String assignmentHash, long exploredCount,
		long prunedCount, long legalUniverseSize, Score incumbentScore, Score finalUpperBound,
		List<Bound> boundComponents, int graphNodeCount, int graphConstraintCount,
		int graphComponentCount, String boundDerivation, String terminationReason, boolean fallbackUsed) {
		public Certificate {
			boundComponents = List.copyOf(boundComponents);
			Objects.requireNonNull(graphFingerprint, "graphFingerprint");
			Objects.requireNonNull(assignmentHash, "assignmentHash");
			Objects.requireNonNull(incumbentScore, "incumbentScore");
			Objects.requireNonNull(finalUpperBound, "finalUpperBound");
			Objects.requireNonNull(boundDerivation, "boundDerivation");
			Objects.requireNonNull(terminationReason, "terminationReason");
			if(exploredCount < 0 || prunedCount < 0 || legalUniverseSize != exploredCount + prunedCount)
				throw new IllegalArgumentException("invalid exact-search universe counts");
		}
	}

	/** Immutable exact FedAll selection bound to the supplied analysis instance. */
	public record Result(PlacementAnalysis analysis, Map<CompiledHopKey, PlacementState> assignment,
		List<CandidateSelectionReceipt> selectedCandidateSelections,
		List<RelocationChoiceReceipt> selectedRelocationChoices,
		List<RelocationActionKey> selectedRelocations, Score score, Certificate certificate,
		String analysisFingerprint, String normalizedPlanFingerprint) implements NormalizedPlannerResult {
		public Result {
			Objects.requireNonNull(analysis, "analysis");
			assignment = immutableAssignment(assignment);
			selectedCandidateSelections = List.copyOf(selectedCandidateSelections);
			selectedRelocationChoices = List.copyOf(selectedRelocationChoices);
			selectedRelocations = List.copyOf(selectedRelocations);
			Objects.requireNonNull(score, "score");
			Objects.requireNonNull(certificate, "certificate");
			Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
			Objects.requireNonNull(normalizedPlanFingerprint, "normalizedPlanFingerprint");
		}
		@Override public String plannerId() { return "FED_ALL"; }
		@Override public Map<CompiledHopKey, PlacementState> selectedStates() { return assignment; }
		@Override public String objectiveCertificate() { return certificate.toString(); }
	}
}
