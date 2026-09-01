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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RulesetsReshapeTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);

  private static Rulesets.ReshapeRule rule() {
    return new Rulesets.ReshapeRule();
  }

  @Test
  public void reshape_row_byrow_true_axis_row_and_notes() {
    OpCaps caps = decide(true, FType.ROW);
    assertFedFout(caps, FType.ROW);
    assertNoteContains(caps, ReasonCode.BROADCAST_OR_ALIGNED_ROW, "divisible by cols");
    assertNoteContains(caps, ReasonCode.INFO, "global cells");
  }

  @Test
  public void reshape_col_byrow_false_axis_col_and_notes() {
    OpCaps caps = decide(false, FType.COL);
    assertFedFout(caps, FType.COL);
    assertNoteContains(caps, ReasonCode.BROADCAST_OR_ALIGNED_COL, "divisible by rows");
    assertNoteContains(caps, ReasonCode.INFO, "global cells");
  }

  @Test
  public void reshape_part_input_tracks_axis_true() {
    assertFedFout(decide(true, FType.PART), FType.ROW);
  }

  @Test
  public void reshape_part_input_tracks_axis_false() {
    assertFedFout(decide(false, FType.PART), FType.COL);
  }

  @Test
  public void reshape_full_input_tracks_axis_true() {
    assertFedFout(decide(true, FType.FULL), FType.ROW);
  }

  @Test
  public void reshape_full_input_tracks_axis_false() {
    assertFedFout(decide(false, FType.FULL), FType.COL);
  }

  @Test
  public void reshape_broadcast_constraint() {
    OpCaps caps = decide(true, FType.BROADCAST);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, caps.reason());
  }

  @Test
  public void reshape_local_no_fed_input() {
    OpCaps caps = decide(true, null);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
  }

  @Test
  public void reshape_nf_no_fed_input() {
    OpCaps caps = decide(true, null);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
  }

  @Test
  public void reshape_missing_byrow_attr_detail() {
    OpCaps caps = rule().caps(sig(null), List.of(FType.ROW), UNKNOWN_SHAPE);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.INFO, caps.reason());
    assertTrue(caps.detail().orElse("").contains("byrow not provided"));
  }

  @Test
  public void reshape_profile_byrow_true() {
    FTypeProfile profile = rule().profile(sig(true), List.of(List.of(FType.ROW)), UNKNOWN_SHAPE);
    assertEquals(List.of(FType.ROW), profile.outputs());
  }

  @Test
  public void reshape_profile_byrow_false() {
    FTypeProfile profile = rule().profile(sig(false), List.of(List.of(FType.COL)), UNKNOWN_SHAPE);
    assertEquals(List.of(FType.COL), profile.outputs());
  }

  @Test
  public void reshape_profile_missing_attr() {
    FTypeProfile profile = rule().profile(sig(null), List.of(List.of(FType.ROW, FType.COL)), UNKNOWN_SHAPE);
    assertEquals(List.of(FType.ROW, FType.COL), profile.outputs());
  }

  private static OpCaps decide(Boolean byRow, FType in) {
    return rule().caps(sig(byRow), Arrays.asList(in), UNKNOWN_SHAPE);
  }

  private static OpSig sig(Boolean byRow) {
    Map<String,String> attrs = (byRow == null)
        ? Map.of()
        : Map.of("reshape.byrow", Boolean.toString(byRow));
    return OpSig.of(ReOrgOp.RESHAPE.toString(), OpCategory.RESHAPE, attrs, InputKind.MATRIX);
  }

  private static void assertFedFout(OpCaps caps, FType expectedAxis) {
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertTrue(caps.foutEnabled());
    assertEquals(expectedAxis, caps.foutFType().orElse(null));
    assertEquals(ReasonCode.OK, caps.reason());
  }

  private static void assertNoteContains(OpCaps caps, ReasonCode code, String contains) {
    Optional<DecisionNote> note = caps.notes().stream()
        .filter(n -> n.code() == code)
        .findFirst();
    assertTrue("Expected note " + code, note.isPresent());
    if (contains != null && !contains.isEmpty()) {
      assertTrue(note.get().message().toLowerCase().contains(contains.toLowerCase()));
    }
  }
}
