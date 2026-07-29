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

import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FederatedRefedPolicy;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.parser.FunctionStatementBlock;
import org.apache.sysds.runtime.controlprogram.LocalVariableMap;

/**
 * FedAll policy with early consolidation of row/column vector matrix-multiply
 * outputs. Both the FOUT candidate and the LOUT alternative are validated by
 * the common rule oracle.
 */
public class FederatedPlannerFedHeuristic extends FederatedPlannerFedAll {
	@Override
	public void rewriteProgram(org.apache.sysds.parser.DMLProgram prog,
		org.apache.sysds.hops.ipa.FunctionCallGraph fgraph,
		org.apache.sysds.hops.ipa.FunctionCallSizeInfo fcallSizes) {
		FederatedRefedPolicy.clearHeuristicDemotedHops();
		super.rewriteProgram(prog, fgraph, fcallSizes);
	}

	@Override
	public void rewriteFunctionDynamic(FunctionStatementBlock function, LocalVariableMap funcArgs) {
		FederatedRefedPolicy.clearHeuristicDemotedHops();
		super.rewriteFunctionDynamic(function, funcArgs);
	}

	@Override
	protected boolean prefersLocalOutput(Hop hop, OpCaps preferredCaps) {
		boolean localOutput = false;
		if( hop instanceof AggBinaryOp && preferredCaps.foutFType().isPresent() ) {
			switch( preferredCaps.foutFType().get() ) {
				case ROW:
					localOutput = hop.getDim2() == 1;
					break;
				case COL:
					localOutput = hop.getDim1() == 1;
					break;
				default:
					break;
			}
		}

		if( localOutput )
			FederatedRefedPolicy.markHeuristicDemotedHop(hop.getHopID());
		else
			FederatedRefedPolicy.unmarkHeuristicDemotedHop(hop.getHopID());
		return localOutput;
	}
}
