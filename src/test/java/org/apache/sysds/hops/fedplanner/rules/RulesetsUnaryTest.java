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

package org.apache.sysds.hops.fedplanner.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Test;

public class RulesetsUnaryTest {

  @Test
  public void unaryRulesSmokeTable() {
    List<Scenario> scenarios = List.of(
        Scenario.of("exp-row-fed",
            new Rulesets.UnaryElemwiseRule(),
            "exp",
            OpCategory.OTHER,
            Map.of(),
            FType.ROW,
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        Scenario.of("log_nz-col-guard-pass",
            new Rulesets.UnaryElemwiseRule(),
            "log_nz",
            OpCategory.OTHER,
            Map.of(
                "rc.execMode", "SINGLE_NODE",
                "rc.cachingActive", "false"),
            FType.COL,
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.COL,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_PASS),
        Scenario.of("sprop-full",
            new Rulesets.UnaryElemwiseRule(),
            "sprop",
            OpCategory.OTHER,
            Map.of(),
            FType.FULL,
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.FULL,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        Scenario.of("sqrt-broadcast",
            new Rulesets.UnaryElemwiseRule(),
            "sqrt",
            OpCategory.OTHER,
            Map.of(),
            FType.BROADCAST,
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.BROADCAST_CONSTRAINT,
            null,
            null),
        Scenario.of("ucumk+-part",
            new Rulesets.UnaryCumulativeRule(),
            "ucumk+",
            OpCategory.OTHER,
            Map.of(),
            FType.PART,
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
            null,
            null),
        Scenario.of("ucummin-col",
            new Rulesets.UnaryCumulativeRule(),
            "ucummin",
            OpCategory.OTHER,
            Map.of(),
            FType.COL,
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME,
            null,
            null),
        Scenario.of("ucumk+*-row",
            new Rulesets.UnaryCumulativeRule(),
            "ucumk+*",
            OpCategory.OTHER,
            Map.of(),
            FType.ROW,
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            "ucumk+* → n×1 result; fed ranges updated",
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        Scenario.of("ucumk+*-col-forbidden",
            new Rulesets.UnaryCumulativeRule(),
            "ucumk+*",
            OpCategory.OTHER,
            Map.of(),
            FType.COL,
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.FOUT_NOT_SUPPORTED_BY_RUNTIME,
            null,
            null),
        Scenario.of("exp-guard-fail",
            new Rulesets.UnaryElemwiseRule(),
            "exp",
            OpCategory.OTHER,
            Map.of("rc.guardOverride", "false"),
            FType.ROW,
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.REPR_CHANGE_GUARD_FAIL,
            "override=false",
            null),
        Scenario.of("transpose-row",
            new Rulesets.ReorgUnaryRule(),
            ReOrgOp.TRANS.toString(),
            OpCategory.REORG,
            Map.of(),
            FType.ROW,
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.COL,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        Scenario.of("diag-part",
            new Rulesets.ReorgUnaryRule(),
            ReOrgOp.DIAG.toString(),
            OpCategory.REORG,
            Map.of(),
            FType.PART,
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.PARTITION_FORBIDDEN,
            "ReorgFEDInstruction supports only ROW or COL partitioned input",
            null));

    for (Scenario sc : scenarios) {
      OpCaps caps = sc.rule.caps(sc.sig(), List.of(sc.inFType), sc.shape);
      assertEquals(sc.name + ": exec", sc.expectedExec, caps.exec());
      assertEquals(sc.name + ": placement", sc.expectedPlacement, caps.placement());
      assertEquals(sc.name + ": fout flag", sc.expectedFout, caps.foutEnabled());
      if (sc.expectedFout) {
        Optional<FType> foutType = caps.foutFType();
        assertTrue(sc.name + ": fout type missing", foutType.isPresent());
        assertEquals(sc.name + ": fout type", sc.expectedFoutType, foutType.get());
      } else {
        assertTrue(sc.name + ": fout type should be empty", caps.foutFType().isEmpty());
      }
      assertEquals(sc.name + ": reason", sc.expectedReason, caps.reason());
      if (sc.expectedDetail != null) {
        Optional<String> detail = caps.detail();
        assertTrue(sc.name + ": detail missing", detail.isPresent());
        assertTrue(sc.name + ": detail mismatch", detail.get().contains(sc.expectedDetail));
      } else {
        assertTrue(sc.name + ": unexpected detail", caps.detail().isEmpty());
      }
      if (sc.expectedNoteCode != null) {
        assertTrue(sc.name + ": missing note " + sc.expectedNoteCode, hasNote(caps, sc.expectedNoteCode));
      }
    }
  }

  private static boolean hasNote(OpCaps caps, ReasonCode code) {
    for (DecisionNote note : caps.notes()) {
      if (note.code() == code)
        return true;
    }
    return false;
  }

  private static final class Scenario {
    final String name;
    final Rule rule;
    final String opcode;
    final OpCategory category;
    final Map<String,String> attrs;
    final FType inFType;
    final ShapeHint shape;
    final ExecType expectedExec;
    final FederatedOutput expectedPlacement;
    final boolean expectedFout;
    final FType expectedFoutType;
    final ReasonCode expectedReason;
    final String expectedDetail;
    final ReasonCode expectedNoteCode;

    private Scenario(String name, Rule rule, String opcode, OpCategory category, Map<String,String> attrs,
        FType inFType, ShapeHint shape, ExecType expectedExec, FederatedOutput expectedPlacement, boolean expectedFout,
        FType expectedFoutType, ReasonCode expectedReason, String expectedDetail, ReasonCode expectedNoteCode) {
      this.name = name;
      this.rule = rule;
      this.opcode = opcode;
      this.category = category;
      this.attrs = attrs;
      this.inFType = inFType;
      this.shape = shape;
      this.expectedExec = expectedExec;
      this.expectedPlacement = expectedPlacement;
      this.expectedFout = expectedFout;
      this.expectedFoutType = expectedFoutType;
      this.expectedReason = expectedReason;
      this.expectedDetail = expectedDetail;
      this.expectedNoteCode = expectedNoteCode;
    }

    static Scenario of(String name, Rule rule, String opcode, OpCategory cat, Map<String,String> attrs,
        FType in, ShapeHint shape, ExecType exec, FederatedOutput placement, boolean fout, FType foutType,
        ReasonCode reason, String detail, ReasonCode noteCode) {
      return new Scenario(name, rule, opcode, cat, attrs, in, shape, exec, placement, fout, foutType, reason,
          detail, noteCode);
    }

    OpSig sig() {
      return OpSig.of(opcode, category, attrs, InputKind.MATRIX);
    }
  }
}
