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

package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.cost.ComputeCost;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

public final class FederatedCostModel {
	// Default values are used as reasonable estimates since we only need to compare
	// relative costs between different federated plans.
	// Memory bandwidth for local computations (25 GB/s).
	private static final double DEFAULT_MBS_MEMORY_BANDWIDTH = 25000.0;
	// Network bandwidth for data transfers between federated sites (1 Gbps).
	private static final double DEFAULT_MBS_NETWORK_BANDWIDTH = 125.0;
	// Network latency between federated sites (1 ms).
	private static final double DEFAULT_MBS_NETWORK_LATENCY = 0.001;

	private FederatedCostModel() {
		// utility class
	}

	public static double computeOpCost(Hop currentHop) {
		double computeCost = ComputeCost.getHOPComputeCost(currentHop);
		double inputAccessCost = computeMemoryAccessCost(currentHop.getInputMemEstimate());
		double outputAccessCost = computeMemoryAccessCost(currentHop.getOutputMemEstimate());

		// Total cost assumes:
		// 1) Computation and input access can overlap (take max)
		// 2) Output access must wait for both (add)
		return Math.max(computeCost, inputAccessCost) + outputAccessCost;
	}

	public static double computeMemoryAccessCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		return memSize / (1024 * 1024) / DEFAULT_MBS_MEMORY_BANDWIDTH;
	}

	public static double computeNetworkCost(double memSize) {
		return DEFAULT_MBS_NETWORK_LATENCY + (memSize / (1024 * 1024) / DEFAULT_MBS_NETWORK_BANDWIDTH);
	}

	public static double computeDownloadNetworkCost(double memSize) {
		if (memSize <= 0)
			return 0.0;
		return computeNetworkCost(memSize);
	}

	public static double computeUploadNetworkCost(double memSize, FType fType, int numWorkers) {
		if (memSize <= 0)
			return 0.0;
		double multiplier = (fType == FType.FULL || fType == FType.BROADCAST)
				? Math.max(1, numWorkers)
				: 1.0;
		return computeNetworkCost(memSize * multiplier);
	}

	public static double computeRefedNetworkCost(double memSize, FType fType, int numWorkers) {
		return computeUploadNetworkCost(memSize, fType, numWorkers);
	}
}

