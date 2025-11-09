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

import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.*;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.fout.*;

/**
 * Validator for QuantilePick operations.
 *
 * VERIFICATION RESULTS:
 *
 * 1. QuantilePick Hop Classes Exploration:
 *    - Searched src/main/java/org/apache/sysds/hops/ for QPick/QuantilePick related classes
 *    - No dedicated QuantilePickOp class found
 *    - QuantilePick operations are distributed across multiple Hop types:
 *      a) UnaryOp: MEDIAN (OpOp1.MEDIAN), IQM (OpOp1.IQM)
 *         - See UnaryOp.java:204, 233 - creates PickByCount lops
 *      b) BinaryOp: IQM (OpOp2.IQM), INTERQUANTILE (OpOp2.INTERQUANTILE), MEDIAN (implicit)
 *         - See BinaryOp.java:260, 284, 345 - creates PickByCount lops
 *      c) TernaryOp: QUANTILE (OpOp3.QUANTILE), INTERQUANTILE (OpOp3.INTERQUANTILE)
 *         - See TernaryOp.java:246 - creates PickByCount lops
 *    - All create PickByCount lops with OpCode "qpick" (see PickByCount.java:32)
 *
 * 2. QuantilePickFEDInstruction Runtime Verification:
 *    - Instruction supports both COL/FULL and ROW partitioning (line 133-136)
 *    - processColumnQPick (line 676-735): Handles COL/FULL partitions
 *      * Executes operations on workers (line 682-725)
 *      * Collects results locally: res.add(response.getData()[0]) (line 719)
 *      * CRITICAL: Sets LOCAL output via ec.setScalarOutput/setMatrixOutput (line 732-735)
 *      * NO call to setFedMapping() → Does NOT support FOUT
 *    - processRowQPick (line 234-293): Handles ROW partitions
 *      * Consolidates results using histograms (line 283)
 *      * Computes global min/max across workers (line 268-274)
 *      * Sets LOCAL output via ec.setScalarOutput/setMatrixOutput (line 289, 292)
 *      * NO call to setFedMapping() → Does NOT support FOUT
 *
 * 3. FOUT Support Verification:
 *    - Compared with instructions that DO support FOUT:
 *      * BinaryMatrixMatrixFEDInstruction calls out.setFedMapping() to enable FOUT
 *      * AggregateUnaryFEDInstruction.processFederatedOutput() calls setFedMapping()
 *    - QuantilePickFEDInstruction NEVER calls setFedMapping() in any code path
 *    - Both processColumnQPick and processRowQPick only set LOCAL output
 *    - Conclusion: FOUT is NOT supported for ANY partitioning type
 *
 * 4. Related OpCodes:
 *    - Primary OpCode: "qpick" (PickByCount.OPCODE, see PickByCount.java:32)
 *    - Operation Types: VALUEPICK, RANGEPICK, IQM, MEDIAN (PickByCount.OperationTypes)
 *
 * FOUT Constraint Table Implementation:
 *
 * Instruction Class              | OP Type  | OpCode  | FOUT Possible? | FOUT Constraint/Reason
 * -------------------------------|----------|---------|----------------|------------------------------------------
 * QuantilePickFEDInstruction     | QPick    | qpick   | No             | No setFedMapping() call; always produces local output
 *
 * Constraint Logic:
 * - ALL partitioning types (ROW/COL/FULL) → DISALLOWED
 * - Reason: processInstruction() always collects results and sets local output
 * - No federated output path exists in the implementation
 *
 * CRITICAL CLARIFICATION about "COL/FULL only, ROW→LOUT" in constraint table:
 * - This refers to federated COMPUTATION support, NOT federated OUTPUT
 * - COL/FULL: Workers can compute quantiles independently (line 682-725), results collected
 * - ROW: Requires global histogram consolidation (line 234-293)
 * - Both paths end with ec.setScalarOutput/setMatrixOutput (local output)
 * - Neither path calls setFedMapping() → No FOUT support
 * - The instruction NEVER checks _fedOut field in processInstruction() (line 132-137)
 * - Compare with AggregateUnaryFEDInstruction which checks "if(_fedOut.isForcedFederated())"
 */
public class QuantilePickValidator extends OutputConstraintValidator {

	@Override
	public boolean canValidate(Hop hop) {
		// QuantilePick operations are represented as:
		// 1. UnaryOp with MEDIAN or IQM
		// 2. BinaryOp with IQM, INTERQUANTILE, or MEDIAN (weighted versions)
		// 3. TernaryOp with QUANTILE or INTERQUANTILE
		if (hop instanceof UnaryOp) {
			UnaryOp uop = (UnaryOp) hop;
			return uop.getOp() == OpOp1.MEDIAN || uop.getOp() == OpOp1.IQM;
		}
		else if (hop instanceof BinaryOp) {
			BinaryOp bop = (BinaryOp) hop;
			return bop.getOp() == OpOp2.IQM || bop.getOp() == OpOp2.INTERQUANTILE;
		}
		else if (hop instanceof TernaryOp) {
			TernaryOp top = (TernaryOp) hop;
			return top.getOp() == OpOp3.QUANTILE || top.getOp() == OpOp3.INTERQUANTILE;
		}
		return false;
	}

