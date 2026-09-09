/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sysds.test.functions.fedplanner.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class BinaryElemwiseFullProfileTest {
	private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 1000);

	@Test
	public void fullMatrixWithLocalVectorRetainsFullProfile() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		OpSig sig = OpSig.of(OpOp2.LESS.toString(), OpCategory.BINARY_EWISE, Map.of(),
			InputKind.MATRIX, InputKind.MATRIX);

		FTypeProfile profile = rule.profile(sig,
			List.of(List.of(FType.FULL), Collections.singletonList(null)), UNKNOWN_SHAPE);

		assertEquals(List.of(FType.FULL), profile.outputs());
	}

	@Test
	public void localScalarWithFullMatrixRetainsFullProfile() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		OpSig sig = OpSig.of(OpOp2.MULT.toString(), OpCategory.BINARY_EWISE, Map.of(),
			InputKind.SCALAR, InputKind.MATRIX);

		FTypeProfile profile = rule.profile(sig,
			List.of(Collections.singletonList(null), List.of(FType.FULL)), UNKNOWN_SHAPE);

		assertEquals(List.of(FType.FULL), profile.outputs());
	}

	@Test
	public void twoFullInputsRetainFullProfile() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		OpSig sig = OpSig.of(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE, Map.of(),
			InputKind.MATRIX, InputKind.MATRIX);

		FTypeProfile profile = rule.profile(sig,
			List.of(List.of(FType.FULL), List.of(FType.FULL)), UNKNOWN_SHAPE);

		assertEquals(List.of(FType.FULL), profile.outputs());
	}

	@Test
	public void rowAndFullAloneDoNotCreateFullProfile() {
		Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
		OpSig sig = OpSig.of(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE, Map.of(),
			InputKind.MATRIX, InputKind.MATRIX);

		FTypeProfile profile = rule.profile(sig,
			Arrays.asList(List.of(FType.ROW), List.of(FType.FULL)), UNKNOWN_SHAPE);

		assertFalse(profile.outputs().contains(FType.FULL));
	}
}
