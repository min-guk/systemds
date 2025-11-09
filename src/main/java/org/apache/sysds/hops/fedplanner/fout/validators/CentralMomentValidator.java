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

import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fout.OutputConstraintValidator;

/**
 * Validator for CentralMoment operation (cm).
 *
 * Runtime Analysis (CentralMomentFEDInstruction.java):
 * - Computes statistical moments (mean, variance, skewness, kurtosis)
 * - Workers compute partial CM_COV_Object statistics (line 90-119)
 * - Results aggregated locally via reduce operation (line 121-122)
 * - Final output: DoubleObject (SCALAR) via ec.setScalarOutput (line 124)
 *
 * LOUT: Feasible - local aggregation via reduce() pattern
 * FOUT: NOT Feasible - Scalar output, no setFedMapping(), _fedOut field parsed but unused in processInstruction()
 */
public class CentralMomentValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// Validate TernaryOp with OpOp3.MOMENT
		// Evidence: TernaryOp.java:199-200 checks for OpOp3.MOMENT
		if (!(hop instanceof TernaryOp && ((TernaryOp)hop).getOp() == OpOp3.MOMENT)) {
			return false;
		}

		// Verify scalar output (defensive check)
		// Evidence: CentralMomentFEDInstruction.java:124 always produces DoubleObject
		return hop.getDataType() == org.apache.sysds.common.Types.DataType.SCALAR;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// LOUT is feasible - workers compute partial CM_COV_Object statistics,
		// coordinator aggregates them locally and produces final scalar
		// Evidence:
		// - CentralMomentFEDInstruction.java:86-119 - fedMapping.mapParallel() computes partial statistics
		// - CentralMomentFEDInstruction.java:121-122 - reduce() aggregates results locally
		// - CentralMomentFEDInstruction.java:124 - ec.setScalarOutput() outputs scalar
		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		// FOUT is NOT feasible - scalar output cannot maintain federated mapping
		// Evidence:
		// - CentralMomentFEDInstruction.java:124 - output is scalar (DoubleObject)
		// - No setOutputFedMapping() call in processInstruction()
		// - _fedOut field parsed (line 58) but unused in processInstruction()
		// - Scalar results cannot be partitioned across workers
		return false;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		return "CentralMoment: LOUT only (produces scalar output)";
	}
}
