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
 * Validator for MAPMM (Map-side Matrix Multiplication) operations.
 *
 * COMPLETE RUNTIME ANALYSIS (MMFEDInstruction.java:74-158):
 *
 * MMFEDInstruction.processInstruction() has 4 INDEPENDENT execution branches:
 *
 * BRANCH #1 (line 84-96): COL×ROW with alignment
 *   Condition: mo1.isFederated(FType.COL) && mo2.isFederated(FType.ROW) && isAligned(COL_T)
 *   FOUT logic:
 *     if (_fedOut.isForcedFederated()) { setPartialOutput(...); }
 *     else { aggregateLocally(...); }
 *   Constraint: CONDITIONAL (forced only)
 *
 * BRANCH #2 (line 98-119): ROW or PART federated left input
 *   Condition: mo1.isFederated(FType.ROW) || mo1.isFederated(FType.PART)
 *   isVector: mo2.getNumColumns() == 1
 *   isPartOut: mo1.isFederated(FType.PART) || (!isVector && mo2.isFederated(FType.PART))
 *   FOUT logic:
 *     if (isPartOut && forced) { setPartialOutput(...); }
 *     else if ((forced || (!isVector && !forcedLocal)) && !isPartOut) { setOutputFedMapping(...); }
 *     else { aggregateLocally(...); }
 *   Constraints:
 *     - PART: CONDITIONAL (forced only)
 *     - MV (!PART): CONDITIONAL (forced only, "performance reasons")
 *     - MM (!PART): ALLOWED (default FOUT unless forcedLocal)
 *
 * BRANCH #3 (line 122-135): vector × ROW federated matrix (VM)
 *   Condition: mo2.isFederated(FType.ROW)
 *   FOUT logic:
 *     if (_fedOut.isForcedFederated()) { setPartialOutput(...); }
 *     else { aggregateLocally(...); }
 *   Constraint: CONDITIONAL (forced only)
 *
 * BRANCH #4 (line 138-151): COL federated left input (VM)
 *   Condition: mo1.isFederated(FType.COL)
 *   FOUT logic:
 *     if (_fedOut.isForcedFederated()) { setPartialOutput(...); }
 *     else { aggregateLocally(...); }
 *   Constraint: CONDITIONAL (forced only)
 *
 * KEY INSIGHT: Branch selection depends on FType at runtime, which Validator has via inputTypes[].
 * However, we cannot perfectly predict which branch will execute because:
 * - Branch conditions check mo1.isFederated(FType.X), not just inputTypes[0] == FType.X
 * - Multiple branches can match (e.g., COL can match Branch #1 or #4)
 *
 * CONSERVATIVE APPROACH: Return worst-case constraint across all possible branches.
 *
 * FOUT CONSTRAINT TABLE:
 * Instruction Class   | OP Type | OpCode                  | FOUT Possible?     | FOUT Constraint/Reason
 * MMFEDInstruction    | MAPMM   | mapmm, pmmj, cpmm, rmm  | Yes (conditional)  | Most branches require forced FOUT; only Branch #2 MM case allows default FOUT
 */
public class MMMValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// MAPMM operations are a subset of AggBinaryOp
		// They are detected by checking the MMultMethod after execution planning
		// However, at Hop level (compile-time), we cannot reliably predict which
		// MMultMethod will be chosen (depends on runtime dimensions/memory)

		// DESIGN DECISION: Since MAPMM is a runtime execution strategy for AggBinaryOp,
		// and the FOUT constraints (PART/MV) are identical to AggregateBinaryValidator,
		// this validator acts as a documentation placeholder.

		// The actual validation is handled by AggregateBinaryValidator which covers
		// all AggBinaryOp operations including MAPMM variants.

		// Return false to skip validation (AggregateBinaryValidator handles this)
		return false;

		// NOTE: If in the future we need to distinguish MAPMM-specific constraints
		// that differ from general AggBinaryOp, we would need to:
		// 1. Access hop._method (MMultMethod) field - but this is determined at Lops construction
		// 2. Or inspect Lops structure - but OutputConstraintValidator runs at Hop level
		// 3. Or defer validation to runtime instruction level (not Hop level)
	}

	// =========================================================================
	// DOCUMENTATION-ONLY METHODS
	// These methods are never called because canValidate() returns false.
	// They exist purely for documentation purposes to explain MMFEDInstruction behavior.
	// Actual validation is performed by AggregateBinaryValidator.
	// =========================================================================

	/**
	 * LOUT Feasibility Analysis (Documentation Only - Never Called)
	 *
	 * Based on MMFEDInstruction.java aggregateLocally() method:
	 *
	 * Evidence:
	 * - Line 192-225: aggregateLocally() implementation
	 * - Line 208-209: Uses GET_VAR and cleanup federated requests
	 * - Line 220-223: Aggregates with FederationUtils.aggAdd() or FederationUtils.bind()
	 * - All 4 branches call aggregateLocally() when FOUT is not forced/feasible
	 *
	 * Key observations:
	 * - Branch #1 (Line 95): aggregateLocally(fedMap, true, ...) - aggAdd=true
	 * - Branch #2 (Line 118): aggregateLocally(fedMap, mo1.isFederated(FType.PART), ...) - aggAdd=conditional
	 * - Branch #3 (Line 134): aggregateLocally(fedMap, true, ...) - aggAdd=true
	 * - Branch #4 (Line 150): aggregateLocally(fedMap, true, ...) - aggAdd=true
	 *
	 * Conclusion: LOUT always feasible via aggregateLocally()
	 *
	 * NOTE: This method is NEVER CALLED because canValidate() returns false.
	 *       Actual validation is performed by AggregateBinaryValidator.
	 */
	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		throw new UnsupportedOperationException(
			"MMMValidator is documentation-only. Actual validation handled by AggregateBinaryValidator.");
	}

	/**
	 * FOUT Feasibility Analysis (Documentation Only - Never Called)
	 *
	 * Based on MMFEDInstruction.java processInstruction() branches:
	 *
	 * Evidence from branch analysis:
	 *
	 * Branch #1 (Line 84-96): COL×ROW with alignment
	 *   - Line 90: if (_fedOut.isForcedFederated()) { setPartialOutput(...); }
	 *   - FOUT: CONDITIONAL (forced only)
	 *
	 * Branch #2 (Line 98-119): ROW or PART federated left input
	 *   - Line 108: if (isPartOut && forced) { setPartialOutput(...); }
	 *   - Line 112-115: if ((forced || (!isVector && !forcedLocal)) && !isPartOut) { setOutputFedMapping(...); }
	 *   - FOUT: Depends on sub-case
	 *     * PART output: CONDITIONAL (forced only)
	 *     * MV (!PART): CONDITIONAL (forced only, "performance reasons")
	 *     * MM (!PART): ALLOWED (default FOUT unless forcedLocal)
	 *
	 * Branch #3 (Line 122-135): vector × ROW matrix (VM)
	 *   - Line 128: if (_fedOut.isForcedFederated()) { setPartialOutput(...); }
	 *   - FOUT: CONDITIONAL (forced only)
	 *
	 * Branch #4 (Line 138-151): COL federated left input (VM)
	 *   - Line 144: if (_fedOut.isForcedFederated()) { setPartialOutput(...); }
	 *   - FOUT: CONDITIONAL (forced only)
	 *
	 * Conclusion: FOUT possible in all branches (conditionally or by default)
	 *
	 * NOTE: This method is NEVER CALLED because canValidate() returns false.
	 *       Actual validation is performed by AggregateBinaryValidator.
	 */
	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		throw new UnsupportedOperationException(
			"MMMValidator is documentation-only. Actual validation handled by AggregateBinaryValidator.");
	}

	/**
	 * Constraint Message (Documentation Only - Never Called)
	 *
	 * NOTE: This method is NEVER CALLED because canValidate() returns false.
	 *       Actual validation is performed by AggregateBinaryValidator.
	 */
	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		throw new UnsupportedOperationException(
			"MMMValidator is documentation-only. Actual validation handled by AggregateBinaryValidator.");
	}
}
