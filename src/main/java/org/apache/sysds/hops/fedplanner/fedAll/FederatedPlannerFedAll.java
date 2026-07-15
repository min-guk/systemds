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

import java.util.List;
import java.util.Map;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
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

	public FedAllPlacementAdapter.Result select(PlacementAnalysis analysis) {
		return adapter.select(analysis);
	}

	/** Temporary compatibility surface; the authorized Heuristic adapter stage removes it. */
	protected FType getFederatedOut(Hop hop, Map<Long, FType> fedHops,
		Map<Long, List<Hop>> rewireTable) {
		return super.getFederatedOut(hop, fedHops);
	}

	/** Temporary compatibility surface; the authorized Heuristic adapter stage removes it. */
	protected FType getPropagatedFType(Hop hop, FType outFType) {
		return outFType;
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
