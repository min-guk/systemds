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
package org.apache.sysds.test.component.federated;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** The origin-bound aggregate-only contract applies before selector invocation. */
public class PrivateAggregateReleasePolicyTest {

	@Test
	public void elementwisePrivateAggregateMustNotBeCollectedOrLocallyProduced() {
		Hop shifted = new BinaryOp("shifted", DataType.MATRIX, ValueType.FP64,
			OpOp2.MINUS, raw(), new LiteralOp(1.0));
		var decision = ExecPlacementPolicy.decide(shifted, Privacy.PRIVATE_AGGREGATE,
			FType.ROW, caps(ExecType.FED, FederatedOutput.FOUT));
		assertTrue("native worker-resident elementwise execution remains legal", decision.allowFED_FOUT);
		assertNoCoordinatorValue(decision);
	}

	@Test
	public void transientPrivateAggregateAliasesMustNotAuthorizeCollection() {
		Hop input = raw();
		for(Hop alias : new Hop[] {input, HopRewriteUtils.createTransientWrite("Xnext", input)}) {
			var decision = ExecPlacementPolicy.decide(alias, Privacy.PRIVATE_AGGREGATE,
				FType.ROW, caps(ExecType.FED, FederatedOutput.FOUT));
			assertTrue("a native FED/FOUT transient alias is metadata forwarding", decision.allowFED_FOUT);
			assertNoCoordinatorValue(decision);
		}
	}

	@Test
	public void privateAggregateNativeLocalOutputMustFailClosed() {
		Hop nonAggregate = new BinaryOp("unreleased", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, raw(), new LiteralOp(1.0));
		var decision = ExecPlacementPolicy.decide(nonAggregate, Privacy.PRIVATE_AGGREGATE,
			FType.ROW, caps(ExecType.FED, FederatedOutput.LOUT));
		assertFalse("a native collection is not legalized by naming its input private-aggregate",
			decision.hasAny());
	}

	@Test
	public void strictPrivateOriginBoundBehaviorIsPreserved() {
		var decision = ExecPlacementPolicy.decide(raw(), Privacy.PRIVATE,
			FType.ROW, caps(ExecType.FED, FederatedOutput.FOUT));
		assertTrue(decision.allowFED_FOUT);
		assertNoCoordinatorValue(decision);
	}

	private static void assertNoCoordinatorValue(ExecPlacementPolicy.Decision decision) {
		assertFalse("CP/LOUT would release an unaggregated origin-bound value", decision.allowCP_LOUT);
		assertFalse("CP/FOUT still computes/materializes that value at the coordinator", decision.allowCP_FOUT);
		assertFalse("FED/LOUT still collects the protected result", decision.allowFED_LOUT);
	}

	private static DataOp raw() {
		return new DataOp("X", DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, null, 4, 2, 8, 1000);
	}

	private static OpCaps caps(ExecType exec, FederatedOutput output) {
		return new OpCaps.Builder().exec(exec).placement(output).build();
	}
}
