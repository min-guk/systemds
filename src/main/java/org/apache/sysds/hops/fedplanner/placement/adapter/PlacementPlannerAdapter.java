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

/** Mutation-free boundary between a shared placement analysis and planner policy. */
public interface PlacementPlannerAdapter<R extends NormalizedPlannerResult> {
	R select(PlannerPlacementContext context);

	default NormalizedPlannerResult select(PlacementAnalysis analysis) {
		PlannerPlacementContext context = PlannerPlacementContext.of(analysis);
		R result = Objects.requireNonNull(select(context), "planner result");
		if(result.analysis() != context.analysis())
			throw new IllegalStateException("planner result analysis identity does not match supplied analysis");
		if(!context.analysisFingerprint().equals(result.analysisFingerprint()))
			throw new IllegalStateException("planner result fingerprint does not match supplied analysis");
		Objects.requireNonNull(result.plannerId(), "plannerId");
		Objects.requireNonNull(result.selectedStates(), "selectedStates");
		Objects.requireNonNull(result.selectedRelocations(), "selectedRelocations");
		Objects.requireNonNull(result.objectiveCertificate(), "objectiveCertificate");
		Objects.requireNonNull(result.normalizedPlanFingerprint(), "normalizedPlanFingerprint");
		return ImmutableNormalizedPlannerResult.of(context, result);
	}
}
