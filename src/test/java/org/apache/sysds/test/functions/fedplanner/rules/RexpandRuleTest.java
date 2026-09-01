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
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RexpandRuleTest {
  private static final OpSig REXPAND_COLS =
      OpSig.of(Opcodes.REXPAND.toString(), OpCategory.OTHER, Map.of("rexpand.dir", "cols"));
  private static final OpSig REXPAND_ROWS =
      OpSig.of(Opcodes.REXPAND.toString(), OpCategory.OTHER, Map.of("rexpand.dir", "rows"));

  private final Rulesets.RexpandRule rule = new Rulesets.RexpandRule();

  @Test
  public void rowInputKeepsRowFoutForColsDirection() {
    OpCaps caps = rule.caps(REXPAND_COLS, List.of(FType.ROW), null);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutFType().isPresent());
    assertEquals(FType.ROW, caps.foutFType().get());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void rowInputSwitchesToColFoutForRowsDirection() {
    OpCaps caps = rule.caps(REXPAND_ROWS, List.of(FType.ROW), null);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutFType().isPresent());
    assertEquals(FType.COL, caps.foutFType().get());
    assertEquals(ReasonCode.OK, caps.reason());
  }

  @Test
  public void broadcastInputFallsBackToCp() {
    OpCaps caps = rule.caps(REXPAND_COLS, List.of(FType.BROADCAST), null);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, caps.reason());
  }
}
