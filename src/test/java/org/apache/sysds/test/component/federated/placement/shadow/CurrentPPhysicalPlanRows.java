/* Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.test.component.federated.placement.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DerivedFoutMaterializationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.RelocationSelections;
import org.apache.sysds.runtime.controlprogram.federated.FederationUtils;

/** Strict source-coordinate projection of one accepted current-P plan. */
public final class CurrentPPhysicalPlanRows {
	private record PhiSource(CompiledHopKey producer, String controlArm) { }
	private record CfgInput(CompiledHopKey direct, List<PhiSource> alternatives) { }
	private record CfgReference(String signature, boolean definition) { }
	private final PlacementAnalysis analysis;
	private final PlanSpaceComparisonIdentity source;
	private final String logicalIdentity;
	private final Map<String,CompiledHopKey> keysByPath;

	public CurrentPPhysicalPlanRows(PlacementAnalysis analysis, PlanSpaceComparisonIdentity source,
		String logicalIdentity) {
		this.analysis = Objects.requireNonNull(analysis);
		this.source = Objects.requireNonNull(source);
		this.logicalIdentity = requiredString(logicalIdentity, "frozen logical identity");
		keysByPath = new HashMap<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes()) {
			String path = source.occurrence(node.key());
			if(keysByPath.putIfAbsent(path, node.key()) != null)
				throw new IllegalArgumentException("Ambiguous source occurrence: " + path);
		}
	}

	/** No ordinal, native signature, or proof receipt is part of the returned physical key. */
	public Map<String,Object> physicalPlan(FullProductionJointPlanExport.Audit proof) {
		if(proof.verdict() != FullProductionJointPlanExport.Verdict.ACCEPTED)
			throw new IllegalArgumentException("Only accepted plans have a physical identity");
		Map<CompiledHopKey,CandidateSelectionReceipt> selected = new HashMap<>();
		for(CandidateSelectionReceipt receipt : proof.candidates())
			if(selected.putIfAbsent(receipt.rule().parentOccurrence(), receipt) != null)
				throw new IllegalArgumentException("Duplicate selected candidate owner");
		List<Map<String,Object>> nodes = new ArrayList<>();
		List<Map<String,Object>> authorities = new ArrayList<>();
		List<Map<String,Object>> geometry = new ArrayList<>();
		Map<CompiledHopKey,Map<String,Object>> authorityByKey = new HashMap<>();
		for(Map.Entry<CompiledHopKey,PlacementState> entry : proof.assignment().entrySet()) {
			CompiledHopKey key = entry.getKey();
			PlacementState state = entry.getValue();
			CandidateSelectionReceipt receipt = selected.remove(key);
			Map<String,Object> occurrence = occurrence(source, key);
			Map<String,Object> catalogNode = source.nodeFor(key);
			Map<String,Object> authorityId = new LinkedHashMap<>();
			authorityId.put("owner", occurrence);
			String kind = receipt == null ? "SYNTHETIC_BOUNDARY" : "CANDIDATE";
			authorityId.put("kind", kind);
			if(receipt == null) authorityId.put("layout", "BOUNDARY");
			else {
				String layout = receipt.realization().key().layoutKind().name();
				authorityId.put("layout", layout);
				DurableAnchorKey anchor = receipt.realization().provenWorkerPool(receipt.supportClause());
				if(anchor != null) {
					authorityId.put("anchor", anchor(anchor));
					addGeometry(geometry, occurrence, anchor);
				}
				else if(layout.equals("NATIVE_LINEAGE")) {
					DurableAnchorKey witness = receipt.realization()
						.nativeWorkerPoolResidencyWitness(receipt.supportClause());
					if(witness == null)
						throw new IllegalArgumentException(
							"Native layout lacks structural worker authority");
					authorityId.put("workerResidency", workerResidency(witness));
				}
				else if(layout.equals("DURABLE_MAP"))
					throw new IllegalArgumentException("Federated layout lacks exact structural geometry");
				if(layout.equals("SOURCE_LINEAGE")) {
					Object external = catalogNode.get("externalSource");
					if(!(external instanceof Map<?,?>))
						throw new IllegalArgumentException("Source lineage lacks frozen external source");
					authorityId.put("externalSource", external);
				}
			}
			Map<String,Object> frozenId = Map.copyOf(authorityId);
			authorityByKey.put(key, frozenId);
			Map<String,Object> authority = new LinkedHashMap<>();
			authority.put("id", frozenId);
			authority.put("source", kind);
			authority.put("owner", occurrence);
			authority.put("kind", kind);
			authorities.add(Map.copyOf(authority));
			Map<String,Object> node = new LinkedHashMap<>();
			node.put("occurrence", occurrence);
			node.put("opcode", catalogNode.get("operation"));
			node.put("exec", state.execType().name());
			node.put("output", state.output().name());
			node.put("ftype", state.fType() == null ? "NONE" : state.fType().name());
			node.put("shapeDependent", state.shapeDependent());
			node.put("executionFType", receipt == null || receipt.emission().executionFType() == null
				? "NONE" : receipt.emission().executionFType().name());
			node.put("valueVersion", valueVersion(source, key,
				analysis.graph().node(key).orElseThrow().valueVersion()));
			node.put("authorityRef", frozenId);
			nodes.add(Map.copyOf(node));
		}
		if(!selected.isEmpty()) throw new IllegalArgumentException("Candidate owner absent from plan nodes");
		List<Map<String,Object>> bindings = bindings(proof, authorityByKey);
		List<Map<String,Object>> actions = actions(proof, geometry);
		if(proof.assignment().isEmpty()) throw new IllegalArgumentException("Empty plan");
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "physical-plan-v1");
		row.put("context", Map.of("logical", logicalIdentity));
		row.put("nodes", List.copyOf(nodes));
		row.put("actions", actions);
		row.put("bindings", bindings);
		row.put("geometry", uniqueGeometry(geometry));
		row.put("authority", List.copyOf(authorities));
		row.put("logicalInputs", source.physicalLogicalInputs());
		return Map.copyOf(row);
	}

	private static Map<String,Object> workerResidency(DurableAnchorKey witness) {
		List<String> raw = new ArrayList<>();
		for(AnchorPartition partition : witness.partitions()) {
			String endpoint = FederationUtils.canonicalFederatedWorkerAddress(partition.workerId());
			if(endpoint == null || endpoint.isBlank())
				throw new IllegalArgumentException(
					"Native layout has invalid worker endpoint authority");
			raw.add(endpoint);
		}
		List<String> endpoints = raw.stream().distinct().sorted().toList();
		if(endpoints.isEmpty())
			throw new IllegalArgumentException("Native layout has no worker endpoint authority");
		return Map.of("ftype", witness.fType().name(), "endpoints", endpoints,
			"layoutExact", false);
	}

	public static Map<String,Object> occurrence(PlanSpaceComparisonIdentity source, CompiledHopKey key) {
		Map<String,Object> detail = source.occurrenceDetails(key);
		String path = requiredString(detail.get("sourcePath"), "source path");
		String namespace = requiredString(detail.get("functionNamespace"), "function namespace");
		String call = requiredString(detail.get("callSitePath"), "call site");
		String recompile = requiredString(detail.get("recompileContext"), "recompile context");
		Object pathParts = detail.get("controlRegion");
		if(!(pathParts instanceof List<?> region) || region.isEmpty())
			throw new IllegalArgumentException("Missing structural control region");
		Map<String,Object> control = Map.of("functionNamespace", namespace, "regionPath", region,
			"callSitePath", call, "recompileContext", recompile);
		return Map.of("sourceOrigin", path, "functionNamespace", namespace,
			"callSitePath", call, "recompileContext", recompile,
			"emittedInstance", path, "controlRegion", control);
	}

	private static String requiredString(Object value, String label) {
		if(!(value instanceof String text) || text.isBlank())
			throw new IllegalArgumentException("Missing " + label);
		return text;
	}

	/** The version coordinate is source-structural; native provenance strings are never emitted. */
	public static Map<String,Object> valueVersion(PlanSpaceComparisonIdentity source,
		CompiledHopKey owner, ValueVersionKey nativeValue) {
		Map<String,Object> catalogNode = source.nodeFor(owner);
		Object raw = catalogNode.get("valueVersion");
		if(!(raw instanceof Map<?,?> version))
			throw new IllegalArgumentException("Missing structural value version");
		Object rawPath = version.get("regionPath");
		if(!(rawPath instanceof List<?> parts) || parts.isEmpty())
			throw new IllegalArgumentException("Missing version control region path");
		List<String> regionPath = new ArrayList<>();
		for(int i = 0; i < parts.size(); i++) {
			String part = requiredString(parts.get(i), "version region part");
			if(i == 0) {
				if(part.startsWith("main/") || part.startsWith("function/")) part = "block/" + part;
				else if(!part.startsWith("block/"))
					throw new IllegalArgumentException("Opaque version region root: " + part);
			}
			else if(!part.matches("[a-z][a-z0-9_-]*"))
				throw new IllegalArgumentException("Opaque version region member: " + part);
			regionPath.add(part);
		}
		String call = nativeValue.definingControlRegion().callSitePath();
		if(call.contains("->")) call = requiredString(source.occurrenceDetails(owner).get("callSitePath"),
			"version boundary call site");
		else if(call.startsWith("main/") || call.startsWith("function/")) call = "block/" + call;
		else if(!call.startsWith("block/"))
			throw new IllegalArgumentException("Opaque version call site");
		String recompile = nativeValue.definingControlRegion().recompileContext();
		if(recompile.startsWith("callsite:") && recompile.equals(owner.recompileContext())) {
			recompile = requiredString(source.occurrenceDetails(owner).get("recompileContext"),
				"version callsite context");
		}
		else if(!recompile.equals("compiled") && !recompile.equals("recompile"))
			throw new IllegalArgumentException("Opaque version recompile context: " + recompile);
		Map<String,Object> region = Map.of(
			"functionNamespace", nativeValue.definingControlRegion().functionNamespace(),
			"regionPath", regionPath, "callSitePath", call,
			"recompileContext", recompile);
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("lexicalVariable", version.get("variable"));
		row.put("definitionOrdinal", version.get("definitionOrdinal"));
		row.put("versionKind", version.get("kind"));
		row.put("definingControlRegion", region);
		row.put("predecessorVersions", version.get("predecessors"));
		return Map.copyOf(row);
	}

	private List<Map<String,Object>> bindings(FullProductionJointPlanExport.Audit proof,
		Map<CompiledHopKey,Map<String,Object>> authorityByKey) {
		Map<CompiledHopKey,CandidateSelectionReceipt> candidates = new HashMap<>();
		for(CandidateSelectionReceipt receipt : proof.candidates())
			candidates.put(receipt.rule().parentOccurrence(), receipt);
		Map<CompiledHopKey,Map<Integer,CompiledHopKey>> producers = new HashMap<>();
		Map<CompiledHopKey,Map<Integer,CandidateRealizationInputBinding>> support = new HashMap<>();
		Map<CompiledHopKey,Map<Integer,List<PhiSource>>> phiInputs = new HashMap<>();
		for(Map<String,Object> edge : source.orderedInputs()) {
			CompiledHopKey consumer = keysByPath.get(edge.get("consumer"));
			if(consumer == null || !proof.assignment().containsKey(consumer)) continue;
			CompiledHopKey producer = keysByPath.get(edge.get("producer"));
			if(producer == null || !proof.assignment().containsKey(producer))
				throw new IllegalArgumentException("Emitted consumer has unresolved source input");
			int position = (Integer) edge.get("inputPosition");
			if(producers.computeIfAbsent(consumer, ignored -> new HashMap<>())
				.putIfAbsent(position, producer) != null)
				throw new IllegalArgumentException("Duplicate source input slot");
		}
		for(CandidateSelectionReceipt receipt : proof.candidates()) {
			CompiledHopKey consumer = receipt.rule().parentOccurrence();
			for(CandidateRealizationInputBinding binding : receipt.supportClause().inputBindings()) {
				int position = binding.inputPosition();
				if(position >= receipt.rule().orderedInputs().size())
					throw new IllegalArgumentException("Support input position outside selected rule");
				CompiledHopKey producer = binding.source().rule().parentOccurrence();
				if(!proof.assignment().containsKey(producer))
					throw new IllegalArgumentException("Support source is not emitted");
				CompiledHopKey prior = producers.computeIfAbsent(consumer, ignored -> new HashMap<>())
					.putIfAbsent(position, producer);
				if(prior != null && !prior.equals(producer))
					throw new IllegalArgumentException("Support source disagrees with ordered source edge");
				if(support.computeIfAbsent(consumer, ignored -> new HashMap<>())
					.putIfAbsent(position, binding) != null)
					throw new IllegalArgumentException("Two exact support bindings share one input slot");
				CandidateSelectionReceipt sourceReceipt = candidates.get(producer);
				if(sourceReceipt != null && !sourceReceipt.realization().key()
					.equals(binding.source().realization()))
					throw new IllegalArgumentException("Support source realization differs from emitted source");
			}
		}
		for(CandidateSelectionReceipt receipt : proof.candidates()) {
			CompiledHopKey consumer = receipt.rule().parentOccurrence();
			Map<Integer,CompiledHopKey> slots = producers.computeIfAbsent(consumer,
				ignored -> new HashMap<>());
			for(int position = 0; position < receipt.rule().orderedInputs().size(); position++)
				if(!slots.containsKey(position)) {
					CfgInput resolved = cfgPredecessor(consumer, position, proof);
					slots.put(position, resolved.direct() == null ? consumer : resolved.direct());
					if(!resolved.alternatives().isEmpty())
						phiInputs.computeIfAbsent(consumer, ignored -> new HashMap<>())
							.put(position, resolved.alternatives());
				}
		}
		Set<RelocationActionKey> emitted = Set.copyOf(RelocationSelections.emittedActions(analysis,
			analysis.graph().relocationActions(), proof.assignment(), proof.candidates(), proof.relocations()));
		List<Map<String,Object>> result = new ArrayList<>();
		for(Map.Entry<CompiledHopKey,Map<Integer,CompiledHopKey>> consumerRow : producers.entrySet()) {
			CompiledHopKey consumer = consumerRow.getKey();
			CandidateSelectionReceipt receipt = candidates.get(consumer);
			if(receipt != null && consumerRow.getValue().size() != receipt.rule().orderedInputs().size())
				throw new IllegalArgumentException("Selected candidate ordered inputs are not fully represented");
			for(Map.Entry<Integer,CompiledHopKey> edge : consumerRow.getValue().entrySet()) {
				int position = edge.getKey();
				CompiledHopKey producer = edge.getValue();
				List<PhiSource> phi = phiInputs.getOrDefault(consumer, Map.of()).get(position);
				String presence;
				String ftype;
				if(receipt != null) {
					List<CandidateInputState> inputs = receipt.rule().orderedInputs();
					if(position < 0 || position >= inputs.size())
						throw new IllegalArgumentException("Candidate input slot missing");
					CandidateInputState input = inputs.get(position);
					presence = input.presence().name();
					ftype = input.fType() == null ? "NONE" : input.fType().name();
				}
				else {
					presence = "PRESENT";
				PlacementState state = proof.assignment().get(producer);
					ftype = state.fType() == null ? "NONE" : state.fType().name();
				}
				CandidateRealizationInputBinding binding = support.getOrDefault(consumer, Map.of())
					.get(position);
				RelocationActionKey selectedAction = null;
				for(var selected : proof.relocations())
					if(selected.demand().consumer().equals(consumer)
						&& selected.demand().inputPosition() == position) {
						if(selectedAction != null && !selectedAction.equals(selected.action()))
							throw new IllegalArgumentException("Two relocation actions for one input slot");
						selectedAction = selected.action();
					}
				if(phi != null && binding != null)
					throw new IllegalArgumentException("PHI input also has exact support binding");
				if(binding != null && binding.relocationAction() != null
					&& selectedAction != null && !binding.relocationAction().equals(selectedAction))
					throw new IllegalArgumentException("Support relocation action disagrees with selection");
				RelocationActionKey action = selectedAction != null ? selectedAction
					: binding == null ? null : binding.relocationAction();
				String mode;
				if(phi != null) mode = "PHI";
				else if(binding != null && binding.kind().name().equals("LOGICAL_TRANSIENT"))
					mode = "LOGICAL_TRANSIENT";
				else if(action != null && emitted.contains(action)) mode = "RELOCATION";
				else if(presence.equals("ABSENT_LOCAL")) mode = "ABSENT_LOCAL";
				else if(proof.assignment().get(producer).output().name().equals("FOUT"))
					mode = "DIRECT_FOUT";
				else mode = "DIRECT";
				Map<String,Object> row = new LinkedHashMap<>();
				row.put("consumer", occurrence(source, consumer));
				row.put("inputPosition", position);
				row.put("producer", phi == null ? occurrence(source, producer)
					: Map.of("kind", "PHI_JOIN_PORT", "owner", occurrence(source, consumer)));
				row.put("presence", presence);
				row.put("ftype", ftype);
				row.put("inputAuthority", mode);
				if(phi == null) row.put("sourceAuthorityRef", authorityByKey.get(producer));
				else {
					List<Map<String,Object>> alternatives = phi.stream().map(alternative ->
						Map.<String,Object>of("producer", occurrence(source, alternative.producer()),
							"controlArm", alternative.controlArm(),
							"sourceAuthorityRef", authorityByKey.get(alternative.producer())))
						.sorted(Comparator.comparing(value -> (String) value.get("controlArm"))).toList();
					row.put("producerAlternatives", alternatives);
				}
				if(mode.equals("RELOCATION")) row.put("actionRef", relocationId(action));
				result.add(Map.copyOf(row));
			}
		}
		for(CandidateSelectionReceipt receipt : proof.candidates())
			if(receipt.rule().orderedInputs().size() > 0
				&& !producers.containsKey(receipt.rule().parentOccurrence()))
				throw new IllegalArgumentException("Candidate has no represented ordered input authority: "
					+ source.occurrence(receipt.rule().parentOccurrence()) + " inputs="
					+ receipt.rule().orderedInputs().size());
		return List.copyOf(result);
	}

	/** A CFG data read has no HOP input edge; its exact predecessor definition supplies that edge. */
	private CfgInput cfgPredecessor(CompiledHopKey consumer, int inputPosition,
		FullProductionJointPlanExport.Audit proof) {
		ValueVersionKey value = analysis.graph().node(consumer).orElseThrow().valueVersion();
		List<CfgReference> references = new ArrayList<>();
		List<PhiSource> matched = new ArrayList<>();
		for(String predecessor : value.predecessorVersions()) {
			if(inputPosition == 0 && predecessor.startsWith("cfg-definition:"))
				references.add(new CfgReference(
					predecessor.substring("cfg-definition:".length()), true));
			else if(predecessor.startsWith("input-" + inputPosition + ":"))
				references.add(new CfgReference(
					predecessor.substring(("input-" + inputPosition + ":").length()), false));
			else if(inputPosition == 0 && predecessor.startsWith("cfg-function-output:")) {
				CompiledHopKey producer = PlanSpaceComparisonIdentity.cfgFunctionOutput(
					analysis, consumer, predecessor);
				if(!proof.assignment().containsKey(producer))
					throw new IllegalArgumentException("CFG function output is absent from P assignment");
				matched.add(new PhiSource(producer, requiredString(
					source.occurrenceDetails(producer).get("callSitePath"), "CFG control arm")));
			}
			else if(inputPosition == 0 && predecessor.startsWith("cfg-function-input:")) {
				List<Map<String,Object>> inputs = source.logicalInputs().stream()
					.filter(fact -> "FUNCTION_INPUT".equals(fact.get("kind"))
						&& source.occurrence(consumer).equals(fact.get("target")))
					.toList();
				if(inputs.isEmpty())
					throw new IllegalArgumentException("Unresolved CFG function input source");
				for(Map<String,Object> input : inputs) {
					CompiledHopKey producer = keysByPath.get(input.get("source"));
					if(producer == null || !proof.assignment().containsKey(producer))
						throw new IllegalArgumentException("CFG function input is absent from P assignment");
					matched.add(new PhiSource(producer,
						requiredString(input.get("boundary"), "function input boundary")));
				}
			}
		}
		if(references.isEmpty() && matched.isEmpty())
			throw new IllegalArgumentException("Unresolved CFG input authority: "
				+ source.occurrence(consumer) + " slot=" + inputPosition);
		for(CfgReference reference : references) {
			List<CompiledHopKey> matches = analysis.graph().nodes().stream()
				.filter(node -> proof.assignment().containsKey(node.key()))
				.filter(node -> node.valueVersion().cfgReferenceSignature().equals(reference.signature()))
				// A TRead can share the writer's ValueVersionKey. A cfg-definition
				// reference denotes the actual definition, not that aliasing read.
				.filter(node -> !reference.definition() || analysis.hop(node.key())
					.filter(hop -> hop instanceof DataOp data &&
						(data.getOp() == OpOpData.TRANSIENTWRITE ||
							data.getOp() == OpOpData.FUNCTIONOUTPUT)).isPresent())
				.map(NeutralPlacementGraph.Node::key).toList();
			if(matches.size() != 1)
				throw new IllegalArgumentException("Ambiguous CFG input authority: "
					+ source.occurrence(consumer) + " slot=" + inputPosition
					+ " reference=" + reference + " matches=" + matches.stream()
						.map(key -> source.occurrence(key) + ":"
							+ analysis.graph().node(key).orElseThrow().kind()).toList());
			CompiledHopKey producer = matches.get(0);
			String controlArm = requiredString(source.occurrenceDetails(producer).get("callSitePath"),
				"CFG control arm");
			matched.add(new PhiSource(producer, controlArm));
		}
		if(matched.size() == 1) return new CfgInput(matched.get(0).producer(), List.of());
		if(matched.stream().map(PhiSource::controlArm).distinct().count() != matched.size())
			throw new IllegalArgumentException("CFG alternatives share one control arm");
		return new CfgInput(null, List.copyOf(matched));
	}

	private Map<String,Object> relocationId(RelocationActionKey key) {
		List<NeutralPlacementGraph.RelocationAction> graphMatches = analysis.graph().relocationActions()
			.stream().filter(action -> action.key().equals(key)).toList();
		if(graphMatches.size() != 1) throw new IllegalArgumentException("Relocation action is not unique");
		List<CompiledHopKey> owners = analysis.graph().nodes().stream()
			.filter(node -> node.valueVersion().equals(key.sourceValueVersion()))
			.map(NeutralPlacementGraph.Node::key).toList();
		if(owners.size() != 1) throw new IllegalArgumentException("Relocation source is ambiguous");
		Map<String,Object> owner = occurrence(source, owners.get(0));
		List<Map<String,Object>> obligations = graphMatches.get(0).obligations().stream()
			.map(obligation -> Map.<String,Object>of("consumer", occurrence(source, obligation.consumer()),
				"inputPosition", obligation.inputPosition(), "requiredState", state(obligation.requiredPlacement())))
			.sorted(Comparator.comparing(Object::toString)).toList();
		return Map.of("kind", "RELOCATION", "source", owner,
			"sourceValueVersion", valueVersion(source, owners.get(0), key.sourceValueVersion()),
			"target", state(key.targetPlacement()), "anchor", anchor(key.durableAnchor()),
			"obligations", obligations);
	}

	private List<Map<String,Object>> actions(FullProductionJointPlanExport.Audit proof,
		List<Map<String,Object>> geometry) {
		List<Map<String,Object>> result = new ArrayList<>();
		for(RelocationActionKey key : RelocationSelections.emittedActions(analysis,
			analysis.graph().relocationActions(), proof.assignment(), proof.candidates(), proof.relocations())) {
			Map<String,Object> id = relocationId(key);
			@SuppressWarnings("unchecked")
			Map<String,Object> owner = (Map<String,Object>) id.get("source");
			result.add(Map.of("id", id, "kind", "RELOCATION", "owner", owner));
			addGeometry(geometry, owner, key.durableAnchor());
		}
		for(DerivedFoutMaterializationActionKey key : proof.derivedFoutActions()) {
			Map<String,Object> owner = occurrence(source, key.producer());
			Map<String,Object> id = Map.of("kind", "DERIVED_FOUT", "producer", owner,
				"sourceValueVersion", valueVersion(source, key.producer(), key.producerValueVersion()),
				"source", state(key.sourcePlacement()), "target", state(key.targetPlacement()),
				"anchor", anchor(key.durableAnchor()));
			result.add(Map.of("id", id, "kind", "DERIVED_FOUT", "owner", owner));
			addGeometry(geometry, owner, key.durableAnchor());
		}
		return List.copyOf(result);
	}

	public static Map<String,Object> state(PlacementState state) {
		return Map.of("exec", state.execType().name(), "output", state.output().name(),
			"ftype", state.fType() == null ? "NONE" : state.fType().name(),
			"shapeDependent", state.shapeDependent());
	}

	public static Map<String,Object> anchor(DurableAnchorKey anchor) {
		List<Map<String,Object>> partitions = anchor.partitions().stream().map(partition ->
			Map.<String,Object>of("worker", partition.workerId(), "begin", partition.begin(),
				"end", partition.end())).toList();
		return Map.of("ftype", anchor.fType().name(), "partitions", partitions);
	}

	private static void addGeometry(List<Map<String,Object>> rows, Map<String,Object> owner,
		DurableAnchorKey anchor) {
		for(AnchorPartition part : anchor.partitions())
			rows.add(Map.of("owner", owner, "worker", part.workerId(),
				"ranges", List.of(part.begin(), part.end()), "ftype", anchor.fType().name()));
	}

	private static List<Map<String,Object>> uniqueGeometry(List<Map<String,Object>> rows) {
		Set<Map<String,Object>> seen = new HashSet<>();
		List<Map<String,Object>> unique = new ArrayList<>();
		for(Map<String,Object> row : rows) if(seen.add(row)) unique.add(row);
		return List.copyOf(unique);
	}
}
