/* Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. */
package org.apache.sysds.test.component.federated.placement.guard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;

/** Lane-local reflection bridge. It validates raw mutability before copying any result surface. */
final class R4SharedFedAllAdapterBridge {
	enum Planner { FED_ALL, HEURISTIC, DP, MIN_ST }
	record Handle(Planner planner, Object adapter, Method select) { }
	static List<Planner> analysisOnlyPlanners() {
		return List.of(Planner.FED_ALL, Planner.HEURISTIC, Planner.MIN_ST);
	}
	record Score(int fed, int fout, int relocations, String signature) { }
	record Bound(String id, List<CompiledHopKey> nodes, int upperFed, int upperFout,
		int lowerRelocations, String derivation) { }
	record Certificate(String graphFingerprint, String assignmentHash, long explored, long pruned,
		long universe, Score incumbent, Score upperBound, List<Bound> bounds, int graphNodes,
		int graphConstraints, int graphComponents, String boundDerivation, String termination,
		boolean fallbackUsed) { }
	record Selection(PlacementAnalysis analysis, Map<CompiledHopKey,PlacementState> assignment,
		List<RelocationActionKey> relocations, Score score, Certificate certificate) { }

	static Handle open(Planner planner) {
		String simple = switch(planner) {
			case FED_ALL -> "FedAllPlacementAdapter"; case HEURISTIC -> "HeuristicPlacementAdapter";
			case DP -> "DpPlacementAdapter"; case MIN_ST -> "MinStPlacementAdapter";
		};
		String name = "org.apache.sysds.hops.fedplanner.placement.adapter." + simple;
		try {
			Class<?> type = Class.forName(name); Object adapter = type.getConstructor().newInstance();
			Method select = planner == Planner.HEURISTIC ? type.getMethod("select", PlacementAnalysis.class, java.util.Set.class)
				: type.getMethod("select", PlacementAnalysis.class);
			return new Handle(planner, adapter, select);
		}
		catch(ReflectiveOperationException e) {
			throw new AssertionError("CAMPAIGN_B_RUNTIME_ADAPTER_MISSING|planner=" + planner + "|member=" + name);
		}
	}

	static Selection select(Handle handle, PlacementAnalysis analysis) {
		try { return normalize(handle.select().invoke(handle.adapter(), analysis)); }
		catch(InvocationTargetException e) { throw contract(handle.planner(), "invoke", e.getCause()); }
		catch(ReflectiveOperationException | RuntimeException e) { throw contract(handle.planner(), "invoke", e); }
	}

	static Selection select(Handle handle, PlacementAnalysis analysis, java.util.Set<?> noRefed) {
		try { return normalize(handle.select().invoke(handle.adapter(), analysis, java.util.Set.copyOf(noRefed))); }
		catch(InvocationTargetException e) { throw contract(handle.planner(), "invoke", e.getCause()); }
		catch(ReflectiveOperationException | RuntimeException e) { throw contract(handle.planner(), "invoke", e); }
	}

