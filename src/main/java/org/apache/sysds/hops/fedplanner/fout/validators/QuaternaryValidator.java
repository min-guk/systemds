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

import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fout.*;

/**
 * Validator for Quaternary operations (wcemm, wdivmm, wsigmoid, wumm, wsloss).
 *
 * ANALYSIS SUMMARY (from runtime federated instructions):
 *
 * 1. WCEMM (Weighted Cross Entropy Matrix Multiplication):
 *    - Evidence: QuaternaryWCeMMFEDInstruction.java:147-152
 *    - GET_VAR at line 137, aggScalar(aop, response) at line 152
 *    - Output: SCALAR only → LOUT forced
 *
 * 2. WSLOSS (Weighted Squared Loss):
 *    - Evidence: QuaternaryWSLossFEDInstruction.java:147-162
 *    - GET_VAR at line 147, aggScalar(aop, response) at line 162
 *    - Output: SCALAR only → LOUT forced
 *
 * 3. WDIVMM (Weighted Division Matrix Multiplication):
 *    - Evidence: QuaternaryWDivMMFEDInstruction.java:177-205
 *    - CONDITIONAL execution path:
 *      a) Lines 177-198: LOUT path (aggregation required)
 *         if ((wdivmm_type.isLeft() && X.isFederated(FType.ROW))
 *             || (wdivmm_type.isRight() && X.isFederated(FType.COL)))
 *           → GET_VAR + aggMatrix() → LOUT output
 *      b) Lines 200-205: FOUT path (no aggregation)
 *         if (wdivmm_type.isLeft() || wdivmm_type.isRight() || wdivmm_type.isBasic())
 *           → setFederatedOutput() → FOUT output
 *    - Critical Insight: Both LOUT and FOUT can occur, depending on:
 *      * WDivMM operation type (LEFT/RIGHT/BASIC)
 *      * Input X federation type (ROW/COL)
 *    - Conservative approach: Allow both LOUT and FOUT
 *
 * 4. WSIGMOID (Weighted Sigmoid):
 *    - Evidence: QuaternaryWSigmoidFEDInstruction.java:122-130
 *    - No GET_VAR request, only setFedMapping() at line 129
 *    - Output: FOUT only
 *
 * 5. WUMM (Weighted Unary Matrix Multiplication):
 *    - Evidence: QuaternaryWUMMFEDInstruction.java:123-131
 *    - No GET_VAR request, only setFedMapping() at line 130
 *    - Output: FOUT only
 */
public class QuaternaryValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		return hop instanceof QuaternaryOp;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		QuaternaryOp qop = (QuaternaryOp) hop;
		OpOp4 op = qop.getOp();

		// Evidence from runtime federated instruction implementations:
		switch (op) {
			case WCEMM:
				// WCeMM ALWAYS aggregates to scalar
				// QuaternaryWCeMMFEDInstruction.java:152 - aggScalar(aop, response)
				// LOUT forced (no FOUT path exists)
				return true; // LOUT is the ONLY option

			case WSLOSS:
				// WSLoss ALWAYS aggregates to scalar
				// QuaternaryWSLossFEDInstruction.java:162 - aggScalar(aop, response)
				// LOUT forced (no FOUT path exists)
				return true; // LOUT is the ONLY option

			case WDIVMM:
				// WDivMM has TWO execution paths:
				// 1. LOUT path: QuaternaryWDivMMFEDInstruction.java:177-198
				//    if ((wdivmm_type.isLeft() && X.isFederated(FType.ROW))
				//        || (wdivmm_type.isRight() && X.isFederated(FType.COL)))
				//    → aggMatrix() aggregation → LOUT
				// 2. FOUT path: Lines 200-205
				//    if (wdivmm_type.isLeft() || wdivmm_type.isRight() || wdivmm_type.isBasic())
				//    → setFederatedOutput() → FOUT
				//
				// CRITICAL: The condition for LOUT path is:
				//   (isLeft && ROW) || (isRight && COL)
				// In ALL other cases, FOUT path is taken.
				//
				// We CANNOT determine wdivmm_type from Hop-level information alone
				// (requires _baseType, _mult, _minus fields from QuaternaryOp).
				// Conservative decision: Allow both LOUT and FOUT
				return true; // LOUT possible in some cases

			case WSIGMOID:
				// WSigmoid ALWAYS produces FOUT
				// QuaternaryWSigmoidFEDInstruction.java:129 - setFedMapping() only
				// No GET_VAR, no aggregation → FOUT only
				return false; // LOUT NOT possible

			case WUMM:
				// WUMM ALWAYS produces FOUT
				// QuaternaryWUMMFEDInstruction.java:130 - setFedMapping() only
				// No GET_VAR, no aggregation → FOUT only
				return false; // LOUT NOT possible

			default:
				// Unknown operation - conservative fallback
				return false;
		}
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		QuaternaryOp qop = (QuaternaryOp) hop;
		OpOp4 op = qop.getOp();

		// Evidence from runtime federated instruction implementations:
		switch (op) {
			case WCEMM:
				// WCeMM aggregates to SCALAR only
				// QuaternaryWCeMMFEDInstruction.java:152 - aggScalar()
				// No FOUT path exists
				return false;

			case WSLOSS:
				// WSLoss aggregates to SCALAR only
				// QuaternaryWSLossFEDInstruction.java:162 - aggScalar()
				// No FOUT path exists
				return false;

			case WDIVMM:
				// WDivMM supports FOUT for BASIC/LEFT/RIGHT variants
				// QuaternaryWDivMMFEDInstruction.java:200-205
				// if (wdivmm_type.isLeft() || wdivmm_type.isRight() || wdivmm_type.isBasic())
				//   → setFederatedOutput() → FOUT
				// Note: Also supports LOUT in specific cases (see isLOUTFeasible)
				return true;

			case WSIGMOID:
				// WSigmoid ALWAYS produces FOUT
				// QuaternaryWSigmoidFEDInstruction.java:129 - setFedMapping()
				return true;

			case WUMM:
				// WUMM ALWAYS produces FOUT
				// QuaternaryWUMMFEDInstruction.java:130 - setFedMapping()
				return true;

			default:
				// Unknown operation - conservative fallback
				return false;
		}
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		QuaternaryOp qop = (QuaternaryOp) hop;
		OpOp4 op = qop.getOp();

		switch (op) {
			case WCEMM:
				return "WCeMM: Scalar aggregation output only (LOUT forced, no FOUT possible)";
			case WSLOSS:
				return "WSLoss: Scalar aggregation output only (LOUT forced, no FOUT possible)";
			case WDIVMM:
				return "WDivMM: Both LOUT and FOUT possible depending on operation type (LEFT/RIGHT/BASIC) and input FType";
			case WSIGMOID:
				return "WSigmoid: Federated matrix output only (FOUT forced, no LOUT possible)";
			case WUMM:
				return "WUMM: Federated matrix output only (FOUT forced, no LOUT possible)";
			default:
				return "Unknown quaternary operation: " + op;
		}
	}
}
