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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class RuleBasicsTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);

  @Test
  public void registryLookupIgnoresOpcodeCase() {
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
    Rulesets.AppendRule appendRule = new Rulesets.AppendRule();
    registry.register(appendRule);

    Optional<Rule> located = registry.byOpcode("APPEND");
    assertTrue("Registry must find opcode irrespective of case", located.isPresent());
    assertSame(appendRule, located.get());
  }

  @Test
  public void defaultModuleContainsAggUnaryAndBinaryMM() {
    RulesCore.RuleRegistry registry = RulesCore.RulesModule.createDefaultRegistry();
    boolean hasAggUnary = false;
    boolean hasBinaryMM = false;
    for (Rule rule : registry.allRules()) {
      hasAggUnary |= rule instanceof Rulesets.AggUnaryRule;
      hasBinaryMM |= rule instanceof Rulesets.BinaryMMRule;
    }
    assertTrue("AggUnaryRule should be registered", hasAggUnary);
    assertTrue("BinaryMMRule should be registered", hasBinaryMM);
  }

  @Test
  public void appendRuleSupportsUppercaseOpcode() {
    Rulesets.AppendRule rule = new Rulesets.AppendRule();
    OpSig sig = OpSig.of(Opcodes.APPEND.toString(), OpCategory.APPEND, Map.of("cbind", "true"));

    assertTrue(rule.supports(sig));
    OpCaps caps = rule.caps(sig, List.of(FType.ROW, FType.ROW), UNKNOWN_SHAPE);
    assertEquals(ExecType.FED, caps.exec());
    assertEquals(ReasonCode.BROADCAST_OR_ALIGNED_ROW, caps.reason());
  }

  @Test
  public void quantilePickIncludesQpick() {
    Rulesets.QuantilePickRule rule = new Rulesets.QuantilePickRule();
    OpSig sig = OpSig.of(Opcodes.QPICK.toString(), OpCategory.QUANTILE_PICK, Map.of());
    assertTrue(rule.opcodes().contains(Opcodes.QPICK.toString()));

    OpCaps caps = rule.caps(sig, List.of(FType.NF), UNKNOWN_SHAPE);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.NOT_FEDERATED_INPUTS, caps.reason());
  }

  @Test
  public void fullInputNotTreatedAsScalarLike() {
    Rulesets.BinaryElemwiseRule rule = new Rulesets.BinaryElemwiseRule();
    OpSig sig = OpSig.of(OpOp2.PLUS.toString(), OpCategory.BINARY_EWISE, Map.of());
    OpCaps caps = rule.caps(sig, List.of(FType.ROW, FType.FULL), UNKNOWN_SHAPE);
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(ReasonCode.UNSUPPORTED_ALIGNMENT, caps.reason());
  }
}
