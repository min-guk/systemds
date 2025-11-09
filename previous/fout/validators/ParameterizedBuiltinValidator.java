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
 * Validator for ParameterizedBuiltin operations.
 *
 * LOUT Analysis:
 * - CONTAINS (line 176-183): Returns scalar via aggBooleanScalar + GET_VAR → LOUT only
 * - Other ops: No LOUT path (only setFedMapping) → LOUT not feasible
 *
 * FOUT Constraints:
 * - CONTAINS: Returns LOCAL scalar (BooleanObject), no FederationMap → FOUT blocked
 * - Supported FED ops: Maintain partition structure via setFedMapping → FOUT allowed
 *   (replace, rmempty, lowertri, uppertri, transformdecode, transformapply, tokenize)
 *
 * Evidence: ParameterizedBuiltinFEDInstruction.java:176-786
 */
public class ParameterizedBuiltinValidator extends OutputConstraintValidator {

	// FED-supported operations that produce federated output via setFedMapping
	// Evidence: ParameterizedBuiltinFEDInstruction.java:90-92 (PARAM_BUILTINS array)
	private static final java.util.Set<org.apache.sysds.common.Types.ParamBuiltinOp> FOUT_OPERATIONS =
		java.util.EnumSet.of(
			org.apache.sysds.common.Types.ParamBuiltinOp.REPLACE,
			org.apache.sysds.common.Types.ParamBuiltinOp.RMEMPTY,
			org.apache.sysds.common.Types.ParamBuiltinOp.LOWER_TRI,
			org.apache.sysds.common.Types.ParamBuiltinOp.UPPER_TRI,
			org.apache.sysds.common.Types.ParamBuiltinOp.TRANSFORMDECODE,
			org.apache.sysds.common.Types.ParamBuiltinOp.TRANSFORMAPPLY,
			org.apache.sysds.common.Types.ParamBuiltinOp.TOKENIZE
		);

	@Override
	public boolean canValidate(Hop hop) {
		return hop instanceof ParameterizedBuiltinOp;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		org.apache.sysds.common.Types.ParamBuiltinOp opCode = ((ParameterizedBuiltinOp) hop).getOp();

		// CONTAINS: Aggregates to scalar via GET_VAR
		// Evidence: ParameterizedBuiltinFEDInstruction.java:176-183
		//   Line 180: FederatedRequest.GET_VAR
		//   Line 182: FederationUtils.aggBooleanScalar(tmp)
		//   Line 183: ec.setVariable(..., new BooleanObject(ret))
		if (opCode == org.apache.sysds.common.Types.ParamBuiltinOp.CONTAINS) {
			return true;
		}

		// FOUT-only operations: No GET_VAR path, only setFedMapping
		// Evidence: ParameterizedBuiltinFEDInstruction.java:185-786
		//   All other operations call setFedMapping without GET_VAR option
		if (isFOUTOperation(opCode)) {
			return false;
		}

		// Unsupported operations
		return false;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		org.apache.sysds.common.Types.ParamBuiltinOp opCode = ((ParameterizedBuiltinOp) hop).getOp();

		// CONTAINS: Returns scalar, no FederationMap
		// Evidence: ParameterizedBuiltinFEDInstruction.java:183
		//   ec.setVariable(output.getName(), new BooleanObject(ret))
		//   No setFedMapping call
		if (opCode == org.apache.sysds.common.Types.ParamBuiltinOp.CONTAINS) {
			return false;
		}

		// FED-supported operations maintain partition structure
		// Evidence: ParameterizedBuiltinFEDInstruction.java
		//   REPLACE (line 201): out.setFedMapping(mo.getFedMapping().copyWithNewID(...))
		//   RMEMPTY (line 448,461,474,610,623,636): out.setFedMapping(...)
		//   LOWERTRI/UPPERTRI (line 296): out.setFedMapping(diagFedMap)
		//   TRANSFORMDECODE (line 741): decodedFrame.setFedMapping(decodedMapping)
		//   TRANSFORMAPPLY (line 786): sets federated mapping
		//   TOKENIZE (line 231): out.setFedMapping(fedMap.copyWithNewID(...))
		if (isFOUTOperation(opCode)) {
			return true;
		}

		// Unsupported operations
		return false;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		org.apache.sysds.common.Types.ParamBuiltinOp opCode = ((ParameterizedBuiltinOp) hop).getOp();

		if (opCode == org.apache.sysds.common.Types.ParamBuiltinOp.CONTAINS) {
			return "ParameterizedBuiltin(CONTAINS): Returns LOCAL boolean scalar (LOUT only)";
		}

		if (isFOUTOperation(opCode)) {
			return "ParameterizedBuiltin(" + opCode + "): Maintains federated output (FOUT only)";
		}

		return "ParameterizedBuiltin(" + opCode + "): No federated implementation (CP-only)";
	}

	/**
	 * Check if the operation is a FED-supported FOUT operation.
	 * These operations maintain federated output via setFedMapping.
	 *
	 * @param opCode The operation code to check
	 * @return true if operation supports FOUT
	 */
	private boolean isFOUTOperation(org.apache.sysds.common.Types.ParamBuiltinOp opCode) {
		return FOUT_OPERATIONS.contains(opCode);
	}
}
