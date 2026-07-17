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
package org.apache.sysds.hops.fedplanner.fedAll;

import java.util.Objects;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.ipa.FunctionCallGraph;
import org.apache.sysds.hops.ipa.FunctionCallSizeInfo;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

/** FedAll policy owner; plan application remains a separate, explicitly authorized phase. */
public class FederatedPlannerFedAll extends AFederatedPlanner {
	private final FedAllPlacementAdapter adapter = new FedAllPlacementAdapter();

	public record InvocationCounters(int selectionCount, int internalAnalysisBuildCount,
		int legacyRouteCount, int repairCount, int fallbackCount, int mutationCount,
		int applicationCount, int doubleApplicationCount) {
		public InvocationCounters {
			if(selectionCount != 1 || internalAnalysisBuildCount != 0 || legacyRouteCount != 0
				|| repairCount != 0 || fallbackCount != 0 || mutationCount != 0
				|| applicationCount != 0 || doubleApplicationCount != 0)
				throw new IllegalArgumentException("FedAll selection receipt counters differ");
		}
	}

	public record FedAllInvocationReceipt(PlacementAnalysis analysis, FedAllPlacementAdapter.Result result,
		InvocationCounters counters, String analysisFingerprintBefore, String analysisFingerprintAfter)
		implements AFederatedPlanner.PlannerInvocationReceipt {
		public FedAllInvocationReceipt {
			Objects.requireNonNull(analysis, "analysis");
			Objects.requireNonNull(result, "result");
			Objects.requireNonNull(counters, "counters");
			Objects.requireNonNull(analysisFingerprintBefore, "analysisFingerprintBefore");
			Objects.requireNonNull(analysisFingerprintAfter, "analysisFingerprintAfter");
			if(result.analysis() != analysis)
				throw new IllegalArgumentException("FedAll receipt producer identity differs");
			if(!analysis.analysisFingerprint().equals(analysisFingerprintBefore)
				|| !analysisFingerprintBefore.equals(analysisFingerprintAfter)
				|| !analysisFingerprintBefore.equals(result.analysisFingerprint()))
				throw new IllegalArgumentException("Supplied analysis changed during FedAll selection");
		}
	}

	public FedAllPlacementAdapter.Result select(PlacementAnalysis analysis) {
		return adapter.select(analysis);
	}

	@Override
	public FedAllInvocationReceipt rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph,
		FunctionCallSizeInfo fcallSizes, PlacementAnalysis analysis) {
		Objects.requireNonNull(analysis, "analysis");
		analysis.assertProgramOwner(prog);
		String fingerprintBefore = analysis.analysisFingerprint();
		FedAllPlacementAdapter.Result result = select(analysis);
		InvocationCounters counters = new InvocationCounters(1, 0, 0, 0, 0, 0, 0, 0);
		return new FedAllInvocationReceipt(analysis, result, counters,
			fingerprintBefore, analysis.analysisFingerprint());
	}

	@Override
	public void rewriteProgram(DMLProgram prog, FunctionCallGraph fgraph, FunctionCallSizeInfo fcallSizes) {
		throw new UnsupportedOperationException(
			"FedAll requires a supplied placement analysis before plan application");
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		throw new UnsupportedOperationException(
			"FedAll requires a supplied placement analysis before plan application");
	}
}
