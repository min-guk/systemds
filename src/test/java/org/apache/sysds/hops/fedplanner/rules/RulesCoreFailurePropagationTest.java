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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.FTypeProfile;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class RulesCoreFailurePropagationTest {
  private static final ShapeHint SHAPE = new ShapeHint(11, 7, 1024, true);

  @Test
  public void inferenceRuntimeFailureAbortsWithRuleContextAndCause() {
    IllegalArgumentException cause = new IllegalArgumentException("profile exploded");
    OpSig sig = sig("throw-profile");
    List<List<FType>> inputLayout = List.of(List.of(FType.ROW), List.of(FType.BROADCAST));
    RulesCore.RuleRegistry registry = registry(new SyntheticRule(sig.opcode(), () -> {
      throw cause;
    }, null));

    IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
        () -> new RulesCore.InferenceEngine(registry).infer(sig, inputLayout, SHAPE));

    assertSame(cause, failure.getCause());
    assertContext(failure, "phase=profile", "opcode=throw-profile",
        "inputLayout=[[ROW], [BROADCAST]]", "rows=11", "cols=7", "blockSize=1024");
  }

  @Test
  public void oracleRuntimeFailureAbortsWithRuleContextAndCause() {
    IllegalStateException cause = new IllegalStateException("caps exploded");
    OpSig sig = sig("throw-caps");
    List<FType> inputLayout = List.of(FType.COL, FType.FULL);
    RulesCore.RuleRegistry registry = registry(new SyntheticRule(sig.opcode(), null, () -> {
      throw cause;
    }));

    IllegalStateException failure = Assert.assertThrows(IllegalStateException.class,
        () -> new RulesCore.OracleEngine(registry).decide(sig, inputLayout, SHAPE));

    assertSame(cause, failure.getCause());
    assertContext(failure, "phase=caps", "opcode=throw-caps",
        "inputLayout=[COL, FULL]", "rows=11", "cols=7", "blockSize=1024");
  }

  @Test
  public void ruleErrorsPropagateUnchanged() {
    AssertionError profileError = new AssertionError("fatal profile error");
    OpSig profileSig = sig("error-profile");
    RulesCore.RuleRegistry profileRegistry = registry(new SyntheticRule(profileSig.opcode(), () -> {
      throw profileError;
    }, null));
    AssertionError observedProfileError = Assert.assertThrows(AssertionError.class,
        () -> new RulesCore.InferenceEngine(profileRegistry)
            .infer(profileSig, List.of(List.of(FType.ROW)), SHAPE));
    assertSame(profileError, observedProfileError);

    AssertionError capsError = new AssertionError("fatal caps error");
    OpSig capsSig = sig("error-caps");
    RulesCore.RuleRegistry capsRegistry = registry(new SyntheticRule(capsSig.opcode(), null, () -> {
      throw capsError;
    }));
    AssertionError observedCapsError = Assert.assertThrows(AssertionError.class,
        () -> new RulesCore.OracleEngine(capsRegistry)
            .decide(capsSig, List.of(FType.COL), SHAPE));
    assertSame(capsError, observedCapsError);
  }

  @Test
  public void registeredUnsupportedRuleKeepsDefaultSemantics() {
    OpSig sig = sig("registered-unsupported");
    RulesCore.RuleRegistry registry = registry(new SyntheticRule(sig.opcode(), null, null));

    FTypeProfile profile = new RulesCore.InferenceEngine(registry)
        .infer(sig, List.of(List.of(FType.ROW)), SHAPE);
    OpCaps caps = new RulesCore.OracleEngine(registry)
        .decide(sig, List.of(FType.ROW), SHAPE);

    assertTrue(profile.outputs().isEmpty());
    assertCpLout(caps, ReasonCode.NOT_IMPLEMENTED);
  }

  @Test
  public void absentRuleKeepsNoRuleSemantics() {
    OpSig sig = sig("absent-rule");
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();

    FTypeProfile profile = new RulesCore.InferenceEngine(registry)
        .infer(sig, List.of(List.of(FType.ROW)), SHAPE);
    OpCaps caps = new RulesCore.OracleEngine(registry)
        .decide(sig, List.of(FType.ROW), SHAPE);

    assertTrue(profile.outputs().isEmpty());
    assertCpLout(caps, ReasonCode.NO_RULE);
  }

  private static OpSig sig(String opcode) {
    return new OpSig(opcode, OpCategory.OTHER, Map.of());
  }

  private static RulesCore.RuleRegistry registry(SyntheticRule rule) {
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
    registry.register(rule);
    return registry;
  }

  private static void assertContext(IllegalStateException failure, String... fragments) {
    for (String fragment : fragments) {
      assertTrue("Missing diagnostic fragment '" + fragment + "' in: " + failure.getMessage(),
          failure.getMessage().contains(fragment));
    }
  }

  private static void assertCpLout(OpCaps caps, ReasonCode reason) {
    assertEquals(ExecType.CP, caps.exec());
    assertEquals(FederatedOutput.LOUT, caps.placement());
    assertEquals(reason, caps.reason());
  }

  private static final class SyntheticRule extends RulesCore.BaseRule {
    private final String opcode;
    private final Supplier<FTypeProfile> profile;
    private final Supplier<OpCaps> caps;

    private SyntheticRule(String opcode, Supplier<FTypeProfile> profile, Supplier<OpCaps> caps) {
      this.opcode = opcode;
      this.profile = profile;
      this.caps = caps;
    }

    @Override
    public OpCategory category() {
      return OpCategory.OTHER;
    }

    @Override
    public Set<String> opcodes() {
      return Set.of(opcode);
    }

    @Override
    public FTypeProfile profile(OpSig sig, List<List<FType>> inFTypeCandidates, ShapeHint hint) {
      return profile == null ? super.profile(sig, inFTypeCandidates, hint) : profile.get();
    }

    @Override
    public OpCaps caps(OpSig sig, List<FType> inFTypes, ShapeHint hint) {
      return caps == null ? super.caps(sig, inFTypes, hint) : caps.get();
    }
  }
}
