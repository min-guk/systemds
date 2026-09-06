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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RulesetsGuardTest {

  private static final ShapeHint KNOWN_SHAPE = new ShapeHint(10, 10, 1000);

  @Test
  public void ewiseSingleNodeNoCachingKeepsFout() {
    Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
    OpSig sig = sig(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE, Map.of(
        "rc.execMode", "SINGLE_NODE",
        "rc.cachingActive", "false"));

    OpCaps caps = rule.caps(sig, List.of(FType.ROW, FType.ROW), KNOWN_SHAPE);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(ReasonCode.OK, caps.reason());
    Optional<DecisionNote> guardNote = guardPassNote(caps);
    assertTrue("Guard PASS note missing", guardNote.isPresent());
    assertTrue(guardNote.get().message().contains("caching"));
  }

  @Test
  public void ewiseFullRequiresProvenSingleRange() {
    Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
    OpSig sig = sig(OpOp2.MULT.toString(), OpCategory.BINARY_EWISE, Map.of());

    OpCaps unknown = rule.caps(sig, java.util.Arrays.asList(FType.FULL, null),
        new ShapeHint(10, 10, 1000));
    assertEquals(ExecType.CP, unknown.exec());
    assertEquals(ReasonCode.FULL_MULTI_PARTITIONS_UNSUPPORTED, unknown.reason());

    OpCaps single = rule.caps(sig, java.util.Arrays.asList(FType.FULL, null),
        new ShapeHint(10, 10, 1000, true));
    assertEquals(ExecType.FED, single.exec());
    assertEquals(FederatedOutput.FOUT, single.placement());
    assertEquals(FType.FULL, single.foutFType().orElse(null));
  }


  @Test
  public void ewiseFullCompanionsRequireProvenSingleRange() {
    Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
    OpSig sig = sig(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE, Map.of());
    ShapeHint unknown = new ShapeHint(10, 10, 1000);
    ShapeHint single = new ShapeHint(10, 10, 1000, true);

    for(List<FType> selected : List.of(
        List.of(FType.FULL, FType.FULL),
        List.of(FType.FULL, FType.BROADCAST))) {
      OpCaps rejected = rule.caps(sig, selected, unknown);
      assertEquals("Unknown FULL cardinality must cap the candidate at CP: " + selected,
          ExecType.CP, rejected.exec());
      assertEquals(ReasonCode.FULL_MULTI_PARTITIONS_UNSUPPORTED, rejected.reason());

      OpCaps accepted = rule.caps(sig, selected, single);
      assertEquals("Proven one-range FULL remains a native candidate: " + selected,
          ExecType.FED, accepted.exec());
      assertEquals(FederatedOutput.FOUT, accepted.placement());
      assertEquals(FType.FULL, accepted.foutFType().orElse(null));
    }
    OpSig commonPoolBlocked = sig(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE, Map.of(
        "rc.execMode", "SINGLE_NODE",
        "rc.cachingActive", "true",
        "rc.memReqEstBytes", Long.toString(134_217_728L),
        "rc.memIn1EstBytes", Long.toString(33_554_432L),
        "rc.memIn2EstBytes", Long.toString(33_554_432L)));
    OpCaps guarded = rule.caps(commonPoolBlocked,
        List.of(FType.FULL, FType.BROADCAST), single);
    assertEquals("A one-range proof must not bypass the active-cache representation guard",
        ExecType.CP, guarded.exec());
    assertFalse(guarded.foutEnabled());
    assertEquals(ReasonCode.REPR_CHANGE_GUARD_FAIL, guarded.reason());
  }

  @Test
  public void mmMemRequirementTriggersGuardFail() {
    Rulesets.BinaryMMRule rule = new Rulesets.BinaryMMRule();
    Map<String,String> attrs = Map.of(
        "rc.memReqEstBytes", Long.toString(134_217_728L),
        "rc.memIn1EstBytes", Long.toString(33_554_432L),
        "rc.memIn2EstBytes", Long.toString(33_554_432L),
        "rc.cachingActive", "true");
    OpSig sig = sig(Opcodes.MMULT.toString(), OpCategory.BINARY_MM, attrs);

    OpCaps caps = rule.caps(sig, List.of(FType.ROW, FType.ROW), KNOWN_SHAPE);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.REPR_CHANGE_GUARD_FAIL, caps.reason());
    assertTrue(caps.detail().isPresent());
    assertTrue(caps.detail().get().contains("memReq"));
  }

  @Test
  public void mmFullBroadcastRetainsSingleWorkerFout() {
    Rulesets.BinaryMMRule rule = new Rulesets.BinaryMMRule();
    OpSig sig = sig(Opcodes.MMULT.toString(), OpCategory.BINARY_MM, Map.of());
	ShapeHint singleWorkerShape = new ShapeHint(10, 10, 1000, true);

    OpCaps capsLeftFull = rule.caps(sig, List.of(FType.FULL, FType.BROADCAST), singleWorkerShape);
    assertEquals(ExecType.FED, capsLeftFull.exec());
    assertEquals(FederatedOutput.FOUT, capsLeftFull.placement());
    assertTrue(capsLeftFull.foutEnabled());
    assertEquals(FType.FULL, capsLeftFull.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, capsLeftFull.reason());

    OpCaps capsRightFull = rule.caps(sig, List.of(FType.BROADCAST, FType.FULL), singleWorkerShape);
    assertEquals(ExecType.FED, capsRightFull.exec());
    assertEquals(FederatedOutput.FOUT, capsRightFull.placement());
    assertTrue(capsRightFull.foutEnabled());
    assertEquals("BROADCAST x FULL retains the complete left-hand worker layout",
      FType.BROADCAST, capsRightFull.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, capsRightFull.reason());

    OpCaps capsLocalLeft = rule.caps(sig, java.util.Arrays.asList(null, FType.FULL), singleWorkerShape);
    assertEquals(ExecType.FED, capsLocalLeft.exec());
    assertEquals(FederatedOutput.FOUT, capsLocalLeft.placement());
    assertEquals(FType.FULL, capsLocalLeft.foutFType().orElse(null));
  }

  @Test
  public void appendWithoutHintsDefaultsToGuardUnknown() {
    Rulesets.AppendRule rule = new Rulesets.AppendRule();
    OpSig sig = sig(Opcodes.APPEND.toString(), OpCategory.APPEND, Map.of("cbind", "false"));

    OpCaps caps = rule.caps(sig, List.of(FType.ROW, FType.ROW), KNOWN_SHAPE);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(ReasonCode.PREFER_BIND_ROW, caps.reason());
    assertTrue("guard unknown note missing",
        caps.notes().stream()
            .anyMatch(n -> n.code() == ReasonCode.REPR_CHANGE_GUARD_UNKNOWN));
  }

  @Test
  public void guardOverrideAllowsFout() {
    Rulesets.AppendRule rule = new Rulesets.AppendRule();
    OpSig sig = sig(Opcodes.APPEND.toString(), OpCategory.APPEND, Map.of(
        "cbind", "true",
        "rc.guardOverride", "true"));

    OpCaps caps = rule.caps(sig, List.of(FType.ROW, FType.ROW), KNOWN_SHAPE);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    Optional<DecisionNote> guardNote = guardPassNote(caps);
    assertTrue(guardNote.isPresent());
    assertTrue(guardNote.get().message().contains("override=true"));
  }

  private static OpSig sig(String opcode, OpCategory category, Map<String,String> attrs) {
    return OpSig.of(opcode, category, attrs);
  }

  private static Optional<DecisionNote> guardPassNote(OpCaps caps) {
    return caps.notes().stream()
        .filter(n -> n.code() == ReasonCode.REPR_CHANGE_GUARD_PASS)
        .findFirst();
  }
}
