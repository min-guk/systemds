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

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.fedCostBased.commons.ExecPlacementPolicy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.rewrite.HopRewriteUtils;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class BinaryMMFullProfileTest {
  private static final Rulesets.BinaryMMRule RULE = new Rulesets.BinaryMMRule();
  private static final OpSig MM = OpSig.of(Opcodes.MMULT.toString(), OpCategory.BINARY_MM, Map.of());
  private static final ShapeHint SINGLE = new ShapeHint(10, 10, 1000, true);
  private static final ShapeHint UNKNOWN = new ShapeHint(10, 10, 1000);

  @Test
  public void provenFullPairsAreDiscoveredUnderTheExistingCapsContract() {
    for(List<FType> inputs : List.of(
        List.of(FType.FULL, FType.FULL),
        Arrays.asList(FType.FULL, null),
        Arrays.asList(null, FType.FULL))) {
      OpCaps caps = RULE.caps(MM, inputs, SINGLE);
      assertEquals(ExecType.FED, caps.exec());
      assertEquals(FederatedOutput.FOUT, caps.placement());
      assertEquals(FType.FULL, caps.foutFType().orElse(null));
      assertEquals("profile must discover the same proven FULL output as caps for " + inputs,
          List.of(FType.FULL), profile(MM, inputs, SINGLE).outputs());
    }
  }

  @Test
  public void protectedAggregateCandidateUsesTheSameDiscoveredFullDomain() {
    List<FType> inputs = Arrays.asList(FType.FULL, null);
    OpCaps caps = RULE.caps(MM, inputs, SINGLE);
    assertEquals(List.of(FType.FULL), profile(MM, inputs, SINGLE).outputs());

    AggBinaryOp multiply = multiply();
    ExecPlacementPolicy.Decision privateAggregate =
        ExecPlacementPolicy.decide(multiply, Privacy.PRIVATE_AGGREGATE, FType.FULL, caps);
    assertTrue("protected data remains on the proven worker-resident FULL path",
        ExecPlacementPolicy.allowsCandidateEmission(privateAggregate,
            ExecType.FED, FederatedOutput.FOUT, false));
    assertFalse("the fixture must not silently become a public/local collection test",
        ExecPlacementPolicy.allowsCandidateEmission(privateAggregate,
            ExecType.FED, FederatedOutput.LOUT, false));
  }

  @Test
  public void unknownFullCardinalityDoesNotEnterTheProfile() {
    for(List<FType> inputs : List.of(
        List.of(FType.FULL, FType.FULL),
        Arrays.asList(FType.FULL, null),
        Arrays.asList(null, FType.FULL))) {
      assertFalse(RULE.caps(MM, inputs, UNKNOWN).foutEnabled());
      assertTrue("unknown single-partition proof must not propagate FULL for " + inputs,
          profile(MM, inputs, UNKNOWN).outputs().isEmpty());
    }
  }

  @Test
  public void representationGuardRejectionDoesNotEnterTheFullProfile() {
    OpSig guarded = OpSig.of(Opcodes.MMULT.toString(), OpCategory.BINARY_MM,
        Map.of("rc.guardOverride", "false"));
    List<FType> inputs = Arrays.asList(FType.FULL, null);
    assertFalse(RULE.caps(guarded, inputs, SINGLE).foutEnabled());
    assertTrue(profile(guarded, inputs, SINGLE).outputs().isEmpty());
  }

  @Test
  public void rowAndBroadcastProfilesRemainUnchanged() {
    assertEquals(List.of(FType.ROW),
        profile(MM, Arrays.asList(FType.ROW, null), UNKNOWN).outputs());
    assertEquals(List.of(FType.BROADCAST),
        profile(MM, Arrays.asList(null, FType.BROADCAST), UNKNOWN).outputs());
    assertTrue("BROADCAST x FULL has a BROADCAST cap, not a FULL output",
        profile(MM, List.of(FType.BROADCAST, FType.FULL), SINGLE).outputs().isEmpty());
  }

  @Test
  public void tsmmAndMmchainDoNotAcquireFullProfileOutputs() {
    OpSig tsmm = OpSig.of(Opcodes.MMULT.toString(), OpCategory.BINARY_MM,
        Map.of("tsmm.type", "LEFT"));
    assertEquals(List.of(FType.BROADCAST),
        profile(tsmm, List.of(FType.FULL, FType.FULL), SINGLE).outputs());

    OpSig mmchain = OpSig.of(Opcodes.MMCHAIN.toString(), OpCategory.OTHER, Map.of());
    assertFalse(RULE.supports(mmchain));
    assertTrue(profile(mmchain, List.of(FType.FULL, FType.FULL), SINGLE).outputs().isEmpty());
  }

  private static FTypeProfile profile(OpSig sig, List<FType> inputs, ShapeHint hint) {
    return RULE.profile(sig, List.of(
        Arrays.asList(inputs.get(0)), Arrays.asList(inputs.get(1))), hint);
  }

  private static AggBinaryOp multiply() {
    Hop left = new DataOp("A", DataType.MATRIX, ValueType.FP64,
        OpOpData.TRANSIENTREAD, "A", 10, 10, 100, 1000);
    Hop right = new DataOp("B", DataType.MATRIX, ValueType.FP64,
        OpOpData.TRANSIENTREAD, "B", 10, 10, 100, 1000);
    return HopRewriteUtils.createMatrixMultiply(left, right);
  }
}
