/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.functions.fedplanner.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class FunctionCallRuleTest {

	@Test
	public void functionCallPropagatesFederatedPlacement() {
		Rulesets.FunctionCallRule rule = new Rulesets.FunctionCallRule();
		OpSig sig = new OpSig("fcall", OpCategory.OTHER, Map.of(), InputKind.MATRIX, InputKind.SCALAR);

		OpCaps caps = rule.caps(sig, Arrays.asList(null, FType.ROW), null);

		assertEquals(ExecType.FED, caps.exec());
		assertTrue(caps.foutFType().isPresent());
		assertEquals(FType.ROW, caps.foutFType().get());
	}

	@Test
	public void functionCallFallsBackToLocalWhenNoFedInput() {
		Rulesets.FunctionCallRule rule = new Rulesets.FunctionCallRule();
		OpSig sig = new OpSig("fcall", OpCategory.OTHER, Map.of(), InputKind.MATRIX);

		OpCaps caps = rule.caps(sig, Arrays.asList((FType) null), null);

		assertEquals(ExecType.CP, caps.exec());
		assertFalse(caps.foutFType().isPresent());
	}
}
