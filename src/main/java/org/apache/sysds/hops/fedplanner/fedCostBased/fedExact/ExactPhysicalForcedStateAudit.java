/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactCategoricalSolver.Factor;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.Alternative;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPhysicalModel.DecisionDomain;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.hops.fedplanner.placement.PlannerCandidateSpaceAudit;
import org.apache.sysds.hops.fedplanner.placement.PlannerRuntimePlacementAudit;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Off-by-default coherent forced-state hook for candidate/runtime space audits.
 *
 * <p>The hook constrains one exact physical-model variable to the published
 * placement and ordered input signature requested by the audit driver. All
 * remaining variables, hard factors, privacy exclusions, and canonical costs
 * are left unchanged. Consequently, a successful solve is a whole-program
 * completion of the requested state rather than an unsafe direct HOP mutation.</p>
 */
public final class ExactPhysicalForcedStateAudit {
	public static final String OCCURRENCE_PROPERTY =
		"sysds.fedplanner.space.audit.force.occurrence";
	public static final String ANALYSIS_PROPERTY =
		"sysds.fedplanner.space.audit.force.analysis";
	public static final String SEMANTIC_OCCURRENCE_PROPERTY =
		"sysds.fedplanner.space.audit.force.semantic.occurrence";
	public static final String INPUT_PROPERTY =
		"sysds.fedplanner.space.audit.force.input";
	public static final String STATE_PROPERTY =
		"sysds.fedplanner.space.audit.force.state";
	public static final String DIRECTORY_PROPERTY =
		"sysds.fedplanner.space.audit.force.dir";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Object WRITE_LOCK = new Object();

	record Constraint(Target target, DecisionDomain domain, int domainIndex,
		List<Integer> allowedValues, Factor factor) {
		Constraint {
			allowedValues = List.copyOf(allowedValues);
			if(allowedValues.isEmpty())
				throw new IllegalArgumentException("FED_SPACE_AUDIT_FORCE_EMPTY_DOMAIN");
		}
	}

	private record Target(String analysisFingerprint, String occurrenceHash,
		String semanticOccurrenceHash, String inputSignature, String physicalState) { }

	private ExactPhysicalForcedStateAudit() {
		// utility class
	}

	/** True only while an exact physical state replay has been configured. */
	public static boolean isActive() {
		return target() != null;
	}

	/**
	 * Select Exact only for an analysis that actually exposes the replay target.
	 * The discovery fingerprint is retained as evidence, but cannot be used as a
	 * replay key because fresh federated worker ports intentionally change it.
	 */
	public static boolean targetsAnalysis(PlacementAnalysis analysis) {
		Target target = target();
		if(target == null)
			return false;
		List<CompiledHopKey> occurrences = matchingOccurrences(analysis, target);
		if(occurrences.isEmpty())
			return false;
		if(occurrences.size() != 1) {
			recordAmbiguity(analysis, target, occurrences);
			throw occurrenceCollision(target, occurrences.size());
		}
		String occurrence = occurrences.get(0).normalizedSignature();
		return analysis.candidateRuleFacts().orderedFacts().stream().anyMatch(fact ->
			fact.key().parentOccurrence().normalizedSignature().equals(occurrence)
				&& inputSignature(fact.key().orderedInputs()).equals(target.inputSignature())
				&& fact.allowedEmissionFacts().stream().anyMatch(emission ->
					physicalState(emission.emissionState().placementState())
						.equals(target.physicalState())));
	}

