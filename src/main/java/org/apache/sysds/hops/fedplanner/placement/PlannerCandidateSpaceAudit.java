/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Exclusion;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateCapabilityFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateShapeProofFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Off-by-default, selector-independent capture of the placement candidate domain.
 *
 * <p>Each JSONL row retains both the last candidate state before privacy closure and
 * the exact state published to all selectors.  This makes privacy filtering visible
 * without reconstructing it from a selected plan.  The audit is observational: it
 * neither adds alternatives nor changes selector behavior.</p>
 */
public final class PlannerCandidateSpaceAudit {
	public static final String PROPERTY = "sysds.fedplanner.space.audit";
	public static final String DIRECTORY_PROPERTY = "sysds.fedplanner.space.audit.dir";
	public static final String CONTEXT_PROPERTY = "sysds.fedplanner.space.audit.context";
	public static final String INVOCATION_PROPERTY =
		"sysds.fedplanner.space.audit.invocation";
	private static final String DEFAULT_DIRECTORY = "target/fedplanner-space-audit";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Object WRITE_LOCK = new Object();

	private PlannerCandidateSpaceAudit() {
		// utility class
	}

	public static boolean isEnabled() {
		return Boolean.getBoolean(PROPERTY);
	}

	/** Capture one immutable analysis exactly once, after privacy closure. */
	static void record(PlacementAnalysis analysis, List<Node> prePrivacyNodes,
		List<CandidateRuleFact> prePrivacyFacts) {
		if(!isEnabled())
			return;
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(prePrivacyNodes, "prePrivacyNodes");
		Objects.requireNonNull(prePrivacyFacts, "prePrivacyFacts");

		Map<CompiledHopKey,Node> rawNodes = identityNodes(prePrivacyNodes);
		Map<CompiledHopKey,Node> publishedNodes = identityNodes(analysis.graph().nodes());
		Map<CandidateRuleKey,CandidateRuleFact> rawFacts = indexFacts(prePrivacyFacts);
		Map<CandidateRuleKey,CandidateRuleFact> publishedFacts =
			indexFacts(analysis.candidateRuleFacts().orderedFacts());
		Set<CandidateRuleKey> keys = new LinkedHashSet<>();
		keys.addAll(rawFacts.keySet());
		keys.addAll(publishedFacts.keySet());
		String auditContext = currentAuditContext();

		List<Map<String,Object>> rows = new ArrayList<>(keys.size());
		for(CandidateRuleKey key : keys) {
			CandidateRuleFact raw = rawFacts.get(key);
			CandidateRuleFact published = publishedFacts.get(key);
			CompiledHopKey occurrence = key.parentOccurrence();
			Node rawNode = rawNodes.get(occurrence);
			Node publishedNode = publishedNodes.get(occurrence);
			Map<String,Object> row = new LinkedHashMap<>();
			row.put("schema", "fedplanner-candidate-space-v1");
			row.put("pid", ProcessHandle.current().pid());
			row.put("analysisFingerprint", analysis.analysisFingerprint());
			row.put("auditContext", auditContext);
			row.put("auditInvocation", currentAuditInvocation());
			row.put("occurrenceKeyHash", PlannerRuntimePlacementAudit.shortHash(
				occurrence.normalizedSignature()));
			row.put("replayOccurrenceKeyHash", replayOccurrenceHash(occurrence));
			row.put("semanticReplayOccurrenceKeyHash",
				semanticReplayOccurrenceHash(occurrence));
			row.put("occurrence", occurrence.normalizedSignature());
			row.put("inputSignature", inputs(key.orderedInputs()));
			row.put("ruleSignature", key.normalizedSignature());
			analysis.hop(occurrence).ifPresent(hop -> addHop(row, hop));
			row.put("privacy", analysis.requirePrivacy(occurrence).name());
			row.put("workers", analysis.numWorkers());
			analysis.shapeFact(occurrence).ifPresent(shape -> row.put("concreteShape", shape(shape)));
			analysis.abstractShapeFact(occurrence)
				.ifPresent(shape -> row.put("abstractShape", shape(shape)));
			row.put("prePrivacyNodeStates", states(rawNode));
			row.put("publishedNodeStates", states(publishedNode));
			row.put("prePrivacyExclusions", exclusions(rawNode));
			row.put("publishedExclusions", exclusions(publishedNode));
			row.put("prePrivacyRule", fact(raw));
			row.put("publishedRule", fact(published));
			row.put("publishedStatesP", published == null ? List.of()
				: emissions(published.allowedEmissionFacts()));
			rows.add(row);
		}
		append(rows);
	}

