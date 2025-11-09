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
import org.apache.sysds.hops.fedplanner.fedCostBased.fout.*;

/**
 * Validator for AggregateTernary operations.
 *
 * CRITICAL ANALYSIS - DESIGN MISMATCH FOUND:
 *
 * 1. HOPs vs Runtime Representation MISMATCH:
 *    - HOPs level (TernaryOp): OpOp3.MOMENT and OpOp3.COV are DISTINCT from AggregateTernary!
 *      * TernaryOp.java:156-162: MOMENT → constructLopsCentralMoment() → CentralMoment lop
 *      * TernaryOp.java:156-162: COV → constructLopsCovariance() → CoVariance lop
 *      * TernaryOp.java:208: CentralMoment → Opcodes.CM (NOT tak+*)
 *      * CoVariance.java:89: CoVariance → Opcodes.COV (NOT tack+*)
 *    - Runtime level:
 *      * CentralMomentCPInstruction.java:43: Opcodes.CM → "cm" instruction (SCALAR output)
 *      * CovarianceCPInstruction.java:44: Opcodes.COV → "cov" instruction (SCALAR output)
 *      * AggregateTernaryCPInstruction.java:47: Opcodes.TAKPM/TACKPM → "tak+*"/"tack+*" (variable output)
 *
 * 2. MOMENT/COV are NOT AggregateTernary Instructions:
 *    - MOMENT (cm) → CentralMomentCPInstruction extends AggregateUnaryCPInstruction
 *    - COV (cov) → CovarianceCPInstruction extends BinaryCPInstruction
 *    - AggregateTernary (tak+*, tack+*) → AggregateTernaryCPInstruction (DIFFERENT instruction type)
 *    - AggregateTernaryFEDInstruction.java:84: Only handles TAKPM/TACKPM opcodes
 *    - NO FED instruction exists for CM/COV opcodes!
 *
 * 3. OpCode Mapping Analysis:
 *    - TernaryOp.OpOp3.MOMENT → Opcodes.CM → "cm" → CentralMomentCPInstruction
 *    - TernaryOp.OpOp3.COV → Opcodes.COV → "cov" → CovarianceCPInstruction
 *    - Opcodes.TAKPM → "tak+*" → AggregateTernaryCPInstruction
 *    - Opcodes.TACKPM → "tack+*" → AggregateTernaryCPInstruction
 *
 * 4. What ARE tak+* and tack+* Actually?
 *    - AggregateTernaryCPInstruction.java:72-73: ret = MatrixBlock.aggregateTernaryOperations(...)
 *    - AggregateTernaryOperator: aggOp.increOp.fn=KahanPlus, binaryFn=Multiply
 *    - tak+* = ternary aggregate with KahanPlus (sum(X * Y * Z))
 *    - tack+* = ternary aggregate with KahanPlus for columns
 *    - These CAN produce matrix output (line 80-83: if scalar → setScalarOutput, else → setMatrixOutput)
 *
 * CONCLUSION - VALIDATOR IS WRONG:
 * - This validator should NOT check for OpOp3.MOMENT or OpOp3.COV
 * - MOMENT/COV are separate instruction types with no FED implementation
 * - AggregateTernaryFEDInstruction only exists for tak+/tack+* operations
 * - There is NO TernaryOp that maps to AggregateTernaryFEDInstruction at HOPs level
 * - The FOUT constraint table is INCORRECT - it lists instructions that don't exist in FED layer
 *
 * PROPER IMPLEMENTATION:
 * - canValidate() should return FALSE - no TernaryOp maps to AggregateTernary FED instructions
 * - OR: This validator should be REMOVED entirely
 * - The tak+/tack+* instructions are likely generated differently (not from TernaryOp HOPs)
 */
public class AggregateTernaryValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// CRITICAL: AggregateTernaryFEDInstruction (tak+*, tack+*) does NOT map to any TernaryOp!
		// - TernaryOp.MOMENT → CentralMomentCPInstruction (cm opcode)
		// - TernaryOp.COV → CovarianceCPInstruction (cov opcode)
		// - tak+*/tack+* are generated from different HOPs (likely rewrite or special case)
		// - NO FED instruction exists for CM/COV opcodes
		//
		// Since no TernaryOp maps to AggregateTernaryFEDInstruction, this validator
		// should NEVER match any Hop operation.
		return false;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// TODO: Runtime 분석
		// Evidence:
		// - AggregateTernaryFEDInstruction.java:114,135,163 - GET_VAR pattern used (LOUT compatible)
		// - AggregateTernaryFEDInstruction.java:118-125 - Scalar: FederationUtils.aggScalar()
		// - AggregateTernaryFEDInstruction.java:143-152 - Scalar: manual sum aggregation
		// - AggregateTernaryFEDInstruction.java:170-179 - Scalar: manual sum aggregation
		// - AggregateTernaryFEDInstruction.java:123-124 - Matrix: FederationUtils.aggMatrix()
		// Note: canValidate() returns false, so this may not be called

		return true; // Default assumption: GET_VAR + aggregation supports LOUT
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		// FOUT Analysis - NOT FEASIBLE:
		// Evidence:
		// - AggregateTernaryFEDInstruction.java:114,135,163 - All paths use GET_VAR
		// - AggregateTernaryFEDInstruction.java:116,141,168 - No setFedMapping() call anywhere
		// - AggregateTernaryFEDInstruction.java:120,124 - Results aggregated locally (aggScalar/aggMatrix)
		// - AggregateTernaryFEDInstruction.java:152,179 - Manual local sum aggregation
		//
		// Comparison with AggregateBinary:
		// - AggregateBinary: setOutputFedMapping() exists → FOUT possible
		// - AggregateTernary: Only GET_VAR + local aggregation → LOUT only
		//
		// Note: canValidate() returns false, so this may not be called

		return false; // FOUT not supported - all paths aggregate locally
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		return "AggregateTernary: Not mapped from HOPs (tak+*/tack+* generated differently)";
	}
}
