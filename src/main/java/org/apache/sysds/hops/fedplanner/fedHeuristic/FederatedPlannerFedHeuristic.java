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

import java.util.Set;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.HeuristicPlacementAdapter;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

/** Heuristic policy owner; plan application remains a separate, explicitly authorized phase. */
public class FederatedPlannerFedHeuristic extends AFederatedPlanner {
	private final HeuristicPlacementAdapter adapter = new HeuristicPlacementAdapter();

	public HeuristicPlacementAdapter.Result select(PlacementAnalysis analysis, Set<ValueVersionKey> markers) {
		return adapter.select(analysis, markers);
	}

	@Override
	public void rewriteProgram(org.apache.sysds.parser.DMLProgram prog,
		org.apache.sysds.hops.ipa.FunctionCallGraph fgraph,
		org.apache.sysds.hops.ipa.FunctionCallSizeInfo fcallSizes) {
		throw new UnsupportedOperationException(
			"FedHeuristic requires a supplied placement analysis before plan application");
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		throw new UnsupportedOperationException(
			"FedHeuristic requires a supplied placement analysis before plan application");
	}
}
