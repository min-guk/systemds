/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.placement.adapter;

import java.util.Objects;

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;

/** Immutable planner input bound to one already constructed placement analysis. */
public record PlannerPlacementContext(PlacementAnalysis analysis, String analysisFingerprint) {
	public PlannerPlacementContext {
		Objects.requireNonNull(analysis, "analysis");
		Objects.requireNonNull(analysisFingerprint, "analysisFingerprint");
		if(!analysis.analysisFingerprint().equals(analysisFingerprint))
			throw new IllegalArgumentException("analysis fingerprint does not match supplied analysis");
	}

	public static PlannerPlacementContext of(PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		return new PlannerPlacementContext(analysis, analysis.analysisFingerprint());
	}
}
