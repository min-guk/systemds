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

import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.selector.PolicyFirstFeasiblePlacementSelector;

/** FedAll policy variant that stops after the first constraint-coherent placement. */
public final class FederatedPlannerFedAllMaxFedFoutSinglePass extends FederatedPlannerFedAll {
	private final FedAllPlacementAdapter adapter = new FedAllPlacementAdapter(
		new PolicyFirstFeasiblePlacementSelector());

	@Override
	public FedAllPlacementAdapter.Result select(PlacementAnalysis analysis) {
		return adapter.select(analysis);
	}
}
