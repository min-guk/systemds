/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.rules.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.sysds.common.Types.AggOp;
import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.OpOpN;
import org.apache.sysds.common.Types.ReOrgOp;
import org.apache.sysds.common.Opcodes;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.AggBinaryOp;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.NaryOp;
import org.apache.sysds.hops.ReorgOp;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade.DecisionEvidence;
import org.junit.Test;

public class OracleDecisionDependenciesTest {
  private static final List<FType> DOMAIN = Arrays.asList(null, FType.ROW, FType.COL,
      FType.FULL, FType.PART, FType.BROADCAST);

  @Test
  public void unaryRelationExactlyMatchesEveryForwardDecision() {
    UnaryOp exp = new UnaryOp("exp", DataType.MATRIX, ValueType.FP64,
        OpOp1.EXP, matrix("X", 8, 4));
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(exp);
    var dependencies = prepared.decisionDependencies().orElseThrow();
    assertEquals(List.of(0), List.copyOf(dependencies.enumeratedPositions()));
    assertTrue(dependencies.shapeIndependent());
    var relation = prepared.prepareExecutionRelation(List.of(DOMAIN)).orElseThrow();
    for(FType input : DOMAIN)
      assertEvidence(prepared.decideWithEvidence(Arrays.asList(input), unknown()),
          relation.evidenceFor(Arrays.asList(input)));
  }

  @Test
  public void newAllDependentUnaryDeclarationFallsBackWhileLegacyUnaryStillBuildsRelation() {
    UnaryOp cumulative = new UnaryOp("cumulative", DataType.MATRIX, ValueType.FP64,
        OpOp1.CUMSUM, matrix("CX", 8, 4));
    OracleFacade.PreparedDecision newDeclaration = facade().prepareDecision(cumulative);
    assertTrue(newDeclaration.decisionDependencies().orElseThrow().shapeIndependent());
    assertTrue("a new declaration that enumerates its only varying axis has no relation gain",
        newDeclaration.prepareExecutionRelation(List.of(DOMAIN)).isEmpty());

    UnaryOp exp = new UnaryOp("legacy-exp", DataType.MATRIX, ValueType.FP64,
        OpOp1.EXP, matrix("LX", 8, 4));
    OracleFacade.PreparedDecision legacy = facade().prepareDecision(exp);
    var relation = legacy.prepareExecutionRelation(List.of(DOMAIN), ignored -> {
      throw new AssertionError("shape-independent relation must not request an exact shape");
    }).orElseThrow();
    assertEquals("legacy ShapeIndependentDecision behavior remains available", DOMAIN.size(),
        relation.regions().size());
    for(FType input : DOMAIN)
      assertEvidence(legacy.decideWithEvidence(Arrays.asList(input), unknown()),
          relation.evidenceFor(Arrays.asList(input)));
  }

  @Test
  public void realMmultRoutePreservesConditionalShapeProofAndWholeCaps() {
    Hop left = matrix("L", 8, 4);
    Hop right = matrix("R", 4, 3);
    AggBinaryOp mm = new AggBinaryOp("mm", DataType.MATRIX, ValueType.FP64,
        OpOp2.MULT, AggOp.SUM, left, right);
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(mm);
    var dependencies = prepared.decisionDependencies().orElseThrow();
    assertEquals(List.of(0, 1), List.copyOf(dependencies.enumeratedPositions()));
    assertEquals(java.util.Set.of("fullSinglePartition"), dependencies.allowedShapeFacts());
    assertTrue("legacy overload must reject shape-qualified rules",
        prepared.prepareExecutionRelation(List.of(DOMAIN, DOMAIN)).isEmpty());

    Function<List<FType>,ShapeHint> exact = tuple -> new ShapeHint(8, 3, 1000,
        java.util.Optional.of(tuple.contains(FType.FULL)), 8, 4, 4, 3);
    assertTrue("two fully dependent MM axes retain streaming enumeration",
        prepared.prepareExecutionRelation(List.of(DOMAIN, DOMAIN), exact).isEmpty());
    for(FType a : DOMAIN)
      for(FType b : DOMAIN) {
        List<FType> tuple = Arrays.asList(a, b);
        DecisionEvidence evidence = prepared.decideWithEvidence(tuple, exact.apply(tuple));
        assertTrue(dependencies.allowedShapeFacts().containsAll(
            evidence.shapeProof().consultedFacts().keySet()));
      }
  }

