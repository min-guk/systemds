/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.FunctionOp;

/**
 * Test-owned source graph coordinates for comparing two independently exported plan spaces.
 * Numeric HOP IDs are used only to join the frozen snapshot to an analysis in memory. They
 * never appear in a physical identity row. A missing or ambiguous join is an adapter error.
 */
public final class PlanSpaceComparisonIdentity {
	private static final ObjectMapper SORT_KEY_MAPPER = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	/** Stable ordering key for nested structural JSON; Map.toString() follows unspecified map iteration order. */
	public static String structuralSortKey(Object value) {
		try {
			return SORT_KEY_MAPPER.writeValueAsString(value);
		}
		catch(JsonProcessingException ex) {
			throw new IllegalArgumentException("Structural identity cannot be serialized", ex);
		}
	}
	record StructuralCallSiteKey(String callSitePath, String recompileContext) {
		StructuralCallSiteKey {
			Objects.requireNonNull(callSitePath);
			Objects.requireNonNull(recompileContext);
		}
	}
	private final Map<CompiledHopKey,String> occurrences;
	private final Map<CompiledHopKey,Map<String,Object>> occurrenceDetails;
	private final Map<CompiledHopKey,Map<String,Object>> nodesByKey;
	private final List<Map<String,Object>> nodes;
	private final List<Map<String,Object>> orderedInputs;
	private final List<Map<String,Object>> logicalInputs;

	private PlanSpaceComparisonIdentity(Map<CompiledHopKey,String> occurrences,
		Map<CompiledHopKey,Map<String,Object>> occurrenceDetails,
		Map<CompiledHopKey,Map<String,Object>> nodesByKey,
		List<Map<String,Object>> nodes, List<Map<String,Object>> orderedInputs,
		List<Map<String,Object>> logicalInputs) {
		this.occurrences = Map.copyOf(occurrences);
		this.occurrenceDetails = Map.copyOf(occurrenceDetails);
		this.nodesByKey = Map.copyOf(nodesByKey);
		this.nodes = List.copyOf(nodes);
		this.orderedInputs = List.copyOf(orderedInputs);
		this.logicalInputs = List.copyOf(logicalInputs);
	}
	public Map<String,Object> occurrenceDetails(CompiledHopKey key) {
		Map<String,Object> result = occurrenceDetails.get(Objects.requireNonNull(key));
		if(result == null) throw new IllegalArgumentException("Unresolved source occurrence: " + key);
		return result;
	}
	public Map<String,Object> nodeFor(CompiledHopKey key) {
		Map<String,Object> result = nodesByKey.get(Objects.requireNonNull(key));
		if(result == null) throw new IllegalArgumentException("Unresolved graph node: " + key);
		return result;
	}

	public String occurrence(CompiledHopKey key) {
		String result = occurrences.get(Objects.requireNonNull(key));
		if(result == null) throw new IllegalArgumentException("Unresolved source occurrence: " + key);
		return result;
	}

	/** Resolve a CFG function-return reference through the compiler's exact boundary constraint. */
	public static CompiledHopKey cfgFunctionOutput(PlacementAnalysis analysis, CompiledHopKey consumer,
		String reference) {
		if(!reference.startsWith("cfg-function-output:"))
			throw new IllegalArgumentException("Not a CFG function output reference");
		String[] parts = reference.split(":", 4);
		if(parts.length != 4) throw new IllegalArgumentException("Malformed CFG function output: " + reference);
		int callOrdinal, position;
		try {
			callOrdinal = Integer.parseInt(parts[1]);
			position = Integer.parseInt(parts[2]);
		}
		catch(NumberFormatException error) {
			throw new IllegalArgumentException("Malformed CFG function output: " + reference, error);
		}
		if(callOrdinal < 0 || position < 0)
			throw new IllegalArgumentException("Malformed CFG function output: " + reference);
		List<CompiledHopKey> matches = analysis.graph().constraints().stream()
			.filter(constraint -> constraint.right() == consumer
				&& constraint.evidence().equals("cfg-function-output-value:" + parts[3])
				&& analysis.graph().node(constraint.left()).orElseThrow().kind()
					== NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT
				&& constraint.left().callSitePath().endsWith("/output-" + position)
				&& constraint.left().emittedHopInstance().equals(
					"output-" + callOrdinal + '-' + position))
			.map(NeutralPlacementGraph.Constraint::left).distinct().toList();
		if(matches.size() != 1)
			throw new IllegalArgumentException("Ambiguous CFG function output source: " + reference);
		return matches.get(0);
	}

