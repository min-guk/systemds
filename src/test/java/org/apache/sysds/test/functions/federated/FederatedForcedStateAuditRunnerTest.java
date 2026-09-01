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

package org.apache.sysds.test.functions.federated;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalForcedStateAudit;
import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimeCapabilityAudit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Replays exact candidate states without paying one Maven/JVM startup per target.
 * The test is skipped during normal suites and activates only with a manifest.
 */
@net.jcip.annotations.NotThreadSafe
public class FederatedForcedStateAuditRunnerTest {
	public static final String MANIFEST_PROPERTY =
		"sysds.fedplanner.space.audit.force.manifest";
	public static final String OUTPUT_PROPERTY =
		"sysds.fedplanner.space.audit.force.campaign.dir";
	public static final String SHARD_INDEX_PROPERTY =
		"sysds.fedplanner.space.audit.force.shard.index";
	public static final String SHARD_COUNT_PROPERTY =
		"sysds.fedplanner.space.audit.force.shard.count";
	public static final String MAX_TARGETS_PROPERTY =
		"sysds.fedplanner.space.audit.force.max.targets";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() { };
	private static final Pattern TARGET_ID = Pattern.compile("[0-9a-f]{16}");
	private static final Pattern JAVA_CLASS = Pattern.compile(
		"[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
	private static final Pattern JAVA_METHOD = Pattern.compile(
		"[A-Za-z_$][A-Za-z0-9_$]*");
	private static final Pattern MANIFEST_SCHEMA = Pattern.compile(
		"fedplanner-forced-state-manifest-v([0-9]+)");

	static record InvocationIdentity(String className, String methodName) { }

	private record ReplayAttempt(Path output, String testCase, Result result,
		Throwable launchFailure, long durationMillis, List<String> failures,
		boolean constraintApplied, boolean constraintSatisfied,
		boolean targetNotExposed, boolean replayIdentityAmbiguous) { }

	@Test
	public void replayManifestShard() throws Exception {
		String manifestProperty = System.getProperty(MANIFEST_PROPERTY);
		Assume.assumeTrue("forced-state manifest not configured",
			manifestProperty != null && !manifestProperty.isBlank());
		Path manifest = Path.of(manifestProperty);
		Path output = Path.of(requiredProperty(OUTPUT_PROPERTY));
		int shardIndex = Integer.getInteger(SHARD_INDEX_PROPERTY, 0);
		int shardCount = Integer.getInteger(SHARD_COUNT_PROPERTY, 1);
		int maxTargets = Integer.getInteger(MAX_TARGETS_PROPERTY, Integer.MAX_VALUE);
		if(shardCount <= 0 || shardIndex < 0 || shardIndex >= shardCount || maxTargets <= 0)
			throw new IllegalArgumentException("Invalid forced-state shard configuration");
		Files.createDirectories(output);
		List<Map<String,Object>> targets = readManifest(manifest);
		int attempted = 0;
		for(int index = 0; index < targets.size() && attempted < maxTargets; index++) {
			if(index % shardCount != shardIndex)
				continue;
			Map<String,Object> target = targets.get(index);
			// Validate identity and strict replay requirements before taking the
			// legacy diagnostic path for an unreplayable context.
			targetOutput(output, required(target, "targetId"));
			requiredReplayInvocation(target);
			if(target.get("replayContext") == null) {
				record(output, target, "UNREPLAYABLE_CONTEXT", 0, null, List.of(), false);
				continue;
			}
			attempted++;
			replay(output, target);
		}
	}

	private static void replay(Path campaignOutput, Map<String,Object> target) throws Exception {
		String targetId = required(target, "targetId");
		InvocationIdentity context = parseInvocation(required(target, "replayContext"),
			"replay context");
		Path targetOutput = targetOutput(campaignOutput, targetId);
		Files.createDirectories(targetOutput);
		set(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY, target, "analysisFingerprint");
		set(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY, target, "occurrenceKeyHash");
		setOptional(ExactPhysicalForcedStateAudit.SEMANTIC_OCCURRENCE_PROPERTY,
			target, "semanticOccurrenceKeyHash");
		set(ExactPhysicalForcedStateAudit.INPUT_PROPERTY, target, "inputSignature", true);
		set(ExactPhysicalForcedStateAudit.STATE_PROPERTY, target, "state");
		try {
			Class<?> testClass = Class.forName(context.className());
			String exactInvocation = requiredReplayInvocation(target);
			String requestedMethod = context.methodName();
			if(exactInvocation != null) {
				InvocationIdentity invocation = parseInvocation(exactInvocation,
					"replay invocation");
				if(!invocation.className().equals(context.className())
					|| !baseMethodName(invocation.methodName()).equals(requestedMethod))
					throw new IllegalArgumentException("Replay invocation does not belong to context: "
						+ exactInvocation + " != " + context.className() + '#' + context.methodName());
				requestedMethod = invocation.methodName();
			}
			List<Description> testCases = matchingTestCases(testClass, requestedMethod);
			if(exactInvocation != null && testCases.size() != 1)
				throw new IllegalArgumentException("Exact replay invocation resolved to "
					+ testCases.size() + " JUnit leaves: " + exactInvocation);
			List<ReplayAttempt> attempts = new ArrayList<>();
			for(int index = 0; index < testCases.size(); index++) {
				ReplayAttempt attempt = replayTestCase(testClass, testCases.get(index),
					targetOutput.resolve(String.format("attempt-%04d", index)));
				attempts.add(attempt);
				// Legacy manifests identify only a method and retain the historical sibling
				// fallback. Strict manifests carry replayInvocation, resolve exactly one leaf,
				// and therefore execute this loop exactly once.
				if(attempt.constraintApplied())
					break;
			}
			ReplayAttempt selected = attempts.stream()
				.filter(ReplayAttempt::constraintApplied).findFirst()
				.orElseGet(() -> attempts.stream()
					.filter(ReplayAttempt::targetNotExposed).findFirst()
					.orElse(attempts.isEmpty() ? null : attempts.get(attempts.size() - 1)));
			String outcome;
			if(selected == null)
				outcome = "NO_MATCHING_TEST";
			else if(selected.constraintApplied())
				outcome = classify(selected.result(), selected.launchFailure(),
					selected.constraintSatisfied(), selected.output(), selected.failures());
			else if(attempts.stream().anyMatch(ReplayAttempt::targetNotExposed))
				outcome = "TARGET_NOT_EXPOSED";
			else if(attempts.stream().anyMatch(ReplayAttempt::replayIdentityAmbiguous))
				outcome = "REPLAY_IDENTITY_AMBIGUOUS";
			else if(attempts.stream().allMatch(attempt -> attempt.launchFailure() != null))
				outcome = "RUNNER_FAILURE";
			else
				outcome = "TARGET_NOT_REACHED";
			long millis = attempts.stream().mapToLong(ReplayAttempt::durationMillis).sum();
			record(campaignOutput, target, outcome, millis,
				selected == null ? null : selected.result(),
				selected == null ? List.of() : selected.failures(),
				selected != null && selected.constraintSatisfied());
		}
		finally {
			clearTargetProperties();
		}
	}

	static InvocationIdentity parseInvocation(String value, String label) {
		int separator = value == null ? -1 : value.indexOf('#');
		if(separator <= 0 || separator == value.length() - 1)
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		String className = value.substring(0, separator);
		String methodName = value.substring(separator + 1);
		if(!JAVA_CLASS.matcher(className).matches() || methodName.isBlank()
			|| !JAVA_METHOD.matcher(baseMethodName(methodName)).matches())
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		return new InvocationIdentity(className, methodName);
	}

	static String requiredReplayInvocation(Map<String,Object> target) {
		String invocation = optional(target, "replayInvocation");
		if(requiresExactReplay(target) && invocation == null)
			throw new IllegalArgumentException(
				"Strict manifest target is missing replayInvocation");
		return invocation;
	}

	static boolean requiresExactReplay(Map<String,Object> target) {
		if(Boolean.TRUE.equals(target.get("exactReplayLeaf")))
			return true;
		Object schemaValue = target.get("schema");
		Matcher schema = MANIFEST_SCHEMA.matcher(String.valueOf(schemaValue));
		return schema.matches() && Integer.parseInt(schema.group(1)) >= 2;
	}

	static Path targetOutput(Path campaignOutput, String targetId) {
		if(targetId == null || !TARGET_ID.matcher(targetId).matches())
			throw new IllegalArgumentException("Invalid targetId: " + targetId);
		Path targets = campaignOutput.toAbsolutePath().normalize().resolve("targets").normalize();
		Path output = targets.resolve(targetId).normalize();
		if(!output.startsWith(targets) || !targets.equals(output.getParent()))
			throw new IllegalArgumentException("Target output escapes campaign targets: " + output);
		return output;
	}

	private static ReplayAttempt replayTestCase(Class<?> testClass, Description testCase,
		Path output) throws IOException {
		Files.createDirectories(output);
		System.setProperty(ExactPhysicalForcedStateAudit.DIRECTORY_PROPERTY, output.toString());
		System.setProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY, output.toString());
		System.setProperty(PlannerRuntimeCapabilityAudit.DIRECTORY_PROPERTY, output.toString());
		String invocation = testCase.getClassName() + '#' + testCase.getMethodName();
		System.setProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY,
			testCase.getClassName() + '#' + baseMethodName(testCase.getMethodName()));
		System.setProperty(PlannerCandidateSpaceAudit.INVOCATION_PROPERTY, invocation);
		PlannerRuntimeCapabilityAudit.resetRecordedWitnesses();
		long start = System.nanoTime();
		Result result = null;
		Throwable launchFailure = null;
		try {
			result = new JUnitCore().run(exactRequest(testClass, testCase));
		}
		catch(Throwable failure) {
			launchFailure = failure;
		}
		long millis = (System.nanoTime() - start) / 1_000_000L;
		List<String> failures = launchFailure == null ? failures(result)
			: List.of(launchFailure.getClass().getName() + ": " + launchFailure.getMessage());
		return new ReplayAttempt(output, testCase.getDisplayName(), result, launchFailure,
			millis, failures, hasForcedEvent(output, "CONSTRAINT_APPLIED"),
			hasForcedEvent(output, "CONSTRAINT_SATISFIED"),
			hasForcedEvent(output, "TARGET_NOT_EXPOSED"),
			hasForcedEvent(output, "REPLAY_IDENTITY_AMBIGUOUS"));
	}

	static String baseMethodName(String methodName) {
		int parameter = methodName.indexOf('[');
		return parameter < 0 ? methodName : methodName.substring(0, parameter);
	}

	static List<Description> matchingTestCases(Class<?> testClass, String methodName) {
		Description root = Request.aClass(testClass).getRunner().getDescription();
		List<Description> matches = new ArrayList<>();
		collectMatchingTestCases(root, methodName, matches);
		return List.copyOf(matches);
	}

	private static void collectMatchingTestCases(Description description, String methodName,
		List<Description> matches) {
		if(description.isTest()) {
			String actual = description.getMethodName();
			boolean exactParameterizedCase = methodName.indexOf('[') >= 0;
			if(actual != null && (actual.equals(methodName)
				|| (!exactParameterizedCase && actual.startsWith(methodName + '['))))
				matches.add(description);
			return;
		}
		for(Description child : description.getChildren())
			collectMatchingTestCases(child, methodName, matches);
	}

	static Request exactRequest(Class<?> testClass, Description testCase) {
		return Request.aClass(testClass).filterWith(new Filter() {
			@Override
			public boolean shouldRun(Description description) {
				if(description.isTest())
					return description.equals(testCase);
				return description.getChildren().stream().anyMatch(this::shouldRun);
			}

			@Override
			public String describe() {
				return "forced-state replay of " + testCase.getDisplayName();
			}
		});
	}

	private static String classify(Result result, Throwable launchFailure, boolean satisfied,
		Path targetOutput, List<String> failures) throws IOException {
		if(launchFailure != null)
			return "RUNNER_FAILURE";
		if(hasForcedEvent(targetOutput, "TARGET_NOT_EXPOSED"))
			return "TARGET_NOT_EXPOSED";
		if(hasForcedEvent(targetOutput, "WHOLE_PROGRAM_INFEASIBLE"))
			return "WHOLE_PROGRAM_INFEASIBLE";
		if(!satisfied && failures.stream().anyMatch(message ->
			message.contains("EXACT_VE_NO_FEASIBLE_ASSIGNMENT")))
			return "WHOLE_PROGRAM_INFEASIBLE";
		if(!satisfied)
			return result != null && result.getRunCount() == 0
				? "NO_MATCHING_TEST" : "TARGET_NOT_REACHED";
		if(hasRuntimeFailure(targetOutput))
			return "RUNTIME_FAILURE";
		return result != null && result.wasSuccessful()
			? "SUCCESS" : "FAILURE_REQUIRES_TRIAGE";
	}

	private static boolean hasRuntimeFailure(Path directory) throws IOException {
		try(var paths = Files.list(directory)) {
			for(Path path : paths.filter(candidate -> candidate.getFileName().toString()
				.startsWith("runtime-capability-")).toList())
				for(String line : Files.readAllLines(path))
					if(line.contains("\"outcome\":\"FAILURE\""))
						return true;
		}
		return false;
	}

	private static boolean hasForcedEvent(Path directory, String event) throws IOException {
		try(var paths = Files.list(directory)) {
			for(Path path : paths.filter(candidate -> candidate.getFileName().toString()
				.startsWith("forced-state-")).toList())
				for(String line : Files.readAllLines(path))
					if(line.contains("\"event\":\"" + event + "\""))
						return true;
		}
		return false;
	}

	private static List<Map<String,Object>> readManifest(Path manifest) throws IOException {
		List<Map<String,Object>> rows = new ArrayList<>();
		for(String line : Files.readAllLines(manifest, StandardCharsets.UTF_8))
			if(!line.isBlank())
				rows.add(MAPPER.readValue(line, MAP));
		return rows;
	}

	private static List<String> failures(Result result) {
		if(result == null)
			return List.of();
		return result.getFailures().stream().map(FederatedForcedStateAuditRunnerTest::failure)
			.toList();
	}

	private static String failure(Failure failure) {
		String value = failure.getTestHeader() + " | "
			+ failure.getException().getClass().getName() + ": "
			+ String.valueOf(failure.getMessage());
		return value.length() <= 4000 ? value : value.substring(0, 4000);
	}

	private static void record(Path output, Map<String,Object> target, String outcome,
		long millis, Result result, List<String> failures, boolean satisfied) throws IOException {
		Map<String,Object> row = new LinkedHashMap<>(target);
		row.put("schema", "fedplanner-forced-state-result-v1");
		row.put("outcome", outcome);
		row.put("durationMillis", millis);
		row.put("constraintSatisfied", satisfied);
		row.put("runCount", result == null ? 0 : result.getRunCount());
		row.put("ignoreCount", result == null ? 0 : result.getIgnoreCount());
		row.put("failureCount", result == null ? failures.size() : result.getFailureCount());
		row.put("failures", failures);
		Path results = output.resolve("forced-state-results-"
			+ ProcessHandle.current().pid() + ".jsonl");
		Files.writeString(results, MAPPER.writeValueAsString(row) + '\n',
			StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static void set(String property, Map<String,Object> target, String field) {
		set(property, target, field, false);
	}

	private static void set(String property, Map<String,Object> target, String field,
		boolean allowEmpty) {
		Object value = target.get(field);
		if(value == null || (!allowEmpty && String.valueOf(value).isBlank()))
			throw new IllegalArgumentException("Missing manifest field: " + field);
		System.setProperty(property, String.valueOf(value));
	}

	private static void setOptional(String property, Map<String,Object> target, String field) {
		Object value = target.get(field);
		if(value == null || String.valueOf(value).isBlank())
			System.clearProperty(property);
		else
			System.setProperty(property, String.valueOf(value));
	}

	private static String required(Map<String,Object> row, String field) {
		Object value = row.get(field);
		if(value == null || String.valueOf(value).isBlank())
			throw new IllegalArgumentException("Missing manifest field: " + field);
		return String.valueOf(value);
	}

	private static String optional(Map<String,Object> row, String field) {
		Object value = row.get(field);
		return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
	}

	private static String requiredProperty(String property) {
		String value = System.getProperty(property);
		if(value == null || value.isBlank())
			throw new IllegalArgumentException("Missing property: " + property);
		return value;
	}

	private static void clearTargetProperties() {
		System.clearProperty(ExactPhysicalForcedStateAudit.ANALYSIS_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.OCCURRENCE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.SEMANTIC_OCCURRENCE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.INPUT_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.STATE_PROPERTY);
		System.clearProperty(ExactPhysicalForcedStateAudit.DIRECTORY_PROPERTY);
		System.clearProperty(PlannerCandidateSpaceAudit.DIRECTORY_PROPERTY);
		System.clearProperty(PlannerRuntimeCapabilityAudit.DIRECTORY_PROPERTY);
		System.clearProperty(PlannerCandidateSpaceAudit.CONTEXT_PROPERTY);
		System.clearProperty(PlannerCandidateSpaceAudit.INVOCATION_PROPERTY);
	}
}