	static Constraint prepare(ExactPhysicalModel model) {
		Target target = target();
		if(target == null)
			return null;
		List<DecisionDomain> domains = model.domains().stream().filter(domain ->
			PlannerCandidateSpaceAudit.replayOccurrenceHash(domain.node().key())
				.equals(target.occurrenceHash())).toList();
		if(domains.isEmpty() && target.semanticOccurrenceHash() != null)
			domains = model.domains().stream().filter(domain ->
				PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(domain.node().key())
					.equals(target.semanticOccurrenceHash())).toList();
		if(domains.isEmpty())
			return null; // A test class may compile multiple independent DML programs.
		if(domains.size() != 1) {
			recordAmbiguity(model.analysis(), target,
				domains.stream().map(domain -> domain.node().key()).toList());
			throw occurrenceCollision(target, domains.size());
		}
		DecisionDomain domain = domains.get(0);
		int domainIndex = model.domains().indexOf(domain);
		List<Integer> allowed = new ArrayList<>();
		for(int value = 0; value < domain.alternatives().size(); value++) {
			Alternative alternative = domain.alternatives().get(value);
			if(alternative.captured()
				&& target.physicalState().equals(physicalState(alternative.state()))
				&& target.inputSignature().equals(inputSignature(alternative.orderedInputs())))
				allowed.add(value);
		}
		if(allowed.isEmpty()) {
			record("TARGET_NOT_EXPOSED", model, target, domain, List.of(), null);
			throw new IllegalArgumentException("FED_SPACE_AUDIT_FORCE_TARGET_NOT_EXPOSED|hash="
				+ target.occurrenceHash() + "|input=" + target.inputSignature()
				+ "|state=" + target.physicalState());
		}
		boolean[] accepted = new boolean[domain.alternatives().size()];
		allowed.forEach(value -> accepted[value] = true);
		Factor factor = Factor.lazy(List.of(domain.variable()),
			values -> accepted[values[0]] ? 0.0 : Double.POSITIVE_INFINITY);
		record("CONSTRAINT_APPLIED", model, target, domain, allowed, null);
		return new Constraint(target, domain, domainIndex, allowed, factor);
	}

	static void verify(ExactPhysicalModel model, Constraint constraint,
		ExactCategoricalSolver.Result result) {
		if(constraint == null)
			return;
		int selected = result.assignmentInVariableOrder().get(constraint.domainIndex());
		if(!constraint.allowedValues().contains(selected))
			throw new IllegalStateException("FED_SPACE_AUDIT_FORCE_SELECTION_LOST|hash="
				+ constraint.target().occurrenceHash() + "|selected=" + selected
				+ "|allowed=" + constraint.allowedValues());
		record("CONSTRAINT_SATISFIED", model, constraint.target(), constraint.domain(),
			constraint.allowedValues(), selected);
	}

	static void recordSolverFailure(ExactPhysicalModel model, Constraint constraint,
		IllegalArgumentException failure) {
		if(constraint == null)
			return;
		String event = "EXACT_VE_NO_FEASIBLE_ASSIGNMENT".equals(failure.getMessage())
			? "WHOLE_PROGRAM_INFEASIBLE" : "FORCED_SOLVER_FAILURE";
		record(event, model, constraint.target(), constraint.domain(),
			constraint.allowedValues(), null);
	}

	static String physicalState(PlacementState state) {
		return state.execType().name() + '/' + state.output().name() + '/'
			+ (state.fType() == null ? "-" : state.fType().name());
	}

	static String inputSignature(List<CandidateInputState> inputs) {
		return String.join(",", inputs.stream()
			.map(CandidateInputState::normalizedSignature).toList());
	}

	private static Target target() {
		String analysis = System.getProperty(ANALYSIS_PROPERTY);
		String occurrence = System.getProperty(OCCURRENCE_PROPERTY);
		String semanticOccurrence = System.getProperty(SEMANTIC_OCCURRENCE_PROPERTY);
		String input = System.getProperty(INPUT_PROPERTY);
		String state = System.getProperty(STATE_PROPERTY);
		if(analysis == null && occurrence == null && semanticOccurrence == null
			&& input == null && state == null)
			return null;
		if(analysis == null || analysis.isBlank() || occurrence == null
			|| occurrence.isBlank() || input == null
			|| state == null || state.isBlank())
			throw new IllegalArgumentException("FED_SPACE_AUDIT_FORCE_TARGET_INCOMPLETE");
		return new Target(analysis.trim(), occurrence.trim(),
			semanticOccurrence == null || semanticOccurrence.isBlank()
				? null : semanticOccurrence.trim(),
			input.trim(), state.trim());
	}