  @Test
  public void binaryElementwiseRelationMatchesFixedSeedShapeCases() {
    BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
        OpOp2.PLUS, matrix("A", 8, 4), matrix("B", 8, 4));
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(plus);
    Function<List<FType>,ShapeHint> exact = tuple -> {
      int salt = java.util.Objects.hashCode(tuple.get(0))
          ^ 31 * java.util.Objects.hashCode(tuple.get(1));
      long rowsB = (salt & 1) == 0 ? 8 : 1;
      return new ShapeHint(8, 4, 1000, java.util.Optional.of(tuple.contains(FType.FULL)),
          8, 4, rowsB, 4);
    };
    assertTrue("fully dependent binary axes retain streaming enumeration",
        prepared.prepareExecutionRelation(List.of(DOMAIN, DOMAIN), exact).isEmpty());
    for(FType a : DOMAIN)
      for(FType b : DOMAIN) {
        List<FType> tuple = Arrays.asList(a, b);
        DecisionEvidence evidence = prepared.decideWithEvidence(tuple, exact.apply(tuple));
        assertTrue(prepared.decisionDependencies().orElseThrow().allowedShapeFacts().containsAll(
            evidence.shapeProof().consultedFacts().keySet()));
      }
  }

  @Test
  public void shapeProviderReceivesExactRepresentativeAndFreshProofState() {
    AggBinaryOp mm = new AggBinaryOp("mm-fresh", DataType.MATRIX, ValueType.FP64,
        OpOp2.MULT, AggOp.SUM, matrix("FL", 8, 4), matrix("FR", 4, 3));
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(mm);
    List<List<FType>> seen = new ArrayList<>();
    assertTrue(prepared.prepareExecutionRelation(List.of(
        List.of(FType.FULL, FType.ROW), Arrays.asList((FType)null)), tuple -> {
          seen.add(new ArrayList<>(tuple));
          return new ShapeHint(8, 3, 1000, java.util.Optional.of(tuple.get(0) == FType.FULL),
              8, 4, 4, 3);
        }).isEmpty());
    assertEquals("no-gain fallback must not evaluate the shape provider", 0, seen.size());
  }

  @Test
  public void shapeSelectorsRetainRawValuesThatMapToTheSameOracleInput() {
    BinaryOp plus = new BinaryOp("plus-scalar", DataType.MATRIX, ValueType.FP64,
        OpOp2.PLUS, matrix("SX", 8, 4), new LiteralOp(1D));
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(plus);
    List<List<FType>> seen = new ArrayList<>();
    assertTrue(prepared.prepareExecutionRelation(List.of(
        List.of(FType.ROW), List.of(FType.ROW, FType.COL)), tuple -> {
          seen.add(new ArrayList<>(tuple));
          return new ShapeHint(8, 4, 1000, java.util.Optional.of(false),
              8, 4, tuple.get(1) == FType.ROW ? 1 : 8, 1);
        }).isEmpty());
    assertEquals(0, seen.size());
  }

  @Test
  public void highArityAllDependentRuleFallsBackBeforeAllocatingCartesianSeeds() {
    Hop[] inputs = new Hop[12];
    for(int index = 0; index < inputs.length; index++)
      inputs[index] = matrix("N" + index, 8, 4);
    NaryOp nary = new NaryOp("nary-product", DataType.MATRIX, ValueType.FP64,
        OpOpN.MULT, inputs);
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(nary);
    List<List<FType>> domains = new ArrayList<>();
    for(int index = 0; index < inputs.length; index++)
      domains.add(List.of(FType.ROW, FType.COL, FType.FULL));
    java.util.concurrent.atomic.AtomicInteger shapeRequests =
        new java.util.concurrent.atomic.AtomicInteger();

    assertTrue("531,441 eager region seeds must fall back to streaming enumeration",
        prepared.prepareExecutionRelation(domains, tuple -> {
          shapeRequests.incrementAndGet();
          return unknown();
        }).isEmpty());
    assertEquals("fallback is decided from domain cardinality before shape evaluation",
        0, shapeRequests.get());
  }

  @Test
  public void supportedPatternVisitorMatchesEveryExactNaryDecision() {
    Hop[] inputs = new Hop[] {matrix("A", 8, 4), matrix("B", 8, 4), matrix("C", 8, 4)};
    NaryOp nary = new NaryOp("nary-product", DataType.MATRIX, ValueType.FP64,
        OpOpN.MULT, inputs);
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(nary);
    List<FType> domain = Arrays.asList(null, FType.ROW, FType.COL, FType.BROADCAST);
    List<List<FType>> domains = List.of(domain, domain, domain);
    Map<List<FType>,DecisionEvidence> visited = new LinkedHashMap<>();
    var diagnostics = prepared.visitSupportedPatterns(domains, ignored -> unknown(),
        (ordinals, tuple, evidence, failure) -> {
          assertTrue(String.valueOf(failure), failure == null);
          visited.put(tuple, evidence);
          return true;
        });

    int exactFed = 0;
    for(FType a : domain)
      for(FType b : domain)
        for(FType c : domain) {
          List<FType> tuple = Arrays.asList(a, b, c);
          DecisionEvidence exact = prepared.decideWithEvidence(tuple, unknown());
          if(exact.caps().exec() == org.apache.sysds.common.Types.ExecType.FED) {
            exactFed++;
            assertTrue("supported FED tuple must be visited: " + tuple, visited.containsKey(tuple));
            assertEvidence(exact, visited.get(tuple));
          }
          else {
            assertFalse("proved non-FED tuple must not reach a leaf: " + tuple,
                visited.containsKey(tuple));
            assertFalse("pruning must not hide RULE_ERROR coverage",
                exact.caps().reason() == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.RULE_ERROR);
            assertFalse("pruning must not hide NOT_IMPLEMENTED coverage",
                exact.caps().reason()
                    == org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.NOT_IMPLEMENTED);
            assertTrue("pruning must not hide required shape facts",
                exact.shapeProof().missingRequiredFacts().isEmpty());
          }
        }
    assertEquals(exactFed, visited.size());
    assertTrue("known impossible prefixes are pruned", diagnostics.prunedPrefixes() > 0);
    assertTrue("the visitor never materializes the 4^arity leaf set",
        diagnostics.visitedLeaves() < 64);
  }

  @Test
  public void determinantOnlyRuleCannotPruneNotImplementedCoverage() {
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
    registry.register(new RulesCore.BaseRule() {
      @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
      @Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
      @Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies>
          decisionDependencies(OpSig sig) {
        return java.util.Optional.of(
            new org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies(
                Set.of(0), Set.of(), Set.of()));
      }
    });
    BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64,
        OpOp2.PLUS, matrix("A", 8, 4), matrix("B", 8, 4));
    OracleFacade.PreparedDecision prepared = new OracleFacade(registry).prepareDecision(plus);
    List<FType> domain = Arrays.asList(null, FType.ROW);
    Map<List<FType>,DecisionEvidence> visited = new LinkedHashMap<>();

    var diagnostics = prepared.visitSupportedPatterns(List.of(domain, domain),
        ignored -> unknown(), (ordinals, tuple, evidence, failure) -> {
          assertTrue(String.valueOf(failure), failure == null);
          visited.put(tuple, evidence);
          return true;
        });

    assertEquals("one obligation per determinant region preserves NOT_IMPLEMENTED coverage",
        2, visited.size());
    assertEquals(0, diagnostics.prunedPrefixes());
    assertEquals(2, diagnostics.visitedLeaves());
    assertEquals(2, diagnostics.determinantRegions());
    assertEquals(2, diagnostics.oracleEvaluations());
    assertEquals(2, diagnostics.coverageObligations());
    for(FType left : domain)
      for(FType right : List.of(FType.ROW)) {
        assertEquals(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.NOT_IMPLEMENTED,
            visited.get(Arrays.asList(left, right)).caps().reason());
        assertEvidence(prepared.decideWithEvidence(Arrays.asList(left, right), unknown()),
            visited.get(Arrays.asList(left, right)));
      }
  }

  @Test
  public void determinantRegionsEvaluateOnceAndExpandOnlySupportedOrdinals() {
    RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
    registry.register(new RulesCore.BaseRule() {
      @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
      @Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
      @Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies>
          decisionDependencies(OpSig sig) {
        return java.util.Optional.of(
            new org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies(
                Set.of(0), Set.of(), Set.of()));
      }
      @Override public OpCaps caps(OpSig sig,List<FType> inputs,ShapeHint hint) {
        boolean supported=inputs.get(0)==FType.ROW;
        return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
            .exec(supported ? org.apache.sysds.common.Types.ExecType.FED
                : org.apache.sysds.common.Types.ExecType.CP)
            .placement(supported
                ? org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT
                : org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT)
            .fout(supported,supported ? FType.ROW : null)
            .reason(supported
                ? org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.OK
                : org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.NO_FED_INPUT)
            .build();
      }
    });
    BinaryOp plus = new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,
        OpOp2.PLUS,matrix("A",8,4),matrix("B",8,4));
    OracleFacade.PreparedDecision prepared=new OracleFacade(registry).prepareDecision(plus);
    List<FType> domain=Arrays.asList(null,FType.ROW);
    List<List<FType>> visited=new ArrayList<>();

    var diagnostics=prepared.visitSupportedPatterns(List.of(domain,domain),ignored -> unknown(),
        (ordinals,tuple,evidence,failure) -> {
          assertTrue(String.valueOf(failure),failure==null);
          visited.add(tuple);
          return true;
        });

    assertEquals(List.of(Arrays.asList(FType.ROW,null),List.of(FType.ROW,FType.ROW)),visited);
    assertEquals(2,diagnostics.determinantRegions());
    assertEquals(2,diagnostics.oracleEvaluations());
    assertEquals(1,diagnostics.prunedPrefixes());
    assertEquals(1,diagnostics.evidenceReuses());
  }

  @Test
  public void determinantReuseRejectsUndeclaredShapeFactsAsCoverageObligations() {
    RulesCore.RuleRegistry registry=new RulesCore.RuleRegistry();
    registry.register(new RulesCore.BaseRule() {
      @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
      @Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
      @Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies>
          decisionDependencies(OpSig sig) {
        return java.util.Optional.of(
            new org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies(
                Set.of(0),Set.of(),Set.of()));
      }
      @Override public OpCaps caps(OpSig sig,List<FType> inputs,ShapeHint hint) {
        hint.rows();
        return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
            .exec(org.apache.sysds.common.Types.ExecType.FED)
            .placement(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.FOUT)
            .fout(true,FType.ROW)
            .reason(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.OK).build();
      }
    });
    BinaryOp plus=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,
        OpOp2.PLUS,matrix("A",8,4),matrix("B",8,4));
    OracleFacade.PreparedDecision prepared=new OracleFacade(registry).prepareDecision(plus);
    List<RuntimeException> failures=new ArrayList<>();

    var diagnostics=prepared.visitSupportedPatterns(
        List.of(Arrays.asList(null,FType.ROW),Arrays.asList(null,FType.COL)),
        ignored -> new ShapeHint(8,4,1000),(ordinals,tuple,evidence,failure) -> {
          failures.add(failure);
          return true;
        });

    assertEquals(2,failures.size());
    assertTrue(failures.stream().allMatch(failure -> failure instanceof IllegalStateException
        && failure.getMessage().contains("undeclared ShapeHint")));
    assertEquals(2,diagnostics.oracleEvaluations());
    assertEquals(2,diagnostics.coverageObligations());
    assertEquals(0,diagnostics.evidenceReuses());
  }

  @Test
  public void unresolvedRegionRepresentativeExposesNonLocalCompletions() {
    RulesCore.RuleRegistry registry=new RulesCore.RuleRegistry();
    registry.register(new RulesCore.BaseRule() {
      @Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
      @Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
      @Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies>
          decisionDependencies(OpSig sig) {
        return java.util.Optional.of(
            new org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies(
                Set.of(0),Set.of(),Set.of("rows")));
      }
      @Override public OpCaps caps(OpSig sig,List<FType> inputs,ShapeHint hint) {
        hint.rows();
        return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
            .exec(org.apache.sysds.common.Types.ExecType.CP)
            .placement(org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput.LOUT)
            .reason(org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode.NO_FED_INPUT).build();
      }
    });
    BinaryOp plus=new BinaryOp("plus",DataType.MATRIX,ValueType.FP64,
        OpOp2.PLUS,matrix("A",8,4),matrix("B",8,4));
    OracleFacade.PreparedDecision prepared=new OracleFacade(registry).prepareDecision(plus);
    List<List<FType>> obligations=new ArrayList<>();

    var diagnostics=prepared.visitSupportedPatterns(
        List.of(java.util.Collections.singletonList(null),Arrays.asList(null,FType.ROW)),
        ignored -> unknown(),(ordinals,tuple,evidence,failure) -> {
          assertTrue(failure==null);
          assertFalse(evidence.shapeProof().missingRequiredFacts().isEmpty());
          obligations.add(tuple);
          return true;
        });

    assertEquals(List.of(Arrays.asList(null,FType.ROW)),obligations);
    assertEquals(1,diagnostics.coverageObligations());
    assertEquals(1,diagnostics.oracleEvaluations());
  }

  @Test
  public void aggregateTernaryDeclarationCoversScalarMatrixAndUnknownOutputShapeFacts() {
    Rulesets.AggTernaryRule rule = new Rulesets.AggTernaryRule();
    OpSig signature = OpSig.of(Opcodes.TAKPM.toString(), OpCategory.AGG_TERNARY,
        java.util.Map.of(), OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX,
        OpSig.InputKind.MATRIX);
    var dependencies = rule.decisionDependencies(signature).orElseThrow();
    assertEquals(java.util.Set.of("rows", "cols"), dependencies.allowedShapeFacts());
    List<ShapeHint> shapes = List.of(
        new ShapeHint(1, 1, 1000), new ShapeHint(8, 4, 1000), unknown());
    List<FType> types = List.of(FType.ROW, FType.COL);
    for(ShapeHint shape : shapes)
      for(FType left : types)
        for(FType middle : types)
          for(FType right : types) {
            ShapeHint exact = new ShapeHint(shape.diagnosticSnapshot().rows(),
                shape.diagnosticSnapshot().cols(), 1000);
            rule.caps(signature, List.of(left, middle, right), exact);
            assertTrue(dependencies.allowedShapeFacts().containsAll(
                exact.proof().consultedFacts().keySet()));
          }
  }

  @Test
  public void ternaryElementwiseRelationPreservesOutputAndOperandShapeProofs() {
    TernaryOp ternary = new TernaryOp("ternary", DataType.MATRIX, ValueType.FP64,
        OpOp3.PLUS_MULT, matrix("TA", 8, 4), matrix("TB", 8, 4), matrix("TC", 8, 4));
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(ternary);
    assertEquals(java.util.Set.of("rows", "cols", "rowsA", "colsA", "rowsB", "colsB"),
        prepared.decisionDependencies().orElseThrow().allowedShapeFacts());
    List<List<FType>> domains = List.of(List.of(FType.ROW, FType.COL),
        List.of(FType.ROW, FType.COL), List.of(FType.ROW, FType.COL));
    List<ShapeHint.DiagnosticSnapshot> shapes = List.of(
        new ShapeHint(8, 4, 1000, java.util.Optional.empty(), 8, 4, 1, 4).diagnosticSnapshot(),
        new ShapeHint(-1, -1, 1000, java.util.Optional.empty(), 1, 4, 8, 1).diagnosticSnapshot());
    for(ShapeHint.DiagnosticSnapshot shape : shapes) {
      Function<List<FType>,ShapeHint> exact = ignored -> new ShapeHint(shape.rows(), shape.cols(),
          shape.blockSize(), shape.fullSinglePartition(), shape.rowsA(), shape.colsA(),
          shape.rowsB(), shape.colsB());
      assertTrue(prepared.prepareExecutionRelation(domains, exact).isEmpty());
      for(FType a : domains.get(0))
        for(FType b : domains.get(1))
          for(FType c : domains.get(2)) {
            List<FType> tuple = List.of(a, b, c);
            DecisionEvidence evidence = prepared.decideWithEvidence(tuple, exact.apply(tuple));
            assertTrue(prepared.decisionDependencies().orElseThrow().allowedShapeFacts().containsAll(
                evidence.shapeProof().consultedFacts().keySet()));
          }
    }
  }

  @Test
  public void subsetDependentReshapeBuildsACompressedExecutionRelation() {
    List<Hop> inputs = List.of(matrix("RX", 8, 4), new LiteralOp(4L), new LiteralOp(8L),
        new LiteralOp(-1L), new LiteralOp(true));
    ReorgOp reshape = new ReorgOp("reshape-relation", DataType.MATRIX, ValueType.FP64,
        ReOrgOp.RESHAPE, inputs);
    OracleFacade.PreparedDecision prepared = facade().prepareDecision(reshape);
    List<List<FType>> domains = List.of(DOMAIN, Arrays.asList(null, FType.ROW),
        Arrays.asList((FType)null), Arrays.asList((FType)null), Arrays.asList((FType)null));
    var relation = prepared.prepareExecutionRelation(domains).orElseThrow();

    assertEquals("six determinant choices cover twelve logical tuples", 6,
        relation.regions().size());
    for(FType primary : DOMAIN)
      for(FType ignoredAuxiliary : domains.get(1)) {
        List<FType> tuple = Arrays.asList(primary, ignoredAuxiliary, null, null, null);
        assertEvidence(prepared.decideWithEvidence(tuple, unknown()), relation.evidenceFor(tuple));
      }
    assertEquals(6, relation.oracleEvaluations());
  }

  @Test
  public void cumulativeOffsetKeysEveryInputThatCanChangeFullPartitionEvidence() {
    Rulesets.CumulativeOffsetRule rule = new Rulesets.CumulativeOffsetRule();
    OpSig signature = OpSig.of(Opcodes.BCUMOFFKP.toString(), OpCategory.OTHER,
        java.util.Map.of(), OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX);
    var dependencies = rule.decisionDependencies(signature).orElseThrow();

    assertEquals(java.util.Set.of(0), dependencies.determinantPositions());
    assertEquals("[FULL,null] and [FULL,FULL] require distinct exact shape evidence keys",
        java.util.Set.of(0, 1), dependencies.shapeSelectorPositions());
    assertEquals(java.util.Set.of("fullSinglePartition"), dependencies.allowedShapeFacts());
  }

  private static void assertEvidence(DecisionEvidence expected, DecisionEvidence actual) {
    assertCaps(expected.caps(), actual.caps());
    assertEquals(expected.shapeProof(), actual.shapeProof());
  }

  private static void assertCaps(OpCaps expected, OpCaps actual) {
    assertEquals(expected.category(), actual.category());
    assertEquals(expected.opcode(), actual.opcode());
    assertEquals(expected.exec(), actual.exec());
    assertEquals(expected.placement(), actual.placement());
    assertEquals(expected.foutEnabled(), actual.foutEnabled());
    assertEquals(expected.foutFType(), actual.foutFType());
    assertEquals(expected.reason(), actual.reason());
    assertEquals(expected.detail(), actual.detail());
    assertEquals(expected.notes().stream().map(note -> note.code() + ":" + note.message()).toList(),
        actual.notes().stream().map(note -> note.code() + ":" + note.message()).toList());
  }

  private static OracleFacade facade() {
    return new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
  }

  private static ShapeHint unknown() {
    return new ShapeHint(-1, -1, -1);
  }

  private static DataOp matrix(String name, long rows, long cols) {
    return new DataOp(name, DataType.MATRIX, ValueType.FP64,
        OpOpData.TRANSIENTREAD, name, rows, cols, rows * cols, 1000);
  }
}
