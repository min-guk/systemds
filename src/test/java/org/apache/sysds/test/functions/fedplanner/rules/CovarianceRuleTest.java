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

import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Exec;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Placement;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class CovarianceRuleTest {

  private static final ShapeHint UNKNOWN = new ShapeHint(-1, -1, 0);
  private static final OpSig COV_SIG =
      new OpSig("cov", OpCategory.BINARY_EWISE, Map.of());
  private static final OpSig COV_SIG_ALIGN_ROW =
      new OpSig("covariance", OpCategory.BINARY_EWISE, Map.of("align_hint", "ROW"));

  private final Rulesets.CovarianceRule rule = new Rulesets.CovarianceRule();

  @Test
  public void rowRowFederatedAllowed() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.ROW, FType.ROW), UNKNOWN);
    assertEquals(Exec.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void colColFederatedAllowed() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.COL, FType.COL), UNKNOWN);
    assertEquals(Exec.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void rowColRejected() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.ROW, FType.COL), UNKNOWN);
    assertEquals(Exec.CP, caps.exec());
    assertEquals(ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void rowAndNonFederatedFallsBackToFed() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.ROW, FType.NF), UNKNOWN);
    assertEquals(Exec.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void nonFederatedAndColFallsBackToFed() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.NF, FType.COL), UNKNOWN);
    assertEquals(Exec.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void noFederatedInputsArityOk() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.NF, FType.NF), UNKNOWN);
    assertEquals(Exec.CP, caps.exec());
    assertEquals(ReasonCode.NO_FED_INPUT, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void weightsLocalNoted() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.ROW, FType.ROW, FType.NF), UNKNOWN);
    assertTrue(hasNote(caps, "weights=local"));
    assertEquals(Exec.FED, caps.exec());
    assertLocalScalar(caps);
  }

  @Test
  public void weightsFederatedNoted() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.ROW, FType.ROW, FType.ROW), UNKNOWN);
    assertTrue(hasNote(caps, "weights=broadcast-sliced"));
    assertEquals(Exec.FED, caps.exec());
    assertLocalScalar(caps);
  }

  @Test
  public void partWithRowRejectedWithoutHint() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.PART, FType.ROW), UNKNOWN);
    assertEquals(Exec.CP, caps.exec());
    assertEquals(ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, caps.reason());
    assertLocalScalar(caps);
  }

  @Test
  public void partPairAlignedViaHint() {
    OpCaps caps = rule.caps(COV_SIG_ALIGN_ROW, List.of(FType.PART, FType.PART), UNKNOWN);
    assertEquals(Exec.FED, caps.exec());
    assertEquals(ReasonCode.OK, caps.reason());
    assertTrue(hasNote(caps, "align=hint:ROW"));
    assertLocalScalar(caps);
  }

  @Test
  public void arityMismatchFallsBackToCp() {
    OpCaps caps = rule.caps(COV_SIG, List.of(FType.ROW), UNKNOWN);
    assertEquals(Exec.CP, caps.exec());
    assertEquals(ReasonCode.ARITY_MISMATCH, caps.reason());
    assertLocalScalar(caps);
  }

  private static void assertLocalScalar(OpCaps caps) {
    assertEquals(Placement.LOUT, caps.placement());
    assertFalse("FOUT must never be enabled", caps.foutEnabled());
  }

  private static boolean hasNote(OpCaps caps, String message) {
    return caps.notes().stream().anyMatch(note -> message.equals(note.message()));
  }
}
