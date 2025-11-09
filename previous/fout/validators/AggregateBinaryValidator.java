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

package org.apache.sysds.hops.fedplanner.fedCostBased.fout.validators;

import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fout.OutputConstraintValidator;

/**
 * Validator for AggregateBinaryOp FOUT constraints.
 *
 * LOUT Analysis:
 * - All execution paths support LOUT via aggregateLocally() method
 * - Evidence: AggregateBinaryFEDInstruction.java:213-245
 *
 * FOUT Constraints (checked in priority order):
 * 1. Only matrix multiplication (ba+*) supported
 *    - Evidence: AggBinaryOp.java:348-349 - isMatrixMultiply() checks innerOp=MULT && outerOp=SUM
 *
 * 2. PART input constraints (overlapping partitions require local aggregation)
 *    - Evidence: AggregateBinaryFEDInstruction.java:117-118
 *      boolean isPartOut = mo1.isFederated(FType.PART) || (!isVector && mo2.isFederated(FType.PART));
 *    - mo1.PART: Always blocks FOUT (applies to both MV and MM)
 *    - mo2.PART: Blocks FOUT only in MM case (ignored in MV case)
 *
 * 3. Matrix-Vector (MV) multiplication not supported for FOUT
 *    - Evidence: AggregateBinaryFEDInstruction.java:116,122-123
 *    - Comment: "not creating federated output in the MV case for reasons of performance"
 *    - Checked after PART constraints for accurate error messaging
 */
public class AggregateBinaryValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// Only validate AggBinaryOp types
		// BUT exclude special patterns (handled by TsmmValidator and MMChainValidator)
		if (!(hop instanceof AggBinaryOp)) {
			return false;
		}

		AggBinaryOp abop = (AggBinaryOp) hop;

		// Exclude TSMM patterns (checked by TsmmValidator first)
		if (abop.checkTransposeSelf() != org.apache.sysds.lops.MMTSJ.MMTSJType.NONE) {
			return false;
		}

		// Exclude MMChain patterns (checked by MMChainValidator second)
		if (abop.checkMapMultChain() != org.apache.sysds.lops.MapMultChain.ChainType.NONE) {
			return false;
		}

		// Only handle general matrix multiplication
		return true;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// LOUT Feasibility Analysis:
		// Evidence:
		// - AggregateBinaryFEDInstruction.java:213-245 - aggregateLocally() method implementation
		// - Line 230: FederatedRequest.GET_VAR to retrieve results
		// - Line 240-243: FederationUtils.aggAdd() or FederationUtils.bind() for aggregation
		// - Line 244: ec.setMatrixOutput() writes result locally
		//
		// All AggregateBinary execution paths eventually call aggregateLocally():
		// - Line 107: COL×ROW with column-transpose alignment
		// - Line 134: ROW×matrix (MV and MM cases)
		// - Line 148: vector×ROW (VM + MM)
		// - Line 160: COL-federated matrix multiplication
		//
		// Conclusion: LOUT always feasible through aggregateLocally()
		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		AggBinaryOp abop = (AggBinaryOp) hop;

		// Check 1: Only matrix multiplication supported
		// Evidence: AggBinaryOp.java:348-349 - isMatrixMultiply() checks innerOp=MULT && outerOp=SUM
		if (!abop.isMatrixMultiply()) {
			return false;
		}

		// Check 2: PART output blocked (checked before MV for accurate error messages)
		// Evidence: AggregateBinaryFEDInstruction.java:117-118
		//   boolean isPartOut = mo1.isFederated(FType.PART) ||
		//       (!isVector && mo2.isFederated(FType.PART));
		//
		// Rule 1: mo1.PART always blocks
		if (inputTypes != null && inputTypes.length > 0 && inputTypes[0] == FType.PART) {
			return false;
		}

		// Rule 2: mo2.PART blocks only in Matrix-Matrix case
		// In Matrix-Vector case, mo2.PART is ignored by the (!isVector && ...) condition
		boolean isMV = isMatrixVectorMult(abop);
		if (!isMV && inputTypes != null && inputTypes.length > 1 && inputTypes[1] == FType.PART) {
			return false;
		}

		// Check 3: Matrix-Vector (MV) case not supported for FOUT
		// Evidence: AggregateBinaryFEDInstruction.java:116 - isVector = mo2.getNumColumns() == 1
		// Evidence: Line 122-123 - "not creating federated output in the MV case for reasons of performance"
		// Note: Runtime code shows _fedOut.isForcedFederated() can override, but comment says "not creating"
		//       We follow the explicit design intent: MV should use LOUT
		if (isMV) {
			return false;
		}

		// All other patterns: FOUT possible
		// Evidence: AggregateBinaryFEDInstruction.java:122-127 - setOutputFedMapping() called
		return true;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		AggBinaryOp abop = (AggBinaryOp) hop;

		// Check in priority order (same as isFOUTFeasible)
		if (!abop.isMatrixMultiply()) {
			return "Only matrix multiplication (ba+*) supported for AggregateBinary";
		}

		// Check PART constraints first (more fundamental blocking reason)
		if (inputTypes != null && inputTypes.length > 0 && inputTypes[0] == FType.PART) {
			return "PART input (mo1) blocks FOUT: overlapping partitions require local aggregation";
		}

		boolean isMV = isMatrixVectorMult(abop);
		if (!isMV && inputTypes != null && inputTypes.length > 1 && inputTypes[1] == FType.PART) {
			return "PART input (mo2) blocks FOUT in Matrix-Matrix case";
		}

		// Check MV constraint last
		if (isMV) {
			return "Matrix-Vector multiplication not supported for FOUT (performance reasons)";
		}

		return null; // FOUT feasible
	}

	/**
	 * Check if this is a Matrix-Vector multiplication pattern.
	 * MV is detected when the right input (second operand) is a column vector.
	 *
	 * Verified from AggregateBinaryFEDInstruction.java:116:
	 *   boolean isVector = mo2.getNumColumns() == 1;
	 *
	 * Note: Returns false if dimensions are unknown (getDim2() == -1).
	 * This is conservative: we won't incorrectly block non-MV patterns.
	 * If dimensions become known later (after recompilation), the federated
	 * planner will re-evaluate the execution plan with updated metadata.
	 *
	 * @param abop The AggregateBinaryOp to check
	 * @return true if this is definitively a Matrix-Vector multiplication (mo2.numCols == 1)
	 */
	private boolean isMatrixVectorMult(AggBinaryOp abop) {
		// Validate input structure
		if (abop == null || abop.getInput() == null || abop.getInput().size() < 2) {
			return false;
		}

		Hop rightInput = abop.getInput().get(1);
		if (rightInput == null) {
			return false;
		}

		long numCols = rightInput.getDim2();

		// Only flag as MV if we know for certain it's a vector (numCols == 1)
		// If dimensions are unknown (numCols == -1), return false (conservative approach)
		// Unknown dimensions will be resolved during recompilation, at which point
		// the federated planner will re-run validation with concrete dimensions
		return numCols == 1;
	}

}
