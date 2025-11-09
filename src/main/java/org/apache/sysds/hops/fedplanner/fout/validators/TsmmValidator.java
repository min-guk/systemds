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
import org.apache.sysds.hops.fedplanner.fout.OutputConstraintValidator;
import org.apache.sysds.hops.fedplanner.fout.OutputConstraintResult;
import org.apache.sysds.lops.MMTSJ.MMTSJType;

/**
 * Validator for Tsmm (transpose self matrix multiplication) operations.
 *
 * VERIFICATION RESULTS (from code analysis):
 *
 * 1. HOPs vs Runtime Representation:
 *    - HOPs level (AggBinaryOp.java:433-450): Pattern appears as binary operation
 *      * LEFT:  t(X) %*% X  → input[0]=t(X), input[1]=X
 *      * RIGHT: X %*% t(X)  → input[0]=X, input[1]=t(X)
 *    - Runtime level: Optimized to unary operation (MMTSJCPInstruction extends UnaryCPInstruction)
 *      * TsmmFEDInstruction.java:46: super(..., in, null, ...) → only input1, input2=null
 *      * TsmmFEDInstruction.java:56: inst.input1 is the ORIGINAL matrix X
 *    - Lops construction (AggBinaryOp.java:506): getInput().get(mmtsj.isLeft() ? 1 : 0)
 *      * LEFT:  uses input[1] (original X from t(X)%*%X)
 *      * RIGHT: uses input[0] (original X from X%*%t(X))
 *
 * 2. Input Type Constraints (TsmmFEDInstruction.java:55-61):
 *    Line 56: MatrixObject mo = ec.getMatrixObject(inst.input1);
 *    Line 57-58: Condition checks:
 *      * LEFT tsmm:  mo.isFederated(FType.ROW) && mo.isFederatedExcept(FType.BROADCAST)
 *      * RIGHT tsmm: mo.isFederated(FType.COL) && mo.isFederatedExcept(FType.BROADCAST)
 *    → LEFT requires ROW, RIGHT requires COL, both EXCLUDE BROADCAST
 *
 * 3. FOUT Behavior (TsmmFEDInstruction.java:99-105):
 *    Line 99:  if (_fedOut.isForcedFederated()) {
 *    Line 100:     fr1 = mo1.getFedMapping().broadcast(mo1);  // Convert input to BROADCAST
 *    Line 104:     setOutputFederated(ec, mo1, fr2, FType.BROADCAST);  // Output as BROADCAST
 *    - When FOUT forced: converts input X to BROADCAST, outputs BROADCAST federated result
 *    - Without FOUT (line 118): aggregates locally via aggAdd
 *
 * FOUT CONSTRAINT TABLE:
 * Instruction Class      | OP Type  | OpCode | FOUT Possible?     | FOUT Constraint/Reason
 * TsmmFEDInstruction     | Tsmm     | tsmm   | Yes (conditional)  | Converts to BROADCAST type when FOUT forced
 */
