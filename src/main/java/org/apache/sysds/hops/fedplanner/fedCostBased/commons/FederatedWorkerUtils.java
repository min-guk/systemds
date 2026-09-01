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

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;

public final class FederatedWorkerUtils {
	private FederatedWorkerUtils() {
		// utility class
	}

	public static int countDistinctWorkers(List<Pair<FederatedRange, FederatedData>> fedMap) {
		if (fedMap == null || fedMap.isEmpty()) {
			return 0;
		}
		Set<InetSocketAddress> workerAddrs = new HashSet<>();
		for (Pair<FederatedRange, FederatedData> p : fedMap) {
			FederatedData data = p.getRight();
			if (data != null && data.getAddress() != null) {
				workerAddrs.add(data.getAddress());
			}
		}
		return workerAddrs.size();
	}
}

