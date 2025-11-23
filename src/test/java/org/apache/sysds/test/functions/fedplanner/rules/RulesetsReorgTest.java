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
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RulesetsReorgTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);
  private final Rulesets.ReorgUnaryRule rule = new Rulesets.ReorgUnaryRule();

  @Test
  public void reorgScenarioTable() {
    List<Scenario> scenarios = List.of(
        Scenario.of("transpose-row", ReOrgOp.TRANS.toString(), Map.of(), List.of(FType.ROW),
            ExecType.FED, FederatedOutput.FOUT, true, FType.COL, ReasonCode.OK, null, true),
        Scenario.of("transpose-col", ReOrgOp.TRANS.toString(), Map.of(), List.of(FType.COL),
            ExecType.FED, FederatedOutput.FOUT, true, FType.ROW, ReasonCode.OK, null, true),
        Scenario.of("rev-part", ReOrgOp.REV.toString(), Map.of(), List.of(FType.PART),
            ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, null, false),
        Scenario.of("roll-full", ReOrgOp.ROLL.toString(), Map.of(), List.of(FType.FULL),
            ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, null, false),
        Scenario.of("diag-broadcast", ReOrgOp.DIAG.toString(), Map.of(), List.of(FType.BROADCAST),
            ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.BROADCAST_CONSTRAINT, null, false),
        Scenario.of("rev-guard-fail", ReOrgOp.REV.toString(), Map.of("rc.guardOverride", "false"), List.of(FType.ROW),
            ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.REPR_CHANGE_GUARD_FAIL, "override=false", false));
    runScenarios(scenarios);
  }

  @Test
  public void profileTransposeSwapsAxes() {
    OpSig sig = OpSig.of(ReOrgOp.TRANS.toString(), rule.category(), Map.of(), InputKind.MATRIX);
    List<List<FType>> candidates = List.of(List.of(FType.ROW, FType.COL));
    List<FType> outs = rule.profile(sig, candidates, UNKNOWN_SHAPE).outputs();
    assertEquals(List.of(FType.ROW, FType.COL), outs);
  }

  @Test
  public void profileDiagPreservesAxis() {
    OpSig sig = OpSig.of(ReOrgOp.DIAG.toString(), rule.category(), Map.of(), InputKind.MATRIX);
    List<List<FType>> candidates = List.of(List.of(FType.COL, FType.ROW));
    List<FType> outs = rule.profile(sig, candidates, UNKNOWN_SHAPE).outputs();
    assertEquals(List.of(FType.COL, FType.ROW), outs);
  }

  private void runScenarios(List<Scenario> scenarios) {
    for (Scenario scenario : scenarios) {
      OpSig sig = OpSig.of(
          scenario.opcode,
          rule.category(),
          scenario.attrs,
          InputKind.MATRIX);
      OpCaps caps = rule.caps(sig, scenario.inFTypes, UNKNOWN_SHAPE);

      assertEquals(msg(scenario, "exec"), scenario.expectedExec, caps.exec());
      assertEquals(msg(scenario, "placement"), scenario.expectedPlacement, caps.placement());
      assertEquals(msg(scenario, "fout"), scenario.expectFout, caps.foutEnabled());
      assertEquals(msg(scenario, "foutFType"), scenario.expectedFoutFType, caps.foutFType().orElse(null));
      assertEquals(msg(scenario, "reason"), scenario.reason, caps.reason());
      assertEquals(msg(scenario, "detail"), scenario.detail, caps.detail().orElse(null));

      if (scenario.expectGuardNote) {
        assertTrue(msg(scenario, "guard-note"),
            caps.notes().stream()
                .map(DecisionNote::code)
                .anyMatch(code -> code == ReasonCode.REPR_CHANGE_GUARD_UNKNOWN));
      }
      else {
        assertTrue(msg(scenario, "guard-note-absent"),
            caps.notes().stream()
                .noneMatch(n -> n.code() == ReasonCode.REPR_CHANGE_GUARD_UNKNOWN));
      }
    }
  }

  private static String msg(Scenario scenario, String field) {
    return scenario.name + " → " + field;
  }

  private static final class Scenario {
    final String name;
    final String opcode;
    final Map<String,String> attrs;
    final List<FType> inFTypes;
    final ExecType expectedExec;
    final FederatedOutput expectedPlacement;
    final boolean expectFout;
    final FType expectedFoutFType;
    final ReasonCode reason;
    final String detail;
    final boolean expectGuardNote;

    private Scenario(String name, String opcode, Map<String,String> attrs, List<FType> inFTypes,
        ExecType expectedExec, FederatedOutput expectedPlacement, boolean expectFout, FType expectedFoutFType,
        ReasonCode reason, String detail, boolean expectGuardNote) {
      this.name = name;
      this.opcode = opcode;
      this.attrs = attrs;
      this.inFTypes = inFTypes;
      this.expectedExec = expectedExec;
      this.expectedPlacement = expectedPlacement;
      this.expectFout = expectFout;
      this.expectedFoutFType = expectedFoutFType;
      this.reason = reason;
      this.detail = detail;
      this.expectGuardNote = expectGuardNote;
    }

    static Scenario of(String name, String opcode, Map<String,String> attrs, List<FType> inFTypes,
        ExecType expectedExec, FederatedOutput expectedPlacement, boolean expectFout, FType expectedFoutFType,
        ReasonCode reason, String detail, boolean expectGuardNote) {
      return new Scenario(name, opcode, attrs, inFTypes,
          expectedExec, expectedPlacement, expectFout, expectedFoutFType, reason, detail, expectGuardNote);
    }
  }
}
