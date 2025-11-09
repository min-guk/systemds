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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Exec;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Placement;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Test;

public class RulesetsWummTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);
  private static final String WUMM_X_AXIS_ONLY_DETAIL =
      "WUMM supports only ROW or COL partitioned X (per QuaternaryWUMMFEDInstruction)";

  private static OpSig defaultSig() {
    return OpSig.of("wumm", OpCategory.QUATERNARY, Map.of("q.type", "WUMM"),
        InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);
  }

  // 1) (ROW, ROW, LOCAL): FED/FOUT(ROW) + OK + U aligned, V broadcast
  @Test
  public void wumm_row_row_local() {
    OpCaps caps = decide(defaultSig(), List.of(FType.ROW, FType.ROW, FType.LOCAL));
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.ROW, caps.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, caps.reason());
    assertNotePresent(caps, ReasonCode.ALIGNED_HINT, "U aligned");
    assertNotePresent(caps, ReasonCode.INFO, "broadcast");
  }

  // 2) (ROW, LOCAL, LOCAL): FED/FOUT(ROW) + OK + U broadcast-sliced, V broadcast
  @Test
  public void wumm_row_local_local() {
    OpCaps caps = decide(defaultSig(), List.of(FType.ROW, FType.LOCAL, FType.LOCAL));
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.ROW, caps.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, caps.reason());
    assertNotePresent(caps, ReasonCode.BROADCAST_OR_ALIGNED_ROW, "broadcast-sliced");
    assertNotePresent(caps, ReasonCode.INFO, "broadcast");
  }

  // 3) (COL, LOCAL, COL): FED/FOUT(COL) + OK + U broadcast, V aligned
  @Test
  public void wumm_col_local_col() {
    OpCaps caps = decide(defaultSig(), List.of(FType.COL, FType.LOCAL, FType.COL));
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.COL, caps.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, caps.reason());
    assertNotePresent(caps, ReasonCode.INFO, "U broadcast");
    assertNotePresent(caps, ReasonCode.ALIGNED_HINT, "V aligned");
  }

  // 4) (COL, LOCAL, LOCAL): FED/FOUT(COL) + OK + U broadcast, V broadcast-sliced
  @Test
  public void wumm_col_local_local() {
    OpCaps caps = decide(defaultSig(), List.of(FType.COL, FType.LOCAL, FType.LOCAL));
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.COL, caps.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, caps.reason());
    assertNotePresent(caps, ReasonCode.INFO, "U broadcast");
    assertNotePresent(caps, ReasonCode.BROADCAST_OR_ALIGNED_COL, "broadcast-sliced");
  }

  // 5) (FULL, ROW, COL): CP/LOUT + PARTITION_FORBIDDEN + detail
  @Test
  public void wumm_full_forbidden() {
    OpCaps caps = decide(defaultSig(), List.of(FType.FULL, FType.ROW, FType.COL));
    assertEquals(Exec.CP, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.PARTITION_FORBIDDEN, caps.reason());
    assertEquals(WUMM_X_AXIS_ONLY_DETAIL, caps.detail().orElse(""));
  }

  // 6) (PART, ROW, COL): CP/LOUT + PARTITION_FORBIDDEN + detail
  @Test
  public void wumm_part_forbidden() {
    OpCaps caps = decide(defaultSig(), List.of(FType.PART, FType.ROW, FType.COL));
    assertEquals(Exec.CP, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.PARTITION_FORBIDDEN, caps.reason());
    assertEquals(WUMM_X_AXIS_ONLY_DETAIL, caps.detail().orElse(""));
  }

  // 7) (BROADCAST, ROW, COL): CP/LOUT + BROADCAST_CONSTRAINT
  @Test
  public void wumm_broadcast_constraint() {
    OpCaps caps = decide(defaultSig(), List.of(FType.BROADCAST, FType.ROW, FType.COL));
    assertEquals(Exec.CP, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, caps.reason());
  }

  // 8) (LOCAL, ROW, COL): CP/LOUT + NO_FED_INPUT
  @Test
  public void wumm_local_no_fed() {
    OpCaps caps = decide(defaultSig(), List.of(FType.LOCAL, FType.ROW, FType.COL));
    assertEquals(Exec.CP, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
  }

  // 9) Guard fail: CP/LOUT + REPR_CHANGE_GUARD_FAIL
  @Test
  public void wumm_guard_fail() {
    OpSig sig = OpSig.of("wumm", OpCategory.QUATERNARY, Map.of("q.type", "WUMM", "rc.guardOverride", "false"),
        InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);
    OpCaps caps = decide(sig, List.of(FType.ROW, FType.ROW, FType.LOCAL));
    assertEquals(Exec.CP, caps.exec());
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.REPR_CHANGE_GUARD_FAIL, caps.reason());
  }

  // 10) Guard unknown: FED/FOUT + unknown guard note
  @Test
  public void wumm_guard_unknown_note() {
    OpCaps caps = decide(defaultSig(), List.of(FType.ROW, FType.ROW, FType.LOCAL));
    assertEquals(Exec.FED, caps.exec());
    assertEquals(Placement.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.ROW, caps.foutFType().orElse(null));
    assertNotePresent(caps, ReasonCode.REPR_CHANGE_GUARD_UNKNOWN, "");
  }

  // profile: inputs {ROW, COL, FULL, PART} -> outputs {ROW, COL}
  @Test
  public void wumm_profile_row_col_only() {
    Rulesets.WeightedUnaryMMRule rule = new Rulesets.WeightedUnaryMMRule();
    OpSig sig = defaultSig();
    List<List<FType>> candidates = new ArrayList<>();
    candidates.add(List.of(FType.ROW, FType.COL, FType.FULL, FType.PART));
    FTypeProfile prof = rule.profile(sig, candidates, UNKNOWN_SHAPE);
    assertEquals(List.of(FType.ROW, FType.COL), prof.outputs());
  }

  private static OpCaps decide(OpSig sig, List<FType> in) {
    Rulesets.WeightedUnaryMMRule rule = new Rulesets.WeightedUnaryMMRule();
    return rule.caps(sig, in, UNKNOWN_SHAPE);
  }

  private static void assertNotePresent(OpCaps caps, ReasonCode code, String contains) {
    Optional<DecisionNote> note = caps.notes().stream()
        .filter(n -> n.code() == code)
        .findFirst();
    assertTrue("Expected note with code=" + code, note.isPresent());
    if (contains != null && !contains.isEmpty()) {
      assertTrue("Expected note message to contain '" + contains + "'",
          note.get().message().toLowerCase().contains(contains.toLowerCase()));
    }
  }
}