	/** Each row uses only JSON scalars, lists, and maps; rows are in source-path order. */
	public List<Map<String,Object>> nodes() { return nodes; }
	/** Includes scalar and repeated edges; the inputPosition is never deduplicated. */
	public List<Map<String,Object>> orderedInputs() { return orderedInputs; }
	/** Native CFG/function binding facts, separate from parser HOP input edges. */
	public List<Map<String,Object>> logicalInputs() { return logicalInputs; }
	/** The same native logical facts with complete source-coordinate endpoints for physical rows. */
	public List<Map<String,Object>> physicalLogicalInputs() {
		Map<String,Map<String,Object>> byPath = new HashMap<>();
		Map<String,Boolean> emittedByPath = new HashMap<>();
		for(Map<String,Object> details : occurrenceDetails.values()) {
			String path = (String) details.get("sourcePath");
			if(byPath.putIfAbsent(path, fullOccurrence(details)) != null)
				throw new IllegalArgumentException("Ambiguous physical logical endpoint");
		}
		for(Map<String,Object> node : nodes) {
			String path = (String) node.get("occurrence");
			Boolean emitted = (Boolean) node.get("emittedWork");
			if(emitted == null || emittedByPath.putIfAbsent(path, emitted) != null)
				throw new IllegalArgumentException("Ambiguous logical endpoint emission status");
		}
		List<Map<String,Object>> result = new ArrayList<>();
		for(Map<String,Object> fact : logicalInputs) {
			String kind = (String) fact.get("kind");
			int position = (Integer) fact.get("position");
			if(position < 0) throw new IllegalArgumentException("Negative logical input position");
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("kind", kind);
			row.put("source", requiredEndpoint(byPath, fact.get("source")));
			row.put("target", requiredEndpoint(byPath, fact.get("target")));
			row.put("position", position);
			if(kind.equals("FUNCTION_INPUT")) {
				int callPosition = (Integer) fact.get("callInputPosition");
				if(callPosition < 0) throw new IllegalArgumentException("Negative function call input position");
				row.put("boundary", requiredEndpoint(byPath, fact.get("boundary")));
				row.put("callInputPosition", callPosition);
			}
			else if(kind.equals("INLINED_FUNCTION_INPUT")) {
				String callSite = (String) fact.get("callSite");
				String functionKey = (String) fact.get("functionKey");
				String formal = (String) fact.get("formal");
				Object actual = fact.get("actual");
				Object bound = fact.get("bound");
				int statementPosition = (Integer) fact.get("statementPosition");
				if(callSite == null || !callSite.startsWith("block/") || functionKey == null
					|| functionKey.isBlank() || formal == null || formal.isBlank()
					|| actual == null
					|| !Map.of("kind", "FORMAL_BINDING", "position", position,
						"formal", formal).equals(bound)
					|| statementPosition < 0)
					throw new IllegalArgumentException("Incomplete frozen inlined function input");
				if(!Boolean.TRUE.equals(emittedByPath.get(fact.get("source"))))
					throw new IllegalArgumentException("Inlined function input source is not emitted");
				Boolean targetEmitted = emittedByPath.get(fact.get("target"));
				if(targetEmitted == null)
					throw new IllegalArgumentException("Inlined function input target emission unresolved");
				row.put("callSite", callSite);
				row.put("functionKey", functionKey);
				row.put("statementPosition", statementPosition);
				row.put("formal", formal);
				if(actual instanceof String name && !name.isBlank()) row.put("actual", name);
				else if(actual instanceof Map<?,?> expression
					&& expression.size() == 2 && "EXPRESSION".equals(expression.get("kind"))
					&& Objects.equals(expression.get("source"), fact.get("source")))
					row.put("actual", Map.of("kind", "EXPRESSION",
						"source", requiredEndpoint(byPath, expression.get("source"))));
				else throw new IllegalArgumentException("Opaque inlined actual argument");
				row.put("bound", bound);
				row.put("targetEmitted", targetEmitted);
			}
			else if(!kind.equals("TRANSIENT"))
				throw new IllegalArgumentException("Unknown logical input fact kind: " + kind);
			result.add(Map.copyOf(row));
		}
		result.sort(Comparator.comparing(PlanSpaceComparisonIdentity::logicalInputOrder));
		if(new HashSet<>(result).size() != result.size())
			throw new IllegalArgumentException("Duplicate physical logical input fact");
		return List.copyOf(result);
	}

	private static Map<String,Object> requiredEndpoint(Map<String,Map<String,Object>> byPath,
		Object path) {
		Map<String,Object> endpoint = byPath.get(path);
		if(endpoint == null) throw new IllegalArgumentException("Unresolved physical logical endpoint");
		return endpoint;
	}

	private static String logicalInputOrder(Map<String,Object> row) {
		@SuppressWarnings("unchecked")
		Map<String,Object> source = (Map<String,Object>) row.get("source");
		@SuppressWarnings("unchecked")
		Map<String,Object> target = (Map<String,Object>) row.get("target");
		return row.get("kind") + "|" + source.get("sourceOrigin") + "|"
			+ target.get("sourceOrigin") + "|" + row.get("position") + "|"
			+ row.getOrDefault("callInputPosition", "") + "|"
			+ (row.containsKey("boundary") ? ((Map<?,?>) row.get("boundary")).get("sourceOrigin") : "");
	}

