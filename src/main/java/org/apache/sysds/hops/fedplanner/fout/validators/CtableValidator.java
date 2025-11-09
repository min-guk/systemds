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

package org.apache.sysds.hops.fedplanner.fout.validators;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fout.*;

/**
 * Validator for Ctable (contingency table) operations.
 *
 * Runtime Analysis (CtableFEDInstruction.java):
 * - Line 72-74, 86-88: parseInstruction() requires at least one FType.ROW input
 * - Line 154: isFedOutput() performs data-dependent runtime validation
 * - Line 250-276: Checks if mo2 slices have separable & ascending value ranges
 * - Line 298: setFedMapping() called when FOUT possible (data-dependent)
 * - Line 224-233: LOUT path via GET_VAR + aggResult()
 *
 * Constraints:
 * - Supported FType: ROW only (COL, BROADCAST, PART rejected at parseInstruction)
 * - LOUT: Always supported (if parseInstruction succeeds)
 * - FOUT: Conditionally supported (data-dependent isFedOutput() check)
 */
public class CtableValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// Only validate TernaryOp with CTABLE opcode
		// Note: Both "ctable" and "ctableexpand" opcodes map to OpOp3.CTABLE
		return hop instanceof TernaryOp &&
		       ((TernaryOp)hop).getOp() == org.apache.sysds.common.Types.OpOp3.CTABLE;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// LOUT Feasibility Analysis:
		// Evidence: CtableFEDInstruction.java:224-233
		// - Line 225: GET_VAR retrieves partial results
		// - Line 232: aggResult() aggregates via binary PLUS operations
		//
		// CRITICAL: Requires CtableFEDInstruction creation (parseInstruction success)
		// Evidence: CtableFEDInstruction.java:72-74, 86-88
		// - parseInstruction() requires at least ONE ROW-partitioned input
		// - If all inputs local/null, parseInstruction returns null → no federated execution
		//
		// Conclusion: LOUT feasible only if parseInstruction succeeds
		return hasValidFederatedInput(inputTypes);
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		// Check: FType constraints (same as LOUT)
		// Evidence: CtableFEDInstruction.java:72-74, 86-88
		// parseInstruction() requires at least one ROW input, rejects COL/BROADCAST/PART
		if (!hasValidFederatedInput(inputTypes)) {
			return false;
		}

		// FOUT path is data-dependent
		// Evidence: CtableFEDInstruction.java:154, 250-276, 298
		// - Line 154: fedOutput = isFedOutput(mo1.getFedMapping(), mo2.getMO())
		// - Lines 250-276: Validates if mo2 slices have separable & ascending value ranges
		// - Line 298: If true → setFedMapping() (FOUT), else → GET_VAR + aggResult() (LOUT)
		//
		// Conclusion: FOUT conditionally feasible (runtime data-dependent check)
		return true;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		// Perform same validation as isFOUTFeasible to generate specific error messages
		String validationError = validateFederatedInputs(inputTypes);
		if (validationError != null) {
			return validationError;
		}

		// All FType constraints satisfied, but FOUT is data-dependent
		return "FOUT feasibility is data-dependent: runtime validates if matrix value ranges are separable " +
		       "(isFedOutput() check at CtableFEDInstruction.java:250-276)";
	}

	/**
	 * Validate federated inputs: require at least one ROW, reject non-ROW federated types.
	 *
	 * Evidence: CtableFEDInstruction.java:72-74, 86-88
	 * parseInstruction() checks (input1.isMatrix() && isFederated(ROW)) OR
	 *                            (input2.isMatrix() && isFederated(ROW)) OR
	 *                            (input3.isMatrix() && isFederated(ROW))
	 *
	 * @param inputTypes array of input FTypes (null = local/scalar input)
	 * @return true if at least one ROW input exists and no non-ROW federated inputs
	 */
	private boolean hasValidFederatedInput(FType[] inputTypes) {
		return validateFederatedInputs(inputTypes) == null;
	}

	/**
	 * Validate federated inputs and return error message if invalid.
	 * Note: input3 is optional (can be null/scalar for weights)
	 *
	 * @param inputTypes array of input FTypes (null = local/scalar input)
	 * @return null if valid, error message otherwise
	 */
	private String validateFederatedInputs(FType[] inputTypes) {
		if (inputTypes == null) {
			return "CTABLE requires input type information for validation";
		}

		boolean hasRowInput = false;
		for (int i = 0; i < inputTypes.length; i++) {
			FType ft = inputTypes[i];
			if (ft != null) {
				if (ft == FType.ROW) {
					hasRowInput = true;
				} else {
					// COL, BROADCAST, PART rejected at parseInstruction
					return "CTABLE only supports ROW-partitioned inputs (found " + ft +
					       " at input " + (i+1) + ")";
				}
			}
		}

		if (!hasRowInput) {
			return "CTABLE requires at least one ROW-partitioned federated input";
		}

		return null;
	}
}
