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

import java.util.HashMap;
import java.util.Map;

import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

public class FederatedPlannerFedHeuristic extends FederatedPlannerFedAll {
	private final Map<Long, FType> heuristicFallbackFTypes = new HashMap<>();

	@Override
	public void rewriteProgram(org.apache.sysds.parser.DMLProgram prog,
		org.apache.sysds.hops.ipa.FunctionCallGraph fgraph,
		org.apache.sysds.hops.ipa.FunctionCallSizeInfo fcallSizes) {
		heuristicFallbackFTypes.clear();
		FederatedRefedPolicy.clearHeuristicDemotedHops();
		super.rewriteProgram(prog, fgraph, fcallSizes);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		heuristicFallbackFTypes.clear();
		FederatedRefedPolicy.clearHeuristicDemotedHops();
		super.rewriteFunctionDynamic(function, funcArgs);
	}

	@Override
	protected FType getFederatedOut(Hop hop, Map<Long, FType> fedHops) {
		FType inferred = super.getFederatedOut(hop, fedHops); // FedAll
		FType ret = applyHeuristics(hop, inferred);
		recordHeuristicFallback(hop, inferred, ret);
		return ret;
	}

	@Override
	protected FType getFederatedOut(Hop hop, Map<Long, FType> fedHops,
		Map<Long, java.util.List<Hop>> rewireTable) {
		FType inferred = super.getFederatedOut(hop, fedHops, rewireTable); // FedAll
		FType ret = applyHeuristics(hop, inferred);
		recordHeuristicFallback(hop, inferred, ret);
		return ret;
	}

	@Override
	protected FType getPropagatedFType(Hop hop, FType outFType) {
		return outFType;
	}

	private void recordHeuristicFallback(Hop hop, FType inferred, FType ret) {
		if( hop == null )
			return;
		if( ret == null && inferred != null ) {
			heuristicFallbackFTypes.put(hop.getHopID(), inferred);
			FederatedRefedPolicy.markHeuristicDemotedHop(hop.getHopID());
		}
		else {
			heuristicFallbackFTypes.remove(hop.getHopID());
			FederatedRefedPolicy.unmarkHeuristicDemotedHop(hop.getHopID());
		}
	}

	private static FType applyHeuristics(Hop hop, FType ret) {
		
		//apply operator-specific heuristics
		if( hop instanceof AggBinaryOp) {
			if( (ret == FType.ROW && hop.getDim2()==1) 
				|| (ret == FType.COL && hop.getDim1()==1) )
			{
				ret = null; //get local vectors
			}
		}
		
		return ret;
	}
}
