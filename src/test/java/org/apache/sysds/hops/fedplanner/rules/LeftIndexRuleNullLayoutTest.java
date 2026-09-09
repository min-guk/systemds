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
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.junit.Test;

public class LeftIndexRuleNullLayoutTest {
  private static final ShapeHint SHAPE = new ShapeHint(2100, 2100, 1000);
  private final Rulesets.LeftIndexRule rule = new Rulesets.LeftIndexRule();

  @Test
  public void immutableFederatedLhsWithLocalScalarIndicesKeepsItsLayout() {
    List<List<FType>> inputLayouts = List.of(
        List.of(FType.ROW),
        List.of(FType.ROW, FType.COL, FType.FULL, FType.PART, FType.BROADCAST),
        localLayout(), localLayout(), localLayout(), localLayout());

    FTypeProfile profile = rule.profile(leftIndexSig(), inputLayouts, SHAPE);

    assertEquals(List.of(FType.ROW), profile.outputs());
  }

  @Test
  public void localMatrixWithFullRhsRetainsFullAnchorSemantics() {
    FTypeProfile profile = rule.profile(leftIndexSig(),
        List.of(localLayout(), List.of(FType.FULL),
            localLayout(), localLayout(), localLayout(), localLayout()),
        SHAPE);

    assertEquals(List.of(FType.FULL), profile.outputs());
  }

  @Test
  public void missingLayoutsRemainUnsupported() {
    assertTrue(rule.profile(leftIndexSig(), List.of(), SHAPE).outputs().isEmpty());
  }

  private static OpSig leftIndexSig() {
    return OpSig.of(Opcodes.LEFT_INDEX.toString(), OpCategory.INDEXING, Map.of(),
        InputKind.MATRIX, InputKind.MATRIX,
        InputKind.SCALAR, InputKind.SCALAR, InputKind.SCALAR, InputKind.SCALAR);
  }

  private static List<FType> localLayout() {
    return Collections.singletonList(null);
  }
}
