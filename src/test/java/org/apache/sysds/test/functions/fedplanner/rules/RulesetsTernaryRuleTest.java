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
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class RulesetsTernaryRuleTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);

  private final Rulesets.FrameMapRule frameRule = new Rulesets.FrameMapRule();
  private final Rulesets.TernaryElemwiseRule elemRule = new Rulesets.TernaryElemwiseRule();

  @Test
  public void frameMapRuleHandlesMarginAndFallbacks() {
    List<TestVector> cases = List.of(
        new TestVector(
            "frame-row-margin-1",
            mapSig(true, Map.of("map.margin", "1")),
            Arrays.asList(FType.ROW, null, null),
            UNKNOWN_SHAPE,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        new TestVector(
            "frame-col-margin-2",
            mapSig(false, Map.of("map.margin", "2")),
            Arrays.asList(null, FType.COL, null),
            UNKNOWN_SHAPE,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.COL,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        new TestVector(
            "margin-mismatch",
            mapSig(true, Map.of("map.margin", "2")),
            Arrays.asList(FType.ROW, null, null),
            UNKNOWN_SHAPE,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
            "margin mismatch or non-federated frame",
            null));

    assertCases(frameRule, cases);
  }

  @Test
  public void ternaryElemwiseRuleMatchesRuntimeSemantics() {
    List<TestVector> cases = List.of(
        new TestVector(
            "plus-mult-row-dominant",
            ewiseSig(OpOp3.PLUS_MULT.toString(), Map.of(), InputKind.MATRIX, InputKind.MATRIX, InputKind.SCALAR),
            Arrays.asList(FType.ROW, null, null),
            UNKNOWN_SHAPE,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        new TestVector(
            "minus-mult-second-fed",
            ewiseSig(OpOp3.MINUS_MULT.toString(), Map.of(), InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX),
            Arrays.asList(null, FType.COL, FType.BROADCAST),
            UNKNOWN_SHAPE,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.COL,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        new TestVector(
            "ifelse-part-leading",
            ewiseSig(OpOp3.IFELSE.toString(), Map.of(), InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX),
            Arrays.asList(FType.PART, null, null),
            UNKNOWN_SHAPE,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.PART,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        new TestVector(
            "ifelse-no-fed-input",
            ewiseSig(OpOp3.IFELSE.toString(), Map.of(), InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX),
            Arrays.asList(null, FType.BROADCAST, null),
            UNKNOWN_SHAPE,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.NO_FED_INPUT,
            null,
            null),
        new TestVector(
            "ifelse-outer-like",
            ewiseSig(OpOp3.IFELSE.toString(), Map.of(), InputKind.MATRIX, InputKind.MATRIX, InputKind.SCALAR),
            Arrays.asList(FType.ROW, FType.COL, null),
            UNKNOWN_SHAPE,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
            null,
            null));

    assertCases(elemRule, cases);
  }

  @Test
  public void ternaryElemwiseRuleFallsBackOnGuardFailure() {
    OpSig sig = ewiseSig(
        OpOp3.PLUS_MULT.toString(), Map.of(
            "rc.memReqEstBytes", "10000000",
            "rc.memIn1EstBytes", "1",
            "rc.memIn2EstBytes", "1"),
        InputKind.MATRIX, InputKind.MATRIX, InputKind.SCALAR);
    List<FType> fTypes = Arrays.asList(FType.ROW, null, null);

    OpCaps caps = elemRule.caps(sig, fTypes, UNKNOWN_SHAPE);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertEquals(ReasonCode.REPR_CHANGE_GUARD_FAIL, caps.reason());
    assertFalse("guard fallback should not allow FOUT", caps.foutEnabled());
    assertTrue("guard detail should propagate memReq info",
        caps.detail().isPresent() && caps.detail().get().contains("memReq="));
  }

  private static void assertCases(Rule rule, List<TestVector> cases) {
    for (TestVector tv : cases) {
      OpCaps caps = rule.caps(tv.sig, tv.inTypes, tv.hint);
      assertEquals(tv.name + ": exec", tv.exec, caps.exec());
      assertEquals(tv.name + ": placement", tv.placement, caps.placement());
      assertEquals(tv.name + ": reason", tv.reason, caps.reason());
      assertEquals(tv.name + ": fout", tv.fout, caps.foutEnabled());
      if (tv.fout) {
        assertEquals(tv.name + ": fout axis", tv.foutType, caps.foutFType().orElse(null));
      }
      else {
        assertFalse(tv.name + ": unexpected fout axis", caps.foutFType().isPresent());
      }

      if (tv.detail != null) {
        assertTrue(tv.name + ": expected detail", caps.detail().isPresent());
        assertEquals(tv.name + ": detail mismatch", tv.detail, caps.detail().get());
      }
      else {
        assertFalse(tv.name + ": unexpected detail", caps.detail().isPresent());
      }

      if (tv.note != null) {
        assertTrue(tv.name + ": guard note missing", hasNote(caps, tv.note));
      }
      else {
        assertTrue(tv.name + ": notes should be empty when not expected", caps.notes().isEmpty());
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

  private static OpSig mapSig(boolean frameIsFirst, Map<String,String> attrs) {
    InputKind first = frameIsFirst ? InputKind.FRAME : InputKind.SCALAR;
    InputKind second = frameIsFirst ? InputKind.SCALAR : InputKind.FRAME;
    return OpSig.of(OpOp3.MAP.toString(), OpCategory.OTHER, attrs, first, second, InputKind.SCALAR);
  }

  private static OpSig ewiseSig(String opcode, Map<String,String> attrs, InputKind... kinds) {
    return OpSig.of(opcode, OpCategory.OTHER, attrs, kinds);
  }

  private static final class TestVector {
    final String name;
    final OpSig sig;
    final List<FType> inTypes;
    final ShapeHint hint;
    final ExecType exec;
    final FederatedOutput placement;
    final boolean fout;
    final FType foutType;
    final ReasonCode reason;
    final String detail;
    final ReasonCode note;

    TestVector(String name, OpSig sig, List<FType> inTypes, ShapeHint hint,
        ExecType exec, FederatedOutput placement, boolean fout, FType foutType,
        ReasonCode reason, String detail, ReasonCode note) {
      this.name = name;
      this.sig = sig;
      this.inTypes = inTypes;
      this.hint = hint;
      this.exec = exec;
      this.placement = placement;
      this.fout = fout;
      this.foutType = foutType;
      this.reason = reason;
      this.detail = detail;
      this.note = note;
    }
  }
}
