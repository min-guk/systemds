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
		try { return new Session(new NeutralPlacementGraphBuilder().build(program), null); }
		catch(Throwable t) {
			LOG.warn("Neutral placement shadow analysis failed without affecting planner selection", t);
			return new Session(null, t);
		}
	}

	public static final class Session {
		private final NeutralPlacementGraph baseline;
		private final Throwable failure;
		private Session(NeutralPlacementGraph baseline, Throwable failure) { this.baseline = baseline; this.failure = failure; }
		public NeutralPlacementGraph graph() { return baseline; }
		public Throwable failure() { return failure; }
		public PlacementShadowComparator.Diff observe(DMLProgram program) {
			if(baseline == null) return null;
			try {
				PlacementShadowComparator.Diff diff = new PlacementShadowComparator().compare(
					baseline, new NeutralPlacementGraphBuilder().build(program));
				if(!diff.isEmpty()) LOG.debug("Neutral placement shadow observed normalized differences: " + diff);
				return diff;
			}
			catch(Throwable t) {
				LOG.warn("Neutral placement shadow comparison failed without affecting planner selection", t);
				return null;
			}
		}
	}
}
