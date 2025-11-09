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
import org.apache.sysds.lops.MapMultChain.ChainType;

/**
 * Validator for MMChain (Matrix Multiplication Chain) operations.
 *
 * VERIFICATION RESULTS:
 * =====================
 * 1. MMChain Hop Implementation:
 *    - MMChain is NOT a separate Hop class in SystemDS
 *    - It is a special pattern detected within AggBinaryOp (matrix multiplication)
 *    - Detection: AggBinaryOp.checkMapMultChain() method identifies three patterns:
 *      * ChainType.XtXv:  t(X)%*%(X%*%v)
 *      * ChainType.XtwXv: t(X)%*%(w*(X%*%v))
 *      * ChainType.XtXvy: t(X)%*%((X%*%v)-y)
 *
 * 2. MMChainFEDInstruction Analysis:
 *    - File: src/main/java/org/apache/sysds/runtime/instructions/fed/MMChainFEDInstruction.java
 *    - Lines 100-141: processInstruction() method
 *    - CRITICAL FINDING: All code paths aggregate results using FederationUtils.aggAdd()
 *      * Line 113: aggAdd for weighted chains with federated weights (XtwXv with federated w)
 *      * Line 125: aggAdd for non-weighted chains (XtXv)
 *      * Line 140: aggAdd for weighted chains with broadcast weights (XtwXv/XtXvy)
 *    - CONCLUSION: MMChain ALWAYS performs local aggregation → FOUT is NOT possible
 *
 * 3. OpCode Variants:
 *    - Primary OpCode: "mmchain" (Opcodes.MMCHAIN)
 *    - Related: "mapmmchain" (Opcodes.MAPMMCHAIN) for Spark execution
 *    - Both variants require aggregation
 *
 * FOUT CONSTRAINT:
 * ================
 * - Instruction Class: MMChainFEDInstruction
 * - OP Type: AggBinaryOp with detected mmchain pattern
 * - OpCode: mmchain
 * - FOUT Possible: NO
 * - FOUT Constraint: Always requires local aggregation
 * - Reason: All execution paths aggregate partial results from federated workers
 */
public class MMChainValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// MMChain is detected as a pattern within AggBinaryOp
		// Check if it's an AggBinaryOp with mmchain pattern
		if (!(hop instanceof AggBinaryOp)) {
			return false;
		}

		AggBinaryOp aggBinOp = (AggBinaryOp) hop;
		// Check if this AggBinaryOp has a detected mmchain pattern
		ChainType chainType = aggBinOp.checkMapMultChain();
		return chainType != ChainType.NONE;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// Input FType constraint from MMChainFEDInstruction.java:54-58
		// Evidence: parseInstruction line 56:
		//   if( mo.isFederated(FType.ROW) )
		//     return MMChainFEDInstruction.parseInstruction(inst);
		//   return null;
		// → Only ROW partitioning supported, returns null (falls back to CP) otherwise

		if (inputTypes == null || inputTypes.length == 0) {
			return false;
		}

		// MMChain requires first input (X matrix) to be ROW partitioned
		if (inputTypes[0] != FType.ROW) {
			return false; // Instruction not created for non-ROW inputs
		}

		// LOUT behavior from MMChainFEDInstruction.java:90-142
		// Evidence: All three execution paths perform local aggregation:
		// - Lines 108,113,114: GET_VAR + aggAdd + setMatrixOutput (weighted+aligned path)
		// - Lines 120,125,126: GET_VAR + aggAdd + setMatrixOutput (non-weighted path)
		// - Lines 134,140,141: GET_VAR + aggAdd + setMatrixOutput (weighted+broadcast path)
		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		// Input FType constraint check (same as LOUT)
		if (inputTypes == null || inputTypes.length == 0) {
			return false;
		}

		if (inputTypes[0] != FType.ROW) {
			return false; // parseInstruction line 56: Only ROW supported
		}

		// FOUT behavior from MMChainFEDInstruction.java:1-144
		// Evidence: Searched entire file for FOUT implementation patterns:
		// - NO _fedOut field or isForcedFederated() checks
		// - NO setOutputFederated() calls
		// - NO out.setFedMapping() or setFederatedMapping() calls
		// - All paths ALWAYS call aggAdd (lines 113, 125, 140) + setMatrixOutput (lines 114, 126, 141)
		//
		// Conclusion: FOUT not implemented.
		// Mathematical reason: MMChain computes t(X) %*% f(X,v,w) where t(X) requires
		// aggregating across ROW partitions, making federated output semantically incorrect.
		return false;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		// Check input constraints first
		if (inputTypes == null || inputTypes.length == 0) {
			return "MMChain: Input types not available";
		}

		if (inputTypes[0] != FType.ROW) {
			return "MMChain: Requires ROW partitioned first input (X matrix), got: " + inputTypes[0]
				+ " (parseInstruction line 56)";
		}

		// Explain output behavior when inputs are valid
		return "MMChain: LOUT feasible via aggAdd (lines 113,125,140); "
			+ "FOUT not implemented (no _fedOut checks or setFedMapping found)";
	}
}
