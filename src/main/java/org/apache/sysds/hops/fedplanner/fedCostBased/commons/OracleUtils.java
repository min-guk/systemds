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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;

public final class OracleUtils {
	private OracleUtils() {
		// utility class
	}

	public static List<FType> alignInputFTypes(Hop hop, List<Hop> collectedHops, List<FType> collectedFTypes) {
		if (hop == null) {
			return collectedFTypes;
		}
		List<Hop> parentInputs = hop.getInput();
		int numInputs = parentInputs == null ? 0 : parentInputs.size();
		List<FType> aligned = new ArrayList<>(Collections.nCopies(numInputs, null));
		if (numInputs == 0) {
			return collectedFTypes.isEmpty() ? aligned : new ArrayList<>(collectedFTypes);
		}

		for (int i = 0; i < collectedHops.size(); i++) {
			Hop child = collectedHops.get(i);
			FType ftype = collectedFTypes.get(i);
			if (child == null) {
				FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping null child for hop "
						+ hop.getHopID());
				continue;
			}
			int pos = -1;
			for (int j = 0; j < numInputs; j++) {
				Hop parent = parentInputs.get(j);
				if (parent == null)
					continue;
				if (parent == child || parent.equals(child)) {
					if (aligned.get(j) == null) {
						pos = j;
						break;
					}
				}
			}
			if (pos >= 0) {
				aligned.set(pos, ftype);
			} else {
				FederatedPlannerLogger.logInfoMessage("[alignInputFTypes] Skipping unmatched child "
						+ child.getHopID() + " for hop " + hop.getHopID());
			}
		}
		return aligned;
	}
}
