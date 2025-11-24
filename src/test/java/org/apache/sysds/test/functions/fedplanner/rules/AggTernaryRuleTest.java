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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class AggTernaryRuleTest {

  private static final OpSig TAKPM =
      OpSig.of(Opcodes.TAKPM.toString(), OpCategory.AGG_TERNARY, Map.of());
  private static final OpSig TACKPM =
      OpSig.of(Opcodes.TACKPM.toString(), OpCategory.AGG_TERNARY, Map.of());
  private static final OpSig QUANTILE_SIG =
      OpSig.of(OpOp3.QUANTILE.toString(), OpCategory.QUANTILE_PICK, Map.of());

  private final Rulesets.AggTernaryRule aggRule = new Rulesets.AggTernaryRule();
  private final Rulesets.QuantileInterquantileCtableDenyRule denyRule =
      new Rulesets.QuantileInterquantileCtableDenyRule();

  @Test
  public void caseAAlignedAllFederated() {
    OpCaps caps = aggRule.caps(
        TAKPM,
        List.of(FType.ROW, FType.ROW, FType.ROW),
        unknownShape());
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void caseBScalarOutputAllowed() {
    OpCaps caps = aggRule.caps(
        TACKPM,
        Arrays.asList(FType.COL, FType.COL, null),
        scalarShape());
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void caseBMatrixOutputRejected() {
    OpCaps caps = aggRule.caps(
        TAKPM,
        List.of(FType.COL, FType.COL, FType.FULL),
        unknownShape());
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.NOT_IMPLEMENTED_FED_MATRIX_OUT, caps.reason());
  }

  @Test
  public void caseCBroadcastInputs() {
    OpCaps caps = aggRule.caps(
        TAKPM,
        Arrays.asList(FType.ROW, null, FType.FULL),
        scalarShape());
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void insufficientFederatedInputs() {
    OpCaps caps = aggRule.caps(
        TAKPM,
        Arrays.asList(null, FType.ROW, FType.ROW),
        scalarShape());
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.UNALIGNED_OR_INSUFFICIENT_FED_INPUTS, caps.reason());
  }

  @Test
  public void quantileExplicitlyDenied() {
    OpCaps caps = denyRule.caps(QUANTILE_SIG, List.of(), unknownShape());
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.MISSING_FED_INSTRUCTION, caps.reason());
  }

  private static ShapeHint scalarShape() {
    return new ShapeHint(1, 1, 0);
  }

  private static ShapeHint unknownShape() {
    return new ShapeHint(-1, -1, 0);
  }
}