	@Override
	public boolean isLOUTFeasible(Hop hop, FType[] inputTypes) {
		// Evidence from QuantilePickFEDInstruction.java runtime analysis:
		// - processInstruction (line 132-137): Dispatches based on input FType
		//   * COL/FULL → processColumnQPick (line 676-736)
		//   * ROW → processRowQPick (line 234-293)
		//
		// - processColumnQPick (line 676-736):
		//   * Line 719: res.add(response.getData()[0]) - aggregates worker results
		//   * Line 732: ec.setScalarOutput(output.getName(), new DoubleObject(...))
		//   * Line 735: ec.setMatrixOutput(output.getName(), (MatrixBlock) res.get(0))
		//
		// - processRowQPick (line 234-293):
		//   * Line 283: createHistogram() - builds global histogram from workers
		//   * Line 292: getSingleQuantileResult() → line 424: ec.setScalarOutput()
		//   * Line 289: computeMultipleQuantiles() → line 377: ec.setMatrixOutput()
		//
		// Both execution paths (COL/FULL and ROW) support federated computation
		// and produce local output via ec.setScalarOutput or ec.setMatrixOutput
		// Conclusion: LOUT is supported for ALL partition types (ROW/COL/FULL)

		return true;
	}

	@Override
	public boolean isFOUTFeasible(Hop hop, FType[] inputTypes) {
		// Evidence from QuantilePickFEDInstruction.java - comprehensive setFedMapping search:
		// - Full file search result: Only ONE occurrence at line 187
		//   * Line 187: in.setFedMapping(newFedMap) - sets federated mapping on INTERMEDIATE variable
		//   * This is in getEquiHeightBins() method (line 139-232) for internal computation
		//   * NOT on the output variable
		//
		// - processInstruction (line 132-137): Does NOT check _fedOut field
		//   * Compare with AggregateUnaryFEDInstruction which has:
		//     if(_fedOut.isForcedFederated()) processFederatedOutput(...)
		//   * QuantilePickFEDInstruction has NO such check
		//
		// - processColumnQPick (line 676-736):
		//   * Line 719: res.add(response.getData()[0]) - aggregates results LOCALLY
		//   * Line 732-735: ec.setScalarOutput/ec.setMatrixOutput - sets LOCAL output only
		//   * NO setFedMapping call on output variable (ec.getMatrixObject(output.getName()))
		//
		// - processRowQPick (line 234-293):
		//   * Line 246-274: Aggregates global min/max/weights from all workers
		//   * Line 283: createHistogram() - consolidates histograms locally
		//   * Line 289, 292: Methods that end with ec.setScalarOutput/ec.setMatrixOutput
		//   * NO setFedMapping call on output variable
		//
		// - Output variable NEVER gets setFedMapping() call in ANY code path
		// - The _fedOut field (inherited from BinaryFEDInstruction) is NEVER checked
		// Conclusion: FOUT is NOT supported for ANY partition type (ROW/COL/FULL)

		return false;
	}

	@Override
	public String getConstraintMessage(Hop hop, FType[] inputTypes) {
		String opName = getOperationName(hop);

		// Based on runtime analysis:
		// - LOUT: Supported for ALL partition types (ROW/COL/FULL)
		// - FOUT: NOT supported (no setFedMapping call, only ec.setScalarOutput/ec.setMatrixOutput)
		return "QuantilePick (" + opName + "): FOUT not supported - quantile computation requires " +
		       "global aggregation across workers (QuantilePickFEDInstruction.processInstruction always " +
		       "produces local output, never calls setFedMapping)";
	}

	/**
	 * Helper method to get a readable operation name for error messages.
	 */
	private String getOperationName(Hop hop) {
		if (hop instanceof UnaryOp) {
			OpOp1 op = ((UnaryOp) hop).getOp();
			return op == OpOp1.MEDIAN ? "median" : "iqm";
		}
		else if (hop instanceof BinaryOp) {
			OpOp2 op = ((BinaryOp) hop).getOp();
			return op == OpOp2.IQM ? "iqm" : "interquantile";
		}
		else if (hop instanceof TernaryOp) {
			OpOp3 op = ((TernaryOp) hop).getOp();
			return op == OpOp3.QUANTILE ? "quantile" : "interquantile";
		}
		return "qpick";
	}
}
