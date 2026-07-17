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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.parser.DMLProgram;

/** Central observational shadow lifecycle around the existing planner. */
public final class PlacementShadowCoordinator {
	private static final Log LOG = LogFactory.getLog(PlacementShadowCoordinator.class);
	private static final AtomicReference<Observation> LAST_RECORDED = new AtomicReference<>();
	private static final ShadowAnalysis PRODUCTION_ANALYSIS = new ShadowAnalysis() {
		@Override
		public NeutralPlacementGraph build(DMLProgram program) {
			return new NeutralPlacementGraphBuilder().buildAnalysis(program).graph();
		}

		@Override
		public List<String> selectedProjection(DMLProgram program) {
			return new NeutralPlacementGraphBuilder().selectedProjection(program);
		}

		@Override
		public List<String> selectedMembershipViolations(DMLProgram program, NeutralPlacementGraph graph) {
			return new NeutralPlacementGraphBuilder().selectedMembershipViolations(program, graph);
		}
	};

	private PlacementShadowCoordinator() {
		// utility class
	}

	public static Session begin(DMLProgram program) {
		return begin(program, PRODUCTION_ANALYSIS);
	}

	public static Session begin(DMLProgram program, PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis").assertProgramOwner(program);
		NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
		ShadowAnalysis shadowAnalysis = new ShadowAnalysis() {
			@Override public NeutralPlacementGraph build(DMLProgram ignored) { return builder.build(program); }
			@Override public List<String> selectedProjection(DMLProgram ignored) { return builder.selectedProjection(program); }
			@Override public List<String> selectedMembershipViolations(DMLProgram ignored, NeutralPlacementGraph graph) {
				return builder.selectedMembershipViolations(program, graph);
			}
		};
		try {
			return new Session(analysis.graph(), shadowAnalysis.selectedProjection(program), null, shadowAnalysis);
		}
		catch(Throwable failure) {
			return new Session(null, List.of(), Failure.of("begin", failure), PRODUCTION_ANALYSIS);
		}
	}

	static Session begin(DMLProgram program, ShadowAnalysis analysis) {
		try {
			return new Session(analysis.build(program), analysis.selectedProjection(program), null, analysis);
		}
		catch(Throwable failure) {
			return new Session(null, safeSelectedProjection(program, analysis), Failure.of("begin", failure), analysis);
		}
	}

	/**
	 * Persists and emits every completed observation. This method is deliberately observational:
	 * it does not select, repair, mutate, or route a plan.
	 */
	public static void record(Observation observation) {
		LAST_RECORDED.set(observation);
		String evidence = "PLACEMENT_SHADOW|" + observation.normalizedEvidence();
		if(observation.successful())
			LOG.info(evidence);
		else
			LOG.warn(evidence);
	}

	public static Observation lastRecordedObservation() {
		return LAST_RECORDED.get();
	}

	static void clearRecordedObservationForTesting() {
		LAST_RECORDED.set(null);
	}

	interface ShadowAnalysis {
		NeutralPlacementGraph build(DMLProgram program);
		List<String> selectedProjection(DMLProgram program);
		List<String> selectedMembershipViolations(DMLProgram program, NeutralPlacementGraph graph);
	}

	public static final class Session {
		private final NeutralPlacementGraph _baseline;
		private final List<String> _selectedBefore;
		private final Failure _failure;
		private final ShadowAnalysis _analysis;

		private Session(NeutralPlacementGraph baseline, List<String> selectedBefore, Failure failure,
			ShadowAnalysis analysis) {
			_baseline = baseline;
			_selectedBefore = List.copyOf(selectedBefore);
			_failure = failure;
			_analysis = analysis;
		}

		public NeutralPlacementGraph graph() {
			return _baseline;
		}

		public Failure failure() {
			return _failure;
		}

		public Observation observe(DMLProgram program) {
			if(_baseline == null)
				return new Observation(null, _selectedBefore, List.of(), List.of(), List.of(), _failure);
			try {
				NeutralPlacementGraph current = _analysis.build(program);
				PlacementShadowComparator.Diff diff = new PlacementShadowComparator()
					.compareProductionSurfaces(_baseline, current);
				List<String> selectedAfter = _analysis.selectedProjection(program);
				return new Observation(diff, _selectedBefore, selectedAfter,
					delta(_selectedBefore, selectedAfter),
					_analysis.selectedMembershipViolations(program, _baseline), null);
			}
			catch(Throwable failure) {
				List<String> selectedAfter = safeSelectedProjection(program, _analysis);
				return new Observation(null, _selectedBefore, selectedAfter,
					delta(_selectedBefore, selectedAfter), List.of(), Failure.of("observe", failure));
			}
		}
	}

