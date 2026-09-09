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
package org.apache.sysds.hops.fedplanner.rules;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Shape guards must run before the single-range FULL append fast path. */
public class AppendFullShapeGuardTest {
	private static final List<List<FType>> FULL_EITHER_SIDE = List.of(
		Arrays.asList(FType.FULL, null), Arrays.asList(null, FType.FULL));

	@Test
	public void fullCbindRejectsKnownRowMismatchInEitherOperandOrder() {
		for(List<FType> inputs : FULL_EITHER_SIDE) {
			OpCaps caps = caps(OpOp2.CBIND, inputs, shape(10, 3, 11, 4));
			Assert.assertNotEquals(inputs.toString(), ExecType.FED, caps.exec());
			Assert.assertFalse(inputs.toString(), caps.foutEnabled());
			Assert.assertEquals(inputs.toString(), ReasonCode.DIM_MISMATCH_ROWS, caps.reason());
		}
	}

	@Test
	public void fullRbindRejectsKnownColumnMismatchInEitherOperandOrder() {
		for(List<FType> inputs : FULL_EITHER_SIDE) {
			OpCaps caps = caps(OpOp2.RBIND, inputs, shape(3, 10, 4, 11));
			Assert.assertNotEquals(inputs.toString(), ExecType.FED, caps.exec());
			Assert.assertFalse(inputs.toString(), caps.foutEnabled());
			Assert.assertEquals(inputs.toString(), ReasonCode.DIM_MISMATCH_COLS, caps.reason());
		}
	}

	@Test
	public void unknownNonAppendAxisDefersTheCheckWithoutClosingFull() {
		for(List<FType> inputs : FULL_EITHER_SIDE) {
			assertFullWithDeferredDimension(caps(OpOp2.CBIND, inputs, shape(-1, 3, 10, 4)), "rows");
			assertFullWithDeferredDimension(caps(OpOp2.RBIND, inputs, shape(3, -1, 4, 10)), "cols");
		}
	}

	@Test
	public void matchingNonAppendAxisRetainsFullInEitherOperandOrder() {
		for(List<FType> inputs : FULL_EITHER_SIDE) {
			assertFull(caps(OpOp2.CBIND, inputs, shape(10, 3, 10, 4)));
			assertFull(caps(OpOp2.RBIND, inputs, shape(3, 10, 4, 10)));
		}
	}

	private static OpCaps caps(OpOp2 op, List<FType> inputs, ShapeHint shape) {
		return new Rulesets.AppendRule().caps(OpSig.of(op.toString(), OpCategory.APPEND,
			Map.of(), InputKind.MATRIX, InputKind.MATRIX), inputs, shape);
	}

	private static ShapeHint shape(long rowsA, long colsA, long rowsB, long colsB) {
		return new ShapeHint(-1, -1, 1000, Optional.of(true), rowsA, colsA, rowsB, colsB);
	}

	private static void assertFull(OpCaps caps) {
		Assert.assertEquals(ExecType.FED, caps.exec());
		Assert.assertEquals(FederatedOutput.FOUT, caps.placement());
		Assert.assertTrue(caps.foutEnabled());
		Assert.assertEquals(FType.FULL, caps.foutFType().orElse(null));
	}

	private static void assertFullWithDeferredDimension(OpCaps caps, String axis) {
		assertFull(caps);
		Assert.assertTrue("missing deferred " + axis + " check note: " + caps.notes(),
			caps.notes().stream().anyMatch(note -> note.message().contains(axis + " unknown")));
	}
}
