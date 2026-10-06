/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sysds.hops.fedplanner.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

/** Matrix elementwise operations retain a runtime-supported replicated output. */
public class BinaryElemwiseBroadcastMatrixRuleTest {
	private static final ShapeHint MATRIX_SHAPE = new ShapeHint(
		128, 64, 8192, Optional.empty(), 128, 64, 128, 64);

	@Test
	public void replicatedAndLocalMatrixPairsPublishBroadcastFout() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		OpSig sig = matrixMinus(Map.of());
		for(List<FType> inputs : List.of(
			List.of(FType.BROADCAST, FType.BROADCAST),
			Arrays.asList(FType.BROADCAST, null),
			Arrays.asList(null, FType.BROADCAST))) {
			OpCaps caps = rule.caps(sig, inputs, MATRIX_SHAPE);
			assertEquals("runtime-supported pair must execute federated: " + inputs,
				ExecType.FED, caps.exec());
			assertEquals(FederatedOutput.FOUT, caps.placement());
			assertEquals(FType.BROADCAST, caps.foutFType().orElse(null));
			assertEquals(List.of(FType.BROADCAST), rule.profile(sig,
				List.of(Collections.singletonList(inputs.get(0)), Collections.singletonList(inputs.get(1))),
				MATRIX_SHAPE).outputs());
		}
	}

	@Test
	public void immutableProfileCandidatesWithoutCompatibleReplicaDoNotThrow() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		List<List<FType>> candidates = List.of(
			List.of(FType.ROW),
			List.of(FType.ROW, FType.COL, FType.FULL, FType.PART, FType.BROADCAST));

		assertEquals(List.of(FType.ROW),
			rule.profile(matrixMinus(Map.of()), candidates, MATRIX_SHAPE).outputs());
	}

	@Test
	public void replicatedMatrixPairStillHonorsRepresentationGuard() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		OpCaps caps = rule.caps(matrixMinus(Map.of("rc.guardOverride", "false")),
			Arrays.asList(FType.BROADCAST, null), MATRIX_SHAPE);

		assertEquals(ExecType.CP, caps.exec());
		assertFalse(caps.foutEnabled());
	}

	private static OpSig matrixMinus(Map<String,String> attrs) {
		return OpSig.of(OpOp2.MINUS.toString(), OpCategory.BINARY_EWISE, attrs,
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX);
	}
}
