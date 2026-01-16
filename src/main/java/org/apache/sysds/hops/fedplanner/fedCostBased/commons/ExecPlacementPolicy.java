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

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

public final class ExecPlacementPolicy {
	public static final class Decision {
		public boolean allowCP_LOUT;
		public boolean allowCP_FOUT;
		public boolean allowFED_LOUT;
		public boolean allowFED_FOUT;

		public boolean hasAny() {
			return allowCP_LOUT || allowCP_FOUT || allowFED_LOUT || allowFED_FOUT;
		}
	}

	private ExecPlacementPolicy() {
		// utility class
	}

	public static Decision decide(Hop hop, Privacy privacy, FType fType, OpCaps caps) {
		ExecType oracleExec = (caps != null) ? caps.exec() : ExecType.CP;
		FederatedOutput placement = (caps != null) ? caps.placement() : FederatedOutput.LOUT;

		Decision decision = new Decision();

		switch (privacy) {
			case PRIVATE:
			case PRIVATE_AGGREGATE:
				// FED/FOUT only (oracleExec == FED && placement == FOUT)
				if (oracleExec == ExecType.FED && placement == FederatedOutput.FOUT) {
					decision.allowFED_FOUT = true;
				}
				break;
			case PRIVATE_AGGREGATE_TO_PUBLIC:
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						decision.allowFED_FOUT = true;
					}
					decision.allowFED_LOUT = true;
				}
				break;
			case PUBLIC:
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						decision.allowFED_FOUT = true;
					}
					decision.allowFED_LOUT = true;
				}

				if (allowCpFout(hop, fType)) {
					decision.allowCP_FOUT = true;
				}
				decision.allowCP_LOUT = true;
				break;
			default:
				// Keep the decision empty for unsupported privacy levels.
				break;
		}

		return decision;
	}

	private static boolean allowCpFout(Hop hop, FType fType) {
		if (hop == null || !hop.getDataType().isMatrix()) {
			return false;
		}
		return true;
	}
}
