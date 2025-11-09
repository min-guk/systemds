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

import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fout.*;

/**
 * Validator for Covariance operation (cov).
 *
 * ANALYSIS:
 * - Runtime: CovarianceFEDInstruction.java
 * - Output type: Scalar (DoubleObject)
 * - LOUT feasibility: Always feasible (scalar output via ec.setVariable/setScalarOutput)
 * - FOUT feasibility: Never feasible (scalar cannot be federated)
 *
 * LOUT Analysis:
 * - Three execution paths, all producing scalar output:
 *   1. processAlignedFedCov (lines 105-134): Both matrices and weights aligned
 *   2. processFedCovWeights (lines 136-159): Matrices aligned, weights broadcasted
 *   3. processCov (lines 161-222): One federated, one local matrix
 * - Evidence: CovarianceFEDInstruction.java:126,132,158,217
 *   ec.setVariable(output.getName(), new DoubleObject(...))
 *   ec.setScalarOutput(output.getName(), new DoubleObject(...))
 * - No federated mapping set on output (no setOutputFedMapping call)
 *
 * FOUT Constraints:
 * - Scalar output cannot maintain federated mapping
 * - Evidence: CovarianceFEDInstruction.java execution flow:
 *   - Lines 118,151: GET_VAR retrieves partial covariances and means from workers
 *   - Lines 125,131,157: aggCov/aggWeightedCov aggregate results locally
 *   - Lines 126,132,158,217: Final scalar result set via setVariable/setScalarOutput
 *   - Lines 161-222: processCov() uses FederatedUDF pattern, reduces to single CM_COV_Object (line 215)
 * - No federated output flag handling (_fedOut flag exists but not used in scalar paths)
 */
public class CovarianceValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// Validate TernaryOp with OpOp3.COV
		// Evidence: TernaryOp maps to CovarianceFEDInstruction via OpOp3.COV
		return hop instanceof TernaryOp &&
		       ((TernaryOp)hop).getOp() == OpOp3.COV;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// LOUT Feasibility Analysis:
		// Evidence:
		// - CovarianceFEDInstruction.java:126,132,158,217 - ec.setVariable/setScalarOutput() sets scalar result locally
		//
		// Execution flow (3 paths, all produce scalar):
		//
		// Path 1: processAlignedFedCov (lines 105-134) - aligned matrices/weights
		// 1. Workers compute local covariance + means (lines 109-122)
		// 2. GET_VAR retrieves partial results (line 118)
		// 3. Coordinator aggregates using aggCov/aggWeightedCov (lines 125,131)
		// 4. Final scalar written via ec.setVariable (lines 126,132)
		//
		// Path 2: processFedCovWeights (lines 136-159) - aligned matrices, broadcast weights
		// 1. Weights broadcasted to workers (line 139)
		// 2. Workers compute weighted covariance + means (lines 145-156)
		// 3. GET_VAR retrieves results (line 151)
		// 4. Coordinator aggregates using aggWeightedCov (line 157)
		// 5. Final scalar written via ec.setVariable (line 158)
		//
		// Path 3: processCov (lines 161-222) - one federated, one local matrix
		// 1. Workers execute COVFunction/COVWeightsFunction UDF (lines 183-200)
		// 2. Results collected in globalCmobj list (line 206)
		// 3. Reduce aggregates CM_COV_Objects (line 215)
		// 4. Final scalar written via ec.setScalarOutput (line 217)
		//
		// Conclusion: LOUT always feasible (scalar output inherently local)
		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		// FOUT Feasibility Analysis:
		// Evidence:
		// - CovarianceFEDInstruction.java:126,132,158,217 - output is scalar (DoubleObject)
		// - No setOutputFedMapping() call anywhere in processInstruction()
		// - _fedOut flag exists (line 68) but not checked in scalar output paths
		// - Scalar results cannot maintain federated distribution
		//
		// Technical reason:
		// - Covariance operations (cov(X,Y), weighted cov(X,Y,W)) produce
		//   a single scalar value that aggregates information across all data
		// - This scalar cannot be partitioned or distributed across workers
		// - FederationMap requires matrix structure (rows/columns) to maintain
		//
		// Comparison with AggregateBinary (matrix multiplication):
		// - AggregateBinary can produce matrix output → FOUT possible via setOutputFedMapping()
		// - Covariance only produces scalar → FOUT structurally impossible
		//
		// Conclusion: FOUT never feasible (scalar output)
		return false;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		// Covariance always produces scalar output, cannot be federated
		return "Covariance produces scalar output, cannot maintain federated mapping (FOUT not supported)";
	}
}
