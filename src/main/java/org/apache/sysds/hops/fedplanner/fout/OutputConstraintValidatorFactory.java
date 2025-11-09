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

package org.apache.sysds.hops.fedplanner.fout;

import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.fout.validators.*;
import org.apache.sysds.hops.fedplanner.ftype.FType;
import java.util.Arrays;
import java.util.List;

/**
 * Factory for selecting the appropriate OutputConstraintValidator.
 * Uses chain of responsibility pattern similar to FederatedTypeHandlerFactory.
 *
 * DESIGN PRINCIPLE - OP Type Filtering:
 * - Only OP Types with FOUT restrictions are registered as validators
 * - If no validator matches (getValidator returns null), FOUT is ALLOWED by default
 * - This implements a "whitelist filter" approach for FOUT constraints
 *
 * Registered OP Types (from FOUT Constraint Table):
 * - AggregateUnary (uack+, uark+, uarimax, uarimin, var)
 * - AggregateBinary (ba+* / mmult)
 * - MAPMM (mapmm, pmmj, cpmm, rmm)
 * - Tsmm (tsmm)
 * - ParameterizedBuiltin (contains)
 * - AggregateTernary (tak*, tack+)
 * - MMChain (mmchain)
 * - QuantilePick (qpick)
 * - Ctable (ctable)
 * - Quaternary (wsloss, wcemm, etc.)
 * - CentralMoment (cm)
 * - Covariance (cov)
 */
public class OutputConstraintValidatorFactory {

	private static final List<OutputConstraintValidator> validators;

	static {
		// Initialize validators for instructions with FOUT restrictions
		// CRITICAL: Order MATTERS when validators check the same Hop type!
		//
		// AggBinaryOp has THREE pattern detectors (checked in this priority):
		//   1. TSMM (checkTransposeSelf): t(X)%*%X or X%*%t(X)
		//   2. MMChain (checkMapMultChain): t(X)%*%(X%*%v) patterns
		//   3. General MM: all other matrix multiplications
		//
		// See AggBinaryOp.optFindMMultMethodCP() lines 1034-1047 for reference.
		// Validators MUST follow the SAME priority order to match SystemDS semantics!
		//
		validators = Arrays.asList(
			// Unary/Binary aggregation operations:
			new AggregateUnaryValidator(),
			// AggBinaryOp pattern validators in PRIORITY ORDER:
			new TsmmValidator(),             // 1st priority: TSMM pattern
			new MMChainValidator(),          // 2nd priority: MMChain pattern
			new AggregateBinaryValidator(),  // 3rd priority: general MM (excluding above patterns)
			new MMMValidator(),
			// Multi-input operations:
			new ParameterizedBuiltinValidator(),
			new AggregateTernaryValidator(),
			new QuaternaryValidator(),
			// Statistical operations:
			new CentralMomentValidator(),
			new CovarianceValidator(),
			// Special operations:
			new QuantilePickValidator(),
			new CtableValidator()
		);
	}

	/**
	 * Gets the appropriate validator for the given Hop.
	 * This method acts as the OP Type filter.
	 *
	 * @param hop The Hop operation to find a validator for
	 * @return The appropriate OutputConstraintValidator, or null if no validator applies
	 *         (null means FOUT is allowed by default - OP Type not in restriction list)
	 */
	public OutputConstraintValidator getValidator(Hop hop) {
		for (OutputConstraintValidator validator : validators) {
			if (validator.canValidate(hop)) {
				return validator;
			}
		}
		return null; // OP Type not in constraint list → FOUT allowed by default
	}

	/**
	 * Check if LOUT is feasible for the given hop.
	 * If no validator applies, returns true (no restrictions).
	 *
	 * @param hop The hop operation
	 * @param inputTypes Input FTypes
	 * @return true if LOUT is feasible, false if FOUT is forced
	 */
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		OutputConstraintValidator validator = getValidator(hop);
		if (validator == null) {
			// No validator = no restrictions = both LOUT and FOUT feasible
			return true;
		}
		return validator.isLOUTFeasible(hop, inputTypes);
	}

	/**
	 * Check if FOUT is feasible for the given hop.
	 * If no validator applies, returns true (no restrictions).
	 *
	 * @param hop The hop operation
	 * @param inputTypes Input FTypes
	 * @return true if FOUT is feasible, false if LOUT is forced
	 */
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		OutputConstraintValidator validator = getValidator(hop);
		if (validator == null) {
			// No validator = no restrictions = both LOUT and FOUT feasible
			return true;
		}
		return validator.isFOUTFeasible(hop, inputTypes);
	}

	/**
	 * Get constraint message for the given hop.
	 * If no validator applies, returns default message.
	 *
	 * @param hop The hop operation
	 * @param inputTypes Input FTypes
	 * @return Constraint message
	 */
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		OutputConstraintValidator validator = getValidator(hop);
		if (validator == null) {
			return "No constraints: both LOUT and FOUT feasible by default";
		}
		return validator.getConstraintMessage(hop, inputTypes);
	}

	/**
	 * Gets the list of all registered validators (for testing/debugging)
	 */
	public static List<OutputConstraintValidator> getAllValidators() {
		return validators;
	}
}
