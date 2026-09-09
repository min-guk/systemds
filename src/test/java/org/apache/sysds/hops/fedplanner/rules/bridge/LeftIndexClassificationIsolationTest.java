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

package org.apache.sysds.hops.fedplanner.rules.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerLogger;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.runtime.controlprogram.context.SparkExecutionContext;
import org.junit.Test;

public class LeftIndexClassificationIsolationTest {
  @Test
  public void facadeClassificationDoesNotInitializeSparkConfiguration() throws Exception {
    LeftIndexingOp hop = matrixLeftIndex();
    assertWithoutSparkConfiguration(() -> assertEquals(Opcodes.LEFT_INDEX.toString(),
        new OracleFacade(RulesCore.RulesModule.createDefaultRegistry()).describe(hop).opcode()));
  }

  @Test
  public void loggerClassificationDoesNotInitializeSparkConfiguration() throws Exception {
    LeftIndexingOp hop = matrixLeftIndex();
    Method canonicalOpcode = FederatedPlannerLogger.class.getDeclaredMethod("canonicalOpcode", Hop.class);
    canonicalOpcode.setAccessible(true);
    assertWithoutSparkConfiguration(() -> assertEquals(Opcodes.LEFT_INDEX.toString(),
        canonicalOpcode.invoke(null, hop)));
  }

  @Test
  public void legacyMapAliasHasTheSameCapsAndProfileAsLeftIndex() {
    Rulesets.LeftIndexRule rule = new Rulesets.LeftIndexRule();
    OpSig left = sig(Opcodes.LEFT_INDEX.toString());
    OpSig map = sig(Opcodes.MAPLEFTINDEX.toString());
    ShapeHint shape = new ShapeHint(10, 10, 1000);
    List<FType> inputs = Arrays.asList(FType.ROW, null, null, null, null, null);
    List<List<FType>> candidates = List.of(Arrays.asList(FType.ROW), List.of(),
        List.of(), List.of(), List.of(), List.of());

    assertCapsEqual(rule.caps(left, inputs, shape), rule.caps(map, inputs, shape));
    assertProfilesEqual(rule.profile(left, candidates, shape), rule.profile(map, candidates, shape));
  }

  private static void assertWithoutSparkConfiguration(ThrowingRunnable classification) throws Exception {
    Field sparkConfig = SparkExecutionContext.class.getDeclaredField("_sconf");
    sparkConfig.setAccessible(true);
    Object prior = sparkConfig.get(null);
    sparkConfig.set(null, null);
    try {
      classification.run();
      assertSame("pure HOP classification must not initialize Spark configuration", null,
          sparkConfig.get(null));
    }
    finally {
      sparkConfig.set(null, prior);
    }
  }

  private static void assertCapsEqual(OpCaps expected, OpCaps actual) {
    assertEquals(expected.category(), actual.category());
    assertEquals(expected.exec(), actual.exec());
    assertEquals(expected.placement(), actual.placement());
    assertEquals(expected.foutFType(), actual.foutFType());
    assertEquals(expected.reason(), actual.reason());
    assertEquals(expected.detail(), actual.detail());
    assertEquals(expected.notes(), actual.notes());
  }

  private static void assertProfilesEqual(FTypeProfile expected, FTypeProfile actual) {
    assertEquals(expected.outputs(), actual.outputs());
  }

  private static OpSig sig(String opcode) {
    return OpSig.of(opcode, OpCategory.INDEXING, Map.of(), InputKind.MATRIX, InputKind.MATRIX,
        InputKind.SCALAR, InputKind.SCALAR, InputKind.SCALAR, InputKind.SCALAR);
  }

  private static LeftIndexingOp matrixLeftIndex() {
    return new LeftIndexingOp("lix", DataType.MATRIX, ValueType.FP64,
        matrix("target", 10, 10), matrix("rhs", 2, 2),
        new LiteralOp(1L), new LiteralOp(2L), new LiteralOp(1L), new LiteralOp(2L), false, false);
  }

  private static DataOp matrix(String name, long rows, long cols) {
    return new DataOp(name, DataType.MATRIX, ValueType.FP64,
        OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
