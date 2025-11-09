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

/**
 * Result container for FOUT (Federated Output) constraint validation.
 * Contains whether LOUT/FOUT are feasible and the reasoning/constraint message.
 */
public class OutputConstraintResult {
	private final boolean loutFeasible;
	private final boolean foutFeasible;
	private final String constraintMessage;

	public OutputConstraintResult(boolean loutFeasible, boolean foutFeasible, String constraintMessage) {
		this.loutFeasible = loutFeasible;
		this.foutFeasible = foutFeasible;
		this.constraintMessage = constraintMessage;
	}

	public boolean isLOUTFeasible() {
		return loutFeasible;
	}

	public boolean isFOUTFeasible() {
		return foutFeasible;
	}

	public String getConstraintMessage() {
		return constraintMessage;
	}

	/**
	 * Both LOUT and FOUT are feasible
	 * Use case: AggregateUnary partial aggregations (conditional _fedOut)
	 */
	public static OutputConstraintResult both(String message) {
		return new OutputConstraintResult(true, true, message);
	}

	/**
	 * Only LOUT is feasible (FOUT is blocked by runtime)
	 * Use case: MMChain (only GET_VAR + aggAdd, no setFedMapping)
	 */
	public static OutputConstraintResult loutOnly(String reason) {
		return new OutputConstraintResult(true, false, "FOUT blocked: " + reason);
	}

	/**
	 * Only FOUT is feasible (LOUT is blocked by runtime)
	 * Use case: BinaryMatrixMatrix (only setOutputFedMapping, no GET_VAR)
	 */
	public static OutputConstraintResult foutOnly(String reason) {
		return new OutputConstraintResult(false, true, "LOUT blocked: " + reason);
	}

	/**
	 * Neither is feasible (error case - should not happen in practice)
	 */
	public static OutputConstraintResult neither(String reason) {
		return new OutputConstraintResult(false, false, "ERROR: " + reason);
	}

	// Backward compatibility aliases
	public static OutputConstraintResult allowed(String message) {
		return both(message);
	}

	public static OutputConstraintResult disallowed(String reason) {
		return loutOnly(reason);
	}

	public static OutputConstraintResult conditional(String constraint) {
		return both("CONDITIONAL: " + constraint);
	}

	@Override
	public String toString() {
		String feasibility = "";
		if (loutFeasible && foutFeasible) feasibility = "BOTH";
		else if (loutFeasible) feasibility = "LOUT_ONLY";
		else if (foutFeasible) feasibility = "FOUT_ONLY";
		else feasibility = "NEITHER";
		return feasibility + ": " + constraintMessage;
	}
}
