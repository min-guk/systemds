/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.sysds.hops.fedplanner.placement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sysds.common.Types.ExecType;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.Node;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.HopOccurrenceProjection;
import org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransaction.FailureInjector;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResult;
import org.apache.sysds.hops.fedplanner.placement.adapter.NormalizedPlannerResults;
import org.apache.sysds.lops.compile.FederatedFoutMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedLocalMaterializeRegistry;
import org.apache.sysds.lops.compile.FederatedRefedRegistry;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.runtime.instructions.fed.FEDInstruction.FederatedOutput;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** Regression for exact CP/LOUT -&gt; CP/FOUT materialization authority. */
public class CampaignBG014CpFoutMaterializationAuthorityRedTest {
	@Before
	public void setUp() {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
	}

	@After
	public void tearDown() {
		PlacementEmissionTransaction.resetForTesting();
		clearRegistries();
	}

	@Test
	public void selectedGraphOwnedCpFoutWritesExactProducerMaterialization() throws Exception {
		FixtureProgram program = FixtureProgram.adopt(compileProgram());
		PlacementAnalysis analysis = new NeutralPlacementGraphBuilder().buildAnalysis(program);
		program.install(analysis);
		NormalizedPlannerResult baseline = new FedAllPlacementAdapter().select(analysis);
		CpFoutPlan selected = firstLegalCpFoutPlan(analysis, baseline);

		PlacementEmissionTransaction.emit(program, selected.plan(), FailureInjector.none());

		HopOccurrenceProjection producer = analysis.occurrences().stream()
			.filter(occurrence -> occurrence.key() == selected.producer().key())
			.findFirst().orElseThrow();
		var spec = FederatedFoutMaterializeRegistry.snapshot(producer.scopeId())
			.get(producer.hop().getHopID());
		Assert.assertNotNull("selected CP/FOUT must write exact producer FOUT materialization authority", spec);
		Assert.assertEquals(selected.state().fType().name(), spec.getFTypeHint());
		Assert.assertNotNull("CP/FOUT materialization must retain durable anchor authority", spec.getAnchorKey());
	}

	private static CpFoutPlan firstLegalCpFoutPlan(PlacementAnalysis analysis,
		NormalizedPlannerResult baseline) {
		List<String> diagnostics = new java.util.ArrayList<>();
		for(Node node : analysis.graph().decisionNodes()) {
			if(!analysis.isCompiledHopOccurrence(node.key()))
				continue;
			for(PlacementState state : node.legalAlternatives()) {
				if(state.execType() != ExecType.CP || state.output() != FederatedOutput.FOUT)
					continue;
				Map<CompiledHopKey,PlacementState> assignment = new LinkedHashMap<>(baseline.selectedStates());
				assignment.put(node.key(), state);
				if(!satisfiesConstraints(analysis, assignment)) {
					diagnostics.add(node.key().normalizedSignature() + " => constraint-conflict");
					continue;
				}
				try {
					CandidateSelections.Selection candidates = CandidateSelections.selectNativeCanonical(
						analysis, analysis.graph().relocationActions(), assignment);
					Map<CompiledHopKey,PlacementEmissionState> emissions = NormalizedPlannerResults.exactEmissionStates(
						analysis, assignment, candidates.candidates());
					NormalizedPlannerResult plan = NormalizedPlannerResults
						.createWithEmissionStatesAndCandidateSelections(analysis, "cp-fout-authority-red",
							emissions, candidates.candidates(), candidates.relocationChoices(),
							"selected-graph-owned-cp-fout");
					return new CpFoutPlan(node, state, plan);
				}
				catch(IllegalArgumentException | IllegalStateException ignored) {
					diagnostics.add(node.key().normalizedSignature() + " => "
						+ ignored.getClass().getSimpleName() + ':' + ignored.getMessage());
					// Try the next exact graph-owned CP/FOUT alternative.
				}
			}
		}
		throw new AssertionError("fixture must admit at least one complete selected CP/FOUT plan: "
			+ diagnostics);
	}

	private static boolean satisfiesConstraints(PlacementAnalysis analysis,
		Map<CompiledHopKey,PlacementState> assignment) {
		return analysis.graph().constraints().stream().allMatch(constraint ->
			NeutralPlacementGraph.constraintSatisfied(constraint,
				assignment.get(constraint.left()), assignment.get(constraint.right())));
	}

	private static DMLProgram compileProgram() throws Exception {
		return ProductionShadowFixtureFactory.compile("B-11");
	}

	private static void clearRegistries() {
		FederatedRefedRegistry.clear();
		FederatedFoutMaterializeRegistry.clear();
		FederatedLocalMaterializeRegistry.clear();
	}

	private record CpFoutPlan(Node producer, PlacementState state, NormalizedPlannerResult plan) { }

	private static final class FixtureProgram extends DMLProgram {
		private PlacementAnalysis authority;

		private FixtureProgram() {
			super(DMLProgram.DEFAULT_NAMESPACE);
		}

		private static FixtureProgram adopt(DMLProgram compiled) {
			FixtureProgram result = new FixtureProgram();
			result.getStatementBlocks().addAll(compiled.getStatementBlocks());
			return result;
		}

		private void install(PlacementAnalysis analysis) {
			authority = analysis;
		}

		@Override
		public PlacementAnalysis requirePlacementAnalysisAuthority() {
			if(authority == null)
				throw new IllegalStateException("fixture program has no placement authority");
			return authority;
		}

		@Override
		public void requirePlacementAnalysisAuthority(PlacementAnalysis candidate) {
			if(candidate != authority)
				throw new IllegalArgumentException("foreign fixture placement authority");
		}
	}
}
