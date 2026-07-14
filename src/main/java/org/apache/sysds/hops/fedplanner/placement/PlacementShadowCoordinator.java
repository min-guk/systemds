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
			return new Session(null, java.util.List.of(), t);
		}
	}

	public static final class Session {
		private final NeutralPlacementGraph baseline;
		private final java.util.List<String> selectedBefore;
		private final Throwable failure;
		private Session(NeutralPlacementGraph baseline, java.util.List<String> selectedBefore, Throwable failure) {
			this.baseline = baseline; this.selectedBefore = selectedBefore; this.failure = failure;
		}
		public NeutralPlacementGraph graph() { return baseline; }
		public Throwable failure() { return failure; }
		public Observation observe(DMLProgram program) {
			if(baseline == null) return new Observation(null, selectedBefore, java.util.List.of(), failure);
			try {
				NeutralPlacementGraphBuilder builder = new NeutralPlacementGraphBuilder();
				PlacementShadowComparator.Diff diff = new PlacementShadowComparator().compareProductionSurfaces(
					baseline, builder.build(program));
				java.util.List<String> selectedAfter = builder.selectedProjection(program);
				if(!diff.isEmpty()) LOG.debug("Neutral placement shadow observed normalized differences: " + diff);
				return new Observation(diff, selectedBefore, selectedAfter, null);
			}
			catch(Throwable t) {
				LOG.warn("Neutral placement shadow comparison failed without affecting planner selection", t);
				return new Observation(null, selectedBefore, java.util.List.of(), t);
			}
		}
	}

	public record Observation(PlacementShadowComparator.Diff graphDiff, java.util.List<String> selectedBefore,
		java.util.List<String> selectedAfter, Throwable failure) {
		public boolean successful() { return failure == null && graphDiff != null; }
	}
}