	private static List<CompiledHopKey> matchingOccurrences(PlacementAnalysis analysis,
		Target target) {
		Map<String,CompiledHopKey> matches = new LinkedHashMap<>();
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			CompiledHopKey occurrence = fact.key().parentOccurrence();
			if(PlannerCandidateSpaceAudit.replayOccurrenceHash(occurrence)
				.equals(target.occurrenceHash()))
				matches.putIfAbsent(occurrence.normalizedSignature(), occurrence);
		}
		if(!matches.isEmpty() || target.semanticOccurrenceHash() == null)
			return List.copyOf(matches.values());
		for(CandidateRuleFact fact : analysis.candidateRuleFacts().orderedFacts()) {
			CompiledHopKey occurrence = fact.key().parentOccurrence();
			if(PlannerCandidateSpaceAudit.semanticReplayOccurrenceHash(occurrence)
				.equals(target.semanticOccurrenceHash()))
				matches.putIfAbsent(occurrence.normalizedSignature(), occurrence);
		}
		return List.copyOf(matches.values());
	}

	private static IllegalArgumentException occurrenceCollision(Target target, int matches) {
		return new IllegalArgumentException("FED_SPACE_AUDIT_FORCE_OCCURRENCE_COLLISION|hash="
			+ target.occurrenceHash() + "|semantic=" + target.semanticOccurrenceHash()
			+ "|matches=" + matches);
	}

	private static void recordAmbiguity(PlacementAnalysis analysis, Target target,
		List<CompiledHopKey> occurrences) {
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "fedplanner-forced-state-v1");
		row.put("event", "REPLAY_IDENTITY_AMBIGUOUS");
		row.put("pid", ProcessHandle.current().pid());
		row.put("discoveryAnalysisFingerprint", target.analysisFingerprint());
		row.put("analysisFingerprint", analysis.analysisFingerprint());
		row.put("occurrenceKeyHash", target.occurrenceHash());
		row.put("semanticOccurrenceKeyHash", target.semanticOccurrenceHash());
		row.put("matches", occurrences.size());
		row.put("matchingOccurrences", occurrences.stream()
			.map(CompiledHopKey::normalizedSignature).toList());
		append(row);
	}

	private static void record(String event, ExactPhysicalModel model, Target target,
		DecisionDomain domain, List<Integer> allowed, Integer selected) {
		String directory = System.getProperty(DIRECTORY_PROPERTY);
		if(directory == null || directory.isBlank())
			return;
		Map<String,Object> row = new LinkedHashMap<>();
		row.put("schema", "fedplanner-forced-state-v1");
		row.put("event", event);
		row.put("pid", ProcessHandle.current().pid());
		row.put("discoveryAnalysisFingerprint", target.analysisFingerprint());
		row.put("analysisFingerprint", model.analysis().analysisFingerprint());
		row.put("occurrenceKeyHash", target.occurrenceHash());
		row.put("semanticOccurrenceKeyHash", target.semanticOccurrenceHash());
		row.put("matchMode", PlannerCandidateSpaceAudit.replayOccurrenceHash(domain.node().key())
			.equals(target.occurrenceHash()) ? "STRUCTURAL" : "SEMANTIC_UNIQUE");
		row.put("occurrence", domain.node().key().normalizedSignature());
		row.put("inputSignature", target.inputSignature());
		row.put("state", target.physicalState());
		row.put("allowedValues", allowed);
		row.put("allowedAlternatives", allowed.stream()
			.map(index -> domain.alternatives().get(index).signature()).toList());
		row.put("selectedValue", selected);
		row.put("selectedAlternative", selected == null ? null
			: domain.alternatives().get(selected).signature());
		append(row);
	}

	private static void append(Map<String,Object> row) {
		String directory = System.getProperty(DIRECTORY_PROPERTY);
		if(directory == null || directory.isBlank())
			return;
		try {
			String json = MAPPER.writeValueAsString(row) + System.lineSeparator();
			Path path = Path.of(directory).resolve("forced-state-"
				+ ProcessHandle.current().pid() + ".jsonl");
			synchronized(WRITE_LOCK) {
				Files.createDirectories(path.getParent());
				Files.writeString(path, json, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
		catch(IOException ex) {
			throw new IllegalStateException("Unable to record forced-state audit", ex);
		}
	}
}
