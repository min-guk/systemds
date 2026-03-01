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
  public void mmFullBroadcastAllowedAsFedLocal() {
    Rulesets.BinaryMMRule rule = new Rulesets.BinaryMMRule();
    OpSig sig = sig(Opcodes.MMULT.toString(), OpCategory.BINARY_MM, Map.of());

    OpCaps capsLeftFull = rule.caps(sig, List.of(FType.FULL, FType.BROADCAST), KNOWN_SHAPE);
    assertEquals(ExecType.FED, capsLeftFull.exec());
    assertEquals(FederatedOutput.LOUT, capsLeftFull.placement());
    assertFalse(capsLeftFull.foutEnabled());
    assertEquals(ReasonCode.OK, capsLeftFull.reason());

    OpCaps capsRightFull = rule.caps(sig, List.of(FType.BROADCAST, FType.FULL), KNOWN_SHAPE);
    assertEquals(ExecType.FED, capsRightFull.exec());
    assertEquals(FederatedOutput.LOUT, capsRightFull.placement());
    assertFalse(capsRightFull.foutEnabled());
    assertEquals(ReasonCode.OK, capsRightFull.reason());
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
