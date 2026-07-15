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
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps.DecisionNote;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class RulesetsWeightedQuaternaryTest {
  private static final String SCALAR_DETAIL = "scalar output → LOUT";
  private static final String WDIVMM_ALIGN_DETAIL = "output dims derive from U/V; partition misalignment risk";
  private static final String WSLOSS_X_AXIS_ONLY_DETAIL =
      "federated WSLoss runtime supports ROW/COL partitioned X only";

  @Test
  public void weightedQuaternarySmokeTable() {
    ShapeHint wdivRowMismatch = hint(10, 5, 20, 4);
    ShapeHint wdivColMismatch = hint(8, 9, 8, 5);
    List<Scenario> scenarios = List.of(
        Scenario.of("wsLoss-row-fed",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(FType.ROW, null, null, null),
            null,
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.OK,
            SCALAR_DETAIL,
            null),
        Scenario.of("wsLoss-col-fed",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(FType.COL, null, null, null),
            null,
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.OK,
            SCALAR_DETAIL,
            null),
        Scenario.of("wsLoss-arity-mismatch",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(FType.ROW, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.ARITY_MISMATCH,
            SCALAR_DETAIL,
            null),
        Scenario.of("wsLoss-broadcast-only",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(FType.BROADCAST, null, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.BROADCAST_CONSTRAINT,
            SCALAR_DETAIL,
            null),
        Scenario.of("wsLoss-no-fed-input",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(null, null, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.NO_FED_INPUT,
            SCALAR_DETAIL,
            null),
        Scenario.of("wsLoss-full-forbidden",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(FType.FULL, null, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.PARTITION_FORBIDDEN,
            WSLOSS_X_AXIS_ONLY_DETAIL,
            null),
        Scenario.of("wsLoss-part-forbidden",
            new Rulesets.WeightedSquaredLossRule(),
            Opcodes.WSLOSS.toString(),
            Map.of("q.type", "WSLOSS"),
            Arrays.asList(FType.PART, null, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.PARTITION_FORBIDDEN,
            WSLOSS_X_AXIS_ONLY_DETAIL,
            null),
        Scenario.of("wcemm-col-fed",
            new Rulesets.WeightedCrossEntropyRule(),
            Opcodes.WCEMM.toString(),
            Map.of("q.type", "WCEMM"),
            Arrays.asList(FType.COL, null, null, null),
            null,
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.OK,
            SCALAR_DETAIL,
            null),
        Scenario.of("wsSigmoid-row-guard-unknown",
            new Rulesets.WeightedSigmoidRule(),
            Opcodes.WSIGMOID.toString(),
            Map.of("q.type", "WSIGMOID"),
            Arrays.asList(FType.ROW, null, null),
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_UNKNOWN),
        Scenario.of("wumm-col",
            new Rulesets.WeightedUnaryMMRule(),
            Opcodes.WUMM.toString(),
            Map.of("q.type", "WUMM"),
            Arrays.asList(FType.COL, null, null),
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.COL,
            ReasonCode.OK,
            null,
            null),
        Scenario.of("wdivmm-basic-row",
            new Rulesets.WeightedDivMMRule(),
            Opcodes.WDIVMM.toString(),
            Map.ofEntries(
                Map.entry("q.type", "WDIVMM"),
                Map.entry("wdivmm.baseType", "0")),
            Arrays.asList(FType.ROW, null, null, null),
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null,
            null),
        Scenario.of("wdivmm-left-misaligned",
            new Rulesets.WeightedDivMMRule(),
            Opcodes.WDIVMM.toString(),
            Map.ofEntries(
                Map.entry("q.type", "WDIVMM"),
                Map.entry("wdivmm.baseType", "1")),
            Arrays.asList(FType.ROW, null, null, null),
            wdivRowMismatch,
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
            WDIVMM_ALIGN_DETAIL,
            null),
        Scenario.of("wdivmm-right-misaligned",
            new Rulesets.WeightedDivMMRule(),
            Opcodes.WDIVMM.toString(),
            Map.ofEntries(
                Map.entry("q.type", "WDIVMM"),
                Map.entry("wdivmm.baseType", "2")),
            Arrays.asList(FType.COL, null, null, null),
            wdivColMismatch,
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY,
            WDIVMM_ALIGN_DETAIL,
            null),
        Scenario.of("wdivmm-basetype-missing",
            new Rulesets.WeightedDivMMRule(),
            Opcodes.WDIVMM.toString(),
            Map.of("q.type", "WDIVMM"),
            Arrays.asList(FType.ROW, null, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.OPCODE_UNSUPPORTED,
            null,
            null),
        Scenario.of("wsSigmoid-broadcast",
            new Rulesets.WeightedSigmoidRule(),
            Opcodes.WSIGMOID.toString(),
            Map.of("q.type", "WSIGMOID"),
            Arrays.asList(FType.BROADCAST, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.BROADCAST_CONSTRAINT,
            null,
            null),
        Scenario.of("wumm-guard-fail",
            new Rulesets.WeightedUnaryMMRule(),
            Opcodes.WUMM.toString(),
            Map.ofEntries(
                Map.entry("q.type", "WUMM"),
                Map.entry("rc.guardOverride", "false")),
            Arrays.asList(FType.ROW, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.REPR_CHANGE_GUARD_FAIL,
            "override=false",
            null),
        Scenario.of("wcemm-local",
            new Rulesets.WeightedCrossEntropyRule(),
            Opcodes.WCEMM.toString(),
            Map.of("q.type", "WCEMM"),
            Arrays.asList(null, null, null, null),
            null,
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.NO_FED_INPUT,
            SCALAR_DETAIL,
            null),
        Scenario.of("wumm-guard-pass",
            new Rulesets.WeightedUnaryMMRule(),
            Opcodes.WUMM.toString(),
            Map.ofEntries(
                Map.entry("q.type", "WUMM"),
                Map.entry("rc.guardOverride", "true")),
            Arrays.asList(FType.ROW, null, null),
            null,
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null,
            ReasonCode.REPR_CHANGE_GUARD_PASS));

    for (Scenario scenario : scenarios) {
      OpSig sig = quaternarySig(scenario.opcode, scenario.attrs, scenario.inFTypes.size());
      OpCaps caps = scenario.rule.caps(sig, scenario.inFTypes, scenario.hint);
      String msg = "scenario=" + scenario.name;
      assertEquals(msg, scenario.exec, caps.exec());
      assertEquals(msg, scenario.placement, caps.placement());
      assertEquals(msg, scenario.reason, caps.reason());
      assertEquals(msg, scenario.fout, caps.foutEnabled());
      if (scenario.fout) {
        assertTrue(msg, caps.foutFType().isPresent());
        assertEquals(msg, scenario.foutType, caps.foutFType().orElse(null));
      }
      else {
        assertTrue(msg, caps.foutFType().isEmpty());
      }
      if (scenario.detail == null) {
        assertTrue(msg, caps.detail().isEmpty());
      }
      else {
        assertTrue(msg, caps.detail().isPresent());
        assertEquals(msg, scenario.detail, caps.detail().get());
      }
      if (scenario.expectedNote != null) {
        boolean found = caps.notes().stream()
            .map(DecisionNote::code)
            .anyMatch(rc -> rc == scenario.expectedNote);
        assertTrue(msg + " expected guard note " + scenario.expectedNote, found);
      }
    }
  }

  private static OpSig quaternarySig(String opcode, Map<String,String> attrs, int arity) {
    InputKind[] kinds = new InputKind[arity];
    Arrays.fill(kinds, InputKind.MATRIX);
    return OpSig.of(opcode, OpCategory.QUATERNARY, attrs, kinds);
  }

  private static ShapeHint hint(long outRows, long outCols, long xRows, long xCols) {
    return new ShapeHint(outRows, outCols, 1000, Optional.empty(), xRows, xCols, -1, -1);
  }

  private static final class Scenario {
    final String name;
    final Rule rule;
    final String opcode;
    final Map<String,String> attrs;
    final List<FType> inFTypes;
    final ShapeHint hint;
    final ExecType exec;
    final FederatedOutput placement;
    final boolean fout;
    final FType foutType;
    final ReasonCode reason;
    final String detail;
    final ReasonCode expectedNote;

    private Scenario(String name, Rule rule, String opcode, Map<String,String> attrs,
        List<FType> inFTypes, ShapeHint hint, ExecType exec, FederatedOutput placement,
        boolean fout, FType foutType, ReasonCode reason, String detail,
        ReasonCode expectedNote) {
      this.name = name;
      this.rule = rule;
      this.opcode = opcode;
      this.attrs = attrs;
      this.inFTypes = inFTypes;
      this.hint = hint;
      this.exec = exec;
      this.placement = placement;
      this.fout = fout;
      this.foutType = foutType;
      this.reason = reason;
      this.detail = detail;
      this.expectedNote = expectedNote;
    }

    static Scenario of(String name, Rule rule, String opcode, Map<String,String> attrs,
        List<FType> inFTypes, ShapeHint hint, ExecType exec, FederatedOutput placement,
        boolean fout, FType foutType, ReasonCode reason, String detail,
        ReasonCode expectedNote) {
      return new Scenario(name, rule, opcode, attrs, inFTypes, hint,
          exec, placement, fout, foutType, reason, detail, expectedNote);
    }
  }
}