	/**
	 * Capture the occurrence that made privacy closure fail closed before a
	 * {@link PlacementAnalysis} can be constructed.
	 *
	 * <p>This hook is observational and off by default. It records the empty
	 * published domain immediately before the existing exception is thrown; it
	 * does not retain, add, or select a placement.</p>
	 */
	static void recordPrivacyFailure(Node prePrivacyNode, Node filteredNode, Privacy privacy,
		Hop hop, List<CandidateRuleFact> prePrivacyFacts,
		List<CandidateRuleFact> filteredFacts, String failureReason) {
		if(!isEnabled())
			return;
		Objects.requireNonNull(prePrivacyNode, "prePrivacyNode");
		Objects.requireNonNull(filteredNode, "filteredNode");
		Objects.requireNonNull(privacy, "privacy");
		Objects.requireNonNull(hop, "hop");
		Objects.requireNonNull(prePrivacyFacts, "prePrivacyFacts");
		Objects.requireNonNull(filteredFacts, "filteredFacts");
		Objects.requireNonNull(failureReason, "failureReason");

		CompiledHopKey occurrence = prePrivacyNode.key();
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "fedplanner-candidate-space-privacy-failure-v1");
		row.put("pid", ProcessHandle.current().pid());
		row.put("analysisFingerprint", null);
		row.put("auditContext", currentAuditContext());
		row.put("auditInvocation", currentAuditInvocation());
		row.put("occurrenceKeyHash", PlannerRuntimePlacementAudit.shortHash(
			occurrence.normalizedSignature()));
		row.put("replayOccurrenceKeyHash", replayOccurrenceHash(occurrence));
		row.put("semanticReplayOccurrenceKeyHash", semanticReplayOccurrenceHash(occurrence));
		row.put("occurrence", occurrence.normalizedSignature());
		addHop(row, hop);
		row.put("privacy", privacy.name());
		row.put("failureReason", failureReason);
		row.put("prePrivacyNodeStates", states(prePrivacyNode));
		row.put("publishedNodeStates", states(filteredNode));
		row.put("prePrivacyExclusions", exclusions(prePrivacyNode));
		row.put("publishedExclusions", exclusions(filteredNode));
		row.put("prePrivacyRules", occurrenceFacts(prePrivacyFacts, occurrence));
		row.put("publishedRules", occurrenceFacts(filteredFacts, occurrence));
		row.put("publishedStatesP", List.of());
		append(List.of(row));
	}

	/**
	 * Replay-stable occurrence identity for audit campaigns.
	 *
	 * <p>Production occurrence identity deliberately contains the concrete program
	 * fingerprint. Federated tests, however, allocate fresh worker ports on every
	 * invocation, which changes literal URLs and therefore that fingerprint. This
	 * audit-only identity retains the complete structural occurrence path while
	 * removing only the program fingerprint and volatile loopback port values.</p>
	 */
	public static String replayOccurrenceHash(CompiledHopKey occurrence) {
		Objects.requireNonNull(occurrence, "occurrence");
		var region = occurrence.controlRegion();
		String stable = String.join("\u0000",
			occurrence.functionNamespace(), normalizeReplayControlPath(occurrence.callSitePath()),
			occurrence.recompileContext(), region.functionNamespace(),
			String.join("\u0001", region.regionPath().stream()
				.map(PlannerCandidateSpaceAudit::normalizeReplayControlPath).toList()),
			normalizeReplayControlPath(region.callSitePath()), region.recompileContext(),
			occurrence.emittedHopInstance(),
			normalizeReplayVolatileValues(occurrence.canonicalSourceOrigin()));
		return PlannerRuntimePlacementAudit.shortHash(stable);
	}

	/**
	 * Secondary audit-only identity for a fail-closed clean-JVM replay.
	 *
	 * <p>Dynamic rewrites can retain the same source operation and control region
	 * while changing only the root/input path used to emit the current HOP DAG.
	 * This identity therefore omits {@link CompiledHopKey#emittedHopInstance()}.
	 * It is never a production identity and must only be accepted after proving
	 * that it resolves to exactly one current decision domain.</p>
	 */
	public static String semanticReplayOccurrenceHash(CompiledHopKey occurrence) {
		Objects.requireNonNull(occurrence, "occurrence");
		var region = occurrence.controlRegion();
		String stable = String.join("\u0000",
			occurrence.functionNamespace(), normalizeReplayControlPath(occurrence.callSitePath()),
			occurrence.recompileContext(), region.functionNamespace(),
			String.join("\u0001", region.regionPath().stream()
				.map(PlannerCandidateSpaceAudit::normalizeReplayControlPath).toList()),
			normalizeReplayControlPath(region.callSitePath()), region.recompileContext(),
			normalizeReplayVolatileValues(occurrence.canonicalSourceOrigin()));
		return PlannerRuntimePlacementAudit.shortHash(stable);
	}

	/**
	 * Statement-block ordinals are construction-order identities, not replay identities.
	 * A test compiled after a different set of earlier programs can assign the same
	 * source branch {@code main/5/branch-if/0} or {@code main/2/branch-if/0}. Keep the
	 * semantic control tokens while replacing only all-numeric path segments. Exact
	 * forcing still rejects a normalized key that matches more than one decision domain.
	 */
	static String normalizeReplayControlPath(String path) {
		return String.join("/", java.util.Arrays.stream(path.split("/", -1))
			.map(segment -> segment.matches("[0-9]+") ? "*" : segment).toList());
	}

	private static String normalizeReplayVolatileValues(String value) {
		return value.replaceAll("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\]):[0-9]{1,5}",
			"$1:<port>");
	}

	/** Replay context shared by candidate and runtime-capability audit receipts. */
	public static String currentAuditContext() {
		String configured = System.getProperty(CONTEXT_PROPERTY);
		if(configured != null && !configured.isBlank())
			return configured.trim();
		return captureCurrentTestContext();
	}

	/**
	 * Exact audit-runner supplied JUnit leaf identity.
	 *
	 * <p>Stack walking can identify only {@code class#method}; that is insufficient
	 * for JUnit parameter siblings. Strict replay therefore accepts only this
	 * explicit runner boundary and never guesses a parameter from compilation
	 * order or candidate contents.</p>
	 */
	public static String currentAuditInvocation() {
		String configured = System.getProperty(INVOCATION_PROPERTY);
		return configured == null || configured.isBlank() ? null : configured.trim();
	}

	/** Capture the annotated JUnit caller before a test delegates DML to a worker thread. */
	public static String captureCurrentTestContext() {
		List<StackWalker.StackFrame> candidates = StackWalker.getInstance().walk(frames -> frames.filter(frame -> {
			String name = frame.getClassName();
			int separator = name.lastIndexOf('.');
			String simple = separator < 0 ? name : name.substring(separator + 1);
			return (name.startsWith("org.apache.sysds.test.")
				|| name.startsWith("org.apache.sysds.hops.fedplanner."))
				&& (simple.endsWith("Test") || simple.contains("Test$"));
		}).toList());
		// Helper methods are closer to the compiler on the stack than the actual
		// JUnit entry point.  Persist the annotated method so the forced-state
		// driver can replay the exact test instead of an uncallable private helper.
		return candidates.stream().filter(PlannerCandidateSpaceAudit::isJUnitTestFrame)
			.map(PlannerCandidateSpaceAudit::context).findFirst()
			.orElseGet(() -> candidates.isEmpty() ? null : context(candidates.get(0)));
	}

	private static boolean isJUnitTestFrame(StackWalker.StackFrame frame) {
		try {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			Class<?> type = Class.forName(frame.getClassName(), false,
				loader == null ? PlannerCandidateSpaceAudit.class.getClassLoader() : loader);
			for(Method method : type.getDeclaredMethods()) {
				if(!method.getName().equals(frame.getMethodName()))
					continue;
				boolean test = java.util.Arrays.stream(method.getDeclaredAnnotations())
					.anyMatch(annotation -> "org.junit.Test".equals(
						annotation.annotationType().getName())
						|| "org.junit.jupiter.api.Test".equals(
							annotation.annotationType().getName()));
				if(test)
					return true;
			}
		}
		catch(ReflectiveOperationException | LinkageError | SecurityException ignored) {
			// Fall back to the nearest test-owned frame below.
		}
		return false;
	}

	private static String context(StackWalker.StackFrame frame) {
		return frame.getClassName() + '#' + frame.getMethodName();
	}

	private static Map<CompiledHopKey,Node> identityNodes(List<Node> nodes) {
		Map<CompiledHopKey,Node> indexed = new IdentityHashMap<>();
		for(Node node : nodes)
			indexed.put(node.key(), node);
		return indexed;
	}

	private static Map<CandidateRuleKey,CandidateRuleFact> indexFacts(List<CandidateRuleFact> facts) {
		Map<CandidateRuleKey,CandidateRuleFact> indexed = new LinkedHashMap<>();
		for(CandidateRuleFact fact : facts)
			indexed.put(fact.key(), fact);
		return indexed;
	}

	private static List<Map<String,Object>> occurrenceFacts(List<CandidateRuleFact> facts,
		CompiledHopKey occurrence) {
		return facts.stream().filter(candidate -> candidate.key().parentOccurrence().equals(occurrence))
			.map(PlannerCandidateSpaceAudit::fact).toList();
	}

	private static void addHop(Map<String,Object> row, Hop hop) {
		row.put("hopId", hop.getHopID());
		row.put("originHopId", hop.getPlannerOriginHopID());
		row.put("opcode", hop.getOpString());
		row.put("hopClass", hop.getClass().getName());
		row.put("dataType", hop.getDataType().name());
		row.put("valueType", hop.getValueType().name());
	}

	private static List<Map<String,Object>> inputs(List<CandidateInputState> inputs) {
		List<Map<String,Object>> out = new ArrayList<>(inputs.size());
		for(int i = 0; i < inputs.size(); i++) {
			CandidateInputState input = inputs.get(i);
			Map<String,Object> value = new LinkedHashMap<>();
			value.put("position", i);
			value.put("presence", input.presence().name());
			value.put("fType", input.fType() == null ? null : input.fType().name());
			out.add(value);
		}
		return out;
	}

	private static Map<String,Object> fact(CandidateRuleFact fact) {
		if(fact == null)
			return Map.of();
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("status", fact.status().name());
		out.put("failureCode", fact.failureCode());
		out.put("capability", capability(fact.capability()));
		out.put("shapeProof", shapeProof(fact.shapeProof()));
		out.put("producerOutputs", fact.profile().producerOutputs().stream().map(Enum::name).toList());
		out.put("profileFailure", fact.profile().evaluationFailure());
		out.put("emissions", emissions(fact.allowedEmissionFacts()));
		return out;
	}

	private static Map<String,Object> capability(CandidateCapabilityFact capability) {
		if(capability == null)
			return Map.of();
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("category", capability.category().name());
		out.put("opcode", capability.opcode());
		out.put("nativeExec", capability.nativeExec().name());
		out.put("nativeOutput", capability.nativeOutput().name());
		out.put("nativeFoutFType", capability.nativeFoutFType() == null
			? null : capability.nativeFoutFType().name());
		out.put("reasonCode", capability.reasonCode().name());
		out.put("detail", capability.detail());
		out.put("notes", capability.notes().stream().map(note -> Map.of(
			"code", note.code().name(), "message", note.message())).toList());
		return out;
	}

	private static Map<String,Object> shapeProof(CandidateShapeProofFact proof) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("consulted", proof.consultedFacts());
		out.put("required", proof.requiredFacts());
		out.put("missing", proof.missingRequiredFacts());
		return out;
	}

	private static List<Map<String,Object>> emissions(List<CandidateEmissionFact> emissions) {
		List<Map<String,Object>> out = new ArrayList<>(emissions.size());
		for(CandidateEmissionFact emission : emissions) {
			PlacementEmissionState exact = emission.emissionState();
			PlacementState state = exact.placementState();
			Map<String,Object> value = new LinkedHashMap<>();
			value.put("signature", emission.normalizedSignature());
			value.put("exec", state.execType().name());
			value.put("output", state.output().name());
			value.put("fType", state.fType() == null ? null : state.fType().name());
			value.put("executionFType", emission.executionFType() == null
				? null : emission.executionFType().name());
			value.put("shapeDependent", state.shapeDependent());
			value.put("derivedFedFout", exact.derivedFedFout());
			value.put("derivedAction", emission.derivedFoutAction() == null ? null
				: emission.derivedFoutAction().normalizedSignature());
			out.add(value);
		}
		return out;
	}

	private static List<String> states(Node node) {
		return node == null ? List.of() : node.legalAlternatives().stream()
			.map(PlacementState::normalizedSignature).toList();
	}

	private static List<Map<String,String>> exclusions(Node node) {
		if(node == null)
			return List.of();
		return node.exclusions().stream().map(PlannerCandidateSpaceAudit::exclusion).toList();
	}

	private static Map<String,String> exclusion(Exclusion exclusion) {
		Map<String,String> out = new LinkedHashMap<>();
		out.put("state", exclusion.state().normalizedSignature());
		out.put("reason", exclusion.reasonCode().name());
		out.put("detail", exclusion.detail());
		return out;
	}

	private static Map<String,Object> shape(NodeShapeFact shape) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("dataType", shape.dataType().name());
		out.put("rows", shape.rows());
		out.put("cols", shape.cols());
		return out;
	}

	private static Map<String,Object> shape(AbstractShapeFact shape) {
		Map<String,Object> out = new LinkedHashMap<>();
		out.put("dataType", shape.dataType().name());
		out.put("rows", shape.rows().normalizedSignature());
		out.put("cols", shape.cols().normalizedSignature());
		out.put("orientation", shape.orientation().name());
		return out;
	}

	private static void append(List<Map<String,Object>> rows) {
		if(rows.isEmpty())
			return;
		Path directory = Path.of(System.getProperty(DIRECTORY_PROPERTY, DEFAULT_DIRECTORY));
		Path output = directory.resolve("candidate-space-" + ProcessHandle.current().pid() + ".jsonl");
		try {
			synchronized(WRITE_LOCK) {
				Files.createDirectories(directory);
				StringBuilder jsonl = new StringBuilder();
				for(Map<String,Object> row : rows)
					jsonl.append(MAPPER.writeValueAsString(row)).append('\n');
				Files.writeString(output, jsonl, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
		catch(IOException ex) {
			throw new IllegalStateException("Unable to write federated planner candidate-space audit: "
				+ output, ex);
		}
	}
}
