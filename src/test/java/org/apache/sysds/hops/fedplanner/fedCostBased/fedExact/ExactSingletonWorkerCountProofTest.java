/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.common.Types.OpOp4;
import org.apache.sysds.hops.QuaternaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.FType;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.CampaignBPlacementAnalysisFixtureBridge;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionRealization;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateEmissionFact;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateInputState;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRealizationSupportClause;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.CandidateRuleKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.IndependentSupportAxis;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.IndependentSupportProduct;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionState;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationInputBinding;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateRealizationReference;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ControlRegionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementState;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.junit.Assert;
import org.junit.Test;

/** Exact one-worker proof parity and recursive-work contract. */
public class ExactSingletonWorkerCountProofTest {
	private static final PlacementState FED_FOUT = new PlacementState(
		ExecType.FED, FederatedOutput.FOUT, FType.FULL, false);
	private static final PlacementEmissionState EMISSION = new PlacementEmissionState(FED_FOUT, false);
	@Test
	public void singletonProofSkipsRecursiveCountsAndPreservesEveryCostCell() throws Exception {
		PlacementAnalysis analysis = oneWorkerAnalysis();
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var referenceWork = ExactPhysicalCostModel.workerCountWorkStatisticsForTest(
			analysis, model, false);
		var singletonWork = ExactPhysicalCostModel.workerCountWorkStatisticsForTest(
			analysis, model, true);

		Assert.assertTrue("complete one-worker authority must certify the fast path",
			singletonWork.singletonCertified());
		Assert.assertTrue("cost queries must bypass recursive support traversal",
			singletonWork.singletonBypasses() > 0);
		Assert.assertEquals(0, singletonWork.sourceAggregateComputations());
		Assert.assertTrue("proof must validate exact source-reference authority",
			singletonWork.singletonValidatedReferences() > 0);
		Assert.assertTrue("proof must inspect concrete durable worker pools",
			singletonWork.singletonValidatedAnchors() > 0);

		ExactPhysicalCostModel.PhysicalCostSurface reference =
			ExactPhysicalCostModel.physicalCostSurfaceWithoutSingletonWorkerProofForTest(analysis, model);
		ExactPhysicalCostModel.PhysicalCostSurface optimized =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		Assert.assertEquals(reference.contributionFingerprint(), optimized.contributionFingerprint());
		Assert.assertEquals(reference.transferKeys(), optimized.transferKeys());
		Assert.assertEquals(reference.supplySharingGroups(), optimized.supplySharingGroups());
		Assert.assertEquals(reference.contributions().stream().map(
			ExactPhysicalCostModel.PhysicalContribution::id).toList(), optimized.contributions().stream()
				.map(ExactPhysicalCostModel.PhysicalContribution::id).toList());
		for(int factor = 0; factor < reference.contributions().size(); factor++)
			assertRawFactor(reference.contributions().get(factor).factor(),
				optimized.contributions().get(factor).factor());
	}

	@Test
	public void factorizedProofScansAxesWithoutMaterializingTheirProduct() {
		PlacementAnalysis analysis = emptyMockAnalysis();
		List<List<CandidateRealizationInputBinding>> factors = new ArrayList<>();
		Map<CandidateRealizationReference,CandidateEmissionRealization> sources = new HashMap<>();
		for(int axis = 0; axis < 3; axis++) {
			List<CandidateRealizationInputBinding> options = new ArrayList<>();
			for(int option = 0; option < 20; option++) {
				CandidateRuleKey sourceRule = rule("factor-" + axis + '-' + option);
				CandidateEmissionRealization source = durable("factor-" + axis + '-' + option);
				CandidateRealizationReference reference = CandidateRealizationReference.of(sourceRule, source);
				sources.put(reference, source);
				options.add(CandidateRealizationInputBinding.direct(axis, reference));
			}
			factors.add(options);
		}
		CandidateEmissionRealization key = CandidateEmissionRealization.valueMap(
			EMISSION, "factor-root", List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateEmissionRealization factorized = CampaignBPlacementAnalysisFixtureBridge.factorizedRealization(
			key.key(), List.of(), factors);
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			sources.get((CandidateRealizationReference)invocation.getArgument(0)));
		Assert.assertEquals(0, CampaignBPlacementAnalysisFixtureBridge
			.materializedFactorizedClauseCount(factorized));
		Assert.assertTrue(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis, mockModel(mockAlternative(factorized)), 1));
		Assert.assertEquals("the 8,000-member relation must remain unmaterialized", 0,
			CampaignBPlacementAnalysisFixtureBridge.materializedFactorizedClauseCount(factorized));

