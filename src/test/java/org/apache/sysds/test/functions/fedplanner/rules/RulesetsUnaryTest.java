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
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Exec;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Placement;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class RulesetsUnaryTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);
  private static final String UCUM_DETAIL = "ucumk+* → n×1 result; fed ranges updated";

  private final Rulesets.UnaryElemwiseRule elemRule = new Rulesets.UnaryElemwiseRule();
  private final Rulesets.UnaryCumulativeRule ucumRule = new Rulesets.UnaryCumulativeRule();

  @Test
  public void unaryElemwiseScenarioTable() {
    List<Scenario> scenarios = List.of(
        Scenario.of("exp-full", elemRule, "exp", Map.of(), List.of(FType.FULL),
            Exec.FED, Placement.FOUT, true, FType.FULL, ReasonCode.OK, null, true),
        Scenario.of("plogp-row", elemRule, "plogp", Map.of(), List.of(FType.ROW),
            Exec.FED, Placement.FOUT, true, FType.ROW, ReasonCode.OK, null, true),
        Scenario.of("isna-guard-fail", elemRule, "isna", Map.of("rc.guardOverride", "false"), List.of(FType.COL),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.REPR_CHANGE_GUARD_FAIL, "override=false", false),
        Scenario.of("broadcast", elemRule, "log", Map.of(), List.of(FType.BROADCAST),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.BROADCAST_CONSTRAINT, null, false),
        Scenario.of("local", elemRule, "sqrt", Map.of(), List.of(FType.LOCAL),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.NO_FED_INPUT, null, false));
    runScenarios(scenarios);
  }

  @Test
  public void unaryElemwiseProfilePreservesFedTypes() {
    OpSig sig = new OpSig("exp", elemRule.category(), Map.of(), InputKind.MATRIX);
    List<List<FType>> candidates = List.of(
        List.of(FType.ROW, FType.PART, FType.BROADCAST, FType.FULL, FType.COL));
    List<FType> outs = elemRule.profile(sig, candidates, UNKNOWN_SHAPE).outputs();
    assertEquals(List.of(FType.ROW, FType.PART, FType.FULL, FType.COL), outs);
  }

  @Test
  public void ucumScenarioTable() {
    List<Scenario> scenarios = List.of(
        Scenario.of("ucumkpp-row", ucumRule, "ucumk+*", Map.of(), List.of(FType.ROW),
            Exec.FED, Placement.FOUT, true, FType.ROW, ReasonCode.OK, UCUM_DETAIL, true),
        Scenario.of("ucumkpp-col", ucumRule, "ucumk+*", Map.of(), List.of(FType.COL),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME, null, false),
        Scenario.of("ucummin-full", ucumRule, "ucummin", Map.of(), List.of(FType.FULL),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, null, false),
        Scenario.of("ucumk+-broadcast", ucumRule, "ucumk+", Map.of(), List.of(FType.BROADCAST),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.BROADCAST_CONSTRAINT, null, false),
        Scenario.of("ucummax-local", ucumRule, "ucummax", Map.of(), List.of(FType.LOCAL),
            Exec.CP, Placement.LOUT, false, null, ReasonCode.NO_FED_INPUT, null, false));
    runScenarios(scenarios);
  }

  @Test
  public void ucumProfileOnlyRow() {
    OpSig sig = new OpSig("ucummin", ucumRule.category(), Map.of(), InputKind.MATRIX);
    List<List<FType>> candidates = List.of(List.of(FType.ROW, FType.COL, FType.PART));
    List<FType> outs = ucumRule.profile(sig, candidates, UNKNOWN_SHAPE).outputs();
    assertEquals(List.of(FType.ROW), outs);
  }

  private void runScenarios(List<Scenario> scenarios) {
    for (Scenario scenario : scenarios) {
      OpSig sig = new OpSig(
          scenario.opcode,
          scenario.rule.category(),
          scenario.attrs,
          InputKind.MATRIX);
      OpCaps caps = scenario.rule.caps(sig, scenario.inFTypes, UNKNOWN_SHAPE);

      assertEquals(msg(scenario, "exec"), scenario.expectedExec, caps.exec());
      assertEquals(msg(scenario, "placement"), scenario.expectedPlacement, caps.placement());
      assertEquals(msg(scenario, "fout"), scenario.expectFout, caps.foutEnabled());
      assertEquals(msg(scenario, "foutFType"),
          scenario.expectedFoutFType,
          caps.foutFType().orElse(null));
      assertEquals(msg(scenario, "reason"), scenario.reason, caps.reason());
      assertEquals(msg(scenario, "detail"),
          scenario.detail,
          caps.detail().orElse(null));

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
    final Rule rule;
    final String opcode;
    final Map<String,String> attrs;
    final List<FType> inFTypes;
    final Exec expectedExec;
    final Placement expectedPlacement;
    final boolean expectFout;
    final FType expectedFoutFType;
    final ReasonCode reason;
    final String detail;
    final boolean expectGuardNote;

    private Scenario(String name, Rule rule, String opcode, Map<String,String> attrs, List<FType> inFTypes,
        Exec expectedExec, Placement expectedPlacement, boolean expectFout, FType expectedFoutFType,
        ReasonCode reason, String detail, boolean expectGuardNote) {
      this.name = name;
      this.rule = rule;
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

    static Scenario of(String name, Rule rule, String opcode, Map<String,String> attrs, List<FType> inFTypes,
        Exec expectedExec, Placement expectedPlacement, boolean expectFout, FType expectedFoutFType,
        ReasonCode reason, String detail, boolean expectGuardNote) {
      return new Scenario(name, rule, opcode, attrs, inFTypes,
          expectedExec, expectedPlacement, expectFout, expectedFoutFType, reason, detail, expectGuardNote);
    }
  }
}
