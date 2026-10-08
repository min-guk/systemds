/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. The ASF licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sysds.common.Types.DataType;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp1;
import org.apache.sysds.common.Types.OpOp2;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.common.Types.OpOpData;
import org.apache.sysds.common.Types.ValueType;
import org.apache.sysds.hops.BinaryOp;
import org.apache.sysds.hops.DataOp;
import org.apache.sysds.hops.FunctionOp;
import org.apache.sysds.hops.FunctionOp.FunctionType;
import org.apache.sysds.hops.IndexingOp;
import org.apache.sysds.hops.LiteralOp;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.UnaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCaps;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpCategory;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.OpSig;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ReasonCode;
import org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeHint;
import org.apache.sysds.hops.fedplanner.rules.RulesCore;
import org.apache.sysds.hops.fedplanner.rules.Rulesets;
import org.apache.sysds.hops.fedplanner.rules.bridge.OracleFacade;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.AbstractShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEvaluationStatus;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.NodeShapeFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ValueVersionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.VersionKind;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

public class PlacementCandidateGeneratorMrvTest {
	private static final PlacementCandidateGenerator.GenerationPrivacy FED_REQUIRED =
		new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE, Set.of(0));

	@Test
	public void partialOracleMrvEmitsExactlyTheFederatedLegalTuples() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, matrix("X"), matrix("U"), matrix("V"), false, false);
		OracleFacade.PreparedDecision prepared = facade().prepareDecision(hop);
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.FULL, FType.ROW, FType.PART, FType.COL, FType.BROADCAST),
			Arrays.asList(null, FType.BROADCAST, FType.ROW),
			Arrays.asList(null, FType.BROADCAST, FType.COL));

		List<List<FType>> reference = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(domains, FED_REQUIRED, tuple -> {
			if(prepared.decideWithEvidence(tuple, null).caps().exec() == ExecType.FED)
				reference.add(tuple);
		}, null);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<List<FType>> actual = new ArrayList<>();
		OracleFacade.PreparedDecision actualOracle = facade().prepareDecision(hop);
		PlacementCandidateGenerator.forEachInputCombination(
			domains, FED_REQUIRED, actualOracle, actual::add, metrics);

		Assert.assertEquals(reference, actual);
		Assert.assertEquals("only ROW/COL X branches reach exact candidate construction", 18,
			metrics.snapshot().inputLeaves());
		Assert.assertTrue("partial Oracle must reduce the 45 privacy-masked Cartesian leaves",
			metrics.snapshot().inputLeaves() < 45);
		var partial = actualOracle.partialDecisionDiagnostics();
		Assert.assertEquals("selected choices are not queried again and FEASIBLE stops later probes",
			11, partial.requests());
		Assert.assertEquals(partial.requests(), partial.evaluations());
	}

	@Test
	public void ruleDirectedRegionsReduceWorkComparedWithPartialMrv() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, matrix("Xwork"), matrix("Uwork"), matrix("Vwork"), false, false);
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.FULL, FType.ROW, FType.PART, FType.COL, FType.BROADCAST),
			Arrays.asList(null, FType.BROADCAST, FType.ROW),
			Arrays.asList(null, FType.BROADCAST, FType.COL));
		OracleFacade.PreparedDecision oldOracle = facade().prepareDecision(hop);
		List<List<FType>> expected = new ArrayList<>();
		List<OpCaps> expectedCaps = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(domains, FED_REQUIRED, oldOracle,
			tuple -> {
				expected.add(tuple);
				expectedCaps.add(oldOracle.decideWithEvidence(tuple, null).caps());
			}, null);
		OracleFacade.PreparedDecision newOracle = facade().prepareDecision(hop);
		var relation = newOracle.prepareExecutionRelation(domains).orElseThrow();
		List<List<FType>> actual = new ArrayList<>();
		List<OpCaps> actualCaps = new ArrayList<>();
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		var cardinality = PlacementCandidateGenerator.forEachExecutionRelationCombination(
			domains, FED_REQUIRED, relation, (tuple, evidence) -> {
				actual.add(tuple);
				actualCaps.add(evidence.caps());
			}, metrics);

		Assert.assertEquals(expected, actual);
		for(int index = 0; index < expectedCaps.size(); index++)
			assertCapsEqual(expectedCaps.get(index), actualCaps.get(index));
		Assert.assertEquals(18, expected.size());
		Assert.assertEquals(11, oldOracle.partialDecisionDiagnostics().evaluations());
		Assert.assertEquals("privacy removes null X before any decision", 5, relation.oracleEvaluations());
		Assert.assertEquals(0, newOracle.partialDecisionDiagnostics().evaluations());
		Assert.assertEquals(java.math.BigInteger.valueOf(18), cardinality);
		Assert.assertEquals(18, metrics.snapshot().inputLeaves());
	}

	@Test
	public void assignedAbsentPrimaryIsNotConfusedWithAnUnassignedInput() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, matrix("X"), matrix("U"), matrix("V"), false, false);
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW),
			Arrays.asList(null, FType.BROADCAST),
			List.of(FType.BROADCAST));
		var protectedSecond = new PlacementCandidateGenerator.GenerationPrivacy(
			Privacy.PRIVATE, Set.of(1));
		List<List<FType>> actual = new ArrayList<>();

		PlacementCandidateGenerator.forEachInputCombination(domains, protectedSecond,
			facade().prepareDecision(hop), actual::add, null);

		Assert.assertEquals(List.of(List.of(FType.ROW, FType.BROADCAST, FType.BROADCAST)), actual);
	}

	@Test
	public void weightedPartialKernelsMatchCompleteOracleLegality() {
		DataOp x = matrix("X");
		DataOp u = matrix("U");
		DataOp v = matrix("V");
		DataOp w = matrix("W");
		List<QuaternaryOp> operations = List.of(
			new QuaternaryOp("wsloss", DataType.SCALAR, ValueType.FP64,
				OpOp4.WSLOSS, x, u, v, w, false),
			new QuaternaryOp("wcemm", DataType.SCALAR, ValueType.FP64,
				OpOp4.WCEMM, x, u, v, new LiteralOp(0.1), 1, false, false),
			new QuaternaryOp("wdivmm", DataType.MATRIX, ValueType.FP64,
				OpOp4.WDIVMM, x, u, v, new LiteralOp(-1L), 1, false, false));
		for(QuaternaryOp operation : operations)
			assertPartialMatchesCompleteOracle(operation);
	}

	@Test
	public void weightedExecutionRelationsMatchEveryForwardDecision() {
		DataOp x = matrix("Xrel");
		DataOp u = matrix("Urel");
		DataOp v = matrix("Vrel");
		DataOp w = matrix("Wrel");
		for(QuaternaryOp operation : List.of(
			new QuaternaryOp("wsloss", DataType.SCALAR, ValueType.FP64,
				OpOp4.WSLOSS, x, u, v, w, false),
			new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
				OpOp4.WSIGMOID, x, u, v, false, false),
			new QuaternaryOp("wcemm", DataType.SCALAR, ValueType.FP64,
				OpOp4.WCEMM, x, u, v, new LiteralOp(0.1), 1, false, false),
			new QuaternaryOp("wdivmm-basic", DataType.MATRIX, ValueType.FP64,
				OpOp4.WDIVMM, x, u, v, new LiteralOp(-1L), 0, false, false),
			new QuaternaryOp("wdivmm-left", DataType.MATRIX, ValueType.FP64,
				OpOp4.WDIVMM, x, u, v, new LiteralOp(-1L), 1, false, false),
			new QuaternaryOp("wdivmm-right", DataType.MATRIX, ValueType.FP64,
				OpOp4.WDIVMM, x, u, v, new LiteralOp(-1L), 2, false, false))) {
			List<List<FType>> relationDomains = weightedRelationDomains(operation);
			var relation = facade().prepareDecision(operation).prepareExecutionRelation(
				relationDomains).orElseThrow();
			Assert.assertEquals("relation construction itself does not call the forward Oracle", 0,
				relation.oracleEvaluations());
			var regions = relation.regions();
			regions.forEach(OracleFacade.ExecutionRegion::evidence);
			Assert.assertEquals("seven runtime X values collapse to six distinct rule keys", 6,
				relation.oracleEvaluations());
			Assert.assertTrue("CP-compatible input rectangles remain represented",
				regions.stream().anyMatch(region -> region.evidence().caps().exec() == ExecType.CP));
			Assert.assertTrue("FED input rectangles remain represented",
				regions.stream().anyMatch(region -> region.evidence().caps().exec() == ExecType.FED));
			assertExecutionRelationMatchesForwardOracle(operation, relationDomains);
		}
	}

	@Test
	public void weightedScalarRulesDeclareExactCandidateHeaderDependencies() {
		DataOp x = matrix("Xdependencies");
		DataOp u = matrix("Udependencies");
		DataOp v = matrix("Vdependencies");
		List<QuaternaryOp> supported = List.of(
			new QuaternaryOp("wsloss-dependencies", DataType.SCALAR, ValueType.FP64,
				OpOp4.WSLOSS, x, u, v, matrix("Wdependencies"), false),
			new QuaternaryOp("wcemm-dependencies", DataType.SCALAR, ValueType.FP64,
				OpOp4.WCEMM, x, u, v, new LiteralOp(0.1), 1, false, false));
		for(QuaternaryOp operation : supported) {
			var dependencies = facade().prepareDecision(operation)
				.candidateFamilyDependencies().orElseThrow();
			Assert.assertEquals(Set.of(0), dependencies.capabilityPositions());
			Assert.assertTrue(dependencies.profilePositions().isEmpty());
			Assert.assertEquals(Set.of(0), dependencies.emissionPositions());
			Assert.assertEquals(Set.of(0), dependencies.allPositions());
		}
		QuaternaryOp sigmoid = new QuaternaryOp("wsigmoid-dependencies", DataType.MATRIX,
			ValueType.FP64, OpOp4.WSIGMOID, x, u, v, false, false);
		Assert.assertEquals(Set.of(0), facade().prepareDecision(sigmoid)
			.candidateFamilyDependencies().orElseThrow().allPositions());
		QuaternaryOp unsupported = new QuaternaryOp("wdivmm-dependencies", DataType.MATRIX,
			ValueType.FP64, OpOp4.WDIVMM, x, u, v, new LiteralOp(-1L), 1, false, false);
		Assert.assertTrue(facade().prepareDecision(unsupported)
			.candidateFamilyDependencies().isEmpty());
	}

	@Test
	public void mmFedKeepsExecutionDeterminantsWithoutAdvertisingAnInactiveFamily() {
		Rulesets.MMFedRule rule = new Rulesets.MMFedRule();
		OpSig exact = OpSig.of("mapmm", OpCategory.BINARY_MM,
			Map.of("r_is_vector", "false"), OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX);
		Assert.assertEquals(Set.of(0, 1),
			rule.shapeIndependentDecision(exact).orElseThrow().determinantPositions());
		Assert.assertTrue(rule.candidateFamilyDependencies(exact).isEmpty());

		OpSig unresolved = OpSig.of("mapmm", OpCategory.BINARY_MM, Map.of(),
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX);
		Assert.assertTrue(rule.shapeIndependentDecision(unresolved).isEmpty());
		Assert.assertTrue(rule.candidateFamilyDependencies(unresolved).isEmpty());
	}

	@Test
	public void privateAggregateUnaryKeepsExactRowsWithoutBuildingUnusedRelationHeaders() {
		UnaryOp hop = new UnaryOp("private-exp", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, matrix("private-X"));
		List<List<FType>> domains = List.of(Arrays.asList(null, FType.ROW, FType.COL));
		ControlRegionKey region = new ControlRegionKey("private-unary-relation", "main",
			List.of("root"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("private-unary-relation", "main", "root",
			"compiled", region, "exp", "exp");
		ValueVersionKey value = new ValueVersionKey("private-unary-relation", "X", region, 0,
			VersionKind.ORDINARY, List.of());
		NodeShapeFact matrixShape = new NodeShapeFact(DataType.MATRIX, 8, 4);
		List<NodeShapeFact> inputShapes = List.of(matrixShape);
		SinglePartitionFacts partitions = new SinglePartitionFacts(hop.getInput(), Map.of(), Set.of());
		List<CandidateRuleKey> keys = new ArrayList<>();
		List<CandidateRuleFact> facts = new ArrayList<>();
		PlacementCandidateGenerator generator = new PlacementCandidateGenerator(facade(), null);
		OracleFacade.PreparedDecision prepared = facade().prepareDecision(hop);
		Assert.assertTrue(prepared.candidateFamilyDependencies().isPresent());
		var preparedRelation = prepared.prepareExecutionRelation(domains).orElseThrow();
		Assert.assertEquals(2, preparedRelation.regions().stream()
			.filter(candidate -> candidate.evidence().caps().exec() == ExecType.FED).count());

		generator.buildNode(hop, key, value, List.of(), Arrays.asList((DurableAnchorKey)null),
			Arrays.asList((CompiledHopKey)null), matrixShape,
			AbstractShapeFact.fromConcrete(matrixShape), partitions, inputShapes, domains, keys, facts,
			new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PRIVATE_AGGREGATE, Set.of(0)));

		List<CandidateRuleFact> available = facts.stream()
			.filter(fact -> fact.status() == CandidateEvaluationStatus.AVAILABLE).toList();
		Assert.assertEquals(2, available.size());
		Assert.assertTrue("unpublished unary relations must not add runtime header/oracle work",
			generator.candidateRuleRelations().isEmpty());
		Assert.assertTrue(available.stream().allMatch(fact ->
			fact.key().orderedInputs().stream().allMatch(CandidateInputState::present)));
		for(CandidateRuleFact exact : available) {
			Assert.assertEquals(1, exact.allowedEmissionFacts().size());
			Assert.assertEquals(ExecType.FED, exact.allowedEmissionFacts().get(0)
				.emissionState().placementState().execType());
			Assert.assertEquals(FederatedOutput.FOUT, exact.allowedEmissionFacts().get(0)
				.emissionState().placementState().output());
		}
	}

	@Test
	public void buildNodeUsesGeneralRuleMrvWhenNoExecutionRelationCompresses() {
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.OTHER; }
			@Override public Set<String> opcodes() { return Set.of(OpOp1.EXP.toString()); }
			@Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies>
				decisionDependencies(OpSig sig) {
				return java.util.Optional.of(
					new org.apache.sysds.hops.fedplanner.rules.RulesApi.DecisionDependencies(
						Set.of(0), Set.of(), Set.of()));
			}
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				boolean fed = inputs.get(0) == FType.ROW;
				var builder = OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(fed ? ExecType.FED : ExecType.CP)
					.placement(fed ? FederatedOutput.FOUT : FederatedOutput.LOUT)
					.reason(fed ? ReasonCode.OK : ReasonCode.NO_FED_INPUT);
				if(fed)
					builder.fout(true, FType.ROW);
				return builder.build();
			}
		});
		UnaryOp hop = new UnaryOp("general-mrv-exp", DataType.MATRIX, ValueType.FP64,
			OpOp1.EXP, matrix("general-mrv-X"));
		List<List<FType>> domains = List.of(Arrays.asList(null, FType.ROW, FType.COL));
		OracleFacade facade = new OracleFacade(registry);
		Assert.assertTrue("all varying axes are declared determinants, so eager relation has no compression",
			facade.prepareDecision(hop).prepareExecutionRelation(domains).isEmpty());
		ControlRegionKey region = new ControlRegionKey("general-mrv", "main",
			List.of("root"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("general-mrv", "main", "root",
			"compiled", region, "exp", "exp");
		ValueVersionKey value = new ValueVersionKey("general-mrv", "X", region, 0,
			VersionKind.ORDINARY, List.of());
		NodeShapeFact shape = new NodeShapeFact(DataType.MATRIX, 8, 4);
		SearchSpaceMetrics metrics = new SearchSpaceMetrics();
		List<CandidateRuleKey> keys = new ArrayList<>();
		List<CandidateRuleFact> facts = new ArrayList<>();
		PlacementCandidateGenerator generator = new PlacementCandidateGenerator(facade, metrics);

		generator.buildNode(hop, key, value, List.of(), Arrays.asList((DurableAnchorKey)null),
			Arrays.asList((CompiledHopKey)null), shape, AbstractShapeFact.fromConcrete(shape),
			new SinglePartitionFacts(hop.getInput(), Map.of(), Set.of()), List.of(shape),
			domains, keys, facts, FED_REQUIRED);

		Assert.assertEquals(1, facts.size());
		Assert.assertEquals(List.of(CandidateInputState.present(FType.ROW)),
			facts.get(0).key().orderedInputs());
		Assert.assertEquals(CandidateEvaluationStatus.AVAILABLE, facts.get(0).status());
		Assert.assertEquals("the surviving leaf reuses early evidence", 2,
			metrics.snapshot().candidateOracleCalls());
		var coverage = metrics.generationPruningCoverage();
		Assert.assertEquals(1, coverage.earlyFeasibilityApplications());
		Assert.assertTrue(coverage.earlyFeasibilityChecks() > 0);
		Assert.assertTrue(coverage.earlyFeasibilityCuts() > 0);
	}

	@Test
	public void buildNodeFamilyMatchesTheExactPublicGeneratorWithoutAllocatingItsRows() {
		QuaternaryOp hop = new QuaternaryOp("wsloss-family", DataType.SCALAR, ValueType.FP64,
			OpOp4.WSLOSS, matrix("XF"), matrix("UF"), matrix("VF"), matrix("WF"), false);
		List<List<FType>> domains = weightedRelationDomains(hop);
		ControlRegionKey region = new ControlRegionKey("family-build", "main",
			List.of("root"), "root", "compiled");
		CompiledHopKey key = new CompiledHopKey("family-build", "main", "root", "compiled",
			region, "weighted", "weighted");
		ValueVersionKey value = new ValueVersionKey("family-build", "sl", region, 0,
			VersionKind.ORDINARY, List.of());
		NodeShapeFact scalar = new NodeShapeFact(DataType.SCALAR, 0, 0);
		List<NodeShapeFact> inputShapes = hop.getInput().stream()
			.map(input -> new NodeShapeFact(input.getDataType(), input.getDim1(), input.getDim2())).toList();
		SinglePartitionFacts partitions = new SinglePartitionFacts(hop.getInput(), Map.of(), Set.of());
		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey> inputAnchors =
			new ArrayList<>(java.util.Collections.nCopies(hop.getInput().size(), null));
		List<CompiledHopKey> inputOwners =
			new ArrayList<>(java.util.Collections.nCopies(hop.getInput().size(), null));

		List<CandidateRuleKey> explicitKeys = new ArrayList<>();
		List<CandidateRuleFact> explicitFacts = new ArrayList<>();
		SearchSpaceMetrics explicitMetrics = new SearchSpaceMetrics();
		PlacementCandidateGenerator explicit = new PlacementCandidateGenerator(
			facade(), explicitMetrics, false);
		PlacementIdentity.beginAnalysisScope(explicitMetrics);
		try {
			explicit.buildNode(hop, key, value, List.of(), inputAnchors, inputOwners, scalar,
				AbstractShapeFact.fromConcrete(scalar), partitions, inputShapes, domains,
				explicitKeys, explicitFacts, null);
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}

		List<CandidateRuleKey> compactKeys = new ArrayList<>();
		List<CandidateRuleFact> compactFacts = new ArrayList<>();
		SearchSpaceMetrics compactMetrics = new SearchSpaceMetrics();
		PlacementCandidateGenerator compact = new PlacementCandidateGenerator(facade(), compactMetrics);
		PlacementIdentity.beginAnalysisScope(compactMetrics);
		try {
			compact.buildNode(hop, key, value, List.of(), inputAnchors, inputOwners, scalar,
				AbstractShapeFact.fromConcrete(scalar), partitions, inputShapes, domains,
				compactKeys, compactFacts,
				new PlacementCandidateGenerator.GenerationPrivacy(Privacy.PUBLIC, Set.of()));
		}
		finally {
			PlacementIdentity.endAnalysisScope();
		}
		List<CpRuleFamily> families = compact.cpRuleFamilies();
		List<CandidateRuleRelation> fedRelations = compact.candidateRuleRelations();
		Assert.assertTrue(families.stream().anyMatch(family ->
			family.logicalSize().compareTo(java.math.BigInteger.ONE) > 0));
		int deferredTuples = families.stream().mapToInt(
			family -> family.logicalSize().intValueExact()).sum()
			+ fedRelations.stream().mapToInt(
				relation -> relation.logicalSize().intValueExact()).sum();
		Assert.assertEquals("only headers with exact input support may replace exact key rows",
			explicitKeys.size() - deferredTuples, compactKeys.size());
		Assert.assertEquals("only headers with exact input support may replace exact fact rows",
			explicitFacts.size() - deferredTuples, compactFacts.size());
		Assert.assertEquals(0, families.stream().mapToInt(CpRuleFamily::materializedMemberCount).sum());
		Assert.assertEquals(0, fedRelations.stream()
			.mapToInt(CandidateRuleRelation::materializedMemberCount).sum());
		Assert.assertTrue(compactMetrics.objectCreationSnapshot().candidateRuleFacts()
			< explicitMetrics.objectCreationSnapshot().candidateRuleFacts());
		Assert.assertEquals("logical tuple metric includes both CP and FED regions",
			java.math.BigInteger.valueOf(explicitFacts.size()),
			compactMetrics.executionRelationSnapshot().logicalTuples());
		long relocationWitnessRows = fedRelations.stream()
			.flatMap(relation -> relation.regions().stream())
			.mapToLong(candidateRegion -> {
				long rows = 1L;
				for(int left = 0; left < candidateRegion.axes().size(); left++) {
					long leftDelta = candidateRegion.axes().get(left).size() - 1L;
					rows += leftDelta;
					for(int right = left + 1; right < candidateRegion.axes().size(); right++)
						rows += leftDelta * (candidateRegion.axes().get(right).size() - 1L);
				}
				return rows;
			}).sum();
		System.out.println("FACTORIZED_GENERATION_WORK|explicitFacts=" + explicitFacts.size()
			+ "|generatedExplicitFedFacts=" + fedRelations.stream()
				.map(CandidateRuleRelation::logicalSize)
				.reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add)
			+ "|newRetainedExactFacts=" + compactFacts.size()
			+ "|generationFedFactsAvoided=" + fedRelations.stream()
				.map(CandidateRuleRelation::logicalSize)
				.reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add)
			+ "|familyHeaders=" + families.size()
			+ "|familyLogicalTuples=" + families.stream().map(CpRuleFamily::logicalSize)
				.reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add)
			+ "|fedRelationHeaders=" + fedRelations.stream()
				.mapToInt(relation -> relation.regions().size()).sum()
			+ "|fedRelationLogicalTuples=" + fedRelations.stream()
				.map(CandidateRuleRelation::logicalSize)
				.reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add)
			+ "|relocationWitnessRows=" + relocationWitnessRows
			+ "|explicitCreated=" + explicitMetrics.objectCreationSnapshot()
			+ "|relationCreated=" + compactMetrics.objectCreationSnapshot()
			+ "|before=" + explicitMetrics.executionRelationSnapshot()
			+ "|after=" + compactMetrics.executionRelationSnapshot());

		List<CandidateRuleFact> reconstructed = new ArrayList<>(compactFacts);
		for(CpRuleFamily family : families)
			expandFamily(family, 0, new ArrayList<>(), reconstructed);
		for(CandidateRuleRelation relation : fedRelations)
			for(CandidateRuleRelation.ConditionalRegion candidateRegion : relation.regions())
				expandRelation(relation, candidateRegion, 0, new ArrayList<>(), reconstructed);
		java.util.Comparator<CandidateRuleFact> order = java.util.Comparator.comparing(
			fact -> fact.key().normalizedSignature());
		explicitFacts.sort(order);
		reconstructed.sort(order);
		Assert.assertEquals(explicitFacts.stream().map(CandidateRuleFact::key).toList(),
			reconstructed.stream().map(CandidateRuleFact::key).toList());
		for(int index = 0; index < explicitFacts.size(); index++) {
			CandidateRuleFact expected = explicitFacts.get(index);
			CandidateRuleFact actual = reconstructed.get(index);
			Assert.assertEquals(expected.capability(), actual.capability());
			Assert.assertEquals(expected.shapeProof(), actual.shapeProof());
			Assert.assertEquals(expected.profile(), actual.profile());
			Assert.assertEquals(expected.allowedEmissionFacts(), actual.allowedEmissionFacts());
		}

		Assert.assertEquals(1, fedRelations.size());
		List<CandidateRuleFact> explicitFed = explicitFacts.stream().filter(fact ->
			fact.capability().nativeExec() == ExecType.FED
				&& fact.capability().nativeOutput() == FederatedOutput.LOUT).toList();
		Assert.assertFalse(explicitFed.isEmpty());
		Assert.assertTrue(compactFacts.stream().noneMatch(explicitFed::contains));
		Assert.assertEquals(java.math.BigInteger.valueOf(explicitFed.size()),
			fedRelations.get(0).logicalSize());
	}

	private static void expandFamily(CpRuleFamily family, int position,
		List<CandidateInputState> inputs, List<CandidateRuleFact> output) {
		if(position == family.axes().size()) {
			output.add(family.requireExact(inputs));
			return;
		}
		for(CandidateInputState input : family.axes().get(position)) {
			inputs.add(input);
			expandFamily(family, position + 1, inputs, output);
			inputs.remove(inputs.size() - 1);
		}
	}

	private static void expandRelation(CandidateRuleRelation relation,
		CandidateRuleRelation.ConditionalRegion region, int position,
		List<CandidateInputState> inputs, List<CandidateRuleFact> output) {
		if(position == region.axes().size()) {
			output.add(relation.requireExact(inputs));
			return;
		}
		for(CandidateInputState input : region.axes().get(position)) {
			inputs.add(input);
			expandRelation(relation, region, position + 1, inputs, output);
			inputs.remove(inputs.size() - 1);
		}
	}

	@Test
	public void executionRelationHandlesEmptyAndMalformedDomains() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, matrix("Xempty"), matrix("Uempty"), matrix("Vempty"), false, false);
		OracleFacade.PreparedDecision prepared = facade().prepareDecision(hop);

		var empty = prepared.prepareExecutionRelation(List.of(
			List.of(FType.ROW), List.of(), List.of(FType.BROADCAST))).orElseThrow();

		Assert.assertTrue(empty.regions().isEmpty());
		Assert.assertEquals(0, empty.oracleEvaluations());
		Assert.assertTrue("arity mismatch must use the existing exact fallback",
			prepared.prepareExecutionRelation(List.of(List.of(FType.ROW), List.of(FType.COL))).isEmpty());
	}

	@Test
	public void generatorConsumesPrivacyMaskedRegionsWithoutPartialSearch() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, matrix("Xregion"), matrix("Uregion"), matrix("Vregion"), false, false);
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW, FType.COL, FType.FULL,
				FType.PART, FType.BROADCAST),
			Arrays.asList(null, FType.BROADCAST), List.of(FType.BROADCAST));
		var protectedSecond = new PlacementCandidateGenerator.GenerationPrivacy(
			Privacy.PRIVATE, Set.of(1));
		OracleFacade.PreparedDecision relationOwner = facade().prepareDecision(hop);
		var relation = relationOwner.prepareExecutionRelation(domains).orElseThrow();
		List<List<FType>> actual = new ArrayList<>();
		List<OpCaps> actualCaps = new ArrayList<>();

		PlacementCandidateGenerator.forEachExecutionRelationCombination(domains,
			protectedSecond, relation, (tuple, evidence) -> {
				actual.add(tuple);
				actualCaps.add(evidence.caps());
			}, null);

		Assert.assertEquals(List.of(
			List.of(FType.ROW, FType.BROADCAST, FType.BROADCAST),
			List.of(FType.COL, FType.BROADCAST, FType.BROADCAST)), actual);
		Assert.assertTrue(actualCaps.stream().allMatch(caps -> caps.exec() == ExecType.FED));
		Assert.assertEquals("relation traversal bypasses the partial MRV kernel", 0,
			relationOwner.partialDecisionDiagnostics().evaluations());
		List<List<FType>> cpAllowed = new ArrayList<>();
		PlacementCandidateGenerator.forEachExecutionRelationCombination(domains, null,
			relation, (tuple, evidence) -> cpAllowed.add(tuple), null);
		Assert.assertEquals("CP-compatible rectangles preserve the complete product", 12,
			cpAllowed.size());
	}

	@Test
	public void privacyEmptyRegionSkipsItsOracleEvaluation() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid-empty-private", DataType.MATRIX,
			ValueType.FP64, OpOp4.WSIGMOID, matrix("XemptyPrivate"),
			matrix("UemptyPrivate"), matrix("VemptyPrivate"), false, false);
		List<List<FType>> domains = List.of(
			List.of(FType.ROW), Arrays.asList((FType) null), List.of(FType.BROADCAST));
		var protectedSecond = new PlacementCandidateGenerator.GenerationPrivacy(
			Privacy.PRIVATE, Set.of(1));
		var relation = facade().prepareDecision(hop)
			.prepareExecutionRelation(domains).orElseThrow();
		List<List<FType>> actual = new ArrayList<>();

		java.math.BigInteger logicalTuples =
			PlacementCandidateGenerator.forEachExecutionRelationCombination(domains,
				protectedSecond, relation, (tuple, evidence) -> actual.add(tuple), null);

		Assert.assertTrue(actual.isEmpty());
		Assert.assertEquals(java.math.BigInteger.ZERO, logicalTuples);
		Assert.assertEquals("privacy masking precedes the costly forward Oracle", 0,
			relation.oracleEvaluations());
	}

	@Test
	public void executionRelationPreservesRuleErrorFailFast() {
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
			@Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
			@Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeIndependentDecision>
				shapeIndependentDecision(OpSig sig) {
				return java.util.Optional.of(
					new org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeIndependentDecision(Set.of(0)));
			}
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(ExecType.CP).placement(FederatedOutput.LOUT)
					.reason(ReasonCode.RULE_ERROR).detail("synthetic relation failure").build();
			}
		});
		BinaryOp plus = new BinaryOp("plus-error", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, matrix("EA"), matrix("EB"));
		List<List<FType>> domains = List.of(List.of(FType.ROW), List.of(FType.COL));
		var relation = new OracleFacade(registry).prepareDecision(plus)
			.prepareExecutionRelation(domains).orElseThrow();

		try {
			PlacementCandidateGenerator.forEachExecutionRelationCombination(domains,
				FED_REQUIRED, relation, (tuple, evidence) -> { }, null);
			Assert.fail("RULE_ERROR must fail before a CP region is privacy-pruned");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("RULE_ERROR"));
			Assert.assertTrue(expected.getMessage(),
				expected.getMessage().contains("synthetic relation failure"));
		}
		Assert.assertEquals(1, relation.oracleEvaluations());
	}

	@Test
	public void guardFailureRemainsPartOfTheForwardDecision() {
		Rulesets.WeightedSigmoidRule rule = new Rulesets.WeightedSigmoidRule();
		OpSig sig = OpSig.of("wsigmoid", OpCategory.QUATERNARY,
			Map.of("rc.guardOverride", "false"), OpSig.InputKind.MATRIX,
			OpSig.InputKind.MATRIX, OpSig.InputKind.MATRIX);

		Assert.assertEquals(Set.of(0),
			rule.shapeIndependentDecision(sig).orElseThrow().determinantPositions());
		for(FType auxiliary : List.of(FType.ROW, FType.COL, FType.BROADCAST)) {
			OpCaps caps = rule.caps(sig, List.of(FType.ROW, auxiliary, FType.BROADCAST),
				new ShapeHint(8, 4, 1000));
			Assert.assertEquals(ExecType.CP, caps.exec());
			Assert.assertEquals(ReasonCode.REPR_CHANGE_GUARD_FAIL, caps.reason());
		}
	}

	@Test
	public void cpAllowedSearchRetainsTuplesThatCannotExecuteFederated() {
		QuaternaryOp hop = new QuaternaryOp("wsigmoid", DataType.MATRIX, ValueType.FP64,
			OpOp4.WSIGMOID, matrix("X"), matrix("U"), matrix("V"), false, false);
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW, FType.FULL, FType.BROADCAST),
			List.of(FType.BROADCAST), List.of(FType.BROADCAST));
		List<List<FType>> actual = new ArrayList<>();

		PlacementCandidateGenerator.forEachInputCombination(domains, null,
			facade().prepareDecision(hop), actual::add, null);

		Assert.assertEquals("CP legality keeps NO_FED tuples in the candidate relation", 4,
			actual.size());
	}

	@Test
	public void functionCallSkipsPartialKernelButKeepsItsCompleteDecision() {
		FunctionOp call = new FunctionOp(FunctionType.DML, "main", "f",
			new String[] {"X", "Y"}, List.of(matrix("X"), matrix("Y")),
			new String[] {"Z"}, true);
		OracleFacade.PreparedDecision prepared = facade().prepareDecision(call);
		Assert.assertFalse(prepared.supportsPartialFedFeasibility());
		Assert.assertEquals(ExecType.FED,
			prepared.decideWithEvidence(List.of(FType.ROW, FType.COL), null).caps().exec());
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW, FType.FULL),
			Arrays.asList(null, FType.COL));
		List<List<FType>> actual = new ArrayList<>();

		PlacementCandidateGenerator.forEachInputCombination(
			domains, FED_REQUIRED, prepared, actual::add, null);

		Assert.assertEquals("only the cheap protected-absence mask applies to UDF placeholders", 4,
			actual.size());
	}

	@Test
	public void rightIndexFramePrimaryRemainsPartiallyFeasible() {
		DataOp frame = new DataOp("F", DataType.FRAME, ValueType.STRING,
			OpOpData.TRANSIENTREAD, "F", 8, 4, 32, 1000);
		IndexingOp rightIndex = new IndexingOp("slice", DataType.FRAME, ValueType.STRING,
			frame, new LiteralOp(1L), new LiteralOp(4L), new LiteralOp(1L),
			new LiteralOp(2L), false, false);
		List<List<FType>> domains = List.of(
			Arrays.asList(null, FType.ROW, FType.FULL, FType.BROADCAST),
			Arrays.asList((FType) null), Arrays.asList((FType) null),
			Arrays.asList((FType) null), Arrays.asList((FType) null));
		List<List<FType>> actual = new ArrayList<>();

		PlacementCandidateGenerator.forEachInputCombination(domains, FED_REQUIRED,
			facade().prepareDecision(rightIndex), actual::add, null);

		Assert.assertEquals(2, actual.size());
		Assert.assertEquals(FType.ROW, actual.get(0).get(0));
		Assert.assertEquals(FType.FULL, actual.get(1).get(0));
		Assert.assertTrue("RightIndex keeps exact tuple receipts instead of relation reuse",
			facade().prepareDecision(rightIndex).prepareExecutionRelation(domains).isEmpty());
	}

	@Test
	public void preparedDecisionPreservesMutableShapeProofContext() {
		AtomicInteger calls = new AtomicInteger();
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
			@Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				calls.incrementAndGet();
				if(inputs.get(0) == FType.ROW)
					hint.rows();
				else
					hint.cols();
				return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(ExecType.FED).placement(FederatedOutput.FOUT)
					.fout(true, FType.ROW).reason(ReasonCode.OK).build();
			}
		});
		BinaryOp plus = new BinaryOp("plus", DataType.MATRIX, ValueType.FP64, OpOp2.PLUS,
			matrix("A"), matrix("B"));
		OracleFacade.PreparedDecision prepared = new OracleFacade(registry).prepareDecision(plus);

		ShapeHint shared = new ShapeHint(8, 4, 1000);
		var first = prepared.decideWithEvidence(List.of(FType.ROW, FType.ROW),
			shared);
		var second = prepared.decideWithEvidence(List.of(FType.COL, FType.COL), shared);
		var repeated = prepared.decideWithEvidence(List.of(FType.ROW, FType.ROW), shared);
		var fresh = prepared.decideWithEvidence(List.of(FType.ROW, FType.ROW),
			new ShapeHint(8, 4, 1000));

		Assert.assertEquals(Set.of("rows"), first.shapeProof().requiredFacts());
		Assert.assertEquals(Set.of("cols", "rows"), second.shapeProof().requiredFacts());
		Assert.assertEquals(Set.of("cols", "rows"), repeated.shapeProof().requiredFacts());
		Assert.assertEquals("a fresh equal diagnostic shape must not inherit prior consultations",
			Set.of("rows"), fresh.shapeProof().requiredFacts());
		Assert.assertEquals("complete decisions preserve public ShapeHint consultation effects", 4,
			calls.get());
	}

	@Test
	public void falseShapeIndependentContractFailsClosed() {
		RulesCore.RuleRegistry registry = new RulesCore.RuleRegistry();
		registry.register(new RulesCore.BaseRule() {
			@Override public OpCategory category() { return OpCategory.BINARY_EWISE; }
			@Override public Set<String> opcodes() { return Set.of(OpOp2.PLUS.toString()); }
			@Override public java.util.Optional<org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeIndependentDecision>
				shapeIndependentDecision(OpSig sig) {
				return java.util.Optional.of(
					new org.apache.sysds.hops.fedplanner.rules.RulesApi.ShapeIndependentDecision(Set.of(0)));
			}
			@Override public OpCaps caps(OpSig sig, List<FType> inputs, ShapeHint hint) {
				hint.rows();
				return OpCaps.newBuilder().category(sig.category()).opcode(sig.opcode())
					.exec(ExecType.FED).placement(FederatedOutput.FOUT)
					.fout(true, FType.ROW).reason(ReasonCode.OK).build();
			}
		});
		BinaryOp plus = new BinaryOp("plus-proof", DataType.MATRIX, ValueType.FP64,
			OpOp2.PLUS, matrix("PA"), matrix("PB"));
		var relation = new OracleFacade(registry).prepareDecision(plus)
			.prepareExecutionRelation(List.of(List.of(FType.ROW), List.of(FType.COL))).orElseThrow();

		try {
			relation.regions().get(0).evidence();
			Assert.fail("shape-dependent evidence must not enter a shape-independent relation");
		}
		catch(IllegalStateException expected) {
			Assert.assertTrue(expected.getMessage().contains("undeclared ShapeHint"));
		}
	}

	private static OracleFacade facade() {
		return new OracleFacade(RulesCore.RulesModule.createDefaultRegistry());
	}

	private static void assertPartialMatchesCompleteOracle(QuaternaryOp operation) {
		List<List<FType>> domains = weightedDomains(operation);
		OracleFacade.PreparedDecision referenceOracle = facade().prepareDecision(operation);
		Set<List<FType>> reference = new LinkedHashSet<>();
		PlacementCandidateGenerator.forEachInputCombination(domains, FED_REQUIRED, tuple -> {
			if(referenceOracle.decideWithEvidence(tuple, null).caps().exec() == ExecType.FED)
				reference.add(tuple);
		}, null);
		List<List<FType>> actual = new ArrayList<>();
		PlacementCandidateGenerator.forEachInputCombination(domains, FED_REQUIRED,
			facade().prepareDecision(operation), actual::add, null);
		Assert.assertEquals(operation.getOp().name(), reference, new LinkedHashSet<>(actual));
	}

	private static List<List<FType>> weightedDomains(QuaternaryOp operation) {
		List<List<FType>> domains = new ArrayList<>();
		domains.add(Arrays.asList(null, FType.ROW, FType.COL, FType.FULL,
			FType.PART, FType.BROADCAST));
		for(int position = 1; position < operation.getInput().size(); position++)
			domains.add(operation.getInput().get(position).getDataType().isMatrix()
				? Arrays.asList(null, FType.BROADCAST)
				: Arrays.asList((FType) null));
		return domains;
	}

	private static List<List<FType>> weightedRelationDomains(QuaternaryOp operation) {
		List<FType> allRuntimeTypes = new ArrayList<>();
		allRuntimeTypes.add(null);
		allRuntimeTypes.addAll(Arrays.asList(FType.values()));
		List<List<FType>> domains = new ArrayList<>();
		for(int position = 0; position < operation.getInput().size(); position++)
			domains.add(operation.getInput().get(position).getDataType().isMatrix()
				? new ArrayList<>(allRuntimeTypes)
				: Arrays.asList((FType) null));
		return domains;
	}

	private static void assertExecutionRelationMatchesForwardOracle(
		org.apache.sysds.hops.Hop operation, List<List<FType>> domains) {
		OracleFacade.PreparedDecision forward = facade().prepareDecision(operation);
		OracleFacade.PreparedDecision relationOwner = facade().prepareDecision(operation);
		var relation = relationOwner.prepareExecutionRelation(domains).orElseThrow();
		long logicalTuples = domains.stream().mapToLong(List::size).reduce(1L, (left, right) -> left * right);
		var regions = relation.regions();
		Assert.assertTrue("determinant relation must never exceed tuple-product Oracle evaluations",
			relation.oracleEvaluations() <= logicalTuples);
		enumerateIndexed(domains, 0, new ArrayList<>(), new ArrayList<>(), (tuple, ordinals) -> {
			var expected = forward.decideWithEvidence(tuple, new ShapeHint(8, 4, 1000));
			var actual = relation.evidenceFor(tuple);
			assertCapsEqual(expected.caps(), actual.caps());
			Assert.assertEquals(expected.shapeProof(), actual.shapeProof());
			Assert.assertTrue("shape-independent relation evidence must have an empty proof",
				actual.shapeProof().requiredFacts().isEmpty());
			long covering = regions.stream().filter(region -> {
				for(int position = 0; position < ordinals.size(); position++)
					if(!region.allowedOptionOrdinals().get(position).contains(ordinals.get(position)))
						return false;
				return true;
			}).count();
			Assert.assertEquals("execution rectangles must be disjoint and complete", 1, covering);
		});
	}

	private static void assertCapsEqual(OpCaps expected, OpCaps actual) {
		Assert.assertEquals(expected.category(), actual.category());
		Assert.assertEquals(expected.opcode(), actual.opcode());
		Assert.assertEquals(expected.exec(), actual.exec());
		Assert.assertEquals(expected.placement(), actual.placement());
		Assert.assertEquals(expected.foutFType(), actual.foutFType());
		Assert.assertEquals(expected.reason(), actual.reason());
		Assert.assertEquals(expected.detail(), actual.detail());
		Assert.assertEquals(expected.notes().stream()
			.map(note -> note.code() + ":" + note.message()).toList(), actual.notes().stream()
				.map(note -> note.code() + ":" + note.message()).toList());
	}

	private static void enumerateIndexed(List<List<FType>> domains, int position,
		List<FType> tuple, List<Integer> ordinals, IndexedTupleConsumer consumer) {
		if(position == domains.size()) {
			consumer.accept(new ArrayList<>(tuple), new ArrayList<>(ordinals));
			return;
		}
		for(int ordinal = 0; ordinal < domains.get(position).size(); ordinal++) {
			tuple.add(domains.get(position).get(ordinal));
			ordinals.add(ordinal);
			enumerateIndexed(domains, position + 1, tuple, ordinals, consumer);
			tuple.remove(tuple.size() - 1);
			ordinals.remove(ordinals.size() - 1);
		}
	}

	@FunctionalInterface
	private interface IndexedTupleConsumer {
		void accept(List<FType> tuple, List<Integer> ordinals);
	}

	private static DataOp matrix(String name) {
		return new DataOp(name, DataType.MATRIX, ValueType.FP64,
			OpOpData.TRANSIENTREAD, name, 8, 4, 32, 1000);
	}
}
