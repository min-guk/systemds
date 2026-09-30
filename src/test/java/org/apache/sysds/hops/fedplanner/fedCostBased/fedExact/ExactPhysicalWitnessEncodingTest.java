/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sysds.hops.fedplanner.fedCostBased.fedExact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.common.Types.OpOp3;
import org.apache.sysds.hops.Hop;
import org.apache.sysds.hops.TernaryOp;
import org.apache.sysds.hops.fedplanner.FTypes.Privacy;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphBuilder;
import org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph.RelocationAction;
import org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.AnchorPartition;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateInputBindingKind;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.DurableAnchorKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.ObligationKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.RelocationActionKey;
import org.apache.sysds.hops.fedplanner.placement.PlacementIdentity;
import org.apache.sysds.hops.fedplanner.placement.adapter.FedAllPlacementAdapter;
import org.apache.sysds.parser.DMLProgram;
import org.apache.sysds.parser.DMLTranslator;
import org.apache.sysds.parser.ParserFactory;
import org.apache.sysds.parser.StatementBlock;
import org.apache.sysds.test.component.federated.placement.shadow.ProductionShadowFixtureFactory;
import org.junit.Assert;
import org.junit.Test;

/** Complete-witness diagnostic for SliceLine's protected CTABLE chain. */
public class ExactPhysicalWitnessEncodingTest {
	private static final String WORKER = "localhost:1234/X";

	@Test
	public void protectedSliceLineWitnessMapsUniquelyBeforeHardAndCostEvaluation()
		throws Exception {
		PlacementAnalysis analysis = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);
		var selected = new FedAllPlacementAdapter().select(analysis);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalCostModel.PhysicalCostSurface surface =
			ExactPhysicalCostModel.physicalCostSurface(analysis, model);
		AtomicBoolean costEvaluated = new AtomicBoolean();
		var evaluation = ExactPhysicalWitnessEncoding.evaluate(model, selected, () -> {
			costEvaluated.set(true);
			return surface.evaluateCanonical(
				ExactPhysicalWitnessEncoding.encode(model, selected).assignment());
		});

		Assert.assertNull("shared legal witness rejected by first DP hard factor: "
			+ evaluation.firstHardFailure(), evaluation.firstHardFailure());
		Assert.assertTrue("canonical cost must run only after every hard factor is finite",
			costEvaluated.get());
		Assert.assertNotNull(evaluation.canonicalCostBits());
		Assert.assertEquals(model.domains().size(), evaluation.witness().assignment().size());
		Assert.assertEquals(selected.selectedStates().size(),
			evaluation.witness().alternatives().size());

