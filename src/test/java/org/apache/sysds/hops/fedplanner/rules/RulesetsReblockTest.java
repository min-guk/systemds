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
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Test;

public class RulesetsReblockTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);

  private static Rulesets.ReblockRule rule() {
    return new Rulesets.ReblockRule();
  }

  @Test
  public void reblock_row_preserves_axis_and_notes() {
    OpCaps caps = decide(FType.ROW);
    assertFedFout(caps, FType.ROW);
    assertNoteContains(caps, ReasonCode.INFO, "metadata");
    assertNoteContains(caps, ReasonCode.INFO, "blocksize");
  }

  @Test
  public void reblock_col_preserves_axis() {
    assertFedFout(decide(FType.COL), FType.COL);
  }

  @Test
  public void reblock_part_preserves_axis() {
    assertFedFout(decide(FType.PART), FType.PART);
  }

  @Test
  public void reblock_full_preserves_axis() {
    assertFedFout(decide(FType.FULL), FType.FULL);
  }

  @Test
  public void reblock_broadcast_constraint() {
    OpCaps caps = decide(FType.BROADCAST);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, caps.reason());
  }

  @Test
  public void reblock_local_no_fed_input() {
    OpCaps caps = decide(FType.LOCAL);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
  }

  @Test
  public void reblock_nf_no_fed_input() {
    OpCaps caps = decide(FType.NF);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertFalse(caps.foutEnabled());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
  }

  @Test
  public void reblock_profile_mirrors_fed_inputs() {
    List<List<FType>> inputs = List.of(List.of(FType.ROW, FType.COL, FType.PART, FType.FULL,
        FType.BROADCAST, FType.LOCAL));
    FTypeProfile profile = rule().profile(sig(), inputs, UNKNOWN_SHAPE);
    assertEquals(List.of(FType.ROW, FType.COL, FType.PART, FType.FULL), profile.outputs());
  }

  private static OpCaps decide(FType in) {
    return rule().caps(sig(), List.of(in), UNKNOWN_SHAPE);
  }

  private static OpSig sig() {
    return OpSig.of(Opcodes.RBLK.toString(), OpCategory.REORG, Map.of(), InputKind.MATRIX);
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
        .filter(n -> n.code() == code && n.message().toLowerCase().contains(contains.toLowerCase()))
        .findFirst();
    assertTrue("Expected note " + code + " containing '" + contains + "'", note.isPresent());
  }
}
