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

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fout.*;

/**
 * Validator for AggregateUnary operations.
 *
 * VERIFICATION RESULTS:
 *
 * 1. Supported AggOp enum values (from Types.java:513-520):
 *    - SUM, SUM_SQ, MIN, MAX        (basic aggregations)
 *    - PROD, SUM_PROD               (product operations)
 *    - TRACE, MEAN, VAR             (statistical operations)
 *    - MAXINDEX, MININDEX           (index operations)
 *    - COUNT_DISTINCT, COUNT_DISTINCT_APPROX, UNIQUE
 *
 * 2. FED runtime support verification (from AggregateUnaryFEDInstruction.java):
 *    - processDefault() handles: general aggregations (lines 114-139)
 *    - processVar() handles: VAR operations separately (lines 232-289)
 *    - Confirmed VAR requires special consolidation (processVar method)
 *    - processFederatedOutput() checks scalar output (lines 148-150)
 *
 * 3. OpCode to AggOp mapping (from AggUnaryOp.java):
 *    - uack+ → column aggregations (Direction.Col)
 *    - uark+ → row aggregations (Direction.Row)
 *    - uarimax, uarimin → index operations (MAXINDEX, MININDEX)
 *    - var → VAR operation
 *
 * FOUT Constraint Table Implementation:
 *
 * Instruction Class                 | OP Type         | OpCode                              | FOUT Possible? | FOUT Constraint/Reason
 * ----------------------------------|-----------------|-------------------------------------|----------------|------------------------------------------
 * AggregateUnaryFEDInstruction      | AggregateUnary  | uack+, uark+, uarimax, uarimin      | No (scalar)    | "Scalars cannot be federated"
 * AggregateUnaryFEDInstruction      | AggregateUnary  | var (variance ops)                  | No             | "requires consolidation of partial results"
 */