	private static Map<String,Object> fullOccurrence(Map<String,Object> detail) {
		String path = (String) detail.get("sourcePath");
		String namespace = (String) detail.get("functionNamespace");
		String call = (String) detail.get("callSitePath");
		String recompile = (String) detail.get("recompileContext");
		Object regions = detail.get("controlRegion");
		if(path == null || path.isBlank() || namespace == null || namespace.isBlank()
			|| call == null || call.isBlank() || recompile == null || recompile.isBlank()
			|| !(regions instanceof List<?> regionPath) || regionPath.isEmpty())
			throw new IllegalArgumentException("Incomplete physical logical occurrence");
		Map<String,Object> region = Map.of("functionNamespace", namespace,
			"regionPath", regionPath, "callSitePath", call,
			"recompileContext", recompile);
		return Map.of("sourceOrigin", path, "functionNamespace", namespace,
			"callSitePath", call, "recompileContext", recompile,
			"emittedInstance", path, "controlRegion", region);
	}

	public static PlanSpaceComparisonIdentity from(PlacementAnalysis analysis,
		PrebuilderSnapshot snapshot) {
		Objects.requireNonNull(analysis);
		Objects.requireNonNull(snapshot);
		Map<Long,List<PrebuilderSnapshot.Edge>> byParent = new HashMap<>();
		for(PrebuilderSnapshot.Edge edge : snapshot.edges()) {
			if(!snapshot.nodes().containsKey(edge.parentId()) || !snapshot.nodes().containsKey(edge.childId()))
				throw new IllegalArgumentException("Snapshot edge has unresolved endpoint");
			byParent.computeIfAbsent(edge.parentId(), ignored -> new ArrayList<>()).add(edge);
		}
		for(List<PrebuilderSnapshot.Edge> edges : byParent.values()) {
			edges.sort(Comparator.comparingInt(PrebuilderSnapshot.Edge::inputIndex));
			for(int i = 0; i < edges.size(); i++)
				if(edges.get(i).inputIndex() != i)
					throw new IllegalArgumentException("Snapshot input positions are not contiguous");
		}
		Map<Long,String> paths = new HashMap<>();
		// Normal AST roots first. Function-call output roots are resolved through the call path.
		for(PrebuilderSnapshot.Root root : snapshot.roots())
			if(!root.blockPath().startsWith("call/"))
				walk(root.hopId(), "block/" + root.blockPath() + "/" + root.role() + "/"
					+ root.index(), byParent, paths, new HashSet<>());
		for(PrebuilderSnapshot.Root root : snapshot.roots())
			if(root.blockPath().startsWith("call/")) {
				long callId;
				try { callId = Long.parseLong(root.blockPath().substring("call/".length())); }
				catch(NumberFormatException ex) {
					throw new IllegalArgumentException("Opaque call output root", ex);
				}
				String callPath = paths.get(callId);
				if(callPath == null) throw new IllegalArgumentException("Unresolved call output root");
				walk(root.hopId(), callPath + "/output/" + root.index(), byParent, paths,
					new HashSet<>());
			}
		if(paths.size() != snapshot.nodes().size())
			throw new IllegalArgumentException("Snapshot contains unreachable source nodes");
		if(new HashSet<>(paths.values()).size() != paths.size())
			throw new IllegalArgumentException("Two source HOPs share a structural path");

		Map<CompiledHopKey,String> occurrencePaths = new LinkedHashMap<>();
		Map<Long,CompiledHopKey> compiledByHopId = new HashMap<>();
		for(PlacementAnalysis.HopOccurrenceProjection projection : analysis.compiledHopOccurrences()) {
			long hopId = projection.hop().getHopID();
			String path = paths.get(hopId);
			if(path == null) throw new IllegalArgumentException("Unresolved compiled source HOP " + hopId);
			PrebuilderSnapshot.Node source = snapshot.nodes().get(hopId);
			if(source == null || !source.operation().equals(projection.hop().getOpString())
				|| !source.name().equals(projection.hop().getName()))
				throw new IllegalArgumentException("Compiled source changed after snapshot: " + path);
			CompiledHopKey prior = compiledByHopId.putIfAbsent(hopId, projection.key());
			if(prior != null && !prior.equals(projection.key()))
				throw new IllegalArgumentException("Ambiguous compiled occurrences for " + path);
			String old = occurrencePaths.putIfAbsent(projection.key(), path);
			if(old != null && !old.equals(path))
				throw new IllegalArgumentException("Compiled occurrence has two source paths");
		}
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
			if(!occurrencePaths.containsKey(node.key()))
				occurrencePaths.put(node.key(), syntheticPath(node, analysis, snapshot,
					occurrencePaths));
		Map<String,String> callPathsBySignature = new HashMap<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
			callPathsBySignature.put(node.key().normalizedSignature(), occurrencePaths.get(node.key()));
		Map<CompiledHopKey,Map<String,Object>> details = new LinkedHashMap<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes()) {
			CompiledHopKey key = node.key();
			String context = key.recompileContext();
			if(context.startsWith("callsite:")) {
				String source = callPathsBySignature.get(context.substring("callsite:".length()));
				if(source == null) throw new IllegalArgumentException("Opaque recompile/call context");
				context = "callsite:" + source;
			}
			else if(!context.equals("compiled") && !context.equals("recompile"))
				throw new IllegalArgumentException("Opaque recompile context: " + context);
			String callSite = key.callSitePath();
			int arrow = callSite.indexOf("->");
			if(arrow >= 0) {
				String sourcePath = occurrencePaths.get(key);
				int boundary = sourcePath.indexOf("/boundary/");
				if(boundary < 0)
					throw new IllegalArgumentException("Unresolved boundary callsite");
				callSite = sourcePath.substring(0, boundary);
			}
			else callSite = "block/" + callSite;
			Map<String,Object> coordinate = new LinkedHashMap<>();
			coordinate.put("sourcePath", occurrencePaths.get(key));
			coordinate.put("functionNamespace", key.functionNamespace());
			coordinate.put("callSitePath", callSite);
			coordinate.put("recompileContext", context);
			coordinate.put("controlRegion", key.controlRegion().regionPath().stream()
				.map(region -> region.startsWith("main") || region.startsWith("function/")
					? "block/" + region : region).toList());
			details.put(key, Map.copyOf(coordinate));
		}
		Map<StructuralCallSiteKey,String> structuralCallSites = structuralCallSites(details);
		Map<String,Map<String,Object>> valuesByReference = new HashMap<>();
		Map<String,Map<String,Object>> functionOutputs = new HashMap<>();
		Set<String> referencedValues = new HashSet<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes())
			for(String reference : node.valueVersion().predecessorVersions()) {
				String target = valueReferenceTarget(reference);
				if(target != null) referencedValues.add(target);
			}
		for(NeutralPlacementGraph.Node target : analysis.graph().nodes())
			for(String reference : target.valueVersion().predecessorVersions()) {
				if(!reference.startsWith("cfg-function-output:")) continue;
				String[] parts = reference.split(":", 4);
				CompiledHopKey source = cfgFunctionOutput(analysis, target.key(), reference);
				Map<String,Object> output = Map.of("role", "functionOutput", "occurrence",
					occurrencePaths.get(source), "position", Integer.parseInt(parts[2]), "variable", parts[3]);
				Map<String,Object> prior = functionOutputs.putIfAbsent(reference, output);
				if(prior != null && !prior.equals(output))
					throw new IllegalArgumentException("CFG function output source changed: " + reference);
			}
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes()) {
			ValueVersionKey value = node.valueVersion();
			if(!referencedValues.contains(value.cfgReferenceSignature())) continue;
			Map<String,Object> coordinate = baseValueVersion(value, callPathsBySignature,
				structuralCallSites);
			Map<String,Object> prior = valuesByReference.putIfAbsent(value.cfgReferenceSignature(), coordinate);
			if(prior != null && !prior.equals(coordinate))
				throw new IllegalArgumentException("Ambiguous value-version predecessor reference");
		}
		List<Map<String,Object>> nodes = new ArrayList<>();
		Map<CompiledHopKey,Map<String,Object>> nodesByKey = new LinkedHashMap<>();
		for(NeutralPlacementGraph.Node node : analysis.graph().nodes()) {
			String path = occurrencePaths.get(node.key());
			PrebuilderSnapshot.Node source = analysis.compiledHopOccurrences().stream()
				.filter(p -> p.key().equals(node.key())).findFirst()
				.map(p -> snapshot.nodes().get(p.hop().getHopID())).orElse(null);
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("occurrence", path);
			row.put("kind", node.kind().name());
			row.put("emittedWork", node.emittedWork());
			row.put("operation", source == null ? node.kind().name() : source.operation());
			row.put("name", source == null ? node.valueVersion().lexicalVariable() : source.name());
			row.put("dataType", source == null ? "BOUNDARY" : source.dataType());
			row.put("valueType", source == null ? "BOUNDARY" : source.valueType());
			row.put("rows", source == null ? -1L : source.rows());
			row.put("columns", source == null ? -1L : source.columns());
			row.put("requiresRecompile", source != null && source.requiresRecompile());
			if(source != null && source.externalSource() != null) {
				PrebuilderSnapshot.ExternalSource external = source.externalSource();
				row.put("externalSource", Map.of("origin", external.origin(), "privacy",
					external.privacy(), "partition", external.partition()));
			}
			row.put("valueVersion", valueVersion(node.valueVersion(), valuesByReference,
				callPathsBySignature, structuralCallSites, functionOutputs, snapshot));
			Map<String,Object> frozen = Map.copyOf(row);
			nodes.add(frozen);
			nodesByKey.put(node.key(), frozen);
		}
		nodes.sort(Comparator.comparing(row -> (String) row.get("occurrence")));
		Set<String> seenPaths = new HashSet<>();
		for(Map<String,Object> row : nodes)
			if(!seenPaths.add((String) row.get("occurrence")))
				throw new IllegalArgumentException("Two compiled nodes share a source path");

		List<Map<String,Object>> inputs = new ArrayList<>();
		for(PrebuilderSnapshot.Edge edge : snapshot.edges()) {
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("consumer", paths.get(edge.parentId()));
			row.put("inputPosition", edge.inputIndex());
			row.put("producer", paths.get(edge.childId()));
			inputs.add(Map.copyOf(row));
		}
		inputs.sort(Comparator.comparing((Map<String,Object> row) -> (String) row.get("consumer"))
			.thenComparingInt(row -> (Integer) row.get("inputPosition")));
		Set<String> inputSlots = new HashSet<>();
		for(Map<String,Object> input : inputs)
			if(!inputSlots.add(input.get("consumer") + "/" + input.get("inputPosition")))
				throw new IllegalArgumentException("Two source edges share one ordered input slot");
		for(PlacementAnalysis.CompiledInputEdgeFact edge : analysis.compiledInputEdgesInCanonicalOrder()) {
			String consumer = occurrencePaths.get(edge.consumer());
			String producer = occurrencePaths.get(edge.producer());
			if(consumer == null || producer == null || inputs.stream().noneMatch(row ->
				row.get("consumer").equals(consumer) && row.get("producer").equals(producer)
					&& row.get("inputPosition").equals(edge.inputPosition())))
				throw new IllegalArgumentException("Compiled input edge disagrees with source snapshot");
		}
		List<Map<String,Object>> logicalInputs = new ArrayList<>();
		for(PlacementAnalysis.LogicalTransientInputFact fact :
			analysis.logicalTransientInputsInCanonicalOrder()) {
			String sourcePath = occurrencePaths.get(fact.sourceWrite());
			String targetPath = occurrencePaths.get(fact.targetRead());
			if(sourcePath == null || targetPath == null)
				throw new IllegalArgumentException("Unresolved logical transient endpoint");
			logicalInputs.add(Map.of("kind", "TRANSIENT", "source", sourcePath,
				"target", targetPath, "position", fact.logicalPosition()));
		}
		for(PlacementAnalysis.LogicalFunctionInputFact fact :
			analysis.logicalFunctionInputsInCanonicalOrder()) {
			String sourcePath = occurrencePaths.get(fact.sourceArgument());
			String boundaryPath = occurrencePaths.get(fact.boundary());
			String targetPath = occurrencePaths.get(fact.targetRead());
			if(sourcePath == null || boundaryPath == null || targetPath == null)
				throw new IllegalArgumentException("Unresolved logical function input endpoint");
			logicalInputs.add(Map.of("kind", "FUNCTION_INPUT", "source", sourcePath,
				"boundary", boundaryPath, "target", targetPath,
				"callInputPosition", fact.callInputPosition(),
				"position", fact.logicalPosition()));
		}
		for(PrebuilderSnapshot.InlinedCall call : snapshot.inlinedCalls()) {
			List<PrebuilderSnapshot.Function> definitions = snapshot.functions().stream()
				.filter(function -> call.functionKey().equals(function.key())
					|| call.functionKey().equals(function.name())
					|| call.functionKey().endsWith("::" + function.name())).toList();
			if(definitions.size() > 1)
				throw new IllegalArgumentException("Inlined call lacks a unique source function definition");
			List<String> formalInputs = definitions.isEmpty()
				? removedInlinedFunctionInputs(call) : definitions.get(0).inputs();
			if(call.statementPosition() < 0)
				throw new IllegalArgumentException("Negative inlined call statement position");
			String callSite = "block/" + call.blockPath() + "/inlined-call/"
				+ call.statementPosition() + "/" + call.functionKey();
			for(PrebuilderSnapshot.InputBoundary input : call.inputs()) {
				if(input.position() < 0 || input.statementPosition() < 0
					|| input.formal() == null || input.formal().isBlank()
					|| input.bound() == null || input.bound().isBlank()
					|| input.position() >= formalInputs.size()
					|| !formalInputs.get(input.position()).equals(input.formal()))
					throw new IllegalArgumentException("Incomplete inlined function input boundary");
				String boundaryPath = callSite + "/boundary/input/" + input.position();
				List<NeutralPlacementGraph.Node> targetMatches = analysis.graph().nodes().stream()
					.filter(node -> node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT)
					.filter(node -> boundaryPath.equals(occurrencePaths.get(node.key()))).toList();
				if(targetMatches.size() != 1)
					throw new IllegalArgumentException("Inlined function input boundary is not unique");
				CompiledHopKey boundary = targetMatches.get(0).key();
				PlacementAnalysis.LogicalInlinedFunctionInputFact fact = analysis
					.requireExactLogicalInlinedFunctionInput(boundary, input.position());
				// Compiler-owned absence proves that the rewrite eliminated the argument's
				// physical occurrence. Only this explicit outcome may omit the logical edge.
				if(fact.sourceArgument().isEmpty()) continue;
				CompiledHopKey source = fact.sourceArgument().orElseThrow();
				String sourcePath = occurrencePaths.get(source);
				if(sourcePath == null)
					throw new IllegalArgumentException("Inlined function input source is unresolved");
				Object actual = input.actual() != null && !input.actual().isBlank() ? input.actual()
					: Map.of("kind", "EXPRESSION", "source", sourcePath);
				logicalInputs.add(Map.of("kind", "INLINED_FUNCTION_INPUT",
					"source", sourcePath, "target", occurrencePaths.get(boundary),
					"callSite", callSite, "functionKey", call.functionKey(),
					"position", input.position(), "statementPosition", input.statementPosition(),
					"formal", input.formal(), "actual", actual,
					"bound", Map.of("kind", "FORMAL_BINDING", "position", input.position(),
						"formal", input.formal())));
			}
		}
		logicalInputs.sort(Comparator.comparing(PlanSpaceComparisonIdentity::structuralSortKey));
		if(new HashSet<>(logicalInputs).size() != logicalInputs.size())
			throw new IllegalArgumentException("Duplicate logical input fact");
		return new PlanSpaceComparisonIdentity(occurrencePaths, details, nodesByKey, nodes, inputs,
			logicalInputs);
	}

	private static List<String> removedInlinedFunctionInputs(PrebuilderSnapshot.InlinedCall call) {
		if(call.functionKey() == null || call.functionKey().isBlank() || call.inputs() == null
			|| call.outputs() == null)
			throw new IllegalArgumentException("Inlined call lacks a source function identity");
		List<String> inputs = new ArrayList<>();
		Set<String> inputFormals = new HashSet<>();
		for(int position = 0; position < call.inputs().size(); position++) {
			PrebuilderSnapshot.InputBoundary input = call.inputs().get(position);
			if(input == null || input.position() != position || input.formal() == null
				|| input.formal().isBlank() || !inputFormals.add(input.formal()))
				throw new IllegalArgumentException("Incomplete removed inlined function signature");
			inputs.add(input.formal());
		}
		Set<Integer> outputPositions = new HashSet<>();
		Set<String> outputFormals = new HashSet<>();
		for(PrebuilderSnapshot.OutputBoundary output : call.outputs())
			if(output == null || output.position() < 0 || output.formal() == null
				|| output.formal().isBlank() || !outputPositions.add(output.position())
				|| !outputFormals.add(output.formal()))
				throw new IllegalArgumentException("Incomplete removed inlined function signature");
		return List.copyOf(inputs);
	}

	private static Map<String,Object> valueVersion(ValueVersionKey value,
		Map<String,Map<String,Object>> valuesByReference, Map<String,String> callPathsBySignature,
		Map<StructuralCallSiteKey,String> structuralCallSites,
		Map<String,Map<String,Object>> functionOutputs,
		PrebuilderSnapshot snapshot) {
		List<Map<String,Object>> predecessors = new ArrayList<>();
		for(String reference : value.predecessorVersions()) {
			String role = "value";
			int inputPosition = -1;
			String target = reference;
			if(reference.startsWith("input-")) {
				int colon = reference.indexOf(':');
				if(colon < 0) throw new IllegalArgumentException("Opaque input lineage: " + reference);
				try { inputPosition = Integer.parseInt(reference.substring(6, colon)); }
				catch(NumberFormatException ex) {
					throw new IllegalArgumentException("Opaque input lineage: " + reference, ex);
				}
				target = reference.substring(colon + 1);
				role = "input";
			}
			else if(reference.startsWith("cfg-definition:")) {
				target = reference.substring("cfg-definition:".length());
				role = "cfgDefinition";
			}
			else if(reference.startsWith("callsite:")) {
				String callPath = callPathsBySignature.get(reference.substring("callsite:".length()));
				if(callPath == null) throw new IllegalArgumentException("Opaque call lineage: " + reference);
				predecessors.add(Map.of("role", "callsite", "occurrence", callPath));
				continue;
			}
			else if(reference.startsWith("cfg-function-input:")) {
				String binding = reference.substring("cfg-function-input:".length());
				int colon = binding.lastIndexOf(':');
				if(colon < 0) throw new IllegalArgumentException("Opaque function input lineage: " + reference);
				String namespace = binding.substring(0, colon);
				String variable = binding.substring(colon + 1);
				List<Map<String,Object>> matches = snapshot.functions().stream()
					.filter(function -> namespace.equals(function.name())
						|| namespace.equals(function.key())
						|| namespace.endsWith("::" + function.name()))
					.filter(function -> function.inputs().contains(variable))
					.map(function -> Map.<String,Object>of("role", "functionInput",
						"function", function.key(), "position", function.inputs().indexOf(variable)))
					.toList();
				if(matches.size() != 1)
					throw new IllegalArgumentException("Unresolved function input lineage: " + reference);
				predecessors.add(matches.get(0));
				continue;
			}
			else if(reference.startsWith("cfg-function-output:")) {
				Map<String,Object> output = functionOutputs.get(reference);
				if(output == null)
					throw new IllegalArgumentException("Unresolved CFG function output lineage: " + reference);
				predecessors.add(output);
				continue;
			}
			Map<String,Object> predecessor = valuesByReference.get(target);
			if(predecessor == null)
				throw new IllegalArgumentException("Opaque predecessor lineage: " + reference);
			Map<String,Object> edge = new LinkedHashMap<>();
			edge.put("role", role);
			if(inputPosition >= 0) edge.put("inputPosition", inputPosition);
			edge.put("value", predecessor);
			predecessors.add(Map.copyOf(edge));
		}
		Map<String,Object> row = new LinkedHashMap<>(baseValueVersion(value, callPathsBySignature,
			structuralCallSites));
		row.put("predecessors", List.copyOf(predecessors));
		return Map.copyOf(row);
	}

	private static String valueReferenceTarget(String reference) {
		if(reference.startsWith("input-")) {
			int colon = reference.indexOf(':');
			return colon < 0 ? null : reference.substring(colon + 1);
		}
		if(reference.startsWith("cfg-definition:"))
			return reference.substring("cfg-definition:".length());
		if(reference.startsWith("callsite:") || reference.startsWith("cfg-function-input:")
			|| reference.startsWith("cfg-function-output:"))
			return null;
		return reference;
	}

	private static String syntheticPath(NeutralPlacementGraph.Node node, PlacementAnalysis analysis,
		PrebuilderSnapshot snapshot, Map<CompiledHopKey,String> occurrencePaths) {
		if(node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_CALL) {
			List<PrebuilderSnapshot.InlinedCall> matches = snapshot.inlinedCalls().stream()
				.filter(call -> sameCompilerBlockPath(call.blockPath(), node.key().callSitePath())).toList();
			if(matches.size() == 1)
				return "block/" + matches.get(0).blockPath() + "/inlined-call/"
					+ matches.get(0).statementPosition() + "/" + matches.get(0).functionKey();
			throw new IllegalArgumentException("Unresolved function call occurrence: "
				+ node.key().callSitePath());
		}
		if(node.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_INPUT
			&& node.kind() != NeutralPlacementGraph.NodeKind.FUNCTION_OUTPUT)
			throw new IllegalArgumentException("Graph node has no source occurrence: " + node.kind());
		String path = node.key().callSitePath();
		int delimiter = path.indexOf("->");
		String direction = node.kind() == NeutralPlacementGraph.NodeKind.FUNCTION_INPUT ? "input" : "output";
		int suffix = path.lastIndexOf('/');
		if(delimiter < 0 || suffix < 0 || !path.substring(suffix + 1).matches(direction + "-[0-9]+"))
			throw new IllegalArgumentException("Opaque function boundary path: " + path);
		String callPath = path.substring(0, delimiter);
		String function = path.substring(delimiter + 2, suffix);
		List<PlacementAnalysis.HopOccurrenceProjection> calls = analysis.compiledHopOccurrences().stream()
			.filter(projection -> projection.hop() instanceof FunctionOp)
			.filter(projection -> projection.key().callSitePath().equals(callPath))
			.filter(projection -> function.equals(((FunctionOp) projection.hop()).getFunctionName())
				|| function.endsWith("::" + ((FunctionOp) projection.hop()).getFunctionName()))
			.toList();
		String context = node.key().recompileContext();
		if(context.startsWith("callsite:"))
			calls = calls.stream().filter(projection -> context.equals(
				"callsite:" + projection.key().normalizedSignature())).toList();
		if(calls.size() == 1)
			return occurrencePaths.get(calls.get(0).key()) + "/boundary/" + function + "/"
				+ direction + "/" + path.substring(suffix + direction.length() + 2);
		for(PrebuilderSnapshot.InlinedCall call : snapshot.inlinedCalls())
			if(sameCompilerBlockPath(call.blockPath(), callPath)
				&& (function.equals(call.functionKey()) || function.endsWith("::" + call.functionKey())))
				return "block/" + call.blockPath() + "/inlined-call/" + call.statementPosition()
					+ "/" + function + "/boundary/" + direction + "/"
					+ path.substring(suffix + direction.length() + 2);
		throw new IllegalArgumentException("Unresolved function boundary call: " + path);
	}

	/** Parser snapshots name control bodies by syntax; placement keys use CFG role names. */
	static boolean sameCompilerBlockPath(String sourcePath, String compiledPath) {
		Objects.requireNonNull(sourcePath);
		Objects.requireNonNull(compiledPath);
		String[] parts = sourcePath.split("/", -1);
		for(int i = 0; i < parts.length; i++)
			parts[i] = switch(parts[i]) {
				case "while", "for" -> "loop-body";
				case "if" -> "branch-if";
				case "else" -> "branch-else";
				default -> parts[i];
			};
		return String.join("/", parts).equals(compiledPath);
	}

	private static Map<String,Object> baseValueVersion(ValueVersionKey value,
		Map<String,String> callPathsBySignature,
		Map<StructuralCallSiteKey,String> structuralCallSites) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("variable", value.lexicalVariable());
		List<String> path = new ArrayList<>();
		for(String part : value.definingControlRegion().regionPath()) {
			if(path.isEmpty()) {
				if(part.startsWith("main/") || part.startsWith("function/")) part = "block/" + part;
				else if(!part.startsWith("block/"))
					throw new IllegalArgumentException("Opaque predecessor region root: " + part);
			}
			else if(!part.matches("[a-z][a-z0-9_-]*"))
				throw new IllegalArgumentException("Opaque predecessor region member: " + part);
			path.add(part);
		}
		row.put("regionPath", List.copyOf(path));
		row.put("functionNamespace", value.definingControlRegion().functionNamespace());
		String callSite = value.definingControlRegion().callSitePath();
		if(callSite.contains("->")) {
			callSite = structuralCallSites.get(new StructuralCallSiteKey(callSite,
				value.definingControlRegion().recompileContext()));
			if(callSite == null) throw new IllegalArgumentException("Unresolved value-version call site");
		}
		else if(callSite.startsWith("main/") || callSite.startsWith("function/"))
			callSite = "block/" + callSite;
		else if(!callSite.startsWith("block/"))
			throw new IllegalArgumentException("Opaque value-version call site");
		String context = value.definingControlRegion().recompileContext();
		if(context.startsWith("callsite:")) {
			String mapped = callPathsBySignature.get(context.substring("callsite:".length()));
			if(mapped == null) throw new IllegalArgumentException("Opaque value-version call context");
			context = "callsite:" + mapped;
		}
		else if(!context.equals("compiled") && !context.equals("recompile"))
			throw new IllegalArgumentException("Opaque value-version recompile context");
		row.put("callSitePath", callSite);
		row.put("recompileContext", context);
		row.put("definitionOrdinal", value.definitionOrdinal());
		row.put("kind", value.versionKind().name());
		return Map.copyOf(row);
	}

	static Map<StructuralCallSiteKey,String> structuralCallSites(
		Map<CompiledHopKey,Map<String,Object>> details) {
		Map<StructuralCallSiteKey,String> result = new HashMap<>();
		for(Map.Entry<CompiledHopKey,Map<String,Object>> entry : details.entrySet()) {
			CompiledHopKey occurrence = entry.getKey();
			StructuralCallSiteKey key = new StructuralCallSiteKey(occurrence.callSitePath(),
				occurrence.recompileContext());
			String mapped = (String) entry.getValue().get("callSitePath");
			String prior = result.putIfAbsent(key, mapped);
			if(prior != null && !prior.equals(mapped))
				throw new IllegalArgumentException("Ambiguous structural call-site mapping");
		}
		return Map.copyOf(result);
	}


	private static void walk(long hopId, String path,
		Map<Long,List<PrebuilderSnapshot.Edge>> byParent, Map<Long,String> paths, Set<Long> active) {
		if(!active.add(hopId)) throw new IllegalArgumentException("Cycle in source HOP graph");
		String prior = paths.get(hopId);
		if(prior == null || path.compareTo(prior) < 0) paths.put(hopId, path);
		for(PrebuilderSnapshot.Edge edge : byParent.getOrDefault(hopId, List.of()))
			walk(edge.childId(), path + "/in/" + edge.inputIndex(), byParent, paths, active);
		active.remove(hopId);
	}
}
