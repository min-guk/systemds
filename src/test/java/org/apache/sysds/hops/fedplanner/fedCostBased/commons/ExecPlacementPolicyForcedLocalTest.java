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
package org.apache.sysds.hops.fedplanner.fedCostBased.commons;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class ExecPlacementPolicyForcedLocalTest {
	@Test
	public void onlyRuntimeQuaternaryWithForcedLocalBranchIsAdvertised() {
		LiteralOp x = new LiteralOp(1D);
		LiteralOp u = new LiteralOp(2D);
		LiteralOp v = new LiteralOp(3D);
		QuaternaryOp wdivmm = new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
			OpOp4.WDIVMM, x, u, v, new LiteralOp(-1), 0, true, false);
		QuaternaryOp wsigmoid = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, x, u, v, false, false);

		assertTrue("QuaternaryWDivMMFEDInstruction implements forced-local collection",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(wdivmm));
		assertFalse("Other quaternary kernels must not inherit WDivMM's runtime contract",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(wsigmoid));
	}

	@Test
	public void onlyOrdinaryCtableAdvertisesForcedLocalAggregation() {
		DataOp rix = matrix("rix");
		DataOp cix = matrix("cix");
		TernaryOp unweighted = new TernaryOp("table", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, rix, cix, new LiteralOp(1D));
		TernaryOp weighted = new TernaryOp("table-weighted", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, rix, cix, matrix("weights"));
		TernaryOp sixInput = new TernaryOp("table-six", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, rix, cix, new LiteralOp(1D), new LiteralOp(10L),
			new LiteralOp(20L), new LiteralOp(false));
		TernaryOp expand = new TernaryOp("table-expand", DataType.MATRIX, ValueType.FP64,
			OpOp3.CTABLE, new LiteralOp("seq(1,10,1)"), cix, new LiteralOp(1D));
		TernaryOp unrelated = new TernaryOp("ifelse", DataType.MATRIX, ValueType.FP64,
			OpOp3.IFELSE, new LiteralOp(true), rix, cix);
		TernaryOp scalar = new TernaryOp("table-scalar", DataType.SCALAR, ValueType.FP64,
			OpOp3.CTABLE, new LiteralOp(1D), new LiteralOp(2D), new LiteralOp(1D));

		assertTrue("ordinary unweighted CTABLE collects aggregate output locally",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(unweighted));
		assertTrue("ordinary weighted CTABLE collects aggregate output locally",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(weighted));
		assertTrue("SliceLine six-input CTABLE uses ordinary processRequest",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(sixInput));
		assertTrue("fixture must exercise the sequence rewrite", expand.isSequenceRewriteApplicable(true));
		assertFalse("ctableexpand row-preserving binding needs separate privacy review",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(expand));
		assertFalse("unrelated ternary operations cannot inherit CTABLE release authority",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(unrelated));
		assertFalse("non-matrix CTABLE cannot advertise matrix collection",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(scalar));
		assertFalse("missing Hop cannot advertise runtime support",
			ExecPlacementPolicy.supportsForcedLocalFederatedOutput(null));

		OpCaps fout = OpCaps.newBuilder().category(OpCategory.OTHER).opcode("ctable")
			.exec(ExecType.FED).placement(FederatedOutput.FOUT).fout(true, FType.ROW)
			.reason(ReasonCode.OK).build();
		ExecPlacementPolicy.Decision strict = ExecPlacementPolicy.decide(
			sixInput, Privacy.PRIVATE, FType.ROW, fout);
		assertTrue("strict PRIVATE may remain at the origin", strict.allowFED_FOUT);
		assertFalse("strict PRIVATE cannot collect CTABLE output", strict.allowFED_LOUT);
		assertFalse("strict PRIVATE cannot execute CTABLE locally", strict.allowCP_LOUT);
		ExecPlacementPolicy.Decision released = ExecPlacementPolicy.decide(
			sixInput, Privacy.PRIVATE_AGGREGATE_TO_PUBLIC, FType.ROW, fout);
		assertTrue("only aggregate-released CTABLE may collect the result", released.allowFED_LOUT);
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.TRANSIENTREAD,
			name, 10, 2, -1, 1_000);
	}
}