public class TsmmValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// Only validate AggBinaryOp with TSMM patterns
		if (!(hop instanceof AggBinaryOp)) {
			return false;
		}

		AggBinaryOp abop = (AggBinaryOp) hop;

		// Check if this is a transpose-self matrix multiplication pattern
		// Returns MMTSJType.LEFT (t(X)%*%X) or MMTSJType.RIGHT (X%*%t(X))
		MMTSJType tsmmType = abop.checkTransposeSelf();
		return tsmmType != MMTSJType.NONE;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// LOUT Analysis from TsmmFEDInstruction.java runtime behavior:
		//
		// Evidence:
		// - TsmmFEDInstruction.java:112-119 - Default path (when !_fedOut.isForcedFederated())
		//   * Line 113: FederatedRequest fr2 = new FederatedRequest(RequestType.GET_VAR, fr1.getID())
		//   * Line 114: FederatedRequest fr3 = mo1.getFedMapping().cleanup(...)
		//   * Line 117: mo1.getFedMapping().execute(getTID(), fr1, fr2, fr3)
		//   * Line 118: MatrixBlock ret = FederationUtils.aggAdd(tmp)
		//   * Line 119: ec.setMatrixOutput(output.getName(), ret)
		//
		// Conclusion: LOUT is the DEFAULT path
		// - Uses GET_VAR to retrieve partial results from workers
		// - Aggregates with FederationUtils.aggAdd()
		// - Outputs local MatrixBlock (non-federated)

		// CRITICAL: Input FType constraints apply to BOTH LOUT and FOUT
		// Evidence from TsmmFEDInstruction.java:55-61 parseInstruction:
		// - Line 57-58 checks happen BEFORE processInstruction (before LOUT/FOUT split)
		// - If condition fails → returns null → no TsmmFEDInstruction created
		// - Therefore LOUT also requires the same input constraints

		AggBinaryOp abop = (AggBinaryOp) hop;
		MMTSJType tsmmType = abop.checkTransposeSelf();

		if (inputTypes != null && inputTypes.length >= 2) {
			FType originalInputType = (tsmmType == MMTSJType.LEFT) ? inputTypes[1] : inputTypes[0];

			if (originalInputType != null) {
				// Constraint from parseInstruction line 57-58:
				// LEFT:  mo.isFederated(FType.ROW) && mo.isFederatedExcept(FType.BROADCAST)
				// RIGHT: mo.isFederated(FType.COL) && mo.isFederatedExcept(FType.BROADCAST)

				if (tsmmType == MMTSJType.LEFT && originalInputType != FType.ROW) {
					return false; // LEFT tsmm requires ROW input
				}
				if (tsmmType == MMTSJType.RIGHT && originalInputType != FType.COL) {
					return false; // RIGHT tsmm requires COL input
				}

				// BROADCAST input is excluded by isFederatedExcept(FType.BROADCAST)
				if (originalInputType == FType.BROADCAST) {
					return false; // Tsmm does not support BROADCAST input
				}
			}
		}

		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		AggBinaryOp abop = (AggBinaryOp) hop;
		MMTSJType tsmmType = abop.checkTransposeSelf();

		// Input FType constraints from TsmmFEDInstruction.java:55-61
		// Evidence: parseInstruction line 57-58:
		//   LEFT:  mo.isFederated(FType.ROW) && mo.isFederatedExcept(FType.BROADCAST)
		//   RIGHT: mo.isFederated(FType.COL) && mo.isFederatedExcept(FType.BROADCAST)

		if (inputTypes != null && inputTypes.length >= 2) {
			// Get FType of original matrix X (see header comments for explanation)
			FType originalInputType = (tsmmType == MMTSJType.LEFT) ? inputTypes[1] : inputTypes[0];

			if (originalInputType != null) {
				// Constraint 1: LEFT requires ROW, RIGHT requires COL
				if (tsmmType == MMTSJType.LEFT && originalInputType != FType.ROW) {
					return false; // LEFT tsmm requires ROW input
				}
				if (tsmmType == MMTSJType.RIGHT && originalInputType != FType.COL) {
					return false; // RIGHT tsmm requires COL input
				}

				// Constraint 2: BROADCAST input is explicitly excluded
				if (originalInputType == FType.BROADCAST) {
					return false; // Tsmm does not support BROADCAST input
				}
			}
		}

		// FOUT behavior from TsmmFEDInstruction.java:99-105
		// Evidence:
		// - Line 99:  if (_fedOut.isForcedFederated())
		// - Line 100:     fr1 = mo1.getFedMapping().broadcast(mo1)  // Convert to BROADCAST
		// - Line 104:     setOutputFederated(ec, mo1, fr2, FType.BROADCAST)
		//
		// Conclusion: FOUT is conditional on _fedOut.isForcedFederated()
		// When FOUT forced:
		// - Converts input X to BROADCAST type
		// - Executes tsmm on each worker
		// - Outputs BROADCAST federated result
		// No additional blocking constraints beyond input FType checks above

		return true;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		AggBinaryOp abop = (AggBinaryOp) hop;
		MMTSJType tsmmType = abop.checkTransposeSelf();

		if (tsmmType == MMTSJType.NONE) {
			return "Not a valid TSMM pattern";
		}

		if (inputTypes != null && inputTypes.length >= 2) {
			FType originalInputType = (tsmmType == MMTSJType.LEFT) ? inputTypes[1] : inputTypes[0];

			if (originalInputType != null) {
				// Check pattern-specific requirements
				if (tsmmType == MMTSJType.LEFT && originalInputType != FType.ROW) {
					return "LEFT tsmm (t(X)%*%X) requires ROW partitioned input X, got: " + originalInputType;
				}
				if (tsmmType == MMTSJType.RIGHT && originalInputType != FType.COL) {
					return "RIGHT tsmm (X%*%t(X)) requires COL partitioned input X, got: " + originalInputType;
				}

				// Check BROADCAST constraint
				if (originalInputType == FType.BROADCAST) {
					return "Tsmm does not support BROADCAST input (must be ROW/COL partitioned)";
				}
			}
		}

		// If all constraints satisfied
		String pattern = tsmmType == MMTSJType.LEFT ? "t(X)%*%X" : "X%*%t(X)";
		return "Tsmm " + pattern + ": LOUT via aggAdd (default); FOUT converts input to BROADCAST and outputs BROADCAST result";
	}
}
