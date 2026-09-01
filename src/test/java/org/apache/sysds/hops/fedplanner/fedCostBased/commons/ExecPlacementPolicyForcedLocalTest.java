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
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
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
}
