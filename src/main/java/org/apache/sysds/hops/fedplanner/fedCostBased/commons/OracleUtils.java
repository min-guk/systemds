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
import java.util.Map;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;

public final class OracleUtils {
	public static final class OracleDecision {
		private final List<FType> alignedInputFTypes;
		private final OpCaps caps;
		private final FType logicalFType;

		private OracleDecision(List<FType> alignedInputFTypes, OpCaps caps, FType logicalFType) {
			this.alignedInputFTypes = alignedInputFTypes;
			this.caps = caps;
			this.logicalFType = logicalFType;
		}

		public List<FType> alignedInputFTypes() {
			return alignedInputFTypes;
		}

		public OpCaps caps() {
			return caps;
		}

		public FType logicalFType() {
			return logicalFType;
		}
	}

	private OracleUtils() {
		// utility class
	}

	public static OracleDecision decideWithOracle(Hop hop, Privacy privacy, List<Hop> collectedHops,
			List<FType> collectedFTypes, OracleFacade oracleFacade, Map<List<FType>, OpCaps> oracleCache,
			Map<Long, List<Hop>> rewireTable) {
		List<FType> alignedInputFTypes = alignInputFTypes(hop, collectedHops, collectedFTypes);
		OpCaps caps = null;

		if (oracleFacade != null) {
			if (oracleCache != null) {
				caps = oracleCache.computeIfAbsent(alignedInputFTypes, k -> {
					OpCaps decision = oracleFacade.decide(hop, k);
					FederatedPlannerLogger.logOracleDecision(hop, privacy, k, decision, rewireTable);
					return decision;
				});
			} else {
				caps = oracleFacade.decide(hop, alignedInputFTypes);
				if (caps != null) {
					FederatedPlannerLogger.logOracleDecision(hop, privacy, alignedInputFTypes, caps, rewireTable);
				}
			}
		}

		FType logicalFType = null;
		if (caps != null && caps.foutFType().isPresent()) {
			logicalFType = caps.foutFType().get();
		}

		return new OracleDecision(alignedInputFTypes, caps, logicalFType);
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