	static Selection normalize(Object raw) throws ReflectiveOperationException {
		if(raw == null) throw new AssertionError("R4_RESULT_NULL");
		PlacementAnalysis analysis = (PlacementAnalysis) call(raw, "analysis");
		Map<?,?> assignmentRaw = (Map<?,?>) call(raw, "assignment");
		List<?> relocationRaw = (List<?>) call(raw, "selectedRelocations");
		assertImmutableMap(assignmentRaw, "assignment"); assertImmutableList(relocationRaw, "relocations");
		Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>();
		for(Map.Entry<?,?> e : assignmentRaw.entrySet()) assignment.put((CompiledHopKey)e.getKey(), (PlacementState)e.getValue());
		List<RelocationActionKey> relocations = new ArrayList<>();
		for(Object r : relocationRaw) relocations.add((RelocationActionKey)r);
		Score score = score(call(raw, "score")); Object proof = call(raw, "certificate");
		List<?> boundRaw = (List<?>) call(proof, "boundComponents"); assertImmutableList(boundRaw, "bounds");
		List<Bound> bounds = new ArrayList<>();
		for(Object b : boundRaw) {
			List<?> nodesRaw = (List<?>) call(b, "nodeKeys"); assertImmutableList(nodesRaw, "bound.nodes");
			List<CompiledHopKey> nodes = new ArrayList<>(); for(Object n : nodesRaw) nodes.add((CompiledHopKey)n);
			bounds.add(new Bound(String.valueOf(call(b,"componentId")), List.copyOf(nodes), integer(b,"upperFed"),
				integer(b,"upperFout"), integer(b,"lowerRelocations"), String.valueOf(call(b,"derivation"))));
		}
		Certificate certificate = new Certificate((String)call(proof,"graphFingerprint"),
			(String)call(proof,"assignmentHash"), number(proof,"exploredCount"), number(proof,"prunedCount"),
			number(proof,"legalUniverseSize"), score(call(proof,"incumbentScore")),
			score(call(proof,"finalUpperBound")), List.copyOf(bounds), integer(proof,"graphNodeCount"),
			integer(proof,"graphConstraintCount"), integer(proof,"graphComponentCount"),
			String.valueOf(call(proof,"boundDerivation")), String.valueOf(call(proof,"terminationReason")),
			(Boolean)call(proof,"fallbackUsed"));
		return new Selection(analysis, Map.copyOf(assignment), List.copyOf(relocations), score, certificate);
	}

	static String assignmentHash(Map<CompiledHopKey,PlacementState> assignment) {
		List<String> lines = assignment.entrySet().stream().map(e -> e.getKey().normalizedSignature() + '='
			+ e.getValue().normalizedSignature()).sorted().toList(); return sha256(String.join("\n", lines));
	}
	static String graphHash(PlacementAnalysis analysis) { return sha256(analysis.graph().normalizedSignature()); }
	static String normalize(Selection s) {
		return s.assignment().entrySet().stream().map(e -> e.getKey().normalizedSignature() + '=' + e.getValue().normalizedSignature())
			.sorted().toList() + "|" + s.relocations().stream().map(RelocationActionKey::normalizedSignature).sorted().toList()
			+ "|" + s.score() + "|" + s.certificate();
	}

	private static Score score(Object value) throws ReflectiveOperationException {
		return new Score(integer(value,"fedCount"), integer(value,"foutCount"), integer(value,"relocationCount"),
			String.valueOf(call(value,"normalizedSignature")));
	}
	private static int integer(Object value, String name) throws ReflectiveOperationException { return ((Number)call(value,name)).intValue(); }
	private static long number(Object value, String name) throws ReflectiveOperationException { return ((Number)call(value,name)).longValue(); }
	private static Object call(Object value, String name) throws ReflectiveOperationException { return value.getClass().getMethod(name).invoke(value); }
	private static AssertionError contract(Planner planner, String field, Throwable cause) {
		return new AssertionError("CAMPAIGN_B_RUNTIME_CONTRACT|planner=" + planner + "|field=" + field
			+ "|reason=" + cause.getClass().getSimpleName());
	}
	@SuppressWarnings({"rawtypes","unchecked"}) private static void assertImmutableMap(Map map, String field) {
		try { map.put(null,null); throw new AssertionError("R4_RESULT_MUTABILITY|" + field); }
		catch(UnsupportedOperationException expected) { }
	}
	@SuppressWarnings({"rawtypes","unchecked"}) private static void assertImmutableList(List list, String field) {
		try { list.add(null); throw new AssertionError("R4_RESULT_MUTABILITY|" + field); }
		catch(UnsupportedOperationException expected) { }
	}
	private static String sha256(String text) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
		catch(Exception e) { throw new AssertionError(e); }
	}
	private R4SharedFedAllAdapterBridge() { }
}
