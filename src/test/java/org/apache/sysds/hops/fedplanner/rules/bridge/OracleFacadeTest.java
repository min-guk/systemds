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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.common.Types.FileFormat;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LeftIndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig.InputKind;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeProof;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.RulesCore.OracleEngine;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.FTypes;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.parser.DataExpression;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Test;

public class OracleFacadeTest {

  private final OracleFacade facade =
      new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());

  @Test
  public void fullSingleHintRequiresEverySelectedFullInputToBeKnownSingle() {
    assertEquals("true", inferredFullSinglePartition(
        binary(federated("left", 1), federated("right", 1)),
        List.of(FType.FULL, FType.FULL)));
    assertEquals("false", inferredFullSinglePartition(
        binary(federated("left", 1), federated("right", 2)),
        List.of(FType.FULL, FType.FULL)));
  }

  @Test
  public void oneKnownFullDoesNotCertifyUnknownFullInEitherInputOrder() {
    assertEquals("UNKNOWN", inferredFullSinglePartition(
        binary(federated("left", 1), matrix("unknownRight", 4, 2)),
        List.of(FType.FULL, FType.FULL)));
    assertEquals("UNKNOWN", inferredFullSinglePartition(
        binary(matrix("unknownLeft", 4, 2), federated("right", 1)),
        List.of(FType.FULL, FType.FULL)));
  }

  @Test
  public void unrelatedGlobalSingleWorkerDoesNotCertifyAnUnknownFullInput() {
    org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.resetFederatedPlannerRunState();
    try {
      org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.registerFedInitVar(
          "unrelated", FType.FULL, "localhost:1234/unrelated|[0,0]-[4,2]|FULL");
      assertEquals("UNKNOWN", inferredFullSinglePartition(
          binary(matrix("localLeft", 4, 2), matrix("localRight", 4, 2)),
          List.of(FType.FULL, FType.FULL)));
      assertEquals("UNKNOWN", inferredFullSinglePartition(
          binary(matrix("rowLeft", 4, 2), matrix("rowRight", 4, 2)),
          List.of(FType.ROW, FType.ROW)));
    }
    finally {
      org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.resetFederatedPlannerRunState();
    }
  }

  @Test
  public void canonicalizesMatrixMultiply() {
    Hop left = matrix("left", 10, 5);
    Hop right = matrix("right", 5, 4);
    AggBinaryOp mm = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
        OpOp2.MULT, AggOp.SUM, left, right);

    OpSig sig = facade.describe(mm);
    assertEquals(Opcodes.MMULT.toString(), sig.opcode());
  }

  @Test
  public void canonicalizesMapLeftIndexing() {
    Hop target = matrix("target", 10, 10);
    Hop rhs = new LiteralOp(42.0);
    Hop rowL = lit(1);
    Hop rowU = lit(1);
    Hop colL = lit(1);
    Hop colU = lit(1);
    LeftIndexingOp lix = new LeftIndexingOp(
        "lix", DataType.MATRIX, ValueType.FP64,
        target, rhs, rowL, rowU, colL, colU, false, false);

    OpSig sig = facade.describe(lix);
    assertEquals(Opcodes.MAPLEFTINDEX.toString(), sig.opcode());
  }

  @Test
  public void scalarRightIndexExecutesFederatedButMaterializesLocally() {
    Hop input = matrix("input", 10, 10);
    IndexingOp scalar = new IndexingOp("scalar", DataType.SCALAR, ValueType.FP64,
        input, lit(1), lit(1), lit(1), lit(1), true, true);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(scalar, Arrays.asList(FType.FULL, null, null, null, null), null);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals("a scalar has no FederationMap and cannot be a federated output value",
        FederatedOutput.LOUT, evidence.caps().placement());
    assertEquals(Optional.empty(), evidence.caps().foutFType());
    assertTrue(evidence.caps().detail().orElse("").contains("scalar-output-materialized-local"));
    assertTrue("a scalar has no output FType profile",
        facade.inferProfile(scalar,
            List.of(List.of(FType.FULL), List.of(), List.of(), List.of(), List.of()), null)
            .outputs().isEmpty());
  }

  @Test
  public void capturesReshapeByRowAttribute() {
    Hop data = matrix("reshape", 4, 4);
    List<Hop> inputs = new ArrayList<>();
    inputs.add(data);
    inputs.add(lit(2));
    inputs.add(lit(8));
    inputs.add(lit(-1));
    inputs.add(new LiteralOp(true));
    ReorgOp reshape = new ReorgOp(
        "reshape", DataType.MATRIX, ValueType.FP64, ReOrgOp.RESHAPE, inputs);

    OpSig sig = facade.describe(reshape);
    assertEquals("true", sig.attrs().get("reshape.byrow"));
  }

  @Test
  public void capturesFrameMapMargin() {
    Hop frame = matrix("frame", 6, 3);
    TernaryOp map = new TernaryOp(
        "map", DataType.FRAME, ValueType.FP64, OpOp3.MAP,
        frame, new LiteralOp("fun"), lit(2));

    OpSig sig = facade.describe(map);
    assertEquals("2", sig.attrs().get("map.margin"));
  }

  @Test
  public void detectsFederatedWriteTargets() {
    Hop in = matrix("input", 10, 10);
    DataOp write = new DataOp("write", DataType.MATRIX, ValueType.FP64,
        in, OpOpData.PERSISTENTWRITE, "out");
    write.setFileFormat(FileFormat.FEDERATED);

    OpSig sig = facade.describe(write);
    assertEquals("true", sig.attrs().get("var.write.federated"));
  }

  @Test
  public void facadeMatchesManualRuleDecision() {
    Hop left = matrix("X", 5, 5);
    Hop right = matrix("Y", 5, 5);
    BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);

    List<FTypes.FType> runtimeTypes = List.of(FTypes.FType.ROW, FTypes.FType.ROW);
    OpCaps viaFacade = facade.decide(plus, runtimeTypes);

    Map<String,String> attrs = Map.of("rc.guardDefaultIfUnknown", "allow");
    OpSig manualSig = OpSig.of(
        OpOp2.PLUS.toString(),
        OpCategory.BINARY_EWISE,
        attrs,
        InputKind.MATRIX,
        InputKind.MATRIX);
    ShapeHint hint = new ShapeHint(plus.getDim1(), plus.getDim2(), plus.getBlocksize());
    OracleEngine legacy = new RulesCore.OracleEngine(RulesCore.RulesModule.createDefaultRegistry());
    OpCaps viaManual = legacy.decide(manualSig, List.of(FType.ROW, FType.ROW), hint);

    assertCapsEquivalent(viaFacade, viaManual);
  }

  @Test
  public void binaryOtherMatrixScalarBridgePreservesOtherForBothOrders() {
    Hop matrix = matrix("X", 4, 4);
    BinaryOp leftOther = new BinaryOp("leftOther", DataType.MATRIX, ValueType.FP64,
        OpOp2.PLUS, matrix, new LiteralOp(1.0));
    OracleFacade.DecisionEvidence leftEvidence =
        facade.decideWithEvidence(leftOther, Arrays.asList(FTypes.FType.OTHER, null), null);
    assertEquals(ExecType.FED, leftEvidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, leftEvidence.caps().placement());
    assertEquals(Optional.of(FType.OTHER), leftEvidence.caps().foutFType());
    assertEquals(ReasonCode.OK, leftEvidence.caps().reason());

    Hop rightMatrix = matrix("Y", 4, 4);
    BinaryOp rightOther = new BinaryOp("rightOther", DataType.MATRIX, ValueType.FP64,
        OpOp2.PLUS, new LiteralOp(1.0), rightMatrix);
    OracleFacade.DecisionEvidence rightEvidence =
        facade.decideWithEvidence(rightOther, Arrays.asList(null, FTypes.FType.OTHER), null);
    assertEquals(ExecType.FED, rightEvidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, rightEvidence.caps().placement());
    assertEquals(Optional.of(FType.OTHER), rightEvidence.caps().foutFType());
    assertEquals(ReasonCode.OK, rightEvidence.caps().reason());
  }

  @Test
  public void binaryOtherMatrixBridgeRejectsNonScalarCounterpart() {
    Hop matrix = matrix("X", 4, 4);
    Hop frame = frame("F", 4, 4);
    BinaryOp plus = new BinaryOp("otherFrame", DataType.MATRIX, ValueType.FP64,
        OpOp2.PLUS, matrix, frame);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(plus, Arrays.asList(FTypes.FType.OTHER, null), null);

    assertFalse("OTHER + FRAME must not be admitted as BinaryMatrixScalarFEDInstruction",
        evidence.caps().exec() == ExecType.FED
            && evidence.caps().placement() == FederatedOutput.FOUT
            && evidence.caps().foutFType().equals(Optional.of(FType.OTHER)));
    assertEquals(ExecType.CP, evidence.caps().exec());
    assertEquals(ReasonCode.NO_FED_INPUT, evidence.caps().reason());
  }

  @Test
  public void naryFederatedExecutionRejectsMultipleLocalMatrixInputs() {
    Hop federated = matrix("federated", 4, 4);
    Hop localLeft = matrix("localLeft", 4, 4);
    Hop localRight = matrix("localRight", 4, 4);
    NaryOp product = new NaryOp("product", DataType.MATRIX, ValueType.FP64,
        OpOpN.MULT, federated, localLeft, localRight);

    OracleFacade.DecisionEvidence evidence = facade.decideWithEvidence(product,
        Arrays.asList(FType.ROW, null, null), null);

    assertEquals("BuiltinNaryFEDInstruction can broadcast at most one local matrix",
        ExecType.CP, evidence.caps().exec());
    assertEquals(FederatedOutput.LOUT, evidence.caps().placement());
    assertEquals(ReasonCode.BROADCAST_CONSTRAINT, evidence.caps().reason());
  }

  @Test
  public void naryFederatedExecutionAllowsOneLocalMatrixAndLocalScalars() {
    Hop federated = matrix("federated", 4, 4);
    Hop local = matrix("local", 4, 4);
    NaryOp product = new NaryOp("product", DataType.MATRIX, ValueType.FP64,
        OpOpN.MULT, federated, local, new LiteralOp(2.0), new LiteralOp(3.0));

    OracleFacade.DecisionEvidence evidence = facade.decideWithEvidence(product,
        Arrays.asList(FType.ROW, null, null, null), null);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(Optional.of(FType.ROW), evidence.caps().foutFType());
    assertEquals(ReasonCode.OK, evidence.caps().reason());
  }

  @Test
  public void transientWriteProofIgnoresObservationOnlyShapeLogging() {
    Hop input = matrix("input", -1, -1);
    input.setBlocksize(-1);
    DataOp write = new DataOp("write", DataType.MATRIX, ValueType.FP64,
        input, OpOpData.TRANSIENTWRITE, "A");
    write.setDim1(-1);
    write.setDim2(-1);
    write.setBlocksize(-1);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(write, List.of(FType.ROW), null);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(Optional.of(FType.ROW), evidence.caps().foutFType());
    assertEquals(new ShapeProof(Map.of(), Set.of(), Set.of()), evidence.shapeProof());
  }

  @Test
  public void explicitHintMergeDoesNotCreateShapeRequirementsForTransientWrite() {
    Hop input = matrix("input", -1, -1);
    DataOp write = new DataOp("write", DataType.MATRIX, ValueType.FP64,
        input, OpOpData.TRANSIENTWRITE, "A");
    ShapeHint hint = new ShapeHint(-1, -1, -1);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(write, List.of(FType.ROW), hint);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(new ShapeProof(Map.of(), Set.of(), Set.of()), evidence.shapeProof());
  }

  @Test
  public void explicitAlignedBinaryHintDoesNotRequireSingleFullPartition() {
    BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS,
        matrix("left", -1, -1), matrix("right", -1, -1));
    ShapeHint hint = new ShapeHint(4, 2, 1000, Optional.empty(), 4, 2, 4, 2);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(plus, List.of(FType.ROW, FType.ROW), hint);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(Optional.of(FType.ROW), evidence.caps().foutFType());
    assertEquals(Set.of(), evidence.shapeProof().missingRequiredFacts());
    assertFalse(evidence.shapeProof().requiredFacts().contains("fullSinglePartition"));
    assertFalse("the rule still certifies its actual shape requirements",
        evidence.shapeProof().requiredFacts().isEmpty());
  }

  @Test
  public void explicitUnknownFullHintCannotReadAnotherProgramsVariableRegistry() {
    org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.resetFederatedPlannerRunState();
    try {
      org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.registerFedInitVar(
          "B", FType.FULL, "localhost:1234/old|[0,0]-[4,2]|FULL");
      BinaryOp append = new BinaryOp("append", DataType.MATRIX, ValueType.FP64, OpOp2.CBIND,
          matrix("B", 4, 2), matrix("C", 4, 2));
      OracleFacade.DecisionEvidence evidence = facade.decideWithEvidence(append,
          List.of(FType.FULL, FType.FULL),
          new ShapeHint(4, 4, 1000, Optional.empty(), 4, 2, 4, 2));
      assertFalse("Unknown occurrence evidence is not a single-partition certificate",
          evidence.caps().exec() == ExecType.FED && evidence.caps().placement() == FederatedOutput.FOUT);
      assertEquals("UNKNOWN", evidence.shapeProof().consultedFacts().get("fullSinglePartition"));
    }
    finally {
      org.apache.sysds.hops.fedplanner.fedCostBased.FederatedPlannerUtils.resetFederatedPlannerRunState();
    }
  }

  @Test
  public void ruleConsultationStillRecordsAnUnknownSinglePartitionRequirement() {
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
    registry.register(new RulesCore.BaseRule() {
      @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
      @Override public Set<String> opcodes() { return Set.of("+"); }
      @Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
        hint.fullSinglePartition();
        return super.caps(sig, inputs, hint);
      }
    });
    BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS,
        matrix("left", 4, 2), matrix("right", 4, 2));

    OracleFacade.DecisionEvidence evidence = new OracleFacade(registry).decideWithEvidence(
        plus, List.of(FType.ROW, FType.ROW), new ShapeHint(4, 2, 1000));

    assertEquals(new ShapeProof(Map.of("fullSinglePartition", "UNKNOWN"),
        Set.of("fullSinglePartition"), Set.of("fullSinglePartition")), evidence.shapeProof());
  }

  @Test
  public void binaryProofIncludesOnlyRuleConsultedMissingShapeFacts() {
    Hop left = matrix("left", 4, 7);
    Hop right = matrix("right", 4, 7);
    BinaryOp plus = new BinaryOp(
        "plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);
    plus.setDim1(-1);
    plus.setDim2(7);
    plus.setBlocksize(-1);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(plus, List.of(FType.ROW, FType.ROW), null);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(Optional.of(FType.ROW), evidence.caps().foutFType());
    assertEquals(Set.of("rows"), evidence.shapeProof().missingRequiredFacts());
  }

  @Test
  public void binaryFederatedMatrixWithLocalMatrixDoesNotRequireStaticOutputShape() {
    Hop left = matrix("left", -1, -1);
    Hop right = matrix("right", -1, -1);
    BinaryOp minus = new BinaryOp(
        "minus", DataType.MATRIX, ValueType.FP64, OpOp2.MINUS, left, right);
    minus.setDim1(-1);
    minus.setDim2(-1);
    minus.setBlocksize(-1);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(minus, Arrays.asList(FType.ROW, null), null);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(Optional.of(FType.ROW), evidence.caps().foutFType());
    assertEquals("runtime broadcast/broadcastSliced does not require compile-time dimensions",
        Set.of(), evidence.shapeProof().missingRequiredFacts());
  }

  @Test
  public void binaryFullMatrixWithLocalMatrixDoesNotRequireEncodedWidth() {
    Hop full = matrix("encoded", 100_000, -1);
    Hop local = matrix("bound", 1, -1);
    BinaryOp less = new BinaryOp(
        "less", DataType.MATRIX, ValueType.FP64, OpOp2.LESS, full, local);
    less.setDim1(100_000);
    less.setDim2(-1);
    less.setBlocksize(1000);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(less, Arrays.asList(FType.FULL, null), null);

    assertEquals(ExecType.FED, evidence.caps().exec());
    assertEquals(FederatedOutput.FOUT, evidence.caps().placement());
    assertEquals(Optional.of(FType.FULL), evidence.caps().foutFType());
    assertEquals("FULL plus a local operand is independent of ROW/COL shape proof",
        Set.of(), evidence.shapeProof().missingRequiredFacts());
  }

  @Test
  public void binaryFullDoesNotAlignWithRowPartition() {
    Hop row = matrix("row", 100_000, -1);
    Hop full = matrix("full", 100_000, -1);
    BinaryOp less = new BinaryOp(
        "less", DataType.MATRIX, ValueType.FP64, OpOp2.LESS, row, full);
    less.setDim1(100_000);
    less.setDim2(-1);
    less.setBlocksize(1000);

    OracleFacade.DecisionEvidence evidence =
        facade.decideWithEvidence(less, List.of(FType.ROW, FType.FULL), null);

    assertEquals(ExecType.CP, evidence.caps().exec());
    assertEquals(FederatedOutput.LOUT, evidence.caps().placement());
    assertEquals(ReasonCode.UNSUPPORTED_ALIGNMENT, evidence.caps().reason());
  }

  private static void assertCapsEquivalent(OpCaps actual, OpCaps expected) {
    assertEquals(expected.exec(), actual.exec());
    assertEquals(expected.placement(), actual.placement());
    assertEquals(expected.reason(), actual.reason());
    assertEquals(expected.foutEnabled(), actual.foutEnabled());
    assertEquals(expected.foutFType(), actual.foutFType());
    assertEquals(expected.detail(), actual.detail());
    assertEquals(expected.notes().size(), actual.notes().size());
    for (int i = 0; i < actual.notes().size(); i++) {
      assertEquals(expected.notes().get(i).code(), actual.notes().get(i).code());
    }
  }

  private static String inferredFullSinglePartition(Hop hop, List<FType> inputTypes) {
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
    registry.register(new RulesCore.BaseRule() {
      @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
      @Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
      @Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
        hint.fullSinglePartition();
        return super.caps(sig, inputs, hint);
      }
    });
    OracleFacade.DecisionEvidence evidence =
        new OracleFacade(registry).decideWithEvidence(hop, inputTypes, null);
    return evidence.shapeProof().consultedFacts().get("fullSinglePartition");
  }

  private static BinaryOp binary(Hop left, Hop right) {
    return new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS, left, right);
  }

  private static DataOp federated(String name, int rangeCount) {
    Hop[] addresses = new Hop[rangeCount];
    Hop[] ranges = new Hop[rangeCount * 2];
    for (int index = 0; index < rangeCount; index++) {
      addresses[index] = new LiteralOp("localhost:" + (1234 + index) + '/' + name);
      ranges[2 * index] = list(new LiteralOp(0L), new LiteralOp(0L));
      ranges[2 * index + 1] = list(new LiteralOp(4L), new LiteralOp(2L));
    }
    HashMap<String,Hop> parameters = new HashMap<>();
    parameters.put(DataExpression.FED_ADDRESSES, list(addresses));
    parameters.put(DataExpression.FED_RANGES, list(ranges));
    return new DataOp(name, DataType.MATRIX, ValueType.FP64, OpOpData.FEDERATED, parameters);
  }

  private static NaryOp list(Hop... inputs) {
    return new NaryOp("list", DataType.LIST, ValueType.UNKNOWN, OpOpN.LIST, inputs);
  }

  private static DataOp matrix(String name, long rows, long cols) {
    return new DataOp(name, DataType.MATRIX, ValueType.FP64,
        OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
  }

  private static DataOp frame(String name, long rows, long cols) {
    return new DataOp(name, DataType.FRAME, ValueType.STRING,
        OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
  }

  private static LiteralOp lit(long v) {
    return new LiteralOp(v);
  }
}