		List<CandidateRealizationReference> indexedSources = sources.keySet().stream().limit(4).toList();
		CandidateEmissionRealization indexed = CandidateEmissionRealization.valueMap(EMISSION,
			"indexed-root", List.of(
				new CandidateRealizationSupportClause(List.of(), List.of(
					CandidateRealizationInputBinding.direct(0, indexedSources.get(0)),
					CandidateRealizationInputBinding.direct(1, indexedSources.get(1)))),
				new CandidateRealizationSupportClause(List.of(), List.of(
					CandidateRealizationInputBinding.direct(0, indexedSources.get(2)),
					CandidateRealizationInputBinding.direct(1, indexedSources.get(3))))))
			.withIndexedSupport();
		Assert.assertEquals(0, indexed.fullyMaterializedSupportClauseCount());
		Assert.assertTrue(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis, mockModel(mockAlternative(indexed)), 1));
		Assert.assertEquals("indexed metadata scan must not allocate clause handles",
			0, indexed.fullyMaterializedSupportClauseCount());
	}

	@Test
	public void referenceBudgetAndMalformedAuthorityFallBackToExactTraversal() {
		PlacementAnalysis analysis = emptyMockAnalysis();
		List<CandidateRealizationInputBinding> bindings = new ArrayList<>();
		for(int index = 0; index < 4100; index++) {
			CandidateRuleKey sourceRule = rule("bounded-source-" + index);
			bindings.add(CandidateRealizationInputBinding.direct(index,
				CandidateRealizationReference.of(sourceRule, durable("bounded-source-" + index))));
		}
		CandidateEmissionRealization bounded = CandidateEmissionRealization.valueMap(
			EMISSION, "bounded-root", List.of(new CandidateRealizationSupportClause(List.of(), bindings)));
		ExactPhysicalModel.Alternative boundedAlternative = mockAlternative(bounded);
		Assert.assertFalse("proof budget exhaustion must retain the original path",
			ExactPhysicalCostModel.singletonWorkerProofForTest(
				analysis, mockModel(boundedAlternative), 1, 32));

		CandidateRuleKey missingRule = rule("missing-source");
		CandidateEmissionRealization missingKey = CandidateEmissionRealization.valueMap(
			EMISSION, "missing-source", List.of(new CandidateRealizationSupportClause(List.of(), List.of())));
		CandidateRealizationReference missing = CandidateRealizationReference.of(missingRule, missingKey);
		CandidateEmissionRealization malformed = CandidateEmissionRealization.valueMap(EMISSION,
			"malformed-root", List.of(new CandidateRealizationSupportClause(List.of(),
				List.of(CandidateRealizationInputBinding.direct(0, missing)))));
		ExactPhysicalModel.Alternative malformedAlternative = mockAlternative(malformed);
		when(malformedAlternative.supportClause()).thenReturn(malformed.supportClauses().get(0));
		when(analysis.requireExactCandidateRealization(any())).thenThrow(
			new IllegalArgumentException("missing exact authority"));
		Assert.assertFalse("eager proof must fail closed instead of accepting missing authority",
			ExactPhysicalCostModel.singletonWorkerProofForTest(
				analysis, mockModel(malformedAlternative), 1));
		try {
			ExactPhysicalCostModel.realizationWorkerCount(analysis, malformedAlternative, 1);
			Assert.fail("the original active traversal must still validate missing authority");
		}
		catch(IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("missing exact authority"));
		}
	}

	@Test
	public void anchoredExecutionOverridesMustValidateTheirSelectedSupport() {
		PlacementAnalysis analysis = emptyMockAnalysis();
		CandidateEmissionRealization anchored = durable("anchored-output");
		DurableAnchorKey twoWorkers = new DurableAnchorKey("two-worker-support", FType.FULL, List.of(
			new AnchorPartition("worker-0", List.of(0L, 0L), List.of(1L, 1L)),
			new AnchorPartition("worker-1", List.of(1L, 0L), List.of(2L, 1L))));
		CandidateRuleKey supportRule = rule("derived-support");
		CandidateEmissionRealization support = CandidateEmissionRealization.durable(
			EMISSION, twoWorkers, List.of(), List.of());
		CandidateRealizationSupportClause derivedSupport = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(supportRule, support))));
		ExactPhysicalModel.Alternative derived = mockAlternative(anchored);
		when(derived.supportClause()).thenReturn(derivedSupport);
		when(derived.captured()).thenReturn(true);
		CandidateEmissionFact derivedEmission = mock(CandidateEmissionFact.class);
		when(derivedEmission.emissionState()).thenReturn(new PlacementEmissionState(FED_FOUT, true));
		when(derived.candidateEmission()).thenReturn(derivedEmission);
		when(analysis.requireExactCandidateRealization(any())).thenReturn(support);
		Assert.assertFalse(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis, mockModel(derived), 1));
		Assert.assertEquals(2, ExactPhysicalCostModel.executionWorkerCount(analysis, derived, 1));

		CandidateRuleKey weightsRule = rule("wdivmm-weights");
		PlacementEmissionState localOutput = new PlacementEmissionState(new PlacementState(
			ExecType.FED, FederatedOutput.LOUT, FType.FULL, false), false);
		CandidateEmissionRealization weights = CandidateEmissionRealization.local(localOutput,
			List.of(), List.of(CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(supportRule, support))));
		CandidateRealizationSupportClause wdivmmSupport = new CandidateRealizationSupportClause(
			List.of(), List.of(CandidateRealizationInputBinding.direct(0,
				CandidateRealizationReference.of(weightsRule, weights))));
		ExactPhysicalModel.Alternative wdivmm = mockAlternative(anchored);
		CompiledHopKey wdivmmOwner = rule("wdivmm-owner").parentOccurrence();
		when(wdivmm.decision()).thenReturn(wdivmmOwner);
		when(wdivmm.supportClause()).thenReturn(wdivmmSupport);
		QuaternaryOp hop = mock(QuaternaryOp.class);
		when(hop.getOp()).thenReturn(OpOp4.WDIVMM);
		when(analysis.hop(wdivmmOwner)).thenReturn(java.util.Optional.of(hop));
		when(analysis.requireExactCandidateRealization(any())).thenAnswer(invocation ->
			((CandidateRealizationReference)invocation.getArgument(0)).rule().parentOccurrence()
				== weightsRule.parentOccurrence() ? weights : support);
		Assert.assertFalse(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis, mockModel(wdivmm), 1));
		Assert.assertEquals(2, ExactPhysicalCostModel.executionWorkerCount(analysis, wdivmm, 1));

		ExactPhysicalModel.Alternative compactWdivmm = mockAlternative(anchored);
		when(compactWdivmm.decision()).thenReturn(wdivmmOwner);
		when(compactWdivmm.supportClause()).thenReturn(wdivmmSupport);
		IndependentSupportProduct compact = mock(IndependentSupportProduct.class);
		IndependentSupportAxis weightsAxis = mock(IndependentSupportAxis.class);
		when(weightsAxis.inputPosition()).thenReturn(0);
		when(weightsAxis.deliveredAnchor()).thenReturn(twoWorkers);
		when(compact.nativeWorkerPoolWitness()).thenReturn(anchored.anchor());
		when(compact.axes()).thenReturn(List.of(weightsAxis));
		when(compactWdivmm.compactSupport()).thenReturn(compact);
		Assert.assertFalse(ExactPhysicalCostModel.singletonWorkerProofForTest(
			analysis, mockModel(compactWdivmm), 1));
		Assert.assertEquals(2,
			ExactPhysicalCostModel.executionWorkerCount(analysis, compactWdivmm, 1));
	}

	private static void assertRawFactor(ExactCategoricalSolver.Factor expected,
		ExactCategoricalSolver.Factor actual) {
		Assert.assertEquals(expected.scope(), actual.scope());
		int cells = expected.scope().stream().mapToInt(
			ExactCategoricalSolver.Variable::domainSize).reduce(1, Math::multiplyExact);
		int[] values = new int[expected.scope().size()];
		for(int cell = 0; cell < cells; cell++) {
			int remaining = cell;
			for(int axis = values.length - 1; axis >= 0; axis--) {
				int radix = expected.scope().get(axis).domainSize();
				values[axis] = remaining % radix;
				remaining /= radix;
			}
			Assert.assertEquals("raw factor mismatch at cell " + cell,
				Double.doubleToRawLongBits(expected.cost(values)),
				Double.doubleToRawLongBits(actual.cost(values)));
		}
	}

	private static PlacementAnalysis oneWorkerAnalysis() throws Exception {
		String script = String.join("\n",
			"A=federated(addresses=list(\"localhost:13001/A\"),ranges=list(list(0,0),list(16,8)));",
			"B=A+1;",
			"C=B*2;",
			"D=C+B;",
			"E=D/(B+1);",
			"print(sum(E));") + "\n";
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(
			program, Privacy.PRIVATE_AGGREGATE);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static PlacementAnalysis emptyMockAnalysis() {
		PlacementAnalysis analysis = mock(PlacementAnalysis.class);
		NeutralPlacementGraph graph = mock(NeutralPlacementGraph.class);
		when(analysis.graph()).thenReturn(graph);
		when(graph.nodes()).thenReturn(List.of());
		when(graph.relocationActions()).thenReturn(List.of());
		when(graph.derivedFoutMaterializationActions()).thenReturn(List.of());
		return analysis;
	}

	private static ExactPhysicalModel mockModel(ExactPhysicalModel.Alternative alternative) {
		ExactPhysicalModel model = mock(ExactPhysicalModel.class);
		ExactPhysicalModel.DecisionDomain domain = mock(ExactPhysicalModel.DecisionDomain.class);
		when(domain.alternatives()).thenReturn(List.of(alternative));
		when(model.domains()).thenReturn(List.of(domain));
		return model;
	}

	private static ExactPhysicalModel.Alternative mockAlternative(
		CandidateEmissionRealization realization) {
		ExactPhysicalModel.Alternative alternative = mock(ExactPhysicalModel.Alternative.class);
		when(alternative.realization()).thenReturn(realization);
		when(alternative.inputAuthorities()).thenReturn(List.of());
		return alternative;
	}

	private static CandidateEmissionRealization durable(String id) {
		return CandidateEmissionRealization.durable(EMISSION,
			new DurableAnchorKey(id, FType.FULL,
				List.of(new AnchorPartition("worker-0", List.of(0L, 0L), List.of(1L, 1L)))),
			List.of(), List.of());
	}

	private static CandidateRuleKey rule(String name) {
		ControlRegionKey region = new ControlRegionKey(
			"singleton-worker-proof", "main", List.of("main"), "main", "compiled");
		CompiledHopKey owner = new CompiledHopKey("singleton-worker-proof", "main", "main",
			"compiled", region, name, name);
		return new CandidateRuleKey(owner, List.of(CandidateInputState.present(FType.FULL)));
	}
}
