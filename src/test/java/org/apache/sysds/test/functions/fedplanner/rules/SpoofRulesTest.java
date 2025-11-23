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
import static org.junit.Assert.assertTrue;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.Rule;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.junit.Test;

public class SpoofRulesTest {

  private static final ShapeHint UNKNOWN_SHAPE = new ShapeHint(-1, -1, 0);
  private static final Rulesets.SpoofCellwiseRule CELL_RULE = new Rulesets.SpoofCellwiseRule();
  private static final Rulesets.SpoofRowwiseRule ROW_RULE = new Rulesets.SpoofRowwiseRule();
  private static final Rulesets.SpoofMultiAggregateRule MULTI_RULE = new Rulesets.SpoofMultiAggregateRule();
  private static final Rulesets.SpoofOuterProductRule OUTER_RULE = new Rulesets.SpoofOuterProductRule();

  @Test
  public void spoofRulesSmokeMatrix() {
    List<Case> cases = List.of(
        new Case("cell-noagg-row", CELL_RULE, cellSig("spoofCellNoAgg", "NO_AGG"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.FOUT, true, FType.ROW, ReasonCode.OK, null, null),
        new Case("cell-noagg-full", CELL_RULE, cellSig("spoofCellFull", "NO_AGG"),
            List.of(FType.FULL), ExecType.FED, FederatedOutput.FOUT, true, FType.FULL, ReasonCode.OK, null, null),
        new Case("cell-noagg-part", CELL_RULE, cellSig("spoofCellPart", "NO_AGG"),
            List.of(FType.PART), ExecType.FED, FederatedOutput.FOUT, true, FType.PART, ReasonCode.OK, null, null),
        new Case("cell-rowagg-row", CELL_RULE, cellSig("spoofCellRowAgg", "ROW_AGG"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.FOUT, true, FType.ROW, ReasonCode.OK, null, null),
        new Case("cell-colagg-row", CELL_RULE, cellSig("spoofCellColAgg", "COL_AGG"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.LOUT, false, null, ReasonCode.OK, null, null),
        new Case("cell-fullagg-col", CELL_RULE, cellSig("spoofCellFullAgg", "FULL_AGG"),
            List.of(FType.COL), ExecType.FED, FederatedOutput.LOUT, false, null, ReasonCode.OK, "scalar output → LOUT", null),
        new Case("cell-broadcast-only", CELL_RULE, cellSig("spoofCellBroadcast", "NO_AGG"),
            List.of(FType.BROADCAST), ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.BROADCAST_CONSTRAINT, null, null),
        new Case("row-noagg-row", ROW_RULE, rowSig("spoofRowNoAgg", "NO_AGG"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.FOUT, true, FType.ROW, ReasonCode.OK, null, null),
        new Case("row-fullagg-row", ROW_RULE, rowSig("spoofRowFullAgg", "FULL_AGG"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.LOUT, false, null, ReasonCode.OK, null, null),
        new Case("row-noagg-col", ROW_RULE, rowSig("spoofRowMisalign", "NO_AGG"),
            List.of(FType.COL), ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, null, null),
        new Case("multi-row", MULTI_RULE, multiSig("spoofMultiRow"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.LOUT, false, null, ReasonCode.OK, null, null),
        new Case("multi-full", MULTI_RULE, multiSig("spoofMultiFull"),
            List.of(FType.FULL), ExecType.CP, FederatedOutput.LOUT, false, null, ReasonCode.UNSUPPORTED_ALIGNMENT_OR_TOPOLOGY, null, null),
        new Case("outer-cellwise-col", OUTER_RULE, outerSig("spoofOuterCellCol", "CELLWISE_OUTER_PRODUCT"),
            List.of(FType.COL), ExecType.FED, FederatedOutput.FOUT, true, FType.COL, ReasonCode.OK, null, null),
        new Case("outer-cellwise-full", OUTER_RULE, outerSig("spoofOuterCellFull", "CELLWISE_OUTER_PRODUCT"),
            List.of(FType.FULL), ExecType.FED, FederatedOutput.FOUT, true, FType.FULL, ReasonCode.OK, null, null),
        new Case("outer-left-col", OUTER_RULE, outerSig("spoofOuterLeftCol", "LEFT_OUTER_PRODUCT"),
            List.of(FType.COL), ExecType.FED, FederatedOutput.FOUT, true, FType.COL, ReasonCode.OK, null, null),
        new Case("outer-right-row", OUTER_RULE, outerSig("spoofOuterRightRow", "RIGHT_OUTER_PRODUCT"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.FOUT, true, FType.ROW, ReasonCode.OK, null, null),
        new Case("outer-agg-row", OUTER_RULE, outerSig("spoofOuterAgg", "AGG_OUTER_PRODUCT"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.LOUT, false, null, ReasonCode.OK, "scalar output → LOUT", null),
        new Case("outer-left-row-mismatch", OUTER_RULE, outerSig("spoofOuterLeftRow", "LEFT_OUTER_PRODUCT"),
            List.of(FType.ROW), ExecType.FED, FederatedOutput.LOUT, false, null, ReasonCode.OK, null, null),
        guardFailCase(),
        guardUnknownCase());

    for (Case c : cases) {
      OpCaps caps = c.rule.caps(c.sig, c.inFTypes, UNKNOWN_SHAPE);
      String prefix = "case=" + c.name + ": ";
      assertEquals(prefix + "exec", c.exec, caps.exec());
      assertEquals(prefix + "placement", c.placement, caps.placement());
      assertEquals(prefix + "reason", c.reason, caps.reason());
      assertEquals(prefix + "foutEnabled", c.fout, caps.foutEnabled());
      assertEquals(prefix + "foutType", c.foutType, caps.foutFType().orElse(null));
      assertEquals(prefix + "detail", c.detail, caps.detail().orElse(null));
      if (c.expectedNote != null) {
        assertTrue(prefix + "note",
            caps.notes().stream().anyMatch(n -> n.code() == c.expectedNote));
      }
    }
  }

  private static Case guardFailCase() {
    Map<String,String> attrs = cellAttrs("NO_AGG");
    attrs.put("rc.guardOverride", "false");
    return new Case("guard-fail", CELL_RULE, spoofSig("spoofCellGuardFail", attrs),
        List.of(FType.ROW), ExecType.CP, FederatedOutput.LOUT, false, null,
        ReasonCode.REPR_CHANGE_GUARD_FAIL, "override=false", null);
  }

  private static Case guardUnknownCase() {
    Map<String,String> attrs = cellAttrs("NO_AGG");
    return new Case("guard-unknown", CELL_RULE, spoofSig("spoofCellGuardUnknown", attrs),
        List.of(FType.ROW), ExecType.FED, FederatedOutput.FOUT, true, FType.ROW,
        ReasonCode.OK, null, ReasonCode.REPR_CHANGE_GUARD_UNKNOWN);
  }

  private static OpSig cellSig(String opcode, String cellType) {
    return spoofSig(opcode, cellAttrs(cellType));
  }

  private static Map<String,String> cellAttrs(String cellType) {
    Map<String,String> attrs = baseAttrs("CELLWISE");
    attrs.put("spoof.cellType", cellType);
    return attrs;
  }

  private static OpSig rowSig(String opcode, String rowType) {
    Map<String,String> attrs = baseAttrs("ROWWISE");
    attrs.put("spoof.rowType", rowType);
    return spoofSig(opcode, attrs);
  }

  private static OpSig multiSig(String opcode) {
    return spoofSig(opcode, baseAttrs("MULTIAGG"));
  }

  private static OpSig outerSig(String opcode, String outerType) {
    Map<String,String> attrs = baseAttrs("OUTER");
    attrs.put("spoof.outer.type", outerType);
    return spoofSig(opcode, attrs);
  }

  private static Map<String,String> baseAttrs(String template) {
    Map<String,String> attrs = new HashMap<>();
    attrs.put("spoof.template", template);
    return attrs;
  }

  private static OpSig spoofSig(String opcode, Map<String,String> attrs) {
    return OpSig.of(opcode, OpCategory.SPOOF, attrs);
  }

  private static final class Case {
    final String name;
    final Rule rule;
    final OpSig sig;
    final List<FType> inFTypes;
    final ExecType exec;
    final FederatedOutput placement;
    final boolean fout;
    final FType foutType;
    final ReasonCode reason;
    final String detail;
    final ReasonCode expectedNote;

    Case(String name, Rule rule, OpSig sig, List<FType> inFTypes,
        ExecType exec, FederatedOutput placement, boolean fout, FType foutType,
        ReasonCode reason, String detail, ReasonCode expectedNote) {
      this.name = name;
      this.rule = rule;
      this.sig = sig;
      this.inFTypes = (inFTypes == null) ? List.of() : new ArrayList<>(inFTypes);
      this.exec = exec;
      this.placement = placement;
      this.fout = fout;
      this.foutType = foutType;
      this.reason = reason;
      this.detail = detail;
      this.expectedNote = expectedNote;
    }
  }
}