public class AggregateUnaryValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		return hop instanceof AggUnaryOp;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		AggUnaryOp auop = (AggUnaryOp) hop;
		AggOp op = auop.getOp();

		// Check 1: Supported operations (from AggUnaryOpHandler.java:30-34)
		// Evidence: Only SUM, MIN, MAX, SUM_SQ, MEAN, VAR, MAXINDEX, MININDEX are supported
		if (!(op == AggOp.SUM || op == AggOp.MIN || op == AggOp.MAX ||
		      op == AggOp.SUM_SQ || op == AggOp.MEAN || op == AggOp.VAR ||
		      op == AggOp.MAXINDEX || op == AggOp.MININDEX)) {
			return false;
		}

		// Check 2: Input must be federated
		// Evidence: AggUnaryOpHandler.java:42-44 - requires federated first input
		if (inputTypes == null || inputTypes.length == 0 || inputTypes[0] == null) {
			return false;
		}

		// Check 3: BROADCAST input not supported
		// Evidence: AggUnaryOpHandler.java:49-52 - BROADCAST input causes duplicate aggregation
		if (inputTypes[0] == FType.BROADCAST) {
			return false;
		}

		// Evidence: LOUT implementation paths
		// - AggregateUnaryFEDInstruction.java:219-230 - processGetOutput() uses GET_VAR
		// - AggregateUnaryFEDInstruction.java:227 - FederationUtils.aggScalar() for scalar outputs
		// - AggregateUnaryFEDInstruction.java:229 - FederationUtils.aggMatrix() for matrix outputs
		// - AggregateUnaryFEDInstruction.java:232-289 - processVar() also uses GET_VAR
		//
		// Conclusion: LOUT is supported for all valid federated operations
		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		AggUnaryOp auop = (AggUnaryOp) hop;
		AggOp op = auop.getOp();

		// Check 1: Operations not supported in FED instruction
		// Evidence: AggUnaryOpHandler.java:30-34
		// Only SUM, MIN, MAX, SUM_SQ, MEAN, VAR, MAXINDEX, MININDEX are supported
		if (!(op == AggOp.SUM || op == AggOp.MIN || op == AggOp.MAX ||
		      op == AggOp.SUM_SQ || op == AggOp.MEAN || op == AggOp.VAR ||
		      op == AggOp.MAXINDEX || op == AggOp.MININDEX)) {
			return false;
		}

		// Check 2: Input must be federated
		// Evidence: AggUnaryOpHandler.java:42-44
		if (inputTypes == null || inputTypes.length == 0 || inputTypes[0] == null) {
			return false;
		}

		// Check 3: BROADCAST input not supported
		// Evidence: AggUnaryOpHandler.java:49-52
		if (inputTypes[0] == FType.BROADCAST) {
			return false;
		}

		// Check 4: VAR operation → FOUT not allowed due to consolidation requirement
		// Evidence: AggregateUnaryFEDInstruction.java:233-236 - processVar() throws exception
		// "Output should not be federated since the instruction requires consolidation of partial results"
		if (op == AggOp.VAR) {
			return false;
		}

		// Check 5: Scalar output → FOUT not allowed
		// Evidence: AggregateUnaryFEDInstruction.java:148-150 - processFederatedOutput() throws exception
		// "Scalars cannot be federated"
		if (isScalarOutput(hop)) {
			return false;
		}

		// Check 6: PART output (mismatched partition+aggregation) not supported
		// Evidence: AggregateUnaryFEDInstruction.java:189-197 - deriveNewOutputFedMapping()
		// Throws "PART output not supported" when:
		// - ROW partition + column aggregation → would produce PART
		// - COL partition + row aggregation → would produce PART
		boolean isColAgg = auop.getDirection().isCol();
		FType inputFType = inputTypes[0];

		if ((inputFType == FType.ROW && isColAgg) ||
		    (inputFType == FType.COL && !isColAgg)) {
			// This combination would require PART output which is not supported
			return false;
		}

		// Evidence: AggregateUnaryFEDInstruction.java:147-210 - processFederatedOutput() implementation
		// - Line 151-153: setFedMapping via FederationUtils.callInstruction
		// - Line 156: deriveNewOutputFedMapping() sets output federation mapping
		// - Line 178-181: ROW partition + row agg → ROW output (supported)
		// - Line 202-205: COL partition + col agg → COL output (supported)
		//
		// Conclusion: FOUT is supported when partition type matches aggregation direction
		return true;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		AggUnaryOp auop = (AggUnaryOp) hop;
		AggOp op = auop.getOp();

		// Check 1: Unsupported operations
		// Reference: AggUnaryOpHandler.java:30-34
		if (!(op == AggOp.SUM || op == AggOp.MIN || op == AggOp.MAX ||
		      op == AggOp.SUM_SQ || op == AggOp.MEAN || op == AggOp.VAR ||
		      op == AggOp.MAXINDEX || op == AggOp.MININDEX)) {
			return "AggregateUnary: " + op + " not supported in federated execution (AggUnaryOpHandler.java:30-34)";
		}

		// Check 2: Input validation
		if (inputTypes == null || inputTypes.length == 0 || inputTypes[0] == null) {
			return "AggregateUnary: Requires federated first input (AggUnaryOpHandler.java:42-44)";
		}

		// Check 3: BROADCAST input
		// Reference: AggUnaryOpHandler.java:49-52
		if (inputTypes[0] == FType.BROADCAST) {
			return "AggregateUnary: BROADCAST input not supported - would cause duplicate aggregation (AggUnaryOpHandler.java:49-52)";
		}

		// Check 4: VAR operation
		// Reference: AggregateUnaryFEDInstruction.java:233-236
		if (op == AggOp.VAR) {
			return "AggregateUnary: VAR requires consolidation of partial results (AggregateUnaryFEDInstruction.java:233-236)";
		}

		// Check 5: Scalar output
		// Reference: AggregateUnaryFEDInstruction.java:148-150
		if (isScalarOutput(hop)) {
			return "AggregateUnary: Scalars cannot be federated (AggregateUnaryFEDInstruction.java:148-150)";
		}

		// Check 6: PART output constraint
		// Reference: AggregateUnaryFEDInstruction.java:189-197
		boolean isColAgg = auop.getDirection().isCol();
		FType inputFType = inputTypes[0];

		if ((inputFType == FType.ROW && isColAgg)) {
			return "AggregateUnary: ROW partition + column aggregation would require PART output (not supported, AggregateUnaryFEDInstruction.java:189-197)";
		}
		if ((inputFType == FType.COL && !isColAgg)) {
			return "AggregateUnary: COL partition + row aggregation would require PART output (not supported, AggregateUnaryFEDInstruction.java:189-197)";
		}

		// FOUT is feasible
		String partitionType = inputFType == FType.ROW ? "ROW" : "COL";
		String aggType = isColAgg ? "column" : "row";
		return "AggregateUnary: " + op + " with " + partitionType + " partition + " + aggType + " aggregation supports FOUT (maintains " + partitionType + " structure)";
	}
}
