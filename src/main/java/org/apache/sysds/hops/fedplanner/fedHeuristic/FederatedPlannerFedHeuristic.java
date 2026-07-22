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

package org.apache.sysds.hops.fedplanner.fedHeuristic;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.PlacementEmissionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HeuristicPolicyFacts;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.PlacementPlannerAdapter;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

/** Heuristic policy owner; plan application remains a separate, explicitly authorized phase. */
public class FederatedPlannerFedHeuristic extends AFederatedPlanner {
	private final HeuristicPlacementAdapter adapter = new HeuristicPlacementAdapter();

	public record InvocationCounters(int selectionCount, int internalAnalysisBuildCount,
		int legacyRouteCount, int repairCount, int fallbackCount, int mutationCount,
		int applicationCount, int doubleApplicationCount) {
		public InvocationCounters {
			if(selectionCount != 1 || internalAnalysisBuildCount != 0 || legacyRouteCount != 0
				|| repairCount != 0 || fallbackCount != 0 || mutationCount != 0
				|| applicationCount != 1 || doubleApplicationCount != 0)
				throw new IllegalArgumentException("FedHeuristic selection receipt counters differ");
		}
	}

	public record HeuristicInvocationReceipt(PlacementAnalysis analysis, HeuristicPolicyFacts policyFacts,
		Set<ValueVersionKey> markers, HeuristicPlacementAdapter.Result result, InvocationCounters counters,
		String analysisFingerprintBefore, String analysisFingerprintAfter,
		NormalizedPlannerResult normalizedResult, PlacementEmissionReceipt emissionReceipt)
		implements AFederatedPlanner.PlannerInvocationReceipt {
		public HeuristicInvocationReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(policyFacts, "policyFacts");
			Objects.requireNonNull(markers, "markers");
			Objects.requireNonNull(result, "result");
			Objects.requireNonNull(counters, "counters");
			Objects.requireNonNull(analysisFingerprintBefore, "analysisFingerprintBefore");
			Objects.requireNonNull(analysisFingerprintAfter, "analysisFingerprintAfter");
			Objects.requireNonNull(normalizedResult, "normalizedResult");
			Objects.requireNonNull(emissionReceipt, "emissionReceipt");
			if(policyFacts != analysis.heuristicPolicyFacts())
				throw new IllegalArgumentException("FedHeuristic policy facts identity differs");
			if(result.analysis() != analysis)
				throw new IllegalArgumentException("FedHeuristic receipt producer identity differs");
			if(!analysis.analysisFingerprint().equals(analysisFingerprintBefore)
				|| !analysisFingerprintBefore.equals(analysisFingerprintAfter)
				|| !analysisFingerprintBefore.equals(result.analysisFingerprint()))
				throw new IllegalArgumentException("Supplied analysis changed during FedHeuristic selection");
			if(normalizedResult.analysis() != analysis
				|| !PlacementEmissionTransaction.canonicalPlanHash(normalizedResult).equals(emissionReceipt.planHash()))
				throw new IllegalArgumentException("FedHeuristic normalized result and emission receipt differ");
		}
	}

	public HeuristicPlacementAdapter.Result select(PlacementAnalysis analysis, Set<ValueVersionKey> markers) {
		return adapter.select(analysis, markers);
	}

	@Override
	public HeuristicInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertCanonicalProgramAuthority(prog);
		HeuristicPolicyFacts policyFacts = analysis.heuristicPolicyFacts();
		Set<ValueVersionKey> markers = Collections.unmodifiableSet(new LinkedHashSet<>(
			policyFacts.demotions().stream().map(fact -> fact.valueVersion()).toList()));
		String fingerprintBefore = analysis.analysisFingerprint();
		HeuristicPlacementAdapter.Result result = select(analysis, markers);
		NormalizedPlannerResult normalized = PlacementPlannerAdapter.normalize(analysis, result);
		PlacementEmissionReceipt emission = PlacementEmissionTransaction.emit(prog, normalized,
			PlacementEmissionTransaction.FailureInjector.none());
		InvocationCounters counters = new InvocationCounters(1, 0, 0, 0, 0, 0, 1, 0);
		return new HeuristicInvocationReceipt(analysis, policyFacts, markers, result, counters,
			fingerprintBefore, analysis.analysisFingerprint(), normalized, emission);
	}

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		throw new UnsupportedOperationException(
			"FedHeuristic requires a supplied placement analysis before plan application");
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		throw new UnsupportedOperationException(
			"FedHeuristic requires a supplied placement analysis before plan application");
	}
}
