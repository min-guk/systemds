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

import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Exec;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Placement;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class CentralMomentRuleTest {

  private static final OpSig CM_SIG = new OpSig("cm", OpCategory.AGG_UNARY, Map.of());
  private static final OpSig CM_HINTED_SIG = new OpSig(
      "aggregateunary", OpCategory.AGG_UNARY, Map.of("aggOp", "CM"));

  private final Rulesets.CentralMomentRule rule = new Rulesets.CentralMomentRule();

  @Test
  public void federatedInputWithoutWeights() {
    OpCaps caps = rule.caps(CM_SIG, List.of(FType.ROW), unknownShape());
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void federatedInputWithLocalWeights() {
    OpCaps caps = rule.caps(CM_SIG, List.of(FType.COL, FType.LOCAL), unknownShape());
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void federatedWeightsRejected() {
    OpCaps caps = rule.caps(CM_SIG, List.of(FType.PART, FType.ROW), unknownShape());
    assertEquals(Exec.CP, caps.exec());
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, caps.reason());
  }

  @Test
  public void nonFederatedInputFallsBack() {
    OpCaps caps = rule.caps(CM_SIG, List.of(FType.LOCAL), unknownShape());
    assertEquals(Exec.CP, caps.exec());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
  }

  @Test
  public void hintedOpcodeSupported() {
    OpCaps caps = rule.caps(CM_HINTED_SIG, List.of(FType.ROW, FType.LOCAL), unknownShape());
    assertEquals(Exec.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  private static ShapeHint unknownShape() {
    return new ShapeHint(-1, -1, 0);
  }
}
