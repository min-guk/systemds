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
import java.util.List;
import java.util.Map;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class BinaryMMTsmmRuleTest {
  private static final ShapeHint UNKNOWN = new ShapeHint(-1, -1, 0);
  private final Rulesets.BinaryMMRule rule = new Rulesets.BinaryMMRule();

  @Test
  public void tsmmLeftRowUsesFedLoutOnly() {
    OpCaps caps = rule.caps(sig(Map.of("tsmm.type", "LEFT")), Arrays.asList(FType.COL, FType.ROW), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME, caps.reason());
  }

  @Test
  public void tsmmRightColUsesFedLoutOnly() {
    OpCaps caps = rule.caps(sig(Map.of("tsmm.type", "RIGHT")), Arrays.asList(FType.COL, FType.ROW), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME, caps.reason());
  }

  @Test
  public void tsmmAxisMismatchFallsBackToCp() {
    OpCaps caps = rule.caps(sig(Map.of("tsmm.type", "LEFT")), Arrays.asList(FType.COL, FType.COL), UNKNOWN);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertEquals(ReasonCode.UNSUPPORTED_ALIGNMENT, caps.reason());
  }

  @Test
  public void tsmmProfileIsBroadcast() {
    List<FType> outs = rule.profile(
        sig(Map.of("tsmm.type", "LEFT")),
        List.of(List.of(FType.ROW), List.of()),
        UNKNOWN).outputs();
    assertEquals(List.of(FType.BROADCAST), outs);
  }

  @Test
  public void fullLeftLocalRightIsFedLout() {
    OpCaps caps = rule.caps(sig(Map.of()), Arrays.asList(FType.FULL, null), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void localLeftFullRightIsFedLout() {
    OpCaps caps = rule.caps(sig(Map.of()), Arrays.asList(null, FType.FULL), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void colTAlignedColFullInputsAreFedLoutOnly() {
    OpCaps caps = rule.caps(sig(Map.of("align", "COL_T")), Arrays.asList(FType.COL, FType.FULL), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME, caps.reason());
  }

  private static OpSig sig(Map<String,String> attrs) {
    return OpSig.of(Opcodes.MMULT.toString(), OpCategory.BINARY_MM, attrs,
        InputKind.MATRIX, InputKind.MATRIX);
  }
}
