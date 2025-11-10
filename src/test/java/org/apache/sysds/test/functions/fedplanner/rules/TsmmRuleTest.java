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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class TsmmRuleTest {

  private static final ShapeHint UNKNOWN = new ShapeHint(-1, -1, 0);
  private static final String AXIS_DETAIL = extractConstant("TSMM_AXIS_ONLY_DETAIL");
  private static final String AGG_NOTE = extractConstant("TSMM_AGG_NOTE");
  private static final String FORCED_NOTE = extractConstant("TSMM_FORCED_BC_NOTE");

  private final Rulesets.TsmmRule rule = new Rulesets.TsmmRule();

  @Test
  public void rowLeftDefaultsToFedLocal() {
    OpCaps caps = rule.caps(sig(Map.of()), List.of(FType.ROW), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.OK, caps.reason());
    assertTrue(hasNote(caps, ReasonCode.INFO, AGG_NOTE));
  }

  @Test
  public void rightColForceFoutBroadcast() {
    OpSig sig = sig(Map.of("tsmm.type", "RIGHT", "force_fout", "true"));
    OpCaps caps = rule.caps(sig, List.of(FType.COL), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.BROADCAST, caps.foutFType().orElse(null));
    assertTrue(hasNote(caps, ReasonCode.INFO, FORCED_NOTE));
    assertTrue(hasNoteCode(caps, ReasonCode.REPR_CHANGE_GUARD_UNKNOWN));
  }

  @Test
  public void fedOutAttrForcesBroadcastGuardPassNote() {
    OpSig sig = sig(Map.of("tsmm.type", "RIGHT", "tsmm.fedOut", "FORCED", "rc.guardOverride", "true"));
    OpCaps caps = rule.caps(sig, List.of(FType.COL), UNKNOWN);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(hasNote(caps, ReasonCode.INFO, FORCED_NOTE));
    assertTrue(hasNoteCode(caps, ReasonCode.REPR_CHANGE_GUARD_PASS));
  }

  @Test
  public void guardFailureFallsBackToCp() {
    OpSig sig = sig(Map.of("tsmm.type", "LEFT", "force_fout", "true", "rc.guardOverride", "false"));
    OpCaps caps = rule.caps(sig, List.of(FType.ROW), UNKNOWN);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertEquals(ReasonCode.REPR_CHANGE_GUARD_FAIL, caps.reason());
    assertTrue(caps.detail().orElse("").contains("override=false"));
  }

  @Test
  public void axisMismatchRejected() {
    OpSig sig = sig(Map.of("tsmm.type", "RIGHT"));
    OpCaps caps = rule.caps(sig, List.of(FType.ROW), UNKNOWN);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.UNSUPPORTED_ALIGNMENT, caps.reason());
    assertTrue(caps.detail().orElse("").contains(AXIS_DETAIL));
  }

  @Test
  public void fullInputForbidden() {
    OpCaps caps = rule.caps(sig(Map.of()), List.of(FType.FULL), UNKNOWN);
    assertEquals(ReasonCode.PARTITION_FORBIDDEN, caps.reason());
    assertTrue(caps.detail().orElse("").contains(AXIS_DETAIL));
  }

  @Test
  public void broadcastInputRejected() {
    OpCaps caps = rule.caps(sig(Map.of()), List.of(FType.BROADCAST), UNKNOWN);
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, caps.reason());
    assertEquals(ExecType.CP, caps.exec());
  }

  @Test
  public void nonFederatedInputRejected() {
    OpCaps caps = rule.caps(sig(Map.of()), List.of(FType.NF), UNKNOWN);
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
    assertEquals(ExecType.CP, caps.exec());
  }

  @Test
  public void arityMismatch() {
    OpCaps caps = rule.caps(sig(Map.of()), List.of(), UNKNOWN);
    assertEquals(ReasonCode.ARITY_MISMATCH, caps.reason());
    assertEquals(ExecType.CP, caps.exec());
  }

  @Test
  public void invalidTypeFallsBackToOpcodeUnsupported() {
    OpSig sig = sig(Map.of("tsmm.type", "DIAGONAL"));
    OpCaps caps = rule.caps(sig, List.of(FType.ROW), UNKNOWN);
    assertEquals(ReasonCode.OPCODE_UNSUPPORTED, caps.reason());
    assertEquals(ExecType.CP, caps.exec());
  }

  @Test
  public void defaultRegistryContainsTsmmRule() {
    RulesCore.RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
    assertTrue("tsmm opcode should resolve via registry",
        registry.byOpcode(Opcodes.TSMM.toString()).isPresent());
  }

  private static OpSig sig(Map<String,String> attrs) {
    return OpSig.of(Opcodes.TSMM.toString(), OpCategory.TSMM, attrs);
  }

  private static boolean hasNote(OpCaps caps, ReasonCode code, String message) {
    return caps.notes().stream()
        .anyMatch(note -> note.code() == code && note.message().contains(message));
  }

  private static boolean hasNoteCode(OpCaps caps, ReasonCode code) {
    return caps.notes().stream().anyMatch(note -> note.code() == code);
  }

  private static String extractConstant(String fieldName) {
    try {
      Field field = Rulesets.TsmmRule.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      Object value = field.get(null);
      assertNotNull("Expected constant " + fieldName, value);
      return value.toString();
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError("Unable to read constant " + fieldName, ex);
    }
  }
}
