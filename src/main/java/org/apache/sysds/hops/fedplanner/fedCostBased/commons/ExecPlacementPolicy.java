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

import java.util.List;

import org.apache.sysds.common.Types;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
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

		if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.FEDERATED) {
			decision.allowFED_FOUT = true;
			return decision;
		}

		if (HopUtils.isPrintOrPWrite(hop)) {
			decision.allowCP_LOUT = true;
			return decision;
		}

		if (isMultiReturnBuiltinHop(hop) || isFunctionOutputFromMultiReturn(hop)) {
			// Multi-return builtins (e.g., eigen) have no runtime FED instruction, and their
			// FunctionOutput hops must stay local as well. Allowing CP->FOUT on outputs can
			// still push FED inputs into the builtin call and trigger invalid FED instructions.
			decision.allowCP_LOUT = true;
			return decision;
		}

		switch (privacy) {
			case PRIVATE:
				// FED/FOUT only (oracleExec == FED && placement == FOUT)
				if (oracleExec == ExecType.FED && placement == FederatedOutput.FOUT) {
					decision.allowFED_FOUT = true;
				}
				break;
			case PRIVATE_AGGREGATE:
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT)
						decision.allowFED_FOUT = true;
					else
						decision.allowFED_LOUT = true;
				}
				else if (oracleExec == ExecType.CP) {
					decision.allowCP_LOUT = true;
				}
				if (hop instanceof DataOp && ((DataOp) hop).getOp() == Types.OpOpData.TRANSIENTWRITE)
					decision.allowCP_LOUT = true;
				break;
			case PRIVATE_AGGREGATE_TO_PUBLIC:
				if (oracleExec == ExecType.FED) {
					if (placement == FederatedOutput.FOUT) {
						decision.allowFED_FOUT = true;
					}
					decision.allowFED_LOUT = true;
				}
				else if (oracleExec == ExecType.CP) {
					decision.allowCP_LOUT = true;
				}
				if (allowCpFout(hop, fType)) {
					decision.allowCP_FOUT = true;
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

		if (hop instanceof DataOp) {
			Types.OpOpData op = ((DataOp) hop).getOp();
			if (op == Types.OpOpData.TRANSIENTREAD) {
				// TRANSIENTREAD placement is resolved via its corresponding TRANSIENTWRITE(s) in the
				// DP rewire table. Do not allow standalone CP->FOUT / FED->LOUT candidates here as they
				// can create inconsistent transient read/write pairings.
				decision.allowCP_FOUT = false;
				decision.allowFED_LOUT = false;
			}
			else if (op == Types.OpOpData.TRANSIENTWRITE) {
				// Do NOT close the candidate space for TRANSIENTWRITE:
				// - CP->FOUT is a valid, runtime-supported materialization (fed_fout/refed) used to
				//   persist a local transient as federated for downstream consumers (e.g., X_samples in kmeans).
				// - FED->LOUT does not represent a meaningful transient-write placement; downloads belong at
				//   parent boundaries, not at the write itself.
				decision.allowFED_LOUT = false;
			}
		}

		return decision;
	}

	private static boolean isMultiReturnBuiltinHop(Hop hop) {
		return hop instanceof FunctionOp
				&& ((FunctionOp) hop).getFunctionType() == FunctionType.MULTIRETURN_BUILTIN;
	}

	private static boolean isFunctionOutputFromMultiReturn(Hop hop) {
		if (!(hop instanceof DataOp) || ((DataOp) hop).getOp() != Types.OpOpData.FUNCTIONOUTPUT)
			return false;
		List<Hop> inputs = hop.getInput();
		if (inputs == null || inputs.isEmpty() || inputs.get(0) == null)
			return false;
		List<Hop> parents = inputs.get(0).getParent();
		if (parents == null || parents.isEmpty())
			return false;
		for (Hop parent : parents) {
			if (parent instanceof FunctionOp
					&& ((FunctionOp) parent).getFunctionType() == FunctionType.MULTIRETURN_BUILTIN)
				return true;
		}
		return false;
	}

	private static boolean isTransientDataOp(Hop hop) {
		if (!(hop instanceof DataOp)) {
			return false;
		}
		Types.OpOpData op = ((DataOp) hop).getOp();
		return op == Types.OpOpData.TRANSIENTREAD || op == Types.OpOpData.TRANSIENTWRITE;
	}

	private static boolean allowCpFout(Hop hop, FType fType) {
		if (hop == null || !hop.getDataType().isMatrix()) {
			return false;
		}
		return true;
	}
}