	private static List<String> safeSelectedProjection(DMLProgram program, ShadowAnalysis analysis) {
		try {
			return analysis.selectedProjection(program);
		}
		catch(Throwable ignored) {
			return List.of();
		}
	}

	private static List<String> delta(List<String> before, List<String> after) {
		Map<String, Integer> counts = new TreeMap<>();
		for(String value : before)
			counts.merge(value, 1, Integer::sum);
		for(String value : after)
			counts.merge(value, -1, Integer::sum);
		List<String> result = new ArrayList<>();
		counts.forEach((value, count) -> {
			for(int i = 0; i < count; i++)
				result.add("-" + value);
			for(int i = 0; i > count; i--)
				result.add("+" + value);
		});
		Collections.sort(result);
		return Collections.unmodifiableList(result);
	}

	public enum ObservationReason {
		BEGIN_FAILURE,
		OBSERVE_FAILURE,
		GRAPH_SURFACE_DIFF,
		SELECTED_PROJECTION_DIFF,
		SELECTED_MEMBERSHIP_VIOLATION,
		CLEAN
	}

	public record Failure(String phase, String type, String message, List<String> causeChain) {
		static Failure of(String phase, Throwable failure) {
			List<String> causes = new ArrayList<>();
			for(Throwable cause = failure; cause != null; cause = cause.getCause())
				causes.add(cause.getClass().getName() + ":" + String.valueOf(cause.getMessage()));
			return new Failure(phase, failure.getClass().getName(), String.valueOf(failure.getMessage()), causes);
		}

		public Failure {
			causeChain = List.copyOf(causeChain);
		}
	}

	public record Observation(PlacementShadowComparator.Diff graphDiff, List<String> selectedBefore,
		List<String> selectedAfter, List<String> selectedProjectionDiff,
		List<String> selectedMembershipViolations, Failure failure) {
		public Observation {
			selectedBefore = immutable(selectedBefore);
			selectedAfter = immutable(selectedAfter);
			selectedProjectionDiff = immutable(selectedProjectionDiff);
			selectedMembershipViolations = immutable(selectedMembershipViolations);
		}

		public List<ObservationReason> reasons() {
			if(failure != null)
				return List.of("begin".equals(failure.phase()) ? ObservationReason.BEGIN_FAILURE
					: ObservationReason.OBSERVE_FAILURE);
			List<ObservationReason> reasons = new ArrayList<>();
			if(graphDiff != null && !graphDiff.isEmpty())
				reasons.add(ObservationReason.GRAPH_SURFACE_DIFF);
			if(!selectedProjectionDiff.isEmpty())
				reasons.add(ObservationReason.SELECTED_PROJECTION_DIFF);
			if(!selectedMembershipViolations.isEmpty())
				reasons.add(ObservationReason.SELECTED_MEMBERSHIP_VIOLATION);
			if(reasons.isEmpty())
				reasons.add(ObservationReason.CLEAN);
			return Collections.unmodifiableList(reasons);
		}

		public boolean successful() {
			return reasons().equals(List.of(ObservationReason.CLEAN));
		}

		public String normalizedEvidence() {
			List<String> evidence = new ArrayList<>();
			evidence.add("reasons=" + reasons());
			if(graphDiff != null && !graphDiff.isEmpty())
				evidence.addAll(graphDiff.normalizedEvidence());
			if(!selectedProjectionDiff.isEmpty())
				evidence.add("selected.diff=" + selectedProjectionDiff);
			if(!selectedMembershipViolations.isEmpty())
				evidence.add("membership=" + selectedMembershipViolations);
			if(failure != null) {
				evidence.add("failure.phase=" + clean(failure.phase()));
				evidence.add("failure.type=" + clean(failure.type()));
				evidence.add("failure.message=" + clean(failure.message()));
				evidence.add("failure.causes=" + clean(failure.causeChain().toString()));
			}
			return String.join("|", evidence);
		}
	}

	private static List<String> immutable(List<String> values) {
		List<String> copy = new ArrayList<>(values);
		Collections.sort(copy);
		return Collections.unmodifiableList(copy);
	}

	private static String clean(String value) {
		return String.valueOf(value).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
	}
}
