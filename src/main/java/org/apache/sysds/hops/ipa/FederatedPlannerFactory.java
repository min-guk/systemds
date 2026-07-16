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

package org.apache.sysds.hops.ipa;

import org.apache.sysds.hops.fedplanner.AFederatedPlanner;
import org.apache.sysds.hops.fedplanner.FTypes.FederatedPlanner;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAll;
import org.apache.sysds.hops.fedplanner.fedAll.FederatedPlannerFedAllMaxFedFoutSinglePass;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.FederatedPlannerDpFedCostBased;
import org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.FederatedPlanMinSTCut;
import org.apache.sysds.hops.fedplanner.fedHeuristic.FederatedPlannerFedHeuristic;

public final class FederatedPlannerFactory {
	private FederatedPlannerFactory() {
		// utility class
	}

	public static AFederatedPlanner create(FederatedPlanner planner) {
		switch(planner) {
			case NONE:
				return null;
			case RUNTIME:
				return null;
			case COMPILE_FED_ALL:
				return new FederatedPlannerFedAll();
			case COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS:
				return new FederatedPlannerFedAllMaxFedFoutSinglePass();
			case COMPILE_FED_HEURISTIC:
				return new FederatedPlannerFedHeuristic();
			case COMPILE_COST_BASED:
				return new FederatedPlannerDpFedCostBased();
			case COMPILE_MIN_ST_CUT:
				return new FederatedPlanMinSTCut();
		}
		throw new IllegalStateException("Unreachable planner value");
	}
}
