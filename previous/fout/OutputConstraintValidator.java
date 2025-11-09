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

package org.apache.sysds.hops.fedplanner.fedCostBased.fout;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;

/**
 * Base class for validating LOUT/FOUT (Local/Federated Output) constraints.
 *
 * WHY THIS EXISTS:
 * - FED instruction runtime implementations have hard-coded output strategies
 * - Some force FOUT (only setFedMapping), some force LOUT (only GET_VAR), some support both
 * - Planner needs to know which strategies are feasible before choosing
 *
 * IMPORTANT DESIGN PRINCIPLE:
 * - Validators reflect RUNTIME implementation constraints (not mathematical feasibility)
 * - Check actual FED instruction code to determine what's feasible:
 *   * If runtime only calls setFedMapping() → LOUT=false, FOUT=true
 *   * If runtime only calls GET_VAR + aggregation → LOUT=true, FOUT=false
 *   * If runtime has _fedOut conditional logic → both may be true
 * - If no validator matches → both LOUT and FOUT assumed feasible (default)
 *
 * EXAMPLES WITH LINE NUMBER EVIDENCE:
 * - BinaryMatrixMatrix: FOUT forced (line 103: setOutputFedMapping only)
 * - MMChain: LOUT forced (line 113: GET_VAR + aggAdd only)
 * - AggregateUnary: Both feasible (line 128: conditional _fedOut check)
 */
public abstract class OutputConstraintValidator {

	/**
	 * Check if LOUT (Local Output) is feasible for this operation.
	 *
	 * LOUT is NOT feasible when:
	 * - Runtime implementation FORCES federated output (only calls setFedMapping, no GET_VAR)
	 * - Example: BinaryMatrixMatrixFEDInstruction.java:103 - setOutputFedMapping() only
	 *
	 * LOUT is feasible when:
	 * - Runtime implementation supports local aggregation (calls GET_VAR + aggregation)
	 * - Example: MMChainFEDInstruction.java:113 - GET_VAR + aggAdd()
	 *
	 * @param hop The hop operation
	 * @param inputTypes Input FTypes for conditional checks
	 * @return true if LOUT is possible, false if FOUT is FORCED by runtime implementation
	 */
	public abstract boolean isLOUTFeasible(Hop hop, FType[] inputTypes);

	/**
	 * Check if FOUT (Federated Output) is feasible for this operation.
	 *
	 * FOUT is NOT feasible when:
	 * - Runtime implementation FORCES local output (only calls GET_VAR + aggregation, no setFedMapping)
	 * - Example: MMChainFEDInstruction.java:113,125,140 - aggAdd() only
	 * - Output is scalar (scalars cannot be federated)
	 * - Operation requires consolidation (e.g., AggregateUnaryFEDInstruction.java:233 - VAR)
	 *
	 * FOUT is feasible when:
	 * - Runtime implementation supports federated output (calls setFedMapping)
	 * - Example: BinaryMatrixMatrixFEDInstruction.java:148 - setOutputFedMapping()
	 *
	 * @param hop The hop operation
	 * @param inputTypes Input FTypes for conditional checks
	 * @return true if FOUT is possible, false if LOUT is FORCED by runtime implementation
	 */
	public abstract boolean isFOUTFeasible(Hop hop, FType[] inputTypes);

	/**
	 * Get detailed constraint message explaining feasibility.
	 *
	 * @param hop The hop operation
	 * @param inputTypes Input FTypes
	 * @return Human-readable message with line numbers and reasons
	 */
	public abstract String getConstraintMessage(Hop hop, FType[] inputTypes);

	/**
	 * Checks if this validator should be applied to the given hop operation.
	 * This acts as an OP Type filter.
	 *
	 * IMPORTANT: Only return true for OP Types that have FOUT restrictions.
	 * If this returns false, the validator is skipped and FOUT is allowed by default.
	 *
	 * Override this method in subclasses to filter specific OP Types.
	 *
	 * @param hop The hop operation to check
	 * @return true if this validator applies to this hop, false to skip validation
	 */
	public abstract boolean canValidate(Hop hop);

	// ===== Helper Methods =====

	/**
	 * Check if output is scalar (scalars cannot be federated)
	 */
	protected boolean isScalarOutput(Hop hop) {
		return hop.isScalar();
	}

	/**
	 * Check if first input is partitioned (ROW/COL)
	 */
	protected boolean isPartitionedInput(FType[] inputTypes) {
		if (inputTypes == null || inputTypes.length == 0) return false;
		FType first = inputTypes[0];
		return first == FType.ROW || first == FType.COL;
	}

	/**
	 * Check if any input is BROADCAST (affects conditional support)
	 */
	protected boolean hasBroadcastInput(FType[] inputTypes) {
		if (inputTypes == null) return false;
		for (FType ft : inputTypes) {
			if (ft == FType.BROADCAST) return true;
		}
		return false;
	}

	/**
	 * Get input partitioning type (returns first input's FType)
	 */
	protected FType getInputPartitionType(FType[] inputTypes) {
		if (inputTypes == null || inputTypes.length == 0) return null;
		return inputTypes[0];
	}

	/**
	 * Check if any input is federated (not null)
	 */
	protected boolean hasAnyFederatedInput(FType[] inputTypes) {
		if (inputTypes == null) return false;
		for (FType ft : inputTypes) {
			if (ft != null) return true;
		}
		return false;
	}

	/**
	 * Check if first input is federated
	 */
	protected boolean hasFederatedFirstInput(FType[] inputTypes) {
		return inputTypes != null && inputTypes.length > 0 && inputTypes[0] != null;
	}
}
