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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RulesetsNaryRuleTest {

  private static final String MULT = OpOpN.MULT.name().toLowerCase(Locale.ROOT);

  private final Rulesets.NaryElemwiseRule rule = new Rulesets.NaryElemwiseRule();

  @Test
  public void fullInputsRetainNativeFullOutput() {
    ShapeHint hint = unknownShape();
    assertFederatedFull(Arrays.asList(FType.FULL, FType.FULL, FType.BROADCAST),
        hint, InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);
    assertFalse("all-federated FULL must not require a single-range proof", hint.wasConsulted());
  }

  @Test
  public void localScalarDoesNotRequireSingleRangeFullBase() {
    ShapeHint hint = unknownShape();
    assertFederatedFull(Arrays.asList(FType.FULL, null),
        hint, InputKind.MATRIX, InputKind.SCALAR);
    assertFalse("local scalars are embedded and must not require topology proof", hint.wasConsulted());
  }

  @Test
  public void oneLocalMatrixCanBroadcastToSingleRangeFullBase() {
    ShapeHint hint = new ShapeHint(-1, -1, 0, true);
    assertFederatedFull(Arrays.asList(FType.FULL, null, null),
        hint, InputKind.MATRIX, InputKind.MATRIX, InputKind.SCALAR);
    assertTrue(hint.proof().requiredFacts().contains("fullSinglePartition"));
  }

  @Test
  public void localMatrixWithMultiRangeFullBaseRemainsRejected() {
    ShapeHint hint = new ShapeHint(-1, -1, 0, false);
    OpCaps caps = caps(Arrays.asList(FType.FULL, null, null), hint,
        InputKind.MATRIX, InputKind.MATRIX, InputKind.SCALAR);

    assertCp(caps, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
    assertTrue(hint.proof().requiredFacts().contains("fullSinglePartition"));
    assertTrue(hint.proof().missingRequiredFacts().isEmpty());
  }

  @Test
  public void localMatrixWithUnknownFullTopologyRemainsRejected() {
    ShapeHint hint = unknownShape();
    OpCaps caps = caps(Arrays.asList(FType.FULL, null, null), hint,
        InputKind.MATRIX, InputKind.MATRIX, InputKind.SCALAR);

    assertCp(caps, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
    assertTrue(hint.proof().requiredFacts().contains("fullSinglePartition"));
    assertTrue(hint.proof().missingRequiredFacts().contains("fullSinglePartition"));
  }

  @Test
  public void twoLocalMatricesBroadcastWithRowBase() {
    OpCaps caps = caps(Arrays.asList(FType.ROW, null, null), unknownShape(),
        InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertEquals(FType.ROW, caps.foutFType().orElse(null));
  }

  @Test
  public void twoLocalMatricesNeedSingleRangeForFullBase() {
    OpCaps caps = caps(Arrays.asList(FType.FULL, null, null), unknownShape(),
        InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);
    assertCp(caps, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);

    assertFederatedFull(Arrays.asList(FType.FULL, null, null),
        new ShapeHint(-1, -1, 0, true),
        InputKind.MATRIX, InputKind.MATRIX, InputKind.MATRIX);
  }

  @Test
  public void mixedFullAndPartitionAxesRemainRejected() {
    List<List<FType>> mixedLayouts = List.of(
        List.of(FType.FULL, FType.ROW),
        List.of(FType.ROW, FType.FULL),
        List.of(FType.FULL, FType.COL),
        List.of(FType.COL, FType.FULL));

    for (List<FType> layout : mixedLayouts) {
      OpCaps caps = caps(layout, unknownShape(), InputKind.MATRIX, InputKind.MATRIX);
      assertCp(caps, ReasonCode.UNSUPPORTED_ALIGNMENT);
    }
  }

  @Test
  public void unsupportedPartLayoutRemainsRejected() {
    OpCaps caps = caps(List.of(FType.FULL, FType.PART), unknownShape(),
        InputKind.MATRIX, InputKind.MATRIX);

    assertCp(caps, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
  }

  @Test
  public void otherLayoutIsNotTreatedAsLocalOrBroadcast() {
    List<List<FType>> unsupportedLayouts = List.of(
        List.of(FType.FULL, FType.OTHER),
        List.of(FType.OTHER, FType.FULL),
        List.of(FType.ROW, FType.OTHER),
        List.of(FType.OTHER, FType.ROW));

    for (List<FType> layout : unsupportedLayouts) {
      OpCaps caps = caps(layout, unknownShape(), InputKind.MATRIX, InputKind.MATRIX);
      assertCp(caps, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY);
    }
  }

  private void assertFederatedFull(List<FType> inFTypes, ShapeHint hint, InputKind... inputKinds) {
    OpCaps caps = caps(inFTypes, hint, inputKinds);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(FederatedOutput.FOUT, caps.placement());
    assertEquals(ReasonCode.OK, caps.reason());
    assertTrue(caps.foutEnabled());
    assertEquals(FType.FULL, caps.foutFType().orElse(null));
  }

  private static void assertCp(OpCaps caps, ReasonCode reason) {
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertEquals(reason, caps.reason());
    assertFalse(caps.foutEnabled());
    assertFalse(caps.foutFType().isPresent());
  }

  private OpCaps caps(List<FType> inFTypes, ShapeHint hint, InputKind... inputKinds) {
    OpSig sig = OpSig.of(MULT, OpCategory.BINARY_EWISE, Map.of(), inputKinds);
    return rule.caps(sig, inFTypes, hint);
  }

  private static ShapeHint unknownShape() {
    return new ShapeHint(-1, -1, 0);
  }
}
