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

import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class MMChainRuleTest {
	private final Rulesets.MMChainRule rule = new Rulesets.MMChainRule();
	private static final OpSig XTXV = OpSig.of(Opcodes.MMCHAIN.toString(), OpCategory.OTHER,
		Map.of("mmchain.type", "XtXv"), InputKind.MATRIX, InputKind.MATRIX);

	@Test
	public void rowInputCompilesFederatedWithLocalOutput() {
		OpCaps caps = rule.caps(XTXV, List.of(FType.ROW, FType.BROADCAST),
			new ShapeHint(10, 4, 1024));
		assertEquals(ExecType.FED, caps.exec());
		assertEquals(FederatedOutput.LOUT, caps.placement());
	}

	@Test
	public void singleRangeFullInputCompilesFederatedWithLocalOutput() {
		OpCaps caps = rule.caps(XTXV, List.of(FType.FULL, FType.BROADCAST),
			new ShapeHint(10, 4, 1024, true));
		assertEquals(ExecType.FED, caps.exec());
		assertEquals(FederatedOutput.LOUT, caps.placement());
	}

	@Test
	public void unknownFullCardinalityFailsClosed() {
		OpCaps caps = rule.caps(XTXV, List.of(FType.FULL, FType.BROADCAST),
			new ShapeHint(10, 4, 1024));
		assertEquals(ExecType.CP, caps.exec());
		assertEquals(FederatedOutput.LOUT, caps.placement());
	}
}
