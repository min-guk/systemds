/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Constraint;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ConstraintKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.NodeKind;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.ReasonCode;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.DecisionEvidence;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;

/** Finite mutation-free construction of the planner-neutral shadow graph. */
public final class NeutralPlacementGraphBuilder {
	private final OracleFacade oracle = new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

	public List<String> selectedProjection(DMLProgram program) {
		List<String> selected = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence : PlacementGraphFingerprint.orderedOccurrences(program)) {
			Hop hop = occurrence.hop();
			ExecType selectedExec = selectedExecType(hop);
			selected.add(occurrence.namespace() + '|' + occurrence.path() + '|'
				+ PlacementGraphFingerprint.structuralKey(hop) + '|'
				+ String.valueOf(selectedExec) + '/' + String.valueOf(hop.getFederatedOutput()));
		}
		Collections.sort(selected);
		return Collections.unmodifiableList(selected);
	}

	public List<String> selectedMembershipViolations(DMLProgram program, NeutralPlacementGraph graph) {
		List<String> violations = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence : PlacementGraphFingerprint.orderedOccurrences(program)) {
			Hop hop = occurrence.hop();
			ExecType selectedExec = selectedExecType(hop);
			if(selectedExec == null || hop.getFederatedOutput() == FederatedOutput.NONE) continue;
			Node node = graph.nodes().stream().filter(n -> n.key().callSitePath().equals(occurrence.path())
				&& n.key().canonicalSourceOrigin().equals(PlacementGraphFingerprint.structuralKey(hop))).findFirst().orElse(null);
			boolean member = node != null && node.legalAlternatives().stream().anyMatch(s ->
				s.execType() == selectedExec && s.output() == hop.getFederatedOutput());
			if(!member) violations.add(occurrence.namespace() + '|' + occurrence.path() + '|'
				+ PlacementGraphFingerprint.structuralKey(hop) + '|' + selectedExec + '/' + hop.getFederatedOutput());
		}
		Collections.sort(violations);
		return Collections.unmodifiableList(violations);
	}

	private static ExecType selectedExecType(Hop hop) {
		return hop.getForcedExecType() != null ? hop.getForcedExecType() : hop.getExecType();
	}

	public NeutralPlacementGraph build(DMLProgram program) {
		String before = PlacementGraphFingerprint.capture(program);
		String registryBefore = registrySentinel(program);
		List<PlacementGraphFingerprint.HopOccurrence> occurrences = PlacementGraphFingerprint.orderedOccurrences(program);
		String programId = structuralFingerprint(occurrences);
		List<Node> nodes = new ArrayList<>();
		Map<String,Integer> versions = new java.util.TreeMap<>();
		Map<String,List<ValueVersionKey>> definitions = new java.util.TreeMap<>();
		Map<Hop,ValueVersionKey> values = new IdentityHashMap<>();
		Map<Hop,CompiledHopKey> keys = new IdentityHashMap<>();
		Map<Hop,Node> nodesByHop = new IdentityHashMap<>();
		Map<Hop,DurableAnchorKey> anchorProvenance = new IdentityHashMap<>();
		for(int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
			PlacementGraphFingerprint.HopOccurrence occurrence = occurrences.get(ordinal);
			Hop hop = occurrence.hop();
			String context = requiresRecompileMetadata(hop) ? "recompile" : "compiled";
			ControlRegionKey region = new ControlRegionKey(programId, occurrence.namespace(),
				Arrays.asList(occurrence.path().split("/")), occurrence.path(), context);
			CompiledHopKey key = new CompiledHopKey(programId, occurrence.namespace(), occurrence.path(), context, region,
				"ordinal-" + ordinal, PlacementGraphFingerprint.structuralKey(hop));
			String variable = lexicalVariable(hop, ordinal);
			int version = isTransientWrite(hop) ? versions.merge(variable, 1, Integer::sum) : versions.getOrDefault(variable, 0);
			VersionKind versionKind = versionKind(hop, occurrence, context, definitions.get(variable));
			List<String> predecessorEdges = new ArrayList<>();
			for(int inputPosition = 0; inputPosition < hop.getInput().size(); inputPosition++) {
				Hop input = hop.getInput(inputPosition);
				if(values.containsKey(input)) predecessorEdges.add("input-" + inputPosition + ':'
					+ values.get(input).normalizedSignature());
			}
			if(isTransientRead(hop) && definitions.containsKey(variable))
				for(ValueVersionKey definition : definitions.get(variable))
					predecessorEdges.add("definition:" + definition.normalizedSignature());
			ValueVersionKey value = new ValueVersionKey(programId, variable, region, version, versionKind,
				predecessorEdges);
			if(isTransientWrite(hop) || isFunctionOutput(hop))
				definitions.computeIfAbsent(variable, k -> new ArrayList<>()).add(value);
			values.put(hop, value);
			keys.put(hop, key);
			List<DurableAnchorKey> anchors = durableAnchor(hop);
			if(!anchors.isEmpty()) anchorProvenance.put(hop, anchors.get(0));
			else {
				Set<DurableAnchorKey> inherited = new java.util.TreeSet<>();
				for(Hop input : hop.getInput())
					if(anchorProvenance.containsKey(input)) inherited.add(anchorProvenance.get(input));
				if(inherited.size() == 1) anchorProvenance.put(hop, inherited.iterator().next());
			}
			Node node = buildNode(hop, key, value, anchors, inputDomains(hop, nodesByHop));
			nodes.add(node);
			nodesByHop.put(hop, node);
		}
		Set<Constraint> constraints = new java.util.TreeSet<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences) {
			CompiledHopKey consumer = keys.get(occurrence.hop());
			for(Hop input : occurrence.hop().getInput())
				if(keys.containsKey(input)) constraints.add(new Constraint(ConstraintKind.DOMINATES, keys.get(input), consumer));
		}
		List<NeutralPlacementGraph.RelocationAction> relocations = relocations(occurrences, keys, values, anchorProvenance);
		NeutralPlacementGraph graph = new NeutralPlacementGraph(nodes, constraints, relocations);
		String after = PlacementGraphFingerprint.capture(program);
		if(!before.equals(after))
			throw new IllegalStateException("Neutral placement analysis mutated the compiled Hop graph");
		if(!registryBefore.equals(registrySentinel(program)))
			throw new IllegalStateException("Neutral placement analysis mutated federated refed state");
		return graph;
	}

	private static String registrySentinel(DMLProgram program) {
		List<String> rows = new ArrayList<>();
		for(long sbId : PlacementGraphFingerprint.statementBlockIds(program)) {
			FederatedRefedRegistry.snapshot(sbId).forEach((hop, spec) -> rows.add("R|" + sbId + '|' + hop + '|'
				+ spec.getAnchorHopId() + '|' + spec.getAnchorKey()));
			FederatedFoutMaterializeRegistry.snapshot(sbId).forEach((hop, spec) -> rows.add("F|" + sbId + '|' + hop
				+ '|' + spec.getAnchorHopId() + '|' + spec.getFTypeHint() + '|' + spec.getAnchorLabel() + '|' + spec.getAnchorKey()));
			FederatedLocalMaterializeRegistry.snapshotScopes(sbId).forEach((scope, entries) -> entries.forEach((hop, spec) ->
				rows.add("L|" + scope + '|' + hop + '|' + spec.getConsumerHopIds() + '|' + spec.getFTypeHint() + '|' + spec.getReason())));
		}
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}

	private Node buildNode(Hop hop, CompiledHopKey key, ValueVersionKey value, List<DurableAnchorKey> anchors,
		List<List<FType>> inputDomains) {
		Set<PlacementState> legal = new LinkedHashSet<>();
		Map<PlacementState,Exclusion> excluded = new java.util.TreeMap<>();
		PlacementState cp = new PlacementState(ExecType.CP, FederatedOutput.LOUT, null, false);
		legal.add(cp);
		boolean transientAccess = isTransientRead(hop) || isTransientWrite(hop);
		for(List<FType> inputs : inputCombinations(inputDomains)) {
			OpCaps caps;
			boolean shapeDependent;
			try {
				DecisionEvidence evidence = oracle.decideWithEvidence(hop, inputs, null);
				caps = evidence.caps();
				shapeDependent = evidence.shapeDependent();
			}
			catch(Throwable t) {
				PlacementState failure = new PlacementState(ExecType.FED, FederatedOutput.LOUT, firstFType(inputs), false);
				excluded.putIfAbsent(failure, new Exclusion(failure, ReasonCode.RULE_ERROR,
					"RULE_ERROR:" + t.getClass().getSimpleName()));
				continue;
			}
			FType outType = caps.foutFType().orElse(firstFType(inputs));
			PlacementState state = new PlacementState(caps.exec(), caps.placement(), outType, shapeDependent);
			String detail = "inputs=" + inputEvidence(inputs) + "|proof=" + evidence.shapeProof()
				+ '|' + caps.reason().name() + caps.detail().map(s -> ":" + s).orElse("");
			if(caps.reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR)
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.RULE_ERROR, detail));
			else if(transientAccess && !isLegalTransient(state))
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.ILLEGAL_TRANSIENT_PLACEMENT, detail));
			else if(key.recompileContext().equals("recompile") && state.execType() == ExecType.CP
				&& state.output() == FederatedOutput.FOUT)
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.RECOMPILE_CP_FOUT, detail));
			else if(!evidence.shapeProof().missingRequiredFacts().isEmpty())
				excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNKNOWN_METADATA, detail));
			else if(caps.exec() == ExecType.FED)
				legal.add(state);
		}
		if(transientAccess)
			legal.removeIf(s -> !isLegalTransient(s));
		if(hop instanceof DataOp && ((DataOp) hop).getOp() == OpOpData.FEDERATED && anchors.isEmpty()) {
			PlacementState state = new PlacementState(ExecType.FED, FederatedOutput.FOUT, FType.OTHER, true);
			excluded.putIfAbsent(state, new Exclusion(state, ReasonCode.UNSUPPORTED_ANCHOR,
				"Federated source lacks literal durable worker/range provenance"));
		}
		return new Node(key, nodeKind(hop, value), value, true, new ArrayList<>(legal),
			new ArrayList<>(excluded.values()), anchors);
	}

	private static List<DurableAnchorKey> durableAnchor(Hop hop) {
		if(!(hop instanceof DataOp) || ((DataOp) hop).getOp() != OpOpData.FEDERATED) return List.of();
		DataOp data = (DataOp) hop;
		int addressIndex = data.getParameterIndex(DataExpression.FED_ADDRESSES);
		int rangeIndex = data.getParameterIndex(DataExpression.FED_RANGES);
		if(addressIndex < 0 || rangeIndex < 0) return List.of();
		List<Hop> addresses = data.getInput(addressIndex).getInput();
		List<Hop> ranges = data.getInput(rangeIndex).getInput();
		if(addresses.isEmpty() || ranges.size() != addresses.size() * 2) return List.of();
		List<AnchorPartition> partitions = new ArrayList<>();
		for(int i = 0; i < addresses.size(); i++) {
			if(!(addresses.get(i) instanceof LiteralOp)) return List.of();
			Hop begin = ranges.get(2 * i), end = ranges.get(2 * i + 1);
			if(begin.getInput().size() < 2 || end.getInput().size() < 2
				|| !(begin.getInput(0) instanceof LiteralOp) || !(begin.getInput(1) instanceof LiteralOp)
				|| !(end.getInput(0) instanceof LiteralOp) || !(end.getInput(1) instanceof LiteralOp)) return List.of();
			partitions.add(new AnchorPartition(((LiteralOp) addresses.get(i)).getStringValue(),
				List.of(((LiteralOp) begin.getInput(0)).getLongValue(), ((LiteralOp) begin.getInput(1)).getLongValue()),
				List.of(((LiteralOp) end.getInput(0)).getLongValue(), ((LiteralOp) end.getInput(1)).getLongValue())));
		}
		FType type = FederatedPlannerUtils.deriveFedInitFType(data);
		if(type == null || type == FType.PART || type == FType.OTHER) return List.of();
		return List.of(new DurableAnchorKey("fed-init:" + data.getName(), type, partitions));
	}

	private static List<NeutralPlacementGraph.RelocationAction> relocations(
		List<PlacementGraphFingerprint.HopOccurrence> occurrences, Map<Hop,CompiledHopKey> keys,
		Map<Hop,ValueVersionKey> values, Map<Hop,DurableAnchorKey> anchorProvenance) {
		Map<ValueVersionKey,List<InputUse>> uses = new java.util.TreeMap<>();
		Map<ValueVersionKey,DurableAnchorKey> valueAnchors = new java.util.TreeMap<>();
		for(PlacementGraphFingerprint.HopOccurrence occurrence : occurrences)
			for(int i = 0; i < occurrence.hop().getInput().size(); i++) {
				Hop input = occurrence.hop().getInput(i);
				if(values.containsKey(input) && anchorProvenance.containsKey(input)) {
					uses.computeIfAbsent(values.get(input), k -> new ArrayList<>())
						.add(new InputUse(keys.get(occurrence.hop()), i, occurrence.path()));
					valueAnchors.put(values.get(input), anchorProvenance.get(input));
				}
			}
		List<NeutralPlacementGraph.RelocationAction> result = new ArrayList<>();
		for(Map.Entry<ValueVersionKey,List<InputUse>> entry : uses.entrySet()) {
			DurableAnchorKey anchor = valueAnchors.get(entry.getKey());
			PlacementState target = new PlacementState(ExecType.FED, FederatedOutput.FOUT, anchor.fType(), false);
			List<CompiledHopKey> consumers = new ArrayList<>();
			for(InputUse use : entry.getValue()) consumers.add(use.consumer());
			RelocationActionKey key = new RelocationActionKey(entry.getKey(), target, anchor,
				entry.getValue().get(0).scope(), consumers);
			List<ObligationKey> obligations = new ArrayList<>();
			for(InputUse use : entry.getValue()) obligations.add(new ObligationKey(use.consumer(), use.position(),
				entry.getKey(), target, key, use.scope()));
			result.add(new NeutralPlacementGraph.RelocationAction(key, obligations));
		}
		return result;
	}

	private record InputUse(CompiledHopKey consumer, int position, String scope) { }

	private static List<List<FType>> inputDomains(Hop hop, Map<Hop,Node> nodesByHop) {
		List<List<FType>> domains = new ArrayList<>();
		for(Hop input : hop.getInput()) {
			Set<FType> types = new LinkedHashSet<>();
			Node predecessor = nodesByHop.get(input);
			if(predecessor != null) {
				predecessor.legalAlternatives().forEach(state -> { if(state.fType() != null) types.add(state.fType()); });
				predecessor.exclusions().forEach(exclusion -> {
					if(exclusion.state().fType() != null) types.add(exclusion.state().fType());
				});
			}
			if(types.isEmpty()) domains.add(Collections.singletonList(null));
			else {
				List<FType> sorted = new ArrayList<>(types);
				sorted.sort(java.util.Comparator.comparing(Enum::name));
				domains.add(Collections.unmodifiableList(sorted));
			}
		}
		return domains;
	}

	private static List<List<FType>> inputCombinations(List<List<FType>> domains) {
		if(domains.isEmpty()) return List.of(List.of());
		List<List<FType>> result = new ArrayList<>();
		enumerateInputCombinations(domains, new ArrayList<>(), result);
		return result;
	}
	private static void enumerateInputCombinations(List<List<FType>> domains, List<FType> prefix,
		List<List<FType>> result) {
		if(prefix.size() == domains.size()) {
			result.add(Collections.unmodifiableList(new ArrayList<>(prefix)));
			return;
		}
		for(FType type : domains.get(prefix.size())) {
			prefix.add(type);
			enumerateInputCombinations(domains, prefix, result);
			prefix.remove(prefix.size() - 1);
		}
	}
	private static String inputEvidence(List<FType> inputs) {
		List<String> evidence = new ArrayList<>();
		for(int i = 0; i < inputs.size(); i++) evidence.add(i + ":" + String.valueOf(inputs.get(i)));
		return String.join(",", evidence);
	}

	private static FType firstFType(List<FType> values) { return values.isEmpty() ? null : values.get(0); }
	private static boolean requiresRecompileMetadata(Hop h) { return h.requiresRecompile(); }
	private static boolean isLegalTransient(PlacementState s) {
		return (s.execType() == ExecType.CP && s.output() == FederatedOutput.LOUT)
			|| (s.execType() == ExecType.FED && s.output() == FederatedOutput.FOUT);
	}
	private static boolean isTransientRead(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTREAD; }
	private static boolean isTransientWrite(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.TRANSIENTWRITE; }
	private static boolean isFunctionOutput(Hop h) { return h instanceof DataOp && ((DataOp) h).getOp() == OpOpData.FUNCTIONOUTPUT; }
	private static VersionKind versionKind(Hop hop, PlacementGraphFingerprint.HopOccurrence occurrence,
		String context, List<ValueVersionKey> priorDefinitions) {
		if(context.equals("recompile")) return VersionKind.CLONE_RECOMPILE;
		if(isFunctionOutput(hop)) return VersionKind.FUNCTION_OUTPUT;
		if(isTransientRead(hop) && !"main".equals(occurrence.namespace())
			&& (priorDefinitions == null || priorDefinitions.isEmpty())) return VersionKind.FUNCTION_INPUT;
		if(occurrence.path().contains("loop-") && isTransientWrite(hop)) return VersionKind.LOOP_BACKEDGE;
		if(occurrence.path().contains("loop-") && isTransientRead(hop)) return VersionKind.LOOP_HEAD_PHI;
		if(isTransientRead(hop) && priorDefinitions != null && priorDefinitions.size() > 1)
			return VersionKind.BRANCH_JOIN_PHI;
		return VersionKind.ORDINARY;
	}
	private static String lexicalVariable(Hop h, int ordinal) {
		return h instanceof DataOp && h.getName() != null && !h.getName().isBlank() ? h.getName() : "value-" + ordinal;
	}
	private static NodeKind nodeKind(Hop h, ValueVersionKey value) {
		if(value.versionKind() == VersionKind.FUNCTION_INPUT) return NodeKind.FUNCTION_INPUT;
		if(value.versionKind() == VersionKind.LOOP_HEAD_PHI || value.versionKind() == VersionKind.LOOP_BACKEDGE)
			return NodeKind.LOOP_PHI;
		if(value.versionKind() == VersionKind.BRANCH_JOIN_PHI) return NodeKind.BRANCH_JOIN;
		if(isTransientRead(h)) return NodeKind.TRANSIENT_READ;
		if(isTransientWrite(h)) return NodeKind.TRANSIENT_WRITE;
		if(isFunctionOutput(h)) return NodeKind.FUNCTION_OUTPUT;
		if(h instanceof FunctionOp) return NodeKind.FUNCTION_CALL;
		return NodeKind.OPERATION;
	}
	private static String structuralFingerprint(List<PlacementGraphFingerprint.HopOccurrence> hops) {
		List<String> rows = new ArrayList<>();
		for(PlacementGraphFingerprint.HopOccurrence h : hops)
			rows.add(h.namespace() + '|' + h.path() + '|' + PlacementGraphFingerprint.structuralKey(h.hop()));
		Collections.sort(rows);
		return PlacementGraphFingerprint.sha256(String.join("\n", rows));
	}
}
