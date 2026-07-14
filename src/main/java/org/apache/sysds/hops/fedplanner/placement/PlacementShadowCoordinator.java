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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.parser.DMLProgram;

/** Central observational shadow lifecycle around the existing planner. */
public final class PlacementShadowCoordinator {
	private static final Log LOG = LogFactory.getLog(PlacementShadowCoordinator.class);
	private PlacementShadowCoordinator() { }

	public static Session begin(DMLProgram program) {
		try {
			NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
			return new Session(builder.build(program), builder.selectedProjection(program), null);
		}
		catch(Throwable t) {
			LOG.warn("Neutral placement shadow analysis failed without affecting planner selection", t);
			return new Session(null, safeSelectedProjection(program), Failure.of("begin", t));
		}
	}

	public static final class Session {
		private final NeutralPlacementGraph baseline;
		private final java.util.List<String> selectedBefore;
		private final Failure failure;
		private Session(NeutralPlacementGraph baseline, java.util.List<String> selectedBefore, Failure failure) {
			this.baseline = baseline; this.selectedBefore = selectedBefore; this.failure = failure;
		}
		public NeutralPlacementGraph graph() { return baseline; }
		public Failure failure() { return failure; }
		public Observation observe(DMLProgram program) {
			if(baseline == null) return new Observation(null, selectedBefore, java.util.List.of(), java.util.List.of(), failure);
			try {
				NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
				PlacementShadowComparator.Diff diff = new PlacementShadowComparator().compareProductionSurfaces(
					baseline, builder.build(program));
				java.util.List<String> selectedAfter = builder.selectedProjection(program);
				if(!diff.isEmpty()) LOG.debug("Neutral placement shadow observed normalized differences: " + diff);
				return new Observation(diff, selectedBefore, selectedAfter, delta(selectedBefore, selectedAfter),
					builder.selectedMembershipViolations(program, baseline), null);
			}
			catch(Throwable t) {
				LOG.warn("Neutral placement shadow comparison failed without affecting planner selection", t);
				java.util.List<String> selectedAfter = safeSelectedProjection(program);
				return new Observation(null, selectedBefore, selectedAfter, delta(selectedBefore, selectedAfter),
					java.util.List.of(), Failure.of("observe", t));
			}
		}
	}

	private static java.util.List<String> safeSelectedProjection(DMLProgram program) {
		try { return new NeutralPlacementGraphBuilder().selectedProjection(program); }
		catch(Throwable ignored) { return java.util.List.of(); }
	}

	private static java.util.List<String> delta(java.util.List<String> before, java.util.List<String> after) {
		java.util.Map<String,Integer> counts = new java.util.TreeMap<>();
		for(String value : before) counts.merge(value, 1, Integer::sum);
		for(String value : after) counts.merge(value, -1, Integer::sum);
		java.util.List<String> result = new java.util.ArrayList<>();
		counts.forEach((value, count) -> {
			for(int i = 0; i < count; i++) result.add("-" + value);
			for(int i = 0; i > count; i--) result.add("+" + value);
		});
		return java.util.Collections.unmodifiableList(result);
	}

	public record Failure(String phase, String type, String message, java.util.List<String> causeChain) {
		static Failure of(String phase, Throwable failure) {
			java.util.List<String> causes = new java.util.ArrayList<>();
			for(Throwable cause = failure; cause != null; cause = cause.getCause())
				causes.add(cause.getClass().getName() + ":" + String.valueOf(cause.getMessage()));
			return new Failure(phase, failure.getClass().getName(), String.valueOf(failure.getMessage()), causes);
		}
		public Failure { causeChain = java.util.List.copyOf(causeChain); }
	}

	public record Observation(PlacementShadowComparator.Diff graphDiff, java.util.List<String> selectedBefore,
		java.util.List<String> selectedAfter, java.util.List<String> selectedProjectionDiff,
		java.util.List<String> selectedMembershipViolations, Failure failure) {
		public boolean successful() { return failure == null && graphDiff != null; }
	}
}