		ExactCategoricalSolver.Result feasible = model.solveLegalityOnly(
			ExactPhysicalOptimizer.PRODUCTION_LIMITS);
		Assert.assertEquals(model.domains().size(), feasible.assignmentInVariableOrder().size());
		LocalPhysicalOptimizer.Result optimized = LocalPhysicalOptimizer.optimize(model, surface);
		Assert.assertTrue("protected SliceLine CTABLE must have a finite DP-local objective",
			Double.isFinite(Double.longBitsToDouble(
				optimized.physicalResult().canonicalObjectiveBits())));
	}

	@Test
	public void firstHardFailureSuppressesCanonicalCostEvaluation() throws Exception {
		PlacementAnalysis analysis = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);
		var selected = new FedAllPlacementAdapter().select(analysis);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		var witness = ExactPhysicalWitnessEncoding.encode(model, selected);
		double[] rejected = new double[model.variables().get(0).domainSize()];
		java.util.Arrays.fill(rejected, Double.POSITIVE_INFINITY);
		AtomicBoolean costEvaluated = new AtomicBoolean();
		var evaluation = ExactPhysicalWitnessEncoding.evaluateHardThenCost(witness,
			model.variables(), List.of(ExactCategoricalSolver.Factor.dense(
				List.of(model.variables().get(0)), rejected)), () -> {
				costEvaluated.set(true);
				return 0L;
			});

		Assert.assertNotNull(evaluation.firstHardFailure());
		Assert.assertNull(evaluation.canonicalCostBits());
		Assert.assertFalse("cost callback must not run after a hard-factor rejection",
			costEvaluated.get());
	}

	@Test
	public void foreignAnalysisAndStaleCandidateAuthorityFailBeforeMatching() throws Exception {
		PlacementAnalysis analysis = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);
		PlacementAnalysis foreign = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);
		var selected = new FedAllPlacementAdapter().select(analysis);
		var foreignSelected = new FedAllPlacementAdapter().select(foreign);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		try {
			ExactPhysicalWitnessEncoding.encode(model, foreignSelected);
			Assert.fail("foreign analysis must fail closed");
		}
		catch(IllegalArgumentException ex) {
			Assert.assertEquals("EXACT_WITNESS_FOREIGN_ANALYSIS", ex.getMessage());
		}

		List<org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CandidateSelectionReceipt>
			staleReceipts = new ArrayList<>(selected.selectedCandidateSelections());
		staleReceipts.set(0, foreignSelected.selectedCandidateSelections().get(0));
		var stale = new FedAllPlacementAdapter.Result(analysis, selected.assignment(), staleReceipts,
			selected.selectedRelocationChoices(), selected.selectedRelocations(), selected.score(),
			selected.certificate(), selected.analysisFingerprint(), selected.normalizedPlanFingerprint());
		try {
			ExactPhysicalWitnessEncoding.encode(model, stale);
			Assert.fail("foreign receipt objects must not match by structural equality");
		}
		catch(IllegalArgumentException ex) {
			Assert.assertTrue(ex.getMessage(),
				ex.getMessage().startsWith("EXACT_WITNESS_STALE_CANDIDATE_AUTHORITY|"));
		}
	}

	@Test
	public void retaggedMaterializationComparisonDoesNotAcceptForeignWorkerEndpoint()
		throws Exception {
		PlacementAnalysis analysis = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);
		var selected = new FedAllPlacementAdapter().select(analysis);
		var ctable = analysis.occurrences().stream().filter(occurrence ->
			occurrence.hop() instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE)
			.findFirst().orElseThrow();
		RelocationAction action = analysis.graph().relocationActions().stream()
			.filter(candidate -> candidate.key().materializationFType()
				== org.apache.sysds.hops.fedplanner.FTypes.FType.ROW)
			.filter(candidate -> candidate.obligations().stream().anyMatch(obligation ->
				obligation.consumer() == ctable.key() && obligation.inputPosition() == 1
					&& obligation.requiredPlacement().equals(selected.selectedStates().get(ctable.key()))))
			.findFirst().orElseThrow();
		Assert.assertFalse("same endpoint and selected direct binding must not activate REFED",
			analysis.graph().isRelocationActive(action, selected.selectedStates(),
				selected.selectedCandidateSelections()));
		RelocationAction equalDistinctAction = actionWithAnchor(action, action.key().durableAnchor());
		Assert.assertNotSame(action, equalDistinctAction);
		Assert.assertNotSame(action.key(), equalDistinctAction.key());
		Assert.assertEquals(action, equalDistinctAction);
		Assert.assertFalse("equal-but-distinct actions retain the public API contract",
			analysis.graph().isRelocationActive(equalDistinctAction, selected.selectedStates(),
				selected.selectedCandidateSelections()));
		var preparedView = new org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraph
			.RelocationEvaluationView() {
				@Override
				public org.apache.sysds.hops.fedplanner.placement.PlacementState state(
					org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey decision) {
					return selected.selectedStates().get(decision);
				}

				@Override
				public int candidateSlotCount() {
					return selected.selectedCandidateSelections().size();
				}

				@Override
				public CandidateSelectionReceipt candidateAt(int slot) {
					return selected.selectedCandidateSelections().get(slot);
				}
			};
		Assert.assertEquals("prepared and existing relocation APIs must agree",
			analysis.graph().isRelocationActive(equalDistinctAction, selected.selectedStates(),
				selected.selectedCandidateSelections()),
			analysis.graph().isRelocationActive(equalDistinctAction, preparedView));
		CandidateSelectionReceipt directConsumer = selected.selectedCandidateSelections().stream()
			.filter(receipt -> receipt.rule().parentOccurrence() == ctable.key())
			.findFirst().orElseThrow();
		RelocationWitness relocationWitness = relocationReceipt(analysis, ctable.key());
		CandidateSelectionReceipt explicitRelocation = relocationWitness.receipt();
		List<CandidateSelectionReceipt> withExplicitRelocation = new ArrayList<>(
			selected.selectedCandidateSelections());
		withExplicitRelocation.remove(directConsumer);
		withExplicitRelocation.add(explicitRelocation);
		var withRelocationState = new IdentityHashMap<>(selected.selectedStates());
		withRelocationState.put(ctable.key(),
			explicitRelocation.emission().emissionState().placementState());
		Assert.assertTrue("an explicit relocation support binding must keep its action active",
			analysis.graph().isRelocationActive(relocationWitness.action(), withRelocationState,
				withExplicitRelocation));

		DurableAnchorKey original = action.key().durableAnchor();
		var sourceReceipt = selected.selectedCandidateSelections().stream().filter(receipt ->
			analysis.graph().node(receipt.rule().parentOccurrence()).orElseThrow().valueVersion()
				.equals(action.key().sourceValueVersion())).findFirst().orElseThrow();
		DurableAnchorKey residency = sourceReceipt.nativeWorkerPoolResidencyWitness();
		Assert.assertNotNull("fixture requires exact selected native endpoint authority", residency);
		Assert.assertFalse("global endpoint equality must remain FType-strict",
			PlacementIdentity.samePhysicalWorkerEndpoints(residency, original));
		List<AnchorPartition> foreignPartitions = original.partitions().stream()
			.map(partition -> new AnchorPartition("localhost:9876/foreign",
				partition.begin(), partition.end())).toList();
		DurableAnchorKey foreignAnchor = new DurableAnchorKey("foreign-endpoint",
			action.key().materializationFType(), foreignPartitions);
		Assert.assertFalse("materialization FType retagging must not erase endpoint identity",
			PlacementIdentity.samePhysicalWorkerEndpoints(residency, foreignAnchor));
		RelocationAction foreignAction = actionWithAnchor(action, foreignAnchor);
		Assert.assertTrue("foreign endpoints must still require materialization in the public API",
			analysis.graph().isRelocationActive(foreignAction, selected.selectedStates(),
				selected.selectedCandidateSelections()));
		Assert.assertTrue("the prepared API must not hide a foreign endpoint transfer either",
			analysis.graph().isRelocationActive(foreignAction, preparedView));
	}

	private static RelocationAction actionWithAnchor(RelocationAction action, DurableAnchorKey anchor) {
		var old = action.key();
		var key = new RelocationActionKey(old.sourceValueVersion(), old.targetPlacement(),
			old.materializationFType(), anchor, old.statementBlockScope(), old.compatibleConsumers());
		var obligations = action.obligations().stream().map(obligation -> new ObligationKey(
			obligation.consumer(), obligation.inputPosition(), obligation.sourceValueVersion(),
			obligation.requiredPlacement(), key, obligation.callRecompileContext())).toList();
		return new RelocationAction(key, obligations, action.directSourcePlacements());
	}

	private record RelocationWitness(CandidateSelectionReceipt receipt, RelocationAction action) { }

	private static RelocationWitness relocationReceipt(PlacementAnalysis analysis,
		org.apache.sysds.hops.fedplanner.placement.PlacementIdentity.CompiledHopKey owner) {
		for(var rule : analysis.candidateRuleFacts().orderedFactsForParent(owner))
			for(var emission : rule.allowedEmissionFacts()) {
				for(var realization : emission.realizations())
					for(var clause : realization.supportClauses())
						for(var binding : clause.inputBindings())
							if(binding.kind() == CandidateInputBindingKind.RELOCATION) {
								RelocationAction action = analysis.graph().relocationActions().stream()
									.filter(candidate -> candidate.key().equals(binding.relocationAction()))
									.findFirst().orElseThrow();
								return new RelocationWitness(new CandidateSelectionReceipt(rule.key(), emission,
									realization, clause, List.of()), action);
							}
			}
		throw new AssertionError("fixture requires explicit relocation-backed CTABLE receipt");
	}

	@Test
	public void zeroAndMultipleAlternativeMatchesHaveDistinctDiagnostics() throws Exception {
		PlacementAnalysis analysis = analyze(sliceLineCtableScript(), Privacy.PRIVATE_AGGREGATE);
		ExactPhysicalModel model = ExactPhysicalModel.build(analysis);
		ExactPhysicalModel.DecisionDomain domain = model.domains().get(0);
		try {
			ExactPhysicalWitnessEncoding.requireUnique(domain.node().key(), null, domain, List.of());
			Assert.fail("zero matches must fail closed");
		}
		catch(IllegalArgumentException ex) {
			Assert.assertTrue(ex.getMessage(),
				ex.getMessage().startsWith("EXACT_WITNESS_ALTERNATIVE_MISSING|"));
			Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("|alternatives="));
		}
		try {
			ExactPhysicalWitnessEncoding.requireUnique(domain.node().key(), null, domain,
				List.of(0, 0));
			Assert.fail("multiple matches must fail closed");
		}
		catch(IllegalArgumentException ex) {
			Assert.assertTrue(ex.getMessage(),
				ex.getMessage().startsWith("EXACT_WITNESS_ALTERNATIVE_AMBIGUOUS|"));
			Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("|matches=[0, 0]"));
		}
	}

	private static String sliceLineCtableScript() {
		return "X=federated(addresses=list(\"" + WORKER + "\"),"
			+ "ranges=list(list(0,0),list(32561,13)));"
			+ "m=nrow(X);n=ncol(X);"
			+ "fdom=colMaxs(X);"
			+ "foffb=t(cumsum(t(fdom)))-fdom;"
			+ "foffe=t(cumsum(t(fdom)));"
			+ "rix=matrix(seq(1,m)%*%matrix(1,1,n),m*n,1);"
			+ "cix=matrix(X+foffb,m*n,1);"
			+ "X2=table(rix,cix,1,m,as.scalar(foffe[,n]),FALSE);"
			+ "e=federated(addresses=list(\"localhost:1234/e\"),"
			+ "ranges=list(list(0,0),list(32561,1)));"
			+ "Y=X2*e;"
			+ "print(max(colSums(Y)));";
	}

	private static PlacementAnalysis analyze(String script, Privacy privacy) throws Exception {
		DMLProgram program = ParserFactory.createParser().parse(
			DMLScript.DML_FILE_PATH_ANTLR_PARSER, script, new HashMap<>());
		DMLTranslator translator = new DMLTranslator(program);
		translator.liveVariableAnalysis(program);
		translator.validateParseTree(program);
		translator.constructHops(program);
		translator.rewriteHopsDAG(program);
		Assert.assertTrue("fixture must retain CTABLE", containsCtable(program));
		ProductionShadowFixtureFactory.registerHermeticSourcePrivacy(program, privacy);
		return new NeutralPlacementGraphBuilder().buildAnalysis(program);
	}

	private static boolean containsCtable(DMLProgram program) {
		ArrayDeque<Hop> pending = new ArrayDeque<>();
		for(StatementBlock block : program.getStatementBlocks())
			if(block.getHops() != null)
				pending.addAll(block.getHops());
		Set<Hop> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		while(!pending.isEmpty()) {
			Hop hop = pending.removeFirst();
			if(!visited.add(hop))
				continue;
			if(hop instanceof TernaryOp ternary && ternary.getOp() == OpOp3.CTABLE)
				return true;
			pending.addAll(hop.getInput());
		}
		return false;
	}
}
