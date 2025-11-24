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
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.junit.Test;

public class VariableRulesTest {
  private static final String CAST_BROADCAST_DETAIL = "broadcast input not supported by CastFEDInstruction";
  private static final String FED_WRITE_DETAIL = "federated write target";

  private final Rule castRule = new Rulesets.CastRule();
  private final Rule writeRule = new Rulesets.VariableWriteRule();

  @Test
  public void variableInstructionSmokeTable() {
    List<Scenario> scenarios = List.of(
        // Cast scenarios
        Scenario.of("castasframe-row",
            castRule,
            Opcodes.CAST_AS_FRAME_VAR.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[] {InputKind.MATRIX},
            List.of(FType.ROW),
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.ROW,
            ReasonCode.OK,
            null),
        Scenario.of("castasmatrix-col",
            castRule,
            Opcodes.CAST_AS_MATRIX.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[] {InputKind.FRAME},
            List.of(FType.COL),
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.COL,
            ReasonCode.OK,
            null),
        Scenario.of("castasframe-part",
            castRule,
            Opcodes.CAST_AS_FRAME_VAR.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[] {InputKind.MATRIX},
            List.of(FType.PART),
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.PART,
            ReasonCode.OK,
            null),
        Scenario.of("castasmatrix-full",
            castRule,
            Opcodes.CAST_AS_MATRIX.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[] {InputKind.FRAME},
            List.of(FType.FULL),
            ExecType.FED,
            FederatedOutput.FOUT,
            true,
            FType.FULL,
            ReasonCode.OK,
            null),
        Scenario.of("castasframe-broadcast",
            castRule,
            Opcodes.CAST_AS_FRAME_VAR.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[] {InputKind.MATRIX},
            List.of(FType.BROADCAST),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.BROADCAST_CONSTRAINT,
            CAST_BROADCAST_DETAIL),
        Scenario.of("castasmatrix-local",
            castRule,
            Opcodes.CAST_AS_MATRIX.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[] {InputKind.FRAME},
            Arrays.asList((FType) null),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.NO_FED_INPUT,
            null),
        Scenario.of("castasframe-missing-arity",
            castRule,
            Opcodes.CAST_AS_FRAME_VAR.toString(),
            OpCategory.VARIABLE_CAST,
            Map.of(),
            new InputKind[0],
            List.of(),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.ARITY_MISMATCH,
            null),
        // Write scenarios
        Scenario.of("write-row-fed-target",
            writeRule,
            Opcodes.WRITE.toString(),
            OpCategory.OTHER,
            Map.of("var.write.federated", "true"),
            new InputKind[] {InputKind.MATRIX, InputKind.SCALAR, InputKind.SCALAR},
            Arrays.asList(FType.ROW, null, null),
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.OK,
            FED_WRITE_DETAIL),
        Scenario.of("write-col-fed-target",
            writeRule,
            Opcodes.WRITE.toString(),
            OpCategory.OTHER,
            Map.of("var.write.federated", "true"),
            new InputKind[] {InputKind.MATRIX, InputKind.SCALAR, InputKind.SCALAR},
            Arrays.asList(FType.COL, null, null),
            ExecType.FED,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.OK,
            FED_WRITE_DETAIL),
        Scenario.of("write-broadcast-input",
            writeRule,
            Opcodes.WRITE.toString(),
            OpCategory.OTHER,
            Map.of("var.write.federated", "true"),
            new InputKind[] {InputKind.MATRIX, InputKind.SCALAR, InputKind.SCALAR},
            Arrays.asList(FType.BROADCAST, null, null),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.BROADCAST_CONSTRAINT,
            null),
        Scenario.of("write-local-input",
            writeRule,
            Opcodes.WRITE.toString(),
            OpCategory.OTHER,
            Map.of("var.write.federated", "true"),
            new InputKind[] {InputKind.MATRIX, InputKind.SCALAR, InputKind.SCALAR},
            Arrays.asList(null, null, null),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.NO_FED_INPUT,
            null),
        Scenario.of("write-nonfed-target",
            writeRule,
            Opcodes.WRITE.toString(),
            OpCategory.OTHER,
            Map.of("var.write.federated", "false"),
            new InputKind[] {InputKind.MATRIX, InputKind.SCALAR, InputKind.SCALAR},
            Arrays.asList(FType.ROW, null, null),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.NOT_IMPLEMENTED,
            null),
        Scenario.of("write-missing-arity",
            writeRule,
            Opcodes.WRITE.toString(),
            OpCategory.OTHER,
            Map.of("var.write.federated", "true"),
            new InputKind[0],
            List.of(),
            ExecType.CP,
            FederatedOutput.LOUT,
            false,
            null,
            ReasonCode.ARITY_MISMATCH,
            null));

    for (Scenario scenario : scenarios) {
      OpSig sig = sig(scenario.opcode, scenario.category, scenario.attrs, scenario.inputKinds);
      OpCaps caps = scenario.rule.caps(sig, scenario.inFTypes, null);
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
        assertEquals(msg, scenario.detail, caps.detail().orElse(null));
      }
    }
  }

  private static OpSig sig(String opcode, OpCategory category, Map<String,String> attrs, InputKind[] kinds) {
    if (kinds == null)
      kinds = new InputKind[0];
    return OpSig.of(opcode, category, attrs, Arrays.copyOf(kinds, kinds.length));
  }

  private static final class Scenario {
    final String name;
    final Rule rule;
    final String opcode;
    final OpCategory category;
    final Map<String,String> attrs;
    final InputKind[] inputKinds;
    final List<FType> inFTypes;
    final ExecType exec;
    final FederatedOutput placement;
    final boolean fout;
    final FType foutType;
    final ReasonCode reason;
    final String detail;

    private Scenario(String name, Rule rule, String opcode, OpCategory category, Map<String,String> attrs,
        InputKind[] inputKinds, List<FType> inFTypes, ExecType exec, FederatedOutput placement,
        boolean fout, FType foutType, ReasonCode reason, String detail) {
      this.name = name;
      this.rule = rule;
      this.opcode = opcode;
      this.category = category;
      this.attrs = Map.copyOf(attrs);
      this.inputKinds = Arrays.copyOf(inputKinds, inputKinds.length);
      this.inFTypes = List.copyOf(inFTypes);
      this.exec = exec;
      this.placement = placement;
      this.fout = fout;
      this.foutType = foutType;
      this.reason = reason;
      this.detail = detail;
    }

    static Scenario of(String name, Rule rule, String opcode, OpCategory category, Map<String,String> attrs,
        InputKind[] inputKinds, List<FType> inFTypes, ExecType exec, FederatedOutput placement,
        boolean fout, FType foutType, ReasonCode reason, String detail) {
      return new Scenario(name, rule, opcode, category, attrs, inputKinds,
          inFTypes, exec, placement, fout, foutType, reason, detail);
    }
  }
}
